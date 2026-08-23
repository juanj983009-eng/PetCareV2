package com.petcare.enterprise.tenancy.infrastructure.persistence;

import com.petcare.enterprise.tenancy.application.TenantPersistenceConflictException;
import com.petcare.enterprise.tenancy.application.TenantRepository;
import com.petcare.enterprise.tenancy.application.TenantSlugAlreadyExistsException;
import com.petcare.enterprise.tenancy.domain.Tenant;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
class JpaTenantRepositoryAdapter implements TenantRepository {

    private final SpringDataTenantRepository repository;

    JpaTenantRepositoryAdapter(SpringDataTenantRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean existsBySlug(String slug) {
        return repository.existsBySlug(slug);
    }

    @Override
    public void save(Tenant tenant) {
        try {
            repository.saveAndFlush(TenantJpaEntity.from(tenant));
        } catch (DataIntegrityViolationException exception) {
            if (rootMessage(exception).contains("uk_tenants_slug")) {
                throw new TenantSlugAlreadyExistsException();
            }
            throw new TenantPersistenceConflictException("Tenant data conflicts with an existing record", exception);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
    }
}
