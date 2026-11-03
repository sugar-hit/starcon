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
- [`2026-11/`](2026-11/) — 2026 年 11 月

### 归档

- [`archive/2026-10/`](archive/2026-10/) — 已销账 26 条（2026-11-03 一次盘点）
- [`archive/2026-11/`](archive/2026-11/) — 已销账 2 条（2026-11-03 一次盘点）

> 2026-11-03 的盘点口径：**已过时**（问题前提消失）、**已修复但条目未销账**、
> **前提已消失**（属流水线/调度工具链本身，承载它的定时自动化已于当日全部取消删除）
> 三类都移入归档，文件头保留判定依据与证据；**仍然有效**的一律留在 backlog 根目录。
> 归档里的每条都写了「残留风险」—— 脚本本体仍在仓库且可被手动调用，
> **重启流水线前需先修**。

## 仍然有效的 12 条（按修复成本从低到高）

盘点后仍留在根目录的条目，附改动量级与迁移需求 —— 排期时按这张表从上往下取。

| # | 条目 | 改动量级 | 需迁移 | 需改断言 | 备注 |
| --- | --- | --- | --- | --- | --- |
| 1 | `2026-10-21-order-change-rule-effective-from-clock-domain` | 1 文件（`OrderChangeCancelService` 两处默认值改 DB 钟） | 否 | 建议加 1 条不变量 | 成本最低、零迁移，直接消掉「刚建规则查不到」 |
| 2 | `2026-10-21-global-error-handler-masks-code-defects-as-409-400` | 9 文件（9 个 `ApiErrorHandler` 各加 `log.error` + 响应加 `code`） | 否 | 否 | 机械改动、可一次提交；消掉 409/400 掩盖真因 |
| 3 | `2026-10-2026-10-26-analytics-section-no-navigation-entry` | 1~2 文件（新增 section-nav 组件并挂进 `/analytics`） | 否 | 建议加 1 条可达性 | 与第 2 条同属「页面不可达」族 |
| 4 | `2026-10-2026-10-26-hardcoded-fixture-dates-self-failing` | 9~20 文件（只动贴近当下的那批） | 否 | 是 | 需逐条判「三个必要特征」，**不许批量替换** |
| 5 | `2026-10-25-sales-order-missing-shop-entity-dimension` | 中（trade 加列 + 回填 + 索引 + RLS 复合键 + reporting 投影） | **是** | 是 | 归卷 09 写入所有者，需先拍板落点 |
| 6 | `2026-10-2026-10-26-undelivered-claims-stale-after-sibling-delivery` | 350+ 文件（涉 354 个文件、~877 处「未交付」字样） | 否 | 否 | diff 巨大 ⇒ 必须按服务分批，每批跑 L1 |
| 7 | `2026-10-23-replacement-char-encoding-corruption` | 200+ 文件（204 文件 / 601 处 U+FFFD） | 否 | 否 | **必须先取证成因**；已应用的迁移不能直接改（Flyway checksum） |
| 8 | `2026-10-2026-10-27-trade-timestamps-ordered-cross-clock-domain` | 数十文件（trade 侧 42 条 `updated_at>=created_at` CHECK） | 视收口方式（建议零迁移，只改绑定源） | 是 | 必须**逐表**判定 `updated_at` 由谁写；禁止批量替换、禁止放宽 CHECK |
| 9 | `2026-11-02-api-proxy-whitelist-has-no-safe-merge-driver` | 3~5 文件（union 驱动加 `api-proxy.ts` 分支 + `.gitattributes` + itest） | 否 | 否 | **严禁**塞进现有 union 兜底（实测会静默产出残缺文件） |
| 10 | `2026-10-22-testcontainers-pg-container-leak` | 150+ 测试类 | 否 | 否 | 改多了可能打破「类间共享容器」的隐含依赖 ⇒ 逐服务验证 |
| 11 | `2026-10-13-flaky-integration-tests-l2-false-red` | 按子类拆（catalog / warehouse 的并发 flake） | 否 | 是 | 临时端口被抢占 + 测试固件未按租户隔离 |
| 12 | `2026-09-30-business-design-review` | 0（产品决策 R-01..R-07） | 否 | 否 | 无工程量，需人工拍板；建议降级为普通 issue |
