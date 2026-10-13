# 两个既有集成测试在全量并发下 flake（L2 误报来源）

**发现时间**：2026-10-13 20:00 tick，L2 批次闸
**严重度**：中（不阻塞交付，但会让每批 L2 假红，浪费一次全量验证 + 一次二分）
**状态**：未修 · 本轮已确认两者都与本批代码无关

## 现象

本批 `./mvnw verify` 连续两次 BUILD FAILURE，但**两次红的用例完全不同，且都与本批改动无关**：

| 轮次 | 失败 | 报错 |
| --- | --- | --- |
| 第 1 次 | `catalog.DuplicateDetectionIntegrationTest` 2 例 | `expected:<200/404> but was:<503>` |
| 第 2 次 | `warehouse.StockOwnershipIntegrationTest` 1 例 | UUID 不匹配 |

**本批改动清单里没有任何 catalog / identity / tenancy / StockOwnership 文件**
（`git diff --name-only b41a14f d6c882c` 全是 warehouse / apps/web / contracts）。

## 单独重跑，两个都绿

```
DuplicateDetectionIntegrationTest: Tests run: 19, Failures: 0  ✅
StockOwnershipIntegrationTest:     Tests run: 40, Failures: 0  ✅
```

## 根因

### ① 503 = 身份桩的临时端口被抢占

`DuplicateDetectionIntegrationTest` 用 JDK 内置 `com.sun.net.httpserver.HttpServer`
起一个假身份服务，并通过 `identity.getAddress().getPort()` 把它绑进
`IDENTITY_URL`（`@DynamicPropertySource`）。这个端口是**操作系统分配的临时端口**。

全量 `verify` 时 surefire 多 fork 并发、Testcontainers 也在密集起停 PG 容器，
临时端口被抢占/复用的概率显著上升 ⇒ stub 连不上 ⇒
`catalog/AccessService` 捕获 `RestClientException` 抛 503
（`services/catalog/.../AccessService.java:103`）⇒ 期望 404/200 的断言拿到 503。

### ② UUID 不匹配 = 测试固件未按租户/用例隔离

`StockOwnershipIntegrationTest.货权档优先级是sku大于法人大于仓库大于兜底`
用**固定业务编码**建档（`OWN-LE`、`CONS-WHU`、`CONS-SKU`…），断言解析出的 ownerId 等于刚建的那个。
并发/复用时同编码的档位被别的用例先写入或覆盖 ⇒ `service.resolve()` 按优先级命中了**另一条**记录
⇒ UUID 对不上。

### ③ warehouse 那次「失败」根本不是断言失败

`2026-10-13T20-08-06_294-jvmRun1.dumpstream`：

```
Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).
```

即测试**全部跑完并通过**后，fork JVM 30 秒不退（残留非守护线程 / 容器未收干净），
surefire 判定 hang 并强杀 ⇒ reactor 报 FAILURE，但 `surefire-reports/*.txt` 里
**没有任何非零 Failures**。这是「退出超时」被当成「测试失败」。

## 对流水线的影响

组批模式下 L2 是**每批唯一一次**全量验证。这两条 flake 会：

1. 让 L2 假红 → 触发「二分定位 + 派修复任务 + 重跑 L2」整条处置流程（一次要多烧 1–2 套全量）
2. 而二分**必然查不出东西**（本批根本没碰这些文件）⇒ 每批都白烧一次全量
3. 长期会侵蚀「L2 红了就一定是跨车道互相破坏」这个判断的可信度

## 建议修法（派 worker 做，不要在批次中途改）

1. **503 桩**：端口不用临时端口，改为显式找一个空闲端口后**固定下来**，
   或改用 WireMock/MockWebServer 这类能自管端口生命周期的桩；
   至少要保证 stub 的 `HttpServer` 在 `@AfterAll` 里显式 `stop(0)`，
   避免端口泄漏到后续 fork。
2. **固件隔离**：`StockOwnershipIntegrationTest` 的建档编码改为**每次运行唯一**
   （UUID 后缀），断言只比较「本次建的那条」，不要依赖固定编码的全局唯一性。
3. **surefire hang**：给集成测试 JVM 加 `-DforkedProcessExitTimeoutInSeconds`
   与显式的 `@AfterAll` 容器清理；若确认是 Testcontainers ryuk 未回收，
   检查测试是否漏了 `container.stop()`。

## 判据（下次遇到同类「L2 红」先做这三步，别急着二分）

1. `git diff --name-only <上批HEAD> <本批HEAD> | grep -E "<失败的模块>"` — **为空就不是本批的锅**
2. 单独重跑失败类：`-Dtest=XxxTest -Dsurefire.failIfNoSpecifiedTests=false` — 绿 ⇒ flake
3. 查 `.dumpstream` 有没有 `kill self fork JVM` — 有 ⇒ 那是退出超时，不是断言失败
