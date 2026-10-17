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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.1.2.1 售后原因分类（分类树维护 + 多选归因 + 平台原因码映射 + 统一口径报表）。
 *
 * <p>全部走 <b>真实 PostgreSQL 16 + 强制 RLS</b>，不使用任何内存替身。
 * 订单夹具走 V99 表（9.1.2.2 手工单的写入所有者），售后单夹具走 11.1.1.1 自己的登记端点
 * （V156 的表，本模块只读它）。
 *
 * <p><b>本测试最要紧的是那些「不该能发生」的事</b>：
 *
 * <ul>
 *   <li><b>加粗句「必须强制选择分类而不是留自由文本」的反面要真的被挡住。</b>
 *       零个原因、两个主因、零个主因、重复分类、权重缺失 —— 五种写法逐个试，
 *       服务层要报 400；<b>并且</b>再绕开服务层直接写 SQL 试一遍，
 *       DB 的提交期 DEFERRABLE 约束触发器必须挡住前两种。
 *       只测服务层的话，哪天有人把端点换成内联 SQL 绕过校验，这条会红。
 *   <li><b>「可归因性」要真的随停用变化</b>：停用父节点后，
 *       它的子节点也必须变成不可选 —— 否则停用一个口径，
 *       运营还能通过「勾它的子分类」把数据继续算进去。
 *   <li><b>报表口径</b>：改过一次归因的单只算一次（历史版本不重复计入）；
 *       主因与次因分开计数；七类零也出现。
 *   <li><b>写回的时刻值</b>用 +08:00 非 UTC 偏移的夹具断言到绝对时刻 ——
 *       {@code Timestamp.valueOf} 的实现会静默偏 −28800s，而只断言「没抛异常」的测试照绿。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AftersalesReasonIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      List.of(
          "trade.aftersales.reason.read",
          "trade.aftersales.reason.write",
          // 夹具要经 11.1.1.1 的**真实登记端点**建售后单（V156 的表是别的车道的写入所有者，
          // 本模块只读它，不能自己 INSERT 出一张形状不同的单）。所以操作人还必须持有
          // 11.1.1.1 的读与写权限 —— 这不是给本模块放宽权限，是夹具需要一个真实上游。
          "trade.aftersales.read",
          "trade.aftersales.write");

  static AccessService.Access actor(UUID uid, UUID tenant, List<String> perms) {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms));
    return new AccessService.Access(uid, tenant, "Test", UUID.randomUUID(), null, perms, grants);
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
      actors.put("Bearer tenant-a", actor(user, a, allPermissions));
      // 只读角色：用来证明写端点真的要求 write 权限，而不是「有读就能改归因」。
      actors.put(
          "Bearer tenant-readonly", actor(user, a, List.of("trade.aftersales.reason.read")));
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
  }

  @Autowired DSLContext sql;
  @Autowired AftersalesReasonService reasons;
  @Autowired AccessService access;
  @Autowired com.starcon.tenancy.TenantTransactions tenants;
  @Autowired MockMvc http;

  /** 在**租户事务**里跑一段直写 SQL。V158 四张表全部 FORCE RLS，事务外一行都碰不到。 */
  <T> T inTenant(java.util.function.Supplier<T> work) {
    return tenants.run(a, work);
  }

  <T> T asWriter(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.aftersales.reason.write", work);
  }

  /** 同一事务上下文里跑一段**无返回值**的写操作（删分类 / 删映射）。 */
  void asWriterVoid(Runnable work) {
    asWriter(
        () -> {
          work.run();
          return null;
        });
  }

  static final OffsetDateTime T0 = OffsetDateTime.parse("2026-03-01T00:00:00Z");

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

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

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 一张真实订单（V99 手工单簇）。必须显式开事务：V99 的约束触发器延迟到 commit 才检查。 */
  UUID seedOrder(String orderNo) throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      c.setAutoCommit(false);
      try (var p =
          c.prepareStatement(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,10.0000,0,0,"
                  + "0,0,10.0000,0,?::timestamptz,?::timestamptz)")) {
        p.setObject(1, id);
        p.setObject(2, a);
        p.setString(3, orderNo);
        p.setString(4, "EXT-" + orderNo);
        p.setObject(5, OffsetDateTime.parse("2026-02-01T00:00:00Z"));
        p.setObject(6, OffsetDateTime.parse("2026-02-01T00:00:00Z"));
        p.setObject(7, OffsetDateTime.parse("2026-02-01T00:00:00Z"));
        p.executeUpdate();
      }
      try (var l =
          c.prepareStatement(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,"
                  + "sku_name,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
                  + "created_at) values(?,?,?,1,?,?,?,1,10.0000,0,0,10.0000,'',?::timestamptz)")) {
        l.setObject(1, UUID.randomUUID());
        l.setObject(2, a);
        l.setObject(3, id);
        l.setObject(4, UUID.randomUUID());
        l.setString(5, "SKU-" + orderNo);
        l.setString(6, "商品");
        l.setObject(7, OffsetDateTime.parse("2026-02-01T00:00:00Z"));
        l.executeUpdate();
      }
      try (var ad =
          c.prepareStatement(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,"
                  + "recipient_name,recipient_phone,recipient_email,company_name,country_code,"
                  + "province,city,district,postcode,address_line1,address_line2,tax_id,note,"
                  + "created_at) values(?,?,?,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
                  + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)")) {
        ad.setObject(1, UUID.randomUUID());
        ad.setObject(2, a);
        ad.setObject(3, id);
        ad.setString(4, "买家" + orderNo);
        ad.setString(5, "buyer@example.com");
        ad.setObject(6, OffsetDateTime.parse("2026-02-01T00:00:00Z"));
        ad.executeUpdate();
      }
      try (var ps =
          c.prepareStatement(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?,?,?,?,?,'INDIVIDUAL','',?::timestamptz)")) {
        ps.setObject(1, UUID.randomUUID());
        ps.setObject(2, a);
        ps.setObject(3, id);
        ps.setObject(4, UUID.randomUUID());
        ps.setString(5, "C-" + orderNo);
        ps.setString(6, "买家" + orderNo);
        ps.setObject(7, OffsetDateTime.parse("2026-02-01T00:00:00Z"));
        ps.executeUpdate();
      }
      c.commit();
    }
    return id;
  }

  /** 经 11.1.1.1 的真实登记端点建一张售后单（本模块只读它，不自己造单）。 */
  UUID seedCase(String orderNo) throws Exception {
    UUID orderId = seedOrder(orderNo);
    // ⚠️ 这里**不**套 asWriter：HTTP 调用走的是控制器，它自己绑定租户事务；
    //   而 Supplier 不能抛 getResponse() / readTree() 的受检异常。
    {
      {
          var body =
              """
              {"orderId":"%s","caseType":"RETURN_REFUND","reasonCode":"QUALITY_DEFECT",
               "requestedAmount":"10.0000","currency":"USD","requestedQuantity":"1.000000",
               "buyerRequest":"外箱破损","receivedAt":"2026-03-01T00:00:00Z",
               "resolveDueAt":"2026-03-08T00:00:00Z"}
              """
                  .formatted(orderId);
          var response =
              http.perform(
                      post("/trade/aftersales/cases")
                          .header("Authorization", "Bearer tenant-a")
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(body))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse();
          return UUID.fromString(json.readTree(response.getContentAsString()).get("id").asText());
      }
    }
  }

  /** 读一次树把基线词表落下来，返回 code → node 的索引。 */
  java.util.Map<String, AftersalesReasonTreeNode> baseline() {
    return asWriter(
        () -> {
          var tree = reasons.tree(new AftersalesReasonTreeQuery(null, null, null));
          var out = new java.util.LinkedHashMap<String, AftersalesReasonTreeNode>();
          for (var node : tree.nodes()) out.put(node.categoryCode(), node);
          return out;
        });
  }

  AftersalesReasonTreeNode node(String code) {
    return baseline().get(code);
  }

  List<AftersalesReasonAttributionItem> items(UUID categoryId, boolean primary, String weight) {
    return List.of(new AftersalesReasonAttributionItem(categoryId, primary, new BigDecimal(weight)));
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  @Test
  void 能力声明如实报出上游跨服务读不可得与下游未交付() throws Exception {
    var response =
        http.perform(get("/trade/aftersales/reasons/capabilities")
                .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var body = json.readTree(response.getContentAsString());

    assertThat(body.get("crossServiceReadAvailable").asBoolean())
        .as("channel 的枚举映射在别的服务的独立库里，禁止跨服务 SQL ⇒ 必须报 false")
        .isFalse();
    assertThat(body.get("platformMappingOwnerModule").asText()).contains("2.1.2.1");
    assertThat(body.get("qualityAnalysisDelivered").asBoolean()).isFalse();
    assertThat(body.get("supplierClaimDelivered").asBoolean()).isFalse();
    assertThat(body.get("rootKinds")).hasSize(7);
    assertThat(body.get("rootKinds").findValuesAsText("value"))
        .containsExactly(
            "QUALITY_DEFECT",
            "DESCRIPTION_MISMATCH",
            "SIZE_MISMATCH",
            "LOGISTICS_DAMAGE",
            "BUYER_REASON",
            "MISTAKE_BUY",
            "FRAUD_RETURN");
    assertThat(body.get("maxDepth").asInt()).isEqualTo(3);
    // 界面必须能说清「为什么平台原因码是租户自己维护的」，而不是显示一个空下拉框。
    var shop =
        body.get("unavailableDimensions").findValuesAsText("name").contains("platformShopScope");
    assertThat(shop).isTrue();
  }

  // ==========================================================================
  // 基线词表：按租户落库，且不是硬编码数据源
  // ==========================================================================

  @Test
  void 基线词表按租户幂等落库且第二次不再落() {
    var first = asWriter(() -> reasons.tree(new AftersalesReasonTreeQuery(null, null, null)));
    assertThat(first.baselineSeededOnThisRead()).as("该租户第一次读应当真的落了新行").isTrue();
    assertThat(first.baselineVocabularySeeded()).isTrue();
    assertThat(first.nodes()).hasSize(7);

    var second = asWriter(() -> reasons.tree(new AftersalesReasonTreeQuery(null, null, null)));
    assertThat(second.baselineSeededOnThisRead())
        .as("第二次读必须是一次 no-op —— 否则每刷新一次页面就多七行")
        .isFalse();
    assertThat(second.nodes()).hasSize(7);

    // 七类逐字对齐蓝图原文，且都是根（depth 1）并归了类。
    assertThat(first.nodes()).allSatisfy(n -> assertThat(n.depth()).isEqualTo(1));
    assertThat(first.nodes().stream().map(AftersalesReasonTreeNode::categoryName).toList())
        .containsExactly(
            "质量问题", "描述不符", "尺寸不符", "物流损坏", "买家原因", "误购", "欺诈退货");
    assertThat(first.nodes()).allSatisfy(n -> assertThat(n.rootKind()).isNotNull());
    assertThat(first.nodes()).allSatisfy(n -> assertThat(n.parentId()).isNull());
  }

  @Test
  void 落库之后基线词表就是普通的租户行_能改名能排序能停用() throws Exception {
    var quality = node("ROOT.QUALITY_DEFECT");
    var renamed =
        asWriter(
            () ->
                reasons.updateCategory(
                    quality.id(),
                    new AftersalesReasonCategoryUpdate(
                        "产品质量问题", null, 15, null, "改过名的根", false),
                    user));
    assertThat(renamed.categoryName()).isEqualTo("产品质量问题");
    assertThat(renamed.sortOrder()).isEqualTo(15);

    var disabled =
        asWriter(
            () ->
                reasons.updateCategory(
                    quality.id(),
                    new AftersalesReasonCategoryUpdate(null, "DISABLED", null, null, null, false),
                    user));
    assertThat(disabled.categoryStatus()).isEqualTo("DISABLED");
    assertThat(disabled.categoryStatusLabel()).isEqualTo("停用");
    assertThat(disabled.assignable()).isFalse();
    assertThat(disabled.notAssignableReason()).contains("停用");

    // ⚠️ 编码与 rootKind 不可改：它们是报表与 6 采购的对齐锚点。
    //   root_kind 没进更新入参，category_code 同理 —— 这里验证「改不掉」而不是「没测」。
    http.perform(
                patch("/trade/aftersales/reasons/categories/" + quality.id())
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    // ⚠️ 试着改编码（不可改列）。
                    //   本服务开了 fail-on-unknown-properties，所以请求体里出现
                    //   categoryCode 会被 Jackson **整个拒掉**（400），而不是被静默忽略。
                    //   这正是想要的行为：把不可改的字段**摆到台面上报错**，
                    //   比「收下了但没生效」诚实得多 —— 后者会让调用方以为改了。
                    .content("{\"categoryCode\":\"ROOT.RENAMED\",\"categoryName\":\"改过名\"}"))
            .andExpect(status().isBadRequest());

    // 只带可改字段时才正常生效。
    var httpResponse =
        http.perform(
                patch("/trade/aftersales/reasons/categories/" + quality.id())
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"categoryName\":\"改过名\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var body = json.readTree(httpResponse.getContentAsString());
    assertThat(body.get("categoryCode").asText())
        .as("编码是不可改列，任何情况下都不会变")
        .isEqualTo("ROOT.QUALITY_DEFECT");
    assertThat(body.get("categoryName").asText()).isEqualTo("改过名");
  }

  // ==========================================================================
  // 分类树：层级、路径、可归因性
  // ==========================================================================

  @Test
  void 子分类挂在根下_路径与深度由数据库递归算出来() {
    var quality = node("ROOT.QUALITY_DEFECT");
    var appearance =
        asWriter(
            () ->
                reasons.createCategory(
                    new AftersalesReasonCategoryCreate(
                        quality.id(),
                        "QUALITY_DEFECT.APPEARANCE",
                        "外观缺陷",
                        null,
                        10,
                        "划痕 / 掉漆 / 破损"),
                    user));
    var functional =
        asWriter(
            () ->
                reasons.createCategory(
                    new AftersalesReasonCategoryCreate(
                        appearance.id(), "QUALITY_DEFECT.APPEARANCE.SCRATCH", "划痕", null, 0, null),
                    user));

    assertThat(appearance.depth()).isEqualTo(2);
    assertThat(functional.depth()).isEqualTo(3);
    assertThat(functional.codePath())
        .isEqualTo("ROOT.QUALITY_DEFECT/QUALITY_DEFECT.APPEARANCE/QUALITY_DEFECT.APPEARANCE.SCRATCH");
    assertThat(functional.namePath()).isEqualTo("质量问题 / 外观缺陷 / 划痕");
    // ⚠️ 子节点不许有 root_kind —— 否则同一批数据会在两个口径里被各计一次。
    assertThat(functional.rootKind()).isNull();
    // ⚠️ childCount 必须在**子节点建好之后**再读：appearance 是刚创建就被 findNode
    //   读回来的，那时它还没有子节点。存冗余计数就必然与事实漂移，所以这里现读现断言。
    assertThat(baseline().get("QUALITY_DEFECT.APPEARANCE").childCount()).isEqualTo(1);
    assertThat(baseline().get("ROOT.QUALITY_DEFECT").childCount()).isEqualTo(1);
  }

  @Test
  void 停用父节点后整支子分类都不可被新归因选中() {
    var quality = node("ROOT.QUALITY_DEFECT");
    var appearance =
        asWriter(
            () ->
                reasons.createCategory(
                    new AftersalesReasonCategoryCreate(
                        quality.id(), "QD_APPEARANCE", "外观缺陷", null, 0, null),
                    user));

    assertThat(appearance.assignable()).isTrue();

    asWriter(
        () ->
            reasons.updateCategory(
                quality.id(),
                new AftersalesReasonCategoryUpdate(null, "DISABLED", null, null, null, false),
                user));

    // 重新读树：子节点本身还是 ACTIVE，但**祖先**停用了 ⇒ 整支不可选。
    var after = baseline().get("QD_APPEARANCE");
    assertThat(after.categoryStatus()).isEqualTo("ACTIVE");
    assertThat(after.assignable())
        .as("祖先停用后子节点必须不可选，否则停用一个口径形同虚设")
        .isFalse();
    assertThat(after.notAssignableReason()).contains("上级");
  }

  @Test
  void 树最多三层_第四层被拒绝() {
    var q = node("ROOT.QUALITY_DEFECT");
    var l2 =
        asWriter(
            () ->
                reasons.createCategory(
                    new AftersalesReasonCategoryCreate(q.id(), "L2", "二层", null, 0, null), user));
    var l3 =
        asWriter(
            () ->
                reasons.createCategory(
                    new AftersalesReasonCategoryCreate(l2.id(), "L3", "三层", null, 0, null), user));
    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.createCategory(
                            new AftersalesReasonCategoryCreate(
                                l3.id(), "L4", "四层", null, 0, null),
                            user)))
        .hasMessageContaining("父节点已在第 3 层");
  }

  @Test
  void 根节点必须归类_子节点不许冒充根() {
    var q = node("ROOT.QUALITY_DEFECT");
    // 建根不给 rootKind ⇒ 报表无法确定它该汇总到哪一类。
    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.createCategory(
                            new AftersalesReasonCategoryCreate(
                                null, "CUSTOM", "自定义", null, 0, null),
                            user)))
        .hasMessageContaining("必须给出 rootKind");

    // 建子节点给 rootKind ⇒ 同一批数据会在两个口径里被各计一次。
    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.createCategory(
                            new AftersalesReasonCategoryCreate(
                                q.id(), "SUB", "子", "FRAUD_RETURN", 0, null),
                            user)))
        .hasMessageContaining("不能给 rootKind");

    // 非法 rootKind 直接 400，不静默回落到某个默认值。
    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.createCategory(
                            new AftersalesReasonCategoryCreate(
                                null, "CUSTOM2", "自定义", "NOT_A_KIND", 0, null),
                            user)))
        .hasMessageContaining("根口径只能是");
  }

  @Test
  void 分类编码租户内唯一且格式受限() {
    // ⚠️ 先让基线词表落库：不落的话 ROOT.QUALITY_DEFECT 这个编码根本不存在，
    //   下面那条「重复编码」自然冲突不了，断言会变成一条永远成立的空断言。
    assertThat(baseline()).containsKey("ROOT.QUALITY_DEFECT");
    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.createCategory(
                            new AftersalesReasonCategoryCreate(
                                null, "ROOT.QUALITY_DEFECT", "重复", "FRAUD_RETURN", 0, null),
                            user)))
        .hasMessageContaining("已存在");

    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.createCategory(
                            new AftersalesReasonCategoryCreate(
                                null, "有中文的编码", "x", "FRAUD_RETURN", 0, null),
                            user)))
        .hasMessageContaining("分类编码只能包含");
  }

  @Test
  void 分类不允许成环() {
    var q = node("ROOT.QUALITY_DEFECT");
    var child =
        asWriter(
            () ->
                reasons.createCategory(
                    new AftersalesReasonCategoryCreate(q.id(), "CHILD", "子", null, 0, null),
                    user));
    // 把父节点挂到它自己的子节点下面。
    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.updateCategory(
                            q.id(),
                            new AftersalesReasonCategoryUpdate(null, null, null, child.id(), null, false),
                            user)))
        .hasMessageContaining("不允许成环");
  }

  @Test
  void 被引用过的分类不能删_只能停用() throws Exception {
    UUID caseId = seedCase("SO-DEL");
    var q = node("ROOT.QUALITY_DEFECT");
    asWriter(
        () ->
            reasons.attribute(
                new AftersalesReasonAttributionCreate(
                    caseId, "外观问题", items(q.id(), true, "1.000000")),
                user));

    assertThatThrownBy(() -> asWriterVoid(() -> reasons.deleteCategory(q.id())))
        .hasMessageContaining("已被 1 条归因引用，不能删除");

    // 有子分类也不能删。
    var other = node("ROOT.SIZE_MISMATCH");
    asWriter(
        () ->
            reasons.createCategory(
                new AftersalesReasonCategoryCreate(other.id(), "SUB", "子", null, 0, null), user));
    assertThatThrownBy(() -> asWriterVoid(() -> reasons.deleteCategory(other.id())))
        .hasMessageContaining("子分类，不能删除");
  }

  // ==========================================================================
  // 加粗句：必须强制选择分类而不是留自由文本
  // ==========================================================================

  @Test
  void 强制选择分类_零个原因被拒_而且报错要说清为什么() throws Exception {
    UUID caseId = seedCase("SO-EMPTY");
    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.attribute(
                            new AftersalesReasonAttributionCreate(caseId, "只是一段话", List.of()),
                            user)))
        .hasMessageContaining("必须至少选择一个原因分类")
        .hasMessageContaining("退货率分析失真");

    // items 为 null 同样被拒（请求体里根本没给 items）。
    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.attribute(
                            new AftersalesReasonAttributionCreate(caseId, "只是一段话", null),
                            user)))
        .hasMessageContaining("必须至少选择一个原因分类");

    // 一条都没写进去。
    assertThat(asWriter(() -> reasons.attribution(caseId)).attributed()).isFalse();
  }

  @Test
  void 强制选择分类_主因必须恰好一个() throws Exception {
    UUID caseId = seedCase("SO-PRIMARY");
    var q = node("ROOT.QUALITY_DEFECT");
    var size = node("ROOT.SIZE_MISMATCH");

    // 零个主因
    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.attribute(
                            new AftersalesReasonAttributionCreate(
                                caseId,
                                "",
                                List.of(
                                    new AftersalesReasonAttributionItem(
                                        q.id(), false, new BigDecimal("0.600000")),
                                    new AftersalesReasonAttributionItem(
                                        size.id(), false, new BigDecimal("0.400000")))),
                            user)))
        .hasMessageContaining("有 0 个");

    // 两个主因
    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.attribute(
                            new AftersalesReasonAttributionCreate(
                                caseId,
                                "",
                                List.of(
                                    new AftersalesReasonAttributionItem(
                                        q.id(), true, new BigDecimal("0.600000")),
                                    new AftersalesReasonAttributionItem(
                                        size.id(), true, new BigDecimal("0.400000")))),
                            user)))
        .hasMessageContaining("有 2 个");
  }

  @Test
  void 强制选择分类_权重必填且落在开区间内() throws Exception {
    UUID caseId = seedCase("SO-WEIGHT");
    var q = node("ROOT.QUALITY_DEFECT");

    // ⚠️ 权重 null **不给默认值 1** —— 默认 1 恰好是最没意义的那个值。
    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.attribute(
                            new AftersalesReasonAttributionCreate(
                                caseId, "", List.of(new AftersalesReasonAttributionItem(q.id(), true, null))),
                            user)))
        .hasMessageContaining("必须显式给权重");

    for (String bad : List.of("0", "-0.5", "1.000001")) {
      assertThatThrownBy(
              () ->
                  asWriter(
                      () ->
                          reasons.attribute(
                              new AftersalesReasonAttributionCreate(
                                  caseId, "", items(q.id(), true, bad)),
                              user)))
          .as("权重 " + bad + " 必须被拒")
          .hasMessageContaining("(0, 1]");
    }
    // 恰好 1 是合法的（只有一个原因时权重就是 1）。
    assertThat(asWriter(() -> reasons.attribute(
            new AftersalesReasonAttributionCreate(caseId, "", items(q.id(), true, "1.000000")), user))
            .current().primaryCount()).isEqualTo(1);
  }

  @Test
  void 同一个原因分类不能被选两次() throws Exception {
    UUID caseId = seedCase("SO-DUP");
    var q = node("ROOT.QUALITY_DEFECT");
    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.attribute(
                            new AftersalesReasonAttributionCreate(
                                caseId,
                                "",
                                List.of(
                                    new AftersalesReasonAttributionItem(
                                        q.id(), true, new BigDecimal("0.600000")),
                                    new AftersalesReasonAttributionItem(
                                        q.id(), false, new BigDecimal("0.400000")))),
                            user)))
        .hasMessageContaining("被选了多次");
  }

  @Test
  void 单次归因最多十个原因() throws Exception {
    UUID caseId = seedCase("SO-MAX");
    var q = node("ROOT.QUALITY_DEFECT");
    var many = new java.util.ArrayList<AftersalesReasonAttributionItem>();
    for (int i = 0; i < 11; i++) {
      many.add(new AftersalesReasonAttributionItem(q.id(), i == 0, new BigDecimal("0.090000")));
    }
    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.attribute(
                            new AftersalesReasonAttributionCreate(caseId, "", many), user)))
        .hasMessageContaining("最多 10 个原因");
  }

  /**
   * <b>绕过服务层直接写 SQL</b>：DB 的提交期 DEFERRABLE 约束触发器必须挡住。
   *
   * <p>这是本模块最要紧的一条测试。只测服务层的话，哪天有人把端点换成内联 SQL、
   * 或者有人直接连库导数据，这两种写入就会静默成功 —— 而「没有归因原因的单」
   * 恰恰是加粗句点名要防的「退货率分析失真」的来源。
   */
  @Test
  void 绕过后端直写零成员归因_提交期约束触发器挡住() throws Exception {
    UUID caseId = seedCase("SO-RAW0");

    // 对照组：同样的语句形状 + 一个成员 ⇒ 提交成功。
    // 没有这条对照，下面的失败就可能只是「这条 SQL 本身写坏了」。
    UUID okSet = UUID.randomUUID();
    inTenant(
        () -> {
          sql.execute(
              "insert into aftersales_reason_attribution_set(id,tenant_id,case_id,"
                  + " attribution_note,attributed_by,attributed_at,created_at)"
                  + " values(?,?::uuid,?::uuid,'',?::uuid,?::timestamptz,?::timestamptz)",
              okSet,
              a,
              caseId,
              user,
              T0,
              T0);
          sql.execute(
              "insert into aftersales_reason_attribution_item(id,tenant_id,set_id,case_id,"
                  + " category_id,is_primary,weight,created_at)"
                  + " values(?,?::uuid,?::uuid,?::uuid,?::uuid,true,1.000000,?::timestamptz)",
              UUID.randomUUID(),
              a,
              okSet,
              caseId,
              node("ROOT.QUALITY_DEFECT").id(),
              T0);
          return null;
        });
    assertThat(asWriter(() -> reasons.attribution(caseId)).attributed()).isTrue();

    // 真正的被测对象：零成员 ⇒ 提交时必须失败。
    // ⚠️ 用**另一张还没归因过的**单：上面那张已经有当前版本，
    //   部分唯一索引会先把「第二个当前版本」拒掉，于是这条测试会因为
    //   一个无关的约束（而不是因为「零成员」）而通过 —— 断言就失去了意义。
    UUID freshCase = seedCase("SO-RAW0B");
    UUID emptySet = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                inTenant(
                    () -> {
                      sql.execute(
                          "insert into aftersales_reason_attribution_set(id,tenant_id,case_id,"
                              + " attribution_note,attributed_by,attributed_at,created_at)"
                              + " values(?,?::uuid,?::uuid,?,?::uuid,?::timestamptz,?::timestamptz)",
                          emptySet,
                          a,
                          freshCase,
                          "我只写了一段自由文本",
                          user,
                          T0,
                          T0);
                      return null;
                    }))
        .as("零成员的归因集合在提交时必须被拒绝 —— 这正是「不允许只写自由文本」的物理含义")
        .hasMessageContaining("必须至少选择一个原因分类");

    // 失败的那次是回滚的：集合没落库，当前生效的仍是对照组那一版。
    assertThat(asWriter(() -> reasons.attribution(caseId)).current().items()).hasSize(1);
  }

  @Test
  void 绕过后端直写两个主因_提交期约束触发器挡住() throws Exception {
    UUID caseId = seedCase("SO-RAW2");
    var q = node("ROOT.QUALITY_DEFECT");
    var size = node("ROOT.SIZE_MISMATCH");
    UUID set = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                inTenant(
                    () -> {
                      sql.execute(
                          "insert into aftersales_reason_attribution_set(id,tenant_id,case_id,"
                              + " attribution_note,attributed_by,attributed_at,created_at)"
                              + " values(?,?::uuid,?::uuid,'',?::uuid,?::timestamptz,?::timestamptz)",
                          set,
                          a,
                          caseId,
                          user,
                          T0,
                          T0);
                      for (UUID cat : List.of(q.id(), size.id())) {
                        sql.execute(
                            "insert into aftersales_reason_attribution_item(id,tenant_id,set_id,"
                                + " case_id,category_id,is_primary,weight,created_at)"
                                + " values(?,?::uuid,?::uuid,?::uuid,?::uuid,true,0.500000,?::timestamptz)",
                            UUID.randomUUID(),
                            a,
                            set,
                            caseId,
                            cat,
                            T0);
                      }
                      return null;
                    }))
        .as("两个主因的归因在提交时必须被拒绝 —— 多选没有主次，归因分析无意义")
        .hasMessageContaining("有且仅有一个主因");

    assertThat(asWriter(() -> reasons.attribution(caseId)).attributed()).isFalse();
  }

  // ==========================================================================
  // 归因：多选、替换、停用后的可归因性
  // ==========================================================================

  @Test
  void 多选归因带主次与权重_主因排第一() throws Exception {
    UUID caseId = seedCase("SO-MULTI");
    var q = node("ROOT.QUALITY_DEFECT");
    var size = node("ROOT.SIZE_MISMATCH");
    var logistics = node("ROOT.LOGISTICS_DAMAGE");

    var view =
        asWriter(
            () ->
                reasons.attribute(
                    new AftersalesReasonAttributionCreate(
                        caseId,
                        "外箱压扁，内件划伤",
                        List.of(
                            new AftersalesReasonAttributionItem(
                                size.id(), false, new BigDecimal("0.200000")),
                            new AftersalesReasonAttributionItem(
                                q.id(), true, new BigDecimal("0.600000")),
                            new AftersalesReasonAttributionItem(
                                logistics.id(), false, new BigDecimal("0.200000")))),
                    user));

    var current = view.current();
    assertThat(view.attributed()).isTrue();
    assertThat(current.itemCount()).isEqualTo(3);
    assertThat(current.primaryCount()).isEqualTo(1);
    // 主因排第一，其余按权重倒序、编码定序 ⇒ 界面照这个顺序渲染即可。
    assertThat(current.items()).extracting(AftersalesReasonAttributionItemRow::isPrimary)
        .containsExactly(true, false, false);
    assertThat(current.items().get(0).categoryCode()).isEqualTo("ROOT.QUALITY_DEFECT");
    // ⚠️ 两个次因权重相同（都是 0.2）⇒ 顺序由**编码**定死：
    //   ROOT.LOGISTICS_DAMAGE < ROOT.SIZE_MISMATCH。
    //   这正是「报表用统一口径」要求排序键唯一确定的落点 ——
    //   只按权重排的话，并列的两行顺序会随执行计划变。
    assertThat(current.items().get(1).categoryCode()).isEqualTo("ROOT.LOGISTICS_DAMAGE");
    assertThat(current.items().get(2).categoryCode()).isEqualTo("ROOT.SIZE_MISMATCH");
    // 权重是相对值，**不要求**合计为 1 —— 合计报出来让界面去提示。
    assertThat(current.weightSum()).isEqualByComparingTo("1.000000");
    // 自由文本原样保留，但它明确不是归因本身。
    assertThat(current.note()).isEqualTo("外箱压扁，内件划伤");
    assertThat(view.noteSemantics()).contains("不是归因本身");
  }

  @Test
  void 改归因是追加一版_旧版留痕且报表只算当前版() throws Exception {
    UUID caseId = seedCase("SO-REVISE");
    var q = node("ROOT.QUALITY_DEFECT");
    var fraud = node("ROOT.FRAUD_RETURN");

    asWriter(
        () ->
            reasons.attribute(
                new AftersalesReasonAttributionCreate(
                    caseId, "初判：质量问题", items(q.id(), true, "1.000000")),
                user));
    asWriter(
        () ->
            reasons.attribute(
                new AftersalesReasonAttributionCreate(
                    caseId, "复判：欺诈退货", items(fraud.id(), true, "1.000000")),
                user));

    var view = asWriter(() -> reasons.attribution(caseId));
    assertThat(view.versionCount()).as("两版都要留痕 —— 纠纷举证需要知道当初为什么这么判").isEqualTo(2);
    assertThat(view.current().note()).isEqualTo("复判：欺诈退货");
    assertThat(view.current().current()).isTrue();

    // 报表**只统计当前生效版本**：这张单算在欺诈退货下，不算在质量问题下。
    var report = asWriter(() -> reasons.report());
    assertThat(report.attributedCaseCount()).isEqualTo(1);
    assertThat(report.rootTotals().stream()
            .filter(t -> t.rootKind().equals("QUALITY_DEFECT"))
            .findFirst().orElseThrow()
            .attributionCaseCount())
        .as("改过一次归因的单不能被历史版本重复计入")
        .isZero();
    assertThat(report.rootTotals().stream()
            .filter(t -> t.rootKind().equals("FRAUD_RETURN"))
            .findFirst().orElseThrow()
            .attributionCaseCount())
        .isEqualTo(1);
  }

  @Test
  void 停用的分类不能再被新归因选中_但历史归因仍指向它() throws Exception {
    UUID caseId = seedCase("SO-DISABLED");
    var q = node("ROOT.QUALITY_DEFECT");
    asWriter(
        () ->
            reasons.attribute(
                new AftersalesReasonAttributionCreate(caseId, "", items(q.id(), true, "1.000000")),
                user));

    asWriter(
        () ->
            reasons.updateCategory(
                q.id(),
                new AftersalesReasonCategoryUpdate(null, "DISABLED", null, null, null, false),
                user));

    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.attribute(
                            new AftersalesReasonAttributionCreate(
                                caseId, "", items(q.id(), true, "1.000000")),
                            user)))
        .hasMessageContaining("已停用");

    // 历史归因仍然指向它 —— 停用是口径变更，不是抹掉历史。
    var view = asWriter(() -> reasons.attribution(caseId));
    assertThat(view.current().items().get(0).categoryCode()).isEqualTo("ROOT.QUALITY_DEFECT");
    assertThat(asWriter(() -> reasons.report()).rows().stream()
            .filter(r -> r.categoryCode().equals("ROOT.QUALITY_DEFECT"))
            .findFirst().orElseThrow()
            .primaryCaseCount())
        .isEqualTo(1);
  }

  @Test
  void 归因只写时刻用加八点偏移的夹具_断言写回的是同一绝对时刻() throws Exception {
    UUID caseId = seedCase("SO-TZ");
    var q = node("ROOT.QUALITY_DEFECT");
    var setId =
        asWriter(
            () -> {
              var v =
                  reasons.attribute(
                      new AftersalesReasonAttributionCreate(
                          caseId, "", items(q.id(), true, "1.000000")),
                      user);
              return v.current().setId();
            });
    // ⚠️ 必须用**另一张还没有当前归因的**单：部分唯一索引
    //   UNIQUE(tenant_id,case_id) WHERE superseded_at IS NULL 会先一步把重复版本拒掉，
    //   于是根本走不到「写回时刻」这一步，测试会因为一个完全无关的约束而失败。
    UUID tzCase = seedCase("SO-TZ2");
    // 直写库读回来断言到绝对时刻，而不是只断言「没抛异常」。
    // ⚠️ Timestamp.valueOf(LocalDateTime) 的实现会静默偏 −28800s，
    //    而只断言不抛异常的测试照绿 —— 这就是这条断言存在的理由。
    var plusEight = OffsetDateTime.parse("2026-03-02T10:15:30+08:00");
    UUID setToStamp = UUID.randomUUID();
    inTenant(
        () -> {
          sql.execute(
              "insert into aftersales_reason_attribution_set(id,tenant_id,case_id,"
                  + " attribution_note,attributed_by,attributed_at,created_at)"
                  + " values(?,?::uuid,?::uuid,'',?::uuid,?::timestamptz,?::timestamptz)",
              setToStamp,
              a,
              tzCase,
              user,
              plusEight,
              plusEight);
          sql.execute(
              "insert into aftersales_reason_attribution_item(id,tenant_id,set_id,case_id,"
                  + " category_id,is_primary,weight,created_at)"
                  + " values(?,?::uuid,?::uuid,?::uuid,?::uuid,true,1.000000,?::timestamptz)",
              UUID.randomUUID(),
              a,
              setToStamp,
              tzCase,
              q.id(),
              plusEight);
          return null;
        });
    var readBack =
        inTenant(
            () ->
                sql.fetchOne(
                        "select attributed_at from aftersales_reason_attribution_set"
                            + " where tenant_id = ?::uuid and id = ?::uuid",
                        a,
                        setToStamp)
                    .get("attributed_at", OffsetDateTime.class));
    assertThat(readBack.toInstant()).isEqualTo(plusEight.toInstant());
    assertThat(readBack.getOffset())
        .as("timestamptz 读回来带会话时区偏移，但**绝对时刻**必须一模一样")
        .isEqualTo(plusEight.getOffset());
    assertThat(setId).isNotNull();
  }

  // ==========================================================================
  // 平台原因码映射（trade 自有表）
  // ==========================================================================

  @Test
  void 平台原因码映射可维护_且同平台同码只能映射一个口径() {
    var q = node("ROOT.QUALITY_DEFECT");
    var size = node("ROOT.SIZE_MISMATCH");

    var row =
        asWriter(
            () ->
                reasons.createMapping(
                    new AftersalesReasonMappingCreate(
                        q.id(),
                        "AMAZON",
                        "PRODUCT_DEFECT",
                        "商品有缺陷",
                        null,
                        null,
                        OffsetDateTime.parse("2026-01-15T08:00:00Z"),
                        null),
                    user));
    assertThat(row.platformSource()).isEqualTo("AMAZON");
    assertThat(row.categoryCode()).isEqualTo("ROOT.QUALITY_DEFECT");
    assertThat(row.rootKindLabel()).isEqualTo("质量问题");
    // captured_at 是**采集时刻**，原样读回。
    assertThat(row.capturedAt().toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-01-15T08:00:00Z").toInstant());

    // 两边都映射会让报表把同一批退货重复计入两行。
    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.createMapping(
                            new AftersalesReasonMappingCreate(
                                size.id(), "AMAZON", "PRODUCT_DEFECT", null, null, null, null, null),
                            user)))
        .hasMessageContaining("已经映射过了");
  }

  @Test
  void 映射来源标成同步器写入时必须给出处() {
    var q = node("ROOT.QUALITY_DEFECT");
    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.createMapping(
                            new AftersalesReasonMappingCreate(
                                q.id(), "SHOPIFY", "DAMAGED", null, "MODULE_2_ENUM_MAPPING", null, null, null),
                            user)))
        .hasMessageContaining("必须给出 sourceReference");
  }

  @Test
  void 同步器写的映射不许删_只许停用_租户自维护的可以删() {
    var q = node("ROOT.QUALITY_DEFECT");
    var synced =
        asWriter(
            () ->
                reasons.createMapping(
                    new AftersalesReasonMappingCreate(
                        q.id(),
                        "EBAY",
                        "DAMAGED",
                        null,
                        "MODULE_2_ENUM_MAPPING",
                        "2.1.2.1 amazon.reason-codes v3",
                        null,
                        null),
                    user));
    var mine =
        asWriter(
            () ->
                reasons.createMapping(
                    new AftersalesReasonMappingCreate(
                        q.id(), "TIKTOK_SHOP", "QUALITY", null, null, null, null, null),
                    user));

    assertThatThrownBy(() -> asWriterVoid(() -> reasons.deleteMapping(synced.id())))
        .hasMessageContaining("不能删除")
        .hasMessageContaining("DISABLED");

    // 停用走更新端点。
    var disabled =
        asWriter(
            () ->
                reasons.updateMapping(
                    synced.id(),
                    new AftersalesReasonMappingCreate(
                        null, null, null, null, null, null, null, "DISABLED"),
                    user));
    assertThat(disabled.mappingStatus()).isEqualTo("DISABLED");

    asWriterVoid(() -> reasons.deleteMapping(mine.id()));
    assertThat(asWriter(() -> reasons.mappings(null, null, null)).rows())
        .extracting(AftersalesReasonMappingRow::id)
        .containsExactly(synced.id());
  }

  @Test
  void 映射的平台与码是匹配键不可改() {
    var q = node("ROOT.QUALITY_DEFECT");
    var row =
        asWriter(
            () ->
                reasons.createMapping(
                    new AftersalesReasonMappingCreate(
                        q.id(), "AMAZON", "PRODUCT_DEFECT", null, null, null, null, null),
                    user));
    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.updateMapping(
                            row.id(),
                            new AftersalesReasonMappingCreate(
                                q.id(), "EBAY", null, null, null, null, null, null),
                            user)))
        .hasMessageContaining("platformSource 是匹配键，不可修改");
    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.updateMapping(
                            row.id(),
                            new AftersalesReasonMappingCreate(
                                q.id(), null, "OTHER_CODE", null, null, null, null, null),
                            user)))
        .hasMessageContaining("platformReasonCode 是匹配键，不可修改");
  }

  @Test
  void 映射列表真筛选_且按平台与码定序() {
    var q = node("ROOT.QUALITY_DEFECT");
    var size = node("ROOT.SIZE_MISMATCH");
    asWriter(
        () ->
            reasons.createMapping(
                new AftersalesReasonMappingCreate(
                    q.id(), "AMAZON", "PRODUCT_DEFECT", null, null, null, null, null), user));
    asWriter(
        () ->
            reasons.createMapping(
                new AftersalesReasonMappingCreate(
                    size.id(), "AMAZON", "WRONG_SIZE", null, null, null, null, null), user));
    asWriter(
        () ->
            reasons.createMapping(
                new AftersalesReasonMappingCreate(
                    q.id(), "EBAY", "DAMAGED", null, null, null, null, null), user));

    assertThat(asWriter(() -> reasons.mappings("AMAZON", null, null)).rows())
        .extracting(AftersalesReasonMappingRow::platformReasonCode)
        .containsExactly("PRODUCT_DEFECT", "WRONG_SIZE");
    assertThat(asWriter(() -> reasons.mappings(null, q.id(), null)).rows()).hasSize(2);
    assertThat(asWriter(() -> reasons.mappings(null, null, "DISABLED")).rows()).isEmpty();
    assertThat(asWriter(() -> reasons.mappings("TIKTOK_SHOP", null, null)).rows()).isEmpty();
    assertThat(asWriter(() -> reasons.mappings(null, null, null)).ownershipNote())
        .as("界面必须能说清这张表是 trade 自己的、不是 channel 的")
        .contains("不是 channel 的 platform_enum_mapping");
  }

  @Test
  void 映射不能指向已停用的分类() {
    var q = node("ROOT.QUALITY_DEFECT");
    asWriter(
        () ->
            reasons.updateCategory(
                q.id(),
                new AftersalesReasonCategoryUpdate(null, "DISABLED", null, null, null, false),
                user));
    assertThatThrownBy(
            () ->
                asWriter(
                    () ->
                        reasons.createMapping(
                            new AftersalesReasonMappingCreate(
                                q.id(), "AMAZON", "PRODUCT_DEFECT", null, null, null, null, null),
                            user)))
        .hasMessageContaining("已停用");
  }

  // ==========================================================================
  // 报表：统一口径
  // ==========================================================================

  @Test
  void 报表七类零也出现_主因与次因分开计数_占比分母是生效归因单数() throws Exception {
    UUID c1 = seedCase("SO-R1");
    UUID c2 = seedCase("SO-R2");
    UUID c3 = seedCase("SO-R3");
    var q = node("ROOT.QUALITY_DEFECT");
    var size = node("ROOT.SIZE_MISMATCH");
    var buyer = node("ROOT.BUYER_REASON");

    // c1：质量问题（主）+ 尺寸不符（次）
    asWriter(
        () ->
            reasons.attribute(
                new AftersalesReasonAttributionCreate(
                    c1,
                    "",
                    List.of(
                        new AftersalesReasonAttributionItem(
                            q.id(), true, new BigDecimal("0.700000")),
                        new AftersalesReasonAttributionItem(
                            size.id(), false, new BigDecimal("0.300000")))),
                user));
    // c2：质量问题（主）
    asWriter(
        () ->
            reasons.attribute(
                new AftersalesReasonAttributionCreate(c2, "", items(q.id(), true, "1.000000")), user));
    // c3：买家原因（主）
    asWriter(
        () ->
            reasons.attribute(
                new AftersalesReasonAttributionCreate(c3, "", items(buyer.id(), true, "1.000000")),
                user));
    // c4：**故意不归因** —— 「还没做过归因」与「归因为空」是**两件不同的事**，
    //     前者在业务上合法，后者被 DB 禁止。报表必须把两者分开报出来。
    seedCase("SO-R4");

    var report = asWriter(() -> reasons.report());
    assertThat(report.attributedCaseCount()).isEqualTo(3);
    assertThat(report.unattributedCaseCount()).isEqualTo(1);
    assertThat(report.attributionItemCount()).isEqualTo(4);
    // c1 的权重合计 1.0（0.7 主因 + 0.3 次因），c2 与 c3 各 1.0 ⇒ 合计 3.000000
    assertThat(report.totalWeight()).isEqualByComparingTo("3.000000");

    var quality =
        report.rows().stream()
            .filter(r -> r.categoryCode().equals("ROOT.QUALITY_DEFECT"))
            .findFirst().orElseThrow();
    assertThat(quality.primaryCaseCount()).isEqualTo(2);
    assertThat(quality.secondaryCaseCount()).isZero();
    assertThat(quality.attributionCaseCount()).isEqualTo(2);
    // 2 / 3 = 66.67%
    assertThat(quality.caseSharePercent()).isEqualByComparingTo("66.67");

    var sizeRow =
        report.rows().stream()
            .filter(r -> r.categoryCode().equals("ROOT.SIZE_MISMATCH"))
            .findFirst().orElseThrow();
    assertThat(sizeRow.primaryCaseCount())
        .as("主因与次因必须分开计数，合成一个数会重复计入")
        .isZero();
    assertThat(sizeRow.secondaryCaseCount()).isEqualTo(1);
    assertThat(sizeRow.caseSharePercent()).isEqualByComparingTo("33.33");

    // 七类**全部出现**，零也出现 —— 少一个会被界面显示成空白，
    // 而空白会被读成「这个类没有数据」而不是「这个类真的是 0」。
    assertThat(report.rootTotals()).hasSize(7);
    assertThat(report.rootTotals().stream().map(AftersalesReasonRootTotal::rootKind).toList())
        .containsExactly(
            "QUALITY_DEFECT",
            "DESCRIPTION_MISMATCH",
            "SIZE_MISMATCH",
            "LOGISTICS_DAMAGE",
            "BUYER_REASON",
            "MISTAKE_BUY",
            "FRAUD_RETURN");
    assertThat(report.rootTotals().stream()
            .filter(t -> t.rootKind().equals("MISTAKE_BUY"))
            .findFirst().orElseThrow()
            .attributionCaseCount())
        .isZero();
    assertThat(report.rootTotals().stream()
            .filter(t -> t.rootKind().equals("BUYER_REASON"))
            .findFirst().orElseThrow()
            .attributionCaseCount())
        .isEqualTo(1);
    assertThat(report.semantics()).contains("superseded_at IS NULL").contains("主因");
    assertThat(report.downstreamNote()).contains("均未交付");
  }

  @Test
  void 报表把子分类汇总进所属根口径() throws Exception {
    UUID c1 = seedCase("SO-ROLLUP");
    var q = node("ROOT.QUALITY_DEFECT");
    var appearance =
        asWriter(
            () ->
                reasons.createCategory(
                    new AftersalesReasonCategoryCreate(
                        q.id(), "QD_APPEARANCE", "外观缺陷", null, 0, null), user));

    asWriter(
        () ->
            reasons.attribute(
                new AftersalesReasonAttributionCreate(
                    c1, "", items(appearance.id(), true, "1.000000")),
                user));

    var report = asWriter(() -> reasons.report());
    var quality =
        report.rootTotals().stream()
            .filter(t -> t.rootKind().equals("QUALITY_DEFECT"))
            .findFirst().orElseThrow();
    assertThat(quality.attributionCaseCount())
        .as("勾子分类的单也必须计入它的根口径 —— 七类是下游唯一对齐的锚点")
        .isEqualTo(1);
    assertThat(quality.primaryCaseCount()).isEqualTo(1);
  }

  @Test
  void 报表在完全没有归因数据时分母为零而不是抛异常() {
    var report = asWriter(() -> reasons.report());
    assertThat(report.attributedCaseCount()).isZero();
    assertThat(report.rows()).isNotEmpty();
    assertThat(report.rows()).allSatisfy(r -> assertThat(r.caseSharePercent()).isEqualByComparingTo("0.00"));
    assertThat(report.totalWeight()).isEqualByComparingTo("0.000000");
  }

  // ==========================================================================
  // HTTP 边界与权限
  // ==========================================================================

  @Test
  void 写端点真的要求写权限_只读角色改不动归因() throws Exception {
    UUID caseId = seedCase("SO-PERM");
    http.perform(get("/trade/aftersales/reasons/attributions")
            .header("Authorization", "Bearer tenant-readonly")
            .param("caseId", caseId.toString()))
        .andExpect(status().isOk());
    http.perform(post("/trade/aftersales/reasons/attributions")
            .header("Authorization", "Bearer tenant-readonly")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"caseId\":\"%s\",\"note\":\"x\",\"items\":[]}".formatted(caseId)))
        .andExpect(status().isForbidden());
  }

  @Test
  void 非法查询参数报400_而不是静默回落() throws Exception {
    http.perform(get("/trade/aftersales/reasons/tree")
            .header("Authorization", "Bearer tenant-a")
            .param("rootKind", "NOT_A_KIND"))
        .andExpect(status().isBadRequest());
    http.perform(get("/trade/aftersales/reasons/mappings")
            .header("Authorization", "Bearer tenant-a")
            .param("platformSource", "MADE_UP_PLATFORM"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 未认证请求拿不到任何数据() throws Exception {
    http.perform(get("/trade/aftersales/reasons/tree")).andExpect(status().isUnauthorized());
    http.perform(get("/trade/aftersales/reasons/reports")).andExpect(status().isUnauthorized());
  }

  /**
   * 回归：<b>「只切启用/停用」的映射更新在 HTTP 边界上必须真的能成。</b>
   *
   * <p>⚠️ 这条测试是被一个<b>契约与运行时对不上</b>的缺陷逼出来的：
   * 契约把 {@code AftersalesReasonMappingCreate.categoryId} 声明成必填 {@code string}，
   * 而更新端点的语义是「传了才改」（服务层 {@code coalesce(?::uuid, category_id)} 与
   * {@code if (categoryId() != null)} 都按可空处理）。前端做状态切换时**不传内部分类**，
   * 被必填的 {@code string} 逼得只能发 {@code ""} —— 靠 Jackson 的空串→null 强制转换
   * 才能蒙对。
   *
   * <p>为什么这值得钉住：{@code ""} 能蒙对是<b>实现细节而不是契约</b> ——
   * 全局 {@code CoercionConfig} 一改、或者换掉 {@code FAIL_ON_NULL_FOR_PRIMITIVES} 类配置，
   * 这个按钮就会突然开始 400，而 CI 全绿。契约标 {@code nullable} 之后，
   * 前端发的是**显式的 null**（「不改内部分类」），不再依赖任何隐式转换。
   *
   * <p>所以这里只断 ①：显式 null 的 PATCH 真的 200 且状态翻过来了。
   * <b>不断言 ②（空串）</b>：那是把「当前 Jackson 版本的强制转换行为」写死成契约，
   * 反而会阻止将来正确的配置收紧。
   */
  @Test
  void 只切启用停用的映射更新在http边界上能成_内部分类可以显式传null表示不改() throws Exception {
    UUID caseId = seedCase("SO-MAPPING-TOGGLE");
    var q = node("ROOT.QUALITY_DEFECT");
    var row =
        asWriter(
            () ->
                reasons.createMapping(
                    new AftersalesReasonMappingCreate(
                        q.id(), "AMAZON", "TOGGLE_PROBE", null, null, null, null, null),
                    user));

    // ① 前端真实发的那个形状：只给 mappingStatus，内部分类显式传 null。
    //    ⚠️ 其余字段刻意全部省略，证明「只切状态」不需要回填匹配键。
    http.perform(patch("/trade/aftersales/reasons/mappings/{id}", row.id().toString())
            .header("Authorization", "Bearer tenant-a")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"categoryId\":null,\"mappingStatus\":\"DISABLED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mappingStatus").value("DISABLED"));
    assertThat(asWriter(() -> reasons.mappings(null, null, null)).activeCount())
        .as("停用之后生效映射数应当少一条")
        .isEqualTo(0);

    // ② 切回来同样只给状态：证明「可空」是双向可用的，
    //    而不是只有停用这一个方向能走。
    http.perform(patch("/trade/aftersales/reasons/mappings/{id}", row.id().toString())
            .header("Authorization", "Bearer tenant-a")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"categoryId\":null,\"mappingStatus\":\"ACTIVE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mappingStatus").value("ACTIVE"))
        // 内部分类没被动过 —— 「传 null 表示不改」必须真的什么都不改。
        .andExpect(jsonPath("$.categoryId").value(q.id().toString()));

    asWriterVoid(() -> reasons.deleteMapping(row.id()));
  }

  @Test
  void 迁移确实建出了四张表_而且都强制rls_分类表与映射表是活表_归因两表追加式() {
    for (String table :
        List.of(
            "aftersales_reason_category",
            "aftersales_reason_attribution_set",
            "aftersales_reason_attribution_item",
            "aftersales_reason_platform_mapping")) {
      var row =
          inTenant(() -> sql.fetchOne("select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table));
      assertThat(row).as("V158 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
      assertThat(
              inTenant(
                      () ->
                          sql.fetchOne(
                                  "select has_table_privilege('trade_app',?,'SELECT')", table)
                              .get(0, Boolean.class)))
          .as("trade_app 必须真的能读 " + table + "，否则「看不到」可能只是谁都读不了")
          .isTrue();
    }

    // 分类表是**活表**（可改），但只授回白名单里的列。
    // ⚠️ 收回表级 UPDATE 再逐列授回之后，has_table_privilege(…,'UPDATE')
    //   会返回 **false** —— 这恰恰是收紧生效的证据（照抄 V156 第 463-473 行的同一结论：
    //   表级 UPDATE 蕴含每一列，只 REVOKE 列是空操作）。
    //   所以「分类能不能改」要问**列**，不是问表。
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select has_column_privilege('trade_app','aftersales_reason_category','category_name','UPDATE')")
                        .get(0, Boolean.class)))
        .as("分类要能改名")
        .isTrue();
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select has_column_privilege('trade_app','aftersales_reason_category','sort_order','UPDATE')")
                        .get(0, Boolean.class)))
        .as("分类要能改排序")
        .isTrue();
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select has_column_privilege('trade_app','aftersales_reason_category','category_status','UPDATE')")
                        .get(0, Boolean.class)))
        .as("分类要能停用 —— 停用是口径变更的入口")
        .isTrue();
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select has_table_privilege('trade_app','aftersales_reason_category','UPDATE')")
                        .get(0, Boolean.class)))
        .as("表级 UPDATE 必须已被收回，否则上面那几条列级断言就毫无意义")
        .isFalse();
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select has_column_privilege('trade_app','aftersales_reason_category','depth','UPDATE')")
                        .get(0, Boolean.class)))
        .as("depth 由触发器维护，运行角色写不出一个假深度去绕开三层上限")
        .isFalse();
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select has_column_privilege('trade_app','aftersales_reason_category','root_kind','UPDATE')")
                        .get(0, Boolean.class)))
        .as("root_kind 是报表的汇总锚点，不可改")
        .isFalse();
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select has_column_privilege('trade_app','aftersales_reason_category','category_code','UPDATE')")
                        .get(0, Boolean.class)))
        .as("编码是 11.5.2 与 6 采购的对齐键，不可改")
        .isFalse();

    // 归因两表**追加式**：改了不改写、补一版不删旧版 —— 纠纷举证的原材料。
    for (String table : List.of("aftersales_reason_attribution_set", "aftersales_reason_attribution_item")) {
      assertThat(
              inTenant(
                      () ->
                          sql.fetchOne("select has_table_privilege('trade_app',?,'UPDATE')", table)
                              .get(0, Boolean.class)))
          .as(table + " 的表级 UPDATE 必须被收回")
          .isFalse();
      assertThat(
              inTenant(
                      () ->
                          sql.fetchOne("select has_table_privilege('trade_app',?,'DELETE')", table)
                              .get(0, Boolean.class)))
          .as(table + " 的 DELETE 必须被收回 —— 删掉一版归因等于毁掉举证材料")
          .isFalse();
    }
    // 集合上唯一需要的写操作是 superseded_at（把当前生效的那一版标记为失效）。
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select has_column_privilege('trade_app','aftersales_reason_attribution_set','superseded_at','UPDATE')")
                        .get(0, Boolean.class)))
        .isTrue();
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select has_column_privilege('trade_app','aftersales_reason_attribution_set','attributed_by','UPDATE')")
                        .get(0, Boolean.class)))
        .as("「谁在什么时候定的归因」不能被事后抹掉")
        .isFalse();

    // 映射表的采集时刻与来源不可改：改了就是伪造采集时刻 / 冒充同步器。
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select has_column_privilege('trade_app','aftersales_reason_platform_mapping','captured_at','UPDATE')")
                        .get(0, Boolean.class)))
        .isFalse();
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select has_column_privilege('trade_app','aftersales_reason_platform_mapping','mapping_source','UPDATE')")
                        .get(0, Boolean.class)))
        .isFalse();
  }

  @Test
  void 本迁移没有alter任何既有表_只对v158建的表有ddl依赖() {
    // V158 只建了四张新表 + 若干函数/触发器，不 ALTER 别人的表。
    // 触发器函数里对既有表的引用必须**只有 SELECT**（aftersales_case 是 11.1.1.1 的写入所有者）。
    for (var name :
        List.of(
            "guard_aftersales_attribution_category",
            "guard_aftersales_reason_platform_mapping",
            "guard_aftersales_reason_category_delete")) {
      var body =
          inTenant(
              () ->
                  sql.fetchOne(
                          "select prosrc from pg_proc where proname = ?", name).get("prosrc", String.class));
      assertThat(body).as(name + " 必须存在").isNotNull();
      assertThat(body.toLowerCase())
          .as(name + " 不得对 aftersales_case 做任何写操作 —— 那是别的车道的写入所有者")
          .doesNotContain("update aftersales_case")
          .doesNotContain("insert into aftersales_case")
          .doesNotContain("delete from aftersales_case");
    }
    // 本迁移不建任何指向既有表的外键（V158 的表只 FK 到自己）。
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select count(*) from pg_constraint"
                                + " where contype = 'f'"
                                + "   and conrelid in (select oid from pg_class where relname like 'aftersales_reason%')"
                                + "   and confrelid in (select oid from pg_class where relname = 'aftersales_case')")
                        .get(0, Long.class)))
        .as("刻意不给 aftersales_case 建外键 —— 那会让本迁移对别的车道的表产生 DDL 依赖")
        .isZero();
  }
}
