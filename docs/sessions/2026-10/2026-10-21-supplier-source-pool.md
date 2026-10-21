# 6.1.2.1 档口与货源池（trade）

> 车道：6.1.2.1 · 分支 `feat/6-1-2-1` · 基线 `integration/module-dev` 1f628c40
> 日期：2026-10-21

## 决策摘要

**模块**：6.1.2.1 档口与货源池（蓝图 06-purchase-and-supply.md），后端落 **trade** 服务，命名空间
`/trade/sources`，前端路由 `/supply/sources`（与 6.1.1.x 供应商共用 L3「供应」入口，各占一个独立分区）。

**蓝图加粗句「货源数据是外部快照，会变」是本模块的立身之本**，它决定了三条结构决策：

1. **「当前价」与「快照价」不混成一列。**
   价格区间 / 起订量 / 标题 / 采集时刻**只**存在 `supplier_source_snapshot`（只追加，一列都不可改写）；
   `supplier_source_item` 是货源**身份**，**一个价格列都没有**，只带一个 `current_snapshot_id` 指针。
   「当前价」= 指针所指那一版里的值。表结构上不存在「当前价」这个概念，因此不存在两列打架。
2. **旧快照永不改写。**
   每次同步**新增一版快照**并前移指针，旧版原地保留；
   `guard_supplier_source_snapshot_immutable` 触发器钉死 snapshot 表的 UPDATE 与 DELETE。
   于是「上周三看到的那个价是什么」永远答得出来。
3. **区间永远是区间。**
   `price_min` 与 `price_max` 同时存在、同时返回（DB CHECK + 触发器双闸）；
   比价时区间重叠就返回区间，不压成一个「精确价」。

**上游是外部平台（主动抓取，不是读库）**：统一接口 `SupplierSourceGateway` + 两个独立 vendor 实现
（`Alibaba1688SourceGateway` / `YiwuGouSourceGateway`），真实 HTTP 客户端，**默认未接线** ——
未登记采集接入时**显式失败并写明缺哪个字段**，绝不返回一批编造的货源。
采集方式两支：`URL_SYNC`（本服务主动抓）与 `PLUGIN_PUSH`（蓝图输入句「插件采集」那条路径，
**不需要任何外部凭证**，是本模块在未接线状态下仍能真实运转的方式）。

**供应商与内部 SKU 在 catalog（另一个库）**：只经授权只读 API（`SupplierPartyQueryPort` /
`SourceSkuQueryPort`），库里**只存逻辑 ID + 名称/编码快照**，**零跨库 FK、零跨库 SQL、零跨库视图**。

## 关键 trade-off

| 取舍                   | 选择                              | 理由                                                                                                  |
| ---------------------- | --------------------------------- | ----------------------------------------------------------------------------------------------------- |
| 「当前价」怎么存       | 身份表存指针，快照表存值          | 两列并存必然出现「哪个是真价」的争论；指针 + 只追加快照让两者在结构上不可能混                         |
| 快照不可改写靠什么保证 | DB 触发器（不是服务层自律）       | 服务层能被绕过；触发器是最后一道闸，测试里**直接改库**也要被拒                                        |
| 一次采集没出现的货     | **不**判成下架                    | 平台列表可能分页/局部返回，判成下架是在编造事实；在售只由平台显式返回的 `on_sale` 决定                |
| 跨币种比价             | 不做，按币种分组给最低价          | 汇率属 12 财务，本模块没有汇率快照；把 ¥ 与 $ 放一起排序就是编一个假的「最低价」                      |
| 外部平台抓不到时       | 落 FAILED 批次 + 失败原因，零快照 | 编造的货源会一路流进比价与采购单 —— 这是本模块最危险的一种假数据                                      |
| 收藏挂在哪             | 挂**身份**（不是快照）            | 挂在快照上则每次同步收藏都消失，那不是收藏，是「收藏了这一版价格」                                    |
| 供应商/SKU 判据        | 只核对存在性，不要求 ACTIVE       | 停售 SKU 恰恰是最需要「还能从哪进货」的比价场景；与换货链路的 `ExchangeSkuQueryPort` 判据**故意不同** |
| 平台词表               | 封闭（ALIBABA_1688 / YIWU_GOU）   | 每个平台一个 vendor 适配器，不在词表里的平台没有适配器，也无从采集                                    |
| api-proxy 形状         | 独立 `services[]` 元素，锚点钉死  | 多车道共享同一文件，并进既有超长 alternation 必然判冲突（结构性，retry 不自愈）                       |

## 文件清单

**迁移（trade）**

- `backend/services/trade/src/main/resources/db/migration/V233__supplier_source_pool.sql`
  —— 七张新表（stall / collection / item / snapshot / favorite / sku_link / collector_endpoint），
  零 ALTER 任何既有表；ENABLE+FORCE RLS + GRANT；两个守卫触发器。

**后端（trade）**

- `SupplierSourceModels.java` —— 传输模型与词表枚举（30 个 DTO）
- `SupplierSourceService.java` —— jOOQ 显式 SQL、真分页真筛选、快照写入与指针前移、比价
- `SupplierSourceController.java` —— `/trade/sources/**` 18 个端点
- `SupplierSourceGateway.java` —— 外部平台统一接口
- `HttpSupplierSourceGateway.java` —— 共用底座（接入登记读取、超时上界、错误显性、BigDecimal 解析）
- `Alibaba1688SourceGateway.java` / `YiwuGouSourceGateway.java` —— 两个独立 vendor 实现
- `SupplierPartyQueryPort.java` —— catalog 供应商只读端口
- `SourceSkuQueryPort.java` —— catalog 内部 SKU 只读端口

**测试（trade）**

- `SupplierSourceIntegrationTest.java` —— 19 例，真实 PostgreSQL 16 + Testcontainers，连接池压到 1

**前端（web）**

- `apps/web/src/components/supply/sources-model.ts` + `sources-model.test.ts`
- `apps/web/src/components/supply/sources.tsx` —— 工作区 + 档口详情两个组件
- `apps/web/src/app/supply/sources/page.tsx` + `apps/web/src/app/supply/sources/[stallId]/page.tsx`
- `apps/web/src/app/api/trade/supplier-source-allowlist.test.ts` —— api-proxy 白名单双向断言
- `apps/web/src/lib/api-proxy.ts` —— **新增一条独立 `services[]` 元素**，既有条目一字未动

**契约 / 台账 / 日志**

- `packages/api-contracts/src/contracts/trade.ts` + `packages/api-contracts/src/trade.openapi.json`（codegen 产物）
- `docs/product/capability-blueprint/module-progress.md` —— 只改 6.1.2.1 那一行的「状态」「证据」
- 本文件

## 验证

| 闸             | 命令                                                                                                                                 | 结果                                                      |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------- |
| L0 编译        | `cd backend && ./mvnw -q compile`                                                                                                    | 绿                                                        |
| L0 类型        | `pnpm typecheck`                                                                                                                     | 绿（3 包）                                                |
| L1 模块        | `./mvnw -pl services/trade -am test -Dtest='SupplierSource*' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | **19 例全绿**                                             |
| 前端           | `pnpm vitest run apps/web/src/app/api/trade apps/web/src/components/supply`                                                          | **313 例全绿**（含 api-proxy 全量 allowlist 回归 244 例） |
| Lint           | `next lint --file <本车道新增文件>`                                                                                                  | 无告警                                                    |
| api-proxy 自检 | `grep -c "prefix: '"` / `git diff --numstat`                                                                                         | 110 → **111** / **43 新增、0 删除**                       |

## 后续待办

1. **6.1.2.2 同款货源推荐**（未交付）：本模块只提供**人工确认**的 SKU↔货源关联，
   不做图片相似度这类自动同款判定（误判会导致采购到错误商品）。capabilities 里已逐条标
   `available=false` + ownerModule + 影响。
2. **6.3.1.2 采购单带入货源价**（未交付）：快照当前只能被读，还不能被带入采购单；
   「下单时以实际下单金额为准」这条在结构上已经落位（快照带采集时刻，无「当前价」列）。
3. **12.1.1 汇率快照**（未交付）：本模块不跨币种比价，比价按币种分组给最低价。
4. **trade 页面权限码未在 identity 注册**（既有缺口，9.x / 11.x / 6.4.1.x 同款）：
   `trade.sources.*` 没有对应的 `page_resource`。本车道沿用兄弟模块做法**不改 identity**，
   以免与其他车道撞 identity 的迁移号；如需修复，应由调度者在集成头统一处理。
5. **契约快照的既有漂移**：`packages/api-contracts/src/trade.openapi.json` 在基线上就有 7 个
   schema 的数值格式漂移（`100` → `100.0`），与本车道无关（那些 DTO 不在本模块）；
   重新生成时一并刷新了。**TS DTO 输出是纯新增**（`trade.ts` +30 行、0 删除），消费端无影响。
