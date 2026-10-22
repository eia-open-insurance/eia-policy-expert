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
upper-case codes plus the nullable `policy.legal_entity_id` assignment), a
product catalog slice (tenant-unique upper-case codes, the requested five
product lines, ACTIVE/RETIRED status, active-product enforcement on new
policies and submissions, and an idempotent demo-tenant seed), and a
credit note slice (DRAFT/ISSUED/APPLIED/CANCELLED/REVERSED billing adjustments
with per-policy numbering, cancellation return-premium links, application to open
installments, and credit reversal), and an underwriting submission slice
(per-tenant numbered intake records with a one-way RECEIVED-to-terminal status
graph and the submission outbox events) from SPEC 11.2. A premium audit command
replaces the policy premium, issues a `PREMIUM_AUDIT` endorsement and version,
and writes `policy.policy.premium-audited` for a bound or in-force term. A rating
worksheet command calculates and persists a reproducible premium for a DRAFT or
QUOTED policy with `RATE_TIMES_EXPOSURE` or `FLAT`, and optionally applies it to
the policy premium with `policy.policy.rated`.

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
| `PolicyRatingController` / `PolicyRatingService` / `RatingWorksheetRepository` / `RatingRules` | DRAFT/QUOTED rating worksheets for `RATE_TIMES_EXPOSURE` and `FLAT`, the canonical SHA-256, optional premium application, and the rated outbox event |
| `BillingController` / `BillingService` / `BillingRepository` | Billing plan, installment schedule, positive premium adjustment installments, PAID/WAIVED settlement, and the paid/waived/credited/outstanding plan summary |
| `BillingDunningController` / `BillingDunningService` / `BillingDunningRepository` | Installment dunning actions, duplicate-level arbitration, and the dunning outbox event |
| `BillingReceivablesController` / `ReceivableRow` / `InstallmentReceivable` | Tenant-wide open-premium receivables read model with derived overdue status, outstanding-after-credit amounts, and latest-SENT dunning enrichment |
| `CreditNoteController` / `CreditNoteService` / `CreditNoteRepository` | Policy credit notes, per-policy numbering, the DRAFT/ISSUED/APPLIED/CANCELLED/REVERSED lifecycle, cancellation return-premium links, application to an open installment, and reversal with credit restore |
| `MassTortProfileController` / `MassTortProfileService` / `MassTortProfileRepository` | One-profile-per-policy upsert, COMPLETE gating, and the profile outbox event |
| `PolicyProgramController` / `PolicyProgramService` / `PolicyProgramRepository` | Commercial program catalog, optional capacity with the derived usage projection, policy assignment/clear, and the program outbox events |
| `LegalEntityController` / `LegalEntityService` / `LegalEntityRepository` | Legal entity catalog, policy assignment/clear, and the legal entity outbox events |
| `ProductController` / `ProductService` / `ProductRepository` / `ProductEvents` | Product catalog, q/status reads, ACTIVE/RETIRED status changes, and the product outbox events |
| `ProductCatalogGuard` | ACTIVE-product check shared by policy and submission create; 422 `UNKNOWN_PRODUCT`/`PRODUCT_NOT_ACTIVE` with `productCode` and `meta` |
| `ProductSeeder` / `ProductSeedRunner` | Idempotent demo-tenant product seed and its conditional startup hook |
| `UnderwritingSubmissionController` / `UnderwritingSubmissionService` / `UnderwritingSubmissionRepository` / `UnderwritingSubmissionNumberAllocator` | Tenant-numbered underwriting submissions, q/status reads, the one-way status graph, and the submission outbox events |
| `SubmissionConversionService` / `ConvertSubmissionRequest` / `SubmissionConversionResult` | Quoted/bound submission conversion into a DRAFT policy, the `policy.submission_id` link, derived base coverage/limit, and idempotent replay of the linked policy |
| `PolicyRules`, `VersionTimeline`, `CoverageRules`, `CancellationRules`, `CancellationMath`, `PartyRules`, `EndorsementRules`, `BillingRules`, `CreditNoteRules`, `MassTortProfileRules`, `ProgramRules`, `LegalEntityRules`, `RatingRules`, `UnderwritingSubmissionRules` | Pure invariants with focused unit tests |
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
| `POST /api/v1/policies/{id}/rating` (also `/internal/v1` alias) | `policy.policy.rated` when `apply` is true; the worksheet, premium replacement, and policy version bump commit together, while a calculation-only request writes no event |
| `PUT /api/v1/policies/{id}/mass-tort-profile` (also `/internal/v1` alias) | `policy.mass-tort-profile.updated`; every accepted upsert writes one event |
| `POST /api/v1/programs` (also `/internal/v1` alias) | `policy.program.created` against the `Program` aggregate |
| `PATCH /api/v1/policies/{id}/program` (also `/internal/v1` alias) | `policy.policy.program-assigned`; `programId` is null when the link is cleared, and every accepted command advances the policy version |
| `POST /api/v1/legal-entities` (also `/internal/v1` alias) | `policy.legal-entity.created` against the `LegalEntity` aggregate |
| `PATCH /api/v1/policies/{id}/legal-entity` (also `/internal/v1` alias) | `policy.policy.legal-entity-assigned`; `legalEntityId` is null when the link is cleared, and every accepted command advances the policy version |
| `POST /api/v1/products` (also `/internal/v1` alias) | `product.product.created` against the `Product` aggregate |
| `PATCH /api/v1/products/{id}/status` (also `/internal/v1` alias) | `product.product.status-changed`; the payload carries the previous and new status, and every accepted transition advances the row version |
| `POST /api/v1/policies/submissions` (also `/internal/v1` alias) | `policy.submission.created` against the `UnderwritingSubmission` aggregate |
| `PATCH /api/v1/policies/submissions/{id}/status` (also `/internal/v1` alias) | `policy.submission.status-changed`; the payload carries the previous and new status and the optional reason, and every accepted transition advances the submission version |
| `POST /api/v1/policies/submissions/{id}/convert` (also `/internal/v1` alias) | `policy.submission.converted` against the `UnderwritingSubmission` aggregate, plus the normal `policy.policy.created` from policy creation; the payload carries `{submissionId, submissionNumber, policyId, policyNumber}` |

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

### Active-product enforcement for new business

New policies and new underwriting submissions must name an ACTIVE product in the
tenant's product catalog. The shared `ProductCatalogGuard` trims and upper-cases
the request code before the tenant-scoped catalog read, so `cgl` matches the
stored `CGL`. An unknown code is `422 UNKNOWN_PRODUCT`; a known but `RETIRED`
code is `422 PRODUCT_NOT_ACTIVE`. Both problems carry
`errors[0].field=productCode` with the same stable code, and `meta` carries
`productCode` and `tenantId`; the retired form also carries `productId` and
`productStatus=RETIRED`. Rejected creates write no policy, submission, number
allocation, or event.

The check is for new business only. Existing policies and submissions are never
re-validated. Submission conversion uses the product accepted on the submission
and calls `PolicyService.createConvertedPolicy`, deliberately bypassing the
guard, so a conversion accepted before retirement still succeeds afterwards.
Renewal likewise copies its source term without a catalog check. An idempotent
policy-create replay is resolved before the guard, so replaying an
already-accepted create also keeps returning the original policy after the
product is retired. The guard runs before the submission number is allocated, so
a rejected submission intake never consumes a number.

The product catalog events use the requested `product.product.created` and
`product.product.status-changed` names, which imply the `product` event
vertical. The platform `EventVerticals` registry does not admit that vertical
yet (`EventEnvelope` and `EventSubject` reject it), so these rows are written
directly as `OutboxEvent`s with the canonical payload and header JSON; the
stored event types and payloads are final. `OutboxEvent.toEnvelope()` and the
HTTP dispatcher will reconstruct them once `eia-platform` adds `product` to
`EventVerticals.ALL`. The created payload carries `productId`, `productCode`,
`productName`, `productLine`, `jurisdiction`, `status`, `version`, `createdAt`,
and `updatedAt`; the status-changed payload carries the same identity fields
plus `previousStatus`, the new `version`/`updatedAt`, and
`changedFields: ["status"]`. The demo seed writes no events.

The underwriting submission events are additive too: `policy.submission.created`,
`policy.submission.status-changed`, and `policy.submission.converted` are not
listed in `eia-contracts/asyncapi/policy.yaml` or `event-catalog.yaml` yet. A
submission is its own aggregate, so the rows carry
`aggregateType=UnderwritingSubmission` and `aggregateVersion=1` on create (then
one higher per accepted transition or conversion); the subject type is
`underwriting-submission`. The created payload carries the submission number,
applicant, product, requested date/limit/currency, tort codes, notes, `RECEIVED`
status, and timestamps. The status-changed payload carries `submissionId`,
`submissionNumber`, `applicantPartyId`, `productCode`, `previousStatus`,
`status`, `version`, `updatedAt`, and `reason` (explicitly null when absent).
The converted payload carries exactly `submissionId`, `submissionNumber`,
`policyId`, and `policyNumber`.

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
written premium and the ACTIVE plan's net premium:
`netPlanPremium = max(plan.totalPremium - sum(installment.credited_amount), 0)`
and `delta = policy.premiumAmount - netPlanPremium`. It is a 404
`BILLING_PLAN_NOT_FOUND` when the policy has no plan and a 409
`BILLING_PLAN_NOT_ACTIVE` when the latest plan is closed. A zero or negative
delta is a 400 `NO_PREMIUM_ADJUSTMENT` with `delta`, `policyPremiumAmount`,
`planTotalPremium`, `creditedAmount`, and `netPlanPremium` metadata, because a
premium decrease is a credit-note concern. Applied credits reduce the plan's
collectible premium, so a credit that follows a premium reduction lets the two
sides align instead of making the command fail. The 24-installment ceiling is
checked before the money remainder, so a full schedule reports 409
`MAX_INSTALLMENTS_REACHED`. `dueDate` is required and may not precede the policy
effective date (the effective date itself is accepted, which allows an inception
adjustment); `reason` is required, trimmed, and capped at 500 characters.

The accepted command appends one DUE installment with the derived amount, the
next installment number, and the supplied due date, then applies the plan change
with one conditional UPDATE that requires the plan to still be ACTIVE and its
`total_premium`/`installment_count` plus the schedule's total
`credited_amount` to equal the values the command read. A concurrent adjustment,
credit application, or closure loses that comparison and rolls the appended
installment back; the `(plan_id, installment_number)` unique key is the second
arbiter. The plan total moves up by the delta (gross), so the plan's net premium
equals the policy premium after the append. The response is 201 with `{plan,
installment, adjustmentAmount, newTotalPremium}`. The policy version bump and
`policy.billing-plan.adjusted` (`policyId`, `policyNumber`, `planId`,
`adjustmentAmount`, `newTotalPremium`, `installmentId`, `installmentNumber`,
`dueDate`, `reason`) commit in the same transaction.

## Policy rating worksheets

`POST /api/v1/policies/{id}/rating` (also the `/internal/v1` alias) accepts
`{ratingMethod, baseRate, exposureAmount, factor, apply}` and always persists
one immutable `policy_rating_worksheet` row. Only `DRAFT` and `QUOTED` policies
are rateable; any other lifecycle state is `409 POLICY_NOT_RATEABLE` with
`policyId`, `sourceStatus`, and the allowed statuses in `meta`. The methods are
`RATE_TIMES_EXPOSURE` (`baseRate * exposureAmount * factor`) and `FLAT`
(`baseRate * factor`). The factor defaults to `1`; `baseRate` and `factor` must
be non-negative; `RATE_TIMES_EXPOSURE` requires a strictly positive
`exposureAmount` and stores it, while `FLAT` ignores the exposure and stores
null. The computed premium is rounded once to scale 4 with HALF_UP.

`calcHash` is the SHA-256 of a canonical line-based rendering of the normalized
method, base rate (scale 6), exposure (scale 4 or `~`), factor (scale 6),
computed premium (scale 4), and currency. Normalizing before hashing makes the
digest independent of the request's decimal scale, so `100` and `100.000000`
reproduce the same hash. The worksheet response is the stored row: identity and
tenant, the normalized inputs, `computedPremium`, `currency`, `calcHash`,
`status=CALCULATED`, `createdAt`, and `createdBy` (the system actor
`00000000-0000-0000-0000-000000000000`, because the internal command carries no
user identity).

A calculation-only command (`apply=false`, the default) returns
`201 {worksheet}` and leaves the policy untouched. `apply=true` replaces the
policy premium with the computed premium, bumps the aggregate version, and
writes `policy.policy.rated` in the same transaction with `{policyId,
policyNumber, premiumAmount, currency, ratingMethod, calcHash, worksheetId}`.
The response is `201 {worksheet, policy}` with the new policy ETag. The
conditional premium update uses the version read by the command, so a stale
`If-Match` or a concurrent change is `409 CONCURRENT_MODIFICATION` and rolls the
worksheet back. `GET /api/v1/policies/{id}/rating` (also `/internal/v1`)
returns the newest worksheet by `created_at`, or `404
RATING_WORKSHEET_NOT_FOUND` when the policy has never been rated. Every read and
write is tenant scoped, and a policy from another tenant is a `404
POLICY_NOT_FOUND`.

## Underwriting submissions

`underwriting_submission` is a tenant-owned intake aggregate that exists before
a policy. The server allocates `submission_number` as `SUB-{YYYY}-{7 digits}`
from `underwriting_submission_number_seq`, a per-tenant counter row locked with
`SELECT ... FOR UPDATE`; `(tenant_id, submission_number)` is unique. The counter
is advanced in its own short transaction, so a rollback of the submission
insert leaves a gap exactly like a database sequence. A first-ever create for a
tenant can race on the counter-row insert; the losing transaction rolls back and
the service retries against the committed row.

The create body is `{applicantPartyId, productCode, requestedEffectiveDate,
requestedLimitAmount, requestedLimitCurrency, tortCodes[], notes?}`. The product
code must name an ACTIVE product for the tenant
(`422 UNKNOWN_PRODUCT`/`PRODUCT_NOT_ACTIVE`) before the number is allocated. A
new submission is `RECEIVED` with `version=0`; `createdAt` and `updatedAt` are
server-owned and microsecond-truncated. The caps are product code 64, currency
three ISO 4217 letters (stored upper-case), non-negative limit, at most 20 tort
codes of at most 32 characters each, and notes at most 2000 characters. Blank
tort-code entries are rejected; a missing tort-code list stores an empty array
and blank notes store null. Bean Validation enforces the same caps at the HTTP
boundary and `UnderwritingSubmissionRules` re-checks them for direct service
calls before the number is allocated.

`GET /api/v1/policies/submissions` lists newest-updated first with an optional
`q` (case-insensitive substring over submission number, product code, and notes)
and an optional `status`; an unknown status is `400 INVALID_SUBMISSION_STATUS`.
`GET /api/v1/policies/submissions/{id}` is tenant-scoped, so a submission from
another tenant is `404 SUBMISSION_NOT_FOUND`. Both routes are also exposed under
`/internal/v1/policies/submissions` for the domain adapter, and all reads/writes
use the `?tenantId=` or `X-EIA-Tenant` scope with the DIRECT-book gate.

`PATCH /api/v1/policies/submissions/{id}/status` accepts `{status, reason?}`.
The graph is:

```
RECEIVED  -> REFERRED, QUOTED, DECLINED, WITHDRAWN
REFERRED  -> QUOTED, DECLINED, WITHDRAWN
QUOTED    -> BOUND, DECLINED, WITHDRAWN
BOUND, DECLINED, WITHDRAWN -> terminal
```

An out-of-order or terminal transition is `409 INVALID_SUBMISSION_TRANSITION`
with `submissionId`, `currentStatus`, and `targetStatus` in `meta`; an unknown
status value is `400 INVALID_SUBMISSION_STATUS`. The optional reason is trimmed
and capped at 500 characters. The aggregate stores the resulting status and
increments `version` (and `updatedAt`); the reason is carried on the
`policy.submission.status-changed` event as the audit explanation. The update
predicate includes the version and current status read by the command, so a
concurrent winner is `409 CONCURRENT_MODIFICATION` and neither the row nor an
event is written by the loser. Every accepted transition returns the updated
submission with `ETag: "<version>"`.

The accepted create writes `policy.submission.created` and each accepted
transition writes `policy.submission.status-changed` in the same transaction as
the submission row, so a rollback leaves neither the aggregate change nor the
event behind. Both event names are additive contract entries, reported above.

### Structured referrals

The referral columns are additive and nullable: `referral_reason VARCHAR(64)`,
`referral_authority VARCHAR(64)`, `referred_to VARCHAR(128)`,
`referral_notes VARCHAR(2000)`, `referral_decision VARCHAR(32)`,
`referral_decided_by VARCHAR(128)`, and `referral_decided_at TIMESTAMPTZ`. A
`ck_underwriting_submission_referral_decision` check allows only
`PENDING`/`APPROVED`/`DECLINED` (or null). The production DDL adds each column
with `ADD COLUMN IF NOT EXISTS` and drops/re-adds the constraint, so the
schema-init path is idempotent for existing databases; `schema-h2.sql` mirrors
the same columns and ALTERs, and `idx_underwriting_submission_tenant_referral`
supports a tenant-scoped pending-decision scan.

`POST /api/v1/policies/submissions/{id}/referral` (also `/internal/v1`) accepts
`{reason, authority, referredTo, notes?}`. The reason and authority are capped
at 64 characters and `referredTo` at 128; notes are optional and capped at 2000.
Only `RECEIVED` and `REFERRED` are referrable; every later status is `409
SUBMISSION_NOT_REFERRABLE` with `submissionId` and `currentStatus` in `meta`.
The command is an upsert of the referral details: it sets the status to
`REFERRED`, starts (or restarts) `referral_decision=PENDING`, clears any prior
decider/timestamp, increments the version, and returns the updated submission
with `ETag: "<version>"`. Calling it again while already `REFERRED` updates the
details without leaving `REFERRED`. The update predicate includes the read
version and status, so a concurrent winner is `409 CONCURRENT_MODIFICATION`
without a row change or event. The accepted command writes
`policy.submission.referred` in the same transaction as the row.

`POST /api/v1/policies/submissions/{id}/referral/decision` accepts
`{decision, decidedBy, notes?}`. `decision` is normalized upper-case and must be
`APPROVED` or `DECLINED`; `PENDING` and unknown values are `400
INVALID_SUBMISSION_REFERRAL_DECISION`. `decidedBy` is required and capped at
128 characters; notes are capped at 2000 and, when supplied, replace
`referral_notes` (an omitted note keeps the referral narrative). The command
requires a pending decision on a `REFERRED` submission; otherwise it is `409
REFERRAL_NOT_PENDING` with `submissionId`, `currentStatus`, and
`referralDecision` in `meta`. `APPROVED` keeps the submission `REFERRED`;
`DECLINED` also sets the lifecycle status to `DECLINED`. Both set
`referral_decided_by`, `referral_decided_at`, increment the version, and write
`policy.submission.referral-decided` in the same transaction. The conditional
update includes the read version, status, and `PENDING` decision, so a replay or
concurrent change is `409 CONCURRENT_MODIFICATION` without an event. Both
referral event names are additive contract entries.

The submission projection exposes `referralReason`, `referralAuthority`,
`referredTo`, `referralNotes`, `referralDecision`, `referralDecidedBy`, and
`referralDecidedAt`. A referral does not bypass the status graph: once
`DECLINED`, the submission is terminal for the lifecycle, and a generic status
transition from `REFERRED` remains governed by the existing graph.

## Submission-to-policy conversion

`POST /api/v1/policies/submissions/{id}/convert` (also the `/internal/v1` alias)
accepts `{premiumAmount, premiumCurrency, expirationDate?}`. The submission must
be `QUOTED` or `BOUND`; `RECEIVED`, `REFERRED`, `DECLINED`, and `WITHDRAWN` are
`409 SUBMISSION_NOT_CONVERTIBLE` with `submissionId` and `currentStatus` in
`meta`. `premiumAmount` is required, non-negative, and normalized to four
fraction digits; `premiumCurrency` is a three-letter ISO 4217 code stored
upper-case; `expirationDate` defaults to the requested effective date plus one
year and an explicit value must be strictly after that effective date
(`400 INVALID_CONVERSION_EXPIRATION`).

The command creates a normal `DRAFT` policy through the policy create path, but
it uses `PolicyService.createConvertedPolicy` so the ACTIVE-product guard is not
re-applied: the submission product was validated at intake and conversion stays
valid if that product is retired in between. The server allocates
`POL-{YYYY}-{7 digits}`, writes the initial DRAFT `policy_version`, and emits
`policy.policy.created`. The policy takes the
submission's applicant, product, requested effective date, and
requested limit/currency, plus the command premium. When the requested limit is
positive, conversion also writes one base coverage and its limit schedule using
the same `CoverageRules` as the coverage command: the coverage code is derived
from the product code, the term spans the policy, and the requested limit is both
the each-occurrence and aggregate amount. A zero requested limit skips the
coverage because a coverage requires a positive each-occurrence amount.

`policy.submission_id` records the source submission and is unique per tenant,
so a submission can produce at most one policy. The submission moves from
`QUOTED` to `BOUND` (a `BOUND` submission without a policy is linked and its
version still advances), and both aggregate versions bump. The policy, the
submission transition, and `policy.submission.converted` commit together; the
payload is `{submissionId, submissionNumber, policyId, policyNumber}`. A retry
after success finds the linked policy and returns it with `200` and
`Idempotent-Replayed: true` without writing a row or an event. The submission
projection exposes the linked `policyId`.

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
- The product catalog is create/list/get plus ACTIVE/RETIRED status only, and
  new policy/submission creates require an ACTIVE row; there is no automatic
  product creation, grace period, or backfill of legacy rows. The
  SPEC 11.2.14 `product_version`, `form_family`, effective dates, owner, and
  the wider DRAFT/FILED/APPROVED/SUSPENDED lifecycle are not modelled; this
  slice uses the requested CGL/PRODUCTS_LIABILITY/UMBRELLA/EXCESS/OTHER taxonomy
  and two statuses. There is no product edit, delete, or policy-to-product
  foreign key. The demo seed runs on startup unless `eia.product.seed.enabled`
  is false, is idempotent by `(tenant_id, product_code)`, never overwrites an
  existing row, and writes no events.
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
- Policy rating is a single premium calculation and worksheet per command. The
  SPEC 11.2.14 `rating_plan`, `rating_factor`, exposure lines, factor trace,
  minimum premium, tax/fee amounts, and APPROVED/SUPERSEDED worksheet workflow
  are not modelled; a worksheet has no plan reference and is always
  `CALCULATED`. Rating is not required before quote or bind. The rating command
  accepts `Idempotency-Key`, stores the SHA-256 of the canonical rating request
  on the key row, and replays the stored worksheet/policy response only for the
  same request; a changed request under the same key is `409
  IDEMPOTENCY_CONFLICT` with `meta.operation` and `meta.key`. The hash includes
  the policy id, so a key cannot replay a worksheet from another policy.
  Without a key each accepted command appends a worksheet.
- Underwriting submissions are an intake and conversion record only. The SPEC
  11.2.14 `clearance`, `referral`, `underwriting_authority`, `declination`,
  `policy_quote`, product/form library, eligibility rules, and
  exposure/coverage detail are not exposed, and conversion writes one base
  coverage rather than the submission's requested coverage schedule. The status
  vocabulary is the requested slice graph; the SPEC `CLEARANCE` and `RATING`
  intermediate states are not modelled. There is no submission edit or delete
  command, no status history table (the reason lives on the event), and no
  `If-Match` on the submission commands. Conversion accepts `Idempotency-Key`,
  stores the SHA-256 of the canonical conversion request (premium, currency,
  expiration, and submission id), and replays the stored policy response only
  for the same request; a changed request under the same key is `409
  IDEMPOTENCY_CONFLICT` with `meta.operation` and `meta.key`. Without a key it
  is idempotent by the linked policy.
