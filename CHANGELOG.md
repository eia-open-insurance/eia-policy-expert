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

### Changed

- `GET /internal/v1/policies` now returns the page envelope instead of a bare
  array and accepts `page`/`size`.
- Illegal lifecycle transitions and duplicate policy numbers are `409` conflicts;
  existing business codes are unchanged.

### Removed

- The legacy `{timestamp, status, code, message, path, details}` body.
