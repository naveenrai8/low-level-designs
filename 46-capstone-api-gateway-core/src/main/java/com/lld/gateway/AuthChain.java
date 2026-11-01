package com.lld.gateway;

import java.util.List;

/** Runs the auth hooks in order. The first rejection wins; the last identified caller is the principal. */
public final class AuthChain {

    private final List<AuthHook> hooks;

    /** Creates the chain from hooks in the order they must run. */
    public AuthChain(List<AuthHook> hooks) {
        this.hooks = List.copyOf(hooks);
    }

    /** Runs every hook until one rejects. Returns pass carrying the identified caller (null if no hook named one). */
    public AuthDecision run(GatewayRequest request, Route route) {
        Principal who = null;
        for (AuthHook hook : hooks) {
            AuthDecision d = hook.check(request, route, who);
            if (d.rejected()) {
                return d;
            }
            if (d.principal() != null) {
                who = d.principal();
            }
        }
        return AuthDecision.pass(who);
    }
}
