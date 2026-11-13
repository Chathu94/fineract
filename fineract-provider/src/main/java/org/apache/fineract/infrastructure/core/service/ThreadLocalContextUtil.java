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
package org.apache.fineract.infrastructure.core.service;

import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.Assert;

/**
 *
 */
public class ThreadLocalContextUtil {

    public static final String CONTEXT_TENANTS = "tenants";

    private static final ThreadLocal<String> contextHolder = new ThreadLocal<>();

    private static final ThreadLocal<FineractPlatformTenant> tenantcontext = new ThreadLocal<>();
    
    private static final ThreadLocal<String> authTokenContext = new ThreadLocal<>();
    
    public static void setTenant(final FineractPlatformTenant tenant) {
        Assert.notNull(tenant, "tenant cannot be null");
        tenantcontext.set(tenant);
    }

    public static FineractPlatformTenant getTenant() {
        return tenantcontext.get();
    }

    public static Boolean getVTReplicaMode() {
        String replicaMode = System.getenv("EVOKE_VITESS_REPLICA_MODE");

        if (replicaMode != null && !replicaMode.isEmpty()) {
            try {
                return Boolean.parseBoolean(replicaMode);
            } catch (NumberFormatException e) {
                System.err.println("Invalid EVOKE_VITESS_REPLICA_MODE env value: " + replicaMode + ", falling back to false");
            }
        }

        String dsHost = System.getenv("DS_HOST");
        if (dsHost != null && dsHost.toLowerCase().contains("vitess")) {
            return true;
        }

        String overrideHost = System.getenv("EVOKE_SQL_TENANT_HOST_OVERRIDE");
        if (overrideHost != null && overrideHost.toLowerCase().contains("vitess")) {
            return true;
        }

        return false;
    }

    public static void executeReplicaQuery(final RoutingDataSource dataSource, final boolean readOnly) {
        if (!ThreadLocalContextUtil.getVTReplicaMode()) return;
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        String schema = "fused_fli";
        FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
        if (tenant != null && tenant.getConnection() != null && tenant.getConnection().getSchemaName() != null) {
            schema = tenant.getConnection().getSchemaName();
        }
        if (readOnly) {
            jdbcTemplate.execute("USE `" + schema + "@replica`");
        } else {
            jdbcTemplate.execute("USE `" + schema + "@primary`");
        }
    }

    public static void setPrimaryTarget(final JdbcTemplate jdbcTemplate) {
        if (!getVTReplicaMode()) return;
        String schema = "fused_fli";
        FineractPlatformTenant tenant = getTenant();
        if (tenant != null && tenant.getConnection() != null && tenant.getConnection().getSchemaName() != null) {
            schema = tenant.getConnection().getSchemaName();
        }
        jdbcTemplate.execute("USE `" + schema + "@primary`");
    }

    public static void setReplicaTarget(final JdbcTemplate jdbcTemplate) {
        if (!getVTReplicaMode()) return;
        String schema = "fused_fli";
        FineractPlatformTenant tenant = getTenant();
        if (tenant != null && tenant.getConnection() != null && tenant.getConnection().getSchemaName() != null) {
            schema = tenant.getConnection().getSchemaName();
        }
        jdbcTemplate.execute("USE `" + schema + "@replica`");
    }

    public static void clearTenant() {
        tenantcontext.remove();
    }

    public static String getDataSourceContext() {
        return contextHolder.get();
    }

    public static void setDataSourceContext(final String dataSourceContext) {
        contextHolder.set(dataSourceContext);
    }

    public static void clearDataSourceContext() {
        contextHolder.remove();
    }
    
    public static void setAuthToken(final String authToken) {
    	authTokenContext.set(authToken);
    }

    public static String getAuthToken() {
        return authTokenContext.get();
    }

}