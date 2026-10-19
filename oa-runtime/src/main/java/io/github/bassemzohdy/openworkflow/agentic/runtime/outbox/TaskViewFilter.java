package io.github.bassemzohdy.openworkflow.agentic.runtime.outbox;

import io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle.InstanceStatus;
import java.util.Objects;

/**
 * The filter of a {@link TaskViewQuery#list} call. The tenant is required: a query never crosses tenants.
 *
 * @param tenant       the tenant whose instances are listed
 * @param status       only instances in this status, or null for all
 * @param definitionId only instances of this definition, or null for all
 */
public record TaskViewFilter(String tenant, InstanceStatus status, String definitionId) {

    public TaskViewFilter {
        Objects.requireNonNull(tenant, "tenant");
        if (tenant.isBlank()) {
            throw new IllegalArgumentException("tenant must not be blank");
        }
    }

    public static TaskViewFilter forTenant(String tenant) {
        return new TaskViewFilter(tenant, null, null);
    }
}
