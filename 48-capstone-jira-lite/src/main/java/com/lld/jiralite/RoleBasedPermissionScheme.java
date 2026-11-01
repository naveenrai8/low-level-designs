package com.lld.jiralite;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The in-memory scheme: grants are keyed by (project, role, action), and people hold roles per project. No grant means no. */
public final class RoleBasedPermissionScheme implements PermissionScheme {

    /** Who holds roles where. A record, so ("A|b", "c") and ("A", "b|c") are different keys (joined strings could collide). */
    private record Member(String project, String user) {
    }

    /** Which actions a role has in a project. */
    private record Seat(String project, Role role) {
    }

    private final Map<Member, Set<Role>> rolesOf = new HashMap<>();
    private final Map<Seat, Set<Action>> grants = new HashMap<>();

    /** Gives the user a role in the project. A person can hold several roles; the answer is "yes" if any of them allows the action. */
    public synchronized void assignRole(String project, String user, Role role) {
        rolesOf.computeIfAbsent(new Member(project, user), k -> new HashSet<>()).add(role);
    }

    /** Lets the role do the action in the project. Grants are per project, so ADMIN in one project says nothing about another. */
    public synchronized void grant(String project, Role role, Action action) {
        grants.computeIfAbsent(new Seat(project, role), k -> new HashSet<>()).add(action);
    }

    /** Returns true only if one of the user's roles in this project has the action granted; otherwise false. */
    @Override
    public synchronized boolean allows(String project, String user, Action action) {
        for (Role role : rolesOf.getOrDefault(new Member(project, user), Set.of())) {
            if (grants.getOrDefault(new Seat(project, role), Set.of()).contains(action)) {
                return true;
            }
        }
        return false;
    }
}
