package com.aicosting;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Path("/costing")
public class CostingResource {

    @Inject
    CostingService costingService;

    @GET
    @Path("/summary")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> summary() {
        return Map.of(
                "totalRevenue", costingService.totalRevenue(),
                "machine1AvailabilityPct", costingService.availabilityPercent("1"),
                "machine2AvailabilityPct", costingService.availabilityPercent("2"));
    }

    /** Native join + GROUP BY + RANK() window-function report, computed in DuckDB. */
    @GET
    @Path("/report")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> report() {
        return costingService.revenueByMachineReport().stream()
                .map(row -> Map.<String, Object>of(
                        "machineId", row[0],
                        "machineName", row[1],
                        "activityCount", row[2],
                        "totalHours", row[3],
                        "revenue", row[4],
                        "runningHoursRank", row[5]))
                .collect(Collectors.toList());
    }
}
