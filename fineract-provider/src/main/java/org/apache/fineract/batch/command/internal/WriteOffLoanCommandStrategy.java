package org.apache.fineract.batch.command.internal;

import javax.ws.rs.core.UriInfo;

import org.apache.fineract.batch.command.CommandStrategy;
import org.apache.fineract.batch.domain.BatchRequest;
import org.apache.fineract.batch.domain.BatchResponse;
import org.apache.fineract.batch.exception.ErrorHandler;
import org.apache.fineract.batch.exception.ErrorInfo;
import org.apache.fineract.portfolio.loanaccount.api.LoansApiResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WriteOffLoanCommandStrategy implements CommandStrategy {

    private final LoansApiResource loansApiResource;

    @Autowired
    public WriteOffLoanCommandStrategy(final LoansApiResource loansApiResource) {
        this.loansApiResource = loansApiResource;
    }

    @Override
    public BatchResponse execute(final BatchRequest request, UriInfo uriInfo) {

        final BatchResponse response = new BatchResponse();
        final String responseBody;

        response.setRequestId(request.getRequestId());
        response.setHeaders(request.getHeaders());

        // Extract loanId from URL: loans/{loanId}/transactions?command=writeoff
        final String[] pathParameters = request.getRelativeUrl().split("/");
        Long loanId = Long.parseLong(pathParameters[1]);

        try {
            // Call write-off transaction
            responseBody = loansApiResource.stateTransitions(
                    loanId,
                    "writeoff",
                    request.getBody()
            );

            response.setStatusCode(200);
            response.setBody(responseBody);

        } catch (RuntimeException e) {

            ErrorInfo ex = ErrorHandler.handler(e);

            response.setStatusCode(ex.getStatusCode());
            response.setBody(ex.getMessage());
        }

        return response;
    }
}