package com.starcon.trade;

import static com.starcon.trade.OrderChangeCancelModels.*;
import static org.assertj.core.api.Assertions.*;
// AssertJ 3.27 没有 assertThrows（它只有 catchThrowable / assertThatThrownBy），
// 这里要拿到异常对象本身来断言 HTTP 状态码，所以显式静态导入 JUnit 5 的那一个。
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 9.1.1.3 订单变更与取消。
 *
 * <p>本测试是 <b>V119 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论， 逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不只是直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>蓝图三条硬规则各自单列一节</b>：
 *
 * <ol>
 *   <li><b>已发货数量不可取消</b>（服务层拒绝 + 绕过服务层写 SQL 也被挡 + 边界值 6 = 10−4 可过）；
 *   <li><b>取消前必须先确认仓库停止作业并释放占用</b>（两个阶段都 COMPLETED 才允许确认， 且确认被拒时逐条报出还差什么）；
 *   <li><b>仅在责任方确认后记录取消终态</b>（申请阶段 cancelledQty 恒 0， 确认那一刻终态量与退款金额一并落库，且时间戳零漂移）。
 * </ol>
 *
 * <p>另含<b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderChangeCancelIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * 本模块的权限码是 trade.order-change.*，但夹具要经 **9.1.2.2 的真实写端点**建单 （本模块读的是真写入者的产出），所以 actor 还要有
   * trade.orders.* —— 与 SalesOrderOverviewIntegrationTest 同一套授权，不为了图省事绕过别人的写入口。
   */
  static final List<String> allPermissions =
      java.util.stream.Stream.of("order-change", "orders")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
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
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, List.of(grant("ALL", List.of("trade.order-change.read")))));
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

  @Autowired OrderChangeCancelService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.order-change.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.order-change.update", work);
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return tenant.equals(a) ? asA(work) : asB(work);
  }

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate order_change_event, order_platform_writeback_task,"
              + " order_warehouse_hold_task, order_cancellation_line, order_cancellation_request,"
              + " order_change_address_field, order_change_request_line, order_change_request,"
              + " order_fulfillment_line_progress, order_platform_change_rule,"
              + " sales_order_status_history, order_line_attribute_snapshot, order_charge,"
              + " order_address_snapshot, sales_order_party_snapshot, sales_order_line,"
              + " sales_order cascade");
    }
  }

  // ==========================================================================
  // 夹具：经 9.1.2.2 的**真实写端点**建手工单 —— 本模块读的是真写入者的产出
  // ==========================================================================

  static final UUID partyOrg = UUID.randomUUID();

  /** 一张已提交的手工单（SUBMITTED 之后订单行与地址快照被 V99 冻住）。 */
  String manualOrder(String orderNo, String qty, String unitPrice) throws Exception {
    String id = postOrder(orderNo, qty, unitPrice);
    http.perform(
            post("/trade/sales-orders/{id}/submit", id).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    return id;
  }

  String postOrder(String orderNo, String qty, String unitPrice) throws Exception {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("orderNo", orderNo);
    m.put("externalOrderNo", "PO-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
    m.put("manualKind", "B2B");
    m.put("currency", "USD");
    m.put("paymentMethod", "CREDIT_TERM");
    m.put("paymentTermsDays", 30);
    m.put("note", "线下 B2B 订单");
    m.put(
        "customer",
        Map.of(
            "customerPartyId", partyOrg.toString(),
            "customerCode", "CUST-001",
            "customerName", "Acme Trading Co.",
            "customerType", "ORGANIZATION",
            "customerDefaultCurrency", "USD"));
    Map<String, Object> l = new LinkedHashMap<>();
    l.put("skuId", UUID.randomUUID().toString());
    l.put("skuCode", "SKU-BLUE-M");
    l.put("skuName", "蓝色中码衬衫");
    l.put("specSnapshot", "蓝色 / M");
    l.put("skuMappingVersion", "map-v3");
    l.put("quantity", qty);
    l.put("unitPrice", unitPrice);
    l.put("discountAmount", "0.00");
    l.put("taxAmount", "0.00");
    l.put("note", "");
    m.put("lines", List.of(l));
    Map<String, Object> shipping = new LinkedHashMap<>();
    shipping.put("addressRole", "SHIPPING");
    shipping.put("recipientName", "Jane Doe");
    shipping.put("recipientPhone", "+1-555-0100");
    shipping.put("recipientEmail", "jane@acme.example");
    shipping.put("companyName", "Acme Trading Co.");
    shipping.put("countryCode", "US");
    shipping.put("province", "CA");
    shipping.put("city", "Los Angeles");
    shipping.put("district", "");
    shipping.put("postcode", "90001");
    shipping.put("addressLine1", "100 Main St");
    shipping.put("addressLine2", "");
    shipping.put("taxId", "");
    shipping.put("note", "");
    m.put("addresses", List.of(shipping));
    String resp =
        http.perform(
                post("/trade/sales-orders")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(m)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(resp).get("id").asText();
  }

  UUID lineIdOf(UUID orderId) {
    return asA(
        () ->
            sql.fetchOne(
                    "select id from sales_order_line where order_id = ? order by line_no", orderId)
                .get("id", UUID.class));
  }

  /** 平台单夹具：9.1.2.1 未交付，所以直接按 V99 的词表落一行 order_source='AMAZON'。 */
  UUID platformOrder(String orderNo) {
    return platformOrder(orderNo, 0);
  }

  /**
   * @param ageHours 下单距今多少小时。可改窗口判定读的是「下单时刻」， 所以测窗口必须能把下单时间挪到过去，而不是靠测试跑得够慢。
   */
  UUID platformOrder(String orderNo, int ageHours) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    asA(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,"
                  + "external_order_no,currency,payment_method,payment_terms_days,status,"
                  + "submitted_at,subtotal_amount,discount_amount,tax_amount,shipping_amount,"
                  + "other_amount,grand_total_amount,note,version,created_at,updated_at)"
                  + " values(?,?,?,'AMAZON','',?,'USD','PREPAID',0,'DRAFT',null,0,0,0,0,0,0,'',0,"
                  + "?::timestamptz,?::timestamptz)",
              orderId,
              actorA.tenantId(),
              orderNo,
              "AMZ-" + UUID.randomUUID().toString().substring(0, 8),
              OffsetDateTime.now().minusHours(ageHours),
              OffsetDateTime.now());
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?,?,?,1,?,?,?,10,9.9900,0,0,99.9000,'',?::timestamptz)",
              lineId,
              actorA.tenantId(),
              orderId,
              UUID.randomUUID(),
              "SKU-BLUE-M",
              "蓝色中码衬衫",
              OffsetDateTime.now());
          return null;
        });
    return orderId;
  }

  UUID platformLineId(UUID orderId) {
    return asA(
        () ->
            sql.fetchOne("select id from sales_order_line where order_id = ?", orderId)
                .get("id", UUID.class));
  }

  /** 默认对全部店铺生效（shop_code='*'），与测试里请求的 SHOP-1 对得上。 */
  void rule(String kind, boolean allowed, String deadlineHours, String maxDecrease) {
    rule("*", kind, allowed, deadlineHours, maxDecrease);
  }

  void rule(
      String shopCode, String kind, boolean allowed, String deadlineHours, String maxDecrease) {
    asA(
        () -> {
          sql.execute(
              "insert into order_platform_change_rule(id,tenant_id,platform_code,shop_code,"
                  + "change_kind,allowed,deadline_hours,max_decrease_qty,requires_platform_ack,"
                  + "effective_from,notes,version,created_by_membership_id,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,?,?,false,?::timestamptz,'',0,?,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              actorA.tenantId(),
              "AMAZON",
              shopCode,
              kind,
              allowed,
              deadlineHours == null ? null : new BigDecimal(deadlineHours),
              maxDecrease == null ? null : new BigDecimal(maxDecrease),
              OffsetDateTime.now(),
              UUID.randomUUID(),
              OffsetDateTime.now(),
              OffsetDateTime.now());
          return null;
        });
  }

  void recordProgress(
      UUID orderId, UUID lineId, String reserved, String shipped, OffsetDateTime observed) {
    BigDecimal shippedQty = new BigDecimal(shipped);
    asA(
        () -> {
          service.recordProgress(
              new OrderChangeCancelProgressInput(
                  orderId,
                  lineId,
                  new BigDecimal(reserved),
                  BigDecimal.ZERO,
                  shippedQty,
                  shippedQty.signum() == 0 ? null : observed,
                  OrderProgressSource.TENANT_MAINTAINED,
                  observed),
              UUID.randomUUID());
          return null;
        });
  }

  OrderChangeCancelCancellationInput cancellationInput(
      UUID orderId, UUID lineId, String qty, List<String> warehouses) {
    return new OrderChangeCancelCancellationInput(
        orderId,
        OrderCancelScope.PARTIAL_LINES,
        "买家临时改主意",
        null,
        null,
        warehouses,
        List.of(
            new OrderChangeCancelCancellationLineInput(lineId, new BigDecimal(qty), "RSV-001")));
  }

  // ==========================================================================
  // 一、已发货数量不可取消，只能转售后（蓝图加粗硬规则）
  // ==========================================================================

  @Test
  void 已发货的行_cancel申请被拒_并逐行报出可取消量与转售后归属() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-SHIP-1", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    recordProgress(orderId, lineId, "0", "4", OffsetDateTime.now());

    // 申请取消 8 件 > 可取消 6 件（10 − 4）
    var thrown =
        assertThrows(
            ResponseStatusException.class,
            () ->
                asA(
                    () ->
                        service.createCancellation(
                            cancellationInput(orderId, lineId, "8", List.of("WH-CN-SH")),
                            UUID.randomUUID())));
    assertThat(thrown.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(thrown.getReason())
        .as("必须逐行报出已发多少、最多还能取消多少、该转售后")
        .contains("已发货 4")
        .contains("最多只能取消 6")
        .contains("只能转售后")
        .contains("11");
  }

  @Test
  void 已发货的行_cancel恰好等于未发货量_可以申请() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-SHIP-2", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    recordProgress(orderId, lineId, "0", "4", OffsetDateTime.now());

    // 边界：10 − 4 = 6 件恰好可取消
    var row =
        asA(
            () ->
                service.createCancellation(
                    cancellationInput(orderId, lineId, "6", List.of("WH-CN-SH")),
                    UUID.randomUUID()));
    assertThat(row.status()).isEqualTo(OrderChangeStatus.REQUESTED);
  }

  @Test
  void 全部已发货的行_完全不能取消_可取消量为零() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-SHIP-3", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    recordProgress(orderId, lineId, "0", "10", OffsetDateTime.now());

    var thrown =
        assertThrows(
            ResponseStatusException.class,
            () ->
                asA(
                    () ->
                        service.createCancellation(
                            cancellationInput(orderId, lineId, "1", List.of("WH-CN-SH")),
                            UUID.randomUUID())));
    assertThat(thrown.getReason()).contains("最多只能取消 0");
  }

  @Test
  void 绕过服务层直接写SQL取消已发货数量_同样被DB守卫挡下() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-SHIP-4", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    recordProgress(orderId, lineId, "0", "4", OffsetDateTime.now());
    UUID cancelId =
        asA(
            () ->
                service
                    .createCancellation(
                        cancellationInput(orderId, lineId, "2", List.of("WH-CN-SH")),
                        UUID.randomUUID())
                    .id());

    var thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into order_cancellation_line(id,tenant_id,"
                              + "cancellation_request_id,order_line_id,ordered_qty,"
                              + "shipped_qty_at_request,cancel_qty,cancelled_qty,unit_price,"
                              + "refund_amount,created_at)"
                              + " values(?,?,?,?,10,0,9,0,9.9900,0,?::timestamptz)",
                          UUID.randomUUID(),
                          actorA.tenantId(),
                          cancelId,
                          lineId,
                          OffsetDateTime.now());
                      return null;
                    }));
    assertThat(rootMessage(thrown))
        .as("DB 层也要挡住：绕过服务层写 SQL 不能把已发货数量写成可取消")
        .contains("不能取消")
        .contains("转售后");
  }

  // ==========================================================================
  // 二、取消前必须先确认仓库停止作业并释放占用
  // ==========================================================================

  @Test
  void 建取消单时同步下达停止作业与释放占用两个阶段的任务() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-HOLD-1", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    UUID cancelId =
        asA(
            () ->
                service
                    .createCancellation(
                        cancellationInput(orderId, lineId, "3", List.of("WH-CN-SH")),
                        UUID.randomUUID())
                    .id());

    var tasks = asA(() -> service.holdTasks(cancelId));
    assertThat(tasks)
        .as("蓝图要求「先确认仓库停止作业**并**释放占用」，两个阶段都必须先下达")
        .extracting(OrderChangeCancelHoldTaskRow::taskStage)
        .containsExactlyInAnyOrder(
            OrderHoldTaskStage.STOP_WORK, OrderHoldTaskStage.RELEASE_OCCUPANCY);
    assertThat(tasks).allMatch(t -> t.status() == OrderHoldTaskStatus.PENDING);
    assertThat(tasks).allMatch(t -> "7.2.1.3".equals(t.ownerModule()));
    assertThat(tasks).allMatch(t -> t.targetApi().contains("reservations"));
  }

  @Test
  void 仓库任务未完成时确认取消被拒_并逐条报出还差什么() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-HOLD-2", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    UUID cancelId =
        asA(
            () ->
                service
                    .createCancellation(
                        cancellationInput(orderId, lineId, "3", List.of("WH-CN-SH")),
                        UUID.randomUUID())
                    .id());

    var blockers = asA(() -> service.blockers(cancelId));
    assertThat(blockers)
        .as("必须逐条报出卡在哪，而不是只给一个 REQUESTED")
        .hasSizeGreaterThanOrEqualTo(2)
        .allMatch(b -> "7.2.1.3".equals(b.ownerModule()));

    var thrown =
        assertThrows(
            ResponseStatusException.class,
            () ->
                asA(
                    () ->
                        service.advanceCancellation(
                            cancelId,
                            new OrderChangeCancelStatusInput(OrderChangeStatus.CONFIRMED, null),
                            UUID.randomUUID())));
    assertThat(thrown.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(thrown.getReason())
        .as("提示要指名是哪一项卡住，而不是只说「未完成」")
        .contains("仓库 WH-CN-SH")
        .contains("未完成");
    assertThat(blockers)
        .as("两个阶段的阻塞项都要逐条列出")
        .anyMatch(b -> b.description().contains("停止作业"))
        .anyMatch(b -> b.description().contains("释放占用"));
  }

  @Test
  void 只完成停止作业仍然不能确认_释放占用也完成才行() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-HOLD-3", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    UUID cancelId =
        asA(
            () ->
                service
                    .createCancellation(
                        cancellationInput(orderId, lineId, "3", List.of("WH-CN-SH")),
                        UUID.randomUUID())
                    .id());
    completeTask(cancelId, OrderHoldTaskStage.STOP_WORK);

    assertThrows(
        ResponseStatusException.class,
        () ->
            asA(
                () ->
                    service.advanceCancellation(
                        cancelId,
                        new OrderChangeCancelStatusInput(OrderChangeStatus.CONFIRMED, null),
                        UUID.randomUUID())));
    assertThat(asA(() -> service.oneCancellation(cancelId)).status())
        .as("被拒的确认不得留下半成品状态")
        .isEqualTo(OrderChangeStatus.REQUESTED);

    completeTask(cancelId, OrderHoldTaskStage.RELEASE_OCCUPANCY);
    var confirmed =
        asA(
            () ->
                service.advanceCancellation(
                    cancelId,
                    new OrderChangeCancelStatusInput(OrderChangeStatus.CONFIRMED, null),
                    UUID.randomUUID()));
    assertThat(confirmed.status()).isEqualTo(OrderChangeStatus.CONFIRMED);
  }

  void completeTask(UUID cancelId, OrderHoldTaskStage stage) {
    var task =
        asA(
            () ->
                service.holdTasks(cancelId).stream()
                    .filter(t -> t.taskStage() == stage)
                    .findFirst()
                    .orElseThrow());
    asA(
        () ->
            service.updateHoldTask(
                cancelId,
                task.id(),
                new OrderChangeCancelHoldTaskInput(
                    OrderHoldTaskStatus.COMPLETED, task.targetQuantity(), "仓库已停止作业并释放占用", ""),
                UUID.randomUUID()));
  }

  @Test
  void 绕过服务层先把取消单置为已确认_同样被DB守卫挡下() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-HOLD-4", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    UUID cancelId =
        asA(
            () ->
                service
                    .createCancellation(
                        cancellationInput(orderId, lineId, "3", List.of("WH-CN-SH")),
                        UUID.randomUUID())
                    .id());

    var thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update order_cancellation_request set status='CONFIRMED'"
                              + " where id = ?",
                          cancelId);
                      return null;
                    }));
    assertThat(rootMessage(thrown)).contains("没有 COMPLETED").contains("先确认仓库停止作业");
  }

  // ==========================================================================
  // 三、仅在责任方确认后才记录取消终态
  // ==========================================================================

  @Test
  void 申请阶段取消终态量恒为零_而不是申请量() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-TERM-1", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    UUID cancelId =
        asA(
            () ->
                service
                    .createCancellation(
                        cancellationInput(orderId, lineId, "3", List.of("WH-CN-SH")),
                        UUID.randomUUID())
                    .id());

    var detail = asA(() -> service.cancellationDetail(cancelId));
    var line = detail.lines().get(0);
    assertThat(line.cancelQuantity()).isEqualByComparingTo("3");
    assertThat(line.cancelledQuantity())
        .as("终态量必须为 0：仓库一个货都没放，不能显示已取消 3 件")
        .isEqualByComparingTo("0");
    assertThat(line.refundAmount()).isEqualByComparingTo("0");
    assertThat(detail.header().refundAmount())
        .as("申请阶段的退款合计是预估值，确认真值由 confirm 函数落")
        .isEqualByComparingTo("29.9700");
  }

  @Test
  void 确认那一刻才落终态数量与退款金额_且时间戳零漂移() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-TERM-2", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    OffsetDateTime observed = OffsetDateTime.now().withOffsetSameInstant(ZoneOffset.ofHours(8));
    recordProgress(orderId, lineId, "10", "0", observed);
    UUID cancelId =
        asA(
            () ->
                service
                    .createCancellation(
                        cancellationInput(orderId, lineId, "3", List.of("WH-CN-SH")),
                        UUID.randomUUID())
                    .id());
    completeTask(cancelId, OrderHoldTaskStage.STOP_WORK);
    completeTask(cancelId, OrderHoldTaskStage.RELEASE_OCCUPANCY);
    asA(
        () ->
            service.advanceCancellation(
                cancelId,
                new OrderChangeCancelStatusInput(OrderChangeStatus.CONFIRMED, "责任方已全部确认"),
                UUID.randomUUID()));

    var detail = asA(() -> service.cancellationDetail(cancelId));
    var line = detail.lines().get(0);
    assertThat(line.cancelledQuantity()).isEqualByComparingTo("3");
    assertThat(line.refundAmount())
        .as("退款 = 单价 9.99 × 3，BigDecimal 精确到分")
        .isEqualByComparingTo("29.9700");
    assertThat(detail.header().refundAmount()).isEqualByComparingTo("29.9700");
    assertThat(detail.header().decidedAt()).isNotNull();

    // timestamptz 往返零漂移：写进去的观测时刻读回来必须是同一瞬间
    var progress = asA(() -> service.progress(orderId));
    assertThat(progress.get(0).observedAt().toInstant())
        .as("OffsetDateTime 绑 timestamptz 必须零漂移（Timestamp.valueOf 会偏 −28800s）")
        .isEqualTo(observed.toInstant());

    // 时间线上确有 CONFIRMED 事件
    assertThat(detail.timeline())
        .anyMatch(e -> "CONFIRMED".equals(e.eventType()) && "REQUESTED".equals(e.fromStatus()));
  }

  @Test
  void 绕过服务层在确认前写终态数量_同样被DB守卫挡下() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-TERM-3", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    UUID cancelId =
        asA(
            () ->
                service
                    .createCancellation(
                        cancellationInput(orderId, lineId, "3", List.of("WH-CN-SH")),
                        UUID.randomUUID())
                    .id());
    UUID cancelLineId =
        asA(
            () ->
                sql.fetchOne(
                        "select id from order_cancellation_line where cancellation_request_id = ?",
                        cancelId)
                    .get("id", UUID.class));

    var thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update order_cancellation_line set cancelled_qty = cancel_qty,"
                              + " refund_amount = round(unit_price*cancel_qty,4) where id = ?",
                          cancelLineId);
                      return null;
                    }));
    assertThat(rootMessage(thrown)).contains("终态数量必须为 0");
  }

  @Test
  void 终态之后的取消单不可再改状态() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-TERM-4", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    UUID cancelId =
        asA(
            () ->
                service
                    .createCancellation(
                        cancellationInput(orderId, lineId, "3", List.of("WH-CN-SH")),
                        UUID.randomUUID())
                    .id());
    completeTask(cancelId, OrderHoldTaskStage.STOP_WORK);
    completeTask(cancelId, OrderHoldTaskStage.RELEASE_OCCUPANCY);
    asA(
        () ->
            service.advanceCancellation(
                cancelId,
                new OrderChangeCancelStatusInput(OrderChangeStatus.CONFIRMED, null),
                UUID.randomUUID()));

    assertThrows(
        ResponseStatusException.class,
        () ->
            asA(
                () ->
                    service.advanceCancellation(
                        cancelId,
                        new OrderChangeCancelStatusInput(OrderChangeStatus.REJECTED, "反悔"),
                        UUID.randomUUID())));
  }

  @Test
  void 拒绝必须给出原因_没有原因的拒绝事后没人能追() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-TERM-5", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    UUID cancelId =
        asA(
            () ->
                service
                    .createCancellation(
                        cancellationInput(orderId, lineId, "3", List.of("WH-CN-SH")),
                        UUID.randomUUID())
                    .id());
    assertThrows(
        ResponseStatusException.class,
        () ->
            asA(
                () ->
                    service.advanceCancellation(
                        cancelId,
                        new OrderChangeCancelStatusInput(OrderChangeStatus.REJECTED, ""),
                        UUID.randomUUID())));
  }

  @Test
  void 同一件货不能既已发货又已取消() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-TERM-6", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    UUID cancelId =
        asA(
            () ->
                service
                    .createCancellation(
                        cancellationInput(orderId, lineId, "3", List.of("WH-CN-SH")),
                        UUID.randomUUID())
                    .id());
    completeTask(cancelId, OrderHoldTaskStage.STOP_WORK);
    completeTask(cancelId, OrderHoldTaskStage.RELEASE_OCCUPANCY);
    asA(
        () ->
            service.advanceCancellation(
                cancelId,
                new OrderChangeCancelStatusInput(OrderChangeStatus.CONFIRMED, null),
                UUID.randomUUID()));

    // 已确认取消 3 件后再记发货 8 件（合计 11 > 10）必须被挡
    assertThrows(
        RuntimeException.class,
        () -> recordProgress(orderId, lineId, "0", "8", OffsetDateTime.now()));
    // 边界：发 7 件（3 + 7 = 10）应当通过
    recordProgress(orderId, lineId, "0", "7", OffsetDateTime.now());
    assertThat(asA(() -> service.progress(orderId)).get(0).shippedQuantity())
        .isEqualByComparingTo("7");
  }

  // ==========================================================================
  // 四、数量调整需平台允许
  // ==========================================================================

  @Test
  void 平台单没有登记可变更规则时_申请被拒而不是默认放行() {
    UUID orderId = platformOrder("PLAT-NO-RULE");
    var thrown =
        assertThrows(
            ResponseStatusException.class,
            () ->
                asA(
                    () ->
                        service.createChange(
                            new OrderChangeCancelChangeInput(
                                orderId,
                                OrderChangeKind.QUANTITY_DECREASE,
                                "买家要求少买一件",
                                "AMAZON",
                                "SHOP-1",
                                List.of(
                                    new OrderChangeCancelChangeLineInput(
                                        platformLineId(orderId), new BigDecimal("9"))),
                                null),
                            UUID.randomUUID())));
    assertThat(thrown.getReason()).as("没有规则不等于允许").contains("没有登记").contains("不能靠代码里的默认值猜");
  }

  @Test
  void 平台规则不允许减量时_申请被拒() {
    UUID orderId = platformOrder("PLAT-NOT-ALLOWED");
    rule("QUANTITY_DECREASE", false, null, null);
    var thrown =
        assertThrows(
            ResponseStatusException.class,
            () ->
                asA(
                    () ->
                        service.createChange(
                            new OrderChangeCancelChangeInput(
                                orderId,
                                OrderChangeKind.QUANTITY_DECREASE,
                                "少买一件",
                                "AMAZON",
                                "SHOP-1",
                                List.of(
                                    new OrderChangeCancelChangeLineInput(
                                        platformLineId(orderId), new BigDecimal("9"))),
                                null),
                            UUID.randomUUID())));
    assertThat(thrown.getReason()).contains("不允许减少数量");
  }

  @Test
  void 超出平台可改窗口时_申请被拒() {
    UUID orderId = platformOrder("PLAT-WINDOW", 5);
    // 平台窗口 1 小时，而这张单是 5 小时前下的
    rule("QUANTITY_DECREASE", true, "1", null);
    var thrown =
        assertThrows(
            ResponseStatusException.class,
            () ->
                asA(
                    () ->
                        service.createChange(
                            new OrderChangeCancelChangeInput(
                                orderId,
                                OrderChangeKind.QUANTITY_DECREASE,
                                "少买一件",
                                "AMAZON",
                                "SHOP-1",
                                List.of(
                                    new OrderChangeCancelChangeLineInput(
                                        platformLineId(orderId), new BigDecimal("9"))),
                                null),
                            UUID.randomUUID())));
    assertThat(thrown.getReason()).contains("超过平台可改窗口").contains("窗口是 1");
  }

  @Test
  void 超出单次减量上限时_申请被拒并报出上限() {
    UUID orderId = platformOrder("PLAT-MAX");
    rule("QUANTITY_DECREASE", true, null, "2");
    var thrown =
        assertThrows(
            ResponseStatusException.class,
            () ->
                asA(
                    () ->
                        service.createChange(
                            new OrderChangeCancelChangeInput(
                                orderId,
                                OrderChangeKind.QUANTITY_DECREASE,
                                "少买三件",
                                "AMAZON",
                                "SHOP-1",
                                List.of(
                                    new OrderChangeCancelChangeLineInput(
                                        platformLineId(orderId), new BigDecimal("7"))),
                                null),
                            UUID.randomUUID())));
    assertThat(thrown.getReason()).contains("超过平台规则上限 2");
  }

  @Test
  void 平台允许且在窗口内_减量申请通过并算出退款金额() {
    UUID orderId = platformOrder("PLAT-OK", 2);
    UUID lineId = platformLineId(orderId);
    rule("QUANTITY_DECREASE", true, "72", null);
    var row =
        asA(
            () ->
                service.createChange(
                    new OrderChangeCancelChangeInput(
                        orderId,
                        OrderChangeKind.QUANTITY_DECREASE,
                        "少买两件",
                        "AMAZON",
                        "SHOP-1",
                        List.of(new OrderChangeCancelChangeLineInput(lineId, new BigDecimal("8"))),
                        null),
                    UUID.randomUUID()));
    assertThat(row.status()).isEqualTo(OrderChangeStatus.REQUESTED);
    assertThat(row.refundAmount()).as("10 − 8 = 2 件 × 9.99").isEqualByComparingTo("19.9800");
    assertThat(row.platformRuleId()).as("必须记下当时按哪条规则判的").isNotNull();
  }

  @Test
  void 平台单减量后建立平台回写任务_且平台未接受前不能确认变更() {
    UUID orderId = platformOrder("PLAT-WRITEBACK");
    UUID lineId = platformLineId(orderId);
    rule("QUANTITY_DECREASE", true, "72", null);
    UUID changeId =
        asA(
            () ->
                service
                    .createChange(
                        new OrderChangeCancelChangeInput(
                            orderId,
                            OrderChangeKind.QUANTITY_DECREASE,
                            "少买两件",
                            "AMAZON",
                            "SHOP-1",
                            List.of(
                                new OrderChangeCancelChangeLineInput(lineId, new BigDecimal("8"))),
                            null),
                        UUID.randomUUID())
                    .id());

    var tasks = asA(() -> service.writebacks(changeId, false));
    assertThat(tasks).hasSize(1);
    assertThat(tasks.get(0).ownerModule()).isEqualTo("2.5.1.1");
    assertThat(tasks.get(0).status()).isEqualTo(OrderWritebackStatus.PENDING);

    assertThrows(
        ResponseStatusException.class,
        () ->
            asA(
                () ->
                    service.advanceChange(
                        changeId,
                        new OrderChangeCancelStatusInput(OrderChangeStatus.CONFIRMED, null),
                        UUID.randomUUID())));

    asA(
        () ->
            service.updateWriteback(
                tasks.get(0).id(),
                new OrderChangeCancelWritebackInput(OrderWritebackStatus.CONFIRMED, ""),
                UUID.randomUUID()));
    var confirmed =
        asA(
            () ->
                service.advanceChange(
                    changeId,
                    new OrderChangeCancelStatusInput(OrderChangeStatus.CONFIRMED, null),
                    UUID.randomUUID()));
    assertThat(confirmed.status()).isEqualTo(OrderChangeStatus.CONFIRMED);
  }

  // ==========================================================================
  // 五、地址修改与数量调整：留痕但不改写订单快照
  // ==========================================================================

  @Test
  void 地址修改按字段级前后值留痕_订单快照一个字都不改() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-ADDR-1", "2", "9.99"));
    var requestId =
        asA(
            () ->
                service
                    .createChange(
                        new OrderChangeCancelChangeInput(
                            orderId,
                            OrderChangeKind.ADDRESS_MODIFY,
                            "买家搬家了",
                            null,
                            null,
                            null,
                            List.of(
                                new OrderChangeCancelAddressFieldInput(
                                    OrderAddressRole.SHIPPING,
                                    OrderAddressFieldCode.RECIPIENT_NAME,
                                    "Jane Doe",
                                    "Jane A. Doe"),
                                new OrderChangeCancelAddressFieldInput(
                                    OrderAddressRole.SHIPPING,
                                    OrderAddressFieldCode.POSTCODE,
                                    "90001",
                                    "90210"))),
                        UUID.randomUUID())
                    .id());

    var detail = asA(() -> service.changeDetail(requestId));
    assertThat(detail.addressFields())
        .hasSize(2)
        .anyMatch(
            f ->
                f.fieldCode() == OrderAddressFieldCode.RECIPIENT_NAME
                    && "Jane Doe".equals(f.beforeValue())
                    && "Jane A. Doe".equals(f.afterValue()))
        .anyMatch(
            f ->
                f.fieldCode() == OrderAddressFieldCode.POSTCODE
                    && "90001".equals(f.beforeValue())
                    && "90210".equals(f.afterValue()));

    // 订单侧快照保持下单当时的样子
    var address =
        asA(
            () ->
                sql.fetchOne(
                        "select recipient_name, postcode from order_address_snapshot"
                            + " where order_id = ? and address_role='SHIPPING'",
                        orderId)
                    .get("recipient_name", String.class));
    assertThat(address).isEqualTo("Jane Doe");
  }

  @Test
  void 已提交订单的快照在DB层冻住_改不动() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-FROZEN-1", "2", "9.99"));
    var thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update sales_order_line set quantity = 1 where order_id = ?", orderId);
                      return null;
                    }));
    assertThat(rootMessage(thrown))
        .as("V99 第 8.3 节：SUBMITTED 之后订单行不可改写，变更只能以独立单据留痕")
        .contains("不可再改");
  }

  @Test
  void 地址变更前后值相同_不是变更不落库() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-ADDR-2", "2", "9.99"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            asA(
                () ->
                    service.createChange(
                        new OrderChangeCancelChangeInput(
                            orderId,
                            OrderChangeKind.ADDRESS_MODIFY,
                            "没改什么",
                            null,
                            null,
                            null,
                            List.of(
                                new OrderChangeCancelAddressFieldInput(
                                    OrderAddressRole.SHIPPING,
                                    OrderAddressFieldCode.CITY,
                                    "Los Angeles",
                                    "Los Angeles"))),
                        UUID.randomUUID())));
  }

  @Test
  void 数量调整明细留痕原量申请量与差额_退款按差额算() {
    UUID orderId = platformOrder("PLAT-LINE-DETAIL");
    UUID lineId = platformLineId(orderId);
    rule("QUANTITY_DECREASE", true, "72", null);
    UUID requestId =
        asA(
            () ->
                service
                    .createChange(
                        new OrderChangeCancelChangeInput(
                            orderId,
                            OrderChangeKind.QUANTITY_DECREASE,
                            "少买两件",
                            "AMAZON",
                            "SHOP-1",
                            List.of(
                                new OrderChangeCancelChangeLineInput(lineId, new BigDecimal("8"))),
                            null),
                        UUID.randomUUID())
                    .id());
    var line = asA(() -> service.changeDetail(requestId)).lines().get(0);
    assertThat(line.originalQuantity()).isEqualByComparingTo("10");
    assertThat(line.requestedQuantity()).isEqualByComparingTo("8");
    assertThat(line.deltaQuantity()).isEqualByComparingTo("-2");
    assertThat(line.lineAmount()).isEqualByComparingTo("-19.9800");
  }

  @Test
  void 手工单不走平台规则也不建回写任务() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-MANUAL-1", "2", "9.99"));
    UUID lineId = lineIdOf(orderId);
    var row =
        asA(
            () ->
                service.createChange(
                    new OrderChangeCancelChangeInput(
                        orderId,
                        OrderChangeKind.QUANTITY_DECREASE,
                        "少买一件",
                        null,
                        null,
                        List.of(new OrderChangeCancelChangeLineInput(lineId, new BigDecimal("1"))),
                        null),
                    UUID.randomUUID()));
    assertThat(row.platformRuleId()).as("手工单没有平台规则").isNull();
    assertThat(asA(() -> service.writebacks(row.id(), false)))
        .as("手工单不建回写任务：建了却永远没人处理，就是拿空任务冒充下游已接入")
        .isEmpty();
  }

  // ==========================================================================
  // 六、履约投影
  // ==========================================================================

  @Test
  void 投影三个口径相加不得超过原量() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-PROG-1", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    assertThrows(
        ResponseStatusException.class,
        () ->
            asA(
                () ->
                    service.recordProgress(
                        new OrderChangeCancelProgressInput(
                            orderId,
                            lineId,
                            new BigDecimal("6"),
                            new BigDecimal("3"),
                            new BigDecimal("3"),
                            OffsetDateTime.now(),
                            OrderProgressSource.TENANT_MAINTAINED,
                            OffsetDateTime.now()),
                        UUID.randomUUID())));
  }

  @Test
  void 重复登记投影是更新而不是第二行() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-PROG-2", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    recordProgress(orderId, lineId, "0", "2", OffsetDateTime.now());
    recordProgress(orderId, lineId, "0", "5", OffsetDateTime.now());
    var rows = asA(() -> service.progress(orderId));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).shippedQuantity()).isEqualByComparingTo("5");
    assertThat(rows.get(0).projected()).isTrue();
  }

  @Test
  void 未投影的订单行不返回行_界面据此显示未接入而不是零() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-PROG-3", "10", "9.99"));
    assertThat(asA(() -> service.progress(orderId)))
        .as("空列表不等于「一件都没发」：capabilities 会报 availableProgress=false")
        .isEmpty();
  }

  // ==========================================================================
  // 七、仓库回执与平台回写的校验
  // ==========================================================================

  @Test
  void 仓库完成回执必须给结果说明_失败必须给原因() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-RECEIPT-1", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    UUID cancelId =
        asA(
            () ->
                service
                    .createCancellation(
                        cancellationInput(orderId, lineId, "3", List.of("WH-CN-SH")),
                        UUID.randomUUID())
                    .id());
    UUID taskId = asA(() -> service.holdTasks(cancelId)).get(0).id();

    assertThrows(
        ResponseStatusException.class,
        () ->
            asA(
                () ->
                    service.updateHoldTask(
                        cancelId,
                        taskId,
                        new OrderChangeCancelHoldTaskInput(
                            OrderHoldTaskStatus.COMPLETED, null, "", ""),
                        UUID.randomUUID())));
    assertThrows(
        ResponseStatusException.class,
        () ->
            asA(
                () ->
                    service.updateHoldTask(
                        cancelId,
                        taskId,
                        new OrderChangeCancelHoldTaskInput(
                            OrderHoldTaskStatus.FAILED, null, "", ""),
                        UUID.randomUUID())));
  }

  @Test
  void 仓库实际完成量超过任务目标时被拒() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-RECEIPT-2", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    UUID cancelId =
        asA(
            () ->
                service
                    .createCancellation(
                        cancellationInput(orderId, lineId, "3", List.of("WH-CN-SH")),
                        UUID.randomUUID())
                    .id());
    UUID taskId = asA(() -> service.holdTasks(cancelId)).get(0).id();
    assertThrows(
        ResponseStatusException.class,
        () ->
            asA(
                () ->
                    service.updateHoldTask(
                        cancelId,
                        taskId,
                        new OrderChangeCancelHoldTaskInput(
                            OrderHoldTaskStatus.COMPLETED, new BigDecimal("99"), "释放", ""),
                        UUID.randomUUID())));
  }

  @Test
  void 事件时间线不可改写_两层各自挡下() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-EVENT-1", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    UUID cancelId =
        asA(
            () ->
                service
                    .createCancellation(
                        cancellationInput(orderId, lineId, "3", List.of("WH-CN-SH")),
                        UUID.randomUUID())
                    .id());

    // 第一层：REVOKE UPDATE,DELETE —— 运行角色 trade_app 连改的权限都没有。
    var denied =
        assertThrows(
            RuntimeException.class,
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update order_change_event set detail='篡改' where"
                              + " cancellation_request_id = ?",
                          cancelId);
                      return null;
                    }));
    assertThat(rootMessage(denied))
        .as("V119 把事件表的 UPDATE/DELETE 从运行角色手里收回了")
        .contains("permission denied");

    // 第二层：即使绕过 GRANT（用迁移用户直连，权限与 RLS 都挡不住），
    // 触发器仍然拒绝 —— append-only 不能只剩一条注释。
    String message = "";
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "update order_change_event set detail='篡改' where cancellation_request_id = '"
              + cancelId
              + "'");
    } catch (Exception e) {
      message = rootMessage(e);
    }
    assertThat(message).as("绕过 GRANT 也必须被触发器挡下").contains("不可改写");
  }

  // ==========================================================================
  // 八、平台规则维护与分页
  // ==========================================================================

  @Test
  void 平台规则可维护_同维度重复登记被拒_更新带乐观锁() {
    var created =
        asA(
            () ->
                service.createRule(
                    new OrderChangeCancelRuleInput(
                        "AMAZON",
                        "SHOP-1",
                        OrderChangeKind.ADDRESS_MODIFY,
                        true,
                        new BigDecimal("24"),
                        null,
                        true,
                        null,
                        null,
                        "Amazon 24 小时内可改地址",
                        null),
                    UUID.randomUUID()));
    assertThat(created.allowed()).isTrue();
    assertThat(created.active()).isTrue();

    assertThrows(
        ResponseStatusException.class,
        () ->
            asA(
                () ->
                    service.createRule(
                        new OrderChangeCancelRuleInput(
                            "AMAZON",
                            "SHOP-1",
                            OrderChangeKind.ADDRESS_MODIFY,
                            false,
                            null,
                            null,
                            true,
                            null,
                            null,
                            "重复登记",
                            null),
                        UUID.randomUUID())));

    // 版本不匹配的更新必须显式失败
    assertThrows(
        ResponseStatusException.class,
        () ->
            asA(
                () ->
                    service.updateRule(
                        created.id(),
                        new OrderChangeCancelRuleInput(
                            "AMAZON",
                            "SHOP-1",
                            OrderChangeKind.ADDRESS_MODIFY,
                            false,
                            null,
                            null,
                            true,
                            null,
                            null,
                            "改窗口",
                            99L),
                        UUID.randomUUID())));
    var updated =
        asA(
            () ->
                service.updateRule(
                    created.id(),
                    new OrderChangeCancelRuleInput(
                        "AMAZON",
                        "SHOP-1",
                        OrderChangeKind.ADDRESS_MODIFY,
                        false,
                        null,
                        null,
                        true,
                        null,
                        null,
                        "改成不允许",
                        created.version()),
                    UUID.randomUUID()));
    assertThat(updated.allowed()).isFalse();
    assertThat(updated.version()).isEqualTo(created.version() + 1);
  }

  @Test
  void 集合分页是真分页_汇总不受分页影响() {
    for (int i = 0; i < 7; i++) rule("SHOP-P" + i, "CANCEL", true, "48", null);
    var page1 =
        asA(() -> service.pageRules(new OrderChangeCancelRuleQuery(null, null, null, null, 0, 3)));
    var page3 =
        asA(() -> service.pageRules(new OrderChangeCancelRuleQuery(null, null, null, null, 2, 3)));
    assertThat(page1.items()).hasSize(3);
    assertThat(page3.items()).hasSize(1);
    assertThat(page1.total()).isEqualTo(7);
    assertThat(page3.total()).as("汇总不受分页影响").isEqualTo(7);
    var ids1 = page1.items().stream().map(OrderChangeCancelRuleRow::id).toList();
    var ids3 = page3.items().stream().map(OrderChangeCancelRuleRow::id).toList();
    assertThat(ids1).doesNotContainAnyElementsOf(ids3);
  }

  @Test
  void 取消单按状态与单号筛选有效() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-FILTER-1", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    asA(
        () ->
            service.createCancellation(
                cancellationInput(orderId, lineId, "2", List.of("WH-CN-SH")), UUID.randomUUID()));
    var byStatus =
        asA(
            () ->
                service.pageCancellations(
                    new OrderChangeCancelQuery(
                        null, null, OrderChangeStatus.REQUESTED, null, 0, 50)));
    assertThat(byStatus.total()).isEqualTo(1);
    var byNo =
        asA(
            () ->
                service.pageCancellations(
                    new OrderChangeCancelQuery(null, "SO-FILTER", null, null, 0, 50)));
    assertThat(byNo.total()).isEqualTo(1);
    var miss =
        asA(
            () ->
                service.pageCancellations(
                    new OrderChangeCancelQuery(null, "SO-NOT-EXIST", null, null, 0, 50)));
    assertThat(miss.total()).isZero();
  }

  @Test
  void 能力声明逐段报出上游可用性与下游归属() {
    var caps = asA(() -> service.capabilities());
    assertThat(caps.availableCapabilities()).isNotEmpty();
    assertThat(caps.unavailableCapabilities())
        .as("未接上的能力必须显式报出 ownerModule 与原因，不能靠空数组让人猜")
        .anyMatch(b -> b.ownerModule().startsWith("11"))
        .anyMatch(b -> b.ownerModule().contains("9.3.3.1"));
    assertThat(caps.statusTransitions())
        .contains("REQUESTED", "CONFIRMED", "REJECTED", "PENDING_VERIFICATION");
    assertThat(caps.warehouseReleaseApi()).contains("reservations");
    assertThat(caps.snapshotNote()).contains("不改写");
  }

  // ==========================================================================
  // 九、HTTP 边界
  // ==========================================================================

  @Test
  void 未认证与只读角色被拒() throws Exception {
    http.perform(get("/trade/sales-orders/change/capabilities"))
        .andExpect(status().isUnauthorized());

    // 只读角色读可以，写不行
    http.perform(
            get("/trade/sales-orders/change/platform-rules")
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());
    // 请求体必须**合法**：Spring 先做 @Valid 再进控制器，
    // 非法体会先被 400 拦下，那样测到的就不是权限边界了。
    http.perform(
            post("/trade/sales-orders/change/platform-rules")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new OrderChangeCancelRuleInput(
                            "AMAZON",
                            "*",
                            OrderChangeKind.CANCEL,
                            true,
                            null,
                            null,
                            true,
                            null,
                            null,
                            "只读角色不该能建",
                            null))))
        .andExpect(status().isForbidden());
  }

  @Test
  void 端点经HTTP可跑通_含建单到确认的完整链路() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-HTTP-1", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    recordProgress(orderId, lineId, "10", "0", OffsetDateTime.now());

    String resp =
        http.perform(
                post("/trade/sales-orders/change/cancellations")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            new OrderChangeCancelCancellationInput(
                                orderId,
                                OrderCancelScope.PARTIAL_LINES,
                                "买家改主意",
                                null,
                                null,
                                List.of("WH-CN-SH"),
                                List.of(
                                    new OrderChangeCancelCancellationLineInput(
                                        lineId, new BigDecimal("2"), "RSV-1"))))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID cancelId = UUID.fromString(json.readTree(resp).get("id").asText());

    http.perform(
            get("/trade/sales-orders/change/cancellations/{id}", cancelId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines[0].cancelQuantity").value(2.0))
        .andExpect(jsonPath("$.warehouseTasks.length()").value(2));

    for (var task : asA(() -> service.holdTasks(cancelId))) {
      http.perform(
              post(
                      "/trade/sales-orders/change/cancellations/{id}/warehouse-tasks/{taskId}/status",
                      cancelId,
                      task.id())
                  .header("Authorization", "Bearer tenant-a")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      json.writeValueAsString(
                          new OrderChangeCancelHoldTaskInput(
                              OrderHoldTaskStatus.COMPLETED,
                              task.targetQuantity(),
                              "仓库已停止作业并释放占用",
                              ""))))
          .andExpect(status().isOk());
    }

    http.perform(
            post("/trade/sales-orders/change/cancellations/{id}/status", cancelId)
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new OrderChangeCancelStatusInput(OrderChangeStatus.CONFIRMED, "已全部确认"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.refundAmount").value(19.98));
  }

  // ==========================================================================
  // 十、多租户三断言
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-TENANT-1", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    UUID cancelId =
        asA(
            () ->
                service
                    .createCancellation(
                        cancellationInput(orderId, lineId, "2", List.of("WH-CN-SH")),
                        UUID.randomUUID())
                    .id());

    assertThat(asA(() -> service.cancellationDetail(cancelId))).isNotNull();
    assertThrows(
        ResponseStatusException.class, () -> asB(() -> service.cancellationDetail(cancelId)));

    http.perform(
            get("/trade/sales-orders/change/cancellations/{id}", cancelId)
                .header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isNotFound());
    assertThat(
            asB(() ->
                    service.pageCancellations(
                        new OrderChangeCancelQuery(null, null, null, null, 0, 50)))
                .total())
        .isZero();
  }

  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    // 事务外没有「环境租户」：任何租户的行都看不见
    assertThat(sql.fetch("select id from order_cancellation_request").size()).isZero();

    UUID orderId = UUID.fromString(manualOrder("SO-TENANT-2", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    UUID cancelId =
        asA(
            () ->
                service
                    .createCancellation(
                        cancellationInput(orderId, lineId, "2", List.of("WH-CN-SH")),
                        UUID.randomUUID())
                    .id());

    // A 的会话 + 伪造指向 B 的头 → 依然读得到自己的单（头不改租户）
    http.perform(
            get("/trade/sales-orders/change/cancellations/{id}", cancelId)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());
    // B 的会话 + 伪造指向 A 的头 → 404
    http.perform(
            get("/trade/sales-orders/change/cancellations/{id}", cancelId)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());

    // 写入路径同样不认头：B 会话伪造 A 的头，落库仍属 B
    UUID orderB = UUID.randomUUID();
    http.perform(
            post("/trade/sales-orders/change/platform-rules")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new OrderChangeCancelRuleInput(
                            "SHOPIFY",
                            "*",
                            OrderChangeKind.CANCEL,
                            true,
                            null,
                            null,
                            true,
                            null,
                            null,
                            "伪造头写入",
                            null))))
        .andExpect(status().isOk());
    assertThat(
            asA(() ->
                    service.pageRules(
                        new OrderChangeCancelRuleQuery("SHOPIFY", null, null, null, 0, 10)))
                .total())
        .as("伪造头不得把数据写进别的租户")
        .isZero();
    assertThat(
            asB(() ->
                    service.pageRules(
                        new OrderChangeCancelRuleQuery("SHOPIFY", null, null, null, 0, 10)))
                .total())
        .isEqualTo(1);

    // 提交后租户上下文必须已清空
    assertThat(sql.fetch("select id from order_cancellation_request").size()).isZero();
  }

  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    UUID orderId = UUID.fromString(manualOrder("SO-TENANT-3", "10", "9.99"));
    UUID lineId = lineIdOf(orderId);
    asA(
        () ->
            service.createCancellation(
                cancellationInput(orderId, lineId, "2", List.of("WH-CN-SH")), UUID.randomUUID()));

    // 池大小为 1：每次读写都复用同一条连接
    for (int i = 0; i < 5; i++) {
      assertThat(
              asA(() ->
                      service.pageCancellations(
                          new OrderChangeCancelQuery(null, null, null, null, 0, 50)))
                  .total())
          .as("第 %d 轮 A", i)
          .isEqualTo(1);
      assertThat(
              asB(() ->
                      service.pageCancellations(
                          new OrderChangeCancelQuery(null, null, null, null, 0, 50)))
                  .total())
          .as("第 %d 轮 B", i)
          .isZero();
      assertThat(sql.fetch("select id from order_cancellation_request").size())
          .as("第 %d 轮事务外", i)
          .isZero();
    }
    assertThat(
            asB(() ->
                    service.pageCancellations(
                        new OrderChangeCancelQuery(null, null, null, null, 0, 50)))
                .total())
        .isZero();
    assertThat(
            asA(() ->
                    service.pageCancellations(
                        new OrderChangeCancelQuery(null, null, null, null, 0, 50)))
                .total())
        .isEqualTo(1);
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  static String rootMessage(Throwable t) {
    var cause = t;
    while (cause.getCause() != null) cause = cause.getCause();
    return cause.getMessage() == null ? "" : cause.getMessage();
  }
}
