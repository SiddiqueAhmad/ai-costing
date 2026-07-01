package com.aicosting;

import org.hibernate.dialect.PostgreSQLDialect;

/**
 * DuckDB speaks close enough to the Postgres wire dialect that Hibernate's
 * stock {@link PostgreSQLDialect} works against it, with one exception:
 * Postgres-only schema-management SQL ("set client_min_messages = WARNING",
 * emitted before DROP statements to silence Postgres NOTICE warnings) isn't
 * recognized by DuckDB. This subclass just disables that one statement.
 */
public class DuckDbDialect extends PostgreSQLDialect {

    @Override
    public String getBeforeDropStatement() {
        return null;
    }
}
