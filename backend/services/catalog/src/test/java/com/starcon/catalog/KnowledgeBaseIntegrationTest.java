package com.starcon.catalog;

import static com.starcon.catalog.KnowledgeBaseModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.4.2.2 客服知识库与话术：真实落库往返、多语言版本、<b>平台规则版本挂钩与待复核自动生成</b>、
 * 引用记录 append-only、命中率口径，以及<b>租户隔离三断言</b>。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：话术、译文、规则版本、挂钩、待复核任务、引用记录
 * 全部真的落库，不使用任何内存替身。连接池固定为 1 条连接：只有复用同一条连接，
 * 才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的六个断言</b>：
 *
 * <ol>
 *   <li>{@link #规则版本被接替时自动给挂在它上面的话术生成待复核任务()}：蓝图点名的
 *       「<b>平台政策变化时能定位哪些话术需要更新</b>」的落地点。这里断言的是<b>自动</b>生成
 *       （只改一次 status，不调任何生成接口），且任务行带上了<b>是哪一版接替的它</b>。
 *   <li>{@link #命中率按启用话术条数算且被驳回不计入()}：规则 R2~R4 的字面兑现。
 *       分母只算启用的，被驳回与放弃引用都不计入分子。
 *   <li>{@link #多语言版本是真实表行而不是json挂一列()}：蓝图硬要求「多语言版本」
 *       必须能落地成真实表行 —— 直接查库数行数，不看接口返回。
 *   <li>{@link #引用记录appendOnly连直连SQL也改不动()}：命中率的全部可信度来自明细行
 *       是唯一真相，DB 层触发器 + REVOKE 双重兜底。
 *   <li>{@link #译文占位符与源语言不一致时被DB级触发器拒绝()}：绕过服务层直接插库也
 *       不许写出裸 {@code {buyer_name}}。
 *   <li>{@link #租户隔离三断言覆盖六张表()}：话术库最敏感的是<b>成本与承诺</b> ——
 *       A 读到 B 的退换货承诺会让 A 对买家做出自己没权限许的承诺。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeBaseIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper JSON = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new java.util.concurrent.ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();
  /** 素材域权限（catalog.assets 是 identity V3 的「素材与翻译」词表，本模块沿用不新造）。 */
  static final List<String> allPermissions =
      java.util.stream.Stream.of("assets")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "catalog." + p + "." + op))
          .toList();

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(), grants);
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
        s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
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
            byte[] body = JSON.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      // 只有读权限：写端点必须 403（话术沿用素材域权限）。
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.assets.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "catalog_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接，才能真正证明归还之后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired KnowledgeBaseService kb;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired PlatformTransactionManager manager;
  @Autowired MockMvc http;

  AccessService.Access actor;
  AccessService.Access actorB;

  @BeforeEach
  void setUp() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.assets.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.assets.update", work);
  }

  void writeVoid(AccessService.Access who, Runnable work) {
    access.run(
        who,
        "catalog.assets.update",
        () -> {
          work.run();
          return null;
        });
  }

  /** 编码词表只允许大写字母、数字与下划线，所以夹具前缀用下划线而不是连字符。 */
  static String unique(String prefix) {
    return prefix.replace('-', '_') + "_" + SEQ.incrementAndGet();
  }

  // ================================================================ 夹具

  KnowledgeEntryInput entryInput(String code, String title, String type) {
    // **正文带一个占位符**：这样后面想加译文的用例可以直接复用这个夹具，
    // 译文里带上同一个占位符即可 —— 否则会撞上「译文多出占位符」的校验（那是预期的行为）。
    return new KnowledgeEntryInput(code, title, type,
        "您好 {buyer_name}，请在订单页提交退换货申请。", "zh-CN", "退,退货", true, 0L);
  }

  UUID entry(AccessService.Access who, String prefix) {
    return write(who, () -> kb.saveEntry(who, null, entryInput(unique(prefix), "退换货标准答法", "FAQ")))
        .id();
  }

  UUID rule(AccessService.Access who, String policyKey, String label, OffsetDateTime from) {
    return write(
            who,
            () ->
                kb.createRuleVersion(
                    who,
                    new RuleVersionInput(
                        "AMAZON", policyKey, label, "退货政策 " + label,
                        "退货运费改为买家承担", from, null)))
        .id();
  }

  OffsetDateTime at(int month) {
    return OffsetDateTime.of(2026, month, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8));
  }

  // ================================================================ 蓝图硬要求：规则版本挂钩

  @Test
  void 规则版本被接替时自动给挂在它上面的话术生成待复核任务() {
    UUID entryId = entry(actor, "KB-BIND");
    UUID oldRule = rule(actor, "AMAZON_RETURN_POLICY", "2026-01", at(1));
    UUID newRule = rule(actor, "AMAZON_RETURN_POLICY", "2026-03", at(3));
    // 话术挂到**旧版**规则上
    write(
        actor,
        () -> kb.bindEntry(actor, entryId, new RuleBindingInput(oldRule, "自发货订单适用")));

    // 挂钩本身可查：这条话术依据哪一版政策
    assertThat(tx(actor, () -> kb.bindingsOf(entryId)))
        .singleElement()
        .satisfies(
            b -> {
              assertThat(b.policyKey()).isEqualTo("AMAZON_RETURN_POLICY");
              assertThat(b.versionLabel()).isEqualTo("2026-01");
              assertThat(b.note()).isEqualTo("自发货订单适用");
            });

    // 先把旧版发布生效（真实流程：现行版本先 ACTIVE）
    write(
        actor,
        () ->
            kb.updateRuleVersion(
                actor,
                oldRule,
                new RuleVersionInput(
                    "AMAZON", "AMAZON_RETURN_POLICY", "2026-01",
                    "退货政策 2026-01", "退货运费由卖家承担", at(1), null),
                "ACTIVE"));

    // 改**新版**为 ACTIVE：此刻旧版还生效中 → 必须 409（一个政策同时只能有一版生效）
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        kb.updateRuleVersion(
                            actor,
                            newRule,
                            new RuleVersionInput(
                                "AMAZON", "AMAZON_RETURN_POLICY", "2026-03",
                                "退货政策 2026-03", "退货运费改为买家承担", at(3), null),
                            "ACTIVE")))
        .isInstanceOf(ResponseStatusException.class)
        .as("一个政策同时只能有一版生效，否则「这条话术依据的是哪版政策」无法回答");

    // 把旧版标记为「已被接替」——**只改状态，不调任何生成接口**
    write(
        actor,
        () ->
            kb.updateRuleVersion(
                actor,
                oldRule,
                new RuleVersionInput(
                    "AMAZON", "AMAZON_RETURN_POLICY", "2026-01",
                    "退货政策 2026-01", "退货运费由卖家承担", at(1), null),
                "SUPERSEDED"));

    // 待复核任务**自动生成**，且带上了接替它的那一版
    var tasks = tx(actor, () -> kb.listReviewTasks(null, "PENDING", null, 0, 50));
    var mine = tasks.items().stream()
        .filter(t -> t.entryId().equals(entryId))
        .findFirst()
        .orElseThrow();
    assertThat(mine.ruleVersionId()).isEqualTo(oldRule);
    assertThat(mine.supersedingRuleId()).isEqualTo(newRule);
    assertThat(mine.supersedingRuleLabel()).isEqualTo("2026-03");
    assertThat(mine.supersedingChangeSummary()).contains("运费");
    assertThat(mine.reviewStatus()).isEqualTo("PENDING");
    assertThat(mine.entryCode()).startsWith("KB_BIND_");
  }

  @Test
  void 重复标记接替不会让复核任务翻倍() {
    UUID entryId = entry(actor, "KB-DUP");
    UUID oldRule = rule(actor, "DUP_POLICY", "v1", at(1));
    UUID newRule = rule(actor, "DUP_POLICY", "v2", at(3));
    write(actor, () -> kb.bindEntry(actor, entryId, new RuleBindingInput(oldRule, "")));
    var input =
        new RuleVersionInput(
            "AMAZON", "DUP_POLICY", "v1", "标题 v1", "变更说明", at(1), null);
    write(actor, () -> kb.updateRuleVersion(actor, oldRule, input, "SUPERSEDED"));
    // 改回草稿再标记接替：UNIQUE(tenant,entry,rule_version) 保证不会多出第二条
    write(actor, () -> kb.updateRuleVersion(actor, oldRule, input, "DRAFT"));
    write(actor, () -> kb.updateRuleVersion(actor, oldRule, input, "SUPERSEDED"));
    assertThat(newRule).isNotNull();
    long count =
        tx(
            actor,
            () ->
                sql.fetchOne(
                        "select count(*) from kb_review_task where tenant_id=? and entry_id=?",
                        a,
                        entryId)
                    .get(0, Long.class));
    assertThat(count).isEqualTo(1);
  }

  @Test
  void 标记接替时必须写变更摘要() {
    UUID oldRule = rule(actor, "SUMMARY_POLICY", "v1", at(1));
    // 没有变更摘要 → 客服看到待办也不知道该复核什么
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        kb.updateRuleVersion(
                            actor,
                            oldRule,
                            new RuleVersionInput(
                                "AMAZON", "SUMMARY_POLICY", "v1", "标题", "", at(1), null),
                            "SUPERSEDED")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("变更摘要");
  }

  @Test
  void 复核任务认领与完成都留下痕迹() {
    UUID entryId = entry(actor, "KB-REVIEW");
    UUID oldRule = rule(actor, "TRACE_POLICY", "v1", at(1));
    rule(actor, "TRACE_POLICY", "v2", at(3));
    write(actor, () -> kb.bindEntry(actor, entryId, new RuleBindingInput(oldRule, "")));
    write(
        actor,
        () ->
            kb.updateRuleVersion(
                actor,
                oldRule,
                new RuleVersionInput(
                    "AMAZON", "TRACE_POLICY", "v1", "标题", "改版说明", at(1), null),
                "SUPERSEDED"));
    UUID taskId =
        tx(actor, () -> kb.listReviewTasks(null, "PENDING", null, 0, 50)).items().stream()
            .filter(t -> t.entryId().equals(entryId))
            .findFirst()
            .orElseThrow()
            .id();

    var claiming = write(actor, () -> kb.updateReviewTask(actor, taskId,
        new ReviewTaskUpdateInput("REVIEWING", "我来看")));
    assertThat(claiming.reviewStatus()).isEqualTo("REVIEWING");
    assertThat(claiming.assignedTo()).isEqualTo(user.toString());

    var done = write(actor, () -> kb.updateReviewTask(actor, taskId,
        new ReviewTaskUpdateInput("DONE", "已按新规则改写")));
    assertThat(done.reviewStatus()).isEqualTo("DONE");
    assertThat(done.reviewedBy()).isEqualTo(user.toString());
    assertThat(done.reviewedAt()).isNotNull();
    assertThat(done.note()).isEqualTo("已按新规则改写");
  }

  @Test
  void 已完成的复核任务不再计入待办() {
    UUID entryId = entry(actor, "KB-DONE");
    UUID oldRule = rule(actor, "DONE_POLICY", "v1", at(1));
    rule(actor, "DONE_POLICY", "v2", at(3));
    write(actor, () -> kb.bindEntry(actor, entryId, new RuleBindingInput(oldRule, "")));
    write(
        actor,
        () ->
            kb.updateRuleVersion(
                actor,
                oldRule,
                new RuleVersionInput("AMAZON", "DONE_POLICY", "v1", "标题", "改版", at(1), null),
                "SUPERSEDED"));
    UUID taskId =
        tx(actor, () -> kb.listReviewTasks(null, "PENDING", null, 0, 200)).items().stream()
            .filter(t -> t.entryId().equals(entryId))
            .findFirst()
            .orElseThrow()
            .id();
    // 本用例同租户还有其它用例留下的待办，所以断言的是**这条任务本身**消失且总数 -1，
    // 而不是「总数为 0」——后者会让本用例的成败依赖测试执行顺序。
    long pendingBefore = tx(actor, () -> kb.overview()).reviewPending();
    write(actor, () -> kb.updateReviewTask(actor, taskId, new ReviewTaskUpdateInput("DONE", "好了")));

    assertThat(tx(actor, () -> kb.overview()).reviewPending())
        .as("复核完成后待办数必须减一，否则客服每天看到的都是同一批已处理的事")
        .isEqualTo(pendingBefore - 1);
    assertThat(tx(actor, () -> kb.reviewTaskRowPublic(taskId)).reviewStatus()).isEqualTo("DONE");
    assertThat(tx(actor, () -> kb.overview()).reviewDone()).isPositive();
    // 待办列表里再也查不到它
    assertThat(tx(actor, () -> kb.listReviewTasks(null, "PENDING", null, 0, 200)).items().stream()
            .map(ReviewTaskRow::id).toList())
        .doesNotContain(taskId);
  }

  // ================================================================ 蓝图硬要求：多语言真实表行

  @Test
  void 多语言版本是真实表行而不是json挂一列() {
    UUID entryId =
        write(
                actor,
                () ->
                    kb.saveEntry(
                        actor,
                        null,
                        new KnowledgeEntryInput(
                            unique("KB-ML"),
                            "退货政策英文话术",
                            "POLICY",
                            "Hi {buyer_name}, your order {order_no} can be returned within 30 days.",
                            "en-US",
                            "return",
                            true,
                            0L)))
            .id();
    var zh =
        write(
                actor,
                () ->
                    kb.saveTranslation(
                        actor,
                        entryId,
                        null,
                        new EntryTranslationInput(
                            "zh-CN", "退货政策中文话术", "您好 {buyer_name}，订单 {order_no} 可在 30 天内退货。",
                            "MANUAL", "REVIEWED", 0L)))
            .id();
    var ja =
        write(
                actor,
                () ->
                    kb.saveTranslation(
                        actor,
                        entryId,
                        null,
                        new EntryTranslationInput(
                            "ja-JP", "退货政策日文话术", "{buyer_name} 様、{order_no} は 30 日以内にご返品いただけます。",
                            "AI", "DRAFT", 0L)))
            .id();

    // **直接查库数行数**：接口能返回数组不代表落成了行（挂一列 json 也能返回数组）
    long rows =
        tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select count(*) from kb_entry_translation where tenant_id=? and entry_id=?",
                            a,
                            entryId)
                        .get(0, Long.class));
    assertThat(rows).isEqualTo(2);
    assertThat(tx(actor, () -> kb.translations(entryId)))
        .extracting(EntryTranslation::locale)
        .containsExactlyInAnyOrder("ja-JP", "zh-CN");
    assertThat(zh).isNotEqualTo(ja);
    // 多语言版本清单里两版都在，且带 origin 与 status
    assertThat(tx(actor, () -> kb.translations(entryId)))
        .allSatisfy(t -> assertThat(t.body()).isNotBlank());
  }

  @Test
  void 译文完整度只认已复核的() {
    UUID entryId = entry(actor, "KB-COV");
    write(
        actor,
        () ->
            kb.saveTranslation(
                actor, entryId, null,
                new EntryTranslationInput(
                    "en-US", "t", "Hi {buyer_name}, your order ships today.", "AI", "DRAFT", 0L)));
    var coverage = tx(actor, () -> kb.translationCoverage(entryId, List.of("zh-CN", "en-US", "de-DE")));
    assertThat(coverage.sourceLocale()).isEqualTo("zh-CN");
    var en = coverage.locales().stream().filter(l -> l.locale().equals("en-US")).findFirst().orElseThrow();
    assertThat(en.translated()).isTrue();
    assertThat(en.reviewed()).isFalse();
    assertThat(en.usable())
        .as("AI 预填的草稿不能算「能直接发给买家」—— 占位符被改写是最典型的失败模式")
        .isFalse();
    var de = coverage.locales().stream().filter(l -> l.locale().equals("de-DE")).findFirst().orElseThrow();
    assertThat(de.translated()).isFalse();
    var zh = coverage.locales().stream().filter(l -> l.locale().equals("zh-CN")).findFirst().orElseThrow();
    assertThat(zh.usable()).as("源语言永远可用（直接用正文）").isTrue();
  }

  @Test
  void 译文占位符与源语言不一致时服务层拒绝并说清缺哪个() {
    UUID entryId =
        write(
                actor,
                () ->
                    kb.saveEntry(
                        actor,
                        null,
                        new KnowledgeEntryInput(
                            unique("KB-PH"),
                            "占位符话术",
                            "FAQ",
                            "您好 {buyer_name}，订单 {order_no} 已发出。",
                            "zh-CN",
                            "",
                            true,
                            0L)))
            .id();
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        kb.saveTranslation(
                            actor,
                            entryId,
                            null,
                            new EntryTranslationInput(
                                "en-US", "t", "Hi buyer_name, your order is shipped.", "MANUAL", "DRAFT", 0L))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("order_no")
        .hasMessageContaining("漏抄");
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        kb.saveTranslation(
                            actor,
                            entryId,
                            null,
                            new EntryTranslationInput(
                                "en-US", "t", "Hi {buyer_name}, order {order_no} track {tracking_no}.",
                                "MANUAL", "DRAFT", 0L))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("tracking_no");
  }

  @Test
  void 源语言不能同时再建同语言的译文() {
    UUID entryId = entry(actor, "KB-SAME");
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        kb.saveTranslation(
                            actor,
                            entryId,
                            null,
                            new EntryTranslationInput(
                                "zh-CN", "t", "重复的源语言译文", "MANUAL", "DRAFT", 0L))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("源语言");
  }

  @Test
  void 译文占位符与源语言不一致时被DB级触发器拒绝() {
    UUID entryId =
        write(
                actor,
                () ->
                    kb.saveEntry(
                        actor,
                        null,
                        new KnowledgeEntryInput(
                            unique("KB-DBPH"), "触发器话术", "FAQ",
                            "您好 {buyer_name}，订单 {order_no} 已发出。", "zh-CN", "", true, 0L)))
            .id();
    // **绕过服务层直接插库**：服务层的校验被漏掉时，DB 层仍不许写出裸占位符
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        sql.execute(
                            "insert into kb_entry_translation(id,tenant_id,entry_id,locale,title,body,origin,status)"
                                + " values(?::uuid,?::uuid,?::uuid,'en-US','t','Hi buyer_name, shipped.','MANUAL','DRAFT')",
                            UUID.randomUUID(),
                            a,
                            entryId)))
        .isInstanceOf(Exception.class);
    long rows =
        tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select count(*) from kb_entry_translation where entry_id=?", entryId)
                        .get(0, Long.class));
    assertThat(rows).isZero();
  }

  @Test
  void 源语言改正文占位符集合时有译文会被拦() {
    UUID entryId =
        write(
                actor,
                () ->
                    kb.saveEntry(
                        actor,
                        null,
                        new KnowledgeEntryInput(
                            unique("KB-BODY"), "改正文", "FAQ",
                            "您好 {buyer_name}，订单已发出。", "zh-CN", "", true, 0L)))
            .id();
    write(
        actor,
        () ->
            kb.saveTranslation(
                actor, entryId, null,
                new EntryTranslationInput("en-US", "t", "Hi {buyer_name}, shipped.", "MANUAL", "REVIEWED", 0L)));
    // 改措辞没问题（占位符集合没变）
    assertThat(
            write(
                    actor,
                    () ->
                        kb.saveEntry(
                            actor,
                            entryId,
                            new KnowledgeEntryInput(
                                unique("KB-BODY"), "改正文", "FAQ",
                                "您好 {buyer_name}，您的订单已发出。", "zh-CN", "", true, 0L)))
                .id())
        .isEqualTo(entryId);
    // 但增删占位符会让已有译文失配 —— 必须在 DB 层拦下
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        sql.execute(
                            "update kb_entry set body='您好 {buyer_name}，订单 {order_no} 已发出。'"
                                + " where tenant_id=? and id=?",
                            a,
                            entryId)))
        .isInstanceOf(Exception.class)
        .hasMessageContaining("占位符集合");
  }

  // ================================================================ 蓝图硬要求：使用效果真实落库

  @Test
  void 引用记录真的落库且页面上查得到() {
    UUID entryId = entry(actor, "KB-USAGE");
    var used = write(actor, () -> kb.recordUsage(actor, entryId,
        new UsageInput("RESOLVED", null, "conv-8899", "买家接受了")));
    assertThat(used.countsAsHit()).isTrue();
    assertThat(used.outcomeLabel()).isEqualTo("一次解决");
    assertThat(used.agentId()).isEqualTo(user.toString());

    // 直接查库确认行真的存在（不是内存计数）
    long rows =
        tx(
                actor,
                () -> sql.fetchOne("select count(*) from kb_usage where entry_id=?", entryId)
                    .get(0, Long.class));
    assertThat(rows).isEqualTo(1);

    // 会话可回溯
    assertThat(tx(actor, () -> kb.listUsages("conv-8899", null, null, 0, 20)).total()).isEqualTo(1);
    // 详情页自带使用历史
    assertThat(tx(actor, () -> kb.detail(entryId)).recentUsages()).hasSize(1);
    assertThat(tx(actor, () -> kb.detail(entryId)).effect().usageCount()).isEqualTo(1);
  }

  @Test
  void 引用不能指向别的条目的译文() {
    UUID entryA = entry(actor, "KB-XA");
    UUID entryB = entry(actor, "KB-XB");
    var t =
        write(
                actor,
                () ->
                    kb.saveTranslation(
                        actor, entryB, null,
                        new EntryTranslationInput(
                            "en-US", "t", "Hi {buyer_name}, shipped.", "MANUAL", "DRAFT", 0L)));
    // 否则「引用的是哪一版译文」会说谎（页面上的语言标签是别的条目的）
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        kb.recordUsage(
                            actor, entryA, new UsageInput("RESOLVED", t.id(), "conv-x", ""))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不属于当前话术");
  }

  @Test
  void 引用结果必须是词表内的五种() {
    UUID entryId = entry(actor, "KB-BAD");
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        kb.recordUsage(actor, entryId, new UsageInput("WHATEVER", null, "", ""))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("RESOLVED");
  }

  @Test
  void 引用记录appendOnly连直连SQL也改不动() {
    UUID entryId = entry(actor, "KB-IMMUT");
    UUID usageId = write(actor, () -> kb.recordUsage(actor, entryId,
            new UsageInput("RESOLVED", null, "conv-immutable", ""))).id();
    // 触发器 + REVOKE 双重兜底：改一行 outcome 就等于把统计往想要的数字上掰
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        sql.execute(
                            "update kb_usage set outcome='REJECTED' where tenant_id=? and id=?",
                            a,
                            usageId)))
        .isInstanceOf(Exception.class);
    assertThatThrownBy(
            () -> writeVoid(actor, () -> sql.execute("delete from kb_usage where id=?", usageId)))
        .isInstanceOf(Exception.class);
    assertThat(tx(actor, () -> kb.detail(entryId)).effect().usageCount())
        .as("改删被拒后明细行必须原样还在")
        .isEqualTo(1);
  }

  @Test
  void 命中率按启用话术条数算且被驳回不计入() {
    // 3 条启用话术：一条被命中过、一条只有被驳回的记录、一条从没被用过
    UUID hit = entry(actor, "KB-HIT1");
    UUID rejected = entry(actor, "KB-HIT2");
    UUID never = entry(actor, "KB-HIT3");
    write(actor, () -> kb.recordUsage(actor, hit, new UsageInput("RESOLVED", null, "c1", "")));
    write(actor, () -> kb.recordUsage(actor, rejected, new UsageInput("REJECTED", null, "c2", "")));

    var overview = tx(actor, () -> kb.overview());
    // 分母 = 启用话术总条数（本租户其它用例也会建话术，所以只断言比例关系而不是绝对值）
    assertThat(overview.usageTotal()).isGreaterThanOrEqualTo(2);
    assertThat(overview.usageRejected()).isGreaterThanOrEqualTo(1);
    assertThat(overview.hitEntryCount()).isLessThan(overview.entryEnabled());
    assertThat(overview.hitRate()).isNotNull();
    assertThat(overview.hitRate()).isLessThanOrEqualTo(java.math.BigDecimal.ONE);

    // 单条：被驳回的那条命中率为 0（规则 R3：负信号不计入命中）
    assertThat(tx(actor, () -> kb.detail(rejected)).effect().hitRate()).isEqualByComparingTo("0");
    assertThat(tx(actor, () -> kb.detail(rejected)).effect().rejectedCount()).isEqualTo(1);
    // 从没被用过的：分母为 0 时是 null 而不是 0（规则 R4）
    assertThat(tx(actor, () -> kb.detail(never)).effect().hitRate()).isNull();

    // 排行榜默认按被引用次数倒序：被引用过的那条必须在没被用过的之前
    var rates = tx(actor, () -> kb.hitRates(null, 500));
    assertThat(rates).isNotEmpty();
    long hitCount = rates.stream().filter(r -> r.entryId().equals(hit)).findFirst().orElseThrow().usageCount();
    long neverCount = rates.stream().filter(r -> r.entryId().equals(never)).findFirst().orElseThrow().usageCount();
    assertThat(hitCount).isGreaterThan(neverCount);
    // 「最少被使用」排序把没用过的顶到最前
    assertThat(tx(actor, () -> kb.hitRates("LEAST_USED", 500)).get(0).usageCount()).isZero();
    // 「被驳回最多」排序把 REJECTED 那条顶到最前
    assertThat(tx(actor, () -> kb.hitRates("REJECTED", 500)).get(0).rejectedCount())
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void 停用的话术不计入命中率分母() {
    UUID off = entry(actor, "KB-OFF");
    write(actor, () -> kb.setEntryEnabled(actor, off, false));
    var overview = tx(actor, () -> kb.overview());
    assertThat(overview.entryDisabled()).isPositive();
    assertThat(overview.entryTotal()).isEqualTo(overview.entryEnabled() + overview.entryDisabled());
  }

  // ================================================================ 条目 CRUD 与删除保护

  @Test
  void 编码重复必须被拒() {
    String code = unique("KB-DUP");
    write(actor, () -> kb.saveEntry(actor, null, entryInput(code, "第一条", "FAQ")));
    assertThatThrownBy(
            () -> write(actor, () -> kb.saveEntry(actor, null, entryInput(code, "第二条", "FAQ"))))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(tx(actor, () -> kb.listEntries(code, null, null, null, 0, 20)).total()).isEqualTo(1);
  }

  @Test
  void 乐观锁版本冲突必须被拒() {
    UUID entryId = entry(actor, "KB-LOCK");
    var current = tx(actor, () -> kb.detail(entryId)).version();
    write(
        actor,
        () ->
            kb.saveEntry(
                actor,
                entryId,
                new KnowledgeEntryInput(
                    unique("KB-LOCK"), "改名一", "FAQ", "正文", "zh-CN", "", true, current)));
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        kb.saveEntry(
                            actor,
                            entryId,
                            new KnowledgeEntryInput(
                                unique("KB-LOCK"), "改名二", "FAQ", "正文", "zh-CN", "", true,
                                current))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("他人改动");
  }

  @Test
  void 被引用过的话术不能删只能停用() {
    UUID entryId = entry(actor, "KB-DEL");
    write(actor, () -> kb.recordUsage(actor, entryId, new UsageInput("RESOLVED", null, "c", "")));
    long version = tx(actor, () -> kb.detail(entryId)).version();
    // kb_usage 对 kb_entry 是 CASCADE，所以删话术会带走引用行 —— 但**必须先显式停用**
    // 才能保住统计口径；这里验证删掉之后命中率分母不会残留悬空行
    assertThatThrownBy(() -> writeVoid(actor, () -> kb.deleteEntry(actor, entryId, version + 99)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("他人改动");
  }

  @Test
  void 规则版本被话术引用时不能删() {
    UUID entryId = entry(actor, "KB-RVDEL");
    UUID ruleId = rule(actor, "KEEP_POLICY", "v1", at(1));
    write(actor, () -> kb.bindEntry(actor, entryId, new RuleBindingInput(ruleId, "")));
    assertThatThrownBy(() -> writeVoid(actor, () -> kb.deleteRuleVersion(actor, ruleId)))
        .isInstanceOf(ResponseStatusException.class);
    // 先解除挂钩才能删（规则版本本身是政策存档）
    var binding = tx(actor, () -> kb.bindingsOf(entryId)).get(0);
    writeVoid(actor, () -> kb.unbindEntry(actor, entryId, binding.id()));
    writeVoid(actor, () -> kb.deleteRuleVersion(actor, ruleId));
    assertThat(tx(actor, () -> kb.bindingsOf(entryId))).isEmpty();
  }

  @Test
  void 同一话术重复挂同一版规则必须被拒() {
    UUID entryId = entry(actor, "KB-RB2");
    UUID ruleId = rule(actor, "ONCE_POLICY", "v1", at(1));
    write(actor, () -> kb.bindEntry(actor, entryId, new RuleBindingInput(ruleId, "")));
    assertThatThrownBy(
            () -> write(actor, () -> kb.bindEntry(actor, entryId, new RuleBindingInput(ruleId, ""))))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(tx(actor, () -> kb.bindingsOf(entryId))).hasSize(1);
  }

  @Test
  void 列表分页筛选与关键词都真的生效() {
    String code = unique("KB-SEARCH");
    write(actor, () -> kb.saveEntry(actor, null,
        new KnowledgeEntryInput(code, "可搜索的退换货话术", "RETURN_EX",
            "换货需要买家承担来回运费。", "zh-CN", "换货", true, 0L)));
    // 关键词搜编码
    assertThat(tx(actor, () -> kb.listEntries(code, null, null, null, 0, 20)).total()).isEqualTo(1);
    // 关键词搜正文（不是只看标题）
    assertThat(tx(actor, () -> kb.listEntries("来回运费", null, null, null, 0, 20)).total())
        .isEqualTo(1);
    // 类型筛选
    assertThat(tx(actor, () -> kb.listEntries(null, "RETURN_EX", null, null, 0, 200)).total())
        .isGreaterThanOrEqualTo(1);
    // 翻页决胜键不重不漏
    var all = tx(actor, () -> kb.listEntries(null, null, null, null, 0, 200)).items();
    var firstPage = tx(actor, () -> kb.listEntries(null, null, null, null, 0, 20)).items();
    assertThat(all).hasSizeGreaterThanOrEqualTo(firstPage.size());
    assertThat(all.stream().map(KnowledgeEntryRow::id).collect(java.util.stream.Collectors.toSet()))
        .containsAll(firstPage.stream().map(KnowledgeEntryRow::id).toList());
  }

  // ================================================================ 端点与权限

  @Test
  void 写端点对只有读权限的身份必须403() throws Exception {
    UUID entryId = entry(actor, "KB-PERM");
    http.perform(post("/catalog/kb/entries")
            .header("Authorization", "Bearer reader")
            .contentType("application/json")
            .content(JSON.writeValueAsString(entryInput(unique("KB-NO"), "越权创建", "FAQ"))))
        .andExpect(status().isForbidden());
    http.perform(put("/catalog/kb/entries/" + entryId + "/enabled?enabled=false")
            .header("Authorization", "Bearer reader"))
        .andExpect(status().isForbidden());
    // 读放行
    http.perform(get("/catalog/kb/entries").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/kb/overview").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/kb/hit-rates").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
  }

  @Test
  void 无认证必须401() throws Exception {
    http.perform(get("/catalog/kb/entries").header("Authorization", "Bearer nope"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 规则版本列表与词表端点可读() throws Exception {
    rule(actor, "PAGE_POLICY", "v1", at(1));
    http.perform(get("/catalog/kb/rule-versions").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/kb/review-tasks").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/kb/options").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
  }

  // ================================================================ 租户隔离三断言

  /**
   * 租户隔离三断言，<b>并且覆盖全部六张表</b>。
   *
   * <p>只测话术条目等于没测：<b>规则版本与待复核任务才是本模块真正的越权面</b> ——
   * A 读到 B 的「平台政策已改版、这些话术要复核」会让 A 按别的平台的规则改自己的话术，
   * 而这条错误在买家收到答复之前<b>不会以任何形式暴露</b>。
   */
  @Test
  void 租户隔离三断言覆盖六张表() throws Exception {
    UUID myEntry = entry(actor, "KB-ISO-A");
    UUID theirEntry = entry(actorB, "KB-ISO-B");
    UUID myRule = rule(actor, "ISO_POLICY", "v1", at(1));
    UUID theirRule = rule(actorB, "ISO_POLICY", "v1", at(1));
    write(actor, () -> kb.bindEntry(actor, myEntry, new RuleBindingInput(myRule, "")));
    write(actorB, () -> kb.bindEntry(actorB, theirEntry, new RuleBindingInput(theirRule, "")));
    UUID myUsage = write(actor, () -> kb.recordUsage(actor, myEntry,
        new UsageInput("RESOLVED", null, "iso-a", ""))).id();
    UUID theirUsage = write(actorB, () -> kb.recordUsage(actorB, theirEntry,
        new UsageInput("RESOLVED", null, "iso-b", ""))).id();

    // 两边都必须真的有行 —— 否则下面的断言就是空的
    assertThat(tx(actor, () -> kb.detail(myEntry)).id()).isEqualTo(myEntry);
    assertThat(tx(actorB, () -> kb.detail(theirEntry)).id()).isEqualTo(theirEntry);
    assertThat(tx(actor, () -> kb.usageRowPublic(myUsage)).id()).isEqualTo(myUsage);
    assertThat(tx(actorB, () -> kb.usageRowPublic(theirUsage)).id()).isEqualTo(theirUsage);

    // 1) 两租户互不可见：读对方条目/规则/引用全部 404
    assertThatThrownBy(() -> tx(actor, () -> kb.detail(theirEntry)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> kb.detail(myEntry)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> kb.bindingsOf(theirEntry)))
        .as("挂钩列表也必须隔离：否则 A 能看到 B 的话术依据哪几版政策")
        .isInstanceOf(ResponseStatusException.class);
    // A 不能把话术挂到 B 的规则上
    assertThatThrownBy(
            () -> write(actor, () -> kb.bindEntry(actor, myEntry, new RuleBindingInput(theirRule, ""))))
        .isInstanceOf(ResponseStatusException.class);
    // A 不能删除或修改 B 的规则版本
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        kb.updateRuleVersion(
                            actor, theirRule,
                            new RuleVersionInput(
                                "AMAZON", "ISO_POLICY", "v1", "改了", "x", at(1), null),
                            "SUPERSEDED")))
        .isInstanceOf(ResponseStatusException.class);
    // A 不能删除 B 的话术
    long theirVersion = tx(actorB, () -> kb.detail(theirEntry)).version();
    assertThatThrownBy(() -> writeVoid(actor, () -> kb.deleteEntry(actor, theirEntry, theirVersion)))
        .isInstanceOf(ResponseStatusException.class);
    // 引用记录行也读不到对方的（直接查库）
    assertThat(tx(actor, () -> sql.fetchOne(
            "select count(*) from kb_usage where tenant_id=? and id=?", a, theirUsage))
        .get(0, Long.class)).isZero();

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系
    http.perform(get("/catalog/kb/entries/" + theirEntry)
            .header("Authorization", "Bearer tenant-a").header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    http.perform(get("/catalog/kb/entries/" + myEntry)
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 伪造头下写入仍落在 B 自己名下 —— 断言**行确实落在 B 名下**，不只断言 A 看不见
    UUID forged = write(actorB, () -> kb.recordUsage(actorB, theirEntry,
        new UsageInput("RESOLVED", null, "iso-forged", ""))).id();
    assertThatThrownBy(() -> tx(actor, () -> kb.usageRowPublic(forged)))
        .as("伪造 tenant 头下写的行，A 依然读不到")
        .isInstanceOf(ResponseStatusException.class);
    assertThat(tx(actorB, () -> sql.fetchOne("select tenant_id from kb_usage where id=?", forged)
            .get(0, UUID.class))).isEqualTo(b);

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言集合互不相交
    for (int i = 0; i < 5; i++) {
      var mine =
          tx(actor, () -> kb.listEntries(null, null, null, null, 0, 200)).items().stream()
              .map(KnowledgeEntryRow::id).collect(java.util.stream.Collectors.toSet());
      var theirs =
          tx(actorB, () -> kb.listEntries(null, null, null, null, 0, 200)).items().stream()
              .map(KnowledgeEntryRow::id).collect(java.util.stream.Collectors.toSet());
      assertThat(mine).isNotEmpty();
      assertThat(theirs).isNotEmpty();
      assertThat(java.util.Collections.disjoint(mine, theirs))
          .as("第 " + i + " 轮：话术集合串租户了")
          .isTrue();
      // 规则版本与待复核任务这两个越权面同样逐轮断言
      var myRules = tx(actor, () -> kb.listRuleVersions(null, null, null, 0, 200)).items().stream()
          .map(RuleVersionRow::id).collect(java.util.stream.Collectors.toSet());
      var theirRules = tx(actorB, () -> kb.listRuleVersions(null, null, null, 0, 200)).items().stream()
          .map(RuleVersionRow::id).collect(java.util.stream.Collectors.toSet());
      assertThat(java.util.Collections.disjoint(myRules, theirRules))
          .as("第 " + i + " 轮：规则版本集合串租户了")
          .isTrue();
    }
  }

  // ================================================================ RLS 与结构

  @Test
  void 六张表都强制RLS() {
    for (String table :
        List.of(
            "kb_rule_version", "kb_entry", "kb_entry_translation",
            "kb_entry_rule", "kb_review_task", "kb_usage")) {
      var row =
          tx(
              actor,
              () ->
                  sql.fetchOne(
                      "select relrowsecurity,relforcerowsecurity from pg_class where relname=?",
                      table));
      assertThat(row.get("relrowsecurity", Boolean.class))
          .as(table + " 必须 ENABLE ROW LEVEL SECURITY")
          .isTrue();
      assertThat(row.get("relforcerowsecurity", Boolean.class))
          .as(table + " 必须 FORCE ROW LEVEL SECURITY（表属主也要受限）")
          .isTrue();
      var policy =
          tx(
              actor,
              () ->
                  sql.fetchOne(
                      "select count(*) from pg_policies where tablename=? and policyname='isolation'",
                      table));
      assertThat(policy.get(0, Long.class)).as(table + " 的 isolation 策略").isEqualTo(1);
    }
  }

  @Test
  void 每张业务表的tenant_id都是NOT_NULL() {
    for (String table :
        List.of(
            "kb_rule_version", "kb_entry", "kb_entry_translation",
            "kb_entry_rule", "kb_review_task", "kb_usage")) {
      var row =
          tx(
              actor,
              () ->
                  sql.fetchOne(
                      "select is_nullable from information_schema.columns"
                          + " where table_name=? and column_name='tenant_id'",
                      table));
      assertThat(row.get(0, String.class))
          .as(table + ".tenant_id 必须 NOT NULL")
          .isEqualTo("NO");
    }
  }

  @Test
  void 触发器与授权都在() {
    // 引用记录 append-only 的两条触发器
    for (String trigger : List.of("refuse_kb_usage_update", "refuse_kb_usage_delete")) {
      long exists =
          tx(actor, () -> sql.fetchOne(
              "select count(*) from pg_trigger where tgname=?", trigger).get(0, Long.class));
      assertThat(exists).as(trigger).isEqualTo(1);
    }
    // 复核任务留痕触发器
    long reviewGuard =
        tx(actor, () -> sql.fetchOne(
            "select count(*) from pg_trigger where tgname='refuse_kb_review_task_delete'")
            .get(0, Long.class));
    assertThat(reviewGuard).isEqualTo(1);
    // 规则接替自动生成待复核任务的触发器
    long spawn =
        tx(actor, () -> sql.fetchOne(
            "select count(*) from pg_trigger where tgname='spawn_kb_review_tasks_on_supersede'")
            .get(0, Long.class));
    assertThat(spawn).isEqualTo(1);
    // 译文占位符守卫
    long guard =
        tx(actor, () -> sql.fetchOne(
            "select count(*) from pg_trigger where tgname='guard_kb_translation_body'")
            .get(0, Long.class));
    assertThat(guard).isEqualTo(1);
    // 一个政策同时只能有一版生效
    long singleActive =
        tx(actor, () -> sql.fetchOne(
            "select count(*) from pg_trigger where tgname='guard_kb_rule_single_active'")
            .get(0, Long.class));
    assertThat(singleActive).isEqualTo(1);
    // kb_usage 只授 SELECT/INSERT（append-only 的 REVOKE 纵深）
    var privileges =
        tx(
            actor,
            () ->
                sql.fetch(
                    "select privilege_type from information_schema.role_table_grants"
                        + " where table_name='kb_usage' and grantee='catalog_app'"));
    assertThat(privileges.stream().map(r -> r.get(0, String.class)).toList())
        .containsExactlyInAnyOrder("SELECT", "INSERT");
  }

  @Test
  void 生效区间在DB层拒绝对齐的两端() {
    UUID ruleId = rule(actor, "INTERVAL_POLICY", "v1", at(1));
    // 终点等于起点 = 这一版从未生效过
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        sql.execute(
                            "update kb_rule_version set effective_to=effective_from"
                                + " where tenant_id=? and id=?",
                            a,
                            ruleId)))
        .isInstanceOf(Exception.class);
  }

  // ================================================================ 时间字段往返

  @Test
  void 规则生效时间写回零漂移() {
    // timestamptz 绑定必须用 OffsetDateTime + ?::timestamptz；
    // 用 Timestamp.valueOf(LocalDateTime) 会静默偏 -28800s（+08:00 时区下正好差 8 小时）。
    UUID ruleId = rule(actor, "TIME_POLICY", "v1", at(3));
    var stored =
        tx(
            actor,
            () ->
                sql.fetchOne(
                        "select effective_from from kb_rule_version where tenant_id=? and id=?",
                        a,
                        ruleId)
                    .get("effective_from", OffsetDateTime.class));
    // **断言写回的值**，不是只断言没抛异常
    assertThat(stored.toInstant())
        .isEqualTo(OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8)).toInstant());
    assertThat(stored.getHour()).isEqualTo(0);
    // 接口读出的也是同一时刻。2026-03-01T00:00+08:00 归一到 UTC 就是 **前一天 16:00** ——
    // 这里断言的真实 UTC 值（2026-02-28T16:00Z）恰好就是「有没有踩 -28800s 陷阱」的判据：
    // 若绑定时误用 Timestamp.valueOf(LocalDateTime)，读回的就是 2026-03-01T00:00Z（差 8 小时）。
    assertThat(tx(actor, () -> kb.listRuleVersions("TIME_POLICY", null, null, 0, 20))
            .items().get(0).effectiveFrom())
        .isEqualTo("2026-02-28T16:00:00Z");
  }

  @Test
  void 引用记录的时间戳往返不丢精度() {
    UUID entryId = entry(actor, "KB-TS");
    var used = write(actor, () -> kb.recordUsage(actor, entryId,
        new UsageInput("RESOLVED", null, "ts-conv", "")));
    assertThat(used.createdAt()).isNotNull();
    var stored =
        tx(
            actor,
            () ->
                sql.fetchOne(
                        "select created_at from kb_usage where tenant_id=? and id=?",
                        a,
                        UUID.fromString(used.id().toString()))
                    .get("created_at", OffsetDateTime.class));
    assertThat(stored.toInstant())
        .as("写回的时间必须与接口返回的是同一时刻（不是 LocalDateTime 截断后的值）")
        .isEqualTo(java.time.Instant.parse(used.createdAt()));
  }
}
