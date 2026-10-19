package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.trade.ProductIssueAttributionModels.AttributionConclude;
import com.starcon.trade.ProductIssueAttributionModels.AttributionCreate;
import com.starcon.trade.ReturnQualityModels.*;
import com.starcon.trade.SupplierClaimModels.*;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.2.3.3 供应商索赔。
 *
 * <p>跑的是蓝图职责句与那条加粗句：
 *
 * <blockquote>
 * 职责：对质量原因退货向供应商发起索赔：关联批次与责任判定、索赔金额、协商记录、赔付与冲减应付处理。
 * 输入：质检结论与责任判定；索赔金额；协商记录。
 * 输出：索赔单 + 冲减应付或退款记录。
 * 关系：上游 = 11.1.2.2、11.2.3.1；下游 = 12 财务（冲减应付）、6.5.1 对账。
 * <b>索赔不是自动到账</b>，系统只跟踪流程与金额，冲减应付需财务确认。
 * </blockquote>
 *
 * <p><b>本类最看重的十组断言</b>：
 *
 * <ol>
 *   <li><b>「没有财务确认就没有生效」真的成立</b>：登记赔付后生效金额**恒为 0**，
 *       索赔单仍是「已达成」；确认那一刻才变成登记金额并推到「已结算」。
 *       而且<b>绕过服务层直连 SQL</b> 塞一个生效金额也被 DB 拒。</li>
 *   <li><b>没有「直接置为已结算」的入口</b>：传输层与端点都没有这个动作。</li>
 *   <li><b>索赔基数不由调用方给</b>：报超过原值的金额被拒；按 1 件报 4 件的钱被拒。</li>
 *   <li><b>责任环节必须可索赔</b>：运输 / 仓储 / 买家因素 / 责任待定一律拒收。</li>
 *   <li><b>质检结论与责任判定必须同时有且指向同一批货</b>。</li>
 *   <li><b>协商是必经环节</b>：没有协商记录进不了协商中，没有「达成一致」记录置不了已达成，
 *       达成金额必须等于协商记录里的那个数。</li>
 *   <li><b>一条质检结论只能被索赔一次</b>。</li>
 *   <li><b>登记赔付幂等</b>：重复登记返回同一条分录，不新建第二条。</li>
 *   <li><b>终态不可逆</b>：已结算后改任何字段被拒（服务层与 DB 各拒一次）。</li>
 *   <li><b>时间写回零漂移 + 金额是 NUMERIC</b>：夹具刻意用 +08:00 非 UTC 偏移，
 *       金额断言到 4 位小数。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SupplierClaimIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID other = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms =
      List.of("trade.aftersales.read", "trade.aftersales.write", "trade.aftersales.finance");

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

  @Autowired SupplierClaimService claims;
  @Autowired ReturnQualityService quality;
  @Autowired ReturnReceiptService receipts;
  @Autowired RmaAuthorizationService rma;
  @Autowired AftersalesCaseService cases;
  @Autowired AftersalesCaseDetailService details;
  @Autowired ProductIssueAttributionService attributions;
  /**
   * 只桩掉 {@code require}（对 identity 的一次 HTTP 调用），{@code run} 仍是真实实现 ——
   * 也就是说 RLS 租户绑定与权限判据跑的都是生产代码路径。
   */
  @MockitoSpyBean AccessService access;
  /**
   * 批次档案在 warehouse 独立库（7.3.1.1）。这里桩掉**读端口**而不是数据本身：
   * 归因单仍然走真实的服务层建单路径（快照仍然被复制进归因单），
   * 只是不真的发一次跨服务 HTTP。真实跨服务读由 {@code ProductLotTracePortIntegrationTest} 覆盖。
   */
  @MockitoSpyBean ProductLotTracePort lots;

  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    pg.stop();
  }

  /** **非 UTC 偏移**夹具：只有这种时刻才能测出 Timestamp.valueOf 的 −28800s 静默偏移。 */
  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");

  /** 协商发生的时刻：刻意用 +08:00 偏移，断言写回值零漂移。 */
  static final OffsetDateTime T_NEG = OffsetDateTime.parse("2026-03-05T14:30:00+08:00");

  static OffsetDateTime due() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8)).plusDays(14);
  }

  @BeforeEach
  void stubIdentity() {
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
          "truncate supplier_claim_event, supplier_claim_settlement, supplier_claim_negotiation,"
              + " supplier_claim_item, supplier_claim,"
              + " quality_supplier_ref,"
              + " product_issue_photo_comparison, product_issue_photo, product_issue_lot_flag,"
              + " product_issue_event, product_issue_attribution,"
              + " return_quality_disposition_instruction, return_quality_check_result,"
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

  static {
    actors.put("Bearer tenant-main", actor(user, tenant));
    actors.put("Bearer tenant-other", actor(UUID.randomUUID(), other));
  }

  <T> T as(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-main"), "trade.aftersales.write", work);
  }

  <T> T asFinance(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-main"), "trade.aftersales.finance", work);
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 供应商引用镜像（V185）：供应商主数据在 catalog 独立库，这里引用的就是它的内部口径。 */
  static final UUID SUPPLIER_REF = UUID.randomUUID();

  void seedSupplierRef() {
    as(() -> {
      sql.execute(
          "insert into quality_supplier_ref(id,tenant_id,supplier_ref_id,supplier_code,supplier_name,"
              + "ref_source,source_reference,ref_status,note,created_by,created_at,updated_at)"
              + " values(?::uuid,?::uuid,?::uuid,?,?,'TENANT_MAINTAINED','TEST','ACTIVE','',?::uuid,"
              + "?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          tenant,
          SUPPLIER_REF,
          "SUP-001",
          "东莞某某电子厂",
          user,
          T1,
          T1);
      return null;
    });
  }

  record Seeded(UUID rmaId, UUID caseId, UUID orderLineId, UUID rmaLineId) {}

  record Posted(UUID receiptId, UUID receiptLineId, UUID postingId, UUID skuId, String skuCode) {}

  Seeded seedAuthorized(String orderNo) {
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
          UUID.randomUUID(), tenant, orderId, "买家-" + orderNo, "buyer-" + orderNo + "@example.com", T1);
      sql.execute(
          "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
              + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
              + " values(?,?::uuid,?::uuid,?,?,?,'INDIVIDUAL','',?::timestamptz)",
          UUID.randomUUID(), tenant, orderId, UUID.randomUUID(), "C-" + orderNo, "买家-" + orderNo, T1);
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

    UUID rmaId =
        as(() -> rma.create(
                new RmaAuthorizationModels.RmaCreate(
                    caseId, "REFUND_TO_BUYER", "TENANT_MAINTAINED", null,
                    "退货仓-" + orderNo, "+1-213-555-0199", "rma-" + orderNo + "@example.com",
                    "US", "CA", "ONTARIO", "", "M5V 2T6", "88 Yonge St", "3 楼", null, null),
                user)
            .head()
            .id());
    UUID rmaLineId = as(() -> rma.detail(rmaId)).lines().get(0).id();
    as(() -> rma.decide(
        rmaId,
        new RmaAuthorizationModels.RmaDecide(
            "PARTIALLY_APPROVED", due(), "外包装压损，只退部分",
            List.of(new RmaAuthorizationModels.RmaLineDecision(rmaLineId, new BigDecimal("3"), null)),
            0L),
        user));
    return new Seeded(rmaId, caseId, lineId, rmaLineId);
  }

  /** 登记并入库一张退货实收（两个判定维度可配）→ 返回该行的待检入库分录。 */
  Posted postReceipt(String orderNo, BigDecimal qty, String packaging, String goods) {
    return postReceipt(seedAuthorized(orderNo), orderNo, qty, packaging, goods);
  }

  /** 登记并入库一张退货实收（复用已建好的售后单）。 */
  Posted postReceipt(Seeded s, String orderNo, BigDecimal qty, String packaging, String goods) {
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

  /** 建一个可用的质检模板并开一张**已判定**的质检单。 */
  UUID concludedInspection(Posted p, String tag) {
    UUID categoryId =
        as(() -> quality.createCategory(
            new QualityCategoryCreate("CAT-" + tag, "小家电", ""), user).id());
    as(() -> {
      quality.mapSku(categoryId, new QualitySkuMappingCreate(p.skuId(), p.skuCode()), user);
      return null;
    });
    var template =
        as(() -> quality.createTemplate(
            new QualityTemplateCreate(
                categoryId, "小家电标准质检", 1,
                new BigDecimal("1.000000"), new BigDecimal("0.100000"),
                new BigDecimal("1.000000"), new BigDecimal("0.500000"), new BigDecimal("0.050000"),
                "",
                List.of(
                    new QualityTemplateItemDraft("APPEARANCE-1", "外观", "APPEARANCE",
                        new BigDecimal("0.200000"), true, "", ""),
                    new QualityTemplateItemDraft("FUNCTION-1", "功能", "FUNCTION",
                        new BigDecimal("0.300000"), true, "", ""))),
            user));
    as(() -> quality.activateTemplate(template.template().id(), user));

    UUID inspectionId =
        as(() -> quality.createInspection(new QualityInspectionCreate(p.postingId(), null), user)
            .head().id());
    List<QualityCheckResultDraft> results =
        as(() -> quality.templateDetail(template.template().id())).items().stream()
            .map(i -> new QualityCheckResultDraft(i.id(), "FAIL", "检出问题", null)).toList();
    as(() -> quality.recordResults(inspectionId,
        new QualityInspectionUpdate(results,
            as(() -> quality.inspectionDetail(inspectionId)).head().version()), user));
    as(() -> quality.conclude(inspectionId,
        new QualityInspectionConclude("DEFECTIVE", "功能失效，判残次",
            as(() -> quality.inspectionDetail(inspectionId)).head().version()), user));
    return inspectionId;
  }

  /** 建一条已归因的责任判定。 */
  UUID attributed(UUID caseId, UUID inspectionId, String stage, UUID lotId) {
    org.mockito.Mockito
        .doAnswer(
            inv -> {
              UUID id = inv.getArgument(0);
              return new ProductLotTracePort.LotSnapshot(
                  id, "LOT-" + tag(), null, SUPPLIER_REF, "东莞某某电子厂");
            })
        .when(lots)
        .snapshot(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());

    UUID attributionId =
        as(() -> attributions.create(
                new AttributionCreate(caseId, inspectionId, null, lotId, "LOT-1", null,
                    SUPPLIER_REF, null, "SKU-1", "商品", new BigDecimal("2.000000")),
                user, "Bearer tenant-main"))
            .row().id();
    as(() -> attributions.attribute(attributionId,
        new AttributionConclude(stage, "归因理由", "改进建议"), user));
    return attributionId;
  }

  static int seq = 0;

  static synchronized String tag() {
    return "T" + (++seq);
  }

  /** 一条完整的可索赔链路：已判定质检单 + 已归因责任判定。 */
  record Chain(UUID caseId, UUID inspectionId, UUID attributionId, String skuCode) {}

  Chain chain(String tag, String stage) {
    // ⚠️ 售后单与实收**必须**是同一张：归因单的归因数量受该售后单的实收数量约束。
    Seeded s = seedAuthorized(tag);
    Posted p = postReceipt(s, tag, new BigDecimal("2"), "DAMAGED", "OPENED_UNDAMAGED");
    UUID inspectionId = concludedInspection(p, tag);
    UUID attributionId = attributed(s.caseId(), inspectionId, stage, UUID.randomUUID());
    return new Chain(s.caseId(), inspectionId, attributionId, p.skuCode());
  }

  /** 建一张索赔单（草稿）。 */
  SupplierClaimDetail newClaim(String tag) {
    return as(() -> claims.createClaim(
        new SupplierClaimCreate(SUPPLIER_REF, "QUALITY_DEFECT", "批次不良：" + tag, "USD"), user));
  }

  /** 一张走到「已达成」的索赔单（含一条明细）。 */
  SupplierClaimDetail agreedClaim(String tag, String stage) {
    Chain c = chain(tag, stage);
    SupplierClaimDetail claim = newClaim(tag);
    as(() -> claims.addItem(claim.head().id(),
        new SupplierClaimItemCreate(c.inspectionId(), c.attributionId(),
            new BigDecimal("2"), new BigDecimal("8.0000")), user));
    as(() -> claims.submit(claim.head().id(), new SupplierClaimSubmit("", 0L), user));
    as(() -> claims.recordNegotiation(claim.head().id(),
        new SupplierNegotiationCreate("SUPPLIER_RESPONSE", "SUPPLIER",
            new BigDecimal("8.0000"), null, "供应商口头同意赔 8", T_NEG), user));
    as(() -> claims.startNegotiation(claim.head().id(), 1L, user));
    as(() -> claims.recordNegotiation(claim.head().id(),
        new SupplierNegotiationCreate("AGREEMENT", "SUPPLIER",
            new BigDecimal("8.0000"), new BigDecimal("8.0000"), "双方确认赔 8", T_NEG), user));
    as(() -> claims.agree(claim.head().id(),
        new SupplierClaimAgree(as(() -> claims.claimDetail(claim.head().id())).head().version()), user));
    return as(() -> claims.claimDetail(claim.head().id()));
  }

  // ==========================================================================
  // 一、建单：恒为草稿 + 快照由 DB 填
  // ==========================================================================

  @Test
  void 建单恒为草稿且供应商快照由数据库填() {
    seedSupplierRef();

    var claim = newClaim("BUILD");
    assertThat(claim.head().claimStatus()).isEqualTo("DRAFT");
    assertThat(claim.head().claimNo()).startsWith("SCL");
    // 快照来自 quality_supplier_ref，不是调用方传的。
    assertThat(claim.head().supplierCode()).isEqualTo("SUP-001");
    assertThat(claim.head().supplierName()).isEqualTo("东莞某某电子厂");
    assertThat(claim.head().claimedAmount()).isEqualByComparingTo("0.0000");
    assertThat(claim.head().offsetStatus()).isEqualTo("NOT_APPLIED");
    assertThat(claim.head().appliedOffsetAmount()).isEqualByComparingTo("0.0000");

    // 理由与币种必填。
    assertThatThrownBy(() -> as(() -> claims.createClaim(
            new SupplierClaimCreate(SUPPLIER_REF, "QUALITY_DEFECT", "", "USD"), user)))
        .hasStackTraceContaining("必须写索赔理由");
    assertThatThrownBy(() -> as(() -> claims.createClaim(
            new SupplierClaimCreate(SUPPLIER_REF, "QUALITY_DEFECT", "理由", null), user)))
        .hasStackTraceContaining("必须给出索赔币种");
    assertThatThrownBy(() -> as(() -> claims.createClaim(
            new SupplierClaimCreate(null, "QUALITY_DEFECT", "理由", "USD"), user)))
        .hasStackTraceContaining("必须给出供应商");
  }

  @Test
  void 供应商引用不存在或已停用时被拒() {
    as(() -> {
      sql.execute(
          "insert into quality_supplier_ref(id,tenant_id,supplier_ref_id,supplier_code,supplier_name,"
              + "ref_source,source_reference,ref_status,note,created_by,created_at,updated_at)"
              + " values(?::uuid,?::uuid,?::uuid,'SUP-OFF','停用供应商','TENANT_MAINTAINED','TEST',"
              + "'DISABLED','',?::uuid,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(), tenant, UUID.randomUUID(), user, T1, T1);
      return null;
    });
    UUID disabled =
        as(() -> sql.fetchOne(
                "select supplier_ref_id from quality_supplier_ref"
                    + " where tenant_id = ?::uuid and supplier_code = 'SUP-OFF'", tenant)
            .get(0, UUID.class));

    assertThatThrownBy(() -> as(() -> claims.createClaim(
            new SupplierClaimCreate(disabled, "QUALITY_DEFECT", "理由", "USD"), user)))
        .hasStackTraceContaining("已停用");
    assertThatThrownBy(() -> as(() -> claims.createClaim(
            new SupplierClaimCreate(UUID.randomUUID(), "QUALITY_DEFECT", "理由", "USD"), user)))
        .hasStackTraceContaining("供应商引用不存在");
  }

  // ==========================================================================
  // 二、硬规则 1：必须同时有质检结论与责任判定，且指向同一批货
  // ==========================================================================

  @Test
  void 责任环节不可索赔时被拒() {
    seedSupplierRef();
    for (String stage : List.of("TRANSPORT", "WAREHOUSE", "BUYER_FACTOR", "UNDETERMINED")) {
      Chain c = chain("STAGE-" + stage, stage);
      SupplierClaimDetail claim = newClaim("STAGE-" + stage);
      assertThatThrownBy(() -> as(() -> claims.addItem(claim.head().id(),
              new SupplierClaimItemCreate(c.inspectionId(), c.attributionId(),
                  new BigDecimal("2"), new BigDecimal("8.0000")), user)))
          .as("环节 " + stage + " 不该能向供应商索赔")
          .hasStackTraceContaining("不能向供应商索赔");
    }
  }

  @Test
  void 质检结论未判定时不能索赔() {
    seedSupplierRef();
    Seeded s = seedAuthorized("UNCONCLUDED");
    Posted p = postReceipt("UNCONCLUDED-2", new BigDecimal("2"), "DAMAGED", "OPENED_UNDAMAGED");
    UUID categoryId =
        as(() -> quality.createCategory(
            new QualityCategoryCreate("CAT-UN", "小家电", ""), user).id());
    as(() -> {
      quality.mapSku(categoryId, new QualitySkuMappingCreate(p.skuId(), p.skuCode()), user);
      return null;
    });
    var template =
        as(() -> quality.createTemplate(
            new QualityTemplateCreate(categoryId, "模板", 1,
                new BigDecimal("1.000000"), new BigDecimal("0.100000"),
                new BigDecimal("1.000000"), new BigDecimal("0.500000"), new BigDecimal("0.050000"),
                "",
                List.of(new QualityTemplateItemDraft("APPEARANCE-1", "外观", "APPEARANCE",
                    new BigDecimal("0.200000"), true, "", ""))),
            user));
    as(() -> quality.activateTemplate(template.template().id(), user));
    UUID inspectionId =
        as(() -> quality.createInspection(new QualityInspectionCreate(p.postingId(), null), user)
            .head().id());

    SupplierClaimDetail claim = newClaim("UNCONCLUDED");
    assertThatThrownBy(() -> as(() -> claims.addItem(claim.head().id(),
            new SupplierClaimItemCreate(inspectionId, UUID.randomUUID(),
                new BigDecimal("2"), new BigDecimal("8.0000")), user)))
        .hasStackTraceContaining("还没有判定");
  }

  @Test
  void 质检结论与责任判定不是同一批货时被拒() {
    seedSupplierRef();
    Chain c1 = chain("PAIR-1", "PRODUCTION");
    Chain c2 = chain("PAIR-2", "PRODUCTION");
    SupplierClaimDetail claim = newClaim("PAIR");

    assertThatThrownBy(() -> as(() -> claims.addItem(claim.head().id(),
            new SupplierClaimItemCreate(c1.inspectionId(), c2.attributionId(),
                new BigDecimal("2"), new BigDecimal("8.0000")), user)))
        .hasStackTraceContaining("不是同一批货");
  }

  @Test
  void 归因没有挂质检结论时不能索赔() {
    seedSupplierRef();
    Chain c = chain("NOINSP", "DESCRIPTION");
    // 把归因的 inspection_id 摘掉（归因单是草稿时没有质检结论的那种情形）。
    as(() -> {
      sql.execute(
          "update product_issue_attribution set inspection_id = null"
              + " where tenant_id = ?::uuid and id = ?::uuid", tenant, c.attributionId());
      return null;
    });
    SupplierClaimDetail claim = newClaim("NOINSP");
    assertThatThrownBy(() -> as(() -> claims.addItem(claim.head().id(),
            new SupplierClaimItemCreate(c.inspectionId(), c.attributionId(),
                new BigDecimal("2"), new BigDecimal("8.0000")), user)))
        .hasStackTraceContaining("没有挂质检结论");
  }

  // ==========================================================================
  // 三、硬规则 2：索赔金额不得超过基数
  // ==========================================================================

  @Test
  void 索赔金额不得超过质检原值且按数量折算() {
    seedSupplierRef();
    Chain c = chain("AMOUNT", "PRODUCTION");
    SupplierClaimDetail claim = newClaim("AMOUNT");

    // 原值 = 单价 5.0000 × 实收 2 = 10.0000。
    // 报 12 元（超过原值）—— 被拒。
    assertThatThrownBy(() -> as(() -> claims.addItem(claim.head().id(),
            new SupplierClaimItemCreate(c.inspectionId(), c.attributionId(),
                new BigDecimal("2"), new BigDecimal("12.0000")), user)))
        .hasStackTraceContaining("不得超过本行上限");

    // 只赔 1 件却按 2 件的价（10 元）报 —— 被拒（上限按数量折算 = 5 元）。
    assertThatThrownBy(() -> as(() -> claims.addItem(claim.head().id(),
            new SupplierClaimItemCreate(c.inspectionId(), c.attributionId(),
                new BigDecimal("1"), new BigDecimal("10.0000")), user)))
        .hasStackTraceContaining("不得超过本行上限");

    // 1 件报 5 元 —— 成立，且上限确实按数量折算成了 5.0000。
    var withItem = as(() -> claims.addItem(claim.head().id(),
        new SupplierClaimItemCreate(c.inspectionId(), c.attributionId(),
            new BigDecimal("1"), new BigDecimal("5.0000")), user));
    var item = withItem.items().get(0);
    assertThat(item.claimableBaseAmount()).isEqualByComparingTo("10.0000");
    assertThat(item.maxClaimAmount()).isEqualByComparingTo("5.0000");
    assertThat(item.claimAmount()).isEqualByComparingTo("5.0000");
    assertThat(item.claimCurrency()).isEqualTo("USD");
    // 头表金额由 DB 触发器重算（不是服务层自己加的）。
    assertThat(withItem.head().claimedAmount()).isEqualByComparingTo("5.0000");
    assertThat(withItem.head().claimableBaseAmount()).isEqualByComparingTo("10.0000");

    // 数量超过实检数量 —— 被拒。
    // ⚠️ 刻意用**另一条**还没被索赔过的质检结论：同一条再报一次会先被
    //   「已经被索赔过了」拦下（那条检查在前），看不到数量上限这条规则。
    Chain c2 = chain("AMOUNT-2", "PRODUCTION");
    assertThatThrownBy(() -> as(() -> claims.addItem(claim.head().id(),
            new SupplierClaimItemCreate(c2.inspectionId(), c2.attributionId(),
                new BigDecimal("3"), new BigDecimal("1.0000")), user)))
        .hasStackTraceContaining("不得超过实检数量");
  }

  @Test
  void 索赔金额是四位小数的数值而不是浮点() {
    seedSupplierRef();
    Chain c = chain("DECIMAL", "PRODUCTION");
    SupplierClaimDetail claim = newClaim("DECIMAL");
    var withItem = as(() -> claims.addItem(claim.head().id(),
        new SupplierClaimItemCreate(c.inspectionId(), c.attributionId(),
            new BigDecimal("2"), new BigDecimal("7.1234")), user));

    // 写回的值逐位相等：浮点会在这里露出 7.1233999999999995 这种尾巴。
    assertThat(withItem.items().get(0).claimAmount()).isEqualTo(new BigDecimal("7.1234"));
    assertThat(withItem.head().claimedAmount()).isEqualTo(new BigDecimal("7.1234"));

    // 库里也是 NUMERIC，不是 double。
    String columnType =
        as(() -> sql.fetchOne(
                "select data_type from information_schema.columns"
                    + " where table_name = 'supplier_claim_item' and column_name = 'claim_amount'")
            .get(0, String.class));
    assertThat(columnType).isEqualTo("numeric");
  }

  @Test
  void 一条质检结论只能被索赔一次() {
    seedSupplierRef();
    Chain c = chain("ONCE", "PRODUCTION");
    SupplierClaimDetail claim = newClaim("ONCE");
    as(() -> claims.addItem(claim.head().id(),
        new SupplierClaimItemCreate(c.inspectionId(), c.attributionId(),
            new BigDecimal("2"), new BigDecimal("8.0000")), user));

    // 同一张单里再加一次 —— 被拒。
    assertThatThrownBy(() -> as(() -> claims.addItem(claim.head().id(),
            new SupplierClaimItemCreate(c.inspectionId(), c.attributionId(),
                new BigDecimal("2"), new BigDecimal("1.0000")), user)))
        .hasStackTraceContaining("已经被索赔过了");

    // 另一张单里再加一次 —— 同样被拒（DB 上还有唯一索引兜底）。
    SupplierClaimDetail other2 = newClaim("ONCE-2");
    assertThatThrownBy(() -> as(() -> claims.addItem(other2.head().id(),
            new SupplierClaimItemCreate(c.inspectionId(), c.attributionId(),
                new BigDecimal("2"), new BigDecimal("1.0000")), user)))
        .hasStackTraceContaining("已经被索赔过了");
  }

  // ==========================================================================
  // 四、状态机：协商是必经环节
  // ==========================================================================

  @Test
  void 提交必须至少一条明细且提交后不能再改明细() {
    seedSupplierRef();
    SupplierClaimDetail empty = newClaim("EMPTY");
    assertThatThrownBy(() -> as(() -> claims.submit(empty.head().id(),
            new SupplierClaimSubmit("", 0L), user)))
        .hasStackTraceContaining("不能提交");

    Chain c = chain("SUBMIT", "PRODUCTION");
    SupplierClaimDetail claim = newClaim("SUBMIT");
    as(() -> claims.addItem(claim.head().id(),
        new SupplierClaimItemCreate(c.inspectionId(), c.attributionId(),
            new BigDecimal("2"), new BigDecimal("8.0000")), user));

    var submitted = as(() -> claims.submit(claim.head().id(), new SupplierClaimSubmit("", 0L), user));
    assertThat(submitted.head().claimStatus()).isEqualTo("SUBMITTED");
    assertThat(submitted.head().submittedAt()).isNotNull();

    // 提交后不能再加明细。
    assertThatThrownBy(() -> as(() -> claims.addItem(claim.head().id(),
            new SupplierClaimItemCreate(c.inspectionId(), c.attributionId(),
                new BigDecimal("1"), new BigDecimal("1.0000")), user)))
        .hasStackTraceContaining("不能再改明细");
  }

  @Test
  void 协商是必经环节且达成金额只有一个出处() {
    seedSupplierRef();
    Chain c = chain("NEGO", "PRODUCTION");
    SupplierClaimDetail claim = newClaim("NEGO");
    as(() -> claims.addItem(claim.head().id(),
        new SupplierClaimItemCreate(c.inspectionId(), c.attributionId(),
            new BigDecimal("2"), new BigDecimal("8.0000")), user));
    as(() -> claims.submit(claim.head().id(), new SupplierClaimSubmit("", 0L), user));

    // 没有协商记录进不了协商中。
    assertThatThrownBy(() -> as(() -> claims.startNegotiation(claim.head().id(), 1L, user)))
        .hasStackTraceContaining("还没有协商记录");

    as(() -> claims.recordNegotiation(claim.head().id(),
        new SupplierNegotiationCreate("SUPPLIER_RESPONSE", "SUPPLIER",
            new BigDecimal("8.0000"), null, "供应商说最多赔 5", T_NEG), user));
    var negotiating = as(() -> claims.startNegotiation(claim.head().id(), 1L, user));
    assertThat(negotiating.head().claimStatus()).isEqualTo("NEGOTIATING");

    // 没有「达成一致」的记录置不了已达成。
    assertThatThrownBy(() -> as(() -> claims.agree(claim.head().id(),
            new SupplierClaimAgree(as(() -> claims.claimDetail(claim.head().id())).head().version()),
            user)))
        .hasStackTraceContaining("还没有「达成一致」的协商记录");

    // 协商记录的序号由 DB 排（不认调用方填的）。
    var withNeg = as(() -> claims.recordNegotiation(claim.head().id(),
        new SupplierNegotiationCreate("AGREEMENT", "SUPPLIER",
            new BigDecimal("5.0000"), new BigDecimal("5.0000"), "那就赔 5", T_NEG), user));
    assertThat(withNeg.negotiations()).hasSize(2);
    assertThat(withNeg.negotiations().get(0).negotiationSeq()).isEqualTo(1);
    assertThat(withNeg.negotiations().get(1).negotiationSeq()).isEqualTo(2);

    // 达成金额取自协商记录（5 而不是 8）。
    var agreed = as(() -> claims.agree(claim.head().id(),
        new SupplierClaimAgree(as(() -> claims.claimDetail(claim.head().id())).head().version()), user));
    assertThat(agreed.head().claimStatus()).isEqualTo("AGREED");
    assertThat(agreed.head().agreedAmount()).isEqualTo(new BigDecimal("5.0000"));

    // 协商记录只有「达成一致」才带达成金额。
    assertThatThrownBy(() -> as(() -> claims.recordNegotiation(claim.head().id(),
            new SupplierNegotiationCreate("SUPPLIER_RESPONSE", "SUPPLIER",
                new BigDecimal("1.0000"), new BigDecimal("1.0000"), "带达成金额的答复", T_NEG), user)))
        .hasStackTraceContaining("只有「达成一致」");
  }

  @Test
  void 协商记录的时刻写回零漂移() {
    seedSupplierRef();
    Chain c = chain("TIME", "PRODUCTION");
    SupplierClaimDetail claim = newClaim("TIME");
    as(() -> claims.addItem(claim.head().id(),
        new SupplierClaimItemCreate(c.inspectionId(), c.attributionId(),
            new BigDecimal("2"), new BigDecimal("8.0000")), user));
    as(() -> claims.submit(claim.head().id(), new SupplierClaimSubmit("", 0L), user));

    var withNeg = as(() -> claims.recordNegotiation(claim.head().id(),
        new SupplierNegotiationCreate("SUPPLIER_RESPONSE", "SUPPLIER",
            new BigDecimal("8.0000"), null, "供应商答复", T_NEG), user));
    var neg = withNeg.negotiations().get(0);
    // 写回值必须与夹具**逐刻相等**（用 Timestamp.valueOf 会静默偏 −28800s）。
    assertThat(neg.occurredAt()).isEqualTo(T_NEG);
    assertThat(neg.occurredAt().getOffset()).isEqualTo(java.time.ZoneOffset.ofHours(8));

    // 库里的值也是同一刻。
    OffsetDateTime stored =
        as(() -> sql.fetchOne(
                "select occurred_at from supplier_claim_negotiation"
                    + " where tenant_id = ?::uuid and id = ?::uuid", tenant, neg.id())
            .get("occurred_at", OffsetDateTime.class));
    assertThat(stored.toInstant()).isEqualTo(T_NEG.toInstant());
  }

  // ==========================================================================
  // 五、硬规则核心：「没有财务确认就没有生效」
  // ==========================================================================

  @Test
  void 登记赔付不生效且财务确认后才生效() {
    seedSupplierRef();
    SupplierClaimDetail agreed = agreedClaim("SETTLE", "PRODUCTION");
    assertThat(agreed.head().claimStatus()).isEqualTo("AGREED");
    assertThat(agreed.head().offsetStatus()).isEqualTo("NOT_APPLIED");
    assertThat(agreed.head().appliedOffsetAmount()).isEqualByComparingTo("0.0000");

    // 登记赔付（走写权限）。
    var registered = as(() -> claims.registerSettlement(agreed.head().id(),
        new SupplierSettlementCreate("OFFSET_PAYABLE", new BigDecimal("8.0000")), user));
    assertThat(registered.settlements()).hasSize(1);
    var settlement = registered.settlements().get(0);
    assertThat(settlement.settlementStatus()).isEqualTo("PENDING_FINANCE_CONFIRM");
    // ⚠️ 登记了不等于生效：生效金额恒为 0。
    assertThat(settlement.effectiveAmount()).isEqualByComparingTo("0.0000");
    assertThat(settlement.requestedAmount()).isEqualByComparingTo("8.0000");
    assertThat(registered.head().offsetStatus()).isEqualTo("PENDING_FINANCE_CONFIRM");
    assertThat(registered.head().appliedOffsetAmount()).isEqualByComparingTo("0.0000");
    // 单子还没结算。
    assertThat(registered.head().claimStatus()).isEqualTo("AGREED");

    // 财务确认（走**独立**的财务权限）。
    var confirmed = asFinance(() -> claims.confirmSettlement(agreed.head().id(), settlement.id(),
        new SupplierSettlementConfirm("AP-PAY-2026-0312"), user));
    var confirmedSettlement = confirmed.settlements().get(0);
    assertThat(confirmedSettlement.settlementStatus()).isEqualTo("CONFIRMED");
    assertThat(confirmedSettlement.effectiveAmount()).isEqualByComparingTo("8.0000");
    assertThat(confirmedSettlement.financeReference()).isEqualTo("AP-PAY-2026-0312");
    assertThat(confirmedSettlement.confirmedAt()).isNotNull();
    // 头表的冲减口径被重算，单子进入终态。
    assertThat(confirmed.head().offsetStatus()).isEqualTo("APPLIED");
    assertThat(confirmed.head().appliedOffsetAmount()).isEqualByComparingTo("8.0000");
    assertThat(confirmed.head().claimStatus()).isEqualTo("SETTLED");
  }

  @Test
  void 财务确认必须回填财务单据号() {
    seedSupplierRef();
    SupplierClaimDetail agreed = agreedClaim("FINREF", "PRODUCTION");
    var registered = as(() -> claims.registerSettlement(agreed.head().id(),
        new SupplierSettlementCreate("OFFSET_PAYABLE", new BigDecimal("8.0000")), user));
    UUID settlementId = registered.settlements().get(0).id();

    assertThatThrownBy(() -> asFinance(() -> claims.confirmSettlement(agreed.head().id(),
            settlementId, new SupplierSettlementConfirm("  "), user)))
        .hasStackTraceContaining("必须回填财务单据号");
  }

  @Test
  void 绕过服务层直连sql写生效金额也被数据库拒() {
    seedSupplierRef();
    SupplierClaimDetail agreed = agreedClaim("BYPASS", "PRODUCTION");
    as(() -> claims.registerSettlement(agreed.head().id(),
        new SupplierSettlementCreate("OFFSET_PAYABLE", new BigDecimal("8.0000")), user));

    // ⚠️ 绕过服务层直连 SQL 把生效金额改成 8 —— 表级 CHECK 必须当场拒。
    assertThatThrownBy(() -> as(() -> sql.execute(
            "update supplier_claim_settlement set effective_amount = 8.0000"
                + " where tenant_id = ?::uuid and claim_id = ?::uuid", tenant, agreed.head().id())))
        .hasStackTraceContaining("effective_amount");
  }

  @Test
  void 绕过服务层直连sql把索赔单置为已结算也被拒() {
    seedSupplierRef();
    SupplierClaimDetail agreed = agreedClaim("BYPASS2", "PRODUCTION");
    // 没有已确认的赔付分录，直接推成 SETTLED —— 守卫③ 必须拒。
    assertThatThrownBy(() -> as(() -> sql.execute(
            "update supplier_claim set claim_status = 'SETTLED'"
                + " where tenant_id = ?::uuid and id = ?::uuid", tenant, agreed.head().id())))
        .hasStackTraceContaining("没有财务确认过的赔付分录");
  }

  @Test
  void 登记赔付幂等且不得超过达成金额() {
    seedSupplierRef();
    SupplierClaimDetail agreed = agreedClaim("IDEM", "PRODUCTION");

    // 不得超过达成金额（8）。
    assertThatThrownBy(() -> as(() -> claims.registerSettlement(agreed.head().id(),
            new SupplierSettlementCreate("OFFSET_PAYABLE", new BigDecimal("9.0000")), user)))
        .hasStackTraceContaining("不得超过达成金额");

    var first = as(() -> claims.registerSettlement(agreed.head().id(),
        new SupplierSettlementCreate("OFFSET_PAYABLE", new BigDecimal("8.0000")), user));
    assertThat(first.settlements()).hasSize(1);

    // 重复登记 —— 返回同一条，不新建第二条（重复冲减是资损事故）。
    var second = as(() -> claims.registerSettlement(agreed.head().id(),
        new SupplierSettlementCreate("OFFSET_PAYABLE", new BigDecimal("8.0000")), user));
    assertThat(second.settlements()).hasSize(1);
    assertThat(second.settlements().get(0).id()).isEqualTo(first.settlements().get(0).id());
  }

  @Test
  void 财务驳回赔付后生效金额仍为零() {
    seedSupplierRef();
    SupplierClaimDetail agreed = agreedClaim("FINREJ", "PRODUCTION");
    var registered = as(() -> claims.registerSettlement(agreed.head().id(),
        new SupplierSettlementCreate("SUPPLIER_REFUND", new BigDecimal("8.0000")), user));
    UUID settlementId = registered.settlements().get(0).id();

    var rejected = asFinance(() -> claims.rejectSettlement(agreed.head().id(), settlementId,
        new SupplierSettlementReject("供应商称货已过保"), user));
    assertThat(rejected.settlements().get(0).settlementStatus()).isEqualTo("REJECTED");
    assertThat(rejected.settlements().get(0).effectiveAmount()).isEqualByComparingTo("0.0000");
    assertThat(rejected.head().offsetStatus()).isEqualTo("FINANCE_REJECTED");
    assertThat(rejected.head().appliedOffsetAmount()).isEqualByComparingTo("0.0000");
    // 单子没有结算（冲减没生效）。
    assertThat(rejected.head().claimStatus()).isEqualTo("AGREED");
  }

  @Test
  void 只有已达成一致的索赔单才能登记赔付() {
    seedSupplierRef();
    Chain c = chain("EARLY", "PRODUCTION");
    SupplierClaimDetail claim = newClaim("EARLY");
    as(() -> claims.addItem(claim.head().id(),
        new SupplierClaimItemCreate(c.inspectionId(), c.attributionId(),
            new BigDecimal("2"), new BigDecimal("8.0000")), user));

    assertThatThrownBy(() -> as(() -> claims.registerSettlement(claim.head().id(),
            new SupplierSettlementCreate("OFFSET_PAYABLE", new BigDecimal("8.0000")), user)))
        .hasStackTraceContaining("只有已达成一致的索赔单才能登记赔付");
  }

  // ==========================================================================
  // 六、终态不可逆
  // ==========================================================================

  @Test
  void 终态不可逆() {
    seedSupplierRef();
    SupplierClaimDetail agreed = agreedClaim("FROZEN", "PRODUCTION");
    var registered = as(() -> claims.registerSettlement(agreed.head().id(),
        new SupplierSettlementCreate("OFFSET_PAYABLE", new BigDecimal("8.0000")), user));
    UUID settlementId = registered.settlements().get(0).id();
    var settled = asFinance(() -> claims.confirmSettlement(agreed.head().id(), settlementId,
        new SupplierSettlementConfirm("AP-1"), user));
    assertThat(settled.head().claimStatus()).isEqualTo("SETTLED");

    // 服务层改不动（状态前置校验：已结算不允许撤回）。
    assertThatThrownBy(() -> as(() -> claims.withdrawClaim(agreed.head().id(),
            new SupplierClaimWithdraw("反悔", settled.head().version()), user)))
        .hasStackTraceContaining("不能撤回");
    // DB 层也改不动（整行冻结）。
    assertThatThrownBy(() -> as(() -> sql.execute(
            "update supplier_claim set claimed_amount = 1.0000"
                + " where tenant_id = ?::uuid and id = ?::uuid", tenant, agreed.head().id())))
        .hasStackTraceContaining("终态");
    // 已确认的赔付分录同样不可逆。
    assertThatThrownBy(() -> as(() -> sql.execute(
            "update supplier_claim_settlement set settlement_status = 'REJECTED'"
                + " where tenant_id = ?::uuid and id = ?::uuid", tenant, settlementId)))
        .hasStackTraceContaining("终态");
  }

  // ==========================================================================
  // 七、列表、候选与能力声明
  // ==========================================================================

  @Test
  void 列表多维筛选是真筛选且汇总不随分页变小() {
    seedSupplierRef();
    Chain c = chain("LIST", "PRODUCTION");
    SupplierClaimDetail claim = newClaim("LIST");
    as(() -> claims.addItem(claim.head().id(),
        new SupplierClaimItemCreate(c.inspectionId(), c.attributionId(),
            new BigDecimal("2"), new BigDecimal("8.0000")), user));
    as(() -> claims.submit(claim.head().id(), new SupplierClaimSubmit("", 0L), user));

    var page = as(() -> claims.listClaims(
        new SupplierClaimListQuery(0, 20, null, null, null, null, null, null, null, null, null, null)));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.rows()).hasSize(1);
    assertThat(page.summary().totalClaimedAmount()).isEqualByComparingTo("8.0000");
    assertThat(page.summary().submitted()).isEqualTo(1);

    // 按状态筛。
    var drafts = as(() -> claims.listClaims(
        new SupplierClaimListQuery(0, 20, "DRAFT", null, null, null, null, null, null, null, null, null)));
    assertThat(drafts.total()).isZero();

    // 按责任环节筛（命中）。
    var byStage = as(() -> claims.listClaims(
        new SupplierClaimListQuery(0, 20, null, null, "PRODUCTION", null, null, null, null, null, null, null)));
    assertThat(byStage.total()).isEqualTo(1);

    // 按**不可索赔**的环节筛 —— 筛不出来（而不是筛出来再显示一堆不能索赔的行）。
    var byBadStage = as(() -> claims.listClaims(
        new SupplierClaimListQuery(0, 20, null, null, "TRANSPORT", null, null, null, null, null, null, null)));
    assertThat(byBadStage.total()).isZero();

    // 按金额区间筛。
    var byAmount = as(() -> claims.listClaims(
        new SupplierClaimListQuery(0, 20, null, null, null, null,
            new BigDecimal("1.0000"), new BigDecimal("5.0000"), null, null, null, null)));
    assertThat(byAmount.total()).isZero();
    var byAmount2 = as(() -> claims.listClaims(
        new SupplierClaimListQuery(0, 20, null, null, null, null,
            new BigDecimal("1.0000"), new BigDecimal("9.0000"), null, null, null, null)));
    assertThat(byAmount2.total()).isEqualTo(1);

    // 按关键字筛。
    var byKeyword = as(() -> claims.listClaims(
        new SupplierClaimListQuery(0, 20, null, null, null, null, null, null, null, null,
            "SUP-001", null)));
    assertThat(byKeyword.total()).isEqualTo(1);
  }

  @Test
  void 可索赔候选只列已判定已归因且环节可索赔的() {
    seedSupplierRef();
    Chain good = chain("CAND-OK", "PRODUCTION");
    Chain bad = chain("CAND-BAD", "TRANSPORT");

    var page = as(() -> claims.candidates(null, 0, 20));
    assertThat(page.rows()).extracting(SupplierClaimCandidateRow::inspectionId)
        .contains(good.inspectionId())
        .doesNotContain(bad.inspectionId());

    // 候选行带基数与上限，且币种来自质检结论。
    var row = page.rows().stream()
        .filter(r -> r.inspectionId().equals(good.inspectionId())).findFirst().orElseThrow();
    assertThat(row.claimableBaseAmount()).isEqualByComparingTo("10.0000");
    assertThat(row.maxClaimAmount()).isEqualByComparingTo("10.0000");
    assertThat(row.claimCurrency()).isEqualTo("USD");
    assertThat(row.attributedStage()).isEqualTo("PRODUCTION");

    // 被索赔过之后就不再是候选。
    SupplierClaimDetail claim = newClaim("CAND");
    as(() -> claims.addItem(claim.head().id(),
        new SupplierClaimItemCreate(good.inspectionId(), good.attributionId(),
            new BigDecimal("2"), new BigDecimal("8.0000")), user));
    var after = as(() -> claims.candidates(null, 0, 20));
    assertThat(after.rows()).extracting(SupplierClaimCandidateRow::inspectionId)
        .doesNotContain(good.inspectionId());
  }

  @Test
  void 能力声明逐条报出下游未交付() {
    var caps = as(() -> claims.capabilities());
    assertThat(caps.related()).extracting(SupplierClaimRelatedModule::id)
        .contains("MODULE_12_FINANCE", "MODULE_6_5_1", "MODULE_11_2_3_1", "MODULE_11_1_2_2");
    assertThat(caps.related())
        .filteredOn(r -> r.id().equals("MODULE_12_FINANCE"))
        .allMatch(r -> !r.delivered() && r.href() == null);
    assertThat(caps.related())
        .filteredOn(r -> r.id().equals("MODULE_6_5_1"))
        .allMatch(r -> !r.delivered());
    // 「直接置为已结算」刻意不提供。
    assertThat(caps.actions())
        .filteredOn(a -> a.code().equals("SETTLE_DIRECTLY"))
        .allMatch(a -> !a.allowed() && a.ownerModule().equals("12 财务"));
    // 财务确认是**独立权限**上的动作。
    assertThat(caps.actions())
        .filteredOn(a -> a.code().equals("CONFIRM_SETTLEMENT"))
        .allMatch(a -> a.allowed());
  }

  // ==========================================================================
  // 八、HTTP 层：财务确认需要独立权限
  // ==========================================================================

  @Test
  void 财务确认需要独立的财务权限() throws Exception {
    seedSupplierRef();
    SupplierClaimDetail agreed = agreedClaim("HTTP", "PRODUCTION");
    var registered = as(() -> claims.registerSettlement(agreed.head().id(),
        new SupplierSettlementCreate("OFFSET_PAYABLE", new BigDecimal("8.0000")), user));
    UUID settlementId = registered.settlements().get(0).id();

    // 只有 read/write 的身份调财务确认 —— 被拒。
    AccessService.Access noFinance =
        new AccessService.Access(user, tenant, "Test", UUID.randomUUID(), null,
            List.of("trade.aftersales.read", "trade.aftersales.write"),
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL",
                List.of("trade.aftersales.read", "trade.aftersales.write"))));
    actors.put("Bearer no-finance", noFinance);

    http.perform(post("/trade/supplier-claims/{id}/settlements/{sid}/confirm",
            agreed.head().id(), settlementId)
            .header("Authorization", "Bearer no-finance")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"financeReference\":\"AP-1\"}"))
        .andExpect(status().is4xxClientError());

    // 生效金额仍然是 0。
    var still = as(() -> claims.claimDetail(agreed.head().id()));
    assertThat(still.settlements().get(0).effectiveAmount()).isEqualByComparingTo("0.0000");
    assertThat(still.head().claimStatus()).isEqualTo("AGREED");
  }
}
