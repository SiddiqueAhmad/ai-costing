package com.aicosting;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Full Create/Read/Update/Delete lifecycle against DuckDB via the /activities REST resource. */
@QuarkusTest
class ActivityCrudTest {

    private static final String CREATE_BODY = """
            {
              "machineId": "1",
              "activityType": "Running",
              "startTime": "2026-07-02T08:00:00",
              "endTime": "2026-07-02T09:00:00",
              "remark": "crud-test-create",
              "submittedBy": "crud-test"
            }
            """;

    @Test
    void createReadUpdateDelete() {
        // CREATE
        int id = given()
                .contentType(ContentType.JSON).body(CREATE_BODY)
                .when().post("/activities")
                .then().statusCode(201)
                .body("machineId", equalTo("1"))
                .body("remark", equalTo("crud-test-create"))
                .extract().path("id");

        // READ (single)
        given().when().get("/activities/{id}", id)
                .then().statusCode(200)
                .body("id", equalTo(id))
                .body("activityType", equalTo("Running"));

        // READ (collection contains it)
        given().when().get("/activities")
                .then().statusCode(200)
                .body("find { it.id == " + id + " }.remark", equalTo("crud-test-create"));

        // UPDATE
        String updateBody = """
                {
                  "activityType": "Breakdown",
                  "remark": "crud-test-updated"
                }
                """;
        given()
                .contentType(ContentType.JSON).body(updateBody)
                .when().put("/activities/{id}", id)
                .then().statusCode(200)
                .body("activityType", equalTo("Breakdown"))
                .body("remark", equalTo("crud-test-updated"));

        // READ confirms update persisted
        given().when().get("/activities/{id}", id)
                .then().statusCode(200)
                .body("activityType", equalTo("Breakdown"));

        // DELETE
        given().when().delete("/activities/{id}", id)
                .then().statusCode(204);

        // READ confirms gone
        given().when().get("/activities/{id}", id)
                .then().statusCode(404);
    }

    @Test
    void updateAndDeleteOfMissingIdReturn404() {
        given()
                .contentType(ContentType.JSON).body("{\"remark\":\"x\"}")
                .when().put("/activities/{id}", 999_999)
                .then().statusCode(404);

        given().when().delete("/activities/{id}", 999_999)
                .then().statusCode(404);
    }
}
