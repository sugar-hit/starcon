package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
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

/** 3.1.1.3 SPU 生命周期：转换矩阵、变更历史、批量规则求值，以及租户隔离三断言。 真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。 */
@SpringBootTest
@AutoConfigureMockMvc
class SpuLifecycleIntegrationTest {
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
  @Autowired LifecycleService lifecycle;
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

  UUID product() {
    return product(a, "P-" + UUID.randomUUID());
  }

  UUID product(UUID tenant, String code) {
    return call(
        actor(tenant),
        "catalog.spu.create",
        () ->
            catalog.saveSpu(
                actor(tenant),
                null,
                new SpuInput(code, "Lifecycle phone", "", "Brand", "", "DRAFT", 0)));
  }

  Detail detail(UUID id) {
    return call(actor(a), "catalog.spu.read", () -> catalog.detail(id));
  }

  /** 建一个已启用且必填齐备的 SKU，让 SPU 具备上架条件。 */
  void readySku(UUID p) {
    call(
        actor(a),
        "catalog.attribute.create",
        () -> {
          catalog.saveAttribute(
              actor(a),
              p,
              null,
              new AttributeInput(
                  "VIDEO",
                  "VIDEO",
                  "TEXT",
                  "COMMON_LOCKED",
                  true,
                  false,
                  false,
                  "",
                  "参数",
                  List.of(),
                  List.of("4K"),
                  detail(p).product().version()));
          return null;
        });
    call(
        actor(a),
        "catalog.sku.create",
        () ->
            catalog.saveSku(
                actor(a),
                p,
                null,
                new SkuInput(
                    "S-" + UUID.randomUUID(),
                    "SKU",
                    "",
                    "件",
                    "ACTIVE",
                    List.of(),
                    detail(p).product().version())));
  }

  LifecycleEvent change(UUID p, String to, String reason) {
    return call(
        actor(a),
        "catalog.spu.update",
        () ->
            lifecycle.transition(
                actor(a),
                p,
                new TransitionInput(to, reason, null, detail(p).product().version()),
                null));
  }

  // ------------------------------------------------------------ 状态域迁移

  @Test
  void legacyStatusDomainIsGoneAndMapped() {
    // V4 已把四值域换成五态，旧值不能再写入。
    UUID p = product();
    assertThatThrownBy(
            () -> tx(a, () -> sql.execute("update spu set status='ACTIVE' where id=?", p)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    // 五态与映射目标都在域内。
    var allowed =
        sql.fetch(
            "select pg_get_constraintdef(oid) as def from pg_constraint where conrelid='spu'::regclass and conname='spu_status_check'");
    assertThat(allowed).hasSize(1);
    for (String state : List.of("DRAFT", "PENDING_LISTING", "ON_SALE", "SUSPENDED", "DISCONTINUED"))
      assertThat(allowed.getFirst().get("def", String.class)).contains(state);
  }

  // ------------------------------------------------------------ 转换矩阵

  @Test
  void matrixRejectsIllegalTransitionsAndRequiresReason() {
    UUID p = product();
    assertThatThrownBy(() -> change(p, "ON_SALE", "直接上架")).hasMessageContaining("不允许的状态流转");
    assertThatThrownBy(() -> change(p, "SUSPENDED", "停售")).hasMessageContaining("不允许的状态流转");
    assertThatThrownBy(() -> change(p, "PENDING_LISTING", "")).hasMessageContaining("必须填写变更原因");
    // 没有可用 SKU 时不能进入待上架（前置条件）。
    assertThatThrownBy(() -> change(p, "PENDING_LISTING", "资料已补齐")).hasMessageContaining("SKU");
    assertThat(detail(p).product().status()).isEqualTo("DRAFT");
  }

  @Test
  void fullLifecycleIsWalkableAndHistoryIsRecorded() {
    UUID p = product();
    readySku(p);
    change(p, "PENDING_LISTING", "资料齐备，提交上架");
    change(p, "ON_SALE", "已刊登，开始销售");
    change(p, "SUSPENDED", "断货停售");
    change(p, "ON_SALE", "补货恢复");
    change(p, "SUSPENDED", "供应商停供");
    change(p, "DISCONTINUED", "彻底停产");
    // 终态不可再流转。
    assertThatThrownBy(() -> change(p, "ON_SALE", "复活")).hasMessageContaining("不允许的状态流转");

    var view = call(actor(a), "catalog.spu.read", () -> lifecycle.view(p));
    assertThat(view.status()).isEqualTo("DISCONTINUED");
    assertThat(view.transitions()).isEmpty();
    assertThat(view.history()).hasSize(6);
    assertThat(view.history())
        .extracting(LifecycleEvent::toStatus)
        .containsExactly(
            "DISCONTINUED", "SUSPENDED", "ON_SALE", "SUSPENDED", "ON_SALE", "PENDING_LISTING");
    assertThat(view.history()).allMatch(e -> e.source().equals("MANUAL"));
    assertThat(view.history()).allMatch(e -> !e.reason().isBlank());
    assertThat(view.history()).allMatch(e -> e.actorId().equals(user));
    // 业务时间与系统时间分列存储，且都不为空。
    assertThat(view.history()).allMatch(e -> e.occurredAt() != null && e.createdAt() != null);
    // 停售事件已发布给下游（是否下架平台商品由 04 决定）。
    var outbox =
        tx(
            a,
            () ->
                sql.fetch(
                    "select payload->>'toStatus' as to from outbox_event where aggregate_id=? and event_type='SpuLifecycleChanged' order by occurred_at",
                    p));
    assertThat(outbox)
        .extracting(r -> r.get("to", String.class))
        .containsExactly(
            "PENDING_LISTING", "ON_SALE", "SUSPENDED", "ON_SALE", "SUSPENDED", "DISCONTINUED");
  }

  @Test
  void lifecycleBusinessTimeIsStoredSeparatelyFromSystemTime() {
    UUID p = product();
    readySku(p);
    var event = change(p, "PENDING_LISTING", "指定业务时间提交");
    var row = tx(a, () -> sql.fetchOne("select * from spu_lifecycle_event where id=?", event.id()));
    assertThat(row.get("from_status", String.class)).isEqualTo("DRAFT");
    assertThat(row.get("to_status", String.class)).isEqualTo("PENDING_LISTING");
    assertThat(row.get("occurred_at", java.time.OffsetDateTime.class)).isNotNull();
    assertThat(row.get("created_at", java.time.OffsetDateTime.class)).isNotNull();
  }

  @Test
  void staleVersionOnTransitionIsRejected() {
    UUID p = product();
    readySku(p);
    long version = detail(p).product().version();
    assertThatThrownBy(
            () ->
                call(
                    actor(a),
                    "catalog.spu.update",
                    () ->
                        lifecycle.transition(
                            actor(a),
                            p,
                            new TransitionInput("PENDING_LISTING", "提交", null, version - 1),
                            null)))
        .hasMessageContaining("请刷新后重试");
  }

  // ------------------------------------------------------------ 批量规则

  @Test
  void catalogOwnedRulesEvaluateForReal() {
    UUID ready = product();
    readySku(ready);
    UUID bare = product();
    // 规则：已启用 SKU 数 ≥ 1 且资料齐备 SKU 数 ≥ 1 → 停售
    UUID rule =
        call(
            actor(a),
            "catalog.spu.create",
            () ->
                lifecycle.saveRule(
                    actor(a),
                    null,
                    new RuleInput(
                        "STOP-SLOW",
                        "停售已上架商品",
                        "清理长尾在售商品",
                        true,
                        "SUSPENDED",
                        "批量规则：长尾商品转停售",
                        List.of(
                            new RuleCondition("ACTIVE_SKU_COUNT", "GTE", new BigDecimal("1"), null),
                            new RuleCondition("READY_SKU_COUNT", "GTE", new BigDecimal("1"), null)),
                        0)));
    var ruleView = call(actor(a), "catalog.spu.read", () -> lifecycle.rule(rule));
    assertThat(ruleView.conditions()).hasSize(2);
    assertThat(ruleView.conditions())
        .extracting(RuleCondition::metric)
        .containsExactly("ACTIVE_SKU_COUNT", "READY_SKU_COUNT");

    change(ready, "PENDING_LISTING", "提交");
    change(ready, "ON_SALE", "上架");

    // 同库内其他用例也可能留下候选 SPU，因此断言集合关系而不是全局计数。
    var preview = call(actor(a), "catalog.spu.read", () -> lifecycle.preview(rule, 200, null));
    assertThat(preview.matches()).extracting(RuleMatch::spuId).contains(ready).doesNotContain(bare);
    assertThat(preview.matches()).extracting(RuleMatch::currentStatus).containsOnly("ON_SALE");
    assertThat(preview.hasMore()).isFalse();

    var run =
        call(
            actor(a),
            "catalog.spu.update",
            () -> lifecycle.run(actor(a), new RuleInputScope(rule, null, null, 200)));
    assertThat(run.matchedCount()).isEqualTo(preview.matchedCount());
    assertThat(run.appliedCount()).isEqualTo(preview.matchedCount());
    assertThat(detail(ready).product().status()).isEqualTo("SUSPENDED");
    assertThat(detail(bare).product().status()).isEqualTo("DRAFT");
    // 规则来源的变更同样入历史。
    assertThat(
            call(actor(a), "catalog.spu.read", () -> lifecycle.history(ready)).getFirst().source())
        .isEqualTo("RULE");
    assertThat(
            call(actor(a), "catalog.spu.read", () -> lifecycle.history(ready)).getFirst().reason())
        .isEqualTo("批量规则：长尾商品转停售");
    // 已产生变更记录的规则不能删除，只能停用。
    assertThatThrownBy(
            () ->
                call(
                    actor(a),
                    "catalog.spu.delete",
                    () -> {
                      lifecycle.deleteRule(
                          actor(a),
                          rule,
                          call(actor(a), "catalog.spu.read", () -> lifecycle.rule(rule)).version());
                      return null;
                    }))
        .hasMessageContaining("不能删除");
  }

  @Test
  void unpublishedMetricsAreRejectedInsteadOfFaked() {
    for (String metric : List.of("INVENTORY_ON_HAND", "SALES_QTY_30D", "LISTING_PUBLISHED_COUNT")) {
      UUID rule =
          call(
              actor(a),
              "catalog.spu.create",
              () ->
                  lifecycle.saveRule(
                      actor(a),
                      null,
                      new RuleInput(
                          "R-" + metric,
                          metric,
                          "未接入投影的规则",
                          true,
                          "SUSPENDED",
                          "规则原因",
                          List.of(new RuleCondition(metric, "GTE", new BigDecimal("1"), null)),
                          0)));
      // 规则定义可以保存（配置完整），但求值必须显式拒绝，不能返回 0。
      assertThatThrownBy(
              () -> call(actor(a), "catalog.spu.read", () -> lifecycle.preview(rule, 50, null)))
          .hasMessageContaining("尚未接入的上游投影");
      assertThatThrownBy(
              () ->
                  call(
                      actor(a),
                      "catalog.spu.update",
                      () -> lifecycle.run(actor(a), new RuleInputScope(rule, null, null, 50))))
          .hasMessageContaining("尚未接入的上游投影");
    }
    // 指标元数据如实标注可用性与拒绝原因。
    var metrics = call(actor(a), "catalog.spu.read", () -> lifecycle.metrics());
    assertThat(metrics).hasSize(LifecycleMetric.values().length);
    assertThat(metrics)
        .filteredOn(MetricInfo::available)
        .extracting(MetricInfo::code)
        .containsExactlyInAnyOrder("SKU_COUNT", "ACTIVE_SKU_COUNT", "READY_SKU_COUNT");
    assertThat(metrics).filteredOn(m -> !m.available()).allMatch(m -> m.note().contains("上游投影未接入"));
  }

  @Test
  void previewCursorWalksEveryCandidateWithoutDuplicates() {
    UUID ruleId = ruleWithSkuCountThreshold("CURSOR-1", new BigDecimal("1"));
    var mine = new ArrayList<UUID>();
    for (int i = 0; i < 3; i++) {
      UUID p = product();
      readySku(p);
      change(p, "PENDING_LISTING", "提交");
      change(p, "ON_SALE", "上架");
      mine.add(p);
    }
    // 用小页长走完整条游标链，确认不漏不重；同库内其他用例的候选也一并被扫到。
    var seen = new ArrayList<UUID>();
    var pages = new java.util.concurrent.atomic.AtomicInteger();
    String cursor = null;
    while (true) {
      var at = cursor;
      var page = call(actor(a), "catalog.spu.read", () -> lifecycle.preview(ruleId, 2, at));
      pages.incrementAndGet();
      assertThat(page.scanned()).isLessThanOrEqualTo(2);
      page.matches().forEach(m -> seen.add(m.spuId()));
      cursor = page.nextCursor();
      if (cursor == null) break;
      assertThat(pages.get()).isLessThan(50);
    }
    assertThat(pages.get()).isGreaterThan(1);
    assertThat(seen).doesNotHaveDuplicates();
    assertThat(seen).containsAll(mine);
    assertThat(seen).allMatch(id -> detail(id).product().status().equals("ON_SALE"));
    // 走完游标后候选集合与单页大扫描一致，证明分页没有静默截断。
    var all = call(actor(a), "catalog.spu.read", () -> lifecycle.preview(ruleId, 200, null));
    assertThat(seen)
        .containsExactlyInAnyOrderElementsOf(all.matches().stream().map(RuleMatch::spuId).toList());
  }

  UUID ruleWithSkuCountThreshold(String code, BigDecimal threshold) {
    return call(
        actor(a),
        "catalog.spu.create",
        () ->
            lifecycle.saveRule(
                actor(a),
                null,
                new RuleInput(
                    code,
                    "按 SKU 数停售",
                    "分页扫描测试",
                    true,
                    "SUSPENDED",
                    "规则原因",
                    List.of(new RuleCondition("SKU_COUNT", "GTE", threshold, null)),
                    0)));
  }

  <T> T tx(UUID tenant, java.util.function.Supplier<T> work) {
    return access.run(actor(tenant), "catalog.spu.read", work);
  }

  // ------------------------------------------------------------ 多租户三断言

  @Test
  void lifecycleIsTenantIsolatedAndHeaderForgeryIsIgnored() throws Exception {
    UUID mine = product();
    readySku(mine);
    change(mine, "PENDING_LISTING", "提交待上架");
    UUID theirs = product(b, "B-" + UUID.randomUUID());

    // 1) 两租户互不可见
    assertThat(tx(a, () -> lifecycle.rules())).isNotNull();
    assertThat(tx(b, () -> lifecycle.rules())).isEmpty();
    assertThat(tx(b, () -> lifecycle.history(mine))).isEmpty();
    assertThat(tx(b, () -> lifecycle.history(theirs))).isEmpty();
    assertThatThrownBy(() -> tx(b, () -> lifecycle.view(mine)))
        .hasMessageContaining("记录不存在或没有访问权限");
    assertThat(tx(b, () -> lifecycle.view(theirs)).status()).isEqualTo("DRAFT");

    // 2) 伪造 tenant header 无效
    http.perform(
            get("/catalog/products/" + mine + "/lifecycle")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/products/" + mine + "/lifecycle")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());
    http.perform(
            post("/catalog/products/" + mine + "/lifecycle")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content("{\"toStatus\":\"ON_SALE\",\"reason\":\"伪造\",\"version\":0}"))
        .andExpect(status().isNotFound());

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(a, () -> lifecycle.history(mine))).isNotEmpty();
      assertThat(tx(b, () -> lifecycle.history(mine))).isEmpty();
      assertThat(tx(b, () -> lifecycle.history(theirs))).isEmpty();
    }
  }

  @Test
  void everyLifecycleTableHasForcedRls() {
    var weak =
        sql.fetch(
            "select relname from pg_class join pg_namespace n on n.oid=relnamespace"
                + " where n.nspname='public' and relkind='r' and relname like 'spu_lifecycle%'"
                + " and (not relrowsecurity or not relforcerowsecurity)");
    assertThat(weak).isEmpty();
    var role =
        sql.fetchOne("select rolsuper,rolbypassrls from pg_roles where rolname=current_user");
    assertThat(role.get("rolsuper", Boolean.class)).isFalse();
    assertThat(role.get("rolbypassrls", Boolean.class)).isFalse();
  }

  // ------------------------------------------------------------ HTTP 契约

  @Test
  void httpEndpointsExposeMatrixRulesAndMetrics() throws Exception {
    UUID p = product();
    readySku(p);
    http.perform(get("/catalog/lifecycle/metrics").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").exists());
    http.perform(
            get("/catalog/products/" + p + "/lifecycle").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.transitions[0].toStatus").value("PENDING_LISTING"))
        .andExpect(jsonPath("$.transitions[0].reasonRequired").value(true));
    http.perform(get("/catalog/lifecycle/rules").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    // 只读角色不能流转。
    http.perform(
            post("/catalog/products/" + p + "/lifecycle")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content("{\"toStatus\":\"PENDING_LISTING\",\"reason\":\"x\",\"version\":0}"))
        .andExpect(status().isForbidden());
    // 未接入投影的规则通过 HTTP 返回明确的 422。
    UUID rule =
        call(
            actor(a),
            "catalog.spu.create",
            () ->
                lifecycle.saveRule(
                    actor(a),
                    null,
                    new RuleInput(
                        "HTTP-STOCK",
                        "库存规则",
                        "未接入",
                        true,
                        "SUSPENDED",
                        "规则原因",
                        List.of(
                            new RuleCondition(
                                "INVENTORY_ON_HAND", "GTE", new BigDecimal("1"), null)),
                        0)));
    http.perform(
            get("/catalog/lifecycle/rules/" + rule + "/preview")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isUnprocessableEntity());
    http.perform(
            post("/catalog/lifecycle/rules")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"code\":\"NEW-RULE\",\"name\":\"新规则\",\"description\":\"\",\"enabled\":true,"
                        + "\"targetStatus\":\"SUSPENDED\",\"reasonTemplate\":\"原因\",\"version\":0,"
                        + "\"conditions\":[{\"metric\":\"ACTIVE_SKU_COUNT\",\"operator\":\"GTE\",\"threshold\":1}]}"))
        .andExpect(status().isOk());
  }
}
