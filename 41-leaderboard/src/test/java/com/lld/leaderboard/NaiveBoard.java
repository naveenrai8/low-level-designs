package com.lld.leaderboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The obviously correct reference: a map that is fully re-sorted on every query. Slow (O(n log n) per query) but too simple to be wrong. */
final class NaiveBoard {

    private final Map<String, long[]> data = new HashMap<>();

    /** Sets score and reach time; if the score is unchanged nothing changes. */
    void set(String user, long score, long nowMillis) {
        long[] cur = data.get(user);
        if (cur != null && cur[0] == score) {
            return;
        }
        data.put(user, new long[] {score, nowMillis});
    }

    /** Removes the user, true if present. */
    boolean remove(String user) {
        return data.remove(user) != null;
    }

    /** Returns the score or null. */
    Long score(String user) {
        long[] v = data.get(user);
        return v == null ? null : v[0];
    }

    /** Returns every row sorted by the tie rule, with 1-based ranks. */
    List<Ranked> sorted() {
        List<Ranked> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : data.entrySet()) {
            rows.add(new Ranked(0, e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        rows.sort((a, b) -> {
            if (a.score() != b.score()) {
                return Long.compare(b.score(), a.score());
            }
            if (a.reachedAtMillis() != b.reachedAtMillis()) {
                return Long.compare(a.reachedAtMillis(), b.reachedAtMillis());
            }
            return a.user().compareTo(b.user());
        });
        List<Ranked> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Ranked r = rows.get(i);
            out.add(new Ranked(i + 1, r.user(), r.score(), r.reachedAtMillis()));
        }
        return out;
    }

    /** Returns the 1-based rank or 0 when absent. */
    int rank(String user) {
        for (Ranked r : sorted()) {
            if (r.user().equals(user)) {
                return r.rank();
            }
        }
        return 0;
    }

    /** Returns 1 + number of strictly higher scores, or 0 when absent. */
    int competitionRank(String user) {
        long[] v = data.get(user);
        if (v == null) {
            return 0;
        }
        int higher = 0;
        for (long[] o : data.values()) {
            if (o[0] > v[0]) {
                higher++;
            }
        }
        return higher + 1;
    }

    /** Returns the number of users. */
    int size() {
        return data.size();
    }
}
