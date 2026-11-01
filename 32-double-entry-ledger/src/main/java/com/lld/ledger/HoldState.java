package com.lld.ledger;

/** The life of a hold: it starts AUTHORIZED and ends in exactly one of CAPTURED, VOIDED or EXPIRED. */
public enum HoldState {
    AUTHORIZED, CAPTURED, VOIDED, EXPIRED
}
