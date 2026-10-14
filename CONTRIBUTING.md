# Contributing to starcon

Contributions are welcome. Read [LICENSING.md](LICENSING.md) before submitting. Submit original contributions under [PolyForm Noncommercial 1.0.0](LICENSE), confirm you have the right to contribute, and retain third-party notices. You retain copyright; submission does not grant additional commercial relicensing rights.

Create a branch from `main`, include relevant tests, and run lint, type checking, unit tests, and build before opening a PR. Use Conventional Commits and document architecture changes through an ADR.

欢迎提交问题报告、文档改进和代码贡献。参与前请阅读 [使用许可与商业授权](LICENSING.md)。

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

## 贡献许可

提交供本项目收录的贡献，应同意将该贡献按 [PolyForm Noncommercial License 1.0.0](LICENSE) 提供，并确认有权提交相关代码或素材。贡献者保留版权；这不构成版权转让或额外商业再许可授权。第三方内容须注明来源和原有许可。

商业授权涉及他人贡献时，需另行取得相应权利人的授权，详见 [LICENSING.md](LICENSING.md)。
