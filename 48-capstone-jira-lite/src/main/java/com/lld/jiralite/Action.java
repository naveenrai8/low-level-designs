package com.lld.jiralite;

/** Things a person can try to do inside one project. Every one of them is checked against the permission scheme before anything changes. */
public enum Action {
    /** See the project: read issues, find them in search, see them in trending. */
    BROWSE,
    /** Create a new issue in the project (also needed in the target project of a move). */
    CREATE,
    /** Move an issue along the workflow, one named transition at a time. */
    TRANSITION,
    /** Add a comment to an issue. */
    COMMENT,
    /** Delete an issue or move it out of the project. */
    ADMINISTER
}
