package com.starcon.catalog;

import static com.starcon.catalog.BrandRules.*;
import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.3.2.1 品牌管理：品牌字典 CRUD、多语言名（按语言唯一）、Logo、授权状态、关联类目、 授权凭证、**阻止刊登判定**（蓝图点名的业务规则），以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BrandIntegrationTest {
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
      // 只有商品读权限：写端点必须 403（品牌沿用商品主数据域权限）。
      actors.put(
          "Bearer reader",
          actor(
              user,
              a,
              null,
              List.of(grant("ALL", List.of("catalog.spu.read", "catalog.attribute.read")))));
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
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired BrandService brands;
  @Autowired CategoryTreeService categories;
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

  // ------------------------------------------------------------ 夹具

  static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  String uniqueCode(String hint) {
    return hint
        + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  UUID tree(AccessService.Access who, String kind, String platformCode) {
    return write(
            who,
            () ->
                categories.saveTree(
                    who,
                    null,
                    new CategoryTreeInput(uniqueCode("TREE"), "分类树", kind, platformCode, true, 0)))
        .id();
  }

  UUID category(AccessService.Access who, UUID treeId, UUID parentId, String name) {
    return write(
            who,
            () ->
                categories.save(
                    who,
                    treeId,
                    null,
                    new CategoryInput(uniqueCode("CAT"), name, parentId, null, true, List.of(), 0)))
        .id();
  }

  /** 建一个品牌。status 缺省 OWNED（自有，恒可刊登），这样夹具不会意外制造阻断状态。 */
  UUID brand(
      AccessService.Access who,
      String name,
      String status,
      List<BrandNameInput> names,
      List<BrandCategoryLinkInput> links) {
    String code = uniqueCode("BR");
    // Logo 引用取编码而不是名字：名字里可能带空格，而引用不能（未编码的空格不是合法 URI）。
    return write(
            who,
            () ->
                brands.save(
                    who,
                    null,
                    new BrandInput(
                        code, name, "logos/" + code + ".png", status, true, names, links, 0)))
        .id();
  }

  BrandSaved credential(
      AccessService.Access who, UUID brandId, LocalDate from, LocalDate to, boolean enabled) {
    return write(
        who,
        () ->
            brands.saveAuthorization(
                who,
                brandId,
                null,
                new BrandAuthorizationInput(
                    uniqueCode("CERT"),
                    "品牌权利人",
                    from.toString(),
                    to == null ? null : to.toString(),
                    "evidence/" + UUID.randomUUID() + ".pdf",
                    "测试凭证",
                    enabled,
                    0)));
  }

  // ------------------------------------------------------------ 品牌字典 CRUD 与唯一性

  @Test
  void brandDictionaryKeepsNameLocaleLogoAndUniqueness() {
    UUID owned =
        brand(
            actor,
            "自有品牌",
            "OWNED",
            List.of(
                new BrandNameInput("zh-CN", "自有品牌"), new BrandNameInput("en-US", "Owned Brand")),
            List.of());
    var detail = tx(actor, () -> brands.detail(owned));
    assertThat(detail.code()).startsWith("BR");
    assertThat(detail.logoUri()).isEqualTo("logos/" + detail.code() + ".png");
    // locale 落库前统一小写（zh-CN 与 zh-cn 是同一个语言，不允许两条各存一份）。
    assertThat(detail.names()).containsEntry("zh-cn", "自有品牌").containsEntry("en-us", "Owned Brand");
    assertThat(detail.enabled()).isTrue();

    // locale 规范化后重复必须被拒：zh-CN 与 zh-cn 是同一个语言，不允许两条。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        brands.save(
                            actor,
                            owned,
                            brandInput(
                                detail,
                                List.of(
                                    new BrandNameInput("zh-CN", "甲"),
                                    new BrandNameInput("zh-cn", "乙"))))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
    assertThat(tx(actor, () -> brands.detail(owned)).names()).containsOnlyKeys("zh-cn", "en-us");

    // 编码建后不可改（刊登与侵权检测按 code 对齐）。状态码与 3.3.1.1 的分类编码同一口径：
    // 不可改字段是 400（提交的内容不合法），409 留给 version 乐观锁与唯一键冲突。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        brands.save(
                            actor,
                            owned,
                            new BrandInput(
                                "OTHERCODE1",
                                "改名",
                                null,
                                "OWNED",
                                true,
                                List.of(),
                                List.of(),
                                detail.version()))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));

    // 同租户编码重复：新建时 409，不让 500 冒到界面上。
    String code = uniqueCode("DUP");
    write(
        actor,
        () ->
            brands.save(
                actor,
                null,
                new BrandInput(code, "先建", null, "OWNED", true, List.of(), List.of(), 0)));
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        brands.save(
                            actor,
                            null,
                            new BrandInput(
                                code, "后建", null, "OWNED", true, List.of(), List.of(), 0))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));

    // 编码与授权状态校验（纯规则，出口是可执行的原因而不是约束名）。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        brands.save(
                            actor,
                            null,
                            new BrandInput(
                                "中文编码", "非法编码", null, "OWNED", true, List.of(), List.of(), 0))))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        brands.save(
                            actor,
                            null,
                            new BrandInput(
                                uniqueCode("OK"),
                                "非法状态",
                                null,
                                "BORROWED",
                                true,
                                List.of(),
                                List.of(),
                                0))))
        .isInstanceOf(ResponseStatusException.class);
  }

  private static BrandInput brandInput(BrandDetail detail, List<BrandNameInput> names) {
    return new BrandInput(
        detail.code(),
        detail.name(),
        detail.logoUri(),
        detail.authorizationStatus(),
        detail.enabled(),
        names,
        List.of(),
        detail.version());
  }

  @Test
  void enabledStateAndVersionLockBothHold() {
    UUID id = brand(actor, "启停品牌", "OWNED", List.of(), List.of());
    long version = tx(actor, () -> brands.detail(id)).version();

    writeVoid(actor, () -> brands.setEnabled(actor, id, false));
    var disabled = tx(actor, () -> brands.detail(id));
    assertThat(disabled.enabled()).isFalse();
    assertThat(disabled.version()).isGreaterThan(version);

    // 乐观锁：拿旧 version 保存必须被拒。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        brands.save(
                            actor,
                            id,
                            new BrandInput(
                                disabled.code(),
                                "并发改",
                                null,
                                "OWNED",
                                false,
                                List.of(),
                                List.of(),
                                version))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));

    writeVoid(actor, () -> brands.setEnabled(actor, id, true));
    assertThat(tx(actor, () -> brands.detail(id)).enabled()).isTrue();

    // 删除要带对 version；带错 version 必须被拒。
    long current = tx(actor, () -> brands.detail(id)).version();
    assertThatThrownBy(() -> writeVoid(actor, () -> brands.delete(actor, id, current + 5)))
        .isInstanceOf(ResponseStatusException.class);
    writeVoid(actor, () -> brands.delete(actor, id, current));
    assertThatThrownBy(() -> tx(actor, () -> brands.detail(id)))
        .isInstanceOf(ResponseStatusException.class);
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

  // ------------------------------------------------------------ 关联类目（用真实外键指向 3.3.1.1）

  @Test
  void categoriesLinkToRealCategoryTreeAndFollowRenames() {
    UUID t = tree(actor, "INTERNAL", null);
    UUID apparel = category(actor, t, null, "服饰");
    UUID tops = category(actor, t, apparel, "上装");
    UUID id =
        brand(actor, "关联品牌", "OWNED", List.of(), List.of(new BrandCategoryLinkInput(tops, null)));

    var detail = tx(actor, () -> brands.detail(id));
    assertThat(detail.categories()).hasSize(1);
    assertThat(detail.categories().getFirst().categoryId()).isEqualTo(tops);
    // 路径串来自 3.3.1.1 的祖先链，不是本模块拼的文本。
    assertThat(detail.categories().getFirst().categoryPath()).isEqualTo("服饰 / 上装");

    // 列表行也带类目名与条数（同一份口径，不让前端再打一次详情）。
    var row =
        tx(actor, () -> brands.list(new BrandQuery(null, null, null, null, null, 0, 200)))
            .items()
            .stream()
            .filter(r -> r.id().equals(id))
            .findFirst()
            .orElseThrow();
    assertThat(row.categoryCount()).isEqualTo(1);
    assertThat(row.categoryNames()).isEqualTo("上装");

    // 重命名类目后，关联上的显示名自动跟着变（关联指向主键，不是快照）。
    long topsVersion =
        tx(actor, () -> categories.tree(t, true)).stream()
            .filter(n -> n.id().equals(tops))
            .findFirst()
            .orElseThrow()
            .version();
    write(
        actor,
        () ->
            categories.save(
                actor,
                t,
                tops,
                new CategoryInput(
                    detail.categories().getFirst().categoryCode(),
                    "短袖",
                    apparel,
                    0,
                    true,
                    List.of(),
                    topsVersion)));
    assertThat(tx(actor, () -> brands.detail(id)).categories().getFirst().categoryName())
        .isEqualTo("短袖");

    // 按类目筛选走的是 brand_category 的复合外键关系，不是字符串比。
    assertThat(
            tx(actor, () -> brands.list(new BrandQuery(null, null, null, null, tops, 0, 200)))
                .items())
        .extracting(BrandRow::id)
        .contains(id);
    assertThat(
            tx(actor, () -> brands.list(new BrandQuery(null, null, null, null, apparel, 0, 200)))
                .items())
        .extracting(BrandRow::id)
        .doesNotContain(id);

    // 不存在或不属于本租户的类目：关联必须被拒。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        brands.save(
                            actor,
                            id,
                            new BrandInput(
                                row.code(),
                                "关联品牌",
                                null,
                                "OWNED",
                                true,
                                List.of(),
                                List.of(new BrandCategoryLinkInput(UUID.randomUUID(), null)),
                                row.version()))))
        .isInstanceOf(ResponseStatusException.class);

    // 整体替换语义：提交空列表等于清空关联（不是增量追加）。
    write(
        actor,
        () ->
            brands.save(
                actor,
                id,
                new BrandInput(row.code(), "关联品牌", null, "OWNED", true, List.of(), List.of(), 0)));
    assertThat(tx(actor, () -> brands.detail(id)).categories()).isEmpty();
  }

  @Test
  void disabledCategoryCannotBeLinkedButExistingLinkSurvives() {
    UUID t = tree(actor, "INTERNAL", null);
    UUID c = category(actor, t, null, "待停用类目");
    UUID id = brand(actor, "品牌", "OWNED", List.of(), List.of(new BrandCategoryLinkInput(c, null)));

    // 停用类目不允许**新增**关联：新建一个指向停用类目的关联没有意义。
    writeVoid(actor, () -> categories.setEnabled(actor, c, false));
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        brands.save(
                            actor,
                            id,
                            new BrandInput(
                                uniqueCode("X"),
                                "品牌",
                                null,
                                "OWNED",
                                true,
                                List.of(),
                                List.of(new BrandCategoryLinkInput(c, null)),
                                0))))
        .isInstanceOf(ResponseStatusException.class);

    // 但已有关联保留，且照样显示（运营要看见「这个品牌挂在哪个已停用的类目上」）。
    var links = tx(actor, () -> brands.detail(id)).categories();
    assertThat(links).hasSize(1);
    assertThat(links.getFirst().categoryId()).isEqualTo(c);
  }

  // ------------------------------------------------------------ 阻止刊登（蓝图点名的业务规则）

  @Test
  void licensedBrandWithoutValidCredentialIsBlockedFromListing() {
    UUID licensed = brand(actor, "需授权品牌", "LICENSED", List.of(), List.of());

    // 关键断言：判定在**建档时**不做拦截——「先登记成需授权、凭证稍后补」是正常业务流。
    // 被拒的应该是刊登，不是建档。
    assertThat(tx(actor, () -> brands.eligibility(licensed)).publishable()).isFalse();
    assertThat(tx(actor, () -> brands.eligibility(licensed)).blockCode())
        .isEqualTo(BrandRules.BLOCK_LICENSE_MISSING);
    assertThat(tx(actor, () -> brands.detail(licensed)).publishBlockMessage()).contains("没有任何授权凭证");

    // 凭证已存在但尚未到生效日 → 未生效（等一等就能刊登，两个下游处理动作不同）。
    credential(actor, licensed, today().plusDays(30), today().plusDays(400), true);
    assertThat(tx(actor, () -> brands.eligibility(licensed)).blockCode())
        .isEqualTo(BrandRules.BLOCK_LICENSE_NOT_YET_EFFECTIVE);

    // 补一条已生效的凭证 → 立刻可刊登。
    credential(actor, licensed, today().minusDays(10), today().plusDays(200), true);
    assertThat(tx(actor, () -> brands.eligibility(licensed)).publishable()).isTrue();

    // 把所有凭证都挪到过去 → 全部过期，判定给出 EXPIRED 而不是 MISSING。
    var list = tx(actor, () -> brands.authorizations(licensed));
    for (var each : list) {
      write(
          actor,
          () ->
              brands.saveAuthorization(
                  actor,
                  licensed,
                  each.id(),
                  new BrandAuthorizationInput(
                      each.certificateNo(),
                      each.grantor(),
                      today().minusDays(400).toString(),
                      today().minusDays(200).toString(),
                      each.evidenceUri(),
                      each.note(),
                      true,
                      each.version())));
    }
    assertThat(tx(actor, () -> brands.eligibility(licensed)).blockCode())
        .isEqualTo(BrandRules.BLOCK_LICENSE_EXPIRED);
    assertThat(tx(actor, () -> brands.eligibility(licensed)).publishable()).isFalse();

    // 撤销（enabled=false）等于无效：行仍在，可查「曾经有过授权、后来被撤销」。
    var expired = tx(actor, () -> brands.authorizations(licensed));
    for (var each : expired) {
      write(
          actor,
          () ->
              brands.saveAuthorization(
                  actor,
                  licensed,
                  each.id(),
                  new BrandAuthorizationInput(
                      each.certificateNo(),
                      each.grantor(),
                      each.validFrom(),
                      each.validTo(),
                      each.evidenceUri(),
                      each.note(),
                      false,
                      each.version())));
    }
    assertThat(tx(actor, () -> brands.authorizations(licensed))).hasSize(expired.size());
    assertThat(tx(actor, () -> brands.eligibility(licensed)).blockCode())
        .isEqualTo(BrandRules.BLOCK_LICENSE_EXPIRED);
  }

  @Test
  void ownedAndAgencyBrandsAlwaysPublishAndDisabledBrandNeverDoes() {
    LocalDate past = today().minusDays(10);
    UUID owned = brand(actor, "自有", "OWNED", List.of(), List.of());
    UUID agency = brand(actor, "代理", "AGENCY", List.of(), List.of());
    // 自有与代理是「本身已有授权」的状态：不给它们伪造凭证，刊登也必须放行。
    assertThat(tx(actor, () -> brands.eligibility(owned)).publishable()).isTrue();
    assertThat(tx(actor, () -> brands.eligibility(agency)).publishable()).isTrue();
    assertThat(tx(actor, () -> brands.eligibility(owned)).blockCode()).isNull();
    credential(actor, owned, past, past, false);
    assertThat(tx(actor, () -> brands.eligibility(owned)).publishable()).isTrue();

    // 停用的品牌在任何授权状态下都登不了——否则「停用」只剩界面上的视觉意义。
    writeVoid(actor, () -> brands.setEnabled(actor, owned, false));
    assertThat(tx(actor, () -> brands.eligibility(owned)).blockCode())
        .isEqualTo(BrandRules.BLOCK_BRAND_DISABLED);

    // 长期有效凭证（validTo 为空）在有效期内恒有效，剩余天数按 0 给（没有终止日可算）。
    UUID licensed = brand(actor, "长期授权", "LICENSED", List.of(), List.of());
    credential(actor, licensed, past, null, true);
    var valid = tx(actor, () -> brands.authorizations(licensed)).getFirst();
    assertThat(valid.validTo()).isNull();
    assertThat(valid.validOn()).isTrue();
    assertThat(valid.daysToExpiry()).isZero();
    assertThat(tx(actor, () -> brands.eligibility(licensed)).publishable()).isTrue();
  }

  @Test
  void authorizationStatusChangeFlipsListingEligibilityImmediately() {
    UUID id = brand(actor, "转型品牌", "OWNED", List.of(), List.of());
    assertThat(tx(actor, () -> brands.eligibility(id)).publishable()).isTrue();
    // 自有 → 需授权：运营改状态就是想表达「从现在起需要凭证」，刊登必须立刻被拦住。
    var detail = tx(actor, () -> brands.detail(id));
    write(
        actor,
        () ->
            brands.save(
                actor,
                id,
                new BrandInput(
                    detail.code(),
                    detail.name(),
                    null,
                    "LICENSED",
                    true,
                    List.of(),
                    List.of(),
                    detail.version())));
    assertThat(tx(actor, () -> brands.eligibility(id)).publishable()).isFalse();
    // 补上凭证后放行。
    credential(actor, id, today().minusDays(1), today().plusDays(30), true);
    assertThat(tx(actor, () -> brands.eligibility(id)).publishable()).isTrue();
  }

  @Test
  void duplicateCertificateNumberAndReversedValidityAreRejected() {
    UUID id = brand(actor, "凭证校验", "LICENSED", List.of(), List.of());
    String no = uniqueCode("CERT");
    write(
        actor,
        () ->
            brands.saveAuthorization(
                actor,
                id,
                null,
                new BrandAuthorizationInput(
                    no,
                    "授权方",
                    today().toString(),
                    today().plusDays(90).toString(),
                    null,
                    null,
                    true,
                    0)));
    // 同一品牌下凭证编号唯一：重复导入同一份合约会让「有几条有效凭证」翻倍。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        brands.saveAuthorization(
                            actor,
                            id,
                            null,
                            new BrandAuthorizationInput(
                                no, "授权方", today().toString(), null, null, null, true, 0))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));

    // 失效早于生效：写进库会让有效区间显示成倒着的。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        brands.saveAuthorization(
                            actor,
                            id,
                            null,
                            new BrandAuthorizationInput(
                                uniqueCode("BAD"),
                                "授权方",
                                today().plusDays(10).toString(),
                                today().toString(),
                                null,
                                null,
                                true,
                                0))))
        .isInstanceOf(ResponseStatusException.class);
    // 日期格式错误也要给可读原因。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        brands.saveAuthorization(
                            actor,
                            id,
                            null,
                            new BrandAuthorizationInput(
                                uniqueCode("BAD"),
                                "授权方",
                                "2026/12/31",
                                null,
                                null,
                                null,
                                true,
                                0))))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void credentialsDoNotCascadeAwayWhenCategoryIsDeleted() {
    // 关联类目的 CASCADE 只影响 brand_category，不该碰凭证：
    // 凭证是品牌层面的合规事实，与某个类目被删无关。
    UUID t = tree(actor, "INTERNAL", null);
    UUID c = category(actor, t, null, "临时类目");
    UUID id =
        brand(actor, "凭证留存", "LICENSED", List.of(), List.of(new BrandCategoryLinkInput(c, null)));
    credential(actor, id, today().minusDays(1), today().plusDays(100), true);
    assertThat(tx(actor, () -> brands.detail(id)).categories()).hasSize(1);

    // 直接删类目（服务层删节点会要求无子分类无商品，这里是根节点可直接删）。
    writeVoid(actor, () -> sql.execute("delete from product_category where id=?", c));
    var detail = tx(actor, () -> brands.detail(id));
    assertThat(detail.categories()).isEmpty();
    assertThat(detail.authorizations()).hasSize(1);
    assertThat(detail.publishable()).isTrue();
  }

  // ------------------------------------------------------------ 列表：分页 / 搜索 / 筛选

  @Test
  void listPagesSearchesAndFiltersOnRealRows() {
    String mark = uniqueCode("MK");
    UUID owned = brand(actor, mark + "-自有", "OWNED", List.of(), List.of());
    UUID licensed =
        brand(
            actor,
            mark + "-需授权",
            "LICENSED",
            List.of(new BrandNameInput("ja-JP", "テストブランド" + mark)),
            List.of());
    UUID blocked = brand(actor, mark + "-停用", "LICENSED", List.of(), List.of());
    writeVoid(actor, () -> brands.setEnabled(actor, blocked, false));

    // 关键词命中编码、名称与多语言名三条路。
    assertThat(
            tx(actor, () -> brands.list(new BrandQuery(mark, null, null, null, null, 0, 200)))
                .items())
        .hasSize(3);
    assertThat(
            tx(actor, () -> brands.list(new BrandQuery("テストブランド", null, null, null, null, 0, 200)))
                .items())
        .extracting(BrandRow::id)
        .containsExactly(licensed);
    // LIKE 通配符按字面处理，不允许 % 把整表捞出来。
    assertThat(
            tx(actor, () -> brands.list(new BrandQuery("%", null, null, null, null, 0, 200)))
                .items())
        .isEmpty();

    assertThat(
            tx(actor, () -> brands.list(new BrandQuery(mark, "OWNED", null, null, null, 0, 200)))
                .items())
        .extracting(BrandRow::id)
        .containsExactly(owned);
    assertThat(
            tx(
                    actor,
                    () -> brands.list(new BrandQuery(mark, null, Boolean.TRUE, null, null, 0, 200)))
                .items())
        .extracting(BrandRow::id)
        .containsExactlyInAnyOrder(owned, licensed);
    // publishable 筛选：列表用的 SQL 表达式与判定服务必须给同一个集合。
    assertThat(
            tx(
                    actor,
                    () -> brands.list(new BrandQuery(mark, null, null, Boolean.TRUE, null, 0, 200)))
                .items())
        .extracting(BrandRow::id)
        .containsExactly(owned);
    assertThat(
            tx(
                    actor,
                    () ->
                        brands.list(new BrandQuery(mark, null, null, Boolean.FALSE, null, 0, 200)))
                .items())
        .extracting(BrandRow::id)
        .containsExactlyInAnyOrder(licensed, blocked);
    // 非法授权状态是 400 而不是「筛不出东西」。
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () -> brands.list(new BrandQuery(mark, "BORROWED", null, null, null, 0, 200))))
        .isInstanceOf(ResponseStatusException.class);

    // 分页是真的分页：size=1 时 total 仍然是全量行数。
    var page = tx(actor, () -> brands.list(new BrandQuery(mark, null, null, null, null, 0, 1)));
    assertThat(page.items()).hasSize(1);
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.size()).isEqualTo(1);
    var second = tx(actor, () -> brands.list(new BrandQuery(mark, null, null, null, null, 1, 1)));
    assertThat(second.items()).hasSize(1);
    assertThat(second.items().getFirst().id()).isNotEqualTo(page.items().getFirst().id());
    // 超上限的 size 被夹到上限，而不是放行几千行。
    assertThat(
            tx(actor, () -> brands.list(new BrandQuery(mark, null, null, null, null, 0, 9999)))
                .size())
        .isEqualTo(200);
  }

  @Test
  void overviewReportsBlockedListingBrands() {
    String mark = uniqueCode("OV");
    UUID ok = brand(actor, mark + "-正常", "OWNED", List.of(), List.of());
    UUID noCredential = brand(actor, mark + "-无凭证", "LICENSED", List.of(), List.of());
    UUID expiring = brand(actor, mark + "-临期", "LICENSED", List.of(), List.of());
    credential(actor, expiring, today().minusDays(1), today().plusDays(10), true);

    var overview = tx(actor, () -> brands.overview());
    assertThat(overview.brandCount()).isGreaterThanOrEqualTo(3);
    assertThat(overview.enabledCount()).isGreaterThanOrEqualTo(3);
    assertThat(overview.ownedCount()).isGreaterThanOrEqualTo(1);
    assertThat(overview.licensedCount()).isGreaterThanOrEqualTo(2);
    // 「现在有多少品牌登不了」：需授权而无有效凭证的那条必须被算进去。
    assertThat(overview.licensedWithoutCredentialCount()).isGreaterThanOrEqualTo(1);
    assertThat(overview.expiringWithin30DaysCount()).isGreaterThanOrEqualTo(1);
    assertThat(overview.blockedListingCount()).isGreaterThanOrEqualTo(1);
    assertThat(overview.note()).contains("UTC");
    assertThat(ok).isNotNull();
    assertThat(noCredential).isNotNull();
  }

  @Test
  void candidatesGiveListingAndComplianceTheirReferenceSet() {
    String mark = uniqueCode("CD");
    UUID ok = brand(actor, mark + "-可刊登", "OWNED", List.of(), List.of());
    UUID blocked = brand(actor, mark + "-不可刊登", "LICENSED", List.of(), List.of());
    UUID off = brand(actor, mark + "-停用", "OWNED", List.of(), List.of());
    writeVoid(actor, () -> brands.setEnabled(actor, off, false));

    // 默认只给启用且可刊登的：刊登表单的品牌选择器要的就是这批。
    var publishable = tx(actor, () -> brands.candidates(null, mark, false));
    assertThat(publishable).extracting(BrandPublishEligibility::brandId).containsExactly(ok);

    // includeBlocked 时连不可刊登的也返回，且带机器可读原因码。
    var all = tx(actor, () -> brands.candidates(null, mark, true));
    assertThat(all)
        .extracting(BrandPublishEligibility::brandId)
        .containsExactlyInAnyOrder(ok, blocked, off);
    assertThat(
            all.stream()
                .filter(e -> e.brandId().equals(blocked))
                .findFirst()
                .orElseThrow()
                .blockCode())
        .isEqualTo(BrandRules.BLOCK_LICENSE_MISSING);
    assertThat(
            all.stream().filter(e -> e.brandId().equals(off)).findFirst().orElseThrow().blockCode())
        .isEqualTo(BrandRules.BLOCK_BRAND_DISABLED);
  }

  // ------------------------------------------------------------ 多租户三断言

  @Test
  void tenantIsolationHoldsAcrossReadWriteAndPoolReuse() throws Exception {
    String mark = uniqueCode("ISO");
    UUID mine = brand(actor, mark + "-A 租户", "LICENSED", List.of(), List.of());
    credential(actor, mine, today().minusDays(1), today().plusDays(100), true);

    UUID theirTree = tree(actorB, "INTERNAL", null);
    UUID theirCategory = category(actorB, theirTree, null, "B 的类目");
    UUID theirs =
        write(
                actorB,
                () ->
                    brands.save(
                        actorB,
                        null,
                        new BrandInput(
                            uniqueCode("BR"),
                            mark + "-B 租户",
                            null,
                            "LICENSED",
                            true,
                            List.of(),
                            List.of(new BrandCategoryLinkInput(theirCategory, null)),
                            0)))
            .id();
    credential(actorB, theirs, today().minusDays(1), today().plusDays(100), true);

    // 1) 两租户互不可见
    assertThat(tx(actor, () -> brands.detail(mine)).code())
        .isEqualTo(tx(actor, () -> brands.detail(mine)).code());
    assertThat(tx(actorB, () -> brands.detail(theirs)).names()).isEmpty();
    assertThatThrownBy(() -> tx(actorB, () -> brands.detail(mine)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> brands.detail(theirs)))
        .isInstanceOf(ResponseStatusException.class);
    // 列表也不串：按关键词取，A 租户看不见 B 租户那条。
    assertThat(
            tx(actor, () -> brands.list(new BrandQuery(mark, null, null, null, null, 0, 200)))
                .items())
        .extracting(BrandRow::id)
        .contains(mine)
        .doesNotContain(theirs);
    assertThat(
            tx(actorB, () -> brands.list(new BrandQuery(mark, null, null, null, null, 0, 200)))
                .items())
        .extracting(BrandRow::id)
        .contains(theirs)
        .doesNotContain(mine);
    // 跨租户写入也不该污染：B 租户不能把凭证挂到 A 租户的品牌上。
    assertThatThrownBy(
            () ->
                write(
                    actorB,
                    () ->
                        brands.saveAuthorization(
                            actorB,
                            mine,
                            null,
                            new BrandAuthorizationInput(
                                uniqueCode("X"),
                                "越权",
                                today().toString(),
                                null,
                                null,
                                null,
                                true,
                                0))))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(tx(actor, () -> brands.authorizations(mine)))
        .extracting(BrandAuthorization::grantor)
        .doesNotContain("越权");
    // 跨租户改 B 租户的品牌也不该动到 A 的。
    var mineDetail = tx(actor, () -> brands.detail(mine));
    assertThatThrownBy(
            () ->
                write(
                    actorB,
                    () ->
                        brands.save(
                            actorB,
                            mine,
                            new BrandInput(
                                mineDetail.code(),
                                "被改",
                                null,
                                "OWNED",
                                true,
                                List.of(),
                                List.of(),
                                0))))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(tx(actor, () -> brands.detail(mine)).name()).isEqualTo(mark + "-A 租户");

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(
            get("/catalog/brands/" + mine)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/brands/overview")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());
    // 伪造头也不能借道写
    http.perform(
            post("/catalog/brands/" + mine + "/authorizations")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content(
                    "{\"certificateNo\":\"FORGED\",\"grantor\":\"伪造\",\"validFrom\":\""
                        + today()
                        + "\",\"enabled\":true}"))
        .andExpect(status().isNotFound());
    assertThat(tx(actor, () -> brands.authorizations(mine)))
        .extracting(BrandAuthorization::certificateNo)
        .doesNotContain("FORGED");

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> brands.detail(mine)).name()).isEqualTo(mark + "-A 租户");
      assertThat(tx(actorB, () -> brands.detail(theirs)).name()).isEqualTo(mark + "-B 租户");
      assertThatThrownBy(() -> tx(actorB, () -> brands.detail(mine)))
          .isInstanceOf(ResponseStatusException.class);
      // 判定路径同样不能串（它是另一个查询路径：凭证要单独取）。
      assertThat(tx(actor, () -> brands.eligibility(mine)).publishable()).isTrue();
      assertThat(tx(actorB, () -> brands.eligibility(theirs)).publishable()).isTrue();
      assertThatThrownBy(() -> tx(actorB, () -> brands.eligibility(mine)))
          .isInstanceOf(ResponseStatusException.class);
      // 候选列表同理。
      assertThat(tx(actor, () -> brands.candidates(null, mark, true)))
          .extracting(BrandPublishEligibility::brandId)
          .contains(mine)
          .doesNotContain(theirs);
      assertThat(tx(actorB, () -> brands.candidates(null, mark, true)))
          .extracting(BrandPublishEligibility::brandId)
          .contains(theirs)
          .doesNotContain(mine);
    }
  }

  // ------------------------------------------------------------ HTTP 与权限

  @Test
  void httpEndpointsExposeTheRealWorkflow() throws Exception {
    UUID t = tree(actor, "INTERNAL", null);
    UUID c = category(actor, t, null, "HTTP 类目");
    String code = uniqueCode("HTTP");

    http.perform(
            post("/catalog/brands")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"code\":\""
                        + code
                        + "\",\"name\":\"HTTP 品牌\",\"logoUri\":\"logos/http.png\","
                        + "\"authorizationStatus\":\"LICENSED\",\"enabled\":true,"
                        + "\"names\":[{\"locale\":\"zh-CN\",\"name\":\"HTTP 品牌\"},"
                        + "{\"locale\":\"en-US\",\"name\":\"HTTP Brand\"}],"
                        + "\"categories\":[{\"categoryId\":\""
                        + c
                        + "\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publishable").value(false))
        .andExpect(jsonPath("$.publishBlockCode").value(BrandRules.BLOCK_LICENSE_MISSING));

    // 判定端点：缺凭证时不可刊登。
    String id = jsonId(http, "/catalog/brands?keyword=" + code);
    http.perform(
            get("/catalog/brands/" + id + "/listing-eligibility")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publishable").value(false))
        .andExpect(jsonPath("$.blockCode").value(BrandRules.BLOCK_LICENSE_MISSING))
        .andExpect(jsonPath("$.blockLabel").value("缺少凭证"));

    // 登记凭证后立刻放行（同一个品牌，同一条链路）。
    http.perform(
            post("/catalog/brands/" + id + "/authorizations")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"certificateNo\":\""
                        + uniqueCode("C")
                        + "\",\"grantor\":\"品牌权利人\","
                        + "\"validFrom\":\""
                        + today().minusDays(1)
                        + "\",\"validTo\":\""
                        + today().plusDays(90)
                        + "\",\"enabled\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publishable").value(true));
    http.perform(get("/catalog/brands/" + id).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publishable").value(true))
        .andExpect(jsonPath("$.categories[0].categoryName").value("HTTP 类目"))
        .andExpect(jsonPath("$.authorizations[0].validOn").value(true))
        .andExpect(jsonPath("$.names['zh-cn']").value("HTTP 品牌"));

    // 批量判定。
    http.perform(
            post("/catalog/brands/listing-eligibility")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"brandIds\":[\"" + id + "\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].publishable").value(true));

    // 引用候选：默认只给可刊登的。
    http.perform(
            get("/catalog/brands/candidates?keyword=" + code)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));

    // 概览。
    http.perform(get("/catalog/brands/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.licensedCount").isNumber());

    // 校验失败要有可读原因（400 而不是 500）。
    http.perform(
            post("/catalog/brands")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"code\":\"中文\",\"name\":\"非法\",\"authorizationStatus\":\"OWNED\"}"))
        .andExpect(status().isBadRequest());
    // 未登录一律 401。
    http.perform(get("/catalog/brands")).andExpect(status().isUnauthorized());
    // 只有读权限不能写。
    http.perform(
            post("/catalog/brands")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    "{\"code\":\""
                        + uniqueCode("R")
                        + "\",\"name\":\"读者建的\",\"authorizationStatus\":\"OWNED\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void writeEndpointsRequireUpdatePermissionAndReadsWork() throws Exception {
    // reader 角色有 catalog.spu.read：读端点放行、写端点 403。
    http.perform(get("/catalog/brands/overview").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/brands").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(
            put("/catalog/brands/" + UUID.randomUUID() + "/enabled?enabled=false")
                .header("Authorization", "Bearer reader"))
        .andExpect(status().isForbidden());
    // 批量判定是**读**端点：reader 有 catalog.spu.read，所以放行到服务层，
    // 再由租户隔离把别人的品牌挡成 404（而不是 403——403 会泄露「这个 id 在别处存在」）。
    http.perform(
            post("/catalog/brands/listing-eligibility")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content("{\"brandIds\":[\"" + UUID.randomUUID() + "\"]}"))
        .andExpect(status().isNotFound());
  }

  /** 从列表里按关键词取出唯一那条的 id（HTTP 链路里用来继续操作真实记录）。 */
  String jsonId(MockMvc http, String path) throws Exception {
    String body =
        http.perform(get(path).header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
    return parsed.get("items").get(0).get("id").asText();
  }

  static org.springframework.test.web.servlet.result.JsonPathResultMatchers jsonPath(String path) {
    return org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(path);
  }
}
