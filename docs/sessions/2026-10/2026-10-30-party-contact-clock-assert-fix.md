# 2026-10-30: 3.6.3.1 地址与联系人 —— timestamptz 守卫跨钟域断言抖动（假红）修复

> 车道 `fix/3-6-3-1-party-contact-clock-assert`（从集成头 `integration/module-dev` @ `121484f8` 切出）。
> 这是 L2 批次闸（2026-10-30 14:01）定位到的**断言抖动修复**，不是新蓝图模块 ——
> **无迁移、无端点、无页面、无契约、无台账改动**；**只改了一个测试文件**，生产代码一个字没动。

## 摘要

| 项 | 值 |
| --- | --- |
| 缺陷 | `PartyAddressContactIntegrationTest` 的 2 处时刻断言拿 **JVM 墙钟**（`Instant.now()`）比 **PG 的 `now()`** ⇒ 跨钟域比较，容差只有 5 分钟 |
| 现象 | L2 全量里 catalog 红 2 例：`建档即写入版本一且时间戳没偏`（…:249）、`联系人建档往返一致且默认只有一个`（…:751）；`difference was 8 Minutes`（实测 8 分 29 秒，同类测试类整轮跑了 304 秒） |
| 改动 | 1 个 Java 测试文件：新增 `dbNow()` / `assertWithinDbClockWindow()` 两个辅助，2 处断言改判**同钟域区间** |
| 迁移 / 端点 / 页面 / 契约 / 台账 | **无 / 无 / 无 / 无 / 无** |
| 生产代码 | **未改**（生产侧是对的：`changed_at` 由服务层写 `now()`，`created_at` 是 `DEFAULT now()`） |
| L0 | `cd backend && ./mvnw -q compile` → **exit 0** |
| L1 | `Tests run: 23, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`（用例数与改前一致 —— 本车道是修断言，**没有新增/删除用例**） |
| PG 容器 | 测试期间存活 **1** 个 `postgres:*` 容器（本用例的 Testcontainers，`postgres:16-alpine`）+ 1 个 `testcontainers-ryuk`；另有 1 个与本次无关的 `langfuse-postgres-1`（postgres:15-alpine，已跑 10 天） |

## 决策摘要

### ① 根因不是业务缺陷，是守卫跨钟域比较

旧断言（两处形状相同）：

```java
assertThat(Instant.parse(<写回值>))
    .isCloseTo(Instant.now(), within(5, ChronoUnit.MINUTES));
```

写进数据库的时刻是 **DB 的时钟域**，断言参照的却是 **JVM 墙钟**：

- `party_address_version.changed_at` —— `PartyAddressService#appendVersion` 的 INSERT 里写的是字面量 `now()`；
- `party_contact.created_at` —— V31 迁移里的 `timestamptz NOT NULL DEFAULT now()`。

两个读数之间只要隔着一次容器停顿（机器被负载挤压时可达十几分钟），守卫就把「停顿」当成了「时区漂移」误报。
本次实测差 8 分 29 秒，而该测试类整轮跑了 304 秒 —— 停顿量与整轮耗时同量级，佐证是停顿不是漂移。

**判据**：同一份 HEAD 在空闲机器上隔离复跑是全绿的 ⇒ **负载型假红**，不是回归。生产代码是对的，本车道不改生产代码。

### ② 修法：照抄先例 `67e1381e`，把参照时刻也拉到同一个钟域

先例 `67e1381e`（`test(catalog): 平台类目映射的 timestamptz 守卫改为同钟域比较，消除负载型假红`，
文件 `PlatformCategoryMappingIntegrationTest.java`）已经落地过同一套写法：仓内 catalog 的**同类守卫 3 天前修过一次**，
本车道是把它补到另一处漏网的断言上。本车道**沿用同一形状**，不另发明：

1. 新增 `dbNow()`：`tx(actor, () -> sql.fetchOne("select clock_timestamp()").get(0, OffsetDateTime.class))`
   —— 参照时刻一律从这里取（DB 自己的时钟域），**不碰 JVM 墙钟**；
2. 新增 `assertWithinDbClockWindow(dbBefore, value, dbAfter)`：断言 `value ∈ [dbBefore, dbAfter]`；
3. 在写操作**之前**取 `dbBefore = dbNow()`，在读回**之后**取 `dbAfter = dbNow()`。

**为什么用区间而不是单个 DB 基准**：停顿若发生在写入之前，单个「写入前」的基准吸收不了（差值 = 停顿全长，仍会红）；
取前后两个 DB 时刻夹住写入时刻 ⇒ 停顿只会**撑宽**区间，不会把正确时刻挤出区间；而真偏 8 小时（−28800s）
必然落在区间外 ⇒ 守卫照样当场抓住。

**关键 trade-off：为什么不是「把 5 分钟容差放大到 30 分钟」？**
那是把守卫换掉：它把「正常跑法下区间只有毫秒级」这个强约束换成了「30 分钟内都算对」，真正的时区漂移若偏 20 分钟就会漏网。
⇒ 本车道**没有放大任何容差**：健康跑法下区间宽度只有两次 DB 往返（毫秒级），比旧的「±5 分钟」**严**得多。

### ③ 守卫强度实测自证（独立复核，未留在提交里）

在 `.tmp/guardcheck/` 下用同一份 AssertJ（3.27.4）复刻 `assertWithinDbClockWindow` 的断言表达式做独立复核：

```
GUARD-REJECTS-8H-SHIFT: OK (offset=28800s, window=300ms)
GUARD-REJECTS-20MIN-DRIFT: OK (old +/-5min tolerance would have passed it)
```

⇒ 真漂移（8 小时 = 28800s）当场被拒；连 20 分钟级漂移也被拒 —— 而旧的「±5 分钟」容差会放过 20 分钟漂移。
**这坐实了「更严而不是放宽」**。该实验产物写在 `.tmp/`（已 gitignore），**不在提交里**。

### ④ 同族的其它断言**没有动**

- `地址改内容后旧版本仍是当时的内容` 里那条比的是**两个 DB 值**（`fetched.changedAt()` vs `versions.get(0).changedAt()`），
  形状本来就正确 ⇒ 原样保留；
- 多租户三断言、RLS 结构断言、HTTP 全链路断言**全部原样保留**。

## 文件清单

| 文件 | 性质 |
| --- | --- |
| `backend/services/catalog/src/test/java/com/starcon/catalog/PartyAddressContactIntegrationTest.java` | **唯一代码改动**：新增 `dbNow()` / `assertWithinDbClockWindow()` 两个辅助；改写 `建档即写入版本一且时间戳没偏`（原 249 行）与 `联系人建档往返一致且默认只有一个`（原 751 行）两处时刻断言；删掉因此不再使用的 `within` / `ChronoUnit` import，补 `OffsetDateTime` import |
| `docs/sessions/2026-10/2026-10-30-party-contact-clock-assert-fix.md` | 本文件 |

**未改**：生产代码、迁移、端点、前端、`packages/api-contracts`、台账（3.6.3.1 那行保持「已交付」未动）、backlog。

## 验证方式

```bash
# L0 编译闸（秒级，无容器）
cd backend && ./mvnw -q compile
# ⇒ exit 0

# L1 模块闸（只跑本测试类）
cd backend && ./mvnw -pl services/catalog -am test \
    -Dtest='PartyAddressContactIntegrationTest*' \
    -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoSpecifiedTests=false
# ⇒ Tests run: 23, Failures: 0, Errors: 0, Skipped: 0 + BUILD SUCCESS（01:34 min）
```

未跑全量 `./mvnw verify`（那是调度者的 L2 批次闸）；本车道无前端改动，未跑 `pnpm` 校验。

## 后续待办

- **无阻塞项。** 生产代码不需要改。
- 可选（本车道未做，超出范围）：仓内可能还有别处「JVM 墙钟 vs DB 钟」的同类写法，建议在后续 L2 闸里
  继续按「两端同钟域」的方向逐个收敛（本车道只处理调度者指派的那一处测试文件）。
- 环境备注（非本次改动引入）：
  - 本机默认 JDK 是 25，`spotless:check` 需显式指定 JDK 21
    （`JAVA_HOME=~/Library/Java/JavaVirtualMachines/openjdk-21/Contents/Home`）；spotless 未绑定到构建生命周期，
    **不影响** L0 / L1 闸。
  - catalog 模块在本次改动之前就有 **150+ 个文件**不满足 spotless（`spotless:check` 全模块跑必然红），
    本车道**没有顺手修它们**。本车道只保证**自己改的那个文件**格式合规：
    `./mvnw -pl services/catalog spotless:apply -DspotlessFiles=.*PartyAddressContactIntegrationTest\.java`
    → 改完 `spotless:check` 里已不再出现本文件。
  - google-java-format 1.22.0 折行中文 javadoc 时会在句中插入多余空格（"两台钟 准得一模一样"），
    故把长说明放在 `//` 行注释里（格式化器不动行注释），javadoc 只留摘要。
