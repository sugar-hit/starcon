package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.1.2.1 SKU 列表：分页、多维筛选、批量操作、导出、未接入投影的处置，以及租户隔离三断言。
 * 真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SkuListIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("spu", "sku", "attribute", "research", "compliance", "assets")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "catalog." + p + "." + op))
          .toList();

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

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
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
            byte[] body =
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer reader",
          actor(
              user,
              a,
              null,
              List.of(
                  grant(
                      "ALL",
                      List.of("catalog.spu.read", "catalog.sku.read", "catalog.attribute.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
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
    // 单连接池：连接归还后复用，才能真正证明不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired CatalogService catalog;
  @Autowired SkuListService skus;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired PlatformTransactionManager manager;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actor;

  @BeforeEach
  void setup() {
    actor = actors.get("Bearer tenant-a");
  }

  AccessService.Access actor(UUID tenant) {
    return actors.get("Bearer tenant-a").tenantId().equals(tenant)
        ? actor
        : actors.get("Bearer tenant-b");
  }

  <T> T call(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  <T> T tx(UUID tenant, java.util.function.Supplier<T> work) {
    return access.run(actor(tenant), "catalog.sku.read", work);
  }

  static SkuListQuery query(Object... kv) {
    var map = new HashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
    return new SkuListQuery(
        (UUID) map.get("spuId"),
        (String) map.get("category"),
        (List<String>) map.get("statuses"),
        (String) map.get("keyword"),
        (String) map.get("barcode"),
        (String) map.get("spec"),
        (java.math.BigDecimal) map.get("onHandMin"),
        (java.math.BigDecimal) map.get("onHandMax"),
        (java.math.BigDecimal) map.get("priceMin"),
        (java.math.BigDecimal) map.get("priceMax"),
        (Boolean) map.get("outOfStock"),
        (Boolean) map.get("onSale"),
        (String) map.get("platform"),
        (String) map.getOrDefault("sort", "updated_at"),
        (String) map.getOrDefault("order", "desc"),
        (int) map.getOrDefault("page", 0),
        (int) map.getOrDefault("size", 50));
  }

  UUID product(String code) {
    return call(
        actor,
        "catalog.spu.create",
        () ->
            catalog.saveSpu(
                actor, null, new SpuInput(code, "SKU 列表测试商品", "描述", "品牌", "3C 配件", "DRAFT", 0)));
  }

  /**
   * 建一个带规格轴（颜色）的 SPU 与若干 SKU。
   *
   * <p>规格必须走真实的 attribute_definition / attribute_value / attribute_value_item，
   * 列表的规格展示是从这里解析的，不存在任何冗余字符串列。
   */
  record Fixture(UUID spuId, List<UUID> skuIds, List<UUID> axisSkuIds) {}

  Fixture fixture(String prefix, String category, int skus) {
    return fixture(prefix, category, skus, "ACTIVE");
  }

  Fixture fixture(String prefix, String category, int skus, String status) {
    UUID p =
        call(
            actor,
            "catalog.spu.create",
            () ->
                catalog.saveSpu(
                    actor,
                    null,
                    new SpuInput(
                        prefix, "SKU 列表测试商品", "描述", "品牌", category, "DRAFT", 0)));
    // 规格轴：OPTION / SKU_ONLY / 必填单选，满足 V1 的 CHECK 约束。
    // 选项数要 ≥ 每个 SPU 的 SKU 数：variant_signature 在同一 SPU 内唯一（V1 的唯一约束），
    // 否则第二个同色 SKU 会被真实约束挡住。
    final List<String> colors = List.of("黑色", "白色", "蓝色", "金色", "银色", "灰色");
    call(
        actor,
        "catalog.attribute.create",
        () -> {
          catalog.saveAttribute(
              actor,
              p,
              null,
              new AttributeInput(
                  "COLOR",
                  "颜色",
                  "OPTION",
                  "SKU_ONLY",
                  false,
                  true,
                  true,
                  "",
                  "规格",
                  colors,
                  List.of(),
                  detail(p).product().version()));
          return null;
        });
    var ids = new ArrayList<UUID>();
    var withAxis = new ArrayList<UUID>();
    for (int i = 0; i < skus; i++) {
      // for 变量不是 effectively final，lambda 里要用不可变副本。
      final int index = i;
      var attribute =
          call(actor, "catalog.spu.read", () -> catalog.attributes(p)).stream()
              .filter(x -> x.variantAxis())
              .findFirst()
              .orElseThrow();
      UUID id =
          call(
              actor,
              "catalog.sku.create",
              () ->
                  catalog.saveSku(
                      actor,
                      p,
                      null,
                      new SkuInput(
                          prefix + "-" + index,
                          "SKU " + index,
                          "BC" + index,
                          "件",
                          status,
                          List.of(
                              new ValueInput(
                                  attribute.id(),
                                  "SET",
                                  List.of(colors.get(index % colors.size())))),
                          detail(p).product().version())));
      ids.add(id);
      withAxis.add(id);
    }
    return new Fixture(p, ids, withAxis);
  }

  Detail detail(UUID id) {
    return call(actor, "catalog.spu.read", () -> catalog.detail(id));
  }

  String skuStatus(UUID sku) {
    return tx(
        a,
        () -> sql.fetchOne("select status from sku where id=?", sku).get("status", String.class));
  }

  // ------------------------------------------------------------ 分页与排序

  @Test
  void paginationIsServerSideAndStable() {
    var f = fixture("PAGE", "3C 配件", 5);
    // 只按 SPU 过滤，避免同库内其他用例的行混进来。
    var first = call(actor, "catalog.sku.read", () -> skus.page(query("spuId", f.spuId(), "size", 2)));
    assertThat(first.total()).isEqualTo(5);
    assertThat(first.items()).hasSize(2);
    assertThat(first.size()).isEqualTo(2);

    // 三页拼起来正好是 5 行，且不重不漏 —— 真分页，不是内存切片的假象。
    var seen = new ArrayList<UUID>();
    for (int p = 0; p < 3; p++) {
      final int index = p;
      var page =
          call(
              actor,
              "catalog.sku.read",
              () -> skus.page(query("spuId", f.spuId(), "size", 2, "page", index)));
      page.items().forEach(r -> seen.add(r.id()));
    }
    assertThat(seen).hasSize(5).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(f.skuIds());
    // 超出范围返回空页而不是报错或重复最后一行。
    var beyond =
        call(actor, "catalog.sku.read", () -> skus.page(query("spuId", f.spuId(), "size", 2, "page", 9)));
    assertThat(beyond.items()).isEmpty();
    assertThat(beyond.total()).isEqualTo(5);
  }

  @Test
  void sortingIsWhitelistedAndAlwaysBreaksTiesById() {
    var f = fixture("SORT", "3C 配件", 4);
    var asc = call(actor, "catalog.sku.read", () -> skus.page(query("spuId", f.spuId(), "sort", "code", "order", "asc")));
    var codes = asc.items().stream().map(SkuListRow::code).toList();
    assertThat(codes).isSorted();
    // 未登记的排序列回落到默认列，而不是拼进 SQL。
    var bogus = call(actor, "catalog.sku.read", () -> skus.page(query("spuId", f.spuId(), "sort", "1;drop table sku")));
    assertThat(bogus.total()).isEqualTo(4);
  }

  // ------------------------------------------------------------ 多维筛选

  @Test
  void multiDimensionalFiltersAllWork() {
    var electronics = fixture("FILTER-A", "3C 配件", 3);
    var home = fixture("FILTER-B", "家居", 2);

    var bySpu = call(actor, "catalog.sku.read", () -> skus.page(query("spuId", electronics.spuId())));
    assertThat(bySpu.items()).hasSize(3);

    var byCategory = call(actor, "catalog.sku.read", () -> skus.page(query("category", "家居")));
    assertThat(byCategory.items()).extracting(SkuListRow::spuId).containsOnly(home.spuId());

    var byStatus = call(actor, "catalog.spu.read", () -> skus.page(query("statuses", List.of("ACTIVE"), "spuId", electronics.spuId())));
    assertThat(byStatus.items()).hasSize(3);

    var byKeyword =
        call(actor, "catalog.sku.read", () -> skus.page(query("spuId", electronics.spuId(), "keyword", "FILTER-A-1")));
    assertThat(byKeyword.items()).hasSize(1);

    var byBarcode = call(actor, "catalog.sku.read", () -> skus.page(query("barcode", "BC0")));
    assertThat(byBarcode.items()).extracting(SkuListRow::id).contains(electronics.skuIds().getFirst());

    // 规格关键字走 variant_signature（由真实规格轴拼出），不是某个冗余列。
    var bySpec = call(actor, "catalog.sku.read", () -> skus.page(query("spuId", electronics.spuId(), "spec", "黑色")));
    assertThat(bySpec.items()).extracting(SkuListRow::id).containsExactly(electronics.skuIds().getFirst());
    var bySpecMiss =
        call(actor, "catalog.sku.read", () -> skus.page(query("spuId", electronics.spuId(), "spec", "不存在色")));
    assertThat(bySpecMiss.items()).isEmpty();

    // 多个条件是「与」关系。
    var combined =
        call(actor, "catalog.sku.read", () -> skus.page(query("spuId", electronics.spuId(), "category", "家居")));
    assertThat(combined.items()).isEmpty();

    // LIKE 通配符按字面量处理，用户输入的 % 不能把条件变成全表。
    var literal = call(actor, "catalog.sku.read", () -> skus.page(query("spuId", electronics.spuId(), "keyword", "%")));
    assertThat(literal.items()).isEmpty();
  }

  @Test
  void invalidStatusIsRejectedInsteadOfReturningEverything() {
    assertThatThrownBy(
            () -> call(actor, "catalog.sku.read", () -> skus.page(query("statuses", List.of("ON_SALE")))))
        .hasMessageContaining("无效状态");
  }

  // ------------------------------------------------------------ 未接入投影

  @Test
  void unwiredProjectionsAreReportedNotFaked() {
    var f = fixture("PROJ", "3C 配件", 2);
    var page = call(actor, "catalog.sku.read", () -> skus.page(query("spuId", f.spuId())));
    for (var row : page.items()) {
      // 每一格投影都显式带上可用性；未接入时 value 必须是 null，不能是 0。
      assertThat(row.projections()).hasSize(SkuField.values().length);
      assertThat(row.projections()).allMatch(c -> !c.available());
      assertThat(row.projections()).allMatch(c -> c.value() == null);
      assertThat(row.projections()).allMatch(c -> c.note().contains("上游投影未接入"));
      assertThat(row.projections()).extracting(SkuProjectionCell::source)
          .contains("07 库存", "06 采购", "05 定价", "04 刊登");
    }

    // 能力声明逐字段说明归属服务，前端据此禁用控件。
    var capabilities = call(actor, "catalog.sku.read", () -> skus.capabilities());
    assertThat(capabilities.fields()).hasSize(SkuField.values().length);
    assertThat(capabilities.fields())
        .allMatch(x -> x.note().contains("上游投影未接入"));
    assertThat(capabilities.fields()).extracting(SkuFieldInfo::code)
        .containsExactlyInAnyOrder(
            "ON_HAND", "IN_TRANSIT", "AVAILABLE_QTY", "UNIT_COST", "PURCHASE_PRICE", "SALE_PRICE", "LISTED_PLATFORMS");
  }

  @Test
  void filtersDependingOnUnwiredProjectionsAreRejectedWithAReason() {
    var f = fixture("REJECT", "3C 配件", 2);
    // 库存区间 → 07 未接入
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.sku.read",
                    () -> skus.page(query("spuId", f.spuId(), "onHandMin", new java.math.BigDecimal("1")))))
        .hasMessageContaining("尚未接入的上游投影")
        .hasMessageContaining("07 库存");
    // 价格区间 → 05 未接入
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.sku.read",
                    () -> skus.page(query("spuId", f.spuId(), "priceMin", new java.math.BigDecimal("10")))))
        .hasMessageContaining("05 定价");
    // 是否缺货 → 07 未接入
    assertThatThrownBy(
            () -> call(actor, "catalog.sku.read", () -> skus.page(query("outOfStock", Boolean.TRUE))))
        .hasMessageContaining("尚未接入的上游投影");
    // 是否在售 / 平台 → 04 未接入
    assertThatThrownBy(
            () -> call(actor, "catalog.sku.read", () -> skus.page(query("onSale", Boolean.FALSE))))
        .hasMessageContaining("04 刊登");
    assertThatThrownBy(
            () -> call(actor, "catalog.sku.read", () -> skus.page(query("platform", "amazon"))))
        .hasMessageContaining("04 刊登");
    // 关键：拒绝发生在筛选之前，没有静默退化成"返回全部 / 返回空"。
    assertThatThrownBy(
            () -> call(actor, "catalog.sku.read", () -> skus.page(query("outOfStock", Boolean.TRUE))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value()).isEqualTo(422));
  }

  // ------------------------------------------------------------ 批量操作

  @Test
  void batchStopSellingChangesStatusAndRecordsHistory() {
    var f = fixture("BATCH-STOP", "3C 配件", 3);
    var result =
        call(
            actor,
            "catalog.sku.update",
            () ->
                skus.batch(
                    actor,
                    new SkuBatchInput("STOP_SELLING", f.skuIds(), "INACTIVE", "供应商停供", null, false)));
    assertThat(result.appliedCount()).isEqualTo(3);
    assertThat(result.skippedCount()).isZero();
    assertThat(result.targetStatus()).isEqualTo("INACTIVE");
    for (UUID id : f.skuIds()) assertThat(skuStatus(id)).isEqualTo("INACTIVE");

    // 业务时间与系统时间分列存储，且都不为空。
    var row =
        tx(
            a,
            () ->
                sql.fetchOne(
                    "select * from sku_status_event where tenant_id=? and sku_id=?", a, f.skuIds().getFirst()));
    assertThat(row.get("from_status", String.class)).isEqualTo("ACTIVE");
    assertThat(row.get("to_status", String.class)).isEqualTo("INACTIVE");
    assertThat(row.get("source", String.class)).isEqualTo("BATCH");
    assertThat(row.get("actor_id", UUID.class)).isEqualTo(user);
    assertThat(row.get("occurred_at", java.time.OffsetDateTime.class)).isNotNull();
    assertThat(row.get("created_at", java.time.OffsetDateTime.class)).isNotNull();
    // 下游事件已发布，是否下架平台商品由 04 决定。
    assertThat(
            tx(
                a,
                () ->
                    sql.fetch(
                        "select * from outbox_event where aggregate_id=? and event_type='SkuBatchStatusChanged'",
                        f.skuIds().getFirst())))
        .isNotEmpty();
  }

  @Test
  void batchSetStatusValidatesTargetAndKeepsDraftInvariant() {
    var f = fixture("BATCH-STATUS", "3C 配件", 2, "ACTIVE");
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.sku.update",
                    () ->
                        skus.batch(
                            actor,
                            new SkuBatchInput("SET_STATUS", f.skuIds(), "DRAFT", "回退", null, false))))
        .hasMessageContaining("只能改为");
    // 原因不能为空。
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.sku.update",
                    () ->
                        skus.batch(
                            actor, new SkuBatchInput("SET_STATUS", f.skuIds(), "ACTIVE", "  ", null, false))))
        .hasMessageContaining("必须填写原因");
    // 重复 SKU 与空选择都被拒绝。
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.sku.update",
                    () ->
                        skus.batch(
                            actor,
                            new SkuBatchInput(
                                "SET_STATUS",
                                List.of(f.skuIds().getFirst(), f.skuIds().getFirst()),
                                "INACTIVE",
                                "重复",
                                null,
                                false))))
        .hasMessageContaining("不能重复");
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.sku.update",
                    () ->
                        skus.batch(
                            actor, new SkuBatchInput("SET_STATUS", List.of(), "INACTIVE", "空", null, false))))
        .hasMessageContaining("请先选择");

    // 归档可用。
    var archived =
        call(
            actor,
            "catalog.sku.update",
            () ->
                skus.batch(
                    actor,
                    new SkuBatchInput("SET_STATUS", f.skuIds(), "ARCHIVED", "供应商停产", null, false)));
    assertThat(archived.appliedCount()).isEqualTo(2);
    for (UUID id : f.skuIds()) assertThat(skuStatus(id)).isEqualTo("ARCHIVED");
  }

  @Test
  void batchDryRunPreviewsWithoutChangingAnything() {
    var f = fixture("BATCH-DRY", "3C 配件", 3);
    var preview =
        call(
            actor,
            "catalog.sku.update",
            () ->
                skus.batch(
                    actor,
                    new SkuBatchInput("STOP_SELLING", f.skuIds(), "INACTIVE", "预览停售", null, true)));
    assertThat(preview.dryRun()).isTrue();
    assertThat(preview.appliedCount()).isEqualTo(3);
    // 预览不能动状态，也不能写这些 SKU 的状态历史。
    for (UUID id : f.skuIds()) assertThat(skuStatus(id)).isEqualTo("ACTIVE");
    assertThat(tx(a, () -> sql.fetch("select * from sku_status_event where tenant_id=? and sku_id=?", a, f.skuIds().getFirst())))
        .isEmpty();
    // 但预览本身留痕，便于事后核对"当时打算做什么"。
    var batchRow =
        tx(
            a,
            () ->
                sql.fetchOne(
                    "select * from sku_batch_operation where tenant_id=? and id=?", a, preview.batchId()));
    assertThat(batchRow).isNotNull();
    assertThat(batchRow.get("status", String.class)).isEqualTo("PREVIEW");
    assertThat(batchRow.get("occurred_at", java.time.OffsetDateTime.class)).isNotNull();
    assertThat(batchRow.get("created_at", java.time.OffsetDateTime.class)).isNotNull();
  }

  @Test
  void batchSkipsAlreadyInTargetStateWithAReason() {
    var f = fixture("BATCH-SKIP", "3C 配件", 2);
    var result =
        call(
            actor,
            "catalog.sku.update",
            () ->
                skus.batch(
                    actor,
                    new SkuBatchInput(
                        "SET_STATUS",
                        List.of(f.skuIds().getFirst()),
                        "ACTIVE",
                        "已经启用",
                        null,
                        false)));
    assertThat(result.appliedCount()).isZero();
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.items().getFirst().reason()).isEqualTo("已处于目标状态");
  }

  @Test
  void listingDependentBatchActionsAreRejectedNotFaked() {
    var f = fixture("BATCH-04", "3C 配件", 1);
    // 批量分配与批量生成刊登依赖 04 刊登：显式拒绝，并说清归属，不返回"成功但什么都没做"。
    for (String action : List.of("ASSIGN", "GENERATE_LISTING")) {
      assertThatThrownBy(
              () ->
                  call(
                      actor,
                      "catalog.sku.update",
                      () ->
                          skus.batch(
                              actor,
                              new SkuBatchInput(
                                  action, f.skuIds(), "INACTIVE", "试试", null, false))))
          .hasMessageContaining("04 刊登")
          .hasMessageContaining("尚未开发");
      assertThatThrownBy(
              () ->
                  call(
                      actor,
                      "catalog.sku.update",
                      () ->
                          skus.batch(
                              actor,
                              new SkuBatchInput(
                                  action, f.skuIds(), "INACTIVE", "试试", null, false))))
          .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
          .satisfies(
              e ->
                  assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
                      .isEqualTo(422));
    }
    // 状态没被动过。
    for (UUID id : f.skuIds()) assertThat(skuStatus(id)).isEqualTo("ACTIVE");
  }

  // ------------------------------------------------------------ 导出

  @Test
  void csvExportStreamsInChunksAndUsesTheSameFilters() {
    var f = fixture("EXPORT", "3C 配件", 3);
    var chunks = new ArrayList<String>();
    call(
        actor,
        "catalog.sku.read",
        () -> {
          skus.streamCsv(actor, query("spuId", f.spuId()), chunks::add);
          return null;
        });
    var csv = String.join("", chunks);
    var lines = csv.lines().toList();
    assertThat(lines.getFirst()).contains("SKU编码", "在仓数量", "售价", "在售平台");
    // 表头 + 3 行；筛选条件与列表一致。
    assertThat(lines).hasSize(4);
    for (UUID id : f.skuIds()) assertThat(csv).contains(id == null ? "" : codeOf(id));
    // 未接入的投影列写"未接入"，不是 0。
    assertThat(csv).contains("未接入");
    assertThat(csv).doesNotContain(",0,");
  }

  @Test
  void csvExportIsChunkedSoLargeSetsDoNotAccumulateInMemory() {
    var f = fixture("EXPORT-CHUNK", "3C 配件", 3);
    var chunks = new ArrayList<String>();
    call(
        actor,
        "catalog.sku.read",
        () -> {
          skus.streamCsv(actor, query("spuId", f.spuId()), chunks::add);
          return null;
        });
    // 表头单独一块，数据按块写出——分批读取的证据。
    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks.getFirst().lines().count()).isEqualTo(1);
  }

  @Test
  void csvExportEscapesFormulaInjection() {
    var f = fixture("EXPORT-ESC", "3C 配件", 1);
    var chunks = new ArrayList<String>();
    call(
        actor,
        "catalog.sku.read",
        () -> {
          skus.streamCsv(actor, query("spuId", f.spuId()), chunks::add);
          return null;
        });
    var csv = String.join("", chunks);
    // 所有字段都被引号包住（RFC 4180），表头前的 BOM 也保留。
    assertThat(csv).startsWith("﻿");
    String dataLine = csv.lines().skip(1).findFirst().orElseThrow();
    for (String cell : dataLine.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)"))
      assertThat(cell).startsWith("\"").endsWith("\"");
  }

  String codeOf(UUID sku) {
    return tx(a, () -> sql.fetchOne("select code from sku where id=?", sku).get("code", String.class));
  }

  // ------------------------------------------------------------ 多租户三断言

  @Test
  void skuListIsTenantIsolatedAndHeaderForgeryIsIgnored() throws Exception {
    var mine = fixture("ISO-MINE", "3C 配件", 2);
    var theirs =
        call(
            actor(b),
            "catalog.sku.create",
            () ->
                catalog.saveSpu(
                    actor(b),
                    null,
                    new SpuInput(
                        "ISO-THEIRS-" + UUID.randomUUID(), "B 租户商品", "", "品牌", "家居", "DRAFT", 0)));

    // 1) 两租户互不可见
    var mineView = tx(a, () -> skus.page(query("spuId", mine.spuId())));
    assertThat(mineView.items()).hasSize(2);
    var theirsView = tx(b, () -> skus.page(query("spuId", theirs)));
    assertThat(theirsView.items()).isEmpty();
    // 拿别人的 SPU ID 去查，结果是空页而不是报错或数据。
    assertThat(tx(b, () -> skus.page(query("spuId", mine.spuId()))).items()).isEmpty();
    // 拿别人的 SKU ID 做批量操作：跳过且不泄露存在性。
    var cross =
        tx(
            b,
            () ->
                call(
                    actor(b),
                    "catalog.sku.update",
                    () ->
                        skus.batch(
                            actor(b),
                            new SkuBatchInput(
                                "STOP_SELLING", mine.skuIds(), "INACTIVE", "越权", null, false))));
    assertThat(cross.appliedCount()).isZero();
    for (UUID id : mine.skuIds()) assertThat(skuStatus(id)).isEqualTo("ACTIVE");

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(
            get("/catalog/skus")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
    http.perform(
            get("/catalog/skus?spuId=" + mine.spuId())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
    http.perform(
            get("/catalog/skus?spuId=" + mine.spuId())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2));
    // 伪造头也不能借道写接口。
    http.perform(
            post("/catalog/skus/batch")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content(
                    "{\"action\":\"STOP_SELLING\",\"skuIds\":[\""
                        + mine.skuIds().getFirst()
                        + "\"],\"targetStatus\":\"INACTIVE\",\"reason\":\"伪造\",\"dryRun\":false}"))
        .andExpect(status().isOk());
    for (UUID id : mine.skuIds()) assertThat(skuStatus(id)).isEqualTo("ACTIVE");

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(a, () -> skus.page(query("spuId", mine.spuId()))).items()).hasSize(2);
      assertThat(tx(b, () -> skus.page(query("spuId", mine.spuId()))).items()).isEmpty();
      assertThat(tx(b, () -> skus.page(query("spuId", theirs))).items()).isEmpty();
    }
  }

  @Test
  void everyNewTableHasForcedRls() {
    var weak =
        sql.fetch(
            "select relname from pg_class join pg_namespace n on n.oid=relnamespace"
                + " where n.nspname='public' and relkind='r'"
                + " and relname in ('sku','sku_status_event','sku_batch_operation','sku_batch_operation_item')"
                + " and (not relrowsecurity or not relforcerowsecurity)");
    assertThat(weak).isEmpty();
    var role = sql.fetchOne("select rolsuper,rolbypassrls from pg_roles where rolname=current_user");
    assertThat(role.get("rolsuper", Boolean.class)).isFalse();
    assertThat(role.get("rolbypassrls", Boolean.class)).isFalse();
    // sku 表必须仍带 tenant_id NOT NULL。
    var column =
        sql.fetchOne(
            "select is_nullable from information_schema.columns where table_name='sku' and column_name='tenant_id'");
    assertThat(column.get("is_nullable", String.class)).isEqualTo("NO");
  }

  // ------------------------------------------------------------ HTTP 契约

  @Test
  void httpEndpointsExposeListCapabilitiesAndBatch() throws Exception {
    var f = fixture("HTTP", "3C 配件", 2);
    http.perform(get("/catalog/skus?spuId=" + f.spuId()).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.items[0].projections[0].available").value(false))
        .andExpect(jsonPath("$.items[0].specs").isArray());
    http.perform(get("/catalog/skus/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fields[0].note").exists());
    // 未接入投影的筛选条件通过 HTTP 返回明确的 422。
    http.perform(get("/catalog/skus?outOfStock=true").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isUnprocessableEntity());
    // 依赖 04 的批量动作同样 422。
    http.perform(
            post("/catalog/skus/batch")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"action\":\"GENERATE_LISTING\",\"skuIds\":[\""
                        + f.skuIds().getFirst()
                        + "\"],\"targetStatus\":\"INACTIVE\",\"reason\":\"x\",\"dryRun\":false}"))
        .andExpect(status().isUnprocessableEntity());
    // 导出返回 CSV 附件。StreamingResponseBody 是异步写出的，
    // 必须先 getAsyncResult() 等它跑完，否则读到的响应体是空的。
    var result =
        http.perform(
                get("/catalog/skus/export?spuId=" + f.spuId())
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/csv; charset=UTF-8"))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("catalog-skus-")))
            .andReturn();
    result.getAsyncResult();
    var body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    assertThat(body).contains("SKU编码").contains("未接入");
    // 只读角色不能批量改状态。
    http.perform(
            post("/catalog/skus/batch")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    "{\"action\":\"STOP_SELLING\",\"skuIds\":[\""
                        + f.skuIds().getFirst()
                        + "\"],\"targetStatus\":\"INACTIVE\",\"reason\":\"x\",\"dryRun\":false}"))
        .andExpect(status().isForbidden());
  }
}
