package com.starcon.trade;

import static com.starcon.trade.AftersalesReasonModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.1.2.1 售后原因分类的**多租户三断言**。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且**实跑在真实业务表**上
 * （aftersales_reason_category / _attribution_set / _attribution_item /
 * _platform_mapping），<b>不是</b>占位探针表 —— 探针表能证明 RLS 开着，
 * 证明不了<b>本模块的分类树、归因与报表查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>，理由和别处不同：
 * 原因分类是**跨租户汇总口径**的数据。漏掉租户谓词，后果不是「A 家看到 B 家的买家姓名」
 * 那种一眼能发现的泄露，而是 —— A 家的「质量问题占比」里混进了 B 家的退货数据，
 * 退货率分析**整体失真**，而报表看上去完全正常，没有任何报错。
 * 蓝图加粗句「原因口径不统一会让退货率分析失真」防的就是这件事，
 * 所以隔离必须在这几张表上**逐条实跑**，不能靠「RLS 开着应该就没事」。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AftersalesReasonTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms =
      List.of(
          "trade.aftersales.reason.read",
          "trade.aftersales.reason.write",
          "trade.aftersales.read",
          "trade.aftersales.write");

  static AccessService.Access actor(UUID uid, UUID tenant) {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms));
    return new AccessService.Access(uid, tenant, "Test", UUID.randomUUID(), null, perms, grants);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var found = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (found == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(found);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(userA, a));
      actors.put("Bearer tenant-b", actor(userB, b));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired AftersalesReasonService reasons;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00Z");

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate aftersales_reason_attribution_item, aftersales_reason_attribution_set,"
              + " aftersales_reason_platform_mapping, aftersales_reason_category,"
              + " aftersales_case_note, aftersales_authorization, aftersales_service_ticket,"
              + " aftersales_owner_assignment, aftersales_case, sales_order cascade");
    }
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.aftersales.reason.write", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-b"), "trade.aftersales.reason.write", work);
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return tenant.equals(a) ? asA(work) : asB(work);
  }

  AftersalesReasonTreeQuery all() {
    return new AftersalesReasonTreeQuery(null, null, null);
  }

  /**
   * 一张真实订单 + 一张真实售后单（走 11.1.1.1 自己的登记端点，不在测试里直接 INSERT）。
   *
   * <p>本模块对 {@code aftersales_case} 全部只读；用它只是为了给归因一个**真实**的落点 ——
   * 归因集合的触发器会真查售后单是否存在。
   */
  /**
   * 一张真实订单（V99 手工单簇），显式带 tenant_id。
   *
   * <p>⚠️ 原始 JDBC 必须写在 {@code Supplier} <b>外面</b>：Supplier 不能抛
   * {@code SQLException}，把它塞进 {@code as(tenant, …)} 里会在编译期就失败。
   * 夹具连接用的是迁移角色（绕过 RLS），所以 tenant_id **必须显式传对** ——
   * 夹具本身不受 RLS 保护，写错就是写错，测试后面会以此失败。
   *
   * <p>也必须**显式开事务**：V99 的「订单必须带商品行 / 收货地址才能 SUBMITTED」
   * 是延迟到 commit 才检查的约束触发器，autocommit 下第一条 insert 就提交了。
   */
  UUID seedOrder(UUID tenant, String orderNo) throws Exception {
    UUID orderId = UUID.randomUUID();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      c.setAutoCommit(false);
      try (var p =
          c.prepareStatement(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?::uuid,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,"
                  + "10.0000,0,0,0,0,10.0000,0,?::timestamptz,?::timestamptz)")) {
        p.setObject(1, orderId);
        p.setObject(2, tenant);
        p.setString(3, orderNo);
        p.setString(4, "EXT-" + orderNo);
        p.setObject(5, T1);
        p.setObject(6, T1);
        p.setObject(7, T1);
        p.executeUpdate();
      }
      try (var l =
          c.prepareStatement(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,"
                  + "sku_name,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
                  + "created_at) values(?,?::uuid,?::uuid,1,?,?,?,1,10.0000,0,0,10.0000,'',"
                  + "?::timestamptz)")) {
        l.setObject(1, UUID.randomUUID());
        l.setObject(2, tenant);
        l.setObject(3, orderId);
        l.setObject(4, UUID.randomUUID());
        l.setString(5, "SKU-" + orderNo);
        l.setString(6, "商品");
        l.setObject(7, T1);
        l.executeUpdate();
      }
      try (var ad =
          c.prepareStatement(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,"
                  + "recipient_name,recipient_phone,recipient_email,company_name,country_code,"
                  + "province,city,district,postcode,address_line1,address_line2,tax_id,note,"
                  + "created_at) values(?,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',"
                  + "?::varchar,'','US','CA','LOS ANGELES','','90001','100 Main St','','','',"
                  + "?::timestamptz)")) {
        ad.setObject(1, UUID.randomUUID());
        ad.setObject(2, tenant);
        ad.setObject(3, orderId);
        ad.setString(4, "买家" + orderNo);
        ad.setString(5, "buyer@example.com");
        ad.setObject(6, T1);
        ad.executeUpdate();
      }
      try (var ps =
          c.prepareStatement(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?::uuid,?::uuid,?,?,?,'INDIVIDUAL','',?::timestamptz)")) {
        ps.setObject(1, UUID.randomUUID());
        ps.setObject(2, tenant);
        ps.setObject(3, orderId);
        ps.setObject(4, UUID.randomUUID());
        ps.setString(5, "C-" + orderNo);
        ps.setString(6, "买家" + orderNo);
        ps.setObject(7, T1);
        ps.executeUpdate();
      }
      c.commit();
    }
    return orderId;
  }

  /**
   * 一张真实订单 + 一张真实售后单（走 11.1.1.1 自己的登记端点，不在测试里直接 INSERT）。
   *
   * <p>本模块对 {@code aftersales_case} 全部只读；用它只是为了给归因一个**真实**的落点 ——
   * 归因成员的触发器会真查售后单是否存在（且属于同一租户）。
   */
  UUID seedCase(UUID tenant, String orderNo) throws Exception {
    UUID orderId = seedOrder(tenant, orderNo);
    var payload = new java.util.LinkedHashMap<String, Object>();
    payload.put("orderId", orderId.toString());
    payload.put("caseType", "RETURN_REFUND");
    payload.put("reasonCode", "QUALITY_DEFECT");
    payload.put("requestedAmount", "10.0000");
    payload.put("currency", "USD");
    payload.put("requestedQuantity", "1.000000");
    payload.put("buyerRequest", orderNo + " 的买家诉求原文");
    payload.put("receivedAt", T1.toString());
    payload.put("resolveDueAt", T1.toString());
    var node =
        json.readTree(
            http.perform(
                    post("/trade/aftersales/cases")
                        // ⚠️ 必须用**被建单的那个租户**的会话：
                        //   固定写 tenant-a 会让「B 的单」其实落在 A 名下，
                        //   于是后面所有「B 看不到 / B 算自己的」断言都变成空断言。
                        .header(
                            "Authorization",
                            tenant.equals(a) ? "Bearer tenant-a" : "Bearer tenant-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    return UUID.fromString(node.get("id").asText());
  }

  /** 让某个租户的基线词表落库，并返回 ROOT.QUALITY_DEFECT 的 id。 */
  UUID qualityId(UUID tenant) {
    return as(
        tenant,
        () -> {
          var tree = reasons.tree(all());
          return tree.nodes().stream()
              .filter(n -> n.categoryCode().equals("ROOT.QUALITY_DEFECT"))
              .findFirst()
              .orElseThrow()
              .id();
        });
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 四张业务表都强制rls而且运行角色真的能读写() {
    for (String table :
        List.of(
            "aftersales_reason_category",
            "aftersales_reason_attribution_set",
            "aftersales_reason_attribution_item",
            "aftersales_reason_platform_mapping")) {
      var row =
          sql.fetchOne("select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V158 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('trade_app',?,'SELECT')", table)
                  .get(0, Boolean.class))
          .as("trade_app 必须真的能读 " + table + "，否则「看不到」可能只是谁都读不了")
          .isTrue();
    }
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() throws Exception {
    UUID caseA = seedCase(a, "MO-A");
    UUID caseB = seedCase(b, "MO-B");
    UUID qa = qualityId(a);
    UUID qb = qualityId(b);

    // 两家的基线词表各自落库，互不可见。
    assertThat(asA(() -> reasons.tree(all()).nodes())).hasSize(7);
    assertThat(asB(() -> reasons.tree(all()).nodes())).hasSize(7);

    // A 归因自己的单，B 归因自己的单。
    asA(
        () ->
            reasons.attribute(
                new AftersalesReasonAttributionCreate(
                    caseA, "A 家的归因", List.of(item(qa, true, "1.000000"))),
                userA));
    asB(
        () ->
            reasons.attribute(
                new AftersalesReasonAttributionCreate(
                    caseB, "B 家的归因", List.of(item(qb, true, "1.000000"))),
                userB));

    // 更强的一格：**按主键直查对方的分类也拿不到**（不只是列表过滤）。
    assertThat(asA(() -> sql.fetch("select id from aftersales_reason_category where id = ?::uuid", qb).size()))
        .as("按主键直查也必须被 RLS 挡住")
        .isZero();
    assertThat(asB(() -> sql.fetch("select id from aftersales_reason_category where id = ?::uuid", qa).size()))
        .isZero();

    // 两家的分类 id **完全不同**（基线是按租户分别落库的，不是共享的全局行）。
    assertThat(qa).isNotEqualTo(qb);
    // 且各自的归因只指向自己的分类。
    assertThat(asA(() -> sql.fetch("select category_id from aftersales_reason_attribution_item").size()))
        .isEqualTo(1);
    assertThat(asB(() -> sql.fetch("select category_id from aftersales_reason_attribution_item").size()))
        .isEqualTo(1);
    assertThat(asA(() -> sql.fetchOne("select category_id from aftersales_reason_attribution_item").get(0, UUID.class)))
        .isEqualTo(qa);
    assertThat(asB(() -> sql.fetchOne("select category_id from aftersales_reason_attribution_item").get(0, UUID.class)))
        .isEqualTo(qb);

    // 归因的**自由文本**同样不能跨租户读到（它写着判责理由）。
    assertThat(asA(() -> sql.fetchOne("select attribution_note from aftersales_reason_attribution_set").get(0, String.class)))
        .isEqualTo("A 家的归因");
    assertThat(asB(() -> sql.fetchOne("select attribution_note from aftersales_reason_attribution_set").get(0, String.class)))
        .isEqualTo("B 家的归因");

    // **报表口径**必须各自独立 —— 这才是本模块最要紧的一格：
    // 漏掉租户谓词的话，A 的「质量问题占比」会变成 50%（混进 B 的那一条），
    // 而报表看上去完全正常，没有任何报错。
    var reportA = asA(() -> reasons.report());
    var reportB = asB(() -> reasons.report());
    assertThat(reportA.attributedCaseCount()).as("A 只能算进自己那一单").isEqualTo(1);
    assertThat(reportB.attributedCaseCount()).isEqualTo(1);
    assertThat(reportA.attributionItemCount()).isEqualTo(1);
    assertThat(reportB.attributionItemCount()).isEqualTo(1);
    assertThat(rootOf(reportA, "QUALITY_DEFECT").attributionCaseCount()).isEqualTo(1);
    assertThat(rootOf(reportB, "QUALITY_DEFECT").attributionCaseCount()).isEqualTo(1);
    assertThat(rootOf(reportA, "QUALITY_DEFECT").caseSharePercent())
        .as("A 只有一单已归因，占比必须是 100.00 —— 混进 B 就不是 100 了")
        .isEqualByComparingTo("100.00");
    assertThat(rootOf(reportB, "QUALITY_DEFECT").caseSharePercent()).isEqualByComparingTo("100.00");
    // 其它六类在两家都必须是 0：不能因为对方有数据就被算成本家的。
    for (String kind :
        List.of(
            "DESCRIPTION_MISMATCH", "SIZE_MISMATCH", "LOGISTICS_DAMAGE",
            "BUYER_REASON", "MISTAKE_BUY", "FRAUD_RETURN")) {
      assertThat(rootOf(reportA, kind).attributionCaseCount()).as(kind + " 在 A 侧必须为 0").isZero();
      assertThat(rootOf(reportB, kind).attributionCaseCount()).as(kind + " 在 B 侧必须为 0").isZero();
    }
  }

  private static AftersalesReasonRootTotal rootOf(AftersalesReasonReport report, String kind) {
    return report.rootTotals().stream()
        .filter(t -> t.rootKind().equals(kind))
        .findFirst()
        .orElseThrow(() -> new AssertionError("七类里缺了 " + kind));
  }

  private static AftersalesReasonAttributionItem item(UUID categoryId, boolean primary, String weight) {
    return new AftersalesReasonAttributionItem(categoryId, primary, new BigDecimal(weight));
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    UUID caseA = seedCase(a, "MO-HDR");

    // 读侧：带合法会话 + 伪造 X-Tenant-Id ⇒ 拿到的是**会话租户**的数据。
    var body =
        http.perform(get("/trade/aftersales/reasons/tree").header("Authorization", "Bearer tenant-a").header("X-Tenant-Id", b))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var parsed = json.readTree(body);
    assertThat(parsed.get("nodes").size()).as("A 只看得到自己的七类").isEqualTo(7);

    // 伪造 X-Tenant-Id 不能开通写入口：用 A 的会话 + B 的头去建分类，
    // 行仍然落在 **A** 名下，B 读不到。
    var create = new java.util.LinkedHashMap<String, Object>();
    create.put("categoryCode", "FORGED_BY_A");
    create.put("categoryName", "A 用伪造头建的分类");
    create.put("rootKind", "FRAUD_RETURN");
    create.put("sortOrder", 0);
    http.perform(
            post("/trade/aftersales/reasons/categories")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(create)))
        .andExpect(status().isCreated());
    assertThat(asA(() -> sql.fetch("select id from aftersales_reason_category where category_code = 'FORGED_BY_A'").size()))
        .as("伪造头不得把行带进 B 的租户 —— 它必须落在会话租户 A 名下")
        .isEqualTo(1);
    assertThat(asB(() -> sql.fetch("select id from aftersales_reason_category where category_code = 'FORGED_BY_A'").size()))
        .as("B 名下必须查不到 A 建的分类")
        .isZero();

    // 跨租户 ID 的**写**操作：拿 A 的分类 ID 去 B 家改状态 ⇒ 必须报「不存在」，
    // 而不是「静默成功」（B 会以为改成功，而 A 的分类其实纹丝未动）。
    var forgedForB =
        json.readTree(
            http.perform(
                    post("/trade/aftersales/reasons/categories")
                        .header("Authorization", "Bearer tenant-b")
                        .header("X-Tenant-Id", a)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString())
            .get("id")
            .asText();
    http.perform(
            patch("/trade/aftersales/reasons/categories/" + forgedForB)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryStatus\":\"DISABLED\"}"))
        .andExpect(status().isBadRequest());
    assertThat(asB(() -> sql.fetchOne("select category_status from aftersales_reason_category where id = ?::uuid", forgedForB).get(0, String.class)))
        .as("B 的分类不能被 A 的会话改掉 —— A 只能看到「它不存在」")
        .isEqualTo("ACTIVE");

    // 跨租户 ID 的**归因**：拿 A 的分类 ID 去给 B 的单归因 ⇒ 分类「不存在」。
    // 这是本模块最容易出事的越权形态：它会让 B 的退货原因被记成 A 的口径，
    // 而 A 的报表里**根本没有这条数据** —— 口径就这么凭空多了一条。
    UUID qa = qualityId(a);
    // 先用 A 的会话 + A 的分类给 A 的单做一次合法归因作为对照组：
    // 没有这条对照，下面「越权归因被拒」就可能只是「归因端点整体坏了」。
    assertThat(
            asA(
                () ->
                    reasons.attribute(
                            new AftersalesReasonAttributionCreate(
                                caseA, "", List.of(item(qa, true, "1.000000"))),
                        userA)
                        .current()
                        .itemCount()))
        .isEqualTo(1);
    // 但 B 用 A 的分类 ID 去归因 ⇒ 分类在 B 的可见范围内不存在。
    var crossBody = new java.util.LinkedHashMap<String, Object>();
    crossBody.put("caseId", caseA.toString());
    crossBody.put("note", "越权归因");
    crossBody.put(
        "items",
        List.of(
            java.util.Map.of("categoryId", qa.toString(), "isPrimary", true, "weight", "1.000000")));
    http.perform(
            post("/trade/aftersales/reasons/attributions")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(crossBody)))
        .andExpect(status().isBadRequest());
    // B 名下仍然一条归因都没有。
    assertThat(asB(() -> sql.fetch("select id from aftersales_reason_attribution_set").size()))
        .as("越权归因不得落库")
        .isZero();
    // A 的那条归因没被动过（note 仍然是空，A 写的是空字符串）。
    assertThat(asA(() -> sql.fetchOne("select attribution_note from aftersales_reason_attribution_set").get(0, String.class)))
        .isEqualTo("");

    // 没有会话就没有租户上下文。
    http.perform(get("/trade/aftersales/reasons/tree")).andExpect(status().isUnauthorized());
    http.perform(get("/trade/aftersales/reasons/reports")).andExpect(status().isUnauthorized());
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    UUID caseA = seedCase(a, "MO-PA");
    UUID caseB = seedCase(b, "MO-PB");
    UUID qa = qualityId(a);
    UUID qb = qualityId(b);
    asA(
        () ->
            reasons.attribute(
                new AftersalesReasonAttributionCreate(caseA, "", List.of(item(qa, true, "1.000000"))),
                userA));
    asB(
        () ->
            reasons.attribute(
                new AftersalesReasonAttributionCreate(caseB, "", List.of(item(qb, true, "1.000000"))),
                userB));

    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后面几轮读到的仍然是本租户而不是上一轮残留。
    for (int i = 0; i < 5; i++) {
      var reportA = asA(() -> reasons.report());
      var reportB = asB(() -> reasons.report());
      assertThat(reportA.attributedCaseCount()).as("第 %d 轮租户 A", i).isEqualTo(1);
      assertThat(reportB.attributedCaseCount()).as("第 %d 轮租户 B", i).isEqualTo(1);
      // 分类树也必须各自是 7 行、且 id 不重叠（基线是按租户分别落库的）。
      assertThat(asA(() -> reasons.tree(all()).nodes())).as("第 %d 轮 A 的分类树", i).hasSize(7);
      assertThat(asB(() -> reasons.tree(all()).nodes())).as("第 %d 轮 B 的分类树", i).hasSize(7);
      // 归因读回也必须各自指向本租户的分类。
      assertThat(asA(() -> reasons.attribution(caseA).current().items().get(0).categoryId()))
          .as("第 %d 轮 A 的归因", i)
          .isEqualTo(qa);
      assertThat(asB(() -> reasons.attribution(caseB).current().items().get(0).categoryId()))
          .as("第 %d 轮 B 的归因", i)
          .isEqualTo(qb);
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(asA(() -> reasons.report().attributedCaseCount())).isEqualTo(1);
    assertThat(asB(() -> reasons.report().attributedCaseCount())).isEqualTo(1);
  }

  // ==========================================================================
  // 额外一条：事务外（没有任何租户上下文）读不到任何一行
  // ==========================================================================
  @Test
  void 没有租户上下文时一张也读不到() {
    assertThat(sql.fetch("select * from aftersales_reason_category").size())
        .as("RLS 是最后一道：没有 app.current_tenant 就是零行")
        .isZero();
    assertThat(sql.fetch("select * from aftersales_reason_attribution_set").size()).isZero();
    assertThat(sql.fetch("select * from aftersales_reason_attribution_item").size()).isZero();
    assertThat(sql.fetch("select * from aftersales_reason_platform_mapping").size()).isZero();
  }
}
