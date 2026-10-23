package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.BadDebtModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 12.1.1.3 坏账与核销的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖的是蓝图 12-finance.md 里 12.1.1.3 那几条**加粗句**与「输入 / 输出」：
 *
 * <ul>
 *   <li>「处理坏账：争议损失确认、买家拒付、平台冻结无法收回的核销，**保留核销依据与审批**」
 *       ⇒ 申请、依据、审批三样各有各的落法；依据与审批都留痕。
 *   <li>「输入：核销申请；**依据材料**；审批」 ⇒ 依据材料可多项、必填、可自证。
 *   <li>「输出：坏账核销记录 + **会计处理** + 审计留痕」
 *       ⇒ 核销记录落库；**会计处理当前不可产生**（下游 12.5.2.1 未交付），
 *       表现为「归属模块 12.5.2.1 + 空凭证号」，**不是一个 0**；审计留痕追加式。
 *   <li>「**核销是财务终审动作**，需要完整依据链，**不允许批量一键核销无审批**」
 *       ⇒ 审批人 ≠ 提交人、审批意见必填、提交前必须��依据；
 *       ⇒ **不存在任何批量核销端点**。
 * </ul>
 *
 * <p><b>断言风格与本仓其它模块一致</b>：业务规则与拒绝理由走**服务层**，HTTP 层只断言**状态码**；
 * 数据层的不变量用**直连 SQL** 单独再拒一次。
 *
 * <p><b>金额逐位相等</b>：JSON mapper 开了 {@code USE_BIG_DECIMAL_FOR_FLOATS}，
 * 否则 {@code 60.0000} 会被读成 {@code 60.0} —— 那样就分不出「金额是四位小数」和「金额丢了一位」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BadDebtIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID shopA = UUID.randomUUID();
  static final UUID customer = UUID.randomUUID();
  static final UUID submitterUser = UUID.randomUUID();
  static final UUID approverUser = UUID.randomUUID();

  static final List<String> PERMISSIONS =
      List.of(
          "finance.baddebt.read",
          "finance.baddebt.create",
          "finance.baddebt.update",
          "finance.baddebt.approve",
          // 12.1.1.2 的账龄端点（用来证明「作废掉的应收自动从账龄里消失」）
          "finance.receivable.read");

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  /** 上游 11.4.1.1 纠纷的假实现：id → 纠纷头。 */
  static final Map<UUID, Map<String, Object>> upstreamDisputes = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer trade;

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role finance_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put(
        "submitter",
        new AccessService.Access(
            submitterUser,
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            PERMISSIONS,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", PERMISSIONS))));
    actors.put(
        "approver",
        new AccessService.Access(
            approverUser,
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            PERMISSIONS,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", PERMISSIONS))));
    try {
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
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.setExecutor(Executors.newFixedThreadPool(2));
      identity.start();

      // 上游 11.4.1.1 纠纷（trade 服务）：只读形状 GET /trade/platform-disputes/{id}
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/platform-disputes",
          e -> {
            String path = e.getRequestURI().getPath();
            String id = path.substring(path.lastIndexOf('/') + 1);
            var head = upstreamDisputes.get(UUID.fromString(id));
            if (head == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(Map.of("head", head));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      trade.setExecutor(Executors.newFixedThreadPool(2));
      trade.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + trade.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_bad_debt_event, finance_bad_debt_evidence, finance_bad_debt_case,"
              + " finance_receivable_event, finance_collection_task, finance_receivable_line,"
              + " finance_receivable, finance_receivable_policy, finance_aging_bucket restart identity cascade");
    }
    upstreamDisputes.clear();
    // 12.1.1.1 的应收确认政策：承接单要用「其他」来源建 ⇒ 必须先启用它。
    as(
        () -> {
          sql.execute(
              "insert into finance_receivable_policy(id,tenant_id,source,enabled,due_days,created_at,updated_at)"
                  + " values(?,?,'OTHER',true,0,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC));
          return null;
        });
  }

  /** 在**租户事务里**跑一段工作（与生产同一条路径：RLS 靠它隔离）。 */
  <T> T as(Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  /** 在**租户事务里**读一行。 */
  Record one(String statement, Object... binds) {
    return as(() -> sql.fetchOne(statement, binds));
  }

  /**
   * 种一张应收单（走数据层：DRAFT → 落明细 → OPEN）。
   *
   * <p>⚠️ 走**数据层**而不是服务层：这里要控制的是**已核销金额**，而那在服务层是由
   * 12.2.1.2 核销的写路径决定的（核销属 12.2.1.2，未交付）。
   *
   * @param settled 行级已核销金额（"0" = 一分未收）
   */
  UUID seedReceivable(
      String no, String currency, String amount, String settled, LocalDate dueDate, String shopName) {
    return as(
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          var id = UUID.randomUUID();
          sql.execute(
              "insert into finance_receivable(id,tenant_id,receivable_no,source,source_system,"
                  + " counterparty_type,counterparty_ref,counterparty_name,shop_id,shop_name,currency,"
                  + " due_date,confirmed_at,status,dispute_flag,dispute_note,created_at,updated_at)"
                  + " values(?,?,?,'MANUAL','manual','CUSTOMER',?,?,?,?,?,?::date,?::timestamptz,'DRAFT',"
                  + "false,'',?::timestamptz,?::timestamptz)",
              id,
              tenant,
              no,
              customer,
              "客户一",
              shopA,
              shopName,
              currency,
              dueDate,
              now,
              now,
              now);
          sql.execute(
              "insert into finance_receivable_line(id,tenant_id,receivable_id,line_no,description,"
                  + " amount,settled_amount,created_at,updated_at)"
                  + " values(?,?,?,1,'测试明细',?,0,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              id,
              new BigDecimal(amount),
              now,
              now);
          sql.execute("update finance_receivable set status='OPEN' where id = ?", id);
          // ⚠️ 已核销金额必须在单头确认**之后**写：V338 的触发器只在明细行变动时
          //   才把行级核销额派生到单头，草稿状态下不动 settled_amount。
          if (!"0".equals(settled)) {
            sql.execute(
                "update finance_receivable_line set settled_amount = ?::numeric where receivable_id = ?",
                new BigDecimal(settled),
                id);
          }
          return id;
        });
  }

  /** 上游 11.4.1.1 纠纷的快照。 */
  void seedDispute(UUID id, String no, String status, String currency, String amount) {
    upstreamDisputes.put(
        id,
        Map.of(
            "disputeNo", no,
            "platformCode", "AMAZON",
            "platformDisputeNo", "PD-" + no,
            "disputeType", "ITEM_NOT_RECEIVED",
            "disputeCurrency", currency,
            "disputeAmount", new BigDecimal(amount),
            "disputeStatus", status,
            "resolvedAt", OffsetDateTime.now(ZoneOffset.UTC).toString()));
  }

  // ================================================================ HTTP 小工具

  String body(String session, String path, Object payload) throws Exception {
    var res =
        http.perform(
                post("/finance/bad-debt" + path)
                    .header("Authorization", session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andReturn()
            .getResponse();
    assertThat(res.getStatus()).as("响应体：%s", res.getContentAsString()).isEqualTo(200);
    return res.getContentAsString();
  }

  int statusOf(String session, String path, Object payload) throws Exception {
    return http.perform(
            post("/finance/bad-debt" + path)
                .header("Authorization", session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload == null ? "{}" : json.writeValueAsString(payload)))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  FinanceBadDebtRow createCase(UUID receivableId, String amount, String reasonType, String detail)
      throws Exception {
    var payload =
        new FinanceBadDebtInput(
            receivableId, new BigDecimal(amount), BadDebtReasonType.valueOf(reasonType), detail, null, null);
    return json.readValue(body("submitter", "", payload), FinanceBadDebtRow.class);
  }

  /** 加一条依据材料。 */
  void addEvidence(UUID caseId, String type, String title) throws Exception {
    var payload =
        new FinanceBadDebtEvidenceInput(
            BadDebtEvidenceType.valueOf(type), title, "REF-" + title, "内容：" + title, null, null);
    body("submitter", "/" + caseId + "/evidence", payload);
  }

  /** 走完「加依据 → 提交 → 审批」到已批准待核销。 */
  FinanceBadDebtRow approve(UUID caseId) throws Exception {
    addEvidence(caseId, "PLATFORM_DISPUTE_DECISION", "平台裁决书");
    http.perform(post("/finance/bad-debt/" + caseId + "/submit").header("Authorization", "submitter"))
        .andExpect(status().isOk());
    return json.readValue(
        body("approver", "/" + caseId + "/approve", new FinanceBadDebtDecisionInput("证据齐全，同意核销")),
        FinanceBadDebtRow.class);
  }

  // ================================================================ 建单与快照

  @Test
  void 建单时快照目标应收的账() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC).minusDays(10), "店铺 A");
    var c = createCase(r, "60.0000", "PLATFORM_FROZEN", "平台冻结该笔货款");

    assertThat(c.status()).isEqualTo(BadDebtStatus.DRAFT);
    assertThat(c.receivableNo()).isEqualTo("AR-1");
    assertThat(c.receivableAmount()).isEqualByComparingTo("100.0000");
    assertThat(c.receivableSettled()).isEqualByComparingTo("0.0000");
    assertThat(c.openBalance()).isEqualByComparingTo("100.0000");
    assertThat(c.writeOffAmount()).isEqualByComparingTo("60.0000");
    assertThat(c.currency()).isEqualTo("USD");
    assertThat(c.reasonType()).isEqualTo(BadDebtReasonType.PLATFORM_FROZEN);
    // 单号是本模块自己的序列（不是复用应收单号）。
    assertThat(c.caseNo()).startsWith("BD-");
  }

  @Test
  void 核销金额超过未核销余额被拒() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var payload =
        new FinanceBadDebtInput(
            r, new BigDecimal("120"), BadDebtReasonType.PLATFORM_FROZEN, "超额", null, null);
    assertThat(statusOf("submitter", "", payload)).isEqualTo(400);
  }

  @Test
  void 已部分回款的应收不受理() throws Exception {
    // 12.2.1.2 的核销写路径已把 40 核销掉了 ⇒ 这张应收有一半是「已收回应收」。
    var r = seedReceivable("AR-1", "USD", "100.0000", "40.0000", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var payload =
        new FinanceBadDebtInput(
            r, new BigDecimal("60"), BadDebtReasonType.PLATFORM_FROZEN, "冻结", null, null);
    assertThat(statusOf("submitter", "", payload)).isEqualTo(400);
  }

  @Test
  void 核销金额必须大于零且事由必须写明() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    assertThat(
            statusOf(
                "submitter",
                "",
                new FinanceBadDebtInput(
                    r, new BigDecimal("0"), BadDebtReasonType.PLATFORM_FROZEN, "冻结", null, null)))
        .isEqualTo(400);
    assertThat(
            statusOf(
                "submitter",
                "",
                new FinanceBadDebtInput(
                    r, new BigDecimal("10"), BadDebtReasonType.PLATFORM_FROZEN, "  ", null, null)))
        .isEqualTo(400);
  }

  // ================================================================ 依据链

  @Test
  void 没有依据材料不许提交审批() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var c = createCase(r, "60.0000", "PLATFORM_FROZEN", "平台冻结");
    assertThat(statusOf("submitter", "/" + c.id() + "/submit", null)).isEqualTo(400);
  }

  @Test
  void 绕过服务层直连sql提交零依据的申请也被db拒() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var c = createCase(r, "60.0000", "PLATFORM_FROZEN", "平台冻结");
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_bad_debt_case set status='PENDING_APPROVAL', submitted_by=?,"
                              + " submitted_at=?::timestamptz where id = ?",
                          submitterUser,
                          OffsetDateTime.now(ZoneOffset.UTC),
                          c.id());
                      return null;
                    }))
        .as("DB 守卫必须挡住「零依据就提交」")
        .hasStackTraceContaining("依据材料");
  }

  @Test
  void 依据材料提交后不可再动() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var c = createCase(r, "60.0000", "PLATFORM_FROZEN", "平台冻结");
    addEvidence(c.id(), "PLATFORM_FROZEN_NOTICE", "冻结通知");
    http.perform(post("/finance/bad-debt/" + c.id() + "/submit").header("Authorization", "submitter"))
        .andExpect(status().isOk());

    // 服务层拒绝
    var payload =
        new FinanceBadDebtEvidenceInput(
            BadDebtEvidenceType.OTHER, "事后补的", null, "事后补的内容", null, null);
    assertThat(statusOf("submitter", "/" + c.id() + "/evidence", payload)).isEqualTo(400);
    // DB 也拒绝（直连 SQL）
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "insert into finance_bad_debt_evidence(id,tenant_id,case_id,evidence_no,"
                              + " evidence_type,title,reference_no,content,attachment_ref,occurred_on,"
                              + " created_by,created_at) values(?,?,?,9,'OTHER','事后补的','','内容','',null,?,"
                              + " ?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          c.id(),
                          submitterUser,
                          OffsetDateTime.now(ZoneOffset.UTC));
                      return null;
                    }))
        .as("依据链冻结必须在 DB 层也成立")
        .hasStackTraceContaining("依据材料不可再改");
  }

  @Test
  void 依据材料可以移除但只进不出_物理删除被收回() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var c = createCase(r, "60.0000", "PLATFORM_FROZEN", "平台冻结");
    addEvidence(c.id(), "PLATFORM_FROZEN_NOTICE", "冻结通知");

    var detail =
        json.readValue(
            http.perform(get("/finance/bad-debt/" + c.id()).header("Authorization", "submitter"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceBadDebtDetail.class);
    assertThat(detail.evidence()).hasSize(1);
    var evidenceId = detail.evidence().get(0).id();

    // 移除：软移除（status='REMOVED'），不是物理删除。
    http.perform(
            post("/finance/bad-debt/" + c.id() + "/evidence/" + evidenceId + "/remove")
                .header("Authorization", "submitter"))
        .andExpect(status().isOk());

    var after =
        json.readValue(
            http.perform(get("/finance/bad-debt/" + c.id()).header("Authorization", "submitter"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceBadDebtDetail.class);
    assertThat(after.evidence()).as("已移除的依据不再出现在依据链里").isEmpty();
    assertThat(after.events())
        .extracting(FinanceBadDebtEventRow::eventType)
        .contains("EVIDENCE_REMOVED");

    // ⚠️ 运行角色对依据表的 DELETE 权限是收回的 ⇒ 物理删除在本服务里根本做不到。
    var privilege =
        one(
            "select has_table_privilege('finance_app','finance_bad_debt_evidence','DELETE') as d");
    assertThat(privilege.get("d", Boolean.class)).as("依据材料不可物理删除").isFalse();

    // 已移除的依据不可复活（依据链只进不出）。
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_bad_debt_evidence set status='ACTIVE' where id = ?", evidenceId);
                      return null;
                    }))
        .as("依据链只进不出")
        .hasStackTraceContaining("不可恢复");
  }

  @Test
  void 已移除的依据不算依据_提交审批仍被拒() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var c = createCase(r, "60.0000", "PLATFORM_FROZEN", "平台冻结");
    addEvidence(c.id(), "PLATFORM_FROZEN_NOTICE", "冻结通知");
    var detail =
        json.readValue(
            http.perform(get("/finance/bad-debt/" + c.id()).header("Authorization", "submitter"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceBadDebtDetail.class);
    http.perform(
            post("/finance/bad-debt/" + c.id() + "/evidence/" + detail.evidence().get(0).id() + "/remove")
                .header("Authorization", "submitter"))
        .andExpect(status().isOk());
    assertThat(statusOf("submitter", "/" + c.id() + "/submit", null))
        .as("依据全被移除 ⇒ 依据链不完整 ⇒ 不许提交审批")
        .isEqualTo(400);
  }

  // ================================================================ 审批

  @Test
  void 审批人不能是提交人() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var c = createCase(r, "60.0000", "PLATFORM_FROZEN", "平台冻结");
    addEvidence(c.id(), "PLATFORM_FROZEN_NOTICE", "冻结通知");
    http.perform(post("/finance/bad-debt/" + c.id() + "/submit").header("Authorization", "submitter"))
        .andExpect(status().isOk());
    // 提交人自己批 ⇒ 400
    assertThat(
            statusOf("submitter", "/" + c.id() + "/approve", new FinanceBadDebtDecisionInput("自己批")))
        .isEqualTo(400);
  }

  @Test
  void 绕过服务层直连sql自己批自己也被db拒() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var c = createCase(r, "60.0000", "PLATFORM_FROZEN", "平台冻结");
    addEvidence(c.id(), "PLATFORM_FROZEN_NOTICE", "冻结通知");
    http.perform(post("/finance/bad-debt/" + c.id() + "/submit").header("Authorization", "submitter"))
        .andExpect(status().isOk());
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_bad_debt_case set status='APPROVED', decided_by=?,"
                              + " decided_at=?::timestamptz, decision_comment='自己批' where id = ?",
                          submitterUser,
                          OffsetDateTime.now(ZoneOffset.UTC),
                          c.id());
                      return null;
                    }))
        .as("DB 守卫必须挡住「自己批自己」")
        .hasStackTraceContaining("审批人与提交人是同一个人");
  }

  @Test
  void 审批意见必填() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var c = createCase(r, "60.0000", "PLATFORM_FROZEN", "平台冻结");
    addEvidence(c.id(), "PLATFORM_FROZEN_NOTICE", "冻结通知");
    http.perform(post("/finance/bad-debt/" + c.id() + "/submit").header("Authorization", "submitter"))
        .andExpect(status().isOk());
    assertThat(statusOf("approver", "/" + c.id() + "/approve", new FinanceBadDebtDecisionInput("  ")))
        .isEqualTo(400);
    assertThat(statusOf("approver", "/" + c.id() + "/reject", new FinanceBadDebtDecisionInput("")))
        .isEqualTo(400);
  }

  @Test
  void 审批人与审批时刻与意见都落库() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var c = createCase(r, "60.0000", "PLATFORM_FROZEN", "平台冻结");
    var approved = approve(c.id());
    assertThat(approved.status()).isEqualTo(BadDebtStatus.APPROVED);
    assertThat(approved.decidedBy()).isEqualTo(approverUser);
    assertThat(approved.submittedBy()).isEqualTo(submitterUser);
    assertThat(approved.decidedAt()).isNotNull();
    assertThat(approved.decisionComment()).isEqualTo("证据齐全，同意核销");
  }

  // ================================================================ 核销执行

  @Test
  void 核销走作废加新单且不写settled_amount() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC).plusDays(30), "店铺 A");
    var c = createCase(r, "60.0000", "PLATFORM_FROZEN", "平台冻结");
    approve(c.id());
    var written = json.readValue(body("approver", "/" + c.id() + "/write-off", null), FinanceBadDebtRow.class);

    assertThat(written.status()).isEqualTo(BadDebtStatus.WRITTEN_OFF);
    assertThat(written.writeOffMethod()).isEqualTo(BadDebtWriteOffMethod.VOID_AND_REPLACE);
    assertThat(written.writeOffAt()).isNotNull();
    assertThat(written.voidedReceivableNo()).isEqualTo("AR-1");

    // ⚠️ settled_amount **一个字节都没被写** —— 它的写者恒为 12.2.1.2。
    var line =
        one(
            "select settled_amount from finance_receivable_line where receivable_id = ?", r);
    assertThat(line.get("settled_amount", BigDecimal.class))
        .as("坏账核销**不写** settled_amount：那一列的写者恒为 12.2.1.2 核销")
        .isEqualByComparingTo("0");

    // 原单被作废。
    var head = one("select status from finance_receivable where id = ?", r);
    assertThat(head.get("status", String.class)).isEqualTo("VOID");

    // 承接单承接了仍可收回的 40。
    assertThat(written.replacementReceivableNo()).isNotBlank();
    var replacement =
        one(
            "select amount, currency, status from finance_receivable where id = ?",
            written.replacementReceivableId());
    assertThat(replacement.get("amount", BigDecimal.class)).isEqualByComparingTo("40.0000");
    assertThat(replacement.get("currency", String.class)).isEqualTo("USD");
    assertThat(replacement.get("status", String.class)).isEqualTo("OPEN");
    // 原单未逾期 ⇒ 承接单到期日沿用原单（未被调整）。
    assertThat(written.replacementDueDateAdjusted()).isFalse();
  }

  @Test
  void 全额核销时不新建承接单() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC).plusDays(30), "店铺 A");
    var c = createCase(r, "100.0000", "PLATFORM_FROZEN", "平台冻结");
    approve(c.id());
    var written = json.readValue(body("approver", "/" + c.id() + "/write-off", null), FinanceBadDebtRow.class);

    assertThat(written.replacementReceivableNo()).isEmpty();
    assertThat(written.replacementReceivableId()).isNull();
    assertThat(written.writeOffMethod()).isEqualTo(BadDebtWriteOffMethod.VOID_AND_REPLACE);
    var count = one("select count(*) as n from finance_receivable").get("n", Long.class);
    assertThat(count).as("原单作废 + 没有承接单 ⇒ 账上只剩一张作废单").isEqualTo(1L);
  }

  @Test
  void 原单已逾期时承接单到期日取今天并如实标注() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC).minusDays(90), "店铺 A");
    var c = createCase(r, "60.0000", "PLATFORM_FROZEN", "平台冻结");
    approve(c.id());
    var written = json.readValue(body("approver", "/" + c.id() + "/write-off", null), FinanceBadDebtRow.class);

    assertThat(written.replacementDueDateAdjusted())
        .as("12.1.1.1 的建单端点不允许到期日早于确认日 ⇒ 原单逾期时承接单到期日取今天，且必须标出来")
        .isTrue();
    var due =
        one(
                "select due_date from finance_receivable where id = ?",
                written.replacementReceivableId())
            .get("due_date", LocalDate.class);
    assertThat(due).isEqualTo(LocalDate.now(ZoneOffset.UTC));
  }

  @Test
  void 会计处理不可用_归属模块写明且凭证号恒空() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC).plusDays(30), "店铺 A");
    var c = createCase(r, "60.0000", "PLATFORM_FROZEN", "平台冻结");
    approve(c.id());
    var written = json.readValue(body("approver", "/" + c.id() + "/write-off", null), FinanceBadDebtRow.class);

    assertThat(written.accountingStatus()).isEqualTo("PENDING_FINANCE");
    assertThat(written.accountingOwnerModule()).isEqualTo("12.5.2.1");
    assertThat(written.accountingRef())
        .as("绝不造凭证：凭证号恒为空串，不是一个 0")
        .isEmpty();
  }

  @Test
  void 直连sql写凭证号也被db拒() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC).plusDays(30), "店铺 A");
    var c = createCase(r, "60.0000", "PLATFORM_FROZEN", "平台冻结");
    approve(c.id());
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_bad_debt_case set accounting_ref='VOUCHER-1' where id = ?", c.id());
                      return null;
                    }))
        .as("凭证号由 CHECK 钉死为空串")
        .hasStackTraceContaining("finance_bad_debt_accounting_ck");
  }

  @Test
  void 终态整行冻结() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC).plusDays(30), "店铺 A");
    var c = createCase(r, "60.0000", "PLATFORM_FROZEN", "平台冻结");
    approve(c.id());
    body("approver", "/" + c.id() + "/write-off", null);
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_bad_debt_case set write_off_amount=1 where id = ?", c.id());
                      return null;
                    }))
        .as("已核销是终态：一列都不许再改")
        .hasStackTraceContaining("终态");
  }

  @Test
  void 状态机非法转换被db拒() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var c = createCase(r, "60.0000", "PLATFORM_FROZEN", "平台冻结");
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_bad_debt_case set status='WRITTEN_OFF' where id = ?", c.id());
                      return null;
                    }))
        .as("DRAFT 不能直接跳到 WRITTEN_OFF")
        .hasStackTraceContaining("状态不能从");
  }

  @Test
  void 新建申请必须先是草稿() {
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "insert into finance_bad_debt_case(id,tenant_id,case_no,receivable_id,"
                              + " receivable_no,counterparty_name,currency,receivable_amount,"
                              + " receivable_settled,open_balance,write_off_amount,reason_type,"
                              + " reason_detail,status,created_at,updated_at)"
                              + " values(?,?,'BD-X',?,?,'客户','USD',100,0,100,50,'OTHER','手工',"
                              + "'PENDING_APPROVAL',?::timestamptz,?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          UUID.randomUUID(),
                          "AR-X",
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC));
                      return null;
                    }))
        .as("新单必须先是草稿")
        .hasStackTraceContaining("必须先是草稿");
  }

  @Test
  void 核销后应收账龄里这一笔自动消失() throws Exception {
    // 12.1.1.2 的账龄分布读的是 12.1.1.1 的应收单 ⇒ 作废掉的应收自动不在分布里。
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC).minusDays(90), "店铺 A");
    var before =
        json.readValue(
            http.perform(get("/finance/receivable-aging/distribution").header("Authorization", "submitter"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ReceivableAgingModels.FinanceReceivableAgingDistribution.class);
    assertThat(before.totalCount()).isEqualTo(1);

    var c = createCase(r, "100.0000", "PLATFORM_FROZEN", "平台冻结");
    approve(c.id());
    body("approver", "/" + c.id() + "/write-off", null);

    var after =
        json.readValue(
            http.perform(get("/finance/receivable-aging/distribution").header("Authorization", "submitter"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ReceivableAgingModels.FinanceReceivableAgingDistribution.class);
    assertThat(after.totalCount()).as("作废掉的应收不再进账龄分布").isZero();
  }

  // ================================================================ 上游纠纷

  @Test
  void 争议损失确认要求上游纠纷已判我方败诉() throws Exception {
    var disputeId = UUID.randomUUID();
    seedDispute(disputeId, "PD-1", "EVIDENCE_SUBMITTED", "USD", "100.0000");
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var payload =
        new FinanceBadDebtInput(
            r, new BigDecimal("60"), BadDebtReasonType.DISPUTE_LOSS, "我方败诉", disputeId, null);
    assertThat(statusOf("submitter", "", payload)).isEqualTo(400);

    // 判败之后就可以建单，并把纠纷快照落库。
    seedDispute(disputeId, "PD-1", "RESOLVED_LOST", "USD", "100.0000");
    var c =
        json.readValue(body("submitter", "", payload), FinanceBadDebtRow.class);
    assertThat(c.disputeId()).isEqualTo(disputeId);
    assertThat(c.disputeNo()).isEqualTo("PD-1");
    assertThat(c.disputeStatus()).isEqualTo("RESOLVED_LOST");
    assertThat(c.disputeAmount()).isEqualByComparingTo("100.0000");
    assertThat(c.disputeCurrency()).isEqualTo("USD");
  }

  @Test
  void 上游纠纷读不到时整体失败而不是假装没有纠纷() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var payload =
        new FinanceBadDebtInput(
            r,
            new BigDecimal("60"),
            BadDebtReasonType.DISPUTE_LOSS,
            "我方败诉",
            UUID.randomUUID(),
            null);
    assertThat(statusOf("submitter", "", payload)).as("上游 404 ⇒ 503/404，绝不装作「没有纠纷」").isIn(404, 503);
  }

  @Test
  void 纠纷币种与应收币种不一致被拒() throws Exception {
    var disputeId = UUID.randomUUID();
    seedDispute(disputeId, "PD-1", "RESOLVED_LOST", "EUR", "100.0000");
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var payload =
        new FinanceBadDebtInput(
            r, new BigDecimal("60"), BadDebtReasonType.DISPUTE_LOSS, "我方败诉", disputeId, null);
    assertThat(statusOf("submitter", "", payload)).isEqualTo(400);
  }

  // ================================================================ 列表 / 能力声明

  @Test
  void 列表是真筛选且汇总覆盖整个结果集() throws Exception {
    var r1 = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var r2 = seedReceivable("AR-2", "EUR", "200.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var c1 = createCase(r1, "60.0000", "PLATFORM_FROZEN", "平台冻结");
    createCase(r2, "80.0000", "BUYER_REFUSAL", "买家拒付");
    approve(c1.id());

    var all =
        json.readValue(
            http.perform(get("/finance/bad-debt").header("Authorization", "submitter"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceBadDebtPage.class);
    assertThat(all.total()).isEqualTo(2);
    assertThat(all.totals()).hasSize(2);
    // ⚠️ 不跨币种合计：USD 与 EUR 各自一行。
    assertThat(all.totals())
        .extracting(FinanceBadDebtCurrencyTotal::currency)
        .containsExactlyInAnyOrder("USD", "EUR");

    var usd =
        json.readValue(
            http.perform(get("/finance/bad-debt").param("currency", "USD").header("Authorization", "submitter"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceBadDebtPage.class);
    assertThat(usd.total()).isEqualTo(1);
    assertThat(usd.totals()).hasSize(1);
    assertThat(usd.totals().get(0).writeOffAmount()).isEqualByComparingTo("60.0000");

    // 汇总覆盖**整个筛选结果集**：分页变小汇总也不变。
    var paged =
        json.readValue(
            http.perform(
                    get("/finance/bad-debt").param("size", "1").param("page", "1")
                        .header("Authorization", "submitter"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceBadDebtPage.class);
    assertThat(paged.items()).hasSize(1);
    assertThat(paged.total()).isEqualTo(2);
    assertThat(paged.totals()).hasSize(2);
  }

  @Test
  void 能力声明如实报出会计处理不可用与批量核销不可用() throws Exception {
    var caps =
        json.readValue(
            http.perform(get("/finance/bad-debt/capabilities").header("Authorization", "submitter"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceBadDebtCapabilities.class);
    var accounting =
        caps.capabilities().stream()
            .filter(c -> c.ownerModule().equals("12.5.2.1"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("能力声明里没有 12.5.2.1 这一行"));
    assertThat(accounting.available()).as("会计处理当前不可产生").isFalse();
    assertThat(accounting.ownerModule()).isEqualTo("12.5.2.1");
    assertThat(accounting.detail()).contains("12.5.2.1");

    var batch =
        caps.capabilities().stream()
            .filter(c -> c.name().contains("批量"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("能力声明里没有「批量一键核销」这一行"));
    assertThat(batch.available()).as("蓝图写死不允许批量一键核销无审批").isFalse();
  }

  @Test
  void 不存在批量核销端点() throws Exception {
    // ⚠️ `/finance/bad-debt/{id}` 的 GET 映射会让这两个路径命中「路径存在、方法不允许」⇒ 405。
    //   405 与 404 一样是「这个端点不存在」；**真判据是没有任何一笔被核销**。
    for (String path : List.of("/finance/bad-debt/batch-write-off", "/finance/bad-debt/batch")) {
      var res =
          http.perform(
                  post(path)
                      .header("Authorization", "submitter")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn()
              .getResponse();
      assertThat(res.getStatus()).as(path + " 不得是 2xx").isNotIn(200, 201, 204);
    }
    var writtenOff =
        one("select count(*) as n from finance_bad_debt_case where status = 'WRITTEN_OFF'")
            .get("n", Long.class);
    assertThat(writtenOff).as("批量端点不存在 ⇒ 没有任何一笔被核销").isZero();
  }

  @Test
  void 详情带出依据材料与留痕() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var c = createCase(r, "60.0000", "PLATFORM_FROZEN", "平台冻结");
    addEvidence(c.id(), "PLATFORM_FROZEN_NOTICE", "冻结通知");
    var detail =
        json.readValue(
            http.perform(get("/finance/bad-debt/" + c.id()).header("Authorization", "submitter"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceBadDebtDetail.class);
    assertThat(detail.evidence()).hasSize(1);
    assertThat(detail.evidence().get(0).title()).isEqualTo("冻结通知");
    assertThat(detail.events()).extracting(FinanceBadDebtEventRow::eventType).contains("CREATED", "EVIDENCE_ADDED");
    // 可执行动作要**说清楚为什么不可执行**。
    var submit =
        detail.actions().stream().filter(a -> a.code().equals("SUBMIT")).findFirst().orElseThrow();
    assertThat(submit.allowed()).isTrue();
  }

  // ================================================================ 数据层口径

  @Test
  void 金额是numeric19_4数量是numeric19_6而不是浮点() {
    for (String column : List.of("write_off_amount", "open_balance", "receivable_amount")) {
      var row =
          one(
              "select data_type, numeric_precision, numeric_scale from information_schema.columns"
                  + " where table_name = 'finance_bad_debt_case' and column_name = ?",
              column);
      assertThat(row.get("data_type", String.class)).isEqualTo("numeric");
      assertThat(row.get("numeric_precision", Integer.class)).isEqualTo(19);
      assertThat(row.get("numeric_scale", Integer.class)).isEqualTo(4);
    }
    // 本模块没有数量列（坏账核销只处理金额）—— 断言库里真的没有浮点列。
    var floats =
        one(
                "select count(*) as n from information_schema.columns"
                    + " where table_name in ('finance_bad_debt_case','finance_bad_debt_evidence')"
                    + " and data_type in ('real','double precision','float')")
            .get("n", Long.class);
    assertThat(floats).as("禁浮点").isZero();
  }

  @Test
  void 时间是timestamptz且写回零漂移() throws Exception {
    var r = seedReceivable("AR-1", "USD", "100.0000", "0", LocalDate.now(ZoneOffset.UTC), "店铺 A");
    var c = createCase(r, "60.0000", "PLATFORM_FROZEN", "平台冻结");
    // 用 +08:00 的夹具写一条留痕，读回来必须是**同一个瞬间**、同一个墙上时间。
    var at = OffsetDateTime.of(2026, 3, 1, 12, 0, 0, 0, ZoneOffset.ofHours(8));
    as(
        () -> {
          sql.execute(
              "insert into finance_bad_debt_event(id,tenant_id,case_id,event_type,from_status,"
                  + " to_status,reason,actor_id,occurred_at) values(?,?,?,'CREATED','','DRAFT',"
                  + "'夹具',?,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              c.id(),
              submitterUser,
              at);
          return null;
        });
    var read =
        one(
                "select occurred_at from finance_bad_debt_event where case_id = ? and reason = '夹具'",
                c.id())
            .get("occurred_at", OffsetDateTime.class);
    assertThat(read.toInstant()).as("写回必须是同一个瞬间").isEqualTo(at.toInstant());
    assertThat(read.withOffsetSameInstant(ZoneOffset.ofHours(8)).toLocalTime())
        .as("用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s：这里必须零漂移")
        .isEqualTo(at.toLocalTime());
  }

  @Test
  void 事件流是追加式的_运行角色改不动() {
    var row =
        one(
            "select has_table_privilege('finance_app','finance_bad_debt_event','UPDATE') as u,"
                + " has_table_privilege('finance_app','finance_bad_debt_event','DELETE') as d,"
                + " has_table_privilege('finance_app','finance_bad_debt_event','INSERT') as i");
    assertThat(row.get("u", Boolean.class)).as("运行角色不得 UPDATE 留痕").isFalse();
    assertThat(row.get("d", Boolean.class)).as("运行角色不得 DELETE 留痕").isFalse();
    assertThat(row.get("i", Boolean.class)).as("运行角色必须能 INSERT 留痕").isTrue();
  }
}
