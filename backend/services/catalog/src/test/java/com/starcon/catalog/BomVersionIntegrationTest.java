package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.5.2.1 BOM 版本管理：BOM/版本 CRUD、草稿→发布状态机、发布后不可变、生效区间冲突裁决、
 * 当前生效版本查询、单位用量与损耗率、单层需求量、版本复制、倒排查询，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS：BOM、版本、组件全部真的落库，
 * 「已发布不可改」「区间重叠拒绝」都是<b>绕过应用层直接写 SQL 也会被拦</b>的事实。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的六个断言：</b>
 *
 * <ol>
 *   <li>{@link #publishedVersionIsImmutableEvenThroughDirectSql()}：直连 SQL 也改不出改名、
 *       改版本号、撤回发布、删版本、给已发布版本加组件、<b>改损耗率</b> —— 绕过应用层照样被 V25 拦。
 *   <li>{@link #effectiveIntervalRulesArePinnedDown()}：R1 半开区间 / R2 重叠拒绝 /
 *       R3 只有已发布参与 / R4 当前生效唯一，逐条钉死。
 *   <li>{@link #publishedVersionCanOnlyBeClosedForward()}：R5 向前关闭是已发布版本上唯一合法的写操作。
 *   <li>{@link #scrapRateIsStoredAsAPercentageAndSurvivesTheRoundTrip()}：R6 百分数语义
 *       + {@code numeric(7,4)} 精度真的存住了，不是 {@code 0.05}。
 *   <li>{@link #singleLevelRequirementAppliesScrapWithoutRounding()}：R7 需求量公式、
 *       6 位小数、<b>不取整</b>、单层不递归。
 *   <li>{@link #tenantIsolationHoldsAcrossReadWriteAndPoolReuse()}：租户隔离三断言。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class BomVersionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(), grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = conn.createStatement()) {
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
            byte[] body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions()))));
      actors.put(
          "Bearer reader", actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static List<String> allPermissions() {
    return java.util.stream.Stream.of("spu", "sku", "assets", "attribute")
        .flatMap(p -> java.util.stream.Stream.of("read", "create", "update", "delete")
            .map(op -> "catalog." + p + "." + op))
        .toList();
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
    // 只有复用同一条连接，才能真正证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired BomVersionService boms;
  @Autowired CatalogService catalog;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actor;
  AccessService.Access actorB;

  @BeforeEach
  void setup() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  /** 写事务里跑一个没有返回值的动作（删除等）。 */
  void writeVoid(AccessService.Access who, Runnable work) {
    access.run(who, "catalog.spu.update", () -> {
      work.run();
      return null;
    });
  }

  // ------------------------------------------------------------ 夹具

  String unique(String hint) {
    return hint + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  UUID spu(AccessService.Access who, String code) {
    return write(
        who,
        () -> catalog.saveSpu(
            who, null, new SpuInput(code, "SPU " + code, "", "Brand", "Category", "DRAFT", 0)));
  }

  UUID sku(AccessService.Access who, UUID spuId, String code, long spuVersion) {
    return write(
        who,
        () -> catalog.saveSku(
            who, spuId, null, new SkuInput(code, "SKU " + code, unique("BC"), "PCS", "ACTIVE", List.of(), spuVersion)));
  }

  /**
   * 建 3 个 SKU（1 个成品 + 2 个组件）并挂到一个 BOM 上。
   *
   * <p>两个组件 SKU 挂在<b>不同</b>的 SPU 上：同一个 SPU 下两个无属性 SKU 的
   * variant_signature 相同，会撞 (tenant_id, spu_id, variant_signature) 唯一键。
   */
  record Fixture(UUID bomId, UUID outputSkuId, UUID skuA, UUID skuB) {}

  Fixture fixture(AccessService.Access who, String hint) {
    UUID outputSku = sku(who, spu(who, unique("SPU")), unique("FG"), 0);
    UUID skuA = sku(who, spu(who, unique("SPU")), unique("CMP"), 0);
    UUID skuB = sku(who, spu(who, unique("SPU")), unique("CMP"), 0);
    UUID bomId =
        write(who, () -> boms.saveBom(who, null,
            new BomInput(unique("BOM"), outputSku, "生产型 BOM", 0))).id();
    return new Fixture(bomId, outputSku, skuA, skuB);
  }

  /** 建一个草稿版本并填两个组件：用量 2（损耗 5%）与 0.5（无损耗）。 */
  UUID draftVersion(AccessService.Access who, Fixture f, String versionNo, Instant from) {
    UUID versionId =
        write(who, () -> boms.createVersion(who, f.bomId(),
                new BomVersionInput(versionNo, "BOM " + versionNo, from.toString(), null, "", 0)))
            .id();
    write(who, () -> boms.replaceComponents(who, versionId,
        new BomComponentsInput(
            List.of(
                new BomComponentInput(f.skuA(), new BigDecimal("2"), new BigDecimal("5"), "主板", ""),
                new BomComponentInput(f.skuB(), new BigDecimal("0.5"), BigDecimal.ZERO, "包材", "")),
            0)));
    return versionId;
  }

  BomVersionRow publish(AccessService.Access who, UUID versionId) {
    return write(who, () -> boms.publishVersion(who, versionId));
  }

  /** 建一个**闭合**生效区间 [from, to) 的草稿版本（真实运营里 v1 通常先开放、发布 v2 前再向前关闭）。 */
  UUID closedVersion(AccessService.Access who, Fixture f, String versionNo, Instant from, Instant to) {
    UUID versionId =
        write(who, () -> boms.createVersion(who, f.bomId(),
                new BomVersionInput(versionNo, "BOM " + versionNo, from.toString(), to.toString(), "", 0)))
            .id();
    write(who, () -> boms.replaceComponents(who, versionId,
        new BomComponentsInput(
            List.of(
                new BomComponentInput(f.skuA(), new BigDecimal("2"), new BigDecimal("5"), "主板", ""),
                new BomComponentInput(f.skuB(), new BigDecimal("0.5"), BigDecimal.ZERO, "包材", "")),
            0)));
    return versionId;
  }

  // ================================================================ BOM 档案

  @Test
  void bomRequiresAnOutputSkuAndKeepsBothIdentifiersImmutable() {
    var f = fixture(actor, "BOM");
    // 成品 SKU 必填：没有成品就没有物料清单（V25「差异 1」）。
    assertThatThrownBy(() -> write(actor, () -> boms.saveBom(actor, null,
        new BomInput(unique("BOM"), null, "", 0))))
        .hasMessageContaining("不能为空");

    var created = tx(actor, () -> boms.bom(f.bomId())).bom();
    assertThat(created.code()).isEqualTo(created.code().toUpperCase(Locale.ROOT));
    assertThat(created.outputSkuId()).isEqualTo(f.outputSkuId());
    assertThat(created.outputSkuCode()).isNotBlank();
    assertThat(created.outputSkuName()).isNotBlank();

    // 编码建后不可改：它是 06 采购建议与 08 加工单里的稳定引用。
    assertThatThrownBy(() -> write(actor, () -> boms.saveBom(actor, f.bomId(),
        new BomInput(unique("BOM"), f.outputSkuId(), "改编码", created.version()))))
        .hasMessageContaining("不可改");
    // 成品建后不可改：已发布版本算出来的需求量已经按实物领走了。
    UUID otherSku = sku(actor, spu(actor, unique("SPU")), unique("FG"), 0);
    assertThatThrownBy(() -> write(actor, () -> boms.saveBom(actor, f.bomId(),
        new BomInput(created.code(), otherSku, "换成品", created.version()))))
        .hasMessageContaining("成品建后不可改");
    // 备注可以改。
    var updated = write(actor, () -> boms.saveBom(actor, f.bomId(),
        new BomInput(created.code(), f.outputSkuId(), "新备注", created.version())));
    assertThat(updated.note()).isEqualTo("新备注");
  }

  @Test
  void outputSkuMustBelongToTheSameTenant() {
    var f = fixture(actor, "BOM");
    // B 租户的 SKU 不能被 A 租户的 BOM 指向。
    UUID theirSku = sku(actorB, spu(actorB, unique("SPU")), unique("FG"), 0);
    assertThatThrownBy(() -> write(actor, () -> boms.saveBom(actor, null,
        new BomInput(unique("BOM"), theirSku, "", 0))))
        .hasMessageContaining("在本租户不存在");
  }

  @Test
  void duplicateBomCodeIsRejectedWithinTheTenant() {
    String code = unique("BOM");
    UUID output = sku(actor, spu(actor, unique("SPU")), unique("FG"), 0);
    write(actor, () -> boms.saveBom(actor, null, new BomInput(code, output, "", 0)));
    assertThatThrownBy(() -> write(actor, () -> boms.saveBom(actor, null,
        new BomInput(code, output, "", 0))))
        .hasMessageContaining("已经存在");
  }

  @Test
  void bomArchiveIsNotDeleteAndPublishedVersionBlocksDelete() {
    var f = fixture(actor, "BOM");
    var created = tx(actor, () -> boms.bom(f.bomId())).bom();
    assertThat(write(actor, () -> boms.setBomStatus(actor, f.bomId(), "ARCHIVED")).status())
        .isEqualTo("ARCHIVED");
    // 归档后不能新建版本：归档表示「不再新增」，历史版本仍可读。
    assertThatThrownBy(() -> write(actor, () -> boms.createVersion(actor, f.bomId(),
        new BomVersionInput("v1", "BOM v1", Instant.now().toString(), null, "", 0))))
        .hasMessageContaining("已归档");
    assertThat(write(actor, () -> boms.setBomStatus(actor, f.bomId(), "ACTIVE")).status())
        .isEqualTo("ACTIVE");

    UUID versionId = draftVersion(actor, f, "v1", Instant.now().minus(10, ChronoUnit.DAYS));
    // 含草稿也不能删：草稿是待办但仍记录了规划。
    assertThatThrownBy(() -> writeVoid(actor, () ->
        boms.deleteBom(actor, f.bomId(), tx(actor, () -> boms.bom(f.bomId())).bom().version())))
        .hasMessageContaining("草稿版本");
    publish(actor, versionId);
    assertThatThrownBy(() -> writeVoid(actor, () ->
        boms.deleteBom(actor, f.bomId(), tx(actor, () -> boms.bom(f.bomId())).bom().version())))
        .hasMessageContaining("已经发布过");
  }

  // ================================================================ 版本与组件

  @Test
  void duplicateVersionNumberIsRejectedWithinTheSameBom() {
    var f = fixture(actor, "BOM");
    Instant from = Instant.now().minus(10, ChronoUnit.DAYS);
    draftVersion(actor, f, "v1", from);
    assertThatThrownBy(() -> write(actor, () -> boms.createVersion(actor, f.bomId(),
        new BomVersionInput("v1", "同名版本", from.toString(), null, "", 0))))
        .hasMessageContaining("同 BOM 同版本号");
  }

  @Test
  void componentsAreReplacedWholesaleAndKeepNumericQuantities() {
    var f = fixture(actor, "BOM");
    UUID versionId = draftVersion(actor, f, "v1", Instant.now().minus(10, ChronoUnit.DAYS));
    var detail = tx(actor, () -> boms.version(versionId));

    // 用量 0.5 与损耗 5 都要真的存住（NUMERIC(19,6) / NUMERIC(7,4)）。
    var main = detail.components().stream()
        .filter(c -> c.skuId().equals(f.skuA())).findFirst().orElseThrow();
    assertThat(main.unitQuantity()).isEqualByComparingTo("2.000000");
    assertThat(main.scrapRate()).isEqualByComparingTo("5.0000");
    assertThat(detail.totalUnitQuantity()).isEqualByComparingTo("2.500000");

    // 整体替换：保存后清单就是提交的那份，不会剩幽灵组件。
    var after = write(actor, () -> boms.replaceComponents(actor, versionId,
        new BomComponentsInput(
            List.of(new BomComponentInput(f.skuB(), new BigDecimal("0.25"), null, "包材", "")),
            tx(actor, () -> boms.version(versionId)).version().version())));
    assertThat(after.components()).hasSize(1);
    assertThat(after.components().get(0).skuId()).isEqualTo(f.skuB());
    // 损耗率缺省按 0（无损耗）处理。
    assertThat(after.components().get(0).scrapRate()).isEqualByComparingTo("0");
  }

  @Test
  void componentValidationRejectsBadQuantitiesRatesAndDuplicates() {
    var f = fixture(actor, "BOM");
    UUID versionId = draftVersion(actor, f, "v1", Instant.now().minus(10, ChronoUnit.DAYS));
    long v = tx(actor, () -> boms.version(versionId)).version().version();

    // 用量必须 > 0：0 用量的组件是「配了但不领」，组装单会领出一条 0 件。
    assertThatThrownBy(() -> write(actor, () -> boms.replaceComponents(actor, versionId,
        new BomComponentsInput(
            List.of(new BomComponentInput(f.skuA(), BigDecimal.ZERO, BigDecimal.ZERO, "", "")),
            v))))
        .hasMessageContaining("必须大于 0");
    // 损耗率上界 100%。
    assertThatThrownBy(() -> write(actor, () -> boms.replaceComponents(actor, versionId,
        new BomComponentsInput(
            List.of(new BomComponentInput(f.skuA(), BigDecimal.ONE, new BigDecimal("120"), "", "")),
            v))))
        .hasMessageContaining("不能超过 100%");
    // 同一 SKU 两行：「每 1 个成品要多少」只能有一个答案。
    assertThatThrownBy(() -> write(actor, () -> boms.replaceComponents(actor, versionId,
        new BomComponentsInput(
            List.of(
                new BomComponentInput(f.skuA(), BigDecimal.ONE, BigDecimal.ZERO, "", ""),
                new BomComponentInput(f.skuA(), new BigDecimal("2"), BigDecimal.ZERO, "", "")),
            v))))
        .hasMessageContaining("出现了两次");
    // 组件必须指向本租户真实存在的 SKU。
    assertThatThrownBy(() -> write(actor, () -> boms.replaceComponents(actor, versionId,
        new BomComponentsInput(
            List.of(new BomComponentInput(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO, "", "")),
            v))))
        .hasMessageContaining("在本租户不存在");
  }

  @Test
  void publishRequiresAtLeastOneComponent() {
    var f = fixture(actor, "BOM");
    UUID versionId =
        write(actor, () -> boms.createVersion(actor, f.bomId(),
            new BomVersionInput("v1", "空清单", Instant.now().toString(), null, "", 0)))
            .id();
    assertThatThrownBy(() -> publish(actor, versionId))
        .hasMessageContaining("至少要有一个组件");
  }

  @Test
  void draftToPublishedIsOneWayAndCarriesReleaseAudit() {
    var f = fixture(actor, "BOM");
    UUID versionId = draftVersion(actor, f, "v1", Instant.now().minus(10, ChronoUnit.DAYS));
    var published = publish(actor, versionId);
    assertThat(published.status()).isEqualTo("PUBLISHED");
    assertThat(published.statusLabel()).isEqualTo("已发布");
    assertThat(published.publishedBy()).isEqualTo(user);
    assertThat(published.publishedAt()).isNotNull();
    assertThat(published.editable()).isFalse();

    // 撤回发布不是合法动作：08 加工单已经在仓库里按它领过料了，撤不回来。
    assertThatThrownBy(() -> publish(actor, versionId))
        .hasMessageContaining("已经是已发布状态");
    // 服务层也不给撤回入口。
    assertThatThrownBy(() -> write(actor, () -> boms.updateVersion(actor, versionId,
        new BomVersionInput("v2", "改名", published.effectiveFrom(), null, "", published.version()))))
        .hasMessageContaining("已发布，不可修改");
    assertThatThrownBy(() -> writeVoid(actor, () -> boms.deleteVersion(actor, versionId, published.version())))
        .hasMessageContaining("已发布，不可修改");
  }

  @Test
  void publishedVersionRefusesEveryContentEdit() {
    var f = fixture(actor, "BOM");
    UUID versionId = draftVersion(actor, f, "v1", Instant.now().minus(10, ChronoUnit.DAYS));
    var published = publish(actor, versionId);
    Instant from = Instant.parse(published.effectiveFrom());

    assertThatThrownBy(() -> write(actor, () -> boms.updateVersion(actor, versionId,
        new BomVersionInput("v1", "改名", from.toString(), null, "", published.version()))))
        .hasMessageContaining("已发布，不可修改");
    assertThatThrownBy(() -> write(actor, () -> boms.updateVersion(actor, versionId,
        new BomVersionInput("v2", "BOM v1", from.toString(), null, "", published.version()))))
        .hasMessageContaining("已发布，不可修改");
    assertThatThrownBy(() -> write(actor, () -> boms.updateVersion(actor, versionId,
        new BomVersionInput("v1", "BOM v1", from.plus(1, ChronoUnit.DAYS).toString(), null, "", published.version()))))
        .hasMessageContaining("已发布，不可修改");
    // 改组件（增删改）同样被拒：08 领的料是按当时的用量与损耗率算的。
    assertThatThrownBy(() -> write(actor, () -> boms.replaceComponents(actor, versionId,
        new BomComponentsInput(
            List.of(new BomComponentInput(f.skuA(), new BigDecimal("3"), new BigDecimal("9"), "", "")),
            published.version()))))
        .hasMessageContaining("已发布，不可修改");
  }

  @Test
  void publishedVersionIsImmutableEvenThroughDirectSql() {
    var f = fixture(actor, "BOM");
    UUID versionId = draftVersion(actor, f, "v1", Instant.now().minus(10, ChronoUnit.DAYS));
    publish(actor, versionId);
    var expected = tx(actor, () -> boms.version(versionId)).components();

    // 绕过应用层直连 SQL：V25 的触发器是最后一道防线，全部要被拦。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update bom_version set name='改名' where tenant_id=? and id=?", a, versionId)))
        .hasMessageContaining("不能改名");
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update bom_version set version_no='v9' where tenant_id=? and id=?", a, versionId)))
        .hasMessageContaining("不能改版本号");
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update bom_version set effective_from=?::timestamptz where tenant_id=? and id=?",
            java.time.OffsetDateTime.now().plus(1, ChronoUnit.DAYS), a, versionId)))
        .hasMessageContaining("不能改生效起点");
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update bom_version set status='DRAFT',published_by=null,published_at=null"
            + " where tenant_id=? and id=?", a, versionId)))
        .hasMessageContaining("不能撤回发布");
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "delete from bom_version where tenant_id=? and id=?", a, versionId)))
        .hasMessageContaining("不能删除");
    // 组件挂在已发布版本上时不可增删改。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "insert into bom_component(id,tenant_id,version_id,sku_id,unit_quantity,scrap_rate)"
            + " values(?,?,?,?,?,?)",
            UUID.randomUUID(), a, versionId, f.skuB(), BigDecimal.ONE, BigDecimal.ZERO)))
        .hasMessageContaining("组件不可修改");
    // **改损耗率也要被拦** —— 这是 BOM 比套装多出来的一道（08 领的料是按损耗率算的）。
    UUID rowId = expected.get(0).id();
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update bom_component set scrap_rate=99 where tenant_id=? and id=?", a, rowId)))
        .hasMessageContaining("组件不可修改");
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update bom_component set unit_quantity=99 where tenant_id=? and id=?", a, rowId)))
        .hasMessageContaining("组件不可修改");
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "delete from bom_component where tenant_id=? and id=?", a, rowId)))
        .hasMessageContaining("组件不可修改");
  }

  // ================================================================ R1–R5 生效区间

  @Test
  void effectiveIntervalRulesArePinnedDown() {
    var f = fixture(actor, "BOM");
    Instant t0 = Instant.parse("2026-03-01T00:00:00Z");
    Instant t1 = Instant.parse("2026-03-01T06:00:00Z");
    Instant t2 = Instant.parse("2026-03-01T12:00:00Z");
    Instant t3 = Instant.parse("2026-03-01T18:00:00Z");

    // v1 必须是**闭合**区间 [t0,t1)，否则它一直有效，v2 就无处安放。
    // 这正是 R5 存在的理由：真实运营里 v1 是开放区间，v2 发布前要先「向前关闭」v1。
    UUID v1 = closedVersion(actor, f, "v1", t0, t1);
    publish(actor, v1);
    // R3：草稿的区间可以随便重叠 —— 草稿是待办不是事实，发布那一刻才被检查。
    UUID v2Draft = draftVersion(actor, f, "v2-draft", t0);
    assertThat(tx(actor, () -> boms.version(v2Draft)).version().status()).isEqualTo("DRAFT");

    // R1 半开区间：[t0,t1) 与 [t1,t2) 在 t1 处不重叠。
    UUID v2 = write(actor, () -> boms.createVersion(actor, f.bomId(),
        new BomVersionInput("v2", "BOM v2", t1.toString(), t2.toString(), "", 0))).id();
    write(actor, () -> boms.replaceComponents(actor, v2,
        new BomComponentsInput(
            List.of(new BomComponentInput(f.skuA(), BigDecimal.ONE, BigDecimal.ZERO, "", "")), 0)));
    publish(actor, v2);

    // R4 当前生效：t0 命中 v1，t1 命中 v2（终点那一瞬间归后一版），t2 之后谁也不命中。
    assertThat(tx(actor, () -> boms.effectiveVersion(f.bomId(), t0.toString())).versionNo())
        .isEqualTo("v1");
    assertThat(tx(actor, () -> boms.effectiveVersion(f.bomId(), t1.toString())).versionNo())
        .isEqualTo("v2");
    assertThat(tx(actor, () -> boms.effectiveVersion(f.bomId(), t2.toString())).effective()).isFalse();
    assertThat(tx(actor, () -> boms.effectiveVersion(f.bomId(), t3.toString())).effective()).isFalse();

    // R2 重叠一律拒绝，不做裁决：报错要点名与哪一版、哪个区间撞了。
    UUID v3 = write(actor, () -> boms.createVersion(actor, f.bomId(),
        new BomVersionInput("v3", "BOM v3", t1.toString(), t3.toString(), "", 0))).id();
    write(actor, () -> boms.replaceComponents(actor, v3,
        new BomComponentsInput(
            List.of(new BomComponentInput(f.skuA(), BigDecimal.ONE, BigDecimal.ZERO, "", "")), 0)));
    var thrown = org.junit.jupiter.api.Assertions.assertThrows(
        ResponseStatusException.class, () -> publish(actor, v3));
    assertThat(thrown.getMessage()).contains("重叠").contains("v2");
    // 被拒之后仍然是草稿：拒绝发布不能有副作用。
    assertThat(tx(actor, () -> boms.version(v3)).version().status()).isEqualTo("DRAFT");
  }

  @Test
  void publishedVersionCanOnlyBeClosedForward() {
    var f = fixture(actor, "BOM");
    UUID v1 = draftVersion(actor, f, "v1", Instant.parse("2026-03-01T00:00:00Z"));
    publish(actor, v1);
    var published = tx(actor, () -> boms.version(v1)).version();

    // 草稿不需要「关闭生效区间」：它还没生效，谈不上停用。
    var f2 = fixture(actor, "BOM");
    UUID draft = draftVersion(actor, f2, "v1", Instant.parse("2026-03-01T00:00:00Z"));
    assertThatThrownBy(() -> write(actor, () ->
        boms.closeVersion(actor, draft, Instant.now().plus(1, ChronoUnit.DAYS).toString())))
        .hasMessageContaining("只有已发布的版本需要关闭生效区间");

    // 不能落进过去：那是追溯改写「那时该按哪一版算料」。
    assertThatThrownBy(() -> write(actor, () ->
        boms.closeVersion(actor, v1, Instant.now().minus(1, ChronoUnit.DAYS).toString())))
        .hasMessageContaining("必须落在当前时刻之后");
    // 留空等于重新变成「一直有效」，会让接替版与它同时生效。
    assertThatThrownBy(() -> write(actor, () -> boms.closeVersion(actor, v1, "")))
        .hasMessageContaining("要指定一个具体时刻");

    // 合法：收紧到未来某一刻。
    var closed = write(actor, () ->
        boms.closeVersion(actor, v1, Instant.now().plus(7, ChronoUnit.DAYS).toString()));
    assertThat(closed.effectiveTo()).isNotNull();
    // 往后延被拒。
    assertThatThrownBy(() -> write(actor, () ->
        boms.closeVersion(actor, v1, Instant.now().plus(30, ChronoUnit.DAYS).toString())))
        .hasMessageContaining("只能收紧不能往后延");
    // 重开（变回开放区间）被拒。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update bom_version set effective_to=null where tenant_id=? and id=?", a, v1)))
        .hasMessageContaining("不能重开生效区间");
  }

  // ================================================================ R6 损耗率

  @Test
  void scrapRateIsStoredAsAPercentageAndSurvivesTheRoundTrip() {
    var f = fixture(actor, "BOM");
    UUID versionId = draftVersion(actor, f, "v1", Instant.now().minus(10, ChronoUnit.DAYS));
    var components = tx(actor, () -> boms.version(versionId)).components();
    var main = components.stream().filter(c -> c.skuId().equals(f.skuA())).findFirst().orElseThrow();

    // 存的是 5（= 5%），不是 0.05。这条断言是 R6 的全部意义：
    // 存成比率的话「填 5」会变成 500% 损耗，采购量翻 6 倍且不报错。
    assertThat(main.scrapRate()).isEqualByComparingTo("5");
    BigDecimal stored = tx(actor, () -> sql.fetchOne(
            "select scrap_rate from bom_component where tenant_id=? and id=?", a, main.id()))
        .get("scrap_rate", BigDecimal.class);
    assertThat(stored).isEqualByComparingTo("5");
    // 列类型必须是 numeric(7,4) 百分数，不是 float。
    org.jooq.Record type = tx(actor, () -> sql.fetchOne(
        "select numeric_precision p, numeric_scale s from information_schema.columns"
            + " where table_name='bom_component' and column_name='scrap_rate'"));
    assertThat(type.get("p", Integer.class)).isEqualTo(7);
    assertThat(type.get("s", Integer.class)).isEqualTo(4);

    // 0.0001 的精度也要存得住：4 位小数不是摆设。
    write(actor, () -> boms.replaceComponents(actor, versionId,
        new BomComponentsInput(
            List.of(new BomComponentInput(f.skuA(), new BigDecimal("2"), new BigDecimal("0.0001"), "", "")),
            tx(actor, () -> boms.version(versionId)).version().version())));
    var reread = tx(actor, () -> boms.version(versionId)).components().get(0);
    assertThat(reread.scrapRate()).isEqualByComparingTo("0.0001");
  }

  // ================================================================ R7 单层需求量

  @Test
  void singleLevelRequirementAppliesScrapWithoutRounding() {
    var f = fixture(actor, "BOM");
    UUID versionId = draftVersion(actor, f, "v1", Instant.now().minus(10, ChronoUnit.DAYS));

    // 产 100 个：组件 A 用量 2 损耗 5% → 2 × 100 × 1.05 = 210；组件 B 用量 0.5 无损耗 → 50
    var requirement = tx(actor, () -> boms.requirement(versionId, "100"));
    assertThat(requirement.outputQuantity()).isEqualByComparingTo("100");
    assertThat(requirement.singleLevel()).isTrue();
    assertThat(requirement.lines()).hasSize(2);
    var aLine = requirement.lines().stream()
        .filter(l -> l.skuId().equals(f.skuA())).findFirst().orElseThrow();
    var bLine = requirement.lines().stream()
        .filter(l -> l.skuId().equals(f.skuB())).findFirst().orElseThrow();
    assertThat(aLine.requiredQuantity()).isEqualByComparingTo("210.000000");
    assertThat(aLine.scrapRate()).isEqualByComparingTo("5");
    assertThat(bLine.requiredQuantity()).isEqualByComparingTo("50.000000");
    assertThat(requirement.totalRequiredQuantity()).isEqualByComparingTo("260.000000");
    // R7：取整是 3.5.2.2 与 06 的决定，这里给的是精确需求量。
    assertThat(requirement.note()).contains("不取整").contains("单层");

    // 小数需求量的场景：产 3 个、损耗 50%、用量 1 → 4.5，不能被悄悄取整成 4 或 5。
    var fractional = tx(actor, () -> boms.requirement(versionId, "3"));
    assertThat(fractional.lines().stream()
        .filter(l -> l.skuId().equals(f.skuA())).findFirst().orElseThrow()
        .requiredQuantity()).isEqualByComparingTo("6.300000");
    assertThat(fractional.lines().stream()
        .filter(l -> l.skuId().equals(f.skuB())).findFirst().orElseThrow()
        .requiredQuantity()).isEqualByComparingTo("1.500000");
  }

  @Test
  void requirementRejectsNonPositiveAndFractionalOutputQuantities() {
    var f = fixture(actor, "BOM");
    UUID versionId = draftVersion(actor, f, "v1", Instant.now().minus(10, ChronoUnit.DAYS));
    // 产 0 个时每个组件的需求量都是 0，清单会被误读成「这个 BOM 什么料都不用」。
    assertThatThrownBy(() -> tx(actor, () -> boms.requirement(versionId, "0")))
        .hasMessageContaining("必须大于 0");
    assertThatThrownBy(() -> tx(actor, () -> boms.requirement(versionId, "-1")))
        .hasMessageContaining("必须大于 0");
    assertThatThrownBy(() -> tx(actor, () -> boms.requirement(versionId, "0.5")))
        .hasMessageContaining("必须是整数");
    assertThatThrownBy(() -> tx(actor, () -> boms.requirement(versionId, "abc")))
        .hasMessageContaining("不是合法数字");
  }

  // ================================================================ 版本复制

  @Test
  void copyingAVersionCarriesQuantityAndScrapIntoANewDraft() {
    var f = fixture(actor, "BOM");
    UUID v1 = draftVersion(actor, f, "v1", Instant.parse("2026-03-01T00:00:00Z"));
    var source = tx(actor, () -> boms.version(v1));

    var copy = write(actor, () -> boms.copyVersion(actor, v1, "v2", null));
    // 复制品永远是草稿：源版本可能已发布，改是为了改出新的一版。
    assertThat(copy.status()).isEqualTo("DRAFT");
    assertThat(copy.versionNo()).isEqualTo("v2");
    // 名字带源版本号，复制完分不清哪份是哪份这件事不能发生。
    assertThat(copy.name()).contains("复制自 v1");
    // 生效起点默认「此刻」而不是沿用源版本（沿用会直接撞 R2，且运营要的是「从现在起接替」）。
    assertThat(Instant.parse(copy.effectiveFrom()))
        .isAfter(Instant.now().minus(1, ChronoUnit.DAYS));

    var copied = tx(actor, () -> boms.version(copy.id()));
    assertThat(copied.components()).hasSize(source.components().size());
    for (BomComponentRow original : source.components()) {
      var same = copied.components().stream()
          .filter(c -> c.skuId().equals(original.skuId())).findFirst().orElseThrow();
      // 复制这个动作的全部意义就是带走这两个数。
      assertThat(same.unitQuantity()).isEqualByComparingTo(original.unitQuantity());
      assertThat(same.scrapRate()).isEqualByComparingTo(original.scrapRate());
    }

    // 复制已发布版本同样得到草稿，且不碰源版本一行。
    publish(actor, v1);
    var fromPublished = write(actor, () -> boms.copyVersion(actor, v1, "v3", null));
    assertThat(fromPublished.status()).isEqualTo("DRAFT");
    assertThat(tx(actor, () -> boms.version(v1)).components()).hasSize(source.components().size());
  }

  // ================================================================ 倒排查询

  @Test
  void usageLookupAnswersWhichBomsUseAComponent() {
    var f = fixture(actor, "BOM");
    var other = fixture(actor, "BOM");
    UUID v1 = draftVersion(actor, f, "v1", Instant.now().minus(10, ChronoUnit.DAYS));
    publish(actor, v1);
    UUID v2 = draftVersion(actor, f, "v2", Instant.now().plus(10, ChronoUnit.DAYS));
    // 另一个 BOM 也用了**同一个**组件 f.skuA —— 倒排查的正是「这个料被谁用着」，
    // 如果另一个 BOM 用的是它自己的组件，这条断言就退化成「只有一个 BOM 用它」而测不出倒排。
    UUID otherVersion =
        write(actor, () -> boms.createVersion(actor, other.bomId(),
            new BomVersionInput("v1", "另一个 BOM", Instant.now().minus(10, ChronoUnit.DAYS).toString(),
                null, "", 0))).id();
    write(actor, () -> boms.replaceComponents(actor, otherVersion,
        new BomComponentsInput(
            List.of(new BomComponentInput(f.skuA(), new BigDecimal("3"), new BigDecimal("1"), "共用件", "")),
            0)));
    assertThat(otherVersion).isNotNull();

    var page = tx(actor, () -> boms.usage(new BomUsageQuery(f.skuA(), null, null, 0, 50)));
    // f 的 v1 与 v2 都用了它，另一个 BOM 的 v1 也用了它 —— 倒排要一次把三处都答出来。
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.total()).isEqualTo(page.items().size());
    assertThat(page.skuCode()).isNotBlank();
    assertThat(page.skuName()).isNotBlank();
    assertThat(page.items().stream().map(BomUsageRow::bomId))
        .containsExactlyInAnyOrder(f.bomId(), f.bomId(), other.bomId());
    // 每行都带用量与损耗率：改之前要先看「改了会影响谁、各是多少」。
    for (BomUsageRow row : page.items()) {
      assertThat(row.unitQuantity()).isPositive();
      assertThat(row.scrapRate()).isNotNull();
      assertThat(row.versionNo()).isNotBlank();
      assertThat(row.bomCode()).isNotBlank();
    }
    assertThat(rowOf(page, v1).unitQuantity()).isEqualByComparingTo("2");
    assertThat(rowOf(page, v1).scrapRate()).isEqualByComparingTo("5");
    assertThat(rowOf(page, otherVersion).unitQuantity()).isEqualByComparingTo("3");
    assertThat(rowOf(page, otherVersion).scrapRate()).isEqualByComparingTo("1");
    // 逐行带 editable：改这一行会动到哪个已冻结的历史版本，列表上一眼可见。
    assertThat(rowOf(page, v1).editable()).isFalse();
    assertThat(rowOf(page, v1).versionStatus()).isEqualTo("PUBLISHED");
    assertThat(rowOf(page, v1).versionStatusLabel()).isEqualTo("已发布");
    assertThat(rowOf(page, v2).editable()).isTrue();
    assertThat(rowOf(page, v1).effective()).isTrue();
    assertThat(rowOf(page, v2).effective()).isFalse();

    // onlyEffective 只列此刻生效的。
    var effectiveOnly = tx(actor, () -> boms.usage(new BomUsageQuery(f.skuA(), null, true, 0, 50)));
    assertThat(effectiveOnly.total()).isEqualTo(1);
    assertThat(effectiveOnly.items().get(0).bomId()).isEqualTo(f.bomId());
    // 关键词过滤。
    assertThat(tx(actor, () -> boms.usage(new BomUsageQuery(f.skuA(), "不存在XYZ", null, 0, 50))).total())
        .isEqualTo(0);
    // 按 BOM 编码过滤要能命中真实的那一个。
    var otherBomCode = tx(actor, () -> boms.bom(other.bomId())).bom().code();
    var byCode = tx(actor, () -> boms.usage(new BomUsageQuery(f.skuA(), otherBomCode, null, 0, 50)));
    assertThat(byCode.total()).isEqualTo(1);
    assertThat(byCode.items().get(0).bomId()).isEqualTo(other.bomId());
  }

  /** 倒排结果里取某个版本的那一行。取不到直接炸：静默返回空会让下面的断言变成「测了个寂寞」。 */
  BomUsageRow rowOf(BomUsagePage page, UUID versionId) {
    return page.items().stream()
        .filter(r -> r.versionId().equals(versionId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("倒排结果里没有版本 " + versionId + " 那一行"));
  }

  // ================================================================ 概览与列表

  @Test
  void overviewCountsOpenEndedAndComponentlessVersions() {
    var f = fixture(actor, "BOM");
    Instant from = Instant.now().minus(10, ChronoUnit.DAYS);
    UUID v1 = draftVersion(actor, f, "v1", from);
    UUID empty = write(actor, () -> boms.createVersion(actor, f.bomId(),
        new BomVersionInput("empty", "空清单", from.toString(), null, "", 0))).id();
    assertThat(empty).isNotNull();

    var before = tx(actor, () -> boms.overview());
    assertThat(before.draftCount()).isGreaterThanOrEqualTo(2);
    assertThat(before.draftWithoutComponentCount()).isGreaterThanOrEqualTo(1);
    // 「已发布但没有组件」此刻为 0：发布的第一道关就是组件齐备。
    assertThat(before.publishedWithoutComponentCount()).isZero();

    publish(actor, v1);
    var after = tx(actor, () -> boms.overview());
    // 开放区间 = 已发布且永远有效；没被接替就会一直停在旧版本上。
    assertThat(after.openEndedCount()).isGreaterThanOrEqualTo(1);
    assertThat(after.effectiveNowCount()).isGreaterThanOrEqualTo(1);
    assertThat(after.note()).contains("开放区间");
  }

  @Test
  void listAndDetailExposeRealTotalsAndTheEffectiveMarker() throws Exception {
    var f = fixture(actor, "BOM");
    UUID v1 = draftVersion(actor, f, "v1", Instant.now().minus(10, ChronoUnit.DAYS));
    publish(actor, v1);

    var list = tx(actor, () -> boms.boms(new BomQuery(f.bomId().toString() + "X", null, 0, 50)));
    assertThat(list.items()).isEmpty();
    var found = tx(actor, () -> boms.boms(new BomQuery(null, null, 0, 200)));
    assertThat(found.total()).isEqualTo(found.items().size());
    assertThat(found.items().stream().map(BomRow::id)).contains(f.bomId());
    // 关键词能搜到成品 SKU 编码：运营手上往往只有成品编码。
    var byOutput = tx(actor, () -> boms.boms(new BomQuery(f.outputSkuId().toString(), null, 0, 200)));
    assertThat(byOutput.items()).isEmpty();
    var outputCode = tx(actor, () -> boms.bom(f.bomId())).bom().outputSkuCode();
    assertThat(tx(actor, () -> boms.boms(new BomQuery(outputCode, null, 0, 200))).items())
        .isNotEmpty();

    var detail = tx(actor, () -> boms.bom(f.bomId()));
    assertThat(detail.versions()).hasSize(1);
    assertThat(detail.effectiveVersionNo()).isEqualTo("v1");
    assertThat(detail.note()).contains("此刻生效");
    assertThat(detail.versions().get(0).effective()).isTrue();

    // 跨 BOM 的版本列表。
    var versions = tx(actor, () -> boms.versions(new BomVersionQuery(null, "PUBLISHED", f.bomId(), 0, 50)));
    assertThat(versions.items()).hasSize(1);
    assertThat(versions.items().get(0).bomCode()).isNotBlank();
    // 「本版本里损耗率最高的一行」是 BOM 独有的可见量。
    assertThat(versions.items().get(0).scrapPercent()).isEqualByComparingTo("5");
    assertThat(versions.items().get(0).outputSkuCode()).isNotBlank();

    // HTTP 层：路由真的通。
    http.perform(get("/catalog/boms/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/boms?status=ACTIVE").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/boms/" + f.bomId() + "/effective").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/bom-versions/" + v1 + "/requirement?outputQuantity=10")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/bom-components/usage?skuId=" + f.skuA())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
  }

  @Test
  void instantsSurviveTheRoundTripWithoutTheDoubleOffsetTrap() throws Exception {
    // 服务层用 OffsetDateTime.ofInstant(..., UTC) + ?::timestamptz 绑值。
    // 若改回 Timestamp.valueOf(LocalDateTime)，这里会静默差 -28800s（Asia/Shanghai）且不报错。
    Instant from = Instant.parse("2026-03-01T00:00:00Z");
    var f = fixture(actor, "BOM");
    UUID v1 =
        write(actor, () -> boms.createVersion(actor, f.bomId(),
            new BomVersionInput("v1", "BOM v1", from.toString(), null, "", 0))).id();
    OffsetDateTime stored =
        tx(actor, () -> sql.fetchOne(
                "select effective_from from bom_version where tenant_id=? and id=?", a, v1)
            .get("effective_from", OffsetDateTime.class));
    // 断言写回来的值，而不是只断言「没抛异常」——「不报错」和「写对了」是两件事。
    assertThat(stored.toInstant()).isEqualTo(from);
    assertThat(tx(actor, () -> boms.version(v1)).version().effectiveFrom()).isEqualTo(from.toString());
    // 带偏移的写法（+08:00）必须归一到同一个时刻。
    write(actor, () -> boms.updateVersion(actor, v1,
        new BomVersionInput("v1", "BOM v1", "2026-03-01T08:00:00+08:00", null, "", 0)));
    OffsetDateTime shifted =
        tx(actor, () -> sql.fetchOne(
                "select effective_from from bom_version where tenant_id=? and id=?", a, v1)
            .get("effective_from", OffsetDateTime.class));
    assertThat(shifted.toInstant()).isEqualTo(from);
    // 发布时间也是 timestamptz，同样不能漂。
    write(actor, () -> boms.replaceComponents(actor, v1,
        new BomComponentsInput(
            List.of(new BomComponentInput(f.skuA(), new BigDecimal("1.5"), BigDecimal.ZERO, "", "")),
            tx(actor, () -> boms.version(v1)).version().version())));
    publish(actor, v1);
    OffsetDateTime publishedAt =
        tx(actor, () -> sql.fetchOne(
                "select published_at from bom_version where tenant_id=? and id=?", a, v1)
            .get("published_at", OffsetDateTime.class));
    assertThat(publishedAt).isNotNull();
    assertThat(publishedAt.toInstant())
        .isBetween(Instant.now().minus(5, ChronoUnit.MINUTES), Instant.now().plus(5, ChronoUnit.MINUTES));
  }

  @Test
  void readerCanReadButCannotWrite() throws Exception {
    var f = fixture(actor, "BOM");
    http.perform(get("/catalog/boms").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(post("/catalog/boms")
            .header("Authorization", "Bearer reader")
            .contentType("application/json")
            .content("{\"code\":\"NOPE\",\"outputSkuId\":\"" + f.outputSkuId() + "\",\"note\":\"\",\"version\":0}"))
        .andExpect(status().isForbidden());
    UUID v1 = draftVersion(actor, f, "v1", Instant.now().minus(10, ChronoUnit.DAYS));
    http.perform(post("/catalog/bom-versions/" + v1 + "/publish")
            .header("Authorization", "Bearer reader"))
        .andExpect(status().isForbidden());
  }

  // ================================================================ 租户隔离三断言

  /**
   * 租户隔离三断言：
   *
   * <ol>
   *   <li>两租户互不可见（读、写、删都按当前租户过滤，含倒排方向）；
   *   <li>伪造 {@code X-Tenant-Id} 头无效（租户只来自已认证的成员关系）；
   *   <li>连接池归还后不串租户（单连接反复交替）。
   * </ol>
   */
  @Test
  void tenantIsolationHoldsAcrossReadWriteAndPoolReuse() throws Exception {
    var mine = fixture(actor, "MINE");
    UUID myVersion = draftVersion(actor, mine, "v1", Instant.now().minus(10, ChronoUnit.DAYS));
    publish(actor, myVersion);
    var theirs = fixture(actorB, "THEIRS");
    UUID theirVersion = draftVersion(actorB, theirs, "v1", Instant.now().minus(10, ChronoUnit.DAYS));
    publish(actorB, theirVersion);

    // 1) 两租户互不可见
    assertThatThrownBy(() -> tx(actor, () -> boms.bom(theirs.bomId())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> boms.bom(mine.bomId())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> boms.version(theirVersion)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> boms.components(theirVersion)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> boms.requirement(theirVersion, "10")))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> boms.effectiveVersion(theirs.bomId(), null)))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(tx(actor, () -> boms.boms(new BomQuery(mine.bomId().toString() + "X", null, 0, 50)))
        .items()).isEmpty();
    // 写方向同样隔离：A 改不动 B 的版本，改不动 B 的组件。
    assertThatThrownBy(() -> write(actor, () -> boms.replaceComponents(actor, theirVersion,
        new BomComponentsInput(
            List.of(new BomComponentInput(mine.skuA(), BigDecimal.ONE, BigDecimal.ZERO, "", "")), 0))))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> write(actor, () -> boms.publishVersion(actor, theirVersion)))
        .isInstanceOf(ResponseStatusException.class);
    // 倒排方向同样隔离：B 连「我的组件这个 SKU」都查不到，直接被拒
    // （比返回空集更强：返回空集的话「这个 SKU 没人用」与「这个 SKU 不属于你」分不开）。
    assertThatThrownBy(() -> tx(actorB, () -> boms.usage(new BomUsageQuery(mine.skuA(), null, null, 0, 50))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("在本租户不存在");
    assertThat(tx(actor, () -> boms.usage(new BomUsageQuery(mine.skuA(), null, null, 0, 50))).total())
        .isEqualTo(1);
    // B 的组件不能被 A 的 BOM 引用（复合外键 + 显式校验双重拦截）。
    assertThatThrownBy(() -> write(actor, () -> boms.saveBom(actor, null,
        new BomInput(unique("BOM"), theirs.outputSkuId(), "", 0))))
        .hasMessageContaining("在本租户不存在");

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(get("/catalog/boms/" + mine.bomId())
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(get("/catalog/bom-versions/" + myVersion)
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 伪造头下写入仍然落在 B 租户自己名下，与 A 无关。
    http.perform(post("/catalog/boms")
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a)
            .contentType("application/json")
            .content("{\"code\":\"" + unique("BOM") + "\",\"outputSkuId\":\"" + theirs.outputSkuId()
                + "\",\"note\":\"\",\"version\":0}"))
        .andExpect(status().isOk());
    long aBomsBefore = tx(actor, () -> boms.boms(new BomQuery(null, null, 0, 200)).total());
    // B 现在有 2 个 BOM：夹具那个 + 伪造头下建的那个（写在了 B 自己名下）。
    assertThat(tx(actorB, () -> boms.boms(new BomQuery(null, null, 0, 200)).total())).isEqualTo(2);
    assertThat(tx(actor, () -> boms.boms(new BomQuery(null, null, 0, 200)).total()))
        .isEqualTo(aBomsBefore);

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> boms.bom(mine.bomId()).bom().id())).isEqualTo(mine.bomId());
      assertThat(tx(actorB, () -> boms.bom(theirs.bomId()).bom().id())).isEqualTo(theirs.bomId());
      assertThatThrownBy(() -> tx(actorB, () -> boms.bom(mine.bomId())))
          .isInstanceOf(ResponseStatusException.class);
      assertThatThrownBy(() -> tx(actor, () -> boms.bom(theirs.bomId())))
          .isInstanceOf(ResponseStatusException.class);
      assertThat(tx(actor, () -> boms.version(myVersion)).version().name()).isEqualTo("BOM v1");
      assertThat(tx(actorB, () -> boms.version(theirVersion)).version().name()).isEqualTo("BOM v1");
      assertThat(tx(actor, () -> boms.boms(new BomQuery(null, null, 0, 200)).total()))
          .isEqualTo(aBomsBefore);
      // 两边各自看到的 BOM 集合互不相交：比「谁的数更大」更能证明没有串。
      var seenByA = tx(actor, () -> boms.boms(new BomQuery(null, null, 0, 200)).items())
          .stream().map(BomRow::id).collect(java.util.stream.Collectors.toSet());
      var seenByB = tx(actorB, () -> boms.boms(new BomQuery(null, null, 0, 200)).items())
          .stream().map(BomRow::id).collect(java.util.stream.Collectors.toSet());
      assertThat(seenByA).isNotEmpty();
      assertThat(seenByB).isNotEmpty();
      assertThat(java.util.Collections.disjoint(seenByA, seenByB)).isTrue();
      assertThat(tx(actor, () -> boms.overview().bomCount())).isEqualTo(seenByA.size());
      // 组件清单同样不串。
      assertThat(tx(actor, () -> boms.components(myVersion)).stream().map(BomComponentRow::skuId))
          .doesNotContainAnyElementsOf(
              tx(actorB, () -> boms.components(theirVersion)).stream()
                  .map(BomComponentRow::skuId).toList());
    }
  }

  @Test
  void rlsPoliciesAreInPlaceOnAllThreeTables() {
    var tables =
        tx(actor, () -> sql.fetch(
            "select relname,relrowsecurity,relforcerowsecurity from pg_class"
                + " where relname in ('bom','bom_version','bom_component') and relkind='r'"));
    assertThat(tables).hasSize(3);
    assertThat(tables)
        .allMatch(
            r ->
                Boolean.TRUE.equals(r.get("relforcerowsecurity", Boolean.class))
                    && Boolean.TRUE.equals(r.get("relrowsecurity", Boolean.class)));
    // 数量必须是 NUMERIC(19,6)，不是 double：组装单领料与采购量都照着这个数走。
    org.jooq.Record quantityType =
        tx(actor, () -> sql.fetchOne(
            "select numeric_precision p, numeric_scale s from information_schema.columns"
                + " where table_name='bom_component' and column_name='unit_quantity'"));
    assertThat(quantityType.get("p", Integer.class)).isEqualTo(19);
    assertThat(quantityType.get("s", Integer.class)).isEqualTo(6);
    // 三个触发器都在：绕过应用层也要被拦。
    var triggers = tx(actor, () -> sql.fetch(
        "select tgname from pg_trigger where not tgisinternal and"
            + " (tgrelid='bom_version'::regclass or tgrelid='bom_component'::regclass)"
            + " and not tgisinternal"));
    assertThat(triggers.stream().map(r -> r.get("tgname", String.class)))
        .contains(
            "guard_bom_version_published",
            "refuse_bom_version_published_delete",
            "guard_bom_component_published",
            "guard_bom_version_interval");
  }
}
