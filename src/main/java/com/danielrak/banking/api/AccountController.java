package com.danielrak.banking.api;

import com.danielrak.banking.domain.Account;
import com.danielrak.banking.domain.AccountService;
import com.danielrak.banking.domain.Currency;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @Operation(summary = "Create an account with a zero balance in each currency")
    @ApiResponse(responseCode = "201", description = "Account created; Location points to it")
    @ApiResponse(responseCode = "400",
            description = "INVALID_CURRENCY for an unsupported or null currency; VALIDATION_FAILED otherwise",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        List<Currency> currencies = request.currencies().stream().map(Currency::valueOf).toList();
        Account account = accountService.create(request.customerId(), request.country(), currencies);
        return ResponseEntity.created(URI.create("/accounts/" + account.id()))
                .body(AccountResponse.from(account));
    }

    @GetMapping("/{accountId}")
    @NotFoundCode(ErrorCode.ACCOUNT_NOT_FOUND)
    @Operation(summary = "Get an account with its balances")
    @ApiResponse(responseCode = "200", description = "The account, balances in currency order")
    @ApiResponse(responseCode = "400", description = "ACCOUNT_NOT_FOUND: the ID is not a UUID",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND: no account has this ID",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public AccountResponse get(@PathVariable UUID accountId) {
        return AccountResponse.from(accountService.get(accountId));
    }
}
