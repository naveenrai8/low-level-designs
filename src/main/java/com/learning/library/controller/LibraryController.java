package com.learning.library.controller;

import com.learning.library.dto.BookRequest;
import com.learning.library.dto.BorrowRequest;
import com.learning.library.dto.MemberRequest;
import com.learning.library.model.Book;
import com.learning.library.model.Member;
import com.learning.library.service.LibraryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/library")
@Tag(name = "Library", description = "Library management operations")
public class LibraryController {

    private final LibraryService libraryService;

    public LibraryController(LibraryService libraryService) {
        this.libraryService = libraryService;
    }

    @PostMapping("/books")
    @Operation(summary = "Add a book")
    public ResponseEntity<String> addBook(@Valid @RequestBody BookRequest request) {
        Book book = new Book(request.isbn(), request.title(), request.author(), request.publicationYear());
        libraryService.addBook(book);
        return ResponseEntity.status(HttpStatus.CREATED).body("Book added successfully");
    }

    @GetMapping("/books")
    @Operation(summary = "Get all books")
    public ResponseEntity<List<Book>> getAllBooks() {
        return ResponseEntity.ok(libraryService.getAllBooks());
    }

    @GetMapping("/books/available")
    @Operation(summary = "Get available books")
    public ResponseEntity<List<Book>> getAvailableBooks() {
        return ResponseEntity.ok(libraryService.getAvailableBooks());
    }

    @GetMapping("/books/search")
    @Operation(summary = "Search books")
    public ResponseEntity<List<Book>> searchBooks(@RequestParam String keyword) {
        return ResponseEntity.ok(libraryService.searchBooks(keyword));
    }

    @GetMapping("/books/{isbn}")
    @Operation(summary = "Get book details")
    public ResponseEntity<Book> getBook(@PathVariable String isbn) {
        Book book = libraryService.getBook(isbn);
        return book != null ? ResponseEntity.ok(book) : ResponseEntity.notFound().build();
    }

    @PostMapping("/members")
    @Operation(summary = "Register a member")
    public ResponseEntity<Member> registerMember(@Valid @RequestBody MemberRequest request) {
        Member member = new Member(request.memberId(), request.name(), request.email());
        Member registered = libraryService.registerMember(member);
        return ResponseEntity.status(HttpStatus.CREATED).body(registered);
    }

    @GetMapping("/members")
    @Operation(summary = "Get all members")
    public ResponseEntity<List<Member>> getAllMembers() {
        return ResponseEntity.ok(libraryService.getAllMembers());
    }

    @GetMapping("/members/{memberId}")
    @Operation(summary = "Get member details")
    public ResponseEntity<Member> getMember(@PathVariable String memberId) {
        Member member = libraryService.getMember(memberId);
        return member != null ? ResponseEntity.ok(member) : ResponseEntity.notFound().build();
    }

    @PostMapping("/borrow")
    @Operation(summary = "Borrow a book")
    public ResponseEntity<?> borrowBook(@Valid @RequestBody BorrowRequest request) {
        try {
            boolean success = libraryService.borrowBook(request.isbn(), request.memberId());
            return success ? ResponseEntity.ok("Book borrowed successfully")
                          : ResponseEntity.badRequest().body("Book not available");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/return")
    @Operation(summary = "Return a book")
    public ResponseEntity<?> returnBook(@Valid @RequestBody BorrowRequest request) {
        try {
            libraryService.returnBook(request.isbn(), request.memberId());
            return ResponseEntity.ok("Book returned successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
