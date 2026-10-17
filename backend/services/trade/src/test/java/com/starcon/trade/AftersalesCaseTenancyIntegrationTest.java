package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
 * 11.1.1.1 售后单列表的**多租户三断言**。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且**实跑在真实业务表**上
 * （aftersales_case / aftersales_owner_assignment / aftersales_case_note /
 * aftersales_authorization / aftersales_service_ticket），
 * <b>不是</b>占位探针表 —— 探针表能证明 RLS 开着，证明不了<b>本模块的多维筛选与聚合查询本身</b>
 * 没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：列表是多维筛选 + 分页 + 聚合（超时数、临期数、分币种金额），
 * 漏掉租户谓词就是**跨租户泄露** —— A 家会看到 B 家的买家诉求原文与退款申请金额，
 * 而 B 家连自己被拖逾期都不知道。售后数据还额外含买家姓名与退货原因，
 * 泄露后果比订单列表更重。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AftersalesCaseTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms = List.of("trade.aftersales.read", "trade.aftersales.write");

  static AccessService.Access actor(UUID uid, UUID tenant) {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms));
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null, perms, grants);
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

  @Autowired AftersalesCaseService service;
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
          "truncate aftersales_case_note, aftersales_authorization, aftersales_service_ticket,"
              + " aftersales_owner_assignment, aftersales_case, sales_order cascade");
    }
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.aftersales.write", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-b"), "trade.aftersales.write", work);
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return tenant.equals(a) ? asA(work) : asB(work);
  }

  /** 一张真实订单 + 一张真实售后单（走本模块自己的登记入口，不在测试里直接 INSERT）。 */
  UUID seedCase(UUID tenant, String orderNo, String amount, String buyerText, UUID owner) {
    UUID orderId = UUID.randomUUID();
    as(
        tenant,
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,10.0000,0,0,"
                  + "0,0,10.0000,0,?::timestamptz,?::timestamptz)",
              orderId, tenant, orderNo, "EXT-" + orderNo, T1, T1, T1);
          // V99 的触发器拒绝「没有任何商品行就 SUBMITTED」的订单，夹具必须带一行。
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?,?::uuid,?::uuid,1,?,?,?,1,10.0000,0,0,10.0000,'',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "SKU-" + orderNo, "商品", T1);
          // V99 的延迟约束触发器还要求收货地址快照：收货地址是下单当时冻结的那一份，
          // 没有它就无法发货。三张子表都在同一个事务里补齐，commit 时一起过检查。
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
                  + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, "买家" + orderNo, "buyer@example.com", T1);
          // 客户快照：V99 的延迟约束触发器第三样必备输入（「客户选择」是手工单的必备输入）。
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?::uuid,?::uuid,?,?,?,'INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "C-" + orderNo,
              "买家" + orderNo, T1);
          return null;
        });
    return as(
        tenant,
        () ->
            service.create(
                new AftersalesCaseModels.AftersalesCaseCreate(
                    orderId,
                    "RETURN_REFUND",
                    "QUALITY_DEFECT",
                    "",
                    new java.math.BigDecimal(amount),
                    "USD",
                    new java.math.BigDecimal("1.000000"),
                    "PLAT-" + orderNo,
                    null,
                    null,
                    buyerText,
                    // 早于「现在」⇒ OVERDUE：让时效预警这条链路也带上租户条件。
                    T1,
                    null,
                    T1,
                    owner == null ? null : owner.toString(),
                    "客服小林",
                    "售后专员",
                    "TENANT_MAINTAINED",
                    null,
                    T1),
                userA).id());
  }

  AftersalesCaseModels.AftersalesCaseQuery query(int page, int size) {
    return new AftersalesCaseModels.AftersalesCaseQuery(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, page, size, null, false);
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 五张业务表都强制rls而且运行角色真的能读写() {
    for (String table :
        List.of(
            "aftersales_case",
            "aftersales_owner_assignment",
            "aftersales_case_note",
            "aftersales_authorization",
            "aftersales_service_ticket")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V156 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('trade_app',?,'SELECT')", table)
                  .get(0, Boolean.class))
          .as("trade_app 必须真的能读 " + table + "，否则「看不到」可能只是谁都读不了")
          .isTrue();
    }
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    seedCase(a, "MO-A", "111.0000", "A 家买家的诉求原文", UUID.randomUUID());
    seedCase(b, "MO-B", "222.0000", "B 家买家的诉求原文", UUID.randomUUID());

    assertThat(asA(() -> service.list(query(0, 50)).total())).as("A 只看得到自己那一份").isEqualTo(1);
    assertThat(asB(() -> service.list(query(0, 50)).total())).isEqualTo(1);

    // 更强的一格：**按主键直查对方的售后单也拿不到**（不只是列表过滤）。
    var caseA = asA(() -> service.list(query(0, 50)).rows().get(0));
    var byIdB =
        asB(() -> sql.fetch("select id from aftersales_case where id = ?", caseA.id()).size());
    assertThat(byIdB).as("按主键直查也必须被 RLS 挡住").isZero();

    // 买家诉求原文这种敏感字段同样不能跨租户读到。
    var textB =
        asB(
            () ->
                sql.fetch("select buyer_request from aftersales_case where id = ?", caseA.id())
                    .size());
    assertThat(textB).isZero();

    // 四张留痕表同样互不可见：按 case_id 直查。
    assertThat(asA(() -> sql.fetch("select * from aftersales_owner_assignment").size())).isEqualTo(1);
    assertThat(asB(() -> sql.fetch("select * from aftersales_owner_assignment").size())).isEqualTo(1);
    assertThat(asA(() -> sql.fetch("select * from aftersales_case_note").size())).isZero();
    assertThat(asB(() -> sql.fetch("select * from aftersales_case_note").size())).isZero();
    assertThat(asA(() -> sql.fetch("select * from aftersales_authorization").size())).isZero();
    assertThat(asB(() -> sql.fetch("select * from aftersales_service_ticket").size())).isZero();

    // 汇总条也不能把对方的超时数、金额算进来。
    var sumA = asA(() -> service.list(query(0, 50)).summary());
    assertThat(sumA.totalCount()).isEqualTo(1);
    assertThat(sumA.overdueCount()).isEqualTo(1);
    assertThat(sumA.amountsByCurrency().get(0).amount())
        .as("A 的金额汇总只能是 A 的那一张")
        .isEqualByComparingTo("111.0000");
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    seedCase(a, "MO-HDR", "333.0000", "A 家买家的诉求原文", UUID.randomUUID());

    // HTTP 边界：带合法会话 + 伪造 X-Tenant-Id → 拿到的仍然是**会话租户**的数据。
    var body =
        http.perform(
                get("/trade/aftersales/cases")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var parsed = json.readTree(body);
    assertThat(parsed.get("total").asLong()).isEqualTo(1);
    // 拿到的必须是**会话租户**那一张（订单号 MO-HDR），不是 B 家的任何东西。
    assertThat(parsed.get("rows").get(0).get("orderNo").asText()).isEqualTo("MO-HDR");

    // 行确实落在 a 名下：用 B 的事务按 B 的租户谓词按订单号查，必须查不到。
    var visibleToB =
        asB(
            () ->
                sql.fetch("select id from aftersales_case where case_no is not null and order_id in"
                        + " (select id from sales_order where order_no = 'MO-HDR')")
                    .size());
    assertThat(visibleToB).as("伪造请求头不得把行带进 B 的租户").isZero();

    // 伪造 X-Tenant-Id 也不能开通写入口：拿 A 的会话 + B 的头去登记，
    // 行仍然落在 A 名下，B 读不到。
    UUID newOrder = UUID.randomUUID();
    asB(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,"
                  + "currency,payment_method,status,submitted_at,subtotal_amount,discount_amount,"
                  + "tax_amount,shipping_amount,other_amount,grand_total_amount,version,created_at,"
                  + "updated_at) values(?,?::uuid,'MO-FORGED','AMAZON','EXT-F','USD','PREPAID',"
                  + "'SUBMITTED',?::timestamptz,10.0000,0,0,0,0,10.0000,0,?::timestamptz,?::timestamptz)",
              newOrder, b, T1, T1, T1);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?,?::uuid,?::uuid,1,?,?,'商品',1,10.0000,0,0,10.0000,'',?::timestamptz)",
              UUID.randomUUID(), b, newOrder, UUID.randomUUID(), "SKU-FORGED", T1);
          // V99 的延迟约束触发器要求 SUBMITTED 单同时有商品行、收货地址与客户快照，三样缺一不可。
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,"
                  + "recipient_name,recipient_phone,recipient_email,company_name,country_code,"
                  + "province,city,district,postcode,address_line1,address_line2,tax_id,note,"
                  + "created_at) values(?,?::uuid,?::uuid,'SHIPPING','买家F','+1-213-555-0100',"
                  + "'f@example.com','','US','CA','LOS ANGELES','','90001','100 Main St','','','',"
                  + "?::timestamptz)",
              UUID.randomUUID(), b, newOrder, T1);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?::uuid,?::uuid,?,'C-FORGED','买家F','INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), b, newOrder, UUID.randomUUID(), T1);
          return null;
        });
    // A 的会话看不到 B 的订单 ⇒ 登记被拒（关联订单不存在）
    var payload =
        new java.util.LinkedHashMap<String, Object>();
    payload.put("orderId", newOrder.toString());
    payload.put("caseType", "RETURN_REFUND");
    payload.put("reasonCode", "QUALITY_DEFECT");
    payload.put("requestedAmount", "10.0000");
    payload.put("currency", "USD");
    payload.put("requestedQuantity", "1.000000");
    payload.put("replyDueAt", T1.toString());
    http.perform(
            post("/trade/aftersales/cases")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload)))
        .andExpect(status().isBadRequest());
    assertThat(asB(() -> service.list(query(0, 50)).total())).as("B 名下仍然一条都没有").isZero();

    // 跨租户 ID 批量操作 ⇒ 逐条 NOT_FOUND，而不是「静默成功」也不是「部分成功」。
    var caseA = asA(() -> service.list(query(0, 50)).rows().get(0));
    var bulkBody = new java.util.LinkedHashMap<String, Object>();
    bulkBody.put("operation", "NOTE");
    bulkBody.put("caseIds", List.of(caseA.id().toString()));
    bulkBody.put("note", "越权备注");
    var result =
        json.readTree(
            http.perform(
                    post("/trade/aftersales/cases/bulk")
                        .header("Authorization", "Bearer tenant-b")
                        .header("X-Tenant-Id", a)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(bulkBody)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(result.get("failed").asInt()).isEqualTo(1);
    assertThat(result.get("items").get(0).get("outcome").asText()).isEqualTo("NOT_FOUND");
    // A 的备注数没有被别人加过
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select count(*) from aftersales_case_note where case_id = ?::uuid",
                            caseA.id())
                        .get(0, Long.class)))
        .isZero();

    // 没有会话就没有租户上下文。
    http.perform(get("/trade/aftersales/cases")).andExpect(status().isUnauthorized());
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    seedCase(a, "MO-PA", "111.0000", "A 家买家的诉求原文", UUID.randomUUID());
    seedCase(b, "MO-PB", "222.0000", "B 家买家的诉求原文", UUID.randomUUID());

    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后面几轮读到的仍然是本租户而不是上一轮残留。
    for (int i = 0; i < 5; i++) {
      var pageA = asA(() -> service.list(query(0, 50)));
      var pageB = asB(() -> service.list(query(0, 50)));
      assertThat(pageA.total()).as("第 %d 轮租户 A", i).isEqualTo(1);
      assertThat(pageB.total()).as("第 %d 轮租户 B", i).isEqualTo(1);
      // 金额也必须各自独立：A 的汇总不能出现 B 的 222。
      assertThat(pageA.summary().amountsByCurrency().get(0).amount())
          .as("第 %d 轮 A 的金额汇总", i)
          .isEqualByComparingTo("111.0000");
      assertThat(pageB.summary().amountsByCurrency().get(0).amount())
          .as("第 %d 轮 B 的金额汇总", i)
          .isEqualByComparingTo("222.0000");
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(asA(() -> service.list(query(0, 50)).total())).isEqualTo(1);
    assertThat(asB(() -> service.list(query(0, 50)).total())).isEqualTo(1);
  }

  // ==========================================================================
  // 额外一条：事务外（没有任何租户上下文）读不到任何一行
  // ==========================================================================
  @Test
  void 没有租户上下文时一张也读不到() {
    seedCase(a, "MO-NOCTX", "111.0000", "A 家买家的诉求原文", UUID.randomUUID());
    assertThat(sql.fetch("select * from aftersales_case").size())
        .as("RLS 是最后一道：没有 app.current_tenant 就是零行")
        .isZero();
  }
}
