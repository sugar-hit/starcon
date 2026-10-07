package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 3.2.2.2 SKU 属性覆写：对单个 SKU 覆写 SPU 公共属性值、显式清空、取消覆写，
 * 并给出解析后的**最终有效值**与来源标注。
 *
 * <h2>四态来源怎么来的</h2>
 *
 * 覆写行在 V1 就已经是 {@code attribute_value} 里 sku_id 非空的那一行，本模块不另建表
 * （理由见 V13 迁移的模型决策）。四种来源在数据层与 API 层都是**可区分的物理形态**，
 * 不靠读的人去猜：
 *
 * <ul>
 *   <li>{@code SKU_OVERRIDE} —— 一行 {@code state='SET'} 加至少一条取值。
 *   <li>{@code SKU_CLEARED} —— 一行 {@code state='CLEAR'}，不带任何取值。有效值就是空，
 *       且**不退回公共值**。
 *   <li>{@code SPU_COMMON} —— SKU 侧**没有这一行**（{@code INHERIT}），取公共值。
 *   <li>{@code UNSET} —— SKU 侧没有这一行，公共侧也没有值。
 * </ul>
 *
 * <p>关键点：「未设置」与「显式清空」的分界是**这一行在不在**，不是某个值是不是 NULL。
 * 清空之后有效值就是空且不退回公共值；若把两者都用 NULL 表达，运营主动抹掉一条属性
 * 就会长得和「这条属性没填过」一样——那正是蓝图原文点名要区分的那件事。
 *
 * <p>解析一律走 {@link EffectiveValues} 这一个实现：3.1.2.2 的详情页与本模块共用，
 * 避免两处对「公共值 + 覆写」的理解漂移。本模块**不重写**解析，只补上本模块需要的
 * 类目必填与可改写性判定。
 *
 * <h2>类目必填校验怎么判「显式清空」</h2>
 *
 * 必填只看**解析后的有效值**，不看来源：{@code UNSET} 与 {@code SKU_CLEARED} 的有效值都是空，
 * 因此对必填属性两者**同样被拒绝**。取舍理由：
 *
 * <ul>
 *   <li>类目规则里 {@code required=true} 的含义是「这个类目下的商品必须有这条属性」。
 *       显式清空恰恰是「这个 SKU 确实没有该属性」的主动断言，放行它等于让必填规则能被
 *       最刻意的那一个操作绕过——那条规则就没有意义了。
 *   <li>所以「显式清空」的价值落在**非必填**属性上：容量、保质期这类只对部分变体成立的参数，
 *       正是需要「这个 SKU 没有」而不是「忘了填」的地方。
 *   <li>已交付的 3.1.2.2 详情页文案也是这个口径：「清空一个必填属性同样会进补全清单」。
 * </ul>
 *
 * <p>必填口径 = 属性定义自身的 {@code required} **或** 该 SPU 所属类目在
 * {@code category_attribute_rule}（V11）里把这条属性标成必填。
 *
 * <p>校验是**整 SKU 范围**的：只要还有必填属性没有生效值，任何一次覆写 / 清空 / 取消覆写都被拒绝，
 * 并回传缺失清单。理由是这里的「保存」意味着"这个 SKU 的属性档案到此为止"——
 * 允许一边缺必填一边保存，界面就会长期停在一个没保存完的状态，却没人说得清还差哪几项。
 *
 * <h2>为什么不推进 sku.version</h2>
 *
 * {@code sku} 表的写入所有者是 3.1.2.x，{@code sku.version} 是那些模块的乐观锁凭据。
 * 本模块改一个属性值就顶一次版本号，会让并发编辑者在 SKU 资料、编码、包装这些无关区域
 * 也全部撞上版本冲突。因此本模块**只读 sku.version**，并发控制交给
 * {@code attribute_value} 的唯一约束（同一 SKU 的同一属性不可能有两行覆写）加行级覆盖写。
 *
 * <h2>事件的 aggregate 仍是 SPU</h2>
 *
 * 覆写事件沿用 {@code CatalogService.event}，aggregate_id 仍是 SPU，与已交付的属性 / SKU 变更
 * 同口径，下游 04 刊登按 aggregate_id 订阅时不必为属性覆写再开一路。
 */
@Service
public class SkuAttributeOverrideService {

  /** 类目必填校验失败。带结构化缺失清单，由 ApiErrorHandler 渲染成 400 + missing。 */
  public static class RequiredMissingException extends RuntimeException {
    private final transient List<SkuOverrideMissing> missing;

    public RequiredMissingException(List<SkuOverrideMissing> missing) {
      super("类目必填属性缺少生效值，未保存");
      this.missing = List.copyOf(missing);
    }

    public List<SkuOverrideMissing> missing() {
      return missing;
    }
  }

  private static final String NOTE =
      "有效值来源四态：SKU_OVERRIDE（SKU 覆写）/ SKU_CLEARED（SKU 已显式清空，不退回公共值）"
          + " / SPU_COMMON（继承 SPU 公共值）/ UNSET（从未设置）。"
          + "「显式清空」与「未设置」是两种状态：前者有一行 state=CLEAR 的覆写记录，后者 SKU 侧没有记录。"
          + "类目必填校验只看解析后的有效值，因此必填属性既不能保持未设置，也不能被显式清空。";

  /**
   * 有效值解析的主查询。
   *
   * <p>形状与 {@code SkuDetailService.effectiveAttributes} 一致，差别只在本模块多要三样：
   * 覆写行的系统时间、该类目的必填要求、以及启用中的选项。取值明细用标量子查询聚成数组，
   * 避免把每个属性的每个取值拉成一行后在内存里重组。
   *
   * <p>绑定顺序即出现顺序：类目 → skuId（三处）→ spuId。
   */
  private static final String SELECT =
      "select d.id,d.code,d.name,d.data_type,d.value_mode,d.multiple,d.required,d.variant_axis,"
          + "d.unit,d.group_name,d.position,d.enabled,"
          + "cv.state as common_state,"
          + "(select array_agg(coalesce(i.text_value,i.decimal_value::text,i.boolean_value::text,"
          + "i.date_value::text,i.option_value) order by i.ordinal)"
          + "  from attribute_value_item i where i.value_id=cv.id) as common_values,"
          + "sv.state as sku_state,sv.updated_at as sku_updated_at,"
          + "(select array_agg(coalesce(i.text_value,i.decimal_value::text,i.boolean_value::text,"
          + "i.date_value::text,i.option_value) order by i.ordinal)"
          + "  from attribute_value_item i where i.value_id=sv.id) as sku_values,"
          + "(select array_agg(o.value order by o.value) from attribute_option o"
          + "  where o.attribute_id=d.id) as options,"
          + "(select array_agg(o.value order by o.value) from attribute_option o"
          + "  where o.attribute_id=d.id and o.enabled) as enabled_options,"
          + "exists(select 1 from category_attribute_rule r"
          + "  where r.tenant_id=d.tenant_id and r.category=? and r.attribute_id=d.id and r.required)"
          + "  as category_required"
          + " from attribute_definition d"
          + " left join attribute_value cv on cv.tenant_id=d.tenant_id and cv.attribute_id=d.id"
          + "   and cv.sku_id is null"
          + " left join attribute_value sv on sv.tenant_id=d.tenant_id and sv.attribute_id=d.id"
          + "   and sv.sku_id=?"
          + " where d.tenant_id=(select tenant_id from sku where id=?) and d.spu_id=?"
          + " order by d.position,d.name,d.id";

  private final DSLContext sql;
  private final CatalogService catalog;

  public SkuAttributeOverrideService(DSLContext sql, CatalogService catalog) {
    this.sql = sql;
    this.catalog = catalog;
  }

  // ============================================================ 读取

  /**
   * 某个 SKU 的有效属性解析结果：逐属性给出最终有效值与来源。
   *
   * <p>只按 SKU 寻址，SPU 由 SKU 行反查——不要求调用方同时传两个 id，那样的组合可能出现
   * "SKU 属于 SPU-A、路径写的是 SPU-B"这种自相矛盾的调用，而 SKU 的归属本来就是唯一的。
   *
   * <p>{@code skuVersion} 只是回给界面显示这个 SKU 的版本，本模块不改它（见类注释）。
   */
  public SkuOverrideView view(UUID skuId) {
    var sku = skuRow(skuId);
    UUID spuId = sku.get("spu_id", UUID.class);
    var spu = catalog.productRow(spuId);
    String category = spu.get("category", String.class);

    List<SkuOverrideAttribute> attributes =
        sql.fetch(SELECT, category, skuId, skuId, spuId).stream()
            .map(SkuAttributeOverrideService::toAttribute)
            .toList();

    return new SkuOverrideView(
        skuId,
        sku.get("code", String.class),
        sku.get("name", String.class),
        sku.get("status", String.class),
        sku.get("version", Long.class),
        spuId,
        spu.get("code", String.class),
        spu.get("name", String.class),
        category,
        attributes,
        summary(attributes),
        NOTE);
  }

  private static SkuOverrideAttribute toAttribute(Record r) {
    String mode = r.get("value_mode", String.class);
    boolean definitionRequired = Boolean.TRUE.equals(r.get("required", Boolean.class));
    boolean categoryRequired = Boolean.TRUE.equals(r.get("category_required", Boolean.class));
    // 必填口径：定义自身要求，或该 SPU 所属类目要求。见类注释。
    boolean required = definitionRequired || categoryRequired;

    var resolved =
        EffectiveValues.resolve(
            mode,
            new EffectiveValues.Raw(r.get("common_state", String.class), strings(r, "common_values")),
            new EffectiveValues.Raw(r.get("sku_state", String.class), strings(r, "sku_values")),
            required);

    String blockReason =
        blockReason(mode, Boolean.TRUE.equals(r.get("enabled", Boolean.class)));
    return new SkuOverrideAttribute(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("data_type", String.class),
        mode,
        EffectiveValues.modeLabel(mode),
        Boolean.TRUE.equals(r.get("multiple", Boolean.class)),
        definitionRequired,
        categoryRequired,
        Boolean.TRUE.equals(r.get("variant_axis", Boolean.class)),
        r.get("unit", String.class),
        r.get("group_name", String.class),
        strings(r, "options"),
        resolved.overrideState(),
        resolved.source().code(),
        resolved.source().label(),
        resolved.rawValues(),
        resolved.effectiveValues(),
        resolved.inherited(),
        resolved.missingRequired(),
        blockReason == null,
        blockReason,
        instant(r.get("sku_updated_at", OffsetDateTime.class)));
  }

  /**
   * 可改写性：只有公共固定与已停用会挡住 SKU 侧赋值（覆写 / 清空）。
   *
   * <p>取消覆写不受这里限制——已停用或公共固定属性的覆写行也允许移除，否则
   * 「这条属性改不动」会连带变成「这条属性永远清不掉」。
   */
  private static String blockReason(String valueMode, boolean definitionEnabled) {
    if (EffectiveValues.COMMON_LOCKED.equalsIgnoreCase(valueMode))
      return "公共固定属性，取值只能来自 SPU 公共值，SKU 侧不能覆写也不能清空";
    if (!definitionEnabled)
      return "属性已停用：停用只挡住新建使用，已有取值仍会正常展示，但不能再赋新值；取消覆写仍允许";
    return null;
  }

  private static SkuOverrideSummary summary(List<SkuOverrideAttribute> attributes) {
    return new SkuOverrideSummary(
        attributes.size(),
        count(attributes, EffectiveValues.Source.SKU_OVERRIDE),
        count(attributes, EffectiveValues.Source.SKU_CLEARED),
        count(attributes, EffectiveValues.Source.SPU_COMMON),
        count(attributes, EffectiveValues.Source.UNSET),
        (int) attributes.stream().filter(SkuOverrideAttribute::missingRequired).count(),
        (int) attributes.stream().filter(a -> !a.overridable()).count());
  }

  private static int count(List<SkuOverrideAttribute> attributes, EffectiveValues.Source source) {
    return (int) attributes.stream().filter(a -> a.source().equals(source.code())).count();
  }

  // ============================================================ 写入

  /** 设置覆写值：走 SPU 侧同一套类型校验，并要求枚举取值属于该属性且处于启用状态。 */
  public SkuOverrideView set(
      AccessService.Access actor, UUID skuId, UUID attributeId, SkuOverrideInput input) {
    UUID spuId = skuRow(skuId).get("spu_id", UUID.class);
    var row = attributeRow(spuId, attributeId);
    var attribute = definition(row);
    requireOverridable(row, attribute);

    List<String> values = input.values();
    CatalogService.check(
        !values.isEmpty(),
        "覆写值不能为空：请填入要覆写的值，或改用「显式清空」表达这个 SKU 没有该属性");
    // 枚举取值先判「存在且启用」再交给 SPU 侧的类型校验。两步分开是为了把
    // 「不存在」与「已停用」两种拒绝说清楚——它们要用户做的事不一样。
    if ("OPTION".equals(attribute.dataType())) requireEnabledOptions(attribute, values);
    // 与 SPU 公共值、SKU 保存路径共用同一份校验（多值上限 / 数值精度 / 日期格式 / 选项归属）。
    CatalogService.validateValues(attribute, values, false);

    catalog.writeValue(actor.tenantId(), spuId, attributeId, skuId, "SET", values, attribute.dataType());
    catalog.event(actor, spuId, "SkuAttributeOverridden");
    return afterWrite(skuId);
  }

  /**
   * 显式清空：写一行 {@code state='CLEAR'}，有效值变空且**不退回公共值**。
   *
   * <p>必填属性在这里被拒（理由见类注释）。与「未设置」的差别在于这一行存在：
   * 之后再把它删掉就是「取消覆写」，会重新落回继承。
   */
  public SkuOverrideView clear(AccessService.Access actor, UUID skuId, UUID attributeId) {
    UUID spuId = skuRow(skuId).get("spu_id", UUID.class);
    var row = attributeRow(spuId, attributeId);
    var attribute = definition(row);
    requireOverridable(row, attribute);

    // 必填必须在写库之前拦：V1 的 validate_value_owner 触发器虽然也会拒 required，
    // 但它抛的是裸 SQL 异常，会被 ApiErrorHandler 映射成一句「编码或规格组合重复」的 409，
    // 用户完全看不出是自己要清空了一条必填属性。这里给可读的 400。
    String reason = requiredReason(spuId, attribute);
    CatalogService.check(reason == null, cannotClearMessage(attribute.name(), reason));

    catalog.writeValue(actor.tenantId(), spuId, attributeId, skuId, "CLEAR", List.of(), attribute.dataType());
    catalog.event(actor, spuId, "SkuAttributeCleared");
    return afterWrite(skuId);
  }

  /**
   * 删除覆写（取消覆写）：移除 SKU 侧那一行，回落到 SPU 公共值。
   *
   * <p>删除是**幂等**的：本来就没有覆写行时也返回当前解析结果，而不是报 404——
   * 「已经回到继承」和「本来就在继承」对用户是同一件事，没必要让界面显示一个错误。
   */
  public SkuOverrideView remove(AccessService.Access actor, UUID skuId, UUID attributeId) {
    UUID spuId = skuRow(skuId).get("spu_id", UUID.class);
    // 取消覆写不受 blockReason 限制：已停用或公共固定属性的覆写行也允许移除，
    // 否则「这条属性改不动」会连带变成「这条属性永远清不掉」。
    attributeRow(spuId, attributeId);
    sql.execute(
        "delete from attribute_value where tenant_id=? and attribute_id=? and sku_id=?",
        actor.tenantId(),
        attributeId,
        skuId);
    catalog.event(actor, spuId, "SkuAttributeOverrideRemoved");
    return afterWrite(skuId);
  }

  /**
   * 写完之后统一跑一次整 SKU 范围的必填校验。
   *
   * <p>校验在事务内完成：{@code AccessService.run} 用 TransactionTemplate 包裹，
   * 抛出的异常会回滚上面那次写入，因此不存在「写进去了但必填没过」的中间态。
   * 代价是多解析一次，换来的是不必把解析规则复制一份去做"预演"——
   * 预演与真实写入走两套逻辑，长期一定漂移。
   */
  private SkuOverrideView afterWrite(UUID skuId) {
    SkuOverrideView result = view(skuId);
    List<SkuOverrideMissing> missing = new ArrayList<>();
    for (var a : result.attributes())
      if (a.missingRequired())
        missing.add(
            new SkuOverrideMissing(
                a.attributeId(),
                a.code(),
                a.name(),
                a.source(),
                EffectiveValues.Source.SKU_CLEARED.code().equals(a.source())
                    ? "属性被显式清空：清空就是声明这个 SKU 没有该属性，必填属性不允许"
                    : "必填属性还没有生效值：既没有 SKU 覆写，SPU 公共侧也没有值"));
    if (!missing.isEmpty()) throw new RequiredMissingException(missing);
    return result;
  }

  // ============================================================ 守卫

  /** SKU 行；取不到即「不存在或没有访问权限」（RLS 已过滤跨租户）。 */
  private Record skuRow(UUID skuId) {
    var row = sql.fetchOne("select * from sku where id=?", skuId);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  /** SPU 级属性定义行；必须属于该 SPU，否则 404。租户级字典条目（spu_id 为空）在这里也是 404。 */
  private Record attributeRow(UUID spuId, UUID attributeId) {
    var row = sql.fetchOne("select * from attribute_definition where id=? and spu_id=?", attributeId, spuId);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  /**
   * 把定义行转成 SPU 侧校验用的 {@link Attribute}。
   *
   * <p>选项取**全部**（含停用）：类型校验据此能给出「不属于此商品」的既有措辞，
   * 启用与否由 {@link #requireEnabledOptions} 单独判，两者错误信息不混。
   */
  private Attribute definition(Record row) {
    UUID id = row.get("id", UUID.class);
    var options =
        sql.fetchOne(
            "select array_agg(o.value order by o.value) as options from attribute_option o"
                + " where o.attribute_id=?",
            id);
    return new Attribute(
        id,
        row.get("code", String.class),
        row.get("name", String.class),
        row.get("data_type", String.class),
        row.get("value_mode", String.class),
        Boolean.TRUE.equals(row.get("multiple", Boolean.class)),
        Boolean.TRUE.equals(row.get("required", Boolean.class)),
        Boolean.TRUE.equals(row.get("variant_axis", Boolean.class)),
        row.get("unit", String.class),
        row.get("group_name", String.class),
        strings(options, "options"),
        List.of());
  }

  private void requireOverridable(Record row, Attribute attribute) {
    String reason =
        blockReason(
            attribute.valueMode(), Boolean.TRUE.equals(row.get("enabled", Boolean.class)));
    CatalogService.check(reason == null, reason);
  }

  private static String cannotClearMessage(String name, String reason) {
    return "属性「"
        + name
        + "」是必填属性（"
        + reason
        + "），不能显式清空：清空就是声明这个 SKU 没有该属性。"
        + "请改为填入取值；要表达「这条属性对本 SKU 不适用」，请先在类目属性规则里取消它的必填";
  }

  /** 枚举取值必须属于该属性**且处于启用状态**：停用只挡住新建使用，已有取值照常可解析。 */
  private void requireEnabledOptions(Attribute attribute, List<String> values) {
    for (String value : values) {
      CatalogService.check(
          sql.fetchOne(
                  "select 1 from attribute_option where attribute_id=? and value=?",
                  attribute.id(),
                  value)
              != null,
          "属性「" + attribute.name() + "」没有选项「" + value + "」");
      CatalogService.check(
          sql.fetchOne(
                  "select 1 from attribute_option where attribute_id=? and value=? and enabled",
                  attribute.id(),
                  value)
              != null,
          "属性选项「"
              + value
              + "」已停用，不能再作为新的取值；停用只挡住新建使用，已有取值仍会正常解析");
    }
  }

  /**
   * 必填判定与理由。类目规则是 V11 的 {@code category_attribute_rule}，
   * {@code category} 仍是 {@code spu.category} 自由文本（与 V11 / V9 同口径，3.3.1.1 后改挂类目主键）。
   *
   * <p>不显式带 tenant 条件：该表的 RLS 策略已按 {@code app.current_tenant} 过滤，
   * 少写一个条件不会放宽可见性，写出来反而像是可以绕过策略。
   */
  private String requiredReason(UUID spuId, Attribute attribute) {
    if (attribute.required()) return "属性定义要求必填";
    String category = catalog.productRow(spuId).get("category", String.class);
    if (category == null || category.isBlank()) return null;
    var hit =
        sql.fetchOne(
            "select category from category_attribute_rule"
                + " where category=? and attribute_id=? and required",
            category,
            attribute.id());
    return hit == null ? null : "类目「" + category + "」的属性规则要求必填";
  }

  // ============================================================ 工具

  private static List<String> strings(Record row, String column) {
    var values = row.get(column, String[].class);
    return values == null ? List.of() : List.of(values);
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }
}