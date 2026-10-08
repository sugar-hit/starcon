package com.starcon.warehouse;

import static com.starcon.warehouse.CustomsDeclarationModels.*;
import static com.starcon.warehouse.DutyTaxModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 10.5.1.3 关税与税费。
 *
 * <p>本测试就是 <b>V90 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，
 * 逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不是直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link CustomsDeclarationIntegrationTest} 一致（同样的 Testcontainers、
 * 同样的 fake identity server、同样的按租户派生 UUID、同样的 TRUNCATE 清场），
 * 派生 id 的前缀不同，避免夹具互相污染。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DutyTaxIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("inventory")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-dutytax-test-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
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
        s.execute("create role warehouse_app login password 'test-only' nosuperuser nobypassrls");
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
            byte[] body =
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, null, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "warehouse_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired DutyTaxService service;
  @Autowired CustomsDeclarationService declarations;
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

  /**
   * 真实报关单 id。报关单的 id 由 {@link CustomsDeclarationService#create} 生成 UUID
   * （10.5.1.1 用发号器发 declaration_code，但主键是随机 UUID），所以夹具必须**记下真实 id**，
   * 不能用本地派生 id 去引用 —— 那是本测试第一版栽的坑。
   */
  final Map<UUID, List<UUID>> declarationIds = new java.util.concurrent.ConcurrentHashMap<>();

  /** 第 slot 张报关单的真实 id（slot 0/1/2）。 */
  UUID declId(UUID tenant, int slot) {
    return declarationIds.get(tenant).get(slot);
  }

  /**
   * 每个租户一整套夹具：法人、SKU、国内仓、海外仓、质量状态、**10.4.1.1 头程发运单**
   * + **10.5.1.1 报关单与明细** + **US 关税/增值税税率**。
   *
   * <p>税率是本模块的**输入数据**（蓝图：「输入：HS 编码税率表；目的国税则」），
   * 所以它必须落库而不是写死在代码里 —— 每个租户各配一套，才能顺带验证税率也按租户隔离。
   */
  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate duty_tax_payment, duty_tax_variance, duty_tax_estimate_line, duty_tax_estimate,"
              + " duty_tax_rate,"
              + " customs_declaration_event, customs_result, customs_submission, customs_document,"
              + " customs_declaration_line, customs_declaration,"
              + " first_mile_shipment_event, first_mile_shipment_leg_receipt,"
              + " first_mile_shipment_line, first_mile_shipment,"
              + " inventory_quality_status, warehouse, sku_ref, legal_entity_ref cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.inventory.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1), tenant, "LE-1", "主体一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 2), tenant, "SKU-1", "商品一");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 4), tenant, "WH-SH", "上海仓", "CN", "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 5), tenant, "WH-LA", "洛杉矶海外仓", "US", "America/Los_Angeles");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 6), tenant, "SELLABLE", "可售", "SELLABLE");
            for (int slot = 0; slot < 3; slot++) {
              seedShipment(tenant, slot);
              seedDeclaration(tenant, slot);
            }
            seedRates(tenant);
            return null;
          });
    }
  }

  /** 10.4.1.1 头程发运单 + 明细行（报关单的来源）。 */
  private void seedShipment(UUID tenant, int slot) {
    sql.execute(
        "insert into first_mile_shipment(id,tenant_id,shipment_code,source_type,source_ref_code,"
            + "legal_entity_id,origin_warehouse_id,destination_warehouse_id,destination_country_code,"
            + "destination_name,transport_mode,carrier_name,booking_no,vessel_or_flight_no,incoterm,"
            + "risk_bearer,currency,freight_cost,insurance_cost,duty_estimate,handling_cost,"
            + "customs_broker,status,created_by,dispatched_at)"
            + " values(?,?,?,'SUPPLIER_PURCHASE','PO-1',?,?,?,'US','洛杉矶海外仓','SEA','COSCO',"
            + "'BK-1','Vessel-1','FOB','BUYER','USD',0,0,0,0,'代理行','DISPATCHED',?,now())",
        idFor(tenant, 10 + slot * 10), tenant, "FMS-" + (slot + 1), idFor(tenant, 1), idFor(tenant, 4),
        idFor(tenant, 5), user);
    for (int i = 1; i <= 2; i++) {
      sql.execute(
          "insert into first_mile_shipment_line(id,tenant_id,shipment_id,line_no,sku_id,"
              + "quality_status_id,planned_quantity,shipped_quantity,declared_item_name,hs_code,brand,"
              + "origin_country_code,declared_unit_value,declared_value,gross_weight_kg,net_weight_kg,"
              + "volume_cbm,carton_count)"
              + " values(?,?,?,?,?,?,?,?,'棉质衬衫','610910','A','CN',10,1000,120,100,0.5,10)",
          idFor(tenant, 100 + slot * 10 + i), tenant, idFor(tenant, 10 + slot * 10), i, idFor(tenant, 2),
          idFor(tenant, 6), 100, 100);
      sql.execute(
          "insert into first_mile_shipment_leg_receipt(id,tenant_id,shipment_id,line_id,"
              + "transit_leg_id,source_reference,sku_id,quantity,shipped_at,registered_at,registered_by)"
              + " values(?,?,?,?,gen_random_uuid(),?,?,100,now(),now(),?)",
          idFor(tenant, 200 + slot * 10 + i), tenant, idFor(tenant, 10 + slot * 10),
          idFor(tenant, 100 + slot * 10 + i),
          "LEG-" + tenant + "-" + slot + "-" + i, idFor(tenant, 2), user);
    }
  }

  /**
   * 10.5.1.1 报关单（10.5.1.3 的直接上游）—— <b>走真实的上游服务</b>建，不手写 SQL 抄状态机。
   *
   * <p>这里刻意复用 {@link CustomsDeclarationService} 而不是直接 INSERT：
   * V85 把「人工复核闸」「三单据齐备才能提交」「放行要结果回执」都钉在 DB 触发器里，
   * 手写 SQL 抄状态机会绕过这些纪律，夹具就变成了一个上游并不存在的世界。
   *
   * <p>第 1 张（slot 0）走完整流程到「已放行」，第 2、3 张停在「已提交」/「已复核」——
   * 因为**一张报关单只估一次税**（UNIQUE(tenant_id,declaration_id)），
   * 多个用例需要各自独立的估算单，必须备三张报关单。
   *
   * <p>注意 V85 的裁决：{@code submitted_at} 是<b>历史痕迹，打上就永不消失</b>，
   * 所以「已报关 / 未报关」必须看 {@code submitted_at} 而不能看 {@code status}。
   */
  private void seedDeclaration(UUID tenant, int slot) {
    List<CustomsLineRequest> lines = new ArrayList<>();
    for (int i = 1; i <= 2; i++) {
      lines.add(new CustomsLineRequest(
          idFor(tenant, 100 + slot * 10 + i),
          "棉质衬衫 100% 棉",
          "610910",
          "MANUAL_COMPLETE",
          Map.of("fiber", "100% 棉", "weave", "平纹"),
          "100% 棉",
          "女装衬衫",
          "A",
          "SH-100",
          "CN",
          new BigDecimal("100"),
          "PCS",
          new BigDecimal("10.0000"),
          new BigDecimal("1000.0000"),
          new BigDecimal("120.0000"),
          new BigDecimal("100.0000"),
          new BigDecimal("0.500000"),
          10));
    }
    CustomsCreateRequest request =
        new CustomsCreateRequest(
            idFor(tenant, 10 + slot * 10),
            idFor(tenant, 1),
            "GENERAL_TRADE",
            "上海星创电子商务有限公司",
            "CN",
            "Pacific Trading LLC",
            "US",
            "",
            "",
            "上海报关行",
            "BR-001",
            "张三",
            "US",
            "US-LAX",
            "上海港",
            "洛杉矶港",
            "SEA",
            "BL-2026-00000" + (slot + 1),
            "USD",
            "CNY",
            new BigDecimal("7.1000000000"),
            6,
            LocalDate.of(2026, 10, 1),
            "首批进口",
            lines);
    UUID id = declarations.create(tenant, request).id();
    declarationIds.computeIfAbsent(tenant, k -> new ArrayList<>()).add(id);
    declarations.submitForReview(id, new CustomsSubmitForReviewRequest("请复核"));
    declarations.review(id, new CustomsReviewRequest("申报资料逐项核对无误"));
    if (slot == 2) {
      // 第 3 张停在「已复核」：够本模块建估算单用，且刻意留下一张**未报关**的单
      // （submitted_at 为空）—— 用来验证「已报关 / 未报关」的判据不靠 status。
      return;
    }
    for (var type : CustomsDocumentType.values()) {
      declarations.generateDocument(id, new CustomsGenerateDocumentRequest(type.name(), ""));
      UUID docId =
          declarations.documents(id).stream()
              .filter(d -> d.documentType().equals(type.name()))
              .findFirst()
              .orElseThrow()
              .id();
      declarations.finalizeDocument(docId, new CustomsFinalizeDocumentRequest(""));
    }
    declarations.submit(id, new CustomsSubmitRequest("BR-REF-" + (slot + 1), "上海报关行", "ACCEPTED", "已受理"));
    if (slot == 0) {
      // resultAt 传 null 用服务默认的 now()：V85 钉死「结果时间不得早于提交时间」，
      // 而提交时间是建夹具那一刻的 now()，写一个固定的历史时刻会被这条纪律挡下。
      declarations.recordResult(id, new CustomsResultRequest(
          "CLEARED", "RL-1", "查验通过放行", null,
          "MANUAL_ENTRY", "CR-1"));
    }
  }

  /**
   * 税率表（蓝图输入：HS 编码税率表 / 目的国税则）—— 真实落库数据。
   *
   * <ul>
   *   <li>US 关税 3.25%，HS 前缀 {@code 6109}（章级，8 位子目查不到时按章取）—— 行 1 命中
   *   <li>US 关税 5.00%，HS 前缀 {@code 610910}（更长的前缀，匹配时优先）—— 用于验证最长前缀优先
   *   <li>US 增值税 10%，国别（VAT 不按 HS 细分）—— 必须配，否则锁不了估算单
   * </ul>
   */
  private void seedRates(UUID tenant) {
    sql.execute(
        "insert into duty_tax_rate(id,tenant_id,destination_country_code,rate_scope,hs_code_prefix,"
            + "tax_kind,ad_valorem_rate,effective_from,tax_source,source_system,source_reference,created_by)"
            + " values(?,?,'US','HS','6109','DUTY',0.0325,'2026-01-01','MANUAL','MANUAL_ENTRY','HTS-2026-CH61',?)",
        idFor(tenant, 500), tenant, user);
    sql.execute(
        "insert into duty_tax_rate(id,tenant_id,destination_country_code,rate_scope,hs_code_prefix,"
            + "tax_kind,ad_valorem_rate,effective_from,tax_source,source_system,source_reference,created_by)"
            + " values(?,?,'US','HS','610910','DUTY',0.05,'2026-01-01','MANUAL','MANUAL_ENTRY','HTS-2026-610910',?)",
        idFor(tenant, 501), tenant, user);
    sql.execute(
        "insert into duty_tax_rate(id,tenant_id,destination_country_code,rate_scope,hs_code_prefix,"
            + "tax_kind,ad_valorem_rate,effective_from,tax_source,source_system,source_reference,created_by)"
            + " values(?,?,'US','COUNTRY','','VAT',0.10,'2026-01-01','MANUAL','MANUAL_ENTRY','US-IRB-2026',?)",
        idFor(tenant, 502), tenant, user);
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.inventory.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.inventory.update", work);
  }

  /** 估算请求：DDU（买方承担），完税价 2000 = 报关单申报货值。 */
  private DutyEstimateCreateRequest estimateRequest(UUID tenant, int slot) {
    return estimateRequest(tenant, slot, "DDU", "BUYER");
  }

  private DutyEstimateCreateRequest estimateRequest(UUID tenant, int slot, String incoterm, String bearer) {
    return new DutyEstimateCreateRequest(
        declId(tenant, slot),
        incoterm,
        bearer,
        idFor(tenant, 1),
        "US",
        "USD",
        "CNY",
        new BigDecimal("7.1000000000"),
        6,
        LocalDate.of(2026, 10, 1),
        new BigDecimal("2000.0000"),
        new BigDecimal("2000.0000"),
        new BigDecimal("100.0000"),
        new BigDecimal("20.0000"),
        new BigDecimal("0.0000"),
        LocalDate.of(2026, 10, 1),
        "首批进口");
  }

  private UUID estimated(UUID tenant) {
    return estimated(tenant, 0);
  }

  private UUID estimated(UUID tenant, int slot) {
    return asA(() -> service.create(tenant, estimateRequest(tenant, slot))).id();
  }

  private DutyPaymentCreateRequest payment(
      String taxKind, String amount, String reference, String payer) {
    return payment(taxKind, amount, reference, payer, null);
  }

  private DutyPaymentCreateRequest payment(
      String taxKind, String amount, String reference, String payer, String cause) {
    return new DutyPaymentCreateRequest(
        taxKind,
        "PAYMENT",
        new BigDecimal(amount),
        "USD",
        payer,
        reference,
        OffsetDateTime.parse("2026-10-05T10:00:00+08:00"),
        "Pacific Trading LLC",
        "MANUAL_ENTRY",
        "SR-" + reference,
        null,
        cause,
        cause == null ? null : "已按 " + cause + " 归因，待复核确认",
        null,
        null,
        "首批到岸");
  }

  // ================================================================ 不变量 1：按 HS 编码算税 + 金额精度

  /**
   * 逐行按 HS 编码税率算关税，金额 HALF_UP 到分。
   *
   * <p>本例的算式（报关单两行申报货值各 1000，完税价 2000 全部落在第一票）：
   *
   * <pre>
   * 完税价 2000 → 行1 1000.00 / 行2 1000.00
   * 运费 100 → 行1 50.00 / 行2 50.00
   * 保险 20  → 行1 10.00 / 行2 10.00
   * 关税税率命中 **最长前缀 610910 = 5.00%**（不是章级 6109 的 3.25%）
   *   行1 关税 = 1000.00 × 5% = 50.00
   *   行2 关税 = 1000.00 × 5% = 50.00   → 合计 100.00
   * 增值税 10%，计税基础 = 完税价 + 关税 + 运费 + 保险
   *   行1 = (1000 + 50 + 50 + 10) × 10% = 111.00
   *   行2 = (1000 + 50 + 50 + 10) × 10% = 111.00   → 合计 222.00
   * 税费合计 = 100.00 + 222.00 = 322.00
   * </pre>
   */
  @Test
  void dutyIsComputedPerLineFromHsCodeWithHalfUpRounding() {
    UUID id = estimated(a);
    var detail = asA(() -> service.detail(a, id));
    assertThat(detail.estimate().status()).isEqualTo("ESTIMATED");
    assertThat(detail.lines()).hasSize(2);

    // 最长前缀优先：610910（5%）压过章级 6109（3.25%）。
    assertThat(detail.lines()).allSatisfy(line -> {
      assertThat(line.hsCode()).isEqualTo("610910");
      assertThat(line.dutyRatePrefix()).isEqualTo("610910");
      assertThat(line.dutyAdValoremRate()).isEqualByComparingTo("0.05");
      assertThat(line.rateMatchStatus()).isEqualTo("MATCHED");
      assertThat(line.vatRateMatched()).isTrue();
    });

    // 逐行分摊与税额：HALF_UP 到分，逐项断言。
    var line1 = detail.lines().get(0);
    assertThat(line1.lineCustomsValue()).isEqualByComparingTo("1000.00");
    assertThat(line1.allocatedFreight()).isEqualByComparingTo("50.00");
    assertThat(line1.allocatedInsurance()).isEqualByComparingTo("10.00");
    assertThat(line1.estimatedDutyAmount()).isEqualByComparingTo("50.00");
    assertThat(line1.vatBaseAmount()).isEqualByComparingTo("1110.00");
    assertThat(line1.estimatedVatAmount()).isEqualByComparingTo("111.00");
    assertThat(line1.estimatedLineTotalTax()).isEqualByComparingTo("161.00");

    // 单头 = 逐行合计（DB 延迟约束也在提交时核对过）。
    var head = detail.estimate();
    assertThat(head.estimatedDutyAmount()).isEqualByComparingTo("100.00");
    assertThat(head.estimatedVatAmount()).isEqualByComparingTo("222.00");
    assertThat(head.estimatedTotalTax()).isEqualByComparingTo("322.00");

    // 分摊合计精确等于单头金额（最后一行吃余数，所以不会有半分钱对不上）。
    var allocatedFreight = detail.lines().stream()
        .map(DutyEstimateLineRow::allocatedFreight)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(allocatedFreight).isEqualByComparingTo("100.00");
  }

  /** HALF_UP 到分：0.005 这类正好卡在中间的数必须向上进位，而不是银行家舍入。 */
  @Test
  void roundingIsHalfUpToCentsNotBankersRounding() {
    // 完税价 10.10 × 5% = 0.505 → HALF_UP 到分 = 0.51（不是银行家舍入的 0.50）
    assertThat(DutyTaxService.round2(new BigDecimal("0.505"))).isEqualByComparingTo("0.51");
    assertThat(DutyTaxService.round2(new BigDecimal("0.5050"))).isEqualByComparingTo("0.51");
    assertThat(DutyTaxService.round2(new BigDecimal("32.495"))).isEqualByComparingTo("32.50");
    assertThat(DutyTaxService.round2(new BigDecimal("-0.505"))).isEqualByComparingTo("-0.51");
  }

  /** 税率按**生效期**取：税则改了，历史估算要能按当时的税率算出来。 */
  @Test
  void rateIsLookedUpByEffectiveDate() {
    // 单行 HS 610910 命中 610910 优先，其次 6109，最后没有就 NO_MATCH。
    var rates = asA(() -> service.rates(a, new DutyRateQuery(
        "US", "DUTY", "HS", null, null, LocalDate.of(2026, 6, 1), null, "EFFECTIVE_FROM", 0, 50)));
    assertThat(rates).extracting(DutyRateRow::hsCodePrefix).contains("610910", "6109");
    // 2025 年的生效日上，两条都还没生效 → 查不到
    assertThat(asA(() -> service.rates(a, new DutyRateQuery(
            "US", "DUTY", "HS", null, null, LocalDate.of(2025, 6, 1), null, "EFFECTIVE_FROM", 0, 50))))
        .isEmpty();
  }

  // ================================================================ 不变量 2：没匹配到税率不许锁定

  @Test
  void unmatchedDutyRateBlocksLock() {
    // DE 目的国没配任何税率 → 关税 NO_MATCH + VAT 未配
    UUID id = asA(() -> service.create(a, new DutyEstimateCreateRequest(
        declId(a, 0), "DDU", "BUYER", idFor(a, 1), "DE", "USD", "CNY",
        new BigDecimal("7.1"), 6, LocalDate.of(2026, 10, 1),
        new BigDecimal("2000"), new BigDecimal("2000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        LocalDate.of(2026, 10, 1), ""))).id();
    var detail = asA(() -> service.detail(a, id));
    assertThat(detail.lines()).allSatisfy(line -> {
      assertThat(line.rateMatchStatus()).isEqualTo("NO_MATCH");
      assertThat(line.vatRateMatched()).isFalse();
      assertThat(line.estimatedDutyAmount()).isEqualByComparingTo("0");
      // 备注必须讲清「按 0 税估算」，不能让界面上凭空猜
      assertThat(line.notes()).contains("匹配到");
    });
    assertThat(detail.estimate().unmatchedRateLines()).isEqualTo(2);

    assertThatThrownBy(() -> asA(() -> service.lock(a, id, new DutyLockRequest(""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没匹配到关税税率");
  }

  /** DB 层同样要挡住：绕过服务层直接把状态改成 LOCKED 也必须失败。 */
  @Test
  void dbBlocksLockWhenRateUnmatched() {
    UUID id = asA(() -> service.create(a, new DutyEstimateCreateRequest(
        declId(a, 0), "DDU", "BUYER", idFor(a, 1), "DE", "USD", "CNY",
        new BigDecimal("7.1"), 6, LocalDate.of(2026, 10, 1),
        new BigDecimal("2000"), new BigDecimal("2000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        LocalDate.of(2026, 10, 1), ""))).id();
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update duty_tax_estimate set status='LOCKED',locked_at=now(),locked_by=?"
                              + " where id=?",
                          user,
                          id);
                      return null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("匹配到关税税率");
  }

  /** 0% 税率是合法配置（英国零税率），不能被当成「没配」—— 配了 0% 就该能锁。 */
  @Test
  void zeroPercentRateIsAValidConfiguration() {
    // GB：关税 0%（零税率商品）+ 增值税 0%
    asA(() -> service.createRate(a, new DutyRateCreateRequest(
        "GB", "HS", "610910", "DUTY", new BigDecimal("0"), null, "PCS", false, "",
        LocalDate.of(2026, 1, 1), null, "MANUAL", true, "MANUAL_ENTRY", "UK-ZERO", "")));
    asA(() -> service.createRate(a, new DutyRateCreateRequest(
        "GB", "COUNTRY", "", "VAT", new BigDecimal("0"), null, "PCS", false, "",
        LocalDate.of(2026, 1, 1), null, "MANUAL", true, "MANUAL_ENTRY", "UK-ZERO-VAT", "")));

    // 目的国按 GB 建估算单：请求里显式给 GB（服务取「请求的目的国」优先于报关单的），
    // 而不是去改已放行的报关单 —— V85 钉死已放行之后不允许再动申报资料。
    UUID id = asA(() -> service.create(a, new DutyEstimateCreateRequest(
        declId(a, 0), "DDU", "BUYER", idFor(a, 1), "GB", "USD", "CNY",
        new BigDecimal("7.1"), 6, LocalDate.of(2026, 10, 1),
        new BigDecimal("2000"), new BigDecimal("2000"), new BigDecimal("100"), new BigDecimal("20"),
        BigDecimal.ZERO, LocalDate.of(2026, 10, 1), ""))).id();
    var detail = asA(() -> service.detail(a, id));
    assertThat(detail.lines()).allSatisfy(line -> {
      assertThat(line.rateMatchStatus()).isEqualTo("MATCHED");
      assertThat(line.vatRateMatched()).isTrue();
      assertThat(line.estimatedDutyAmount()).isEqualByComparingTo("0");
      assertThat(line.estimatedVatAmount()).isEqualByComparingTo("0");
    });
    // 0% 税率配齐了 → 允许锁定
    assertThat(asA(() -> service.lock(a, id, new DutyLockRequest("零税率批次"))).status())
        .isEqualTo("LOCKED");
  }

  // ================================================================ 不变量 3：DDU 下卖方成本不含税

  @Test
  void dduExcludesTaxFromSellerLandedCost() {
    UUID ddu = asA(() -> service.create(a, estimateRequest(a, 0, "DDU", "BUYER"))).id();
    var dduHead = asA(() -> service.detail(a, ddu)).estimate();
    // 基础 = 货值 2000 + 运费 100 + 保险 20 + 其他 0 = 2120.00
    assertThat(dduHead.landedCostTotal()).isEqualByComparingTo("2442.00"); // 2120 + 100 + 222
    assertThat(dduHead.landedCostSeller()).isEqualByComparingTo("2120.00"); // 不含关税与增值税

    // DDP：卖方承担 → 卖方到岸成本含全部税费
    UUID ddp = asA(() -> service.create(a, estimateRequest(a, 1, "DDP", "SELLER"))).id();
    var ddpHead = asA(() -> service.detail(a, ddp)).estimate();
    assertThat(ddpHead.landedCostSeller()).isEqualByComparingTo("2442.00");
    assertThat(ddpHead.landedCostSeller()).isEqualByComparingTo(ddpHead.landedCostTotal());
  }

  /** DDP 必须配 SELLER、DDU 必须配 BUYER：服务层讲人话，DB 层 CHECK 兜底。 */
  @Test
  void ddpMustBeSellerAndDduMustBeBuyer() {
    assertThatThrownBy(() -> asA(() -> service.create(a, estimateRequest(a, 0, "DDP", "BUYER"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DDP");
    assertThatThrownBy(() -> asA(() -> service.create(a, estimateRequest(a, 0, "DDU", "SELLER"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DDU");
    // DB 层：绕过服务层也必须被 CHECK 挡下
    UUID id = estimated(a);
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update duty_tax_estimate set incoterm='DDU',duty_bearer='SELLER' where id=?", id);
                      return null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** DB 层钉死：DDU 下把税算进卖方到岸成本必须被拒。 */
  @Test
  void dbBlocksDduSellerCostIncludingTax() {
    UUID id = estimated(a);
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update duty_tax_estimate set landed_cost_seller=landed_cost_total where id=?", id);
                      return null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("买方承担");
  }

  // ================================================================ 不变量 4：估算与实缴差异必须记录

  /** 蓝图加粗的硬要求：录实缴时**同时**写下差异快照，两者不可分开。 */
  @Test
  void paymentAlwaysProducesVarianceSnapshot() {
    UUID id = estimated(a);
    asA(() -> service.recordPayment(a, id,
        payment("DUTY", "120.0000", "PAY-DUTY-1", "BUYER", "HS_CLASSIFICATION")));

    var detail = asA(() -> service.detail(a, id));
    assertThat(detail.payments()).hasSize(1);
    assertThat(detail.payments().get(0).paidAmount()).isEqualByComparingTo("120.0000");
    assertThat(detail.payments().get(0).taxKind()).isEqualTo("DUTY");

    // 差异：估算 100.00，实缴 120.00，差额 +20.00，差异率 20%
    assertThat(detail.variances()).hasSize(1);
    var variance = detail.variances().get(0);
    assertThat(variance.taxKind()).isEqualTo("DUTY");
    assertThat(variance.estimatedAmount()).isEqualByComparingTo("100.00");
    assertThat(variance.actualAmount()).isEqualByComparingTo("120.00");
    assertThat(variance.varianceAmount()).isEqualByComparingTo("20.00");
    assertThat(variance.varianceRate()).isEqualByComparingTo("0.200000");
    assertThat(variance.varianceCause()).isEqualTo("HS_CLASSIFICATION");
    assertThat(variance.resolution()).isEqualTo("OPEN");

    // 头部的实缴合计与差异合计可对比
    assertThat(detail.estimate().paidDutyAmount()).isEqualByComparingTo("120.00");
    assertThat(detail.estimate().varianceTotal()).isEqualByComparingTo("20.00");
  }

  /** 绕过服务层只写缴款、不写差异 → DB 必须拒收（这就是蓝图那句「差异要记录」的强制力）。 */
  @Test
  void dbRejectsPaymentWithoutVariance() {
    UUID id = estimated(a);
    UUID declarationId = declId(a, 0);
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into duty_tax_payment(id,tenant_id,estimate_id,declaration_id,"
                              + "payment_sequence,tax_kind,paid_amount,currency,payer,payment_reference,"
                              + "paid_at,source_reference,created_by)"
                              + " values(gen_random_uuid(),?,?,?,1,'DUTY',50,'USD','BUYER','RAW-SQL-1',"
                              + "now(),'SR-RAW',?)",
                          a, id, declarationId, user);
                      return null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("没有对应的差异记录");
  }

  /**
   * 实缴与差异是 append-only，而且**两道防线都在**：
   *
   * <ul>
   *   <li>授权层：V90 对 warehouse_app {@code REVOKE UPDATE, DELETE}（本测试连的就是 warehouse_app，
   *       所以直接写 SQL 会先被权限拒掉）
   *   <li>触发器层：{@code duty_payment_append_only} / {@code duty_variance_append_only}
   *       —— 换一个仍然有权限的角色去改同样被挡
   * </ul>
   *
   * <p>两条都断言：只测一条的话，将来有人把 REVOKE 撤掉而触发器还在（或反过来），
   * 另一条防线就没有回归保护了。
   */
  @Test
  void paymentsAndVariancesAreAppendOnly() {
    UUID id = estimated(a);
    asA(() -> service.recordPayment(a, id, payment("DUTY", "120.0000", "PAY-AP-1", "BUYER")));

    // 防线一：授权层确实收回了 UPDATE / DELETE
    for (var table : List.of("duty_tax_payment", "duty_tax_variance")) {
      assertThat(
              asA(
                  () ->
                      sql.fetchOne(
                              "select has_table_privilege('warehouse_app',?,'UPDATE') u,"
                                  + "has_table_privilege('warehouse_app',?,'DELETE') d",
                              table,
                              table)))
          .satisfies(
              r -> {
                assertThat(r.get("u", Boolean.class)).as("%s 的 UPDATE 权限", table).isFalse();
                assertThat(r.get("d", Boolean.class)).as("%s 的 DELETE 权限", table).isFalse();
              });
    }

    // 防线二：绕过授权层（用超级用户直连），触发器仍然挡下改写
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      c.createStatement().execute("set app.current_tenant = '" + a + "'");
      assertThatThrownBy(
              () ->
                  c.createStatement()
                      .executeUpdate("update duty_tax_payment set paid_amount=1 where tenant_id='" + a + "'"))
          .hasMessageContaining("append-only");
      assertThatThrownBy(
              () ->
                  c.createStatement()
                      .executeUpdate("delete from duty_tax_variance where tenant_id='" + a + "'"))
          .hasMessageContaining("append-only");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 补缴再留一条新快照：既「第 1 次差多少」不丢，也不重复计入汇总。 */
  @Test
  void secondPaymentAddsNewVarianceSnapshot() {
    UUID id = estimated(a);
    asA(() -> service.recordPayment(a, id, payment("DUTY", "60.0000", "PAY-1", "BUYER")));
    asA(() -> service.recordPayment(a, id, payment("DUTY", "40.0000", "PAY-2", "BUYER")));

    var detail = asA(() -> service.detail(a, id));
    assertThat(detail.payments()).hasSize(2);
    assertThat(detail.variances()).hasSize(2);
    // 第 1 次快照：实缴 60 vs 估算 100 → 差 -40
    assertThat(detail.variances().get(0).paymentSequence()).isEqualTo(1);
    assertThat(detail.variances().get(0).varianceAmount()).isEqualByComparingTo("-40.00");
    // 第 2 次快照：累计实缴 100 vs 估算 100 → 差 0
    assertThat(detail.variances().get(1).paymentSequence()).isEqualTo(2);
    assertThat(detail.variances().get(1).actualAmount()).isEqualByComparingTo("100.00");
    assertThat(detail.variances().get(1).varianceAmount()).isEqualByComparingTo("0.00");

    // 汇总只取最新快照，不把两次实缴相加
    assertThat(detail.estimate().paidDutyAmount()).isEqualByComparingTo("100.00");
    assertThat(detail.estimate().varianceTotal()).isEqualByComparingTo("0.00");
  }

  /** 估 0 却真收到税：差异率无意义（除零），用 rate_calculable=false 如实标记。 */
  @Test
  void zeroEstimateWithRealPaymentMarksRateNotCalculable() {
    UUID id = asA(() -> service.create(a, new DutyEstimateCreateRequest(
        declId(a, 0), "DDU", "BUYER", idFor(a, 1), "DE", "USD", "CNY",
        new BigDecimal("7.1"), 6, LocalDate.of(2026, 10, 1),
        new BigDecimal("2000"), new BigDecimal("2000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        LocalDate.of(2026, 10, 1), ""))).id();
    asA(() -> service.recordPayment(a, id, payment("DUTY", "88.0000", "PAY-UNMATCHED", "BUYER")));

    var variance = asA(() -> service.detail(a, id)).variances().get(0);
    assertThat(variance.estimatedAmount()).isEqualByComparingTo("0");
    assertThat(variance.actualAmount()).isEqualByComparingTo("88.00");
    assertThat(variance.varianceAmount()).isEqualByComparingTo("88.00");
    assertThat(variance.rateCalculable()).isFalse();
    assertThat(variance.varianceRate()).isNull();
    // 自动初判为「税率没配」—— 蓝图点名的根因之一
    assertThat(variance.varianceCause()).isEqualTo("RATE_CONFIG");
  }

  /** 差异分析：蓝图那句「长期差异大说明 HS 归类或税率配置有误」的查询出口。 */
  @Test
  void varianceAnalysisAggregatesByCauseAndHsCode() {
    UUID id = estimated(a);
    asA(() -> service.recordPayment(a, id, payment("DUTY", "150.0000", "PAY-ANALYSIS", "BUYER", "HS_CLASSIFICATION")));
    UUID unmatched = asA(() -> service.create(a, new DutyEstimateCreateRequest(
        declId(a, 1), "DDU", "BUYER", idFor(a, 1), "DE", "USD", "CNY",
        new BigDecimal("7.1"), 6, LocalDate.of(2026, 10, 1),
        new BigDecimal("2000"), new BigDecimal("2000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        LocalDate.of(2026, 10, 1), ""))).id();
    assertThat(unmatched).isNotNull();

    var analysis = asA(() -> service.varianceAnalysis(a, "US", null, null));
    assertThat(analysis.estimateCount()).isEqualTo(1);
    assertThat(analysis.totalEstimated()).isEqualByComparingTo("100.00");
    assertThat(analysis.totalActual()).isEqualByComparingTo("150.00");
    assertThat(analysis.totalVariance()).isEqualByComparingTo("50.00");
    assertThat(analysis.absVarianceRate()).isEqualByComparingTo("0.500000");
    assertThat(analysis.byCause())
        .anySatisfy(c -> assertThat(c.dimension()).isEqualTo("HS_CLASSIFICATION"));
    // 领先指标要单独看：DE 那票的目的国是 DE，在 US 口径的分析里本来就不该出现，
    // 所以这里按**不限目的国**再查一次，才能证明「没配税率的 HS 码」这个出口真的有数据。
    assertThat(asA(() -> service.varianceAnalysis(a, null, null, null)).unmatchedRateByHsCode())
        .isNotEmpty()
        .allSatisfy(u -> assertThat(u.hsCode()).isEqualTo("610910"));
    assertThat(analysis.note()).contains("修归类");
  }

  // ================================================================ 不变量 5：锁定后不可改 + 作废

  @Test
  void lockedEstimateCannotChangeBasisOrBearer() {
    UUID id = estimated(a);
    asA(() -> service.lock(a, id, new DutyLockRequest("报价已发出")));

    assertThatThrownBy(() -> asA(() -> service.update(a, id, new DutyEstimateUpdateRequest(
        null, "DDP", null, null, null, null, null, null, null, "改承担方"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不允许改基础");
    assertThatThrownBy(() -> asA(() -> service.recalculate(a, id, LocalDate.of(2026, 10, 1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不允许重算");
    // DB 层也挡
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update duty_tax_estimate set customs_value=1 where id=?", id);
                      return null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("已锁定");
  }

  /** 已实缴的估算单不许作废：钱付出去了，作废会让「缴了多少税」在账上消失。 */
  @Test
  void paidEstimateCannotBeVoided() {
    UUID id = estimated(a);
    asA(() -> service.recordPayment(a, id, payment("DUTY", "100.0000", "PAY-VOID", "BUYER")));
    assertThatThrownBy(() -> asA(() -> service.voidEstimate(a, id, new DutyVoidRequest("算错了"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("已有实缴记录");
    // 作废后不能再录缴款
    UUID voided = estimated(a, 1);
    asA(() -> service.voidEstimate(a, voided, new DutyVoidRequest("重复估算")));
    assertThatThrownBy(() -> asA(() -> service.recordPayment(a, voided,
            payment("DUTY", "10.0000", "PAY-AFTER-VOID", "BUYER"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("已作废");
  }

  /** 一张报关单只估一次税。 */
  @Test
  void oneEstimatePerDeclaration() {
    estimated(a);
    assertThatThrownBy(() -> asA(() -> service.create(a, estimateRequest(a, 0))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // ================================================================ 不变量 6：税率表

  @Test
  void ratesRequireTraceableSource() {
    assertThatThrownBy(() -> asA(() -> service.createRate(a, new DutyRateCreateRequest(
        "US", "HS", "6201", "DUTY", new BigDecimal("0.1"), null, "PCS", false, "",
        LocalDate.of(2026, 1, 1), null, "MANUAL", true, "MANUAL_ENTRY", "", ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("税率出处");
    // 优惠税率必须有协定代码
    assertThatThrownBy(() -> asA(() -> service.createRate(a, new DutyRateCreateRequest(
        "US", "HS", "6201", "DUTY", new BigDecimal("0"), null, "PCS", true, "",
        LocalDate.of(2026, 1, 1), null, "MANUAL", true, "MANUAL_ENTRY", "REF", ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("协定代码");
    // 国别税率不能带 HS 前缀
    assertThatThrownBy(() -> asA(() -> service.createRate(a, new DutyRateCreateRequest(
        "US", "COUNTRY", "6201", "VAT", new BigDecimal("0.1"), null, "PCS", false, "",
        LocalDate.of(2026, 1, 1), null, "MANUAL", true, "MANUAL_ENTRY", "REF", ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有 HS 编码前缀");
  }

  /** 税率改了，已算出的明细行里抄的税率值不受影响 —— 「当时按 5% 算的」仍可回答。 */
  @Test
  void editingRateDoesNotRewriteHistoricalEstimateLines() {
    UUID id = estimated(a);
    assertThat(asA(() -> service.detail(a, id)).lines().get(0).dutyAdValoremRate())
        .isEqualByComparingTo("0.05");

    asA(() -> service.updateRate(a, idFor(a, 501), new DutyRateUpdateRequest(
        new BigDecimal("0.08"), null, null, null, null, null, null, "HTS-2026-610910-REV", null, null)));

    // 历史行仍写着 0.05
    assertThat(asA(() -> service.detail(a, id)).lines().get(0).dutyAdValoremRate())
        .isEqualByComparingTo("0.05");
    // 但税率表本身已经是 0.08
    var rate = asA(() -> service.rates(a, new DutyRateQuery(
        "US", "DUTY", "HS", "610910", null, null, null, "CREATED_AT", 0, 10))).get(0);
    assertThat(rate.adValoremRate()).isEqualByComparingTo("0.08");
  }

  // ================================================================ 不变量 7：多租户三断言

  @Test
  void twoTenantsCannotSeeEachOther() {
    asA(() -> service.create(a, estimateRequest(a, 0)));
    asB(() -> service.create(b, estimateRequest(b, 0)));

    assertThat(asA(() -> service.page(a, new DutyEstimateQuery(
        null, null, null, null, null, null, null, null, null, 0, 50))).rows())
        .hasSize(1);
    assertThat(asB(() -> service.page(b, new DutyEstimateQuery(
        null, null, null, null, null, null, null, null, null, 0, 50))).rows())
        .hasSize(1);

    // 知道 id 也读不到对方的估算单
    UUID aId = asA(() -> service.page(a, new DutyEstimateQuery(
        null, null, null, null, null, null, null, null, null, 0, 50))).rows().get(0).id();
    assertThatThrownBy(() -> asB(() -> service.detail(b, aId)))
        .isInstanceOf(ResponseStatusException.class);
    // 税率表也隔离：两个租户各配了一套 US 610910 税率，B 必须只看到自己那一条
    // （派生 id 随租户而不同，所以「看到的是不是自己的那条」是可断言的）。
    var aRates = asA(() -> service.rates(a, new DutyRateQuery(
        "US", "DUTY", "HS", "610910", null, null, null, "CREATED_AT", 0, 10)));
    var bRates = asB(() -> service.rates(b, new DutyRateQuery(
        "US", "DUTY", "HS", "610910", null, null, null, "CREATED_AT", 0, 10)));
    assertThat(aRates).singleElement().satisfies(r -> assertThat(r.id()).isEqualTo(idFor(a, 501)));
    assertThat(bRates).singleElement().satisfies(r -> assertThat(r.id()).isEqualTo(idFor(b, 501)));
    // 直接用对方的税率 id 改：查不到
    assertThatThrownBy(() -> asB(() -> service.updateRate(b, idFor(a, 501), new DutyRateUpdateRequest(
        new BigDecimal("0.99"), null, null, null, null, null, null, "HACK", null, null))))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID aId = asA(() -> service.create(a, estimateRequest(a, 0))).id();
    // 客户端伪造 X-Tenant-Id 头：端点根本不读它，租户只来自已认证成员关系
    http.perform(get("/warehouse/duty-taxes/{id}", aId)
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 列表也不受伪造头影响：tenant-b 自己一张估算单都没有，伪造 A 的头也必须是 0 ——
    // 如果代理或端点信了这个头，这里就会变成 1。
    http.perform(get("/warehouse/duty-taxes")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.total").value(0));
    // B 自己建一张后就是 1，仍然看不到 A 的那一张
    asB(() -> service.create(b, estimateRequest(b, 0)));
    http.perform(get("/warehouse/duty-taxes")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.total").value(1));
  }

  @Test
  void pooledConnectionDoesNotLeakTenant() {
    asA(() -> service.create(a, estimateRequest(a, 0)));
    asB(() -> service.create(b, estimateRequest(b, 0)));

    // 连接池固定 1 条连接：连续在两个租户间来回走，读的必须始终是当前租户的行。
    for (int i = 0; i < 5; i++) {
      assertThat(asA(() -> service.page(a, new DutyEstimateQuery(
          null, null, null, null, null, null, null, null, null, 0, 50)).rows()))
          .singleElement()
          .satisfies(r -> assertThat(r.declarationCode()).startsWith("CD-"));
      assertThat(asB(() -> service.page(b, new DutyEstimateQuery(
          null, null, null, null, null, null, null, null, null, 0, 50)).rows()))
          .singleElement();
    }
    // 缴款与差异表同样不串
    UUID aId = asA(() -> service.page(a, new DutyEstimateQuery(
        null, null, null, null, null, null, null, null, null, 0, 50))).rows().get(0).id();
    asA(() -> service.recordPayment(a, aId, payment("DUTY", "100.0000", "PAY-LEAK", "BUYER")));
    assertThat(asB(() -> sql.fetch("select * from duty_tax_payment where estimate_id=?", aId)))
        .isEmpty();
    assertThat(asB(() -> sql.fetch("select * from duty_tax_variance where estimate_id=?", aId)))
        .isEmpty();
  }

  // ================================================================ 端点与契约

  @Test
  void capabilitiesDeclareUndeliveredDependencies() {
    var caps = asA(() -> service.capabilities());
    assertThat(caps.statusOptions()).isNotEmpty();
    assertThat(caps.taxKinds()).extracting(DutyTaxKindOption::value).contains("DUTY", "VAT", "GST");
    assertThat(caps.dutyBearers()).extracting(DutyTaxBearerOption::value).contains("SELLER", "BUYER");
    assertThat(caps.dependencies())
        .anySatisfy(d -> {
          assertThat(d.value()).isEqualTo("HS_CODE_DICTIONARY");
          assertThat(d.available()).isFalse();
          assertThat(d.ownerModule()).isEqualTo("3.7.1.1");
        });
    assertThat(caps.dependencies()).anySatisfy(d -> {
      assertThat(d.value()).isEqualTo("TARIFF_TAX_BASIS");
      assertThat(d.ownerModule()).isEqualTo("16.3.1.1");
    });
    assertThat(caps.estimateVsPaymentNote()).contains("差异");
    assertThat(caps.zeroRateNote()).contains("0%");
  }

  @Test
  void endpointsReturnContractShapes() throws Exception {
    asA(() -> service.create(a, estimateRequest(a, 0)));
    http.perform(get("/warehouse/duty-taxes/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.taxKinds").isArray())
        .andExpect(jsonPath("$.dependencies[?(@.value=='HS_CODE_DICTIONARY')].available")
            .value(false));
    http.perform(get("/warehouse/duty-taxes/rates")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.hsCodePrefix=='610910')].adValoremRate").value(0.05));
    http.perform(get("/warehouse/duty-taxes")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].estimateCode").exists())
        .andExpect(jsonPath("$.summary.total").value(1));
    UUID id = asA(() -> service.page(a, new DutyEstimateQuery(
        null, null, null, null, null, null, null, null, null, 0, 50))).rows().get(0).id();
    http.perform(get("/warehouse/duty-taxes/{id}", id)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines[0].dutyAdValoremRate").value(0.05))
        .andExpect(jsonPath("$.lines[0].rateMatchStatus").value("MATCHED"));
    http.perform(get("/warehouse/duty-taxes/{id}/lines", id)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].hsCode").value("610910"));
    http.perform(get("/warehouse/duty-taxes/{id}/payments", id)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
    http.perform(get("/warehouse/duty-taxes/{id}/variances", id)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
    http.perform(get("/warehouse/duty-taxes/variance-analysis")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.byCause").isArray());
  }

  /** 只读角色不能改税率也不能录实缴：录实缴那一刻钱就出去了。 */
  @Test
  void readOnlyRoleCannotWrite() throws Exception {
    http.perform(post("/warehouse/duty-taxes/rates")
            .header("Authorization", "Bearer read-only")
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().isForbidden());
  }
}
