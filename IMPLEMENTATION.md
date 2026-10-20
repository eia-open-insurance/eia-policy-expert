# Implementation notes

## Status

The Policy expert implements the first slice (policy header, lifecycle,
idempotent create, ETag/If-Match, canonical problems), the second slice
(versions, coverage and limits, aggregate erosion, endorsements, insureds and
locations, cancellation and reinstatement), a renewal slice (new DRAFT term with
predecessor lineage and copied terms), and a billing slice (billing plan,
installment schedule, and settlement) from SPEC 11.2.

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
| `BillingController` / `BillingService` / `BillingRepository` | Billing plan, installment schedule, PAID/WAIVED settlement |
| `PolicyRules`, `VersionTimeline`, `CoverageRules`, `CancellationRules`, `CancellationMath`, `PartyRules`, `EndorsementRules`, `BillingRules` | Pure invariants with focused unit tests |
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
| `PATCH /api/v1/policies/{id}/billing/installments/{installmentId}` | `policy.installment.paid` or `policy.installment.waived`; same-status replay writes nothing |

Payloads carry `policyId`, `policyNumber`, `status`, `version`, the policy
effective/expiration dates, premium and limit amounts as scale-4 decimal
strings with their ISO 4217 currencies, and the event-specific ids and facts
(endorsement/cancellation/reinstatement identifiers, version number and hash,
return premium, approval references, and so on). The envelope carries
`book=DIRECT`, `vertical=policy`, `aggregateType=Policy`,
`aggregateVersion=policy.version()+1`, and a deterministic event id.

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

The billing events (`policy.billing-plan.created`, `policy.installment.paid`,
`policy.installment.waived`) use the canonical three-segment envelope form, so
`OutboxEvent.fromEnvelope` and the platform dispatcher accept them unchanged.
They are not yet listed in `eia-contracts/asyncapi/policy.yaml`; the catalog
needs these additive entries. Billing payloads carry `policyId`,
`policyNumber`, `planId`, `planNumber`, and either the full installment schedule
(plan creation) or the settled installment facts (`installmentId`, `number`,
`amount`, `currency`, `status`, `paymentId`).

`policy.policy.version-issued` is emitted by the issue, endorsement,
cancellation, and reinstatement commands. Coverage/insured/location changes can
also issue a version, but those writes are outside the requested event set and
remain event-free in this slice.

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

- Billing exposure starts with the plan and installment schedule required by
  this slice. Plan `COMPLETE`/`CANCELLED` transitions, dunning, receivables,
  billing instructions, and SAP AR reconciliation (SPEC 11.2.13) are not
  exposed yet.
- Mass tort profile, coverage blocks, occurrences, layers, forms, exclusions,
  rating exposures, premium audit, and the claim/cession/reinsurance projections
  are not implemented.
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
  version, and `VOID` / `PREMIUM_AUDIT` change types is schema-ready but not
  exposed.
