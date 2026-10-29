package com.starcon.channel;

import static com.starcon.channel.ListingCollectBoxModels.*;
import static com.starcon.channel.ListingCollectModels.CollectListingRequest;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 4.4.1.3 采集箱。
 *
 * <p>蓝图：「采集结果的暂存区，按平台/来源/标签分组，支持勾选后批量认领为内部商品（生成 SPU/SKU 草稿并预填内容）、批量删除、批量打标。输入：勾选操作；认领规则（目标分类、品牌映射、命名规则）。输出：认领后的商品草稿；采集箱状态更新。关系：上游 = 4.4.1.1/4.4.1.2；下游 = 3.1.1.1 商品建档、4.2.1.1 编辑器。<b>认领时必须做品牌与分类映射</b>，否则采集来的商品归属混乱，后续无法按品牌做合规与绩效分析。」
 *
 * <p>🔴 <b>本测试类用 JDK 自带的 {@code com.sun.net.httpserver.HttpServer} 起一个真实的本地
 * HTTP 服务</b>，返回一段**真实的商品页 HTML**（含 JSON-LD Product、Open Graph 标签），
 * 采集草稿由 <b>4.4.1.1 的真抓服务</b>落库 —— 采集箱的输入因此是**真的上游产物**，
 * 而不是手工插进去的行。
 *
 * <p>本测试类钉住的事：
 *
 * <ol>
 *   <li><b>🔴 收录</b>：真抓 → 草稿 → 进箱；重复收录被拒并写明原因。
 *   <li><b>🔴 分组</b>：按平台/来源、按标签、按认领状态三份**真聚合**（不是前端自己数）。
 *   <li><b>🔴 批量打标</b>：挂标签 / 摘标签，条目上的标签真的变了。
 *   <li><b>🔴 认领规则的三条硬要求</b>：没有品牌映射 / 没有分类映射 / 命名规则没有占位符
 *       ⇒ 一律 400 拒绝，**建不出**这样的规则。
 *   <li><b>🔴 认领必须做品牌与分类映射</b>：品牌没映射的那一条**认领失败**并写明原因；
 *       映射过了的那一条产出**商品草稿载荷**（名称按命名规则、品牌与分类是映射结果、
 *       内容已预填、图片只透传 URL）。
 *   <li><b>🔴 只产出草稿，不建商品</b>：channel 库里**没有** spu / sku 表，
 *       {@code listing} / {@code listing_variant} / {@code listing_claim} 全是零行，
 *       认领结果停在 {@code buildState=PENDING_BUILD}。
 *   <li><b>🔴 批量删除</b>：条目真删；上游草稿在没有引用时**连带清理**、被 4.4.1.2 的结果清单
 *       引用时**保留**（否则会破坏别人的事实）。
 *   <li><b>timestamptz 往返</b>：认领时刻写回的值**逐字相等**。
 *   <li><b>金额</b>：外部页面原文标价是 {@code NUMERIC(19,4)}。
 * </ol>
 *
 * <p>多租户三断言在 {@code ListingCollectBoxTenancyIntegrationTest} 里（连接池压到 1，
 * 直查用 {@code channel_app} 而不是超级用户）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingCollectBoxIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer shop;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID tenant;
  AccessService.Access actor;
  String bearer;

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

  static AccessService.Access actor(UUID uid, UUID t, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, t, "Test", UUID.randomUUID(), null,
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

      // 🔴 真实的"外部站点"：收到请求时现场生成商品页 HTML（含 JSON-LD 与 og 标签）
      shop = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      shop.createContext(
          "/",
          e -> {
            String path = e.getRequestURI().getPath();
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
   * 🔴 真实的商品页 HTML。
   *
   * <p>带品牌（{@code product:brand}）与类目（JSON-LD {@code category}）—— 这两样是
   * <b>认领时的映射依据</b>，没有它们就认领不了（蓝图：认领时必须做品牌与分类映射）。
   * <ul>
   *   <li>{@code /nobrand/...} —— 刻意**不带品牌**，用来断言「没有品牌就认领不了」。
   *   <li>{@code /brand-<名字>/...} —— 刻意**换成别的品牌**，用来断言「品牌没映射的那一条认领不了」。
   * </ul>
   */
  static String productPage(String path) {
    String id = path.replaceAll("[^0-9]", "");
    if (id.isEmpty()) id = "1";
    boolean noBrand = path.startsWith("/nobrand");
    String brand = "Acme";
    var brandMatcher = java.util.regex.Pattern.compile("/brand-([^/]+)").matcher(path);
    if (brandMatcher.find()) brand = brandMatcher.group(1);
    if (noBrand) brand = "";
    String brandMeta = brand.isEmpty() ? "" : "<meta property=\"product:brand\" content=\"" + brand + "\">";
    String ldBrand =
        brand.isEmpty() ? "" : ", \"brand\": { \"@type\": \"Brand\", \"name\": \"" + brand + "\" }";
    return """
        <!doctype html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8">
          <title>采集箱测试页</title>
          <meta property="og:title" content="采集箱商品-%ID%">
          <meta property="og:image" content="https://cdn.example.com/%ID%.jpg">
          <meta property="og:description" content="采集箱商品描述 %ID%">
          <meta property="product:price:amount" content="128.5000">
          <meta property="product:price:currency" content="CNY">
          %BRAND%
          <script type="application/ld+json">
          {
            "@context": "https://schema.org/",
            "@type": "Product",
            "name": "采集箱商品-%ID%",
            "description": "采集箱商品正文 %ID%",
            "category": "女装"
            %LDBRAND%,
            "image": ["https://cdn.example.com/main/%ID%.jpg"],
            "additionalProperty": [
              { "@type": "PropertyValue", "name": "颜色", "value": "红" },
              { "@type": "PropertyValue", "name": "尺码", "value": "M" }
            ]
          }
          </script>
        </head>
        <body><h1>采集箱商品-%ID%</h1></body>
        </html>
        """
        .replace("%ID%", id)
        .replace("%BRAND%", brandMeta)
        .replace("%LDBRAND%", ldBrand);
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

  @Autowired ListingCollectService collectService;
  @Autowired ListingCollectBoxService box;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  @BeforeEach
  void seedTestSources() {
    exec(
        "insert into listing_collect_source (id,code,name,host,kind,min_interval_seconds,enabled,position)"
            + " values ('6c1d0003-0000-4000-8000-000000000011','TEST_BOX','本地测试站点','127.0.0.1',"
            + "'PLATFORM',0,true,910)"
            + " on conflict (id) do update set min_interval_seconds=0");
  }

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer box-" + tenant;
    actors.put(bearer, actor(user, tenant, List.of(grant("ALL", allPermissions))));
    actor = actors.get(bearer);
  }

  // ==================================================================
  // 辅助
  // ==================================================================

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

  /**
   * 参数化执行。
   *
   * <p>🔴 为什么要这个：PG JDBC 驱动会处理 SQL 里的 {@code {}} 转义序列 ——
   * 同一串语句在 psql 里合法，驱动发出去的却不是同一串（报「Unterminated string literal」）。
   * 凡是值里带花括号的（jsonb 空对象、命名规则模板），一律走占位符。
   */
  static void execPrepared(String sql, Object... params) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        s.setObject(i + 1, params[i]);
      }
      s.execute();
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

  static long count(String sql) {
    String value = scalar(sql);
    return value == null ? 0L : Long.parseLong(value);
  }

  String url(String path) {
    return "http://127.0.0.1:" + shop.getAddress().getPort() + path;
  }

  /** 🔴 真抓一次外部商品页，拿到 4.4.1.1 落的采集草稿 id。 */
  UUID collectDraft(String path) {
    var result =
        as(
            actor,
            "channel.operation.create",
            () -> collectService.collect(new CollectListingRequest(url(path), ""), user));
    assertThat(result.task().state()).isEqualTo("SUCCEEDED");
    assertThat(result.draft()).isNotNull();
    return result.draft().draftId();
  }

  UUID enroll(UUID draftId) {
    var result =
        as(actor, "channel.operation.create", () -> box.enroll(List.of(draftId)));
    assertThat(result.succeeded()).isEqualTo(1);
    return result.outcomes().get(0).itemId();
  }

  ListingCollectBoxClaimRuleRow rule(
      String name, String targetCategory, List<ListingCollectBoxKeyValue> categoryMapping,
      List<ListingCollectBoxKeyValue> brandMapping, String template) {
    return as(
        actor,
        "channel.operation.create",
        () ->
            box.createRule(
                new CreateClaimRuleRequest(
                    name, targetCategory, categoryMapping, brandMapping, template, null, null)));
  }

  // ==================================================================
  // 收录
  // ==================================================================

  @Test
  @DisplayName("4.4.1.3 收录：真抓的草稿进箱，候选列表里不再出现")
  void enrollRealDraft() {
    UUID draftId = collectDraft("/product/101");

    var candidates = as(actor, "channel.operation.read", () -> box.candidates(null, null, 0, 50));
    assertThat(candidates.items()).extracting(ListingCollectBoxCandidateRow::draftId).contains(draftId);

    UUID itemId = enroll(draftId);

    var items = as(actor, "channel.operation.read", () -> box.listItems(null, null, null, null, 0, 50));
    assertThat(items.total()).isEqualTo(1);
    ListingCollectBoxItemRow item = items.items().get(0);
    assertThat(item.itemId()).isEqualTo(itemId);
    assertThat(item.draftId()).isEqualTo(draftId);
    assertThat(item.title()).isEqualTo("采集箱商品-101");
    assertThat(item.origin()).isEqualTo("LINK");
    assertThat(item.state()).isEqualTo("PENDING");
    assertThat(item.sourceCode()).isEqualTo("TEST_BOX");
    // 🔴 外部页面原文标价（不是我方售价）
    assertThat(item.sourcePriceAmount()).isEqualByComparingTo("128.5000");

    var after = as(actor, "channel.operation.read", () -> box.candidates(null, null, 0, 50));
    assertThat(after.items()).extracting(ListingCollectBoxCandidateRow::draftId).doesNotContain(draftId);
  }

  @Test
  @DisplayName("4.4.1.3 收录：同一份草稿不能入箱两次（第二次写明原因）")
  void enrollTwiceIsRejected() {
    UUID draftId = collectDraft("/product/102");
    enroll(draftId);

    var again = as(actor, "channel.operation.create", () -> box.enroll(List.of(draftId)));
    assertThat(again.succeeded()).isZero();
    assertThat(again.failed()).isEqualTo(1);
    assertThat(again.outcomes().get(0).reason()).contains("已经在采集箱");
  }

  @Test
  @DisplayName("4.4.1.3 收录：草稿不存在时逐条报失败，不影响别的条")
  void enrollUnknownDraftFails() {
    UUID draftId = collectDraft("/product/103");
    var result =
        as(actor, "channel.operation.create", () -> box.enroll(List.of(draftId, UUID.randomUUID())));
    assertThat(result.succeeded()).isEqualTo(1);
    assertThat(result.failed()).isEqualTo(1);
    assertThat(result.outcomes().get(1).reason()).contains("不存在");
  }

  // ==================================================================
  // 分组
  // ==================================================================

  @Test
  @DisplayName("4.4.1.3 分组：按平台/来源、按标签、按状态都是真聚合")
  void groupsAreRealAggregations() {
    UUID a = enroll(collectDraft("/product/201"));
    UUID b = enroll(collectDraft("/product/202"));

    var tag = as(actor, "channel.operation.create", () -> box.createTag(new CreateBoxTagRequest("待议价", "#f00")));
    as(actor, "channel.operation.update", () -> box.tag(new TagBoxItemsRequest(List.of(a), tag.tagId(), "ADD")));

    var groups = as(actor, "channel.operation.read", () -> box.groups());
    assertThat(groups.total()).isEqualTo(2);
    assertThat(groups.bySource()).hasSize(1);
    assertThat(groups.bySource().get(0).key()).isEqualTo("TEST_BOX");
    assertThat(groups.bySource().get(0).count()).isEqualTo(2);
    assertThat(groups.byTag()).hasSize(1);
    assertThat(groups.byTag().get(0).label()).isEqualTo("待议价");
    assertThat(groups.byTag().get(0).count()).isEqualTo(1);
    assertThat(groups.byState()).extracting(ListingCollectBoxGroupRow::key).contains("PENDING");
    assertThat(b).isNotNull();
  }

  // ==================================================================
  // 批量打标
  // ==================================================================

  @Test
  @DisplayName("4.4.1.3 批量打标：挂上再摘掉，条目上的标签真的变了")
  void bulkTagAddsAndRemoves() {
    UUID itemId = enroll(collectDraft("/product/301"));
    var tag = as(actor, "channel.operation.create", () -> box.createTag(new CreateBoxTagRequest("已谈好", "")));

    var added =
        as(
            actor,
            "channel.operation.update",
            () -> box.tag(new TagBoxItemsRequest(List.of(itemId), tag.tagId(), "ADD")));
    assertThat(added.succeeded()).isEqualTo(1);

    var item = as(actor, "channel.operation.read", () -> box.itemDetail(itemId.toString()));
    assertThat(item.tags()).extracting(ListingCollectBoxTagRef::name).contains("已谈好");

    var removed =
        as(
            actor,
            "channel.operation.update",
            () -> box.tag(new TagBoxItemsRequest(List.of(itemId), tag.tagId(), "REMOVE")));
    assertThat(removed.succeeded()).isEqualTo(1);

    var after = as(actor, "channel.operation.read", () -> box.itemDetail(itemId.toString()));
    assertThat(after.tags()).isEmpty();
  }

  @Test
  @DisplayName("4.4.1.3 批量打标：按标签筛选真的只筛出带这个标签的条目")
  void filterByTag() {
    UUID tagged = enroll(collectDraft("/product/302"));
    UUID plain = enroll(collectDraft("/product/303"));
    var tag = as(actor, "channel.operation.create", () -> box.createTag(new CreateBoxTagRequest("重点", "")));
    as(actor, "channel.operation.update", () -> box.tag(new TagBoxItemsRequest(List.of(tagged), tag.tagId(), "ADD")));

    var filtered =
        as(actor, "channel.operation.read", () -> box.listItems(null, tag.tagId().toString(), null, null, 0, 50));
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.items().get(0).itemId()).isEqualTo(tagged);
    assertThat(plain).isNotNull();
  }

  // ==================================================================
  // 认领规则
  // ==================================================================

  @Test
  @DisplayName("4.4.1.3 认领规则：没有品牌映射 ⇒ 400（蓝图：认领时必须做品牌与分类映射）")
  void ruleWithoutBrandMappingIsRejected() {
    var thrown =
        catchThrowable(
            () ->
                rule("无品牌规则", "CAT-1", List.of(), List.of(), "{brand}-{title}"));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("品牌映射");
  }

  @Test
  @DisplayName("4.4.1.3 认领规则：没有目标分类也没有分类映射 ⇒ 400")
  void ruleWithoutCategoryIsRejected() {
    var thrown =
        catchThrowable(() -> rule("无分类规则", "", List.of(), List.of(new ListingCollectBoxKeyValue("Acme", "BR-1")), "{brand}-{title}"));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("目标分类");
  }

  @Test
  @DisplayName("4.4.1.3 认领规则：命名规则没有占位符 ⇒ 400")
  void ruleWithoutPlaceholderIsRejected() {
    var thrown =
        catchThrowable(() -> rule("无占位符规则", "CAT-1", List.of(), List.of(new ListingCollectBoxKeyValue("Acme", "BR-1")), "固定名字"));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("占位符");
  }

  @Test
  @DisplayName("4.4.1.3 认领规则：品牌映射为空 ⇒ DB 级 CHECK 也拦得住")
  void ruleWithEmptyBrandMappingCannotBeStored() {
    // 服务层已经拒了；这里直接打 DB，验证 CHECK 约束真的在（不是只有服务层的自觉）。
    // 🔴 用**参数化**语句：字面量里的 `{}` 会被 PG JDBC 驱动的转义处理改写（同一串语句在 psql 里
    //    是合法的，驱动发出去的却不是同一串），值一律走占位符。
    var thrown =
        catchThrowable(
            () ->
                execPrepared(
                    "insert into listing_collect_claim_rule"
                        + " (id,tenant_id,name,target_category_code,category_mapping,brand_mapping,"
                        + " naming_template,state,created_at,updated_at)"
                        + " values (gen_random_uuid(),?::uuid,?,'CAT-1',?::jsonb,?::jsonb,?,'ACTIVE',"
                        + "now(),now())",
                    tenant.toString(),
                    "空品牌规则",
                    "{}",
                    "{}",
                    "{brand}-{title}"));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("check constraint");
  }

  // ==================================================================
  // 认领
  // ==================================================================

  @Test
  @DisplayName("4.4.1.3 认领：品牌没映射的那一条失败并写明原因，别的条照常认领")
  void claimFailsWhenBrandNotMapped() {
    // 第一条品牌是 Acme（规则里映射了），第二条品牌是 OtherBrand（规则里**没有**映射）
    UUID mapped = enroll(collectDraft("/product/401"));
    UUID unmapped = enroll(collectDraft("/brand-OtherBrand/product/402"));
    var r = rule("规则", "CAT-1", List.of(new ListingCollectBoxKeyValue("女装", "CAT-1")), List.of(new ListingCollectBoxKeyValue("Acme", "BR-1")), "{brand}-{title}");

    var result =
        as(actor, "channel.operation.update", () -> box.claim(List.of(mapped, unmapped), r.ruleId().toString()));

    assertThat(result.succeeded()).isEqualTo(1);
    assertThat(result.failed()).isEqualTo(1);
    ListingCollectBoxClaimOutcome failed =
        result.outcomes().stream().filter(o -> !o.ok()).findFirst().orElseThrow();
    assertThat(failed.itemId()).isEqualTo(unmapped);
    assertThat(failed.reason()).contains("OtherBrand").contains("品牌映射");
    // 映射过了的那一条：品牌是映射结果
    ListingCollectBoxClaimOutcome ok =
        result.outcomes().stream().filter(ListingCollectBoxClaimOutcome::ok).findFirst().orElseThrow();
    assertThat(ok.claimResult().targetBrandCode()).isEqualTo("BR-1");
    assertThat(ok.claimResult().sourceBrand()).isEqualTo("Acme");

    // 🔴 失败原因要写回条目 —— 「为什么这一条没认领成功」必须看得见
    var item = as(actor, "channel.operation.read", () -> box.itemDetail(unmapped.toString()));
    assertThat(item.state()).isEqualTo("PENDING");
    assertThat(item.claimError()).contains("品牌映射");
  }

  @Test
  @DisplayName("4.4.1.3 认领：类目没命中映射时落到规则的目标分类（兜底）")
  void claimFallsBackToRuleTargetCategory() {
    UUID itemId = enroll(collectDraft("/product/403"));
    // 规则有品牌映射、有分类映射，但草稿的类目「女装」不在映射里 ⇒ 落到规则的目标分类
    var withFallback = rule("有兜底", "CAT-FALLBACK", List.of(), List.of(new ListingCollectBoxKeyValue("Acme", "BR-1")), "{brand}-{title}");
    var result =
        as(actor, "channel.operation.update", () -> box.claim(List.of(itemId), withFallback.ruleId().toString()));
    assertThat(result.succeeded()).isEqualTo(1);
    assertThat(result.outcomes().get(0).claimResult().targetCategoryCode()).isEqualTo("CAT-FALLBACK");
  }

  @Test
  @DisplayName("4.4.1.3 认领：产出商品草稿载荷（命名 / 映射 / 预填内容 / 图片只透传 URL）")
  void claimProducesProductDraft() {
    UUID itemId = enroll(collectDraft("/product/404"));
    var r =
        rule(
            "女装规则",
            "CAT-WOMEN",
            List.of(new ListingCollectBoxKeyValue("女装", "CAT-WOMEN-DRESS")),
            List.of(new ListingCollectBoxKeyValue("Acme", "BRAND-ACME")),
            "{brand}-{title}");

    var result = as(actor, "channel.operation.update", () -> box.claim(List.of(itemId), r.ruleId().toString()));
    assertThat(result.succeeded()).isEqualTo(1);

    ListingCollectBoxClaimResultRow claim = result.outcomes().get(0).claimResult();
    assertThat(claim).isNotNull();
    // 🔴 命名规则产出
    assertThat(claim.spuName()).isEqualTo("BRAND-ACME-采集箱商品-404");
    // 🔴 品牌与分类都是**映射结果**
    assertThat(claim.targetBrandCode()).isEqualTo("BRAND-ACME");
    assertThat(claim.targetCategoryCode()).isEqualTo("CAT-WOMEN-DRESS");
    assertThat(claim.sourceBrand()).isEqualTo("Acme");
    assertThat(claim.sourceCategory()).isEqualTo("女装");
    // 🔴 内容预填
    assertThat(claim.spuDraft().title()).isEqualTo("采集箱商品-404");
    assertThat(claim.spuDraft().description()).contains("采集箱商品正文");
    assertThat(claim.spuDraft().specs()).extracting(ListingCollectBoxKeyValue::name).contains("颜色", "尺码");
    // 🔴 图片只透传 URL
    assertThat(claim.spuDraft().mainImageUrl()).startsWith("https://cdn.example.com/");
    assertThat(claim.spuDraft().imageUrls()).allMatch(u -> u.startsWith("https://"));
    // 🔴 SKU 草稿行：上游每个规格轴只有一个取值 ⇒ 一个观察到的组合
    assertThat(claim.skuDraftCount()).isEqualTo(1);
    assertThat(claim.skuDrafts().get(0).specValues()).extracting(ListingCollectBoxKeyValue::name)
        .contains("颜色", "尺码");
    // 🔴 落库归 03 建档 —— 本模块不假装已经建好
    assertThat(claim.buildState()).isEqualTo("PENDING_BUILD");
    assertThat(claim.buildNote()).contains("03");

    // 条目状态更新为已认领
    var item = as(actor, "channel.operation.read", () -> box.itemDetail(itemId.toString()));
    assertThat(item.state()).isEqualTo("CLAIMED");
    assertThat(item.claimResultId()).isEqualTo(claim.claimResultId());
    assertThat(item.claimedAt()).isNotNull();
  }

  @Test
  @DisplayName("4.4.1.3 认领：已认领的条目不能再认领一次")
  void claimTwiceIsRejected() {
    UUID itemId = enroll(collectDraft("/product/405"));
    var r = rule("规则", "CAT-1", List.of(), List.of(new ListingCollectBoxKeyValue("Acme", "BR-1")), "{brand}-{title}");
    as(actor, "channel.operation.update", () -> box.claim(List.of(itemId), r.ruleId().toString()));

    var again = as(actor, "channel.operation.update", () -> box.claim(List.of(itemId), r.ruleId().toString()));
    assertThat(again.succeeded()).isZero();
    assertThat(again.outcomes().get(0).reason()).contains("已经认领");
  }

  @Test
  @DisplayName("4.4.1.3 认领：草稿没有品牌属性 ⇒ 认领不了（必须有品牌映射）")
  void claimFailsWhenDraftHasNoBrand() {
    UUID itemId = enroll(collectDraft("/nobrand/406"));
    var r = rule("规则", "CAT-1", List.of(), List.of(new ListingCollectBoxKeyValue("Acme", "BR-1")), "{brand}-{title}");
    var result = as(actor, "channel.operation.update", () -> box.claim(List.of(itemId), r.ruleId().toString()));
    assertThat(result.succeeded()).isZero();
    assertThat(result.outcomes().get(0).reason()).contains("品牌");
  }

  @Test
  @DisplayName("4.4.1.3 🔴 只产出草稿：channel 库里没有 spu / sku 表，也不建 listing")
  void claimDoesNotCreateProducts() {
    UUID itemId = enroll(collectDraft("/product/407"));
    var r = rule("规则", "CAT-1", List.of(), List.of(new ListingCollectBoxKeyValue("Acme", "BR-1")), "{brand}-{title}");
    as(actor, "channel.operation.update", () -> box.claim(List.of(itemId), r.ruleId().toString()));

    // 🔴 channel 库里根本没有商品表 —— 采集箱无处可写
    assertThat(scalar("select (to_regclass('public.spu') is null)::text")).isEqualTo("true");
    assertThat(scalar("select (to_regclass('public.sku') is null)::text")).isEqualTo("true");
    // 🔴 也不许顺手建 listing（listing 是「已在平台上架的商品」）
    assertThat(count("select count(*) from listing where tenant_id='" + tenant + "'")).isZero();
    assertThat(count("select count(*) from listing_variant where tenant_id='" + tenant + "'")).isZero();
    assertThat(count("select count(*) from listing_claim where tenant_id='" + tenant + "'")).isZero();
    // 🔴 认领结果停在本模块自己的表里
    assertThat(count("select count(*) from listing_collect_claim_result where tenant_id='" + tenant + "'")).isEqualTo(1);
  }

  // ==================================================================
  // 批量删除
  // ==================================================================

  @Test
  @DisplayName("4.4.1.3 批量删除：条目真删；上游草稿没有引用时连带清理")
  void removeDeletesItemAndDraft() {
    UUID draftId = collectDraft("/product/501");
    UUID itemId = enroll(draftId);

    var result =
        as(
            actor,
            "channel.operation.delete",
            () -> box.remove(new RemoveBoxItemsRequest(List.of(itemId))));
    assertThat(result.succeeded()).isEqualTo(1);
    assertThat(result.outcomes().get(0).draftRemoved()).isTrue();

    assertThat(count("select count(*) from listing_collect_box_item where tenant_id='" + tenant + "' and id='" + itemId + "'")).isZero();
    assertThat(count("select count(*) from listing_collect_draft where tenant_id='" + tenant + "' and id='" + draftId + "'")).isZero();
    // 🔴 采集任务（抓取留痕）**不能**被连带删掉 —— 它是审计事实
    assertThat(count("select count(*) from listing_collect_task where tenant_id='" + tenant + "'")).isEqualTo(1);
  }

  @Test
  @DisplayName("4.4.1.3 批量删除：草稿被 4.4.1.2 的结果清单引用时保留草稿（不破坏别人的事实）")
  void removeKeepsReferencedDraft() {
    UUID draftId = collectDraft("/product/502");
    UUID itemId = enroll(draftId);

    // 🔴 造一条 4.4.1.2 的结果清单引用（draft_id 外键没有 ON DELETE CASCADE）
    exec(
        "insert into listing_bulk_collect_task"
            + " (id,tenant_id,source_id,mode,state,created_at,updated_at)"
            + " select gen_random_uuid(),'"
            + tenant
            + "',source_id,'PLUGIN','SUCCEEDED',now(),now() from listing_collect_draft"
            + " where id='"
            + draftId
            + "'");
    exec(
        "insert into listing_bulk_collect_result"
            + " (id,tenant_id,bulk_task_id,origin,source_url,normalized_url,state,"
            + " collect_task_id,draft_id,created_at,updated_at)"
            + " select gen_random_uuid(),'"
            + tenant
            + "',t.id,'PLUGIN',d.source_url,d.normalized_url,'FETCHED',d.task_id,d.id,now(),now()"
            + " from listing_bulk_collect_task t join listing_collect_draft d"
            + " on d.tenant_id=t.tenant_id and d.id='"
            + draftId
            + "' where t.tenant_id='"
            + tenant
            + "'");

    var result =
        as(
            actor,
            "channel.operation.delete",
            () -> box.remove(new RemoveBoxItemsRequest(List.of(itemId))));
    assertThat(result.succeeded()).isEqualTo(1);
    assertThat(result.outcomes().get(0).draftRemoved()).isFalse();
    assertThat(result.outcomes().get(0).reason()).contains("结果清单");

    assertThat(count("select count(*) from listing_collect_box_item where tenant_id='" + tenant + "' and id='" + itemId + "'")).isZero();
    assertThat(count("select count(*) from listing_collect_draft where tenant_id='" + tenant + "' and id='" + draftId + "'")).isEqualTo(1);
  }

  // ==================================================================
  // 分页 / 时间 / 金额
  // ==================================================================

  @Test
  @DisplayName("4.4.1.3 列表：真分页 + 状态筛选")
  void listIsReallyPaginated() {
    for (int i = 0; i < 5; i++) enroll(collectDraft("/product/60" + i));

    var page0 = as(actor, "channel.operation.read", () -> box.listItems(null, null, null, null, 0, 2));
    var page1 = as(actor, "channel.operation.read", () -> box.listItems(null, null, null, null, 1, 2));
    assertThat(page0.total()).isEqualTo(5);
    assertThat(page0.items()).hasSize(2);
    assertThat(page1.items()).hasSize(2);
    assertThat(page0.items().get(0).itemId()).isNotEqualTo(page1.items().get(0).itemId());

    var byState = as(actor, "channel.operation.read", () -> box.listItems(null, null, "PENDING", null, 0, 50));
    assertThat(byState.total()).isEqualTo(5);
  }

  @Test
  @DisplayName("4.4.1.3 列表：非法的认领状态 ⇒ 422（不静默忽略）")
  void unknownStateIsRejected() {
    var thrown =
        catchThrowable(
            () -> as(actor, "channel.operation.read", () -> box.listItems(null, null, "NOPE", null, 0, 50)));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("未知的条目状态");
  }

  @Test
  @DisplayName("4.4.1.3 🔴 timestamptz 往返：认领时刻写回的值逐字相等")
  void timestamptzRoundTripsExactly() {
    UUID itemId = enroll(collectDraft("/product/701"));
    var r = rule("规则", "CAT-1", List.of(), List.of(new ListingCollectBoxKeyValue("Acme", "BR-1")), "{brand}-{title}");
    var result = as(actor, "channel.operation.update", () -> box.claim(List.of(itemId), r.ruleId().toString()));
    OffsetDateTime claimedAt = result.outcomes().get(0).claimResult().claimedAt();

    String stored =
        scalar(
            "select to_char(claimed_at at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS.US')"
                + " from listing_collect_claim_result where item_id='"
                + itemId
                + "'");
    assertThat(stored).isEqualTo(claimedAt.withOffsetSameInstant(java.time.ZoneOffset.UTC)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")));
  }

  @Test
  @DisplayName("4.4.1.3 🔴 金额列：外部页面原文标价是 NUMERIC(19,4)，且采集箱不改写它")
  void priceColumnIsNumeric() {
    assertThat(
            scalar(
                "select numeric_precision||','||numeric_scale from information_schema.columns"
                    + " where table_name='listing_collect_draft' and column_name='source_price_amount'"))
        .isEqualTo("19,4");

    UUID itemId = enroll(collectDraft("/product/702"));
    var item = as(actor, "channel.operation.read", () -> box.itemDetail(itemId.toString()));
    assertThat(item.sourcePriceAmount()).isEqualByComparingTo("128.5000");
  }

  // ==================================================================
  // HTTP 面 / 能力声明
  // ==================================================================

  @Test
  @DisplayName("4.4.1.3 HTTP 面：401 / 404 / 400 都对")
  void httpSurface() throws Exception {
    // 无会话 ⇒ 401
    http.perform(get("/channel/listing-collect-box/items")).andExpect(status().isUnauthorized());
    // 不存在的条目 ⇒ 404
    http.perform(get("/channel/listing-collect-box/items/" + UUID.randomUUID())
            .header("Authorization", bearer))
        .andExpect(status().isNotFound());
    // 空选择 ⇒ 400
    http.perform(post("/channel/listing-collect-box/items/remove")
            .header("Authorization", bearer)
            .contentType("application/json")
            .content("{\"itemIds\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("4.4.1.3 能力声明：未交付的维度 available=false + ownerModule + impact")
  void capabilitiesDeclareUndeliveredDimensions() {
    var caps = as(actor, "channel.operation.read", () -> box.capabilities());
    assertThat(caps.mappingRequired()).isTrue();
    assertThat(caps.draftOnly()).isTrue();
    // 🔴 商品草稿落库归 03 建档 —— 本模块不假装已经建好
    assertThat(caps.builtInCatalog()).isFalse();
    var delivery =
        caps.dimensions().stream()
            .filter(d -> d.dimension().equals("product_draft_delivery"))
            .findFirst()
            .orElseThrow();
    assertThat(delivery.available()).isFalse();
    assertThat(delivery.ownerModule()).contains("3.1.1.1");
    assertThat(delivery.impact()).contains("不写");
    // 🔴 媒体库 / 售价 / 库存也都标成未交付
    assertThat(caps.dimensions().stream().filter(d -> !d.available()).count()).isGreaterThanOrEqualTo(4);
  }

  /**
   * 🔴 收尾：停掉本地桩与 Testcontainers 容器。
   *
   * <p>不回收容器的话，Ryuk 只在 JVM 退出时按 session 标签统一回收 —— 整个模块跑完之前容器一直活着
   * （实测 channel 一个服务就能堆到 110+ 个 postgres 容器），容器内存 + Docker VM 把机器压进 swap，
   * JVM 被卡住十几分钟 ⇒ 全量跑时断言假红、SSL 握手失败。
   *
   * <p>顺序：先停外部服务，再停容器。反了会出现「容器已停但还有 in-flight 请求打进来」。
   */
  @AfterAll
  static void stopTestResources() {
    if (identity != null) identity.stop(0);
    if (shop != null) shop.stop(0);
    pg.stop();
  }
}
