#!/usr/bin/env bash
# Contract check (docs/test-plan.md): runs every request and error case the assignment PDF names against a
# running stack, and checks each response's status and design.md §3 `code`, plus the PDF's output fields on
# the success paths. With a RabbitMQ management URL it also checks the test account's events (§5).
#
# Usage: scripts/contract-check.sh [BASE_URL] [RABBITMQ_MGMT_URL]
#   BASE_URL           the API, default http://localhost:8080
#   RABBITMQ_MGMT_URL  e.g. http://localhost:15672; enables the event case. Credentials come from
#                      RABBITMQ_USER / RABBITMQ_PASS (default banking / banking, as in docker-compose.yml)
# Exit: 0 if every case passed, 1 if any failed, 2 if the stack or a required tool is missing.
set -uo pipefail

BASE_URL="${1:-http://localhost:8080}"
MGMT_URL="${2:-}"
MGMT_AUTH="${RABBITMQ_USER:-banking}:${RABBITMQ_PASS:-banking}"
EVENT_WAIT_SECONDS=10

for tool in curl jq; do
    command -v "$tool" >/dev/null || { echo "contract-check: $tool is required" >&2; exit 2; }
done
# jq 1.7+ keeps number literals as written, so "69.50" can be told apart from "69.5" (scale 2, ADR-0001).
[[ "$(jq -n '1.50 | tojson')" == '"1.50"' ]] || { echo "contract-check: jq 1.7+ is required" >&2; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
passed=0 failed=0 skipped=0

# request METHOD PATH [JSON]: sets STATUS and BODY. No Accept header, as a plain client would send.
request() {
    local args=(-s -o "$TMP/body" -w '%{http_code}' -X "$1")
    [[ $# -ge 3 ]] && args+=(-H 'Content-Type: application/json' --data-binary "$3")
    STATUS="$(curl "${args[@]}" "$BASE_URL$2")" || STATUS="000"
    BODY="$(cat "$TMP/body" 2>/dev/null || true)"
}

# expect ID DESCRIPTION STATUS CODE [JQ_CONDITION...]: checks the last response. CODE "-" means a success
# body (no code); each condition is a jq expression over the body that must be true.
expect() {
    local id="$1" desc="$2" want_status="$3" want_code="$4" cond code
    shift 4
    if [[ "$STATUS" != "$want_status" ]]; then
        fail "$id" "$desc" "status $STATUS, expected $want_status; body: ${BODY:0:300}"
        return
    fi
    if [[ "$want_code" != "-" ]]; then
        code="$(jq -r '.code // "<none>"' <<<"$BODY" 2>/dev/null || echo "<not JSON>")"
        if [[ "$code" != "$want_code" ]]; then
            fail "$id" "$desc" "code $code, expected $want_code; body: ${BODY:0:300}"
            return
        fi
    fi
    for cond in "$@"; do
        if ! jq -e "$cond" <<<"$BODY" >/dev/null 2>&1; then
            fail "$id" "$desc" "not true: $cond; body: ${BODY:0:300}"
            return
        fi
    done
    pass "$id" "$desc"
}

pass() { passed=$((passed + 1)); printf 'PASS  %s  %s\n' "$1" "$2"; }
fail() { failed=$((failed + 1)); printf 'FAIL  %s  %s\n      %s\n' "$1" "$2" "$3"; }
skip() { skipped=$((skipped + 1)); printf 'SKIP  %s  %s: %s\n' "$1" "$2" "$3"; }

# A money field: a JSON number written with exactly 2 decimals (design.md §2).
money() { printf '(%s | tojson) == "%s"' "$1" "$2"; }

request GET /actuator/health
if [[ "$STATUS" != "200" ]] || ! jq -e '.status == "UP"' <<<"$BODY" >/dev/null 2>&1; then
    echo "contract-check: $BASE_URL is not up (GET /actuator/health → $STATUS)" >&2
    exit 2
fi
echo "Contract check against $BASE_URL"

UNKNOWN="00000000-0000-4000-8000-000000000000"
MALFORMED="not-a-uuid"
CUSTOMER="C-contract-$(date +%s)-$RANDOM"

# ---- Create account (PDF: output account ID, customer ID, balances; error "Invalid currency")
request POST /accounts "{\"customerId\": \"$CUSTOMER\", \"country\": \"EE\", \"currencies\": [\"EUR\", \"USD\"]}"
expect C01 "create account → 201 with a zero balance per currency" 201 - \
    '.accountId | test("^[0-9a-f-]{36}$")' \
    ".customerId == \"$CUSTOMER\"" \
    '[.balances[].currency] == ["EUR", "USD"]' \
    "$(money '.balances[0].availableAmount' 0.00)" \
    "$(money '.balances[1].availableAmount' 0.00)"
ACCOUNT="$(jq -r '.accountId // empty' <<<"$BODY" 2>/dev/null)"
if [[ -z "$ACCOUNT" ]]; then
    echo "contract-check: no account was created, so the remaining cases can't run" >&2
    exit 1
fi

request POST /accounts '{"customerId": "C-contract", "country": "EE", "currencies": ["JPY"]}'
expect C02 "create account, unsupported currency → 400 INVALID_CURRENCY" 400 INVALID_CURRENCY

# ---- Get account (error "Account not found")
request GET "/accounts/$ACCOUNT"
expect C03 "get account → 200 with ID, customer ID and balances" 200 - \
    ".accountId == \"$ACCOUNT\"" ".customerId == \"$CUSTOMER\"" '[.balances[].currency] == ["EUR", "USD"]'
request GET "/accounts/$UNKNOWN"
expect C04 "get account, unknown ID → 404 ACCOUNT_NOT_FOUND" 404 ACCOUNT_NOT_FOUND
request GET "/accounts/$MALFORMED"
expect C05 "get account, malformed ID → 400 ACCOUNT_NOT_FOUND" 400 ACCOUNT_NOT_FOUND

# ---- Create transaction (PDF output fields; six errors)
tx() { printf '{"amount": %s, "currency": "%s", "direction": "%s", "description": "%s"}' "$@"; }
request POST "/accounts/$ACCOUNT/transactions" "$(tx 100.00 EUR IN Salary)"
expect C06 "IN 100.00 → 201 with every output field and balance after 100.00" 201 - \
    ".accountId == \"$ACCOUNT\"" '.transactionId | test("^[0-9a-f-]{36}$")' \
    "$(money .amount 100.00)" '.currency == "EUR"' '.direction == "IN"' '.description == "Salary"' \
    "$(money .balanceAfter 100.00)"
request POST "/accounts/$ACCOUNT/transactions" "$(tx 30.50 EUR OUT Rent)"
expect C07 "OUT 30.50 → 201 with balance after 69.50" 201 - \
    '.direction == "OUT"' "$(money .balanceAfter 69.50)"

request POST "/accounts/$ACCOUNT/transactions" "$(tx 10.00 JPY IN Bad)"
expect C08 "unsupported currency → 400 INVALID_CURRENCY" 400 INVALID_CURRENCY
request POST "/accounts/$ACCOUNT/transactions" "$(tx 10.00 GBP IN Bad)"
expect C09 "currency the account doesn't hold → 422 INVALID_CURRENCY" 422 INVALID_CURRENCY
request POST "/accounts/$ACCOUNT/transactions" "$(tx 10.00 EUR SIDEWAYS Bad)"
expect C10 "invalid direction → 400 INVALID_DIRECTION" 400 INVALID_DIRECTION
request POST "/accounts/$ACCOUNT/transactions" "$(tx -5.00 EUR IN Bad)"
expect C11 "negative amount → 400 INVALID_AMOUNT" 400 INVALID_AMOUNT
request POST "/accounts/$ACCOUNT/transactions" "$(tx 1000.00 EUR OUT Bad)"
expect C12 "OUT over the balance → 422 INSUFFICIENT_FUNDS" 422 INSUFFICIENT_FUNDS
request POST "/accounts/$UNKNOWN/transactions" "$(tx 10.00 EUR IN Bad)"
expect C13 "unknown account → 404 ACCOUNT_MISSING" 404 ACCOUNT_MISSING
request POST "/accounts/$MALFORMED/transactions" "$(tx 10.00 EUR IN Bad)"
expect C14 "malformed account ID → 400 ACCOUNT_MISSING" 400 ACCOUNT_MISSING
request POST "/accounts/$ACCOUNT/transactions" '{"amount": 10.00, "currency": "EUR", "direction": "IN"}'
expect C15 "no description → 400 DESCRIPTION_MISSING" 400 DESCRIPTION_MISSING

# ---- IN adds and OUT subtracts in that currency; rejected requests changed nothing
request GET "/accounts/$ACCOUNT"
expect C16 "balances after the transactions → EUR 69.50, USD 0.00" 200 - \
    "$(money '(.balances[] | select(.currency == "EUR") | .availableAmount)' 69.50)" \
    "$(money '(.balances[] | select(.currency == "USD") | .availableAmount)' 0.00)"

# ---- Get transactions (PDF output fields; error "Invalid account")
request GET "/accounts/$ACCOUNT/transactions"
expect C17 "list transactions → 200, the two in post order with every output field" 200 - \
    'length == 2' '[.[].direction] == ["IN", "OUT"]' "$(money '.[0].amount' 100.00)" "$(money '.[1].amount' 30.50)" \
    "all(.[]; .accountId == \"$ACCOUNT\" and (.transactionId | type == \"string\") and .currency == \"EUR\" and (.description | type == \"string\"))"
request GET "/accounts/$UNKNOWN/transactions"
expect C18 "list transactions, unknown account → 404 INVALID_ACCOUNT" 404 INVALID_ACCOUNT
request GET "/accounts/$MALFORMED/transactions"
expect C19 "list transactions, malformed ID → 400 INVALID_ACCOUNT" 400 INVALID_ACCOUNT

# ---- Events (PDF: every insert and update is published; design.md §5)
EXPECTED_EVENTS='["account.created","balance.created","balance.created","transaction.created","balance.updated","transaction.created","balance.updated"]'
if [[ -z "$MGMT_URL" ]]; then
    skip C20 "events for the account, in order" "SKIPPED (no mgmt URL)"
else
    # Peek banking.events.all through the management API: ack_requeue_true puts every message back.
    queue="$MGMT_URL/api/queues/%2F/banking.events.all"
    actual="" deadline=$((SECONDS + EVENT_WAIT_SECONDS))
    while :; do
        depth="$(curl -s -u "$MGMT_AUTH" "$queue" | jq -r '.messages // 0' 2>/dev/null || echo 0)"
        if [[ "$depth" -gt 0 ]]; then
            actual="$(curl -s -u "$MGMT_AUTH" -H 'Content-Type: application/json' -X POST "$queue/get" \
                --data "{\"count\": $depth, \"ackmode\": \"ack_requeue_true\", \"encoding\": \"auto\"}" |
                jq -c --arg id "$ACCOUNT" \
                    '[.[] | select((.payload | fromjson? | .accountId) == $id) | .routing_key]' 2>/dev/null)"
        fi
        [[ "$actual" == "$EXPECTED_EVENTS" || $SECONDS -ge $deadline ]] && break
        sleep 1
    done
    if [[ "$actual" == "$EXPECTED_EVENTS" ]]; then
        pass C20 "events for the account, in order (7)"
    else
        fail C20 "events for the account, in order" "got ${actual:-nothing}, expected $EXPECTED_EVENTS"
    fi
fi

echo "$passed passed, $failed failed, $skipped skipped"
[[ "$failed" -eq 0 ]]
