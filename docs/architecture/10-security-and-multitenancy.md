# 10 — Security and Multitenancy

Status: **Proposed** · 2026-10-21

## 1. Tenancy model

**Shared database, shared schema, tenant column** (`organization_id` / `owner_organization_id`) — ADR-002. Rationale: 5–tens of tenants, shared catalog content across tenants (one course offered to many colleges), one learner across many tenants. Separate databases/schemas per college would duplicate content and break the single-identity model.

Enforcement layers:
1. **Request actor context** — `CurrentActor{userId, platformRoles, status}` from the JWT; org memberships loaded lazily per request (cached 60 s, invalidated by membership events).
2. **Service-level authorization** — `OrgAuthorizationService.require(actor, orgId, Permission)` for admin operations; `ContentAccessService.check(...)` for learning resources (05 §6). Controllers call exactly one of these before any read of tenant data.
3. **Tenant-scoped repositories** — repository methods for tenant tables take `organizationId` as a required parameter (`findByIdAndOrganizationId`); unscoped `findById` on tenant tables is not exposed outside the module (Modulith boundaries + ArchUnit rule).
4. **Tests** — cross-tenant test suite (§9).
5. **Optional DB hardening (later)** — Postgres RLS on tenant tables with `SET LOCAL app.actor_org_ids = '{…}'` per transaction; deferred because B2C rows have `NULL` org and staff/platform paths need bypass, which adds complexity. Trigger: first security review finding or > 20 tenants.

## 2. Roles and permissions

Roles: platform (`ROLE_PLATFORM_ADMIN`, `ROLE_PLATFORM_SUPPORT`, `ROLE_USER`) on `user_role`; org-scoped (`ORG_ADMIN`, `INSTRUCTOR`, `LEARNER`) on `organization_membership`; course-scoped (`OWNER`, `INSTRUCTOR`, `TA`) on `course_instructor`. Permissions are code constants mapped from roles (no permission tables until custom roles are a requirement).

| Permission | Platform admin | Platform support | Org admin | Instructor | Learner |
|---|---|---|---|---|---|
| ORG_CREATE / ORG_SUSPEND | ✔ | | | | |
| ORG_SETTINGS_EDIT | ✔ | | own | | |
| MEMBER_MANAGE / INVITE / IMPORT | ✔ | read | own | | |
| COHORT_MANAGE | ✔ | read | own | | |
| OFFERING_MANAGE | ✔ (B2C + any) | | own org | | |
| COURSE_CREATE | ✔ (platform) | | own org | own org (becomes OWNER) | |
| COURSE_EDIT / PUBLISH | ✔ | | own org courses | courses where OWNER/INSTRUCTOR (publish: OWNER) | |
| ASSET_UPLOAD | ✔ | | own org | own org | |
| ASSET_RETIRE / REPROCESS | ✔ | ✔ | own org | | |
| LIVE_SCHEDULE / HOST | ✔ | | own org | own offerings | |
| REPORT_VIEW | ✔ | ✔ | own org | own cohorts/courses | own data |
| AI_ARTIFACT_APPROVE | ✔ | | own org | own courses | |
| ENROLLMENT_GRANT / REVOKE | ✔ | | own org offerings | | |
| B2C_ACCESS_GRANT / REVOKE (offline payments) | ✔ | | | | |
| IMPERSONATE (support) | ✔ (audited, read-only) | ✔ (audited, read-only) | | | |
| LEARN | ✔ | ✔ | ✔ | ✔ | ✔ |

`ROLE_ENROLLMENT_MANAGER` (contract team) holds only `B2C_ACCESS_GRANT / REVOKE` and the reconciliation report; it cannot see course content admin, org data or learner progress beyond enrollment status.

Invariants: an org must keep ≥ 1 ORG_ADMIN; an ORG_ADMIN cannot grant platform roles; instructors can't see learners outside cohorts linked to their offerings.

## 3. Authentication hardening (changes to existing code)

| Item | Change |
|---|---|
| Password hashing | Keep BCrypt (existing); set strength 12 explicitly; enforce max length 128 (BCrypt truncates at 72 bytes — reject > 72 bytes UTF-8 or pre-hash with SHA-256 per Spring's guidance). Consider Argon2id later via `DelegatingPasswordEncoder` (transparent upgrade on login). |
| Password policy | Min 8, block top-10k breached passwords list; no composition rules (NIST 800-63B). |
| JWT | `sub`=userId, `ver`, `scope`; 15 min TTL; HS256 secret from Secrets Manager (≥ 256-bit); plan migration to asymmetric (ES256) if other services ever verify tokens. |
| Refresh tokens | Existing rotation + reuse detection kept; add `family_id`, device metadata; purge job. |
| Lockout & throttling | §6. |
| CSRF | Stateless bearer APIs stay CSRF-exempt; refresh endpoint protected by `SameSite=Strict` cookie + `Origin` header check (add). |
| CORS | Allow-list of app origins only; credentials allowed for `/api/auth/*`. |
| Dev credentials in repo (D6) | Remove `spring.security.user.*` (unused once `AppUserDetailsService` exists), move DB password/JWT secret to env vars with no defaults in non-local profiles; rotate anything that was ever committed. |

## 4. IDOR and cross-tenant protection

Rule: **every endpoint that accepts an id resolves it through an authorization-aware lookup.** Patterns:

```java
// Admin path
Cohort cohort = cohortRepository.findByIdAndOrganizationId(cohortId, orgId)
    .orElseThrow(NotFoundException::new);         // 404 even if it exists in another org
orgAuthorization.require(actor, orgId, COHORT_MANAGE);

// Learner path
AccessDecision d = contentAccess.check(actor, ResourceRef.lessonContent(contentId), PLAY);
if (d.denied()) throw d.toException();             // 404 for cross-tenant, 403 for not-entitled
```

- Path consistency checks: `/api/orgs/{orgId}/cohorts/{cohortId}/members/{userId}` verifies cohort ∈ org **and** user has a membership in org.
- Nested ids in bodies (e.g. `contentAssetId` when attaching to a lesson) are checked too: the asset's owner org must equal the course's owner org (or be platform-owned and licensed).
- Enumeration resistance: UUIDs help but are **not** relied upon.
- Every cross-tenant DENY → `audit_event ACCESS_DENIED_CROSS_TENANT` + metric (09 §8).

## 5. Media security

| Control | Detail |
|---|---|
| Buckets | Private, Block Public Access, bucket policies deny non-TLS; source bucket readable only by app roles + MediaConvert role; delivery bucket readable only by the CloudFront distribution's OAC. |
| Presigned upload | Server-generated key; ≤ 15 min; signed `Content-Length`/checksum; one key per upload; IAM role with `s3:PutObject` limited to `org/*/asset/*/source/*`. |
| Presigned download (PDF, import reports) | ≤ 60 s, single object, issued only after `ContentAccessService` DOWNLOAD/permission check; `Content-Disposition: attachment`. |
| CDN | Signed cookies with custom policy scoped to `/org/{org}/asset/{asset}/g{gen}/*` and expiry ≤ min(20 min, grant end); key group rotation every 90 days; WAF (AWS managed rules + rate rule) on the distribution; geo restriction optional (Q-13). |
| Revocation | Bounded by cookie TTL; emergency: regenerate path (bump generation) or rotate key group. |
| Live | Private IVS channels with ES384 playback-key-signed JWTs (exp ≤ session end + 30 min); stream keys only to hosts, rotated per session; chat tokens with per-role capabilities. |
| Limits | Watermarking / DRM discussed in 06 §6 and Q-09. |

## 6. Rate limiting & abuse

*Implemented 2026-10-22: per-IP token buckets on public auth and invitation endpoints (08 §1.6). Single-node in-memory; moves to Redis with ≥ 2 API nodes. Behind the ALB set `server.forward-headers-strategy=native` so limits key on the real client IP.*

- Edge: CloudFront/ALB + AWS WAF rate-based rules on `/api/auth/*` (e.g. 100 req/5 min/IP).
- App: Bucket4j-style token buckets keyed by IP and user (08 §1.6). Store in-process for a single API node; **Redis** becomes justified when ≥ 2 API nodes must share counters — this is the first concrete Redis use-case (and the existing dependency can serve it).
- Account lockout on credential failures (04 §3.3); CAPTCHA after repeated failures (provider TBD).
- B2C account sharing: concurrent playback session cap (06 §6), device list in account settings.

## 7. Provider credentials

- **No payment integration:** payments/refunds are offline (ADR-012), so there are no gateway keys or payment webhooks to protect. The risk moves to **staff grants**: dedicated role, mandatory `externalReference`, Idempotency-Key, full audit, daily reconciliation export, alert on unusual grant volume, and MFA for staff accounts (Q-18 follow-up).
- **AWS events** arrive via EventBridge → SQS with IAM — no public HTTP endpoint to protect.
- **Secrets:** AWS Secrets Manager (JWT secret, CloudFront private key, IVS playback private key, LLM/STT API keys). Loaded at start-up into memory; never logged; rotation runbooks. IAM roles per role (api, worker) with least privilege; no long-lived AWS keys in config.
- **LLM data handling:** send only transcript text (no PII), use provider settings that disable training on inputs; record consent type `AI_PROCESSING` where required by org contract.

## 8. Audit, retention and privacy

**Audited actions** (`audit_event`): authentication anomalies (lockouts, reuse detection), role/membership changes, invitations, imports, offering open/close, grant/extend/revoke (with contract/bank reference), course publish/archive, asset retire/delete/reprocess, live session ingest-key views, refunds, admin impersonation, data exports, account deactivation/deletion, cross-tenant denials.

**Retention schedule (initial; confirm with legal — Q-08):**
| Data | Retention |
|---|---|
| Access grants incl. contract/bank references | permanent (finance reconciliation; payment records themselves live with the contract team) |
| Enrollments, progress, attendance | Life of the platform (anonymised with the user) |
| Audit events | 2 years |
| Refresh tokens | expiry + 30 days |
| One-time tokens | expiry + 7 days |
| Bulk import rows | 90 days |
| AI Q&A logs | 90 days |
| Source videos | while referenced by any version; retired → 1 year in archive tier, then delete (unless contract says otherwise) |
| Application logs | 30 days hot, 90 days archive; no tokens/PII in logs (mask emails) |

**Privacy (India DPDP Act 2023 — Q-07):** consent records, purpose limitation (marketing separate), data principal rights (access/export: `GET /api/users/me/export` async job; deletion: anonymization flow), breach response runbook, data stored in `ap-south-1`. Minors: some college users may be < 18 → verifiable parental consent requirements must be assessed.

## 9. Security testing

- **Tenant-isolation test suite:** fixture with two orgs, each with cohorts, courses, assets, sessions; for every endpoint in 08, a parameterised test calls it as a user of org A with ids from org B and asserts 404/403 and no data in body. Runs in CI.
- **Entitlement tests:** expired grant, revoked grant, wrong version, preview-only, suspended org, removed cohort member, grant revoked for offline refund → playback/download/join/Q&A all denied.
- **Staff-grant tests:** only `ROLE_ENROLLMENT_MANAGER`/platform admin can grant; missing reference rejected; duplicate submit idempotent; revoke blocks playback on next refresh.
- Dependency scanning (OWASP Dependency-Check/GitHub Dependabot), secret scanning on the repo, ZAP baseline against staging, a pre-launch external penetration test.
