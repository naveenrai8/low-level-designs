package com.lld.spreadsheet;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when an edit would create a circular reference. The sheet is untouched. The message names the path, for example "cycle: A1 -> B1 -> C1 -> A1" (each cell reads the next).
 */
public final class CycleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<CellId> path;

    /**
     * Builds the error.
     *
     * @param path the cells of the cycle, first and last being the same cell
     */
    public CycleException(List<CellId> path) {
        super("cycle: " + path.stream().map(CellId::toString).collect(Collectors.joining(" -> ")));
        this.path = List.copyOf(path);
    }

    /**
     * The cycle.
     *
     * @return the cells in order, the first repeated at the end
     */
    public List<CellId> path() {
        return path;
    }
}
