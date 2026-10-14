# Dependency security maintenance

The CI security job runs CodeQL and `pnpm audit --audit-level=high` over both runtime and development dependencies. It does not skip audits or reduce the severity threshold.

## Patched advisory: CVE-2026-93687

[GHSA-vfj7-8cjw-p6xm](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm) affects braces through 3.0.3. As of 2026-10-15 no patched upstream release exists. Deeply nested patterns can exhaust the stack in recursive AST walkers.

The repository applies `patches/braces@3.0.3.patch` through pnpm's patchedDependencies. It limits parser nesting and compile/expand/stringify traversal to depth 128, including direct AST callers. Normal brace and range expansion remains supported. Regression tests in `packages/config-tailwind/src/braces-security.test.ts` cover ordinary patterns and deep brace, parenthesis and AST inputs.

The exact CVE is excluded from pnpm's version-based audit because that audit cannot recognize a local source patch. This exclusion applies only together with the committed patch and tests; it is not an acceptance of the unpatched vulnerability. Reevaluate the exception when an upstream fix becomes available, upgrade, and remove the patch and exclusion together. Do not add broad package or severity exclusions.

Next.js and Vitest were upgraded to patched releases; PostCSS, source-map-js and glob dependencies are constrained to patched compatible versions. Lower-severity findings remain visible in the audit output and should be reevaluated with dependency updates.
