package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.jooq.Record;
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
 * 6.4.1.2 BOM 关联（BOM 冻结快照）。
 *
 * <p><b>本测试是 V98 迁移那些 DB 级不变量的独立复验</b>，并且专门验证本模块的
 * <b>本质</b>：冻结的是<b>快照</b>而不是<b>引用</b>。
 *
 * <p><b>catalog 是一个真起服务的 stub</b>，它<b>按 3.5.2.2 的规则真算</b>一棵
 * 两层展开树（成品 OUT-1 → 半成品 SA → 面料 + 拉链），而不是返回写死的数组：
 *
 * <ul>
 *   <li><b>E1 逐层结转</b>：顶层 required = 产量 × 单位用量 × (1 + 损耗率/100)；
 *       深层 required = <b>父行缺口</b> × 单位用量 × (1 + 损耗率/100) ——
 *       同一个 R7 公式，但驱动量换成了父行的 gap。这条正是单层快照答不出的部分。
 *   <li><b>E2 缺口</b>：gap = max(0, required − available − inTransit)，下限 0。
 *   <li><b>R6</b>：scrap_rate 是<b>百分数</b>（5 = 5%），stub 按百分数算，
 *       断言也按百分数算 —— 任何一处当成比率，领料量会静默翻 6 倍。
 *   <li><b>R7</b>：6 位小数 HALF_UP，<b>不取整</b>。
 * </ul>
 *
 * <p><b>最关键的一条测试是 {@code snapshotSurvivesBomChange}</b>：冻结之后
 * 让 catalog 改掉整棵 BOM 的组件与损耗率，再读快照 —— 每一个数必须原封不动。
 * 那才是蓝图「冻结与订单冻结组件同理」的验收。
 *
 * <p>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。连接池固定 1 条：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BomBindingIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer catalog;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** catalog stub 的 BOM 登记表：版本 ID → 该版的组件树与状态。测试改它就等于「BOM 改配」。 */
  static final Map<String, Object> catalogBoms = new ConcurrentHashMap<>();

  /** catalog 规则 R4 时刻查询的登记：bomId → 此刻生效的版本号（null = 无生效版本）。 */
  static final Map<String, String> catalogEffective = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      Stream.of("processing")
          .flatMap(p -> Stream.of("read", "update").map(op -> "trade." + p + "." + op))
          .toList();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-trade-bom-binding-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(), grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static void write(HttpServer server, com.sun.net.httpserver.HttpExchange e, int code, Object body)
      throws java.io.IOException {
    // HttpExchange 的写操作抛 IOException；调用点都在 lambda 里（无法再声明受检异常），
    // 所以统一包成 RuntimeException —— 写失败就是测试夹具坏了，必须炸出来而不是吞掉。
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(code, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  /** 规则 R7：单位用量 × 产量 × (1 + 损耗率/100)，6 位小数 HALF_UP，**不取整**。 */
  static BigDecimal r7(BigDecimal driver, BigDecimal unit, BigDecimal scrapPercent) {
    return unit.multiply(driver)
        .multiply(BigDecimal.ONE.add(scrapPercent.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP)))
        .setScale(6, RoundingMode.HALF_UP);
  }

  /** 规则 E2：缺口 = max(0, required − available − inTransit)。 */
  static BigDecimal gapOf(BigDecimal required, BigDecimal available, BigDecimal inTransit) {
    BigDecimal gap = required.subtract(available).subtract(inTransit);
    return gap.signum() < 0 ? BigDecimal.ZERO : gap.setScale(6, RoundingMode.HALF_UP);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
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
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, List.of(grant("ALL", List.of("trade.processing.read")))));
      identity.start();

      // ---- catalog stub：3.5.2.2 展开 + 3.5.2.1 规则 R4 的时刻查询。
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      // ---- catalog stub 也答 6.4.1.1 的两个单层接口：本测试要**先建一张加工单**
      //      才能冻结它的 BOM，而建单走的是 3.5.2.1 的版本档案 + 单层 R7 需求量。
      //      形状逐字对齐 catalog 契约，R7 三元组按公式真算。
      catalog.createContext(
          "/catalog/bom-versions/",
          e -> {
            String path = e.getRequestURI().getPath();
            String rest = path.substring("/catalog/bom-versions/".length());
            boolean requirement = rest.endsWith("/requirement");
            String versionId =
                requirement ? rest.substring(0, rest.length() - "/requirement".length()) : rest;
            Object registered = catalogBoms.get(versionId);
            if (registered == null) {
              send(catalog, e, 404, Map.of("message", "BOM 版本不存在"));
              return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) registered;
            if (!requirement) {
              send(
                  catalog,
                  e,
                  200,
                  map(
                      "id", versionId, "bomId", meta.get("bomId"), "bomCode", meta.get("bomCode"),
                      "versionNo", meta.get("versionNo"), "outputSkuId", meta.get("outputSkuId"),
                      "outputSkuCode", meta.get("outputSkuCode"), "status", meta.get("status")));
              return;
            }
            String query = e.getRequestURI().getQuery();
            BigDecimal outputQuantity = new BigDecimal(query.replace("outputQuantity=", ""));
            // 单层：只展开成品版本的直接组件（SA），R7 三元组真算。
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> components = (List<Map<String, Object>>) meta.get("components");
            List<Map<String, Object>> lines = new ArrayList<>();
            for (Map<String, Object> component : components) {
              BigDecimal required =
                  r7(outputQuantity, decimal(component.get("unitQuantity")),
                      decimal(component.get("scrapRate")));
              lines.add(
                  map(
                      "skuId", component.get("skuId"), "skuCode", component.get("skuCode"),
                      "unitQuantity", component.get("unitQuantity"), "scrapRate", component.get("scrapRate"),
                      "requiredQuantity", required));
            }
            send(
                catalog, e, 200,
                map(
                    "bomId", meta.get("bomId"), "versionId", versionId,
                    "versionNo", meta.get("versionNo"), "outputSkuId", meta.get("outputSkuId"),
                    "outputSkuCode", meta.get("outputSkuCode"), "outputQuantity", outputQuantity,
                    "lines", lines, "singleLevel", true));
          });

      catalog.createContext(
          "/catalog/bom-explosions",
          e -> {
            Map<String, Object> input = json.readValue(e.getRequestBody().readAllBytes(), Map.class);
            String versionId = String.valueOf(input.get("versionId"));
            Object registered = catalogBoms.get(versionId);
            if (registered == null) {
              send(catalog, e, 404, Map.of("message", "BOM 版本不存在"));
              return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) registered;
            BigDecimal outputQuantity = new BigDecimal(String.valueOf(input.get("outputQuantity")));
            int maxDepth =
                input.get("maxDepth") == null ? 10 : Integer.parseInt(String.valueOf(input.get("maxDepth")));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) meta.get("components");
            String asOf =
                input.get("asOf") == null
                    ? OffsetDateTime.now(ZoneOffset.UTC).toString()
                    : String.valueOf(input.get("asOf"));

            List<Map<String, Object>> lines = new ArrayList<>();
            int reached = 1;
            int purchaseLines = 0;
            // 递归展开：子装配按「父行缺口」结转（规则 E1），命中上限就停并标 truncated（E4）。
            var expansion = new Expansion();
            expand(children, null, 1, outputQuantity, maxDepth, lines, expansion);
            for (Map<String, Object> line : lines) {
              reached = Math.max(reached, (int) line.get("depth"));
              if (!((boolean) line.get("expanded")) && decimal(line.get("gap")).signum() > 0) {
                purchaseLines++;
              }
            }
            boolean truncated = expansion.truncated;
            BigDecimal totalRequired =
                lines.stream()
                    .map(l -> decimal(l.get("requiredQuantity")))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(6, RoundingMode.HALF_UP);
            BigDecimal totalGap =
                lines.stream()
                    .map(l -> decimal(l.get("gap")))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(6, RoundingMode.HALF_UP);

            var response = new LinkedHashMap<String, Object>();
            // catalog 每调一次展开就**新建**一条 bom_explosion_run（V26 的 run 表是
            // 「一次展开一行」），所以 run id 每次调用都不同 —— stub 必须如实模拟，
            // 否则会用一个恒定的 run id 掩盖掉服务层真正的行为。
            response.put("id", java.util.UUID.randomUUID());
            response.put("bomId", meta.get("bomId"));
            response.put("bomCode", meta.get("bomCode"));
            response.put("versionId", versionId);
            response.put("versionNo", meta.get("versionNo"));
            response.put("versionStatus", meta.get("status"));
            response.put("outputSkuId", meta.get("outputSkuId"));
            response.put("outputSkuCode", meta.get("outputSkuCode"));
            response.put("outputQuantity", outputQuantity);
            response.put("warehouseId", input.get("warehouseId"));
            response.put("asOf", asOf);
            response.put("maxDepth", maxDepth);
            response.put("reachedDepth", reached);
            response.put("status", truncated ? "TRUNCATED" : "COMPLETED");
            response.put("truncated", truncated);
            response.put("lineCount", lines.size());
            response.put("purchaseLineCount", purchaseLines);
            response.put("totalRequired", totalRequired);
            response.put("totalGap", totalGap);
            response.put("lines", lines);
            send(catalog, e, 200, response);
          });
      catalog.createContext(
          "/catalog/boms/",
          e -> {
            String path = e.getRequestURI().getPath();
            if (!path.endsWith("/effective")) {
              send(catalog, e, 404, Map.of("message", "接口不存在"));
              return;
            }
            String bomId = path.substring("/catalog/boms/".length(), path.length() - "/effective".length());
            String effectiveNo = catalogEffective.get(bomId);
            var response = new LinkedHashMap<String, Object>();
            response.put("bomId", bomId);
            response.put("effective", effectiveNo != null);
            response.put("versionNo", effectiveNo);
            response.put("at", e.getRequestURI().getQuery());
            response.put("components", List.of());
            send(catalog, e, 200, response);
          });
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** lambda 里用：把 write 的受检异常包成 unchecked（夹具坏了必须炸出来）。 */
  static void send(HttpServer server, com.sun.net.httpserver.HttpExchange e, int code, Object body) {
    try {
      write(server, e, code, body);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("catalog stub 写响应失败", failure);
    }
  }

  static BigDecimal decimal(Object value) {
    return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
  }

  /**
   * 按 3.5.2.2 的规则真算一层。
   *
   * <p>{@code driver} 是驱动量：顶层是成品产量，深层是<b>父行的缺口</b>（E1）。
   */
  @SuppressWarnings("unchecked")
  static void expand(
      List<Map<String, Object>> components,
      String parentLineId,
      int depth,
      BigDecimal driver,
      int maxDepth,
      List<Map<String, Object>> out,
      Expansion expansion) {
    int seq = 0;
    for (Map<String, Object> component : components) {
      seq++;
      String path = parentLineId == null ? String.valueOf(seq) : parentLineId + "." + seq;
      BigDecimal unit = decimal(component.get("unitQuantity"));
      BigDecimal scrap = decimal(component.get("scrapRate"));
      BigDecimal required = r7(driver, unit, scrap);
      BigDecimal available = component.get("available") == null ? BigDecimal.ZERO : decimal(component.get("available"));
      BigDecimal inTransit = component.get("inTransit") == null ? BigDecimal.ZERO : decimal(component.get("inTransit"));
      BigDecimal gap = gapOf(required, available, inTransit);
      BigDecimal surplus =
          available.add(inTransit).subtract(required).signum() < 0
              ? BigDecimal.ZERO
              : available.add(inTransit).subtract(required).setScale(6, RoundingMode.HALF_UP);

      boolean isSub = component.get("children") != null;
      boolean covered = gap.signum() == 0;
      // 规则 E4：命中深度上限时**截断但必须显式标明** —— 静默截断是未完成。
      boolean depthCapped = isSub && !covered && depth >= maxDepth;
      if (depthCapped) {
        expansion.truncated = true;
      }
      boolean expanded = isSub && !covered && depth < maxDepth;

      var line = new LinkedHashMap<String, Object>();
      line.put("id", UUID.nameUUIDFromBytes(("line-" + path).getBytes()));
      line.put("parentLineId", parentLineId == null ? null : UUID.nameUUIDFromBytes(("line-" + parentLineId).getBytes()));
      line.put("depth", depth);
      line.put("path", path);
      line.put("skuId", component.get("skuId"));
      line.put("skuCode", component.get("skuCode"));
      line.put("skuName", component.get("skuName"));
      line.put("componentId", component.get("componentId"));
      line.put("sourceVersionId", component.get("sourceVersionId"));
      line.put("sourceVersionNo", component.get("sourceVersionNo"));
      line.put("sourceBomCode", component.get("sourceBomCode"));
      line.put("unitQuantity", unit);
      line.put("scrapRate", scrap);
      line.put("requiredQuantity", required);
      line.put("available", available);
      line.put("inTransit", inTransit);
      line.put("gap", gap);
      line.put("surplus", surplus);
      line.put("isSubAssembly", isSub);
      line.put("subBomId", isSub ? component.get("subBomId") : null);
      line.put("subBomCode", isSub ? component.get("subBomCode") : null);
      line.put("subVersionId", isSub ? component.get("sourceVersionId") : null);
      line.put("subVersionNo", isSub ? component.get("sourceVersionNo") : null);
      line.put("ambiguousSubBom", false);
      line.put("expanded", expanded);
      line.put("covered", covered);
      line.put("truncated", false);
      line.put("role", component.getOrDefault("role", ""));
      line.put("note", "");
      out.add(line);

      if (expanded) {
        expand(
            (List<Map<String, Object>>) component.get("children"),
            path,
            depth + 1,
            gap,
            maxDepth,
            out,
            expansion);
      }
    }
  }

  /** 递归展开的副产物：本次展开是否命中深度上限被截断（规则 E4）。 */
  static final class Expansion {
    boolean truncated;
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
    // 单连接：只有复用同一条连接才能证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    catalog.stop(0);
    pg.stop();
  }

  @Autowired BomBindingService service;
  @Autowired ProcessingOrderService orders;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // 成品 OUT-1、半成品 SA、面料 FABRIC、拉链 ZIPPER（catalog 侧逻辑 ID，本服务不建外键）
  static final UUID bomId = UUID.randomUUID(), subBomId = UUID.randomUUID(),
      versionPublished = UUID.randomUUID(), subVersion = UUID.randomUUID(),
      versionDraft = UUID.randomUUID(), skuOut = UUID.randomUUID(), skuSa = UUID.randomUUID(),
      skuFabric = UUID.randomUUID(), skuZipper = UUID.randomUUID(),
      componentSa = UUID.randomUUID(), componentFabric = UUID.randomUUID(),
      componentZipper = UUID.randomUUID();

  @BeforeEach
  void seed() throws Exception {
    catalogBoms.clear();
    catalogEffective.clear();
    catalogEffective.put(bomId.toString(), "v1");
    registerBom(versionPublished, "PUBLISHED");
    catalogBoms.put(
        versionDraft.toString(),
        bomMeta(versionDraft, "v9", "DRAFT", withScrap("5", "0", "0")));
    catalogBoms.put(
        versionDraft.toString() + "-as-published",
        bomMeta(versionDraft, "v9", "PUBLISHED", withScrap("5", "0", "0")));

    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 级联清干净：V98 的两张表挂 CASCADE，truncate 加工单会连带清掉快照。
      s.execute(
          "truncate processing_bom_snapshot_line, processing_bom_snapshot,"
              + " processing_order_event, processing_order_component, processing_inbound_request,"
              + " processing_order, processing_factory restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      as(
          tenant,
          () -> {
            sql.execute(
                "insert into processing_factory(id,tenant_id,code,name,factory_kind,status,note)"
                    + " values(?,?,?,?,?,'ACTIVE',?)",
                idFor(tenant, 1), tenant, "F-01", "上海某加工厂", "OUTSOURCED", "");
            return null;
          });
    }
  }

  static Map<String, Object> bomMeta(UUID versionId, String versionNo, String status, List<Map<String, Object>> components) {
    return Map.of(
        "bomId", bomId,
        "bomCode", "BOM-001",
        "runId", UUID.nameUUIDFromBytes(("run-" + versionId).getBytes()),
        "versionNo", versionNo,
        "status", status,
        "outputSkuId", skuOut,
        "outputSkuCode", "OUT-1",
        "components", components);
  }

  /**
   * 两层展开树：OUT-1 → 半成品 SA（3 个/件，损耗 5%）→ 面料 + 拉链。
   *
   * <p>SA 有 4 在手、3 在途（E1：深层按<b>父行缺口</b>结转），
   * 于是 SA 的 required=30、gap=23，而它下面的料按 23 结转而不是 30 ——
   * 这正是单层领料清单答不出的那一层。
   */
  static List<Map<String, Object>> tree(String saScrap, String fabricScrap, String zipperScrap) {
    return withScrap(saScrap, fabricScrap, zipperScrap);
  }

  static List<Map<String, Object>> withScrap(String saScrap, String fabricScrap, String zipperScrap) {
    return List.of(
        map(
            "skuId", skuSa, "skuCode", "SA", "skuName", "半成品外套",
            "componentId", componentSa, "sourceVersionId", versionPublished,
            "sourceVersionNo", "v1", "sourceBomCode", "BOM-001",
            "unitQuantity", "3", "scrapRate", saScrap,
            "available", "4", "inTransit", "3", "role", "半成品",
            "subBomId", subBomId, "subBomCode", "BOM-SUB-SA",
            "children",
                List.of(
                    map(
                        "skuId", skuFabric, "skuCode", "FABRIC", "skuName", "面料",
                        "componentId", componentFabric, "sourceVersionId", subVersion,
                        "sourceVersionNo", "v1", "sourceBomCode", "BOM-SUB-SA",
                        "unitQuantity", "0.5", "scrapRate", fabricScrap, "role", "主料"),
                    map(
                        "skuId", skuZipper, "skuCode", "ZIPPER", "skuName", "拉链",
                        "componentId", componentZipper, "sourceVersionId", subVersion,
                        "sourceVersionNo", "v1", "sourceBomCode", "BOM-SUB-SA",
                        "unitQuantity", "2", "scrapRate", zipperScrap, "role", "辅料"))));
  }

  /** 可变长 map：{@code Map.of} 最多 10 对，展开树的一行超过这个数。 */
  static Map<String, Object> map(Object... pairs) {
    var out = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) {
      out.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return out;
  }

  void registerBom(UUID versionId, String status) {
    catalogBoms.put(versionId.toString(), bomMeta(versionId, "v1", status, tree("5", "0", "0")));
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.processing.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-b"), "trade.processing.update", work);
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return tenant.equals(a) ? asA(work) : asB(work);
  }

  ProcessingModels.ProcessingOrderSaved createOrder(UUID tenant, String orderNo) {
    return as(
        tenant,
        () ->
            orders.createOrder(
                new ProcessingModels.ProcessingOrderInput(
                    orderNo, idFor(tenant, 1), versionPublished, new BigDecimal("10"),
                    java.time.LocalDate.now().plusDays(30), "春季外套加工"),
                user, "Bearer tenant-a"));
  }

  BomBindingModels.BomFreezeResult freeze(UUID orderId) {
    return asA(
        () ->
            service.freeze(
                orderId,
                new BomBindingModels.BomFreezeInput(
                    versionPublished, new BigDecimal("10"), null, null, null, "春季首批"),
                user,
                "Bearer tenant-a"));
  }

  // ================================================================ 冻结语义（本模块的核心）

  @Test
  @DisplayName("冻结落库的是两层展开树：OUT-1 → SA → 面料/拉链，且深层按父行缺口结转")
  void freezeStoresWholeExplosionTree() {
    var order = createOrder(a, "PO-FREEZE-001");
    var frozen = freeze(order.id());

    assertThat(frozen.alreadyFrozen()).isFalse();
    var snapshot = frozen.snapshot();
    assertThat(snapshot.lines()).hasSize(3);
    assertThat(snapshot.lineCount()).isEqualTo(3);
    assertThat(snapshot.snapshotStatus()).isEqualTo("COMPLETED");
    assertThat(snapshot.truncated()).isFalse();
    assertThat(snapshot.outputQuantity()).isEqualByComparingTo("10.000000");
    assertThat(snapshot.asOf()).isNotNull();

    var sa = snapshot.lines().stream().filter(l -> l.skuCode().equals("SA")).findFirst().orElseThrow();
    // E1 顶层：产量 10 × 单位用量 3 × (1 + 5%) = 31.5
    assertThat(sa.requiredQuantity()).isEqualByComparingTo("31.500000");
    assertThat(sa.depth()).isEqualTo(1);
    assertThat(sa.path()).isEqualTo("1");
    assertThat(sa.isSubAssembly()).isTrue();
    assertThat(sa.expanded()).isTrue();
    // E2：31.5 − 可用 4 − 在途 3 = 24.5
    assertThat(sa.gap()).isEqualByComparingTo("24.500000");
    assertThat(sa.subVersionNo()).isEqualTo("v1");

    var fabric = snapshot.lines().stream().filter(l -> l.skuCode().equals("FABRIC")).findFirst().orElseThrow();
    // **E1 逐层结转**：深层按父行缺口 24.5 驱动，不是按父行需求 31.5：
    // 24.5 × 0.5 × (1 + 0%) = 12.25
    assertThat(fabric.requiredQuantity()).isEqualByComparingTo("12.250000");
    assertThat(fabric.depth()).isEqualTo(2);
    assertThat(fabric.path()).isEqualTo("1.1");
    // 深层记的是**子 BOM 的那一版**，单层快照答不出这一点。
    assertThat(fabric.sourceVersionNo()).isEqualTo("v1");
    assertThat(fabric.sourceBomCode()).isEqualTo("BOM-SUB-SA");

    var zipper = snapshot.lines().stream().filter(l -> l.skuCode().equals("ZIPPER")).findFirst().orElseThrow();
    // 24.5 × 2 × (1 + 0%) = 49
    assertThat(zipper.requiredQuantity()).isEqualByComparingTo("49.000000");
    assertThat(zipper.path()).isEqualTo("1.2");

    // 树形落在数据里：第 2 层的父行确实是 SA 那一行。
    assertThat(fabric.parentLineId()).isEqualTo(sa.id());
    assertThat(sa.parentLineId()).isNull();
  }

  @Test
  @DisplayName("冻结同时拍下单位用量与损耗率（R6 百分数，5 就是 5% 不是 500%）")
  void freezeCopiesUnitQuantityAndScrapRate() {
    var order = createOrder(a, "PO-R6-001");
    var snapshot = freeze(order.id()).snapshot();

    var sa = snapshot.lines().stream().filter(l -> l.skuCode().equals("SA")).findFirst().orElseThrow();
    assertThat(sa.unitQuantity()).isEqualByComparingTo("3.000000");
    // 原样搬运：catalog 给 5 就存 5，绝不除以 100 变成 0.05（那是 R6 禁止的比率化）。
    assertThat(sa.scrapRate()).isEqualByComparingTo("5.0000");
    assertThat(sa.scrapRate().compareTo(new BigDecimal("1"))).isGreaterThan(0);

    var fabric = snapshot.lines().stream().filter(l -> l.skuCode().equals("FABRIC")).findFirst().orElseThrow();
    // 0.5 米面料：NUMERIC(19,6) 保得住小数，整数列会逼运营写假数。
    assertThat(fabric.unitQuantity()).isEqualByComparingTo("0.500000");
    assertThat(fabric.scrapRate()).isEqualByComparingTo("0.0000");
  }

  @Test
  @DisplayName("蓝图验收项：BOM 中途改配，已冻结快照一个数都不变")
  void snapshotSurvivesBomChange() {
    var order = createOrder(a, "PO-FROZEN-001");
    var before = freeze(order.id()).snapshot();

    // —— 改配：catalog 侧的组件用量、损耗率、可用量、在途全变，BOM 也接替新版本 ——
    catalogBoms.put(
        versionPublished.toString(),
        bomMeta(versionPublished, "v2", "PUBLISHED", tree("20", "30", "40")));
    catalogEffective.put(bomId.toString(), "v2");

    var after = asA(() -> service.binding(order.id(), "Bearer tenant-a"));
    var snapshot = after.snapshot();

    assertThat(snapshot.lines()).hasSameSizeAs(before.lines());
    for (var oldLine : before.lines()) {
      var newLine =
          snapshot.lines().stream()
              .filter(l -> l.path().equals(oldLine.path()))
              .findFirst()
              .orElseThrow(() -> new AssertionError("路径 " + oldLine.path() + " 的行消失了"));
      assertThat(newLine.unitQuantity()).as("路径 %s 的单位用量", oldLine.path())
          .isEqualByComparingTo(oldLine.unitQuantity());
      assertThat(newLine.scrapRate()).as("路径 %s 的损耗率", oldLine.path())
          .isEqualByComparingTo(oldLine.scrapRate());
      assertThat(newLine.requiredQuantity()).as("路径 %s 的需求量", oldLine.path())
          .isEqualByComparingTo(oldLine.requiredQuantity());
      assertThat(newLine.available()).isEqualByComparingTo(oldLine.available());
      assertThat(newLine.inTransit()).isEqualByComparingTo(oldLine.inTransit());
      assertThat(newLine.gap()).isEqualByComparingTo(oldLine.gap());
    }
    // 版本号快照也原样留存：BOM 接替 v2 不改写这张单冻结的 v1。
    assertThat(snapshot.bomVersionNo()).isEqualTo("v1");

    // 但**漂移必须被显式告警** —— 冻结不等于不管后续变化，运营要知道新单该用哪一版。
    assertThat(after.drifted()).isTrue();
    assertThat(after.effectiveVersionNo()).isEqualTo("v2");
    assertThat(after.notes()).anySatisfy(n -> assertThat(n).contains("接替新版本").contains("不受影响"));
  }

  @Test
  @DisplayName("冻结幂等：重复冻结返回同一份快照，不覆盖也不报错")
  void freezeIsIdempotentAndNeverOverwrites() {
    var order = createOrder(a, "PO-IDEMPOTENT-001");
    var first = freeze(order.id());
    assertThat(first.alreadyFrozen()).isFalse();

    // catalog 侧改配之后再冻一次：必须返回原来那份，不能换成新的树。
    catalogBoms.put(
        versionPublished.toString(),
        bomMeta(versionPublished, "v1", "PUBLISHED", tree("50", "0", "0")));

    var second = freeze(order.id());
    assertThat(second.alreadyFrozen()).isTrue();
    assertThat(second.snapshot().id()).isEqualTo(first.snapshot().id());
    var sa = second.snapshot().lines().stream().filter(l -> l.skuCode().equals("SA")).findFirst().orElseThrow();
    assertThat(sa.scrapRate()).isEqualByComparingTo("5.0000");

    long rows =
        asA(() -> sql.fetchOne("select count(*) from processing_bom_snapshot where order_id = ?", order.id())
            .get(0, Long.class));
    assertThat(rows).isEqualTo(1L);
  }

  @Test
  @DisplayName("一张单只能有一份快照：直接插第二份被 UNIQUE 挡下")
  void oneSnapshotPerOrder() {
    var order = createOrder(a, "PO-UNIQUE-001");
    var frozen = freeze(order.id()).snapshot();

    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into processing_bom_snapshot(id,tenant_id,order_id,explosion_run_id,"
                              + "bom_id,bom_version_id,bom_version_no,output_sku_id,output_sku_code,"
                              + "output_quantity,as_of,max_depth,reached_depth,snapshot_status,"
                              + "line_count,purchase_line_count,frozen_by,frozen_at)"
                              + " values(?,?,?,?,?,?,?,?,?,10,now(),10,1,'COMPLETED',1,1,?,now())",
                          UUID.randomUUID(), a, order.id(), UUID.randomUUID(), bomId,
                          versionPublished, "v1", skuOut, "OUT-1", user);
                      return null;
                    }))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    assertThat(frozen.orderId()).isEqualTo(order.id());
  }

  @Test
  @DisplayName("快照不可改写：绕过服务层直连 SQL 也改不动快照与明细")
  void snapshotIsImmutableEvenViaDirectSql() {
    var order = createOrder(a, "PO-IMMUTABLE-001");
    var snapshot = freeze(order.id()).snapshot();

    // 应用账号（trade_app）：REVOKE 已经先一步把 UPDATE/DELETE 收走了。
    // 这是纵深防线的第一层 —— 应用连写的资格都没有。
    for (String stmt :
        List.of(
            "update processing_bom_snapshot set bom_version_no = 'v99' where id = '"
                + snapshot.id()
                + "'",
            "update processing_bom_snapshot_line set scrap_rate = 99 where id = '"
                + snapshot.lines().get(0).id()
                + "'",
            "delete from processing_bom_snapshot_line where id = '"
                + snapshot.lines().get(0).id()
                + "'",
            "delete from processing_bom_snapshot where id = '" + snapshot.id() + "'")) {
      assertThatThrownBy(
              () ->
                  asA(
                      () -> {
                        sql.execute(stmt);
                        return null;
                      }))
          .as(stmt)
          // jOOQ 把 SQLSTATE 42501 包成 DataAccessException，可读消息在**根因**上；
          // 同时断言它确实是权限错误而不是别的失败。
          .getRootCause()
          .hasMessageContaining("permission denied");
    }

    // 迁移账号（表 owner，绕得过 REVOKE）：触发器仍然把每一条 UPDATE/DELETE 拒下来。
    // 只钉 REVOKE 不够 —— 换个连接身份就能改，而触发器在**数据库层**没有旁路。
    for (String stmt :
        List.of(
            "update processing_bom_snapshot set bom_version_no = 'v99' where id = '"
                + snapshot.id()
                + "'",
            "update processing_bom_snapshot_line set scrap_rate = 99 where id = '"
                + snapshot.lines().get(0).id()
                + "'",
            "delete from processing_bom_snapshot_line where id = '"
                + snapshot.lines().get(0).id()
                + "'",
            "delete from processing_bom_snapshot where id = '" + snapshot.id() + "'")) {
      assertThatThrownBy(
              () -> {
                try (var c =
                        DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                    var s2 = c.createStatement()) {
                  s2.execute(stmt);
                }
              })
          .as(stmt)
          .hasMessageContaining("已冻结快照");
    }

    // 一个数都没变。
    var original = snapshot.lines().get(0);
    var after = asA(() -> service.binding(order.id(), "Bearer tenant-a")).snapshot();
    assertThat(after.bomVersionNo()).isEqualTo("v1");
    assertThat(after.lines().get(0).scrapRate()).isEqualByComparingTo(original.scrapRate());
    assertThat(after.lines().get(0).id()).isEqualTo(original.id());
    assertThat(after.lines()).hasSameSizeAs(snapshot.lines());
  }

  // ================================================================ 冻结的前置条件

  @Test
  @DisplayName("草稿 BOM 版本不能冻结：按草稿冻结的树无从回溯（规则 R3）")
  void draftVersionCannotBeFrozen() {
    var order = createOrder(a, "PO-DRAFT-001");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.freeze(
                            order.id(),
                            new BomBindingModels.BomFreezeInput(
                                versionDraft, new BigDecimal("10"), null, null, null, ""),
                            user,
                            "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能冻结展开结果");
  }

  @Test
  @DisplayName("已下达的加工单不能补冻结：否则「按哪棵树算的」会有两个答案")
  void releasedOrderCannotBeFrozenLater() {
    var order = createOrder(a, "PO-RELEASED-001");
    freeze(order.id());

    asA(
        () -> {
          orders.advanceStatus(
              order.id(),
              new ProcessingModels.ProcessingStatusInput("RELEASED", null, "发给上海某加工厂", null, 0L),
              user);
          return null;
        });

    assertThatThrownBy(() -> freeze(order.id()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能再冻结");
  }

  @Test
  @DisplayName("冻结的版本必须与加工单绑定的版本一致")
  void freezeRejectsVersionMismatch() {
    var order = createOrder(a, "PO-MISMATCH-001");
    // 伪造一个「别的版本」：同一个 BOM 的另一个已发布版本号。
    catalogBoms.put(
        versionDraft.toString(),
        bomMeta(versionDraft, "v9", "PUBLISHED", withScrap("5", "0", "0")));

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.freeze(
                            order.id(),
                            new BomBindingModels.BomFreezeInput(
                                versionDraft, new BigDecimal("10"), null, null, null, ""),
                            user,
                            "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不是同一版");
  }

  @Test
  @DisplayName("展开被商品中心拒（循环引用）时如实报 409，不落任何快照")
  void cycleRejectionSurfacesAsConflict() throws Exception {
    var order = createOrder(a, "PO-CYCLE-001");
    var cyclic = HttpServer.create(new InetSocketAddress(0), 0);
    cyclic.createContext(
        "/catalog/bom-explosions",
        e -> send(cyclic, e, 409, Map.of("message", "BOM 循环引用：BOM-001 → SA → BOM-001")));
    cyclic.start();
    var port = cyclic.getAddress().getPort();
    try {
      var ported = new BomExplosionPort("http://127.0.0.1:" + port);
      assertThatThrownBy(
              () ->
                  ported.explode(
                      versionPublished, new BigDecimal("10"), null, null, null, "", "Bearer t"))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("循环引用")
          .hasMessageContaining("BOM-001");
    } finally {
      cyclic.stop(0);
    }
    // 一次失败的冻结不能留下半份快照。
    long rows =
        asA(() -> sql.fetchOne("select count(*) from processing_bom_snapshot where order_id = ?", order.id())
            .get(0, Long.class));
    assertThat(rows).isZero();
  }

  @Test
  @DisplayName("catalog 不可达时 503 且不落库，绝不返回空快照冒充「没有组件」")
  void catalogDownFailsLoudly() {
    var order = createOrder(a, "PO-CATDOWN-001");
    var dead = new BomExplosionPort("http://127.0.0.1:1");
    assertThatThrownBy(
            () ->
                dead.explode(versionPublished, new BigDecimal("10"), null, null, null, "", "Bearer t"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不可达");
    long rows =
        asA(() -> sql.fetchOne("select count(*) from processing_bom_snapshot where order_id = ?", order.id())
            .get(0, Long.class));
    assertThat(rows).isZero();
  }

  @Test
  @DisplayName("展开树为空时不冻结：零行快照等于「这批料不用领任何组件」")
  void emptyExplosionIsRejected() {
    var order = createOrder(a, "PO-EMPTY-001");
    catalogBoms.put(
        versionPublished.toString(), bomMeta(versionPublished, "v1", "PUBLISHED", List.of()));
    assertThatThrownBy(() -> freeze(order.id()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有任何组件");
  }

  @Test
  @DisplayName("asOf 原样落库并可读回：Timestamp.valueOf 的静默 −28800s 不复存在")
  void asOfKeepsItsOffset() {
    var order = createOrder(a, "PO-ASOF-001");
    var instant = OffsetDateTime.parse("2026-10-10T08:00:00+08:00");
    var frozen =
        asA(
            () ->
                service.freeze(
                    order.id(),
                    new BomBindingModels.BomFreezeInput(
                        versionPublished, new BigDecimal("10"), null, instant.toString(), null, ""),
                    user,
                    "Bearer tenant-a"));

    // 写回的值就是写进去的值（同一瞬间，不是偏了 8 小时的另一个瞬间）。
    assertThat(OffsetDateTime.parse(frozen.snapshot().asOf()))
        .isEqualTo(instant.withOffsetSameInstant(ZoneOffset.UTC));

    // 直接查库核对列值，确保往返没有再偏一次。
    Record row =
        asA(
            () ->
                sql.fetchOne(
                    "select as_of, frozen_at from processing_bom_snapshot where id = ?",
                    frozen.snapshot().id()));
    assertThat(row.get("as_of", OffsetDateTime.class).toInstant())
        .isEqualTo(instant.toInstant());
  }

  @Test
  @DisplayName("asOf 缺时区偏移量直接拒绝：猜一个会在东八区静默偏 −28800s")
  void asOfWithoutOffsetIsRejected() {
    var order = createOrder(a, "PO-ASOF-BAD-001");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.freeze(
                            order.id(),
                            new BomBindingModels.BomFreezeInput(
                                versionPublished, new BigDecimal("10"), null, "2026-10-10T08:00:00", null, ""),
                            user,
                            "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("格式不对");
  }

  @Test
  @DisplayName("深度上限命中时快照标记 TRUNCATED 并在读回时显式告警")
  void truncatedSnapshotIsVisible() {
    var order = createOrder(a, "PO-TRUNC-001");
    var frozen =
        asA(
            () ->
                service.freeze(
                    order.id(),
                    new BomBindingModels.BomFreezeInput(
                        versionPublished, new BigDecimal("10"), null, null, 1, "深度 1"),
                    user,
                    "Bearer tenant-a"));
    assertThat(frozen.snapshot().snapshotStatus()).isEqualTo("TRUNCATED");
    assertThat(frozen.snapshot().truncated()).isTrue();

    var binding = asA(() -> service.binding(order.id(), "Bearer tenant-a"));
    assertThat(binding.snapshot().truncated()).isTrue();
    assertThat(binding.notes()).anySatisfy(n -> assertThat(n).contains("截断").contains("不完整"));
  }

  // ================================================================ 多租户三断言（AGENTS.md 硬要求）

  @Test
  @DisplayName("多租户断言 1：A 看不到 B 的冻结快照（RLS 下查不到 → 404）")
  void tenantIsolationSnapshot() {
    var orderA = createOrder(a, "PO-ISO-A");
    var orderB = createOrder(b, "PO-ISO-B");
    freeze(orderA.id());
    asB(() -> service.binding(orderB.id(), "Bearer tenant-b"));

    var detailA = asA(() -> service.binding(orderA.id(), "Bearer tenant-a"));
    assertThat(detailA.orderNo()).isEqualTo("PO-ISO-A");
    assertThat(detailA.snapshot()).isNotNull();

    // B 的单在 A 的租户上下文里根本查不到（连单头都查不到，不是「查到了但是空的」）。
    assertThatThrownBy(() -> asA(() -> service.binding(orderB.id(), "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或无权查看");
    // 明细同样读不到。
    assertThatThrownBy(() -> asA(() -> service.lines(orderB.id())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或无权查看");
  }

  @Test
  @DisplayName("多租户断言 2：伪造 X-Tenant-Id 无效，且行确实落在自称的租户名下")
  void forgedTenantHeaderCannotMoveRows() throws Exception {
    var orderA = createOrder(a, "PO-FORGE-A");
    var frozenA = freeze(orderA.id());

    // A 的身份 + 伪造 B 的头：读 A 的单正常，快照在 A 名下。
    http.perform(
            get("/trade/processing/orders/{id}/bom-binding", orderA.id().toString())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.snapshot.bomVersionNo").value("v1"));

    // 冻结动作同样只按已认证成员关系落租户 —— 伪造头把行写到了别处吗？没有。
    var orderA2 = createOrder(a, "PO-FORGE-A2");
    http.perform(
            post("/trade/processing/orders/{id}/bom-binding", orderA2.id().toString())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new BomBindingModels.BomFreezeInput(
                            versionPublished, new BigDecimal("10"), null, null, null, ""))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alreadyFrozen").value(false));

    // **正面断言**：两张快照的 tenant_id 都是 A，而不是伪造头声称的 B。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs =
            s.executeQuery(
                "select count(*) from processing_bom_snapshot where tenant_id = '"
                    + a
                    + "' and order_id in ('"
                    + orderA.id()
                    + "','"
                    + orderA2.id()
                    + "')")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getLong(1)).isEqualTo(2L);
    }
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs =
            s.executeQuery(
                "select count(*) from processing_bom_snapshot where tenant_id = '"
                    + b
                    + "' and order_id in ('"
                    + orderA.id()
                    + "','"
                    + orderA2.id()
                    + "')")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getLong(1)).as("伪造的头不能把行写进 B 租户").isZero();
    }
    // 明细也一样在 A 名下。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs =
            s.executeQuery(
                "select count(*) from processing_bom_snapshot_line where tenant_id = '"
                    + a
                    + "' and snapshot_id = '"
                    + frozenA.snapshot().id()
                    + "'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getLong(1)).isEqualTo(3L);
    }

    // 用 A 的身份去读 B 的单：404（租户由 Bearer 决定，伪造头无效）。
    var orderB = createOrder(b, "PO-FORGE-B");
    asB(() -> service.binding(orderB.id(), "Bearer tenant-b"));
    http.perform(
            get("/trade/processing/orders/{id}/bom-binding", orderB.id().toString())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("多租户断言 3：连接池归还后不串租户（池压到 1，20 轮往返）")
  void connectionPoolDoesNotLeakTenant() {
    var orderA = createOrder(a, "PO-POOL-A");
    var orderB = createOrder(b, "PO-POOL-B");
    freeze(orderA.id());
    asB(
        () ->
            service.freeze(
                orderB.id(),
                new BomBindingModels.BomFreezeInput(
                    versionPublished, new BigDecimal("10"), null, null, null, ""),
                user,
                "Bearer tenant-b"));

    for (int i = 0; i < 20; i++) {
      var bindingA = asA(() -> service.binding(orderA.id(), "Bearer tenant-a"));
      assertThat(bindingA.orderNo()).isEqualTo("PO-POOL-A");
      assertThat(bindingA.snapshot().bomVersionNo()).isEqualTo("v1");
      assertThat(bindingA.snapshot().lines()).hasSize(3);

      var bindingB = asB(() -> service.binding(orderB.id(), "Bearer tenant-b"));
      assertThat(bindingB.orderNo()).isEqualTo("PO-POOL-B");
      assertThat(bindingB.snapshot()).isNotNull();
      assertThat(bindingB.snapshot().lines()).hasSize(3);
    }
  }

  @Test
  @DisplayName("每张新表都 relforcerowsecurity")
  void rlsIsForcedOnEverySnapshotTable() {
    for (String table : List.of("processing_bom_snapshot", "processing_bom_snapshot_line")) {
      Boolean forced =
          asA(
              () ->
                  sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?", table)
                      .get(0, Boolean.class));
      assertThat(forced).as(table).isTrue();
    }
    // 授权侧也钉住：trade_app 对两张新表有写权，但对已冻结的表没有改写权。
    Record revoked =
        asA(
            () ->
                sql.fetchOne(
                    "select 1 from information_schema.role_table_grants"
                        + " where grantee = 'trade_app' and table_name = 'processing_bom_snapshot'"
                        + " and privilege_type = 'UPDATE'"));
    assertThat(revoked).as("trade_app 对已冻结快照不应保留 UPDATE 权（REVOKE 必须真的生效）").isNull();
    Record granted =
        asA(
            () ->
                sql.fetchOne(
                    "select 1 from information_schema.role_table_grants"
                        + " where grantee = 'trade_app' and table_name = 'processing_bom_snapshot'"
                        + " and privilege_type = 'INSERT'"));
    assertThat(granted).as("trade_app 必须保留 INSERT 权，否则冻结动作根本写不进去").isNotNull();
  }

  // ================================================================ HTTP 契约

  @Test
  @DisplayName("HTTP 契约：401 未认证 / 403 只读不能冻结 / 404 跨租户 / 读冻结幂等")
  void httpContract() throws Exception {
    var order = createOrder(a, "PO-HTTP-001");

    http.perform(get("/trade/processing/orders/{id}/bom-binding", order.id().toString()))
        .andExpect(status().isUnauthorized());

    // 只读身份能读，但不能冻结（冻结等于让仓库照这棵树备料）。
    http.perform(
            post("/trade/processing/orders/{id}/bom-binding", order.id().toString())
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new BomBindingModels.BomFreezeInput(
                            versionPublished, new BigDecimal("10"), null, null, null, ""))))
        .andExpect(status().isForbidden());

    http.perform(
            post("/trade/processing/orders/{id}/bom-binding", order.id().toString())
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new BomBindingModels.BomFreezeInput(
                            versionPublished, new BigDecimal("10"), null, null, null, ""))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.snapshot.lineCount").value(3))
        .andExpect(jsonPath("$.snapshot.lines[0].scrapRate").value(5.0))
        .andExpect(jsonPath("$.alreadyFrozen").value(false));

    // 重放同一请求：幂等，且明确回报 alreadyFrozen。
    http.perform(
            post("/trade/processing/orders/{id}/bom-binding", order.id().toString())
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new BomBindingModels.BomFreezeInput(
                            versionPublished, new BigDecimal("10"), null, null, null, ""))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alreadyFrozen").value(true));

    // 读绑定：带漂移判定与提示。
    http.perform(
            get("/trade/processing/orders/{id}/bom-binding", order.id().toString())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.drifted").value(false))
        .andExpect(jsonPath("$.snapshot.truncated").value(false));

    // 只读展开树：纯本地读。
    http.perform(
            get("/trade/processing/orders/{id}/bom-binding/lines", order.id().toString())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].path").value("1"));

    // 跨租户 404。
    var orderB = createOrder(b, "PO-HTTP-B");
    http.perform(
            get("/trade/processing/orders/{id}/bom-binding", orderB.id().toString())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());

    // 不存在的单：404（而不是 500）。
    http.perform(
            get("/trade/processing/orders/{id}/bom-binding", UUID.randomUUID().toString())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("未冻结的加工单：绑定读得出「还没冻结」，明细返回空数组而不是 404")
  void unboundOrderIsAReadableState() throws Exception {
    var order = createOrder(a, "PO-UNBOUND-001");
    var binding = asA(() -> service.binding(order.id(), "Bearer tenant-a"));
    assertThat(binding.snapshot()).isNull();
    assertThat(binding.drifted()).isFalse();
    assertThat(binding.notes()).anySatisfy(n -> assertThat(n).contains("还没有冻结"));

    http.perform(
            get("/trade/processing/orders/{id}/bom-binding/lines", order.id().toString())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }
}