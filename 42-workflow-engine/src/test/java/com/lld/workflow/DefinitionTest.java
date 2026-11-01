package com.lld.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Proves a workflow definition is checked when built and cannot change afterwards. */
class DefinitionTest {

    /** Builds with the given pieces and returns the InvalidWorkflow message. */
    private static String problem(WorkflowDefinition.Builder b) {
        return assertThrows(WorkflowException.InvalidWorkflow.class, b::build).getMessage();
    }

    /** A definition with no initial state is rejected. */
    @Test
    void noInitialStateRejected() {
        assertTrue(problem(WorkflowDefinition.builder().states("A")).contains("no initial state"));
    }

    /** An initial state that was never declared is rejected. */
    @Test
    void undeclaredInitialRejected() {
        assertTrue(problem(WorkflowDefinition.builder().states("A").initial("Z")).contains("not a declared state"));
    }

    /** A transition that starts from an unknown state is rejected and the message names it. */
    @Test
    void unknownSourceRejected() {
        assertTrue(problem(WorkflowDefinition.builder().states("A", "B").initial("A").transition("t", Set.of("A", "X"), "B")).contains("unknown state 'X'"));
    }

    /** A transition that ends in an unknown state is rejected. */
    @Test
    void unknownTargetRejected() {
        assertTrue(problem(WorkflowDefinition.builder().states("A").initial("A").transition("t", Set.of("A"), "Y")).contains("unknown state 'Y'"));
    }

    /** A state that nothing leads to is rejected as unreachable. */
    @Test
    void unreachableStateRejected() {
        assertTrue(problem(WorkflowDefinition.builder().states("A", "B", "C").initial("A").transition("t", Set.of("A"), "B")).contains("'C' is unreachable"));
    }

    /** Two transitions with the same name are rejected. */
    @Test
    void duplicateTransitionNameRejected() {
        assertTrue(problem(WorkflowDefinition.builder().states("A", "B").initial("A").transition("t", Set.of("A"), "B").transition("t", Set.of("B"), "A")).contains("duplicate transition name 't'"));
    }

    /** Reachability follows chains: a state two hops away is reachable, so a valid chain builds. */
    @Test
    void chainedStatesAreReachable() {
        WorkflowDefinition d = Fixtures.bugFlow();
        assertEquals(Set.of("start"), d.transitionsFrom("OPEN"));
        assertEquals(Set.of("reopen"), d.transitionsFrom("DONE"));
    }

    /** Changing the builder after build() does not change the definition already built. */
    @Test
    void definitionIsImmutableAfterBuild() {
        WorkflowDefinition.Builder b = WorkflowDefinition.builder().states("A", "B").initial("A").transition("go", Set.of("A"), "B");
        WorkflowDefinition d = b.build();
        b.states("C").transition("extra", Set.of("B"), "C");
        assertFalse(d.states().contains("C"));
        assertTrue(d.transition("extra").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> d.states().add("Z"));
    }

    /** A transition with no source state is rejected. */
    @Test
    void transitionWithNoSourceRejected() {
        assertTrue(problem(WorkflowDefinition.builder().states("A", "B").initial("A").transition("t", Set.of(), "B")).contains("'t' has no source state"));
    }

    /** A Transition copies the set and list it is given, so changing the caller's collections afterwards changes nothing. */
    @Test
    void transitionCopiesItsInputs() {
        Set<String> from = new java.util.HashSet<>(Set.of("A"));
        java.util.List<Guard> guards = new java.util.ArrayList<>();
        Transition t = new Transition("t", from, "B", guards);
        from.add("Z");
        guards.add(Guard.fieldEquals("g", "k", "v"));
        assertEquals(Set.of("A"), t.from());
        assertTrue(t.guards().isEmpty());
    }

    /** An IssueView copies the field map it is given, so changing the caller's map afterwards does not change the view. */
    @Test
    void issueViewCopiesItsFields() {
        java.util.Map<String, String> fields = new java.util.HashMap<>(java.util.Map.of("k", "v"));
        IssueView v = new IssueView("I", "P", 1, "A", 0, fields);
        fields.put("k", "changed");
        fields.put("extra", "1");
        assertEquals(java.util.Map.of("k", "v"), v.fields());
    }
}
