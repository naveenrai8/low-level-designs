package com.lld.spreadsheet;

/**
 * One undoable edit: the Command pattern in its smallest form. It remembers the cell, the text before and the text after, which is enough to go either way.
 *
 * @param cell the edited cell
 * @param oldRaw the text before ("" if the cell was empty)
 * @param newRaw the text after ("" if the edit cleared it)
 */
record Edit(CellId cell, String oldRaw, String newRaw) { }
