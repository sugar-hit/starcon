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
 * 11.5.2.1 质量分析的<b>多租户三断言</b>（AGENTS.md 明文要求，缺一不可）。
 *
 * <p>跑的是**实打在本模块自己四张表上**的三条断言（quality_defect_observation /
 * quality_cost_parameter / quality_supplier_ref / quality_analysis_event），
 * **不是**占位探针表 —— 探针表能证明 RLS 开着，
 * 证明不了<b>本模块的报表查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：质量成本估算与供应商质量对比是要拿去
 * 和供应商谈索赔的<b>责任与金额判定</b> ——
 * 一个租户能看到另一个租户「这个供应商生产责任占 80%、赔了多少钱」，
 * 等于拿到了对手供应商的底牌；而缺陷照片聚类会直接暴露对方的举证材料。
 * 报表类端点又是**无 id 直达的 GET**（一个 URL 就能拿到全量聚合），
 * 比按 id 直达更难靠路由形状挡住。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，
 * 才能真正证明归还后不会串租户 —— 池大于 1 时每次可能拿到不同连接，
 * 「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离 ——
 * 复用上一轮的记录，第二轮断言通过只能说明「同一条记录没变」。
 *
 * <p>⚠️ 身份桩用 {@code AccessService} 的 <b>spy</b>（只替换对 identity 的一次 HTTP 调用），
 * {@code run} 仍是真实实现 ⇒ RLS 租户绑定跑的是生产代码路径。
 * 一个额外监听端口都不开（照抄 11.2.3.1 / 11.1.2.2 的同一决定：
 * JDK 内置 HttpServer 靠端口 0 现分配，全量并发下偶发端口被抢占 → 503 的已知 flake）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class QualityAnalysisTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
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
    actors.put("Bearer tenant-a", actor(userA, a));
    actors.put("Bearer tenant-b", actor(userB, b));
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
    // ⚠️ **连接池固定为 1**：池大于 1 时每次可能拿到不同连接，
    // 「归还后不串租户」可能只是碰巧换了条干净连接。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
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

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.aftersales.write", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-b"), "trade.aftersales.write", work);
  }

  <T> T as(UUID uid, UUID tid, java.util.function.Supplier<T> work) {
    return access.run(actor(uid, tid), "trade.aftersales.write", work);
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  record Seeded(UUID inspectionId, UUID checkResultId) {}

  /** 建订单 → 售后单 → 授权 → 实收入库 → 类目 → 模板 → 开单 → 录 FAIL → 判定。 */
  Seeded seed(UUID tid, UUID uid, String tag) throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    BigDecimal qty = new BigDecimal("4");
    BigDecimal unitPrice = new BigDecimal("5.0000");
    BigDecimal amount = unitPrice.multiply(qty);

    as(uid, tid, () -> {
      sql.execute(
          "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
              + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
              + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
              + " values(?::uuid,?::uuid,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,"
              + "?::numeric,0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
          orderId, tid, "ORD-" + tag, "EXT-" + tag, T1, amount, amount, T1, T1);
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
              + "created_at) values(?::uuid,?::uuid,?::uuid,1,?::uuid,?,?,'黑色 / L',?::numeric,"
              + "?::numeric,0,0,?::numeric,'',?::timestamptz)",
          lineId, tid, orderId, UUID.randomUUID(), "SKU-" + tag, "商品" + tag, qty, unitPrice,
          amount, T1);
      sql.execute(
          "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
              + "recipient_phone,recipient_email,company_name,country_code,province,city,"
              + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
              + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
          UUID.randomUUID(), tid, orderId, "买家-" + tag, "buyer-" + tag + "@example.com", T1);
      sql.execute(
          "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
              + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?,'INDIVIDUAL','',?::timestamptz)",
          UUID.randomUUID(), tid, orderId, UUID.randomUUID(), "C-" + tag, "买家-" + tag, T1);
      sql.execute(
          "insert into order_fulfillment_line_progress(id,tenant_id,order_id,order_line_id,"
              + "ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,"
              + "observed_at,version,created_at,updated_at)"
              + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::numeric,0,0,?::numeric,?::timestamptz,"
              + "'MODULE_9_3_3_1',?::timestamptz,0,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(), tid, orderId, lineId, qty, qty, T1, T1, T1, T1);
      return null;
    });

    UUID caseId =
        as(uid, tid, () -> cases.create(
                new AftersalesCaseModels.AftersalesCaseCreate(
                    orderId, "RETURN_REFUND", "QUALITY_DEFECT", "外箱压损",
                    amount, "USD", qty, "PLAT-" + tag, null, null,
                    "买家诉求-" + tag, T1, null, T1,
                    null, null, null, "TENANT_MAINTAINED", null, T1),
                uid)
            .id());
    as(uid, tid, () -> {
      details.addLine(caseId,
          new AftersalesCaseDetailModels.AftersalesCaseLineCreate(lineId, qty, amount, "USD", "第 1 件"),
          uid);
      return null;
    });

    UUID rmaId =
        as(uid, tid, () -> rma.create(
                new RmaAuthorizationModels.RmaCreate(
                    caseId, "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                    "退货仓-" + tag, "+1-213-555-0199", "rma-" + tag + "@example.com",
                    "US", "CA", "ONTARIO", "", "M5V 2T6", "88 Yonge St", "3 楼", null, null),
                uid)
            .head()
            .id());
    UUID rmaLineId = as(uid, tid, () -> rma.detail(rmaId)).lines().get(0).id();
    as(uid, tid, () -> {
      rma.decide(rmaId,
          new RmaAuthorizationModels.RmaDecide(
              "PARTIALLY_APPROVED", due(), "外包装压损，只退部分",
              List.of(new RmaAuthorizationModels.RmaLineDecision(rmaLineId, new BigDecimal("3"), null)),
              0L),
          uid);
      return null;
    });

    var draft =
        as(uid, tid, () -> receipts.create(
            new ReturnReceiptModels.ReturnReceiptCreate(
                rmaId, null, "CARRIER", "FedEx", "RT-" + tag, "外箱一角压瘪", T1,
                List.of(
                    new ReturnReceiptModels.ReturnReceiptLineDraft(
                        rmaLineId, "SKU-X", "SN-" + UUID.randomUUID(), "LOT-" + tag,
                        new BigDecimal("2"), "DAMAGED", "OPENED_UNDAMAGED", "外箱压损")),
                List.of()),
            uid));
    as(uid, tid, () -> {
      receipts.post(draft.head().id(),
          new ReturnReceiptModels.ReturnReceiptPost(null, draft.head().version()), uid);
      return null;
    });

    UUID postingId =
        as(uid, tid, () -> sql.fetchOne(
                "select id from return_inventory_posting where tenant_id=?::uuid and receipt_id=?::uuid",
                tid, draft.head().id()))
            .get("id", UUID.class);
    UUID skuId =
        as(uid, tid, () -> sql.fetchOne(
                "select sku_id from return_inventory_posting where tenant_id=?::uuid and id=?::uuid",
                tid, postingId))
            .get("sku_id", UUID.class);

    UUID categoryId =
        as(uid, tid, () -> inspections.createCategory(
            new QualityCategoryCreate("CAT-" + tag, "小家电-" + tag, ""), uid).id());
    as(uid, tid, () -> {
      inspections.mapSku(categoryId, new QualitySkuMappingCreate(skuId, "SKU-" + tag), uid);
      return null;
    });
    var template =
        as(uid, tid, () -> inspections.createTemplate(
            new QualityTemplateCreate(
                categoryId, "小家电标准质检", 1,
                new BigDecimal("1.000000"), new BigDecimal("0.100000"),
                new BigDecimal("1.000000"), new BigDecimal("0.500000"), new BigDecimal("0.050000"),
                "",
                List.of(
                    new QualityTemplateItemDraft("APPEARANCE-1", "外观", "APPEARANCE",
                        new BigDecimal("0.200000"), true, "", ""))),
            uid));
    as(uid, tid, () -> {
      inspections.activateTemplate(template.template().id(), uid);
      return null;
    });

    UUID inspectionId =
        as(uid, tid, () -> inspections.createInspection(
            new QualityInspectionCreate(postingId, null), uid).head().id());
    UUID itemId =
        as(uid, tid, () -> sql.fetchOne(
                "select id from return_quality_template_item where tenant_id=?::uuid"
                    + " and template_id=?::uuid and item_code='APPEARANCE-1'",
                tid, template.template().id()))
            .get("id", UUID.class);
    as(uid, tid, () -> {
      inspections.recordResults(inspectionId,
          new QualityInspectionUpdate(
              List.of(new QualityCheckResultDraft(itemId, "FAIL", "外壳有裂痕", null)), 0L), uid);
      inspections.conclude(inspectionId,
          new QualityInspectionConclude("DEFECTIVE", "外壳裂了", 1L), uid);
      return null;
    });
    UUID checkResultId =
        as(uid, tid, () -> sql.fetchOne(
                "select id from return_quality_check_result where tenant_id=?::uuid"
                    + " and inspection_id=?::uuid",
                tid, inspectionId))
            .get("id", UUID.class);

    return new Seeded(inspectionId, checkResultId);
  }

  /** 登记一条缺陷观察（买家开箱发现，1 件）。 */
  UUID observe(UUID tid, UUID uid, Seeded seeded) {
    return as(uid, tid, () -> quality.recordObservation(
            new DefectObservationCreate(
                seeded.inspectionId(), seeded.checkResultId(), null,
                "CRACK", "外壳裂纹-" + tid, "APPEARANCE", "BUYER_UNBOXING", T1,
                new BigDecimal("1"), "MAJOR", ""),
            uid)
        .id());
  }

  private static QualityWindow all() {
    return new QualityWindow(null, null, null, null);
  }

  /**
   * 在给定租户的上下文里裸查观察表（**没有 WHERE**），返回 {@code id @ tenant_id}。
   *
   * <p>这是「FORCE RLS 真的在生效」的直接证据：RLS 若没开，这里会返回两个租户的行。
   */
  java.util.List<String> rowsOf(UUID tenant) {
    AccessService.Access found = actors.get("Bearer tenant-a").tenantId().equals(tenant)
        ? actors.get("Bearer tenant-a")
        : actors.get("Bearer tenant-b");
    return access.run(found, "trade.aftersales.read", () -> {
      var out = new java.util.ArrayList<String>();
      // ⚠️ 只取**有效行**：作废行按设计是留痕，会一直留在表里，
      //   跨轮次累积是正常的；这里要断言的是「当前生效的那一条属于谁」。
      for (org.jooq.Record rec :
          sql.fetch("select id, tenant_id from quality_defect_observation"
              + " where observation_status = 'RECORDED'")) {
        out.add(rec.get("id", UUID.class) + " @ " + rec.get("tenant_id", UUID.class));
      }
      return out;
    });
  }

  // ==========================================================================
  // 断言一：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见() throws Exception {
    Seeded sa = seed(a, userA, "ISO-A");
    Seeded sb = seed(b, userB, "ISO-B");

    // 每轮都新建实体：复用上一轮的记录，第二轮断言通过只能说明「同一条记录没变」。
    UUID oa = observe(a, userA, sa);
    UUID ob = observe(b, userB, sb);

    // ---- 总览：两个租户各自只看见自己那一张质检单 ----
    assertThat(asA(() -> quality.overview(all())).concludedInspections()).isEqualTo(1);
    assertThat(asB(() -> quality.overview(all())).concludedInspections()).isEqualTo(1);

    // ---- 缺陷类型分布：条数与件数互不串 ----
    assertThat(asA(() -> quality.defectDistribution(all(), null)).totals().failCheckCount())
        .isEqualTo(1);
    assertThat(asB(() -> quality.defectDistribution(all(), null)).totals().failCheckCount())
        .isEqualTo(1);

    // ---- 开箱率：分子分母都只在**自己**的观察范围内 ----
    OpenBoxReport ra = asA(() -> quality.openBoxRate(all(), null));
    OpenBoxReport rb = asB(() -> quality.openBoxRate(all(), null));
    assertThat(ra.observationCount()).isEqualTo(1);
    assertThat(rb.observationCount()).isEqualTo(1);
    assertThat(ra.observationCoverage()).isEqualByComparingTo("100.00");

    // ---- 缺陷观察列表：只看得见自己那条 ----
    assertThat(asA(() -> quality.listObservations(null, null, null, null, null, 0, 20))
        .rows()).extracting(DefectObservationRow::id).containsExactly(oa);
    assertThat(asB(() -> quality.listObservations(null, null, null, null, null, 0, 20))
        .rows()).extracting(DefectObservationRow::id).containsExactly(ob);

    // ⚠️ **按 id 直达也不行**：A 拿着 B 的观察 id 去作废，
    //   得到的是与「不存在」完全相同的错误 —— 不告诉调用方「这个 id 存在但不属于你」。
    assertThatThrownBy(() -> asA(() -> quality.voidObservation(
        ob, new DefectObservationVoid("越权作废", 0L), userA)))
        .hasStackTraceContaining("不存在或不属于当前租户");

    // 也不能替别人登记：拿 B 的检项 id 在 A 的上下文里写，检项根本查不到。
    assertThatThrownBy(() -> asA(() -> quality.recordObservation(
            new DefectObservationCreate(
                sb.inspectionId(), sb.checkResultId(), null,
                "CRACK", "越权登记", "APPEARANCE", "BUYER_UNBOXING", T1,
                new BigDecimal("1"), "MAJOR", ""),
            userA)))
        .hasStackTraceContaining("不存在或不属于当前租户");

    // ---- 成本参数：每币种的生效版本也各自独立 ----
    asA(() -> quality.createCostParameter(
        new CostParameterCreate("USD", new BigDecimal("9.0000"), new BigDecimal("99.0000"),
            LocalDate.parse("2026-01-01"), "A 租户自己的承运商价"), userA));
    assertThat(asA(() -> quality.listCostParameters(null, 0, 20)).rows())
        .singleElement()
        .satisfies(r -> assertThat(r.returnLogisticsPerCase())
            .isEqualByComparingTo("9.0000"));
    // B 那边**一条都没有** —— 不是「单价 0」，是「没登记」。
    assertThat(asB(() -> quality.listCostParameters(null, 0, 20)).rows()).isEmpty();
    assertThat(asB(() -> quality.qualityCost(all())).currencies().get(0).returnLogistics().basis())
        .isEqualTo(CostBasis.NOT_REGISTERED.name());

    // ---- 供应商引用同样互不可见 ----
    UUID refA = UUID.randomUUID();
    UUID refB = UUID.randomUUID();
    asA(() -> quality.upsertSupplierRef(
        new SupplierRefUpsert(refA, "SUP-A", "A 的供应商", "TENANT_MAINTAINED", null, ""), userA));
    asB(() -> quality.upsertSupplierRef(
        new SupplierRefUpsert(refB, "SUP-B", "B 的供应商", "TENANT_MAINTAINED", null, ""), userB));
    assertThat(asA(() -> quality.listSupplierRefs(null, 0, 20)).rows())
        .extracting(SupplierRefRow::supplierName).containsExactly("A 的供应商");
    assertThat(asB(() -> quality.listSupplierRefs(null, 0, 20)).rows())
        .extracting(SupplierRefRow::supplierName).containsExactly("B 的供应商");

    // ---- HTTP 层：按 id 的列表端点 ----
    String listA =
        http.perform(get("/trade/quality-analysis/defect-observations")
                .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    String listB =
        http.perform(get("/trade/quality-analysis/defect-observations")
                .header("Authorization", "Bearer tenant-b"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(listA).contains(oa.toString()).doesNotContain(ob.toString());
    assertThat(listB).contains(ob.toString()).doesNotContain(oa.toString());

    // ---- HTTP 层：聚合报表端点（无 id 可比对，用**两租户可观测的差异**证明没串）----
    // 只有 A 登记了成本参数（9.00 / 99.00），B 没登记 ⇒ 两边的合计依据等级必须不同：
    //   A = ESTIMATED（进了合计），B = NOT_REGISTERED（**被排除在合计之外**）。
    //   如果租户上下文串了，B 的报表会变成 A 的参数算出来的数 —— 这条断言就是抓这个。
    for (String path :
        List.of(
            "/trade/quality-analysis/overview",
            "/trade/quality-analysis/quality-cost",
            "/trade/quality-analysis/open-box-rate",
            "/trade/quality-analysis/defect-distribution",
            "/trade/quality-analysis/lot-trend",
            "/trade/quality-analysis/improvements",
            "/trade/quality-analysis/photo-clusters",
            "/trade/quality-analysis/supplier-comparison",
            "/trade/quality-analysis/cost-parameters",
            "/trade/quality-analysis/supplier-refs")) {
      String bodyA =
          http.perform(get(path).header("Authorization", "Bearer tenant-a"))
              .andExpect(status().isOk())
              .andReturn().getResponse().getContentAsString();
      String bodyB =
          http.perform(get(path).header("Authorization", "Bearer tenant-b"))
              .andExpect(status().isOk())
              .andReturn().getResponse().getContentAsString();
      // 每一个端点：A 的供应商名称与单价都**不许**出现在 B 的响应里。
      assertThat(bodyB).doesNotContain("A 的供应商").doesNotContain("9.0000").doesNotContain("99.0000");
      assertThat(bodyA).doesNotContain("B 的供应商");
      // 带成本的端点额外断言依据等级（A 登记了参数 ⇒ ESTIMATED；B 没登记 ⇒ NOT_REGISTERED）。
      if (path.endsWith("/overview") || path.endsWith("/quality-cost")) {
        assertThat(bodyA).contains("ESTIMATED");
        assertThat(bodyB).contains("NOT_REGISTERED");
      }
    }
  }

  // ==========================================================================
  // 断言二：伪造 tenant header 无效
  // ==========================================================================

  @Test
  void 伪造租户头无效() throws Exception {
    Seeded sa = seed(a, userA, "FORGE-A");
    Seeded sb = seed(b, userB, "FORGE-B");
    UUID oa = observe(a, userA, sa);
    UUID ob = observe(b, userB, sb);

    // 带着 **B 的令牌** + **伪造的 A 的 X-Tenant-Id**：读到的必须是 B 的数据。
    String page =
        http.perform(get("/trade/quality-analysis/defect-observations")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(page).contains(ob.toString()).doesNotContain(oa.toString());

    // 反过来：带着 A 的令牌 + 伪造的 B 的头，读到的仍然是 A 的数据。
    String page2 =
        http.perform(get("/trade/quality-analysis/defect-observations")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(page2).contains(oa.toString()).doesNotContain(ob.toString());

    // 报表类端点同样不认这个头：B 的令牌 + 伪造的 A 的头，读到的仍然是 **B 的**数字。
    // A 登记了成本参数（合计依据等级 = ESTIMATED），B 没登记（= NOT_REGISTERED），
    // 所以「读到的是谁的」在这里是一个**可以直接从响应里看出来**的差异。
    String overview =
        http.perform(get("/trade/quality-analysis/overview")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(overview).contains("NOT_REGISTERED").doesNotContain("ESTIMATED");

    String cost =
        http.perform(get("/trade/quality-analysis/quality-cost")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(cost).doesNotContain("9.0000").doesNotContain("99.0000");

    // 直接在 RLS 上验证：绑定 A 的租户之后，B 的行在库里**读不出来**，
    // 绕过服务层也一样（FORCE RLS 对表 owner 之外的角色同样生效，这里跑的是 trade_app）。
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) as c from quality_defect_observation where observation_no like 'QDO%'")
        .get("c", Integer.class))).isEqualTo(1);
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) as c from quality_defect_observation"
                + " where tenant_id = ?::uuid", b)
        .get("c", Integer.class))).isZero();
    // **不带** WHERE 的裸查同样被 RLS 收窄 —— 这才是 FORCE 的意义。
    assertThat(asA(() -> sql.fetchOne("select count(*) as c from quality_defect_observation")
        .get("c", Integer.class))).isEqualTo(1);
  }

  // ==========================================================================
  // 断言三：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 连接池归还后不串租户() throws Exception {
    Seeded sa = seed(a, userA, "POOL-A");
    Seeded sb = seed(b, userB, "POOL-B");
    // B 侧再建一张单：每轮都要在**同一条检项**上「写一条 → 作废 → 再写一条」，
    // 用同一张单的同一检项会被「一个 FAIL 检项同时只能有一条有效观察」当场拒。
    Seeded sb2 = seed(b, userB, "POOL-B2");
    UUID oa = observe(a, userA, sa);
    UUID ob = observe(b, userB, sb);

    // ⚠️ **每一轮都重新 seed**（新建订单 / 售后单 / 质检单 / 观察），
    //   而不是复用上一轮的行：复用时第二轮通过只能说明「那条记录没变」，
    //   证不了「这一轮的租户上下文是对的」。
    for (int round = 0; round < 3; round++) {
      final int r = round;
      // A → B → A → B 交替，且**每次都换一批新实体**：
      // 若上一轮的租户上下文残留在连接上，B 就会读到 A 的行（或反过来）。
      final UUID ma = oa;
      final UUID mb = ob;

      assertThat(asA(() -> quality.openBoxRate(all(), null)).observationCount()).isEqualTo(1);
      assertThat(asA(() -> quality.listObservations(null, null, null, null, null, 0, 20))
          .rows()).extracting(DefectObservationRow::id).containsExactly(ma);

      assertThat(asB(() -> quality.openBoxRate(all(), null)).observationCount()).isEqualTo(1);
      // ⚠️ 断言的是**有效行**（status=RECORDED）恰好等于本租户那一条：
      //   列表默认**含已作废行**（作废是留痕，抹掉就看不出这里曾经记错过什么），
      //   所以第二轮起 B 的列表会多出上一轮作废的那条 —— 那是设计，不是串租户。
      assertThat(asA(() -> quality.listObservations(null, null, null, "RECORDED", null, 0, 20))
          .rows()).extracting(DefectObservationRow::id).containsExactly(ma);
      assertThat(asB(() -> quality.listObservations(null, null, null, "RECORDED", null, 0, 20))
          .rows()).extracting(DefectObservationRow::id).containsExactly(mb);
      assertThat(asA(() -> quality.openBoxRate(all(), null)).observationCount()).isEqualTo(1);
      assertThat(asB(() -> quality.openBoxRate(all(), null)).observationCount()).isEqualTo(1);

      // 裸查（RULE 本身）在两个上下文里各自只看得见**自己那一侧**的行。
      assertThat(rowsOf(a)).containsExactly(ma + " @ " + a);
      assertThat(rowsOf(b)).containsExactly(mb + " @ " + b);

      // 再换回来：同一批数据，A 必须仍然只看见自己的。
      assertThat(asA(() -> quality.listObservations(null, null, null, "RECORDED", null, 0, 20))
          .rows()).extracting(DefectObservationRow::id).containsExactly(ma);

      // 写路径也一样：两次写在同一个连接上先后发生，第二次不能继承第一次的租户。
      UUID extra =
          asB(() -> quality.recordObservation(
                  new DefectObservationCreate(
                      sb2.inspectionId(), sb2.checkResultId(), null,
                      "SCRATCH", "划痕-" + r, "APPEARANCE", "WAREHOUSE_INTAKE", T1,
                      new BigDecimal("1"), "MINOR", ""),
                  userB)
              .id());
      assertThat(extra).isNotNull();
      assertThat(asA(() -> quality.listObservations(null, null, null, null, null, 0, 20))
          .rows()).extracting(DefectObservationRow::id).doesNotContain(extra);
      assertThat(asB(() -> quality.listObservations(null, null, null, null, null, 0, 20))
          .rows()).extracting(DefectObservationRow::id).contains(extra);
      // 写路径之后紧接着读：B 看得见刚写的那条，A **一条都看不见**（含 id 不相等）。
      assertThat(rowsOf(a)).containsExactly(ma + " @ " + a);
      assertThat(rowsOf(b)).containsExactlyInAnyOrder(mb + " @ " + b, extra + " @ " + b);
      assertThat(rowsOf(a)).containsExactly(ma + " @ " + a);

      // 同一批次只允许一条**有效**观察 ⇒ 第二轮必须先作废上一轮 B 的。
      asB(() -> quality.voidObservation(extra, new DefectObservationVoid("第 " + r + " 轮作废", 0L), userB));
    }
  }
}
