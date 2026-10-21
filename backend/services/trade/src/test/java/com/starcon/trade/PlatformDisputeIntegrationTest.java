package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.PlatformDisputeModels.*;
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
 * 11.4.1.1 平台纠纷列表。
 *
 * <p>跑的是蓝图职责句与那条加粗句：
 *
 * <blockquote>
 * 职责：管理平台纠纷/争议（Amazon A-to-Z、eBay 争议、速卖通纠纷）：类型、金额、平台时限、举证要求、我方立场、结果记录。
 * 输入：平台纠纷事件/同步；责任判定；举证材料。
 * 输出：纠纷单 + 时效倒计时 + 举证清单。
 * 关系：上游 = 2 平台同步、15 客服；下游 = 11.4.1.2 申诉、12 财务（争议金额影响结算）。
 * <b>平台纠纷有严格举证时限</b>，超期自动判败，是硬性时效要求。
 * </blockquote>
 *
 * <p><b>本类最看重的十二组断言</b>：
 *
 * <ol>
 *   <li><b>超期自动判败真的成立</b>（本类的重心）：过了举证截止时刻且没提交举证 ⇒
 *       纠纷**自己**落成「我方败诉 / 超期自动判负」，<b>不需要任何人工动作</b>；
 *       状态、结果码、结果时刻都是 DB 写进去的。</li>
 *   <li><b>超期后不可再提交举证</b>：服务层拒（写之前就拦），
 *       <b>绕过服务层直连 SQL</b> 也被 DB 拒（举证提交时刻晚于截止时刻直接 RAISE）。</li>
 *   <li><b>举证清单在超期后不可再改</b>。</li>
 *   <li><b>举证时限只能收紧不能放宽</b>：想给自己续命 ⇒ DB 拒。</li>
 *   <li><b>倒计时是派生态</b>：库里**没有**「剩余小时数」列，
 *       剩余秒数由截止时刻在读取时刻算出（并断言 information_schema 里确实没有那一列）。</li>
 *   <li><b>举证清单不齐不许提交举证</b>：必举证项缺一个就提交不了。</li>
 *   <li><b>类型与平台必须对得上</b>：A_TO_Z 只能配 AMAZON。</li>
 *   <li><b>同一个平台纠纷号只能登记一次</b>。</li>
 *   <li><b>「没有财务确认就没有生效」</b>：登记结算影响后生效金额**恒为 0**，
 *       确认那一刻才变成登记金额；<b>绕过服务层直连 SQL</b> 塞生效金额也被 DB 拒。</li>
 *   <li><b>金额按币种分组</b>：跨币种**不折算**、不合并成一个数。</li>
 *   <li><b>终态不可逆</b>：败诉后立场与责任判定改不动（服务层与 DB 各拒一次）。</li>
 *   <li><b>时间写回零漂移 + 金额是 NUMERIC(19,4)</b>：夹具刻意用 +08:00 非 UTC 偏移。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformDisputeIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID other = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
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
    actors.put("Bearer tenant-main", actor(user, tenant));
    actors.put("Bearer tenant-other", actor(UUID.randomUUID(), other));
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

  @Autowired PlatformDisputeService disputes;
  /**
   * 只桩掉 {@code require}（对 identity 的一次 HTTP 调用），{@code run} 仍是真实实现 ——
   * 也就是说 RLS 租户绑定与权限判据跑的都是生产代码路径。
   */
  @MockitoSpyBean AccessService access;

  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    pg.stop();
  }

  /** **非 UTC 偏移**夹具：只有这种时刻才能测出 Timestamp.valueOf 的 −28800s 静默偏移。 */
  static final OffsetDateTime DEADLINE = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");

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
          "truncate platform_dispute_event, platform_dispute_settlement,"
              + " platform_dispute_evidence_item, platform_dispute");
    }
  }

  <T> T as(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-main"), "trade.aftersales.write", work);
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 建一件纠纷（默认：AMAZON / A_TO_Z / USD / 14 天后到期）。 */
  PlatformDisputeDetail newDispute(String tag, OffsetDateTime deadline) {
    return as(
        () ->
            disputes.createDispute(
                new PlatformDisputeCreate(
                    "AMAZON", "Amazon", "AZ-" + tag, "A_TO_Z", "买家称未收到货",
                    "ORDER-" + tag, "USD", new BigDecimal("88.5000"), deadline,
                    "UNDETERMINED", "UNDETERMINED", "", ""),
                user));
  }

  /** 建一件**已提交举证**的纠纷（用于结算影响那条链）。 */
  PlatformDisputeDetail submittedDispute(String tag) {
    PlatformDisputeDetail d = newDispute(tag, OffsetDateTime.now(ZoneOffset.UTC).plusDays(14));
    UUID id = d.head().id();
    as(
        () ->
            disputes.addEvidenceItem(
                id,
                new PlatformEvidenceItemCreate("ORDER_FULFILLMENT_PROOF", "履约证明", true, ""),
                user));
    as(
        () ->
            disputes.addEvidenceItem(
                id, new PlatformEvidenceItemCreate("INVOICE", "发票", true, ""), user));
    // 进入举证准备（必须已有举证清单）。
    as(() -> disputes.startEvidence(id, versionOf(id), user));
    // 清单齐了才能提交。
    for (PlatformDisputeEvidenceRow item :
        as(() -> disputes.disputeDetail(id).evidenceItems())) {
      as(
          () ->
              disputes.markEvidence(
                  id,
                  item.id(),
                  new PlatformEvidenceMark("PROVIDED", "已上传", null),
                  user));
    }
    return as(
        () ->
            disputes.submitEvidence(
                id, new PlatformDisputeSubmitEvidence("已提交全部举证", versionOf(id)), user));
  }

  long versionOf(UUID id) {
    return as(() -> disputes.disputeDetail(id).head().version());
  }

  // ==========================================================================
  // 一、类型、金额、平台快照
  // ==========================================================================

  @Test
  void 建单恒为已立案且平台快照落库() {
    PlatformDisputeDetail d = newDispute("A1", OffsetDateTime.now(ZoneOffset.UTC).plusDays(14));
    assertThat(d.head().disputeStatus()).isEqualTo("OPEN");
    assertThat(d.head().disputeStatusLabel()).isEqualTo("已立案");
    // 平台来源是**快照**：平台码 + 平台名 + 平台纠纷号 + 平台订单号。
    assertThat(d.head().platformCode()).isEqualTo("AMAZON");
    assertThat(d.head().platformName()).isEqualTo("Amazon");
    assertThat(d.head().platformDisputeNo()).isEqualTo("AZ-A1");
    assertThat(d.head().platformOrderNo()).isEqualTo("ORDER-A1");
    // 单号由 DB 的发号器出。
    assertThat(d.head().disputeNo()).startsWith("PDS");
  }

  @Test
  void 金额是NUMERIC四位小数而不是浮点() {
    PlatformDisputeDetail d = newDispute("M1", OffsetDateTime.now(ZoneOffset.UTC).plusDays(14));
    assertThat(d.head().disputeAmount()).isEqualByComparingTo("88.5000");

    String type =
        as(
            () ->
                sql.fetchOne(
                        "select data_type, numeric_precision, numeric_scale"
                            + " from information_schema.columns"
                            + " where table_name = 'platform_dispute'"
                            + " and column_name = 'dispute_amount'")
                    .get("data_type", String.class));
    assertThat(type).isEqualTo("numeric");
    assertThat(
            as(
                () ->
                    sql.fetchOne(
                            "select numeric_precision, numeric_scale"
                                + " from information_schema.columns"
                                + " where table_name = 'platform_dispute'"
                                + " and column_name = 'dispute_amount'")
                        .get("numeric_precision", Integer.class)))
        .isEqualTo(19);
    assertThat(
            as(
                () ->
                    sql.fetchOne(
                            "select numeric_precision, numeric_scale"
                                + " from information_schema.columns"
                                + " where table_name = 'platform_dispute'"
                                + " and column_name = 'dispute_amount'")
                        .get("numeric_scale", Integer.class)))
        .isEqualTo(4);
  }

  @Test
  void 举证截止时刻是真实timestamptz且写回零漂移() {
    // 夹具刻意用 +08:00 偏移：Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s。
    PlatformDisputeDetail d = newDispute("T1", DEADLINE);
    OffsetDateTime stored = d.head().evidenceDeadline();
    assertThat(stored.toInstant()).isEqualTo(DEADLINE.toInstant());
    assertThat(stored.getOffset()).isEqualTo(DEADLINE.getOffset());

    String type =
        as(
            () ->
                sql.fetchOne(
                        "select data_type from information_schema.columns"
                            + " where table_name = 'platform_dispute'"
                            + " and column_name = 'evidence_deadline'")
                    .get("data_type", String.class));
    assertThat(type).isEqualTo("timestamp with time zone");
  }

  @Test
  void 倒计时是派生态_库里没有剩余小时数列() {
    // ⚠️ 这条断言是「倒计时不许落成会自己过期的一列」的**直接证据**。
    long remaining =
        as(
            () ->
                sql.fetchOne(
                        "select count(*) from information_schema.columns"
                            + " where table_name = 'platform_dispute'"
                            + " and (column_name like '%remaining%' or column_name like '%countdown%'"
                            + "   or column_name like '%hours_left%' or column_name like '%days_left%')")
                    .get(0, Long.class));
    assertThat(remaining).isZero();

    // 剩余秒数由截止时刻在读取时刻算出。
    OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC).plusDays(10);
    PlatformDisputeDetail d = newDispute("CD", deadline);
    long expected = deadline.toEpochSecond() - OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond();
    assertThat(d.head().secondsRemaining()).isBetween(expected - 10, expected + 10);
    assertThat(d.head().deadlineState()).isEqualTo("NORMAL");

    // 72 小时内算「即将到期」（阈值也是派生出来的，不是另一列）。
    PlatformDisputeDetail urgent =
        newDispute("CD2", OffsetDateTime.now(ZoneOffset.UTC).plusHours(10));
    assertThat(urgent.head().deadlineState()).isEqualTo("URGENT");
    assertThat(urgent.head().deadlineStateLabel()).isEqualTo("即将到期");
  }

  @Test
  void 类型与平台必须对得上() {
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        disputes.createDispute(
                            new PlatformDisputeCreate(
                                "EBAY", "eBay", "EB-1", "A_TO_Z", "x", "O-1", "USD",
                                new BigDecimal("10.0000"), OffsetDateTime.now(ZoneOffset.UTC).plusDays(5),
                                null, null, null, null),
                            user)))
        .hasMessageContaining("只属于平台 AMAZON");

    // DB 侧也钉一道：绕过服务层直连 SQL 写 A_TO_Z + EBAY 被拒。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "insert into platform_dispute(id,tenant_id,dispute_no,platform_code,"
                                + "platform_name,platform_dispute_no,dispute_type,dispute_currency,"
                                + "dispute_amount,evidence_deadline,dispute_status,created_by)"
                                + " values(?::uuid,?::uuid,'PDS-RAW','EBAY','eBay','EB-RAW','A_TO_Z','USD',"
                                + "10.0000,?::timestamptz,'OPEN',?::uuid)",
                            UUID.randomUUID(), tenant, OffsetDateTime.now(ZoneOffset.UTC).plusDays(5), user)))
        .hasMessageContaining("只属于平台 AMAZON");
  }

  @Test
  void 平台码形状被CHECK钉死() {
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        disputes.createDispute(
                            new PlatformDisputeCreate(
                                "amazon!", "Amazon", "AZ-BAD", "OTHER", "x", "O-1", "USD",
                                new BigDecimal("10.0000"), OffsetDateTime.now(ZoneOffset.UTC).plusDays(5),
                                null, null, null, null),
                            user)))
        .hasMessageContaining("平台码形状");
  }

  @Test
  void 同一个平台纠纷号只能登记一次() {
    newDispute("DUP", OffsetDateTime.now(ZoneOffset.UTC).plusDays(14));
    assertThatThrownBy(
            () -> newDispute("DUP", OffsetDateTime.now(ZoneOffset.UTC).plusDays(14)))
        .hasMessageContaining("已经登记过");
  }

  // ==========================================================================
  // 二、**超期自动判败**（本模块的重心）
  // ==========================================================================

  @Test
  void 建单即已超期当场判负() {
    // 登记时举证时限已经过了 ⇒ 这件纠纷在系统里**当场**就是败诉。
    PlatformDisputeDetail d =
        newDispute("EXPIRED", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
    assertThat(d.head().disputeStatus()).isEqualTo("RESOLVED_LOST");
    assertThat(d.head().resolutionCode()).isEqualTo("DEADLINE_EXPIRED");
    assertThat(d.head().resolvedAt()).isNotNull();
    // 系统判负：**不挂操作员**。
    assertThat(d.head().deadlineState()).isEqualTo("PASSED");
  }

  @Test
  void 超期自动判败_不需要任何人工动作() {
    OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC).plusDays(14);
    PlatformDisputeDetail d = newDispute("AUTO", deadline);
    UUID id = d.head().id();

    // 进入举证准备 + 清单齐了。
    as(
        () ->
            disputes.addEvidenceItem(
                id,
                new PlatformEvidenceItemCreate("ORDER_FULFILLMENT_PROOF", "履约证明", true, ""),
                user));
    as(() -> disputes.startEvidence(id, versionOf(id), user));
    as(
        () ->
            disputes.updatePosition(id, new PlatformDisputePositionUpdate(
                "REJECT", "LOGISTICS", "物流超时", "", versionOf(id)), user));

    // 时间走到截止时刻之后 —— 这里**直接改截止时刻**来模拟时钟推进
    // （DB 守卫③ 允许**收紧**，所以这个写法是合法的）。
    OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
    as(
        () ->
            sql.execute(
                "update platform_dispute set evidence_deadline = ?::timestamptz"
                    + " where tenant_id = ?::uuid and id = ?::uuid",
                past, tenant, id));

    // ⚠️ **不需要任何人工动作**：读一次列表，纠纷已经是败诉。
    var page = as(() -> disputes.listDisputes(query()));
    var row = page.rows().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    assertThat(row.disputeStatus()).isEqualTo("RESOLVED_LOST");
    assertThat(row.resolutionCode()).isEqualTo("DEADLINE_EXPIRED");
    assertThat(row.deadlineState()).isEqualTo("PASSED");
    assertThat(page.summary().overdue()).isEqualTo(1);
    assertThat(page.summary().resolvedLost()).isEqualTo(1);

    // DB 那一行本身也是败诉（不是只在响应里算出来的）。
    Record raw =
        as(
            () ->
                sql.fetchOne(
                    "select dispute_status, resolution_code, resolved_by, resolved_at"
                        + " from platform_dispute where tenant_id = ?::uuid and id = ?::uuid",
                    tenant, id));
    assertThat(raw.get("dispute_status", String.class)).isEqualTo("RESOLVED_LOST");
    assertThat(raw.get("resolution_code", String.class)).isEqualTo("DEADLINE_EXPIRED");
    assertThat(raw.get("resolved_by", UUID.class)).isNull();
    assertThat(raw.get("resolved_at", OffsetDateTime.class)).isNotNull();

    // 留痕里也有「举证超期，自动判负」这一条。
    var detail = as(() -> disputes.disputeDetail(id));
    assertThat(detail.events())
        .anyMatch(e -> "RESOLVED_LOST".equals(e.eventType()) && e.reasonNote().contains("举证超期"));
  }

  @Test
  void 超期后不可再提交举证() {
    OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC).plusDays(14);
    PlatformDisputeDetail d = newDispute("LATE", deadline);
    UUID id = d.head().id();
    as(
        () ->
            disputes.addEvidenceItem(
                id,
                new PlatformEvidenceItemCreate("ORDER_FULFILLMENT_PROOF", "履约证明", true, ""),
                user));
    as(() -> disputes.startEvidence(id, versionOf(id), user));
    for (PlatformDisputeEvidenceRow item :
        as(() -> disputes.disputeDetail(id).evidenceItems())) {
      as(
          () ->
              disputes.markEvidence(
                  id, item.id(), new PlatformEvidenceMark("PROVIDED", "已上传", null), user));
    }

    // 时间推进到截止时刻之后。
    as(
        () ->
            sql.execute(
                "update platform_dispute set evidence_deadline = ?::timestamptz"
                    + " where tenant_id = ?::uuid and id = ?::uuid",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), tenant, id));

    // 服务层拒：写之前就拦下来。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        disputes.submitEvidence(
                            id, new PlatformDisputeSubmitEvidence("x", versionOf(id)), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("举证时限已过");

    // ⚠️ **绕过服务层直连 SQL** 塞一个晚于截止时刻的举证提交时刻 ⇒ DB 拒。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "update platform_dispute set evidence_submitted_at = ?::timestamptz,"
                                + " evidence_submitted_by = ?::uuid"
                                + " where tenant_id = ?::uuid and id = ?::uuid",
                            OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(30), user, tenant, id)))
        .hasMessageContaining("超期后不可再提交举证");
  }

  @Test
  void 超期后举证清单不可再改() {
    OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC).plusDays(14);
    PlatformDisputeDetail d = newDispute("ITEM", deadline);
    UUID id = d.head().id();
    as(
        () ->
            disputes.addEvidenceItem(
                id,
                new PlatformEvidenceItemCreate("ORDER_FULFILLMENT_PROOF", "履约证明", true, ""),
                user));

    as(
        () ->
            sql.execute(
                "update platform_dispute set evidence_deadline = ?::timestamptz"
                    + " where tenant_id = ?::uuid and id = ?::uuid",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), tenant, id));

    // 服务层拒。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        disputes.addEvidenceItem(
                            id,
                            new PlatformEvidenceItemCreate("INVOICE", "发票", true, ""),
                            user)))
        .satisfies(
            e ->
                assertThat(e.getMessage())
                    .containsAnyOf("举证时限已过", "举证清单已冻结"));

    // 绕过服务层直连 SQL 改清单 ⇒ DB 拒。
    UUID itemId = as(() -> disputes.disputeDetail(id).evidenceItems().get(0).id());
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "update platform_dispute_evidence_item set evidence_state = 'PROVIDED',"
                                + " evidence_note = 'x', provided_by = ?::uuid"
                                + " where tenant_id = ?::uuid and id = ?::uuid",
                            user, tenant, itemId)))
        .satisfies(
            e ->
                assertThat(e.getMessage())
                    .containsAnyOf("举证时限已过", "举证清单已冻结"));
  }

  @Test
  void 举证时限只能收紧不能放宽() {
    PlatformDisputeDetail d = newDispute("WIDEN", OffsetDateTime.now(ZoneOffset.UTC).plusDays(14));
    UUID id = d.head().id();
    OffsetDateTime later = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30);
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "update platform_dispute set evidence_deadline = ?::timestamptz"
                                + " where tenant_id = ?::uuid and id = ?::uuid",
                            later, tenant, id)))
        .hasMessageContaining("只能收紧不能放宽");
  }

  @Test
  void 按时提交举证后不再受截止时刻约束() {
    // 提交举证之后，平台裁决才是结果 —— 「按时提交了却因为超期被判负」是另一条假事实。
    PlatformDisputeDetail d = submittedDispute("OK");
    assertThat(d.head().disputeStatus()).isEqualTo("EVIDENCE_SUBMITTED");
    assertThat(d.head().evidenceSubmittedAt()).isNotNull();
    assertThat(d.head().deadlineState()).isEqualTo("RELEASED");
  }

  @Test
  void 举证清单不齐不许提交举证() {
    PlatformDisputeDetail d = newDispute("MISS", OffsetDateTime.now(ZoneOffset.UTC).plusDays(14));
    UUID id = d.head().id();
    as(
        () ->
            disputes.addEvidenceItem(
                id, new PlatformEvidenceItemCreate("INVOICE", "发票", true, ""), user));
    as(() -> disputes.startEvidence(id, versionOf(id), user));

    // 必举证项还没具备 ⇒ 提交不了（超期即判负，缺一项就是白丢）。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        disputes.submitEvidence(
                            id, new PlatformDisputeSubmitEvidence("x", versionOf(id)), user)))
        .hasMessageContaining("必举证材料没有具备");

    // 标记缺失必须写清缺什么。
    UUID itemId = as(() -> disputes.disputeDetail(id).evidenceItems().get(0).id());
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        disputes.markEvidence(
                            id, itemId, new PlatformEvidenceMark("MISSING", "", ""), user)))
        .hasMessageContaining("必须写清缺什么");

    // 标成缺失（写了原因）之后，缺失项进入清单的「缺失」计数。
    as(
        () ->
            disputes.markEvidence(
                id, itemId, new PlatformEvidenceMark("MISSING", "", "平台后台没有下载入口"), user));
    var head = as(() -> disputes.disputeDetail(id).head());
    assertThat(head.evidenceMissing()).isEqualTo(1);
    assertThat(head.evidenceProvided()).isZero();
  }

  @Test
  void 提交举证后举证清单冻结() {
    PlatformDisputeDetail d = submittedDispute("FROZEN");
    UUID id = d.head().id();
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        disputes.addEvidenceItem(
                            id,
                            new PlatformEvidenceItemCreate("INVOICE", "发票", true, ""),
                            user)))
        .hasMessageContaining("举证清单已冻结");

    UUID itemId = as(() -> disputes.disputeDetail(id).evidenceItems().get(0).id());
    assertThatThrownBy(() -> as(() -> disputes.removeEvidenceItem(id, itemId, user)))
        .hasMessageContaining("举证清单已冻结");
  }

  // ==========================================================================
  // 三、我方立场、责任判定与结果记录
  // ==========================================================================

  @Test
  void 我方立场与责任判定可以更新() {
    PlatformDisputeDetail d = newDispute("POS", OffsetDateTime.now(ZoneOffset.UTC).plusDays(14));
    UUID id = d.head().id();
    as(
        () ->
            disputes.updatePosition(id, new PlatformDisputePositionUpdate(
                "PARTIAL_ACCEPT", "MERCHANT_FULFILLMENT", "部分承认，物流延误", "",
                versionOf(id)), user));
    var head = as(() -> disputes.disputeDetail(id).head());
    assertThat(head.ourPosition()).isEqualTo("PARTIAL_ACCEPT");
    assertThat(head.ourPositionLabel()).isEqualTo("部分承认");
    assertThat(head.liabilityStage()).isEqualTo("MERCHANT_FULFILLMENT");
  }

  @Test
  void 记录平台裁决结果且超期判负不接受人工声明() {
    PlatformDisputeDetail d = submittedDispute("RES");
    UUID id = d.head().id();

    // 「超期判负」是系统判的，不接受人工声明。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        disputes.resolve(
                            id,
                            new PlatformDisputeResolve("DEADLINE_EXPIRED", "x", versionOf(id)),
                            user)))
        .hasMessageContaining("不接受人工声明");

    // 平台判我方败诉。
    as(
        () ->
            disputes.resolve(
                id, new PlatformDisputeResolve("PLATFORM_RULING_AGAINST", "平台判败诉", versionOf(id)),
                user));
    var head = as(() -> disputes.disputeDetail(id).head());
    assertThat(head.disputeStatus()).isEqualTo("RESOLVED_LOST");
    assertThat(head.resolutionCode()).isEqualTo("PLATFORM_RULING_AGAINST");
    assertThat(head.resolvedAt()).isNotNull();
  }

  @Test
  void 终态不可逆() {
    PlatformDisputeDetail d = submittedDispute("TERM");
    UUID id = d.head().id();
    as(
        () ->
            disputes.resolve(
                id,
                new PlatformDisputeResolve("PLATFORM_RULING_AGAINST", "x", versionOf(id)),
                user));

    // 服务层拒。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        disputes.updatePosition(id, new PlatformDisputePositionUpdate(
                            "ACCEPT", "BUYER", "改主意了", "", versionOf(id)), user)))
        .hasMessageContaining("已是终态");

    // DB 也拒。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "update platform_dispute set our_position = 'ACCEPT'"
                                + " where tenant_id = ?::uuid and id = ?::uuid",
                            tenant, id)))
        .hasMessageContaining("终态不可逆");
  }

  @Test
  void 我方撤诉必须写理由() {
    PlatformDisputeDetail d = newDispute("WD", OffsetDateTime.now(ZoneOffset.UTC).plusDays(14));
    UUID id = d.head().id();
    assertThatThrownBy(
            () -> as(() -> disputes.withdraw(id, new PlatformDisputeWithdraw("", versionOf(id)), user)))
        .hasMessageContaining("撤回必须写理由");
    as(
        () ->
            disputes.withdraw(
                id, new PlatformDisputeWithdraw("买家撤诉了", versionOf(id)), user));
    assertThat(as(() -> disputes.disputeDetail(id).head()).disputeStatus()).isEqualTo("WITHDRAWN");
  }

  // ==========================================================================
  // 四、结算影响（下游 12 财务当前不存在 ⇒ trade 侧自有事实记录）
  // ==========================================================================

  @Test
  void 登记结算影响不生效且财务确认后才生效() {
    PlatformDisputeDetail d = submittedDispute("FIN");
    UUID id = d.head().id();
    as(
        () ->
            disputes.resolve(
                id,
                new PlatformDisputeResolve("PLATFORM_RULING_AGAINST", "x", versionOf(id)),
                user));

    // 登记：生效金额**恒为 0**。
    var registered = as(() -> disputes.registerSettlement(id, new PlatformDisputeSettlementCreate(
        new BigDecimal("40.0000")), user));
    assertThat(registered.settlements()).hasSize(1);
    assertThat(registered.settlements().get(0).requestedAmount()).isEqualByComparingTo("40.0000");
    assertThat(registered.settlements().get(0).effectiveAmount()).isEqualByComparingTo("0.0000");
    assertThat(registered.settlements().get(0).settlementStatus()).isEqualTo("PENDING_FINANCE_CONFIRM");
    assertThat(registered.head().effectiveAmount()).isEqualByComparingTo("0.0000");
    assertThat(registered.head().settlementState()).isEqualTo("PENDING_FINANCE_CONFIRM");

    // ⚠️ **绕过服务层直连 SQL** 塞一个生效金额 ⇒ DB 拒（表级 CHECK）。
    UUID settlementId = registered.settlements().get(0).id();
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "update platform_dispute_settlement set effective_amount = 40.0000"
                                + " where tenant_id = ?::uuid and id = ?::uuid",
                            tenant, settlementId)))
        .hasMessageContaining("violates check constraint");

    // 财务确认：生效金额变成登记金额。
    var confirmed = as(() -> disputes.confirmSettlement(id, settlementId,
        new PlatformDisputeSettlementConfirm("FIN-2026-001"), user));
    assertThat(confirmed.settlements().get(0).effectiveAmount()).isEqualByComparingTo("40.0000");
    assertThat(confirmed.settlements().get(0).settlementStatus()).isEqualTo("CONFIRMED");
    assertThat(confirmed.head().effectiveAmount()).isEqualByComparingTo("40.0000");
    assertThat(confirmed.head().settlementState()).isEqualTo("APPLIED");
  }

  @Test
  void 登记结算影响幂等() {
    PlatformDisputeDetail d = submittedDispute("IDEM");
    UUID id = d.head().id();
    as(
        () ->
            disputes.resolve(
                id,
                new PlatformDisputeResolve("PLATFORM_RULING_AGAINST", "x", versionOf(id)),
                user));
    as(() -> disputes.registerSettlement(id,
        new PlatformDisputeSettlementCreate(new BigDecimal("40.0000")), user));
    var again = as(() -> disputes.registerSettlement(id,
        new PlatformDisputeSettlementCreate(new BigDecimal("40.0000")), user));
    assertThat(again.settlements()).hasSize(1);
  }

  @Test
  void 只有已终结的纠纷才能登记结算影响() {
    PlatformDisputeDetail d = newDispute("NOS", OffsetDateTime.now(ZoneOffset.UTC).plusDays(14));
    UUID id = d.head().id();
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        disputes.registerSettlement(id,
                            new PlatformDisputeSettlementCreate(new BigDecimal("40.0000")), user)))
        .hasMessageContaining("只有已终结");
  }

  @Test
  void 财务驳回后生效金额仍为0() {
    PlatformDisputeDetail d = submittedDispute("REJ");
    UUID id = d.head().id();
    as(
        () ->
            disputes.resolve(
                id,
                new PlatformDisputeResolve("PLATFORM_RULING_AGAINST", "x", versionOf(id)),
                user));
    var registered = as(() -> disputes.registerSettlement(id,
        new PlatformDisputeSettlementCreate(new BigDecimal("40.0000")), user));
    UUID settlementId = registered.settlements().get(0).id();
    var rejected = as(() -> disputes.rejectSettlement(id, settlementId,
        new PlatformDisputeSettlementReject("争议金额不在本期结算范围内"), user));
    assertThat(rejected.settlements().get(0).effectiveAmount()).isEqualByComparingTo("0.0000");
    assertThat(rejected.settlements().get(0).settlementStatus()).isEqualTo("REJECTED");
    assertThat(rejected.head().settlementState()).isEqualTo("FINANCE_REJECTED");
  }

  // ==========================================================================
  // 五、列表：真分页、多维筛选、按币种分组
  // ==========================================================================

  @Test
  void 列表多维筛选是真筛选且汇总不随分页变小() {
    newDispute("L1", OffsetDateTime.now(ZoneOffset.UTC).plusDays(3));
    newDispute("L2", OffsetDateTime.now(ZoneOffset.UTC).plusDays(9));

    var page = as(() -> disputes.listDisputes(query()));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.rows()).hasSize(2);
    assertThat(page.summary().total()).isEqualTo(2);
    assertThat(page.summary().open()).isEqualTo(2);

    // 按状态筛。
    assertThat(as(() -> disputes.listDisputes(query("RESOLVED_LOST", null, null, null))).total())
        .isZero();

    // 按金额区间筛。
    assertThat(
            as(
                    () ->
                        disputes.listDisputes(
                            new PlatformDisputeListQuery(
                                0, 20, null, null, null, null, null, null, null, null,
                                new BigDecimal("1.0000"), new BigDecimal("10.0000"), null, null, null, null)))
                .total())
        .isZero();

    // 按关键字筛（平台纠纷号）。
    assertThat(as(() -> disputes.listDisputes(query(null, null, "AZ-L2", null))).total()).isEqualTo(1);

    // 真分页：size=1 时只回一行，但 total 仍是 2（汇总不随分页变小）。
    var paged = as(() -> disputes.listDisputes(new PlatformDisputeListQuery(
        0, 1, null, null, null, null, null, null, null, null, null, null, null, null, null, null)));
    assertThat(paged.rows()).hasSize(1);
    assertThat(paged.total()).isEqualTo(2);
    assertThat(paged.summary().total()).isEqualTo(2);
  }

  @Test
  void 金额按币种分组不做固定汇率折算() {
    newDispute("USD1", OffsetDateTime.now(ZoneOffset.UTC).plusDays(3));
    as(
        () ->
            disputes.createDispute(
                new PlatformDisputeCreate(
                    "EBAY", "eBay", "EB-EUR", "EBAY_DISPUTE", "x", "O-EUR", "EUR",
                    new BigDecimal("20.0000"), OffsetDateTime.now(ZoneOffset.UTC).plusDays(5),
                    null, null, null, null),
                user));

    var page = as(() -> disputes.listDisputes(query()));
    // ⚠️ **不合并成一个数**：两种币种两组，各自的合计。
    assertThat(page.currencyBreakdown()).hasSize(2);
    var eur = page.currencyBreakdown().stream()
        .filter(c -> "EUR".equals(c.currency())).findFirst().orElseThrow();
    var usd = page.currencyBreakdown().stream()
        .filter(c -> "USD".equals(c.currency())).findFirst().orElseThrow();
    assertThat(eur.totalAmount()).isEqualByComparingTo("20.0000");
    assertThat(usd.totalAmount()).isEqualByComparingTo("88.5000");
    // 汇总里**没有**跨币种的金额合计。
    assertThat(page.summary()).extracting(PlatformDisputeSummary::total).isEqualTo(2L);
  }

  // ==========================================================================
  // 六、能力声明
  // ==========================================================================

  @Test
  void 能力声明如实报出上游与下游缺口() {
    var caps = as(() -> disputes.capabilities());

    // 上游缺口：channel 的纠纷同步 **未交付**，crm 的客服工单 **未交付**。
    assertThat(caps.related()).extracting(PlatformDisputeRelatedModule::id)
        .contains("MODULE_2_CHANNEL_SYNC", "MODULE_15_CRM_TICKET",
            "MODULE_11_4_1_2", "MODULE_12_FINANCE");
    assertThat(caps.related())
        .filteredOn(r -> r.id().equals("MODULE_2_CHANNEL_SYNC"))
        .allMatch(r -> !r.delivered() && r.href() == null);
    assertThat(caps.related())
        .filteredOn(r -> r.id().equals("MODULE_15_CRM_TICKET"))
        .allMatch(r -> !r.delivered());
    assertThat(caps.related())
        .filteredOn(r -> r.id().equals("MODULE_11_4_1_2"))
        .allMatch(r -> !r.delivered());
    // 下游 12 财务：服务不存在 ⇒ 只落 trade 侧自有事实记录。
    assertThat(caps.related())
        .filteredOn(r -> r.id().equals("MODULE_12_FINANCE"))
        .allMatch(r -> !r.delivered());

    // 「手动标记超期判负」刻意不提供。
    assertThat(caps.actions())
        .filteredOn(a -> a.code().equals("AUTO_LOSE"))
        .allMatch(a -> !a.allowed());
    // 财务确认是**独立权限**上的动作。
    assertThat(caps.actions())
        .filteredOn(a -> a.code().equals("CONFIRM_SETTLEMENT"))
        .allMatch(PlatformDisputeCapabilityAction::allowed);

    // 能力声明正文里必须写清「超期自动判败」与「上游缺口」。
    assertThat(caps.hardRuleDeadline()).contains("超期自动判败").contains("timestamptz");
    assertThat(caps.hardRuleDeadline()).contains("没有").contains("剩余小时数");
    assertThat(caps.platformSyncGap()).contains("没有纠纷专用同步端点");
    assertThat(caps.hardRuleFinanceConfirm()).contains("finance 服务");
  }

  // ==========================================================================
  // 七、HTTP 层：财务确认需要独立权限
  // ==========================================================================

  @Test
  void 财务确认需要独立的财务权限() throws Exception {
    PlatformDisputeDetail d = submittedDispute("HTTP");
    UUID id = d.head().id();
    as(
        () ->
            disputes.resolve(
                id,
                new PlatformDisputeResolve("PLATFORM_RULING_AGAINST", "x", versionOf(id)),
                user));
    var registered = as(() -> disputes.registerSettlement(id,
        new PlatformDisputeSettlementCreate(new BigDecimal("40.0000")), user));
    UUID settlementId = registered.settlements().get(0).id();

    // 只有 read/write 的身份调财务确认 —— 被拒。
    AccessService.Access noFinance =
        new AccessService.Access(user, tenant, "Test", UUID.randomUUID(), null,
            List.of("trade.aftersales.read", "trade.aftersales.write"),
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL",
                List.of("trade.aftersales.read", "trade.aftersales.write"))));
    actors.put("Bearer no-finance", noFinance);

    http.perform(post("/trade/platform-disputes/{id}/settlements/{sid}/confirm", id, settlementId)
            .header("Authorization", "Bearer no-finance")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"financeReference\":\"AP-1\"}"))
        .andExpect(status().is4xxClientError());

    // 生效金额仍然是 0。
    var still = as(() -> disputes.disputeDetail(id));
    assertThat(still.settlements().get(0).effectiveAmount()).isEqualByComparingTo("0.0000");
    assertThat(still.head().settlementState()).isEqualTo("PENDING_FINANCE_CONFIRM");
  }

  // ==========================================================================
  // 小工具
  // ==========================================================================

  /** 默认查询（全部筛掉）。 */
  static PlatformDisputeListQuery query() {
    return query(null, null, null, null);
  }

  static PlatformDisputeListQuery query(
      String status, String platformCode, String keyword, String disputeType) {
    return new PlatformDisputeListQuery(
        0, 20, status, disputeType, platformCode, null, null, null, null, null,
        null, null, null, null, keyword, null);
  }
}
