package com.learning.library.service;

import com.learning.library.model.Book;
import com.learning.library.model.BookStatus;
import com.learning.library.model.Member;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class LibraryService {
    private final ConcurrentHashMap<String, Book> books = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Member> members = new ConcurrentHashMap<>();

    public void addBook(Book book) {
        books.put(book.getIsbn(), book);
    }

    public Member registerMember(Member member) {
        members.put(member.getMemberId(), member);
        return member;
    }

    public boolean borrowBook(String isbn, String memberId) throws Exception {
        Book book = books.get(isbn);
        Member member = members.get(memberId);

        if (book == null) throw new Exception("Book not found");
        if (member == null) throw new Exception("Member not found");

        if (book.borrow(memberId)) {
            member.addBorrowedBook(isbn);
            return true;
        }
        return false;
    }

    public void returnBook(String isbn, String memberId) throws Exception {
        Book book = books.get(isbn);
        Member member = members.get(memberId);

        if (book == null) throw new Exception("Book not found");
        if (member == null) throw new Exception("Member not found");

        book.returnBook();
        member.removeBorrowedBook(isbn);
    }

    public List<Book> searchBooks(String keyword) {
        return books.values().stream()
                .filter(book -> book.getTitle().toLowerCase().contains(keyword.toLowerCase()) ||
                               book.getAuthor().toLowerCase().contains(keyword.toLowerCase()))
                .collect(Collectors.toList());
    }

    public List<Book> getAvailableBooks() {
        return books.values().stream()
                .filter(book -> book.getStatus() == BookStatus.AVAILABLE)
                .collect(Collectors.toList());
    }

    public Book getBook(String isbn) {
        return books.get(isbn);
    }

    public Member getMember(String memberId) {
        return members.get(memberId);
    }

    public List<Book> getAllBooks() {
        return List.copyOf(books.values());
    }

    public List<Member> getAllMembers() {
        return List.copyOf(members.values());
    }
}
