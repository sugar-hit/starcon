package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.starcon.trade.ReturnReceiptModels.*;
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
 * 11.2.2.1 退货入库。
 *
 * <p>跑的是蓝图职责句的四个动词与那条加粗句：
 *
 * <blockquote>
 * 职责：登记退回实物：扫码核对 SKU 与序列号、清点数量、拍照留证、判定外包装与商品状态。
 * 输入：退货实收登记（SKU、数量、批次/序列号、照片）；退货授权单。
 * 输出：退货实收记录 + 关联授权行分配；待检库存入库分录（<b>不是可售</b>）。
 * <b>退回商品默认进待检状态</b>，这是硬规则——直接进可售会把残次品当新品卖出去。
 * </blockquote>
 *
 * <p><b>本类最看重的八组断言</b>：
 *
 * <ol>
 *   <li><b>硬规则 1 真的被钉死</b>：入库后分录的 inventoryState 恒 PENDING_INSPECTION、
 *       sellableQtyDelta 恒 0（<b>断言写回的值</b>），而且<b>绕过服务层直连 SQL 也写不进
 *       「已进可售」的分录</b> —— 表级 CHECK 当场拒。</li>
 *   <li><b>硬规则 2 两个维度真的独立</b>：外箱 DESTROYED + 商品 OPENED_UNDAMAGED 合法并存，
 *       任一维度仍是「未判定」时入库被拒，而 NOT_APPLICABLE <b>不算</b>「未判定」。</li>
 *   <li><b>硬规则 3 超收报错不截断</b>：明细超过授权量被拒（服务层 + DB 触发器各一道），
 *       草稿也占额度，且<b>绕过服务层直连 SQL 也插不进去</b>。</li>
 *   <li><b>实收行必须挂在本单的授权行上</b>：挂到另一张授权单的授权行被拒（DB 触发器）。</li>
 *   <li><b>只有批过的授权单能收</b>：拒绝 / 撤销 / 超时关闭的单一律收不了。</li>
 *   <li><b>入库即冻结</b>：入库后改单头 / 加行 / 加照片 / 撤销，服务层与 DB 触发器都拒。</li>
 *   <li><b>时间写回零漂移</b>：夹具刻意用 +08:00 非 UTC 偏移，
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s，只有这样才能测出来。</li>
 *   <li><b>照片与分录是追加式 / 不可改的</b>：权限已收回且带正对照。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReturnReceiptIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID other = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
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
      actors.put("Bearer tenant-main", actor(user, tenant));
      actors.put("Bearer tenant-other", actor(UUID.randomUUID(), other));
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

  @Autowired ReturnReceiptService receipts;
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

  /** **非 UTC 偏移**夹具：只有这种时刻才能测出 Timestamp.valueOf 的 −28800s 静默偏移。 */
  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");

  /** 寄回时限：带非 UTC 偏移的**将来**时刻（11.2.1.1 会拒掉过去时限，写死会整体塌掉）。 */
  static OffsetDateTime due() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8)).plusDays(14);
  }

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate return_receipt_photo, return_inventory_posting, return_receipt_line,"
              + " return_receipt_event, return_receipt,"
              + " rma_instruction_event, rma_return_tracking_event, rma_label,"
              + " rma_instruction_line, rma_instruction,"
              + " rma_decision_event, rma_authorization_line, rma_authorization,"
              + " aftersales_case_line, aftersales_case_note, aftersales_authorization,"
              + " aftersales_service_ticket, aftersales_owner_assignment, aftersales_case,"
              + " order_fulfillment_line_progress, sales_order cascade");
    }
  }

  <T> T as(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-main"), "trade.aftersales.write", work);
  }

  // ==========================================================================
  // 夹具：订单 → 售后单 → 退货授权单（已授权 3 件 / 订购 4 件）
  // ==========================================================================

  record Seeded(UUID rmaId, UUID caseId, UUID orderId, UUID orderLineId, UUID rmaLineId,
      String orderNo) {}

  record CaseSeeded(UUID caseId, UUID lineId, BigDecimal qty, BigDecimal amount) {}

  CaseSeeded seedCase(String orderNo) {
    return seedCase(orderNo, new BigDecimal("4"));
  }

  CaseSeeded seedCase(String orderNo, BigDecimal qty) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    BigDecimal unitPrice = new BigDecimal("5.0000");
    BigDecimal amount = unitPrice.multiply(qty);

    as(() -> {
      sql.execute(
          "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
              + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
              + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
              + " values(?,?,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,?::numeric,"
              + "0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
          orderId, tenant, orderNo, "EXT-" + orderNo, T1, amount, amount, T1, T1);
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
              + "created_at) values(?,?::uuid,?::uuid,1,?,?,?,'黑色 / L',?::numeric,?::numeric,"
              + "0,0,?::numeric,'',?::timestamptz)",
          lineId, tenant, orderId, UUID.randomUUID(), "SKU-" + orderNo, "商品" + orderNo,
          qty, unitPrice, amount, T1);
      sql.execute(
          "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
              + "recipient_phone,recipient_email,company_name,country_code,province,city,"
              + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
              + " values(?,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
              + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
          UUID.randomUUID(), tenant, orderId, "买家-" + orderNo, "buyer-" + orderNo + "@example.com",
          T1);
      sql.execute(
          "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
              + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
              + " values(?,?::uuid,?::uuid,?,?,?,'INDIVIDUAL','',?::timestamptz)",
          UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "C-" + orderNo,
          "买家-" + orderNo, T1);
      // 已发数量：11.2.1.1 用它算可退上限，没发出去的货不能退。
      sql.execute(
          "insert into order_fulfillment_line_progress(id,tenant_id,order_id,order_line_id,"
              + "ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,"
              + "observed_at,version,created_at,updated_at)"
              + " values(?,?::uuid,?::uuid,?::uuid,?::numeric,0,0,?::numeric,?::timestamptz,"
              + "'MODULE_9_3_3_1',?::timestamptz,0,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(), tenant, orderId, lineId, qty, qty, T1, T1, T1, T1);
      return null;
    });

    UUID caseId =
        as(() -> cases.create(
                new AftersalesCaseModels.AftersalesCaseCreate(
                    orderId, "RETURN_REFUND", "QUALITY_DEFECT", "外箱压损",
                    amount, "USD", qty, "PLAT-" + orderNo, null, null,
                    "买家诉求-" + orderNo, T1, null, T1,
                    null, null, null, "TENANT_MAINTAINED", null, T1),
                user)
            .id());
    as(() -> details.addLine(
            caseId,
            new AftersalesCaseDetailModels.AftersalesCaseLineCreate(
                lineId, qty, amount, "USD", "第 1 件"),
            user));
    return new CaseSeeded(caseId, lineId, qty, amount);
  }

  /** 建一张**已授权**的退货授权单：订购 4 件、授权 3 件（留出「部分授权」的口子）。 */
  Seeded seedAuthorized(String orderNo) {
    CaseSeeded c = seedCase(orderNo);
    return authorize(c, orderNo, new BigDecimal("3"), "PARTIALLY_APPROVED", due());
  }

  Seeded authorize(
      CaseSeeded c, String orderNo, BigDecimal authQty, String decision, OffsetDateTime dueAt) {
    UUID rmaId =
        as(() -> rma.create(
                new RmaAuthorizationModels.RmaCreate(
                    c.caseId(), "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                    "退货仓-" + orderNo, "+1-213-555-0199", "rma-" + orderNo + "@example.com",
                    "US", "CA", "ONTARIO", "", "M5V 2T6", "88 Yonge St", "3 楼", null, null),
                user)
            .head()
            .id());
    var detail = as(() -> rma.detail(rmaId));
    UUID rmaLineId = detail.lines().get(0).id();
    as(() -> rma.decide(
        rmaId,
        new RmaAuthorizationModels.RmaDecide(
            decision, dueAt, "外包装压损，只退部分",
            List.of(new RmaAuthorizationModels.RmaLineDecision(rmaLineId, authQty, null)),
            0L),
        user));
    return new Seeded(rmaId, c.caseId(), null, c.lineId(), rmaLineId, orderNo);
  }

  // ==========================================================================
  // 请求构造
  // ==========================================================================

  private ReturnReceiptCreate createRequest(UUID rmaId, String channel) {
    return new ReturnReceiptCreate(
        rmaId,
        null,
        channel,
        "CARRIER".equals(channel) ? "FedEx" : null,
        "CARRIER".equals(channel) ? "RT-778899" : null,
        "外箱一角压瘪",
        T1,
        List.of(
            new ReturnReceiptLineDraft(
                null, "SKU-X", "SN-" + UUID.randomUUID(), null,
                new BigDecimal("2"), "DAMAGED", "OPENED_UNDAMAGED", "外箱压损")),
        List.of(
            new ReturnPhotoDraft(
                "TENANT_MANUAL_UPLOAD", null, "PACKAGING",
                "https://obj.example.com/photo-" + UUID.randomUUID() + ".jpg",
                "a".repeat(64), 2048L, "image/jpeg", T1, "外箱压损")));
  }

  /** 建一张草稿（数量可配，两个判定维度可配）。 */
  private ReturnReceiptDetail draft(
      Seeded s, BigDecimal qty, String packaging, String goods, String channel) {
    var base = createRequest(s.rmaId(), channel);
    UUID serial = UUID.randomUUID();
    return as(() -> receipts.create(
        new ReturnReceiptCreate(
            base.rmaId(), base.instructionId(), base.receiptChannel(), base.carrierCode(),
            base.returnTrackingNo(), base.packageNote(), base.receivedAt(),
            List.of(new ReturnReceiptLineDraft(
                s.rmaLineId(), "SKU-X", "SN-" + serial, null, qty, packaging, goods, "外箱压损")),
            base.photos()),
        user));
  }

  /** 建一张草稿并入库（两个维度默认已判）。 */
  private ReturnReceiptDetail posted(Seeded s) {
    return posted(s, new BigDecimal("2"), "DAMAGED", "OPENED_UNDAMAGED");
  }

  private ReturnReceiptDetail posted(
      Seeded s, BigDecimal qty, String packaging, String goods) {
    var d = draft(s, qty, packaging, goods, "CARRIER");
    return as(() -> receipts.post(d.head().id(), new ReturnReceiptPost(null, d.head().version()), user));
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  @Test
  void 能力声明逐块报可用性且未交付下游一律归属模块号() {
    var caps = as(receipts::capabilities);

    assertThat(caps.sections()).isNotEmpty();
    // 职责句的四个动词逐块 Delivered=true。
    for (String section :
        List.of("scanVerify", "countQty", "photoEvidence", "packagingVsGoods",
            "pendingInspectionPosting", "rmaLineAllocation")) {
      var s = caps.sections().stream().filter(x -> x.section().equals(section)).findFirst();
      assertThat(s).as("能力声明缺区块 " + section).isPresent();
      assertThat(s.get().available()).as(section + " 应为已交付").isTrue();
    }

    // ⚠️ **未交付的下游一律 available=false + 归属模块号**，
    //   而且 href 必须为 null —— 一个点进去 404 的链接比没有链接更糟。
    for (String section : List.of("allocationToOrderLine", "qualityInspection", "disposition",
        "inventoryApply", "batchAttribution")) {
      var s = caps.sections().stream().filter(x -> x.section().equals(section)).findFirst();
      assertThat(s).as("能力声明缺区块 " + section).isPresent();
      assertThat(s.get().available()).as(section + " 未交付，不该报 true").isFalse();
      assertThat(s.get().ownerModule()).as(section + " 必须报归属模块号").isNotBlank();
      assertThat(s.get().landsIn()).as(section + " 未交付，不该报落点表").isNull();
    }

    for (ReturnReceiptRelatedModule m : caps.relatedModules()) {
      if (!m.delivered()) {
        assertThat(m.ownerModule()).as(m.id() + " 未交付必须带归属模块").isNotBlank();
        assertThat(m.href()).as(m.id() + " 未交付不得给链接").isNull();
      }
    }

    // 「转可售」动作**永远** allowed=false：不是暂时没做，是刻意没有。
    var resell = caps.actions().stream().filter(a -> a.code().equals("RESELL")).findFirst();
    assertThat(resell).isPresent();
    assertThat(resell.get().allowed()).isFalse();
    assertThat(resell.get().ownerModule()).isEqualTo("11.2.3.2");

    assertThat(caps.boundaries()).isNotEmpty();
    assertThat(caps.inventoryNote()).contains("待检").contains("可售");
  }

  // ==========================================================================
  // 硬规则 1：退回商品默认进待检，绝不进可售
  // ==========================================================================

  @Test
  void 硬规则1_入库分录恒为待检且可售增量恒为0() {
    var s = seedAuthorized("RR-PEND-1");
    var d = posted(s, new BigDecimal("2"), "DESTROYED", "DAMAGED");

    assertThat(d.head().receiptStatus()).isEqualTo("POSTED");
    assertThat(d.inventoryState()).isEqualTo("PENDING_INSPECTION");
    assertThat(d.pendingInspectionQty()).isEqualByComparingTo("2");
    // ⚠️ **断言写回的值**：可售增量恒 0 —— 这就是硬规则 1 在传输层的落点。
    assertThat(d.sellableQtyDelta()).isEqualByComparingTo("0");

    assertThat(d.postings()).hasSize(1);
    var posting = d.postings().get(0);
    assertThat(posting.inventoryState()).isEqualTo("PENDING_INSPECTION");
    assertThat(posting.postingType()).isEqualTo("RETURN_INTAKE");
    assertThat(posting.pendingInspectionQty()).isEqualByComparingTo("2");
    assertThat(posting.sellableQtyDelta()).isEqualByComparingTo("0");
    // 分录自带 SKU 快照（跨服务交接凭据）：订单行被改名之后它仍然读得出 SKU。
    assertThat(posting.skuCode()).isEqualTo("SKU-RR-PEND-1");
    // 判定快照也进分录：待检队列按它排检不必回实收行做连接。
    assertThat(posting.packagingCondition()).isEqualTo("DESTROYED");
    assertThat(posting.goodsCondition()).isEqualTo("DAMAGED");

    // 列表页同样把两格摆出来（正对照用）。
    var page = as(() -> receipts.list(new ReturnReceiptListQuery(null, null, null, null,
        s.rmaId(), null, null, null, null, null, null, null, null)));
    assertThat(page.rows()).hasSize(1);
    assertThat(page.rows().get(0).sellableQtyDelta()).isEqualByComparingTo("0");
    assertThat(page.rows().get(0).inventoryState()).isEqualTo("PENDING_INSPECTION");
    assertThat(page.rows().get(0).inspectionCompleted()).isFalse();
    assertThat(page.rows().get(0).inspectionOwnerModule()).isEqualTo("11.2.3.1");
    assertThat(page.summary().sellableQtyDelta()).isEqualByComparingTo("0");
    assertThat(page.summary().pendingInspectionQty()).isEqualByComparingTo("2");
  }

  @Test
  void 硬规则1_绕过服务层直连sql也写不进已进可售的分录() {
    var s = seedAuthorized("RR-SELLSQL");
    var d = posted(s);
    UUID lineId = d.lines().get(0).id();

    // ⚠️ **绕过服务层**：直接 INSERT 一条 inventory_state='SELLABLE' 的分录。
    //   服务的接口层面根本没有这个字段，这条 SQL 只有手工构造才写得出来 ——
    //   正是它能证明表级 CHECK 真的在挡，而不是「反正没人会这么调」。
    //   ⚠️ 这一条**只**违反库存状态这一道（sellable 增量给 0）：
    //   两道同时违反时报的是合并 CHECK 名 `return_inventory_posting_check`，
    //   断言就分不清到底是被哪一道挡下的。
    assertThatThrownBy(
            () ->
                as(() -> {
                  sql.execute(
                      "insert into return_inventory_posting(id,tenant_id,receipt_line_id,receipt_id,"
                          + "inventory_state,sellable_qty_delta,pending_inspection_qty,"
                          + "packaging_condition,goods_condition,posted_by)"
                          + " values(?,?::uuid,?::uuid,?::uuid,'SELLABLE',0,1,'INTACT','UNOPENED',"
                          + "?::uuid)",
                      UUID.randomUUID(), tenant, lineId, d.head().id(), user);
                  return null;
                }))
        .as("表级 CHECK 必须拒掉任何非 PENDING_INSPECTION 的入库状态")
        .hasMessageContaining("return_inventory_posting_inventory_state_check");

    // ⚠️ 同一条硬规则的第二道：可售增量必须恒 0。
    assertThatThrownBy(
            () ->
                as(() -> {
                  sql.execute(
                      "insert into return_inventory_posting(id,tenant_id,receipt_line_id,receipt_id,"
                          + "sellable_qty_delta,pending_inspection_qty,"
                          + "packaging_condition,goods_condition,posted_by)"
                          + " values(?,?::uuid,?::uuid,?::uuid,1,1,'INTACT','UNOPENED',?::uuid)",
                      UUID.randomUUID(), tenant, lineId, d.head().id(), user);
                  return null;
                }))
        .as("表级 CHECK 必须拒掉任何非 0 的可售增量")
        .hasMessageContaining("return_inventory_posting_check");

    // ⚠️ **正面证明**（只看报错名不够）：表级 CHECK 的定义里**确实**写着
    //   inventory_state = 'PENDING_INSPECTION' 与 sellable_qty_delta = 0。
    //   报错约束名只能证明「有一道 CHECK 挡了」，证明不了「挡的是这两条规则」。
    var checks =
        sql.fetch(
            "select conname, pg_get_constraintdef(oid) as def from pg_constraint"
                + " where conrelid = 'return_inventory_posting'::regclass and contype = 'c'");
    assertThat(checks).isNotEmpty();
    // ⚠️ pg_get_constraintdef 的实际文本带类型转换括号（`= (0)::numeric`），
    //   按裸 SQL 写法匹配会永远匹配不上 —— 那是一条自己都过不去的断言。
    assertThat(checks.map(r -> r.get("def", String.class)))
        .as("表级 CHECK 的定义必须真的写着这两条硬规则")
        .anyMatch(def -> def.contains("sellable_qty_delta = (0)::numeric"));
    assertThat(checks.map(r -> r.get("def", String.class)))
        .anyMatch(def -> def.contains("= 'PENDING_INSPECTION'::text"));

    // 正对照：分录行数没有被上面两次失败写进去（防假绿）。
    assertThat(as(() -> sql.fetch("select * from return_inventory_posting").size())).isEqualTo(1);
    assertThat(as(() -> sql.fetchOne(
            "select sellable_qty_delta from return_inventory_posting where receipt_line_id = ?::uuid",
            lineId).get(0, BigDecimal.class)))
        .isEqualByComparingTo("0");
  }

  @Test
  void 硬规则1_入库不可逆且不生成任何反向冲销分录() {
    var s = seedAuthorized("RR-NOREV");
    var d = posted(s);

    // 已入库的单不可撤销：分录已生成，回冲属 11.2.3.2（未交付）。
    assertThatThrownBy(
            () -> as(() -> receipts.cancel(d.head().id(),
                new ReturnReceiptCancel("录错了", d.head().version()), user)))
        .as("已入库的实收单不能撤销")
        .hasMessageContaining("不能撤销");
    assertThatThrownBy(
            () -> as(() -> receipts.post(d.head().id(),
                new ReturnReceiptPost(null, d.head().version()), user)))
        .hasMessageContaining("已入库");

    // 没有「反向冲销分录」：撤销走的是显式状态，不是打一条负数分录。
    // （AGENTS.md：每张事实表只有一个写入所有者，反向冲销会让账有两份真相。）
    assertThat(as(() -> sql.fetchOne(
            "select count(*) from return_inventory_posting where tenant_id = ?::uuid and"
                + " receipt_id = ?::uuid",
            tenant, d.head().id()).get(0, Long.class)))
        .isEqualTo(1L);
  }

  // ==========================================================================
  // 硬规则 2：外包装与商品状态是两个独立维度
  // ==========================================================================

  @Test
  void 硬规则2_两个维度分别落库分别展示() {
    var s = seedAuthorized("RR-DIM");

    // 外箱损毁 + 商品完好：蓝图 11.2.3.1 的加粗句场景，必须能表达。
    var d = posted(s, new BigDecimal("1"), "DESTROYED", "OPENED_UNDAMAGED");
    var line = d.lines().get(0);
    assertThat(line.packagingCondition()).isEqualTo("DESTROYED");
    assertThat(line.packagingConditionLabel()).isEqualTo("外包装损毁");
    assertThat(line.goodsCondition()).isEqualTo("OPENED_UNDAMAGED");
    assertThat(line.goodsConditionLabel()).isEqualTo("已拆封、无损坏");
    assertThat(line.bothDimensionsJudged()).isTrue();

    // 两列在库里也是**两列**：改一列不影响另一列。
    as(() -> {
      sql.execute(
          "update return_receipt_line set goods_condition = 'MISSING_PARTS'"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          tenant, line.id());
      return null;
    });
    var after = as(() -> receipts.detail(d.head().id()));
    assertThat(after.lines().get(0).packagingCondition()).isEqualTo("DESTROYED");
    assertThat(after.lines().get(0).goodsCondition()).isEqualTo("MISSING_PARTS");

    // ⚠️ 绕过服务层直连 SQL 也分得清（两列各自有 CHECK，缺一列过不去）。
    assertThatThrownBy(
            () ->
                as(() -> {
                  sql.execute(
                      "update return_receipt_line set packaging_condition = '压坏了'"
                          + " where tenant_id = ?::uuid and id = ?::uuid",
                      tenant, line.id());
                  return null;
                }))
        .as("外包装判定不在枚举内必须被 CHECK 拒")
        .hasMessageContaining("return_receipt_line_packaging_condition_check");
  }

  @Test
  void 硬规则2_任一维度未判定则入库被拒而NA不算未判定() {
    var s1 = seedAuthorized("RR-UNJUDGED-PKG");
    var d1 = draft(s1, new BigDecimal("1"), "NOT_INSPECTED", "OPENED_UNDAMAGED", "CARRIER");
    assertThatThrownBy(
            () -> as(() -> receipts.post(d1.head().id(),
                new ReturnReceiptPost(null, d1.head().version()), user)))
        .as("外包装未判定不能入库")
        .hasMessageContaining("两个维度");

    var s2 = seedAuthorized("RR-UNJUDGED-GOODS");
    var d2 = draft(s2, new BigDecimal("1"), "INTACT", "NOT_INSPECTED", "CARRIER");
    assertThatThrownBy(
            () -> as(() -> receipts.post(d2.head().id(),
                new ReturnReceiptPost(null, d2.head().version()), user)))
        .as("商品状态未判定不能入库")
        .hasMessageContaining("两个维度");

    // ⚠️ NOT_APPLICABLE **不算**「未判定」：散装 / 无包装无配件的 SKU 本来就没有这个维度，
    //   合成一个值会让入库闸门把「不适用」当成「还没判」一直拦住。
    var s3 = seedAuthorized("RR-NA");
    var d3 = draft(s3, new BigDecimal("1"), "MISSING", "NOT_APPLICABLE", "WALK_IN");
    var ok = as(() -> receipts.post(d3.head().id(),
        new ReturnReceiptPost(null, d3.head().version()), user));
    assertThat(ok.head().receiptStatus()).isEqualTo("POSTED");
    assertThat(ok.postings().get(0).goodsCondition()).isEqualTo("NOT_APPLICABLE");
  }

  // ==========================================================================
  // 硬规则 3：关联授权行、超收报错不截断
  // ==========================================================================

  @Test
  void 硬规则3_实收超过授权量被拒且是报错不是截断() {
    var s = seedAuthorized("RR-OVER");
    assertThat(as(() -> receipts.receivable(s.rmaId()).authorizedTotal()))
        .isEqualByComparingTo("3");

    assertThatThrownBy(() -> draft(s, new BigDecimal("4"), "INTACT", "UNOPENED", "CARRIER"))
        .as("实收 4 件 > 授权 3 件必须报错")
        .hasMessageContaining("超过该授权行剩余可收量");

    // 截断是最坏的处理：录进去的 4 件会被悄悄按 3 件算，
    // 于是「买家寄了几件」与「我们记了几件」从此对不上，而两边都以为自己是对的。
    assertThat(as(() -> sql.fetch("select * from return_receipt_line").size())).isZero();
    assertThat(as(() -> receipts.receivable(s.rmaId()).takenTotal())).isEqualByComparingTo("0");
  }

  @Test
  void 硬规则3_草稿也占额度所以并发两张草稿也会有一张被拒() {
    var s = seedAuthorized("RR-DRAFT-HOLD");
    var d1 = draft(s, new BigDecimal("2"), "INTACT", "UNOPENED", "CARRIER");
    assertThat(d1.head().receiptStatus()).isEqualTo("DRAFT");

    // ⚠️ **草稿占额度**：只算已入库的话，两张草稿各自校验时都合法，
    //   并发入库时总量已经超了。
    assertThat(as(() -> receipts.receivable(s.rmaId()).takenTotal())).isEqualByComparingTo("2");
    assertThatThrownBy(() -> draft(s, new BigDecimal("2"), "INTACT", "UNOPENED", "CARRIER"))
        .as("第二张草稿只剩 1 件额度，2 件必须被拒")
        .hasMessageContaining("已被");
    // 精确卡边：剩 1 件时收 1 件必须放行（额度算错 1 也会让真实收货进不来）。
    assertThat(draft(s, new BigDecimal("1"), "INTACT", "UNOPENED", "CARRIER")
            .lines().get(0).receivedQty())
        .isEqualByComparingTo("1");

    // 撤销草稿后额度立刻归还（否则一次录错就得永远等别人撤销）。
    as(() -> receipts.cancel(d1.head().id(), new ReturnReceiptCancel("扫错了", d1.head().version()), user));
    assertThat(as(() -> receipts.receivable(s.rmaId()).takenTotal())).isEqualByComparingTo("1");
    assertThat(draft(s, new BigDecimal("2"), "INTACT", "UNOPENED", "CARRIER").head().id())
        .isNotNull();
  }

  @Test
  void 硬规则3_绕过服务层直连sql也插不进超收的行() {
    var s = seedAuthorized("RR-OVER-SQL");
    var d = draft(s, new BigDecimal("1"), "INTACT", "UNOPENED", "CARRIER");
    UUID lineId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                as(() -> {
                  sql.execute(
                      "insert into return_receipt_line(id,tenant_id,receipt_id,"
                          + "rma_authorization_line_id,scanned_serial_no,received_qty,"
                          + "packaging_condition,goods_condition,counted_by,counted_at,line_no,"
                          + "created_at) values(?,?::uuid,?::uuid,?::uuid,?,?::numeric,'INTACT',"
                          + "'UNOPENED',?::uuid,?::timestamptz,99,?::timestamptz)",
                      lineId, tenant, d.head().id(), s.rmaLineId(), "SN-SQL-" + UUID.randomUUID(),
                      new BigDecimal("3"), user, T1, T1);
                  return null;
                }))
        .as("DB 触发器必须独立于服务层拦住超收")
        .hasMessageContaining("超过该授权行剩余可收量");

    assertThat(as(() -> sql.fetch("select * from return_receipt_line").size())).isEqualTo(1);
  }

  @Test
  void 硬规则3_入库时刻对整单再算一次上限() {
    // 授权 3 件（订购 4 件，留出余量），先录一张 3 件的草稿（此刻合法）。
    var c = seedCase("RR-POSTSUM");
    var s = authorize(c, "RR-POSTSUM", new BigDecimal("3"), "PARTIALLY_APPROVED", due());
    var d = draft(s, new BigDecimal("3"), "INTACT", "UNOPENED", "CARRIER");

    // 绕过服务层把授权量改成 2 件（模拟授权被下调 / 数据被改），
    // 然后让这张草稿入库：入库时刻那一道必须拦住 ——
    // 只在录入时校验的话，两个并发提交会双双越过。
    as(() -> {
      sql.execute(
          "update rma_authorization_line set authorized_qty = 2 where tenant_id = ?::uuid"
              + " and id = ?::uuid",
          tenant, s.rmaLineId());
      return null;
    });

    assertThatThrownBy(
            () -> as(() -> receipts.post(d.head().id(),
                new ReturnReceiptPost(null, d.head().version()), user)))
        .as("入库时刻必须重新校验上限")
        .hasMessageContaining("入库合计");

    // 没有分录被生成（异常回滚）。
    assertThat(as(() -> sql.fetch("select * from return_inventory_posting").size())).isZero();
  }

  @Test
  void 实收行必须挂在本单的授权行上() {
    var s1 = seedAuthorized("RR-ALIGN-1");
    var s2 = seedAuthorized("RR-ALIGN-2");
    var d = draft(s1, new BigDecimal("1"), "INTACT", "UNOPENED", "CARRIER");

    // ⚠️ 挂到**另一张**授权单的授权行上：少了这一段它会完全合法，
    //   于是「A 授权单的实收量」被记到 B 的额度上。
    assertThatThrownBy(
            () ->
                as(() -> {
                  sql.execute(
                      "insert into return_receipt_line(id,tenant_id,receipt_id,"
                          + "rma_authorization_line_id,scanned_serial_no,received_qty,"
                          + "packaging_condition,goods_condition,counted_by,counted_at,line_no,"
                          + "created_at) values(?,?::uuid,?::uuid,?::uuid,?,?::numeric,'INTACT',"
                          + "'UNOPENED',?::uuid,?::timestamptz,99,?::timestamptz)",
                      UUID.randomUUID(), tenant, d.head().id(), s2.rmaLineId(),
                      "SN-X-" + UUID.randomUUID(), new BigDecimal("1"), user, T1, T1);
                  return null;
                }))
        .as("实收行必须挂在本单所关联的退货授权单的授权行上")
        .hasMessageContaining("不属于本实收单所关联的退货授权单");
  }

  @Test
  void 剩余可收量端点把授权量已被占还能收并排给出() {
    var s = seedAuthorized("RR-RECV");
    assertThat(as(() -> receipts.receivable(s.rmaId()).remainingTotal()))
        .isEqualByComparingTo("3");

    posted(s, new BigDecimal("2"), "INTACT", "UNOPENED");
    var after = as(() -> receipts.receivable(s.rmaId()));
    var row = after.rows().get(0);
    assertThat(row.authorizedQty()).isEqualByComparingTo("3");
    assertThat(row.takenQty()).isEqualByComparingTo("2");
    assertThat(row.remainingQty()).isEqualByComparingTo("1");
    assertThat(row.overAuthorized()).isFalse();
    assertThat(row.skuCode()).isEqualTo("SKU-RR-RECV");
    assertThat(after.note()).contains("草稿也占额度");

    // 别的租户的授权单取不到（读路径同样带租户谓词）。
    assertThatThrownBy(() -> as(() -> receipts.receivable(UUID.randomUUID())))
        .hasMessageContaining("不存在或不属于当前租户");
  }

  // ==========================================================================
  // 上游：只有批过的授权单能收
  // ==========================================================================

  @Test
  void 只有已授权的授权单能收且绕过服务层也不行() {
    var c = seedCase("RR-UNAPPROVED");
    UUID rmaId =
        as(() -> rma.create(
                new RmaAuthorizationModels.RmaCreate(
                    c.caseId(), "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                    "退货仓-UNAPPROVED", "+1-213-555-0199", "rma-un@example.com",
                    "US", "CA", "ONTARIO", "", "M5V 2T6", "88 Yonge St", "3 楼", null, null),
                user)
            .head()
            .id());
    // 草稿行的授权量恒 0 → 连额度都不该有。
    UUID rmaLineId = as(() -> rma.detail(rmaId)).lines().get(0).id();

    // 绕过服务层：直连 SQL 建单头 + 建明细行。
    UUID receiptId = UUID.randomUUID();
    as(() -> {
      sql.execute(
          "insert into return_receipt(id,tenant_id,receipt_no,rma_id,receipt_channel,"
              + "carrier_code,return_tracking_no,received_at,receipt_status,created_by,created_at)"
              + " values(?,?::uuid,'RTR-UNAPPROVED',?::uuid,'CARRIER','FedEx','RT-X',"
              + "?::timestamptz,'DRAFT',?::uuid,?::timestamptz)",
          receiptId, tenant, rmaId, T1, user, T1);
      return null;
    });
    assertThatThrownBy(
            () ->
                as(() -> {
                  sql.execute(
                      "insert into return_receipt_line(id,tenant_id,receipt_id,"
                          + "rma_authorization_line_id,scanned_serial_no,received_qty,"
                          + "packaging_condition,goods_condition,counted_by,counted_at,line_no,"
                          + "created_at) values(?,?::uuid,?::uuid,?::uuid,?,?::numeric,'INTACT',"
                          + "'UNOPENED',?::uuid,?::timestamptz,1,?::timestamptz)",
                      UUID.randomUUID(), tenant, receiptId, rmaLineId,
                      "SN-" + UUID.randomUUID(), new BigDecimal("1"), user, T1, T1);
                  return null;
                }))
        .as("未审批的授权行（授权量 0）不该收得到货")
        .hasMessageContaining("不在授权状态");
  }

  @Test
  void 拒绝的授权单不能收() {
    var c = seedCase("RR-REJECTED");
    UUID rmaId =
        as(() -> rma.create(
                new RmaAuthorizationModels.RmaCreate(
                    c.caseId(), "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                    "退货仓-REJ", "+1-213-555-0199", "rma-rej@example.com",
                    "US", "CA", "ONTARIO", "", "M5V 2T6", "88 Yonge St", "3 楼", null, null),
                user)
            .head()
            .id());
    var rmaLineId = as(() -> rma.detail(rmaId)).lines().get(0).id();
    as(() -> rma.decide(
        rmaId,
        new RmaAuthorizationModels.RmaDecide(
            "REJECTED", due(), "已过售后期",
            List.of(new RmaAuthorizationModels.RmaLineDecision(rmaLineId, new BigDecimal("3"), null)),
            0L),
        user));

    UUID receiptId = UUID.randomUUID();
    as(() -> {
      sql.execute(
          "insert into return_receipt(id,tenant_id,receipt_no,rma_id,receipt_channel,"
              + "carrier_code,return_tracking_no,received_at,receipt_status,created_by,created_at)"
              + " values(?,?::uuid,'RTR-REJECTED',?::uuid,'CARRIER','FedEx','RT-X',"
              + "?::timestamptz,'DRAFT',?::uuid,?::timestamptz)",
          receiptId, tenant, rmaId, T1, user, T1);
      return null;
    });
    assertThatThrownBy(
            () ->
                as(() -> {
                  sql.execute(
                      "insert into return_receipt_line(id,tenant_id,receipt_id,"
                          + "rma_authorization_line_id,scanned_serial_no,received_qty,"
                          + "packaging_condition,goods_condition,counted_by,counted_at,line_no,"
                          + "created_at) values(?,?::uuid,?::uuid,?::uuid,?,?::numeric,'INTACT',"
                          + "'UNOPENED',?::uuid,?::timestamptz,1,?::timestamptz)",
                      UUID.randomUUID(), tenant, receiptId, rmaLineId,
                      "SN-" + UUID.randomUUID(), new BigDecimal("1"), user, T1, T1);
                  return null;
                }))
        .as("拒绝的授权单不该收得到货")
        .hasMessageContaining("不在授权状态");
  }

  // ==========================================================================
  // 入库即冻结 / 乐观锁
  // ==========================================================================

  @Test
  void 入库即冻结单头与行且撤销入口关闭() {
    var s = seedAuthorized("RR-FROZEN");
    var d = posted(s);

    assertThatThrownBy(
            () ->
                as(() -> receipts.update(
                    d.head().id(),
                    new ReturnReceiptUpdate(null, "CARRIER", "FedEx", "RT-778899", "改备注", T1,
                        d.lines().stream()
                            .map(l -> new ReturnReceiptLineDraft(
                                l.rmaLineId(), l.scannedSkuCode(), l.scannedSerialNo(), l.batchNo(),
                                l.receivedQty(), l.packagingCondition(), l.goodsCondition(), "x"))
                            .toList(),
                        d.head().version()),
                    user)))
        .as("入库后不能改单头要素")
        .hasMessageContaining("已入库");

    assertThatThrownBy(
            () ->
                as(() -> receipts.appendPhoto(
                    d.head().id(),
                    new ReturnPhotoAppend("TENANT_MANUAL_UPLOAD", null, "GOODS",
                        "https://obj.example.com/late.jpg", "b".repeat(64), 1024L, "image/jpeg",
                        T1, "补拍"),
                    user)))
        .as("入库后证据不能事后补改")
        .hasMessageContaining("已入库");

    // 详情里的动作也必须报出不可用 + 理由（按钮变灰而不给理由会让人以为系统坏了）。
    var detail = as(() -> receipts.detail(d.head().id()));
    var edit = detail.actions().stream().filter(a -> a.code().equals("EDIT_DRAFT")).findFirst().get();
    assertThat(edit.allowed()).isFalse();
    assertThat(edit.blockingReason()).isNotBlank();
    var resell = detail.actions().stream().filter(a -> a.code().equals("RESELL")).findFirst().get();
    assertThat(resell.allowed()).isFalse();
    assertThat(resell.ownerModule()).isEqualTo("11.2.3.2");
    assertThat(resell.unavailableReason()).contains("待检");
  }

  @Test
  void 版本不符与缺失版本都拒绝() {
    var s = seedAuthorized("RR-VER");
    var d = draft(s, new BigDecimal("1"), "INTACT", "UNOPENED", "CARRIER");

    assertThatThrownBy(
            () -> as(() -> receipts.post(d.head().id(), new ReturnReceiptPost(null, null), user)))
        .as("必须给 expectedVersion")
        .hasMessageContaining("必须给出 expectedVersion");
    assertThatThrownBy(
            () -> as(() -> receipts.post(d.head().id(),
                new ReturnReceiptPost(null, d.head().version() + 5), user)))
        .as("版本不符必须拒绝")
        .hasMessageContaining("版本不符");

    // 撤销也必须带理由。
    assertThatThrownBy(
            () -> as(() -> receipts.cancel(d.head().id(),
                new ReturnReceiptCancel("  ", d.head().version()), user)))
        .hasMessageContaining("必须说明撤销理由");
  }

  @Test
  void 空单不能入库() {
    var s = seedAuthorized("RR-EMPTY");
    var base = createRequest(s.rmaId(), "CARRIER");
    var d =
        as(() -> receipts.create(
            new ReturnReceiptCreate(
                base.rmaId(), null, "CARRIER", "FedEx", "RT-778899", "", T1,
                List.of(new ReturnReceiptLineDraft(
                    s.rmaLineId(), "SKU-X", "SN-" + UUID.randomUUID(), null,
                    new BigDecimal("1"), "INTACT", "UNOPENED", "")),
                List.of()),
            user));
    as(() -> {
      sql.execute(
          "delete from return_receipt_line where tenant_id = ?::uuid and receipt_id = ?::uuid",
          tenant, d.head().id());
      return null;
    });

    assertThatThrownBy(
            () -> as(() -> receipts.post(d.head().id(),
                new ReturnReceiptPost(null, d.head().version()), user)))
        .as("空单入库等于什么都没收到却生成了入库记录")
        .hasMessageContaining("没有任何明细行");
    assertThat(as(() -> sql.fetch("select * from return_inventory_posting").size())).isZero();
  }

  // ==========================================================================
  // 拍照留证
  // ==========================================================================

  @Test
  void 照片追加式且同一内容不重复登记() {
    var s = seedAuthorized("RR-PHOTO");
    var d = draft(s, new BigDecimal("1"), "DAMAGED", "OPENED_UNDAMAGED", "CARRIER");
    assertThat(d.photos()).hasSize(1);
    // 拍摄时刻与登记时刻**分两列**：两列之差就是「货到了多久才被拍下来」。
    assertThat(d.photos().get(0).capturedAt()).isEqualTo(T1);
    assertThat(d.photos().get(0).registeredAt()).isNotNull();

    assertThatThrownBy(
            () ->
                as(() -> receipts.appendPhoto(
                    d.head().id(),
                    new ReturnPhotoAppend("TENANT_MANUAL_UPLOAD", null, "GOODS",
                        "https://obj.example.com/dup.jpg", "a".repeat(64), 1024L, "image/jpeg",
                        T1, "重复内容"),
                    user)))
        .as("同一 sha256 不重复登记：重复的举证材料会让「拍了几张照」有两个答案")
        .hasMessageContaining("duplicate key");

    // 校验和格式必须真校验：64 位十六进制。
    assertThatThrownBy(
            () ->
                as(() -> receipts.appendPhoto(
                    d.head().id(),
                    new ReturnPhotoAppend("TENANT_MANUAL_UPLOAD", null, "GOODS",
                        "https://obj.example.com/x.jpg", "abc", 1024L, "image/jpeg", T1, null),
                    user)))
        .hasMessageContaining("64 位十六进制");

    // 写成 18 集成来源时必须给 source_reference。
    assertThatThrownBy(
            () ->
                as(() -> receipts.appendPhoto(
                    d.head().id(),
                    new ReturnPhotoAppend("MODULE_18_OBJECT_STORAGE_SYNC", null, "GOODS",
                        "https://obj.example.com/x.jpg", "c".repeat(64), 1024L, "image/jpeg",
                        T1, null),
                    user)))
        .hasMessageContaining("source_reference");
  }

  // ==========================================================================
  // 时间：写回零漂移
  // ==========================================================================

  @Test
  void 时间写回零漂移() {
    var s = seedAuthorized("RR-TIME");
    var d = posted(s);
    var head = as(() -> receipts.detail(d.head().id())).head();

    // ⚠️ **断言写回的值**：夹具是 2026-03-01T08:00+08:00 = 2026-03-01T00:00Z。
    //   用 Timestamp.valueOf(LocalDateTime) 绑会静默偏 −28800s（变成 2026-03-01T08:00Z）。
    assertThat(head.receivedAt().toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-03-01T00:00:00Z").toInstant());
    assertThat(head.receivedAt().toInstant()).isEqualTo(T1.toInstant());

    // 清单点的 countedAt 同理。
    var line = d.lines().get(0);
    assertThat(line.countedAt().toInstant()).isEqualTo(T1.toInstant());
  }

  // ==========================================================================
  // 列表：分页 / 排序 / 检索
  // ==========================================================================

  @Test
  void 列表真分页且汇总不受分页影响() {
    // 授权 8 件（订购 4 件不够，所以这里另建一张订购 8 的单）——否则第 4 张就超额度了。
    var c = seedCase("RR-PAGE", new BigDecimal("8"));
    var s = authorize(c, "RR-PAGE", new BigDecimal("8"), "APPROVED", due());
    for (int i = 0; i < 5; i++) {
      posted(s, BigDecimal.ONE, "INTACT", "UNOPENED");
    }

    var p1 = as(() -> receipts.list(new ReturnReceiptListQuery(0, 2, null, null, s.rmaId(),
        null, null, null, null, null, null, null, null)));
    var p2 = as(() -> receipts.list(new ReturnReceiptListQuery(1, 2, null, null, s.rmaId(),
        null, null, null, null, null, null, null, null)));

    assertThat(p1.total()).isEqualTo(5);
    assertThat(p1.rows()).hasSize(2);
    assertThat(p2.rows()).hasSize(2);
    // 两页不重叠。
    assertThat(p1.rows().stream().map(ReturnReceiptRow::id).toList())
        .doesNotContainAnyElementsOf(p2.rows().stream().map(ReturnReceiptRow::id).toList());
    // ⚠️ 汇总不受分页影响：「待检库存总量」是页面上最该盯的那一格，
    //   它不能因为翻到第二页就变小。
    assertThat(p1.summary().total()).isEqualTo(5);
    assertThat(p1.summary().pendingInspectionQty()).isEqualByComparingTo("5");
    assertThat(p1.summary().pendingInspectionQty())
        .isEqualByComparingTo(p2.summary().pendingInspectionQty());
  }

  @Test
  void 排序白名单拒绝任意表达式() {
    var s = seedAuthorized("RR-SORT");
    posted(s);
    var page =
        as(() -> receipts.list(new ReturnReceiptListQuery(null, null, null, null, null,
            null, null, null, null, null, null, null, "received_at desc; drop table sales_order")));
    assertThat(page.rows()).hasSize(1);
    // 非法排序键落回默认排序而不是被当表达式执行。
    assertThat(page.sortNote()).contains("白名单");
    // 订单表还在（真被 drop 的话后面所有用例都会红）。
    assertThat(as(() -> sql.fetch("select * from sales_order").size())).isEqualTo(1);
  }

  @Test
  void 筛选值先过枚举且按运单号与关键字能真检索() {
    var s = seedAuthorized("RR-SEARCH");
    var d = posted(s);

    assertThat(as(() -> receipts.list(new ReturnReceiptListQuery(null, null, "POSTED", null,
        null, null, null, null, null, null, null, null, null))).rows()).hasSize(1);
    assertThat(as(() -> receipts.list(new ReturnReceiptListQuery(null, null, "CANCELLED", null,
        null, null, null, null, null, null, null, null, null))).rows()).isEmpty();

    // 拼错的状态值必须**报错**，不能静默返回零行（运营会读成「没有这样的实收单」）。
    assertThatThrownBy(
            () -> as(() -> receipts.list(new ReturnReceiptListQuery(null, null, "POSTEDD", null,
                null, null, null, null, null, null, null, null, null))))
        .hasMessageContaining("取值非法");

    // 按回寄运单号反查（客服手上只有一个单号时的入口）。
    assertThat(as(() -> receipts.list(new ReturnReceiptListQuery(null, null, null, null,
        null, null, null, null, "RT-778899", null, null, null, null))).rows()).hasSize(1);

    // 关键字命中实收单号 / 授权单号 / 订单号。
    for (String kw : List.of(d.head().receiptNo(), "RR-SEARCH")) {
      assertThat(as(() -> receipts.list(new ReturnReceiptListQuery(null, null, null, null,
          null, null, null, null, null, null, null, kw, null))).rows())
          .as("关键字 " + kw + " 应命中")
          .hasSize(1);
    }

    // 按两个判定维度筛选（质检队列的入口）。
    assertThat(as(() -> receipts.list(new ReturnReceiptListQuery(null, null, null, null,
        null, null, null, null, null, "DAMAGED", null, null, null))).rows()).hasSize(1);
    assertThat(as(() -> receipts.list(new ReturnReceiptListQuery(null, null, null, null,
        null, null, null, null, null, null, "DEFECTIVE", null, null))).rows()).isEmpty();
  }

  // ==========================================================================
  // HTTP 契约 + 权限
  // ==========================================================================

  @Test
  void http契约与权限() throws Exception {
    var s = seedAuthorized("RR-HTTP");
    var d = draft(s, new BigDecimal("1"), "INTACT", "UNOPENED", "CARRIER");

    http.perform(get("/trade/return-receipts/capabilities")
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sections").isArray());

    // 无 Authorization ⇒ 401（租户不来自 X-Tenant-Id 头）。
    http.perform(get("/trade/return-receipts")).andExpect(status().isUnauthorized());
    // 伪造 X-Tenant-Id 头不改变结果。
    http.perform(get("/trade/return-receipts/" + d.head().id())
            .header("Authorization", "Bearer tenant-main")
            .header("X-Tenant-Id", other))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.head.receiptNo").value(d.head().receiptNo()));

    // ⚠️ 放行 GET /trade/return-receipts/receivable?rmaId=...
    http.perform(get("/trade/return-receipts/receivable")
            .param("rmaId", s.rmaId().toString())
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].authorizedQty").value(3));

    // 400：缺少必填字段。
    http.perform(post("/trade/return-receipts")
            .header("Authorization", "Bearer tenant-main")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rmaId\":\"" + s.rmaId() + "\",\"receiptChannel\":\"CARRIER\"}"))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================

  @Test
  void 五张新表都强制rls而且运行角色真的能读写() {
    for (String table : List.of("return_receipt", "return_receipt_line", "return_receipt_photo",
        "return_inventory_posting", "return_receipt_event")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V172 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
    }
  }

  @Test
  void 照片留痕与入库分录对运行角色是追加式或只读() {
    var s = seedAuthorized("RR-REVOKE");
    var d = posted(s);

    // 正对照：运行角色的 INSERT 与 SELECT 权限还在（否则上面所有用例都写不进去）。
    assertThat(can("return_receipt_photo", "INSERT")).isTrue();
    assertThat(can("return_receipt_photo", "SELECT")).isTrue();
    assertThat(can("return_inventory_posting", "INSERT")).isTrue();
    assertThat(can("return_inventory_posting", "SELECT")).isTrue();
    // 该改 / 该删的都收回。
    assertThat(can("return_receipt_photo", "UPDATE")).isFalse();
    assertThat(can("return_receipt_photo", "DELETE")).isFalse();
    assertThat(can("return_receipt_event", "UPDATE")).isFalse();
    assertThat(can("return_receipt_event", "DELETE")).isFalse();
    // ⚠️ 入库分录同样收回：它是待检库存的**账**，改一条而不改实收行就账实不符，
    //   删一条则等于凭空少了待检库存。
    assertThat(can("return_inventory_posting", "UPDATE")).isFalse();
    assertThat(can("return_inventory_posting", "DELETE")).isFalse();

    // 分录真实存在（正对照防假绿）。
    assertThat(as(() -> sql.fetch("select * from return_inventory_posting").size())).isEqualTo(1);
    assertThat(d.postings()).hasSize(1);
  }

  /** 运行角色 {@code trade_app} 对某张表的某项权限。 */
  private boolean can(String table, String action) {
    return sql.fetchOne(
            "select has_table_privilege('trade_app', ?, ?) as ok", table, action)
        .get(0, Boolean.class);
  }
}
