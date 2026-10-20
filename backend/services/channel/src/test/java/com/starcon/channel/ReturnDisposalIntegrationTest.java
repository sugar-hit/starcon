package com.starcon.channel;

import static com.starcon.channel.ReturnDisposalModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 10.7.1.3 弃置与销毁。
 *
 * <p>本测试类钉死六组事：
 *
 * <ol>
 *   <li><b>🔴 凭证缺失 ⇒ 不许置为完成态</b>：服务端 422（并说清缺哪几样），
 *       <b>绕过服务层直接写 SQL 也一样被 DB 触发器拒</b>。</li>
 *   <li><b>🔴 销毁方式是枚举事实</b>：压碎 / 焚烧 / 专业销毁；方式**决定**凭证要求
 *       （专业销毁要重量、焚烧要处置证明编号）。</li>
 *   <li><b>🔴 凭证要求只能加严不能放松</b>：品牌方政策把要求加严；
 *       客户端自报的旗子被触发器**重算覆盖**；排期后**冻结**。</li>
 *   <li><b>🔴 上游是「弃置销毁」的处置单</b>：转销售 / 退运回国的进不来（服务层 + DB 各拒一次）。</li>
 *   <li><b>索赔依据与销毁单同口径</b>：数量 / 重量必须逐字相同（DB 钉死）；
 *       未销毁的单子不许生成依据。</li>
 *   <li><b>时刻一律钉死</b>：timestamptz 往返不偏 8 小时，且断言<b>写回的值</b>。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReturnDisposalIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 复用 identity 已注册的 channel 权限码：本模块不新增权限码，identity 零改动。 */
  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of("channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
            + " then create role starcon_app nologin; end if; end $$");
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
            + " then create role channel_app login password 'test-only' nosuperuser nobypassrls; end if; end $$");
        s.execute("alter role channel_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            AccessService.Access who = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (who == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(who);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
      actors.put("Bearer tenant-a", actor(user, a, grants));
      actors.put("Bearer tenant-b", actor(user, b, grants));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired ReturnDisposalService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  AccessService.Access actorA, actorB;

  @BeforeEach
  void setup() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    truncateAll();
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  void truncateAll() {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // CASCADE：这些表当前还没有外键引用，但以后任何车道给它们加外键时，
      // 引用方不在同一条 truncate 语句里 PG 会直接拒
      // （cannot truncate a table referenced in a foreign key constraint），级联一并清干净。
      s.execute("truncate return_disposal_event, return_disposal_claim_basis, return_disposal_cost,"
          + " return_disposal_evidence, return_disposal_order, return_disposal_evidence_policy,"
          + " return_inventory_change_instruction, return_disposition_cost, return_disposition_order,"
          + " return_disposition_decision, return_resale_restriction, return_receiving_registration"
          + " cascade");
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 上游 10.7.1.1 夹具（**同服务**，直接落库）
  // ==================================================================

  record Disposition(
      UUID registrationId, UUID decisionId, UUID orderId, String dispositionNo, String receiptNo) {}

  /**
   * 落一条 10.7.1.1 的处置事实（登记 → 决策 → 处置单）。
   *
   * <p>走的是真实表与真实触发器（app.actor 照常盖章），所以本模块读到的是**真上游**。
   */
  Disposition seedDisposition(UUID tenant, String method, String skuCode, String qty) {
    UUID registrationId = UUID.randomUUID();
    UUID decisionId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    String receiptId = UUID.randomUUID().toString();
    String receiptLineId = UUID.randomUUID().toString();
    String receiptNo = "RR-" + shortId();
    String registrationNo = shortId();
    String decisionNo = shortId();
    String dispositionNo = shortId();
    boolean resale = "RESALE".equals(method);
    execAsTenant(
        tenant,
        "insert into return_receiving_registration"
            + " (id,tenant_id,receipt_id,receipt_line_id,receipt_no,sku_code,sku_name,batch_no,"
            + "  brand_code,platform_code,warehouse_type,warehouse_ref,warehouse_country,received_qty,"
            + "  received_at,note,registration_no)"
            + " values ('" + registrationId + "','" + tenant + "','" + receiptId + "','"
            + receiptLineId + "','" + receiptNo + "','" + skuCode + "','" + skuCode + " 样机','B-1',"
            + "'BRAND-A','AMAZON','OVERSEAS','WH-DE-1','DE'," + qty + ", now(), '夹具','RCV-"
            + registrationNo + "')");
    execAsTenant(
        tenant,
        "insert into return_disposition_decision"
            + " (id,tenant_id,registration_id,receipt_id,receipt_line_id,receipt_no,sku_code,sku_name,"
            + "  batch_no,brand_code,platform_code,disposition_method,decided_qty,decision_basis,"
            + "  decided_at,resale_restricted,status,decision_no"
            + (resale ? ",quality_inspection_id,quality_verdict,quality_verdict_at" : "")
            + ")"
            + " values ('" + decisionId + "','" + tenant + "','" + registrationId + "','"
            + receiptId + "','" + receiptLineId + "','" + receiptNo + "','" + skuCode + "','" + skuCode
            + " 样机','B-1','BRAND-A','AMAZON','" + method + "'," + qty + ",'夹具', now(), false,"
            + "'RECORDED','RDC-" + decisionNo + "'"
            + (resale ? ",'" + UUID.randomUUID() + "','RESALABLE', now()" : "")
            + ")");
    execAsTenant(
        tenant,
        "insert into return_disposition_order"
            + " (id,tenant_id,decision_id,registration_id,receipt_id,receipt_line_id,receipt_no,"
            + "  sku_code,sku_name,batch_no,brand_code,platform_code,disposition_method,disposed_qty,"
            + "  cost_currency,salvage_currency,salvage_amount,status,issued_at,disposition_no)"
            + " values ('" + orderId + "','" + tenant + "','" + decisionId + "','" + registrationId
            + "','" + receiptId + "','" + receiptLineId + "','" + receiptNo + "','" + skuCode + "','"
            + skuCode + " 样机','B-1','BRAND-A','AMAZON','" + method + "'," + qty
            + ",'USD','USD',0,'ISSUED', now(), 'RDO-" + dispositionNo + "')");
    return new Disposition(registrationId, decisionId, orderId, "RDO-" + dispositionNo, receiptNo);
  }

  static String shortId() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  static void execAsTenant(UUID tenant, String sql) {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  // ==================================================================
  // 夹具：建销毁单
  // ==================================================================

  CreateDisposalOrderRequest orderRequest(UUID dispositionOrderId, String method, String qty) {
    return new CreateDisposalOrderRequest(dispositionOrderId, method, new BigDecimal(qty), "KG", "USD", "夹具销毁单");
  }

  ReturnDisposalDetail create(UUID dispositionOrderId, String method, String qty) {
    return as(actorA, "channel.operation.create", () -> service.createOrder(orderRequest(dispositionOrderId, method, qty)));
  }

  ReturnDisposalDetail schedule(UUID orderId) {
    return as(
        actorA,
        "channel.operation.update",
        () ->
            service.schedule(
                orderId,
                new ScheduleDisposalRequest(
                    OffsetDateTime.of(2026, 5, 1, 8, 0, 0, 0, ZoneOffset.UTC), "排期")));
  }

  ReturnDisposalDetail evidence(UUID orderId, String kind) {
    return as(
        actorA,
        "channel.operation.create",
        () ->
            service.recordEvidence(
                orderId,
                new RecordDisposalEvidenceRequest(
                    kind,
                    "PHOTO".equals(kind) ? "https://cdn.example.com/disposal-photo.jpg" : null,
                    "WEIGHT".equals(kind) ? new BigDecimal("12.500000") : null,
                    "CERTIFICATE".equals(kind) ? "INC-2026-0001" : null,
                    "EXECUTOR".equals(kind) ? "销毁厂 A" : null,
                    OffsetDateTime.of(2026, 5, 2, 9, 30, 0, 0, ZoneOffset.UTC),
                    "夹具凭证")));
  }

  ReturnDisposalDetail dispose(UUID orderId, String executor) {
    return as(
        actorA,
        "channel.operation.update",
        () ->
            service.dispose(
                orderId,
                new DisposeDisposalRequest(
                    OffsetDateTime.of(2026, 5, 2, 10, 0, 0, 0, ZoneOffset.UTC),
                    null,
                    executor,
                    null,
                    "夹具执行销毁")));
  }

  // ==================================================================
  // 1. 能力声明
  // ==================================================================

  @Test
  @DisplayName("10.7.1.3 能力声明：凭证闸 / 方式枚举 / 未交付下游逐条写明 ownerModule")
  void capabilitiesDeclareEvidenceGateAndDownstreamGaps() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());

    assertThat(caps.methods()).containsExactlyInAnyOrder("SHRED", "INCINERATE", "PROFESSIONAL");
    assertThat(caps.evidenceKinds()).containsExactlyInAnyOrder("PHOTO", "WEIGHT", "CERTIFICATE", "EXECUTOR");
    assertThat(caps.statuses()).containsExactlyInAnyOrder("DRAFT", "SCHEDULED", "DISPOSED", "CANCELLED");
    assertThat(caps.methodNote()).contains("枚举事实").contains("专业销毁").contains("重量");
    assertThat(caps.evidenceNote()).contains("加严").contains("冻结");

    var gate =
        caps.dimensions().stream()
            .filter(d -> d.dimension().equals("evidence_completeness_gate"))
            .findFirst()
            .orElseThrow();
    assertThat(gate.available()).isTrue();
    assertThat(gate.source()).contains("guard_return_disposal_order_state");
    assertThat(gate.impact()).contains("绕过服务层");

    // 🔴 12 财务不存在：费用以本服务自有事实落地
    var finance =
        caps.dimensions().stream()
            .filter(d -> d.dimension().equals("finance_settlement"))
            .findFirst()
            .orElseThrow();
    assertThat(finance.available()).isFalse();
    assertThat(finance.ownerModule()).isEqualTo("12");
    assertThat(finance.impact()).contains("不存在");

    // 🔴 11.2.3.3 供应商索赔在 trade（跨服务）：只产出依据，不写它的表
    var claim =
        caps.dimensions().stream()
            .filter(d -> d.dimension().equals("claim_handoff"))
            .findFirst()
            .orElseThrow();
    assertThat(claim.available()).isFalse();
    assertThat(claim.ownerModule()).isEqualTo("11.2.3.3");
    assertThat(claim.impact()).contains("trade").contains("绝不写");
  }

  // ==================================================================
  // 2. 待销毁清单
  // ==================================================================

  @Test
  @DisplayName("10.7.1.3 待销毁清单：只收 10.7.1.1 判为「弃置销毁」的处置单")
  void candidatesOnlyContainDiscardDispositions() {
    var discard = seedDisposition(a, "DISCARD", "SKU-DISCARD", "5");
    seedDisposition(a, "RESALE", "SKU-RESALE", "3");
    seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-SHIPBACK", "2");

    var page = as(actorA, "channel.operation.read", () -> service.candidates(null, 0, 50));

    assertThat(page.total()).isEqualTo(1);
    assertThat(page.rows()).hasSize(1);
    assertThat(page.rows().get(0).dispositionOrderId()).isEqualTo(discard.orderId());
    assertThat(page.rows().get(0).dispositionMethod()).isEqualTo("DISCARD");
    assertThat(page.totalQty()).isEqualByComparingTo("5");
  }

  @Test
  @DisplayName("10.7.1.3 建销毁单：非「弃置销毁」的上游进不来（服务层 422）")
  void createOrderRejectsNonDiscardUpstream() {
    var resale = seedDisposition(a, "RESALE", "SKU-RESALE", "3");
    var shipback = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-SHIPBACK", "2");

    assertThatThrownBy(() -> create(resale.orderId(), "SHRED", "3"))
        .hasMessageContaining("弃置销毁");
    assertThatThrownBy(() -> create(shipback.orderId(), "SHRED", "2"))
        .hasMessageContaining("弃置销毁");
  }

  @Test
  @DisplayName("10.7.1.3 建销毁单：数量超处置量被拒；同一处置单不能进两张销毁单")
  void createOrderRejectsOversizedQuantityAndDuplicate() {
    var d = seedDisposition(a, "DISCARD", "SKU-DISCARD", "5");

    assertThatThrownBy(() -> create(d.orderId(), "SHRED", "9"))
        .hasMessageContaining("超过处置单的处置量");

    create(d.orderId(), "SHRED", "5");
    assertThatThrownBy(() -> create(d.orderId(), "SHRED", "5"))
        .hasMessageContaining("已经进过销毁单");
  }

  @Test
  @DisplayName("10.7.1.3 销毁方式不是自由文本：未知方式 422")
  void unknownDisposalMethodIsRejected() {
    var d = seedDisposition(a, "DISCARD", "SKU-DISCARD", "5");
    assertThatThrownBy(() -> create(d.orderId(), "BURN_IN_BACKYARD", "5"))
        .hasMessageContaining("销毁方式");
  }

  // ==================================================================
  // 3. 🔴 凭证缺失 ⇒ 不许置为完成态（本模块的核心约束）
  // ==================================================================

  @Test
  @DisplayName("10.7.1.3 🔴 凭证缺失时销毁单进不了完成态（服务端 422 并说清缺哪几样）")
  void disposeIsRefusedWhenEvidenceIsMissing() {
    var d = seedDisposition(a, "DISCARD", "SKU-DISCARD", "5");
    var order = create(d.orderId(), "PROFESSIONAL", "5");
    schedule(order.order().id());

    // 专业销毁要求：照片 + 重量 + 处置证明 + 执行方签核
    var ex = assertThatThrownBy(() -> dispose(order.order().id(), "销毁厂 A"));
    ex.hasMessageContaining("凭证不齐").hasMessageContaining("销毁照片");

    // 补齐照片与执行方签核 —— 还差重量与处置证明
    evidence(order.order().id(), "PHOTO");
    evidence(order.order().id(), "EXECUTOR");
    var ex2 = assertThatThrownBy(() -> dispose(order.order().id(), "销毁厂 A"));
    ex2.hasMessageContaining("重量记录").hasMessageContaining("处置证明编号");

    // 补齐重量
    evidence(order.order().id(), "WEIGHT");
    var ex3 = assertThatThrownBy(() -> dispose(order.order().id(), "销毁厂 A"));
    ex3.hasMessageContaining("处置证明编号");

    // 全部补齐 ⇒ 允许完成
    evidence(order.order().id(), "CERTIFICATE");
    var done = dispose(order.order().id(), "销毁厂 A");
    assertThat(done.order().status()).isEqualTo("DISPOSED");
    assertThat(done.order().weightKg()).isEqualByComparingTo("12.500000");
  }

  @Test
  @DisplayName("10.7.1.3 🔴 绕过服务层直接写 SQL 置为已销毁 ⇒ DB 触发器一样拒")
  void directSqlCannotSkipTheEvidenceGate() {
    var d = seedDisposition(a, "DISCARD", "SKU-DISCARD", "5");
    var order = create(d.orderId(), "PROFESSIONAL", "5");
    schedule(order.order().id());

    // 时刻与执行方都给了 —— 只差凭证，DB 仍要拒
    var ex =
        assertThatThrownBy(
            () ->
                execAsTenant(
                    a,
                    "update return_disposal_order set status='DISPOSED', disposed_at=now(),"
                        + " executed_by='"
                        + user
                        + "', executor_name='销毁厂 A' where tenant_id='"
                        + a
                        + "' and id='"
                        + order.order().id()
                        + "'"));
    ex.hasMessageContaining("PHOTO");

    // 补上照片后仍然差重量与处置证明 ⇒ 继续拒
    evidence(order.order().id(), "PHOTO");
    var ex2 =
        assertThatThrownBy(
            () ->
                execAsTenant(
                    a,
                    "update return_disposal_order set status='DISPOSED', disposed_at=now(),"
                        + " executed_by='"
                        + user
                        + "', executor_name='销毁厂 A' where tenant_id='"
                        + a
                        + "' and id='"
                        + order.order().id()
                        + "'"));
    ex2.hasMessageContaining("WEIGHT");
  }

  @Test
  @DisplayName("10.7.1.3 🔴 已销毁的销毁单不可变（数量 / 重量 / 方式 / 执行方）")
  void disposedOrderIsImmutable() {
    var d = seedDisposition(a, "DISCARD", "SKU-DISCARD", "5");
    var order = create(d.orderId(), "SHRED", "5");
    schedule(order.order().id());
    evidence(order.order().id(), "PHOTO");
    evidence(order.order().id(), "EXECUTOR");
    dispose(order.order().id(), "销毁厂 A");

    var ex =
        assertThatThrownBy(
            () ->
                execAsTenant(
                    a,
                    "update return_disposal_order set weight_kg=999 where tenant_id='"
                        + a
                        + "' and id='"
                        + order.order().id()
                        + "'"));
    ex.hasMessageContaining("cannot be altered");
  }

  @Test
  @DisplayName("10.7.1.3 状态机：只有已排期的销毁单可以执行销毁；非法迁移被拒")
  void stateMachineRejectsIllegalMoves() {
    var d = seedDisposition(a, "DISCARD", "SKU-DISCARD", "5");
    var order = create(d.orderId(), "SHRED", "5");

    // 草稿态直接销毁 ⇒ 409
    assertThatThrownBy(() -> dispose(order.order().id(), "销毁厂 A")).hasMessageContaining("已排期");

    // 草稿 → 已销毁 非法迁移 ⇒ DB 拒
    var ex =
        assertThatThrownBy(
            () ->
                execAsTenant(
                    a,
                    "update return_disposal_order set status='DISPOSED' where tenant_id='"
                        + a
                        + "' and id='"
                        + order.order().id()
                        + "'"));
    ex.hasMessageContaining("cannot move from");
  }

  // ==================================================================
  // 4. 🔴 销毁方式是枚举事实：方式决定凭证要求
  // ==================================================================

  @Test
  @DisplayName("10.7.1.3 🔴 销毁方式决定凭证要求：压碎不要重量、焚烧要处置证明、专业销毁要重量")
  void disposalMethodDeterminesEvidenceRequirements() {
    var shred = create(seedDisposition(a, "DISCARD", "SKU-SHRED", "5").orderId(), "SHRED", "5");
    assertThat(shred.order().requirePhoto()).isTrue();
    assertThat(shred.order().requireWeight()).isFalse();
    assertThat(shred.order().requireCertificate()).isFalse();
    assertThat(shred.order().requireExecutor()).isTrue();

    var burn = create(seedDisposition(a, "DISCARD", "SKU-BURN", "5").orderId(), "INCINERATE", "5");
    assertThat(burn.order().requirePhoto()).isTrue();
    assertThat(burn.order().requireWeight()).isFalse();
    assertThat(burn.order().requireCertificate()).isTrue();
    assertThat(burn.order().requireExecutor()).isTrue();

    var pro = create(seedDisposition(a, "DISCARD", "SKU-PRO", "5").orderId(), "PROFESSIONAL", "5");
    assertThat(pro.order().requirePhoto()).isTrue();
    assertThat(pro.order().requireWeight()).isTrue();
    assertThat(pro.order().requireCertificate()).isTrue();
    assertThat(pro.order().requireExecutor()).isTrue();
  }

  @Test
  @DisplayName("10.7.1.3 🔴 品牌方政策只能加严不能放松；排期后要求被冻结")
  void evidencePolicyCanOnlyTightenAndFreezesOnSchedule() {
    var d = seedDisposition(a, "DISCARD", "SKU-BRAND", "5");
    var order = create(d.orderId(), "SHRED", "5");
    assertThat(order.order().requireWeight()).isFalse();

    // 品牌方合约要求：照片 + 重量 + 处置证明 + 执行方签核
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.createPolicy(
                new CreateDisposalPolicyRequest(
                    "BRAND", "BRAND-A", true, true, true, true, null, null, "品牌合约要求")));

    var d2 = seedDisposition(a, "DISCARD", "SKU-BRAND2", "5");
    var order2 = create(d2.orderId(), "SHRED", "5");
    assertThat(order2.order().requireWeight()).isTrue();
    assertThat(order2.order().requireCertificate()).isTrue();

    // 排期之后旗子被冻结：直接改 SQL 放松要求 ⇒ 被拒
    schedule(order2.order().id());
    var ex =
        assertThatThrownBy(
            () ->
                execAsTenant(
                    a,
                    "update return_disposal_order set require_weight=false where tenant_id='"
                        + a
                        + "' and id='"
                        + order2.order().id()
                        + "'"));
    ex.hasMessageContaining("frozen");

    // 撤销政策也要留理由
    var policies = as(actorA, "channel.operation.read", () -> service.policies(null, null, 0, 50));
    assertThat(policies.total()).isEqualTo(1);
    assertThatThrownBy(() -> as(actorA, "channel.operation.update", () -> service.revokePolicy(
            policies.rows().get(0).id(), new RevokeDisposalPolicyRequest(""))))
        .hasMessageContaining("理由");
  }

  @Test
  @DisplayName("10.7.1.3 🔴 客户端自报的凭证要求被触发器重算覆盖")
  void clientSuppliedRequirementFlagsAreOverwritten() {
    var d = seedDisposition(a, "DISCARD", "SKU-PRO", "5");
    UUID orderId = UUID.randomUUID();
    // 直接建一张「专业销毁」的单子，但**自称**不需要重量
    execAsTenant(
        a,
        "insert into return_disposal_order"
            + " (id,tenant_id,disposal_no,disposition_order_id,decision_id,source_reference,"
            + "  receipt_no,sku_code,sku_name,batch_no,brand_code,platform_code,disposal_method,"
            + "  disposal_qty,weight_unit,cost_currency,require_photo,require_weight,"
            + "  require_certificate,require_executor,note)"
            + " values ('" + orderId + "','" + a + "','DSP-RAW','" + d.orderId() + "','"
            + d.decisionId() + "','" + d.dispositionNo() + "','" + d.receiptNo() + "','SKU-PRO','样机','B-1',"
            + "'BRAND-A','AMAZON','PROFESSIONAL',5,'KG','USD',true,false,false,false,'自报旗子')");

    var detail = as(actorA, "channel.operation.read", () -> service.orderDetail(orderId));
    assertThat(detail.order().requireWeight()).isTrue();
    assertThat(detail.order().requireCertificate()).isTrue();
  }

  // ==================================================================
  // 5. 凭证
  // ==================================================================

  @Test
  @DisplayName("10.7.1.3 凭证：每类有专属必填字段；一类凭证一张（重复登记改写不新增）")
  void evidenceRequiresKindSpecificFields() {
    var d = seedDisposition(a, "DISCARD", "SKU-DISCARD", "5");
    var order = create(d.orderId(), "SHRED", "5");

    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.recordEvidence(
                            order.order().id(),
                            new RecordDisposalEvidenceRequest(
                                "PHOTO", null, null, null, null, null, "空照片"))))
        .hasMessageContaining("照片地址");
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.recordEvidence(
                            order.order().id(),
                            new RecordDisposalEvidenceRequest(
                                "WEIGHT", null, null, null, null, null, "空重量"))))
        .hasMessageContaining("重量");
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.recordEvidence(
                            order.order().id(),
                            new RecordDisposalEvidenceRequest(
                                "CERTIFICATE", null, null, null, null, null, "空编号"))))
        .hasMessageContaining("处置证明编号");

    evidence(order.order().id(), "PHOTO");
    evidence(order.order().id(), "PHOTO");
    var detail = as(actorA, "channel.operation.read", () -> service.orderDetail(order.order().id()));
    assertThat(detail.evidence()).hasSize(1);
  }

  @Test
  @DisplayName("10.7.1.3 凭证：已销毁的单子不再接受新凭证")
  void disposedOrderAcceptsNoFurtherEvidence() {
    var d = seedDisposition(a, "DISCARD", "SKU-DISCARD", "5");
    var order = create(d.orderId(), "SHRED", "5");
    schedule(order.order().id());
    evidence(order.order().id(), "PHOTO");
    evidence(order.order().id(), "EXECUTOR");
    dispose(order.order().id(), "销毁厂 A");

    assertThatThrownBy(() -> evidence(order.order().id(), "PHOTO")).hasMessageContaining("已销毁");
  }

  // ==================================================================
  // 6. 费用
  // ==================================================================

  @Test
  @DisplayName("10.7.1.3 销毁费用：混币种被拒；合计不落表（查询时现算）")
  void disposalCostRejectsMixedCurrency() {
    var d = seedDisposition(a, "DISCARD", "SKU-DISCARD", "5");
    var order = create(d.orderId(), "SHRED", "5");

    as(
        actorA,
        "channel.operation.create",
        () ->
            service.recordCost(
                order.order().id(),
                new RecordDisposalCostRequest(
                    "DESTROY_FEE", new BigDecimal("12.34"), "USD", "销毁厂 A", null, "夹具费用")));

    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.recordCost(
                            order.order().id(),
                            new RecordDisposalCostRequest(
                                "PICKUP_FEE", new BigDecimal("3.00"), "EUR", "", null, "混币种"))))
        .hasMessageContaining("币种");

    var detail = as(actorA, "channel.operation.read", () -> service.orderDetail(order.order().id()));
    assertThat(detail.costs()).hasSize(1);
    assertThat(detail.order().totalCost()).isEqualByComparingTo("12.34");
  }

  // ==================================================================
  // 7. 索赔依据
  // ==================================================================

  @Test
  @DisplayName("10.7.1.3 索赔依据：未销毁的单子不许生成；数量与重量必须与销毁单逐字相同")
  void claimBasisRequiresDisposedOrderAndMatchesQuantity() {
    var d = seedDisposition(a, "DISCARD", "SKU-DISCARD", "5");
    var order = create(d.orderId(), "SHRED", "5");
    schedule(order.order().id());

    // 未销毁 ⇒ 409
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.createClaimBasis(
                            order.order().id(),
                            new CreateDisposalClaimBasisRequest(
                                "BRAND", "BRAND-A", new BigDecimal("100.00"), "USD", "夹具依据"))))
        .hasMessageContaining("已销毁");

    evidence(order.order().id(), "PHOTO");
    evidence(order.order().id(), "EXECUTOR");
    var done = dispose(order.order().id(), "销毁厂 A");

    var basis =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createClaimBasis(
                    done.order().id(),
                    new CreateDisposalClaimBasisRequest(
                        "BRAND", "BRAND-A", new BigDecimal("100.00"), "USD", "夹具依据")));
    assertThat(basis.claimBases()).hasSize(1);
    var row = basis.claimBases().get(0);
    assertThat(row.claimQty()).isEqualByComparingTo("5");
    assertThat(row.evidenceComplete()).isTrue();
    assertThat(row.sourceReference()).isEqualTo(d.dispositionNo());
    assertThat(row.status()).isEqualTo("PENDING");

    // 绕过服务层塞一个数量不同的依据 ⇒ DB 拒
    var ex =
        assertThatThrownBy(
            () ->
                execAsTenant(
                    a,
                    "insert into return_disposal_claim_basis"
                        + " (id,tenant_id,disposal_order_id,claim_type,counterparty_code,claim_qty,"
                        + "  claim_weight_kg,claimed_amount,claimed_currency,source_reference)"
                        + " values ('" + UUID.randomUUID() + "','" + a + "','" + done.order().id()
                        + "','BRAND','BRAND-A',99,0,10.00,'USD','RDO-X')"));
    ex.hasMessageContaining("quantity must match");

    // 重复生成依据 ⇒ 409
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.createClaimBasis(
                            done.order().id(),
                            new CreateDisposalClaimBasisRequest(
                                "BRAND", "BRAND-A", new BigDecimal("100.00"), "USD", "再来一条"))))
        .hasMessageContaining("索赔两次");
  }

  @Test
  @DisplayName("10.7.1.3 索赔依据：提交下游要回填下游单号；驳回要写理由")
  void claimBasisSubmitAndReject() {
    var d = seedDisposition(a, "DISCARD", "SKU-DISCARD", "5");
    var order = create(d.orderId(), "SHRED", "5");
    schedule(order.order().id());
    evidence(order.order().id(), "PHOTO");
    evidence(order.order().id(), "EXECUTOR");
    var done = dispose(order.order().id(), "销毁厂 A");
    var basis =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createClaimBasis(
                    done.order().id(),
                    new CreateDisposalClaimBasisRequest(
                        "SUPPLIER", "SUP-001", new BigDecimal("50.00"), "USD", "夹具依据")));
    UUID claimId = basis.claimBases().get(0).id();

    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () -> service.submitClaimBasis(claimId, new SubmitDisposalClaimBasisRequest("", null))))
        .hasMessageContaining("下游索赔单号");

    var submitted =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.submitClaimBasis(
                    claimId, new SubmitDisposalClaimBasisRequest("CLM-2026-0001", "已提交")));
    assertThat(submitted.claimBases().get(0).status()).isEqualTo("SUBMITTED");
    assertThat(submitted.claimBases().get(0).claimRef()).isEqualTo("CLM-2026-0001");

    // 终态不许再改
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.rejectClaimBasis(
                            claimId, new RejectDisposalClaimBasisRequest("事后反悔"))))
        .hasMessageContaining("不能重复");
  }

  // ==================================================================
  // 8. 概览 / 列表 / 留痕 / 端点
  // ==================================================================

  @Test
  @DisplayName("10.7.1.3 概览与列表：真分页两页不重叠；概览是全量口径")
  void overviewAndListAreReal() {
    for (int i = 0; i < 3; i++) {
      create(seedDisposition(a, "DISCARD", "SKU-" + i, "5").orderId(), "SHRED", "5");
    }
    var first = as(actorA, "channel.operation.read", () -> service.orders(null, null, null, 0, 2));
    var second = as(actorA, "channel.operation.read", () -> service.orders(null, null, null, 1, 2));
    assertThat(first.total()).isEqualTo(3);
    assertThat(second.total()).isEqualTo(3);
    assertThat(first.rows()).hasSize(2);
    assertThat(second.rows()).hasSize(1);
    assertThat(first.rows().get(0).id()).isNotEqualTo(second.rows().get(0).id());

    var overview = as(actorA, "channel.operation.read", () -> service.overview());
    assertThat(overview.orderCount()).isEqualTo(3);
    assertThat(overview.draftCount()).isEqualTo(3);
    // 三条处置单都已经进了销毁单 ⇒ 候选清单里不再有它们
    assertThat(overview.candidateCount()).isZero();
  }

  @Test
  @DisplayName("10.7.1.3 留痕是追加式的：改与删都被 DB 拒")
  void eventsAreAppendOnly() {
    var d = seedDisposition(a, "DISCARD", "SKU-DISCARD", "5");
    var order = create(d.orderId(), "SHRED", "5");
    var events = as(actorA, "channel.operation.read", () -> service.events(order.order().id(), 0, 50));
    assertThat(events.total()).isGreaterThanOrEqualTo(1);
    assertThat(events.rows().get(0).eventType()).isEqualTo("ORDER_CREATED");

    var ex =
        assertThatThrownBy(
            () ->
                execAsTenant(
                    a,
                    "update return_disposal_event set detail='改过了' where tenant_id='"
                        + a
                        + "' and order_id='"
                        + order.order().id()
                        + "'"));
    ex.hasMessageContaining("append-only");
  }

  @Test
  @DisplayName("10.7.1.3 端点：401 未认证 / 404 不存在 / 422 非法输入")
  void endpointStatusCodes() throws Exception {
    http.perform(get("/channel/return-disposals/orders")).andExpect(status().isUnauthorized());

    http.perform(get("/channel/return-disposals/orders").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    http.perform(
            get("/channel/return-disposals/orders/" + UUID.randomUUID())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());

    var d = seedDisposition(a, "DISCARD", "SKU-DISCARD", "5");
    http.perform(
            post("/channel/return-disposals/orders")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"dispositionOrderId\":\"" + d.orderId() + "\",\"disposalMethod\":\"NOPE\","
                        + "\"disposalQty\":5,\"costCurrency\":\"USD\"}"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  @DisplayName("10.7.1.3 时刻往返：timestamptz 不偏 8 小时，且断言写回的值")
  void timestampsRoundTripWithoutOffsetDrift() {
    var d = seedDisposition(a, "DISCARD", "SKU-DISCARD", "5");
    var order = create(d.orderId(), "SHRED", "5");
    schedule(order.order().id());
    evidence(order.order().id(), "PHOTO");
    evidence(order.order().id(), "EXECUTOR");
    var done = dispose(order.order().id(), "销毁厂 A");

    assertThat(done.order().plannedAt()).isEqualTo(OffsetDateTime.of(2026, 5, 1, 8, 0, 0, 0, ZoneOffset.UTC));
    assertThat(done.order().disposedAt()).isEqualTo(OffsetDateTime.of(2026, 5, 2, 10, 0, 0, 0, ZoneOffset.UTC));
    var ev = done.evidence().stream().filter(e -> "PHOTO".equals(e.evidenceKind())).findFirst().orElseThrow();
    assertThat(ev.occurredAt()).isEqualTo(OffsetDateTime.of(2026, 5, 2, 9, 30, 0, 0, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("10.7.1.3 跨租户：租户 B 看不到租户 A 的销毁单（同为 404）")
  void otherTenantCannotSeeTheOrder() {
    var d = seedDisposition(a, "DISCARD", "SKU-DISCARD", "5");
    var order = create(d.orderId(), "SHRED", "5");
    assertThatThrownBy(() -> as(actorB, "channel.operation.read", () -> service.orderDetail(order.order().id())))
        .hasMessageContaining("不存在");
    var list = as(actorB, "channel.operation.read", () -> service.orders(null, null, null, 0, 50));
    assertThat(list.total()).isZero();
  }
}
