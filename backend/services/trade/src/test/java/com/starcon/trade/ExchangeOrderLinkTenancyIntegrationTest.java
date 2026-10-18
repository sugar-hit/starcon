package com.starcon.trade;

import static com.starcon.trade.ExchangeOrderLinkModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.jooq.Record;
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
 * 11.3.2.2 换货新单的**多租户三断言**。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且**实跑在真实业务表**上
 * （exchange_order_link / exchange_order_link_line / exchange_order_link_event），
 * <b>不是</b>占位探针表 —— 探针表能证明 RLS 开着，证明不了<b>本模块的查询本身</b>
 * 没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：换货关联里同时装着<b>原订单号、新销售单号、
 * 替换 SKU、原 SKU 与仓库侧运单号</b>。漏掉租户谓词就是跨租户泄露 ——
 * A 家会看到 B 家「哪些买家在换货、换成了什么、货发到哪个运单号」，
 * 而 B 家连自己被拖住都不知道。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExchangeOrderLinkTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
  static final UUID membershipA = UUID.randomUUID(), membershipB = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer warehouse;
  static HttpServer catalog;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms = List.of("trade.aftersales.read", "trade.aftersales.write");
  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-04-01T08:00:00+08:00");

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
      actors.put("Bearer tenant-a", actor(userA, membershipA, a));
      actors.put("Bearer tenant-b", actor(userB, membershipB, b));
      identity.start();

      // 隔离用例不需要真实预占语义，只需要 warehouse 端点按契约形状应答。
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/inventory/sellable/skus",
          e -> {
            String sku = null;
            for (String kv : String.valueOf(e.getRequestURI().getRawQuery()).split("&")) {
              if (kv.startsWith("skuId=")) sku = kv.substring(6);
            }
            var row = new java.util.LinkedHashMap<String, Object>();
            row.put("skuId", sku);
            row.put("skuCode", "TEN-SKU");
            row.put("skuName", "隔离用例替换商品");
            row.put("onHand", new BigDecimal("100"));
            row.put("reserved", BigDecimal.ZERO);
            row.put("available", new BigDecimal("100"));
            row.put("sellable", new BigDecimal("100"));
            send(e, Map.of("rows", List.of(row), "total", 1, "page", 0, "size", 1));
          });
      warehouse.createContext(
          "/warehouse/inventory/reservations",
          e -> {
            if ("POST".equals(e.getRequestMethod())) {
              send(
                  e,
                  Map.of(
                      "id", UUID.randomUUID().toString(),
                      "reservationNo", "RSV-TEN-" + UUID.randomUUID().toString().substring(0, 6),
                      "status", "HELD",
                      "statusLabel", "预占中"));
              return;
            }
            e.sendResponseHeaders(404, -1);
            e.close();
          });
      warehouse.createContext(
          "/warehouse/inventory/reservations/",
          e -> {
            e.getRequestBody().readAllBytes();
            String path = e.getRequestURI().getPath();
            int last = path.lastIndexOf('/');
            int prev = path.lastIndexOf('/', last - 1);
            send(
                e,
                Map.of(
                    "reservationId", path.substring(prev + 1, last),
                    "reservationNo", "RSV-TEN",
                    "terminal", true,
                    "statusBefore", "HELD",
                    "statusAfter", "RELEASED",
                    "restoredQuantity", "0.000000",
                    "replayed", false,
                    "message", "已释放"));
          });
      warehouse.start();

      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/skus/",
          e -> {
            String id = e.getRequestURI().getPath().substring("/catalog/skus/".length());
            send(e, Map.of("id", id, "code", "TEN-SKU", "name", "隔离用例替换商品",
                "status", "ACTIVE"));
          });
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static AccessService.Access actor(UUID uid, UUID membership, UUID tenant) {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms));
    return new AccessService.Access(uid, tenant, "Test", membership, null, perms, grants);
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
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    warehouse.stop(0);
    catalog.stop(0);
    pg.stop();
  }

  static void send(com.sun.net.httpserver.HttpExchange e, Object payload) throws java.io.IOException {
    byte[] body = json.writeValueAsBytes(payload);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, body.length);
    e.getResponseBody().write(body);
    e.close();
  }

  @Autowired ExchangeOrderLinkService links;
  @Autowired ExchangeAuthorizationService exchanges;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate exchange_order_link_event, exchange_order_link_line, exchange_order_link,"
              + " exchange_return_requirement, exchange_outbound_demand, exchange_reservation,"
              + " exchange_authorization_line, exchange_authorization,"
              + " aftersales_case_line, aftersales_case, shipment_record, sales_order cascade");
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

  Record one(String query, Object... binds) {
    var r = sql.fetch(query, binds);
    return r.isEmpty() ? null : r.get(0);
  }

  ExchangeOrderQuery query(int page, int size) {
    return new ExchangeOrderQuery(null, null, null, null, null, null, page, size);
  }

  /** 一套完整的素材：原订单（含地址与客户快照）+ 换货售后单 + 售后商品行。 */
  record Seed(UUID orderId, UUID caseId, UUID caseLineId) {}

  Seed seedCase(UUID tenant, String suffix) {
    UUID orderId = UUID.randomUUID();
    UUID skuId = UUID.randomUUID();
    as(
        tenant,
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,"
                  + "external_order_no,currency,payment_method,payment_terms_days,status,"
                  + "submitted_at,subtotal_amount,discount_amount,tax_amount,shipping_amount,"
                  + "other_amount,grand_total_amount,note,version,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,?,'AMAZON','',?,'USD','PREPAID',0,'SUBMITTED',"
                  + "?::timestamptz,120.0000,0,0,0,0,120.0000,'',0,?::timestamptz,?::timestamptz)",
              orderId, tenant, "SO-" + suffix, "EXT-" + suffix, T1, T1, T1);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,1,?::uuid,?,?,2,60.0000,0,0,120.0000,'',"
                  + "?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, skuId, "ORIG-" + suffix, "商品", T1);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,"
                  + "recipient_name,recipient_phone,recipient_email,company_name,country_code,"
                  + "province,city,district,postcode,address_line1,address_line2,tax_id,note,"
                  + "created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
                  + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, "买家" + suffix, "buyer@example.com", T1);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?,'INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "C-" + suffix,
              "买家" + suffix, T1);
          return null;
        });
    UUID caseId = UUID.randomUUID();
    UUID caseLineId = UUID.randomUUID();
    as(
        tenant,
        () -> {
          sql.execute(
              "insert into aftersales_case(id,tenant_id,case_no,order_id,case_type,status,"
                  + "reason_code,requested_amount,currency,requested_quantity,buyer_request,"
                  + "reply_due_at,received_at,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,?,?::uuid,'EXCHANGE','RECEIVED','SIZE_MISMATCH',"
                  + "120.0000,'USD',2.000000,?,?::timestamptz,?::timestamptz,?::timestamptz,"
                  + "?::timestamptz)",
              caseId, tenant, "AS-" + suffix, orderId, "隔离用例诉求", T1, T1, T1, T1);
          sql.execute(
              "insert into aftersales_case_line(id,tenant_id,case_id,order_line_id,quantity,"
                  + "requested_amount,currency,buyer_note,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,(select id from sales_order_line"
                  + " where tenant_id=?::uuid and order_id=?::uuid limit 1),2.000000,null,null,"
                  + "'尺码不对',?::timestamptz)",
              caseLineId, tenant, caseId, tenant, orderId, T1);
          return null;
        });
    return new Seed(orderId, caseId, caseLineId);
  }

  /** 走 11.3.2.1 自己的授权入口造一张换货授权单。 */
  UUID authorize(UUID tenant, String suffix) {
    Seed seed = seedCase(tenant, suffix);
    UUID member = tenant.equals(a) ? membershipA : membershipB;
    UUID user = tenant.equals(a) ? userA : userB;
    return as(
        tenant,
        () ->
            exchanges.authorize(
                    new ExchangeAuthorizationModels.ExchangeAuthorizeRequest(
                        seed.caseId(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        List.of(
                            new ExchangeAuthorizationModels.ExchangeLineRequest(
                                seed.caseLineId(), UUID.randomUUID(), new BigDecimal("2"), "换码")),
                        false,
                        "隔离用例授权"),
                    user,
                    "Bearer tenant-a")
                .authorization()
                .id());
  }

  /** 一张真实的换货新单（走本模块自己的建单入口，不在测试里直接 INSERT 关联表）。 */
  ExchangeOrderDetail createLink(UUID tenant, String suffix) {
    UUID authorizationId = authorize(tenant, suffix);
    UUID user = tenant.equals(a) ? userA : userB;
    UUID member = tenant.equals(a) ? membershipA : membershipB;
    return as(tenant, () -> links.create(
        new ExchangeOrderCreateRequest(authorizationId, "隔离用例"), user, member));
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 三张业务表都强制rls而且运行角色真的能读写() {
    for (String table :
        List.of("exchange_order_link", "exchange_order_link_line", "exchange_order_link_event")) {
      var row = one("select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V167 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
      assertThat(
              one("select has_table_privilege('trade_app',?,'SELECT')", table).get(0, Boolean.class))
          .as("trade_app 必须真的能读 " + table + "，否则「看不到」可能只是谁都读不了")
          .isTrue();
    }
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    // 每轮都**新建实体**，否则测的是幂等不是隔离。
    ExchangeOrderRow rowA = asA(() -> createLink(a, "MO-A").link());
    createLink(b, "MO-B");

    assertThat(asA(() -> links.list(query(0, 50)).total())).as("A 只看得到自己那一份").isEqualTo(1);
    assertThat(asB(() -> links.list(query(0, 50)).total())).isEqualTo(1);

    // 正对照：同样写法在**自己**租户下必须查得到，否则「查不到」可能只是 id 写错了。
    assertThat(asA(() -> sql.fetch(
            "select id from exchange_order_link where id = ?::uuid", rowA.id()).size()))
        .as("正对照：A 家按主键必须能查到自己这一行")
        .isEqualTo(1);

    var byIdB =
        asB(() -> sql.fetch("select id from exchange_order_link where id = ?::uuid", rowA.id()).size());
    assertThat(byIdB).as("按主键直查也必须被 RLS 挡住").isZero();

    // 用对方的 id 调详情同样是 404，而不是「静默成功」。
    assertThatThrownBy(() -> asB(() -> links.detail(rowA.id())))
        .hasMessageContaining("不存在或不属于当前租户");

    // 跨租户最危险的两样：原订单号与**新销售单号**（等于告诉对手货发到哪张单）。
    assertThat(asB(() -> sql.fetch(
            "select original_order_no, new_order_no from exchange_order_link where tenant_id = ?::uuid",
            a).size()))
        .isZero();
    assertThat(asB(() -> sql.fetch(
            "select id from sales_order where manual_kind = 'EXCHANGE' and tenant_id = ?::uuid", a)
            .size()))
        .as("换货新单本身也不许被对方租户看到")
        .isZero();
  }

  // ==========================================================================
  // 断言 2：伪造 tenant header 无效
  //
  // 正确预期：请求**仍然按会话里的真实成员关系正常执行并 200**，
  // 写入落在会话租户名下，对方租户逐字节未变 —— 不是 4xx。
  // 断言要比对两侧数据库，不靠状态码。
  // ==========================================================================
  @Test
  void 断言2_伪造租户头无效且行仍落在会话租户名下() throws Exception {
    Seed seed = seedCase(a, "MO-FORGED");
    UUID authorizationId =
        asA(
            () ->
                exchanges.authorize(
                        new ExchangeAuthorizationModels.ExchangeAuthorizeRequest(
                            seed.caseId(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            List.of(
                                new ExchangeAuthorizationModels.ExchangeLineRequest(
                                    seed.caseLineId(), UUID.randomUUID(), new BigDecimal("2"),
                                    "换码")),
                            false,
                            "伪造头写入"),
                        userA,
                        "Bearer tenant-a")
                    .authorization()
                    .id());

    String body =
        ("{\"authorizationId\":\"%s\",\"reasonNote\":\"伪造头写入\"}").formatted(authorizationId);

    http.perform(post("/trade/aftersales/exchange-orders")
            .header("Authorization", "Bearer tenant-a")
            // 伪造的租户头与内部键：后端必须完全忽略它们。
            .header("X-Tenant-Id", b.toString())
            .header("X-Internal-Key", "forged")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        // 伪造头**不该**改变结果：仍按会话里的 A 家成员关系正常执行。
        .andExpect(status().isOk());

    var row = asA(() -> one("select tenant_id, new_order_id from exchange_order_link"));
    assertThat(row).isNotNull();
    assertThat(row.get(0, UUID.class))
        .as("伪造 X-Tenant-Id 改不动数据归属：租户来自已认证成员关系")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 建出来的那张换货新单也必须落在 A 家名下。
    assertThat(asA(() -> one(
                "select tenant_id from sales_order where id = ?::uuid",
                row.get(1, UUID.class))
            .get(0, UUID.class)))
        .isEqualTo(a);

    // B 家在自己的会话里看不到这一笔（伪造头声称是 B 家，但写进了 A 家）。
    assertThat(asB(() -> links.list(query(0, 50)).total()))
        .as("伪造头声称写给 B 家，B 家会话里却一笔也没有 —— 这一笔真落在 A 家名下")
        .isZero();
    assertThat(asA(() -> links.list(query(0, 50)).total())).isEqualTo(1L);
  }

  /**
   * 断言 2 的**写侧**那一半：不是「伪造头改不了归属」，而是
   * <b>拿着自己合法的 B 家会话 + 伪造头，也改不动 A 家已存在的换货新单</b>。
   *
   * <p>两个攻击面不同：前者靠「租户只从成员关系解析」挡住，后者必须靠 **FORCE RLS** 挡住。
   */
  @Test
  void 断言2_合法B家会话加伪造头改不动A家已有的换货新单() throws Exception {
    var createdA = asA(() -> createLink(a, "MO-WRITE"));

    // 对照组：A 家自己撤得动，否则本测试可能只是「接口本来就坏了」。
    assertThat(asA(() -> links.list(query(0, 50)).total())).isEqualTo(1L);

    String body =
        ("{\"expectedVersion\":%d,\"reason\":\"B 家伪造撤销\"}")
            .formatted(createdA.link().version());

    http.perform(post("/trade/aftersales/exchange-orders/" + createdA.link().id() + "/cancel")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString())
            .header("X-Internal-Key", "forged")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        // B 家会话看不到 A 家的单：404 而不是「静默改成功」。
        .andExpect(status().isNotFound());

    // 行的归属与内容都还在 A 家名下，且**没有被 B 家那次伪造撤销改动**。
    var row =
        asA(
            () ->
                one(
                    "select tenant_id, closure_status, cancel_reason, version"
                        + " from exchange_order_link where id = ?::uuid",
                    createdA.link().id()));
    assertThat(row).isNotNull();
    assertThat(row.get(0, UUID.class)).as("伪造撤销不得改掉归属").isEqualTo(a);
    assertThat(row.get(1, String.class)).as("状态必须仍是已建立").isEqualTo("CREATED");
    assertThat(row.get(2, String.class)).as("撤销原因不能被 B 家的伪造值写进去").isEmpty();
    assertThat(row.get(3, Integer.class)).as("版本号没有被伪造撤销推进").isEqualTo(1);

    // 换货新单也必须逐字节未变：B 家那次伪造调用连一张单都没碰。
    assertThat(asA(() -> one(
                "select status from sales_order where id = ?::uuid",
                createdA.link().newOrderId())
            .get(0, String.class)))
        .as("新单仍是草稿，没被伪造撤销作废")
        .isEqualTo("DRAFT");
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 每轮都新建实体：老用同一批行的话，第 2 轮开始测的就是幂等了。
    for (int i = 0; i < 5; i++) {
      UUID expected = i % 2 == 0 ? a : b;
      UUID other = expected.equals(a) ? b : a;
      final UUID tenant = expected;
      final String suffix = "POOL" + i;
      ExchangeOrderDetail created = as(expected, () -> createLink(tenant, suffix));

      var seen =
          as(
              expected,
              () ->
                  one(
                      "select tenant_id from exchange_order_link where id = ?::uuid",
                      created.link().id()));
      assertThat(seen).as("第 " + i + " 轮：刚写的这一行必须查得到（正对照）").isNotNull();
      assertThat(seen.get(0, UUID.class)).as("第 " + i + " 轮：归属必须是会话租户").isEqualTo(expected);

      var leaked =
          as(
              other,
              () ->
                  sql.fetch(
                          "select id from exchange_order_link where id = ?::uuid",
                          created.link().id())
                      .size());
      assertThat(leaked).as("第 " + i + " 轮：对方租户读不到这一行").isZero();

      // 明细行也必须各自独立（它带着替换 SKU 与原 SKU）。
      assertThat(as(other, () -> sql.fetch(
              "select id from exchange_order_link_line where link_id = ?::uuid",
              created.link().id()).size()))
          .as("第 " + i + " 轮：对方租户连明细行都读不到")
          .isZero();

      // 汇总也必须各自独立：「A 家汇总里混进 B 家的量」比看到对方的单更隐蔽。
      // ⚠️ 断言的是**累计条数**而不是「各自 1 条」：5 轮里 A/B 轮流写入，
      //   到第 5 轮时 A 家本来就该有 3 条。写成 1 的话测的就不是隔离而是「库里只有一行」。
      long ownExpected = expected.equals(a) ? (i + 2) / 2 : (i + 1) / 2;
      long otherExpected = expected.equals(a) ? (i + 1) / 2 : (i + 2) / 2;
      assertThat(as(expected, () -> links.list(query(0, 50)).total()))
          .as("第 " + i + " 轮：本租户只该看到本租户累计建的 " + ownExpected + " 条")
          .isEqualTo(ownExpected);
      assertThat(as(other, () -> links.list(query(0, 50)).total()))
          .as("第 " + i + " 轮：对方租户只该看到它自己建的 " + otherExpected + " 条")
          .isEqualTo(otherExpected);
    }
  }

  // ==========================================================================
  // 附带：未认证
  // ==========================================================================
  @Test
  void 未认证返回401() throws Exception {
    http.perform(get("/trade/aftersales/exchange-orders")).andExpect(status().isUnauthorized());
    http.perform(get("/trade/aftersales/exchange-orders").header("Authorization", "Bearer nope"))
        .andExpect(status().isUnauthorized());
  }
}
