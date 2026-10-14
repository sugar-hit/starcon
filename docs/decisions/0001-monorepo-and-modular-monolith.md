# ADR-0001: Monorepo + Modular Monolith 起步

- **状态**: Accepted
- **日期**: 2026-09-25
- **决策者**: Architecture Team
- **关联**: [`docs/architecture/overview.md`](../architecture/overview.md)

## 背景与问题

starcon 是一个企业级 SaaS ERP，要服务多个电商平台、多个国家、多种业务场景。代码组织面临两个问题：

1. **跨模块共享多**：订单 schema、币种处理、租户上下文、事件 envelope 等在多个领域模块间共用
2. **早期阶段不适合纯微服务**：分布式事务、调试链路、部署复杂度会在团队 < 10 人时吃光所有时间

## 评估的选项

### 选项 A: Monorepo + Modular Monolith（推荐）

- **优点**:
  - 一个 PR 可以原子地改"前端 + 后端 + 共享类型 + DB 迁移 + 文档"
  - 跨服务类型 / 契约靠 TS compiler + IDE 自动保证
  - 部署依然单服务单镜像（K8s 一个 deployment），但**代码层模块边界清晰**
  - 后续拆微服务时改的是 deployment 拓扑，代码结构几乎不动
- **缺点**:
  - 单仓体积增长快，需要靠 Turborepo 远程缓存 + CI 增量
  - 团队大了之后需要强 CODEOWNERS 纪律

### 选项 B: Polyrepo（每个服务独立仓库）

- **优点**:
  - 团队 / 权限天然隔离
  - 单仓体积小
- **缺点**:
  - 一次跨服务改动要开 5+ 个 PR，关联 review 困难
  - 共享类型靠 npm private registry，发布/版本协调极重
  - 早期团队完全不值得这个开销

### 选项 C: 单仓 Monolith（无 packages / modules 边界）

- **优点**:
  - 最快起步
- **缺点**:
  - 3 个月后必然变成大泥球
  - 后续拆服务重构成本巨大

## 决策

选 **A: Monorepo (pnpm + Turborepo) + Modular Monolith**。

## 理由

starcon 当前是早期 SaaS（团队 < 10 人），分布式复杂度会带来 > 5 倍的边际成本。Modular Monolith 让我们用最低的工程复杂度获得：

- **清晰的领域边界** (`apps/api/src/modules/<domain>/`)
- **未来拆微服务的路径**（每个模块改成独立 deployment + transport 即变服务）
- **跨模块类型 / 契约的强一致**（TS compiler 保证）

## 后果

### 正面

- 跨模块改动原子化
- 共享类型 / 工具零摩擦
- 部署模型简单（K8s 1 个 API deployment）
- 后续拆服务无需重写业务代码

### 负面 / 风险

- 单仓体积增长 → 必须 Turborepo 远程缓存 + CI 增量
- 团队 > 20 人时需强 CODEOWNERS 纪律
- 模块间若硬编码跨模块调用，后期拆服务有迁移成本

### 折衷 / 后续待办

- 启用 Turborepo 远程缓存（Vercel / 自托管）
- 设立 `CODEOWNERS`，每个 `apps/api/src/modules/<domain>/` 指定 owner
- ADR-0007 跟踪"何时拆服务"的临界指标
