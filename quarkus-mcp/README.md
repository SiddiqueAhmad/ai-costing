# quarkus-mcp

An experiment: Quarkus 3.36.3 exposing the manufacturing costing domain (same
data model as the Streamlit app at the repo root, `../app.py`) as MCP tools,
backed by **DuckDB** instead of PostgreSQL.

The point of the experiment is that DuckDB's SQL dialect tracks PostgreSQL
closely enough that the exact same JPA entities, Panache repositories and
JPQL queries work against either database — switching is a matter of
datasource configuration, not code.

## Why DuckDB works as a drop-in for Postgres here

Quarkus has no built-in `db-kind` for DuckDB, so the datasource is wired
manually:

```properties
quarkus.datasource.db-kind=other
quarkus.datasource.jdbc.driver=org.duckdb.DuckDBDriver
quarkus.datasource.jdbc.url=jdbc:duckdb:${user.dir}/data/ai-costing.duckdb
quarkus.hibernate-orm.dialect=com.aicosting.DuckDbDialect
```

`db-kind=other` also means Hibernate can't auto-detect a SQL dialect. Rather
than writing one from scratch, [`DuckDbDialect`](src/main/java/com/aicosting/DuckDbDialect.java)
simply extends Hibernate's stock `PostgreSQLDialect` and disables the one
Postgres-only statement (`set client_min_messages = WARNING`, used to
silence Postgres NOTICE warnings) that DuckDB's JDBC driver doesn't
recognize. Everything else — DDL generation, JPQL translation, native
queries — passes straight through unmodified. The Postgres server-version
compatibility check is also disabled (`quarkus.hibernate-orm.database.version-check.enabled=false`),
since DuckDB reports its own version string, not a Postgres version.

## Switching to real PostgreSQL

Run with a `postgres` profile against a live Postgres instance:

```shell
docker run --rm -e POSTGRES_USER=quarkus -e POSTGRES_PASSWORD=quarkus \
  -e POSTGRES_DB=ai_costing -p 5432:5432 postgres:16

./mvnw quarkus:dev -Dquarkus.profile=postgres
```

See `%postgres.*` properties in `src/main/resources/application.properties`.
No Java code changes are needed — `MachineActivity`, `CostingService` and
`CostingMcpServer` are unaware of which database is behind them.

## Running

```shell
./mvnw quarkus:dev
```

This starts on <http://localhost:8080> with:

- A REST endpoint: `GET /costing/summary`
- An MCP server (Streamable HTTP + SSE) at `/mcp` exposing four tools:
  - `listActivities(machineId?)` — list logged machine activity, optionally filtered by machine
  - `machineAvailability(machineId)` — OEE-style availability percentage (running time / total logged time)
  - `totalRevenue()` — estimated billable revenue across all activity
  - `logActivity(machineId, activityType, startTime, endTime, remark?)` — record a new activity entry

Sample data is seeded from `src/main/resources/import.sql` on every startup
(`quarkus.hibernate-orm.database.generation=drop-and-create`, dev-experiment
only — not meant for a persistent database).

### Trying the MCP tools with curl

```shell
SID=$(curl -si -X POST localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}' \
  | grep -i Mcp-Session-Id | cut -d' ' -f2 | tr -d '\r')

curl -s -X POST localhost:8080/mcp -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

curl -s -X POST localhost:8080/mcp -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"totalRevenue","arguments":{}}}'
```

Or point an MCP client (e.g. Claude Desktop / an MCP inspector) at
`http://localhost:8080/mcp`.

## Testing

```shell
./mvnw test
```

`CostingResourceTest` exercises the REST endpoint; `CostingMcpServerTest`
uses [McpAssured](https://docs.quarkiverse.io/quarkus-mcp-server/dev/guides-testing.html)
to drive the MCP tools over a real in-process Streamable HTTP client.
