package com.lld.elevator;

/** The operating mode of one car. Changes only go through {@link ModeTable}. */
public enum CarMode {
    /** Takes hall calls and cab requests as usual. */
    NORMAL,
    /** Taken out of service for a technician. The dispatcher never picks it and it does not move. */
    MAINTENANCE,
    /** Fire recall: drives to the lobby (floor 0), opens its doors once (a FIRE_RECALL stop), and stays there ignoring all requests. A car that was in maintenance returns to maintenance, not NORMAL, when the fire clears. */
    FIRE
}
