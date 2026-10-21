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
  `GET /api/v1/billing/installments` (plus the `/internal/v1` alias for the
  Policy domain adapter) joins `policy_installment`, `policy_billing_plan`, and
  `policy` to return plan/policy identifiers and the insured party, filters by
  effective `DUE`/`OVERDUE` status and an inclusive `dueBefore` date, caps
  `limit` at 200, and derives overdue status and `daysPastDue` without mutating
  rows.
- Idempotent `idx_policy_installment_tenant_status_due` index on
  `(tenant_id, status, due_date)` for the receivables scan.
- Mass tort profile slice: idempotent `policy_mass_tort_profile` table with one
  profile per policy (`uq_policy_mass_tort_profile_policy`), a `DRAFT`/`COMPLETE`
  status, tort/trigger/allocation vocabularies, an inclusive coverage block, and
  a 2000-character allocation-notes cap.
- `GET`/`PUT /api/v1/policies/{policyId}/mass-tort-profile` (plus the
  `/internal/v1` alias) with tenant-header/query scoping, a DIRECT-book gate, a
  `201` create / `200` update upsert, and `COMPLETE` validation requiring the
  allocation notes and both coverage-block dates.
- `policy.mass-tort-profile.updated` outbox event written in the same
  transaction as the profile upsert and policy version bump.
- Commercial program catalog slice: idempotent `policy_program` table with a
  tenant-unique `program_number`, `CAPTIVE`/`POOL`/`FRONTING`/`COINSURANCE`/
  `MASS_TORT` type vocabulary, `ACTIVE`/`CLOSED` status, an inclusive effective
  range, and an optional lead policy.
- Nullable `policy.program_id` FK with an idempotent `ALTER`, a startup-safe FK
  redefinition, and `(tenant_id, program_id)` index; the policy API projection
  now carries `programId` on list, get, and mutation responses.
- `POST`/`GET /api/v1/programs` and `GET /api/v1/programs/{id}` plus
  `PATCH /api/v1/policies/{policyId}/program` (each also under `/internal/v1`)
  with tenant-header/query scoping and the DIRECT-book gate. A null `programId`
  clears the policy link, a foreign program is `404 PROGRAM_NOT_FOUND`, and
  assignment/clear bumps the policy version and returns the new `ETag`.
- `policy.program.created` (Program aggregate) and
  `policy.policy.program-assigned` (policy aggregate, `programId` set or null)
  outbox events written in the same transaction as the catalog row or policy
  assignment.
- Legal entity catalog slice: idempotent `legal_entity` table with a
  tenant-unique upper-case `code`, `INSURER`/`MGA`/`CAPTIVE`/`POOL`/`FRONTER`
  type vocabulary, a two-letter jurisdiction, optional regulator/license
  fields, and `ACTIVE`/`INACTIVE` status.
- Nullable `policy.legal_entity_id` FK with an idempotent `ALTER`, a
  startup-safe FK redefinition, and `(tenant_id, legal_entity_id)` index; the
  policy API projection now carries `legalEntityId` on list, get, and mutation
  responses.
- `POST`/`GET /api/v1/legal-entities` and `GET /api/v1/legal-entities/{id}`
  plus `PATCH /api/v1/policies/{policyId}/legal-entity` (each also under
  `/internal/v1`) with tenant-header/query scoping and the DIRECT-book gate. A
  null `legalEntityId` clears the policy link, a foreign legal entity is `404
  LEGAL_ENTITY_NOT_FOUND`, and assignment/clear bumps the policy version and
  returns the new `ETag`.
- `policy.legal-entity.created` (LegalEntity aggregate) and
  `policy.policy.legal-entity-assigned` (policy aggregate, `legalEntityId` set
  or null) outbox events written in the same transaction as the catalog row or
  policy assignment.
- Policy credit note slice: idempotent `policy_credit_note` table with a
  positive `numeric(19,4)` amount in the policy currency, an optional
  500-character reason, a per-policy unique `note_number`
  (`uq_policy_credit_note_number`), and the DRAFT/ISSUED/APPLIED/CANCELLED
  lifecycle with `issued_at`/`applied_at` columns and status/timestamp checks.
- `GET`/`POST /api/v1/policies/{policyId}/credit-notes` and
  `PATCH /api/v1/policies/{policyId}/credit-notes/{creditNoteId}/status` (each
  also under `/internal/v1`) with tenant-header/query scoping, the DIRECT-book
  gate, newest-first listing, `201` DRAFT creation, amount/currency/reason
  validation, and `409` for out-of-order or terminal transitions.
- `policy.credit-note.issued`, `policy.credit-note.applied`, and
  `policy.credit-note.cancelled` outbox events (`policyId`, `policyNumber`,
  `creditNoteId`, `noteNumber`, `amount`, `currency`, `status`) written in the
  same transaction as the status change and policy version bump.

### Changed

- `GET /internal/v1/policies` now returns the page envelope instead of a bare
  array and accepts `page`/`size`.
- Illegal lifecycle transitions and duplicate policy numbers are `409` conflicts;
  existing business codes are unchanged.
- Policy versions are issued on `ISSUED` and on every material post-issue change
  (coverage, insured, location, endorsement, cancellation, reinstatement).

### Removed

- The legacy `{timestamp, status, code, message, path, details}` body.
