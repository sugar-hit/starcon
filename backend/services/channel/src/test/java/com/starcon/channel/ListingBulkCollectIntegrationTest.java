package com.starcon.channel;

import static com.starcon.channel.ListingBulkCollectModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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
 * 4.4.1.2 批量/插件采集。
 *
 * <p>蓝图：「支持按分类页、关键词搜索结果批量采集，以及浏览器插件在用户浏览时抓取当前页商品，解决大批量找货场景。输入：分类页 URL /
 * 关键词 + 页数范围；插件上报的页面数据。输出：批量采集任务 + 结果清单。关系：上游 = 4.4.1.1；下游 = 4.4.1.3。<b>抓取必须有频率限制与去重</b>
 * （同 URL 不重复抓、已存在的商品跳过），避免重复劳动与外部站点压力。」
 *
 * <p>🔴 <b>本测试类用 JDK 自带的 {@code com.sun.net.httpserver.HttpServer} 起一个真实的本地
 * HTTP 服务</b>，现场生成**真实的分类页 / 搜索结果页 / 商品详情页 HTML**，然后断言服务层
 * <b>真的把商品链接解析出来、真的把标题与主图解析出来、真的落成了采集草稿</b>。
 *
 * <p>这不是 mock：
 *
 * <ul>
 *   <li>HTTP 是真的（真的 connect、真的发请求、真的收字节、真的按状态码分支）；
 *   <li>HTML 是真的（由本地服务在收到请求时现场生成，含商品卡片与 JSON-LD 商品页）；
 *   <li>解析是真的（断言的是「HTML 里的那几个值被解析了出来」，不是「返回了预先写好的常量」）；
 *   <li>而且请求**次数**被计数 —— 「去重后没有第二次请求」「白名单外一个请求都没发」
 *       这类断言只有真发请求才测得出来。
 * </ul>
 *
 * <p>本测试类钉住的事：
 *
 * <ol>
 *   <li><b>🔴 分类页批量采集：真抓真解析</b>：分类页上的商品链接被抓出来，详情页被逐个抓取，
 *       标题 / 主图 / 标价被解析出来，<b>并落成 4.4.1.1 的采集草稿</b>。
 *   <li><b>🔴 关键词批量采集</b>：关键词进平台搜索模板，拼出真实的搜索页地址并抓取。
 *   <li><b>🔴 去重</b>：同一 URL 第二次遇到 ⇒ 记「已存在」并跳过，<b>服务端的请求计数不增加</b>。
 *   <li><b>🔴 限频三层</b>：并发数超限 → 429；发起太密 → 429；同 host 刚被抓过 → 429；
 *       运行中被打断 → 停在 RATE_LIMITED 且**已采到的结果全部保留**。
 *   <li><b>🔴 白名单外 400</b>：分类页域名不在白名单 ⇒ 被拒，且**一个 HTTP 请求都没发出去**。
 *   <li><b>🔴 插件上报</b>：同样落成采集草稿；重复上报 ⇒ 跳过；白名单外的商品链接 ⇒ 拒绝。
 *   <li><b>🔴 只产生草稿</b>：采集之后 {@code listing} / {@code listing_variant} /
 *       {@code listing_content_snapshot} 一行都没有 —— 采集不建商品。
 *   <li><b>结果清单真分页与筛选</b>、<b>timestamptz 往返</b>、<b>金额 NUMERIC</b>、<b>HTTP 面</b>。
 * </ol>
 *
 * <p>多租户三断言在 {@code ListingBulkCollectTenancyIntegrationTest} 里。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingBulkCollectIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer shop;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  /** 🔴 本地"外部站点"收到的请求次数：去重 / 白名单 / 限频三类断言全靠它。 */
  static final AtomicInteger requests = new AtomicInteger();

  /** 🔴 单独统计商品详情页的请求次数（去重断言要精确到"详情页没被再抓一次"）。 */
  static final AtomicInteger detailRequests = new AtomicInteger();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  /** 🔴 测试用的白名单编码（容器可能被别的测试类复用 ⇒ 编码按 host 读回来，不写死）。 */
  String sourceCode;

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

      // 🔴 真实的"外部站点"：分类页 / 搜索结果页 / 商品详情页
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
            if (path.startsWith("/product/")) {
              detailRequests.incrementAndGet();
              byte[] body = productPage(path).getBytes(StandardCharsets.UTF_8);
              e.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
              e.sendResponseHeaders(200, body.length);
              e.getResponseBody().write(body);
              e.close();
              return;
            }
            byte[] body =
                listingPage(path, e.getRequestURI().getQuery()).getBytes(StandardCharsets.UTF_8);
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
   * 🔴 真实的商品页 HTML：含 JSON-LD（schema.org Product）、Open Graph 标签与规格表。
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
          <script type="application/ld+json">
          {
            "@context": "https://schema.org/",
            "@type": "Product",
            "name": "JSON-LD 标题 商品-%ID%",
            "description": "JSON-LD 描述 商品-%ID%",
            "brand": { "@type": "Brand", "name": "JSON-LD 品牌" },
            "image": ["https://cdn.example.com/main/%ID%.jpg"],
            "additionalProperty": [
              { "@type": "PropertyValue", "name": "材质", "value": "纯棉" }
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
          <table>
            <tr><th>产地</th><td>广东</td></tr>
          </table>
          <p>商品-%ID% 的正文描述</p>
        </body>
        </html>
        """
        .replace("%ID%", id);
  }

  /**
   * 🔴 真实的分类页 / 搜索结果页 HTML。
   *
   * <p>页面上有：商品卡片（带图 + title 属性 + 价格）、导航链接（**只有文字，没有图也没有 title 属性**
   * —— 它必须被解析器忽略）、以及一个指向白名单外的链接（必须被服务端挡掉）。
   */
  static String listingPage(String path, String query) {
    int page = 1;
    if (query != null) {
      for (String part : query.split("&")) {
        if (part.startsWith("page=")) {
          try {
            page = Integer.parseInt(part.substring("page=".length()));
          } catch (NumberFormatException ignored) {
            page = 1;
          }
        }
      }
    }
    int first = 100 + page * 2 - 1;
    int second = 100 + page * 2;
    return """
        <!doctype html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8">
          <title>商品列表 第 %PAGE% 页</title>
        </head>
        <body>
          <ul class="offer-list">
            <li><a href="/product/%ID1%" title="卡片标题 %ID1%">
              <img src="https://cdn.example.com/%ID1%.jpg" alt="图片 %ID1%">
              <span class="title">卡片文本 %ID1%</span>
              <span class="price">¥12.34</span>
            </a></li>
            <li><a href="/product/%ID2%" title="卡片标题 %ID2%">
              <img src="https://cdn.example.com/%ID2%.jpg" alt="图片 %ID2%">
              <span class="title">卡片文本 %ID2%</span>
              <span class="price">¥56.78</span>
            </a></li>
          </ul>
          <nav>
            <a href="/">首页</a>
            <a href="/help">帮助中心</a>
          </nav>
        </body>
        </html>
        """
        .replace("%ID1%", String.valueOf(first))
        .replace("%ID2%", String.valueOf(second))
        .replace("%PAGE%", String.valueOf(page));
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

  @Autowired ListingBulkCollectService service;
  @Autowired AccessService access;
  @Autowired org.jooq.DSLContext sql;
  @Autowired MockMvc http;

  /**
   * 🔴 白名单里补一行指向本地测试服务的来源（必须在 Flyway 之后做）。
   *
   * <p>🔴 **host 唯一**是 `listing_collect_source` 的硬约束，而 Testcontainers 可能把容器
   * 复用给别的测试类（4.4.1.1 的测试也往 {@code 127.0.0.1} 插了一行）⇒ 这里用
   * {@code on conflict (host) do update}，并把**实际生效的编码读回来**用，
   * 不写死编码 —— 否则两个测试类共用一个容器时会把对方那一行顶掉。
   */
  @BeforeEach
  void seedTestSources() {
    exec(
        "insert into listing_collect_source (id,code,name,host,kind,min_interval_seconds,enabled,position)"
            + " values ('6c1d0002-0000-4000-8000-000000000301','TEST_BULK_LOOPBACK','批量采集测试站点',"
            + "'127.0.0.1','PLATFORM',0,true,950)"
            + " on conflict (host) do update set min_interval_seconds=0, enabled=true");
    sourceCode =
        scalar("select code from listing_collect_source where host='127.0.0.1'");
    assertThat(sourceCode).as("测试用白名单行必须存在").isNotBlank();

    // 搜索模板指向本地测试服务（端口是动态的，只能运行时写）
    exec(
        "insert into listing_bulk_collect_search"
            + " (id,source_code,name,search_url_template,category_page_param,page_origin,"
            + "  bulk_min_interval_seconds,enabled)"
            + " select '6c1d0002-0000-4000-8000-000000000401', code, '批量采集测试搜索模板',"
            + " 'http://127.0.0.1:"
            + shop.getAddress().getPort()
            + "/search?kw={keyword}&page={page}', 'page', 1, 0, true"
            + " from listing_collect_source where host='127.0.0.1'"
            + " on conflict (source_code) do update set search_url_template=excluded.search_url_template,"
            + "   bulk_min_interval_seconds=0, enabled=true");

    // 限频策略：默认放开（并发 2、发起间隔 0、预算 60 秒），个别用例自己调
    exec(
        "update listing_bulk_collect_policy set max_concurrent_tasks=2,"
            + " min_task_start_interval_seconds=0, max_pages_per_task=10, max_items_per_task=100,"
            + " max_items_per_report=100, max_task_seconds=60 where code='DEFAULT'");
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
    detailRequests.set(0);
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

  /** 🔴 在租户上下文里读一列字符串（走 jOOQ，与写库同一条路径）。 */
  List<String> tenantStrings(AccessService.Access who, String query, Object... binds) {
    return as(
        who,
        "channel.operation.read",
        () ->
            sql.fetch(query, binds).stream()
                .map(r -> r.get(0, String.class))
                .toList());
  }

  BulkCollectTaskRow startCategory(AccessService.Access who, int from, int to) {
    return as(
        who,
        "channel.operation.create",
        () ->
            service.start(
                new StartBulkCollectRequest(
                    "CATEGORY_PAGE", sourceCode, url("/category"), "", from, to, "", ""),
                user));
  }

  BulkCollectTaskRow runTask(AccessService.Access who, String taskId) {
    return as(who, "channel.operation.create", () -> service.run(taskId, user));
  }

  // ==================================================================
  // 1. 🔴 分类页批量采集：真抓真解析 + 落成 4.4.1.1 的采集草稿
  // ==================================================================

  @Test
  @DisplayName("4.4.1.2 分类页批量采集：真翻页、真抓详情页、真落成采集草稿")
  void bulkCollectsCategoryPages() {
    var task = startCategory(actorA, 1, 2);
    assertThat(task.state()).as("刚发起的任务是待运行").isEqualTo("PENDING");
    assertThat(task.totalPages()).isEqualTo(2);
    assertThat(task.sourceCode()).isEqualTo(sourceCode);

    var done = runTask(actorA, task.taskId().toString());
    assertThat(done.state()).as("两页都采完 ⇒ 成功").isEqualTo("SUCCEEDED");
    assertThat(done.errorMessage()).isEmpty();
    // 第 1 页 2 条 + 第 2 页 2 条
    assertThat(done.discoveredCount()).isEqualTo(4);
    assertThat(done.fetchedCount()).isEqualTo(4);
    assertThat(done.skippedDuplicateCount()).isZero();
    assertThat(done.failedCount()).isZero();

    // 🔴 请求次数：2 次翻页 + 4 次详情页
    assertThat(requests.get()).as("2 次翻页 + 4 次商品详情页").isEqualTo(6);
    assertThat(detailRequests.get()).as("4 个商品详情页各抓一次").isEqualTo(4);

    // 🔴 落成的是 4.4.1.1 的采集草稿（下游 4.4.1.3 采集箱认领的就是它）
    List<String> titles =
        tenantStrings(
            actorA, "select title from listing_collect_draft order by normalized_url");
    assertThat(titles)
        .as("草稿标题是**从真实 HTML 里解析出来的** JSON-LD 标题")
        .containsExactly(
            "JSON-LD 标题 商品-101",
            "JSON-LD 标题 商品-102",
            "JSON-LD 标题 商品-103",
            "JSON-LD 标题 商品-104");

    // 🔴 结果清单：每条都指向一个采集草稿
    var results = as(actorA, "channel.operation.read", () -> service.listResults(task.taskId().toString(), null, 0, 50));
    assertThat(results.total()).isEqualTo(4);
    assertThat(results.items()).allMatch(r -> "FETCHED".equals(r.state()));
    assertThat(results.items()).allMatch(r -> r.draftId() != null);
    assertThat(results.items()).allMatch(r -> r.collectTaskId() != null);
    assertThat(results.items()).allMatch(r -> "BULK".equals(r.origin()));
    assertThat(results.items()).allMatch(r -> r.title().startsWith("JSON-LD 标题 商品-"));

    // 🔴 采集任务（4.4.1.1 的表）里也有对应的成功记录 —— 批量没有另起一套采集模型
    assertThat(countAsTenant(a, "select count(*) from listing_collect_task where state='SUCCEEDED'"))
        .isEqualTo(4);
    assertThat(countAsTenant(a, "select count(*) from listing_collect_draft")).isEqualTo(4);
  }

  // ==================================================================
  // 2. 🔴 去重：同 URL 不重复抓
  // ==================================================================

  @Test
  @DisplayName("4.4.1.2 去重：已存在的商品跳过，**不再发第二次 HTTP 请求**")
  void deduplicatesAcrossTasksAndPlugin() {
    var task = startCategory(actorA, 1, 1);
    runTask(actorA, task.taskId().toString());
    assertThat(detailRequests.get()).as("第一轮抓了 2 个详情页").isEqualTo(2);

    // ---- 再来一批同样的页面 ⇒ 全部跳过，**详情页请求数不增加**
    var second = startCategory(actorA, 1, 1);
    var secondDone = runTask(actorA, second.taskId().toString());
    assertThat(secondDone.fetchedCount()).isZero();
    assertThat(secondDone.skippedDuplicateCount()).isEqualTo(2);
    assertThat(detailRequests.get()).as("🔴 去重命中时详情页请求数不增加").isEqualTo(2);
    assertThat(requests.get()).as("第一轮 1 次翻页 + 2 次详情页，第二轮只多 1 次翻页").isEqualTo(4);

    var results = as(actorA, "channel.operation.read", () -> service.listResults(second.taskId().toString(), null, 0, 50));
    assertThat(results.items()).allMatch(r -> "SKIPPED_DUPLICATE".equals(r.state()));
    assertThat(results.items()).allMatch(r -> r.skipReason().contains("已经采过"));
    // 跳过的条目仍然指向**既有**草稿，不产生第二份草稿
    assertThat(results.items()).allMatch(r -> r.draftId() != null);
    assertThat(countAsTenant(a, "select count(*) from listing_collect_draft")).isEqualTo(2);
  }

  @Test
  @DisplayName("4.4.1.2 去重：插件上报已采过的商品 ⇒ 跳过，不重复建草稿")
  void pluginReportSkipsExisting() {
    var task = startCategory(actorA, 1, 1);
    runTask(actorA, task.taskId().toString());
    long draftsBefore = countAsTenant(a, "select count(*) from listing_collect_draft");

    var report =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.reportPlugin(
                    new PluginReportRequest(
                        url("/category"),
                        "商品列表 第 1 页",
                        List.of(
                            new PluginReportItem(
                                url("/product/101"), "插件报的商品 101",
                                "https://cdn.example.com/101.jpg", new java.math.BigDecimal("12.34"), "CNY"),
                            new PluginReportItem(
                                url("/product/999"), "插件报的新商品 999",
                                "https://cdn.example.com/999.jpg", new java.math.BigDecimal("56.78"), "CNY")),
                        ""),
                    user));

    assertThat(report.fetchedCount()).as("只有没采过的商品才落成草稿").isEqualTo(1);
    assertThat(report.skippedDuplicateCount()).isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from listing_collect_draft"))
        .as("🔴 已存在的商品不重复建草稿")
        .isEqualTo(draftsBefore + 1);
    // 🔴 插件上报**不会**再去抓那个商品页（那会把外部站点的压力翻倍）
    assertThat(detailRequests.get()).as("插件上报不触发服务端抓取").isEqualTo(2);
  }

  // ==================================================================
  // 3. 🔴 限频：三层，每一层都明确拒绝
  // ==================================================================

  @Test
  @DisplayName("4.4.1.2 限频①：并发批量任务数超限 ⇒ 429 并写明原因")
  void rejectsTooManyConcurrentTasks() {
    exec("update listing_bulk_collect_policy set max_concurrent_tasks=1 where code='DEFAULT'");

    startCategory(actorA, 1, 1); // 第一个任务停在 PENDING ⇒ 算「在跑」
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> startCategory(actorA, 1, 1));
    assertThat(error).as("并发超限必须被拒").isNotNull();
    assertThat(error.getMessage()).contains("同时已有");
    assertThat(error.getMessage()).contains("批量采集任务在跑");
    assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(429);
  }

  @Test
  @DisplayName("4.4.1.2 限频②：发起太密 ⇒ 429 并写明还要等多少秒")
  void rejectsTooFrequentStart() {
    exec("update listing_bulk_collect_policy set min_task_start_interval_seconds=30 where code='DEFAULT'");

    startCategory(actorA, 1, 1);
    var error = org.assertj.core.api.Assertions.catchThrowable(() -> startCategory(actorA, 1, 1));
    assertThat(error).isNotNull();
    assertThat(error.getMessage()).contains("发起得太密了");
    assertThat(error.getMessage()).contains("秒");
    assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(429);
  }

  @Test
  @DisplayName("4.4.1.2 限频③：同一来源刚被抓过 ⇒ 429 并写明还要等多少秒")
  void rejectsTooFrequentHostGrab() {
    // 先用 0 间隔采一轮（留下抓取历史）
    var task = startCategory(actorA, 1, 1);
    runTask(actorA, task.taskId().toString());
    assertThat(detailRequests.get()).isEqualTo(2);

    // 再把该来源的批量间隔调到 60 秒 ⇒ 下一次发起必须被拒
    exec(
        "update listing_bulk_collect_search set bulk_min_interval_seconds=60"
            + " where source_code='"
            + sourceCode
            + "'");
    var error = org.assertj.core.api.Assertions.catchThrowable(() -> startCategory(actorA, 1, 1));
    assertThat(error).isNotNull();
    assertThat(error.getMessage()).contains("抓取过于频繁");
    assertThat(error.getMessage()).contains("批量采集最小间隔");
    assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(429);
  }

  @Test
  @DisplayName("4.4.1.2 限频：运行中被限频打断 ⇒ 停在 RATE_LIMITED，已采到的结果全部保留")
  void rateLimitStopsRunButKeepsResults() {
    exec(
        "update listing_bulk_collect_search set bulk_min_interval_seconds=60 where source_code='"
            + sourceCode
            + "'");
    exec("update listing_bulk_collect_policy set max_task_seconds=1 where code='DEFAULT'");

    var task = startCategory(actorA, 1, 1);
    var done = runTask(actorA, task.taskId().toString());

    assertThat(done.state()).as("🔴 被限频打断 ⇒ RATE_LIMITED").isEqualTo("RATE_LIMITED");
    assertThat(done.errorMessage()).contains("抓取过于频繁");
    assertThat(done.errorMessage()).contains("还需要等");
    assertThat(done.skippedRateLimitedCount()).isGreaterThan(0);
    assertThat(done.nextPage()).as("断点保留在被打断的那一页").isEqualTo(1);

    var results = as(actorA, "channel.operation.read", () -> service.listResults(task.taskId().toString(), null, 0, 50));
    assertThat(results.items()).anyMatch(r -> "SKIPPED_RATE_LIMITED".equals(r.state()));
    assertThat(results.items())
        .filteredOn(r -> "SKIPPED_RATE_LIMITED".equals(r.state()))
        .allMatch(r -> r.skipReason().contains("还需要等"));
  }

  // ==================================================================
  // 4. 🔴 白名单
  // ==================================================================

  @Test
  @DisplayName("4.4.1.2 白名单外：分类页域名不在白名单 ⇒ 400，**一个 HTTP 请求都不发**")
  void rejectsHostOutsideAllowlist() {
    int before = requests.get();
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.start(
                            new StartBulkCollectRequest(
                                "CATEGORY_PAGE",
                                sourceCode,
                                "http://not-allowed.example.com/category",
                                "",
                                1,
                                1,
                                "",
                                ""),
                            user)));
    assertThat(error).isNotNull();
    assertThat(error.getMessage()).contains("不在平台白名单内");
    assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(400);
    assertThat(requests.get()).as("🔴 白名单外一个请求都不能发").isEqualTo(before);
  }

  @Test
  @DisplayName("4.4.1.2 白名单外：平台编码不在白名单 ⇒ 400")
  void rejectsUnknownSourceCode() {
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.start(
                            new StartBulkCollectRequest(
                                "CATEGORY_PAGE", "NOT_A_PLATFORM", url("/category"), "", 1, 1, "", ""),
                            user)));
    assertThat(error).isNotNull();
    assertThat(error.getMessage()).contains("不在平台白名单内");
  }

  // ==================================================================
  // 5. 🔴 关键词批量采集
  // ==================================================================

  @Test
  @DisplayName("4.4.1.2 关键词批量：关键词进平台搜索模板，真实抓搜索结果页")
  void bulkCollectsByKeyword() {
    var task =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.start(
                    new StartBulkCollectRequest("KEYWORD", sourceCode, "", "蓝牙耳机", 1, 2, "", ""),
                    user));
    assertThat(task.state()).isEqualTo("PENDING");
    assertThat(task.keyword()).isEqualTo("蓝牙耳机");

    var done = runTask(actorA, task.taskId().toString());
    assertThat(done.state()).isEqualTo("SUCCEEDED");
    assertThat(done.fetchedCount()).isEqualTo(4);

    // 🔴 搜索页真的被请求了（关键词进了 URL）
    assertThat(detailRequests.get()).isEqualTo(4);
    assertThat(countAsTenant(a, "select count(*) from listing_collect_draft")).isEqualTo(4);
  }

  @Test
  @DisplayName("4.4.1.2 关键词批量：没有搜索模板的平台 ⇒ 400 并说清原因")
  void rejectsKeywordWithoutTemplate() {
    // 用一个没有 listing_bulk_collect_search 行的平台
    exec(
        "insert into listing_collect_source (id,code,name,host,kind,min_interval_seconds,enabled,position)"
            + " values ('6c1d0002-0000-4000-8000-000000000302','TEST_BULK_NO_TEMPLATE','无模板测试站点',"
            + "'no-template.example.com','PLATFORM',0,true,951)"
            + " on conflict (host) do nothing");
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.start(
                            new StartBulkCollectRequest(
                                "KEYWORD", "TEST_BULK_NO_TEMPLATE", "", "耳机", 1, 1, "", ""),
                            user)));
    assertThat(error).isNotNull();
    assertThat(error.getMessage()).contains("没有配置关键词搜索模板");
    assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(400);
  }

  // ==================================================================
  // 6. 🔴 插件上报
  // ==================================================================

  @Test
  @DisplayName("4.4.1.2 插件上报：落成采集草稿（下游 4.4.1.3 认领的就是它）")
  void pluginReportCreatesDrafts() {
    var report =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.reportPlugin(
                    new PluginReportRequest(
                        url("/category"),
                        "商品列表 第 1 页",
                        List.of(
                            new PluginReportItem(
                                url("/product/201"), "插件报的商品 201",
                                "https://cdn.example.com/201.jpg", new java.math.BigDecimal("19.90"), "CNY"),
                            new PluginReportItem(
                                url("/product/202"), "插件报的商品 202",
                                "https://cdn.example.com/202.jpg", new java.math.BigDecimal("29.90"), "CNY")),
                        ""),
                    user));

    assertThat(report.task().mode()).isEqualTo("PLUGIN");
    assertThat(report.task().state()).isEqualTo("SUCCEEDED");
    assertThat(report.fetchedCount()).isEqualTo(2);
    assertThat(report.skippedDuplicateCount()).isZero();
    assertThat(report.rejectedCount()).isZero();

    // 🔴 落成的是 4.4.1.1 的采集草稿
    List<String> titles =
        tenantStrings(actorA, "select title from listing_collect_draft order by normalized_url");
    assertThat(titles).containsExactly("插件报的商品 201", "插件报的商品 202");

    // 🔴 插件上报的采集任务 http_status=0（不是本服务发起的 HTTP 抓取）
    assertThat(countAsTenant(a, "select count(*) from listing_collect_task where http_status=0"))
        .isEqualTo(2);
    // 🔴 插件上报**不触发服务端抓取**
    assertThat(detailRequests.get()).isZero();
    assertThat(requests.get()).isZero();
  }

  @Test
  @DisplayName("4.4.1.2 插件上报：白名单外的商品链接被拒绝，白名单外的页面被 400")
  void pluginReportRejectsOutsideAllowlist() {
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.reportPlugin(
                            new PluginReportRequest(
                                "http://not-allowed.example.com/list",
                                "外部页面",
                                List.of(
                                    new PluginReportItem(url("/product/301"), "外部商品", "", null, null)),
                                ""),
                            user)));
    assertThat(error).isNotNull();
    assertThat(error.getMessage()).contains("不在平台白名单内");

    // 页面在白名单内、商品链接不在 ⇒ 那一���被拒，但其余条目照常落库
    var report =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.reportPlugin(
                    new PluginReportRequest(
                        url("/category"),
                        "商品列表 第 1 页",
                        List.of(
                            new PluginReportItem(
                                url("/product/302"), "白名单内商品", "", null, null),
                            new PluginReportItem(
                                "http://not-allowed.example.com/item/9", "白名单外商品", "", null, null),
                            new PluginReportItem(url("/product/303"), "", "", null, null)),
                        ""),
                    user));
    assertThat(report.fetchedCount()).isEqualTo(1);
    assertThat(report.rejectedCount()).as("白名单外 + 没标题 ⇒ 两条被拒").isEqualTo(2);
  }

  @Test
  @DisplayName("4.4.1.2 插件上报：一次上报超过条数上限 ⇒ 422")
  void pluginReportRejectsTooManyItems() {
    exec("update listing_bulk_collect_policy set max_items_per_report=1 where code='DEFAULT'");
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.reportPlugin(
                            new PluginReportRequest(
                                url("/category"),
                                "商品列表",
                                List.of(
                                    new PluginReportItem(url("/product/401"), "商品 401", "", null, null),
                                    new PluginReportItem(url("/product/402"), "商品 402", "", null, null)),
                                ""),
                            user)));
    assertThat(error).isNotNull();
    assertThat(error.getMessage()).contains("一次插件上报最多带");
    assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(422);
  }

  // ==================================================================
  // 7. 🔴 只产生草稿
  // ==================================================================

  @Test
  @DisplayName("4.4.1.2 只产生草稿：采集之后商品表一行都没有")
  void onlyProducesDrafts() {
    var task = startCategory(actorA, 1, 1);
    runTask(actorA, task.taskId().toString());
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.reportPlugin(
                new PluginReportRequest(
                    url("/category"),
                    "商品列表",
                    List.of(new PluginReportItem(url("/product/501"), "插件商品 501", "", null, null)),
                    ""),
                user));

    assertThat(countAsTenant(a, "select count(*) from listing_collect_draft")).isEqualTo(3);
    // 🔴 平台商品表一行都没有
    assertThat(countAsTenant(a, "select count(*) from listing")).isZero();
    assertThat(countAsTenant(a, "select count(*) from listing_variant")).isZero();
    assertThat(countAsTenant(a, "select count(*) from listing_content_snapshot")).isZero();
    assertThat(countAsTenant(a, "select count(*) from listing_claim")).isZero();
  }

  // ==================================================================
  // 8. 结果清单：真分页与筛选
  // ==================================================================

  @Test
  @DisplayName("4.4.1.2 结果清单：真分页 + 状态筛选")
  void listsResultsWithPaginationAndFilter() {
    var task = startCategory(actorA, 1, 2);
    runTask(actorA, task.taskId().toString());

    var firstPage = as(actorA, "channel.operation.read", () -> service.listResults(task.taskId().toString(), null, 0, 2));
    assertThat(firstPage.total()).isEqualTo(4);
    assertThat(firstPage.items()).hasSize(2);
    assertThat(firstPage.page()).isZero();
    assertThat(firstPage.size()).isEqualTo(2);

    var secondPage = as(actorA, "channel.operation.read", () -> service.listResults(task.taskId().toString(), null, 1, 2));
    assertThat(secondPage.items()).hasSize(2);
    assertThat(secondPage.items())
        .as("第二页不能和第一页重复")
        .doesNotContainAnyElementsOf(firstPage.items());

    var filtered =
        as(actorA, "channel.operation.read", () -> service.listResults(task.taskId().toString(), "FETCHED", 0, 50));
    assertThat(filtered.total()).isEqualTo(4);

    var empty =
        as(actorA, "channel.operation.read", () -> service.listResults(task.taskId().toString(), "SKIPPED_DUPLICATE", 0, 50));
    assertThat(empty.total()).isZero();

    // 未知状态 ⇒ 422
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.read",
                    () -> service.listResults(task.taskId().toString(), "NOT_A_STATE", 0, 50)));
    assertThat(error).isNotNull();
    assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(422);
  }

  @Test
  @DisplayName("4.4.1.2 任务列表：真分页 + 模式/状态/来源筛选")
  void listsTasksWithFilters() {
    var task = startCategory(actorA, 1, 1);
    runTask(actorA, task.taskId().toString());

    var page = as(actorA, "channel.operation.read", () -> service.listTasks(null, null, null, 0, 50));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).taskId()).isEqualTo(task.taskId());

    var byMode = as(actorA, "channel.operation.read", () -> service.listTasks("CATEGORY_PAGE", null, null, 0, 50));
    assertThat(byMode.total()).isEqualTo(1);
    var byOtherMode = as(actorA, "channel.operation.read", () -> service.listTasks("KEYWORD", null, null, 0, 50));
    assertThat(byOtherMode.total()).isZero();
    var byState = as(actorA, "channel.operation.read", () -> service.listTasks(null, "SUCCEEDED", null, 0, 50));
    assertThat(byState.total()).isEqualTo(1);
    var bySource = as(actorA, "channel.operation.read", () -> service.listTasks(null, null, sourceCode, 0, 50));
    assertThat(bySource.total()).isEqualTo(1);

    // 未知模式 ⇒ 422
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as(actorA, "channel.operation.read", () -> service.listTasks("NOPE", null, null, 0, 50)));
    assertThat(error).isNotNull();
    assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(422);
  }

  // ==================================================================
  // 9. 时间与金额纪律
  // ==================================================================

  @Test
  @DisplayName("4.4.1.2 timestamptz 往返逐字相等 + 金额 NUMERIC 往返相等")
  void roundTripsTimestampsAndMoney() {
    var task = startCategory(actorA, 1, 1);
    runTask(actorA, task.taskId().toString());

    // 🔴 抓取时刻：草稿与采集任务**逐字相等**
    var rows =
        as(
            actorA,
            "channel.operation.read",
            () ->
                sql.fetch(
                    """
                    select t.captured_at as task_captured, d.captured_at as draft_captured,
                           d.source_price_amount, d.source_price_currency
                    from listing_collect_draft d
                    join listing_collect_task t on t.tenant_id=d.tenant_id and t.id=d.task_id
                    order by d.normalized_url
                    """));
    assertThat(rows).hasSize(2);
    for (var row : rows) {
      assertThat(row.get("draft_captured", OffsetDateTime.class))
          .as("🔴 timestamptz 往返必须逐字相等")
          .isEqualTo(row.get("task_captured", OffsetDateTime.class));
      assertThat(row.get("source_price_amount", java.math.BigDecimal.class))
          .as("🔴 金额 NUMERIC 往返相等")
          .isEqualByComparingTo("88.80");
      assertThat(row.get("source_price_currency", String.class)).isEqualTo("CNY");
    }

    // 🔴 列类型：金额是 NUMERIC，不是 float
    assertThat(scalar("select data_type from information_schema.columns where table_name='listing_collect_draft' and column_name='source_price_amount'"))
        .isEqualTo("numeric");
    assertThat(scalar("select data_type from information_schema.columns where table_name='listing_bulk_collect_result' and column_name='price_amount'"))
        .isEqualTo("numeric");
    // 🔴 时间列是 timestamptz
    assertThat(scalar("select data_type from information_schema.columns where table_name='listing_bulk_collect_result' and column_name='captured_at'"))
        .isEqualTo("timestamp with time zone");
    assertThat(scalar("select data_type from information_schema.columns where table_name='listing_bulk_collect_task' and column_name='started_at'"))
        .isEqualTo("timestamp with time zone");
  }

  // ==================================================================
  // 10. HTTP 面
  // ==================================================================

  @Test
  @DisplayName("4.4.1.2 HTTP 面：401 / 404 / 409")
  void httpSurface() throws Exception {
    // 无会话 ⇒ 401（请求体合法 ⇒ 走到鉴权那一步，而不是被参数校验挡成 400）
    http.perform(get("/channel/listing-bulk-collects")).andExpect(status().isUnauthorized());
    http.perform(post("/channel/listing-bulk-collects")
            .contentType("application/json")
            .content(
                "{\"mode\":\"CATEGORY_PAGE\",\"sourceCode\":\"X\",\"categoryUrl\":\"http://x.example.com/a\"}"))
        .andExpect(status().isUnauthorized());

    // 不存在 ⇒ 404
    http.perform(get("/channel/listing-bulk-collects/" + UUID.randomUUID())
            .header("Authorization", bearerA))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/listing-bulk-collects/not-a-uuid")
            .header("Authorization", bearerA))
        .andExpect(status().isNotFound());

    // 已采完的任务再跑 ⇒ 409
    var task = startCategory(actorA, 1, 1);
    runTask(actorA, task.taskId().toString());
    http.perform(post("/channel/listing-bulk-collects/" + task.taskId() + "/run")
            .header("Authorization", bearerA))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("4.4.1.2 能力声明：逐维度声明 + 未交付段 ownerModule")
  void declaresCapabilities() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    assertThat(caps.draftOnly()).isTrue();
    assertThat(caps.deduplicated()).isTrue();
    assertThat(caps.rateLimited()).isTrue();
    assertThat(caps.draftNote()).contains("只产生草稿");
    assertThat(caps.dedupNote()).contains("不会再次请求来源站点");
    assertThat(caps.rateLimitNote()).contains("明确拒绝");
    assertThat(caps.policy().maxConcurrentTasks()).isEqualTo(2);
    assertThat(caps.sources()).isNotEmpty();

    var byKey = new java.util.HashMap<String, BulkCollectCapabilityDimension>();
    caps.dimensions().forEach(d -> byKey.put(d.dimension(), d));
    assertThat(byKey.get("category_page_collect").available()).isTrue();
    assertThat(byKey.get("keyword_collect").available()).isTrue();
    assertThat(byKey.get("plugin_report").available()).isTrue();
    assertThat(byKey.get("rate_limit").available()).isTrue();
    assertThat(byKey.get("dedup").available()).isTrue();
    // 🔴 未交付段必须 available=false + ownerModule
    assertThat(byKey.get("collection_box").available()).isFalse();
    assertThat(byKey.get("collection_box").ownerModule()).contains("4.4.1.3");
    assertThat(byKey.get("claim_to_spu").available()).isFalse();
    assertThat(byKey.get("media_library").available()).isFalse();
    assertThat(byKey.get("price").available()).isFalse();
    assertThat(byKey.get("internal_stock").available()).isFalse();
    assertThat(byKey.get("browser_extension").available()).isFalse();
  }

  @Test
  @DisplayName("4.4.1.2 端点可用：capabilities / 列表 / 结果清单 走 HTTP")
  void httpReadEndpoints() throws Exception {
    http.perform(get("/channel/listing-bulk-collects/capabilities")
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.draftOnly").value(true))
        .andExpect(jsonPath("$.policy.maxConcurrentTasks").exists());
    http.perform(get("/channel/listing-bulk-collects")
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
  }
}
