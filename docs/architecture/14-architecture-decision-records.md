# 14 — Architecture Decision Records

Format: Context · Options · Decision · Rationale · Consequences. Status **Proposed** (2026-10-21) until R0 review accepts them.

---

## ADR-001 Modular monolith with API and worker roles
- **Context:** Single Spring Boot app with Modulith (`ModularityTests`); `initial_prompt.md` mandates no microservices; small team; workloads include long-running media/AI jobs.
- **Options:** (a) monolith, single role; (b) monolith, API + worker roles from the same artifact; (c) microservices.
- **Decision:** (b).
- **Rationale:** Keeps transactional consistency and one codebase while isolating long/blocking work from user traffic and scaling it independently.
- **Consequences:** Profile-driven bean activation (`ohara.role`); both roles share the DB; deployments must stay backward compatible across roles for one release.

## ADR-002 Shared-schema multitenancy with application-enforced isolation
- **Context:** 5–tens of colleges, shared catalog content, learners in multiple orgs and in B2C.
- **Options:** DB-per-tenant; schema-per-tenant; shared schema + `organization_id`; shared schema + RLS.
- **Decision:** Shared schema + tenant column, enforced by `OrgAuthorizationService`/`ContentAccessService` and tenant-scoped repositories; RLS deferred.
- **Rationale:** Cross-tenant sharing of courses and identities is a core requirement; per-tenant DBs would duplicate content and identities.
- **Consequences:** Isolation depends on code discipline → mandatory cross-tenant test suite and audit of denials; revisit RLS at > 20 tenants or after a security finding.

## ADR-003 Learner = User + memberships (no `student` table)
- **Context:** B2B and B2C learners; instructors who are also learners.
- **Decision:** No `student` entity; org/cohort memberships carry context-specific attributes.
- **Consequences:** One account per human; reports join memberships; college roll numbers live on `organization_membership.external_ref`.

## ADR-004 Immutable published course versions with stable lesson keys
- **Context:** Courses change while learners study them.
- **Options:** in-place edits; immutable snapshot per version; event-sourced curriculum.
- **Decision:** Copy-on-write `course_version`; published rows immutable; `module_key`/`lesson_key` stable across versions; enrollments pin a version.
- **Consequences:** Publishing copies rows (cheap — hundreds per course); progress maps by `lesson_key`; explicit migration for learners to newer versions.

## ADR-005 Separate enrollment, access grant and payment
- **Context:** B2B cohort assignment, B2C access after offline payment, admin grants, refunds (offline), expiries.
- **Decision:** `enrollment` (relationship) + `access_grant` (why/when access is allowed, with source, window and — for paid access — the offline contract/bank reference). Grants have unique `(source, source_ref_id)`. Money is not modeled in Ohara (ADR-012).
- **Rationale:** Relationship and permission have different lifecycles; grants can be created idempotently from cohort memberships, bulk imports and staff actions.
- **Consequences:** `ContentAccessService` is the single gatekeeper; grants double as access history and as the reconciliation record for finance.

## ADR-006 Private S3 + CloudFront signed cookies for VOD
- **Context:** Paid content; HLS requests hundreds of segments.
- **Options:** public S3; signed URLs per object; signed cookies; token-auth at edge (Lambda@Edge/CloudFront Functions with KVS).
- **Decision:** Two private buckets, CloudFront OAC, signed cookies scoped to the asset prefix with ≤ 20 min TTL, issued after `ContentAccessService` checks.
- **Consequences:** CDN alias on app's registrable domain; mobile native players need header injection or signed-URL fallback; revocation lag ≤ TTL.

## ADR-007 MediaConvert for transcoding; READY only after output verification
- **Context:** ~100 h/month uploads; no encoder ops capacity.
- **Options:** MediaConvert Basic/Professional; FFmpeg worker fleet; third-party video platform (Mux/Cloudflare Stream).
- **Decision:** MediaConvert Basic tier, lecture-tuned ladder, sidecar VTT captions; worker verifies manifests/segments before READY.
- **Rationale:** ~$135–225/month vs ~$30–60 compute for FFmpeg — the difference is far below the cost of operating a fleet; third-party platforms lock playback and delivery too.
- **Consequences:** `Transcoder` port; re-evaluate above ~1M normalized minutes/month or when custom processing is needed.

## ADR-008 Postgres job table + Modulith outbox + SQS for AWS events; no Kafka yet
- **Context:** Need durable async processing; `initial_prompt.md` lists Kafka/Debezium as a later phase.
- **Options:** Kafka (+Debezium CDC); SQS-only; Step Functions; Postgres jobs + Modulith event registry; Redis queues.
- **Decision:** Postgres `processing_job` + Spring Modulith JDBC event publication registry; SQS (+DLQ) only for EventBridge/S3 events.
- **Rationale:** Transactional with business writes, observable, no new infrastructure; volumes are tiny (hundreds of jobs/day).
- **Consequences:** Polling with `SKIP LOCKED`; triggers to introduce Kafka: (1) ≥ 2 independent consumers needing replay (analytics, rewards), (2) event volume > ~100/s sustained, (3) need for CDC into a warehouse. Modulith can externalize events to Kafka without changing producers.

## ADR-009 Amazon IVS Low-Latency + IVS Chat for live classes
- **Context:** Broadcast to ≤ 500 per cohort, chat/Q&A, recordings reused as lessons, AWS-based VOD stack.
- **Options:** IVS; Mux Live; Cloudflare Stream Live; WebRTC SaaS (100ms/Agora/IVS Real-Time); self-built.
- **Decision:** IVS Low-Latency, private channels, one channel + chat room per cohort; Advanced SD default, Standard for HD sessions; auto-record to S3 and adopt renditions into the VOD pipeline.
- **Consequences:** Per-viewer-hour output cost dominates at high attendance (11 §3.1); `LiveStreamingProvider` port bounds switching cost; quotas must be checked before scaling.

## ADR-010 Transcript-first AI; Postgres FTS before pgvector; no separate vector DB
- **Context:** Summaries, chapters, quizzes and grounded Q&A over lectures with strict tenant isolation.
- **Decision:** All text artifacts from transcripts; selective OCR for slide-heavy content; retrieval via Postgres FTS filtered by allowed assets; add pgvector in Postgres only if evaluation demands; LLM/STT behind ports.
- **Consequences:** Cheap, isolated, auditable; semantic recall may be lower than embeddings → measured by an eval set before Q&A GA.

## ADR-011 JWT subject = user UUID; org roles resolved server-side
- **Context:** Current tokens use email as subject and carry global roles.
- **Decision:** `sub`=userId, plus `email`, `ver` (auth_version), `scope`; platform roles only in token; org roles looked up per request; access TTL 15 min; `ver` + account status checked against the DB on each request (implemented in R1 slice 1).
- **Consequences:** One forced re-login at rollout; email change and role changes no longer invalidate identity mapping; revocation is immediate at the cost of one PK lookup per request.

## ADR-012 No online payments; staff-granted access with offline references
- **Context:** `initial_prompt.md` planned Razorpay/Cashfree. The business has confirmed (2026-10-21) that all payments and refunds — B2B and B2C — are done by the contract team by bank transfer.
- **Options:** (a) integrate gateways anyway; (b) no payment integration, staff grants access with a reference; (c) record full offline payment ledger in Ohara.
- **Decision:** (b). No cart, order, payment, webhook or subscription tables; no gateway credentials. `ROLE_ENROLLMENT_MANAGER` grants/extends/revokes B2C access with a mandatory `external_reference` (contract no. / bank UTR); bulk grants via `bulk_import_job(mode=GRANT_ACCESS)`; refunds become revocations (`REFUNDED_OFFLINE`); a reconciliation export serves finance. Finance remains the system of record for money.
- **Rationale:** Removes the riskiest integration (webhooks, idempotent capture, refunds) and a whole module; matches how the business actually collects money.
- **Consequences:** Access correctness now depends on staff actions → dedicated least-privilege role, MFA, Idempotency-Key, audit of every grant, volume alerts. Self-serve instant B2C purchase isn't possible; if it's needed later, a commerce module can emit grants with a new `source` without changing the access model.

## ADR-013 RFC 9457 problem details and typed exceptions
- **Context:** `ApiExceptionHandler` maps every `IllegalArgumentException` to 400.
- **Decision:** Typed exceptions → specific statuses; problem-details body with stable `code`; cross-tenant → 404.
- **Consequences:** Existing tests asserting 400 for revoked refresh tokens change to 401; clients can branch on `code`.

## ADR-014 Redis only for demonstrated needs
- **Context:** Redis is a dependency but unused.
- **Decision:** Don't put Redis on any critical path. First justified uses: shared rate-limit counters with ≥ 2 API nodes; optional short-TTL access-decision cache if profiling shows need. Otherwise remove the dependency.
- **Consequences:** Fewer moving parts; progress heartbeats stay in Postgres (volumes are small); no cart exists since payments are offline.
