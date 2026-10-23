package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 1.1.1.2 异常订单队列的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且<b>实跑在真实业务表</b>上
 * （{@code sales_order} / {@code shipment_record} / {@code shipment_writeback_task} /
 * {@code platform_order} / {@code order_address_check}），不是占位探针表 ——
 * 探针表能证明 RLS 开着，证明不了<b>本模块的判定查询本身</b>没漏租户谓词。
 *
 * <p>本模块尤其不能省隔离：异常队列是**跨订单宽条件**的判定源，
 * 漏掉租户谓词就是<b>跨租户归因</b> —— A 家的地址不可投递结论会挂到 B 家的订单上，
 * 而 B 家连自己被挂上都不知道。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AnomalyOrderTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      java.util.stream.Stream.of("orders")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenant) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        allPermissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", allPermissions)));
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
            var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (access == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a));
      actors.put("Bearer tenant-b", actor(user, b));
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

  @Autowired AnomalyOrderService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.orders.read", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.orders.read", work);
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return tenant.equals(a) ? asA(work) : asB(work);
  }

  // ------------------------------------------------------------ 夹具

  static final OffsetDateTime NOW =
      OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
  static final OffsetDateTime H72 = NOW.minusHours(72);

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate shipment_writeback_task, shipment_record, platform_order,"
              + " order_address_check_item, order_address_check, order_address_check_payment_rule,"
              + " order_address_check_country_rule, order_address_check_embargo_rule,"
              + " order_address_check_policy, sales_order_status_history,"
              + " order_charge, order_address_snapshot, sales_order_party_snapshot,"
              + " sales_order_line, sales_order cascade");
    }
  }

  /** 一张带「平台超时未回传发货」信号的平台单。 */
  UUID seedStuckOrder(UUID tenant, String orderNo) {
    return as(
        tenant,
        () -> {
          UUID orderId = UUID.randomUUID();
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,"
                  + "100.0000,0,0,0,0,100.0000,0,?::timestamptz,?::timestamptz)",
              orderId, tenant, orderNo, "EXT-" + orderNo, H72, H72, H72);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?,?,?,1,?,'SKU-1','商品',1,100.0000,0,0,100.0000,'',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), H72);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?,?,?,'C-1','收件人','INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), H72);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?,?,?,'SHIPPING','收件人','+1-213-555-0100','buyer@example.com','',"
                  + "'US','CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tenant, orderId, H72);
          UUID recordId = UUID.randomUUID();
          sql.execute(
              "insert into shipment_record(id,tenant_id,record_no,source_handover_id,"
                  + "source_packing_order_id,source_outbound_order_id,warehouse_id,carrier_code,"
                  + "carrier_name,master_waybill_no,shipped_at,parcel_count,sales_order_id,"
                  + "sales_order_no,shop_ref,synced_at,created_at,updated_at)"
                  + " values(?,?,'SR-1',?::uuid,?::uuid,?::uuid,?::uuid,'DHL','DHL','WB-1',"
                  + "?::timestamptz,1,?,?,'SHOP-1',?::timestamptz,?::timestamptz,?::timestamptz)",
              recordId, tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
              UUID.randomUUID(), H72, orderId, orderNo, H72, H72, H72);
          sql.execute(
              "insert into shipment_writeback_task(id,tenant_id,task_no,source_record_id,"
                  + "source_record_no,sales_order_no,carrier_code,master_waybill_no,shipped_at,"
                  + "platform_code,shop_code,shop_id,platform_order_ref,idempotency_key,payload,"
                  + "status,verification,attempt_count,max_attempts,next_attempt_at,created_at,updated_at)"
                  + " values(?,?,'PB-1',?,'SR-1',?,'DHL','WB-1',?::timestamptz,'AMAZON','SHOP-1',"
                  + "?::uuid,'EXT-'||?,'idem-'||?,'{}'::jsonb,'PENDING','NOT_REQUIRED',0,5,"
                  + "?::timestamptz,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(), tenant, recordId, orderNo, H72, UUID.randomUUID(), orderNo, orderNo,
              H72, H72, H72);
          return orderId;
        });
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================
  @Test
  void 两租户互不可见() {
    UUID orderA = seedStuckOrder(a, "SO-A");

    // A 租户看得见自己那一条。
    assertThat(asA(() -> service.page(null, null, null, null, null, null, 0, 50)).total())
        .isEqualTo(1);
    assertThat(asA(() -> service.page(null, null, null, null, null, null, 0, 50)).items().get(0).orderNo())
        .isEqualTo("SO-A");

    // B 租户**看不到**：清单为空，按主键直查也是 404。
    assertThat(asB(() -> service.page(null, null, null, null, null, null, 0, 50)).total()).isZero();
    assertThatThrownBy(() -> asB(() -> service.detail(orderA)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于本租户");
  }

  @Test
  void 两家各有一单时互不可见() {
    seedStuckOrder(a, "SO-A");
    seedStuckOrder(b, "SO-B");

    var pageA = asA(() -> service.page(null, null, null, null, null, null, 0, 50));
    assertThat(pageA.total()).isEqualTo(1);
    assertThat(pageA.items().get(0).orderNo()).isEqualTo("SO-A");

    var pageB = asB(() -> service.page(null, null, null, null, null, null, 0, 50));
    assertThat(pageB.total()).isEqualTo(1);
    assertThat(pageB.items().get(0).orderNo()).isEqualTo("SO-B");
  }

  @Test
  void 按店铺筛选不会跨租户看到对方的店() {
    seedStuckOrder(a, "SO-A");
    seedStuckOrder(b, "SO-B");

    // 两家都用同一个店铺码 'SHOP-1'：A 按店铺筛出来的只能是 A 自己的那一单。
    var pageA = asA(() -> service.page(null, "SHOP-1", null, null, null, null, 0, 50));
    assertThat(pageA.total()).isEqualTo(1);
    assertThat(pageA.items().get(0).orderNo()).isEqualTo("SO-A");
    assertThat(asB(() -> service.page(null, "SHOP-1", null, null, null, null, 0, 50)).items().get(0).orderNo())
        .isEqualTo("SO-B");
  }

  // ==========================================================================
  // 断言 2：伪造 tenant header 无效
  // ==========================================================================
  @Test
  void 伪造租户上下文无效() {
    UUID orderA = seedStuckOrder(a, "SO-A");

    // 在 A 的会话里伪造 B 的租户上下文（等价于伪造 X-Tenant-Id 头）⇒ 读不到 A 的行。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "trade.orders.read",
                    () -> {
                      sql.execute("select set_config('app.current_tenant','" + b + "',true)");
                      return service.detail(orderA);
                    }))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或不属于本租户");

    // 伪造上下文下的清单也读不到 A 的行。
    assertThat(
            access.run(
                actorA,
                "trade.orders.read",
                () -> {
                  sql.execute("select set_config('app.current_tenant','" + b + "',true)");
                  return service.page(null, null, null, null, null, null, 0, 50).total();
                }))
        .isZero();
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================
  @Test
  void 连接池固定一条时交替使用不串租户() {
    UUID orderA = seedStuckOrder(a, "SO-A");
    UUID orderB = seedStuckOrder(b, "SO-B");

    for (int round = 0; round < 5; round++) {
      // A → B → A 交替：连接池只有 1 条连接，租户上下文必须随事务一起换掉。
      assertThat(asA(() -> service.page(null, null, null, null, null, null, 0, 50)).total())
          .isEqualTo(1);
      assertThat(asA(() -> service.page(null, null, null, null, null, null, 0, 50)).items().get(0).orderNo())
          .isEqualTo("SO-A");

      assertThat(asB(() -> service.page(null, null, null, null, null, null, 0, 50)).total())
          .isEqualTo(1);
      assertThat(asB(() -> service.page(null, null, null, null, null, null, 0, 50)).items().get(0).orderNo())
          .isEqualTo("SO-B");

      // 主键直查也必须互不可见。
      assertThatThrownBy(() -> asB(() -> service.detail(orderA)))
          .isInstanceOf(ResponseStatusException.class);
      assertThatThrownBy(() -> asA(() -> service.detail(orderB)))
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  @Test
  void 事务外读零行() {
    seedStuckOrder(a, "SO-A");
    // 不带租户上下文读 ⇒ 一行都读不到（RLS 的 USING 谓词不成立）。
    assertThat(
            sql.fetchOne(
                    "select count(*) c from sales_order where id in "
                        + "(select sales_order_id from shipment_record)")
                .get("c", Long.class))
        .as("没有租户上下文时不该读到任何行")
        .isZero();
  }

  @Test
  void 两家各建各的地址校验结论互不串() {
    // A 家的订单在 A 的会话里判得出异常，在 B 的会话里读不到同一行。
    UUID orderA = seedStuckOrder(a, "SO-A");
    assertThat(asA(() -> service.detail(orderA).evidence())).isNotEmpty();
    assertThatThrownBy(() -> asB(() -> service.detail(orderA)))
        .isInstanceOf(ResponseStatusException.class);
  }
}
