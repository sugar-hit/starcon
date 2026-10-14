# 2026-10-15: Bilingual README and noncommercial licensing

## Summary / 摘要

Rewrite the competition-facing README in English with a separate Chinese version. Adopt the unmodified English PolyForm Noncommercial 1.0.0 terms, bilingual licensing guidance, required notices, and contribution guidance. The user authorized delivering these changes to main.

## Decisions / 决策

- README.md is the English landing page; README.zh-CN.md provides the Chinese version.
- LICENSE retains the official standard text; LICENSING.md explains it without altering its permissions or exceptions.
- Commercial purposes outside the standard grant require prior written authorization from the relevant rights holders.
- Use source-available terminology rather than claim OSI open-source status.
- Main initially recorded 102 delivered modules. PR #7 merged during preparation; the final overview reflects 152 / 465 (32.7%) and six services.
- Submit only task-related documentation and license metadata on a branch based on the latest main.

## Files / 文件

- README.md / README.zh-CN.md
- LICENSE / LICENSING.md / NOTICE
- CONTRIBUTING.md / package.json
- docs/sessions/2026-10/2026-10-15-bilingual-readme-licensing.md

## Trade-offs / 取舍

A standard noncommercial license meets the user's commercial restriction but may not qualify for competitions requiring OSI licenses. Contributors retain copyright; commercial relicensing requires sufficient rights. Separate language pages keep the landing page readable; the official English terms remain authoritative.

## Validation / 验证

Formatting, relative links, license metadata, original license text, and diff checks passed. Local lint and typecheck passed, 153 unit-test files / 2796 tests passed, and the production build passed after allowing Google Fonts access. Existing Hook warnings remain. Main CI run 38594664277 was already failing before this PR, including formatting, tests, backend, browser startup, and CodeQL configuration. Remote PR checks must be evaluated before merge.

## Follow-up / 后续

- Keep both README versions synchronized with delivery status.
- Confirm contributor rights and execute a written agreement when commercial authorization is requested.
- Check competition licensing eligibility and prepare demonstration media.

## References / 关联

- [Module ledger](../../product/capability-blueprint/module-progress.md)
- [Licensing guide](../../../LICENSING.md)
