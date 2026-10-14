package com.starcon.warehouse;

import static com.starcon.warehouse.TradeDocumentModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * 10.5.2.1 贸易单证。
 *
 * <p>本测试就是 <b>V91 迁移那条 DB 级不变量的独立复验</b>：不采信任何外部结论， 闸门逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不是直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link CustomsDeclarationIntegrationTest} 一致（同样的 Testcontainers、 同样的 fake identity
 * server、同样的按租户派生 UUID、同样的 TRUNCATE 清场）， 派生 id 的前缀不同，避免夹具互相污染。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TradeDocumentIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("inventory")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-trade-test-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired TradeDocumentService service;
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

  /** 每个租户一整套夹具：法人、SKU、国内仓、海外仓、**10.5.1.1 报关单（已复核已提交）+ 明细**。 */
  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate trade_document_event, trade_document_reminder, trade_document_check,"
              + " trade_document, trade_document_set, trade_origin_preference_rule,"
              + " trade_certification_status, trade_document_template, trade_document_rule,"
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
                idFor(tenant, 1),
                tenant,
                "LE-1",
                "主体一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 2),
                tenant,
                "SKU-1",
                "商品一");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "WH-SH",
                "上海仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "WH-LA",
                "洛杉矶海外仓",
                "US",
                "America/Los_Angeles");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 6),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            seedSlot(tenant, 0);
            return null;
          });
    }
  }

  /**
   * 一整套「可备证」的上游数据：10.4.1.1 头程发运单 + 明细行 + 在途腿回执， 再叠一张 10.5.1.1 报关单（状态 SUBMITTED，所以备证包可以建）+ 申报明细行。
   *
   * <p>id 段按 slot 互不重叠：发运单 10+10·slot、明细 100+10·slot、 在途腿回执 200+10·slot、报关单 300+10·slot、申报明细
   * 400+10·slot。
   *
   * <p>在途腿回执不能省：V80 的延迟约束触发器 {@code first_mile_leg} 钉死 「已发出量 &gt; 0 的明细行必须恰好有一条在途腿」，且必须与明细行写在同一事务里。
   *
   * <p>报关单特意建成 SUBMITTED 且 {@code submitted_at} 非空 —— 这是「时间戳是历史痕迹、 判据必须用状态机」的样本，用例 {@link
   * #draftSourceDeclarationIsRejectedByStatusNotTimestamp} 把它退回 DRAFT 后 submitted_at 仍然留着，而建包必须被拒。
   */
  private void seedSlot(UUID tenant, int slot) {
    sql.execute(
        "insert into first_mile_shipment(id,tenant_id,shipment_code,source_type,source_ref_code,"
            + "legal_entity_id,origin_warehouse_id,destination_warehouse_id,destination_country_code,"
            + "destination_name,transport_mode,carrier_name,booking_no,vessel_or_flight_no,incoterm,"
            + "risk_bearer,currency,freight_cost,insurance_cost,duty_estimate,handling_cost,"
            + "customs_broker,status,created_by,dispatched_at)"
            + " values(?,?,?,'SUPPLIER_PURCHASE','PO-1',?,?,?,'US','洛杉矶海外仓','SEA','COSCO',"
            + "'BK-1','Vessel-1','FOB','BUYER','USD',0,0,0,0,'代理行','DISPATCHED',?,now())",
        idFor(tenant, 10 + slot * 10),
        tenant,
        "FMS-" + (slot + 1),
        idFor(tenant, 1),
        idFor(tenant, 4),
        idFor(tenant, 5),
        idFor(tenant, 1));
    sql.execute(
        "insert into first_mile_shipment_line(id,tenant_id,shipment_id,line_no,sku_id,"
            + "quality_status_id,planned_quantity,shipped_quantity,declared_item_name,hs_code,brand,"
            + "origin_country_code,declared_unit_value,declared_value,gross_weight_kg,net_weight_kg,"
            + "volume_cbm,carton_count)"
            + " values(?,?,?,?,?,?,?,?,'棉质衬衫','620520','A','CN',20,2000,35.5,30,0.5,2)",
        idFor(tenant, 100 + slot * 10 + 1),
        tenant,
        idFor(tenant, 10 + slot * 10),
        1,
        idFor(tenant, 2),
        idFor(tenant, 6),
        new BigDecimal("100"),
        new BigDecimal("100"));
    sql.execute(
        "insert into first_mile_shipment_leg_receipt(id,tenant_id,shipment_id,line_id,"
            + "transit_leg_id,source_reference,sku_id,quantity,shipped_at,registered_at,registered_by)"
            + " values(?,?,?,?,gen_random_uuid(),?,?,100,now(),now(),?)",
        idFor(tenant, 200 + slot * 10 + 1),
        tenant,
        idFor(tenant, 10 + slot * 10),
        idFor(tenant, 100 + slot * 10 + 1),
        "LEG-" + tenant + "-" + slot,
        idFor(tenant, 2),
        idFor(tenant, 1));
    // 报关单先落 DRAFT：V85 的 guard_customs_lock_lines 不允许在已复核之后才补明细行。
    sql.execute(
        "insert into customs_declaration(id,tenant_id,declaration_code,source_shipment_id,"
            + "source_shipment_code,legal_entity_id,trade_mode,exporter_name,exporter_country_code,"
            + "consignee_name,consignee_country_code,destination_country_code,transport_mode,"
            + "declaration_currency,declaration_value,base_currency,exchange_rate,exchange_rate_scale,"
            + "exchange_rate_date,declaration_value_base,total_line_count,created_by)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        idFor(tenant, 300 + slot * 10),
        tenant,
        "CD-" + (slot + 1),
        idFor(tenant, 10 + slot * 10),
        "FMS-" + (slot + 1),
        idFor(tenant, 1),
        "GENERAL_TRADE",
        "上海星创电子商务有限公司",
        "CN",
        "Pacific Trading LLC",
        "US",
        "US",
        "SEA",
        "USD",
        new BigDecimal("2000.0000"),
        "CNY",
        new BigDecimal("7.1234567890"),
        6,
        LocalDate.of(2026, 10, 1),
        new BigDecimal("14246.9136"),
        1,
        idFor(tenant, 1));
    sql.execute(
        "insert into customs_declaration_line(id,tenant_id,declaration_id,line_no,sku_id,"
            + "sku_code,sku_name,source_line_id,declared_item_name,hs_code,hs_code_source,"
            + "hs_code_verified,declaration_elements,declaration_element_status,"
            + "declaration_elements_source,origin_country_code,quantity,uom,declared_value,"
            + "gross_weight_kg,net_weight_kg,package_count)"
            // 22 列 = 21 个占位符 + 1 个 false 字面量（hs_code_verified），jsonb 要显式 ::jsonb
            + " values(?,?,?,?,?,?,?,?,?,?,?,false,?::jsonb,?,?,?,?,?,?,?,?,?)",
        idFor(tenant, 400 + slot * 10 + 1),
        tenant,
        idFor(tenant, 300 + slot * 10),
        1,
        idFor(tenant, 2),
        "SKU-1",
        "商品一",
        idFor(tenant, 100 + slot * 10 + 1),
        "棉质衬衫",
        "620520",
        "MANUAL",
        "{\"fiber\": \"100% 棉\"}",
        "MANUAL_COMPLETE",
        "MANUAL",
        "CN",
        new BigDecimal("100"),
        "PCS",
        new BigDecimal("2000.0000"),
        new BigDecimal("35.5000"),
        new BigDecimal("30.0000"),
        2);
    // 明细齐备后把报关单推到「已复核」：备证包只接受已复核的申报资料。
    // guard_customs_2_review_completeness 会在这一步逐行检查申报要素齐备性。
    sql.execute(
        "update customs_declaration set status='REVIEWED',review_round=1,reviewed_at=?::timestamptz,"
            + "reviewed_by=? where id=?",
        OffsetDateTime.parse("2026-10-08T09:00:00+08:00"),
        idFor(tenant, 1),
        idFor(tenant, 300 + slot * 10));
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.inventory.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.inventory.read", work);
  }

  // ================================================================ 夹具构造

  private TradeRuleCreateRequest rule(String country, String kind, String level) {
    return new TradeRuleCreateRequest(
        country, kind, level, "", false, "TENANT_MAINTAINED", "手工维护", null, null, "");
  }

  private TradeRuleCreateRequest conditionalRule(String country, String kind, String condition) {
    return new TradeRuleCreateRequest(
        country,
        kind,
        "CONDITIONAL",
        condition,
        false,
        "TENANT_MAINTAINED",
        "手工维护",
        null,
        null,
        "");
  }

  private TradePreferenceRuleCreateRequest prefRule(String scheme, String country) {
    return new TradePreferenceRuleCreateRequest(
        scheme,
        country,
        "ALL",
        "",
        "满足关税优惠条件的加工",
        "非原产材料税则号发生改变",
        true,
        null,
        null,
        "ACTIVE",
        "TENANT_MAINTAINED",
        "协定文本",
        "");
  }

  private TradeSetCreateRequest setRequest(UUID tenant) {
    return new TradeSetCreateRequest(idFor(tenant, 300), idFor(tenant, 1), "备证包");
  }

  /** 基础规则：发票 / 装箱单 / 提单 三份必备。 */
  private void seedBaseRules(UUID tenant) {
    asA(
        () -> {
          service.createRule(tenant, rule("US", "COMMERCIAL_INVOICE", "MANDATORY"));
          service.createRule(tenant, rule("US", "PACKING_LIST", "MANDATORY"));
          service.createRule(tenant, rule("US", "BILL_OF_LADING", "MANDATORY"));
          return null;
        });
  }

  /** 生成并定稿一类单证，返回单证 id。 */
  private UUID generateAndFinalize(UUID setId, String kind) {
    // 每次调服务都要经 asA：租户上下文是 AccessService 在事务里绑的，
    // 漏掉一次调用就等于在 RLS 下查一张「不存在的表」，报出来的是 404 而不是权限错误。
    asA(
        () ->
            service.generateDocument(
                setId,
                new TradeDocumentCreateRequest(kind, null, null, null, null, null, Map.of(), "")));
    UUID docId =
        asA(
            () ->
                service.documents(setId).stream()
                    .filter(d -> d.documentKind().equals(kind) && !"SUPERSEDED".equals(d.status()))
                    .map(TradeDocumentRow::id)
                    .findFirst()
                    .orElseThrow());
    asA(
        () ->
            service.finalizeDocument(
                setId, docId, new TradeDocumentFinalizeRequest(null, "ERP-" + kind)));
    return docId;
  }

  /**
   * 把报关单退回草稿让明细行可以改（V85 的 guard_customs_lock_lines 钉死「已复核之后不许直接改明细」）， 改完再复核回 REVIEWED ——
   * 与真实流程同一条路，不绕过闸门。
   */
  private void withEditableDeclaration(BigDecimal newLineValue) {
    asA(
        () -> {
          sql.execute("update customs_declaration set status='DRAFT' where id=?", idFor(a, 300));
          sql.execute(
              "update customs_declaration_line set declared_value=? where id=?",
              newLineValue,
              idFor(a, 401));
          // 单头同步：V85 的延迟约束钉死「单头申报货值 = 明细合计」且折算额自洽，
          // 只改明细会被 V85 自己拒掉 —— 那是 V85 在正确工作，不是本用例的障碍。
          sql.execute(
              "update customs_declaration set declaration_value=?,"
                  + "declaration_value_base=round(?*exchange_rate,4) where id=?",
              newLineValue,
              newLineValue,
              idFor(a, 300));
          sql.execute(
              "update customs_declaration set status='REVIEWED',review_round=1,reviewed_at=?::timestamptz,"
                  + "reviewed_by=? where id=?",
              OffsetDateTime.parse("2026-10-08T09:00:00+08:00"),
              idFor(a, 1),
              idFor(a, 300));
          return null;
        });
  }

  // ================================================================ 单证要求规则是数据不是常量

  @Test
  void rulesAreRealRowsNotConstants() {
    seedBaseRules(a);
    var page =
        asA(
            () ->
                service.rules(
                    new TradeDocumentService.RuleQuery("US", null, null, null, null, null, 0, 50)));
    assertThat(page.rows()).hasSize(3);
    assertThat(page.summary().total()).isEqualTo(3);
    assertThat(page.summary().mandatory()).isEqualTo(3);
    // 换个国家就换一套要求：这就是「数据不是代码常量」的直接证据
    asA(() -> service.createRule(a, rule("DE", "COMMERCIAL_INVOICE", "MANDATORY")));
    var de =
        asA(
            () ->
                service.rules(
                    new TradeDocumentService.RuleQuery("DE", null, null, null, null, null, 0, 50)));
    assertThat(de.rows()).hasSize(1);
    // 条件必备必须写清条件
    assertThatThrownBy(
            () -> asA(() -> service.createRule(a, conditionalRule("US", "IMPORT_LICENSE", ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("适用条件说明");
  }

  @Test
  void ruleVersionMismatchIsRejected() {
    seedBaseRules(a);
    UUID ruleId =
        asA(
            () ->
                service
                    .rules(
                        new TradeDocumentService.RuleQuery(
                            "US", null, null, null, null, null, 0, 50))
                    .rows()
                    .get(0)
                    .id());
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.updateRule(
                            ruleId,
                            new TradeRuleUpdateRequest(
                                99L,
                                "MANDATORY",
                                "",
                                false,
                                "TENANT_MAINTAINED",
                                "x",
                                null,
                                null,
                                "ACTIVE",
                                ""))))
        .isInstanceOf(IllegalStateException.class);
  }

  // ================================================================ 单证模板

  @Test
  void templatesAreRealRowsWithVersionChain() {
    UUID t1 =
        asA(() ->
                service.createTemplate(
                    a,
                    new TradeTemplateCreateRequest(
                        "INV-STD",
                        "COMMERCIAL_INVOICE",
                        "",
                        "USD",
                        Map.of("sections", List.of("抬头", "明细", "总额")),
                        "ACTIVE",
                        null,
                        null,
                        "标准发票版式")))
            .id();
    assertThat(t1).isNotNull();
    // 改版：版本号递增且挂上上一版
    var t2 =
        asA(
            () ->
                service.createTemplate(
                    a,
                    new TradeTemplateCreateRequest(
                        "INV-STD",
                        "COMMERCIAL_INVOICE",
                        "",
                        "USD",
                        Map.of("sections", List.of("抬头", "明细", "总额", "签署栏")),
                        "ACTIVE",
                        null,
                        null,
                        "加了签署栏")));
    assertThat(t2.templateVersion()).isEqualTo(2);
    var rows =
        asA(() ->
                service.templates(
                    new TradeDocumentService.TemplateQuery(
                        "COMMERCIAL_INVOICE", null, null, null, null, 0, 50)))
            .rows();
    assertThat(rows).hasSize(2);
    // 按版本号取，不靠数组下标：排序键是 id（随机 UUID），下标顺序没有保证
    var v2 = rows.stream().filter(r -> r.templateVersion() == 2).findFirst().orElseThrow();
    assertThat(v2.supersedesTemplateId()).isEqualTo(t1);
    // 空模板体被拒：空模板生成出来的是一张空白单证
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.createTemplate(
                            a,
                            new TradeTemplateCreateRequest(
                                "EMPTY",
                                "PACKING_LIST",
                                "",
                                "USD",
                                Map.of(),
                                "DRAFT",
                                null,
                                null,
                                ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("模板体不能为空");
  }

  // ================================================================ 认证状态（16 合规替代来源）

  @Test
  void certificationCarriesItsSource() {
    var saved =
        asA(
            () ->
                service.createCertification(
                    a,
                    new TradeCertificationCreateRequest(
                        idFor(a, 2),
                        "CE",
                        "欧盟 CE 认证",
                        "VALID",
                        "CE-2026-0001",
                        "TÜV",
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2027, 1, 1),
                        "s3://cert/ce.pdf",
                        "TENANT_MAINTAINED",
                        "人工登记",
                        "")));
    assertThat(saved.status()).isEqualTo("VALID");
    var row =
        asA(() ->
                service.certifications(
                    new TradeDocumentService.CertificationQuery(
                        null, null, null, null, null, 0, 50)))
            .rows()
            .get(0);
    // 来源标记必须如实带出来：分不清「系统知道的」还是「人写上去的」就等于没有
    assertThat(row.certificationSource()).isEqualTo("TENANT_MAINTAINED");
    // 有效必须有证书编号与有效期
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.createCertification(
                            a,
                            new TradeCertificationCreateRequest(
                                idFor(a, 2),
                                "FCC",
                                "",
                                "VALID",
                                "",
                                "",
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2027, 1, 1),
                                "",
                                null,
                                "",
                                ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("证书编号");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.createCertification(
                            a,
                            new TradeCertificationCreateRequest(
                                idFor(a, 2),
                                "FDA",
                                "",
                                "VALID",
                                "F-1",
                                "",
                                null,
                                null,
                                "",
                                null,
                                "",
                                ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("有效期");
  }

  // ================================================================ 建包

  @Test
  void setIsCreatedFromCustomsDeclarationSnapshot() {
    var saved = asA(() -> service.createSet(a, setRequest(a)));
    var row = asA(() -> service.detail(saved.id())).set();
    // 收发货人 / 币种 / 金额都从报关单固化下来
    assertThat(row.exporterName()).isEqualTo("上海星创电子商务有限公司");
    assertThat(row.consigneeName()).isEqualTo("Pacific Trading LLC");
    assertThat(row.destinationCountryCode()).isEqualTo("US");
    assertThat(row.documentCurrency()).isEqualTo("USD");
    // 金额 HALF_UP 到分
    assertThat(row.totalDeclaredValue()).isEqualByComparingTo("2000.00");
    assertThat(row.totalDocumentCount()).isZero();
    assertThat(row.status()).isEqualTo("DRAFT");
  }

  /**
   * 来源报关单还没复核过就不许建包。
   *
   * <p>这条特意验证「判据用状态机而不是时间戳」：本夹具的报关单 review_round=1、 submitted_at 非空，但把状态改回 DRAFT 后必须被拒 ——
   * submitted_at 是历史痕迹， 用它判「有没有报过关」会把退回草稿的单错判成已报关。
   */
  @Test
  void draftSourceDeclarationIsRejectedByStatusNotTimestamp() {
    asA(
        () -> {
          sql.execute("update customs_declaration set status='DRAFT' where id=?", idFor(a, 300));
          return null;
        });
    // reviewed_at 仍然留着 —— 它是「已经复核过一次」的历史痕迹。
    // 判据是**状态机**不是时间戳：痕迹留着但状态退回草稿，就不再是可以备证的申报资料。
    assertThat(
            asA(() ->
                    sql.fetchOne(
                        "select reviewed_at from customs_declaration where id=?", idFor(a, 300)))
                .get(0, OffsetDateTime.class))
        .isNotNull();
    assertThatThrownBy(() -> asA(() -> service.createSet(a, setRequest(a))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("人工复核");
  }

  @Test
  void oneSetPerDeclaration() {
    asA(() -> service.createSet(a, setRequest(a)));
    assertThatThrownBy(() -> asA(() -> service.createSet(a, setRequest(a))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // ================================================================ 单证生成

  @Test
  void documentContentIsASnapshotNotRecomputed() {
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    generateAndFinalize(setId, "COMMERCIAL_INVOICE");

    var doc = asA(() -> service.documents(setId)).get(0);
    assertThat(doc.documentKind()).isEqualTo("COMMERCIAL_INVOICE");
    assertThat(doc.status()).isEqualTo("FINAL");
    assertThat(doc.totalAmount()).isEqualByComparingTo("2000.00");
    assertThat(doc.quantityTotal()).isEqualByComparingTo("100.000000");
    assertThat(doc.content()).containsKey("lines");
    assertThat(doc.issuedAt()).isNotNull();

    // 改报关明细之后，历史单证内容**不得**跟着变
    withEditableDeclaration(new BigDecimal("9999"));
    var after = asA(() -> service.documents(setId)).get(0);
    assertThat(after.totalAmount()).isEqualByComparingTo("2000.00");
    assertThat(after.content().get("lines").toString()).contains("2000.0");
  }

  @Test
  void documentVersionChainSupersedesRatherThanOverwrites() {
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    UUID v1 = generateAndFinalize(setId, "PACKING_LIST");
    // 改版：旧版本置为被取代而不是被覆盖 —— 举证「上一版出的是什么」靠的就是它
    asA(
        () ->
            service.generateDocument(
                setId,
                new TradeDocumentCreateRequest(
                    "PACKING_LIST", null, null, null, null, null, Map.of(), "改版")));
    var docs = asA(() -> service.documents(setId));
    assertThat(docs).hasSize(2);
    assertThat(
            docs.stream().filter(d -> d.documentVersion() == 1).findFirst().orElseThrow().status())
        .isEqualTo("SUPERSEDED");
    assertThat(
            docs.stream().filter(d -> d.documentVersion() == 2).findFirst().orElseThrow().status())
        .isEqualTo("DRAFT");
    assertThat(v1).isNotNull();
  }

  @Test
  void finalizeRequiresSourceReference() {
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    asA(
        () ->
            service.generateDocument(
                setId,
                new TradeDocumentCreateRequest(
                    "PACKING_LIST", null, null, null, null, null, Map.of(), "")));
    UUID docId = asA(() -> service.documents(setId)).get(0).id();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.finalizeDocument(
                            setId, docId, new TradeDocumentFinalizeRequest(null, ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("来源定位");
  }

  // ================================================================ 完整性校验

  @Test
  void checkFailsWhenCountryHasNoRules() {
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    generateAndFinalize(setId, "COMMERCIAL_INVOICE");
    var saved = asA(() -> service.runCheck(setId));
    // 一条规则都没配 ≠ 「没有要求就算通过」：没有定义要求就无法断言齐不齐
    assertThat(saved.status()).isEqualTo("DRAFT");
    var check = asA(() -> service.checks(setId)).get(0);
    assertThat(check.result()).isEqualTo("FAIL");
    assertThat(check.missingCount()).isEqualTo(1);
    assertThat(check.ruleCount()).isZero();
    var reminders = asA(() -> service.reminders(setId));
    // 阻断级只有一条：目的国没配规则；另外还有一条提示级「已出单证但规则没要求这一类」
    var blockers = reminders.stream().filter(r -> r.severity().equals("BLOCKER")).toList();
    assertThat(blockers).hasSize(1);
    assertThat(blockers.get(0).message()).contains("没有配置任何单证要求规则");
    assertThat(reminders.stream().anyMatch(r -> r.reminderType().equals("UNDECLARED_DOCUMENT")))
        .isTrue();
  }

  @Test
  void checkPassesWhenAllMandatoryDocumentsAreFinal() {
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    generateAndFinalize(setId, "COMMERCIAL_INVOICE");
    generateAndFinalize(setId, "PACKING_LIST");
    generateAndFinalize(setId, "BILL_OF_LADING");
    asA(() -> service.runCheck(setId));
    var check = asA(() -> service.checks(setId)).get(0);
    assertThat(check.result()).isEqualTo("PASS");
    assertThat(check.requiredCount()).isEqualTo(3);
    assertThat(check.satisfiedCount()).isEqualTo(3);
    assertThat(check.missingCount()).isZero();
    assertThat(check.items()).hasSize(3);
    // 单证包上的汇总必须跟着校验走（DB 延迟约束在提交时复核过）
    var row = asA(() -> service.detail(setId)).set();
    assertThat(row.latestCheckResult()).isEqualTo("PASS");
    assertThat(row.totalDocumentCount()).isEqualTo(3);
    assertThat(row.missingDocumentCount()).isZero();
  }

  @Test
  void checkReportsUnfinalizedDocumentAsBlocking() {
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    generateAndFinalize(setId, "COMMERCIAL_INVOICE");
    generateAndFinalize(setId, "PACKING_LIST");
    // 提单只生成不定稿
    asA(
        () ->
            service.generateDocument(
                setId,
                new TradeDocumentCreateRequest(
                    "BILL_OF_LADING", null, null, null, null, null, Map.of(), "")));
    asA(() -> service.runCheck(setId));
    var check = asA(() -> service.checks(setId)).get(0);
    assertThat(check.result()).isEqualTo("FAIL");
    var reminders = asA(() -> service.reminders(setId));
    assertThat(reminders)
        .anyMatch(
            r -> r.reminderType().equals("DOCUMENT_NOT_FINAL") && r.severity().equals("BLOCKER"));
  }

  @Test
  void conditionalRequirementIsOnlyAWarningBecauseSystemDoesNotJudgeTheCondition() {
    asA(() -> service.createRule(a, conditionalRule("US", "CERTIFICATION_DOC", "目的国要求时才需要")));
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    generateAndFinalize(setId, "COMMERCIAL_INVOICE");
    generateAndFinalize(setId, "PACKING_LIST");
    generateAndFinalize(setId, "BILL_OF_LADING");
    asA(() -> service.runCheck(setId));
    var check = asA(() -> service.checks(setId)).get(0);
    // 必备三类齐备 → 结论是 PASS（条件缺失只是提示级，不阻断）
    assertThat(check.result()).isEqualTo("PASS");
    var warnings =
        asA(() -> service.reminders(setId)).stream()
            .filter(r -> r.severity().equals("WARNING"))
            .toList();
    assertThat(warnings).hasSize(1);
    assertThat(warnings.get(0).message()).contains("适用条件（人工判断）");
    // 条件是人口径：items 里明确标出交给人判断
    assertThat(check.items().toString()).contains("CONDITIONAL_REVIEW_REQUIRED");
  }

  @Test
  void checkReadsCertificationStatusAndReportsExpiry() {
    asA(
        () ->
            service.createRule(
                a,
                new TradeRuleCreateRequest(
                    "US",
                    "CERTIFICATION_DOC",
                    "MANDATORY",
                    "",
                    true,
                    "TENANT_MAINTAINED",
                    "x",
                    null,
                    null,
                    "")));
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    generateAndFinalize(setId, "COMMERCIAL_INVOICE");
    generateAndFinalize(setId, "PACKING_LIST");
    generateAndFinalize(setId, "BILL_OF_LADING");
    generateAndFinalize(setId, "CERTIFICATION_DOC");
    asA(() -> service.runCheck(setId));
    var check = asA(() -> service.checks(setId)).get(0);
    assertThat(check.result()).isEqualTo("FAIL");
    assertThat(asA(() -> service.reminders(setId)))
        .anyMatch(
            r ->
                r.reminderType().equals("CERTIFICATION_MISSING") && r.severity().equals("BLOCKER"));

    // 补一条已过期的认证 → 换成「已过期」提醒，仍阻断
    asA(
        () ->
            service.createCertification(
                a,
                new TradeCertificationCreateRequest(
                    idFor(a, 2),
                    "CE",
                    "欧盟 CE 认证",
                    "EXPIRED",
                    "CE-1",
                    "TÜV",
                    LocalDate.of(2024, 1, 1),
                    LocalDate.of(2025, 1, 1),
                    "",
                    null,
                    "",
                    "")));
    asA(() -> service.runCheck(setId));
    assertThat(asA(() -> service.checks(setId)).get(0).result()).isEqualTo("FAIL");
    var reminders = asA(() -> service.reminders(setId));
    assertThat(reminders).anyMatch(r -> r.reminderType().equals("CERTIFICATION_EXPIRED"));
    // 上一轮不再报的提醒被显式自动结清（否则会永久挡住已补齐的单证包）
    assertThat(reminders.stream().filter(r -> r.reminderType().equals("CERTIFICATION_MISSING")))
        .allMatch(r -> r.status().equals("RESOLVED"));
    assertThat(
            reminders.stream()
                .filter(r -> r.reminderType().equals("CERTIFICATION_MISSING"))
                .findFirst()
                .orElseThrow()
                .resolutionNote())
        .contains("自动结清");
  }

  // ================================================================ FTA 优惠与「实质性改变」边界

  @Test
  void claimingPreferenceLeavesItPendingUntilBusinessConfirms() {
    var prefId = asA(() -> service.createPreferenceRule(a, prefRule("RCEP", "US"))).id();
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    generateAndFinalize(setId, "COMMERCIAL_INVOICE");
    generateAndFinalize(setId, "PACKING_LIST");
    generateAndFinalize(setId, "BILL_OF_LADING");
    UUID co = generateAndFinalize(setId, "RCEP_CERTIFICATE_OF_ORIGIN");

    var claimed =
        asA(
            () ->
                service.claimPreference(
                    setId, co, new TradePreferenceClaimRequest("RCEP", "CN", prefId, "", null)));
    assertThat(claimed.message()).contains("待业务确认");

    var doc =
        asA(() -> service.documents(setId)).stream()
            .filter(d -> d.id().equals(co))
            .findFirst()
            .orElseThrow();
    // 登记主张后状态停在「待业务确认」，系统**不**自己把它判成已确认
    assertThat(doc.substantiveChangeStatus()).isEqualTo("PENDING_BUSINESS_CONFIRMATION");
    assertThat(doc.businessConfirmedAt()).isNull();
    assertThat(doc.businessConfirmedBy()).isNull();

    // 待确认时结论是「待业务确认」而**不是**「通过」
    asA(() -> service.runCheck(setId));
    var check = asA(() -> service.checks(setId)).get(0);
    assertThat(check.result()).isEqualTo("PENDING_BUSINESS_CONFIRMATION");
    assertThat(check.missingCount()).isZero();
    assertThat(check.pendingBusinessCount()).isEqualTo(1);
    var pending =
        asA(() -> service.reminders(setId)).stream()
            .filter(r -> r.reminderType().equals("UNCONFIRMED_SUBSTANTIVE_CHANGE"))
            .findFirst()
            .orElseThrow();
    assertThat(pending.severity()).isEqualTo("BLOCKER");
    assertThat(pending.message()).contains("实质性改变是否成立需要业务确认");
  }

  @Test
  void reviewIsBlockedWhilePreferenceIsUnconfirmed() {
    var prefId = asA(() -> service.createPreferenceRule(a, prefRule("RCEP", "US"))).id();
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    generateAndFinalize(setId, "COMMERCIAL_INVOICE");
    generateAndFinalize(setId, "PACKING_LIST");
    generateAndFinalize(setId, "BILL_OF_LADING");
    UUID co = generateAndFinalize(setId, "RCEP_CERTIFICATE_OF_ORIGIN");
    asA(
        () ->
            service.claimPreference(
                setId, co, new TradePreferenceClaimRequest("RCEP", "CN", prefId, "", null)));
    asA(() -> service.submitForReview(setId, new TradeSetReviewRequest("看看")));

    // 复核**过不去**：不是复核完才发现提交不了
    assertThatThrownBy(() -> asA(() -> service.review(setId, new TradeSetReviewRequest("通过"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("实质性改变");

    // 业务确认后复核才过
    asA(
        () ->
            service.confirmPreference(
                setId,
                co,
                new TradePreferenceClaimRequest(
                    "RCEP", "CN", prefId, "非原产材料税则号已改变，增值超过 40%", true)));
    var doc =
        asA(() -> service.documents(setId)).stream()
            .filter(d -> d.id().equals(co))
            .findFirst()
            .orElseThrow();
    assertThat(doc.substantiveChangeStatus()).isEqualTo("CONFIRMED");
    assertThat(doc.businessConfirmedAt()).isNotNull();
    assertThat(doc.businessConfirmedBy()).isNotNull();
    // 规则版本被钉在单证上：事后能回答「按哪一版规则批的优惠」
    assertThat(doc.preferenceRuleVersion()).isNotNull();
    asA(() -> service.review(setId, new TradeSetReviewRequest("通过")));

    asA(() -> service.runCheck(setId));
    assertThat(asA(() -> service.checks(setId)).get(0).result()).isEqualTo("PASS");
  }

  @Test
  void confirmationWithoutReferenceOrNoteIsRejected() {
    var prefId = asA(() -> service.createPreferenceRule(a, prefRule("RCEP", "US"))).id();
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    UUID co = generateAndFinalize(setId, "RCEP_CERTIFICATE_OF_ORIGIN");
    // 没主张优惠就无从确认
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.confirmPreference(
                            setId,
                            co,
                            new TradePreferenceClaimRequest("RCEP", "CN", prefId, "符合", true))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("还没有主张");
    asA(
        () ->
            service.claimPreference(
                setId, co, new TradePreferenceClaimRequest("RCEP", "CN", prefId, "", null)));
    // 确认依据太短被拒：优惠原产地证能显著降低关税，「凭什么适用」必须写清楚
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.confirmPreference(
                            setId,
                            co,
                            new TradePreferenceClaimRequest("RCEP", "CN", prefId, "符合", true))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("确认依据太短");
  }

  @Test
  void onlyOriginCertificateCanClaimPreference() {
    var prefId = asA(() -> service.createPreferenceRule(a, prefRule("RCEP", "US"))).id();
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    UUID inv = generateAndFinalize(setId, "COMMERCIAL_INVOICE");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.claimPreference(
                            setId,
                            inv,
                            new TradePreferenceClaimRequest("RCEP", "CN", prefId, "x", null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不是原产地证");
  }

  @Test
  void preferenceRuleRequiresSubstantiveChangeCriterion() {
    // 「实质性改变」的适用条件口径不能为空：它是业务确认时要依据的东西
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.createPreferenceRule(
                            a,
                            new TradePreferenceRuleCreateRequest(
                                "RCEP",
                                "US",
                                "ALL",
                                "",
                                "条件",
                                "",
                                null,
                                null,
                                null,
                                "ACTIVE",
                                "TENANT_MAINTAINED",
                                "",
                                ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("实质性改变");
  }

  // ================================================================ 提交闸（三道）

  @Test
  void submitRequiresReviewThenPassingCheckThenNoBlockers() {
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    generateAndFinalize(setId, "COMMERCIAL_INVOICE");
    generateAndFinalize(setId, "PACKING_LIST");
    generateAndFinalize(setId, "BILL_OF_LADING");

    // ① 没复核不许提交
    assertThatThrownBy(
            () -> asA(() -> service.submit(setId, new TradeSetSubmitRequest("报关行", "R-1"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("人工复核");

    asA(() -> service.submitForReview(setId, new TradeSetReviewRequest("看看")));
    asA(() -> service.review(setId, new TradeSetReviewRequest("通过")));

    // ② 没跑校验不许提交
    assertThatThrownBy(
            () -> asA(() -> service.submit(setId, new TradeSetSubmitRequest("报关行", "R-1"))))
        .isInstanceOf(DataIntegrityViolationException.class);

    asA(() -> service.runCheck(setId));
    var saved = asA(() -> service.submit(setId, new TradeSetSubmitRequest("上海报关行", "R-0001")));
    assertThat(saved.status()).isEqualTo("SUBMITTED");
    // 提交痕迹是历史
    assertThat(asA(() -> service.detail(setId)).set().submittedAt()).isNotNull();

    asA(() -> service.acknowledge(setId, new TradeSetAcknowledgeRequest("ACK-1")));
    assertThat(asA(() -> service.detail(setId)).set().status()).isEqualTo("ACKNOWLEDGED");
  }

  @Test
  void submitGateRejectsWhenCheckResultIsNotPass() {
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    generateAndFinalize(setId, "COMMERCIAL_INVOICE");
    asA(() -> service.submitForReview(setId, new TradeSetReviewRequest("看看")));
    asA(() -> service.review(setId, new TradeSetReviewRequest("通过")));
    asA(() -> service.runCheck(setId));
    // 缺提单 → 校验不通过 → 提交被 DB 闸门挡下
    assertThatThrownBy(
            () -> asA(() -> service.submit(setId, new TradeSetSubmitRequest("报关行", "R-1"))))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("完整性校验必须通过");
  }

  /** 绕服务层直接改状态同样被 DB 触发器挡下（人工复核闸 + 提交闸）。 */
  @Test
  void directStatusUpdateIsBlockedByDatabase() {
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    generateAndFinalize(setId, "COMMERCIAL_INVOICE");
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update trade_document_set set status='SUBMITTED',submitted_at=now() where id=?",
                          setId);
                      return null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("人工复核");
  }

  /** 绕服务层把「待业务确认」的优惠改成「已确认」而不留痕，DB 拒绝。 */
  @Test
  void directPreferenceSelfConfirmationIsBlockedByDatabase() {
    var prefId = asA(() -> service.createPreferenceRule(a, prefRule("RCEP", "US"))).id();
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    generateAndFinalize(setId, "COMMERCIAL_INVOICE");
    generateAndFinalize(setId, "PACKING_LIST");
    generateAndFinalize(setId, "BILL_OF_LADING");
    UUID co = generateAndFinalize(setId, "RCEP_CERTIFICATE_OF_ORIGIN");
    asA(
        () ->
            service.claimPreference(
                setId, co, new TradePreferenceClaimRequest("RCEP", "CN", prefId, "", null)));
    // 不给确认人/确认时间就想标成已确认 → CHECK 拒绝
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update trade_document set substantive_change_status='CONFIRMED' where id=?",
                          co);
                      return null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void reminderResolutionRequiresNote() {
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    generateAndFinalize(setId, "COMMERCIAL_INVOICE");
    asA(() -> service.runCheck(setId));
    UUID reminderId = asA(() -> service.reminders(setId)).get(0).id();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.resolveReminder(
                            reminderId, new TradeReminderResolveRequest("RESOLVED", ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("处理必须写说明");
    var saved =
        asA(
            () ->
                service.resolveReminder(
                    reminderId, new TradeReminderResolveRequest("RESOLVED", "已补齐提单并重跑校验")));
    assertThat(saved.status()).isEqualTo("RESOLVED");
  }

  // ================================================================ timestamptz 与金额精度

  @Test
  void timestamptzRoundTripsWithoutOffsetDrift() {
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    generateAndFinalize(setId, "COMMERCIAL_INVOICE");
    OffsetDateTime when = OffsetDateTime.parse("2026-10-08T15:30:45+08:00");
    asA(() -> service.submitForReview(setId, new TradeSetReviewRequest("复核于 " + when)));
    asA(() -> service.review(setId, new TradeSetReviewRequest("复核于 " + when)));
    asA(
        () -> {
          sql.execute(
              "update trade_document_set set reviewed_at=?::timestamptz,updated_at=?::timestamptz where id=?",
              when,
              when,
              setId);
          return null;
        });
    OffsetDateTime readBack =
        asA(() -> sql.fetchOne("select reviewed_at from trade_document_set where id=?", setId))
            .get(0, OffsetDateTime.class);
    // 断言写回的值：静默偏 -28800s 是本仓库踩过的坑
    assertThat(readBack.toInstant()).isEqualTo(when.toInstant());
    // TIMESTAMPTZ preserves the instant; its returned offset depends on the database/JVM zone.
  }

  @Test
  void moneyIsRoundedHalfUpToCents() {
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    // 2000.0055 → HALF_UP 到分 = 2000.01
    withEditableDeclaration(new BigDecimal("2000.0055"));
    asA(
        () ->
            service.generateDocument(
                setId,
                new TradeDocumentCreateRequest(
                    "COMMERCIAL_INVOICE", null, null, null, null, null, Map.of(), "")));
    var doc = asA(() -> service.documents(setId)).get(0);
    assertThat(doc.totalAmount()).isEqualByComparingTo("2000.01");
    // Java 侧 setScale(2,HALF_UP) 得到 2000.01；列是 NUMERIC(19,4)，
    // 读回来是 2000.0100 —— 值相同，标度由列宽决定，两处口径不冲突。
    assertThat(doc.totalAmount().setScale(2, java.math.RoundingMode.HALF_UP))
        .isEqualByComparingTo("2000.01");
    assertThat(
            asA(() ->
                    sql.fetchOne(
                        "select total_amount from trade_document where id=?",
                        asA(() -> service.documents(setId)).get(0).id()))
                .get(0, java.math.BigDecimal.class))
        .isEqualByComparingTo("2000.0100");
  }

  // ================================================================ 分页与筛选

  @Test
  void paginationIsRealAndSummaryIsNot() {
    // 四个 slot 各建一个单证包
    asA(() -> service.createSet(a, setRequest(a)));
    for (int slot = 1; slot <= 3; slot++) {
      final int s0 = slot;
      asA(
          () -> {
            seedSlot(a, s0);
            return null;
          });
      asA(
          () ->
              service.createSet(
                  a, new TradeSetCreateRequest(idFor(a, 300 + s0 * 10), idFor(a, 1), "")));
    }

    var p1 =
        asA(
            () ->
                service.page(
                    new TradeSetQuery(null, null, null, null, null, null, "SET_CODE", 0, 2)));
    var p2 =
        asA(
            () ->
                service.page(
                    new TradeSetQuery(null, null, null, null, null, null, "SET_CODE", 1, 2)));
    assertThat(p1.total()).isEqualTo(4);
    assertThat(p1.rows()).hasSize(2);
    assertThat(p2.rows()).hasSize(2);
    // 两页不重叠
    assertThat(p1.rows().stream().map(TradeSetRow::id).toList())
        .doesNotContainAnyElementsOf(p2.rows().stream().map(TradeSetRow::id).toList());
    // 汇总不受分页影响
    assertThat(p1.summary().total()).isEqualTo(4);
    assertThat(p2.summary().total()).isEqualTo(4);
    // 关键词搜索是真的
    var found =
        asA(
            () ->
                service.page(
                    new TradeSetQuery(null, null, null, null, null, "CD-2", "SET_CODE", 0, 50)));
    assertThat(found.total()).isEqualTo(1);
    // 非法排序键静默回落到默认键，且表还在
    // 非法排序键静默回落到默认键，且表还在 —— 注入串进不来
    var injected =
        asA(
            () ->
                service.page(
                    new TradeSetQuery(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "SET_CODE; drop table trade_document_set",
                        0,
                        50)));
    assertThat(injected.rows()).hasSize(4);
    assertThat(
            asA(
                () ->
                    service
                        .page(new TradeSetQuery(null, null, null, null, null, null, null, 0, 50))
                        .total()))
        .isEqualTo(4);
  }

  // ================================================================ 端点与契约

  @Test
  void endpointsReturnContractShapes() throws Exception {
    seedBaseRules(a);
    http.perform(
            get("/warehouse/trade-documents/capabilities")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statusOptions").isArray())
        .andExpect(jsonPath("$.documentKinds").isArray())
        // 16 合规未交付：显式报不可用 + 归属模块，而不是假装可用
        .andExpect(
            jsonPath("$.dependencies[?(@.value=='COMPLIANCE_CERTIFICATION')].available")
                .value(false))
        .andExpect(
            jsonPath("$.dependencies[?(@.value=='COMPLIANCE_CERTIFICATION')].ownerModule")
                .value("16 合规"));
    http.perform(get("/warehouse/trade-documents/rules").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].countryCode").value("US"))
        .andExpect(jsonPath("$.summary.total").value(3));
    http.perform(
            get("/warehouse/trade-documents/templates").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows").isArray());
    http.perform(
            get("/warehouse/trade-documents/certifications")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(
            get("/warehouse/trade-documents/preference-rules")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(
            get("/warehouse/trade-documents/reminders").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary").exists());
    http.perform(get("/warehouse/trade-documents").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.total").value(0));
  }

  @Test
  void detailEndpointReturnsFullAggregate() throws Exception {
    seedBaseRules(a);
    UUID setId = asA(() -> service.createSet(a, setRequest(a))).id();
    generateAndFinalize(setId, "COMMERCIAL_INVOICE");
    generateAndFinalize(setId, "PACKING_LIST");
    generateAndFinalize(setId, "BILL_OF_LADING");
    asA(() -> service.runCheck(setId));
    http.perform(
            get("/warehouse/trade-documents/{id}", setId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.set.setCode").exists())
        .andExpect(jsonPath("$.documents.length()").value(3))
        .andExpect(jsonPath("$.checks[0].result").value("PASS"))
        .andExpect(jsonPath("$.events").isArray())
        .andExpect(jsonPath("$.allowedNext").isArray());
    http.perform(
            get("/warehouse/trade-documents/{id}/checks", setId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].items").isArray());
  }

  @Test
  void unauthenticatedIsRejected() throws Exception {
    http.perform(get("/warehouse/trade-documents")).andExpect(status().isUnauthorized());
    http.perform(get("/warehouse/trade-documents/rules")).andExpect(status().isUnauthorized());
  }

  @Test
  void readOnlyCannotWrite() throws Exception {
    http.perform(
            post("/warehouse/trade-documents/rules")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void missingSetIs404() throws Exception {
    http.perform(
            get("/warehouse/trade-documents/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }

  // ================================================================ 多租户三断言

  @Test
  void twoTenantsCannotSeeEachOther() {
    seedBaseRules(a);
    UUID aId = asA(() -> service.createSet(a, setRequest(a))).id();
    asB(
        () -> {
          service.createRule(b, rule("US", "COMMERCIAL_INVOICE", "MANDATORY"));
          service.createRule(b, rule("US", "PACKING_LIST", "MANDATORY"));
          service.createRule(b, rule("US", "BILL_OF_LADING", "MANDATORY"));
          service.createSet(b, setRequest(b));
          return null;
        });
    assertThat(
            asA(
                () ->
                    service
                        .page(new TradeSetQuery(null, null, null, null, null, null, null, 0, 50))
                        .rows()))
        .hasSize(1);
    assertThat(
            asB(
                () ->
                    service
                        .page(new TradeSetQuery(null, null, null, null, null, null, null, 0, 50))
                        .rows()))
        .hasSize(1);
    // 知道 id 也读不到对方的单证包
    assertThatThrownBy(() -> asB(() -> service.detail(aId)))
        .isInstanceOf(ResponseStatusException.class);
    // 规则同样互不可见
    assertThat(
            asA(() ->
                    service.rules(
                        new TradeDocumentService.RuleQuery(
                            "US", null, null, null, null, null, 0, 50)))
                .rows())
        .hasSize(3);
    assertThat(
            asB(() ->
                    service.rules(
                        new TradeDocumentService.RuleQuery(
                            "US", null, null, null, null, null, 0, 50)))
                .rows())
        .hasSize(3);
  }

  @Test
  void forgedTenantHeaderIsIgnored() throws Exception {
    seedBaseRules(a);
    UUID aId = asA(() -> service.createSet(a, setRequest(a))).id();
    // 客户端伪造 X-Tenant-Id 头：端点根本不读它，租户只来自已认证成员关系
    http.perform(
            get("/warehouse/trade-documents/{id}", aId)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
  }

  @Test
  void pooledConnectionDoesNotLeakTenant() {
    seedBaseRules(a);
    asA(() -> service.createSet(a, setRequest(a)));
    asB(
        () -> {
          service.createRule(b, rule("US", "COMMERCIAL_INVOICE", "MANDATORY"));
          service.createSet(b, setRequest(b));
          return null;
        });

    // 连接池固定 1 条连接：连续在两个租户间来回走，读的必须始终是当前租户的行。
    for (int i = 0; i < 5; i++) {
      assertThat(
              asA(
                  () ->
                      service
                          .rules(
                              new TradeDocumentService.RuleQuery(
                                  "US", null, null, null, null, null, 0, 50))
                          .rows()))
          .hasSize(3);
      assertThat(
              asB(
                  () ->
                      service
                          .rules(
                              new TradeDocumentService.RuleQuery(
                                  "US", null, null, null, null, null, 0, 50))
                          .rows()))
          .hasSize(1);
    }
    // 事件表同样不串。注意断言的是「读不到行」而不是「抛异常」：
    // RLS 的隔离语义就是**静默过滤**（对方行对你根本不存在）。
    UUID bId =
        asB(() ->
                service
                    .page(new TradeSetQuery(null, null, null, null, null, null, null, 0, 50))
                    .rows())
            .get(0)
            .id();
    assertThat(asA(() -> sql.fetch("select * from trade_document_event where set_id=?", bId)))
        .isEmpty();
    assertThat(asA(() -> sql.fetch("select * from trade_document_reminder where set_id=?", bId)))
        .isEmpty();
  }

  @Test
  void everyBusinessTableForcesRls() {
    List<String> tables =
        List.of(
            "trade_document_rule",
            "trade_document_template",
            "trade_certification_status",
            "trade_origin_preference_rule",
            "trade_document_set",
            "trade_document",
            "trade_document_check",
            "trade_document_reminder",
            "trade_document_event");
    for (String t : tables) {
      // get(0, boolean.class) 返回原始 boolean，显式装箱成 Boolean 才接得上 Supplier<Boolean>。
      Boolean enabled =
          asA(
              () ->
                  Boolean.valueOf(
                      sql.fetchOne("select relforcerowsecurity from pg_class where relname=?", t)
                          .get(0, boolean.class)));
      assertThat(Boolean.TRUE.equals(enabled))
          .withFailMessage(t + " 没有 FORCE ROW LEVEL SECURITY")
          .isTrue();
    }
  }
}
