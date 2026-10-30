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
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

        String countSql = """
                SELECT
                    count(*) AS total_elements,
                    count(*) FILTER (WHERE a.status = 'PROGRAMADA') AS scheduled_count,
                    count(*) FILTER (WHERE a.status = 'CONFIRMADA') AS confirmed_count,
                    count(*) FILTER (WHERE a.status = 'EN_ESPERA') AS waiting_count,
                    count(*) FILTER (WHERE a.status = 'NO_ASISTIO') AS no_show_count
                """
                + BASE_FROM_AND_WHERE;
        AppointmentCounts counts = jdbc.queryForObject(
                countSql,
                params,
                (rs, rowNum) -> new AppointmentCounts(
                        rs.getLong("total_elements"),
                        rs.getLong("scheduled_count"),
                        rs.getLong("confirmed_count"),
                        rs.getLong("waiting_count"),
                        rs.getLong("no_show_count")));
        long totalElements = counts != null ? counts.totalElements() : 0L;

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

        return new AppointmentPage(
                content,
                query.page(),
                query.size(),
                totalElements,
                totalPages,
                counts != null ? counts.scheduledCount() : 0L,
                counts != null ? counts.confirmedCount() : 0L,
                counts != null ? counts.waitingCount() : 0L,
                counts != null ? counts.noShowCount() : 0L);
    }

    private record AppointmentCounts(
            long totalElements,
            long scheduledCount,
            long confirmedCount,
            long waitingCount,
            long noShowCount) {
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
        String vetsSql = """
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

        Map<UUID, String> vetNames = new LinkedHashMap<>();
        Map<UUID, List<UUID>> vetBranches = new LinkedHashMap<>();

        jdbc.query(vetsSql, Map.of("tenantId", tenantId), rs -> {
            UUID id = rs.getObject("id", UUID.class);
            vetNames.put(id, rs.getString("full_name"));
            vetBranches.put(id, new ArrayList<>());
        });

        if (vetNames.isEmpty()) {
            return List.of();
        }

        String branchesSql = """
                SELECT ub.user_id, ub.branch_id
                FROM user_branches ub
                JOIN branches b
                  ON b.tenant_id = ub.tenant_id AND b.id = ub.branch_id
                WHERE ub.tenant_id = :tenantId
                  AND b.active = true
                  AND ub.user_id IN (:userIds)
                ORDER BY lower(b.name) ASC, ub.branch_id ASC
                """;
        MapSqlParameterSource branchParams = new MapSqlParameterSource()
                .addValue("tenantId", tenantId, Types.OTHER)
                .addValue("userIds", vetNames.keySet());

        jdbc.query(branchesSql, branchParams, rs -> {
            UUID userId = rs.getObject("user_id", UUID.class);
            UUID branchId = rs.getObject("branch_id", UUID.class);
            List<UUID> assigned = vetBranches.get(userId);
            if (assigned != null && !assigned.contains(branchId)) {
                assigned.add(branchId);
            }
        });

        return vetNames.entrySet().stream()
                .map(entry -> new VeterinarianOption(
                        entry.getKey(),
                        entry.getValue(),
                        List.copyOf(vetBranches.getOrDefault(entry.getKey(), List.of()))))
                .toList();
    }

    @Override
    public Optional<BranchOption> findActiveBranchById(UUID tenantId, UUID branchId) {
        String sql = """
                SELECT id, name, time_zone
                FROM branches
                WHERE tenant_id = :tenantId
                  AND id = :branchId
                  AND active = true
                """;
        List<BranchOption> results = jdbc.query(
                sql,
                Map.of("tenantId", tenantId, "branchId", branchId),
                (rs, rowNum) -> new BranchOption(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("time_zone")));
        return results.stream().findFirst();
    }

    @Override
    public Optional<String> findActivePatientName(UUID tenantId, UUID patientId) {
        String sql = """
                SELECT p.name
                FROM patients p
                JOIN owners o
                  ON o.tenant_id = p.tenant_id
                 AND o.id = p.owner_id
                WHERE p.tenant_id = :tenantId
                  AND p.id = :patientId
                  AND p.status = 'ACTIVO'
                  AND o.active = true
                """;
        List<String> results = jdbc.query(
                sql,
                Map.of("tenantId", tenantId, "patientId", patientId),
                (rs, rowNum) -> rs.getString("name"));
        return results.stream().findFirst();
    }

    @Override
    public Optional<VeterinarianEligibility> findActiveUserEligibility(
            UUID tenantId,
            UUID userId,
            UUID branchId) {
        String sql = """
                SELECT
                    u.id,
                    btrim(u.first_name || ' ' || u.last_name) AS full_name,
                    EXISTS (
                        SELECT 1
                        FROM user_roles ur
                        JOIN roles r
                          ON r.tenant_id = ur.tenant_id AND r.id = ur.role_id
                        WHERE ur.tenant_id = u.tenant_id
                          AND ur.user_id = u.id
                          AND upper(r.name) = 'VETERINARIO'
                    ) AS has_veterinarian_role,
                    EXISTS (
                        SELECT 1
                        FROM user_branches ub
                        JOIN branches b
                          ON b.tenant_id = ub.tenant_id AND b.id = ub.branch_id
                        WHERE ub.tenant_id = u.tenant_id
                          AND ub.user_id = u.id
                          AND ub.branch_id = :branchId
                          AND b.active = true
                    ) AS assigned_to_branch
                FROM users u
                WHERE u.tenant_id = :tenantId
                  AND u.id = :userId
                  AND u.status = 'ACTIVO'
                """;
        List<VeterinarianEligibility> results = jdbc.query(
                sql,
                Map.of("tenantId", tenantId, "userId", userId, "branchId", branchId),
                (rs, rowNum) -> new VeterinarianEligibility(
                        rs.getObject("id", UUID.class),
                        rs.getString("full_name"),
                        rs.getBoolean("has_veterinarian_role"),
                        rs.getBoolean("assigned_to_branch")));
        return results.stream().findFirst();
    }

    @Override
    public boolean hasOverlappingActiveAppointment(
            UUID tenantId,
            UUID veterinarianId,
            Instant startsAt,
            Instant endsAt) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM appointments a
                    WHERE a.tenant_id = :tenantId
                      AND a.veterinarian_id = :veterinarianId
                      AND a.status <> 'CANCELADA'
                      AND tstzrange(a.starts_at, a.ends_at, '[)') && tstzrange(:startsAt, :endsAt, '[)')
                )
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId, Types.OTHER)
                .addValue("veterinarianId", veterinarianId, Types.OTHER)
                .addValue("startsAt", OffsetDateTime.ofInstant(startsAt, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("endsAt", OffsetDateTime.ofInstant(endsAt, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        Boolean exists = jdbc.queryForObject(sql, params, Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public boolean hasOverlappingActiveAppointmentExcluding(
            UUID tenantId,
            UUID veterinarianId,
            Instant startsAt,
            Instant endsAt,
            UUID excludedAppointmentId) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM appointments a
                    WHERE a.tenant_id = :tenantId
                      AND a.veterinarian_id = :veterinarianId
                      AND a.id <> :excludedAppointmentId
                      AND a.status <> 'CANCELADA'
                      AND tstzrange(a.starts_at, a.ends_at, '[)') && tstzrange(:startsAt, :endsAt, '[)')
                )
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId, Types.OTHER)
                .addValue("veterinarianId", veterinarianId, Types.OTHER)
                .addValue("excludedAppointmentId", excludedAppointmentId, Types.OTHER)
                .addValue("startsAt", OffsetDateTime.ofInstant(startsAt, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("endsAt", OffsetDateTime.ofInstant(endsAt, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        Boolean exists = jdbc.queryForObject(sql, params, Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public AppointmentSummary insertAppointment(NewAppointmentRecord record) {
        String sql = """
                INSERT INTO appointments (
                    id,
                    tenant_id,
                    branch_id,
                    patient_id,
                    veterinarian_id,
                    starts_at,
                    ends_at,
                    appointment_type,
                    status,
                    reason,
                    notes,
                    created_by,
                    created_at,
                    updated_at
                ) VALUES (
                    :id,
                    :tenantId,
                    :branchId,
                    :patientId,
                    :veterinarianId,
                    :startsAt,
                    :endsAt,
                    :appointmentType,
                    :status,
                    :reason,
                    :notes,
                    :createdBy,
                    :createdAt,
                    :updatedAt
                )
                """;
        OffsetDateTime startsAtUtc = OffsetDateTime.ofInstant(record.startsAt(), ZoneOffset.UTC);
        OffsetDateTime endsAtUtc = OffsetDateTime.ofInstant(record.endsAt(), ZoneOffset.UTC);
        OffsetDateTime createdAtUtc = OffsetDateTime.ofInstant(record.createdAt(), ZoneOffset.UTC);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", record.id(), Types.OTHER)
                .addValue("tenantId", record.tenantId(), Types.OTHER)
                .addValue("branchId", record.branchId(), Types.OTHER)
                .addValue("patientId", record.patientId(), Types.OTHER)
                .addValue("veterinarianId", record.veterinarianId(), Types.OTHER)
                .addValue("startsAt", startsAtUtc, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("endsAt", endsAtUtc, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("appointmentType", record.appointmentType(), Types.VARCHAR)
                .addValue("status", record.status(), Types.VARCHAR)
                .addValue("reason", record.reason(), Types.VARCHAR)
                .addValue("notes", record.notes(), Types.VARCHAR)
                .addValue("createdBy", record.createdBy(), Types.OTHER)
                .addValue("createdAt", createdAtUtc, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("updatedAt", createdAtUtc, Types.TIMESTAMP_WITH_TIMEZONE);

        jdbc.update(sql, params);

        return new AppointmentSummary(
                record.id(),
                record.startsAt(),
                record.endsAt(),
                record.appointmentType(),
                record.status(),
                record.patientId(),
                record.patientName(),
                record.branchId(),
                record.branchName(),
                record.veterinarianId(),
                record.veterinarianFullName());
    }

    @Override
    public Optional<AppointmentSummary> findById(UUID tenantId, UUID appointmentId) {
        String sql = """
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
                FROM appointments a
                JOIN patients p
                  ON p.tenant_id = a.tenant_id AND p.id = a.patient_id
                JOIN branches b
                  ON b.tenant_id = a.tenant_id AND b.id = a.branch_id
                JOIN users v
                  ON v.tenant_id = a.tenant_id AND v.id = a.veterinarian_id
                WHERE a.tenant_id = :tenantId
                  AND a.id = :appointmentId
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId, Types.OTHER)
                .addValue("appointmentId", appointmentId, Types.OTHER);
        List<AppointmentSummary> results = jdbc.query(sql, params, this::mapAppointment);
        return results.stream().findFirst();
    }

    @Override
    public boolean updateStatusConditionally(
            UUID tenantId,
            UUID appointmentId,
            String expectedPreviousStatus,
            String newStatus,
            Instant updatedAt) {
        String sql = """
                UPDATE appointments
                SET status = :newStatus,
                    updated_at = :updatedAt
                WHERE tenant_id = :tenantId
                  AND id = :appointmentId
                  AND status = :expectedPreviousStatus
                  AND (:newStatus <> 'NO_ASISTIO' OR ends_at <= :updatedAt)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId, Types.OTHER)
                .addValue("appointmentId", appointmentId, Types.OTHER)
                .addValue("expectedPreviousStatus", expectedPreviousStatus, Types.VARCHAR)
                .addValue("newStatus", newStatus, Types.VARCHAR)
                .addValue("updatedAt", OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        return jdbc.update(sql, params) == 1;
    }

    @Override
    public boolean rescheduleConditionally(RescheduleAppointmentMutation mutation) {
        String sql = """
                UPDATE appointments
                SET branch_id = :newBranchId,
                    veterinarian_id = :newVeterinarianId,
                    starts_at = :newStartsAt,
                    ends_at = :newEndsAt,
                    status = :newStatus,
                    updated_at = :updatedAt
                WHERE tenant_id = :tenantId
                  AND id = :appointmentId
                  AND status = :expectedPreviousStatus
                  AND branch_id = :expectedBranchId
                  AND veterinarian_id = :expectedVeterinarianId
                  AND starts_at = :expectedStartsAt
                  AND ends_at = :expectedEndsAt
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", mutation.tenantId(), Types.OTHER)
                .addValue("appointmentId", mutation.appointmentId(), Types.OTHER)
                .addValue("expectedPreviousStatus", mutation.expectedPreviousStatus(), Types.VARCHAR)
                .addValue("expectedBranchId", mutation.expectedBranchId(), Types.OTHER)
                .addValue("expectedVeterinarianId", mutation.expectedVeterinarianId(), Types.OTHER)
                .addValue("expectedStartsAt", OffsetDateTime.ofInstant(mutation.expectedStartsAt(), ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("expectedEndsAt", OffsetDateTime.ofInstant(mutation.expectedEndsAt(), ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("newBranchId", mutation.newBranchId(), Types.OTHER)
                .addValue("newVeterinarianId", mutation.newVeterinarianId(), Types.OTHER)
                .addValue("newStartsAt", OffsetDateTime.ofInstant(mutation.newStartsAt(), ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("newEndsAt", OffsetDateTime.ofInstant(mutation.newEndsAt(), ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("newStatus", mutation.newStatus(), Types.VARCHAR)
                .addValue("updatedAt", OffsetDateTime.ofInstant(mutation.updatedAt(), ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        return jdbc.update(sql, params) == 1;
    }

    @Override
    public boolean cancelConditionally(CancelAppointmentMutation mutation) {
        String sql = """
                UPDATE appointments
                SET status = 'CANCELADA',
                    cancellation_reason = :cancellationReason,
                    cancelled_at = :cancelledAt,
                    cancelled_by = :cancelledBy,
                    updated_at = :cancelledAt
                WHERE tenant_id = :tenantId
                  AND id = :appointmentId
                  AND status = :expectedPreviousStatus
                  AND branch_id = :expectedBranchId
                  AND veterinarian_id = :expectedVeterinarianId
                  AND starts_at = :expectedStartsAt
                  AND ends_at = :expectedEndsAt
                """;
        OffsetDateTime cancelledAtUtc = OffsetDateTime.ofInstant(mutation.cancelledAt(), ZoneOffset.UTC);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", mutation.tenantId(), Types.OTHER)
                .addValue("appointmentId", mutation.appointmentId(), Types.OTHER)
                .addValue("expectedPreviousStatus", mutation.expectedPreviousStatus(), Types.VARCHAR)
                .addValue("expectedBranchId", mutation.expectedBranchId(), Types.OTHER)
                .addValue("expectedVeterinarianId", mutation.expectedVeterinarianId(), Types.OTHER)
                .addValue("expectedStartsAt", OffsetDateTime.ofInstant(mutation.expectedStartsAt(), ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("expectedEndsAt", OffsetDateTime.ofInstant(mutation.expectedEndsAt(), ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("cancellationReason", mutation.cancellationReason(), Types.VARCHAR)
                .addValue("cancelledAt", cancelledAtUtc, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("cancelledBy", mutation.cancelledBy(), Types.OTHER);
        return jdbc.update(sql, params) == 1;
    }

    @Override
    public void insertStatusHistory(AppointmentStatusHistoryRecord record) {
        String sql = """
                INSERT INTO appointment_status_history (
                    id,
                    tenant_id,
                    appointment_id,
                    previous_status,
                    new_status,
                    changed_by,
                    changed_at,
                    correlation_id
                ) VALUES (
                    :id,
                    :tenantId,
                    :appointmentId,
                    :previousStatus,
                    :newStatus,
                    :changedBy,
                    :changedAt,
                    :correlationId
                )
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", record.id(), Types.OTHER)
                .addValue("tenantId", record.tenantId(), Types.OTHER)
                .addValue("appointmentId", record.appointmentId(), Types.OTHER)
                .addValue("previousStatus", record.previousStatus(), Types.VARCHAR)
                .addValue("newStatus", record.newStatus(), Types.VARCHAR)
                .addValue("changedBy", record.changedBy(), Types.OTHER)
                .addValue("changedAt", OffsetDateTime.ofInstant(record.changedAt(), ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("correlationId", record.correlationId(), Types.OTHER);
        jdbc.update(sql, params);
    }

    @Override
    public List<AppointmentActivityDayRecord> findActivityByDateRange(
            UUID tenantId,
            UUID branchId,
            LocalDate startDate,
            LocalDate endDate) {
        OffsetDateTime windowStart = startDate.minusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime windowEnd = endDate.plusDays(2).atStartOfDay().atOffset(ZoneOffset.UTC);

        String sql = """
                SELECT
                    (a.starts_at AT TIME ZONE b.time_zone)::date AS scheduled_date,
                    count(*) FILTER (WHERE a.status = 'FINALIZADA') AS completed_count,
                    count(*) FILTER (WHERE a.status != 'FINALIZADA') AS other_count
                FROM appointments a
                JOIN branches b
                  ON b.tenant_id = a.tenant_id AND b.id = a.branch_id
                WHERE a.tenant_id = :tenantId
                  AND a.starts_at >= :windowStart
                  AND a.starts_at < :windowEnd
                  AND (a.starts_at AT TIME ZONE b.time_zone)::date >= :startDate
                  AND (a.starts_at AT TIME ZONE b.time_zone)::date <= :endDate
                  AND a.status != 'CANCELADA'
                  AND (CAST(:branchId AS UUID) IS NULL OR a.branch_id = CAST(:branchId AS UUID))
                GROUP BY (a.starts_at AT TIME ZONE b.time_zone)::date
                ORDER BY scheduled_date ASC
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId, Types.OTHER)
                .addValue("windowStart", windowStart, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("windowEnd", windowEnd, Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("startDate", Date.valueOf(startDate), Types.DATE)
                .addValue("endDate", Date.valueOf(endDate), Types.DATE)
                .addValue("branchId", branchId, Types.OTHER);

        return jdbc.query(sql, params, (rs, rowNum) -> new AppointmentActivityDayRecord(
                rs.getDate("scheduled_date").toLocalDate(),
                rs.getLong("completed_count"),
                rs.getLong("other_count")));
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
