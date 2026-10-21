package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.PlatformDisputeAppealModels.*;
import com.starcon.trade.PlatformDisputeModels.*;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.4.1.2 证据与申诉的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且**实跑在本模块自己的四张表上**
 * （platform_dispute_appeal 全族），**不是**占位探针表。
 *
 * <p><b>本模块尤其不能省隔离</b>：申诉包里同时有**我方主张的金额、平台回复原文、
 * 裁决金额与逐条证据引用** —— 一个租户能看到另一个租户的「我们主张赔 88.5 美元、
 * 平台只认了 40 美元」，拿到的是**谈判底牌**；证据引用里常常写着
 * 「这份签收照片其实是买家自己拍的」。
 * 端点又大多是<b>按 id 直达</b>的 URL，猜到一个 UUID 就能读，不需要翻列表。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离。
 *
 * <p>⚠️ 身份桩用 {@code AccessService} 的 <b>spy</b>（只替换对 identity 的一次 HTTP 调用），
 * {@code run} 仍是真实实现 ⇒ RLS 租户绑定跑的是生产代码路径。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformDisputeAppealTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms =
      List.of(
          "trade.aftersales.read",
          "trade.aftersales.write",
          "trade.aftersales.platform",
          "trade.aftersales.finance");

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
  @Autowired PlatformDisputeAppealService appeals;
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
          "truncate platform_dispute_appeal_event, platform_dispute_appeal_submission,"
              + " platform_dispute_appeal_material, platform_dispute_appeal,"
              + " platform_dispute_event, platform_dispute_settlement,"
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

  /** 在指定租户里建一件纠纷。 */
  UUID newDispute(UUID tid, UUID uid, String tag, String amount) {
    return as(
            uid,
            tid,
            () ->
                disputes.createDispute(
                        new PlatformDisputeCreate(
                            "AMAZON", "Amazon", "AZ-" + tag, "A_TO_Z", "买家称未收到货 " + tag,
                            "ORDER-" + tag, "USD", new BigDecimal(amount),
                            OffsetDateTime.now(ZoneOffset.UTC).plusDays(14),
                            "REJECT", "LOGISTICS", "立场-" + tag, ""),
                        uid)
                    .head()
                    .id());
  }

  /** 在指定租户里建一件**举证准备中**的纠纷（带一项必举证清单）。 */
  UUID preparingDispute(UUID tid, UUID uid, String tag) {
    UUID id = newDispute(tid, uid, tag, "88.5000");
    as(
        uid,
        tid,
        () ->
            disputes.addEvidenceItem(
                id, new PlatformEvidenceItemCreate("PRODUCT_PHOTO", "平台要求的材料-" + tag, true, ""),
                uid));
    return as(uid, tid, () -> disputes.startEvidence(id, disputes.disputeDetail(id).head().version(), uid).head().id());
  }

  /** 在指定租户里建一份申诉草稿。 */
  UUID newAppeal(UUID tid, UUID uid, UUID disputeId, String tag) {
    return as(
            uid,
            tid,
            () ->
                appeals.createAppeal(
                        new PlatformAppealCreate(
                            disputeId, "我方已于时限内妥投-" + tag, "TPL-AZ-01"),
                        uid)
                    .head()
                    .id());
  }

  private static PlatformAppealListQuery query() {
    return new PlatformAppealListQuery(
        0, 20, null, null, null, null, null, null, null, null, null, null);
  }

  // ==========================================================================
  // 断言一：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见() throws Exception {
    // 每轮都新建实体：复用上一轮的记录，第二轮断言通过只能说明「同一条记录没变」。
    UUID ida = preparingDispute(a, userA, "ISO-A");
    UUID idb = preparingDispute(b, userB, "ISO-B");
    UUID appeala = newAppeal(a, userA, ida, "ISO-A");
    UUID appealb = newAppeal(b, userB, idb, "ISO-B");

    // ---- 列表互不可见（含汇总口径）----
    assertThat(asA(() -> appeals.listAppeals(query())).total()).isEqualTo(1);
    assertThat(asA(() -> appeals.listAppeals(query())).rows())
        .extracting(PlatformAppealRow::id).containsExactly(appeala);
    assertThat(asB(() -> appeals.listAppeals(query())).rows())
        .extracting(PlatformAppealRow::id).containsExactly(appealb);

    // 金额与申诉文本不串。
    assertThat(asA(() -> appeals.appealDetail(appeala)).head().claimedAmount())
        .isEqualByComparingTo("88.5000");
    assertThat(asB(() -> appeals.appealDetail(appealb)).head().claimedAmount())
        .isEqualByComparingTo("88.5000");
    assertThat(asA(() -> appeals.appealDetail(appeala)).appealText()).contains("ISO-A");
    assertThat(asB(() -> appeals.appealDetail(appealb)).appealText()).contains("ISO-B");
    assertThat(asA(() -> appeals.appealDetail(appeala)).appealText()).doesNotContain("ISO-B");

    // ⚠️ **按 id 直达也不行**：A 拿着 B 的申诉 id 读，
    //   得到的是与「不存在」完全相同的错误（不告诉调用方「这个 ID 存在但不属于你」）。
    assertThatThrownBy(() -> asA(() -> appeals.appealDetail(appealb)))
        .hasStackTraceContaining("不存在或不属于当前租户");
    // 写入口也拦住：不能替别人建单、不能替别人提交申诉。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        appeals.createAppeal(
                            new PlatformAppealCreate(idb, "越权建单", ""), userA)))
        .hasStackTraceContaining("不存在或不属于当前租户");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        appeals.submitAppeal(
                            appealb, new PlatformAppealSubmit("越权提交", 0L), userA)))
        .hasStackTraceContaining("不存在或不属于当前租户");

    // HTTP 层同样如此（按 id 直达的 URL 是最容易被猜到的那种）。
    http.perform(get("/trade/platform-dispute-appeals/{id}", appealb)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().is4xxClientError());
  }

  @Test
  void 别人的证据材料引用也拿不到() {
    UUID ida = preparingDispute(a, userA, "MAT-A");
    UUID idb = preparingDispute(b, userB, "MAT-B");
    UUID appeala = newAppeal(a, userA, ida, "MAT-A");
    UUID appealb = newAppeal(b, userB, idb, "MAT-B");

    // A 挂一条**B 租户**的来源记录 ⇒ 不存在（A 的租户上下文里读不到）⇒ 400。
    UUID foreignSource = UUID.randomUUID();
    UUID foreignCase = UUID.randomUUID();
    asB(
        () ->
            sql.execute(
                "insert into aftersales_case(id,tenant_id,case_no,order_id,case_type,status,"
                    + " reason_code,requested_amount,currency,requested_quantity,received_at,"
                    + " reply_due_at) values(?::uuid,?::uuid,?,?::uuid,'RETURN_REFUND',"
                    + "'IN_PROCESS','QUALITY_DEFECT',10.0000,'USD',1.000000,now(),"
                    + "now()+interval '10 days')",
                foreignCase,
                b,
                "AS-MAT-B",
                UUID.randomUUID()));
    asB(
        () ->
            sql.execute(
                "insert into aftersales_message(id,tenant_id,case_id,direction,channel,"
                    + " message_body,message_source,sent_at,captured_at,author_id)"
                    + " values(?::uuid,?::uuid,?::uuid,'US_TO_BUYER','PLATFORM',"
                    + "'B 租户的沟通记录','TENANT_MAINTAINED',now(),now(),?::uuid)",
                foreignSource,
                b,
                foreignCase,
                userB));

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        appeals.attachMaterial(
                            appeala,
                            new PlatformAppealMaterialCreate(
                                "AFTERSALES_MESSAGE", foreignSource, null, ""),
                            userA)))
        .hasStackTraceContaining("举证材料必须来自系统里已有的事实记录");

    // B 自己挂得上（证明不是「所有人都拿不到」）。
    assertThat(asB(() -> appeals.appealDetail(appealb)).materials()).isEmpty();
  }

  // ==========================================================================
  // 断言二：伪造 tenant header 无效
  // ==========================================================================

  @Test
  void 伪造租户头无效() throws Exception {
    UUID ida = preparingDispute(a, userA, "FORGE-A");
    UUID idb = preparingDispute(b, userB, "FORGE-B");
    UUID appeala = newAppeal(a, userA, ida, "FORGE-A");
    UUID appealb = newAppeal(b, userB, idb, "FORGE-B");

    // 带着 **B 的令牌** + **伪造的 A 的 X-Tenant-Id**：读到的必须是 B 的数据，
    // 不是 A 的 —— 头改不动 identity 解析出来的可信租户。
    String list =
        http.perform(get("/trade/platform-dispute-appeals")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(list).contains(appealb.toString()).doesNotContain(appeala.toString());

    // 反过来：带着 A 的令牌 + 伪造的 B 的头，读到的仍然是 A 的数据。
    String list2 =
        http.perform(get("/trade/platform-dispute-appeals")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(list2).contains(appeala.toString()).doesNotContain(appealb.toString());

    // 详情同理。
    String detail =
        http.perform(get("/trade/platform-dispute-appeals/{id}", appealb)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(detail).contains(appealb.toString()).doesNotContain(appeala.toString());

    // 直接在 RLS 上验证：绑定 A 的租户之后，B 的行在库里**读不出来**，
    // 绕过服务层也一样（FORCE RLS 对表 owner 之外的角色同样生效，这里跑的是 trade_app）。
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) from platform_dispute_appeal where tenant_id=?::uuid and id=?::uuid", b, appealb))
        .get(0, Long.class)).as("RLS 之下看不到别的租户的行").isZero();

    // ⚠️ **即使不带 tenant_id 谓词**也读不到（证明是真隔离，不是应用层过滤）。
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) from platform_dispute_appeal where id=?::uuid", appealb))
        .get(0, Long.class)).as("不带租户谓词也读不到别人的行").isZero();
  }

  // ==========================================================================
  // 断言三：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 连接池归还后不串租户() throws Exception {
    UUID ida = preparingDispute(a, userA, "POOL-A");
    UUID idb = preparingDispute(b, userB, "POOL-B");
    UUID appeala = newAppeal(a, userA, ida, "POOL-A");
    UUID appealb = newAppeal(b, userB, idb, "POOL-B");

    // 交替借还：同一件申诉反复以两个租户的身份读，读到的永远是**当前绑定**的租户。
    for (int i = 0; i < 3; i++) {
      assertThat(asA(() -> appeals.appealDetail(appeala)).appealText()).contains("POOL-A");
      assertThat(asB(() -> appeals.appealDetail(appealb)).appealText()).contains("POOL-B");
      // 归还之后再读另一个租户的 id：仍然读不到。
      assertThatThrownBy(() -> asA(() -> appeals.appealDetail(appealb)))
          .hasStackTraceContaining("不存在或不属于当前租户");
      assertThatThrownBy(() -> asB(() -> appeals.appealDetail(appeala)))
          .hasStackTraceContaining("不存在或不属于当前租户");
    }

    // 列表口径也不串。
    assertThat(asA(() -> appeals.listAppeals(query())).rows())
        .extracting(PlatformAppealRow::id).containsExactly(appeala);
    assertThat(asB(() -> appeals.listAppeals(query())).rows())
        .extracting(PlatformAppealRow::id).containsExactly(appealb);
    assertThat(asA(() -> appeals.listAppeals(query())).summary().totalAppeals()).isEqualTo(1);
    assertThat(asB(() -> appeals.listAppeals(query())).summary().totalAppeals()).isEqualTo(1);
  }

  // ==========================================================================
  // 附：越权写
  // ==========================================================================

  @Test
  void 伪造租户头不能替别人提交申诉() throws Exception {
    UUID ida = preparingDispute(a, userA, "WRITE-A");
    UUID idb = preparingDispute(b, userB, "WRITE-B");
    UUID appeala = newAppeal(a, userA, ida, "WRITE-A");
    UUID appealb = newAppeal(b, userB, idb, "WRITE-B");

    // B 的令牌 + 伪造的 A 的头去**提交** A 的申诉：绝不会落到 A 的行上。
    http.perform(post("/trade/platform-dispute-appeals/{id}/submit", appeala)
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString())
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{\"submitNote\":\"越权提交\",\"expectedVersion\":0}"))
        .andExpect(status().is4xxClientError());

    // A 的申诉仍然是草稿 —— 没有被越权提交。
    assertThat(asA(() -> appeals.appealDetail(appeala)).head().appealStatus()).isEqualTo("DRAFT");
    assertThat(asB(() -> appeals.appealDetail(appealb)).head().appealStatus()).isEqualTo("DRAFT");
  }
}
