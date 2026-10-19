package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.trade.ReturnQualityModels.*;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.2.3.1 质检与判定。
 *
 * <p>跑的是蓝图职责句与那条加粗句：
 *
 * <blockquote>
 * 职责：退回商品质检：外观、功能、配件完整性、是否可再销售；给出可再销售/残次/报废判定与残值估算。
 * 输入：待检退货；质检模板（按类目）；质检结果。
 * 输出：质检结论 + 残值金额 + 质量状态指令。
 * <b>可再销售判定要保守</b>：包装破损但商品完好可判可再销售，缺少配件则应判残次。
 * </blockquote>
 *
 * <p><b>本类最看重的九组断言</b>：
 *
 * <ol>
 *   <li><b>蓝图那句话的两半真的走两条不同的路径</b>：包装破损 + 商品完好 → 判<b>可再销售</b>成功；
 *       缺配件 → 判可再销售<b>被拒</b>、判报废<b>也被拒</b>、只有判残次成功。
 *       而且这两条断言各跑两遍：<b>服务层</b>与<b>绕过服务层直连 SQL</b>。</li>
 *   <li><b>两条证据链都算数</b>：入库时记的 goods_condition 与本次逐项结果里的失败检项，
 *       任一指向缺配件都判残次 —— 只看一边的话，另一边就成了可以绕过的后门。</li>
 *   <li><b>判定是三值枚举不是布尔开关</b>：三档在库里有三个不同的取值与三个不同的指令类型。</li>
 *   <li><b>残值真算且算得对</b>：逐项断言原值 / 扣减 / 系数 / 上限 / 残值五个数，
 *       并断言<b>绕过服务层塞一个错的残值会被 DB 触发器当场拒</b>。</li>
 *   <li><b>质量状态指令由 DB 生成</b>：判定那一刻出现一张 PENDING 的指令，
 *       消费方恒为 11.2.3.2，运行角色 UPDATE/DELETE 被拒（正对照：同角色能改别��表）。</li>
 *   <li><b>判定是终态</b>：判完改结论 / 撤销 / 直连 SQL 改残值，服务层与触发器都拒。</li>
 *   <li><b>强制检项漏检不能判定</b>。</li>
 *   <li><b>时间写回零漂移</b>：夹具刻意用 +08:00 非 UTC 偏移。</li>
 *   <li><b>模板真落库并按类目取用</b>：没有类目就没有模板（报错而不是退回默认模板）。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReturnQualityIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID other = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
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
    r.add("IDENTITY_URL", () -> "http://localhost:8081");
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired ReturnQualityService quality;
  @Autowired ReturnReceiptService receipts;
  @Autowired RmaAuthorizationService rma;
  @Autowired AftersalesCaseService cases;
  @Autowired AftersalesCaseDetailService details;
  /**
   * 只桩掉 {@code require}（对 identity 的一次 HTTP 调用），{@code run} 仍是真实实现 ——
   * 也就是说 RLS 租户绑定与权限判据跑的都是生产代码路径。
   *
   * <p>⚠️ 刻意**不用** JDK 内置 {@code HttpServer} 起身份桩：它靠端口 0 让操作系统现分配，
   * 全量并发下偶发端口被抢占 → 503（本仓已知 flake）。这里一个额外监听端口都不开。
   */
  @MockitoSpyBean AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    pg.stop();
  }

  /** **非 UTC 偏移**夹具：只有这种时刻才能测出 Timestamp.valueOf 的 −28800s 静默偏移。 */
  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");

  static OffsetDateTime due() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8)).plusDays(14);
  }

  @BeforeEach
  void stubIdentity() {
    // 判据逐字照抄 AccessService.require：权限码要在某个 grant 里命中，
    // 且同一 grant 里必须同时有该动作与对应资源的 .read，否则「能写不能读」会被静默放行。
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
  }

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate return_quality_disposition_instruction, return_quality_check_result,"
              + " return_quality_event, return_quality_inspection,"
              + " return_quality_template_item, return_quality_template,"
              + " return_quality_category_sku, return_quality_category,"
              + " return_receipt_photo, return_inventory_posting, return_receipt_line,"
              + " return_receipt_event, return_receipt,"
              + " rma_decision_event, rma_authorization_line, rma_authorization,"
              + " aftersales_case_line, aftersales_case_note, aftersales_authorization,"
              + " aftersales_service_ticket, aftersales_owner_assignment, aftersales_case,"
              + " order_fulfillment_line_progress, sales_order cascade");
    }
  }

  <T> T as(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-main"), "trade.aftersales.write", work);
  }

  static {
    actors.put("Bearer tenant-main", actor(user, tenant));
    actors.put("Bearer tenant-other", actor(UUID.randomUUID(), other));
  }

  // ==========================================================================
  // 夹具：订单 → 售后单 → 退货授权单 → 退货实收入库 → 待检分录
  // ==========================================================================

  record Seeded(UUID rmaId, UUID caseId, UUID orderLineId, UUID rmaLineId) {}

  record CaseSeeded(UUID caseId, UUID lineId) {}

  record QualityFixture(UUID categoryId, UUID templateId, UUID skuId, String skuCode) {}

  CaseSeeded seedCase(String orderNo) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    BigDecimal qty = new BigDecimal("4");
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
    return new CaseSeeded(caseId, lineId);
  }

  Seeded seedAuthorized(String orderNo) {
    CaseSeeded c = seedCase(orderNo);
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
            "PARTIALLY_APPROVED", due(), "外包装压损，只退部分",
            List.of(new RmaAuthorizationModels.RmaLineDecision(rmaLineId, new BigDecimal("3"), null)),
            0L),
        user));
    return new Seeded(rmaId, c.caseId(), c.lineId(), rmaLineId);
  }

  /** 登记并入库一张退货实收（两个判定维度可配）→ 返回该行的待检入库分录。 */
  record Posted(UUID receiptId, UUID receiptLineId, UUID postingId, UUID skuId, String skuCode) {}

  Posted post(String orderNo, BigDecimal qty, String packaging, String goods) {
    Seeded s = seedAuthorized(orderNo);
    var draft =
        as(() -> receipts.create(
            new ReturnReceiptModels.ReturnReceiptCreate(
                s.rmaId(), null, "CARRIER", "FedEx", "RT-" + orderNo,
                "外箱一角压瘪", T1,
                List.of(
                    new ReturnReceiptModels.ReturnReceiptLineDraft(
                        s.rmaLineId(), "SKU-X", "SN-" + UUID.randomUUID(), null,
                        qty, packaging, goods, "外箱压损")),
                List.of()),
            user));
    as(() -> receipts.post(draft.head().id(),
        new ReturnReceiptModels.ReturnReceiptPost(null, draft.head().version()), user));

    Record posting =
        as(() -> sql.fetchOne(
            "select * from return_inventory_posting where tenant_id = ?::uuid and receipt_id = ?::uuid",
            tenant, draft.head().id()));
    return new Posted(
        posting.get("receipt_id", UUID.class),
        posting.get("receipt_line_id", UUID.class),
        posting.get("id", UUID.class),
        posting.get("sku_id", UUID.class),
        posting.get("sku_code", String.class));
  }

  /** 建一个可用的质检模板（四维度各一项，费率固定，断言里的算式依赖这些数）。 */
  QualityFixture seedTemplate(UUID skuId, String skuCode) {
    UUID categoryId =
        as(() -> quality.createCategory(
            new QualityCategoryCreate("CAT-" + skuCode, "小家电", "带配件的机器"), user)
            .id());
    as(() -> {
      quality.mapSku(categoryId, new QualitySkuMappingCreate(skuId, skuCode), user);
      return null;
    });

    QualityTemplateDetail template =
        as(() -> quality.createTemplate(
            new QualityTemplateCreate(
                categoryId, "小家电标准质检", 1,
                new BigDecimal("1.000000"), new BigDecimal("0.100000"),
                new BigDecimal("1.000000"), new BigDecimal("0.500000"), new BigDecimal("0.050000"),
                "V179 夹具模板",
                List.of(
                    item("APPEARANCE-1", "外观划痕", "APPEARANCE", "0.200000"),
                    item("PACKAGING-1", "外包装", "PACKAGING", "0.100000"),
                    item("FUNCTION-1", "功能自检", "FUNCTION", "0.500000"),
                    item("ACCESSORY-1", "配件清单", "ACCESSORY", "0.300000"))),
            user));
    as(() -> quality.activateTemplate(template.template().id(), user));
    return new QualityFixture(categoryId, template.template().id(), skuId, skuCode);
  }

  private static QualityTemplateItemDraft item(String code, String name, String dim, String rate) {
    return new QualityTemplateItemDraft(code, name, dim, new BigDecimal(rate), true, "", "");
  }

  /** 开一张质检单（按类目自动取模板）。 */
  UUID openInspection(Posted p) {
    return as(() -> quality.createInspection(new QualityInspectionCreate(p.postingId(), null), user)
        .head()
        .id());
  }

  /** 构造一份「除指定检项外全部合格」的逐项结果。 */
  List<QualityCheckResultDraft> resultsFor(UUID templateId, String failedItemCode) {
    return as(() -> quality.templateDetail(templateId))
        .items().stream()
            .map(
                i ->
                    new QualityCheckResultDraft(
                        i.id(),
                        i.itemCode().equals(failedItemCode) ? "FAIL" : "PASS",
                        i.itemCode().equals(failedItemCode) ? "检出问题" : "",
                        null))
            .toList();
  }

  /** 录逐项结果（整单替换）。 */
  void record(UUID inspectionId, UUID templateId, String failedItemCode) {
    List<QualityCheckResultDraft> results = resultsFor(templateId, failedItemCode);
    long v = as(() -> quality.inspectionDetail(inspectionId).head().version());
    as(() -> quality.recordResults(inspectionId, new QualityInspectionUpdate(results, v), user));
  }

  QualityInspectionDetail detail(UUID id) {
    return as(() -> quality.inspectionDetail(id));
  }

  long version(UUID id) {
    return detail(id).head().version();
  }

  // ==========================================================================
  // 一、蓝图那句话的两半：两条不同的路径（硬规则 1）
  // ==========================================================================

  @Test
  void 包装破损但商品完好可以判可再销售() {
    // 夹具刻意取蓝图那句话的左半：外箱损毁 + 里面的货完好。
    Posted p = post("PKG-1", new BigDecimal("2"), "DESTROYED", "OPENED_UNDAMAGED");
    QualityFixture f = seedTemplate(p.skuId(), p.skuCode());
    UUID id = openInspection(p);
    record(id, f.templateId(), "PACKAGING-1");

    var preview = as(() -> quality.verdictPreview(p.postingId()));
    assertThat(preview.requiredPath())
        .as("包装问题**不得**把判定推进到「商品有问题」那条路径")
        .isEqualTo(QualityVerdictPath.PACKAGING_OR_APPEARANCE_ONLY);
    assertThat(preview.verdictOptions())
        .filteredOn(QualityVerdictOption::allowed)
        .extracting(QualityVerdictOption::code)
        .as("包装破损时三档都可选 —— 蓝图原话「包装破损但商品完好可判可再销售」")
        .contains("RESALABLE", "DEFECTIVE", "SCRAPPED");

    var done = as(() -> quality.conclude(
        id, new QualityInspectionConclude("RESALABLE", "外箱损毁但货完好，可重新包装后上架", version(id)), user));

    assertThat(done.head().verdict()).isEqualTo("RESALABLE");
    // 原值 = 单价 5.0000 × 数量 2 = 10.0000；扣减 = 包装检项 0.100000；
    // 系数 = 1.000000 − 0.100000 = 0.900000；可再销售档上限 1.000000 ⇒ 生效 0.900000；
    // 残值 = round(10.0000 × 0.900000, 4) = 9.0000
    assertThat(done.head().originalValue()).isEqualByComparingTo("10.0000");
    assertThat(done.head().salvageAmount()).isEqualByComparingTo("9.0000");
    assertThat(done.head().salvageCurrency()).isEqualTo("USD");
    assertThat(done.instruction().instructionType()).isEqualTo("MOVE_TO_SELLABLE");
    assertThat(done.instruction().targetInventoryState()).isEqualTo("SELLABLE");
  }

  @Test
  void 缺少配件必须判残次判可再销售与判报废都被拒() {
    // 蓝图那句话的右半：入库时就已经记下「配件缺失」。
    Posted p = post("ACC-1", new BigDecimal("2"), "DAMAGED", "MISSING_PARTS");
    QualityFixture f = seedTemplate(p.skuId(), p.skuCode());
    UUID id = openInspection(p);
    record(id, f.templateId(), "ACCESSORY-1");

    var preview = as(() -> quality.verdictPreview(p.postingId()));
    assertThat(preview.requiredPath()).isEqualTo(QualityVerdictPath.ACCESSORY_MISSING);
    assertThat(preview.verdictOptions())
        .filteredOn(o -> o.code().equals("RESALABLE"))
        .allMatch(o -> !o.allowed() && o.blockedReason() != null && !o.blockedReason().isBlank());

    // ---- 服务层这一道 ----
    assertThatThrownBy(
            () -> as(() -> quality.conclude(
                id, new QualityInspectionConclude("RESALABLE", "想直接当整机卖", version(id)), user)))
        .hasStackTraceContaining("配件缺失只能判残次");
    assertThatThrownBy(
            () -> as(() -> quality.conclude(
                id, new QualityInspectionConclude("SCRAPPED", "当废铁处理", version(id)), user)))
        .hasStackTraceContaining("配件缺失只能判残次");

    // ---- 绕过服务层直连 SQL 也一样被拒（DB 触发器那一道）----
    assertThatThrownBy(
            () -> as(() -> sql.execute(
                "update return_quality_inspection set inspection_status='CONCLUDED',"
                    + " verdict='RESALABLE', verdict_label='可再销售', conclusion_note='直连 SQL 绕过',"
                    + " salvage_amount=10, concluded_by=?::uuid, concluded_at=?::timestamptz,"
                    + " version=version+1 where tenant_id=?::uuid and id=?::uuid",
                user, T1, tenant, id)))
        .hasStackTraceContaining("配件缺失必须判残次");

    // ---- 只有残次这一档过得了 ----
    var done = as(() -> quality.conclude(
        id, new QualityInspectionConclude("DEFECTIVE", "缺电源适配器，按残次处理并向仓库追责", version(id)), user));
    assertThat(done.head().verdict()).isEqualTo("DEFECTIVE");
    // 扣减 = 配件检项 0.300000；系数 = 0.700000；残次档上限 0.500000 ⇒ 生效 0.500000；
    // 残值 = round(10.0000 × 0.500000, 4) = 5.0000
    assertThat(done.head().salvageAmount()).isEqualByComparingTo("5.0000");
    assertThat(done.instruction().instructionType()).isEqualTo("MOVE_TO_DEFECTIVE");
  }

  @Test
  void 入库维度正常但配件检项不合格同样只能判残次() {
    // 证明**两条证据链都算数**：入库维度是 OPENED_UNDAMAGED（看不出缺配件），
    // 但本次质检的配件清单检项 FAIL —— 只看入库维度的话，这一单会被放行。
    Posted p = post("ACC-2", new BigDecimal("2"), "INTACT", "OPENED_UNDAMAGED");
    QualityFixture f = seedTemplate(p.skuId(), p.skuCode());
    UUID id = openInspection(p);
    record(id, f.templateId(), "ACCESSORY-1");

    assertThat(as(() -> quality.verdictPreview(p.postingId())).requiredPath())
        .isEqualTo(QualityVerdictPath.ACCESSORY_MISSING);
    assertThatThrownBy(
            () -> as(() -> quality.conclude(
                id, new QualityInspectionConclude("RESALABLE", "包装完好就想上架", version(id)), user)))
        .hasStackTraceContaining("配件缺失只能判残次");
  }

  @Test
  void 商品本体损坏或功能失效禁止判可再销售() {
    // 路径②：与路径①不同 —— 残次**和**报废都还可选，只是可再销售被禁。
    Posted damaged = post("FUN-1", new BigDecimal("2"), "INTACT", "DAMAGED");
    QualityFixture fd = seedTemplate(damaged.skuId(), damaged.skuCode());
    UUID idd = openInspection(damaged);
    record(idd, fd.templateId(), "APPEARANCE-1");

    var preview = as(() -> quality.verdictPreview(damaged.postingId()));
    assertThat(preview.requiredPath()).isEqualTo(QualityVerdictPath.GOODS_OR_FUNCTION_DAMAGED);
    assertThat(preview.verdictOptions())
        .filteredOn(o -> o.code().equals("RESALABLE"))
        .allMatch(o -> !o.allowed());
    assertThat(preview.verdictOptions())
        .filteredOn(o -> o.code().equals("SCRAPPED"))
        .allMatch(QualityVerdictOption::allowed);

    assertThatThrownBy(
            () -> as(() -> quality.conclude(
                idd, new QualityInspectionConclude("RESALABLE", "想当新品卖", version(idd)), user)))
        .hasStackTraceContaining("不能判可再销售");

    // 功能检项失败是同一条路径的另一个来源（入库维度这次是完好的）。
    Posted broken = post("FUN-2", new BigDecimal("2"), "INTACT", "OPENED_UNDAMAGED");
    QualityFixture fb = seedTemplate(broken.skuId(), broken.skuCode());
    UUID idb = openInspection(broken);
    record(idb, fb.templateId(), "FUNCTION-1");
    assertThat(as(() -> quality.verdictPreview(broken.postingId())).requiredPath())
        .isEqualTo(QualityVerdictPath.GOODS_OR_FUNCTION_DAMAGED);
  }

  @Test
  void 判定是三值枚举而不是布尔开关() {
    Posted p = post("VERDICT-1", new BigDecimal("2"), "INTACT", "OPENED_UNDAMAGED");
    QualityFixture f = seedTemplate(p.skuId(), p.skuCode());
    UUID id = openInspection(p);
    record(id, f.templateId(), "APPEARANCE-1");

    var done = as(() -> quality.conclude(
        id, new QualityInspectionConclude("DEFECTIVE", "外壳有划痕，按残次折价处理", version(id)), user));

    // 三档在库里是三个不同的取值，落到三条不同的指令与三个不同的目标库存状态。
    assertThat(done.head().verdict()).isEqualTo("DEFECTIVE");
    assertThat(QualityVerdict.RESALABLE.instructionType()).isEqualTo("MOVE_TO_SELLABLE");
    assertThat(QualityVerdict.DEFECTIVE.instructionType()).isEqualTo("MOVE_TO_DEFECTIVE");
    assertThat(QualityVerdict.SCRAPPED.instructionType()).isEqualTo("WRITE_OFF_SCRAP");
    assertThat(done.instruction().instructionType()).isEqualTo("MOVE_TO_DEFECTIVE");
    assertThat(done.instruction().targetInventoryState()).isEqualTo("DEFECTIVE");
    // 报废不可逆这一条写在枚举上，界面据此把按钮做成需要二次确认。
    assertThat(QualityVerdict.SCRAPPED.irreversible()).isTrue();
    assertThat(QualityVerdict.RESALABLE.irreversible()).isFalse();
  }

  // ==========================================================================
  // 二、残值是真算的（硬规则 2）
  // ==========================================================================

  @Test
  void 残值按可审阅的式子真算且塞不进假数() {
    Posted p = post("SALVAGE-1", new BigDecimal("2"), "INTACT", "OPENED_UNDAMAGED");
    QualityFixture f = seedTemplate(p.skuId(), p.skuCode());
    UUID id = openInspection(p);
    record(id, f.templateId(), "APPEARANCE-1");

    var done = as(() -> quality.conclude(
        id, new QualityInspectionConclude("RESALABLE", "仅外观划痕，可重新包装上架", version(id)), user));

    // 逐项断言五个数：原值 / 扣减 / 系数 / 档位上限 / 残值。
    //   原值 = 5.0000 × 2 = 10.0000
    //   扣减 = 外观检项 0.200000
    //   系数 = 1.000000 − 0.200000 = 0.800000
    //   可再销售档上限 = 1.000000 ⇒ 生效 0.800000
    //   残值 = round(10.0000 × 0.800000, 4) = 8.0000
    Record raw = as(() -> sql.fetchOne(
        "select original_value, salvage_deduction, grade_coefficient, salvage_cap, salvage_amount,"
            + " salvage_currency from return_quality_inspection where tenant_id=?::uuid and id=?::uuid",
        tenant, id));
    assertThat(raw.get("original_value", BigDecimal.class)).isEqualByComparingTo("10.0000");
    assertThat(raw.get("salvage_deduction", BigDecimal.class)).isEqualByComparingTo("0.200000");
    assertThat(raw.get("grade_coefficient", BigDecimal.class)).isEqualByComparingTo("0.800000");
    assertThat(raw.get("salvage_cap", BigDecimal.class)).isEqualByComparingTo("1.000000");
    assertThat(raw.get("salvage_amount", BigDecimal.class)).isEqualByComparingTo("8.0000");
    assertThat(raw.get("salvage_currency", String.class)).isEqualTo("USD");
    // 全部落在 NUMERIC 上：不是 double，读回来是 BigDecimal。
    assertThat(raw.get("salvage_amount")).isInstanceOf(BigDecimal.class);

    // ---- 绕过服务层塞一个「多报点残值」的数，触发器当场拒 ----
    // 刻意用**另一张草稿**：已判定的单会先被终态冻结触发器拦住，
    // 那样测到的是「终态」而不是「残值金额与公式对不上」，等于白测。
    Posted p2 = post("SALVAGE-2", new BigDecimal("2"), "INTACT", "OPENED_UNDAMAGED");
    QualityFixture f2 = seedTemplate(p2.skuId(), p2.skuCode());
    UUID other = openInspection(p2);
    record(other, f2.templateId(), "APPEARANCE-1");
    assertThatThrownBy(
            () -> as(() -> sql.execute(
                "update return_quality_inspection set inspection_status='CONCLUDED',"
                    + " verdict='RESALABLE', verdict_label='可再销售', conclusion_note='直连 SQL 编残值',"
                    + " salvage_amount=99, concluded_by=?::uuid, concluded_at=?::timestamptz,"
                    + " version=version+1 where tenant_id=?::uuid and id=?::uuid",
                user, T1, tenant, other)))
        .hasStackTraceContaining("残值金额与");
    assertThat(detail(other).head().inspectionStatus()).as("被拒的事务整体回滚，单还是草稿").isEqualTo("DRAFT");
  }

  @Test
  void 传输层根本没有残值字段() {
    // 残值是财务数据：契约里**不允许**出现一个可填的残值金额。
    // 三个写入口的请求体里都没有金额字段（编译期保证：构造函数的形参列表里没有它）。
    assertThat(QualityInspectionConclude.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactly("verdict", "conclusionNote", "expectedVersion");
    assertThat(QualityInspectionCreate.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactly("postingId", "templateId");
  }

  // ==========================================================================
  // 三、质量状态指令：只出不消费
  // ==========================================================================

  @Test
  void 质量状态指令由数据库生成且恒为待消费() {
    Posted p = post("INSTR-1", new BigDecimal("2"), "INTACT", "OPENED_UNDAMAGED");
    QualityFixture f = seedTemplate(p.skuId(), p.skuCode());
    UUID id = openInspection(p);
    record(id, f.templateId(), "APPEARANCE-1");
    assertThat(as(() -> sql.fetchOne(
            "select count(*) from return_quality_disposition_instruction"
                + " where tenant_id=?::uuid and inspection_id=?::uuid",
            tenant, id))
        .get(0, Long.class)).as("判定之前不该有指令").isZero();

    var done = as(() -> quality.conclude(
        id, new QualityInspectionConclude("RESALABLE", "仅外观瑕疵", version(id)), user));

    // 指令由 **DB 触发器**生成：服务层没有任何写它的代码路径。
    Record instruction = as(() -> sql.fetchOne(
        "select * from return_quality_disposition_instruction"
            + " where tenant_id=?::uuid and inspection_id=?::uuid",
        tenant, id));
    assertThat(instruction.get("instruction_type", String.class)).isEqualTo("MOVE_TO_SELLABLE");
    assertThat(instruction.get("target_inventory_state", String.class)).isEqualTo("SELLABLE");
    assertThat(instruction.get("qty", BigDecimal.class)).isEqualByComparingTo("2");
    assertThat(instruction.get("salvage_amount", BigDecimal.class)).isEqualByComparingTo("8.0000");
    assertThat(instruction.get("sku_code", String.class)).isEqualTo(p.skuCode());
    // 消费侧未交付 ⇒ 恒 PENDING、消费方恒 11.2.3.2。
    assertThat(instruction.get("instruction_status", String.class)).isEqualTo("PENDING");
    assertThat(instruction.get("consumer_module", String.class)).isEqualTo("11.2.3.2");
    assertThat(done.instruction().instructionStatus()).isEqualTo("PENDING");

    // 运行角色对指令表**没有** UPDATE/DELETE：正对照是同一角色能改别的表。
    assertThatThrownBy(
            () -> as(() -> sql.execute(
                "update return_quality_disposition_instruction set instruction_status='CONSUMED'"
                    + " where tenant_id=?::uuid and id=?::uuid",
                tenant, instruction.get("id", UUID.class))))
        .hasStackTraceContaining("permission denied");
    assertThat(as(() -> sql.execute(
            "update return_quality_category set note = '运行角色可改本模块的类目表' "
                + "where tenant_id=?::uuid", tenant)))
        .as("正对照：同一个运行角色能改本模块的类目表，所以上一条拒的是**权限**不是连接")
        .isEqualTo(1);
  }

  // ==========================================================================
  // 四、终态、覆盖性、时间
  // ==========================================================================

  @Test
  void 判定是终态不可修改() {
    Posted p = post("FREEZE-1", new BigDecimal("2"), "INTACT", "OPENED_UNDAMAGED");
    QualityFixture f = seedTemplate(p.skuId(), p.skuCode());
    UUID id = openInspection(p);
    record(id, f.templateId(), "APPEARANCE-1");
    as(() -> quality.conclude(
        id, new QualityInspectionConclude("RESALABLE", "仅外观瑕疵", version(id)), user));

    assertThatThrownBy(() -> as(() -> quality.conclude(
        id, new QualityInspectionConclude("DEFECTIVE", "改判残次", version(id)), user)))
        .hasStackTraceContaining("终态");
    assertThatThrownBy(() -> as(() -> quality.cancel(
        id, new QualityInspectionCancel("判错了", version(id)), user)))
        .hasStackTraceContaining("终态");
    assertThatThrownBy(
            () -> as(() -> sql.execute(
                "update return_quality_inspection set verdict='DEFECTIVE'"
                    + " where tenant_id=?::uuid and id=?::uuid",
                tenant, id)))
        .hasStackTraceContaining("终态");
    assertThatThrownBy(
            () -> as(() -> sql.execute(
                "update return_quality_check_result set check_outcome='FAIL'"
                    + " where tenant_id=?::uuid and inspection_id=?::uuid",
                tenant, id)))
        .hasStackTraceContaining("终态");
    assertThat(detail(id).head().verdict()).isEqualTo("RESALABLE");
  }

  @Test
  void 强制检项漏检不能判定() {
    Posted p = post("MANDATORY-1", new BigDecimal("2"), "INTACT", "OPENED_UNDAMAGED");
    QualityFixture f = seedTemplate(p.skuId(), p.skuCode());
    UUID id = openInspection(p);

    var d = detail(id);
    assertThat(d.mandatoryMissingCount()).isEqualTo(4);
    assertThat(d.actions()).filteredOn(a -> a.code().equals("CONCLUDE")).allMatch(a -> !a.allowed());
    assertThatThrownBy(() -> as(() -> quality.conclude(
        id, new QualityInspectionConclude("RESALABLE", "一项没检就判", version(id)), user)))
        .hasStackTraceContaining("强制检项");

    record(id, f.templateId(), "APPEARANCE-1");
    assertThat(detail(id).mandatoryMissingCount()).isZero();
  }

  @Test
  void 时间写回零漂移() {
    Posted p = post("TIME-1", new BigDecimal("2"), "INTACT", "OPENED_UNDAMAGED");
    QualityFixture f = seedTemplate(p.skuId(), p.skuCode());
    UUID id = openInspection(p);
    record(id, f.templateId(), "APPEARANCE-1");

    OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5);
    var done = as(() -> quality.conclude(
        id, new QualityInspectionConclude("RESALABLE", "仅外观瑕疵", version(id)), user));
    OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(5);

    // ⚠️ 夹具是 +08:00 的非 UTC 偏移：`Timestamp.valueOf(LocalDateTime)` 会静默偏 −28800s，
    //   所以这两条断言必须落在「服务端自己写的时刻」而不是某个写死的常量上。
    OffsetDateTime concludedAt =
        as(() -> sql.fetchOne(
                "select concluded_at from return_quality_inspection"
                    + " where tenant_id=?::uuid and id=?::uuid",
                tenant, id))
            .get("concluded_at", OffsetDateTime.class);
    assertThat(concludedAt).isBetween(before, after);
    OffsetDateTime checkedAt =
        as(() -> sql.fetchOne(
                "select min(checked_at) as m from return_quality_check_result"
                    + " where tenant_id=?::uuid and inspection_id=?::uuid",
                tenant, id))
            .get("m", OffsetDateTime.class);
    assertThat(checkedAt).isBetween(before, after);
    // 非 UTC 时刻也必须写回成同一个瞬间（不是偏 8 小时的另一个瞬间）。
    assertThat(done.events()).anyMatch(e -> "CONCLUDED".equals(e.eventType()) && e.occurredAt() != null);
  }

  // ==========================================================================
  // 五、模板按类目取用、队列、唯一性、能力声明、端点
  // ==========================================================================

  @Test
  void 质检模板真落库并按类目取用() {
    Posted p = post("TPL-1", new BigDecimal("2"), "INTACT", "OPENED_UNDAMAGED");
    QualityFixture f = seedTemplate(p.skuId(), p.skuCode());

    // 按类目取用：开质检单不给模板，服务端自己按 SKU 的类目取到那个启用中的模板。
    UUID id = openInspection(p);
    var d = detail(id);
    assertThat(d.head().templateId()).isEqualTo(f.templateId());
    assertThat(d.head().categoryId()).isEqualTo(f.categoryId());
    // 标的物与数量**由 DB 触发器从待检分录取**，不来自请求体。
    assertThat(d.head().inspectedQty()).isEqualByComparingTo("2");
    assertThat(d.head().skuCode()).isEqualTo(p.skuCode());
    assertThat(d.head().originalValue()).isEqualByComparingTo("0.0000");

    // 模板详情带检项与「失败后还能不能判可再销售」。
    var tpl = as(() -> quality.templateDetail(f.templateId()));
    assertThat(tpl.items()).hasSize(4);
    assertThat(tpl.items()).filteredOn(QualityTemplateItemRow::blocksResaleWhenFailed)
        .extracting(QualityTemplateItemRow::itemDimension)
        .containsExactlyInAnyOrder("FUNCTION", "ACCESSORY");
    assertThat(tpl.items()).filteredOn(i -> !i.blocksResaleWhenFailed())
        .extracting(QualityTemplateItemRow::itemDimension)
        .containsExactlyInAnyOrder("APPEARANCE", "PACKAGING");
  }

  @Test
  void sku没有归类就没有模板而不是退回默认模板() {
    Posted p = post("NOCAT-1", new BigDecimal("2"), "INTACT", "OPENED_UNDAMAGED");
    assertThatThrownBy(() -> as(() -> quality.createInspection(
        new QualityInspectionCreate(p.postingId(), null), user)))
        .hasStackTraceContaining("还没有归到任何质检类目");
  }

  @Test
  void 一张待检分录同时只能有一张在途质检单() {
    Posted p = post("ONE-1", new BigDecimal("2"), "INTACT", "OPENED_UNDAMAGED");
    seedTemplate(p.skuId(), p.skuCode());
    UUID id = openInspection(p);

    assertThatThrownBy(() -> as(() -> quality.createInspection(
        new QualityInspectionCreate(p.postingId(), null), user)))
        .hasStackTraceContaining("已有一张在途质检单");
    // DB 层同样钉死（部分唯一索引），绕���服务层直插也一样会被拒。
    assertThatThrownBy(() -> as(() -> sql.execute(
        "insert into return_quality_inspection(id,tenant_id,inspection_no,posting_id,template_id,"
            + "template_version,category_id,inspection_status,created_by)"
            + " select ?::uuid,?::uuid,'RQI-HACK',posting_id,template_id,template_version,"
            + "   category_id,'DRAFT',?::uuid"
            + " from return_quality_inspection where tenant_id=?::uuid and id=?::uuid",
        UUID.randomUUID(), tenant, user, tenant, id)))
        .hasStackTraceContaining("duplicate key");

    // 撤销之后同一分录可以重开（队列与唯一性都只看「在途」）。
    as(() -> quality.cancel(id, new QualityInspectionCancel("录错了重开", version(id)), user));
    UUID again = openInspection(p);
    assertThat(again).isNotEqualTo(id);
  }

  @Test
  void 判定之后该条离开待检队列() {
    Posted p = post("QUEUE-1", new BigDecimal("2"), "INTACT", "OPENED_UNDAMAGED");
    QualityFixture f = seedTemplate(p.skuId(), p.skuCode());

    var before = as(() -> quality.pendingQueue(null, null, null));
    assertThat(before.rows()).anyMatch(r -> r.postingId().equals(p.postingId()));
    assertThat(before.rows()).filteredOn(r -> r.postingId().equals(p.postingId()))
        .allMatch(r -> r.hasTemplate() && r.skuCode().equals(p.skuCode()));

    UUID id = openInspection(p);
    record(id, f.templateId(), "APPEARANCE-1");
    as(() -> quality.conclude(
        id, new QualityInspectionConclude("RESALABLE", "仅外观瑕疵", version(id)), user));

    var after = as(() -> quality.pendingQueue(null, null, null));
    assertThat(after.rows()).noneMatch(r -> r.postingId().equals(p.postingId()));
    assertThat(after.total()).isEqualTo(before.total() - 1);
  }

  @Test
  void 列表是真分页且汇总跨全表() {
    for (int i = 1; i <= 3; i++) {
      Posted p = post("PAGE-" + i, new BigDecimal("2"), "INTACT", "OPENED_UNDAMAGED");
      QualityFixture f = seedTemplate(p.skuId(), p.skuCode());
      UUID id = openInspection(p);
      record(id, f.templateId(), "APPEARANCE-1");
      as(() -> quality.conclude(
          id, new QualityInspectionConclude("RESALABLE", "仅外观瑕疵", version(id)), user));
    }
    var page = as(() -> quality.listInspections(new QualityInspectionListQuery(0, 2, null, null, null,
        null, null, null, null, null)));
    assertThat(page.rows()).hasSize(2);
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.summary().concluded()).isEqualTo(3);
    assertThat(page.summary().salvageAmount()).isEqualByComparingTo("24.0000");

    var second = as(() -> quality.listInspections(new QualityInspectionListQuery(1, 2, null, null, null,
        null, null, null, null, null)));
    assertThat(second.rows()).hasSize(1);
    assertThat(second.rows().get(0).id()).isNotIn(page.rows().get(0).id(), page.rows().get(1).id());
    // 翻页不改变汇总口径。
    assertThat(second.summary().total()).isEqualTo(3);
  }

  @Test
  void 乐观锁拦得住并发判定() {
    Posted p = post("LOCK-1", new BigDecimal("2"), "INTACT", "OPENED_UNDAMAGED");
    QualityFixture f = seedTemplate(p.skuId(), p.skuCode());
    UUID id = openInspection(p);
    record(id, f.templateId(), "APPEARANCE-1");
    long stale = version(id);
    // 用当前版本重录一次（内容不变）推进版本，模拟「另一个人刚改过这张单」。
    as(() -> quality.recordResults(
        id, new QualityInspectionUpdate(resultsFor(f.templateId(), "APPEARANCE-1"), stale), user));

    assertThatThrownBy(() -> as(() -> quality.conclude(
        id, new QualityInspectionConclude("RESALABLE", "用旧版本号判", stale), user)))
        .hasStackTraceContaining("版本不符");
  }

  @Test
  void 能力声明逐块报可用性且未交付下游一律归属模块号() {
    var caps = as(quality::capabilities);
    for (String section : List.of("appearance", "function", "accessory", "resalability",
        "salvageEstimate", "templateByCategory", "dispositionInstruction")) {
      var s = caps.sections().stream().filter(x -> x.section().equals(section)).findFirst();
      assertThat(s).as("能力声明缺区块 " + section).isPresent();
      assertThat(s.get().available()).as(section + " 应为已交付").isTrue();
    }
    for (String section : List.of("dispositionApply", "salvageAccounting", "supplierClaim",
        "batchAttribution", "defectResale")) {
      var s = caps.sections().stream().filter(x -> x.section().equals(section)).findFirst();
      assertThat(s).as("能力声明缺区块 " + section).isPresent();
      assertThat(s.get().available()).as(section + " 未交付，不该报 true").isFalse();
      assertThat(s.get().ownerModule()).as(section + " 必须报归属模块号").isNotBlank();
      assertThat(s.get().landsIn()).isNull();
    }
    for (QualityRelatedModule m : caps.relatedModules()) {
      if (!m.delivered()) {
        assertThat(m.ownerModule()).isNotBlank();
        assertThat(m.href()).as(m.id() + " 未交付不得给链接").isNull();
      }
    }
    // 「执行处置」永远 allowed=false：不是暂时没做，是刻意没有。
    var apply = caps.actions().stream().filter(a -> a.code().equals("APPLY_DISPOSITION")).findFirst();
    assertThat(apply).isPresent();
    assertThat(apply.get().allowed()).isFalse();
    assertThat(apply.get().method()).isNull();
    assertThat(apply.get().ownerModule()).isEqualTo("11.2.3.2");
    assertThat(caps.boundaries()).isNotEmpty();
  }

  @Test
  void 端点走完整的认证与租户绑定链路() throws Exception {
    Posted p = post("HTTP-1", new BigDecimal("2"), "INTACT", "OPENED_UNDAMAGED");
    QualityFixture f = seedTemplate(p.skuId(), p.skuCode());

    http.perform(get("/trade/return-quality/capabilities")
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk());

    http.perform(get("/trade/return-quality/queue")
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));

    http.perform(get("/trade/return-quality/verdict-preview")
            .param("postingId", p.postingId().toString())
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requiredPath").value("PACKAGING_OR_APPEARANCE_ONLY"));

    http.perform(MockMvcRequestBuilders.post("/trade/return-quality/inspections")
            .header("Authorization", "Bearer tenant-main")
            .contentType("application/json")
            .content(json.writeValueAsString(new QualityInspectionCreate(p.postingId(), null))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.head.templateId").value(f.templateId().toString()));

    // 没有认证头 ⇒ 不进业务代码。
    http.perform(get("/trade/return-quality/queue")).andExpect(status().isUnauthorized());
  }
}
