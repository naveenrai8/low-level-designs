# Topic 34: Auction, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## Run the tests (core only, no Spring)

```bash
mvn -q -pl 34-auction test                                   # all 43 tests, about 2 s (estimate: Apple Silicon Mac, JDK 21.0.8, 3 runs of 1.9-2.2 s wall including Maven start)
mvn -q -pl 34-auction test -Dtest=EnglishAuctionTest         # proxy trace, rules, half-open close, reserve
mvn -q -pl 34-auction test -Dtest=SoftCloseTest              # anti-sniping window, chain, cap
mvn -q -pl 34-auction test -Dtest=ConcurrencyTest            # racy defect vs locked fix, hammer
mvn -q -pl 34-auction test -Dtest=SealedBidTest              # Vickrey and the truthful-bidding property
python3 scripts/check_style.py 34-auction                    # style gate: expect "0 problem(s)"
```

No output from `mvn -q` means every test passed.

## See the defect

`ConcurrencyTest.racyAuctionAcknowledgesTwoLeadersAndLosesABid` passes only because `RacyAuction` is broken: two receipts say `leading=true` with `seq=1` and `accepted` is 1. The fixed twin, `lockedAuctionSerialisesTheSameInterleaving`, runs the same steps against `LockedAuction` and sees one leader.

## Mutation checks

Each mutation was applied by hand to the main code, the suite was run, and the code was restored. A mutation counts as killed when the named test fails.

| Mutation | Killed by |
|---|---|
| M1: `LockedAuction.bid` reads `clock.instant()` before `lock.lock()` | `ConcurrencyTest.clockIsReadInsideTheLockSoAWaitingBidSeesTheEnd` |
| M2: `Proxy.apply` drops the `outcome != null` check | `EnglishAuctionTest.settledAuctionStaysClosedWhenTheClockStepsBack` |
| M3: `SealedBidAuction.submit` drops the `outcome != null` check | `SealedBidTest.settledSealedAuctionStaysClosedWhenTheClockStepsBack` |
| M4: `Vickrey` uses `amount < reserve` as `<=` | `SealedBidTest.bidEqualToReserveSells` |
| M5: plain `+` in the capped price | the three `EnglishAuctionTest` `NearOverflow` tests (`higherMax...`, `lowerMax...`, `minimumBid...`) |
| M6: plain `+` in the minimum-bid check | `EnglishAuctionTest.minimumBidNearOverflowIsTooLow` |
| M7: drop the reserve lift of the price | `reserveLiftsThePriceAndRaisesTheMinimumBid`, `leaderMaxEqualToReserveSells` |
