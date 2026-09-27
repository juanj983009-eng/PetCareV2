package com.petcare.enterprise.appointments.infrastructure.persistence;

import com.petcare.enterprise.appointments.application.AppointmentScheduleRepository;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.AppointmentPage;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.AppointmentSummary;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.BranchOption;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.NormalizedAppointmentQuery;
import com.petcare.enterprise.appointments.application.AppointmentScheduleUseCase.VeterinarianOption;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAppointmentScheduleRepositoryAdapter implements AppointmentScheduleRepository {

    private static final String BASE_FROM_AND_WHERE = """
            FROM appointments a
            JOIN patients p
              ON p.tenant_id = a.tenant_id AND p.id = a.patient_id
            JOIN branches b
              ON b.tenant_id = a.tenant_id AND b.id = a.branch_id
            JOIN users v
              ON v.tenant_id = a.tenant_id AND v.id = a.veterinarian_id
            WHERE a.tenant_id = :tenantId
              AND a.starts_at >= :windowStart
              AND a.starts_at < :windowEnd
              AND (a.starts_at AT TIME ZONE b.time_zone)::date = :targetDate
              AND (CAST(:branchId AS UUID) IS NULL OR a.branch_id = CAST(:branchId AS UUID))
              AND (CAST(:veterinarianId AS UUID) IS NULL OR a.veterinarian_id = CAST(:veterinarianId AS UUID))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAppointmentScheduleRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    @Override
    public AppointmentPage search(UUID tenantId, NormalizedAppointmentQuery query) {
        OffsetDateTime windowStart = query.date().minusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime windowEnd = query.date().plusDays(2).atStartOfDay().atOffset(ZoneOffset.UTC);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId, Types.OTHER)
                .addValue("windowStart", windowStart, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("windowEnd", windowEnd, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("targetDate", Date.valueOf(query.date()), Types.DATE)
                .addValue("branchId", query.branchId(), Types.OTHER)
                .addValue("veterinarianId", query.veterinarianId(), Types.OTHER)
                .addValue("limit", query.size(), Types.INTEGER)
                .addValue("offset", query.page() * query.size(), Types.INTEGER);

        String countSql = "SELECT count(*) " + BASE_FROM_AND_WHERE;
        Long total = jdbc.queryForObject(countSql, params, Long.class);
        long totalElements = total != null ? total : 0L;

        String orderDirection = query.ascending() ? "ASC" : "DESC";
        String selectSql = """
                SELECT
                    a.id,
                    a.starts_at,
                    a.ends_at,
                    a.appointment_type,
                    a.status,
                    p.id AS patient_id,
                    p.name AS patient_name,
                    b.id AS branch_id,
                    b.name AS branch_name,
                    v.id AS veterinarian_id,
                    btrim(v.first_name || ' ' || v.last_name) AS veterinarian_full_name
                """
                + BASE_FROM_AND_WHERE
                + " ORDER BY a.starts_at " + orderDirection + ", a.id " + orderDirection
                + " LIMIT :limit OFFSET :offset";

        List<AppointmentSummary> content = totalElements == 0L
                ? List.of()
                : jdbc.query(selectSql, params, this::mapAppointment);

        int totalPages = totalElements == 0L
                ? 0
                : (int) Math.ceil((double) totalElements / query.size());

        return new AppointmentPage(content, query.page(), query.size(), totalElements, totalPages);
    }

    @Override
    public List<BranchOption> findActiveBranches(UUID tenantId) {
        String sql = """
                SELECT id, name, time_zone
                FROM branches
                WHERE tenant_id = :tenantId
                  AND active = true
                ORDER BY lower(name) ASC, id ASC
                """;
        return jdbc.query(
                sql,
                Map.of("tenantId", tenantId),
                (rs, rowNum) -> new BranchOption(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("time_zone")));
    }

    @Override
    public List<VeterinarianOption> findActiveVeterinarians(UUID tenantId) {
        String sql = """
                SELECT DISTINCT
                    u.id,
                    btrim(u.first_name || ' ' || u.last_name) AS full_name,
                    lower(u.last_name) AS sort_last,
                    lower(u.first_name) AS sort_first
                FROM users u
                JOIN user_roles ur
                  ON ur.tenant_id = u.tenant_id AND ur.user_id = u.id
                JOIN roles r
                  ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
                WHERE u.tenant_id = :tenantId
                  AND u.status = 'ACTIVO'
                  AND upper(r.name) = 'VETERINARIO'
                ORDER BY sort_last ASC, sort_first ASC, u.id ASC
                """;
        return jdbc.query(
                sql,
                Map.of("tenantId", tenantId),
                (rs, rowNum) -> new VeterinarianOption(
                        rs.getObject("id", UUID.class),
                        rs.getString("full_name")));
    }

    private AppointmentSummary mapAppointment(ResultSet rs, int rowNum) throws SQLException {
        return new AppointmentSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
                rs.getString("appointment_type"),
                rs.getString("status"),
                rs.getObject("patient_id", UUID.class),
                rs.getString("patient_name"),
                rs.getObject("branch_id", UUID.class),
                rs.getString("branch_name"),
                rs.getObject("veterinarian_id", UUID.class),
                rs.getString("veterinarian_full_name"));
    }
}
