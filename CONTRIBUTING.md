# Contributing to starcon

> 占位文档，将在首个 PR 流程确立后补充。

## 提交流程

1. 从 `main` 拉特性分支: `git checkout -b feat/<ticket-id>-<short-desc>`
2. 写代码 + 测试
3. 本地跑完: `pnpm lint && pnpm typecheck && pnpm test`
4. 提交: 遵循 Conventional Commits（`feat:` `fix:` `refactor:` `docs:` `test:` `chore:` `perf:`）
5. Push 后开 PR，填写 `.github/PULL_REQUEST_TEMPLATE.md`

## 沟通上下文

所有有结论的 AI 会话必须落到 `docs/sessions/YYYY-MM/`，详见 `docs/sessions/README.md`。

## 架构变更

任何影响架构的改动**必须**先写 ADR (`docs/decisions/000N-<slug>.md`)，review 通过后才动代码。