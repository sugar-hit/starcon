package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.1.3.1 规格轴定义：轴字典 CRUD、SPU 绑定、规格选项与属性选项**同一个值源**的硬约束、
 * 类目允许规则、矩阵行数，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SpecAxisIntegrationTest {
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
      // 只有 attribute 读权限：所有写端点必须 403。
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

  @Autowired CatalogService catalog;
  @Autowired SpecAxisService specAxis;
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
    return access.run(who, "catalog.attribute.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.attribute.update", work);
  }

  /** void 版：删除类端点返回 void，不能走 Supplier。 */
  void write(AccessService.Access who, Runnable work) {
    access.run(
        who,
        "catalog.attribute.update",
        () -> {
          work.run();
          return null;
        });
  }

  // ------------------------------------------------------------ 夹具

  /** 轴编码租户内唯一：每个用例用独立编码，避免用例之间互相抢占字典位。 */
  String uniqueCode(String hint) {
    return hint + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
  }

  UUID saveAxis(String code, String name) {
    return write(
            actor,
            () -> specAxis.saveAxis(actor, null, new SpecAxisInput(code, name, true, 0)))
        .id();
  }

  UUID saveAxisB(String code, String name) {
    return write(
            actorB,
            () -> specAxis.saveAxis(actorB, null, new SpecAxisInput(code, name, true, 0)))
        .id();
  }

  /** 建一个 SPU（category 可指定，用于类目规则用例）。 */
  UUID spu(String category) {
    UUID id =
        write(
                actor,
                () ->
                    catalog.saveSpu(
                        actor,
                        null,
                        new SpuInput(
                            "AX-" + UUID.randomUUID().toString().substring(0, 8),
                            "规格轴测试商品",
                            "描述",
                            "品牌",
                            category,
                            "DRAFT",
                            0)));
    return id;
  }

  /** 建 SPU 并绑一条轴（自动建承载属性），返回 SPU 与轴的 id。 */
  record Bound(UUID spuId, UUID axisId, UUID attributeId) {}

  Bound bound(String code, String category) {
    UUID axisId = saveAxis(code, code);
    UUID spuId = spu(category);
    var saved =
        write(
            actor,
            () ->
                specAxis.bindAxis(
                    actor, spuId, new SpecAxisBindingInput(axisId, 0, true, code)));
    return new Bound(spuId, axisId, saved.attributeDefinitionId());
  }

  SpecAxisOptionSaved option(UUID axisId, String value, int position) {
    return write(actor, () -> specAxis.addOption(actor, axisId, new SpecAxisOptionInput(value, position, true)));
  }

  // ------------------------------------------------------------ 规格轴字典

  @Test
  void axisIsCreatedListedAndUpdatedWithOptimisticLock() {
    String code = uniqueCode("COLOR");
    var saved =
        write(actor, () -> specAxis.saveAxis(actor, null, new SpecAxisInput(code, "颜色", true, 0)));
    assertThat(saved.version()).isZero();

    var listed = tx(actor, () -> specAxis.axes()).stream().filter(x -> x.id().equals(saved.id())).findFirst();
    assertThat(listed).isPresent();
    assertThat(listed.get().code()).isEqualTo(code);
    assertThat(listed.get().optionCount()).isZero();
    assertThat(listed.get().boundSpuCount()).isZero();

    // 更新走 version 乐观锁。
    var updated =
        write(
            actor,
            () ->
                specAxis.saveAxis(
                    actor, saved.id(), new SpecAxisInput(code, "颜色维度", false, 0)));
    assertThat(updated.version()).isEqualTo(1);
    // 旧 version 再提交 → 冲突。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        specAxis.saveAxis(
                            actor, saved.id(), new SpecAxisInput(code, "再改一次", true, 0))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
  }

  @Test
  void duplicateAxisCodeIsRejected() {
    String code = uniqueCode("SIZE");
    saveAxis(code, "尺码");
    assertThatThrownBy(() -> saveAxis(code, "尺码重复"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
  }

  @Test
  void axisCodeIsImmutableBecauseBindingsRelyOnIt() {
    UUID axisId = saveAxis(uniqueCode("CAP"), "容量");
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        specAxis.saveAxis(
                            actor, axisId, new SpecAxisInput("RENAMED", "容量", true, 0))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
  }

  @Test
  void boundOrUsedAxisCannotBeDeletedAndShouldBeDisabled() {
    var mine = bound(uniqueCode("CLR"), "服饰");
    option(mine.axisId(), "红", 0);
    // 有绑定 → 删不动。
    assertThatThrownBy(() -> write(actor, () -> specAxis.deleteAxis(actor, mine.axisId(), 0)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    // 停用可以：挡住新建，历史照常可读。
    var axis = tx(actor, () -> specAxis.axes()).stream().filter(x -> x.id().equals(mine.axisId())).findFirst().orElseThrow();
    write(
        actor,
        () ->
            specAxis.saveAxis(
                actor, mine.axisId(), new SpecAxisInput(axis.code(), axis.name(), false, axis.version())));
    assertThat(
            tx(actor, () -> specAxis.axes()).stream()
                .filter(x -> x.id().equals(mine.axisId()))
                .findFirst()
                .orElseThrow()
                .enabled())
        .isFalse();
  }

  @Test
  void unusedAndEmptyAxisCanBeDeleted() {
    UUID axisId = saveAxis(uniqueCode("TMP"), "临时轴");
    write(actor, () -> specAxis.deleteAxis(actor, axisId, 0));
    assertThat(tx(actor, () -> specAxis.axes()).stream().map(SpecAxis::id)).doesNotContain(axisId);
  }

  // ------------------------------------------------------------ 绑定与承载属性

  @Test
  void bindingCreatesVariantAttributeWithSameCodeAndSkusCanUseTheAxis() {
    String code = uniqueCode("CLR");
    var mine = bound(code, "服饰");
    // 承载属性是 variant_axis，且 code 与轴一致（V9 触发器强制）。
    var attribute =
        tx(
            actor,
            () ->
                sql.fetchOne(
                    "select code,data_type,value_mode,multiple,required,variant_axis"
                        + " from attribute_definition where id=?",
                    mine.attributeId()));
    assertThat(attribute.get("code", String.class)).isEqualTo(code);
    assertThat(attribute.get("data_type", String.class)).isEqualTo("OPTION");
    assertThat(attribute.get("value_mode", String.class)).isEqualTo("SKU_ONLY");
    assertThat(attribute.get("multiple", Boolean.class)).isFalse();
    assertThat(attribute.get("required", Boolean.class)).isTrue();
    assertThat(attribute.get("variant_axis", Boolean.class)).isTrue();

    var panel = tx(actor, () -> specAxis.panel(mine.spuId()));
    assertThat(panel.axes()).hasSize(1);
    assertThat(panel.axes().getFirst().specAxisCode()).isEqualTo(code);
    assertThat(panel.axes().getFirst().attributeCode()).isEqualTo(code);
  }

  @Test
  void bindingWithoutCreateAttributeIsRejectedWithActionableMessage() {
    UUID axisId = saveAxis(uniqueCode("NOATTR"), "未建承载属性");
    UUID spuId = spu("服饰");
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        specAxis.bindAxis(
                            actor, spuId, new SpecAxisBindingInput(axisId, 0, false, null))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("variant_axis");
  }

  @Test
  void bindingTwiceIsRejectedAsConflict() {
    var mine = bound(uniqueCode("DUP"), "服饰");
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        specAxis.bindAxis(
                            actor,
                            mine.spuId(),
                            new SpecAxisBindingInput(mine.axisId(), 1, true, null))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
  }

  @Test
  void unbindingRemovesTheAxisFromTheSpu() {
    var mine = bound(uniqueCode("UNB"), "服饰");
    write(actor, () -> specAxis.unbindAxis(actor, mine.spuId(), mine.axisId()));
    assertThat(tx(actor, () -> specAxis.panel(mine.spuId())).axes()).isEmpty();
  }

  // ------------------------------------------------------------ 值源硬约束（蓝图核心）

  @Test
  void optionIsWrittenToBothValueSourcesInOneTransaction() {
    var mine = bound(uniqueCode("SRC"), "服饰");
    option(mine.axisId(), "深空灰", 0);

    // 规格轴侧有这行。
    var axisOptions = tx(actor, () -> specAxis.options(mine.axisId()));
    assertThat(axisOptions).extracting(SpecAxisOption::value).containsExactly("深空灰");
    // 属性选项值源里**同一行**也有：不是两份拷贝，是同一个值。
    var inSource =
        tx(
            actor,
            () ->
                sql.fetchOne(
                    "select count(*) from attribute_option where attribute_id=? and value='深空灰'",
                    mine.attributeId()));
    assertThat(inSource.get(0, Long.class)).isEqualTo(1);
    // 选项带回它的承载属性：界面上要能把"它同时是属性选项"讲出来。
    assertThat(axisOptions.getFirst().sourceAttributeId()).isEqualTo(mine.attributeId());
    assertThat(axisOptions.getFirst().sourceAttributeCode()).isEqualTo(
        tx(actor, () -> specAxis.panel(mine.spuId())).axes().getFirst().attributeCode());
  }

  @Test
  void sameValueIsSharedAcrossSpusUsingTheSameAxis() {
    // 这条用例是"租户级字典"的立身之本：一条轴被两个 SPU 复用时，
    // 同一个值只写一次值源，两边都能看到——而不是每个 SPU 自己一套。
    String code = uniqueCode("SHR");
    UUID axisId = saveAxis(code, "共享颜色");
    UUID spu1 = spu("服饰");
    UUID spu2 = spu("服饰");
    var bind1 = write(actor, () -> specAxis.bindAxis(actor, spu1, new SpecAxisBindingInput(axisId, 0, true, code)));
    var bind2 = write(actor, () -> specAxis.bindAxis(actor, spu2, new SpecAxisBindingInput(axisId, 0, true, code)));

    option(axisId, "米白", 0);

    // 值源里两个承载属性各一行，值相同。
    long rows =
        tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select count(*) from attribute_option where attribute_id in (?,?) and value='米白'",
                            bind1.attributeDefinitionId(), bind2.attributeDefinitionId())
                        .get(0, Long.class))
            ;
    assertThat(rows).isEqualTo(2);
    // 规格轴侧只有一条选项：值本身只有一个，不是每 SPU 一份。
    assertThat(tx(actor, () -> specAxis.options(axisId))).hasSize(1);
  }

  @Test
  void orphanSpecOptionIsRejectedByDatabaseTrigger() {
    // 绕过应用层直连写入：值源里没有这个值 → DB 必须拒（蓝图硬约束的技术兑现点）。
    var mine = bound(uniqueCode("ORP"), "服饰");
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () -> {
                      sql.execute(
                          "insert into spec_axis_option(tenant_id,spec_axis_id,value,position)"
                              + " values(?::uuid,?::uuid,'不存在的值',0)",
                          a,
                          mine.axisId());
                      return null;
                    }))
        .hasMessageContaining("同一个值源");
  }

  @Test
  void attributeOptionCannotBeDeletedWhileASpecOptionUsesIt() {
    // 反向硬约束：值源不能被抽走，否则规格选项变成悬空引用。
    var mine = bound(uniqueCode("DEL"), "服饰");
    option(mine.axisId(), "藏青", 0);
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () -> {
                      sql.execute(
                          "delete from attribute_option where attribute_id=? and value='藏青'",
                          mine.attributeId());
                      return null;
                    }))
        .hasMessageContaining("同一个值源");
  }

  @Test
  void attributeOptionValueCannotBeRenamedWhileUsedBySpecAxis() {
    var mine = bound(uniqueCode("RNM"), "服饰");
    option(mine.axisId(), "藕粉", 0);
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () -> {
                      sql.execute(
                          "update attribute_option set value='粉色' where attribute_id=? and value='藕粉'",
                          mine.attributeId());
                      return null;
                    }))
        .hasMessageContaining("同一个值源");
  }

  @Test
  void optionOnUnboundAxisIsRejectedBecauseThereIsNoValueSource() {
    UUID axisId = saveAxis(uniqueCode("NOB"), "未绑定的轴");
    assertThatThrownBy(() -> option(axisId, "红", 0))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("还没有绑定任何 SPU");
  }

  @Test
  void deletingOptionRemovesItFromBothValueSources() {
    var mine = bound(uniqueCode("RMO"), "服饰");
    option(mine.axisId(), "军绿", 0);
    write(actor, () -> specAxis.deleteOption(actor, mine.axisId(), "军绿"));
    assertThat(tx(actor, () -> specAxis.options(mine.axisId()))).isEmpty();
    long left =
        tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select count(*) from attribute_option where attribute_id=? and value='军绿'",
                            mine.attributeId())
                        .get(0, Long.class));
    assertThat(left).isZero();
  }

  @Test
  void addingTheSameOptionTwiceIsIdempotent() {
    var mine = bound(uniqueCode("IDM"), "服饰");
    option(mine.axisId(), "白", 0);
    option(mine.axisId(), "白", 3);
    var options = tx(actor, () -> specAxis.options(mine.axisId()));
    assertThat(options).hasSize(1);
    // 重复登记会更新位置，不是插入第二行。
    assertThat(options.getFirst().position()).isEqualTo(3);
  }

  // ------------------------------------------------------------ 矩阵与类目规则

  @Test
  void matrixRowCountIsCartesianProductOfAvailableOptions() {
    String colorCode = uniqueCode("MXC"), sizeCode = uniqueCode("MXS");
    UUID colorAxis = saveAxis(colorCode, "颜色");
    UUID sizeAxis = saveAxis(sizeCode, "尺码");
    UUID spuId = spu("服饰");
    write(actor, () -> specAxis.bindAxis(actor, spuId, new SpecAxisBindingInput(colorAxis, 0, true, colorCode)));
    write(actor, () -> specAxis.bindAxis(actor, spuId, new SpecAxisBindingInput(sizeAxis, 1, true, sizeCode)));
    option(colorAxis, "红", 0);
    option(colorAxis, "蓝", 1);
    option(sizeAxis, "S", 0);
    option(sizeAxis, "M", 1);
    option(sizeAxis, "L", 2);

    // 2 颜色 × 3 尺码 = 6 行矩阵。
    assertThat(tx(actor, () -> specAxis.panel(spuId)).matrixRowCount()).isEqualTo(6);

    // 停用一个选项后矩阵收缩到 4：停用只挡新建，历史仍可读。
    write(
        actor,
        () ->
            specAxis.addOption(
                actor, sizeAxis, new SpecAxisOptionInput("L", 2, false)));
    assertThat(tx(actor, () -> specAxis.panel(spuId)).matrixRowCount()).isEqualTo(4);
    assertThat(tx(actor, () -> specAxis.options(sizeAxis))).hasSize(3);
  }

  @Test
  void categoryRuleRejectsAxisThatCategoryDoesNotAllow() {
    String allowed = uniqueCode("ALW"), denied = uniqueCode("DEN");
    UUID allowedAxis = saveAxis(allowed, "允许的颜色");
    UUID deniedAxis = saveAxis(denied, "不允许的轴");
    write(
        actor,
        () ->
            specAxis.saveCategoryRule(
                actor, new CategorySpecAxisInput("受限类目", false, 0), allowedAxis));

    UUID spuId = spu("受限类目");
    write(actor, () -> specAxis.bindAxis(actor, spuId, new SpecAxisBindingInput(allowedAxis, 0, true, allowed)));
    // 名单外的轴：DB 触发器拒。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        specAxis.bindAxis(
                            actor, spuId, new SpecAxisBindingInput(deniedAxis, 1, true, denied))))
        .isInstanceOf(ResponseStatusException.class);
    // 没配规则的类目不受限制。
    UUID otherSpu = spu("自由类目");
    write(actor, () -> specAxis.bindAxis(actor, otherSpu, new SpecAxisBindingInput(deniedAxis, 0, true, denied)));
    assertThat(tx(actor, () -> specAxis.panel(otherSpu)).axes()).hasSize(1);
  }

  @Test
  void missingRequiredAxisIsReportedInPanel() {
    String required = uniqueCode("REQ"), optional = uniqueCode("OPT");
    UUID requiredAxis = saveAxis(required, "必绑颜色");
    UUID optionalAxis = saveAxis(optional, "可选尺码");
    write(actor, () -> specAxis.saveCategoryRule(actor, new CategorySpecAxisInput("必绑类目", true, 0), requiredAxis));
    write(actor, () -> specAxis.saveCategoryRule(actor, new CategorySpecAxisInput("必绑类目", false, 1), optionalAxis));

    UUID spuId = spu("必绑类目");
    var empty = tx(actor, () -> specAxis.panel(spuId));
    assertThat(empty.missingRequiredAxisCount()).isEqualTo(1);
    assertThat(empty.requiredAxisNotes()).anyMatch(n -> n.contains("尚未绑定"));

    write(actor, () -> specAxis.bindAxis(actor, spuId, new SpecAxisBindingInput(requiredAxis, 0, true, required)));
    var filled = tx(actor, () -> specAxis.panel(spuId));
    assertThat(filled.missingRequiredAxisCount()).isZero();
    assertThat(filled.requiredAxisNotes()).anyMatch(n -> n.contains("已绑定"));
  }

  @Test
  void categoryRulesAreListedAndDeletedWithoutUnbindingExistingSpus() {
    String code = uniqueCode("RUL");
    UUID axisId = saveAxis(code, "规则轴");
    UUID spuId = spu("规则类目");
    write(actor, () -> specAxis.bindAxis(actor, spuId, new SpecAxisBindingInput(axisId, 0, true, code)));
    write(actor, () -> specAxis.saveCategoryRule(actor, new CategorySpecAxisInput("规则类目", false, 0), axisId));

    var rules = tx(actor, () -> specAxis.categoryRules("规则类目"));
    assertThat(rules).hasSize(1);
    assertThat(rules.getFirst().boundSpuCount()).isEqualTo(1);
    assertThat(rules.getFirst().specAxisCode()).isEqualTo(code);

    write(actor, () -> specAxis.deleteCategoryRule(actor, "规则类目", axisId));
    assertThat(tx(actor, () -> specAxis.categoryRules("规则类目"))).isEmpty();
    // 删规则**不**解绑既有 SPU：规则只是新建绑定时的准入条件。
    assertThat(tx(actor, () -> specAxis.panel(spuId)).axes()).hasSize(1);
  }

  @Test
  void overviewCountsRealRowsAndFlagsUnboundVariantAxisAttributes() {
    var mine = bound(uniqueCode("OVW"), "服饰");
    option(mine.axisId(), "红", 0);
    var overview = tx(actor, () -> specAxis.overview());
    assertThat(overview.axisCount()).isPositive();
    assertThat(overview.boundSpuCount()).isPositive();
    assertThat(overview.optionCount()).isPositive();
    assertThat(overview.topAxes()).isNotEmpty();
    // 缺口提示随真实计数走：本库没有未挂字典的 variant_axis 属性，所以是「全部已挂」这句。
    // 断言计数而不是文案分支——文案随数据变，断言它等于把用例钉死在某个数据状态上。
    assertThat(overview.unboundVariantAxisAttributes())
        .isEqualTo(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from attribute_definition d where d.variant_axis"
                                    + " and not exists(select 1 from spu_spec_axis b"
                                    + " where b.tenant_id=d.tenant_id and b.attribute_definition_id=d.id)")
                            .get(0, Long.class))
                .longValue());
    assertThat(overview.note()).isNotBlank();
  }

  @Test
  void unboundVariantAxisAttributeIsFlaggedAsGovernanceGap() {
    // 显式造一个"有 variant_axis 属性但没挂轴字典"的 SPU（3.1.1/3.1.2 的历史数据形状），
    // 概览必须把它报成治理缺口，而不是当作正常。
    UUID spuId = spu("孤儿属性类目");
    String code = uniqueCode("ORP");
    UUID attrId = UUID.randomUUID();
    tx(
        actor,
        () -> {
          sql.execute(
              "insert into attribute_definition(id,tenant_id,spu_id,code,name,data_type,value_mode,"
                  + "multiple,required,variant_axis,group_name,position)"
                  + " values(?::uuid,?::uuid,?::uuid,?,'未挂字典的规格轴属性','OPTION','SKU_ONLY',"
                  + "false,true,true,'规格',0)",
              attrId,
              a,
              spuId,
              code);
          return null;
        });
    var overview = tx(actor, () -> specAxis.overview());
    assertThat(overview.unboundVariantAxisAttributes()).isPositive();
    assertThat(overview.note()).contains("variant_axis");
  }

  // ------------------------------------------------------------ 租户隔离三断言

  @Test
  void specAxesAreTenantIsolatedAndHeaderForgeryIsIgnored() throws Exception {
    String myCode = uniqueCode("ISO");
    var mine = bound(myCode, "服饰");
    option(mine.axisId(), "我租户的颜色", 0);
    UUID myAxisId = mine.axisId();

    // B 租户建自己的同编码轴（**同 code，不同租户**）：字典是租户级隔离的。
    UUID theirAxisId = saveAxisB(myCode, "B 租户同编码轴");
    var theirSpu = new Object[] {null};
    access.run(
        actorB,
        "catalog.spu.create",
        () -> {
          UUID spuId =
              catalog.saveSpu(
                      actorB,
                      null,
                      new SpuInput(
                          "ISO-B-" + UUID.randomUUID().toString().substring(0, 8),
                          "B 租户商品",
                          "",
                          "品牌",
                          "服饰",
                          "DRAFT",
                          0));
          theirSpu[0] = spuId;
          return null;
        });
    UUID theirSpuId = (UUID) theirSpu[0];
    access.run(
        actorB,
        "catalog.attribute.update",
        () -> {
          specAxis.bindAxis(actorB, theirSpuId, new SpecAxisBindingInput(theirAxisId, 0, true, myCode));
          specAxis.addOption(actorB, theirAxisId, new SpecAxisOptionInput("B 的颜色", 0, true));
          return null;
        });

    // 1) 两租户互不可见
    assertThat(tx(actorB, () -> specAxis.axes()).stream().map(SpecAxis::id)).doesNotContain(myAxisId);
    assertThat(tx(actor, () -> specAxis.axes()).stream().map(SpecAxis::id)).doesNotContain(theirAxisId);
    // B 的选项读不到 A 的，反之亦然。
    assertThat(tx(actor, () -> specAxis.options(myAxisId))).extracting(SpecAxisOption::value)
        .containsExactly("我租户的颜色");
    // B 拿 A 的 SPU 取面板 → 404
    assertThatThrownBy(() -> tx(actorB, () -> specAxis.panel(mine.spuId())))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    // B 往 A 的轴上加选项 → 404（写路径同样过 RLS）
    assertThatThrownBy(
            () -> write(actorB, () -> specAxis.addOption(actorB, myAxisId, new SpecAxisOptionInput("越权写入", 9, true))))
        .isInstanceOf(ResponseStatusException.class);
    // B 改 A 的轴名 → 404
    assertThatThrownBy(
            () ->
                write(
                    actorB,
                    () ->
                        specAxis.saveAxis(
                            actorB, myAxisId, new SpecAxisInput(myCode, "B 改的名字", true, 0))))
        .isInstanceOf(ResponseStatusException.class);
    // A 的数据仍在
    assertThat(tx(actor, () -> specAxis.panel(mine.spuId())).axes()).hasSize(1);

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(
            get("/catalog/spec-axis/spus/" + mine.spuId())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/spec-axis/spus/" + mine.spuId())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());
    // 伪造头也不能借道写
    http.perform(
            post("/catalog/spec-axis/axes/" + myAxisId + "/options")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content("{\"value\":\"伪造头写入\",\"position\":9,\"enabled\":true}"))
        .andExpect(status().isNotFound());

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> specAxis.options(myAxisId))).extracting(SpecAxisOption::value)
          .containsExactly("我租户的颜色");
      assertThat(tx(actorB, () -> specAxis.options(theirAxisId))).extracting(SpecAxisOption::value)
          .containsExactly("B 的颜色");
      assertThatThrownBy(() -> tx(actorB, () -> specAxis.panel(mine.spuId())))
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  @Test
  void writeEndpointsRequireAttributeUpdatePermission() throws Exception {
    String code = uniqueCode("RBAC");
    http.perform(
            post("/catalog/spec-axis/axes")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content("{\"code\":\"" + code + "\",\"name\":\"只读\",\"enabled\":true}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void httpEndpointsExposeTheRealWorkflow() throws Exception {
    String code = uniqueCode("HTTP");
    http.perform(get("/catalog/spec-axis/axes").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/spec-axis/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    var created =
        http.perform(
                post("/catalog/spec-axis/axes")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content("{\"code\":\"" + code + "\",\"name\":\"HTTP 轴\",\"enabled\":true}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = between(created, "\"id\":\"", "\"");
    http.perform(get("/catalog/spec-axis/axes/" + id + "/options").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    // 未认证 → 不能是 200（身份服务拒绝或不可达都算）。
    int anonymous =
        http.perform(get("/catalog/spec-axis/axes")).andReturn().getResponse().getStatus();
    assertThat(anonymous).isNotEqualTo(200);
  }

  private static String between(String text, String prefix, String suffix) {
    int start = text.indexOf(prefix);
    if (start < 0) throw new IllegalStateException("prefix not found: " + text);
    start += prefix.length();
    int end = text.indexOf(suffix, start);
    if (end < 0) throw new IllegalStateException("suffix not found: " + text);
    return text.substring(start, end);
  }

  // ------------------------------------------------------------ 表结构断言

  @Test
  void specAxisTablesAreRlsProtectedAndTenantScoped() {
    var weak =
        sql.fetch(
            "select relname from pg_class join pg_namespace n on n.oid=relnamespace"
                + " where n.nspname='public' and relkind='r'"
                + " and relname in ('spec_axis','spec_axis_option','spu_spec_axis','category_spec_axis')"
                + " and (not relrowsecurity or not relforcerowsecurity)");
    assertThat(weak).isEmpty();
    for (String table : List.of("spec_axis", "spec_axis_option", "spu_spec_axis", "category_spec_axis")) {
      var nullable =
          sql.fetchOne(
              "select is_nullable from information_schema.columns where table_name=? and column_name='tenant_id'",
              table);
      assertThat(nullable.get("is_nullable", String.class)).as(table).isEqualTo("NO");
    }
    // 规格轴必须是租户级字典：spec_axis 不得带 spu_id（退化成每 SPU 一套就违反蓝图）。
    var axisColumns =
        sql.fetch(
            "select column_name from information_schema.columns"
                + " where table_name='spec_axis' and column_name='spu_id'");
    assertThat(axisColumns).isEmpty();
    // 选项值长度必须与 attribute_option.value 一致（两处存同一个值）。
    for (String table : List.of("spec_axis_option", "attribute_option")) {
      var type =
          sql.fetchOne(
              "select character_maximum_length from information_schema.columns"
                  + " where table_name=? and column_name='value'",
              table);
      assertThat(type.get("character_maximum_length", Integer.class)).as(table).isEqualTo(100);
    }
  }
}