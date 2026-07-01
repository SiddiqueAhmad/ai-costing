package com.aicosting;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class CostingService {

    private static final List<String> BILLABLE_ACTIVITIES = List.of("Running", "Setup");

    @ConfigProperty(name = "aicosting.rate.machine-1")
    double rateMachine1;

    @ConfigProperty(name = "aicosting.rate.machine-2")
    double rateMachine2;

    @Inject
    EntityManager entityManager;

    public double hourlyRate(String machineId) {
        if (machineId.contains("1")) {
            return rateMachine1;
        }
        if (machineId.contains("2")) {
            return rateMachine2;
        }
        return 0.0;
    }

    public double cost(MachineActivity activity) {
        if (!BILLABLE_ACTIVITIES.contains(activity.activityType)) {
            return 0.0;
        }
        return activity.durationHours() * hourlyRate(activity.machineId);
    }

    public List<MachineActivity> activitiesForMachine(String machineId) {
        return MachineActivity.list("machineId", machineId);
    }

    public double totalRevenue() {
        return MachineActivity.<MachineActivity>listAll().stream()
                .mapToDouble(this::cost)
                .sum();
    }

    public double availabilityPercent(String machineId) {
        List<MachineActivity> activities = activitiesForMachine(machineId);
        double totalHrs = activities.stream().mapToDouble(MachineActivity::durationHours).sum();
        if (totalHrs <= 0) {
            return 0.0;
        }
        double runningHrs = activities.stream()
                .filter(a -> "Running".equals(a.activityType))
                .mapToDouble(MachineActivity::durationHours)
                .sum();
        return (runningHrs / totalHrs) * 100.0;
    }

    // --------------------------------------------------------------------
    // CRUD
    // --------------------------------------------------------------------

    @Transactional
    public MachineActivity createActivity(String machineId, String activityType, LocalDateTime start,
            LocalDateTime end, String remark, String submittedBy) {
        MachineActivity activity = new MachineActivity();
        activity.machineId = machineId;
        activity.activityType = activityType;
        activity.startTime = start;
        activity.endTime = end;
        activity.remark = remark;
        activity.submittedBy = submittedBy;
        activity.persist();
        return activity;
    }

    public Optional<MachineActivity> findActivity(Long id) {
        return Optional.ofNullable(MachineActivity.findById(id));
    }

    @Transactional
    public Optional<MachineActivity> updateActivity(Long id, String activityType, LocalDateTime start,
            LocalDateTime end, String remark) {
        MachineActivity activity = MachineActivity.findById(id);
        if (activity == null) {
            return Optional.empty();
        }
        if (activityType != null) {
            activity.activityType = activityType;
        }
        if (start != null) {
            activity.startTime = start;
        }
        if (end != null) {
            activity.endTime = end;
        }
        if (remark != null) {
            activity.remark = remark;
        }
        // no explicit persist() needed: managed entity, flushed at commit.
        return Optional.of(activity);
    }

    @Transactional
    public boolean deleteActivity(Long id) {
        return MachineActivity.deleteById(id);
    }

    // --------------------------------------------------------------------
    // Heavy join / aggregate queries -- exercises real machine_activity JOIN
    // machine SQL against DuckDB, both via Hibernate-translated JPQL and via
    // a raw native query using DuckDB-specific analytic SQL (window function).
    // --------------------------------------------------------------------

    /** JPQL join-fetch across the machine_activity -> machine foreign key. */
    @SuppressWarnings("unchecked")
    public List<MachineActivity> activitiesAboveRate(double minHourlyRate) {
        return entityManager.createQuery(
                "select a from MachineActivity a join fetch a.machine m "
                        + "where m.hourlyRate >= :minRate order by a.startTime")
                .setParameter("minRate", minHourlyRate)
                .getResultList();
    }

    /**
     * Native SQL report: join + GROUP BY + CASE aggregation + RANK() window
     * function, computed entirely in DuckDB (not in Java). Confirms DuckDB
     * accepts the same join/aggregate/window SQL a Postgres-backed report
     * would use.
     */
    @SuppressWarnings("unchecked")
    public List<Object[]> revenueByMachineReport() {
        String sql = """
                SELECT m.id,
                       m.name,
                       COUNT(*) AS activity_count,
                       SUM(EXTRACT(EPOCH FROM (a.end_time - a.start_time)) / 3600.0) AS total_hours,
                       SUM(CASE WHEN a.activity_type IN ('Running', 'Setup')
                                THEN EXTRACT(EPOCH FROM (a.end_time - a.start_time)) / 3600.0 * m.hourly_rate
                                ELSE 0 END) AS revenue,
                       RANK() OVER (ORDER BY SUM(CASE WHEN a.activity_type = 'Running'
                                THEN EXTRACT(EPOCH FROM (a.end_time - a.start_time)) / 3600.0
                                ELSE 0 END) DESC) AS running_hours_rank
                FROM machine_activity a
                JOIN machine m ON m.id = a.machine_id
                GROUP BY m.id, m.name
                ORDER BY revenue DESC
                """;
        return entityManager.createNativeQuery(sql).getResultList();
    }
}
