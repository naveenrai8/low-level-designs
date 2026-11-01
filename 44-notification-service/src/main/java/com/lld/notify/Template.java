package com.lld.notify;

import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A message with named holes, for example {@code "Hi {{name}}, your code is {{code}}"}. A <em>placeholder</em> is one {@code {{name}}}.
 *
 * <p>A missing variable is an error, never a silent blank: "Hi , your code is " reaching a customer is worse than a clear failure at the door. Extra variables are ignored.
 *
 * <p>Filling is one pass over the template text. A value that itself contains {@code {{x}}} is inserted as plain text and never expanded again.
 *
 * @param text the template text with {@code {{name}}} placeholders
 */
public record Template(String text) {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");

    /** Rejects a null template. */
    public Template {
        Objects.requireNonNull(text, "text");
    }

    /**
     * Fills every placeholder with its variable, passing each value through {@code escaper} first. The template text itself is trusted and is not escaped.
     *
     * @throws TemplateException naming the first variable that is missing
     */
    public String render(Map<String, String> variables, UnaryOperator<String> escaper) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = variables.get(name);
            if (value == null) {
                throw new TemplateException("missing template variable: " + name);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(escaper.apply(value)));
        }
        m.appendTail(out);
        return out.toString();
    }
}
