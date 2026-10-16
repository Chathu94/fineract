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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.persistence.EntityManager;
import javax.persistence.Query;
import org.apache.fineract.commands.service.WorkflowDecisionRepository.WorkflowState;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

public class WorkflowDecisionRepositoryTest {
    private EntityManager entityManager;
    private Query query;
    private WorkflowDecisionRepository repository;

    @Before public void setUp() {
        entityManager = mock(EntityManager.class);
        query = mock(Query.class);
        repository = new WorkflowDecisionRepository();
        ReflectionTestUtils.setField(repository, "entityManager", entityManager);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
    }

    @Test public void schemaPresenceUsesExistingTenantWorkflowTable() {
        when(query.getResultList()).thenReturn(Collections.singletonList("workflow_context"));
        assertTrue(repository.hasWorkflowSchema());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertTrue(sql.getValue().contains("table_schema = database()"));
        assertTrue(sql.getValue().contains("table_name = 'workflow_context'"));
    }

    @Test public void missingWorkflowTableIsDetected() {
        when(query.getResultList()).thenReturn(Collections.emptyList());
        assertFalse(repository.hasWorkflowSchema());
    }

    @Test public void commandIdIsBoundAsExactTextWithoutLosingPrecision() {
        when(query.getResultList()).thenReturn(Collections.emptyList());
        repository.lockContexts(9007199254740993L);
        verify(query).setParameter(1, "9007199254740993");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertEquals("select status, approval_status from workflow_context "
                + "where command_id = ?1 and binary command_id = binary ?1 order by id for update", sql.getValue());
    }

    @Test public void multipleStatesAndEmptyApprovalStatusArePreserved() {
        when(query.getResultList()).thenReturn(Arrays.asList(
                new Object[] {"COMPLETED", "APPROVED"}, new Object[] {"RUNNING", ""},
                new Object[] {"PENDING_APPROVAL", null}));
        List<WorkflowState> states = repository.lockContexts(321001L);
        assertEquals(3, states.size());
        assertEquals("APPROVED", states.get(0).approvalStatus);
        assertEquals("RUNNING", states.get(1).status);
        assertEquals("", states.get(1).approvalStatus);
        assertNull(states.get(2).approvalStatus);
    }

    @Test public void bothEnabledFlagsAreRequiredForStoredCommandEntityAndAction() {
        when(query.getResultList()).thenReturn(Collections.singletonList("c_checker_inbox_rule"),
                Collections.singletonList(321010L));
        assertTrue(repository.isValidationEnabled(321001L));
        verify(query).setParameter(1, 321001L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager, times(2)).createNativeQuery(sql.capture());
        assertTrue(sql.getAllValues().get(0).contains("table_schema = database()"));
        assertTrue(sql.getAllValues().get(0).contains("table_name = 'c_checker_inbox_rule'"));
        String lookup = sql.getAllValues().get(1);
        assertTrue(lookup.contains("from m_portfolio_command_source cmd"));
        assertTrue(lookup.contains("rule.entity_name = cmd.entity_name"));
        assertTrue(lookup.contains("rule.action_name = cmd.action_name"));
        assertTrue(lookup.contains("config.id = rule.configuration_id"));
        assertTrue(lookup.contains("where cmd.id = ?1 and config.enabled = 1 and rule.validation_enabled = 1"));
    }

    @Test public void disabledOrMissingMatchingConfigurationOrRuleDoesNotEnableValidation() {
        when(query.getResultList()).thenReturn(Collections.singletonList("c_checker_inbox_rule"), Collections.emptyList());
        assertFalse(repository.isValidationEnabled(321001L));
        verify(query).setParameter(1, 321001L);
    }

    @Test public void missingRuleTableDoesNotEnableValidationOrQueryIt() {
        when(query.getResultList()).thenReturn(Collections.emptyList());
        assertFalse(repository.isValidationEnabled(321001L));
        verify(entityManager, times(1)).createNativeQuery(anyString());
        verify(query, never()).setParameter(anyInt(), anyObject());
    }
}
