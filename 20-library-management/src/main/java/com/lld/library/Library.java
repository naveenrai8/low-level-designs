package com.lld.library;

import java.util.List;

/**
 * A library with a catalogue, physical copies at several branches, loans, fines and a hold queue per title.
 *
 * <p>Every method throws a {@link LibraryException} subtype on a business-rule failure, and a failed call changes nothing, except that it may pass on holds whose pickup window has already ended (that is true whether or not the call succeeds). All methods are safe to call from many threads.
 */
public interface Library {

    /** Adds a title to the catalogue. Adding the same ISBN again changes nothing. */
    void addTitle(Title title);

    /**
     * Adds one physical copy of a title to a branch and returns its new id.
     *
     * <p>If members are waiting in the title's hold queue, the new copy goes straight to the head of the queue instead of the shelf.
     *
     * @throws LibraryException.UnknownTitle if the ISBN is not in the catalogue
     * @throws LibraryException.UnknownBranch if the branch does not exist
     */
    String addCopy(String isbn, String branch);

    /** Registers a member. Registering the same id again changes nothing. */
    void registerMember(String memberId);

    /** Finds titles whose name or author contains the text, ignoring case. */
    List<Title> search(String text);

    /**
     * Lends a copy of the title at the branch to the member and returns the loan.
     *
     * <p>If a copy is being held for this member at this branch and the pickup window is still open, that copy is taken. Otherwise a shelf copy is taken. Two members racing for the last copy: exactly one wins and the other gets NoCopyAvailable.
     *
     * @throws LibraryException.UnknownMember if the member is not registered
     * @throws LibraryException.UnknownTitle if the title is not in the catalogue
     * @throws LibraryException.UnknownBranch if the branch does not exist
     * @throws LibraryException.AlreadyBorrowed if the member already has this title on loan
     * @throws LibraryException.LoanLimitReached if the member is at the loan limit
     * @throws LibraryException.NoCopyAvailable if no copy can be taken at the branch
     */
    Loan checkout(String memberId, String isbn, String branch);

    /**
     * Closes a loan, puts the copy back at the given branch and returns the receipt with any fine.
     *
     * <p>If the title has a hold queue, the copy goes to the head of it and that member is notified. A notifier that throws does not stop the return.
     *
     * @throws LibraryException.UnknownLoan if the loan id was never issued
     * @throws LibraryException.AlreadyReturned if the loan was already closed
     * @throws LibraryException.UnknownBranch if the branch does not exist
     */
    ReturnReceipt returnCopy(String loanId, String branch);

    /**
     * Puts the member at the back of the title's queue and returns their position (1 is the head).
     *
     * @throws LibraryException.AlreadyBorrowed if the member has the title on loan
     * @throws LibraryException.DuplicateHold if they already wait for it
     * @throws LibraryException.CopyAvailable if a copy is on a shelf, so borrowing is the right move
     * @throws LibraryException.UnknownMember if the member is not registered
     * @throws LibraryException.UnknownTitle if the title is not in the catalogue
     */
    int placeHold(String memberId, String isbn);

    /**
     * Starts moving a shelf copy to another branch. The copy is in transit, and cannot be borrowed, until {@link #receive}.
     *
     * @throws LibraryException.CopyNotTransferable if the copy is not on a shelf or is already at the destination
     * @throws LibraryException.UnknownCopy if the copy id does not exist
     * @throws LibraryException.UnknownBranch if the destination does not exist
     */
    void transfer(String copyId, String toBranch);

    /**
     * Finishes a transfer: the copy arrives and goes to the hold queue head or onto the shelf.
     *
     * @throws LibraryException.NotInTransit if the copy is not in transit
     * @throws LibraryException.UnknownCopy if the copy id does not exist
     */
    void receive(String copyId);

    /** Passes every expired hold on to the next member in line (or back to the shelf). Other calls also do this for the title they touch. */
    void sweepHolds();

    /** A snapshot of one copy's state. */
    CopyState stateOf(String copyId);

    /** A snapshot of the member ids waiting for the title, head first. */
    List<String> holdQueue(String isbn);

    /** How many open loans the member has right now. */
    int openLoans(String memberId);

    /** How many copies of the title are on a shelf of the branch right now. */
    int availableCopies(String isbn, String branch);
}
