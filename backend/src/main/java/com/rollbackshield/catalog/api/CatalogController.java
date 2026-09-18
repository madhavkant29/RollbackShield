package com.rollbackshield.catalog.api;

import com.rollbackshield.catalog.application.CatalogApplicationService;
import com.rollbackshield.catalog.domain.AppService;
import com.rollbackshield.catalog.domain.Organization;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.security.CurrentPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.rollbackshield.catalog.api.CatalogDtos.*;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final CatalogApplicationService catalog;

    public CatalogController(CatalogApplicationService catalog) {
        this.catalog = catalog;
    }

    @PostMapping("/organizations")
    public ResponseEntity<OrganizationResponse> createOrganization(
        @Valid @RequestBody CreateOrganizationRequest request) {
        Organization org = catalog.createOrganization(request.name());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new OrganizationResponse(org.id().toString(), org.name()));
    }

    @PostMapping("/services")
    public ResponseEntity<ServiceResponse> createService(@Valid @RequestBody CreateServiceRequest request) {
        OrganizationId callerOrg = OrganizationId.of(CurrentPrincipal.get().organizationId());
        AppService service = catalog.createService(callerOrg, request.name());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new ServiceResponse(service.id().toString(), service.organizationId().toString(),
                service.name()));
    }

    @GetMapping("/services")
    public List<ServiceResponse> listServices() {
        OrganizationId callerOrg = OrganizationId.of(CurrentPrincipal.get().organizationId());
        return catalog.listServices(callerOrg).stream()
            .map(s -> new ServiceResponse(s.id().toString(), s.organizationId().toString(), s.name()))
            .toList();
    }
}
