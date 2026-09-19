package com.rollbackshield.servicemapping.api;

import com.rollbackshield.catalog.domain.AppService;
import com.rollbackshield.integrations.domain.ResourceBinding;
import com.rollbackshield.integrations.domain.ServiceMapping;
import com.rollbackshield.servicemapping.application.ServiceMappingApplicationService;
import com.rollbackshield.shared.api.ValidationException;
import com.rollbackshield.shared.domain.IntegrationId;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ServiceId;
import com.rollbackshield.shared.security.CurrentPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.rollbackshield.servicemapping.api.ServiceMappingDtos.*;

/**
 * Service import and mapping API. Tenant scope is always the authenticated
 * principal; bindings are only accepted for resources a real sync discovered.
 */
@RestController
@RequestMapping("/api/v1/services")
public class ServiceMappingController {

    private final ServiceMappingApplicationService mappingService;

    public ServiceMappingController(ServiceMappingApplicationService mappingService) {
        this.mappingService = mappingService;
    }

    @PostMapping("/import")
    public ResponseEntity<ImportedServiceResponse> importService(
        @Valid @RequestBody ImportServiceRequest request) {
        OrganizationId organizationId = callerOrg();
        ServiceMappingApplicationService.ImportedService imported = mappingService.importService(
            organizationId, IntegrationId.of(request.integrationId()), request.resourceExternalId(),
            request.name());
        AppService service = imported.service();
        return ResponseEntity.status(HttpStatus.CREATED).body(new ImportedServiceResponse(
            service.id().toString(), service.organizationId().toString(), service.name(),
            toResponse(imported.mapping())));
    }

    @GetMapping("/{serviceId}/mapping")
    public MappingResponse getMapping(@PathVariable String serviceId) {
        return toResponse(mappingService.getOrEmpty(callerOrg(), ServiceId.of(serviceId)));
    }

    @PostMapping("/{serviceId}/mapping/bindings")
    public MappingResponse addBinding(@PathVariable String serviceId,
                                      @Valid @RequestBody AddBindingRequest request) {
        ResourceBinding.BindingRole role = parseRole(request.role());
        ServiceMapping mapping = mappingService.addBinding(callerOrg(), ServiceId.of(serviceId), role,
            IntegrationId.of(request.integrationId()), request.externalId(), request.evidence());
        return toResponse(mapping);
    }

    @DeleteMapping("/{serviceId}/mapping/bindings")
    public MappingResponse removeBinding(@PathVariable String serviceId,
                                         @RequestParam String role,
                                         @RequestParam String externalId) {
        ServiceMapping mapping = mappingService.removeBinding(callerOrg(), ServiceId.of(serviceId),
            parseRole(role), externalId);
        return toResponse(mapping);
    }

    private static ResourceBinding.BindingRole parseRole(String value) {
        try {
            return ResourceBinding.BindingRole.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("INVALID_BINDING_ROLE", "Unknown binding role " + value,
                Map.of("role", value));
        }
    }

    private static OrganizationId callerOrg() {
        return OrganizationId.of(CurrentPrincipal.get().organizationId());
    }

    private static MappingResponse toResponse(ServiceMapping mapping) {
        return new MappingResponse(mapping.serviceId().toString(), mapping.id().toString(),
            mapping.bindings().stream().map(binding -> new BindingResponse(binding.role().name(),
                binding.integrationId().toString(), binding.resourceType().name(), binding.externalId(),
                binding.confidence().name(), binding.evidence(), binding.boundAt().toString())).toList(),
            mapping.updatedAt().toString());
    }
}
