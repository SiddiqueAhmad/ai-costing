package com.aicosting;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThan;

@QuarkusTest
class CostingResourceTest {

    @Test
    void testCostingSummary() {
        given()
                .when().get("/costing/summary")
                .then()
                .statusCode(200)
                .body("totalRevenue", greaterThan(0f))
                .body("machine1AvailabilityPct", greaterThan(0f))
                .body("machine2AvailabilityPct", greaterThan(0f));
    }
}
