package com.lld.jiralite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * An in-memory inverted index: for each word, the set of issue ids that contain it.
 *
 * <p>Cost: a text query looks up each word and intersects the sets, so it touches only the issues that contain the rarest word, not every issue. A query with no text has no words to start from, so it scans every document. The project allow-list and the other filters are applied to the candidates after that; a real engine would turn them into a filter clause or pick shards first.
 *
 * <p>Words are runs of letters and digits in any language, lower-cased. A text that has no words at all (like {@code "!!!"}) matches nothing.
 */
public final class InvertedIndex implements SearchIndex {

    private final Map<String, IssueDoc> docs = new HashMap<>();
    private final Map<String, Set<String>> postings = new HashMap<>();

    /** Cuts text into lower-case words (letters and digits of any script); everything else is a separator. */
    public static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
    }

    /** Replaces the document: first removes every old word of this id, then adds the new ones. */
    @Override
    public synchronized void put(IssueDoc doc) {
        remove(doc.id());
        docs.put(doc.id(), doc);
        for (String w : words(doc.text())) {
            postings.computeIfAbsent(w, k -> new HashSet<>()).add(doc.id());
        }
    }

    /** Removes the id from the document table and from the list of every word it used. */
    @Override
    public synchronized void remove(String id) {
        IssueDoc old = docs.remove(id);
        if (old == null) {
            return;
        }
        for (String w : words(old.text())) {
            Set<String> ids = postings.get(w);
            if (ids != null) {
                ids.remove(id);
                if (ids.isEmpty()) {
                    postings.remove(w);
                }
            }
        }
    }

    /** Finds candidates from the words (intersection), then applies the project allow-list and the other filters. Results are sorted by id. A text with no words returns nothing. */
    @Override
    public synchronized List<String> search(Query q) {
        Set<String> candidates;
        if (q.text() == null) {
            candidates = new HashSet<>(docs.keySet());
        } else {
            Set<String> terms = words(q.text());
            if (terms.isEmpty()) {
                return List.of();
            }
            candidates = null;
            for (String t : terms) {
                Set<String> ids = postings.getOrDefault(t, Set.of());
                if (candidates == null) {
                    candidates = new HashSet<>(ids);
                } else {
                    candidates.retainAll(ids);
                }
            }
        }
        Set<String> sorted = new TreeSet<>();
        for (String id : candidates) {
            IssueDoc d = docs.get(id);
            boolean ok = q.projects().contains(d.project())
                    && (q.status() == null || q.status().equals(d.status()))
                    && (q.assignee() == null || q.assignee().equals(d.assignee()))
                    && (q.label() == null || d.labels().contains(q.label()));
            if (ok) {
                sorted.add(id);
            }
        }
        return new ArrayList<>(sorted);
    }
}
