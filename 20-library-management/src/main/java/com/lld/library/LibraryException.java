package com.lld.library;

/**
 * The error contract of the library. Every failure is one of these nested types.
 *
 * <p>A failed call changes nothing (except that it may pass on holds whose pickup window had already ended): the copy, the loan record and the member's loan count are left as they were. The one exception is a throwing notifier, which is swallowed and counted, never thrown.
 */
public abstract class LibraryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a message naming the problem and the id involved. */
    protected LibraryException(String message) {
        super(message);
    }

    /** The member id was never registered. */
    public static final class UnknownMember extends LibraryException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given member id. */
        public UnknownMember(String id) {
            super("unknown member: " + id);
        }
    }

    /** The ISBN is not in the catalogue. */
    public static final class UnknownTitle extends LibraryException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given ISBN. */
        public UnknownTitle(String isbn) {
            super("unknown title: " + isbn);
        }
    }

    /** The copy id does not exist. */
    public static final class UnknownCopy extends LibraryException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given copy id. */
        public UnknownCopy(String id) {
            super("unknown copy: " + id);
        }
    }

    /** The loan id was never issued. */
    public static final class UnknownLoan extends LibraryException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given loan id. */
        public UnknownLoan(String id) {
            super("unknown loan: " + id);
        }
    }

    /** The branch is not one of this library's branches. */
    public static final class UnknownBranch extends LibraryException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given branch. */
        public UnknownBranch(String id) {
            super("unknown branch: " + id);
        }
    }

    /** No copy of the title can be taken at that branch right now (all out, held for someone else, or in transit). */
    public static final class NoCopyAvailable extends LibraryException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given title and branch. */
        public NoCopyAvailable(String isbn, String branch) {
            super("no copy of " + isbn + " available at " + branch);
        }
    }

    /** The member already holds the maximum number of open loans. */
    public static final class LoanLimitReached extends LibraryException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given member and limit. */
        public LoanLimitReached(String member, int limit) {
            super("member " + member + " already has " + limit + " open loans");
        }
    }

    /** The member already has this title on loan, so they cannot borrow it again or hold it. */
    public static final class AlreadyBorrowed extends LibraryException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given member and title. */
        public AlreadyBorrowed(String member, String isbn) {
            super("member " + member + " already has " + isbn + " on loan");
        }
    }

    /** The loan was already closed, so a second return is refused. */
    public static final class AlreadyReturned extends LibraryException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given loan id. */
        public AlreadyReturned(String id) {
            super("loan already returned: " + id);
        }
    }

    /** The member is already in this title's hold queue, or already has a copy waiting for them. */
    public static final class DuplicateHold extends LibraryException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given member and title. */
        public DuplicateHold(String member, String isbn) {
            super("member " + member + " already holds " + isbn);
        }
    }

    /** A copy is on the shelf, so a hold would be pointless: borrow it instead. */
    public static final class CopyAvailable extends LibraryException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given title. */
        public CopyAvailable(String isbn) {
            super("a copy of " + isbn + " is available, borrow it instead of holding");
        }
    }

    /** The copy cannot start a transfer: it is not on a shelf, or it is already at the destination. */
    public static final class CopyNotTransferable extends LibraryException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given copy and the reason. */
        public CopyNotTransferable(String id, String reason) {
            super("copy " + id + " cannot be transferred: " + reason);
        }
    }

    /** The copy is not in transit, so there is nothing to receive. */
    public static final class NotInTransit extends LibraryException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given copy id. */
        public NotInTransit(String id) {
            super("copy is not in transit: " + id);
        }
    }
}
