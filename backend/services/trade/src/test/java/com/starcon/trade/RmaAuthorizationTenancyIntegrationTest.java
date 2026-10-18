package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.starcon.trade.RmaAuthorizationModels.*;
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
 * 11.2.1.1 退货授权单的**多租户三断言**。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且**实跑在本模块自己的三张表上**
 * （rma_authorization / rma_authorization_line / rma_decision_event），
 * <b>不是</b>占位探针表 —— 探针表能证明 RLS 开着，证明不了<b>本模块的查询本身</b>
 * 没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：授权单带着<b>退货地址</b>（收货人姓名 + 电话）
 * 与<b>买家回寄运单号**，而审批、详情、可退量校验端点都是<b>按 id 直达</b>的 URL ——
 * 猜到一个 UUID 就能看，不需要翻列表。而可退量校验还要读**别的租户不该看到的订单行**。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离 —— 复用上一轮的记录，
 * 第二轮断言通过只能说明「同一条记录没变」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RmaAuthorizationTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
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

  @Autowired RmaAuthorizationService rma;
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

  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate rma_decision_event, rma_authorization_line, rma_authorization,"
              + " aftersales_case_line, aftersales_case_note, aftersales_authorization,"
              + " aftersales_service_ticket, aftersales_owner_assignment, aftersales_case,"
              + " order_fulfillment_line_progress, sales_order cascade");
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

  /** 一张真实订单（带商品行 + 已发进度）+ 一张真实售后单 + 商品行 + 一张退货授权单草稿。 */
  record Seeded(UUID rmaId, UUID caseId, UUID orderId, UUID lineId) {}

  Seeded seed(UUID tid, String orderNo) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    BigDecimal qty = new BigDecimal("4");
    BigDecimal unitPrice = new BigDecimal("5.0000");
    BigDecimal amount = unitPrice.multiply(qty);

    as(
        tid,
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,?::numeric,"
                  + "0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
              orderId, tid, orderNo, "EXT-" + orderNo, T1, amount, amount, T1, T1);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
                  + "created_at) values(?,?::uuid,?::uuid,1,?,?,?,'黑色 / L',?::numeric,?::numeric,"
                  + "0,0,?::numeric,'',?::timestamptz)",
              lineId, tid, orderId, UUID.randomUUID(), "SKU-" + orderNo, "商品" + orderNo,
              qty, unitPrice, amount, T1);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
                  + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tid, orderId, "买家-" + orderNo, "buyer-" + orderNo + "@example.com",
              T1);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?::uuid,?::uuid,?,?,?,'INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), tid, orderId, UUID.randomUUID(), "C-" + orderNo,
              "买家-" + orderNo, T1);
          sql.execute(
              "insert into order_fulfillment_line_progress(id,tenant_id,order_id,order_line_id,"
                  + "ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,"
                  + "observed_at,version,created_at,updated_at)"
                  + " values(?,?::uuid,?::uuid,?::uuid,?::numeric,0,0,?::numeric,?::timestamptz,"
                  + "'MODULE_9_3_3_1',?::timestamptz,0,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(), tid, orderId, lineId, qty, qty, T1, T1, T1, T1);
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
    as(
        tid,
        () ->
            details.addLine(
                caseId,
                new AftersalesCaseDetailModels.AftersalesCaseLineCreate(
                    lineId, qty, amount, "USD", "第 1 件"),
                userA));

    UUID rmaId =
        as(
            tid,
            () ->
                rma.create(
                        new RmaCreate(
                            caseId, "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                            "退货仓-" + orderNo, "+1-213-555-0199", "rma-" + orderNo + "@example.com",
                            "US", "CA", "ONTARIO", "", "M5V 2T6", "88 Yonge St", "3 楼", null, null),
                        userA)
                    .head()
                    .id());

    return new Seeded(rmaId, caseId, orderId, lineId);
  }

  private RmaDetail detail(UUID tid, UUID rmaId) {
    return as(tid, () -> rma.detail(rmaId));
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
    assertThat(detail(a, sa.rmaId()).head().returnRecipientName()).isEqualTo("退货仓-MO-TA");
    assertThat(detail(b, sb.rmaId()).head().returnRecipientName()).isEqualTo("退货仓-MO-TB");

    // 更强的一格：**按主键直查对方的单也拿不到**（不只是列表过滤）。
    // 这些端点是按 id 直达的 URL，拿不到才是真的拿不到。
    assertThatThrownBy(() -> detail(b, sa.rmaId()))
        .as("B 家读 A 家的退货授权单必须失败")
        .hasMessageContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> detail(a, sb.rmaId()))
        .as("A 家读 B 家的退货授权单必须失败")
        .hasMessageContaining("不存在或不属于当前租户");

    // 可退量校验同样按 caseId 隔离
    assertThatThrownBy(() -> asB(() -> rma.returnableQuantities(sa.caseId())))
        .hasMessageContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> asA(() -> rma.returnableQuantities(sb.caseId())))
        .hasMessageContaining("不存在或不属于当前租户");

    // 三张表按主键直查也互不可见，**并且每张都有正对照**
    //（正对照缺席时，「两边都是 0」这种假绿会被当成隔离生效）。
    for (String table : List.of("rma_authorization", "rma_authorization_line", "rma_decision_event")) {
      // ⚠️ 主键列名逐表不同：头表叫 id，行表与留痕表叫 rma_id。
      //   这里按表分派，写死一个列名会让三张表里有一张直接语法错 —— 而错的那张
      //   恰恰是「查对方记录返回零行」这条断言的表，测出来的是报错不是隔离。
      String pk = table.equals("rma_authorization") ? "id" : "rma_id";
      assertThat(rowCount(a, table)).as("A 自己的 " + table).isEqualTo(1);
      assertThat(rowCount(b, table)).as("B 自己的 " + table).isEqualTo(1);
      assertThat(asB(() -> sql.fetch(
                  "select * from " + table + " where " + pk + " = ?::uuid", sa.rmaId()).size()))
          .as("B 按主键直查 A 的 " + table)
          .isZero();
      assertThat(asA(() -> sql.fetch(
                  "select * from " + table + " where " + pk + " = ?::uuid", sa.rmaId()).size()))
          .as("A 按同样写法直查自己的 " + table + " 必须查得到（防假绿的正对照）")
          .isEqualTo(1);
    }

    // 审批也一样：拿对方的 id 去批，必须落到会话租户名下而不是对方的单上。
    // ⚠️ **行 id 必须先在 asA 里取出来**：把 asA 的调用嵌进 asB 的 lambda 里，
    //   内层事务会把 app.current_tenant 重新设成 A，于是外层这次「跨租户审批」
    //   实际是在 A 的租户下跑的、必然不报错 —— 这条断言就变成了永远为真的假绿。
    var lineIdA = detail(a, sa.rmaId).lines().get(0).id();
    assertThatThrownBy(() ->
            asB(() ->
                rma.decide(
                    sa.rmaId,
                    new RmaDecide("APPROVED", OffsetDateTime.parse("2026-12-01T00:00:00Z"), null,
                        List.of(new RmaLineDecision(lineIdA, new BigDecimal("1"), null)),
                        0L),
                    userB)))
        .hasMessageContaining("不存在或不属于当前租户");
    assertThat(detail(a, sa.rmaId()).head().rmaStatus()).isEqualTo("DRAFT");
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
                get("/trade/rma-authorizations/" + sa.rmaId)
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    var parsed = json.readTree(body);
    assertThat(parsed.get("head").get("returnRecipientName").asText()).isEqualTo("退货仓-MO-HDR");
    assertThat(parsed.get("head").get("caseNo").asText()).isNotBlank();

    // 可退量校验同理：伪造头拿不到 B 的任何数据。
    http.perform(
            get("/trade/rma-authorizations/returnable-quantities")
                .param("caseId", sa.caseId.toString())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.returnableQty").value(4));

    // ⚠️ **伪造头不能把别人的单读出来**：B 的会话 + 伪造 A 的头去读 A 的 id，
    //   得到的仍然必须是「不存在或不属于当前租户」——租户**只**来自会话。
    http.perform(
            get("/trade/rma-authorizations/" + sa.rmaId)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isBadRequest());

    // 伪造头也开不了写入口：B 的会话 + 伪造 A 的头想批 A 的单，必须被拒。
    var decide = new LinkedHashMap<String, Object>();
    decide.put("decision", "APPROVED");
    decide.put("returnByAt", "2026-12-01T00:00:00Z");
    decide.put(
        "lines",
        List.of(Map.of(
            "lineId", detail(a, sa.rmaId).lines().get(0).id(),
            "authorizedQty", 1)));
    decide.put("expectedVersion", 0L);
    http.perform(
            post("/trade/rma-authorizations/" + sa.rmaId + "/decide")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(decide)))
        .andExpect(status().isBadRequest());
    assertThat(detail(a, sa.rmaId).head().rmaStatus()).as("A 的单仍然是草稿").isEqualTo("DRAFT");

    // 伪造头也不能写进 A 的租户：A 名下的授权单数仍然是它自己那一张。
    assertThat(rowCount(a, "rma_authorization")).isEqualTo(1);
    assertThat(rowCount(b, "rma_authorization")).isZero();

    // 没有会话就没有租户上下文。
    http.perform(get("/trade/rma-authorizations/" + sa.rmaId))
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

      assertThat(detail(a, sa.rmaId).head().caseNo()).as("第 " + round + " 轮 A 读到的是 A 的单")
          .isEqualTo(asA(() -> sql.fetchOne(
                  "select case_no from aftersales_case where tenant_id = ?::uuid and id = ?::uuid",
                  a, sa.caseId)
              .get(0, String.class)));
      assertThat(detail(b, sb.rmaId).head().caseNo()).as("第 " + round + " 轮 B 读到的是 B 的单")
          .isEqualTo(asB(() -> sql.fetchOne(
                  "select case_no from aftersales_case where tenant_id = ?::uuid and id = ?::uuid",
                  b, sb.caseId)
              .get(0, String.class)));

      // 交替写：先批再登记回寄运单号，然后立刻用另一个租户读 —— 必须读不到。
      var lineIdA = detail(a, sa.rmaId).lines().get(0).id();
      asA(() ->
          rma.decide(
              sa.rmaId,
              new RmaDecide("APPROVED", OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(14),
                  null, List.of(new RmaLineDecision(lineIdA, new BigDecimal("1"), null)), 0L),
              userA));
      asA(() ->
          rma.recordReturnLogistics(
              sa.rmaId,
              new RmaReturnLogistics("FedEx", "A-" + round, T1, "TENANT_MAINTAINED", 1L),
              userA));
      assertThat(asB(() -> sql.fetch(
                  "select * from rma_authorization where id = ?::uuid", sa.rmaId).size()))
          .as("第 " + round + " 轮：B 读不到 A 的回寄运单号")
          .isZero();
      assertThat(asA(() -> sql.fetch(
                  "select * from rma_authorization where id = ?::uuid", sa.rmaId).size()))
          .as("第 " + round + " 轮：A 用同样写法读得到自己的那一张（防假绿的正对照）")
          .isEqualTo(1);
    }

    // 事务外读：没有 app.current_tenant 时 FORCE RLS 让所有行都读不到。
    assertThat(sql.fetch("select * from rma_authorization").size()).as("事务外读必须零行").isZero();
    assertThat(sql.fetch("select * from rma_authorization_line").size()).isZero();
    assertThat(sql.fetch("select * from rma_decision_event").size()).isZero();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 三张新表都强制rls而且运行角色真的能读写() {
    for (String table : List.of("rma_authorization", "rma_authorization_line", "rma_decision_event")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V161 必须真的建出 " + table).isNotNull();
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
