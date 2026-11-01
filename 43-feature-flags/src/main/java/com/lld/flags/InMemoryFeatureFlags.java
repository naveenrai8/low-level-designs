package com.lld.flags;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The flag evaluator. The live config is one immutable {@link FlagConfig} in an AtomicReference.
 * <p>Concurrency model: readers do one {@code get()} at the start and use that object for the whole evaluation, so they see all-old or all-new. Writers replace the reference with compare-and-set, only if the version goes up. Nothing is locked, so readers never wait.
 */
public final class InMemoryFeatureFlags implements FeatureFlags {
    private final AtomicReference<FlagConfig> live = new AtomicReference<>(new FlagConfig(0, Map.of()));
    private final Clock clock;
    private final EventSink sink;
    private final int sampleBasisPoints;
    private final Runnable insideEvaluate;
    private final Runnable beforeSwap;
    private final AtomicLong sinkFailures = new AtomicLong();

    /**
     * Creates the evaluator.
     *
     * @param clock supplies timestamps for events
     * @param sink where sampled events go
     * @param sampleBasisPoints share of users whose evaluations are reported: 0 none, 10,000 all
     */
    public InMemoryFeatureFlags(Clock clock, EventSink sink, int sampleBasisPoints) {
        this(clock, sink, sampleBasisPoints, () -> { });
    }

    /**
     * Creates the evaluator with a test hook that runs each time a flag's evaluation starts (after the snapshot was read).
     * <p>A test uses it to swap the config in the middle of an evaluation, with no sleeping.
     *
     * @param clock supplies timestamps for events
     * @param sink where sampled events go
     * @param sampleBasisPoints share of users whose evaluations are reported
     * @param insideEvaluate the hook
     */
    InMemoryFeatureFlags(Clock clock, EventSink sink, int sampleBasisPoints, Runnable insideEvaluate) {
        this(clock, sink, sampleBasisPoints, insideEvaluate, () -> { });
    }

    /**
     * Creates the evaluator with two test hooks: one inside each flag decision, and one in {@link #apply} between reading the live config and the compare-and-set.
     * <p>The second hook lets a test make another writer win the race at an exact point, with no sleeping.
     *
     * @param clock supplies timestamps for events
     * @param sink where sampled events go
     * @param sampleBasisPoints share of users whose evaluations are reported
     * @param insideEvaluate runs when a flag decision starts
     * @param beforeSwap runs just before each compare-and-set attempt
     */
    InMemoryFeatureFlags(Clock clock, EventSink sink, int sampleBasisPoints, Runnable insideEvaluate, Runnable beforeSwap) {
        if (sampleBasisPoints < 0 || sampleBasisPoints > Bucketing.BUCKETS) {
            throw new IllegalArgumentException("sample basis points must be 0.." + Bucketing.BUCKETS);
        }
        this.clock = clock;
        this.sink = sink;
        this.sampleBasisPoints = sampleBasisPoints;
        this.insideEvaluate = insideEvaluate;
        this.beforeSwap = beforeSwap;
    }

    /**
     * Swaps the live config in with compare-and-set, but only if the new version is higher; otherwise reports STALE and changes nothing.
     * <p>If another writer wins the race, it re-reads and tries again, so the highest version always ends up live.
     *
     * @param next an already validated config
     * @return APPLIED or STALE
     */
    @Override
    public ApplyResult apply(FlagConfig next) {
        while (true) {
            FlagConfig current = live.get();
            if (next.version() <= current.version()) {
                return ApplyResult.STALE;
            }
            beforeSwap.run();
            if (live.compareAndSet(current, next)) {
                return ApplyResult.APPLIED;
            }
        }
    }

    /**
     * Says which config version is live right now.
     *
     * @return the version
     */
    @Override
    public long version() {
        return live.get().version();
    }

    /**
     * Counts events lost because the sink threw an exception.
     *
     * @return the number of failed emits
     */
    public long sinkFailures() {
        return sinkFailures.get();
    }

    /**
     * Reads the live snapshot once, decides, then reports an event if the user is sampled.
     *
     * @param flagKey the flag
     * @param context the user
     * @param fallback returned when the flag does not exist
     * @return the decision
     */
    @Override
    public Evaluation evaluate(String flagKey, Context context, String fallback) {
        FlagConfig snapshot = live.get();
        Flag flag = snapshot.flags().get(flagKey);
        Evaluation result;
        if (flag == null) {
            result = new Evaluation(flagKey, fallback, Reason.FLAG_NOT_FOUND, null, snapshot.version(), "");
        } else {
            result = decide(snapshot, flag, context);
        }
        report(flag, context, result);
        return result;
    }

    /**
     * Runs the precedence ladder for one flag against one snapshot: off, prerequisites, targets, rules, rollout, default.
     * <p>Prerequisite flags are decided by the same method and the same snapshot, which is what stops a mid-evaluation swap from mixing versions.
     *
     * @param snapshot the one snapshot for the whole evaluation
     * @param flag the flag to decide
     * @param ctx the user
     * @return the decision
     */
    private Evaluation decide(FlagConfig snapshot, Flag flag, Context ctx) {
        insideEvaluate.run();
        long v = snapshot.version();
        if (!flag.enabled()) {
            return new Evaluation(flag.key(), flag.offVariant(), Reason.OFF, null, v, "");
        }
        for (Prerequisite p : flag.prerequisites()) {
            Evaluation need = decide(snapshot, snapshot.flags().get(p.flagKey()), ctx);
            if (need.reason() == Reason.OFF || !need.variant().equals(p.variant())) {
                String why = "needs " + p.flagKey() + "=" + p.variant() + " but it served " + need.variant() + (need.reason() == Reason.OFF ? " (flag is off)" : "");
                return new Evaluation(flag.key(), flag.offVariant(), Reason.PREREQUISITE_FAILED, null, v, why);
            }
        }
        String user = ctx.userId();
        if (user != null && flag.targets().containsKey(user)) {
            return new Evaluation(flag.key(), flag.targets().get(user), Reason.TARGET, null, v, "");
        }
        List<String> notes = new ArrayList<>();
        for (Rule rule : flag.rules()) {
            if (rule.matches(ctx.attributes(), notes)) {
                return new Evaluation(flag.key(), rule.variant(), Reason.RULE, rule.id(), v, String.join("; ", notes));
            }
        }
        if (flag.rollout() != null) {
            if (user != null) {
                int bucket = Bucketing.bucket(flag.key(), flag.salt(), user);
                return new Evaluation(flag.key(), flag.rollout().variantFor(bucket), Reason.ROLLOUT, null, v, String.join("; ", notes));
            }
            notes.add("no user id, rollout skipped");
        }
        return new Evaluation(flag.key(), flag.defaultVariant(), Reason.DEFAULT, null, v, String.join("; ", notes));
    }

    /**
     * Sends an event to the sink if this user is sampled. A failing sink is counted and swallowed so it can never break evaluation.
     *
     * @param flag the flag (null if unknown)
     * @param ctx the user
     * @param result what was decided
     */
    private void report(Flag flag, Context ctx, Evaluation result) {
        String salt = flag == null ? "" : flag.salt();
        if (!sampled(result.flagKey(), salt, ctx.userId())) {
            return;
        }
        try {
            sink.emit(new EvaluationEvent(clock.instant(), result.flagKey(), result.variant(), result.reason(), result.ruleId(), result.configVersion(), ctx.userId()));
        } catch (RuntimeException e) {
            sinkFailures.incrementAndGet();
        }
    }

    /**
     * Decides whether a user's evaluations are reported. It is a hash of the user, not a random draw, so a user is always in or always out.
     * <p>The sampling hash uses a different salt from rollout, so being sampled says nothing about being in a rollout.
     *
     * @param flagKey the flag
     * @param salt the flag's salt
     * @param userId the user (null is never sampled unless the rate is 100%)
     * @return true when events for this user should be sent
     */
    boolean sampled(String flagKey, String salt, String userId) {
        if (sampleBasisPoints == 0) {
            return false;
        }
        if (sampleBasisPoints >= Bucketing.BUCKETS) {
            return true;
        }
        return userId != null && Bucketing.bucket(flagKey, salt + "/sample", userId) < sampleBasisPoints;
    }
}
