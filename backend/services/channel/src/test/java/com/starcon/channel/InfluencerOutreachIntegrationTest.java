package com.starcon.channel;

import static com.starcon.channel.InfluencerModels.*;
import static com.starcon.channel.InfluencerOutreachModels.*;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 14.5.1.2 建联与样品。
 *
 * <p>本测试类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>🔴 按规则筛选达人</b>：条件真落库、真筛选（不是写死的候选列表）。
 *   <li><b>🔴 建联必须走官方接口</b>：{@code contact_channel} 被 DB 钉死；
 *       官方私信接口未接入时发送入口<b>不写任何数据</b>；
 *       批量建联<b>只生成草稿</b>。
 *   <li><b>🔴 幂等键 + 频率上限</b>：重复登记被拒；超每日上限被拒。
 *   <li><b>🔴 状态机</b>：非法流转被拒；终态没有出边。
 *   <li><b>🔴 样品签收由包裹推导</b>：人手改样品单状态推不出 DELIVERED / SIGNED。
 *   <li><b>🔴 佣金计提</b>：金额由服务层按规则算；{@code finance_posted} 恒为 false
 *       （DB 直接拒写 true）；{@code tracking_available} 恒为 false。
 *   <li><b>🔴 「没录」不是 0</b>：样品成本 / 佣金比例没录就是 null。
 *   <li><b>🔴 时间字段写回值正确</b>（AGENTS.md：断言写回值，不能只断言没抛异常）。
 *   <li><b>真分页真筛选</b>、<b>能力声明如实标四件做不到的事</b>。
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：用墙钟会让
 * 「这次写入落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InfluencerOutreachIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 🔴 每个用例一对**全新的租户**（共用租户会让前一个用例的数据被后一个用例数进去）。 */
  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

  /** 🔴 钉死的时刻与金额。 */
  static final OffsetDateTime SENT_AT = OffsetDateTime.parse("2026-10-01T09:30:00+08:00");
  static final OffsetDateTime SIGNED_AT = OffsetDateTime.parse("2026-10-05T18:20:00+08:00");
  static final OffsetDateTime FOLLOWUP_AT = OffsetDateTime.parse("2026-10-02T10:00:00+08:00");

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

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
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer t-a-" + a;
    bearerB = "Bearer t-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    actorA = actors.get(bearerA);
    actorB = actors.get(bearerB);
  }

  /** 播种全局码表（平台目录）。刻意**不在静态块里**播种（那时表还没建）。 */
  @BeforeEach
  void seedGlobalCatalog() {
    exec("insert into platform (id,code,name,integration,support_status,position)"
        + " values ('" + AMAZON + "','AMAZON','Amazon','OFFICIAL_API','AVAILABLE',1)"
        + " on conflict (id) do nothing");
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired InfluencerOutreachService service;
  @Autowired InfluencerService influencers;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, Supplier<T> work) {
    return access.run(who, permission, work);
  }

  void asVoid(AccessService.Access who, String permission, Runnable work) {
    access.run(
        who,
        permission,
        () -> {
          work.run();
          return null;
        });
  }

  static void exec(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以带 actor 的租户上下文执行 SQL（shop 的 `stamp_shop_owner()` 触发器要求 actor）。 */
  static void execAsTenant(UUID tenantId, String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以管理员连接执行 SQL（用于断言 DB 约束真的挡住了非法写入）。 */
  static boolean execFails(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
      return false;
    } catch (SQLException e) {
      return true;
    }
  }

  static String scalar(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  String seedShop(UUID tenantId, String code) {
    // 🔴 shop 有触发器 `stamp_shop_owner()`，要求会话里有 actor —— 用带 actor 的连接插入。
    execAsTenant(
        tenantId,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('"
            + tenantId
            + "','"
            + UUID.randomUUID()
            + "','"
            + AMAZON
            + "','"
            + code
            + "','测试店铺 "
            + code
            + "') on conflict (tenant_id,code) do nothing");
    return code;
  }

  /** 建一个达人（14.5.1.1 已交付的上游）。 */
  UUID seedInfluencer(String shopCode, String name, String category, Long followers) {
    var detail =
        as(
            actorA,
            "channel.operation.create",
            () ->
                influencers.saveProfile(
                    new SaveInfluencerRequest(
                        AMAZON,
                        shopCode,
                        "",
                        name,
                        "@" + name,
                        category,
                        followers == null ? 100_000L : followers,
                        new BigDecimal("0.05"),
                        new BigDecimal("10000"),
                        "USD",
                        new BigDecimal("0.02"),
                        "ACTIVE",
                        "")));
    return detail.influencer().id();
  }

  /** 建一个筛选条件（类目 + 粉丝层级）。 */
  UUID seedSegment(String code, String category, String followerTier) {
    return as(
            actorA,
            "channel.operation.create",
            () ->
                service.saveSegment(
                    new SaveInfluencerOutreachSegmentRequest(
                        code,
                        "分群 " + code,
                        "",
                        category,
                        followerTier,
                        null,
                        null,
                        null,
                        "",
                        "",
                        null,
                        "",
                        true,
                        "ACTIVE",
                        "")))
        .id();
  }

  /** 建一个邀约模板。 */
  UUID seedTemplate(String code, String body) {
    return as(
            actorA,
            "channel.operation.create",
            () ->
                service.saveTemplate(
                    new SaveInfluencerOutreachTemplateRequest(
                        code, "模板 " + code, AMAZON, "合作邀请", body, "ACTIVE", "")))
        .id();
  }

  /** 建一条建联记录（草稿）。 */
  UUID seedOutreach(UUID influencerId, UUID segmentId, UUID templateId, String key) {
    return as(
            actorA,
            "channel.operation.create",
            () ->
                service.saveOutreach(
                    new SaveInfluencerOutreachRequest(
                        influencerId, segmentId, templateId, key, "测试建联")))
        .id();
  }

  // ==================================================================
  // 一、按规则筛选达人
  // ==================================================================

  @Test
  @DisplayName("14.5.1.2 按规则筛选达人：条件真落库、真筛选（不是写死的候选列表）")
  void segmentCandidatesReallyFilter() {
    String shop = seedShop(a, "SEG-1");
    UUID beauty = seedInfluencer(shop, "美妆达人", "美妆", 200_000L);
    seedInfluencer(shop, "家居达人", "家居", 200_000L);
    UUID segment = seedSegment("SEG-BEAUTY", "美妆", "MID");

    var page = as(actorA, "channel.operation.read", () -> service.segmentCandidates(segment.toString(), null, 0, 50));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items()).extracting(InfluencerOutreachCandidate::id).containsExactly(beauty);

    // 🔴 换条件就换结果（证明是真筛选，不是碰巧只有一个达人）。
    UUID other = seedSegment("SEG-HOME", "家居", "MID");
    var otherPage =
        as(actorA, "channel.operation.read", () -> service.segmentCandidates(other.toString(), null, 0, 50));
    assertThat(otherPage.total()).isEqualTo(1);
    assertThat(otherPage.items().get(0).displayName()).isEqualTo("家居达人");

    // 🔴 粉丝层级下限真的参与筛选。
    UUID tierSegment = seedSegment("SEG-TOP", "美妆", "MACRO");
    var tierPage =
        as(
            actorA,
            "channel.operation.read",
            () -> service.segmentCandidates(tierSegment.toString(), null, 0, 50));
    assertThat(tierPage.total()).as("20 万粉是 MID，不是 MACRO").isZero();
  }

  @Test
  @DisplayName("14.5.1.2 筛选条件：黑名单里的达人默认被排除")
  void segmentCandidatesExcludeBlacklisted() {
    String shop = seedShop(a, "SEG-BL");
    UUID influencerId = seedInfluencer(shop, "被拉黑的达人", "美妆", 200_000L);
    UUID segment = seedSegment("SEG-BL", "美妆", "");

    as(
        actorA,
        "channel.operation.create",
        () ->
            influencers.blacklistInfluencer(
                new BlacklistInfluencerRequest(
                    influencerId, "FRAUD", "测试拉黑", null, "")));

    var page = as(actorA, "channel.operation.read", () -> service.segmentCandidates(segment.toString(), null, 0, 50));
    assertThat(page.total()).as("黑名单里的达人默认不建联").isZero();

    // 🔴 显式打开开关才可见。
    UUID loose =
        as(
                actorA,
                "channel.operation.create",
                () ->
                    service.saveSegment(
                        new SaveInfluencerOutreachSegmentRequest(
                            "SEG-BL-OPEN", "含黑名单", "", "美妆", "", null, null, null, "", "", null, "",
                            false, "ACTIVE", "")))
            .id();
    var openPage =
        as(actorA, "channel.operation.read", () -> service.segmentCandidates(loose.toString(), null, 0, 50));
    assertThat(openPage.total()).isEqualTo(1);
    assertThat(openPage.items().get(0).blacklisted()).isTrue();
  }

  // ==================================================================
  // 二、模板
  // ==================================================================

  @Test
  @DisplayName("14.5.1.2 模板渲染：真渲染 + 变量是闭合词表")
  void templateRenderAndClosedVocabulary() {
    String shop = seedShop(a, "TPL-1");
    UUID influencerId = seedInfluencer(shop, "小美", "美妆", 200_000L);
    UUID template =
        seedTemplate("TPL-1", "你好 {{display_name}}，我们是 {{shop_name}}（{{platform_name}}）。");

    var preview =
        as(
            actorA,
            "channel.operation.read",
            () -> service.templatePreview(template.toString(), influencerId));
    // 🔴 平台名称取自库里的真实平台目录（不写死「Amazon」—— 平台目录是全局码表）。
    String platformName =
        scalar("select name from platform where id = '" + AMAZON + "'");
    assertThat(preview.messageBody())
        .isEqualTo("你好 小美，我们是 测试店铺 TPL-1（" + platformName + "）。");
    assertThat(preview.values()).containsEntry("display_name", "小美");

    // 🔴 未登记的占位符在**保存模板时**就被拒（不是发出去才发现）。
    assertThatThrownBy(() -> seedTemplate("TPL-BAD", "你好 {{达人名字}}"))
        .hasMessageContaining("未登记的占位符");
  }

  // ==================================================================
  // 三、建联记录
  // ==================================================================

  @Test
  @DisplayName("14.5.1.2 建联记录：真落库 + 草稿没有发送时刻 + 沟通渠道钉死官方接口")
  void outreachRecordReallyStored() {
    String shop = seedShop(a, "OUT-1");
    UUID influencerId = seedInfluencer(shop, "小美", "美妆", 200_000L);
    UUID segment = seedSegment("SEG-OUT", "美妆", "");
    UUID template = seedTemplate("TPL-OUT", "你好 {{display_name}}");

    UUID id = seedOutreach(influencerId, segment, template, "key-1");
    var detail = as(actorA, "channel.operation.read", () -> service.outreach(id.toString()));
    assertThat(detail.outreach().status()).isEqualTo("DRAFT");
    assertThat(detail.outreach().sentAt()).as("草稿没有发送时刻").isNull();
    assertThat(detail.outreach().contactChannel()).isEqualTo("OFFICIAL_API");
    assertThat(detail.outreach().messageBody()).isEqualTo("你好 小美");
    assertThat(detail.outreach().idempotencyKey()).isEqualTo("key-1");
    assertThat(detail.outreach().influencerName()).isEqualTo("小美");
  }

  @Test
  @DisplayName("14.5.1.2 幂等键：重复登记同一条发送被拒；同一达人不能重复建联")
  void idempotencyAndSingleOpenOutreach() {
    String shop = seedShop(a, "IDEM-1");
    UUID influencerId = seedInfluencer(shop, "小美", "美妆", 200_000L);
    UUID segment = seedSegment("SEG-IDEM", "美妆", "");
    UUID template = seedTemplate("TPL-IDEM", "你好 {{display_name}}");
    seedOutreach(influencerId, segment, template, "key-dup");

    // 🔴 同一个幂等键再来一次 ⇒ 拒绝。
    assertThatThrownBy(() -> seedOutreach(influencerId, segment, template, "key-dup"))
        .hasMessageContaining("幂等键已存在");

    // 🔴 另一个幂等键、但同一个达人已有未走完的邀约 ⇒ 拒绝（重复建联 = 重复发私信）。
    assertThatThrownBy(() -> seedOutreach(influencerId, segment, template, "key-other"))
        .hasMessageContaining("未走完的邀约");
  }

  @Test
  @DisplayName("14.5.1.2 手动登记平台回执：状态转 SENT + 发送时刻写回值正确（+08:00 不能偏 −28800s）")
  void registerReceiptWritesBackCorrectInstant() {
    String shop = seedShop(a, "RCPT-1");
    UUID influencerId = seedInfluencer(shop, "小美", "美妆", 200_000L);
    UUID segment = seedSegment("SEG-RCPT", "美妆", "");
    UUID template = seedTemplate("TPL-RCPT", "你好 {{display_name}}");
    UUID id = seedOutreach(influencerId, segment, template, "key-rcpt");

    var row =
        as(
                actorA,
                "channel.operation.update",
                () ->
                    service.registerReceipt(
                        id.toString(),
                        new RegisterInfluencerOutreachReceiptRequest(
                            "MSG-1", "已通过官方私信发出", SENT_AT, "")))
            .id();
    assertThat(row).isNotNull();

    var detail = as(actorA, "channel.operation.read", () -> service.outreach(id.toString()));
    assertThat(detail.outreach().status()).isEqualTo("SENT");
    assertThat(detail.outreach().platformMessageId()).isEqualTo("MSG-1");
    assertThat(detail.outreach().platformReceipt()).isEqualTo("已通过官方私信发出");
    // 🔴 断言**写回的值**：用 Timestamp.valueOf(LocalDateTime) 会偏 −28800s。
    assertThat(detail.outreach().sentAt()).isNotNull();
    assertThat(detail.outreach().sentAt().toInstant()).isEqualTo(SENT_AT.toInstant());

    // 🔴 已发送的私信不许再登记一次回执。
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.registerReceipt(
                            id.toString(),
                            new RegisterInfluencerOutreachReceiptRequest(
                                "MSG-2", "重复登记", SENT_AT, ""))))
        .hasMessageContaining("只有草稿");
  }

  @Test
  @DisplayName("14.5.1.2 频率上限：超过每日发送上限被拒（风控红线）")
  void rateLimitBlocksOverDailyCap() {
    String shop = seedShop(a, "RATE-1");
    UUID influencerId = seedInfluencer(shop, "小美", "美妆", 200_000L);
    UUID segment = seedSegment("SEG-RATE", "美妆", "");
    UUID template = seedTemplate("TPL-RATE", "你好 {{display_name}}");

    // 🔴 每日上限配成 1，最小间隔 0（只为测「每日上限」这一条）。
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.savePolicy(
                new SaveInfluencerOutreachPolicyRequest(AMAZON, 1, 0, "ACTIVE", "测试用")));

    UUID first = seedOutreach(influencerId, segment, template, "key-rate-1");
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.registerReceipt(
                first.toString(),
                new RegisterInfluencerOutreachReceiptRequest(
                    "MSG-A", "第一条", OffsetDateTime.now(java.time.ZoneOffset.UTC), "")));

    UUID second = seedInfluencer(shop, "小美二号", "美妆", 200_000L);
    UUID other = seedOutreach(second, segment, template, "key-rate-2");
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.registerReceipt(
                            other.toString(),
                            new RegisterInfluencerOutreachReceiptRequest(
                                "MSG-B", "第二条", OffsetDateTime.now(java.time.ZoneOffset.UTC), ""))))
        .hasMessageContaining("每日上限");

    // 🔴 被拒的那条**没有**变成已发送（状态不变）。
    var detail = as(actorA, "channel.operation.read", () -> service.outreach(other.toString()));
    assertThat(detail.outreach().status()).isEqualTo("DRAFT");
    assertThat(detail.outreach().sentAt()).isNull();
  }

  @Test
  @DisplayName("14.5.1.2 状态机：非法流转被拒，终态没有出边")
  void outreachStateMachineEnforced() {
    String shop = seedShop(a, "FSM-1");
    UUID influencerId = seedInfluencer(shop, "小美", "美妆", 200_000L);
    UUID segment = seedSegment("SEG-FSM", "美妆", "");
    UUID template = seedTemplate("TPL-FSM", "你好 {{display_name}}");
    UUID id = seedOutreach(influencerId, segment, template, "key-fsm");

    // 🔴 草稿不能凭空跳到「已完成」。
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.transitionOutreach(
                            id.toString(), new TransitionInfluencerOutreachRequest("COMPLETED", null, ""))))
        .hasMessageContaining("不允许的状态流转");

    // 🔴 草稿可以取消。
    var cancelled =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.transitionOutreach(
                    id.toString(), new TransitionInfluencerOutreachRequest("CANCELLED", null, "")));
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(cancelled.closedAt()).isNotNull();

    // 🔴 终态没有出边。
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.transitionOutreach(
                            id.toString(), new TransitionInfluencerOutreachRequest("REPLIED", null, ""))))
        .hasMessageContaining("不允许的状态流转");
  }

  @Test
  @DisplayName("14.5.1.2 批量建联：只生成草稿（不是批量群发）+ 重复批次幂等")
  void batchCreateOnlyDrafts() {
    String shop = seedShop(a, "BATCH-1");
    seedInfluencer(shop, "美妆一", "美妆", 200_000L);
    seedInfluencer(shop, "美妆二", "美妆", 200_000L);
    seedInfluencer(shop, "家居一", "家居", 200_000L);
    UUID segment = seedSegment("SEG-BATCH", "美妆", "");
    UUID template = seedTemplate("TPL-BATCH", "你好 {{display_name}}");

    var result =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.batchCreate(
                    new BatchInfluencerOutreachRequest(segment, template, "batch-1", 50, "")));
    assertThat(result.matchedCount()).isEqualTo(2);
    assertThat(result.createdCount()).isEqualTo(2);
    assertThat(result.items()).allMatch(InfluencerOutreachBatchItem::created);

    // 🔴 批量生成的**全是草稿**：没发出去。
    var page =
        as(
            actorA,
            "channel.operation.read",
            () ->
                service.outreachs(
                    null, segment, null, null, null, "DRAFT", null, "CREATED_DESC", 0, 50));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items()).allSatisfy(row -> assertThat(row.sentAt()).isNull());

    // 🔴 同一个批次键再跑一次 ⇒ 幂等（不重复建）。
    var again =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.batchCreate(
                    new BatchInfluencerOutreachRequest(segment, template, "batch-1", 50, "")));
    assertThat(again.createdCount()).isZero();
    assertThat(again.skippedCount()).isEqualTo(2);
    assertThat(again.items())
        .allSatisfy(item -> assertThat(item.result()).isEqualTo("SKIPPED_DUPLICATE"));
  }

  @Test
  @DisplayName("14.5.1.2 官方私信接口未接入：发送入口明确失败且不写任何数据")
  void officialApiNotIntegratedWritesNothing() {
    String shop = seedShop(a, "API-1");
    UUID influencerId = seedInfluencer(shop, "小美", "美妆", 200_000L);
    UUID segment = seedSegment("SEG-API", "美妆", "");
    UUID template = seedTemplate("TPL-API", "你好 {{display_name}}");
    UUID id = seedOutreach(influencerId, segment, template, "key-api");

    var result = as(actorA, "channel.operation.create", () -> service.sendViaPlatform(id.toString(), bearerA));
    assertThat(result.sent()).isFalse();
    assertThat(result.message()).contains("未接入");
    assertThat(result.platformMessageId()).isEmpty();

    // 🔴 **没有写任何数据**：状态仍是草稿、发送时刻仍是空。
    var detail = as(actorA, "channel.operation.read", () -> service.outreach(id.toString()));
    assertThat(detail.outreach().status()).isEqualTo("DRAFT");
    assertThat(detail.outreach().sentAt()).isNull();
    assertThat(detail.outreach().platformMessageId()).isEmpty();

    // 🔴 逐平台能力表如实标「未接入」。
    var availability = as(actorA, "channel.operation.read", () -> service.availability());
    assertThat(availability.platforms()).isNotEmpty();
    assertThat(availability.platforms())
        .allSatisfy(p -> assertThat(p.available()).isFalse());
  }

  @Test
  @DisplayName("14.5.1.2 合作跟进：追加型事实 + 下次跟进时刻写回值正确")
  void followupRecorded() {
    String shop = seedShop(a, "FU-1");
    UUID influencerId = seedInfluencer(shop, "小美", "美妆", 200_000L);
    UUID segment = seedSegment("SEG-FU", "美妆", "");
    UUID template = seedTemplate("TPL-FU", "你好 {{display_name}}");
    UUID id = seedOutreach(influencerId, segment, template, "key-fu");

    as(
        actorA,
        "channel.operation.create",
        () ->
            service.createFollowup(
                id.toString(),
                new CreateInfluencerOutreachFollowupRequest(
                    "CALL", "第一次电话沟通，达人表示有兴趣", FOLLOWUP_AT,
                    OffsetDateTime.parse("2026-10-09T10:00:00+08:00"), null, "")));

    var detail = as(actorA, "channel.operation.read", () -> service.outreach(id.toString()));
    assertThat(detail.followups()).hasSize(1);
    var followup = detail.followups().get(0);
    assertThat(followup.followupType()).isEqualTo("CALL");
    assertThat(followup.occurredAt().toInstant()).isEqualTo(FOLLOWUP_AT.toInstant());
    assertThat(followup.nextFollowupAt().toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-10-09T10:00:00+08:00").toInstant());
    assertThat(detail.outreach().followupCount()).isEqualTo(1);
  }

  // ==================================================================
  // 四、样品单与包裹
  // ==================================================================

  @Test
  @DisplayName("14.5.1.2 样品单 + 包裹：签收由包裹推导，且签收时刻写回值正确")
  void sampleSignOffDerivedFromShipments() {
    String shop = seedShop(a, "SMP-1");
    UUID influencerId = seedInfluencer(shop, "小美", "美妆", 200_000L);
    UUID segment = seedSegment("SEG-SMP", "美妆", "");
    UUID template = seedTemplate("TPL-SMP", "你好 {{display_name}}");
    UUID outreachId = seedOutreach(influencerId, segment, template, "key-smp");

    var sample =
        as(
                actorA,
                "channel.operation.create",
                () ->
                    service.createSampleOrder(
                        new SaveInfluencerSampleOrderRequest(
                            outreachId, "SKU-001", "某款口红", new BigDecimal("2"),
                            new BigDecimal("39.90"), "USD", "小美", "13800000000", "某市某区某街 1 号",
                            "")))
            .id();

    // 🔴 新建样品单是「已申请」，没有出库时刻。
    var fresh = as(actorA, "channel.operation.read", () -> service.sampleOrders(outreachId, null, null, null, null, null, "CREATED_DESC", 0, 50));
    assertThat(fresh.items().get(0).status()).isEqualTo("REQUESTED");
    assertThat(fresh.items().get(0).outboundAt()).isNull();

    // 🔴 🔴 10 物流未交付 ⇒ 轨迹不可查。
    assertThat(fresh.items().get(0).trackingAvailable()).isFalse();
    assertThat(fresh.items().get(0).trackingOwnerModule()).isEqualTo("10 物流");
    assertThat(fresh.items().get(0).warehouseOwnerModule()).isEqualTo("8 仓库");

    // 🔴 人手改样品单状态推不出「已签收」。
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.transitionSampleOrder(
                            sample.toString(),
                            new TransitionInfluencerSampleOrderRequest("SIGNED", null, ""))))
        .hasMessageContaining("由包裹推导");

    // 登记包裹 ⇒ 样品单状态推进到「已寄出」（本地事实）。
    var shipment =
        as(
                actorA,
                "channel.operation.create",
                () ->
                    service.createShipment(
                        sample.toString(),
                        new CreateInfluencerSampleShipmentRequest(
                            "SF", "TN-12345", BigDecimal.ONE, SENT_AT, "")))
            .id();
    var afterShip = as(actorA, "channel.operation.read", () -> service.sampleOrders(outreachId, null, null, null, null, null, "CREATED_DESC", 0, 50));
    assertThat(afterShip.items().get(0).status()).isEqualTo("OUTBOUNDED");
    assertThat(afterShip.items().get(0).statusDerived()).isFalse();
    assertThat(afterShip.items().get(0).outboundStatus()).isEqualTo("LOCAL_OUTBOUNDED");

    // 包裹送达 ⇒ 样品单 DELIVERED（**由包裹推导**）。
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.transitionShipment(
                shipment.toString(),
                new TransitionInfluencerSampleShipmentRequest("DELIVERED", SENT_AT, "", "")));
    var delivered = as(actorA, "channel.operation.read", () -> service.sampleOrders(outreachId, null, null, null, null, null, "CREATED_DESC", 0, 50));
    assertThat(delivered.items().get(0).status()).isEqualTo("DELIVERED");
    assertThat(delivered.items().get(0).statusDerived()).isTrue();

    // 包裹签收 ⇒ 样品单 SIGNED，且签收时刻写回值正确。
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.transitionShipment(
                shipment.toString(),
                new TransitionInfluencerSampleShipmentRequest("SIGNED", SIGNED_AT, "小美本人", "")));
    var signed = as(actorA, "channel.operation.read", () -> service.sampleOrders(outreachId, null, null, null, null, null, "CREATED_DESC", 0, 50));
    assertThat(signed.items().get(0).status()).isEqualTo("SIGNED");
    assertThat(signed.items().get(0).signedAt()).isNotNull();
    assertThat(signed.items().get(0).signedAt().toInstant()).isEqualTo(SIGNED_AT.toInstant());
    assertThat(signed.items().get(0).signedBy()).isEqualTo("小美本人");
    assertThat(signed.items().get(0).shipmentCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("14.5.1.2 样品成本：没录就是 null（不可比），不是 0")
  void sampleValueNullWhenNotRecorded() {
    String shop = seedShop(a, "SMP-2");
    UUID influencerId = seedInfluencer(shop, "小美", "美妆", 200_000L);
    UUID segment = seedSegment("SEG-SMP2", "美妆", "");
    UUID template = seedTemplate("TPL-SMP2", "你好 {{display_name}}");
    UUID outreachId = seedOutreach(influencerId, segment, template, "key-smp2");

    as(
        actorA,
        "channel.operation.create",
        () ->
            service.createSampleOrder(
                new SaveInfluencerSampleOrderRequest(
                    outreachId, "SKU-002", "某款面膜", BigDecimal.ONE, null, "", "", "", "", "")));

    var page =
        as(
            actorA,
            "channel.operation.read",
            () -> service.sampleOrders(outreachId, null, null, null, null, null, "CREATED_DESC", 0, 50));
    assertThat(page.items().get(0).sampleValue()).as("没录就是 null（不可比）").isNull();
    assertThat(page.items().get(0).quantity()).isEqualByComparingTo("1");
  }

  // ==================================================================
  // 五、佣金
  // ==================================================================

  @Test
  @DisplayName("14.5.1.2 佣金计提：金额按规则算出来 + 未进财务（finance_posted 恒为 false）")
  void commissionAccrualComputedAndNotPosted() {
    String shop = seedShop(a, "COM-1");
    UUID influencerId = seedInfluencer(shop, "小美", "美妆", 200_000L);
    UUID segment = seedSegment("SEG-COM", "美妆", "");
    UUID template = seedTemplate("TPL-COM", "你好 {{display_name}}");
    UUID outreachId = seedOutreach(influencerId, segment, template, "key-com");

    UUID rule =
        as(
                actorA,
                "channel.operation.create",
                () ->
                    service.saveCommissionRule(
                        new SaveInfluencerCommissionRuleRequest(
                            "RULE-10", "美妆 10%", "GLOBAL", null, null, "RATE",
                            new BigDecimal("0.1"), null, "USD", null, null, "ACTIVE", "")))
            .id();

    var accrual =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.createAccrual(
                    new CreateInfluencerCommissionAccrualRequest(
                        outreachId, rule, new BigDecimal("1000"), "USD", "测试计提")));

    assertThat(accrual.accruedAmount()).isEqualByComparingTo("100.0000");
    assertThat(accrual.commissionRate()).isEqualByComparingTo("0.1");
    assertThat(accrual.baseAmount()).isEqualByComparingTo("1000");
    assertThat(accrual.accruedCurrency()).isEqualTo("USD");
    // 🔴 12 财务未交付 ⇒ 未进费用归集。
    assertThat(accrual.financePosted()).isFalse();
    assertThat(accrual.financeOwnerModule()).isEqualTo("12 财务");

    // 🔴 计提行是**事实**：规则后来改了，已计提的这行不动。
    as(
        actorA,
        "channel.operation.update",
        () ->
            service.updateCommissionRule(
                rule.toString(),
                new SaveInfluencerCommissionRuleRequest(
                    "RULE-20", "美妆 20%", "GLOBAL", null, null, "RATE",
                    new BigDecimal("0.2"), null, "USD", null, null, "ACTIVE", "")));
    var page =
        as(
            actorA,
            "channel.operation.read",
            () -> service.accruals(outreachId, null, null, "ACCRUED_DESC", 0, 50));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).accruedAmount()).as("已计提的金额不跟着规则变").isEqualByComparingTo("100.0000");
    assertThat(page.items().get(0).commissionRate()).isEqualByComparingTo("0.1");
  }

  @Test
  @DisplayName("14.5.1.2 佣金规则：形态与取值必须自洽（混合形态被拒）")
  void commissionRuleShapeEnforced() {
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.saveCommissionRule(
                            new SaveInfluencerCommissionRuleRequest(
                                "RULE-BAD", "混合形态", "GLOBAL", null, null, "RATE",
                                new BigDecimal("0.1"), new BigDecimal("20"), "USD", null, null,
                                "ACTIVE", ""))))
        .hasMessageContaining("混合形态");
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.saveCommissionRule(
                            new SaveInfluencerCommissionRuleRequest(
                                "RULE-BAD2", "超范围比例", "GLOBAL", null, null, "RATE",
                                new BigDecimal("1.5"), null, "USD", null, null, "ACTIVE", ""))))
        .hasMessageContaining("佣金比例必须在");
  }

  // ==================================================================
  // 六、DB 约束（风控红线在存储层）
  // ==================================================================

  @Test
  @DisplayName("14.5.1.2 DB 约束：沟通渠道只能是官方接口、轨迹不可查、未入财务")
  void databaseGuardsTheRedLines() {
    String shop = seedShop(a, "DB-1");
    UUID influencerId = seedInfluencer(shop, "小美", "美妆", 200_000L);
    UUID segment = seedSegment("SEG-DB", "美妆", "");
    UUID template = seedTemplate("TPL-DB", "你好 {{display_name}}");
    UUID outreachId = seedOutreach(influencerId, segment, template, "key-db");

    // 🔴 第三方群发通道写不进去。
    assertThat(execFails("update influencer_outreach set contact_channel = 'THIRD_PARTY_TOOL'"
            + " where id = '" + outreachId + "'"))
        .as("contact_channel 被 DB CHECK 钉死为 OFFICIAL_API")
        .isTrue();

    var sample =
        as(
                actorA,
                "channel.operation.create",
                () ->
                    service.createSampleOrder(
                        new SaveInfluencerSampleOrderRequest(
                            outreachId, "SKU-003", "某款眼影", BigDecimal.ONE, null, "", "", "", "",
                            "")))
            .id();
    // 🔴 「物流已接通」写不进去。
    assertThat(execFails("update influencer_sample_order set tracking_available = true where id = '"
            + sample + "'"))
        .as("tracking_available 恒为 false")
        .isTrue();

    UUID rule =
        as(
                actorA,
                "channel.operation.create",
                () ->
                    service.saveCommissionRule(
                        new SaveInfluencerCommissionRuleRequest(
                            "RULE-DB", "全局 10%", "GLOBAL", null, null, "RATE",
                            new BigDecimal("0.1"), null, "USD", null, null, "ACTIVE", "")))
            .id();
    UUID accrual =
        as(
                actorA,
                "channel.operation.create",
                () ->
                    service.createAccrual(
                        new CreateInfluencerCommissionAccrualRequest(
                            outreachId, rule, new BigDecimal("100"), "USD", "")))
            .id();
    // 🔴 「已入财务」写不进去（12 财务不存在）。
    assertThat(execFails("update influencer_commission_accrual set finance_posted = true where id = '"
            + accrual + "'"))
        .as("finance_posted 恒为 false")
        .isTrue();

    // 🔴 沟通渠道与「已发送却没有发送时刻」也都被 DB 挡住。
    assertThat(execFails("update influencer_outreach set status = 'SENT' where id = '"
            + outreachId + "'"))
        .as("没有发送时刻就不许是 SENT")
        .isTrue();
  }

  // ==================================================================
  // 七、能力声明 / 列表 / HTTP
  // ==================================================================

  @Test
  @DisplayName("14.5.1.2 能力声明：四件做不到的事如实标 available=false + ownerModule")
  void capabilitiesHonestAboutGaps() {
    var capabilities = as(actorA, "channel.operation.read", () -> service.capabilities());
    var byDimension =
        capabilities.dimensions().stream()
            .collect(java.util.stream.Collectors.toMap(d -> d.dimension(), d -> d));

    for (String key :
        List.of("official_outreach_api", "warehouse_outbound", "logistics_tracking", "finance_commission")) {
      assertThat(byDimension.get(key)).as(key + " 必须在场").isNotNull();
      assertThat(byDimension.get(key).available()).as(key + " 必须标未交付").isFalse();
      assertThat(byDimension.get(key).ownerModule()).as(key + " 必须说清归谁").isNotBlank();
      assertThat(byDimension.get(key).impact()).as(key + " 必须说清不交付的影响").isNotBlank();
    }
    assertThat(byDimension.get("official_outreach_api").ownerModule()).contains("channel/vendor");
    assertThat(byDimension.get("warehouse_outbound").ownerModule()).contains("8 仓库");
    assertThat(byDimension.get("logistics_tracking").ownerModule()).contains("10 物流");
    assertThat(byDimension.get("finance_commission").ownerModule()).contains("12 财务");

    // 已交付的维度标 true。
    for (String key :
        List.of(
            "influencer_segment", "outreach_template", "outreach_record", "sample_management",
            "followup", "commission_setting")) {
      assertThat(byDimension.get(key).available()).as(key + " 已交付").isTrue();
    }
  }

  @Test
  @DisplayName("14.5.1.2 建联列表：真分页 + 真筛选")
  void outreachListRealPagingAndFiltering() {
    String shop = seedShop(a, "LIST-1");
    UUID segment = seedSegment("SEG-LIST", "美妆", "");
    UUID template = seedTemplate("TPL-LIST", "你好 {{display_name}}");
    for (int i = 0; i < 5; i++) {
      UUID influencerId = seedInfluencer(shop, "达人" + i, "美妆", 200_000L);
      seedOutreach(influencerId, segment, template, "key-list-" + i);
    }

    var page =
        as(
            actorA,
            "channel.operation.read",
            () -> service.outreachs(null, segment, null, null, null, "DRAFT", null, "CREATED_DESC", 0, 50));
    assertThat(page.total()).isEqualTo(5);
    assertThat(page.items()).hasSize(5);

    var firstPage =
        as(
            actorA,
            "channel.operation.read",
            () -> service.outreachs(null, segment, null, null, null, "DRAFT", null, "CREATED_DESC", 0, 2));
    assertThat(firstPage.items()).hasSize(2);
    assertThat(firstPage.total()).isEqualTo(5);

    var secondPage =
        as(
            actorA,
            "channel.operation.read",
            () -> service.outreachs(null, segment, null, null, null, "DRAFT", null, "CREATED_DESC", 1, 2));
    assertThat(secondPage.items()).hasSize(2);
    assertThat(secondPage.items())
        .as("第二页不能与第一页重复")
        .doesNotContainAnyElementsOf(firstPage.items());

    var thirdPage =
        as(
            actorA,
            "channel.operation.read",
            () -> service.outreachs(null, segment, null, null, null, "DRAFT", null, "CREATED_DESC", 2, 2));
    assertThat(thirdPage.items()).as("5 条 / 每页 2 条 ⇒ 第三页只剩 1 条").hasSize(1);

    // 🔴 状态筛选真的生效（没有已发送的记录）。
    var sent =
        as(
            actorA,
            "channel.operation.read",
            () -> service.outreachs(null, segment, null, null, null, "SENT", null, "CREATED_DESC", 0, 50));
    assertThat(sent.total()).isZero();
  }

  @Test
  @DisplayName("14.5.1.2 概览：本地计提金额只在单一币种时汇总")
  void summaryAggregatesSingleCurrencyOnly() {
    String shop = seedShop(a, "SUM-1");
    UUID influencerId = seedInfluencer(shop, "小美", "美妆", 200_000L);
    UUID segment = seedSegment("SEG-SUM", "美妆", "");
    UUID template = seedTemplate("TPL-SUM", "你好 {{display_name}}");
    UUID outreachId = seedOutreach(influencerId, segment, template, "key-sum");

    UUID rule =
        as(
                actorA,
                "channel.operation.create",
                () ->
                    service.saveCommissionRule(
                        new SaveInfluencerCommissionRuleRequest(
                            "RULE-SUM", "全局 10%", "GLOBAL", null, null, "RATE",
                            new BigDecimal("0.1"), null, "USD", null, null, "ACTIVE", "")))
            .id();
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.createAccrual(
                new CreateInfluencerCommissionAccrualRequest(
                    outreachId, rule, new BigDecimal("1000"), "USD", "")));

    var summary = as(actorA, "channel.operation.read", () -> service.summary());
    assertThat(summary.totalOutreachs()).isEqualTo(1);
    assertThat(summary.draftCount()).isEqualTo(1);
    assertThat(summary.accrualCount()).isEqualTo(1);
    assertThat(summary.accruedAmountTotal()).isEqualByComparingTo("100.0000");
    assertThat(summary.accruedCurrency()).isEqualTo("USD");
    assertThat(summary.downstreamNote()).contains("12 财务");
  }

  @Test
  @DisplayName("14.5.1.2 HTTP 端点：未认证 401，认证后可读")
  void httpEndpointsRequireAuth() throws Exception {
    http.perform(get("/channel/outreach/capabilities")).andExpect(status().isUnauthorized());
    http.perform(get("/channel/outreach/capabilities").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions.length()").value(10));
    http.perform(get("/channel/outreach/options").header("Authorization", bearerA))
        .andExpect(status().isOk());
    http.perform(get("/channel/outreach/summary").header("Authorization", bearerA))
        .andExpect(status().isOk());
    http.perform(get("/channel/outreach/outreachs").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
    http.perform(get("/channel/outreach/segments").header("Authorization", bearerA))
        .andExpect(status().isOk());
    http.perform(get("/channel/outreach/templates").header("Authorization", bearerA))
        .andExpect(status().isOk());
    http.perform(get("/channel/outreach/samples").header("Authorization", bearerA))
        .andExpect(status().isOk());
    http.perform(get("/channel/outreach/commission-rules").header("Authorization", bearerA))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("14.5.1.2 权限：只有读权限时写端点 403")
  void writeRequiresWritePermission() throws Exception {
    String readOnly = "Bearer t-ro-" + a;
    actors.put(
        readOnly,
        actor(
            user,
            a,
            List.of(grant("ALL", List.of("channel.operation.read", "channel.operation.read")))));
    http.perform(
            post("/channel/outreach/segments")
                .header("Authorization", readOnly)
                .contentType("application/json")
                .content("{\"segmentCode\":\"X\",\"segmentName\":\"X\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("14.5.1.2 建联记录：已发出的私信不许物理删除，也不许改口")
  void sentOutreachIsImmutableEvidence() {
    String shop = seedShop(a, "IMM-1");
    UUID influencerId = seedInfluencer(shop, "小美", "美妆", 200_000L);
    UUID segment = seedSegment("SEG-IMM", "美妆", "");
    UUID template = seedTemplate("TPL-IMM", "你好 {{display_name}}");
    UUID id = seedOutreach(influencerId, segment, template, "key-imm");

    as(
        actorA,
        "channel.operation.update",
        () ->
            service.registerReceipt(
                id.toString(),
                new RegisterInfluencerOutreachReceiptRequest("MSG-IMM", "已发出", SENT_AT, "")));

    // 🔴 已发出的私信是举证凭据，不许物理删除。
    assertThatThrownBy(
            () ->
                asVoid(
                    actorA,
                    "channel.operation.delete",
                    () -> service.deleteOutreach(id.toString())))
        .hasMessageContaining("举证凭据");

    // 🔴 也不许改口（改模板不改历史）。
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.update",
                    () ->
                        service.updateOutreach(
                            id.toString(),
                            new SaveInfluencerOutreachRequest(
                                influencerId, segment, template, "key-imm-2", "改口"))))
        .hasMessageContaining("不可改口");
  }
}
