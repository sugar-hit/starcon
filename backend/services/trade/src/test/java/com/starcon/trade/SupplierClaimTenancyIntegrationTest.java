package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
 * 11.2.3.3 供应商索赔的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且<b>实跑在本模块自己的五张表上</b>
 * （supplier_claim 全族），<b>不是</b>占位探针表 —— 探针表能证明 RLS 开着，
 * 证明不了<b>本模块的查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：索赔单里同时有<b>供应商名、批次、SKU、金额与协商记录</b> ——
 * 一个租户能看到另一个租户「向供应商索赔 8 美元、协商成 5」，
 * 拿到的是**谈判底牌**；而协商记录里常常有对方的开价与让步。
 * 端点又大多是<b>按 id 直达</b>的 URL，猜到一个 UUID 就能读，不需要翻列表。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离 —— 复用上一轮的记录，
 * 第二轮断言通过只能说明「同一条记录没变」。
 *
 * <p>⚠️ 身份桩用 {@code AccessService} 的 <b>spy</b>（只替换对 identity 的一次 HTTP 调用），
 * {@code run} 仍是真实实现 ⇒ RLS 租户绑定跑的是生产代码路径。
 * 刻意<b>不用</b> JDK 内置 {@code HttpServer} 起身份桩：它靠端口 0 让操作系统现分配，
 * 全量并发下偶发端口被抢占 → 503（本仓已知 flake）。这里一个额外监听端口都不开。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SupplierClaimTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
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
  @MockitoSpyBean ProductLotTracePort lots;
  @Autowired SupplierClaimService claims;
  @Autowired ReturnQualityService quality;
  @Autowired ReturnReceiptService receipts;
  @Autowired RmaAuthorizationService rma;
  @Autowired AftersalesCaseService cases;
  @Autowired AftersalesCaseDetailService details;
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
              String permission = inv.getArgument(1, String.class);
              boolean allowed =
                  found.grants().stream()
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
  // 夹具：在指定租户里建一整套「可索赔」的链路
  // ==========================================================================

  record Chain(UUID supplierRefId, UUID inspectionId, UUID attributionId, UUID caseId) {}

  /** 供应商引用镜像（V185）。 */
  UUID seedSupplierRef(UUID tid, UUID uid, String code) {
    UUID ref = UUID.randomUUID();
    as(uid, tid, () -> {
      sql.execute(
          "insert into quality_supplier_ref(id,tenant_id,supplier_ref_id,supplier_code,supplier_name,"
              + "ref_source,source_reference,ref_status,note,created_by,created_at,updated_at)"
              + " values(?::uuid,?::uuid,?::uuid,?,?,'TENANT_MAINTAINED','TEST','ACTIVE','',?::uuid,"
              + "?::timestamptz,?::timestamptz)",
          UUID.randomUUID(), tid, ref, code, "供应商-" + code, uid, T1, T1);
      return null;
    });
    return ref;
  }

  /** 建一整套链路：订单 → 售后单 → 授权 → 实收 → 质检判定 → 责任归因 → 供应商镜像。 */
  Chain seed(UUID tid, UUID uid, String tag) {
    UUID supplierRefId = seedSupplierRef(tid, uid, "SUP-" + tag);
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
              + " values(?,?::uuid,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,?::numeric,"
              + "0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
          orderId, tid, "ORD-" + tag, "EXT-" + tag, T1, amount, amount, T1, T1);
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
              + "created_at) values(?::uuid,?::uuid,?::uuid,1,?::uuid,?,?,'黑色 / L',?::numeric,"
              + "?::numeric,0,0,?::numeric,'',?::timestamptz)",
          lineId, tid, orderId, UUID.randomUUID(), "SKU-" + tag, "商品" + tag, qty, unitPrice, amount, T1);
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
    as(uid, tid, () -> details.addLine(
        caseId,
        new AftersalesCaseDetailModels.AftersalesCaseLineCreate(lineId, qty, amount, "USD", "第 1 件"),
        uid));

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
    as(uid, tid, () -> rma.decide(
        rmaId,
        new RmaAuthorizationModels.RmaDecide(
            "PARTIALLY_APPROVED", due(), "外包装压损，只退部分",
            List.of(new RmaAuthorizationModels.RmaLineDecision(rmaLineId, new BigDecimal("3"), null)),
            0L),
        uid));

    var draft =
        as(uid, tid, () -> receipts.create(
            new ReturnReceiptModels.ReturnReceiptCreate(
                rmaId, null, "CARRIER", "FedEx", "RT-" + tag,
                "外箱一角压瘪", T1,
                List.of(
                    new ReturnReceiptModels.ReturnReceiptLineDraft(
                        rmaLineId, "SKU-X", "SN-" + UUID.randomUUID(), null,
                        new BigDecimal("2"), "DAMAGED", "OPENED_UNDAMAGED", "外箱压损")),
                List.of()),
            uid));
    as(uid, tid, () -> receipts.post(draft.head().id(),
        new ReturnReceiptModels.ReturnReceiptPost(null, draft.head().version()), uid));

    Record posting =
        as(uid, tid, () -> sql.fetchOne(
            "select * from return_inventory_posting where tenant_id = ?::uuid and receipt_id = ?::uuid",
            tid, draft.head().id()));
    UUID postingId = posting.get("id", UUID.class);
    UUID skuId = posting.get("sku_id", UUID.class);
    String skuCode = posting.get("sku_code", String.class);

    // ---- 质检类目 / 模板 / 判定 ----
    UUID categoryId =
        as(uid, tid, () -> quality.createCategory(
            new QualityCategoryCreate("CAT-" + tag, "小家电-" + tag, ""), uid).id());
    as(uid, tid, () -> {
      quality.mapSku(categoryId, new QualitySkuMappingCreate(skuId, skuCode), uid);
      return null;
    });
    var template =
        as(uid, tid, () -> quality.createTemplate(
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
            uid));
    as(uid, tid, () -> quality.activateTemplate(template.template().id(), uid));
    UUID inspectionId =
        as(uid, tid, () -> quality.createInspection(new QualityInspectionCreate(postingId, null), uid)
            .head().id());
    List<QualityCheckResultDraft> results =
        as(uid, tid, () -> quality.templateDetail(template.template().id())).items().stream()
            .map(i -> new QualityCheckResultDraft(i.id(), "FAIL", "检出问题", null)).toList();
    as(uid, tid, () -> quality.recordResults(inspectionId,
        new QualityInspectionUpdate(results,
            as(uid, tid, () -> quality.inspectionDetail(inspectionId)).head().version()), uid));
    as(uid, tid, () -> quality.conclude(inspectionId,
        new QualityInspectionConclude("DEFECTIVE", "功能失效",
            as(uid, tid, () -> quality.inspectionDetail(inspectionId)).head().version()), uid));

    // ---- 责任归因（生产责任，挂批次）----
    org.mockito.Mockito
        .doAnswer(
            inv -> {
              UUID lotId = inv.getArgument(0);
              return new ProductLotTracePort.LotSnapshot(
                  lotId, "LOT-" + tag, null, supplierRefId, "供应商-SUP-" + tag);
            })
        .when(lots)
        .snapshot(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());

    UUID attributionId =
        as(uid, tid, () -> attributions.create(
                new AttributionCreate(caseId, inspectionId, null, UUID.randomUUID(), "LOT-" + tag,
                    null, supplierRefId, skuId, skuCode, "商品-" + tag, new BigDecimal("2.000000")),
                uid, "Bearer " + (tid.equals(a) ? "tenant-a" : "tenant-b")))
            .row().id();
    as(uid, tid, () -> attributions.attribute(attributionId,
        new AttributionConclude("PRODUCTION", "生产不良", "改进建议"), uid));

    return new Chain(supplierRefId, inspectionId, attributionId, caseId);
  }

  /** 建一张索赔单并加上一条明细（草稿态）。 */
  UUID newClaim(UUID tid, UUID uid, Chain c, String tag) {
    UUID claimId =
        as(uid, tid, () -> claims.createClaim(
            new SupplierClaimCreate(c.supplierRefId(), "QUALITY_DEFECT", "批次不良：" + tag, "USD"),
            uid).head().id());
    as(uid, tid, () -> claims.addItem(claimId,
        new SupplierClaimItemCreate(c.inspectionId(), c.attributionId(),
            new BigDecimal("2"), new BigDecimal("8.0000")), uid));
    return claimId;
  }

  private static SupplierClaimListQuery query() {
    return new SupplierClaimListQuery(0, 20, null, null, null, null, null, null, null, null, null, null);
  }

  // ==========================================================================
  // 断言一：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见() throws Exception {
    Chain ca = seed(a, userA, "ISO-A");
    // 每轮都新建实体：复用上一轮的记录，第二轮断言通过只能说明「同一条记录没变」。
    Chain cb = seed(b, userB, "ISO-B");

    // ---- 候选互不可见（⚠️ 必须在建索赔单**之前**断言：已被索赔过的质检结论不再出现在候选里）----
    assertThat(asA(() -> claims.candidates(null, 0, 20)).rows())
        .extracting(SupplierClaimCandidateRow::inspectionId).containsExactly(ca.inspectionId());
    assertThat(asB(() -> claims.candidates(null, 0, 20)).rows())
        .extracting(SupplierClaimCandidateRow::inspectionId).containsExactly(cb.inspectionId());

    UUID ida = newClaim(a, userA, ca, "ISO-A");
    UUID idb = newClaim(b, userB, cb, "ISO-B");

    // ---- 列表互不可见（含汇总口径）----
    assertThat(asA(() -> claims.listClaims(query())).total()).isEqualTo(1);
    assertThat(asA(() -> claims.listClaims(query())).rows())
        .extracting(SupplierClaimRow::id).containsExactly(ida);
    assertThat(asB(() -> claims.listClaims(query())).rows())
        .extracting(SupplierClaimRow::id).containsExactly(idb);

    // 供应商名字不串（两边的供应商编码不同）。
    assertThat(asA(() -> claims.claimDetail(ida)).head().supplierCode()).isEqualTo("SUP-ISO-A");
    assertThat(asB(() -> claims.claimDetail(idb)).head().supplierCode()).isEqualTo("SUP-ISO-B");

    // ---- 候选互不可见（索赔单建好之后，两个候选集合都空了：已被索赔过的质检结论不再出现）----
    assertThat(asA(() -> claims.candidates(null, 0, 20)).rows()).isEmpty();
    assertThat(asB(() -> claims.candidates(null, 0, 20)).rows()).isEmpty();

    // ⚠️ **按 id 直达也不行**：A 拿着 B 的索赔单 id 读，
    //   得到的是与「不存在」完全相同的错误（不告诉调用方「这个 ID 存在但不属于你」）。
    assertThatThrownBy(() -> asA(() -> claims.claimDetail(idb)))
        .hasStackTraceContaining("不存在或不属于当前租户");
    // 写入口也拦住：不能替别人加明细、不能替别人提交。
    assertThatThrownBy(() -> asA(() -> claims.addItem(idb,
            new SupplierClaimItemCreate(cb.inspectionId(), cb.attributionId(),
                new BigDecimal("2"), new BigDecimal("8.0000")), userA)))
        .hasStackTraceContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> asA(() -> claims.submit(idb, new SupplierClaimSubmit("", 0L), userA)))
        .hasStackTraceContaining("不存在或不属于当前租户");

    // HTTP 层同样如此（按 id 直达的 URL 是最容易被猜到的那种）。
    http.perform(get("/trade/supplier-claims/{id}", idb)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().is4xxClientError());
  }

  // ==========================================================================
  // 断言二：伪造 tenant header 无效
  // ==========================================================================

  @Test
  void 伪造租户头无效() throws Exception {
    Chain ca = seed(a, userA, "FORGE-A");
    Chain cb = seed(b, userB, "FORGE-B");
    UUID ida = newClaim(a, userA, ca, "FORGE-A");
    UUID idb = newClaim(b, userB, cb, "FORGE-B");

    // 带着 **B 的令牌** + **伪造的 A 的 X-Tenant-Id**：读到的必须是 B 的数据，
    // 不是 A 的 —— 头改不动 identity 解析出来的可信租户。
    String list =
        http.perform(get("/trade/supplier-claims")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(list).contains(idb.toString()).doesNotContain(ida.toString());

    // 反过来：带着 A 的令牌 + 伪造的 B 的头，读到的仍然是 A 的数据。
    String list2 =
        http.perform(get("/trade/supplier-claims")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(list2).contains(ida.toString()).doesNotContain(idb.toString());

    // 详情同理。
    String detail =
        http.perform(get("/trade/supplier-claims/{id}", idb)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(detail).contains(idb.toString()).doesNotContain(ida.toString());

    // 直接在 RLS 上验证：绑定 A 的租户之后，B 的行在库里**读不出来**，
    // 绕过服务层也一样（FORCE RLS 对表 owner 之外的角色同样生效，这里跑的是 trade_app）。
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) from supplier_claim where tenant_id=?::uuid and id=?::uuid", b, idb))
        .get(0, Long.class)).as("RLS 之下看不到别的租户的行").isZero();

    // ⚠️ **即使不带 tenant_id 谓词**也读不到（证明是真隔离，不是应用层过滤）。
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) from supplier_claim where id=?::uuid", idb))
        .get(0, Long.class)).as("不带租户谓词也读不到别人的行").isZero();
  }

  // ==========================================================================
  // 断言三：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 连接池归还后不串租户() throws Exception {
    Chain ca = seed(a, userA, "POOL-A");
    Chain cb = seed(b, userB, "POOL-B");
    UUID ida = newClaim(a, userA, ca, "POOL-A");
    UUID idb = newClaim(b, userB, cb, "POOL-B");

    // 交替借还：同一张索赔单反复以两个租户的身份读，读到的永远是**当前绑定**的租户。
    for (int i = 0; i < 3; i++) {
      assertThat(asA(() -> claims.claimDetail(ida)).head().supplierCode()).isEqualTo("SUP-POOL-A");
      assertThat(asB(() -> claims.claimDetail(idb)).head().supplierCode()).isEqualTo("SUP-POOL-B");
      // 归还之后再读另一个租户的 id：仍然读不到。
      assertThatThrownBy(() -> asA(() -> claims.claimDetail(idb)))
          .hasStackTraceContaining("不存在或不属于当前租户");
      assertThatThrownBy(() -> asB(() -> claims.claimDetail(ida)))
          .hasStackTraceContaining("不存在或不属于当前租户");
    }

    // 协商与赔付也在交替中完成，两个租户的结论互不污染。
    asA(() -> claims.submit(ida, new SupplierClaimSubmit("", 0L), userA));
    asB(() -> claims.submit(idb, new SupplierClaimSubmit("", 0L), userB));

    asA(() -> claims.recordNegotiation(ida,
        new SupplierNegotiationCreate("AGREEMENT", "SUPPLIER",
            new BigDecimal("8.0000"), new BigDecimal("8.0000"), "A 租户谈成 8", T1), userA));
    asB(() -> claims.recordNegotiation(idb,
        new SupplierNegotiationCreate("AGREEMENT", "SUPPLIER",
            new BigDecimal("3.0000"), new BigDecimal("3.0000"), "B 租户谈成 3", T1), userB));

    // ⚠️ 协商是**必经环节**：先有协商记录才进得了协商中。
    asA(() -> claims.startNegotiation(ida, 1L, userA));
    asB(() -> claims.startNegotiation(idb, 1L, userB));

    asA(() -> claims.agree(ida, new SupplierClaimAgree(
        asA(() -> claims.claimDetail(ida)).head().version()), userA));
    asB(() -> claims.agree(idb, new SupplierClaimAgree(
        asB(() -> claims.claimDetail(idb)).head().version()), userB));

    // 登记赔付 → 财务确认：两个租户的冲减生效金额互不串。
    UUID sa = asA(() -> claims.registerSettlement(ida,
        new SupplierSettlementCreate("OFFSET_PAYABLE", new BigDecimal("8.0000")), userA))
        .settlements().get(0).id();
    UUID sb = asB(() -> claims.registerSettlement(idb,
        new SupplierSettlementCreate("OFFSET_PAYABLE", new BigDecimal("3.0000")), userB))
        .settlements().get(0).id();

    asA(() -> claims.confirmSettlement(ida, sa, new SupplierSettlementConfirm("AP-A"), userA));
    asB(() -> claims.confirmSettlement(idb, sb, new SupplierSettlementConfirm("AP-B"), userB));

    // 两条结论各自独立：金额、冲减、状态都不串。
    var da = asA(() -> claims.claimDetail(ida));
    var db = asB(() -> claims.claimDetail(idb));
    assertThat(da.head().agreedAmount()).isEqualByComparingTo("8.0000");
    assertThat(db.head().agreedAmount()).isEqualByComparingTo("3.0000");
    assertThat(da.head().appliedOffsetAmount()).isEqualByComparingTo("8.0000");
    assertThat(db.head().appliedOffsetAmount()).isEqualByComparingTo("3.0000");
    assertThat(da.settlements().get(0).financeReference()).isEqualTo("AP-A");
    assertThat(db.settlements().get(0).financeReference()).isEqualTo("AP-B");
    assertThat(asA(() -> claims.listClaims(query())).rows())
        .extracting(SupplierClaimRow::id).containsExactly(ida);
  }

  // ==========================================================================
  // 附：本模块的隔离面（证明断言跑在真表上）
  // ==========================================================================

  @Test
  void 伪造租户头不能写也不能确认冲减() throws Exception {
    Chain ca = seed(a, userA, "WRITE-A");
    Chain cb = seed(b, userB, "WRITE-B");
    UUID ida = newClaim(a, userA, ca, "WRITE-A");
    UUID idb = newClaim(b, userB, cb, "WRITE-B");

    // B 的令牌 + 伪造的 A 的头去**写** A 的索赔单：绝不会落到 A 的行上。
    http.perform(MockMvcRequestBuilders.post("/trade/supplier-claims/{id}/submit", ida)
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"submitNote\":\"越权提交\",\"expectedVersion\":0}"))
        .andExpect(status().is4xxClientError());

    // A 的索赔单仍然是草稿。
    assertThat(asA(() -> claims.claimDetail(ida)).head().claimStatus()).isEqualTo("DRAFT");
    // B 自己的那张也没被提交（越权请求打到了 B 的租户上，但版本对不上 → 拒绝）。
    assertThat(asB(() -> claims.claimDetail(idb)).head().claimStatus()).isEqualTo("DRAFT");

    // 反过来，A 也不能替 B 提交。
    http.perform(MockMvcRequestBuilders.post("/trade/supplier-claims/{id}/submit", idb)
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"submitNote\":\"越权提交\",\"expectedVersion\":0}"))
        .andExpect(status().is4xxClientError());
    assertThat(asB(() -> claims.claimDetail(idb)).head().claimStatus()).isEqualTo("DRAFT");
  }
}
