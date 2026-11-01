package com.lld.popularity;

/**
 * One row of a decayed top-K answer.
 *
 * @param id the item
 * @param score its decayed score right now (a recent event counts more than an old one)
 */
public record Scored(String id, double score) {
}
