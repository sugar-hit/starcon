package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.TextTranslationModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.jooq.DSLContext;
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
 * 18.4.2.1「文本与翻译」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>本测试盯住四件事：
 *
 * <ol>
 *   <li><b>AI 未接入 ⇒ 409 且一个字都没写</b>（不落草稿、不落状态、不留「已翻译」的痕迹）；
 *   <li><b>确定性检查真的拦得住</b>（品牌词缺失 / 占位符缺失 / 违禁词命中 / 长度越界 ⇒ FAIL）；
 *   <li><b>人工确认是硬门槛</b>（未确认不能发布，未确认的译文下游取不走）；
 *   <li><b>置信度有可解释的计算依据</b>（四个维度分列存储，不是拍脑袋的数）。
 * </ol>
 *
 * <p>租户隔离的三条断言在 {@code TextTranslationTenancyIntegrationTest} 里（同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TextTranslationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer catalog;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /**
   * 造一个允许 **null 值**的 JSON 对象。
   *
   * <p>🔴 刻意**不用** {@code Map.of(...)}：它对 null 值直接抛 NPE，而上游真实返回里
   * 「这一列没有值」是常态（SPU 行的 {@code skuId} 就是空的）。夹具自己先炸掉的话，
   * 上游桩会变成 500，而本服务如实翻成 503 —— 测试会看到「上游不可达」，
   * 真正的错（夹具构造错了）反而被藏起来。
   */
  static Map<String, Object> map(Object... keyValues) {
    var out = new java.util.LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      out.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
    }
    return out;
  }

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role platform_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    var permissions =
        List.of(
            "platform.text.read",
            "platform.text.create",
            "platform.text.update",
            "platform.text.confirm",
            "platform.text.publish",
            "platform.text.reject");
    actors.put(
        "session-a",
        new AccessService.Access(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Test",
            UUID.randomUUID(),
            null,
            permissions,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions))));
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

      // catalog 上游桩：真实形状的 JSON，**不是**假数据源 —— 它模拟的是上游服务返回什么。
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/translations/locales",
          e -> {
            if (!"session-a".equals(e.getRequestHeaders().getFirst("Authorization"))) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body =
                json.writeValueAsBytes(
                    Map.of(
                        "items",
                        List.of(
                            Map.of(
                                "locale", "en-US",
                                "localeName", "英语（美国）",
                                "enabled", true,
                                "translationCount", 12,
                                "pendingCount", 3),
                            Map.of(
                                "locale", "de",
                                "localeName", "德语",
                                "enabled", false,
                                "translationCount", 4,
                                "pendingCount", 4)),
                        "total",
                        2,
                        "page",
                        0,
                        "size",
                        200));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      catalog.createContext(
          "/catalog/translations/entries",
          e -> {
            var query = e.getRequestURI().getRawQuery() == null ? "" : e.getRequestURI().getRawQuery();
            if (query.contains("00000000000b")) {
              // 上游 500 —— 本服务必须如实报 503（读不到），而不是返回空集合。
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            // 🔴 桩把**收到的 spuId 过滤参数**原样回显：这样「上游到底有没有被按 SPU 过滤」
            //   就是一条可断言的事实，而不是靠「返回了 1 条」这种看不出区别的假象。
            var requestedSpuId = query.contains("spuId=")
                ? query.substring(query.indexOf("spuId=") + 6).split("&")[0]
                : "";
            byte[] body =
                json.writeValueAsBytes(
                    Map.of(
                        "items",
                        List.of(
                            map(
                                "id",
                                UUID.randomUUID().toString(),
                                "targetType",
                                "SPU",
                                "spuId",
                                requestedSpuId,
                                "skuId",
                                null,
                                "locale",
                                "zh-CN",
                                "name",
                                "便携榨汁杯",
                                "description",
                                "六叶刀头，USB 充电。",
                                "status",
                                "REVIEWED")),
                        "total",
                        1,
                        "page",
                        0,
                        "size",
                        200));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      catalog.setExecutor(Executors.newFixedThreadPool(2));
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "platform_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("CATALOG_URL", () -> "http://127.0.0.1:" + catalog.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + (catalog.getAddress().getPort() + 1));
  }

  @Autowired DSLContext sql;
  @Autowired TenantTransactions tenants;
  @Autowired MockMvc http;

  /** 直接读库时**必须**在可信租户事务里读 —— 否则 RLS 会把行全部挡掉。 */
  private <T> T inTenant(java.util.function.Supplier<T> work) {
    return tenants.run(tenant(), work);
  }

  private UUID tenant() {
    return actors.get("session-a").tenantId();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_text_check, platform_text_draft, platform_text_task,"
              + " platform_text_template, platform_text_rule, platform_text_banned_term,"
              + " platform_text_glossary, platform_app_audit_event, platform_app_quota_usage,"
              + " platform_app_quota, platform_app_ip_allowlist, platform_app_scope,"
              + " platform_app_credential, platform_app restart identity cascade");
      s.execute("alter sequence platform_text_task_no_seq restart with 1");
    }
  }

  // ================================================================ 夹具

  private TextTaskRow createTask(
      String contentType, String sourceText, String sourceLocale, String targetLocale) throws Exception {
    var body =
        json.writeValueAsString(
            new TextTaskInput(contentType, sourceText, sourceLocale, targetLocale, "", "", null, null));
    var response =
        http.perform(
                post("/platform/text/tasks")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, TextTaskRow.class);
  }

  private void saveGlossary(String sourceTerm, String targetTerm, String type, String locale)
      throws Exception {
    var body =
        json.writeValueAsString(
            new TextGlossaryInput(sourceTerm, targetTerm, type, locale, false, true, ""));
    http.perform(
            post("/platform/text/glossary")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  private void saveBannedTerm(String term, String category, String severity) throws Exception {
    var body = json.writeValueAsString(new TextBannedTermInput(term, category, severity, "", true, ""));
    http.perform(
            post("/platform/text/banned-terms")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  private void saveRule(
      String contentType, String locale, Integer min, Integer max, String charset) throws Exception {
    var body =
        json.writeValueAsString(
            new TextRuleInput(contentType, locale, min, max, charset, true, true, ""));
    http.perform(
            post("/platform/text/rules")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  private TextTaskDetail saveDraft(UUID taskId, String targetText) throws Exception {
    var body = json.writeValueAsString(new TextDraftInput(targetText));
    var response =
        http.perform(
                post("/platform/text/tasks/" + taskId + "/drafts")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, TextTaskDetail.class);
  }

  private TextTaskDetail confirm(UUID taskId) throws Exception {
    var response =
        http.perform(post("/platform/text/tasks/" + taskId + "/confirm").header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, TextTaskDetail.class);
  }

  // ==========================================================================
  // 1. AI 未接入：409 且一个字都没写
  // ==========================================================================

  @Test
  void ai未接入时生成端点返回409且一个字都没写() throws Exception {
    var task = createTask("DESCRIPTION", "便携榨汁杯，六叶刀头。", "zh-CN", "en-US");
    assertThat(task.status()).isEqualTo("PENDING");

    http.perform(
            post("/platform/text/tasks/" + task.id() + "/generate").header("Authorization", "session-a"))
        .andExpect(status().isConflict());

    // 🔴 **一个字都没写**：没有草稿、没有检查结论、任务状态没动。
    assertThat(inTenant(() -> sql.fetch("select id from platform_text_draft")))
        .as("AI 未接入时不得凭空长出一版译文")
        .isEmpty();
    assertThat(inTenant(() -> sql.fetch("select id from platform_text_check"))).isEmpty();
    var rows = inTenant(() -> sql.fetch("select status,current_draft_id from platform_text_task"));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("status", String.class)).isEqualTo("PENDING");
    assertThat(rows.get(0).get("current_draft_id", UUID.class)).isNull();
  }

  @Test
  void 能力声明如实答未接入并标明归属模块() throws Exception {
    var capabilities =
        json.readValue(
            http.perform(get("/platform/text/capabilities").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            TextCapabilities.class);
    var ai =
        capabilities.capabilities().stream()
            .filter(c -> c.name().contains("AI 自动翻译"))
            .findFirst()
            .orElse(null);
    assertThat(ai).as("AI 那一维必须在能力声明里点名，不能悄悄消失").isNotNull();
    assertThat(ai.available()).as("本仓没有真实 AI 供应商适配器").isFalse();
    assertThat(ai.ownerModule()).as("未接入的能力必须标明归谁").isEqualTo("18.4");
    assertThat(ai.note()).contains("一个字都不写");
  }

  // ==========================================================================
  // 2. 确定性检查真的拦得住
  // ==========================================================================

  @Test
  void 品牌词缺失判失败并拦住发布() throws Exception {
    saveGlossary("StarLight", "StarLight", "BRAND", "en-US");
    var task = createTask("TITLE", "StarLight 便携榨汁杯", "zh-CN", "en-US");

    var detail = saveDraft(task.id(), "Portable juicer cup");
    var terminology =
        detail.checks().stream().filter(c -> "TERMINOLOGY".equals(c.ruleCode())).findFirst().orElse(null);
    assertThat(terminology).isNotNull();
    assertThat(terminology.verdict()).as("品牌词缺失是硬伤").isEqualTo("FAIL");
    assertThat(terminology.matchedItem()).isEqualTo("StarLight");

    // 拦住发布：先确认（人的判断允许），发布仍被确定性检查拦下。
    confirm(task.id());
    http.perform(
            post("/platform/text/tasks/" + task.id() + "/publish").header("Authorization", "session-a"))
        .andExpect(status().isConflict());

    // 补回品牌词后再发：就过了。
    saveDraft(task.id(), "StarLight Portable Juicer Cup");
    confirm(task.id());
    http.perform(
            post("/platform/text/tasks/" + task.id() + "/publish").header("Authorization", "session-a"))
        .andExpect(status().isOk());
  }

  @Test
  void 占位符缺失或越界都判失败() throws Exception {
    var task = createTask("CUSTOMER_REPLY", "Hi {{buyer_name}}, order {{order_no}} is ready.", "en-US", "de");

    var missing = saveDraft(task.id(), "Hallo, Ihre Bestellung ist bereit.");
    var placeholder =
        missing.checks().stream().filter(c -> "PLACEHOLDER".equals(c.ruleCode())).findFirst().orElse(null);
    assertThat(placeholder).isNotNull();
    assertThat(placeholder.verdict()).isEqualTo("FAIL");
    assertThat(placeholder.matchedItem()).isEqualTo("buyer_name");

    var unknown = saveDraft(task.id(), "Hallo {{buyer_name}}, {{order_no}} {{not_a_variable}}.");
    var violations = unknown.violations().stream().filter(v -> "PLACEHOLDER".equals(v.ruleCode())).toList();
    assertThat(violations).isNotEmpty();
    assertThat(violations.stream().anyMatch(v -> "not_a_variable".equals(v.matchedItem())))
        .as("封闭词表之外的变量必须被判失败")
        .isTrue();
  }

  @Test
  void 违禁词命中给出可执行的规避建议() throws Exception {
    saveBannedTerm("100% pure", "ABSOLUTE", "BLOCK");
    var task = createTask("DESCRIPTION", "Juicer description", "en-US", "de");

    var detail = saveDraft(task.id(), "100% pure stainless steel juicer");
    var banned =
        detail.checks().stream().filter(c -> "BANNED_TERM".equals(c.ruleCode())).findFirst().orElse(null);
    assertThat(banned).isNotNull();
    assertThat(banned.verdict()).as("租户登记的 BLOCK 级词条命中即失败").isEqualTo("FAIL");
    assertThat(banned.matchedItem()).isEqualTo("100% pure");

    var violation =
        detail.violations().stream().filter(v -> "BANNED_TERM".equals(v.ruleCode())).findFirst().orElse(null);
    assertThat(violation).as("违禁词规避建议是本模块的职责之一").isNotNull();
    assertThat(violation.suggestion()).contains("100% pure");
  }

  @Test
  void 内置红线命中判警告而不是硬拦() throws Exception {
    var task = createTask("DESCRIPTION", "desc", "en-US", "de");
    var detail = saveDraft(task.id(), "The best juicer in the world");
    var banned =
        detail.checks().stream().filter(c -> "BANNED_TERM".equals(c.ruleCode())).findFirst().orElse(null);
    assertThat(banned).isNotNull();
    assertThat(banned.verdict()).as("绝对化用语需要人判断，判警告").isEqualTo("WARN");
  }

  @Test
  void 长度越界判失败且不配规则时显式跳过() throws Exception {
    var task = createTask("TITLE", "a title", "en-US", "de");
    var skipped = saveDraft(task.id(), "x");
    var length =
        skipped.checks().stream().filter(c -> "LENGTH".equals(c.ruleCode())).findFirst().orElse(null);
    assertThat(length).isNotNull();
    assertThat(length.verdict()).as("没配规则 ⇒ 显式 SKIPPED，不是假装通过").isEqualTo("SKIPPED");

    saveRule("TITLE", "de", 5, 10, "ANY");
    var tooLong = saveDraft(task.id(), "this title is definitely longer than ten characters");
    var length2 =
        tooLong.checks().stream().filter(c -> "LENGTH".equals(c.ruleCode())).findFirst().orElse(null);
    assertThat(length2).isNotNull();
    assertThat(length2.verdict()).isEqualTo("FAIL");
  }

  // ==========================================================================
  // 3. 置信度：可解释
  // ==========================================================================

  @Test
  void 置信度是四个维度的加权且逐维可解释() throws Exception {
    saveGlossary("StarLight", "StarLight", "BRAND", "en-US");
    saveRule("TITLE", "en-US", 5, 80, "ANY");
    var task = createTask("TITLE", "StarLight juicer for {{buyer_name}}", "zh-CN", "en-US");

    var detail = saveDraft(task.id(), "StarLight juicer for {{buyer_name}}");

    var confidence = detail.confidence();
    assertThat(confidence.total()).isNotNull();
    assertThat(confidence.total()).isInstanceOf(BigDecimal.class);
    assertThat(confidence.total()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
    // 四个依据分列存储（不是只给一个总分）。
    assertThat(confidence.termCoverage()).isEqualByComparingTo("1.0000");
    assertThat(confidence.placeholderIntegrity()).isEqualByComparingTo("1.0000");
    assertThat(confidence.checkPassRate()).isNotNull();
    assertThat(confidence.lengthCompliance()).isEqualByComparingTo("1.0000");
    // 逐维说明是**人话**，前端直接显示。
    assertThat(confidence.explanation()).isNotEmpty();
    assertThat(confidence.explanation().stream().anyMatch(s -> s.contains("术语覆盖率"))).isTrue();
    assertThat(confidence.explanation().stream().anyMatch(s -> s.contains("占位符完整率"))).isTrue();
    assertThat(confidence.explanation().stream().anyMatch(s -> s.contains("校验通过率"))).isTrue();
    assertThat(confidence.explanation().stream().anyMatch(s -> s.contains("长度合规率"))).isTrue();

    // 库里真的存着这些列（不是响应里编的）。
    var stored =
        inTenant(
            () ->
                sql.fetchOne(
                    "select confidence,confidence_term_coverage,confidence_placeholder_integrity,"
                        + " confidence_check_pass_rate,confidence_length_compliance"
                        + " from platform_text_draft where task_id = ?",
                    task.id()));
    assertThat(stored.get("confidence", BigDecimal.class)).isEqualByComparingTo(confidence.total());
    assertThat(stored.get("confidence_term_coverage", BigDecimal.class))
        .isEqualByComparingTo("1.0000");
  }

  @Test
  void 不适用维度按剩余维度归一而不是当成零分() throws Exception {
    // 源文本没有术语、也没有占位符 ⇒ 术语覆盖率与占位符完整率**不适用**。
    var task = createTask("DESCRIPTION", "plain text", "en-US", "de");
    var detail = saveDraft(task.id(), "plain text");
    assertThat(detail.confidence().termCoverage()).isNull();
    assertThat(detail.confidence().placeholderIntegrity()).isNull();
    // 但总分仍然是可用的（按剩余适用维度归一），不是 0。
    assertThat(detail.confidence().total()).isGreaterThan(BigDecimal.ZERO);
    assertThat(detail.confidence().explanation().stream().anyMatch(s -> s.contains("不适用"))).isTrue();
  }

  // ==========================================================================
  // 4. 人工确认是硬门槛
  // ==========================================================================

  @Test
  void 未确认不能发布且未确认的译文下游取不走() throws Exception {
    var task = createTask("DESCRIPTION", "hello", "en-US", "de");
    saveDraft(task.id(), "hallo");

    // 没确认就发布 ⇒ 409。
    http.perform(
            post("/platform/text/tasks/" + task.id() + "/publish").header("Authorization", "session-a"))
        .andExpect(status().isConflict());

    // 未确认 ⇒ 下游取不到。
    var before =
        json.readValue(
            http.perform(get("/platform/text/published").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            TextTaskRow[].class);
    assertThat(before).isEmpty();

    confirm(task.id());
    http.perform(
            post("/platform/text/tasks/" + task.id() + "/publish").header("Authorization", "session-a"))
        .andExpect(status().isOk());

    var after =
        json.readValue(
            http.perform(get("/platform/text/published").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            TextTaskRow[].class);
    assertThat(after).hasSize(1);
    assertThat(after[0].status()).isEqualTo("PUBLISHED");
  }

  @Test
  void 改动已确认的译文会让确认作废() throws Exception {
    var task = createTask("DESCRIPTION", "hello", "en-US", "de");
    saveDraft(task.id(), "hallo");
    confirm(task.id());
    assertThat(inTenant(() -> sql.fetchOne("select status from platform_text_task where id = ?", task.id()))
            .get(0, String.class)).isEqualTo("CONFIRMED");

    // 改内容 ⇒ 确认作废（确认是对**那一版**的确认）。
    var detail = saveDraft(task.id(), "hallo (neu)");
    assertThat(detail.task().status()).isEqualTo("DRAFTED");
    assertThat(detail.task().confirmedAt()).isNull();
    http.perform(
            post("/platform/text/tasks/" + task.id() + "/publish").header("Authorization", "session-a"))
        .andExpect(status().isConflict());
  }

  @Test
  void 草稿按版本追加旧版本留着() throws Exception {
    var task = createTask("DESCRIPTION", "hello", "en-US", "de");
    saveDraft(task.id(), "hallo");
    var second = saveDraft(task.id(), "guten tag");
    assertThat(second.history()).hasSize(2);
    assertThat(second.history().get(0).version()).isEqualTo(2);
    assertThat(second.history().get(1).version()).isEqualTo(1);
  }

  @Test
  void 驳回是终态且必须写原因() throws Exception {
    var task = createTask("DESCRIPTION", "hello", "en-US", "de");
    saveDraft(task.id(), "hallo");
    http.perform(
            post("/platform/text/tasks/" + task.id() + "/reject")
                .header("Authorization", "session-a")
                .param("reason", "口径不对"))
        .andExpect(status().isOk());
    var rows = inTenant(() -> sql.fetch("select status,reject_reason from platform_text_task"));
    assertThat(rows.get(0).get("status", String.class)).isEqualTo("REJECTED");
    assertThat(rows.get(0).get("reject_reason", String.class)).isEqualTo("口径不对");

    // 没写原因 ⇒ 400。
    var other = createTask("DESCRIPTION", "hello2", "en-US", "de");
    http.perform(
            post("/platform/text/tasks/" + other.id() + "/reject").header("Authorization", "session-a"))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 5. 模板与变量绑定
  // ==========================================================================

  @Test
  void 模板变量词表是封闭的越界变量存不进来() throws Exception {
    // 词表内的变量：**必须收得进来**（`buyer_name` / `order_no` 都在 crm 的封闭词表里）。
    // 把「词表内」也判成 400 的话，这个模块就变成了「任何客服话术都存不进来」。
    var body =
        json.writeValueAsString(
            new TextTemplateInput(
                "GREETING",
                "打招呼",
                "Hi {{buyer_name}}, your order {{order_no}} is ready.",
                "CUSTOMER_REPLY",
                "de",
                true,
                ""));
    var accepted =
        json.readValue(
            http.perform(
                    post("/platform/text/templates")
                        .header("Authorization", "session-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            TextTemplateRow.class);
    assertThat(accepted.variables()).containsExactlyInAnyOrder("buyer_name", "order_no");

    // 词表外的变量：**存不进来**（服务端确定填不上，留着就是发给买家的 {{xxx}}）。
    var bad =
        json.writeValueAsString(
            new TextTemplateInput(
                "GREETING", "打招呼", "Hi {{not_a_variable}}", "CUSTOMER_REPLY", "en-US", true, ""));
    http.perform(
            post("/platform/text/templates")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bad))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 模板变量绑定把缺的变量如实报出来() throws Exception {
    var body =
        json.writeValueAsString(
            new TextTemplateInput(
                "GREETING",
                "打招呼",
                "Hi {{buyer_name}}, order {{order_no}} is ready.",
                "CUSTOMER_REPLY",
                "en-US",
                true,
                ""));
    var template =
        json.readValue(
            http.perform(
                    post("/platform/text/templates")
                        .header("Authorization", "session-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            TextTemplateRow.class);
    assertThat(template.variables()).containsExactlyInAnyOrder("buyer_name", "order_no");

    var binding =
        json.readValue(
            http.perform(
                    get("/platform/text/templates/" + template.id() + "/binding")
                        .header("Authorization", "session-a")
                        .param("buyer_name", "Anna"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            TextTemplateBinding.class);
    assertThat(binding.body()).contains("Hi Anna");
    assertThat(binding.missing()).containsExactly("order_no");
  }

  @Test
  void 用模板可以建客服话术任务() throws Exception {
    var body =
        json.writeValueAsString(
            new TextTemplateInput(
                "FOLLOWUP", "催发货", "Hi {{buyer_name}}, order {{order_no}} shipped.",
                "CUSTOMER_REPLY", "de", true, ""));
    var template =
        json.readValue(
            http.perform(
                    post("/platform/text/templates")
                        .header("Authorization", "session-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            TextTemplateRow.class);

    var taskBody =
        json.writeValueAsString(
            new TextTaskInput(
                "CUSTOMER_REPLY", "", "en-US", "", "", "", null, template.id().toString()));
    var task =
        json.readValue(
            http.perform(
                    post("/platform/text/tasks")
                        .header("Authorization", "session-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            TextTaskRow.class);
    assertThat(task.sourceText()).isEqualTo("Hi {{buyer_name}}, order {{order_no}} shipped.");
    assertThat(task.targetLocale()).isEqualTo("de");
  }

  // ==========================================================================
  // 6. 跨服务只读
  // ==========================================================================

  @Test
  void 目标语言来自上游多语言内容() throws Exception {
    var locales =
        json.readValue(
            http.perform(get("/platform/text/locales").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            TextLocaleView.class);
    assertThat(locales.items()).hasSize(2);
    assertThat(locales.items().get(0).locale()).isEqualTo("en-US");
    assertThat(locales.source()).contains("catalog");
  }

  @Test
  void 上游源文本读得到时返回真实数据() throws Exception {
    // 🔴 用一个**认得出来**的 SPU id：上游桩把它回显回来，
    //   这样「本服务到底有没有把 spuId 传给上游」是一条可断言的事实。
    //   （判据写错时上游收不到过滤参数，会返回全量条目 —— 那在页面上长得像「译文都在这儿」，
    //   实际是「查的是全部 SPU」。）
    var spuId = UUID.randomUUID();
    var candidates =
        json.readValue(
            http.perform(
                    get("/platform/text/source-candidates")
                        .header("Authorization", "session-a")
                        .param("refType", "CATALOG_SPU")
                        .param("refId", spuId.toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            TextSourceCandidateView.class);
    assertThat(candidates.items()).hasSize(1);
    assertThat(candidates.items().get(0).name()).isEqualTo("便携榨汁杯");
    assertThat(candidates.items().get(0).locale()).isEqualTo("zh-CN");
    // 上游确实收到了 spuId 过滤（回显值 === 请求的 id）。
    assertThat(candidates.items().get(0).refId())
        .as("上游必须被按请求的 SPU 过滤")
        .isEqualTo(spuId);
  }

  @Test
  void 上游故障必须翻成503() throws Exception {
    // 上游 500 ⇒ 本服务如实报 503（读不到），**不**返回空集合冒充「上游没有数据」。
    http.perform(
            get("/platform/text/source-candidates")
                .header("Authorization", "session-a")
                .param("refType", "CATALOG_SPU")
                .param("refId", "00000000-0000-4000-8000-00000000000b"))
        .andExpect(status().isServiceUnavailable());
  }

  // ==========================================================================
  // 7. 数据层纪律
  // ==========================================================================

  @Test
  void 本模块不落任何金额() {
    var amountColumns =
        sql.fetch(
            "select table_name, column_name from information_schema.columns"
                + " where table_name in ('platform_text_glossary','platform_text_banned_term',"
                + " 'platform_text_rule','platform_text_template','platform_text_task',"
                + " 'platform_text_draft','platform_text_check')"
                + " and (column_name like '%amount%' or column_name like '%price%' or column_name like '%fee%'"
                + " or column_name like '%balance%' or column_name like '%tax%' or column_name like '%currency%')");
    assertThat(amountColumns).as("文本模块不落金额").isEmpty();
  }

  @Test
  void 置信度用numeric不用浮点() {
    var columns =
        sql.fetch(
            "select column_name, data_type, numeric_precision, numeric_scale"
                + " from information_schema.columns where table_name = 'platform_text_draft'"
                + " and column_name like 'confidence%'");
    assertThat(columns).hasSize(5);
    for (var column : columns) {
      assertThat(column.get("data_type", String.class)).isEqualTo("numeric");
      assertThat(column.get("numeric_scale", Integer.class)).isEqualTo(4);
    }
  }

  @Test
  void 时间列写回的值正确而不是静默偏八小时() throws Exception {
    var before = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
    var task = createTask("DESCRIPTION", "hello", "en-US", "de");
    var rows =
        inTenant(
            () ->
                sql.fetch(
                    "select created_at, updated_at from platform_text_task where id = ?", task.id()));
    var created = rows.get(0).get("created_at", OffsetDateTime.class);
    assertThat(created).isNotNull();
    // 写回的值必须落在「刚才」这个窗口里 —— 偏了 −28800s 就会被这个断言抓住。
    assertThat(created).isAfter(before);
    assertThat(created).isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1));
    // 时区没有被静默换掉（写进去的是 UTC 那一刻的同一瞬间）。
    assertThat(created.toInstant()).isAfter(before.toInstant());
  }

  @Test
  void 任务列表是真分页真筛选() throws Exception {
    createTask("TITLE", "alpha title", "en-US", "de");
    createTask("DESCRIPTION", "beta description", "en-US", "fr");
    createTask("HIGHLIGHT", "gamma highlight", "en-US", "de");

    var page =
        json.readValue(
            http.perform(
                    get("/platform/text/tasks")
                        .header("Authorization", "session-a")
                        .param("size", "2")
                        .param("contentType", "TITLE"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            TextTaskPage.class);
    assertThat(page.items()).hasSize(1);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.page()).isZero();
  }

  @Test
  void 概览的几个数来自真实数据() throws Exception {
    createTask("TITLE", "alpha", "en-US", "de");
    saveGlossary("StarLight", "StarLight", "BRAND", "");
    var overview =
        json.readValue(
            http.perform(get("/platform/text/overview").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            TextOverview.class);
    assertThat(overview.total()).isEqualTo(1);
    assertThat(overview.pending()).isEqualTo(1);
    assertThat(overview.glossary()).isEqualTo(1);
    assertThat(overview.published()).isZero();
  }

  @Test
  void 术语表违禁词与规则都可增删改查() throws Exception {
    var glossary =
        json.readValue(
            http.perform(
                    post("/platform/text/glossary")
                        .header("Authorization", "session-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsString(
                                new TextGlossaryInput(
                                    "Ninja", "Ninja", "BRAND", "en-US", false, true, "品牌"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            TextGlossaryRow.class);
    assertThat(glossary.termTypeLabel()).isEqualTo("品牌词");

    var list =
        json.readValue(
            http.perform(get("/platform/text/glossary").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            TextGlossaryRow[].class);
    assertThat(list).hasSize(1);

    http.perform(
            delete("/platform/text/glossary/" + glossary.id()).header("Authorization", "session-a"))
        .andExpect(status().isOk());
    assertThat(inTenant(() -> sql.fetch("select id from platform_text_glossary"))).isEmpty();
  }
}
