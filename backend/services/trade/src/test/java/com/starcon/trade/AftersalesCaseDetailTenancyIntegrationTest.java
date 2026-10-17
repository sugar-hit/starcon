package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
 * 11.1.1.2 售后单详情的**多租户三断言**。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且**实跑在真实业务表上**
 * （aftersales_case_line / aftersales_message / aftersales_attachment 与 V156 的四张留痕表），
 * <b>不是</b>占位探针表 —— 探针表能证明 RLS 开着，证明不了<b>本模块的聚合查询本身</b>
 * 没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：详情页是**按 id 直达**的 URL —— 猜到一个 UUID 就能看，
 * 不需要翻列表。而一次聚合会同时返回订单号、买家姓名、收货地址、买家诉求原文、责任人、
 * 授权金额与沟通正文。漏掉租户谓词就是跨租户泄露，且泄露面比列表页大得多。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AftersalesCaseDetailTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
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

  @Autowired AftersalesCaseService cases;
  @Autowired AftersalesCaseDetailService details;
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
          "truncate aftersales_case_line, aftersales_message, aftersales_attachment,"
              + " aftersales_case_note, aftersales_authorization, aftersales_service_ticket,"
              + " aftersales_owner_assignment, aftersales_case, sales_order cascade");
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

  /** 一张真实订单（带商品行 / 地址 / 客户快照）+ 一张真实售后单 + 一条沟通记录 + 一份凭证。 */
  record Seeded(UUID caseId, UUID orderId, UUID lineId) {}

  Seeded seedCase(UUID tid, String orderNo, String buyerText) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    as(
        tid,
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,10.0000,0,0,"
                  + "0,0,10.0000,0,?::timestamptz,?::timestamptz)",
              orderId, tid, orderNo, "EXT-" + orderNo, T1, T1, T1);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?,?::uuid,?::uuid,1,?,?,?,1,10.0000,0,0,10.0000,'',?::timestamptz)",
              lineId, tid, orderId, UUID.randomUUID(), "SKU-" + orderNo, "商品", T1);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
                  + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tid, orderId, "买家" + orderNo, "buyer@example.com", T1);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?::uuid,?::uuid,?,?,?,'INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), tid, orderId, UUID.randomUUID(), "C-" + orderNo,
              "买家" + orderNo, T1);
          return null;
        });
    UUID caseId =
        as(
            tid,
            () ->
                cases.create(
                        new AftersalesCaseModels.AftersalesCaseCreate(
                            orderId, "RETURN_REFUND", "QUALITY_DEFECT", "",
                            new BigDecimal("10.0000"), "USD", new BigDecimal("1.000000"),
                            "PLAT-" + orderNo, null, null, buyerText, T1, null, T1,
                            null, null, null, null, null, null),
                        userA)
                    .id());
    as(
        tid,
        () -> {
          details.addMessage(
              caseId,
              new AftersalesCaseDetailModels.AftersalesMessageCreate(
                  "BUYER_TO_US", "PLATFORM", buyerText + "（原话）", null, null, null, T1, T1));
          details.addAttachment(
              caseId,
              new AftersalesCaseDetailModels.AftersalesAttachmentCreate(
                  "BUYER_EVIDENCE", "evi-" + orderNo + ".jpg", "image/jpeg",
                  "obj://" + orderNo + "/evi.jpg", 512L, "凭证", "BUYER", null, null, T1),
              userA);
          return null;
        });
    return new Seeded(caseId, orderId, lineId);
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    var ca = seedCase(a, "MO-TA", "A 家买家的诉求原文");
    var cb = seedCase(b, "MO-TB", "B 家买家的诉求原文");

    // 各看各的：两个 id 都能读
    assertThat(asA(() -> details.detail(ca.caseId()).header().caseNo())).isNotBlank();
    assertThat(asB(() -> details.detail(cb.caseId()).header().caseNo())).isNotBlank();

    // 更强的一格：**按主键直查对方的单也拿不到**（不只是列表过滤）。
    // 详情页是按 id 直达的 URL，拿不到才是真的拿不到。
    assertThatThrownBy(() -> asB(() -> details.detail(ca.caseId())))
        .as("B 家读 A 家的售后单必须失败")
        .hasMessageContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> asA(() -> details.detail(cb.caseId())))
        .as("A 家读 B 家的售后单必须失败")
        .hasMessageContaining("不存在或不属于当前租户");

    // 时间线同样按主键隔离
    assertThatThrownBy(() -> asB(() -> details.timelinePage(
            ca.caseId(), new AftersalesCaseDetailModels.AftersalesTimelineQuery(0, 50))))
        .hasMessageContaining("不存在或不属于当前租户");

    // 三张新表按 case_id 直查也互不可见
    assertThat(asA(() -> sql.fetch("select * from aftersales_message").size())).isEqualTo(1);
    assertThat(asB(() -> sql.fetch("select * from aftersales_message").size())).isEqualTo(1);
    assertThat(asA(() -> sql.fetch("select * from aftersales_attachment").size())).isEqualTo(1);
    assertThat(asB(() -> sql.fetch("select * from aftersales_attachment").size())).isEqualTo(1);
    assertThat(asA(() -> sql.fetch("select * from aftersales_case_line").size())).isZero();
    assertThat(asB(() -> sql.fetch("select * from aftersales_case_line").size())).isZero();
    assertThat(asB(() -> sql.fetch(
                "select * from aftersales_message where case_id = ?::uuid", ca.caseId()).size()))
        .as("按主键直查对方的沟通记录同样必须被挡住")
        .isZero();
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    var ca = seedCase(a, "MO-HDR", "A 家买家的诉求原文");

    // HTTP 边界：带合法会话 + 伪造 X-Tenant-Id → 拿到的仍然是**会话租户**的数据。
    var body =
        http.perform(
                get("/trade/aftersales/cases/" + ca.caseId())
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    var parsed = json.readTree(body);
    // 拿到的必须是 A 家的单（订单号 MO-HDR），不是 B 家的任何东西
    assertThat(parsed.get("order").get("orderNo").asText()).isEqualTo("MO-HDR");
    assertThat(parsed.get("messages").get(0).get("messageBody").asText())
        .contains("A 家买家的诉求原文");

    // 伪造的 X-Tenant-Id 不能把 B 的单带出来：B 的会话 + 伪造的头读 A 的 id 仍然 400
    http.perform(
            get("/trade/aftersales/cases/" + ca.caseId())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isBadRequest());

    // 伪造头也开不了写入口：B 的会话 + 伪造头想给 A 的单加备注，必须被拒
    var note = new LinkedHashMap<String, Object>();
    note.put("noteBody", "越权备注");
    http.perform(
            post("/trade/aftersales/cases/" + ca.caseId() + "/notes")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(note)))
        .andExpect(status().isBadRequest());
    // A 的备注数没有被别人加过
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) from aftersales_case_note where case_id = ?::uuid", ca.caseId())
        .get(0, Long.class)))
        .isZero();

    // 伪造头也开不了消息写入：B 的会话 + 伪造头给 A 的单登记沟通记录，必须被拒
    var msg = new LinkedHashMap<String, Object>();
    msg.put("direction", "US_TO_BUYER");
    msg.put("channel", "EMAIL");
    msg.put("messageBody", "越权消息");
    http.perform(
            post("/trade/aftersales/cases/" + ca.caseId() + "/messages")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(msg)))
        .andExpect(status().isBadRequest());
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) from aftersales_message where case_id = ?::uuid", ca.caseId())
        .get(0, Long.class)))
        .as("A 名下的沟通记录仍然是它自己那一条")
        .isEqualTo(1);

    // 没有会话就没有租户上下文
    http.perform(get("/trade/aftersales/cases/" + ca.caseId()))
        .andExpect(status().isUnauthorized());
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    var ca = seedCase(a, "MO-PA", "A 家买家的诉求原文");
    var cb = seedCase(b, "MO-PB", "B 家买家的诉求原文");

    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后面几轮读到的仍然是本租户而不是上一轮残留。
    for (int i = 0; i < 5; i++) {
      var orderNoA = asA(() -> details.detail(ca.caseId()).order().orderNo());
      var bodyA = asA(() -> details.detail(ca.caseId()).messages().get(0).messageBody());
      assertThat(orderNoA).as("第 " + i + " 轮 A 读到的订单号").isEqualTo("MO-PA");
      assertThat(bodyA).as("第 " + i + " 轮 A 读到的沟通正文").contains("A 家");

      var orderNoB = asB(() -> details.detail(cb.caseId()).order().orderNo());
      var bodyB = asB(() -> details.detail(cb.caseId()).messages().get(0).messageBody());
      assertThat(orderNoB).as("第 " + i + " 轮 B 读到的订单号").isEqualTo("MO-PB");
      assertThat(bodyB).as("第 " + i + " 轮 B 读到的沟通正文").contains("B 家");

      // 交替写：写完立刻用另一个租户读，读到的必须是「读不到」
      asA(() -> details.addNote(
          ca.caseId(), new AftersalesCaseDetailModels.AftersalesNoteCreate("A 的备注", null)));
      asB(() -> details.addNote(
          cb.caseId(), new AftersalesCaseDetailModels.AftersalesNoteCreate("B 的备注", null)));
      assertThat(asB(() -> sql.fetchOne(
              "select count(*) from aftersales_case_note where case_id = ?::uuid", ca.caseId())
          .get(0, Long.class)))
          .as("第 " + i + " 轮：B 读不到 A 的备注")
          .isZero();
      assertThat(asA(() -> details.detail(ca.caseId()).notes()))
          .as("第 " + i + " 轮：A 只看到自己的备注")
          .allMatch(n -> "A 的备注".equals(n.noteBody()));
    }

    // 事务外读：没有 app.current_tenant 时 FORCE RLS 让所有行都读不到
    assertThat(sql.fetch("select * from aftersales_case").size())
        .as("事务外读必须零行")
        .isZero();
    assertThat(sql.fetch("select * from aftersales_message").size()).isZero();
    assertThat(sql.fetch("select * from aftersales_attachment").size()).isZero();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 三张新表都强制rls而且运行角色真的能读写() {
    for (String table : List.of("aftersales_case_line", "aftersales_message", "aftersales_attachment")) {
      var row = sql.fetchOne(
          "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V157 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
      assertThat(sql.fetchOne("select has_table_privilege('trade_app',?,'SELECT')", table).get(0, Boolean.class))
          .as("trade_app 必须真的能读 " + table)
          .isTrue();
      assertThat(sql.fetchOne("select has_table_privilege('trade_app',?,'INSERT')", table).get(0, Boolean.class))
          .as("trade_app 必须真能往 " + table + " 写")
          .isTrue();
    }
  }
}
