# 6.2.1.2 补货建议 —— trade 段「上游 404 ⇒ 503」红的车道结论（2026-10-22）

> 车道 `6.2.1.2-fix`，分支 `fix/6-2-1-2-trade-catalog-upstream`，worktree `.worktrees/6.2.1.2-fix`，基线 `12cbd407`。
> 归属：**trade 服务**（模块 6.2.1.2 补货建议）。
> 触发：2026-10-22 批次 L2（`backend` 段 `./mvnw verify`）在 trade 段红，
> `ReplenishmentSuggestionTenancyIntegrationTest` 三条租户断言全拿 **503**
> 「商品主数据服务返回 404」。

## 决策摘要

1. **本车道未能复现该红 —— 如实记录，不硬凑根因。**
   三种语境实测：单独跑该类 **4/4 绿**；与同门类一起 **45 例绿**；与紧邻前一个类一起 **46 例绿**；
   **整模块连跑 2380 例 0 失败**（15:46 min，JDK 21）。⇒ 该红是**瞬时/环境依赖**，
   不是可稳定复现的确定性缺陷。

2. **能钉死的部分钉死了：404 来自「请求没落到 catalog 桩的 context 上」。**
   - 503 那句文案在全仓 trade 里**只属于** `ReplenishmentCatalogPort.unavailable()`；
     而该类里 `sku()` 的 404 分支给出的是 HTTP **404**「商品不存在：SKU …」，
     ⇒ 那次 404 必然出自 **`quotes()`**，即 `GET /catalog/supplier-quotes/effective`。
   - 桩注册的 context 就是 `/catalog/supplier-quotes/effective`，
     `com.sun.net.httpserver` 对**同路径 + 查询串**是**精确命中**的
     ⇒ 请求若真落到本类自己的桩上，**不可能**回 404。
   - ⇒ **那次请求没有落到这个类的桩上**（落到了别处，或路径不是这一条）。

3. **被证伪的假设（逐条）**：
   - ❌ **「Spring 上下文跨测试类共享，捡到了别的类的 `CATALOG_URL`」** ——
     `DynamicPropertiesContextCustomizer.equals` 就是比 `Set<Method>`（javap 反编译 spring-test 6.2.11 实测）；
     且失败那轮跑出了 **HikariPool-113** ⇒ 约 **113 个各自独立的上下文**（每类一个）⇒ 上下文**不跨类共享**。
   - ❌ **「走了机器全局默认值 `http://127.0.0.1:8082`」** —— 失败的那个类**显式**给了
     `CATALOG_URL`（`@DynamicPropertySource`，优先级高于任何配置文件）⇒ 默认值根本没被读到；
     且 `lsof` 实测本机 **8082 无监听** ⇒ 走默认值只会得到「连接被拒 ⇒ 暂时不可达」，
     **不是** 404。
   - ❌ **「双斜杠路径」** —— `UriComponentsBuilder.path()` 拼在无路径的 base 上只产出**单斜杠**
     （路径形状与桩的 context 精确匹配）。

4. **剩下的唯一自洽解释：同 worktree 并发构建共用 `backend/*/target/`。**
   失败窗口（10:51）机器上有别的 cron agent 在**同一个 worktree** 活动。两个 maven 进程写同一份
   `target/classes` ⇒ **正在跑的 JVM 是懒加载 class 的**，可能加载到另一个构建写出来的字节 ⇒
   「请求路径与桩的 context 对不上 ⇒ 上游回 404 ⇒ trade 如实报 503」完全说得通；
   而且**原始失败报告已被 11:12–11:14 的重跑覆盖成绿报告**（这本身就是并发覆盖的指纹）。
   集成 worktree 自己的 HEAD 在窗口内**没有移动**（reflog：10:20:36 → 11:21:01）⇒ 不是该 worktree 的源码分叉，
   但**同 worktree 并发构建仍是一条真实的污染通道**。⇒ 已写 backlog：
   `docs/backlog/2026-10/2026-10-22-concurrent-build-same-worktree-target-collision.md`（**流水线纪律，不在本车道改**）。

5. **本车道改的是那条**结构性**通道：静默依赖机器瞬时状态。**
   trade 主代码里 **33 处**上游地址默认值是**机器全局固定端口**：
   `CATALOG_URL` **19 处** `:8082`、`WAREHOUSE_URL` **11 处** `:8084`、`CHANNEL_URL` **3 处** `:8083`；
   而 `deploy/docker/docker-compose.catalog.yml` 正是把 catalog / channel / warehouse 映射到宿主机
   **这三个端口** ⇒ **任何忘了显式给上游地址的测试类，都会去敲「这台机器上碰巧在跑的那台服务」**，
   测试结果取决于「跑那一刻机器上跑着什么」，而不是取决于被测代码。
   ⇒ 已在 `backend/services/trade/src/test/resources/application.properties` 把**测试类路径**上的兜底值
   换成**必然连不通**的哨兵 `http://127.0.0.1:1`（tcpmux，无人监听）：
   显式 `@DynamicPropertySource` 仍然优先；**忘了给上游地址的测试类一旦真走到上游 ⇒ 立刻 503 响亮失败**，
   不会再静默命中真服务。

6. **回归用例 `TradeUpstreamAddressIsolationTest`（3 例）钉住这条防线**：
   ① 主代码里**确实存在**机器全局上游默认值（否则用例会空转 ⇒ 自证扫描有效）；
   ② 测试兜底值**覆盖每一个**这样的键，且**不等于**那个机器全局地址；
   ③ 兜底值**当场验证真的连不通**（真去连一次，必须被拒），且端口**不在**机器全局端口集合内。

## 关键 trade-off

- **没有改生产代码的默认值。** 主代码那 33 处默认值只是本地开发的兜底，
  生产由 `deploy/docker` 显式注入服务名形式（如 `http://identity:8081`）；
  为一条**只在测试里发作**的耦合去动 19 处生产默认值，改动面大、收益小 ⇒ 改在测试侧。
- **没有试图「复现」那个 404。** 复现成本 15 分钟/次且不稳定；把结构性通道消掉 + 留下可钉住的回归，
  比再赌一次复现更划算。
- **哨兵选 `127.0.0.1:1` 而不是 `.invalid` 域名**：端口 1 立即 `ECONNREFUSED`（毫秒级、无 DNS 依赖），
  而 `.invalid` 要走一次 DNS 解析（快慢取决于解析器）⇒ 选**行为确定**的那个。

## 诚实边界

- 本车道**未能复现**该红 ⇒ 「并发构建」是**最强自洽解释**，不是**已证实**的根因。
- 本车道的改动**消除了「静默依赖机器瞬时状态」这条通道**，
  但**不解释、也不单独防止**那一次 404（失败的那个类**显式**给了上游地址）⇒
  那一次的直接堵点仍需靠**流水线纪律**（不并发同 worktree 构建）。

## 文件清单

| 文件 | 改动 |
| --- | --- |
| `backend/services/trade/src/test/resources/application.properties` | **新增**：测试类路径上的上游地址兜底 = 必然连不通的哨兵 |
| `backend/services/trade/src/test/java/com/starcon/trade/TradeUpstreamAddressIsolationTest.java` | **新增**：回归用例（3 例） |
| `docs/backlog/2026-10/2026-10-22-concurrent-build-same-worktree-target-collision.md` | **新增**：流水线纪律建议（同 worktree 不得并发构建） |
| `docs/sessions/2026-10/2026-10-22-6-2-1-2-trade-catalog-upstream-404.md` | **新增**：本文件 |
| `docs/product/capability-blueprint/module-progress.md` | 改：6.2.1.2 证据列补本车道结论 |

## 验证

- 定向：`./mvnw -pl services/trade -am test -Dtest='TradeUpstreamAddressIsolationTest,ReplenishmentSuggestionTenancyIntegrationTest'
  -Dsurefire.failIfNoSpecifiedTests=false` ⇒ **7 例 0 失败**（3 + 4）。
  （顺带证明主 `application.yml` **仍被加载** —— 桩上下文起来说明 `spring.flyway.url=${DB_URL}` 解析得到。）
- 全量：`./mvnw -pl services/trade -am test` ⇒ **2383 例 0 失败 0 错误**（15:19 min，BUILD SUCCESS；
  原 2380 例**不减**，本车道 +3）。
- 格式：本车道新增的 Java 文件**本身**符合 spotless（`./mvnw -pl services/trade spotless:check` 的违规清单里
  **没有** `TradeUpstreamAddressIsolationTest`）。⚠️ 但整模块 `spotless:check` 仍红，且**与本车道无关**：
  它报「Violations also present in **371** other files」（如 `ReturnReceiptAllocationIntegrationTest`）——
  那是**既有**的 javadoc 换行风格与 spotless 的 google-java-format 不一致（CI 的 `verify spotless:check` 本来就红），
  本车道**不**去 mass-reformat（已有 backlog `2026-10-21-spotless-apply-mass-reformat-hazard.md`）。
- 台账：`node tools/module-progress/sync.mjs --check` ⇒ **✓ 台账与蓝图一致**。

## 后续待办

- [ ] 流水线：L2 与任何其它构建**不得在同一 worktree 并发**（backlog 已记，本车道不碰 `lane.mjs` / `PIPELINE.md`）。
- [ ] 其余服务（catalog / channel / warehouse / crm / identity）同样存在「上游地址默认值 = 机器全局端口」的写法，
      是否逐服务补同样的测试侧兜底，由调度者排期。
- [ ] 若日后再次撞到「上游回 404」，**先查那次失败报告是否还在磁盘上**：
      不在 ⇒ 高度怀疑被并发跑覆盖，先按 backlog 那条查同 worktree 的构建进程。
