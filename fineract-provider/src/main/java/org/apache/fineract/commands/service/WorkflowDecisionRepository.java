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
package org.apache.fineract.commands.service;

import java.util.ArrayList;
import java.util.List;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.springframework.stereotype.Repository;

/** Reads existing workflow state using the maker-checker decision transaction. */
@Repository
public class WorkflowDecisionRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public boolean isValidationEnabled(final Long commandId) {
        if (!hasTable("c_checker_inbox_rule")) { return false; }
        final Query query = this.entityManager.createNativeQuery("select config.id from m_portfolio_command_source cmd "
                + "join c_checker_inbox_rule rule on rule.entity_name = cmd.entity_name and rule.action_name = cmd.action_name "
                + "join c_configuration config on config.id = rule.configuration_id "
                + "where cmd.id = ?1 and config.enabled = 1 and rule.validation_enabled = 1");
        query.setParameter(1, commandId);
        return !query.getResultList().isEmpty();
    }

    public boolean hasWorkflowSchema() {
        return hasTable("workflow_context");
    }

    private boolean hasTable(final String tableName) {
        final String schemaName = ThreadLocalContextUtil.getTenant().getConnection().getSchemaName();
        final Query query = this.entityManager
                .createNativeQuery("SHOW TABLES FROM `" + schemaName + "` LIKE '" + tableName + "'");
        for (Object value : query.getResultList()) {
            final Object name = value instanceof Object[] ? ((Object[]) value)[0] : value;
            if (name != null && tableName.equals(name.toString())) { return true; }
        }
        return false;
    }

    public List<WorkflowState> lockContexts(final Long commandId) {
        final Query query = this.entityManager.createNativeQuery("select status, approval_status from workflow_context "
                + "where command_id = ?1 and binary command_id = binary ?1 order by id for update");
        query.setParameter(1, commandId.toString());
        final List<WorkflowState> states = new ArrayList<>();
        for (Object value : query.getResultList()) {
            final Object[] row = (Object[]) value;
            states.add(new WorkflowState(string(row[0]), string(row[1])));
        }
        return states;
    }

    private static String string(final Object value) { return value == null ? null : value.toString(); }

    public static final class WorkflowState {
        final String status;
        final String approvalStatus;

        public WorkflowState(final String status, final String approvalStatus) {
            this.status = status;
            this.approvalStatus = approvalStatus;
        }
    }
}
