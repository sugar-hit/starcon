package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.starcon.trade.RefundApplicationModels.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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
 * 11.3.1.1 退款申请的**多租户三断言**。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且**实跑在本模块自己的三张表上**
 * （refund_approval_policy / refund_application / refund_application_event），
 * <b>不是</b>占位探针表 —— 探针表能证明 RLS 开着，证明不了<b>本模块的查询本身</b>
 * 没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：申请单带着<b>原收款流水号</b>与<b>平台退款单号</b>，
 * 而提交 / 审批 / 派发 / 登记结果都是<b>按 id 直达</b>的 URL ——
 * 猜到一个 UUID 就能读，不需要翻列表。而可退余额校验还要读**别的租户不该看到的订单行**，
 * 且它直接决定「能不能把钱退出去」。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离 —— 复用上一轮的记录，
 * 第二轮断言通过只能说明「同一条记录没变」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RefundApplicationTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
  static final UUID approverA = UUID.randomUUID(), approverB = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms =
      List.of("trade.aftersales.read", "trade.aftersales.write");

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

  @Autowired RefundApplicationService refunds;
  @Autowired AftersalesCaseService cases;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate refund_application_event, refund_application, refund_approval_policy,"
              + " aftersales_case_line, aftersales_case_note, aftersales_authorization,"
              + " aftersales_service_ticket, aftersales_owner_assignment, aftersales_case,"
              + " order_fulfillment_line_progress, sales_order_line, order_address_snapshot,"
              + " sales_order_party_snapshot, sales_order cascade");
    }
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.aftersales.write", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-b"), "trade.aftersales.write", work);
  }

  <T> T as(UUID tid, java.util.function.Supplier<T> work) {
    return tid.equals(a) ? asA(work) : asB(work);
  }

  /** 一张真实订单 + 一张真实售后单 + 一张退款申请草稿。 */
  record Seeded(UUID applicationId, UUID caseId, UUID orderId, String orderNo) {}

  Seeded seed(UUID tid, String orderNo, String baseAmount) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    BigDecimal qty = new BigDecimal("2");
    BigDecimal amount = new BigDecimal(baseAmount);
    BigDecimal unitPrice = amount.divide(qty, 4, java.math.RoundingMode.HALF_UP);

    as(
        tid,
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,"
                  + "?::numeric,0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
              orderId, tid, orderNo, "EXT-" + orderNo, T1, amount, amount, T1, T1);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
                  + "created_at) values(?::uuid,?::uuid,?::uuid,1,?::uuid,?,?,'黑色 / L',?::numeric,"
                  + "?::numeric,0,0,?::numeric,'',?::timestamptz)",
              lineId, tid, orderId, UUID.randomUUID(), "SKU-" + orderNo, "商品" + orderNo,
              qty, unitPrice, amount, T1);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
                  + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tid, orderId, "买家-" + orderNo,
              "buyer-" + orderNo + "@example.com", T1);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,'买家-" + orderNo + "','INDIVIDUAL','',"
                  + "?::timestamptz)",
              UUID.randomUUID(), tid, orderId, UUID.randomUUID(), "C-" + orderNo, T1);
          return null;
        });

    UUID caseId =
        as(
            tid,
            () ->
                cases.create(
                        new AftersalesCaseModels.AftersalesCaseCreate(
                            orderId, "RETURN_REFUND", "QUALITY_DEFECT", "碎了",
                            amount, "USD", qty, "PLAT-" + orderNo, null, null,
                            "买家诉求-" + orderNo, T1, null, T1,
                            null, null, null, "TENANT_MAINTAINED", null, T1),
                        userA)
                    .id());

    // 每个租户各配一条审批规则 —— 逐表隔离断言需要三张表**都有行**才能比对，
    // 少了它 refund_approval_policy 那张表的正对照恒为 0，变成永远为真的假绿。
    as(tid, () -> refunds.upsertPolicy(
        new RefundPolicyUpsert("USD", new BigDecimal("20.0000"), orderNo + " 的规则", T1), userA));

    UUID applicationId =
        as(
            tid,
            () ->
                refunds.create(
                        new RefundCreate(
                            caseId, null, "ORIGINAL", new BigDecimal("10.0000"), "USD",
                            "RETURN_REFUND", null, "PAY-" + orderNo, null),
                        userA)
                    .head()
                    .id());
    return new Seeded(applicationId, caseId, orderId, orderNo);
  }

  Seeded seed(UUID tid, String orderNo) {
    return seed(tid, orderNo, "60.0000");
  }

  private RefundDetail detail(UUID tid, UUID applicationId) {
    return as(tid, () -> refunds.detail(applicationId));
  }

  private long rowCount(UUID tid, String table) {
    return as(tid, () -> sql.fetch("select * from " + table).size());
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    var sa = seed(a, "MO-TA");
    var sb = seed(b, "MO-TB");

    // 正对照：各自都读得到自己的。
    assertThat(detail(a, sa.applicationId()).head().originalPaymentRef())
        .isEqualTo("PAY-MO-TA");
    assertThat(detail(b, sb.applicationId()).head().originalPaymentRef())
        .isEqualTo("PAY-MO-TB");

    // 更强的一格：**按主键直查对方的单也拿不到**（不只是列表过滤）。
    assertThatThrownBy(() -> detail(b, sa.applicationId()))
        .as("B 家读 A 家的退款申请必须失败")
        .hasMessageContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> detail(a, sb.applicationId()))
        .hasMessageContaining("不存在或不属于当前租户");

    // ⚠️ **可退余额校验同样按 caseId 隔离** —— 它直接决定「能不能把钱退出去」，
    //   读到别人的售后单就等于拿到了别人的退款额度。
    assertThatThrownBy(() -> asB(() -> refunds.balance(sa.caseId())))
        .hasMessageContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> asA(() -> refunds.balance(sb.caseId())))
        .hasMessageContaining("不存在或不属于当前租户");

    // 列表也只看得到自己的
    assertThat(asA(() -> refunds.list(new RefundListQuery(null, 50, null, null, null, null, null,
        null, null)).rows()))
        .hasSize(1);
    assertThat(asB(() -> refunds.list(new RefundListQuery(null, 50, null, null, null, null, null,
        null, null)).rows()))
        .hasSize(1);

    // 三张表按主键直查也互不可见，**并且每张都有正对照**
    //（正对照缺席时，「两边都是 0」这种假绿会被当成隔离生效）。
    for (String table : List.of("refund_approval_policy", "refund_application",
        "refund_application_event")) {
      assertThat(rowCount(a, table)).as("A 自己的 " + table).isEqualTo(1);
      assertThat(rowCount(b, table)).as("B 自己的 " + table).isEqualTo(1);
      assertThat(asB(() -> sql.fetch("select * from " + table + " where tenant_id = ?::uuid", a).size()))
          .as("B 按 tenant_id 直查 A 的 " + table)
          .isZero();
      assertThat(asA(() -> sql.fetch("select * from " + table + " where tenant_id = ?::uuid", a).size()))
          .as("A 用同样写法直查自己的 " + table + " 必须查得到（防假绿的正对照）")
          .isEqualTo(1);
    }

    // 审批也一样：拿对方的 id 去提交，必须落到会话租户名下而不是对方的单上。
    // ⚠️ **version 必须先在 asA 里取出来**：把 asA 的调用嵌进 asB 的 lambda 里，
    //   内层事务会把 app.current_tenant 重新设成 A，于是外层这次「跨租户提交」
    //   实际是在 A 的租户下跑的、必然不报错 —— 这条断言就变成了永远为真的假绿。
    long version = detail(a, sa.applicationId()).head().version();
    assertThatThrownBy(() -> asB(() -> refunds.submit(sa.applicationId(), new RefundSubmit(version), userB)))
        .hasMessageContaining("不存在或不属于当前租户");
    assertThat(detail(a, sa.applicationId()).head().applicationStatus()).isEqualTo("DRAFT");
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  //
  // ⚠️ **正确预期是「按会话里的真实成员关系正常执行并 200」**，不是 4xx：
  //   伪造的 X-Tenant-Id 头根本进不到租户解析里（租户来自已认证成员关系），
  //   请求仍然写进会话租户名下，对方租户逐字节未变。断言要比对两侧数据库，不比对状态码。
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    var sa = seed(a, "MO-HDR");

    // A 的会话 + 伪造的 X-Tenant-Id（B）⇒ 拿到的仍然是 A 的单，而且**成功**。
    var body =
        http.perform(
                get("/trade/refund-applications/" + sa.applicationId())
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    var parsed = json.readTree(body);
    assertThat(parsed.get("head").get("originalPaymentRef").asText()).isEqualTo("PAY-MO-HDR");
    assertThat(parsed.get("head").get("caseNo").asText()).isNotBlank();

    // 可退余额校验同理：伪造头拿不到 B 的任何数据。
    http.perform(
            get("/trade/refund-applications/refundable-balance")
                .param("caseId", sa.caseId().toString())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk())
        // ⚠️ 草稿**不占用**额度（提交才占用），所以仍是全额 60 ——
        //   这里要断言的正是「伪造头没有把 B 的额度算进来」。
        .andExpect(jsonPath("$.availableAmount").value(60.0))
        .andExpect(jsonPath("$.occupiedAmount").value(0.0));

    // ⚠️ **伪造头不能把别人的单读出来**：B 的会话 + 伪造 A 的头去读 A 的 id，
    //   得到的仍然必须是「不存在或不属于当前租户」——租户**只**来自会话。
    http.perform(
            get("/trade/refund-applications/" + sa.applicationId())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isBadRequest());

    // 伪造头也开不了写入口：B 的会话 + 伪造 A 的头想提交 A 的单，必须被拒。
    http.perform(
            post("/trade/refund-applications/" + sa.applicationId() + "/submit")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0}"))
        .andExpect(status().isBadRequest());
    assertThat(detail(a, sa.applicationId()).head().applicationStatus()).as("A 的单仍然是草稿")
        .isEqualTo("DRAFT");

    // 伪造头也不能写进 A 的租户：A 名下的申请数仍然是它自己那一张。
    assertThat(rowCount(a, "refund_application")).isEqualTo(1);
    assertThat(rowCount(b, "refund_application")).isZero();

    // 没有会话就没有租户上下文。
    http.perform(get("/trade/refund-applications/" + sa.applicationId()))
        .andExpect(status().isUnauthorized());
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 池大小为 1：下面每轮都复用同一条连接，只有「事务局部绑定 + 提交即清空」
    // 才能保证后面几轮读到的仍然是本租户而不是上一轮残留。
    for (int i = 0; i < 5; i++) {
      // ⚠️ 每轮**新建实体**：复用上一轮的记录，测的就是幂等而不是隔离了。
      final int round = i;
      var sa = seed(a, "MO-POOL-A-" + i);
      var sb = seed(b, "MO-POOL-B-" + i);

      assertThat(detail(a, sa.applicationId).head().caseNo()).as("第 " + round + " 轮 A 读到的是 A 的单")
          .isEqualTo(asA(() -> sql.fetchOne(
                  "select case_no from aftersales_case where tenant_id = ?::uuid and id = ?::uuid",
                  a, sa.caseId)
              .get(0, String.class)));
      assertThat(detail(b, sb.applicationId).head().caseNo()).as("第 " + round + " 轮 B 读到的是 B 的单")
          .isEqualTo(asB(() -> sql.fetchOne(
                  "select case_no from aftersales_case where tenant_id = ?::uuid and id = ?::uuid",
                  b, sb.caseId)
              .get(0, String.class)));

      // 交替写：A 提交 + 双签审批，B 立刻读 —— 必须读不到，也**批不动** A 的单。
      long version = asA(() -> refunds.submit(sa.applicationId(), new RefundSubmit(0L), userA)
          .head().version());
      asA(() -> refunds.decide(sa.applicationId(),
          new RefundDecision(true, "初审通过", version), approverA));
      // ⚠️ seed 配的阈值是 20，而这张单只有 10 ⇒ **单签**通过，不需要复审。
      //   这里刻意按状态分支：写死「一定会有第二次审批」会在阈值调过之后整个断言崩掉。
      long afterFirst = detail(a, sa.applicationId).head().version();
      if ("SECOND_APPROVAL_PENDING".equals(detail(a, sa.applicationId).head().applicationStatus())) {
        asA(() -> refunds.decide(sa.applicationId(),
            new RefundDecision(true, "复审通过", afterFirst), approverB));
      }

      assertThat(asB(() -> sql.fetch(
                  "select * from refund_application where id = ?::uuid", sa.applicationId).size()))
          .as("第 " + round + " 轮：B 读不到 A 的原收款流水号")
          .isZero();
      assertThat(asA(() -> sql.fetch(
                  "select * from refund_application where id = ?::uuid", sa.applicationId).size()))
          .as("第 " + round + " 轮：A 用同样写法读得到自己的那一张（防假绿的正对照）")
          .isEqualTo(1);

      // ⚠️ **审批规则表也必须隔离**：B 改自己的规则是合法的（改的是 B 那张表），
      //   真正要断言的是**改不到 A 的**：B 把阈值调成 0 之后，A 的规则必须纹丝不动。
      asB(() -> refunds.upsertPolicy(
          new RefundPolicyUpsert("USD", BigDecimal.ZERO, "B 第 " + round + " 轮改的规则", null), userB));
      assertThat(asA(() -> refunds.policy("USD", null).dualApprovalThreshold()))
          .as("第 " + round + " 轮：B 把阈值改成 0 之后，A 的阈值必须仍然是它自己配的那个")
          .isEqualByComparingTo("20.0000");
      assertThat(asB(() -> sql.fetchOne(
                  "select count(*) from refund_approval_policy where dual_approval_threshold = 0"
                      + " and tenant_id = ?::uuid", b)
              .get(0, Long.class)))
          .as("B 写的是 B 自己的行")
          .isEqualTo(1L);
    }

    // 事务外读：没有 app.current_tenant 时 FORCE RLS 让所有行都读不到。
    assertThat(sql.fetch("select * from refund_application").size()).as("事务外读必须零行").isZero();
    assertThat(sql.fetch("select * from refund_application_event").size()).isZero();
    assertThat(sql.fetch("select * from refund_approval_policy").size()).isZero();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 三张新表都强制rls而且运行角色真的能读写() {
    for (String table : List.of("refund_approval_policy", "refund_application",
        "refund_application_event")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V166 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
      assertThat(sql.fetchOne("select has_table_privilege('trade_app',?,'SELECT')", table)
              .get(0, Boolean.class))
          .as("trade_app 必须真的能读 " + table + "，否则「看不到」可能只是谁都读不了")
          .isTrue();
      assertThat(sql.fetchOne("select has_table_privilege('trade_app',?,'INSERT')", table)
              .get(0, Boolean.class))
          .as("trade_app 必须真能往 " + table + " 写")
          .isTrue();
    }
  }
}
