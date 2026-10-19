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
- `LivePostgresSmokeTest` opt-in live PostgreSQL flow through issue, coverage,
  endorsement, erosion, cancellation, and reinstatement.

### Changed

- `GET /internal/v1/policies` now returns the page envelope instead of a bare
  array and accepts `page`/`size`.
- Illegal lifecycle transitions and duplicate policy numbers are `409` conflicts;
  existing business codes are unchanged.
- Policy versions are issued on `ISSUED` and on every material post-issue change
  (coverage, insured, location, endorsement, cancellation, reinstatement).

### Removed

- The legacy `{timestamp, status, code, message, path, details}` body.
