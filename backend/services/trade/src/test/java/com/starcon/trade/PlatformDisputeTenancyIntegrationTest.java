package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.PlatformDisputeModels.*;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.4.1.1 平台纠纷的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且<b>实跑在本模块自己的四张表上</b>
 * （platform_dispute 全族），<b>不是</b>占位探针表 —— 探针表能证明 RLS 开着，
 * 证明不了<b>本模块的查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：纠纷单里同时有<b>平台争议金额、我方立场、责任判定
 * 与举证清单</b> —— 一个租户能看到另一个租户「被平台索赔 88.5 美元、我们承认责任」，
 * 拿到的是**谈判底牌**；而举证清单里常常写着「我们其实没交这份材料」。
 * 端点又大多是<b>按 id 直达</b>的 URL，猜到一个 UUID 就能读，不需要翻列表。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离 —— 复用上一轮的记录，
 * 第二轮断言通过只能说明「同一条记录没变」。
 *
 * <p>⚠️ 身份桩用 {@code AccessService} 的 <b>spy</b>（只替换对 identity 的一次 HTTP 调用），
 * {@code run} 仍是真实实现 ⇒ RLS 租户绑定跑的是生产代码路径。
 *
 * <p>🔴 <b>时间基准取数据库自己的时钟</b>（{@link #dbNow()}）：本模块的「超期」判定
 * 全在 <b>DB 钟域</b>里（V265 守卫① 判 {@code evidence_deadline <= now()}、
 * 状态机守卫判 {@code evidence_deadline > now()} 就拒绝「提前判负」、
 * 服务层读时收口 {@code expireOverdueDisputes()} 也用 {@code now()}），
 * 夹具给的时刻就必须也是那个钟域的时刻。夹具若写 JVM 墙钟，
 * 「到没到期」就成了宿主机钟与 PG 容器钟在比大小 —— 结论取决于两台钟的差，
 * 也就是取决于运气（2026-10-27 的 L2 批次闸：
 * expected "RESOLVED_LOST" but was "OPEN"）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformDisputeTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms =
      List.of("trade.aftersales.read", "trade.aftersales.write", "trade.aftersales.finance");

  static AccessService.Access actor(UUID uid, UUID tid) {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms));
    return new AccessService.Access(uid, tid, "Test", UUID.randomUUID(), null, perms, grants);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put("Bearer tenant-a", actor(userA, a));
    actors.put("Bearer tenant-b", actor(userB, b));
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:8081");
    r.add("INTERNAL_KEY", () -> "test-key");
    // ⚠️ **连接池固定为 1**：池大于 1 时每次可能拿到不同连接，
    // 「归还后不串租户」可能只是碰巧换了条干净连接。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @MockitoSpyBean AccessService access;
  @Autowired PlatformDisputeService disputes;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    pg.stop();
  }

  @BeforeEach
  void stubIdentity() {
    org.mockito.Mockito
        .doAnswer(
            inv -> {
              AccessService.Access found = actors.get(inv.getArgument(0, String.class));
              if (found == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
              }
              String permission = inv.getArgument(1, String.class);
              boolean allowed =
                  found.grants().stream()
                      .anyMatch(
                          g ->
                              g.permissions().contains(permission)
                                  && g.permissions()
                                      .contains(
                                          permission.substring(0, permission.lastIndexOf('.')) + ".read")
                                  && ("ALL".equals(g.scopeMode()) || "SELF".equals(g.scopeMode())));
              if (!allowed) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色没有此操作权限");
              }
              return found;
            })
        .when(access)
        .require(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate platform_dispute_event, platform_dispute_settlement,"
              + " platform_dispute_evidence_item, platform_dispute");
    }
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.aftersales.write", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-b"), "trade.aftersales.write", work);
  }

  <T> T as(UUID uid, UUID tid, java.util.function.Supplier<T> work) {
    return access.run(actor(uid, tid), "trade.aftersales.write", work);
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /**
   * 在指定租户里建一件纠纷（金额与平台纠纷号都带租户标记，便于断言不串）。
   *
   * <p>🔴 举证时限取 **DB 钟**的「现在加 14 天」（{@link #dbNow()}），不取 JVM 墙钟：
   * 判超期的地方全在 DB 钟域里，夹具给的时刻也必须是那个钟域的时刻。
   * 14 天的余量保证它稳稳落在「DB 钟的未来」，建单即超期那条唯一例外（守卫⑨）不会被碰到。
   */
  UUID newDispute(UUID tid, UUID uid, String tag, String amount) {
    return as(
            uid,
            tid,
            () ->
                disputes.createDispute(
                        new PlatformDisputeCreate(
                            "AMAZON", "Amazon", "AZ-" + tag, "A_TO_Z", "买家称未收到货 " + tag,
                            "ORDER-" + tag, "USD", new BigDecimal(amount),
                            dbNow().plusDays(14),
                            "REJECT", "LOGISTICS", "立场-" + tag, ""),
                        uid)
                    .head()
                    .id());
  }

  /** 加一项举证材料。 */
  void addEvidence(UUID tid, UUID uid, UUID disputeId, String label) {
    as(
        uid,
        tid,
        () ->
            disputes.addEvidenceItem(
                disputeId,
                new PlatformEvidenceItemCreate("ORDER_FULFILLMENT_PROOF", label, true, ""),
                uid));
  }

  private static PlatformDisputeListQuery query() {
    return new PlatformDisputeListQuery(
        0, 20, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  /**
   * 读一次**数据库自己的时钟**（Postgres 容器时钟），并归一到 UTC。
   *
   * <p>写法照抄 {@code com.starcon.warehouse.StockOwnershipIntegrationTest#dbNow()}。
   *
   * <p>本模块的「超期」判定<b>全部发生在 DB 钟域</b>里：V265 守卫①
   * （{@code guard_platform_dispute_deadline}）判 {@code NEW.evidence_deadline <= now()}，
   * V265 状态机守卫判 {@code NEW.evidence_deadline > now()} 就拒绝「提前判负」，
   * 服务层的读时收口 {@code expireOverdueDisputes()} 也用 {@code now()}。
   * 夹具要跟它比，就必须也用它的钟。
   *
   * <p>夹具若写 JVM 墙钟（{@code OffsetDateTime.now(UTC).minusMinutes(1)}），
   * 这一行的「是否已超期」就取决于宿主机钟与 PG 容器钟的差 ——
   * 本机实测 PG 容器钟领先宿主 JVM 约 50ms（重负载下还会互相超越），
   * 也就是说：**夹具的判定结果取决于两台钟的差，而不是取决于夹具写的是什么**。
   * 那是运气，不是断言（2026-10-27 的 L2 批次闸：expected "RESOLVED_LOST" but was "OPEN"）。
   *
   * <p>归一到 UTC 是为了能原样塞进 URL 查询参数：{@code +08:00} 里的 {@code +} 在查询串里会被
   * 当成空格。截断到毫秒沿用原 {@code now()} 的做法 —— 截断只会让这个时刻更早，
   * 对「必须落在过去」这类比较永远安全。
   */
  OffsetDateTime dbNow() {
    return sql.fetchOne("select now()")
        .get(0, OffsetDateTime.class)
        .withOffsetSameInstant(ZoneOffset.UTC)
        .truncatedTo(ChronoUnit.MILLIS);
  }

  // ==========================================================================
  // 断言一：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见() throws Exception {
    // 每轮都新建实体：复用上一轮的记录，第二轮断言通过只能说明「同一条记录没变」。
    UUID ida = newDispute(a, userA, "ISO-A", "88.5000");
    UUID idb = newDispute(b, userB, "ISO-B", "12.2500");
    addEvidence(a, userA, ida, "A 租户的履约证明");
    addEvidence(b, userB, idb, "B 租户的履约证明");

    // ---- 列表互不可见（含汇总口径）----
    assertThat(asA(() -> disputes.listDisputes(query())).total()).isEqualTo(1);
    assertThat(asA(() -> disputes.listDisputes(query())).rows())
        .extracting(PlatformDisputeRow::id).containsExactly(ida);
    assertThat(asB(() -> disputes.listDisputes(query())).rows())
        .extracting(PlatformDisputeRow::id).containsExactly(idb);

    // 金额与立场不串。
    assertThat(asA(() -> disputes.disputeDetail(ida)).head().disputeAmount())
        .isEqualByComparingTo("88.5000");
    assertThat(asB(() -> disputes.disputeDetail(idb)).head().disputeAmount())
        .isEqualByComparingTo("12.2500");
    assertThat(asA(() -> disputes.disputeDetail(ida)).head().positionNote()).isEqualTo("立场-ISO-A");
    assertThat(asB(() -> disputes.disputeDetail(idb)).head().positionNote()).isEqualTo("立场-ISO-B");

    // ---- 举证清单互不可见 ----
    assertThat(asA(() -> disputes.disputeDetail(ida)).evidenceItems())
        .extracting(PlatformDisputeEvidenceRow::evidenceLabel)
        .containsExactly("A 租户的履约证明");
    assertThat(asB(() -> disputes.disputeDetail(idb)).evidenceItems())
        .extracting(PlatformDisputeEvidenceRow::evidenceLabel)
        .containsExactly("B 租户的履约证明");

    // ⚠️ **按 id 直达也不行**：A 拿着 B 的纠纷 id 读，
    //   得到的是与「不存在」完全相同的错误（不告诉调用方「这个 ID 存在但不属于你」）。
    assertThatThrownBy(() -> asA(() -> disputes.disputeDetail(idb)))
        .hasStackTraceContaining("不存在或不属于当前租户");
    // 写入口也拦住：不能替别人加举证材料、不能替别人提交举证。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        disputes.addEvidenceItem(
                            idb,
                            new PlatformEvidenceItemCreate("INVOICE", "越权加的", true, ""),
                            userA)))
        .hasStackTraceContaining("不存在或不属于当前租户");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        disputes.submitEvidence(
                            idb, new PlatformDisputeSubmitEvidence("越权提交", 0L), userA)))
        .hasStackTraceContaining("不存在或不属于当前租户");

    // HTTP 层同样如此（按 id 直达的 URL 是最容易被猜到的那种）。
    http.perform(get("/trade/platform-disputes/{id}", idb)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().is4xxClientError());
  }

  // ==========================================================================
  // 断言二：伪造 tenant header 无效
  // ==========================================================================

  @Test
  void 伪造租户头无效() throws Exception {
    UUID ida = newDispute(a, userA, "FORGE-A", "88.5000");
    UUID idb = newDispute(b, userB, "FORGE-B", "12.2500");

    // 带着 **B 的令牌** + **伪造的 A 的 X-Tenant-Id**：读到的必须是 B 的数据，
    // 不是 A 的 —— 头改不动 identity 解析出来的可信租户。
    String list =
        http.perform(get("/trade/platform-disputes")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(list).contains(idb.toString()).doesNotContain(ida.toString());

    // 反过来：带着 A 的令牌 + 伪造的 B 的头，读到的仍然是 A 的数据。
    String list2 =
        http.perform(get("/trade/platform-disputes")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(list2).contains(ida.toString()).doesNotContain(idb.toString());

    // 详情同理。
    String detail =
        http.perform(get("/trade/platform-disputes/{id}", idb)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(detail).contains(idb.toString()).doesNotContain(ida.toString());

    // 直接在 RLS 上验证：绑定 A 的租户之后，B 的行在库里**读不出来**，
    // 绕过服务层也一样（FORCE RLS 对表 owner 之外的角色同样生效，这里跑的是 trade_app）。
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) from platform_dispute where tenant_id=?::uuid and id=?::uuid", b, idb))
        .get(0, Long.class)).as("RLS 之下看不到别的租户的行").isZero();

    // ⚠️ **即使不带 tenant_id 谓词**也读不到（证明是真隔离，不是应用层过滤）。
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) from platform_dispute where id=?::uuid", idb))
        .get(0, Long.class)).as("不带租户谓词也读不到别人的行").isZero();
  }

  // ==========================================================================
  // 断言三：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 连接池归还后不串租户() throws Exception {
    UUID ida = newDispute(a, userA, "POOL-A", "88.5000");
    UUID idb = newDispute(b, userB, "POOL-B", "12.2500");

    // 交替借还：同一件纠纷反复以两个租户的身份读，读到的永远是**当前绑定**的租户。
    for (int i = 0; i < 3; i++) {
      assertThat(asA(() -> disputes.disputeDetail(ida)).head().disputeAmount())
          .isEqualByComparingTo("88.5000");
      assertThat(asB(() -> disputes.disputeDetail(idb)).head().disputeAmount())
          .isEqualByComparingTo("12.2500");
      // 归还之后再读另一个租户的 id：仍然读不到。
      assertThatThrownBy(() -> asA(() -> disputes.disputeDetail(idb)))
          .hasStackTraceContaining("不存在或不属于当前租户");
      assertThatThrownBy(() -> asB(() -> disputes.disputeDetail(ida)))
          .hasStackTraceContaining("不存在或不属于当前租户");
    }

    // 举证清单也在交替中补齐，两个租户的清单互不污染。
    addEvidence(a, userA, ida, "A 的举证");
    addEvidence(b, userB, idb, "B 的举证");
    assertThat(asA(() -> disputes.disputeDetail(ida)).evidenceItems())
        .extracting(PlatformDisputeEvidenceRow::evidenceLabel)
        .containsExactly("A 的举证");
    assertThat(asB(() -> disputes.disputeDetail(idb)).evidenceItems())
        .extracting(PlatformDisputeEvidenceRow::evidenceLabel)
        .containsExactly("B 的举证");

    // ⚠️ **超期自动判败也不串**：A 的纠纷超期判负，B 的不受影响。
    //
    // 🔴 **截止时刻必须取 DB 钟域的「现在减 1 分钟」**（{@link #dbNow()}），
    // 不能取 JVM 墙钟：判超期的地方全在 DB 钟域里
    // （V265 守卫① 判 {@code evidence_deadline <= now()}，
    //  状态机守卫判 {@code evidence_deadline > now()} 就拒绝「提前判负」，
    //  服务层读时收口 {@code expireOverdueDisputes()} 也用 {@code now()}）。
    // 拿宿主钟写这个时刻，这一行「到没到期」就成了两台钟在比大小 ——
    // 2026-10-27 的 L2 批次闸就是这么红的：expected "RESOLVED_LOST" but was "OPEN"。
    //
    // ⚠️ 断言**不许改弱**：多租户三断言之一，判成 OPEN 就不算隔离证明。
    asA(() -> sql.execute(
        "update platform_dispute set evidence_deadline = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        dbNow().minusMinutes(1), a, ida));

    // 写回值必须**断言**：截止时刻确实落在 DB 钟的「过去」，且留出了 30s 以上的余量。
    // 判超期的地方在 DB 钟域，所以「过去」也只能在 DB 钟域里量 ——
    // 拿宿主钟去量，等于又换了一个钟域。
    OffsetDateTime writtenDeadline =
        asA(() -> sql.fetchOne(
                "select evidence_deadline from platform_dispute"
                    + " where tenant_id = ?::uuid and id = ?::uuid",
                a, ida)
            .get("evidence_deadline", OffsetDateTime.class));
    assertThat(writtenDeadline)
        .as("🔴 夹具写的举证截止时刻必须落在 DB 钟的「现在减 1 分钟」之内："
            + "两个端点都在 DB 钟域，所以必然不晚于它 —— 这条是**恒真**的。"
            + "若写进去的是宿主墙钟，成立与否就取决于两台钟的差"
            + "（宿主钟领先容器钟时，deadline 会落进容器钟的未来，"
            + "  本该超期的纠纷被判成 OPEN —— 2026-10-27 L2 批次闸就是这么红的）")
        .isBeforeOrEqualTo(dbNow().minusMinutes(1));
    assertThat(writtenDeadline)
        .as("🔴 而且要留足余量：至少 30s 的过去，不是擦着边过去 —— "
            + "余量就是留给两台钟的差的")
        .isBefore(dbNow().minusSeconds(30));

    assertThat(asA(() -> disputes.disputeDetail(ida)).head().disputeStatus())
        .isEqualTo("RESOLVED_LOST");
    assertThat(asB(() -> disputes.disputeDetail(idb)).head().disputeStatus()).isEqualTo("OPEN");
    assertThat(asA(() -> disputes.listDisputes(query())).rows())
        .extracting(PlatformDisputeRow::id).containsExactly(ida);
    assertThat(asB(() -> disputes.listDisputes(query())).rows())
        .extracting(PlatformDisputeRow::id).containsExactly(idb);
  }

  /**
   * 🔴 超期判定必须锚在 **DB 钟**上：截止时刻落在 DB 钟的**未来**时，纠纷**不得**被判负。
   *
   * <p>这是本模块超期判定的**正面**证据：判超期的地方全在 DB 钟域里
   * （V265 守卫① 判 {@code NEW.evidence_deadline <= now()}；
   * 状态机守卫判 {@code NEW.evidence_deadline > now()} 就拒绝「提前判负」；
   * 服务层读时收口 {@code expireOverdueDisputes()} 也用 {@code now()}），
   * 所以「DB 钟的未来」必须是「未超期」。
   *
   * <p>⚠️ 这条断言**不许改弱**：把它改成「判成 RESOLVED_LOST 也行」，
   * 就等于承认超期判定可以随便锚在哪个钟上 —— 那正是 2026-10-27 L2 批次闸
   * （expected "RESOLVED_LOST" but was "OPEN"）暴露的东西。
   */
  @Test
  void 超期判定走DB钟域_未来截止时刻不判负() {
    UUID id = newDispute(a, userA, "CLOCK-A", "88.5000");

    // 截止时刻 = **DB 钟**的「现在加 1 分钟」：在 DB 钟域里明确是**未来**。
    // （从 14 天收紧到 1 分钟是收紧，V265 守卫③ 允许；只放宽才拒。）
    asA(() -> sql.execute(
        "update platform_dispute set evidence_deadline = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        dbNow().plusMinutes(1), a, id));

    // 写回值必须断言：它确实落在 DB 钟的**未来**（不是宿主钟的未来）。
    OffsetDateTime writtenDeadline =
        asA(() -> sql.fetchOne(
                "select evidence_deadline from platform_dispute"
                    + " where tenant_id = ?::uuid and id = ?::uuid",
                a, id)
            .get("evidence_deadline", OffsetDateTime.class));
    assertThat(writtenDeadline)
        .as("🔴 夹具写的截止时刻必须落在 DB 钟的未来：判超期的地方全在 DB 钟域里，"
            + "「未来」只能在 DB 钟域里量")
        .isAfter(dbNow());

    assertThat(asA(() -> disputes.disputeDetail(id)).head().disputeStatus())
        .as("🔴 截止时刻还在 DB 钟的未来，就不该判负 —— "
            + "判负的判据是 DB 钟域的 now()，不是宿主机墙钟")
        .isEqualTo("OPEN");
  }

  // ==========================================================================
  // 附：本模块的隔离面（证明断言跑在真表上）
  // ==========================================================================

  @Test
  void 伪造租户头不能写也不能替别人提交举证() throws Exception {
    UUID ida = newDispute(a, userA, "WRITE-A", "88.5000");
    UUID idb = newDispute(b, userB, "WRITE-B", "12.2500");
    addEvidence(a, userA, ida, "A 的举证");
    addEvidence(b, userB, idb, "B 的举证");
    // 两边都进到「举证准备中」—— 只有都准备好了，越权提交才**可能**被误执行。
    asA(() -> disputes.startEvidence(ida, versionOf(ida, a, userA), userA));
    asB(() -> disputes.startEvidence(idb, versionOf(idb, b, userB), userB));

    // B 的令牌 + 伪造的 A 的头去**写** A 的纠纷：绝不会落到 A 的行上。
    http.perform(MockMvcRequestBuilders.post("/trade/platform-disputes/{id}/submit-evidence", ida)
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"submitNote\":\"越权提交\",\"expectedVersion\":1}"))
        .andExpect(status().is4xxClientError());

    // A 的纠纷仍然是「举证准备中」—— 没有被越权提交。
    assertThat(asA(() -> disputes.disputeDetail(ida)).head().disputeStatus())
        .isEqualTo("EVIDENCE_PREPARING");
    // B 自己的那张也没被提交（越权请求打到了 B 的租户上，但 B 的举证项还没具备 → 拒绝）。
    assertThat(asB(() -> disputes.disputeDetail(idb)).head().disputeStatus())
        .isEqualTo("EVIDENCE_PREPARING");

    // 反过来，A 也不能替 B 提交。
    http.perform(MockMvcRequestBuilders.post("/trade/platform-disputes/{id}/submit-evidence", idb)
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"submitNote\":\"越权提交\",\"expectedVersion\":1}"))
        .andExpect(status().is4xxClientError());
    assertThat(asB(() -> disputes.disputeDetail(idb)).head().disputeStatus())
        .isEqualTo("EVIDENCE_PREPARING");
  }

  long versionOf(UUID id, UUID tid, UUID uid) {
    return as(uid, tid, () -> disputes.disputeDetail(id).head().version());
  }
}
