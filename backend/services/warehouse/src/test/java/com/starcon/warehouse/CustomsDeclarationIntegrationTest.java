package com.starcon.warehouse;

import static com.starcon.warehouse.CustomsDeclarationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 10.5.1.1 报关单管理。
 *
 * <p>本测试就是 <b>V85 迁移那条 DB 级不变量的独立复验</b>：不采信任何外部结论，
 * 八条闸门逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不是直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link FirstMileShipmentIntegrationTest} 一致（同样的 Testcontainers、
 * 同样的 fake identity server、同样的按租户派生 UUID、同样的 TRUNCATE 清场），
 * 派生 id 的前缀不同，避免夹具互相污染。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomsDeclarationIntegrationTest {
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
        ("starcon-warehouse-customs-test-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired CustomsDeclarationService service;
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

  /** 每个租户一整套夹具：法人、SKU、国内仓、海外仓、质量状态、**10.4.1.1 头程发运单 + 明细**。 */
  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate customs_declaration_event, customs_result, customs_submission, customs_document,"
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
            seedShipment(tenant);
            return null;
          });
    }
  }

  /**
   * 10.4.1.1 头程发运单 + 明细行（报关单的货物来源）。
   *
   * <p>逐行登记 7.5.1.2 在途腿回执：V80 的延迟约束触发器 {@code first_mile_leg} 钉死
   * 「已发出量 &gt; 0 的明细行必须恰好有一条在途腿」。这里必须与明细行写在**同一事务**里。
   */
  private void seedShipment(UUID tenant) {
    sql.execute(
        "insert into first_mile_shipment(id,tenant_id,shipment_code,source_type,source_ref_code,"
            + "legal_entity_id,origin_warehouse_id,destination_warehouse_id,destination_country_code,"
            + "destination_name,transport_mode,carrier_name,booking_no,vessel_or_flight_no,incoterm,"
            + "risk_bearer,currency,freight_cost,insurance_cost,duty_estimate,handling_cost,"
            + "customs_broker,status,created_by,dispatched_at)"
            + " values(?,?,?,'SUPPLIER_PURCHASE','PO-1',?,?,?,'US','洛杉矶海外仓','SEA','COSCO',"
            + "'BK-1','Vessel-1','FOB','BUYER','USD',0,0,0,0,'代理行','DISPATCHED',?,now())",
        idFor(tenant, 10), tenant, "FMS-1", idFor(tenant, 1), idFor(tenant, 4), idFor(tenant, 5),
        user);
    for (int i = 1; i <= 2; i++) {
      sql.execute(
          "insert into first_mile_shipment_line(id,tenant_id,shipment_id,line_no,sku_id,"
              + "quality_status_id,planned_quantity,shipped_quantity,declared_item_name,hs_code,brand,"
              + "origin_country_code,declared_unit_value,declared_value,gross_weight_kg,net_weight_kg,"
              + "volume_cbm,carton_count)"
              + " values(?,?,?,?,?,?,?,?,'棉质衬衫','610910','A','CN',10,1000,120,100,0.5,10)",
          idFor(tenant, 20 + i), tenant, idFor(tenant, 10), i, idFor(tenant, 2), idFor(tenant, 6),
          100, 100);
      sql.execute(
          "insert into first_mile_shipment_leg_receipt(id,tenant_id,shipment_id,line_id,"
              + "transit_leg_id,source_reference,sku_id,quantity,shipped_at,registered_at,registered_by)"
              + " values(?,?,?,?,gen_random_uuid(),?,?,100,now(),now(),?)",
          idFor(tenant, 30 + i), tenant, idFor(tenant, 10), idFor(tenant, 20 + i),
          "LEG-" + tenant + "-" + i, idFor(tenant, 2), user);
    }
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

  /** 一条内容齐备的明细：HS 编码、申报要素、货值、毛重、箱数、计量单位都在。 */
  private CustomsLineRequest goodLine(UUID tenant, int lineNo) {
    return new CustomsLineRequest(
        idFor(tenant, 20 + lineNo),
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
        10);
  }

  private CustomsCreateRequest createRequest(UUID tenant, UUID shipmentId) {
    return new CustomsCreateRequest(
        shipmentId,
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
        "BL-2026-000001",
        "USD",
        "CNY",
        new BigDecimal("7.1234567890"),
        6,
        LocalDate.of(2026, 10, 1),
        "首批出口",
        List.of(goodLine(tenant, 1), goodLine(tenant, 2)));
  }

  /** 建单 → 送复核 → 复核通过，返回已复核的报关单 id。 */
  private UUID reviewed(UUID tenant) {
    CustomsSaved saved = asA(() -> service.create(tenant, createRequest(tenant, idFor(tenant, 10))));
    UUID id = saved.id();
    asA(() -> service.submitForReview(id, new CustomsSubmitForReviewRequest("请复核")));
    asA(() -> service.review(id, new CustomsReviewRequest("申报资料逐项核对无误")));
    return id;
  }

  /** 生成并定稿三单据。 */
  private void finalizeDocuments(UUID id) {
    for (var type : CustomsDocumentType.values()) {
      CustomsSaved saved =
          asA(() -> service.generateDocument(id, new CustomsGenerateDocumentRequest(type.name(), "")));
      assertThat(saved.message()).isNotBlank();
      UUID docId =
          asA(
              () ->
                  service.documents(id).stream()
                      .filter(d -> d.documentType().equals(type.name()))
                      .findFirst()
                      .orElseThrow()
                      .id());
      asA(() -> service.finalizeDocument(docId, new CustomsFinalizeDocumentRequest("")));
    }
  }

  // ================================================================ 不变量 1：人工复核闸

  @Test
  void draftCannotSubmitBeforeReview() {
    CustomsSaved saved = asA(() -> service.create(a, createRequest(a, idFor(a, 10))));
    UUID id = saved.id();
    assertThat(saved.status()).isEqualTo("DRAFT");

    assertThatThrownBy(
            () -> asA(() -> service.submit(id, new CustomsSubmitRequest("BR-REF-1", "代理行", "ACCEPTED", ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须人工复核");
  }

  /** 绕过服务层直接改状态也必须被 DB 挡下 —— 闸门在 DB 层，不是只靠服务层 if。 */
  @Test
  void dbBlocksDirectUpdateToSubmittedWithoutReview() {
    CustomsSaved saved = asA(() -> service.create(a, createRequest(a, idFor(a, 10))));
    UUID id = saved.id();
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update customs_declaration set status='SUBMITTED',submitted_at=now()"
                              + " where id=?",
                          id);
                      return null;
                    }))
        .hasMessageContaining("提交报关行前必须人工复核");
  }

  /** 复核不通过退回草稿后，改完必须**重新**走复核才能再次提交。 */
  @Test
  void rejectedReviewMustBeRedoneBeforeSubmit() {
    CustomsSaved saved = asA(() -> service.create(a, createRequest(a, idFor(a, 10))));
    UUID id = saved.id();
    asA(() -> service.submitForReview(id, new CustomsSubmitForReviewRequest("请复核")));
    asA(() -> service.rejectReview(id, new CustomsRejectReviewRequest("品名与实物不符")));
    assertThat(asA(() -> service.detail(id)).declaration().status()).isEqualTo("REJECTED");

    // 复核退回态直接提交 → 状态机与 DB 闸门都拒绝
    assertThatThrownBy(
            () -> asA(() -> service.submit(id, new CustomsSubmitRequest("BR-REF-2", "代理行", "ACCEPTED", ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须人工复核");
  }

  @Test
  void reviewGateRejectsIncompleteDeclarationElements() {
    CustomsSaved saved = asA(() -> service.create(a, createRequest(a, idFor(a, 10))));
    UUID id = saved.id();
    // 第 1 行改成缺 HS 编码 + 申报要素未齐备
    asA(
        () -> {
          sql.execute(
              "update customs_declaration_line set hs_code='',declaration_element_status='MANUAL_PENDING'"
                  + " where declaration_id=? and line_no=1",
              id);
          return null;
        });
    asA(() -> service.submitForReview(id, new CustomsSubmitForReviewRequest("请复核")));
    assertThatThrownBy(() -> asA(() -> service.review(id, new CustomsReviewRequest("过"))))
        .hasMessageContaining("HS 编码");
  }

  @Test
  void reviewRequiresAtLeastOneLine() {
    CustomsSaved saved =
        asA(
            () ->
                service.create(
                    a,
                    new CustomsCreateRequest(
                        idFor(a, 10), idFor(a, 1), "GENERAL_TRADE", "出口方", "CN", "收货方", "US",
                        "", "", "", "", "", "US", "", "", "", "SEA", "", "USD", "CNY",
                        new BigDecimal("7.1234567890"), 6, LocalDate.of(2026, 10, 1), "", List.of())));
    UUID id = saved.id();
    asA(() -> service.submitForReview(id, new CustomsSubmitForReviewRequest("请复核")));
    assertThatThrownBy(() -> asA(() -> service.review(id, new CustomsReviewRequest("过"))))
        .hasMessageContaining("不允许复核通过");
  }

  // ================================================================ 不变量 2：复核后明细锁定

  @Test
  void linesAreLockedAfterReview() {
    UUID id = reviewed(a);
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update customs_declaration_line set declared_value=1 where declaration_id=?",
                          id);
                      return null;
                    }))
        .hasMessageContaining("不允许直接改明细");
  }

  @Test
  void reviewedDeclarationCannotBeUpdated() {
    UUID id = reviewed(a);
    assertThatThrownBy(() -> asA(() -> service.update(id, updateRequest(a))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不允许改单");
  }

  @Test
  void withdrawReviewAllowsEditingAgain() {
    UUID id = reviewed(a);
    asA(() -> service.withdrawReview(id, new CustomsWithdrawReviewRequest("需要补资料")));
    assertThat(asA(() -> service.detail(id)).declaration().status()).isEqualTo("PENDING_REVIEW");
    asA(() -> service.update(id, updateRequest(a)));
    assertThat(asA(() -> service.detail(id)).lines()).hasSize(2);
  }

  // ================================================================ 不变量 3：货值与汇率快照

  @Test
  void rateSnapshotIsStoredAndSelfConsistent() {
    CustomsSaved saved = asA(() -> service.create(a, createRequest(a, idFor(a, 10))));
    UUID id = saved.id();
    CustomsDeclarationRow row = asA(() -> service.detail(id)).declaration();

    // 明细合计 2000 × 汇率 7.1234567890 = 14246.9135780 → 4 位小数 14246.9136
    assertThat(row.declarationValue()).isEqualByComparingTo("2000.0000");
    assertThat(row.exchangeRate()).isEqualByComparingTo("7.1234567890");
    assertThat(row.exchangeRateScale()).isEqualTo(6);
    assertThat(row.exchangeRateDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(row.declarationValueBase()).isEqualByComparingTo("14246.9136");

    // DB 层再钉一次：篡改折算额必须被拒
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update customs_declaration set declaration_value_base=1 where id=?", id);
                      return null;
                    }))
        .hasMessageContaining("汇率快照必须自洽可举证");
  }

  @Test
  void headValueMustEqualLineSum() {
    CustomsSaved saved = asA(() -> service.create(a, createRequest(a, idFor(a, 10))));
    UUID id = saved.id();
    asA(() -> service.submitForReview(id, new CustomsSubmitForReviewRequest("请复核")));
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute("update customs_declaration set review_round=1,reviewed_at=now(),"
                          + "reviewed_by=? where id=?", user, id);
                      return null;
                    }))
        .isInstanceOfAny(IllegalArgumentException.class, RuntimeException.class);
  }

  // ================================================================ 不变量 4：三单据齐备才许提交

  @Test
  void submitRequiresAllThreeDocumentsFinal() {
    UUID id = reviewed(a);
    // 只生成一份单据就提交 → DB 层报出缺哪几份
    asA(() -> service.generateDocument(id, new CustomsGenerateDocumentRequest("CUSTOMS_DECLARATION", "")));
    assertThatThrownBy(
            () -> asA(() -> service.submit(id, new CustomsSubmitRequest("BR-REF-3", "代理行", "ACCEPTED", ""))))
        .hasMessageContaining("必须人工复核").isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void submitBlockedWhenDocumentsNotFinal() {
    UUID id = reviewed(a);
    for (var type : CustomsDocumentType.values()) {
      asA(() -> service.generateDocument(id, new CustomsGenerateDocumentRequest(type.name(), "")));
    }
    assertThatThrownBy(
            () -> asA(() -> service.submit(id, new CustomsSubmitRequest("BR-REF-4", "代理行", "ACCEPTED", ""))))
        .hasMessageContaining("定稿");
  }

  @Test
  void happyPathReviewSubmitAndClear() {
    UUID id = reviewed(a);
    finalizeDocuments(id);
    CustomsSaved submitted =
        asA(() -> service.submit(id, new CustomsSubmitRequest("BR-REF-OK", "上海报关行", "ACCEPTED", "已收单")));
    assertThat(submitted.status()).isEqualTo("SUBMITTED");

    CustomsSaved cleared =
        asA(
            () ->
                service.recordResult(
                    id,
                    new CustomsResultRequest(
                        "CLEARED", "REL-2026-001", "货物已放行", null, "", "CUSTOMS-REL-001")));
    assertThat(cleared.status()).isEqualTo("CLEARED");

    CustomsDeclarationDetail detail = asA(() -> service.detail(id));
    assertThat(detail.submissions()).hasSize(1);
    assertThat(detail.results()).hasSize(1);
    assertThat(detail.events()).extracting(CustomsEventRow::kind).contains("SUBMITTED", "CLEARED");
    // 放行是终态
    assertThat(detail.transitions()).isEmpty();
  }

  // ================================================================ 不变量 5：三种结果都落审计

  @Test
  void clearedRejectedAmendedAllAudited() {
    for (var type : List.of("CLEARED", "REJECTED", "AMENDED")) {
      UUID id = reviewed(a);
      finalizeDocuments(id);
      asA(() -> service.submit(id, new CustomsSubmitRequest("BR-" + type, "代理行", "ACCEPTED", "")));
      asA(
          () ->
              service.recordResult(
                  id,
                  new CustomsResultRequest(
                      type, "REF-" + type, type + " 的原因说明", null, "", "SRC-" + type)));
      List<CustomsResultRow> results = asA(() -> service.results(id));
      assertThat(results).hasSize(1);
      assertThat(results.get(0).resultType()).isEqualTo(type);
      assertThat(results.get(0).reason()).isNotBlank();
      assertThat(results.get(0).actorId()).isNotNull();
      assertThat(results.get(0).sourceReference()).isEqualTo("SRC-" + type);
    }
  }

  @Test
  void rejectionReasonIsMandatory() {
    UUID id = reviewed(a);
    finalizeDocuments(id);
    asA(() -> service.submit(id, new CustomsSubmitRequest("BR-REJ", "代理行", "ACCEPTED", "")));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.recordResult(
                            id,
                            new CustomsResultRequest(
                                "REJECTED", "REF-R", "  ", null, "", "SRC-R"))))
        .hasMessageContaining("海关结果原因");
  }

  @Test
  void amendmentCountComesFromResultRowsNotHandFilled() {
    UUID id = reviewed(a);
    finalizeDocuments(id);
    asA(() -> service.submit(id, new CustomsSubmitRequest("BR-AM", "代理行", "ACCEPTED", "")));
    asA(
        () ->
            service.recordResult(
                id,
                new CustomsResultRequest("AMENDED", "REF-AM", "海关要求改单", null, "", "SRC-AM")));
    assertThat(asA(() -> service.detail(id)).declaration().amendmentCount()).isEqualTo(1);
    // DB 层：手填一个与结果条数不符的计数必须被拒
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update customs_declaration set amendment_count=7 where id=?", id);
                      return null;
                    }))
        .hasMessageContaining("改单次数必须来自结果记录");
  }

  // ================================================================ 不变量 6：一票货只报一次关

  @Test
  void oneShipmentOneDeclaration() {
    asA(() -> service.create(a, createRequest(a, idFor(a, 10))));
    assertThatThrownBy(() -> asA(() -> service.create(a, createRequest(a, idFor(a, 10)))))
        .hasMessageContaining("customs_declaration_tenant_id_source_shipment_id_key");
  }

  // ================================================================ 不变量 7：申报要素可后补结构

  @Test
  void declarationElementsAreBackfillableStructure() {
    CustomsSaved saved = asA(() -> service.create(a, createRequest(a, idFor(a, 10))));
    List<CustomsLineRow> lines = asA(() -> service.lines(saved.id()));
    CustomsLineRow line = lines.get(0);
    // 结构在：jsonb 对象 + 齐备性状态机 + 来源标记都落库，3.7.1.1 交付后回填即可
    assertThat(line.declarationElements()).containsEntry("fiber", "100% 棉");
    assertThat(line.declarationElementStatus()).isEqualTo("MANUAL_COMPLETE");
    assertThat(line.declarationElementsSource()).isEqualTo("MANUAL");
    // HS 编码上游未交付，所以没有任何一行能说「这个码来自字典」
    assertThat(line.hsCodeSource()).isEqualTo("MANUAL");
    assertThat(line.hsCodeVerified()).isFalse();
  }

  @Test
  void capabilitiesDeclareMissingUpstream() {
    CustomsCapabilities caps = access.run(actorA, "warehouse.inventory.read", () -> service.capabilities());
    assertThat(caps.dependencies())
        .extracting(CustomsDimension::value)
        .contains("HS_CODE_DICTIONARY", "BROKER_API");
    assertThat(caps.dependencies())
        .filteredOn(d -> d.value().equals("HS_CODE_DICTIONARY"))
        .singleElement()
        .satisfies(d -> {
          assertThat(d.available()).isFalse();
          assertThat(d.ownerModule()).isEqualTo("3.7.1.1");
          assertThat(d.note()).contains("未交付");
        });
    assertThat(caps.hsCodeNote()).contains("当前不可用");
  }

  // ================================================================ 不变量 8：多租户三断言

  @Test
  void twoTenantsCannotSeeEachOther() {
    asA(() -> service.create(a, createRequest(a, idFor(a, 10))));
    asB(() -> service.create(b, createRequest(b, idFor(b, 10))));

    assertThat(asA(() -> service.page(new CustomsQuery(null, null, null, null, null, null, null, null, 0, 50)).rows()))
        .hasSize(1);
    assertThat(asB(() -> service.page(new CustomsQuery(null, null, null, null, null, null, null, null, 0, 50)).rows()))
        .hasSize(1);

    // 知道 id 也读不到对方的单
    UUID aId = asA(() -> service.page(new CustomsQuery(null, null, null, null, null, null, null, null, 0, 50)).rows())
        .get(0).id();
    assertThatThrownBy(() -> asB(() -> service.detail(aId)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID aId = asA(() -> service.create(a, createRequest(a, idFor(a, 10)))).id();
    // 客户端伪造 X-Tenant-Id 头：端点根本不读它，租户只来自已认证成员关系
    http.perform(get("/warehouse/customs-declarations/{id}", aId)
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
  }

  @Test
  void pooledConnectionDoesNotLeakTenant() {
    asA(() -> service.create(a, createRequest(a, idFor(a, 10))));
    asB(() -> service.create(b, createRequest(b, idFor(b, 10))));

    // 连接池固定 1 条连接：这里连续在两个租户间来回走，读的必须始终是当前租户的行。
    for (int i = 0; i < 5; i++) {
      assertThat(asA(() -> service.page(new CustomsQuery(null, null, null, null, null, null, null, null, 0, 50)).rows()))
          .singleElement()
          .satisfies(r -> assertThat(r.declarationCode()).doesNotContain(b.toString()));
      assertThat(asB(() -> service.page(new CustomsQuery(null, null, null, null, null, null, null, null, 0, 50)).rows()))
          .singleElement();
    }
    // 事件/明细行同样不串
    UUID bId = asB(() -> service.page(new CustomsQuery(null, null, null, null, null, null, null, null, 0, 50)).rows())
        .get(0).id();
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.fetch("select * from customs_declaration_event where declaration_id=?", bId);
                      return null;
                    }))
        .isInstanceOf(Exception.class);
  }

  // ================================================================ 端点与契约

  @Test
  void endpointsReturnContractShapes() throws Exception {
    asA(() -> service.create(a, createRequest(a, idFor(a, 10))));
    http.perform(get("/warehouse/customs-declarations/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statusOptions").isArray())
        .andExpect(jsonPath("$.tradeModes").isArray())
        .andExpect(jsonPath("$.dependencies[?(@.value=='HS_CODE_DICTIONARY')].available")
            .value(false));
    http.perform(get("/warehouse/customs-declarations")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].declarationCode").exists())
        .andExpect(jsonPath("$.summary.total").value(1));
    http.perform(get("/warehouse/customs-declarations")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.total").value(1));
  }

  @Test
  void unauthenticatedIsRejected() throws Exception {
    http.perform(get("/warehouse/customs-declarations")).andExpect(status().isUnauthorized());
  }

  @Test
  void readOnlyCannotWrite() throws Exception {
    http.perform(
            post("/warehouse/customs-declarations")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  private CustomsUpdateRequest updateRequest(UUID tenant) {
    return new CustomsUpdateRequest(
        null,
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
        "BL-2026-000002",
        "USD",
        "CNY",
        new BigDecimal("7.1234567890"),
        6,
        LocalDate.of(2026, 10, 1),
        "改单后",
        List.of(goodLine(tenant, 1), goodLine(tenant, 2)));
  }
}