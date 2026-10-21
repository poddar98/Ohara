# 01 — Current-State Assessment

**Repository:** `github.com/poddar98/Ohara` · **Branch assessed:** `phase-3-architecture` (based on `phase-2-user-auth` @ `6c8c967`) · **Assessed:** 2026-10-21

This document records only what the repository actually contains. Recommendations are in later documents. Every finding is tagged **[Fact]** (observed, with source), **[Assumption]** (believed but not verifiable from the repo), or **[Gap]** (missing capability).

---

## 1. Architecture overview

- **[Fact]** One Spring Boot application (`OharaApplication.java`) structured as a **modular monolith** with Spring Modulith (`pom.xml`: `spring-modulith-starter-core`, BOM `2.0.8`).
- **[Fact]** Top-level modules under `org.example.ohara`: `auth`, `user`, `common`. Each uses the layered sub-packages `api / application / domain / infrastructure`.
- **[Fact]** Boundaries are verified by `src/test/java/org/example/ohara/ModularityTests.java` (`ApplicationModules.of(OharaApplication.class).verify()`).
- **[Fact]** `user` publishes two named interfaces: `user.domain` (`@NamedInterface("domain")`) and `user.application` (`@NamedInterface("application")`). `auth` depends on `user` through `UserService` and the `User`/`UserRole` entities.
- **[Fact]** The intended direction is captured in `src/main/resources/initial_prompt.md`: modular monolith (explicitly "not microservices"), planned modules `course, content, enrollment, video, progress, cart, order, payment, bulkimport, notification`, video bytes never routed through Spring Boot, mock adapters for AWS/payments until accounts exist, Kafka/Debezium "later".

## 2. Technology stack and versions

| Concern | Observed | Source |
|---|---|---|
| Language | Java 21 | `pom.xml` `<java.version>21` |
| Framework | Spring Boot **4.0.8** (parent) | `pom.xml` |
| Spring Cloud | 2025.1.3 (only Resilience4j circuit-breaker starter) | `pom.xml` |
| Modularity | Spring Modulith 2.0.8 (core, actuator, observability, test) | `pom.xml` |
| Web | `spring-boot-starter-webmvc` (servlet stack) | `pom.xml` |
| Persistence | Spring Data JPA / Hibernate, `ddl-auto: validate` | `application-local.yml` |
| Database | PostgreSQL 16 (`postgres:16-alpine`) | `docker-compose.yml` |
| Migrations | Flyway (`flyway-core`, `flyway-database-postgresql`), `classpath:db/migration` | `pom.xml`, yml |
| Cache / KV | Redis 7 (compose) + `spring-boot-starter-data-redis` | `docker-compose.yml`, `pom.xml` |
| Security | Spring Security, JJWT 0.12.6 (HS256) | `pom.xml`, `JwtTokenProvider.java` |
| Concurrency | `spring.threads.virtual.enabled: true` | `application-local.yml`, `application-docker.yml`, test properties |
| Build | Maven wrapper, Lombok annotation processor | `pom.xml`, `mvnw` |
| Tests | JUnit (Jupiter 6 per surefire output), Spring Boot test, Modulith test, security test | `pom.xml` |

## 3. Database model (as migrated)

`V1__create_foundation_schema.sql`: `app_metadata (id BIGSERIAL, key_name UNIQUE, value_text, created_at, updated_at)` — placeholder, unused by code.

`V2__create_user_auth_schema.sql`:

| Table | Key columns | Notes |
|---|---|---|
| `users` | `id UUID PK`, `email UNIQUE`, `first_name`, `last_name`, `status VARCHAR(32) DEFAULT 'ACTIVE'`, `created_at`, `updated_at`, `last_login_at` | Emails lower-cased in `AuthService`, not by the DB |
| `user_profile` | `user_id PK/FK`, `display_name`, `first_name`, `last_name` | Duplicates name columns of `users` |
| `user_role` | `id`, `user_id FK`, `name`, `UNIQUE(user_id,name)` | **Global** roles; only `ROLE_USER` is ever written |
| `auth_identity` | `id`, `user_id FK`, `provider`, `identifier`, `verified_at`, `UNIQUE(provider,identifier)` | `provider='local'`; `verified_at` never set |
| `password_credential` | `id`, `auth_identity_id UNIQUE FK`, `encoded_password`, `password_changed_at` | BCrypt hash |
| `refresh_token` | `id`, `user_id FK`, `token_hash UNIQUE`, `expires_at`, `revoked_at`, `created_at` | SHA-256 (base64url) of the JWT |

- **[Fact]** Every FK referencing `users` is `ON DELETE CASCADE`.
- **[Fact]** All timestamps are `TIMESTAMP` (without time zone) mapped to `LocalDateTime`; Hibernate `jdbc.time_zone: UTC` is set in yml.
- **[Fact]** No indexes beyond PK/unique constraints (e.g. none on `refresh_token.user_id`, which `findByUserAndRevokedAtIsNull` queries).

## 4. Authentication and authorization

- **[Fact]** Endpoints (`AuthController`): `POST /api/auth/register`, `/login`, `/refresh`, `/logout`. `UserController`: `GET /api/users/me`. `AppHealthController`: `GET /api/health`. Actuator exposes `health,info`.
- **[Fact]** Passwords: `BCryptPasswordEncoder` (`SecurityConfig`), via `DaoAuthenticationProvider` + `AppUserDetailsService`. Non-`ACTIVE` users are locked/disabled.
- **[Fact]** Access token: JWT HS256, subject = **email**, claims `tokenType=ACCESS`, `roles`; 1 h validity (`jwt.access-token-validity-ms`). Validated per request in `JwtAuthenticationFilter`; no DB lookup per request, so status changes take effect only after access-token expiry.
- **[Fact]** Refresh token: JWT `tokenType=REFRESH`, 7 days, delivered as `HttpOnly; SameSite=Strict; Path=/api/auth` cookie (`Secure` controlled by `auth.refresh-cookie.secure`). Stored hashed; rotated on every refresh; presenting a revoked token revokes all active tokens of that user (`AuthService.refresh`, `@Transactional(noRollbackFor = IllegalArgumentException.class)`). Logout is idempotent.
- **[Fact]** Stateless sessions, CSRF disabled (acceptable for bearer-token APIs; the refresh cookie is `SameSite=Strict` and scoped to `/api/auth`).
- **[Fact]** `@EnableMethodSecurity` is on, but no `@PreAuthorize`/`@Secured` is used. Authorization = "authenticated or not".
- **[Gap]** No email verification, password reset, account deactivation flow, organization/tenant concept, org-scoped roles, invitations, or rate limiting on login.

## 5. Course, enrollment, media, async, integrations

| Capability | Status |
|---|---|
| Course / curriculum / content | **[Gap]** none |
| Enrollment / entitlement / progress | **[Gap]** none |
| Orders / payments (Razorpay, Cashfree per `initial_prompt.md`) | **[Gap]** none — and **not needed**: payments/refunds are handled offline by the contract team via bank transfer (decision 2026-10-21, ADR-012) |
| Media upload / S3 / CloudFront / MediaConvert | **[Gap]** none; no AWS SDK dependency |
| Live streaming | **[Gap]** none |
| AI (transcription, LLM) | **[Gap]** none |
| Async processing / queues / scheduling | **[Gap]** none (no `@Async`, `@Scheduled`, SQS, Kafka) |
| Redis | **[Fact]** dependency + compose service only; **no code uses it** |
| Resilience4j | **[Fact]** dependency only; unused |
| Email / notifications | **[Gap]** none |
| Observability | **[Fact]** Actuator health/info, Modulith observability (runtime); no metrics export, tracing backend or structured logging config |
| Deployment | **[Fact]** compose runs Postgres + Redis only; **[Gap]** no app Dockerfile, no CI, no IaC |

## 6. API conventions observed

- **[Fact]** Paths under `/api/{module}`; JSON records as DTOs (`api/dto`); Bean Validation on requests.
- **[Fact]** Errors: `ApiExceptionHandler` maps `IllegalArgumentException` → **400** `{"message": ...}` and `AuthenticationException` → 401. Not-found, duplicate, and revoked-token cases all surface as 400. No validation-error body shaping (Spring default for `MethodArgumentNotValidException`).

## 7. Tests

- **[Fact]** Unit / slice tests pass without infrastructure: `AuthServiceTest`, `JwtTokenProviderTest`, `AppHealthServiceTest`, `AppHealthControllerTest`, `ModularityTests`.
- **[Fact]** `OharaApplicationTests`, `ApplicationBeansTests`, `AuthFlowIntegrationTest` need Postgres at `localhost:5432/ohara_test` (created by `docker/postgres/init/01-create-test-db.sql`). They fail with "Connection refused" when compose is not running — an environment dependency, not a code defect. **[Gap]** no Testcontainers.

## 8. Technical debt and architectural gaps

| # | Item | Impact | Source |
|---|---|---|---|
| D1 | `ON DELETE CASCADE` from `users` | Deleting a user would erase credentials, tokens and (once added) progress/orders — conflicts with financial and learning-record retention | `V2__...sql` |
| D2 | JWT subject is the email | Email change invalidates identity mapping; leaks PII into every token | `JwtTokenProvider.buildToken` |
| D3 | `IllegalArgumentException` → 400 for everything | Clients can't distinguish 404/409/401/403; unsafe to reuse for authorization failures | `ApiExceptionHandler` |
| D4 | `TIMESTAMP` without time zone + `LocalDateTime.now()` (JVM zone) | Ambiguity for access windows, live schedules, expiry checks across zones | V2, entities |
| D5 | Name duplicated in `users` and `user_profile` | Drift risk | V2 |
| D6 | Dev credentials and JWT secret committed in `application-*.yml` / `application.properties` | Must be externalised before any shared environment (values intentionally not reproduced here) | resources |
| D7 | Prompt documents (`initial_prompt.md`, `Ohara_Claude_Repository_Analysis_Prompt.md`) in `src/main/resources` | Packaged into the runtime jar; belong under `docs/` | resources |
| D8 | No index on `refresh_token(user_id)` | Reuse-detection query scans | V2 |
| D9 | Roles are global only | Cannot express "instructor at College A, learner at College B" | `user_role` |
| D10 | Unused Redis & Resilience4j dependencies | Start-up/health coupling to Redis without benefit | `pom.xml` |
| D11 | Access-token validity 1 h with no revocation check | Suspended users keep access up to 1 h; matters once paid content exists | yml, filter |
| D12 | ITs require a manually started DB | Fragile CI | tests |
| D13 | Login has no throttling / lockout | Credential stuffing | `AuthController` |

## 9. Reuse / modify / new

**Reuse as-is:** Modulith structure and `ModularityTests`; Flyway + `ddl-auto: validate`; refresh-token rotation + reuse detection; BCrypt credential model (`auth_identity` → `password_credential` already allows future OAuth/SSO identities); DTO-record + Bean Validation convention; virtual-thread setting; docker-compose for local infra.

**Modify:** `users` (status values, drop duplicated names later), FK cascade rules, timestamp types → `TIMESTAMPTZ`/`Instant`; `JwtTokenProvider` subject → user UUID and add `ver`/`sid` claims; `ApiExceptionHandler` → RFC 9457 problem details with typed exceptions; `AppUserDetailsService` (statuses); `SecurityConfig` (rate-limit filter, new public routes); `RegisterRequest`/`AuthService.register` (verification state).

**New modules:** `organization` (orgs, memberships, cohorts, invitations, bulk import), `course` (catalog, versions, curriculum), `content` (assets, uploads, renditions), `enrollment` (offerings, enrollments, access grants, progress, `ContentAccessService`), `media` (transcode pipeline, playback signing), `live` (IVS sessions, attendance), `ai` (transcripts, artifacts, Q&A), `notification` (email), `platform` (jobs, outbox, audit) — see `02-target-architecture.md`.

## 10. Assumptions that cannot be verified from the repo

| Assumption | Why it matters |
|---|---|
| AWS will be the cloud (S3, CloudFront, MediaConvert, IVS, SQS) — `initial_prompt.md` says services are "not yet bought" | Provider adapters must start as mocks |
| Primary audience is in India (₹ pricing) | Region choice `ap-south-1`, CDN/IVS India rates, DPDP Act |
| A web SPA and possibly mobile apps will consume the API | Cookie vs header refresh, CORS, signed-cookie domains |
| No existing production data | Migrations V3+ can alter V2 tables without backfill risk |
| B2C pricing basis (subscription vs per-course) undecided | Only affects how long staff grants last (payments are offline) |
| Email delivery provider undecided | Verification/reset/invite flows need an adapter |
