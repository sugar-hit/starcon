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
import org.springframework.dao.DataIntegrityViolationException;
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
 * 3.3.1.2 类目属性要求：规则 CRUD、三种要求形态与取值约束、条件必填求值、
 * **表单 schema 生成**、**保存前校验**、继承语义，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CategoryAttributeRequirementIntegrationTest {
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
      // 只有属性读权限：写端点必须 403（规则沿用属性域权限）。
      actors.put(
          "Bearer reader",
          actor(
              user, a, null, List.of(grant("ALL", List.of("catalog.attribute.read", "catalog.spu.read")))));
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

  @Autowired CategoryTreeService categories;
  @Autowired AttributeDictionaryService dictionary;
  @Autowired CategoryAttributeRequirementService requirements;
  @Autowired CategoryAttributeRequirementForms forms;
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

  void writeVoid(AccessService.Access who, Runnable work) {
    access.run(
        who,
        "catalog.attribute.update",
        () -> {
          work.run();
          return null;
        });
  }

  // ------------------------------------------------------------ 夹具

  String uniqueCode(String hint) {
    return hint + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
  }

  UUID tree(String kind, String platformCode) {
    return write(
            actor,
            () ->
                categories.saveTree(
                    actor,
                    null,
                    new CategoryTreeInput(
                        uniqueCode(kind.equals("INTERNAL") ? "TREE" : "PLAT"),
                        "分类树",
                        kind,
                        platformCode,
                        true,
                        0)))
        .id();
  }

  UUID category(UUID treeId, UUID parentId, String name) {
    return write(
            actor,
            () ->
                categories.save(
                    actor,
                    treeId,
                    null,
                    new CategoryInput(uniqueCode("CAT"), name, parentId, null, true, List.of(), 0)))
        .id();
  }

  /** 建一个租户级字典属性（类目要求只能挂在字典条目上）。 */
  UUID attribute(String code, String name, String dataType, String... options) {
    return attributeFor(actor, code, name, dataType, options);
  }

  /** 3.2.1.1 的字典条目 + 3.2.1.2 的选项写入。枚举属性必须带选项一起创建（3.2.1.1 的硬校验）。 */
  UUID attributeFor(AccessService.Access who, String code, String name, String dataType, String... options) {
    if ("OPTION".equals(dataType) && options.length == 0)
      throw new IllegalArgumentException("枚举属性必须至少给一个选项：" + code);
    return write(
            who,
            () ->
                dictionary.save(
                    who,
                    null,
                    new AttributeDefinitionInput(
                        code, name, dataType, "COMMON_DEFAULT", "DESCRIPTION",
                        false, false, false, false, "", "基本参数", 0, true,
                        List.of(options), 0)))
        .id();
  }

  CategoryAttributeRequirementInput requirement(
      UUID attributeId, String requirementKind, int position) {
    return new CategoryAttributeRequirementInput(
        attributeId, requirementKind, null, null, null, false, position, List.of(), null, List.of(), true, 0);
  }

  CategoryAttributeRequirementSaved saveRequirement(
      UUID categoryId, CategoryAttributeRequirementInput input) {
    return write(actor, () -> requirements.save(actor, categoryId, input));
  }

  // ------------------------------------------------------------ 要求形态与取值约束

  @Test
  void rulesCarryRequirementShapeAndPerDataTypeConstraints() {
    UUID t = tree("INTERNAL", null);
    UUID c = category(t, null, "约束类目");
    UUID option = attribute("OPT" + UUID.randomUUID().toString().substring(0, 4), "枚举属性", "OPTION", "红", "蓝");
    UUID text = attribute("TXT" + UUID.randomUUID().toString().substring(0, 4), "文本属性", "TEXT");
    UUID number = attribute("NUM" + UUID.randomUUID().toString().substring(0, 4), "数值属性", "DECIMAL");
    UUID bool = attribute("BOL" + UUID.randomUUID().toString().substring(0, 4), "布尔属性", "BOOLEAN");

    // 枚举：白名单 + 位置
    saveRequirement(
        c,
        new CategoryAttributeRequirementInput(
            option, "REQUIRED", null, null, null, false, 10, List.of("红"), null, List.of(), true, 0));
    // 文本：长度上限
    saveRequirement(
        c,
        new CategoryAttributeRequirementInput(
            text, "OPTIONAL", null, null, 20, false, 20, List.of(), null, List.of(), true, 0));
    // 数值：区间（BigDecimal，不走浮点）
    saveRequirement(
        c,
        new CategoryAttributeRequirementInput(
            number, "REQUIRED", new java.math.BigDecimal("0.01"), new java.math.BigDecimal("99.99"),
            null, false, 30, List.of(), null, List.of(), true, 0));

    var rules = tx(actor, () -> requirements.effectiveRequirements(c));
    assertThat(rules).hasSize(3);

    var optionRule = ruleOf(rules, option);
    assertThat(optionRule.requirement()).isEqualTo("REQUIRED");
    assertThat(optionRule.allowedOptions()).containsExactly("红");
    assertThat(optionRule.position()).isEqualTo(10);

    var textRule = ruleOf(rules, text);
    assertThat(textRule.requirement()).isEqualTo("OPTIONAL");
    assertThat(textRule.maxLength()).isEqualTo(20);

    var numberRule = ruleOf(rules, number);
    assertThat(numberRule.minValue()).isEqualByComparingTo("0.01");
    assertThat(numberRule.maxValue()).isEqualByComparingTo("99.99");

    // 给错数据类型配形态必须被拒（否则是永远匹配不上的死约束）。
    assertThatThrownBy(
            () ->
                saveRequirement(
                    c,
                    new CategoryAttributeRequirementInput(
                        bool, "REQUIRED", java.math.BigDecimal.ONE, null, null, false, 40,
                        List.of(), null, List.of(), true, 0)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(message(e)).contains("数值属性"));
    // 文本属性配长度以外的选项集同样被拒。
    assertThatThrownBy(
            () ->
                saveRequirement(
                    c,
                    new CategoryAttributeRequirementInput(
                        text, "OPTIONAL", null, null, null, false, 50, List.of("红"),
                        null, List.of(), true, 0)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(message(e)).contains("枚举属性"));
    // 「允许自由填写」与「限定允许选项」互相矛盾。
    assertThatThrownBy(
            () ->
                saveRequirement(
                    c,
                    new CategoryAttributeRequirementInput(
                        option, "OPTIONAL", null, null, null, true, 60, List.of("红"),
                        null, List.of(), true, 0)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(message(e)).contains("互相矛盾"));
    // 区间反了（下限 > 上限）。
    assertThatThrownBy(
            () ->
                saveRequirement(
                    c,
                    new CategoryAttributeRequirementInput(
                        number, "REQUIRED", new java.math.BigDecimal("10"), new java.math.BigDecimal("1"),
                        null, false, 70, List.of(), null, List.of(), true, 0)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(message(e)).contains("下限不能大于上限"));
  }

  // ------------------------------------------------------------ 条件必填：真正求值

  @Test
  void conditionalRequiredIsOnlyEnforcedWhenTheConditionHolds() {
    UUID t = tree("INTERNAL", null);
    UUID c = category(t, null, "条件类目");
    UUID color = attribute("CLR" + UUID.randomUUID().toString().substring(0, 4), "颜色", "OPTION", "红色", "蓝色");
    UUID warranty = attribute("WAR" + UUID.randomUUID().toString().substring(0, 4), "保修期", "OPTION", "一年", "两年");

    saveRequirement(c, requirement(color, "REQUIRED", 10));

    // 空取值集合必须被拒：条件恒不成立 = 要求等于没配。
    assertThatThrownBy(
            () ->
                saveRequirement(
                    c,
                    new CategoryAttributeRequirementInput(
                        warranty, "CONDITIONAL_REQUIRED", null, null, null, false, 20, List.of(),
                        new CategoryAttributeCondition(color, null, null, null, "IN"), List.of(), true, 0)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(message(e)).contains("至少一个条件取值"));

    // 自引用被拒。
    assertThatThrownBy(
            () ->
                saveRequirement(
                    c,
                    new CategoryAttributeRequirementInput(
                        warranty, "CONDITIONAL_REQUIRED", null, null, null, false, 20, List.of(),
                        new CategoryAttributeCondition(warranty, null, null, null, "IN"),
                        List.of(new CategoryAttributeConditionValue("一年", 0)), true, 0)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(message(e)).contains("不能依赖属性自己"));

    // 条件取值不在前置属性的值源里 —— 条件永远不成立，必须在配置侧就拒。
    assertThatThrownBy(
            () ->
                saveRequirement(
                    c,
                    new CategoryAttributeRequirementInput(
                        warranty, "CONDITIONAL_REQUIRED", null, null, null, false, 20, List.of(),
                        new CategoryAttributeCondition(color, null, null, null, "IN"),
                        List.of(new CategoryAttributeConditionValue("绿色", 0)), true, 0)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(message(e)).contains("不在属性"));

    // 排序类算子用在枚举属性上 —— 不可枚举，必须拒。
    assertThatThrownBy(
            () ->
                saveRequirement(
                    c,
                    new CategoryAttributeRequirementInput(
                        warranty, "CONDITIONAL_REQUIRED", null, null, null, false, 20, List.of(),
                        new CategoryAttributeCondition(color, null, null, null, "GT"),
                        List.of(new CategoryAttributeConditionValue("红色", 0)), true, 0)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(message(e)).contains("没有大小关系"));

    // 合法配置：颜色为红色时保修期必填。
    saveRequirement(
        c,
        new CategoryAttributeRequirementInput(
            warranty, "CONDITIONAL_REQUIRED", null, null, null, false, 20, List.of(),
            new CategoryAttributeCondition(color, null, null, null, "IN"),
            List.of(new CategoryAttributeConditionValue("红色", 0)), true, 0));

    // 条件成立 + 未填 → CONDITION_UNMET
    var unmet =
        tx(actor, () -> forms.validate(c, new AttributeValidationInput(List.of(
            new AttributeValueInput(color, null, "红色")))));
    assertThat(unmet.valid()).isFalse();
    assertThat(unmet.results()).filteredOn(r -> r.attributeId().equals(warranty))
        .singleElement()
        .satisfies(r -> {
          assertThat(r.code()).isEqualTo("CONDITION_UNMET");
          assertThat(r.message()).contains("红色");
        });

    // 条件不成立（蓝色）→ 可选，不填也通过
    var notTriggered =
        tx(actor, () -> forms.validate(c, new AttributeValidationInput(List.of(
            new AttributeValueInput(color, null, "蓝色")))));
    assertThat(notTriggered.valid()).isTrue();

    // 前置属性没填 → 条件未知，按「未触发」处理，不逼用户先填前置字段
    var unknown =
        tx(actor, () -> forms.validate(c, new AttributeValidationInput(List.of())));
    assertThat(unknown.valid()).isFalse(); // 颜色本身是 REQUIRED，仍然缺
    assertThat(unknown.results()).filteredOn(r -> r.attributeId().equals(warranty))
        .singleElement()
        .satisfies(r -> assertThat(r.code()).isEqualTo("PASSED"));

    // 条件成立 + 已填 → 通过
    var met =
        tx(actor, () -> forms.validate(c, new AttributeValidationInput(List.of(
            new AttributeValueInput(color, null, "红色"),
            new AttributeValueInput(warranty, null, "两年")))));
    assertThat(met.valid()).isTrue();
  }

  // ------------------------------------------------------------ 表单 schema 生成

  @Test
  void entryFormSchemaOrdersFieldsAndEvaluatesConditionalRules() {
    UUID t = tree("INTERNAL", null);
    UUID c = category(t, null, "表单类目");
    UUID color = attribute("CLR" + UUID.randomUUID().toString().substring(0, 4), "颜色", "OPTION", "红色", "蓝色");
    UUID warranty = attribute("WAR" + UUID.randomUUID().toString().substring(0, 4), "保修期", "OPTION", "一年", "两年");
    UUID weight = attribute("WGT" + UUID.randomUUID().toString().substring(0, 4), "克重", "DECIMAL");
    UUID note = attribute("NTE" + UUID.randomUUID().toString().substring(0, 4), "备注", "TEXT");

    saveRequirement(c, requirement(color, "REQUIRED", 30));
    // 条件字段故意排在 position 更小处：依赖顺序必须由拓扑保证，而不是 position。
    saveRequirement(
        c,
        new CategoryAttributeRequirementInput(
            warranty, "CONDITIONAL_REQUIRED", null, null, null, false, 10, List.of(),
            new CategoryAttributeCondition(color, null, null, null, "IN"),
            List.of(new CategoryAttributeConditionValue("红色", 0)), true, 0));
    saveRequirement(
        c,
        new CategoryAttributeRequirementInput(
            weight, "REQUIRED", null, new java.math.BigDecimal("500"), null, false, 20,
            List.of(), null, List.of(), true, 0));
    saveRequirement(
        c,
        new CategoryAttributeRequirementInput(
            note, "OPTIONAL", null, null, 30, false, 40, List.of(), null, List.of(), true, 0));

    // 未填任何值：条件字段不亮必填，但前置字段必须排在它前面。
    var blank = tx(actor, () -> forms.schema(c, Map.of(), false));
    assertThat(blank.fieldCount()).isEqualTo(4);
    var codes = blank.fields().stream().map(EntryFormField::key).toList();
    assertThat(codes.indexOf(fieldKey(blank, color))).isLessThan(codes.indexOf(fieldKey(blank, warranty)));
    var warrantyField = blank.fields().stream().filter(f -> f.attributeId().equals(warranty)).findFirst().orElseThrow();
    assertThat(warrantyField.required()).isFalse();
    assertThat(warrantyField.dependency()).isNotNull();
    assertThat(warrantyField.dependency().active()).isFalse();
    assertThat(warrantyField.control()).isEqualTo("SELECT");

    // 传入当前值：条件成立，字段立刻变必填（条件真正参与 schema 生成）。
    var filled =
        tx(actor, () -> forms.schema(c, Map.of(fieldKey(blank, color), "红色"), false));
    var warrantyFilled = filled.fields().stream().filter(f -> f.attributeId().equals(warranty)).findFirst().orElseThrow();
    assertThat(warrantyFilled.required()).isTrue();
    assertThat(warrantyFilled.dependency().active()).isTrue();
    assertThat(filled.requiredFieldCount()).isEqualTo(3);

    // 控件类型按数据类型推导；约束随字段下发。
    assertThat(fieldOf(filled, color).control()).isEqualTo("SELECT");
    assertThat(fieldOf(filled, weight).control()).isEqualTo("NUMBER");
    assertThat(fieldOf(filled, weight).constraints().maxValue()).isEqualByComparingTo("500");
    assertThat(fieldOf(filled, note).control()).isEqualTo("TEXT");
    assertThat(fieldOf(filled, note).constraints().maxLength()).isEqualTo(30);
    // 枚举字段带选项列表。
    assertThat(fieldOf(filled, color).options()).extracting(EntryFormOption::value)
        .containsExactlyInAnyOrder("红色", "蓝色");
    assertThat(blank.note()).isNotBlank();
  }

  // ------------------------------------------------------------ 保存前校验：逐字段结果

  @Test
  void validationReportsPerFieldProblems() {
    UUID t = tree("INTERNAL", null);
    UUID c = category(t, null, "校验类目");
    UUID color = attribute("CLR" + UUID.randomUUID().toString().substring(0, 4), "颜色", "OPTION", "红色", "蓝色");
    UUID weight = attribute("WGT" + UUID.randomUUID().toString().substring(0, 4), "克重", "DECIMAL");
    UUID note = attribute("NTE" + UUID.randomUUID().toString().substring(0, 4), "备注", "TEXT");
    UUID flag = attribute("BOL" + UUID.randomUUID().toString().substring(0, 4), "是否进口", "BOOLEAN");

    saveRequirement(c, requirement(color, "REQUIRED", 10));
    saveRequirement(
        c,
        new CategoryAttributeRequirementInput(
            weight, "REQUIRED", new java.math.BigDecimal("1"), new java.math.BigDecimal("100"),
            null, false, 20, List.of(), null, List.of(), true, 0));
    saveRequirement(
        c,
        new CategoryAttributeRequirementInput(
            note, "OPTIONAL", null, null, 5, false, 30, List.of(), null, List.of(), true, 0));
    saveRequirement(c, requirement(flag, "OPTIONAL", 40));
    // 把颜色规则收窄成白名单「红色」，用来测 OPTION_NOT_ALLOWED。
    saveRequirement(
        c,
        new CategoryAttributeRequirementInput(
            color, "REQUIRED", null, null, null, false, 10, List.of("红色"),
            null, List.of(), true, 0));

    var report =
        tx(
            actor,
            () ->
                forms.validate(
                    c,
                    new AttributeValidationInput(
                        List.of(
                            new AttributeValueInput(null, fieldKeyOf(color), "蓝色"),
                            new AttributeValueInput(weight, null, "500"),
                            new AttributeValueInput(note, null, "太长了太长了"),
                            new AttributeValueInput(flag, null, "1")))));

    assertThat(report.valid()).isFalse();
    assertThat(report.failedCount()).isEqualTo(4);
    assertThat(resultOf(report, color).code()).isEqualTo("OPTION_NOT_ALLOWED");
    assertThat(resultOf(report, weight).code()).isEqualTo("OUT_OF_RANGE");
    assertThat(resultOf(report, note).code()).isEqualTo("LENGTH_EXCEEDED");
    // 布尔属性只接受 true/false 字面量：把 1 当成 true 会让「没勾选但值是 1」静默通过。
    assertThat(resultOf(report, flag).code()).isEqualTo("TYPE_MISMATCH");

    // 缺失：把可选项去掉再校验必填项缺失。
    var missing =
        tx(actor, () -> forms.validate(c, new AttributeValidationInput(List.of())));
    assertThat(missing.valid()).isFalse();
    assertThat(resultOf(missing, weight).code()).isEqualTo("MISSING");
    assertThat(resultOf(missing, note).code()).isEqualTo("PASSED"); // 可选不填合法

    // 提交了本类目没有要求的属性 → 明确报错，不静默丢弃。
    UUID unrelated = attribute("UNR" + UUID.randomUUID().toString().substring(0, 4), "无关属性", "TEXT");
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () ->
                        forms.validate(
                            c,
                            new AttributeValidationInput(
                                List.of(new AttributeValueInput(unrelated, null, "x"))))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(message(e)).contains("没有生效的属性要求"));

    // 类型不符：数值属性提交非数值。
    var typeProblem =
        tx(
            actor,
            () ->
                forms.validate(
                    c,
                    new AttributeValidationInput(
                        List.of(new AttributeValueInput(weight, null, "很重")))));
    assertThat(resultOf(typeProblem, weight).code()).isEqualTo("TYPE_MISMATCH");
    assertThat(report.note()).isNotBlank();
  }

  // ------------------------------------------------------------ 继承语义

  @Test
  void requirementsInheritFromAncestorsWithNearestWinsOverride() {
    UUID t = tree("INTERNAL", null);
    UUID parent = category(t, null, "上装");
    UUID child = category(t, parent, "T恤");
    UUID grandchild = category(t, child, "纯棉T恤");
    UUID color = attribute("CLR" + UUID.randomUUID().toString().substring(0, 4), "颜色", "OPTION", "红色", "蓝色");
    UUID size = attribute("SIZ" + UUID.randomUUID().toString().substring(0, 4), "尺码", "OPTION", "S", "M");
    UUID brand = attribute("BRD" + UUID.randomUUID().toString().substring(0, 4), "品牌", "TEXT");

    // 上装要求颜色（必填）与尺码（可选）
    saveRequirement(parent, requirement(color, "REQUIRED", 10));
    saveRequirement(parent, requirement(size, "OPTIONAL", 20));
    // 上装要求品牌；T恤 把它改成必填（就近覆盖）
    saveRequirement(parent, requirement(brand, "OPTIONAL", 30));
    saveRequirement(child, requirement(brand, "REQUIRED", 30));

    // 子类目没有自己的规则时，继承父类的全部生效要求。
    var inherited = tx(actor, () -> requirements.effectiveRequirements(child));
    assertThat(inherited).hasSize(3);
    // 上装是 T恤 的父节点，在祖先链 [上装, T恤] 上的下标是 0 —— 「继承来的」不等于「深度 > 0」。
    assertThat(ruleOf(inherited, color).depth()).isZero();
    assertThat(ruleOf(inherited, color).inherited()).isTrue();
    // T恤 自己配的品牌是链上的最后一层，不算继承。
    assertThat(ruleOf(inherited, brand).inherited()).isFalse();
    assertThat(ruleOf(inherited, brand).depth()).isEqualTo(1);
    assertThat(inherited).extracting(CategoryAttributeRequirement::attributeId).contains(color, size, brand);
    // 就近覆盖：品牌在上装是可选，在 T恤 是必填 —— 生效的是后者。
    assertThat(ruleOf(inherited, brand).requirement()).isEqualTo("REQUIRED");
    assertThat(ruleOf(inherited, brand).sourceCategoryId()).isEqualTo(child);

    // 孙类目同时看到「上装的颜色」与「T恤的品牌」，且来源层级可区分。
    var deep = tx(actor, () -> requirements.effectiveRequirements(grandchild));
    assertThat(deep).hasSize(3);
    assertThat(deep).allMatch(CategoryAttributeRequirement::inherited);
    // 链是 [上装, T恤, 纯棉T恤]：上装 0、T恤 1，层数可直接看出隔了几层继承下来。
    assertThat(ruleOf(deep, color).depth()).isZero();
    assertThat(ruleOf(deep, brand).depth()).isEqualTo(1);

    // 停用本节点的要求 = 豁免祖先的要求（属性整体不进入有效集，不是降级为可选）。
    writeVoid(actor, () -> requirements.setEnabled(actor, child, brand, false));
    var afterDisable = tx(actor, () -> requirements.effectiveRequirements(child));
    assertThat(afterDisable).extracting(CategoryAttributeRequirement::attributeId).doesNotContain(brand);
    assertThat(afterDisable).extracting(CategoryAttributeRequirement::attributeId).contains(color, size);
    // 再启用即恢复。
    writeVoid(actor, () -> requirements.setEnabled(actor, child, brand, true));
    assertThat(tx(actor, () -> requirements.effectiveRequirements(child)))
        .extracting(CategoryAttributeRequirement::attributeId).contains(brand);

    // 删除本节点的要求 → 回退到祖先链上的上一条（而不是变成无要求）。
    writeVoid(actor, () -> requirements.delete(actor, child, brand));
    var afterDelete = tx(actor, () -> requirements.effectiveRequirements(child));
    assertThat(afterDelete).hasSize(3);
    assertThat(ruleOf(afterDelete, brand).requirement()).isEqualTo("OPTIONAL");
    assertThat(ruleOf(afterDelete, brand).sourceCategoryId()).isEqualTo(parent);
    assertThat(ruleOf(afterDelete, brand).depth()).isZero();
    assertThat(ruleOf(afterDelete, brand).inherited()).isTrue();

    // 本节点视图不含继承来的规则。
    var own = tx(actor, () -> requirements.ownRequirements(child));
    assertThat(own).extracting(CategoryAttributeRequirement::attributeId).doesNotContain(color, size);
    var view = tx(actor, () -> requirements.view(child));
    assertThat(view.summary().ownCount()).isEqualTo(own.size());
    assertThat(view.summary().effectiveCount()).isEqualTo(3);
    assertThat(view.categoryPath()).contains("T恤");
  }

  @Test
  void disablingTheNearestRuleExemptsTheCategoryFromAnAncestorRequirement() {
    UUID t = tree("INTERNAL", null);
    UUID parent = category(t, null, "父类目");
    UUID child = category(t, parent, "子类目");
    UUID color = attribute("CLR" + UUID.randomUUID().toString().substring(0, 4), "颜色", "OPTION", "红色", "蓝色");

    // 父类目要求颜色，子类目没有任何规则：先确认它确实继承到了。
    saveRequirement(parent, requirement(color, "REQUIRED", 10));
    assertThat(tx(actor, () -> requirements.effectiveRequirements(child)))
        .extracting(CategoryAttributeRequirement::attributeId).contains(color);

    // 子类目在**本节点**加一条同名规则并停用：这是「我不需要这个属性」的唯一表达方式。
    // 关键在于停用的必须是「最近的那条」——若先过滤 enabled 再取最近，
    // 这条停用规则会被跳过，要求回退到父类目的 REQUIRED，豁免就失效了。
    saveRequirement(child, requirement(color, "REQUIRED", 10));
    writeVoid(actor, () -> requirements.setEnabled(actor, child, color, false));
    var exempted = tx(actor, () -> requirements.effectiveRequirements(child));
    assertThat(exempted).isEmpty();
    // 父类目自己不受影响。
    assertThat(tx(actor, () -> requirements.effectiveRequirements(parent)))
        .extracting(CategoryAttributeRequirement::attributeId).contains(color);
    // 停用的规则仍留在配置视图里（可再启用），且表单不会因此长出必填星号。
    var own = tx(actor, () -> requirements.ownRequirements(child));
    assertThat(own).singleElement().satisfies(r -> assertThat(r.enabled()).isFalse());
    assertThat(tx(actor, () -> forms.schema(child, Map.of(), false)).fieldCount()).isZero();
    var withDisabled = tx(actor, () -> forms.schema(child, Map.of(), true));
    assertThat(withDisabled.fieldCount()).isEqualTo(1);
    assertThat(withDisabled.fields().get(0).required()).isFalse();

    // 重新启用即恢复继承（虽然那条规则本身也会生效，但要求重新存在了）。
    writeVoid(actor, () -> requirements.setEnabled(actor, child, color, true));
    assertThat(tx(actor, () -> requirements.effectiveRequirements(child)))
        .extracting(CategoryAttributeRequirement::attributeId).contains(color);
  }

  // ------------------------------------------------------------ 冲突与幂等

  @Test
  void duplicateConfigurationIsRejectedByTheCategoryScopedUniqueKey() {
    UUID t = tree("INTERNAL", null);
    UUID c = category(t, null, "冲突类目");
    UUID color = attribute("CLR" + UUID.randomUUID().toString().substring(0, 4), "颜色", "OPTION", "红色");

    saveRequirement(c, requirement(color, "REQUIRED", 10));
    // 再次提交是「改这条要求」，走 upsert，不会留下两条。
    var second = saveRequirement(c, requirement(color, "OPTIONAL", 20));
    assertThat(second.requirement()).isEqualTo("OPTIONAL");
    assertThat(tx(actor, () -> requirements.effectiveRequirements(c))).hasSize(1);

    // DB 层的唯一索引在 (category_id, attribute_id) 上：绕过服务层也写不进第二条。
    var duplicates =
        tx(actor, () -> sql.fetchOne(
            "select count(*) from category_attribute_rule where category_id=? and attribute_id=?",
            c, color));
    assertThat(duplicates.get(0, Long.class)).isEqualTo(1);

    // 乐观锁：版本不匹配必须被拒。
    assertThatThrownBy(
            () ->
                saveRequirement(
                    c,
                    new CategoryAttributeRequirementInput(
                        color, "REQUIRED", null, null, null, false, 30, List.of(), null, List.of(),
                        true, 999)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(message(e)).contains("已被他人修改"));
  }

  // ------------------------------------------------------------ 平台类目边界

  @Test
  void platformTreeIsRejectedForFormGenerationAndValidation() {
    UUID amazon = tree("PLATFORM", "AMAZON");
    UUID node = category(amazon, null, "Amazon 类目");
    // 规则本身仍可配置（平台树节点由 04 维护映射），但表单生成 / 校验只服务内部类目。
    UUID color = attribute("CLR" + UUID.randomUUID().toString().substring(0, 4), "颜色", "OPTION", "红色");
    saveRequirement(node, requirement(color, "REQUIRED", 10));
    assertThatThrownBy(() -> tx(actor, () -> forms.schema(node, Map.of(), false)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(message(e)).contains("内部管理分类"));
    assertThatThrownBy(
            () -> tx(actor, () -> forms.validate(node, new AttributeValidationInput(List.of()))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(message(e)).contains("内部管理分类"));
  }

  // ------------------------------------------------------------ 与已交付模块的关系

  @Test
  void legacyAttributeDictionaryRulePathKeepsWorkingAndStaysSynced() {
    UUID t = tree("INTERNAL", null);
    // 3.2.1.1 的类目规则端点仍走「类目文本」，且不写 requirement —— V15 的触发器负责派生。
    // 这里故意用一个新的自由文本类目名：它会由 resolveOrCreateCategoryId 在内部树上落一个类目行，
    // 这正是 V14 留下的口子在 V15 之后仍然成立的证明。
    String legacyCategory = "兼容类目-" + UUID.randomUUID().toString().substring(0, 6);
    UUID color = attribute("CLR" + UUID.randomUUID().toString().substring(0, 4), "颜色", "OPTION", "红色");

    var legacy =
        write(
                actor,
                () ->
                    dictionary.saveCategoryRule(
                        actor,
                        color,
                        new CategoryAttributeRuleInput(
                            legacyCategory, true, null, null, 5, List.of("红色"))));
    assertThat(legacy.allowedOptionCount()).isEqualTo(1);

    // 派生后的权威列：REQUIRED，且与已交付的 required 布尔一致。
    // 直查 SQL 必须放在 tx(...) 里：连接池归还后 app.current_tenant 已清空，
    // 在外面查会被 RLS 静默过滤掉（查不到 ≠ 不存在，这是多租户表最容易误判的一点）。
    UUID resolvedCategory = write(actor, () -> categories.resolveOrCreateCategoryId(actor, legacyCategory));
    var derived =
        tx(actor, () -> sql.fetchOne(
            "select requirement,required from category_attribute_rule where category_id=? and attribute_id=?",
            resolvedCategory, color));
    assertThat(derived.get("requirement", String.class)).isEqualTo("REQUIRED");
    assertThat(derived.get("required", Boolean.class)).isTrue();

    // requirement 是权威列、required 是派生列：无论从哪一侧写，两列都必须一致。
    // 写 required 改不动 requirement（否则两列会各说各话，3.2.1.1 的页面与本模块的表单
    // 会给出不同的必填结论）；写 requirement 则一定同步出对应的 required。
    writeVoid(actor, () -> sql.execute(
        "update category_attribute_rule set required=false where category_id=? and attribute_id=?",
        resolvedCategory, color));
    var stillRequired =
        tx(actor, () -> sql.fetchOne(
            "select requirement,required from category_attribute_rule where category_id=? and attribute_id=?",
            resolvedCategory, color));
    assertThat(stillRequired.get("required", Boolean.class)).isTrue();
    assertThat(stillRequired.get("requirement", String.class)).isEqualTo("REQUIRED");

    writeVoid(actor, () -> sql.execute(
        "update category_attribute_rule set requirement='OPTIONAL'"
            + " where category_id=? and attribute_id=?", resolvedCategory, color));
    var optional =
        tx(actor, () -> sql.fetchOne(
            "select requirement,required from category_attribute_rule where category_id=? and attribute_id=?",
            resolvedCategory, color));
    assertThat(optional.get("requirement", String.class)).isEqualTo("OPTIONAL");
    assertThat(optional.get("required", Boolean.class)).isFalse();
    // 条件必填派生为 required=false：3.2.2.2 的 SKU 属性覆写据此不会把合法录入误挡。
    // （不能直接把 requirement 改成 CONDITIONAL_REQUIRED：V15 的 CHECK 要求条件成套存在，
    //   这正是「死配置写不进来」的保证。）
    assertThatThrownBy(
            () ->
                writeVoid(
                    actor,
                    () ->
                        sql.execute(
                            "update category_attribute_rule set requirement='CONDITIONAL_REQUIRED'"
                                + " where category_id=? and attribute_id=?",
                            resolvedCategory, color)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // ------------------------------------------------------------ 多租户三断言

  @Test
  void tenantIsolationHoldsAcrossReadWriteAndPoolReuse() throws Exception {
    UUID t = tree("INTERNAL", null);
    UUID mine = category(t, null, "A 租户类目");
    UUID color = attribute("CLR" + UUID.randomUUID().toString().substring(0, 4), "颜色", "OPTION", "红色");
    saveRequirement(mine, requirement(color, "REQUIRED", 10));

    UUID theirTree = write(actorB, () -> categories.saveTree(actorB, null,
        new CategoryTreeInput(uniqueCode("THEIRS"), "B 的树", "INTERNAL", null, true, 0))).id();
    UUID theirs = write(actorB, () -> categories.save(actorB, theirTree, null,
        new CategoryInput(uniqueCode("CAT"), "B 的类目", null, null, true, List.of(), 0))).id();
    UUID theirColor = attributeFor(actorB, "CLR" + UUID.randomUUID().toString().substring(0, 4), "B 的颜色", "OPTION", "红色");
    write(actorB, () -> requirements.save(actorB, theirs,
        new CategoryAttributeRequirementInput(theirColor, "REQUIRED", null, null, null, false, 10,
            List.of(), null, List.of(), true, 0)));

    // 1) 两租户互不可见
    assertThat(tx(actor, () -> requirements.effectiveRequirements(mine)))
        .extracting(CategoryAttributeRequirement::attributeId).contains(color);
    assertThat(tx(actorB, () -> requirements.effectiveRequirements(theirs)))
        .extracting(CategoryAttributeRequirement::attributeId).contains(theirColor).doesNotContain(color);
    assertThatThrownBy(() -> tx(actorB, () -> requirements.effectiveRequirements(mine)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> requirements.effectiveRequirements(theirs)))
        .isInstanceOf(ResponseStatusException.class);
    // 跨租户写入也不该污染：B 租户不能把规则挂到 A 租户的类目上。
    assertThatThrownBy(() -> write(actorB, () -> requirements.save(actorB, mine,
        new CategoryAttributeRequirementInput(theirColor, "REQUIRED", null, null, null, false, 10,
            List.of(), null, List.of(), true, 0))))
        .isInstanceOf(ResponseStatusException.class);
    // A 的数据仍在
    assertThat(tx(actor, () -> requirements.effectiveRequirements(mine)))
        .extracting(CategoryAttributeRequirement::attributeId).contains(color);

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(
            get("/catalog/categories/attribute-requirements/" + mine)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/categories/attribute-requirements/overview")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());
    // 伪造头也不能借道写
    http.perform(
            post("/catalog/categories/attribute-requirements/" + mine)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content(
                    "{\"attributeId\":\"" + theirColor + "\",\"requirement\":\"REQUIRED\",\"position\":0,\"enabled\":true}"))
        .andExpect(status().isNotFound());
    assertThat(tx(actor, () -> requirements.effectiveRequirements(mine)))
        .extracting(CategoryAttributeRequirement::attributeId).doesNotContain(theirColor);

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> requirements.effectiveRequirements(mine)))
          .extracting(CategoryAttributeRequirement::attributeId).contains(color);
      assertThat(tx(actorB, () -> requirements.effectiveRequirements(theirs)))
          .extracting(CategoryAttributeRequirement::attributeId).contains(theirColor).doesNotContain(color);
      assertThatThrownBy(() -> tx(actorB, () -> requirements.effectiveRequirements(mine)))
          .isInstanceOf(ResponseStatusException.class);
      // 表单生成路径同样不能串（它是另一个 service，值查询走不同 SQL）。
      assertThat(tx(actor, () -> forms.schema(mine, Map.of(), false)).fieldCount()).isEqualTo(1);
      assertThat(tx(actorB, () -> forms.schema(theirs, Map.of(), false)).fieldCount()).isEqualTo(1);
    }
  }

  // ------------------------------------------------------------ HTTP 与权限

  @Test
  void httpEndpointsExposeTheRealWorkflow() throws Exception {
    UUID t = tree("INTERNAL", null);
    UUID c = category(t, null, "HTTP 类目");
    UUID color = attribute("CLR" + UUID.randomUUID().toString().substring(0, 4), "颜色", "OPTION", "红色");
    UUID warranty = attribute("WAR" + UUID.randomUUID().toString().substring(0, 4), "保修期", "OPTION", "一年");

    http.perform(
            post("/catalog/categories/attribute-requirements/" + c)
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"attributeId\":\"" + color + "\",\"requirement\":\"REQUIRED\",\"position\":10,\"enabled\":true}"))
        .andExpect(status().isOk());
    http.perform(
            post("/catalog/categories/attribute-requirements/" + c)
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"attributeId\":\"" + warranty + "\",\"requirement\":\"CONDITIONAL_REQUIRED\",\"position\":20,"
                        + "\"enabled\":true,\"condition\":{\"attributeId\":\"" + color + "\",\"operator\":\"IN\"},"
                        + "\"conditionValues\":[{\"value\":\"红色\",\"position\":0}]}"))
        .andExpect(status().isOk());

    http.perform(
            get("/catalog/categories/attribute-requirements/" + c + "/effective")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("CONDITIONAL_REQUIRED")));

    // 表单 schema：带当前值时条件字段必填。
    http.perform(
            post("/catalog/categories/attribute-requirements/" + c + "/entry-form-schema")
                .header("Authorization", "Bearer tenant-a")
                .param("values", "颜色:红色"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("\"required\":true")));
    http.perform(
            post("/catalog/categories/attribute-requirements/" + c + "/entry-form-schema")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("\"required\":false")));

    http.perform(
            post("/catalog/categories/attribute-requirements/" + c + "/validate")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"values\":[{\"attributeCode\":\"" + attributeCode(color) + "\",\"value\":\"红色\"}]}"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("CONDITION_UNMET")));

    http.perform(get("/catalog/categories/attribute-requirements/overview")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    // 只读身份：读端点放行、写端点 403（规则沿用属性域权限）。
    http.perform(get("/catalog/categories/attribute-requirements/" + c)
            .header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(
            post("/catalog/categories/attribute-requirements/" + c)
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    "{\"attributeId\":\"" + color + "\",\"requirement\":\"REQUIRED\",\"position\":10,\"enabled\":true}"))
        .andExpect(status().isForbidden());

    // 匿名不可读。
    int anonymous =
        http.perform(get("/catalog/categories/attribute-requirements/overview"))
            .andReturn().getResponse().getStatus();
    assertThat(anonymous).isNotEqualTo(200);
  }

  // ------------------------------------------------------------ 表结构断言

  @Test
  void requirementTablesAreRlsProtectedAndTenantScoped() {
    var weak =
        tx(actor, () -> sql.fetch(
            "select relname from pg_class join pg_namespace n on n.oid=relnamespace"
                + " where n.nspname='public' and relkind='r'"
                + " and relname in ('category_attribute_rule','category_attribute_condition_value')"
                + " and (not relrowsecurity or not relforcerowsecurity)"));
    assertThat(weak).isEmpty();
    for (String table : List.of("category_attribute_rule", "category_attribute_condition_value")) {
      var nullable =
          sql.fetchOne(
              "select is_nullable from information_schema.columns"
                  + " where table_name=? and column_name='tenant_id'",
              table);
      assertThat(nullable.get("is_nullable", String.class)).as(table).isEqualTo("NO");
    }
    // V15 的权威列必须 NOT NULL（requirement 是三种要求形态的唯一口径）。
    var requirement =
        sql.fetchOne(
            "select is_nullable from information_schema.columns"
                + " where table_name='category_attribute_rule' and column_name='requirement'");
    assertThat(requirement.get("is_nullable", String.class)).isEqualTo("NO");
    // 类目作用域唯一索引必须在（否则同一类目同一属性能配两条）。
    var index =
        sql.fetchOne(
            "select count(*) from pg_index i join pg_class c on c.oid=i.indexrelid"
                + " where c.relname='category_attribute_rule_category_id_key'");
    assertThat(index.get(0, Long.class)).isEqualTo(1);
  }

  // ------------------------------------------------------------ 辅助

  String attributeCode(UUID id) {
    return tx(actor, () -> sql.fetchOne("select code from attribute_definition where id=?", id)
        .get("code", String.class));
  }

  static CategoryAttributeRequirement ruleOf(List<CategoryAttributeRequirement> rules, UUID attributeId) {
    return rules.stream().filter(r -> r.attributeId().equals(attributeId)).findFirst()
        .orElseThrow(() -> new AssertionError("no rule for " + attributeId));
  }

  static String fieldKey(EntryFormSchema schema, UUID attributeId) {
    return schema.fields().stream().filter(f -> f.attributeId().equals(attributeId)).findFirst()
        .orElseThrow().key();
  }

  static EntryFormField fieldOf(EntryFormSchema schema, UUID attributeId) {
    return schema.fields().stream().filter(f -> f.attributeId().equals(attributeId)).findFirst()
        .orElseThrow();
  }

  static AttributeValidationResult resultOf(AttributeValidationReport report, UUID attributeId) {
    return report.results().stream().filter(r -> r.attributeId().equals(attributeId)).findFirst()
        .orElseThrow(() -> new AssertionError("no result for " + attributeId));
  }

  String fieldKeyOf(UUID attributeId) {
    return attributeCode(attributeId);
  }

  static String message(Throwable e) {
    return e instanceof ResponseStatusException rse
        ? String.valueOf(rse.getReason())
        : String.valueOf(e.getMessage());
  }
}