package com.lld.flags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lld.flags.FeatureFlags.ApplyResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Proves hot reload: atomic swap, no torn reads, bad configs rejected, versions only go up.
 */
class ReloadTest {

    /**
     * Makes a flag "a" serving the given variant by default, plus a flag "b" that needs a=want, so one evaluation of b reads two flags.
     *
     * @param aVariant the variant a serves
     * @return the flags
     */
    private static List<Flag> pair(String aVariant) {
        Flag a = Flag.builder("a").variants("x", "y").off("x").dflt(aVariant).build();
        Flag b = Flag.builder("b").variants("x", "y").off("x").dflt("y").requires("a", "y").build();
        return List.of(a, b);
    }

    /**
     * An evaluation that reads two flags (b, then its prerequisite a) is cut in half by a swap. It must use only the old snapshot: the result is version 1 for the whole answer.
     */
    @Test
    void swapInsideEvaluationIsNotSeen() {
        InMemoryFeatureFlags[] box = new InMemoryFeatureFlags[1];
        AtomicInteger calls = new AtomicInteger();
        // The hook runs once for b and once for a. We swap after b's decision starts.
        box[0] = new InMemoryFeatureFlags(Fixtures.CLOCK, e -> { }, 0, () -> {
            if (calls.incrementAndGet() == 1) {
                box[0].apply(FlagConfig.of(2, pair("x")));
            }
        });
        box[0].apply(FlagConfig.of(1, pair("y")));
        Evaluation e = box[0].evaluate("b", Context.of("u"), "none");
        assertEquals(2, calls.get(), "the prerequisite was also decided inside the same evaluation");
        assertEquals(2, box[0].version(), "the swap really happened mid-evaluation");
        // Old snapshot: a=y so b passes and serves "y", all labelled version 1.
        assertEquals("y", e.variant());
        assertEquals(1, e.configVersion());
        // The next evaluation sees all-new: a=x, so b fails its prerequisite.
        Evaluation next = box[0].evaluate("b", Context.of("u"), "none");
        assertEquals(Reason.PREREQUISITE_FAILED, next.reason());
        assertEquals(2, next.configVersion());
    }

    /**
     * An invalid new config throws while it is built, so apply is never reached and the old config stays live. The atomicity comes from validating in the constructors before apply, not from apply itself.
     */
    @Test
    void invalidConfigLeavesOldLive() {
        InMemoryFeatureFlags f = Fixtures.with(1, pair("y").toArray(new Flag[0]));
        Flag cyc1 = Flag.builder("a").variants("on").off("on").dflt("on").requires("b", "on").build();
        Flag cyc2 = Flag.builder("b").variants("on").off("on").dflt("on").requires("a", "on").build();
        assertThrows(InvalidConfigException.class, () -> f.apply(FlagConfig.of(2, List.of(cyc1, cyc2))));
        assertEquals(1, f.version());
        assertEquals("y", f.evaluate("b", Context.of("u"), "none").variant());
    }

    /**
     * A config whose version is not above the live one is ignored, whether older or equal.
     */
    @Test
    void olderOrEqualVersionIsIgnored() {
        InMemoryFeatureFlags f = Fixtures.with(5, pair("y").toArray(new Flag[0]));
        assertEquals(ApplyResult.STALE, f.apply(FlagConfig.of(4, pair("x"))));
        assertEquals(ApplyResult.STALE, f.apply(FlagConfig.of(5, pair("x"))));
        assertEquals(5, f.version());
        assertEquals("y", f.evaluate("a", Context.of("u"), "none").variant());
        assertEquals(ApplyResult.APPLIED, f.apply(FlagConfig.of(6, pair("x"))));
        assertEquals("x", f.evaluate("a", Context.of("u"), "none").variant());
    }
}
