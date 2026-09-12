package biz.thonbecker.personal.foosball.platform.tenant;

import jakarta.persistence.PrePersist;
import java.lang.reflect.Field;

/** Assigns the request tenant to newly persisted Foosball records. */
public class TenantAssignmentListener {

    private static final Long LEGACY_DEFAULT_TENANT_ID = 1L;

    @PrePersist
    public void assignTenant(Object entity) {
        try {
            final var field = findTenantIdField(entity.getClass());
            field.setAccessible(true);
            if (field.get(entity) == null) {
                field.set(entity, TenantContext.get() == null ? LEGACY_DEFAULT_TENANT_ID : TenantContext.get());
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Foosball entity is missing tenant_id", exception);
        }
    }

    private Field findTenantIdField(Class<?> type) throws NoSuchFieldException {
        var currentType = type;
        while (currentType != null) {
            try {
                return currentType.getDeclaredField("tenantId");
            } catch (NoSuchFieldException ignored) {
                currentType = currentType.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + ".tenantId");
    }
}
