package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.trade.PlatformDisputeAppealModels.*;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.4.1.2 证据与申诉。
 *
 * <p>跑的是蓝图职责句与那条加粗句：
 *
 * <blockquote>
 * 职责：组织举证材料（物流签收、沟通记录、商品照片、检测报告）打包提交申诉，记录平台回复与结果。
 * 输入：举证材料；申诉文本（可用模板）；提交动作。
 * 输出：申诉提交记录 + 平台回复 + 结果（胜诉/败诉/部分）。
 * 关系：上游 = 11.4.1.1；下游 = 2 平台接口、16 合规（账号绩效）。
 * <b>举证材料必须来自系统记录</b>（轨迹、沟通日志），临时拼凑的证据在申诉中往往不被采信。
 * </blockquote>
 *
 * <p><b>本类最看重的十组断言</b>：
 *
 * <ol>
 *   <li><b>时限判定只有一个判据</b>：服务层与 DB 触发器调<b>同一个 SQL 函数</b>
 *       （{@code platform_dispute_appeal_gate}）。断言：函数存在；服务层给出的判据与
 *       直连调用函数的输出<b>逐字一致</b>；判据说不可提交时，<b>绕过服务层直连 SQL</b>
 *       也被 DB 拒；判据说可提交时直连 SQL <b>能过</b>（证明两侧不漂移）。</li>
 *   <li><b>举证材料只能来自系统记录</b>：指向不存在的记录 ⇒ 服务层 400、
 *       直连 SQL 也被 DB 拒（守卫②）。</li>
 *   <li><b>证据类型由来源记录推导</b>：调用方不能自由填类型。</li>
 *   <li><b>只存逻辑引用 + 快照元数据</b>：库里没有文件本体列（查 information_schema 断言）。</li>
 *   <li><b>必举证项齐备 + 被材料覆盖</b>：清单说齐了但包里没有 ⇒ 提交被拒。</li>
 *   <li><b>提交申诉驱动上游</b>：提交后纠纷单的举证提交时刻被写上、
 *       状态进「举证已提交」—— <b>「按时提交了却因超期被判负」这条假事实不存在</b>。</li>
 *   <li><b>不假装能调平台</b>：只落待提交平台事实（目标系统 / 目标键 / 载荷 / 幂等键 /
 *       状态机 / 重试计数），状态机的四个状态都可达。</li>
 *   <li><b>结果三档</b>：胜诉 / 败诉 / <b>部分</b>；前两档驱动上游的「记录平台裁决」，
 *       「部分」不代填上游的结果码（上游没有这一档）。</li>
 *   <li><b>终态不可逆</b>（服务层与 DB 各拒一次）；<b>材料包提交后冻结</b>。</li>
 *   <li><b>金额是 NUMERIC(19,4) 而不是浮点</b>；<b>时间落成 timestamptz 且写回零漂移</b>。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformDisputeAppealIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID other = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms =
      List.of(
          "trade.aftersales.read",
          "trade.aftersales.write",
          "trade.aftersales.platform",
          "trade.aftersales.finance");

  static AccessService.Access actor(UUID uid, UUID tid) {
    return actor(uid, tid, perms);
  }

  static AccessService.Access actor(UUID uid, UUID tid, List<String> granted) {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", granted));
    return new AccessService.Access(uid, tid, "Test", UUID.randomUUID(), null, granted, grants);
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
    // 只有读 / 写权限的角色（**没有** platform 权限）。
    actors.put(
        "Bearer readonly",
        actor(user, tenant, List.of("trade.aftersales.read", "trade.aftersales.write")));
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
  @Autowired PlatformDisputeAppealService appeals;
  /** 只桩掉 {@code require}（对 identity 的一次 HTTP 调用），{@code run} 仍是真实实现。 */
  @MockitoSpyBean AccessService access;

  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    pg.stop();
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
          "truncate platform_dispute_appeal_event, platform_dispute_appeal_submission,"
              + " platform_dispute_appeal_material, platform_dispute_appeal,"
              + " platform_dispute_event, platform_dispute_settlement,"
              + " platform_dispute_evidence_item, platform_dispute");
    }
  }

  <T> T as(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-main"), "trade.aftersales.write", work);
  }

  <T> T asPlatform(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-main"), "trade.aftersales.platform", work);
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 建一件纠纷（AMAZON / A_TO_Z / USD / 默认 14 天后到期）。 */
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

  /** 建一件**举证准备中**的纠纷（带一项必举证清单）。 */
  PlatformDisputeDetail preparingDispute(String tag, String evidenceType) {
    PlatformDisputeDetail d = newDispute(tag, OffsetDateTime.now(ZoneOffset.UTC).plusDays(14));
    UUID id = d.head().id();
    as(
        () ->
            disputes.addEvidenceItem(
                id, new PlatformEvidenceItemCreate(evidenceType, "平台要求的材料-" + tag, true, ""),
                user));
    return as(() -> disputes.startEvidence(id, d.head().version(), user));
  }

  /** 造一张售后单（沟通记录 / 附件的父单）。 */
  UUID newAftersalesCase(String tag) {
    UUID id = UUID.randomUUID();
    as(
        () ->
            sql.execute(
                "insert into aftersales_case(id,tenant_id,case_no,order_id,case_type,status,"
                    + " reason_code,requested_amount,currency,requested_quantity,received_at,"
                    + " reply_due_at) values(?::uuid,?::uuid,?,?::uuid,'RETURN_REFUND','IN_PROCESS',"
                    + "'QUALITY_DEFECT',10.0000,'USD',1.000000,now(),now()+interval '10 days')",
                id,
                tenant,
                "AS-" + tag,
                UUID.randomUUID()));
    return id;
  }

  /** 造一份附件（商品照片 / 凭证）。 */
  UUID newAttachment(UUID caseId, String evidenceKind, String tag) {
    UUID id = UUID.randomUUID();
    as(
        () ->
            sql.execute(
                "insert into aftersales_attachment(id,tenant_id,case_id,evidence_kind,file_name,"
                    + " content_type,storage_ref,file_size_bytes,description,attached_by_side,"
                    + " evidence_source,source_reference,captured_at,uploaded_by)"
                    + " values(?::uuid,?::uuid,?::uuid,?,?,'image/jpeg',?,1024,?,'US',"
                    + "'TENANT_MAINTAINED',?,now(),?::uuid)",
                id,
                tenant,
                caseId,
                evidenceKind,
                "photo-" + tag + ".jpg",
                "ev/" + tag + "/photo.jpg",
                "商品实物照片-" + tag,
                "SRC-" + tag,
                user));
    return id;
  }

  /** 造一条沟通记录。 */
  UUID newMessage(UUID caseId, String tag) {
    UUID id = UUID.randomUUID();
    as(
        () ->
            sql.execute(
                "insert into aftersales_message(id,tenant_id,case_id,direction,channel,"
                    + " message_body,external_message_id,message_source,source_reference,sent_at,"
                    + " captured_at,author_id)"
                    + " values(?::uuid,?::uuid,?::uuid,'US_TO_BUYER','PLATFORM',?,?,"
                    + "'TENANT_MAINTAINED',?,now(),now(),?::uuid)",
                id,
                tenant,
                caseId,
                "已与买家说明并上传签收凭证-" + tag,
                "MSG-" + tag,
                "REF-" + tag,
                user));
    return id;
  }

  /** 造一条物流轨迹观测（物流签收）。 */
  UUID newTrackingObservation(String tag) {
    UUID recordId = UUID.randomUUID();
    as(
        () ->
            sql.execute(
                "insert into shipment_record(id,tenant_id,record_no,source_handover_id,"
                    + " source_packing_order_id,source_outbound_order_id,warehouse_id,carrier_code,"
                    + " master_waybill_no,shipped_at,parcel_count,synced_at,status)"
                    + " values(?::uuid,?::uuid,?,?::uuid,?::uuid,?::uuid,?::uuid,'SF',?,"
                    + " now()-interval '3 days',1,now(),'SHIPPED')",
                recordId,
                tenant,
                "SR-" + tag,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "WB-" + tag));
    UUID id = UUID.randomUUID();
    as(
        () ->
            sql.execute(
                "insert into shipment_record_tracking_observation(id,tenant_id,record_id,"
                    + " status,source,observation_ref,detail,observed_at)"
                    + " values(?::uuid,?::uuid,?::uuid,'DELIVERED','CARRIER_FEED',?,?,now())",
                id,
                tenant,
                recordId,
                "TRACK-" + tag,
                "买家本人签收-" + tag));
    return id;
  }

  /** 造一张质检单（检测报告）。 */
  UUID newInspectionOrder(String tag) {
    UUID templateId = UUID.randomUUID();
    as(
        () ->
            sql.execute(
                "insert into quality_inspection_template(id,tenant_id,code,name,inspection_type,"
                    + " sampling_ratio,min_sample_quantity,max_sample_quantity,created_by)"
                    + " values(?::uuid,?::uuid,?,?,'INCOMING',0.100000,1,0,?::uuid)",
                templateId,
                tenant,
                "TPL-" + tag,
                "来料质检模板-" + tag,
                user));
    UUID id = UUID.randomUUID();
    as(
        () ->
            sql.execute(
                "insert into quality_inspection_order(id,tenant_id,inspection_no,template_id,"
                    + " template_code,template_name,inspection_type,sku_code,arrived_quantity,"
                    + " lot_size,sampling_ratio,min_sample_quantity,max_sample_quantity,"
                    + " rounding_mode,sample_quantity,sampled_quantity,created_by)"
                    + " values(?::uuid,?::uuid,?,?::uuid,?,?,'INCOMING',?,100.000000,100.000000,"
                    + "0.100000,1,0,'CEILING',10.000000,10.000000,?::uuid)",
                id,
                tenant,
                "QI-" + tag,
                templateId,
                "TPL-" + tag,
                "来料质检模板-" + tag,
                "SKU-" + tag,
                user));
    return id;
  }

  /** 建一份草稿申诉。 */
  PlatformAppealDetail newAppeal(UUID disputeId, String tag) {
    return as(
        () ->
            appeals.createAppeal(
                new PlatformAppealCreate(
                    disputeId, "我方已于时限内妥投，证据见附件-" + tag, "TPL-AZ-01"),
                user));
  }

  /** 建一件**已提交举证**的纠纷（用于申诉提交那条链）。 */
  PlatformDisputeDetail preparingWithItem(String tag, String evidenceType) {
    return preparingDispute(tag, evidenceType);
  }

  // ==========================================================================
  // 一、建单与主张金额
  // ==========================================================================

  @Test
  void 建申诉单_主张金额从纠纷单上读() {
    PlatformDisputeDetail d = newDispute("AMT", OffsetDateTime.now(ZoneOffset.UTC).plusDays(14));
    PlatformAppealDetail a = newAppeal(d.head().id(), "AMT");

    assertThat(a.head().appealStatus()).isEqualTo("DRAFT");
    assertThat(a.head().claimedAmount()).isEqualByComparingTo("88.5000");
    assertThat(a.head().claimedCurrency()).isEqualTo("USD");
    assertThat(a.appealText()).contains("证据见附件");
    assertThat(a.head().templateCode()).isEqualTo("TPL-AZ-01");
    // 草稿态没有任何提交记录。
    assertThat(a.submissions()).isEmpty();
    assertThat(a.materials()).isEmpty();
  }

  @Test
  void 同一件纠纷不能有两份未终结的申诉() {
    PlatformDisputeDetail d = newDispute("DUP", OffsetDateTime.now(ZoneOffset.UTC).plusDays(14));
    newAppeal(d.head().id(), "DUP");
    assertThatThrownBy(() -> newAppeal(d.head().id(), "DUP2"))
        .hasStackTraceContaining("已经有一份未终结的申诉");
  }

  // ==========================================================================
  // 二、举证材料只能来自系统记录
  // ==========================================================================

  @Test
  void 举证材料必须来自系统里已有的记录() {
    PlatformDisputeDetail d = preparingDispute("SRC", "LOGISTICS_TRACKING_PROOF");
    PlatformAppealDetail a = newAppeal(d.head().id(), "SRC");

    // 指向一条**不存在**的记录 ⇒ 服务层 400。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        appeals.attachMaterial(
                            a.head().id(),
                            new PlatformAppealMaterialCreate(
                                "SHIPMENT_TRACKING_OBSERVATION", UUID.randomUUID(), null, "编的"),
                            user)))
        .hasStackTraceContaining("举证材料必须来自系统里已有的事实记录");
  }

  @Test
  void 绕过服务层直连SQL塞一条假材料也被DB拒() {
    PlatformDisputeDetail d = preparingDispute("SRC2", "LOGISTICS_TRACKING_PROOF");
    PlatformAppealDetail a = newAppeal(d.head().id(), "SRC2");

    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "insert into platform_dispute_appeal_material(id,tenant_id,appeal_id,"
                                + " material_seq,evidence_type,source_module,source_ref_id,"
                                + " source_ref_no,source_label,source_locator,source_occurred_at,"
                                + " source_kind,created_by)"
                                + " values(?::uuid,?::uuid,?::uuid,1,'LOGISTICS_TRACKING_PROOF',"
                                + "'SHIPMENT_TRACKING_OBSERVATION',?::uuid,'','编的轨迹','',now(),"
                                + "'DELIVERED',?::uuid)",
                            UUID.randomUUID(),
                            tenant,
                            a.head().id(),
                            UUID.randomUUID(),
                            user)))
        .hasStackTraceContaining("举证材料必须来自系统里已有的事实记录");
  }

  @Test
  void 证据类型由来源记录推导_四类来源都验一遍() {
    PlatformDisputeDetail d = preparingDispute("TYPE", "PRODUCT_PHOTO");
    PlatformAppealDetail a = newAppeal(d.head().id(), "TYPE");
    UUID caseId = newAftersalesCase("TYPE");

    // 商品照片（附件 evidence_kind=PRODUCT_PHOTO）
    UUID photo = newAttachment(caseId, "PRODUCT_PHOTO", "TYPE");
    as(
        () ->
            appeals.attachMaterial(
                a.head().id(),
                new PlatformAppealMaterialCreate("AFTERSALES_ATTACHMENT", photo, null, ""),
                user));
    // 沟通记录
    UUID msg = newMessage(caseId, "TYPE");
    as(
        () ->
            appeals.attachMaterial(
                a.head().id(),
                new PlatformAppealMaterialCreate("AFTERSALES_MESSAGE", msg, null, ""),
                user));
    // 物流签收（轨迹观测）
    UUID track = newTrackingObservation("TYPE");
    as(
        () ->
            appeals.attachMaterial(
                a.head().id(),
                new PlatformAppealMaterialCreate("SHIPMENT_TRACKING_OBSERVATION", track, null, ""),
                user));
    // 检测报告（质检单）
    UUID inspection = newInspectionOrder("TYPE");
    as(
        () ->
            appeals.attachMaterial(
                a.head().id(),
                new PlatformAppealMaterialCreate("QUALITY_INSPECTION_ORDER", inspection, null, ""),
                user));

    PlatformAppealDetail full = as(() -> appeals.appealDetail(a.head().id()));
    assertThat(full.materials()).hasSize(4);
    assertThat(full.materials())
        .extracting(PlatformAppealMaterialRow::evidenceType)
        .containsExactlyInAnyOrder(
            "PRODUCT_PHOTO", "COMMUNICATION_LOG", "LOGISTICS_TRACKING_PROOF",
            "ORDER_FULFILLMENT_PROOF");
    // 序号由 DB 排。
    assertThat(full.materials())
        .extracting(PlatformAppealMaterialRow::materialSeq)
        .containsExactly(1, 2, 3, 4);
  }

  @Test
  void 材料只存逻辑引用与快照_库里没有文件本体列() {
    PlatformDisputeDetail d = preparingDispute("REF", "PRODUCT_PHOTO");
    PlatformAppealDetail a = newAppeal(d.head().id(), "REF");
    UUID caseId = newAftersalesCase("REF");
    UUID photo = newAttachment(caseId, "PRODUCT_PHOTO", "REF");

    as(
        () ->
            appeals.attachMaterial(
                a.head().id(),
                new PlatformAppealMaterialCreate("AFTERSALES_ATTACHMENT", photo, null, "商品照片"),
                user));

    PlatformAppealMaterialRow m = as(() -> appeals.appealDetail(a.head().id())).materials().get(0);
    assertThat(m.sourceModule()).isEqualTo("AFTERSALES_ATTACHMENT");
    assertThat(m.sourceRefId()).isEqualTo(photo);
    // 快照元数据：有单号 / 描述 / 定位符 / 时刻。
    assertThat(m.sourceRefNo()).isEqualTo("SRC-REF");
    assertThat(m.sourceLabel()).isEqualTo("photo-REF.jpg");
    assertThat(m.sourceLocator()).isEqualTo("ev/REF/photo.jpg");
    assertThat(m.sourceOccurredAt()).isNotNull();
    assertThat(m.sourceKind()).isEqualTo("PRODUCT_PHOTO");

    // ⚠️ 库里**没有**文件本体列（只存逻辑引用 + 快照）。
    List<String> columns =
        sql.fetch(
                "select column_name from information_schema.columns"
                    + " where table_name = 'platform_dispute_appeal_material'")
            .map(x -> x.get(0, String.class));
    assertThat(columns)
        .noneMatch(
            c ->
                c.contains("body")
                    || c.contains("content")
                    || c.contains("bytes")
                    || c.contains("blob"));
    assertThat(columns).contains("source_ref_id", "source_locator", "source_label");
  }

  @Test
  void 材料覆盖清单项时驱动上游标为已具备() {
    PlatformDisputeDetail d = preparingDispute("COV", "LOGISTICS_TRACKING_PROOF");
    UUID itemId = d.evidenceItems().get(0).id();
    PlatformAppealDetail a = newAppeal(d.head().id(), "COV");
    UUID track = newTrackingObservation("COV");

    assertThat(as(() -> disputes.disputeDetail(d.head().id())).evidenceItems().get(0).evidenceState())
        .isEqualTo("PENDING");

    as(
        () ->
            appeals.attachMaterial(
                a.head().id(),
                new PlatformAppealMaterialCreate(
                    "SHIPMENT_TRACKING_OBSERVATION", track, itemId, "承运商轨迹"),
                user));

    // 上游的清单项被驱动成「已具备」（上游仍是唯一写入者）。
    assertThat(as(() -> disputes.disputeDetail(d.head().id())).evidenceItems().get(0).evidenceState())
        .isEqualTo("PROVIDED");
    assertThat(as(() -> appeals.appealDetail(a.head().id())).gate().coveredItemCount()).isEqualTo(1);
  }

  @Test
  void 证据类型与清单项类型对不上时拒绝() {
    PlatformDisputeDetail d = preparingDispute("MISMATCH", "LOGISTICS_TRACKING_PROOF");
    UUID itemId = d.evidenceItems().get(0).id();
    PlatformAppealDetail a = newAppeal(d.head().id(), "MISMATCH");
    UUID caseId = newAftersalesCase("MISMATCH");
    UUID photo = newAttachment(caseId, "PRODUCT_PHOTO", "MISMATCH");

    assertThatThrownBy(
            () ->
                as(
                    () ->
                        appeals.attachMaterial(
                            a.head().id(),
                            new PlatformAppealMaterialCreate(
                                "AFTERSALES_ATTACHMENT", photo, itemId, ""),
                            user)))
        .hasStackTraceContaining("对不上");
  }

  // ==========================================================================
  // 三、时限判定：服务层与 DB 触发器共用同一个判据
  // ==========================================================================

  @Test
  void 判据函数存在且服务层与直连调用输出逐字一致() {
    PlatformDisputeDetail d = preparingDispute("GATE", "PRODUCT_PHOTO");
    PlatformAppealDetail a = newAppeal(d.head().id(), "GATE");

    // 函数真的在库里（这是「共用同一个判据」的前提）。
    Integer fn =
        sql.fetchOne(
                "select count(*) from pg_proc where proname = 'platform_dispute_appeal_gate'")
            .get(0, Integer.class);
    assertThat(fn).as("判据必须是 DB 里的一个函数").isEqualTo(1);

    // 服务层给出的判据 = 直连调用函数的输出。
    Record direct =
        as(
                () ->
                    sql.fetchOne(
                        "select * from platform_dispute_appeal_gate(?::uuid,?::uuid,?::uuid)",
                        tenant,
                        d.head().id(),
                        a.head().id()));
    PlatformAppealGateView gate = a.gate();
    assertThat(direct.get("window_state", String.class)).isEqualTo(gate.windowState());
    assertThat(direct.get("is_submittable", Boolean.class)).isEqualTo(gate.submittable());
    assertThat(direct.get("missing_required_count", Integer.class))
        .isEqualTo((int) gate.missingRequiredCount());
    assertThat(direct.get("uncovered_required_count", Integer.class))
        .isEqualTo((int) gate.uncoveredRequiredCount());
    assertThat(direct.get("checklist_item_count", Integer.class))
        .isEqualTo((int) gate.checklistItemCount());
  }

  @Test
  void 超期后提交申诉_服务层与DB各拒一次() {
    PlatformDisputeDetail d = preparingDispute("LATE", "PRODUCT_PHOTO");
    PlatformAppealDetail a = newAppeal(d.head().id(), "LATE");

    // 把举证时限**收紧**到过去（V265 守卫③ 只禁放宽，不禁收紧）。
    as(
        () ->
            sql.execute(
                "update platform_dispute set evidence_deadline = ?::timestamptz"
                    + " where tenant_id = ?::uuid and id = ?::uuid",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                tenant,
                d.head().id()));

    // 判据说不可提交。
    PlatformAppealGateView gate = as(() -> appeals.appealDetail(a.head().id())).gate();
    assertThat(gate.windowState()).isEqualTo("EXPIRED");
    assertThat(gate.submittable()).isFalse();

    // 服务层 400（写之前就拦）。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        appeals.submitAppeal(
                            a.head().id(), new PlatformAppealSubmit("迟了", versionOf(a.head().id())),
                            user)))
        .hasStackTraceContaining("申诉提交窗口已关闭");

    // ⚠️ **绕过服务层直连 SQL** 也被 DB 拒（守卫① 调的是同一个函数）。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "update platform_dispute_appeal set appeal_status = 'SUBMITTED',"
                                + " submitted_at = ?::timestamptz, submitted_by = ?::uuid,"
                                + " version = version + 1"
                                + " where tenant_id = ?::uuid and id = ?::uuid",
                            OffsetDateTime.now(ZoneOffset.UTC),
                            user,
                            tenant,
                            a.head().id())))
        .hasStackTraceContaining("申诉提交窗口已关闭");
  }

  @Test
  void 判据说可提交时直连SQL也过得去_证明两侧不漂移() {
    PlatformDisputeDetail d = preparingDispute("OK", "PRODUCT_PHOTO");
    PlatformAppealDetail a = newAppeal(d.head().id(), "OK");
    UUID caseId = newAftersalesCase("OK");
    UUID photo = newAttachment(caseId, "PRODUCT_PHOTO", "OK");
    UUID itemId = d.evidenceItems().get(0).id();

    as(
        () ->
            appeals.attachMaterial(
                a.head().id(),
                new PlatformAppealMaterialCreate("AFTERSALES_ATTACHMENT", photo, itemId, ""),
                user));

    assertThat(as(() -> appeals.appealDetail(a.head().id())).gate().submittable()).isTrue();

    // 判据说可以 ⇒ 直连 SQL 也写得进去（DB 守卫与服务层给出同一个答案）。
    as(
        () ->
            sql.execute(
                "update platform_dispute_appeal set appeal_status = 'SUBMITTED',"
                    + " submitted_at = ?::timestamptz, submitted_by = ?::uuid, version = version + 1"
                    + " where tenant_id = ?::uuid and id = ?::uuid",
                OffsetDateTime.now(ZoneOffset.UTC),
                user,
                tenant,
                a.head().id()));
    assertThat(as(() -> appeals.appealDetail(a.head().id())).head().appealStatus())
        .isEqualTo("SUBMITTED");
  }

  // ==========================================================================
  // 四、必举证项：齐备 + 被材料覆盖
  // ==========================================================================

  @Test
  void 必举证项没具备不许提交申诉() {
    PlatformDisputeDetail d = preparingDispute("MISS", "PRODUCT_PHOTO");
    PlatformAppealDetail a = newAppeal(d.head().id(), "MISS");

    assertThatThrownBy(
            () ->
                as(
                    () ->
                        appeals.submitAppeal(
                            a.head().id(), new PlatformAppealSubmit("交", versionOf(a.head().id())), user)))
        .hasStackTraceContaining("必举证材料没有具备");
  }

  @Test
  void 清单说齐了但包里没有材料也不许提交() {
    PlatformDisputeDetail d = preparingDispute("UNCOV", "PRODUCT_PHOTO");
    UUID itemId = d.evidenceItems().get(0).id();
    PlatformAppealDetail a = newAppeal(d.head().id(), "UNCOV");

    // 人工把清单项标成「已具备」，但**没有**挂任何材料 ⇒ 清单齐了、包里没有。
    as(
        () ->
            disputes.markEvidence(
                d.head().id(),
                itemId,
                new PlatformEvidenceMark("PROVIDED", "人工核对过", null),
                user));
    PlatformAppealGateView gate = as(() -> appeals.appealDetail(a.head().id())).gate();
    assertThat(gate.missingRequiredCount()).isZero();
    assertThat(gate.uncoveredRequiredCount()).isEqualTo(1);

    assertThatThrownBy(
            () ->
                as(
                    () ->
                        appeals.submitAppeal(
                            a.head().id(), new PlatformAppealSubmit("交", versionOf(a.head().id())), user)))
        .hasStackTraceContaining("没有被本次申诉的材料覆盖");
  }

  // ==========================================================================
  // 五、提交申诉：驱动上游 + 落待提交平台事实
  // ==========================================================================

  @Test
  void 提交申诉驱动上游提交举证_且不会因超期被判负() {
    PlatformDisputeDetail d = preparingDispute("SUB", "LOGISTICS_TRACKING_PROOF");
    UUID itemId = d.evidenceItems().get(0).id();
    PlatformAppealDetail a = newAppeal(d.head().id(), "SUB");
    UUID track = newTrackingObservation("SUB");
    as(
        () ->
            appeals.attachMaterial(
                a.head().id(),
                new PlatformAppealMaterialCreate(
                    "SHIPMENT_TRACKING_OBSERVATION", track, itemId, "承运商轨迹"),
                user));

    PlatformAppealDetail submitted =
        as(
            () ->
                appeals.submitAppeal(
                    a.head().id(), new PlatformAppealSubmit("已打包提交", versionOf(a.head().id())), user));

    assertThat(submitted.head().appealStatus()).isEqualTo("SUBMITTED");
    assertThat(submitted.head().submittedAt()).isNotNull();

    // ⚠️ 上游的举证提交时刻被写上了（**申诉提交与举证提交是同一个动作**）。
    PlatformDisputeDetail after = as(() -> disputes.disputeDetail(d.head().id()));
    assertThat(after.head().evidenceSubmittedAt()).isNotNull();
    assertThat(after.head().disputeStatus()).isEqualTo("EVIDENCE_SUBMITTED");

    // ⚠️ **「按时提交了却因超期被判负」是另一条假事实** ——
    //   ① 提交后 `evidence_submitted_at` 非空 ⇒ V265 守卫① 的判负分支**不再触发**；
    //   ② 连「把时限改到提交时刻之前」都**做不到**（V265 守卫① 直接 RAISE）——
    //      那条假事实在**结构上**就进不来。
    assertThat(after.head().evidenceSubmittedAt()).isNotNull();
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "update platform_dispute set evidence_deadline = ?::timestamptz"
                                + " where tenant_id = ?::uuid and id = ?::uuid",
                            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                            tenant,
                            d.head().id())))
        .hasStackTraceContaining("晚于举证截止时刻");
    assertThat(as(() -> disputes.disputeDetail(d.head().id())).head().disputeStatus())
        .isEqualTo("EVIDENCE_SUBMITTED");
  }

  @Test
  void 不假装能调平台_只落待提交平台事实() {
    PlatformDisputeDetail d = preparingDispute("OUT", "LOGISTICS_TRACKING_PROOF");
    UUID itemId = d.evidenceItems().get(0).id();
    PlatformAppealDetail a = newAppeal(d.head().id(), "OUT");
    UUID track = newTrackingObservation("OUT");
    as(
        () ->
            appeals.attachMaterial(
                a.head().id(),
                new PlatformAppealMaterialCreate(
                    "SHIPMENT_TRACKING_OBSERVATION", track, itemId, ""),
                user));
    PlatformAppealDetail submitted =
        as(
            () ->
                appeals.submitAppeal(
                    a.head().id(), new PlatformAppealSubmit("交", versionOf(a.head().id())), user));

    assertThat(submitted.submissions()).hasSize(1);
    PlatformAppealSubmissionRow s = submitted.submissions().get(0);
    assertThat(s.targetSystem()).isEqualTo("CHANNEL");
    assertThat(s.targetKey()).isNotBlank();
    assertThat(s.idempotencyKey()).isNotBlank();
    assertThat(s.submissionStatus()).isEqualTo("PENDING");
    assertThat(s.attemptCount()).isEqualTo(1);

    // 载荷是**具名结构**的 jsonb（不是字符串拼接），且带着申诉文本与材料引用。
    String payload =
        as(
                () ->
                    sql.fetchOne(
                            "select payload::text from platform_dispute_appeal_submission"
                                + " where tenant_id = ?::uuid and appeal_id = ?::uuid",
                            tenant,
                            a.head().id())
                        .get(0, String.class));
    assertThat(payload).contains("\"appealText\"").contains("\"materials\"").contains("evidenceType");
  }

  @Test
  void 提交尝试状态机四个状态都可达_且重试复用同一行() {
    PlatformDisputeDetail d = preparingDispute("ST", "LOGISTICS_TRACKING_PROOF");
    UUID itemId = d.evidenceItems().get(0).id();
    PlatformAppealDetail a = newAppeal(d.head().id(), "ST");
    UUID track = newTrackingObservation("ST");
    as(
        () ->
            appeals.attachMaterial(
                a.head().id(),
                new PlatformAppealMaterialCreate(
                    "SHIPMENT_TRACKING_OBSERVATION", track, itemId, ""),
                user));
    as(
        () ->
            appeals.submitAppeal(
                a.head().id(), new PlatformAppealSubmit("交", versionOf(a.head().id())), user));

    // 已发出
    as(
        () ->
            asPlatform(
                () ->
                    appeals.recordSubmissionAttempt(
                        a.head().id(),
                        new PlatformAppealSubmissionAttempt("SENT", null, null),
                        user)));
    assertThat(as(() -> appeals.appealDetail(a.head().id())).submissions().get(0).submissionStatus())
        .isEqualTo("SENT");

    // 失败（必须写原因）
    assertThatThrownBy(
            () ->
                asPlatform(
                    () ->
                        appeals.recordSubmissionAttempt(
                            a.head().id(),
                            new PlatformAppealSubmissionAttempt("FAILED", null, null),
                            user)))
        .hasStackTraceContaining("必须写清失败原因");

    as(
        () ->
            asPlatform(
                () ->
                    appeals.recordSubmissionAttempt(
                        a.head().id(),
                        new PlatformAppealSubmissionAttempt(
                            "FAILED", "Amazon 限流", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10)),
                        user)));
    PlatformAppealDetail failed = as(() -> appeals.appealDetail(a.head().id()));
    assertThat(failed.submissions().get(0).submissionStatus()).isEqualTo("FAILED");
    assertThat(failed.submissions().get(0).lastError()).isEqualTo("Amazon 限流");
    assertThat(failed.submissions().get(0).nextRetryAt()).isNotNull();

    // 重试：**复用同一行**，幂等键稳定，尝试次数 +1。
    String keyBefore = failed.submissions().get(0).idempotencyKey();
    PlatformAppealDetail retried =
        as(() -> appeals.retrySubmission(a.head().id(), new PlatformAppealRetrySubmission(null), user));
    assertThat(retried.submissions()).hasSize(1);
    assertThat(retried.submissions().get(0).id()).isEqualTo(failed.submissions().get(0).id());
    assertThat(retried.submissions().get(0).idempotencyKey()).isEqualTo(keyBefore);
    assertThat(retried.submissions().get(0).submissionStatus()).isEqualTo("PENDING");
    assertThat(retried.submissions().get(0).attemptCount()).isEqualTo(2);

    // 平台已受理 ⇒ 终态不可再改。
    as(
        () ->
            asPlatform(
                () ->
                    appeals.recordSubmissionAttempt(
                        a.head().id(),
                        new PlatformAppealSubmissionAttempt("ACKNOWLEDGED", null, null),
                        user)));
    assertThatThrownBy(
            () ->
                asPlatform(
                    () ->
                        appeals.recordSubmissionAttempt(
                            a.head().id(),
                            new PlatformAppealSubmissionAttempt("SENT", null, null),
                            user)))
        .hasStackTraceContaining("终态不可修改");
    assertThatThrownBy(
            () -> as(() -> appeals.retrySubmission(a.head().id(), new PlatformAppealRetrySubmission(null), user)))
        .hasStackTraceContaining("不需要重试");
  }

  // ==========================================================================
  // 六、平台回复与结果
  // ==========================================================================

  @Test
  void 平台回复_胜诉与败诉都驱动上游() {
    PlatformDisputeDetail d = preparingDispute("WON", "LOGISTICS_TRACKING_PROOF");
    UUID itemId = d.evidenceItems().get(0).id();
    PlatformAppealDetail a = newAppeal(d.head().id(), "WON");
    UUID track = newTrackingObservation("WON");
    as(
        () ->
            appeals.attachMaterial(
                a.head().id(),
                new PlatformAppealMaterialCreate(
                    "SHIPMENT_TRACKING_OBSERVATION", track, itemId, ""),
                user));
    as(
        () ->
            appeals.submitAppeal(
                a.head().id(), new PlatformAppealSubmit("交", versionOf(a.head().id())), user));

    PlatformAppealDetail won =
        asPlatform(
            () ->
                appeals.recordPlatformReply(
                    a.head().id(),
                    new PlatformAppealPlatformReply(
                        "平台已支持我方", "R-1", "WON", null, null, "签收照片有效",
                        versionOf(a.head().id())),
                    user));
    assertThat(won.head().appealStatus()).isEqualTo("RESOLVED_WON");
    assertThat(won.head().resolutionCode()).isEqualTo("PLATFORM_RULING_FOR");
    assertThat(won.head().resolvedAt()).isNotNull();
    // 上游的纠纷单也被驱动成胜诉（上游仍是唯一写入者）。
    assertThat(as(() -> disputes.disputeDetail(d.head().id())).head().disputeStatus())
        .isEqualTo("RESOLVED_WON");
  }

  @Test
  void 平台回复_败诉() {
    PlatformDisputeDetail d = preparingDispute("LOST", "LOGISTICS_TRACKING_PROOF");
    UUID itemId = d.evidenceItems().get(0).id();
    PlatformAppealDetail a = newAppeal(d.head().id(), "LOST");
    UUID track = newTrackingObservation("LOST");
    as(
        () ->
            appeals.attachMaterial(
                a.head().id(),
                new PlatformAppealMaterialCreate(
                    "SHIPMENT_TRACKING_OBSERVATION", track, itemId, ""),
                user));
    as(
        () ->
            appeals.submitAppeal(
                a.head().id(), new PlatformAppealSubmit("交", versionOf(a.head().id())), user));

    PlatformAppealDetail lost =
        asPlatform(
            () ->
                appeals.recordPlatformReply(
                    a.head().id(),
                    new PlatformAppealPlatformReply(
                        "平台不认可我方证据", "R-2", "LOST", null, null, "签收时间存疑", versionOf(a.head().id())),
                    user));
    assertThat(lost.head().appealStatus()).isEqualTo("RESOLVED_LOST");
    assertThat(lost.head().resolutionCode()).isEqualTo("PLATFORM_RULING_AGAINST");
    assertThat(as(() -> disputes.disputeDetail(d.head().id())).head().disputeStatus())
        .isEqualTo("RESOLVED_LOST");
  }

  @Test
  void 平台回复_部分支持_金额必须小于主张金额且不代填上游结果码() {
    PlatformDisputeDetail d = preparingDispute("PART", "LOGISTICS_TRACKING_PROOF");
    UUID itemId = d.evidenceItems().get(0).id();
    PlatformAppealDetail a = newAppeal(d.head().id(), "PART");
    UUID track = newTrackingObservation("PART");
    as(
        () ->
            appeals.attachMaterial(
                a.head().id(),
                new PlatformAppealMaterialCreate(
                    "SHIPMENT_TRACKING_OBSERVATION", track, itemId, ""),
                user));
    as(
        () ->
            appeals.submitAppeal(
                a.head().id(), new PlatformAppealSubmit("交", versionOf(a.head().id())), user));

    // 全额 = 胜诉，不该走「部分」。
    assertThatThrownBy(
            () ->
                asPlatform(
                    () ->
                        appeals.recordPlatformReply(
                            a.head().id(),
                            new PlatformAppealPlatformReply(
                                "全额", "R-3", "PARTIAL", new BigDecimal("88.5000"), "USD", "", versionOf(a.head().id())),
                            user)))
        .hasStackTraceContaining("必须小于主张金额");

    PlatformAppealDetail partial =
        asPlatform(
            () ->
                appeals.recordPlatformReply(
                    a.head().id(),
                    new PlatformAppealPlatformReply(
                        "部分支持", "R-4", "PARTIAL", new BigDecimal("40.0000"), "USD",
                        "平台按部分金额支持", versionOf(a.head().id())),
                    user));
    assertThat(partial.head().appealStatus()).isEqualTo("RESOLVED_PARTIAL");
    assertThat(partial.head().resolutionCode()).isEqualTo("PARTIAL_RULING");
    assertThat(partial.head().awardedAmount()).isEqualByComparingTo("40.0000");
    assertThat(partial.head().awardedCurrency()).isEqualTo("USD");

    // ⚠️ 11.4.1.1 的结果码域里**没有「部分」** ⇒ 本模块**不代填**上游的结果码，
    //    纠纷单仍在「举证已提交」（这是**如实报出的口径缺口**，不是漏做）。
    assertThat(as(() -> disputes.disputeDetail(d.head().id())).head().disputeStatus())
        .isEqualTo("EVIDENCE_SUBMITTED");
  }

  @Test
  void 我方撤诉驱动上游撤诉() {
    PlatformDisputeDetail d = preparingDispute("WD", "LOGISTICS_TRACKING_PROOF");
    UUID itemId = d.evidenceItems().get(0).id();
    PlatformAppealDetail a = newAppeal(d.head().id(), "WD");
    UUID track = newTrackingObservation("WD");
    as(
        () ->
            appeals.attachMaterial(
                a.head().id(),
                new PlatformAppealMaterialCreate(
                    "SHIPMENT_TRACKING_OBSERVATION", track, itemId, ""),
                user));
    as(
        () ->
            appeals.submitAppeal(
                a.head().id(), new PlatformAppealSubmit("交", versionOf(a.head().id())), user));

    PlatformAppealDetail wd =
        as(
            () ->
                appeals.withdrawAppeal(
                    a.head().id(),
                    new PlatformAppealWithdraw("与买家协商后撤诉", versionOf(a.head().id())),
                    user));
    assertThat(wd.head().appealStatus()).isEqualTo("WITHDRAWN");
    assertThat(wd.head().resolutionCode()).isEqualTo("WITHDRAWN_BY_US");
    assertThat(as(() -> disputes.disputeDetail(d.head().id())).head().disputeStatus())
        .isEqualTo("WITHDRAWN");
  }

  // ==========================================================================
  // 七、冻结与终态不可逆
  // ==========================================================================

  @Test
  void 材料包提交后冻结() {
    PlatformDisputeDetail d = preparingDispute("FREEZE", "LOGISTICS_TRACKING_PROOF");
    UUID itemId = d.evidenceItems().get(0).id();
    PlatformAppealDetail a = newAppeal(d.head().id(), "FREEZE");
    UUID track = newTrackingObservation("FREEZE");
    as(
        () ->
            appeals.attachMaterial(
                a.head().id(),
                new PlatformAppealMaterialCreate(
                    "SHIPMENT_TRACKING_OBSERVATION", track, itemId, ""),
                user));
    as(
        () ->
            appeals.submitAppeal(
                a.head().id(), new PlatformAppealSubmit("交", versionOf(a.head().id())), user));

    // 服务层拒（申诉单已不是草稿 ⇒ 材料包冻结）。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        appeals.attachMaterial(
                            a.head().id(),
                            new PlatformAppealMaterialCreate(
                                "SHIPMENT_TRACKING_OBSERVATION", UUID.randomUUID(), null, ""),
                            user)))
        .hasStackTraceContaining("不能挂证据材料");

    // 直连 SQL 也被 DB 拒。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "insert into platform_dispute_appeal_material(id,tenant_id,appeal_id,"
                                + " material_seq,evidence_type,source_module,source_ref_id,"
                                + " source_ref_no,source_label,source_locator,source_occurred_at,"
                                + " source_kind,created_by)"
                                + " values(?::uuid,?::uuid,?::uuid,9,'OTHER',"
                                + "'AFTERSALES_MESSAGE',?::uuid,'','x','',now(),'PLATFORM',?::uuid)",
                            UUID.randomUUID(),
                            tenant,
                            a.head().id(),
                            UUID.randomUUID(),
                            user)))
        .hasStackTraceContaining("材料包已冻结");
  }

  @Test
  void 终态不可逆_服务层与DB各拒一次() {
    PlatformDisputeDetail d = preparingDispute("TERM", "LOGISTICS_TRACKING_PROOF");
    UUID itemId = d.evidenceItems().get(0).id();
    PlatformAppealDetail a = newAppeal(d.head().id(), "TERM");
    UUID track = newTrackingObservation("TERM");
    as(
        () ->
            appeals.attachMaterial(
                a.head().id(),
                new PlatformAppealMaterialCreate(
                    "SHIPMENT_TRACKING_OBSERVATION", track, itemId, ""),
                user));
    as(
        () ->
            appeals.submitAppeal(
                a.head().id(), new PlatformAppealSubmit("交", versionOf(a.head().id())), user));
    asPlatform(
        () ->
            appeals.recordPlatformReply(
                a.head().id(),
                new PlatformAppealPlatformReply(
                    "平台支持我方", "R-TERM", "WON", null, null, "签收有效", versionOf(a.head().id())),
                user));

    // 服务层拒（版本是对的 ⇒ 拒的是状态，不是乐观锁）。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        appeals.updateText(
                            a.head().id(),
                            new PlatformAppealTextUpdate(
                                "改一下", "", versionOf(a.head().id())),
                            user)))
        .hasStackTraceContaining("不能改申诉文本");

    // 直连 SQL 也被 DB 拒（守卫③ 整行冻结）。
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        sql.execute(
                            "update platform_dispute_appeal set appeal_text = '偷偷改'"
                                + " where tenant_id = ?::uuid and id = ?::uuid",
                            tenant,
                            a.head().id())))
        .hasStackTraceContaining("终态");
  }

  // ==========================================================================
  // 八、列表与汇总
  // ==========================================================================

  @Test
  void 列表是真分页与真筛选_金额按币种分组不折算() {
    PlatformDisputeDetail d1 = preparingDispute("L1", "PRODUCT_PHOTO");
    PlatformDisputeDetail d2 = preparingDispute("L2", "PRODUCT_PHOTO");
    newAppeal(d1.head().id(), "L1");
    newAppeal(d2.head().id(), "L2");

    PlatformAppealPage page = as(() -> appeals.listAppeals(query(0, 20, null, null)));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.rows()).hasSize(2);

    // 真分页：第 0 页 1 条、第 1 页 1 条，汇总不随分页变小。
    PlatformAppealPage first = as(() -> appeals.listAppeals(query(0, 1, null, null)));
    PlatformAppealPage second = as(() -> appeals.listAppeals(query(1, 1, null, null)));
    assertThat(first.rows()).hasSize(1);
    assertThat(second.rows()).hasSize(1);
    assertThat(first.rows().get(0).id()).isNotEqualTo(second.rows().get(0).id());
    assertThat(first.summary().totalAppeals()).isEqualTo(2);

    // 真筛选：按状态筛。
    PlatformAppealPage drafts = as(() -> appeals.listAppeals(query(0, 20, "DRAFT", null)));
    assertThat(drafts.total()).isEqualTo(2);
    PlatformAppealPage submitted = as(() -> appeals.listAppeals(query(0, 20, "SUBMITTED", null)));
    assertThat(submitted.total()).isZero();

    // 金额按币种分组（这里全是 USD ⇒ 一组）。
    assertThat(page.summary().currencyBreakdown()).hasSize(1);
    assertThat(page.summary().currencyBreakdown().get(0).currency()).isEqualTo("USD");
    assertThat(page.summary().currencyBreakdown().get(0).claimedAmount())
        .isEqualByComparingTo("177.0000");
  }

  @Test
  void 关键字能搜到申诉号与平台纠纷号() {
    PlatformDisputeDetail d = preparingDispute("KW", "PRODUCT_PHOTO");
    newAppeal(d.head().id(), "KW");
    assertThat(as(() -> appeals.listAppeals(query(0, 20, null, "AZ-KW"))).total()).isEqualTo(1);
    assertThat(as(() -> appeals.listAppeals(query(0, 20, null, "不存在的号"))).total()).isZero();
  }

  // ==========================================================================
  // 九、金额与时间的落法
  // ==========================================================================

  @Test
  void 金额是NUMERIC_时间落成timestamptz() {
    List<Record> cols =
        sql.fetch(
            "select column_name, data_type, numeric_precision, numeric_scale"
                + " from information_schema.columns where table_name = 'platform_dispute_appeal'"
                + " and column_name in ('claimed_amount','awarded_amount')");
    assertThat(cols).hasSize(2);
    for (Record c : cols) {
      assertThat(c.get("data_type", String.class)).isEqualTo("numeric");
      assertThat(c.get("numeric_precision", Integer.class)).isEqualTo(19);
      assertThat(c.get("numeric_scale", Integer.class)).isEqualTo(4);
    }

    // ⚠️ **没有**「剩余时长」这一类列：倒计时是派生态。
    List<String> all =
        sql.fetch("select column_name from information_schema.columns where table_name = 'platform_dispute_appeal'")
            .map(x -> x.get(0, String.class));
    assertThat(all)
        .noneMatch(
            c ->
                c.contains("remaining")
                    || c.contains("countdown")
                    || c.contains("hours_left")
                    || c.contains("days_left"));

    // 时间列是 timestamptz。
    List<String> timeCols =
        sql.fetch(
                "select data_type from information_schema.columns"
                    + " where table_name = 'platform_dispute_appeal'"
                    + " and column_name in ('submitted_at','platform_reply_at','resolved_at','created_at')")
            .map(x -> x.get(0, String.class));
    assertThat(timeCols).hasSize(4).allMatch("timestamp with time zone"::equals);
  }

  @Test
  void 举证时限写回零漂移_非UTC偏移夹具() {
    // **非 UTC 偏移**夹具：只有这种时刻才能测出 Timestamp.valueOf 的 −28800s 静默偏移。
    OffsetDateTime deadline = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");
    PlatformDisputeDetail d = newDispute("TZ", deadline);
    OffsetDateTime readBack = as(() -> disputes.disputeDetail(d.head().id())).head().evidenceDeadline();
    assertThat(readBack.toInstant()).isEqualTo(deadline.toInstant());
    assertThat(readBack).isEqualTo(deadline);
  }

  // ==========================================================================
  // 十、能力声明
  // ==========================================================================

  @Test
  void 能力声明如实报出两个缺口() {
    PlatformAppealCapabilities c = as(() -> appeals.capabilities());

    // channel 没有申诉提交端点 ⇒ 平台提交这一维度 available=false。
    PlatformAppealSectionCapability platform =
        c.dimensions().stream()
            .filter(x -> "platformSubmission".equals(x.section()))
            .findFirst()
            .orElseThrow();
    assertThat(platform.available()).isFalse();
    assertThat(platform.reason()).contains("没有纠纷 / 申诉提交端点");

    // 16 合规未交付 ⇒ 账号绩效 available=false。
    PlatformAppealSectionCapability perf =
        c.dimensions().stream()
            .filter(x -> "accountPerformance".equals(x.section()))
            .findFirst()
            .orElseThrow();
    assertThat(perf.available()).isFalse();

    // 刻意不做的事被声明出来。
    assertThat(c.boundaries()).anyMatch(b -> b.boundary().contains("不接受任意上传的文件"));
    assertThat(c.actions())
        .anyMatch(x -> "UPLOAD_ARBITRARY_FILE".equals(x.code()) && !x.allowed());
    assertThat(c.actions())
        .anyMatch(x -> "CALL_PLATFORM".equals(x.code()) && !x.allowed());
  }

  // ==========================================================================
  // 十一、HTTP 层
  // ==========================================================================

  @Test
  void HTTP层端点与权限都对() throws Exception {
    PlatformDisputeDetail d = preparingDispute("HTTP", "PRODUCT_PHOTO");
    PlatformAppealDetail a = newAppeal(d.head().id(), "HTTP");

    http.perform(get("/trade/platform-dispute-appeals/capabilities")
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk());
    http.perform(get("/trade/platform-dispute-appeals")
            .header("Authorization", "Bearer tenant-main"))
        .andExpect(status().isOk());
    var detailResp =
        http.perform(get("/trade/platform-dispute-appeals/{id}", a.head().id())
                .header("Authorization", "Bearer tenant-main"))
            .andReturn()
            .getResponse();
    assertThat(detailResp.getStatus())
        .as(detailResp.getContentAsString())
        .isEqualTo(200);

    // 平台回复端点需要**独立权限** ⇒ 只有读/写权限的角色被拒。
    http.perform(post("/trade/platform-dispute-appeals/{id}/platform-reply", a.head().id())
            .header("Authorization", "Bearer readonly")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{\"platformReplyText\":\"x\",\"result\":\"WON\"}"))
        .andExpect(status().is4xxClientError());
  }

  private static PlatformAppealListQuery query(
      Integer page, Integer size, String status, String keyword) {
    return new PlatformAppealListQuery(
        page, size, status, null, null, null, null, null, null, null, keyword, null);
  }

  /** 读当前版本（挂材料会推进版本 ⇒ 提交前必须重读，不能拿建单那一刻的版本）。 */
  long versionOf(UUID appealId) {
    return as(() -> appeals.appealDetail(appealId).head().version());
  }
}
