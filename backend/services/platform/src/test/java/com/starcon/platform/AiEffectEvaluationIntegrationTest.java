package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AiEffectEvaluationModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * 18.4.3.2「效果评估」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>本测试盯住十件事：
 *
 * <ol>
 *   <li><b>采纳率的分母只有一个来源</b>：18.4.3.1 的用量台账（只读）；本模块**只做分子**；
 *   <li><b>分母为 0 ⇒ 比率是 null</b>（不是 0、不是 Infinity）；
 *   <li><b>「修改后采用」也算被采纳</b>（蓝图原文：直接采用 + 人工修改后采用）；
 *   <li><b>请求体不接受归属</b>：tenantId / userId / membershipId 一律 400；
 *   <li><b>采纳记录是事实</b>：改写被触发器拒绝，但**可以受控撤回**（ACTIVE → WITHDRAWN）；
 *   <li><b>回测的实际值来自真实销量</b>，**不拿预测值自己当实际值**；
 *   <li><b>没有实际值来源 ⇒ 如实记 NO_ACTUAL_SOURCE</b>，<b>不记 0</b>；
 *   <li><b>低价值判定要样本量达标</b>：不拿 3 次调用判死刑；
 *   <li><b>「建议下线」只是建议</b>：本仓没有下线开关 ⇒ 建议里写明需人工执行；
 *   <li><b>助手答案有用性反馈如实答不可用</b>：不伪造助手数据。
 * </ol>
 *
 * <p>租户隔离的三条断言在 {@code AiEffectEvaluationTenancyIntegrationTest} 里（同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiEffectEvaluationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final UUID TENANT = UUID.randomUUID();
  static final UUID MEMBER_A = UUID.randomUUID();

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID membership, List<String> permissions) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        membership,
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
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
    var all =
        List.of("platform.ai-effect.read", "platform.ai-effect.record", "platform.ai-effect.update");
    actors.put("session-admin", actor(UUID.randomUUID(), TENANT, MEMBER_A, all));
    actors.put("session-readonly", actor(UUID.randomUUID(), TENANT, MEMBER_A, List.of("platform.ai-effect.read")));
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
      identity.setExecutor(Executors.newFixedThreadPool(8));
      identity.start();
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
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_ai_effect_suggestion, platform_ai_effect_backtest,"
              + " platform_ai_effect_feedback, platform_ai_effect_adoption,"
              + " platform_ai_effect_threshold, platform_ai_usage_record, platform_ai_model,"
              + " platform_ai_quota_usage, platform_ai_quota, platform_ai_sensitive_policy,"
              + " platform_app_audit_event, platform_app_quota_usage, platform_app_quota,"
              + " platform_app_ip_allowlist, platform_app_scope, platform_app_credential,"
              + " platform_app restart identity cascade");
    }
    seedModel();
  }

  /** 目录里预置一个模型（18.4.3.1 的用量台账需要它）。 */
  void seedModel() {
    tenants.run(
        TENANT,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_ai_model(id,tenant_id,model_code,model_name,provider,unit,"
                  + " unit_cost,currency,context_limit,enabled,note,created_at,updated_at)"
                  + " values(?,?,?,'按次计量','TEST','REQUEST',0.5,'USD',0,true,'',"
                  + " ?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              TENANT,
              "call-model",
              now,
              now);
          return null;
        });
  }

  /** 直接在库里补一条用量台账（18.4.3.1 的表，本模块**只读**）。 */
  UUID seedUsage(String capability, String feature) {
    return tenants.run(
        TENANT,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          var id = UUID.randomUUID();
          sql.execute(
              "insert into platform_ai_usage_record(id,tenant_id,membership_id,capability_code,"
                  + " feature_code,model_id,model_code,unit,quantity,unit_cost,cost,currency,"
                  + " request_ref,occurred_at,created_at)"
                  + " values(?,?,?,?,?,(select id from platform_ai_model where model_code = ?),"
                  + " 'call-model','REQUEST',1,0.5,0.5,'USD','',?::timestamptz,?::timestamptz)",
              id,
              TENANT,
              MEMBER_A,
              capability,
              feature,
              "call-model",
              now,
              now);
          return id;
        });
  }

  /** 录一条采纳记录（走端点，返回后端给的完整行）。 */
  AiAdoptionRow recordAdoption(
      String session, String capability, String feature, String decision, UUID artifactId)
      throws Exception {
    var body =
        """
        {"capabilityCode":"%s","featureCode":"%s","decision":"%s","artifactType":"TRANSLATION",
         "artifactId":"%s","artifactRef":"task-1","editNote":"改了一处","rejectReason":""}
        """
            .formatted(capability, feature, decision, artifactId)
            .replace("\n", "");
    var response =
        http.perform(
                post("/platform/ai/effects/adoptions")
                    .header("Authorization", session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    try {
      return json.readValue(response.getContentAsString(), AiAdoptionRow.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  AiFeedbackRow recordFeedback(
      String session, String capability, String feature, String usefulness, String reasonCode)
      throws Exception {
    var body =
        """
        {"capabilityCode":"%s","featureCode":"%s","usefulness":"%s","reasonCode":"%s",
         "reasonText":"","artifactType":"ASSISTANT","artifactRef":"answer-1"}
        """
            .formatted(capability, feature, usefulness, reasonCode)
            .replace("\n", "");
    var response =
        http.perform(
                post("/platform/ai/effects/feedback")
                    .header("Authorization", session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    try {
      return json.readValue(response.getContentAsString(), AiFeedbackRow.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ==========================================================================
  // 1. 采纳率的分母只有一个来源 + 分母为 0 ⇒ 比率是 null
  // ==========================================================================
  @Test
  void 分母为零时比率是null而不是零() throws Exception {
    // 没有任何用量台账 ⇒ 分母为 0。
    recordAdoption("session-admin", "TEXT_TRANSLATION", "", "ACCEPTED", UUID.randomUUID());

    var report = adoptionReport();
    assertThat(report.items()).hasSize(1);
    var row = report.items().get(0);
    // 🔴 分母为 0 ⇒ 比率是 **null**（不是 0、不是 Infinity）。
    assertThat(row.adoptionRate()).as("分母为 0 时比率必须是 null").isNull();
    assertThat(row.calls()).as("分母 = 18.4.3.1 用量台账里的调用次数").isZero();
    assertThat(row.adoptedCount()).as("分子 = 本模块的采纳条数").isEqualTo(1L);
    assertThat(row.verdict()).as("没有调用记录 ⇒ 样本不足，不下低价值结论").isEqualTo("INSUFFICIENT_DATA");
    assertThat(row.denominatorSource()).contains("platform_ai_usage_record");
    assertThat(row.numeratorSource()).contains("platform_ai_effect_adoption");
  }

  @Test
  void 采纳率等于采纳次数除以调用次数() throws Exception {
    seedUsage("TEXT_TRANSLATION", "");
    seedUsage("TEXT_TRANSLATION", "");
    seedUsage("TEXT_TRANSLATION", "");
    recordAdoption("session-admin", "TEXT_TRANSLATION", "", "ACCEPTED", UUID.randomUUID());
    recordAdoption("session-admin", "TEXT_TRANSLATION", "", "MODIFIED", UUID.randomUUID());

    var report = adoptionReport();
    var row = report.items().get(0);
    assertThat(row.calls()).isEqualTo(3L);
    assertThat(row.adoptedCount()).isEqualTo(2L);
    // 🔴 2 / 3 = 0.666667（十进制文本，前端不碰浮点）。
    assertThat(row.adoptionRate()).isEqualTo("0.666667");
    assertThat(row.acceptedCount()).isEqualTo(1L);
    assertThat(row.modifiedCount()).isEqualTo(1L);
  }

  // ==========================================================================
  // 2. 「修改后采用」也算被采纳
  // ==========================================================================
  @Test
  void 修改后采用也算被采纳而不是被当成没用() throws Exception {
    seedUsage("TEXT_TRANSLATION", "");
    var row = recordAdoption("session-admin", "TEXT_TRANSLATION", "", "MODIFIED", UUID.randomUUID());

    assertThat(row.decision()).isEqualTo("MODIFIED");
    assertThat(row.decisionLabel()).isEqualTo("修改后采用");
    var report = adoptionReport();
    assertThat(report.items().get(0).adoptedCount()).isEqualTo(1L);
    assertThat(report.items().get(0).adoptionRate()).isEqualTo("1.000000");
  }

  @Test
  void 拒绝不算被采纳() throws Exception {
    seedUsage("TEXT_TRANSLATION", "");
    var rejected =
        http.perform(
                post("/platform/ai/effects/adoptions")
                    .header("Authorization", "session-admin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"capabilityCode":"TEXT_TRANSLATION","featureCode":"","decision":"REJECTED",
                         "artifactType":"TRANSLATION","artifactId":"%s","artifactRef":"t-1",
                         "rejectReason":"译文不准"}
                        """
                            .formatted(UUID.randomUUID())
                            .replace("\n", "")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var row = json.readValue(rejected.getContentAsString(), AiAdoptionRow.class);
    assertThat(row.decision()).isEqualTo("REJECTED");

    var report = adoptionReport();
    assertThat(report.items().get(0).adoptedCount()).as("拒绝不计入分子").isZero();
    assertThat(report.items().get(0).rejectedCount()).isEqualTo(1L);
    assertThat(report.items().get(0).adoptionRate()).isEqualTo("0.000000");
  }

  @Test
  void 驳回必须写明原因() throws Exception {
    http.perform(
            post("/platform/ai/effects/adoptions")
                .header("Authorization", "session-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"capabilityCode":"TEXT_TRANSLATION","featureCode":"","decision":"REJECTED",
                     "artifactType":"TRANSLATION","artifactId":"%s","artifactRef":"t-1"}
                    """
                        .formatted(UUID.randomUUID())
                        .replace("\n", "")))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 3. 请求体不接受归属
  // ==========================================================================
  @Test
  void 请求体不接受归属字段() throws Exception {
    for (String body :
        List.of(
            """
            {"capabilityCode":"TEXT_TRANSLATION","decision":"ACCEPTED","artifactType":"ASSISTANT",
             "tenantId":"%s"}
            """,
            """
            {"capabilityCode":"TEXT_TRANSLATION","decision":"ACCEPTED","artifactType":"ASSISTANT",
             "membershipId":"%s"}
            """,
            """
            {"capabilityCode":"TEXT_TRANSLATION","decision":"ACCEPTED","artifactType":"ASSISTANT",
             "userId":"%s"}
            """)) {
      http.perform(
              post("/platform/ai/effects/adoptions")
                  .header("Authorization", "session-admin")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body.formatted(UUID.randomUUID()).replace("\n", "")))
          .andExpect(status().isBadRequest());
    }
  }

  // ==========================================================================
  // 4. 采纳记录是事实：改写被拒，但可以受控撤回
  // ==========================================================================
  @Test
  void 采纳记录不可改写但可以受控撤回() throws Exception {
    // 🔴 先记一条调用：撤回后采纳行不再计入分子，但**调用仍在**
    //    ⇒ 报表里这一行还在（分母 1、分子 0），而不是整行消失。
    seedUsage("TEXT_TRANSLATION", "");
    var row = recordAdoption("session-admin", "TEXT_TRANSLATION", "", "ACCEPTED", UUID.randomUUID());

    // 直连 SQL 改写事实列 ⇒ 触发器当场拒。
    assertThatThrownBy(
            () ->
                tenants.run(
                    TENANT,
                    () ->
                        sql.execute(
                            "update platform_ai_effect_adoption set decision = 'REJECTED' where id = ?",
                            row.id())))
        .as("采纳记录是事实记录，不允许改写")
        .hasStackTraceContaining("不允许改写");

    // 受控撤回：ACTIVE → WITHDRAWN。
    http.perform(
            post("/platform/ai/effects/adoptions/" + row.id() + "/withdraw")
                .header("Authorization", "session-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"填错了\"}"))
        .andExpect(status().isOk());
    var after =
        json.readValue(
            http.perform(get("/platform/ai/effects/adoptions").header("Authorization", "session-admin"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiAdoptionPage.class);
    assertThat(after.items()).hasSize(1);
    assertThat(after.items().get(0).status()).isEqualTo("WITHDRAWN");
    assertThat(after.items().get(0).withdrawnReason()).isEqualTo("填错了");

    // 🔴 撤回后不再计入分子。
    var report = adoptionReport();
    assertThat(report.items().get(0).adoptedCount()).as("撤回的行不再计入分子").isZero();
  }

  @Test
  void 同一个产物版本只允许一条处置() throws Exception {
    var artifact = UUID.randomUUID();
    recordAdoption("session-admin", "TEXT_TRANSLATION", "", "ACCEPTED", artifact);
    // 再登记同一条 ⇒ 唯一索引当场拒（否则分子虚高 ⇒ 采纳率可以刷）。
    http.perform(
            post("/platform/ai/effects/adoptions")
                .header("Authorization", "session-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"capabilityCode":"TEXT_TRANSLATION","featureCode":"","decision":"ACCEPTED",
                     "artifactType":"TRANSLATION","artifactId":"%s","artifactRef":"task-1"}
                    """
                        .formatted(artifact)
                        .replace("\n", "")))
        .andExpect(status().isConflict());
  }

  // ==========================================================================
  // 5. 有用性反馈
  // ==========================================================================
  @Test
  void 有用性反馈三档都能登记并按维度聚合() throws Exception {
    recordFeedback("session-admin", "ASSISTANT", "chat", "USEFUL", "INACCURATE");
    recordFeedback("session-admin", "ASSISTANT", "chat", "PARTIALLY_USEFUL", "TOO_VERBOSE");
    recordFeedback("session-admin", "ASSISTANT", "chat", "NOT_USEFUL", "MISSING_CONTEXT");

    var overview = overview();
    assertThat(overview.usefulness().total()).isEqualTo(3L);
    assertThat(overview.usefulness().usefulCount()).isEqualTo(1L);
    assertThat(overview.usefulness().partiallyUsefulCount()).isEqualTo(1L);
    assertThat(overview.usefulness().notUsefulCount()).isEqualTo(1L);
    assertThat(overview.usefulness().usefulRate()).isEqualTo("0.333333");
    // 🔴 正向 = 有用 + 部分有用 ⇒ 2/3。
    assertThat(overview.usefulness().positiveRate()).isEqualTo("0.666667");
  }

  @Test
  void 理由码必须是受控词表里的码() throws Exception {
    http.perform(
            post("/platform/ai/effects/feedback")
                .header("Authorization", "session-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"capabilityCode":"ASSISTANT","featureCode":"chat","usefulness":"NOT_USEFUL",
                     "reasonCode":"随便编一个","artifactType":"ASSISTANT"}
                    """
                        .replace("\n", "")))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 6. 预测准确度回测
  // ==========================================================================
  @Test
  void 回测的实际值来自真实销量而不是预测值自己() throws Exception {
    seedForecastRun("SKU-1", "100", "80", "120", 7);
    // 预测期内的真实销量 = 60。
    seedDemand("SKU-1", 60);

    var result = runBacktest();
    assertThat(result.evaluatedCount()).isEqualTo(1L);
    assertThat(result.comparedCount()).isEqualTo(1L);
    assertThat(result.noActualSourceCount()).isZero();

    var item = result.items().get(0);
    assertThat(item.forecastPoint()).isEqualTo("100.000000");
    assertThat(item.actualUnits()).as("实际值 = 真实销量求和").isEqualTo("60.000000");
    assertThat(item.actualSource()).isEqualTo("platform_forecast_demand_history.units");
    assertThat(item.absoluteError()).as("|实际 - 预测| = 40").isEqualTo("40.000000");
    assertThat(item.relativeError()).as("40 / 60").isEqualTo("0.666667");
    assertThat(item.withinInterval()).as("60 落在 [80,120] 之外").isFalse();
    assertThat(item.status()).isEqualTo("COMPARED");
    // 🔴 MAPE 只算**实际值非 0** 的行。
    assertThat(result.mape()).isEqualTo("0.666667");
    assertThat(result.hitRate()).isEqualTo("0.000000");
  }

  @Test
  void 没有实际值来源时如实记NO_ACTUAL_SOURCE且不记零() throws Exception {
    seedForecastRun("SKU-2", "100", "80", "120", 7);
    // 🔴 一条销量都没有 ⇒ NO_ACTUAL_SOURCE，actual_units 留 NULL（**不记 0**）。
    var result = runBacktest();
    assertThat(result.noActualSourceCount()).isEqualTo(1L);
    assertThat(result.comparedCount()).isZero();
    var item = result.items().get(0);
    assertThat(item.status()).isEqualTo("NO_ACTUAL_SOURCE");
    assertThat(item.actualUnits()).as("没有实际值来源 ⇒ 留 null，不是 0").isNull();
    assertThat(item.absoluteError()).isNull();
    assertThat(item.relativeError()).isNull();
    assertThat(item.note()).contains("无法回测，缺实际值来源");
    assertThat(result.mape()).as("没有可比行 ⇒ MAPE 是 null").isNull();
  }

  @Test
  void 实际值为零时相对误差不可计算() throws Exception {
    seedForecastRun("SKU-3", "100", "80", "120", 7);
    // 有销量记录，但销量是 0 ⇒ 实际值 = 0（**不是**缺数据）。
    seedDemand("SKU-3", 0);
    var result = runBacktest();
    var item = result.items().get(0);
    assertThat(item.status()).isEqualTo("COMPARED");
    assertThat(item.actualUnits()).isEqualTo("0.000000");
    assertThat(item.relativeError()).as("实际值为 0 ⇒ 相对误差不可计算 ⇒ null").isNull();
    assertThat(item.absoluteError()).isEqualTo("100.000000");
    // 🔴 实际值为 0 的行**不参与** MAPE。
    assertThat(result.mape()).isNull();
  }

  // ==========================================================================
  // 7. 低价值判定要样本量达标
  // ==========================================================================
  @Test
  void 样本不足时不下低价值结论() throws Exception {
    // 只有 3 次调用（默认门槛 20）⇒ 数据不足，**不是**低价值。
    seedUsage("TEXT_TRANSLATION", "");
    seedUsage("TEXT_TRANSLATION", "");
    seedUsage("TEXT_TRANSLATION", "");

    var report = adoptionReport();
    assertThat(report.items().get(0).calls()).isEqualTo(3L);
    assertThat(report.items().get(0).verdict()).isEqualTo("INSUFFICIENT_DATA");
    assertThat(report.items().get(0).reason()).contains("样本不足");
  }

  @Test
  void 样本足够且采纳率低于阈值时判低价值() throws Exception {
    for (int i = 0; i < 25; i++) seedUsage("TEXT_TRANSLATION", "");
    // 采纳 2 次 ⇒ 2/25 = 0.08 < 0.2。
    recordAdoption("session-admin", "TEXT_TRANSLATION", "", "ACCEPTED", UUID.randomUUID());
    recordAdoption("session-admin", "TEXT_TRANSLATION", "", "MODIFIED", UUID.randomUUID());

    var report = adoptionReport();
    assertThat(report.items().get(0).verdict()).isEqualTo("LOW_VALUE");
    assertThat(report.items().get(0).adoptionRate()).isEqualTo("0.080000");

    // 概览里也要出现在低价值清单中。
    var overview = overview();
    assertThat(overview.adoption().lowValueFeatures()).isEqualTo(1L);
    assertThat(overview.lowValueFeatures()).hasSize(1);
    assertThat(overview.lowValueFeatures().get(0).verdict()).isEqualTo("LOW_VALUE");
  }

  @Test
  void 阈值可以由租户自己维护() throws Exception {
    var before = thresholds();
    assertThat(before.configured()).as("库里没登记 ⇒ 用系统默认阈值").isFalse();
    assertThat(before.minCalls()).isEqualTo(20L);

    http.perform(
            post("/platform/ai/effects/thresholds")
                .header("Authorization", "session-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"minCalls\":5,\"lowAdoptionRate\":\"0.5\"}"))
        .andExpect(status().isOk());

    var after = thresholds();
    assertThat(after.configured()).as("登记之后 ⇒ 是租户自己定的").isTrue();
    assertThat(after.minCalls()).isEqualTo(5L);
    assertThat(after.lowAdoptionRate()).isEqualTo("0.500000");
  }

  // ==========================================================================
  // 8. 「建议下线」只是建议
  // ==========================================================================
  @Test
  void 建议下线只是建议不执行下线() throws Exception {
    for (int i = 0; i < 25; i++) seedUsage("TEXT_TRANSLATION", "");
    var generated = generateSuggestions();
    assertThat(generated.created()).isEqualTo(1L);

    var suggestions = suggestions();
    assertThat(suggestions.items()).hasSize(1);
    var suggestion = suggestions.items().get(0);
    assertThat(suggestion.suggestionType()).isEqualTo("OFFLINE_CANDIDATE");
    assertThat(suggestion.currentMetric()).contains("调用 25 次");
    assertThat(suggestion.evidence()).contains("platform_ai_effect_adoption");
    // 🔴 offline_action 必须如实写明「需人工执行」—— 本仓没有 AI 功能开关。
    assertThat(suggestion.offlineAction()).contains("需人工执行");
    assertThat(suggestion.offlineAction()).contains("只出建议清单");

    // 🔴 **本模块没有任何下线端点** —— 假装存在比不做更糟。
    http.perform(
            post("/platform/ai/effects/suggestions/" + suggestion.id() + "/disable")
                .header("Authorization", "session-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 建议可以记录人工决策但只记录不执行() throws Exception {
    for (int i = 0; i < 25; i++) seedUsage("TEXT_TRANSLATION", "");
    generateSuggestions();
    var suggestion = suggestions().items().get(0);

    http.perform(
            post("/platform/ai/effects/suggestions/" + suggestion.id() + "/decision")
                .header("Authorization", "session-admin")
                .param("decision", "ACCEPTED")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"确认没人用\"}"))
        .andExpect(status().isOk());

    var after = suggestions();
    assertThat(after.items().get(0).status()).isEqualTo("ACCEPTED");
    assertThat(after.items().get(0).decisionNote()).isEqualTo("确认没人用");
    assertThat(after.items().get(0).decidedBy()).isEqualTo(MEMBER_A);
  }

  @Test
  void 条件不再成立时旧建议被自动关闭() throws Exception {
    for (int i = 0; i < 25; i++) seedUsage("TEXT_TRANSLATION", "");
    generateSuggestions();
    assertThat(suggestions().items().get(0).status()).isEqualTo("OPEN");

    // 把门槛降到 1 次调用 ⇒ 采纳率 0/25 = 0 仍 < 0.2，条件仍成立 ⇒ 不该关。
    // 反过来：把低价值阈值抬到 0 ⇒ 采纳率 0 ≥ 0，条件不再成立 ⇒ 自动关闭。
    http.perform(
            post("/platform/ai/effects/thresholds")
                .header("Authorization", "session-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lowAdoptionRate\":\"0\"}"))
        .andExpect(status().isOk());
    var refreshed = generateSuggestions();
    assertThat(refreshed.resolved()).isEqualTo(1L);
    assertThat(suggestions().items().get(0).status()).isEqualTo("RESOLVED");
  }

  // ==========================================================================
  // 9. 助手答案有用性反馈如实答不可用
  // ==========================================================================
  @Test
  void 助手答案有用性反馈如实答不可用并说明归属() throws Exception {
    var capabilities = capabilities();
    var assistant =
        capabilities.capabilities().stream()
            .filter(c -> c.name().contains("助手答案有用性反馈"))
            .findFirst()
            .orElseThrow();
    assertThat(assistant.available()).as("18.4.1.1 已熔断未交付 ⇒ 评估对象不可用").isFalse();
    assertThat(assistant.ownerModule()).isEqualTo("18.4.1.1");
    assertThat(assistant.basis()).contains("不伪造");

    // 缺口里也要点名归谁。
    assertThat(capabilities.downstreamGaps()).isNotEmpty();
    assertThat(capabilities.downstreamGaps().stream().anyMatch(g -> "18.4.1.1".equals(g.module())))
        .as("能力声明必须点名 18.4.1.1 未交付")
        .isTrue();
  }

  @Test
  void 能力声明逐条报出有没有依据是什么缺了归谁() throws Exception {
    var capabilities = capabilities();
    assertThat(capabilities.capabilities()).isNotEmpty();
    for (var c : capabilities.capabilities()) {
      assertThat(c.available()).isNotNull();
      assertThat(c.basis()).as("每一维都要写清依据").isNotBlank();
      assertThat(c.module()).isNotBlank();
    }
    // 🔴 「建议下线」不是「下线」—— 下线动作要如实说没有。
    assertThat(
            capabilities.downstreamGaps().stream()
                .anyMatch(g -> g.name().contains("下线开关")))
        .as("必须如实报出本仓没有按能力粒度的 AI 功能开关")
        .isTrue();
  }

  // ==========================================================================
  // 10. 权限：只读的人录不进来
  // ==========================================================================
  @Test
  void 只读角色录不进事实台账() throws Exception {
    http.perform(
            post("/platform/ai/effects/adoptions")
                .header("Authorization", "session-readonly")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"capabilityCode":"TEXT_TRANSLATION","featureCode":"","decision":"ACCEPTED",
                     "artifactType":"ASSISTANT","artifactRef":"x"}
                    """
                        .replace("\n", "")))
        .andExpect(status().isForbidden());
  }

  // ================================================================ 辅助

  AiAdoptionReportPage adoptionReport() throws Exception {
    return json.readValue(
        http.perform(get("/platform/ai/effects/adoption-report").header("Authorization", "session-admin"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        AiAdoptionReportPage.class);
  }

  AiEffectOverview overview() throws Exception {
    return json.readValue(
        http.perform(get("/platform/ai/effects/overview").header("Authorization", "session-admin"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        AiEffectOverview.class);
  }

  AiEffectThresholdRow thresholds() throws Exception {
    return json.readValue(
        http.perform(get("/platform/ai/effects/thresholds").header("Authorization", "session-admin"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        AiEffectThresholdRow.class);
  }

  AiEffectCapabilities capabilities() throws Exception {
    return json.readValue(
        http.perform(get("/platform/ai/effects/capabilities").header("Authorization", "session-admin"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        AiEffectCapabilities.class);
  }

  AiSuggestionPage suggestions() throws Exception {
    return json.readValue(
        http.perform(get("/platform/ai/effects/suggestions").header("Authorization", "session-admin"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        AiSuggestionPage.class);
  }

  AiSuggestionGenerateResult generateSuggestions() throws Exception {
    return json.readValue(
        http.perform(
                post("/platform/ai/effects/suggestions/generate")
                    .header("Authorization", "session-admin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        AiSuggestionGenerateResult.class);
  }

  AiBacktestRunResult runBacktest() throws Exception {
    return json.readValue(
        http.perform(
                post("/platform/ai/effects/backtests/run")
                    .header("Authorization", "session-admin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        AiBacktestRunResult.class);
  }

  /** 造一次预测运行 + 一条预测结果（18.4.2.2 的表，本模块**只读**）。 */
  UUID seedForecastRun(String skuCode, String point, String lower, String upper, int horizonDays) {
    return tenants.run(
        TENANT,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          var runId = UUID.randomUUID();
          var resultId = UUID.randomUUID();
          sql.execute(
              "insert into platform_forecast_run(id,tenant_id,run_no,algorithm,ma_window_days,"
                  + " smoothing_alpha,window_days,horizon_days,confidence_level,z_value,"
                  + " history_source,status,evaluated_sku_count,forecast_sku_count,"
                  + " skipped_sku_count,skipped_reason,inventory_connected,in_transit_connected,"
                  + " purchase_connected,note,requested_by,started_at,finished_at)"
                  + " values(?,?,?,'MOVING_AVERAGE',3,0.3,30,?,0.95,1.96,'MANUAL','COMPLETED',"
                  + " 1,1,0,'',false,false,false,'',null,?::timestamptz,?::timestamptz)",
              runId,
              TENANT,
              "RUN-" + runId.toString().substring(0, 6),
              horizonDays,
              now,
              now);
          sql.execute(
              "insert into platform_forecast_result(id,tenant_id,run_id,sku_code,sku_name,"
                  + " algorithm,ma_window_days,smoothing_alpha,window_days,horizon_days,"
                  + " history_source,sample_days,coverage_rate,baseline_daily,promo_factor,"
                  + " point,lower,upper,sigma,z_value,confidence_level,created_at)"
                  + " values(?,?,?,?,'SKU','MOVING_AVERAGE',3,0.3,30,?,'MANUAL',30,1.0,"
                  + " 10,1,?,?,?,10,1.96,0.95,?::timestamptz)",
              resultId,
              TENANT,
              runId,
              skuCode,
              horizonDays,
              new java.math.BigDecimal(point),
              new java.math.BigDecimal(lower),
              new java.math.BigDecimal(upper),
              now);
          return runId;
        });
  }

  /** 造一条真实销量（18.4.2.2 的表，本模块**只读**）。 */
  void seedDemand(String skuCode, double units) {
    tenants.run(
        TENANT,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          // 🔴 预测期 = [运行时刻的 UTC 日期 + 1 天, ...] —— 与服务层**同一口径**算出来，
          //   不靠 DB 会话时区（会话时区一变，这条销量就落进窗口外了）。
          var businessDate = now.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).plusDays(1);
          sql.execute(
              "insert into platform_forecast_demand_history(id,tenant_id,sku_code,sku_name,"
                  + " business_date,units,source,source_ref,recorded_at)"
                  + " values(?,?,?,'SKU',?::date,?,'MANUAL','test',?::timestamptz)",
              UUID.randomUUID(),
              TENANT,
              skuCode,
              businessDate.toLocalDate(),
              new java.math.BigDecimal(units),
              now);
          return null;
        });
  }
}
