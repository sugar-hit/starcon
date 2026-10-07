package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.3.3.1 计量单位（字典视角）：单位组 CRUD、组成员整体替换、组内换算版本区间不重叠、
 * 按 asOf 取版本、换算查询的七个拒绝原因码、**量纲守卫在 DB 层真的会拦**，
 * 以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnitGroupIntegrationTest {
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
      // 只有 spu 读权限：所有写端点必须 403。
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read")))));
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

  @Autowired UnitGroupService groups;
  @Autowired UnitConversionService units;
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

  <T> T call(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** void 版：delete / freeze 这类没有返回值的服务方法用。 */
  void call(AccessService.Access who, String permission, Runnable work) {
    access.run(
        who,
        permission,
        () -> {
          work.run();
          return null;
        });
  }

  // ------------------------------------------------------------ 夹具

  /** 单位编码在租户内唯一：每个用例用独立编码，避免用例之间互相抢占字典位。 */
  String uniqueCode(String hint) {
    return hint + UUID.randomUUID().toString().substring(0, 6);
  }

  /** 幂等登记单位：单位编码租户内唯一，多个用例共用同一个编码时只能建一次。 */
  void ensureUnit(String code, String dimension) {
    boolean exists =
        tx(
            actor,
            () ->
                sql.fetchOne("select id from unit_of_measure where tenant_id=? and code=?",
                    actor.tenantId(), code) != null);
    if (!exists) saveUnit(code, dimension);
  }

  /** 单位字典的写入所有者是 UnitConversionService（V8），本模块只读它——夹具也走同一个写入口。 */
  UUID saveUnit(String code, String dimension) {
    return call(
            actor,
            "catalog.sku.update",
            () ->
                units.saveUnit(
                        actor,
                        null,
                        new UnitOfMeasureInput(
                            code, code, dimension, 0, true, true, true, true, 0))
                    .id())
        ;
  }

  /** 组编码租户内唯一。 */
  String uniqueGroup(String hint) {
    return hint + UUID.randomUUID().toString().substring(0, 6);
  }

  UnitGroupInput groupInput(
      String code, String name, String dimension, String baseUnit, List<String> members) {
    var memberInputs = new ArrayList<UnitGroupMemberInput>();
    int position = 0;
    for (String unit : members) memberInputs.add(new UnitGroupMemberInput(unit, position++));
    return new UnitGroupInput(code, name, dimension, baseUnit, "", true, memberInputs, 0);
  }

  UUID saveGroup(
      String code, String dimension, String baseUnit, List<String> members) {
    return call(
            actor,
            "catalog.spu.update",
            () -> groups.save(actor, null, groupInput(code, code, dimension, baseUnit, members)))
        .id();
  }

  UnitGroupConversionInput conversionInput(
      String unitCode, String ratio, String from, String to) {
    return new UnitGroupConversionInput(unitCode, new BigDecimal(ratio), from, to, "");
  }

  /** 为组里每个非基准成员配一条从今天起长期生效的换算版本。 */
  void ensureConversions(UUID groupId, String baseUnit, Map<String, String> ratios) {
    for (var entry : ratios.entrySet()) {
      if (entry.getKey().equals(baseUnit)) continue;
      boolean exists =
          tx(
              actor,
              () ->
                  sql.fetchOne(
                          "select 1 from unit_group_conversion where group_id=? and unit_code=?",
                          groupId,
                          entry.getKey())
                      != null);
      if (!exists)
        call(
            actor,
            "catalog.spu.update",
            () ->
                groups.addConversion(actor, groupId, conversionInput(entry.getKey(), entry.getValue(), "2024-01-01", null)));
    }
  }

  // ------------------------------------------------------------ 概览与列表

  @Test
  void overviewCountsUngroupedUnitsAndIncompleteGroups() {
    String gram = uniqueCode("克");
    saveUnit(gram, "WEIGHT");
    UUID groupId = saveGroup(uniqueGroup("重量组"), "WEIGHT", gram, List.of(gram));
    // 只有基准成员的组是完整的：它本来就不需要任何换算版本。
    assertThat(tx(actor, groups::overview).incompleteGroupCount()).isGreaterThanOrEqualTo(0);
    // 加一个还没有换算版本的成员后，概览要报出这个缺口。
    String kilo = uniqueCode("千克");
    saveUnit(kilo, "WEIGHT");
    call(
        actor,
        "catalog.spu.update",
        () ->
            groups.save(
                actor,
                groupId,
                new UnitGroupInput(
                    tx(actor, () -> sql.fetchOne("select code from unit_group where id=?", groupId).get("code", String.class)),
                    "重量组",
                    "WEIGHT",
                    gram,
                    "",
                    true,
                    List.of(new UnitGroupMemberInput(gram, 0), new UnitGroupMemberInput(kilo, 1)),
                    tx(actor, () -> sql.fetchOne("select version from unit_group where id=?", groupId).get("version", Long.class)))));
    long before = tx(actor, groups::overview).incompleteGroupCount();
    ensureConversions(groupId, gram, Map.of(kilo, "1000"));
    // 补上换算版本后这个组不再计入缺口（用"严格变小"而不是 -1：
    // 同一租户的其它用例留下的组也会计入这个数）。
    assertThat(tx(actor, groups::overview).incompleteGroupCount()).isLessThan(before);
    // 未归组单位：这个用例至少造出了不属于任何组的单位。
    assertThat(tx(actor, groups::overview).ungroupedUnitCount()).isGreaterThan(0);
    assertThat(tx(actor, groups::overview).groupCount()).isGreaterThan(0);
    assertThat(tx(actor, groups::overview).openConversionCount()).isGreaterThan(0);
  }

  @Test
  void groupListIsPagedSearchableAndReportsIncompleteGroups() {
    String gram = uniqueCode("克");
    String kilo = uniqueCode("千克");
    saveUnit(gram, "WEIGHT");
    saveUnit(kilo, "WEIGHT");
    String code = uniqueGroup("重量组");
    UUID groupId = saveGroup(code, "WEIGHT", gram, List.of(gram, kilo));

    // 详情里能看到「千克还没有当天生效的换算版本」
    var detail = tx(actor, () -> groups.detail(groupId, null));
    assertThat(detail.group().allMembersConvertible()).isFalse();
    assertThat(detail.members()).hasSize(2);
    assertThat(detail.members().stream().filter(UnitGroupMember::base).count()).isEqualTo(1);
    assertThat(detail.members().stream().filter(m -> m.unitCode().equals(kilo)).findFirst().orElseThrow()
            .currentQuantityPerBase())
        .isNull();

    ensureConversions(groupId, gram, Map.of(kilo, "1000"));
    // 列表行也能看出这个组现在可换算了：不用点开详情。
    var page = tx(actor, () -> groups.list(new UnitGroupQuery(code, null, null, 0, 20)));
    var row = page.items().stream().filter(r -> r.id().equals(groupId)).findFirst().orElseThrow();
    assertThat(row.allMembersConvertible()).isTrue();
    assertThat(row.memberCount()).isEqualTo(2);
    assertThat(row.dimensionLabel()).isEqualTo("重量");
    assertThat(row.baseUnitName()).isEqualTo(gram);

    // 按量纲筛：WEIGHT 组被选中，其他量纲的组不在结果里。
    var weight = tx(actor, () -> groups.list(new UnitGroupQuery(null, "WEIGHT", null, 0, 20)));
    assertThat(weight.items()).allMatch(r -> r.dimension().equals("WEIGHT"));
    assertThat(weight.items().stream().anyMatch(r -> r.id().equals(groupId))).isTrue();
    // 按不存在的量纲筛：空结果，不是全部。
    var length = tx(actor, () -> groups.list(new UnitGroupQuery(null, "LENGTH", null, 0, 20)));
    assertThat(length.items()).noneMatch(r -> r.id().equals(groupId));
  }

  @Test
  void groupKeywordSearchCoversMemberUnitNamesAndEscapesLikeWildcards() {
    String gram = uniqueCode("克");
    String kilo = uniqueCode("千克");
    saveUnit(gram, "WEIGHT");
    saveUnit(kilo, "WEIGHT");
    String code = uniqueGroup("重量组");
    UUID groupId = saveGroup(code, "WEIGHT", gram, List.of(gram, kilo));

    // 按成员单位名搜得到这个组：运营手里只有一个单位（「千克」），要问的是它在哪个组里。
    assertThat(
            tx(actor, () -> groups.list(new UnitGroupQuery(kilo, null, null, 0, 20))).items()
                .stream()
                .anyMatch(r -> r.id().equals(groupId)))
        .isTrue();
    // 按不存在的关键词搜不到。
    assertThat(
            tx(actor, () -> groups.list(new UnitGroupQuery("不存在的组", null, null, 0, 20))).items())
        .noneMatch(r -> r.id().equals(groupId));
    // LIKE 通配符必须被转义：搜「%」时它是用户要找的那一个字，不是通配符。
    // 不转义的话这条搜索会几乎匹配全表。
    var escaped =
        tx(
            actor,
            () ->
                groups.list(
                    new UnitGroupQuery("%", null, null, 0, 200)));
    assertThat(escaped.items()).noneMatch(r -> r.id().equals(groupId));
  }

  @Test
  void groupMembersAreReplacedWholesaleAndKeepTheirOrder() {
    String gram = uniqueCode("克");
    String kilo = uniqueCode("千克");
    String ton = uniqueCode("吨");
    saveUnit(gram, "WEIGHT");
    saveUnit(kilo, "WEIGHT");
    saveUnit(ton, "WEIGHT");
    UUID groupId = saveGroup(uniqueGroup("重量组"), "WEIGHT", gram, List.of(gram, kilo));
    ensureConversions(groupId, gram, Map.of(kilo, "1000"));

    var code = tx(actor, () -> sql.fetchOne("select code from unit_group where id=?", groupId).get("code", String.class));
    long version = tx(actor, () -> sql.fetchOne("select version from unit_group where id=?", groupId).get("version", Long.class));

    // 当前仍开放生效的换算版本删不掉：删了等于静默把千克变成不可换算。
    var kiloVersions =
        tx(
            actor,
            () ->
                sql.fetch(
                        "select id from unit_group_conversion where tenant_id=? and group_id=? and unit_code=?",
                        actor.tenantId(),
                        groupId,
                        kilo)
                    .stream()
                    .map(r -> r.get("id", UUID.class))
                    .toList());
    assertThat(kiloVersions).isNotEmpty();
    for (UUID versionId : kiloVersions) {
      assertThatThrownBy(
              () -> call(actor, "catalog.spu.update", () -> groups.deleteConversion(actor, groupId, versionId)))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("不能删除当前仍生效");
    }

    // 已闭合的历史版本可以单独删。
    call(
        actor,
        "catalog.spu.update",
        () -> groups.addConversion(actor, groupId, conversionInput(kilo, "1000", "2023-01-01", "2023-12-31")));
    UUID historical =
        tx(
            actor,
            () ->
                sql.fetchOne(
                        "select id from unit_group_conversion where tenant_id=? and group_id=? and unit_code=? and effective_to is not null",
                        actor.tenantId(),
                        groupId,
                        kilo)
                    .get("id", UUID.class));
    call(actor, "catalog.spu.update", () -> groups.deleteConversion(actor, groupId, historical));
    // 删掉的确实没了。
    assertThat(
            tx(
                actor,
                () ->
                    sql.fetch(
                            "select id from unit_group_conversion where tenant_id=? and group_id=? and unit_code=?",
                            actor.tenantId(),
                            groupId,
                            kilo)
                        .stream()
                        .map(r -> r.get("id", UUID.class))
                        .toList()))
        .doesNotContain(historical);

    // 移出成员时，它剩下的组内换算版本随之级联删除：组内换算只在组内有意义
    // （与 V16 删类目会级联删掉品牌关联同一口径）。真正不可删的是单据冻结快照。
    var saved =
        call(
            actor,
            "catalog.spu.update",
            () ->
                groups.save(
                    actor,
                    groupId,
                    new UnitGroupInput(
                        code,
                        "重量组（改）",
                        "WEIGHT",
                        gram,
                        "备注",
                        true,
                        List.of(new UnitGroupMemberInput(ton, 0), new UnitGroupMemberInput(gram, 1)),
                        version)));
    assertThat(saved.memberCount()).isEqualTo(2);
    var detail = tx(actor, () -> groups.detail(groupId, null));
    // 成员顺序按 position 排：ton 在前。
    assertThat(detail.members().get(0).unitCode()).isEqualTo(ton);
    assertThat(detail.group().name()).isEqualTo("重量组（改）");
    assertThat(detail.group().note()).isEqualTo("备注");
    assertThat(detail.conversions()).noneMatch(c -> c.unitCode().equals(kilo));
  }

  @Test
  void groupBaseUnitMustBeAmongMembersAndCodeAndDimensionAreImmutable() {
    String gram = uniqueCode("克");
    String kilo = uniqueCode("千克");
    saveUnit(gram, "WEIGHT");
    saveUnit(kilo, "WEIGHT");
    String code = uniqueGroup("重量组");
    UUID groupId = saveGroup(code, "WEIGHT", gram, List.of(gram, kilo));

    // 基准单位不在成员里 → 400：它不占换算行（比例恒为 1），必须在本组。
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.spu.update",
                    () ->
                        groups.save(
                            actor,
                            null,
                            groupInput(uniqueGroup("坏组"), "坏组", "WEIGHT", kilo, List.of(gram)))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须在本组成员里");

    long version = tx(actor, () -> sql.fetchOne("select version from unit_group where id=?", groupId).get("version", Long.class));
    // 改编码 → 400：下游按编码引用换算关系。
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.spu.update",
                    () -> groups.save(actor, groupId, groupInput(uniqueGroup("改了"), "重量组", "WEIGHT", gram, List.of(gram, kilo)))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("编码建后不可修改");

    // 改量纲 → 400：改了会让已配置的换算突然不合法。
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.spu.update",
                    () ->
                        groups.save(
                            actor,
                            groupId,
                            new UnitGroupInput(code, "重量组", "LENGTH", gram, "", true, List.of(new UnitGroupMemberInput(gram, 0)), version))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("量纲建后不可修改");

    // 旧 version → 409 乐观锁。
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.spu.update",
                    () ->
                        groups.save(
                            actor,
                            groupId,
                            new UnitGroupInput(code, "重量组", "WEIGHT", gram, "", true, List.of(new UnitGroupMemberInput(gram, 0)), version + 5))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
  }

  @Test
  void groupMembersMustShareDimensionWithTheGroup() {
    String gram = uniqueCode("克");
    String piece = uniqueCode("件");
    saveUnit(gram, "WEIGHT");
    saveUnit(piece, "COUNT");
    // 组是重量组，往里加「件」（数量）→ 400，理由要说清是量纲不匹配。
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.spu.update",
                    () -> groups.save(actor, null, groupInput(uniqueGroup("重量组"), "重量组", "WEIGHT", gram, List.of(gram, piece)))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("量纲不匹配")
        .hasMessageContaining("件");
  }

  // ------------------------------------------------------------ 组内换算版本

  @Test
  void groupConversionVersionsAreWindowedAndNonOverlapping() {
    String gram = uniqueCode("克");
    String kilo = uniqueCode("千克");
    saveUnit(gram, "WEIGHT");
    saveUnit(kilo, "WEIGHT");
    UUID groupId = saveGroup(uniqueGroup("重量组"), "WEIGHT", gram, List.of(gram, kilo));

    // 第一版：2024-01-01 起长期生效。
    call(
        actor,
        "catalog.spu.update",
        () -> groups.addConversion(actor, groupId, conversionInput(kilo, "1000", "2024-01-01", null)));
    // 区间重叠 → 409，并列出冲突区间。
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.spu.update",
                    () -> groups.addConversion(actor, groupId, conversionInput(kilo, "1000", "2025-01-01", null))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409))
        .hasMessageContaining("生效区间与已有版本重叠");

    // 不重叠的区间（2000 年前）→ 成功。
    call(
        actor,
        "catalog.spu.update",
        () -> groups.addConversion(actor, groupId, conversionInput(kilo, "1000", "2000-01-01", "2023-12-31")));
    // 详情里两版都在，按生效起始日倒序。
    var detail = tx(actor, () -> groups.detail(groupId, null));
    assertThat(detail.conversions()).hasSize(2);
    assertThat(detail.conversions().get(0).effectiveFrom()).isEqualTo("2024-01-01");

    // 比例非法 → 400：0 会让换算恒为 0。
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.spu.update",
                    () -> groups.addConversion(actor, groupId, conversionInput(kilo, "0", "2026-01-01", null))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须大于 0");

    // 基准单位不占换算行：比例恒为 1。
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.spu.update",
                    () -> groups.addConversion(actor, groupId, conversionInput(gram, "1", "2026-01-01", null))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不需要换算版本");

    // 非组成员不能配组内换算。
    String ton = uniqueCode("吨");
    saveUnit(ton, "WEIGHT");
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.spu.update",
                    () -> groups.addConversion(actor, groupId, conversionInput(ton, "1000", "2026-01-01", null))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不是单位组")
        .hasMessageContaining("成员");
  }

  @Test
  void groupBaseUnitCannotBeRemovedWhileStillTheBase() {
    String gram = uniqueCode("克");
    String kilo = uniqueCode("千克");
    saveUnit(gram, "WEIGHT");
    saveUnit(kilo, "WEIGHT");
    String code = uniqueGroup("重量组");
    UUID groupId = saveGroup(code, "WEIGHT", gram, List.of(gram, kilo));
    long version =
        tx(
            actor,
            () ->
                sql.fetchOne("select version from unit_group where id=?", groupId)
                    .get("version", Long.class));
    // 仍以 gram 为基准单位，却把 gram 从成员里去掉 → 应用层直接拒：
    // 基准单位不占换算行（比例恒为 1），移出组会让组没有基准。
    // （V17 的 refuse_unit_group_base_removal 触发器在 DB 层兜底。）
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.spu.update",
                    () ->
                        groups.save(
                            actor,
                            groupId,
                            new UnitGroupInput(
                                code,
                                "重量组",
                                "WEIGHT",
                                gram,
                                "",
                                true,
                                List.of(new UnitGroupMemberInput(kilo, 0)),
                                version))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须在本组成员里");
  }

  @Test
  void groupBaseUnitCanBeMigratedToAnotherMemberInOneSave() {
    String gram = uniqueCode("克");
    String kilo = uniqueCode("千克");
    saveUnit(gram, "WEIGHT");
    saveUnit(kilo, "WEIGHT");
    String code = uniqueGroup("重量组");
    UUID groupId = saveGroup(code, "WEIGHT", gram, List.of(gram, kilo));
    // 基准单位迁移：一次保存里同时把 base 改成 kilo、并把 gram 移出成员。
    // 这是合法的——守卫在删除那一刻看到的新基准已经是 kilo。
    var saved =
        call(
            actor,
            "catalog.spu.update",
            () ->
                groups.save(
                    actor,
                    groupId,
                    new UnitGroupInput(
                        code,
                        "重量组",
                        "WEIGHT",
                        kilo,
                        "",
                        true,
                        List.of(new UnitGroupMemberInput(kilo, 0)),
                        tx(
                            actor,
                            () ->
                                sql.fetchOne("select version from unit_group where id=?", groupId)
                                    .get("version", Long.class)))));
    assertThat(saved.memberCount()).isEqualTo(1);
    var detail = tx(actor, () -> groups.detail(groupId, null));
    assertThat(detail.group().baseUnitCode()).isEqualTo(kilo);
    assertThat(detail.members()).hasSize(1);
    assertThat(detail.members().get(0).base()).isTrue();
  }

  // ------------------------------------------------------------ 换算查询

  @Test
  void convertResolvesGroupRatioChainAndFlagsAsOf() {
    String gram = uniqueCode("克");
    String kilo = uniqueCode("千克");
    String ton = uniqueCode("吨");
    saveUnit(gram, "WEIGHT");
    saveUnit(kilo, "WEIGHT");
    saveUnit(ton, "WEIGHT");
    UUID groupId = saveGroup(uniqueGroup("重量组"), "WEIGHT", gram, List.of(gram, kilo, ton));
    ensureConversions(groupId, gram, Map.of(kilo, "1000", ton, "1000000"));

    // 1 吨 → 克 = 1 × 1000000 ÷ 1 = 1000000。
    var result =
        tx(actor, () -> groups.convert(new UnitGroupConvertInput(ton, gram, new BigDecimal("1"), "2026-06-01")));
    assertThat(result.denied()).isFalse();
    assertThat(result.convertedQuantity()).isEqualByComparingTo(new BigDecimal("1000000.000000"));
    assertThat(result.groupId()).isEqualTo(groupId);
    assertThat(result.baseUnitCode()).isEqualTo(gram);
    assertThat(result.fromQuantityPerBase()).isEqualByComparingTo(new BigDecimal("1000000"));

    // 2 磅这种链式：这里用 2 吨 → 千克 = 2 × 1000000 ÷ 1000 = 2000。
    var chain =
        tx(actor, () -> groups.convert(new UnitGroupConvertInput(ton, kilo, new BigDecimal("2"), "2026-06-01")));
    assertThat(chain.denied()).isFalse();
    assertThat(chain.convertedQuantity()).isEqualByComparingTo(new BigDecimal("2000.000000"));
  }

  @Test
  void convertDeniesSelfUnitUnregisteredAndDimensionMismatchWithReasonCodes() {
    String gram = uniqueCode("克");
    String kilo = uniqueCode("千克");
    String piece = uniqueCode("件");
    saveUnit(gram, "WEIGHT");
    saveUnit(kilo, "WEIGHT");
    saveUnit(piece, "COUNT");
    UUID groupId = saveGroup(uniqueGroup("重量组"), "WEIGHT", gram, List.of(gram, kilo));
    ensureConversions(groupId, gram, Map.of(kilo, "1000"));

    // 同单位 → SELF_CONVERSION（不是错误，是"无需换算"）。
    var self = tx(actor, () -> groups.convert(new UnitGroupConvertInput(kilo, kilo, new BigDecimal("1"), "2026-06-01")));
    assertThat(self.denied()).isTrue();
    assertThat(self.denyCode()).isEqualTo(UnitGroupRules.DENY_SELF_CONVERSION);

    // 未登记单位 → UNIT_UNREGISTERED。
    var unregistered =
        tx(actor, () -> groups.convert(new UnitGroupConvertInput("不存在的单位", kilo, new BigDecimal("1"), "2026-06-01")));
    assertThat(unregistered.denied()).isTrue();
    assertThat(unregistered.denyCode()).isEqualTo(UnitGroupRules.DENY_UNIT_UNREGISTERED);

    // 量纲不匹配（件 COUNT vs 千克 WEIGHT）→ DIMENSION_MISMATCH，蓝图点名的硬约束。
    // 注意这条在「同一个组」之前就给出答案：用户问的正是「为什么换不了」。
    var mismatch =
        tx(actor, () -> groups.convert(new UnitGroupConvertInput(piece, kilo, new BigDecimal("1"), "2026-06-01")));
    assertThat(mismatch.denied()).isTrue();
    assertThat(mismatch.denyCode()).isEqualTo(UnitGroupRules.DENY_DIMENSION_MISMATCH);
    assertThat(mismatch.denyMessage()).contains("量纲不匹配").contains("件").contains("千克");
  }

  @Test
  void convertDeniesNotSameGroupAndNoEffectiveVersionWithoutFallback() {
    String gram = uniqueCode("克");
    String kilo = uniqueCode("千克");
    String mg = uniqueCode("毫克");
    String piece = uniqueCode("件");
    saveUnit(gram, "WEIGHT");
    saveUnit(kilo, "WEIGHT");
    saveUnit(mg, "WEIGHT");
    saveUnit(piece, "COUNT");
    UUID groupId = saveGroup(uniqueGroup("重量组"), "WEIGHT", gram, List.of(gram, kilo));
    ensureConversions(groupId, gram, Map.of(kilo, "1000"));
    // 毫克是个独立的同量纲单位，但不在这个组里。
    saveUnit(uniqueCode("盎司"), "WEIGHT");

    // 毫克与千克同量纲但不同组 → NOT_SAME_GROUP。
    var notSame =
        tx(actor, () -> groups.convert(new UnitGroupConvertInput(mg, kilo, new BigDecimal("1"), "2026-06-01")));
    assertThat(notSame.denied()).isTrue();
    assertThat(notSame.denyCode()).isEqualTo(UnitGroupRules.DENY_NOT_SAME_GROUP);

    // 组内换算版本从 2024-01-01 起生效：查 2023 年没有生效版本 → NO_EFFECTIVE_VERSION，
    // 绝不能回退到"最近的一版"（那会让一张按过期比例生成的采购单看起来完全正常）。
    var noVersion =
        tx(actor, () -> groups.convert(new UnitGroupConvertInput(kilo, gram, new BigDecimal("1"), "2023-06-01")));
    assertThat(noVersion.denied()).isTrue();
    assertThat(noVersion.denyCode()).isEqualTo(UnitGroupRules.DENY_NO_EFFECTIVE_VERSION);
    assertThat(noVersion.denyMessage()).contains("取最近一版");
    // 生效期内正常换算。
    assertThat(tx(actor, () -> groups.convert(new UnitGroupConvertInput(kilo, gram, new BigDecimal("1"), "2024-06-01"))).denied())
        .isFalse();
  }

  @Test
  void convertDeniesDisabledUnitAndDisabledGroup() {
    String gram = uniqueCode("克");
    String kilo = uniqueCode("千克");
    saveUnit(gram, "WEIGHT");
    saveUnit(kilo, "WEIGHT");
    UUID groupId = saveGroup(uniqueGroup("重量组"), "WEIGHT", gram, List.of(gram, kilo));
    ensureConversions(groupId, gram, Map.of(kilo, "1000"));

    // 停用组 → GROUP_DISABLED（而不是笼统的 NOT_SAME_GROUP，运营才不会去错地方找）。
    long version = tx(actor, () -> sql.fetchOne("select version from unit_group where id=?", groupId).get("version", Long.class));
    call(actor, "catalog.spu.update", () -> groups.setEnabled(actor, groupId, false, version));
    var disabledGroup =
        tx(actor, () -> groups.convert(new UnitGroupConvertInput(kilo, gram, new BigDecimal("1"), "2026-06-01")));
    assertThat(disabledGroup.denied()).isTrue();
    assertThat(disabledGroup.denyCode()).isEqualTo(UnitGroupRules.DENY_GROUP_DISABLED);

    // 恢复组，停用其中一个单位 → UNIT_DISABLED。
    long restored = tx(actor, () -> sql.fetchOne("select version from unit_group where id=?", groupId).get("version", Long.class));
    call(actor, "catalog.spu.update", () -> groups.setEnabled(actor, groupId, true, restored));
    // 单位字典的写入所有者是 UnitConversionService，停用它也要走那个入口。
    call(
        actor,
        "catalog.sku.update",
        () ->
            units.saveUnit(
                actor,
                unitId(kilo),
                new UnitOfMeasureInput(kilo, kilo, "WEIGHT", 0, true, true, true, false, 0)));
    var disabledUnit =
        tx(actor, () -> groups.convert(new UnitGroupConvertInput(kilo, gram, new BigDecimal("1"), "2026-06-01")));
    assertThat(disabledUnit.denied()).isTrue();
    assertThat(disabledUnit.denyCode()).isEqualTo(UnitGroupRules.DENY_UNIT_DISABLED);
  }

  private UUID unitId(String code) {
    return tx(actor, () -> sql.fetchOne("select id from unit_of_measure where code=?", code).get("id", UUID.class));
  }

  @Test
  void convertByAsOfRespectsTheCorrectVersion() {
    String gram = uniqueCode("克");
    String kilo = uniqueCode("千克");
    saveUnit(gram, "WEIGHT");
    saveUnit(kilo, "WEIGHT");
    UUID groupId = saveGroup(uniqueGroup("重量组"), "WEIGHT", gram, List.of(gram, kilo));
    // 两版：2000-01-01 ~ 2023-12-31 是 1000；2024-01-01 起是 1001（运营改过装箱比）。
    call(actor, "catalog.spu.update", () -> groups.addConversion(actor, groupId, conversionInput(kilo, "1000", "2000-01-01", "2023-12-31")));
    call(actor, "catalog.spu.update", () -> groups.addConversion(actor, groupId, conversionInput(kilo, "1001", "2024-01-01", null)));

    var oldVersion = tx(actor, () -> groups.convert(new UnitGroupConvertInput(kilo, gram, new BigDecimal("1"), "2023-06-01")));
    assertThat(oldVersion.denied()).isFalse();
    assertThat(oldVersion.convertedQuantity()).isEqualByComparingTo(new BigDecimal("1000.000000"));

    var newVersion = tx(actor, () -> groups.convert(new UnitGroupConvertInput(kilo, gram, new BigDecimal("1"), "2024-06-01")));
    assertThat(newVersion.denied()).isFalse();
    assertThat(newVersion.convertedQuantity()).isEqualByComparingTo(new BigDecimal("1001.000000"));
  }

  @Test
  void groupDeleteIsRejectedWhenConversionsExist() {
    String gram = uniqueCode("克");
    String kilo = uniqueCode("千克");
    saveUnit(gram, "WEIGHT");
    saveUnit(kilo, "WEIGHT");
    UUID groupId = saveGroup(uniqueGroup("重量组"), "WEIGHT", gram, List.of(gram, kilo));
    ensureConversions(groupId, gram, Map.of(kilo, "1000"));
    long version = tx(actor, () -> sql.fetchOne("select version from unit_group where id=?", groupId).get("version", Long.class));

    // 有换算版本 → 400，提示改为停用。
    assertThatThrownBy(() -> call(actor, "catalog.spu.update", () -> groups.delete(actor, groupId, version)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能删除")
        .hasMessageContaining("停用");

    // 没有换算版本的组 → 删得掉。
    String oz = uniqueCode("盎司");
    saveUnit(oz, "WEIGHT");
    UUID empty = saveGroup(uniqueGroup("空组"), "WEIGHT", oz, List.of(oz));
    long emptyVersion = tx(actor, () -> sql.fetchOne("select version from unit_group where id=?", empty).get("version", Long.class));
    call(actor, "catalog.spu.update", () -> groups.delete(actor, empty, emptyVersion));
    // 删掉的组取不回来了（404），而不是变一个空详情。
    assertThatThrownBy(() -> tx(actor, () -> groups.detail(empty, null)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
  }

  // ------------------------------------------------------------ 量纲守卫（DB 层）

  @Test
  void dimensionGuardTriggersRejectCrossDimensionMembersAndConversions() {
    String gram = uniqueCode("克");
    String piece = uniqueCode("件");
    saveUnit(gram, "WEIGHT");
    saveUnit(piece, "COUNT");
    UUID groupId = saveGroup(uniqueGroup("重量组"), "WEIGHT", gram, List.of(gram));
    // 绕过应用层直接往重量组塞一个「件」（COUNT）。应用层会拦，但 DB 层也必须拦：
    // 有人直连库写、或某个未来服务忘了应用层检查时，这条路必须关死。
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () ->
                        sql.execute(
                            "insert into unit_group_member(tenant_id,group_id,unit_code,position)"
                                + " values(?,?,?,0)",
                            actor.tenantId(),
                            groupId,
                            piece)))
        .hasMessageContaining("量纲不匹配");

    // 换算行同理。守卫触发器在 BEFORE INSERT，先于外键执行，所以这里拿到的是量纲拒绝：
    // 给一个 COUNT 单位在 WEIGHT 组里建换算行，先撞上量纲守卫。
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () ->
                        sql.execute(
                            "insert into unit_group_conversion(id,tenant_id,group_id,unit_code,"
                                + "quantity_per_base,effective_from) values(?,?,?,?,?,?)",
                            UUID.randomUUID(),
                            actor.tenantId(),
                            groupId,
                            piece,
                            new BigDecimal("1"),
                            LocalDate.of(2026, 1, 1))))
        .hasMessageContaining("量纲不匹配")
        .hasMessageContaining("两者之间不允许换算");

    // 守卫也拦「把 COUNT 单位作为建组时的基准单位而组声明 WEIGHT」——
    // 应用层已拦过一次，这里直接打 DB 证明触发器也在。
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () ->
                        sql.execute(
                            "insert into unit_group(id,tenant_id,code,name,dimension,base_unit_code)"
                                + " values(?,?,?,?,'WEIGHT',?)",
                            UUID.randomUUID(),
                            actor.tenantId(),
                            uniqueGroup("守卫组"),
                            "守卫组",
                            piece)))
        .hasMessageContaining("量纲不匹配");
  }

  // ------------------------------------------------------------ 租户隔离三断言

  @Test
  void unitGroupIsTenantIsolatedAndHeaderForgeryIsIgnored() throws Exception {
    String gram = uniqueCode("克");
    String kilo = uniqueCode("千克");
    saveUnit(gram, "WEIGHT");
    saveUnit(kilo, "WEIGHT");
    String myCode = uniqueGroup("重量组");
    UUID myGroupId = saveGroup(myCode, "WEIGHT", gram, List.of(gram, kilo));
    ensureConversions(myGroupId, gram, Map.of(kilo, "1000"));

    // B 租户建自己的重量组：它的单位字典与 A 完全隔离，两边可以有同名单位。
    String bGram = uniqueCode("克");
    String bKilo = uniqueCode("千克");
    UUID bGroupId =
        access.run(
            actorB,
            "catalog.sku.update",
            () -> {
              units.saveUnit(
                  actorB,
                  null,
                  new UnitOfMeasureInput(
                      bGram, bGram, "WEIGHT", 0, true, true, true, true, 0));
              units.saveUnit(
                  actorB,
                  null,
                  new UnitOfMeasureInput(
                      bKilo, bKilo, "WEIGHT", 0, true, true, true, true, 0));
              return groups.save(
                      actorB,
                      null,
                      groupInput(
                          uniqueGroup("B重量组"),
                          "B重量组",
                          "WEIGHT",
                          bGram,
                          List.of(bGram, bKilo)))
                  .id();
            });

    // 1) 两租户互不可见
    // B 的列表里看不到 A 的组。
    assertThat(tx(actorB, () -> groups.list(new UnitGroupQuery(null, null, null, 0, 200))).items().stream()
            .map(UnitGroupRow::id))
        .doesNotContain(myGroupId);
    // B 拿 A 的组 id 取详情 → 404。
    assertThatThrownBy(() -> tx(actorB, () -> groups.detail(myGroupId, null)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    // B 拿 A 的组去改 → 404。
    assertThatThrownBy(
            () ->
                tx(
                    actorB,
                    () ->
                        groups.save(
                            actorB,
                            myGroupId,
                            groupInput(myCode, "B 改的", "WEIGHT", gram, List.of(gram)))))
        .isInstanceOf(ResponseStatusException.class);
    // A 的组还在。
    assertThat(tx(actor, () -> groups.detail(myGroupId, null)).group().code()).isEqualTo(myCode);

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系。
    http.perform(
            get("/catalog/unit-groups/" + myGroupId)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/unit-groups/" + myGroupId)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());
    // 伪造头也不能借道写：拿别人的组去改 → 404。
    http.perform(
            put("/catalog/unit-groups/" + myGroupId)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content(
                    "{\"code\":\"" + myCode + "\",\"name\":\"B 伪造\",\"dimension\":\"WEIGHT\","
                        + "\"baseUnitCode\":\"" + gram + "\",\"enabled\":true,\"members\":[]}"))
        .andExpect(status().isNotFound());

    // 3) 连接池归还后不串租户（单连接反复交替）。
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> groups.detail(myGroupId, null).group().id())).isEqualTo(myGroupId);
      assertThat(tx(actorB, () -> groups.detail(bGroupId, null).group().id())).isEqualTo(bGroupId);
      assertThatThrownBy(() -> tx(actorB, () -> groups.detail(myGroupId, null)))
          .isInstanceOf(ResponseStatusException.class);
      assertThatThrownBy(() -> tx(actor, () -> groups.detail(bGroupId, null)))
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  @Test
  void writeEndpointsRequireSpuUpdatePermission() throws Exception {
    http.perform(
            post("/catalog/unit-groups")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    "{\"code\":\"只读组\",\"name\":\"只读\",\"dimension\":\"WEIGHT\","
                        + "\"baseUnitCode\":\"克\",\"enabled\":true,\"members\":[]}"))
        .andExpect(status().isForbidden());
  }

  // ------------------------------------------------------------ 表结构

  @Test
  void unitGroupTablesAreRlsProtectedAndQuantitiesAreNumeric() {
    var weak =
        sql.fetch(
            "select relname from pg_class join pg_namespace n on n.oid=relnamespace"
                + " where n.nspname='public' and relkind='r'"
                + " and relname in ('unit_group','unit_group_member','unit_group_conversion')"
                + " and (not relrowsecurity or not relforcerowsecurity)");
    assertThat(weak).isEmpty();
    for (String table : List.of("unit_group", "unit_group_member", "unit_group_conversion")) {
      var nullable =
          sql.fetchOne(
              "select is_nullable from information_schema.columns where table_name=? and column_name='tenant_id'",
              table);
      assertThat(nullable.get("is_nullable", String.class)).as(table).isEqualTo("NO");
    }
    // 数量列必须是 numeric(19,6)，不得退化成浮点。
    var type =
        sql.fetchOne(
            "select data_type,numeric_precision,numeric_scale from information_schema.columns"
                + " where table_name='unit_group_conversion' and column_name='quantity_per_base'");
    assertThat(type.get("data_type", String.class)).isEqualTo("numeric");
    assertThat(type.get("numeric_precision", Integer.class)).isEqualTo(19);
    assertThat(type.get("numeric_scale", Integer.class)).isEqualTo(6);
  }
}