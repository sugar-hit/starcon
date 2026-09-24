# Changelog

starcon 的变更日志。所有**用户可见**的改动都记录在此。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

> **说明**：本仓库当前还在 0.x 阶段。版本号与发布说明由 CI + Changesets 自动生成。

## [Unreleased]

### Added

- 初始仓库骨架（monorepo + modular monolith + cloud-native）
- `apps/web`、`apps/admin`、`apps/api`、`apps/worker`、`apps/scheduler`、`apps/notification-gateway` 占位
- `apps/api/src/modules/` 11 个领域模块占位（auth / tenant / product / inventory / order / fulfillment / shipment / finance / billing / analytics / notification）
- 外部适配器目录（platform-connectors / payment-gateways / shipping-carriers）
- 15 个 `packages/*` 共享库占位
- `infra/` Terraform / Helm / ArgoCD / K8s 占位
- `docs/` 知识库骨架 + AI 会话日志规范
- `AGENTS.md` AI 协作入口

[Unreleased]: https://example.com/starcon/compare/v0.0.0...HEAD