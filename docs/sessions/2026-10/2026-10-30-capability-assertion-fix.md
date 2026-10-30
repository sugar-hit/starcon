# 2026-10-30: 13.6.1.1 能力声明过期断言修复

## 摘要

修复能力蓝图模块 **13.6.1.1「自定义报表」**（卷 13 L3 13.6.1，D7 阶段）里**过期**的能力声明断言。13.6.2.1「导出与订阅」交付后，把自定义报表能力声明中的 `exportAvailable` / `deliveryAvailable` 从 `false` 翻成了 `true`，并清空了对应的 `exportUnavailableReason` / `deliveryUnavailableReason` —— **生产行为是正确且有意为之**，但 13.6.1.1 交付时把「导出与投递尚未交付」写成了断言，于是 13.6.1.1 的 `CustomReportIntegrationTest` 与 13.6.2.1 的 `ExportSubscriptionIntegrationTest` 互相矛盾。本车道**只改测试预期**，不动生产代码、不动迁移、不动端点、不动前端、不动台账。

## 决策 / 结论

1. **这是过期测试，不是生产缺陷**。13.6.2.1 已补齐导出（Excel / CSV / PDF + 异步任务）与实际投递（传输层回报成功才写 DELIVERED），能力声明如实翻成 `available=true` 是**对的**；留旧断言只会让集成闸永远红。因此**修测试，不动生产代码**。

2. **实际要改 6 处，不是报告里写的 2 处**。surefire 只报 2 个 failures，是因为 AssertJ 在第一处失败就抛异常中止了同一个测试方法里**后续所有断言** —— 只改报错的那 2 处，重跑会接着红。取证方式：对整个测试文件 grep `exportAvailable|deliveryAvailable|UnavailableReason|notDelivered`，确认命中且仅命中这 6 行（L285 / L287 / L288 / L290 / L865 / L866），没有第 7 处。

3. **保留 3 处不变**（实测已通过，改了反而会引入错误）：
   - L286 / L289 `exportOwnerModule()` / `deliveryOwnerModule()` `contains("13.6.2.1")` —— **归属模块没变**，变的只是「是否已交付」。
   - L815 `result.note()).contains("未交付")` —— 那是 `run()` 的 note（报表运行结果说明），与能力声明无关。

4. **断言取值与新测试对齐**：`exportUnavailableReason()` / `deliveryUnavailableReason()` 由 `isNotBlank()` 改为 `isEmpty()`（对应 13.6.2.1 新测试 `ExportSubscriptionIntegrationTest` L681 / L683），两个 `available` 由 `isFalse()` 改为 `isTrue()`，HTTP 侧 `jsonPath("$.exportAvailable" / "$.deliveryAvailable")` 由 `value(false)` 改为 `value(true)`。

5. **方法名不能带 `.`**：改名 `能力声明显式标出未交付的导出与投递` → `能力声明反映导出与投递已交付` 时，第一版写成 `能力声明反映导出与投递已由 13.6.2.1 交付`，**`13.6.2.1` 含小数点是非法 Java 标识符** ⇒ `testCompile` 直接报「需要'('」两处错误。教训：**Java 方法名不能用模块号**，模块号只能进 `.as(...)` 描述字符串（字符串字面量里带点没问题）。

6. **L0 编译闸不编译测试源码**：首轮 `./mvnw -q compile` 通过（exit 0），但同一份代码在 L1 的 `testCompile` 阶段就炸了 —— 上一轮 **L0 绿灯不等于测试源码可编译**，L1 才是权威闸。

## 关键 trade-off / 讨论

- **改测试 vs 改生产代码**：另一种做法是把生产代码的 `exportAvailable` 改回 `false` 让旧断言通过。**拒绝** —— 那会让界面继续显示「未交付」，是对 13.6.2.1 已交付能力的**反向造假**，也与本项目「能力声明必须诚实」的核心纪律冲突（见 13.6.2.1 会话第 19 条「绝不允许未发出却标 DELIVERED」）。

- **改文案 vs 只改断言**：`.as("…未交付")` 描述与 `isTrue()` 并存会自相矛盾（描述说未交付、断言说已交付）。故一并改为表达「已由 13.6.2.1 交付」。同理方法名也不再声称「未交付」。**保留旧文案同样能过测试，但语义会自相矛盾** —— 不留。

- **不顺手重构**：只改这 6 处断言 + 相关文案，测试结构、其他用例、命名风格一律不动。

## 涉及文件

**修改（唯一）**

- `backend/services/reporting/src/test/java/com/starcon/reporting/CustomReportIntegrationTest.java`
  - L283 方法名：`能力声明显式标出未交付的导出与投递` → `能力声明反映导出与投递已交付`
  - L285：`exportAvailable()` `.as("导出属 13.6.2.1，未交付").isFalse()` → `.as("导出已由 13.6.2.1 交付").isTrue()`
  - L287：`exportUnavailableReason()` `.isNotBlank()` → `.isEmpty()`
  - L288：`deliveryAvailable()` `.as("实际投递属 13.6.2.1，未交付").isFalse()` → `.as("实际投递已由 13.6.2.1 交付").isTrue()`
  - L290：`deliveryUnavailableReason()` `.isNotBlank()` → `.isEmpty()`
  - L865 / L866：`jsonPath("$.exportAvailable" / "$.deliveryAvailable")` `.value(false)` → `.value(true)`

**新增**

- `docs/sessions/2026-10/2026-10-30-capability-assertion-fix.md`（本文件）

**未改动**：生产代码、Flyway 迁移、端点、前端页面、`docs/product/capability-blueprint/module-progress.md` 台账（13.6.1.1 那行本就是「已交付」，本次只是修复既有交付的测试预期）。

## 验证

- **L0 编译闸**：`cd backend && ./mvnw -q compile` ✅ exit 0（首轮通过，末轮改动后再跑一次仍 exit 0）。
- **L1 模块闸**：
  ```bash
  cd backend && ./mvnw -pl services/reporting -am test \
      -Dtest='CustomReportIntegrationTest*' \
      -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
  ```
  ✅ **Tests run: 34, Failures: 0, Errors: 0, Skipped: 0**（`CustomReportIntegrationTest`，耗时 46.52 s），Reactor `backend / tenancy / reporting` 全 SUCCESS，BUILD SUCCESS，总耗时 01:09 min。实跑 Testcontainers PostgreSQL 16（16.15）。
- **容器**：测试期本车道 PG 容器 `postgres:16-alpine`（容器名 `sleepy_thompson`，id `1b004784ab02`）+ `testcontainers/ryuk:0.12.0`（id `e71fdc229c3a`）并存；测试结束 20s 后 `docker ps -a` 中**两者均已消失** ⇒ 已被 ryuk 回收。同期机器上的 `postgres:15-alpine`（`langfuse-postgres-1`）与其它容器属用户常驻服务，**不属本车道**。
- 未跑 `./mvnw verify` 全量（按调度约定由调度器在集成头每批只跑一次）；本车道无前端改动，未跑 `pnpm typecheck` / `pnpm vitest`。

## 后续待办

- 无新增功能待办。集成头合并后，13.6.1.1 与 13.6.2.1 的能力声明断言已**语义一致**（都断言「导出与投递已交付」），后续再有模块改动 `capabilities()` 时，两处测试会一起提醒。
- 长期教训已在本文件「决策 / 结论」第 5、6 条留档：**Java 方法名不能含模块号；L0 编译闸不覆盖测试源码**。

## 关联

- `docs/product/capability-blueprint/module-progress.md`（13.6.1.1 / 13.6.2.1 行，本次**未改动**）
- `docs/sessions/2026-10/2026-10-30-export-and-subscription.md`（13.6.2.1 交付会话 —— 能力开关翻真的来源）
- `docs/sessions/2026-10/2026-10-29-custom-report.md`（上游 13.6.1.1）
- `docs/sessions/2026-10/2026-10-30-1100-pipeline-round131-gate-held-peer-l2-red-capability-assertion.md`（定位到本红因的调度会话）
