# 产品文档

## 子目录

- [`capability-blueprint/`](capability-blueprint/) — **系统功能蓝图**：starcon 应该有哪些功能，四级目录（系统-功能-子功能-模块）与每个最小模块的输入/输出/关系
- [`prds/`](prds/README.md) — Product Requirements Document（产品需求文档）与评审规范
- [`user-stories/`](user-stories/) — 用户故事
- [`personas/`](personas/) — 用户画像

- [`sprints/`](sprints/README.md) — 按依赖逐功能实施与验收的 Sprint 清单

## 功能蓝图 vs PRD

|          | 功能蓝图                                                           | PRD                    |
| -------- | ------------------------------------------------------------------ | ---------------------- |
| 回答     | 整个系统**应该有哪些功能**                                         | 某个功能**具体怎么做** |
| 粒度     | 四级目录，最小模块                                                 | 单功能                 |
| 生命周期 | 演进，随系统设计更新                                               | 立项后趋于稳定         |
| 入口     | [`capability-blueprint/README.md`](capability-blueprint/README.md) | `prds/`                |

写新功能前先在蓝图里找到它所在的系统与相邻模块，确认边界后再写 PRD。

## PRD 规范

每个 PRD 是一个可独立交付、可验收的产品增量。统一元数据、必备章节、验收写法和评审清单见
[`prds/README.md`](prds/README.md)。新 PRD 命名 `<ticket>-<feature-slug>.md`；没有 ticket 时可先用稳定功能名。

## 纪律

- **开发 TODO 不进 PRD**：PRD 只描述"我们要给用户什么"，不写"待对接支付"、"暂用 mock"
- **探索与调研走 `docs/investment/`**，视觉规范和设计稿走 `docs/design/`，跟 PRD 分开
- **跨模块 TODO 走 `docs/backlog/`**，PRD 不写实现层细节
