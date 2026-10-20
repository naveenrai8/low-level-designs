# Topic 12: Common Mistakes — Commands

Run from `low-level-designs/` with `JAVA_HOME` pointing at a JDK 21+.

```bash
mvn -q -pl 12-common-mistakes test                                   # all 30 tests
mvn -q -pl 12-common-mistakes test -Dtest='*Test#bug*'               # only the reproduced mistakes
mvn -q -pl 12-common-mistakes test -Dtest=MoneySplitTest             # M6
mvn -q -pl 12-common-mistakes test -Dtest=EventBusTest               # M7, including the silent skip
```

Reproduce the M6 floating-point table yourself:

```bash
cat > /tmp/fp.jsh <<'EOF'
for (double total : new double[]{1.00, 10.00, 20.00, 50.00, 100.00, 1000.00}) {
  double share = Math.round(total / 3 * 100.0) / 100.0; double sum = share * 3;
  System.out.printf("total=%.2f sum=%s diff=%s passesDelta0.01=%s%n",
      total, sum, total - sum, Math.abs(total - sum) <= 0.01);
}
/exit
EOF
"$JAVA_HOME/bin/jshell" -q /tmp/fp.jsh
```

Measured output (JDK 21.0.8): $1.00, $50.00 and $100.00 fail the tolerance check, while $10.00,
$20.00 and $1,000.00 pass. Every one of them is off by exactly one cent.

Reproduce the M7 iterator quirk:

```bash
cat > /tmp/it.jsh <<'EOF'
var l = new java.util.ArrayList<>(java.util.List.of("once", "always"));
var seen = new java.util.ArrayList<String>();
for (String s : l) { seen.add(s); if (s.equals("once")) l.remove(s); }
System.out.println(seen);   // [once]: "always" skipped, no exception
/exit
EOF
"$JAVA_HOME/bin/jshell" -q /tmp/it.jsh
```
