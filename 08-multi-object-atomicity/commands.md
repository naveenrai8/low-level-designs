# Topic 08: Multi-Object Atomicity — Commands

Run from `low-level-designs/` with `JAVA_HOME` pointing at a JDK 21+.

```bash
mvn -q -pl 08-multi-object-atomicity test                     # all 5 tests
mvn -q -pl 08-multi-object-atomicity test -Dtest='BankTest#bug*'   # just the two reproduced bugs

# Diagnose a real deadlock: while a hung JVM is running
jps -l                              # find the pid
jcmd <pid> Thread.print | tail -40  # look for "Found one Java-level deadlock" and the lock owners
```

Experiment: in `OrderedLockingBank.transfer`, replace the ordered `first`/`second` with
`from`/`to`. `fixOrderedLockingCompletesOpposingTransfers` then hangs until its `join` timeout,
and `fixConservesMoneyExactly…` fails its 30-second `Future.get`. Ordering is the whole fix.
