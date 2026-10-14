# 跨模块 TODO 集中地

本目录收集**跨模块 / 跨 PR / 跨应用**的待办事项。单文件级别的小 TODO 直接留在源码 `// TODO:`，不进这里。

## 收录标准

✅ **进 backlog**:

- "X 模块上线后默认 Y，但升级路径在 Z 切换" — 跨模块协调
- "后续需要接入支付渠道 A，但当前只支持 B" — 已知缺口
- "DB 性能瓶颈需要重构，目前先这样" — 技术债
- "OAuth provider 之一权限审批中" — 阻塞性外因

❌ **不进 backlog**:

- 单文件 / 单函数内的临时 TODO → 源码 `// TODO:` 即可
- 当前任务相关的 TODO → 跟当前 PR 一起
- 设计稿 / UI 探索文档 → 走 `docs/investment/`
- "我们以后想做 XX 功能" 这种纯想法 → 走 `docs/product/prds/`

## 文件命名

```
docs/backlog/YYYY-MM/YYYY-MM-DD-<topic-slug>.md
```

- 按 **产生日期** 归档到当月目录
- 同一主题的后续追加写到同一文件

## 模板

```md
# <topic>

- 创建: YYYY-MM-DD
- Owner: <谁负责 / TBD>
- 优先级: P0 / P1 / P2
- 关联: <ADR / 会话 / 模块路径>

## 背景

(为什么这事要做 / 现在为啥不做)

## 待办内容

- [ ] 具体动作 1
- [ ] 具体动作 2

## 影响面

- 模块 X: ...
- 模块 Y: ...

## 完成标准

(怎样算 done)
```

## 状态

- **Open**: 还在 backlog 里
- **Done**: 落地后移到 `docs/backlog/archive/YYYY-MM/`
- **Won't do**: 明确放弃，写明原因

## 索引

- [`2026-09/`](2026-09/) — 2026 年 9 月
- [`2026-10/`](2026-10/) — 2026 年 10 月
  - [`2026-10-07-sku-code-api-proxy-allowlist.md`](2026-10/2026-10-07-sku-code-api-proxy-allowlist.md) — **P0** 3.1.2.3 前端白名单漏 `sku-code`，页面必然 404
