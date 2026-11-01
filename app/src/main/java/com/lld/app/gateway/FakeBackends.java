package com.lld.app.gateway;

import com.lld.gateway.GatewayRequest;
import com.lld.gateway.GatewayResponse;
import com.lld.gateway.Upstream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** The in-memory backends the demo gateway forwards to. A real deployment would put HTTP clients here. */
final class FakeBackends {

    /** A backend that answers 200 with a small body, or 500 while switched to failing. */
    static final class Fake implements Upstream {
        private final String name;
        private final AtomicBoolean failing = new AtomicBoolean();
        private final AtomicBoolean crash = new AtomicBoolean();
        private final AtomicInteger calls = new AtomicInteger();
        private volatile GatewayRequest last;

        /** Creates a healthy backend with this name. */
        Fake(String name) {
            this.name = name;
        }

        /** Answers 200 echoing the method and path, or 500 while failing. The timeout is ignored because the fake is instant. */
        @Override
        public GatewayResponse handle(GatewayRequest request, Duration timeout) {
            calls.incrementAndGet();
            last = request;
            if (crash.get()) {
                throw new IllegalStateException("boom");
            }
            if (failing.get()) {
                return GatewayResponse.of(500, name + " is failing");
            }
            return GatewayResponse.of(200, name + " handled " + request.method() + " " + request.path());
        }

        /** Switches the backend between healthy and failing, so a demo or test can break it on purpose. */
        void failing(boolean value) {
            failing.set(value);
        }

        /** The last request this backend received, or null. Lets a test check what the gateway really forwarded. */
        GatewayRequest last() {
            return last;
        }

        /** Makes the backend throw instead of answering, so the adapter's last-resort handling can be tested. */
        void crashing(boolean value) {
            crash.set(value);
        }

        /** How many calls reached this backend. */
        int calls() {
            return calls.get();
        }
    }

    final Fake orders = new Fake("orders");
    final Fake billing = new Fake("billing");
}
