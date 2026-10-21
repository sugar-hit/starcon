package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.PurchaseOrderEditModels.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.3.1.2 采购单创建与编辑的<b>行为测试</b>。
 *
 * <p>跑在**真实的 PostgreSQL 16**（Testcontainers）上，跨服务走**真的本地 HTTP 替身**。
 * 每一例都断言「蓝图那句话有没有真的成立」，而不是「服务有没有返回 200」。
 *
 * <p>本模块最要紧的三条断言：
 *
 * <ol>
 *   <li><b>金额与行数由 DB 算</b>（V254 的触发器）：调用方传不进来，
 *       头金额与各行金额之和**永远是同一个数</b>。</li>
 *   <li><b>汇率快照在提交那一刻冻结，取不到就是没有</b>：绝不按 1:1 填一个数进去 ——
 *       假汇率会让「跨币种合计」变成一个看起来能对账、其实对不上的数。</li>
 *   <li><b>歧义报价不替用户挑</b>：提交时 409，而不是静默取第一条。</li>
 * </ol>
 */
class PurchaseOrderEditIntegrationTest extends PurchaseOrderEditFixture {

  @Autowired PurchaseOrderEditService editor;

  @BeforeEach
  void clean() throws Exception {
    truncateAll();
    catalogDown.set(false);
    warehouseDown.set(false);
    ambiguousQuotes.set(false);
    noQuote.set(false);
    noRate.set(false);
  }

  private LocalDate soon() {
    return LocalDate.now(ZoneOffset.UTC).plusDays(20);
  }

  private PurchaseOrderEditOrderInput form(int lineCount) {
    List<PurchaseOrderEditLineInput> lines = new java.util.ArrayList<>();
    for (int i = 0; i < lineCount; i++) {
      lines.add(
          new PurchaseOrderEditLineInput(
              i + 1,
              UUID.nameUUIDFromBytes(("SKU-" + i).getBytes()),
              "SKU-" + i,
              "测试商品 " + i,
              new BigDecimal("100"),
              "箱",
              new BigDecimal("2.5"),
              "MANUAL",
              null,
              "测试行"));
    }
    return new PurchaseOrderEditOrderInput(
        supplierRoleId,
        "SUP-TEST",
        "测试供应商",
        legalEntityId,
        "US-LLC",
        "美国子公司",
        "USD",
        warehouseId,
        "WH-LA-01",
        "洛杉矶仓",
        soon(),
        "手工建单",
        lines,
        new PurchaseOrderEditAddressInput(
            "张三", "13800000000", "US", "California", "Los Angeles", "LA County", "123 Main St",
            "90001", "工作日送"),
        List.of());
  }

  private PurchaseOrderEditForm create() {
    return asWrite(
        "Bearer tenant-a",
        () -> editor.create(new PurchaseOrderEditCreateRequest(form(1)), userA, departmentA, "Bearer tenant-a"));
  }

  // ==========================================================================
  // 建单
  // ==========================================================================

  @Test
  void 手工建单落库且金额与行数由数据库算() {
    PurchaseOrderEditForm created = create();

    assertThat(created.status()).isEqualTo("DRAFT");
    assertThat(created.orderNo()).startsWith("PO-");
    // 🔴 手工建单必须写 'MANUAL'：写错成转单来源会把两套来源混成一套。
    assertThat(created.sourceType()).isEqualTo("MANUAL");
    assertThat(created.lines()).hasSize(1);

    // 行金额与头金额都是 DB 算的（调用方传不进来）。
    assertThat(created.lines().get(0).lineAmount()).isEqualByComparingTo("250");
    assertThat(created.amounts().totalAmount()).isEqualByComparingTo("250");
    assertThat(created.amounts().lineCount()).isEqualTo(1);

    // 库里的值也确实是 250（不是 Java 侧算完写进去的）。
    Long total =
        as("Bearer tenant-a", () -> sql.fetchOne("select total_amount from purchase_order").get(0, BigDecimal.class)).longValue();
    assertThat(total).isEqualTo(250L);
  }

  @Test
  void 建单缺关键字段被拒() {
    PurchaseOrderEditOrderInput noSupplier =
        new PurchaseOrderEditOrderInput(
            null, "SUP", "供应商", legalEntityId, "US-LLC", "美国", "USD", warehouseId, "WH", "仓",
            soon(), "", form(1).lines(), null, List.of());
    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        editor.create(
                            new PurchaseOrderEditCreateRequest(noSupplier), userA, departmentA,
                            "Bearer tenant-a")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("供应商");

    PurchaseOrderEditOrderInput noLines =
        new PurchaseOrderEditOrderInput(
            supplierRoleId, "SUP", "供应商", legalEntityId, "US-LLC", "美国", "USD", warehouseId, "WH",
            "仓", soon(), "", List.of(), null, List.of());
    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        editor.create(
                            new PurchaseOrderEditCreateRequest(noLines), userA, departmentA,
                            "Bearer tenant-a")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("一行");

    // 报价来源的行必须带 quoteId（否则没法冻结报价快照）。
    PurchaseOrderEditLineInput quoteWithoutId =
        new PurchaseOrderEditLineInput(
            1, UUID.randomUUID(), "SKU", "商品", BigDecimal.ONE, "箱", BigDecimal.ONE, "QUOTE", null, "");
    PurchaseOrderEditOrderInput quoteForm =
        new PurchaseOrderEditOrderInput(
            supplierRoleId, "SUP", "供应商", legalEntityId, "US-LLC", "美国", "USD", warehouseId, "WH",
            "仓", soon(), "", List.of(quoteWithoutId), null, List.of());
    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        editor.create(
                            new PurchaseOrderEditCreateRequest(quoteForm), userA, departmentA,
                            "Bearer tenant-a")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quoteId");
  }

  // ==========================================================================
  // 编辑
  // ==========================================================================

  @Test
  void 编辑是整体替换_增行改行删行() {
    PurchaseOrderEditForm created = create();

    // 两行（增一行）
    PurchaseOrderEditForm two =
        asWrite(
            "Bearer tenant-a",
            () ->
                editor.update(
                    created.id(),
                    new PurchaseOrderEditUpdateRequest(created.version(), form(2)),
                    userA,
                    departmentA,
                    "ALL",
                    "Bearer tenant-a"));
    assertThat(two.lines()).hasSize(2);
    assertThat(two.amounts().lineCount()).isEqualTo(2);
    assertThat(two.amounts().totalAmount()).isEqualByComparingTo("500");
    assertThat(two.version()).isEqualTo(created.version() + 1);

    // 回到一行（删一行）—— 少传一行 = 删掉那一行。
    PurchaseOrderEditForm one =
        asWrite(
            "Bearer tenant-a",
            () ->
                editor.update(
                    two.id(),
                    new PurchaseOrderEditUpdateRequest(two.version(), form(1)),
                    userA,
                    departmentA,
                    "ALL",
                    "Bearer tenant-a"));
    assertThat(one.lines()).hasSize(1);
    assertThat(one.amounts().lineCount()).isEqualTo(1);
    assertThat(one.amounts().totalAmount()).isEqualByComparingTo("250");

    // 行号是连续的 1..N（不是乱序）。
    assertThat(one.lines().get(0).lineNo()).isEqualTo(1);
  }

  @Test
  void 乐观锁不匹配返409() {
    PurchaseOrderEditForm created = create();
    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        editor.update(
                            created.id(),
                            new PurchaseOrderEditUpdateRequest(created.version() + 5, form(1)),
                            userA,
                            departmentA,
                            "ALL",
                            "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已被别人改过");
  }

  @Test
  void 缺expectedVersion被拒() {
    PurchaseOrderEditForm created = create();
    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        editor.update(
                            created.id(),
                            new PurchaseOrderEditUpdateRequest(null, form(1)),
                            userA,
                            departmentA,
                            "ALL",
                            "Bearer tenant-a")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expected_version");
  }

  // ==========================================================================
  // 提交与快照冻结
  // ==========================================================================

  @Test
  void 提交把草稿推进待审批并在那一刻冻结快照() {
    // 一行带报价的行（priceSource=QUOTE）
    List<PurchaseOrderEditLineInput> lines =
        List.of(
            new PurchaseOrderEditLineInput(
                1, UUID.nameUUIDFromBytes("SKU-Q".getBytes()), "SKU-Q", "报价商品", new BigDecimal("10"),
                "箱", new BigDecimal("2.5"), "QUOTE", quoteId, ""));
    PurchaseOrderEditOrderInput quoteForm =
        new PurchaseOrderEditOrderInput(
            supplierRoleId, "SUP-TEST", "测试供应商", legalEntityId, "US-LLC", "美国子公司", "USD",
            warehouseId, "WH-LA-01", "洛杉矶仓", soon(), "带报价的行", lines, null, List.of());
    PurchaseOrderEditForm created =
        asWrite(
            "Bearer tenant-a",
            () ->
                editor.create(
                    new PurchaseOrderEditCreateRequest(quoteForm), userA, departmentA,
                    "Bearer tenant-a"));

    // 提交前：还没有冻结快照（还没提交就没有）。
    assertThat(created.lines().get(0).snapshot()).isNull();
    assertThat(created.amounts().fxStatus()).isEqualTo("NOT_APPLICABLE");

    PurchaseOrderEditForm submitted =
        asWrite(
            "Bearer tenant-a",
            () ->
                editor.submit(
                    created.id(),
                    new PurchaseOrderEditSubmitRequest(created.version(), "提交采购"),
                    userA,
                    departmentA,
                    "ALL",
                    "Bearer tenant-a"));

    assertThat(submitted.status()).isEqualTo("PENDING_APPROVAL");
    assertThat(submitted.editable()).isFalse();

    // ---- 行级快照：单位换算 / 报价 / 库存 ----
    PurchaseOrderEditLineSnapshot snapshot = submitted.lines().get(0).snapshot();
    assertThat(snapshot).isNotNull();
    assertThat(snapshot.conversionStatus()).isEqualTo("CATALOG_UNIT_CONVERSION");
    // 🔴 箱规是 1 箱 = 12 件：数量 10 箱 = 120 件。
    assertThat(snapshot.quantityPerBase()).isEqualByComparingTo("12");
    assertThat(snapshot.baseQuantity()).isEqualByComparingTo("120");
    // 报价快照
    assertThat(snapshot.quoteId()).isEqualTo(quoteId);
    assertThat(snapshot.quoteUnitPrice()).isEqualByComparingTo("2.5");
    assertThat(snapshot.quoteCurrency()).isEqualTo("USD");
    // 库存快照（08 仓储）
    assertThat(snapshot.stockStatus()).isEqualTo("CAPTURED");
    assertThat(snapshot.stockAvailableQty()).isEqualByComparingTo("40");

    // ---- 单级快照：本位币与汇率 ----
    assertThat(submitted.amounts().functionalCurrency()).isEqualTo("CNY");
    assertThat(submitted.amounts().fxStatus()).isEqualTo("CAPTURED");
    assertThat(submitted.amounts().fxRateToFunctional()).isEqualByComparingTo("7.2");
    // 总额 25 USD × 7.2 = 180 CNY
    assertThat(submitted.amounts().functionalAmount()).isEqualByComparingTo("180");

    // 库里也落了两张快照表。
    Long lineSnapshots =
        as("Bearer tenant-a", () -> sql.fetchOne("select count(*) from purchase_order_line_snapshot").get(0, Long.class));
    assertThat(lineSnapshots).isEqualTo(1L);
    Long submitSnapshots =
        as("Bearer tenant-a", () -> sql.fetchOne("select count(*) from purchase_order_submit_snapshot").get(0, Long.class));
    assertThat(submitSnapshots).isEqualTo(1L);

    // 留痕：状态推进有记录。
    String events =
        as(
            "Bearer tenant-a",
            () -> sql.fetchOne("select string_agg(event_type,',') from purchase_order_event").get(0, String.class));
    assertThat(events).contains("STATUS_CHANGED");
  }

  @Test
  void 提交时取不到汇率就是没有_绝不按一比一填() {
    noRate.set(true);
    PurchaseOrderEditForm created = create();
    PurchaseOrderEditForm submitted =
        asWrite(
            "Bearer tenant-a",
            () ->
                editor.submit(
                    created.id(),
                    new PurchaseOrderEditSubmitRequest(created.version(), ""),
                    userA,
                    departmentA,
                    "ALL",
                    "Bearer tenant-a"));

    // 🔴 取不到汇率 ⇒ 快照是「没有」，**不是** 1:1。
    assertThat(submitted.amounts().fxStatus()).isEqualTo("UNAVAILABLE");
    assertThat(submitted.amounts().fxRateToFunctional()).isNull();
    assertThat(submitted.amounts().functionalAmount()).isNull();
    // 原币种总额照报（那一半是真事实）。
    assertThat(submitted.amounts().totalAmount()).isEqualByComparingTo("250");
    // 库里的行上也没有汇率。
    BigDecimal lineRate =
        as("Bearer tenant-a", () -> sql.fetchOne("select fx_rate_to_functional from purchase_order_line").get(0, BigDecimal.class));
    assertThat(lineRate).isNull();
  }

  @Test
  void 提交时报价歧义直接409不替用户挑() {
    ambiguousQuotes.set(true);
    List<PurchaseOrderEditLineInput> lines =
        List.of(
            new PurchaseOrderEditLineInput(
                1, UUID.nameUUIDFromBytes("SKU-Q".getBytes()), "SKU-Q", "报价商品", new BigDecimal("10"),
                "箱", new BigDecimal("2.5"), "QUOTE", quoteId, ""));
    PurchaseOrderEditOrderInput quoteForm =
        new PurchaseOrderEditOrderInput(
            supplierRoleId, "SUP-TEST", "测试供应商", legalEntityId, "US-LLC", "美国子公司", "USD",
            warehouseId, "WH-LA-01", "洛杉矶仓", soon(), "", lines, null, List.of());
    PurchaseOrderEditForm created =
        asWrite(
            "Bearer tenant-a",
            () ->
                editor.create(
                    new PurchaseOrderEditCreateRequest(quoteForm), userA, departmentA,
                    "Bearer tenant-a"));

    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        editor.submit(
                            created.id(),
                            new PurchaseOrderEditSubmitRequest(created.version(), ""),
                            userA,
                            departmentA,
                            "ALL",
                            "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("歧义");

    // 单还是草稿（提交没有半途成功）。
    String status =
        as("Bearer tenant-a", () -> sql.fetchOne("select status from purchase_order").get(0, String.class));
    assertThat(status).isEqualTo("DRAFT");
  }

  @Test
  void 带出报价时歧义不替用户挑() {
    ambiguousQuotes.set(true);
    PurchaseOrderEditQuotePreview preview =
        as(
            "Bearer tenant-a",
            () ->
                editor.quotePreview(
                    new PurchaseOrderEditQuotePreviewRequest(
                        UUID.nameUUIDFromBytes("SKU-Q".getBytes()), supplierRoleId, new BigDecimal("10"),
                        LocalDate.now(ZoneOffset.UTC)),
                    "Bearer tenant-a"));
    assertThat(preview.ambiguous()).isTrue();
    // 🔴 selected 必须是 null：并列时不替用户挑。
    assertThat(preview.selected()).isNull();
    assertThat(preview.candidates()).hasSize(2);
  }

  @Test
  void 提交后不能再改() {
    PurchaseOrderEditForm created = create();
    PurchaseOrderEditForm submitted =
        asWrite(
            "Bearer tenant-a",
            () ->
                editor.submit(
                    created.id(),
                    new PurchaseOrderEditSubmitRequest(created.version(), ""),
                    userA,
                    departmentA,
                    "ALL",
                    "Bearer tenant-a"));

    assertThatThrownBy(
            () ->
                asWrite(
                    "Bearer tenant-a",
                    () ->
                        editor.update(
                            submitted.id(),
                            new PurchaseOrderEditUpdateRequest(submitted.version(), form(2)),
                            userA,
                            departmentA,
                            "ALL",
                            "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只有草稿能");
  }

  @Test
  void 只有草稿能删行_已提交的行删不掉() throws Exception {
    PurchaseOrderEditForm created = create();
    asWrite(
        "Bearer tenant-a",
        () ->
            editor.submit(
                created.id(),
                new PurchaseOrderEditSubmitRequest(created.version(), ""),
                userA,
                departmentA,
                "ALL",
                "Bearer tenant-a"));

    // 直接连库删（绕过服务层）也删不掉：V258 的触发器钉死「只有草稿能删行」。
    try (var c = admin();
        var ps = c.prepareStatement("delete from purchase_order_line where order_id=?::uuid")) {
      ps.setObject(1, created.id());
      assertThatThrownBy(ps::executeUpdate)
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("它的行不可删除");
    }
  }

  @Test
  void 税额恒为没有并说清归属模块() {
    PurchaseOrderEditForm created = create();
    assertThat(created.amounts().taxRate()).isNull();
    assertThat(created.amounts().taxAmount()).isNull();
    assertThat(created.amounts().taxStatus()).isEqualTo("NOT_AVAILABLE");
    assertThat(created.amounts().taxNote()).contains("12 财务");
  }

  // ==========================================================================
  // 由 6.2.2.2 的合并草稿建单
  // ==========================================================================

  @Test
  void 由合并草稿建单并把草稿置为已转单() throws Exception {
    UUID draftId =
        seedMergeDraft(tenantA, "T1", "USD", "2.5", LocalDate.now(ZoneOffset.UTC).plusDays(15), true);

    PurchaseOrderEditFromDraftResult result =
        asWrite(
            "Bearer tenant-a",
            () ->
                editor.fromDrafts(
                    new PurchaseOrderEditFromDraftRequest(List.of(draftId)), userA, departmentA,
                    "Bearer tenant-a"));

    assertThat(result.created()).hasSize(1);
    assertThat(result.rejected()).isEmpty();
    PurchaseOrderEditCreatedOrder created = result.created().get(0);
    assertThat(created.orderNo()).startsWith("PO-");
    assertThat(created.lineCount()).isEqualTo(1);
    assertThat(created.totalAmount()).isEqualByComparingTo("250");

    // 草稿被置为 CONVERTED（V253 显式允许 status → CONVERTED）。
    String draftStatus =
        as("Bearer tenant-a", () -> sql.fetchOne("select status from purchase_demand_merge_draft").get(0, String.class));
    assertThat(draftStatus).isEqualTo("CONVERTED");

    // 来源记在本模块自己的表上。
    Long sources =
        as("Bearer tenant-a", () -> sql.fetchOne("select count(*) from purchase_order_draft_source").get(0, Long.class));
    assertThat(sources).isEqualTo(1L);

    // 🔴 草稿 id **没有**被塞进 source_conversion_id（那一列指向 6.2.2.1 的转单事实）。
    UUID conversion =
        as("Bearer tenant-a", () -> sql.fetchOne("select source_conversion_id from purchase_order_line").get(0, UUID.class));
    assertThat(conversion).isNull();

    // 头是「来自采购申请」来源（合并草稿背后就是 6.2.2.1 的申请行）。
    String sourceType =
        as("Bearer tenant-a", () -> sql.fetchOne("select source_type from purchase_order").get(0, String.class));
    assertThat(sourceType).isEqualTo("PURCHASE_REQUEST_CONVERSION");
  }

  @Test
  void 合并草稿没定稿或缺单价被拒且逐条给原因() throws Exception {
    UUID notFinalized =
        seedMergeDraft(tenantA, "T2", "USD", "2.5", LocalDate.now(ZoneOffset.UTC).plusDays(15), false);
    UUID noPrice =
        seedMergeDraft(tenantA, "T3", "USD", null, LocalDate.now(ZoneOffset.UTC).plusDays(15), true);
    UUID noDate =
        seedMergeDraft(tenantA, "T4", "USD", "2.5", null, true);

    PurchaseOrderEditFromDraftResult result =
        asWrite(
            "Bearer tenant-a",
            () ->
                editor.fromDrafts(
                    new PurchaseOrderEditFromDraftRequest(List.of(notFinalized, noPrice, noDate)),
                    userA,
                    departmentA,
                    "Bearer tenant-a"));

    assertThat(result.created()).isEmpty();
    assertThat(result.rejected()).hasSize(3);
    assertThat(result.rejected())
        .extracting(PurchaseOrderEditRejectedDraft::reasonCode)
        .containsExactlyInAnyOrder("BATCH_NOT_FINALIZED", "UNIT_PRICE_MISSING", "NO_PROMISED_DATE");
    // 被拒的都有中文原因（光有原因码用户看不懂）。
    assertThat(result.rejected()).allSatisfy(r -> assertThat(r.reason()).isNotBlank());
  }

  // ==========================================================================
  // 附件
  // ==========================================================================

  @Test
  void 附件元数据来自catalog媒体库() {
    UUID mediaId = UUID.randomUUID();
    List<PurchaseOrderEditAttachmentInput> attachments =
        List.of(new PurchaseOrderEditAttachmentInput(mediaId, "CONTRACT", "采购合同"));
    PurchaseOrderEditOrderInput withAttachment =
        new PurchaseOrderEditOrderInput(
            supplierRoleId, "SUP-TEST", "测试供应商", legalEntityId, "US-LLC", "美国子公司", "USD",
            warehouseId, "WH-LA-01", "洛杉矶仓", soon(), "", form(1).lines(), null, attachments);

    PurchaseOrderEditForm created =
        asWrite(
            "Bearer tenant-a",
            () ->
                editor.create(
                    new PurchaseOrderEditCreateRequest(withAttachment), userA, departmentA,
                    "Bearer tenant-a"));

    assertThat(created.attachments()).hasSize(1);
    PurchaseOrderEditAttachment attachment = created.attachments().get(0);
    assertThat(attachment.mediaId()).isEqualTo(mediaId);
    // 元数据是**从 catalog 读来的**，不是调用方填的。
    assertThat(attachment.mediaName()).isEqualTo("合同扫描件.pdf");
    assertThat(attachment.mimeType()).isEqualTo("application/pdf");
    assertThat(attachment.storageKey()).isEqualTo("media/" + mediaId + ".pdf");
  }

  // ==========================================================================
  // HTTP 契约
  // ==========================================================================

  @Test
  void http契约_未登录401与参数错400与找不到404() throws Exception {
    http.perform(get("/trade/purchase-order-editor/capabilities"))
        .andExpect(status().isUnauthorized());

    http.perform(get("/trade/purchase-order-editor/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.capabilities[0].code").value("manualCreate"));

    // 能力声明里如实标出未交付的下游。
    String body =
        http.perform(
                get("/trade/purchase-order-editor/capabilities").header("Authorization", "Bearer tenant-a"))
            .andReturn().getResponse().getContentAsString();
    assertThat(body).contains("6.3.1.3 采购单审批");
    assertThat(body).contains("12 财务");

    // 缺 SKU 的报价预览 ⇒ 400。
    http.perform(
            post("/trade/purchase-order-editor/quote-preview")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());

    // 不存在的采购单 ⇒ 404。
    http.perform(
            get("/trade/purchase-order-editor/orders/" + UUID.randomUUID())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }
}
