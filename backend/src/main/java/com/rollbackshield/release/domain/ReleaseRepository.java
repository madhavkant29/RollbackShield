package com.rollbackshield.release.domain;

import com.rollbackshield.shared.domain.OrganizationId;
import com.rollbackshield.shared.domain.ReleaseId;
import com.rollbackshield.shared.domain.ServiceId;

import java.util.List;
import java.util.Optional;

public interface ReleaseRepository {

    Release save(Release release);

    Optional<Release> findById(ReleaseId id);

    List<Release> findByService(ServiceId serviceId);

    /**
     * Conditional save used by state-changing operations: succeeds only if
     * the stored release is still at `expectedState` (§22 optimistic
     * concurrency -- e.g. rollback vs rollback, commit vs rollback races).
     */
    boolean compareAndSave(Release release, ReleaseState expectedState);
}
