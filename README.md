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
- `outbox_event`: canonical Policy events written in the same transaction as
  each covered mutation (`created`, `updated`, `version-issued`, `endorsed`,
  `cancelled`, `reinstated`, `billing-plan-created`, `installment-paid`,
  `installment-waived`), with conditional Quartz HTTP dispatch.

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
