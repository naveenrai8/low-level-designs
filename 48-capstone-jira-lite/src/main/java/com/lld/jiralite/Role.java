package com.lld.jiralite;

/** A named bundle of actions. A person gets roles per project, and the scheme says which actions each role allows. */
public enum Role {
    /** Can only look. */
    VIEWER,
    /** Can look, create, transition and comment. */
    DEVELOPER,
    /** Can do everything, including delete and move. */
    ADMIN
}
