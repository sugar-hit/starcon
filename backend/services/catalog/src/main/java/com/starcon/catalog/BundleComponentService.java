package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.5.1.2 组件维护：套装组件 SKU 与数量的逐行维护、循环检测与结构化校验结果。
 *
 * <p>蓝图原文：「职责：维护套装组件 SKU 与数量，校验不出现循环引用（套装 A 含套装 B 且 B 含 A） 与组件数量必须大于 0。」「输入：组件增删改；{@code
 * bundle_component} 记录；循环检测。」 「输出：组件清单；校验结果（循环、数量非法、组件不存在均拒绝保存）。」 「关系：上游 = 3.5.1.1；下游 = 07
 * 库存（预占组件）、09 订单（{@code order_line_component} 冻结展开结果）。**已发布版本的组件不可改**，只能新建版本 —— 历史订单冻结的是当时版本。」
 *
 * <p><b>与 3.5.1.1 的分工</b>（V23 文件头第四节已经写死，本类只是照做）：
 *
 * <ul>
 *   <li>3.5.1.1 交付「随草稿版本整体替换组件清单 + 发布时校验齐备」， 本类<b>不</b>改那条路径的语义（整体替换仍归它）。
 *   <li>本类交付<b>独立组件工作台</b>：跨版本看清单、逐行增删改、以及成环检测。
 * </ul>
 *
 * <p><b>三条校验分别落在哪一层</b>：
 *
 * <ol>
 *   <li><b>数量必须大于 0</b> —— DB 的 CHECK（V23）已经拦得住；服务层用 {@link BundleComponentRules#quantityProblem}
 *       提前拒并给人话原因。
 *   <li><b>组件必须存在</b> —— {@code bundle_component} 上有指向 {@code sku} 的复合外键， 但外键报错是英文的 {@code violates
 *       foreign key constraint}。这里一次查询问齐、 缺哪个点名哪个。
 *   <li><b>循环引用</b> —— 本模块新增的判定（V24 规则 R6）。判定口径在 {@link BundleComponentRules#cyclePath}（纯函数，可单测），
 *       DB 侧 {@code find_bundle_cycle_path} + 两个触发器是纵深防线。
 *       两边必须给出同一个答案，所以服务层查完环之后<b>直接用同一条路径生成报错文案</b>。
 * </ol>
 *
 * <p>「已发布版本的组件不可改」由 V23 的 {@code guard_bundle_component_published} 兜底， 本类只在服务层先给一句可执行的中文原因。
 *
 * <p>权限口径：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}， 与 3.5.1.1 完全一致（同一份商品数据，不该有两套权限）。
 */
@Service
public class BundleComponentService {
  /** 单页最大行数，与 3.5.1.1 同一口径。 */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 组件清单的连接形状（清单与计数共用）。
   *
   * <p>计数必须复用<b>同一段 FROM</b>：如果 count 用一张简化过的表， 那么「清单按版本过滤」和「总数按版本过滤」就会给出两个不同的数字， 翻页时页数与实际行数对不上。
   */
  private static final String COMPONENT_FROM =
      "from bundle_component c "
          + "join bundle_version v on v.tenant_id=c.tenant_id and v.id=c.version_id "
          + "join bundle b on b.tenant_id=v.tenant_id and b.id=v.bundle_id "
          + "join sku s on s.tenant_id=c.tenant_id and s.id=c.sku_id "
          + "left join bundle_sku lk on lk.tenant_id=c.tenant_id and lk.sku_id=c.sku_id "
          + "left join bundle lb on lb.tenant_id=lk.tenant_id and lb.id=lk.bundle_id ";

  /**
   * 组件清单的读形状。
   *
   * <p>{@code linked_*} 三个字段来自 {@code bundle_sku} 左连接：组件 SKU 代表套装时才有值。
   * 这一列就是「嵌套套装」的可见面，也是成环判定用的边的可视化。
   */
  private static final String COMPONENT_SELECT =
      "select c.id,c.version_id,c.sku_id,c.quantity,c.role,c.note,c.version,"
          + "v.bundle_id,v.version_no,v.name,v.status,"
          + "b.code as bundle_code,"
          + "s.code as sku_code,s.name as sku_name,"
          + "lk.bundle_id as linked_bundle_id,lb.code as linked_bundle_code "
          + COMPONENT_FROM;

  /** 指向的连接形状（清单与计数共用，理由同 {@link #COMPONENT_FROM}）。 */
  private static final String LINK_FROM =
      "from bundle_sku l "
          + "join bundle b on b.tenant_id=l.tenant_id and b.id=l.bundle_id "
          + "join sku s on s.tenant_id=l.tenant_id and s.id=l.sku_id ";

  /**
   * 指向的读形状。
   *
   * <p>{@code used_by_published} 单独算而不是从 {@code used_by_count} 推： 「被草稿引用」和「被已发布版本引用」的结论完全相反 ——
   * 前者解开指向只是少一条嵌套，后者会改写已冻结的展开结果。
   */
  private static final String LINK_SELECT =
      "select l.id,l.bundle_id,l.sku_id,l.note,l.version,l.created_at,l.updated_at,"
          + "b.code as bundle_code,s.code as sku_code,s.name as sku_name,"
          + "(select count(*) from bundle_component c where c.tenant_id=l.tenant_id and c.sku_id=l.sku_id)"
          + "  as used_by_count,"
          + "(select count(*) from bundle_component c join bundle_version v"
          + "    on v.tenant_id=c.tenant_id and v.id=c.version_id"
          + "  where c.tenant_id=l.tenant_id and c.sku_id=l.sku_id and v.status='PUBLISHED')"
          + "  as used_by_published "
          + LINK_FROM;

  private final DSLContext sql;

  public BundleComponentService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 概览

  /**
   * 组件工作台概览。
   *
   * <p>{@code selfReferencingCount} 与 {@code nonPositiveQuantityCount} 是<b>纪律的体检指标</b>， 正常恒为
   * 0。写出来不是为了好看，而是让「一条都没坏」这件事可核对 —— 约束建了不等于数据一定对，把这两个数摆在页面上，坏了当场看得见。
   */
  public BundleComponentOverview overview() {
    UUID tenantId = currentTenantId();
    var counts =
        sql.fetchOne(
            "select"
                + " (select count(*) from bundle_component where tenant_id=?) as component_count,"
                + " (select count(*) from bundle_version v where v.tenant_id=?"
                + "   and exists (select 1 from bundle_component c"
                + "     where c.tenant_id=v.tenant_id and c.version_id=v.id)) as version_with_component,"
                + " (select count(distinct sku_id) from bundle_component where tenant_id=?) as distinct_sku,"
                + " (select count(*) from bundle_sku where tenant_id=?) as linked_sku,"
                + " (select count(*) from bundle_component c where c.tenant_id=?"
                + "   and exists (select 1 from bundle_sku lk"
                + "     where lk.tenant_id=c.tenant_id and lk.sku_id=c.sku_id)) as nested_count,"
                + " (select count(*) from bundle_component c where c.tenant_id=?"
                + "   and exists (select 1 from bundle_sku lk join bundle_version v"
                + "       on v.tenant_id=c.tenant_id and v.id=c.version_id"
                + "     where lk.tenant_id=c.tenant_id and lk.sku_id=c.sku_id and lk.bundle_id=v.bundle_id)) as self_ref,"
                + " (select count(*) from bundle_component where tenant_id=? and quantity<=0) as bad_quantity",
            tenantId,
            tenantId,
            tenantId,
            tenantId,
            tenantId,
            tenantId,
            tenantId);
    return new BundleComponentOverview(
        counts.get("component_count", Long.class),
        counts.get("version_with_component", Long.class),
        counts.get("distinct_sku", Long.class),
        counts.get("linked_sku", Long.class),
        counts.get("nested_count", Long.class),
        counts.get("self_ref", Long.class),
        counts.get("bad_quantity", Long.class),
        "「嵌套组件」= 组件 SKU 本身代表另一个套装（套装 A 里装着套装 B）。"
            + "这类组件在 09 订单展开时会继续往下展开一层，所以必须没有环 —— "
            + "套装 A 含 B 且 B 含 A 会让展开在环上无限递归，而订单要冻结的是有限的展开结果。"
            + "「自引用」与「数量非法」正常恒为 0：它们是纪律的体检指标，"
            + "摆在这里是为了让「一条都没坏」可核对，而不是靠约束建了就一定对。");
  }

  // ================================================================ 组件清单

  /** 跨版本组件清单：分页 + 关键词（SKU 编码/名称、套装编码、版本号、角色）+ 版本/套装/是否嵌套。 */
  public BundleComponentPage components(BundleComponentQuery query) {
    int page = Math.max(0, query.page());
    int size = clampSize(query.size());
    StringBuilder where = new StringBuilder(" where c.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenantId());
    String keyword = BundleComponentRules.normalize(query.keyword());
    if (!keyword.isEmpty()) {
      where.append(
          " and (lower(s.code) like ? escape '!' or lower(s.name) like ? escape '!'"
              + " or lower(b.code) like ? escape '!' or lower(v.version_no) like ? escape '!'"
              + " or lower(c.role) like ? escape '!')");
      String like = escapeLike(keyword.toLowerCase(Locale.ROOT));
      for (int i = 0; i < 5; i++) binds.add(like);
    }
    if (query.versionId() != null) {
      where.append(" and c.version_id=?");
      binds.add(query.versionId());
    }
    if (query.bundleId() != null) {
      where.append(" and v.bundle_id=?");
      binds.add(query.bundleId());
    }
    String nested = BundleComponentRules.normalize(query.nested()).toLowerCase(Locale.ROOT);
    if ("true".equals(nested)) {
      where.append(" and lk.bundle_id is not null");
    } else if ("false".equals(nested)) {
      where.append(" and lk.bundle_id is null");
    } else if (!nested.isEmpty()) {
      throw bad("「是否嵌套」只能是 true 或 false");
    }
    // count 复用同一段 FROM：总数与清单必须是同一个集合，否则翻页页数会对不上。
    long total =
        sql.fetchOne("select count(*) " + COMPONENT_FROM + where, binds.toArray())
            .get(0, Long.class);
    if (total == 0) return new BundleComponentPage(List.of(), page, size, 0, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);
    var rows =
        sql.fetch(
            COMPONENT_SELECT + where + " order by b.code,v.version_no,s.code limit ? offset ?",
            pageBinds.toArray());
    List<BundleComponentItem> items = new ArrayList<>(rows.size());
    for (Record r : rows) items.add(componentItem(r));
    return new BundleComponentPage(
        items, page, size, total, (int) Math.ceil(total / (double) size));
  }

  /** 单行组件详情。跨租户读不到（RLS + 显式 tenant_id 过滤）。 */
  public BundleComponentItem component(UUID id) {
    Record r =
        sql.fetchOne(COMPONENT_SELECT + " where c.tenant_id=? and c.id=?", currentTenantId(), id);
    if (r == null) throw notFound("组件不存在");
    return componentItem(r);
  }

  // ================================================================ 校验结果

  /**
   * 结构化校验结果：把一个版本上的组件问题<b>一次列全</b>。
   *
   * <p>为什么要「一次列全」而不是只给第一条：蓝图的输出写的是「校验结果（循环、数量非法、 组件不存在均拒绝保存）」，运营的下一个动作是「一次改完」，不是「改一个提交一次」。
   * 逐条报错会让人把同一个错误改三遍。
   *
   * <p>检查项：
   *
   * <ol>
   *   <li>版本已发布 → 整版不可改（唯一一条「结构性」问题，不是某一行的问题）；
   *   <li>同一 SKU 在本版本出现两行（V23 的 UNIQUE 兜底，报错要说清为什么「要几件」无解）；
   *   <li>数量非法（≤ 0 或超出 numeric(19,6)）；
   *   <li>组件 SKU 不存在（指向别的租户、或已被删除 —— 外键在，但外键不给中文原因）；
   *   <li>循环引用（逐行判定：这一行的 SKU 会不会让包含链闭合成环）。
   * </ol>
   */
  public BundleComponentCheck check(UUID versionId) {
    UUID tenantId = currentTenantId();
    var v =
        sql.fetchOne(
            "select v.id,v.bundle_id,v.version_no,v.name,v.status,b.code as bundle_code"
                + " from bundle_version v join bundle b on b.tenant_id=v.tenant_id and b.id=v.bundle_id"
                + " where v.tenant_id=? and v.id=?",
            tenantId,
            versionId);
    if (v == null) throw notFound("套装版本不存在");
    UUID bundleId = v.get("bundle_id", UUID.class);
    String versionNo = v.get("version_no", String.class);
    String status = v.get("status", String.class);
    boolean editable = "DRAFT".equals(status);

    var rows =
        sql.fetch(
            "select c.id,c.sku_id,c.quantity,c.role,s.code as sku_code,s.name as sku_name,"
                + "lk.bundle_id as linked_bundle_id "
                + "from bundle_component c "
                + "join bundle_version v on v.tenant_id=c.tenant_id and v.id=c.version_id "
                + "left join sku s on s.tenant_id=c.tenant_id and s.id=c.sku_id "
                + "left join bundle_sku lk on lk.tenant_id=c.tenant_id and lk.sku_id=c.sku_id "
                + "where c.tenant_id=? and c.version_id=? order by s.code",
            tenantId,
            versionId);
    // 包含链邻接表只加载一次：整个租户的边（不是只有这一个版本的），否则查环会漏掉
    // 「经由别的版本的组件绕回来」这种环。
    Map<UUID, List<UUID>> edges = containmentEdges(tenantId);

    List<BundleComponentProblem> problems = new ArrayList<>();
    if (!editable) {
      problems.add(
          problem(
              "VERSION_FROZEN",
              BundleComponentRules.publishedComponentProblem(versionNo),
              null,
              null,
              null));
    }
    if (rows.isEmpty()) {
      problems.add(
          problem("EMPTY_COMPONENT_LIST", BundleRules.componentCountProblem(0), null, null, null));
    }
    Map<UUID, Long> seen = new HashMap<>();
    BigDecimal total = BigDecimal.ZERO;
    for (Record r : rows) {
      UUID componentId = r.get("id", UUID.class);
      UUID skuId = r.get("sku_id", UUID.class);
      String skuCode = r.get("sku_code", String.class);
      BigDecimal quantity = r.get("quantity", BigDecimal.class);
      if (quantity != null) total = total.add(quantity);
      // 同一 SKU 两行：V23 的 UNIQUE(tenant_id,version_id,sku_id) 兜底，这里提前说清后果。
      if (seen.merge(skuId, 1L, Long::sum) > 1) {
        problems.add(
            problem(
                "DUPLICATE_SKU",
                "组件「"
                    + (skuCode == null ? skuId : skuCode)
                    + "」在这个版本里出现了多行：每个组件「每件套装要几件」只能有一个答案，"
                    + "两行时订单展开只能取其中一条，而扣库存会按取到的那条走",
                skuId,
                skuCode,
                componentId));
      }
      String quantityIssue = BundleComponentRules.quantityProblem(quantity);
      if (quantityIssue != null) {
        problems.add(problem("QUANTITY_INVALID", quantityIssue, skuId, skuCode, componentId));
      }
      if (skuCode == null) {
        problems.add(
            problem(
                "COMPONENT_SKU_MISSING",
                "组件 SKU（" + skuId + "）在本租户不存在：套装组件必须指向真实存在的 SKU，" + "否则扣组件库存时扣的是一个不存在的东西",
                skuId,
                null,
                componentId));
        continue;
      }
      // 成环：这一行的 SKU 代表某个套装时，那个套装不能走回本版本所属的套装。
      UUID linked = r.get("linked_bundle_id", UUID.class);
      if (linked != null) {
        List<UUID> path = BundleComponentRules.cyclePath(edges, linked, bundleId);
        if (path != null) {
          problems.add(
              problem(
                  "CIRCULAR_REFERENCE",
                  BundleComponentRules.cycleProblem(ring(tenantId, path)),
                  skuId,
                  skuCode,
                  componentId));
        }
      }
    }
    boolean ok = problems.isEmpty();
    return new BundleComponentCheck(
        v.get("id", UUID.class),
        bundleId,
        v.get("bundle_code", String.class),
        versionNo,
        status,
        BundleRules.statusLabel(status),
        rows.size(),
        total,
        editable,
        ok,
        problems,
        ok
            ? "校验通过：这个版本可以发布（生效区间不重叠的判定归 3.5.1.1 的发布接口）"
            : "上面 "
                + problems.size()
                + " 条都要处理完才能发布。校验是保存前的体检，"
                + "点保存时同样会被拒 —— 这里只是让你一次看全，不用反复提交试错");
  }

  // ================================================================ 逐行增删改

  /**
   * 给草稿版本加一个组件。
   *
   * <p>校验顺序按「先说最根本的那条」排：版本冻结 → 数量 → 组件存在 → 重复 → 环。
   *
   * <p>与 3.5.1.1 的整体替换并存：整体替换是「把清单改成提交的那份」， 这里是「加一行」——工作台上一行一行地加更顺手，而整体替换留给「我已经在别处整理好了一份清单」。
   */
  public BundleComponentItem addComponent(
      AccessService.Access actor, UUID versionId, BundleComponentInput input) {
    UUID tenantId = actor.tenantId();
    var version = requireEditableVersion(tenantId, versionId);
    UUID bundleId = version.get("bundle_id", UUID.class);
    String versionNo = version.get("version_no", String.class);

    String quantityIssue = BundleComponentRules.quantityProblem(input.quantity());
    checkRule(quantityIssue);
    String role = BundleComponentRules.normalize(input.role());
    String note = BundleComponentRules.normalize(input.note());
    checkRule(BundleComponentRules.roleProblem(role));
    checkRule(BundleComponentRules.componentNoteProblem(note));

    UUID skuId = input.skuId();
    var sku = sql.fetchOne("select code,name from sku where tenant_id=? and id=?", tenantId, skuId);
    if (sku == null) {
      throw bad("组件 SKU（" + skuId + "）在本租户不存在：套装组件必须指向真实存在的 SKU，" + "否则扣组件库存时扣的是一个不存在的东西");
    }
    long existing =
        sql.fetchOne(
                "select count(*) from bundle_component where tenant_id=? and version_id=? and sku_id=?",
                tenantId,
                versionId,
                skuId)
            .get(0, Long.class);
    check(
        existing == 0,
        "这个 SKU 已经在这个版本的组件清单里了：每个组件「每件套装要几件」只能有一个答案，" + "两行时订单展开只能取其中一条，而扣库存会按取到的那条走。要改数量请直接改那一行");
    long count =
        sql.fetchOne(
                "select count(*) from bundle_component where tenant_id=? and version_id=?",
                tenantId,
                versionId)
            .get(0, Long.class);
    checkRule(BundleComponentRules.componentCountProblem((int) count + 1));

    checkNoCycle(tenantId, bundleId, skuId, versionNo);

    UUID id = Ids.next();
    try {
      sql.execute(
          "insert into bundle_component(id,tenant_id,version_id,sku_id,quantity,role,note,version)"
              + " values(?,?,?,?,?,?,?,0)",
          id,
          tenantId,
          versionId,
          skuId,
          input.quantity(),
          role,
          note);
    } catch (DataAccessException e) {
      throw conflict(rootMessage(e));
    }
    audit(actor, versionId, "BundleComponentAdded");
    return component(id);
  }

  /**
   * 改一行组件的数量 / 角色 / 备注。
   *
   * <p>SKU 不可改（见 {@link BundleComponentEditInput}）：组件行的身份是 「这个版本里的这个 SKU」，换 SKU 走「删一行 + 加一行」，历史更清楚。
   *
   * <p>数量可以改不会引入新的环（指向没变），所以这里不重查环 —— 环只由「换了哪个 SKU」决定。 这不是省事：{@code guard_bundle_component_cycle}
   * 挂在 INSERT 上， 「改数量导致成环」在数学上不存在。
   */
  public BundleComponentItem editComponent(
      AccessService.Access actor, UUID id, BundleComponentEditInput input) {
    UUID tenantId = actor.tenantId();
    var current =
        sql.fetchOne(
            "select c.version_id,c.version as row_version,v.bundle_id,v.version_no,v.status"
                + " from bundle_component c"
                + " join bundle_version v on v.tenant_id=c.tenant_id and v.id=c.version_id"
                + " where c.tenant_id=? and c.id=?",
            tenantId,
            id);
    if (current == null) throw notFound("组件不存在");
    requireEditableVersion(tenantId, current.get("version_id", UUID.class));
    checkVersion(current.get("row_version", Long.class), input.rowVersion());

    String quantityIssue = BundleComponentRules.quantityProblem(input.quantity());
    checkRule(quantityIssue);
    String role = BundleComponentRules.normalize(input.role());
    String note = BundleComponentRules.normalize(input.note());
    checkRule(BundleComponentRules.roleProblem(role));
    checkRule(BundleComponentRules.componentNoteProblem(note));
    try {
      int affected =
          sql.execute(
              "update bundle_component set quantity=?,role=?,note=?,version=version+1,updated_at=now()"
                  + " where tenant_id=? and id=? and version=?",
              input.quantity(),
              role,
              note,
              tenantId,
              id,
              input.rowVersion());
      check(affected == 1, "这行组件刚被别人改过（行版本不是 " + input.rowVersion() + "），请刷新后重试");
    } catch (DataAccessException e) {
      throw conflict(rootMessage(e));
    }
    audit(actor, id, "BundleComponentUpdated");
    return component(id);
  }

  /** 删一行组件。已发布版本的组件由 V23 触发器拒绝（这里先给一句人话）。 */
  public void deleteComponent(AccessService.Access actor, UUID id, long rowVersion) {
    UUID tenantId = actor.tenantId();
    var current =
        sql.fetchOne(
            "select c.version_id,c.version as row_version,v.bundle_id,v.version_no,v.status"
                + " from bundle_component c"
                + " join bundle_version v on v.tenant_id=c.tenant_id and v.id=c.version_id"
                + " where c.tenant_id=? and c.id=?",
            tenantId,
            id);
    if (current == null) throw notFound("组件不存在");
    requireEditableVersion(tenantId, current.get("version_id", UUID.class));
    checkVersion(current.get("row_version", Long.class), rowVersion);
    try {
      sql.execute(
          "delete from bundle_component where tenant_id=? and id=? and version=?",
          tenantId,
          id,
          rowVersion);
    } catch (DataAccessException e) {
      throw conflict(rootMessage(e));
    }
    audit(actor, id, "BundleComponentDeleted");
  }

  // ================================================================ SKU ↔ 套装指向

  /**
   * 指向列表。
   *
   * <p>这一层是「嵌套套装」的唯一可见面：没有它，「套装 A 含套装 B」在数据上无法表达， 循环也就无从发生（V24 文件头第二节）。
   */
  public BundleSkuPage links(BundleSkuQuery query) {
    int page = Math.max(0, query.page());
    int size = clampSize(query.size());
    StringBuilder where = new StringBuilder(" where l.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenantId());
    String keyword = BundleComponentRules.normalize(query.keyword());
    if (!keyword.isEmpty()) {
      where.append(
          " and (lower(s.code) like ? escape '!' or lower(s.name) like ? escape '!'"
              + " or lower(b.code) like ? escape '!')");
      String like = escapeLike(keyword.toLowerCase(Locale.ROOT));
      for (int i = 0; i < 3; i++) binds.add(like);
    }
    long total =
        sql.fetchOne("select count(*) " + LINK_FROM + where, binds.toArray()).get(0, Long.class);
    if (total == 0) return new BundleSkuPage(List.of(), page, size, 0, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);
    var rows =
        sql.fetch(
            LINK_SELECT + where + " order by b.code,s.code limit ? offset ?", pageBinds.toArray());
    List<BundleSkuRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) items.add(linkRow(r));
    return new BundleSkuPage(items, page, size, total, (int) Math.ceil(total / (double) size));
  }

  /**
   * 新建指向，或改一个 SKU 代表哪个套装（id 为空即新建）。
   *
   * <p><b>改指向是本模块里最危险的操作</b>：它一次性改掉所有把该 SKU 当组件的套装的出边。 所以两件事都要查：
   *
   * <ol>
   *   <li>这个 SKU 是不是已经被别的套装代表（UNIQUE 兜底，但报错要说明「为什么只能一个」）；
   *   <li>改完之后会不会成环（对每个「把它当组件的套装」查可达性，V24 的 {@code guard_bundle_sku_cycle} 兜底）。
   * </ol>
   */
  public BundleSkuRow saveLink(AccessService.Access actor, UUID id, BundleSkuInput input) {
    UUID tenantId = actor.tenantId();
    String note = BundleComponentRules.normalize(input.note());
    checkRule(BundleComponentRules.linkNoteProblem(note));
    UUID bundleId = input.bundleId();
    UUID skuId = input.skuId();
    var bundle =
        sql.fetchOne("select code from bundle where tenant_id=? and id=?", tenantId, bundleId);
    if (bundle == null) throw notFound("套装不存在");
    var sku = sql.fetchOne("select code,name from sku where tenant_id=? and id=?", tenantId, skuId);
    if (sku == null) throw bad("SKU（" + skuId + "）在本租户不存在");

    // 「一个 SKU 至多代表一个套装」：查已存在的指向，但**要排除正在改的这一行** ——
    // 不排除的话，「把这条指向改到另一个套装」永远会被自己拦下，改指向功能等于不存在。
    var owner =
        sql.fetchOne(
            "select bundle_id from bundle_sku where tenant_id=? and sku_id=?"
                + (id == null ? "" : " and id<>?"),
            id == null ? new Object[] {tenantId, skuId} : new Object[] {tenantId, skuId, id});
    if (owner != null && !owner.get("bundle_id", UUID.class).equals(bundleId)) {
      String currentOwner =
          sql.fetchOne(
                  "select code from bundle where tenant_id=? and id=?",
                  tenantId,
                  owner.get("bundle_id", UUID.class))
              .get("code", String.class);
      throw conflict(
          "SKU「"
              + sku.get("code", String.class)
              + "」已经代表套装「"
              + currentOwner
              + "」：一个 SKU 至多代表一个套装，两行时「这个组件展开成什么」有两个答案，"
              + "而 09 订单冻结展开结果时只能取一条。请先解开原指向");
    }

    // 成环预检：所有把这个 SKU 当组件的套装，都不能被新指向的套装走回去。
    if (id == null || !bundleId.equals(currentLinkBundle(tenantId, id))) {
      checkLinkNoCycle(tenantId, bundleId, sku.get("code", String.class));
    }
    try {
      if (id == null) {
        UUID newId = Ids.next();
        sql.execute(
            "insert into bundle_sku(id,tenant_id,bundle_id,sku_id,note,version) values(?,?,?,?,?,0)",
            newId,
            tenantId,
            bundleId,
            skuId,
            note);
        audit(actor, newId, "BundleSkuLinked");
        return link(newId);
      }
      var current =
          sql.fetchOne("select version from bundle_sku where tenant_id=? and id=?", tenantId, id);
      if (current == null) throw notFound("指向不存在");
      checkVersion(current.get("version", Long.class), input.rowVersion());
      sql.execute(
          "update bundle_sku set bundle_id=?,sku_id=?,note=?,version=version+1,updated_at=now()"
              + " where tenant_id=? and id=? and version=?",
          bundleId,
          skuId,
          note,
          tenantId,
          id,
          input.rowVersion());
      audit(actor, id, "BundleSkuRelinked");
      return link(id);
    } catch (DataAccessException e) {
      throw conflict(rootMessage(e));
    }
  }

  /**
   * 解开指向。
   *
   * <p>已发布版本里的组件行引用过这个 SKU 时拒绝：删掉指向后， 那些**冻结的**组件会从「套装 B 的可售 SKU」静默降级成普通 SKU —— 展开结果变了，而那一版是已发布的历史。
   */
  public void deleteLink(AccessService.Access actor, UUID id, long rowVersion) {
    UUID tenantId = actor.tenantId();
    var current =
        sql.fetchOne(
            "select sku_id,version from bundle_sku where tenant_id=? and id=?", tenantId, id);
    if (current == null) throw notFound("指向不存在");
    checkVersion(current.get("version", Long.class), rowVersion);
    UUID skuId = current.get("sku_id", UUID.class);
    long published =
        sql.fetchOne(
                "select count(*) from bundle_component c join bundle_version v"
                    + " on v.tenant_id=c.tenant_id and v.id=c.version_id"
                    + " where c.tenant_id=? and c.sku_id=? and v.status='PUBLISHED'",
                tenantId,
                skuId)
            .get(0, Long.class);
    check(
        published == 0,
        "有 "
            + published
            + " 个已发布版本的组件引用着这个 SKU，不能解指向：解开后那些组件会从「套装的可售 SKU」"
            + "静默降级成普通 SKU，而已发布版本里的组件是冻结的历史 —— "
            + "09 订单冻结的展开结果会变。要停止使用请归档套装，或新建一个版本接替");
    try {
      sql.execute(
          "delete from bundle_sku where tenant_id=? and id=? and version=?",
          tenantId,
          id,
          rowVersion);
    } catch (DataAccessException e) {
      throw conflict(rootMessage(e));
    }
    audit(actor, id, "BundleSkuUnlinked");
  }

  // ================================================================ 内部

  private BundleSkuRow link(UUID id) {
    Record r = sql.fetchOne(LINK_SELECT + " where l.tenant_id=? and l.id=?", currentTenantId(), id);
    if (r == null) throw notFound("指向不存在");
    return linkRow(r);
  }

  private UUID currentLinkBundle(UUID tenantId, UUID linkId) {
    var row =
        sql.fetchOne(
            "select bundle_id from bundle_sku where tenant_id=? and id=?", tenantId, linkId);
    return row == null ? null : row.get("bundle_id", UUID.class);
  }

  /**
   * 校验「把 skuId 当组件放进 bundleId 的版本」不会成环。
   *
   * <p>口径与 V24 的 {@code find_bundle_cycle_path} 一致：从组件指向的套装出发， 沿包含边能不能走回 {@code bundleId}（规则
   * R6）。走不回就是合法的嵌套。
   */
  private void checkNoCycle(UUID tenantId, UUID bundleId, UUID skuId, String versionNo) {
    var linked =
        sql.fetchOne(
            "select bundle_id from bundle_sku where tenant_id=? and sku_id=?", tenantId, skuId);
    if (linked == null) return; // 普通 SKU：套装之间的边根本不存在
    List<UUID> path =
        BundleComponentRules.cyclePath(
            containmentEdges(tenantId), linked.get("bundle_id", UUID.class), bundleId);
    if (path != null) {
      throw conflict(
          BundleComponentRules.cycleProblem(ring(tenantId, path)) + "（在套装「" + versionNo + "」上）");
    }
  }

  /** 改指向前的成环预检：所有把该 SKU 当组件的套装都不能被新指向的套装走回去。 */
  private void checkLinkNoCycle(UUID tenantId, UUID bundleId, String skuCode) {
    Map<UUID, List<UUID>> edges = containmentEdges(tenantId);
    var holders =
        sql.fetch(
            "select distinct v.bundle_id from bundle_component c"
                + " join bundle_version v on v.tenant_id=c.tenant_id and v.id=c.version_id"
                + " join bundle_sku l on l.tenant_id=c.tenant_id and l.sku_id=c.sku_id"
                + " where c.tenant_id=? and l.bundle_id=?",
            tenantId,
            bundleId);
    for (Record r : holders) {
      UUID holder = r.get("bundle_id", UUID.class);
      List<UUID> path = BundleComponentRules.cyclePath(edges, bundleId, holder);
      if (path != null) {
        String holderCode = bundleCode(tenantId, holder);
        String targetCode = bundleCode(tenantId, bundleId);
        throw conflict(
            BundleComponentRules.relinkProblem(
                ring(tenantId, path), skuCode, holderCode, targetCode));
      }
    }
  }

  /**
   * 加载整个租户的包含边：套装 → 它直接包含的套装。
   *
   * <p>为什么加载全租户而不是只加载这一个版本：环可能绕经<b>别的版本</b>的组件 （A 的组件里有 B，B 的 v2 草稿里有 C，C 的 v1 里有 A）。
   * 只看当前版本会漏掉这种环，而漏掉的环会在对方版本发布的那一刻才显形。
   *
   * <p>只取「SKU 代表套装」的那部分边：普通 SKU 组件不产生套装之间的边。 这让邻接表的规模等于「嵌套组件数」，而不是「组件总数」。
   */
  private Map<UUID, List<UUID>> containmentEdges(UUID tenantId) {
    Map<UUID, List<UUID>> edges = new LinkedHashMap<>();
    var rows =
        sql.fetch(
            "select distinct v.bundle_id as from_bundle,l.bundle_id as to_bundle"
                + " from bundle_component c"
                + " join bundle_version v on v.tenant_id=c.tenant_id and v.id=c.version_id"
                + " join bundle_sku l on l.tenant_id=c.tenant_id and l.sku_id=c.sku_id"
                + " where c.tenant_id=? and l.bundle_id<>v.bundle_id",
            tenantId);
    for (Record r : rows) {
      edges
          .computeIfAbsent(r.get("from_bundle", UUID.class), k -> new ArrayList<>())
          .add(r.get("to_bundle", UUID.class));
    }
    return edges;
  }

  /**
   * 把一条包含路径接成闭合的环文案 {@code A → B → C → A}。
   *
   * <p>用<b>编码</b>而不是 UUID：报错是给人看的，运营在页面上看到的是套装编码 （{@code BND-SUMMER-01}），不是一串十六进制。
   */
  private String ring(UUID tenantId, List<UUID> path) {
    Map<UUID, String> codes = new LinkedHashMap<>();
    for (UUID id : path) codes.put(id, bundleCode(tenantId, id));
    List<String> ordered = new ArrayList<>(path.size());
    for (UUID id : path) ordered.add(codes.getOrDefault(id, id.toString()));
    return BundleComponentRules.ringText(ordered);
  }

  private String bundleCode(UUID tenantId, UUID bundleId) {
    var row =
        sql.fetchOne("select code from bundle where tenant_id=? and id=?", tenantId, bundleId);
    return row == null ? bundleId.toString() : row.get("code", String.class);
  }

  /** 取回版本行，并要求它是草稿（已发布版本的组件一律不可改）。 */
  private Record requireEditableVersion(UUID tenantId, UUID versionId) {
    var version =
        sql.fetchOne(
            "select bundle_id,version_no,status from bundle_version where tenant_id=? and id=?",
            tenantId,
            versionId);
    if (version == null) throw notFound("套装版本不存在");
    checkEditable(version.get("status", String.class), version.get("version_no", String.class));
    return version;
  }

  private static void checkEditable(String status, String versionNo) {
    check("DRAFT".equals(status), BundleComponentRules.publishedComponentProblem(versionNo));
  }

  private BundleComponentItem componentItem(Record r) {
    String versionStatus = r.get("status", String.class);
    UUID linkedId = r.get("linked_bundle_id", UUID.class);
    return new BundleComponentItem(
        r.get("id", UUID.class),
        r.get("version_id", UUID.class),
        r.get("bundle_id", UUID.class),
        r.get("bundle_code", String.class),
        r.get("version_no", String.class),
        r.get("name", String.class),
        versionStatus,
        BundleRules.statusLabel(versionStatus),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("quantity", BigDecimal.class),
        r.get("role", String.class),
        r.get("note", String.class),
        linkedId != null,
        linkedId,
        r.get("linked_bundle_code", String.class),
        "DRAFT".equals(versionStatus),
        r.get("version", Long.class));
  }

  private static BundleComponentProblem problem(
      String code, String message, UUID skuId, String skuCode, UUID componentId) {
    return new BundleComponentProblem(code, message, skuId, skuCode, componentId);
  }

  private static BundleSkuRow linkRow(Record r) {
    return new BundleSkuRow(
        r.get("id", UUID.class),
        r.get("bundle_id", UUID.class),
        r.get("bundle_code", String.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("note", String.class),
        r.get("used_by_count", Long.class),
        r.get("used_by_published", Long.class) > 0,
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static int clampSize(int size) {
    if (size <= 0) return 20;
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private static String escapeLike(String value) {
    return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  private static void check(boolean ok, String message) {
    if (!ok) throw MediaRules.bad(message);
  }

  private static void checkRule(String problem) {
    check(problem == null, problem);
  }

  private static void checkVersion(long current, long submitted) {
    check(current == submitted, "这条记录刚被别人改过（版本是 " + current + "，你提交的是 " + submitted + "），请刷新后重试");
  }

  /** 当前 UTC 时刻。写 timestamptz 列一律配 {@code ?::timestamptz}，不经过 Timestamp.valueOf。 */
  static OffsetDateTime utcNow() {
    return OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static String rootMessage(DataAccessException e) {
    var root = e.getRootCause();
    String message = root == null ? e.getMessage() : root.getMessage();
    return message == null ? e.getClass().getSimpleName() : message.replace('\n', ' ');
  }

  void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        resourceId,
        action);
  }
}
