package com.petcare.enterprise.clinical.infrastructure.persistence;

import com.petcare.enterprise.clinical.application.PatientSearchRepository;
import com.petcare.enterprise.clinical.application.PatientSearchUseCase.PatientPage;
import com.petcare.enterprise.clinical.application.PatientSearchUseCase.PatientSearch;
import com.petcare.enterprise.clinical.application.PatientSearchUseCase.PatientSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPatientSearchRepositoryAdapter implements PatientSearchRepository {
    private final NamedParameterJdbcTemplate jdbc;

    JdbcPatientSearchRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PatientPage search(UUID tenantId, PatientSearch query) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("limit", query.size())
                .addValue("offset", query.page() * query.size());

        List<String> predicates = new ArrayList<>();
        predicates.add("p.tenant_id = :tenantId");
        predicates.add("o.tenant_id = :tenantId");

        if (query.query() != null && !query.query().isBlank()) {
            String rawTrimmed = query.query().trim().toLowerCase(Locale.ROOT);
            String pattern = "%" + rawTrimmed + "%";
            String alnumTrimmed = rawTrimmed.replaceAll("[^a-z0-9]", "");
            parameters.addValue("pattern", pattern);
            parameters.addValue("alnumPattern", alnumTrimmed.isEmpty() ? pattern : "%" + alnumTrimmed + "%");
            predicates.add("""
                    (lower(p.name) LIKE :pattern
                     OR lower(o.first_name || ' ' || o.last_name) LIKE :pattern
                     OR lower(coalesce(o.document_number, '')) LIKE :pattern
                     OR lower(regexp_replace(coalesce(o.document_number, ''), '[^a-zA-Z0-9]', '', 'g')) LIKE :alnumPattern
                     OR lower(coalesce(p.microchip, '')) LIKE :pattern
                     OR lower(regexp_replace(coalesce(p.microchip, ''), '[^a-zA-Z0-9]', '', 'g')) LIKE :alnumPattern)
                    """);
        }

        if (query.species() != null && !query.species().isBlank()) {
            parameters.addValue("species", query.species().trim().toLowerCase(Locale.ROOT));
            predicates.add("lower(p.species) = :species");
        }

        if (query.status() != null && !query.status().isBlank()) {
            parameters.addValue("status", query.status().trim().toUpperCase(Locale.ROOT));
            predicates.add("p.status = :status");
        }

        String whereClause = String.join(" AND ", predicates);

        Long total = jdbc.queryForObject("""
                SELECT count(*)
                FROM patients p
                JOIN owners o ON o.tenant_id = p.tenant_id AND o.id = p.owner_id
                WHERE %s
                """.formatted(whereClause), parameters, Long.class);

        List<PatientSummary> content = jdbc.query("""
                SELECT p.id,
                       p.name,
                       p.species,
                       p.breed,
                       p.sex,
                       p.microchip,
                       p.status,
                       o.id AS owner_id,
                       trim(o.first_name || ' ' || o.last_name) AS owner_full_name,
                       o.document_type AS owner_document_type,
                       o.document_number AS owner_document_number
                FROM patients p
                JOIN owners o ON o.tenant_id = p.tenant_id AND o.id = p.owner_id
                WHERE %s
                ORDER BY %s, p.id ASC
                LIMIT :limit OFFSET :offset
                """.formatted(whereClause, orderByClause(query)), parameters, this::map);

        long totalElements = total == null ? 0L : total;
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / query.size());
        return new PatientPage(content, query.page(), query.size(), totalElements, totalPages);
    }

    private PatientSummary map(ResultSet result, int row) throws SQLException {
        return new PatientSummary(
                result.getObject("id", UUID.class),
                result.getString("name"),
                result.getString("species"),
                result.getString("breed"),
                result.getString("sex"),
                result.getString("microchip"),
                result.getString("status"),
                result.getObject("owner_id", UUID.class),
                result.getString("owner_full_name"),
                result.getString("owner_document_type"),
                result.getString("owner_document_number"));
    }

    private static String orderByClause(PatientSearch query) {
        String dir = query.direction().name();
        return switch (query.sortBy()) {
            case NAME -> "lower(p.name) " + dir;
            case SPECIES -> "lower(p.species) " + dir + ", lower(p.name) ASC";
            case OWNER_NAME -> "lower(o.first_name) " + dir + ", lower(o.last_name) " + dir + ", lower(p.name) ASC";
            case STATUS -> "p.status " + dir + ", lower(p.name) ASC";
            case CREATED_AT -> "p.created_at " + dir;
        };
    }
}
