# 11.2.3.2 指令消费契约对齐（纯测试修复车道）

- **日期**：2026-10-23
- **车道**：`11.2.3.2`，分支 `fix/11-2-3-2-instruction-contract`，worktree `.worktrees/11.2.3.2`
- **性质**：**纯测试修复**（只改 `backend/services/trade/src/test/java/com/starcon/trade/ReturnQualityIntegrationTest.java` 一个文件；不改迁移、不改生产代码）

## 决策摘要

1. **失败不是回归缺陷，是 11.2.3.1 的断言钉住了一个已经过时的权限模型。**
   `ReturnQualityIntegrationTest.质量状态指令由数据库生成且恒为待消费` 断言
   「运行角色把 `instruction_status` 改成 `CONSUMED` 会被 `permission denied` 拒」。
   那句话成立的前提是 **11.2.3.2 尚未交付**（V179 在交付前把指令表的表级 UPDATE/DELETE 整个收回，
   注释原文：「消费侧：11.2.3.2 未交付 ⇒ 恒为 PENDING，本模块**没有**任何把它推进的代码路径」）。
   11.2.3.2 交付后，V337 对这条指令做了**纯增量**改动：
   - 放宽 CHECK：`instruction_status = 'PENDING'` → `IN ('PENDING','CONSUMED')`；
   - `GRANT UPDATE (instruction_status) ON return_quality_disposition_instruction TO trade_app`
     （**只授这一列**；表级 UPDATE/DELETE 仍被 V179 收回）；
   - 挂消费守卫 `guard_return_quality_disposition_instruction_consumed()`：
     只允许 `PENDING → CONSUMED`，且**必须**已存在一张 `status='EXECUTED'` 的
     `return_inventory_disposition` 顶着这条指令。

   ⇒ **业务不变式其实更强了**（从「根本改不动」变成「没有已执行处置单就改不动、且改不回」），
   **但错误信号从 `permission denied` 变成了守卫触发器的 `check_violation` 消息** ⇒ 旧断言失效。

2. **因此只改测试断言，用新契约把同一个不变式重新证明一遍，并且证得更强。**
   新断言不再是「改不动」，而是「**改得动，但只在处置单真正执行之后才改得动**」：
   - 没有已执行处置单时 ⇒ 被**消费守卫**拒（消息含「质量状态指令只能在处置单真正执行（库存已改）之后才标为已消费」）；
   - 最小权限仍然成立 ⇒ 改 `consumer_module`（**改成同一个值**）与 `DELETE` 仍被 `permission denied` 拒；
   - 正对照保留 ⇒ 同一运行角色仍能改 `return_quality_category`；
   - **正向路径补上** ⇒ 造一张 `status='EXECUTED'` 的处置单顶着这条指令后，
     同一列的 UPDATE **成功**（证明 V337 授的那一列是**可用**的，不是被守卫全堵死）；
   - 追加 ⇒ 消费**不可逆**（标错了改不回 `PENDING`）。

3. **正向路径用 SQL 夹具走完整状态机，不走 HTTP。**
   11.2.3.2 的处置单正常由服务层建，但那条链路要真连 warehouse（建单要候选库位、执行要回执），
   而 `ReturnQualityIntegrationTest` **刻意不开额外监听端口**（类注释写明：JDK HttpServer 靠端口 0
   现分配，全量并发下偶发端口被抢占 → 503，本仓已知 flake）。
   ⇒ 新增私有夹具 `seedExecutedDisposition(...)`：直连 SQL 走
   **草稿 → 待审 → 已批 → 已执行** 四步，**每一步都过 V337 的守卫触发器**
   （守卫① 挂在真实 PENDING 指令上且数量相等 / 守卫② 状态流转合法 / 守卫⑤ 提交人 ≠ 审批人且审批意见必填 /
   守卫④ 草稿之外不许改业务要素）。这是**测试夹具，不是生产逻辑**。

4. **顺带把 V337 的三张新表加进本类的 `clean()` truncate 清单。**
   不加的话，处置单夹具会跨用例残留（指令表每例被 truncate，处置单表没有）。
   加了之后每个用例都是干净库，不留脏行。

## 关键 trade-off

| 取舍 | 选择 | 理由 |
|---|---|---|
| 改迁移让旧断言继续成立 | **不改** | V337 已进过集成头并被应用过，改内容会让 Flyway checksum 校验炸掉（常驻库 `starcon-mig-v2` 尤其敏感）。 |
| 走 HTTP + warehouse 桩建处置单 | **不用**，改用 SQL 夹具 | 本类刻意不开额外监听端口（已知 flake）；且本车道是纯测试修复，不引入新的外部依赖面。 |
| SQL 夹具直接 `INSERT status='EXECUTED'` | **不**，走完整状态机四步 | 直插 EXECUTED 只过守卫①，状态机 / 职责分离 / 审批意见必填这三道闸门就没被证明；走四步等于顺带证明 V337 的守卫链接受这条合法路径。 |
| 只改断言字符串 | **不**，重排为五条分组断言 | 任务书要求「用新契约重新证明一遍并且比原来更强」；只换子串等于把不变式钉在更弱的信号上。 |
| `consumer_module` 断言用 `= 'x'` | **用 `= '11.2.3.2'`（同值）** | 同值改动在有权限时本该成功 ⇒ 被拒就**只可能是权限**，不会与那条 `CHECK(consumer_module='11.2.3.2')` 混淆。 |
| 顺手修掉文件里另一处 U+FFFD 乱码 | **不修** | 在另一个测试方法的注释里，与本车道无关；保持 diff 严格收敛。 |

## 文件清单

| 文件 | 改动 |
|---|---|
| `backend/services/trade/src/test/java/com/starcon/trade/ReturnQualityIntegrationTest.java` | 唯一改动文件。① 测试方法改名 `质量状态指令由数据库生成且恒为待消费` → `质量状态指令由数据库生成且只能被已执行的处置单消费`；② 断言按 a–e 重写；③ 新增私有夹具 `seedExecutedDisposition(...)`；④ `clean()` 的 truncate 清单加 V337 三张表；⑤ 类注释第 5 组断言描述改写（原描述已不成立）。 |

**未改动**（硬性约束）：`V337__trade_return_inventory_disposition.sql`、`V179__trade_return_quality_inspection.sql`、
任何 `src/main/java` 下的文件、`apps/web/**`、`packages/api-contracts/**`、其它测试类。

## 验证

| 闸 | 命令 | 结果 |
|---|---|---|
| L0（后端编译） | `cd backend && ./mvnw -q compile` | ✅ exit 0 |
| L0（类型） | `pnpm typecheck` | ✅ `Tasks: 3 successful, 3 total` |
| L1（模块闸） | `./mvnw -pl services/trade -am test -Dtest='ReturnQualityIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false` | ✅ `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`（**跑绿 4 次**，结果完全一致；另有 2 次**故意跑红**的变异验证，见下表） |

**变异验证（证明新断言不是空跑）**：

| 变异 | 结果 |
|---|---|
| ④ 的期望状态 `CONSUMED` → `MUTANT-SHOULD-FAIL` | ❌ 变红：`expected: "MUTANT-SHOULD-FAIL" but was: "CONSUMED"` ⇒ 正向路径真的把指令推成了已消费 |
| ① 的期望守卫消息 → `MUTANT-GUARD-MESSAGE` | ❌ 变红，实际抛的是 `ERROR: 质量状态指令只能在处置单真正执行（库存已改）之后才标为已消费：instruction_id=8fc6cfc5-...` ⇒ 拒的确实是消费守卫 |

**容器**（L1 期间逐秒采样 + 抓全量清单）：

| 口径 | 结果 |
|---|---|
| **本车道自己的 L1 运行期间** | 存活 `postgres:16-alpine` **1 个**（本测试类自己的 testcontainer，容器名 `relaxed_wright`）+ `testcontainers/ryuk:0.12.0` **1 个**（testcontainers 的回收哨兵）；`docker ps` 总数 **14**（基线 12 + PG + ryuk）。全量清单见 `.tmp/containers-inventory.txt`。 |
| **机器全局峰值**（同一时间窗内） | `postgres:16-alpine` 最多 **4 个** —— 这是**并发车道的容器**（`12.1.1.2` / `12.1.2.1` / `12.2.1.1` 三条同时 `running`）加上本车道自己的那一个，**不是本车道一次开多个**。 |
| **结束前是否回收** | ✅ 全部回收：本车道 L1 结束后 `postgres:16-alpine` 归 0；全部跑完（含变异验证与最终复跑）后 `docker ps -a` 里 `postgres:16-alpine` 与 `testcontainers/ryuk` **均为 0 条**（连停止状态的都没有），`docker ps` 总数回到基线 **12**。正常路径是本类 `@AfterAll` 的 `pg.stop()` 显式停掉，ryuk 是兜底（它也在 JVM 退出后随之消失）。 |

> 采样方法的两处坑（都已按全量清单更正，别照抄这个过滤写法）：
> ① `docker ps --filter ancestor=testcontainers/ryuk` **匹配不到带 tag 的镜像**（实际是 `testcontainers/ryuk:0.12.0`），
>    按镜像名 grep 才准 —— 早期一版采样因此误判「ryuk 从未起过」。
> ② 只统计 `postgres:16-alpine` 的逐秒采样会把**并发车道**的容器算进来，
>    回答「本车道一次存活几个 PG」必须以**抓全量清单时本车道自己的容器**为准。

## 后续待办

1. **无新增迁移需求。** 本车道全程没碰迁移；V337 的权限模型与守卫逻辑足以表达新契约。
2. **`ReturnQualityIntegrationTest` 里另有一处 U+FFFD 乱码**（第 928 行注释「绕␦服务层」）——
   与本车道无关，未修；可留给该文件的下一次改动顺手清掉。
3. **集成头全量 `mvnw verify` 需要调度者跑一次**，确认这条用例在「全套 trade 用例同跑」的并发/端口环境下
   依旧稳定（本车道只跑了单测试类，未跑全量 trade）。
4. **调度者并入时注意**：本 commit 只动一个测试文件，与其它三条车道（若也动 trade 的测试）无路径重叠，
   但都落在 `backend/services/trade/src/test/java/com/starcon/trade/` 目录，合并顺序仍需人工确认。
