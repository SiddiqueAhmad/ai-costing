package com.aicosting;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class CostingMcpServer {

    @Inject
    CostingService costingService;

    @Tool(description = "List machine activity records, optionally filtered by machine id (e.g. '1' or '2')")
    public List<String> listActivities(
            @ToolArg(description = "Machine id to filter by; omit to list all machines", required = false) String machineId) {
        List<MachineActivity> activities = (machineId == null || machineId.isBlank())
                ? MachineActivity.listAll()
                : costingService.activitiesForMachine(machineId);
        return activities.stream()
                .map(a -> "#%d machine=%s type=%s start=%s end=%s cost=%.2f"
                        .formatted(a.id, a.machineId, a.activityType, a.startTime, a.endTime, costingService.cost(a)))
                .collect(Collectors.toList());
    }

    @Tool(description = "Compute machine availability (OEE) as the percentage of running time over total logged time")
    public String machineAvailability(@ToolArg(description = "Machine id, e.g. '1' or '2'") String machineId) {
        double pct = costingService.availabilityPercent(machineId);
        return "Machine %s availability: %.1f%%".formatted(machineId, pct);
    }

    @Tool(description = "Compute total estimated billable revenue across all logged machine activity")
    public String totalRevenue() {
        return "Estimated total revenue: PKR %.2f".formatted(costingService.totalRevenue());
    }

    @Tool(description = "Record a new machine activity entry")
    @Transactional
    public String logActivity(
            @ToolArg(description = "Machine id, e.g. '1' or '2'") String machineId,
            @ToolArg(description = "Activity type: Running, Setup, Maintenance, Idle, Breakdown or Off") String activityType,
            @ToolArg(description = "Start time in ISO-8601 format, e.g. 2026-07-01T08:00:00") String startTime,
            @ToolArg(description = "End time in ISO-8601 format, e.g. 2026-07-01T09:30:00") String endTime,
            @ToolArg(description = "Optional free-text remark", required = false) String remark) {
        MachineActivity activity = new MachineActivity();
        activity.machineId = machineId;
        activity.activityType = activityType;
        activity.startTime = LocalDateTime.parse(startTime);
        activity.endTime = LocalDateTime.parse(endTime);
        activity.remark = remark;
        activity.persist();
        return "Logged activity #" + activity.id;
    }
}
