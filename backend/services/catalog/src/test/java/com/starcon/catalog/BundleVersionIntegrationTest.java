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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
 * 3.5.1.1 套装版本管理：版本 CRUD、草稿/发布状态机、发布后不可变、生效区间冲突裁决、 当前生效版本查询、组件清单，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS：套装、版本、组件全部真的落库， 「已发布不可改」「区间重叠拒绝」都是<b>绕过应用层直接写 SQL 也会被拦</b>的事实。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的四个断言：</b>
 *
 * <ol>
 *   <li>{@link #publishedVersionIsImmutableEvenThroughDirectSql()}：直连 SQL 也改不出改名、
 *       改版本号、撤回发布、删版本、给已发布版本加组件 —— 绕过应用层照样被 V23 触发器拦住。
 *   <li>{@link #effectiveIntervalRulesArePinnedDown()}：R1 半开区间 / R2 重叠拒绝 / R4 当前生效唯一
 *       三条裁决规则逐条钉死，防止后来者被换成「自动裁决」。
 *   <li>{@link #publishedVersionCanOnlyBeClosedForward()}：R5 向前关闭是已发布版本上唯一合法的写操作。
 *   <li>{@link #tenantIsolationHoldsAcrossReadWriteAndPoolReuse()}：租户隔离三断言。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class BundleVersionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();

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
      try (var conn =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
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
            byte[] body =
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions()))));
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static List<String> allPermissions() {
    return java.util.stream.Stream.of("spu", "sku", "assets", "attribute")
        .flatMap(
            p ->
                java.util.stream.Stream.of("read", "create", "update", "delete")
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired BundleVersionService bundles;
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

  void writeVoid(AccessService.Access who, Runnable work) {
    access.run(
        who,
        "catalog.spu.update",
        () -> {
          work.run();
          return null;
        });
  }

  // ------------------------------------------------------------ 夹具

  String unique(String hint) {
    return hint
        + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  UUID spu(AccessService.Access who, String code) {
    return write(
        who,
        () ->
            catalog.saveSpu(
                who, null, new SpuInput(code, "SPU " + code, "", "Brand", "Category", "DRAFT", 0)));
  }

  /** 建 SKU。第二个 SKU 起必须带 SPU 的当前版本 —— 传 0 会撞「商品已被修改，请刷新后重试」， 那条 409 说的是乐观锁而不是「一个 SPU 只能有一个 SKU」。 */
  UUID sku(AccessService.Access who, UUID spuId, String code, long spuVersion) {
    return write(
        who,
        () ->
            catalog.saveSku(
                who,
                spuId,
                null,
                new SkuInput(
                    code, "SKU " + code, unique("BC"), "PCS", "ACTIVE", List.of(), spuVersion)));
  }

  /** 建套装 + 一个草稿版本 + 两个组件（数量 2 与 0.5，验证 NUMERIC(19,6) 不是摆设）。 */
  record Fixture(UUID bundleId, UUID versionId, UUID skuA, UUID skuB) {}

  Fixture fixture(AccessService.Access who, String hint) {
    // 两个 SKU 挂在<b>不同</b>的 SPU 上：同一个 SPU 下两个无属性 SKU 的 variant_signature
    // 相同，会撞 (tenant_id, spu_id, variant_signature) 唯一键。套装组件本来就不要求同 SPU。
    UUID skuA = sku(who, spu(who, unique("SPU")), unique("SKU"), 0);
    UUID skuB = sku(who, spu(who, unique("SPU")), unique("SKU"), 0);
    UUID bundleId =
        write(who, () -> bundles.saveBundle(who, null, new BundleInput(unique("BND"), "套装", 0)))
            .id();
    Instant from = Instant.now().minus(10, ChronoUnit.DAYS);
    UUID versionId =
        write(
                who,
                () ->
                    bundles.createVersion(
                        who,
                        bundleId,
                        new BundleVersionInput("v1", "套装 v1", from.toString(), null, "", 0)))
            .id();
    write(
        who,
        () ->
            bundles.replaceComponents(
                who,
                versionId,
                new BundleComponentsInput(
                    List.of(
                        new BundleComponentInput(skuA, new BigDecimal("2"), "主机", ""),
                        new BundleComponentInput(skuB, new BigDecimal("0.5"), "配件", "")),
                    0)));
    return new Fixture(bundleId, versionId, skuA, skuB);
  }

  /** 给草稿补一个组件（发布的第一道关是「组件齐备」，所以每个要发布的版本都得先有组件）。 */
  void giveComponent(AccessService.Access who, UUID versionId, UUID skuId) {
    write(
        who,
        () ->
            bundles.replaceComponents(
                who,
                versionId,
                new BundleComponentsInput(
                    List.of(new BundleComponentInput(skuId, new BigDecimal("1"), "", "")), 0)));
  }

  BundleVersionRow publish(AccessService.Access who, UUID versionId) {
    return write(who, () -> bundles.publishVersion(who, versionId));
  }

  // ================================================================ 套装档案

  @Test
  void bundleArchiveIsNotDeleteAndCodeIsImmutable() {
    var created =
        write(
            actor, () -> bundles.saveBundle(actor, null, new BundleInput(unique("BND"), "备注", 0)));
    assertThat(created.code()).isEqualTo(created.code().toUpperCase(Locale.ROOT));

    // 编码建后不可改：它是刊登与订单里的稳定引用。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        bundles.saveBundle(
                            actor,
                            created.id(),
                            new BundleInput(unique("BND"), "换个名字", created.version()))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不可改");

    // 归档不是删除：历史版本照常可读。
    var archived = write(actor, () -> bundles.setBundleStatus(actor, created.id(), "ARCHIVED"));
    assertThat(archived.status()).isEqualTo("ARCHIVED");
    assertThat(tx(actor, () -> bundles.bundle(created.id()).bundle().id())).isEqualTo(created.id());

    // 归档后不能新建版本。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        bundles.createVersion(
                            actor,
                            created.id(),
                            new BundleVersionInput(
                                "v1", "新版本", Instant.now().toString(), null, "", 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已归档");
  }

  @Test
  void bundleWithPublishedVersionCannotBeDeleted() {
    var f = fixture(actor, "BND");
    publish(actor, f.versionId());
    var detail = tx(actor, () -> bundles.bundle(f.bundleId()));
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () -> bundles.deleteBundle(actor, f.bundleId(), detail.bundle().version())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已经发布过");
  }

  @Test
  void duplicateVersionNumberIsRejectedWithinTheSameBundle() {
    var f = fixture(actor, "BND");
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        bundles.createVersion(
                            actor,
                            f.bundleId(),
                            new BundleVersionInput(
                                "v1", "重复版本号", Instant.now().toString(), null, "", 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已经有版本");
  }

  // ================================================================ 组件

  @Test
  void componentsAreReplacedWholesaleAndKeepDecimalQuantity() {
    var f = fixture(actor, "BND");
    var detail = tx(actor, () -> bundles.version(f.versionId()));
    // 0.5 件是真实场景：int 会逼运营写成「1 件」，而订单展开与库存扣减都照着假数走。
    assertThat(detail.components()).hasSize(2);
    assertThat(detail.totalQuantity()).isEqualByComparingTo("2.5");
    assertThat(
            detail.components().stream()
                .anyMatch(c -> c.quantity().compareTo(new BigDecimal("0.5")) == 0))
        .isTrue();

    // 整体替换：保存后清单就是提交的那份，不会剩幽灵组件。
    var replaced =
        write(
            actor,
            () ->
                bundles.replaceComponents(
                    actor,
                    f.versionId(),
                    new BundleComponentsInput(
                        List.of(new BundleComponentInput(f.skuA(), new BigDecimal("3"), "", "")),
                        0)));
    assertThat(replaced.components()).hasSize(1);
    assertThat(replaced.components().get(0).quantity()).isEqualByComparingTo("3");

    // 同一个 SKU 出现两次：每件要几件就无解了。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        bundles.replaceComponents(
                            actor,
                            f.versionId(),
                            new BundleComponentsInput(
                                List.of(
                                    new BundleComponentInput(f.skuA(), BigDecimal.ONE, "", ""),
                                    new BundleComponentInput(
                                        f.skuA(), new BigDecimal("2"), "", "")),
                                0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("出现了两次");

    // 指向不存在的 SKU：扣库存时扣的是一个不存在的东西。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        bundles.replaceComponents(
                            actor,
                            f.versionId(),
                            new BundleComponentsInput(
                                List.of(
                                    new BundleComponentInput(
                                        UUID.randomUUID(), BigDecimal.ONE, "", "")),
                                0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在");
    assertThat(versionOfBundleComponent(f.versionId())).isEqualTo(1);
  }

  @Test
  void publishRequiresAtLeastOneComponent() {
    UUID bundleId =
        write(actor, () -> bundles.saveBundle(actor, null, new BundleInput(unique("BND"), "", 0)))
            .id();
    UUID versionId =
        write(
                actor,
                () ->
                    bundles.createVersion(
                        actor,
                        bundleId,
                        new BundleVersionInput("v1", "空套装", Instant.now().toString(), null, "", 0)))
            .id();
    // 空组件的套装在刊登里是一个「能买的商品」，展开到订单里却什么都不是。
    assertThatThrownBy(() -> publish(actor, versionId))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("至少要有一个组件");
    assertThat(tx(actor, () -> bundles.version(versionId).version().status())).isEqualTo("DRAFT");
  }

  private long versionOfBundleComponent(UUID versionId) {
    return tx(
        actor,
        () ->
            sql.fetchOne(
                    "select count(*) from bundle_component where tenant_id=? and version_id=?",
                    a,
                    versionId)
                .get(0, Long.class));
  }

  // ================================================================ 状态机

  @Test
  void draftToPublishedIsOneWayAndCarriesReleaseAudit() {
    var f = fixture(actor, "BND");
    var draft = tx(actor, () -> bundles.version(f.versionId()));
    assertThat(draft.version().status()).isEqualTo("DRAFT");
    assertThat(draft.editable()).isTrue();

    var published = publish(actor, f.versionId());
    assertThat(published.status()).isEqualTo("PUBLISHED");
    assertThat(published.statusLabel()).isEqualTo("已发布");
    // 没有发布人的 PUBLISHED 在数据里等于「有人按过发布」这种无法证伪的说法。
    assertThat(published.publishedBy()).isEqualTo(user);
    assertThat(published.publishedAt()).isNotNull();
    assertThat(published.editable()).isFalse();

    // 重复发布被拒。
    assertThatThrownBy(() -> publish(actor, f.versionId()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已经是已发布状态");
  }

  @Test
  void publishedVersionRefusesEveryContentEdit() {
    var f = fixture(actor, "BND");
    publish(actor, f.versionId());
    var v = tx(actor, () -> bundles.version(f.versionId())).version();

    for (BundleVersionInput input :
        List.of(
            new BundleVersionInput("v9", "改名", v.effectiveFrom(), v.effectiveTo(), "", 0),
            new BundleVersionInput(v.versionNo(), "改名", v.effectiveFrom(), v.effectiveTo(), "", 0),
            new BundleVersionInput(
                v.versionNo(), v.name(), Instant.now().toString(), v.effectiveTo(), "", 0),
            new BundleVersionInput(
                v.versionNo(), v.name(), v.effectiveFrom(), v.effectiveTo(), "备注", 0))) {
      assertThatThrownBy(
              () -> write(actor, () -> bundles.updateVersion(actor, f.versionId(), input)))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("已发布，不可修改");
    }

    // 组件同样冻结。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        bundles.replaceComponents(
                            actor,
                            f.versionId(),
                            new BundleComponentsInput(
                                List.of(
                                    new BundleComponentInput(
                                        f.skuA(), new BigDecimal("9"), "", "")),
                                0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已发布，不可修改");

    // 删已发布版本同样被拒。
    assertThatThrownBy(
            () -> writeVoid(actor, () -> bundles.deleteVersion(actor, f.versionId(), v.version())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已发布，不可修改");
  }

  /**
   * 绕过应用层直接写 SQL 也一样被拦。
   *
   * <p>这是「发布后不可变」的纵深防线：V23 的触发器不是应用层的自觉， 用 Navicat 或另一个服务直连数据库写也一样改不出「改名 / 撤回发布 / 删版本 / 加组件」。
   */
  @Test
  void publishedVersionIsImmutableEvenThroughDirectSql() {
    var f = fixture(actor, "BND");
    publish(actor, f.versionId());
    UUID versionId = f.versionId();

    // 改名。
    assertThatThrownBy(
            () ->
                rawSql(
                    "update bundle_version set name='直连改的名' where tenant_id='"
                        + a
                        + "' and id='"
                        + versionId
                        + "'"))
        .hasMessageContaining("不能改名");
    // 改版本号。
    assertThatThrownBy(
            () ->
                rawSql(
                    "update bundle_version set version_no='hacked' where tenant_id='"
                        + a
                        + "' and id='"
                        + versionId
                        + "'"))
        .hasMessageContaining("不能改版本号");
    // 移动生效起点 = 追溯改写历史。
    assertThatThrownBy(
            () ->
                rawSql(
                    "update bundle_version set effective_from=now() where tenant_id='"
                        + a
                        + "' and id='"
                        + versionId
                        + "'"))
        .hasMessageContaining("不能改生效起点");
    // 撤回发布是单向的。
    assertThatThrownBy(
            () ->
                rawSql(
                    "update bundle_version set status='DRAFT',published_by=null,published_at=null"
                        + " where tenant_id='"
                        + a
                        + "' and id='"
                        + versionId
                        + "'"))
        .hasMessageContaining("不能撤回发布");
    // 删版本 = 让「这单当时按哪一版展开的」无从查证。
    assertThatThrownBy(
            () ->
                rawSql(
                    "delete from bundle_version where tenant_id='"
                        + a
                        + "' and id='"
                        + versionId
                        + "'"))
        .hasMessageContaining("不能删除");
    // 给已发布版本加组件。
    assertThatThrownBy(
            () ->
                rawSql(
                    "insert into bundle_component(id,tenant_id,version_id,sku_id,quantity) values('"
                        + UUID.randomUUID()
                        + "','"
                        + a
                        + "','"
                        + versionId
                        + "','"
                        + f.skuB()
                        + "',7)"))
        .hasMessageContaining("组件不可修改");
    // 改已发布版本的组件数量。
    assertThatThrownBy(
            () ->
                rawSql(
                    "update bundle_component set quantity=99 where tenant_id='"
                        + a
                        + "' and version_id='"
                        + versionId
                        + "'"))
        .hasMessageContaining("组件不可修改");
    // 删已发布版本的组件。
    assertThatThrownBy(
            () ->
                rawSql(
                    "delete from bundle_component where tenant_id='"
                        + a
                        + "' and version_id='"
                        + versionId
                        + "'"))
        .hasMessageContaining("组件不可修改");

    // 一条都没改成功。
    var still = tx(actor, () -> bundles.version(f.versionId()));
    assertThat(still.version().status()).isEqualTo("PUBLISHED");
    assertThat(still.version().name()).isEqualTo("套装 v1");
    assertThat(still.version().versionNo()).isEqualTo("v1");
    assertThat(still.components()).hasSize(2);
  }

  /** 直连 SQL（不经过服务层），用来证明 DB 层触发器真的拦得住。 */
  private void rawSql(String statement) {
    try (var conn =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = conn.createStatement()) {
      s.execute("set app.current_tenant='" + a + "'");
      s.execute(statement);
    } catch (Exception e) {
      var root = e.getCause() == null ? e : e.getCause();
      throw new IllegalStateException(root.getMessage(), root);
    }
  }

  // ================================================================ 生效区间

  /**
   * R1 半开区间 / R2 重叠拒绝 / R4 当前生效唯一 —— 三条裁决规则逐条钉死。
   *
   * <p>这三条是本模块对蓝图的唯一「补规则」。若哪天有人把它们换成「后来者覆盖」， 这个用例会先红，而不是等到订单冻结了错误的版本才被发现。
   */
  @Test
  void effectiveIntervalRulesArePinnedDown() {
    var f = fixture(actor, "BND");
    Instant t0 = Instant.now().minus(100, ChronoUnit.DAYS);
    Instant t1 = t0.plus(10, ChronoUnit.DAYS);
    Instant t2 = t0.plus(20, ChronoUnit.DAYS);
    Instant t3 = t0.plus(30, ChronoUnit.DAYS);

    // v1: [t0, t1)；发布。
    write(
        actor,
        () ->
            bundles.updateVersion(
                actor,
                f.versionId(),
                new BundleVersionInput("v1", "套装 v1", t0.toString(), t1.toString(), "", 0)));
    publish(actor, f.versionId());

    // R1：紧接着发布 [t1, t2) —— 边界相接**不**算重叠（半开区间）。
    UUID second =
        write(
                actor,
                () ->
                    bundles.createVersion(
                        actor,
                        f.bundleId(),
                        new BundleVersionInput("v2", "套装 v2", t1.toString(), t2.toString(), "", 0)))
            .id();
    giveComponent(actor, second, f.skuA());
    publish(actor, second);

    // R2：与 [t1,t2) 真重叠的版本一律拒绝，且报错点名与哪一版、哪个区间撞了。
    assertThatThrownBy(
            () -> {
              UUID third =
                  write(
                          actor,
                          () ->
                              bundles.createVersion(
                                  actor,
                                  f.bundleId(),
                                  new BundleVersionInput(
                                      "v3",
                                      "套装 v3",
                                      t1.plus(1, ChronoUnit.DAYS).toString(),
                                      t2.plus(1, ChronoUnit.DAYS).toString(),
                                      "",
                                      0)))
                      .id();
              giveComponent(actor, third, f.skuA());
              publish(actor, third);
            })
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("重叠")
        .hasMessageContaining("不做裁决")
        .hasMessageContaining("v2");
    // 被拒的版本仍然是草稿 —— 发布失败不能顺手把版本改掉。
    assertThat(
            tx(
                    actor,
                    () ->
                        bundles.versions(
                            new BundleVersionQuery(null, "PUBLISHED", f.bundleId(), 0, 50)))
                .total())
        .isEqualTo(2);

    // R3：草稿之间随便重叠，发布时才检查。
    UUID overlapDraft =
        write(
                actor,
                () ->
                    bundles.createVersion(
                        actor,
                        f.bundleId(),
                        new BundleVersionInput(
                            "v9",
                            "草稿 A",
                            t1.plus(2, ChronoUnit.DAYS).toString(),
                            t2.plus(5, ChronoUnit.DAYS).toString(),
                            "",
                            0)))
            .id();
    write(
        actor,
        () ->
            bundles.createVersion(
                actor,
                f.bundleId(),
                new BundleVersionInput(
                    "v10",
                    "草稿 B",
                    t1.plus(3, ChronoUnit.DAYS).toString(),
                    t2.plus(9, ChronoUnit.DAYS).toString(),
                    "",
                    0)));
    // v3（发布被拒，留在草稿）+ v9 + v10 = 3 个草稿。
    assertThat(
            tx(
                    actor,
                    () ->
                        bundles.versions(
                            new BundleVersionQuery(null, "DRAFT", f.bundleId(), 0, 50)))
                .total())
        .isEqualTo(3);
    giveComponent(actor, overlapDraft, f.skuA());
    assertThatThrownBy(() -> publish(actor, overlapDraft)).hasMessageContaining("重叠");

    // R4：当前生效版本是纯时刻查询，至多命中一个版本。
    assertThat(tx(actor, () -> bundles.effectiveVersion(f.bundleId(), t0.toString())).versionNo())
        .isEqualTo("v1");
    // 边界那一瞬间归后一版，不是前一版。
    assertThat(tx(actor, () -> bundles.effectiveVersion(f.bundleId(), t1.toString())).versionNo())
        .isEqualTo("v2");
    assertThat(
            tx(
                    actor,
                    () ->
                        bundles.effectiveVersion(
                            f.bundleId(), t1.minus(1, ChronoUnit.SECONDS).toString()))
                .versionNo())
        .isEqualTo("v1");
    // 区间有缝的时刻真的没有生效版本 —— 显式 false，而不是「查不到」。
    var gap = tx(actor, () -> bundles.effectiveVersion(f.bundleId(), t3.toString()));
    assertThat(gap.effective()).isFalse();
    assertThat(gap.versionNo()).isNull();
    assertThat(gap.note()).contains("没有生效版本");
    // 不传 at 就是查「此刻」。本用例的区间都在过去（t2 = 90 天前），所以此刻**确实**没有生效版本 ——
    // 而「查到没有」正是曾经 NPE 的那条分支（moment 为 null 时流到 moment.toString()）。
    // 这条断言的作用是钉死「不传参数不 500」，不是断言此刻有生效版本。
    var now = tx(actor, () -> bundles.effectiveVersion(f.bundleId(), null));
    assertThat(now.effective()).isFalse();
    assertThat(now.versionNo()).isNull();
    // 不传参数必须与显式传同一个时刻给出同一个答案。
    assertThat(
            tx(actor, () -> bundles.effectiveVersion(f.bundleId(), Instant.now().toString()))
                .effective())
        .isFalse();
    // 命中版本时也不能 NPE：显式传一个落在 v1 区间内的时刻。
    assertThat(tx(actor, () -> bundles.effectiveVersion(f.bundleId(), t0.toString())).versionNo())
        .isEqualTo("v1");
  }

  /**
   * R5：已发布版本只允许「向前关闭」。
   *
   * <p>这是本模块为可用性让出的<b>唯一</b>一个口子。若连它都禁掉， 第一个开放区间的版本发布后就永远无法被接替，整个版本机制第二天就死了。
   */
  @Test
  void publishedVersionCanOnlyBeClosedForward() {
    var f = fixture(actor, "BND");
    publish(actor, f.versionId());
    Instant future = Instant.now().plus(30, ChronoUnit.DAYS);
    Instant later = future.plus(30, ChronoUnit.DAYS);

    // 允许：把开放区间收到一个仍在未来的时刻。
    var closed = write(actor, () -> bundles.closeVersion(actor, f.versionId(), future.toString()));
    assertThat(closed.effectiveTo()).isNotNull();

    // 拒绝：重新变成「一直有效」（会与已接替它的版本重叠）。
    assertThatThrownBy(() -> write(actor, () -> bundles.closeVersion(actor, f.versionId(), null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("一直有效");
    // 拒绝：落进过去 = 追溯改写「那时生效的是哪一版」。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        bundles.closeVersion(
                            actor,
                            f.versionId(),
                            Instant.now().minus(1, ChronoUnit.DAYS).toString())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须落在当前时刻之后");
    // 拒绝：往后延（会与接替版本重叠）。这条由服务层先挡，DB 触发器兜底。
    assertThatThrownBy(
            () -> write(actor, () -> bundles.closeVersion(actor, f.versionId(), later.toString())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只能收紧不能往后延");

    // 关掉之后那一刻起本版不再生效（前提是没有接替版）。
    assertThat(
            tx(actor, () -> bundles.effectiveVersion(f.bundleId(), later.toString())).effective())
        .isFalse();
    // 区间是半开的：终点那一瞬间已经不属于本版了（这正是 R1 的含义）。
    assertThat(
            tx(actor, () -> bundles.effectiveVersion(f.bundleId(), future.toString())).versionNo())
        .isNull();
    assertThat(
            tx(
                    actor,
                    () ->
                        bundles.effectiveVersion(
                            f.bundleId(), future.minus(1, ChronoUnit.SECONDS).toString()))
                .versionNo())
        .isEqualTo("v1");
  }

  @Test
  void closeOnlyAppliesToPublishedVersions() {
    var f = fixture(actor, "BND");
    // 草稿直接改生效区间就行 —— 草稿还没生效，谈不上「停用」。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        bundles.closeVersion(
                            actor,
                            f.versionId(),
                            Instant.now().plus(10, ChronoUnit.DAYS).toString())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只有已发布的版本需要关闭生效区间");
  }

  // ================================================================ 概览与列表

  @Test
  void overviewCountsOpenEndedAndUnfinishedDrafts() {
    var f = fixture(actor, "BND");
    publish(actor, f.versionId());
    // 一个空草稿：建了版本但填不出能发布的内容。
    UUID bundleId2 =
        write(actor, () -> bundles.saveBundle(actor, null, new BundleInput(unique("BND"), "", 0)))
            .id();
    write(
        actor,
        () ->
            bundles.createVersion(
                actor,
                bundleId2,
                new BundleVersionInput("v1", "没组件", Instant.now().toString(), null, "", 0)));

    var overview = tx(actor, () -> bundles.overview());
    assertThat(overview.openEndedCount()).isGreaterThanOrEqualTo(1);
    assertThat(overview.draftWithoutComponentCount()).isGreaterThanOrEqualTo(1);
    assertThat(overview.note()).contains("开放区间");
  }

  @Test
  void listAndDetailExposeRealTotalsAndTheEffectiveMarker() throws Exception {
    var f = fixture(actor, "BND");
    publish(actor, f.versionId());
    UUID second =
        write(
                actor,
                () ->
                    bundles.createVersion(
                        actor,
                        f.bundleId(),
                        new BundleVersionInput(
                            "v2",
                            "下一版",
                            Instant.now().plus(10, ChronoUnit.DAYS).toString(),
                            null,
                            "",
                            0)))
            .id();

    var page = tx(actor, () -> bundles.bundles(new BundleQuery(null, "ACTIVE", 0, 20)));
    assertThat(page.total()).isGreaterThanOrEqualTo(1);
    assertThat(page.totalPages()).isEqualTo((int) Math.ceil(page.total() / 20.0));
    assertThat(page.items()).isNotEmpty();

    // 详情页上「此刻生效」必须落在已发布那一版上，而不是草稿。
    var detail = tx(actor, () -> bundles.bundle(f.bundleId()));
    assertThat(detail.effectiveVersionNo()).isEqualTo("v1");
    assertThat(detail.versions()).hasSize(2);
    assertThat(detail.versions().stream().filter(BundleVersionSummary::effective).count())
        .isEqualTo(1);
    assertThat(
            detail.versions().stream()
                .filter(v -> v.id().equals(second))
                .findFirst()
                .orElseThrow()
                .effective())
        .isFalse();

    // HTTP 层同样通。
    http.perform(get("/catalog/bundles/" + f.bundleId()).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.effectiveVersionNo").value("v1"));
    http.perform(get("/catalog/bundles/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/versions?status=DRAFT").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
  }

  @Test
  void instantsSurviveTheRoundTripWithoutTheDoubleOffsetTrap() throws Exception {
    // 服务层用 OffsetDateTime.ofInstant(..., UTC) + ?::timestamptz 绑值。
    // 若改回 Timestamp.valueOf(LocalDateTime)，这里会静默差 -28800s（Asia/Shanghai）且不报错。
    Instant from = Instant.parse("2026-03-01T00:00:00Z");
    var f2 = fixture(actor, "BND");
    write(
        actor,
        () ->
            bundles.updateVersion(
                actor,
                f2.versionId(),
                new BundleVersionInput("v1", "套装 v1", from.toString(), null, "", 0)));
    java.time.OffsetDateTime stored =
        tx(
            actor,
            () ->
                sql.fetchOne(
                        "select effective_from from bundle_version where tenant_id=? and id=?",
                        a,
                        f2.versionId())
                    .get("effective_from", java.time.OffsetDateTime.class));
    assertThat(stored.toInstant()).isEqualTo(from);
    assertThat(tx(actor, () -> bundles.version(f2.versionId())).version().effectiveFrom())
        .isEqualTo(from.toString());
  }

  @Test
  void readerCanReadButCannotWrite() throws Exception {
    var f = fixture(actor, "BND");
    http.perform(get("/catalog/bundles").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(
            post("/catalog/bundles")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content("{\"code\":\"NOPE\",\"note\":\"\",\"version\":0}"))
        .andExpect(status().isForbidden());
    publish(actor, f.versionId());
    http.perform(
            post("/catalog/versions/" + f.versionId() + "/publish")
                .header("Authorization", "Bearer reader"))
        .andExpect(status().isForbidden());
  }

  // ================================================================ 租户隔离三断言

  /**
   * 租户隔离三断言：
   *
   * <ol>
   *   <li>两租户互不可见（读、写、删都按当前租户过滤）；
   *   <li>伪造 {@code X-Tenant-Id} 头无效（租户只来自已认证的成员关系）；
   *   <li>连接池归还后不串租户（单连接反复交替）。
   * </ol>
   */
  @Test
  void tenantIsolationHoldsAcrossReadWriteAndPoolReuse() throws Exception {
    var mine = fixture(actor, "MINE");
    publish(actor, mine.versionId());
    var theirs = fixture(actorB, "THEIRS");
    publish(actorB, theirs.versionId());

    // 1) 两租户互不可见
    assertThatThrownBy(() -> tx(actor, () -> bundles.bundle(theirs.bundleId())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> bundles.bundle(mine.bundleId())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> bundles.version(theirs.versionId())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> bundles.components(theirs.versionId())))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(
            tx(
                    actor,
                    () ->
                        bundles.bundles(
                            new BundleQuery(mine.bundleId().toString() + "X", null, 0, 50)))
                .items())
        .isEmpty();
    // 反查方向同样隔离：B 拿不到「我的组件被哪些套装引用」。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        bundles.replaceComponents(
                            actor,
                            theirs.versionId(),
                            new BundleComponentsInput(
                                List.of(
                                    new BundleComponentInput(mine.skuA(), BigDecimal.ONE, "", "")),
                                0))))
        .isInstanceOf(ResponseStatusException.class);

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(
            get("/catalog/bundles/" + mine.bundleId())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/versions/" + mine.versionId())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 伪造头下写入仍然落在 B 租户自己的套装上，与 A 无关。
    http.perform(
            post("/catalog/bundles")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content("{\"code\":\"" + unique("BND") + "\",\"note\":\"\",\"version\":0}"))
        .andExpect(status().isOk());
    long aBundlesBefore =
        tx(actor, () -> bundles.bundles(new BundleQuery(null, null, 0, 200)).total());
    // 租户 B 现在有 2 个套装：夹具那个 + 伪造头下建的那个（写在了 B 自己名下）。
    assertThat(tx(actorB, () -> bundles.bundles(new BundleQuery(null, null, 0, 200)).total()))
        .isEqualTo(2);
    assertThat(tx(actor, () -> bundles.bundles(new BundleQuery(null, null, 0, 200)).total()))
        .isEqualTo(aBundlesBefore);

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> bundles.bundle(mine.bundleId()).bundle().id()))
          .isEqualTo(mine.bundleId());
      assertThat(tx(actorB, () -> bundles.bundle(theirs.bundleId()).bundle().id()))
          .isEqualTo(theirs.bundleId());
      assertThatThrownBy(() -> tx(actorB, () -> bundles.bundle(mine.bundleId())))
          .isInstanceOf(ResponseStatusException.class);
      assertThatThrownBy(() -> tx(actor, () -> bundles.bundle(theirs.bundleId())))
          .isInstanceOf(ResponseStatusException.class);
      assertThat(tx(actor, () -> bundles.version(mine.versionId())).version().name())
          .isEqualTo("套装 v1");
      assertThat(tx(actorB, () -> bundles.version(theirs.versionId())).version().name())
          .isEqualTo("套装 v1");
      assertThat(tx(actor, () -> bundles.bundles(new BundleQuery(null, null, 0, 200)).total()))
          .isEqualTo(aBundlesBefore);
      // 两边各自看到的套装集合互不相交：比「谁的数更大」更能证明没有串。
      var seenByA =
          tx(actor, () -> bundles.bundles(new BundleQuery(null, null, 0, 200)).items()).stream()
              .map(BundleRow::id)
              .collect(java.util.stream.Collectors.toSet());
      var seenByB =
          tx(actorB, () -> bundles.bundles(new BundleQuery(null, null, 0, 200)).items()).stream()
              .map(BundleRow::id)
              .collect(java.util.stream.Collectors.toSet());
      assertThat(seenByA).isNotEmpty();
      assertThat(seenByB).isNotEmpty();
      assertThat(java.util.Collections.disjoint(seenByA, seenByB)).isTrue();
      assertThat(tx(actor, () -> bundles.overview().bundleCount())).isEqualTo(seenByA.size());
    }
  }

  @Test
  void rlsPoliciesAreInPlaceOnAllThreeTables() {
    var tables =
        tx(
            actor,
            () ->
                sql.fetch(
                    "select relname,relrowsecurity,relforcerowsecurity from pg_class"
                        + " where relname in ('bundle','bundle_version','bundle_component')"
                        + " and relkind='r'"));
    assertThat(tables).hasSize(3);
    assertThat(tables)
        .allMatch(
            r ->
                Boolean.TRUE.equals(r.get("relforcerowsecurity", Boolean.class))
                    && Boolean.TRUE.equals(r.get("relrowsecurity", Boolean.class)));
    // 数量必须是 NUMERIC(19,6)，不是 double：订单展开与库存扣减都照着这个数走。
    org.jooq.Record quantityType =
        tx(
            actor,
            () ->
                sql.fetchOne(
                    "select numeric_precision p, numeric_scale s"
                        + " from information_schema.columns"
                        + " where table_name='bundle_component' and column_name='quantity'"));
    assertThat(quantityType.get("p", Integer.class)).isEqualTo(19);
    assertThat(quantityType.get("s", Integer.class)).isEqualTo(6);
  }
}
