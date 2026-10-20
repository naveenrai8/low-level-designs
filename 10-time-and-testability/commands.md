# Topic 10: Time and Testability — Commands

Run from `low-level-designs/` with `JAVA_HOME` pointing at a JDK 21+.

```bash
mvn -q -pl 10-time-and-testability test                                  # all 16 tests, ~0.1 s
mvn -q -pl 10-time-and-testability test -Dtest='HoldRegistryTest#bug*'   # the two hold bugs
mvn -q -pl 10-time-and-testability test -Dtest=DeadlineTest              # wall clock vs monotonic

# Is NTP slewing or stepping this machine's clock? (macOS / Linux)
sntp -d time.apple.com 2>&1 | tail -3      # macOS: offset vs a reference server
timedatectl timesync-status                # Linux with systemd-timesyncd
chronyc tracking                           # Linux with chrony
```

Experiment: rewrite `fixHoldIsActiveOneNanoBeforeExpiryAndGoneAtExactlyExpiry` the "usual" way,
with `Clock.systemUTC()`, a 1-second TTL and `Thread.sleep(1_000)`. Point it at
`InclusiveExpiryHoldRegistry` and it passes, because the sleep overshoots the boundary by a few
milliseconds. The test gets slower and stops testing the thing it's named after.
