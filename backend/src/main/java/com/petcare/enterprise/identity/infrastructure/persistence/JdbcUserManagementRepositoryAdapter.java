package com.petcare.enterprise.identity.infrastructure.persistence;

import com.petcare.enterprise.identity.application.UserManagementException;
import com.petcare.enterprise.identity.application.UserManagementRepository;
import com.petcare.enterprise.identity.application.UserManagementUseCase.BranchOption;
import com.petcare.enterprise.identity.application.UserManagementUseCase.ManagedUser;
import com.petcare.enterprise.identity.application.UserManagementUseCase.RoleOption;
import com.petcare.enterprise.identity.application.UserManagementUseCase.SortDirection;
import com.petcare.enterprise.identity.application.UserManagementUseCase.SortField;
import com.petcare.enterprise.identity.application.UserManagementUseCase.UserPage;
import com.petcare.enterprise.identity.application.UserManagementUseCase.UserSearch;
import com.petcare.enterprise.identity.domain.EmailAddress;
import com.petcare.enterprise.identity.domain.UserStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcUserManagementRepositoryAdapter implements UserManagementRepository {

    private static final String USER_SELECT = """
            SELECT u.id, u.first_name, u.last_name, u.email, u.status,
                   u.created_at, u.updated_at, u.version,
                   selected_role.id AS role_id, selected_role.name AS role_name,
                   selected_branch.id AS branch_id, selected_branch.name AS branch_name,
                   selected_branch.active AS branch_active
            FROM users u
            LEFT JOIN LATERAL (
                SELECT r.id, r.name
                FROM user_roles ur
                JOIN roles r ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
                WHERE ur.tenant_id = u.tenant_id AND ur.user_id = u.id
                ORDER BY ur.assigned_at, r.name, r.id
                LIMIT 1
            ) selected_role ON true
            LEFT JOIN LATERAL (
                SELECT b.id, b.name, b.active
                FROM user_branches ub
                JOIN branches b ON b.tenant_id = ub.tenant_id AND b.id = ub.branch_id
                WHERE ub.tenant_id = u.tenant_id AND ub.user_id = u.id
                ORDER BY ub.primary_branch DESC, ub.assigned_at, b.name, b.id
                LIMIT 1
            ) selected_branch ON true
            """;

    private static final RowMapper<ManagedUser> USER_MAPPER = JdbcUserManagementRepositoryAdapter::mapUser;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcUserManagementRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserPage findAll(UUID tenantId, UserSearch search) {
        StringBuilder where = new StringBuilder(" WHERE u.tenant_id = :tenantId");
        MapSqlParameterSource parameters = new MapSqlParameterSource("tenantId", tenantId);
        if (search.search() != null && !search.search().isBlank()) {
            where.append("""
                     AND (lower(u.first_name || ' ' || u.last_name) LIKE :search
                          OR lower(u.email) LIKE :search)
                    """);
            parameters.addValue("search", "%" + search.search().trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (search.roleId() != null) {
            where.append("""
                     AND EXISTS (
                         SELECT 1 FROM user_roles filtered_role
                         WHERE filtered_role.tenant_id = u.tenant_id
                           AND filtered_role.user_id = u.id
                           AND filtered_role.role_id = :roleId)
                    """);
            parameters.addValue("roleId", search.roleId());
        }
        if (search.status() != null) {
            where.append(" AND u.status = :status");
            parameters.addValue("status", search.status().name());
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM users u" + where,
                parameters,
                Long.class);
        parameters.addValue("limit", search.size());
        parameters.addValue("offset", (long) search.page() * search.size());
        String order = orderColumn(search.sortField()) + " " + direction(search.direction()) + ", u.id ASC";
        List<ManagedUser> content = jdbc.query(
                USER_SELECT + where + " ORDER BY " + order + " LIMIT :limit OFFSET :offset",
                parameters,
                USER_MAPPER);
        long totalElements = total == null ? 0 : total;
        int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + search.size() - 1) / search.size());
        return new UserPage(content, search.page(), search.size(), totalElements, totalPages);
    }

    @Override
    public Optional<ManagedUser> findByTenantIdAndId(UUID tenantId, UUID userId) {
        return jdbc.query(
                        USER_SELECT + " WHERE u.tenant_id = :tenantId AND u.id = :userId",
                        Map.of("tenantId", tenantId, "userId", userId),
                        USER_MAPPER)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<RoleOption> findRoleByTenantIdAndId(UUID tenantId, UUID roleId) {
        return jdbc.query(
                        "SELECT id, name FROM roles WHERE tenant_id = :tenantId AND id = :roleId",
                        Map.of("tenantId", tenantId, "roleId", roleId),
                        (result, row) -> new RoleOption(uuid(result, "id"), result.getString("name")))
                .stream()
                .findFirst();
    }

    @Override
    public List<RoleOption> findRolesByTenantId(UUID tenantId) {
        return jdbc.query(
                "SELECT id, name FROM roles WHERE tenant_id = :tenantId ORDER BY name, id",
                Map.of("tenantId", tenantId),
                (result, row) -> new RoleOption(uuid(result, "id"), result.getString("name")));
    }

    @Override
    public boolean existsByTenantIdAndEmail(UUID tenantId, EmailAddress email, UUID excludingUserId) {
        String sql = "SELECT count(*) FROM users WHERE tenant_id = :tenantId AND email = :email";
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("email", email.value());
        if (excludingUserId != null) {
            sql += " AND id <> :excludingUserId";
            parameters.addValue("excludingUserId", excludingUserId);
        }
        Integer count = jdbc.queryForObject(sql, parameters, Integer.class);
        return count != null && count > 0;
    }

    @Override
    public ManagedUser create(NewUser user) {
        try {
            jdbc.update("""
                    INSERT INTO users (
                        id, tenant_id, first_name, last_name, email, password_hash,
                        status, created_at, updated_at, version
                    ) VALUES (
                        :id, :tenantId, :firstName, :lastName, :email, :passwordHash,
                        'ACTIVO', :createdAt, :createdAt, 0
                    )
                    """, userParameters(user));
            replaceAssociations(
                    user.tenantId(), user.id(), user.roleId(), user.branchId(), user.createdAt());
            return findByTenantIdAndId(user.tenantId(), user.id()).orElseThrow();
        } catch (DataIntegrityViolationException exception) {
            throw UserManagementException.emailConflict();
        }
    }

    @Override
    public Optional<ManagedUser> update(UpdatedUser user) {
        try {
            int updated = jdbc.update("""
                    UPDATE users
                       SET first_name = :firstName,
                           last_name = :lastName,
                           email = :email,
                           updated_at = :updatedAt,
                           version = version + 1
                     WHERE tenant_id = :tenantId AND id = :id AND version = :version
                    """, new MapSqlParameterSource()
                    .addValue("firstName", normalizedName(user.firstName()))
                    .addValue("lastName", normalizedName(user.lastName()))
                    .addValue("email", user.email().value())
                    .addValue("updatedAt", Timestamp.from(user.updatedAt()))
                    .addValue("tenantId", user.tenantId())
                    .addValue("id", user.id())
                    .addValue("version", user.version()));
            if (updated != 1) {
                return Optional.empty();
            }
            replaceAssociations(user.tenantId(), user.id(), user.roleId(), user.branchId(), user.updatedAt());
            return findByTenantIdAndId(user.tenantId(), user.id());
        } catch (DataIntegrityViolationException exception) {
            throw UserManagementException.emailConflict();
        }
    }

    @Override
    public Optional<ManagedUser> updateStatus(
            UUID tenantId,
            UUID userId,
            UserStatus status,
            long version,
            Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE users
                   SET status = :status, updated_at = :updatedAt, version = version + 1
                 WHERE tenant_id = :tenantId AND id = :userId AND version = :version
                """, new MapSqlParameterSource()
                .addValue("status", status.name())
                .addValue("updatedAt", Timestamp.from(updatedAt))
                .addValue("tenantId", tenantId)
                .addValue("userId", userId)
                .addValue("version", version));
        return updated == 1 ? findByTenantIdAndId(tenantId, userId) : Optional.empty();
    }

    private void replaceAssociations(
            UUID tenantId,
            UUID userId,
            UUID roleId,
            UUID branchId,
            Instant assignedAt) {
        Map<String, Object> keys = Map.of("tenantId", tenantId, "userId", userId);
        jdbc.update(
                "DELETE FROM user_roles WHERE tenant_id = :tenantId AND user_id = :userId",
                keys);
        jdbc.update("""
                INSERT INTO user_roles (tenant_id, user_id, role_id, assigned_at)
                VALUES (:tenantId, :userId, :roleId, :assignedAt)
                """, new MapSqlParameterSource(keys)
                .addValue("roleId", roleId)
                .addValue("assignedAt", Timestamp.from(assignedAt)));
        jdbc.update(
                "DELETE FROM user_branches WHERE tenant_id = :tenantId AND user_id = :userId",
                keys);
        jdbc.update("""
                INSERT INTO user_branches (
                    tenant_id, user_id, branch_id, primary_branch, assigned_at
                ) VALUES (:tenantId, :userId, :branchId, true, :assignedAt)
                """, new MapSqlParameterSource(keys)
                .addValue("branchId", branchId)
                .addValue("assignedAt", Timestamp.from(assignedAt)));
    }

    private static MapSqlParameterSource userParameters(NewUser user) {
        return new MapSqlParameterSource()
                .addValue("id", user.id())
                .addValue("tenantId", user.tenantId())
                .addValue("firstName", normalizedName(user.firstName()))
                .addValue("lastName", normalizedName(user.lastName()))
                .addValue("email", user.email().value())
                .addValue("passwordHash", user.passwordHash())
                .addValue("createdAt", Timestamp.from(user.createdAt()));
    }

    private static ManagedUser mapUser(ResultSet result, int row) throws SQLException {
        UUID roleId = uuidNullable(result, "role_id");
        UUID branchId = uuidNullable(result, "branch_id");
        return new ManagedUser(
                uuid(result, "id"),
                result.getString("first_name"),
                result.getString("last_name"),
                result.getString("email"),
                UserStatus.valueOf(result.getString("status")),
                roleId == null ? null : new RoleOption(roleId, result.getString("role_name")),
                branchId == null ? null : new BranchOption(
                        branchId,
                        result.getString("branch_name"),
                        result.getBoolean("branch_active")),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant(),
                result.getLong("version"));
    }

    private static UUID uuid(ResultSet result, String column) throws SQLException {
        return result.getObject(column, UUID.class);
    }

    private static UUID uuidNullable(ResultSet result, String column) throws SQLException {
        return result.getObject(column, UUID.class);
    }

    private static String normalizedName(String value) {
        return value.trim();
    }

    private static String orderColumn(SortField field) {
        return switch (field) {
            case FIRST_NAME -> "lower(u.first_name)";
            case LAST_NAME -> "lower(u.last_name)";
            case EMAIL -> "u.email";
            case STATUS -> "u.status";
            case CREATED_AT -> "u.created_at";
        };
    }

    private static String direction(SortDirection direction) {
        return direction == SortDirection.DESC ? "DESC" : "ASC";
    }
}
