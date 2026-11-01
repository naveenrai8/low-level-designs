package com.lld.flags;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One feature flag: its variants and every level of its targeting. Immutable.
 * <p>Evaluation order is fixed: kill switch, prerequisites, individual targets, rules (in list order, first match wins), rollout, default.
 *
 * @param key unique name
 * @param salt extra text mixed into the bucket hash; change it to re-shuffle users (may be empty)
 * @param variants all variant names this flag may serve
 * @param enabled the kill switch: false means serve the off variant to everyone
 * @param offVariant the variant served when the flag is off or a prerequisite fails
 * @param defaultVariant the variant served when nothing else applies
 * @param targets user id to variant, checked before rules
 * @param rules checked in order; the first that matches wins
 * @param rollout the percentage split, or null for none
 * @param prerequisites other flags that must serve given variants
 */
public record Flag(String key, String salt, Set<String> variants, boolean enabled, String offVariant, String defaultVariant,
                   Map<String, String> targets, List<Rule> rules, Rollout rollout, List<Prerequisite> prerequisites) {

    /**
     * Copies every collection and checks every variant name used anywhere is in the variants set and rule ids are unique.
     * <p>Throws {@link InvalidConfigException} when something is wrong.
     */
    public Flag {
        if (key == null || key.isBlank()) {
            throw new InvalidConfigException("flag needs a key");
        }
        salt = salt == null ? "" : salt;
        variants = Set.copyOf(variants == null ? Set.of() : variants);
        targets = Map.copyOf(targets == null ? Map.of() : targets);
        rules = List.copyOf(rules == null ? List.of() : rules);
        prerequisites = List.copyOf(prerequisites == null ? List.of() : prerequisites);
        if (variants.isEmpty()) {
            throw new InvalidConfigException("flag " + key + " needs variants");
        }
        require(key, variants, offVariant);
        require(key, variants, defaultVariant);
        for (String v : targets.values()) {
            require(key, variants, v);
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Rule r : rules) {
            require(key, variants, r.variant());
            if (!ids.add(r.id())) {
                throw new InvalidConfigException("flag " + key + " has two rules with id " + r.id());
            }
        }
        if (rollout != null) {
            for (Rollout.Slice s : rollout.slices()) {
                require(key, variants, s.variant());
            }
        }
    }

    /**
     * Fails if a variant name is not one of the flag's variants.
     *
     * @param key the flag key (for the message)
     * @param variants the allowed names
     * @param v the name to check
     */
    private static void require(String key, Set<String> variants, String v) {
        if (v == null || !variants.contains(v)) {
            throw new InvalidConfigException("flag " + key + " uses unknown variant " + v);
        }
    }

    /**
     * Starts a builder for a flag.
     *
     * @param key the flag key
     * @return a new builder
     */
    public static Builder builder(String key) {
        return new Builder(key);
    }

    /** A small helper to build flags without a ten-argument constructor. */
    public static final class Builder {
        private final String key;
        private String salt = "";
        private final Set<String> variants = new LinkedHashSet<>();
        private boolean enabled = true;
        private String off;
        private String dflt;
        private final Map<String, String> targets = new LinkedHashMap<>();
        private final List<Rule> rules = new ArrayList<>();
        private Rollout rollout;
        private final List<Prerequisite> prerequisites = new ArrayList<>();

        /**
         * Creates a builder.
         *
         * @param key the flag key
         */
        private Builder(String key) {
            this.key = key;
        }

        /**
         * Sets the bucket salt.
         *
         * @param s the salt
         * @return this builder
         */
        public Builder salt(String s) {
            this.salt = s;
            return this;
        }

        /**
         * Adds variant names.
         *
         * @param names the names
         * @return this builder
         */
        public Builder variants(String... names) {
            variants.addAll(List.of(names));
            return this;
        }

        /**
         * Sets the kill switch.
         *
         * @param e false turns the flag off for everyone
         * @return this builder
         */
        public Builder enabled(boolean e) {
            this.enabled = e;
            return this;
        }

        /**
         * Sets the off variant.
         *
         * @param v the variant
         * @return this builder
         */
        public Builder off(String v) {
            this.off = v;
            return this;
        }

        /**
         * Sets the default variant.
         *
         * @param v the variant
         * @return this builder
         */
        public Builder dflt(String v) {
            this.dflt = v;
            return this;
        }

        /**
         * Adds an individual target.
         *
         * @param userId the user
         * @param variant the variant to serve
         * @return this builder
         */
        public Builder target(String userId, String variant) {
            targets.put(userId, variant);
            return this;
        }

        /**
         * Adds a rule at the end of the rule list.
         *
         * @param r the rule
         * @return this builder
         */
        public Builder rule(Rule r) {
            rules.add(r);
            return this;
        }

        /**
         * Sets the rollout.
         *
         * @param r the rollout
         * @return this builder
         */
        public Builder rollout(Rollout r) {
            this.rollout = r;
            return this;
        }

        /**
         * Adds a prerequisite.
         *
         * @param flagKey the other flag
         * @param variant the variant it must serve
         * @return this builder
         */
        public Builder requires(String flagKey, String variant) {
            prerequisites.add(new Prerequisite(flagKey, variant));
            return this;
        }

        /**
         * Builds and validates the flag.
         *
         * @return the flag
         */
        public Flag build() {
            return new Flag(key, salt, variants, enabled, off, dflt, targets, rules, rollout, prerequisites);
        }
    }
}
