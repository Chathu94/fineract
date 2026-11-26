/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.infrastructure.core.boot.db;

import static org.junit.Assert.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.fineract.infrastructure.security.service.JdbcTenantDetailsService;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.googlecode.flyway.core.Flyway;

/**
 * The tenants-store pool must not leave a transaction open after a tenant lookup, even when the previous borrower
 * (OpenJPA, at boot) returned the connection with autocommit off. Vitess kills such idle transactions after 50m and
 * the next lookup on that connection fails, which made Quartz skip triggers.
 *
 * Needs a MySQL server: -Dfineract.test.mysql.host/port/username/password (default localhost:3306 root/mysql);
 * skipped when none is reachable.
 */
public class TenantsDataSourceAutoCommitTest {

    private static final String DB = "fineract_tenants_autocommit_test";

    private final String host = System.getProperty("fineract.test.mysql.host", "localhost");
    private final int port = Integer.getInteger("fineract.test.mysql.port", 3306);
    private final String username = System.getProperty("fineract.test.mysql.username", "root");
    private final String password = System.getProperty("fineract.test.mysql.password", "mysql");

    private Connection observer;
    private org.apache.tomcat.jdbc.pool.DataSource tenantsDataSource;

    @Before
    public void setUp() throws SQLException {
        try {
            this.observer = DriverManager.getConnection("jdbc:mysql://" + this.host + ":" + this.port + "/", this.username,
                    this.password);
        } catch (final SQLException e) {
            Assume.assumeNoException("No MySQL at " + this.host + ":" + this.port, e);
        }
        try (Statement st = this.observer.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DB);
            st.execute("CREATE DATABASE " + DB);
        }

        final DataSourceProperties properties = new DataSourceProperties("com.mysql.jdbc.Driver", "jdbc", "mysql", this.port);
        properties.setHost(this.host);
        properties.setDBName(DB);
        properties.setUsername(this.username);
        properties.setPassword(this.password);

        // the real tenants schema; Flyway holds two connections at once, so not through the single-connection pool
        final Flyway flyway = new Flyway();
        flyway.setDataSource(properties.getUrl(), this.username, this.password);
        flyway.setLocations("sql/migrations/list_db");
        flyway.migrate();

        // a single physical connection, so every borrow below gets the same one
        properties.setInitialSize(1);
        properties.setMaxActive(1);
        this.tenantsDataSource = new org.apache.tomcat.jdbc.pool.DataSource(properties);
    }

    @After
    public void tearDown() throws SQLException {
        if (this.tenantsDataSource != null) {
            this.tenantsDataSource.close();
        }
        if (this.observer != null) {
            try (Statement st = this.observer.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS " + DB);
            }
            this.observer.close();
        }
    }

    @Test
    public void tenantLookupLeavesNoOpenTransactionAfterConnectionReturnedWithAutoCommitOff() throws SQLException {
        final long connectionId;
        try (Connection connection = this.tenantsDataSource.getConnection()) {
            connectionId = queryLong(connection, "SELECT CONNECTION_ID()");
            // what OpenJPA's ConfiguringConnectionDecorator does before closing the connection back into the pool
            connection.setAutoCommit(false);
        }

        new JdbcTenantDetailsService(this.tenantsDataSource).loadTenantById("default");

        assertEquals("open transactions on the pooled connection after the tenant lookup", 0,
                queryLong(this.observer, "SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_mysql_thread_id = " + connectionId));
        try (Connection connection = this.tenantsDataSource.getConnection()) {
            assertEquals("same physical connection", connectionId, queryLong(connection, "SELECT CONNECTION_ID()"));
            assertEquals("server-side autocommit", 1, queryLong(connection, "SELECT @@autocommit"));
        }
    }

    private static long queryLong(final Connection connection, final String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
