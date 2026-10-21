# 08 — API Specification

Status: **Proposed** · 2026-10-21 · Extends the existing `/api/...` REST conventions (`AuthController`, `UserController`). Existing endpoints are marked **[exists]**; changes to them are noted.

## 1. Conventions

### 1.1 Transport & auth
- JSON over HTTPS, `/api` prefix, resource-oriented paths, camelCase JSON, Java records as DTOs with Bean Validation (as today).
- **Access token**: `Authorization: Bearer <jwt>` (15 min). **Refresh**: HttpOnly cookie `refresh_token`, `Path=/api/auth`, `SameSite=Strict` **[exists]**.
- Unless stated, endpoints require an authenticated `ACTIVE` user. `scope=unverified` tokens may call only `/api/auth/*`, `GET /api/users/me`, catalog reads and preview playback.
- Authorization is done in services (`ContentAccessService`, `OrgAuthorizationService`), not by URL patterns alone. Every path containing an id is checked against the actor — ids are never trusted as authorization (10 §4).

### 1.2 Errors — RFC 9457 Problem Details (replaces `{"message"}` from `ApiExceptionHandler`)
```json
{ "type": "https://errors.ohara.example/invitation-expired",
  "title": "Invitation expired", "status": 410,
  "code": "invitation_expired", "detail": "Ask your administrator to resend the invitation.",
  "instance": "/api/invitations/…/accept", "traceId": "…",
  "errors": [ { "field": "email", "code": "invalid_email" } ] }
```
Typed exceptions map to statuses: `ValidationException` 400 / 422 (semantic), `UnauthenticatedException` 401, `ForbiddenException` 403, `NotFoundException` 404, `ConflictException` 409, `GoneException` 410, `PreconditionFailedException` 412, `RateLimitedException` 429 (+`Retry-After`). **Cross-tenant access returns 404** (don't reveal existence); same-tenant insufficient role returns 403.

### 1.3 Pagination & filtering
Cursor pagination: `?limit=50&cursor=<opaque>`; response `{ "items": [...], "nextCursor": "…" }`. Max `limit` 200. Filters as query params (`status`, `cohortId`, `q`). Sorting fixed per endpoint (documented) to keep index usage predictable.

### 1.4 Concurrency
Mutable admin resources return `ETag` (`version` column); updates require `If-Match` → 412 on mismatch.

### 1.5 Idempotency
`Idempotency-Key` header (UUID) **required** on: staff access grants/extensions/revocations, asset/upload initiation, bulk import submit, offering open, invitation create (bulk). Stored in `idempotency_key` (24 h): same key + same body hash → replay stored response; same key + different body → 422 `idempotency_key_reused`.

### 1.6 Rate limits (per IP + per user; 429 with `Retry-After`)
| Group | Limit |
|---|---|
| login, register, forgot-password, verify resend | 5/min/IP, 10/hour/email |
| invitations accept/lookup | 20/min/IP |
| playback session create/refresh | 60/min/user |
| progress heartbeats | 10/min/user/lesson |
| AI Q&A | 30/day/user, org budget |
| admin bulk endpoints | 10/min/user |

### 1.7 Audit
Endpoints marked **(A)** write `audit_event`.

---

## 2. Identity & account

| Method & path | Purpose | Authz | Request | Response | Errors | Notes |
|---|---|---|---|---|---|---|
| `POST /api/auth/register` **[exists – changed]** | B2C sign-up | public | `RegisterRequest{firstName, lastName, email, password(8–72), consents[{type, version}] (consents: later slice)}` | 202 `{status:"VERIFICATION_REQUIRED", email}` — no tokens, no cookie (identical for existing emails) | 400 validation; 429 | Enumeration-safe: existing email → same 202 + notice email (04 §3.1) |
| `POST /api/auth/verify-email` | Verify email | public | `{token}` | 200 `AuthResponse` (full scope) + cookie | 400 invalid; 410 expired/consumed | Idempotent for same token within 5 min |
| `POST /api/auth/verify-email/resend` | Resend | public or unverified | `{email?}` | 202 | 429 | Always 202 |
| `POST /api/auth/login` **[exists – changed]** | Login | public | `LoginRequest{email, password}` | 200 `AuthResponse{accessToken, tokenType, email, userId, scope}` + cookie | 401 generic; 403 `account_suspended`/`account_deactivated`(+`reactivationToken`); 429 lockout | (A) on failure bursts |
| `POST /api/auth/refresh` **[exists]** | Rotate tokens | refresh cookie | — | 200 `AuthResponse` + new cookie | 401 invalid/expired/reused | Reuse → revoke all sessions (current behaviour) |
| `POST /api/auth/logout` **[exists]** | Logout | cookie | — | 204, cookie cleared | — | Idempotent |
| `POST /api/auth/logout-all` | Revoke all sessions | auth | — | 204 | — | bumps `auth_version` (A) |
| `POST /api/auth/forgot-password` | Request reset | public | `{email}` | 202 | 429 | Always 202 |
| `POST /api/auth/reset-password` | Reset | public | `{token, newPassword}` | 204 | 400; 410 | Revokes sessions (A) |
| `POST /api/auth/reactivate` | Reactivate | public | `{reactivationToken}` | 202 (email confirm) | 410 | |
| `GET /api/users/me` **[exists – changed]** | Current user | auth (any scope) | — | `UserResponse{id, email, emailVerified, status, profile{displayName, firstName, lastName, locale, timeZone}, platformRoles[]}` | — | Resolve by `sub`=userId |
| `PATCH /api/users/me` | Update profile | auth | `{displayName?, firstName?, lastName?, locale?, timeZone?, phone?}` (JSON merge) | 200 `UserResponse` | 400 | |
| `POST /api/users/me/password` | Change password | auth | `{currentPassword, newPassword}` | 204 | 400; 401 wrong current | revokes other sessions (A) |
| `POST /api/users/me/email` | Start email change | auth | `{newEmail, password}` | 202 | 400; 401 | (A) |
| `POST /api/users/me/email/confirm` | Confirm change | public | `{token}` | 204 | 410 | (A) |
| `POST /api/users/me/deactivate` | Deactivate | auth | `{password}` | 204 | 401 | (A) |
| `POST /api/users/me/deletion-request` | Request deletion | auth | `{password}` | 202 `{scheduledFor}` | 409 legal hold | (A) |
| `GET /api/users/me/organizations` | My affiliations | auth | — | `[{organizationId, name, roles[], status, cohorts[{id, name, status}]}]` | — | |
| `GET /api/users/me/consents` / `POST` | View/change consents | auth | `{type, granted}` | list | — | append-only |

## 3. Organizations, members, cohorts, invitations, imports

All `/api/orgs/{orgId}/**` require `ORG_ADMIN` of `orgId` (or platform admin) unless noted. Cross-org id → 404.

| Method & path | Purpose | Request | Response | Errors / notes |
|---|---|---|---|---|
| `POST /api/admin/organizations` | Create org (platform admin) | `{name, slug, type, allowedEmailDomains?, settings?}` | 201 `OrganizationResponse` | 409 slug (A) |
| `GET /api/admin/organizations` | List orgs | `?status&q&cursor` | page | platform admin |
| `GET /api/orgs/{orgId}` / `PATCH` | View/update org | settings subset | `OrganizationResponse` + ETag | 412 (A on PATCH) |
| `GET /api/orgs/{orgId}/members` | List members | `?role&status&q&cursor` | page `MemberResponse{userId, name, email, roles[], status, externalRef, cohorts[]}` | |
| `POST /api/orgs/{orgId}/members` | Add single member | `{email, firstName, lastName, role, cohortId?, externalRef?, mode: INVITE\|CREATE_ACCOUNT}` | 201 `{membershipId?|invitationId}` | 409 already member (A) |
| `PATCH /api/orgs/{orgId}/members/{userId}` | Change role/status | `{roles?, status?(SUSPENDED/ACTIVE/REMOVED), externalRef?}` | 200 | cannot remove last ORG_ADMIN → 409 (A) |
| `POST /api/orgs/{orgId}/invitations` | Invite | `{email, role, cohortId?}` | 201 `InvitationResponse{id, email, role, status, expiresAt}` | dedup to existing PENDING (A) |
| `GET /api/orgs/{orgId}/invitations` | List | `?status&cursor` | page | |
| `POST /api/orgs/{orgId}/invitations/{id}/resend` | Resend | — | 202 | 409 if not PENDING; 5/day |
| `POST /api/orgs/{orgId}/invitations/{id}/revoke` | Revoke | — | 204 | (A) |
| `GET /api/invitations/{token}` | Lookup (invitee) | public | `{organizationName, role, cohortName?, emailMasked, accountExists, expiresAt}` | 404; 410 expired/revoked |
| `POST /api/invitations/{token}/accept` | Accept | public (new account: `{firstName, lastName, password}`) or auth (existing) | 200 `AuthResponse` + memberships | 409 email mismatch; 410; 403 suspended (A) |
| `POST /api/orgs/{orgId}/imports` | Start import | `Idempotency-Key`; `{fileName, bytes, sha256, mode, cohortId?}` | 201 `{importId, uploadUrl(presigned PUT, 15 min)}` | 409 same file already imported; 413 > 5 MB |
| `POST /api/orgs/{orgId}/imports/{id}/submit` | Validate | — | 202 | |
| `GET /api/orgs/{orgId}/imports/{id}` | Status/summary | — | `{status, totalRows, succeeded, failed, preview{toInvite, toLink, duplicates, errors}}` | poll every 2–5 s |
| `POST /api/orgs/{orgId}/imports/{id}/confirm` | Process | — | 202 | 409 not VALIDATED (A) |
| `POST /api/orgs/{orgId}/imports/{id}/cancel` | Cancel | — | 202 | rows processed so far stay |
| `GET /api/orgs/{orgId}/imports/{id}/rows` | Row results | `?status&cursor` | page | |
| `GET /api/orgs/{orgId}/imports/{id}/error-report` | Error CSV | — | 302 to 60 s presigned GET | |
| `POST /api/orgs/{orgId}/cohorts` | Create cohort | `{name, code, startsOn, endsOn}` | 201 | 409 code (A) |
| `GET /api/orgs/{orgId}/cohorts` / `GET …/{cohortId}` / `PATCH` | Manage | | | ETag |
| `GET /api/orgs/{orgId}/cohorts/{cohortId}/members` | Members | `?status&cursor` | page | |
| `POST /api/orgs/{orgId}/cohorts/{cohortId}/members` | Add existing org members | `{userIds[≤500]}` | 200 `{added, alreadyMember, notOrgMember[]}` | idempotent (A) |
| `DELETE /api/orgs/{orgId}/cohorts/{cohortId}/members/{userId}` | Remove (soft) | `?reason` | 204 | sets `left_at`, revokes grants (A) |
| `POST /api/orgs/{orgId}/cohorts/{cohortId}/members/{userId}/transfer` | Transfer | `{toCohortId, copyProgress: bool}` | 200 | 409 target not in org (A) |
| `GET /api/orgs/{orgId}/reports/{enrollment\|progress\|attendance}` | Reports | `?cohortId&courseId&from&to&format=json\|csv` | page or 202 `{exportJobId}` for CSV | instructors: own cohorts only |

## 4. Catalog & curriculum

| Method & path | Purpose | Authz | Request / Response | Notes |
|---|---|---|---|---|
| `GET /api/catalog/courses` | B2C discovery | public | `?q&category&level&language&cursor` → page `CourseCard{id, slug, title, subtitle, thumbnailUrl, priceMinor, currency, offeringId, durationMinutes}` | only PUBLIC courses with OPEN B2C offering; cached 60 s |
| `GET /api/catalog/courses/{slug}` | Course landing | public | `CourseDetail{…, curriculum outline (titles, durations, isPreview), instructors}` | no content URLs |
| `GET /api/me/courses` | My learning | auth | `[{enrollmentId, course, status, progressPercent, accessUntil, channel}]` | |
| `GET /api/me/continue-watching` | Resume list | auth | `[{lessonKey, lessonId, title, positionMs, durationMs, enrollmentId}]` | |
| `GET /api/enrollments/{enrollmentId}/curriculum` | Curriculum of pinned version | owner of enrollment | modules → lessons → contents with `locked`, `progress`, `mediaStatus` | uses `checkMany` |
| `POST /api/courses` | Create course | platform admin, org admin, or instructor in org | `{title, slug, ownerOrganizationId?, categoryId?, visibility}` → 201 `CourseResponse` (creates draft v1) | (A) |
| `GET /api/courses/{courseId}` / `PATCH` | Manage course | course staff | ETag | |
| `GET /api/courses/{courseId}/versions` | Versions | course staff | `[{id, versionNumber, status, publishedAt, activeEnrollments}]` | |
| `POST /api/courses/{courseId}/versions` | Create draft from latest published | course OWNER | 201 `{versionId}` | 409 draft exists |
| `GET /api/courses/{courseId}/versions/{versionId}` | Full curriculum tree | staff | tree | |
| `PATCH /api/courses/{courseId}/versions/{versionId}` | Edit metadata | staff, DRAFT only | `If-Match` | 409 if PUBLISHED |
| `POST /api/courses/{courseId}/versions/{versionId}/publish` | Publish | OWNER/org admin | `{changeNotes, migrateActiveEnrollments: bool}` → 200 | 422 with checklist failures; Idempotency-Key (A) |
| `POST /api/courses/{courseId}/versions/{versionId}/archive` | Archive | OWNER | 409 if active enrollments | (A) |
| `POST …/versions/{versionId}/modules` / `PATCH …/modules/{moduleId}` / `DELETE` | Modules | staff, DRAFT | `{title, position}` | |
| `PUT …/versions/{versionId}/modules/order` | Reorder modules | staff, DRAFT | `{moduleIds[]}` | one transaction |
| `POST …/modules/{moduleId}/lessons` / `PATCH …/lessons/{lessonId}` / `DELETE` | Lessons | staff, DRAFT | `{title, type, isPreview, position}` | |
| `PUT …/modules/{moduleId}/lessons/order` | Reorder lessons | staff, DRAFT | `{lessonIds[]}` | |
| `POST …/lessons/{lessonId}/contents` | Attach content | staff, DRAFT; asset owner org == course owner org (or platform) | `{kind, contentAssetId?, externalUrl?, titleOverride?, isRequired, settings?}` | 422 asset not usable |
| `PATCH/DELETE …/lessons/{lessonId}/contents/{contentId}` | Edit/detach | staff, DRAFT | | asset untouched |
| `POST /api/courses/{courseId}/instructors` / `DELETE …/{userId}` | Manage instructors | OWNER/org admin | `{userId, role}` | user must be INSTRUCTOR member of owner org (A) |

## 5. Enrollment, entitlements, progress, staff access grants

| Method & path | Purpose | Authz | Request / Response | Errors / idempotency |
|---|---|---|---|---|
| `POST /api/orgs/{orgId}/offerings` | Create B2B offering | ORG_ADMIN; course licensed/owned | `{courseId, cohortId?, startsAt, endsAt, versionPolicy, pinnedVersionId?, seatLimit?}` → 201 | 409 duplicate cohort offering (A) |
| `GET /api/orgs/{orgId}/offerings` / `PATCH …/{id}` | Manage | ORG_ADMIN | window changes propagate to grants | ETag (A) |
| `POST /api/offerings/{id}/open` / `close` | Open/close | ORG_ADMIN or platform admin (B2C) | 202 (enrollment fan-out async) | Idempotency-Key (A) |
| `POST /api/admin/offerings` | Create B2C offering | platform admin | `{courseId, listPriceMinor?, currency?, accessDurationDays?}` (price is display-only) | (A) |
| `GET /api/enrollments/{id}` | Enrollment detail | owner, org admin of org, platform admin | `{status, courseVersionId, grants[{source, validFrom, validUntil, revoked}], progressPercent}` | |
| `POST /api/orgs/{orgId}/enrollments/{id}/revoke` | Revoke B2B access | ORG_ADMIN | `{reason}` | revokes grants (A) |
| `POST /api/admin/enrollments/{id}/grants` | Admin grant/extend | platform admin, ORG_ADMIN for own offerings | `{validUntil, reason}` → 201 grant | (A) |
| `POST /api/enrollments/{id}/migrate-version` | Switch to latest published | owner (if offering allows) or ORG_ADMIN | `{targetVersionId}` | 409 not allowed (A) |
| `PUT /api/enrollments/{id}/lessons/{lessonKey}/progress` | Heartbeat / position | owner, active grant | `{positionMs, durationMs, watchedDeltaMs, clientTs}` → 204 | last-write-wins by `clientTs`; rate-limited |
| `POST /api/enrollments/{id}/lessons/{lessonKey}/complete` | Mark reading done | owner | 204 | idempotent |
| `GET /api/enrollments/{id}/progress` | Progress summary | owner/staff | per lesson | |
| `POST /api/admin/offerings/{offeringId}/grants` | Grant B2C access after offline payment | `ROLE_ENROLLMENT_MANAGER` or platform admin | **Idempotency-Key**; `{email, firstName, lastName, externalReference (required), validUntil?, note?}` → 201 `{enrollmentId, grantId, userCreated, referenceAlreadyUsed[]}` | 409 `already_has_access`; 422 offering not B2C/OPEN; creates account + set-password email if none (A) |
| `POST /api/admin/grants/{grantId}/extend` | Extend after offline renewal | same | `{validUntil, externalReference}` → 201 new grant | Idempotency-Key (A) |
| `POST /api/admin/grants/{grantId}/revoke` | Revoke (refund done offline, contract ended, mistake) | same; ORG_ADMIN for own B2B offerings | `{reason: REFUNDED_OFFLINE\|CONTRACT_ENDED\|ADMIN, externalReference?, note?}` → 200 | idempotent (A) |
| `GET /api/admin/access-grants` | Reconciliation report | same + finance read | `?source=CONTRACT&offeringId&from&to&format=json\|csv` → grants with reference, staff, window, revocation | export for finance |
| `POST /api/admin/imports` | Bulk grant (mode `GRANT_ACCESS`) | same | `{offeringId, fileName, bytes, sha256}` → presigned upload; then submit/confirm as in §3 | CSV columns: email, firstName, lastName, externalReference, validUntil? |

## 6. Assets, uploads, playback

| Method & path | Purpose | Authz | Request / Response | Notes |
|---|---|---|---|---|
| `POST /api/assets` | Create asset + start upload | instructor/org admin of `ownerOrganizationId` (or platform) | **Idempotency-Key**; `{type, title, ownerOrganizationId?, fileName, bytes, mime, sha256?, contentProfile?}` → 201 `{assetId, uploadId, partSizeBytes, expiresAt, singlePutUrl?}` | 413 limits; 415 type (A) |
| `POST /api/uploads/{uploadId}/parts:presign` | Part URLs | upload initiator | `{partNumbers[≤100]}` → `[{partNumber, url, expiresAt}]` | 410 expired |
| `GET /api/uploads/{uploadId}` | Resume info | initiator | `{status, uploadedParts[{n, etag, size}]}` | |
| `POST /api/uploads/{uploadId}/complete` | Complete | initiator | `{parts[{partNumber, etag, checksumSha256}]}` → 200 `AssetResponse` | idempotent; 422 checksum/size mismatch |
| `POST /api/uploads/{uploadId}/abort` | Abort | initiator | 204 | |
| `GET /api/assets` | Asset library | org staff | `?ownerOrganizationId&type&status&q&cursor` | tenant-scoped |
| `GET /api/assets/{assetId}` | Status | staff of owner org | `AssetResponse{id, type, title, mediaStatus, durationMs, renditions[], aiStatus{…}, usedIn[{courseId, versionId, lessonId}], failure?}` | learners use lesson endpoints instead |
| `POST /api/assets/{assetId}/reprocess` | Retry/regenerate media | org admin/platform admin | `{reason}` → 202 | new generation (A) |
| `POST /api/assets/{assetId}/retire` | Retire | org admin/platform admin | `{replacementAssetId?, reason}` | (A) |
| `DELETE /api/assets/{assetId}` | Delete unused asset | owner staff | 204 | 409 `asset_in_use` (A) |
| `POST /api/playback/sessions` | Authorize playback | `ContentAccessService PLAY` | `{lessonId, contentId}` → 200 + CloudFront signed cookies; body `{sessionId, masterPlaylistUrl, expiresAt, refreshAt, resumePositionMs, captions[{lang, url}], chapters[]}` | 403 `not_entitled`/`access_expired`; 409 `media_not_ready`; never returns S3 URLs |
| `POST /api/playback/sessions/{id}/refresh` | Re-authorize | session owner | 200 new cookies | 403 if revoked |
| `POST /api/playback/errors` | Client telemetry | auth | `{sessionId, code, rendition, positionMs}` | sampled, 204 |
| `GET /api/lessons/{lessonId}/contents/{contentId}/download` | PDF/resource | `DOWNLOAD` | 302 → 60 s signed URL | |
| `GET /api/lessons/{lessonId}/transcript` | Transcript | `VIEW_CONTENT` | `{segments[{startMs, endMs, text}]}` paged | approved/auto-published only |
| `GET /api/lessons/{lessonId}/artifacts` | Summary, chapters, flashcards | `VIEW_CONTENT` | approved artifacts | |
| `GET /api/courses/{courseId}/transcripts/search` | Search within course | enrolled | `?q` → hits with deep links | FTS, allowed assets only |

## 7. AI administration & Q&A

| Method & path | Purpose | Authz | Notes |
|---|---|---|---|
| `GET /api/assets/{assetId}/ai` | AI job + artifact status | staff | per type: status, version, cost |
| `POST /api/assets/{assetId}/ai/{type}` | Request/regenerate artifact (QUIZ_DRAFT, FLASHCARDS, KEY_CONCEPTS, SUMMARY…) | staff | Idempotency-Key; 202; budget check → 429 `ai_budget_exceeded` |
| `POST /api/assets/{assetId}/ai/jobs/{jobId}/retry` | Retry failed/dead | org admin | 202 (A) |
| `GET /api/assets/{assetId}/artifacts/{artifactId}` | Review draft | staff | full content |
| `PATCH /api/assets/{assetId}/artifacts/{artifactId}` | Edit draft | staff | content edits recorded (`edited_by`) |
| `POST /api/assets/{assetId}/artifacts/{artifactId}/approve` / `reject` | Review decision | course staff | (A) |
| `POST /api/courses/{courseId}/qa` | Grounded Q&A | `QUERY_AI` | `{question(≤500 chars), lessonId?}` → `{answer, grounded, citations[{lessonId, title, startMs}]}`; 429 daily limit |
| `POST /api/courses/{courseId}/qa/{qaId}/feedback` | Rate answer | asker | `{rating: -1\|1, comment?}` |

## 8. Live classes

| Method & path | Purpose | Authz | Request / Response | Notes |
|---|---|---|---|---|
| `POST /api/live-sessions` | Schedule | instructor of offering / ORG_ADMIN | `{offeringId, cohortId, lessonId?, title, scheduledStart, scheduledEnd, hostUserId?, needsHd?}` → 201 | 409 host overlap; 422 cohort not in offering (A) |
| `GET /api/live-sessions` | Calendar | learner: own cohorts; staff: org | `?cohortId&from&to&status` | |
| `GET /api/live-sessions/{id}` | Details | `VIEW_METADATA` | status, times, recording status | |
| `PATCH /api/live-sessions/{id}` | Reschedule | host/ORG_ADMIN, SCHEDULED/READY only | `If-Match` | notifies learners (A) |
| `POST /api/live-sessions/{id}/cancel` | Cancel | host/ORG_ADMIN | `{reason}` | (A) |
| `GET /api/live-sessions/{id}/ingest` | Stream credentials | **host only** | `{ingestEndpoint, streamKey}` (no-store) | key rotated after session; (A) |
| `POST /api/live-sessions/{id}/end` | Force end | host/ORG_ADMIN | 202 | stops stream via provider |
| `POST /api/live-sessions/{id}/join` | Learner join | `JOIN_LIVE` | → `{playbackUrl (token in query, exp ≤ end+30m), chatEndpoint, chatToken (exp 1h, refreshable), status}` | 409 `not_live_yet` with startsAt; 403 |
| `POST /api/live-sessions/{id}/chat-token` | Refresh chat token | `JOIN_LIVE` | new token | |
| `POST /api/live-sessions/{id}/heartbeat` | Attendance | joined learner | `{clientTs}` → 204 | capped deltas |
| `GET /api/live-sessions/{id}/attendance` | Attendance report | host/ORG_ADMIN | page + CSV export | |
| `PATCH /api/live-sessions/{id}/attendance/{userId}` | Manual override | host | `{attendanceStatus, note}` | (A) |
| `POST /api/live-sessions/{id}/recording/publish` | Attach recording to lesson | host/ORG_ADMIN | `{lessonId?}` (DRAFT version) or auto via schedule | 409 recording not READY |
| `POST /api/live-sessions/{id}/recording/retry` | Retry processing | ORG_ADMIN | 202 (A) |
| `POST /api/webhooks/aws/…` | — | — | Not used: AWS events arrive via EventBridge → SQS, not HTTP |

## 9. Operations

| Method & path | Purpose | Authz |
|---|---|---|
| `GET /api/health` **[exists]**, `/actuator/health`, `/actuator/info` **[exists]** | Liveness/readiness | public (health details restricted) |
| `GET /api/admin/jobs` | Job search | platform admin — `?type&status&subjectId` |
| `POST /api/admin/jobs/{id}/retry` / `cancel` | Manual recovery | platform admin (A) |
| `GET /api/admin/audit-events` | Audit search | platform admin; org admin gets `/api/orgs/{orgId}/audit-events` scoped |
