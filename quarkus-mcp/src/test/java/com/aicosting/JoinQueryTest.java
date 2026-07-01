package com.aicosting;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises real multi-table joins (machine_activity JOIN machine) against
 * DuckDB: a Hibernate-translated JPQL join-fetch, and a raw native SQL query
 * combining JOIN + GROUP BY + CASE aggregation + a RANK() window function.
 */
@QuarkusTest
class JoinQueryTest {

    @Inject
    CostingService costingService;

    @Test
    void jpqlJoinFetchTraversesForeignKey() {
        List<MachineActivity> activities = costingService.activitiesAboveRate(4000);
        assertFalse(activities.isEmpty(), "expected activities for machine 1 (rate 5000)");
        for (MachineActivity a : activities) {
            assertEquals("1", a.machineId);
            assertEquals("CNC Mill 1", a.machine.name);
            assertTrue(a.machine.hourlyRate >= 4000);
        }
    }

    @Test
    void nativeJoinGroupByAndWindowFunctionReport() {
        List<Object[]> rows = costingService.revenueByMachineReport();
        assertEquals(2, rows.size(), "expected one aggregated row per machine");

        for (Object[] row : rows) {
            String machineId = (String) row[0];
            String machineName = (String) row[1];
            long activityCount = ((Number) row[2]).longValue();
            double totalHours = ((Number) row[3]).doubleValue();
            double revenue = ((Number) row[4]).doubleValue();
            long rank = ((Number) row[5]).longValue();

            assertTrue(activityCount > 0);
            assertTrue(totalHours > 0);
            assertTrue(revenue > 0);
            assertTrue(rank == 1 || rank == 2);
            assertTrue(machineName.startsWith("CNC Mill"));
            assertTrue(machineId.equals("1") || machineId.equals("2"));
        }

        // Machine 1 has more running hours (8.5h across two Running blocks) than
        // machine 2 (7.5h), so it should be ranked first by the window function.
        Object[] top = rows.stream().filter(r -> ((Number) r[5]).longValue() == 1).findFirst().orElseThrow();
        assertEquals("1", top[0]);
    }
}
