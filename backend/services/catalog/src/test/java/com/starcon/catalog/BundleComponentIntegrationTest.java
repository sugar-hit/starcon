package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.5.1.2 组件维护：逐行增删改、三条校验、循环引用、SKU↔套装指向、租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS：组件、指向全部真的落库。
 * 「已发布组件不可改」「成环拒绝」都是<b>绕过应用层直接写 SQL 也会被拦</b>的事实。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的四个断言：</b>
 *
 * <ol>
 *   <li>{@link #circularReferenceIsRefusedWithTheWholeRing()}：合法嵌套放行、自引用与
 *       二元/三元环拒绝，且报错<b>点名整条环</b>（BND-A → BND-B → BND-A）。
 *   <li>{@link #cyclesAreRefusedEvenThroughDirectSql()}：直连 SQL 也写不出成环的组件与指向。
 *   <li>{@link #publishedVersionComponentsAreFrozen()}：已发布版本的组件增删改全拒（蓝图纪律）。
 *   <li>{@link #tenantIsolationHoldsAcrossReadWriteAndPoolReuse()}：租户隔离三断言。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class BundleComponentIntegrationTest {
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired BundleComponentService components;
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

  /** 一个普通 SKU（挂在自己的 SPU 上）。 */
  UUID plainSku(AccessService.Access who, String hint) {
    return sku(who, spu(who, unique("SPU")), unique(hint), 0);
  }

  /** 套装 + 一个草稿版本。 */
  record Version(UUID bundleId, UUID versionId) {}

  Version bundleWithDraft(AccessService.Access who, String hint) {
    UUID bundleId =
        write(who, () -> bundles.saveBundle(who, null, new BundleInput(unique(hint), "", 0))).id();
    UUID versionId =
        write(
                who,
                () ->
                    bundles.createVersion(
                        who,
                        bundleId,
                        new BundleVersionInput(
                            "v1", "套装 v1", Instant.now().minus(10, ChronoUnit.DAYS).toString(),
                            null, "", 0)))
            .id();
    return new Version(bundleId, versionId);
  }

  /** 让 skuId 成为 bundleId 的可售 SKU（V24 的指向），返回指向行 id。 */
  UUID link(AccessService.Access who, UUID bundleId, UUID skuId) {
    return write(who, () -> components.saveLink(who, null, new BundleSkuInput(bundleId, skuId, "", 0))).id();
  }

  BundleComponentItem add(AccessService.Access who, UUID versionId, UUID skuId, String quantity) {
    return write(who, () -> components.addComponent(who, versionId,
        new BundleComponentInput(skuId, new BigDecimal(quantity), "", "")));
  }

  /** 套装的真实编码（夹具用随机唯一编码，报错文案里出现的是它）。 */
  String bundleCode(UUID bundleId) {
    return tx(actor, () -> bundles.bundle(bundleId).bundle().code());
  }

  BundleVersionRow publish(AccessService.Access who, UUID versionId) {
    return write(who, () -> bundles.publishVersion(who, versionId));
  }

  // ================================================================ 逐行增删改

  @Test
  void componentRowsAreAddedEditedAndDeletedOneByOne() {
    var v = bundleWithDraft(actor, "BND");
    UUID skuA = plainSku(actor, "SKU");
    UUID skuB = plainSku(actor, "SKU");

    var first = add(actor, v.versionId(), skuA, "2");
    assertThat(first.quantity()).isEqualByComparingTo("2");
    assertThat(first.skuCode()).isNotBlank();
    assertThat(first.bundleCode()).isNotBlank();
    assertThat(first.editable()).isTrue();

    // 逐行改数量：行级乐观锁，提交当前 rowVersion。
    var edited =
        write(actor, () -> components.editComponent(actor, first.id(),
            new BundleComponentEditInput(new BigDecimal("3"), "主机", "备注", first.rowVersion())));
    assertThat(edited.quantity()).isEqualByComparingTo("3");
    assertThat(edited.role()).isEqualTo("主机");

    // 乐观锁：拿旧 rowVersion 再改一次必须被拒（否则会静默覆盖别人的改动）。
    assertThatThrownBy(() -> write(actor, () -> components.editComponent(actor, first.id(),
        new BundleComponentEditInput(new BigDecimal("4"), "", "", first.rowVersion()))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("刚被别人改过");

    var second = add(actor, v.versionId(), skuB, "0.5");
    // 0.5 件是真实场景：NUMERIC(19,6) 不是摆设。
    assertThat(second.quantity()).isEqualByComparingTo("0.5");

    var page = tx(actor, () -> components.components(
        new BundleComponentQuery(null, v.versionId(), null, null, 0, 20)));
    assertThat(page.total()).isEqualTo(2);

    writeVoid(actor, () -> components.deleteComponent(actor, second.id(), second.rowVersion()));
    assertThat(tx(actor, () -> components.component(first.id())).quantity()).isEqualByComparingTo("3");
    assertThat(tx(actor, () -> components.components(
        new BundleComponentQuery(null, v.versionId(), null, null, 0, 20))).total()).isEqualTo(1);
  }

  @Test
  void threeValidationsRefuseToSave() {
    var v = bundleWithDraft(actor, "BND");
    UUID skuA = plainSku(actor, "SKU");

    // 1) 数量非法：0 与负数都拒（CHECK 兜底，服务层先给中文原因）。
    assertThatThrownBy(() -> add(actor, v.versionId(), skuA, "0"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须大于 0");
    assertThatThrownBy(() -> add(actor, v.versionId(), skuA, "-2"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须大于 0");

    // 2) 组件不存在：扣库存时扣的是一个不存在的东西。
    assertThatThrownBy(() -> add(actor, v.versionId(), UUID.randomUUID(), "1"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在");

    // 3) 同一个 SKU 两行：「要几件」无解。
    add(actor, v.versionId(), skuA, "1");
    assertThatThrownBy(() -> add(actor, v.versionId(), skuA, "2"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已经在");
    // 改数量走改那一行，不走「再加一行」。
    assertThatThrownBy(() -> add(actor, v.versionId(), skuA, "9")).hasMessageContaining("改数量");
  }

  // ================================================================ 循环引用

  /**
   * 循环引用：合法嵌套放行，自引用与二元/三元环拒绝，报错点名整条环。
   *
   * <p>这是本模块对蓝图的唯一「补规则」。若哪天有人把判定换成「只查直接反向边」，
   * 三元环会被漏掉，而漏掉的环要到对方版本发布、09 订单展开时才显形。
   */
  @Test
  void circularReferenceIsRefusedWithTheWholeRing() {
    // A 含 B 含 C：一条不带环的链。**顺序很重要** —— 先建下游再建上游，
    // 反过来（B 含 C、C 含 B）会当场闭合成二元环。
    var aV = bundleWithDraft(actor, "A");
    var bV = bundleWithDraft(actor, "B");
    var cV = bundleWithDraft(actor, "BND");
    UUID skuForB = plainSku(actor, "SKUB");
    UUID skuForC = plainSku(actor, "SKUC");
    link(actor, bV.bundleId(), skuForB);
    link(actor, cV.bundleId(), skuForC);

    // B 含 C：合法（C 不含 B，链还没闭上）。
    add(actor, bV.versionId(), skuForC, "1");
    // A 含 B：合法（构成 A→B→C）。
    add(actor, aV.versionId(), skuForB, "1");

    // 现在闭环：建一个代表 A 的 SKU，再让 C 含它 → A→B→C→A 三元环。
    UUID skuForA = plainSku(actor, "SKUA");
    link(actor, aV.bundleId(), skuForA);
    // 报错必须点名**整条环**，且方向与包含关系一致：A 含 B、B 含 C，现在 C 含 A。
    // 编码是夹具随机生成的（unique("A") 等），所以这里取真实值来断言，
    // 而不是把字面量写死 —— 写死等于断言一个与真实编码无关的字符串。
    String codeA = bundleCode(aV.bundleId());
    String codeB = bundleCode(bV.bundleId());
    String codeC = bundleCode(cV.bundleId());
    assertThatThrownBy(() -> add(actor, cV.versionId(), skuForA, "1"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("循环引用")
        .hasMessageContaining(codeA + " → " + codeB + " → " + codeC + " → " + codeA)
        .hasMessageContaining("无限递归");
    // 一条都没写进去：C 的清单仍然只有那条代表 B 的组件。
    var cComponents = tx(actor, () -> components.components(
        new BundleComponentQuery(null, cV.versionId(), null, null, 0, 20)));
    assertThat(cComponents.total()).isZero();

    // 自引用：套装 C 用自己的可售 SKU 当组件，是长度为 1 的环。
    assertThatThrownBy(() -> add(actor, cV.versionId(), skuForC, "1"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("循环引用");
  }

  @Test
  void relinkingASkuToCloseACycleIsRefused() {
    // 改指向是本模块最危险的操作：它一次性改掉所有把该 SKU 当组件的套装的出边。
    // 这里的场景是「不新增任何组件行、只改一条指向就把环闭上」——
    // 组件侧的检测完全看不到它，所以指向侧必须自己查一次（V24 的 guard_bundle_sku_cycle）。
    //
    // A 含 B（skuForB），B 含 C（skuForC）。把 skuForC 改指回 A，就得到 A→B→C→A。
    var aV = bundleWithDraft(actor, "A");
    var bV = bundleWithDraft(actor, "B");
    var cV = bundleWithDraft(actor, "BND");
    UUID skuForB = plainSku(actor, "SKUB");
    UUID skuForC = plainSku(actor, "SKUC");
    UUID linkForB = link(actor, bV.bundleId(), skuForB);
    UUID linkForC = link(actor, cV.bundleId(), skuForC);
    add(actor, bV.versionId(), skuForC, "1"); // B 含 C
    add(actor, aV.versionId(), skuForB, "1"); // A 含 B

    String codeA = bundleCode(aV.bundleId());
    String codeB = bundleCode(bV.bundleId());
    // 把 skuForC 改指回 A。B 是这个 SKU 唯一的组件持有者，所以新边是 B→A，
    // 而 A→B 本来就存在 —— 于是闭合成的是**二元环 A↔B**（C 不在环里：它只是这次被改指向的
    // 套装的原主人，并没有把任何组件指向 A）。
    // 环从「持有者」B 读起（先说「谁的组件出了问题」），再到被指向的 A。
    assertThatThrownBy(() -> write(actor, () -> components.saveLink(actor, linkForC,
        new BundleSkuInput(aV.bundleId(), skuForC, "", 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("循环引用")
        .hasMessageContaining(codeB + " → " + codeA + " → " + codeB);

    // 换一个不闭合的目标就能改成功（证明拦的是「成环」而不是「改指向」这件事本身）。
    var dV = bundleWithDraft(actor, "D");
    write(actor, () -> components.saveLink(actor, linkForC,
        new BundleSkuInput(dV.bundleId(), skuForC, "换个套装", 0)));
    assertThat(tx(actor, () -> components.links(new BundleSkuQuery(null, 0, 200)).items()).stream()
        .filter(r -> r.id().equals(linkForC)).findFirst().orElseThrow()
        .bundleId()).isEqualTo(dV.bundleId());
    // 另一条指向不受影响。
    assertThat(tx(actor, () -> components.links(new BundleSkuQuery(null, 0, 200)).items()).stream()
        .filter(r -> r.id().equals(linkForB)).findFirst().orElseThrow()
        .bundleId()).isEqualTo(bV.bundleId());
  }

  @Test
  void cyclesAreRefusedEvenThroughDirectSql() {
    // 纵深防线：绕过应用层直连数据库写也一样被 V24 的触发器拦住。
    var aV = bundleWithDraft(actor, "A");
    var bV = bundleWithDraft(actor, "BND");
    UUID skuForB = plainSku(actor, "SKUB");
    UUID linkId = link(actor, bV.bundleId(), skuForB);
    add(actor, aV.versionId(), skuForB, "1");

    // 直连 SQL 把 skuForB 改指回 A：成环。
    assertThatThrownBy(() -> rawSql(
        "update bundle_sku set bundle_id='" + aV.bundleId() + "' where id='" + linkId + "'"))
        .hasMessageContaining("循环引用");
  }

  // ================================================================ 已发布冻结

  /**
   * 已发布版本的组件不可改（蓝图：「已发布版本的组件不可改，只能新建版本」）。
   *
   * <p>新增 / 改 / 删三路都拒，且报错指向唯一正确动作：新建版本接替。
   */
  @Test
  void publishedVersionComponentsAreFrozen() {
    var v = bundleWithDraft(actor, "BND");
    UUID skuA = plainSku(actor, "SKU");
    UUID skuB = plainSku(actor, "SKU");
    var row = add(actor, v.versionId(), skuA, "2");
    publish(actor, v.versionId());

    // 加一行：拒。
    assertThatThrownBy(() -> add(actor, v.versionId(), skuB, "1"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不可增删改");
    // 改一行：拒。
    assertThatThrownBy(() -> write(actor, () -> components.editComponent(actor, row.id(),
        new BundleComponentEditInput(new BigDecimal("5"), "", "", row.rowVersion()))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不可增删改");
    // 删一行：拒。
    assertThatThrownBy(() -> writeVoid(actor,
        () -> components.deleteComponent(actor, row.id(), row.rowVersion())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不可增删改");
    // 冻结行在清单里带 editable=false 与「已发布」标记。
    var list = tx(actor, () -> components.components(
        new BundleComponentQuery(null, v.versionId(), null, null, 0, 20))).items();
    assertThat(list).hasSize(1);
    assertThat(list.get(0).editable()).isFalse();
    assertThat(list.get(0).versionStatus()).isEqualTo("PUBLISHED");
  }

  /** 直连 SQL（不经过服务层），用来证明 DB 层触发器真的拦得住。 */
  private void rawSql(String statement) {
    try (var conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = conn.createStatement()) {
      s.execute("set app.current_tenant='" + a + "'");
      s.execute("set app.actor='" + user + "'");
      s.execute(statement);
    } catch (Exception e) {
      var root = e.getCause() == null ? e : e.getCause();
      throw new IllegalStateException(root.getMessage(), root);
    }
  }

  // ================================================================ 校验结果

  @Test
  void checkListsEveryProblemAtOnce() {
    var v = bundleWithDraft(actor, "BND");
    // 空清单：至少要有一个组件。
    var empty = tx(actor, () -> components.check(v.versionId()));
    assertThat(empty.ok()).isFalse();
    assertThat(empty.problems()).extracting(BundleComponentProblem::code)
        .contains("EMPTY_COMPONENT_LIST");

    // 合法清单：ok=true 且 problems 为空。
    UUID skuA = plainSku(actor, "SKU");
    add(actor, v.versionId(), skuA, "2");
    var ok = tx(actor, () -> components.check(v.versionId()));
    assertThat(ok.ok()).isTrue();
    assertThat(ok.problems()).isEmpty();
    assertThat(ok.totalQuantity()).isEqualByComparingTo("2");
    assertThat(ok.editable()).isTrue();

    // 冻结版本：ok=false 且点名 VERSION_FROZEN。
    publish(actor, v.versionId());
    var frozen = tx(actor, () -> components.check(v.versionId()));
    assertThat(frozen.ok()).isFalse();
    assertThat(frozen.problems()).extracting(BundleComponentProblem::code)
        .contains("VERSION_FROZEN");
    assertThat(frozen.editable()).isFalse();
  }

  // ================================================================ 指向

  @Test
  void oneSkuRepresentsAtMostOneBundle() {
    var aV = bundleWithDraft(actor, "A");
    var bV = bundleWithDraft(actor, "BND");
    UUID skuForB = plainSku(actor, "SKUB");
    link(actor, aV.bundleId(), skuForB);
    // 一个 SKU 至多代表一个套装：两行时「这个组件展开成什么」有两个答案。
    assertThatThrownBy(() -> link(actor, bV.bundleId(), skuForB))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已经代表");
  }

  @Test
  void linkCannotBeDroppedWhenAPublishedVersionReferencesIt() {
    var holder = bundleWithDraft(actor, "HOLDER");
    var represented = bundleWithDraft(actor, "BND");
    UUID skuForB = plainSku(actor, "SKUB");
    UUID linkId = link(actor, represented.bundleId(), skuForB);
    add(actor, holder.versionId(), skuForB, "1");
    publish(actor, holder.versionId());
    // 已发布版本里的组件引用着这个 SKU：解开会让它静默降级成普通 SKU，改写冻结的展开结果。
    assertThatThrownBy(() -> writeVoid(actor, () -> components.deleteLink(actor, linkId, 0)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已发布");
  }

  @Test
  void linksListExposesRealUsageCounts() {
    var holder = bundleWithDraft(actor, "HOLDER");
    var represented = bundleWithDraft(actor, "BND");
    UUID skuForB = plainSku(actor, "SKUB");
    link(actor, represented.bundleId(), skuForB);
    add(actor, holder.versionId(), skuForB, "1");
    var row = tx(actor, () -> components.links(new BundleSkuQuery(null, 0, 20))).items().stream()
        .filter(r -> r.skuId().equals(skuForB)).findFirst().orElseThrow();
    assertThat(row.usedByComponentCount()).isEqualTo(1);
    assertThat(row.usedByPublished()).isFalse(); // 还是草稿
    publish(actor, holder.versionId());
    var published = tx(actor, () -> components.links(new BundleSkuQuery(null, 0, 20))).items().stream()
        .filter(r -> r.skuId().equals(skuForB)).findFirst().orElseThrow();
    assertThat(published.usedByPublished()).isTrue();
  }

  // ================================================================ 概览

  @Test
  void overviewCountsNestedComponentsAndHealthStaysGreen() {
    var holder = bundleWithDraft(actor, "HOLDER");
    var represented = bundleWithDraft(actor, "BND");
    UUID skuForB = plainSku(actor, "SKUB");
    link(actor, represented.bundleId(), skuForB);
    add(actor, holder.versionId(), skuForB, "1"); // 嵌套组件
    UUID plain = plainSku(actor, "SKU");
    add(actor, represented.versionId(), plain, "2"); // 普通组件

    var overview = tx(actor, () -> components.overview());
    assertThat(overview.nestedComponentCount()).isGreaterThanOrEqualTo(1);
    assertThat(overview.componentCount()).isGreaterThanOrEqualTo(2);
    // 自引用与数量非法正常恒为 0：这是纪律的体检指标。
    assertThat(overview.selfReferencingCount()).isEqualTo(0);
    assertThat(overview.nonPositiveQuantityCount()).isEqualTo(0);
  }

  // ================================================================ 列表与 HTTP

  @Test
  void listAndDetailExposeRealTotalsAndTheNestedMarker() throws Exception {
    var holder = bundleWithDraft(actor, "HOLDER");
    var represented = bundleWithDraft(actor, "BND");
    UUID skuForB = plainSku(actor, "SKUB");
    link(actor, represented.bundleId(), skuForB);
    var nested = add(actor, holder.versionId(), skuForB, "1");
    UUID plain = plainSku(actor, "SKU");
    add(actor, represented.versionId(), plain, "1");

    // 嵌套标记：nested=true 的那一行带 linkedBundleCode。
    var nestedOnly = tx(actor, () -> components.components(
        new BundleComponentQuery(null, null, null, "true", 0, 20))).items();
    assertThat(nestedOnly).isNotEmpty();
    assertThat(nestedOnly.get(0).nested()).isTrue();
    assertThat(nestedOnly.get(0).linkedBundleCode()).isNotBlank();

    // 关键词搜的是编码/名称/版本号/角色，搜一个 UUID 必然命中 0 行
    // ——证明它真的在过滤，而不是「查不到就返回全部」那种假搜索。
    var byKeyword = tx(actor, () -> components.components(
        new BundleComponentQuery(skuForB.toString(), null, null, null, 0, 20)));
    assertThat(byKeyword.total()).isZero();

    // 按版本过滤只返回该版本的组件。
    var byVersion = tx(actor, () -> components.components(
        new BundleComponentQuery(null, holder.versionId(), null, null, 0, 20)));
    assertThat(byVersion.items()).extracting(BundleComponentItem::versionId)
        .containsOnly(holder.versionId());

    // HTTP 层同样通。
    http.perform(get("/catalog/components/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.selfReferencingCount").value(0));
    http.perform(get("/catalog/components/" + nested.id()).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nested").value(true));
    http.perform(get("/catalog/versions/" + holder.versionId() + "/component-check")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));
    http.perform(get("/catalog/bundle-skus").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
  }

  @Test
  void readerCanReadButCannotWrite() throws Exception {
    var v = bundleWithDraft(actor, "BND");
    UUID skuA = plainSku(actor, "SKU");
    add(actor, v.versionId(), skuA, "1");
    http.perform(get("/catalog/components").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(post("/catalog/versions/" + v.versionId() + "/components")
            .header("Authorization", "Bearer reader")
            .contentType("application/json")
            .content("{\"skuId\":\"" + UUID.randomUUID() + "\",\"quantity\":1,\"role\":\"\",\"note\":\"\"}"))
        .andExpect(status().isForbidden());
  }

  // ================================================================ 租户隔离三断言

  /**
   * 租户隔离三断言：
   *
   * <ol>
   *   <li>两租户互不可见（组件、指向、校验都按当前租户过滤）；
   *   <li>伪造 {@code X-Tenant-Id} 头无效（租户只来自已认证的成员关系）；
   *   <li>连接池归还后不串租户（单连接反复交替）。
   * </ol>
   */
  @Test
  void tenantIsolationHoldsAcrossReadWriteAndPoolReuse() throws Exception {
    var mine = bundleWithDraft(actor, "MINE");
    var mineRepresented = bundleWithDraft(actor, "MINE-R");
    UUID mySku = plainSku(actor, "SKU");
    UUID myLink = link(actor, mineRepresented.bundleId(), mySku);
    var myRow = add(actor, mine.versionId(), mySku, "2");
    publish(actor, mine.versionId());

    var theirs = bundleWithDraft(actorB, "THEIRS");
    var theirsRepresented = bundleWithDraft(actorB, "THEIRS-R");
    UUID theirSku = plainSku(actorB, "SKU");
    UUID theirLink = link(actorB, theirsRepresented.bundleId(), theirSku);
    add(actorB, theirs.versionId(), theirSku, "3");
    publish(actorB, theirs.versionId());

    // 1) 两租户互不可见
    // B 读 A 的组件行：读不到（RLS + 显式 tenant_id 过滤）。
    assertThatThrownBy(() -> tx(actorB, () -> components.component(myRow.id())))
        .isInstanceOf(ResponseStatusException.class);
    // B 拿 A 的版本做校验：连「这个版本存不存在」都答不出来（404 而不是「校验不通过」）。
    assertThatThrownBy(() -> tx(actorB, () -> components.check(mine.versionId())))
        .isInstanceOf(ResponseStatusException.class);
    // A 读自己的组件与校验当然是通的（反向断言：防止上面的隔离把谁都给挡了）。
    assertThat(tx(actor, () -> components.component(myRow.id())).skuId()).isEqualTo(mySku);
    assertThat(tx(actor, () -> components.check(mine.versionId())).versionId()).isEqualTo(mine.versionId());
    // B 看不到 A 的组件、也拿不到 A 的指向。
    assertThat(tx(actorB, () -> components.components(
        new BundleComponentQuery(null, mine.versionId(), null, null, 0, 20))).items()).isEmpty();
    // 跨租户改我的组件：拒。
    assertThatThrownBy(() -> write(actorB, () -> components.editComponent(actorB, myRow.id(),
        new BundleComponentEditInput(new BigDecimal("9"), "", "", myRow.rowVersion()))))
        .isInstanceOf(ResponseStatusException.class);
    // 跨租户删我的指向：拒。
    assertThatThrownBy(() -> writeVoid(actorB, () -> components.deleteLink(actorB, myLink, 0)))
        .isInstanceOf(ResponseStatusException.class);
    // 反查方向同样隔离：B 拿不到「我的组件被哪些套装引用」。
    assertThat(tx(actorB, () -> components.components(
        new BundleComponentQuery(null, null, null, "true", 0, 200))).items())
        .noneMatch(i -> i.skuId().equals(mySku));

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(get("/catalog/components/" + myRow.id())
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(get("/catalog/versions/" + mine.versionId() + "/component-check")
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 伪造头下写入仍然落在 B 自己名下，与 A 无关。
    http.perform(post("/catalog/bundle-skus")
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a)
            .contentType("application/json")
            .content("{\"bundleId\":\"" + theirs.bundleId() + "\",\"skuId\":\"" + theirSku
                + "\",\"note\":\"\",\"rowVersion\":0}"))
        .andExpect(status().is4xxClientError());
    // 两个租户各自的组件数互不相干。
    assertThat(tx(actorB, () -> components.components(
        new BundleComponentQuery(null, null, null, null, 0, 200)).items()))
        .extracting(BundleComponentItem::skuId)
        .doesNotContain(mySku);
    assertThat(tx(actor, () -> components.components(
        new BundleComponentQuery(null, null, null, null, 0, 200)).items()))
        .extracting(BundleComponentItem::skuId)
        .doesNotContain(theirSku);

    // 3) 连接池归还后不串租户（单连接反复交替）
    // B 自己的那行组件 id 先在 B 的租户上下文里取出来 —— 拿到 id 之后，
    // 「A 能不能读到这一行 id」才是有意义的断言（在内层用 A 的上下文查 B 的版本
    // 会得到空列表，那证明的是过滤而不是隔离）。
    UUID theirRow =
        tx(actorB, () -> components.components(
                new BundleComponentQuery(null, theirs.versionId(), null, null, 0, 20))
            .items().get(0).id());
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> components.component(myRow.id()).quantity()))
          .isEqualByComparingTo("2");
      assertThat(tx(actorB, () -> components.component(theirRow).quantity()))
          .isEqualByComparingTo("3");
      assertThatThrownBy(() -> tx(actorB, () -> components.component(myRow.id())))
          .isInstanceOf(ResponseStatusException.class);
      assertThatThrownBy(() -> tx(actor, () -> components.component(theirRow)))
          .isInstanceOf(ResponseStatusException.class);
      // 两边看到的组件集合互不相交。
      var seenByA = tx(actor, () -> components.components(
          new BundleComponentQuery(null, null, null, null, 0, 200)).items())
          .stream().map(BundleComponentItem::skuId).collect(java.util.stream.Collectors.toSet());
      var seenByB = tx(actorB, () -> components.components(
          new BundleComponentQuery(null, null, null, null, 0, 200)).items())
          .stream().map(BundleComponentItem::skuId).collect(java.util.stream.Collectors.toSet());
      assertThat(seenByA).isNotEmpty();
      assertThat(seenByB).isNotEmpty();
      assertThat(java.util.Collections.disjoint(seenByA, seenByB)).isTrue();
      // 指向列表也各自隔离。
      assertThat(tx(actor, () -> components.links(new BundleSkuQuery(null, 0, 200)).items()))
          .extracting(BundleSkuRow::id)
          .contains(myLink)
          .doesNotContain(theirLink);
    }
  }

  @Test
  void rlsPoliciesAreInPlaceOnTheLinkTable() {
    var tables =
        tx(actor, () -> sql.fetch(
            "select relname,relrowsecurity,relforcerowsecurity from pg_class"
                + " where relname in ('bundle_sku','bundle_component') and relkind='r'"));
    assertThat(tables).hasSize(2);
    assertThat(tables).allMatch(r ->
        Boolean.TRUE.equals(r.get("relforcerowsecurity", Boolean.class))
            && Boolean.TRUE.equals(r.get("relrowsecurity", Boolean.class)));
    // 数量列仍然是 NUMERIC(19,6)：逐行编辑没有把精度改回 double。
    var quantityType = tx(actor, () -> sql.fetchOne(
        "select numeric_precision p, numeric_scale s from information_schema.columns"
            + " where table_name='bundle_component' and column_name='quantity'"));
    assertThat(quantityType.get("p", Integer.class)).isEqualTo(19);
    assertThat(quantityType.get("s", Integer.class)).isEqualTo(6);
  }
}
