package com.rollbackshield.integration;

import com.rollbackshield.audit.adapter.InMemoryAuditTrail;
import com.rollbackshield.catalog.adapter.InMemoryAppServiceRepository;
import com.rollbackshield.catalog.adapter.InMemoryOrganizationRepository;
import com.rollbackshield.catalog.application.CatalogApplicationService;
import com.rollbackshield.catalog.domain.AppService;
import com.rollbackshield.catalog.domain.Organization;
import com.rollbackshield.release.adapter.InMemoryReleaseRepository;
import com.rollbackshield.release.application.ReleaseApplicationService;
import com.rollbackshield.release.domain.Release;
import com.rollbackshield.shared.api.NotFoundException;
import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.workfence.adapter.InMemoryWorkQueue;
import com.rollbackshield.workfence.application.WorkFenceApplicationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §30/§37: proves organization A cannot read organization B's services or
 * releases through the application-service layer -- the layer every
 * controller in api/ goes through. Deliberately does NOT go through MockMvc/
 * HTTP: LocalDevAuthFilter fixes one principal per process, so cross-tenant
 * behavior is exercised directly against the application services with two
 * distinct OrganizationIds, exactly like two different Cognito principals
 * would produce in production.
 */
class TenantIsolationTest {

    private final InMemoryOrganizationRepository organizations = new InMemoryOrganizationRepository();
    private final InMemoryAppServiceRepository services = new InMemoryAppServiceRepository();
    private final InMemoryReleaseRepository releases = new InMemoryReleaseRepository();
    private final InMemoryAuditTrail auditTrail = new InMemoryAuditTrail();
    private final WorkFenceApplicationService workFence = new WorkFenceApplicationService(
        releases, new InMemoryWorkQueue(), auditTrail, event -> { });

    private final CatalogApplicationService catalog = new CatalogApplicationService(organizations, services);
    private final ReleaseApplicationService releaseService = new ReleaseApplicationService(
        releases, auditTrail, workFence, event -> { });

    @Test
    void organizationCannotReadAnotherOrganizationsService() {
        OrganizationId orgA = OrganizationId.newId();
        OrganizationId orgB = OrganizationId.newId();
        organizations.save(new Organization(orgA, "Org A", Instant.now()));
        organizations.save(new Organization(orgB, "Org B", Instant.now()));

        AppService serviceInA = catalog.createService(orgA, "checkout");

        assertThatThrownBy(() -> catalog.getService(serviceInA.id(), orgB))
            .isInstanceOf(NotFoundException.class);

        // The legitimate owner can still read it -- this isn't a blanket failure.
        assertThat(catalog.getService(serviceInA.id(), orgA)).isEqualTo(serviceInA);
    }

    @Test
    void organizationCannotReadAnotherOrganizationsRelease() {
        OrganizationId orgA = OrganizationId.newId();
        OrganizationId orgB = OrganizationId.newId();
        organizations.save(new Organization(orgA, "Org A", Instant.now()));

        AppService serviceInA = catalog.createService(orgA, "checkout");
        Release releaseInA = releaseService.create(orgA, serviceInA.id(), "v1", "v2");

        assertThatThrownBy(() -> releaseService.get(releaseInA.id(), orgB))
            .isInstanceOf(NotFoundException.class);

        assertThat(releaseService.get(releaseInA.id(), orgA)).isEqualTo(releaseInA);
    }
}
