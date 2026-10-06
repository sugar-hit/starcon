package com.starcon.channel;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.channel.oauth.OAuthAuthorizationService;
import com.starcon.channel.secrets.SecretStore;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 2.2.1.2 授权向导的集成覆盖。
 *
 * <p>这条链路不能靠 mock 交差：它要真的跳到平台、真的换到令牌、真的把店铺建出来。
 * 因此测试起了一个真的本地授权服务器（{@link StubOAuthServer}），走完整的 HTTP 往返：
 * 发起授权 → 302 跳到平台 → 平台带 code + state 回调 → 换令牌 → 读账号 → 确认店铺 → 建连接与店铺。
 *
 * <p>多租户部分除了 AGENTS.md 要求的"两租户互不可见 / 伪造租户头无效 / 连接池归还不串租户"，
 * 还要额外证明本模块特有的一条：<b>回调请求带不带伪造的租户头，都不改变回调解析出的租户归属</b>。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OAuthAuthorizationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID(), user = UUID.randomUUID();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "channel." + p + "." + op))
          .toList();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static com.sun.net.httpserver.HttpServer identity;
  static StubOAuthServer oauth;
  static Path secretDir;

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(), grants);
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
      identity = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            AccessService.Access resolved = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (resolved == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(resolved);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, tenantA, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, tenantB, List.of(grant("ALL", allPermissions))));
      identity.start();

      // 令牌落点放在项目内，绝不写到家目录或 /tmp
      secretDir = Path.of(".tmp", "oauth-test-secrets").toAbsolutePath().normalize();
      deleteRecursively(secretDir);
      Files.createDirectories(secretDir);
      oauth = new StubOAuthServer();
      // 端点配置是配置：把 eBay 那一行指向本地 stub，等价于"这个环境部署成了这样"
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
        // Flyway 还没跑，profile 表还不存在：这里只做连接保活，profile 由 @BeforeAll 在迁移后改写
        c.createStatement().close();
      }
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("CHANNEL_OAUTH_PUBLIC_BASE_URL", () -> "http://oauth.test");
    r.add("CHANNEL_SECRET_STORE_DIR", () -> secretDir.toString());
    r.add("CHANNEL_OAUTH_EBAY_CLIENT_ID", () -> oauth.clientId);
    r.add("CHANNEL_OAUTH_EBAY_CLIENT_SECRET", () -> oauth.clientSecret);
    r.add("CHANNEL_OAUTH_AMAZON_CLIENT_ID", () -> oauth.clientId);
    r.add("CHANNEL_OAUTH_AMAZON_CLIENT_SECRET", () -> oauth.clientSecret);
    r.add("CHANNEL_OAUTH_SHOPIFY_CLIENT_ID", () -> oauth.clientId);
    r.add("CHANNEL_OAUTH_SHOPIFY_CLIENT_SECRET", () -> oauth.clientSecret);
    // 单连接让"连接池归还后不串租户"这条断言有意义：下一个借用者拿到的就是同一条物理会话
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired ChannelService channelService;
  @Autowired OAuthAuthorizationService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired SecretStore secrets;
  @Autowired MockMvc http;

  AccessService.Access actorA, actorB;

  /**
   * 把端点改写成本地 stub，只做一次。
   *
   * <p>不能放在 @BeforeAll：Spring 上下文是在测试实例注入时才加载的，Flyway 那时还没跑，
   * profile 表根本不存在。改用迁移执行后的惰性一次性初始化。
   *
   * <p>走 superuser 而不是 DSLContext：platform_oauth_profile 是全局码表，
   * 运行角色只有 SELECT 权限，测试也不该为了改配置去放宽它。
   */
  private static boolean profilesRepointed;

  private static void repointProfilesOnce() throws Exception {
    if (profilesRepointed) return;
    synchronized (OAuthAuthorizationIntegrationTest.class) {
      if (profilesRepointed) return;
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
        oauth.repointProfile(c, "EBAY");
        oauth.repointProfile(c, "AMAZON");
        oauth.repointProfile(c, "SHOPIFY");
      }
      profilesRepointed = true;
    }
  }

  @BeforeEach
  void reset() throws Exception {
    repointProfilesOnce();
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    oauth.accountId = "seller-" + UUID.randomUUID();
    oauth.accountName = "Stub 旗舰店";
    oauth.shops = List.<String[]>of(new String[] {"SHOP-" + UUID.randomUUID().toString().substring(0, 8), "美国旗舰店", "EBAY_US"});
    oauth.grantedScopes = "offline_access https://api.ebay.com/oauth/api_scope/sell.fulfillment";
    oauth.tokenStatus = 0;
    oauth.tokenBodyOverride = null;
    oauth.authorizeError = null;
    oauth.requirePkce = false;
  }

  @AfterAll
  static void stop() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("drop schema public cascade; create schema public;");
    }
    oauth.close();
    identity.stop(0);
    pg.stop();
    deleteRecursively(secretDir);
  }

  // ------------------------------------------------------------------
  // 辅助：把一次完整授权跑到底
  // ------------------------------------------------------------------

  record Start(String sessionId, URI authorizeUrl) {}

  String json(Object value) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  Start startAs(AccessService.Access who, String bearer, String platformCode, String loginHint) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("platformCode", platformCode);
    body.put("loginHint", loginHint);
    body.put("idempotencyKey", "idem-" + UUID.randomUUID());
    var raw =
        http.perform(post("/channel/oauth/sessions")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(json(body)))
            .andReturn()
            .getResponse();
    if (raw.getStatus() != 200)
      throw new AssertionError("start failed: " + raw.getStatus() + " " + raw.getContentAsString());
    String response = raw.getContentAsString();
    Map<?, ?> parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(response, Map.class);
    return new Start((String) parsed.get("sessionId"), URI.create((String) parsed.get("authorizeUrl")));
  }

  /** 模拟浏览器走完 302，返回平台最终回调的地址。 */
  URI platformRedirectsToCallback(Start start) throws Exception {
    return oauth.followAuthorizationRedirect(start.authorizeUrl());
  }

  String callback(URI callbackUrl) throws Exception {
    return callback(callbackUrl, null, null);
  }

  /** 可选地伪造租户头：用来证明回调的租户归属与请求头无关。 */
  String callback(URI callbackUrl, String forgedTenant, String bearer) throws Exception {
    var request = get(callbackUrl.getPath());
    String code = queryParam(callbackUrl, "code");
    if (code != null) request = request.param("code", code);
    String state = queryParam(callbackUrl, "state");
    if (state != null) request = request.param("state", state);
    String error = queryParam(callbackUrl, "error");
    if (error != null) request = request.param("error", error);
    String description = queryParam(callbackUrl, "error_description");
    if (description != null) request = request.param("error_description", description);
    if (forgedTenant != null) request = request.header("X-Tenant-Id", forgedTenant);
    if (bearer != null) request = request.header("Authorization", bearer);
    var rr = http.perform(request).andReturn();
    return rr.getResponse().getContentAsString();
  }

  static String queryParam(URI uri, String name) {
    return StubOAuthServer.parseQuery(uri.getRawQuery()).get(name);
  }

  Map<?, ?> parse(String body) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  List<?> list(String body) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, List.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 单列单行取值。jOOQ 的 fetchValue 重载会解析成 Object，这里显式取第 0 列。 */
  <T> T scalar(Class<T> type, String query, Object... binds) {
    org.jooq.Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) return;
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  // ------------------------------------------------------------------
  // 六步全链路
  // ------------------------------------------------------------------

  @Test
  void fullWizardFlowBindsConnectionCredentialAndShops() throws Exception {
    Start start = startAs(actorA, "Bearer tenant-a", "EBAY", "");
    assertThat(start.sessionId()).isNotBlank();
    // 第 3 步：授权地址必须真的指向平台，而不是页面上的假跳转
    assertThat(start.authorizeUrl().toString()).startsWith(oauth.base() + "/oauth/authorize");
    Map<String, String> authorizeQuery =
        StubOAuthServer.parseQuery(start.authorizeUrl().getRawQuery());
    assertThat(authorizeQuery).containsKeys("client_id", "redirect_uri", "state", "scope");
    assertThat(authorizeQuery.get("client_id")).isEqualTo(oauth.clientId);
    // 第 2 步「选账号」：eBay 走 prompt=select_account，让用户在授权页重新选账号
    assertThat(authorizeQuery.get("prompt")).isEqualTo("select_account");

    URI callbackUrl = platformRedirectsToCallback(start);
    assertThat(callbackUrl.getPath()).isEqualTo("/channel/oauth/callback");
    assertThat(queryParam(callbackUrl, "code")).isNotBlank();
    assertThat(queryParam(callbackUrl, "state")).isEqualTo(authorizeQuery.get("state"));

    // 第 4 步：回调校验
    Map<?, ?> session = parse(callback(callbackUrl));
    assertThat(session.get("status")).isEqualTo("AUTHORIZED");
    assertThat((String) session.get("grantedScopes")).contains("offline_access");
    assertThat(session.get("authorizedAccount")).isEqualTo(oauth.accountId);

    // 第 5 步：店铺候选
    String candidates =
        http.perform(get("/channel/oauth/sessions/{id}/shops", start.sessionId())
                .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<?> shops = list(candidates);
    assertThat(shops).isNotEmpty();
    assertThat((String) ((Map<?, ?>) shops.get(0)).get("externalShopId")).isEqualTo(oauth.shops.get(0)[0]);

    // 第 6 步：确认店铺范围并完成
    String completed =
        http.perform(post("/channel/oauth/sessions/{id}/confirm", start.sessionId())
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(json(Map.of("idempotencyKey", "confirm-" + UUID.randomUUID()))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<?, ?> result = parse(completed);
    UUID connectionId = UUID.fromString((String) result.get("connectionId"));
    UUID credentialId = UUID.fromString((String) result.get("credentialId"));
    assertThat((Integer) result.get("shopCount")).isEqualTo(shops.size());
    assertThat(result.get("nextRoute")).isEqualTo("/channels?section=shops");

    // 落库是真的：connection / credential_ref / shop 三张表都要有行
    Map<String, Object> row = as(actorA, "channel.connection.read", () -> {
      org.jooq.Record r = sql.fetchOne(
          "select c.authorized_account,c.status,c.credential_id,cr.external_ref,cr.provider,cr.expires_at,"
              + "(select count(*) from shop s where s.tenant_id=c.tenant_id and s.connection_id=c.id) shop_count"
              + " from channel_connection c join credential_ref cr on cr.tenant_id=c.tenant_id and cr.id=c.credential_id"
              + " where c.id=?", connectionId);
      Map<String, Object> m = new LinkedHashMap<>();
      for (int i = 0; i < r.size(); i++) m.put(r.field(i).getName(), r.get(i));
      return m;
    });
    assertThat(row.get("authorized_account")).isEqualTo(oauth.accountId);
    assertThat(row.get("credential_id")).isEqualTo(credentialId);
    assertThat(row.get("status")).isIn("ACTIVE", "EXPIRING");
    assertThat((String) row.get("external_ref")).startsWith(secrets.provider());
    // 2.2.1.3 铁律：库里只有引用，令牌明文落在密钥管理器目录里
    assertThat((String) row.get("external_ref")).doesNotContain(oauth.issuedAccessToken());
    assertThat(Files.readString(secretDir.resolve(
        "channel/" + tenantA + "/ebay/" + oauth.accountId)))
        .isEqualTo(oauth.issuedAccessToken());
    assertThat(((Number) row.get("shop_count")).intValue()).isEqualTo(shops.size());

    // 授权产物也不该出现在任何响应里
    assertThat(completed).doesNotContain(oauth.issuedAccessToken()).doesNotContain("stub-refresh-token");
    assertThat(session.toString()).doesNotContain(oauth.issuedAccessToken());
  }

  @Test
  void shopSiteIsLinkedWhenPlatformReturnsAKnownMarketplace() throws Exception {
    oauth.shops = List.<String[]>of(new String[] {"SHOP-MK-1", "德国站", "DE"});
    Start start = startAs(actorA, "Bearer tenant-a", "EBAY", "");
    assertThat(parse(callback(platformRedirectsToCallback(start))).get("status")).isEqualTo("AUTHORIZED");
    String completed =
        http.perform(post("/channel/oauth/sessions/{id}/confirm", start.sessionId())
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(json(Map.of("idempotencyKey", "confirm-" + UUID.randomUUID()))))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<?, ?> result = parse(completed);
    UUID shopId = UUID.fromString(((List<?>) result.get("shopIds")).get(0).toString());
    int sites =
        as(
            actorA,
            "channel.shop.read",
            () ->
                scalar(
                    Integer.class,
                    "select count(*) from shop_marketplace where tenant_id=? and shop_id=?",
                    tenantA,
                    shopId));
    // DE 是 eBay 注册表里真实存在的站点，平台回传 'DE' 就应该被链上
    assertThat(sites).isEqualTo(1);
  }

  // ------------------------------------------------------------------
  // 本模块特有：回调的租户归属
  // ------------------------------------------------------------------

  @Test
  void callbackTenantComesFromStateAndIgnoresTheRequestHeaders() throws Exception {
    Start start = startAs(actorA, "Bearer tenant-a", "EBAY", "");
    URI callbackUrl = platformRedirectsToCallback(start);
    // 关键断言：回调带上 B 的租户头和 B 的令牌，租户归属仍然必须是发起授权的 A
    String body = callback(callbackUrl, tenantB.toString(), "Bearer tenant-b");
    assertThat(parse(body).get("status")).isEqualTo("AUTHORIZED");
    String sessionId = start.sessionId();

    http.perform(post("/channel/oauth/sessions/{id}/confirm", sessionId)
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(json(Map.of("idempotencyKey", "confirm-" + UUID.randomUUID()))))
        .andExpect(status().isOk());
    UUID connectionId =
        as(
            actorA,
            "channel.connection.read",
            () ->
                scalar(
                    UUID.class,
                    "select connection_id from oauth_authorization_session where id=?",
                    UUID.fromString(sessionId)));

    // 连接建在 A 名下，B 一条都看不到（即使 B 是全权限角色）
    long aConnections = as(actorA, "channel.connection.read", () -> channelService.connections(oauth.accountId)).size();
    long bConnections = as(actorB, "channel.connection.read", () -> channelService.connections(oauth.accountId)).size();
    assertThat(aConnections).isEqualTo(1);
    assertThat(bConnections).isZero();
    assertThat(as(actorA, "channel.connection.read", () -> scalar(
        Integer.class, "select count(*) from channel_connection where id=?", connectionId))).isEqualTo(1);
  }

  @Test
  void callbackWithoutAnyTenantHeaderStillResolvesTheInitiatingTenant() throws Exception {
    Start start = startAs(actorA, "Bearer tenant-a", "EBAY", "");
    String body = callback(platformRedirectsToCallback(start));
    assertThat(parse(body).get("status")).isEqualTo("AUTHORIZED");
    assertThat(parse(body).get("authorizedAccount")).isEqualTo(oauth.accountId);
  }

  @Test
  void replayedStateIsRejectedBecauseItIsSingleUse() throws Exception {
    Start start = startAs(actorA, "Bearer tenant-a", "EBAY", "");
    URI callbackUrl = platformRedirectsToCallback(start);
    assertThat(parse(callback(callbackUrl)).get("status")).isEqualTo("AUTHORIZED");
    // 同一个 state 再来一次（CSRF 重放）：state 已消费，必须被拒
    http.perform(get("/channel/oauth/callback")
            .param("code", queryParam(callbackUrl, "code"))
            .param("state", queryParam(callbackUrl, "state")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void forgedStateIsRejected() throws Exception {
    http.perform(get("/channel/oauth/callback").param("code", "whatever").param("state", "forged-state"))
        .andExpect(status().isBadRequest());
    // 完全没有 state 也不行：没有 state 就无法确定租户归属，必须拒绝而不是猜
    http.perform(get("/channel/oauth/callback").param("code", "whatever"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anotherTenantCannotReadOrCancelThisAuthorizationSession() throws Exception {
    Start start = startAs(actorA, "Bearer tenant-a", "EBAY", "");
    String sessionId = start.sessionId();
    http.perform(get("/channel/oauth/sessions/{id}", sessionId).header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/oauth/sessions/{id}/shops", sessionId).header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isNotFound());
    http.perform(post("/channel/oauth/sessions/{id}/cancel", sessionId).header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isNotFound());
    // A 自己也还没取消
    http.perform(get("/channel/oauth/sessions/{id}", sessionId).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
  }

  @Test
  void forgedTenantHeaderCannotReachAnotherTenantsAuthorizationSessions() throws Exception {
    Start start = startAs(actorA, "Bearer tenant-a", "EBAY", "");
    // A 的令牌 + 伪造的 B 租户头：只能看到 A 自己的会话
    http.perform(get("/channel/oauth/sessions/{id}", start.sessionId())
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", tenantB.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"));
  }

  @Test
  void connectionReturnedToPoolDoesNotLeakThePreviousTenantAcrossCallbacks() throws Exception {
    // 先让 A 走完一次回调，再让 B 走一次：池大小为 1，两次用的是同一条物理会话
    Start a = startAs(actorA, "Bearer tenant-a", "EBAY", "");
    assertThat(parse(callback(platformRedirectsToCallback(a))).get("status")).isEqualTo("AUTHORIZED");

    oauth.accountId = "seller-" + UUID.randomUUID();
    Start b = startAs(actorB, "Bearer tenant-b", "EBAY", "");
    Map<?, ?> bSession = parse(callback(platformRedirectsToCallback(b)));
    assertThat(bSession.get("status")).isEqualTo("AUTHORIZED");
    // B 的回调必须解析到 B 自己授权的那个会话，而不是池里残留的 A 的会话
    assertThat(bSession.get("authorizedAccount")).isEqualTo(oauth.accountId);
    assertThat((String) bSession.get("id")).isEqualTo(b.sessionId());
    // A 的 state 已被消费，不能重放；改用两个租户各自读自己的会话来证明租户上下文被重新绑定：
    // 同一条物理会话上，B 的回调之后 A 仍只能看到 A 的会话。
    http.perform(get("/channel/oauth/sessions/{id}", a.sessionId()).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(a.sessionId()));
    http.perform(get("/channel/oauth/sessions/{id}", a.sessionId()).header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/oauth/sessions/{id}", b.sessionId()).header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(b.sessionId()));
  }

  // ------------------------------------------------------------------
  // 失败原因必须可执行
  // ------------------------------------------------------------------

  @Test
  void badPlatformCredentialsAreReportedAsAnActionableReason() throws Exception {
    Start start = startAs(actorA, "Bearer tenant-a", "EBAY", "");
    oauth.tokenStatus = 401;
    Map<?, ?> session = parse(callback(platformRedirectsToCallback(start)));
    assertThat(session.get("status")).isEqualTo("FAILED");
    assertThat(session.get("failureCode")).isEqualTo("CREDENTIAL_REJECTED");
    assertThat((String) session.get("failureMessage")).contains("凭据");
  }

  @Test
  void missingScopesAreReportedAsAnActionableReason() throws Exception {
    Start start = startAs(actorA, "Bearer tenant-a", "EBAY", "");
    oauth.grantedScopes = "https://api.ebay.com/oauth/api_scope/sell.account";
    Map<?, ?> session = parse(callback(platformRedirectsToCallback(start)));
    assertThat(session.get("status")).isEqualTo("FAILED");
    assertThat(session.get("failureCode")).isEqualTo("INSUFFICIENT_SCOPE");
    assertThat((String) session.get("failureMessage")).contains("offline_access");
    // 权限不足时绝不能已经把连接建出来
    long connections = as(actorA, "channel.connection.read", () -> channelService.connections(oauth.accountId)).size();
    assertThat(connections).isZero();
  }

  @Test
  void userDecliningAuthorizationIsReportedAsAnActionableReason() throws Exception {
    Start start = startAs(actorA, "Bearer tenant-a", "EBAY", "");
    oauth.authorizeError = "access_denied";
    Map<?, ?> session = parse(callback(platformRedirectsToCallback(start)));
    assertThat(session.get("status")).isEqualTo("FAILED");
    assertThat(session.get("failureCode")).isEqualTo("PLATFORM_REJECTED");
    assertThat((String) session.get("failureMessage")).contains("取消");
  }

  @Test
  void anAccountAlreadyBoundByAnotherTenantIsReportedAsAnActionableReason() throws Exception {
    // A 先把账号绑走
    oauth.accountId = "seller-shared-" + UUID.randomUUID().toString().substring(0, 8);
    Start a = startAs(actorA, "Bearer tenant-a", "EBAY", "");
    assertThat(parse(callback(platformRedirectsToCallback(a))).get("status")).isEqualTo("AUTHORIZED");
    http.perform(post("/channel/oauth/sessions/{id}/confirm", a.sessionId())
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(json(Map.of("idempotencyKey", "confirm-" + UUID.randomUUID()))))
        .andExpect(status().isOk());

    // B 授权同一个平台账号：必须明确报"已被其他租户绑定"，而不是建出第二条连接
    Start b = startAs(actorB, "Bearer tenant-b", "EBAY", "");
    Map<?, ?> session = parse(callback(platformRedirectsToCallback(b)));
    assertThat(session.get("status")).isEqualTo("FAILED");
    assertThat(session.get("failureCode")).isEqualTo("ACCOUNT_BOUND_BY_OTHER_TENANT");
    assertThat((String) session.get("failureMessage")).contains("其他租户");
    assertThat(as(actorB, "channel.connection.read", () -> channelService.connections(oauth.accountId))).isEmpty();
  }

  @Test
  void shopAlreadyClaimedByAnotherTenantIsRejectedAtConfirmTime() throws Exception {
    oauth.accountId = "seller-x" + UUID.randomUUID().toString().substring(0, 8);
    oauth.shops = List.<String[]>of(new String[] {"SHOP-SHARED", "共享店铺", "US"});
    Start a = startAs(actorA, "Bearer tenant-a", "EBAY", "");
    callback(platformRedirectsToCallback(a));
    http.perform(post("/channel/oauth/sessions/{id}/confirm", a.sessionId())
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(json(Map.of("idempotencyKey", "confirm-" + UUID.randomUUID()))))
        .andExpect(status().isOk());

    // B 用另一个账号授权，但平台把同一个店铺也带回来了：确认阶段必须挡住
    oauth.accountId = "seller-y" + UUID.randomUUID().toString().substring(0, 8);
    Start b = startAs(actorB, "Bearer tenant-b", "EBAY", "");
    callback(platformRedirectsToCallback(b));
    http.perform(post("/channel/oauth/sessions/{id}/confirm", b.sessionId())
            .header("Authorization", "Bearer tenant-b")
            .contentType("application/json")
            .content(json(Map.of("idempotencyKey", "confirm-" + UUID.randomUUID()))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("其他租户")));
  }

  // ------------------------------------------------------------------
  // 幂等与取消
  // ------------------------------------------------------------------

  @Test
  void theSameIdempotencyKeyReusesTheSameSessionAndState() throws Exception {
    String key = "idem-" + UUID.randomUUID();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("platformCode", "EBAY");
    body.put("idempotencyKey", key);
    String first =
        http.perform(post("/channel/oauth/sessions")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(json(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String second =
        http.perform(post("/channel/oauth/sessions")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(json(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<?, ?> a = parse(first);
    Map<?, ?> b = parse(second);
    // 同一个会话、同一个 state：用户连点两次不会产生两个待授权会话
    assertThat(a.get("sessionId")).isEqualTo(b.get("sessionId"));
    assertThat(queryParam(URI.create((String) a.get("authorizeUrl")), "state"))
        .isEqualTo(queryParam(URI.create((String) b.get("authorizeUrl")), "state"));
  }

  @Test
  void cancellingASessionInvalidatesItsState() throws Exception {
    Start start = startAs(actorA, "Bearer tenant-a", "EBAY", "");
    URI callbackUrl = platformRedirectsToCallback(start);
    http.perform(post("/channel/oauth/sessions/{id}/cancel", start.sessionId())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    // 取消后平台再回调就换不到租户了
    http.perform(get(callbackUrl.getPath())
            .param("code", queryParam(callbackUrl, "code"))
            .param("state", queryParam(callbackUrl, "state")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void confirmRequiresAtLeastOneShop() throws Exception {
    Start start = startAs(actorA, "Bearer tenant-a", "EBAY", "");
    callback(platformRedirectsToCallback(start));
    http.perform(post("/channel/oauth/sessions/{id}/confirm", start.sessionId())
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(json(Map.of("shopIds", List.of(), "idempotencyKey", "c-" + UUID.randomUUID()))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("至少确认一个店铺")));
  }

  @Test
  void confirmingTwiceReturnsTheSameConnectionInsteadOfDuplicating() throws Exception {
    Start start = startAs(actorA, "Bearer tenant-a", "EBAY", "");
    callback(platformRedirectsToCallback(start));
    String key = "confirm-" + UUID.randomUUID();
    Map<String, Object> body = new LinkedHashMap<>(Map.of("idempotencyKey", key));
    String first =
        http.perform(post("/channel/oauth/sessions/{id}/confirm", start.sessionId())
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(json(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String second =
        http.perform(post("/channel/oauth/sessions/{id}/confirm", start.sessionId())
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(json(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(parse(first).get("connectionId")).isEqualTo(parse(second).get("connectionId"));
    assertThat(as(actorA, "channel.connection.read", () -> channelService.connections(oauth.accountId))).hasSize(1);
  }

  // ------------------------------------------------------------------
  // 端点与凭据配置
  // ------------------------------------------------------------------

  @Test
  void aPlatformWithoutAnOAuthProfileIsRejectedWithAConcreteReason() throws Exception {
    http.perform(post("/channel/oauth/sessions")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(json(Map.of("platformCode", "ETSY", "idempotencyKey", "k-" + UUID.randomUUID()))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ETSY")));
  }

  @Test
  void aPlatformWithoutConfiguredClientCredentialsNamesTheMissingVariables() throws Exception {
    // PLANNED 平台没有 profile；用一个有 profile 但没配凭据的平台验证提示是否说清缺哪个变量
    http.perform(post("/channel/oauth/sessions")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(json(Map.of("platformCode", "TEMU", "idempotencyKey", "k-" + UUID.randomUUID()))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("CHANNEL_OAUTH_TEMU_CLIENT_ID")));
  }

  @Test
  void shopifyRefusesToStartWithoutAShopDomainBecauseItCannotBuildAValidRedirect() throws Exception {
    http.perform(post("/channel/oauth/sessions")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(json(Map.of("platformCode", "SHOPIFY", "loginHint", "", "idempotencyKey", "k-" + UUID.randomUUID()))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(".myshopify.com")));
  }

  @Test
  void amazonSendsPkceBecauseThatPlatformRequiresIt() throws Exception {
    Start start = startAs(actorA, "Bearer tenant-a", "AMAZON", "seller@example.com");
    Map<String, String> query = StubOAuthServer.parseQuery(start.authorizeUrl().getRawQuery());
    assertThat(query.get("code_challenge_method")).isEqualTo("S256");
    assertThat(query.get("code_challenge")).isNotBlank();
    assertThat(query.get("code_verifier")).isNull();
    assertThat(query).doesNotContainKey("code_verifier");

    oauth.requirePkce = true;
    callback(platformRedirectsToCallback(start));
    // 平台确实收到了 code_verifier，否则 stub 会以 invalid_grant 拒绝、状态会是 FAILED
    assertThat(oauth.lastCodeVerifier).isNotBlank();
  }

  @Test
  void unauthorizedRoleCannotStartAnAuthorization() throws Exception {
    actors.put("Bearer limited", actor(user, tenantA, List.of(grant("ALL", List.of("channel.connection.read")))));
    http.perform(post("/channel/oauth/sessions")
            .header("Authorization", "Bearer limited")
            .contentType("application/json")
            .content(json(Map.of("platformCode", "EBAY", "idempotencyKey", "k-" + UUID.randomUUID()))))
        .andExpect(status().isForbidden());
  }
}
