package com.lld.jiralite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lld.popularity.Ranked;
import com.lld.popularity.WindowSpec;
import com.lld.workflow.InMemoryWorkflowEngine;
import com.lld.workflow.WorkflowDefinition;
import com.lld.workflow.WorkflowEngine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Traps found in review: short top-K, lost marks, dead hooks, check order, empty queries, lag-mode deletes, migration, and small contract details. Each test names the bug it pins. */
class HardeningTest {

    /** Builds a service on its own engine with a custom permission rule, for tests that need rights the fixed roles cannot express. */
    private static JiraService custom(PermissionScheme scheme, String... projects) {
        InMemoryWorkflowEngine engine = new InMemoryWorkflowEngine(Clock.fixed(Instant.parse("2026-01-05T12:00:00Z"), ZoneOffset.UTC), () -> 0L, Runnable::run, Long.MAX_VALUE, 5);
        JiraService j = new JiraService(engine, scheme, new InvertedIndex(), () -> 0L, WindowSpec.of(Duration.ofMinutes(1), Duration.ofMinutes(10)), true);
        for (String p : projects) {
            j.createProject(p, Rig.flow());
        }
        return j;
    }

    /** Runs the action after the service's own hook is replaced by a do-nothing hook, then refreshes and shows what the index holds. Proves the service marks changes itself. */
    private static Rig deadHookWorld(Consumer<Rig> action) {
        Rig r = new Rig(false);
        r.make("bob", "WEB", "First issue");
        r.jira.refresh();
        r.engine.addHook("jira-index", (event, actions) -> { });
        action.accept(r);
        r.jira.refresh();
        return r;
    }

    /** Short top-K trap: asking for k=1 after the leader was deleted must still return the next live issue, so the tracker must be asked for everything first. */
    @Test
    void trendingFiltersBeforeTakingTheFirstK() {
        Rig r = new Rig();
        r.make("bob", "WEB", "one", "bug");
        r.make("bob", "WEB", "two", "ui");
        for (int i = 0; i < 5; i++) {
            r.jira.get("bob", "WEB-1");
        }
        r.jira.get("bob", "WEB-2");
        r.jira.delete("alice", "WEB-1");
        assertEquals(List.of(new Ranked("WEB-2", 1)), r.jira.trendingIssues("bob", "WEB", 1));
    }

    /** Label trending with k=1 must still sum all issues of the label before it cuts the list. */
    @Test
    void trendingLabelsSumBeforeTakingTheFirstK() {
        Rig r = new Rig();
        r.make("bob", "WEB", "one", "bug");
        r.make("bob", "WEB", "two", "bug");
        r.make("bob", "WEB", "three", "ui");
        for (int i = 0; i < 2; i++) {
            r.jira.get("bob", "WEB-1");
            r.jira.get("bob", "WEB-2");
        }
        for (int i = 0; i < 3; i++) {
            r.jira.get("bob", "WEB-3");
        }
        assertEquals(List.of(new Ranked("bug", 4)), r.jira.trendingLabels("bob", "WEB", 1));
    }

    /** Move needs ADMINISTER in the source: a developer with CREATE and BROWSE in the target is still refused, and nothing moves. */
    @Test
    void moveNeedsAdministerInTheSource() {
        Rig r = new Rig();
        r.make("bob", "WEB", "t");
        r.scheme.assignRole("OPS", "bob", Role.DEVELOPER);
        assertThrows(JiraException.PermissionDenied.class, () -> r.jira.move("bob", "WEB-1", "OPS"));
        assertEquals("WEB-1", r.jira.get("bob", "WEB-1").id());
        assertEquals(List.of(), r.jira.search("bob", null, null, null, "t").stream().filter(id -> id.startsWith("OPS")).toList());
    }

    /** The service marks a transition dirty on its own, even if the engine hook is dead. */
    @Test
    void transitionIsIndexedWithoutTheHook() {
        Rig r = deadHookWorld(w -> w.jira.transition("bob", "WEB-1", "start", 0));
        assertEquals(List.of("WEB-1"), r.index.search(new Query(Set.of("WEB"), "IN_PROGRESS", null, null, null)));
    }

    /** The service marks a comment dirty on its own, even if the engine hook is dead. */
    @Test
    void commentIsIndexedWithoutTheHook() {
        Rig r = deadHookWorld(w -> w.jira.comment("bob", "WEB-1", "zebra"));
        assertEquals(List.of("WEB-1"), r.index.search(new Query(Set.of("WEB"), null, null, null, "zebra")));
    }

    /** The service marks an assign dirty on its own, even if the engine hook is dead. */
    @Test
    void assignIsIndexedWithoutTheHook() {
        Rig r = deadHookWorld(w -> w.jira.assign("bob", "WEB-1", "zoe"));
        assertEquals(List.of("WEB-1"), r.index.search(new Query(Set.of("WEB"), null, "zoe", null, null)));
    }

    /** The service marks a delete dirty on its own, so the index copy is cleaned even if the engine hook is dead. */
    @Test
    void deleteIsIndexedWithoutTheHook() {
        Rig r = deadHookWorld(w -> w.jira.delete("alice", "WEB-1"));
        assertEquals(List.of(), r.index.search(new Query(Set.of("WEB"), null, null, null, null)));
    }

    /** A move cleans the old key out of the index copy and puts the new key in, even if the engine hook is dead. */
    @Test
    void moveIsIndexedWithoutTheHook() {
        Rig r = deadHookWorld(w -> w.jira.move("alice", "WEB-1", "OPS"));
        assertEquals(List.of(), r.index.search(new Query(Set.of("WEB"), null, null, null, null)));
        assertEquals(List.of("OPS-1"), r.index.search(new Query(Set.of("OPS"), null, null, null, null)));
    }

    /** A refused assign (viewer) must not change the assignee, not even for a moment. */
    @Test
    void deniedAssignChangesNothing() {
        Rig r = new Rig();
        r.jira.createIssue("bob", "WEB", "t", "bob", Set.of());
        assertThrows(JiraException.PermissionDenied.class, () -> r.jira.assign("carol", "WEB-1", "carol"));
        assertEquals("bob", r.jira.get("bob", "WEB-1").assignee());
    }

    /** Assigning null means nobody and is stored like create does (empty text), so the filters behave the same. */
    @Test
    void nullAssigneeIsNormalised() {
        Rig r = new Rig();
        r.jira.createIssue("bob", "WEB", "t", "bob", Set.of());
        r.jira.assign("bob", "WEB-1", null);
        assertEquals("", r.jira.get("bob", "WEB-1").assignee());
        assertEquals(List.of("WEB-1"), r.jira.search("bob", null, "", null, null));
    }

    /** Strangers get IssueNotFound (not PermissionDenied) from assign, delete and move, and a deleted issue is not found a second time. */
    @Test
    void hiddenIssuesAreNotFoundOnEveryWritePath() {
        Rig r = new Rig();
        r.make("bob", "WEB", "t");
        assertThrows(JiraException.IssueNotFound.class, () -> r.jira.assign("eve", "WEB-1", "eve"));
        assertThrows(JiraException.IssueNotFound.class, () -> r.jira.delete("eve", "WEB-1"));
        assertThrows(JiraException.IssueNotFound.class, () -> r.jira.move("dave", "WEB-1", "OPS"));
        r.jira.delete("alice", "WEB-1");
        assertThrows(JiraException.IssueNotFound.class, () -> r.jira.delete("alice", "WEB-1"));
        assertThrows(JiraException.IssueNotFound.class, () -> r.jira.move("alice", "WEB-1", "OPS"));
    }

    /** Numbers and short words are searchable, and a leading space in the query does not create a phantom empty word. */
    @Test
    void tokenizerKeepsDigitsAndIgnoresPadding() {
        Rig r = new Rig();
        r.make("bob", "WEB", "HTTP 500 on checkout");
        assertEquals(List.of("WEB-1"), r.jira.search("bob", null, null, null, "500"));
        assertEquals(List.of("WEB-1"), r.jira.search("bob", null, null, null, "  checkout "));
    }

    /** A query with no words at all matches nothing (not everything); letters of other scripts count as words. */
    @Test
    void emptyTextQueryMatchesNothing() {
        Rig r = new Rig();
        r.make("bob", "WEB", "Café menu");
        r.make("bob", "WEB", "日本 login");
        assertEquals(List.of(), r.jira.search("bob", null, null, null, "!!!"));
        assertEquals(List.of(), r.jira.search("bob", null, null, null, "   "));
        assertEquals(List.of(), r.jira.search("bob", null, null, null, ""));
        assertEquals(List.of("WEB-1"), r.jira.search("bob", null, null, null, "café"));
        assertEquals(List.of("WEB-2"), r.jira.search("bob", null, null, null, "日本"));
        assertEquals(List.of("WEB-1", "WEB-2"), r.jira.search("bob", null, null, null, null));
    }

    /** Differential test with an independent hand-written tokenizer (a character loop), so a bug in {@code InvertedIndex.words} cannot hide behind itself. */
    @Test
    void indexMatchesAnIndependentOracle() {
        Random rnd = new Random(480);
        String[] words = {"login", "500", "café", "disk", "a1", "x", "ALERT"};
        String[] seps = {" ", "  ", ", ", "-", "/", "!"};
        InvertedIndex ix = new InvertedIndex();
        Map<String, String> texts = new HashMap<>();
        for (int i = 0; i < 30; i++) {
            StringBuilder t = new StringBuilder(seps[rnd.nextInt(seps.length)]);
            for (int w = rnd.nextInt(4); w >= 0; w--) {
                t.append(words[rnd.nextInt(words.length)]).append(seps[rnd.nextInt(seps.length)]);
            }
            texts.put("I-" + i, t.toString());
            ix.put(new IssueDoc("I-" + i, "A", "OPEN", "", Set.of(), t.toString()));
        }
        for (int q = 0; q < 200; q++) {
            String query = seps[rnd.nextInt(seps.length)] + words[rnd.nextInt(words.length)] + seps[rnd.nextInt(seps.length)] + words[rnd.nextInt(words.length)];
            Set<String> need = oracleWords(query);
            Set<String> expected = new TreeSet<>();
            texts.forEach((id, t) -> {
                if (oracleWords(t).containsAll(need)) {
                    expected.add(id);
                }
            });
            assertEquals(new ArrayList<>(expected), ix.search(new Query(Set.of("A"), null, null, null, query)), query);
        }
    }

    /** The oracle tokenizer: walks the characters and keeps runs of letters and digits, lower-cased. */
    private static Set<String> oracleWords(String text) {
        Set<String> out = new HashSet<>();
        StringBuilder cur = new StringBuilder();
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                cur.append(c);
            } else {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                }
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** Labels come back sorted, so the answer does not depend on hash order. */
    @Test
    void labelsAreSorted() {
        Rig r = new Rig();
        IssueDetails d = r.jira.createIssue("bob", "WEB", "t", null, new java.util.LinkedHashSet<>(List.of("zeta", "Alpha", "mid")));
        assertEquals(List.of("alpha", "mid", "zeta"), d.labels());
    }

    /** A index wrapper that runs a probe just before each put, so a test can make another thread's change happen exactly during an index write. */
    private static final class ProbeIndex implements SearchIndex {
        final InvertedIndex inner;
        Runnable beforePut = () -> { };

        /** Wraps the real in-memory index. */
        ProbeIndex(InvertedIndex inner) {
            this.inner = inner;
        }

        /** Runs the probe once, then forwards the write. */
        @Override
        public void put(IssueDoc doc) {
            Runnable p = beforePut;
            beforePut = () -> { };
            p.run();
            inner.put(doc);
        }

        /** Forwards the removal. */
        @Override
        public void remove(String id) {
            inner.remove(id);
        }

        /** Forwards the search. */
        @Override
        public List<String> search(Query query) {
            return inner.search(query);
        }
    }

    /** Lost-mark trap: a change that lands while the index write is in progress must stay marked, so the next refresh indexes the newer state (forced with a same-thread probe, no threads). */
    @Test
    void changeDuringAnIndexWriteIsNotLost() {
        ProbeIndex[] probe = new ProbeIndex[1];
        Rig r = new Rig(false, ix -> probe[0] = new ProbeIndex(ix));
        r.make("bob", "WEB", "First issue");
        probe[0].beforePut = () -> r.engine.transition("WEB-1", "start", "automation", WorkflowEngine.ANY_VERSION);
        r.jira.refresh();
        assertEquals(1, r.jira.pendingIndexWrites());
        r.jira.refresh();
        assertEquals(0, r.jira.pendingIndexWrites());
        assertEquals(List.of("WEB-1"), r.jira.search("bob", "IN_PROGRESS", null, null, null));
    }

    /** The outage error is generic: no hidden key and no internal text, which stay only in the cause. */
    @Test
    void outageMessageLeaksNothing() {
        Rig r = new Rig();
        r.make("bob", "WEB", "t");
        r.jira.indexFault = () -> {
            throw new IllegalStateException("indexer down");
        };
        JiraException e = assertThrows(JiraException.IndexUnavailable.class, () -> r.jira.search("eve", null, null, null, "t"));
        assertEquals("search is temporarily unavailable", e.getMessage());
        assertTrue(e.getCause().getMessage().contains("indexer down"));
        assertTrue(e.getCause().getMessage().length() > 0 && !e.getMessage().contains("WEB-1"));
    }

    /** Migration fires no engine hook, so the service marks the project's live issues dirty itself and search shows the new status. */
    @Test
    void migrationRefreshesTheIndex() {
        Rig r = new Rig(false);
        r.make("bob", "WEB", "t");
        r.jira.transition("bob", "WEB-1", "start", 0);
        r.jira.refresh();
        WorkflowDefinition next = WorkflowDefinition.builder().states("OPEN", "ACTIVE", "DONE").initial("OPEN")
                .transition("start", Set.of("OPEN"), "ACTIVE").transition("done", Set.of("ACTIVE"), "DONE").build();
        assertThrows(JiraException.PermissionDenied.class, () -> r.jira.migrate("bob", "WEB", next, Map.of("IN_PROGRESS", "ACTIVE")));
        assertEquals(1, r.jira.migrate("alice", "WEB", next, Map.of("IN_PROGRESS", "ACTIVE")));
        r.jira.refresh();
        assertEquals(List.of("WEB-1"), r.jira.search("alice", "ACTIVE", null, null, null));
        assertEquals(List.of(), r.jira.search("alice", "IN_PROGRESS", null, null, null));
    }

    /** In lag mode a deleted key is already gone from search before any refresh. */
    @Test
    void lagModeDeleteIsInvisibleAtOnce() {
        Rig r = new Rig(false);
        r.make("bob", "WEB", "t");
        r.jira.refresh();
        assertEquals(List.of("WEB-1"), r.jira.search("bob", null, null, null, "t"));
        r.jira.delete("alice", "WEB-1");
        assertEquals(List.of(), r.jira.search("bob", null, null, null, "t"));
    }

    /** In lag mode a moved issue is gone from the old project's search before any refresh, and the new key is not yet there. */
    @Test
    void lagModeMoveIsInvisibleAtOnce() {
        Rig r = new Rig(false);
        r.make("bob", "WEB", "t");
        r.jira.refresh();
        r.jira.move("alice", "WEB-1", "OPS");
        assertEquals(List.of(), r.jira.search("alice", null, null, null, "t"));
        r.jira.refresh();
        assertEquals(List.of("OPS-1"), r.jira.search("alice", null, null, null, "t"));
    }

    /** Create needs BROWSE too (no exception to "see it first"), and a move target needs BROWSE as well. */
    @Test
    void createAndMoveTargetNeedBrowse() {
        JiraService noBrowse = custom((p, u, a) -> a != Action.BROWSE, "WEB");
        assertThrows(JiraException.PermissionDenied.class, () -> noBrowse.createIssue("u", "WEB", "t", null, Set.of()));
        JiraService hidden = custom((p, u, a) -> !(p.equals("OPS") && a == Action.BROWSE), "WEB", "OPS");
        hidden.createIssue("u", "WEB", "t", null, Set.of());
        assertThrows(JiraException.PermissionDenied.class, () -> hidden.move("u", "WEB-1", "OPS"));
        assertEquals("WEB-1", hidden.get("u", "WEB-1").id());
    }

    /** Move needs CREATE in the target on its own: a user who is ADMIN in the source and may only BROWSE the target is refused, and nothing is created there. */
    @Test
    void moveNeedsCreateInTheTarget() {
        JiraService j = custom((p, u, a) -> p.equals("WEB") || a == Action.BROWSE, "WEB", "OPS");
        j.createIssue("u", "WEB", "t", null, Set.of());
        assertThrows(JiraException.PermissionDenied.class, () -> j.move("u", "WEB-1", "OPS"));
        assertEquals("WEB-1", j.get("u", "WEB-1").id());
        assertThrows(JiraException.IssueNotFound.class, () -> j.get("u", "OPS-1"));
    }

    /** A project with permission rows but no real project gives ProjectNotFound (not an NPE), and a failed create does not burn a key number. */
    @Test
    void ghostProjectIsNotFoundAndBurnsNoKey() {
        InMemoryWorkflowEngine engine = new InMemoryWorkflowEngine(Clock.fixed(Instant.parse("2026-01-05T12:00:00Z"), ZoneOffset.UTC), () -> 0L, Runnable::run, Long.MAX_VALUE, 5);
        JiraService j = new JiraService(engine, (p, u, a) -> true, new InvertedIndex(), () -> 0L, WindowSpec.of(Duration.ofMinutes(1), Duration.ofMinutes(10)), true);
        assertThrows(JiraException.ProjectNotFound.class, () -> j.trendingIssues("u", "GHOST", 3));
        assertThrows(JiraException.ProjectNotFound.class, () -> j.trendingLabels("u", "GHOST", 3));
        assertThrows(JiraException.ProjectNotFound.class, () -> j.createIssue("u", "GHOST", "t", null, Set.of()));
        j.createProject("GHOST", Rig.flow());
        assertEquals("GHOST-1", j.createIssue("u", "GHOST", "t", null, Set.of()).id());
    }

    /** When the engine itself refuses a create (here: no actor), the key counter does not move, so the next good create still gets WEB-1. */
    @Test
    void engineRefusedCreateBurnsNoKey() {
        JiraService j = custom((p, u, a) -> true, "WEB");
        assertThrows(RuntimeException.class, () -> j.createIssue(null, "WEB", "t", null, Set.of()));
        assertEquals("WEB-1", j.createIssue("u", "WEB", "t", null, Set.of()).id());
    }

    /** A move records the mover as the actor in the engine's audit log and keeps the original reporter on the issue. */
    @Test
    void moveRecordsTheMoverNotTheReporter() {
        Rig r = new Rig();
        r.make("bob", "WEB", "t");
        IssueDetails moved = r.jira.move("alice", "WEB-1", "OPS");
        assertEquals("bob", moved.reporter());
        var last = r.engine.auditLog().get(r.engine.auditLog().size() - 1);
        assertEquals("OPS-1", last.issueId());
        assertEquals("alice", last.actor());
    }

    /** Role keys cannot collide: ("A|b", "c") and ("A", "b|c") are different people in different projects. */
    @Test
    void permissionKeysDoNotCollide() {
        RoleBasedPermissionScheme s = new RoleBasedPermissionScheme();
        s.assignRole("A", "b|c", Role.VIEWER);
        s.grant("A|b", Role.VIEWER, Action.BROWSE);
        assertFalse(s.allows("A|b", "c", Action.BROWSE));
    }

    /** Bulk survives an unexpected error on one item: that item reports ERROR and the others still run. */
    @Test
    void bulkContinuesAfterAnUnexpectedError() {
        JiraService j = custom((p, u, a) -> {
            if (p.equals("OPS") && a == Action.TRANSITION) {
                throw new IllegalStateException("scheme broke");
            }
            return true;
        }, "WEB", "OPS");
        j.createIssue("u", "WEB", "a", null, Set.of());
        j.createIssue("u", "OPS", "b", null, Set.of());
        j.createIssue("u", "WEB", "c", null, Set.of());
        Map<String, Outcome> out = j.bulkTransition("u", List.of("WEB-1", "OPS-1", "WEB-2"), "start");
        assertEquals(Map.of("WEB-1", Outcome.DONE, "OPS-1", Outcome.ERROR, "WEB-2", Outcome.DONE), out);
    }
}
