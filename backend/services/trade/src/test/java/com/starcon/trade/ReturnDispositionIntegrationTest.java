package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.trade.ReturnQualityModels.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.2.3.2 转可售/残次/报废（trade 侧：处置决定 + 审批 + 报废损耗 + 消费质检指令）。
 *
 * <p>跑的是蓝图职责句与那条红线：
 *
 * <blockquote>
 * 职责：按质检结论把退货转入对应状态：可再销售转可售库存、残次转残次库存（可用于清仓）、
 * 报废则核销并计入损耗。
 * 输入：质检结论；处置决定；{@code expected_version}。
 * 输出：库存状态变更分录；可售量更新；报废损耗记录。
 * <b>报废是不可逆的</b>，需审批与原因，不能由客服随手处理。
 * </blockquote>
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS。
 *
 * <p><b>上游夹具走的是 11.2.3.1 的真实链路</b>（订单 → 售后单 → 退货授权 → 退货实收入库 →
 * 质检单 → 判定），质量状态指令由 <b>V179 的 DB 触发器</b>在判定那一刻生成 ——
 * 本类不手工造指令，所以「消费方拿到的东西确实来自上游」这件事是被真链路证明的。
 *
 * <p>warehouse 侧用**真 HTTP 桩**（JDK HttpServer）：库存事实的写入所有权在 warehouse，
 * 那一半由 warehouse 的集成测试
 * （{@code ReturnDispositionExecutionIntegrationTest}）在真库里证明；
 * 本类证明的是「处置决定 → 审批 → 执行 → 消费指令 → 记报废损耗」这一段的真数据流。
 *
 * <p><b>本类最看重的九组断言</b>：
 *
 * <ol>
 *   <li><b>处置决定不能改判质检结论</b>：目标状态与数量都从指令读，数量对不上直接拒。</li>
 *   <li><b>报废不可逆的三道闸门</b>：没批不能执行；提交人不能审批自己提交的；
 *       审批意见必填。</li>
 *   <li><b>执行成功才消费指令</b>，且指令只能 PENDING → CONSUMED 一次。</li>
 *   <li><b>执行失败绝不标成已执行</b>：warehouse 拒绝时处置单落「执行失败」，指令仍是待消费。</li>
 *   <li><b>报废损耗记了</b>，而<b>财务侧字段恒为 null</b>（不填 0 冒充无损耗）。</li>
 *   <li><b>绕过服务层直连 SQL 也绕不过</b>：数量与指令不符的处置单插不进去；
 *       没有已执行处置单的指令改不成已消费。</li>
 *   <li><b>expected_version</b>：不符要拒。</li>
 *   <li><b>来源库位不在候选里要拒</b>（不猜「货在哪」）。</li>
 *   <li><b>时间写回零漂移</b>（+08:00 夹具）。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReturnDispositionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID other = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static final UUID approver = UUID.randomUUID();
  static final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms =
      List.of("trade.aftersales.read", "trade.aftersales.write", "trade.aftersales.approve");

  static AccessService.Access actor(UUID uid, UUID tid) {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms));
    return new AccessService.Access(uid, tid, "Test", UUID.randomUUID(), null, perms, grants);
  }

  // ---- 夹具 id（warehouse 侧的候选库位事实）----
  static final UUID SKU_ID = UUID.nameUUIDFromBytes("rd-sku".getBytes());
  static final UUID BUCKET_ID = UUID.nameUUIDFromBytes("rd-bucket".getBytes());
  static final UUID LOCATION_ID = UUID.nameUUIDFromBytes("rd-location".getBytes());
  static final UUID WAREHOUSE_ID = UUID.nameUUIDFromBytes("rd-warehouse".getBytes());
  static final UUID LEGAL_ENTITY_ID = UUID.nameUUIDFromBytes("rd-le".getBytes());

  /** warehouse 桩收到的执行命令（断言「命令真的发出去了」）。 */
  static final List<Map<String, Object>> warehouseExecutions = new CopyOnWriteArrayList<>();

  static HttpServer warehouse;

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
      // warehouse 桩必须在 @DynamicPropertySource 之前起来（那里要读它的端口）。
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/inventory/return-dispositions/options",
          ReturnDispositionIntegrationTest::options);
      warehouse.createContext(
          "/warehouse/inventory/return-dispositions/execute",
          ReturnDispositionIntegrationTest::execute);
      warehouse.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put("Bearer tenant-main", actor(user, tenant));
    actors.put("Bearer tenant-other", actor(UUID.randomUUID(), other));
    actors.put("Bearer approver", actor(approver, tenant));
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:8081");
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    // warehouse 端口指向本类的真 HTTP 桩（真 HTTP 往返，不是内存替身）。
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
  }

  @MockitoSpyBean AccessService access;
  @Autowired ReturnQualityService quality;
  @Autowired ReturnReceiptService receipts;
  @Autowired RmaAuthorizationService rma;
  @Autowired AftersalesCaseService cases;
  @Autowired AftersalesCaseDetailService details;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    warehouse.stop(0);
    pg.stop();
  }

  /** **非 UTC 偏移**夹具：只有这种时刻才能测出 Timestamp.valueOf 的 −28800s 静默偏移。 */
  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");

  static OffsetDateTime due() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8)).plusDays(14);
  }

  // ================================================================ warehouse 桩

  static void options(HttpExchange e) throws java.io.IOException {
    Map<String, String> query = new java.util.HashMap<>();
    String raw = e.getRequestURI().getRawQuery();
    if (raw != null) {
      for (String pair : raw.split("&")) {
        String[] kv = pair.split("=", 2);
        query.put(kv[0], kv.length > 1 ? java.net.URLDecoder.decode(kv[1], "UTF-8") : "");
      }
    }
    String targetState = query.getOrDefault("targetState", "SELLABLE");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("targetInventoryState", targetState);
    body.put("requiredLocationType", "SCRAP".equals(targetState) ? null : targetState);
    List<Map<String, Object>> buckets = new ArrayList<>();
    Map<String, Object> bucket = new LinkedHashMap<>();
    bucket.put("bucketId", BUCKET_ID.toString());
    bucket.put("warehouseId", WAREHOUSE_ID.toString());
    bucket.put("warehouseCode", "WH-ONE");
    bucket.put("warehouseName", "一仓");
    bucket.put("locationId", UUID.nameUUIDFromBytes("rd-quarantine".getBytes()).toString());
    bucket.put("locationCode", "Q-01");
    bucket.put("locationName", "待检区");
    bucket.put("lotId", null);
    bucket.put("lotCode", "");
    bucket.put("legalEntityId", LEGAL_ENTITY_ID.toString());
    bucket.put("qualityStatusCode", "PENDING_INSPECTION");
    bucket.put("skuId", SKU_ID.toString());
    bucket.put("skuCode", "SKU-1");
    bucket.put("skuName", "商品一");
    bucket.put("onHand", new BigDecimal("10"));
    bucket.put("reserved", BigDecimal.ZERO);
    bucket.put("available", new BigDecimal("10"));
    buckets.add(bucket);
    body.put("pendingBuckets", buckets);
    List<Map<String, Object>> locations = new ArrayList<>();
    if (!"SCRAP".equals(targetState)) {
      Map<String, Object> location = new LinkedHashMap<>();
      location.put("locationId", LOCATION_ID.toString());
      location.put("code", "S-01");
      location.put("name", "存储区");
      location.put("type", "SELLABLE".equals(targetState) ? "STORAGE" : "DEFECTIVE");
      location.put("warehouseId", WAREHOUSE_ID.toString());
      location.put("warehouseCode", "WH-ONE");
      location.put("pickable", true);
      location.put("status", "ACTIVE");
      locations.add(location);
    }
    body.put("targetLocations", locations);
    respond(e, 200, body);
  }

  static void execute(HttpExchange e) throws java.io.IOException {
    String raw = new String(e.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    @SuppressWarnings("unchecked")
    Map<String, Object> command = new ObjectMapper().readValue(raw, Map.class);
    warehouseExecutions.add(command);
    // 单号带 REJECT 的处置单：warehouse 明确拒绝（模拟校验不过 / 上游报错）。
    if (String.valueOf(command.get("dispositionNo")).contains("REJECT")) {
      respond(e, 500, Map.of("detail", "仓库拒绝了这次执行"));
      return;
    }
    Map<String, Object> receipt = new LinkedHashMap<>();
    receipt.put("executionId", UUID.randomUUID().toString());
    receipt.put("executionNo", "RDE1");
    receipt.put("dispositionId", command.get("dispositionId"));
    receipt.put("targetInventoryState", command.get("targetInventoryState"));
    receipt.put("skuId", command.get("skuId"));
    receipt.put("skuCode", command.get("skuCode"));
    receipt.put("qty", command.get("qty"));
    receipt.put("sourceBucketId", command.get("sourceBucketId"));
    receipt.put(
        "targetBucketId",
        "SCRAP".equals(command.get("targetInventoryState")) ? null : UUID.randomUUID().toString());
    receipt.put("inventoryTransactionId", UUID.randomUUID().toString());
    receipt.put("sellableQuantityBefore", BigDecimal.ZERO);
    receipt.put(
        "sellableQuantityAfter",
        "SELLABLE".equals(command.get("targetInventoryState")) ? new BigDecimal("2") : BigDecimal.ZERO);
    receipt.put("occurredAt", command.get("occurredAt"));
    receipt.put("idempotentReplay", false);
    receipt.put("note", "ok");
    respond(e, 200, receipt);
  }

  static void respond(HttpExchange e, int status, Object body) throws java.io.IOException {
    byte[] bytes = new ObjectMapper().writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  // ================================================================ 夹具

  @BeforeEach
  void stubIdentityAndClean() throws Exception {
    org.mockito.Mockito
        .doAnswer(
            inv -> {
              String bearer = inv.getArgument(0);
              String permission = inv.getArgument(1);
              AccessService.Access actor = actors.get(bearer);
              if (actor == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
              }
              boolean allowed =
                  actor.grants().stream()
                      .anyMatch(
                          g ->
                              g.permissions().contains(permission)
                                  && g.permissions()
                                      .contains(
                                          permission.substring(0, permission.lastIndexOf('.')) + ".read")
                                  && ("ALL".equals(g.scopeMode()) || "SELF".equals(g.scopeMode())));
              if (!allowed) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色没有此操作权限");
              }
              return actor;
            })
        .when(access)
        .require(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

    warehouseExecutions.clear();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate return_inventory_disposition_loss, return_inventory_disposition_event,"
              + " return_inventory_disposition, return_quality_disposition_instruction,"
              + " return_quality_check_result, return_quality_event, return_quality_inspection,"
              + " return_quality_template_item, return_quality_template, return_quality_category_sku,"
              + " return_quality_category, return_inventory_posting, return_receipt_photo,"
              + " return_receipt_line, return_receipt_event, return_receipt, rma_decision_event,"
              + " rma_authorization_line, rma_authorization, aftersales_case_line,"
              + " aftersales_case_note, aftersales_authorization, aftersales_service_ticket,"
              + " aftersales_owner_assignment, aftersales_case, order_fulfillment_line_progress,"
              + " sales_order_line, sales_order cascade");
    }
  }

  <T> T as(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-main"), "trade.aftersales.write", work);
  }

  record CaseSeeded(UUID caseId, UUID lineId) {}

  record Seeded(UUID rmaId, UUID caseId, UUID orderLineId, UUID rmaLineId) {}

  record Posted(UUID postingId, UUID skuId, String skuCode) {}

  /** 订单 → 售后单 → 售后行。 */
  CaseSeeded seedCase(String orderNo) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    BigDecimal qty = new BigDecimal("4");
    BigDecimal unitPrice = new BigDecimal("5.0000");
    BigDecimal amount = unitPrice.multiply(qty);

    as(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,?::numeric,"
                  + "0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
              orderId,
              tenant,
              orderNo,
              "EXT-" + orderNo,
              T1,
              amount,
              amount,
              T1,
              T1);
          // SKU 固定成夹具常量：warehouse 桩的候选库位挂的是同一个 SKU。
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
                  + "created_at) values(?,?::uuid,?::uuid,1,?::uuid,?,?,'黑色 / L',?::numeric,"
                  + "?::numeric,0,0,?::numeric,'',?::timestamptz)",
              lineId,
              tenant,
              orderId,
              SKU_ID,
              "SKU-1",
              "商品一",
              qty,
              unitPrice,
              amount,
              T1);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
                  + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              orderId,
              "买家-" + orderNo,
              "buyer-" + orderNo + "@example.com",
              T1);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?::uuid,?::uuid,?,?,?,'INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              orderId,
              UUID.randomUUID(),
              "C-" + orderNo,
              "买家-" + orderNo,
              T1);
          sql.execute(
              "insert into order_fulfillment_line_progress(id,tenant_id,order_id,order_line_id,"
                  + "ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,"
                  + "observed_at,version,created_at,updated_at)"
                  + " values(?,?::uuid,?::uuid,?::uuid,?::numeric,0,0,?::numeric,?::timestamptz,"
                  + "'MODULE_9_3_3_1',?::timestamptz,0,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              orderId,
              lineId,
              qty,
              qty,
              T1,
              T1,
              T1,
              T1);
          return null;
        });

    UUID caseId =
        as(
            () ->
                cases
                    .create(
                        new AftersalesCaseModels.AftersalesCaseCreate(
                            orderId,
                            "RETURN_REFUND",
                            "QUALITY_DEFECT",
                            "外箱压损",
                            amount,
                            "USD",
                            qty,
                            "PLAT-" + orderNo,
                            null,
                            null,
                            "买家诉求-" + orderNo,
                            T1,
                            null,
                            T1,
                            null,
                            null,
                            null,
                            "TENANT_MAINTAINED",
                            null,
                            T1),
                        user)
                    .id());
    as(
        () ->
            details.addLine(
                caseId,
                new AftersalesCaseDetailModels.AftersalesCaseLineCreate(
                    lineId, qty, amount, "USD", "第 1 件"),
                user));
    return new CaseSeeded(caseId, lineId);
  }

  /** 退货授权单（授权 3 件）。 */
  Seeded seedAuthorized(String orderNo) {
    CaseSeeded c = seedCase(orderNo);
    UUID rmaId =
        as(
            () ->
                rma.create(
                        new RmaAuthorizationModels.RmaCreate(
                            c.caseId(),
                            "REFUND_TO_BUYER",
                            "TENANT_MAINTAINED",
                            null,
                            "退货仓-" + orderNo,
                            "+1-213-555-0199",
                            "rma-" + orderNo + "@example.com",
                            "US",
                            "CA",
                            "ONTARIO",
                            "",
                            "M5V 2T6",
                            "88 Yonge St",
                            "3 楼",
                            null,
                            null),
                        user)
                    .head()
                    .id());
    var detail = as(() -> rma.detail(rmaId));
    UUID rmaLineId = detail.lines().get(0).id();
    as(
        () ->
            rma.decide(
                rmaId,
                new RmaAuthorizationModels.RmaDecide(
                    "PARTIALLY_APPROVED",
                    due(),
                    "外包装压损，只退部分",
                    List.of(
                        new RmaAuthorizationModels.RmaLineDecision(
                            rmaLineId, new BigDecimal("3"), null)),
                    0L),
                user));
    return new Seeded(rmaId, c.caseId(), c.lineId(), rmaLineId);
  }

  /** 登记并入库一张退货实收 → 返回待检入库分录。 */
  Posted postReturn(String orderNo, BigDecimal qty, String packaging, String goods) {
    Seeded s = seedAuthorized(orderNo);
    var draft =
        as(
            () ->
                receipts.create(
                    new ReturnReceiptModels.ReturnReceiptCreate(
                        s.rmaId(),
                        null,
                        "CARRIER",
                        "FedEx",
                        "RT-" + orderNo,
                        "外箱一角压瘪",
                        T1,
                        List.of(
                            new ReturnReceiptModels.ReturnReceiptLineDraft(
                                s.rmaLineId(),
                                "SKU-1",
                                "SN-" + UUID.randomUUID(),
                                null,
                                qty,
                                packaging,
                                goods,
                                "外箱压损")),
                        List.of()),
                    user));
    as(
        () ->
            receipts.post(
                draft.head().id(),
                new ReturnReceiptModels.ReturnReceiptPost(null, draft.head().version()),
                user));
    Record posting =
        as(
            () ->
                sql.fetchOne(
                    "select * from return_inventory_posting where tenant_id = ?::uuid and receipt_id = ?::uuid",
                    tenant,
                    draft.head().id()));
    return new Posted(
        posting.get("id", UUID.class),
        posting.get("sku_id", UUID.class),
        posting.get("sku_code", String.class));
  }

  /** 建一个可用的质检模板（四个检项，费率固定；残值算式依赖这些数）。 */
  record QualityFixture(UUID categoryId, UUID templateId) {}

  QualityFixture seedTemplate(UUID skuId, String skuCode) {
    UUID categoryId =
        as(
            () ->
                quality
                    .createCategory(
                        new QualityCategoryCreate("CAT-" + skuCode, "小家电", "带配件的机器"), user)
                    .id());
    as(
        () -> {
          quality.mapSku(categoryId, new QualitySkuMappingCreate(skuId, skuCode), user);
          return null;
        });
    QualityTemplateDetail template =
        as(
            () ->
                quality.createTemplate(
                    new QualityTemplateCreate(
                        categoryId,
                        "小家电标准质检",
                        1,
                        new BigDecimal("1.000000"),
                        new BigDecimal("0.100000"),
                        new BigDecimal("1.000000"),
                        new BigDecimal("0.500000"),
                        new BigDecimal("0.050000"),
                        "11.2.3.2 夹具模板",
                        List.of(
                            item("APPEARANCE-1", "外观划痕", "APPEARANCE", "0.200000"),
                            item("PACKAGING-1", "外包装", "PACKAGING", "0.100000"),
                            item("FUNCTION-1", "功能自检", "FUNCTION", "0.500000"),
                            item("ACCESSORY-1", "配件清单", "ACCESSORY", "0.300000"))),
                    user));
    as(() -> quality.activateTemplate(template.template().id(), user));
    return new QualityFixture(categoryId, template.template().id());
  }

  private static QualityTemplateItemDraft item(String code, String name, String dim, String rate) {
    return new QualityTemplateItemDraft(code, name, dim, new BigDecimal(rate), true, "", "");
  }

  /** 走完 11.2.3.1 的真链路，产出**一条待消费的质量状态指令**。 */
  UUID seedInstruction(String targetState) {
    // 外箱损毁 + 商品完好 ⇒ 三档判定都允许（11.2.3.1 的保守判定路径）。
    Posted p = postReturn("RD-" + targetState, new BigDecimal("2"), "DESTROYED", "OPENED_UNDAMAGED");
    QualityFixture f = seedTemplate(p.skuId(), p.skuCode());
    UUID inspectionId =
        as(
            () ->
                quality
                    .createInspection(new QualityInspectionCreate(p.postingId(), null), user)
                    .head()
                    .id());
    List<QualityCheckResultDraft> results =
        as(() -> quality.templateDetail(f.templateId()))
            .items().stream()
                .map(
                    i ->
                        new QualityCheckResultDraft(
                            i.id(),
                            i.itemCode().equals("PACKAGING-1") ? "FAIL" : "PASS",
                            i.itemCode().equals("PACKAGING-1") ? "外包装压损" : "",
                            null))
                .toList();
    long v = as(() -> quality.inspectionDetail(inspectionId).head().version());
    as(
        () ->
            quality.recordResults(
                inspectionId, new QualityInspectionUpdate(results, v), user));
    long v2 = as(() -> quality.inspectionDetail(inspectionId).head().version());
    String verdict =
        switch (targetState) {
          case "SELLABLE" -> "RESALABLE";
          case "DEFECTIVE" -> "DEFECTIVE";
          default -> "SCRAPPED";
        };
    as(
        () ->
            quality.conclude(
                inspectionId,
                new QualityInspectionConclude(verdict, "质检完成：外箱压损，商品" + verdict, v2),
                user));
    return as(
        () ->
            sql.fetchOne(
                    "select id from return_quality_disposition_instruction"
                        + " where tenant_id=? and inspection_id=?",
                    tenant,
                    inspectionId)
                .get("id", UUID.class));
  }

  Map<String, Object> createBody(UUID instructionId, String targetState, String qty, String location) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("instructionId", instructionId.toString());
    body.put("sourceBucketId", BUCKET_ID.toString());
    body.put("targetLocationId", location);
    body.put("qty", qty == null ? null : new BigDecimal(qty));
    body.put("reasonNote", "外箱压损，商品完好，可再销售");
    if ("SCRAP".equals(targetState)) {
      body.put("scrapReasonCode", "DAMAGED_UNREPAIRABLE");
    }
    return body;
  }

  MvcResult postJson(String path, Object body, String bearer) throws Exception {
    return http.perform(
            post(path)
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(json.writeValueAsString(body)))
        .andReturn();
  }

  MvcResult postJson(String path, Object body) throws Exception {
    return postJson(path, body, "Bearer tenant-main");
  }

  Map<String, Object> body(MvcResult r) throws Exception {
    return json.readValue(r.getResponse().getContentAsString(), Map.class);
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> dispositionOf(MvcResult r) throws Exception {
    return (Map<String, Object>) body(r).get("disposition");
  }

  long versionOf(UUID dispositionId) {
    return as(
        () ->
            sql.fetchOne(
                    "select version from return_inventory_disposition where tenant_id=? and id=?",
                    tenant,
                    dispositionId)
                .get("version", Long.class));
  }

  String statusOf(UUID dispositionId) {
    return as(
        () ->
            sql.fetchOne(
                    "select status from return_inventory_disposition where tenant_id=? and id=?",
                    tenant,
                    dispositionId)
                .get("status", String.class));
  }

  String instructionStatusOf(UUID instructionId) {
    return as(
        () ->
            sql.fetchOne(
                    "select instruction_status from return_quality_disposition_instruction"
                        + " where tenant_id=? and id=?",
                    tenant,
                    instructionId)
                .get("instruction_status", String.class));
  }

  long countDispositions() {
    return as(
        () ->
            sql.fetchOne(
                    "select count(*) from return_inventory_disposition where tenant_id=?", tenant)
                .get(0, Long.class));
  }

  // ================================================================ 断言

  @Test
  void 待处置指令列表能查到质检产出的指令() throws Exception {
    UUID instructionId = seedInstruction("SELLABLE");
    http.perform(
            get("/trade/return-dispositions/instructions")
                .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.instructions[0].id").value(instructionId.toString()))
        .andExpect(jsonPath("$.instructions[0].targetInventoryState").value("SELLABLE"))
        .andExpect(jsonPath("$.instructions[0].qty").value(2));
  }

  @Test
  void 建单_目标状态与数量由质检结论决定_数量对不上要拒() throws Exception {
    UUID instructionId = seedInstruction("SELLABLE");

    // 数量与质检结论不一致 → 拒。
    var wrong =
        postJson(
            "/trade/return-dispositions",
            createBody(instructionId, "SELLABLE", "1", LOCATION_ID.toString()));
    assertThat(wrong.getResponse().getStatus()).isEqualTo(400);

    // 数量一致 → 成功，且单上的状态与数量就是指令上的。
    var ok =
        postJson(
            "/trade/return-dispositions",
            createBody(instructionId, "SELLABLE", "2", LOCATION_ID.toString()));
    assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    Map<String, Object> disposition = dispositionOf(ok);
    assertThat(disposition.get("targetInventoryState")).isEqualTo("SELLABLE");
    assertThat(new BigDecimal(String.valueOf(disposition.get("qty")))).isEqualByComparingTo("2");
    assertThat(disposition.get("status")).isEqualTo("DRAFT");
  }

  @Test
  void 建单_来源库位不在待检候选里要拒() throws Exception {
    UUID instructionId = seedInstruction("SELLABLE");
    Map<String, Object> body = createBody(instructionId, "SELLABLE", "2", LOCATION_ID.toString());
    body.put("sourceBucketId", UUID.randomUUID().toString());
    var result = postJson("/trade/return-dispositions", body);
    assertThat(result.getResponse().getStatus()).isEqualTo(400);
  }

  @Test
  void 报废必须给损耗原因码() throws Exception {
    UUID instructionId = seedInstruction("SCRAP");
    Map<String, Object> body = createBody(instructionId, "SCRAP", "2", null);
    body.remove("scrapReasonCode");
    var result = postJson("/trade/return-dispositions", body);
    assertThat(result.getResponse().getStatus()).isEqualTo(400);
  }

  @Test
  void 没批不能执行_提交人不能审批自己提交的() throws Exception {
    UUID instructionId = seedInstruction("SCRAP");
    var created = postJson("/trade/return-dispositions", createBody(instructionId, "SCRAP", "2", null));
    assertThat(created.getResponse().getStatus()).isEqualTo(200);
    Map<String, Object> disposition = dispositionOf(created);
    UUID dispositionId = UUID.fromString(String.valueOf(disposition.get("id")));
    long version = ((Number) disposition.get("version")).longValue();

    // 没提交就执行 → 拒。
    var early =
        postJson(
            "/trade/return-dispositions/" + dispositionId + "/execute",
            Map.of("expectedVersion", version));
    assertThat(early.getResponse().getStatus()).isEqualTo(409);

    // 提交 → 自己审批 → 拒（职责分离）。
    var submitted =
        postJson(
            "/trade/return-dispositions/" + dispositionId + "/submit",
            Map.of("expectedVersion", version));
    assertThat(submitted.getResponse().getStatus()).isEqualTo(200);
    long version2 = ((Number) dispositionOf(submitted).get("version")).longValue();

    var selfApprove =
        postJson(
            "/trade/return-dispositions/" + dispositionId + "/approve",
            Map.of("expectedVersion", version2, "decisionNote", "自己批自己"));
    assertThat(selfApprove.getResponse().getStatus()).isEqualTo(409);

    // 审批意见为空 → 拒。
    var noNote =
        postJson(
            "/trade/return-dispositions/" + dispositionId + "/approve",
            Map.of("expectedVersion", version2, "decisionNote", "  "),
            "Bearer approver");
    assertThat(noNote.getResponse().getStatus()).isEqualTo(400);
  }

  @Test
  void 执行成功_处置单变已执行_指令被消费_命令真的发到warehouse() throws Exception {
    UUID instructionId = seedInstruction("SELLABLE");
    var created =
        postJson(
            "/trade/return-dispositions",
            createBody(instructionId, "SELLABLE", "2", LOCATION_ID.toString()));
    UUID dispositionId = UUID.fromString(String.valueOf(dispositionOf(created).get("id")));
    long version = ((Number) dispositionOf(created).get("version")).longValue();

    var submitted =
        postJson(
            "/trade/return-dispositions/" + dispositionId + "/submit",
            Map.of("expectedVersion", version));
    long version2 = ((Number) dispositionOf(submitted).get("version")).longValue();
    var approved =
        postJson(
            "/trade/return-dispositions/" + dispositionId + "/approve",
            Map.of("expectedVersion", version2, "decisionNote", "同意转可售"),
            "Bearer approver");
    assertThat(approved.getResponse().getStatus()).isEqualTo(200);
    long version3 = ((Number) dispositionOf(approved).get("version")).longValue();

    var executed =
        postJson(
            "/trade/return-dispositions/" + dispositionId + "/execute",
            Map.of("expectedVersion", version3));
    assertThat(executed.getResponse().getStatus()).isEqualTo(200);

    // 处置单已执行。
    assertThat(statusOf(dispositionId)).isEqualTo("EXECUTED");
    // 指令被消费（一次且仅一次）。
    assertThat(instructionStatusOf(instructionId)).isEqualTo("CONSUMED");
    // 命令真的发到 warehouse 了。
    assertThat(warehouseExecutions).hasSize(1);
    assertThat(warehouseExecutions.get(0).get("dispositionId")).isEqualTo(dispositionId.toString());
    assertThat(warehouseExecutions.get(0).get("targetInventoryState")).isEqualTo("SELLABLE");
    // 再执行一次 → 拒（指令已消费）。
    var again =
        postJson(
            "/trade/return-dispositions/" + dispositionId + "/execute",
            Map.of("expectedVersion", versionOf(dispositionId)));
    assertThat(again.getResponse().getStatus()).isEqualTo(409);
  }

  @Test
  void 执行失败_绝不标成已执行_指令仍是待消费() throws Exception {
    UUID instructionId = seedInstruction("SELLABLE");
    var created =
        postJson(
            "/trade/return-dispositions",
            createBody(instructionId, "SELLABLE", "2", LOCATION_ID.toString()));
    UUID dispositionId = UUID.fromString(String.valueOf(dispositionOf(created).get("id")));
    long version = ((Number) dispositionOf(created).get("version")).longValue();
    var submitted =
        postJson(
            "/trade/return-dispositions/" + dispositionId + "/submit",
            Map.of("expectedVersion", version));
    long version2 = ((Number) dispositionOf(submitted).get("version")).longValue();
    var approved =
        postJson(
            "/trade/return-dispositions/" + dispositionId + "/approve",
            Map.of("expectedVersion", version2, "decisionNote", "同意"),
            "Bearer approver");
    long version3 = ((Number) dispositionOf(approved).get("version")).longValue();

    // 把单号改成 REJECT：warehouse 桩会明确拒绝。
    as(
        () -> {
          sql.execute(
              "update return_inventory_disposition set disposition_no='RDP-REJECT-1'"
                  + " where tenant_id=? and id=?",
              tenant,
              dispositionId);
          return null;
        });

    var executed =
        postJson(
            "/trade/return-dispositions/" + dispositionId + "/execute",
            Map.of("expectedVersion", version3));
    // ⚠️ 执行失败也返回 200：失败记录必须落库（抛错会把它一起回滚掉）。
    assertThat(executed.getResponse().getStatus()).isEqualTo(200);
    // 没有标成已执行，指令也没有被消费，失败原因写清楚了。
    assertThat(statusOf(dispositionId)).isEqualTo("EXECUTION_FAILED");
    assertThat(instructionStatusOf(instructionId)).isEqualTo("PENDING");
    assertThat(String.valueOf(dispositionOf(executed).get("executionFailureReason")))
        .isNotBlank();
  }

  @Test
  void 报废执行_损耗记录落库且财务字段恒为null() throws Exception {
    UUID instructionId = seedInstruction("SCRAP");
    var created = postJson("/trade/return-dispositions", createBody(instructionId, "SCRAP", "2", null));
    UUID dispositionId = UUID.fromString(String.valueOf(dispositionOf(created).get("id")));
    long version = ((Number) dispositionOf(created).get("version")).longValue();
    var submitted =
        postJson(
            "/trade/return-dispositions/" + dispositionId + "/submit",
            Map.of("expectedVersion", version));
    long version2 = ((Number) dispositionOf(submitted).get("version")).longValue();
    var approved =
        postJson(
            "/trade/return-dispositions/" + dispositionId + "/approve",
            Map.of("expectedVersion", version2, "decisionNote", "同意报废"),
            "Bearer approver");
    long version3 = ((Number) dispositionOf(approved).get("version")).longValue();
    var executed =
        postJson(
            "/trade/return-dispositions/" + dispositionId + "/execute",
            Map.of("expectedVersion", version3));
    assertThat(executed.getResponse().getStatus()).isEqualTo(200);

    Record loss =
        as(
            () ->
                sql.fetchOne(
                    "select scrap_qty,original_value,salvage_amount,proposed_loss_amount,"
                        + "finance_loss_amount,finance_currency,finance_reference,"
                        + "finance_confirmed_at,finance_owner_module,loss_status"
                        + " from return_inventory_disposition_loss where tenant_id=? and disposition_id=?",
                    tenant,
                    dispositionId));
    assertThat(loss).isNotNull();
    assertThat(loss.get("scrap_qty", BigDecimal.class)).isEqualByComparingTo("2");
    // 原值 = 订单行单价 5.0000 × 数量 2 = 10.0000（11.2.3.1 的触发器算的）。
    assertThat(loss.get("original_value", BigDecimal.class)).isEqualByComparingTo("10");
    // 残值 = 10 × min(0.9, 报废档上限 0.05) = 0.5
    assertThat(loss.get("salvage_amount", BigDecimal.class)).isEqualByComparingTo("0.5");
    // 损耗 = 原值 − 残值。
    assertThat(loss.get("proposed_loss_amount", BigDecimal.class)).isEqualByComparingTo("9.5");
    // ⚠️ 财务侧字段恒为 null —— 不是 0（填 0 会让财务以为这批货零损耗）。
    assertThat(loss.get("finance_loss_amount", BigDecimal.class)).isNull();
    assertThat(loss.get("finance_currency", String.class)).isNull();
    assertThat(loss.get("finance_reference", String.class)).isNull();
    assertThat(loss.get("finance_confirmed_at", OffsetDateTime.class)).isNull();
    assertThat(loss.get("finance_owner_module", String.class)).isEqualTo("12 财务");
    assertThat(loss.get("loss_status", String.class)).isEqualTo("PENDING_FINANCE");
  }

  @Test
  void 绕过服务层直连SQL也绕不过() throws Exception {
    UUID instructionId = seedInstruction("SELLABLE");

    // ① 数量与质检结论不一致的处置单插不进去（DB 触发器真查一次）。
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "insert into return_inventory_disposition(id,tenant_id,disposition_no,"
                              + "instruction_id,inspection_id,receipt_id,receipt_line_id,instruction_type,"
                              + "target_inventory_state,sku_id,sku_code,qty,source_bucket_id,"
                              + "source_warehouse_id,source_location_id,source_legal_entity_id,"
                              + "reason_code,reason_note,status,created_by)"
                              + " values(?::uuid,?::uuid,'RDP-HACK',?::uuid,?::uuid,?::uuid,?::uuid,"
                              + "'MOVE_TO_SELLABLE','SELLABLE',?::uuid,'SKU-1',1::numeric,?::uuid,"
                              + "?::uuid,?::uuid,?::uuid,'SELLABLE','绕过服务层','DRAFT',?::uuid)",
                          UUID.randomUUID(),
                          tenant,
                          instructionId,
                          UUID.randomUUID(),
                          UUID.randomUUID(),
                          UUID.randomUUID(),
                          SKU_ID,
                          BUCKET_ID,
                          WAREHOUSE_ID,
                          LOCATION_ID,
                          LEGAL_ENTITY_ID,
                          user);
                      return null;
                    }))
        .isNotNull();
    // 一行都没写进去。
    assertThat(countDispositions()).isZero();

    // ② 没有已执行处置单的指令改不成已消费。
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update return_quality_disposition_instruction"
                              + " set instruction_status='CONSUMED' where tenant_id=? and id=?",
                          tenant,
                          instructionId);
                      return null;
                    }))
        .isNotNull();
    assertThat(instructionStatusOf(instructionId)).isEqualTo("PENDING");
  }

  @Test
  void expectedVersion不符要拒() throws Exception {
    UUID instructionId = seedInstruction("SELLABLE");
    var created =
        postJson(
            "/trade/return-dispositions",
            createBody(instructionId, "SELLABLE", "2", LOCATION_ID.toString()));
    UUID dispositionId = UUID.fromString(String.valueOf(dispositionOf(created).get("id")));

    var mismatch =
        postJson(
            "/trade/return-dispositions/" + dispositionId + "/submit",
            Map.of("expectedVersion", 99));
    assertThat(mismatch.getResponse().getStatus()).isEqualTo(409);
  }

  @Test
  void 处置单没有删除端点_处置决定是举证材料() throws Exception {
    UUID instructionId = seedInstruction("SELLABLE");
    var created =
        postJson(
            "/trade/return-dispositions",
            createBody(instructionId, "SELLABLE", "2", LOCATION_ID.toString()));
    UUID dispositionId = UUID.fromString(String.valueOf(dispositionOf(created).get("id")));

    // 没有 DELETE 映射 ⇒ Spring 返回 405（处置决定只能靠状态机终结，不能被删掉）。
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/trade/return-dispositions/" + dispositionId)
                .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isMethodNotAllowed());
    // 行还在。
    assertThat(countDispositions()).isEqualTo(1);
  }

  @Test
  void 处置单列表与详情都能查到_并给出当前可执行动作() throws Exception {
    UUID instructionId = seedInstruction("SELLABLE");
    var created =
        postJson(
            "/trade/return-dispositions",
            createBody(instructionId, "SELLABLE", "2", LOCATION_ID.toString()));
    UUID dispositionId = UUID.fromString(String.valueOf(dispositionOf(created).get("id")));

    http.perform(get("/trade/return-dispositions").header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.summary.totalCount").value(1))
        .andExpect(jsonPath("$.summary.draftCount").value(1));

    http.perform(
            get("/trade/return-dispositions/" + dispositionId)
                .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.disposition.id").value(dispositionId.toString()))
        .andExpect(jsonPath("$.disposition.status").value("DRAFT"))
        .andExpect(jsonPath("$.actions.length()").value(org.hamcrest.Matchers.greaterThan(3)))
        .andExpect(jsonPath("$.instruction.id").value(instructionId.toString()));
  }

  @Test
  void 时间写回零漂移() throws Exception {
    UUID instructionId = seedInstruction("SELLABLE");
    var created =
        postJson(
            "/trade/return-dispositions",
            createBody(instructionId, "SELLABLE", "2", LOCATION_ID.toString()));
    UUID dispositionId = UUID.fromString(String.valueOf(dispositionOf(created).get("id")));

    OffsetDateTime createdAt = OffsetDateTime.parse(String.valueOf(dispositionOf(created).get("createdAt")));
    // 库里写回的时刻必须与写入时刻一致（不能偏 8 小时）。
    OffsetDateTime stored =
        as(
            () ->
                sql.fetchOne(
                        "select created_at from return_inventory_disposition where tenant_id=? and id=?",
                        tenant,
                        dispositionId)
                    .get("created_at", OffsetDateTime.class));
    assertThat(stored.toInstant()).isEqualTo(createdAt.toInstant());

    // 上游夹具刻意用 +08:00 非 UTC 偏移（退货实收时刻 T1）：
    // 读回的时刻必须还是同一个**瞬间**（用 Timestamp.valueOf(LocalDateTime) 写会静默偏 −28800s）。
    OffsetDateTime receivedAt =
        as(
            () ->
                sql.fetchOne(
                        "select received_at from return_receipt where tenant_id=? and rma_id="
                            + "(select id from rma_authorization where tenant_id=? and case_id in"
                            + " (select case_id from rma_authorization_line where tenant_id=?))",
                        tenant,
                        tenant,
                        tenant)
                    .get("received_at", OffsetDateTime.class));
    assertThat(receivedAt.toInstant()).isEqualTo(T1.toInstant());
  }
}
