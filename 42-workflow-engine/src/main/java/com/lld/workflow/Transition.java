package com.lld.workflow;

import java.util.List;
import java.util.Set;

/**
 * One named move between states, allowed from one or more source states, behind zero or more guards.
 *
 * <p>The name is unique inside a workflow, so a caller asks for "close" and the engine knows which arrow that is from the issue's current state.
 *
 * @param name the unique name callers use to ask for this move
 * @param from the states this move may start from
 * @param to the state this move ends in
 * @param guards checks that must all pass, evaluated in order
 */
public record Transition(String name, Set<String> from, String to, List<Guard> guards) {

    /** Copies the sets and lists so a transition cannot be changed after it is built. */
    public Transition {
        from = Set.copyOf(from);
        guards = List.copyOf(guards);
    }
}
