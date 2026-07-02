package com.aicosting;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises DuckDB's Quack client/server protocol (new in the 1.5.x line,
 * still beta) through the same JDBC driver the Quarkus app uses. Quack lifts
 * DuckDB's historical one-writer-process restriction: one DuckDB instance
 * serves a database over HTTP, and other instances ATTACH to it remotely --
 * including for writes.
 *
 * The quack + httpfs extension binaries are version-locked to the engine
 * (v1.5.3, matching duckdb-jdbc.version in pom.xml) and are expected at
 * -Dquack.extensions.quack / -Dquack.extensions.httpfs, defaulting to the
 * paths the pip packages duckdb-extension-quack / duckdb-extension-httpfs
 * install to. The whole class is skipped (JUnit assumption) when the
 * binaries aren't present, so environments without them still build green.
 *
 * Verified to work over Quack (v1.5.3 beta):
 *  - concurrent INSERTs from multiple client connections (the headline claim)
 *  - single-table SELECT against the attached remote
 *  - CREATE/DROP TABLE against the attached remote
 *  - arbitrary SQL (UPDATE/DELETE, sequences, multi-table JOIN + window
 *    functions) executed *server-side* via the quack_query() pass-through
 *
 * Known beta limitations, pinned by tests below so a future upgrade that
 * lifts them fails loudly here and lets us simplify:
 *  - UPDATE/DELETE on an attached remote table ("Can only update base table")
 *  - joining two remote tables client-side in one query
 *  - remote sequences are invisible to the client catalog (nextval fails),
 *    which is exactly what blocks Hibernate's sequence-generated ids -- and
 *    hence running the full ORM-based suite -- over a Quack ATTACH for now.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuackProtocolTest {

    private static final String QUACK_EXT = System.getProperty("quack.extensions.quack",
            "/usr/local/lib/python3.11/dist-packages/duckdb_extension_quack/extensions/v1.5.3/quack.duckdb_extension");
    private static final String HTTPFS_EXT = System.getProperty("quack.extensions.httpfs",
            "/usr/local/lib/python3.11/dist-packages/duckdb_extension_httpfs/extensions/v1.5.3/httpfs.duckdb_extension");

    private static final String SERVER_URL = "quack://localhost:9595";
    private static final String TOKEN = "sesame";

    private Path workDir;
    private Connection server;

    @BeforeAll
    void startQuackServer() throws Exception {
        assumeTrue(Files.exists(Path.of(QUACK_EXT)) && Files.exists(Path.of(HTTPFS_EXT)),
                "quack/httpfs extension binaries not available -- skipping Quack protocol tests");

        workDir = Files.createTempDirectory("quack-jdbc-test");
        server = open(workDir.resolve("server.duckdb"));
        try (Statement st = server.createStatement()) {
            st.execute("""
                    CREATE TABLE machine_activity(
                        id BIGINT PRIMARY KEY, machine_id VARCHAR NOT NULL, activity_type VARCHAR NOT NULL,
                        start_time TIMESTAMP NOT NULL, end_time TIMESTAMP NOT NULL,
                        remark VARCHAR, submitted_by VARCHAR)""");
            st.execute("CREATE TABLE machine(id VARCHAR PRIMARY KEY, name VARCHAR, hourly_rate DOUBLE)");
            st.execute("INSERT INTO machine VALUES ('1','CNC Mill 1',5000),('2','CNC Mill 2',3500)");
            st.execute("INSERT INTO machine_activity VALUES "
                    + "(1,'1','Running','2026-07-01 08:00:00','2026-07-01 12:00:00','Batch A','operator1')");
            try (ResultSet rs = st.executeQuery(
                    "SELECT * FROM quack_serve('" + SERVER_URL + "', token=>'" + TOKEN + "', disable_ssl=>true)")) {
                assertTrue(rs.next(), "quack_serve should report the listening endpoint");
            }
        }
    }

    @AfterAll
    void stopQuackServer() throws Exception {
        if (server != null) {
            try (Statement st = server.createStatement()) {
                st.execute("SELECT * FROM quack_stop('" + SERVER_URL + "')");
            } catch (SQLException ignored) {
                // server connection closing is enough to tear the endpoint down
            }
            server.close();
        }
    }

    private Connection open(Path dbFile) throws SQLException {
        Properties props = new Properties();
        props.setProperty("allow_unsigned_extensions", "true");
        Connection c = DriverManager.getConnection("jdbc:duckdb:" + dbFile, props);
        try (Statement st = c.createStatement()) {
            st.execute("LOAD '" + HTTPFS_EXT + "'");
            st.execute("LOAD '" + QUACK_EXT + "'");
        }
        return c;
    }

    private Connection openClient(String name) throws SQLException {
        Connection c = open(workDir.resolve(name + ".duckdb"));
        try (Statement st = c.createStatement()) {
            st.execute("ATTACH '" + SERVER_URL + "' AS remote (TYPE QUACK, TOKEN '" + TOKEN + "', DISABLE_SSL true)");
        }
        return c;
    }

    @Test
    void remoteReadAndInsertThroughJdbc() throws Exception {
        try (Connection client = openClient("client-rw"); Statement st = client.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM remote.machine")) {
                rs.next();
                assertEquals(2, rs.getLong(1));
            }
            st.execute("INSERT INTO remote.machine_activity VALUES "
                    + "(1000,'2','Setup','2026-07-01 08:00:00','2026-07-01 08:30:00','over-quack-jdbc','jdbc-client')");
            try (ResultSet rs = st.executeQuery(
                    "SELECT remark FROM remote.machine_activity WHERE id = 1000")) {
                assertTrue(rs.next());
                assertEquals("over-quack-jdbc", rs.getString(1));
            }
        }
    }

    @Test
    void multipleConcurrentWriterConnections() throws Exception {
        final int writers = 4;
        final int rowsPerWriter = 25;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<Integer>> results = pool.invokeAll(
                    java.util.stream.IntStream.range(0, writers).<Callable<Integer>>mapToObj(w -> () -> {
                        int ok = 0;
                        try (Connection c = openClient("writer-" + w); Statement st = c.createStatement()) {
                            for (int i = 0; i < rowsPerWriter; i++) {
                                st.execute(("INSERT INTO remote.machine_activity VALUES "
                                        + "(%d,'1','Running','2026-07-01 09:00:00','2026-07-01 10:00:00','w%d-r%d','writer%d')")
                                        .formatted(2000 + w * 1000 + i, w, i, w));
                                ok++;
                            }
                        }
                        return ok;
                    }).toList());
            int total = 0;
            for (Future<Integer> f : results) {
                total += f.get();
            }
            assertEquals(writers * rowsPerWriter, total, "every concurrent write should succeed");
        } finally {
            pool.shutdownNow();
        }

        try (Connection check = openClient("check"); Statement st = check.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT count(*), count(DISTINCT id) FROM remote.machine_activity WHERE id >= 2000")) {
            rs.next();
            assertEquals(writers * rowsPerWriter, rs.getLong(1), "no lost writes");
            assertEquals(writers * rowsPerWriter, rs.getLong(2), "no duplicated ids");
        }
    }

    @Test
    void serverSidePassThroughRunsJoinsUpdatesAndSequences() throws Exception {
        try (Connection client = openClient("client-passthru"); Statement st = client.createStatement()) {
            String join = "SELECT m.name, count(*) AS cnt FROM machine_activity a "
                    + "JOIN machine m ON m.id = a.machine_id GROUP BY m.name ORDER BY cnt DESC";
            try (ResultSet rs = st.executeQuery(passThrough(join))) {
                assertTrue(rs.next(), "server-side join should return rows");
            }
            st.execute(passThrough("UPDATE machine_activity SET remark='server-side-update' WHERE id=1"));
            try (ResultSet rs = st.executeQuery(
                    passThrough("SELECT remark FROM machine_activity WHERE id=1"))) {
                assertTrue(rs.next());
                assertEquals("server-side-update", rs.getString(1));
            }
            st.execute(passThrough("CREATE SEQUENCE IF NOT EXISTS jdbc_seq START 500"));
            try (ResultSet rs = st.executeQuery(passThrough("SELECT nextval('jdbc_seq')"))) {
                assertTrue(rs.next());
                assertEquals(500, rs.getLong(1));
            }
        }
    }

    /** Pins the v1.5.3 beta limitations; if an upgrade lifts them, these fail and we simplify. */
    @Test
    void betaLimitationsClientSideMutationAndCatalog() throws Exception {
        // The DuckDB JDBC driver invalidates a Statement after an error, so each
        // probe needs its own.
        try (Connection client = openClient("client-limits")) {
            SQLException update = assertThrows(SQLException.class, () -> {
                try (Statement st = client.createStatement()) {
                    st.execute("UPDATE remote.machine_activity SET remark='x' WHERE id=1");
                }
            });
            assertTrue(update.getMessage().contains("Can only update base table"), update.getMessage());

            SQLException delete = assertThrows(SQLException.class, () -> {
                try (Statement st = client.createStatement()) {
                    st.execute("DELETE FROM remote.machine_activity WHERE id=1");
                }
            });
            assertTrue(delete.getMessage().contains("Can only delete from base table"), delete.getMessage());

            // Two remote tables in one client-side query -> multiple streaming scans.
            assertThrows(SQLException.class, () -> {
                try (Statement st = client.createStatement()) {
                    st.executeQuery("SELECT m.name, count(*) FROM remote.machine_activity a "
                            + "JOIN remote.machine m ON m.id = a.machine_id GROUP BY m.name");
                }
            });

            // Remote sequences don't surface in the client catalog (blocks Hibernate ids).
            assertThrows(SQLException.class, () -> {
                try (Statement st = client.createStatement()) {
                    st.executeQuery("SELECT nextval('remote.jdbc_seq')");
                }
            });
        }
    }

    private String passThrough(String sql) {
        return "SELECT * FROM quack_query('" + SERVER_URL + "', $$" + sql + "$$, "
                + "token=>'" + TOKEN + "', disable_ssl=>true)";
    }
}
