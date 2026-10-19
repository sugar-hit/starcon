package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
 * 11.2.3.1 质检与判定的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且**实跑在本模块自己的八张表上**
 * （return_quality_* 全族），<b>不是</b>占位探针表 —— 探针表能证明 RLS 开着，
 * 证明不了<b>本模块的查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：质检结论决定这批货<b>以什么身份重新进入销售</b> ——
 * 一个租户能看到另一个租户「判成可再销售」，等于他把别人的残次品当新品买走了；
 * 而逐项结果、判定理由与照片引用是要拿去和供应商 / 平台索赔的<b>举证材料</b>。
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
class ReturnQualityTenancyIntegrationTest {
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
  @Autowired ReturnQualityService quality;
  @Autowired ReturnReceiptService receipts;
  @Autowired RmaAuthorizationService rma;
  @Autowired AftersalesCaseService cases;
  @Autowired AftersalesCaseDetailService details;
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

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.aftersales.write", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-b"), "trade.aftersales.write", work);
  }

  // ==========================================================================
  // 夹具：在指定租户里建一整套「可判质检」的链路
  // ==========================================================================

  // ⚠️ **刻意不带 inspectionId**：夹具不开质检单（待检队列断言要的是「还没开单」的那批货），
  //   带一个恒为 null 的字段只会被误用成「质检单 id」—— 而 null 传给 inspectionDetail
  //   得到的是「质检单不存在或不属于当前租户：null」，症状离病因很远。
  //   需要质检单的地方用 open(...) 显式开，拿它返回的 id。
  record Fixture(UUID categoryId, UUID templateId, UUID postingId, String skuCode) {}

  /** 建订单 → 售后单 → 授权 → 实收入库 → 类目 → 模板 → 质检单（草稿）。 */
  Fixture seed(UUID tid, UUID uid, String tag, String goodsCondition) throws Exception {
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
              + " values(?,?,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,?::numeric,"
              + "0,0,0,0,?::numeric,0,?::timestamptz,?::timestamptz)",
          orderId, tid, "ORD-" + tag, "EXT-" + tag, T1, amount, amount, T1, T1);
      sql.execute(
          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
              + "spec_snapshot,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
              + "created_at) values(?,?::uuid,?::uuid,1,?,?,?,'黑色 / L',?::numeric,?::numeric,"
              + "0,0,?::numeric,'',?::timestamptz)",
          lineId, tid, orderId, UUID.randomUUID(), "SKU-" + tag, "商品" + tag,
          qty, unitPrice, amount, T1);
      sql.execute(
          "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
              + "recipient_phone,recipient_email,company_name,country_code,province,city,"
              + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
              + " values(?,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
              + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
          UUID.randomUUID(), tid, orderId, "买家-" + tag, "buyer-" + tag + "@example.com", T1);
      sql.execute(
          "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
              + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
              + " values(?,?::uuid,?::uuid,?,?,?,'INDIVIDUAL','',?::timestamptz)",
          UUID.randomUUID(), tid, orderId, UUID.randomUUID(), "C-" + tag, "买家-" + tag, T1);
      sql.execute(
          "insert into order_fulfillment_line_progress(id,tenant_id,order_id,order_line_id,"
              + "ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,"
              + "observed_at,version,created_at,updated_at)"
              + " values(?,?::uuid,?::uuid,?::uuid,?::numeric,0,0,?::numeric,?::timestamptz,"
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
                rmaId, null, "CARRIER", "FedEx", "RT-" + tag, "外箱一角压瘪", T1,
                List.of(
                    new ReturnReceiptModels.ReturnReceiptLineDraft(
                        rmaLineId, "SKU-X", "SN-" + UUID.randomUUID(), null,
                        new BigDecimal("2"), "DAMAGED", goodsCondition, "外箱压损")),
                List.of()),
            uid));
    as(uid, tid, () -> receipts.post(draft.head().id(),
        new ReturnReceiptModels.ReturnReceiptPost(null, draft.head().version()), uid));

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
    String skuCode = "SKU-" + tag;

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
                    new QualityTemplateItemDraft("ACCESSORY-1", "配件清单", "ACCESSORY",
                        new BigDecimal("0.300000"), true, "", ""))),
            uid));
    as(uid, tid, () -> quality.activateTemplate(template.template().id(), uid));

    // ⚠️ 刻意**不**在这里开质检单：待检队列要断言的恰恰是「还没开单」的那批货。
    //   需要质检单的地方显式调 open(...)。
    return new Fixture(categoryId, template.template().id(), postingId, skuCode);
  }

  /** 开一张质检单（夹具刻意不开：队列断言要的是「还没开单」的那批货）。 */
  UUID open(UUID tid, UUID uid, UUID postingId) {
    return as(uid, tid, () -> quality.createInspection(
        new QualityInspectionCreate(postingId, null), uid).head().id());
  }

  <T> T as(UUID uid, UUID tid, java.util.function.Supplier<T> work) {
    return access.run(actor(uid, tid), "trade.aftersales.write", work);
  }

  // ==========================================================================
  // 断言一：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见() throws Exception {
    Fixture fa = seed(a, userA, "ISO-A", "OPENED_UNDAMAGED");
    // 每轮都新建实体：复用上一轮的记录，第二轮断言通过只能说明「同一条记录没变」。
    Fixture fb = seed(b, userB, "ISO-B", "OPENED_UNDAMAGED");

    // ---- 待检队列互不可见（此时两单都还没开质检单，队列里各有一条）----
    assertThat(asA(() -> quality.pendingQueue(null, null, null)).rows())
        .extracting(QualityPendingRow::postingId).containsExactly(fa.postingId());
    assertThat(asB(() -> quality.pendingQueue(null, null, null)).rows())
        .extracting(QualityPendingRow::postingId).containsExactly(fb.postingId());

    // 开单之后队列自然清空，两个租户各自只清自己那条。
    UUID ia = open(a, userA, fa.postingId());
    UUID ib = open(b, userB, fb.postingId());
    assertThat(asA(() -> quality.pendingQueue(null, null, null)).rows()).isEmpty();
    assertThat(asB(() -> quality.pendingQueue(null, null, null)).rows()).isEmpty();

    // ---- 列表互不可见（列表页 + 汇总口径）----
    assertThat(asA(() -> quality.listInspections(query())).total()).isEqualTo(1);
    assertThat(asA(() -> quality.listInspections(query())).rows())
        .extracting(QualityInspectionRow::id).containsExactly(ia);
    assertThat(asB(() -> quality.listInspections(query())).rows())
        .extracting(QualityInspectionRow::id).containsExactly(ib);

    // 类目与模板互不可见。
    assertThat(asA(() -> quality.listCategories(null, null, null, null)).rows())
        .extracting(QualityCategoryRow::id).containsExactly(fa.categoryId());
    assertThat(asA(() -> quality.listTemplates(null, null, null, null, null, null)).rows())
        .extracting(QualityTemplateRow::id).containsExactly(fa.templateId());

    // ⚠️ **按 id 直达也不行**：A 拿着 B 的质检单 id / 待检分录 id / 模板 id 读，
    //   得到的是与「不存在」完全相同的错误（不告诉调用方「这个 ID 存在但不属于你」）。
    assertThatThrownBy(() -> asA(() -> quality.inspectionDetail(ib)))
        .hasStackTraceContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> asA(() -> quality.verdictPreview(fb.postingId())))
        .hasStackTraceContaining("不存在或不属于当前租户");
    assertThatThrownBy(() -> asA(() -> quality.templateDetail(fb.templateId())))
        .hasStackTraceContaining("不存在或不属于当前租户");

    // 判定入口也拦住：不能替别人下结论。
    assertThatThrownBy(() -> asA(() -> quality.conclude(
        ib, new QualityInspectionConclude("RESALABLE", "越权判定", 0L), userA)))
        .hasStackTraceContaining("不存在或不属于当前租户");

    // HTTP 层同样如此（按 id 直达的 URL 是最容易被猜到的那种）。
    http.perform(get("/trade/return-quality/inspections/{id}", ib)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().is4xxClientError());
  }

  // ==========================================================================
  // 断言二：伪造 tenant header 无效
  // ==========================================================================

  @Test
  void 伪造租户头无效() throws Exception {
    Fixture fa = seed(a, userA, "FORGE-A", "OPENED_UNDAMAGED");
    Fixture fb = seed(b, userB, "FORGE-B", "OPENED_UNDAMAGED");

    // 带着 **B 的令牌** + **伪造的 A 的 X-Tenant-Id**：读到的必须是 B 的数据，
    // 不是 A 的 —— 头改不动 identity 解析出来的可信租户。
    // ---- 待检队列：带着 **B 的令牌** + **伪造的 A 的 X-Tenant-Id**，读到的是 B 的数据 ----
    String queue =
        http.perform(get("/trade/return-quality/queue")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(queue).contains(fb.postingId().toString()).doesNotContain(fa.postingId().toString());

    // 开单之后队列清空，下面两段列表断言才有东西可比。
    UUID ia = open(a, userA, fa.postingId());
    UUID ib = open(b, userB, fb.postingId());

    var page =
        http.perform(get("/trade/return-quality/inspections")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(page).contains(ib.toString()).doesNotContain(ia.toString());

    // 反过来：带着 A 的令牌 + 伪造的 B 的头，读到的仍然是 A 的数据。
    String page2 =
        http.perform(get("/trade/return-quality/inspections")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(page2).contains(ia.toString()).doesNotContain(ib.toString());

    // 直接在 RLS 上验证：绑定 A 的租户之后，B 的行在库里**读不出来**，
    // 绕过服务层也一样（FORCE RLS 对表 owner 之外的角色同样生效，这里跑的是 trade_app）。
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) from return_quality_inspection where tenant_id=?::uuid and id=?::uuid",
            b, ib))
        .get(0, Long.class)).as("RLS 之下看不到别的租户的行").isZero();
  }

  // ==========================================================================
  // 断言三：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 连接池归还后不串租户() throws Exception {
    Fixture fa = seed(a, userA, "POOL-A", "OPENED_UNDAMAGED");
    Fixture fb = seed(b, userB, "POOL-B", "OPENED_UNDAMAGED");
    UUID ia = open(a, userA, fa.postingId());
    UUID ib = open(b, userB, fb.postingId());

    // 交替借还：同一张质检单反复以两个租户的身份读，读到的永远是**当前绑定**的租户。
    for (int i = 0; i < 3; i++) {
      assertThat(asA(() -> quality.inspectionDetail(ia)).head().skuCode())
          .isEqualTo(fa.skuCode());
      assertThat(asB(() -> quality.inspectionDetail(ib)).head().skuCode())
          .isEqualTo(fb.skuCode());
      // 归还之后再读另一个租户的 id：仍然读不到。
      assertThatThrownBy(() -> asA(() -> quality.inspectionDetail(ib)))
          .hasStackTraceContaining("不存在或不属于当前租户");
      assertThatThrownBy(() -> asB(() -> quality.inspectionDetail(ia)))
          .hasStackTraceContaining("不存在或不属于当前租户");
    }

    // 判定也在交替中完成，两个租户的结论互不污染。
    var resultsA = asA(() -> quality.templateDetail(fa.templateId())).items();
    asA(() -> quality.recordResults(ia,
        new QualityInspectionUpdate(resultsA.stream()
            .map(x -> new QualityCheckResultDraft(x.id(), "PASS", "", null)).toList(),
            asA(() -> quality.inspectionDetail(ia)).head().version()),
        userA));
    asA(() -> quality.conclude(ia,
        new QualityInspectionConclude("RESALABLE", "A 租户判可再销售",
            asA(() -> quality.inspectionDetail(ia)).head().version()),
        userA));

    var resultsB = asB(() -> quality.templateDetail(fb.templateId())).items();
    asB(() -> quality.recordResults(ib,
        new QualityInspectionUpdate(resultsB.stream()
            .map(x -> new QualityCheckResultDraft(x.id(), "FAIL", "缺配件", null)).toList(),
            asB(() -> quality.inspectionDetail(ib)).head().version()),
        userB));
    asB(() -> quality.conclude(ib,
        new QualityInspectionConclude("DEFECTIVE", "B 租户判残次",
            asB(() -> quality.inspectionDetail(ib)).head().version()),
        userB));

    // 两条结论各自独立：判定 + 残值 + 指令都不串。
    var da = asA(() -> quality.inspectionDetail(ia));
    var db = asB(() -> quality.inspectionDetail(ib));
    assertThat(da.head().verdict()).isEqualTo("RESALABLE");
    assertThat(db.head().verdict()).isEqualTo("DEFECTIVE");
    assertThat(da.instruction().instructionType()).isEqualTo("MOVE_TO_SELLABLE");
    assertThat(db.instruction().instructionType()).isEqualTo("MOVE_TO_DEFECTIVE");
    assertThat(asA(() -> quality.listInspections(query())).rows())
        .filteredOn(r -> r.verdict() != null)
        .extracting(QualityInspectionRow::verdict).containsExactly("RESALABLE");
  }

  // ==========================================================================
  // 附：本模块的隔离面（证明断言跑在真表上）
  // ==========================================================================

  @Test
  void 伪造租户头不能写也不能判() throws Exception {
    Fixture fa = seed(a, userA, "WRITE-A", "OPENED_UNDAMAGED");
    Fixture fb = seed(b, userB, "WRITE-B", "OPENED_UNDAMAGED");
    UUID ia = open(a, userA, fa.postingId());
    UUID ib = open(b, userB, fb.postingId());

    // B 的令牌 + 伪造的 A 的头去**写** A 的质检单：写的是 B 自己那张（或根本不存在），
    // 绝不会落到 A 的行上。
    String body =
        "{\"verdict\":\"DEFECTIVE\",\"conclusionNote\":\"越权改判\",\"expectedVersion\":0}";
    http.perform(MockMvcRequestBuilders.post("/trade/return-quality/inspections/{id}/conclude", ia)
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().is4xxClientError());

    // A 的质检单仍然是草稿。
    assertThat(asA(() -> quality.inspectionDetail(ia)).head().inspectionStatus())
        .isEqualTo("DRAFT");
    assertThat(asA(() -> sql.fetchOne(
            "select count(*) from return_quality_disposition_instruction"
                + " where tenant_id=?::uuid and inspection_id=?::uuid", a, ia))
        .get(0, Long.class)).as("越权请求不该产生指令").isZero();

    // 反过来，A 也不能替 B 判。
    http.perform(MockMvcRequestBuilders.post("/trade/return-quality/inspections/{id}/conclude", ib)
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"verdict\":\"RESALABLE\",\"conclusionNote\":\"越权改判\",\"expectedVersion\":0}"))
        .andExpect(status().is4xxClientError());
    assertThat(asB(() -> quality.inspectionDetail(ib)).head().inspectionStatus())
        .isEqualTo("DRAFT");
  }

  private static QualityInspectionListQuery query() {
    return new QualityInspectionListQuery(
        0, 20, null, null, null, null, null, null, null, null);
  }
}
