package com.danielrak.banking.api;

import com.danielrak.banking.domain.Currency;
import com.danielrak.banking.domain.Direction;
import com.danielrak.banking.domain.Transaction;
import com.danielrak.banking.domain.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts/{accountId}/transactions")
@Tag(name = "Transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /** 201 without {@code Location}: there is no endpoint for a single transaction (design.md §2). */
    @PostMapping
    @NotFoundCode(ErrorCode.ACCOUNT_MISSING)
    @Operation(summary = "Post an IN or OUT transaction and return it with the balance after it")
    @ApiResponse(responseCode = "201", description = "Transaction posted; balanceAfter is the new balance")
    @ApiResponse(responseCode = "400",
            description = "INVALID_CURRENCY, INVALID_DIRECTION, INVALID_AMOUNT or DESCRIPTION_MISSING for that field; "
                    + "ACCOUNT_MISSING if the ID is not a UUID; VALIDATION_FAILED otherwise",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "ACCOUNT_MISSING: no account has this ID",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422",
            description = "INVALID_CURRENCY: the account has no balance in this currency; "
                    + "INSUFFICIENT_FUNDS: an OUT larger than the available balance",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<TransactionResponse> create(@PathVariable UUID accountId,
            @Valid @RequestBody CreateTransactionRequest request) {
        Transaction transaction = transactionService.create(accountId, request.amount(),
                Currency.valueOf(request.currency()), Direction.valueOf(request.direction()), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(TransactionResponse.from(transaction));
    }

    @GetMapping
    @NotFoundCode(ErrorCode.INVALID_ACCOUNT)
    @Operation(summary = "List an account's transactions in the order they were posted")
    @ApiResponse(responseCode = "200", description = "The transactions, oldest first; [] if none")
    @ApiResponse(responseCode = "400", description = "INVALID_ACCOUNT: the ID is not a UUID",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "INVALID_ACCOUNT: no account has this ID",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public List<TransactionResponse> list(@PathVariable UUID accountId) {
        return transactionService.list(accountId).stream().map(TransactionResponse::from).toList();
    }
}
