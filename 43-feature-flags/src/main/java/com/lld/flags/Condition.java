package com.lld.flags;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * One test on one user attribute, such as plan IN [pro, team] or age GTE 18.
 * <p>Values can be String, Boolean, or a Byte, Short, Integer, Long, Float, Double, BigInteger or BigDecimal. Types are never coerced: the string "5" is not the number 5. A wrong type or an absent attribute never matches (it fails closed) and says why.
 *
 * @param attribute the attribute name to read from the user
 * @param op the comparison to make
 * @param values what to compare with (exactly one value, except for IN which takes one or more)
 */
public record Condition(String attribute, Op op, List<Object> values) {

    /** The comparisons we support. */
    public enum Op {
        /** Equal. */ EQ,
        /** Not equal (still needs a matching type). */ NEQ,
        /** Equal to any of the values. */ IN,
        /** Number greater than. */ GT,
        /** Number greater than or equal. */ GTE,
        /** Number less than. */ LT,
        /** Number less than or equal. */ LTE,
        /** String starts with. */ STARTS_WITH
    }

    /** What happened when the condition looked at a user. */
    public enum Outcome {
        /** The condition is true. */ MATCH,
        /** The condition is false. */ NO_MATCH,
        /** The user has no such attribute. */ MISSING,
        /** The attribute has a type this condition cannot compare. */ TYPE_MISMATCH
    }

    /**
     * Checks the condition is well formed and copies the value list.
     * <p>Throws {@link InvalidConfigException} for a blank attribute, no values, several values on a non-IN op, or a value of a type the op cannot use.
     */
    public Condition {
        if (attribute == null || attribute.isBlank()) {
            throw new InvalidConfigException("condition needs an attribute name");
        }
        if (op == null || values == null || values.isEmpty()) {
            throw new InvalidConfigException("condition on " + attribute + " needs an op and at least one value");
        }
        values = List.copyOf(values);
        if (op != Op.IN && values.size() != 1) {
            throw new InvalidConfigException("condition " + op + " on " + attribute + " takes exactly one value");
        }
        for (Object v : values) {
            boolean ok = switch (op) {
                case GT, GTE, LT, LTE -> isNumber(v);
                case STARTS_WITH -> v instanceof String;
                default -> v instanceof String || v instanceof Boolean || isNumber(v);
            };
            if (!ok) {
                throw new InvalidConfigException("condition " + op + " on " + attribute + " cannot use value " + v);
            }
        }
    }

    /**
     * Tests this condition against a user's attributes.
     *
     * @param attributes the user's attributes (never null)
     * @return MATCH, NO_MATCH, MISSING or TYPE_MISMATCH
     */
    public Outcome test(Map<String, Object> attributes) {
        Object actual = attributes.get(attribute);
        if (actual == null) {
            return Outcome.MISSING;
        }
        return switch (op) {
            case GT, GTE, LT, LTE -> compareNumber(actual);
            case STARTS_WITH -> actual instanceof String s
                    ? (s.startsWith((String) values.get(0)) ? Outcome.MATCH : Outcome.NO_MATCH)
                    : Outcome.TYPE_MISMATCH;
            case EQ, IN -> {
                boolean sameKind = false;
                for (Object v : values) {
                    if (kind(v) == kind(actual) && kind(actual) != 0) {
                        sameKind = true;
                        if (same(v, actual)) {
                            yield Outcome.MATCH;
                        }
                    }
                }
                yield sameKind ? Outcome.NO_MATCH : Outcome.TYPE_MISMATCH;
            }
            case NEQ -> {
                Object v = values.get(0);
                if (kind(v) != kind(actual) || kind(actual) == 0) {
                    yield Outcome.TYPE_MISMATCH;
                }
                yield same(v, actual) ? Outcome.NO_MATCH : Outcome.MATCH;
            }
        };
    }

    /**
     * Compares a numeric attribute with the one configured number for GT, GTE, LT and LTE.
     *
     * @param actual the user's attribute value
     * @return TYPE_MISMATCH if it is not a finite number, else MATCH or NO_MATCH
     */
    private Outcome compareNumber(Object actual) {
        if (!isNumber(actual)) {
            return Outcome.TYPE_MISMATCH;
        }
        int c = big(actual).compareTo(big(values.get(0)));
        boolean ok = switch (op) {
            case GT -> c > 0;
            case GTE -> c >= 0;
            case LT -> c < 0;
            default -> c <= 0;
        };
        return ok ? Outcome.MATCH : Outcome.NO_MATCH;
    }

    /**
     * Tells whether two values of the same kind are equal; numbers are compared by value so 5 equals 5.0.
     *
     * @param a one value
     * @param b the other value
     * @return true when equal
     */
    private static boolean same(Object a, Object b) {
        return kind(a) == 3 ? big(a).compareTo(big(b)) == 0 : a.equals(b);
    }

    /**
     * Sorts a value into a kind so we never compare across types: 1 string, 2 boolean, 3 finite number, 0 anything else.
     *
     * @param v the value
     * @return the kind number
     */
    private static int kind(Object v) {
        if (v instanceof String) {
            return 1;
        }
        if (v instanceof Boolean) {
            return 2;
        }
        return isNumber(v) ? 3 : 0;
    }

    /**
     * Tells whether the value is a finite number of a known JDK type (NaN, infinity and custom Number subclasses are not usable, because their toString may not parse).
     *
     * @param v the value
     * @return true for a usable number
     */
    private static boolean isNumber(Object v) {
        if (v instanceof Double d) {
            return Double.isFinite(d);
        }
        if (v instanceof Float f) {
            return Float.isFinite(f);
        }
        return v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long || v instanceof BigInteger || v instanceof BigDecimal;
    }

    /**
     * Converts a number to BigDecimal so longs and doubles compare exactly.
     *
     * @param v a number accepted by {@link #isNumber}
     * @return the exact decimal value
     */
    private static BigDecimal big(Object v) {
        return new BigDecimal(v.toString());
    }
}
