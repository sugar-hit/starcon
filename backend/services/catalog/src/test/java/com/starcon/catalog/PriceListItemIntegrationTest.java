package com.starcon.catalog;

import static com.starcon.catalog.PriceListItemModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 5.1.1.2 价目项批量维护：真实 PostgreSQL 16 + 强制 RLS 下的价目项 CRUD、批量设价
 * （固定值 / 按百分比 / 按类目统一）、阶梯价、冲突检测、解析歧义、Excel 导入导出。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：价目项真的落库。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的断言</b>：
 *
 * <ol>
 *   <li>{@link #价目项真的落库_币种来自价目表()}：价目项行的币种**不存在**，
 *       视图里的币种是从价目表头带出来的（DB 层结构性地挡掉跨币种）。
 *   <li>{@link #同优先级多条有效价目必须报错让用户选而不是随机取()}：蓝图加粗句 ——
 *       409 + 候选清单，{@code selected} 恒为 null。
 *   <li>{@link #阶梯价不是冲突_按数量选档()}：先选档再选优先级。
 *   <li>{@link #批量预览先看影响范围且不落库()}：预览与执行走同一个计划函数。
 *   <li>{@link #导入有错误时整批不落库()}：先报后改。
 *   <li>{@link #导出是真实的xlsx()}：导出的字节能被 POI 真的打开并读回行。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PriceListItemIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  /**
   * 测试用的 JSON 序列化器。
   *
   * <p>🔴 必须关掉 {@code WRITE_DATES_AS_TIMESTAMPS}：默认的 ObjectMapper 会把
   * {@link OffsetDateTime} 写成**数字**，而服务端只认 ISO 字符串 ——
   * 那样 POST 会被 400 掉，而原因藏在序列化器里。
   */
  static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();

  static AccessService.Access actor(
      UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static List<String> allPermissions() {
    return java.util.stream.Stream.of("spu", "sku", "attribute", "research", "compliance", "assets")
        .flatMap(
            p ->
                java.util.stream.Stream.of("read", "create", "update", "delete")
                    .map(op -> "catalog." + p + "." + op))
        .toList();
  }

  static {
    pg.start();
    try (var conn =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = conn.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
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
            byte[] body = JSON.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant(allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant(allPermissions()))));
      actors.put(
          "Bearer reader",
          actor(user, a, List.of(grant(List.of("catalog.spu.read", "catalog.sku.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static AccessService.Grant grant(List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions);
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "catalog_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接，才能真正证明归还之后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @Autowired PriceListItemService items;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actorA;
  AccessService.Access actorB;

  @BeforeEach
  void setUp() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  // ================================================================ 夹具

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  static String unique(String prefix) {
    return prefix + "-" + SEQ.incrementAndGet();
  }

  /** 一张 USD 价目表。 */
  UUID newPriceList(String currency) {
    return write(
        actorA,
        () -> {
          var id = UUID.randomUUID();
          sql.execute(
              "insert into price_list(id,tenant_id,code,name,currency,valid_from,enabled,created_by)"
                  + " values(?::uuid,?::uuid,?,?,?,?::timestamptz,true,?::uuid)",
              id,
              a,
              unique("PL"),
              "价目表",
              currency,
              OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
              user);
          return id;
        });
  }

  /** 一个 SPU（带分类与品牌）。 */
  UUID newSpu(String code, String category, String brand) {
    return write(
        actorA,
        () -> {
          var id = UUID.randomUUID();
          sql.execute(
              "insert into spu(id,tenant_id,code,name,brand,category,status)"
                  + " values(?::uuid,?::uuid,?,?,?,?,'ON_SALE')",
              id,
              a,
              code,
              "SPU " + code,
              brand,
              category);
          return id;
        });
  }

  /** 一个 SKU。 */
  UUID newSku(UUID spuId, String code) {
    return write(
        actorA,
        () -> {
          var id = UUID.randomUUID();
          sql.execute(
              "insert into sku(id,tenant_id,spu_id,code,name,barcode,base_unit,status,variant_signature)"
                  + " values(?::uuid,?::uuid,?::uuid,?,?,?,'件','ACTIVE',?)",
              id,
              a,
              spuId,
              code,
              "SKU " + code,
              "BAR-" + code,
              code);
          return id;
        });
  }

  private PriceListItemInput itemInput(String skuCode, String price, String from) {
    return new PriceListItemInput(
        skuCode,
        new BigDecimal(price),
        BigDecimal.ZERO,
        0,
        OffsetDateTime.parse(from),
        null,
        Boolean.TRUE,
        "",
        null);
  }

  private PriceListItemView saveItem(UUID priceListId, String skuCode, String price, String from) {
    return write(actorA, () -> items.save(actorA, priceListId, null, itemInput(skuCode, price, from)));
  }

  // ================================================================ 断言一：真的落库 + 币种来自价目表

  @Test
  void 价目项真的落库_币种来自价目表() {
    var list = newPriceList("USD");
    var spu = newSpu(unique("SPU"), "家居", "ACME");
    var sku = newSku(spu, unique("SKU"));

    var view = saveItem(list, skuCode(sku), "19.99", "2026-01-01T00:00:00Z");

    assertThat(view.skuCode()).isEqualTo(skuCode(sku));
    assertThat(view.unitPrice()).isEqualByComparingTo(new BigDecimal("19.99"));
    // 币种是从价目表头带出来的（价目项自己**没有**这个字段）。
    assertThat(view.currency()).isEqualTo("USD");

    var row =
        tx(
            actorA,
            () ->
                sql.fetchOne(
                    "select tenant_id, price_list_id, sku_id, unit_price, min_quantity, priority"
                        + " from price_list_item where id=?",
                    view.id()));
    assertThat(row).isNotNull();
    assertThat(row.get("tenant_id", UUID.class)).isEqualTo(a);
    assertThat(row.get("price_list_id", UUID.class)).isEqualTo(list);
    assertThat(row.get("sku_id", UUID.class)).isEqualTo(sku);
    assertThat(row.get("unit_price", BigDecimal.class)).isEqualByComparingTo(new BigDecimal("19.99"));
  }

  /** SKU 编码（插入时用的就是它，编码唯一）。 */
  private String skuCode(UUID skuId) {
    return tx(actorA, () -> sql.fetchOne("select code from sku where id=?", skuId).get(0, String.class));
  }

  @Test
  void 价目项表里没有币种列_结构上无法跨币种() {
    var columns =
        tx(
            actorA,
            () ->
                sql.fetch(
                    "select column_name from information_schema.columns"
                        + " where table_name='price_list_item'"));
    var names = columns.stream().map(r -> r.get(0, String.class)).toList();
    assertThat(names).contains("unit_price", "min_quantity", "priority", "valid_from", "valid_to");
    // 🔴 价目项**没有**币种列 ⇒ 一个价目表里的价目项在结构上不可能跨币种。
    assertThat(names).doesNotContain("currency");

    // 视图里的币种确实来自价目表头。
    var list = newPriceList("EUR");
    var spu = newSpu(unique("SPU"), "家居", "ACME");
    var sku = newSku(spu, unique("SKU"));
    var view = saveItem(list, skuCode(sku), "9.99", "2026-01-01T00:00:00Z");
    assertThat(view.currency()).isEqualTo("EUR");
  }

  // ================================================================ 断言二：数量与时间都是精确十进制

  @Test
  void 数量门槛是NUMERIC19_6_小数逐字相等() {
    var list = newPriceList("USD");
    var spu = newSpu(unique("SPU"), "家居", "ACME");
    var sku = newSku(spu, unique("SKU"));
    var input =
        new PriceListItemInput(
            skuCode(sku),
            new BigDecimal("0.000001"),
            new BigDecimal("0.5"),
            0,
            OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            null,
            Boolean.TRUE,
            "",
            null);
    var view = write(actorA, () -> items.save(actorA, list, null, input));
    assertThat(view.minQuantity()).isEqualByComparingTo(new BigDecimal("0.5"));
    assertThat(view.unitPrice()).isEqualByComparingTo(new BigDecimal("0.000001"));

    // 直接查库：列类型真的是 NUMERIC(19,6)，值逐字相等（不是浮点近似）。
    var row =
        tx(
            actorA,
            () ->
                sql.fetchOne(
                    "select unit_price, min_quantity from price_list_item where id=?", view.id()));
    assertThat(row.get("unit_price", BigDecimal.class)).isEqualByComparingTo(new BigDecimal("0.000001"));
    assertThat(row.get("min_quantity", BigDecimal.class)).isEqualByComparingTo(new BigDecimal("0.5"));
    var type =
        tx(
            actorA,
            () ->
                sql.fetchOne(
                    "select data_type, numeric_precision, numeric_scale from information_schema.columns"
                        + " where table_name='price_list_item' and column_name='min_quantity'"));
    assertThat(type.get("data_type", String.class)).isEqualTo("numeric");
    assertThat(type.get("numeric_precision", Integer.class)).isEqualTo(19);
    assertThat(type.get("numeric_scale", Integer.class)).isEqualTo(6);
  }

  @Test
  void 生效区间写回值逐字相等_不偏8小时() {
    var list = newPriceList("USD");
    var spu = newSpu(unique("SPU"), "家居", "ACME");
    var sku = newSku(spu, unique("SKU"));
    // 用 +08:00 的偏移写：如果服务端用 Timestamp.valueOf(LocalDateTime) 转换，
    // 读回来会静默偏 −28800s。
    var from = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");
    var view =
        write(
            actorA,
            () ->
                items.save(
                    actorA,
                    list,
                    null,
                    new PriceListItemInput(
                        skuCode(sku),
                        new BigDecimal("12.34"),
                        BigDecimal.ZERO,
                        0,
                        from,
                        null,
                        Boolean.TRUE,
                        "",
                        null)));
    assertThat(view.validFrom()).isEqualTo(from);
    var row =
        tx(
            actorA,
            () ->
                sql.fetchOne("select valid_from from price_list_item where id=?", view.id()));
    assertThat(row.get("valid_from", OffsetDateTime.class).toInstant())
        .isEqualTo(from.toInstant());
  }

  // ================================================================ 断言三：🔴 同优先级多条有效价目 ⇒ 报错让用户选

  @Test
  void 同优先级多条有效价目必须报错让用户选而不是随机取() throws Exception {
    var list = newPriceList("USD");
    var spu = newSpu(unique("SPU"), "家居", "ACME");
    var sku = newSku(spu, unique("SKU"));
    var code = skuCode(sku);
    // 两条：同 SKU、同档位、同优先级、生效区间重叠。
    saveItem(list, code, "19.99", "2026-01-01T00:00:00Z");
    var second =
        write(
            actorA,
            () ->
                items.save(
                    actorA,
                    list,
                    null,
                    new PriceListItemInput(
                        code,
                        new BigDecimal("21.99"),
                        BigDecimal.ZERO,
                        0,
                        OffsetDateTime.parse("2026-02-01T00:00:00Z"),
                        null,
                        Boolean.TRUE,
                        "",
                        null)));

    // 冲突清单报出来了（区间重叠 + 同优先级 + 同档位）。
    var conflicts = tx(actorA, () -> items.conflicts(list));
    assertThat(conflicts).hasSize(1);
    assertThat(conflicts.get(0).skuCode()).isEqualTo(code);
    assertThat(conflicts.get(0).candidates()).hasSize(2);

    // 🔴 解析出口：409 + 候选清单（绝不随机挑一条）。
    http.perform(
            get("/catalog/price-lists/lists/{id}/items/resolve", list)
                .param("skuCode", code)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.ambiguous").value(true))
        .andExpect(jsonPath("$.candidates.length()").value(2))
        .andExpect(jsonPath("$.skuCode").value(code));

    // 服务层同样是歧义：selected 恒为 null。
    var ambiguity =
        catchThrowableOfType(
            () -> tx(actorA, () -> items.resolve(list, code, null, null)),
            PriceListItemService.PriceAmbiguityException.class);
    assertThat(ambiguity).isNotNull();
    assertThat(ambiguity.resolution().selected()).isNull();
    assertThat(ambiguity.resolution().unitPrice()).isNull();
    assertThat(ambiguity.resolution().candidates()).hasSize(2);
    assertThat(ambiguity.getMessage()).contains("不会随机取一条");
    assertThat(second.id()).isNotNull();
  }

  @Test
  void 不同优先级的多条有效价目取更优先的那条() {
    var list = newPriceList("USD");
    var spu = newSpu(unique("SPU"), "家居", "ACME");
    var sku = newSku(spu, unique("SKU"));
    var code = skuCode(sku);
    // 🔴 数字**越小**越优先 ⇒ 19.99 那条给 5，17.99 那条给 1。
    write(
        actorA,
        () ->
            items.save(
                actorA,
                list,
                null,
                new PriceListItemInput(
                    code,
                    new BigDecimal("19.99"),
                    BigDecimal.ZERO,
                    5,
                    OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                    null,
                    Boolean.TRUE,
                    "",
                    null)));
    write(
        actorA,
        () ->
            items.save(
                actorA,
                list,
                null,
                new PriceListItemInput(
                    code,
                    new BigDecimal("17.99"),
                    BigDecimal.ZERO,
                    1,
                    OffsetDateTime.parse("2026-02-01T00:00:00Z"),
                    null,
                    Boolean.TRUE,
                    "",
                    null)));

    var resolution = tx(actorA, () -> items.resolve(list, code, null, null));
    assertThat(resolution.ambiguous()).isFalse();
    assertThat(resolution.unitPrice()).isEqualByComparingTo(new BigDecimal("17.99"));
    assertThat(resolution.candidates()).hasSize(2);
    // 冲突清单是空的（优先级不同 ⇒ 可判定）。
    assertThat(tx(actorA, () -> items.conflicts(list))).isEmpty();
  }

  // ================================================================ 断言四：阶梯价不是冲突

  @Test
  void 阶梯价不是冲突_按数量选档() {
    var list = newPriceList("USD");
    var spu = newSpu(unique("SPU"), "家居", "ACME");
    var sku = newSku(spu, unique("SKU"));
    var code = skuCode(sku);
    write(
        actorA,
        () ->
            items.save(
                actorA,
                list,
                null,
                new PriceListItemInput(
                    code,
                    new BigDecimal("19.99"),
                    BigDecimal.ZERO,
                    0,
                    OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                    null,
                    Boolean.TRUE,
                    "",
                    null)));
    write(
        actorA,
        () ->
            items.save(
                actorA,
                list,
                null,
                new PriceListItemInput(
                    code,
                    new BigDecimal("15.99"),
                    new BigDecimal("100"),
                    0,
                    OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                    null,
                    Boolean.TRUE,
                    "",
                    null)));

    // 🔴 两档同优先级并存**不是**冲突（档位不同）。
    assertThat(tx(actorA, () -> items.conflicts(list))).isEmpty();

    var bulk = tx(actorA, () -> items.resolve(list, code, new BigDecimal("150"), null));
    assertThat(bulk.unitPrice()).isEqualByComparingTo(new BigDecimal("15.99"));
    var small = tx(actorA, () -> items.resolve(list, code, new BigDecimal("50"), null));
    assertThat(small.unitPrice()).isEqualByComparingTo(new BigDecimal("19.99"));
  }

  // ================================================================ 断言五：批量

  @Test
  void 批量预览先看影响范围且不落库() {
    var list = newPriceList("USD");
    var category = unique("CAT");
    var spu = newSpu(unique("SPU"), category, "ACME");
    var skuA = newSku(spu, unique("SKU"));
    var skuB = newSku(spu, unique("SKU"));
    var request =
        new PriceListItemBatchRequest(
            BatchMode.FIXED,
            BatchScope.CATEGORY,
            category,
            null,
            List.of(),
            List.of(),
            new BigDecimal("9.90"),
            null,
            BigDecimal.ZERO,
            0,
            OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            null,
            Boolean.TRUE,
            "");

    var preview = tx(actorA, () -> items.preview(list, request));
    assertThat(preview.matchedSkus()).isEqualTo(2);
    assertThat(preview.createdItems()).isEqualTo(2);
    assertThat(preview.skippedSkus()).isZero();
    assertThat(preview.lines()).hasSize(2);
    assertThat(preview.lines().get(0).action()).isEqualTo("CREATE");
    assertThat(preview.lines().get(0).newPrice()).isEqualByComparingTo(new BigDecimal("9.90"));

    // 🔴 预览**没有**落库。
    assertThat(countItems(list)).isZero();
    assertThat(skuA).isNotNull();
    assertThat(skuB).isNotNull();
  }

  @Test
  void 批量按固定值设价_按分类() {
    var list = newPriceList("USD");
    var category = unique("CAT");
    var spu = newSpu(unique("SPU"), category, "ACME");
    var other = newSpu(unique("SPU"), unique("CAT"), "ACME");
    newSku(spu, unique("SKU"));
    newSku(spu, unique("SKU"));
    newSku(other, unique("SKU"));

    var applied =
        write(
            actorA,
            () ->
                items.apply(
                    actorA,
                    list,
                    new PriceListItemBatchRequest(
                        BatchMode.FIXED,
                        BatchScope.CATEGORY,
                        category,
                        null,
                        List.of(),
                        List.of(),
                        new BigDecimal("9.90"),
                        null,
                        BigDecimal.ZERO,
                        0,
                        OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                        null,
                        Boolean.TRUE,
                        "")));
    assertThat(applied.matchedSkus()).isEqualTo(2);
    assertThat(applied.createdItems()).isEqualTo(2);
    assertThat(countItems(list)).isEqualTo(2);

    // 再跑一次同样的批量 ⇒ 变成**覆盖**同槽位，而不是再插一条。
    var again =
        write(
            actorA,
            () ->
                items.apply(
                    actorA,
                    list,
                    new PriceListItemBatchRequest(
                        BatchMode.FIXED,
                        BatchScope.CATEGORY,
                        category,
                        null,
                        List.of(),
                        List.of(),
                        new BigDecimal("11.00"),
                        null,
                        BigDecimal.ZERO,
                        0,
                        OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                        null,
                        Boolean.TRUE,
                        "")));
    assertThat(again.createdItems()).isZero();
    assertThat(again.updatedItems()).isEqualTo(2);
    assertThat(countItems(list)).isEqualTo(2);
  }

  @Test
  void 批量按百分比设价_按品牌() {
    var list = newPriceList("USD");
    var brand = unique("BRAND");
    var spu = newSpu(unique("SPU"), unique("CAT"), brand);
    var sku = newSku(spu, unique("SKU"));
    var code = skuCode(sku);
    saveItem(list, code, "19.99", "2026-01-01T00:00:00Z");

    var applied =
        write(
            actorA,
            () ->
                items.apply(
                    actorA,
                    list,
                    new PriceListItemBatchRequest(
                        BatchMode.PERCENTAGE,
                        BatchScope.BRAND,
                        null,
                        brand,
                        List.of(),
                        List.of(),
                        null,
                        new BigDecimal("10"),
                        BigDecimal.ZERO,
                        0,
                        OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                        null,
                        Boolean.TRUE,
                        "")));
    assertThat(applied.updatedItems()).isEqualTo(1);
    assertThat(applied.createdItems()).isZero();
    var price =
        tx(
            actorA,
            () ->
                sql.fetchOne(
                        "select unit_price from price_list_item where price_list_id=?", list)
                    .get(0, BigDecimal.class));
    // 19.99 × 1.10 = 21.989
    assertThat(price).isEqualByComparingTo(new BigDecimal("21.989000"));
  }

  @Test
  void 批量按类目统一_每个分类一个价() {
    var list = newPriceList("USD");
    var homeCategory = unique("CAT");
    var outdoorCategory = unique("CAT");
    var home = newSpu(unique("SPU"), homeCategory, "ACME");
    var outdoor = newSpu(unique("SPU"), outdoorCategory, "ACME");
    newSku(home, unique("SKU"));
    newSku(outdoor, unique("SKU"));

    var applied =
        write(
            actorA,
            () ->
                items.apply(
                    actorA,
                    list,
                    new PriceListItemBatchRequest(
                        BatchMode.CATEGORY_UNIFIED,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(
                            new PriceListItemCategoryPrice(homeCategory, new BigDecimal("5.00")),
                            new PriceListItemCategoryPrice(outdoorCategory, new BigDecimal("8.00"))),
                        null,
                        null,
                        BigDecimal.ZERO,
                        0,
                        OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                        null,
                        Boolean.TRUE,
                        "")));
    assertThat(applied.matchedSkus()).isEqualTo(2);
    assertThat(applied.createdItems()).isEqualTo(2);
    var prices =
        tx(
            actorA,
            () ->
                sql.fetch("select unit_price from price_list_item where price_list_id=?", list)
                    .stream()
                    .map(r -> r.get(0, BigDecimal.class))
                    .toList());
    assertThat(prices).containsExactlyInAnyOrder(
        new BigDecimal("5.000000"), new BigDecimal("8.000000"));
  }

  @Test
  void 批量按SKU清单_不存在的SKU被跳过并说明原因() {
    var list = newPriceList("USD");
    var spu = newSpu(unique("SPU"), "家居", "ACME");
    var sku = newSku(spu, unique("SKU"));
    var request =
        new PriceListItemBatchRequest(
            BatchMode.FIXED,
            BatchScope.SKU_SET,
            null,
            null,
            List.of(skuCode(sku), "NOT-EXIST-SKU"),
            List.of(),
            new BigDecimal("3.00"),
            null,
            BigDecimal.ZERO,
            0,
            OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            null,
            Boolean.TRUE,
            "");

    var preview = tx(actorA, () -> items.preview(list, request));
    assertThat(preview.matchedSkus()).isEqualTo(1);
    assertThat(preview.skippedSkus()).isEqualTo(1);
    var skipped = preview.lines().stream().filter(l -> "SKIP".equals(l.action())).findFirst();
    assertThat(skipped).isPresent();
    assertThat(skipped.get().reason()).contains("SKU 不存在");
  }

  // ================================================================ 断言六：Excel 导入导出

  @Test
  void 导出是真实的xlsx() throws Exception {
    var list = newPriceList("USD");
    var spu = newSpu(unique("SPU"), "家居", "ACME");
    var sku = newSku(spu, unique("SKU"));
    saveItem(list, skuCode(sku), "19.99", "2026-01-01T00:00:00Z");

    var bytes = tx(actorA, () -> items.exportXlsx(list));
    assertThat(bytes).isNotEmpty();
    // ZIP 魔数（xlsx 就是 zip）。
    assertThat(bytes[0] & 0xFF).isEqualTo(0x50);
    assertThat(bytes[1] & 0xFF).isEqualTo(0x4B);

    // 🔴 真的能被 POI 打开并读回行 —— 不是「假 xlsx」。
    try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
      var sheet = workbook.getSheetAt(0);
      assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("SKU编码");
      assertThat(sheet.getLastRowNum()).isGreaterThan(0);
      assertThat(sheet.getRow(1).getCell(2).getNumericCellValue())
          .isEqualTo(19.99);
    }

    // 端点也真的返回这个 content-type（浏览器代理据此原样透传字节）。
    http.perform(
            get("/catalog/price-lists/lists/{id}/items/export", list)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Content-Type",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
  }

  @Test
  void 导入有错误时整批不落库() throws Exception {
    var list = newPriceList("USD");
    var spu = newSpu(unique("SPU"), "家居", "ACME");
    var sku = newSku(spu, unique("SKU"));
    var good = skuCode(sku);
    // 第二行：SKU 不存在；第三行：币种跨表；第四行：单价不是数字。
    var bytes =
        xlsx(
            List.of(
                List.of("SKU编码", "SKU名称", "单价", "币种", "数量门槛", "优先级", "生效起点", "生效终点", "启用", "备注"),
                List.of(good, "好行", "19.99", "USD", "0", "0", "2026-01-01T00:00:00Z", "", "是", ""),
                List.of("NO-SUCH-SKU", "坏行", "1.00", "USD", "0", "0", "2026-01-01T00:00:00Z", "", "是", ""),
                List.of(good, "跨币种", "2.00", "CNY", "0", "0", "2026-01-01T00:00:00Z", "", "是", ""),
                List.of(good, "不是数字", "abc", "USD", "0", "0", "2026-01-01T00:00:00Z", "", "是", "")));

    var report =
        write(
            actorA,
            () ->
                items.importFile(
                    actorA,
                    list,
                    new PriceListItemImportRequest(
                        "prices.xlsx", Base64.getEncoder().encodeToString(bytes), Boolean.TRUE)));

    assertThat(report.applied()).isFalse();
    assertThat(report.status()).isEqualTo("REJECTED");
    assertThat(report.errorRows()).isEqualTo(3);
    assertThat(report.okRows()).isEqualTo(1);
    // 🔴 一条都没落库。
    assertThat(countItems(list)).isZero();

    // 报告逐行写明「哪一行错、为什么」。
    var errors = report.rows().stream().filter(r -> "ERROR".equals(r.status())).toList();
    assertThat(errors).hasSize(3);
    assertThat(errors.get(0).messages()).anyMatch(m -> m.contains("SKU 不存在"));
    assertThat(errors.get(1).messages()).anyMatch(m -> m.contains("币种与价目表不一致"));
    assertThat(errors.get(2).messages()).anyMatch(m -> m.contains("单价不是数字"));
    assertThat(errors).allSatisfy(r -> assertThat(r.rowNo()).isPositive());
  }

  @Test
  void 导入全部合法时先预览再落库() throws Exception {
    var list = newPriceList("USD");
    var spu = newSpu(unique("SPU"), "家居", "ACME");
    var sku = newSku(spu, unique("SKU"));
    var other = newSku(spu, unique("SKU"));
    var bytes =
        xlsx(
            List.of(
                List.of("SKU编码", "SKU名称", "单价", "币种", "数量门槛", "优先级", "生效起点", "生效终点", "启用", "备注"),
                List.of(skuCode(sku), "第一行", "19.99", "USD", "0", "0", "2026-01-01T00:00:00Z", "", "是", ""),
                List.of(skuCode(other), "第二行", "25.50", "USD", "100", "0", "2026-01-01T00:00:00Z", "", "是", "阶梯档")));
    var encoded = Base64.getEncoder().encodeToString(bytes);

    // 第一次：只校验，不落库。
    var preview =
        write(
            actorA,
            () ->
                items.importFile(
                    actorA, list, new PriceListItemImportRequest("prices.xlsx", encoded, Boolean.FALSE)));
    assertThat(preview.status()).isEqualTo("PREVIEW");
    assertThat(preview.applied()).isFalse();
    assertThat(preview.errorRows()).isZero();
    assertThat(preview.okRows()).isEqualTo(2);
    assertThat(countItems(list)).isZero();

    // 第二次：落库。
    var applied =
        write(
            actorA,
            () ->
                items.importFile(
                    actorA, list, new PriceListItemImportRequest("prices.xlsx", encoded, Boolean.TRUE)));
    assertThat(applied.status()).isEqualTo("APPLIED");
    assertThat(applied.applied()).isTrue();
    assertThat(applied.appliedRows()).isEqualTo(2);
    assertThat(countItems(list)).isEqualTo(2);

    // 阶梯档位真的写进去了。
    var tiers =
        tx(
            actorA,
            () ->
                sql.fetch("select min_quantity, unit_price from price_list_item where price_list_id=?", list)
                    .stream()
                    .map(r -> List.of(r.get("min_quantity", BigDecimal.class), r.get("unit_price", BigDecimal.class)))
                    .toList());
    assertThat(tiers).hasSize(2);
    assertThat(tiers).anySatisfy(
        t -> assertThat(t.get(0)).isEqualByComparingTo(new BigDecimal("100")));
  }

  // ================================================================ 断言七：契约与边界

  @Test
  void 客户端不能直接写租户_未知字段直接400() throws Exception {
    var list = newPriceList("USD");
    var payload =
        "{\"skuCode\":\"X-1\",\"unitPrice\":1.00,\"validFrom\":\"2026-01-01T00:00:00Z\","
            + "\"tenantId\":\""
            + b
            + "\"}";
    http.perform(
            post("/catalog/price-lists/lists/{id}/items", list)
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(payload))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 价目项数量计数器由触发器维护() {
    var list = newPriceList("USD");
    var spu = newSpu(unique("SPU"), "家居", "ACME");
    var sku = newSku(spu, unique("SKU"));
    var code = skuCode(sku);
    assertThat(itemCountOf(list)).isZero();
    var view = saveItem(list, code, "1.00", "2026-01-01T00:00:00Z");
    assertThat(itemCountOf(list)).isEqualTo(1);
    write(
        actorA,
        () -> {
          items.delete(list, view.id());
          return null;
        });
    assertThat(itemCountOf(list)).isZero();
  }

  @Test
  void 改价目项要带版本_乐观锁拦住覆盖() {
    var list = newPriceList("USD");
    var spu = newSpu(unique("SPU"), "家居", "ACME");
    var sku = newSku(spu, unique("SKU"));
    var code = skuCode(sku);
    var view = saveItem(list, code, "19.99", "2026-01-01T00:00:00Z");

    var stale =
        new PriceListItemInput(
            code,
            new BigDecimal("21.99"),
            BigDecimal.ZERO,
            0,
            view.validFrom(),
            null,
            Boolean.TRUE,
            "",
            view.version() - 1);
    assertThatThrownBy(
            () -> write(actorA, () -> items.save(actorA, list, view.id(), stale)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已经被别人改过了");
  }

  @Test
  void 同一个SKU同档位同生效起点不能有两条() {
    var list = newPriceList("USD");
    var spu = newSpu(unique("SPU"), "家居", "ACME");
    var sku = newSku(spu, unique("SKU"));
    var code = skuCode(sku);
    saveItem(list, code, "19.99", "2026-01-01T00:00:00Z");
    assertThatThrownBy(() -> saveItem(list, code, "21.99", "2026-01-01T00:00:00Z"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("歧义");
  }

  @Test
  void 读不到价目表时返回404() throws Exception {
    http.perform(
            get("/catalog/price-lists/lists/{id}/items", UUID.randomUUID())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }

  // ================================================================ 工具

  long countItems(UUID priceListId) {
    return tx(
            actorA,
            () ->
                sql.fetchOne(
                        "select count(*) from price_list_item where price_list_id=?", priceListId)
                    .get(0, Long.class))
        .longValue();
  }

  int itemCountOf(UUID priceListId) {
    return tx(
            actorA,
            () ->
                sql.fetchOne("select item_count from price_list where id=?", priceListId)
                    .get(0, Integer.class))
        .intValue();
  }

  /** 造一个真实的 xlsx（表头 + 数据行）。 */
  static byte[] xlsx(List<List<String>> rows) throws Exception {
    try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("价目项");
      for (int r = 0; r < rows.size(); r++) {
        Row row = sheet.createRow(r);
        for (int c = 0; c < rows.get(r).size(); c++) {
          row.createCell(c).setCellValue(rows.get(r).get(c));
        }
      }
      workbook.write(out);
      return out.toByteArray();
    }
  }
}
