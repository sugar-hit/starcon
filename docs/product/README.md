# 产品文档

## 子目录

- [`prds/`](prds/) — Product Requirements Document（产品需求文档）
- [`user-stories/`](user-stories/) — 用户故事
- [`personas/`](personas/) — 用户画像

## PRD 模板

每个 PRD 一个文件，命名 `<ticket>-<feature-slug>.md`，包含：

1. 背景与机会
2. 目标用户 / Persona
3. 用户故事
4. 功能范围 (in scope / out of scope)
5. 验收标准
6. 度量指标
7. 风险与依赖
8. 里程碑

## 纪律

- **开发 TODO 不进 PRD**：PRD 只描述"我们要给用户什么"，不写"待对接支付"、"暂用 mock"
- **设计稿走 `docs/investment/`**，跟 PRD 分开
- **跨模块 TODO 走 `docs/backlog/`**，PRD 不写实现层细节