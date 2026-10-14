## What

<!-- 一句话说清改了什么 -->

## Why

<!-- 为什么要改，解决什么问题，关联 issue/ADR/session -->

## 影响面

<!-- 涉及哪些模块、是否有 breaking change、是否需要 migration -->

- [ ] 数据库迁移
- [ ] 配置文件变更
- [ ] 公共 API 变更
- [ ] 事件 schema 变更
- [ ] ADR 补充

## 测试

- [ ] 单元测试
- [ ] 集成测试
- [ ] E2E 测试 (如有 UI / 跨服务改动)
- [ ] 本地手动验证: ...

## 截图

<!-- UI 改动必须有截图 -->

## Checklist

- [ ] `pnpm lint && pnpm typecheck && pnpm test` 全绿
- [ ] 关联会话语档更新 (如有)
- [ ] CHANGELOG.md 更新 (用户可见改动)
- [ ] 没把 secret / token 写进代码或 commit message
- [ ] 自查 `AGENTS.md` + `docs/sessions/README.md` 的纪律
