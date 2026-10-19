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

The first vertical slice persists the `policy` aggregate with `policy_number`, `product_code`, `insured_party_id`, `status`, `effective_date`, `expiration_date`, `premium_amount`, `premium_currency`, `limit_amount`, `limit_currency`, `version`, and audit timestamps. It enforces the policy period, non-negative premium and limit, and the SPEC policy status lifecycle.

Persistence also stores create idempotency in `policy_idempotency`
(`tenant_id`, `idempotency_key`, `request_hash`, `policy_id`, `created_at`) with
the unique constraint `(tenant_id, idempotency_key)`. The key and the policy are
written in one transaction; a replay returns the original policy and a reuse
with a different request returns `409 IDEMPOTENCY_CONFLICT`.

## Cross-cutting API behavior

- Errors are canonical `application/problem+json` (SPEC 6.11 / 12.2) with
  `errors[{field, code, message}]`, `meta`, and a correlation id.
- `GET /internal/v1/policies` accepts `page` (default 0) and `size` (default 20,
  maximum 100) and returns `{items, page, size, total, _links}` with stable
  `updated_at DESC, id ASC` ordering.
- Detail responses carry `ETag: "<version>"`; `PATCH /{id}/status` accepts
  `If-Match` with precedence over the compatibility `expectedVersion` body
  field. Stale versions are `409 CONCURRENT_MODIFICATION`.
- `POST /internal/v1/policies` accepts `Idempotency-Key`; the first create is
  `201`, a replay is `200` with `Idempotent-Replayed: true`.

## Local development

Requires JDK 22, PostgreSQL 17, and a database named `eia_policy` owned by `postgres`.

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-22.0.2.9-hotspot'
.\gradlew.bat build
.\gradlew.bat bootRun
```

The service listens on port 8083. `schema.sql` creates the tables and sequences on startup.

- `GET /internal/v1/policies?tenantId={tenantId}&page=0&size=20`
- `GET /internal/v1/policies/{id}?tenantId={tenantId}`
- `POST /internal/v1/policies` (`Idempotency-Key` optional)
- `PATCH /internal/v1/policies/{id}/status?tenantId={tenantId}` (`If-Match` optional)

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
