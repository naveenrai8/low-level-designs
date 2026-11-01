package com.lld.workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable, validated description of a workflow: its states, its starting state and its transitions.
 *
 * <p>Validation happens once, in {@link Builder#build()}, so a definition that exists is always usable. The builder copies everything, so changing the builder afterwards cannot change a definition that was already built.
 */
public final class WorkflowDefinition {

    private final Set<String> states;
    private final String initial;
    private final Map<String, Transition> transitions;

    /** Creates the definition from already-copied, already-validated parts. Only the builder calls this. */
    private WorkflowDefinition(Set<String> states, String initial, Map<String, Transition> transitions) {
        this.states = states;
        this.initial = initial;
        this.transitions = transitions;
    }

    /** Returns every state name, in the order they were declared. The set is unmodifiable. */
    public Set<String> states() {
        return Collections.unmodifiableSet(states);
    }

    /** Returns the state every new issue starts in. */
    public String initial() {
        return initial;
    }

    /** Returns the transition with this name, or empty if the workflow has none by that name. */
    public Optional<Transition> transition(String name) {
        return Optional.ofNullable(transitions.get(name));
    }

    /** Returns the names of the transitions that may start from this state (guards are not evaluated here). */
    public Set<String> transitionsFrom(String state) {
        Set<String> names = new LinkedHashSet<>();
        for (Transition t : transitions.values()) {
            if (t.from().contains(state)) {
                names.add(t.name());
            }
        }
        return names;
    }

    /** Starts a new, empty builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Collects states and transitions, then validates and freezes them in {@link #build()}. */
    public static final class Builder {

        private final Set<String> states = new LinkedHashSet<>();
        private String initial;
        private final List<Transition> transitions = new ArrayList<>();

        /** Creates an empty builder. */
        private Builder() {
        }

        /** Declares states. Declaring the same name twice is harmless. */
        public Builder states(String... names) {
            Collections.addAll(states, names);
            return this;
        }

        /** Says which state new issues start in. It must also be declared as a state. */
        public Builder initial(String name) {
            this.initial = name;
            return this;
        }

        /** Adds a transition named {@code name} from any of the {@code from} states to {@code to}, behind the given guards. Problems are reported by {@link #build()}. */
        public Builder transition(String name, Set<String> from, String to, Guard... guards) {
            transitions.add(new Transition(name, from, to, List.of(guards)));
            return this;
        }

        /**
         * Checks the whole definition and returns it frozen.
         *
         * <p>It throws {@link WorkflowException.InvalidWorkflow} listing every problem it can find (the unreachable-state check needs a valid initial state): no initial state, an initial state that is not declared, a transition using an unknown state, a transition with no source, a duplicate transition name, or a state that cannot be reached from the initial state.
         */
        public WorkflowDefinition build() {
            List<String> problems = new ArrayList<>();
            if (initial == null) {
                problems.add("no initial state");
            } else if (!states.contains(initial)) {
                problems.add("initial state '" + initial + "' is not a declared state");
            }
            Map<String, Transition> byName = new LinkedHashMap<>();
            for (Transition t : transitions) {
                if (byName.putIfAbsent(t.name(), t) != null) {
                    problems.add("duplicate transition name '" + t.name() + "'");
                }
                if (t.from().isEmpty()) {
                    problems.add("transition '" + t.name() + "' has no source state");
                }
                for (String f : t.from()) {
                    if (!states.contains(f)) {
                        problems.add("transition '" + t.name() + "' starts from unknown state '" + f + "'");
                    }
                }
                if (!states.contains(t.to())) {
                    problems.add("transition '" + t.name() + "' ends in unknown state '" + t.to() + "'");
                }
            }
            if (initial != null && states.contains(initial)) {
                Set<String> reached = reachable();
                for (String s : states) {
                    if (!reached.contains(s)) {
                        problems.add("state '" + s + "' is unreachable from the initial state");
                    }
                }
            }
            if (!problems.isEmpty()) {
                throw new WorkflowException.InvalidWorkflow(String.join("; ", problems));
            }
            return new WorkflowDefinition(new LinkedHashSet<>(states), initial, Collections.unmodifiableMap(new LinkedHashMap<>(byName)));
        }

        /** Walks the transitions breadth-first from the initial state and returns every state it can reach. */
        private Set<String> reachable() {
            Set<String> seen = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            seen.add(initial);
            queue.add(initial);
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                for (Transition t : transitions) {
                    if (t.from().contains(cur) && seen.add(t.to())) {
                        queue.add(t.to());
                    }
                }
            }
            return seen;
        }
    }
}
