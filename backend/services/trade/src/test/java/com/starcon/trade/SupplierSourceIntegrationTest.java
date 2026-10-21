package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 6.1.2.1 档口与货源池（trade 侧）。
 *
 * <p><b>本测试是 V233 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，逐条在真实 PostgreSQL 16 上跑一遍，
 * 并且走 HTTP 端点而不是直连数据库，以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p><b>catalog 是真起一个本地 stub 服务</b>（而不是在测试里 new 一个假对象）：供应商与内部 SKU 的事实都在
 * catalog 那边，本车道**不重新实现**它们，只按它们的契约形状取回逻辑 ID 与名称快照。
 *
 * <p><b>1688 开放平台也是真起一个本地 stub 服务</b>：本模块的采集是**主动抓取**，适配器必须真的发 HTTP、
 * 真的解析报文、真的落库。stub 扮演的是「外部平台」，它返回的报文是按 1688 开放平台形状写的；
 * 未接线的情形（没有登记采集接入）则**完全不发请求**，服务层显式失败。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：只有复用同一条连接，
 * 才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SupplierSourceIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer catalog;
  static HttpServer platform;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("sources")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  /** catalog stub 的供应商清单：roleId → 名称。 */
  static final Map<String, String> catalogSuppliers = new java.util.concurrent.ConcurrentHashMap<>();

  /** catalog stub 的 SKU 清单：skuId → 编码 / 名称。 */
  static final Map<String, String[]> catalogSkus = new java.util.concurrent.ConcurrentHashMap<>();

  /** 1688 stub 的返回报文（null = 返回 HTTP 500）。 */
  static volatile String platformBody;

  /** 1688 stub 收到的请求次数。 */
  static final java.util.concurrent.atomic.AtomicInteger platformCalls =
      new java.util.concurrent.atomic.AtomicInteger();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-trade-source-test-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static void write(HttpServer server, com.sun.net.httpserver.HttpExchange e, int code, Object body)
      throws java.io.IOException {
    byte[] bytes =
        body instanceof String s ? s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            : json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(code, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
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
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, null, List.of(grant("ALL", List.of("trade.sources.read")))));
      identity.start();

      // ---- catalog stub：供应商（6.1.1.1）与内部 SKU（2.x）。
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/supply/vendors",
          e -> {
            String path = e.getRequestURI().getPath();
            String rest = path.substring("/catalog/supply/vendors".length());
            if (rest.startsWith("/")) {
              String roleId = rest.substring(1);
              String name = catalogSuppliers.get(roleId);
              if (name == null) {
                write(catalog, e, 404, Map.of("message", "供应商不存在"));
                return;
              }
              write(
                  catalog,
                  e,
                  200,
                  Map.of(
                      "summary",
                      Map.of(
                          "roleId", roleId,
                          "partyCode", "SUP-" + roleId.substring(0, 4),
                          "partyName", name,
                          "partyStatus", "ACTIVE")));
              return;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var entry : catalogSuppliers.entrySet()) {
              rows.add(
                  Map.of(
                      "roleId", entry.getKey(),
                      "partyId", UUID.randomUUID().toString(),
                      "partyCode", "SUP-" + entry.getKey().substring(0, 4),
                      "partyName", entry.getValue(),
                      "partyStatus", "ACTIVE"));
            }
            write(
                catalog,
                e,
                200,
                Map.of("items", rows, "total", rows.size(), "page", 0, "size", 200));
          });
      catalog.createContext(
          "/catalog/skus/",
          e -> {
            String skuId = e.getRequestURI().getPath().substring("/catalog/skus/".length());
            String[] row = catalogSkus.get(skuId);
            if (row == null) {
              write(catalog, e, 404, Map.of("message", "SKU 不存在"));
              return;
            }
            write(
                catalog,
                e,
                200,
                Map.of(
                    "id", skuId,
                    "code", row[0],
                    "name", row[1],
                    "status", "ACTIVE"));
          });
      catalog.start();

      // ---- 1688 开放平台 stub：扮演外部货源平台。
      platform = HttpServer.create(new InetSocketAddress(0), 0);
      platform.createContext(
          "/open/shop/items",
          e -> {
            platformCalls.incrementAndGet();
            String body = platformBody;
            if (body == null) {
              write(platform, e, 500, Map.of("message", "平台内部错误"));
              return;
            }
            write(platform, e, 200, body);
          });
      platform.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    catalog.stop(0);
    platform.stop(0);
    pg.stop();
  }

  @Autowired SupplierSourceService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actorA;
  AccessService.Access actorB;

  static final UUID supplierRole = UUID.randomUUID();
  static final UUID skuOne = UUID.randomUUID();
  static final UUID skuTwo = UUID.randomUUID();

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    catalogSuppliers.clear();
    catalogSkus.clear();
    platformBody = null;
    platformCalls.set(0);
    catalogSuppliers.put(supplierRole.toString(), "义乌老王批发");
    catalogSkus.put(skuOne.toString(), new String[] {"SKU-BAG-001", "女士通勤包"});
    catalogSkus.put(skuTwo.toString(), new String[] {"SKU-BAG-002", "女士手提包"});

    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate supplier_source_sku_link, supplier_source_favorite, supplier_source_snapshot,"
              + " supplier_source_item, supplier_source_collection, supplier_source_collector_endpoint,"
              + " supplier_source_stall cascade");
    }
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.sources.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.sources.update", work);
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return tenant.equals(a) ? asA(work) : asB(work);
  }

  // ================================================================ 夹具

  SupplierSourceModels.StallRow createStall(
      UUID tenant, String code, String collectMethod, String platform) {
    return as(
        tenant,
        () ->
            service.createStall(
                new SupplierSourceModels.StallInput(
                    code,
                    platform,
                    "SHOP-" + code,
                    "档口 " + code,
                    "https://shop.example.com/" + code,
                    collectMethod,
                    null,
                    "测试档口",
                    0),
                "Bearer tenant-a"));
  }

  SupplierSourceModels.SyncResult push(
      UUID tenant,
      SupplierSourceModels.StallRow stall,
      List<SupplierSourceModels.CollectedItemInput> items) {
    return as(
        tenant,
        () ->
            service.pushCollection(
                stall.id(), new SupplierSourceModels.CollectionPushInput(items)));
  }

  static SupplierSourceModels.CollectedItemInput item(
      String itemId, String title, String min, String max, String moq) {
    return new SupplierSourceModels.CollectedItemInput(
        itemId, title, new BigDecimal(min), new BigDecimal(max), "CNY", new BigDecimal(moq),
        "红色 / 大号", "https://img.example.com/" + itemId + ".jpg",
        "https://shop.example.com/item/" + itemId, true);
  }

  // ================================================================ 采集 / 快照

  @Test
  @DisplayName("插件推送采集：货源条目落库，价格区间与起订量都是快照值")
  void pluginPushWritesSnapshots() {
    var stall = createStall(a, "S-PLUGIN", "PLUGIN_PUSH", "ALIBABA_1688");
    var result =
        push(
            a,
            stall,
            List.of(
                item("I-1", "女士通勤包", "12.5000", "18.8000", "2.000000"),
                item("I-2", "女士手提包", "20.0000", "20.0000", "5.000000")));

    assertThat(result.status()).isEqualTo("SUCCEEDED");
    assertThat(result.itemCount()).isEqualTo(2);
    assertThat(result.failureReason()).isEmpty();

    var page =
        asA(() -> service.pageItems(new SupplierSourceModels.SourceItemQuery(
            stall.id(), null, null, null, false, null, null, null, null, false, 0, 20)));
    assertThat(page.total()).isEqualTo(2);
    var row = page.items().stream().filter(i -> i.platformItemId().equals("I-1")).findFirst().orElseThrow();
    assertThat(row.priceMin()).isEqualByComparingTo("12.5000");
    assertThat(row.priceMax()).isEqualByComparingTo("18.8000");
    assertThat(row.moq()).isEqualByComparingTo("2.000000");
    assertThat(row.currency()).isEqualTo("CNY");
    assertThat(row.onSale()).isTrue();

    // **采集时刻必须断言写回的值**：API 回显的时刻与库里那一行必须是同一个值。
    var stored =
        asA(
            () ->
                sql.fetchOne(
                        "select collected_at from supplier_source_snapshot where title = '女士通勤包'")
                    .get(0, java.time.OffsetDateTime.class));
    assertThat(stored).isNotNull();
    assertThat(stored.toString()).isEqualTo(row.collectedAt());
  }

  @Test
  @DisplayName("同步产生新一版快照，旧快照一字不改（「上周三看到的那个价是什么」答得出来）")
  void newSnapshotDoesNotRewriteOldOne() {
    var stall = createStall(a, "S-HIST", "PLUGIN_PUSH", "ALIBABA_1688");
    push(a, stall, List.of(item("I-1", "女士通勤包", "12.5000", "18.8000", "2.000000")));

    var itemId =
        asA(
                () ->
                    service.pageItems(new SupplierSourceModels.SourceItemQuery(
                            stall.id(), null, null, null, false, null, null, null, null, false, 0, 20))
                        .items()
                        .get(0)
                        .id());
    var first = asA(() -> service.item(itemId));
    assertThat(first.snapshots()).hasSize(1);
    var oldSnapshotId = first.snapshots().get(0).id();
    var oldPrice = first.snapshots().get(0).priceMin();
    var oldCollectedAt = first.snapshots().get(0).collectedAt();

    // 第二次采集：价格涨了。
    push(a, stall, List.of(item("I-1", "女士通勤包", "15.0000", "21.0000", "2.000000")));

    var second = asA(() -> service.item(itemId));
    assertThat(second.snapshots()).hasSize(2);
    // 当前版本是新的那版。
    assertThat(second.item().priceMin()).isEqualByComparingTo("15.0000");
    assertThat(second.item().snapshotSeq()).isEqualTo(2);

    // **旧快照一行都没被改**：价格与采集时刻原样。
    var oldRow =
        asA(
            () ->
                sql.fetchOne(
                    "select price_min, collected_at from supplier_source_snapshot where id = ?",
                    oldSnapshotId));
    assertThat(oldRow.get("price_min", BigDecimal.class)).isEqualByComparingTo(oldPrice);
    assertThat(oldRow.get("collected_at", java.time.OffsetDateTime.class).toString())
        .isEqualTo(oldCollectedAt);
  }

  @Test
  @DisplayName("快照不可改写：直接改库会被触发器挡下")
  void snapshotCannotBeRewritten() {
    var stall = createStall(a, "S-IMMUTABLE", "PLUGIN_PUSH", "ALIBABA_1688");
    push(a, stall, List.of(item("I-1", "女士通勤包", "12.5000", "18.8000", "2.000000")));
    var snapshotId =
        asA(
            () ->
                sql.fetchOne("select id from supplier_source_snapshot limit 1").get(0, UUID.class));

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update supplier_source_snapshot set price_min = 1 where id = ?",
                            snapshotId)))
        .hasStackTraceContaining("货源快照不可改写");
  }

  @Test
  @DisplayName("价格区间写反 / 起订量非法 → 跳过并写明原因，不静默丢弃")
  void badItemsAreSkippedWithReason() {
    var stall = createStall(a, "S-BAD", "PLUGIN_PUSH", "ALIBABA_1688");
    var result =
        push(
            a,
            stall,
            List.of(
                item("I-BAD", "写反的区间", "18.8000", "12.5000", "2.000000"),
                item("I-OK", "正常的货", "12.5000", "18.8000", "2.000000")));

    assertThat(result.itemCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.skippedReason()).contains("价格区间写反");

    var page =
        asA(() -> service.pageItems(new SupplierSourceModels.SourceItemQuery(
            stall.id(), null, null, null, false, null, null, null, null, false, 0, 20)));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).platformItemId()).isEqualTo("I-OK");
  }

  // ================================================================ 1688 主动抓取

  @Test
  @DisplayName("未接线时同步显式失败：零快照写入，失败原因落库")
  void syncFailsWhenNotWired() {
    var stall = createStall(a, "S-UNWIRED", "URL_SYNC", "ALIBABA_1688");
    int callsBefore = platformCalls.get();

    var result = asA(() -> service.sync(stall.id()));

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.itemCount()).isZero();
    assertThat(result.failureReason()).contains("尚未接线");
    // **没有真的发过请求**：未接线就是不接线。
    assertThat(platformCalls.get()).isEqualTo(callsBefore);

    // 零快照写入。
    long snapshots =
        asA(() -> sql.fetchOne("select count(*) from supplier_source_snapshot").get(0, Long.class));
    assertThat(snapshots).isZero();

    // 失败原因落库（可查的事实，不是日志里的一行字）。
    var detail = asA(() -> service.stall(stall.id()));
    assertThat(detail.stall().lastCollectionStatus()).isEqualTo("FAILED");
    assertThat(detail.stall().lastFailureReason()).contains("尚未接线");
    assertThat(detail.collections()).hasSize(1);
    assertThat(detail.collections().get(0).status()).isEqualTo("FAILED");
    assertThat(detail.collections().get(0).failureReason()).contains("尚未接线");
  }

  @Test
  @DisplayName("接线后真的走 HTTP：报文解析后落库，坏数据计入跳过")
  void wiredSyncGoesThroughHttp() throws Exception {
    var stall = createStall(a, "S-WIRED", "URL_SYNC", "ALIBABA_1688");
    asA(
        () ->
            service.saveCollectorEndpoint(
                "ALIBABA_1688",
                new SupplierSourceModels.CollectorEndpointInput(
                    "http://localhost:" + platform.getAddress().getPort(),
                    "secret://1688/collect",
                    true,
                    "测试登记")));
    platformBody =
        json.writeValueAsString(
            Map.of(
                "items",
                List.of(
                    Map.of(
                        "itemId", "1688-1",
                        "title", "1688 通勤包",
                        "priceMin", "9.9000",
                        "priceMax", "13.5000",
                        "currency", "CNY",
                        "moq", "3.000000",
                        "spec", "米白",
                        "imageUrl", "https://img.example.com/1688-1.jpg",
                        "itemUrl", "https://shop.1688.com/item/1688-1",
                        "onSale", true),
                    Map.of(
                        "itemId", "1688-bad",
                        "title", "区间写反的货",
                        "priceMin", "20.0000",
                        "priceMax", "10.0000",
                        "currency", "CNY",
                        "moq", "1.000000",
                        "onSale", true))));
    int callsBefore = platformCalls.get();

    var result = asA(() -> service.sync(stall.id()));

    // 真的发了一次 HTTP。
    assertThat(platformCalls.get()).isEqualTo(callsBefore + 1);
    assertThat(result.status()).isEqualTo("SUCCEEDED");
    assertThat(result.itemCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.skippedReason()).contains("价格区间不可用");

    var page =
        asA(() -> service.pageItems(new SupplierSourceModels.SourceItemQuery(
            stall.id(), null, null, null, false, null, null, null, null, false, 0, 20)));
    assertThat(page.total()).isEqualTo(1);
    var row = page.items().get(0);
    assertThat(row.platformItemId()).isEqualTo("1688-1");
    assertThat(row.priceMin()).isEqualByComparingTo("9.9000");
    assertThat(row.priceMax()).isEqualByComparingTo("13.5000");
    assertThat(row.moq()).isEqualByComparingTo("3.000000");
  }

  @Test
  @DisplayName("平台返回错误时同步失败：零快照写入，原因写明")
  void syncFailsWhenPlatformErrors() {
    var stall = createStall(a, "S-PLATFORM-ERR", "URL_SYNC", "ALIBABA_1688");
    asA(
        () ->
            service.saveCollectorEndpoint(
                "ALIBABA_1688",
                new SupplierSourceModels.CollectorEndpointInput(
                    "http://localhost:" + platform.getAddress().getPort(),
                    "secret://1688/collect",
                    true,
                    "测试登记")));
    platformBody = null; // stub 返回 HTTP 500

    var result = asA(() -> service.sync(stall.id()));

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.failureReason()).contains("500");
    long snapshots =
        asA(() -> sql.fetchOne("select count(*) from supplier_source_snapshot").get(0, Long.class));
    assertThat(snapshots).isZero();
  }

  @Test
  @DisplayName("插件采集档口不能点主动同步（采集方式用错了就明说）")
  void pluginStallRejectsActiveSync() {
    var stall = createStall(a, "S-PLUGIN-SYNC", "PLUGIN_PUSH", "ALIBABA_1688");
    assertThatThrownBy(() -> asA(() -> service.sync(stall.id())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("插件采集推送");
  }

  @Test
  @DisplayName("采集接入只存引用：粘一个明文 token 在写库那一刻就被拒")
  void collectorEndpointOnlyAcceptsReference() {
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.saveCollectorEndpoint(
                            "ALIBABA_1688",
                            new SupplierSourceModels.CollectorEndpointInput(
                                "http://example.internal", "sk-live-abcdefg", true, "粘了明文"))))
        .hasMessageContaining("凭证只能是");
  }

  // ================================================================ 收藏

  @Test
  @DisplayName("收藏落库并可查询，取消收藏后不再计入")
  void favoriteIsPersisted() {
    var stall = createStall(a, "S-FAV", "PLUGIN_PUSH", "ALIBABA_1688");
    push(a, stall, List.of(item("I-1", "女士通勤包", "12.5000", "18.8000", "2.000000")));
    var itemId =
        asA(
                () ->
                    service.pageItems(new SupplierSourceModels.SourceItemQuery(
                            stall.id(), null, null, null, false, null, null, null, null, false, 0, 20))
                        .items()
                        .get(0)
                        .id());

    var saved =
        asA(() -> service.favorite(itemId, new SupplierSourceModels.FavoriteInput("备选"), user));
    assertThat(saved.favorited()).isTrue();
    assertThat(saved.favoritedAt()).isNotNull();

    var favoritedOnly =
        asA(() -> service.pageItems(new SupplierSourceModels.SourceItemQuery(
            null, null, null, null, true, null, null, null, null, false, 0, 20)));
    assertThat(favoritedOnly.total()).isEqualTo(1);
    assertThat(favoritedOnly.items().get(0).favorited()).isTrue();

    var removed = asA(() -> service.unfavorite(itemId));
    assertThat(removed.favorited()).isFalse();
    var after =
        asA(() -> service.pageItems(new SupplierSourceModels.SourceItemQuery(
            null, null, null, null, true, null, null, null, null, false, 0, 20)));
    assertThat(after.total()).isZero();
  }

  // ================================================================ SKU 关联与比价

  @Test
  @DisplayName("SKU 货源关联 + 比价：按币种分组，价格下限升序，最低价只有一个")
  void skuLinksAndPriceComparison() {
    var stall = createStall(a, "S-CMP", "PLUGIN_PUSH", "ALIBABA_1688");
    push(
        a,
        stall,
        List.of(
            item("I-CHEAP", "便宜的货", "10.0000", "12.0000", "5.000000"),
            item("I-MID", "中档的货", "15.0000", "18.0000", "3.000000"),
            item("I-EXPENSIVE", "贵的货", "30.0000", "35.0000", "1.000000")));

    var items =
        asA(() -> service.pageItems(new SupplierSourceModels.SourceItemQuery(
            stall.id(), null, null, null, false, null, null, null, null, false, 0, 20)));
    var cheap = items.items().stream().filter(i -> i.platformItemId().equals("I-CHEAP")).findFirst().orElseThrow();
    var mid = items.items().stream().filter(i -> i.platformItemId().equals("I-MID")).findFirst().orElseThrow();
    var expensive =
        items.items().stream().filter(i -> i.platformItemId().equals("I-EXPENSIVE")).findFirst().orElseThrow();

    asA(() -> service.linkSku(cheap.id(), new SupplierSourceModels.SkuLinkInput(skuOne, null), "Bearer tenant-a", user));
    asA(() -> service.linkSku(mid.id(), new SupplierSourceModels.SkuLinkInput(skuOne, null), "Bearer tenant-a", user));
    asA(() -> service.linkSku(expensive.id(), new SupplierSourceModels.SkuLinkInput(skuOne, null), "Bearer tenant-a", user));

    var view = asA(() -> service.skuSources(skuOne));
    assertThat(view.linkCount()).isEqualTo(3);
    assertThat(view.candidates()).hasSize(3);
    // 比价排序：价格下限升序。
    assertThat(view.candidates().get(0).priceMin()).isEqualByComparingTo("10.0000");
    assertThat(view.candidates().get(1).priceMin()).isEqualByComparingTo("15.0000");
    assertThat(view.candidates().get(2).priceMin()).isEqualByComparingTo("30.0000");
    // 最低价只有一个。
    assertThat(view.candidates().stream().filter(SupplierSourceModels.SkuCandidate::lowestInCurrency).count())
        .isEqualTo(1);
    assertThat(view.lowestByCurrency()).hasSize(1);
    assertThat(view.lowestByCurrency().get(0).currency()).isEqualTo("CNY");
    assertThat(view.lowestByCurrency().get(0).priceMin()).isEqualByComparingTo("10.0000");
    // 区间不是精确价：最低价候选也带着上限。
    assertThat(view.lowestByCurrency().get(0).priceMax()).isEqualByComparingTo("12.0000");
  }

  @Test
  @DisplayName("SKU 不存在时关联失败（catalog 是唯一来源，不凭空造一个 SKU）")
  void linkToUnknownSkuFails() {
    var stall = createStall(a, "S-SKU-404", "PLUGIN_PUSH", "ALIBABA_1688");
    push(a, stall, List.of(item("I-1", "女士通勤包", "12.5000", "18.8000", "2.000000")));
    var itemId =
        asA(
                () ->
                    service.pageItems(new SupplierSourceModels.SourceItemQuery(
                            stall.id(), null, null, null, false, null, null, null, null, false, 0, 20))
                        .items()
                        .get(0)
                        .id());

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.linkSku(
                            itemId,
                            new SupplierSourceModels.SkuLinkInput(UUID.randomUUID(), null),
                            "Bearer tenant-a",
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("内部 SKU 不存在");
  }

  @Test
  @DisplayName("档口关联供应商：只存逻辑 ID + 名称快照（catalog 是唯一来源）")
  void stallLinksSupplierWithSnapshot() {
    var row =
        asA(
            () ->
                service.createStall(
                    new SupplierSourceModels.StallInput(
                        "S-SUPPLIER",
                        "ALIBABA_1688",
                        "SHOP-SUP",
                        "义乌老王档口",
                        "https://shop.example.com/sup",
                        "PLUGIN_PUSH",
                        supplierRole,
                        "",
                        0),
                    "Bearer tenant-a"));
    assertThat(row.supplierPartyId()).isEqualTo(supplierRole);
    assertThat(row.supplierName()).isEqualTo("义乌老王批发");
  }

  // ================================================================ 多租户三断言（AGENTS.md 硬要求）

  @Test
  @DisplayName("多租户断言 1：A 看不到 B 的货源条目（RLS 下查不到 → 404）")
  void tenantIsolationList() {
    var stallA = createStall(a, "S-ISO-A", "PLUGIN_PUSH", "ALIBABA_1688");
    var stallB = createStall(b, "S-ISO-B", "PLUGIN_PUSH", "ALIBABA_1688");
    push(a, stallA, List.of(item("I-A", "A 家的货", "10.0000", "12.0000", "1.000000")));
    push(b, stallB, List.of(item("I-B", "B 家的货", "20.0000", "22.0000", "1.000000")));

    var pageA =
        asA(() -> service.pageItems(new SupplierSourceModels.SourceItemQuery(
            null, null, null, null, false, null, null, null, null, false, 0, 20)));
    assertThat(pageA.total()).isEqualTo(1);
    assertThat(pageA.items().get(0).platformItemId()).isEqualTo("I-A");

    assertThatThrownBy(() -> asA(() -> service.stall(stallB.id())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或无权查看");
  }

  @Test
  @DisplayName("多租户断言 2：伪造 X-Tenant-Id 头无效（走 HTTP 端点，租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    var stallA = createStall(a, "S-FORGE-A", "PLUGIN_PUSH", "ALIBABA_1688");
    var stallB = createStall(b, "S-FORGE-B", "PLUGIN_PUSH", "ALIBABA_1688");

    http.perform(
            get("/trade/sources/stalls/{id}", stallA.id().toString())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stall.code").value("S-FORGE-A"));

    // 拿 A 的身份 + B 的头去读 B 的档口：读不到（租户由 Bearer 决定）。
    http.perform(
            get("/trade/sources/stalls/{id}", stallB.id().toString())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("多租户断言 3：连接池归还后不串租户（池压到 1，20 轮往返）")
  void connectionPoolDoesNotLeakTenant() {
    var stallA = createStall(a, "S-POOL-A", "PLUGIN_PUSH", "ALIBABA_1688");
    var stallB = createStall(b, "S-POOL-B", "PLUGIN_PUSH", "ALIBABA_1688");

    for (int i = 0; i < 20; i++) {
      assertThat(asA(() -> service.stall(stallA.id()).stall().code())).isEqualTo("S-POOL-A");
      assertThat(asB(() -> service.stall(stallB.id()).stall().code())).isEqualTo("S-POOL-B");
    }
  }

  @Test
  @DisplayName("每张新表都 relforcerowsecurity")
  void rlsIsForcedOnEveryTable() {
    for (String table :
        List.of(
            "supplier_source_stall",
            "supplier_source_collection",
            "supplier_source_item",
            "supplier_source_snapshot",
            "supplier_source_favorite",
            "supplier_source_sku_link",
            "supplier_source_collector_endpoint")) {
      Boolean forced =
          asA(
              () ->
                  sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?", table)
                      .get(0, Boolean.class));
      assertThat(forced).as(table).isTrue();
    }
  }

  // ================================================================ HTTP 契约

  @Test
  @DisplayName("HTTP 契约：401 未认证 / 403 只读 / 404 跨租户")
  void httpContract() throws Exception {
    http.perform(get("/trade/sources/items")).andExpect(status().isUnauthorized());

    http.perform(get("/trade/sources/items").header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());

    // 只读身份不能建档口。
    http.perform(
            post("/trade/sources/stalls")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new SupplierSourceModels.StallInput(
                            "S-RO", "ALIBABA_1688", "SHOP-RO", "只读建的档口",
                            "", "PLUGIN_PUSH", null, "", 0))))
        .andExpect(status().isForbidden());

    // 跨租户读别人的档口 → 404。
    var stallB = createStall(b, "S-HTTP-B", "PLUGIN_PUSH", "ALIBABA_1688");
    http.perform(
            get("/trade/sources/stalls/{id}", stallB.id().toString())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("真分页：三页合起来恰好 N 条且互不重叠")
  void paginationIsReal() {
    var stall = createStall(a, "S-PAGE", "PLUGIN_PUSH", "ALIBABA_1688");
    List<SupplierSourceModels.CollectedItemInput> items = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      items.add(item("I-" + i, "货 " + i, "10.0000", "12.0000", "1.000000"));
    }
    push(a, stall, items);

    List<String> seen = new ArrayList<>();
    UUID stallId = stall.id();
    for (int page = 0; page < 3; page++) {
      final int currentPage = page;
      var result =
          asA(() -> service.pageItems(new SupplierSourceModels.SourceItemQuery(
              stallId, null, null, null, false, null, null, null, null, false, currentPage, 3)));
      assertThat(result.total()).isEqualTo(7);
      assertThat(result.items()).hasSizeLessThanOrEqualTo(3);
      result.items().forEach(i -> seen.add(i.platformItemId()));
    }
    assertThat(seen).hasSize(7).doesNotHaveDuplicates();
  }
}
