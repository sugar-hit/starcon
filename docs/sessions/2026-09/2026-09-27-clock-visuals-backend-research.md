# 2026-09-27: 工作台时钟视觉与后端选型调研

## 摘要

优化固定六城时间带及侧栏英文标语；根据现有骨架、ADR-0001 和官方技术资料，形成后端选型与主数据落地建议。本轮没有实施后端。

## 决策 / 结论

- 地球保留 48px，改不透明浅蓝海洋与蓝色大陆，定位点增加白色描边，解决照片背景上透明蓝不清楚的问题；SVG 使用 React useId 避免服务端/客户端随机 ID 不一致。
- 地球绝对定位右上角，不再撑高城市行；城市、国家分层，时间突出，日期/GMT 可换行；提高元信息亮度。
- 用户所说的左下英文为 PIONEERING INNOVATION.，由强制两行改成 10px 单行。
- 推荐 TypeScript + NestJS/Fastify + PostgreSQL 模块化单体。具体框架、ORM 与部署仍是调研建议，未新增 Accepted ADR。
- 主数据互通以统一 ID、单一写入归属和公开服务接口实现；同租户共享，跨租户隔离。
- 第一里程碑：租户隔离底座与 SKU / 店铺 / 仓库 → 单平台订单 → 库存预占 → 出库 → 待结算。

## 涉及文件

- packages/ui/src/components/common/world-clock-strip.tsx
- packages/ui/src/components/common/city-backdrop.tsx
- packages/ui/src/components/common/mini-globe.tsx
- packages/ui/src/components/layout/app-shell.tsx
- tests/e2e/world-clock.spec.ts
- docs/design/visual-spec.md
- docs/investment/2026-09-27-backend-and-master-data.md
- 本会话日志

## 关键 trade-off / 讨论

保留地球作为区域识别辅助，但不让它控制行高。照片保持原配置，未动其他工作中的取图脚本。
后端采用单库强事务以降低早期复杂度；异步同步使用 outbox 与幂等，未来是否拆服务以实测瓶颈和团队边界判断。
架构蓝图里的 Kafka / ClickHouse / K8s 不视为第一期前置条件，也不承诺模块可零成本拆分。

## 验证

- pnpm lint、pnpm typecheck、pnpm test（28 项）、pnpm build 通过。
- 浏览器初次验证遇到现有 Next dev 与 build 共用缓存，页面没有完成 hydration；重启本项目预览后重新验证。
- 时钟与固定外壳浏览器回归 13/13 通过，包含 1440 / 768 / 390 / 320px 卡片无重叠、无溢出与英文单行检查。
- 已查看 1440px 与 320px 截图，确认照片、地球与文字正常。截图在 work/test-results/world-clock-clock-layout-s-44e60-footer-slogan-fits-one-line/。

## 后续待办

- 核实核心开发团队技能、首个平台、业务规模、SLA 与部署地区，再冻结技术栈 ADR。
- 建立主数据关系图、权限矩阵、租户隔离与库存事务验证。
- 本轮未提交或推送；仓库已有 tools/scripts 的改动保持原状。

## 关联

- [调研全文](../../investment/2026-09-27-backend-and-master-data.md)
- [现有模块化单体 ADR](../../decisions/0001-monorepo-and-modular-monolith.md)
