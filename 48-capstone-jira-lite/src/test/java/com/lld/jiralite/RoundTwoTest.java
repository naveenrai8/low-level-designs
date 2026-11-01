package com.lld.jiralite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lld.workflow.WorkflowException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Round 2 (15 to 30 min): the search seam, permission-filtered results, and what the index promises. */
class RoundTwoTest {

    /** Builds a small world: three WEB issues and one OPS issue with known words, labels and assignees. */
    private static Rig seeded(boolean ryw) {
        Rig r = new Rig(ryw);
        r.jira.createIssue("bob", "WEB", "Login page crash", "bob", Set.of("Bug", "frontend"));
        r.jira.createIssue("bob", "WEB", "Add dark mode", "carol", Set.of("feature", "frontend"));
        r.jira.createIssue("alice", "WEB", "Crash on logout", "bob", Set.of("bug"));
        r.jira.createIssue("dave", "OPS", "Disk crash alert", "dave", Set.of("bug"));
        return r;
    }

    /** Each filter works alone and together: text (all words must match), status, assignee and label (case-insensitive). */
    @Test
    void filtersWork() {
        Rig r = seeded(true);
        assertEquals(List.of("WEB-1", "WEB-3"), r.jira.search("bob", null, null, null, "crash"));
        assertEquals(List.of("WEB-1"), r.jira.search("bob", null, null, null, "login CRASH"));
        assertEquals(List.of("WEB-1", "WEB-3"), r.jira.search("alice", null, "bob", null, null));
        assertEquals(List.of("OPS-1", "WEB-1", "WEB-3"), r.jira.search("alice", null, null, "BUG", null));
        r.jira.transition("bob", "WEB-1", "start", 0);
        assertEquals(List.of("WEB-1"), r.jira.search("alice", "IN_PROGRESS", null, null, null));
        assertEquals(List.of("WEB-1"), r.jira.search("alice", "IN_PROGRESS", "bob", "bug", "login"));
        assertEquals(List.of(), r.jira.search("alice", "DONE", null, null, null));
    }

    /** Search only returns projects the asker can browse; a stranger sees nothing and an empty allow-list is not "everything". */
    @Test
    void searchIsPermissionFiltered() {
        Rig r = seeded(true);
        assertEquals(List.of("OPS-1", "WEB-1", "WEB-3"), r.jira.search("alice", null, null, null, "crash"));
        assertEquals(List.of("OPS-1"), r.jira.search("dave", null, null, null, "crash"));
        assertEquals(List.of("WEB-1", "WEB-3"), r.jira.search("carol", null, null, null, "crash"));
        assertEquals(List.of(), r.jira.search("eve", null, null, null, "crash"));
        assertEquals(List.of(), r.jira.search("eve", null, null, null, null));
    }

    /** Comment words become searchable, and an issue the user cannot browse never matches even by a word only in its comment. */
    @Test
    void commentTextIsSearchableAndStillFiltered() {
        Rig r = seeded(true);
        r.jira.comment("bob", "WEB-2", "blocked by zebra");
        assertEquals(List.of("WEB-2"), r.jira.search("carol", null, null, null, "zebra"));
        assertEquals(List.of(), r.jira.search("dave", null, null, null, "zebra"));
    }

    /** With read-your-writes ON, a search right after a write sees it with no refresh and nothing is pending afterwards. */
    @Test
    void readYourWritesWhenPromised() {
        Rig r = seeded(true);
        r.jira.transition("bob", "WEB-1", "start", 0);
        assertEquals(List.of("WEB-1"), r.jira.search("bob", "IN_PROGRESS", null, null, null));
        assertEquals(0, r.jira.pendingIndexWrites());
    }

    /** With read-your-writes OFF the promise is only "after refresh": before it the search is stale, after it the search is exact. */
    @Test
    void lagUntilRefreshWhenNotPromised() {
        Rig r = seeded(false);
        assertEquals(List.of(), r.jira.search("alice", null, null, null, "crash"));
        assertEquals(4, r.jira.pendingIndexWrites());
        r.jira.refresh();
        assertEquals(List.of("OPS-1", "WEB-1", "WEB-3"), r.jira.search("alice", null, null, null, "crash"));
        r.jira.transition("bob", "WEB-1", "start", 0);
        assertEquals(List.of(), r.jira.search("alice", "IN_PROGRESS", null, null, null));
        r.jira.refresh();
        assertEquals(List.of("WEB-1"), r.jira.search("alice", "IN_PROGRESS", null, null, null));
    }

    /** A transition the guard rejects leaves the index and the pending list exactly as they were. */
    @Test
    void failedTransitionNeverReachesTheIndex() {
        Rig r = seeded(true);
        r.jira.transition("bob", "WEB-1", "start", 0);
        r.jira.refresh();
        assertThrows(WorkflowException.GuardFailed.class, () -> r.jira.transition("bob", "WEB-1", "done", 1));
        assertEquals(0, r.jira.pendingIndexWrites());
        assertEquals(List.of("WEB-1"), r.jira.search("alice", "IN_PROGRESS", null, null, null));
        assertEquals(List.of(), r.jira.search("alice", "DONE", null, null, null));
    }

    /** Permission and illegal-move failures also leave nothing pending. */
    @Test
    void deniedAndIllegalChangesLeaveNothingPending() {
        Rig r = seeded(false);
        r.jira.refresh();
        assertThrows(JiraException.PermissionDenied.class, () -> r.jira.transition("carol", "WEB-1", "start", 0));
        assertThrows(WorkflowException.IllegalTransition.class, () -> r.jira.transition("bob", "WEB-1", "done", 0));
        assertThrows(JiraException.IssueNotFound.class, () -> r.jira.transition("eve", "WEB-1", "start", 0));
        assertEquals(0, r.jira.pendingIndexWrites());
    }

    /** When the indexer is down, search refuses (no stale answer), the issue stays pending, and the next refresh after recovery catches up. */
    @Test
    void indexerOutageIsRetried() {
        Rig r = seeded(true);
        r.jira.refresh();
        r.jira.transition("bob", "WEB-1", "start", 0);
        r.jira.indexFault = () -> {
            throw new IllegalStateException("indexer down");
        };
        assertThrows(JiraException.IndexUnavailable.class, () -> r.jira.search("alice", null, null, null, null));
        assertEquals(1, r.jira.pendingIndexWrites());
        assertEquals("IN_PROGRESS", r.engine.get("WEB-1").state());
        r.jira.indexFault = () -> { };
        assertEquals(List.of("WEB-1"), r.jira.search("alice", "IN_PROGRESS", null, null, null));
        assertEquals(0, r.jira.pendingIndexWrites());
    }

    /** A change made straight on the engine (workflow automation) is noticed by the engine hook, so the index catches up too. */
    @Test
    void engineSideChangesAreIndexedThroughTheHook() {
        Rig r = seeded(false);
        r.jira.refresh();
        r.engine.transition("WEB-2", "start", "automation", 0);
        assertEquals(1, r.jira.pendingIndexWrites());
        r.jira.refresh();
        assertEquals(List.of("WEB-2"), r.index.search(new Query(Set.of("WEB"), "IN_PROGRESS", null, null, null)));
    }

    /** Delete takes the issue out of search right away (read-your-writes on). */
    @Test
    void deletedIssuesLeaveTheIndex() {
        Rig r = seeded(true);
        r.jira.delete("alice", "WEB-1");
        assertEquals(List.of("OPS-1", "WEB-3"), r.jira.search("alice", null, null, null, "crash"));
    }

    /** Replacing a document drops its old words: after the title changes nothing finds the old word. */
    @Test
    void replacingADocumentForgetsOldWords() {
        InvertedIndex ix = new InvertedIndex();
        ix.put(new IssueDoc("A-1", "A", "OPEN", "", Set.of(), "alpha beta"));
        ix.put(new IssueDoc("A-1", "A", "OPEN", "", Set.of(), "gamma"));
        Set<String> a = Set.of("A");
        assertEquals(List.of(), ix.search(new Query(a, null, null, null, "alpha")));
        assertEquals(List.of("A-1"), ix.search(new Query(a, null, null, null, "gamma")));
        ix.remove("A-1");
        ix.remove("A-1");
        assertEquals(List.of(), ix.search(new Query(a, null, null, null, null)));
    }

    /** Differential test: the inverted index agrees with a brute-force scan for 300 random queries over random put/replace/remove history, with random allow-lists. */
    @Test
    void indexMatchesBruteForce() {
        Random rnd = new Random(48);
        String[] words = {"login", "crash", "dark", "mode", "disk", "alert", "slow", "page"};
        String[] projects = {"A", "B", "C"};
        String[] statuses = {"OPEN", "DONE"};
        String[] people = {"", "x", "y"};
        String[] labels = {"bug", "ui", "ops"};
        InvertedIndex ix = new InvertedIndex();
        Map<String, IssueDoc> truth = new HashMap<>();
        for (int step = 0; step < 400; step++) {
            String id = "I-" + rnd.nextInt(40);
            if (rnd.nextInt(5) == 0) {
                ix.remove(id);
                truth.remove(id);
            } else {
                StringBuilder text = new StringBuilder();
                for (int w = rnd.nextInt(4); w >= 0; w--) {
                    text.append(words[rnd.nextInt(words.length)]).append(' ');
                }
                IssueDoc d = new IssueDoc(id, projects[rnd.nextInt(3)], statuses[rnd.nextInt(2)], people[rnd.nextInt(3)], Set.of(labels[rnd.nextInt(3)]), text.toString());
                ix.put(d);
                truth.put(id, d);
            }
        }
        for (int q = 0; q < 300; q++) {
            Set<String> allow = new HashSet<>();
            for (String p : projects) {
                if (rnd.nextBoolean()) {
                    allow.add(p);
                }
            }
            String st = rnd.nextInt(3) == 0 ? statuses[rnd.nextInt(2)] : null;
            String who = rnd.nextInt(3) == 0 ? people[rnd.nextInt(3)] : null;
            String lab = rnd.nextInt(3) == 0 ? labels[rnd.nextInt(3)] : null;
            String text = rnd.nextInt(2) == 0 ? words[rnd.nextInt(words.length)] + " " + words[rnd.nextInt(words.length)] : null;
            Set<String> expected = new TreeSet<>();
            for (IssueDoc d : truth.values()) {
                boolean ok = allow.contains(d.project()) && (st == null || st.equals(d.status())) && (who == null || who.equals(d.assignee()))
                        && (lab == null || d.labels().contains(lab));
                if (ok && text != null) {
                    ok = InvertedIndex.words(d.text()).containsAll(InvertedIndex.words(text));
                }
                if (ok) {
                    expected.add(d.id());
                }
            }
            assertEquals(new ArrayList<>(expected), ix.search(new Query(allow, st, who, lab, text)), "query " + q);
        }
    }
}
