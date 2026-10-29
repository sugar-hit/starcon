# 跨时钟域的第 3 例：channel `credential_ref.authorized_at` 绑 JVM 墙钟 ⇒ 撞 `credential_window` CHECK ⇒ 被谎报成 409「店铺编码重复」（2026-10-28 取证）

**优先级：P0 —— 这不是测试脆弱性，这是生产正确性缺陷。**
同族前两例：trade 的 `fulfillment_request_step`（已修，`docs/backlog/2026-10/2026-10-2026-10-21-trade-fulfillment-clock-domain-db-constraint.md`）、
catalog 的 timestamptz 往返断言（已修，`docs/backlog/2026-10/2026-10-2026-10-27-catalog-timestamp-roundtrip-assertion-cross-clock-domain.md`）。
**状态：本轮已派修复车道（`fix-channel-l2-red`，channel 服务，无迁移）。**

## 现象

2026-10-27 23:04 起跑的 L2 批次闸（round 115），channel 服务红了 11 例：

```
[ERROR] Tests run: 23, Failures: 10 -- in com.starcon.channel.OAuthAuthorizationIntegrationTest
[ERROR] ...shopAlreadyClaimedByAnotherTenantIsRejectedAtConfirmTime -- <<< FAILURE!
java.lang.AssertionError: Status expected:<200> but was:<409>
```

MockMvc dump 里的真正原因（**不是**业务层的 409）：

```
Resolved Exception:
             Type = org.springframework.dao.DataIntegrityViolationException
           Status = 409
    Body = {"type":"about:blank","title":"Conflict","status":409,
            "detail":"店铺编码重复、平台账号已被绑定，或该店铺仍被单据引用。",
            "instance":"/channel/oauth/callback"}
```

**调度者取证**：同一 HEAD、机器空闲时单跑 channel 全模块 —— `Tests run: 2384, Failures: 0, Errors: 14`，
`OAuthAuthorizationIntegrationTest` **23/23 全绿**；单独跑该类也 23/23 全绿。
⇒ **间歇失败，且只在重负载下出现。**

## 根因（逐条取证）

1. **约束原文**（`backend/services/channel/src/main/resources/db/migration/V1__channel_registry_connections_and_shops.sql:226`）：

   ```sql
   -- 授权时间不能是未来（写入即已发生的动作）。到期时间允许早于当前时间：
   -- 2.2.2.1 的健康诊断必须能读出"已过期的凭据"来提示重新授权，因此这里不约束 expires_at 相对 now()。
   ALTER TABLE credential_ref ADD CONSTRAINT credential_window
     CHECK(authorized_at <= now() + interval '5 minutes');
   ```

   ⇒ **CHECK 右端是 PG 的 `now()`（DB 钟域），容差只有 5 分钟。**

2. **写入端绑的是 JVM 墙钟**（`backend/services/channel/src/main/java/com/starcon/channel/oauth/OAuthAuthorizationService.java`）：

   ```java
   java.sql.Timestamp authorizedAt = Timestamps.at(Instant.now());   // ← JVM 墙钟
   ...
   "insert into credential_ref(...,authorized_at,expires_at) values (?,?,?,?,?,?,?)"
   ```

   `upsertCredential(...)` 的**更新分支**同样传 JVM 墙钟。

3. ⇒ **跨钟域比较**：JVM 墙钟比 DB 时钟快 >5 分钟 ⇒ CHECK 违反 ⇒ `DataIntegrityViolationException`。

4. **为什么它表现为 409 而不是 500**：全局异常处理器把 `DataIntegrityViolationException` 映射成
   `409 "店铺编码重复、平台账号已被绑定，或该店铺仍被单据引用。"` ——
   **一条「时间戳比 DB 时钟快了 >5 分钟」被谎报成「店铺编码重复」。**
   同族记录：`docs/backlog/2026-10-2026-10-21-global-error-handler-masks-code-defects-as-409-400.md`。

5. **为什么在 L2 才红**：L2 全量 verify 时，Testcontainers 容器堆到 60~110 个
   （见 `docs/backlog/2026-10-22-testcontainers-pg-container-leak.md`）⇒ swap 用掉 4.5 GiB / 5 GiB
   ⇒ JVM 被卡住 15~17 分钟 ⇒ **捕获 `Instant.now()` 与 INSERT 落库之间隔了一次真实停顿**。
   L2 日志铁证：`HikariPool-84 - Thread starvation or clock leap detected (housekeeper delta=15m27s919ms)`。

## 影响面

- **生产正确性**：任何在「JVM 时钟与 DB 时钟相差 >5 分钟」的主机上发生的 OAuth 回调，
  都会失败并返回一条**误导性的 409**（用户会以为是自己重复纳管了店铺，实际是时钟问题）。
  停机 15 分钟的节点、NTP 跳变、虚拟机时钟漂移都会命中。
- **诊断成本**：错误消息把「时钟问题」伪装成「业务冲突」，排查方向一开始就跑偏
  （本轮调度者因此先花了不少时间找 `oauth_shop_owner` / 全局唯一索引）。
- **同族隐患面**：凡是「写进带 `CHECK(... now() ...)` 约束的列」的地方都可能是同一族。
  channel 内至少还有 `Timestamps.at(Instant.now())` 的其它调用点，需要逐个判断。

## 待办内容

- [ ] **`authorized_at` 改用 DB 钟域**：INSERT 用 `now()`（列默认值或 SQL 内 `now()`），
      不把 `Instant.now()` 转 Timestamp 再绑；更新分支同理。**改写入端，不改约束，不放宽 5 分钟容差。**
- [ ] 补一条回归用例：断言「即使 JVM 墙钟与 DB 时钟相差很大，写入的 `authorized_at` 仍满足
      `credential_window`」，且写回的值与 DB 钟域一致。**该用例必须修复前红、修复后绿。**
- [ ] `grep -rn 'Timestamps.at(Instant.now())' backend/services/channel/src/main/java/` 全量过一遍，
      列出清单与判断（哪些是同族隐患、哪些无害）。
- [ ] **跨服务扫描**：把「写进带 `now()` CHECK 的列却绑 JVM 墙钟」这一族在
      catalog / identity / warehouse / trade / finance / crm 全部扫一遍并登记。
      已有三例（trade / catalog / channel）说明这不是孤例。
- [ ] 全局异常处理器：把 `DataIntegrityViolationException` 从「409 业务冲突」里摘出来
      （约束名进响应或日志），否则同族问题还会被继续伪装。
      归 `docs/backlog/2026-10-2026-10-21-global-error-handler-masks-code-defects-as-409-400.md` 一并处理。

## 完成标准

- [ ] channel 全模块（2384 例）在**重负载下**也全绿（不只是在空闲机器上绿）。
- [ ] 回归用例证明「写回值与 DB 钟域一致」，且修复前红、修复后绿。
- [ ] 跨服务扫描清单已登记，每条都有「是/否是同族」的判断。
