package com.starcon.catalog;

import static com.starcon.catalog.CategoryAttributeRequirementRules.*;
import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.3.1.2 类目属性要求：按类目配置必填 / 可选 / 条件必填与取值范围，
 * 并输出蓝图点名的两个结果——**录入表单 schema** 与**保存前校验结果**。
 *
 * <p>本类的纪律：
 *
 * <ol>
 *   <li><b>继承按属性逐条就近覆盖</b>。有效规则集 = 沿 {@code path_ids} 从自己往根找
 *       每个属性的第一条规则；这条规则 {@code enabled=false} 时该属性整体不生效
 *       （子节点豁免祖先要求的唯一手段）。算法见 V15 迁移头部注释，只有一处实现：
 *       {@link #effectiveRequirements}。
 *   <li><b>表单 schema 与保存前校验共用同一个求值器</b>。两处分别实现条件判定的话，
 *       一定会出现「表单标了必填、校验却放行」的分裂——那种 bug 要等到用户投诉才发现。
 *       因此两者都调 {@link CategoryAttributeRequirementRules#conditionHolds}。
 *   <li><b>派生结果不落表</b>。表单 schema 依赖「当前已填的值」（条件必填据此决定字段亮不亮必填），
 *       落表就必然过期。按类目 + 按提交值现算。
 *   <li><b>冲突在写入侧拒绝，不在读取侧兜底</b>：同分类同属性重复、引用不存在的属性、
 *       条件引用不可枚举的取值，都在保存时给出可读原因，而不是让录入表单静默失效。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.attribute.read}，写 {@code catalog.attribute.update}。
 * 类目属性要求是「属性」在「类目」上的投影，沿用属性域权限（与 3.2.1.1 / 3.2.1.2 同源），
 * 不新造权限词表——新词表要改 identity 服务的迁移，属于另一个服务的写入所有权。
 */
@Service
public class CategoryAttributeRequirementService {

  /**
   * 规则行的列清单（**不含 {@code select} 前缀**）。
   *
   * <p>之所以不把 select 写进常量：继承解析那条查询需要在列清单前面再接
   * {@code select distinct on (r.attribute_id)}，而列名必须排在 distinct on 之后。
   * 把 select 写死在常量里就会拼出 {@code select distinct on (...) select ...} 这种废 SQL。
   */
  private static final String RULE_COLUMNS =
      "r.category_id,r.attribute_id,r.requirement,r.enabled,r.min_value,r.max_value,"
          + "r.max_length,r.allow_free_text,r.position,r.version,r.updated_at,"
          + "r.condition_attribute_id,r.condition_operator,"
          + "d.code as attribute_code,d.name as attribute_name,d.data_type,"
          + "c.name as category_name";

  private static final String RULE_FROM =
      " from category_attribute_rule r"
          + " join attribute_definition d on d.tenant_id=r.tenant_id and d.id=r.attribute_id"
          + " join product_category c on c.tenant_id=r.tenant_id and c.id=r.category_id"
          + " where r.tenant_id=?";

  private final DSLContext sql;
  private final CategoryTreeService categories;

  public CategoryAttributeRequirementService(DSLContext sql, CategoryTreeService categories) {
    this.sql = sql;
    this.categories = categories;
  }

  // ============================================================ 读取：有效规则集（继承解析）

  /**
   * 某个类目的**有效规则集**：沿祖先链按属性逐条就近覆盖的结果。
   *
   * <p>覆盖在 SQL 侧用一条 {@code distinct on} 完成。排序键是「目标类目的祖先链里，
   * 该规则所在节点的下标」：下标越大越靠近自己，所以降序取第一行就是「最近的那条」。
   * 在 Java 里做同一件事要把祖先链上的规则全部捞出来再排序去重——规则一多就是无用功。
   *
   * <p>下标由 {@link #decorate} 按 {@code targetPath} 在内存里算，**不在 SQL 里算**：
   * SQL 侧能看到的只有「规则自己所在类目的 path_ids」，拿不到被查询的那个目标类目的链，
   * 拿 `c.path_ids` 去算会把每条规则的深度都算成它在自身链里的下标（即恒为它自己的层数），
   * 于是「继承来的」与「自己配的」永远分不开。
   *
   * <p>停用语义（V15 迁移头部规则的第 3 条）：**生效规则先按「最近」选定，再看它启没启用**。
   * 所以 {@code enabled=false} 的那条如果离自己最近，该属性就整体不进入有效规则集——
   * 这正是子节点豁免祖先要求的手段。反过来（先过滤 enabled 再取最近）会让子节点的停用
   * 退化成「失效并回退到祖先」，那样子类目就**没有任何办法**说「这个属性我不需要」：
   * 祖先要求一旦配上，子孙全部继承，停用只能把要求变成可选而不能取消。
   */
  public List<CategoryAttributeRequirement> effectiveRequirements(UUID categoryId) {
    UUID[] path = categoryRow(categoryId).get("path_ids", UUID[].class);
    if (path == null || path.length == 0) return List.of();
    var rows =
        sql.fetch(
            "select distinct on (r.attribute_id) " + RULE_COLUMNS + RULE_FROM
                + " and r.category_id = any(?)"
                + " order by r.attribute_id,"
                + "coalesce(array_position(?::uuid[],r.category_id),0) desc",
            currentTenantId(),
            path,
            path);
    // 停用的生效规则在这里剔除（而不是在 SQL 的 where 里）—— where 里过滤就分不清
    // 「没有这条规则」与「有但被停用」，而这两者的区别正是豁免语义的全部。
    return decorate(rows, path).stream().filter(CategoryAttributeRequirement::enabled).toList();
  }

  /** 某个类目**自己**配置的规则（不含继承）。配置页「只看本节点」视图用。 */
  public List<CategoryAttributeRequirement> ownRequirements(UUID categoryId) {
    // targetPath 只放自己：这些规则按定义全在本节点，深度恒为 0（inherited=false）。
    // 租户占位符必须显式绑定：RLS 会按 app.current_tenant 再过滤一次，但
    // RULE_FROM 里已经有 r.tenant_id=?，少传一个参数会直接报「未设定参数值」。
    return decorate(
        sql.fetch(
            "select " + RULE_COLUMNS + RULE_FROM + " and r.category_id=?",
            currentTenantId(), categoryId),
        new UUID[] {categoryId});
  }

  /**
   * 补齐规则上的选项集合与条件取值，并按目标类目的祖先链算出每条规则的层数。
   *
   * <p>两处都是「一次查询 + 内存归拢」而不是逐条查：一份类目的规则动辄几十条，
   * 逐条查就是几十次往返，而三个端点都会调它。
   *
   * @param targetPath 被查询类目的祖先链（根 → 自己），用来判定每条规则是继承来的还是本节点的
   */
  private List<CategoryAttributeRequirement> decorate(List<Record> rows, UUID[] targetPath) {
    if (rows.isEmpty()) return List.of();
    List<UUID> attributeIds = rows.stream().map(r -> r.get("attribute_id", UUID.class)).distinct().toList();
    List<UUID> categoryIds = rows.stream().map(r -> r.get("category_id", UUID.class)).distinct().toList();

    Map<String, List<String>> options = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select category_id,attribute_id,option_value from category_attribute_option"
                + " where category_id = any(?) and attribute_id = any(?)"
                + " order by category_id,attribute_id,option_value",
            categoryIds.toArray(new UUID[0]), attributeIds.toArray(new UUID[0])))
      options
          .computeIfAbsent(optionKey(r.get("category_id", UUID.class), r.get("attribute_id", UUID.class)),
              k -> new ArrayList<>())
          .add(r.get("option_value", String.class));

    Map<String, List<CategoryAttributeConditionValue>> conditions = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select category_id,attribute_id,condition_value,position"
                + " from category_attribute_condition_value"
                + " where category_id = any(?) and attribute_id = any(?)"
                + " order by category_id,attribute_id,condition_attribute_id,position",
            categoryIds.toArray(new UUID[0]), attributeIds.toArray(new UUID[0])))
      conditions
          .computeIfAbsent(optionKey(r.get("category_id", UUID.class), r.get("attribute_id", UUID.class)),
              k -> new ArrayList<>())
          .add(
              new CategoryAttributeConditionValue(
                  r.get("condition_value", String.class), r.get("position", Integer.class)));

    List<CategoryAttributeRequirement> list = new ArrayList<>(rows.size());
    for (Record r : rows) {
      UUID categoryId = r.get("category_id", UUID.class);
      UUID attributeId = r.get("attribute_id", UUID.class);
      List<CategoryAttributeConditionValue> conditionValues =
          conditions.get(optionKey(categoryId, attributeId));
      list.add(
          toRequirement(
              r,
              options.get(optionKey(categoryId, attributeId)),
              conditionValues == null
                  ? List.of()
                  : conditionValues.stream().map(CategoryAttributeConditionValue::value).toList(),
              depthIn(targetPath, categoryId),
              targetPath.length - 1));
    }
    return list;
  }

  /**
   * 规则所在节点在被查询类目的祖先链里的层数：0 = 本节点自己。
   *
   * <p>找不到时返回 0（当作本节点）而不是抛错：调用方只传「本节点链」或「自身链」，
   * 两种情况下结果都该是 0。真的传错了链条也只是层数显示不对，不会让整棵树的读取失败。
   */
  private static int depthIn(UUID[] path, UUID categoryId) {
    for (int i = path.length - 1; i >= 0; i--) if (path[i].equals(categoryId)) return i;
    return 0;
  }

  private static String optionKey(UUID categoryId, UUID attributeId) {
    return categoryId + "/" + attributeId;
  }

  private CategoryAttributeRequirement toRequirement(
      Record r, List<String> allowedOptions, List<String> conditionValues, int depth, int selfIndex) {
    UUID categoryId = r.get("category_id", UUID.class);
    UUID attributeId = r.get("attribute_id", UUID.class);
    UUID conditionAttributeId = r.get("condition_attribute_id", UUID.class);
    CategoryAttributeCondition condition = null;
    if (conditionAttributeId != null) {
      // 前置属性的 code / name 只用于界面显示「当【颜色】等于红色时必填」。
      // 这里的取值来自同租户的属性字典（外键已保证存在）；取不到就让 condition 为 null，
      // 而不是把整条规则变成 500——那条规则仍会在校验时按 id 求值。
      var dep =
          sql.fetchOne(
              "select code,name,data_type from attribute_definition where id=?",
              conditionAttributeId);
      condition =
          new CategoryAttributeCondition(
              conditionAttributeId,
              dep == null ? null : dep.get("code", String.class),
              dep == null ? null : dep.get("name", String.class),
              dep == null ? null : dep.get("data_type", String.class),
              r.get("condition_operator", String.class));
    }
    return new CategoryAttributeRequirement(
        categoryId,
        r.get("category_name", String.class),
        attributeId,
        r.get("attribute_code", String.class),
        r.get("attribute_name", String.class),
        r.get("data_type", String.class),
        r.get("requirement", String.class),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        // 继承 = 规则挂在自己以外的祖先节点上。判据是「层数是否等于自己所在的那一层」，
        // 不是 depth > 0：根类目的规则深度就是 0，用 depth>0 会把「根节点自己配的」
        // 误标成继承来的，配置页上每一行都会挂个假的「继承」标签。
        depth < selfIndex,
        depth,
        categoryId,
        r.get("category_name", String.class),
        r.get("min_value", BigDecimal.class),
        r.get("max_value", BigDecimal.class),
        r.get("max_length", Integer.class),
        Boolean.TRUE.equals(r.get("allow_free_text", Boolean.class)),
        r.get("position", Integer.class),
        allowedOptions == null ? List.of() : allowedOptions,
        condition,
        conditionValues == null ? List.of() : conditionValues,
        r.get("version", Long.class),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  // ============================================================ 读取：配置页视图

  /** 配置页主视图：生效规则集 + 本节点规则 + 统计。 */
  public CategoryRequirementsView view(UUID categoryId) {
    var category = categoryRow(categoryId);
    var effective = effectiveRequirements(categoryId);
    var own = ownRequirements(categoryId);
    long conditional =
        effective.stream().filter(r -> "CONDITIONAL_REQUIRED".equals(r.requirement())).count();
    long required = effective.stream().filter(r -> "REQUIRED".equals(r.requirement())).count();
    long disabled = own.stream().filter(r -> !r.enabled()).count();
    return new CategoryRequirementsView(
        categoryId,
        category.get("code", String.class),
        category.get("name", String.class),
        categoryPath(categoryId),
        effective,
        own,
        new CategoryRequirementsSummary(
            effective.size(),
            required,
            conditional,
            effective.size() - required - conditional,
            own.size(),
            own.stream().filter(CategoryAttributeRequirement::inherited).count(),
            own.size() - disabled,
            disabled));
  }

  // ============================================================ 写入

  /**
   * 新建或更新一条类目属性要求（同一类目同一属性唯一）。
   *
   * <p>「新建还是更新」由是否存在同一 {@code (category_id, attribute_id)} 的规则决定，
   * 不另设 PUT/POST 两套路径——蓝图的 CRUD 语义是「配一条要求」，不是「维护一个规则的行」。
   * 更新走 version 乐观锁，与 3.3.1.1 的类目保存同口径。
   *
   * <p>冲突检测都在这里给出可读原因，DB 层的唯一约束与 CHECK 是第二道：
   * <ul>
   *   <li>同分类同属性重复 → 走 upsert 而不是报冲突（这是「改这条要求」的正常路径），
   *       但**属性必须存在且是租户级字典条目**，否则 V11 的外键会报一个界面上没法显示的 23503；
   *   <li>条件引用不存在的属性 → 复合外键 + 服务层双重拒绝；
   *   <li>条件引用不可枚举的取值 → {@link #operatorDataTypeProblem}。
   * </ul>
   */
  public CategoryAttributeRequirementSaved save(
      AccessService.Access actor, UUID categoryId, CategoryAttributeRequirementInput input) {
    categoryRow(categoryId);
    var definition = dictionaryRow(input.attributeId());
    String dataType = definition.get("data_type", String.class);
    CatalogService.check(requirementProblem(input.requirement()) == null, requirementProblem(input.requirement()));

    List<String> allowed = trimmedList(input.allowedOptions());
    String rangeProblem =
        rangeProblem(dataType, input.minValue(), input.maxValue(), input.maxLength(),
            input.allowFreeText(), allowed.size());
    CatalogService.check(rangeProblem == null, rangeProblem);

    List<String> conditionValues = conditionValueList(input.conditionValues());
    String valuesProblem = conditionValuesProblem(input.requirement(), conditionValues);
    CatalogService.check(valuesProblem == null, valuesProblem);

    UUID conditionAttributeId = null;
    String operator = null;
    CategoryAttributeCondition condition = input.condition();
    if ("CONDITIONAL_REQUIRED".equals(normalizeRequirement(input.requirement()))) {
      CatalogService.check(condition != null, "条件必填必须指定前置属性");
      conditionAttributeId = condition.attributeId();
      CatalogService.check(!conditionAttributeId.equals(input.attributeId()),
          "条件必填不能依赖属性自己：判定条件要读的就是这个字段当前的值，永远无法收敛");
      var dependency = dictionaryRow(conditionAttributeId);
      operator = normalizeOperator(condition.operator());
      String operatorProblem = operatorProblem(operator);
      CatalogService.check(operatorProblem == null, operatorProblem);
      // 不可枚举的取值条件在这里被拒：算子与前置属性数据类型不相容时，条件永远求不出真值。
      String typeProblem = operatorDataTypeProblem(operator, dependency.get("data_type", String.class));
      CatalogService.check(typeProblem == null, typeProblem);
      // 条件取值必须落在前置属性的取值域里：对枚举属性就是必须在它的选项值源中。
      verifyConditionValues(conditionAttributeId, dependency.get("data_type", String.class), operator, conditionValues);
    }

    String categoryName = categoryRow(categoryId).get("name", String.class);
    var existing = sql.fetchOne(
        "select version from category_attribute_rule where category_id=? and attribute_id=?",
        categoryId, input.attributeId());
    try {
      if (existing == null) {
        sql.execute(
            "insert into category_attribute_rule(tenant_id,category,category_id,attribute_id,"
                + "required,requirement,condition_attribute_id,condition_operator,"
                + "min_value,max_value,max_length,allow_free_text,position,enabled)"
                + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            actor.tenantId(), categoryName, categoryId, input.attributeId(),
            // required 由 V15 的触发器派生，这里显式传一个与 requirement 无关的 false 占位即可。
            false, normalizeRequirement(input.requirement()), conditionAttributeId, operator,
            input.minValue(), input.maxValue(), input.maxLength(), input.allowFreeText(),
            input.position(), input.enabled());
      } else {
        int updated =
            sql.execute(
                "update category_attribute_rule set requirement=?,condition_attribute_id=?,"
                    + "condition_operator=?,min_value=?,max_value=?,max_length=?,allow_free_text=?,"
                    + "position=?,enabled=?,version=version+1 where category_id=? and attribute_id=?"
                    + " and version=?",
                normalizeRequirement(input.requirement()), conditionAttributeId, operator,
                input.minValue(), input.maxValue(), input.maxLength(), input.allowFreeText(),
                input.position(), input.enabled(), categoryId, input.attributeId(), input.version());
        if (updated != 1)
          throw new ResponseStatusException(HttpStatus.CONFLICT, "类目属性要求已被他人修改，请刷新后重试");
      }
    } catch (DataAccessException e) {
      throw conflict("类目属性要求保存失败：该类目上可能已配置过同一个属性，或条件引用的属性不存在，请刷新后重试");
    }

    replaceAllowedOptions(actor.tenantId(), categoryId, input.attributeId(), allowed);
    replaceConditionValues(actor.tenantId(), categoryId, input.attributeId(), conditionAttributeId, conditionValues);
    audit(actor, categoryId, input.attributeId(), "CategoryAttributeRequirementSaved");
    return new CategoryAttributeRequirementSaved(
        categoryId, input.attributeId(), normalizeRequirement(input.requirement()), conditionValues.size());
  }

  /**
   * 删除一条要求。
   *
   * <p>删掉之后该属性会回退到祖先链上的上一条规则（如果祖先有），而不是变成「无要求」——
   * 这正是继承语义的一致性表现：删除是「不再在本层覆盖」。
   */
  public void delete(AccessService.Access actor, UUID categoryId, UUID attributeId) {
    categoryRow(categoryId);
    var row =
        sql.fetchOne(
            "select 1 from category_attribute_rule where category_id=? and attribute_id=?",
            categoryId, attributeId);
    CatalogService.check(row != null, "这个类目上没有配置该属性的要求");
    sql.execute(
        "delete from category_attribute_rule where category_id=? and attribute_id=?",
        categoryId, attributeId);
    audit(actor, categoryId, attributeId, "CategoryAttributeRequirementDeleted");
  }

  /**
   * 启用停用一条要求。
   *
   * <p>停用与删除是**两个不同的动作**，语义也必须分开（与 3.3.1.1 停用类目的口径一致）：
   * <ul>
   *   <li>停用 = 本节点不再覆盖祖先：该属性回退到祖先链上的上一条规则；
   *   <li>删除 = 本层的这条覆盖彻底不存在，条件取值与允许选项一并清除。
   * </ul>
   * 两者都保留「撤销」能力：停用可再启用，删除则需要重新配置。
   */
  public void setEnabled(AccessService.Access actor, UUID categoryId, UUID attributeId, boolean enabled) {
    var row =
        sql.fetchOne(
            "select 1 from category_attribute_rule where category_id=? and attribute_id=?",
            categoryId, attributeId);
    CatalogService.check(row != null, "这个类目上没有配置该属性的要求");
    sql.execute(
        "update category_attribute_rule set enabled=?,version=version+1 where category_id=? and attribute_id=?",
        enabled, categoryId, attributeId);
    audit(actor, categoryId, attributeId, enabled ? "CategoryAttributeRequirementEnabled" : "CategoryAttributeRequirementDisabled");
  }

  /**
   * 条件取值必须落在前置属性的取值域内。
   *
   * <p>对枚举属性是硬要求：条件里写一个不在值源里的值，那条条件永远不会被满足，
   * 字段会一直停在「非必填」——配置的人看不出任何异常。
   * 对数值 / 日期属性则校验可解析性，否则排序类算子无法求值。
   */
  private void verifyConditionValues(
      UUID conditionAttributeId, String dataType, String operator, List<String> values) {
    for (String value : values) {
      CatalogService.check(value.length() <= MAX_CONDITION_VALUE_LENGTH,
          "条件取值最多 " + MAX_CONDITION_VALUE_LENGTH + " 个字符");
      if ("OPTION".equals(dataType)) {
        boolean exists =
            sql.fetchOne(
                    "select 1 from attribute_option where attribute_id=? and value=?",
                    conditionAttributeId, value)
                != null;
        CatalogService.check(exists,
            "条件取值「" + value + "」不在属性「" + dataType + "」的选项值源里：该条件永远不会成立");
      }
      if (isOrderingOperator(operator) && Set.of("DECIMAL", "DATE").contains(dataType)) {
        boolean numeric;
        try {
          new BigDecimal(value);
          numeric = true;
        } catch (NumberFormatException e) {
          numeric = false;
        }
        CatalogService.check(numeric,
            "条件取值「" + value + "」无法与数值或日期属性比较：排序类算子要求取值是可比较的数值");
      }
    }
  }

  private void replaceAllowedOptions(UUID tenantId, UUID categoryId, UUID attributeId, List<String> values) {
    sql.execute(
        "delete from category_attribute_option where category_id=? and attribute_id=?",
        categoryId, attributeId);
    for (String value : values)
      sql.execute(
          "insert into category_attribute_option(tenant_id,category,category_id,attribute_id,option_value)"
              + " values(?,(select name from product_category where id=?),?,?,?)",
          tenantId, categoryId, categoryId, attributeId, value);
  }

  /**
   * 整体替换条件取值。
   *
   * <p>覆盖式而不是增量：条件取值是「这条条件由哪几个值构成」的一个整体，
   * 界面上也是一张表单，增量会让「取消勾选一个值」与「新增一个值」走两套代码。
   *
   * <p>先删后插的顺序不可颠倒：V15 的 {@code guard_category_attribute_condition_value}
   * 在插入时校验「取值挂在规则声明的前置属性上」，而规则的 condition_attribute_id 在
   * 同一次 save 里可能刚被改过——先写取值再改规则会拿旧的依赖属性去对。
   */
  private void replaceConditionValues(
      UUID tenantId, UUID categoryId, UUID attributeId, UUID conditionAttributeId, List<String> values) {
    sql.execute(
        "delete from category_attribute_condition_value where category_id=? and attribute_id=?",
        categoryId, attributeId);
    if (conditionAttributeId == null) return;
    for (int i = 0; i < values.size(); i++)
      sql.execute(
          "insert into category_attribute_condition_value(tenant_id,category_id,attribute_id,"
              + "condition_attribute_id,condition_value,position) values(?,?,?,?,?,?)",
          tenantId, categoryId, attributeId, conditionAttributeId, values.get(i), i);
  }

  // ============================================================ 工具

  private Record categoryRow(UUID id) {
    var row = sql.fetchOne("select id,code,name,path_ids from product_category where id=?", id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  /**
   * 属性字典条目。只认 {@code spu_id IS NULL} 的租户级字典（与 3.2.1.1 同一口径）：
   * 类目要求必须挂在**全局**属性上，否则同一个类目在两个商品上会要求不同的属性。
   */
  private Record dictionaryRow(UUID id) {
    var row = sql.fetchOne("select * from attribute_definition where id=? and spu_id is null", id);
    if (row == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,
          "属性不存在，或它不是属性字典条目（类目属性要求只能挂在租户级属性字典上）");
    return row;
  }

  private String categoryPath(UUID categoryId) {
    UUID[] path = categoryRow(categoryId).get("path_ids", UUID[].class);
    if (path == null || path.length == 0) return "";
    Map<UUID, String> names = new LinkedHashMap<>();
    for (Record r : sql.fetch("select id,name from product_category where id in (" + placeholders(path) + ")", path))
      names.put(r.get("id", UUID.class), r.get("name", String.class));
    List<String> parts = new ArrayList<>();
    for (UUID id : path) {
      String name = names.get(id);
      if (name != null) parts.add(name);
    }
    return String.join(" / ", parts);
  }

  private static List<String> trimmedList(List<String> values) {
    if (values == null) return List.of();
    return values.stream()
        .filter(java.util.Objects::nonNull)
        .map(CategoryAttributeRequirementRules::normalizeConditionValue)
        .filter(v -> !v.isEmpty())
        .distinct()
        .toList();
  }

  /**
   * 条件取值去空白 + 去重。
   *
   * <p>去重而不是报错：条件取值的集合语义是「属于哪几个值」，重复写同一个值不改变语义，
   * 而界面上一次误触就整条保存失败会让人改成乱删。真正非法的情况（空取值集合）
   * 由 {@link CategoryAttributeRequirementRules#conditionValuesProblem} 负责拒绝。
   */
  private static List<String> conditionValueList(List<CategoryAttributeConditionValue> values) {
    if (values == null) return List.of();
    return values.stream()
        .filter(java.util.Objects::nonNull)
        .map(CategoryAttributeConditionValue::value)
        .map(CategoryAttributeRequirementRules::normalizeConditionValue)
        .filter(v -> !v.isEmpty())
        .distinct()
        .toList();
  }

  private static String placeholders(UUID[] ids) {
    return String.join(",", java.util.Collections.nCopies(ids.length, "?"));
  }

  private UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private void audit(AccessService.Access actor, UUID categoryId, UUID attributeId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(), actor.tenantId(), actor.userId(), attributeId, action);
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /** 有效规则按录入表单的字段顺序排：position 升序，同位按属性编码，保证输出稳定可测。 */
  static Comparator<CategoryAttributeRequirement> byFormOrder() {
    return Comparator.comparingInt(CategoryAttributeRequirement::position)
        .thenComparing(CategoryAttributeRequirement::attributeCode);
  }
}