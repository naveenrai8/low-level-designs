package com.learning.library.model;

import java.util.ArrayList;
import java.util.List;

public class Member {
    private final String memberId;
    private final String name;
    private final String email;
    private final List<String> borrowedBooks;

    public Member(String memberId, String name, String email) {
        this.memberId = memberId;
        this.name = name;
        this.email = email;
        this.borrowedBooks = new ArrayList<>();
    }

    public synchronized void addBorrowedBook(String isbn) {
        borrowedBooks.add(isbn);
    }

    public synchronized void removeBorrowedBook(String isbn) {
        borrowedBooks.remove(isbn);
    }

    public String getMemberId() { return memberId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public List<String> getBorrowedBooks() { return new ArrayList<>(borrowedBooks); }
}
