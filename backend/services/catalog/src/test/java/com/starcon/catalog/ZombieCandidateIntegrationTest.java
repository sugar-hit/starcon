package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.ZombieCandidateModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.9.2.2 僵尸主数据清理：候选判定、引用快照接入、批量停用、停用记录与租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>时间断言刻意写成「断言写回的值」而不是「断言没抛异常」：本模块的「长期未维护」
 * 判定完全依赖 {@code timestamptz}，而 {@code Timestamp.valueOf(LocalDateTime)} 在东八区
 * 会静默偏 -28800s —— 那样的 bug 不抛异常，只会让"刚过阈值 8 小时的 SKU"被算成没维护。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ZombieCandidateIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final ObjectMapper json = new ObjectMapper();
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
            byte[] body = json.writeValueAsBytes(access);
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
  @Autowired ZombieCandidateService zombies;
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

  // ================================================================ 数据准备

  /** 建一个 SPU，返回其 id。 */
  UUID spu(UUID tenant, String code) {
    return call(
        actor(tenant),
        "catalog.spu.create",
        () ->
            catalog.saveSpu(
                actor(tenant), null, new SpuInput(code, "僵尸清理测试", "", "Brand", "", "DRAFT", 0)));
  }

  /**
   * 建一个 SKU，返回其 id。
   *
   * <p>版本号必须**现取**：每建一个 SKU 都会让 SPU 的 version +1，硬传 0 的话第二个就撞乐观锁
   * （症状是 409「商品已被修改」—— 与本模块无关，但会让用例莫名其妙地红）。
   */
  UUID sku(UUID tenant, UUID spuId, String code) {
    var version =
        call(
                actor(tenant),
                "catalog.spu.read",
                () ->
                    sql.fetchOne("select version from spu where tenant_id=? and id=?", tenant, spuId))
            .get(0, Long.class);
    return call(
        actor(tenant),
        "catalog.sku.create",
        () ->
            catalog.saveSku(
                actor(tenant),
                spuId,
                null,
                new SkuInput(code, "SKU " + code, "", "件", "ACTIVE", List.of(), version)));
  }

  /**
   * 把主数据的最后维护时刻往回拨 {@code days} 天。
   *
   * <p>直连 SQL 而不是加个"创建于过去"的入口：主数据的 {@code updated_at} 由服务层维护，
   * 让业务接口接受任意过去时刻等于开一个"可以把任何记录改成很久以前"的后门。
   * 这里用 {@code ?::timestamptz} 绑定 OffsetDateTime —— 与服务层同一手法。
   */
  void backdate(String table, UUID tenant, UUID id, long days) {
    // 截断到毫秒：PG 的 timestamptz 是微秒精度，用带纳秒的「期望值」去比会恒不相等，
    // 而那种失败与时区无关、纯粹是精度噪音，会盖掉真正的时区断言。
    var moment = OffsetDateTime.now(ZoneOffset.UTC).minusDays(days).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    call(
        actor(tenant),
        "catalog.spu.read",
        () -> {
          sql.execute(
              "update " + table + " set updated_at=?::timestamptz where tenant_id=? and id=?",
              moment, tenant, id);
          return null;
        });
  }

  /**
   * 保存规则配置。
   *
   * <p>{@code input.version()} 传 {@code -1L} 表示「用当前版本」—— 测试共享同一个库，
   * 规则行在第一个用例里就建好并一路自增，所以每个用例都要自己取一次。
   *
   * <p>刻意**不**在这里无条件吞掉版本冲突：那会把「乐观锁根本没生效」也一起测成绿的。
   * 真正验证乐观锁的用例显式传一个错的版本号。
   */
  void configure(ZombieEntityType type, CleanupRuleInput input) {
    final CleanupRuleInput effective;
    if (input.version() < 0) {
      var current = call(actor, "catalog.spu.read", () -> zombies.rule(type));
      effective =
          new CleanupRuleInput(
              input.inactiveDays(),
              input.snapshotStaleDays(),
              input.requireZeroOrderReference(),
              input.requireZeroInventory(),
              input.requireZeroListing(),
              input.requireZeroCost(),
              input.enabled(),
              input.deactivateNote(),
              current.version());
    } else {
      effective = input;
    }
    call(actor, "catalog.spu.update", () -> zombies.saveRule(actor, type, effective));
  }

  /** 规则：全部维度关闭，只按维护阈值判。 */
  void ruleOnly(int inactiveDays) {
    configure(
        ZombieEntityType.SKU,
new CleanupRuleInput(inactiveDays, 90, false, false, false, false, true, "清理测试", -1L));
  }

  ReferenceStatPushResult push(UUID tenant, ZombieEntityType type, ReferenceStatItem... items) {
    return call(
        actor(tenant),
        "catalog.spu.update",
        () -> zombies.pushStats(actor(tenant), type, new ReferenceStatPush(List.of(items))));
  }

  ReferenceStatItem stat(UUID entityId, ZombieMetric metric, String value, OffsetDateTime at) {
    return new ReferenceStatItem(
        entityId, metric.name(), new BigDecimal(value), metric.source(), at);
  }

  CandidatePage list(UUID tenant, String entityType, String keyword, String verdict, int page, int size) {
    return call(
        actor(tenant),
        "catalog.spu.read",
        () -> zombies.list(new CandidateQuery(entityType, keyword, verdict, page, size)));
  }

  // ================================================================ 判定与规则

  @Test
  void 默认规则四个维度全部关闭() {
    var rules = call(actor, "catalog.spu.read", () -> zombies.rules());
    assertThat(rules).hasSize(3);
    for (var rule : rules) {
      assertThat(rule.requiredDimensionCount()).isZero();
      assertThat(rule.snapshotStaleDays()).isEqualTo(90);
    }
  }

  @Test
  void 规则可保存且版本自增() {
    ruleOnly(180);
    var saved = call(actor, "catalog.spu.read", () -> zombies.rule(ZombieEntityType.SKU));
    assertThat(saved.inactiveDays()).isEqualTo(180);
    assertThat(saved.configured()).isTrue();
    

    configure(

    ZombieEntityType.SKU,
    new CleanupRuleInput(200, 60, false, true, false, false, true, "改了", saved.version()));
    var again = call(actor, "catalog.spu.read", () -> zombies.rule(ZombieEntityType.SKU));
    assertThat(again.inactiveDays()).isEqualTo(200);
    assertThat(again.snapshotStaleDays()).isEqualTo(60);
    assertThat(again.requireZeroInventory()).isTrue();
    assertThat(again.version()).isEqualTo(saved.version() + 1);
  }

  @Test
  void 规则乐观锁冲突给409() {
    ruleOnly(180);
    // 显式传一个错的版本号：规则配置是多人协作改的，版本对不上必须拒而不是覆盖。
    assertThatThrownBy(
            () ->
                configure(
                    ZombieEntityType.SKU,
                    new CleanupRuleInput(300, 90, false, false, false, false, true, "x", 99L)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("刷新后重试");
    // 冲突之后状态没被写坏。
    var rule = call(actor, "catalog.spu.read", () -> zombies.rule(ZombieEntityType.SKU));
    assertThat(rule.inactiveDays()).isEqualTo(180);
  }

  @Test
  void 超出维护阈值才是候选_维护期内不是() {
    ruleOnly(365);
    var old = sku(a, spu(a, "P-" + UUID.randomUUID()), "OLD-" + UUID.randomUUID().toString().substring(0, 8));
    var fresh = sku(a, spu(a, "P-" + UUID.randomUUID()), "FRESH-" + UUID.randomUUID().toString().substring(0, 8));
    backdate("sku", a, old, 400);
    backdate("sku", a, fresh, 3);

    var page = list(a, "SKU", null, null, 0, 50);
    assertThat(page.countsConsistent()).isTrue();
    // 候选清单的时间窗口只看「超出维护阈值」的：FRESH 才维护过 3 天，
    // 它连 scored CTE 都进不去（而不是"进去了被标成在用"）——
    // 两种口径在计数上等价，但后者会让运营以为刚维护过的也要逐条复核。
    assertThat(page.items()).extracting(Candidate::code)
        .contains(codeOf(old)).doesNotContain(codeOf(fresh));
  }

  @Test
  void 缺观测判无法判定而不是候选() {
    ruleOnly(365);
    configure(
    ZombieEntityType.SKU,
    new CleanupRuleInput(365, 90, true, false, false, false, true, "x", -1L));
    var product = spu(a, "P-" + UUID.randomUUID());
    var orphan = sku(a, product, "NOOBS-" + UUID.randomUUID().toString().substring(0, 8));
    backdate("sku", a, orphan, 400);

    var page = list(a, "SKU", null, "INCOMPLETE", 0, 50);
    assertThat(page.items()).extracting(Candidate::code).contains(codeOf(orphan));
    assertThat(page.items().get(0).verdict()).isEqualTo("INCOMPLETE");
    assertThat(page.items().get(0).verdictLabel()).isEqualTo("无法判定");
  }

  @Test
  void 观测到零才是候选() {
    ruleOnly(365);
    configure(
    ZombieEntityType.SKU,
    new CleanupRuleInput(365, 90, false, true, false, false, true, "x", -1L));
    // 每个 SKU 挂各自的 SPU：同一 SPU 下两个无属性 SKU 的 variant_signature 相同，
    // 会撞 UNIQUE(tenant_id,spu_id,variant_signature)（与本模块无关，但不改这里全是无关的红）。
    var withStock = sku(a, spu(a, "P-" + UUID.randomUUID()), "STK-" + UUID.randomUUID().toString().substring(0, 8));
    var noStock = sku(a, spu(a, "P-" + UUID.randomUUID()), "NOSTK-" + UUID.randomUUID().toString().substring(0, 8));
    backdate("sku", a, withStock, 400);
    backdate("sku", a, noStock, 400);

    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var pushed =
        push(
            a,
            ZombieEntityType.SKU,
            stat(withStock, ZombieMetric.INVENTORY_ON_HAND, "12", now),
            stat(noStock, ZombieMetric.INVENTORY_ON_HAND, "0", now));
    assertThat(pushed.written()).isEqualTo(2);

    var candidates = list(a, "SKU", null, "CANDIDATE", 0, 50);
    assertThat(candidates.items()).extracting(Candidate::code).contains(codeOf(noStock));
    var inUse = list(a, "SKU", null, "NOT_ZOMBIE", 0, 50);
    assertThat(inUse.items()).extracting(Candidate::code).contains(codeOf(withStock)).doesNotContain(codeOf(noStock));
  }

  @Test
  void 过期观测判无法判定而不是沿用旧值() {
    ruleOnly(365);
    configure(
    ZombieEntityType.SKU,
    new CleanupRuleInput(365, 30, false, true, false, false, true, "x", -1L));
    var product = spu(a, "P-" + UUID.randomUUID());
    var stale = sku(a, product, "STALE-" + UUID.randomUUID().toString().substring(0, 8));
    backdate("sku", a, stale, 400);
    // 观测值是 0，但观测时刻在 200 天前 > 30 天过期阈值。
    push(a, ZombieEntityType.SKU,
        stat(stale, ZombieMetric.INVENTORY_ON_HAND, "0",
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(200)));

    var incomplete = list(a, "SKU", null, "INCOMPLETE", 0, 50);
    var row = incomplete.items().stream().filter(c -> c.code().equals(codeOf(stale))).findFirst();
    assertThat(row).isPresent();
    var dimension = row.get().dimensions().get(1);
    assertThat(dimension.state()).isEqualTo("UNKNOWN");
    assertThat(dimension.stale()).isTrue();
    // 关键：过期信息可见（值与观测时刻仍在），而不是被悄悄按"从未观测"处理。
    assertThat(dimension.value()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(dimension.observedAt()).isNotNull();
    // 且**不能**被当成候选停用。
    assertThat(list(a, "SKU", null, "CANDIDATE", 0, 50).items())
        .extracting(Candidate::code).doesNotContain(codeOf(stale));
  }

  @Test
  void 关键词搜索覆盖编码与名称且通配符被转义() {
    ruleOnly(365);
    var product = spu(a, "P-" + UUID.randomUUID());
    var hit = sku(a, product, "FINDME-" + UUID.randomUUID().toString().substring(0, 8));
    backdate("sku", a, hit, 400);
    assertThat(list(a, "SKU", "FINDME-", null, 0, 50).items())
        .extracting(Candidate::code).contains(codeOf(hit));
    // 不转义的话 "%" 会变成"匹配所有"。
    assertThat(list(a, "SKU", "%", null, 0, 500).total())
        .isLessThanOrEqualTo(list(a, "SKU", null, null, 0, 500).total());
  }

  // ================================================================ timestamptz 往返（要求 5）

  @Test
  void 观测时刻写回值一致_不偏八小时() {
    ruleOnly(365);
    var product = spu(a, "P-" + UUID.randomUUID());
    var target = sku(a, product, "TS-" + UUID.randomUUID().toString().substring(0, 8));
    backdate("sku", a, target, 400);
    // 东八区偏移。若服务层用 Timestamp.valueOf(LocalDateTime) 绑定，这里会偏 -28800s。
    var observed = OffsetDateTime.of(2026, 2, 20, 15, 30, 0, 0, ZoneOffset.ofHours(8));
    push(a, ZombieEntityType.SKU,
        stat(target, ZombieMetric.ORDER_LINE_COUNT, "0", observed));

    var evidence =
        call(actor, "catalog.spu.read", () -> zombies.evidence(ZombieEntityType.SKU, target));
    var dimension = evidence.dimensions().get(0);
    assertThat(dimension.value()).isEqualByComparingTo(BigDecimal.ZERO);
    // 断言**写回的值**，而不是"没抛异常"。
    assertThat(dimension.observedAt()).isNotNull();
    assertThat(dimension.observedAt().toInstant()).isEqualTo(observed.toInstant());
    assertThat(dimension.observedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(8));
  }

  @Test
  void 最后维护时刻走timestamptz往返() {
    ruleOnly(365);
    var product = spu(a, "P-" + UUID.randomUUID());
    var target = sku(a, product, "MT-" + UUID.randomUUID().toString().substring(0, 8));
    var moment = OffsetDateTime.now(ZoneOffset.UTC).minusDays(500).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    backdate("sku", a, target, 500);
    var stored =
        call(
            actor,
            "catalog.spu.read",
            () ->
                sql.fetchOne("select updated_at from sku where tenant_id=? and id=?", a, target)
                    .get(0, OffsetDateTime.class));
    assertThat(stored.toInstant()).isEqualTo(moment.toInstant());
  }

  // ================================================================ 批量停用

  @Test
  void 批量停用落库并留停用记录() {
    ruleOnly(365);
    var one = sku(a, spu(a, "P-" + UUID.randomUUID()), "D1-" + UUID.randomUUID().toString().substring(0, 8));
    var two = sku(a, spu(a, "P-" + UUID.randomUUID()), "D2-" + UUID.randomUUID().toString().substring(0, 8));
    backdate("sku", a, one, 400);
    backdate("sku", a, two, 400);

    var result =
        call(
            actor,
            "catalog.spu.update",
            () ->
                zombies.deactivate(
                    actor, ZombieEntityType.SKU, new DeactivateInput(List.of(one, two), "长期未使用")));
    assertThat(result.affected()).isEqualTo(2);
    assertThat(result.entries()).extracting(DeactivatedEntry::previousStatus).containsOnly("ACTIVE");

    // 真的改成了 INACTIVE（停用而非删除：行还在）。
    for (var id : List.of(one, two)) {
      var row =
          call(
              actor,
              "catalog.spu.read",
              () -> sql.fetchOne("select status from sku where tenant_id=? and id=?", a, id));
      assertThat(row).isNotNull();
      assertThat(row.get("status", String.class)).isEqualTo("INACTIVE");
    }

    // 停用记录：逐条一行，冻结了规则与证据。
    var records = call(actor, "catalog.spu.read", () -> zombies.records(new RecordQuery("SKU", null, null, 0, 50)));
    assertThat(records.items()).extracting(CleanupRecord::entityId).contains(one, two);
    var record = records.items().stream().filter(r -> r.entityId().equals(one)).findFirst().orElseThrow();
    assertThat(record.reason()).isEqualTo("长期未使用");
    assertThat(record.newStatus()).isEqualTo("INACTIVE");
    assertThat(record.ruleSnapshot()).isNotNull();
    assertThat(record.evidence()).isNotNull();
    assertThat(record.affectedCount()).isEqualTo(2);
    assertThat(record.decidedBy()).isEqualTo(user);
  }

  @Test
  void 停用记录不可改() {
    // 先造一条记录。
    ruleOnly(365);
    var product = spu(a, "P-" + UUID.randomUUID());
    var target = sku(a, product, "RO-" + UUID.randomUUID().toString().substring(0, 8));
    backdate("sku", a, target, 400);
    call(
        actor,
        "catalog.spu.update",
        () ->
            zombies.deactivate(
                actor, ZombieEntityType.SKU, new DeactivateInput(List.of(target), "原始理由")));

    // 停用记录只授 SELECT/INSERT；UPDATE/DELETE 必须在 DB 层被拒（可篡改审计是不允许的）。
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.spu.read",
                    () ->
                        sql.execute(
                            "update master_data_cleanup_record set reason='篡改' where tenant_id=?",
                            a)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.spu.read",
                    () ->
                        sql.execute("delete from master_data_cleanup_record where tenant_id=?", a)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);

    // 断言没被改掉（"抛了异常"本身不够：万一抛的是别的错）。
    var reason =
        call(
            actor,
            "catalog.spu.read",
            () ->
                sql.fetchOne(
                        "select reason from master_data_cleanup_record where tenant_id=? and entity_id=?",
                        a, target)
                    .get("reason", String.class));
    assertThat(reason).isEqualTo("原始理由");
    assertThat(
            call(
                    actor,
                    "catalog.spu.read",
                    () ->
                        sql.fetchOne("select count(*) from master_data_cleanup_record where tenant_id=?",
                            a))
                .get(0, Long.class))
        .isEqualTo(1L);
  }

  @Test
  void 维护期内的主数据不能被停用_整批拒绝() {
    ruleOnly(365);
    var stale = sku(a, spu(a, "P-" + UUID.randomUUID()), "S-" + UUID.randomUUID().toString().substring(0, 8));
    var fresh = sku(a, spu(a, "P-" + UUID.randomUUID()), "F-" + UUID.randomUUID().toString().substring(0, 8));
    backdate("sku", a, stale, 400);
    backdate("sku", a, fresh, 1);

    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.spu.update",
                    () ->
                        zombies.deactivate(
                            actor, ZombieEntityType.SKU,
                            new DeactivateInput(List.of(stale, fresh), "批量"))))
        .isInstanceOf(ZombieCandidateService.ZombieBatchRejectedException.class);

    // Z4：整批拒绝，**一条都不动**（不能跳过后继续停用其余）。
    var untouched =
        call(
            actor,
            "catalog.spu.read",
            () -> sql.fetchOne("select status from sku where tenant_id=? and id=?", a, stale));
    assertThat(untouched.get("status", String.class)).isEqualTo("ACTIVE");
  }

  @Test
  void 无法判定的条目不能被停用() {
    ruleOnly(365);
    configure(
    ZombieEntityType.SKU,
    new CleanupRuleInput(365, 90, true, false, false, false, true, "x", -1L));
    var product = spu(a, "P-" + UUID.randomUUID());
    var target = sku(a, product, "I-" + UUID.randomUUID().toString().substring(0, 8));
    backdate("sku", a, target, 400);
    // 启用了订单维度但没有推送观测 → INCOMPLETE。
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.spu.update",
                    () ->
                        zombies.deactivate(
                            actor, ZombieEntityType.SKU,
                            new DeactivateInput(List.of(target), "停用"))))
        .isInstanceOf(ZombieCandidateService.ZombieBatchRejectedException.class);
  }

  @Test
  void 已经停用的主数据不重复停用() {
    ruleOnly(365);
    var product = spu(a, "P-" + UUID.randomUUID());
    var target = sku(a, product, "Z-" + UUID.randomUUID().toString().substring(0, 8));
    backdate("sku", a, target, 400);
    call(
        actor,
        "catalog.spu.update",
        () ->
            zombies.deactivate(
                actor, ZombieEntityType.SKU, new DeactivateInput(List.of(target), "第一次")));
    // 第二次：Z6 —— 已经是 INACTIVE，不再重复停用、不再产生重复记录。
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.spu.update",
                    () ->
                        zombies.deactivate(
                            actor, ZombieEntityType.SKU,
                            new DeactivateInput(List.of(target), "第二次"))))
        .isInstanceOf(ZombieCandidateService.ZombieBatchRejectedException.class);
    var count =
        call(
            actor,
            "catalog.spu.read",
            () ->
                sql.fetchOne(
                        "select count(*) from master_data_cleanup_record where tenant_id=? and entity_id=?",
                        a, target)
                    .get(0, Long.class));
    assertThat(count).isEqualTo(1L);
  }

  @Test
  void 停用主数据后不再出现在候选清单() {
    ruleOnly(365);
    var product = spu(a, "P-" + UUID.randomUUID());
    var target = sku(a, product, "G-" + UUID.randomUUID().toString().substring(0, 8));
    backdate("sku", a, target, 400);
    assertThat(list(a, "SKU", null, "CANDIDATE", 0, 50).items())
        .extracting(Candidate::code).contains(codeOf(target));
    call(
        actor,
        "catalog.spu.update",
        () ->
            zombies.deactivate(
                actor, ZombieEntityType.SKU, new DeactivateInput(List.of(target), "停用")));
    assertThat(list(a, "SKU", null, "CANDIDATE", 0, 50).items())
        .extracting(Candidate::code).doesNotContain(codeOf(target));
  }

  // ================================================================ 供应商与客户

  @Test
  void 供应商与客户也走同一套判定() {
    ruleOnly(365);
    UUID supplier = party(a, "SUP-" + UUID.randomUUID().toString().substring(0, 8), "SUPPLIER");
    UUID customer = party(a, "CUS-" + UUID.randomUUID().toString().substring(0, 8), "CUSTOMER");
    backdate("party", a, supplier, 400);
    backdate("party", a, customer, 400);

    for (var type : List.of(ZombieEntityType.SUPPLIER, ZombieEntityType.CUSTOMER)) {
      configure(
          type,
          new CleanupRuleInput(365, 90, false, false, false, false, true, "清理", -1L));
      var page = list(a, type.name(), null, "CANDIDATE", 0, 50);
      var expected = type == ZombieEntityType.SUPPLIER ? supplier : customer;
      assertThat(page.items()).extracting(Candidate::entityId).contains(expected);
    }
  }

  @Test
  void 供应商停用后写入party状态与理由() {
    ruleOnly(365);
    UUID supplier = party(a, "SUP2-" + UUID.randomUUID().toString().substring(0, 8), "SUPPLIER");
    backdate("party", a, supplier, 400);
    configure(
    ZombieEntityType.SUPPLIER,
    new CleanupRuleInput(365, 90, false, false, false, false, true, "默认停用文案", -1L));
    call(
        actor,
        "catalog.spu.update",
        () ->
            zombies.deactivate(
                actor, ZombieEntityType.SUPPLIER,
                new DeactivateInput(List.of(supplier), "僵尸供应商")));
    var row =
        call(
            actor,
            "catalog.spu.read",
            () ->
                sql.fetchOne(
                    "select status,status_reason,status_changed_at from party where tenant_id=? and id=?",
                    a, supplier));
    assertThat(row.get("status", String.class)).isEqualTo("INACTIVE");
    assertThat(row.get("status_reason", String.class)).isEqualTo("僵尸供应商");
    assertThat(row.get("status_changed_at", OffsetDateTime.class)).isNotNull();
  }

  // ================================================================ 上游接入

  @Test
  void 推送引用观测可覆盖同一维度() {
    ruleOnly(365);
    var product = spu(a, "P-" + UUID.randomUUID());
    var target = sku(a, product, "UPD-" + UUID.randomUUID().toString().substring(0, 8));
    backdate("sku", a, target, 400);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    push(a, ZombieEntityType.SKU, stat(target, ZombieMetric.ORDER_LINE_COUNT, "0", now));
    var again =
        push(a, ZombieEntityType.SKU, stat(target, ZombieMetric.ORDER_LINE_COUNT, "7", now.plusMinutes(1)));
    assertThat(again.written()).isEqualTo(1);

    // 最新一次观测胜出：保留旧值会让"最近有订单了但仍显示 0"这种最危险的错误一直存在。
    var evidence =
        call(actor, "catalog.spu.read", () -> zombies.evidence(ZombieEntityType.SKU, target));
    assertThat(evidence.dimensions().get(0).value()).isEqualByComparingTo("7");
  }

  @Test
  void 推送不存在的主体被拒且逐条回报原因() {
    var result =
        push(
            a,
            ZombieEntityType.SKU,
            stat(UUID.randomUUID(), ZombieMetric.INVENTORY_ON_HAND, "0",
                OffsetDateTime.now(ZoneOffset.UTC)));
    assertThat(result.rejected()).isEqualTo(1);
    assertThat(result.rejectedReasons()).hasSize(1);
    assertThat(result.rejectedReasons().get(0)).contains("主体不存在");
  }

  @Test
  void 供应商身份的主体不能推客户维度的观测() {
    // 触发器校验角色：给一个只有 SUPPLIER 身份的主体推 CUSTOMER 引用会被拒。
    UUID supplier = party(a, "MIX-" + UUID.randomUUID().toString().substring(0, 8), "SUPPLIER");
    var result =
        push(
            a,
            ZombieEntityType.CUSTOMER,
            new ReferenceStatItem(
                supplier, ZombieMetric.ORDER_LINE_COUNT.name(), BigDecimal.ZERO, "trade",
                OffsetDateTime.now(ZoneOffset.UTC)));
    assertThat(result.rejected()).isEqualTo(1);
  }

  @Test
  void 词表标出哪些上游已接入() {
    var options = call(actor, "catalog.spu.read", () -> zombies.options());
    assertThat(options.metrics()).hasSize(4);
    var inventory =
        options.metrics().stream()
            .filter(m -> m.code().equals("INVENTORY_ON_HAND"))
            .findFirst()
            .orElseThrow();
    assertThat(inventory.wired()).isTrue();
    assertThat(inventory.owner()).isEqualTo("07 库存");
    // 订单与成本的归属服务在本仓库不存在 —— 界面对这两个维度禁用开关。
    assertThat(options.metrics().stream().filter(m -> m.code().equals("ORDER_LINE_COUNT"))
            .findFirst().orElseThrow().wired()).isFalse();

    var overview = call(actor, "catalog.spu.read", () -> zombies.overview());
    assertThat(overview.rows()).hasSize(3);
    assertThat(overview.unmappedDimensions()).isNotEmpty();
  }

  // ================================================================ HTTP 全链路

  @Test
  void http链路读写权限分离() throws Exception {
    ruleOnly(365);
    var product = spu(a, "P-" + UUID.randomUUID());
    var target = sku(a, product, "H-" + UUID.randomUUID().toString().substring(0, 8));
    backdate("sku", a, target, 400);

    http.perform(get("/catalog/zombie-candidates").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/zombie-candidates").header("Authorization", "Bearer reader")
            .param("entityType", "SKU"))
        .andExpect(status().isOk());
    // reader 没有 update 权限 → 停用 403。
    http.perform(post("/catalog/zombie-candidates/deactivate")
            .header("Authorization", "Bearer reader")
            .param("entityType", "SKU")
            .contentType("application/json")
            .content(json.writeValueAsString(new DeactivateInput(List.of(target), "x"))))
        .andExpect(status().isForbidden());
    // 缺 token → 401。
    http.perform(get("/catalog/zombie-candidates")).andExpect(status().isUnauthorized());
  }

  @Test
  void http保存规则走乐观锁() throws Exception {
    http.perform(put("/catalog/zombie-candidates/rules/SKU")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(
                "{\"inactiveDays\":180,\"snapshotStaleDays\":90,"
                    + "\"requireZeroOrderReference\":false,\"requireZeroInventory\":false,"
                    + "\"requireZeroListing\":false,\"requireZeroCost\":false,"
                    + "\"enabled\":true,\"deactivateNote\":\"清理\",\"version\":99}"))
        .andExpect(status().isConflict());
  }

  @Test
  void http推送引用观测() throws Exception {
    ruleOnly(365);
    var product = spu(a, "P-" + UUID.randomUUID());
    var target = sku(a, product, "PS-" + UUID.randomUUID().toString().substring(0, 8));
    backdate("sku", a, target, 400);
    http.perform(post("/catalog/zombie-candidates/reference-stats")
            .header("Authorization", "Bearer tenant-a")
            .param("entityType", "SKU")
            .contentType("application/json")
            .content(
                "{\"items\":[{\"entityId\":\"" + target + "\",\"metric\":\"INVENTORY_ON_HAND\","
                    + "\"metricValue\":0,\"source\":\"warehouse\",\"observedAt\":\""
                    + OffsetDateTime.now(ZoneOffset.UTC) + "\"}]}"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("\"written\":1")));
  }

  @Test
  void http批量停用被拒时返回blocked清单() throws Exception {
    ruleOnly(365);
    var product = spu(a, "P-" + UUID.randomUUID());
    var stale = sku(a, product, "SB-" + UUID.randomUUID().toString().substring(0, 8));
    backdate("sku", a, stale, 400);
    http.perform(post("/catalog/zombie-candidates/deactivate")
            .header("Authorization", "Bearer tenant-a")
            .param("entityType", "SKU")
            .contentType("application/json")
            .content(
                json.writeValueAsString(
                    new DeactivateInput(List.of(stale), "x"))))
        .andExpect(status().isOk());
    // 再来一次 → 409 + blocked 清单
    http.perform(post("/catalog/zombie-candidates/deactivate")
            .header("Authorization", "Bearer tenant-a")
            .param("entityType", "SKU")
            .contentType("application/json")
            .content(
                json.writeValueAsString(
                    new DeactivateInput(List.of(stale), "x"))))
        .andExpect(status().isConflict())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("blocked")));
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 租户隔离三断言() throws Exception {
    ruleOnly(365);
    var theirSku = sku(b, spu(b, "P-" + UUID.randomUUID()), "B-" + UUID.randomUUID().toString().substring(0, 8));
    backdate("sku", b, theirSku, 400);
    var mySku = sku(a, spu(a, "P-" + UUID.randomUUID()), "A-" + UUID.randomUUID().toString().substring(0, 8));
    backdate("sku", a, mySku, 400);
    push(b, ZombieEntityType.SKU,
        stat(theirSku, ZombieMetric.INVENTORY_ON_HAND, "0", OffsetDateTime.now(ZoneOffset.UTC)));

    // 1) 两租户互不可见：候选清单、快照、停用记录三处都不含对方数据
    var mine = list(a, "SKU", null, null, 0, 500);
    assertThat(mine.items()).extracting(Candidate::entityId).contains(mySku).doesNotContain(theirSku);
    var theirs = list(b, "SKU", null, null, 0, 500);
    assertThat(theirs.items()).extracting(Candidate::entityId).contains(theirSku).doesNotContain(mySku);

    var myRecords = call(actor, "catalog.spu.read", () -> zombies.records(new RecordQuery("SKU", null, null, 0, 200)));
    assertThat(myRecords.items()).extracting(CleanupRecord::entityId).doesNotContain(theirSku);

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系
    http.perform(get("/catalog/zombie-candidates/evidence")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b)
            .param("entityType", "SKU")
            .param("entityId", theirSku.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/catalog/zombie-candidates/evidence")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b)
            .param("entityType", "SKU")
            .param("entityId", mySku.toString()))
        .andExpect(status().isOk());
    // 伪造头也不能借道停用别人的 SKU。
    http.perform(post("/catalog/zombie-candidates/deactivate")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b)
            .param("entityType", "SKU")
            .contentType("application/json")
            .content(json.writeValueAsString(new DeactivateInput(List.of(theirSku), "伪造"))))
        .andExpect(status().isNotFound());
    // 伪造头也不能借道推快照到别人的租户。
    http.perform(post("/catalog/zombie-candidates/reference-stats")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b)
            .param("entityType", "SKU")
            .contentType("application/json")
            .content(
                "{\"items\":[{\"entityId\":\"" + theirSku + "\",\"metric\":\"ORDER_LINE_COUNT\","
                    + "\"metricValue\":99,\"source\":\"trade\",\"observedAt\":\""
                    + OffsetDateTime.now(ZoneOffset.UTC) + "\"}]}"))
        // 按设计返回 200 + rejected：A 的租户命名空间里根本没有 B 的 SKU，
        // 逐条拒收比整批 404 更能说清"为什么没写进去"。
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("\"rejected\":1")));
    // 断言行确实还在 B 名下、且值没有被 A 的伪造推送改掉。
    var stillTheirs =
        call(
            actor(b),
            "catalog.spu.read",
            () ->
                sql.fetchOne(
                        "select metric_value from master_data_reference_stats"
                            + " where tenant_id=? and entity_id=? and metric='INVENTORY_ON_HAND'",
                        b, theirSku)
                    .get("metric_value", BigDecimal.class));
    assertThat(stillTheirs).isEqualByComparingTo(BigDecimal.ZERO);

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言快照集合不相交
    for (int i = 0; i < 5; i++) {
      assertThat(list(a, "SKU", null, null, 0, 500).items())
          .extracting(Candidate::entityId).doesNotContain(theirSku);
      assertThat(list(b, "SKU", null, null, 0, 500).items())
          .extracting(Candidate::entityId).doesNotContain(mySku);
    }
  }

  @Test
  void 三张表都强制RLS() {
    // 三张表 tenant_id NOT NULL，且 ENABLE + FORCE RLS isolation 策略逐字相同。
    for (var table :
        List.of("master_data_reference_stats", "master_data_cleanup_rule", "master_data_cleanup_record")) {
      var rls =
          call(
              actor,
              "catalog.spu.read",
              () ->
                  sql.fetchOne(
                      "select relrowsecurity,relforcerowsecurity from pg_class where relname=?",
                      table));
      assertThat(rls.get("relrowsecurity", Boolean.class)).isTrue();
      assertThat(rls.get("relforcerowsecurity", Boolean.class)).isTrue();
    }
  }

  // ================================================================ 工具

  /**
   * 建一个主体并指派角色（供应商 / 客户），返回主体 id。
   *
   * <p>直连 SQL 而不是走 PartyService：本用例要测的是**本模块**对 party 档案的判定与停用，
   * 用对手方的建档 API 只会让这个文件被 P3/P5 那几条规则绑住，而那些规则属于 3.6.1.1。
   * 写入的列仍然满足 V27 的 CHECK（email 非空、有角色），所以触发器与 RLS 都照常生效。
   */
  UUID party(UUID tenant, String code, String roleType) {
    var partyId = UUID.randomUUID();
    call(
        actor(tenant),
        "catalog.spu.read",
        () -> {
          sql.execute(
              "insert into party(id,tenant_id,code,name,party_type,country_code,email,status)"
                  + " values(?,?,?,?,'ORGANIZATION','CN',?,'ACTIVE')",
              partyId, tenant, code, "测试主体 " + code, "zombie-" + code + "@example.com");
          sql.execute(
              "insert into party_role(id,tenant_id,party_id,role_type,status) values(?,?,?,?,'ACTIVE')",
              UUID.randomUUID(), tenant, partyId, roleType);
          return null;
        });
    return partyId;
  }

  /** 取主数据的编码，用于断言清单里出现的确实是这一条。 */
  String codeOf(UUID id) {
    return call(
            actor,
            "catalog.spu.read",
            () -> sql.fetchOne("select code from sku where tenant_id=? and id=?", a, id))
        .get("code", String.class);
  }
}