# docs/

starcon 的**单一信息源**。所有架构、产品、运维、决策、AI 沟通上下文都集中在此。

## 子目录索引

| 路径 | 作用 | 谁维护 |
|---|---|---|
| [`architecture/`](architecture/) | 系统架构设计（活文档，随演进更新） | 人 + AI |
| [`decisions/`](decisions/) | 架构决策记录 ADR（永久，不可逆追溯） | 人 + AI |
| [`product/`](product/) | 产品 PRD / 用户故事 / 用户画像 | 产品 |
| [`operations/`](operations/) | 运维 Runbook / 故障复盘 / On-call 指南 | SRE |
| [`onboarding/`](onboarding/) | 新人入职指南 / 约定 / 术语表 | 所有人 |
| [`api/`](api/) | 自动生成的 API 文档 | CI |
| [`compliance/`](compliance/) | 合规（GDPR / 数据出境 / SOC2） | 法务 + SRE |
| [`sessions/`](sessions/) | **AI 会话日志**（项目级可检索） | AI |
| [`backlog/`](backlog/) | 跨模块 TODO 集中地 | AI |
| [`investment/`](investment/) | 调研 / 探索笔记（一次性） | AI |

## 写入规范

- **ADR** 一旦写入 `decisions/` 就**不可改写**，要变就新增 `000N` 并 supersede 旧的
- **会话语档** 写到 `sessions/YYYY-MM/` 当月目录，月内归档，跨年移入 `archive/`
- **跨模块 TODO** 写到 `backlog/YYYY-MM/`，单文件级别的小 TODO 直接源码 `// TODO:` 内联
- **架构图** 用 mermaid 写在 `.md` 里（GitHub 原生渲染），不进 `.png`/`drawio`
- **任何 dev TODO / "待对接" 之类的开发侧注意事项都不进 `product/`**——见 backlog

## 给 AI agent

新会话开工前，按这个顺序读：
1. `AGENTS.md`（仓库级纪律）
2. `docs/sessions/` 下最近 3 篇（看上次决定）
3. `docs/decisions/` 下所有（看不可逆约束）
4. `docs/architecture/overview.md`（看当前架构）
5. `docs/backlog/`（看跨模块 TODO）