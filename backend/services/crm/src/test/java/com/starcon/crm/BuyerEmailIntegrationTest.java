package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 15.1.2.1 邮件收发的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。租户隔离三断言在
 * {@link BuyerEmailTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>🔴 加粗句「分开展示会导致重复回复买家」</b>：同一买家 + 同一订单 + 同一店铺的邮件
 *       必须落在同一个会话键上；会话视图里必须同时看得到<b>收到的</b>与<b>发出的</b>两行。</li>
 *   <li><b>🔴 加粗句「发送失败要保留原消息状态」</b>：发送失败时，<b>收到的那封</b>邮件的状态
 *       必须**不变**（仍是「已收到」），失败原因留在发送记录里。</li>
 *   <li><b>平台侧未接入的通道一律拒绝</b>：发送记录的 {@code PLATFORM_API} 与平台邮件同步
 *       都必须明确失败 —— 一个「返回成功回执」的假通道会让「买家已经收到回复了」
 *       在数据上看起来发生过，而重复回复买家的代价正是平台违规与买家投诉。</li>
 *   <li><b>上游不造数据</b>：店铺必须在 channel 的真实清单里；上游读不到就 503，
 *       绝不返回空集合冒充「没有店铺」。</li>
 *   <li><b>时区不静默偏移（硬性要求 5）</b>：用 {@code +08:00} 的偏移量写入，
 *       再<b>断言写回的值</b>等于同一个绝对时刻。</li>
 *   <li><b>禁浮点</b>：附件字节数是 {@code numeric(19,6)}，写回必须逐位相同。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class BuyerEmailIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final UUID shopId = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 上游（channel）的店铺清单。 */
  static final List<SignoffDeliveredOrderPort.ShopView> upstreamShops = new CopyOnWriteArrayList<>();

  /** 上游（channel）读不到时的开关（用来验证 503 而不是空集合）。 */
  static volatile boolean channelDown = false;

  static HttpServer identity;
  static HttpServer channel;

  /** 平台邮件端口的开关（默认未接入；打开后模拟适配器已交付）。 */
  static final TogglePlatformEmailPort platformPort = new TogglePlatformEmailPort();

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role crm_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    List<String> permissions = List.of("crm.email.read", "crm.email.update");
    actors.put(
        "session",
        new AccessService.Access(
            userId,
            tenant,
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
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    // 上游 stub：真的起一个 HTTP 服务，端口与真实 channel 服务同形。
    try {
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/shops",
          e -> {
            if (channelDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            write(e, List.copyOf(upstreamShops));
          });
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * 平台邮件端口的测试替身。
   *
   * <p>🔴 它<b>默认委托给真的 {@link UnavailablePlatformEmailPort}</b> ——
   * 「未接入」的判定跑的是生产代码，不是测试自己编的一套。
   * 只有显式打开开关时，它才模拟「适配器已交付」，好让同步的落库路径也能被覆盖。
   */
  static final class TogglePlatformEmailPort implements PlatformEmailPort {
    private final PlatformEmailPort delegate = new UnavailablePlatformEmailPort();
    volatile boolean available = false;
    final List<RawEmail> emails = new CopyOnWriteArrayList<>();

    @Override
    public Availability availability(String platformCode) {
      return available
          ? new Availability(true, "AVAILABLE", "适配器已交付（测试态）")
          : delegate.availability(platformCode);
    }

    @Override
    public FetchResult fetch(String platformCode, UUID shop, String bearer) {
      return available
          ? new FetchResult(true, "", List.copyOf(emails))
          : delegate.fetch(platformCode, shop, bearer);
    }
  }

  @TestConfiguration
  static class PortConfig {
    @Bean
    @Primary
    PlatformEmailPort platformEmailPort() {
      return platformPort;
    }
  }

  private static void write(com.sun.net.httpserver.HttpExchange e, Object body) {
    try {
      byte[] bytes = json.writeValueAsBytes(body);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(200, bytes.length);
      e.getResponseBody().write(bytes);
      e.close();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "crm_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
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
          "truncate table crm_buyer_email_send_record, crm_buyer_email_attachment,"
              + " crm_buyer_email_template, crm_buyer_email cascade");
    }
    upstreamShops.clear();
    upstreamShops.add(
        new SignoffDeliveredOrderPort.ShopView(
            shopId, "SHOP-E", "邮件测试店铺", "AMAZON", "亚马逊", "SELF_FULFILLED", "ACTIVE"));
    channelDown = false;
    platformPort.available = false;
    platformPort.emails.clear();
  }

  /** 登记一封收到的邮件（走真实端点）。 */
  JsonNode createEmail(String buyerRef, String orderNo, String subject, String receivedAt) {
    try {
      String body =
          """
          {"shopId":"%s","buyerRef":"%s","salesOrderNo":"%s","subject":"%s",
           "body":"正文内容","receivedAt":"%s"}
          """
              .formatted(shopId, buyerRef, orderNo, subject, receivedAt);
      var response =
          http.perform(
                  post("/crm/buyer-emails")
                      .header("Authorization", "session")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(body))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      return json.readTree(response);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ================================================================ 直读夹具
  //
  // ⚠️ 这些读**必须**在绑定租户的事务里做：RLS 是 FORCE 的，事务外读业务表
  //   一行都看不到（那正是隔离生效的样子，不是 bug）。直接在事务外断言「读到了 N 行」
  //   会得到一个假红。

  /** 在本租户事务里读若干行。 */
  List<org.jooq.Record> rows(String text, Object... args) {
    return tenants.run(tenant, () -> sql.fetch(text, args));
  }

  /** 在本租户事务里读一行（没有就是 null）。 */
  org.jooq.Record row(String text, Object... args) {
    return tenants.run(tenant, () -> sql.fetchOne(text, args));
  }

  /** 在本租户事务里数行数。 */
  long count(String text, Object... args) {
    return tenants.run(tenant, () -> sql.fetchOne(text, args).get(0, Long.class));
  }

  // ================================================================ 登记与落库

  @Test
  void 登记收到的邮件真落库并给出邮件号与会话键() throws Exception {
    var created = createEmail("buyer-1", "SO-1", "包裹破损", "2026-06-01T09:30:00+08:00");

    assertThat(created.get("emailNo").asText()).startsWith("BE-");
    assertThat(created.get("direction").asText()).isEqualTo("INBOUND");
    assertThat(created.get("status").asText()).isEqualTo("RECEIVED");
    assertThat(created.get("source").asText()).isEqualTo("MANUAL_ENTRY");
    assertThat(created.get("shopName").asText()).isEqualTo("邮件测试店铺");
    assertThat(created.get("platformCode").asText()).isEqualTo("AMAZON");
    assertThat(created.get("subject").asText()).isEqualTo("包裹破损");
    // 会话键 = <shopId>|<buyerRef 小写>|<orderNo 小写>
    assertThat(created.get("conversationKey").asText())
        .isEqualTo(shopId + "|buyer-1|so-1");
    // 附件数与发送记录数都从**真实明细**数出来。
    assertThat(created.get("attachmentCount").asLong()).isZero();
    assertThat(created.get("sendRecordCount").asLong()).isZero();

    // 真落库（不是只返回了一个对象）。
    assertThat(count("select count(*) from crm_buyer_email where buyer_ref = ?", "buyer-1"))
        .isEqualTo(1L);
  }

  @Test
  void 同一买家同一订单同一店铺共用会话键_不同订单不同键() throws Exception {
    var first = createEmail("buyer-1", "SO-1", "问题 A", "2026-06-01T09:30:00+08:00");
    var second = createEmail("buyer-1", "SO-1", "追问", "2026-06-02T09:30:00+08:00");
    var other = createEmail("buyer-1", "SO-2", "另一个订单", "2026-06-03T09:30:00+08:00");

    assertThat(second.get("conversationKey").asText())
        .as("同一买家 + 同一订单 + 同一店铺必须落在同一个会话里")
        .isEqualTo(first.get("conversationKey").asText());
    assertThat(other.get("conversationKey").asText())
        .as("不同订单必须是不同的会话")
        .isNotEqualTo(first.get("conversationKey").asText());
  }

  @Test
  void 时间带偏移量写入后断言写回值等于同一绝对时刻() throws Exception {
    var created = createEmail("buyer-tz", "SO-TZ", "时区", "2026-06-01T09:30:00+08:00");
    var expected = OffsetDateTime.parse("2026-06-01T09:30:00+08:00").toInstant();

    var returned = OffsetDateTime.parse(created.get("receivedAt").asText()).toInstant();
    assertThat(returned)
        .as("写回的时刻必须与填进去的是同一个绝对时刻（用 Timestamp.valueOf 会静默偏 −28800 秒）")
        .isEqualTo(expected);

    // 直接查库再核一遍（不能只断言「没抛异常」）。
    var stored =
        row("select received_at from crm_buyer_email where id = ?",
                UUID.fromString(created.get("id").asText()))
            .get("received_at", OffsetDateTime.class);
    assertThat(stored.toInstant()).isEqualTo(expected);
  }

  // ================================================================ 列表：真分页 + 真筛选

  @Test
  void 邮件列表真分页且总数与筛选一致() throws Exception {
    for (int i = 0; i < 5; i++) {
      createEmail("buyer-" + i, "SO-" + i, "主题 " + i, "2026-06-0" + (i + 1) + "T09:30:00+08:00");
    }

    var page =
        json.readTree(
            http.perform(get("/crm/buyer-emails").param("page", "0").param("size", "2")
                    .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(page.get("items").size()).isEqualTo(2);
    assertThat(page.get("totalElements").asLong()).isEqualTo(5L);
    assertThat(page.get("totalPages").asLong()).isEqualTo(3L);
    assertThat(page.get("page").asInt()).isZero();
    assertThat(page.get("size").asInt()).isEqualTo(2);

    var page2 =
        json.readTree(
            http.perform(get("/crm/buyer-emails").param("page", "2").param("size", "2")
                    .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(page2.get("items").size()).isEqualTo(1);
  }

  @Test
  void 筛选按方向状态买家订单主题与时间窗生效() throws Exception {
    createEmail("buyer-a", "SO-A", "包裹破损", "2026-06-01T09:30:00+08:00");
    createEmail("buyer-b", "SO-B", "发票问题", "2026-06-05T09:30:00+08:00");

    var byBuyer =
        json.readTree(
            http.perform(get("/crm/buyer-emails").param("buyerRef", "buyer-a")
                    .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(byBuyer.get("totalElements").asLong()).isEqualTo(1L);
    assertThat(byBuyer.get("items").get(0).get("buyerRef").asText()).isEqualTo("buyer-a");

    var byOrder =
        json.readTree(
            http.perform(get("/crm/buyer-emails").param("salesOrderNo", "SO-B")
                    .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(byOrder.get("totalElements").asLong()).isEqualTo(1L);

    var bySubject =
        json.readTree(
            http.perform(get("/crm/buyer-emails").param("subject", "发票")
                    .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(bySubject.get("totalElements").asLong()).isEqualTo(1L);
    assertThat(bySubject.get("items").get(0).get("subject").asText()).isEqualTo("发票问题");

    var byDirection =
        json.readTree(
            http.perform(get("/crm/buyer-emails").param("direction", "INBOUND")
                    .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(byDirection.get("totalElements").asLong()).isEqualTo(2L);

    // 时间窗（闭开区间 [from, to)）
    var byWindow =
        json.readTree(
            http.perform(get("/crm/buyer-emails")
                    .param("receivedFrom", "2026-06-04T00:00:00+08:00")
                    .param("receivedTo", "2026-06-06T00:00:00+08:00")
                    .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(byWindow.get("totalElements").asLong()).isEqualTo(1L);
    assertThat(byWindow.get("items").get(0).get("buyerRef").asText()).isEqualTo("buyer-b");
  }

  // ================================================================ 附件

  @Test
  void 附件元数据落库且字节数原样写回() throws Exception {
    var created = createEmail("buyer-att", "SO-ATT", "带附件", "2026-06-01T09:30:00+08:00");
    String id = created.get("id").asText();

    http.perform(
            post("/crm/buyer-emails/" + id + "/attachments")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"fileName":"photo.jpg","contentType":"image/jpeg","sizeBytes":1234.500000,
                     "storageKey":"bucket/photo.jpg","checksumSha256":"abc123"}
                    """))
        .andExpect(status().isOk());

    var stored =
        row(
            "select file_name, content_type, size_bytes, storage_key, checksum_sha256"
                + " from crm_buyer_email_attachment where email_id = ?",
            UUID.fromString(id));
    assertThat(stored.get("file_name", String.class)).isEqualTo("photo.jpg");
    assertThat(stored.get("content_type", String.class)).isEqualTo("image/jpeg");
    assertThat(stored.get("size_bytes", BigDecimal.class))
        .as("字节数是 numeric(19,6)：禁浮点，写回必须逐位相同")
        .isEqualByComparingTo(new BigDecimal("1234.500000"));
    assertThat(stored.get("storage_key", String.class)).isEqualTo("bucket/photo.jpg");
    assertThat(stored.get("checksum_sha256", String.class)).isEqualTo("abc123");

    // 列表里的附件数是**从明细数出来的**。
    var after =
        json.readTree(
            http.perform(get("/crm/buyer-emails/" + id).header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(after.get("email").get("attachmentCount").asLong()).isEqualTo(1L);
    assertThat(after.get("attachments").size()).isEqualTo(1);
    assertThat(after.get("attachments").get(0).get("fileName").asText()).isEqualTo("photo.jpg");
  }

  // ================================================================ 发送记录

  @Test
  void 发送成功把原邮件置为已回复并在会话里落下我们回的那一行() throws Exception {
    var created = createEmail("buyer-s", "SO-S", "要退款", "2026-06-01T09:30:00+08:00");
    String id = created.get("id").asText();

    var result =
        json.readTree(
            http.perform(
                    post("/crm/buyer-emails/" + id + "/send-records")
                        .header("Authorization", "session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"transport":"MANUAL_HANDOFF","status":"SENT","subject":"已为您退款",
                             "body":"退款将在 3 个工作日内到账","platformReceipt":"RCP-001"}
                            """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(result.get("sendRecord").get("status").asText()).isEqualTo("SENT");
    assertThat(result.get("sendRecord").get("platformReceipt").asText()).isEqualTo("RCP-001");
    assertThat(result.get("sendRecord").get("attemptedAt").asText()).isNotEmpty();
    // 发出去的那一行（OUTBOUND）也在同一个会话里。
    var outbound = result.get("outboundEmail");
    assertThat(outbound.get("direction").asText()).isEqualTo("OUTBOUND");
    assertThat(outbound.get("status").asText()).isEqualTo("SENT");
    assertThat(outbound.get("replyToEmailId").asText()).isEqualTo(id);
    assertThat(outbound.get("conversationKey").asText())
        .isEqualTo(created.get("conversationKey").asText());

    // 收到的那封被置为「已回复」。
    var after =
        json.readTree(
            http.perform(get("/crm/buyer-emails/" + id).header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(after.get("email").get("status").asText()).isEqualTo("REPLIED");
    assertThat(after.get("sendRecords").size()).isEqualTo(1);
    // 会话视图里两行都在（收 + 发）—— 这正是「同一会话处理」的物理落点。
    assertThat(after.get("conversation").size()).isEqualTo(2);
  }

  @Test
  void 发送失败保留原邮件状态且失败原因留痕() throws Exception {
    var created = createEmail("buyer-f", "SO-F", "要退款", "2026-06-01T09:30:00+08:00");
    String id = created.get("id").asText();

    var result =
        json.readTree(
            http.perform(
                    post("/crm/buyer-emails/" + id + "/send-records")
                        .header("Authorization", "session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"transport":"MANUAL_HANDOFF","status":"FAILED","subject":"已为您退款",
                             "body":"退款将在 3 个工作日内到账","failureReason":"平台侧邮箱被拒收"}
                            """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(result.get("sendRecord").get("status").asText()).isEqualTo("FAILED");
    assertThat(result.get("sendRecord").get("failureReason").asText()).isEqualTo("平台侧邮箱被拒收");
    // 发出去的那一行如实记着失败。
    assertThat(result.get("outboundEmail").get("status").asText()).isEqualTo("FAILED");

    // 🔴 收到的那封**状态不变** —— 仍是「已收到」，不能让客服以为已经回过。
    var after =
        json.readTree(
            http.perform(get("/crm/buyer-emails/" + id).header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(after.get("email").get("status").asText())
        .as("发送失败必须保留原邮件状态")
        .isEqualTo("RECEIVED");
    assertThat(after.get("sendRecords").get(0).get("failureReason").asText())
        .isEqualTo("平台侧邮箱被拒收");
  }

  @Test
  void 失败不写失败原因时必须被拒() throws Exception {
    var created = createEmail("buyer-f2", "SO-F2", "要退款", "2026-06-01T09:30:00+08:00");
    String id = created.get("id").asText();

    http.perform(
            post("/crm/buyer-emails/" + id + "/send-records")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"transport":"MANUAL_HANDOFF","status":"FAILED","subject":"x","body":"y"}
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 平台发送通道未接入被拒() throws Exception {
    var created = createEmail("buyer-p", "SO-P", "要退款", "2026-06-01T09:30:00+08:00");
    String id = created.get("id").asText();

    http.perform(
            post("/crm/buyer-emails/" + id + "/send-records")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"transport":"PLATFORM_API","status":"SENT","subject":"x","body":"y"}
                    """))
        .andExpect(status().isConflict());

    // 一条发送记录都不许留下。
    assertThat(
            count(
                "select count(*) from crm_buyer_email_send_record where email_id = ?",
                UUID.fromString(id)))
        .isZero();
  }

  @Test
  void 没有正文的发送被拒() throws Exception {
    var created = createEmail("buyer-e", "SO-E", "要退款", "2026-06-01T09:30:00+08:00");
    String id = created.get("id").asText();

    http.perform(
            post("/crm/buyer-emails/" + id + "/send-records")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"transport":"MANUAL_HANDOFF","status":"QUEUED"}
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 对发出去的那一封不能再登记发送记录() throws Exception {
    var created = createEmail("buyer-o", "SO-O", "要退款", "2026-06-01T09:30:00+08:00");
    String id = created.get("id").asText();
    var result =
        json.readTree(
            http.perform(
                    post("/crm/buyer-emails/" + id + "/send-records")
                        .header("Authorization", "session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"transport":"MANUAL_HANDOFF","status":"SENT","subject":"x","body":"y"}
                            """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String outboundId = result.get("outboundEmail").get("id").asText();

    http.perform(
            post("/crm/buyer-emails/" + outboundId + "/send-records")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"transport":"MANUAL_HANDOFF","status":"SENT","subject":"x","body":"y"}
                    """))
        .andExpect(status().isConflict());
  }

  // ================================================================ 平台邮件同步

  @Test
  void 平台邮件同步未接入返回409且不写任何数据() throws Exception {
    long before = count("select count(*) from crm_buyer_email");

    http.perform(
            post("/crm/buyer-emails/sync")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shopId\":\"" + shopId + "\"}"))
        .andExpect(status().isConflict());

    assertThat(count("select count(*) from crm_buyer_email"))
        .as("未接入的同步绝不能写任何数据")
        .isEqualTo(before);
  }

  @Test
  void 平台邮件同步接上后按外部ID幂等落库() throws Exception {
    platformPort.available = true;
    platformPort.emails.add(
        new PlatformEmailPort.RawEmail(
            "EXT-1",
            "buyer-sync",
            "买家",
            "buyer@example.com",
            "平台来信",
            "正文",
            OffsetDateTime.parse("2026-06-01T09:30:00+08:00"),
            "SO-SYNC",
            null));

    http.perform(
            post("/crm/buyer-emails/sync")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shopId\":\"" + shopId + "\"}"))
        .andExpect(status().isOk());

    var synced =
        row(
            "select buyer_ref, source, external_message_id, conversation_key"
                + " from crm_buyer_email where external_message_id = 'EXT-1'");
    assertThat(synced).isNotNull();
    assertThat(synced.get("buyer_ref", String.class)).isEqualTo("buyer-sync");
    assertThat(synced.get("source", String.class)).isEqualTo("PLATFORM_SYNC");
    assertThat(synced.get("conversation_key", String.class)).isEqualTo(shopId + "|buyer-sync|so-sync");

    // 幂等：再同步一次不重复落库。
    http.perform(
            post("/crm/buyer-emails/sync")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shopId\":\"" + shopId + "\"}"))
        .andExpect(status().isOk());
    assertThat(count("select count(*) from crm_buyer_email where external_message_id = 'EXT-1'"))
        .isEqualTo(1L);
  }

  // ================================================================ 模板

  @Test
  void 模板增删改查且发送时引用模板采用模板正文() throws Exception {
    var template =
        json.readTree(
            http.perform(
                    post("/crm/buyer-emails/templates")
                        .header("Authorization", "session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"templateCode":"REFUND_V1","name":"退款话术","locale":"zh-CN",
                             "subject":"关于您的退款","body":"您好，退款将在 3 个工作日内到账。"}
                            """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String templateId = template.get("id").asText();
    assertThat(template.get("status").asText()).isEqualTo("ACTIVE");
    assertThat(template.get("usageCount").asLong()).isZero();

    // 列表能查到（真分页 + 真筛选）。
    var list =
        json.readTree(
            http.perform(get("/crm/buyer-emails/templates").param("keyword", "REFUND")
                    .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(list.get("totalElements").asLong()).isEqualTo(1L);

    // 改模板。
    http.perform(
            put("/crm/buyer-emails/templates/" + templateId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"templateCode":"REFUND_V1","name":"退款话术 v2","locale":"zh-CN",
                     "subject":"关于您的退款","body":"您好，退款将在 3 个工作日内到账。"}
                    """))
        .andExpect(status().isOk());

    // 发送时引用模板：主题与正文取自模板。
    var created = createEmail("buyer-t", "SO-T", "要退款", "2026-06-01T09:30:00+08:00");
    String id = created.get("id").asText();
    var result =
        json.readTree(
            http.perform(
                    post("/crm/buyer-emails/" + id + "/send-records")
                        .header("Authorization", "session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"transport":"MANUAL_HANDOFF","status":"SENT","templateId":"%s"}
                            """
                                .formatted(templateId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(result.get("sendRecord").get("subject").asText()).isEqualTo("关于您的退款");
    assertThat(result.get("sendRecord").get("body").asText())
        .isEqualTo("您好，退款将在 3 个工作日内到账。");
    assertThat(result.get("sendRecord").get("templateCode").asText()).isEqualTo("REFUND_V1");

    // 模板的引用次数从**真实发送记录**数出来。
    var after =
        json.readTree(
            http.perform(get("/crm/buyer-emails/templates/" + templateId)
                    .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(after.get("usageCount").asLong()).isEqualTo(1L);
    assertThat(after.get("name").asText()).isEqualTo("退款话术 v2");
  }

  @Test
  void 已被发送记录引用的模板删不掉() throws Exception {
    var template =
        json.readTree(
            http.perform(
                    post("/crm/buyer-emails/templates")
                        .header("Authorization", "session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"templateCode":"KEEP_V1","name":"保留话术","subject":"s","body":"b"}
                            """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String templateId = template.get("id").asText();

    var created = createEmail("buyer-keep", "SO-KEEP", "要退款", "2026-06-01T09:30:00+08:00");
    http.perform(
            post("/crm/buyer-emails/" + created.get("id").asText() + "/send-records")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"transport":"MANUAL_HANDOFF","status":"SENT","templateId":"%s"}
                    """
                        .formatted(templateId)))
        .andExpect(status().isOk());

    // 删不掉（DB 触发器）：删掉后「这次回复用的哪套话术」在历史里就没有正文可查了。
    http.perform(
            delete("/crm/buyer-emails/templates/" + templateId).header("Authorization", "session"))
        .andExpect(status().isConflict());
    assertThat(
            count(
                "select count(*) from crm_buyer_email_template where id = ?",
                UUID.fromString(templateId)))
        .isEqualTo(1L);
  }

  @Test
  void 没有被引用的模板可以删掉() throws Exception {
    var template =
        json.readTree(
            http.perform(
                    post("/crm/buyer-emails/templates")
                        .header("Authorization", "session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"templateCode":"DROP_V1","name":"一次性话术","subject":"s","body":"b"}
                            """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    http.perform(
            delete("/crm/buyer-emails/templates/" + template.get("id").asText())
                .header("Authorization", "session"))
        .andExpect(status().isOk());
    assertThat(
            count(
                "select count(*) from crm_buyer_email_template where id = ?",
                UUID.fromString(template.get("id").asText())))
        .isZero();
  }

  // ================================================================ 数据库层守卫

  @Test
  void 会话键建后不可改由数据库层挡住() throws Exception {
    var created = createEmail("buyer-guard", "SO-GUARD", "改键", "2026-06-01T09:30:00+08:00");
    UUID id = UUID.fromString(created.get("id").asText());

    var failure =
        assertThatThrownBy(
                () ->
                    tenants.run(
                        tenant,
                        () -> {
                          sql.execute(
                              "update crm_buyer_email set conversation_key = ? where tenant_id = ? and id = ?",
                              "改了键",
                              tenant,
                              id);
                          return null;
                        }))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(failure.actual().getMessage()).contains("会话键");
  }

  @Test
  void 方向与状态不自洽时数据库层拒写() throws Exception {
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () -> {
                      sql.execute(
                          "insert into crm_buyer_email"
                              + "(id,tenant_id,email_no,shop_id,shop_name,platform_code,direction,source,"
                              + " buyer_ref,conversation_key,received_at,status,created_at,updated_at)"
                              + " values (?,?,'BE-X',?,'店铺','AMAZON','INBOUND','MANUAL_ENTRY','b',"
                              + " 'k',now()::timestamptz,'SENT',now()::timestamptz,now()::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          shopId);
                      return null;
                    }))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  // ================================================================ 上游不可用

  @Test
  void 上游店铺清单读不到时返回503而不是空集合() throws Exception {
    channelDown = true;
    http.perform(get("/crm/buyer-emails/shops").header("Authorization", "session"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void 能力声明逐维说清有没有与归属模块() throws Exception {
    var caps =
        json.readTree(
            http.perform(get("/crm/buyer-emails/capabilities").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var dimensions = caps.get("dimensions");
    assertThat(dimensions.size()).isGreaterThanOrEqualTo(8);
    var byName = new java.util.HashMap<String, JsonNode>();
    for (var d : dimensions) {
      byName.put(d.get("dimension").asText(), d);
    }
    assertThat(byName.get("inbox_thread").get("available").asBoolean()).isFalse();
    assertThat(byName.get("inbox_thread").get("ownerModule").asText()).isEqualTo("15.1.1.1");
    assertThat(byName.get("platform_email_sync").get("available").asBoolean()).isFalse();
    assertThat(byName.get("platform_email_api").get("available").asBoolean()).isFalse();
    assertThat(byName.get("message_send").get("ownerModule").asText()).isEqualTo("15.1.1.2");
    assertThat(byName.get("email_template").get("available").asBoolean()).isTrue();
    assertThat(byName.get("send_failure_keeps_state").get("available").asBoolean()).isTrue();
    // 会话键公式必须写在能力声明里（15.1.1.1 要按它对齐）。
    assertThat(caps.get("conversationKeySpec").asText()).contains("shopId");
  }
}
