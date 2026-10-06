# Task 6: Docker (`feat/docker`)

> **Status:** done 2026-10-06 (PR #6). Approved with two changes (health without RabbitMQ, a real HTTP healthcheck); outcome at the end.

## Context
This is Stage 3, task 6 of 7 in `docs/sdlc-plan.md`, item 7 in the build order. Success criterion 5
(intent.md) and the definition of done need `docker compose up --build` on a clean clone to start
Postgres, RabbitMQ and the app, with no local Java or Gradle, no config changes, Flyway migrating on
startup, the API on :8080 and Swagger UI working. Today `docker-compose.yml` only has the two
brokers, and the app is run with `./gradlew bootRun`.

Two things stop the app from running in a container. First, `application.yml` points at `localhost`.
Second, it uses RabbitMQ's `guest`, which only works from loopback. The task also carries the
springdoc fixes deferred from the task 3 review. The OpenAPI schema doesn't show the custom
validators' rules (`amount`, `currency`, `direction`, `description` aren't marked required and have
no enums), and the two `create` methods collide as `create_1`.

**Out of scope:** the `NUMERIC(19,2)` overflow 422 (design.md §8, still open in sdlc-plan item 6), and
the README (Stage 6).

## Decisions (approved in chat)
1. **One RabbitMQ user for compose: `banking`/`banking`**, set with `RABBITMQ_DEFAULT_USER`/`_PASS`,
   to match Postgres. Once these are set the broker doesn't create `guest`, so the `application.yml`
   defaults change from `guest` to `banking` and `bootRun` against compose keeps working. The
   management UI login becomes banking/banking. Tests are unaffected, because `@ServiceConnection`
   supplies the container's own credentials.
2. **The app gets its connection settings from env vars in compose**: `SPRING_DATASOURCE_URL`,
   `SPRING_RABBITMQ_HOST`, `SPRING_RABBITMQ_USERNAME`, `SPRING_RABBITMQ_PASSWORD`. The `localhost`
   defaults in `application.yml` stay for `bootRun`.
3. **The app gets a healthcheck through Actuator, and health is app + DB only.** Add
   `spring-boot-starter-actuator` and expose only `health` (the default exposure, made explicit).
   Set `management.health.rabbit.enabled: false`: the app serves without the broker by design
   (ADR-0003, the outbox), so a broker outage must not make it unhealthy. `depends_on rabbitmq:
   service_healthy` stays, for startup order only. With this, `docker compose up --build --wait`
   returns once the API is really up, which the Stage 5 CI smoke test and contract check need.
   - The compose healthcheck does a real HTTP GET of `/actuator/health` over bash's `/dev/tcp` and
     greps for `"status":"UP"`. The Temurin JRE image has no `curl`/`wget`.
   - For the README: this one endpoint acts as both liveness and readiness. Behind an orchestrator
     they would be split (`/actuator/health/liveness` for the process, `/readiness` with the DB).
4. **Dockerfile: multi-stage, with Spring Boot's layered extraction.**
   - Build stage: `eclipse-temurin:25-jdk`. Copy the wrapper and build scripts, then `src`, then
     `./gradlew bootJar --no-daemon`, with a BuildKit cache mount on `/root/.gradle` so rebuilds don't
     download Gradle and dependencies again. `bootJar` doesn't run tests. Testcontainers can't run
     inside `docker build`, and tests belong to `./gradlew check` (design.md §8).
   - Then `java -Djarmode=tools -jar app.jar extract --layers`. That is the layout from the
     Boot 4 reference docs, and it puts dependencies and app classes in separate image layers.
   - Runtime stage: `eclipse-temurin:25-jre`, a non-root user, `EXPOSE 8080`,
     `ENTRYPOINT ["java", "-jar", "app.jar"]`.
5. **Swagger schema: literal `allowableValues`, guarded by a test.** Annotation values must be
   constants, so the enums are written as literals (`{"EUR", "SEK", "GBP", "USD"}`, `{"IN", "OUT"}`).
   A new `OpenApiIT` fails if they drift from `Currency.values()`/`Direction.values()`.
   `@Schema(implementation = Currency.class)` would avoid the duplication, but it changes how the
   field is typed in the schema, so the literals are simpler to reason about.
6. **The host ports stay published** (5432, 5672, 15672, plus 8080 for the app). This keeps the
   `bootRun` dev loop: `docker compose up -d postgres rabbitmq`, then `./gradlew bootRun`.

## Approach

### 1. `Dockerfile` + `.dockerignore` (new)
- `.dockerignore`: `.git`, `.gradle`, `build`, `.idea`, `.claude`, `docs`, `*.md`, `.env`. This keeps
  the build context small, and keeps local build output and secrets out of it.
- `Dockerfile` as in decision 4. The `COPY` order is wrapper → `build.gradle`/`settings.gradle` → `src`,
  so a code change doesn't invalidate the earlier layers.

### 2. `docker-compose.yml`
- `rabbitmq`: `RABBITMQ_DEFAULT_USER: banking`, `RABBITMQ_DEFAULT_PASS: banking`. Update the UI
  comment.
- `app` (new): `build: .`, `ports: "8080:8080"`, the env vars from decision 2,
  `depends_on: {postgres: {condition: service_healthy}, rabbitmq: {condition: service_healthy}}`, and
  a healthcheck that GETs `/actuator/health` and checks for `"status":"UP"`, with a `start_period`.

### 3. Config: `build.gradle`, `application.yml`
- `build.gradle`: `implementation 'org.springframework.boot:spring-boot-starter-actuator'`.
- `application.yml`: the RabbitMQ username and password become `banking`,
  `management.endpoints.web.exposure.include: health`, and `management.health.rabbit.enabled: false`.

### 4. springdoc (`com.danielrak.banking.api`)
- `CreateTransactionRequest`:
  - `amount`: `@Schema(requiredMode = REQUIRED, example = "10.50", description = "> 0, at most 2 decimals")`;
  - `currency`: `@Schema(requiredMode = REQUIRED, allowableValues = {…})`;
  - `direction`: `@Schema(requiredMode = REQUIRED, allowableValues = {"IN", "OUT"})`;
  - `description`: `@Schema(requiredMode = REQUIRED)`.
- `CreateAccountRequest.currencies`: `@ArraySchema(schema = @Schema(allowableValues = {…}), uniqueItems = true)`.
  `customerId` and `country` already show as required and constrained through `@NotBlank`/`@NotNull`/`@Pattern`
  (checked in the generated doc, then fixed only if not).
- `@Operation(operationId = …)`: `createAccount`, `getAccount`, `createTransaction`, `listTransactions`.

### 5. Test: `OpenApiIT` (new, `@IntegrationTest`, uses the existing `RestTestClient`)
- `GET /v3/api-docs`: the four operationIds are present and unique;
  `CreateTransactionRequest.required` holds all four fields; the `currency`/`direction` enums equal
  `Currency.values()`/`Direction.values()`; `CreateAccountRequest.currencies.items.enum` equals
  `Currency.values()`.
- `GET /swagger-ui.html` → a redirect to the UI, followed by 200.
- `GET /actuator/health` → 200 `UP`, and still 200 `UP` while the RabbitMQ container is paused.
  This reuses `EventDeliveryIT`'s pause/unpause through the Docker client, with unpause in `finally`.
  It's cheap, because with the rabbit indicator off nothing calls the broker.

### 6. Docs
- **CLAUDE.md, Commands:** `docker compose up --build` (the full stack);
  `docker compose up -d postgres rabbitmq` + `./gradlew bootRun` for dev; the UI login is banking/banking.
- **design.md:**
  - §1: Actuator health, and compose waiting on the healthchecks;
  - §8: the image is built with `bootJar` (tests don't run), and the stale "planned with task 6" in
    the overflow bullet now points to sdlc-plan.
- **sdlc-plan:** the status row shows tasks 1–6, and item 7 is ✅ with a pointer to this plan.
- **This plan:** copied to `docs/plans/task-6-docker.md`, with an outcome section at the end.

## Files
- New: `Dockerfile`, `.dockerignore`, `src/test/java/com/danielrak/banking/OpenApiIT.java`,
  `docs/plans/task-6-docker.md`
- Changed: `docker-compose.yml`, `build.gradle`, `src/main/resources/application.yml`,
  `api/CreateTransactionRequest.java`, `api/CreateAccountRequest.java`, `api/AccountController.java`,
  `api/TransactionController.java`, `CLAUDE.md`, `docs/design.md`, `docs/sdlc-plan.md`

## Delivery and commits
1. Commit this plan as `docs/plans/task-6-docker.md`.
2. Steps 1–5, then `./gradlew check` and the compose verification below.
3. Commit the feature, the test and the docs.
4. `/code-review low` on `Dockerfile`, `.dockerignore`, `docker-compose.yml`, `application.yml` and the
   `build.gradle` diff only. **Show its findings and wait before fixing or committing.** Then fix,
   check, and commit.
5. Push and open the PR, then update sdlc-plan with the PR number, and commit and push that.

## Risks
- **The existing local stack:** `RABBITMQ_DEFAULT_USER` only applies when the broker's node database
  is created for the first time. Recreating the container gives it a new hostname and so a fresh
  node, but if `guest` still appears, `docker compose down -v` resets it. A clean clone is unaffected.
- **Host ports in use** (e.g. a local Postgres on 5432) would make `up` fail. That's accepted for
  now and noted for the README.
- **The first `docker build` downloads Gradle and every dependency** (a few minutes). The cache
  mount makes later builds fast.

## Verification
- `./gradlew check` is green, with the coverage gate.
- **Clean clone:** commit, then `git clone` the branch into the job's tmp dir, stop the dev stack,
  and in the clone run `docker compose up --build --wait`. Then check:
  - all three services are healthy, and the app log shows Flyway applying V1, V2…;
  - `docker compose stop rabbitmq` → the app stays healthy and still serves GETs; then start it again;
  - `curl` create account → 201, post transaction → 201, GET transactions → 200, and an unknown
    account → 404;
  - `/swagger-ui.html` → 200, and `/v3/api-docs` shows the required fields, the enums and the four
    operationIds;
  - the RabbitMQ management API (banking/banking) shows the events on the demo queue;
  - `docker compose down -v`.

## Outcome
- **Built:** steps 1–6 as planned, with these deviations:
  - **Layered extraction without `--launcher`.** The default layout (`app.jar` + `lib/`) is the one
    `java -jar app.jar` runs. The image is 579 MB and runs as uid 999 `banking`, with the app files
    owned by root (read-only to it).
  - **The broker-paused health check was replaced** by a registry check (`leavesTheBrokerOutOfHealth`:
    a `db` contributor and no `rabbit` one). With the rabbit indicator switched back on through an env
    var, the paused-broker test still passed. `RabbitHealthIndicator` only reads
    `getServerProperties()` from the cached connection, so a paused broker stays UP and the test
    proved nothing. The registry check fails with the indicator on and passes with it off.
- **`./gradlew check`:** 195 tests, 0 failed, 0 skipped. Coverage: lines 0.95, branches 0.868.
- **Clean clone** (`git clone --branch feat/docker`, `docker compose up --build --wait`):
  - all three services healthy in 18 s (with a warm BuildKit cache);
  - Flyway applied V1 and V2 to an empty schema, and the app started in 1.4 s;
  - create account → 201, IN → 201, an OUT over the balance → 422 `INSUFFICIENT_FUNDS`,
    list → 200, an unknown account → 404, `/swagger-ui.html` → 200;
  - `/v3/api-docs` has the four operationIds, `CreateTransactionRequest` requires all four fields
    with the currency and direction enums, and `currencies` has the item enum with `uniqueItems`;
  - the demo queue held the 5 events, readable as banking/banking; `guest` → 401;
  - `docker compose stop rabbitmq`: the app stayed healthy, created an account and served GETs.
    After `start`, that account's `account.created` and `balance.created` arrived.
- **Dev stack:** recreated with the new user (banking 200, guest 401). The risk about `guest`
  surviving didn't happen.
