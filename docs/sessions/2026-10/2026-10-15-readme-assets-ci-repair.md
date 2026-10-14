# 2026-10-15: README assets and CI repair

## Summary

Add the existing brand logo to both README versions and replace externally hosted badges and Mermaid with repository SVG assets. Repair all previously failed CI checks rather than skip them.

## Decisions and causes

- Node 20.11 could not load the locked Vite/Vitest dependency graph. Use Node 22, declare >=22.12, and test locally on Node 22.22.
- Normalize existing frontend/document files with Prettier and backend Java files with the configured Java 21 Spotless formatter. Generated contracts remain generator-owned.
- Upgrade Next.js to 15.5.27 and Vitest to 4.1.11, synchronize package peer dependencies, and migrate route/page parameters to asynchronous resolution.
- Install pnpm and dependencies in the security job, use explicit CodeQL permissions, and keep the full high-severity audit gate.
- Patch braces 3.0.3 parsing/recursive traversal with a depth limit of 128 because upstream has no fix for CVE-2026-93687. Regression tests cover valid patterns, deep brace/parenthesis inputs and direct AST traversal. Exclude only this patched CVE from version-based audit; maintenance details are in docs/compliance/dependency-security.md.
- Give the non-root container user owned, persistent media/secret storage directories. Fresh catalog/channel processes previously failed with AccessDeniedException. CI verifies runtime UID and write permission.
- Wait for all six services needed by contract generation and print startup diagnostics on failure.
- Correct the catalog/warehouse/trade assertions that confused preserved timestamps with preserved time-zone offsets. Keep instant and eight-hour drift assertions; normalize explicit warehouse wall-clock checks to +08:00. Match PostgreSQL microsecond precision in round-trip comparisons. Audit timestamp tests assert both before/after time bounds.
- Parameterize the frontend bucket-label test with explicit Shanghai, UTC and New York timezones rather than rely on the machine timezone. The complete frontend suite passes with TZ=UTC.
- Disable unsupported TLS negotiation only in the description-library PostgreSQL test container.
- Make Playwright's base URL configurable for isolated acceptance, and derive request origins from the actual browser URL.
- Generate deterministic OpenAPI operation IDs from HTTP method/path, with collision checks and tests, because SpringDoc's discovery-order suffixes cause drift. Regenerate contracts from six live services; never hand-edit them.

## Validation so far

- Browser-rendered logo, badges and architecture verified visually.
- Node 22 lint/typecheck and 154 frontend test files / 2801 tests passed.
- Next.js 15 production build passed.
- 17 code-generation tests and module ledger consistency passed.
- Complete frontend/document formatting check passed.
- Six isolated services running against a fresh PostgreSQL volume; non-root writable storage assertions passed.
- Real product browser acceptance passed, including persistence, overrides, mobile layouts and request-origin checks.
- Previously failing catalog tests passed on real PostgreSQL after fixes; full Java verification and remote PR CI are being checked separately.

## Files

README.md, README.zh-CN.md, docs/design/readme/*.svg, .nvmrc, package manifests/lockfile, patches/braces@3.0.3.patch, docs/compliance/dependency-security.md, packages/config-tailwind/src/braces-security.test.ts, .github/workflows/ci.yml, deploy/docker/Dockerfile.backend, deploy/docker/docker-compose.catalog.yml, API proxy/route/page parameter handling, Playwright config/tests, codegen/tests/generated contracts, and existing files normalized by repository formatters.

## Trade-offs

Repository-wide formatting is a large mechanical diff but necessary to restore existing format gates; no global format exclusions were added. Framework upgrade is larger than a workflow-only repair, but leaves known patched vulnerabilities fixed. The braces exception has a concrete tested patch rather than a weakened audit threshold. Temporary isolated services use separate ports/volumes and do not alter existing development data.

## Follow-up

Replace the braces patch and exact audit exception together when an upstream fix is released. Keep bilingual README assets and version labels synchronized. Do not merge until remote CI passes.

## First remote verification

PR #9 first run passed Lint, Typecheck, Security Scan/CodeQL, and Catalog Browser & Contract Tests. A frontend timezone-assumption failure was reproduced and fixed; broader warehouse timestamp assertions found by full Java verification were corrected in a follow-up commit. All gates remain enabled.
