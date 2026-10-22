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
  date, a per-policy `cancellation_number`, pro-rata return premium, and a
  reinstatement gate that requires an approval and documented premium evidence.
- `policy_billing_plan` and `policy_installment`: one ACTIVE plan per policy,
  an equal premium split across 1..24 installments with the rounding remainder
  on the last row, PAID/WAIVED settlement, and credit application through
  `credited_amount` with a CREDITED terminal status once credits cover the
  gross amount. Each read carries `creditedAmount` and `outstandingAmount`, and
  the plan read includes a paid/waived/credited/outstanding summary.
- `billing_dunning_action`: one reminder/notice/collections action per
  installment and escalation level, with channel, notes, status, and sent time.
  The unique `(tenant_id, installment_id, level)` key is the duplicate-send
  arbiter.
- `policy_credit_note`: policy billing adjustments with a positive
  `numeric(19,4)` amount in the policy currency, an optional 500-character
  reason, a per-policy unique `note_number`, and the DRAFT/ISSUED/APPLIED/
  CANCELLED/REVERSED lifecycle with `issued_at`/`applied_at` stamped by the
  transition that reaches each state. `applied_installment_id` names the open
  installment an APPLIED note was credited against. A nullable `cancellation_id`
  links a return-premium note to the one cancellation it refunds, unique per
  tenant/policy when present.
- `policy_mass_tort_profile`: one profile per policy carrying the tort type,
  trigger theory, allocation method/notes, an inclusive coverage block, and
  DRAFT/COMPLETE status. A COMPLETE profile requires the notes and both block
  dates.
- `policy_program`: the commercial program catalog (`CAPTIVE`, `POOL`,
  `FRONTING`, `COINSURANCE`, `MASS_TORT`) with a tenant-unique program number,
  an inclusive effective range, ACTIVE/CLOSED status, an optional positive
  `capacity_amount` (`numeric(19,4)`) with its three-letter `capacity_currency`,
  and an optional lead policy. `policy.program_id` is a nullable FK to the
  catalog, so a policy can be assigned to or cleared from one program.
- `legal_entity`: the regulated carrier catalog (`INSURER`, `MGA`, `CAPTIVE`,
  `POOL`, `FRONTER`) with a tenant-unique upper-case `code`, name, two-letter
  jurisdiction, optional regulator and license number, and ACTIVE/INACTIVE
  status. `policy.legal_entity_id` is a nullable FK to the catalog, so a policy
  can be assigned to or cleared from one issuing legal entity.
- Policy renewal lineage: `policy.renewed_from_policy_id` points a renewal DRAFT
  at the term it replaces. Renewal copies the source product, insured, coverage
  grants, limits, insureds, and locations into the new term without mutating the
  source policy.
- `outbox_event`: canonical Policy events written in the same transaction as
  each covered mutation (`created`, `updated`, `version-issued`, `endorsed`,
  `premium-audited`, `cancelled`, `reinstated`, `renewed`, `billing-plan.created`,
  `billing-plan.adjusted`, `installment.paid`, `installment.waived`, `dunning.sent`,
  `mass-tort-profile.updated`, `credit-note.created`, `credit-note.issued`,
  `credit-note.applied`, `credit-note.cancelled`, `credit-note.reversed`,
  `program.created`, `policy.program-assigned`, `legal-entity.created`,
  `policy.legal-entity-assigned`), with conditional Quartz HTTP dispatch.

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

Premium audit is exposed under `/api/v1/policies/{policyId}/premium-audit` (the
same controller keeps the `/internal/v1` alias for the domain adapter):

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/policies/{policyId}/premium-audit` | Replace the policy premium with the audited amount on a `BOUND`, `ISSUED`, `IN_FORCE`, `ENDORSED`, or `REINSTATED` policy; `201` with the updated policy and its new `ETag` |

The audit body is `{auditedPremium, effectiveDate, reason, approvedBy?,
approvalRef?}`. `auditedPremium` must be non-negative and must differ from the
current premium (`400 NO_PREMIUM_CHANGE` otherwise), `reason` is mandatory, and
`effectiveDate` is resolved by the version timeline (a date before inception or
a regression of the current version needs `approvedBy` and `approvalRef`). An
accepted audit writes a `PREMIUM_AUDIT` endorsement and version with the signed
premium delta and description `Premium audit: old -> new (delta)`, replaces
`policy.premiumAmount`, bumps the aggregate version, and writes
`policy.policy.premium-audited` (`policyId`, `policyNumber`, `previousPremium`,
`auditedPremium`, `delta`, `currency`, `effectiveDate`, `reason`) plus
`policy.policy.version-issued` in the same transaction.

Billing is also exposed under `/api/v1/policies/{policyId}/billing` (the same
controller keeps the `/internal/v1` alias for the domain adapter):

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/policies/{policyId}/billing` | ACTIVE (else latest) plan, its installments, and the money summary; `404` when none |
| `POST` | `/api/v1/policies/{policyId}/billing` | Create the plan and split the policy premium; `201`, `409` when an ACTIVE plan exists |
| `POST` | `/api/v1/policies/{policyId}/billing/adjustment` | Append one `DUE` installment for the positive `policy.premiumAmount - plan.totalPremium` remainder; `201`, `400 NO_PREMIUM_ADJUSTMENT` when the remainder is not positive, `404` without a plan, `409` for a non-ACTIVE plan or a full 24-installment schedule |
| `PATCH` | `/api/v1/policies/{policyId}/billing/installments/{installmentId}` | Settle as `PAID` or `WAIVED`; same-status replay is a no-op, terminal-status (including `CREDITED`) switch is `409` |

The plan response is `{"plan": {...}, "installments": [...], "summary": {...}}`.
Each installment carries the gross `amount`, the applied `creditedAmount`, and
the derived `outstandingAmount` (zero once PAID, WAIVED, or CREDITED). The
summary carries `totalAmount`, `paidAmount`, `waivedAmount`, `creditedAmount`,
`outstandingAmount`, `overdueAmount`, `currency`, and `installmentCount`;
`outstandingAmount` sums the installment remainders, so waived and fully
credited rows never contribute. Plan creation, settlement, credit application,
and premium adjustment bump the policy aggregate version, so every billing
outbox event has a distinct `aggregateVersion`.

A positive premium adjustment is a `POST` to
`/api/v1/policies/{policyId}/billing/adjustment` with
`{dueDate, reason}`. The command requires an `ACTIVE` plan (`404
BILLING_PLAN_NOT_FOUND` when the policy has none, `409
BILLING_PLAN_NOT_ACTIVE` when the latest plan is closed) and derives the amount
instead of accepting one: `delta = policy.premiumAmount - plan.totalPremium`.
The delta must be positive; zero and negative remainders are `400
NO_PREMIUM_ADJUSTMENT` because a premium decrease belongs to credit notes. A
`dueDate` is required and may not precede the policy effective date (the
effective date itself is allowed), `reason` is required and capped at 500
characters, and the plan may not grow past 24 installments (`409
MAX_INSTALLMENTS_REACHED`). An accepted command appends one `DUE` installment
with the next number and the derived amount, moves `plan.totalPremium` to the
policy premium and `plan.installmentCount` up by one, bumps the policy version,
and returns `201` with `{plan, installment, adjustmentAmount,
newTotalPremium}`. The same transaction writes `policy.billing-plan.adjusted`
with `policyId`, `policyNumber`, `planId`, `adjustmentAmount`,
`newTotalPremium`, `installmentId`, `installmentNumber`, `dueDate`, and
`reason`; the plan update is conditional on the totals the command read, so a
concurrent adjustment or plan closure rolls the appended installment back.

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

The commercial program catalog is exposed under `/api/v1/programs` and the
assignment command under `/api/v1/policies/{policyId}/program` (both keep the
`/internal/v1` alias for the domain adapter):

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/programs` | Tenant-scoped program list, newest first |
| `POST` | `/api/v1/programs` | Create an ACTIVE program; `201` with the generated `programNumber` |
| `GET` | `/api/v1/programs/{id}` | One program; `404 PROGRAM_NOT_FOUND` |
| `PATCH` | `/api/v1/policies/{policyId}/program` | Assign `{programId}` or clear with `{programId:null}`; bumps the policy version and returns the updated policy with its `ETag` |

The create body is `{name, programType, effectiveFrom, effectiveTo,
leadPolicyId?, capacityAmount?, capacityCurrency?}`. `programType` is one of
`CAPTIVE`, `POOL`, `FRONTING`, `COINSURANCE`, or `MASS_TORT` (case-insensitive,
stored upper-case), and the effective range is inclusive, so a one-day program
is valid while an end before the start is a `400 INVALID_PROGRAM_DATES`.
Capacity is optional, but when supplied it must be a positive
`numeric(19,4)` amount with a three-letter ISO 4217 currency; the two fields are
both present or both absent (`400 INVALID_PROGRAM_CAPACITY` otherwise). The
tenant comes from `?tenantId=` or `X-EIA-Tenant`; the route is fixed to the
DIRECT book. Program reads carry the capacity plus the derived `usedAmount`
(sum of `policy.limit_amount` for the tenant's assigned policies),
`utilizationPct` (`usedAmount / capacityAmount * 100`, two decimals, `null`
without capacity), and `policyCount`; a program without policies reports a zero
used amount. Creating a program writes `policy.program.created` against the
Program aggregate; assignment and clearing write
`policy.policy.program-assigned` with `programId` set or `null` in the same
transaction as the policy version bump. A program id from another tenant is a
`404 PROGRAM_NOT_FOUND`.

The legal entity catalog is exposed under `/api/v1/legal-entities` and the
assignment command under `/api/v1/policies/{policyId}/legal-entity` (both keep
the `/internal/v1` alias for the domain adapter):

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/legal-entities` | Tenant-scoped legal entity list, newest first |
| `POST` | `/api/v1/legal-entities` | Create an ACTIVE legal entity; `201` |
| `GET` | `/api/v1/legal-entities/{id}` | One legal entity; `404 LEGAL_ENTITY_NOT_FOUND` |
| `PATCH` | `/api/v1/policies/{policyId}/legal-entity` | Assign `{legalEntityId}` or clear with `{legalEntityId:null}`; bumps the policy version and returns the updated policy with its `ETag` |

The create body is `{code, name, entityType, jurisdiction, regulator?,
licenseNumber?}`. `code` is trimmed, upper-cased, and unique per tenant, so a
duplicate is `409 DUPLICATE_LEGAL_ENTITY_CODE`. `entityType` is one of
`INSURER`, `MGA`, `CAPTIVE`, `POOL`, or `FRONTER` (case-insensitive, stored
upper-case), and `jurisdiction` is a two-letter ISO 3166-1 alpha-2 code (stored
upper-case). `regulator` is capped at 120 characters and `licenseNumber` at 80;
both are optional and stored as `null` when blank. The tenant comes from
`?tenantId=` or `X-EIA-Tenant`; the route is fixed to the DIRECT book. Creating a
legal entity writes `policy.legal-entity.created` against the LegalEntity
aggregate; assignment and clearing write
`policy.policy.legal-entity-assigned` with `legalEntityId` set or `null` in the
same transaction as the policy version bump. A legal entity id from another
tenant is a `404 LEGAL_ENTITY_NOT_FOUND`.

Credit notes are exposed under `/api/v1/policies/{policyId}/credit-notes` (the
same controller keeps the `/internal/v1` alias for the domain adapter):

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/policies/{policyId}/credit-notes` | Credit notes for the policy, newest first |
| `POST` | `/api/v1/policies/{policyId}/credit-notes` | Create a `DRAFT` note; `201` |
| `PATCH` | `/api/v1/policies/{policyId}/credit-notes/{creditNoteId}/status` | Transition to `ISSUED`, `APPLIED`, or `CANCELLED`; `409` for an out-of-order or terminal transition |

The create body is `{amount, currency, reason?, cancellationId?}`. `amount` must
be greater than zero, `currency` must match the policy premium currency
(case-insensitive, stored upper-case), and `reason` is optional, trimmed, and
capped at 500 characters. The tenant comes from `?tenantId=` or `X-EIA-Tenant`,
and the route is fixed to the DIRECT book. A new note is `DRAFT`; `DRAFT` may
move to `ISSUED` or `CANCELLED`, `ISSUED` may move to `APPLIED` or `CANCELLED`,
and `APPLIED` / `CANCELLED` are terminal. `ISSUED` stamps `issuedAt`, `APPLIED`
stamps `appliedAt` and `appliedInstallmentId`, and `CANCELLED` stamps neither.

Supplying `cancellationId` makes the note a cancellation return-premium credit.
The cancellation must belong to the same tenant and policy (`404
CANCELLATION_NOT_FOUND`), be `ACTIVE` (`409 CANCELLATION_NOT_ACTIVE`), and have
an `amount` within one cent of its return premium with the same currency
(`400 CREDIT_NOTE_AMOUNT_MISMATCH` / `400 CURRENCY_MISMATCH`). A cancellation can
fund at most one note: a duplicate is `409 DUPLICATE_CANCELLATION_CREDIT_NOTE`,
enforced by the unique `(tenant_id, policy_id, cancellation_id)` key. The
response carries `cancellationId` and `cancellationNumber`; the number is the
per-policy `CX-{7 digits}` reference, or the cancellation id for a legacy row
that predates numbering.

The status body is `{status, installmentId?}`. `installmentId` is required for
`APPLIED`: the installment must belong to the same tenant and policy, still be
`DUE` or `OVERDUE`, and its uncredited remainder (`amount - creditedAmount`)
must cover the note amount. Applying adds the note amount to
`policy_installment.credited_amount`; the installment becomes `CREDITED` when
the credits reach the gross amount and stays open after a partial credit.
Overpayment is `409 CREDIT_NOTE_EXCEEDS_INSTALLMENT`, a settled/credited target
is `409 INSTALLMENT_NOT_CREDITABLE`, and a missing `installmentId` is
`400 INVALID_CREDIT_NOTE_INSTALLMENT`. `ISSUED` and `CANCELLED` ignore the
optional field. `REVERSED` is reachable only from `APPLIED`, requires a reason,
and is covered below.

Creating a note bumps the policy aggregate version and writes
`policy.credit-note.created` (`policyId`, `policyNumber`, `creditNoteId`,
`noteNumber`, `amount`, `currency`, `status`, `createdAt`) in the same
transaction. A linked note adds `cancellationId` and `cancellationNumber`.
Every accepted transition bumps the policy aggregate version and writes
`policy.credit-note.issued`, `policy.credit-note.applied`, or
`policy.credit-note.cancelled` (`policyId`, `policyNumber`, `creditNoteId`,
`noteNumber`, `amount`, `currency`, `status`) in the same transaction. The
`applied` event additionally carries `installmentId` and the installment's
cumulative `creditedAmount` after the application.

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
`policyNumber`, and `insuredPartyId`. Only open (`DUE`/`OVERDUE`) installments
are returned: PAID, WAIVED, and fully CREDITED rows are excluded. Each item keeps
`amount` gross and carries `creditedAmount` plus the collectible
`outstandingAmount` (`amount - creditedAmount`); both `totalAmount` and
`overdueAmount` sum the outstanding amount, so applied credits reduce what the
queue expects to collect. Overdue is derived at read time: a stored `DUE`
installment past its due date is returned as `OVERDUE` with `daysPastDue > 0`,
and the stored row is never mutated. Each item also carries `lastDunningLevel`
and `lastDunningAt` from the latest `SENT` dunning action for the installment
(both `null` when it has never been dunned; cancelled history is ignored).

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
