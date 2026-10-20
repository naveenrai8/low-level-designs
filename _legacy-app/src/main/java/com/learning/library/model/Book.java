package com.learning.library.model;

public class Book {
    private final String isbn;
    private final String title;
    private final String author;
    private final int publicationYear;
    private volatile BookStatus status;
    private String borrowedBy;

    public Book(String isbn, String title, String author, int publicationYear) {
        this.isbn = isbn;
        this.title = title;
        this.author = author;
        this.publicationYear = publicationYear;
        this.status = BookStatus.AVAILABLE;
    }

    public synchronized boolean borrow(String memberId) {
        if (status != BookStatus.AVAILABLE) {
            return false;
        }
        this.status = BookStatus.BORROWED;
        this.borrowedBy = memberId;
        return true;
    }

    public synchronized void returnBook() {
        this.status = BookStatus.AVAILABLE;
        this.borrowedBy = null;
    }

    public String getIsbn() { return isbn; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public int getPublicationYear() { return publicationYear; }
    public BookStatus getStatus() { return status; }
    public String getBorrowedBy() { return borrowedBy; }
}
