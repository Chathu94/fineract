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
import org.apache.fineract.commands.exception.WorkflowDecisionException;
import org.apache.fineract.commands.service.WorkflowDecisionRepository.WorkflowState;
import org.junit.Before;
import org.junit.Test;

public class WorkflowDecisionServiceTest {
    private static final Long COMMAND = 71001L;
    private WorkflowDecisionRepository repository;
    private WorkflowDecisionService service;

    @Before public void setUp() {
        repository = mock(WorkflowDecisionRepository.class);
        service = new WorkflowDecisionService(repository);
        when(repository.isValidationEnabled(COMMAND)).thenReturn(true);
    }

    private void state(final String status, final String approvalStatus) {
        when(repository.lockContexts(COMMAND)).thenReturn(Collections.singletonList(new WorkflowState(status, approvalStatus)));
    }

    private void denied(final String reason) {
        try { service.validate(COMMAND); fail("Expected workflow refusal"); }
        catch (WorkflowDecisionException expected) {
            assertEquals("error.msg.command.workflow." + reason, expected.getGlobalisationMessageCode());
        }
    }

    @Test public void runningAndPendingApprovalWithoutDecisionAreBlocked() {
        for (String status : Arrays.asList("RUNNING", "PENDING_APPROVAL")) {
            state(status, null);
            denied("approvals.incomplete");
        }
    }

    @Test public void emptyAndWhitespaceApprovalStatusesArePending() {
        for (String status : Arrays.asList("RUNNING", "PENDING_APPROVAL")) {
            for (String decision : Arrays.asList("", " ", "\t\n")) {
                state(status, decision);
                denied("approvals.incomplete");
            }
        }
    }

    @Test public void completedApprovedWorkflowAllowsDecision() {
        state("COMPLETED", "APPROVED");
        service.validate(COMMAND);
    }

    @Test public void completedRejectedWorkflowAllowsDecision() {
        state("COMPLETED", "REJECTED");
        service.validate(COMMAND);
    }

    @Test public void completedWithoutRecognizedDecisionIsBlocked() {
        for (String decision : Arrays.asList(null, "", " ", "CHANGE_REQUESTED", "APPOVED")) {
            state("COMPLETED", decision);
            denied("state.invalid");
        }
    }

    @Test public void pendingStatusWithDecisionIsInconsistent() {
        for (String status : Arrays.asList("RUNNING", "PENDING_APPROVAL")) {
            for (String decision : Arrays.asList("APPROVED", "REJECTED")) {
                state(status, decision);
                denied("state.invalid");
            }
        }
    }

    @Test public void unsupportedStatusIsBlocked() {
        for (String status : Arrays.asList(null, "", "ERROR", "STOPPED", "CANCELLED")) {
            state(status, null);
            denied("state.invalid");
        }
    }

    @Test public void unlinkedCommandRetainsExistingBehavior() {
        when(repository.lockContexts(COMMAND)).thenReturn(Collections.<WorkflowState>emptyList());
        service.validate(COMMAND);
    }

    @Test public void allLinkedWorkflowsMustBeCompleted() {
        when(repository.lockContexts(COMMAND)).thenReturn(Arrays.asList(
                new WorkflowState("COMPLETED", "APPROVED"), new WorkflowState("PENDING_APPROVAL", null)));
        denied("approvals.incomplete");
    }

    @Test public void multipleCompletedWorkflowsAreAllowed() {
        when(repository.lockContexts(COMMAND)).thenReturn(Arrays.asList(
                new WorkflowState("COMPLETED", "APPROVED"), new WorkflowState("COMPLETED", "REJECTED")));
        service.validate(COMMAND);
    }

    @Test public void disabledConfigurationSkipsWorkflowValidation() {
        state("PENDING_APPROVAL", null);
        when(repository.isValidationEnabled(COMMAND)).thenReturn(false);
        service.validate(COMMAND);
        verify(repository, never()).lockContexts(anyLong());
    }

    @Test public void currentConfigurationIsReadForEveryDecision() {
        state("PENDING_APPROVAL", null);
        when(repository.isValidationEnabled(COMMAND)).thenReturn(false, true);
        service.validate(COMMAND);
        denied("approvals.incomplete");
        verify(repository, times(2)).isValidationEnabled(COMMAND);
    }
}
