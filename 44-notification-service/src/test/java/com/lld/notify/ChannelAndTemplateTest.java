package com.lld.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Proves templating, escaping and the SMS limit. */
class ChannelAndTemplateTest {

    private final Fixture f = new Fixture(Instant.parse("2026-01-05T12:00:00Z"), 100, 3);

    /** Proves placeholders are filled by name, with spaces inside the braces allowed. */
    @Test
    void fillsNamedPlaceholders() {
        assertEquals("Hi Ann, code 42", new Template("Hi {{ name }}, code {{code}}").render(Map.of("name", "Ann", "code", "42"), v -> v));
    }

    /** Proves a missing variable is an error that names the variable, not a blank. */
    @Test
    void missingVariableIsAnError() {
        TemplateException e = assertThrows(TemplateException.class, () -> new Template("Hi {{name}}").render(Map.of(), v -> v));
        assertTrue(e.getMessage().contains("name"));
    }

    /** Proves a value that looks like a placeholder is inserted as text and not expanded a second time. */
    @Test
    void valuesAreNotReExpanded() {
        assertEquals("Hi {{code}}", new Template("Hi {{name}}").render(Map.of("name", "{{code}}", "code", "x"), v -> v));
    }

    /** Proves a dollar sign or backslash in a value is kept literally (regex replacement trap). */
    @Test
    void dollarAndBackslashSurvive() {
        assertEquals("a$1\\b", new Template("{{x}}").render(Map.of("x", "a$1\\b"), v -> v));
    }

    /** Proves email escapes HTML in values, so a user name cannot inject markup, and the ampersand is escaped exactly once. */
    @Test
    void emailEscapesHtml() {
        EmailChannel email = new EmailChannel((a, b, k) -> { });
        assertEquals("&lt;b&gt;&amp;&quot;&#39;", email.escape("<b>&\"'"));
        assertEquals("&amp;lt;", email.escape("&lt;"));
    }

    /** Proves the escaped value ends up in the stored email body but the SMS body stays raw. */
    @Test
    void escapingIsPerChannel() {
        var email = new NotificationRequest("u1", ChannelType.EMAIL, "hello", Map.of("name", "<i>", "code", "1"), Priority.NORMAL);
        var sms = new NotificationRequest("u1", ChannelType.SMS, "hello", Map.of("name", "<i>", "code", "1"), Priority.NORMAL);
        assertEquals("Hi &lt;i&gt;, code 1", f.service.find(f.service.submit("a", email).id()).get().body());
        assertEquals("Hi <i>, code 1", f.service.find(f.service.submit("b", sms).id()).get().body());
    }

    /** Proves an SMS of exactly 160 characters is kept and 161 is cut to 160 ending in three dots. */
    @Test
    void smsLimit() {
        SmsChannel sms = new SmsChannel((a, b, k) -> { });
        String exact = "x".repeat(160);
        assertEquals(exact, sms.fit(exact));
        String cut = sms.fit("x".repeat(161));
        assertEquals(160, cut.length());
        assertTrue(cut.endsWith("..."));
    }

    /** Proves a submit with a missing variable fails with a bad-request error and claims no idempotency key. */
    @Test
    void submitWithMissingVariableIsRejectedAndKeyIsFree() {
        var bad = new NotificationRequest("u1", ChannelType.EMAIL, "hello", Map.of("name", "Ann"), Priority.NORMAL);
        assertThrows(InvalidRequestException.class, () -> f.service.submit("k", bad));
        assertEquals(false, f.service.submit("k", Fixture.hello("u1", ChannelType.EMAIL)).replay());
    }

    /** Proves unknown users, templates and a channel without an address are rejected. */
    @Test
    void unknownThingsAreRejected() {
        assertThrows(InvalidRequestException.class, () -> f.service.submit("k1", Fixture.hello("nobody", ChannelType.EMAIL)));
        assertThrows(InvalidRequestException.class, () -> f.service.submit("k2", new NotificationRequest("u1", ChannelType.EMAIL, "nope", Map.of(), Priority.NORMAL)));
        assertThrows(InvalidRequestException.class, () -> f.service.submit(" ", Fixture.hello("u1", ChannelType.EMAIL)));
    }

    /** Proves the right channel strategy is used for each type. */
    @Test
    void routesToTheRequestedChannel() {
        f.service.submit("a", Fixture.hello("u1", ChannelType.SMS));
        f.service.runDue();
        assertEquals("+100|Hi Ann, code 42", f.sent.get(0));
    }
}
