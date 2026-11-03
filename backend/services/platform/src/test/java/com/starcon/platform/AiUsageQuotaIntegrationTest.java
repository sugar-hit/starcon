package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AiUsageQuotaModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * 18.4.3.1「使用与配额」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>本测试盯住九件事：
 *
 * <ol>
 *   <li><b>成本不硬编码 + 单价快照</b>：改目录价**不动**历史台账；
 *   <li><b>量纲纪律</b>：以「次」为量纲的模型，调用量只能是 1；
 *   <li><b>请求体不接受归属</b>：tenantId / userId / membershipId 一律 400；
 *   <li><b>配额超限说清五要素</b>：超了哪一条、当前用量、阈值、周期、什么时候重置；
 *   <li><b>用户级配额只管那个人</b>；
 *   <li><b>配额计数的并发正确性</b>：并发记用量，计数**一条不丢**；
 *   <li><b>用量台账只追加</b>：UPDATE / DELETE 都被触发器拒绝；
 *   <li><b>报表不混算</b>：不同模型 / 不同币种不加成一个总数；
 *   <li><b>敏感策略的生效判定</b>：更具体的那条生效，没登记落到默认档。
 * </ol>
 *
 * <p>租户隔离的三条断言在 {@code AiUsageQuotaTenancyIntegrationTest} 里（同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiUsageQuotaIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final UUID TENANT = UUID.randomUUID();
  static final UUID OTHER_TENANT = UUID.randomUUID();
  static final UUID MEMBER_A = UUID.randomUUID();
  static final UUID MEMBER_B = UUID.randomUUID();

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
        List.of("platform.ai-usage.read", "platform.ai-usage.record", "platform.ai-usage.update");
    actors.put("session-admin", actor(UUID.randomUUID(), TENANT, MEMBER_A, all));
    actors.put("session-member-a", actor(UUID.randomUUID(), TENANT, MEMBER_A, all));
    actors.put("session-member-b", actor(UUID.randomUUID(), TENANT, MEMBER_B, all));
    actors.put("session-readonly", actor(UUID.randomUUID(), TENANT, MEMBER_A, List.of("platform.ai-usage.read")));
    actors.put("session-other", actor(UUID.randomUUID(), OTHER_TENANT, UUID.randomUUID(), all));
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
      // 并发用例要同时打 8 条请求 ⇒ 线程池给够，别让夹具自己变成瓶颈。
      identity.setExecutor(Executors.newFixedThreadPool(16));
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
          "truncate table platform_ai_sensitive_policy, platform_ai_quota_usage, platform_ai_quota,"
              + " platform_ai_usage_record, platform_ai_model, platform_app_audit_event,"
              + " platform_app_quota_usage, platform_app_quota, platform_app_ip_allowlist,"
              + " platform_app_scope, platform_app_credential, platform_app restart identity cascade");
    }
    // 目录里预置两个模型：一个按「次」计量，一个按「Token」计量。
    saveModel("session-admin", "call-model", "按次计量的模型", "REQUEST", "0.5");
    saveModel("session-admin", "token-model", "按 Token 计量的模型", "TOKEN", "0.002");
  }

  AiModelRow saveModel(String session, String code, String name, String unit, String cost) throws Exception {
    var body =
        """
        {"modelCode":"%s","modelName":"%s","provider":"TEST","unit":"%s","unitCost":"%s",
         "currency":"USD","contextLimit":8192,"enabled":true,"note":""}
        """
            .formatted(code, name, unit, cost)
            .replace("\n", "");
    var response =
        http.perform(
                post("/platform/ai/usage/models")
                    .header("Authorization", session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    try {
      return json.readValue(response.getContentAsString(), AiModelRow.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 记一次用量，返回后端给的完整结果。 */
  AiUsageRecordResult recordUsage(String session, String capability, String model, String quantity) throws Exception {
    var body =
        """
        {"capabilityCode":"%s","featureCode":"","modelCode":"%s","quantity":"%s","requestRef":"req-1"}
        """
            .formatted(capability, model, quantity)
            .replace("\n", "");
    var response =
        http.perform(
                post("/platform/ai/usage/records")
                    .header("Authorization", session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    try {
      return json.readValue(response.getContentAsString(), AiUsageRecordResult.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  AiQuotaRow saveQuota(
      String session,
      String path,
      String capability,
      String limitCount,
      String limitCost,
      String period,
      String currency)
      throws Exception {
    var body =
        """
        {"capabilityCode":"%s","limitCount":%s,"limitCost":%s,"currency":"%s","period":"%s",
         "enabled":true,"note":""}
        """
            .formatted(
                capability,
                limitCount == null ? "null" : limitCount,
                limitCost == null ? "null" : "\"" + limitCost + "\"",
                currency == null ? "USD" : currency,
                period)
            .replace("\n", "");
    var response =
        http.perform(
                post(path)
                    .header("Authorization", session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    try {
      return json.readValue(response.getContentAsString(), AiQuotaRow.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ==========================================================================
  // 1. 成本不硬编码 + 单价快照
  // ==========================================================================
  @Test
  void 成本按目录单价算且改价不动历史() throws Exception {
    var result = recordUsage("session-admin", "TEXT_TRANSLATION", "token-model", "1500");
    assertThat(result.record().cost()).isEqualTo("3.000000");
    assertThat(result.record().unitCost()).isEqualTo("0.002000");
    assertThat(result.record().unit()).isEqualTo("TOKEN");
    assertThat(result.record().currency()).isEqualTo("USD");

    // 目录改价：0.002 → 0.005
    saveModel("session-admin", "token-model", "按 Token 计量的模型", "TOKEN", "0.005");

    var again = recordUsage("session-admin", "TEXT_TRANSLATION", "token-model", "1500");
    assertThat(again.record().cost()).isEqualTo("7.500000");

    // 🔴 历史那一行**不动** —— 台账里存的是记这一行那一刻的单价快照。
    //    直读必须放在可信租户事务里：RLS 下没有租户上下文就一行都看不见。
    var historical =
        tenants.run(
            TENANT,
            () ->
                sql.fetchOne(
                        "select cost from platform_ai_usage_record where id = ?",
                        result.record().id())
                    .get("cost", java.math.BigDecimal.class));
    assertThat(historical).isEqualByComparingTo("3.000000");
    var historicalUnitCost =
        tenants.run(
            TENANT,
            () ->
                sql.fetchOne(
                        "select unit_cost from platform_ai_usage_record where id = ?",
                        result.record().id())
                    .get("unit_cost", java.math.BigDecimal.class));
    assertThat(historicalUnitCost).isEqualByComparingTo("0.002000");
  }

  @Test
  void 发生时刻写回且与库里一致() throws Exception {
    var result = recordUsage("session-admin", "TEXT_TRANSLATION", "call-model", "1");
    assertThat(result.record().occurredAt()).isNotNull();
    var stored =
        tenants.run(
            TENANT,
            () ->
                sql.fetchOne(
                        "select occurred_at from platform_ai_usage_record where id = ?",
                        result.record().id())
                    .get("occurred_at", OffsetDateTime.class));
    // ⚠️ 断言写回值（不是只断言「不空」）：timestamptz 往返要一模一样。
    assertThat(stored.toInstant()).isEqualTo(result.record().occurredAt().toInstant());
  }

  // ==========================================================================
  // 2. 量纲纪律
  // ==========================================================================
  @Test
  void 以次为量纲的模型调用量只能是1() throws Exception {
    http.perform(
            post("/platform/ai/usage/records")
                .header("Authorization", "session-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"capabilityCode":"TEXT_TRANSLATION","modelCode":"call-model","quantity":"0.5"}
                    """))
        .andExpect(status().isBadRequest());

    var ok = recordUsage("session-admin", "TEXT_TRANSLATION", "call-model", "1");
    assertThat(ok.record().quantity()).isEqualTo("1.000000");
  }

  @Test
  void 未登记的模型不能记用量() throws Exception {
    http.perform(
            post("/platform/ai/usage/records")
                .header("Authorization", "session-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"capabilityCode":"TEXT_TRANSLATION","modelCode":"not-registered","quantity":"1"}
                    """))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 3. 请求体不接受归属
  // ==========================================================================
  @Test
  void 请求体不接受归属字段() throws Exception {
    for (String field : List.of("tenantId", "userId", "membershipId")) {
      http.perform(
              post("/platform/ai/usage/records")
                  .header("Authorization", "session-admin")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {"capabilityCode":"TEXT_TRANSLATION","modelCode":"call-model","quantity":"1",
                       "%s":"%s"}
                      """
                          .formatted(field, TENANT)))
          .andExpect(status().isBadRequest());
    }
    // 配额与策略的请求体同样不接受。
    http.perform(
            post("/platform/ai/usage/quotas")
                .header("Authorization", "session-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"capabilityCode":"TEXT_TRANSLATION","limitCount":10,"period":"DAY",
                     "membershipId":"%s"}
                    """
                        .formatted(MEMBER_A)))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 4. 配额超限说清五要素
  // ==========================================================================
  @Test
  void 次数超限说清超了哪一条当前用量阈值周期与重置时间() throws Exception {
    var quota =
        saveQuota(
            "session-admin",
            "/platform/ai/usage/quotas",
            "TEXT_TRANSLATION",
            "2",
            null,
            "DAY",
            null);
    assertThat(quota.scope()).isEqualTo("TENANT");
    assertThat(quota.limitCount()).isEqualTo(2L);

    var first = recordUsage("session-admin", "TEXT_TRANSLATION", "call-model", "1");
    assertThat(first.evaluation().allowed()).isTrue();
    var second = recordUsage("session-admin", "TEXT_TRANSLATION", "call-model", "1");
    assertThat(second.evaluation().allowed()).isTrue();

    var third = recordUsage("session-admin", "TEXT_TRANSLATION", "call-model", "1");
    assertThat(third.evaluation().allowed()).isFalse();
    assertThat(third.evaluation().mode()).isEqualTo("POST_RECORD");
    assertThat(third.evaluation().violations()).hasSize(1);

    var v = third.evaluation().violations().get(0);
    // 🔴 五个要素缺一不可。
    assertThat(v.quotaId()).isEqualTo(quota.id());
    assertThat(v.scope()).isEqualTo("TENANT");
    assertThat(v.capabilityCode()).isEqualTo("TEXT_TRANSLATION");
    assertThat(v.limitKind()).isEqualTo("COUNT");
    assertThat(v.limitValue()).isEqualTo("2");
    assertThat(v.currentUsage()).isEqualTo("3");
    assertThat(v.period()).isEqualTo("DAY");
    assertThat(v.periodStart()).isNotBlank();
    assertThat(v.resetAt()).isNotBlank();
    assertThat(v.resetAt()).isEqualTo(v.periodEnd());
    assertThat(v.message()).contains("次数上限").contains("3").contains("重置");
  }

  @Test
  void 成本上限越线也说清五要素() throws Exception {
    var quota =
        saveQuota(
            "session-admin",
            "/platform/ai/usage/quotas",
            "TOKEN_WORK",
            null,
            "1.000000",
            "MONTH",
            null);
    assertThat(quota.costConfigured()).isTrue();

    // 单价 0.002 × 1500 Token = 3.000000 > 1.000000 ⇒ 第一笔就越线。
    var result = recordUsage("session-admin", "TOKEN_WORK", "token-model", "1500");
    assertThat(result.evaluation().allowed()).isFalse();
    var v = result.evaluation().violations().get(0);
    assertThat(v.limitKind()).isEqualTo("COST");
    assertThat(v.limitValue()).isEqualTo("1.000000");
    assertThat(v.currentUsage()).isEqualTo("3.000000");
    assertThat(v.period()).isEqualTo("MONTH");
    assertThat(v.resetAt()).isNotBlank();
  }

  @Test
  void 成本上限按本条配额的币种计量别的币种只计次数() throws Exception {
    // 一条 **EUR** 的成本上限；台账里的调用都是 USD ⇒ 成本**不计入**这条配额。
    var quota =
        saveQuota(
            "session-admin",
            "/platform/ai/usage/quotas",
            "MIXED_CUR",
            null,
            "1.000000",
            "DAY",
            "EUR");
    assertThat(quota.currency()).isEqualTo("EUR");

    var result = recordUsage("session-admin", "MIXED_CUR", "token-model", "1500");
    assertThat(result.record().cost()).isEqualTo("3.000000");
    assertThat(result.record().currency()).isEqualTo("USD");
    // 🔴 不同币种不混算：这条 EUR 的成本上限**没有被这次 USD 调用顶穿**。
    assertThat(result.evaluation().allowed()).isTrue();
    assertThat(result.evaluation().violations()).isEmpty();

    // 但**次数**照样计 —— 次数与币种无关。
    var refreshed = quotasOf("session-admin").stream()
        .filter(q -> q.id().equals(quota.id()))
        .findFirst()
        .orElseThrow();
    assertThat(refreshed.usedCount()).isEqualTo(1L);
    assertThat(refreshed.usedCost()).isEqualTo("0.000000");
  }

  @Test
  void 周期边界是确定的Utc时刻() throws Exception {
    var day =
        saveQuota("session-admin", "/platform/ai/usage/quotas", "DAY_CAP", "100", null, "DAY", null);
    assertThat(day.periodStart()).endsWith("T00:00:00Z");
    assertThat(day.periodEnd()).endsWith("T00:00:00Z");
    assertThat(OffsetDateTime.parse(day.periodStart()).plusDays(1).toInstant().toString())
        .isEqualTo(day.periodEnd());

    var month =
        saveQuota("session-admin", "/platform/ai/usage/quotas", "MONTH_CAP", "100", null, "MONTH", null);
    assertThat(month.periodStart()).matches("\\d{4}-\\d{2}-01T00:00:00Z");
    assertThat(OffsetDateTime.parse(month.periodStart()).plusMonths(1).toInstant().toString())
        .isEqualTo(month.periodEnd());
  }

  /** 读配额列表。 */
  List<AiQuotaRow> quotasOf(String session) throws Exception {
    var response =
        http.perform(get("/platform/ai/usage/quotas").header("Authorization", session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return List.of(json.readValue(response.getContentAsString(), AiQuotaRow[].class));
  }

  // ==========================================================================
  // 5. 用户级配额只管那个人
  // ==========================================================================
  @Test
  void 用户级配额只算那个人() throws Exception {
    var quota =
        saveQuota(
            "session-admin",
            "/platform/ai/usage/members/" + MEMBER_A + "/quotas",
            "TEXT_TRANSLATION",
            "1",
            null,
            "DAY",
            null);
    assertThat(quota.scope()).isEqualTo("USER");
    assertThat(quota.membershipId()).isEqualTo(MEMBER_A);

    var a1 = recordUsage("session-member-a", "TEXT_TRANSLATION", "call-model", "1");
    assertThat(a1.evaluation().allowed()).isTrue();

    var a2 = recordUsage("session-member-a", "TEXT_TRANSLATION", "call-model", "1");
    assertThat(a2.evaluation().allowed()).isFalse();
    assertThat(a2.evaluation().violations().get(0).membershipId()).isEqualTo(MEMBER_A);

    // 🔴 另一个成员**不受**这条配额约束（主体是具体的人，不是「所有人」）。
    var b1 = recordUsage("session-member-b", "TEXT_TRANSLATION", "call-model", "1");
    assertThat(b1.evaluation().allowed()).isTrue();
    assertThat(b1.evaluation().violations()).isEmpty();
  }

  // ==========================================================================
  // 6. 配额计数的并发正确性
  // ==========================================================================
  @Test
  void 并发记用量计数一条不丢() throws Exception {
    saveQuota("session-admin", "/platform/ai/usage/quotas", "CONCURRENT", "1000", null, "DAY", null);

    var threads = new ArrayList<Thread>();
    var failures = new java.util.concurrent.atomic.AtomicInteger();
    for (int i = 0; i < 8; i++) {
      var t =
          new Thread(
              () -> {
                try {
                  recordUsage("session-member-a", "CONCURRENT", "call-model", "1");
                } catch (Exception e) {
                  failures.incrementAndGet();
                }
              });
      threads.add(t);
      t.start();
    }
    for (var t : threads) t.join();
    assertThat(failures.get()).as("并发请求本身不该失败").isZero();

    var row =
        tenants.run(
            TENANT,
            () ->
                sql.fetchOne(
                    "select used_count, used_cost from platform_ai_quota_usage"
                        + " join platform_ai_quota q on q.id = platform_ai_quota_usage.quota_id"
                        + " where q.capability_code = 'CONCURRENT'"));
    assertThat(row).isNotNull();
    // 🔴 8 次并发 ⇒ 计数必须是 8。读后写的实现这里会小于 8（丢计数）。
    assertThat(row.get("used_count", Long.class)).isEqualTo(8L);
    assertThat(row.get("used_cost", java.math.BigDecimal.class))
        .isEqualByComparingTo("4.000000");

    var records =
        tenants.run(
            TENANT,
            () ->
                sql.fetchOne(
                        "select count(*) from platform_ai_usage_record where capability_code = 'CONCURRENT'")
                    .get(0, Long.class));
    assertThat(records).isEqualTo(8L);
  }

  // ==========================================================================
  // 7. 用量台账只追加
  // ==========================================================================
  @Test
  void 用量台账不许改也不许删() throws Exception {
    var result = recordUsage("session-admin", "TEXT_TRANSLATION", "call-model", "1");
    var id = result.record().id();

    // 🔴 第一道闸：**运行角色连权限都没有**（REVOKE UPDATE / DELETE）。
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('platform_app','platform_ai_usage_record','UPDATE')")
                .get(0, Boolean.class))
        .isFalse();
    assertThatThrownBy(
            () ->
                tenants.run(
                    TENANT,
                    () ->
                        sql.execute(
                            "update platform_ai_usage_record set cost = 0 where id = ?", id)))
        .as("运行角色根本不该有改写用量历史的权限")
        .hasStackTraceContaining("permission denied");

    // 🔴 第二道闸：**触发器**。用**属主身份**（超管）连进来 ——
    //    少授权限码会被「用属主身份跑」绕开，触发器不会。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var st = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  st.execute(
                      "update platform_ai_usage_record set cost = 0 where id = '" + id + "'"))
          .as("改写用量历史等于伪造历史")
          .hasMessageContaining("只追加");
      assertThatThrownBy(
              () -> st.execute("delete from platform_ai_usage_record where id = '" + id + "'"))
          .as("删掉用量历史等于没有历史")
          .hasMessageContaining("只追加");
    }

    var still =
        tenants.run(
            TENANT,
            () ->
                sql.fetchOne(
                        "select cost from platform_ai_usage_record where id = ?", id)
                    .get("cost", java.math.BigDecimal.class));
    assertThat(still).isEqualByComparingTo("0.500000");
  }

  // ==========================================================================
  // 8. 报表不混算
  // ==========================================================================
  @Test
  void 报表按能力乘模型给可计费粒度且不混算() throws Exception {
    recordUsage("session-admin", "TEXT_TRANSLATION", "token-model", "1500");
    recordUsage("session-admin", "TEXT_TRANSLATION", "call-model", "1");
    recordUsage("session-member-b", "TITLE_OPTIMIZATION", "call-model", "1");

    var response =
        http.perform(get("/platform/ai/usage/summary").header("Authorization", "session-admin"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var summary = json.readValue(response.getContentAsString(), AiUsageSummary.class);

    assertThat(summary.totalCalls()).isEqualTo(3L);
    // 两个能力 × 各自的模型 ⇒ 两行，且**不**把不同量纲加成一个总数。
    assertThat(summary.byCapability()).hasSize(3);
    assertThat(summary.byCapability())
        .extracting(AiUsageSummaryRow::capabilityCode)
        .containsExactly("TEXT_TRANSLATION", "TEXT_TRANSLATION", "TITLE_OPTIMIZATION");
    // 每个币种一行（不同币种不混算）。
    assertThat(summary.byCurrency()).hasSize(1);
    assertThat(summary.byCurrency().get(0).currency()).isEqualTo("USD");
    assertThat(summary.byCurrency().get(0).calls()).isEqualTo(3L);
    // 成员维度也按币种分开。
    assertThat(summary.byMember()).hasSize(2);
    assertThat(summary.billableBasis()).contains("能力").contains("模型").contains("币种");
  }

  // ==========================================================================
  // 9. 敏感数据策略的生效判定
  // ==========================================================================
  @Test
  void 敏感策略更具体的那条生效没登记落到默认档() throws Exception {
    // 默认档：库里没登记 ⇒ 允许，并如实说明。
    var effective = effectivePolicy("session-admin", "TEXT_TRANSLATION", "token-model");
    assertThat(effective.configured()).isFalse();
    assertThat(effective.action()).isEqualTo("ALLOW");
    assertThat(effective.basis()).contains("没有登记").contains("各 AI 能力");

    // 登记一条「能力 × 全部模型」= BLOCK
    http.perform(
            post("/platform/ai/usage/policies")
                .header("Authorization", "session-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"capabilityCode":"TEXT_TRANSLATION","modelCode":"","action":"BLOCK","enabled":true,
                     "note":"含客户身份证号"}
                    """))
        .andExpect(status().isOk());

    var wildcard = effectivePolicy("session-admin", "TEXT_TRANSLATION", "token-model");
    assertThat(wildcard.configured()).isTrue();
    assertThat(wildcard.action()).isEqualTo("BLOCK");
    assertThat(wildcard.modelCode()).isEmpty();

    // 对个别模型改成 MASK ⇒ 更具体的那条生效。
    http.perform(
            post("/platform/ai/usage/policies")
                .header("Authorization", "session-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"capabilityCode":"TEXT_TRANSLATION","modelCode":"token-model","action":"MASK",
                     "enabled":true,"note":""}
                    """))
        .andExpect(status().isOk());

    var specific = effectivePolicy("session-admin", "TEXT_TRANSLATION", "token-model");
    assertThat(specific.action()).isEqualTo("MASK");
    assertThat(specific.modelCode()).isEqualTo("token-model");
  }

  AiSensitivePolicyView effectivePolicy(String session, String capability, String model) throws Exception {
    var response =
        http.perform(
                get("/platform/ai/usage/policies/effective")
                    .header("Authorization", session)
                    .param("capabilityCode", capability)
                    .param("modelCode", model))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return json.readValue(response.getContentAsString(), AiSensitivePolicyView.class);
  }

  // ==========================================================================
  // 10. 能力声明与权限
  // ==========================================================================
  @Test
  void 能力声明如实写明敏感策略的执行点() throws Exception {
    var response =
        http.perform(get("/platform/ai/usage/capabilities").header("Authorization", "session-admin"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var caps = json.readValue(response.getContentAsString(), AiUsageCapabilities.class);

    var policy =
        caps.capabilities().stream()
            .filter(c -> c.name().contains("敏感数据处理策略"))
            .findFirst()
            .orElseThrow();
    assertThat(policy.available()).isTrue();
    assertThat(policy.basis()).contains("AI 能力自己在调用前查").contains("没有统一的 AI 出站拦截点");

    // 🔴 「全局拦截」如实答不可用，并写明缺在谁身上。
    var global =
        caps.downstreamGaps().stream()
            .filter(g -> g.name().contains("全局 AI 出站拦截点"))
            .findFirst()
            .orElseThrow();
    assertThat(global.available()).isFalse();
    assertThat(global.detail()).contains("不是").contains("拦截器");

    // 下游计费的边界：只提供口径，不直连 finance。
    var billing =
        caps.downstreamGaps().stream()
            .filter(g -> "17.6.1".equals(g.module()))
            .findFirst()
            .orElseThrow();
    assertThat(billing.detail()).contains("不直连 finance 库").contains("不写 finance 表");
  }

  @Test
  void 只有读权限的人改不了目录也记不了用量() throws Exception {
    http.perform(
            post("/platform/ai/usage/models")
                .header("Authorization", "session-readonly")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"modelCode":"x","modelName":"x","unit":"REQUEST","unitCost":"1","currency":"USD"}
                    """))
        .andExpect(status().isForbidden());

    http.perform(
            post("/platform/ai/usage/records")
                .header("Authorization", "session-readonly")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"capabilityCode":"TEXT_TRANSLATION","modelCode":"call-model","quantity":"1"}
                    """))
        .andExpect(status().isForbidden());
  }
}
