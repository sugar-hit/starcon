package com.starcon.channel;

import static com.starcon.channel.IpRiskLibraryModels.*;
import static com.starcon.channel.IpRiskLibraryRules.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.4.1.1 侵权库。
 *
 * <p>蓝图：「维护知识产权风险库：品牌商标、专利、版权、肖像权，登记风险等级与适用平台。
 * 输入：侵权条目登记；批量导入。输出：侵权库记录。关系：上游 = 3.3.2 品牌；下游 = 16.4.1.2 检测。
 * <b>侵权检测是刊登前拦截而非事后申诉</b>，成本差一个数量级。」
 *
 * <p>🔴🔴 本类里有六处<b>结构</b>上的不变量：
 *
 * <ol>
 *   <li><b>权利类型 / 风险等级是受控值域</b>：DB 层 CHECK 钉死（迁移 V315），
 *       服务层<b>先</b>按同一份规则拒（422，带人话），DB <b>再</b>兜底（23514）。
 *       风险等级<b>不是</b> 0..100 的裸数字。</li>
 *   <li><b>适用平台是多对多</b>：独立表存 ⇒ 「这个平台上有哪些高风险条目」可反查。</li>
 *   <li><b>匹配特征是多值</b>：品牌名（可空）+ 关键词 / 短语集合 + 可选标识符特征。
 *       <b>本模块只登记，不执行任何比对</b>。</li>
 *   <li><b>批量导入逐行提交</b>：一行失败<b>不回滚</b>已成功的行（逐行 savepoint）；
 *       结果<b>落库</b>（{@code ip_risk_import_batch} / {@code ip_risk_import_row}），
 *       不是「整个文件成功 / 失败」二选一。</li>
 *   <li><b>批量导入幂等</b>：{@code UNIQUE(tenant_id, entry_code)} 是锚点，
 *       重复导入<b>不产生重复条目</b>（<b>不靠「先查后插」的竞态写法</b>）。</li>
 *   <li><b>只停用、不物理删除</b>：停用必须写理由；停用后条目内容<b>冻结</b>
 *       （历史要能解释），重新启用才能改。</li>
 * </ol>
 */
@Service
public class IpRiskLibraryService {

  /** 一页最多多少行。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 一次导入最多多少行。 */
  private static final int MAX_IMPORT_ROWS = 2000;

  // ==================================================================
  // 口径说明（能力声明与各处共用同一句话）
  // ==================================================================

  private static final String RIGHT_TYPE_NOTE =
      "🔴 权利类型是**受控枚举**（四种：品牌商标 / 专利 / 版权 / 肖像权），由 DB 层 CHECK 钉死。"
          + "**不**留自由文本 —— 自由文本的「商标」/「TRADEMARK」/「商标类」会被检测那一侧"
          + "当成三种不同的权利，统计口径就废了。";

  private static final String RISK_LEVEL_NOTE =
      "🔴 风险等级是**受控等级集**（高 / 中 / 低），**不用 0..100 的裸数字** —— "
          + "裸数字没有口径，下游拿到的「72 分」没法解释成「高风险」还是「中风险」，"
          + "排序与筛选也无从统一。";

  private static final String PLATFORM_NOTE =
      "🔴 适用平台是**多对多**：一条侵权条目常常在多个平台上都要拦（Amazon / Shopify / eBay / TikTok …），"
          + "所以它落在独立表里，**不是一个单列**。平台编码直接读本服务自己的 `platform` 目录"
          + "（全局码表，**不复制目录表、不造平行平台表**）⇒ 「这个平台上有哪些高风险条目」可直接反查。";

  private static final String MATCH_FEATURE_NOTE =
      "🔴 匹配特征是本模块**最关键**的一组字段：下游 16.4.1.2 要拿它去比对商品"
          + "**标题 / 描述 / 图片 / 品牌**。条目因此带三个可被匹配的字段："
          + "**品牌名**（可空 —— 有些专利 / 版权没有品牌名）、**关键词 / 短语集合**（多值）、"
          + "**标识符 / 外观特征描述**（可选，如商标注册号、版权作品名）。"
          + "🔴 **本模块只登记这些特征，不执行任何比对**，也不产出任何检测结论。";

  private static final String LIFECYCLE_NOTE =
      "🔴 生命周期只有**启用 / 停用**两态。**停用 = 不再参与未来的检测，但历史记录保留**："
          + "停用必须写**理由**（没有理由的停用等于「随手关掉」），停用后条目的匹配特征**冻结**"
          + "（那会改写「停用那一刻它是什么」），重新启用才能改。"
          + "🔴 **不做物理删除**：引用它的其他模块可能已经引用过这一行。";

  private static final String IMPORT_NOTE =
      "🔴 批量导入是**真入库 + 行级结果**：一次导入返回**成功行数 / 失败行数 + 逐行错误**"
          + "（哪一行、哪个字段、为什么），不是「整个文件成功 / 失败」二选一。"
          + "🔴 **部分失败不回滚已成功的行**（逐行 savepoint 提交）。"
          + "🔴 **幂等**：同一批重复导入**不产生重复条目**（条目编码唯一约束兜底）。";

  private static final String UPSTREAM_NOTE =
      "上游 = **3.3.2 品牌**（**catalog 服务**，端口 8082，**已交付**）。"
          + "跨服务**只经授权 HTTP API 读**（`GET /catalog/brands`），转发调用方自己的 bearer，"
          + "租户仍由上游按已认证成员关系解析（两道 RLS）。"
          + "落库只存品牌**逻辑 id**，**零跨库 FK、零影子表**。"
          + "🔴 **读不到就显式说读不到**（available=false + 原因），"
          + "**绝不返回空集合冒充「上游一个数据都没有」**。";

  private static final String DOWNSTREAM_NOTE =
      "下游 = **16.4.1.2 检测与预警**（**未交付** ⇒ 本模块**只做「库 + 登记 + 批量导入 + 查询」**，"
          + "**不实现检测逻辑、不产出任何检测结论、不建检测结果表**；"
          + "页面与 API 里**没有**「立即检测 / 放行」这类入口 —— 放行一个不存在的入口比 404 更糟）。"
          + "**16.4.2.1 品牌授权**（`brand_authz_*`）与 **16.4.2.2 授权凭证**（`brand_cred_*`）"
          + "**已交付但与本模块是两件事**（那两个管「授权」，本模块管「风险条目」）⇒ **零 FK**。"
          + "**4 刊登**已交付，但刊登前的检测归 16.4.1.2，本模块**不越界**做拦截。";

  private final DSLContext sql;
  private final IpRiskLibrarySourcePort source;

  public IpRiskLibraryService(DSLContext sql, IpRiskLibrarySourcePort source) {
    this.sql = sql;
    this.source = source;
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

  /** 🔴 <b>同源 DB 时钟</b>：停用时刻一律从这里取。 */
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

  /** 路径里的 id 非法 ⇒ 与「不存在」同等处理（不泄露「格式对但没有」）。 */
  private static UUID requireUuid(String raw) {
    UUID parsed = parseUuid(raw);
    if (parsed == null) {
      throw notFound();
    }
    return parsed;
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
   * 能力声明：值域、各条口径，以及**哪些下游未交付**。
   *
   * <p>🔴 下游 **16.4.1.2 检测与预警** 当前<b>未交付</b>，如实报出 ownerModule 与影响；
   * **16.4.2.1 / 16.4.2.2** 已交付但**与本模块是两件事**（零 FK）。
   */
  public IpRiskLibraryCapabilities capabilities() {
    List<IpRiskLibraryCapabilityGap> gaps =
        List.of(
            new IpRiskLibraryCapabilityGap(
                "16.4.1.2",
                "检测与预警",
                "16.4.1.2 检测与预警",
                false,
                "检测与预警**未交付** ⇒ 本模块**只做「库 + 登记 + 批量导入 + 查询」**："
                    + "**不实现检测逻辑、不产出任何检测结论、不建检测结果表**，"
                    + "页面与 API 里**没有**「立即检测 / 放行」这类入口"
                    + "（放行一个不存在的入口比 404 更糟）。"
                    + "等 16.4.1.2 交付后，由那一侧来读本模块的**条目与匹配特征**。"),
            new IpRiskLibraryCapabilityGap(
                "3.3.2",
                "品牌",
                "03 商品主数据 · 3.3.2 品牌",
                true,
                "品牌**已交付**（catalog 服务，端口 8082）⇒ 本模块经只读端口读 "
                    + "`GET /catalog/brands`；转发调用方自己的 bearer，租户仍由 catalog "
                    + "按已认证成员关系解析（两道 RLS）；"
                    + "**读不到就显式说读不到**（available=false + 原因），"
                    + "绝不返回空集合冒充「上游一个数据都没有」。"
                    + "落库只存品牌**逻辑 id**（`brand_id`），**零跨库 FK、零影子表**。"),
            new IpRiskLibraryCapabilityGap(
                "16.4.2.1",
                "品牌授权",
                "16.4.2.1 品牌授权",
                true,
                "品牌授权**已交付**（同服务 `brand_authz_*`）但**与本模块是两件事**："
                    + "它回答「有没有被授权」，本模块回答「有没有侵权风险条目」⇒ "
                    + "**零 FK、互不改对方的表**，要关联只用逻辑 id。"),
            new IpRiskLibraryCapabilityGap(
                "16.4.2.2",
                "授权凭证",
                "16.4.2.2 授权凭证",
                true,
                "授权凭证**已交付**（同服务 `brand_cred_*`）但**与本模块是两件事** ⇒ **零 FK**。"),
            new IpRiskLibraryCapabilityGap(
                "4",
                "刊登",
                "04 刊登与内容",
                true,
                "刊登**已交付**（catalog + channel），但**刊登前的侵权检测归 16.4.1.2** ⇒ "
                    + "本模块**不越界**做拦截、不建刊登闸门。"));

    return new IpRiskLibraryCapabilities(
        gaps,
        RIGHT_TYPES,
        RIGHT_TYPE_LABELS,
        RISK_LEVELS,
        RISK_LEVEL_LABELS,
        STATUSES,
        STATUS_LABELS,
        TERM_KINDS,
        TERM_KIND_LABELS,
        SOURCES,
        SOURCE_LABELS,
        RIGHT_TYPE_NOTE,
        RISK_LEVEL_NOTE,
        PLATFORM_NOTE,
        MATCH_FEATURE_NOTE,
        LIFECYCLE_NOTE,
        IMPORT_NOTE,
        UPSTREAM_NOTE,
        DOWNSTREAM_NOTE);
  }

  // ==================================================================
  // 平台目录（读本服务自己的 `platform` 目录 —— 全局码表）
  // ==================================================================

  /** 平台目录里真实存在的平台编码（大写）。 */
  private Set<String> knownPlatformCodes() {
    Set<String> codes = new LinkedHashSet<>();
    for (Record row : sql.fetch("select code from platform order by position, code")) {
      codes.add(row.get("code", String.class));
    }
    return codes;
  }

  /** 适用平台清单（真目录，**不写死平台常量**）。 */
  public List<IpRiskLibraryPlatform> platforms() {
    List<IpRiskLibraryPlatform> items = new ArrayList<>();
    for (Record row :
        sql.fetch("select code, name, support_status from platform order by position, code")) {
      items.add(
          new IpRiskLibraryPlatform(
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("support_status", String.class)));
    }
    return items;
  }

  // ==================================================================
  // 上游：3.3.2 品牌候选（跨服务只读）
  // ==================================================================

  /**
   * 品牌候选（3.3.2，catalog 服务，只读）。
   *
   * <p>🔴 **读不到就说读不到** —— available=false + 人话原因，
   * <b>绝不</b>返回空集合冒充「上游一个品牌都没有」。
   */
  public IpRiskLibraryBrandSet brands(String keyword, String bearer) {
    var set = source.brands(keyword, bearer);
    return new IpRiskLibraryBrandSet(set.available(), set.items(), set.note());
  }

  // ==================================================================
  // 侵权库清单
  // ==================================================================

  /**
   * 侵权库清单（**真分页 + 真筛选**）。
   *
   * @param rightType 权利类型（可空）
   * @param riskLevel 风险等级（可空）
   * @param status 生命周期（可空）
   * @param platformCode 适用平台（可空）—— **多对多的反查入口**
   * @param keyword 关键字（编码 / 标题 / 品牌名 / 特征 / 关键词）
   */
  public IpRiskLibraryEntryPage entries(
      String rightType,
      String riskLevel,
      String status,
      String platformCode,
      String keyword,
      int page,
      int size) {
    UUID tenant = currentTenant();
    int safePage = clampPage(page);
    int safeSize = clampSize(size);

    StringBuilder where = new StringBuilder(" where e.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);

    String rightTypeValue = text(rightType).toUpperCase();
    if (!rightTypeValue.isEmpty()) {
      if (!knownRightType(rightTypeValue)) {
        throw unprocessable("未知的权利类型：" + rightType);
      }
      where.append(" and e.right_type = ?");
      params.add(rightTypeValue);
    }
    String riskLevelValue = text(riskLevel).toUpperCase();
    if (!riskLevelValue.isEmpty()) {
      if (!knownRiskLevel(riskLevelValue)) {
        throw unprocessable("未知的风险等级：" + riskLevel);
      }
      where.append(" and e.risk_level = ?");
      params.add(riskLevelValue);
    }
    String statusValue = text(status).toUpperCase();
    if (!statusValue.isEmpty()) {
      if (!knownStatus(statusValue)) {
        throw unprocessable("未知的生命周期：" + status);
      }
      where.append(" and e.status = ?");
      params.add(statusValue);
    }
    String platformValue = text(platformCode).toUpperCase();
    if (!platformValue.isEmpty()) {
      where.append(
          " and exists (select 1 from ip_risk_entry_platform p"
              + " where p.tenant_id = e.tenant_id and p.entry_id = e.id and p.platform_code = ?)");
      params.add(platformValue);
    }
    String keywordValue = text(keyword);
    if (!keywordValue.isEmpty()) {
      where.append(
          " and (e.entry_code ilike '%' || ? || '%'"
              + " or e.title ilike '%' || ? || '%'"
              + " or e.brand_name ilike '%' || ? || '%'"
              + " or e.match_pattern ilike '%' || ? || '%'"
              + " or exists (select 1 from ip_risk_match_term t"
              + " where t.tenant_id = e.tenant_id and t.entry_id = e.id"
              + " and t.term ilike '%' || ? || '%'))");
      params.add(keywordValue);
      params.add(keywordValue);
      params.add(keywordValue);
      params.add(keywordValue);
      params.add(keywordValue);
    }

    String base = " from ip_risk_entry e";
    long total = count(sql.fetchValue("select count(*)" + base + where, params.toArray()));

    Map<String, Long> riskLevelCounts = new LinkedHashMap<>();
    for (String code : RISK_LEVELS) {
      riskLevelCounts.put(code, 0L);
    }
    Map<String, Long> rightTypeCounts = new LinkedHashMap<>();
    for (String code : RIGHT_TYPES) {
      rightTypeCounts.put(code, 0L);
    }
    long activeCount = 0L;
    long disabledCount = 0L;
    for (Record row :
        sql.fetch(
            "select e.right_type, e.risk_level, e.status, count(*) as c" + base + where
                + " group by e.right_type, e.risk_level, e.status",
            params.toArray())) {
      String type = row.get("right_type", String.class);
      String level = row.get("risk_level", String.class);
      String life = row.get("status", String.class);
      long c = count(row.get("c"));
      rightTypeCounts.merge(type, c, Long::sum);
      riskLevelCounts.merge(level, c, Long::sum);
      if ("ACTIVE".equals(life)) {
        activeCount += c;
      } else {
        disabledCount += c;
      }
    }

    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<Record> rows =
        sql.fetch(
            "select e.*" + base + where + " order by e.created_at desc, e.id desc limit ? offset ?",
            pageParams.toArray());

    List<UUID> ids = new ArrayList<>();
    for (Record row : rows) {
      ids.add(row.get("id", UUID.class));
    }
    Map<UUID, List<String>> platformsByEntry = loadPlatformCodes(tenant, ids);
    Map<UUID, List<String>> termsByEntry = loadMatchTerms(tenant, ids);

    List<IpRiskLibraryEntry> items = new ArrayList<>();
    for (Record row : rows) {
      UUID id = row.get("id", UUID.class);
      items.add(
          toEntry(
              row,
              platformsByEntry.getOrDefault(id, List.of()),
              termsByEntry.getOrDefault(id, List.of())));
    }

    return new IpRiskLibraryEntryPage(
        items,
        total,
        safePage,
        safeSize,
        activeCount,
        disabledCount,
        riskLevelCounts,
        rightTypeCounts,
        "🔴 各风险等级 / 各权利类型的计数覆盖**整个筛选结果集**（不是当前页）；"
            + "「适用平台」是**多对多**筛选 —— 命中该平台的条目**不限于**只登记了它一个平台的条目。");
  }

  /** 一行 → 传输模型。 */
  private IpRiskLibraryEntry toEntry(
      Record row, List<String> platformCodes, List<String> matchTerms) {
    UUID brandId = row.get("brand_id", UUID.class);
    String rightType = row.get("right_type", String.class);
    String riskLevel = row.get("risk_level", String.class);
    String status = row.get("status", String.class);
    String source = row.get("source", String.class);
    return new IpRiskLibraryEntry(
        row.get("id", UUID.class).toString(),
        row.get("entry_code", String.class),
        rightType,
        rightTypeLabel(rightType),
        riskLevel,
        riskLevelLabel(riskLevel),
        row.get("title", String.class),
        row.get("rights_holder", String.class),
        idText(brandId),
        row.get("brand_name", String.class),
        row.get("match_pattern", String.class),
        row.get("note", String.class),
        status,
        statusLabel(status),
        row.get("disable_reason", String.class),
        time(row, "disabled_at"),
        source,
        sourceLabel(source),
        platformCodes,
        matchTerms,
        time(row, "created_at"),
        time(row, "updated_at"));
  }

  /** 批量取一页条目的适用平台编码。 */
  private Map<UUID, List<String>> loadPlatformCodes(UUID tenant, List<UUID> entryIds) {
    Map<UUID, List<String>> result = new LinkedHashMap<>();
    if (entryIds.isEmpty()) {
      return result;
    }
    StringBuilder statement =
        new StringBuilder(
            "select entry_id, platform_code from ip_risk_entry_platform"
                + " where tenant_id = ? and entry_id in (");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    for (int i = 0; i < entryIds.size(); i++) {
      statement.append(i == 0 ? "?" : ",?");
      params.add(entryIds.get(i));
    }
    statement.append(") order by platform_code");
    for (Record row : sql.fetch(statement.toString(), params.toArray())) {
      result
          .computeIfAbsent(row.get("entry_id", UUID.class), k -> new ArrayList<>())
          .add(row.get("platform_code", String.class));
    }
    return result;
  }

  /** 批量取一页条目的匹配特征。 */
  private Map<UUID, List<String>> loadMatchTerms(UUID tenant, List<UUID> entryIds) {
    Map<UUID, List<String>> result = new LinkedHashMap<>();
    if (entryIds.isEmpty()) {
      return result;
    }
    StringBuilder statement =
        new StringBuilder(
            "select entry_id, term from ip_risk_match_term where tenant_id = ? and entry_id in (");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    for (int i = 0; i < entryIds.size(); i++) {
      statement.append(i == 0 ? "?" : ",?");
      params.add(entryIds.get(i));
    }
    statement.append(") order by term");
    for (Record row : sql.fetch(statement.toString(), params.toArray())) {
      result
          .computeIfAbsent(row.get("entry_id", UUID.class), k -> new ArrayList<>())
          .add(row.get("term", String.class));
    }
    return result;
  }

  // ==================================================================
  // 条目详情
  // ==================================================================

  /** 条目详情（条目 + 匹配特征 + 适用平台）。 */
  public IpRiskLibraryEntryDetail entry(String entryId) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(entryId);
    Record row =
        sql.fetchOne(
            "select e.* from ip_risk_entry e where e.tenant_id = ? and e.id = ?", tenant, id);
    if (row == null) {
      throw notFound();
    }
    return detailOf(tenant, row);
  }

  private IpRiskLibraryEntryDetail detailOf(UUID tenant, Record row) {
    UUID id = row.get("id", UUID.class);
    List<IpRiskLibraryMatchTerm> terms = new ArrayList<>();
    for (Record term :
        sql.fetch(
            "select id, entry_id, term, term_kind from ip_risk_match_term"
                + " where tenant_id = ? and entry_id = ? order by term",
            tenant,
            id)) {
      String kind = term.get("term_kind", String.class);
      terms.add(
          new IpRiskLibraryMatchTerm(
              term.get("id", UUID.class).toString(),
              term.get("entry_id", UUID.class).toString(),
              term.get("term", String.class),
              kind,
              termKindLabel(kind)));
    }
    List<IpRiskLibraryPlatformRef> platforms = new ArrayList<>();
    for (Record platform :
        sql.fetch(
            "select p.platform_code, pl.name as platform_name"
                + " from ip_risk_entry_platform p left join platform pl on pl.code = p.platform_code"
                + " where p.tenant_id = ? and p.entry_id = ? order by p.platform_code",
            tenant,
            id)) {
      platforms.add(
          new IpRiskLibraryPlatformRef(
              platform.get("platform_code", String.class),
              platform.get("platform_name", String.class)));
    }
    List<String> platformCodes = new ArrayList<>();
    for (IpRiskLibraryPlatformRef ref : platforms) {
      platformCodes.add(ref.platformCode());
    }
    List<String> termTexts = new ArrayList<>();
    for (IpRiskLibraryMatchTerm term : terms) {
      termTexts.add(term.term());
    }
    return new IpRiskLibraryEntryDetail(
        toEntry(row, platformCodes, termTexts), terms, platforms, MATCH_FEATURE_NOTE);
  }

  // ==================================================================
  // 登记 / 修改
  // ==================================================================

  /**
   * 登记侵权条目（**按条目编码幂等**）。
   *
   * <p>🔴 幂等锚点是 DB 上的 {@code UNIQUE(tenant_id, entry_code)}，
   * <b>不靠「先查后插」的竞态写法</b>：重复登记命中唯一约束 ⇒ 不产生第二条，
   * 既有内容**原样保留**（不覆盖）。
   */
  public IpRiskLibraryEntryCreation createEntry(IpRiskLibraryEntryInput input) {
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    NormalizedInput normalized = normalize(input, knownPlatformCodes());

    UUID id = UUID.randomUUID();
    Object inserted =
        sql.fetchValue(
            """
            insert into ip_risk_entry (id, tenant_id, entry_code, right_type, risk_level, title,
                rights_holder, brand_name, brand_id, match_pattern, note, status, source,
                created_by, created_at, updated_at)
            values (?,?,?,?,?,?,?,?,?,?,?,'ACTIVE','MANUAL',?,now(),now())
            on conflict (tenant_id, entry_code) do nothing
            returning id
            """,
            id,
            tenant,
            normalized.entryCode(),
            normalized.rightType(),
            normalized.riskLevel(),
            normalized.title(),
            normalized.rightsHolder(),
            normalized.brandName(),
            normalized.brandId(),
            normalized.matchPattern(),
            normalized.note(),
            actor);

    if (inserted == null) {
      // 🔴 幂等命中：条目已存在 —— 原样返回，**不覆盖既有内容**。
      Record existing =
          sql.fetchOne(
              "select e.* from ip_risk_entry e where e.tenant_id = ? and e.entry_code = ?",
              tenant,
              normalized.entryCode());
      if (existing == null) {
        throw new IllegalStateException("条目编码命中唯一约束但读不到该行");
      }
      return new IpRiskLibraryEntryCreation(
          detailOf(tenant, existing).entry(),
          false,
          "🔴 条目编码「" + normalized.entryCode() + "」已存在 ⇒ 本次**没有新建**，"
              + "既有内容**原样保留**（登记是幂等的，不覆盖）。");
    }

    writeChildren(tenant, id, normalized.platformCodes(), normalized.matchTerms());
    Record row =
        sql.fetchOne("select e.* from ip_risk_entry e where e.tenant_id = ? and e.id = ?", tenant, id);
    return new IpRiskLibraryEntryCreation(
        detailOf(tenant, row).entry(),
        true,
        "🔴 条目已登记。适用平台是**多对多**，匹配特征是**多值** —— "
            + "本模块**只登记特征，不执行任何比对**（比对归 16.4.1.2，该模块**未交付**）。");
  }

  /**
   * 修改侵权条目（**整体替换**语义）。
   *
   * <p>🔴 <b>条目编码是身份，不可修改</b>：它是批量导入的幂等锚点，
   * 改了会让「同一批重复导入」不再幂等。
   * 🔴 <b>已停用的条目不可改内容</b>（停用后特征冻结）—— 先重新启用再改。
   */
  public IpRiskLibraryEntryDetail updateEntry(String entryId, IpRiskLibraryEntryInput input) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(entryId);
    Record existing =
        sql.fetchOne(
            "select e.* from ip_risk_entry e where e.tenant_id = ? and e.id = ?", tenant, id);
    if (existing == null) {
      throw notFound();
    }
    if (!"ACTIVE".equals(existing.get("status", String.class))) {
      throw unprocessable("侵权条目已停用，匹配特征已冻结；请先重新启用再修改");
    }
    String currentCode = existing.get("entry_code", String.class);
    if (!text(input.entryCode()).isEmpty() && !currentCode.equals(text(input.entryCode()))) {
      throw unprocessable("条目编码是条目的身份，不可修改（它是批量导入的幂等锚点）");
    }

    NormalizedInput normalized = normalize(input, knownPlatformCodes());
    sql.execute(
        """
        update ip_risk_entry
           set right_type = ?, risk_level = ?, title = ?, rights_holder = ?,
               brand_name = ?, brand_id = ?, match_pattern = ?, note = ?, updated_at = now()
         where tenant_id = ? and id = ?
        """,
        normalized.rightType(),
        normalized.riskLevel(),
        normalized.title(),
        normalized.rightsHolder(),
        normalized.brandName(),
        normalized.brandId(),
        normalized.matchPattern(),
        normalized.note(),
        tenant,
        id);

    // 🔴 适用平台与匹配特征**整体替换**（多对多 / 多值都是集合语义）。
    sql.execute("delete from ip_risk_entry_platform where tenant_id = ? and entry_id = ?", tenant, id);
    sql.execute("delete from ip_risk_match_term where tenant_id = ? and entry_id = ?", tenant, id);
    writeChildren(tenant, id, normalized.platformCodes(), normalized.matchTerms());

    Record row =
        sql.fetchOne("select e.* from ip_risk_entry e where e.tenant_id = ? and e.id = ?", tenant, id);
    return detailOf(tenant, row);
  }

  /**
   * 改生命周期（**启用 / 停用**）。
   *
   * <p>🔴 停用**必须写理由**；🔴 重新启用**不抹掉**停用理由与停用时刻（那是历史）。
   */
  public IpRiskLibraryEntryDetail setStatus(String entryId, IpRiskLibraryStatusInput input) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(entryId);
    Record existing =
        sql.fetchOne(
            "select e.* from ip_risk_entry e where e.tenant_id = ? and e.id = ?", tenant, id);
    if (existing == null) {
      throw notFound();
    }
    String status = text(input.status()).toUpperCase();
    if (!knownStatus(status)) {
      throw unprocessable("未知的生命周期：" + input.status());
    }
    if ("DISABLED".equals(status)) {
      String reason = text(input.reason());
      if (reason.isEmpty()) {
        throw unprocessable("停用必须写理由（没有理由的停用等于「随手关掉」，事后没人说得清为什么）");
      }
      sql.execute(
          """
          update ip_risk_entry
             set status = 'DISABLED', disable_reason = ?, disabled_at = now(), updated_at = now()
           where tenant_id = ? and id = ?
          """,
          reason,
          tenant,
          id);
    } else {
      // 🔴 重新启用：**保留** disable_reason / disabled_at 作为历史（不抹掉）。
      sql.execute(
          "update ip_risk_entry set status = 'ACTIVE', updated_at = now()"
              + " where tenant_id = ? and id = ?",
          tenant,
          id);
    }
    Record row =
        sql.fetchOne("select e.* from ip_risk_entry e where e.tenant_id = ? and e.id = ?", tenant, id);
    return detailOf(tenant, row);
  }

  // ==================================================================
  // 批量导入
  // ==================================================================

  /**
   * 批量导入侵权条目。
   *
   * <p>🔴 **逐行提交**：一行失败<b>不回滚</b>已成功的行（逐行 savepoint）。
   * 🔴 **幂等**：条目编码唯一约束兜底，重复导入**不产生重复条目**。
   * 🔴 **行级结果落库**（{@code ip_risk_import_batch} / {@code ip_risk_import_row}），
   * 不是「整个文件成功 / 失败」二选一。
   */
  public IpRiskLibraryImportResult importEntries(IpRiskLibraryImportInput input) {
    UUID tenant = currentTenant();
    UUID actor = currentActor();
    List<IpRiskLibraryImportRowInput> rows =
        input.rows() == null ? List.of() : input.rows();
    if (rows.isEmpty()) {
      throw unprocessable("导入内容为空：至少要有一行");
    }
    if (rows.size() > MAX_IMPORT_ROWS) {
      throw unprocessable("一次最多导入 " + MAX_IMPORT_ROWS + " 行，本次 " + rows.size() + " 行");
    }

    Set<String> knownPlatforms = knownPlatformCodes();
    UUID batchId = UUID.randomUUID();

    List<IpRiskLibraryImportRowResult> results = new ArrayList<>();
    int inserted = 0;
    int skipped = 0;
    int failed = 0;
    int rowNo = 0;
    for (IpRiskLibraryImportRowInput row : rows) {
      rowNo++;
      RowOutcome outcome = importRow(tenant, actor, row, knownPlatforms);
      switch (outcome.outcome()) {
        case "INSERTED" -> inserted++;
        case "SKIPPED" -> skipped++;
        default -> failed++;
      }
      results.add(
          new IpRiskLibraryImportRowResult(
              rowNo,
              text(row.entryCode()),
              outcome.outcome(),
              idText(outcome.entryId()),
              outcome.errorField(),
              outcome.errorMessage()));
    }

    String status = failed == 0 ? "COMPLETED" : "PARTIAL";
    sql.execute(
        """
        insert into ip_risk_import_batch (id, tenant_id, source_name, total_rows,
            inserted_rows, skipped_rows, failed_rows, status, created_by, created_at)
        values (?,?,?,?,?,?,?,?,?,now())
        """,
        batchId,
        tenant,
        text(input.sourceName()),
        rows.size(),
        inserted,
        skipped,
        failed,
        status,
        actor);

    for (IpRiskLibraryImportRowResult result : results) {
      sql.execute(
          """
          insert into ip_risk_import_row (id, tenant_id, batch_id, row_no, entry_code,
              entry_id, outcome, error_field, error_message, created_at)
          values (?,?,?,?,?,?,?,?,?,now())
          """,
          UUID.randomUUID(),
          tenant,
          batchId,
          result.rowNo(),
          result.entryCode(),
          result.entryId() == null ? null : UUID.fromString(result.entryId()),
          result.outcome(),
          result.errorField(),
          result.errorMessage());
    }

    return new IpRiskLibraryImportResult(
        batchId.toString(),
        text(input.sourceName()),
        rows.size(),
        inserted,
        skipped,
        failed,
        status,
        results,
        "🔴 逐行提交：**失败行不回滚已成功的行**。"
            + "🔴 幂等：条目编码已存在的行记为「跳过」，**不产生重复条目**、也不改写既有内容。"
            + "🔴 行级结果（哪一行、哪个字段、为什么）已落库，可随时回查。");
  }

  /** 一行的处理结果。 */
  private record RowOutcome(
      String outcome, UUID entryId, String errorField, String errorMessage) {}

  /**
   * 处理一行导入。
   *
   * <p>🔴 校验不过 ⇒ 直接记 FAILED（不碰 DB）。
   * 🔴 DB 报错 ⇒ **回滚到该行的 savepoint**，只让这一行失败。
   */
  private RowOutcome importRow(
      UUID tenant,
      UUID actor,
      IpRiskLibraryImportRowInput row,
      Set<String> knownPlatforms) {
    List<String> platformCodes = new ArrayList<>();
    if (row.platformCodes() != null) {
      for (String code : row.platformCodes()) {
        String value = text(code).toUpperCase();
        if (!value.isEmpty()) {
          platformCodes.add(value);
        }
      }
    }
    List<RowProblem> problems =
        validateEntryFields(
            row.entryCode(), row.rightType(), row.riskLevel(), row.title(), platformCodes,
            knownPlatforms);
    if (!problems.isEmpty()) {
      RowProblem first = problems.get(0);
      return new RowOutcome("FAILED", null, first.field(), first.message());
    }

    String savepoint = "sp_ip_risk_" + UUID.randomUUID().toString().replace('-', '_');
    sql.execute("savepoint " + savepoint);
    try {
      String entryCode = text(row.entryCode());
      UUID id = UUID.randomUUID();
      Object inserted =
          sql.fetchValue(
              """
              insert into ip_risk_entry (id, tenant_id, entry_code, right_type, risk_level, title,
                  rights_holder, brand_name, brand_id, match_pattern, note, status, source,
                  created_by, created_at, updated_at)
              values (?,?,?,?,?,?,?,?,?,?,?,'ACTIVE','IMPORT',?,now(),now())
              on conflict (tenant_id, entry_code) do nothing
              returning id
              """,
              id,
              tenant,
              entryCode,
              text(row.rightType()).toUpperCase(),
              text(row.riskLevel()).toUpperCase(),
              text(row.title()),
              text(row.rightsHolder()),
              blankToNull(text(row.brandName())),
              parseUuid(row.brandId()),
              blankToNull(text(row.matchPattern())),
              text(row.note()),
              actor);
      if (inserted == null) {
        // 🔴 幂等命中：条目已存在 ⇒ 跳过，**不改写既有内容**。
        Object existing =
            sql.fetchValue(
                "select id from ip_risk_entry where tenant_id = ? and entry_code = ?",
                tenant,
                entryCode);
        sql.execute("release savepoint " + savepoint);
        return new RowOutcome(
            "SKIPPED",
            existing == null ? null : UUID.fromString(String.valueOf(existing)),
            null,
            null);
      }
      writeChildren(tenant, id, platformCodes, row.matchTerms());
      sql.execute("release savepoint " + savepoint);
      return new RowOutcome("INSERTED", id, null, null);
    } catch (RuntimeException e) {
      // 🔴 只回滚**这一行** —— 已成功的行必须留下。
      try {
        sql.execute("rollback to savepoint " + savepoint);
        sql.execute("release savepoint " + savepoint);
      } catch (RuntimeException ignored) {
        // 回滚本身失败时，外层事务会一起失败 —— 不会留下半截数据。
      }
      return new RowOutcome("FAILED", null, "row", shortMessage(e));
    }
  }

  /** 导入批次清单（**真分页**）。 */
  public IpRiskLibraryImportPage imports(int page, int size) {
    UUID tenant = currentTenant();
    int safePage = clampPage(page);
    int safeSize = clampSize(size);
    long total =
        count(
            sql.fetchValue(
                "select count(*) from ip_risk_import_batch where tenant_id = ?", tenant));
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    params.add(safeSize);
    params.add(safePage * safeSize);
    List<IpRiskLibraryImportBatch> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, source_name, total_rows, inserted_rows, skipped_rows, failed_rows,"
                + " status, created_at from ip_risk_import_batch"
                + " where tenant_id = ? order by created_at desc, id desc limit ? offset ?",
            params.toArray())) {
      items.add(toBatch(row));
    }
    return new IpRiskLibraryImportPage(
        items,
        total,
        safePage,
        safeSize,
        "🔴 导入批次与逐行结果**append-only**（事后改写行级结果等于把导入记录做成假的）。");
  }

  /** 导入批次详情（批次 + 逐行结果）。 */
  public IpRiskLibraryImportDetail importBatch(String batchId) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(batchId);
    Record row =
        sql.fetchOne(
            "select id, source_name, total_rows, inserted_rows, skipped_rows, failed_rows,"
                + " status, created_at from ip_risk_import_batch where tenant_id = ? and id = ?",
            tenant,
            id);
    if (row == null) {
      throw notFound();
    }
    List<IpRiskLibraryImportRowResult> rows = new ArrayList<>();
    for (Record line :
        sql.fetch(
            "select row_no, entry_code, entry_id, outcome, error_field, error_message"
                + " from ip_risk_import_row where tenant_id = ? and batch_id = ? order by row_no",
            tenant,
            id)) {
      UUID entryId = line.get("entry_id", UUID.class);
      rows.add(
          new IpRiskLibraryImportRowResult(
              line.get("row_no", Integer.class),
              line.get("entry_code", String.class),
              line.get("outcome", String.class),
              idText(entryId),
              line.get("error_field", String.class),
              line.get("error_message", String.class)));
    }
    return new IpRiskLibraryImportDetail(
        toBatch(row), rows, "🔴 逐行结果（哪一行、哪个字段、为什么）是这次导入的**证据**，不可改写。");
  }

  private IpRiskLibraryImportBatch toBatch(Record row) {
    return new IpRiskLibraryImportBatch(
        row.get("id", UUID.class).toString(),
        row.get("source_name", String.class),
        intOf(row, "total_rows"),
        intOf(row, "inserted_rows"),
        intOf(row, "skipped_rows"),
        intOf(row, "failed_rows"),
        row.get("status", String.class),
        time(row, "created_at"));
  }

  // ==================================================================
  // 平台汇总（**「这个平台上有哪些高风险条目」**的反查入口）
  // ==================================================================

  /** 平台上的侵权风险汇总。 */
  public IpRiskLibraryPlatformSummaryPage platformSummary() {
    UUID tenant = currentTenant();
    List<IpRiskLibraryPlatformSummary> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select p.code as platform_code, p.name as platform_name,
                   count(e.id) filter (where e.status = 'ACTIVE') as total_count,
                   count(e.id) filter (where e.status = 'ACTIVE' and e.risk_level = 'HIGH') as high_count,
                   count(e.id) filter (where e.status = 'ACTIVE' and e.risk_level = 'MEDIUM') as medium_count,
                   count(e.id) filter (where e.status = 'ACTIVE' and e.risk_level = 'LOW') as low_count,
                   count(e.id) filter (where e.status = 'DISABLED') as disabled_count
              from platform p
              left join ip_risk_entry_platform ep
                on ep.platform_code = p.code and ep.tenant_id = ?
              left join ip_risk_entry e
                on e.tenant_id = ep.tenant_id and e.id = ep.entry_id
             group by p.code, p.name, p.position
             order by p.position, p.code
            """,
            tenant)) {
      items.add(
          new IpRiskLibraryPlatformSummary(
              row.get("platform_code", String.class),
              row.get("platform_name", String.class),
              count(row.get("total_count")),
              count(row.get("high_count")),
              count(row.get("medium_count")),
              count(row.get("low_count")),
              count(row.get("disabled_count")),
              "🔴 计数只统计**启用中**的条目（停用的不再参与未来的检测，但**历史记录保留**）。"));
    }
    return new IpRiskLibraryPlatformSummaryPage(
        items, "🔴 适用平台是**多对多** —— 同一条条目会出现在它登记过的每个平台上。");
  }

  // ==================================================================
  // 内部：归一化与写入
  // ==================================================================

  /** 归一化后的一条（**所有校验都在这里做**，DB 只是兜底）。 */
  private record NormalizedInput(
      String entryCode,
      String rightType,
      String riskLevel,
      String title,
      String rightsHolder,
      String brandName,
      UUID brandId,
      String matchPattern,
      String note,
      List<String> platformCodes,
      List<IpRiskLibraryTermInput> matchTerms) {}

  private NormalizedInput normalize(IpRiskLibraryEntryInput input, Set<String> knownPlatforms) {
    String entryCode = text(input.entryCode());
    if (entryCode.isEmpty()) {
      throw unprocessable("条目编码不能为空（它是租户内稳定业务键，也是批量导入的幂等锚点）");
    }
    String rightType = text(input.rightType()).toUpperCase();
    if (!knownRightType(rightType)) {
      throw unprocessable(
          "未知的权利类型："
              + (text(input.rightType()).isEmpty() ? "(空)" : input.rightType())
              + "（只能是 TRADEMARK / PATENT / COPYRIGHT / IMAGE_RIGHT）");
    }
    String riskLevel = text(input.riskLevel()).toUpperCase();
    if (!knownRiskLevel(riskLevel)) {
      throw unprocessable(
          "未知的风险等级："
              + (text(input.riskLevel()).isEmpty() ? "(空)" : input.riskLevel())
              + "（只能是 HIGH / MEDIUM / LOW）");
    }
    String title = text(input.title());
    if (title.isEmpty()) {
      throw unprocessable("条目标题不能为空（它是这条权利的人话名）");
    }
    List<String> platformCodes = new ArrayList<>();
    if (input.platformCodes() != null) {
      for (String code : input.platformCodes()) {
        String value = text(code).toUpperCase();
        if (value.isEmpty()) {
          continue;
        }
        if (!knownPlatforms.contains(value)) {
          throw unprocessable("未知的适用平台：" + value + "（平台目录里没有这个平台）");
        }
        if (!platformCodes.contains(value)) {
          platformCodes.add(value);
        }
      }
    }
    UUID brandId = parseUuid(input.brandId());
    if (!text(input.brandId()).isEmpty() && brandId == null) {
      throw unprocessable("品牌 id 不是合法 UUID");
    }
    List<IpRiskLibraryTermInput> terms = new ArrayList<>();
    if (input.matchTerms() != null) {
      for (IpRiskLibraryTermInput term : input.matchTerms()) {
        String value = text(term.term());
        if (value.isEmpty()) {
          continue;
        }
        String kind = text(term.termKind()).toUpperCase();
        if (kind.isEmpty()) {
          kind = "KEYWORD";
        }
        if (!knownTermKind(kind)) {
          throw unprocessable("未知的匹配特征分类：" + term.termKind() + "（只能是 KEYWORD / PHRASE）");
        }
        terms.add(new IpRiskLibraryTermInput(value, kind));
      }
    }
    return new NormalizedInput(
        entryCode,
        rightType,
        riskLevel,
        title,
        text(input.rightsHolder()),
        blankToNull(text(input.brandName())),
        brandId,
        blankToNull(text(input.matchPattern())),
        text(input.note()),
        platformCodes,
        terms);
  }

  /** 写子表：适用平台（多对多）+ 匹配特征（多值）。 */
  private void writeChildren(
      UUID tenant, UUID entryId, List<String> platformCodes, List<IpRiskLibraryTermInput> terms) {
    for (String code : platformCodes) {
      sql.execute(
          """
          insert into ip_risk_entry_platform (id, tenant_id, entry_id, platform_code, created_at)
          values (?,?,?,?,now())
          on conflict (tenant_id, entry_id, platform_code) do nothing
          """,
          UUID.randomUUID(),
          tenant,
          entryId,
          code);
    }
    for (IpRiskLibraryTermInput term : terms) {
      sql.execute(
          """
          insert into ip_risk_match_term (id, tenant_id, entry_id, term, term_kind, created_at)
          values (?,?,?,?,?,now())
          on conflict (tenant_id, entry_id, term) do nothing
          """,
          UUID.randomUUID(),
          tenant,
          entryId,
          term.term(),
          term.termKind());
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  /** 把异常压成一行人话（导入的行级错误要能直接给人看）。 */
  private static String shortMessage(RuntimeException e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      return e.getClass().getSimpleName();
    }
    int cut = message.indexOf('\n');
    String head = cut > 0 ? message.substring(0, cut) : message;
    return head.length() > 480 ? head.substring(0, 480) : head;
  }
}
