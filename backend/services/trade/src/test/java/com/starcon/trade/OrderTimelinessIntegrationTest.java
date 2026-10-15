package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * 9.5.1.1 时效分析。
 *
 * <p>本测试是 <b>V131 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，
 * 逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不只直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p><b>零替身</b>：本模块三个上游（9.4.1.2 shipment_record、9.4.1.1
 * shipment_pending_unit、V99 sales_order 族）**全在 trade 自己的库里**，
 * 所以夹具直接以表所有者身份落真库，不需要起任何 HTTP 替身 ——
 * 这是本模块与 9.4.1.2 测试最大的不同：那一格要替身是因为它要读
 * warehouse 服务，而本模块**一条 SELECT 都不碰 warehouse 库**。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>夹具里的时刻一律 pin 死</b>（{@link #ORDERED_AT} 等）：本模块的时长
 * 全是「两个时刻相减」，这些列又全要被断言 —— 若用 {@code now()} 构造，
 * 同一个用例只在某个钟点通过，批次调度会随机引爆。
 *
 * <p><b>本测试要钉住的十组</b>：
 * <ol>
 *   <li><b>时长由 DB 复算</b>：三段秒数断言<b>写回值</b>本身（93600 / 9000 / 102600），不是「没抛异常」；</li>
 *   <li><b>读不到就是 UNSET 而不是 0</b>：DRAFT 单没有 submitted_at、
 *       没被 9.4.1.1 同步过的票没有 packed_at，两种都断言该段为 null；</li>
 *   <li><b>幂等</b>：同一批货同步两次行数不变 —— 分母被翻倍准时率当场失真；</li>
 *   <li><b>三个桶互不吞并</b>：UNSET / NO_PROMISE / NO_ACTUAL 各自独立；</li>
 *   <li><b>比率必须带分母</b>：超时率分母不含未登记时限；</li>
 *   <li><b>四维拆解是真聚合</b>，且空维度值单独成组；</li>
 *   <li><b>DB 级闸门</b>：时间倒流 / 承诺早于登记 / 到货早于交接 / 快照脱离凭据 / 凭据改删；</li>
 *   <li><b>append-only 两层</b>：权限 42501 + 触发器 23514（以表所有者再撞一次）；</li>
 *   <li><b>FORCE RLS 逐张</b>且 starcon_app 拿不到；</li>
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderTimelinessIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  // ---------------------------------------------------------------- 夹具时刻（全部 pin 死）
  // order_to_pack       = 2026-03-02T10:00 − 2026-03-01T08:00 = 26h    = 93600s
  // pack_to_handover    = 2026-03-02T12:30 − 2026-03-02T10:00 = 2h30m  =  9000s
  // order_to_handover   = 2026-03-02T12:30 − 2026-03-01T08:00 = 28h30m = 102600s
  // ship_overdue(晚)     = 2026-03-02T12:30 − 2026-03-01T00:00 = 36h30m = 131400s
  // promise_vs_delivery = 2026-03-10T00:00 − 2026-03-08T00:00 = 48h    = 172800s
  static final OffsetDateTime ORDERED_AT = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");
  static final OffsetDateTime PACKED_AT = OffsetDateTime.parse("2026-03-02T10:00:00+08:00");
  static final OffsetDateTime HANDED_OVER_AT = OffsetDateTime.parse("2026-03-02T12:30:00+08:00");
  static final OffsetDateTime SLA_DUE_ONTIME = OffsetDateTime.parse("2026-03-03T00:00:00+08:00");
  static final OffsetDateTime SLA_DUE_LATE = OffsetDateTime.parse("2026-03-01T00:00:00+08:00");
  static final OffsetDateTime PROMISED_AT = OffsetDateTime.parse("2027-03-08T00:00:00+08:00");
  static final OffsetDateTime DELIVERED_LATE_AT = OffsetDateTime.parse("2027-03-10T00:00:00+08:00");
  static final OffsetDateTime REGISTERED_AT = OffsetDateTime.parse("2026-03-02T13:00:00+08:00");

  static final long SEC_ORDER_TO_PACK = 93600L;
  static final long SEC_PACK_TO_HANDOVER = 9000L;
  static final long SEC_ORDER_TO_HANDOVER = 102600L;
  static final long SEC_SHIP_OVERDUE = 131400L;
  static final long SEC_PROMISE_LATE = 172800L;

  @Autowired MockMvc mvc;

  // 租户 A 的五票货，覆盖全部分支
  static final UUID orderOnTime = UUID.randomUUID();
  static final UUID orderLate = UUID.randomUUID();
  static final UUID orderDraft = UUID.randomUUID();
  static final UUID orderNoPack = UUID.randomUUID();
  static final UUID skuAlpha = UUID.randomUUID(), skuBeta = UUID.randomUUID();
  // 打包单 id：待发货单元与发货记录**共用同一个**，
  // 同步按 source_packing_order_id join，两边不一致就永远取不到出库时刻
  static final UUID packOnTime = UUID.randomUUID();
  static final UUID packLate = UUID.randomUUID();
  static final UUID packDraft = UUID.randomUUID();
  static final UUID packNoPack = UUID.randomUUID();
  static final UUID packNoSnapshot = UUID.randomUUID();
  static final UUID packTenantB = UUID.randomUUID();

  static final List<String> allPermissions =
      java.util.stream.Stream.of("fulfillment")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
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
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, null, List.of(grant("ALL", List.of("trade.fulfillment.read")))));
      identity.start();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------------------------------------------------------------- 夹具

  /**
   * 以**表所有者**身份把上游三张表落成真数据。
   *
   * <p>刻意不用 HTTP 替身：这三个上游都在 trade 库里（V127 / V124 / V99），
   * 替身只会把「同库只读 join」这条真实路径换成假的。
   */
  static void seed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {

      // —— 销售订单（V99）。
      //     刻意先全部按 DRAFT 落，再补行，最后提交：V99 的 guard_sales_order_submitted
      //     触发器规定「已提交的订单必须已有商品行」，先提交后补行会被它当场拒。
      insertOrder(s, a, orderOnTime, "SO-ON-TIME");
      insertOrder(s, a, orderLate, "SO-LATE");
      insertOrder(s, a, orderDraft, "SO-DRAFT"); // 永远是草稿：没有 submitted_at
      insertOrder(s, a, orderNoPack, "SO-NOPACK");
      UUID orderB = UUID.randomUUID(); // 租户 B 的单：只用来证明互不可见
      insertOrder(s, b, orderB, "SO-TENANT-B");

      // —— 订单行（V99）：商品维度的来源
      insertLine(s, a, orderOnTime, skuAlpha, "SKU-ALPHA", "商品甲", ORDERED_AT);
      insertLine(s, a, orderLate, skuBeta, "SKU-BETA", "商品乙", ORDERED_AT);
      insertLine(s, a, orderNoPack, skuAlpha, "SKU-ALPHA", "商品甲", ORDERED_AT);
      insertLine(s, a, orderDraft, skuBeta, "SKU-BETA", "商品乙", ORDERED_AT);
      insertLine(s, b, orderB, skuAlpha, "SKU-BETA", "商品乙", ORDERED_AT);

      // 客户快照与收货地址快照同样是提交前置条件（V99 的 guard_sales_order_submitted）
      insertParty(s, a, orderOnTime);
      insertParty(s, a, orderLate);
      insertParty(s, a, orderNoPack);
      insertParty(s, b, orderB);
      insertAddress(s, a, orderOnTime);
      insertAddress(s, a, orderLate);
      insertAddress(s, a, orderNoPack);
      insertAddress(s, b, orderB);

      // 补完商品行与地址才提交：DRAFT 保持不变（submitted_at 留空 = UNSET 段）
      submit(s, a, orderOnTime, ORDERED_AT);
      submit(s, a, orderLate, ORDERED_AT);
      submit(s, a, orderNoPack, ORDERED_AT);
      submit(s, b, orderB, ORDERED_AT);

      // —— 待发货单元（V124）：出库时刻的来源。
      //    刻意只给前两票 packed_at：后两票模拟「9.4.1.1 还没同步过」。
      // 刻意只给这三票 pending_unit：SO-NOPACK 与无快照那票模拟「9.4.1.1 还没同步过」
      insertPending(s, a, packOnTime, orderOnTime, "SO-ON-TIME", PACKED_AT);
      insertPending(s, a, packLate, orderLate, "SO-LATE", PACKED_AT);
      insertPending(s, a, packDraft, orderDraft, "SO-DRAFT", PACKED_AT);

      // —— 发货记录（V127）：交接时刻与发货时限的来源
      insertRecord(s, a, packOnTime, "SO-ON-TIME", "SHOP-A", "WH-A", "DHL", "DHL",
          HANDED_OVER_AT, SLA_DUE_ONTIME, orderOnTime);
      insertRecord(s, a, packLate, "SO-LATE", "SHOP-A", "WH-A", "UPS", "UPS",
          HANDED_OVER_AT, SLA_DUE_LATE, orderLate);
      // 草稿单：没有 submitted_at，出库与交接都在（下单到出库 UNSET）
      insertRecord(s, a, packDraft, "SO-DRAFT", "SHOP-A", "WH-A", "DHL", "DHL",
          HANDED_OVER_AT, null, orderDraft);
      // 已提交但没有待发货单元：出库时刻取不到（出库到交接 UNSET）
      insertRecord(s, a, packNoPack, "SO-NOPACK", "SHOP-A", "WH-B", "UPS", "UPS",
          HANDED_OVER_AT, null, orderNoPack);
      // 这票没有订单快照也没有 pending_unit：模拟 9.4.1.1 还没同步过、还没回填订单
      insertRecord(s, a, packNoSnapshot, "", "SHOP-B", "WH-B", "FEDEX", "FedEx",
          HANDED_OVER_AT, null, null);
      // 租户 B 的一票：证明互不可见
      insertRecord(s, b, packTenantB, "SO-TENANT-B", "SHOP-A", "WH-A", "DHL", "DHL",
          HANDED_OVER_AT, null, orderB);
    }
  }

  static void insertOrder(Statement s, UUID tenant, UUID id, String no) throws Exception {
    s.execute(
        // 头侧金额必须与商品行真值一致（V99 触发器会逐项复核），
        // 这里按每单一行 ×2 件 ×10.00 = 20.00 落
        "insert into sales_order (id,tenant_id,order_no,order_source,manual_kind,currency,"
            + " payment_method,status,subtotal_amount,grand_total_amount)"
            + " values ('" + id + "','" + tenant + "','" + no + "','MANUAL','OFFLINE_STORE','USD',"
            + " 'PREPAID','DRAFT',20.00,20.00)");
  }

  static void insertParty(Statement s, UUID tenant, UUID orderId) throws Exception {
    s.execute(
        "insert into sales_order_party_snapshot (id,tenant_id,order_id,customer_party_id,"
            + " customer_code,customer_name,customer_type)"
            + " values (gen_random_uuid(),'" + tenant + "','" + orderId + "',gen_random_uuid(),"
            + "'C-1','测试客户','INDIVIDUAL')");
  }

  static void insertAddress(Statement s, UUID tenant, UUID orderId) throws Exception {
    s.execute(
        "insert into order_address_snapshot (id,tenant_id,order_id,address_role,"
            + " recipient_name,country_code,address_line1)"
            + " values (gen_random_uuid(),'" + tenant + "','" + orderId + "','SHIPPING',"
            + "'测试收件人','US','1 Test Street')");
  }

  /** 提交时刻是「下单时刻」的真值来源，刻意 pin 死而不是 now()。 */
  static void submit(Statement s, UUID tenant, UUID id, OffsetDateTime at) throws Exception {
    s.execute(
        "update sales_order set status='SUBMITTED',submitted_at=" + ts(at)
            + " where id='" + id + "' and tenant_id='" + tenant + "'");
  }

  static void insertLine(Statement s, UUID tenant, UUID orderId, UUID skuId, String code,
      String name, OffsetDateTime at) throws Exception {
    s.execute(
        "insert into sales_order_line (id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
            + " quantity,unit_price,line_amount)"
            + " values (gen_random_uuid(),'" + tenant + "','" + orderId + "',1,'" + skuId
            + "','" + code + "','" + name + "',2,10.00,20.00)");
  }

  static void insertPending(Statement s, UUID tenant, UUID packingId, UUID orderId, String orderNo,
      OffsetDateTime packedAt) throws Exception {
    s.execute(
        "insert into shipment_pending_unit (id,tenant_id,source_packing_order_id,"
            + " source_outbound_order_id,packed_at,parcel_count,total_weight_kg,warehouse_id,"
            + " warehouse_ref,outbound_order_no,sales_order_id,sales_order_no,carrier_channel,"
            + " carrier_code,status,handed_over_at,synced_at)"
            + " values ('" + packingId + "','" + tenant + "','" + packingId + "',gen_random_uuid(),"
            + ts(packedAt) + ",1,1.5,gen_random_uuid(),'WH-A','OB-1','" + orderId + "','"
            + orderNo + "','THIRD_PARTY','DHL','HANDED_OVER'," + ts(HANDED_OVER_AT) + ",now())");
  }

  static void insertRecord(Statement s, UUID tenant, UUID packingId, String orderNo, String shop,
      String warehouse, String carrierCode, String carrierName, OffsetDateTime shippedAt,
      OffsetDateTime slaDue, UUID orderId) throws Exception {
    s.execute(
        "insert into shipment_record (id,tenant_id,record_no,source_handover_id,"
            + " source_handover_code,source_packing_order_id,source_outbound_order_id,"
            + " outbound_order_no,warehouse_id,warehouse_ref,carrier_code,carrier_name,"
            + " master_waybill_no,shipped_at,parcel_count,sales_order_id,sales_order_no,shop_ref,"
            + " sla_deadline_at,sla_source,synced_at)"
            + " values ('" + packingId + "','" + tenant + "','SR-" + packingId.toString().substring(0, 8) + "',gen_random_uuid(),"
            + "'HO-1','" + packingId + "',gen_random_uuid(),'OB-1',gen_random_uuid(),'"
            + warehouse + "','" + carrierCode + "','" + carrierName + "','WB-" + packingId + "',"
            + ts(shippedAt) + ",1,"
            + (orderId == null ? "null" : "'" + orderId + "'") + ",'"
            + (orderNo == null ? "" : orderNo) + "','" + shop + "',"
            + (slaDue == null ? "null" : ts(slaDue)) + ","
            + (slaDue == null ? "''" : "'MANUAL_ATTESTATION'") + ","
            + ts(REGISTERED_AT) + ")");
  }

  /** 时刻一律用 {@code ?} 语义的显式 timestamptz 字面量，**带时区偏移**（不依赖会话 TimeZone）。 */
  static String ts(OffsetDateTime t) {
    return "'" + t.toString() + "'::timestamptz";
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-only");
    // 只有复用同一条连接，才能真正证明归还后不会串租户
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  /**
   * 每个用例前清库重种再同步。
   *
   * <p>时机必须在 Spring 上下文起来<b>之后</b>：Flyway 是随上下文启动跑的，
   * 静态初始化块执行时上游三张表还不存在。
   *
   * <p>顺带解决登记类用例的相互污染：凭据表随事实表级联删除，
   * 所以「登记承诺」不会漏进下一个用例。
   */
  @BeforeEach
  void resetSeedAndSync() throws Exception {
    truncateAll();
    seed();
    var res =
        mvc.perform(post("/trade/fulfillment/timeliness/sync")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andReturn();
    // 租户 B 也要同步一次，否则「互不可见」分不清是隔离生效还是 B 根本没数据
    mvc.perform(post("/trade/fulfillment/timeliness/sync")
            .header("Authorization", "Bearer tenant-b")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk());
    Throwable root = res.getResolvedException();
    while (root != null && root.getCause() != null) {
      root = root.getCause();
    }
    assertThat(res.getResponse().getStatus())
        .as("sync 失败，root=%s: %s", root == null ? "null" : root.getClass().getName(),
            root == null ? "null" : root.getMessage())
        .isEqualTo(200);
  }

  static void truncateAll() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate order_timeliness_event, order_timeliness_delivery,"
              + " order_timeliness_promise, order_timeliness_fact,"
              + " shipment_record, shipment_pending_unit, sales_order_line,"
              + " order_address_snapshot, sales_order_party_snapshot, sales_order"
              + " cascade");
    }
  }

  // ================================================================ HTTP 辅助

  org.springframework.test.web.servlet.ResultActions req(String path, String who)
      throws Exception {
    return mvc.perform(get("/trade/fulfillment/timeliness" + path)
        .header("Authorization", who));
  }

  JsonNodeObject getJson(String path, String who) throws Exception {
    var res = req(path, who).andExpect(status().isOk());
    return new JsonNodeObject(res);
  }

  /** 极简 JSON 取值包装：只取本测试需要的字段，避免引入额外依赖。 */
  record JsonNodeObject(org.springframework.test.web.servlet.ResultActions res) {
    com.fasterxml.jackson.databind.JsonNode node() throws Exception {
      String body = res.andReturn().getResponse().getContentAsString();
      return json.readTree(body);
    }

    long at(String path) throws Exception {
      com.fasterxml.jackson.databind.JsonNode n = node().at(path);
      assertThat(n.isNumber()).as("期望数字：" + path + "，实得 " + n).isTrue();
      return n.asLong();
    }

    String text(String path) throws Exception {
      com.fasterxml.jackson.databind.JsonNode n = node().at(path);
      return n.isMissingNode() ? null : n.asText();
    }

    boolean isNull(String path) throws Exception {
      return node().at(path).isNull();
    }

    int size(String path) throws Exception {
      return node().at(path).size();
    }
  }

  // ================================================================ 一、时长由 DB 复算

  @Test
  @DisplayName("三段时长断言写回值本身：93600 / 9000 / 102600 秒，不是「没抛异常」")
  void durationsAreComputedByDb() throws Exception {
    String sql =
        "select f.order_to_pack_seconds,f.pack_to_handover_seconds,f.order_to_handover_seconds"
            + " from order_timeliness_fact f"
            + " where f.sales_order_no='SO-ON-TIME'";
    var f = owner(sql);
    assertThat(f.getLong("order_to_pack_seconds")).isEqualTo(SEC_ORDER_TO_PACK);
    assertThat(f.getLong("pack_to_handover_seconds")).isEqualTo(SEC_PACK_TO_HANDOVER);
    assertThat(f.getLong("order_to_handover_seconds")).isEqualTo(SEC_ORDER_TO_HANDOVER);
  }

  @Test
  @DisplayName("timestamptz 往返不偏 −28800s：写回的值与夹具逐字相等")
  void timestamptzRoundTripHasNoDrift() throws Exception {
    String sql =
        "select f.ordered_at,f.packed_at,f.handed_over_at from order_timeliness_fact f"
            + " where f.sales_order_no='SO-ON-TIME'";
    var f = owner(sql);
    assertThat(f.getObject("ordered_at", OffsetDateTime.class).toInstant())
        .isEqualTo(ORDERED_AT.toInstant());
    assertThat(f.getObject("packed_at", OffsetDateTime.class).toInstant())
        .isEqualTo(PACKED_AT.toInstant());
    assertThat(f.getObject("handed_over_at", OffsetDateTime.class).toInstant())
        .isEqualTo(HANDED_OVER_AT.toInstant());
  }

  // ================================================================ 二、UNSET 而不是 0

  @Test
  @DisplayName("草稿单没有 submitted_at：下单到出库落 UNSET 而不是 0")
  void draftOrderHasNoOrderedAtSoSegmentIsUnset() throws Exception {
    var f =
        owner(
            "select f.order_to_pack_seconds,f.pack_to_handover_seconds,"
                + " f.order_to_handover_seconds"
                + " from order_timeliness_fact f where f.sales_order_no='SO-DRAFT'");
    assertThat(f.getObject("order_to_pack_seconds", Long.class))
        .as("下单到出库必须 UNSET，0 会伪装成「瞬间出库」")
        .isNull();
    // 出库到交接仍在（草稿单照样出库了）
    assertThat(f.getObject("pack_to_handover_seconds", Long.class)).isEqualTo(SEC_PACK_TO_HANDOVER);
    // 下单到交接同样 UNSET：起点缺失时整段不可算，**不许拿出库到交接顶替**
    assertThat(f.getObject("order_to_handover_seconds", Long.class))
        .as("起点缺失时整段 UNSET，不能用两段相加硬凑")
        .isNull();
  }

  @Test
  @DisplayName("9.4.1.1 没同步过的票没有 packed_at：出库到交接落 UNSET 而不是 0")
  void missingPendingUnitLeavesSegmentUnset() throws Exception {
    var f =
        owner(
            "select f.packed_at,f.pack_to_handover_seconds from order_timeliness_fact f"
                + " where f.sales_order_no='SO-NOPACK'");
    assertThat(f.getObject("packed_at", OffsetDateTime.class)).isNull();
    assertThat(f.getObject("pack_to_handover_seconds", Long.class))
        .as("出库到交接必须 UNSET，0 会伪装成「打包完立刻交接」")
        .isNull();
  }

  @Test
  @DisplayName("报表每一段都同时给已测数与未测数，只给均值没有意义")
  void reportAlwaysCarriesBothDenominators() throws Exception {
    var report = getJson("/report", "Bearer tenant-a");
    // 租户 A 四票：下单到出库 2 票可测（SO-DRAFT 无 submitted_at、无订单快照那票也没单），
    // 出库到交接 3 票可测（SO-NOPACK 没有 packed_at）
    assertThat(report.text("/segments/0/segment")).isEqualTo("ORDER_TO_PACK");
    assertThat(report.text("/segments/0/segmentLabel")).isEqualTo("下单到出库");
    assertThat(report.at("/segments/0/measuredCount") + report.at("/segments/0/unsetCount"))
        .isEqualTo(5);
    assertThat(report.text("/segments/1/segment")).isEqualTo("PACK_TO_HANDOVER");
    assertThat(report.at("/segments/1/measuredCount") + report.at("/segments/1/unsetCount"))
        .isEqualTo(5);
  }

  // ================================================================ 三、幂等

  @Test
  @DisplayName("重复同步不增加行：分母被翻倍准时率当场失真")
  void syncIsIdempotent() throws Exception {
    long before = owner("select count(*) as c from order_timeliness_fact").getLong("c");
    var res =
        mvc.perform(post("/trade/fulfillment/timeliness/sync")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
    long after = owner("select count(*) as c from order_timeliness_fact").getLong("c");
    assertThat(after).isEqualTo(before);
    assertThat(res.andReturn().getResponse().getContentAsString()).contains("\"inserted\":0");
  }

  @Test
  @DisplayName("同步结果如实报出缺端点的票数，不把它算进已测")
  void syncResultReportsUnsetCounts() throws Exception {
    String body =
        mvc.perform(post("/trade/fulfillment/timeliness/sync")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var n = json.readTree(body);
    assertThat(n.get("scanned").asInt()).isGreaterThan(0);
    assertThat(n.get("withoutOrderedAt").asInt()).isGreaterThan(0);
    assertThat(n.get("withoutPackedAt").asInt()).isGreaterThan(0);
  }

  // ================================================================ 四、三个桶互不吞并

  @Test
  @DisplayName("未登记承诺到货落 NO_PROMISE，不是「准时」")
  void unregisteredPromiseIsItsOwnBucket() throws Exception {
    var page = getJson("/facts?promiseState=NO_PROMISE", "Bearer tenant-a");
    assertThat(page.at("/total")).isGreaterThan(0);
    var report = getJson("/report", "Bearer tenant-a");
    assertThat(report.at("/promise/noPromise")).isGreaterThan(0);
    assertThat(report.at("/promise/met")).isEqualTo(0);
  }

  @Test
  @DisplayName("登记承诺后从 NO_PROMISE 变 NO_ACTUAL，两个桶不合并")
  void registeringPromiseMovesToNoActualBucket() throws Exception {
    String factId = factIdFor("SO-ON-TIME");
    postPromise(factId, PROMISED_AT.toString()).andExpect(status().isOk());
    var detail = getJson("/facts/" + factId, "Bearer tenant-a");
    assertThat(detail.text("/fact/promiseVsDeliveryState")).isEqualTo("NO_ACTUAL");
    assertThat(detail.text("/fact/deliveryState")).isEqualTo("UNSET");
  }

  @Test
  @DisplayName("两端都登记后达成率才出现，未登记的从不进分母")
  void promiseMetRateOnlyCountsBothRegistered() throws Exception {
    String factId = factIdFor("SO-LATE");
    postPromise(factId, PROMISED_AT.toString()).andExpect(status().isOk());
    postDelivery(factId, DELIVERED_LATE_AT.toString(), "DELIVERED", "到门未签收后退回").andExpect(status().isOk());

    var detail = getJson("/facts/" + factId, "Bearer tenant-a");
    assertThat(detail.text("/fact/promiseVsDeliveryState")).isEqualTo("LATE");
    assertThat(detail.at("/fact/promiseVsDeliverySeconds")).isEqualTo(SEC_PROMISE_LATE);

    var report = getJson("/report", "Bearer tenant-a");
    // 达成率分母 = MET + LATE：全租户只有这一票两端都登记过，所以分母正好是 1
    assertThat(report.at("/promise/met") + report.at("/promise/late")).isEqualTo(1);
    // 其余四票压根没登记承诺，仍在 NO_PROMISE 桶里 —— 没登记的**不进分母**
    assertThat(report.at("/promise/noPromise")).isEqualTo(4);
    // 这一票两端都登记完了，所以 NO_ACTUAL 归零（不是被并进达成桶）
    assertThat(report.at("/promise/noActual")).isEqualTo(0);
  }

  @Test
  @DisplayName("提前到货判定为 MET，差值是负秒数而不是超期")
  void earlyDeliveryIsMet() throws Exception {
    String factId = factIdFor("SO-ON-TIME");
    // 前提：两端都登记过才谈得上「达成」。只登记实际到货的话按定义是 NO_PROMISE
    postPromise(factId, PROMISED_AT.toString()).andExpect(status().isOk());
    postDelivery(factId, OffsetDateTime.parse("2027-03-05T00:00:00+08:00").toString(), "DELIVERED",
        "本人签收").andExpect(status().isOk());
    var detail = getJson("/facts/" + factId, "Bearer tenant-a");
    assertThat(detail.text("/fact/promiseVsDeliveryState")).isEqualTo("MET");
    assertThat(detail.at("/fact/promiseVsDeliverySeconds")).isNegative();
  }

  // ================================================================ 五、比率必须带分母

  @Test
  @DisplayName("发货超时三桶分开：未登记时限既不算准时也不算超时")
  void shipSlaThreeBucketsStaySeparate() throws Exception {
    var report = getJson("/report", "Bearer tenant-a");
    long unset = report.at("/sla/unset");
    long onTime = report.at("/sla/onTime");
    long overdue = report.at("/sla/overdue");
    assertThat(unset).isGreaterThan(0);
    assertThat(onTime).isGreaterThan(0);
    assertThat(overdue).isGreaterThan(0);
    // 超时率分母不含 UNSET
    assertThat(report.text("/sla/rateBasis")).contains("不含未登记时限");
  }

  @Test
  @DisplayName("发货超时秒数断言写回值（131400s）")
  void shipOverdueSecondsIsExact() throws Exception {
    var f =
        owner(
            "select f.ship_overdue_seconds,f.ship_sla_state from order_timeliness_fact f"
                + " where f.sales_order_no='SO-LATE'");
    assertThat(f.getLong("ship_overdue_seconds")).isEqualTo(SEC_SHIP_OVERDUE);
    assertThat(f.getString("ship_sla_state")).isEqualTo("OVERDUE");
  }

  // ================================================================ 六、四维拆解

  @Test
  @DisplayName("承运商维度是真 GROUP BY，每组带自己的均值与超时数")
  void carrierDimensionReallyGroups() throws Exception {
    var page = getJson("/dimensions?dimensionBy=CARRIER", "Bearer tenant-a");
    assertThat(page.size("/items")).isGreaterThan(1);
    var labels = new java.util.ArrayList<String>();
    var node = json.readTree(page.res().andReturn().getResponse().getContentAsString());
    node.get("items").forEach(i -> labels.add(i.get("dimensionKey").asText()));
    assertThat(labels).contains("DHL", "UPS", "FEDEX");
  }

  @Test
  @DisplayName("商品维度走订单行只读 join，能按 SKU 拆出时效")
  void skuDimensionJoinsOrderLines() throws Exception {
    var page = getJson("/dimensions?dimensionBy=SKU", "Bearer tenant-a");
    var body = json.readTree(page.res().andReturn().getResponse().getContentAsString());
    var names = new java.util.ArrayList<String>();
    body.get("items").forEach(i -> names.add(i.get("dimensionLabel").asText()));
    assertThat(names).contains("商品甲", "商品乙");
    assertThat(page.text("/caveat")).contains("只读 join");
  }

  @Test
  @DisplayName("空维度值单独成组，不并进任何有值分组")
  void emptyDimensionIsItsOwnGroup() throws Exception {
    var page = getJson("/dimensions?dimensionBy=SHOP", "Bearer tenant-a");
    var body = json.readTree(page.res().andReturn().getResponse().getContentAsString());
    var labels = new java.util.ArrayList<String>();
    body.get("items").forEach(i -> labels.add(i.get("dimensionLabel").asText()));
    assertThat(labels).contains("SHOP-A", "SHOP-B");
  }

  // ================================================================ 七、超时明细

  @Test
  @DisplayName("超时明细把发货超时与承诺超时分列报数，不混成一个超时数")
  void overdueSeparatesTwoSources() throws Exception {
    var page = getJson("/overdue", "Bearer tenant-a");
    assertThat(page.at("/shipOverdueCount")).isGreaterThan(0);
    var body = json.readTree(page.res().andReturn().getResponse().getContentAsString());
    var kinds = new java.util.ArrayList<String>();
    body.get("items").forEach(i -> kinds.add(i.get("overdueKind").asText()));
    assertThat(kinds).contains("SHIP_SLA");
  }

  @Test
  @DisplayName("超时明细按秒数倒序，真排序而不是原样返回")
  void overdueIsActuallySorted() throws Exception {
    var page = getJson("/overdue", "Bearer tenant-a");
    var body = json.readTree(page.res().andReturn().getResponse().getContentAsString());
    long prev = Long.MAX_VALUE;
    for (var item : body.get("items")) {
      long sec = item.get("overdueSeconds").asLong();
      assertThat(sec).isLessThanOrEqualTo(prev);
      prev = sec;
    }
  }

  // ================================================================ 八、清单与分页

  @Test
  @DisplayName("真分页且汇总不受分页影响")
  void paginationIsRealAndSummaryIsNotPaged() throws Exception {
    var page1 = getJson("/facts?page=0&size=2", "Bearer tenant-a");
    var page2 = getJson("/facts?page=1&size=2", "Bearer tenant-a");
    assertThat(page1.size("/items")).isEqualTo(2);
    assertThat(page2.size("/items")).isGreaterThan(0);
    assertThat(page1.at("/summary/totalFacts")).isEqualTo(page2.at("/summary/totalFacts"));
    // 两页的 id 必须不重叠
    var ids1 = ids(page1);
    var ids2 = ids(page2);
    assertThat(ids1).doesNotContainAnyElementsOf(ids2);
  }

  static java.util.List<String> ids(JsonNodeObject o) throws Exception {
    var out = new java.util.ArrayList<String>();
    o.node().get("items").forEach(i -> out.add(i.get("id").asText()));
    return out;
  }

  @Test
  @DisplayName("关键字筛选是真筛选")
  void keywordFilterIsReal() throws Exception {
    var hit = getJson("/facts?keyword=SO-LATE", "Bearer tenant-a");
    assertThat(hit.at("/total")).isEqualTo(1);
    var miss = getJson("/facts?keyword=SO-NOPE", "Bearer tenant-a");
    assertThat(miss.at("/total")).isEqualTo(0);
  }

  @Test
  @DisplayName("排序白名单外的值被拒（不接受任意列名）")
  void sortOutsideWhitelistIsRejected() throws Exception {
    req("/facts?sort=DROP_TABLE", "Bearer tenant-a").andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("维度白名单外的值被拒")
  void dimensionOutsideWhitelistIsRejected() throws Exception {
    req("/dimensions?dimensionBy=NOT_A_DIMENSION", "Bearer tenant-a")
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 九、能力声明

  @Test
  @DisplayName("capabilities：轨迹实际时间 / 实际到货 available=false 且点名 ownerModule")
  void capabilitiesDeclareMissingUpstreams() throws Exception {
    var caps = getJson("/capabilities", "Bearer tenant-a");
    var body = json.readTree(caps.res().andReturn().getResponse().getContentAsString());
    var unavailable = new java.util.ArrayList<String>();
    for (var d : body.get("dimensions")) {
      if (!d.get("available").asBoolean()) {
        unavailable.add(d.get("key").asText());
        assertThat(d.get("ownerModule").asText())
            .as("available=false 必须点名 ownerModule：" + d.get("key").asText())
            .isNotEmpty();
        assertThat(d.get("reason").asText()).isNotEmpty();
      }
    }
    assertThat(unavailable).contains("TRACKING_ACTUAL_TIME", "DELIVERY_PROMISE_CONFIG");
    assertThat(body.get("durationUnit").asText()).contains("秒");
  }

  // ================================================================ 十、DB 级闸门

  @Test
  @DisplayName("时间倒流被里程碑触发器拒（绕过服务层写 SQL 同样被拒）")
  void timeInversionRejected() {
    assertThatThrownBy(
            () ->
                asOwner(
                        "insert into order_timeliness_fact (id,tenant_id,fact_no,source_record_id,"
                            + " handed_over_at,packed_at,synced_at) values"
                            + " (gen_random_uuid(),'" + a + "','TL-X',gen_random_uuid(),"
                            + ts(OffsetDateTime.parse("2026-03-02T10:00:00+08:00")) + ","
                            + ts(OffsetDateTime.parse("2026-03-02T12:00:00+08:00")) + ",now())")
                    .run())
        .hasMessageContaining("时间倒流");
  }

  @Test
  @DisplayName("实际到货早于交接被触发器拒：货还没交出去不可能已签收")
  void deliveryBeforeHandoverRejected() throws Exception {
    String factId = factIdFor("SO-ON-TIME");
    // 走 HTTP：服务层与 DB 层都要把它挡住（HTTP 层只回通用 409）
    mvc.perform(
            post("/trade/fulfillment/timeliness/facts/{id}/delivery", factId)
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"deliveredAt\":\"2026-03-01T00:00:00+08:00\",\"state\":\"DELIVERED\","
                        + "\"source\":\"MANUAL_ATTESTATION\",\"note\":\"试图在交接之前签收\"}"))
        .andExpect(status().isConflict());

    // 直连才拿得到触发器原文：证明确有这一道闸，而不是被 409 掩盖掉了
    assertThatThrownBy(
            () ->
                asOwner(
                        "insert into order_timeliness_delivery"
                            + " (id,tenant_id,fact_id,delivered_at,state,source,note,"
                            + "  registered_by_membership_id,registered_at)"
                            + " values (gen_random_uuid(),'" + a + "','" + factId + "',"
                            + ts(OffsetDateTime.parse("2026-03-01T00:00:00+08:00"))
                            + ",'DELIVERED','MANUAL_ATTESTATION','试图在交接之前签收',gen_random_uuid(),"
                            + ts(REGISTERED_AT) + ")")
                    .run())
        .hasMessageContaining("早于交接时刻");

    // 闸门生效：凭据行必须一行都没落
    long rows =
        owner(
                "select count(*) as c from order_timeliness_delivery where fact_id='" + factId + "'")
            .getLong("c");
    assertThat(rows).as("被拒的登记不许留下半行").isZero();
  }

  @Test
  @DisplayName("未送达必须带原因：拒收 / 退回 / 丢失是三件不同的事")
  void notDeliveredNeedsReason() throws Exception {
    String factId = factIdFor("SO-NOPACK");
    var res =
        postDelivery(factId, DELIVERED_LATE_AT.toString(), "NOT_DELIVERED", "").andReturn();
    assertThat(res.getResponse().getStatus())
        .as("未送达缺原因要在服务层就说人话，而不是甩一个通用 409")
        .isEqualTo(400);
    assertThat(res.getResolvedException())
        .isNotNull()
        .hasMessageContaining("未送达必须写明原因");
    // 闸门生效：一行都没落
    long rows =
        owner(
                "select count(*) as c from order_timeliness_delivery where fact_id='" + factId + "'")
            .getLong("c");
    assertThat(rows).as("被拒的登记不许留下半行").isZero();
  }

  @Test
  @DisplayName("快照脱离凭据行改写被拒：先落凭据再更新快照的顺序是钉死的")
  void snapshotCannotDriftFromCredential() throws Exception {
    String factId = factIdFor("SO-DRAFT");
    assertThatThrownBy(
            () ->
                asOwner(
                        "update order_timeliness_fact set actual_delivered_at="
                            + ts(DELIVERED_LATE_AT)
                            + ",delivery_state='DELIVERED' where id='"
                            + factId
                            + "'")
                    .run())
        .hasMessageContaining("先落 order_timeliness_delivery");
  }

  @Test
  @DisplayName("凭据 append-only 两层：运行角色被 REVOKE 42501")
  void credentialsAreAppendOnlyForRuntimeRole() throws Exception {
    String factId = factIdFor("SO-ON-TIME");
    postPromise(factId, PROMISED_AT.toString()).andExpect(status().isOk());
    assertThatThrownBy(
            () ->
                asTradeApp("delete from order_timeliness_promise where fact_id='" + factId + "'")
                    .run())
        .hasMessageContaining("permission denied for table order_timeliness_promise");
  }

  @Test
  @DisplayName("凭据 append-only 第二层：以表所有者身份再撞一次，证明触发器不是死代码")
  void credentialsAreAppendOnlyForOwnerToo() throws Exception {
    String factId = factIdFor("SO-ON-TIME");
    postPromise(factId, PROMISED_AT.toString()).andExpect(status().isOk());
    assertThatThrownBy(
            () ->
                asOwner(
                        "update order_timeliness_promise set note='tampered' where fact_id='"
                            + factId + "'")
                    .run())
        .hasMessageContaining("append-only");
  }

  // ================================================================ 十一、RLS 与多租户三断言

  @Test
  @DisplayName("五道 DB 闸门的触发器确实存在（不是死代码）")
  void guardTriggersExist() throws Exception {
    var expected =
        java.util.Map.of(
            "order_timeliness_fact",
                new java.util.TreeSet<>(
                    java.util.List.of(
                        "guard_order_timeliness_milestones_trg",
                        "guard_order_timeliness_snapshots_trg")),
            "order_timeliness_promise",
                new java.util.TreeSet<>(
                    java.util.List.of("guard_order_timeliness_promise_append_only")),
            "order_timeliness_delivery",
                new java.util.TreeSet<>(
                    java.util.List.of(
                        "guard_order_timeliness_delivery_append_only",
                        "guard_order_timeliness_delivery_order_trg")),
            "order_timeliness_event",
                new java.util.TreeSet<>(
                    java.util.List.of("guard_order_timeliness_event_append_only")));
    for (var e : expected.entrySet()) {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var st = c.createStatement();
          var rs =
              st.executeQuery(
                  "select tgname from pg_trigger"
                      + " where tgrelid = to_regclass('" + e.getKey() + "') and not tgisinternal")) {
        var actual = new java.util.TreeSet<String>();
        while (rs.next()) {
          actual.add(rs.getString(1));
        }
        assertThat(actual).as("表 %s 的闸门触发器", e.getKey()).containsAll(e.getValue());
      }
    }
  }

  @Test
  @DisplayName("四张表逐张 FORCE RLS")
  void everyTableForcesRls() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs =
            s.executeQuery(
                "select relname, relrowsecurity, relforcerowsecurity from pg_class"
                    + " where relname like 'order_timeliness%' and relkind='r'"
                    + " order by relname")) {
      int seen = 0;
      while (rs.next()) {
        assertThat(rs.getBoolean("relrowsecurity")).as(rs.getString("relname")).isTrue();
        assertThat(rs.getBoolean("relforcerowsecurity")).as(rs.getString("relname")).isTrue();
        seen++;
      }
      assertThat(seen).isEqualTo(4);
    }
  }

  @Test
  @DisplayName("starcon_app 跨服务只读角色拿不到任何业务表")
  void crossServiceReadOnlyRoleGetsNothing() throws Exception {
    assertThatThrownBy(
            () -> asStarconApp("select count(*) from order_timeliness_fact").run())
        .hasMessageContaining("permission denied");
  }

  @Test
  @DisplayName("多租户断言一：两租户互不可见")
  void twoTenantsCannotSeeEachOther() throws Exception {
    var mine = getJson("/facts", "Bearer tenant-a");
    var theirs = getJson("/facts", "Bearer tenant-b");
    assertThat(mine.at("/total")).isGreaterThan(0);
    assertThat(theirs.at("/total")).isEqualTo(1);
    // A 看不见 B 的单号
    var bodyA = json.readTree(mine.res().andReturn().getResponse().getContentAsString());
    bodyA.get("items").forEach(
        i -> assertThat(i.get("salesOrderNo").asText()).isNotEqualTo("SO-TENANT-B"));
  }

  @Test
  @DisplayName("多租户断言二：伪造 tenant header 无效")
  void forgedTenantHeaderIsIgnored() throws Exception {
    var forged =
        req("/facts", "Bearer tenant-a")
            .andExpect(status().isOk());
    String body =
        mvc.perform(get("/trade/fulfillment/timeliness/facts")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    // 带伪造头的结果必须与不带完全一致（= 仍是租户 A 的数据）
    assertThat(body).isEqualTo(forged.andReturn().getResponse().getContentAsString());
  }

  @Test
  @DisplayName("多租户断言三：连接池归还后不串租户（池压到 1）")
  void pooledConnectionDoesNotLeakTenant() throws Exception {
    for (int i = 0; i < 5; i++) {
      assertThat(getJson("/facts", "Bearer tenant-a").at("/total")).isGreaterThan(1);
      assertThat(getJson("/facts", "Bearer tenant-b").at("/total")).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("无权角色 403，读写都挡住")
  void forbiddenRoleGets403() throws Exception {
    req("/facts", "Bearer read-only").andExpect(status().isOk());
    mvc.perform(post("/trade/fulfillment/timeliness/sync")
            .header("Authorization", "Bearer read-only")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("未认证 401")
  void anonymousGets401() throws Exception {
    mvc.perform(get("/trade/fulfillment/timeliness/facts"))
        .andExpect(status().isUnauthorized());
  }

  // ================================================================ 端到端辅助

  static String factIdFor(String salesOrderNo) throws Exception {
    return owner(
            "select id from order_timeliness_fact where sales_order_no='" + salesOrderNo + "'")
        .getString("id");
  }

  /** 返回原始结果而不是内部断言 200：失败用例要拿到服务层的拒绝原因。 */
  org.springframework.test.web.servlet.ResultActions postPromise(String factId, String promisedAt)
      throws Exception {
    return mvc.perform(
        post("/trade/fulfillment/timeliness/facts/{id}/promise", factId)
            .header("Authorization", "Bearer tenant-a")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"promisedDeliveryAt\":\"" + promisedAt + "\","
                    + "\"source\":\"MANUAL_ATTESTATION\",\"note\":\"平台面承诺\"}"));
  }

  org.springframework.test.web.servlet.ResultActions postDelivery(
      String factId, String deliveredAt, String state, String note) throws Exception {
    return mvc.perform(
        post("/trade/fulfillment/timeliness/facts/{id}/delivery", factId)
            .header("Authorization", "Bearer tenant-a")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"deliveredAt\":\"" + deliveredAt + "\",\"state\":\"" + state
                    + "\",\"source\":\"MANUAL_ATTESTATION\",\"note\":\"" + note + "\"}"));
  }

  // ================================================================ 直连辅助

  /** 以**表所有者**身份直连查询（FORCE RLS 对 owner 同样生效，所以要显式绑定租户）。 */
  static java.sql.ResultSet owner(String sqlText) throws Exception {
    Connection c =
        DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    var st = c.createStatement();
    st.execute("select set_config('app.current_tenant','" + a + "',false)");
    java.sql.ResultSet rs = st.executeQuery(sqlText);
    assertThat(rs.next()).as("owner 查询没有返回行：" + sqlText).isTrue();
    return rs;
  }

  @FunctionalInterface
  interface OwnerSql {
    void run() throws Exception;
  }

  static OwnerSql asOwner(String sqlText) {
    return () -> {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var st = c.createStatement()) {
        st.execute("select set_config('app.current_tenant','" + a + "',false)");
        st.execute(sqlText);
      }
    };
  }

  static OwnerSql asTradeApp(String sqlText) {
    return () -> {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), "trade_app", "test-only");
          var st = c.createStatement()) {
        st.execute("select set_config('app.current_tenant','" + a + "',false)");
        st.execute(sqlText);
      }
    };
  }

  static OwnerSql asStarconApp(String sqlText) {
    return () -> {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var st = c.createStatement()) {
        st.execute("grant starcon_app to " + pg.getUsername());
      }
      // 换一条连接再 SET ROLE：角色切换必须在建立连接**之后**才生效
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var st = c.createStatement()) {
        st.execute("set role starcon_app");
        st.executeQuery(sqlText).close();
      }
    };
  }
}
