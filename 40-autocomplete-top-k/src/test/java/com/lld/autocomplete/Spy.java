package com.lld.autocomplete;

import java.util.List;
import java.util.function.Consumer;

/** A test wrapper that forwards everything to a real index and calls a hook at the start of every suggest. */
final class Spy implements Autocomplete {

    private final Autocomplete d;
    private final Consumer<String> onSuggest;

    /** Wraps {@code d}; {@code onSuggest} is called with the prefix before each suggest. */
    Spy(Autocomplete d, Consumer<String> onSuggest) {
        this.d = d;
        this.onSuggest = onSuggest;
    }

    /** {@inheritDoc} */
    @Override
    public void add(String word, long delta) {
        d.add(word, delta);
    }

    /** {@inheritDoc} */
    @Override
    public boolean remove(String word) {
        return d.remove(word);
    }

    /** {@inheritDoc} */
    @Override
    public long count(String word) {
        return d.count(word);
    }

    /** Runs the hook, then answers normally. */
    @Override
    public List<Suggestion> suggest(String prefix, int k) {
        onSuggest.accept(prefix);
        return d.suggest(prefix, k);
    }

    /** {@inheritDoc} */
    @Override
    public List<Suggestion> suggestFuzzy(String prefix, int k) {
        return d.suggestFuzzy(prefix, k);
    }

    /** {@inheritDoc} */
    @Override
    public int size() {
        return d.size();
    }
}
