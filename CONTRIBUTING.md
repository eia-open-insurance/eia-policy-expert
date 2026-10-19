# Contributing

## Workflow

1. Create a short-lived branch from `main`.
2. Make the change with tests.
3. Open a pull request.
4. Obtain CODEOWNERS approval and pass required checks.
5. Squash merge to `main`.

## Standards

- Follow the repository standard in `../CONVENTIONS.md`.
- Keep one deployable component per repository.
- No business logic in shared libraries or front-end remotes.
- Update `CHANGELOG.md`.
- Sign commits.

## Required checks

Lint, unit tests, contract tests, integration or Playwright tests where applicable, security scan, SBOM, and build.
