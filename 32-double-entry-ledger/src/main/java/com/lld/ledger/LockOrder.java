package com.lld.ledger;

/** How the ledger orders the account locks of one entry. SORTED is the fix; AS_WRITTEN is the deliberately broken lock-order inversion that tests use to show a real deadlock. */
enum LockOrder {
    SORTED, AS_WRITTEN
}
