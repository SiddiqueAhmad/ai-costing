package com.aicosting;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

@Path("/costing/summary")
public class CostingResource {

    @Inject
    CostingService costingService;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> summary() {
        return Map.of(
                "totalRevenue", costingService.totalRevenue(),
                "machine1AvailabilityPct", costingService.availabilityPercent("1"),
                "machine2AvailabilityPct", costingService.availabilityPercent("2"));
    }
}
