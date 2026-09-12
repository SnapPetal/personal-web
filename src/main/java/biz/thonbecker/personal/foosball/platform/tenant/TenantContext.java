package biz.thonbecker.personal.foosball.platform.tenant;

import java.util.Objects;

/** Holds the tenant selected by the current Foosball request. */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT_TENANT_ID = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Long tenantId) {
        CURRENT_TENANT_ID.set(Objects.requireNonNull(tenantId, "tenantId"));
    }

    public static Long get() {
        return CURRENT_TENANT_ID.get();
    }

    public static long requireTenantId() {
        final var tenantId = get();
        if (tenantId == null) {
            throw new IllegalStateException("No Foosball tenant is selected");
        }
        return tenantId;
    }

    public static void clear() {
        CURRENT_TENANT_ID.remove();
    }
}
