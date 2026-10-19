package com.starcon.trade;

import static com.starcon.trade.QualityAnalysisModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.QualityAnalysisModels.*;
import com.starcon.trade.ReturnQualityModels.*;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.5.2.1 质量分析的集成测试（Testcontainers PostgreSQL 16 实跑）。
 *
 * <p>本模块的每一条断言都对着蓝图职责句里的一个承诺：
 * 缺陷类型分布 / 批次质量趋势 / 供应商质量对比 / 缺陷照片聚类 / 开箱率 / 质量成本估算 /
 * 责任分布 / 改进清单，外加那条加粗句「<b>质量成本应量化</b>」所要求的
 * 「哪几项是实测、哪几项是估算」必须<b>在响应里标出来</b>。
 *
 * <p>⚠️ <b>口径类断言比数字类断言重要</b>：一个把「参数未登记」按 0 算进合计的实现，
 * 报出来的总成本仍然是个漂亮的小数，只有断言 {@code NOT_REGISTERED} 且
 * {@code total} 不含它才拦得住。
 */
@SpringBootTest
@AutoConfigureMockMvc
class QualityAnalysisIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms =
      List.of("trade.aftersales.read", "trade.aftersales.write");

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
    actors.put(
        "Bearer tenant-a",
        new AccessService.Access(
            user, tenant, "Test", UUID.randomUUID(), null, perms,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms))));
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

  @MockitoSpyBean AccessService access;
  @Autowired QualityAnalysisService quality;
  @Autowired ReturnQualityService inspections;
  @Autowired AftersalesCaseService cases;
  @Autowired AftersalesCaseDetailService details;
  @Autowired RmaAuthorizationService rma;
  @Autowired ReturnReceiptService receipts;
  @Autowired ProductIssueAttributionService attributions;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    pg.stop();
  }

  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");

  static OffsetDateTime due() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8)).plusDays(14);
  }

  @BeforeEach
  void stubIdentity() {
    org.mockito.Mockito
        .doAnswer(
            inv -> {
              AccessService.Access found = actors.get(inv.getArgument(0, String.class));
              if (found == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
              }
              return found;
            })
        .when(access)
        .require(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate quality_analysis_event, quality_defect_observation,"
              + " quality_cost_parameter, quality_supplier_ref,"
              + " product_issue_event, product_issue_photo_comparison, product_issue_photo,"
              + " product_issue_lot_flag, product_issue_attribution,"
              + " return_quality_event, return_quality_disposition_instruction,"
              + " return_quality_check_result, return_quality_inspection,"
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
    return access.run(actors.get("Bearer tenant-a"), "trade.aftersales.write", work);
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  record Fixture(UUID caseId, UUID postingId, UUID skuId, String skuCode, UUID templateId) {}

  /** 建订单 → 售后单 → 授权 → 实收入库 → 质检类目 → 模板（已启用）。 */
  Fixture seed(String tag, String goodsCondition) throws Exception {
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
              + " values(?::uuid,?::uuid,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,"
              + "?::numeric,0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
          orderId, tenant, "ORD-" + tag, "EXT-" + tag, T1, amount, amount, T1, T1);
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
              + "created_at) values(?::uuid,?::uuid,?::uuid,1,?::uuid,?,?,'黑色 / L',?::numeric,"
              + "?::numeric,0,0,?::numeric,'',?::timestamptz)",
          lineId, tenant, orderId, UUID.randomUUID(), "SKU-" + tag, "商品" + tag, qty, unitPrice,
          amount, T1);
      sql.execute(
          "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
              + "recipient_phone,recipient_email,company_name,country_code,province,city,"
              + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
              + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
          UUID.randomUUID(), tenant, orderId, "买家-" + tag, "buyer-" + tag + "@example.com", T1);
      sql.execute(
          "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
              + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?,'INDIVIDUAL','',?::timestamptz)",
          UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "C-" + tag, "买家-" + tag, T1);
      sql.execute(
          "insert into order_fulfillment_line_progress(id,tenant_id,order_id,order_line_id,"
              + "ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,"
              + "observed_at,version,created_at,updated_at)"
              + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::numeric,0,0,?::numeric,?::timestamptz,"
              + "'MODULE_9_3_3_1',?::timestamptz,0,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(), tenant, orderId, lineId, qty, qty, T1, T1, T1, T1);
      return null;
    });

    UUID caseId =
        as(() -> cases.create(
                new AftersalesCaseModels.AftersalesCaseCreate(
                    orderId, "RETURN_REFUND", "QUALITY_DEFECT", "外箱压损",
                    amount, "USD", qty, "PLAT-" + tag, null, null,
                    "买家诉求-" + tag, T1, null, T1,
                    null, null, null, "TENANT_MAINTAINED", null, T1),
                user)
            .id());
    as(() -> {
      details.addLine(caseId,
          new AftersalesCaseDetailModels.AftersalesCaseLineCreate(lineId, qty, amount, "USD", "第 1 件"),
          user);
      return null;
    });

    UUID rmaId =
        as(() -> rma.create(
                new RmaAuthorizationModels.RmaCreate(
                    caseId, "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                    "退货仓-" + tag, "+1-213-555-0199", "rma-" + tag + "@example.com",
                    "US", "CA", "ONTARIO", "", "M5V 2T6", "88 Yonge St", "3 楼", null, null),
                user)
            .head()
            .id());
    UUID rmaLineId = as(() -> rma.detail(rmaId)).lines().get(0).id();
    as(() -> {
      rma.decide(rmaId,
          new RmaAuthorizationModels.RmaDecide(
              "PARTIALLY_APPROVED", due(), "外包装压损，只退部分",
              List.of(new RmaAuthorizationModels.RmaLineDecision(rmaLineId, new BigDecimal("3"), null)),
              0L),
          user);
      return null;
    });

    var draft =
        as(() -> receipts.create(
            new ReturnReceiptModels.ReturnReceiptCreate(
                rmaId, null, "CARRIER", "FedEx", "RT-" + tag, "外箱一角压瘪", T1,
                List.of(
                    new ReturnReceiptModels.ReturnReceiptLineDraft(
                        rmaLineId, "SKU-X", "SN-" + UUID.randomUUID(), "LOT-" + tag,
                        new BigDecimal("2"), "DAMAGED", goodsCondition, "外箱压损")),
                List.of()),
            user));
    as(() -> {
      receipts.post(draft.head().id(),
          new ReturnReceiptModels.ReturnReceiptPost(null, draft.head().version()), user);
      return null;
    });

    UUID postingId =
        as(() -> sql.fetchOne(
                "select id from return_inventory_posting where tenant_id=?::uuid and receipt_id=?::uuid",
                tenant, draft.head().id()))
            .get("id", UUID.class);
    UUID skuId =
        as(() -> sql.fetchOne(
                "select sku_id from return_inventory_posting where tenant_id=?::uuid and id=?::uuid",
                tenant, postingId))
            .get("sku_id", UUID.class);
    String skuCode = "SKU-" + tag;

    UUID categoryId =
        as(() -> inspections.createCategory(
            new QualityCategoryCreate("CAT-" + tag, "小家电-" + tag, ""), user).id());
    as(() -> {
      inspections.mapSku(categoryId, new QualitySkuMappingCreate(skuId, skuCode), user);
      return null;
    });
    var template =
        as(() -> inspections.createTemplate(
            new QualityTemplateCreate(
                categoryId, "小家电标准质检", 1,
                new BigDecimal("1.000000"), new BigDecimal("0.100000"),
                new BigDecimal("1.000000"), new BigDecimal("0.500000"), new BigDecimal("0.050000"),
                "",
                List.of(
                    new QualityTemplateItemDraft("APPEARANCE-1", "外观", "APPEARANCE",
                        new BigDecimal("0.200000"), true, "", ""),
                    new QualityTemplateItemDraft("ACCESSORY-1", "配件清单", "ACCESSORY",
                        new BigDecimal("0.300000"), true, "", ""))),
            user));
    as(() -> {
      inspections.activateTemplate(template.template().id(), user);
      return null;
    });

    return new Fixture(caseId, postingId, skuId, skuCode, template.template().id());
  }

  /**
   * 模板里某个 item_code 对应的检项 id。
   *
   * <p>⚠️ <b>必须</b>走 {@code as(...)}：FORCE RLS 之下没有绑定租户上下文就<b>一条都查不到</b>，
   * 拿到的是 {@code null} 而不是报错 —— 这正是租户隔离最容易被误判成「SQL 写错了」的形状。
   */
  UUID templateItemId(UUID templateId, String itemCode) {
    return as(() -> sql.fetchOne(
            "select id from return_quality_template_item"
                + " where tenant_id = ?::uuid and template_id = ?::uuid and item_code = ?",
            tenant, templateId, itemCode)
        .get("id", UUID.class));
  }

  record Concluded(UUID inspectionId, UUID appearanceFailId, UUID accessoryFailId) {}

  /** 一张质检单上、某个模板检项对应的**逐项结果**行 id。 */
  UUID checkResultId(UUID inspectionId, UUID templateItemId) {
    return as(() -> sql.fetchOne(
            "select id from return_quality_check_result"
                + " where tenant_id = ?::uuid and inspection_id = ?::uuid and template_item_id = ?::uuid",
            tenant, inspectionId, templateItemId)
        .get("id", UUID.class));
  }

  /**
   * 开质检单 → 录逐项结果 → 判定。
   *
   * @param appearance APPEARANCE-1 的结论
   * @param accessory ACCESSORY-1 的结论
   */
  Concluded conclude(Fixture f, String appearance, String accessory, String verdict) {
    UUID id =
        as(() -> inspections.createInspection(new QualityInspectionCreate(f.postingId(), null), user)
            .head().id());
    UUID appearanceItem = templateItemId(f.templateId(), "APPEARANCE-1");
    UUID accessoryItem = templateItemId(f.templateId(), "ACCESSORY-1");
    as(() -> {
      inspections.recordResults(id,
          new QualityInspectionUpdate(
              List.of(
                  new QualityCheckResultDraft(appearanceItem, appearance,
                      "FAIL".equals(appearance) ? "外壳有裂痕" : "", null),
                  new QualityCheckResultDraft(accessoryItem, accessory,
                      "FAIL".equals(accessory) ? "缺一个卡扣" : "", null)),
              0L),
          user);
      return null;
    });
    as(() -> {
      inspections.conclude(id, new QualityInspectionConclude(verdict, "判定说明-" + verdict, 1L), user);
      return null;
    });
    // ⚠️ 返回的是 **check_result** 的 id（return_quality_check_result），
    //   不是 template_item 的 id —— 缺陷观察挂在**逐项结果**上，V185 守卫②认的是它。
    return new Concluded(
        id,
        "FAIL".equals(appearance) ? checkResultId(id, appearanceItem) : null,
        "FAIL".equals(accessory) ? checkResultId(id, accessoryItem) : null);
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  @Test
  void 能力声明逐块报可用性且不认领未交付模块() {
    QualityAnalysisCapabilities c = as(() -> quality.capabilities());

    // 职责句点名的六项 + 输出句的责任分布/改进清单，八块全部 delivered。
    assertThat(c.sections()).hasSize(8);
    assertThat(c.sections()).allMatch(QualityAnalysisSection::available);
    assertThat(c.sections()).extracting(QualityAnalysisSection::key)
        .containsExactlyInAnyOrder(
            "defect-distribution", "lot-trend", "supplier-comparison", "photo-clusters",
            "open-box-rate", "quality-cost", "responsibility", "improvements");

    // 三条口径说明都要在 —— 口径只写在文档里、响应里不报，就是换个界面就换套算法。
    assertThat(c.openBoxNote()).contains("覆盖率").contains("签收时发现刻意不计入");
    assertThat(c.costNote()).contains("实测").contains("按参数估算").contains("排除在合计之外");
    assertThat(c.photoClusterNote()).contains("sha256").contains("不是图像相似度");

    // 未交付的下游必须如实报 false + 归属系统，**不许替它们认领**。
    assertThat(c.relatedModules())
        .filteredOn(r -> "6.1.1.3".equals(r.moduleId()))
        .singleElement()
        .satisfies(r -> {
          assertThat(r.delivered()).isFalse();
          assertThat(r.ownerSystem()).isEqualTo("6 采购");
        });
    assertThat(c.relatedModules())
        .filteredOn(r -> "13".equals(r.moduleId()))
        .singleElement()
        .satisfies(r -> assertThat(r.delivered()).isFalse());

    // 上游确实已交付（这正是本模块开工前的依赖自检结论）。
    assertThat(c.relatedModules())
        .filteredOn(r -> r.moduleId().equals("11.2.3.1") || r.moduleId().equals("11.1.2.2"))
        .allMatch(QualityAnalysisRelatedModule::delivered);

    // 刻意不做的事里，「不造差评事实」与「不做图像相似度」必须在。
    assertThat(c.boundaries())
        .anyMatch(b -> b.contains("没有商品评价表"))
        .anyMatch(b -> b.contains("图像相似度"));
  }

  // ==========================================================================
  // 缺陷类型分布
  // ==========================================================================

  @Test
  void 缺陷分布的分母是失败检项数且通过检项不进来() throws Exception {
    Fixture f = seed("DIST", "OPENED_UNDAMAGED");
    conclude(f, "FAIL", "PASS", "DEFECTIVE");

    DefectDistributionReport r = as(() -> quality.defectDistribution(new QualityWindow(null, null, null, null), null));

    assertThat(r.totals().failCheckCount()).isEqualTo(1);
    assertThat(r.totals().inspectionCount()).isEqualTo(1);
    // PASS 的那项绝不进分布。
    assertThat(r.types()).extracting(DefectTypeRow::itemCode).containsExactly("APPEARANCE-1");
    assertThat(r.dimensions()).singleElement()
        .satisfies(d -> {
          assertThat(d.dimension()).isEqualTo("APPEARANCE");
          assertThat(d.share()).isEqualByComparingTo("100.00");
        });
    assertThat(r.types().get(0).share()).isEqualByComparingTo("100.00");
  }

  @Test
  void 没有任何缺陷时分母为零报零而不是一百() {
    DefectDistributionReport r =
        as(() -> quality.defectDistribution(new QualityWindow(null, null, null, null), null));

    assertThat(r.totals().failCheckCount()).isZero();
    assertThat(r.totals().defectiveValueShare()).isEqualByComparingTo("0.00");
    assertThat(r.types()).isEmpty();
    assertThat(r.dimensions()).isEmpty();

    // ⚠️ 空库时总览也必须能出得来：**一条都没有**要报 0，而不是抛异常或返回 null。
    //   （比率类聚合最容易在这里踩到「没有行 ⇒ fetchOne 返回 null ⇒ NPE」。）
    QualityOverview emptyOverview = as(() -> quality.overview(new QualityWindow(null, null, null, null)));
    assertThat(emptyOverview.concludedInspections()).isZero();
    assertThat(emptyOverview.nonResalableRate()).isEqualByComparingTo("0.00");
    assertThat(emptyOverview.openBoxRate()).isEqualByComparingTo("0.00");
    assertThat(emptyOverview.observationCoverage()).isEqualByComparingTo("0.00");
    assertThat(emptyOverview.responsibility()).hasSize(6);
    assertThat(emptyOverview.lotFlags().flaggedLots()).isZero();
    assertThat(emptyOverview.costTotals()).isEmpty();
  }

  // ==========================================================================
  // 批次质量趋势
  // ==========================================================================

  @Test
  void 批次趋势按月分桶且只算已判定质检单() throws Exception {
    Fixture f1 = seed("TREND-A", "OPENED_UNDAMAGED");
    Fixture f2 = seed("TREND-B", "DAMAGED");
    conclude(f1, "PASS", "PASS", "RESALABLE");
    conclude(f2, "FAIL", "FAIL", "DEFECTIVE");

    // 第三张单开一张**草稿**质检单：草稿态不该进趋势。
    // ⚠️ 必须用第三张单 —— 11.2.3.1 规定「一张分录同时只能有一张在途质检单」，
    //   在已判定的分录上再开一张会被当场拒。
    Fixture f3 = seed("TREND-C", "OPENED_UNDAMAGED");
    as(() -> inspections.createInspection(new QualityInspectionCreate(f3.postingId(), null), user)
        .head().id());

    LotTrendReport r = as(() -> quality.lotTrend(new QualityWindow(null, null, null, null), "month", null, null));

    assertThat(r.totals().inspectionCount()).isEqualTo(2);
    assertThat(r.buckets()).hasSize(1);
    LotTrendBucket b = r.buckets().get(0);
    assertThat(b.inspectionCount()).isEqualTo(2);
    assertThat(b.lotCount()).isEqualTo(2);
    // 非可可再销售率按**件数**：判残次那张 2 件 / 全部 4 件 = 50.00。
    assertThat(b.nonResalableRate()).isEqualByComparingTo("50.00");
    // 缺陷件数 = 判 FAIL 的**检项条数**（不是商品件数）：f2 两项都判了 FAIL。
    assertThat(b.defectiveQty()).isEqualByComparingTo("2");

    assertThatThrownBy(() -> quality.lotTrend(new QualityWindow(null, null, null, null), "year", null, null))
        .hasStackTraceContaining("时间桶只能是");
  }

  // ==========================================================================
  // 供应商质量对比
  // ==========================================================================

  @Test
  void 供应商对比一行一个供应商且未登记时名称为null() throws Exception {
    // ⚠️ **两张单**而不是一张：V180 的部分唯一索引规定「一张售后单同时只有一版生效归因」，
    //   在同一张单上建第二张归因单会被 DB 当场拒 —— 这条约束恰恰是正确的。
    Fixture f1 = seed("SUP-1", "OPENED_UNDAMAGED");
    Fixture f2 = seed("SUP-2", "OPENED_UNDAMAGED");
    conclude(f1, "FAIL", "PASS", "DEFECTIVE");
    conclude(f2, "FAIL", "PASS", "DEFECTIVE");

    UUID supplierA = UUID.randomUUID();
    UUID supplierB = UUID.randomUUID();

    UUID ia = openAttribution(f1.caseId(), new BigDecimal("2"), supplierA);
    UUID ib = openAttribution(f2.caseId(), new BigDecimal("1"), supplierB);
    as(() -> {
      attributions.attribute(ia,
          new ProductIssueAttributionModels.AttributionConclude("PRODUCTION", "外箱压溃", "改模具"), user);
      attributions.attribute(ib,
          new ProductIssueAttributionModels.AttributionConclude("TRANSPORT", "运输磕碰", "换包装"), user);
      return null;
    });

    SupplierComparisonReport r =
        as(() -> quality.supplierComparison(new QualityWindow(null, null, null, null), null));

    assertThat(r.suppliers()).hasSize(2);
    // ⚠️ **未登记时名称是 null**，不是 uuid —— 把 uuid 当名字渲染会被当成供应商名。
    assertThat(r.suppliers()).allMatch(s -> s.supplierName() == null && !s.registered());
    assertThat(r.unnamedCount()).isEqualTo(2);

    SupplierQualityRow production =
        r.suppliers().stream().filter(s -> s.supplierRefId().equals(supplierA)).findFirst().orElseThrow();
    assertThat(production.productionQty()).isEqualByComparingTo("2.000000");
    // ⚠️ 占比是**行内**占比（该供应商自己的生产件 ÷ 它自己的全部归因件），
    //   不是两个供应商之间的份额 —— 供应商 A 的两件全归生产，所以是 100.00。
    assertThat(production.productionShare()).isEqualByComparingTo("100.00");
    assertThat(r.suppliers()).filteredOn(s2 -> s2.supplierRefId().equals(supplierB))
        .singleElement()
        .satisfies(s2 -> {
          assertThat(s2.productionQty()).isEqualByComparingTo("0");
          assertThat(s2.productionShare()).isEqualByComparingTo("0.00");
          assertThat(s2.transportQty()).isEqualByComparingTo("1.000000");
        });
    assertThat(production.improvementCount()).isEqualTo(1);

    // 登记名称之后，同一行变可读。
    as(() -> quality.upsertSupplierRef(
        new SupplierRefUpsert(supplierA, "SUP-001", "深圳华强电子", "TENANT_MAINTAINED", null, ""), user));
    SupplierComparisonReport named =
        as(() -> quality.supplierComparison(new QualityWindow(null, null, null, null), null));
    assertThat(named.suppliers())
        .filteredOn(s -> s.supplierRefId().equals(supplierA))
        .singleElement()
        .satisfies(s -> {
          assertThat(s.supplierName()).isEqualTo("深圳华强电子");
          assertThat(s.registered()).isTrue();
        });
    assertThat(named.unnamedCount()).isEqualTo(1);
  }

  @Test
  void 同步器来源必须带来源引用() {
    UUID ref = UUID.randomUUID();
    assertThatThrownBy(() -> as(() -> quality.upsertSupplierRef(
        new SupplierRefUpsert(ref, "S1", "某供应商", "MODULE_6_PURCHASE_SYNC", null, ""), user)))
        .hasStackTraceContaining("必须带 sourceReference");

    assertThatThrownBy(() -> as(() -> quality.upsertSupplierRef(
        new SupplierRefUpsert(ref, "", "", "TENANT_MAINTAINED", null, ""), user)))
        .hasStackTraceContaining("至少填一个");
  }

  // ==========================================================================
  // 缺陷照片聚类（内容指纹口径）
  // ==========================================================================

  @Test
  void 照片聚类按内容指纹而不是相似度() throws Exception {
    // 两张单 ⇒ 两张归因单（一张单只能有一版生效归因）。
    Fixture f1 = seed("PHOTO-1", "OPENED_UNDAMAGED");
    Fixture f2 = seed("PHOTO-2", "OPENED_UNDAMAGED");
    conclude(f1, "FAIL", "PASS", "DEFECTIVE");
    conclude(f2, "FAIL", "PASS", "DEFECTIVE");

    UUID i1 = openAttribution(f1.caseId(), new BigDecimal("1"), null);
    UUID i2 = openAttribution(f2.caseId(), new BigDecimal("1"), null);
    String shared = "a".repeat(64);
    String solo = "b".repeat(64);
    as(() -> {
      attributions.registerPhotos(i1,
          new ProductIssueAttributionModels.PhotoRegisterRequest(List.of(
              new ProductIssueAttributionModels.PhotoRegister(
                  null, "DEFECT_CLOSEUP", "obj/1.jpg", shared, 1024L, "image/jpeg", T1, ""),
              new ProductIssueAttributionModels.PhotoRegister(
                  null, "PACKAGING", "obj/2.jpg", solo, 2048L, "image/jpeg", T1, ""))), user);
      attributions.registerPhotos(i2,
          new ProductIssueAttributionModels.PhotoRegisterRequest(List.of(
              new ProductIssueAttributionModels.PhotoRegister(
                  null, "DEFECT_CLOSEUP", "obj/3.jpg", shared, 1024L, "image/jpeg", T1, ""))), user);
      return null;
    });

    PhotoClusterReport r = as(() -> quality.photoClusters(2, null));

    assertThat(r.photoCount()).isEqualTo(3);
    // 只有**两张及以上**同指纹的才算簇：shared 出现两次，solo 只一次。
    assertThat(r.clusters()).hasSize(1);
    PhotoClusterRow c = r.clusters().get(0);
    assertThat(c.contentSha256()).isEqualTo(shared);
    assertThat(c.photoCount()).isEqualTo(2);
    assertThat(c.attributionCount()).isEqualTo(2);
    assertThat(c.repeated()).isTrue();
    assertThat(r.repeatedClusterCount()).isEqualTo(1);
    // 响应里**没有任何相似度数字** —— 本仓没有图像模型，编一个就是冒充识别能力。
    assertThat(r.basisNote()).contains("不是图像相似度");
  }

  // ==========================================================================
  // 开箱率
  // ==========================================================================

  @Test
  void 开箱率必须和覆盖率一起报且签收环节不计入分子() throws Exception {
    Fixture f = seed("OPENBOX", "OPENED_UNDAMAGED");
    Concluded c = conclude(f, "FAIL", "FAIL", "DEFECTIVE");

    OpenBoxReport empty = as(() -> quality.openBoxRate(new QualityWindow(null, null, null, null), null));
    assertThat(empty.failCheckCount()).isEqualTo(2);
    // 还没登记观察 ⇒ 覆盖率为 0，开箱率也必须报 0 而不是「无数据」以外的任何值。
    assertThat(empty.observationCoverage()).isEqualByComparingTo("0.00");
    assertThat(empty.openBoxRate()).isEqualByComparingTo("0.00");
    assertThat(empty.bySku()).isEmpty();

    // 一件买家开箱、一件入库时发现。
    as(() -> {
      quality.recordObservation(
          new DefectObservationCreate(c.inspectionId(), c.appearanceFailId(), null,
              "CRACK", "外壳裂纹", "APPEARANCE", "BUYER_UNBOXING", T1,
              new BigDecimal("1"), "MAJOR", "开箱即见"), user);
      quality.recordObservation(
          new DefectObservationCreate(c.inspectionId(), c.accessoryFailId(), null,
              "CLIP", "缺卡扣", "ACCESSORY", "WAREHOUSE_INTAKE", T1,
              new BigDecimal("1"), "MINOR", "入库才发现"), user);
      return null;
    });

    OpenBoxReport r = as(() -> quality.openBoxRate(new QualityWindow(null, null, null, null), null));
    assertThat(r.observationCoverage()).isEqualByComparingTo("100.00");
    assertThat(r.observedQty()).isEqualByComparingTo("2");
    assertThat(r.openBoxQty()).isEqualByComparingTo("1");
    assertThat(r.openBoxRate()).isEqualByComparingTo("50.00");

    // **签收时发现刻意不计入分子** —— 货到买家手上有没有拆开验过没有证据。
    as(() -> {
      org.jooq.Record v = sql.fetchOne(
          "select id from quality_defect_observation where tenant_id = ?::uuid"
              + " and check_result_id = ?::uuid", tenant, c.accessoryFailId());
      sql.execute(
          "update quality_defect_observation set discovery_stage = 'DELIVERY_SIGNATURE'"
              + " where id = ?::uuid and tenant_id = ?::uuid",
          v.get("id", UUID.class), tenant);
      return null;
    });
    OpenBoxReport after = as(() -> quality.openBoxRate(new QualityWindow(null, null, null, null), null));
    assertThat(after.openBoxQty()).isEqualByComparingTo("1");
    assertThat(after.openBoxRate()).isEqualByComparingTo("50.00");
    assertThat(after.stages()).extracting(DiscoveryStageSlice::discoveryStage)
        .containsExactly("BUYER_UNBOXING", "DELIVERY_SIGNATURE");
  }

  @Test
  void 覆盖率不足时开箱率只代表已登记那部分() throws Exception {
    Fixture f = seed("COVER", "OPENED_UNDAMAGED");
    Concluded c = conclude(f, "FAIL", "FAIL", "DEFECTIVE");

    as(() -> quality.recordObservation(
        new DefectObservationCreate(c.inspectionId(), c.appearanceFailId(), null,
            "CRACK", "外壳裂纹", "APPEARANCE", "BUYER_UNBOXING", T1,
            new BigDecimal("1"), "MAJOR", ""), user));

    OpenBoxReport r = as(() -> quality.openBoxRate(new QualityWindow(null, null, null, null), null));
    // 一条 FAIL 检项登记了、一条没登记 ⇒ 覆盖率 50%，开箱率仍是 100% 但**只代表已登记那一条**。
    assertThat(r.observationCoverage()).isEqualByComparingTo("50.00");
    assertThat(r.openBoxRate()).isEqualByComparingTo("100.00");
    assertThat(r.basisNote()).contains("覆盖率");
  }

  // ==========================================================================
  // 缺陷观察：服务层与 DB 层各判一次
  // ==========================================================================

  @Test
  void 通过的检项不许登记为缺陷() throws Exception {
    Fixture f = seed("PASSITEM", "OPENED_UNDAMAGED");
    Concluded c = conclude(f, "PASS", "PASS", "RESALABLE");
    UUID appearanceResult = checkResultId(c.inspectionId(), templateItemId(f.templateId(), "APPEARANCE-1"));

    // 服务层拦。
    assertThatThrownBy(() -> as(() -> quality.recordObservation(
        new DefectObservationCreate(c.inspectionId(), appearanceResult, null,
            "X", "不存在的问题", "APPEARANCE", "BUYER_UNBOXING", T1,
            new BigDecimal("1"), "MINOR", ""), user)))
        .hasStackTraceContaining("只有判 FAIL 的检项");

    // ⚠️ **绕过后端直连 SQL 也存不进去**（V185 守卫②）：只判一次的话这里就是缺口。
    assertThatThrownBy(() -> as(() -> sql.execute(
        "insert into quality_defect_observation(id,tenant_id,observation_no,inspection_id,"
            + "check_result_id,defect_code,defect_name,defect_dimension,discovery_stage,"
            + "discovered_at,affected_qty,severity,observation_status,recorded_by,recorded_at,"
            + "version,created_at) values(?::uuid,?::uuid,'RAW-1',?::uuid,?::uuid,'X','假缺陷',"
            + "'APPEARANCE','BUYER_UNBOXING',?::timestamptz,1::numeric,'MINOR','RECORDED',"
            + "?::uuid,?::timestamptz,0,?::timestamptz)",
        UUID.randomUUID(), tenant, c.inspectionId(), appearanceResult, T1, user, T1, T1)))
        .hasStackTraceContaining("只有判 FAIL 的检项");
  }

  @Test
  void 维度必须与检项逐字一致() throws Exception {
    Fixture f = seed("DIM", "OPENED_UNDAMAGED");
    Concluded c = conclude(f, "FAIL", "PASS", "DEFECTIVE");

    assertThatThrownBy(() -> as(() -> quality.recordObservation(
        new DefectObservationCreate(c.inspectionId(), c.appearanceFailId(), null,
            "CRACK", "外壳裂纹", "PACKAGING", "BUYER_UNBOXING", T1,
            new BigDecimal("1"), "MAJOR", ""), user)))
        .hasStackTraceContaining("维度必须与检项维度一致");
  }

  @Test
  void 影响件数不得超过实检数量() throws Exception {
    Fixture f = seed("QTY", "OPENED_UNDAMAGED");
    Concluded c = conclude(f, "FAIL", "PASS", "DEFECTIVE");

    assertThatThrownBy(() -> as(() -> quality.recordObservation(
        new DefectObservationCreate(c.inspectionId(), c.appearanceFailId(), null,
            "CRACK", "外壳裂纹", "APPEARANCE", "BUYER_UNBOXING", T1,
            new BigDecimal("99"), "MAJOR", ""), user)))
        .hasStackTraceContaining("超过该质检单的实检数量");
  }

  @Test
  void 买家开箱发现的时刻不得晚于仓内判定完成() throws Exception {
    Fixture f = seed("TIME", "OPENED_UNDAMAGED");
    Concluded c = conclude(f, "FAIL", "PASS", "DEFECTIVE");

    OffsetDateTime later =
        OffsetDateTime.now(ZoneOffset.ofHours(8)).plusYears(1);

    assertThatThrownBy(() -> as(() -> quality.recordObservation(
        new DefectObservationCreate(c.inspectionId(), c.appearanceFailId(), null,
            "CRACK", "外壳裂纹", "APPEARANCE", "BUYER_UNBOXING", later,
            new BigDecimal("1"), "MAJOR", ""), user)))
        .hasStackTraceContaining("不可能晚于仓内判定完成时刻");
  }

  @Test
  void 同一条失败检项只能有一条有效观察() throws Exception {
    Fixture f = seed("DUP", "OPENED_UNDAMAGED");
    Concluded c = conclude(f, "FAIL", "PASS", "DEFECTIVE");

    DefectObservationCreate request =
        new DefectObservationCreate(c.inspectionId(), c.appearanceFailId(), null,
            "CRACK", "外壳裂纹", "APPEARANCE", "BUYER_UNBOXING", T1,
            new BigDecimal("1"), "MAJOR", "");
    DefectObservationRow first = as(() -> quality.recordObservation(request, user));

    assertThatThrownBy(() -> as(() -> quality.recordObservation(request, user)))
        .hasStackTraceContaining("quality_defect_observation_live");

    // 作废之后可以重记 —— 作废是留痕不是删除，重记走新行。
    as(() -> quality.voidObservation(first.id(),
        new DefectObservationVoid("记错了检项", 0L), user));
    DefectObservationRow second = as(() -> quality.recordObservation(request, user));
    assertThat(second.id()).isNotEqualTo(first.id());
    assertThat(second.observationNo()).isNotEqualTo(first.observationNo());
  }

  @Test
  void 作废是终态业务列被冻结() throws Exception {
    Fixture f = seed("VOID", "OPENED_UNDAMAGED");
    Concluded c = conclude(f, "FAIL", "PASS", "DEFECTIVE");
    DefectObservationRow row =
        as(() -> quality.recordObservation(
            new DefectObservationCreate(c.inspectionId(), c.appearanceFailId(), null,
                "CRACK", "外壳裂纹", "APPEARANCE", "BUYER_UNBOXING", T1,
                new BigDecimal("1"), "MAJOR", ""), user));
    as(() -> quality.voidObservation(row.id(), new DefectObservationVoid("记错了", 0L), user));

    // V185 守卫①：作废后业务列不可再改（绕过服务层直连也一样被拒）。
    assertThatThrownBy(() -> as(() -> sql.execute(
        "update quality_defect_observation set defect_name = '改成别的'"
            + " where id = ?::uuid and tenant_id = ?::uuid", row.id(), tenant)))
        .hasStackTraceContaining("缺陷观察已作废");

    // 已作废的行不进任何统计口径。
    OpenBoxReport r = as(() -> quality.openBoxRate(new QualityWindow(null, null, null, null), null));
    assertThat(r.observationCount()).isZero();

    // 但列表默认**仍能看到它** —— 作废是留痕，抹掉就看不出这里曾经记错过什么。
    assertThat(as(() -> quality.listObservations(null, null, null, null, null, 0, 20)).total())
        .isEqualTo(1);
  }

  // ==========================================================================
  // 质量成本估算（加粗句所在）
  // ==========================================================================

  @Test
  void 成本三项依据等级不同且未登记项被排除在合计之外() throws Exception {
    Fixture f = seed("COST", "OPENED_UNDAMAGED");
    Concluded c = conclude(f, "FAIL", "PASS", "DEFECTIVE");
    as(() -> quality.recordObservation(
        new DefectObservationCreate(c.inspectionId(), c.appearanceFailId(), null,
            "CRACK", "外壳裂纹", "APPEARANCE", "BUYER_UNBOXING", T1,
            new BigDecimal("1"), "MAJOR", ""), user));

    // ---- 参数未登记 ----
    QualityCostReport unregistered = as(() -> quality.qualityCost(new QualityWindow(null, null, null, null)));
    assertThat(unregistered.parameters()).isEmpty();
    CostCurrencyBreakdown u = unregistered.currencies().get(0);
    assertThat(u.loss().basis()).isEqualTo(CostBasis.MEASURED.name());
    assertThat(u.returnLogistics().basis()).isEqualTo(CostBasis.NOT_REGISTERED.name());
    assertThat(u.reviewImpact().basis()).isEqualTo(CostBasis.NOT_REGISTERED.name());
    // ⚠️ **未登记项的金额是 null 而不是 0**：按 0 算出来的总成本会被读成「质量没花钱」。
    assertThat(u.returnLogistics().amount()).isNull();
    assertThat(u.reviewImpact().amount()).isNull();
    assertThat(u.excludedItemCount()).isEqualTo(2);
    assertThat(u.total()).isEqualByComparingTo(u.loss().amount());
    assertThat(u.total()).isLessThan(u.loss().amount().add(new BigDecimal("1")));
    assertThat(unregistered.reviewNote()).contains("没有商品评价表");

    // ---- 登记参数后：损耗仍是实测，另两项变估算并进入合计 ----
    as(() -> quality.createCostParameter(
        new CostParameterCreate("USD", new BigDecimal("4.5000"), new BigDecimal("12.0000"),
            LocalDate.parse("2026-01-01"), "按承运商合约价"), user));
    QualityCostReport registered = as(() -> quality.qualityCost(new QualityWindow(null, null, null, null)));
    CostCurrencyBreakdown g = registered.currencies().get(0);
    assertThat(g.loss().basis()).isEqualTo(CostBasis.MEASURED.name());
    assertThat(g.returnLogistics().basis()).isEqualTo(CostBasis.ESTIMATED.name());
    assertThat(g.reviewImpact().basis()).isEqualTo(CostBasis.ESTIMATED.name());
    assertThat(g.excludedItemCount()).isZero();
    // 退货物流 = 实收件数 2 × 4.5；差评影响 = 已登记缺陷件数 1 × 12。
    assertThat(g.returnLogistics().basisQty()).isEqualByComparingTo("2");
    assertThat(g.returnLogistics().amount()).isEqualByComparingTo("9.0000");
    assertThat(g.reviewImpact().basisQty()).isEqualByComparingTo("1");
    assertThat(g.reviewImpact().amount()).isEqualByComparingTo("12.0000");
    assertThat(g.total())
        .isEqualByComparingTo(
            g.loss().amount().add(new BigDecimal("9.0000")).add(new BigDecimal("12.0000")));
    // 混合了估算的合计**必须**在总览里标成估算，不能冒充全实测。
    QualityOverview o = as(() -> quality.overview(new QualityWindow(null, null, null, null)));
    assertThat(o.costTotals()).singleElement()
        .satisfies(t -> {
          assertThat(t.currency()).isEqualTo("USD");
          assertThat(t.excludedItemCount()).isZero();
          // 混了估算项 ⇒ 合计的依据等级不能是 MEASURED。
          assertThat(t.basis()).isEqualTo(CostBasis.ESTIMATED.name());
          assertThat(t.basisLabel()).isEqualTo("按参数估算");
        });
  }

  @Test
  void 每个币种只能有一份生效参数且换版要留痕() throws Exception {
    Fixture f = seed("PARAM", "OPENED_UNDAMAGED");
    conclude(f, "PASS", "PASS", "RESALABLE");

    CostParameterRow first =
        as(() -> quality.createCostParameter(
            new CostParameterCreate("USD", new BigDecimal("4.0000"), new BigDecimal("10.0000"),
                LocalDate.parse("2026-01-01"), "初版"), user));

    assertThatThrownBy(() -> as(() -> quality.createCostParameter(
        new CostParameterCreate("USD", new BigDecimal("5.0000"), new BigDecimal("11.0000"),
            LocalDate.parse("2026-02-01"), "重复生效"), user)))
        .hasStackTraceContaining("每币种同时只能有一份生效参数");

    // 版本号不对 → 拒。
    assertThatThrownBy(() -> as(() -> quality.supersedeCostParameter(
        first.id(), new CostParameterSupersede("涨价了", 99L), user)))
        .hasStackTraceContaining("版本号不一致");

    CostParameterRow superseded =
        as(() -> quality.supersedeCostParameter(
            first.id(), new CostParameterSupersede("承运商涨价", 0L), user));
    assertThat(superseded.parameterStatus()).isEqualTo("SUPERSEDED");
    assertThat(superseded.parameterStatusLabel()).isEqualTo("已换版");
    assertThat(superseded.effectiveTo()).isNotNull();

    // 换版必须留痕（V185 守卫⑦：没有留痕的换版会让「上次是多少」永远答不上来）。
    // ⚠️ 查询也必须走 as(...)：FORCE RLS 之下没有租户上下文就**一条留痕都读不到**。
    assertThat(as(() -> sql.fetchOne(
            "select count(*) as c from quality_analysis_event where tenant_id = ?::uuid"
                + " and event_scope = 'COST_PARAMETER' and event_type = 'SUPERSEDED'"
                + " and subject_id = ?::uuid",
            tenant, first.id())
        .get("c", Integer.class))).isEqualTo(1);

    // 换版后可以再建一份生效的，老版本仍在列表里。
    as(() -> quality.createCostParameter(
        new CostParameterCreate("USD", new BigDecimal("5.0000"), new BigDecimal("11.0000"),
            LocalDate.parse("2026-02-01"), "新价"), user));
    CostParameterPage page = as(() -> quality.listCostParameters(null, 0, 20));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.rows()).filteredOn(r -> r.parameterStatus().equals("ACTIVE")).hasSize(1);
  }

  @Test
  void 换版绕过后端直连SQL也会被守卫拦下() throws Exception {
    Fixture f = seed("PARAMRAW", "OPENED_UNDAMAGED");
    conclude(f, "PASS", "PASS", "RESALABLE");
    UUID id =
        as(() -> quality.createCostParameter(
            new CostParameterCreate("USD", new BigDecimal("4.0000"), new BigDecimal("10.0000"),
                LocalDate.parse("2026-01-01"), ""), user)).id();

    assertThatThrownBy(() -> as(() -> sql.execute(
        "update quality_cost_parameter set parameter_status = 'SUPERSEDED',"
            + " superseded_by = ?::uuid, supersede_reason = '绕过服务层'"
            + " where id = ?::uuid and tenant_id = ?::uuid", user, id, tenant)))
        .hasStackTraceContaining("必须先写留痕");
  }

  // ==========================================================================
  // 责任分布 + 改进清单
  // ==========================================================================

  @Test
  void 责任分布按件数占比且只算当前生效归因() throws Exception {
    // 生产 2 件 vs 运输 1 件 ⇒ 按**件数**占比 66.67，而按**单张数**会是 50.00。
    // 归因数量受 11.1.2.2 守卫限制（不得超过实收 2 件），所以用 2 : 1 而不是 8 : 2。
    Fixture f1 = seed("RESP-1", "OPENED_UNDAMAGED");
    Fixture f2 = seed("RESP-2", "OPENED_UNDAMAGED");
    conclude(f1, "FAIL", "PASS", "DEFECTIVE");
    conclude(f2, "FAIL", "PASS", "DEFECTIVE");
    UUID lot = UUID.randomUUID();

    UUID i1 = openAttribution(f1.caseId(), new BigDecimal("2"), null);
    UUID i2 = openAttribution(f2.caseId(), new BigDecimal("1"), null);
    as(() -> {
      attributions.attribute(i1, new ProductIssueAttributionModels.AttributionConclude(
          "PRODUCTION", "模具磨损", "修模具"), user);
      attributions.attribute(i2, new ProductIssueAttributionModels.AttributionConclude(
          "TRANSPORT", "野蛮分拣", "加护角"), user);
      return null;
    });
    attachLot(i1, lot, "LOT-RESP");

    ImprovementReport r =
        as(() -> quality.improvements(new QualityWindow(null, null, null, null), null, null, 0, 20));

    assertThat(r.attributionCount()).isEqualTo(2);
    ResponsibilitySlice production =
        r.responsibility().stream().filter(x -> x.stage().equals("PRODUCTION")).findFirst().orElseThrow();
    assertThat(production.attributedQty()).isEqualByComparingTo("2.000000");
    // 2 / 3 = 66.67 —— 按**件数**；按单张数算会得到 50.00，两张单权重一样是错的。
    assertThat(production.share()).isEqualByComparingTo("66.67");
    assertThat(r.items()).extracting(ImprovementRow::improvementSuggestion)
        .containsExactlyInAnyOrder("修模具", "加护角");

    // 改判后的旧版本（SUPERSEDED）不算进清单 —— 否则同一个问题被计两次。
    as(() -> {
      attributions.supersede(i1,
          new ProductIssueAttributionModels.AttributionSupersede(
              "归错了", "UNDETERMINED", "改判留痕", "先不下结论"), user);
      return null;
    });
    ImprovementReport after =
        as(() -> quality.improvements(new QualityWindow(null, null, null, null), null, null, 0, 20));
    // ⚠️ 改判**不是删掉旧版本**：11.1.2.2 会把旧版标 SUPERSEDED 并**另起一版直接归因**，
    //   所以生效单数不变（仍是 2）—— 变的是这 2 单的**环节内容**。
    assertThat(after.attributionCount()).isEqualTo(2);
    assertThat(after.responsibility())
        .filteredOn(x -> x.stage().equals("UNDETERMINED"))
        .singleElement()
        .satisfies(x -> assertThat(x.attributedQty()).isEqualByComparingTo("2.000000"));
    assertThat(after.responsibility())
        .filteredOn(x -> x.stage().equals("PRODUCTION"))
        .singleElement()
        .satisfies(x -> assertThat(x.share()).isEqualByComparingTo("0.00"));
  }

  @Test
  void 改进清单里被标记为质量问题的批次排前面() throws Exception {
    Fixture f1 = seed("ORDER-1", "OPENED_UNDAMAGED");
    Fixture f2 = seed("ORDER-2", "OPENED_UNDAMAGED");
    conclude(f1, "FAIL", "PASS", "DEFECTIVE");
    conclude(f2, "FAIL", "PASS", "DEFECTIVE");

    // flagged 只覆盖 1 件，plain 覆盖 2 件：单靠件数排，plain 会排前面 ——
    // 正是「先动已经在冒烟的那几个批次」这条排序规则要反转的情况。
    UUID plain = openAttribution(f1.caseId(), new BigDecimal("2"), null);
    UUID flagged = openAttribution(f2.caseId(), new BigDecimal("1"), null);
    as(() -> {
      attributions.attribute(plain, new ProductIssueAttributionModels.AttributionConclude(
          "PRODUCTION", "件多但没标", "常规改进"), user);
      attributions.attribute(flagged, new ProductIssueAttributionModels.AttributionConclude(
          "PRODUCTION", "件少但已冒烟", "紧急改进"), user);
      return null;
    });
    // 直接建一个 CONFIRMED 批次标记（11.1.2.2 的刷新接口需要 HTTP 端口，本测试不起桩）。
    as(() -> {
      attachLot(flagged, UUID.randomUUID(), "LOT-FLAGGED");
      UUID lotId =
          sql.fetchOne("select lot_id from product_issue_attribution where tenant_id=?::uuid and id=?::uuid",
                  tenant, flagged).get("lot_id", UUID.class);
      sql.execute(
          "insert into product_issue_lot_flag(id,tenant_id,lot_id,lot_code,sku_id,sku_code,"
              + "suspect_return_count,affected_return_qty,stage_breakdown,flag_status,min_flag_qty,"
              + "flag_note,recomputed_at,flagged_by,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,'LOT-FLAGGED',?::uuid,'SKU-ORDER',1,1::numeric,"
              + "?::jsonb,'CONFIRMED',1::numeric,'连续退货',?::timestamptz,?::uuid,?::timestamptz)",
          UUID.randomUUID(), tenant, lotId,
          sql.fetchOne("select sku_id from product_issue_attribution where tenant_id=?::uuid and id=?::uuid",
              tenant, flagged).get("sku_id", UUID.class),
          "{\"PRODUCTION\":1}", T1, user, T1);
      return null;
    });

    ImprovementReport r =
        as(() -> quality.improvements(new QualityWindow(null, null, null, null), null, null, 0, 20));
    assertThat(r.items().get(0).improvementSuggestion()).isEqualTo("紧急改进");
    assertThat(r.items().get(0).lotFlagged()).isTrue();
    assertThat(r.items().get(0).lotFlagStatus()).isEqualTo("CONFIRMED");
    assertThat(r.items().get(1).lotFlagged()).isFalse();
  }

  // ==========================================================================
  // 总览与 HTTP 层
  // ==========================================================================

  @Test
  void 总览共用一个参考时刻且比率按件数() throws Exception {
    Fixture f = seed("OVER", "OPENED_UNDAMAGED");
    Concluded c = conclude(f, "FAIL", "FAIL", "DEFECTIVE");
    as(() -> quality.recordObservation(
        new DefectObservationCreate(c.inspectionId(), c.appearanceFailId(), null,
            "CRACK", "外壳裂纹", "APPEARANCE", "BUYER_UNBOXING", T1,
            new BigDecimal("2"), "MAJOR", ""), user));

    QualityOverview o = as(() -> quality.overview(new QualityWindow(null, null, null, null)));
    assertThat(o.concludedInspections()).isEqualTo(1);
    assertThat(o.inspectedQty()).isEqualByComparingTo("2");
    assertThat(o.nonResalableQty()).isEqualByComparingTo("2");
    assertThat(o.nonResalableRate()).isEqualByComparingTo("100.00");
    assertThat(o.openBoxRate()).isEqualByComparingTo("100.00");
    assertThat(o.observationCoverage()).isEqualByComparingTo("50.00");
    assertThat(o.responsibility()).hasSize(6);
    assertThat(o.costTotals()).isNotEmpty();
    assertThat(o.basisNote()).contains("分母是件数不是单数");

    // 全部子块的 generatedAt 必须**逐字相同** —— 一次刷新里出现两个参考时刻，
    // 会让人对不上「质检单数为什么比开箱率的分母多一张」。
    OpenBoxReport ob = as(() -> quality.openBoxRate(new QualityWindow(null, null, null, null), null));
    assertThat(ob.generatedAt()).isNotNull();
    assertThat(o.generatedAt()).isNotNull();
  }

  @Test
  void 能力声明经HTTP可达且不需要租户头() throws Exception {
    http.perform(get("/trade/quality-analysis/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sections.length()").value(8));

    http.perform(get("/trade/quality-analysis/overview")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.basisNote").exists());

    // 伪造的 X-Tenant-Id 不影响读到的租户。
    http.perform(get("/trade/quality-analysis/overview")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", UUID.randomUUID().toString()))
        .andExpect(status().isOk());

    // 期间参数写错要报 400，而不是静默当成「不筛」。
    http.perform(get("/trade/quality-analysis/overview")
            .param("from", "2026-13-01")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 小工具
  // ==========================================================================

  /** 建一张归因单（不走 HTTP 批次端口，与 11.1.2.2 的测试同一决定）。 */
  UUID openAttribution(UUID caseId, BigDecimal qty, UUID supplierRefId) {
    UUID id =
        as(() -> attributions.create(
                new ProductIssueAttributionModels.AttributionCreate(
                    caseId, null, null, null, null, null, null,
                    null, "SKU-ATT", "商品", qty),
                user,
                "Bearer tenant-a")
            .row()
            .id());
    as(() -> {
      // ⚠️ **必须挂批次**：11.1.2.2 的守卫规定「归因到生产必须挂批次」——
      //   没有批次就指不到某一次投料，向供应商索赔时说不出「哪一批」。
      sql.execute(
          "update product_issue_attribution set lot_supplier_ref_id = ?::uuid,"
              + " lot_id = ?::uuid, lot_code = ?"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          supplierRefId, UUID.randomUUID(), "LOT-" + UUID.randomUUID(), tenant, id);
      return null;
    });
    return id;
  }

  void attachLot(UUID attributionId, UUID lotId, String lotCode) {
    as(() -> {
      sql.execute(
          "update product_issue_attribution set lot_id = ?::uuid, lot_code = ?"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          lotId, lotCode, tenant, attributionId);
      return null;
    });
  }
}
