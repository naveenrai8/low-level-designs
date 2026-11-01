package com.lld.popularity;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Popularity for many tenants (customers) in one process, with no data shared between them.
 *
 * <p>Isolation: each tenant gets its own {@link TopK}, so one tenant's items can never show up in another tenant's answer.
 *
 * <p>Memory policy: every tenant is created from the same factory, and the caller supplies the factory, and the memory bound holds only if it builds fixed-size trackers such as {@link SketchWindowedTopK} (the exact {@link WindowedTopK} grows with distinct ids). Heap entries hold id strings, so limit id length at the edge. A noisy tenant that floods a million distinct ids can only fill its own tables, so it cannot slow down, evict or distort anyone else. Total memory is at most {@code maxTenants * perTenantBytes}. When {@code maxTenants} is reached a new tenant is refused with {@link TenantLimitException}; existing tenants are never evicted.
 *
 * <p>Locking: the tenant map is a {@link ConcurrentHashMap} (lock-free reads), and each tenant's tracker has its own lock. Two tenants never contend; writers on the same tenant queue on that tenant's lock only. A global lock would make one busy tenant stall all others; striping by hash would let two unrelated tenants share a stripe by accident.
 */
public final class MultiTenantPopularity {

    private final ConcurrentHashMap<String, TopK> tenants = new ConcurrentHashMap<>();
    private final Supplier<TopK> factory;
    private final int maxTenants;

    /** Creates a tracker that builds each tenant's state with {@code factory} and holds at most {@code maxTenants} tenants. */
    public MultiTenantPopularity(Supplier<TopK> factory, int maxTenants) {
        if (maxTenants <= 0) {
            throw new IllegalArgumentException("maxTenants must be > 0");
        }
        this.factory = factory;
        this.maxTenants = maxTenants;
    }

    /** Counts one event for {@code item} in {@code tenant}'s own state. Throws {@link TenantLimitException} if the tenant is new and the limit is reached. */
    public void record(String tenant, String item) {
        tenantState(tenant, true).record(item);
    }

    /** Returns the tenant's best {@code k} items; a negative {@code k} throws for every tenant, known or not. An unknown tenant gets an empty list and is not created, so reads cannot be used to fill the tenant table. */
    public List<Ranked> topK(String tenant, int k) {
        if (k < 0) {
            throw new IllegalArgumentException("k must be >= 0");
        }
        TopK t = tenantState(tenant, false);
        return t == null ? List.of() : t.topK(k);
    }

    /** Returns how many tenants are held. */
    public int tenantCount() {
        return tenants.size();
    }

    /** Finds a tenant's tracker, creating it when {@code create} is true. Creation is serialised on one short lock (only for new tenants; the hot path is the lock-free get above) so two racing new tenants cannot both slip past the limit. */
    private TopK tenantState(String tenant, boolean create) {
        Validation.item(tenant);
        TopK t = tenants.get(tenant);
        if (t != null || !create) {
            return t;
        }
        synchronized (tenants) {
            TopK again = tenants.get(tenant);
            if (again != null) {
                return again;
            }
            if (tenants.size() >= maxTenants) {
                throw new TenantLimitException(maxTenants);
            }
            TopK fresh = factory.get();
            tenants.put(tenant, fresh);
            return fresh;
        }
    }
}
