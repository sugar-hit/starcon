# 2026-10-22: 10.4.2.2 —— 端口遮蔽型测试假红（stub 绑通配地址）

## 摘要

本轮修的是一条**测试假红**，不是新功能、也不是业务代码改动。

集成头 `integration/module-dev` 的 L2 第 1 步（`./mvnw verify`）在 **channel** 模块红：
`StockForecastAvailabilityPortTest.inTransitSumsOutstandingLegsAndKeepsMissingAsNull` 报
`503 SERVICE_UNAVAILABLE「warehouse 服务返回 404：在途数据读不到」`，
同类的另外 5 例全绿 —— 典型的**顺序 / 时序型偶发**。

根因不在被测代码，而在**测试桩的绑定地址**：
本测试的桩用 `new InetSocketAddress(0)`（通配地址 0.0.0.0），
而仓库里其它 13 个 channel 桩类用 `new InetSocketAddress("127.0.0.1", 0)`（显式回环）。
**macOS 的 JDK 默认开 `SO_REUSEADDR`** ⇒ 通配地址的 bind 在「同端口已被更具体的 127.0.0.1 绑定占用」时
**照样成功**，而客户端连上的是那台**更具体**的（= 别的测试的桩）⇒
本测试收到的是**别的桩的响应**（`404 No context found`）⇒
端口的 `catch (RestClientResponseException)` 把它翻成 **503「读不到」**。

修法：把桩的绑定改成**显式回环**，`base` 同步改成 `http://127.0.0.1:<port>`。
此后同端口冲突会**响亮地抛 `BindException`** ——
把「静默给出错误答案」变成「明确报错」。

附带修：本批 16.1.2.1 车道新增的 `PlatformViolationIntegrationTest` /
`PlatformViolationTenancyIntegrationTest` 里那几台**静态**桩**从不停机**，
端口被占住整整一轮 JVM 生命周期 ⇒ 既是资源泄漏，也是上面这个假红的放大器。

## 决策 / 结论

1. **根因是测试桩的绑定地址，不是被测端口的逻辑。**
   本机独立复现（`.tmp/PortShadowingRepro.java`）：
   ```
   other-test stub bound on 127.0.0.1:57655
   wildcard bind on SAME port 57655 => ALLOWED (SO_REUSEADDR)
   client(localhost) status=404 | <h1>404 Not Found</h1>No context found for request
   client(127.0.0.1)  status=404 | <h1>404 Not Found</h1>No context found for request
   >>> 命中了【别的测试的 stub】(404 No context found)
   ```
   ⇒ 注意 `client(127.0.0.1)` **同样**中招 ⇒ **只改 base URL 不解决问题**，
   真正的修法是**绑定地址**（绑回环后同端口冲突直接抛 `BindException`）。

2. **生产代码一行不改。**
   `ForecastInTransitQueryPort` 把 404 翻成 503「读不到」是**正确**的纪律：
   读不到就报读不到，**绝不用 0 冒充**。这条纪律被本轮的假红顺手验证了一遍。

3. **断言一例不减、一条不放宽。**
   不 `@Disabled`、不 `assumeTrue`、不改任何断言、不减少测试例数。
   6 例仍是 6 例，断言一字未动。

4. **`@AfterEach` 必须容错 `upstream == null`。**
   修完之后，端口冲突会以 `BindException` 的形式暴露 ——
   若此时 `@AfterEach` 里的 `upstream.stop(0)` 抛 NPE，就会把**真正的原因盖掉**。
   ⇒ 加 null 守卫，让「端口冲突」始终是可见的那条错误。

5. **静态桩必须 `@AfterAll` 停机。**
   `PlatformViolationRulesTest` 没有 HttpServer，无需处理；
   `PlatformViolationIntegrationTest`（identity + warehouse）与
   `PlatformViolationTenancyIntegrationTest`（identity）补上 `@AfterAll` 的 `stop(0)`。

## 关键 trade-off / 讨论

- **为什么不用「重试」或「随机端口」绕过？**
  重试掩盖根因；随机端口只是把概率问题藏得更深。
  显式回环绑定是**仓库既有约定**（channel 里 13 个桩类都是这么写的），
  照抄约定即可，不需要引入新机制。

- **「变成响亮报错」够不够？**
  够。假红的危害从来不是「这条测试偶尔红」，
  而是**它红了没人知道是环境问题**，于是在正确的生产代码上被误判为回归。
  端口冲突本来就是**环境问题**，让它以 `BindException` 的名义直接冒出来，
  比伪装成「上游 503」诚实得多。

- **为什么不顺手把全仓库的通配绑定都改掉？**
  见「后续待办」：通配绑定在仓库里是**普遍现象**（351 个文件），
  一次性横扫会变成一个巨大的 diff、越过本车道的边界、且极易与其它车道打架。
  本轮**只修本车道直接相关的 3 个文件**，其余**如实报告、不动手**。

## 涉及文件

- `backend/services/channel/src/test/java/com/starcon/channel/StockForecastAvailabilityPortTest.java`
  — 桩绑定 `new InetSocketAddress(0)` ⇒ `new InetSocketAddress("127.0.0.1", 0)`；
  `base` `http://localhost:<port>` ⇒ `http://127.0.0.1:<port>`；
  `@AfterEach` 加 null 守卫；补根因注释
- `backend/services/channel/src/test/java/com/starcon/channel/PlatformViolationIntegrationTest.java`
  — 补 `@AfterAll static void stopStubs()`：停 identity + warehouse 两台静态桩
- `backend/services/channel/src/test/java/com/starcon/channel/PlatformViolationTenancyIntegrationTest.java`
  — 补 `@AfterAll static void stopStubs()`：停 identity 静态桩
- `docs/sessions/2026-10/2026-10-22-10-4-2-2-stub-port-shadowing.md` — 本记录

**只碰测试代码**：生产代码、迁移、端点、契约、前端页面**一律未动**。
**未改台账**：`docs/product/capability-blueprint/module-progress.md` 里 10.4.2.2 那一行
**保持原样** —— 本次是测试缺陷修复，模块交付状态没变（仍是已交付）。

## 验证

全部在车道 worktree `10.4.2.2-fix`（分支 `fix/10-4-2-2-stub-port-shadowing`）上实跑：

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 目标类单跑 | `./mvnw -pl services/channel -am test -Dtest='StockForecastAvailabilityPortTest'` | `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` / BUILD SUCCESS |
| 整模块连跑 ×3 | `./mvnw -pl services/channel -am test` | 三次均 `Tests run: 1733, Failures: 0, Errors: 0, Skipped: 0` / BUILD SUCCESS |
| L0 编译 | `./mvnw -q compile` | EXIT=0 |
| L0 类型 | `pnpm typecheck` | `3 successful, 3 total` / EXIT=0 |

**关于「修复前能否稳定复现」**：不能稳定复现 —— 它是偶发的（调度者实测：单跑绿、
整模块重跑也绿，只有在集成头整轮 `verify` 的并发/顺序下才红）。
所以本轮**用「重复跑整模块 N 次全绿」作为「不再复现」的证据**，
并用独立 Java 程序（`.tmp/PortShadowingRepro.java`）**确定性地**证明了机制本身：
修复前 wildcard bind ⇒ ALLOWED + 命中别的桩 ⇒ 404；
修复后 loopback bind ⇒ `BindException: Address already in use`（响亮失败）。

## 后续待办

- **全仓库仍有大量「通配地址绑定」的测试桩**（`HttpServer.create(new InetSocketAddress(0), 0)`），
  分布在 channel / trade / warehouse / catalog / crm 五个模块，**本轮未动**（见下节清单）。
  ⇒ 建议另开一条**专门车道**统一收敛成 `new InetSocketAddress("127.0.0.1", 0)`，
  否则同样的假红还会在别的模块复现。
- `PlatformViolation*` 两个集成测试类的 **PostgreSQLContainer `pg` 同样是静态且从不 stop**，
  本轮未动（不在本车道范围内），但同样值得在后续车道一并收敛。
- `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md` 里
  记的 §① / §④（catalog / trade 的同类现象）**适用面比记录的更广**，
  本轮的证据可作为该条 backlog 的补充论据。

### 其它仍用通配地址绑定的测试类（**只报告，未改动**）

按 `HttpServer.create(new InetSocketAddress(0), 0)` 精确检索，`backend/services/*/src/test/java` 下：

| 模块 | 命中文件数 |
| --- | --- |
| `services/channel/` | **105** |
| `services/trade/` | 93 |
| `services/warehouse/` | 63 |
| `services/catalog/` | 57 |
| `services/crm/` | 32 |
| **合计** | **350 个文件 / 459 处** |

（channel 里的 105 个含本轮**未改绑定地址**的 `PlatformViolationIntegrationTest` /
`PlatformViolationTenancyIntegrationTest` —— 它们本轮只补了 `@AfterAll` 停机，
按车道范围不动其绑定地址；它们的桩现在会随类结束而释放端口。）

复现命令（供后续车道直接取用）：

```bash
cd backend
grep -rl "HttpServer.create(new InetSocketAddress(0), 0)" services/*/src/test/java
```


## 关联

- `docs/backlog/2026-10-13-flaky-integration-tests-l2-false-red.md` — 同类 flake 的既有记录（§① / §④）
- `backend/services/channel/src/main/java/com/starcon/channel/ForecastInTransitQueryPort.java`
  — 被测端口（**未改动**）；其 404 ⇒ 503「读不到」的映射是**正确**的纪律
