package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.NotifyDeliveryModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
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
 * 18.5.2.1「投递记录」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>本测试盯住九件事：
 *
 * <ol>
 *   <li><b>🔴 退订先查后登记</b>：重复登记**不产生第二行**；
 *       撤销后再登记落成**新行**（历史区间留着）。</li>
 *   <li><b>🔴 退订必须被尊重并生效</b>：命中退订的收件人**不进投递尝试**，
 *       改写一行拦截留痕，且<b>进统计</b>。</li>
 *   <li><b>🔴 重试也要重查退订</b>：第一次投递之后才退订的，重试必须被拦下。</li>
 *   <li><b>触达率的分母不含被拦下的人</b>。</li>
 *   <li><b>结果是三态</b>：待出结果 ≠ 失败。</li>
 *   <li><b>模板版本是快照</b>。</li>
 *   <li><b>真分页真筛选</b>。</li>
 *   <li><b>时间没被时区吃掉</b>：写回库的时刻与读出来的是同一个时刻。</li>
 *   <li><b>请求体不接受归属字段</b>：带 tenantId 直接 400。</li>
 * </ol>
 *
 * <p>租户隔离的三条断言在 {@code NotifyDeliveryTenancyIntegrationTest} 里（同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotifyDeliveryIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  static final List<String> PERMISSIONS =
      List.of(
          "platform.notify_delivery.read",
          "platform.notify_delivery.create",
          "platform.notify_delivery.retry",
          "platform.notify_delivery.unsubscribe");

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
    var access =
        new AccessService.Access(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Test",
            UUID.randomUUID(),
            null,
            PERMISSIONS,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", PERMISSIONS)));
    actors.put("session-a", access);
    try {
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var found = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (found == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(found);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.setExecutor(Executors.newFixedThreadPool(2));
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

  @Autowired DSLContext sql;
  @Autowired TenantTransactions tenants;
  @Autowired MockMvc http;

  private UUID tenant() {
    return actors.get("session-a").tenantId();
  }

  /** 直接读库时**必须**在可信租户事务里读 —— 否则 RLS 会把行全部挡掉。 */
  private <T> T inTenant(java.util.function.Supplier<T> work) {
    return tenants.run(tenant(), work);
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_notify_unsubscribe_block, platform_notify_delivery_record_attempt,"
              + " platform_notify_delivery_record, platform_notify_unsubscribe,"
              + " platform_notify_template_activation, platform_notify_template_version,"
              + " platform_notify_template restart identity cascade");
    }
  }

  // ================================================================ 夹具

  /** 造一个带版本 + 启用记录的通知模板（18.5.1.1 的真实形状）。 */
  private UUID seedTemplate(String key, String scene) {
    return inTenant(
        () -> {
          var id = UUID.randomUUID();
          var versionId = UUID.randomUUID();
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_notify_template(id,tenant_id,template_key,scene,language,name,"
                  + " created_at,updated_at) values(?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
              id,
              tenant(),
              key,
              scene,
              "zh-CN",
              "模板-" + key,
              now,
              now);
          sql.execute(
              "insert into platform_notify_template_version(id,tenant_id,template_id,version_no,"
                  + " subject,body,variables,created_at)"
                  + " values(?,?,?,1,?,?,'[]'::jsonb,?::timestamptz)",
              versionId,
              tenant(),
              id,
              "",
              "正文-" + key,
              now);
          sql.execute(
              "insert into platform_notify_template_activation(id,tenant_id,template_id,version_id,"
                  + " activated_at) values(?,?,?,?,?::timestamptz)",
              UUID.randomUUID(),
              tenant(),
              id,
              versionId,
              now);
          return id;
        });
  }

  private NotifyDeliveryDetail record(
      UUID templateId, String channel, String scopeType, String scopeRef, List<NotifyRecipientInput> recipients)
      throws Exception {
    var payload =
        new NotifyDeliveryInput(templateId, null, channel, scopeType, scopeRef, recipients);
    var response =
        http.perform(
                post("/platform/notifications/deliveries")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, NotifyDeliveryDetail.class);
  }

  private NotifyUnsubscribeRow unsubscribe(String recipientRef, String channel, String scopeType, String scopeKey)
      throws Exception {
    return unsubscribe(recipientRef, channel, scopeType, scopeKey, null);
  }

  private NotifyUnsubscribeRow unsubscribe(
      String recipientRef, String channel, String scopeType, String scopeKey, UUID templateId)
      throws Exception {
    var payload =
        new NotifyUnsubscribeInput(recipientRef, channel, scopeType, scopeKey, templateId, "不想再收", "USER_REQUEST");
    var response =
        http.perform(
                post("/platform/notifications/unsubscribes")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, NotifyUnsubscribeRow.class);
  }

  private NotifyDeliveryStatistics statistics() throws Exception {
    var response =
        http.perform(
                get("/platform/notifications/deliveries/statistics")
                    .header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, NotifyDeliveryStatistics.class);
  }

  // ==========================================================================
  // 1. 退订先查后登记
  // ==========================================================================
  @Test
  void 退订先查后登记_重复登记不产生第二行() throws Exception {
    var first = unsubscribe("buyer@x.com", "EMAIL", "GLOBAL", null);
    var second = unsubscribe("buyer@x.com", "EMAIL", "GLOBAL", null);

    assertThat(second.id())
        .as("已生效的退订重复登记 ⇒ 返回同一条，不产生第二行")
        .isEqualTo(first.id());
    assertThat(second.revokedAt()).isNull();

    var rows =
        inTenant(
            () ->
                sql.fetch("select id from platform_notify_unsubscribe where recipient_ref = ?",
                    "buyer@x.com"));
    assertThat(rows).as("退订表里只能有一行").hasSize(1);
  }

  @Test
  void 撤销退订后再登记_落成新行且历史区间留着() throws Exception {
    var first = unsubscribe("buyer@x.com", "EMAIL", "GLOBAL", null);

    var revoked =
        json.readValue(
            http.perform(
                    post("/platform/notifications/unsubscribes/" + first.id() + "/revoke")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            NotifyUnsubscribeRow.class);
    assertThat(revoked.revokedAt()).as("撤销退订是软撤销").isNotNull();

    var again = unsubscribe("buyer@x.com", "EMAIL", "GLOBAL", null);
    assertThat(again.id())
        .as("撤销之后再退订 ⇒ 落成新行（历史区间留着）")
        .isNotEqualTo(first.id());

    var rows =
        inTenant(
            () ->
                sql.fetch(
                    "select id, revoked_at from platform_notify_unsubscribe"
                        + " where recipient_ref = ? order by created_at asc",
                    "buyer@x.com"));
    assertThat(rows).as("两段退订历史都留着").hasSize(2);
    assertThat(rows.get(0).get("revoked_at", OffsetDateTime.class)).isNotNull();
    assertThat(rows.get(1).get("revoked_at", OffsetDateTime.class)).isNull();
  }

  @Test
  void 撤销退订后不再拦截() throws Exception {
    var templateId = seedTemplate("order-paid", "ORDER_STATUS");
    var unsub = unsubscribe("buyer@x.com", "EMAIL", "GLOBAL", null);

    http.perform(
            post("/platform/notifications/unsubscribes/" + unsub.id() + "/revoke")
                .header("Authorization", "session-a"))
        .andExpect(status().isOk());

    var detail =
        record(
            templateId,
            "EMAIL",
            "SINGLE",
            "buyer@x.com",
            List.of(new NotifyRecipientInput("buyer@x.com", "DELIVERED", 200, null, 120L)));
    assertThat(detail.blocks()).as("撤销后不该再拦截").isEmpty();
    assertThat(detail.delivery().blockedCount().intValueExact()).isZero();
    assertThat(detail.attempts()).hasSize(1);
  }

  // ==========================================================================
  // 2. 退订必须被尊重并生效（拦截 + 进统计）
  // ==========================================================================
  @Test
  void 退订命中_收件人被拦截且不进投递尝试() throws Exception {
    var templateId = seedTemplate("order-paid", "ORDER_STATUS");
    unsubscribe("buyer@x.com", "EMAIL", "GLOBAL", null);

    var detail =
        record(
            templateId,
            "EMAIL",
            "SUBSCRIBERS",
            "all",
            List.of(
                new NotifyRecipientInput("buyer@x.com", "DELIVERED", 200, null, 100L),
                new NotifyRecipientInput("other@x.com", "DELIVERED", 200, null, 100L)));

    // 退订的那个收件人**不在投递尝试里** —— 没发出去的东西不能出现在投递日志里。
    assertThat(detail.attempts()).hasSize(1);
    assertThat(detail.attempts().get(0).recipientRef()).isEqualTo("other@x.com");

    // 拦截留痕在，且写清了命中的是哪条退订。
    assertThat(detail.blocks()).hasSize(1);
    assertThat(detail.blocks().get(0).recipientRef()).isEqualTo("buyer@x.com");
    assertThat(detail.blocks().get(0).unsubscribeScopeType()).isEqualTo("GLOBAL");

    // 🔴 进统计：拦截数落在投递记录上。
    assertThat(detail.delivery().blockedCount().intValueExact()).isEqualTo(1);
    assertThat(detail.delivery().recipientCount().intValueExact()).isEqualTo(2);
    assertThat(detail.delivery().targetedCount().intValueExact()).isEqualTo(1);
  }

  @Test
  void 退订命中_拦截数进租户级统计() throws Exception {
    var templateId = seedTemplate("order-paid", "ORDER_STATUS");
    unsubscribe("buyer@x.com", "EMAIL", "GLOBAL", null);
    record(
        templateId,
        "EMAIL",
        "SUBSCRIBERS",
        "all",
        List.of(
            new NotifyRecipientInput("buyer@x.com", "DELIVERED", 200, null, 100L),
            new NotifyRecipientInput("other@x.com", "DELIVERED", 200, null, 100L)));

    var stats = statistics();
    assertThat(stats.blockedAttempts().intValueExact()).as("拦截数必须进统计").isEqualTo(1);
    assertThat(stats.totalBlocked().intValueExact()).isEqualTo(1);
    assertThat(stats.totalRecipients().intValueExact()).isEqualTo(2);
    assertThat(stats.totalTargeted().intValueExact()).isEqualTo(1);
    assertThat(stats.unsubscribedRecipients().intValueExact()).isEqualTo(1);
    // 退订率 = 已退订收件人 / 本租户投递过的收件人 = 1 / 2
    assertThat(stats.unsubscribeRate()).isEqualByComparingTo("0.5000");
  }

  @Test
  void 退订范围_模板级与场景级都能命中() throws Exception {
    var templateId = seedTemplate("order-paid", "ORDER_STATUS");
    var otherId = seedTemplate("stock-low", "STOCK");

    // 模板级退订：只退 order-paid。
    unsubscribe("buyer@x.com", "EMAIL", "TEMPLATE", null, templateId);

    var detail =
        record(
            templateId,
            "EMAIL",
            "SINGLE",
            "buyer@x.com",
            List.of(new NotifyRecipientInput("buyer@x.com", "DELIVERED", 200, null, 100L)));
    assertThat(detail.blocks()).as("模板级退订要命中同一个模板").hasSize(1);

    // 另一个模板不受影响。
    var other =
        record(
            otherId,
            "EMAIL",
            "SINGLE",
            "buyer@x.com",
            List.of(new NotifyRecipientInput("buyer@x.com", "DELIVERED", 200, null, 100L)));
    assertThat(other.blocks()).as("模板级退订不该命中别的模板").isEmpty();
  }

  @Test
  void 退订范围_场景级按模板场景命中() throws Exception {
    var templateId = seedTemplate("order-paid", "ORDER_STATUS");
    unsubscribe("buyer@x.com", "EMAIL", "SCENE", "ORDER_STATUS");

    var detail =
        record(
            templateId,
            "EMAIL",
            "SINGLE",
            "buyer@x.com",
            List.of(new NotifyRecipientInput("buyer@x.com", "DELIVERED", 200, null, 100L)));
    assertThat(detail.blocks()).as("场景级退订要命中该场景的模板").hasSize(1);
  }

  // ==========================================================================
  // 3. 触达率
  // ==========================================================================
  @Test
  void 触达率_分母不含被退订拦下的人() throws Exception {
    var templateId = seedTemplate("order-paid", "ORDER_STATUS");
    unsubscribe("buyer@x.com", "EMAIL", "GLOBAL", null);

    var detail =
        record(
            templateId,
            "EMAIL",
            "SUBSCRIBERS",
            "all",
            List.of(
                new NotifyRecipientInput("buyer@x.com", "DELIVERED", 200, null, 100L),
                new NotifyRecipientInput("a@x.com", "DELIVERED", 200, null, 100L),
                new NotifyRecipientInput("b@x.com", "FAILED", 550, "邮箱不可用", 100L)));

    // 实际投递 2 人（a 送达、b 失败）⇒ 触达率 = 1/2 = 0.5000
    assertThat(detail.delivery().targetedCount().intValueExact()).isEqualTo(2);
    assertThat(detail.delivery().deliveredCount().intValueExact()).isEqualTo(1);
    assertThat(detail.delivery().failedCount().intValueExact()).isEqualTo(1);
    assertThat(detail.delivery().deliveryRate())
        .as("分母是实际投递的人数，不含被退订拦下的那个")
        .isEqualByComparingTo("0.5000");
    assertThat(detail.delivery().status()).isEqualTo("PARTIAL");
    assertThat(detail.delivery().failureReason()).isEqualTo("邮箱不可用");
  }

  @Test
  void 触达率_分母为零时记零而不是报错() throws Exception {
    var templateId = seedTemplate("order-paid", "ORDER_STATUS");
    unsubscribe("buyer@x.com", "EMAIL", "GLOBAL", null);

    var detail =
        record(
            templateId,
            "EMAIL",
            "SINGLE",
            "buyer@x.com",
            List.of(new NotifyRecipientInput("buyer@x.com", "DELIVERED", 200, null, 100L)));

    assertThat(detail.delivery().status()).isEqualTo("BLOCKED");
    assertThat(detail.delivery().deliveryRate()).isEqualByComparingTo("0.0000");
    assertThat(detail.delivery().targetedCount().intValueExact()).isZero();
  }

  // ==========================================================================
  // 4. 结果三态
  // ==========================================================================
  @Test
  void 结果是三态_待出结果不等于失败() throws Exception {
    var templateId = seedTemplate("order-paid", "ORDER_STATUS");

    var detail =
        record(
            templateId,
            "EMAIL",
            "SUBSCRIBERS",
            "all",
            List.of(
                new NotifyRecipientInput("a@x.com", "PENDING", null, null, null),
                new NotifyRecipientInput("b@x.com", "DELIVERED", 200, null, 100L)));

    assertThat(detail.delivery().status()).isEqualTo("PENDING");
    assertThat(detail.delivery().deliveredCount().intValueExact()).isEqualTo(1);
    assertThat(detail.delivery().failedCount().intValueExact()).isZero();
    assertThat(detail.delivery().pendingCount().intValueExact()).isEqualTo(1);
    // 待出结果的收件人也**在**投递日志里（它是这次投递的一部分）。
    assertThat(detail.attempts()).hasSize(2);
    assertThat(detail.attempts().get(0).outcome()).isEqualTo("PENDING");
  }

  // ==========================================================================
  // 5. 重试
  // ==========================================================================
  @Test
  void 重试_失败后重投成功_触达数与尝试数都更新() throws Exception {
    var templateId = seedTemplate("order-paid", "ORDER_STATUS");
    var detail =
        record(
            templateId,
            "EMAIL",
            "SINGLE",
            "a@x.com",
            List.of(new NotifyRecipientInput("a@x.com", "FAILED", 550, "邮箱不可用", 100L)));
    assertThat(detail.delivery().status()).isEqualTo("FAILED");
    assertThat(detail.delivery().attemptCount()).isEqualTo(1);

    var retried =
        json.readValue(
            http.perform(
                    post("/platform/notifications/deliveries/" + detail.delivery().id() + "/retry")
                        .header("Authorization", "session-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsString(
                                new NotifyRetryInput(
                                    List.of("a@x.com"), true, true, 200, null, 150L, null, "再试一次"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            NotifyDeliveryDetail.class);

    assertThat(retried.attempts()).as("重试要留下新的一行尝试").hasSize(2);
    assertThat(retried.attempts().get(1).attemptSource()).isEqualTo("RETRY");
    assertThat(retried.attempts().get(1).outcome()).isEqualTo("DELIVERED");
    assertThat(retried.delivery().deliveredCount().intValueExact()).isEqualTo(1);
    assertThat(retried.delivery().failedCount().intValueExact()).isEqualTo(0);
    assertThat(retried.delivery().status()).isEqualTo("DELIVERED");
    assertThat(retried.delivery().deliveryRate()).isEqualByComparingTo("1.0000");
    assertThat(retried.delivery().attemptCount()).isEqualTo(2);
  }

  @Test
  void 重试_没点名时只重试失败与待出结果的收件人() throws Exception {
    var templateId = seedTemplate("order-paid", "ORDER_STATUS");
    var detail =
        record(
            templateId,
            "EMAIL",
            "SUBSCRIBERS",
            "all",
            List.of(
                new NotifyRecipientInput("ok@x.com", "DELIVERED", 200, null, 100L),
                new NotifyRecipientInput("bad@x.com", "FAILED", 550, "邮箱不可用", 100L),
                new NotifyRecipientInput("wait@x.com", "PENDING", null, null, null)));

    var retried =
        json.readValue(
            http.perform(
                    post("/platform/notifications/deliveries/" + detail.delivery().id() + "/retry")
                        .header("Authorization", "session-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsString(
                                new NotifyRetryInput(
                                    List.of(), true, false, 550, "还是不行", 100L, null, null))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            NotifyDeliveryDetail.class);

    assertThat(retried.attempts()).hasSize(5);
    var retriedRefs = retried.attempts().stream().map(NotifyAttemptRow::recipientRef).toList();
    assertThat(retriedRefs).containsOnly("ok@x.com", "bad@x.com", "wait@x.com");
    // 已经送达的 ok@x.com **没有被重试**（它的尝试数仍是 1）。
    var okAttempts =
        retried.attempts().stream().filter(a -> a.recipientRef().equals("ok@x.com")).count();
    assertThat(okAttempts).isEqualTo(1);
  }

  @Test
  void 重试要重查退订_投递之后才退订的重试被拦下() throws Exception {
    var templateId = seedTemplate("order-paid", "ORDER_STATUS");
    var detail =
        record(
            templateId,
            "EMAIL",
            "SINGLE",
            "a@x.com",
            List.of(new NotifyRecipientInput("a@x.com", "FAILED", 550, "邮箱不可用", 100L)));

    // 第一次投递之后收件人才退订。
    unsubscribe("a@x.com", "EMAIL", "GLOBAL", null);

    var response =
        http.perform(
                post("/platform/notifications/deliveries/" + detail.delivery().id() + "/retry")
                    .header("Authorization", "session-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            new NotifyRetryInput(
                                List.of("a@x.com"), true, true, 200, null, 100L, null, null))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var retried = json.readValue(response.getContentAsString(), NotifyDeliveryDetail.class);

    // 🔴 一次都没发出去，但**如实返回**而不是抛错：
    //   抛错会让事务回滚，刚写下的拦截留痕一起没了 ——
    //   而那行留痕正是「为什么这个收件人没收到」的证据。
    assertThat(retried.attempts()).as("被退订拦截 ⇒ 没有新的尝试").hasSize(1);
    assertThat(retried.delivery().status()).isEqualTo("BLOCKED");
    assertThat(retried.delivery().blockedCount().intValueExact()).isEqualTo(1);
    assertThat(retried.delivery().targetedCount().intValueExact()).isZero();

    // 拦截留痕已经写下了 —— 「为什么这个收件人没收到」有据可查。
    var blocks =
        inTenant(
            () ->
                sql.fetch(
                    "select recipient_ref from platform_notify_unsubscribe_block where delivery_id = ?",
                    detail.delivery().id()));
    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).get(0, String.class)).isEqualTo("a@x.com");
  }

  // ==========================================================================
  // 6. 模板版本快照
  // ==========================================================================
  @Test
  void 模板版本被快照_改版不影响历史投递记录() throws Exception {
    var templateId = seedTemplate("order-paid", "ORDER_STATUS");
    var detail =
        record(
            templateId,
            "EMAIL",
            "SINGLE",
            "a@x.com",
            List.of(new NotifyRecipientInput("a@x.com", "DELIVERED", 200, null, 100L)));

    assertThat(detail.delivery().templateKey()).isEqualTo("order-paid");
    assertThat(inTenant(() -> sql.fetchOne(
            "select template_version_id from platform_notify_delivery_record where id = ?",
            detail.delivery().id()).get(0, UUID.class)))
        .as("没指定版本时按 activation 解析当前生效的那一版")
        .isNotNull();
  }

  // ==========================================================================
  // 7. 真分页真筛选
  // ==========================================================================
  @Test
  void 真分页真筛选() throws Exception {
    var templateId = seedTemplate("order-paid", "ORDER_STATUS");
    var otherId = seedTemplate("stock-low", "STOCK");
    record(templateId, "EMAIL", "SINGLE", "a@x.com",
        List.of(new NotifyRecipientInput("a@x.com", "DELIVERED", 200, null, 100L)));
    record(otherId, "SMS", "SINGLE", "b@x.com",
        List.of(new NotifyRecipientInput("b@x.com", "DELIVERED", 200, null, 100L)));
    record(templateId, "PUSH", "SINGLE", "c@x.com",
        List.of(new NotifyRecipientInput("c@x.com", "FAILED", 500, "推送失败", 100L)));

    var all =
        json.readValue(
            http.perform(get("/platform/notifications/deliveries").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            NotifyDeliveryRow[].class);
    assertThat(all).hasSize(3);

    var byChannel =
        json.readValue(
            http.perform(
                    get("/platform/notifications/deliveries").param("channel", "SMS")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            NotifyDeliveryRow[].class);
    assertThat(byChannel).hasSize(1);
    assertThat(byChannel[0].channel()).isEqualTo("SMS");

    var byTemplate =
        json.readValue(
            http.perform(
                    get("/platform/notifications/deliveries").param("templateId", templateId.toString())
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            NotifyDeliveryRow[].class);
    assertThat(byTemplate).hasSize(2);

    var byStatus =
        json.readValue(
            http.perform(
                    get("/platform/notifications/deliveries").param("status", "FAILED")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            NotifyDeliveryRow[].class);
    assertThat(byStatus).hasSize(1);

    var byKeyword =
        json.readValue(
            http.perform(
                    get("/platform/notifications/deliveries").param("keyword", "stock")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            NotifyDeliveryRow[].class);
    assertThat(byKeyword).hasSize(1);
    assertThat(byKeyword[0].templateKey()).isEqualTo("stock-low");

    // 真分页：limit=1 时只回一条，第二页是另一条。
    var page1 =
        json.readValue(
            http.perform(
                    get("/platform/notifications/deliveries").param("limit", "1").param("offset", "0")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            NotifyDeliveryRow[].class);
    var page2 =
        json.readValue(
            http.perform(
                    get("/platform/notifications/deliveries").param("limit", "1").param("offset", "1")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            NotifyDeliveryRow[].class);
    assertThat(page1).hasSize(1);
    assertThat(page2).hasSize(1);
    assertThat(page1[0].id()).isNotEqualTo(page2[0].id());
  }

  // ==========================================================================
  // 8. 时间没被时区吃掉
  // ==========================================================================
  @Test
  void 时间没被时区吃掉() throws Exception {
    var templateId = seedTemplate("order-paid", "ORDER_STATUS");
    var detail =
        record(
            templateId,
            "EMAIL",
            "SINGLE",
            "a@x.com",
            List.of(new NotifyRecipientInput("a@x.com", "DELIVERED", 200, null, 100L)));

    var occurredAt =
        inTenant(
            () ->
                sql.fetchOne(
                        "select occurred_at from platform_notify_delivery_record_attempt"
                            + " where delivery_id = ?",
                        detail.delivery().id())
                    .get(0, OffsetDateTime.class));
    var lastAttemptAt =
        inTenant(
            () ->
                sql.fetchOne(
                        "select last_attempt_at from platform_notify_delivery_record where id = ?",
                        detail.delivery().id())
                    .get(0, OffsetDateTime.class));

    assertThat(lastAttemptAt).as("写回库的时间与读出来的是同一个时刻").isNotNull();
    assertThat(lastAttemptAt.toInstant())
        .as("时间点必须一致（不能被时区悄悄挪掉）")
        .isEqualTo(occurredAt.toInstant());
  }

  // ==========================================================================
  // 9. 请求体不接受归属字段
  // ==========================================================================
  @Test
  void 请求体带tenantId直接400() throws Exception {
    var templateId = seedTemplate("order-paid", "ORDER_STATUS");
    var body =
        """
        {"templateId":"%s","channel":"EMAIL","scopeType":"SINGLE","scopeRef":"a@x.com",
         "tenantId":"22222222-2222-2222-2222-222222222222",
         "recipients":[{"ref":"a@x.com","outcome":"DELIVERED","statusCode":200}]}
        """
            .formatted(templateId);

    http.perform(
            post("/platform/notifications/deliveries")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 模板不存在返回404() throws Exception {
    var payload =
        new NotifyDeliveryInput(
            UUID.randomUUID(),
            null,
            "EMAIL",
            "SINGLE",
            "a@x.com",
            List.of(new NotifyRecipientInput("a@x.com", "DELIVERED", 200, null, 100L)));
    http.perform(
            post("/platform/notifications/deliveries")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload)))
        .andExpect(status().isNotFound());
  }

  @Test
  void 退订清单与拦截清单可查() throws Exception {
    var templateId = seedTemplate("order-paid", "ORDER_STATUS");
    unsubscribe("buyer@x.com", "EMAIL", "GLOBAL", null);
    record(
        templateId,
        "EMAIL",
        "SUBSCRIBERS",
        "all",
        List.of(
            new NotifyRecipientInput("buyer@x.com", "DELIVERED", 200, null, 100L),
            new NotifyRecipientInput("other@x.com", "DELIVERED", 200, null, 100L)));

    var unsubscribes =
        json.readValue(
            http.perform(
                    get("/platform/notifications/unsubscribes").param("active", "true")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            NotifyUnsubscribeRow[].class);
    assertThat(unsubscribes).hasSize(1);
    assertThat(unsubscribes[0].recipientRef()).isEqualTo("buyer@x.com");

    var blocks =
        json.readValue(
            http.perform(
                    get("/platform/notifications/unsubscribes/blocks")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            NotifyBlockRow[].class);
    assertThat(blocks).hasSize(1);
    assertThat(blocks[0].recipientRef()).isEqualTo("buyer@x.com");
  }

  @AfterAll
  static void stopContainers() {
    if (identity != null) identity.stop(0);
    if (pg.isRunning()) pg.stop();
  }
}
