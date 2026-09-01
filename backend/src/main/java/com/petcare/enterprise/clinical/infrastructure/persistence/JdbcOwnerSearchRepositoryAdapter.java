package com.petcare.enterprise.clinical.infrastructure.persistence;

import com.petcare.enterprise.clinical.application.OwnerSearchRepository;
import com.petcare.enterprise.clinical.application.OwnerSearchUseCase.OwnerPage;
import com.petcare.enterprise.clinical.application.OwnerSearchUseCase.OwnerSearch;
import com.petcare.enterprise.clinical.application.OwnerSearchUseCase.OwnerSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOwnerSearchRepositoryAdapter implements OwnerSearchRepository {
    private final NamedParameterJdbcTemplate jdbc;

    JdbcOwnerSearchRepositoryAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public OwnerPage search(UUID tenantId, OwnerSearch query) {
        String pattern = "%" + query.query().trim().toLowerCase(java.util.Locale.ROOT) + "%";
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("pattern", pattern)
                .addValue("limit", query.size()).addValue("offset", query.page() * query.size());
        String predicate = "tenant_id = :tenantId AND (lower(first_name || ' ' || last_name) LIKE :pattern OR lower(coalesce(document_number, '')) LIKE :pattern OR lower(phone) LIKE :pattern)";
        Long total = jdbc.queryForObject("SELECT count(*) FROM owners WHERE " + predicate, parameters, Long.class);
        List<OwnerSummary> content = jdbc.query("""
                SELECT id, first_name, last_name, document_type, document_number, phone
                FROM owners WHERE %s
                ORDER BY %s %s, id ASC LIMIT :limit OFFSET :offset
                """.formatted(predicate, orderColumn(query), direction(query)), parameters, this::map);
        long totalElements = total == null ? 0L : total;
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / query.size());
        return new OwnerPage(content, query.page(), query.size(), totalElements, totalPages);
    }
    @Override public boolean documentExists(UUID tenantId, String documentNumber) { return count("document_number", tenantId, documentNumber) > 0; }
    @Override public boolean phoneExists(UUID tenantId, String phone) { return count("phone", tenantId, phone) > 0; }
    @Override public com.petcare.enterprise.clinical.application.OwnerRegistrationUseCase.Owner create(UUID tenantId, com.petcare.enterprise.clinical.application.OwnerRegistrationUseCase.NewOwner owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO owners (id,tenant_id,first_name,last_name,document_type,document_number,phone,email,address,notifications_accepted,active)
                VALUES (:id,:tenantId,:firstName,:lastName,:documentType,:documentNumber,:phone,:email,:address,:notificationsAccepted,true)
                """,
                new MapSqlParameterSource().addValue("id", id).addValue("tenantId", tenantId).addValue("firstName", owner.firstName()).addValue("lastName", owner.lastName()).addValue("documentType", owner.documentType()).addValue("documentNumber", owner.documentNumber()).addValue("phone", owner.phone()).addValue("email", owner.email()).addValue("address", owner.address()).addValue("notificationsAccepted", owner.notificationsAccepted()));
        return new com.petcare.enterprise.clinical.application.OwnerRegistrationUseCase.Owner(id, owner.firstName(), owner.lastName(), owner.documentType(), owner.documentNumber(), owner.phone(), owner.email(), owner.address(), owner.notificationsAccepted(), true);
    }
    private int count(String column, UUID tenantId, String value) { Integer result = jdbc.queryForObject("SELECT count(*) FROM owners WHERE tenant_id=:tenantId AND " + column + "=:value", Map.of("tenantId", tenantId, "value", value), Integer.class); return result == null ? 0 : result; }

    private OwnerSummary map(ResultSet result, int row) throws SQLException {
        return new OwnerSummary(result.getObject("id", UUID.class), result.getString("first_name"), result.getString("last_name"),
                result.getString("document_type"), result.getString("document_number"), result.getString("phone"));
    }
    private static String orderColumn(OwnerSearch query) { return switch (query.sortBy()) { case NAME -> "lower(first_name), lower(last_name)"; case DOCUMENT -> "lower(coalesce(document_number, ''))"; case PHONE -> "phone"; }; }
    private static String direction(OwnerSearch query) { return query.direction().name(); }
}
