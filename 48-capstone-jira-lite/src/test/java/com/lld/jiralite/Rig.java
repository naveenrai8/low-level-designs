package com.lld.jiralite;

import com.lld.popularity.WindowSpec;
import com.lld.workflow.Guard;
import com.lld.workflow.InMemoryWorkflowEngine;
import com.lld.workflow.WorkflowDefinition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Shared test setup: a real workflow engine, the role scheme, an inverted index and a movable clock, with a fixed cast of users. */
final class Rig {

    /** The one clock for both the engine and the trending window; tests move it by hand. */
    final AtomicLong nanos = new AtomicLong();
    final InMemoryWorkflowEngine engine = new InMemoryWorkflowEngine(Clock.fixed(Instant.parse("2026-01-05T12:00:00Z"), ZoneOffset.UTC), nanos::get, Runnable::run, Long.MAX_VALUE, 5);
    final RoleBasedPermissionScheme scheme = new RoleBasedPermissionScheme();
    final InvertedIndex index = new InvertedIndex();
    final SearchIndex searchIndex;
    final JiraService jira;

    /** Builds the world with read-your-writes on: alice is ADMIN in WEB and OPS, bob DEVELOPER in WEB, carol VIEWER in WEB, dave DEVELOPER in OPS, eve nobody. */
    Rig() {
        this(true);
    }

    /** Same world, but the caller picks the index promise. */
    Rig(boolean readYourWrites) {
        this(readYourWrites, null);
    }

    /** Same world, but the service talks to the given index (a wrapper around {@code index}, so tests can watch or interrupt writes). Null means the plain index. */
    Rig(boolean readYourWrites, java.util.function.Function<InvertedIndex, SearchIndex> wrap) {
        searchIndex = wrap == null ? index : wrap.apply(index);
        for (Role r : Role.values()) {
            for (Action a : Action.values()) {
                boolean on = switch (r) {
                    case VIEWER -> a == Action.BROWSE;
                    case DEVELOPER -> a != Action.ADMINISTER;
                    case ADMIN -> true;
                };
                if (on) {
                    for (String p : new String[] {"WEB", "OPS"}) {
                        scheme.grant(p, r, a);
                    }
                }
            }
        }
        scheme.assignRole("WEB", "alice", Role.ADMIN);
        scheme.assignRole("OPS", "alice", Role.ADMIN);
        scheme.assignRole("WEB", "bob", Role.DEVELOPER);
        scheme.assignRole("WEB", "carol", Role.VIEWER);
        scheme.assignRole("OPS", "dave", Role.DEVELOPER);
        scheme.assignRole("WEB", "frank", Role.ADMIN);
        jira = new JiraService(engine, scheme, searchIndex, nanos::get, WindowSpec.of(Duration.ofMinutes(1), Duration.ofMinutes(10)), readYourWrites);
        jira.createProject("WEB", flow());
        jira.createProject("OPS", flow());
    }

    /** OPEN to IN_PROGRESS (start), IN_PROGRESS to DONE (done, not by the reporter), DONE back to OPEN (reopen). */
    static WorkflowDefinition flow() {
        return WorkflowDefinition.builder()
                .states("OPEN", "IN_PROGRESS", "DONE")
                .initial("OPEN")
                .transition("start", Set.of("OPEN"), "IN_PROGRESS")
                .transition("done", Set.of("IN_PROGRESS"), "DONE", Guard.actorIsNotField("four-eyes", "reporter"))
                .transition("reopen", Set.of("DONE"), "OPEN")
                .build();
    }

    /** Moves the shared clock forward. */
    void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
    }

    /** Creates an issue as the given user with no assignee and the given labels. */
    IssueDetails make(String user, String project, String title, String... labels) {
        return jira.createIssue(user, project, title, "", Set.of(labels));
    }
}
