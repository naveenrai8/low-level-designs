package com.lld.jiralite;

/** The result for one issue of a bulk transition. */
public enum Outcome {
    /** The transition was applied. */
    DONE,
    /** The person may not browse or transition this issue. Unknown keys give the same answer, so nothing leaks. */
    DENIED,
    /** The person was allowed but the workflow refused (wrong state, guard, version). */
    REFUSED,
    /** Something unexpected went wrong for this one issue (for example the permission scheme threw). The rest of the list was still processed. */
    ERROR
}
