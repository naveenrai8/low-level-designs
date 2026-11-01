package com.lld.elevator;

import java.util.EnumMap;
import java.util.Map;

/**
 * The one table that says which mode change is legal. Everything else is rejected.
 *
 * <p>Reading the table: from NORMAL, MAINTENANCE_ON gives MAINTENANCE and FIRE_ON gives FIRE. From MAINTENANCE, MAINTENANCE_OFF gives NORMAL and FIRE_ON gives FIRE (fire beats a technician). From FIRE, only FIRE_OFF is allowed and it gives NORMAL. See Topic 06 for why a table beats scattered {@code if}s.
 */
public final class ModeTable {

    private static final Map<CarMode, Map<ModeEvent, CarMode>> TABLE = new EnumMap<>(CarMode.class);

    static {
        TABLE.put(CarMode.NORMAL, new EnumMap<>(Map.of(ModeEvent.MAINTENANCE_ON, CarMode.MAINTENANCE, ModeEvent.FIRE_ON, CarMode.FIRE)));
        TABLE.put(CarMode.MAINTENANCE, new EnumMap<>(Map.of(ModeEvent.MAINTENANCE_OFF, CarMode.NORMAL, ModeEvent.FIRE_ON, CarMode.FIRE)));
        TABLE.put(CarMode.FIRE, new EnumMap<>(Map.of(ModeEvent.FIRE_OFF, CarMode.NORMAL)));
    }

    /** Not instantiable: this class only holds static helpers. */
    private ModeTable() {
    }

    /** Returns the mode after {@code event} happens in mode {@code from}. Throws {@link IllegalStateException} if the table has no such row. */
    public static CarMode next(CarMode from, ModeEvent event) {
        CarMode to = TABLE.get(from).get(event);
        if (to == null) {
            throw new IllegalStateException("illegal mode change: " + event + " while " + from);
        }
        return to;
    }
}
