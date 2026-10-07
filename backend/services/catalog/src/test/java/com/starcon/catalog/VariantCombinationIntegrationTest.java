package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.tenancy.Ids;
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
 * 3.1.3.2 组合唯一性校验：结构化冲突清单、矩阵生成前预校验、生成待建 SKU 草稿、
 * **数据库层唯一性约束（含绕过应用层仍被拒）**，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class VariantCombinationIntegrationTest {
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
  @Autowired VariantCombinationService service;
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

  // ------------------------------------------------------------ 夹具

  /** 轴编码租户内唯一：每个用例用独立编码，避免用例之间互相抢占字典位。 */
  String uniqueCode(String hint) {
    return hint + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
  }

  UUID saveAxis(String code, String name) {
    return write(actor, () -> specAxis.saveAxis(actor, null, new SpecAxisInput(code, name, true, 0)))
        .id();
  }

  UUID saveAxisB(String code, String name) {
    return write(
            actorB, () -> specAxis.saveAxis(actorB, null, new SpecAxisInput(code, name, true, 0)))
        .id();
  }

  UUID spu(String category) {
    return write(
            actor,
            () ->
                catalog.saveSpu(
                    actor,
                    null,
                    new SpuInput(
                        "VC-" + UUID.randomUUID().toString().substring(0, 8),
                        "组合唯一性测试商品",
                        "描述",
                        "品牌",
                        category,
                        "DRAFT",
                        0)));
  }

  UUID spuB(String category) {
    return write(
            actorB,
            () ->
                catalog.saveSpu(
                    actorB,
                    null,
                    new SpuInput(
                        "VCB-" + UUID.randomUUID().toString().substring(0, 8),
                        "B 租户商品",
                        "",
                        "品牌",
                        category,
                        "DRAFT",
                        0)));
  }

  /** 两条轴（颜色 + 尺码）绑到同一个 SPU：组合唯一性至少要两个维度才有意义。 */
  record TwoAxes(UUID spuId, UUID colorAxis, UUID sizeAxis, String colorCode, String sizeCode) {}

  TwoAxes twoAxes() {
    return twoAxes(actor, true);
  }

  TwoAxes twoAxes(AccessService.Access who, boolean tenantA) {
    String colorCode = uniqueCode("CLR");
    String sizeCode = uniqueCode("SIZ");
    UUID colorAxis =
        tenantA
            ? saveAxis(colorCode, "颜色")
            : write(actorB, () -> specAxis.saveAxis(actorB, null, new SpecAxisInput(colorCode, "颜色", true, 0)))
                .id();
    UUID sizeAxis =
        tenantA
            ? saveAxis(sizeCode, "尺码")
            : write(actorB, () -> specAxis.saveAxis(actorB, null, new SpecAxisInput(sizeCode, "尺码", true, 0)))
                .id();
    UUID spuId = tenantA ? spu("服饰") : spuB("服饰");
    write(
        who,
        () -> {
          specAxis.bindAxis(who, spuId, new SpecAxisBindingInput(colorAxis, 0, true, colorCode));
          specAxis.bindAxis(who, spuId, new SpecAxisBindingInput(sizeAxis, 1, true, sizeCode));
          return null;
        });
    return new TwoAxes(spuId, colorAxis, sizeAxis, colorCode, sizeCode);
  }

  SpecAxisOptionSaved option(UUID axisId, String value, int position) {
    return write(actor, () -> specAxis.addOption(actor, axisId, new SpecAxisOptionInput(value, position, true)));
  }

  SpecAxisOptionSaved optionB(UUID axisId, String value, int position) {
    return write(
        actorB,
        () -> specAxis.addOption(actorB, axisId, new SpecAxisOptionInput(value, position, true)));
  }

  CombinationInput combo(UUID axisId, String value) {
    return new CombinationInput(List.of(new CombinationValueInput(axisId, value)));
  }

  /** 「颜色=红 且 尺码=大」这一条组合。 */
  CombinationInput combo(TwoAxes axes, String color, String size) {
    return new CombinationInput(
        List.of(
            new CombinationValueInput(axes.colorAxis(), color),
            new CombinationValueInput(axes.sizeAxis(), size)));
  }

  /** 与应用层同一套规则的期望签名，用于构造"绕过应用层"的裸 SQL。 */
  String signature(TwoAxes axes, String color, String size) {
    return CombinationRules.signature(
            List.of(
                new CombinationRules.Part(axes.colorCode(), color),
                new CombinationRules.Part(axes.sizeCode(), size)),
            Set.of())
        .signature();
  }

  VariantDraftInput matrixDraft() {
    return new VariantDraftInput("MATRIX", null, null, null);
  }

  /** 直连写库用的草稿壳子：组合有外键指向它，先得有一行。 */
  UUID rawDraft(UUID spuId) {
    UUID draftId = Ids.next();
    sql.execute(
        "insert into sku_matrix_draft(id,tenant_id,spu_id,mode,status,axis_count,combination_count,"
            + "notes,actor_id,occurred_at) values(?::uuid,?::uuid,?::uuid,'EXPLICIT','PENDING',2,1,"
            + "'绕过应用层写入',?::uuid,now())",
        draftId, a, spuId, user);
    return draftId;
  }

  /** 绕过应用层直插一条组合占用（签名与明细都对得上，只差唯一性）。 */
  UUID rawCombination(TwoAxes axes, UUID draftId, String signature) {
    UUID id = Ids.next();
    sql.execute(
        "insert into spu_variant_combination(id,tenant_id,spu_id,signature,axis_count,draft_id,"
            + "actor_id,occurred_at) values(?::uuid,?::uuid,?::uuid,?,2,?::uuid,?::uuid,now())",
        id, a, axes.spuId(), signature, draftId, user);
    sql.execute(
        "insert into spu_variant_combination_value(tenant_id,combination_id,spec_axis_id,axis_code,"
            + "option_value,position) values(?::uuid,?::uuid,?::uuid,?,?,0)",
        a, id, axes.colorAxis(), axes.colorCode(), "红");
    sql.execute(
        "insert into spu_variant_combination_value(tenant_id,combination_id,spec_axis_id,axis_code,"
            + "option_value,position) values(?::uuid,?::uuid,?::uuid,?,?,1)",
        a, id, axes.sizeAxis(), axes.sizeCode(), "大");
    return id;
  }

  /** 建一个真实的 SKU 并写入它在规格轴上的取值（走 attribute_value / attribute_value_item）。 */
  UUID rawSku(TwoAxes axes, String color, String size, String signature) {
    UUID skuId = Ids.next();
    sql.execute(
        "insert into sku(id,tenant_id,spu_id,code,name,variant_signature)"
            + " values(?::uuid,?::uuid,?::uuid,?,?,?)",
        skuId,
        a,
        axes.spuId(),
        "VC-SKU-" + UUID.randomUUID().toString().substring(0, 8),
        "真实 SKU",
        signature);
    writeAxisValue(axes, skuId, axes.colorAxis(), color);
    writeAxisValue(axes, skuId, axes.sizeAxis(), size);
    return skuId;
  }

  void writeAxisValue(TwoAxes axes, UUID skuId, UUID axisId, String value) {
    UUID attributeId =
        sql.fetchOne(
                "select attribute_definition_id from spu_spec_axis where spec_axis_id=?", axisId)
            .get("attribute_definition_id", UUID.class);
    UUID valueId = Ids.next();
    sql.execute(
        "insert into attribute_value(id,tenant_id,spu_id,attribute_id,sku_id,state)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,'SET')",
        valueId, a, axes.spuId(), attributeId, skuId);
    sql.execute(
        "insert into attribute_value_item(tenant_id,value_id,attribute_id,ordinal,option_value)"
            + " values(?::uuid,?::uuid,?::uuid,0,?)",
        a, valueId, attributeId, value);
  }

  // ------------------------------------------------------------ 重复检测与结构化冲突清单

  @Test
  void duplicateCombinationsInOneSubmissionAreReportedWithTheirIndexes() {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.colorAxis(), "蓝", 1);
    option(axes.sizeAxis(), "大", 0);

    var result =
        tx(
            actor,
            () ->
                service.check(
                    axes.spuId(),
                    new CombinationCheckInput(
                        List.of(
                            combo(axes, "红", "大"),
                            combo(axes, "蓝", "大"),
                            combo(axes, "红", "大")))));

    // 结构化清单，不是一个布尔值：运营要知道是"第 1 条和第 3 条撞了"。
    assertThat(result.valid()).isFalse();
    assertThat(result.submittedCount()).isEqualTo(3);
    assertThat(result.plannedCount()).isEqualTo(1);
    var duplicate =
        result.conflicts().stream()
            .filter(c -> "DUPLICATE_IN_BATCH".equals(c.reasonCode()))
            .findFirst()
            .orElseThrow();
    assertThat(duplicate.signature()).isEqualTo(signature(axes, "红", "大"));
    assertThat(duplicate.occurrenceIndexes()).containsExactly(0, 2);
    assertThat(duplicate.message()).contains("2 次");
    // 撞自己那条不进 planned：它已经是冲突，不是可用组合。
    assertThat(result.planned()).extracting(PlannedCombination::signature)
        .containsExactly(signature(axes, "蓝", "大"));
  }

  @Test
  void incompleteCombinationIsRejectedBecauseEveryBoundAxisMustBeValued() {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.sizeAxis(), "大", 0);

    // 只填颜色、漏了尺码：同一个组合在不同人填法下会指向不同的 SKU。
    var result =
        tx(
            actor,
            () ->
                service.check(
                    axes.spuId(),
                    new CombinationCheckInput(List.of(combo(axes.colorAxis(), "红")))));
    assertThat(result.valid()).isFalse();
    assertThat(result.planned()).isEmpty();
    var incomplete =
        result.conflicts().stream()
            .filter(c -> "INCOMPLETE_COMBINATION".equals(c.reasonCode()))
            .findFirst()
            .orElseThrow();
    assertThat(incomplete.message()).contains(axes.sizeCode());
  }

  @Test
  void axisFromAnotherSpuCannotBeMixedIntoACombination() {
    TwoAxes mine = twoAxes();
    TwoAxes other = twoAxes();
    option(mine.colorAxis(), "红", 0);
    option(mine.sizeAxis(), "大", 0);
    option(other.colorAxis(), "蓝", 0);
    option(other.sizeAxis(), "小", 0);

    // "从另一个商品复制组合"最容易带进来的错：两轴都合法，但不是本 SPU 的轴。
    var result =
        tx(
            actor,
            () ->
                service.check(
                    mine.spuId(),
                    new CombinationCheckInput(
                        List.of(
                            new CombinationInput(
                                List.of(
                                    new CombinationValueInput(mine.colorAxis(), "红"),
                                    new CombinationValueInput(other.sizeAxis(), "小")))))));
    assertThat(result.valid()).isFalse();
    assertThat(result.planned()).isEmpty();
    var unknown =
        result.conflicts().stream()
            .filter(c -> "UNKNOWN_AXIS".equals(c.reasonCode()))
            .findFirst()
            .orElseThrow();
    assertThat(unknown.message()).contains("没有绑定到这个 SPU");
  }

  @Test
  void optionMustBelongToTheAxisItIsSubmittedUnder() {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    // "大" 只登记在尺码轴上，把它挂到颜色轴下必须被拒。
    option(axes.sizeAxis(), "大", 0);

    var result =
        tx(
            actor,
            () ->
                service.check(
                    axes.spuId(),
                    new CombinationCheckInput(
                        List.of(
                            new CombinationInput(
                                List.of(
                                    new CombinationValueInput(axes.colorAxis(), "大"),
                                    new CombinationValueInput(axes.sizeAxis(), "大")))))));
    assertThat(result.valid()).isFalse();
    var unknown =
        result.conflicts().stream()
            .filter(c -> "OPTION_UNKNOWN".equals(c.reasonCode()))
            .findFirst()
            .orElseThrow();
    assertThat(unknown.message()).contains("没有取值");
    assertThat(unknown.message()).contains("大");
    // 候选清单要写出来：运营得知道该从哪几个取值里挑，而不是只知道自己填错了。
    assertThat(unknown.message()).contains("候选");
  }

  @Test
  void disabledOptionAndDisabledAxisBlockNewCombinationsButHistoryStaysReadable() {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.colorAxis(), "蓝", 1);
    option(axes.sizeAxis(), "大", 0);

    // 停用取值：挡新建。
    write(actor, () -> specAxis.addOption(actor, axes.colorAxis(), new SpecAxisOptionInput("蓝", 1, false)));
    var blocked =
        tx(
            actor,
            () ->
                service.check(
                    axes.spuId(),
                    new CombinationCheckInput(List.of(combo(axes, "蓝", "大")))));
    assertThat(blocked.valid()).isFalse();
    assertThat(blocked.conflicts()).extracting(CombinationConflict::reasonCode)
        .contains("OPTION_DISABLED");

    // 历史组合照常可读：面板仍能解析出已占用组合的轴值。
    write(actor, () -> service.generateDraft(actor, axes.spuId(),
        new VariantDraftInput("EXPLICIT", List.of(combo(axes, "红", "大")), null, null)));
    var panel = tx(actor, () -> service.panel(axes.spuId()));
    assertThat(panel.combinations()).anySatisfy(
        row -> {
          assertThat(row.values()).extracting(CombinationValue::value).contains("红", "大");
        });

    // 停用轴同样挡新建。
    var axis = tx(actor, () -> specAxis.axes()).stream()
        .filter(x -> x.id().equals(axes.sizeAxis())).findFirst().orElseThrow();
    write(actor, () -> specAxis.saveAxis(actor, axis.id(),
        new SpecAxisInput(axis.code(), axis.name(), false, axis.version())));
    var axisBlocked =
        tx(
            actor,
            () ->
                service.check(
                    axes.spuId(),
                    new CombinationCheckInput(List.of(combo(axes, "红", "大")))));
    assertThat(axisBlocked.conflicts()).extracting(CombinationConflict::reasonCode)
        .contains("AXIS_DISABLED");
  }

  @Test
  void theSameAxisTwiceInsideOneCombinationIsRejected() {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.colorAxis(), "蓝", 1);
    option(axes.sizeAxis(), "大", 0);

    var result =
        tx(
            actor,
            () ->
                service.check(
                    axes.spuId(),
                    new CombinationCheckInput(
                        List.of(
                            new CombinationInput(
                                List.of(
                                    new CombinationValueInput(axes.colorAxis(), "红"),
                                    new CombinationValueInput(axes.colorAxis(), "蓝"),
                                    new CombinationValueInput(axes.sizeAxis(), "大")))))));
    assertThat(result.valid()).isFalse();
    assertThat(result.planned()).isEmpty();
    assertThat(result.conflicts()).extracting(CombinationConflict::reasonCode).contains("DUPLICATE_AXIS");
  }

  // ------------------------------------------------------------ 生成待建 SKU 草稿

  @Test
  void validCombinationsProduceAPendingSkuDraftAndNotASkuRow() {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.colorAxis(), "蓝", 1);
    option(axes.sizeAxis(), "大", 0);

    var draft = write(actor, () -> service.generateDraft(actor, axes.spuId(), matrixDraft()));

    // 蓝图输出是"待建 SKU 草稿"，不是 SKU 本身：sku 表必须还是空的。
    assertThat(draft.status()).isEqualTo("PENDING");
    assertThat(draft.mode()).isEqualTo("MATRIX");
    assertThat(draft.axisCount()).isEqualTo(2);
    assertThat(draft.combinationCount()).isEqualTo(2);
    assertThat(draft.combinations()).extracting(PlannedCombination::signature)
        .containsExactlyInAnyOrder(
            signature(axes, "红", "大"), signature(axes, "蓝", "大"));
    assertThat(draft.combinations().getFirst().values())
        .extracting(CombinationValue::value)
        .containsExactlyInAnyOrder("红", "大");
    assertThat(draft.occurredAt()).isNotBlank();
    long skus =
        tx(actor, () -> sql.fetchOne("select count(*) from sku where spu_id=?", axes.spuId()).get(0, Long.class));
    assertThat(skus).isZero();
  }

  @Test
  void draftReservesItsCombinationsAndADiscardedDraftReleasesThemAgain() {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.sizeAxis(), "大", 0);

    var draft = write(actor, () -> service.generateDraft(actor, axes.spuId(), matrixDraft()));

    // 同一组合不能被第二次草稿占用。
    var second =
        tx(
            actor,
            () ->
                service.check(
                    axes.spuId(), new CombinationCheckInput(List.of(combo(axes, "红", "大")))));
    assertThat(second.valid()).isFalse();
    var reserved =
        second.conflicts().stream()
            .filter(c -> "ALREADY_RESERVED".equals(c.reasonCode()))
            .findFirst()
            .orElseThrow();
    assertThat(reserved.existingDraftId()).isEqualTo(draft.id());
    assertThat(reserved.existingCombinationId()).isNotNull();

    // 作废草稿 → 释放占用 → 同一个组合可以重新生成。
    var discarded = write(actor, () -> service.discardDraft(actor, axes.spuId(), draft.id()));
    assertThat(discarded.releasedCombinationCount()).isEqualTo(1);
    assertThat(tx(actor, () -> service.drafts(axes.spuId())).getFirst().status()).isEqualTo("DISCARDED");
    assertThat(
            tx(actor, () -> service.combinations(axes.spuId())).stream()
                .filter(row -> "RESERVED".equals(row.source())))
        .isEmpty();
    var again = write(actor, () -> service.generateDraft(actor, axes.spuId(), matrixDraft()));
    assertThat(again.combinationCount()).isEqualTo(1);
    // 作废两次要报错，而不是静默成功。
    assertThatThrownBy(
            () -> write(actor, () -> service.discardDraft(actor, axes.spuId(), draft.id())))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
  }

  @Test
  void combinationsAlreadyUsedByARealSkuAreBlocked() {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.sizeAxis(), "大", 0);
    // 已有真实 SKU 取了「红/大」：按轴值逐条比对必须命中。
    // 裸 SQL 写 sku 也必须在租户事务里跑：RLS 是 FORCE 的，没有 app.current_tenant 一律被拒。
    UUID skuId = tx(actor, () -> rawSku(axes, "红", "大", signature(axes, "红", "大")));

    var result =
        tx(
            actor,
            () ->
                service.check(
                    axes.spuId(), new CombinationCheckInput(List.of(combo(axes, "红", "大")))));
    assertThat(result.valid()).isFalse();
    var conflict =
        result.conflicts().stream()
            .filter(c -> "ALREADY_SKU".equals(c.reasonCode()))
            .findFirst()
            .orElseThrow();
    assertThat(conflict.existingSkuId()).isEqualTo(skuId);
    assertThat(conflict.message()).contains("与已存在的 SKU");
    // 生成草稿时同样被拒，且返回完整清单而不是一句提示。
    assertThatThrownBy(
            () -> write(actor, () -> service.generateDraft(actor, axes.spuId(), matrixDraft())))
        .isInstanceOf(VariantCombinationService.CombinationConflictException.class)
        .satisfies(
            e ->
                assertThat(
                        ((VariantCombinationService.CombinationConflictException) e)
                            .result()
                            .conflicts())
                    .extracting(CombinationConflict::reasonCode)
                    .contains("ALREADY_SKU"));

    // 组合清单里能看到这个既有 SKU 的组合，来源标成 SKU（按取值解析出来的那种）。
    assertThat(tx(actor, () -> service.combinations(axes.spuId())))
        .anySatisfy(
            row -> {
              assertThat(row.source()).isEqualTo("SKU");
              assertThat(row.skuId()).isEqualTo(skuId);
              assertThat(row.values()).extracting(CombinationValue::value).containsExactlyInAnyOrder("红", "大");
            });
  }

  @Test
  void oversizedMatrixIsRejectedWithTheRealRowCountInsteadOfBeingTruncated() {
    TwoAxes axes = twoAxes();
    // 45 × 45 = 2025 行，超过上限 2000。
    for (int i = 0; i < 45; i++) {
      option(axes.colorAxis(), "C" + i, i);
      option(axes.sizeAxis(), "S" + i, i);
    }
    var panel = tx(actor, () -> service.panel(axes.spuId()));
    assertThat(panel.matrixRowCount()).isEqualTo(2025);
    assertThat(panel.matrixTooLarge()).isTrue();
    assertThat(panel.note()).contains("2025");
    assertThatThrownBy(() -> write(actor, () -> service.generateDraft(actor, axes.spuId(), matrixDraft())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("2025");
    // 一行也不该落库（限定本 SPU：这个库是所有用例共用的，全表 count 会把别的用例算进来）。
    assertThat(
            tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select count(*) from spu_variant_combination where spu_id=?",
                            axes.spuId())
                        .get(0, Long.class)))
        .isZero();
  }

  @Test
  void axisWithoutEnabledOptionMakesMatrixEmptyAndDraftGenerationSaysWhy() {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    // 尺码轴一个取值都没登记 → 矩阵是 0 行，不是 1 行。
    var panel = tx(actor, () -> service.panel(axes.spuId()));
    assertThat(panel.matrixRowCount()).isZero();
    assertThat(panel.availableMatrixCount()).isZero();
    assertThat(panel.note()).contains("0 行");
    assertThatThrownBy(() -> write(actor, () -> service.generateDraft(actor, axes.spuId(), matrixDraft())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("尺码");
  }

  @Test
  void panelReportsOccupiedAndAvailableMatrixRowsAndOverviewCountsRealRows() {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.colorAxis(), "蓝", 1);
    option(axes.sizeAxis(), "大", 0);
    // 先占掉「红/大」。
    write(actor, () -> service.generateDraft(actor, axes.spuId(),
        new VariantDraftInput("EXPLICIT", List.of(combo(axes, "红", "大")), null, null)));

    var panel = tx(actor, () -> service.panel(axes.spuId()));
    assertThat(panel.matrixRowCount()).isEqualTo(2);
    assertThat(panel.reservedCombinationCount()).isEqualTo(1);
    assertThat(panel.conflictMatrixCount()).isEqualTo(1);
    assertThat(panel.availableMatrixCount()).isEqualTo(1);
    assertThat(panel.drafts()).hasSize(1);
    assertThat(panel.note()).contains("还可生成 1 条");

    var overview = tx(actor, () -> service.overview());
    assertThat(overview.axisBoundSpuCount()).isPositive();
    assertThat(overview.reservedCombinationCount()).isPositive();
    assertThat(overview.pendingDraftCount()).isPositive();
    assertThat(overview.note()).isNotBlank();
    assertThat(overview.blockedSpuCount())
        .isEqualTo(
            tx(actor, () -> sql.fetchOne("select count(distinct spu_id) from sku where btrim(variant_signature)<>''").get(0, Long.class))
                .longValue());
  }

  @Test
  void draftDetailReturnsEveryCombinationWithItsAxisValues() {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.colorAxis(), "蓝", 1);
    option(axes.sizeAxis(), "大", 0);
    var created = write(actor, () -> service.generateDraft(actor, axes.spuId(), matrixDraft()));

    var detail = tx(actor, () -> service.draft(axes.spuId(), created.id()));
    assertThat(detail.id()).isEqualTo(created.id());
    assertThat(detail.spuCode()).isNotBlank();
    assertThat(detail.combinations()).hasSize(2);
    assertThat(detail.combinations()).allSatisfy(
        combination -> {
          assertThat(combination.values()).hasSize(2);
          assertThat(combination.values()).extracting(CombinationValue::axisName).doesNotContainNull();
        });
    // 不属于这个 SPU 的草稿 id 读不到。
    assertThatThrownBy(() -> tx(actor, () -> service.draft(spu("服饰"), created.id())))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void businessTimeAndSystemTimeAreStoredSeparately() {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.sizeAxis(), "大", 0);
    write(
        actor,
        () ->
            service.generateDraft(
                actor,
                axes.spuId(),
                new VariantDraftInput(
                    "MATRIX", null, "自定义备注", "2026-01-02T03:04:05Z")));

    var row =
        tx(
            actor,
            () ->
                sql.fetchOne(
                    "select notes,occurred_at,created_at from sku_matrix_draft where spu_id=?",
                    axes.spuId()));
    assertThat(row.get("notes", String.class)).isEqualTo("自定义备注");
    // 业务时间是调用方给的那一刻，与系统写入时间分开存。
    assertThat(row.get("occurred_at", java.time.OffsetDateTime.class).toInstant())
        .isEqualTo(java.time.OffsetDateTime.parse("2026-01-02T03:04:05Z").toInstant());
    assertThat(row.get("created_at", java.time.OffsetDateTime.class)).isNotNull();
  }

  // ------------------------------------------------------------ 数据库层唯一性（蓝图硬要求）

  @Test
  void databaseUniqueConstraintRejectsADuplicateCombinationEvenWhenTheApplicationIsBypassed() {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.sizeAxis(), "大", 0);
    // 应用层先占住「红/大」。
    write(actor, () -> service.generateDraft(actor, axes.spuId(), matrixDraft()));

    // 绕过应用层：另起一份草稿，直连写库再插一条同签名的组合。
    // 应用层的校验完全不参与，唯一索引必须自己挡住——这是蓝图那句硬要求的技术兑现点。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () -> {
                      UUID draftId = rawDraft(axes.spuId());
                      rawCombination(axes, draftId, signature(axes, "红", "大"));
                      return null;
                    }))
        .hasMessageContaining("duplicate key");
    // 真的没有落库。
    assertThat(
            tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select count(*) from spu_variant_combination where spu_id=? and signature=?",
                            axes.spuId(),
                            signature(axes, "红", "大"))
                        .get(0, Long.class)))
        .isEqualTo(1);
  }

  @Test
  void databaseRejectsReservingACombinationThatARealSkuAlreadyUses() {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.sizeAxis(), "大", 0);
    tx(actor, () -> rawSku(axes, "红", "大", signature(axes, "红", "大")));

    // V1 的 UNIQUE 只管 sku 表内部；跨表这一刀由 V10 的触发器补：
    // "占用表有一条 + sku 表有一条" 同签名并存必须被拒。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () -> {
                      UUID draftId = rawDraft(axes.spuId());
                      rawCombination(axes, draftId, signature(axes, "红", "大"));
                      return null;
                    }))
        .hasMessageContaining("已存在签名相同的 SKU");
  }

  @Test
  void v1SkuSignatureUniquenessStillRejectsADuplicateRealSku() {
    // 已兑现的真实 SKU 那一侧的唯一性是 V1 的既有事实，本模块不能把它削弱。
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.sizeAxis(), "大", 0);
    tx(actor, () -> rawSku(axes, "红", "大", signature(axes, "红", "大")));
    assertThatThrownBy(
            () -> tx(actor, () -> rawSku(axes, "红", "大", signature(axes, "红", "大"))))
        .hasMessageContaining("duplicate key");
  }

  @Test
  void databaseRejectsASignatureThatDoesNotMatchItsAxisValues() {
    // 没有延迟约束触发器的话，可以写 signature='C=红|S=XL' 而明细只放一条轴——
    // 唯一索引会为这串不存在的组合挡住另一个真实的「红/大」，唯一性就名存实亡。
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.sizeAxis(), "大", 0);
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () -> {
                      UUID draftId = rawDraft(axes.spuId());
                      UUID id = Ids.next();
                      sql.execute(
                          "insert into spu_variant_combination(id,tenant_id,spu_id,signature,axis_count,"
                              + "draft_id,actor_id,occurred_at) values(?::uuid,?::uuid,?::uuid,?,1,"
                              + "?::uuid,?::uuid,now())",
                          id, a, axes.spuId(), "自报但不存在的组合", draftId, user);
                      sql.execute(
                          "insert into spu_variant_combination_value(tenant_id,combination_id,"
                              + "spec_axis_id,axis_code,option_value,position)"
                              + " values(?::uuid,?::uuid,?::uuid,?,?,0)",
                          a, id, axes.colorAxis(), axes.colorCode(), "红");
                      return null;
                    }))
        .hasMessageContaining("不一致");
  }

  @Test
  void databaseRejectsAxisCountThatDisagreesWithTheAxisValueRows() {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.sizeAxis(), "大", 0);
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () -> {
                      UUID draftId = rawDraft(axes.spuId());
                      UUID id = Ids.next();
                      // 声明 2 条轴，实际只写 1 行明细。
                      sql.execute(
                          "insert into spu_variant_combination(id,tenant_id,spu_id,signature,axis_count,"
                              + "draft_id,actor_id,occurred_at) values(?::uuid,?::uuid,?::uuid,?,2,"
                              + "?::uuid,?::uuid,now())",
                          id, a, axes.spuId(), axes.colorCode() + "=红", draftId, user);
                      sql.execute(
                          "insert into spu_variant_combination_value(tenant_id,combination_id,"
                              + "spec_axis_id,axis_code,option_value,position)"
                              + " values(?::uuid,?::uuid,?::uuid,?,?,0)",
                          a, id, axes.colorAxis(), axes.colorCode(), "红");
                      return null;
                    }))
        .hasMessageContaining("轴数");
  }

  @Test
  void databaseRejectsAnAxisThatDoesNotBelongToTheSpu() {
    // 绕过应用层把另一个 SPU 的规格轴塞进组合明细：结构守卫必须挡住。
    TwoAxes mine = twoAxes();
    TwoAxes other = twoAxes();
    option(mine.colorAxis(), "红", 0);
    option(mine.sizeAxis(), "大", 0);
    option(other.colorAxis(), "蓝", 0);
    option(other.sizeAxis(), "小", 0);
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () -> {
                      UUID draftId = rawDraft(mine.spuId());
                      UUID id = Ids.next();
                      sql.execute(
                          "insert into spu_variant_combination(id,tenant_id,spu_id,signature,axis_count,"
                              + "draft_id,actor_id,occurred_at) values(?::uuid,?::uuid,?::uuid,?,2,"
                              + "?::uuid,?::uuid,now())",
                          id, a, mine.spuId(), "混用轴", draftId, user);
                      sql.execute(
                          "insert into spu_variant_combination_value(tenant_id,combination_id,"
                              + "spec_axis_id,axis_code,option_value,position)"
                              + " values(?::uuid,?::uuid,?::uuid,?,?,0)",
                          a, id, mine.colorAxis(), mine.colorCode(), "红");
                      // 第二个轴属于另一个 SPU。
                      sql.execute(
                          "insert into spu_variant_combination_value(tenant_id,combination_id,"
                              + "spec_axis_id,axis_code,option_value,position)"
                              + " values(?::uuid,?::uuid,?::uuid,?,?,1)",
                          a, id, other.sizeAxis(), other.sizeCode(), "小");
                      return null;
                    }))
        .hasMessageContaining("不属于该 SPU");
  }

  @Test
  void databaseRejectsASelfReportedAxisCodeThatIsNotTheRealOne() {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.sizeAxis(), "大", 0);
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () -> {
                      UUID draftId = rawDraft(axes.spuId());
                      UUID id = Ids.next();
                      sql.execute(
                          "insert into spu_variant_combination(id,tenant_id,spu_id,signature,axis_count,"
                              + "draft_id,actor_id,occurred_at) values(?::uuid,?::uuid,?::uuid,?,2,"
                              + "?::uuid,?::uuid,now())",
                          id, a, axes.spuId(), "自报编码", draftId, user);
                      sql.execute(
                          "insert into spu_variant_combination_value(tenant_id,combination_id,"
                              + "spec_axis_id,axis_code,option_value,position)"
                              + " values(?::uuid,?::uuid,?::uuid,'NOT_THE_REAL_CODE',?,0)",
                          a, id, axes.colorAxis(), "红");
                      return null;
                    }))
        .hasMessageContaining("不接受调用方自报");
  }

  @Test
  void databaseRejectsADisabledOptionInsideANewCombination() {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.sizeAxis(), "大", 0);
    write(
        actor,
        () ->
            specAxis.addOption(actor, axes.sizeAxis(), new SpecAxisOptionInput("大", 0, false)));
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () -> {
                      UUID draftId = rawDraft(axes.spuId());
                      UUID id = Ids.next();
                      sql.execute(
                          "insert into spu_variant_combination(id,tenant_id,spu_id,signature,axis_count,"
                              + "draft_id,actor_id,occurred_at) values(?::uuid,?::uuid,?::uuid,?,2,"
                              + "?::uuid,?::uuid,now())",
                          id, a, axes.spuId(), "停用取值组合", draftId, user);
                      sql.execute(
                          "insert into spu_variant_combination_value(tenant_id,combination_id,"
                              + "spec_axis_id,axis_code,option_value,position)"
                              + " values(?::uuid,?::uuid,?::uuid,?,?,0)",
                          a, id, axes.colorAxis(), axes.colorCode(), "红");
                      sql.execute(
                          "insert into spu_variant_combination_value(tenant_id,combination_id,"
                              + "spec_axis_id,axis_code,option_value,position)"
                              + " values(?::uuid,?::uuid,?::uuid,?,?,1)",
                          a, id, axes.sizeAxis(), axes.sizeCode(), "大");
                      return null;
                    }))
        .hasMessageContaining("已停用");
  }

  @Test
  void aDraftCannotContainTheSameCombinationTwice() {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.sizeAxis(), "大", 0);
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () -> {
                      UUID draftId = rawDraft(axes.spuId());
                      UUID combinationId = rawCombination(axes, draftId, signature(axes, "红", "大"));
                      sql.execute(
                          "insert into sku_matrix_draft_item(tenant_id,draft_id,combination_id,position)"
                              + " values(?::uuid,?::uuid,?::uuid,0)",
                          a, draftId, combinationId);
                      sql.execute(
                          "insert into sku_matrix_draft_item(tenant_id,draft_id,combination_id,position)"
                              + " values(?::uuid,?::uuid,?::uuid,1)",
                          a, draftId, combinationId);
                      return null;
                    }))
        .hasMessageContaining("duplicate key");
  }

  // ------------------------------------------------------------ 租户隔离三断言

  @Test
  void combinationsAreTenantIsolatedAndHeaderForgeryIsIgnored() throws Exception {
    TwoAxes mine = twoAxes();
    option(mine.colorAxis(), "我租户的红", 0);
    option(mine.sizeAxis(), "我租户的大", 0);
    var myDraft = write(actor, () -> service.generateDraft(actor, mine.spuId(), matrixDraft()));

    // B 租户建同结构的一套（不同租户，同一套轴编码会各自独立）。
    TwoAxes theirs = twoAxes(actorB, false);
    optionB(theirs.colorAxis(), "B 的红", 0);
    optionB(theirs.sizeAxis(), "B 的大", 0);
    var theirDraft =
        write(actorB, () -> service.generateDraft(actorB, theirs.spuId(), matrixDraft()));
    assertThat(theirDraft.combinationCount()).isEqualTo(1);

    // 1) 两租户互不可见：读对方的 SPU 一律 404（RLS 已把那行过滤掉，读不到就是读不到，
    //    返回空列表反而会让人以为"这个 SPU 确实没有草稿"）。
    assertThatThrownBy(() -> tx(actorB, () -> service.drafts(mine.spuId())))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    assertThatThrownBy(() -> tx(actor, () -> service.drafts(theirs.spuId())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> service.draft(mine.spuId(), myDraft.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () -> tx(actor, () -> service.draft(theirs.spuId(), theirDraft.id())))
        .isInstanceOf(ResponseStatusException.class);
    // B 拿 A 的 SPU 生成草稿 → 404；台账与草稿都读不到。
    assertThatThrownBy(() -> tx(actorB, () -> service.panel(mine.spuId())))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(
            tx(
                actorB,
                () ->
                    sql.fetchOne(
                            "select count(*) from spu_variant_combination where spu_id=?",
                            mine.spuId())
                        .get(0, Long.class)))
        .isZero();
    // A 的数据仍在。
    assertThat(tx(actor, () -> service.panel(mine.spuId())).reservedCombinationCount()).isEqualTo(1);

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(
            get("/catalog/variant-combination/spus/" + mine.spuId())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/variant-combination/spus/" + mine.spuId())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());
    http.perform(
            post("/catalog/variant-combination/spus/" + mine.spuId() + "/drafts")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content("{\"mode\":\"MATRIX\"}"))
        .andExpect(status().isNotFound());

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> service.panel(mine.spuId())).reservedCombinationCount()).isEqualTo(1);
      assertThat(tx(actorB, () -> service.panel(theirs.spuId())).reservedCombinationCount())
          .isEqualTo(1);
      assertThat(
              tx(
                  actorB,
                  () ->
                      sql.fetchOne(
                              "select count(*) from sku_matrix_draft where spu_id=?", mine.spuId())
                          .get(0, Long.class)))
          .isZero();
    }
  }

  // ------------------------------------------------------------ HTTP 与权限

  @Test
  void writeEndpointsRequireAttributeUpdatePermission() throws Exception {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.sizeAxis(), "大", 0);
    http.perform(
            post("/catalog/variant-combination/spus/" + axes.spuId() + "/drafts")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content("{\"mode\":\"MATRIX\"}"))
        .andExpect(status().isForbidden());
    // 预校验是纯查询，只读角色也该能看到冲突清单。
    http.perform(
            post("/catalog/variant-combination/spus/" + axes.spuId() + "/check")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content("{\"combinations\":[{\"values\":[{\"specAxisId\":\"" + axes.colorAxis()
                    + "\",\"value\":\"红\"}]}]}"))
        .andExpect(status().isOk());
  }

  @Test
  void httpEndpointsExposeTheRealWorkflowAndReturnStructuredConflictsOnRejection() throws Exception {
    TwoAxes axes = twoAxes();
    option(axes.colorAxis(), "红", 0);
    option(axes.sizeAxis(), "大", 0);

    http.perform(get("/catalog/variant-combination/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(
            get("/catalog/variant-combination/spus/" + axes.spuId())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(
            get("/catalog/variant-combination/spus/" + axes.spuId() + "/combinations")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(
            get("/catalog/variant-combination/spus/" + axes.spuId() + "/drafts")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    String created =
        http.perform(
                post("/catalog/variant-combination/spus/" + axes.spuId() + "/drafts")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content("{\"mode\":\"MATRIX\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String draftId = between(created, "\"id\":\"", "\"");
    assertThat(created).contains("\"status\":\"PENDING\"");
    http.perform(
            get("/catalog/variant-combination/spus/" + axes.spuId() + "/drafts/" + draftId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    // 再生成一次 → 409，且响应体里带结构化冲突清单（不是一句提示语）。
    String rejected =
        http.perform(
                post("/catalog/variant-combination/spus/" + axes.spuId() + "/drafts")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content("{\"mode\":\"MATRIX\"}"))
            .andExpect(status().isConflict())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(rejected).contains("\"reasonCode\":\"ALREADY_RESERVED\"");
    assertThat(rejected).contains("\"conflictCount\":1");

    http.perform(
            delete("/catalog/variant-combination/spus/" + axes.spuId() + "/drafts/" + draftId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    // 未认证 → 不能是 200。
    int anonymous =
        http.perform(get("/catalog/variant-combination/overview")).andReturn().getResponse().getStatus();
    assertThat(anonymous).isNotEqualTo(200);
  }

  // ------------------------------------------------------------ 表结构断言

  @Test
  void combinationTablesAreRlsProtectedTenantScopedAndCarryTheUniqueConstraint() {
    var tables =
        List.of(
            "sku_matrix_draft",
            "spu_variant_combination",
            "spu_variant_combination_value",
            "sku_matrix_draft_item");
    var weak =
        sql.fetch(
            "select relname from pg_class join pg_namespace n on n.oid=relnamespace"
                + " where n.nspname='public' and relkind='r'"
                + " and relname in ('sku_matrix_draft','spu_variant_combination',"
                + "'spu_variant_combination_value','sku_matrix_draft_item')"
                + " and (not relrowsecurity or not relforcerowsecurity)");
    assertThat(weak).isEmpty();
    for (String table : tables) {
      var nullable =
          sql.fetchOne(
              "select is_nullable from information_schema.columns where table_name=? and column_name='tenant_id'",
              table);
      assertThat(nullable.get("is_nullable", String.class)).as(table).isEqualTo("NO");
    }

    // 蓝图硬要求的落点本体：(tenant_id, spu_id, signature) 唯一索引必须真实存在。
    var unique =
        sql.fetch(
            "select indexname from pg_indexes where tablename='spu_variant_combination'"
                + " and indexdef like '%UNIQUE%'");
    assertThat(unique).isNotEmpty();
    assertThat(
            sql.fetchOne(
                    "select count(*) from pg_constraint where conrelid='spu_variant_combination'::regclass"
                        + " and contype='u'"
                        + " and pg_get_constraintdef(oid) like '%(tenant_id, spu_id, signature)%'")
                .get(0, Long.class))
        .isEqualTo(1);

    // 一个组合里同一条轴只能取一个值：主键就是 (tenant_id, combination_id, spec_axis_id)。
    assertThat(
            sql.fetchOne(
                    "select pg_get_constraintdef(oid) from pg_constraint"
                        + " where conrelid='spu_variant_combination_value'::regclass"
                        + " and contype='p'")
                .get(0, String.class))
        .contains("spec_axis_id");
  }

  private static String between(String text, String prefix, String suffix) {
    int start = text.indexOf(prefix);
    if (start < 0) throw new IllegalStateException("prefix not found: " + text);
    start += prefix.length();
    int end = text.indexOf(suffix, start);
    if (end < 0) throw new IllegalStateException("suffix not found: " + text);
    return text.substring(start, end);
  }
}