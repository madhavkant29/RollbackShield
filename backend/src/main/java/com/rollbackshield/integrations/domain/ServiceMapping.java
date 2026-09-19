package com.rollbackshield.integrations.domain;

import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ServiceId;
import com.rollbackshield.shared.domain.ServiceMappingId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What a RollbackShield service is actually made of, as observed from
 * connected systems. Bindings are append/replace operations performed by the
 * discovery and mapping services; there is no arbitrary "add edge" API.
 */
public record ServiceMapping(
    ServiceMappingId id,
    OrganizationId organizationId,
    ServiceId serviceId,
    List<ResourceBinding> bindings,
    Instant createdAt,
    Instant updatedAt
) {

    public ServiceMapping {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(serviceId, "serviceId");
        bindings = List.copyOf(bindings == null ? List.of() : bindings);
    }

    public static ServiceMapping empty(OrganizationId organizationId, ServiceId serviceId) {
        Instant now = Instant.now();
        return new ServiceMapping(ServiceMappingId.newId(), organizationId, serviceId, List.of(), now, now);
    }

    public ServiceMapping withBinding(ResourceBinding binding) {
        List<ResourceBinding> next = bindings.stream()
            .filter(existing -> !(existing.role() == binding.role()
                && existing.integrationId().equals(binding.integrationId())
                && existing.externalId().equals(binding.externalId())))
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        next.add(binding);
        return new ServiceMapping(id, organizationId, serviceId, next, createdAt, Instant.now());
    }

    public ServiceMapping withoutBinding(ResourceBinding.BindingRole role, String externalId) {
        List<ResourceBinding> next = bindings.stream()
            .filter(existing -> !(existing.role() == role && existing.externalId().equals(externalId)))
            .toList();
        return new ServiceMapping(id, organizationId, serviceId, next, createdAt, Instant.now());
    }

    public List<ResourceBinding> bindingsFor(ResourceBinding.BindingRole role) {
        return bindings.stream().filter(binding -> binding.role() == role).toList();
    }

    public java.util.Optional<ResourceBinding> firstBinding(ResourceBinding.BindingRole role) {
        return bindingsFor(role).stream().findFirst();
    }
}
