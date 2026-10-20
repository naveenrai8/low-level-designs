package com.learning.stackoverflow.model;

public enum VoteValue {
    UPVOTE(1), DOWNVOTE(-1);

    private final int value;

    VoteValue(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
