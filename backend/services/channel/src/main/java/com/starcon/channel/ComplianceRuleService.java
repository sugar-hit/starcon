package com.starcon.channel;

import static com.starcon.channel.ComplianceRuleModels.*;
import static com.starcon.channel.ComplianceRuleRules.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.2.1.1 规则库。
 *
 * <p>蓝图：「管理合规规则库：规则类型（违禁词、极限词、侵权词、认证要求、禁限售、标签要求、材质要求）、
 * 严重度（阻断/警告/提示）、适用平台与站点、生效时间与版本。输入：规则定义与词库上传；版本更新；适用范围配置。
 * 输出：规则集 + 生效版本。关系：上游 = 平台政策同步 + 人工维护；下游 = 16.2.1.2 执行、4.2.3.2 预检。
 * <b>规则版本化是关键</b>：政策变化时历史商品的判定结果要可解释，不能因为规则库更新就说过去的商品当时违规。」
 *
 * <p>🔴🔴 本类里有五处<b>结构</b>上的不变量：
 *
 * <ol>
 *   <li><b>规则改动 = 新增一个版本</b>：本类<b>没有</b>「改词」「改严重度」的入口 ——
 *       唯一能改内容的路径是 {@link #createVersion}。版本内容在 DB 层被触发器钉死不可改写。</li>
 *   <li><b>生效时刻不许回填</b>：新版本的 {@code effectiveFrom} 不得早于当前时刻。
 *       回填会让「当时生效的那一版」变成今天这一版 ⇒ 历史判定再也解释不清。</li>
 *   <li><b>严重度落在版本上</b>：{@code severity} 是版本的属性，规则行上**没有**严重度列。</li>
 *   <li><b>值域在 DB 层钉死</b>：规则类型 7 种 / 严重度 3 种由迁移 V311 的 CHECK 钉死，
 *       服务层**先**按同一份规则拒（422，带人话），DB **再**兜底（23514）。</li>
 *   <li><b>下游未交付一律不编</b>：16.2.1.2 拦截执行当前<b>未交付</b>
 *       ⇒ 能力声明里 {@code available=false} + {@code ownerModule} + 影响说明，
 *       <b>不建拦截表、不造指向不存在入口的放行形状</b>。</li>
 * </ol>
 */
@Service
public class ComplianceRuleService {

  /** 一页最多多少行。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 一次最多追加多少个词条。 */
  private static final int MAX_TERMS_PER_CALL = 2000;

  /** 一次最多追加多少条适用范围。 */
  private static final int MAX_SCOPES_PER_CALL = 200;

  // ==================================================================
  // 口径说明（能力声明与各处共用同一句话）
  // ==================================================================

  private static final String RULE_TYPE_NOTE =
      "🔴 规则类型是**受控值域**（七种），由 DB 层 CHECK 钉死（值域要可审阅，不只靠应用层 enum）。"
          + "类型是**规则的身份**：一条规则永远是同一类规则，改类型 = 另一条规则。";

  private static final String SEVERITY_NOTE =
      "🔴 严重度是**版本**的属性（阻断 / 警告 / 提示），**不**落在规则上 —— "
          + "政策改了严重度就新增一个版本，旧版本原样留着。"
          + "所以规则行上**没有**一个可以被单独改掉的「严重度」列。";

  private static final String VERSIONING_NOTE =
      "🔴🔴 **规则版本化是关键**：改规则 = **新增一个版本**（带生效时间窗），**不是原地改词**。"
          + "版本内容（严重度 / 标题 / 说明 / 词条 / 适用范围 / 生效时刻）在 DB 层不可改写；"
          + "生效时刻**不许回填**（回填会让「当时生效的那一版」变成今天这一版）；"
          + "同一时刻**只能有一个版本生效**（窗口不重叠由触发器钉死）。"
          + "⇒ 政策变化后，历史商品的判定结果仍然答得出「当时按哪一版、那一版有多严重、按哪些词判」。";

  private static final String WORD_LIBRARY_NOTE =
      "🔴 词库是**多词条**：违禁词 / 极限词 / 侵权词三类规则带词条集合，一条规则多个词条。"
          + "词条**属于某个版本**，不能脱离版本存在；版本生效后词条**锁死**（改一个词 = 新增一个版本）。"
          + "另外四类规则（认证要求 / 禁限售 / 标签要求 / 材质要求）**不带词条** —— "
          + "它们约束的是「有没有这份认证 / 这个品类能不能卖 / 标签要写什么 / 材质是什么」。";

  private static final String SCOPE_NOTE =
      "🔴 适用范围 = **适用平台 + 站点**（可再收窄到具体店铺），直接读本服务自己的 "
          + "`platform` / `marketplace` / `shop` 目录 —— **不复制目录表、不造平行平台表、不写死店铺常量**。"
          + "适用范围**属于某个版本**；店铺维度在这里**显式**落地，并按「适用平台与站点」收敛。";

  private static final String UPSTREAM_NOTE =
      "上游 = **平台政策同步** + **人工维护**。"
          + "平台政策适配器**未交付** ⇒ 本模块当前只有「人工维护」这一条真实路径"
          + "（建规则 / 上传词库 / 发新版本 / 配适用范围）；"
          + "本模块**不编造**一份「平台政策自动同步」的假入口。";

  private static final String DOWNSTREAM_NOTE =
      "下游 = **16.2.1.2 拦截执行**（**未交付** ⇒ 本模块**不建拦截表**、"
          + "**不造指向不存在入口的放行形状**；页面与 API 里**没有**「立即校验 / 放行」这类入口）、"
          + "**4.2.3.2 合规预检**（**已交付**，同服务 `listing_compliance_*`；"
          + "但它用的是**自己的租户级词表**，**不是**本模块的受版本治理规则库，两边互不改动对方的表）。";

  private final DSLContext sql;

  public ComplianceRuleService(DSLContext sql) {
    this.sql = sql;
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  private UUID currentActor() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.actor',true),'')");
    return raw == null ? null : UUID.fromString(String.valueOf(raw));
  }

  /** 🔴 <b>同源 DB 时钟</b>：生效时刻一律从这里取（应用机与数据库机差半天会让「刚生效」变成「昨天生效」）。 */
  private OffsetDateTime dbNow() {
    Object raw = sql.fetchValue("select now()");
    return raw instanceof OffsetDateTime value ? value : OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static UUID parseUuid(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 🔴 非法 UUID 按「筛不出东西」处理（筛选参数是用户敲进来的），但**绝不当成没筛选**。 */
  private static final UUID NO_SUCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

  private static UUID parseFilterId(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    UUID parsed = parseUuid(value);
    return parsed == null ? NO_SUCH_ID : parsed;
  }

  private static long count(Object raw) {
    return raw == null ? 0L : ((Number) raw).longValue();
  }

  private static int intOf(Record row, String column) {
    Number value = row.get(column, Number.class);
    return value == null ? 0 : value.intValue();
  }

  private static int clampSize(int size) {
    return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
  }

  private static int clampPage(int page) {
    return Math.max(page, 0);
  }

  private static OffsetDateTime time(Record row, String column) {
    return row.get(column, OffsetDateTime.class);
  }

  private static String idText(UUID id) {
    return id == null ? null : id.toString();
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明：值域、版本化口径、以及**哪些下游未交付**。
   *
   * <p>🔴 下游 **16.2.1.2 拦截执行** 当前<b>未交付</b>，如实报出 ownerModule 与影响；
   * **4.2.3.2 合规预检** 已交付（同服务），但它用的是自己的租户级词表，不是本模块的受版本治理规则库。
   */
  public ComplianceRuleCapabilities capabilities() {
    List<ComplianceRuleCapabilityGap> gaps =
        List.of(
            new ComplianceRuleCapabilityGap(
                "16.2.1.2",
                "拦截执行",
                "16.2.1.2 拦截执行",
                false,
                "拦截执行**未交付** ⇒ 本模块**不建拦截表**、**不造指向不存在入口的放行形状**；"
                    + "规则库页面与 API 里**没有**「立即校验 / 放行 / 豁免」这类入口"
                    + "（放行一个不存在的入口比 404 更糟）。"
                    + "等 16.2.1.2 交付后，由那一侧来读本模块的**生效版本**。"),
            new ComplianceRuleCapabilityGap(
                "4.2.3.2",
                "合规预检（违禁词 / 侵权）",
                "04 刊登与内容 · 4.2.3.2 合规预检",
                true,
                "合规预检**已交付**且就在本服务（`listing_compliance_*`）。"
                    + "但它用的是**自己的租户级词表**，**不是**本模块的受版本治理规则库 —— "
                    + "两边**互不改动对方的表**，边界写在两侧的迁移文件头。"
                    + "本模块**不接管**它的词表，也不替它改判定。"),
            new ComplianceRuleCapabilityGap(
                "02",
                "平台政策同步",
                "02 渠道与店铺 · 平台政策适配器",
                false,
                "平台政策适配器**未交付** ⇒ 本模块当前只有「人工维护」这一条真实路径。"
                    + "本模块**不编造**一份「平台政策自动同步」的假入口；"
                    + "真接上之后，新增的仍然是**版本**（政策变化 = 新版本），不是原地改词。"));

    return new ComplianceRuleCapabilities(
        gaps,
        RULE_TYPES,
        RULE_TYPE_LABELS,
        SEVERITIES,
        SEVERITY_LABELS,
        STATUSES,
        STATUS_LABELS,
        TERM_KINDS,
        TERM_KIND_LABELS,
        WORD_RULE_TYPES,
        RULE_TYPE_NOTE,
        SEVERITY_NOTE,
        VERSIONING_NOTE,
        WORD_LIBRARY_NOTE,
        SCOPE_NOTE,
        UPSTREAM_NOTE,
        DOWNSTREAM_NOTE);
  }

  // ==================================================================
  // 店铺范围
  // ==================================================================

  /** 店铺目录里的一行。 */
  private record ShopRow(
      UUID id, String code, String name, String platformCode, String platformName, UUID platformId) {}

  /**
   * 🔴 店铺清单<b>直接读本服务的店铺目录</b>（{@code shop} / {@code platform}），
   * 不写死店铺常量、不造平行店铺表。
   */
  private List<ShopRow> loadShops(UUID tenant, String platformCode, String keyword) {
    StringBuilder statement =
        new StringBuilder(
            """
            select s.id, s.code, s.name, s.platform_id, p.code as platform_code, p.name as platform_name
            from shop s join platform p on p.id = s.platform_id
            where s.tenant_id = ? and s.status <> 'ARCHIVED'
            """);
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    if (!text(platformCode).isEmpty()) {
      statement.append(" and p.code = ?");
      params.add(text(platformCode).toUpperCase());
    }
    if (!text(keyword).isEmpty()) {
      statement.append(" and (s.code ilike '%' || ? || '%' or s.name ilike '%' || ? || '%')");
      params.add(text(keyword));
      params.add(text(keyword));
    }
    statement.append(" order by s.code");

    List<ShopRow> shops = new ArrayList<>();
    for (Record row : sql.fetch(statement.toString(), params.toArray())) {
      shops.add(
          new ShopRow(
              row.get("id", UUID.class),
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("platform_code", String.class),
              row.get("platform_name", String.class),
              row.get("platform_id", UUID.class)));
    }
    return shops;
  }

  /** 店铺范围清单（**真分页** + 真统计）。 */
  public ComplianceRuleShopPage shops(String platformCode, String keyword, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = clampPage(page);
    int safeSize = clampSize(size);

    List<ShopRow> shops = loadShops(tenant, platformCode, keyword);
    long total = shops.size();

    Map<UUID, long[]> counts = new LinkedHashMap<>();
    for (ShopRow shop : shops) {
      counts.put(shop.id(), new long[2]);
    }
    if (!shops.isEmpty()) {
      List<Object> params = new ArrayList<>();
      params.add(tenant);
      StringBuilder placeholders = new StringBuilder();
      for (ShopRow shop : shops) {
        placeholders.append(placeholders.length() == 0 ? "?" : ",?");
        params.add(shop.id());
      }
      OffsetDateTime now = dbNow();
      // 🔴 一个店铺适用的规则 = 「作用域覆盖到它」的那些规则：
      //   显式点名该店铺的适用范围，或者平台级 / 站点级的适用范围（该店铺在那个平台上、且开了那个站点）。
      for (Record row :
          sql.fetch(
              """
              select s.id as shop_id,
                     count(distinct sc.rule_id) as rule_count,
                     count(distinct sc.rule_id) filter (where v.effective_from <= ?::timestamptz
                        and (v.effective_to is null or v.effective_to > ?::timestamptz)) as effective_count
              from compliance_rule_scope sc
              join compliance_rule_version v
                on v.tenant_id = sc.tenant_id and v.id = sc.rule_version_id
              join compliance_rule r
                on r.tenant_id = v.tenant_id and r.id = v.rule_id
              join shop s
                on s.tenant_id = sc.tenant_id
               and (sc.shop_id = s.id
                    or (sc.shop_id is null and sc.platform_id = s.platform_id
                        and (sc.marketplace_id is null
                             or exists (select 1 from shop_marketplace sm
                                         where sm.tenant_id = s.tenant_id and sm.shop_id = s.id
                                           and sm.marketplace_id = sc.marketplace_id))))
              where sc.tenant_id = ? and r.status = 'ACTIVE' and s.id in ("""
                  + placeholders
                  + """
              ) group by s.id
              """,
              concat(now, now, params))) {
        long[] bucket = counts.get(row.get("shop_id", UUID.class));
        if (bucket != null) {
          bucket[0] = count(row.get("rule_count"));
          bucket[1] = count(row.get("effective_count"));
        }
      }
    }

    int from = Math.min(safePage * safeSize, shops.size());
    int to = Math.min(from + safeSize, shops.size());
    List<ComplianceRuleShop> items = new ArrayList<>();
    for (ShopRow shop : shops.subList(from, to)) {
      long[] bucket = counts.getOrDefault(shop.id(), new long[2]);
      items.add(
          new ComplianceRuleShop(
              shop.id().toString(),
              shop.code(),
              shop.name(),
              shop.platformId().toString(),
              shop.platformCode(),
              shop.platformName(),
              (int) bucket[0],
              (int) bucket[1]));
    }
    return new ComplianceRuleShopPage(items, total, safePage, safeSize);
  }

  /** 把「两个时刻 + 参数列表」拼成 jOOQ 的可变参数。 */
  private static Object[] concat(OffsetDateTime first, OffsetDateTime second, List<Object> rest) {
    List<Object> all = new ArrayList<>();
    all.add(first);
    all.add(second);
    all.addAll(rest);
    return all.toArray();
  }

  // ==================================================================
  // 规则集
  // ==================================================================

  /** 规则清单（**真分页 + 真筛选**）。 */
  public ComplianceRulePage rules(
      String ruleType, String status, String platformCode, String keyword, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = clampPage(page);
    int safeSize = clampSize(size);

    StringBuilder where = new StringBuilder(" where r.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);

    String typeValue = text(ruleType).toUpperCase();
    if (!typeValue.isEmpty()) {
      if (!knownRuleType(typeValue)) {
        throw unprocessable("未知的规则类型：" + ruleType);
      }
      where.append(" and r.rule_type = ?");
      params.add(typeValue);
    }
    String statusValue = text(status).toUpperCase();
    if (!statusValue.isEmpty()) {
      if (!knownStatus(statusValue)) {
        throw unprocessable("未知的规则状态：" + status);
      }
      where.append(" and r.status = ?");
      params.add(statusValue);
    }
    String keywordValue = text(keyword);
    if (!keywordValue.isEmpty()) {
      where.append(
          " and (r.rule_code ilike '%' || ? || '%' or v.title ilike '%' || ? || '%')");
      params.add(keywordValue);
      params.add(keywordValue);
    }
    if (!text(platformCode).isEmpty()) {
      where.append(
          " and exists (select 1 from compliance_rule_scope sc join platform p on p.id = sc.platform_id"
              + " where sc.tenant_id = r.tenant_id and sc.rule_version_id = v.id and p.code = ?)");
      params.add(text(platformCode).toUpperCase());
    }

    OffsetDateTime now = dbNow();
    String currentVersionJoin =
        """
        left join lateral (
            select vv.id, vv.version_no, vv.severity, vv.title, vv.description,
                   vv.effective_from, vv.effective_to
              from compliance_rule_version vv
             where vv.tenant_id = r.tenant_id and vv.rule_id = r.id
               and vv.effective_from <= ?::timestamptz
               and (vv.effective_to is null or vv.effective_to > ?::timestamptz)
             order by vv.effective_from desc limit 1
        ) v on true
        """;

    // 🔴 计数与分组统计**必须带上同一个 lateral join** —— 关键词筛选引用的是
    //   「当前生效版本」的标题，漏掉它会让 where 引用一张不存在的表。
    String base = " from compliance_rule r " + currentVersionJoin;

    List<Object> countParams = new ArrayList<>();
    countParams.add(now);
    countParams.add(now);
    countParams.addAll(params);
    long total = count(sql.fetchValue("select count(*)" + base + where, countParams.toArray()));

    Map<String, Long> typeCounts = new LinkedHashMap<>();
    for (String code : RULE_TYPES) {
      typeCounts.put(code, 0L);
    }
    Map<String, Long> severityCounts = new LinkedHashMap<>();
    for (String code : SEVERITIES) {
      severityCounts.put(code, 0L);
    }
    long activeCount = 0L;
    long retiredCount = 0L;
    for (Record row :
        sql.fetch(
            "select r.rule_type, r.status, v.severity, count(*) as c" + base + where
                + " group by r.rule_type, r.status, v.severity",
            countParams.toArray())) {
      String type = row.get("rule_type", String.class);
      String life = row.get("status", String.class);
      String severity = row.get("severity", String.class);
      long c = count(row.get("c"));
      typeCounts.merge(type, c, Long::sum);
      if (severity != null) {
        severityCounts.merge(severity, c, Long::sum);
      }
      if ("ACTIVE".equals(life)) {
        activeCount += c;
      } else {
        retiredCount += c;
      }
    }

    List<Object> pageParams = new ArrayList<>();
    pageParams.add(now);
    pageParams.add(now);
    pageParams.addAll(params);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<Record> rows =
        sql.fetch(
            "select r.*, v.id as version_id, v.version_no, v.severity, v.title, v.description,"
                + " v.effective_from, v.effective_to,"
                + " (select count(*) from compliance_rule_version vv where vv.tenant_id = r.tenant_id"
                + "   and vv.rule_id = r.id) as version_count,"
                + " (select count(*) from compliance_rule_term t where t.tenant_id = r.tenant_id"
                + "   and t.rule_version_id = v.id) as term_count,"
                + " (select count(*) from compliance_rule_scope sc where sc.tenant_id = r.tenant_id"
                + "   and sc.rule_version_id = v.id) as scope_count"
                + base
                + where
                + " order by r.created_at desc, r.id desc limit ? offset ?",
            pageParams.toArray());

    List<ComplianceRule> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(toRule(row));
    }

    return new ComplianceRulePage(
        items,
        total,
        safePage,
        safeSize,
        activeCount,
        retiredCount,
        typeCounts,
        severityCounts,
        "🔴 规则行上的严重度取自**当前生效的那个版本**；"
            + "各类型 / 各严重度的计数覆盖**整个筛选结果集**（不是当前页）。");
  }

  /** 一行规则 → 传输模型。 */
  private ComplianceRule toRule(Record row) {
    UUID versionId = row.get("version_id", UUID.class);
    String severity = row.get("severity", String.class);
    Integer versionNo = row.get("version_no", Integer.class);
    return new ComplianceRule(
        row.get("id", UUID.class).toString(),
        row.get("rule_code", String.class),
        row.get("rule_type", String.class),
        ruleTypeLabel(row.get("rule_type", String.class)),
        row.get("status", String.class),
        statusLabel(row.get("status", String.class)),
        row.get("title", String.class),
        row.get("description", String.class),
        severity,
        severity == null ? "" : severityLabel(severity),
        intOf(row, "version_count"),
        intOf(row, "term_count"),
        intOf(row, "scope_count"),
        idText(versionId),
        versionNo,
        time(row, "effective_from"),
        time(row, "effective_to"),
        versionId != null,
        time(row, "created_at"));
  }

  /** 规则详情（规则 + 全部版本）。 */
  public ComplianceRuleDetail rule(String ruleId) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(ruleId);
    Record row = ruleRow(tenant, id, dbNow());
    if (row == null) {
      throw notFound();
    }
    return new ComplianceRuleDetail(
        toRule(row), versionsOf(tenant, id), "🔴 版本**不可改写**：改规则 = 新增一个版本。");
  }

  private Record ruleRow(UUID tenant, UUID id, OffsetDateTime at) {
    return sql.fetchOne(
        """
        select r.*, v.id as version_id, v.version_no, v.severity, v.title, v.description,
               v.effective_from, v.effective_to,
               (select count(*) from compliance_rule_version vv where vv.tenant_id = r.tenant_id
                 and vv.rule_id = r.id) as version_count,
               (select count(*) from compliance_rule_term t where t.tenant_id = r.tenant_id
                 and t.rule_version_id = v.id) as term_count,
               (select count(*) from compliance_rule_scope sc where sc.tenant_id = r.tenant_id
                 and sc.rule_version_id = v.id) as scope_count
        from compliance_rule r
        left join lateral (
            select vv.id, vv.version_no, vv.severity, vv.title, vv.description,
                   vv.effective_from, vv.effective_to
              from compliance_rule_version vv
             where vv.tenant_id = r.tenant_id and vv.rule_id = r.id
               and vv.effective_from <= ?::timestamptz
               and (vv.effective_to is null or vv.effective_to > ?::timestamptz)
             order by vv.effective_from desc limit 1
        ) v on true
        where r.tenant_id = ? and r.id = ?
        """,
        at,
        at,
        tenant,
        id);
  }

  // ==================================================================
  // 建规则（只建身份）
  // ==================================================================

  /**
   * 建一条规则（<b>只建身份</b>：编码 + 类型 + 生命周期）。
   *
   * <p>🔴 <b>幂等</b>：命中 {@code (租户, 规则编码)} ⇒ 原样返回既有规则（{@code created=false}），
   * <b>不产生第二条规则</b>、<b>不改写</b>既有内容。
   */
  public ComplianceRuleCreation createRule(ComplianceRuleCreateInput input) {
    UUID tenant = currentTenant();
    String code = text(input == null ? null : input.ruleCode());
    if (code.isEmpty()) {
      throw unprocessable("规则编码（ruleCode）不能为空 —— 它是本模块的幂等锚点");
    }
    String type = text(input == null ? null : input.ruleType()).toUpperCase();
    if (!knownRuleType(type)) {
      throw unprocessable(
          "未知的规则类型："
              + (input == null ? null : input.ruleType())
              + "。可选："
              + String.join(" / ", RULE_TYPES));
    }

    Record existing = sql.fetchOne(
        "select id from compliance_rule where tenant_id = ? and rule_code = ?", tenant, code);
    if (existing != null) {
      UUID id = existing.get("id", UUID.class);
      return new ComplianceRuleCreation(
          toRule(ruleRow(tenant, id, dbNow())),
          false,
          "🔴 幂等命中：这个规则编码（" + code + "）已经存在，返回既有规则，**不新建**。");
    }

    UUID id = UUID.randomUUID();
    sql.execute(
        """
        insert into compliance_rule (id, tenant_id, rule_code, rule_type, status, created_by, created_at, updated_at)
        values (?,?,?,?,'ACTIVE',?,now(),now())
        on conflict (tenant_id, rule_code) do nothing
        """,
        id,
        tenant,
        code,
        type,
        currentActor());
    UUID stored =
        sql.fetchOne("select id from compliance_rule where tenant_id = ? and rule_code = ?", tenant, code)
            .get("id", UUID.class);
    return new ComplianceRuleCreation(
        toRule(ruleRow(tenant, stored, dbNow())),
        true,
        "🔴 规则身份只有编码 / 类型 / 生命周期；**标题、说明、严重度、词条、适用范围都在版本上**，"
            + "要改内容请新增一个版本。");
  }

  /** 改规则的<b>生命周期</b>（停用 / 重新启用）。 */
  public ComplianceRule updateRule(String ruleId, ComplianceRuleUpdateInput input) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(ruleId);
    String status = text(input == null ? null : input.status()).toUpperCase();
    if (!knownStatus(status)) {
      throw unprocessable(
          "未知的规则状态：" + (input == null ? null : input.status()) + "。可选：" + String.join(" / ", STATUSES));
    }
    Record existing = ruleRow(tenant, id, dbNow());
    if (existing == null) {
      throw notFound();
    }
    sql.execute(
        "update compliance_rule set status = ?, updated_at = now() where tenant_id = ? and id = ?",
        status,
        tenant,
        id);
    return toRule(ruleRow(tenant, id, dbNow()));
  }

  // ==================================================================
  // 版本（**规则改动的唯一入口**）
  // ==================================================================

  /**
   * 🔴 新增一个版本 —— <b>规则改动的唯一入口</b>。
   *
   * <p>版本内容（严重度 / 标题 / 说明 / 词条 / 适用范围 / 生效时刻）**一次性写全**：
   * 词条与适用范围属于版本，版本生效后它们就锁死了。
   *
   * <p>🔴 <b>生效时刻不许回填</b>：{@code effectiveFrom} 早于当前时刻一律拒（422）。
   * 回填会让「当时生效的那一版」变成今天这一版，历史判定就再也解释不清。
   */
  public ComplianceRuleVersionDetail createVersion(
      String ruleId, ComplianceRuleVersionInput input) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(ruleId);
    Record ruleRecord = ruleRow(tenant, id, dbNow());
    if (ruleRecord == null) {
      throw notFound();
    }
    String ruleType = ruleRecord.get("rule_type", String.class);
    String severity = text(input == null ? null : input.severity()).toUpperCase();
    if (!knownSeverity(severity)) {
      throw unprocessable(
          "未知的严重度："
              + (input == null ? null : input.severity())
              + "。可选："
              + String.join(" / ", SEVERITIES));
    }
    String title = text(input == null ? null : input.title());
    if (title.isEmpty()) {
      throw unprocessable("版本标题不能为空");
    }

    OffsetDateTime now = dbNow();
    OffsetDateTime effectiveFrom = input == null || input.effectiveFrom() == null ? now : input.effectiveFrom();
    if (effectiveFrom.isBefore(now)) {
      throw unprocessable(
          "生效时刻不能早于当前时刻（不许回填：回填会改写「当时生效的那一版」，历史判定就再也解释不清）");
    }

    int versionNo =
        sql.fetchValue(
                "select coalesce(max(version_no),0) + 1 from compliance_rule_version where tenant_id = ? and rule_id = ?",
                tenant,
                id)
            instanceof Number n
            ? n.intValue()
            : 1;

    UUID versionId = UUID.randomUUID();
    sql.execute(
        """
        insert into compliance_rule_version
          (id, tenant_id, rule_id, version_no, severity, title, description,
           effective_from, effective_to, change_note, created_by, created_at)
        values (?,?,?,?,?,?,?,?::timestamptz,null,?,?,now())
        """,
        versionId,
        tenant,
        id,
        versionNo,
        severity,
        title,
        text(input == null ? null : input.description()),
        effectiveFrom,
        text(input == null ? null : input.changeNote()),
        currentActor());

    // 🔴 词条与适用范围**属于版本**：随版本一起落库，落完即锁死。
    List<ComplianceRuleTermInput> terms = collectTerms(input);
    if (!terms.isEmpty()) {
      if (!wordRuleType(ruleType)) {
        throw unprocessable(
            "规则类型「"
                + ruleTypeLabel(ruleType)
                + "」不带词条（只有违禁词 / 极限词 / 侵权词三类规则有词条集合）");
      }
      insertTerms(tenant, id, versionId, terms);
    }
    List<ComplianceRuleScopeInput> scopes =
        input == null || input.scopes() == null ? List.of() : input.scopes();
    if (!scopes.isEmpty()) {
      insertScopes(tenant, id, versionId, scopes);
    }

    return versionDetail(tenant, id, versionId);
  }

  /** 收集词条入参（数组 + 词库原文，合并去重）。 */  private List<ComplianceRuleTermInput> collectTerms(ComplianceRuleVersionInput input) {
    if (input == null) {
      return List.of();
    }
    Map<String, ComplianceRuleTermInput> merged = new LinkedHashMap<>();
    if (input.terms() != null) {
      for (ComplianceRuleTermInput term : input.terms()) {
        if (term == null) {
          continue;
        }
        String value = text(term.term());
        if (value.isEmpty()) {
          continue;
        }
        merged.putIfAbsent(value, term);
      }
    }
    // 🔴 词库上传：一行一个词；空行与 # 开头的注释行忽略。
    for (String line : text(input.termsText()).split("\\R")) {
      String value = line.trim();
      if (value.isEmpty() || value.startsWith("#")) {
        continue;
      }
      merged.putIfAbsent(value, new ComplianceRuleTermInput(value, "GENERAL", ""));
    }
    return List.copyOf(merged.values());
  }

  /** 版本清单（**真分页**）。 */
  public ComplianceRuleVersionPage versions(String ruleId, int page, int size) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(ruleId);
    int safePage = clampPage(page);
    int safeSize = clampSize(size);
    if (sql.fetchOne("select id from compliance_rule where tenant_id = ? and id = ?", tenant, id) == null) {
      throw notFound();
    }

    long total =
        count(
            sql.fetchValue(
                "select count(*) from compliance_rule_version where tenant_id = ? and rule_id = ?",
                tenant,
                id));

    List<Object> pageParams = new ArrayList<>();
    pageParams.add(tenant);
    pageParams.add(id);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<Record> rows =
        sql.fetch(
            """
            select v.*, r.rule_code,
                   (select count(*) from compliance_rule_term t where t.tenant_id = v.tenant_id
                     and t.rule_version_id = v.id) as term_count,
                   (select count(*) from compliance_rule_scope sc where sc.tenant_id = v.tenant_id
                     and sc.rule_version_id = v.id) as scope_count
            from compliance_rule_version v
            join compliance_rule r on r.tenant_id = v.tenant_id and r.id = v.rule_id
            where v.tenant_id = ? and v.rule_id = ?
            order by v.version_no desc limit ? offset ?
            """,
            pageParams.toArray());

    OffsetDateTime now = dbNow();
    List<ComplianceRuleVersion> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(toVersion(row, now));
    }
    return new ComplianceRuleVersionPage(
        items,
        total,
        safePage,
        safeSize,
        "🔴 版本**不可改写**：改规则 = 新增一个版本（旧版本原样留着，历史判定才解释得清）。");
  }

  private List<ComplianceRuleVersion> versionsOf(UUID tenant, UUID ruleId) {
    OffsetDateTime now = dbNow();
    List<ComplianceRuleVersion> result = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select v.*, r.rule_code,
                   (select count(*) from compliance_rule_term t where t.tenant_id = v.tenant_id
                     and t.rule_version_id = v.id) as term_count,
                   (select count(*) from compliance_rule_scope sc where sc.tenant_id = v.tenant_id
                     and sc.rule_version_id = v.id) as scope_count
            from compliance_rule_version v
            join compliance_rule r on r.tenant_id = v.tenant_id and r.id = v.rule_id
            where v.tenant_id = ? and v.rule_id = ?
            order by v.version_no desc
            """,
            tenant,
            ruleId)) {
      result.add(toVersion(row, now));
    }
    return result;
  }

  private ComplianceRuleVersion toVersion(Record row, OffsetDateTime now) {
    OffsetDateTime from = time(row, "effective_from");
    OffsetDateTime to = time(row, "effective_to");
    boolean inForce = from != null && !from.isAfter(now) && (to == null || to.isAfter(now));
    String severity = row.get("severity", String.class);
    return new ComplianceRuleVersion(
        row.get("id", UUID.class).toString(),
        row.get("rule_id", UUID.class).toString(),
        row.get("rule_code", String.class),
        intOf(row, "version_no"),
        severity,
        severityLabel(severity),
        row.get("title", String.class),
        row.get("description", String.class),
        from,
        to,
        inForce,
        row.get("change_note", String.class),
        intOf(row, "term_count"),
        intOf(row, "scope_count"),
        time(row, "created_at"));
  }

  /** 版本详情（版本 + 词条 + 适用范围）。 */
  public ComplianceRuleVersionDetail versionDetail(String ruleId, String versionId) {
    UUID tenant = currentTenant();
    UUID rule = requireUuid(ruleId);
    UUID version = requireUuid(versionId);
    Record row = versionRow(tenant, rule, version);
    if (row == null) {
      throw notFound();
    }
    return versionDetail(tenant, rule, version);
  }

  private ComplianceRuleVersionDetail versionDetail(UUID tenant, UUID ruleId, UUID versionId) {
    Record versionRecord = versionRow(tenant, ruleId, versionId);
    if (versionRecord == null) {
      throw notFound();
    }
    Record ruleRecord = ruleRow(tenant, ruleId, dbNow());
    OffsetDateTime now = dbNow();
    return new ComplianceRuleVersionDetail(
        ruleRecord == null ? null : toRule(ruleRecord),
        toVersion(versionRecord, now),
        termsOf(tenant, versionId),
        scopesOf(tenant, versionId),
        "🔴 词条与适用范围**属于版本**：版本生效后锁死；改一个词 = 新增一个版本。");
  }

  private Record versionRow(UUID tenant, UUID ruleId, UUID versionId) {
    return sql.fetchOne(
        """
        select v.*, r.rule_code,
               (select count(*) from compliance_rule_term t where t.tenant_id = v.tenant_id
                 and t.rule_version_id = v.id) as term_count,
               (select count(*) from compliance_rule_scope sc where sc.tenant_id = v.tenant_id
                 and sc.rule_version_id = v.id) as scope_count
        from compliance_rule_version v
        join compliance_rule r on r.tenant_id = v.tenant_id and r.id = v.rule_id
        where v.tenant_id = ? and v.rule_id = ? and v.id = ?
        """,
        tenant,
        ruleId,
        versionId);
  }

  /**
   * 🔴 <b>可解释性</b>：某一时刻生效的是哪一版 —— 连同它**当时**的词条与适用范围。
   *
   * <p>这是蓝图加粗句的落点：政策变化后，回头查「某个商品当时被判违规，依据的是哪一版、
   * 那一版有多严重、按哪些词判」，这里答得出来。
   */
  public ComplianceRuleEffectiveVersion effectiveVersion(String ruleId, String at) {
    UUID tenant = currentTenant();
    UUID rule = requireUuid(ruleId);
    OffsetDateTime moment = parseTime(at, "解析时刻");
    if (moment == null) {
      moment = dbNow();
    }
    Record ruleRecord = ruleRow(tenant, rule, moment);
    if (ruleRecord == null) {
      throw notFound();
    }
    UUID versionId = inForceVersionId(tenant, rule, moment);
    if (versionId == null) {
      return new ComplianceRuleEffectiveVersion(
          toRule(ruleRecord),
          null,
          List.of(),
          List.of(),
          moment,
          "🔴 这一刻**没有生效的版本** —— 该规则在这个时刻不生效（要么还没到第一版的生效时刻，"
              + "要么它已被停用）。**不是「查不到就是没问题」**。");
    }
    Record versionRecord = versionRow(tenant, rule, versionId);
    return new ComplianceRuleEffectiveVersion(
        toRule(ruleRecord),
        toVersion(versionRecord, moment),
        termsOf(tenant, versionId),
        scopesOf(tenant, versionId),
        moment,
        "🔴 这是**那一刻生效的那一版**（连同它当时的词条与适用范围），"
            + "**不是**按今天的内容重算的结果。");
  }

  /** 某一时刻生效的版本 id（同一时刻只可能有一个）。 */
  private UUID inForceVersionId(UUID tenant, UUID ruleId, OffsetDateTime at) {
    return sql.fetchOne(
            """
            select v.id from compliance_rule_version v
             where v.tenant_id = ? and v.rule_id = ?
               and v.effective_from <= ?::timestamptz
               and (v.effective_to is null or v.effective_to > ?::timestamptz)
             order by v.effective_from desc limit 1
            """,
            tenant,
            ruleId,
            at,
            at)
        .get("id", UUID.class);
  }

  // ==================================================================
  // 词条
  // ==================================================================

  /** 词条清单（**真分页**）。 */
  public ComplianceRuleTermPage terms(String ruleId, String versionId, int page, int size) {
    UUID tenant = currentTenant();
    UUID rule = requireUuid(ruleId);
    UUID version = requireUuid(versionId);
    int safePage = clampPage(page);
    int safeSize = clampSize(size);
    if (versionRow(tenant, rule, version) == null) {
      throw notFound();
    }

    long total =
        count(
            sql.fetchValue(
                "select count(*) from compliance_rule_term where tenant_id = ? and rule_version_id = ?",
                tenant,
                version));

    List<Object> pageParams = new ArrayList<>();
    pageParams.add(tenant);
    pageParams.add(version);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<Record> rows =
        sql.fetch(
            """
            select * from compliance_rule_term
             where tenant_id = ? and rule_version_id = ?
             order by term limit ? offset ?
            """,
            pageParams.toArray());

    List<ComplianceRuleTerm> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(toTerm(row));
    }
    return new ComplianceRuleTermPage(
        items,
        total,
        safePage,
        safeSize,
        "🔴 词条**属于版本**：版本生效后锁死（改一个词 = 新增一个版本）；词条**不可删除**。");
  }

  /** 追加词条（**批量**，一条规则多个词条）。 */
  public ComplianceRuleTermBatchResult addTerms(
      String ruleId, String versionId, ComplianceRuleTermsInput input) {
    UUID tenant = currentTenant();
    UUID rule = requireUuid(ruleId);
    UUID version = requireUuid(versionId);
    if (versionRow(tenant, rule, version) == null) {
      throw notFound();
    }
    requireVersionEditable(tenant, version);
    List<ComplianceRuleTermInput> terms =
        input == null || input.terms() == null ? List.of() : input.terms();
    if (terms.isEmpty()) {
      throw unprocessable("至少要给一个词条");
    }
    if (terms.size() > MAX_TERMS_PER_CALL) {
      throw unprocessable("一次最多追加 " + MAX_TERMS_PER_CALL + " 个词条");
    }
    List<String> skipped = new ArrayList<>();
    int inserted = insertTerms(tenant, rule, version, terms, skipped);
    return new ComplianceRuleTermBatchResult(
        version.toString(),
        terms.size(),
        inserted,
        skipped.size(),
        skipped,
        "🔴 词条**属于版本**；同一版本里已存在的词会被跳过（重复上传同一份词库不产生第二条）。");
  }

  /**
   * 🔴 <b>词库上传</b>：整份词库一次落库（每行一个词）。
   *
   * <p>空行与 {@code #} 开头的注释行忽略；与版本里已有的词重复的会被跳过。
   */
  public ComplianceRuleTermBatchResult uploadTerms(
      String ruleId, String versionId, ComplianceRuleTermsUploadInput input) {
    UUID tenant = currentTenant();
    UUID rule = requireUuid(ruleId);
    UUID version = requireUuid(versionId);
    if (versionRow(tenant, rule, version) == null) {
      throw notFound();
    }
    requireVersionEditable(tenant, version);
    String content = text(input == null ? null : input.content());
    if (content.isEmpty()) {
      throw unprocessable("词库内容不能为空（每行一个词）");
    }
    List<ComplianceRuleTermInput> terms = new ArrayList<>();
    for (String line : content.split("\\R")) {
      String value = line.trim();
      if (value.isEmpty() || value.startsWith("#")) {
        continue;
      }
      terms.add(new ComplianceRuleTermInput(value, "GENERAL", ""));
    }
    if (terms.isEmpty()) {
      throw unprocessable("词库里没有解析出任何词（每行一个词，空行与 # 注释行会被忽略）");
    }
    if (terms.size() > MAX_TERMS_PER_CALL) {
      throw unprocessable("一次最多上传 " + MAX_TERMS_PER_CALL + " 个词");
    }
    List<String> skipped = new ArrayList<>();
    int inserted = insertTerms(tenant, rule, version, terms, skipped);
    return new ComplianceRuleTermBatchResult(
        version.toString(),
        terms.size(),
        inserted,
        skipped.size(),
        skipped,
        "🔴 词库上传是**批量**的：整份词库一次落库（每行一个词，空行与 # 注释行忽略），"
            + "与已有词重复的跳过。");
  }

  /** 落词条（返回真正写入的条数）。 */
  private int insertTerms(
      UUID tenant, UUID ruleId, UUID versionId, List<ComplianceRuleTermInput> terms) {
    return insertTerms(tenant, ruleId, versionId, terms, new ArrayList<>());
  }

  private int insertTerms(
      UUID tenant,
      UUID ruleId,
      UUID versionId,
      List<ComplianceRuleTermInput> terms,
      List<String> skipped) {
    int inserted = 0;
    for (ComplianceRuleTermInput term : terms) {
      String value = text(term == null ? null : term.term());
      if (value.isEmpty()) {
        continue;
      }
      String kind = text(term == null ? null : term.termKind()).toUpperCase();
      if (kind.isEmpty()) {
        kind = "GENERAL";
      }
      if (!knownTermKind(kind)) {
        throw unprocessable(
            "未知的词条分类："
                + (term == null ? null : term.termKind())
                + "。可选："
                + String.join(" / ", TERM_KINDS));
      }
      int affected =
          sql.execute(
              """
              insert into compliance_rule_term
                (id, tenant_id, rule_version_id, term, term_kind, note, created_at)
              values (?,?,?,?,?,?,now())
              on conflict (tenant_id, rule_version_id, term) do nothing
              """,
              UUID.randomUUID(),
              tenant,
              versionId,
              value,
              kind,
              text(term == null ? null : term.note()));
      if (affected > 0) {
        inserted++;
      } else {
        skipped.add(value);
      }
    }
    return inserted;
  }

  private List<ComplianceRuleTerm> termsOf(UUID tenant, UUID versionId) {
    List<ComplianceRuleTerm> result = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select * from compliance_rule_term
             where tenant_id = ? and rule_version_id = ? order by term
            """,
            tenant,
            versionId)) {
      result.add(toTerm(row));
    }
    return result;
  }

  private ComplianceRuleTerm toTerm(Record row) {
    String kind = row.get("term_kind", String.class);
    return new ComplianceRuleTerm(
        row.get("id", UUID.class).toString(),
        row.get("rule_version_id", UUID.class).toString(),
        row.get("term", String.class),
        kind,
        termKindLabel(kind),
        row.get("note", String.class),
        time(row, "created_at"));
  }

  // ==================================================================
  // 适用范围
  // ==================================================================

  /** 适用范围清单（**真分页**）。 */
  public ComplianceRuleScopePage scopes(String ruleId, String versionId, int page, int size) {
    UUID tenant = currentTenant();
    UUID rule = requireUuid(ruleId);
    UUID version = requireUuid(versionId);
    int safePage = clampPage(page);
    int safeSize = clampSize(size);
    if (versionRow(tenant, rule, version) == null) {
      throw notFound();
    }

    long total =
        count(
            sql.fetchValue(
                "select count(*) from compliance_rule_scope where tenant_id = ? and rule_version_id = ?",
                tenant,
                version));

    List<Object> pageParams = new ArrayList<>();
    pageParams.add(tenant);
    pageParams.add(version);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<Record> rows =
        sql.fetch(
            """
            select sc.*, p.code as platform_code, p.name as platform_name,
                   m.code as marketplace_code, m.name as marketplace_name,
                   s.code as shop_code, s.name as shop_name
            from compliance_rule_scope sc
            join platform p on p.id = sc.platform_id
            left join marketplace m on m.id = sc.marketplace_id
            left join shop s on s.tenant_id = sc.tenant_id and s.id = sc.shop_id
             where sc.tenant_id = ? and sc.rule_version_id = ?
             order by p.code, m.code nulls first, s.code nulls first
             limit ? offset ?
            """,
            pageParams.toArray());

    List<ComplianceRuleScope> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(toScope(row));
    }
    return new ComplianceRuleScopePage(
        items,
        total,
        safePage,
        safeSize,
        "🔴 适用范围 = **适用平台 + 站点**（可再收窄到具体店铺），直接读本服务自己的店铺目录。");
  }

  /** 追加适用范围（**批量**）。 */
  public ComplianceRuleScopeBatchResult addScopes(
      String ruleId, String versionId, ComplianceRuleScopesInput input) {
    UUID tenant = currentTenant();
    UUID rule = requireUuid(ruleId);
    UUID version = requireUuid(versionId);
    if (versionRow(tenant, rule, version) == null) {
      throw notFound();
    }
    requireVersionEditable(tenant, version);
    List<ComplianceRuleScopeInput> scopes =
        input == null || input.scopes() == null ? List.of() : input.scopes();
    if (scopes.isEmpty()) {
      throw unprocessable("至少要给一条适用范围");
    }
    if (scopes.size() > MAX_SCOPES_PER_CALL) {
      throw unprocessable("一次最多追加 " + MAX_SCOPES_PER_CALL + " 条适用范围");
    }
    List<String> skipped = new ArrayList<>();
    int inserted = insertScopes(tenant, rule, version, scopes, skipped);
    return new ComplianceRuleScopeBatchResult(
        version.toString(),
        scopes.size(),
        inserted,
        skipped.size(),
        skipped,
        "🔴 适用范围**属于版本**；重复的「平台 + 站点 + 店铺」会被跳过。");
  }

  private int insertScopes(
      UUID tenant, UUID ruleId, UUID versionId, List<ComplianceRuleScopeInput> scopes) {
    return insertScopes(tenant, ruleId, versionId, scopes, new ArrayList<>());
  }

  private int insertScopes(
      UUID tenant,
      UUID ruleId,
      UUID versionId,
      List<ComplianceRuleScopeInput> scopes,
      List<String> skipped) {
    int inserted = 0;
    for (ComplianceRuleScopeInput scope : scopes) {
      UUID platform = parseUuid(scope == null ? null : scope.platformId());
      if (platform == null) {
        throw unprocessable("适用平台（platformId）不能为空，且必须是合法 UUID");
      }
      UUID marketplace = parseUuid(scope == null ? null : scope.marketplaceId());
      UUID shop = parseUuid(scope == null ? null : scope.shopId());
      String label = platform + " / " + (marketplace == null ? "全部站点" : marketplace);
      int affected =
          sql.execute(
              """
              insert into compliance_rule_scope
                (id, tenant_id, rule_id, rule_version_id, platform_id, marketplace_id, shop_id, created_at)
              values (?,?,?,?,?,?,?,now())
              on conflict (tenant_id, rule_version_id, platform_id, marketplace_id, shop_id) do nothing
              """,
              UUID.randomUUID(),
              tenant,
              ruleId,
              versionId,
              platform,
              marketplace,
              shop);
      if (affected > 0) {
        inserted++;
      } else {
        skipped.add(label);
      }
    }
    return inserted;
  }

  private List<ComplianceRuleScope> scopesOf(UUID tenant, UUID versionId) {
    List<ComplianceRuleScope> result = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select sc.*, p.code as platform_code, p.name as platform_name,
                   m.code as marketplace_code, m.name as marketplace_name,
                   s.code as shop_code, s.name as shop_name
            from compliance_rule_scope sc
            join platform p on p.id = sc.platform_id
            left join marketplace m on m.id = sc.marketplace_id
            left join shop s on s.tenant_id = sc.tenant_id and s.id = sc.shop_id
             where sc.tenant_id = ? and sc.rule_version_id = ?
             order by p.code, m.code nulls first, s.code nulls first
            """,
            tenant,
            versionId)) {
      result.add(toScope(row));
    }
    return result;
  }

  private ComplianceRuleScope toScope(Record row) {
    return new ComplianceRuleScope(
        row.get("id", UUID.class).toString(),
        row.get("rule_version_id", UUID.class).toString(),
        row.get("platform_id", UUID.class).toString(),
        row.get("platform_code", String.class),
        row.get("platform_name", String.class),
        idText(row.get("marketplace_id", UUID.class)),
        row.get("marketplace_code", String.class),
        row.get("marketplace_name", String.class),
        idText(row.get("shop_id", UUID.class)),
        row.get("shop_code", String.class),
        row.get("shop_name", String.class),
        time(row, "created_at"));
  }

  // ==================================================================
  // 生效规则集
  // ==================================================================

  /**
   * 🔴 <b>生效规则集</b>：某一时刻真正生效的规则 + 它们生效的那一版。
   *
   * <p>这就是蓝图说的输出「规则集 + 生效版本」。下游 16.2.1.2 拦截执行交付后，
   * 由那一侧来读这个集合。
   */
  public ComplianceRuleEffectiveSet effective(
      String at, String platformCode, String shopId, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = clampPage(page);
    int safeSize = clampSize(size);
    OffsetDateTime moment = parseTime(at, "解析时刻");
    if (moment == null) {
      moment = dbNow();
    }

    StringBuilder where =
        new StringBuilder(
            """
             where r.tenant_id = ? and r.status = 'ACTIVE'
               and v.effective_from <= ?::timestamptz
               and (v.effective_to is null or v.effective_to > ?::timestamptz)
            """);
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    params.add(moment);
    params.add(moment);

    if (!text(platformCode).isEmpty()) {
      where.append(
          " and exists (select 1 from compliance_rule_scope sc join platform p on p.id = sc.platform_id"
              + " where sc.tenant_id = r.tenant_id and sc.rule_version_id = v.id and p.code = ?)");
      params.add(text(platformCode).toUpperCase());
    }
    UUID filterShop = parseFilterId(shopId);
    if (filterShop != null) {
      where.append(
          " and exists (select 1 from compliance_rule_scope sc join shop s on s.tenant_id = sc.tenant_id"
              + " where sc.tenant_id = r.tenant_id and sc.rule_version_id = v.id"
              + "   and (sc.shop_id = s.id or (sc.shop_id is null and sc.platform_id = s.platform_id"
              + "        and (sc.marketplace_id is null"
              + "             or exists (select 1 from shop_marketplace sm"
              + "                         where sm.tenant_id = s.tenant_id and sm.shop_id = s.id"
              + "                           and sm.marketplace_id = sc.marketplace_id))))"
              + "   and s.id = ?)");
      params.add(filterShop);
    }

    long total =
        count(
            sql.fetchValue(
                "select count(*) from compliance_rule r join compliance_rule_version v"
                    + " on v.tenant_id = r.tenant_id and v.rule_id = r.id"
                    + where,
                params.toArray()));

    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<Record> rows =
        sql.fetch(
            """
            select r.*, v.id as version_id, v.version_no, v.severity, v.title, v.description,
                   v.effective_from, v.effective_to,
                   (select count(*) from compliance_rule_version vv where vv.tenant_id = r.tenant_id
                     and vv.rule_id = r.id) as version_count,
                   (select count(*) from compliance_rule_term t where t.tenant_id = r.tenant_id
                     and t.rule_version_id = v.id) as term_count,
                   (select count(*) from compliance_rule_scope sc where sc.tenant_id = r.tenant_id
                     and sc.rule_version_id = v.id) as scope_count
            from compliance_rule r
            join compliance_rule_version v on v.tenant_id = r.tenant_id and v.rule_id = r.id
            """
                + where
                + " order by r.rule_code, v.version_no desc limit ? offset ?",
            pageParams.toArray());

    List<ComplianceRuleEffectiveEntry> items = new ArrayList<>();
    for (Record row : rows) {
      UUID versionId = row.get("version_id", UUID.class);
      List<String> platformCodes = new ArrayList<>();
      List<String> marketplaceCodes = new ArrayList<>();
      List<String> shopCodes = new ArrayList<>();
      for (Record scope : sql.fetch(
          """
          select p.code as platform_code, m.code as marketplace_code, s.code as shop_code
          from compliance_rule_scope sc
          join platform p on p.id = sc.platform_id
          left join marketplace m on m.id = sc.marketplace_id
          left join shop s on s.tenant_id = sc.tenant_id and s.id = sc.shop_id
           where sc.tenant_id = ? and sc.rule_version_id = ?
           order by p.code, m.code nulls first, s.code nulls first
          """,
          tenant,
          versionId)) {
        addDistinct(platformCodes, scope.get("platform_code", String.class));
        addDistinct(marketplaceCodes, scope.get("marketplace_code", String.class));
        addDistinct(shopCodes, scope.get("shop_code", String.class));
      }
      items.add(
          new ComplianceRuleEffectiveEntry(
              toRule(row),
              toVersion(versionRow(tenant, row.get("id", UUID.class), versionId), moment),
              intOf(row, "term_count"),
              platformCodes,
              marketplaceCodes,
              shopCodes,
              "🔴 这一行是**那一刻生效的那一版**；词条与适用范围也是那一版的，不是今天的。"));
    }

    return new ComplianceRuleEffectiveSet(
        items,
        total,
        safePage,
        safeSize,
        moment,
        "🔴 生效规则集 = **规则集 + 生效版本**；解析时刻可指定，"
            + "用来回答「当时按哪一版判的」。");
  }

  private static void addDistinct(List<String> target, String value) {
    if (value != null && !target.contains(value)) {
      target.add(value);
    }
  }

  // ==================================================================
  // 内部
  // ==================================================================

  /**
   * 🔴 版本内容（词条 / 适用范围）**可编辑性**的前置校验（服务层先拒，DB 触发器再兜底）。
   *
   * <p>版本生效之后，词条与适用范围就锁死了 —— 改一个词 = 新增一个版本。
   */
  private void requireVersionEditable(UUID tenant, UUID versionId) {
    Object editable =
        sql.fetchValue("select compliance_rule_version_editable(?,?)", tenant, versionId);
    if (!Boolean.TRUE.equals(editable)) {
      throw unprocessable(
          "规则版本已经生效，词条 / 适用范围不可再改（改规则 = 新增一个版本，"
              + "这样历史判定才解释得清）");
    }
  }

  private static UUID requireUuid(String raw) {
    UUID parsed = parseUuid(raw);
    if (parsed == null) {
      // 🔴 路径参数非法 ⇒ 404（跨租户与不存在逐字相同），不泄露「这个 id 格式对不对」。
      throw notFound();
    }
    return parsed;
  }

  /** 解析时间入参；空 ⇒ null，非法 ⇒ 422。 */
  private static OffsetDateTime parseTime(String raw, String field) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value);
    } catch (RuntimeException e) {
      throw unprocessable(field + " 不是合法的 ISO 时间");
    }
  }
}
