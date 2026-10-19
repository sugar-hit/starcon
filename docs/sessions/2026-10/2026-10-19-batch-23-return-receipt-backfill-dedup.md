# 批次 23 · 退货入库 / 增量补拉 / 去重视图

**日期**：2026-10-19 08:00 tick
**并入头**：`8300d842` → `647dc079`（3 条车道，4.1.1.2 因冲突未并入）
**派发**：plan 排了 6 条，实际派发 4 条（运行时 child-Agent 并发上限 4）

---

## 模块 11.2.2.1 退货入库

**这个功能做什么**：退货的包裹寄回来了，仓库扫码核对 SKU 与序列号、清点数量、
拍照留证，判定外包装和商品各自的状态，然后登记成一条退货实收记录并挂到对应的
退货授权行上。退回的商品**先进待检状态**，不是可售。

**技术落地**：

- 迁移：`V172__trade_return_receipt_intake.sql` — 5 张表（`relforcerowsecurity = true` 已逐张断言），
  4 个触发器，硬规则 CHECK
- 端点：`/trade/return-receipts` × 9 个（ReturnReceiptController）
- 页面：`/aftersales/return-receipts`
- 测试：`ReturnReceiptIntegrationTest` 25 例 + `ReturnReceiptTenancyIntegrationTest` 4 例

**关键设计决策**：

「待检不可售」不是靠服务层自觉，而是**钉在三处**：
① `inventory_state` CHECK 只允许一个取值；② `sellable_qty_delta` CHECK 恒 0；
③ 分录由 DB 触发器生成，服务层**根本没有写该表的代码路径**。
测试直连 SQL 插「SELLABLE」与「可售 +1」两条分录都被拒，并**正面读
`pg_get_constraintdef`** 证明被挡的确实是这两条规则 —— 不靠「没报错」推断。

「超收整笔拒绝」（不截断）是刻意的：按实际业务超收应属 11.2.2.2 实收分配，
而它未交付。11.2.2.2 落地后要回到 `insertLines` 与 `trg_guard_return_receipt_line`
同一处放宽。

## 模块 2.6.2.1 增量补拉

**这个功能做什么**：webhook 漏推或平台故障恢复后，主动按连接 + 站点 + 资源类型 +
时间窗去把缺失区间的数据补拉回来，并如实记录拉了多少、新增多少、更新多少、
去重多少。

**技术落地**：

- 迁移：`V173__incremental_backfill.sql` — `backfill_task` / `backfill_record` / `backfill_segment`
- 端点：`/channel/backfills/*` × 9 个（BackfillController）
- 页面：`/channels/backfill`
- 测试：`BackfillIntegrationTest` 19 例 + `backfill-model.test.ts` 13 例 + allowlist 4 例

**关键设计决策**：

幂等由**唯一索引**保证，不由应用层自觉：同一 `externalKey` 补拉两次，
第二次返回 `deduplicated=1, created=0`，测试**直查 `backfill_record` 断言台账仍只有 1 行**。
另有「补拉 5 次仍 1 行」「指纹变化判更新而非去重」两例。

平台增量接口形态、分页上限、限频做成**显式入参**而不是由代码推断 ——
`platform.capabilities` 没有这三个字段，而猜错的后果是「补拉看起来成功了，
其实只捞到最近几条」。本仓库无真实平台出口，故**未造假 HTTP 适配器、
未假装实现退避重试**；下游分发标 `available=false` + `ownerModule=9.1.1.1`。

## 模块 2.6.1.2 去重视图

**这个功能做什么**：平台把同一条 webhook 消息重推了好几次，面板要能看出
「这个业务事件被推了几次、每次的处理结果如何」，并保证它只被真正处理一次。

**技术落地**：

- 迁移：`V174__webhook_dedup_view.sql` — 两个视图 + 部分索引，零 `ALTER`
- 端点：`/channel/webhook-dedup/*` × 4 个
- 页面：`channel-workspace.tsx` 的 `WebhookDedupPanel`
- 测试：`WebhookDedupIntegrationTest` 22 例 + allowlist 4 例

**关键设计决策**：蓝图与实际有两处不符，实现一律对齐**实际**：

1. 蓝图写 `event_id`，真实列名是 `external_event_id`；
2. 蓝图暗示「入库时拒掉重复」，实际上游 V170 建的是**部分**唯一索引
   `WHERE duplicate_of IS NULL` —— 重复推送是**入库成行**、标 `DUPLICATE`
   并 `duplicate_of` 指回首推，不是被拒。

所以去重命中取自那些 DUPLICATE 行，分组键用 `(endpoint_id, external_event_id)`
与部分索引**同口径**。若只按 `(tenant, event_id)` 归并，会把两个入口各自独立的
事件错并成一条 —— 那才是真正的「重复业务记录」。

---

## 未并入：4.1.1.2 Listing 详情

**冲突文件**：`apps/web/src/lib/api-proxy.ts`
**根因**：该文件 `git check-attr merge` 返回 `unspecified` ——
它是多车道共用的累积型白名单，**唯一一个没有绑定 merge driver 的共享文件**。
2.6.2.1 与 4.1.1.2 都按约定在 `listings` 之后、`prefix: 'trade/'` 之前插入
新的独立元素，插入锚点相同 ⇒ 文本合并必然冲突。

**分支 `feat/4-1-1-2` @ `bf10fb76` 完好**，ahead=1 / behind=0，工作区干净，未丢失。

## L2 结果：两个模块红于既有 flake，取证后绿

首次全量 `./mvnw verify`：warehouse `Tests run: 1486, Failures: 0, Errors: 27`。

| 模块 | 现象 | 单独重跑 | 判定 |
| --- | --- | --- | --- |
| warehouse `LegalEntityTransferIntegrationTest` | 27 例全 error，根因 `EOFException` 发生在静态初始化里的 PG SSL 握手 —— **一次坏连接导致整个类初始化失败，27 例是级联** | **26/26 绿** | 既有 flake（容器/连接） |
| trade `RefundStatusTenancyIntegrationTest` | 4 例 `expected:<200/401/404> but was:<503>`，根因身份桩用 `new InetSocketAddress(0), 0)` 占**操作系统临时端口**，全量并发下被抢占 | **连跑三次，10/10 全绿** | 既有 flake ①（见 `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md`） |

判定依据：两次失败的模块，本批改动清单里**零个文件**涉及
（`git diff --name-only 8300d842 647dc079 | grep -E 'warehouse|refundstatus|identity'` 为空），
本批唯一的 trade 改动是**新增** `ReturnReceipt*` + V172。

> ⚠️ **本批的 push 建立在 flake 补跑之上，不是首次全量绿。** 如实记录，不做「一次就绿」的表述。

## 契约漂移：两个互相独立的成因（本批取证）

1. **路径序不稳**：`tools/codegen/catalog-contracts.py:201` 写快照时缺 `sort_keys=True`，
   而消费侧第 29 行是 `sorted(...)`。已提交 spec 的 path 键序实测未排序
   ⇒ 每次全量 codegen 都产生大 diff（本批 channel 出现「+4 路径却动 16,867 行」）。
2. **浮点格式漂移**：已提交 spec 里写的是 `1e-8`，Python 序列化出来是 `1e-08`。
   实测拿 Python 往返一次文件不等 —— 当把未变更的 checked-in spec 拷进 `--spec-dir`
   复用时就会凭空产生 diff。

两条车道都独立发现并**刻意 revert 掉**了无关的 trade 漂移，处置正确。

## 遗留待办

1. `apps/web/src/lib/api-proxy.ts` 未绑定 merge driver —— 多车道批次必然冲突，需用户裁决。
2. `catalog-contracts.py` 的 `sort_keys` 与 spec 复用策略待定。
3. 4.1.1.2 详情页已交付但**列表行下钻入口未加**（当时被禁止改 4.1.1.1 已交付文件），
   并入后需在列表行补入口。
4. `apps/web/src/lib/api-proxy.ts` 之外，`LegalEntityTransferIntegrationTest` 与
   `RefundStatusTenancyIntegrationTest` 的 flake 根因均已定位未修，沿用 backlog 既有建议。
