package com.lld.jiralite;

/** The single question every path asks: may this person do this action in this project? Implementations must answer "no" when nothing says "yes" (deny by default). */
public interface PermissionScheme {

    /**
     * Answers whether the user may do the action in the project.
     *
     * @param project the project key
     * @param user the person asking
     * @param action what they want to do
     * @return true only if something explicitly allows it
     */
    boolean allows(String project, String user, Action action);
}
