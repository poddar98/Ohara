# 13 — Open Questions and Pending Decisions

Status: **Open** · 2026-10-21 · Each item: question, why it matters, options, **recommendation**, consequence, and the phase that needs the answer. IDs are referenced from other documents.

| ID | Question | Why it matters | Options | Recommendation | Consequence | Needed by |
|---|---|---|---|---|---|---|
| Q-01 | Is ₹1,000 B2C a **monthly fee**, a **one-time course price**, or a time-boxed pass? | Now only decides the default `valid_until` the contract team applies to grants (payments are offline) and the revenue model (×12 difference) | Monthly (staff extend each month) / one-time with N-month access | Per-course grant with `access_duration_days` on the offering; monthly renewals = staff `extend` with the new bank reference | No schema impact either way | R2 |
| Q-02 | Expected B2C paid conversion? | Cost per paying learner; contract-team workload (grants/month) | 30% / 100% scenarios in 11 | Track from launch; if grants exceed ~500/month, prioritise the `GRANT_ACCESS` bulk import UX | Only affects financial model and staffing | R6 |
| Q-03 | How are platform courses licensed to colleges (all, a list, seat counts, dates)? | Determines whether an `organization_course_license` table is needed | Settings list / license table | `settings.licensed_course_ids` for v1; table when seat limits or dates appear in contracts | Small migration later | R2 |
| Q-04 | Which AWS account/region; are S3, CloudFront, MediaConvert, IVS, Transcribe, SQS approved? (`initial_prompt.md`: "not yet bought") | Real adapters and IaC | ap-south-1 single account with dev/prod separation | **ap-south-1**, separate dev and prod accounts (AWS Organizations) | Mocks until provisioned | R3 |
| Q-05 | Must live chat be persisted (moderation evidence, college records)? | Adds `live_chat_message` table and IVS Chat logging | Not stored / IVS chat logging to S3 / DB table | **Not stored** by default; enable IVS chat logging to S3 per org if required | DB stays lean; import later possible | R4 |
| Q-06 | Email provider (SES, other) and sending domain? | Verification/reset/invite flows | Amazon SES / third-party transactional email | **Amazon SES** in ap-south-1 with DKIM/SPF/DMARC on the domain | Needs production access request for SES | R1 |
| Q-07 | DPDP Act 2023 obligations, incl. **minors** among college users and parental consent | Consent model, data-principal rights, breach duties | Legal review | Engage counsel before onboarding the first college; keep `user_consent` append-only | May add age attestation + guardian consent flow | R1 |
| Q-08 | Record-retention periods (financial, academic, audit) | Retention jobs, anonymization scope | 10 §8 defaults | Confirm with finance/legal; implement as config | Changing later only alters job configs | R1/R2 |
| Q-09 | Is DRM or per-user watermarking contractually required by any college/content owner? | DRM adds licence server cost, Professional-tier encoding, player complexity | None / visible watermark / Widevine+FairPlay | **Signed cookies + optional visible watermark**; DRM only if a contract requires it | DRM later requires re-packaging assets | R3 |
| Q-10 | Do audiences need a 360p / audio-only rung for poor connectivity? | Ladder, cost, UX | Add 360p / audio-only / neither | Add **360p for B2C mobile** after measuring rebuffer rate | Extra ~10% transcoding cost | R3 |
| Q-11 | Mobile apps: native players or WebView? | Signed-cookie vs signed-URL playback | Cookies on native HTTP stack / signed URLs / app-only token proxy | Web: cookies; native: inject cookies as headers in ExoPlayer/AVPlayer; fall back to signed URLs | Small `CdnSigner` extension | R3 |
| Q-12 | Assessment scope: question banks, grading, assignments with file submissions? | Separate module & tables | Simple quizzes / full assessment engine | Separate `assessment` module after R3; quiz drafts from AI land there | Not modeled in this document set beyond asset container | after R3 |
| Q-13 | Geo-restrict delivery (India only)? | Licensing, abuse | None / CloudFront geo restriction | None initially; enable per course if licensing requires | Simple config | R3 |
| Q-14 | If a college learner already has B2B access, may the contract team also grant the same course B2C (e.g. to extend beyond the cohort window)? | Grant rule | Block / allow as extension | **Allow only when the B2B grant ends within 30 days**, otherwise 409 `already_has_access` | Business rule config | R2 |
| Q-15 | STT and LLM provider choice and data-processing terms | Quality on Indian English/Hinglish; privacy | Transcribe / Whisper-class self-hosted / other; Claude (Anthropic API or Bedrock) / others | Benchmark 10 lectures for STT; Claude Haiku 5.5 (Q&A) + Sonnet 5.5 Batch (artifacts) subject to eval; prefer providers that don't train on inputs | Port abstraction keeps switch cost low | R5 |
| Q-16 | Must instructors approve every AI artifact, or can orgs auto-publish captions/summaries? | Workflow & liability | Always review / per-type org setting | Per-type org setting; quizzes always reviewed | Settings UI | R5 |
| Q-17 | Certificates of completion? | New artifact (PDF), verification URL | Out of scope / simple PDF / verifiable credential | Defer; enrollment `completed_at` is enough to add later | — | after R2 |
| Q-18 | College SSO (Google Workspace / SAML)? | `auth_identity` providers | Later | Model supports it; build on first college request | — | post-R1 |
| Q-19 | Can a learner be in multiple cohorts of the same org concurrently? | Constraint vs setting | Allow / disallow | Allow in schema; default org setting max 1 active | — | R1 |
| Q-22 | Expected peak concurrency for live (multiple cohorts simultaneously)? | IVS quotas (concurrent views per account), scheduling conflicts | — | Request quota increase ahead of R4 pilot if > default | — | R4 |
| Q-23 | Do we need Kafka/Debezium (original Phase 10) for analytics, sales, rewards consumers? | Infrastructure scope | Now / when a consumer exists | **When a consumer needs replay/stream processing** (ADR-008 triggers) | Modulith events can be externalized to Kafka later (`spring-modulith-events-kafka`) | R6+ |

## Resolved

| ID | Decision (2026-10-21) | Consequence |
|---|---|---|
| R-01 | **No online payments.** All payments — B2B contracts and B2C individuals — and all refunds are handled by the contract team outside Ohara, by bank transfer. | No cart/order/payment/webhook tables, no gateway (Razorpay/Cashfree dropped), no checkout API. Access = staff grant with mandatory contract/bank reference (`access_grant.source='CONTRACT'`); refund = staff revoke (`REFUNDED_OFFLINE`). New platform role `ROLE_ENROLLMENT_MANAGER`. Former Q-20 (refund policy) and Q-21 (GST/invoicing) move to the contract/finance team and are out of Ohara's scope. ADR-012. |

## New questions raised by R-01

| ID | Question | Recommendation | Needed by |
|---|---|---|---|
| Q-24 | Should learners be able to "request enrollment" in-app (lead capture for the contract team), or is contact purely offline? | Start offline ("Contact us to enroll" on the course page); add a simple request form if the contract team asks | R2 |
| Q-25 | Who on the contract team gets `ROLE_ENROLLMENT_MANAGER`, and do they need MFA? | Named accounts only, MFA (TOTP) required for staff roles before go-live | R2 |
| Q-26 | Does finance need a periodic export of grants/revocations for reconciliation, and in what format? | Daily/weekly CSV from `GET /api/admin/access-grants` | R2 |

## Assumptions to validate early

1. Audience mainly in India (prices, region, compliance).
2. No production data yet → V3 can rewrite V2 tables safely.
3. Lectures are mostly slides/talking head → 720p-top ladder is acceptable (validate with instructors on code-heavy courses).
4. Live classes don't require learners on camera.
5. 1,000 concurrent recorded viewers is a reasonable peak for sizing heartbeats/sessions.
