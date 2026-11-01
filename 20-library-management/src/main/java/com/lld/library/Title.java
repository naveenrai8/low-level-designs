package com.lld.library;

/**
 * A book as the catalogue knows it (one entry, however many physical copies exist).
 *
 * @param isbn the unique id of the title
 * @param name the book's name
 * @param author the author
 */
public record Title(String isbn, String name, String author) {
}
