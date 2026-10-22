# Changelog

All notable changes to this repository are documented here.

The format is based on Keep a Changelog and the project uses Semantic Versioning.

## [Unreleased]

### Added

- Repository baseline.
- Policy expert slice: PostgreSQL-backed `policy` table, create/list/get/status endpoints, lifecycle and money invariants, structured errors, and JUnit tests.
- Canonical RFC 9457 `application/problem+json` errors with
  `errors[{field, code, message}]`, `meta`, and correlation ids.
- `policy_idempotency` table scoped by
  `(tenant_id, operation, idempotency_key)` with transactional create replay and
  stored-response replay for policy rating and submission conversion.
- Page envelope `{items, page, size, total, _links}` for the internal list with
  `updated_at DESC, id ASC` ordering.
- `ETag` on detail and mutation responses plus `If-Match` concurrency control.
- `Idempotency-Key` support on create with `Idempotent-Replayed` response
  headers.
- Stored-response `Idempotency-Key` replay on policy rating and submission
  conversion: the first call is `201`, a replay is `200` with the stored
  worksheet/policy body and ETag, the key is capped at 128 characters, and the
  scope is tenant plus operation.
- Canonical request hashes on rating and submission conversion idempotency
  rows: a replay of the same canonical request returns the stored response,
  while a different base rate, premium, or request under the same key is `409
  IDEMPOTENCY_CONFLICT` with `meta.operation`/`meta.key` and writes nothing.
  Decimal scale and surrounding whitespace are normalized before hashing, and
  the hash carries the policy or submission id so a key cannot cross resources.
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
- Optional program capacity: idempotent `policy_program.capacity_amount`
  (`numeric(19,4)`) and `capacity_currency` (`char(3)`) columns with positive
  amount, three-letter currency, and both-or-neither checks. Create accepts
  `capacityAmount`/`capacityCurrency`, rejects non-positive amounts and bad
  currencies, and stores the pair only when both are supplied.
- Program reads now carry `capacityAmount`, `capacityCurrency`, `usedAmount`
  (sum of `policy.limit_amount` for the tenant's assigned policies, zero when
  none), `utilizationPct` (`usedAmount / capacityAmount * 100`, two decimals,
  `null` without capacity), and `policyCount`; list uses one grouped usage
  query, and get/create return the same projection.
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
- Credit note application: `policy_installment.credited_amount` (numeric(19,4),
  default 0, bounded by the gross amount), the `CREDITED` installment status,
  and `policy_credit_note.applied_installment_id`. `PATCH .../status` accepts an
  optional `installmentId`, required for `APPLIED`; the note amount must fit the
  installment's uncredited remainder, a full credit moves the row to `CREDITED`,
  and the note/installment/policy-version/outbox writes share one transaction.
  The applied event adds `installmentId` and the cumulative `creditedAmount`.
- Billing plan reads now carry a `summary` and per-installment
  `creditedAmount`/`outstandingAmount`; the summary totals paid, waived,
  credited, outstanding, and overdue money. The receivables read keeps `amount`
  gross, adds the same two credit fields, excludes `CREDITED` rows, and sums
  `outstandingAmount` into `totalAmount`/`overdueAmount`.
- Credit note reversal: the `REVERSED` status plus idempotent
  `reversed_at`/`reversal_reason` columns and an updated status check on
  `policy_credit_note`. `PATCH .../status` accepts `REVERSED` only from
  `APPLIED`, requires a reason of at most 500 characters, retains
  `applied_installment_id` for audit, releases the note amount from the credited
  installment (clamped at zero), reopens a `CREDITED` row as `DUE`/`OVERDUE`
  depending on its due date, and leaves a legacy APPLIED note without an
  installment as a status/reason-only reversal.
- `policy.credit-note.reversed` outbox event (`creditNoteId`, `noteNumber`,
  `installmentId`, `amount`, `reason`, `status`) written in the same transaction
  as the reversal and policy version bump; billing summaries and receivables
  reflect the restored credit.
- Cancellation return-premium credit notes: nullable
  `policy_credit_note.cancellation_id` with idempotent DDL and the
  `uq_policy_credit_note_cancellation` unique key
  (`(tenant_id, policy_id, cancellation_id)`), so an unlinked note stays
  valid while a cancellation funds at most one note.
- `CreateCreditNoteRequest` accepts an optional `cancellationId`. The
  cancellation is resolved on the same tenant/policy (`404
  CANCELLATION_NOT_FOUND`), must still be `ACTIVE` (`409
  CANCELLATION_NOT_ACTIVE`), and its return premium must match the note amount
  within 0.01 in the same currency (`400 CREDIT_NOTE_AMOUNT_MISMATCH` / `400
  CURRENCY_MISMATCH`). A duplicate link is `409
  DUPLICATE_CANCELLATION_CREDIT_NOTE`.
- `CreditNote` exposes `cancellationId` and `cancellationNumber`; the new
  additive `policy_cancellation.cancellation_number` is a per-policy
  `CX-{7 digits}` reference generated from
  `policy_cancellation_number_seq` (the cancellation id is the fallback for a
  legacy row without a number).
- `policy.credit-note.created` outbox event (`policyId`, `policyNumber`,
  `creditNoteId`, `noteNumber`, `amount`, `currency`, `status`, `createdAt`)
  written in the same transaction as the note and policy version bump; a linked
  note adds `cancellationId` and `cancellationNumber`.
- Premium audit command: `POST /api/v1/policies/{policyId}/premium-audit` (and
  the `/internal/v1` alias) accepts `{auditedPremium, effectiveDate, reason,
  approvedBy?, approvalRef?}` on a `BOUND`, `ISSUED`, `IN_FORCE`, `ENDORSED`, or
  `REINSTATED` policy. The audited premium must be non-negative and must change
  the policy premium (`400 NO_PREMIUM_CHANGE`); the reason is mandatory; the
  effective date is resolved by `VersionTimeline`, including the backdate
  reason/approval gate.
- A premium audit writes a `PREMIUM_AUDIT` endorsement and `policy_version`
  with the signed delta and description
  `Premium audit: old -> new (delta)`, replaces `policy.premium_amount`, bumps
  the aggregate version, and writes `policy.policy.premium-audited`
  (`policyId`, `policyNumber`, `previousPremium`, `auditedPremium`, `delta`,
  `currency`, `effectiveDate`, `reason`) plus `policy.policy.version-issued` in
  the same transaction. The endorsement type check is migrated idempotently for
  existing databases.
- Positive premium adjustment installments:
  `POST /api/v1/policies/{policyId}/billing/adjustment` (and the `/internal/v1`
  alias) accepts `{dueDate, reason}` and derives
  `delta = policy.premiumAmount - netPlanPremium`, where the plan's net premium
  is its total less the sum of applied installment credits, floored at zero. It
  requires an ACTIVE plan (`404 BILLING_PLAN_NOT_FOUND` when none, `409
  BILLING_PLAN_NOT_ACTIVE` when the plan is closed), rejects a non-positive
  delta with `400 NO_PREMIUM_ADJUSTMENT` (a decrease uses credit notes),
  validates the due date against the policy effective date and the required
  500-character-capped reason, and enforces the 24-installment ceiling (`409
  MAX_INSTALLMENTS_REACHED`).
- An accepted adjustment appends one `DUE` installment with the next number and
  the derived amount, moves `plan.totalPremium` up by the delta (gross) and
  `plan.installmentCount` up by one with a conditional plan UPDATE that also
  checks the credited total the command read, bumps the policy version, returns
  `201` with `{plan, installment, adjustmentAmount, newTotalPremium}`, and
  writes `policy.billing-plan.adjusted` (`policyId`, `policyNumber`, `planId`,
  `adjustmentAmount`, `newTotalPremium`, `installmentId`, `installmentNumber`,
  `dueDate`, `reason`) in the same transaction.

- Underwriting submission slice: idempotent `underwriting_submission` table with
  a tenant-unique `submission_number`, applicant/product/requested term and
  limit, a PostgreSQL `text[]` of tort codes, optional notes, a `version`, and
  the `RECEIVED`/`REFERRED`/`QUOTED`/`BOUND`/`DECLINED`/`WITHDRAWN` status
  check. `submission_number` is `SUB-{YYYY}-{7 digits}` from the locked
  per-tenant `underwriting_submission_number_seq` counter, so numbering restarts
  at one per tenant and concurrent creates cannot collide.
- `GET`/`POST /api/v1/policies/submissions` and
  `GET /api/v1/policies/submissions/{id}` (each also under `/internal/v1`) with
  tenant-header/query scoping, the DIRECT-book gate, newest-update-first
  ordering, case-insensitive `q` across submission number/product/notes, and an
  optional `status` filter.
- `PATCH /api/v1/policies/submissions/{id}/status` accepts `{status, reason?}`
  and enforces the one-way graph `RECEIVED -> REFERRED/QUOTED/DECLINED/WITHDRAWN`,
  `REFERRED -> QUOTED/DECLINED/WITHDRAWN`, and
  `QUOTED -> BOUND/DECLINED/WITHDRAWN`, with `BOUND`/`DECLINED`/`WITHDRAWN`
  terminal. Out-of-order/terminal transitions are `409
  INVALID_SUBMISSION_TRANSITION`; a concurrent change is `409
  CONCURRENT_MODIFICATION`; every accepted transition bumps the version and
  returns the new `ETag`.
- Submission validation caps product code at 64, the requested-limit currency at
  three letters, the limit at non-negative, tort codes at 20 entries of 32
  characters, notes at 2000 characters, and the optional reason at 500
  characters.
- `policy.submission.created` and `policy.submission.status-changed` outbox
  events written in the same transaction as the submission row; the
  status-changed payload carries the previous/new status, version, updated time,
  and optional reason.
- Nullable `policy.submission_id` with an idempotent `ALTER`, an FK to
  `underwriting_submission`, and a tenant-scoped unique constraint so a
  submission can produce at most one policy.
- `POST /api/v1/policies/submissions/{id}/convert` (also `/internal/v1`) accepts
  `{premiumAmount, premiumCurrency, expirationDate?}` and creates a `DRAFT`
  policy from the quoted submission: applicant/product/requested effective
  date/requested limit, premium and currency from the command, and expiration
  defaulting to effective + one year. Conversion allocates the normal policy
  number, writes the initial DRAFT version, initializes one base coverage and
  limit with the existing coverage rules, links `submissionId`, moves the
  submission to `BOUND`, bumps both versions, and emits
  `policy.submission.converted {submissionId, submissionNumber, policyId,
  policyNumber}`. Only `QUOTED`/`BOUND` are convertible (`409
  SUBMISSION_NOT_CONVERTIBLE`); a retry after success returns the linked policy
  with `200 Idempotent-Replayed: true`.
- The submission projection exposes `policyId` once conversion has linked a
  policy.

### Fixed

- Positive premium adjustments compare the policy premium with the billing
  plan's net premium instead of its gross total. The plan total is reduced by
  the sum of `policy_installment.credited_amount` for the plan (floored at
  zero), so a credited schedule no longer fails with
  `NO_PREMIUM_ADJUSTMENT` when the real variance is positive; the appended
  installment and the gross plan-total increase use that net-derived delta.

### Changed

- `GET /internal/v1/policies` now returns the page envelope instead of a bare
  array and accepts `page`/`size`.
- Illegal lifecycle transitions and duplicate policy numbers are `409` conflicts;
  existing business codes are unchanged.
- Policy versions are issued on `ISSUED` and on every material post-issue change
  (coverage, insured, location, endorsement, cancellation, reinstatement).
- Issuing a `BOUND` policy that was already premium-audited supersedes the
  audit's `PREMIUM_AUDIT` version first, so the policy keeps exactly one
  `ISSUED` version.

### Removed

- The legacy `{timestamp, status, code, message, path, details}` body.
