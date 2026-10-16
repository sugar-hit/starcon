package com.starcon.catalog;

import static com.starcon.catalog.PlatformCategoryMappingModels.*;
import static com.starcon.catalog.PlatformCategoryMappingRules.*;

import com.starcon.tenancy.Ids;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * 4.2.3.1 平台类目映射：内部分类 ↔ 平台类目的多对多映射（带平台 + 站点两个维度），
 * 以及一份独立的平台类目必填属性映射规则（平台的「材质」对应内部哪个属性 ID）。
 *
 * <p>本类的纪律：
 *
 * <ol>
 *   <li><b>平台与站点是两条独立列，不是「amazon/us」这样一个字符串</b>。蓝图写死了
 *       「同一个内部类目在不同平台/不同站点映射到不同的平台类目」，所以这两个维度必须能各自
 *       独立筛选与统计。合成字符串的话，覆盖率只能靠前缀解析还原，而解析写错的症状是
 *       「刊登到 DE 站却填了 US 站的类目」——平台驳回之前没人会发现。
 *   <li><b>映射是多对多，所以台账必须把「另一侧」显式带出来</b>。一个平台类目被多少个内部分类映射到
 *       （{@code mappedInternalCount}）如果不可见，运营改了内部分类的映射却不知道
 *       那个平台类目还服务于谁，删错了要到刊登时才炸。
 *   <li><b>属性规则挂在映射上，不是挂在平台类目上</b>。同一个平台类目在内部分类 A 上要求材质，
 *       在内部分类 B 上可能完全不要求——必填清单是**按映射**而论的。
 *   <li><b>「必填但没绑内部属性」是一个要被看见的正常状态</b>，不是错误。
 *       {@code unmappedRequiredCount} 把它显式统计出来，而不是让 {@code internalAttributeId} 变成 NOT NULL
 *       把这个事实挡在库外（挡在库外的后果是运营配不出这条规则，刊登被驳回才知道自己缺一个属性）。
 *   <li><b>分页是真分页</b>：筛选、排序、LIMIT/OFFSET 全在 SQL 上，计数相关字段用相关子查询算，
 *       不在内存里切页也不在内存里统计。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 映射是商品主数据的下游配置，与 SPU 同域；不新造权限词表（新词表要改 identity 服务的迁移，
 * 属于另一个服务的写入所有权）。
 */
@Service
public class PlatformCategoryMappingService {
  /** 台账页大小上限：与相邻模块一致，防止一次拉爆整个租户的数据。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 排序白名单：界面能点的列都写死在这里，避免把用户输入拼进 order by。 */
  private static final String SORT_UPDATED_DESC = "m.updated_at desc, m.id";

  /** 租户上下文取法：RLS 已按它隔离，这里带上只是让执行计划走租户前缀的索引。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  private static final String FROM_MAPPING =
      " from platform_category_mapping m"
          + " join product_category ic on ic.tenant_id=m.tenant_id and ic.id=m.internal_category_id"
          + " join product_category pc on pc.tenant_id=m.tenant_id and pc.id=m.platform_category_id";

  /**
   * 台账每行都要算的三个计数。
   *
   * <p>用相关子查询而不是 LEFT JOIN + GROUP BY：后者要在三张表上做笛卡尔再聚合，
   * 且台账要真分页——先聚合再分页会把 LIMIT 作用在聚合结果上，页大小与总数都对不上。
   */
  private static final String COUNT_COLUMNS =
      " (select count(*) from platform_category_mapping o"
          + "   where o.tenant_id=m.tenant_id and o.platform_category_id=m.platform_category_id"
          + "     and o.platform_code=m.platform_code and o.site_code=m.site_code) as mapped_internal_count,"
          + " (select count(*) from platform_category_attribute_mapping a"
          + "   where a.tenant_id=m.tenant_id and a.mapping_id=m.id and a.enabled) as attribute_rule_count,"
          + " (select count(*) from platform_category_attribute_mapping a"
          + "   where a.tenant_id=m.tenant_id and a.mapping_id=m.id and a.enabled"
          + "     and a.requirement='REQUIRED') as required_count,"
          + " (select count(*) from platform_category_attribute_mapping a"
          + "   where a.tenant_id=m.tenant_id and a.mapping_id=m.id and a.enabled"
          + "     and a.requirement='REQUIRED' and a.internal_attribute_id is null) as unmapped_required_count";

  private final DSLContext sql;

  public PlatformCategoryMappingService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 概览

  /**
   * 概览：规模、覆盖率与缺口。
   *
   * <p>覆盖率用「已映射的内部分类数 ÷ 内部分类总数」而不是「映射条数」：
   * 一个内部分类配 10 条映射不代表 10 个类目能刊登，只看条数会把「一个类目配了 10 次」
   * 误读成「覆盖很好」。
   */
  public PlatformCategoryMappingOverview overview() {
    long mappingCount = count("select count(*) from platform_category_mapping");
    long enabledMappingCount = count("select count(*) from platform_category_mapping where enabled");
    long defaultMappingCount = count("select count(*) from platform_category_mapping where is_default");
    long attributeRuleCount = count("select count(*) from platform_category_attribute_mapping where enabled");
    long unmappedRequiredCount =
        count(
            "select count(*) from platform_category_attribute_mapping"
                + " where enabled and requirement='REQUIRED' and internal_attribute_id is null");

    // 内部分类总数：INTERNAL 树上**启用**的节点。停用的类目不参与覆盖率分母，
    // 否则运营停用一个历史类目，覆盖率立刻掉一截，而那不是他的工作做坏了。
    long internalCategoryCount =
        count(
            "select count(*) from product_category c join product_category_tree t"
                + " on t.tenant_id=c.tenant_id and t.id=c.tree_id"
                + " where t.tree_kind='INTERNAL' and c.enabled");
    long platformCategoryCount =
        count(
            "select count(*) from product_category c join product_category_tree t"
                + " on t.tenant_id=c.tenant_id and t.id=c.tree_id"
                + " where t.tree_kind='PLATFORM' and c.enabled");
    long mappedInternalCategoryCount =
        count(
            "select count(distinct m.internal_category_id) from platform_category_mapping m"
                + " join product_category c on c.tenant_id=m.tenant_id and c.id=m.internal_category_id"
                + " join product_category_tree t on t.tenant_id=c.tenant_id and t.id=c.tree_id"
                + " where t.tree_kind='INTERNAL' and c.enabled and m.enabled");
    long mappedPlatformCategoryCount =
        count(
            "select count(distinct m.platform_category_id) from platform_category_mapping m"
                + " join product_category c on c.tenant_id=m.tenant_id and c.id=m.platform_category_id"
                + " join product_category_tree t on t.tenant_id=c.tenant_id and t.id=c.tree_id"
                + " where t.tree_kind='PLATFORM' and c.enabled and m.enabled");

    long platformCount = count("select count(distinct platform_code) from platform_category_mapping");
    long siteCount =
        count("select count(distinct (platform_code, site_code)) from platform_category_mapping");

    return new PlatformCategoryMappingOverview(
        mappingCount,
        enabledMappingCount,
        defaultMappingCount,
        internalCategoryCount,
        mappedInternalCategoryCount,
        platformCategoryCount,
        mappedPlatformCategoryCount,
        platformCount,
        siteCount,
        attributeRuleCount,
        unmappedRequiredCount,
        coverage());
  }

  /**
   * 按「平台 × 站点」拆开的覆盖情况。
   *
   * <p>分母的口径刻意与全局覆盖率不同：这里的「内部分类数」是**在该平台上已有映射**的内部分类数，
   * 而不是全租户的内部分类总数。原因是同一个类目在 Amazon US 已配、在 Shopify 还没配，
   * 把 Shopify 的分母算成全租户类目数会让所有平台看起来都没覆盖，
   * 而页面恰恰要回答的是「我这个平台配得怎么样了」。
   */
  public List<PlatformSiteCoverage> coverage() {
    return sql.fetch(
            "select m.platform_code, m.site_code,"
                + " count(*) as mapping_count,"
                + " count(distinct m.internal_category_id) as internal_category_count,"
                + " count(distinct m.internal_category_id) filter (where m.enabled) as mapped_internal_count,"
                + " coalesce(sum("
                + "   (select count(*) from platform_category_attribute_mapping a"
                + "     where a.tenant_id=m.tenant_id and a.mapping_id=m.id and a.enabled"
                + "       and a.requirement='REQUIRED' and a.internal_attribute_id is null)),0) as unmapped_count"
                + " from platform_category_mapping m"
                + " where m.enabled"
                + " group by m.platform_code, m.site_code"
                + " order by m.platform_code, m.site_code")
        .stream()
        .map(
            r ->
                new PlatformSiteCoverage(
                    r.get("platform_code", String.class),
                    r.get("site_code", String.class),
                    r.get("mapping_count", Long.class),
                    r.get("internal_category_count", Long.class),
                    r.get("mapped_internal_count", Long.class),
                    r.get("unmapped_count", Long.class)))
        .toList();
  }

  // ============================================================ 台账（真分页）

  /**
   * 映射台账。
   *
   * <p>所有筛选条件都在 SQL 上，且 {@code total} 与 {@code items} 用同一段 where：
   * 两段条件各写一次的话，改了一处忘了另一处，页码就会算错（症状是最后一页翻出来是空的）。
   */
  public PlatformCategoryMappingPage page(
      UUID internalCategoryId,
      String platformCode,
      String siteCode,
      String keyword,
      Boolean enabled,
      boolean unmappedOnly,
      String sort,
      int page,
      int size) {
    String normalizedPlatform =
        platformCode == null || platformCode.isBlank() ? null : normalizePlatform(platformCode);
    String normalizedSite = siteCode == null || siteCode.isBlank() ? null : normalizeSite(siteCode);

    // where 片段与它的参数列表**一起构造**：两段条件各写一次的话，
    // 改了一处忘了另一处，页码就会算错（症状是最后一页翻出来是空的）；
    // 只留 where 不留参数的话，count 查询会在运行时报「未设定参数值 1」而指不到原因。
    StringBuilder where = new StringBuilder();
    List<Object> params = new ArrayList<>();
    if (internalCategoryId != null) {
      where.append(" and m.internal_category_id = ?");
      params.add(internalCategoryId);
    }
    if (normalizedPlatform != null) {
      where.append(" and m.platform_code = ?");
      params.add(normalizedPlatform);
    }
    if (normalizedSite != null) {
      where.append(" and m.site_code = ?");
      params.add(normalizedSite);
    }
    if (enabled != null) where.append(" and m.enabled = ").append(enabled ? "true" : "false");
    if (keyword != null && !keyword.isBlank()) {
      // 关键词同时搜内部类目与平台类目的编码和名称：运营记得的是「我要配 T恤」
      // 或「Shopify 那个子类目」其中之一，两个方向都得搜得到。
      where.append(" and (ic.code ilike ? or ic.name ilike ? or pc.code ilike ? or pc.name ilike ?)");
      String like = "%" + keyword.trim() + "%";
      params.add(like);
      params.add(like);
      params.add(like);
      params.add(like);
    }
    String whereText = where.toString() + (unmappedOnly ? andUnmappedRequired() : "");
    String tenant = TENANT;

    long total = count("select count(*)" + FROM_MAPPING + " where m.tenant_id = " + tenant + whereText, params.toArray());

    int safeSize = size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    int safePage = Math.max(page, 1);
    var records =
        sql.fetch(
            "select m.id, m.internal_category_id, ic.code as internal_category_code,"
                + " ic.name as internal_category_name, m.platform_code, m.site_code,"
                + " m.platform_category_id, pc.code as platform_category_code,"
                + " pc.name as platform_category_name, pc.path_ids as platform_category_path,"
                + " m.is_default, m.enabled," + COUNT_COLUMNS + ","
                + " m.version, m.created_at, m.updated_at"
                + FROM_MAPPING
                + " where m.tenant_id = "
                + tenant
                + whereText
                + " order by "
                + orderBy(sort)
                + " limit "
                + safeSize
                + " offset "
                + ((long) (safePage - 1) * safeSize),
            params.toArray());

    return new PlatformCategoryMappingPage(rows(records), total, safePage, safeSize);
  }

  /** 「只看有缺口的」：要求缺口条件与 where 是 AND，所以这里必须与拼接保持一致。 */
  private static String andUnmappedRequired() {
    return " and exists (select 1 from platform_category_attribute_mapping a"
        + " where a.tenant_id=m.tenant_id and a.mapping_id=m.id and a.enabled"
        + " and a.requirement='REQUIRED' and a.internal_attribute_id is null)";
  }

  /**
   * 排序白名单。
   *
   * <p>列名与方向都不来自用户输入的可变片段：只有整段从白名单里取，
   * 拼进 {@code order by} 的永远是这几个常量之一。
   */
  private static String orderBy(String sort) {
    String key = sort == null ? "" : sort.trim();
    return switch (key) {
      case "createdAt,asc" -> "m.created_at asc, m.id";
      case "createdAt,desc" -> "m.created_at desc, m.id";
      case "platform,asc" -> "m.platform_code asc, m.site_code asc, m.id";
      case "internalCategory,asc" -> "ic.code asc, ic.name asc, m.id";
      case "updatedAt,asc" -> "m.updated_at asc, m.id";
      // 默认最新改动在前；末尾补 m.id 是为了让「同一毫秒改了两条」也有确定顺序，
      // 否则真分页的 LIMIT/OFFSET 会在两次翻页之间漏行或重行。
      default -> SORT_UPDATED_DESC;
    };
  }

  private List<PlatformCategoryMappingRow> rows(java.util.Collection<Record> records) {
    // 路径串要把一批行里所有祖先一次取回来。逐行查会变成 N+1：
    // 一页 20 行、每行 4 层祖先就是 80 次查询，而路径只是给人看的名字串。
    Map<UUID, String> names = new LinkedHashMap<>();
    LinkedHashSet<UUID> ancestorIds = new LinkedHashSet<>();
    for (Record r : records) {
      UUID[] path = r.get("platform_category_path", UUID[].class);
      if (path != null) for (UUID id : path) ancestorIds.add(id);
    }
    if (!ancestorIds.isEmpty()) {
      for (Record r :
          sql.fetch(
              "select id, name from product_category where id in ("
                  + placeholders(ancestorIds.size())
                  + ")",
              ancestorIds.toArray()))
        names.put(r.get("id", UUID.class), r.get("name", String.class));
    }

    List<PlatformCategoryMappingRow> out = new ArrayList<>();
    for (Record r : records) {
      UUID[] path = r.get("platform_category_path", UUID[].class);
      out.add(
          new PlatformCategoryMappingRow(
              r.get("id", UUID.class),
              r.get("internal_category_id", UUID.class),
              r.get("internal_category_code", String.class),
              r.get("internal_category_name", String.class),
              r.get("platform_code", String.class),
              r.get("site_code", String.class),
              r.get("platform_category_id", UUID.class),
              r.get("platform_category_code", String.class),
              r.get("platform_category_name", String.class),
              pathText(path, names),
              Boolean.TRUE.equals(r.get("is_default", Boolean.class)),
              Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
              r.get("mapped_internal_count", Long.class),
              r.get("attribute_rule_count", Long.class),
              r.get("required_count", Long.class),
              r.get("unmapped_required_count", Long.class),
              r.get("version", Long.class),
              instant(r.get("created_at", OffsetDateTime.class)),
              instant(r.get("updated_at", OffsetDateTime.class))));
    }
    return out;
  }

  private static String pathText(UUID[] path, Map<UUID, String> names) {
    if (path == null || path.length == 0) return "";
    List<String> parts = new ArrayList<>();
    for (UUID id : path) {
      String name = names.get(id);
      if (name != null) parts.add(name);
    }
    return String.join(" / ", parts);
  }

  // ============================================================ 详情

  /** 映射详情：映射行 + 属性规则 + 同一平台类目上的其他内部分类 + 汇总。 */
  public PlatformCategoryMappingDetail detail(UUID id) {
    var record = mappingRow(id);
    PlatformCategoryMappingRow row = rows(List.of(record)).get(0);
    List<PlatformAttributeMappingRow> attributes = attributeRows(List.of(id));
    List<MappedInternalCategory> alsoMappedFrom =
        sql.fetch(
                "select distinct c.id, c.code, c.name, c.enabled"
                    + " from platform_category_mapping m"
                    + " join product_category c on c.tenant_id=m.tenant_id and c.id=m.internal_category_id"
                    + " where m.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid and m.platform_code=?"
                    + " and m.site_code=? and m.platform_category_id=? and m.id<>?"
                    + " order by c.code",
                row.platformCode(),
                row.siteCode(),
                row.platformCategoryId(),
                id)
            .stream()
            .map(
                r ->
                    new MappedInternalCategory(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class),
                        Boolean.TRUE.equals(r.get("enabled", Boolean.class))))
            .toList();

    long required =
        attributes.stream().filter(a -> "REQUIRED".equals(a.requirement()) && a.enabled()).count();
    long mapped =
        attributes.stream().filter(a -> "REQUIRED".equals(a.requirement()) && a.enabled() && a.internalAttributeId() != null).count();
    return new PlatformCategoryMappingDetail(
        row,
        attributes,
        alsoMappedFrom,
        new PlatformMappingSummary(attributes.size(), required, mapped, required - mapped));
  }

  private Record mappingRow(UUID id) {
    var row =
        sql.fetchOne(
            "select m.id, m.internal_category_id, ic.code as internal_category_code,"
                + " ic.name as internal_category_name, m.platform_code, m.site_code,"
                + " m.platform_category_id, pc.code as platform_category_code,"
                + " pc.name as platform_category_name, pc.path_ids as platform_category_path,"
                + " m.is_default, m.enabled," + COUNT_COLUMNS + ","
                + " m.version, m.created_at, m.updated_at"
                + FROM_MAPPING
                + " where m.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid and m.id = ?",
            id);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  // ============================================================ 映射维护

  /**
   * 新建或改一条映射（id 为空即新建），走 version 乐观锁。
   *
   * <p>两类类目的树种类在服务层先查一遍再写：DB 的 {@code guard_platform_category_mapping_scope}
   * 触发器会兜住所有绕过服务的写入，但它抛的是带约束语义的中文异常，
   * 这里先查是为了把「左边这个类目来自平台树，不能当内部分类」这种**指哪不对**的原因说清楚。
   */
  public PlatformCategoryMappingRow save(
      AccessService.Access actor, UUID id, PlatformCategoryMappingInput input) {
    String problem = firstNonNull(platformProblem(input.platformCode()), siteProblem(input.siteCode()));
    CatalogService.check(problem == null, problem);
    String platform = normalizePlatform(input.platformCode());
    String site = normalizeSite(input.siteCode());

    Record internal = categoryRow(input.internalCategoryId());
    Record platformCategory = categoryRow(input.platformCategoryId());
    CatalogService.check(
        "INTERNAL".equals(internal.get("tree_kind", String.class)),
        "左边的类目必须来自内部管理分类树（INTERNAL），当前选中的「"
            + internal.get("name", String.class)
            + "」在平台类目树上。左侧是内部分类，右侧才是平台类目，两边选反了");
    CatalogService.check(
        "PLATFORM".equals(platformCategory.get("tree_kind", String.class)),
        "右边的类目必须来自平台类目树（PLATFORM），当前选中的「"
            + platformCategory.get("name", String.class)
            + "」在内部管理分类树上");
    String owner = platformCategory.get("platform_code", String.class);
    CatalogService.check(
        owner != null && owner.equals(platform),
        "右边的平台类目属于平台「" + owner + "」，与本条映射声明的平台「" + platform + "」不一致");

    if (id == null) {
      UUID newId = Ids.next();
      try {
        // is_default 一律先按 false 落库。
        // 同一组合下可能已经有一条默认（partial unique index 只允许一个），
        // 直接插 true 会当场撞索引抛出一条看不懂的 23505，
        // 而「先插 false → 清掉旧默认 → 再置位」在同一个事务里做，结果一样但错误可读。
        sql.execute(
            "insert into platform_category_mapping"
                + "(id,tenant_id,internal_category_id,platform_code,site_code,platform_category_id,is_default,enabled)"
                + " values(?,?,?,?,?,?,false,?)",
            newId,
            actor.tenantId(),
            input.internalCategoryId(),
            platform,
            site,
            input.platformCategoryId(),
            input.enabled());
      } catch (DataAccessException e) {
        throw conflict(
            "这条映射已存在（同一个内部分类在该平台该站点下已经映射到这个平台类目），或已被他人并发创建");
      }
      if (input.isDefault()) promoteToDefault(actor.tenantId(), input.internalCategoryId(), platform, site, newId);
      audit(actor, newId, "PlatformCategoryMappingSaved");
      return rows(List.of(mappingRow(newId))).get(0);
    }

    Record existing = mappingRow(id);
    CatalogService.check(
        input.version() == existing.get("version", Long.class),
        "映射已被他人修改，请刷新后重试");

    // 与新建同一口径：先把 is_default 落到 false 再决定要不要置位，
    // 否则「把一条映射改成目标组合并设为默认」会在同组合已有默认时当场撞 partial unique index。
    boolean wantDefault = input.isDefault();
    try {
      sql.execute(
          "update platform_category_mapping set internal_category_id=?,platform_code=?,site_code=?,"
              + "platform_category_id=?,is_default=false,enabled=?,version=version+1,updated_at=now()"
              + " where id=? and version=?",
          input.internalCategoryId(),
          platform,
          site,
          input.platformCategoryId(),
          input.enabled(),
          id,
          input.version());
    } catch (DataAccessException e) {
      throw conflict("这条映射已存在，或它的默认目标已被他人占用，请刷新后重试");
    }
    if (wantDefault) promoteToDefault(actor.tenantId(), input.internalCategoryId(), platform, site, id);
    audit(actor, id, "PlatformCategoryMappingSaved");
    return rows(List.of(mappingRow(id))).get(0);
  }

  /**
   * 把某条映射设成它所在「内部分类 × 平台 × 站点」组合的默认目标。
   *
   * <p>顺序是「先清同组合的其它默认，再置位」。反过来的话，本条刚被置位就撞上
   * 还没清掉的旧默认，抛出来的是一条约束名而不是「同一组合只能有一个默认」。
   */
  private void promoteToDefault(
      UUID tenantId, UUID internalCategoryId, String platform, String site, UUID id) {
    clearOtherDefaults(tenantId, internalCategoryId, platform, site, id);
    sql.execute(
        "update platform_category_mapping set is_default=true,version=version+1,updated_at=now()"
            + " where id=?",
        id);
  }

  /**
   * 启用停用。停用不删属性规则：它们是「这个平台类目要什么」的既有事实，
   * 停用期间平台照常要求，删掉等于让运营重新录一遍。
   */
  public void setEnabled(AccessService.Access actor, UUID id, boolean enabled, long version) {
    Record existing = mappingRow(id);
    CatalogService.check(
        version == existing.get("version", Long.class), "映射已被他人修改，请刷新后重试");
    sql.execute(
        "update platform_category_mapping set enabled=?,version=version+1,updated_at=now()"
            + " where id=? and version=?",
        enabled,
        id,
        version);
    audit(actor, id, "PlatformCategoryMappingEnabled");
  }

  /**
   * 设为该「内部分类 × 平台 × 站点」的默认目标。
   *
   * <p>先清掉同一组合下的其他默认再置位，而不是直接改：
   * V145 的 partial unique index 只保证「至多一个默认」，不在这里先清，
   * 冲突会以一条看不懂的 23505 冒出来。
   */
  public void setDefault(AccessService.Access actor, UUID id, long version) {
    Record existing = mappingRow(id);
    CatalogService.check(
        version == existing.get("version", Long.class), "映射已被他人修改，请刷新后重试");
    promoteToDefault(
        actor.tenantId(),
        existing.get("internal_category_id", UUID.class),
        existing.get("platform_code", String.class),
        existing.get("site_code", String.class),
        id);
    audit(actor, id, "PlatformCategoryMappingDefault");
  }

  private void clearOtherDefaults(UUID tenantId, UUID internalCategoryId, String platform, String site, UUID keepId) {
    // 租户隔离由 RLS 的 WITH CHECK 保证（缺了它，这条 UPDATE 会把默认位写到别的租户行上）。
    // 这里显式带上 tenant_id 只是为了让执行计划走
    // platform_category_mapping_default 这条 partial index，不是安全边界的唯一来源。
    sql.execute(
        "update platform_category_mapping set is_default=false,updated_at=now()"
            + " where tenant_id=? and internal_category_id=? and platform_code=? and site_code=?"
            + " and is_default and id<>?",
        tenantId,
        internalCategoryId,
        platform,
        site,
        keepId);
  }

  /** 删映射。属性规则随映射级联删除（DB 的 ON DELETE CASCADE）。 */
  public void delete(AccessService.Access actor, UUID id, long version) {
    Record existing = mappingRow(id);
    CatalogService.check(
        version == existing.get("version", Long.class), "映射已被他人修改，请刷新后重试");
    sql.execute("delete from platform_category_mapping where id=? and version=?", id, version);
    audit(actor, id, "PlatformCategoryMappingDeleted");
  }

  // ============================================================ 平台类目属性规则

  /** 某条映射下的属性规则列表（台账与详情共用）。 */
  public List<PlatformAttributeMappingRow> attributeRows(java.util.Collection<UUID> mappingIds) {
    if (mappingIds.isEmpty()) return List.of();
    return sql.fetch(
            "select a.id, a.mapping_id, a.platform_attribute_code, a.platform_attribute_name,"
                + " a.internal_attribute_id, d.code as internal_attribute_code,"
                + " d.name as internal_attribute_name, a.requirement, a.platform_data_type,"
                + " a.multiple, a.allowed_values, a.transform, a.enabled, a.position,"
                + " a.version, a.created_at, a.updated_at"
                + " from platform_category_attribute_mapping a"
                + " left join attribute_definition d"
                + "   on d.tenant_id=a.tenant_id and d.id=a.internal_attribute_id and d.spu_id is null"
                + " where a.mapping_id in ("
                + placeholders(mappingIds.size())
                + ")"
                + " order by a.position, a.platform_attribute_code",
            mappingIds.toArray())
        .stream()
        .map(PlatformCategoryMappingService::toAttributeRow)
        .toList();
  }

  private static PlatformAttributeMappingRow toAttributeRow(Record r) {
    return new PlatformAttributeMappingRow(
        r.get("id", UUID.class),
        r.get("mapping_id", UUID.class),
        r.get("platform_attribute_code", String.class),
        r.get("platform_attribute_name", String.class),
        r.get("internal_attribute_id", UUID.class),
        r.get("internal_attribute_code", String.class),
        r.get("internal_attribute_name", String.class),
        r.get("requirement", String.class),
        r.get("platform_data_type", String.class),
        Boolean.TRUE.equals(r.get("multiple", Boolean.class)),
        stringArray(r.get("allowed_values", String[].class)),
        r.get("transform", String.class),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("position", Integer.class),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  /**
   * 新建或改一条属性规则（ruleId 为空即新建）。
   *
   * <p>内部属性必须指向**租户级字典条目**（{@code attribute_definition.spu_id IS NULL}）：
   * 指向某个 SPU 私有属性的话，映射会在那个 SPU 被删时静默失效，
   * 而症状是刊登表单某天开始少一个必填字段。所以查一次并给出可读原因。
   */
  public PlatformAttributeMappingRow saveAttributeRule(
      AccessService.Access actor, UUID mappingId, UUID ruleId, PlatformAttributeMappingInput input) {
    mappingRow(mappingId); // 映射必须存在且本租户可见
    String problem =
        attributeProblem(
            input.platformAttributeCode(),
            input.platformAttributeName(),
            input.requirement(),
            input.platformDataType(),
            input.allowedValues(),
            input.transform(),
            input.position());
    CatalogService.check(problem == null, problem);

    String code = normalizePlatformAttributeCode(input.platformAttributeCode());
    String name = normalizePlatformAttributeName(input.platformAttributeName());
    String requirement = input.requirement().trim();
    String dataType = input.platformDataType().trim();
    String transform = normalizeTransform(input.transform());
    List<String> values = normalizeAllowedValues(input.allowedValues());

    UUID internalAttributeId = input.internalAttributeId();
    if (internalAttributeId != null) assertDictionaryAttribute(internalAttributeId);

    if (ruleId == null) {
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into platform_category_attribute_mapping"
                + "(id,tenant_id,mapping_id,platform_attribute_code,platform_attribute_name,"
                + "internal_attribute_id,requirement,platform_data_type,multiple,allowed_values,transform,enabled,position)"
                + " values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            newId,
            actor.tenantId(),
            mappingId,
            code,
            name,
            internalAttributeId,
            requirement,
            dataType,
            input.multiple(),
            values.toArray(new String[0]),
            transform,
            input.enabled(),
            input.position());
      } catch (DataAccessException e) {
        throw conflict("这条平台属性规则已存在（同一映射下平台属性编码「" + code + "」只能有一条）");
      }
      audit(actor, newId, "PlatformAttributeMappingSaved");
      return attributeRow(newId);
    }

    Record existing = attributeRowRecord(ruleId);
    CatalogService.check(
        existing.get("mapping_id", UUID.class).equals(mappingId),
        "这条属性规则不属于当前映射，不能在这里改");
    CatalogService.check(
        input.version() == existing.get("version", Long.class), "属性规则已被他人修改，请刷新后重试");
    try {
      sql.execute(
          "update platform_category_attribute_mapping set platform_attribute_code=?,platform_attribute_name=?,"
              + "internal_attribute_id=?,requirement=?,platform_data_type=?,multiple=?,allowed_values=?,"
              + "transform=?,enabled=?,position=?,version=version+1,updated_at=now()"
              + " where id=? and version=?",
          code,
          name,
          internalAttributeId,
          requirement,
          dataType,
          input.multiple(),
          values.toArray(new String[0]),
          transform,
          input.enabled(),
          input.position(),
          ruleId,
          input.version());
    } catch (DataAccessException e) {
      throw conflict("这条平台属性规则已存在（同一映射下平台属性编码「" + code + "」只能有一条）");
    }
    audit(actor, ruleId, "PlatformAttributeMappingSaved");
    return attributeRow(ruleId);
  }

  public void setAttributeEnabled(
      AccessService.Access actor, UUID mappingId, UUID ruleId, boolean enabled, long version) {
    Record existing = attributeRowRecord(ruleId);
    CatalogService.check(
        existing.get("mapping_id", UUID.class).equals(mappingId),
        "这条属性规则不属于当前映射，不能在这里改");
    CatalogService.check(
        version == existing.get("version", Long.class), "属性规则已被他人修改，请刷新后重试");
    sql.execute(
        "update platform_category_attribute_mapping set enabled=?,version=version+1,updated_at=now()"
            + " where id=? and version=?",
        enabled,
        ruleId,
        version);
    audit(actor, ruleId, "PlatformAttributeMappingEnabled");
  }

  public void deleteAttributeRule(AccessService.Access actor, UUID mappingId, UUID ruleId) {
    Record existing = attributeRowRecord(ruleId);
    CatalogService.check(
        existing.get("mapping_id", UUID.class).equals(mappingId),
        "这条属性规则不属于当前映射，不能在这里删");
    sql.execute("delete from platform_category_attribute_mapping where id=?", ruleId);
    audit(actor, ruleId, "PlatformAttributeMappingDeleted");
  }

  private Record attributeRowRecord(UUID ruleId) {
    var row =
        sql.fetchOne(
            "select * from platform_category_attribute_mapping where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid and id=?",
            ruleId);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private PlatformAttributeMappingRow attributeRow(UUID ruleId) {
    var row =
        sql.fetchOne(
            "select a.id, a.mapping_id, a.platform_attribute_code, a.platform_attribute_name,"
                + " a.internal_attribute_id, d.code as internal_attribute_code,"
                + " d.name as internal_attribute_name, a.requirement, a.platform_data_type,"
                + " a.multiple, a.allowed_values, a.transform, a.enabled, a.position,"
                + " a.version, a.created_at, a.updated_at"
                + " from platform_category_attribute_mapping a"
                + " left join attribute_definition d"
                + "   on d.tenant_id=a.tenant_id and d.id=a.internal_attribute_id and d.spu_id is null"
                + " where a.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid and a.id=?",
            ruleId);
    if (row == null) throw CatalogService.missing();
    return toAttributeRow(row);
  }

  private void assertDictionaryAttribute(UUID attributeId) {
    var row =
        sql.fetchOne(
            "select id, spu_id, code, name from attribute_definition"
                + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid and id=?",
            attributeId);
    if (row == null) throw CatalogService.missing();
    CatalogService.check(
        row.get("spu_id", UUID.class) == null,
        "只能绑定租户级属性字典条目，不能绑定单个商品的私有属性："
            + row.get("name", String.class)
            + " 属于某一个商品，它被删掉会让这条平台必填规则静默失效");
  }

  // ============================================================ 下游解析（4.2.1.1 表单生成）

  /**
   * 按「内部分类 × 平台 × 站点」解析刊登目标与必填属性清单。
   *
   * <p>取默认映射；同一组合下没有默认时取唯一一条启用映射；有多条且没有默认时
   * 明确报「需要指定」，**不静默挑第一条**——静默挑一条的后果是同一个商品
   * 在两次刊登里落到不同的平台类目，而两边看起来都「成功」了。
   */
  public PlatformCategoryResolveResult resolve(UUID internalCategoryId, String platformCode, String siteCode) {
    CatalogService.check(internalCategoryId != null, "内部分类不能为空");
    String platform = normalizePlatform(platformCode);
    String site = normalizeSite(siteCode);
    String problem = firstNonNull(platformProblem(platform), siteProblem(site));
    CatalogService.check(problem == null, problem);

    var candidates =
        sql.fetch(
            "select m.id, m.internal_category_id, ic.name as internal_category_name,"
                + " m.platform_code, m.site_code, m.platform_category_id,"
                + " pc.code as platform_category_code, pc.name as platform_category_name,"
                + " pc.path_ids as platform_category_path, m.is_default"
                + FROM_MAPPING
                + " where m.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid and m.internal_category_id=?"
                + " and m.platform_code=? and m.site_code=? and m.enabled"
                + " order by m.is_default desc, m.created_at",
            internalCategoryId,
            platform,
            site);
    if (candidates.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "内部分类在该平台该站点下还没有启用的映射：刊登表单无法确定目标平台类目，请先在平台类目映射里补一条");

    boolean anyDefault = candidates.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("is_default", Boolean.class)));
    CatalogService.check(
        anyDefault || candidates.size() == 1,
        "内部分类「"
            + candidates.get(0).get("internal_category_name", String.class)
            + "」在平台 "
            + platform
            + " 站点 "
            + site
            + " 下有 "
            + candidates.size()
            + " 条启用映射但没有默认目标：请先把它设为默认，否则刊登时落到哪条映射是不确定的");

    Record chosen = candidates.get(0);
    UUID mappingId = chosen.get("id", UUID.class);
    List<PlatformAttributeMappingRow> attributes = attributeRows(List.of(mappingId));

    List<PlatformRequiredAttribute> required = new ArrayList<>();
    long unmapped = 0;
    for (PlatformAttributeMappingRow a : attributes) {
      if (!a.enabled()) continue;
      boolean mapped = a.internalAttributeId() != null;
      if ("REQUIRED".equals(a.requirement()) && !mapped) unmapped++;
      required.add(
          new PlatformRequiredAttribute(
              a.platformAttributeCode(),
              a.platformAttributeName(),
              a.requirement(),
              a.platformDataType(),
              a.multiple(),
              a.allowedValues(),
              a.transform(),
              a.internalAttributeId(),
              a.internalAttributeCode(),
              a.internalAttributeName(),
              mapped));
    }

    var pathIds = chosen.get("platform_category_path", UUID[].class);
    String pathText = "";
    if (pathIds != null && pathIds.length > 0) {
      Map<UUID, String> names = new LinkedHashMap<>();
      for (Record r :
          sql.fetch(
              "select id, name from product_category where id in (" + placeholders(pathIds.length) + ")",
              (Object[]) pathIds))
        names.put(r.get("id", UUID.class), r.get("name", String.class));
      pathText = pathText(pathIds, names);
    }

    return new PlatformCategoryResolveResult(
        mappingId,
        internalCategoryId,
        chosen.get("internal_category_name", String.class),
        platform,
        site,
        chosen.get("platform_category_id", UUID.class),
        chosen.get("platform_category_code", String.class),
        chosen.get("platform_category_name", String.class),
        pathText,
        Boolean.TRUE.equals(chosen.get("is_default", Boolean.class)),
        required,
        unmapped);
  }

  // ============================================================ 候选

  /**
   * 平台与站点的候选项：平台取自**已建的 PLATFORM 分类树**，站点取自该平台上已在用的映射。
   *
   * <p>没有任何一处是硬编码常量：平台码表在 channel 服务，catalog 不跨服务读，
   * 而租户自己建的平台树是租户范围内的真实事实，也是平台码在本服务里唯一的权威来源
   * （V145 的触发器用它校验映射的平台归属）。
   */
  public List<PlatformSiteOption> platformSites() {
    var platforms = new LinkedHashMap<String, PlatformSiteOption>();
    for (Record r :
        sql.fetch(
            "select t.platform_code, count(*) as category_count"
                + " from product_category_tree t"
                + " join product_category c on c.tenant_id=t.tenant_id and c.tree_id=t.id"
                + " where t.tree_kind='PLATFORM' and c.enabled"
                + " group by t.platform_code order by t.platform_code"))
      platforms.put(
          r.get("platform_code", String.class),
          new PlatformSiteOption(
              r.get("platform_code", String.class), new ArrayList<>(), 0L, r.get("category_count", Long.class)));

    for (Record r :
        sql.fetch(
            "select platform_code, site_code, count(*) as mapping_count"
                + " from platform_category_mapping"
                + " where enabled group by platform_code, site_code"
                + " order by platform_code, site_code")) {
      String platform = r.get("platform_code", String.class);
      long count = r.get("mapping_count", Long.class);
      PlatformSiteOption existing = platforms.get(platform);
      if (existing == null) {
        // 映射里有这个平台但树被停用/删了：仍然列出来，否则运营会以为自己配的映射凭空消失
        List<String> sites = new ArrayList<>();
        sites.add(r.get("site_code", String.class));
        platforms.put(platform, new PlatformSiteOption(platform, sites, count, 0L));
      } else {
        existing.siteCodes().add(r.get("site_code", String.class));
        platforms.put(
            platform,
            new PlatformSiteOption(
                platform, existing.siteCodes(), existing.mappingCount() + count, existing.categoryCount()));
      }
    }
    return platforms.values().stream()
        .map(o -> new PlatformSiteOption(o.platformCode(), List.copyOf(o.siteCodes()), o.mappingCount(), o.categoryCount()))
        .toList();
  }

  /**
   * 内部属性字典候选项（供「平台的材质对应内部哪个属性」下拉）。
   *
   * <p>只取租户级字典条目（{@code spu_id IS NULL}），与 {@link #assertDictionaryAttribute} 同一口径：
   * 下拉里给出后来会被服务端拒绝的选项，是最让人白填一次的界面。
   */
  public List<InternalAttributeOption> attributeOptions(String keyword) {
    StringBuilder sqlText =
        new StringBuilder(
            "select id, code, name, data_type, multiple from attribute_definition"
                + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid and spu_id is null");
    if (keyword != null && !keyword.isBlank()) sqlText.append(" and (code ilike ? or name ilike ?)");
    sqlText.append(" order by code limit 200");
    Object[] params =
        keyword == null || keyword.isBlank()
            ? new Object[0]
            : new Object[] {"%" + keyword.trim() + "%", "%" + keyword.trim() + "%"};
    return sql.fetch(sqlText.toString(), params).stream()
        .map(
            r ->
                new InternalAttributeOption(
                    r.get("id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class),
                    r.get("data_type", String.class),
                    Boolean.TRUE.equals(r.get("multiple", Boolean.class))))
        .toList();
  }

  // ============================================================ 内部工具

  /** 类目行 + 它所在树的种类与平台归属。 */
  private Record categoryRow(UUID categoryId) {
    var row =
        sql.fetchOne(
            "select c.id, c.code, c.name, c.enabled, t.tree_kind, t.platform_code, t.enabled as tree_enabled"
                + " from product_category c"
                + " join product_category_tree t on t.tenant_id=c.tenant_id and t.id=c.tree_id"
                + " where c.tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid and c.id=?",
            categoryId);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  private long count(String sqlText, Object... params) {
    return sql.fetchOne(sqlText, params).get(0, Long.class);
  }

  private static String placeholders(int n) {
    return String.join(",", java.util.Collections.nCopies(n, "?"));
  }

  private static String firstNonNull(String... values) {
    for (String value : values) if (value != null) return value;
    return null;
  }

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        resourceId,
        action);
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  private static List<String> stringArray(String[] values) {
    return values == null ? List.of() : List.of(values);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
