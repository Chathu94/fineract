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

import java.util.Collections;
import java.util.Map;
import com.google.gson.JsonParser;
import org.apache.fineract.commands.api.MakercheckersApiResource;
import org.apache.fineract.commands.domain.CommandSource;
import org.apache.fineract.commands.domain.CommandSourceRepository;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.exception.CommandNotAwaitingApprovalException;
import org.apache.fineract.commands.exception.WorkflowDecisionException;
import org.apache.fineract.commands.handler.NewCommandSourceHandler;
import org.apache.fineract.commands.provider.CommandHandlerProvider;
import org.apache.fineract.commands.service.WorkflowDecisionRepository.WorkflowState;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.ExcludeNothingWithPrettyPrintingOffJsonSerializerGoogleGson;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.jobs.service.SchedulerJobRunnerReadService;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

public class MakerCheckerExecutionTest {
    private static final Long ID = 93001L;
    private static final Long LOAN = 93002L;
    private static final String JSON = "{\"amount\":42}";
    private CommandSource commandSource;
    private CommandSourceRepository commandRepository;
    private WorkflowDecisionRepository workflowRepository;
    private PortfolioCommandSourceWritePlatformServiceImpl writeService;
    private MakercheckersApiResource api;
    private NewCommandSourceHandler handler;
    private AppUser checker;
    private PlatformSecurityContext context;

    @Before @SuppressWarnings("unchecked") public void setUp() {
        commandRepository = mock(CommandSourceRepository.class);
        workflowRepository = mock(WorkflowDecisionRepository.class);
        handler = mock(NewCommandSourceHandler.class);
        context = mock(PlatformSecurityContext.class);
        checker = mock(AppUser.class);
        when(context.authenticatedUser()).thenReturn(checker);
        when(context.authenticatedUser(any(CommandWrapper.class))).thenReturn(checker);
        FromJsonHelper jsonHelper = mock(FromJsonHelper.class);
        when(jsonHelper.parse(JSON)).thenReturn(new JsonParser().parse(JSON));
        String href = "/loans/93002/transactions?command=waiveinterest";
        CommandWrapper wrapper = CommandWrapper.fromExistingCommand(ID, "WAIVEINTERESTPORTION", "LOAN", LOAN, null, href, null,
                null, null, null, LOAN, null, null, null, null);
        JsonCommand command = JsonCommand.fromExistingCommand(ID, JSON, new JsonParser().parse(JSON), jsonHelper, "LOAN", LOAN, null,
                null, null, LOAN, null, null, href, null, null, null);
        commandSource = CommandSource.fullEntryFrom(wrapper, command, mock(AppUser.class));
        commandSource.updateForAudit(null, null, null, LOAN, null, null, null);
        ReflectionTestUtils.setField(commandSource, "id", ID);
        commandSource.markAsAwaitingApproval();
        when(commandRepository.findOne(ID)).thenReturn(commandSource);
        CommandHandlerProvider provider = mock(CommandHandlerProvider.class);
        when(provider.getHandler("LOAN", "WAIVEINTERESTPORTION")).thenReturn(handler);
        when(handler.processCommand(any(JsonCommand.class))).thenReturn(new CommandProcessingResultBuilder().withEntityId(LOAN)
                .withLoanId(LOAN).build());
        SynchronousCommandProcessingService processor = new SynchronousCommandProcessingService(context, mock(ApplicationContext.class),
                (ToApiJsonSerializer<Map<String, Object>>) mock(ToApiJsonSerializer.class),
                (ToApiJsonSerializer<CommandProcessingResult>) mock(ToApiJsonSerializer.class), commandRepository,
                mock(ConfigurationDomainService.class), provider);
        writeService = new PortfolioCommandSourceWritePlatformServiceImpl(context, commandRepository, jsonHelper, processor,
                mock(SchedulerJobRunnerReadService.class), new WorkflowDecisionService(workflowRepository));
        api = new MakercheckersApiResource(null, new DefaultToApiJsonSerializer<>(
                new ExcludeNothingWithPrettyPrintingOffJsonSerializerGoogleGson(), null, null, null), null, null, writeService, null);
        when(workflowRepository.isValidationEnabled(ID)).thenReturn(true);
        when(workflowRepository.hasWorkflowSchema()).thenReturn(true);
        state("PENDING_APPROVAL", null);
    }

    private void state(String status, String approvalStatus) {
        when(workflowRepository.lockContexts(ID)).thenReturn(Collections.singletonList(new WorkflowState(status, approvalStatus)));
    }

    private void assertNoExecutionOrAuditChange() {
        verify(handler, never()).processCommand(any(JsonCommand.class));
        verify(commandRepository, never()).save(any(CommandSource.class));
        assertTrue(commandSource.isMarkedAsAwaitingApproval());
        assertNull(ReflectionTestUtils.getField(commandSource, "checker"));
        assertNull(ReflectionTestUtils.getField(commandSource, "checkedOnDate"));
    }

    private void denied(String action, String reason) {
        try { api.approveMakerCheckerEntry(ID, action, false, false); fail("Expected workflow refusal"); }
        catch (WorkflowDecisionException expected) {
            assertEquals("error.msg.command.workflow." + reason, expected.getGlobalisationMessageCode());
            assertNoExecutionOrAuditChange();
        }
    }

    @Test public void pendingWorkflowCannotApproveEvenWithAllUserPermissions() {
        denied("approve", "approvals.incomplete");
        verify(checker).validateHasCheckerPermissionTo("WAIVEINTERESTPORTION_LOAN");
        verify(checker, never()).getId();
    }

    @Test public void pendingWorkflowCannotRejectEvenWithAllUserPermissions() {
        denied("reject", "approvals.incomplete");
    }

    @Test public void runningWithoutDecisionBlocksBothActions() {
        state("RUNNING", "");
        denied("approve", "approvals.incomplete");
        denied("reject", "approvals.incomplete");
    }

    @Test public void invalidWorkflowStateBlocksBothActions() {
        state("COMPLETED", null);
        denied("approve", "state.invalid");
        denied("reject", "state.invalid");
    }

    private void approveAndVerifyAudit() {
        approveAndVerifyAudit(false);
    }

    private void approveAndVerifyAudit(final boolean execute) {
        approveAndVerifyAudit(false, execute);
    }

    private void approveAndVerifyAudit(final Boolean makerIsChecker, final boolean execute) {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "synthetic", "Synthetic tenant", "Asia/Colombo", null));
        try { api.approveMakerCheckerEntry(ID, "approve", makerIsChecker, execute); }
        finally { ThreadLocalContextUtil.clearTenant(); }
        verify(handler).processCommand(any(JsonCommand.class));
        verify(commandRepository).save(commandSource);
        assertFalse(commandSource.isMarkedAsAwaitingApproval());
        assertSame(checker, ReflectionTestUtils.getField(commandSource, "checker"));
        assertNotNull(ReflectionTestUtils.getField(commandSource, "checkedOnDate"));
    }

    @Test public void completedApprovedWorkflowExecutesAndAuditsNormally() {
        state("COMPLETED", "APPROVED");
        approveAndVerifyAudit();
    }

    @Test public void completedRejectedWorkflowExecutesUnderRequestedPolicy() {
        state("COMPLETED", "REJECTED");
        approveAndVerifyAudit();
    }

    @Test public void completedApprovedWorkflowCanRejectNormally() {
        state("COMPLETED", "APPROVED");
        api.approveMakerCheckerEntry(ID, "reject", false, false);
        verify(handler, never()).processCommand(any(JsonCommand.class));
        verify(commandRepository).save(commandSource);
        assertFalse(commandSource.isMarkedAsAwaitingApproval());
        assertSame(checker, ReflectionTestUtils.getField(commandSource, "checker"));
    }

    @Test public void completedRejectedWorkflowCanRejectNormally() {
        state("COMPLETED", "REJECTED");
        api.approveMakerCheckerEntry(ID, "reject", false, false);
        verify(commandRepository).save(commandSource);
        assertFalse(commandSource.isMarkedAsAwaitingApproval());
    }

    @Test public void unlinkedCommandRetainsExistingApprovalBehavior() {
        when(workflowRepository.lockContexts(ID)).thenReturn(Collections.<WorkflowState>emptyList());
        approveAndVerifyAudit();
    }

    @Test public void existingCheckerPermissionIsStillRequired() {
        state("COMPLETED", "APPROVED");
        doThrow(new NoAuthorizationException("No checker permission")).when(checker)
                .validateHasCheckerPermissionTo("WAIVEINTERESTPORTION_LOAN");
        try { api.approveMakerCheckerEntry(ID, "approve", false, false); fail("Expected checker refusal"); }
        catch (NoAuthorizationException expected) { assertNoExecutionOrAuditChange(); }
        verify(workflowRepository, never()).lockContexts(anyLong());
    }

    @Test public void decidedCommandStillCannotBeReplayed() {
        commandSource.markAsRejected(checker, org.joda.time.DateTime.now());
        try { api.approveMakerCheckerEntry(ID, "approve", false, false); fail("Expected pending-command refusal"); }
        catch (CommandNotAwaitingApprovalException expected) {
            verify(handler, never()).processCommand(any(JsonCommand.class));
        }
        verify(workflowRepository, never()).lockContexts(anyLong());
    }

    @Test public void executeTrueApprovesPendingWorkflowAndKeepsAudit() {
        approveAndVerifyAudit(true);
        verify(workflowRepository, never()).isValidationEnabled(anyLong());
        verify(workflowRepository, never()).hasWorkflowSchema();
        verify(workflowRepository, never()).lockContexts(anyLong());
    }

    @Test public void executeTrueRejectsPendingWorkflowNormally() {
        api.approveMakerCheckerEntry(ID, "reject", false, true);
        verify(handler, never()).processCommand(any(JsonCommand.class));
        verify(commandRepository).save(commandSource);
        assertFalse(commandSource.isMarkedAsAwaitingApproval());
        assertSame(checker, ReflectionTestUtils.getField(commandSource, "checker"));
        verify(workflowRepository, never()).isValidationEnabled(anyLong());
        verify(workflowRepository, never()).hasWorkflowSchema();
    }

    @Test public void executeTrueBypassesInvalidWorkflowState() {
        state("COMPLETED", null);
        approveAndVerifyAudit(true);
        verify(workflowRepository, never()).hasWorkflowSchema();
    }

    @Test public void executeTrueStillRequiresCheckerPermissionForBothActions() {
        doThrow(new NoAuthorizationException("No checker permission")).when(checker)
                .validateHasCheckerPermissionTo("WAIVEINTERESTPORTION_LOAN");
        for (String action : new String[] {"approve", "reject"}) {
            try { api.approveMakerCheckerEntry(ID, action, false, true); fail("Expected checker refusal"); }
            catch (NoAuthorizationException expected) { assertNoExecutionOrAuditChange(); }
        }
        verify(workflowRepository, never()).hasWorkflowSchema();
    }

    @Test public void executeTrueStillRefusesAlreadyDecidedCommandForBothActions() {
        commandSource.markAsRejected(checker, org.joda.time.DateTime.now());
        for (String action : new String[] {"approve", "reject"}) {
            try { api.approveMakerCheckerEntry(ID, action, false, true); fail("Expected pending-command refusal"); }
            catch (CommandNotAwaitingApprovalException expected) {
                verify(handler, never()).processCommand(any(JsonCommand.class));
                verify(commandRepository, never()).save(any(CommandSource.class));
            }
        }
        verify(workflowRepository, never()).hasWorkflowSchema();
    }

    @Test public void serviceCallsWithoutExecuteStillValidateWorkflow() {
        try { writeService.approveEntry(ID); fail("Expected workflow refusal"); }
        catch (WorkflowDecisionException expected) { assertNoExecutionOrAuditChange(); }
        try { writeService.rejectEntry(ID); fail("Expected workflow refusal"); }
        catch (WorkflowDecisionException expected) { assertNoExecutionOrAuditChange(); }
    }

    @Test public void disabledConfigurationAllowsPendingWorkflowApproval() {
        when(workflowRepository.isValidationEnabled(ID)).thenReturn(false);
        approveAndVerifyAudit();
        verify(workflowRepository).isValidationEnabled(ID);
        verify(workflowRepository, never()).hasWorkflowSchema();
        verify(workflowRepository, never()).lockContexts(anyLong());
    }

    @Test public void disabledConfigurationAllowsPendingWorkflowRejection() {
        when(workflowRepository.isValidationEnabled(ID)).thenReturn(false);
        api.approveMakerCheckerEntry(ID, "reject", false, false);
        verify(commandRepository).save(commandSource);
        assertFalse(commandSource.isMarkedAsAwaitingApproval());
        assertSame(checker, ReflectionTestUtils.getField(commandSource, "checker"));
        verify(handler, never()).processCommand(any(JsonCommand.class));
        verify(workflowRepository).isValidationEnabled(ID);
        verify(workflowRepository, never()).lockContexts(anyLong());
    }

    @Test public void disabledConfigurationStillRequiresCheckerPermissionForBothActions() {
        when(workflowRepository.isValidationEnabled(ID)).thenReturn(false);
        doThrow(new NoAuthorizationException("No checker permission")).when(checker)
                .validateHasCheckerPermissionTo("WAIVEINTERESTPORTION_LOAN");
        for (String action : new String[] {"approve", "reject"}) {
            try { api.approveMakerCheckerEntry(ID, action, false, false); fail("Expected checker refusal"); }
            catch (NoAuthorizationException expected) { assertNoExecutionOrAuditChange(); }
        }
        verify(workflowRepository, never()).isValidationEnabled(anyLong());
    }

    @Test public void enabledConfigurationChecksOriginalCommandIdForBothActions() {
        denied("approve", "approvals.incomplete");
        denied("reject", "approvals.incomplete");
        verify(workflowRepository, times(2)).isValidationEnabled(ID);
    }

    @Test public void makerIsCheckerDoesNotBypassPendingWorkflowValidation() {
        try { api.approveMakerCheckerEntry(ID, "approve", true, false); fail("Expected workflow refusal"); }
        catch (WorkflowDecisionException expected) { assertNoExecutionOrAuditChange(); }
        verify(context, never()).setMaker(any(AppUser.class));
    }

    @Test public void makerIsCheckerAndExecuteCanBothBeEnabled() {
        approveAndVerifyAudit(true, true);
        verify(context).setMaker(commandSource.getMaker());
        verify(context).setMaker(null);
        verify(workflowRepository, never()).isValidationEnabled(anyLong());
    }

    @Test public void makerIsCheckerStillWorksWithCompletedWorkflowValidation() {
        state("COMPLETED", "APPROVED");
        approveAndVerifyAudit(true, false);
        verify(context).setMaker(commandSource.getMaker());
        verify(context).setMaker(null);
        verify(workflowRepository).lockContexts(ID);
    }

    @Test public void legacyMakerIsCheckerServiceCallStillValidatesWorkflow() {
        try { writeService.approveEntry(ID, Boolean.TRUE); fail("Expected workflow refusal"); }
        catch (WorkflowDecisionException expected) { assertNoExecutionOrAuditChange(); }
        verify(workflowRepository).lockContexts(ID);
    }

    @Test public void deleteRetainsExistingBehaviorWithoutNewWorkflowCheck() {
        writeService.deleteEntry(ID);
        verify(commandRepository).delete(ID);
        verify(workflowRepository, never()).hasWorkflowSchema();
    }
}
