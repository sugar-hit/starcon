# 2026-10-07 BOM 展开（3.5.2.2，阶段 D1–D2）

分支 `feat/3-5-2-2-bom-explosion`，基线 `feat/3-5-2-1-bom-version`（7cf42be）。

## 决策摘要

### 1. 展开结果落库，冻结的是「建议」而不是库存事实

蓝图说「展开结果是建议不是事实，实际采购由 06 生成单据后成立」。这句话容易被读成
「那就不用落库，现算就行」。本轮**没有**这样读：

- 06 的采购单引用的是**某一次展开的 id**，不是「现在的 BOM × 现在的库存」。
  后者随时会变，单子发出去之后就无法解释「当时为什么按这个数下单」。
- 所以 V26 建了 `bom_explosion_run` + `bom_explosion_line` 两张表，冻结的是
  **规则、BOM 版本、仓库过滤、as_of、逐行的用量与损耗率**，全部在行里。
- 但库存数字**仍然只有 warehouse 有**。catalog 不复制任何库存快照：
  表里的 `available` / `in_transit` 是「这一行当时算的时候，库存服务说的是这个数」，
  它是展开结果的组成部分，不是库存的第二份权威。

### 2. 跨服务只走授权 API，且租户上下文必须透传

库存事实在 warehouse，BOM 事实在 catalog。跨库 JOIN 会把两个服务绑死在同一个 PG 实例上，
跨库 FK / 视图同理。所以：

- warehouse 新增 `POST /warehouse/inventory/availability`（批量可用量，按 SKU 集合 + 可选仓库）。
- catalog 用 `RestClient` + `@Value` 调它，写法与既有 `AccessService` 完全同构。
- **catalog 把调用者的 `Authorization` 头原样透传过去**，租户仍由 warehouse 向 identity
  从已认证成员关系解析。这里特意**没有**发明 `X-Tenant-Id` 之类的头：
  那等于新建一条「客户端自称租户」的旁路，正是 AGENTS.md 明令禁止的。
- 测试里用一个 `HttpServer` 起确定性替身满足它，并**断言替身确实收到了 Authorization 头与
  warehouseId** —— 证明的是生产代码真的走 HTTP 且没丢租户上下文，而不是把替身逻辑塞进生产代码。

### 3. 循环引用直接拒绝，不截断

V25 的 `bom_component` 只引用 SKU 不引用 BOM，嵌套靠「子 BOM 的成品 SKU 出现在父 BOM 的
组件行里」表达，所以**循环引用在数据库层做不到约束** —— 只有真正展开时才知道绕回自己。

选择 409 拒绝而不是设最大深度截断，理由写在 V26 文件头：绕回自己意味着这批料的需求量
**在数学上无界**，任何有限的截断结果都是一个错的小数，而它会被当成采购建议下成真实的单。
错误信息逐个点名绕回路径（成品A → 半成品B → 成品A）与所在层级，否则运营只知道「有循环」
却不知道改哪一行。**拒绝必须无副作用**：一条记录都不落，测试钉死了这一点。

深度上限是另一回事 —— 合法 BOM 树可以很深，所以那里**截断但显式标明**
（`status=TRUNCATED` + 行级 `truncated` + note 写明「这份清单不完整」）。
静默死循环与静默截断都算未完成。

### 4. 逐层结转：子层用父行的缺口而不是需求量

这是本模块最容易被做错、且错了最贵的一处。

半成品 SA 本仓已有 4 个、在途 3 个，这次要用 10 个 → 只差 3 个才需要向下展开。
若按 10 展开，SA 下面的面料会被多算 7 份的量，而那 7 份 SA 根本不生产 ——
多算出来的料会变成一张**真实的采购单**，也就是凭空多花的钱。

蓝图要的是「需要采购/备货的组件清单」，采购口径必然是净额。所以顶层驱动量 = 成品数量，
子层驱动量 = 父行的缺口。公式本身仍是 R7 的同一句，转发 `BomRules.requiredQuantity`，
不复制 —— 「同一句公式写两份，迟早有一份被改松」，而那处偏差只在跨模块比对里才暴露。

### 5. 逐层推进而不是先走完整棵树再取数

判定某个子装配要不要继续展开，需要先知道**它自己的缺口**，而缺口要等 warehouse 返回可用与在途。
所以实现上按层推进，每层 1 次组件查询 + 1 次子 BOM 判定 + 1 次批量可用量。

若改成「先把树走完再统一取数」，会对**已被库存完全覆盖**的半成品也递归下去，
产生一串 0 需求的噪声行，更糟的是可能在**不该展开的区域**里撞上循环引用而误报。

## 关键 trade-off

| 取舍                   | 选择                                                                          | 代价                                                                                                 |
| ---------------------- | ----------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| 循环引用               | 409 拒绝                                                                      | 遇到循环的用户暂时拿不到清单，必须先改数据。换成截断的话错的小数会变成真实采购单                     |
| 深度上限               | 截断 + 显式标注                                                               | 清单可能不完整，页面上必须显眼，运营要能看懂「为什么不全」                                           |
| 缺口下限               | 截 0，富余量另给 `surplus`                                                    | 多一列，但避免了缺口列出现负号                                                                       |
| 歧义子 BOM             | 按 `(code,id)` 确定性取第一个 + 告警                                          | 结果「能用」但数据有问题；另一条路是直接拒绝，那会让这类租户完全展不开                               |
| 展开权限               | `spu.read`（纯计算）                                                          | 落库的行来自只读用户。但改动 BOM/组件的入口仍是 `spu.update`，落库的也只是调用者自己这份建议快照     |
| `WAREHOUSE_URL` 默认空 | 其余 14 个 catalog 集成测试不碰展开路径，不被一个用不上的配置项卡在启动失败上 | 真实调用时才报错并说清原因                                                                           |
| 子 BOM 无生效版本      | 按叶子件处理 + note 说明                                                      | 结果里有一行「子装配（未展开）」，但不下钻。另一条路是报错，那会让「只有草稿子 BOM」的租户完全展不开 |

## 文件清单

### 迁移

- `backend/services/catalog/src/main/resources/db/migration/V26__bom_explosion.sql`（新）

### catalog 后端

- `BomExplosionService.java`（新）：逐层推进的递归展开
- `BomExplosionRules.java`（新）：纯规则层（R6/R7 转发 BomRules，自有 E1/E2/E4）
- `InventoryAvailabilityPort.java`（新）：跨服务读取口 + 真实 HTTP 实现
- `InventoryAvailabilityService.java`（新）：Spring 装配与配置校验
- `BomExplosionController.java`（新）：`POST/GET /catalog/bom-explosions`
- `CatalogModels.java`（改）：+4 个 `BomExplosion*` 传输模型

### warehouse 后端

- `InventoryAvailabilityService.java`（新）：批量可用量聚合
- `InventoryController.java`（改）：+1 端点
- `WarehouseModels.java`（改）：+3 个 `Availability*` 传输模型

### 前端

- `bom-model.ts`（改）：展开工作区的口径固化与读模型
- `bom-workspace.tsx`（改）：展开工作区 UI
- `bom-model.test.ts`（改）：38 → 51 例
- `api/catalog/[...path]/route.ts` 白名单 + `route.test.ts`（改）
- `packages/api-contracts/`（codegen 产物，只由脚本生成）

### 配置与文档

- `deploy/docker/docker-compose.catalog.yml`（改）：`WAREHOUSE_URL`
- `docs/product/capability-blueprint/module-progress.md`（改）：3.5.2.2 一行

### 测试

- `BomExplosionIntegrationTest.java`（新，13 例）
- `BomExplosionRulesTest.java`（新，11 例）
- `InventoryAvailabilityIntegrationTest.java`（新，warehouse 8 例）

## 过程中修掉的两个真实缺陷

1. **单例服务上的可变中间态**（自查发现，未上线）：第一版把「每层的可用/在途」暂存在
   service 的实例字段上。Spring 单例 + 并发请求会互相覆盖，**甚至把 A 租户的可用量算进
   B 租户的缺口**。改成方法内局部返回 `Map<UUID, Stock>`。
2. **子 BOM 歧义告警被吞掉**（测试先红后绿发现）：`note` 原本被展开说明整体覆盖，
   于是恰恰在真正展开歧义行的那一刻把「被 2 个 BOM 同时声明为产出」的告警盖掉 ——
   而那正是最需要看见它的时刻。改成基础说明在前、展开/截断/覆盖说明追加在后。

## 后续待办

- **06 采购与供应**：消费 `GET /catalog/bom-explosions/{id}`，取 `gap > 0` 的行生成采购单。
  取整、补安全库存、合并同一 SKU 的多行是 06 的决定 —— 本模块只给建议。
- **07 库存补货**：同一批行作为补货建议输入。
- **循环引用的预防**：现在只有展开时才报（409）。若希望在**保存组件时**就拦住，
  应在 3.5.2.1 的 `replaceComponents` 加校验（3.5.1.2 套装侧已有循环检测，BOM 侧还没有）。
- **展开基准时刻的选择器**：目前是手填 ISO 串，可考虑接一个现成时刻控件。
- **warehouse 侧 `stock_bucket` 与本模块的关系**：当前可用量取的是 balance 级（仓库级决策单位），
  与 V1 的口径一致；若 07 补货需要批次/库位维度，应由 warehouse 另出端点，而不是在本模块里下钻。
