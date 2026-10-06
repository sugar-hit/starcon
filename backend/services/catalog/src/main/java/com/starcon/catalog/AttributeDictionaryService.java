package com.starcon.catalog;

import static com.starcon.catalog.AttributeDictionaryRules.*;
import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.2.1.1 属性定义列表：租户级属性字典、选项集合与类目绑定规则。
 *
 * <p>五条纪律贯穿本类：
 *
 * <ol>
 *   <li><b>只管租户级字典行（{@code spu_id IS NULL}）</b>。V11 把 {@code attribute_definition}
 *       提升成「字典 + SPU 级定义」同表，本类的每一条读写都带 {@code spu_id is null}。
 *       少写这一个条件就会让本页面把每个商品各自建的属性也列出来——那不是字典。
 *   <li><b>值源只有一处</b>。选项写在 {@code attribute_option}，也就是 3.1.3.1 规格轴共享的那个值源。
 *       不另建选项表，否则同一个「红色」会在两处各存一份，直接违反蓝图硬约束。
 *   <li><b>停用与删除分开</b>。被商品取用过的选项只能停用：历史 SKU 的属性值还要靠它解析。
 *       停用挡住的是「不许再新建取用」，既有取值照常可读。
 *   <li><b>编码与数据类型建后不可改</b>。编码是 SPU 级定义与规格轴按 code 对齐的锚点；
 *       改编码会让已存在的对齐关系在下次写入时突然报「不一致」。要改就新建一条字典。
 *   <li><b>删除前先看引用</b>。仍被规格轴引用的字典条目删不动——那会让那些 SPU 的规格凭空消失。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.attribute.read}，写 {@code catalog.attribute.update}，
 * 与属性域保持一致（属性字典与 SPU 属性是同一个域）。
 */
@Service
public class AttributeDictionaryService {
  /** 字典列表页单页上限：防止一次拉爆浏览器，也让 count 与 offset 的代价可控。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 单条字典的选项上限，与 SPU 级属性的 50 项上限同口径。 */
  private static final int MAX_OPTIONS = 50;

  private static final String FROM =
      " from attribute_definition d where d.spu_id is null";

  private final DSLContext sql;
  private final CategoryTreeService categories;

  public AttributeDictionaryService(DSLContext sql, CategoryTreeService categories) {
    this.sql = sql;
    this.categories = categories;
  }

  // ============================================================ 列表

  /**
   * 字典列表（真实分页：先 count 再 limit/offset）。
   *
   * <p>{@code enabledCount} 是**不受筛选条件影响的**启用字典总数：界面上把它放在筛选结果旁边，
   * 用户才知道「筛出来的 2 条」之外还有没有停用的条目。0 与「没查过」含义不同，所以单独返回。
   */
  public AttributeDefinitionPage list(AttributeDefinitionQuery query) {
    int page = query.page();
    int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    conditions(query, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " and " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*)" + FROM + sqlWhere, binds.toArray()).get(0, Long.class);
    long enabledCount = sql.fetchOne("select count(*)" + FROM + " and d.enabled").get(0, Long.class);

    // 决胜列 id 恒定追加：position 相同的行也不会在翻页边界上错位。
    var rows =
        sql.fetch(
            SELECT_COLUMNS
                + FROM
                + sqlWhere
                + " order by d.position,d.code,d.id limit ? offset ?",
            append(binds, size, (long) page * size).toArray());
    return new AttributeDefinitionPage(
        rows.stream().map(this::toRow).toList(), total, page, size, enabledCount);
  }

  private static final String SELECT_COLUMNS =
      "select d.id,d.code,d.name,d.data_type,d.value_mode,d.usage,d.multiple,d.required,"
          + "d.filterable,d.variant_axis,d.enabled,d.unit,d.group_name,d.position,d.version,"
          + "d.created_at,d.updated_at,"
          + "(select count(*) from attribute_option o where o.tenant_id=d.tenant_id"
          + "  and o.attribute_id=d.id) as option_count,"
          + "(select count(*) from attribute_option o where o.tenant_id=d.tenant_id"
          + "  and o.attribute_id=d.id and o.enabled) as enabled_option_count,"
          + "(select count(*) from category_attribute_rule r where r.tenant_id=d.tenant_id"
          + "  and r.attribute_id=d.id) as category_rule_count,"
          // SPU 级定义与字典条目不在同一个命名空间里（编码唯一性按作用域各管各的），
          // 所以这里只能按 code 对齐统计「有多少商品已经建了同编码的属性」。
          + "(select count(*) from attribute_definition u where u.tenant_id=d.tenant_id"
          + "  and u.spu_id is not null and u.code=d.code) as spu_usage_count";

  private void conditions(
      AttributeDefinitionQuery query, List<String> where, List<Object> binds) {
    if (query.keyword() != null && !query.keyword().isBlank()) {
      String escaped = query.keyword().replace("!", "!!").replace("%", "!%").replace("_", "!_");
      where.add("(d.code ilike ? escape '!' or d.name ilike ? escape '!')");
      binds.add("%" + escaped + "%");
      binds.add("%" + escaped + "%");
    }
    if (query.dataType() != null && !query.dataType().isBlank()) {
      where.add("d.data_type=?");
      binds.add(query.dataType().trim());
    }
    if (query.usage() != null && !query.usage().isBlank()) {
      where.add("d.usage=?");
      binds.add(query.usage().trim());
    }
    if (query.filterable() != null) {
      where.add("d.filterable=?");
      binds.add(query.filterable());
    }
    if (query.enabled() != null) {
      where.add("d.enabled=?");
      binds.add(query.enabled());
    }
    if (query.groupName() != null && !query.groupName().isBlank()) {
      where.add("d.group_name=?");
      binds.add(query.groupName().trim());
    }
  }

  private AttributeDefinitionRow toRow(Record r) {
    return new AttributeDefinitionRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("data_type", String.class),
        r.get("value_mode", String.class),
        r.get("usage", String.class),
        Boolean.TRUE.equals(r.get("multiple", Boolean.class)),
        Boolean.TRUE.equals(r.get("required", Boolean.class)),
        Boolean.TRUE.equals(r.get("filterable", Boolean.class)),
        Boolean.TRUE.equals(r.get("variant_axis", Boolean.class)),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("unit", String.class),
        r.get("group_name", String.class),
        r.get("position", Integer.class),
        r.get("option_count", Long.class),
        r.get("enabled_option_count", Long.class),
        r.get("category_rule_count", Long.class),
        r.get("spu_usage_count", Long.class),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(more));
    return all;
  }

  // ============================================================ 概览

  /**
   * 字典概览：规模、选项、类目规则，以及几个治理缺口指标。
   *
   * <p>{@code spuLevelDefinitionCount} 与 {@code dictionaryAttributeCount} 分开报：
   * V11 之后两者同表，不分开就会把「本租户定义了几条字典」和「多少商品各自建了属性」混成一个数。
   *
   * <p>{@code categoryWithoutRuleCount} 是缺口：已有商品的类目里，一个属性要求都没配的数量——
   * 这些类目的录入表单不会做必填校验，运营容易误以为「配过就等于全局生效」。
   */
  public AttributeDictionaryOverview overview() {
    var head =
        sql.fetchOne(
            "select count(*) as all_attributes,count(*) filter (where d.enabled) as enabled_attributes,"
                + "count(*) filter (where d.filterable) as filterable_attributes,"
                + "count(*) filter (where d.usage='SPECIFICATION') as spec_attributes"
                + FROM);
    long options =
        sql.fetchOne(
                "select count(*) from attribute_option o join attribute_definition d"
                    + " on d.tenant_id=o.tenant_id and d.id=o.attribute_id where d.spu_id is null")
            .get(0, Long.class);
    long disabledOptions =
        sql.fetchOne(
                "select count(*) from attribute_option o join attribute_definition d"
                    + " on d.tenant_id=o.tenant_id and d.id=o.attribute_id"
                    + " where d.spu_id is null and not o.enabled")
            .get(0, Long.class);
    long rules =
        sql.fetchOne("select count(*) from category_attribute_rule").get(0, Long.class);
    long spuLevel =
        sql.fetchOne("select count(*) from attribute_definition where spu_id is not null")
            .get(0, Long.class);
    long categoriesWithoutRule =
        sql.fetchOne(
                "select count(*) from (select p.category from spu p where btrim(p.category)<>''"
                    + " and not exists (select 1 from category_attribute_rule r"
                    + " where r.tenant_id=p.tenant_id and r.category=p.category)"
                    + " group by p.category) t")
            .get(0, Long.class);
    var top =
        sql.fetch(
                "select d.id,d.code,d.name,"
                    + "(select count(*) from category_attribute_rule r"
                    + "  where r.tenant_id=d.tenant_id and r.attribute_id=d.id) as category_rule_count"
                    + " from attribute_definition d where d.spu_id is null"
                    + " order by category_rule_count desc,d.code limit 8")
            .map(
                r ->
                    new AttributeUsageCount(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class),
                        r.get("category_rule_count", Long.class)))
            .stream()
            .toList();
    return new AttributeDictionaryOverview(
        head.get("all_attributes", Long.class),
        head.get("enabled_attributes", Long.class),
        options,
        disabledOptions,
        rules,
        spuLevel,
        head.get("filterable_attributes", Long.class),
        head.get("spec_attributes", Long.class),
        categoriesWithoutRule,
        top,
        "字典条目与商品的 SPU 级属性定义同表但作用域不同：这里统计的都是租户级字典（spu_id 为空）。");
  }

  // ============================================================ 保存与删除

  /** 取字典行；SPU 级定义或别的租户的行在这里表现为「不存在或没有访问权限」。 */
  private Record dictionaryRow(UUID id) {
    var row = sql.fetchOne("select * from attribute_definition where id=? and spu_id is null", id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  /**
   * 新建或更新字典条目（id 为空即新建），走 version 乐观锁。
   *
   * <p>更新**不接管选项集合**：选项有独立的子资源（增 / 改 / 停用 / 删除）。
   * 把选项集合塞进 PUT 会让「保存定义」顺手删掉几个选项，而选项一旦被商品取用过就删不动——
   * 那种失败发生在用户以为只是改了个名字之后，是最难排查的一类。
   */
  public AttributeDefinitionSaved save(
      AccessService.Access actor, UUID id, AttributeDefinitionInput input) {
    List<String> options = input.options() == null ? List.of() : input.options();
    String problem =
        firstNonNull(
            codeProblem(input.code()),
            inVocabularyProblem(input.dataType(), DATA_TYPES, "属性数据类型"),
            inVocabularyProblem(input.valueMode(), VALUE_MODES, "取值模式"),
            inVocabularyProblem(input.usage(), USAGES, "属性用途"),
            optionsProblem(input.dataType(), options),
            variantAxisProblem(
                input.variantAxis(),
                input.dataType(),
                input.valueMode(),
                input.multiple(),
                input.required(),
                input.usage()));
    CatalogService.check(problem == null, problem);
    CatalogService.check(!input.name().trim().isEmpty(), "属性名称不能为空");
    CatalogService.check(options.size() <= MAX_OPTIONS, "单个属性最多配置 " + MAX_OPTIONS + " 个选项");
    String code = normalizeCode(input.code());
    String groupName =
        input.groupName() == null || input.groupName().trim().isEmpty()
            ? suggestGroupName(input.usage())
            : input.groupName().trim();

    if (id == null) {
      if (sql.fetchOne("select id from attribute_definition where spu_id is null and code=?", code)
          != null)
        throw conflict("属性编码已存在：" + code);
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into attribute_definition(id,tenant_id,spu_id,code,name,data_type,value_mode,"
                + "multiple,required,variant_axis,unit,group_name,position,usage,filterable,enabled)"
                + " values(?,?,null,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            newId,
            actor.tenantId(),
            code,
            input.name().trim(),
            input.dataType(),
            input.valueMode(),
            input.multiple(),
            input.required(),
            input.variantAxis(),
            input.unit() == null ? "" : input.unit().trim(),
            groupName,
            input.position(),
            input.usage(),
            input.filterable(),
            input.enabled());
      } catch (DataAccessException e) {
        throw conflict("属性编码已被并发创建或占用，请刷新后重试");
      }
      replaceOptionsOnCreate(actor.tenantId(), newId, options);
      audit(actor, newId, "AttributeDefinitionSaved");
      return new AttributeDefinitionSaved(newId, 0, options.size());
    }

    var row = dictionaryRow(id);
    CatalogService.check(
        row.get("code", String.class).equals(code),
        "属性编码建后不可修改：商品的 SPU 级属性与规格轴都按同 code 对齐这个字典条目，"
            + "改编码会让已有对齐在下一次写入时报「不一致」。要改请新建一条字典条目");
    CatalogService.check(
        row.get("data_type", String.class).equals(input.dataType()),
        "属性数据类型建后不可修改：已存在的属性值与类目取值范围都是按这个类型解析的。"
            + "要改请新建一条字典条目");
    CatalogService.check(
        row.get("variant_axis", Boolean.class).equals(input.variantAxis()),
        "是否规格轴建后不可修改：规格轴要满足「必填单选枚举 + 用途=规格」的固定形态，"
            + "在已有属性值的条目上改这个开关会让取值模式自相矛盾");
    int updated;
    try {
      updated =
          sql.execute(
              "update attribute_definition set name=?,value_mode=?,multiple=?,required=?,"
                  + "usage=?,filterable=?,unit=?,group_name=?,position=?,enabled=?,"
                  + "version=version+1,updated_at=now() where id=? and version=?",
              input.name().trim(),
              input.valueMode(),
              input.multiple(),
              input.required(),
              input.usage(),
              input.filterable(),
              input.unit() == null ? "" : input.unit().trim(),
              groupName,
              input.position(),
              input.enabled(),
              id,
              input.version());
    } catch (DataAccessException e) {
      throw conflict("属性定义写入失败，请刷新后重试");
    }
    if (updated != 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "属性定义已被他人修改，请刷新后重试");
    long version =
        sql.fetchOne("select version from attribute_definition where id=?", id)
            .get("version", Long.class);
    audit(actor, id, "AttributeDefinitionSaved");
    // 选项数上限是 MAX_OPTIONS(50)，塞进 int 是安全的；这里显式收窄，避免留下隐式窄化转换。
    return new AttributeDefinitionSaved(
        id, version, Math.toIntExact(optionCount(actor.tenantId(), id)));
  }

  /** 新建时一次性铺好选项；之后的增删改走选项子资源。 */
  private void replaceOptionsOnCreate(UUID tenantId, UUID attributeId, List<String> options) {
    for (String option : options)
      sql.execute(
          "insert into attribute_option(tenant_id,attribute_id,value,position,enabled)"
              + " values(?,?,?,?,?)",
          tenantId,
          attributeId,
          option.trim(),
          0,
          true);
  }

  private long optionCount(UUID tenantId, UUID attributeId) {
    return sql.fetchOne(
            "select count(*) from attribute_option where tenant_id=? and attribute_id=?",
            tenantId,
            attributeId)
        .get(0, Long.class);
  }

  /**
   * 删除字典条目。
   *
   * <p>仍被规格轴引用的条目删不动：那会让那些 SPU 的规格凭空消失，而它们的 SKU 上还留着
   * 按这条轴算出的 {@code variant_signature}。正确做法是停用。
   *
   * <p>类目规则会随条目一起删掉（级联）：规则是「这条属性的要求」，属性没了要求就没有意义，
   * 留着会成为指向空处的死规则。
   */
  public void delete(AccessService.Access actor, UUID id, long version) {
    var row = dictionaryRow(id);
    if (row.get("version", Long.class) != version)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "属性定义已被他人修改，请刷新后重试");
    String code = row.get("code", String.class);
    long axes =
        sql.fetchOne("select count(*) from spu_spec_axis where attribute_definition_id=?", id)
            .get(0, Long.class);
    CatalogService.check(
        axes == 0,
        "属性「" + code + "」仍被 " + axes + " 个商品的规格轴引用，不能删除；"
            + "请改为停用，停用只挡住新建使用，已有的规格与取值仍可读");
    long spuUsage =
        sql.fetchOne(
                "select count(*) from attribute_definition where spu_id is not null and code=?",
                code)
            .get(0, Long.class);
    CatalogService.check(
        spuUsage == 0,
        "属性「" + code + "」已被 " + spuUsage + " 个商品用在 SPU 级属性定义上，不能删除；"
            + "请改为停用");
    sql.execute("delete from attribute_definition where id=? and spu_id is null", id);
    audit(actor, id, "AttributeDefinitionDeleted");
  }

  // ============================================================ 属性选项（值源）

  /**
   * 选项列表，每项带「被商品取用次数」与「被多少条类目规则列为可选值」。
   *
   * <p>两个计数决定界面上给「删除」还是给「停用」，必须在删除前就能看见，
   * 不能等点了删除才被拒绝。合成字段 {@code inUse} 给界面直接判断用。
   */
  public List<AttributeOptionRow> options(UUID attributeId) {
    dictionaryRow(attributeId);
    return sql.fetch(
            "select o.value,o.position,o.enabled,o.created_at,"
                + "(select count(*) from attribute_value_item i"
                + "  where i.tenant_id=o.tenant_id and i.attribute_id=o.attribute_id"
                + "  and i.option_value=o.value) as product_value_count,"
                + "(select count(*) from category_attribute_option c"
                + "  where c.tenant_id=o.tenant_id and c.attribute_id=o.attribute_id"
                + "  and c.option_value=o.value) as category_rule_count"
                + " from attribute_option o where o.attribute_id=? order by o.position,o.value",
            attributeId)
        .stream()
        .map(
            r -> {
              long productValueCount = r.get("product_value_count", Long.class);
              long categoryRuleCount = r.get("category_rule_count", Long.class);
              return new AttributeOptionRow(
                  r.get("value", String.class),
                  r.get("position", Integer.class),
                  Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
                  productValueCount,
                  categoryRuleCount,
                  productValueCount + categoryRuleCount > 0,
                  instant(r.get("created_at", OffsetDateTime.class)));
            })
        .toList();
  }

  /** 新增或登记一个选项（同值重复提交按幂等处理，更新位置与启用状态）。 */
  public AttributeOptionSaved addOption(
      AccessService.Access actor, UUID attributeId, AttributeOptionInput input) {
    var definition = dictionaryRow(attributeId);
    CatalogService.check(
        "OPTION".equals(definition.get("data_type", String.class)),
        "只有枚举属性（OPTION）可以配置选项集合");
    String value = input.value().trim();
    CatalogService.check(!value.isEmpty(), "选项值不能为空");
    CatalogService.check(value.length() <= 100, "选项值最多 100 个字符");
    // 幂等重复登记（改位置/启用状态）不算新增，所以只在真的要插入新值时才查上限。
    boolean exists =
        sql.fetchOne(
                "select 1 from attribute_option where attribute_id=? and value=?",
                attributeId,
                value)
            != null;
    if (!exists)
      CatalogService.check(
          optionCount(actor.tenantId(), attributeId) < MAX_OPTIONS,
          "单个属性最多配置 " + MAX_OPTIONS + " 个选项");
    try {
      sql.execute(
          "insert into attribute_option(tenant_id,attribute_id,value,position,enabled)"
              + " values(?,?,?,?,?) on conflict (tenant_id,attribute_id,value)"
              + " do update set position=excluded.position,enabled=excluded.enabled",
          actor.tenantId(),
          attributeId,
          value,
          input.position(),
          input.enabled());
    } catch (DataAccessException e) {
      throw conflict("选项写入失败，请刷新后重试");
    }
    audit(actor, attributeId, "AttributeOptionSaved");
    return new AttributeOptionSaved(attributeId, value, input.position(), input.enabled());
  }

  /** 更新一个选项的位置与启用状态（值本身不可改——它已经被取值引用，改值等于换了一个属性值）。 */
  public AttributeOptionSaved updateOption(
      AccessService.Access actor, UUID attributeId, String value, AttributeOptionInput input) {
    dictionaryRow(attributeId);
    String target = value.trim();
    int updated =
        sql.execute(
            "update attribute_option set position=?,enabled=? where attribute_id=? and value=?",
            input.position(),
            input.enabled(),
            attributeId,
            target);
    if (updated == 0) throw CatalogService.missing();
    audit(actor, attributeId, "AttributeOptionSaved");
    return new AttributeOptionSaved(attributeId, target, input.position(), input.enabled());
  }

  /**
 * 删除选项。
 *
   * <p>两类引用都要先拦：已被商品取用过的不能删（历史商品需要它来解析，只能停用）；
   * 已被类目规则列为可选值的也不能删——删掉会连带把那些规则改掉，而用户只是想删一个值。
   * 正确顺序是先改规则、再删选项。
   *
   * <p>「停用不删除」是蓝图对属性选项的硬要求：历史单据还要能展示那个值。
   */
  public void deleteOption(AccessService.Access actor, UUID attributeId, String value) {
    dictionaryRow(attributeId);
    String target = value == null ? "" : value.trim();
    long used =
        sql.fetchOne(
                "select count(*) from attribute_value_item"
                    + " where attribute_id=? and option_value=?",
                attributeId,
                target)
            .get(0, Long.class);
    CatalogService.check(
        used == 0,
        "无法删除选项「" + target + "」：已有 " + used + " 处商品属性值取用了它。"
            + "历史商品需要它来解析，请改为停用该选项");
    long referenced =
        sql.fetchOne(
                "select count(*) from category_attribute_option"
                    + " where attribute_id=? and option_value=?",
                attributeId,
                target)
            .get(0, Long.class);
    CatalogService.check(
        referenced == 0,
        "无法删除选项「" + target + "」：有 " + referenced + " 条类目规则把它列为可选值。"
            + "删掉会连带改掉那些规则，请先在类目规则里移除它");
    int deleted =
        sql.execute(
            "delete from attribute_option where attribute_id=? and value=?", attributeId, target);
    if (deleted == 0) throw CatalogService.missing();
    audit(actor, attributeId, "AttributeOptionDeleted");
  }

  // ============================================================ 类目属性规则

  /** 类目属性规则列表；category 留空返回全部。 */
  public List<CategoryAttributeRule> categoryRules(String category, UUID onlyAttributeId) {
    StringBuilder where = new StringBuilder(" where 1=1");
    List<Object> binds = new ArrayList<>();
    if (category != null && !category.isBlank()) {
      where.append(" and r.category=?");
      binds.add(category.trim());
    }
    if (onlyAttributeId != null) {
      where.append(" and r.attribute_id=?");
      binds.add(onlyAttributeId);
    }
    var rows =
        sql.fetch(
            "select r.category,r.attribute_id,r.required,r.min_value,r.max_value,r.position,"
                + "r.created_at,d.code,d.name,d.data_type,"
                + "(select count(*) from spu p where p.tenant_id=r.tenant_id"
                + "  and p.category=r.category) as product_count"
                + " from category_attribute_rule r"
                + " join attribute_definition d"
                + "  on d.tenant_id=r.tenant_id and d.id=r.attribute_id"
                + where
                + " order by r.category,r.position,d.code",
            binds.toArray());
    List<CategoryAttributeRule> rules =
        rows.stream().map(this::toCategoryRule).collect(java.util.stream.Collectors.toList());
    for (int i = 0; i < rules.size(); i++) {
      CategoryAttributeRule rule = rules.get(i);
      rules.set(i, withAllowedOptions(rule));
    }
    return rules;
  }

  private CategoryAttributeRule toCategoryRule(Record r) {
    return new CategoryAttributeRule(
        r.get("category", String.class),
        r.get("attribute_id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("data_type", String.class),
        Boolean.TRUE.equals(r.get("required", Boolean.class)),
        r.get("min_value", BigDecimal.class),
        r.get("max_value", BigDecimal.class),
        r.get("position", Integer.class),
        r.get("product_count", Long.class),
        List.of(),
        instant(r.get("created_at", OffsetDateTime.class)));
  }

  /** 补上枚举属性的可选值子集。非枚举属性原样返回，不去查那一张表。 */
  private CategoryAttributeRule withAllowedOptions(CategoryAttributeRule rule) {
    if (!"OPTION".equals(rule.dataType())) return rule;
    List<String> allowed =
        sql.fetch(
                "select option_value from category_attribute_option"
                    + " where attribute_id=? and category=? order by option_value",
                rule.attributeId(),
                rule.category())
            .getValues("option_value", String.class);
    if (allowed.isEmpty()) return rule;
    return new CategoryAttributeRule(
        rule.category(),
        rule.attributeId(),
        rule.attributeCode(),
        rule.attributeName(),
        rule.dataType(),
        rule.required(),
        rule.minValue(),
        rule.maxValue(),
        rule.position(),
        rule.productCount(),
        allowed,
        rule.createdAt());
  }

  /**
   * 新增或更新一条类目属性规则（必填 + 可选范围）。
   *
   * <p>范围形态按数据类型分流：枚举属性用 {@code allowedOptions} 收窄取值子集，
   * 数值属性用 {@code minValue}/{@code maxValue}。两者互斥，规则里写错形态会表达出一个
   * 永远匹配不上的范围，所以在这里拦住而不是留给下游。
   */
  public CategoryAttributeRuleSaved saveCategoryRule(
      AccessService.Access actor, UUID attributeId, CategoryAttributeRuleInput input) {
    var definition = dictionaryRow(attributeId);
    String dataType = definition.get("data_type", String.class);
    String category = input.category().trim();
    CatalogService.check(!category.isBlank(), "类目不能为空");
    List<String> allowed =
        input.allowedOptions() == null ? List.of() : input.allowedOptions().stream().map(String::trim).toList();

    String rangeProblem = rangeProblem(dataType, input.minValue(), input.maxValue());
    CatalogService.check(rangeProblem == null, rangeProblem);
    CatalogService.check(
        "OPTION".equals(dataType) || allowed.isEmpty(),
        "可选值范围只对枚举属性（OPTION）有意义；数值属性请改用取值上下限");
    CatalogService.check(
        !("OPTION".equals(dataType)) || (input.minValue() == null && input.maxValue() == null),
        "枚举属性的可选值范围请用可选值子集表达，不要同时设置取值上下限");
    CatalogService.check(
        new java.util.LinkedHashSet<>(allowed).size() == allowed.size(), "可选值不可重复");

    // 可选值必须是属性值源里已有的值：复合外键是最后一道，这里先给出可读原因。
    for (String option : allowed) {
      boolean exists =
          sql.fetchOne(
                      "select 1 from attribute_option where attribute_id=? and value=?",
                      attributeId,
                      option)
                  != null;
      CatalogService.check(
          exists, "可选值「" + option + "」不在该属性的选项集合里，请先在选项集合中登记它");
    }

    // V14 兑现点：category_id 是 NOT NULL 外键，而本方法的入参仍是类目**文本**（已交付 API 语义不变）。
    // 文本 → 类目主键的解析与按需建节点由 CategoryTreeService 承担，本模块是唯一写入口。
    UUID categoryId = categories.resolveOrCreateCategoryId(actor, category);

    sql.execute(
        "insert into category_attribute_rule(tenant_id,category,category_id,attribute_id,required,"
            + "min_value,max_value,position) values(?,?,?,?,?,?,?,?)"
            + " on conflict (tenant_id,category,attribute_id)"
            + " do update set required=excluded.required,min_value=excluded.min_value,"
            + "max_value=excluded.max_value,position=excluded.position",
        actor.tenantId(),
        category,
        categoryId,
        attributeId,
        input.required(),
        input.minValue(),
        input.maxValue(),
        input.position());
    sql.execute(
        "delete from category_attribute_option where tenant_id=? and category=? and attribute_id=?",
        actor.tenantId(),
        category,
        attributeId);
    for (String option : allowed)
      sql.execute(
          "insert into category_attribute_option(tenant_id,category,category_id,attribute_id,option_value)"
              + " values(?,?,?,?,?)",
          actor.tenantId(),
          category,
          categoryId,
          attributeId,
          option);
    audit(actor, attributeId, "CategoryAttributeRuleSaved");
    return new CategoryAttributeRuleSaved(category, attributeId, allowed.size());
  }

  /**
   * 删除类目属性规则。
   *
   * <p>删规则不改动任何商品已录入的属性值——规则是「新建录入时的准入条件」，
   * 已经录入的数据本来就合法，被一条新规则连坐改掉会凭空改掉既有商品的事实。
   */
  public void deleteCategoryRule(AccessService.Access actor, UUID attributeId, String category) {
    dictionaryRow(attributeId);
    String target = category == null ? "" : category.trim();
    int deleted =
        sql.execute(
            "delete from category_attribute_rule where tenant_id=? and category=? and attribute_id=?",
            actor.tenantId(),
            target,
            attributeId);
    if (deleted == 0) throw CatalogService.missing();
    audit(actor, attributeId, "CategoryAttributeRuleDeleted");
  }

  // ============================================================ 工具

  private static String firstNonNull(String... values) {
    for (String value : values) if (value != null) return value;
    return null;
  }

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(), actor.tenantId(), actor.userId(), resourceId, action);
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}