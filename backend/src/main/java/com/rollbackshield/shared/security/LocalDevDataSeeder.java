package com.rollbackshield.shared.security;

import com.rollbackshield.catalog.domain.Organization;
import com.rollbackshield.catalog.domain.OrganizationRepository;
import com.rollbackshield.shared.domain.OrganizationId;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Local-dev-only startup seed: ensures the fixed organization referenced by
 * {@link LocalDevAuthFilter#LOCAL_DEV_ORGANIZATION_ID} actually exists, so
 * every request under the 'local' profile resolves to a real tenant instead
 * of failing catalog lookups. Never wired outside the 'local' profile.
 */
@Component
@Profile("local")
public class LocalDevDataSeeder implements ApplicationRunner {

    private final OrganizationRepository organizations;

    public LocalDevDataSeeder(OrganizationRepository organizations) {
        this.organizations = organizations;
    }

    @Override
    public void run(ApplicationArguments args) {
        OrganizationId id = OrganizationId.of(LocalDevAuthFilter.LOCAL_DEV_ORGANIZATION_ID);
        if (organizations.findById(id).isEmpty()) {
            organizations.save(new Organization(id, "Local Dev Org", Instant.now()));
        }
    }
}
