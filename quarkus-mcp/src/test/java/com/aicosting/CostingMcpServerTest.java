package com.aicosting;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CostingMcpServerTest {

    @Test
    void testToolsAreDiscovered() {
        McpAssured.newConnectedStreamableClient()
                .when()
                .toolsList(page -> assertTrue(page.size() >= 4))
                .thenAssertResults();
    }

    @Test
    void testTotalRevenueTool() {
        McpAssured.newConnectedStreamableClient()
                .when()
                .toolsCall("totalRevenue")
                .withAssert(response -> {
                    assertFalse(response.isError());
                    String text = response.firstContent().asText().text();
                    assertTrue(text.startsWith("Estimated total revenue: PKR"));
                })
                .send()
                .thenAssertResults();
    }

    @Test
    void testMachineAvailabilityTool() {
        McpAssured.newConnectedStreamableClient()
                .when()
                .toolsCall("machineAvailability")
                .withArguments(Map.of("machineId", "1"))
                .withAssert(response -> {
                    assertFalse(response.isError());
                    String text = response.firstContent().asText().text();
                    assertTrue(text.startsWith("Machine 1 availability:"));
                })
                .send()
                .thenAssertResults();
    }
}
