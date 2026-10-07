package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 3.2.1.2 属性选项管理：跨属性的选项总览、启用/停用生命周期、批量重排。
 *
 * <p>与 3.2.1.1（{@link AttributeDictionaryService}）的分工是「单属性 vs 跨属性」，
 * 不是「谁比谁新」：
 *
 * <ul>
 *   <li>3.2.1.1 管**一条字典定义**：它的选项集合、它的类目规则、它的增删改。
 *   <li>本类管**租户级选项面**：把所有枚举属性的选项拍平成一张表，回答
 *       「哪些选项被引用了」「哪些属性一个取值都没有」「这个选项还能删吗」。
 * </ul>
 *
 * <p>五条纪律：
 *
 * <ol>
 *   <li><b>引用计数是输入，不是输出</b>。蓝图把「该选项被多少商品使用」列为输入，
 *       所以 {@code deletable} 必须在**删除之前**就算出来给到界面，
 *       而不是点了删除才被拒绝。
 *   <li><b>不伪造计数</b>。{@code productValueCount} 现在恒为 0，因为
 *       {@code attribute_value} 的复合外键仍锁在 SPU 作用域（V11 结尾已注明）。
 *       那是数据库现状，界面照实呈现；{@code categoryRuleCount} 则是真实的。
 *   <li><b>停用 ≠ 删除</b>。有引用的选项只能停用：历史单据还要靠它解析那个值。
 *       停用挡住的是「不许再新建取用」，既有取值照常可读。
 *   <li><b>排序是覆盖式的</b>。批量重排在事务里逐条校验后统一写入，
 *       半套顺序比整批失败更难排查。
 *   <li><b>租户边界一次也不越</b>。所有读写都在 {@code AccessService.run} 打开的
 *       租户上下文里跑，跨租户的行由 RLS 挡掉，服务层再按 attributeId 做一次归属校验。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.attribute.read}，写 {@code catalog.attribute.update}，
 * 与属性域保持一致。
 */
@Service
public class AttributeOptionService {
  /** 聚合列表单页上限：与字典列表页同口径，防止一次拉爆浏览器。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 单个属性的选项上限，与 3.2.1.1 同口径；批量重排的提交量也按它封顶。 */
  private static final int MAX_OPTIONS = 50;

  /**
   * 排序白名单。
   *
   * <p>排序片段直接拼进 SQL，所以只允许命中这张表的固定值——任意列名出库就是注入面。
   * 每个片段都以 {@code o.attribute_id,o.value} 收尾作决胜列：
   * position 相同的行也不会在翻页边界上错位或重复。
   */
  private static final Map<String, String> ORDER_BY =
      Map.of(
          "POSITION", " order by o.attribute_id,o.position,o.value",
          "ATTRIBUTE", " order by d.position,d.code,o.position,o.value",
          "VALUE", " order by o.value",
          "CREATED_AT", " order by o.created_at desc,o.attribute_id,o.value",
          "REFERENCES", " order by reference_total desc,o.attribute_id,o.position,o.value");

  /** 默认排序：按属性分组、组内按 position。与字典页的阅读顺序一致。 */
  private static final String DEFAULT_SORT = "POSITION";

  /**
   * 引用总数（两个计数的合成）表达式。
   *
   * <p>它是 {@code productValueCount + categoryRuleCount} 的内联形式，因此
   * **主查询与 count 子查询都能用同一个片段**——排序与筛选都依赖它，
   * 两处各写一遍必然会出现「能筛不能排」这种对不上的情况。
   */
  private static final String REFERENCE_TOTAL =
      "(select count(*) from attribute_value_item i"
          + "  where i.tenant_id=o.tenant_id and i.attribute_id=o.attribute_id"
          + "  and i.option_value=o.value)"
          + " +(select count(*) from category_attribute_option c"
          + "  where c.tenant_id=o.tenant_id and c.attribute_id=o.attribute_id"
          + "  and c.option_value=o.value)";

  /**
   * 聚合视图的列。
   *
   * <p>两个引用计数都按 {@code tenant_id + attribute_id + option_value} 反查，
   * 各自命中 V12 新建的索引；引用来源与 3.2.1.1 完全一致，不另立口径。
   */
  private static final String SELECT_COLUMNS =
      "select o.attribute_id,d.code,d.name,o.value,o.position,o.enabled,o.created_at,"
          + "(select count(*) from attribute_value_item i"
          + "  where i.tenant_id=o.tenant_id and i.attribute_id=o.attribute_id"
          + "  and i.option_value=o.value) as product_value_count,"
          + "(select count(*) from category_attribute_option c"
          + "  where c.tenant_id=o.tenant_id and c.attribute_id=o.attribute_id"
          + "  and c.option_value=o.value) as category_rule_count,"
          + REFERENCE_TOTAL
          + " as reference_total"
          // 只列租户级字典条目（spu_id is null）：SPU 级定义的选项是商品自己的取值，不进字典总览。
          + " from attribute_option o"
          + " join attribute_definition d on d.tenant_id=o.tenant_id and d.id=o.attribute_id"
          + " where d.spu_id is null";

  /** 所有筛选与计数共用的 FROM：口径只有一处。 */
  private static final String FROM_OPTIONS =
      " from attribute_option o"
          + " join attribute_definition d on d.tenant_id=o.tenant_id and d.id=o.attribute_id"
          + " where d.spu_id is null";

  private static final String NOTE =
      "productValueCount 反映数据库现状：字典条目暂时不会被赋商品属性值（attribute_value 的复合外键"
          + "仍锁在 SPU 作用域），所以它现在恒为 0。categoryRuleCount 是真实的类目规则引用。";

  private final DSLContext sql;

  public AttributeOptionService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 聚合列表

  /**
   * 跨属性的选项总览（真实分页：先 count 再 limit/offset）。
   *
   * <p>{@code enabledCount} 与 {@code inUseCount} 不受筛选影响，理由见 {@link AttributeOptionPage}。
   */
  public AttributeOptionPage list(AttributeOptionQuery query) {
    int page = query.page();
    int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    conditions(query, where, binds);
    String sqlWhere = String.join(" and ", where);

    long total = count(sqlWhere, binds);
    long enabledCount = count("o.enabled", List.of());
    long inUseCount = count(REFERENCE_TOTAL + ">0", List.of());

    var rows =
        sql.fetch(
            SELECT_COLUMNS
                + (sqlWhere.isEmpty() ? "" : " and " + sqlWhere)
                + orderBy(query.sort())
                + " limit ? offset ?",
            append(binds, size, (long) page * size).toArray());
    return new AttributeOptionPage(
        rows.stream().map(this::toRow).toList(),
        total,
        page,
        size,
        enabledCount,
        inUseCount,
        NOTE);
  }

  /**
   * 总数与两个「不受筛选影响」的计数共用同一段条件。
   *
   * <p>count 子查询必须显式带上 {@link #REFERENCE_TOTAL}：引用筛选条件写在 where 里，
   * 不投影这个表达式的话 PostgreSQL 会报「列 reference_total 不存在」。
   */
  private long count(String where, List<Object> binds) {
    return sql.fetchOne(
            "select count(*) from (select " + REFERENCE_TOTAL + " as reference_total" + FROM_OPTIONS
                + (where.isEmpty() ? "" : " and " + where)
                + ") t",
            binds.toArray())
        .get(0, Long.class);
  }

  private void conditions(
      AttributeOptionQuery query, List<String> where, List<Object> binds) {
    if (query.keyword() != null && !query.keyword().isBlank()) {
      String escaped = query.keyword().replace("!", "!!").replace("%", "!%").replace("_", "!_");
      // 同时匹配选项值与归属属性：用户常常只记得「那个蓝色的选项」或「颜色那个属性」。
      where.add(
          "(o.value ilike ? escape '!' or d.code ilike ? escape '!' or d.name ilike ? escape '!')");
      binds.add("%" + escaped + "%");
      binds.add("%" + escaped + "%");
      binds.add("%" + escaped + "%");
    }
    if (query.attributeId() != null) {
      where.add("o.attribute_id=?");
      binds.add(query.attributeId());
    }
    if (query.enabled() != null) {
      where.add("o.enabled=?");
      binds.add(query.enabled());
    }
    // 引用状态是上面两个计数的合成，与 deletable 同源。
    // 这里必须写表达式而不是 SELECT 里的别名：PostgreSQL 的 WHERE 先于投影求值，
    // 引用输出别名会直接报「列 reference_total 不存在」。
    if (query.referenced() != null) {
      where.add(REFERENCE_TOTAL + (query.referenced() ? ">0" : "=0"));
    }
  }

  /** 未命中的排序值退回默认档，而不是把用户输入原样拼进 SQL。 */
  private static String orderBy(String sort) {
    if (sort == null) return ORDER_BY.get(DEFAULT_SORT);
    return ORDER_BY.getOrDefault(sort.trim().toUpperCase(java.util.Locale.ROOT), ORDER_BY.get(DEFAULT_SORT));
  }

  private AttributeOptionOverviewRow toRow(org.jooq.Record r) {
    long productValueCount = r.get("product_value_count", Long.class);
    long categoryRuleCount = r.get("category_rule_count", Long.class);
    boolean inUse = productValueCount + categoryRuleCount > 0;
    return new AttributeOptionOverviewRow(
        r.get("attribute_id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("value", String.class),
        r.get("position", Integer.class),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        productValueCount,
        categoryRuleCount,
        inUse,
        !inUse,
        instant(r.get("created_at", OffsetDateTime.class)));
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(more));
    return all;
  }

  // ============================================================ 启用 / 停用

  /**
   * 切换单个选项的启用状态。
   *
   * <p>停用永远允许：它只挡住「不许再新建取用」，既有取值照常可读。
   * 启用也不校验引用——被引用过的选项重新启用是正常的（运营误停用后要能改回来）。
   *
   * <p>启用时额外校验父属性是启用的：一个停用属性底下的「启用选项」在界面上永远选不到，
   * 留着这个状态只会让人以为取值可选。它是**可读拒绝**而不是静默纠正。
   */
  public AttributeOptionToggled toggle(
      AccessService.Access actor, UUID attributeId, String value, AttributeOptionToggleInput input) {
    assertDictionaryAttribute(attributeId);
    String target = value == null ? "" : value.trim();
    CatalogService.check(!target.isEmpty(), "选项值不能为空");

    var existing =
        sql.fetchOne(
            "select o.enabled,d.enabled as attribute_enabled"
                + " from attribute_option o"
                + " join attribute_definition d on d.tenant_id=o.tenant_id and d.id=o.attribute_id"
                + " where o.attribute_id=? and o.value=? and d.spu_id is null",
            attributeId,
            target);
    if (existing == null) throw CatalogService.missing();

    if (Boolean.TRUE.equals(input.enabled())) {
      CatalogService.check(
          Boolean.TRUE.equals(existing.get("attribute_enabled", Boolean.class)),
          "所属属性已停用：请先在属性字典里启用该属性，再启用它底下的选项");
    }

    int updated =
        sql.execute(
            "update attribute_option set enabled=? where attribute_id=? and value=?",
            input.enabled(),
            attributeId,
            target);
    if (updated != 1) throw CatalogService.missing();

    audit(actor, attributeId, "AttributeOptionToggled");
    var counts = referenceCounts(attributeId, target);
    boolean inUse = counts.productValueCount() + counts.categoryRuleCount() > 0;
    return new AttributeOptionToggled(
        attributeId, target, input.enabled(), inUse, !inUse);
  }

  // ============================================================ 批量重排

  /**
   * 批量重排：一次提交某个属性的若干选项及其新位置。
   *
   * <p>三条前置校验，缺一不可：
   *
   * <ol>
   *   <li>提交量不超过 {@link #MAX_OPTIONS}——防止拿它当批量写接口绕过单项上限。
   *   <li>同一批次里 value 不重复：重复会按顺序覆盖同一个值，用户以为排了两次。
   *   <li>每个 value 都真实属于该属性：否则会静默改到别人的选项上。
   * </ol>
   *
   * <p>整批在一个事务里写入（{@code AccessService.run} 已经开了事务），
   * 中途失败不会留下半套顺序。
   */
  public AttributeOptionOrderSaved reorder(AccessService.Access actor, AttributeOptionOrderInput input) {
    assertDictionaryAttribute(input.attributeId());
    List<AttributeOptionOrderItem> items = input.items();
    CatalogService.check(
        items.size() <= MAX_OPTIONS, "单个属性最多 " + MAX_OPTIONS + " 个选项，一次最多提交 " + MAX_OPTIONS + " 项");

    var distinct = new LinkedHashSet<String>();
    for (AttributeOptionOrderItem item : items) {
      String normalized = item.value() == null ? "" : item.value().trim();
      CatalogService.check(!normalized.isEmpty(), "选项值不能为空");
      distinct.add(normalized);
    }
    CatalogService.check(
        distinct.size() == items.size(), "同一次重排里出现了重复的选项值，请检查提交内容");

    for (AttributeOptionOrderItem item : items) {
      String normalized = item.value().trim();
      boolean exists =
          sql.fetchOne(
                  "select 1 from attribute_option o"
                      + " join attribute_definition d on d.tenant_id=o.tenant_id and d.id=o.attribute_id"
                      + " where o.attribute_id=? and o.value=? and d.spu_id is null",
                  input.attributeId(),
                  normalized)
              != null;
      CatalogService.check(
          exists,
          "选项「" + normalized + "」不属于该属性，请刷新后重试（它可能已被删除或停用后改名）");
    }

    int applied = 0;
    for (AttributeOptionOrderItem item : items) {
      applied +=
          sql.execute(
              "update attribute_option set position=? where attribute_id=? and value=? and position<>?",
              item.position(),
              input.attributeId(),
              item.value().trim(),
              item.position());
    }
    audit(actor, input.attributeId(), "AttributeOptionReordered");
    return new AttributeOptionOrderSaved(input.attributeId(), items.size(), applied);
  }

  // ============================================================ 工具

  /**
   * 确认 attributeId 指向**本租户的字典条目**。
   *
   * <p>与 3.2.1.1 的 {@code dictionaryRow} 同一口径：SPU 级定义（spu_id 有值）与别的租户的行
   * 都表现为「不存在或没有访问权限」，不区分两者——区分开等于告诉调用方「这个属性存在，
   * 只是你没权限」，那是信息泄露。
   */
  private void assertDictionaryAttribute(UUID attributeId) {
    boolean exists =
        sql.fetchOne("select 1 from attribute_definition where id=? and spu_id is null", attributeId)
            != null;
    if (!exists) throw CatalogService.missing();
  }

  /** 两个引用计数。删与停用的判定都以它为准，避免各处重算一遍口径。 */
  private ReferenceCounts referenceCounts(UUID attributeId, String value) {
    long product =
        sql.fetchOne(
                "select count(*) from attribute_value_item"
                    + " where attribute_id=? and option_value=?",
                attributeId,
                value)
            .get(0, Long.class);
    long rules =
        sql.fetchOne(
                "select count(*) from category_attribute_option"
                    + " where attribute_id=? and option_value=?",
                attributeId,
                value)
            .get(0, Long.class);
    return new ReferenceCounts(product, rules);
  }

  private record ReferenceCounts(long productValueCount, long categoryRuleCount) {}

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(), actor.tenantId(), actor.userId(), resourceId, action);
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }
}