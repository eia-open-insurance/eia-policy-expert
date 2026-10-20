# Changelog

All notable changes to this repository are documented here.

The format is based on Keep a Changelog and the project uses Semantic Versioning.

## [Unreleased]

### Added

- Repository baseline.
- Policy expert slice: PostgreSQL-backed `policy` table, create/list/get/status endpoints, lifecycle and money invariants, structured errors, and JUnit tests.
- Canonical RFC 9457 `application/problem+json` errors with
  `errors[{field, code, message}]`, `meta`, and correlation ids.
- `policy_idempotency` table with unique constraint
  `(tenant_id, idempotency_key)` and transactional create replay.
- Page envelope `{items, page, size, total, _links}` for the internal list with
  `updated_at DESC, id ASC` ordering.
- `ETag` on detail and mutation responses plus `If-Match` concurrency control.
- `Idempotency-Key` support on create with `Idempotent-Replayed` response
  headers.
- Second slice: immutable `policy_version` history with a single-ISSUED partial
  unique index, canonical SHA-256 snapshot hashes re-verified on read, and the
  derived current version.
- `policy_coverage` and `policy_limit` with coverage code, per-occurrence,
  aggregate, sublimit, deductible, coinsurance, currency, and term-bound dates.
- `policy_limit_state` and `policy_limit_erosion` with idempotent facts,
  latest-source-version aggregation, and `LIMIT_EXCEEDED` protection.
- `policy_endorsement` with automatic numbering, coverage changes, and backdate
  reason/approval enforcement.
- `policy_insured` and `policy_location`, including the named-insured gate
  before `ISSUED`.
- `policy_cancellation` with pro-rata/flat/short-rate return premium and
  `policy_reinstatement` gated by approval and premium evidence.
- Generic `policy_command_idempotency` for all second-slice mutations.
- Billing slice: `policy_billing_plan` with one ACTIVE plan per policy
  (`uq_policy_billing_plan_active`), `policy_installment` with unique
  `(plan_id, installment_number)`, and tenant-scoped GET/POST/PATCH endpoints
  under `/api/v1/policies/{policyId}/billing` (plus the `/internal/v1` alias).
- Equal premium split across 1..24 MONTHLY/QUARTERLY/SEMI_ANNUAL/ANNUAL
  installments, with the rounding remainder on the last installment and the
  sum guaranteed to equal the policy premium.
- PAID/WAIVED settlement with payment evidence, same-status idempotent no-op,
  and `409` when switching between terminal statuses.
- Billing outbox events `policy.billing-plan.created`,
  `policy.installment.paid`, and `policy.installment.waived` written in the
  same transaction as the plan/installment change.
- `LivePostgresSmokeTest` opt-in live PostgreSQL flow through issue, coverage,
  endorsement, erosion, cancellation, and reinstatement.
- Policy renewal slice: nullable `policy.renewed_from_policy_id` lineage with an
  idempotent `ALTER`, `POST /api/v1/policies/{policyId}/renew` (plus the
  `/internal/v1` alias), inherited term defaults, copied coverage/limit/insured/
  location terms, and the `policy.policy.renewed` outbox event written in the
  same transaction.
- Tenant-wide premium receivables read model:
  `GET /api/v1/billing/installments` joins `policy_installment`,
  `policy_billing_plan`, and `policy` to return plan/policy identifiers and the
  insured party, filters by effective `DUE`/`OVERDUE` status and an inclusive
  `dueBefore` date, caps `limit` at 200, and derives overdue status and
  `daysPastDue` without mutating rows.
- Idempotent `idx_policy_installment_tenant_status_due` index on
  `(tenant_id, status, due_date)` for the receivables scan.

### Changed

- `GET /internal/v1/policies` now returns the page envelope instead of a bare
  array and accepts `page`/`size`.
- Illegal lifecycle transitions and duplicate policy numbers are `409` conflicts;
  existing business codes are unchanged.
- Policy versions are issued on `ISSUED` and on every material post-issue change
  (coverage, insured, location, endorsement, cancellation, reinstatement).

### Removed

- The legacy `{timestamp, status, code, message, path, details}` body.
