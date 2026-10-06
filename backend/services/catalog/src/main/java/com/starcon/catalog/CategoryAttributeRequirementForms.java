package com.starcon.catalog;

import static com.starcon.catalog.CategoryAttributeRequirementRules.*;
import static com.starcon.catalog.CatalogModels.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.3.1.2 的两个输出：**录入表单 schema 生成**与**保存前校验**。
 *
 * <p>与 {@link CategoryAttributeRequirementService} 分开成两个类，因为两者的职责性质不同：
 * 配置侧是「按类目维护规则」，输出侧是「按类目 + 按当前填写值现算」。前者关心写入冲突与继承，
 * 后者关心求值与顺序。混在一个类里会让「改一条规则」和「渲染一个表单」互相牵连。
 *
 * <p>本类的两条硬纪律：
 *
 * <ol>
 *   <li><b>条件必填必须真正求值，且与校验共用同一个判定</b>。
 *       schema 里的 {@code required} 与 {@code dependency.active}、校验里的
 *       {@code conditionActive} 全部来自 {@link CategoryAttributeRequirementRules#conditionHolds}。
 *       两处若各写一遍，天平早晚会歪：表单亮着必填星号、提交却被放行。
 *   <li><b>派生结果不落表</b>。表单依赖「当前已填了什么」，任何缓存都会在用户改完前置字段后过期。
 * </ol>
 *
 * <p>字段顺序：先按规则 position，再按条件依赖拓扑排序。**依赖必须在被依赖之后出现**——
 * 否则录入表单会把「保修期」排在「颜色」前面，用户还不知道为什么这个字段突然必填了。
 */
@Service
public class CategoryAttributeRequirementForms {

  /** 字段键。schema 的 key 用属性 code：它是录入表单与刊登表单之间最稳定的对接标识。 */
  private static final String NO_VALUE_SENTINEL = null;

  private final DSLContext sql;
  private final CategoryAttributeRequirementService requirements;

  public CategoryAttributeRequirementForms(
      DSLContext sql, CategoryAttributeRequirementService requirements) {
    this.sql = sql;
    this.requirements = requirements;
  }

  // ============================================================ 表单 schema 生成

  /**
   * 生成某个类目的录入表单 schema。
   *
   * <p>{@code currentValues} 是**已填写的值**（key = 属性 code 或 id），用于求值条件必填：
   * 不传时所有条件字段的 {@code dependency.active=false}、{@code required=false}
   * ——不是「未知」，而是「还没触发」，前端初始渲染据此不亮必填星号。
   *
   * @param includeDisabled 是否包含停用规则的字段。默认 {@code false}：停用的规则不参与校验，
   *     字段就不该出现在录入表单里（仍可用 {@code true} 做影响面排查）
   */
  public EntryFormSchema schema(UUID categoryId, Map<String, String> currentValues, boolean includeDisabled) {
    var category = categoryRow(categoryId);
    // 平台类目映射树的节点归 04 刊登维护，蓝图明确「此处只管内部类目」。
    // 早拒绝好过生成一份「用不上的内部表单」——04 侧要的是平台自己的要求，两套不能混。
    CatalogService.check(
        "INTERNAL".equals(treeKind(categoryId)),
        "平台类目映射树的属性要求由 04 刊登按平台维护，本端点只服务内部管理分类"
            + "：不同平台的类目要求可能不同，混用会让刊登合规判断失效");

    List<CategoryAttributeRequirement> rules = requirements.effectiveRequirements(categoryId);
    if (includeDisabled) {
      rules = new ArrayList<>(rules);
      rules.addAll(requirements.ownRequirements(categoryId).stream().filter(r -> !r.enabled()).toList());
    }
    List<CategoryAttributeRequirement> ordered = orderForEntry(rules);

    List<EntryFormField> fields = new ArrayList<>(ordered.size());
    for (CategoryAttributeRequirement rule : ordered) {
      // 条件求值读的是**前置属性**的当前值，不是本字段自己的值：
      // 「保修期在颜色=红色时必填」取决于颜色填了什么，与保修期填了什么无关。
      // 两者读混会让条件永远不成立——症状是「字段该亮必填时不亮」，极难自查。
      String dependencyValue =
          rule.condition() == null
              ? null
              : submitted(currentValues, rule.condition().attributeId(), rule.condition().attributeCode());
      fields.add(toField(rule, dependencyValue));
    }
    long requiredCount = fields.stream().filter(EntryFormField::required).count();
    long conditionalCount =
        fields.stream().filter(f -> "CONDITIONAL_REQUIRED".equals(f.requirement())).count();
    return new EntryFormSchema(
        categoryId,
        category.get("code", String.class),
        category.get("name", String.class),
        categoryPath(categoryId),
        fields.size(),
        (int) requiredCount,
        (int) conditionalCount,
        fields,
        "字段顺序按规则的 position 排，条件依赖的前置字段排在依赖它的字段之前。"
            + "停用的规则不参与校验，因此默认不出现在表单里。");
  }

  /**
   * 字段顺序：position 升序 + 条件依赖拓扑。
   *
   * <p>拓扑这一层不是可有可无的美化：条件必填的字段排在它的前置字段**之前**时，
   * 用户在表单上先看到一个必填星号，却要往下滚很久才找到决定它亮不亮的那个字段。
   * 稳定排序（position → code）保证同样的配置每次生成的顺序一致，
   * 否则集成测试与前端快照都会不稳定。
   */
  private List<CategoryAttributeRequirement> orderForEntry(List<CategoryAttributeRequirement> rules) {
    List<CategoryAttributeRequirement> base = new ArrayList<>(rules);
    base.sort(CategoryAttributeRequirementService.byFormOrder());
    Map<UUID, CategoryAttributeRequirement> byAttribute = new LinkedHashMap<>();
    for (CategoryAttributeRequirement rule : base) byAttribute.put(rule.attributeId(), rule);

    List<CategoryAttributeRequirement> result = new ArrayList<>(base.size());
    // 用 Set 而不是 List 记「已排过」：List.contains 与 add 的一致性不该由调用方去赌，
    // 规则重复进表单是那种「界面上看不出哪里错了」的问题。
    Set<UUID> placed = new LinkedHashSet<>();
    for (CategoryAttributeRequirement rule : base) {
      // 前置属性若也在本表单内且还没排过，把它提到前面；否则维持 position 顺序。
      // 只提升一层：条件依赖「另一个条件必填字段」在配置上是允许的（服务层不禁止），
      // 这里不递归——两跳依赖在录入表单上无法向用户解释，且配置入口已能看出依赖关系。
      CategoryAttributeCondition condition = rule.condition();
      if (condition != null) {
        CategoryAttributeRequirement dependency = byAttribute.get(condition.attributeId());
        if (dependency != null && placed.add(dependency.attributeId())) result.add(dependency);
      }
      if (placed.add(rule.attributeId())) result.add(rule);
    }
    return result;
  }

  /** 把一条规则翻成一个表单字段。{@code dependencyValue} 是**前置属性**的当前值。 */
  private EntryFormField toField(CategoryAttributeRequirement rule, String dependencyValue) {
    EntryFormDependency dependency = null;
    boolean required = false;
    if (rule.condition() != null) {
      boolean active =
          conditionHolds(
              rule.condition().operator(),
              conditionValues(rule),
              rule.condition().dataType(),
              dependencyValue);
      dependency =
          new EntryFormDependency(
              rule.condition().attributeId(),
              rule.condition().attributeCode(),
              rule.condition().attributeName(),
              rule.condition().operator(),
              conditionValues(rule),
              active);
      // 条件成立才必填——这是本模块「条件必填真正参与 schema 生成」的落点。
      required = active;
    } else {
      required = "REQUIRED".equals(rule.requirement());
    }
    // 停用的规则不参与校验，因此**不得**产生必填星号。
    // 它出现在表单里只可能来自 includeDisabled=true 的影响面排查，那种场景下
    // 让运营看到一个「必填但不校验」的字段，比不显示更容易误导。
    if (!rule.enabled()) required = false;
    return new EntryFormField(
        rule.attributeCode(),
        rule.attributeId(),
        rule.attributeCode(),
        rule.attributeName(),
        rule.dataType(),
        control(rule.dataType()),
        required,
        rule.requirement(),
        dependency,
        options(rule),
        new EntryFormConstraints(
            rule.minValue(),
            rule.maxValue(),
            rule.maxLength(),
            "OPTION".equals(rule.dataType()) ? rule.allowFreeText() : null,
            rule.allowedOptions().isEmpty() ? null : rule.allowedOptions()),
        rule.position(),
        rule.inherited(),
        rule.sourceCategoryId(),
        rule.sourceCategoryName());
  }

  /**
   * 控件类型：由属性数据类型推导，前端不再自己映射一遍。
   *
   * <p>映射错了的代价是「数字属性给了文本框」，用户输进来的值到校验那步才发现不合法——
   * 提前到 schema 层解决。
   */
  private static String control(String dataType) {
    return switch (dataType == null ? "" : dataType.trim().toUpperCase(Locale.ROOT)) {
      case "DECIMAL" -> "NUMBER";
      case "BOOLEAN" -> "BOOLEAN";
      case "DATE" -> "DATE";
      case "OPTION" -> "SELECT";
      default -> "TEXT";
    };
  }

  /**
   * 枚举属性的可选项，停用的也返回。
   *
   * <p>停用项必须返回而不能消失：消失会让运营以为自己配的选项不见了，
   * 而实际上它只是被 3.2.1.2 停用了（既有商品还要按它解析）。前端按 {@code enabled} 置灰。
   */
  private List<EntryFormOption> options(CategoryAttributeRequirement rule) {
    if (!"OPTION".equals(rule.dataType())) return List.of();
    Map<String, Boolean> enabled = new LinkedHashMap<>();
    for (Record r :
        sql.fetch("select value,enabled from attribute_option where attribute_id=? order by value",
            rule.attributeId()))
      enabled.put(r.get("value", String.class), Boolean.TRUE.equals(r.get("enabled", Boolean.class)));
    return enabled.keySet().stream().map(v -> new EntryFormOption(v, enabled.getOrDefault(v, true))).toList();
  }

  // ============================================================ 保存前校验

  /**
   * 按类目的有效规则集校验一份提交的属性值。
   *
   * <p>逐字段返回结果（不是遇错就抛 400）：录入表单需要「同时高亮所有不合法字段」，
   * 一次报一个错误会让用户来回提交十几轮。整体结论放在 {@code valid} 上。
   *
   * <p>三条口径：
   * <ul>
   *   <li><b>未提交且非必填 → PASSED</b>。可选属性不填是合法的，不该报错。
   *   <li><b>条件必填在条件不成立时不算缺失</b>：此时它就是一个普通可选项。
   *       条件成立却没填，才报 {@code CONDITION_UNMET}——把「条件触发了但没填」
   *       与「没触发」分成两个码，运营看到提示才知道该去改哪个字段。
   *   <li><b>提交了本类目未配置的属性 → {@code UNKNOWN_ATTRIBUTE}</b>（失败）。
   *       静默丢弃会让人以为值存进去了，实际被扔掉。
   * </ul>
   */
  public AttributeValidationReport validate(UUID categoryId, AttributeValidationInput input) {
    var category = categoryRow(categoryId);
    CatalogService.check(
        "INTERNAL".equals(treeKind(categoryId)),
        "平台类目映射树的属性要求由 04 刊登按平台维护，本端点只服务内部管理分类");

    List<CategoryAttributeRequirement> rules =
        requirements.effectiveRequirements(categoryId).stream()
            .sorted(CategoryAttributeRequirementService.byFormOrder())
            .toList();

    // 提交值按「属性 id」归拢：前端可能按 code 提交、脚本可能按 id 提交，
    // 校验侧统一到 id 上比对，避免「同一个值被当成两条」。
    Map<UUID, String> submittedById = new LinkedHashMap<>();
    for (AttributeValueInput entry : input.values()) {
      UUID attributeId = resolveAttributeId(entry);
      CatalogService.check(attributeId != null,
          "每个提交项都要带 attributeId 或 attributeCode");
      boolean known =
          rules.stream().anyMatch(r -> r.attributeId().equals(attributeId));
      CatalogService.check(known,
          "属性「" + entry.attributeCode() + "」在这个类目上没有生效的属性要求，无法校验："
              + "请先在类目属性要求里配置它，或检查是否误传了别的类目的属性");
      submittedById.put(attributeId, entry.value());
    }

    List<AttributeValidationResult> results = new ArrayList<>();
    for (CategoryAttributeRequirement rule : rules) {
      // ownValue 是本字段提交的值；dependencyValue 是条件前置属性提交的值。
      // 两者必须分开取：条件读自己的值会让条件恒不成立（见 schema 侧的同一处注释）。
      String ownValue = submittedById.get(rule.attributeId());
      String dependencyValue =
          rule.condition() == null ? null : submittedById.get(rule.condition().attributeId());
      results.add(check(rule, ownValue, dependencyValue));
    }
    long failed = results.stream().filter(r -> !r.passed()).count();
    return new AttributeValidationReport(
        categoryId,
        failed == 0,
        results.size(),
        (int) failed,
        results,
        "校验基于该类目的有效属性要求（含从祖先类目继承的）。条件必填只在条件成立时才判必填，"
            + "条件不成立时该字段等同于可选项。");
  }

  /** 单条规则的校验：缺失 → 类型 → 取值约束，按这个顺序短路，每条规则最多给一个原因。 */
  private AttributeValidationResult check(
      CategoryAttributeRequirement rule, String ownValue, String dependencyValue) {
    String value = normalizeConditionValue(ownValue);
    boolean blank = value.isEmpty();

    if (rule.condition() != null) {
      boolean active =
          conditionHolds(
              rule.condition().operator(),
              conditionValues(rule),
              rule.condition().dataType(),
              dependencyValue);
      if (active && blank)
        return result(rule, "CONDITION_UNMET", active, value,
            "「" + rule.attributeName() + "」在「" + rule.condition().attributeName() + "」取 "
                + String.join(" / ", conditionValues(rule)) + " 时必填，请补填");
      if (blank) return result(rule, "PASSED", active, value, null);
      return checkValue(rule, value, active);
    }
    if (blank)
      return "REQUIRED".equals(rule.requirement())
          ? result(rule, "MISSING", null, value, "「" + rule.attributeName() + "」是该类目的必填属性")
          : result(rule, "PASSED", null, value, null);
    return checkValue(rule, value, null);
  }

  /** 已提交值的类型与取值约束校验。 */
  private AttributeValidationResult checkValue(
      CategoryAttributeRequirement rule, String value, Boolean conditionActive) {
    String typeProblem = typeProblem(rule.dataType(), value);
    if (typeProblem != null) return result(rule, "TYPE_MISMATCH", conditionActive, value, typeProblem);

    if (rule.minValue() != null || rule.maxValue() != null) {
      BigDecimal number = new BigDecimal(value);
      if (rule.minValue() != null && number.compareTo(rule.minValue()) < 0)
        return result(rule, "OUT_OF_RANGE", conditionActive, value,
            "「" + rule.attributeName() + "」不能小于 " + rule.minValue());
      if (rule.maxValue() != null && number.compareTo(rule.maxValue()) > 0)
        return result(rule, "OUT_OF_RANGE", conditionActive, value,
            "「" + rule.attributeName() + "」不能大于 " + rule.maxValue());
    }
    if (rule.maxLength() != null && value.length() > rule.maxLength())
      return result(rule, "LENGTH_EXCEEDED", conditionActive, value,
          "「" + rule.attributeName() + "」最多 " + rule.maxLength() + " 个字符，当前 " + value.length() + " 个");

    if ("OPTION".equals(rule.dataType())) {
      boolean allowed =
          rule.allowedOptions().isEmpty() || rule.allowedOptions().contains(value);
      if (!allowed && !rule.allowFreeText())
        return result(rule, "OPTION_NOT_ALLOWED", conditionActive, value,
            "「" + value + "」不在该类目允许的取值范围内"
                + (rule.allowedOptions().isEmpty() ? "" : "：" + String.join(" / ", rule.allowedOptions())));
      boolean exists =
          sql.fetchOne("select 1 from attribute_option where attribute_id=? and value=?",
              rule.attributeId(), value) != null;
      if (!exists && !rule.allowFreeText())
        return result(rule, "OPTION_NOT_ALLOWED", conditionActive, value,
            "「" + value + "」不是属性「" + rule.attributeName() + "」的选项值");
    }
    return result(rule, "PASSED", conditionActive, value, null);
  }

  /**
   * 类型校验。返回可读原因或 {@code null}。
   *
   * <p>BOOLEAN 刻意只接受 {@code true}/{@code false} 字面量：把 {@code 1} / {@code yes} /
   * {@code 是} 都算成 true 会让「勾选框没勾但值是 1」这类数据静默通过。
   * DATE 按 ISO {@code yyyy-MM-dd}：与属性字典的 DATE 存法一致。
   */
  private static String typeProblem(String dataType, String value) {
    String type = dataType == null ? "" : dataType.trim().toUpperCase(Locale.ROOT);
    return switch (type) {
      case "DECIMAL" -> {
        try {
          new BigDecimal(value);
          yield null;
        } catch (NumberFormatException e) {
          yield "「" + value + "」不是合法数值";
        }
      }
      case "BOOLEAN" ->
          ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))
              ? null
              : "布尔值只能是 true 或 false";
      case "DATE" -> {
        try {
          java.time.LocalDate.parse(value);
          yield null;
        } catch (RuntimeException e) {
          yield "日期必须是 yyyy-MM-dd 格式";
        }
      }
      default -> null;
    };
  }

  private AttributeValidationResult result(
      CategoryAttributeRequirement rule, String code, Boolean conditionActive, String value, String message) {
    return new AttributeValidationResult(
        rule.attributeId(),
        rule.attributeCode(),
        rule.attributeName(),
        code,
        message,
        "PASSED".equals(code),
        rule.condition() != null ? Boolean.TRUE.equals(conditionActive) : "REQUIRED".equals(rule.requirement()),
        rule.requirement(),
        conditionActive,
        value);
  }

  // ============================================================ 概览

  /** 概览：规则覆盖率。与 3.2.1.1 的「类目缺口」同源，但按类目主键统计，能与分类树对上。 */
  public CategoryRequirementsOverview overview() {
    UUID tenantId = currentTenantId();
    var counts =
        sql.fetchOne(
            "select count(*) as requirement_count,"
                + "count(*) filter (where requirement='CONDITIONAL_REQUIRED') as conditional_count,"
                + "count(*) filter (where not enabled) as disabled_count"
                + " from category_attribute_rule where tenant_id=?",
            tenantId);
    long categoryCount =
        sql.fetchOne(
                "select count(*) from product_category c join product_category_tree t"
                    + " on t.tenant_id=c.tenant_id and t.id=c.tree_id"
                    + " where c.tenant_id=? and t.tree_kind='INTERNAL'",
                tenantId)
            .get(0, Long.class);
    long covered =
        sql.fetchOne(
                "select count(distinct r.category_id) from category_attribute_rule r"
                    + " join product_category c on c.tenant_id=r.tenant_id and c.id=r.category_id"
                    + " join product_category_tree t on t.tenant_id=c.tenant_id and t.id=c.tree_id"
                    + " where r.tenant_id=? and t.tree_kind='INTERNAL' and r.enabled",
                tenantId)
            .get(0, Long.class);
    return new CategoryRequirementsOverview(
        categoryCount,
        covered,
        categoryCount - covered,
        counts.get("requirement_count", Long.class),
        counts.get("conditional_count", Long.class),
        counts.get("disabled_count", Long.class),
        "覆盖率按内部管理分类树的类目统计（含从祖先继承到至少一条要求的类目）。"
            + "没有要求的类目，其录入表单不做必填校验。");
  }

  // ============================================================ 工具

  /**
   * 从规则上取条件取值。
   *
   * <p>条件取值存在 {@code category_attribute_condition_value} 里，而
   * {@link CategoryAttributeRequirement} 不重复携带它们（避免同一份数据在 DTO 里出现两处）。
   * 这里按规则逐条取：表单生成与校验都会用到，而一份类目的规则数有限，
   * 逐条查换来的是「规则 DTO 与条件取值表各自单一来源」。
   */
  private List<String> conditionValues(CategoryAttributeRequirement rule) {
    if (rule.condition() == null) return List.of();
    return sql.fetch(
            "select condition_value from category_attribute_condition_value"
                + " where category_id=? and attribute_id=? order by position",
            rule.categoryId(), rule.attributeId())
        .getValues("condition_value", String.class);
  }

  /** 提交值取值：id 优先，其次 code（前端拿的是 schema，用 code 最顺手）。 */
  private static String submitted(Map<String, String> values, UUID attributeId, String attributeCode) {
    if (values == null || values.isEmpty()) return NO_VALUE_SENTINEL;
    String byId = values.get(attributeId.toString());
    return byId != null ? byId : values.get(attributeCode);
  }

  /** 把提交项解析成属性 id：两者给全时以 id 为准。 */
  private UUID resolveAttributeId(AttributeValueInput entry) {
    if (entry.attributeId() != null) return entry.attributeId();
    if (entry.attributeCode() == null || entry.attributeCode().isBlank()) return null;
    var row = sql.fetchOne("select id from attribute_definition where code=?", entry.attributeCode().trim());
    return row == null ? null : row.get("id", UUID.class);
  }

  private Record categoryRow(UUID id) {
    var row = sql.fetchOne("select id,code,name,path_ids from product_category where id=?", id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private String treeKind(UUID categoryId) {
    var row =
        sql.fetchOne(
            "select t.tree_kind from product_category c join product_category_tree t"
                + " on t.tenant_id=c.tenant_id and t.id=c.tree_id where c.id=?",
            categoryId);
    return row == null ? null : row.get("tree_kind", String.class);
  }

  private String categoryPath(UUID categoryId) {
    UUID[] path = categoryRow(categoryId).get("path_ids", UUID[].class);
    if (path == null || path.length == 0) return "";
    Map<UUID, String> names = new LinkedHashMap<>();
    for (Record r :
        sql.fetch("select id,name from product_category where id in ("
                + String.join(",", java.util.Collections.nCopies(path.length, "?")) + ")", path))
      names.put(r.get("id", UUID.class), r.get("name", String.class));
    List<String> parts = new ArrayList<>();
    for (UUID id : path) if (names.containsKey(id)) parts.add(names.get(id));
    return String.join(" / ", parts);
  }

  private UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }
}