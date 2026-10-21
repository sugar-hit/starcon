# 2026-10-20: batch-37 L2 —— channel 测试清理补 cascade

- **日期**：2026-10-20
- **车道**：`fix/batch-37-l2-green`（worktree `.worktrees/fix-batch37`，基线 = 集成头 `integration/module-dev` 的 `1db7a43c`）
- **性质**：跨车道互相破坏的**测试清理代码**修复。**零业务逻辑改动、零迁移文件改动、零断言改动。**

## 摘要

batch-37 集成头 L2 实测暴露 3 个 channel 测试类全红（Errors 合计 39），报错原文是
`cannot truncate a table referenced in a foreign key constraint`。根因是本批并入的两个新迁移
引入了**真实的 FOREIGN KEY 约束**，而这 3 个测试类的 `truncateAll()` 没有写 `cascade`。
修复方式：给 7 个测试类的 `truncate` 语句补 `cascade`（表清单一个字没动），
另加 4 个当前是绿的同类隐患类做预防性加固。

## 决策 / 结论

1. **根因是「测试清理动作」而非业务断言**。集成头 L2 的失败全部发生在 `@BeforeEach` 的
   清理动作里（`truncateAll()` 直接抛 `RuntimeException`），**没有一个业务断言失败**。
   所以这是纯粹的「测试没跟上 schema 演进」。

2. **触发外键的三个约束（已取证，未改动）**：
   - `V216__logistics_exception_case.sql:169` —
     `logistics_exception_case` → `FOREIGN KEY (tenant_id, subject_id) REFERENCES logistics_tracking_subject(tenant_id, id)`
   - `V216__logistics_exception_case.sql:173` —
     `logistics_exception_case` → `FOREIGN KEY (tenant_id, source_event_id) REFERENCES logistics_tracking_event(tenant_id, id)`
   - `V217__logistics_pickup_signoff.sql:179` —
     `logistics_pickup_signoff_case` → `FOREIGN KEY (tenant_id, source_manifest_item_id) REFERENCES handover_manifest_item(tenant_id, id)`
   - （`V217` 另有 → `handover_batch` / `logistics_tracking_subject` / `logistics_tracking_event`
     的引用，同样是 `ON DELETE RESTRICT`）

3. **PostgreSQL 的规则决定了改法**：只要被 TRUNCATE 的表被别的表以外键引用，
   且引用方**不在同一条 TRUNCATE 语句里**，PG 就直接拒（不会级联）。
   参考 PG 报错原文的建议：`Truncate table "logistics_exception_case" at the same time, or use TRUNCATE ... CASCADE.`

4. **本仓已有正确写法，照抄即可**。同目录的 `TrackingQueryIntegrationTest`、
   `LogisticsExceptionIntegrationTest`、`LogisticsTimelinessIntegrationTest`
   **早就写了 `cascade`**，所以它们在本批迁移落地后依然全绿 —— 这三条正好是「对照组」，
   证明问题只出在没写 `cascade` 的那几个类上。本次改动沿用 `LogisticsTimelinessIntegrationTest`
   的注释写法（中文注释说明为什么要 cascade）。

5. **顺带加固 4 个类**：`ReturnDisposition*` / `ReturnShipback*` 这 4 个类当前是绿的
   （`return_*` 系列表目前**还没有任何 FOREIGN KEY**，只有触发器校验），
   但属于同一类隐患：以后任何车道给这些表加外键就会重演今天这个红。
   一并补上 `cascade` + 注释，避免再开一次同样的修复车道。

## 关键 trade-off / 否决的方案

- **❌ 否决「删掉 V216 / V217 的外键」**。那会削弱租户作用域内的引用完整性 ——
  `logistics_exception_case.subject_id` 必须指向**同租户**的 `logistics_tracking_subject`，
  这条复合外键正是本仓多租户纪律（ADR-0002）在 DB 层的落点。更关键的是：
  这两个迁移**已被并入集成头**，改它会让 Flyway 校验和（checksum）不匹配，
  已经在生产/其他环境跑过 V216/V217 的库会直接启动失败。
- **❌ 否决「把引用方也加进 truncate 清单」**。`logistics_exception_case` /
  `logistics_pickup_signoff_case` 属于**别的模块**的表，把它们塞进本模块测试的清理清单，
  等于让 A 模块的测试去删 B 模块的事实表 —— 一旦对方模块加字段改表，这份清单就会变成
  跨模块的隐式耦合。`cascade` 表达的是「连同被引用的行一起清空」，语义上更准确：
  引用方本来就是空的（被级联清掉的是它指向我们的那些行）。
- **✅ 选「truncate 清单不变 + 末尾加 `cascade`」**。改动面最小、语义最清楚，
  与本仓既有 3 个绿测试的写法完全一致。

## 涉及文件

**必改（修复前就是红的，3 个类 / 39 个 Error）**

| 文件 | 改了什么 |
| --- | --- |
| `backend/services/channel/src/test/java/com/starcon/channel/LogisticsPickupSignoffIntegrationTest.java` | `truncateAll()` 末尾加 `cascade` + 中文注释（点名 V216/V217 的三处引用） |
| `backend/services/channel/src/test/java/com/starcon/channel/LogisticsPickupSignoffTenancyIntegrationTest.java` | 同上 |
| `backend/services/channel/src/test/java/com/starcon/channel/HandoverBatchIntegrationTest.java` | 同上（该类是本批之前就绿的**既有测试**，被本批迁移打破） |

**顺带加固（修复前是绿的，同类隐患）**

| 文件 | 改了什么 |
| --- | --- |
| `backend/services/channel/src/test/java/com/starcon/channel/ReturnDispositionIntegrationTest.java` | `truncateAll()` 末尾加 `cascade` + 中文注释 |
| `backend/services/channel/src/test/java/com/starcon/channel/ReturnDispositionTenancyIntegrationTest.java` | 同上 |
| `backend/services/channel/src/test/java/com/starcon/channel/ReturnShipbackIntegrationTest.java` | 同上 |
| `backend/services/channel/src/test/java/com/starcon/channel/ReturnShipbackTenancyIntegrationTest.java` | 同上 |

**未改动（刻意）**：任何 `.sql` 迁移文件、`src/main/java` 下的任何一行、任何测试断言。

## 验证结果

### L1（只跑这 7 个类）

```bash
cd backend && ./mvnw -pl services/channel -am test \
  -Dtest='HandoverBatchIntegrationTest,LogisticsPickupSignoffIntegrationTest,LogisticsPickupSignoffTenancyIntegrationTest,ReturnDispositionIntegrationTest,ReturnDispositionTenancyIntegrationTest,ReturnShipbackIntegrationTest,ReturnShipbackTenancyIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
```

**`BUILD SUCCESS`**，Reactor：`backend` SUCCESS / `tenancy` SUCCESS / `channel` SUCCESS (38.213 s)。

| 测试类 | Tests run | Failures | Errors | 修复前 Errors |
| --- | --- | --- | --- | --- |
| `HandoverBatchIntegrationTest` | 14 | 0 | **0** | 14 |
| `LogisticsPickupSignoffIntegrationTest` | 22 | 0 | **0** | 22 |
| `LogisticsPickupSignoffTenancyIntegrationTest` | 3 | 0 | **0** | 3 |
| `ReturnDispositionIntegrationTest` | 26 | 0 | **0** | 0（加固） |
| `ReturnDispositionTenancyIntegrationTest` | 3 | 0 | **0** | 0（加固） |
| `ReturnShipbackIntegrationTest` | 29 | 0 | **0** | 0（加固） |
| `ReturnShipbackTenancyIntegrationTest` | 3 | 0 | **0** | 0（加固） |
| **合计** | **100** | **0** | **0** | **39 → 0** |

判据满足：7 个类全绿，Errors 合计 0（原 39）。

### TS 侧（本次只碰 Java，理论上不受影响）

- `pnpm typecheck` → **exit 0**，`Tasks: 3 successful, 3 total`（全部 cache hit）。
- `pnpm lint` → **exit 0**，`Tasks: 3 successful, 3 total`。
  输出里只有 `react-hooks/exhaustive-deps` 的 **Warning**（`variant-combination-workspace.tsx` /
  `pending-shipment-workspace.tsx` / `goods-receipt.tsx` / `putaway-task-workspace.tsx`），
  均为**既有**告警，与本次 Java 侧改动无关；**无 error**。

### Spotless（额外自查，非本次派发范围，但 CI 是硬门禁）

CI 的 backend job 跑 `./mvnw -B -ntp verify spotless:check`（`.github/workflows/ci.yml:100`，Java 21），
所以本车道顺手量了一次，**结论是：本次改动对 spotless 中性**。

- **本机默认 JDK 25 跑不了 spotless**：`google-java-format` 1.22.0 与 JDK 25 不兼容
  （`NoSuchMethodError: com.sun.tools.javac.util.Log$DeferredDiagnosticHandler.getDiagnostics()`），
  插件直接起不来。**必须用 JDK 21**（`/Users/guan/Library/Java/JavaVirtualMachines/openjdk-21/Contents/Home`）
  才跑得动 —— CI 用的是 21，所以这不是 CI 的问题，是本机默认 JDK 的问题。
- **集成头基线上 `services/channel` 模块本身就有 239 个文件违反 spotless**（`Violations also present in 238 other files`）。
  典型样例：`try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());`
  这一行 104 列，gjf 要拆行 —— **仓库现有代码本来就没被 gjf 格式化过**。
  这是**既有**状态，不在本车道范围内（重新格式化 239 个文件会横扫其他车道的文件）。
- **本次改动前 / 后的违规文件数完全一致（均为 239）**：
  用 `git stash` 把这 7 个文件退到基线跑一遍 `spotless:check` 得 238 other files，
  恢复后再跑同样是 238 other files。
- **这 7 个文件在改动前就已经在违规名单里，改动后仍在**：逐文件用 gjf 规范化前后对比
  （`gjf(基线) vs gjf(改动后)`），diff 只包含**我这次的改动本身**，
  且 **gjf 原样保留了我的中文注释**（它只重排代码、不动注释）。
  即：**没有任何文件因为本次改动从 CLEAN 变成 VIOLATION**。

### 未跑

`./mvnw verify` 全量 —— 那是调度器的 L2，每批只跑一次，本次刻意不跑。

## 后续待办

- 无。集成头合入本车道分支后重跑一次全量 L2 即可。
- **给后续车道的通用提醒**：channel 服务里任何新增外键的迁移，
  都必须同步检查同目录集成测试的 `truncate` 语句是否需要 `cascade`。
  本仓已有绿测试（`TrackingQueryIntegrationTest` 等）是对的范式。
- **（既有、不在本车道范围）** `services/channel` 模块有 239 个文件违反 spotless，
  CI 的 `spotless:check` 门禁在集成头基线上就是红的。
  要么单独开一条「跑一遍 `spotless:apply`」的批量车道，要么把门禁降级为 warn。
  **注意**：跑 `spotless:apply` 会大面积改动其他车道的文件，必须独立成车道，不能塞进业务批次。

## 关联

- ADR-0002 多租户策略（复合外键 `(tenant_id, id)` 是租户作用域引用完整性的 DB 落点）
- `docs/architecture/multi-tenancy.md`
- `docs/sessions/2026-10/2026-10-20-logistics-exception-case.md`（V216 的设计会话）
- `docs/sessions/2026-10/2026-10-20-logistics-pickup-signoff.md`（V217 的设计会话）
