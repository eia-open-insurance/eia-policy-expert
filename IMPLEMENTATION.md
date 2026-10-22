# Implementation notes

## Status

The Policy expert implements the first slice (policy header, lifecycle,
idempotent create, ETag/If-Match, canonical problems), the second slice
(versions, coverage and limits, aggregate erosion, endorsements, insureds and
locations, cancellation and reinstatement), a renewal slice (new DRAFT term with
predecessor lineage and copied terms), a billing slice (billing plan,
installment schedule, positive premium adjustment installments, settlement,
dunning actions, and the tenant-wide receivables read model), a mass tort
profile slice (one profile per policy
with tort, trigger, allocation, coverage block, and DRAFT/COMPLETE gating), a
commercial program catalog slice (tenant-unique programs, optional positive
capacity with its currency, derived used/utilization/policy-count reads, plus
the nullable `policy.program_id` assignment), a legal entity catalog slice (tenant-unique
upper-case codes plus the nullable `policy.legal_entity_id` assignment), and a
credit note slice (DRAFT/ISSUED/APPLIED/CANCELLED/REVERSED billing adjustments
with per-policy numbering, cancellation return-premium links, application to open
installments, and credit reversal) from SPEC 11.2. A premium audit command
replaces the policy premium, issues a `PREMIUM_AUDIT` endorsement and version,
and writes `policy.policy.premium-audited` for a bound or in-force term.

Policy writes also append canonical domain events to the platform transactional
outbox (SPEC 7.5.1) in the same database transaction as the aggregate write.

## Structure

| Package area | Responsibility |
|---|---|
| `PolicyController`, `PolicyService`, `PolicyRepository` | Policy aggregate, lifecycle, tenant-scoped reads |
| `PolicyVersionController` / `PolicyVersionService` / `PolicyVersionRepository` | Immutable version history, derived current version, hash verification |
| `CoverageController` / `CoverageService` / `CoverageRepository` | Coverage grants, limit schedules, erosion ledger |
| `EndorsementController` / `EndorsementService` / `EndorsementRepository` | Endorsements and the backdate gate |
| `PolicyPartyController` / `PolicyPartyService` / `PolicyPartyRepository` | Insureds and locations |
| `CancellationController` / `CancellationService` / `CancellationRepository` | Cancellation, return premium, reinstatement |
| `PolicyRenewalController` / `PolicyRenewalService` / `PolicyRenewalRules` | Renewal DRAFT creation, predecessor lineage, term inheritance |
| `PremiumAuditController` / `PremiumAuditService` / `PremiumAuditRules` | Version-timeline-aware premium replacement, `PREMIUM_AUDIT` endorsement/version, and the audit outbox event |
| `BillingController` / `BillingService` / `BillingRepository` | Billing plan, installment schedule, positive premium adjustment installments, PAID/WAIVED settlement, and the paid/waived/credited/outstanding plan summary |
| `BillingDunningController` / `BillingDunningService` / `BillingDunningRepository` | Installment dunning actions, duplicate-level arbitration, and the dunning outbox event |
| `BillingReceivablesController` / `ReceivableRow` / `InstallmentReceivable` | Tenant-wide open-premium receivables read model with derived overdue status, outstanding-after-credit amounts, and latest-SENT dunning enrichment |
| `CreditNoteController` / `CreditNoteService` / `CreditNoteRepository` | Policy credit notes, per-policy numbering, the DRAFT/ISSUED/APPLIED/CANCELLED/REVERSED lifecycle, cancellation return-premium links, application to an open installment, and reversal with credit restore |
| `MassTortProfileController` / `MassTortProfileService` / `MassTortProfileRepository` | One-profile-per-policy upsert, COMPLETE gating, and the profile outbox event |
| `PolicyProgramController` / `PolicyProgramService` / `PolicyProgramRepository` | Commercial program catalog, optional capacity with the derived usage projection, policy assignment/clear, and the program outbox events |
| `LegalEntityController` / `LegalEntityService` / `LegalEntityRepository` | Legal entity catalog, policy assignment/clear, and the legal entity outbox events |
| `PolicyRules`, `VersionTimeline`, `CoverageRules`, `CancellationRules`, `CancellationMath`, `PartyRules`, `EndorsementRules`, `BillingRules`, `CreditNoteRules`, `MassTortProfileRules`, `ProgramRules`, `LegalEntityRules` | Pure invariants with focused unit tests |
| `PolicyVersionHasher`, `VersionIssuer` | Canonical version snapshot hash and issuance |
| `CommandIdempotency` | Per-command replay/conflict behaviour |
| `PolicyEvents`, `PolicyOutboxSchedulingConfiguration` | Canonical event envelopes and the Quartz outbox dispatch job |

## Transactional outbox

The build includes `eia-platform` as a composite build and depends on
`com.eia:libs` for `OutboxRepository`, `OutboxDispatchJob`, and the canonical
envelope types. `outbox_event` is created idempotently in `schema.sql` from the
canonical DDL in `eia-platform/libs/src/main/resources/db/outbox_event.sql`.
Quartz dispatch is registered only when `eia.outbox.enabled=true`; the
`eia.outbox.*` block in `application.yml` carries the endpoint, batch, retry,
lease, interval, and signing settings.

Every covered command inserts its event through `OutboxRepository.insert` from
inside the service `@Transactional` method, so a rollback removes both the
aggregate change and the event row:

| Command | Event(s) |
|---|---|
| `POST /internal/v1/policies` | `policy.policy.created` |
| `PATCH /internal/v1/policies/{id}/status` | `policy.policy.updated`; on `ISSUED` also `policy.policy.version-issued` |
| `POST /internal/v1/policies/{id}/endorsements` | `policy.policy.endorsed` + `policy.policy.version-issued` |
| `POST /internal/v1/policies/{id}/cancel` | `policy.policy.cancelled` + `policy.policy.version-issued` |
| `POST /internal/v1/policies/{id}/reinstate` | `policy.policy.reinstated` + `policy.policy.version-issued` |
| `POST /api/v1/policies/{id}/renew` (also `/internal/v1` alias) | `policy.policy.renewed` |
| `POST /api/v1/policies/{id}/billing` | `policy.billing-plan.created` |
| `POST /api/v1/policies/{id}/billing/adjustment` | `policy.billing-plan.adjusted`; the positive premium remainder is appended as one DUE installment and the plan total/count move in the same transaction |
| `PATCH /api/v1/policies/{id}/billing/installments/{installmentId}` | `policy.installment.paid` or `policy.installment.waived`; same-status replay writes nothing |
| `POST /api/v1/billing/installments/{installmentId}/dunning` (also `/internal/v1` alias) | `policy.dunning.sent`; each accepted action advances the policy version |
| `POST /api/v1/policies/{id}/credit-notes` (also `/internal/v1` alias) | `policy.credit-note.created`; a linked return-premium note adds `cancellationId`/`cancellationNumber`, and the note is created `DRAFT` |
| `PATCH /api/v1/policies/{id}/credit-notes/{creditNoteId}/status` (also `/internal/v1` alias) | `policy.credit-note.issued`, `policy.credit-note.applied`, `policy.credit-note.cancelled`, or `policy.credit-note.reversed`; an APPLIED transition credits the named open installment (or `409`s), a REVERSED transition requires a reason, releases the credit from the recorded installment, and reopens a CREDITED row as DUE/OVERDUE; terminal/out-of-order transitions are `409`, and each accepted transition advances the policy version |
| `PUT /api/v1/policies/{id}/mass-tort-profile` (also `/internal/v1` alias) | `policy.mass-tort-profile.updated`; every accepted upsert writes one event |
| `POST /api/v1/programs` (also `/internal/v1` alias) | `policy.program.created` against the `Program` aggregate |
| `PATCH /api/v1/policies/{id}/program` (also `/internal/v1` alias) | `policy.policy.program-assigned`; `programId` is null when the link is cleared, and every accepted command advances the policy version |
| `POST /api/v1/legal-entities` (also `/internal/v1` alias) | `policy.legal-entity.created` against the `LegalEntity` aggregate |
| `PATCH /api/v1/policies/{id}/legal-entity` (also `/internal/v1` alias) | `policy.policy.legal-entity-assigned`; `legalEntityId` is null when the link is cleared, and every accepted command advances the policy version |

Payloads carry `policyId`, `policyNumber`, `status`, `version`, the policy
effective/expiration dates, premium and limit amounts as scale-4 decimal
strings with their ISO 4217 currencies, and the event-specific ids and facts
(endorsement/cancellation/reinstatement identifiers, version number and hash,
return premium, approval references, and so on). The envelope carries
`book=DIRECT`, `vertical=policy`, `aggregateType=Policy`,
`aggregateVersion=policy.version()+1`, and a deterministic event id.

`policy.program.created` is the exception to the policy-aggregate envelope: the
program is itself a catalog aggregate, so the row carries `aggregateType=Program`,
`aggregateId=programId`, and `aggregateVersion=1`. Its payload carries
`programId`, `programNumber`, `name`, `programType`, the effective dates,
`status`, `leadPolicyId`, and `createdAt`. The assignment event keeps the policy
aggregate and carries `programId` (explicitly `null` on clear) and
`previousProgramId`.

### Event-name mismatch report

`policy.policy.version-issued`, `policy.policy.endorsed`,
`policy.policy.cancelled`, `policy.policy.reinstated`, and
`policy.policy.renewed` match `eia-contracts/asyncapi/policy.yaml`,
`event-catalog.yaml`, and SPEC 11.2.9. The renewed channel is still marked
`x-status: planned` in the contract repository; the publisher now exists here,
so that contract status can move to implemented.

`policy.policy.created` and `policy.policy.updated` do **not** appear in any of
those contracts. The catalog publishes lifecycle names (`quoted`, `bound`,
`issued`, ...) but no create or generic update event. The requested names are
emitted as specified; the catalog needs either these two additions or an agreed
mapping to the existing lifecycle names. This is a contract mismatch owned by
`eia-contracts`, not by this repository.

The billing events (`policy.billing-plan.created`,
`policy.billing-plan.adjusted`, `policy.installment.paid`,
`policy.installment.waived`) use the canonical three-segment envelope form, so
`OutboxEvent.fromEnvelope` and the platform dispatcher accept them unchanged.
They are not yet listed in `eia-contracts/asyncapi/policy.yaml`; the catalog
needs these additive entries. Billing payloads carry `policyId`,
`policyNumber`, and `planId`. Plan creation adds `planNumber` and the full
installment schedule; the adjustment adds `adjustmentAmount`,
`newTotalPremium`, `installmentId`, `installmentNumber`, `dueDate`, and
`reason`; settlement adds `planNumber` and the settled installment facts
(`installmentId`, `number`, `amount`, `currency`, `status`, `paymentId`).

`policy.dunning.sent` follows the same three-segment form and carries
`policyId`, `policyNumber`, `installmentId`, `level`, `channel`, `sentAt`, and
`status`. Dunning is allowed only while the installment's effective status is
DUE or OVERDUE; one action per `(tenant, installment, level)` is enforced by
the table's unique constraint, so a race surfaces as `409 DUNNING_ALREADY_SENT`.
The event name is additive and still needs a contract entry.

The mass tort profile event uses the canonical
`policy.mass-tort-profile.updated` name already declared in
`eia-contracts/asyncapi/policy.yaml` and `event-catalog.yaml`; its payload is
`policyId`, `policyNumber`, the profile `status`, `tortType`, `triggerType`,
`allocationMethod`, and the ISO-8601 `coverageBlockStart`/`coverageBlockEnd`
(nullable for a DRAFT profile). The channel is still marked planned in the
contract repository.

The program catalog events are additive. `policy.program.created` is emitted
against the `Program` aggregate and `policy.policy.program-assigned` against the
policy; neither name is listed in `eia-contracts/asyncapi/policy.yaml` yet. The
contract repository also still marks `GET /api/v1/programs` as planned; the
expert now exposes the create/list/get surface plus the policy assignment
command.

The legal entity events are additive too. `policy.legal-entity.created` is
emitted against the `LegalEntity` aggregate (`aggregateVersion=1`, payload
`legalEntityId`, `code`, `name`, `entityType`, `jurisdiction`, `regulator`,
`licenseNumber`, `status`, `createdAt`) and
`policy.policy.legal-entity-assigned` against the policy (`legalEntityId` and
`previousLegalEntityId`, explicitly null on clear, plus `changedFields`).
Neither name is listed in `eia-contracts/asyncapi/policy.yaml` or
`event-catalog.yaml` yet, and the contract still marks `/legal-entities` as
planned.

The credit note events are additive too: `policy.credit-note.created`,
`policy.credit-note.issued`, `policy.credit-note.applied`,
`policy.credit-note.cancelled`, and `policy.credit-note.reversed` are not listed
in `eia-contracts/asyncapi/policy.yaml` or `event-catalog.yaml` yet. Their
payload carries `policyId`, `policyNumber`, `creditNoteId`, `noteNumber`,
`amount` (scale-4 decimal string), `currency`, and the new `status`; the
`created` event adds `createdAt` and, for a return-premium note, the optional
`cancellationId` and `cancellationNumber`. The
`applied` event additionally carries `installmentId` and the installment's
cumulative `creditedAmount` after the application; `issued` and `cancelled`
never name an installment. The `reversed` event payload is the reversal
contract: `creditNoteId`, `noteNumber`, `installmentId` (explicitly null for a
legacy APPLIED note), `amount`, `reason` (the required reversal reason), and
`status`.

## Cancellation return-premium credit notes

`policy_credit_note.cancellation_id` is a nullable link to
`policy_cancellation`; `uq_policy_credit_note_cancellation` on
`(tenant_id, policy_id, cancellation_id)` allows any number of unlinked notes
and at most one note per cancellation. The DDL is additive and idempotent:
`ADD COLUMN IF NOT EXISTS` plus a dropped/re-added unique constraint.

`POST .../credit-notes` accepts an optional `cancellationId`. The service
resolves it with a tenant-and-policy-scoped read, so a cancellation from another
policy or tenant, or an unknown id, is `404 CANCELLATION_NOT_FOUND`. The
cancellation must still be `ACTIVE` (the final status written by the cancel
command; `REINSTATED` and `VOID` are `409 CANCELLATION_NOT_ACTIVE`), and the note
`amount` must equal `return_premium_amount` within 0.01 with the same currency
(`400 CREDIT_NOTE_AMOUNT_MISMATCH` / `400 CURRENCY_MISMATCH`). A duplicate link is
`409 DUPLICATE_CANCELLATION_CREDIT_NOTE`; the unique key arbitrates a concurrent
duplicate.

`policy_cancellation` also gains an additive, nullable
`cancellation_number` (`CX-{7 digits}`, per-policy unique via
`uq_policy_cancellation_number`) generated from
`policy_cancellation_number_seq`. New cancellations always carry one. A credit
note exposes `cancellationId` and `cancellationNumber`; for a historical
cancellation without a number, reads and the create response fall back to the
cancellation id so the link is still visible.

The accepted create writes `policy.credit-note.created` in the same transaction
as the note row and the policy-version bump, so a rejected or rolled-back link
leaves neither a note nor an event behind.

`policy.policy.version-issued` is emitted by the issue, endorsement,
cancellation, and reinstatement commands. Coverage/insured/location changes can
also issue a version, but those writes are outside the requested event set and
remain event-free in this slice.

## Credit application and outstanding amounts

`policy_installment.credited_amount` starts at zero and accumulates applied
credit notes (`ck_policy_installment_credited` keeps it between zero and the
gross amount). `policy_credit_note.applied_installment_id` records the
installment an APPLIED note settled against; it is nullable for historical
APPLIED notes and for notes that never applied, and a REVERSED note retains it
for audit. `CREDITED` is a terminal installment status, reached only when the
credits cover the gross amount.

Applying is an ISSUED -&gt; APPLIED transition that also requires
`installmentId`. The rules reject a missing id (`400
INVALID_CREDIT_NOTE_INSTALLMENT`), a missing/cross-tenant/cross-policy
installment (`404 INSTALLMENT_NOT_FOUND`), a PAID/WAIVED/CREDITED target (`409
INSTALLMENT_NOT_CREDITABLE`), and an amount above
`amount - credited_amount` (`409 CREDIT_NOTE_EXCEEDS_INSTALLMENT` with
`noteAmount`/`availableAmount` meta). The conditional installment UPDATE
re-checks the open status and the uncredited remainder, so a concurrent credit
cannot overdraw the row; the note transition rolls back with it.

Reversing is an APPLIED -&gt; REVERSED transition that requires a `reason`
(trimmed, at most 500 characters; `400 INVALID_CREDIT_NOTE_REASON` when missing
or too long) and is a `409 INVALID_CREDIT_NOTE_TRANSITION` for any other
current status. When the note carries an `applied_installment_id`, the reversal
decrements that installment's `credited_amount` by the note amount (clamped at
zero) and, if the row was `CREDITED` and the new credited total is below its
gross amount, restores `DUE` when the due date is today or later and `OVERDUE`
when it is already past. The update is conditional on the credited amount and
status read by the command, so a concurrent billing change rolls the reversal
back. A legacy APPLIED note without an installment is only marked REVERSED.
`policy_credit_note` stores `reversed_at` and `reversal_reason`; the status
check admits REVERSED and `ck_policy_credit_note_reversed` requires the stamp.
The billing summary and the tenant-wide receivables read then reflect the
restored credit (higher outstanding/overdue totals, and a reopened row returns
to the worklist).

Read models derive `outstandingAmount` as `amount - creditedAmount` for an open
DUE/OVERDUE row and zero for PAID/WAIVED/CREDITED. The billing plan read exposes
per-installment `creditedAmount`/`outstandingAmount` plus a summary
(`totalAmount`, `paidAmount`, `waivedAmount`, `creditedAmount`,
`outstandingAmount`, `overdueAmount`, `currency`, `installmentCount`) whose
outstanding total is the sum of the row remainders. The tenant-wide receivables
read keeps `amount` gross but sums `outstandingAmount` into `totalAmount` and
`overdueAmount`, and it excludes fully CREDITED rows along with PAID/WAIVED.

## Positive premium adjustment installments

`POST /api/v1/policies/{policyId}/billing/adjustment` (and the `/internal/v1`
alias) accepts `{dueDate, reason}` and bills the positive gap between the policy
written premium and the ACTIVE plan total: `delta = policy.premiumAmount -
plan.totalPremium`. It is a 404 `BILLING_PLAN_NOT_FOUND` when the policy has no
plan and a 409 `BILLING_PLAN_NOT_ACTIVE` when the latest plan is closed. A zero
or negative delta is a 400 `NO_PREMIUM_ADJUSTMENT` with `delta`,
`policyPremiumAmount`, and `planTotalPremium` metadata, because a premium
decrease is a credit-note concern. The 24-installment ceiling is checked before
the money remainder, so a full schedule reports 409
`MAX_INSTALLMENTS_REACHED`. `dueDate` is required and may not precede the policy
effective date (the effective date itself is accepted, which allows an inception
adjustment); `reason` is required, trimmed, and capped at 500 characters.

The accepted command appends one DUE installment with the derived amount, the
next installment number, and the supplied due date, then applies the plan change
with one conditional UPDATE that requires the plan to still be ACTIVE and its
`total_premium`/`installment_count` to equal the values the command read. A
concurrent adjustment or closure loses that comparison and rolls the appended
installment back; the `(plan_id, installment_number)` unique key is the second
arbiter. The response is 201 with `{plan, installment, adjustmentAmount,
newTotalPremium}`. The policy version bump and
`policy.billing-plan.adjusted` (`policyId`, `policyNumber`, `planId`,
`adjustmentAmount`, `newTotalPremium`, `installmentId`, `installmentNumber`,
`dueDate`, `reason`) commit in the same transaction.

## Verification

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-22.0.2.9-hotspot'
.\gradlew.bat build
$env:EIA_LIVE_PG='true'
.\gradlew.bat test --tests '*LivePostgresSmokeTest*'
```

`build` runs the JUnit suite (rules, services, controllers, schema contracts).
The live smoke is opt-in and requires PostgreSQL.

## Known SPEC gaps in this slice

- Billing exposure starts with the plan, installment schedule, positive premium
  adjustment installments, PAID/WAIVED settlement required by this slice,
  dunning actions, and the tenant-wide receivables read model. Plan `COMPLETE`/`CANCELLED` transitions, dunning
  cancellation/retry, billing instructions, and SAP AR reconciliation
  (SPEC 11.2.13) are not exposed yet.
- Mass tort coverage blocks, occurrences, layers, forms, exclusions, rating
  exposures, and the claim/cession/reinsurance projections are not implemented.
  The profile itself is exposed, but the separate `policy_coverage_block` table
  is still a gap.
- The program catalog is create/list/get plus policy assignment only. Capacity
  is a single optional amount/currency pair on the program; the SPEC 11.2.16
  `program_version`/`program_capacity` provider shares, fronting/coinsurance
  structures, and capacity-share sum rule are not modelled. Utilization is
  derived from assigned policy limits, not from capacity-provider shares.
  Program lifecycle transitions (CLOSED), program edits, and lead-policy
  integrity are not exposed: `lead_policy_id` is stored as an opaque UUID
  without a FK.
- The legal entity catalog is create/list/get plus policy assignment only.
  Entity edits, ACTIVE/INACTIVE retirement, legal-entity relationships,
  organization units, and the SPEC 10.11 NAIC/LEI/SAP company-code and
  `external_code_map` columns are not exposed. `jurisdiction` is a two-letter
  code as requested by this slice, while the wider SPEC model uses
  `domicile_country` (ISO 3166-1 alpha-3).
- Credit notes expose the DRAFT/ISSUED/APPLIED/CANCELLED/REVERSED lifecycle,
  application to one open installment, and full reversal of an APPLIED note.
  There is no partial reversal or unapply-by-amount command, no GL/AR posting,
  and no idempotency-key replay for note creation. A note can only settle the
  installment it names; it is not allocated across multiple installments or
  receivables. A cancellation can be refunded by exactly one return-premium
  note, so a split or partial cancellation credit is not supported.
- Coverage/limit rows are keyed by coverage code and unique active code; the
  full `coverage`, `coverage_type`, layer, and form reference tables from
  SPEC 10.11 are not loaded.
- Aggregate erosion is driven by the explicit `/erosion` endpoint; there is no
  event dispatcher or consumer for `direct.claim.*` events yet.
- Version windows use one effective date plus a supersession cutover. Renewal
  creates a new DRAFT term with predecessor lineage, but the daily
  `policy.renewal-batch`, renewal invitations, and new-term approval paths
  (SPEC POL-07) are not exposed.
- `policy` does not yet denormalize `cancelled_at`, `cancellation_effective_date`,
  or coverage totals; those live on the dedicated second-slice tables.
- Legacy business error codes remain instead of the `EIA-XXX-NNNN` scheme
  (tracked as M-02 in `eia-contracts/MISMATCHES.md`).
- No authentication, authorization, or rate limiting.
- Outbox dispatch is HTTP-only (no broker, ADR-0004). There is no subscriber or
  subscription registry in this slice; enabling dispatch requires
  `EIA_OUTBOX_ENDPOINT` and an HMAC signing secret for production.
- Support for multiple concurrent `DRAFT` versions, corrections that void a
  version, and the `VOID` change type is schema-ready but not exposed.
- Premium audit is a versioned policy command only. The SPEC
  `policy_premium_audit` worksheet (audit periods, audit type,
  reported exposure, deposit/rated/additional/return premium, and
  SCHEDULED/IN_REVIEW/FINALIZED statuses) and the quarterly
  `policy.premium-audit-batch` are not implemented.
