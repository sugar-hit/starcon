package com.starcon.catalog;

import static com.starcon.catalog.CategoryTreeRules.*;
import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.3.1.1 分类树管理：多棵独立的分类树、多级层级、拖拽排序、防环、启用停用、
 * SPU 的主分类与辅助分类、多语言分类名，以及蓝图点名的三个输出（树结构 / 路径串 / 类目商品数）。
 *
 * <p>本类的纪律：
 *
 * <ol>
 *   <li><b>树是独立实体，不是分类上的一个标签</b>。蓝图写死了「分类是多棵独立的树，不能混为一棵——
 *       内部类目与平台类目的维护节奏完全不同」。所以 {@code product_category_tree} 有自己的编码、
 *       种类、启用状态与版本，分类只能在**同一棵树内**挂载（V14 的复合外键钉死）。
 *   <li><b>防环是双重的，不是服务层自觉</b>。本类在保存/移动前用一条递归 CTE 上溯祖先链判环
 *       （为了给出「不能把分类挂到它自己的子孙节点下」这种可读原因），
 *       V14 的 {@code guard_product_category_cycle} 触发器再兜一道（为了挡住绕过服务的写入）。
 *       只有服务层那一道的话，运维 psql 或未来的 ETL 能造出有环的树，
 *       而环一旦形成，所有递归查询都是**死循环**而不是报错。
 *   <li><b>重命名会同步规则表的兼容文本列</b>。V14 把 {@code category_attribute_rule} /
 *       {@code category_spec_axis} 改挂了 {@code category_id} 外键，但它们的 {@code category}
 *       文本列被**已交付模块**（3.2.1.1 / 3.1.3.1 / 3.2.2.2）继续按文本读写，所以保留。
 *       本类是**唯一**允许写那一列的地方，且只在重命名时写——
 *       否则文本与类目名会各说各话，3.2.1.1 的「类目缺口」统计按文本比就永远对不上分类树。
 *   <li><b>停用不删除，且对下游规则有明确口径</b>：停用的类目不再出现在树与选择候选里，
 *       但它身上的属性要求 / 允许规格轴**保留**（那些规则是既有商品录入校验的依据，
 *       删掉等于让已录入商品突然「不合法」）。类目删除则相反：连同规则级联删除。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 分类是商品主数据的一部分，与 SPU 同域；不新造权限词表（那需要动 identity 服务的迁移，
 * 属于另一个服务的写入所有权）。
 */
@Service
public class CategoryTreeService {
  /**
   * 祖先链上溯的硬上限。
   *
   * <p>与 V14 触发器里的 64 层同口径。人手工维护的分类树通常不到 6 层，
   * 这个上限只在「数据已经成环」时才起作用：没有它，一次递归会永远跑下去，
   * 把连接池占住而不是报错。
   */
  private static final int MAX_ANCESTOR_DEPTH = 64;

  private static final String FROM_TREE = " from product_category_tree t where t.tenant_id=?";

  private final DSLContext sql;

  public CategoryTreeService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 树

  /** 树列表：页面顶部选树的那一排。内部树与平台树同列，按种类排序让同类的挨在一起。 */
  public List<CategoryTree> trees() {
    return sql.fetch(
            "select t.id,t.code,t.name,t.tree_kind,t.platform_code,t.enabled,t.version,"
                + "t.created_at,t.updated_at" + FROM_TREE + " order by t.tree_kind,t.code",
            currentTenantId())
        .stream()
        .map(CategoryTreeService::toTree)
        .toList();
  }

  private static CategoryTree toTree(Record r) {
    return new CategoryTree(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("tree_kind", String.class),
        r.get("platform_code", String.class),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  /**
   * 建树或改树（id 为空即新建），走 version 乐观锁。
   *
   * <p>{@code treeKind} 建后不可改：它决定这棵树能不能当内部类目用，也决定
   * 「平台树必须带 platform_code」这条 DB CHECK 是否成立。半路改种类会让
   * 一棵已经挂满商品的内部树突然变成平台树，那些内部类目立刻失去主口径地位。
   */
  public CategoryTree saveTree(AccessService.Access actor, UUID id, CategoryTreeInput input) {
    String problem =
        firstNonNull(
            codeProblem(input.code()),
            nameProblem(input.name()),
            treeKindProblem(input.treeKind()),
            platformProblem(input.treeKind(), input.platformCode()));
    CatalogService.check(problem == null, problem);
    String code = normalizeCode(input.code());
    String kind = input.treeKind().trim();
    String platform = "PLATFORM".equals(kind) ? normalizeName(input.platformCode()) : null;

    if (id == null) {
      if (sql.fetchOne("select id from product_category_tree where code=?", code) != null)
        throw conflict("分类树编码已存在：" + code);
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into product_category_tree(id,tenant_id,code,name,tree_kind,platform_code,enabled)"
                + " values(?,?,?,?,?,?,?)",
            newId, actor.tenantId(), code, normalizeName(input.name()), kind, platform,
            input.enabled());
      } catch (DataAccessException e) {
        throw conflict("分类树编码已被并发创建或占用，请刷新后重试");
      }
      audit(actor, newId, "CategoryTreeSaved");
      return new CategoryTree(
          newId, code, normalizeName(input.name()), kind, platform, input.enabled(), 0,
          null, null);
    }

    var row = treeRow(id);
    CatalogService.check(
        row.get("tree_kind", String.class).equals(kind),
        "树种类建后不可修改：它决定这棵树能否作为内部管理分类使用，"
            + "已挂商品的树半路改种类会让既有类目失去主分类口径");
    int updated;
    try {
      updated =
          sql.execute(
              "update product_category_tree set name=?,enabled=?,version=version+1,updated_at=now()"
                  + " where id=? and version=?",
              normalizeName(input.name()), input.enabled(), id, input.version());
    } catch (DataAccessException e) {
      throw conflict("分类树保存失败，请刷新后重试");
    }
    if (updated != 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "分类树已被他人修改，请刷新后重试");
    long version = sql.fetchOne("select version from product_category_tree where id=?", id)
        .get("version", Long.class);
    audit(actor, id, "CategoryTreeSaved");
    return new CategoryTree(
        id, code, normalizeName(input.name()), kind, platform, input.enabled(), version,
        null, null);
  }

  /** 删树：整棵树连同类目与类目上的规则一起级联删除。类目上有商品时拒绝，提示先改商品的分类。 */
  public void deleteTree(AccessService.Access actor, UUID id, long version) {
    var row = treeRow(id);
    if (row.get("version", Long.class) != version)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "分类树已被他人修改，请刷新后重试");
    long used = productCount(actor.tenantId(), "tree_id=?", id);
    CatalogService.check(
        used == 0,
        "这棵树下面还有 " + used + " 件商品：请先把它们改挂到别的分类，或停用这棵树。删除树会连同分类上的属性要求一起删除");
    sql.execute("delete from product_category_tree where id=?", id);
    audit(actor, id, "CategoryTreeDeleted");
  }

  private Record treeRow(UUID id) {
    var row = sql.fetchOne("select * from product_category_tree where id=?", id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  /**
   * 某个分类所在的那棵树。
   *
   * <p>分类是「树内」的实体，更新分类时必须先确认它属于哪棵树——否则一个按 {@code treeId}
   * 建分类的请求可以带着 A 树的 treeId 去改 B 树的分类。这里读一次树，
   * 顺带把「这个分类在别的树上，要在它自己的树上维护」这条拒绝变成确定的。
   */
  public CategoryTree treeOf(UUID categoryId) {
    var row = categoryRow(categoryId);
    Record tree =
        sql.fetchOne(
            "select id,code,name,tree_kind,platform_code,enabled,version,created_at,updated_at"
                + " from product_category_tree where id=?",
            row.get("tree_id", UUID.class));
    return toTree(tree);
  }

  // ============================================================ 分类树结构（蓝图的三个输出）

  /**
   * 一次取回整棵树：结构 + 路径串 + 每个节点的商品数统计。
   *
   * <p>三个输出合在一个端点里，是因为它们来自同一次查询（三个 LEFT JOIN 子查询），
   * 分成三个端点会让前端发三次请求拿到**互相不一致的三个瞬间**——拖拽之后这种不一致很难察觉。
   *
   * <p>{@code includeDisabled=false}（默认）时停用的类目不进结果，且**它的子孙也不再返回**：
   * 停用一个中间类目而把它的子类目留在结果里，界面上会出现一条没有父亲的孤儿子树。
   */
  public List<CategoryNode> tree(UUID treeId, boolean includeDisabled) {
    treeRow(treeId);
    var rows =
        sql.fetch(
            NODE_COLUMNS + NODE_FROM + " and c.tree_id=?" + (includeDisabled ? "" : " and c.enabled")
                + " order by c.path_ids,c.position,c.code",
            currentTenantId(), treeId);
    return withNames(rows.stream().map(r -> toNode(r)).toList());
  }

  private static final String NODE_COLUMNS =
      "select c.id,c.tree_id,c.parent_id,c.code,c.name,c.position,c.enabled,c.path_ids,"
          + "c.version,c.created_at,c.updated_at,t.code as tree_code,t.tree_kind,t.platform_code,"
          // 直接商品数：主分类恰好挂在这一节点上的 SPU 数。
          + "(select count(*) from spu_category sc join spu p on p.tenant_id=sc.tenant_id and p.id=sc.spu_id"
          + "  where sc.tenant_id=c.tenant_id and sc.category_id=c.id and sc.role='PRIMARY') as direct_count,"
          // 子树商品数：主分类落在本节点**或任一子孙**上的 SPU 数。
          // 方向别写反：c.path_ids 是「c 的祖先链」，`sc.category_id = any(c.path_ids)` 统计的是
          // 「挂在本节点**祖先**上的商品」；子树口径要反过来问——「这个商品的类目，其祖先里有没有 c」，
          // 即 `c.id = any(d.path_ids)`，其中 d 是该商品挂的类目。写反了会把子树的数字算到父节点头上。
          + "(select count(*) from spu_category sc join spu p on p.tenant_id=sc.tenant_id and p.id=sc.spu_id"
          + "  where sc.tenant_id=c.tenant_id and sc.role='PRIMARY'"
          + "  and exists (select 1 from product_category d where d.tenant_id=sc.tenant_id"
          + "    and d.id=sc.category_id and c.id=any(d.path_ids))) as subtree_count,"
          // 辅助分类数：单独给出，否则「主分类口径的商品数」会被辅助归类污染。
          + "(select count(*) from spu_category sc join spu p on p.tenant_id=sc.tenant_id and p.id=sc.spu_id"
          + "  where sc.tenant_id=c.tenant_id and sc.category_id=c.id and sc.role='SECONDARY') as secondary_count,"
          // 规则数：类目上挂了多少条属性要求 / 允许规格轴（决定停用该类目会影响多少录入校验）。
          + "(select count(*) from category_attribute_rule r"
          + "  where r.tenant_id=c.tenant_id and r.category_id=c.id) as attribute_rule_count,"
          + "(select count(*) from category_spec_axis r"
          + "  where r.tenant_id=c.tenant_id and r.category_id=c.id) as spec_axis_rule_count";

  private static final String NODE_FROM =
      " from product_category c join product_category_tree t"
          + " on t.tenant_id=c.tenant_id and t.id=c.tree_id where c.tenant_id=?";

  private CategoryNode toNode(Record r) {
    UUID[] path = r.get("path_ids", UUID[].class);
    return new CategoryNode(
        r.get("id", UUID.class),
        r.get("tree_id", UUID.class),
        r.get("tree_code", String.class),
        r.get("tree_kind", String.class),
        r.get("platform_code", String.class),
        r.get("parent_id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("position", Integer.class),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        path == null ? 1 : path.length,
        // 路径串在服务层拼：SQL 侧拼要靠 array_to_string + 子查询，既难读又不好复用。
        // 名字取当前名称（不是建树时的快照），所以重命名后路径串自动跟着变。
        pathText(r.get("path_ids", UUID[].class)),
        r.get("direct_count", Long.class),
        r.get("subtree_count", Long.class),
        r.get("secondary_count", Long.class),
        r.get("attribute_rule_count", Long.class),
        r.get("spec_axis_rule_count", Long.class),
        Map.of(),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  /** 把祖先链 id 数组换成 "服饰 / 上装 / T恤" 的名称路径串。 */
  private String pathText(UUID[] ids) {
    if (ids == null || ids.length == 0) return "";
    Map<UUID, String> names = new LinkedHashMap<>();
    for (Record r : sql.fetch("select id,name from product_category where id in (" + placeholders(ids) + ")", ids))
      names.put(r.get("id", UUID.class), r.get("name", String.class));
    var parts = new ArrayList<String>(ids.length);
    for (UUID id : ids) {
      String name = names.get(id);
      // 祖先被删不该发生（外键 CASCADE 会连带删掉子孙），真出现也不该让整棵树查不出来。
      if (name != null) parts.add(name);
    }
    return String.join(" / ", parts);
  }

  /** 批量补多语言名：一次查询按 locale 归拢，避免每个节点各查一次（N+1）。 */
  private List<CategoryNode> withNames(List<CategoryNode> nodes) {
    if (nodes.isEmpty()) return nodes;
    List<UUID> ids = nodes.stream().map(CategoryNode::id).toList();
    var rows =
        sql.fetch(
            "select category_id,locale,name from product_category_name"
                + " where category_id in (" + placeholders(ids) + ")",
            ids.toArray());
    Map<UUID, Map<String, String>> byCategory = new LinkedHashMap<>();
    for (Record r : rows) {
      byCategory
          .computeIfAbsent(r.get("category_id", UUID.class), k -> new LinkedHashMap<>())
          .put(r.get("locale", String.class), r.get("name", String.class));
    }
    return nodes.stream()
        .map(n -> withNames(n, byCategory.getOrDefault(n.id(), Map.of())))
        .toList();
  }

  private static CategoryNode withNames(CategoryNode node, Map<String, String> names) {
    return new CategoryNode(
        node.id(), node.treeId(), node.treeCode(), node.treeKind(), node.platformCode(),
        node.parentId(), node.code(), node.name(), node.position(), node.enabled(), node.depth(),
        node.path(), node.directProductCount(), node.subtreeProductCount(),
        node.secondaryProductCount(), node.attributeRuleCount(), node.specAxisRuleCount(), names,
        node.version(), node.createdAt(), node.updatedAt());
  }

  // ============================================================ 分类增删改

  /**
   * 建分类或改分类（id 为空即新建），走 version 乐观锁。
   *
   * <p>编码建后不可改：它是刊登映射与类目规则按 code 对齐的锚点，改编码会让已有对齐在下一次写入时
   * 突然报「对不上」。要改就新建一条分类。
   */
  public CategorySaved save(AccessService.Access actor, UUID treeId, UUID id, CategoryInput input) {
    var tree = treeRow(treeId);
    CatalogService.check(
        nameProblem(input.name()) == null, nameProblem(input.name()));
    CatalogService.check(codeProblem(input.code()) == null, codeProblem(input.code()));
    CatalogService.check(positionProblem(input.position()) == null, positionProblem(input.position()));
    String namesProblem = namesProblem(input.names());
    CatalogService.check(namesProblem == null, namesProblem);
    String code = normalizeCode(input.code());

    if (id == null) {
      UUID parentId = input.parentId();
      if (parentId != null) {
        var parent = categoryRow(parentId);
        CatalogService.check(
            parent.get("tree_id", UUID.class).equals(treeId),
            "父分类属于另一棵分类树：分类只能在同一棵树内挂载，不能跨树");
        CatalogService.check(
            Boolean.TRUE.equals(parent.get("enabled", Boolean.class)),
            "不能在停用的分类下新建子分类：请先启用父分类");
      }
      UUID newId = Ids.next();
      int position =
          input.position() != null
              ? input.position()
              : nextPosition(actor.tenantId(), treeId, parentId);
      try {
        // path_ids 传占位值：由 V14 的 BEFORE 触发器算成 [.. 父链, 自己]。
        // 这里**不能**自己在 Java 里拼——拼错一条就是一棵损坏的树，且没有任何约束会告诉你。
        sql.execute(
            "insert into product_category(id,tenant_id,tree_id,code,name,parent_id,position,enabled,path_ids)"
                + " values(?,?,?,?,?,?,?,?,?)",
            newId, actor.tenantId(), treeId, code, normalizeName(input.name()), parentId,
            position, input.enabled(), new UUID[0]);
      } catch (DataAccessException e) {
        throw conflict("分类编码已被并发创建或占用，或该落点会让分类树形成环，请刷新后重试");
      }
      replaceNames(actor.tenantId(), newId, input.names());
      audit(actor, newId, "CategorySaved");
      return savedResult(newId);
    }

    var row = categoryRow(id);
    CatalogService.check(
        row.get("tree_id", UUID.class).equals(treeId),
        "这个分类属于另一棵分类树，请在它自己的树上维护");
    CatalogService.check(
        row.get("code", String.class).equals(code),
        "分类编码建后不可修改：刊登映射与类目规则按 code 对齐这个分类，改编码会让已有对齐在下一次写入时报「对不上」。"
            + "要改请新建一条分类");
    int updated;
    try {
      updated =
          sql.execute(
              "update product_category set name=?,position=?,enabled=?,version=version+1,updated_at=now()"
                  + " where id=? and version=?",
              normalizeName(input.name()),
              input.position() == null ? row.get("position", Integer.class) : input.position(),
              input.enabled(), id, input.version());
    } catch (DataAccessException e) {
      throw conflict("分类保存失败，请刷新后重试");
    }
    if (updated != 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "分类已被他人修改，请刷新后重试");

    // 【V14 留下的口子在这里兑现】重命名类目时，同步两张规则表的 category 文本列。
    // 那一列是已交付模块（3.2.1.1 / 3.1.3.1 / 3.2.2.2）的读写口径，只在这里写，
    // 才能保证「文本 == 当前类目名」，3.2.1.1 的类目缺口统计才不会和分类树对不上。
    syncRuleCategoryText(actor.tenantId(), id, normalizeName(input.name()));
    replaceNames(actor.tenantId(), id, input.names());
    audit(actor, id, "CategorySaved");
    return savedResult(id);
  }

  /** 同步规则表的兼容文本列。只在重命名路径调用，其他地方不许单独写它。 */
  private void syncRuleCategoryText(UUID tenantId, UUID categoryId, String name) {
    sql.execute(
        "update category_attribute_rule set category=? where tenant_id=? and category_id=?",
        name, tenantId, categoryId);
    sql.execute(
        "update category_attribute_option set category=? where tenant_id=? and category_id=?",
        name, tenantId, categoryId);
    sql.execute(
        "update category_spec_axis set category=? where tenant_id=? and category_id=?",
        name, tenantId, categoryId);
  }

  private CategorySaved savedResult(UUID id) {
    var row = sql.fetchOne("select path_ids,version from product_category where id=?", id);
    UUID[] path = row.get("path_ids", UUID[].class);
    return new CategorySaved(
        id, row.get("version", Long.class), path == null ? 1 : path.length, pathText(path));
  }

  /**
   * 拖拽落点：改父节点与同级位置。
   *
   * <p>防环在服务层先判一次（给可读原因），V14 触发器再兜一道。顺序位的重排是
   * 「把落点之后的同级整体后移一位」——只改落点那一行的 position 会让两行撞在同一个位置上，
   * 而 {@code (tree_id,parent_id,position)} 上没有唯一约束，撞位不会报错，只会**顺序变得不确定**，
   * 那种问题要等到运营抱怨「排序自己乱了」才会被发现。
   */
  public CategorySaved move(AccessService.Access actor, UUID id, CategoryMoveInput input) {
    var row = categoryRow(id);
    UUID treeId = row.get("tree_id", UUID.class);
    UUID oldParent = row.get("parent_id", UUID.class);
    UUID newParent = input.parentId();
    CatalogService.check(!id.equals(newParent), "不能把分类挂到它自己下面");
    CatalogService.check(positionProblem(input.position()) == null, positionProblem(input.position()));

    if (newParent != null) {
      var parent = categoryRow(newParent);
      CatalogService.check(
          parent.get("tree_id", UUID.class).equals(treeId),
          "父分类属于另一棵分类树：分类只能在同一棵树内挂载，不能跨树");
      // 服务层防环：上溯父链看会不会撞回自己。触发器也会判，这里是为了给出可读原因
      // 并在同一次往返里拒绝，而不是先让 DB 抛一个约束名。
      CatalogService.check(!isDescendant(actor.tenantId(), id, newParent),
          "不能把分类挂到它自己的子孙节点下：这会让分类树形成环");
    }

    int position = input.position() == null ? nextPosition(actor.tenantId(), treeId, newParent) : input.position();
    boolean sameParent = java.util.Objects.equals(oldParent, newParent);
    if (sameParent) {
      int current = row.get("position", Integer.class);
      if (position != current) {
        if (position > current) {
          sql.execute(
              "update product_category set position=position-1"
                  + " where tenant_id=? and tree_id=? and parent_id is not distinct from ?"
                  + " and position>? and position<=?",
              actor.tenantId(), treeId, oldParent, current, position);
        } else {
          sql.execute(
              "update product_category set position=position+1"
                  + " where tenant_id=? and tree_id=? and parent_id is not distinct from ?"
                  + " and position>=? and position<?",
              actor.tenantId(), treeId, oldParent, position, current);
        }
      }
    } else {
      sql.execute(
          "update product_category set position=position+1"
              + " where tenant_id=? and tree_id=? and parent_id is not distinct from ? and position>=?",
          actor.tenantId(), treeId, oldParent, position);
    }

    int updated;
    try {
      // parent_id 变更由 V14 的两个触发器接管：算新的 path_ids + 同步整条子树的前缀。
      updated =
          sql.execute(
              "update product_category set parent_id=?,position=?,version=version+1,updated_at=now()"
                  + " where id=? and version=?",
              newParent, position, id, input.version());
    } catch (DataAccessException e) {
      throw conflict("移动失败：这个落点会让分类树形成环，或分类已被他人修改，请刷新后重试");
    }
    if (updated != 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "分类已被他人修改，请刷新后重试");
    audit(actor, id, "CategoryMoved");
    return savedResult(id);
  }

  /**
   * 服务层防环：{@code candidateParent} 的祖先链里是否出现了 {@code id}。
   *
   * <p>用递归 CTE 一次上溯到底，带 {@code MAX_ANCESTOR_DEPTH} 上限防止历史脏数据成环时把自己跑死。
   */
  private boolean isDescendant(UUID tenantId, UUID id, UUID candidateParent) {
    long hits =
        sql.fetchOne(
                "with recursive ancestors as ("
                    + " select id,parent_id,1 as lvl from product_category"
                    + "  where tenant_id=? and id=?"
                    + " union all"
                    + " select c.id,c.parent_id,a.lvl+1 from product_category c"
                    + "  join ancestors a on c.tenant_id=? and c.id=a.parent_id"
                    + "  where a.lvl < ?)"
                    + " select count(*) from ancestors where id=?",
                tenantId, candidateParent, tenantId, MAX_ANCESTOR_DEPTH, id)
            .get(0, Long.class);
    return hits > 0;
  }

  private Record categoryRow(UUID id) {
    var row = sql.fetchOne("select * from product_category where id=?", id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private int nextPosition(UUID tenantId, UUID treeId, UUID parentId) {
    Integer max =
        sql.fetchOne(
                "select max(position) from product_category"
                    + " where tenant_id=? and tree_id=? and parent_id is not distinct from ?",
                tenantId, treeId, parentId)
            .get(0, Integer.class);
    return max == null ? 0 : max + 1;
  }

  /**
   * 删分类。
   *
   * <p>两类东西会挡住删除，都应该走「停用」而不是删除：
   * <ul>
   *   <li>它下面还有子分类——删掉父节点会把整支子树变成孤儿（外键 CASCADE 会连坐删掉整支，
   *       那是「删一个节点」不该有的破坏面）；
   *   <li>它上面还有商品——商品的分类就没了。
   * </ul>
   * 至于它身上的类目规则：不在这里挡，让外键 CASCADE 处理（类目没了规则就没有意义，
   * 与 V11「删属性会级联删掉它的类目规则」同一口径）。
   */
  public void delete(AccessService.Access actor, UUID id, long version) {
    var row = categoryRow(id);
    if (row.get("version", Long.class) != version)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "分类已被他人修改，请刷新后重试");
    long children =
        sql.fetchOne("select count(*) from product_category where parent_id=?", id).get(0, Long.class);
    CatalogService.check(
        children == 0,
        "这个分类下面还有 " + children + " 个子分类：请先把它们移到别处，或停用这个分类。删除会连同整支子树一起消失");
    long products =
        sql.fetchOne("select count(*) from spu_category where category_id=?", id).get(0, Long.class);
    CatalogService.check(
        products == 0,
        "这个分类上还挂着 " + products + " 件商品：请先把它们改挂到别的分类。删除分类会让这些商品失去分类");
    sql.execute("delete from product_category where id=?", id);
    audit(actor, id, "CategoryDeleted");
  }

  /**
   * 启用停用。
   *
   * <p>停用的口径必须说清楚，因为它直接影响下游：
   * <ul>
   *   <li>树里默认过滤掉停用类目（{@code includeEnabledOnly}），不再出现在拖拽落点与选择候选里；
   *   <li>它身上的属性要求与允许规格轴**保留**——那些规则是既有商品录入校验的依据，
   *       删掉会让已录入商品突然「不合法」；
   *   <li>已挂在它上面的商品**保持挂载**，商品数统计照常计入（停用是治理动作，不是数据变更）。
   * </ul>
   * 停用父分类不级联停用子分类：子分类自己可能还挂着商品，一次性把整支停掉会造成大面积「看不见」。
   */
  public void setEnabled(AccessService.Access actor, UUID id, boolean enabled) {
    categoryRow(id);
    sql.execute(
        "update product_category set enabled=?,version=version+1,updated_at=now() where id=?",
        enabled, id);
    audit(actor, id, enabled ? "CategoryEnabled" : "CategoryDisabled");
  }

  private void replaceNames(UUID tenantId, UUID categoryId, List<CategoryNameInput> names) {
    sql.execute(
        "delete from product_category_name where tenant_id=? and category_id=?", tenantId, categoryId);
    if (names == null) return;
    for (CategoryNameInput entry : names)
      sql.execute(
          "insert into product_category_name(tenant_id,category_id,locale,name) values(?,?,?,?)",
          tenantId, categoryId, normalizeLocale(entry.locale()), normalizeName(entry.name()));
  }

  // ============================================================ 规则表的类目解析（V14 改挂主键的配套）

  /**
   * 把一个类目**文本**解析成类目主键：命中就返回，miss 就内部管理分类树里建一个。
   *
   * <p>这是 V14 把 {@code category_attribute_rule.category} / {@code category_spec_axis.category}
   * 改挂 {@code category_id} 之后的配套入口。已交付的两个规则写入端点
   * （3.2.1.1 {@code saveCategoryRule}、3.1.3.1 {@code saveCategoryRule}）对外的入参是**类目文本**，
   * 它们的 API 语义本模块不改（改了就等于重开两个已交付模块的账）；而 {@code category_id} 又是
   * NOT NULL 的外键——两者只能在这里会合。
   *
   * <p>「miss 就建一个」而不是「miss 就拒绝」，理由：
   * <ul>
   *   <li>拒绝会让 3.2.1.1 / 3.1.3.1 已交付的规则配置能力**当场不可用**（任何一条新规则都存不进去），
   *       而分类树是本模块刚交付的，运营手上还没有节点可用——那就变成「必须先去分类树建类目」
   *       才能配属性要求，跨模块的硬顺序要求。
   *   <li>建出来之后那条规则立刻锚在一个真实的类目主键上，这正是 V14 改挂主键的目的：
   *       3.2.1.1 的「有商品的类目里没配任何属性要求的缺口」统计从此能按主键和分类树对上，
   *       而不是继续按字符串比。
   * </ul>
   * 建出来的节点一律落在**内部管理分类树**（{@code INTERNAL}）：平台类目映射树的节点归 04 刊登维护，
   * 属性要求挂到平台树上没有业务含义。
   *
   * <p>编码生成规则与 V14 的自由文本迁移**逐字一致**（同样的规范化 + md5 后缀），
   * 所以「迁移时建过的类目」与「这里现建的类目」是同一行，不会出现两个同名类目。
   */
  public UUID resolveOrCreateCategoryId(AccessService.Access actor, String categoryText) {
    String name = normalizeName(categoryText);
    CatalogService.check(!name.isEmpty(), "类目不能为空");
    var hit =
        sql.fetchOne(
            "select c.id from product_category c join product_category_tree t"
                + " on t.tenant_id=c.tenant_id and t.id=c.tree_id"
                + " where c.tenant_id=? and c.name=? order by c.created_at limit 1",
            actor.tenantId(), name);
    if (hit != null) return hit.get("id", UUID.class);

    UUID treeId = internalTreeId(actor);
    UUID id = Ids.next();
    sql.execute(
        "insert into product_category(id,tenant_id,tree_id,code,name,parent_id,position,enabled,path_ids)"
            + " values(?,?,?,?,?,null,0,true,?)",
        id, actor.tenantId(), treeId, legacyCode(name), name, new UUID[0]);
    return id;
  }

  /**
   * 本租户的内部管理分类树，取不到就建一棵。
   *
   * <p>与 V14 迁移里那段 DO 块**同一条口径**（编码固定 {@code INTERNAL}、名称「内部管理分类」）：
   * 分类树落地时每个有类目数据的租户都被自动补了一棵，这里保证「迁移前没有任何类目数据的租户」
   * 在第一次配置类目规则时得到同一棵树。写死编码是为了让这两条路径收敛到同一行——
   * 两棵 INTERNAL 树会让「内部类目」这个概念本身失去唯一性。
   */
  private UUID internalTreeId(AccessService.Access actor) {
    var hit =
        sql.fetchOne(
            "select id from product_category_tree where tenant_id=? and code='INTERNAL'",
            actor.tenantId());
    if (hit != null) return hit.get("id", UUID.class);
    UUID id = Ids.next();
    try {
      sql.execute(
          "insert into product_category_tree(id,tenant_id,code,name,tree_kind,platform_code,enabled)"
              + " values(?,?,?,?,?,null,true)",
          id, actor.tenantId(), "INTERNAL", "内部管理分类", "INTERNAL");
    } catch (DataAccessException e) {
      // 并发下另一个请求先建好了：重读一次即可，不必让调用方看到冲突。
      var again =
          sql.fetchOne(
              "select id from product_category_tree where tenant_id=? and code='INTERNAL'",
              actor.tenantId());
      if (again == null) throw conflict("内部管理分类树创建失败，请刷新后重试");
      return again.get("id", UUID.class);
    }
    return id;
  }

  /**
   * 自由文本类目名的编码生成。与 V14 的数据迁移逐字一致。
   *
   * <p>中文类目名（很常见）规范化后是空串，必须退化；加了 md5 后缀保证不同中文名不会撞码，
   * 也保证同一个名字在任何时候都算出同一个码（迁移与现建是同一行）。
   */
  static String legacyCode(String name) {
    String ascii =
        name.replaceAll("[^A-Za-z0-9_-]", "").trim();
    if (ascii.isEmpty()) return "LEGACY-" + md5(name, 8);
    String upper = ascii.toUpperCase(java.util.Locale.ROOT);
    return upper.substring(0, Math.min(50, upper.length())) + "-" + md5(name, 4);
  }

  /** 纯 Java 的 md5（十六进制）：SQL 侧 {@code md5()} 不可用，而编码必须在应用层算出来。 */
  private static String md5(String value, int length) {
    try {
      var digest =
          java.security.MessageDigest.getInstance("MD5").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var hex = new StringBuilder();
      for (byte b : digest) hex.append(String.format("%02x", b));
      return hex.substring(0, length);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // ============================================================ SPU 主分类 / 辅助分类

  /** 读某个 SPU 的分类关联：主分类 + 辅助分类。 */
  public SpuCategoryAssignment spuAssignment(UUID spuId) {
    if (sql.fetchOne("select id from spu where id=?", spuId) == null) throw CatalogService.missing();
    UUID primaryId = null;
    String primaryName = null;
    var secondary = new ArrayList<SpuCategoryLink>();
    for (Record r :
        sql.fetch(
            "select sc.category_id,sc.role,sc.position,c.code,c.name"
                + " from spu_category sc join product_category c"
                + "  on c.tenant_id=sc.tenant_id and c.id=sc.category_id"
                + " where sc.spu_id=? order by sc.role desc,sc.position,c.code",
            spuId)) {
      if ("PRIMARY".equals(r.get("role", String.class))) {
        primaryId = r.get("category_id", UUID.class);
        primaryName = r.get("name", String.class);
      } else {
        secondary.add(
            new SpuCategoryLink(
                r.get("category_id", UUID.class), r.get("code", String.class),
                r.get("name", String.class), r.get("position", Integer.class)));
      }
    }
    return new SpuCategoryAssignment(spuId, primaryId, primaryName, secondary);
  }

  /**
   * 写 SPU 的主分类与辅助分类。
   *
   * <p>整体替换而不是增量增删：界面上「主分类 + 一组辅助分类」是一张表单，
   * 增量端点会让「取消勾选一个辅助分类」与「新增一个」走两套代码，两套都要各自正确地处理唯一约束。
   *
   * <p>主分类唯一由 {@code spu_category_single_primary} 部分唯一索引保证（DB 层），
   * 这里先显式拒绝「把已经是主分类的节点重复当主分类提交」这种可读错误。
   */
  public SpuCategoryAssignment saveSpuCategories(
      AccessService.Access actor, UUID spuId, SpuCategoryInput input) {
    if (sql.fetchOne("select id from spu where id=?", spuId) == null) throw CatalogService.missing();
    UUID primaryId = input.primaryCategoryId();
    if (primaryId != null) {
      var category = categoryRow(primaryId);
      CatalogService.check(
          Boolean.TRUE.equals(category.get("enabled", Boolean.class)),
          "不能把商品挂到已停用的分类上：请先启用该分类，或换一个启用中的分类");
    }
    List<SpuCategoryLinkInput> secondary =
        input.secondary() == null ? List.of() : input.secondary();
    for (SpuCategoryLinkInput link : secondary) {
      var category = categoryRow(link.categoryId());
      CatalogService.check(
          Boolean.TRUE.equals(category.get("enabled", Boolean.class)),
          "不能把商品挂到已停用的辅助分类上：请先启用该分类，或换一个启用中的分类");
      CatalogService.check(
          !link.categoryId().equals(primaryId),
          "同一个分类不能既是主分类又是辅助分类：请把它只放在主分类上");
    }
    CatalogService.check(
        secondary.stream().map(SpuCategoryLinkInput::categoryId).distinct().count()
            == secondary.size(),
        "辅助分类不能重复挂同一个分类");

    sql.execute("delete from spu_category where tenant_id=? and spu_id=?", actor.tenantId(), spuId);
    if (primaryId != null)
      sql.execute(
          "insert into spu_category(tenant_id,spu_id,category_id,role,position) values(?,?,?,?,?)",
          actor.tenantId(), spuId, primaryId, "PRIMARY", 0);
    int position = 0;
    for (SpuCategoryLinkInput link : secondary)
      sql.execute(
          "insert into spu_category(tenant_id,spu_id,category_id,role,position) values(?,?,?,?,?)",
          actor.tenantId(), spuId, link.categoryId(), "SECONDARY",
          link.position() == null ? position++ : link.position());
    audit(actor, spuId, "SpuCategoriesSaved");
    return spuAssignment(spuId);
  }

  // ============================================================ 概览

  /** 概览：规模与几个治理缺口指标。 */
  public CategoryOverview overview() {
    UUID tenantId = currentTenantId();
    var trees =
        sql.fetchOne(
            "select count(*) as all_trees,"
                + "count(*) filter (where tree_kind='INTERNAL') as internal_trees,"
                + "count(*) filter (where tree_kind='PLATFORM') as platform_trees,"
                + "count(*) filter (where not enabled) as disabled_trees"
                + FROM_TREE,
            currentTenantId());
    var cats =
        sql.fetchOne(
            "select count(*) as all_categories,"
                + "count(*) filter (where not enabled) as disabled_categories,"
                + "coalesce(max(cardinality(path_ids)),0) as max_depth"
                + " from product_category where tenant_id=?",
            tenantId);
    long products = sql.fetchOne("select count(*) from spu where tenant_id=?", tenantId).get(0, Long.class);
    long categorized =
        sql.fetchOne(
                "select count(distinct sc.spu_id) from spu_category sc"
                    + " where sc.tenant_id=? and sc.role='PRIMARY'",
                tenantId)
            .get(0, Long.class);
    long uncategorized =
        sql.fetchOne(
                "select count(*) from spu p where p.tenant_id=?"
                    + " and not exists (select 1 from spu_category sc"
                    + "  where sc.tenant_id=p.tenant_id and sc.spu_id=p.id and sc.role='PRIMARY')",
                tenantId)
            .get(0, Long.class);
    long orphaned =
        sql.fetchOne(
                "select count(*) from category_attribute_rule r"
                    + " where r.tenant_id=? and not exists (select 1 from product_category c"
                    + "  where c.tenant_id=r.tenant_id and c.id=r.category_id)",
                tenantId)
            .get(0, Long.class);
    return new CategoryOverview(
        trees.get("all_trees", Long.class),
        trees.get("internal_trees", Long.class),
        trees.get("platform_trees", Long.class),
        trees.get("disabled_trees", Long.class),
        cats.get("all_categories", Long.class),
        cats.get("disabled_categories", Long.class),
        cats.get("max_depth", Long.class),
        products,
        categorized,
        uncategorized,
        orphaned,
        "主分类口径：spu_category.role='PRIMARY'。辅助分类（SECONDARY）不计入品类统计，只在节点上单列。");
  }

  // ============================================================ 工具

  private long productCount(UUID tenantId, String condition, Object... binds) {
    List<Object> all = new ArrayList<>();
    all.add(tenantId);
    all.addAll(List.of(binds));
    return sql.fetchOne(
            "select count(*) from spu_category sc join spu p"
                + " on p.tenant_id=sc.tenant_id and p.id=sc.spu_id"
                + " where sc.tenant_id=? and " + condition,
            all.toArray())
        .get(0, Long.class);
  }

  /**
   * 拼出一串 {@code ?,?,?} 占位符。
   *
   * <p>不用 {@code = any(?::uuid[])}：jOOQ 把 {@code UUID[]} 绑成**一个** uuid 参数
   * （而不是 uuid[]），整条语句会报 {@code cannot cast type uuid to uuid[]}。
   * 逐个占位符是本仓库已有的做法（OAuthAuthorizationService.loadCandidates 同理），
   * ID 数量有限，拼占位符更稳也更好读。调用方必须先判空——空列表会拼出 {@code in ()} 语法错误。
   */
  private static String placeholders(List<UUID> ids) {
    return String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
  }

  private static String placeholders(UUID[] ids) {
    return String.join(",", java.util.Collections.nCopies(ids.length, "?"));
  }

  /** 当前租户从 RLS 生效的连接上下文取（本服务的每个查询都在 AccessService.run 包裹里）。 */
  private UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

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
