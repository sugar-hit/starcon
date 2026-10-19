package com.starcon.channel;

import static com.starcon.channel.ListingCollectModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 4.4.1.1 链接采集。
 *
 * <p>蓝图：「粘贴外部商品链接（1688/淘宝/供应商站/同行 listing），抓取标题、主图、规格、属性、描述等，生成采集草稿供认领为内部商品。输入：商品
 * URL（支持平台白名单）；抓取任务。输出：采集到的商品草稿（图片下载入媒体库）；来源 URL 与抓取时间。关系：上游 = 外部页面；下游 =
 * 4.4.1.3 采集箱、3.1.1.1（认领为 SPU）。<b>采集只产生草稿</b>，进入正式商品库必须走 03 的建档流程并补全属性与合规信息。」
 *
 * <p>🔴 <b>本测试类用 JDK 自带的 {@code com.sun.net.httpserver.HttpServer} 起一个真实的本地
 * HTTP 服务</b>，返回一段**真实的商品页 HTML**（含 JSON-LD / Open Graph / 规格表），
 * 然后断言服务层**真的把标题、主图、规格解析了出来**。
 *
 * <p>这不是 mock：
 *
 * <ul>
 *   <li>HTTP 是真的（真的 connect、真的发请求、真的收字节、真的按状态码分支）；
 *   <li>HTML 是真的（由本地服务在收到请求时现场生成，含 JSON-LD / og / 规格表）；
 *   <li>解析是真的（断言的是「HTML 里的那几个值被解析了出来」，不是「返回了预先写好的常量」）；
 *   <li>而且请求**次数**被计数 —— 「去重后没有第二次请求」「白名单外一个请求都没发」
 *       这类断言只有真发请求才测得出来。
 * </ul>
 *
 * <p>本测试类钉住的事：
 *
 * <ol>
 *   <li><b>🔴 真抓真解析</b>：JSON-LD / og / 规格表三条解析路径各自被覆盖。
 *   <li><b>🔴 白名单外 400</b>：不在白名单的域名被拒，且**一个 HTTP 请求都没发出去**。
 *   <li><b>🔴 去重</b>：同一 URL 第二次采集直接返回既有草稿，**服务端的请求计数不增加**。
 *   <li><b>🔴 限频</b>：同租户同 host 的第二次抓取被 429 拒，并写明还要等多少秒。
 *   <li><b>🔴 重定向守卫</b>：白名单域名把请求引到白名单外 ⇒ 拒绝，且不跟随。
 *   <li><b>🔴 失败留痕</b>：外部站点 500 ⇒ 任务 FAILED + 失败原因，**不产生草稿**。
 *   <li><b>🔴 只产生草稿</b>：采集之后 {@code listing} / {@code listing_variant} /
 *       {@code listing_content_snapshot} 一行都没有 —— 采集不建商品。
 *   <li><b>🔴 图片只记 URL</b>：草稿里的图片是 URL 字符串，本模块没有任何媒体表被写。
 *   <li><b>timestamptz 往返</b>：抓取时刻写回的值**逐字相等**。
 *   <li><b>金额</b>：外部页面原文标价是 {@code NUMERIC(19,4)}，往返相等。
 * </ol>
 *
 * <p>多租户三断言在 {@code ListingCollectTenancyIntegrationTest} 里（连接池压到 1，直查用
 * {@code channel_app} 而不是超级用户）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingCollectIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer shop;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  /** 🔴 本地"外部站点"收到的请求次数：去重 / 白名单 / 重定向三类断言全靠它。 */
  static final AtomicInteger requests = new AtomicInteger();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

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

      // 🔴 真实的"外部站点"：收到请求时现场生成商品页 HTML
      shop = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      shop.createContext(
          "/",
          e -> {
            requests.incrementAndGet();
            String path = e.getRequestURI().getPath();
            if (path.startsWith("/boom")) {
              byte[] body = "boom".getBytes(StandardCharsets.UTF_8);
              e.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
              e.sendResponseHeaders(500, body.length);
              e.getResponseBody().write(body);
              e.close();
              return;
            }
            if (path.startsWith("/redirect-out")) {
              e.getResponseHeaders().add("Location", "http://not-allowed.example.com/product/1");
              e.sendResponseHeaders(302, -1);
              e.close();
              return;
            }
            if (path.startsWith("/redirect-ok")) {
              e.getResponseHeaders().add("Location", "/product/1");
              e.sendResponseHeaders(302, -1);
              e.close();
              return;
            }
            if (path.startsWith("/no-product")) {
              // 🔴 真实错误页的样子：�� title>，但没有任何商品信号
              //    （没有 JSON-LD Product、没有 og:title、没有 og:image）
              byte[] body =
                  ("<html><head><title>404 - 页面不存在</title></head>"
                          + "<body>没有找到该商品</body></html>")
                      .getBytes(StandardCharsets.UTF_8);
              e.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
              e.sendResponseHeaders(200, body.length);
              e.getResponseBody().write(body);
              e.close();
              return;
            }
            byte[] body = productPage(path).getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      shop.start();

    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * 🔴 真实的商品页 HTML：同时含 JSON-LD（schema.org Product）、Open Graph 标签与规格表。
   *
   * <p>三个解析路径各有各的值，测试断言的就是它们被解析出来。
   */
  static String productPage(String path) {
    String id = path.replaceAll("[^0-9]", "");
    if (id.isEmpty()) id = "1";
    return """
        <!doctype html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8">
          <title>页面标题 - 某商城</title>
          <meta property="og:title" content="OG 标题 商品-%ID%">
          <meta property="og:image" content="https://cdn.example.com/og/%ID%.jpg">
          <meta property="og:description" content="OG 描述 商品-%ID%">
          <meta property="product:price:amount" content="128.5000">
          <meta property="product:price:currency" content="CNY">
          <meta property="product:brand" content="OG 品牌">
          <script type="application/ld+json">
          {
            "@context": "https://schema.org/",
            "@type": "Product",
            "name": "JSON-LD 标题 商品-%ID%",
            "description": "JSON-LD 描述 商品-%ID%",
            "sku": "SKU-%ID%",
            "brand": { "@type": "Brand", "name": "JSON-LD 品牌" },
            "image": ["https://cdn.example.com/main/%ID%.jpg", "https://cdn.example.com/extra/%ID%.jpg"],
            "additionalProperty": [
              { "@type": "PropertyValue", "name": "材质", "value": "纯棉" },
              { "@type": "PropertyValue", "name": "尺码", "value": "M" }
            ],
            "offers": {
              "@type": "Offer",
              "price": "88.80",
              "priceCurrency": "CNY"
            }
          }
          </script>
        </head>
        <body>
          <h1>商品-%ID%</h1>
          <div class="detail-attrs">
            <table>
              <tr><th>产地</th><td>广东</td></tr>
              <tr><th>保质期</th><td>36 个月</td></tr>
            </table>
          </div>
          <p>商品-%ID% 的正文描述</p>
        </body>
        </html>
        """
        .replace("%ID%", id);
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
  }

  @Autowired ListingCollectService service;
  @Autowired AccessService access;
  @Autowired org.jooq.DSLContext sql;
  @Autowired MockMvc http;

  /**
   * 🔴 白名单里补两行指向本地测试服务的来源（生产种子只有真实平台）。
   *
   * <p>🔴 **必须在 Spring 上下文起来之后**做：V202 迁移（连同表与种子行）是 Flyway 在
   * 上下文启动时跑的，而静态初始化块早于它 —— 在那儿插表会得到
   * {@code relation "listing_collect_source" does not exist}。
   *
   * <p>IP 那一行 {@code min_interval_seconds=0}（多数用例不受限频影响），
   * hostname 那一行 60 秒（限频用例用）。
   */
  @BeforeEach
  void seedTestSources() {
    exec(
        "insert into listing_collect_source (id,code,name,host,kind,min_interval_seconds,enabled,position)"
            + " values ('6c1d0002-0000-4000-8000-000000000001','TEST_LOOPBACK_IP','本地测试站点(IP)',"
            + "'127.0.0.1','PLATFORM',0,true,900)"
            + " on conflict (id) do update set min_interval_seconds=0");
    exec(
        "insert into listing_collect_source (id,code,name,host,kind,min_interval_seconds,enabled,position)"
            + " values ('6c1d0002-0000-4000-8000-000000000002','TEST_LOOPBACK_HOST','本地测试站点(主机名)',"
            + "'localhost','PLATFORM',60,true,910)"
            + " on conflict (id) do update set min_interval_seconds=60");
  }

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer t-a-" + a;
    String bearerB = "Bearer t-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    actorA = actors.get(bearerA);
    actorB = actors.get(bearerB);
    requests.set(0);
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  static void exec(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 在某个租户上下文里用 jOOQ 读一个标量（与写库走同一条路径与同一套时区设置）。 */
  <T> T queryValue(AccessService.Access who, Class<T> type, String query, Object... binds) {
    return as(who, "channel.operation.read", () -> sql.fetchOne(query, binds).get(0, type));
  }

  /** 以应用角色 channel_app 在某个租户上下文里直查（绕开超级用户绕过 RLS 的问题）。 */
  static long countAsTenant(UUID tenant, String sql) {
    try (Connection c =
        DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.read',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(sql)) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  String url(String path) {
    return "http://127.0.0.1:" + shop.getAddress().getPort() + path;
  }

  /** 键值项列表 → 便于断言的 Map（测试侧便利方法，不进生产代码）。 */
  static Map<String, String> specMap(List<ListingCollectKeyValue> items) {
    Map<String, String> out = new java.util.LinkedHashMap<>();
    for (ListingCollectKeyValue item : items) {
      out.put(item.name(), item.value());
    }
    return out;
  }

  // ==================================================================
  // 1. 🔴 真抓真解析
  // ==================================================================

  @Test
  @DisplayName("4.4.1.1 真抓真解析：JSON-LD / og / 规格表三条路径都被解析出来")
  void collectsRealPage() {
    CollectListingResult result =
        as(actorA, "channel.operation.create", () -> service.collect(new CollectListingRequest(url("/product/1"), ""), user));

    assertThat(result.deduplicated()).as("首次采集不是去重命中").isFalse();
    assertThat(result.task().state()).isEqualTo("SUCCEEDED");
    assertThat(result.task().errorMessage()).isEmpty();
    assertThat(result.task().httpStatus()).isEqualTo(200);
    assertThat(result.task().attemptNo()).isEqualTo(1);

    var draft = result.draft();
    assertThat(draft).as("成功必须产生草稿").isNotNull();
    // 🔴 JSON-LD 优先于 og 与 <title>
    assertThat(draft.title()).isEqualTo("JSON-LD 标题 商品-1");
    assertThat(draft.description()).isEqualTo("JSON-LD 描述 商品-1");
    // 🔴 主图来自 JSON-LD 的 image 数组第一项
    assertThat(draft.mainImageUrl()).isEqualTo("https://cdn.example.com/main/1.jpg");
    assertThat(draft.imageUrls())
        .containsExactly(
            "https://cdn.example.com/main/1.jpg", "https://cdn.example.com/extra/1.jpg");
    // 🔴 规格来自 JSON-LD additionalProperty + 页面规格表（两组都要在）
    assertThat(specMap(draft.specs())).containsEntry("材质", "纯棉").containsEntry("尺码", "M");
    assertThat(specMap(draft.specs())).containsEntry("产地", "广东").containsEntry("保质期", "36 个月");
    // 🔴 属性来自 JSON-LD 的 brand / sku
    assertThat(specMap(draft.attributes())).containsEntry("品牌", "JSON-LD 品牌").containsEntry("货号", "SKU-1");
    // 🔴 外部页面原文标价（不是我方售价）
    assertThat(draft.sourcePriceAmount()).isEqualByComparingTo(new BigDecimal("88.80"));
    assertThat(draft.sourcePriceCurrency()).isEqualTo("CNY");
    // 🔴 来源 URL 与抓取时间
    assertThat(draft.sourceUrl()).isEqualTo(url("/product/1"));
    assertThat(draft.capturedAt()).isNotNull();

    assertThat(requests.get()).as("真的发了一次 HTTP 请求").isEqualTo(1);
  }

  @Test
  @DisplayName("4.4.1.1 og / <title> 兜底：没有 JSON-LD 时用 Open Graph")
  void fallsBackToOpenGraph() {
    // 直接测解析器（同一个被服务层调用的纯函数）：页面只有 og 标签与 <title>
    String html =
        """
        <html><head>
          <title>页面标题 - 某商城</title>
          <meta property="og:title" content="OG 标题 商品-9">
          <meta property="og:image" content="/relative/9.jpg">
          <meta property="og:description" content="OG 描述 商品-9">
          <meta property="product:price:amount" content="19.90">
          <meta property="product:price:currency" content="CNY">
        </head><body></body></html>
        """;
    var parsed = ListingCollectHtmlParser.parse(html, "https://shop.example.com/p/9");
    assertThat(parsed.title()).isEqualTo("OG 标题 商品-9");
    assertThat(parsed.description()).isEqualTo("OG 描述 商品-9");
    // 🔴 相对图片地址被解析成绝对地址
    assertThat(parsed.mainImageUrl()).isEqualTo("https://shop.example.com/relative/9.jpg");
    assertThat(parsed.priceAmount()).isEqualByComparingTo(new BigDecimal("19.90"));
    assertThat(parsed.priceCurrency()).isEqualTo("CNY");
  }

  @Test
  @DisplayName("4.4.1.1 <title> 兜底：连 og 都没有时用 <title>")
  void fallsBackToTitleTag() {
    var parsed =
        ListingCollectHtmlParser.parse(
            "<html><head><title>只有标题</title></head><body>x</body></html>", "https://x.example.com/");
    assertThat(parsed.title()).isEqualTo("只有标题");
  }

  // ==================================================================
  // 2. 🔴 白名单外 400（且一个请求都不发）
  // ==================================================================

  @Test
  @DisplayName("4.4.1.1 白名单外的域名一律 400 并说明原因，一个请求都不发")
  void rejectsHostOutsideAllowlist() {
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.collect(
                            new CollectListingRequest("http://not-allowed.example.com/product/1", ""),
                            user)));
    assertThat(error).isNotNull();
    assertThat(error.getMessage()).contains("不在平台白名单内");
    assertThat(error.getMessage()).contains("not-allowed.example.com");
    assertThat(requests.get()).as("白名单外**一个 HTTP 请求都不该发出去**").isZero();
  }

  @Test
  @DisplayName("4.4.1.1 白名单外走 HTTP 端点也是 400，不是 500")
  void rejectsHostOutsideAllowlistOverHttp() throws Exception {
    http.perform(
            post("/channel/listing-collects")
                .header("Authorization", bearerA)
                .contentType("application/json")
                .content("{\"url\":\"http://not-allowed.example.com/p/1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("白名单")));
  }

  // ==================================================================
  // 3. 🔴 去重
  // ==================================================================

  @Test
  @DisplayName("4.4.1.1 同一 URL 不重复抓：第二次直接返回既有草稿，服务端请求数不增加")
  void deduplicatesSameUrl() {
    var first =
        as(actorA, "channel.operation.create", () -> service.collect(new CollectListingRequest(url("/product/7"), ""), user));
    assertThat(first.deduplicated()).isFalse();
    assertThat(requests.get()).isEqualTo(1);

    // 🔴 同一 URL 再来一次：直接返回既有草稿
    var second =
        as(actorA, "channel.operation.create", () -> service.collect(new CollectListingRequest(url("/product/7"), ""), user));
    assertThat(second.deduplicated()).as("第二次必须命中去重").isTrue();
    assertThat(second.draft()).isNotNull();
    assertThat(second.draft().draftId()).isEqualTo(first.draft().draftId());
    assertThat(second.draft().title()).isEqualTo(first.draft().title());
    assertThat(requests.get()).as("去重命中时**不得再发一次 HTTP 请求**").isEqualTo(1);
  }

  @Test
  @DisplayName("4.4.1.1 去重按规范化 URL：跟踪参数不同但页面相同 ⇒ 仍然算同一个")
  void deduplicatesAcrossTrackingParams() {
    var first =
        as(actorA, "channel.operation.create", () -> service.collect(new CollectListingRequest(url("/product/8"), ""), user));
    assertThat(first.deduplicated()).isFalse();

    var second =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.collect(
                    new CollectListingRequest(url("/product/8") + "?utm_source=wechat&spm=a1z", ""),
                    user));
    assertThat(second.deduplicated()).as("带 utm 参数的同一页面必须算同一个").isTrue();
    assertThat(requests.get()).as("规范化后重复 ⇒ 不再发请求").isEqualTo(1);
  }

  @Test
  @DisplayName("4.4.1.1 去重是租户内的：A 采过不影响 B 采")
  void dedupIsPerTenant() {
    as(actorA, "channel.operation.create", () -> service.collect(new CollectListingRequest(url("/product/3"), ""), user));
    var other =
        as(actorB, "channel.operation.create", () -> service.collect(new CollectListingRequest(url("/product/3"), ""), user));
    assertThat(other.deduplicated()).as("另一个租户不共享去重记录").isFalse();
    assertThat(requests.get()).isEqualTo(2);
  }

  // ==================================================================
  // 4. 🔴 限频
  // ==================================================================

  @Test
  @DisplayName("4.4.1.1 同租户同 host 抓太频繁 ⇒ 429 并写明还要等多少秒")
  void rateLimitsSameHost() {
    // localhost 那一行的 min_interval 是 60 秒
    String target = "http://localhost:" + shop.getAddress().getPort() + "/product/5";
    as(actorA, "channel.operation.create", () -> service.collect(new CollectListingRequest(target, ""), user));
    assertThat(requests.get()).isEqualTo(1);

    // 🔴 同一租户、同一 host、不同 URL ⇒ 仍被限频
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.collect(
                            new CollectListingRequest(
                                "http://localhost:" + shop.getAddress().getPort() + "/product/6", ""),
                            user)));
    assertThat(error).isNotNull();
    assertThat(error.getMessage()).contains("抓取过于频繁");
    assertThat(error.getMessage()).contains("秒");
    assertThat(requests.get()).as("被限频的请求**不该打到外部站点**").isEqualTo(1);

    // 🔴 另一个租户不受影响（限频是每租户的）
    var other =
        as(
            actorB,
            "channel.operation.create",
            () ->
                service.collect(
                    new CollectListingRequest(
                        "http://localhost:" + shop.getAddress().getPort() + "/product/6", ""),
                    user));
    assertThat(other.task().state()).isEqualTo("SUCCEEDED");
    assertThat(requests.get()).isEqualTo(2);
  }

  // ==================================================================
  // 5. 🔴 重定向守卫
  // ==================================================================

  @Test
  @DisplayName("4.4.1.1 白名单域名把请求引到白名单外 ⇒ 拒绝跟随")
  void refusesRedirectOutsideAllowlist() {
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () -> service.collect(new CollectListingRequest(url("/redirect-out"), ""), user)));
    assertThat(error).isNotNull();
    assertThat(error.getMessage()).contains("不在平台白名单内的域名");
    // 🔴 白名单外一律 400：重定向到白名单外与直接提交白名单外 URL 是同一件事
    assertThat(error).hasMessageContaining("400");
    assertThat(requests.get()).as("只该有一次请求（重定向本身），不该跟到白名单外").isEqualTo(1);
  }

  @Test
  @DisplayName("4.4.1.1 白名单内的重定向可以跟随，最终 URL 落在草稿里")
  void followsRedirectInsideAllowlist() {
    var result =
        as(actorA, "channel.operation.create", () -> service.collect(new CollectListingRequest(url("/redirect-ok"), ""), user));
    assertThat(result.task().state()).isEqualTo("SUCCEEDED");
    assertThat(result.task().finalUrl()).isEqualTo(url("/product/1"));
    assertThat(result.draft().title()).isEqualTo("JSON-LD 标题 商品-1");
  }

  // ==================================================================
  // 6. 🔴 失败留痕
  // ==================================================================

  @Test
  @DisplayName("4.4.1.1 外部站点 500 ⇒ 任务 FAILED + 失败原因，且不产生草稿")
  void recordsFailure() {
    var result =
        as(actorA, "channel.operation.create", () -> service.collect(new CollectListingRequest(url("/boom"), ""), user));
    assertThat(result.task().state()).isEqualTo("FAILED");
    assertThat(result.task().errorMessage()).contains("500");
    assertThat(result.task().httpStatus()).isEqualTo(500);
    assertThat(result.draft()).as("失败不产生草稿").isNull();
    assertThat(result.task().attemptNo()).isEqualTo(1);
  }

  @Test
  @DisplayName("4.4.1.1 页面里没有商品信息 ⇒ 失败并说清原因（不静默产出一份空草稿）")
  void failsWhenNoProductFound() {
    var result =
        as(actorA, "channel.operation.create", () -> service.collect(new CollectListingRequest(url("/no-product"), ""), user));
    assertThat(result.task().state()).isEqualTo("FAILED");
    assertThat(result.task().errorMessage()).contains("没有任何商品信号");
    assertThat(result.task().errorMessage()).contains("404 - 页面不存在");
    assertThat(result.draft()).isNull();
  }

  @Test
  @DisplayName("4.4.1.1 失败的 URL 可以重试（去重只挡成功过的 URL）")
  void failedUrlCanBeRetried() {
    var failed =
        as(actorA, "channel.operation.create", () -> service.collect(new CollectListingRequest(url("/boom"), ""), user));
    assertThat(failed.task().state()).isEqualTo("FAILED");
    assertThat(failed.task().attemptNo()).isEqualTo(1);

    // 同一个 URL 再来一次：走第 2 次尝试（不是被去重挡掉）
    var second =
        as(actorA, "channel.operation.create", () -> service.collect(new CollectListingRequest(url("/boom"), ""), user));
    assertThat(second.task().attemptNo()).isEqualTo(2);
    assertThat(second.deduplicated()).isFalse();
  }

  // ==================================================================
  // 7. 🔴 只产生草稿（边界一）
  // ==================================================================

  @Test
  @DisplayName("4.4.1.1 采集只产生草稿：不写 listing / listing_variant / 内容快照")
  void collectsNothingButDraft() {
    as(actorA, "channel.operation.create", () -> service.collect(new CollectListingRequest(url("/product/2"), ""), user));

    assertThat(countAsTenant(a, "select count(*) from listing_collect_draft")).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from listing_collect_task")).isEqualTo(1);
    // 🔴 采集**不建任何商品**：listing / listing_variant / listing_content_snapshot 一行都不能有
    assertThat(countAsTenant(a, "select count(*) from listing")).isZero();
    assertThat(countAsTenant(a, "select count(*) from listing_variant")).isZero();
    assertThat(countAsTenant(a, "select count(*) from listing_content_snapshot")).isZero();
    // 🔴 也不碰 4.4.2.1 商品认领的表（本模块与跨店搬家语义完全不同）
    assertThat(countAsTenant(a, "select count(*) from listing_claim")).isZero();
    assertThat(countAsTenant(a, "select count(*) from listing_claim_field")).isZero();
  }

  @Test
  @DisplayName("4.4.1.1 图片只记 URL：草稿里存的是地址字符串，没有媒体表被写")
  void storesImageUrlsOnly() {
    var result =
        as(actorA, "channel.operation.create", () -> service.collect(new CollectListingRequest(url("/product/4"), ""), user));
    var draft = result.draft();
    assertThat(draft.mainImageUrl()).startsWith("https://cdn.example.com/");
    assertThat(draft.imageUrls()).allMatch(url -> url.startsWith("http"));

    // 🔴 channel 库里没有任何媒体表（媒体库归 03 catalog），本模块也**不建**一张
    assertThat(scalar("select count(*) from pg_class where relname like 'listing_collect_media%'"))
        .as("本模块不建媒体表").isEqualTo("0");
    // 🔴 草稿表里没有 sku / spu / 内部可售量 / 我方售价列
    for (String column : List.of("sku_id", "spu_id", "internal_sku_id", "stock", "price")) {
      assertThat(scalar("select count(*) from information_schema.columns"
              + " where table_name='listing_collect_draft' and column_name='" + column + "'"))
          .as("采集草稿不该有 " + column + " 列")
          .isEqualTo("0");
    }
  }

  // ==================================================================
  // 8. 时间与金额往返
  // ==================================================================

  @Test
  @DisplayName("4.4.1.1 timestamptz 往返：抓取时刻写回的值逐字相等")
  void capturedAtRoundTrips() {
    var result =
        as(actorA, "channel.operation.create", () -> service.collect(new CollectListingRequest(url("/product/11"), ""), user));
    OffsetDateTime captured = result.draft().capturedAt();
    assertThat(captured).isNotNull();

    // 🔴 用同一条 JDBC 路径把值读回来比 —— 不用 PG 的 ::text 格式（那是 PG 的输出格式，
    //    与 OffsetDateTime 的 toString 差一个时区写法与微秒补零，比它等于在比格式而不是比值）。
    OffsetDateTime stored =
        queryValue(
            actorA,
            OffsetDateTime.class,
            "select captured_at from listing_collect_draft where id=?",
            result.draft().draftId());
    assertThat(stored).as("抓取时刻写回的值必须逐字相等").isEqualTo(captured);
    // 🔴 时刻本身也必须合理：不能是 1970，也不能是未来
    assertThat(stored.getYear()).isBetween(2020, 2100);
  }

  @Test
  @DisplayName("4.4.1.1 金额是 NUMERIC：外部页面原文标价往返相等，且不是浮点")
  void priceIsNumeric() {
    var result =
        as(actorA, "channel.operation.create", () -> service.collect(new CollectListingRequest(url("/product/12"), ""), user));
    BigDecimal amount = result.draft().sourcePriceAmount();
    assertThat(amount).isEqualByComparingTo(new BigDecimal("88.80"));

    String type =
        scalar(
            "select data_type from information_schema.columns"
                + " where table_name='listing_collect_draft' and column_name='source_price_amount'");
    assertThat(type).isEqualTo("numeric");
    String precision =
        scalar(
            "select numeric_precision||','||numeric_scale from information_schema.columns"
                + " where table_name='listing_collect_draft' and column_name='source_price_amount'");
    assertThat(precision).isEqualTo("19,4");
  }

  // ==================================================================
  // 9. 列表 / 详情 / 能力
  // ==================================================================

  @Test
  @DisplayName("4.4.1.1 采集历史真分页 + 状态筛选 + 时间倒序")
  void listsHistoryWithPagingAndFilter() {
    for (int i = 1; i <= 3; i++) {
      String target = url("/product/2" + i);
      as(actorA, "channel.operation.create", () -> service.collect(new CollectListingRequest(target, ""), user));
    }
    as(actorA, "channel.operation.create", () -> service.collect(new CollectListingRequest(url("/boom"), ""), user));

    var all = as(actorA, "channel.operation.read", () -> service.list(null, null, 0, 50));
    assertThat(all.total()).isEqualTo(4);
    assertThat(all.items()).hasSize(4);
    // 🔴 时间倒序：最后创建的排在最前
    assertThat(all.items().get(0).state()).isEqualTo("FAILED");
    assertThat(all.items().get(3).state()).isEqualTo("SUCCEEDED");

    var firstPage = as(actorA, "channel.operation.read", () -> service.list(null, null, 0, 2));
    var secondPage = as(actorA, "channel.operation.read", () -> service.list(null, null, 1, 2));
    assertThat(firstPage.items()).hasSize(2);
    assertThat(secondPage.items()).hasSize(2);
    assertThat(firstPage.total()).isEqualTo(4);
    assertThat(firstPage.items().get(0).taskId()).isNotEqualTo(secondPage.items().get(0).taskId());

    var succeeded = as(actorA, "channel.operation.read", () -> service.list("SUCCEEDED", null, 0, 50));
    assertThat(succeeded.total()).isEqualTo(3);
    assertThat(succeeded.items()).allMatch(row -> "SUCCEEDED".equals(row.state()));

    var failed = as(actorA, "channel.operation.read", () -> service.list("FAILED", null, 0, 50));
    assertThat(failed.total()).isEqualTo(1);

    var bySource = as(actorA, "channel.operation.read", () -> service.list(null, "TEST_LOOPBACK_IP", 0, 50));
    assertThat(bySource.total()).isEqualTo(4);
    assertThat(bySource.items().get(0).sourceCode()).isEqualTo("TEST_LOOPBACK_IP");
  }

  @Test
  @DisplayName("4.4.1.1 未知状态被 422 拒绝，不是静默忽略")
  void rejectsUnknownStateFilter() {
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as(actorA, "channel.operation.read", () -> service.list("NOPE", null, 0, 50)));
    assertThat(error).isNotNull();
    assertThat(error.getMessage()).contains("未知的抓取状态");
  }

  @Test
  @DisplayName("4.4.1.1 详情返回任务 + 草稿；不存在的 id 404")
  void detailAndNotFound() {
    var result =
        as(actorA, "channel.operation.create", () -> service.collect(new CollectListingRequest(url("/product/13"), ""), user));
    var detail = as(actorA, "channel.operation.read", () -> service.detail(result.task().taskId().toString()));
    assertThat(detail.task().taskId()).isEqualTo(result.task().taskId());
    assertThat(detail.draft()).isNotNull();
    assertThat(detail.draft().title()).isEqualTo(result.draft().title());

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorA, "channel.operation.read", () -> service.detail(UUID.randomUUID().toString()))))
        .isNotNull();
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorA, "channel.operation.read", () -> service.detail("not-a-uuid"))))
        .isNotNull();
  }

  @Test
  @DisplayName("4.4.1.1 能力表：未交付段 available=false + ownerModule + impact")
  void capabilitiesDeclareBoundaries() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    assertThat(caps.draftOnly()).as("采集只产生草稿").isTrue();
    assertThat(caps.mediaDownloaded()).as("媒体库归 03，本模块不下载").isFalse();
    assertThat(caps.deduplicated()).isTrue();
    assertThat(caps.rateLimited()).isTrue();
    assertThat(caps.sources()).isNotEmpty();
    assertThat(caps.sources().stream().map(ListingCollectSourceRow::host)).contains("1688.com", "taobao.com");

    var byDimension = caps.dimensions().stream().collect(java.util.stream.Collectors.toMap(d -> d.dimension(), d -> d));
    assertThat(byDimension.get("media_library").available()).isFalse();
    assertThat(byDimension.get("media_library").ownerModule()).contains("catalog");
    assertThat(byDimension.get("media_library").impact()).contains("只记录图片 URL");
    assertThat(byDimension.get("collection_box").available()).isFalse();
    assertThat(byDimension.get("collection_box").ownerModule()).contains("4.4.1.3");
    assertThat(byDimension.get("batch_collect").available()).isFalse();
    assertThat(byDimension.get("batch_collect").ownerModule()).contains("4.4.1.2");
    assertThat(byDimension.get("claim_to_spu").available()).isFalse();
    assertThat(byDimension.get("claim_to_spu").ownerModule()).contains("3.1.1.1");
    assertThat(byDimension.get("price").available()).isFalse();
    assertThat(byDimension.get("price").ownerModule()).contains("05");
    assertThat(byDimension.get("source_allowlist").available()).isTrue();
    assertThat(byDimension.get("dedup").available()).isTrue();
    assertThat(byDimension.get("rate_limit").available()).isTrue();
  }

  @Test
  @DisplayName("4.4.1.1 非法 URL 被 400 拒绝（scheme / 主机缺失）")
  void rejectsMalformedUrl() {
    for (String bad : List.of("not-a-url", "ftp://1688.com/x", "file:///etc/passwd", "")) {
      var error =
          org.assertj.core.api.Assertions.catchThrowable(
              () ->
                  as(
                      actorA,
                      "channel.operation.create",
                      () -> service.collect(new CollectListingRequest(bad, ""), user)));
      assertThat(error).as("非法 URL 应当被拒：" + bad).isNotNull();
    }
    assertThat(requests.get()).isZero();
  }

  @Test
  @DisplayName("4.4.1.1 白名单外 ⇒ 400；无会话 ⇒ 401（HTTP 面）")
  void httpSurface() throws Exception {
    http.perform(get("/channel/listing-collects/capabilities")).andExpect(status().isUnauthorized());
    http.perform(get("/channel/listing-collects").header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.total").isNumber());
    http.perform(get("/channel/listing-collects/" + UUID.randomUUID()).header("Authorization", bearerA))
        .andExpect(status().isNotFound());
  }
}
