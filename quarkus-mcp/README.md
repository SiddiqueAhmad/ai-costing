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

This was verified for real (not just documented): the entire test suite —
`ActivityCrudTest`, `JoinQueryTest`, `TransactionIsolationTest` and the
original resource/MCP tests, 11 tests total, zero source changes — passes
running against a live PostgreSQL 16 instance via:

```shell
mvn test -Dquarkus.test.profile=postgres
```

One gap turned up along the way: the base profile pins
`quarkus.datasource.jdbc.driver=org.duckdb.DuckDBDriver`, and `%postgres.*`
wasn't overriding it, so Agroal tried to open a `jdbc:postgresql://` URL
with the DuckDB driver class and failed with "Driver does not support the
provided URL". Fixed by adding
`%postgres.quarkus.datasource.jdbc.driver=org.postgresql.Driver`.

## Running

```shell
./mvnw quarkus:dev
```

This starts on <http://localhost:8080> with:

- REST endpoints:
  - `GET /costing/summary` — total revenue and per-machine availability
  - `GET /costing/report` — join + `GROUP BY` + `RANK()` window-function report (see below)
  - `GET/POST/PUT/DELETE /activities[/{id}]` — full CRUD over `machine_activity`
- An MCP server (Streamable HTTP + SSE) at `/mcp` exposing four tools:
  - `listActivities(machineId?)` — list logged machine activity, optionally filtered by machine
  - `machineAvailability(machineId)` — OEE-style availability percentage (running time / total logged time)
  - `totalRevenue()` — estimated billable revenue across all activity
  - `logActivity(machineId, activityType, startTime, endTime, remark?)` — record a new activity entry

## CRUD, joins and transaction isolation — what actually happened

Beyond the read-only summary, this module was stress-tested with a full CRUD
lifecycle, multi-table joins/aggregates, and concurrent-transaction probes
against DuckDB. Two real incompatibilities with Postgres-style Hibernate
usage turned up; both are now worked around and covered by tests.

**1. DuckDB rejects `ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY`.**
Adding a `@ManyToOne` from `MachineActivity` to a new `Machine` catalog
entity (to get a real foreign-key join) broke schema generation outright:
Hibernate emits the FK as a post-hoc `ALTER TABLE`, and DuckDB's JDBC driver
throws `Not implemented Error: No support for that ALTER TABLE option yet!`
(DuckDB only supports inline FKs at `CREATE TABLE` time). Because the schema
statement failed, `import.sql` seeding cascaded into failure too, silently
leaving the database empty. Fixed with
`@JoinColumn(..., foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))`
on `MachineActivity#machine` — the join still works in JPQL/SQL, only the
DDL-time constraint is skipped. See `MachineActivity.java`.

**2. DuckDB's concurrency control does not block concurrent writers — it
conflicts them.** Verified empirically against both databases with the same
test (`TransactionIsolationTest#concurrentWritesToSameRowConflictRatherThanSilentlyOverwrite`):
two raw JDBC connections, `c1` writes an uncommitted row, `c2` tries to
write the same row before `c1` commits.
- **Postgres** (real, live 16 instance): `c2` *blocks* until `c1` commits or
  rolls back, then proceeds — classic `READ COMMITTED` row-level locking,
  last committed writer wins. The test class visibly takes ~2s longer under
  Postgres because it waits out this block.
- **DuckDB**: `c2` does not block at all — it fails immediately with
  `SQLException: TransactionContext Error: Conflict on update!`
  (optimistic MVCC).

No dirty reads either way — verified directly. This is the one genuine
"your Postgres code may need to change" finding: code that relies on
Postgres's blocking behavior (e.g. using a write as a poor-man's row lock,
or code that never expects a write to simply fail with a conflict) will
need to add conflict-catch-and-retry logic to run correctly against
DuckDB. See `TransactionIsolationTest.java`.

Everything else — full CRUD (`ActivityCrudTest`), a Hibernate-translated
JPQL join-fetch, and a raw native query combining `JOIN` + `GROUP BY` +
`CASE` aggregation + `RANK() OVER (...)` (`JoinQueryTest`,
`CostingService#revenueByMachineReport`) — worked against DuckDB exactly as
it would against Postgres, no code changes required.

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
