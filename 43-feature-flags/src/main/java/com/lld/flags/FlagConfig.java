package com.lld.flags;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole configuration at one version: every flag. It is an immutable snapshot, so a reader holding one never sees it change.
 * <p>Building one checks the things that span several flags: every prerequisite exists and names a real variant, there are no cycles, and no chain is deeper than {@link #MAX_PREREQ_DEPTH}.
 *
 * @param version a number that only goes up; a config with a version not above the live one is ignored
 * @param flags the flags by key
 */
public record FlagConfig(long version, Map<String, Flag> flags) {
    /** The longest allowed chain of prerequisites (A needs B needs C counts as 2). */
    public static final int MAX_PREREQ_DEPTH = 5;

    /**
     * Copies the map and runs the cross-flag checks.
     * <p>Throws {@link InvalidConfigException} when a check fails.
     */
    public FlagConfig {
        flags = Map.copyOf(flags);
        flags.forEach((k, f) -> {
            if (!k.equals(f.key())) {
                throw new InvalidConfigException("map key " + k + " does not match flag key " + f.key());
            }
        });
        Map<String, Integer> depth = new HashMap<>();
        for (String key : flags.keySet()) {
            depthOf(key, flags, depth, new ArrayList<>());
        }
    }

    /**
     * Builds a config from a list of flags.
     *
     * @param version the config version
     * @param all the flags
     * @return the validated config
     */
    public static FlagConfig of(long version, Collection<Flag> all) {
        Map<String, Flag> map = new LinkedHashMap<>();
        for (Flag f : all) {
            if (map.put(f.key(), f) != null) {
                throw new InvalidConfigException("duplicate flag " + f.key());
            }
        }
        return new FlagConfig(version, map);
    }

    /**
     * Finds how deep the prerequisite chain under a flag goes, throwing on a cycle, a missing flag, a bad variant or a chain that is too deep.
     *
     * @param key the flag to measure
     * @param flags all flags
     * @param memo depths already computed
     * @param path the flags currently being walked, used to spot cycles
     * @return the number of prerequisite hops below this flag
     */
    private static int depthOf(String key, Map<String, Flag> flags, Map<String, Integer> memo, List<String> path) {
        if (path.contains(key)) {
            throw new InvalidConfigException("prerequisite cycle: " + String.join(" -> ", path) + " -> " + key);
        }
        Integer known = memo.get(key);
        if (known != null) {
            return known;
        }
        path.add(key);
        int deepest = -1;
        for (Prerequisite p : flags.get(key).prerequisites()) {
            Flag target = flags.get(p.flagKey());
            if (target == null) {
                throw new InvalidConfigException("flag " + key + " needs unknown flag " + p.flagKey());
            }
            if (!target.variants().contains(p.variant())) {
                throw new InvalidConfigException("flag " + key + " needs " + p.flagKey() + "=" + p.variant() + " which is not a variant");
            }
            deepest = Math.max(deepest, depthOf(p.flagKey(), flags, memo, path));
        }
        path.remove(path.size() - 1);
        int depth = deepest + 1;
        if (depth > MAX_PREREQ_DEPTH) {
            throw new InvalidConfigException("flag " + key + " has a prerequisite chain of " + depth + ", the limit is " + MAX_PREREQ_DEPTH);
        }
        memo.put(key, depth);
        return depth;
    }
}
