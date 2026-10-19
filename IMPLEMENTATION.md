# Implementation notes

## Status

The Policy expert implements the first slice (policy header, lifecycle,
idempotent create, ETag/If-Match, canonical problems) and the second slice
(versions, coverage and limits, aggregate erosion, endorsements, insureds and
locations, cancellation and reinstatement) from SPEC 11.2.

## Structure

| Package area | Responsibility |
|---|---|
| `PolicyController`, `PolicyService`, `PolicyRepository` | Policy aggregate, lifecycle, tenant-scoped reads |
| `PolicyVersionController` / `PolicyVersionService` / `PolicyVersionRepository` | Immutable version history, derived current version, hash verification |
| `CoverageController` / `CoverageService` / `CoverageRepository` | Coverage grants, limit schedules, erosion ledger |
| `EndorsementController` / `EndorsementService` / `EndorsementRepository` | Endorsements and the backdate gate |
| `PolicyPartyController` / `PolicyPartyService` / `PolicyPartyRepository` | Insureds and locations |
| `CancellationController` / `CancellationService` / `CancellationRepository` | Cancellation, return premium, reinstatement |
| `PolicyRules`, `VersionTimeline`, `CoverageRules`, `CancellationRules`, `CancellationMath`, `PartyRules`, `EndorsementRules` | Pure invariants with focused unit tests |
| `PolicyVersionHasher`, `VersionIssuer` | Canonical version snapshot hash and issuance |
| `CommandIdempotency` | Per-command replay/conflict behaviour |

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

- Billing, premium receivables, producer payables, and dunning
  (SPEC 11.2.13) are not implemented.
- Mass tort profile, coverage blocks, occurrences, layers, forms, exclusions,
  rating exposures, premium audit, and the claim/cession/reinsurance projections
  are not implemented.
- Coverage/limit rows are keyed by coverage code and unique active code; the
  full `coverage`, `coverage_type`, layer, and form reference tables from
  SPEC 10.11 are not loaded.
- Aggregate erosion is driven by the explicit `/erosion` endpoint; there is no
  event dispatcher or consumer for `direct.claim.*` events yet.
- Version windows use one effective date plus a supersession cutover; there is
  no multi-term renewal chain and no new-term approval path (SPEC POL-07).
- `policy` does not yet denormalize `cancelled_at`, `cancellation_effective_date`,
  or coverage totals; those live on the dedicated second-slice tables.
- Legacy business error codes remain instead of the `EIA-XXX-NNNN` scheme
  (tracked as M-02 in `eia-contracts/MISMATCHES.md`).
- No authentication, authorization, rate limiting, or event publication (the
  outbox library exists in `eia-platform` but is not wired here).
- Support for multiple concurrent `DRAFT` versions, corrections that void a
  version, and `VOID` / `PREMIUM_AUDIT` change types is schema-ready but not
  exposed.
