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
package org.apache.fineract.batch.command.internal;

import com.google.gson.JsonElement;
import org.apache.fineract.batch.command.CommandStrategy;
import org.apache.fineract.batch.domain.BatchRequest;
import org.apache.fineract.batch.domain.BatchResponse;
import org.apache.fineract.batch.exception.ErrorHandler;
import org.apache.fineract.batch.exception.ErrorInfo;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.loanaccount.api.LoanChargesApiResource;
import org.apache.fineract.portfolio.loanaccount.service.LoanWritePlatformService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.ws.rs.core.UriInfo;

/**
 * Implements {@link CommandStrategy} and Create
 * Charge for a Loan. It passes the contents of the body from the BatchRequest
 * to {@link LoanChargesApiResource} and
 * gets back the response. This class will also catch any errors raised by
 * {@link LoanChargesApiResource} and map
 * those errors to appropriate status codes in BatchResponse.
 * 
 * @author Rishabh Shukla
 * 
 * @see CommandStrategy
 * @see BatchRequest
 * @see BatchResponse
 */
@Component
public class WaiveChargeCommandStrategy implements CommandStrategy {

    private final LoanWritePlatformService writePlatformService;
    private final FromJsonHelper fromApiJsonHelper;

    @Autowired
    public WaiveChargeCommandStrategy(final LoanWritePlatformService writePlatformService, final FromJsonHelper fromApiJsonHelper) {
        this.writePlatformService = writePlatformService;
        this.fromApiJsonHelper = fromApiJsonHelper;
    }

    @Override
    public BatchResponse execute(BatchRequest request, @SuppressWarnings("unused") UriInfo uriInfo) {

        final BatchResponse response = new BatchResponse();
        final String responseBody;

        response.setRequestId(request.getRequestId());
        response.setHeaders(request.getHeaders());

        final String[] pathParameters = request.getRelativeUrl().split("/");
        final String[] pathParameters2 = pathParameters[3].split("\\?");
        Long loanId = Long.parseLong(pathParameters[1]);
        Long chargeId = Long.parseLong(pathParameters2[0]);

        // Try-catch blocks to map exceptions to appropriate status codes
        try {

            // Calls 'executeLoanCharge' function from 'LoanChargesApiResource'
            // to create
            // a new charge for a loan
            final CommandWrapperBuilder builder = new CommandWrapperBuilder().withJson(request.getBody());
            final CommandWrapper wrapper = builder.waiveLoanCharge(loanId, chargeId).build();
            final JsonElement parsedCommand = this.fromApiJsonHelper.parse(request.getBody());
            JsonCommand command = JsonCommand.from(request.getBody(), parsedCommand, this.fromApiJsonHelper, wrapper.getEntityName(), wrapper.getEntityId(),
                    wrapper.getSubentityId(), wrapper.getGroupId(), wrapper.getClientId(), wrapper.getLoanId(), wrapper.getSavingsId(),
                    wrapper.getTransactionId(), wrapper.getHref(), wrapper.getProductId(),wrapper.getCreditBureauId(),wrapper.getOrganisationCreditBureauId());
            responseBody = writePlatformService.waiveLoanCharge(loanId, chargeId, command).toString();

            response.setStatusCode(200);
            // Sets the body of the response after Charge has been successfully
            // created
            response.setBody(responseBody);

        } catch (RuntimeException e) {

            // Gets an object of type ErrorInfo, containing information about
            // raised exception
            ErrorInfo ex = ErrorHandler.handler(e);

            response.setStatusCode(ex.getStatusCode());
            response.setBody(ex.getMessage());
        }

        return response;
    }
}
