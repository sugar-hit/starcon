package com.starcon.trade;

import static com.starcon.trade.AftersalesReasonModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 11.1.2.1 售后原因分类。
 *
 * <p><b>本类是 aftersales_reason_* 簇（V158）的唯一写入所有者</b>，
 * 对 {@code aftersales_case} 簇（V156，11.1.1.1 车道的表）**只读** ——
 * 刻意不加外键、不 ALTER，跨车道 DDL 回归面 = 0。
 *
 * <p><b>五条硬纪律</b>：
 *
 * <ol>
 *   <li><b>「必须强制选择分类而不是留自由文本」是三层防线，本层是第一层。</b>
 *       服务层拒绝「零个原因」「两个主因」「零个主因」「重复分类」「权重缺失/越界」，
 *       <b>DB 层由提交期 DEFERRABLE 约束触发器再挡一次</b>（V158 第七节 ③④）
 *       —— 绕过后端直接开 psql 写 SQL 也存不进去一个零成员的归因。
 *       自由文本 {@code note} 挂在<b>集合</b>上而不是成员上，所以「只写了一段话」
 *       在类型与物理两层都不可能成立。
 *   <li><b>筛选、聚合、报表口径全部在数据库层带 {@code tenant_id}。</b>
 *       「查全量再在 Java 里过滤」在本模块的后果比别处更重：原因口径一旦串了租户，
 *       <b>退货率分析会整体失真</b> —— 这正是蓝图加粗句要防的事。
 *       本类每一条 {@code where} 都拼进 SQL 字符串，唯一的 Java 侧遍历是
 *       ①「≤3 层的祖先链校验」与 ②「把七类词表补零成完整行」，
 *       两者都**不是**对结果集的筛选。
 *   <li><b>七类词表按租户幂等落库，不写死在 Java 数组里当数据源。</b>
 *       {@link #ensureBaseline()} 用 {@code INSERT ... ON CONFLICT DO NOTHING}
 *       把蓝图点名的七类写成<b>普通的租户行</b>：能改名、能排序、能停用、能加子节点、
 *       能加树外的新根。枚举 {@link AftersalesReasonRootKind} 只用来保证
 *       「根节点必须归入七类之一」这条 CHECK 与服务端校验一致。
 *   <li><b>平台原因码映射是 trade 自己的表，不 join channel。</b>
 *       上游 2.1.2.1 的 {@code platform_enum_mapping} 在 channel 服务独立库
 *       starcon_channel（实查 V1__channel_registry_connections_and_shops.sql /
 *       V3__platform_enum_tenant_override.sql），按 AGENTS.md 模块边界纪律
 *       禁止跨服务 SQL / FK / 视图。本表形状与它<b>语义一致</b>，
 *       同步器按同一形状写入时表结构与查询都不用改。
 *   <li><b>时间绑定一律 {@code ?::timestamptz} + {@link OffsetDateTime}。</b>
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s（本项目所在时区），
 *       「按归因时刻筛报表」会因此筛出错的区间。
 * </ol>
 */
@Service
public class AftersalesReasonService {

  /** 分类树最大层数（与 V158 的 {@code depth BETWEEN 1 AND 3} 一致）。 */
  public static final int MAX_DEPTH = 3;

  /**
   * 单次归因可选的原因个数上限。
   *
   * <p>蓝图要求「支持多选」所以上限不能是 1；但也必须有上限 ——
   * 把整棵树几十个叶子全勾上、权重全是 1，那不是归因，是没归因。
   * 主因恰好一个由 DB 层钉死，这里管的是「几个次因还算归因」。
   */
  public static final int MAX_ATTRIBUTION_ITEMS = 10;

  /** 根口径编码前缀。基线词表的七类编码都是 {@code ROOT.<KIND>}。 */
  private static final String BASELINE_PREFIX = "ROOT.";

  private final DSLContext sql;

  public AftersalesReasonService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：哪些维度真有依据、上游通没通、下游交付没交付、平台原因码映射归谁。
   *
   * <p>存在的理由与 V156 相同：让界面能说清「为什么平台原因码是租户自己维护的、
   * 为什么没有店铺维度」，而不是让空下拉框显示成空白让人以为「这个租户没有映射」。
   */
  public AftersalesReasonCapabilities capabilities() {
    boolean seeded = baselineSeeded();
    return new AftersalesReasonCapabilities(
        List.of(
            "原因分类树 aftersales_reason_category（parent_id / depth / category_code /"
                + " category_status / sort_order 全是真列，depth 由触发器维护）",
            "根口径 aftersales_reason_category.root_kind（七类，报表的汇总锚点）",
            "归因 aftersales_reason_attribution_set + _item（多选 + 恰好一个主因 + 显式权重）",
            "当前生效归因 部分唯一索引 UNIQUE(tenant_id,case_id) WHERE superseded_at IS NULL",
            "自由文本 aftersales_reason_attribution_set.attribution_note（补充说明，非归因本身）",
            "平台原因码映射 aftersales_reason_platform_mapping（**trade 自有表**，非跨服务读）",
            "报表口径 按**当前生效**归因集合统计，primary 与 secondary 分开计数"),
        List.of(
            new AftersalesReasonDimension(
                "platformShopScope",
                "平台原因码映射的店铺粒度",
                false,
                "2.1.2.1 平台枚举映射（channel 服务独立库 starcon_channel）",
                "上游 2.1.2.1 已交付，但它的 platform_enum_mapping / platform_enum_override"
                    + " 落在 **channel 服务**的独立库里，**不是 trade 库**。"
                    + "按 AGENTS.md 模块边界纪律**禁止跨服务 SQL / FK / 视图** ⇒ 本服务读不到它，"
                    + "更不能 join。所以本模块在 **trade 库**建自己的映射表"
                    + "（形状与 channel 的枚举映射语义一致：内部口径 ⇄ 平台码 + 来源 + 采集时刻），"
                    + "同步器将来按**同一形状**写入时**表结构与查询都不用改**。"
                    + "当前粒度只能到**平台级**：卷 2 的店铺表也在 channel 独立库。"),
            new AftersalesReasonDimension(
                "qualityAnalysis",
                "质量归因分析",
                false,
                "11.5.2.1 质量分析",
                "下游未交付，本模块**不假装已对接**。本模块做的是它要消费的**统一口径**："
                    + "当前生效归因、主因与次因分开计数、占比 = 归因单数 ÷ 生效归因单总数、"
                    + "排序键确定。它交付后直接取用 /reports，不必自己再定一套。"),
            new AftersalesReasonDimension(
                "supplierClaim",
                "供应商索赔",
                false,
                "6.4.2.2 索赔（6 采购与供应）",
                "下游未交付，本模块**不假装已对接**。本模块只保证它将来要用的两样东西"
                    + "现在是可靠的：分类**编码**租户内唯一且**不可改**（历史索赔按编码对齐），"
                    + "以及分类停用是一个显式动作而历史归因仍指向它 —— "
                    + "停用不会让已经成立的索赔失去归因依据。")),
        Arrays.stream(AftersalesReasonRootKind.values())
            .map(k -> new AftersalesReasonEnumOption(k.name(), k.label()))
            .toList(),
        Arrays.stream(AftersalesPlatformSource.values())
            .map(p -> new AftersalesReasonEnumOption(p.name(), p.label()))
            .toList(),
        seeded,
        MAX_DEPTH,
        MAX_ATTRIBUTION_ITEMS,
        false,
        "2.1.2.1 平台枚举映射（channel 服务独立库；本表为 trade 自有副本，按同一形状同步）",
        false,
        false,
        "平台原因码映射落在 trade 库的 aftersales_reason_platform_mapping，**不是** channel 的 "
            + "platform_enum_mapping —— 后者在别的服务的独立库里，禁止跨服务 SQL / FK / 视图。"
            + "本表与它形状一致，同步器按同一形状写入时无需改结构。"
            + "mapping_source 区分 TENANT_MAINTAINED 与 MODULE_2_ENUM_MAPPING，"
            + "后者必须带 sourceReference；captured_at 是**采集时刻**不是登记时刻。",
        "三层强制：(1) 服务层拒绝零个原因 / 两个主因 / 零个主因 / 重复分类 / 权重缺失或越界；"
            + "(2) DB 层 DEFERRABLE INITIALLY DEFERRED 约束触发器在**提交瞬间**判定"
            + "「至少一个成员 + 恰好一个主因」，绕过后端直接写 SQL 也存不进去；"
            + "(3) 自由文本只挂在归因集合上，而零成员的集合根本提交不了 ⇒ "
            + "「只写了一段话没选分类」在物理上不存在。",
        "只统计**当前生效**的归因集合（superseded_at IS NULL）；主因（is_primary）与次因分开计数，"
            + "合成一个数会让既是主因又在别的分类里当次因的单被重复计入。"
            + "占比 = 该口径归因单数 ÷ 生效归因单总数，两位小数四舍五入。"
            + "排序键 (root_kind, depth, sort_order, category_code, id) 唯一确定，"
            + "两次导出同一批数据必须逐行相同。");
  }

  // ================================================================ 基线词表

  /**
   * 按租户幂等落库蓝图点名的七类根口径。
   *
   * <p><b>返回本次是否真的落了新行</b>（用于告诉界面「这是该租户第一次打开本模块」）。
   * {@code ON CONFLICT (tenant_id, category_code) DO NOTHING} 让它可以被每个读入口
   * 无条件调用：重复调用是一次廉价的 no-op，不会产生重复行，也不需要额外的「是否已初始化」状态位。
   *
   * <p>⚠️ 这**不是**「用硬编码数组当数据源」：落库之后七类就是普通的租户行 ——
   *   能改名（{@code updateCategory}）、能改排序、能停用（停用后不再可被新归因选中，
   *   历史归因仍指向它）、能在底下加子节点。Java 侧的枚举只用于
   *   「根节点必须归入七类之一」这条服务端校验与 DB CHECK 保持一致。
   */
  public boolean ensureBaseline() {
    // 「本次读取是否真的落了新行」= 插入**前后**行数之差。
    // ⚠️ 不能写成「插入后行数 < 7」：那在插入成功之后恒为 false，
    // 于是 baselineSeededOnThisRead 永远是 false，界面上的「首次初始化」提示就成了死代码。
    long before = baselineCount();
    // ⚠️ 占位符与绑定必须逐列对齐：列顺序是
    //   (id, tenant_id, created_by, updated_by, category_code, root_kind,
    //    category_status, sort_order, depth, category_name) = 10 列 7 个占位符
    //   （root_kind 与 category_status 是**带引号的字面量**）。
    // ⚠️ root_kind 必须是 'X' 而不是裸的 X：裸标识符会被 PG 当成列名，
    //   报「column quality_defect does not exist」。它是编译期枚举常量而不是用户输入，
    //   所以拼进 SQL 不构成注入面。
    List<Object> binds = new ArrayList<>();
    StringBuilder values = new StringBuilder();
    int sort = 0;
    for (AftersalesReasonRootKind kind : AftersalesReasonRootKind.values()) {
      if (sort > 0) values.append(",");
      values
          .append("(?,?::uuid,?::uuid,?::uuid,?,'")
          .append(kind.name())
          .append("','ACTIVE',?,1,?)");
      UUID systemActor = UUID.randomUUID();
      binds.add(UUID.randomUUID()); // id
      binds.add(currentTenant()); // tenant_id
      binds.add(systemActor); // created_by —— 基线词表是系统代建的
      binds.add(systemActor); // updated_by —— 同上
      binds.add(BASELINE_PREFIX + kind.name()); // category_code
      binds.add(sort * 10); // sort_order：留出 10 的间隔，方便运营在两根之间插一个自定义根
      binds.add(kind.label()); // category_name —— 七类逐字对齐蓝图原文
      sort++;
    }
    sql.execute(
        "insert into aftersales_reason_category(id,tenant_id,created_by,updated_by,category_code,"
            + " root_kind,category_status,sort_order,depth,category_name)"
            + " values "
            + values
            + " on conflict (tenant_id,category_code) do nothing",
        binds.toArray());
    return baselineCount() > before;
  }

  private long baselineCount() {
    return sql.fetchOne(
            "select count(*) from aftersales_reason_category"
                + " where tenant_id = ?::uuid and category_code like ?",
            currentTenant(),
            BASELINE_PREFIX + "%")
        .get(0, Long.class);
  }

  private boolean baselineSeeded() {
    return baselineCount() == AftersalesReasonRootKind.values().length;
  }

  // ================================================================ 分类树

  /**
   * 读分类树（整树或某支）。
   *
   * <p><b>路径、层级与「是否可被归因」全部由一个递归 CTE 在数据库层算出来</b>
   * （{@code t} 自根向下累加 codePath / namePath / allActive）。
   * 「一个停用的父节点，它的子节点该不该能选」这条规则如果在前端算，
   * 每个组件都会抄一遍，早晚出现「一处整支灰、一处只有父节点灰」的不一致 ——
   * 而这条规则直接决定报表会不会把数据算进一个已作废的口径。
   *
   * <p>{@code sub} 是第二个递归 CTE：按 {@code rootKind} 筛某一支时用它收集后代。
   * 不传 {@code rootKind} 时它<b>不出现在 SQL 里</b>，而不是传一个空集合进去。
   */
  public AftersalesReasonTree tree(AftersalesReasonTreeQuery query) {
    boolean seeded = ensureBaseline();

    String status = isSet(query.categoryStatus())
        ? parseEnum(AftersalesReasonCategoryStatus.class, query.categoryStatus(), "分类状态").name()
        : null;
    String rootKind =
        isSet(query.rootKind())
            ? parseEnum(AftersalesReasonRootKind.class, query.rootKind(), "根口径").name()
            : null;
    boolean assignableOnly = Boolean.TRUE.equals(query.assignableOnly());

    List<Object> binds = new ArrayList<>();
    // t 的锚点：根节点。
    binds.add(currentTenant());
    // t 的递归项：子节点（深度上限由 V158 的 CHECK 保证，这里再挡一层不让 CTE 自己跑飞）。
    binds.add(currentTenant());
    StringBuilder with = new StringBuilder();
    if (rootKind != null) {
      // sub：某支的后代集合。锚点就是 root_kind 匹配的那些根。
      with
          .append(
              ", sub as (select c.id from aftersales_reason_category c"
                  + " where c.tenant_id = ?::uuid and c.root_kind = ?"
                  + " union all select c.id from aftersales_reason_category c"
                  + " join sub on c.parent_id = sub.id where c.tenant_id = ?::uuid)");
      binds.add(currentTenant());
      binds.add(rootKind);
      binds.add(currentTenant());
    }
    StringBuilder where = new StringBuilder(" where c.tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (status != null) {
      where.append(" and c.category_status = ?");
      binds.add(status);
    }
    if (rootKind != null) {
      where.append(" and (c.root_kind = ? or c.id in (select id from sub))");
      binds.add(rootKind);
    }
    if (assignableOnly) {
      // 「可被新归因选中」= 自己 ACTIVE 且**全部祖先** ACTIVE（allActive 由 CTE 累加而来）。
      where.append(" and c.category_status = 'ACTIVE' and t.all_active");
    }

    List<Record> rows =
        sql.fetch(
            "with recursive t as ("
                // ⚠️ 锚点里的 code_path / name_path 必须**显式 ::text**：
                //   递归项是 `path || '/' || code`，`||` 的结果是 text，
                //   而锚点若留在 varchar(48) 上，PG 会报
                //   「recursive query t column 3 has type character varying(48)
                //     in non-recursive term but type character varying overall」。
                + "  select c.id, c.parent_id, c.category_code::text as code_path,"
                + "         c.category_name::text as name_path,"
                + "         (c.category_status = 'ACTIVE') as all_active"
                + "    from aftersales_reason_category c"
                + "   where c.tenant_id = ?::uuid and c.parent_id is null"
                + "  union all"
                + "  select c.id, c.parent_id, t.code_path || '/' || c.category_code,"
                + "         t.name_path || ' / ' || c.category_name,"
                + "         t.all_active and (c.category_status = 'ACTIVE')"
                + "    from aftersales_reason_category c join t on c.parent_id = t.id"
                + "   where c.tenant_id = ?::uuid and c.depth <= "
                + MAX_DEPTH
                + ")"
                + with
                + " select c.id, c.parent_id, c.category_code, c.category_name, c.root_kind,"
                + "        c.category_status, c.sort_order, c.depth, c.description,"
                + "        t.code_path, t.name_path, t.all_active,"
                + "        (select count(*) from aftersales_reason_category ch"
                + "          where ch.tenant_id = c.tenant_id and ch.parent_id = c.id) as child_count,"
                + "        (select count(*) from aftersales_reason_attribution_item ai"
                + "          where ai.tenant_id = c.tenant_id and ai.category_id = c.id)"
                + "          as attribution_count"
                + "   from aftersales_reason_category c join t on t.id = c.id"
                + where
                // 排序键必须**唯一确定**：sort_order 允许重复，单靠它排会随执行计划变。
                + " order by c.depth, c.sort_order, c.category_code, c.id",
            binds.toArray());

    Map<String, String> rootLabels = rootKindLabels();
    List<AftersalesReasonTreeNode> nodes = new ArrayList<>();
    long active = 0;
    for (Record r : rows) {
      String st = r.get("category_status", String.class);
      boolean allActive = r.get("all_active", Boolean.class);
      boolean assignable = "ACTIVE".equals(st) && allActive;
      String reason =
          assignable
              ? null
              : !"ACTIVE".equals(st)
                  ? "分类已停用：停用后不再可被新归因选中，历史归因仍指向它"
                  : "上级分类已停用";
      if ("ACTIVE".equals(st)) active++;
      String kind = r.get("root_kind", String.class);
      nodes.add(
          new AftersalesReasonTreeNode(
              r.get("id", UUID.class),
              r.get("parent_id", UUID.class),
              r.get("category_code", String.class),
              r.get("category_name", String.class),
              kind,
              kind == null ? null : rootLabels.get(kind),
              st,
              statusLabel(st),
              r.get("sort_order", Integer.class),
              r.get("depth", Integer.class),
              r.get("child_count", Long.class),
              r.get("attribution_count", Long.class),
              assignable,
              reason,
              r.get("code_path", String.class),
              r.get("name_path", String.class),
              r.get("description", String.class)));
    }

    return new AftersalesReasonTree(
        nodes,
        nodes.size(),
        active,
        nodes.size() - active,
        MAX_DEPTH,
        baselineSeeded(),
        seeded,
        "depth, sort_order, category_code, id —— **排序键唯一确定**：sort_order 允许重复，"
            + "单靠它排会让同一层的兄弟顺序随执行计划变，而蓝图要求「报表用统一口径」",
        "层级 1.."
            + MAX_DEPTH
            + "，由触发器 guard_aftersales_reason_category_tree 维护（不可写成假深度）。"
            + "根节点必须声明 root_kind（七类之一），子节点必须为 null —— "
            + "否则报表无法确定「这个分类该汇总到哪一类」。"
            + "分类树是**可维护结构**：编码租户内唯一且**不可改**（报表与 6 采购的历史数据按编码对齐），"
            + "改名 / 排序 / 停用 / 加子节点都可以。停用是口径变更：不再可被新归因选中，历史归因仍指向它。",
        rootKind == null
            ? (status == null && !assignableOnly ? "全树" : "全树（已按筛选条件过滤）")
            : "根口径 " + rootKind + " 的整支子树");
  }

  // ================================================================ 分类维护

  /**
   * 新建一个分类。
   *
   * <p>⚠️ <b>编码不可改是硬规则</b>（V158 的列级白名单根本没授 {@code category_code}）：
   * 11.5.2 与 6 采购的历史数据按编码对齐，改编码会让全部历史归因对不上账。
   * 编码在这里做格式校验只是给人话错误，真正的唯一性由数据库的
   * {@code UNIQUE(tenant_id, category_code)} 保证。
   */
  public AftersalesReasonTreeNode createCategory(AftersalesReasonCategoryCreate request, UUID actorId) {
    if (request == null) {
      throw new IllegalArgumentException("请求体不能为空");
    }
    String code = requireCode(request.categoryCode());
    String name = requireText(request.categoryName(), "分类名称", 128);
    String description = clamp(request.description(), 500);
    int sort = request.sortOrder() == null ? 0 : request.sortOrder();

    String rootKind;
    if (request.parentId() == null) {
      // 建根：必须归入七类之一（与 V158 的 root_kind CHECK 一致）。
      if (!isSet(request.rootKind())) {
        throw new IllegalArgumentException(
            "新建根分类时必须给出 rootKind（"
                + Arrays.toString(AftersalesReasonRootKind.values())
                + "）。根节点不归类的话，报表无法确定该汇总到哪一类");
      }
      rootKind = parseEnum(AftersalesReasonRootKind.class, request.rootKind(), "根口径").name();
    } else {
      // 建子节点：rootKind 必须为 null。给子节点编一个 root_kind 会让同一批数据
      // 在两个口径里被各计一次 —— 正是加粗句担心的「统计失真」。
      if (isSet(request.rootKind())) {
        throw new IllegalArgumentException(
            "新建子分类时不能给 rootKind（rootKind 只属于根节点）。"
                + "要新增一个归类，请新建一个根分类");
      }
      rootKind = null;
      requireAssignableCategory(request.parentId(), "父分类");
    }

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    try {
      sql.execute(
          "insert into aftersales_reason_category(id,tenant_id,parent_id,category_code,"
              + " category_name,root_kind,category_status,sort_order,depth,description,"
              + " created_by,updated_by,created_at,updated_at)"
              // ⚠️ 列 14 个 ⇒ 占位符必须**正好** 14 个值（7 个字面量 + 7 个绑定）。
            //   多写一个 `?` 不会在编译期被发现，只会在跑时抛
            //   「未设定参数值 13 的内容」—— 而错误信息完全不提是哪张表。
            + " values (?,?::uuid,?::uuid,?,?,?,'ACTIVE',?,1,?,?::uuid,?::uuid,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          request.parentId(),
          code,
          name,
          rootKind,
          sort,
          description,
          actorId,
          actorId,
          now,
          now);
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      if (isUniqueViolation(e)) {
        throw new IllegalArgumentException(
            "分类编码在本租户内已存在：" + code + "。编码是报表与下游对齐的锚点，必须唯一 —— 请换一个");
      }
      throw e;
    }
    return findNode(id);
  }

  /**
   * 更新一个分类。
   *
   * <p>⚠️ <b>可更新的列只有这五个</b>（{@code category_name} / {@code category_status} /
   * {@code sort_order} / {@code parent_id} / {@code description}），
   * 与 V158 的列级白名单逐字对应。{@code root_kind} 与 {@code category_code}
   * 刻意不可改：前者是报表汇总锚点（改它会把全部历史归因重新归到另一个口径），
   * 后者是下游对齐键。要「换口径」请新建分类再迁归因。
   *
   * <p><b>挂到已停用的父节点下会被 DB 触发器拒绝</b>，这里先做一次服务层预检只是为了给人话错误。
   */
  public AftersalesReasonTreeNode updateCategory(
      UUID id, AftersalesReasonCategoryUpdate request, UUID actorId) {
    if (request == null) {
      throw new IllegalArgumentException("请求体不能为空");
    }
    Record existing =
        sql.fetchOne(
            "select id, category_code, category_name, parent_id, category_status, sort_order,"
                + " description from aftersales_reason_category where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    if (existing == null) {
      throw new IllegalArgumentException("原因分类不存在或不属于当前租户：" + id);
    }

    String name = isSet(request.categoryName()) ? requireText(request.categoryName(), "分类名称", 128) : null;
    String status =
        isSet(request.categoryStatus())
            ? parseEnum(AftersalesReasonCategoryStatus.class, request.categoryStatus(), "分类状态").name()
            : null;
    Integer sort = request.sortOrder();
    // description 特殊：null = 不改，空串 = 清空。其它字段没有这个歧义
    // （本接口不接受「把名字改成空」这种请求）。
    String description = request.description() == null ? null : clamp(request.description(), 500);

    // ⚠️ parentId 也要算进「有没有要改」里：漏了它的话，一次**只**改父节点的请求
    //   （比如把分类挪到另一个根下）会被判成「没有要改的内容」而 400，
    //   而成环检测那条路径根本走不到。
    if (name == null
        && status == null
        && sort == null
        && description == null
        && request.parentId() == null
        && !request.moveToRoot()) {
      throw new IllegalArgumentException(
          "没有要改的内容。可改的是：名称、状态、排序位、说明、父分类（rootKind 与编码不可改）");
    }

    if (request.moveToRoot()) {
      if (request.parentId() != null) {
        throw new IllegalArgumentException("moveToRoot=true 时不能同时给 parentId");
      }
      if (existing.get("parent_id", UUID.class) == null) {
        throw new IllegalArgumentException(
            "该分类已经是根节点。要给它换一个根口径请新建一个根分类 ——"
                + "rootKind 刻意不可改，因为它是报表的汇总锚点");
      }
      throw new IllegalArgumentException(
          "把已有的分类提升为根节点需要给它一个 rootKind（七类之一），而 rootKind 是不可改列 ——"
              + "这是刻意的：改 rootKind 会让全部历史归因被重新归到另一个口径。请新建一个根分类并迁归因");
    }
    if (request.parentId() != null) {
      if (request.parentId().equals(id)) {
        throw new IllegalArgumentException("分类不能挂到它自己下面");
      }
      requireAssignableCategory(request.parentId(), "父分类");
    }

    sql.execute(
        "update aftersales_reason_category"
            + " set category_name = coalesce(?, category_name),"
            + "     category_status = coalesce(?, category_status),"
            + "     sort_order = coalesce(?, sort_order),"
            + "     description = coalesce(?, description),"
            + "     parent_id = coalesce(?::uuid, parent_id),"
            + "     updated_by = ?::uuid, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        name,
        status,
        sort,
        description,
        request.parentId(),
        actorId,
        OffsetDateTime.now(ZoneOffset.UTC),
        currentTenant(),
        id);
    return findNode(id);
  }

  /**
   * 删除一个分类（叶子且从未被引用）。
   *
   * <p>⚠️ <b>「删不掉」不是缺陷，是口径完整性要求。</b>
   * 被归因引用过的分类**不能删** —— 删了之后 11.5.2 的历史归因会指向一个不存在的分类，
   * 退货率分析的分母就变了。要「下线」一个分类请改状态：停用后不再可被新归因选中，
   * 而历史归因仍然指向它，两个口径都还在。
   */
  public void deleteCategory(UUID id) {
    Record existing =
        sql.fetchOne(
            "select category_code, category_name from aftersales_reason_category"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    if (existing == null) {
      throw new IllegalArgumentException("原因分类不存在或不属于当前租户：" + id);
    }
    long children =
        sql.fetchOne(
                "select count(*) from aftersales_reason_category where tenant_id = ?::uuid and parent_id = ?::uuid",
                currentTenant(),
                id)
            .get(0, Long.class);
    if (children > 0) {
      throw new IllegalArgumentException(
          "该分类下还有 " + children + " 个子分类，不能删除。请先迁走或删掉子分类");
    }
    long used =
        sql.fetchOne(
                "select count(*) from aftersales_reason_attribution_item where tenant_id = ?::uuid and category_id = ?::uuid",
                currentTenant(),
                id)
            .get(0, Long.class);
    if (used > 0) {
      throw new IllegalArgumentException(
          "该分类已被 " + used + " 条归因引用，不能删除。要下线请改状态为 DISABLED —— "
              + "停用后不再可被新归因选中，历史归因仍指向它");
    }
    long mapped =
        sql.fetchOne(
                "select count(*) from aftersales_reason_platform_mapping where tenant_id = ?::uuid and category_id = ?::uuid",
                currentTenant(),
                id)
            .get(0, Long.class);
    if (mapped > 0) {
      throw new IllegalArgumentException("该分类已被 " + mapped + " 条平台原因码映射引用，不能删除");
    }
    sql.execute(
        "delete from aftersales_reason_category where tenant_id = ?::uuid and id = ?::uuid",
        currentTenant(),
        id);
  }

  // ================================================================ 归因

  /**
   * 读一张售后单的当前归因。
   *
   * <p>⚠️ {@code attributed=false} 与「归因为空」是<b>两件不同的事</b>：
   * 前者是「这张单还没做过归因」（11.1.1.1 登记单时合法存在的状态），
   * 后者是 DB 层禁止存在的数据（零成员的归因集合提交不了）。
   * 把两者混成一个空数组，界面就没法区分「还没归因」与「归因坏了」。
   */
  public AftersalesReasonAttributionView attribution(UUID caseId) {
    if (caseId == null) {
      throw new IllegalArgumentException("必须指定售后单");
    }
    Record caseRow =
        sql.fetchOne(
            "select id, case_no from aftersales_case where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            caseId);
    if (caseRow == null) {
      throw new IllegalArgumentException("售后单不存在或不属于当前租户：" + caseId);
    }

    Record set =
        sql.fetchOne(
            "select id, attribution_note, attributed_by, attributed_at, superseded_at"
                + " from aftersales_reason_attribution_set"
                + " where tenant_id = ?::uuid and case_id = ?::uuid and superseded_at is null",
            currentTenant(),
            caseId);
    long versions =
        sql.fetchOne(
                "select count(*) from aftersales_reason_attribution_set where tenant_id = ?::uuid and case_id = ?::uuid",
                currentTenant(),
                caseId)
            .get(0, Long.class);
    if (set == null) {
      return new AftersalesReasonAttributionView(
          caseId,
          caseRow.get("case_no", String.class),
          false,
          null,
          versions,
          NOTE_SEMANTICS,
          ENFORCEMENT_NOTE);
    }
    return new AftersalesReasonAttributionView(
        caseId,
        caseRow.get("case_no", String.class),
        true,
        readSet(set, true),
        versions,
        NOTE_SEMANTICS,
        ENFORCEMENT_NOTE);
  }

  /**
   * 登记一次归因（<b>替换语义</b>：新写一版，旧的自动标记为失效）。
   *
   * <p><b>加粗句「必须强制选择分类而不是留自由文本」在这里第一次被真正兑现。</b>
   * 下面每一个校验都对应一种「看起来像归因、其实不是」的打法：
   *
   * <ul>
   *   <li>{@code items} 为空 / null ⇒ 「只写了备注」——直接拒绝，并指向 NOTE_SEMANTICS 说明。
   *   <li>主因数 ≠ 1 ⇒ 「勾了三个都没标主因」或「全标了主因」——多选没有主次，归因分析无意义。
   *   <li>分类 ID 重复 ⇒ 同一个原因被勾两次，权重会被算两遍。
   *   <li>权重缺失 / 越界 ⇒ 「什么都勾了、权重全是 1」是最没意义的归因形态，所以权重**必填**。
   *   <li>分类已停用 / 上级已停用 ⇒ 把数据算进一个运营已经宣布作废的口径。
   * </ul>
   *
   * <p><b>顺序很关键</b>：先把旧版标记失效，再插新版 —— 因为
   * {@code UNIQUE(tenant_id,case_id) WHERE superseded_at IS NULL} 保证一张单只有一版生效。
   * 归因是纠纷举证的原材料，所以是<b>追加版本</b>而不是就地改写（照抄 V156 责任人流转的同一纪律）。
   */
  public AftersalesReasonAttributionView attribute(
      AftersalesReasonAttributionCreate request, UUID actorId) {
    if (request == null) {
      throw new IllegalArgumentException("请求体不能为空");
    }
    if (request.caseId() == null) {
      throw new IllegalArgumentException("必须指定售后单");
    }
    Record caseRow =
        sql.fetchOne(
            "select id, case_no from aftersales_case where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            request.caseId());
    if (caseRow == null) {
      throw new IllegalArgumentException("售后单不存在或不属于当前租户：" + request.caseId());
    }
    if (request.items() == null || request.items().isEmpty()) {
      throw new IllegalArgumentException(
          "归因必须至少选择一个原因分类。"
              + "原因口径不统一会让退货率分析失真，所以不能只写一段说明文字 —— "
              + "说明文字是**补充**，不是归因本身");
    }
    if (request.items().size() > MAX_ATTRIBUTION_ITEMS) {
      throw new IllegalArgumentException(
          "单次归因最多 " + MAX_ATTRIBUTION_ITEMS
              + " 个原因，本次 " + request.items().size()
              + " 个。把几十个分类全勾上、权重全是 1，那不是归因，是没归因");
    }
    if (request.note() != null && request.note().length() > 1000) {
      throw new IllegalArgumentException("补充说明不能超过 1000 字符");
    }

    // ---- 逐项校验：分类可归因性 + 权重 + 去重 + 主因数 ----
    Set<String> seen = new LinkedHashSet<>();
    int primaries = 0;
    List<Object[]> binds = new ArrayList<>();
    for (AftersalesReasonAttributionItem item : request.items()) {
      if (item == null || item.categoryId() == null) {
        throw new IllegalArgumentException("归因项缺少 categoryId");
      }
      if (!seen.add(item.categoryId().toString())) {
        throw new IllegalArgumentException(
            "同一个原因分类被选了多次：" + item.categoryId() + "。重复选择会让权重被算两遍");
      }
      if (item.isPrimary()) primaries++;
      BigDecimal weight = item.weight();
      // ⚠️ 权重 null 直接拒绝，**不**给默认值 1：默认 1 恰好就是最没意义的那个值。
      if (weight == null) {
        throw new IllegalArgumentException(
            "每个归因原因都必须显式给权重（0 < weight <= 1）。"
                + "不给权重时全选都是 1，那不是归因 —— 多选必须有主次或权重，否则分析无意义");
      }
      if (weight.signum() <= 0 || weight.compareTo(BigDecimal.ONE) > 0) {
        throw new IllegalArgumentException("权重必须落在 (0, 1] 区间内，收到 " + weight.toPlainString());
      }
      // 分类可归因性：存在 + 同租户 + ACTIVE + 全部祖先 ACTIVE。
      CategoryRef ref = requireAssignableCategory(item.categoryId(), "原因分类");
      binds.add(new Object[] {item.categoryId(), item.isPrimary(), weight, ref.code()});
    }
    if (primaries != 1) {
      throw new IllegalArgumentException(
          "一次归因必须有且仅有一个主因，当前有 " + primaries + " 个（本次共选了 "
              + request.items().size()
              + " 个原因）。蓝图要求「不同原因分类要有权重或主次」——"
              + "多个主因或没有主因都会让归因分析失真");
    }

    UUID setId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    // ① 旧版失效。⚠️ 只有 superseded_at 这一列运行角色能改（V158 的列级白名单），
    //    「谁在什么时候定的归因」不能被事后抹掉。
    sql.execute(
        "update aftersales_reason_attribution_set set superseded_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and case_id = ?::uuid and superseded_at is null",
        now,
        currentTenant(),
        request.caseId());

    // ② 新版落库。DB 的提交期约束触发器会在这条语句**提交时**判定
    //    「至少一个成员 + 恰好一个主因」—— 那是绕过后端也绕不开的一层。
    sql.execute(
        "insert into aftersales_reason_attribution_set(id,tenant_id,case_id,attribution_note,"
            + " attributed_by,attributed_at,created_at)"
            + " values (?,?::uuid,?::uuid,?,?::uuid,?::timestamptz,?::timestamptz)",
        setId,
        currentTenant(),
        request.caseId(),
        request.note() == null ? "" : request.note(),
        actorId,
        now,
        now);

    // ③ 成员。case_id 冗余存一份，触发器 guard_aftersales_attribution_category
    //    会钉死它与集合的 case_id 一致。
    for (Object[] b : binds) {
      sql.execute(
          "insert into aftersales_reason_attribution_item(id,tenant_id,set_id,case_id,"
              + " category_id,is_primary,weight,created_at)"
              + " values (?,?::uuid,?::uuid,?::uuid,?::uuid,?,?,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          setId,
          request.caseId(),
          b[0],
          b[1],
          b[2],
          now);
    }

    return attribution(request.caseId());
  }

  // ================================================================ 平台原因码映射

  /**
   * 平台原因码映射列表（真筛选 + 真排序）。
   *
   * <p>⚠️ 这张表在 **trade 库**，不是 channel 的 {@code platform_enum_mapping}
   * （后者在 channel 服务独立库，禁止跨服务 SQL）。见 capabilities 里
   * {@code crossServiceReadAvailable=false} 的说明。
   */
  public AftersalesReasonMappingList mappings(String platform, UUID categoryId, String status) {
    String platformSource =
        isSet(platform) ? parseEnum(AftersalesPlatformSource.class, platform, "平台来源").name() : null;
    String mappingStatus =
        isSet(status)
            ? parseEnum(AftersalesReasonMappingStatus.class, status, "映射状态").name()
            : null;

    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where m.tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (platformSource != null) {
      where.append(" and m.platform_source = ?");
      binds.add(platformSource);
    }
    if (categoryId != null) {
      where.append(" and m.category_id = ?::uuid");
      binds.add(categoryId);
    }
    if (mappingStatus != null) {
      where.append(" and m.mapping_status = ?");
      binds.add(mappingStatus);
    }

    List<Record> rows =
        sql.fetch(
            "select m.id, m.category_id, c.category_code, c.category_name, c.root_kind,"
                + " m.platform_source, m.platform_reason_code, m.platform_reason_name,"
                + " m.mapping_source, m.source_reference, m.captured_at, m.mapping_status, m.created_at"
                + " from aftersales_reason_platform_mapping m"
                + " join aftersales_reason_category c"
                + "   on c.tenant_id = m.tenant_id and c.id = m.category_id"
                + where
                + " order by m.platform_source, m.platform_reason_code, m.id",
            binds.toArray());

    Map<String, String> rootLabels = rootKindLabels();
    List<AftersalesReasonMappingRow> out = new ArrayList<>();
    long active = 0;
    for (Record r : rows) {
      String st = r.get("mapping_status", String.class);
      if ("ACTIVE".equals(st)) active++;
      String kind = r.get("root_kind", String.class);
      out.add(
          new AftersalesReasonMappingRow(
              r.get("id", UUID.class),
              r.get("category_id", UUID.class),
              r.get("category_code", String.class),
              r.get("category_name", String.class),
              kind,
              kind == null ? null : rootLabels.get(kind),
              r.get("platform_source", String.class),
              r.get("platform_reason_code", String.class),
              r.get("platform_reason_name", String.class),
              r.get("mapping_source", String.class),
              r.get("source_reference", String.class),
              r.get("captured_at", OffsetDateTime.class),
              st,
              r.get("created_at", OffsetDateTime.class)));
    }
    return new AftersalesReasonMappingList(
        out,
        out.size(),
        active,
        "本表落在 **trade 库**，是 trade 自己的表，不是 channel 的 platform_enum_mapping"
            + "（后者在 channel 服务独立库 starcon_channel，按 AGENTS.md 模块边界纪律禁止跨服务 SQL / FK / 视图）。"
            + "本表与它形状一致（内部口径 ⇄ 平台码 + 来源 + 采集时刻），"
            + "2.1.2.1 的同步器按同一形状写入时表结构与查询都不用改。",
        "platform_source, platform_reason_code, id —— 排序键唯一确定，"
            + "两次导出同一批映射必须逐行相同");
  }

  /**
   * 新建一条平台原因码映射。
   *
   * <p>⚠️ <b>采集时刻由调用方给</b>（{@code capturedAt}），缺省才取 now() ——
   * 填 now() 会让三个月前的平台映射被当成今天的采集的（照抄 V150 的 captured_at 纪律）。
   * 同步器写入时必须给真实采集时刻，否则同步历史不可回溯。
   */
  public AftersalesReasonMappingRow createMapping(AftersalesReasonMappingCreate request, UUID actorId) {
    if (request == null) {
      throw new IllegalArgumentException("请求体不能为空");
    }
    if (request.categoryId() == null) {
      throw new IllegalArgumentException("必须指定内部原因分类");
    }
    String platformSource =
        parseEnum(AftersalesPlatformSource.class, request.platformSource(), "平台来源").name();
    String code = requireText(request.platformReasonCode(), "平台原因码", 64);
    AftersalesReasonMappingSource source =
        isSet(request.mappingSource())
            ? parseEnum(AftersalesReasonMappingSource.class, request.mappingSource(), "映射来源")
            : AftersalesReasonMappingSource.TENANT_MAINTAINED;
    if (source == AftersalesReasonMappingSource.MODULE_2_ENUM_MAPPING
        && !isSet(request.sourceReference())) {
      throw new IllegalArgumentException(
          "映射来源为 MODULE_2_ENUM_MAPPING 时必须给出 sourceReference（哪份来源的哪一段），"
              + "否则「这行是同步器写的」只是口头声明");
    }
    String status =
        isSet(request.mappingStatus())
            ? parseEnum(AftersalesReasonMappingStatus.class, request.mappingStatus(), "映射状态").name()
            : "ACTIVE";
    // 映射必须指向 ACTIVE 的分类：把平台码映射到一个已停用的口径上，
    // 报表会从平台侧捞回一批数据算进一个运营已经作废的分类。
    requireAssignableCategory(request.categoryId(), "原因分类");

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    try {
      sql.execute(
          "insert into aftersales_reason_platform_mapping(id,tenant_id,category_id,platform_source,"
              + " platform_reason_code,platform_reason_name,mapping_source,source_reference,"
              + " captured_at,mapping_status,created_by,created_at,updated_at)"
              + " values (?,?::uuid,?::uuid,?,?,?,?,?,?::timestamptz,?,?::uuid,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          request.categoryId(),
          platformSource,
          code,
          request.platformReasonName() == null ? "" : clamp(request.platformReasonName(), 128),
          source.name(),
          request.sourceReference() == null ? "" : clamp(request.sourceReference(), 128),
          request.capturedAt() == null ? now : request.capturedAt(),
          status,
          actorId,
          now,
          now);
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      if (isUniqueViolation(e)) {
        throw new IllegalArgumentException(
            "平台 " + platformSource + " 的原因码 " + code + " 已经映射过了。"
                + "同一个平台的同一个原因码只能映射到一个内部口径 —— 两边都映射会让报表把同一批退货重复计入两行");
      }
      throw e;
    }
    return findMapping(id);
  }

  /**
   * 更新一条映射。
   *
   * <p>⚠️ <b>可更新的只有三列</b>（{@code category_id} / {@code platform_reason_name} /
   * {@code mapping_status}），与 V158 的列级白名单一致。
   * {@code platform_reason_code} 与 {@code platform_source} 是<b>匹配键</b>，
   * 改它们等于把一条映射换成另一条；要换请删了重建。{@code captured_at} 与
   * {@code mapping_source} 不可改：改了就是伪造采集时刻 / 冒充同步器。
   */
  public AftersalesReasonMappingRow updateMapping(
      UUID id, AftersalesReasonMappingCreate request, UUID actorId) {
    if (request == null) {
      throw new IllegalArgumentException("请求体不能为空");
    }
    Record existing =
        sql.fetchOne(
            "select id, platform_source, platform_reason_code, mapping_source"
                + " from aftersales_reason_platform_mapping where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    if (existing == null) {
      throw new IllegalArgumentException("平台原因码映射不存在或不属于当前租户：" + id);
    }
    // 匹配键不可改：给一个「改键」的入口，等于让人以为改键是受支持的。
    if (isSet(request.platformSource())
        && !parseEnum(AftersalesPlatformSource.class, request.platformSource(), "平台来源")
            .name()
            .equals(existing.get("platform_source", String.class))) {
      throw new IllegalArgumentException(
          "platformSource 是匹配键，不可修改（当前 "
              + existing.get("platform_source", String.class)
              + "）。要换平台请删掉这条再新建");
    }
    if (isSet(request.platformReasonCode())
        && !requireText(request.platformReasonCode(), "平台原因码", 64)
            .equals(existing.get("platform_reason_code", String.class))) {
      throw new IllegalArgumentException(
          "platformReasonCode 是匹配键，不可修改（当前 "
              + existing.get("platform_reason_code", String.class)
              + "）。要换平台码请删掉这条再新建");
    }
    if (request.categoryId() != null) {
      requireAssignableCategory(request.categoryId(), "原因分类");
    }
    if (request.mappingStatus() == null && request.categoryId() == null
        && request.platformReasonName() == null) {
      throw new IllegalArgumentException(
          "没有要改的内容。可改的是：内部分类、平台原因名、状态"
              + "（平台来源与平台原因码是匹配键，不可改）");
    }
    sql.execute(
        "update aftersales_reason_platform_mapping"
            + " set category_id = coalesce(?::uuid, category_id),"
            + "     platform_reason_name = coalesce(?, platform_reason_name),"
            + "     mapping_status = coalesce(?, mapping_status),"
            + "     updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        request.categoryId(),
        request.platformReasonName() == null
            ? null
            : clamp(request.platformReasonName(), 128),
        isSet(request.mappingStatus())
            ? parseEnum(AftersalesReasonMappingStatus.class, request.mappingStatus(), "映射状态").name()
            : null,
        OffsetDateTime.now(ZoneOffset.UTC),
        currentTenant(),
        id);
    return findMapping(id);
  }

  /**
   * 删除一条平台原因码映射。
   *
   * <p>⚠️ <b>同步器写的行不许删，只许停用。</b>同步器下次跑还会写回来，
   * 删了它就变成「我明明停用了却又生效了」；而且删掉之后平台侧那个原因码
   * 在本租户就没有任何口径了，归因会退化成「无法归类的平台原因」。
   * 租户自维护的行才允许删 —— 那是租户自己填的，删掉是它自己的决定。
   */
  public void deleteMapping(UUID id) {
    Record existing =
        sql.fetchOne(
            "select mapping_source, platform_source, platform_reason_code"
                + " from aftersales_reason_platform_mapping where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    if (existing == null) {
      throw new IllegalArgumentException("平台原因码映射不存在或不属于当前租户：" + id);
    }
    if (AftersalesReasonMappingSource.MODULE_2_ENUM_MAPPING.name()
        .equals(existing.get("mapping_source", String.class))) {
      throw new IllegalArgumentException(
          "这条映射由 2.1.2.1 同步器写入，不能删除（同步器下次运行会写回来，"
              + "删了会变成「明明停用了却又生效」）。请把状态改成 DISABLED 停用它 —— "
              + "停用保留历史口径，而删除会让平台侧那个原因码在本租户没有任何口径");
    }
    sql.execute(
        "delete from aftersales_reason_platform_mapping where tenant_id = ?::uuid and id = ?::uuid",
        currentTenant(),
        id);
  }

  // ================================================================ 报表

  /**
   * 报表（统一口径）。
   *
   * <p><b>只统计当前生效的归因集合</b>（{@code superseded_at IS NULL}）：
   * 把历史版本一起算进去会让「改过一次归因的单」被计两次，报表数字随
   * 「改了几次」变化 —— 那不叫统一口径。
   *
   * <p><b>主因与次因分开计数</b>（{@code primaryCaseCount} / {@code secondaryCaseCount}）：
   * 合成一个数会让「既是主因、又在别的分类里当次因」的单被重复计入两个口径 ——
   * 这正是蓝图加粗句说的「退货率分析失真」。
   *
   * <p>⚠️ 左连集合时 {@code superseded_at is null} 放在 <b>WHERE</b> 而不是 ON 上：
   *   放 ON 上会让「属于历史版本」的成员行 s.case_id 变 NULL 但 i.* 仍被计入，
   *   于是历史版本照样进报表 —— 这是一个看起来能跑、结果是错的写法。
   */
  public AftersalesReasonReport report() {
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    // ⚠️ 报表也必须落基线词表：不落的话，从没打开过分类树页面的租户会拿到一份
    //   **空报表** —— 七类全部消失，而界面无法区分「这个租户还没有分类」
    //   与「这个租户所有原因都是 0」。蓝图要求「报表用统一口径」，
    //   七类口径就该**恒定存在**，哪怕全是 0。
    ensureBaseline();
    Map<String, String> rootLabels = rootKindLabels();

    List<Record> rows =
        sql.fetch(
            // ⚠️ 递归 CTE `up`：自根向上，给**每个分类**标出它属于哪一个根口径，
            //   七类汇总就按它分组。
            //   少了它的话，子分类的 root_kind 恒为 NULL，汇总只会统计直接勾了根分类的
            //   那些单，**勾了子分类的单会整个消失** —— 而七类正是下游
            //   （11.5.2 质量分析、6 采购索赔）唯一对齐的锚点，最不能错在这里。
            //   depth 必须一起 select：递归项的终止条件 `u.depth > 1` 要读它，
            //   不 select 的话 PG 直接报「column u.depth does not exist」。
            "with recursive up as ("
                + "  select c.id, c.root_kind, c.depth from aftersales_reason_category c"
                + "   where c.tenant_id = ?::uuid and c.parent_id is null"
                + "  union all"
                + "  select c.id, u.root_kind, c.depth from aftersales_reason_category c"
                + "    join up u on c.parent_id = u.id"
                // ⚠️ 这里的递归是**向下**走（锚点是根），所以终止条件不能写成
                //   `u.depth > 1`（那是向上走的写法）：根的 depth 恰好是 1，
                //   那个条件恒为 false，于是整个递归一步都不走，up 里只剩 7 个根 ——
                //   报表会退化成「只有直接勾了根分类的单才计入七类」。
                //   向下走天然有界（每步 depth +1，而 depth 由 CHECK 钉在 1..3）。
                + "   where c.tenant_id = ?::uuid and c.depth <= "
                + MAX_DEPTH
                + ")"
                + " select c.id, c.category_code, c.category_name, c.root_kind, c.depth, c.sort_order,"
                + "        up.root_kind as effective_root_kind,"
                + "        count(*) filter (where i.is_primary) as primary_count,"
                + "        count(*) filter (where i.is_primary is false) as secondary_count,"
                + "        count(distinct i.case_id) as case_count,"
                + "        coalesce(sum(i.weight), 0) as weight_sum"
                + " from aftersales_reason_category c"
                + " join up on up.id = c.id"
                + " left join aftersales_reason_attribution_item i"
                + "   on i.tenant_id = c.tenant_id and i.category_id = c.id"
                + " left join aftersales_reason_attribution_set s"
                + "   on s.tenant_id = i.tenant_id and s.id = i.set_id"
                + " where c.tenant_id = ?::uuid and (i.id is null or s.superseded_at is null)"
                + " group by c.id, c.category_code, c.category_name, c.root_kind, c.depth,"
                + "          c.sort_order, up.root_kind"
                + " order by c.root_kind nulls last, c.depth, c.sort_order, c.category_code, c.id",
            currentTenant(),
            currentTenant(),
            currentTenant());

    long attributedCaseCount =
        sql.fetchOne(
                "select count(*) from aftersales_reason_attribution_set"
                    + " where tenant_id = ?::uuid and superseded_at is null",
                currentTenant())
            .get(0, Long.class);
    long unattributedCaseCount =
        sql.fetchOne(
                "select count(*) from aftersales_case c where c.tenant_id = ?::uuid"
                    + " and not exists (select 1 from aftersales_reason_attribution_set s"
                    + "   where s.tenant_id = c.tenant_id and s.case_id = c.id and s.superseded_at is null)",
                currentTenant())
            .get(0, Long.class);

    List<AftersalesReasonReportRow> out = new ArrayList<>();
    Map<String, long[]> rootAgg = new LinkedHashMap<>();
    long itemCount = 0;
    BigDecimal totalWeight = BigDecimal.ZERO.setScale(6);
    for (Record r : rows) {
      long primary = r.get("primary_count", Long.class);
      long secondary = r.get("secondary_count", Long.class);
      long cases = r.get("case_count", Long.class);
      BigDecimal weightSum = r.get("weight_sum", BigDecimal.class);
      itemCount += primary + secondary;
      totalWeight = totalWeight.add(weightSum);
      String kind = r.get("root_kind", String.class);
      out.add(
          new AftersalesReasonReportRow(
              r.get("id", UUID.class),
              r.get("category_code", String.class),
              r.get("category_name", String.class),
              kind,
              kind == null ? null : rootLabels.get(kind),
              r.get("depth", Integer.class),
              r.get("sort_order", Integer.class),
              primary,
              secondary,
              cases,
              share(cases, attributedCaseCount),
              weightSum));
      // ⚠️ 按 **effective_root_kind**（所属根口径）汇总，**不是**按本行的 root_kind：
      //   本行的 root_kind 对子分类恒为 null，按它分组会让「勾了子分类的单」
      //   从七类汇总里整个消失。
      String effectiveRoot = r.get("effective_root_kind", String.class);
      if (effectiveRoot != null) {
        long[] agg = rootAgg.computeIfAbsent(effectiveRoot, k -> new long[2]);
        agg[0] += primary;
        agg[1] += cases;
      }
    }

    // 按七类根口径汇总：**七类全部出现，零也出现**。
    // 少列一个「0」的行，界面就会把它显示成空白，而空白会被读成「这个类没有数据」
    // 而不是「这个类真的是 0」。
    List<AftersalesReasonRootTotal> totals = new ArrayList<>();
    for (AftersalesReasonRootKind kind : AftersalesReasonRootKind.values()) {
      long[] agg = rootAgg.getOrDefault(kind.name(), new long[2]);
      totals.add(
          new AftersalesReasonRootTotal(
              kind.name(),
              kind.label(),
              agg[0],
              agg[1],
              share(agg[1], attributedCaseCount)));
    }

    return new AftersalesReasonReport(
        out,
        totals,
        at,
        attributedCaseCount,
        unattributedCaseCount,
        itemCount,
        totalWeight.setScale(6, RoundingMode.HALF_UP),
        "只统计**当前生效**的归因集合（superseded_at IS NULL）；改过一次归因的单只算一次。"
            + "主因（is_primary）与次因分开计数 —— 合成一个数会让既是主因又在别的分类里"
            + "当次因的单被重复计入。"
            + "占比 = 该口径归因单数 ÷ 生效归因单总数（attributedCaseCount），两位小数四舍五入，"
            + "分母为 0 时全为 0.00。"
            + "七类根口径**全部出现**，零也出现。"
            + "权重合计仅供参考：权重是相对值，**不要求**合计为 1。",
        "下游 11.5.2.1 质量分析与 6.4.2.2 供应商索赔**均未交付**，本模块不假装已对接 —— "
            + "它交付后直接取用本报表的既有口径，不必自己再定一套。");
  }

  // ================================================================ 内部

  private static final String NOTE_SEMANTICS =
      "补充说明是**结构化归因之外的说明**，不是归因本身。"
          + "归因必须有至少一个被选中的原因分类且恰好一个主因 —— "
          + "说明文字留空完全可以，写了说明但没选分类则根本提交不了。";

  private static final String ENFORCEMENT_NOTE =
      "强制选择分类的三层防线："
          + "(1) 服务层拒绝零个原因 / 多个或零个主因 / 重复分类 / 权重缺失或越界；"
          + "(2) DB 层 DEFERRABLE INITIALLY DEFERRED 约束触发器在**提交瞬间**判定"
          + "「至少一个成员 + 恰好一个主因」，绕过后端直接写 SQL 也存不进去；"
          + "(3) 归因只能指向 ACTIVE 且全部祖先 ACTIVE 的分类，"
          + "停用是一个显式的口径变更动作，不会悄悄把历史数据算进作废口径。";

  /** 一个归因集合的读回形态。 */
  private AftersalesReasonAttributionSetRow readSet(Record set, boolean current) {
    UUID setId = set.get("id", UUID.class);
    Map<String, String> rootLabels = rootKindLabels();
    List<Record> items =
        sql.fetch(
            "with recursive t as ("
                // ⚠️ 同上：锚点必须 ::text，否则与递归项的 `||` 结果类型不一致。
                + "  select c.id, c.category_code::text as code_path from aftersales_reason_category c"
                + "   where c.tenant_id = ?::uuid and c.parent_id is null"
                + "  union all"
                + "  select c.id, t.code_path || '/' || c.category_code"
                + "    from aftersales_reason_category c join t on c.parent_id = t.id"
                + "   where c.tenant_id = ?::uuid and c.depth <= "
                + MAX_DEPTH
                + ")"
                + " select i.id, i.category_id, c.category_code, c.category_name, c.root_kind,"
                + "        c.depth, i.is_primary, i.weight, t.code_path"
                + "   from aftersales_reason_attribution_item i"
                + "   join aftersales_reason_category c"
                + "     on c.tenant_id = i.tenant_id and c.id = i.category_id"
                + "   join t on t.id = c.id"
                + "  where i.tenant_id = ?::uuid and i.set_id = ?::uuid"
                // 主因排第一，其余按权重倒序、编码定序 —— 界面直接照这个顺序渲染即可，
                // 且同一份归因在两次读取里顺序完全一致。
                + "  order by i.is_primary desc, i.weight desc, c.category_code, c.id",
            currentTenant(),
            currentTenant(),
            currentTenant(),
            setId);

    List<AftersalesReasonAttributionItemRow> out = new ArrayList<>();
    int primaries = 0;
    BigDecimal sum = BigDecimal.ZERO.setScale(6);
    for (Record r : items) {
      if (r.get("is_primary", Boolean.class)) primaries++;
      String kind = r.get("root_kind", String.class);
      out.add(
          new AftersalesReasonAttributionItemRow(
              r.get("id", UUID.class),
              r.get("category_id", UUID.class),
              r.get("category_code", String.class),
              r.get("category_name", String.class),
              kind,
              kind == null ? null : rootLabels.get(kind),
              r.get("depth", Integer.class),
              r.get("is_primary", Boolean.class),
              r.get("weight", BigDecimal.class),
              r.get("code_path", String.class)));
      sum = sum.add(r.get("weight", BigDecimal.class));
    }
    return new AftersalesReasonAttributionSetRow(
        setId,
        set.get("attribution_note", String.class),
        out,
        out.size(),
        primaries,
        sum.setScale(6, RoundingMode.HALF_UP),
        set.get("attributed_by", UUID.class),
        set.get("attributed_at", OffsetDateTime.class),
        current,
        set.get("superseded_at", OffsetDateTime.class));
  }

  private AftersalesReasonTreeNode findNode(UUID id) {
    AftersalesReasonTreeQuery all = new AftersalesReasonTreeQuery(null, null, null);
    // 复用整树读取里的映射逻辑（路径 / 可归因性 / 子节点数 / 归因数都来自那里），
    // 避免同一套口径在两个地方各写一份而漂移。
    for (AftersalesReasonTreeNode node : tree(all).nodes()) {
      if (node.id().equals(id)) return node;
    }
    throw new IllegalArgumentException("原因分类不存在或不属于当前租户：" + id);
  }

  private AftersalesReasonMappingRow findMapping(UUID id) {
    AftersalesReasonMappingList all = mappings(null, null, null);
    for (AftersalesReasonMappingRow row : all.rows()) {
      if (row.id().equals(id)) return row;
    }
    throw new IllegalArgumentException("平台原因码映射不存在或不属于当前租户：" + id);
  }

  /** 分类的最小引用信息。 */
  private record CategoryRef(UUID id, String code, String name) {}

  /**
   * 校验一个分类**当前可被新归因选中**，并返回它的引用信息。
   *
   * <p>用递归 CTE 取到根的**整条祖先链**（最多 3 行），然后在 Java 里判断链上有没有停用的节点。
   * 这一步刻意留在 Java：它是<b>校验</b>而不是「把结果集筛一遍」，
   * 链长被 V158 的 depth CHECK 钉死在 3 层内，遍历成本是常数。
   *
   * <p>「上级已停用」这条规则必须在服务端也判一次：DB 触发器只在
   * {@code parent_id} 被改写时检查，自己已经存在的子节点不会因为父节点停用而报错。
   * 允许把分类挂在一支已停用的分支下，会让归因把数据算进一个作废的口径。
   */
  private CategoryRef requireAssignableCategory(UUID categoryId, String label) {
    List<Record> chain =
        sql.fetch(
            "with recursive up as ("
                + "  select c.id, c.parent_id, c.category_code, c.category_name,"
                + "         c.category_status, c.depth"
                + "    from aftersales_reason_category c"
                + "   where c.tenant_id = ?::uuid and c.id = ?::uuid"
                + "  union all"
                + "  select p.id, p.parent_id, p.category_code, p.category_name,"
                + "         p.category_status, p.depth"
                + "    from aftersales_reason_category p join up u on u.parent_id = p.id"
                // ⚠️ 往上走的终止条件是「当前行还不是根」即 depth > 1：
                //   写成 `depth < MAX_DEPTH` 会把第 3 层节点的祖先链截断成它自己，
                //   于是「上级已停用」这条规则对第三层节点永远失效。
                + "   where p.tenant_id = ?::uuid and u.depth > 1"
                + ")"
                + " select id, parent_id, category_code, category_name, category_status, depth from up",
            currentTenant(),
            categoryId,
            currentTenant());
    Record self = null;
    for (Record r : chain) {
      if (r.get("id", UUID.class).equals(categoryId)) self = r;
    }
    if (self == null) {
      throw new IllegalArgumentException(label + "不存在或不属于当前租户：" + categoryId);
    }
    String selfStatus = self.get("category_status", String.class);
    if (!"ACTIVE".equals(selfStatus)) {
      throw new IllegalArgumentException(
          label + "已停用（" + self.get("category_code", String.class)
              + "）：停用后不能再被新归因选中，历史归因仍指向它");
    }
    for (Record r : chain) {
      if (!"ACTIVE".equals(r.get("category_status", String.class))) {
        throw new IllegalArgumentException(
            label + "所在的上级分支已停用（"
                + r.get("category_code", String.class)
                + "）：整支灰掉的分支不能再被新归因选中。停用是一个显式的口径变更动作");
      }
    }
    return new CategoryRef(
        self.get("id", UUID.class),
        self.get("category_code", String.class),
        self.get("category_name", String.class));
  }

  /** 七类根口径 → 中文名。用于把 DB 存的英文 kind 变成界面能直接显示的字。 */
  private Map<String, String> rootKindLabels() {
    Map<String, String> labels = new LinkedHashMap<>();
    for (AftersalesReasonRootKind kind : AftersalesReasonRootKind.values()) {
      labels.put(kind.name(), kind.label());
    }
    return labels;
  }

  /**
   * 枚举值 → 中文名。
   *
   * <p>只服务于 {@link AftersalesReasonCategoryStatus}：树节点的 status 是从 DB 读的**字符串**，
   * 界面上要显示「启用 / 停用」而不是 {@code ACTIVE / DISABLED}。
   * 认不出的值原样返回而不是抛错 —— DB 的 CHECK 保证它只可能是这两个值之一，
   * 而万一将来 CHECK 加了一个新值，这里原样返回比整页 500 更有用。
   */
  private static String statusLabel(String name) {
    try {
      return AftersalesReasonCategoryStatus.valueOf(name).label();
    } catch (IllegalArgumentException | NullPointerException e) {
      return name;
    }
  }

  /** 占比。分母为 0 时全为 {@code 0.00} 而不是抛异常 —— 「还没有归因数据」是一个合法状态。 */
  private static BigDecimal share(long numerator, long denominator) {
    if (denominator <= 0) return new BigDecimal("0.00");
    return BigDecimal.valueOf(numerator)
        .multiply(new BigDecimal("100"))
        .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
  }

  private UUID currentTenant() {
    // 与 AftersalesCaseService 同一套：来自 app.current_tenant（由 AccessService.run
    // 在事务内绑定），**不是**任何请求头。查询里仍显式带 tenant_id 谓词，
    // 与 FORCE RLS 形成两道独立的防线。
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }

  /**
   * 判断是不是唯一约束冲突（PostgreSQL SQLSTATE 23505）。
   *
   * <p>⚠️ 参数刻意是 {@link Throwable} 而不是 jOOQ 的 {@code DataAccessException}：
   * 本服务开着 Spring 的 SQL 异常翻译，唯一冲突冒到调用方时已经是
   * {@code org.springframework.dao.DuplicateKeyException}，**不是** jOOQ 那个类
   * —— 所以 catch jOOQ 的类型根本不会触发，唯一冲突会带着一句
   * 「duplicate key value violates unique constraint …」原样冒出去，
   * 而那句话完全没告诉调用方「这个编码已经有人用了，请换一个」。
   */
  private static boolean isUniqueViolation(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof java.sql.SQLException sql
          && "23505".equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isSet(String v) {
    return v != null && !v.isBlank();
  }

  /**
   * 枚举解析：非法值在这里就 400，<b>不</b>静默回落到默认值。
   *
   * <p>照抄 AftersalesCaseService 的同一套写法。两个理由：
   * 一是把 {@code Arrays.toString} 的枚举全量放进报错里，调用方不用去猜有哪些合法值；
   * 二是「静默回落」在这里特别危险 —— 把一个拼错的 root_kind 回落成某个默认值，
   * 会让新建的分类被归到一个运营根本没选过的口径里，而报表不会报错。
   */
  private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    try {
      return Enum.valueOf(type, raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          label + "只能是 " + Arrays.toString(type.getEnumConstants()) + "：收到 " + raw);
    }
  }

  private static String requireText(String raw, String label, int max) {
    if (!isSet(raw)) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    String v = raw.trim();
    if (v.length() > max) {
      throw new IllegalArgumentException(label + "不能超过 " + max + " 字符，收到 " + v.length());
    }
    return v;
  }

  /** 分类编码的字符集：只允许能安全出现在路径与 SQL 标识里的字符。 */
  private static String requireCode(String raw) {
    String v = requireText(raw, "分类编码", 48);
    if (!v.matches("^[A-Za-z0-9][A-Za-z0-9._-]*$")) {
      throw new IllegalArgumentException(
          "分类编码只能包含字母、数字、点、下划线、连字符，且必须以字母或数字开头：收到 " + v);
    }
    return v;
  }

  private static String clamp(String raw, int max) {
    if (raw == null) return "";
    String v = raw.trim();
    if (v.length() > max) {
      throw new IllegalArgumentException("内容不能超过 " + max + " 字符，收到 " + v.length());
    }
    return v;
  }
}
