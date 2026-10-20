# eia-policy-expert

- GitHub organization: `eia-open-insurance`
- Repository URL: https://github.com/eia-open-insurance/eia-policy-expert
- Type: Spring Boot service
- Deployable: Yes
- Owner: Policy
- Runtime: Java 22, Spring Boot, Spring Batch, Quartz
- Data store: eia_policy (ent, ref, audit schemas)
- Depends on: eia-contracts, eia-platform/libs

## Purpose

Policy persistence, invariants, read models, batch jobs, Quartz schedules, and outbox/inbox. Direct policy lifecycle, coverage, limits, billing, underwriting, rating, and capacity.

## Implemented slice

The second vertical slice keeps the first-slice `policy` aggregate and adds the
contract structures from SPEC 11.2:

- `policy_version`: immutable versions with `version_number`,
  `policy_version_number`, `change_type`, `status`, effective dates, `issued_at`,
  `issued_by`, `approved_by`, `premium_delta_amount`, `supersedes_version_id`,
  and `immutable_hash`. A partial unique index allows one `ISSUED` version per
  policy; the current version is derived from that row and its canonical SHA-256
  hash is recomputed on read.
- `policy_coverage` and `policy_limit`: coverage code/part/occurrence basis plus
  per-occurrence, aggregate, sublimit, deductible, coinsurance, currency, and
  effective dates, all constrained to the policy term.
- `policy_limit_state` and `policy_limit_erosion`: an idempotent erosion ledger.
  Facts are unique by `(limit, claim, allocation, source_version)`; the highest
  source version per claim/allocation chain wins, and a fact that would take the
  aggregate negative is rejected with the available amount.
- `policy_endorsement`: numbered endorsements that each issue a policy version.
  A backdated endorsement needs a reason and an approval.
- `policy_insured` and `policy_location`: parties, roles, addresses, state,
  country, and location codes. At least one ACTIVE named insured is required
  before `ISSUED`.
- `policy_cancellation` and `policy_reinstatement`: notice, reason, effective
  date, pro-rata return premium, and a reinstatement gate that requires an
  approval and documented premium evidence.
- `policy_billing_plan` and `policy_installment`: one ACTIVE plan per policy,
  an equal premium split across 1..24 installments with the rounding remainder
  on the last row, and PAID/WAIVED settlement.
- `billing_dunning_action`: one reminder/notice/collections action per
  installment and escalation level, with channel, notes, status, and sent time.
  The unique `(tenant_id, installment_id, level)` key is the duplicate-send
  arbiter.
- `policy_mass_tort_profile`: one profile per policy carrying the tort type,
  trigger theory, allocation method/notes, an inclusive coverage block, and
  DRAFT/COMPLETE status. A COMPLETE profile requires the notes and both block
  dates.
- Policy renewal lineage: `policy.renewed_from_policy_id` points a renewal DRAFT
  at the term it replaces. Renewal copies the source product, insured, coverage
  grants, limits, insureds, and locations into the new term without mutating the
  source policy.
- `outbox_event`: canonical Policy events written in the same transaction as
  each covered mutation (`created`, `updated`, `version-issued`, `endorsed`,
  `cancelled`, `reinstated`, `renewed`, `billing-plan.created`, `installment.paid`,
  `installment.waived`, `dunning.sent`, `mass-tort-profile.updated`), with
  conditional Quartz HTTP dispatch.

Every second-slice mutation accepts an optional `If-Match` strong entity tag
(the aggregate `version`), returns the new `ETag`, and accepts an optional
`Idempotency-Key` scoped by tenant and command type. A replay returns `200` with
`Idempotent-Replayed: true`; reuse with a different request is `409
IDEMPOTENCY_CONFLICT`.

### Second-slice endpoints

All paths preserve the first-slice surface and are additive under
`/internal/v1/policies/{policyId}`:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/versions` | Version history (page envelope) |
| `GET` | `/versions/current` | Derived current version with `hashVerified` |
| `GET` | `/versions/{versionId}` | One version snapshot |
| `GET`/`POST` | `/coverages` | Coverage list / add coverage with limit schedule |
| `GET` | `/limits` | Limit schedules |
| `GET` | `/aggregate-state` | Erosion state per limit |
| `POST` | `/erosion` | Apply an idempotent erosion fact |
| `GET`/`POST` | `/endorsements` | Endorsement list / endorse |
| `GET` | `/endorsements/{endorsementId}` | One endorsement |
| `GET`/`POST` | `/insureds` | Insured list / add insured |
| `GET`/`POST` | `/locations` | Location list / add location |
| `GET` | `/cancellations` | Cancellation history |
| `POST` | `/cancel` | Cancel with computed return premium |
| `GET` | `/reinstatements` | Reinstatement history |
| `POST` | `/reinstate` | Reinstate with approval and premium evidence |

Renewal is exposed under the external API path (and keeps the `/internal/v1`
alias used by the domain adapter):

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/policies/{policyId}/renew` | Create a new DRAFT renewal term from an `ISSUED`, `IN_FORCE`, `ENDORSED`, or `REINSTATED` policy; `201` with the new policy, `409 RENEWAL_NOT_ALLOWED` otherwise |

The renew body carries `effectiveDate` (optional; defaults to the source
expiration plus one day and must not precede the source expiration),
`expirationDate` (required, after the effective date), and optional
`premiumAmount`, `premiumCurrency`, `limitAmount`, and `limitCurrency` that
default to the source policy. The source policy is never mutated; the new policy
carries `renewedFromPolicyId` and copied coverage, limit, insured, and location
terms rebound to the new term. `policy.policy.renewed` is written in the same
transaction.

Billing is also exposed under `/api/v1/policies/{policyId}/billing` (the same
controller keeps the `/internal/v1` alias for the domain adapter):

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/policies/{policyId}/billing` | ACTIVE (else latest) plan and its installments; `404` when none |
| `POST` | `/api/v1/policies/{policyId}/billing` | Create the plan and split the policy premium; `201`, `409` when an ACTIVE plan exists |
| `PATCH` | `/api/v1/policies/{policyId}/billing/installments/{installmentId}` | Settle as `PAID` or `WAIVED`; same-status replay is a no-op, terminal-status switch is `409` |

The plan response is `{"plan": {...}, "installments": [...]}`. Plan creation
and settlement bump the policy aggregate version, so every billing outbox event
has a distinct `aggregateVersion`.

Dunning is exposed under the tenant-wide billing path (the same controller keeps
the `/internal/v1` alias for the domain adapter):

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/billing/installments/{installmentId}/dunning` | Record a `REMINDER`, `FIRST_NOTICE`, `SECOND_NOTICE`, or `COLLECTIONS` action over `EMAIL`, `LETTER`, or `PORTAL`; `201`, `409 INSTALLMENT_NOT_DUNNABLE` for PAID/WAIVED, `409 DUNNING_ALREADY_SENT` for a repeated level |
| `GET` | `/api/v1/billing/installments/{installmentId}/dunning` | Dunning history, newest first |

The body is `{level, channel, notes?}`. The tenant comes from `?tenantId=` or
`X-EIA-Tenant`, and the route is fixed to the DIRECT book. An installment is
dunnable while its effective status is `DUE` or `OVERDUE`, so a stored `DUE` row
past its due date is dunnable. Notes are trimmed and capped at 2000 characters.
Every accepted action bumps the policy aggregate version and writes
`policy.dunning.sent` (`policyId`, `policyNumber`, `installmentId`, `level`,
`channel`, `sentAt`, `status`) in the same transaction.

The mass tort profile is exposed under
`/api/v1/policies/{policyId}/mass-tort-profile` (the same controller keeps the
`/internal/v1` alias for the domain adapter):

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/policies/{policyId}/mass-tort-profile` | Current profile; `404 MASS_TORT_PROFILE_NOT_FOUND` when the policy has none |
| `PUT` | `/api/v1/policies/{policyId}/mass-tort-profile` | Upsert the profile; `201` when created, `200` when updated |

The body carries `tortType`, `triggerType`, `allocationMethod`, optional
`allocationNotes`, optional `coverageBlockStart`/`coverageBlockEnd`, and
`status`. The tenant comes from `?tenantId=` or `X-EIA-Tenant`; the route is
fixed to the DIRECT book. Dates are an inclusive block (`coverageBlockEnd` may
not precede the start), notes are capped at 2000 characters, and `COMPLETE`
requires the notes and both block dates. Every accepted upsert bumps the policy
aggregate version and writes `policy.mass-tort-profile.updated` in the same
transaction.

The tenant-wide premium receivables worklist is
`GET /api/v1/billing/installments` (also exposed under
`/internal/v1/billing/installments` for the Policy domain adapter):

| Query | Meaning |
|---|---|
| `status` | optional `DUE` or `OVERDUE` (effective status); defaults to both |
| `dueBefore` | optional inclusive `YYYY-MM-DD` due-date cutoff |
| `limit` | default 100, clamped to 1..200 |

The tenant comes from `X-EIA-Tenant` or `?tenantId=`, and the route is fixed to
the DIRECT book: a missing `X-EIA-Book` is treated as DIRECT and another book is
`404`. The response is `{items, totalAmount, overdueAmount, count}` with items
ordered by `due_date ASC, policy_number ASC, installment_number ASC`. Each item
joins the plan, policy, and installment so it carries `planNumber`,
`policyNumber`, and `insuredPartyId`. Overdue is derived at read time: a stored
`DUE` installment past its due date is returned as `OVERDUE` with
`daysPastDue > 0`, and the stored row is never mutated. Each item also carries
`lastDunningLevel` and `lastDunningAt` from the latest `SENT` dunning action
for the installment (both `null` when it has never been dunned; cancelled
history is ignored).

## Cross-cutting API behavior

- Errors are canonical `application/problem+json` (SPEC 6.11 / 12.2) with
  `errors[{field, code, message}]`, `meta`, and a correlation id. Business-rule
  failures are `422`; missing approvals are `428`; state and version conflicts
  are `409`; not-found resources are `404`.
- `GET /internal/v1/policies` accepts `page` (default 0) and `size` (default 20,
  maximum 100) and returns `{items, page, size, total, _links}` with stable
  `updated_at DESC, id ASC` ordering. Sub-resource collections use the same
  envelope and are ordered by version, sequence, or creation time.
- Detail and mutation responses carry `ETag: "<version>"`; every mutation
  accepts `If-Match` with precedence over the compatibility `expectedVersion`
  body field on status changes. Stale versions are `409
  CONCURRENT_MODIFICATION`.
- `POST /internal/v1/policies` accepts `Idempotency-Key`; the first create is
  `201`, a replay is `200` with `Idempotent-Replayed: true`.

## Local development

Requires JDK 22, PostgreSQL 17, and a database named `eia_policy` owned by `postgres`.

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-22.0.2.9-hotspot'
.\gradlew.bat build
.\gradlew.bat bootRun
```

The service listens on port 8083. `schema.sql` creates the tables and sequences on startup and is idempotent.

### Live PostgreSQL smoke

`LivePostgresSmokeTest` is skipped unless `EIA_LIVE_PG=true`. It starts the real
service against PostgreSQL and drives create -> named insured -> issue ->
coverage -> endorsement -> erosion -> cancellation -> reinstatement over HTTP,
asserting the version/hash invariants, ETag/If-Match, idempotency replay, and the
computed return premium.

```powershell
$env:EIA_LIVE_PG='true'
.\gradlew.bat test --tests '*LivePostgresSmokeTest*'
```

`EIA_LIVE_PG_URL`, `EIA_LIVE_PG_USER`, and `EIA_LIVE_PG_PASSWORD` override the
connection; rows are tenant-scoped and removed by the test.

## Responsibilities

- Keep this repository focused on one deployable component or one platform concern.
- Follow the repository standard in `../CONVENTIONS.md` and the specification in `../../SPEC.md`.
- Publish and consume contracts through `eia-contracts`.
- Maintain README, CODEOWNERS, SECURITY, CHANGELOG, tests, and local verification.
- Contain no business logic in shared libraries or front-end remotes.

## Verification

- Lint and format
- Unit tests with 100% business-logic coverage where applicable
- Contract tests
- Integration or Playwright tests where applicable
- Security scan and SBOM
- Build and publish

## Notes

Writes data; does not decide business outcomes.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
