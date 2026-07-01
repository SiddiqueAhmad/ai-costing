package com.aicosting;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

@ApplicationScoped
public class CostingService {

    private static final List<String> BILLABLE_ACTIVITIES = List.of("Running", "Setup");

    @ConfigProperty(name = "aicosting.rate.machine-1")
    double rateMachine1;

    @ConfigProperty(name = "aicosting.rate.machine-2")
    double rateMachine2;

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
}
