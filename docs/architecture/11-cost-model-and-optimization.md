# 11 — Cost Model and Optimization

Status: **Planning estimate** · Prices gathered **2026-10-21** · Currency **USD** (INR at an assumed **₹88 / USD** — change in one place). All workloads are **scenario inputs, not forecasts**.

> Read this as an order-of-magnitude model for decisions (which lever matters, where the risk is), not as a quote. Every price is tagged **[V] verified on the provider's pricing page on 2026-10-21**, **[3P] third-party source, not yet verified on the official page**, or **[E] engineering estimate**. Re-check [3P] and [E] values in the AWS Pricing Calculator for `ap-south-1` before budgeting.

## 1. Workload assumptions

| Input | Value | Note |
|---|---|---|
| Colleges / cohorts / learners | 5 / 10 / 5,000 B2B | planning assumption |
| B2C MAU | 2,000–5,000 | **not** equal to paying users |
| New uploaded video | 100 h/month | lectures, mostly slides + talking head |
| Live classes | 2 × 1 h per cohort per week → **87 sessions/month** (10 × 2 × 52/12) | |
| Live attendance scenarios | 20% / 50% / 100% of 500 → 100 / 250 / 500 concurrent viewers per session | full-hour viewing assumed (upper bound) |
| Recorded viewing — Scenario L (light) | 5,000 viewer-hours/month total | |
| Recorded viewing — Scenario H (heavy) | 50,000 B2B + 100,000 B2C = 150,000 viewer-hours/month | separate scenario, not additive to L |
| Delivered GB per recorded viewer-hour | **0.6 GB** central (720p-top lecture ladder ≈ 1.3 Mbps incl. audio); 0.35 low (480p); 1.2 high (1080p-heavy) [E] | biggest single cost driver |
| Transcript length | ~9,000 words ≈ 12k tokens per lecture-hour [E] | |
| Viewer region | India | |

## 2. Unit prices used

| Item | Price | Tag / source |
|---|---|---|
| IVS live input, Standard channel (ABR up to 1080p) | $2.00 / h | [V] aws.amazon.com/ivs/pricing |
| IVS live input, Advanced SD | $0.50 / h | [V] |
| IVS live input, Basic (no transcoding) | $0.20 / h | [V] |
| IVS output to India viewers, first 10k h/month | SD $0.046, HD $0.092, Full HD $0.184 per viewer-hour | [V] |
| IVS output India, next 40k h | SD $0.042, HD $0.084 | [V] |
| IVS Chat | $0.56 / 1k sent, $0.008 / 1k delivered; each input hour includes 2,700 sent + 270,000 delivered | [V] |
| IVS recording to S3 | S3 storage/request costs only | [V] (no separate IVS fee listed) |
| MediaConvert Basic tier | $0.0075 / normalized min (first 100k); AVC ≤30fps multipliers SD 1×, HD 2× | [V] US East (Ohio) — **Mumbai rate not shown; verify** |
| MediaConvert Professional tier | $0.012 / normalized min (first 50k) | [V] US East (Ohio) |
| Amazon Transcribe batch | $0.006 / min as shown on the page | [V] US East — page shows a single rate; **historically the first tier was $0.024/min — model uses $0.006–$0.024 range until confirmed for ap-south-1** |
| CloudFront data transfer to India, first 10 TB | $0.109 / GB | [3P] several 2026 sources agree; one says $0.12 |
| CloudFront India higher tiers | ~$0.085 (10–50 TB), ~$0.07 (50–150 TB) | [3P] sources conflict — treat as indicative |
| CloudFront HTTPS requests India | $0.012 / 10k | [3P] |
| CloudFront free tier | 1 TB + 10M requests / month | [3P] |
| CloudFront flat-rate plans | Pro $15, Business $200, Premium $1,000 per month per distribution; listed data-transfer allowance 50 TB on paid plans, "no overage charges" | [V] aws.amazon.com/cloudfront/pricing — **eligibility for video streaming, throttling behaviour and India performance must be confirmed** |
| S3 Standard, Mumbai | ~$0.025 / GB-month (≈ ₹2.1) | [3P] reseller page |
| S3 Glacier Instant Retrieval | ~$0.005 / GB-month | [E] verify |
| Claude Haiku 5.5 | $0.10 in / $0.50 out per 1M tokens (prompts ≤ 100K) | Anthropic pricing as of 2026-10-06 (Claude API reference) |
| Claude Sonnet 5.5 | $2 in / $10 out per 1M tokens; Batch API −50% | same |
| App compute, DB, logging | see §4 | [E] |

## 3. Usage-driven cost by category

### 3.1 Live (87 sessions × 1 h)

| Attendance | Viewer-h | Standard channel + HD output | Advanced SD channel + SD output |
|---|---|---|---|
| 20% (100 viewers) | 8,700 | input $174 + output $800 = **$974** | $43.5 + $400 = **$444** |
| 50% (250) | 21,750 | $174 + ($920 + 11,750 × $0.084 = $1,907) = **$2,081** | $43.5 + ($460 + $494) = **$997** |
| 100% (500) | 43,500 | $174 + ($920 + $2,814) = **$3,908** | $43.5 + ($460 + $1,407) = **$1,911** |

- Chat: at 500 viewers and ~300 messages/session → ~150k deliveries/h, inside the 270k included per input hour → **≈ $0**.
- Recording storage: in §3.6. Re-transcoding recordings is avoided by adopting IVS renditions (06 §7.5) — saves **$117–196/month**.
- **Cost per live viewer-hour:** Standard/HD $0.112 (20%) → $0.090 (100%); Advanced SD $0.051 → $0.044. Input cost is fixed per session, so cost per viewer-hour falls as attendance rises.

### 3.2 Recorded delivery (CloudFront)

| Scenario | Viewer-h | GB (0.6/vh) | Data transfer | Requests (~620/vh) | Total |
|---|---|---|---|---|---|
| L | 5,000 | 3,000 GB | (3,000 − 1,000 free) × $0.109 = **$218** | 3.1M — inside free tier → $0 | **≈ $218** |
| H | 150,000 | 90,000 GB | flat upper bound 90,000 × $0.109 = $9,810; tiered estimate $1,090 + $3,400 + $2,800 = **$7,290** | 93M − 10M free → **$100** | **$7,390 – $9,910** |
| H split | B2B 50k vh → 30 TB; B2C 100k vh → 60 TB | | | | |

- **GB per recorded viewer-hour:** 0.6 central (0.35–1.2).
- **Cost per recorded viewer-hour:** 0.6 × $0.109 + $0.0007 ≈ **$0.066 (≈ ₹5.8)** at first-tier list price; ≈ $0.05 at the indicative higher tiers.
- Sensitivity: at 1.2 GB/vh (1080p-heavy) Scenario H doubles to ~$15–20k. At 0.35 GB/vh it is ~$4.3–5.8k. **Ladder choice matters more than any other price in this model.**

### 3.3 Transcoding (MediaConvert, 100 uploaded h = 6,000 min)

| Ladder | Normalized-min multiplier | Basic tier | Professional tier |
|---|---|---|---|
| 720p + 480p (default `SLIDES`) | 2 + 1 = 3× → 18,000 | **$135** | $216 |
| 1080p + 720p + 480p (`SCREEN_DETAIL`) | 2 + 2 + 1 = 5× → 30,000 | **$225** | $360 |

**Cost per processed video-hour:** $1.35 (Basic, 720p top) – $2.25 (Basic, 1080p) – $3.60 (Pro, 1080p). Live recordings: $0 when adopted.

### 3.4 Transcription (100 uploaded + 87 live = 187 h = 11,220 min)
$0.006/min → **$67/month**; $0.024/min → **$269/month**. **Cost per transcript minute: $0.006–0.024.**

### 3.5 AI generation

| Item | Assumption | Claude Haiku 5.5 | Claude Sonnet 5.5 (Batch −50%) |
|---|---|---|---|
| Artifacts per lecture-hour (summary+chapters, quiz, flashcards) | ~42k input + 6.5k output tokens | $0.0075 | $0.075 (standard $0.149) |
| Monthly (187 h) | | **$1.40** | **$14** (standard $28) |
| **Cost per AI-generated artifact** | 3 artifacts/h | **< $0.003** | **≈ $0.025** |
| Q&A per question | 4k in + 400 out | $0.0006 | $0.012 |
| Q&A, 10k learners × 10 q/month = 100k q | | $60 | $1,200 (90/10 Haiku/Sonnet mix ≈ **$174**) |

Q&A volume, not artifact generation, is the AI cost risk.

### 3.6 Storage (S3)

| Item | Monthly growth [E] |
|---|---|
| Upload originals (≈ 2.5 GB/h) | 250 GB |
| Delivery renditions (≈ 1.2 GB/h) | 120 GB |
| Live recordings adopted as delivery renditions (≈ 2 GB/h) | 174 GB |
| **Total** | **≈ 550 GB/month → ≈ 6.6 TB after 12 months** |

Month-12 bill: all-Standard 6.6 TB × $0.025 ≈ **$165**; with lifecycle (originals → Glacier IR after 30 days) ≈ 3.6 TB Standard + 3 TB IR ≈ **$105**. Month 1: ≈ $14.

### 3.7 Queues, orchestration, application, monitoring

| Item | Estimate | Tag |
|---|---|---|
| SQS + EventBridge (AWS service events) | < 1M requests/month → ≈ $0–1 | [E] |
| API ×2 + worker ×1 (Fargate ~1 vCPU/2 GB) + ALB | $120–200 | [E] |
| RDS PostgreSQL (db.t4g.medium Multi-AZ, 100 GB) | $150–250 | [E] |
| NAT, Secrets Manager, WAF, misc. | $50–100 | [E] |
| CloudWatch logs/metrics/alarms | $30–80 | [E] |
| **Fixed platform subtotal** | **≈ $350–600/month** (scale to ~$900 in Scenario H) | [E] |

## 4. Scenario totals (monthly)

| Category | Scenario L — low | Scenario L — high | Scenario H — low | Scenario H — high |
|---|---|---|---|---|
| Live | $444 (20%, Adv SD) | $974 (20%, Std HD) | $1,911 (100%, Adv SD) | $3,908 (100%, Std HD) |
| Recorded delivery | $218 | $218 | $7,390 | $9,910 |
| Transcoding | $135 | $225 | $135 | $421 (incl. re-transcoding recordings) |
| Transcription | $67 | $269 | $67 | $269 |
| AI generation | $14 | $14 | $14 | $28 |
| AI Q&A | $35 (20k q mix) | $35 | $174 | $1,200 (Sonnet-only) |
| Storage | $14 | $105 | $105 | $165 |
| Queues | $1 | $1 | $1 | $1 |
| Fixed platform | $350 | $600 | $600 | $900 |
| **Total** | **≈ $1,280 (₹1.1 L)** | **≈ $2,440 (₹2.1 L)** | **≈ $10,400 (₹9.1 L)** | **≈ $16,800 (₹14.8 L)** |

No stage is counted twice: live recordings appear in storage and transcription only (not transcoding unless the "high" column re-transcodes them); IVS output and CloudFront delivery are separate audiences (live vs on-demand).

**Fixed vs usage-driven:** fixed ≈ $350–900 (compute, DB, monitoring, base storage). Everything else scales with usage — live viewer-hours and recorded GB delivered dominate (70–85% of Scenario H).

## 5. Unit metrics

| Metric | Value |
|---|---|
| Cost per live viewer-hour | $0.044–0.112 (Adv SD ↔ Std HD; falls with attendance) |
| Cost per recorded viewer-hour | ≈ $0.066 list ($0.05 tiered); 0.6 GB/vh |
| GB delivered per viewer-hour | 0.6 (0.35–1.2) |
| Cost per processed video-hour | $1.35–3.60 |
| Cost per transcript minute | $0.006–0.024 |
| Cost per AI artifact | < $0.003 (Haiku) – ≈ $0.025 (Sonnet batch) |
| **Media cost per B2B learner / month** (Scenario H B2B share: 50% live attendance, 10 recorded vh/learner) | live $0.42 (Std HD) or $0.20 (Adv SD) + recorded $0.65 + processing share $0.05 → **$0.90–1.12 ≈ ₹79–99** |
| **Media cost per B2C active learner / month** (Scenario H: 20 vh/MAU at 5,000 MAU) | recorded $1.31 + processing $0.05 → **$1.36 ≈ ₹120** (at 2,000 MAU the same 100k vh is 50 vh/MAU → ≈ ₹290) |
| **Cost per paying B2C learner** | 30% conversion of 5,000 → 1,500 payers → **≈ ₹400**; 100% → ≈ ₹120 |
| **Marginal cost of another college** (1,000 learners, 2 cohorts, shared content) | live 17 h input + 4,330 vh at 50% → $191 (Adv SD)–$399 (Std HD); recorded 10 vh/learner → 6 TB → $510–654 → **≈ $700–1,050/month (₹62–92k)** |
| **Marginal 1,000 active B2C learners** | 5 vh/MAU → $255–330; 20 vh/MAU → $1,050–1,320 |

## 6. Financial sensitivity (revenue, kept separate from cost)

| Stream | Basis | Monthly revenue |
|---|---|---|
| B2B | 5,000 × ₹600 (hypothetical) | ₹30.0 lakh (≈ $34.1k) |
| B2C, 2,000 MAU | 30% pay ₹1,000 / 100% pay | ₹6.0 lakh / ₹20.0 lakh |
| B2C, 5,000 MAU | 30% / 100% | ₹15.0 lakh / ₹50.0 lakh |

Against infrastructure of ₹1.1–2.1 lakh (L) or ₹9.1–14.8 lakh (H). **Revenue minus infrastructure is not profit:** excluded are GST treatment of the price points, payment-gateway fees (typically ~2% — verify contracted rate), refunds/chargebacks, salaries, content production, instructor/college revenue share, support, sales and marketing. Whether ₹1,000 is monthly or one-time (Q-01) changes B2C revenue by an order of magnitude over a year.

## 7. Optimization priorities (ranked by impact on Scenario H)

1. **Lecture-tuned ladder + `content_profile`** (06 §4): default top rung 720p at ~1.2 Mbps; 1080p only when needed. Moving from 1.2 to 0.6 GB/vh saves ~$7–10k/month in Scenario H. Measure actual GB/vh from CloudFront logs in the first month.
2. **Live defaults:** Advanced SD channel + SD output unless `needs_hd` → halves live cost ($3.9k → $1.9k at 100%).
3. **CloudFront pricing options:** evaluate flat-rate plans (if video use is eligible) and, past ~10 TB/month, private/committed pricing with AWS. Origin Shield + immutable segment caching keep S3 GETs negligible.
4. **Don't double-process live recordings:** adopt IVS renditions (saves up to ~$196/month and hours of latency).
5. **AI:** Haiku-first Q&A with retrieval gating (no LLM call when nothing relevant is found), per-user/org caps, Batch API + prompt caching for artifacts, transcript-first (no frame analysis by default), regenerate only on demand.
6. **Storage lifecycle:** originals → Glacier IR after 30 days; delete superseded generations; abort incomplete multipart uploads.
7. **Autoscale workers on backlog**; scale API on CPU/latency; Fargate Spot for workers.
8. **Regional pricing:** India viewer rates are higher than US/EU for both IVS and CloudFront; nothing to do but know it (don't serve from a far region to "save" — latency suffers, price is by viewer location).
9. **Provider portability:** `CdnSigner`/`LiveStreamingProvider` ports let us benchmark Cloudflare Stream / Bunny / Mux per-minute pricing if delivery exceeds ~50 TB/month. **Building a proprietary CDN or streaming stack is not justified** at these volumes: at 90 TB/month the entire delivery bill (~$7–10k) is below the cost of the engineers needed to run one.

## 8. Cost observability

- Tag every AWS resource with `env`, `component` (`api`, `worker`, `media`, `live`, `ai`); activate cost-allocation tags.
- Per-org/per-course attribution: CloudFront standard logs (to S3, sampled 100%) parsed daily by a `COST_ATTRIBUTION` job using the `/org/{org}/asset/{asset}/` path → GB per org/course; IVS viewer-hours per session from our attendance data; AI cost from `processing_job`/`ai_qa_log`.
- AWS Budgets (50/80/100%) and Cost Anomaly Detection; app alerts in 09 §8.
