package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.4.3.1 多语言内容：SPU/SKU 的多语言名称与描述（{@code spu_translation} / {@code sku_translation}）、
 * 租户语言需求（人工维护的一侧）、AI 翻译建议与采纳流程、翻译完整度。
 *
 * <p>蓝图原文：「职责：维护 SPU/SKU 的多语言名称与描述（`spu_translation` `sku_translation`）， 按语言唯一，支持人工校对与 AI
 * 翻译的建议采纳流程。」 「输入：翻译文本（人工/AI 建议）；语言列表（由店铺启用的站点决定实际需要哪些语言）。」 「输出：各语言的名称与描述记录 + 翻译状态（待翻/已翻/已校对）。」 「下游
 * = 04 刊登填充。**需要哪些语言由店铺启用站点推导**，不靠人肉记忆。」
 *
 * <p>本类的纪律：
 *
 * <ol>
 *   <li><b>「待翻」是一等状态而不是「行不存在」。</b>否则「这个商品的德语还没翻」只能靠 「查哪些行不存在」得到，完整度报表也无从汇总。V22 用三条 CHECK 把三态互斥的形状
 *       钉死，这里是同口径的应用层校验。
 *   <li><b>只有 {@code REVIEWED} 可刊登。</b>「已翻」是机器译文，拿去发平台等于没做校对 ——所以 {@link #coverage} 把 publishable 与
 *       status 分开报，04 刊登只读 publishable。
 *   <li><b>语言需求不从站点编。</b>站点推导走 {@link TranslationLocaleProjectionPort}， 未接入时 {@link
 *       #requirementView()} 返回 available=false 并写明归 02 渠道，
 *       而不是回一个空语言列表（空列表会被读成「不需要做多语言」——本模块最危险的错误方向）。
 *   <li><b>建议处置是终态。</b>采纳错了直接改译文，不把建议改回待处置再走一遍 ——否则同一条建议两次采纳产出两条不同译文历史，无从判断哪次算数。
 * </ol>
 *
 * <p>权限口径：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。 译文挂在商品上、与 3.4.2.2 同为商品文案，所以沿用
 * spu 词表 （不新造——新词表要改 identity 服务的迁移，属于另一个服务的写入所有权）。
 *
 * <p>批量操作在 {@link TranslationBatchService}，同一套纪律。
 */
@Service
public class TranslationService {
  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** CSV 导出的分批大小：峰值内存与结果集大小无关。 */
  private static final int CSV_CHUNK = 500;

  private final DSLContext sql;
  private final TranslationLocaleProjectionPort sitePort;

  public TranslationService(DSLContext sql, TranslationLocaleProjectionPort sitePort) {
    this.sql = sql;
    this.sitePort = sitePort;
  }

  // ============================================================ 表名与主体的映射

  /**
   * 译文表名。由已校验的 {@code targetType} 决定，**不是用户输入**， 所以拼进 SQL 不构成注入面（与 {@link DescriptionService} 用
   * enum 分派同一判断）。
   *
   * <p>分两张表而不是一张多态表的理由写在 V22 文件头：合表就没法建复合外键， 「译文必须挂在一个真实存在的商品上」只能降级成触发器。
   */
  static String tableOf(String targetType) {
    return "SKU".equals(MediaRules.normalizeUpper(targetType))
        ? "sku_translation"
        : "spu_translation";
  }

  /** 译文表上的外键列名。 */
  static String subjectColumnOf(String targetType) {
    return "SKU".equals(MediaRules.normalizeUpper(targetType)) ? "sku_id" : "spu_id";
  }

  // ============================================================ 概览

  /**
   * 多语言内容概览。
   *
   * <p>{@code pendingTranslationCount} 是这个模块唯一需要每天盯的数字： 语言需求推导出来之后，待翻积压直接决定 04 刊登能不能上新。
   */
  public TranslationOverview overview() {
    UUID tenantId = currentTenantId();
    var locales =
        sql.fetchOne(
            "select count(*) as total,"
                + "count(*) filter (where enabled) as enabled_count,"
                + "count(*) filter (where source='MANUAL') as manual_count,"
                + "count(*) filter (where source='SHOP_SITE') as site_count"
                + " from translation_locale where tenant_id=?",
            tenantId);
    var spuRows = statusCounts("spu_translation", tenantId);
    var skuRows = statusCounts("sku_translation", tenantId);
    var suggestions =
        sql.fetchOne(
            "select count(*) filter (where status='PENDING') as pending_count,"
                + "count(*) filter (where status='ADOPTED') as adopted_count,"
                + "count(*) filter (where status='REJECTED') as rejected_count"
                + " from translation_suggestion where tenant_id=?",
            tenantId);
    long batchCount =
        sql.fetchOne("select count(*) from translation_batch where tenant_id=?", tenantId)
            .get(0, Long.class);
    var site = sitePort.derivedLocales(tenantId);
    long spuTotal = spuRows[0], skuTotal = skuRows[0];
    long pending = spuRows[1] + skuRows[1];
    long translated = spuRows[2] + skuRows[2];
    long reviewed = spuRows[3] + skuRows[3];
    return new TranslationOverview(
        locales.get("total", Long.class),
        locales.get("enabled_count", Long.class),
        locales.get("manual_count", Long.class),
        locales.get("site_count", Long.class),
        site.available(),
        spuTotal,
        skuTotal,
        pending,
        translated,
        reviewed,
        reviewed,
        suggestions.get("pending_count", Long.class),
        suggestions.get("adopted_count", Long.class),
        suggestions.get("rejected_count", Long.class),
        batchCount,
        "待翻（pending）写成「已经登记了这种语言、但还没有文本」，不是「查不到行」："
            + "只有已校对（REVIEWED）才可刊登，「已翻」是机器译文，发到平台上等于没做校对这一步。"
            + "需要哪些语言由店铺启用站点推导（02 渠道），未接入前用「人工维护」登记。");
  }

  /** 单表按状态统计：[总数, 待翻, 已翻, 已校对]。表名来自本类的常量，不含用户输入。 */
  private long[] statusCounts(String table, UUID tenantId) {
    var r =
        sql.fetchOne(
            "select count(*) as total,"
                + "count(*) filter (where status='PENDING') as pending,"
                + "count(*) filter (where status='TRANSLATED') as translated,"
                + "count(*) filter (where status='REVIEWED') as reviewed"
                + " from "
                + table
                + " where tenant_id=?",
            tenantId);
    return new long[] {
      r.get("total", Long.class),
      r.get("pending", Long.class),
      r.get("translated", Long.class),
      r.get("reviewed", Long.class)
    };
  }

  // ============================================================ 语言需求（人工维护的一侧）

  /** 语言需求列表：分页 + 关键词 + 来源 + 启用状态。每行自带该语言的译文覆盖统计。 */
  public TranslationLocalePage locales(TranslationLocaleQuery query) {
    int page = Math.max(0, query.page());
    int size = clampSize(query.size());
    StringBuilder where = new StringBuilder(" where tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenantId());
    String keyword = MediaRules.normalize(query.keyword());
    if (!keyword.isEmpty()) {
      where.append(" and (lower(locale) like ? escape '!' or lower(locale_name) like ? escape '!'");
      binds.add(escapeLike(keyword.toLowerCase(Locale.ROOT)));
      binds.add(escapeLike(keyword.toLowerCase(Locale.ROOT)));
      where.append(')');
    }
    String source = MediaRules.normalizeUpper(query.source());
    if (!source.isEmpty()) {
      check(
          TranslationRules.localeSourceProblem(source) == null,
          TranslationRules.localeSourceProblem(source));
      where.append(" and source=?");
      binds.add(source);
    }
    String enabled = MediaRules.normalize(query.enabled());
    if (!enabled.isEmpty()) {
      where.append(" and enabled=?");
      binds.add(Boolean.valueOf("true".equalsIgnoreCase(enabled) || "1".equals(enabled)));
    }
    long total =
        sql.fetchOne("select count(*) from translation_locale" + where, binds.toArray())
            .get(0, Long.class);
    if (total == 0) return new TranslationLocalePage(List.of(), page, size, 0, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);
    var rows =
        sql.fetch(
            "select id,locale,locale_name,source,enabled,note,version,created_at,updated_at"
                + " from translation_locale"
                + where
                + " order by locale limit ? offset ?",
            pageBinds.toArray());
    Map<String, long[]> coverage = localeCoverage();
    List<TranslationLocaleRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) {
      String locale = r.get("locale", String.class);
      long[] stat = coverage.getOrDefault(locale, new long[4]);
      items.add(localeRow(r, stat));
    }
    return new TranslationLocalePage(
        items, page, size, total, (int) Math.ceil(total / (double) size));
  }

  /** 单条语言需求。 */
  public TranslationLocaleRow locale(UUID id) {
    var r =
        sql.fetchOne(
            "select id,locale,locale_name,source,enabled,note,version,created_at,updated_at"
                + " from translation_locale where tenant_id=? and id=?",
            currentTenantId(),
            id);
    if (r == null) throw notFound("语言需求不存在");
    String locale = r.get("locale", String.class);
    return localeRow(r, localeCoverage().getOrDefault(locale, new long[4]));
  }

  private TranslationLocaleRow localeRow(Record r, long[] stat) {
    String source = r.get("source", String.class);
    return new TranslationLocaleRow(
        r.get("id", UUID.class),
        r.get("locale", String.class),
        r.get("locale_name", String.class),
        source,
        TranslationRules.localeSourceLabel(source),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("note", String.class),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)),
        stat[0],
        stat[1],
        stat[2],
        stat[3]);
  }

  /**
   * 建语言需求或改语言需求（id 为空即新建）。
   *
   * <p>语言标签**建后不可改**：译文行的键是 {@code (主体, locale)}，改标签等于把一个商品上 所有同语言译文一次性变成「非需求语言」，而没人会想到去重新校对。
   */
  public TranslationLocaleSaved saveLocale(
      AccessService.Access actor, UUID id, TranslationLocaleInput input) {
    UUID tenantId = actor.tenantId();
    String locale = TranslationRules.normalizeLocale(input.locale());
    String problem = TranslationRules.localeProblem(locale);
    check(problem == null, problem);
    String localeName = MediaRules.normalize(input.localeName());
    problem = TranslationRules.localeNameProblem(localeName);
    check(problem == null, problem);
    boolean enabled = input.enabled() == null || input.enabled();
    String note = MediaRules.normalize(input.note());
    check(note.length() <= 500, "备注最多 500 个字符");
    if (id == null) {
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into translation_locale(id,tenant_id,locale,locale_name,source,enabled,note,version)"
                + " values(?,?,?,?,'MANUAL',?,?,0)",
            newId,
            tenantId,
            locale,
            localeName,
            enabled,
            note);
      } catch (DataAccessException e) {
        throw conflict(
            "语言「"
                + locale
                + "」已经在语言需求里了（"
                + rootMessage(e)
                + "）：同一租户同一种语言只能有一条需求，两条时「我们到底需不需要这种语言」无法回答，"
                + "而完整度报表会把它算成两个语言");
      }
      audit(actor, newId, "TranslationLocaleCreated");
      return new TranslationLocaleSaved(locale(newId), true, "语言需求已建");
    }
    var current =
        sql.fetchOne(
            "select locale,version from translation_locale where tenant_id=? and id=?",
            tenantId,
            id);
    if (current == null) throw notFound("语言需求不存在");
    checkVersion(current.get("version", Long.class), input.version());
    String currentLocale = current.get("locale", String.class);
    check(
        currentLocale.equals(locale),
        "语言标签建后不可改：这行需求的标签是它下面所有商品译文主键的一部分，"
            + "改标签等于把「"
            + currentLocale
            + "」的译文一次性变成非需求语言而没人会想到去重新校对。要换语言请新建一条需求并逐个迁移译文");
    int affected =
        sql.execute(
            "update translation_locale set locale_name=?,enabled=?,note=?,version=version+1,"
                + "updated_at=now() where tenant_id=? and id=? and version=?",
            localeName,
            enabled,
            note,
            tenantId,
            id,
            input.version());
    check(affected == 1, "这条语言需求刚被别人改过（版本不是 " + input.version() + "），请刷新后重试");
    audit(actor, id, "TranslationLocaleUpdated");
    return new TranslationLocaleSaved(locale(id), false, "语言需求已更新");
  }

  /** 停用/启用语言需求。停用后不再出现在批量操作的默认语言里，已建的译文照常可读。 */
  public TranslationLocaleRow setLocaleEnabled(
      AccessService.Access actor, UUID id, boolean enabled) {
    int affected =
        sql.execute(
            "update translation_locale set enabled=?,version=version+1,updated_at=now()"
                + " where tenant_id=? and id=?",
            enabled,
            actor.tenantId(),
            id);
    if (affected == 0) throw notFound("语言需求不存在");
    audit(actor, id, enabled ? "TranslationLocaleEnabled" : "TranslationLocaleDisabled");
    return locale(id);
  }

  /** 删除语言需求。仍被商品译文使用时由 V22 触发器拒绝并说明被用了几条。 */
  public void deleteLocale(AccessService.Access actor, UUID id, long version) {
    var current =
        sql.fetchOne(
            "select version from translation_locale where tenant_id=? and id=?",
            actor.tenantId(),
            id);
    if (current == null) throw notFound("语言需求不存在");
    checkVersion(current.get("version", Long.class), version);
    try {
      sql.execute(
          "delete from translation_locale where tenant_id=? and id=? and version=?",
          actor.tenantId(),
          id,
          version);
    } catch (DataAccessException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, rootMessage(e));
    }
    audit(actor, id, "TranslationLocaleDeleted");
  }

  // ============================================================ 「需要哪些语言」：人工维护 ∪ 站点推导

  /**
   * 合并视图：租户需要哪些语言（人工维护 ∪ 店铺启用站点推导）。
   *
   * <p>蓝图硬要求「需要哪些语言由店铺启用站点推导，不靠人肉记忆」。站点是 02 渠道的事实， catalog 禁止跨服务 SQL，所以推导走 {@link
   * TranslationLocaleProjectionPort}。
   *
   * <p><b>未接入时返回 {@code siteLocaleAvailable=false} 且 {@code siteNote} 写明归 02 渠道</b>， {@code
   * requirements} 里 {@code fromSite} 一条都没有——空的站点推导列表会被读成 「这个租户不需要多语言」，那是本模块最危险的错误方向。
   */
  public TranslationRequirementView requirementView() {
    UUID tenantId = currentTenantId();
    var manual =
        sql.fetch(
            "select locale,locale_name,enabled,note,source from translation_locale"
                + " where tenant_id=? order by locale",
            tenantId);
    var site = sitePort.derivedLocales(tenantId);
    Map<String, long[]> coverage = localeCoverage();

    Map<String, TranslationRequirementRow> merged = new LinkedHashMap<>();
    for (Record r : manual) {
      String locale = r.get("locale", String.class);
      merged.put(
          locale,
          requirementRow(
              locale,
              r.get("locale_name", String.class),
              true,
              false,
              0,
              0,
              Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
              r.get("note", String.class),
              coverage));
    }
    int derivedCount = 0;
    // 未接入时端口的 rows 一定是空的（端口契约如此），这里再挡一层：
    // 即使将来有人写了个会编数据的适配器，available=false 也不允许把它的结果混进视图。
    if (site.available()) {
      for (TranslationLocaleProjectionPort.DerivedLocale d : site.rows()) {
        String locale = TranslationRules.normalizeLocale(d.locale());
        if (locale.isEmpty()) continue;
        derivedCount++;
        TranslationRequirementRow existing = merged.get(locale);
        merged.put(
            locale,
            requirementRow(
                locale,
                d.localeName() == null || d.localeName().isBlank()
                    ? (existing == null ? "" : existing.localeName())
                    : d.localeName(),
                existing != null,
                true,
                d.shopCount(),
                d.marketplaceCount(),
                existing == null || existing.enabled(),
                existing == null ? "由店铺启用站点推导" : existing.note(),
                coverage));
      }
    }
    return new TranslationRequirementView(
        site.available(),
        site.note(),
        List.copyOf(merged.values()),
        manual.size(),
        derivedCount,
        "「人工维护」是现在就能真实写入的一侧；「店铺站点推导」的归属是 02 渠道。"
            + "需要哪些语言最终由店铺启用的站点决定，而不是人肉记忆——站点侧接入后本视图会自动多出那一栏。");
  }

  /**
   * 从店铺启用站点同步语言需求。
   *
   * <p>02 渠道未接入时返回 409 并写明归 02 渠道，**不写入任何行**——编一份「推导结果」写进 语言需求表，会让「人工维护」与「站点推导」两个来源在数据上分不开，
   * 而那正是本模块要消除的东西。
   */
  public TranslationRequirementView syncFromSites(AccessService.Access actor) {
    UUID tenantId = actor.tenantId();
    var site = sitePort.derivedLocales(tenantId);
    if (!site.available()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "店铺启用站点 → 语言的推导尚未接入：站点与市场是 02 渠道的事实"
              + "（shop / shop_marketplace / marketplace.default_locale），"
              + "catalog 禁止跨服务 SQL 访问，所以这里拿不到权威数据。本次未写入任何语言需求——"
              + "请改用「人工维护」登记租户实际需要的语言，等 02 渠道接入后本接口会自动可用。");
    }
    for (TranslationLocaleProjectionPort.DerivedLocale d : site.rows()) {
      String locale = TranslationRules.normalizeLocale(d.locale());
      if (locale.isEmpty()) continue;
      sql.execute(
          "insert into translation_locale(id,tenant_id,locale,locale_name,source,enabled,note,version)"
              + " values(?,?,?,?,'SHOP_SITE',true,?,0)"
              + " on conflict (tenant_id,locale) do update set source='SHOP_SITE',"
              + "locale_name=case when excluded.locale_name<>'' then excluded.locale_name"
              + " else translation_locale.locale_name end,updated_at=now()",
          Ids.next(),
          tenantId,
          locale,
          MediaRules.normalize(d.localeName()),
          "由店铺启用站点推导");
    }
    audit(actor, Ids.next(), "TranslationLocalesSyncedFromSites");
    return requirementView();
  }

  // ============================================================ 商品工作台

  /**
   * 某个 SPU / SKU 的多语言工作台视图。
   *
   * <p>把「需要哪些语言」（租户语言需求）与「翻到什么程度」（译文状态）在**同一行**对上， 页面不必自己拼两张表再对齐——而「这个商品还差哪几种语言」正是运营唯一要问的问题。
   *
   * <p>租户语言需求之外的译文也会显示，但 {@code required=false}：有人手工翻了一条不在需求里的
   * 语言是有价值的（它可能对应一个刚开的站点），静默隐藏会让运营以为那条译文不存在。
   */
  public TranslationProductView productView(String targetType, UUID targetId) {
    String type = checkedType(targetType);
    Map<String, Object> product = loadProduct(type, targetId);
    Map<String, TranslationProductRow> rows = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select locale,locale_name from translation_locale where tenant_id=? and enabled"
                + " order by locale",
            currentTenantId()))
      rows.put(
          r.get("locale", String.class),
          new TranslationProductRow(
              r.get("locale", String.class),
              r.get("locale_name", String.class),
              true,
              null,
              null,
              null,
              null,
              false));
    for (Record r :
        sql.fetch(
            "select * from "
                + tableOf(type)
                + " where tenant_id=? and "
                + subjectColumnOf(type)
                + "=?",
            currentTenantId(),
            targetId)) {
      String locale = r.get("locale", String.class);
      String status = r.get("status", String.class);
      TranslationProductRow existing = rows.get(locale);
      rows.put(
          locale,
          new TranslationProductRow(
              locale,
              existing == null ? "" : existing.localeName(),
              existing != null,
              status,
              TranslationRules.statusLabel(status),
              r.get("id", UUID.class),
              r.get("name", String.class),
              "REVIEWED".equals(status)));
    }
    long pendingSuggestions =
        sql.fetchOne(
                "select count(*) from translation_suggestion where tenant_id=? and status='PENDING'"
                    + " and "
                    + subjectColumnOf(type)
                    + "=?",
                currentTenantId(),
                targetId)
            .get(0, Long.class);
    var site = sitePort.derivedLocales(currentTenantId());
    return new TranslationProductView(
        type,
        targetId,
        (String) product.get("code"),
        (String) product.get("name"),
        List.copyOf(rows.values()),
        pendingSuggestions,
        site.available(),
        site.note(),
        "只有「已校对」的译文能刊登：「已翻」是机器译文或刚填的草稿，发到平台上等于没做校对这一步。" + "不在这行里的译文标成「非需求语言」，它们照常可读，不会被刊登取用。");
  }

  // ============================================================ 译文列表与详情

  /**
   * 译文列表：分页 + 关键词 + 状态 + 语言 + 主体类型 + 具体主体。
   *
   * <p>两张表用 {@code UNION ALL} 一次查完再排序分页——在 Java 里合并两页再切片会造出 「总数对不上、且翻页会漏行或重行」的假分页。
   */
  public TranslationEntryPage entries(TranslationEntryQuery query) {
    int page = Math.max(0, query.page());
    int size = clampSize(query.size());
    Filters filter = entryFilter(query);
    long total = countEntries(filter);
    if (total == 0) return new TranslationEntryPage(List.of(), page, size, 0, 0);
    List<Record> rows = selectEntries(filter, size, (long) page * size);
    List<TranslationEntry> items = new ArrayList<>(rows.size());
    for (Record r : rows) items.add(toEntry(r, r.get("target_type", String.class)));
    return new TranslationEntryPage(
        items, page, size, total, (int) Math.ceil(total / (double) size));
  }

  /** 单条译文详情。 */
  public TranslationEntry entry(UUID id) {
    LocatedEntry located = locateEntry(id);
    return toEntry(located.row(), located.type());
  }

  /**
   * 建译文或改译文。
   *
   * <p>语言**不可改**（{@link TranslationEntryEdit} 里没有 locale 字段）：唯一性主键是 {@code (主体,
   * locale)}，「把德语译文改成法语」不是编辑而是删一条建一条。
   */
  public TranslationEntry saveEntry(
      AccessService.Access actor, String targetType, UUID targetId, UUID id, Object input) {
    String type = checkedType(targetType);
    UUID tenantId = actor.tenantId();
    String locale;
    String name;
    String description;
    String origin;
    String status;
    long version;
    if (input instanceof TranslationEntryInput create) {
      locale = TranslationRules.normalizeLocale(create.locale());
      name = MediaRules.normalize(create.name());
      description = MediaRules.normalize(create.description());
      origin = MediaRules.normalizeUpper(create.origin());
      status = MediaRules.normalizeUpper(create.status());
      version = 0;
    } else if (input instanceof TranslationEntryEdit edit) {
      check(id != null, "改译文必须给译文 id");
      var current =
          sql.fetchOne(
              "select * from " + tableOf(type) + " where tenant_id=? and id=?", tenantId, id);
      if (current == null) throw notFound("译文不存在");
      locale = current.get("locale", String.class);
      name = MediaRules.normalize(edit.name());
      description = MediaRules.normalize(edit.description());
      origin = MediaRules.normalizeUpper(edit.origin());
      status = MediaRules.normalizeUpper(edit.status());
      version = edit.version();
    } else {
      throw new IllegalArgumentException("未知的译文写入参数类型");
    }

    String problem = TranslationRules.localeProblem(locale);
    check(problem == null, problem);
    problem = TranslationRules.nameProblem(name);
    check(problem == null, problem);
    problem = TranslationRules.descriptionProblem(description);
    check(problem == null, problem);
    if (origin.isEmpty()) origin = "MANUAL";
    problem = TranslationRules.originProblem(origin);
    check(problem == null, problem);
    if (status.isEmpty())
      status = name.isEmpty() && description.isEmpty() ? "PENDING" : "TRANSLATED";
    problem = TranslationRules.statusProblem(status);
    check(problem == null, problem);
    problem = TranslationRules.translationTextProblem(status, name, description);
    check(problem == null, problem);

    String table = tableOf(type);
    String column = subjectColumnOf(type);
    boolean reviewed = "REVIEWED".equals(status);
    boolean hasText = !name.isEmpty() || !description.isEmpty();
    OffsetDateTime now = utcNow();

    if (id == null) {
      // 主体必须真实存在：复合外键会兜住，但先给一句人话。
      if (targetId == null) throw MediaRules.bad("新建译文必须给商品 id");
      loadProduct(type, targetId);
      UUID newId = Ids.next();
      try {
        sql.execute(
            "insert into "
                + table
                + "(id,tenant_id,"
                + column
                + ",locale,name,description,origin,status,reviewed_by,reviewed_at,version,"
                + "translated_at) values(?,?,?,?,?,?,?,?,?,?::timestamptz,0,?::timestamptz)",
            newId,
            tenantId,
            targetId,
            locale,
            name,
            description,
            origin,
            status,
            reviewed ? actor.userId() : null,
            reviewed ? now : null,
            hasText ? now : null);
      } catch (DataAccessException e) {
        throw conflict(
            "这个商品已经有语言「"
                + locale
                + "」的译文了（"
                + rootMessage(e)
                + "）：蓝图要求「按语言唯一」——两条时「这条商品的这个语言的名称是哪个」无法回答，"
                + "而 04 刊登按 (商品, 语言) 取一条");
      }
      audit(actor, newId, "TranslationEntryCreated");
      return entry(newId);
    }

    var current =
        sql.fetchOne("select * from " + table + " where tenant_id=? and id=?", tenantId, id);
    if (current == null) throw notFound("译文不存在");
    checkVersion(current.get("version", Long.class), version);
    problem =
        TranslationRules.statusTransitionProblem(
            current.get("status", String.class), status, name, description);
    check(problem == null, problem);

    int affected =
        sql.execute(
            "update "
                + table
                + " set name=?,description=?,origin=?,status=?,reviewed_by=?,reviewed_at=?::timestamptz,"
                + "version=version+1,updated_at=now(),translated_at=coalesce(?::timestamptz,translated_at)"
                + " where tenant_id=? and id=? and version=?",
            name,
            description,
            origin,
            status,
            reviewed ? actor.userId() : null,
            reviewed ? now : null,
            hasText ? now : null,
            tenantId,
            id,
            version);
    check(affected == 1, "这条译文刚被别人改过（版本不是 " + version + "），请刷新后重试");
    audit(actor, id, "TranslationEntryUpdated");
    return entry(id);
  }

  /**
   * 翻译状态流转（人工校对 / 退回）。
   *
   * <p>单独一个端点而不是复用编辑：改状态会**留下校对人与校对时刻**，而普通编辑不该能 顺手伪造这两样。V22 的触发器在 DB 层再兜一次。
   */
  public TranslationEntry setEntryStatus(
      AccessService.Access actor, UUID id, TranslationStatusInput input) {
    String status = MediaRules.normalizeUpper(input.status());
    String problem = TranslationRules.statusProblem(status);
    check(problem == null, problem);
    LocatedEntry located = locateEntry(id);
    var current = located.row();
    problem =
        TranslationRules.statusTransitionProblem(
            current.get("status", String.class),
            status,
            current.get("name", String.class),
            current.get("description", String.class));
    check(problem == null, problem);
    checkVersion(current.get("version", Long.class), input.version());

    boolean reviewed = "REVIEWED".equals(status);
    OffsetDateTime now = utcNow();
    int affected =
        sql.execute(
            "update "
                + located.table()
                + " set status=?,reviewed_by=?,reviewed_at=?::timestamptz,version=version+1,updated_at=now()"
                + " where tenant_id=? and id=? and version=?",
            status,
            reviewed ? actor.userId() : null,
            reviewed ? now : null,
            currentTenantId(),
            id,
            input.version());
    check(affected == 1, "这条译文刚被别人改过（版本不是 " + input.version() + "），请刷新后重试");
    audit(
        actor,
        id,
        "TranslationStatusChanged:" + current.get("status", String.class) + "->" + status);
    return entry(id);
  }

  /** 删除译文。删一条待翻占位行是日常操作（不再需要这种语言）。 */
  public void deleteEntry(AccessService.Access actor, UUID id, long version) {
    LocatedEntry located = locateEntry(id);
    checkVersion(located.row().get("version", Long.class), version);
    int affected =
        sql.execute(
            "delete from " + located.table() + " where tenant_id=? and id=? and version=?",
            actor.tenantId(),
            id,
            version);
    if (affected == 0) throw notFound("译文不存在或刚被别人改过");
    audit(actor, id, "TranslationEntryDeleted");
  }

  // ============================================================ 翻译完整度（04 刊登的读形状）

  /**
   * 翻译完整度：这个商品在这些语言上能不能直接刊登。
   *
   * <p>04 刊登填充的读形状。只有 {@code publishable=true}（status='REVIEWED'）才可刊登： 「已翻」是机器译文，拿去发平台等于没做校对这一步。
   *
   * @param locales 要检查的语言；为空时用租户启用的语言需求
   */
  public TranslationCoverage coverage(String targetType, UUID targetId, List<String> locales) {
    String type = checkedType(targetType);
    Map<String, Object> product = loadProduct(type, targetId);
    List<String> wanted = normalizeLocales(locales);
    if (wanted.isEmpty()) wanted = requiredLocales();
    java.util.Set<String> requiredSet =
        sql
            .fetch(
                "select locale from translation_locale where tenant_id=? and enabled",
                currentTenantId())
            .stream()
            .map(r -> r.get(0, String.class))
            .collect(Collectors.toSet());
    Map<String, Record> entries = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select * from "
                + tableOf(type)
                + " where tenant_id=? and "
                + subjectColumnOf(type)
                + "=?",
            currentTenantId(),
            targetId)) entries.put(r.get("locale", String.class), r);

    List<TranslationCoverageRow> rows = new ArrayList<>(wanted.size());
    long pending = 0, translated = 0, reviewed = 0, publishable = 0, requiredCount = 0;
    for (String locale : wanted) {
      Record r = entries.get(locale);
      String status = r == null ? null : r.get("status", String.class);
      boolean isRequired = requiredSet.contains(locale);
      boolean canPublish = "REVIEWED".equals(status);
      if (isRequired) requiredCount++;
      if ("PENDING".equals(status)) pending++;
      if ("TRANSLATED".equals(status)) translated++;
      if ("REVIEWED".equals(status)) reviewed++;
      if (canPublish) publishable++;
      rows.add(
          new TranslationCoverageRow(
              locale,
              isRequired,
              status,
              status == null ? "未登记" : TranslationRules.statusLabel(status),
              r == null ? null : r.get("id", UUID.class),
              r == null ? null : r.get("name", String.class),
              canPublish));
    }
    return new TranslationCoverage(
        type,
        targetId,
        (String) product.get("code"),
        (String) product.get("name"),
        wanted,
        rows,
        requiredCount,
        publishable,
        pending,
        translated,
        reviewed,
        "只有「已校对」才可刊登。缺哪些语言看 required=true 且 publishable=false 的行——" + "那正是 04 刊登填充时会取到空的那几行。");
  }

  // ============================================================ AI 翻译建议

  /** 建议列表：分页 + 关键词 + 状态 + 语言 + 主体 + batch_ref。 */
  public TranslationSuggestionPage suggestions(TranslationSuggestionQuery query) {
    int page = Math.max(0, query.page());
    int size = clampSize(query.size());
    StringBuilder where = new StringBuilder(" where s.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenantId());
    String type = MediaRules.normalizeUpper(query.targetType());
    if (!type.isEmpty()) {
      check(
          TranslationRules.targetTypeProblem(type) == null,
          TranslationRules.targetTypeProblem(type));
      where.append(" and s.target_type=?");
      binds.add(type);
    }
    String status = MediaRules.normalizeUpper(query.status());
    if (!status.isEmpty()) {
      check(
          TranslationRules.suggestionStatusProblem(status) == null,
          TranslationRules.suggestionStatusProblem(status));
      where.append(" and s.status=?");
      binds.add(status);
    }
    String locale = TranslationRules.normalizeLocale(query.locale());
    if (!locale.isEmpty()) {
      check(TranslationRules.localeProblem(locale) == null, TranslationRules.localeProblem(locale));
      where.append(" and s.locale=?");
      binds.add(locale);
    }
    String batchRef = MediaRules.normalize(query.batchRef());
    if (!batchRef.isEmpty()) {
      where.append(" and s.batch_ref=?");
      binds.add(batchRef);
    }
    UUID spuId = parseUuid(query.spuId(), "spuId");
    if (spuId != null) {
      where.append(" and s.spu_id=?");
      binds.add(spuId);
    }
    UUID skuId = parseUuid(query.skuId(), "skuId");
    if (skuId != null) {
      where.append(" and s.sku_id=?");
      binds.add(skuId);
    }
    String keyword = MediaRules.normalize(query.keyword());
    if (!keyword.isEmpty()) {
      where.append(
          " and (s.suggested_name like ? escape '!' or s.suggested_description like ? escape '!'"
              + " or s.locale like ? escape '!' or s.batch_ref like ? escape '!')");
      String like = escapeLike(keyword);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    long total =
        sql.fetchOne("select count(*) from translation_suggestion s" + where, binds.toArray())
            .get(0, Long.class);
    if (total == 0) return new TranslationSuggestionPage(List.of(), page, size, 0, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);
    var rows =
        sql.fetch(
            suggestionSelect() + where + " order by s.created_at desc,s.id desc limit ? offset ?",
            pageBinds.toArray());
    List<TranslationSuggestionRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) items.add(toSuggestion(r));
    return new TranslationSuggestionPage(
        items, page, size, total, (int) Math.ceil(total / (double) size));
  }

  /** 单条建议。 */
  public TranslationSuggestionRow suggestion(UUID id) {
    var r =
        sql.fetchOne(suggestionSelect() + " where s.tenant_id=? and s.id=?", currentTenantId(), id);
    if (r == null) throw notFound("翻译建议不存在");
    return toSuggestion(r);
  }

  /**
   * 登记一批 AI 翻译建议（18 AI 翻译回写）。
   *
   * <p>catalog 不调 AI：调它等于跨服务依赖调用顺序（catalog 就得知道 18 什么时候能回）。 18 翻完调这里写回，catalog 侧再由人工采纳或驳回。
   */
  public TranslationSuggestionPage submitSuggestions(
      AccessService.Access actor, TranslationSuggestionSubmit submit) {
    String problem = TranslationRules.suggestionsSizeProblem(submit.items().size());
    check(problem == null, problem);
    List<TranslationSuggestionRow> written = new ArrayList<>();
    for (TranslationSuggestionInput item : submit.items()) written.add(saveSuggestion(actor, item));
    audit(actor, Ids.next(), "TranslationSuggestionsSubmitted:" + written.size());
    return new TranslationSuggestionPage(written, 0, written.size(), written.size(), 1);
  }

  /** 登记单条建议。 */
  public TranslationSuggestionRow saveSuggestion(
      AccessService.Access actor, TranslationSuggestionInput input) {
    String type = checkedType(input.targetType());
    String locale = TranslationRules.normalizeLocale(input.locale());
    String problem = TranslationRules.localeProblem(locale);
    check(problem == null, problem);
    String name = TranslationRules.normalizeText(input.suggestedName());
    String description = TranslationRules.normalizeText(input.suggestedDescription());
    problem = TranslationRules.nameProblem(name);
    check(problem == null, problem);
    problem = TranslationRules.descriptionProblem(description);
    check(problem == null, problem);
    check(!name.isEmpty() || !description.isEmpty(), "这条翻译建议既没有名称也没有描述：采纳一个空字符串没有任何意义");
    String origin = MediaRules.normalizeUpper(input.origin());
    if (origin.isEmpty()) origin = "AI";
    problem = TranslationRules.suggestionOriginProblem(origin);
    check(problem == null, problem);
    String modelName = MediaRules.normalize(input.modelName());
    problem = TranslationRules.modelNameProblem(modelName);
    check(problem == null, problem);
    String batchRef = MediaRules.normalize(input.batchRef());
    problem = TranslationRules.batchRefProblem(batchRef);
    check(problem == null, problem);

    UUID spuId = "SPU".equals(type) ? requireUuid(input.spuId(), "spuId") : null;
    UUID skuId = "SKU".equals(type) ? requireUuid(input.skuId(), "skuId") : null;
    if (spuId != null) loadProduct("SPU", spuId);
    if (skuId != null) loadProduct("SKU", skuId);

    UUID id = Ids.next();
    try {
      sql.execute(
          "insert into translation_suggestion(id,tenant_id,target_type,spu_id,sku_id,locale,"
              + "suggested_name,suggested_description,origin,model_name,batch_ref,status,version)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,'PENDING',0)",
          id,
          actor.tenantId(),
          type,
          spuId,
          skuId,
          locale,
          name,
          description,
          origin,
          modelName,
          batchRef);
    } catch (DataAccessException e) {
      throw conflict(
          "这个商品已经有一条语言「"
              + locale
              + "」的待处置建议了（"
              + rootMessage(e)
              + "）：同时挂两条建议时「采纳哪一条」无法回答，而采纳顺序会决定最终译文。"
              + "请先处置已存在的那条，或换用别的语言");
    }
    audit(actor, id, "TranslationSuggestionSubmitted");
    return suggestion(id);
  }

  /**
   * 采纳一条 AI 建议：正文写进商品译文，本表保留快照。
   *
   * <p>采纳后译文落在 {@code TRANSLATED}（已翻）而不是 {@code REVIEWED}（已校对）——
   * 机器译文拿去发平台等于没做校对这一步。人工校对是**另一次显式的动作**。
   *
   * <p>已有译文时直接改正文，但状态回到「已翻」并清掉校对留痕：之前校对的是上一版正文， AI 建议刚把正文换掉，那份校对结论已经不对应当前文本了。
   */
  public TranslationEntry adoptSuggestion(AccessService.Access actor, UUID id) {
    Record current = locateSuggestion(id);
    String status = current.get("status", String.class);
    if (!"PENDING".equals(status))
      throw conflict(
          "这条翻译建议已经被处置过（"
              + TranslationRules.suggestionStatusLabel(status)
              + "）：处置结果是终态，采纳错了请直接改译文内容——"
              + "把建议改回待处置再走一遍会让同一条建议产出两条不同的译文历史，无从判断哪次算数");
    String type = current.get("target_type", String.class);
    String locale = current.get("locale", String.class);
    UUID subject =
        "SKU".equals(type) ? current.get("sku_id", UUID.class) : current.get("spu_id", UUID.class);
    String name = current.get("suggested_name", String.class);
    String description = current.get("suggested_description", String.class);
    String table = tableOf(type);
    String column = subjectColumnOf(type);
    OffsetDateTime now = utcNow();

    var existing =
        sql.fetchOne(
            "select id,version from "
                + table
                + " where tenant_id=? and "
                + column
                + "=? and locale=?",
            actor.tenantId(),
            subject,
            locale);
    TranslationEntry entry;
    if (existing == null) {
      UUID newId = Ids.next();
      sql.execute(
          "insert into "
              + table
              + "(id,tenant_id,"
              + column
              + ",locale,name,description,origin,status,version,translated_at)"
              + " values(?,?,?,?,?,?,'AI','TRANSLATED',0,?::timestamptz)",
          newId,
          actor.tenantId(),
          subject,
          locale,
          name,
          description,
          now);
      entry = entry(newId);
    } else {
      UUID existingId = existing.get("id", UUID.class);
      int affected =
          sql.execute(
              "update "
                  + table
                  + " set name=?,description=?,origin='AI',status='TRANSLATED',reviewed_by=null,"
                  + "reviewed_at=null,version=version+1,updated_at=now(),translated_at=?::timestamptz"
                  + " where tenant_id=? and id=? and version=?",
              name,
              description,
              now,
              actor.tenantId(),
              existingId,
              existing.get("version", Long.class));
      check(affected == 1, "这条译文刚被别人改过，本次采纳没有生效，请刷新后重试");
      entry = entry(existingId);
    }

    int decided =
        sql.execute(
            "update translation_suggestion set status='ADOPTED',decided_by=?,decided_at=?::timestamptz,"
                + "reject_reason='',version=version+1,updated_at=now() where tenant_id=? and id=?"
                + " and status='PENDING'",
            actor.userId(),
            now,
            actor.tenantId(),
            id);
    check(decided == 1, "这条建议刚被别人处置过，本次采纳没有生效，请刷新后重试");
    audit(actor, id, "TranslationSuggestionAdopted");
    return entry;
  }

  /** 驳回一条 AI 建议。理由必填：不给理由的驳回让下一轮翻译无从改进。 */
  public TranslationSuggestionRow rejectSuggestion(
      AccessService.Access actor, UUID id, TranslationSuggestionReject input) {
    String reason = MediaRules.normalize(input.rejectReason());
    String problem = TranslationRules.rejectReasonProblem(reason);
    check(problem == null, problem);
    String status = locateSuggestion(id).get("status", String.class);
    if (!"PENDING".equals(status))
      throw conflict(
          "这条翻译建议已经被处置过（" + TranslationRules.suggestionStatusLabel(status) + "）：处置结果是终态");
    OffsetDateTime now = utcNow();
    int affected =
        sql.execute(
            "update translation_suggestion set status='REJECTED',decided_by=?,decided_at=?::timestamptz,"
                + "reject_reason=?,version=version+1,updated_at=now()"
                + " where tenant_id=? and id=? and status='PENDING'",
            actor.userId(),
            now,
            reason,
            actor.tenantId(),
            id);
    check(affected == 1, "这条建议刚被别人处置过，本次驳回没有生效，请刷新后重试");
    audit(actor, id, "TranslationSuggestionRejected");
    return suggestion(id);
  }

  /** 删除一条建议。只允许删已驳回的：待处置的建议删掉等于让 18 的成果无声消失。 */
  public void deleteSuggestion(AccessService.Access actor, UUID id, long version) {
    var current = locateSuggestion(id);
    String status = current.get("status", String.class);
    check(
        "REJECTED".equals(status),
        "只有「已驳回」的建议可以删除，当前是「"
            + TranslationRules.suggestionStatusLabel(status)
            + "」：待处置的建议删掉等于让 18 的成果无声消失，"
            + "已采纳的建议删掉后译文的历史来源就没了。请先处置它");
    checkVersion(current.get("version", Long.class), version);
    sql.execute(
        "delete from translation_suggestion where tenant_id=? and id=? and version=?",
        actor.tenantId(),
        id,
        version);
    audit(actor, id, "TranslationSuggestionDeleted");
  }

  // ============================================================ CSV 导出

  /**
   * 导出译文 CSV（04 刊登与 18 批量校对取用）。
   *
   * <p>表头先写、数据按 {@value #CSV_CHUNK} 行分批写出，峰值内存与结果集大小无关。 走 GET + query 而不是 POST：导出结果与当前筛选完全一致。
   */
  public void streamEntriesCsv(
      TranslationEntryQuery query, java.util.function.Consumer<String> chunk) {
    chunk.accept("\ufeff对象类型,对象编码,对象名称,语言,名称,描述,来源,状态,是否可刊登,创建时间,校对时间\n");
    Filters filter = entryFilter(query);
    int offset = 0;
    while (true) {
      List<Record> rows = selectEntries(filter, CSV_CHUNK, (long) offset * CSV_CHUNK);
      if (rows.isEmpty()) break;
      StringBuilder buffer = new StringBuilder();
      for (Record r : rows) {
        String rowType = r.get("target_type", String.class);
        String rowStatus = r.get("status", String.class);
        buffer
            .append(csv(rowType))
            .append(',')
            .append(
                csv(
                    "SKU".equals(rowType)
                        ? r.get("sku_code", String.class)
                        : r.get("spu_code", String.class)))
            .append(',')
            .append(
                csv(
                    "SKU".equals(rowType)
                        ? r.get("sku_name", String.class)
                        : r.get("spu_name", String.class)))
            .append(',')
            .append(csv(r.get("locale", String.class)))
            .append(',')
            .append(csv(r.get("name", String.class)))
            .append(',')
            .append(csv(r.get("description", String.class)))
            .append(',')
            .append(csv(TranslationRules.originLabel(r.get("origin", String.class))))
            .append(',')
            .append(csv(TranslationRules.statusLabel(rowStatus)))
            .append(',')
            .append(csv("REVIEWED".equals(rowStatus) ? "可刊登" : "不可刊登"))
            .append(',')
            .append(csv(instant(r.get("created_at", OffsetDateTime.class))))
            .append(',')
            .append(csv(instant(r.get("reviewed_at", OffsetDateTime.class))))
            .append('\n');
      }
      chunk.accept(buffer.toString());
      if (rows.size() < CSV_CHUNK) break;
      offset++;
    }
  }

  // ============================================================ 内部：列表查询

  /** 列表筛选条件与它的绑定值。表名与列名由已校验的主体类型决定，不含用户输入。 */
  private record Filters(String targetType, String clause, List<Object> binds) {}

  private Filters entryFilter(TranslationEntryQuery query) {
    String type = MediaRules.normalizeUpper(query.targetType());
    if (!type.isEmpty()) {
      String problem = TranslationRules.targetTypeProblem(type);
      check(problem == null, problem);
    }
    String status = MediaRules.normalizeUpper(query.status());
    if (!status.isEmpty()) {
      String problem = TranslationRules.statusProblem(status);
      check(problem == null, problem);
    }
    String locale = TranslationRules.normalizeLocale(query.locale());
    if (!locale.isEmpty()) {
      String problem = TranslationRules.localeProblem(locale);
      check(problem == null, problem);
    }
    // 列名必须带 t. 前缀：外层 left join 了 spu 与 sku，两张表都有 status / locale /
    // name / version / created_at，不限定就是 PG 的 "column reference is ambiguous"。
    StringBuilder clause = new StringBuilder("1=1");
    List<Object> binds = new ArrayList<>();
    if (!status.isEmpty()) {
      clause.append(" and t.status=?");
      binds.add(status);
    }
    if (!locale.isEmpty()) {
      clause.append(" and t.locale=?");
      binds.add(locale);
    }
    UUID spuId = parseUuid(query.spuId(), "spuId");
    if (spuId != null) {
      clause.append(" and t.spu_id=?");
      binds.add(spuId);
    }
    UUID skuId = parseUuid(query.skuId(), "skuId");
    if (skuId != null) {
      clause.append(" and t.sku_id=?");
      binds.add(skuId);
    }
    String keyword = MediaRules.normalize(query.keyword());
    if (!keyword.isEmpty()) {
      clause.append(
          " and (t.name like ? escape '!' or t.description like ? escape '!'"
              + " or t.locale like ? escape '!')");
      String like = escapeLike(keyword);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    return new Filters(type, clause.toString(), binds);
  }

  /**
   * 译文 SELECT：两张表 UNION ALL 后再排序分页，避免「在 Java 里合并两页再切片」的假分页。
   *
   * <p>每个分支只 join 它自己那张商品表：{@code spu_translation} 行上没有 {@code sku_id} 列， 硬把两个商品表都 join 上会得到 PG 的
   * {@code column t.sku_id does not exist}。 另一侧的商品编码那一列直接给空串 —— 一行只属于一个主体，另一侧本来就没有值。
   */
  private String entrySelect(String type) {
    boolean sku = "SKU".equals(type);
    return "select t.id,"
        + (sku ? "'SKU'" : "'SPU'")
        + " as target_type,t.locale,t.name,t.description,t.origin,t.status,t.reviewed_by,"
        + "t.reviewed_at,t.version,t.created_at,t.updated_at,t.translated_at,"
        + (sku
            ? "null::uuid as spu_id,t.sku_id,'' as spu_code,'' as spu_name,"
                + "coalesce(sk.code,'') as sku_code,coalesce(sk.name,'') as sku_name"
            : "t.spu_id,null::uuid as sku_id,coalesce(sp.code,'') as spu_code,"
                + "coalesce(sp.name,'') as spu_name,'' as sku_code,'' as sku_name")
        + " from "
        + tableOf(type)
        + " t"
        + (sku
            ? " left join sku sk on sk.tenant_id=t.tenant_id and sk.id=t.sku_id"
            : " left join spu sp on sp.tenant_id=t.tenant_id and sp.id=t.spu_id");
  }

  private List<Record> selectEntries(Filters filter, int size, long offset) {
    List<String> tables =
        filter.targetType().isEmpty()
            ? TranslationRules.TARGET_TYPES
            : List.of(filter.targetType());
    StringBuilder sqlText = new StringBuilder();
    List<Object> binds = new ArrayList<>();
    for (String type : tables) {
      if (sqlText.length() > 0) sqlText.append(" union all ");
      sqlText.append(entrySelect(type)).append(" where t.tenant_id=? and ").append(filter.clause());
      binds.add(currentTenantId());
      binds.addAll(filter.binds());
    }
    sqlText.append(" order by created_at desc,id desc limit ? offset ?");
    binds.add(size);
    binds.add(offset);
    return sql.fetch(sqlText.toString(), binds.toArray());
  }

  private long countEntries(Filters filter) {
    List<String> tables =
        filter.targetType().isEmpty()
            ? TranslationRules.TARGET_TYPES
            : List.of(filter.targetType());
    StringBuilder sqlText = new StringBuilder("select coalesce(sum(total),0) from (");
    List<Object> binds = new ArrayList<>();
    for (int i = 0; i < tables.size(); i++) {
      if (i > 0) sqlText.append(" union all ");
      sqlText
          .append("select count(*) as total from ")
          .append(tableOf(tables.get(i)))
          // 别名与列表查询一致，filter.clause() 里的 t.xxx 才解析得到。
          .append(" t where t.tenant_id=? and ")
          .append(filter.clause());
      binds.add(currentTenantId());
      binds.addAll(filter.binds());
    }
    sqlText.append(") counted");
    return sql.fetchOne(sqlText.toString(), binds.toArray()).get(0, Long.class);
  }

  private String suggestionSelect() {
    return "select s.*,coalesce(sp.code,'') as spu_code,coalesce(sp.name,'') as spu_name,"
        + "coalesce(sk.code,'') as sku_code,coalesce(sk.name,'') as sku_name"
        + " from translation_suggestion s"
        + " left join spu sp on sp.tenant_id=s.tenant_id and sp.id=s.spu_id"
        + " left join sku sk on sk.tenant_id=s.tenant_id and sk.id=s.sku_id";
  }

  /** 每种语言的译文覆盖统计：[总数, 待翻, 已翻, 已校对]。 */
  private Map<String, long[]> localeCoverage() {
    var rows =
        sql.fetch(
            "select locale,count(*) as total,"
                + "count(*) filter (where status='PENDING') as pending,"
                + "count(*) filter (where status='TRANSLATED') as translated,"
                + "count(*) filter (where status='REVIEWED') as reviewed"
                + " from spu_translation where tenant_id=? group by locale"
                + " union all"
                + " select locale,count(*) as total,"
                + "count(*) filter (where status='PENDING') as pending,"
                + "count(*) filter (where status='TRANSLATED') as translated,"
                + "count(*) filter (where status='REVIEWED') as reviewed"
                + " from sku_translation where tenant_id=? group by locale",
            currentTenantId(),
            currentTenantId());
    Map<String, long[]> out = new LinkedHashMap<>();
    for (Record r : rows) {
      String locale = r.get("locale", String.class);
      long[] stat = out.computeIfAbsent(locale, k -> new long[4]);
      stat[0] += r.get("total", Long.class);
      stat[1] += r.get("pending", Long.class);
      stat[2] += r.get("translated", Long.class);
      stat[3] += r.get("reviewed", Long.class);
    }
    return out;
  }

  /** 合并视图的一行。覆盖统计统一从 {@link #localeCoverage()} 的四项数组里取，两个来源同一口径。 */
  private TranslationRequirementRow requirementRow(
      String locale,
      String localeName,
      boolean fromManual,
      boolean fromSite,
      int shopCount,
      int marketplaceCount,
      boolean enabled,
      String note,
      Map<String, long[]> coverage) {
    long[] stat = coverage.getOrDefault(locale, new long[4]);
    return new TranslationRequirementRow(
        locale,
        localeName,
        true,
        fromManual,
        fromSite,
        shopCount,
        marketplaceCount,
        enabled,
        note,
        stat[0],
        stat[1],
        stat[2],
        stat[3]);
  }

  // ============================================================ 内部：记录定位与校验

  /** 译文在两张表里的位置。 */
  record LocatedEntry(String table, String type, Record row) {}

  private LocatedEntry locateEntry(UUID id) {
    for (String type : TranslationRules.TARGET_TYPES) {
      String table = tableOf(type);
      var r =
          sql.fetchOne(
              "select * from " + table + " where tenant_id=? and id=?", currentTenantId(), id);
      if (r != null) return new LocatedEntry(table, type, r);
    }
    throw notFound("译文不存在");
  }

  private Record locateSuggestion(UUID id) {
    var r =
        sql.fetchOne(
            "select * from translation_suggestion where tenant_id=? and id=?",
            currentTenantId(),
            id);
    if (r == null) throw notFound("翻译建议不存在");
    return r;
  }

  /** 取商品本体（编码与名称，页面与 CSV 都要显示）。 */
  private Map<String, Object> loadProduct(String type, UUID id) {
    String table = "SKU".equals(type) ? "sku" : "spu";
    var r =
        sql.fetchOne(
            "select code,name from " + table + " where tenant_id=? and id=?",
            currentTenantId(),
            id);
    if (r == null) throw notFound("商品不存在：" + TranslationRules.targetTypeLabel(type) + " " + id);
    return Map.of("code", r.get("code", String.class), "name", r.get("name", String.class));
  }

  /**
   * 组装一条译文。
   *
   * <p>{@code targetType} 由调用方给出而不是从行里取：按 id 定位走的是 {@code select *}， 那种行里既没有 {@code target_type}
   * 也没有另一张表的主体列 （{@code spu_translation} 没有 {@code sku_id}），让记录自己报会得到假值。
   */
  private TranslationEntry toEntry(Record r, String targetType) {
    boolean sku = "SKU".equals(targetType);
    return new TranslationEntry(
        r.get("id", UUID.class),
        targetType,
        sku ? null : r.get("spu_id", UUID.class),
        sku ? r.get("sku_id", UUID.class) : null,
        r.get("locale", String.class),
        r.get("name", String.class),
        r.get("description", String.class),
        r.get("origin", String.class),
        r.get("status", String.class),
        r.get("reviewed_by", UUID.class),
        instant(r.get("reviewed_at", OffsetDateTime.class)),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)),
        instant(r.get("translated_at", OffsetDateTime.class)));
  }

  private TranslationSuggestionRow toSuggestion(Record r) {
    String targetType = r.get("target_type", String.class);
    boolean sku = "SKU".equals(targetType);
    return new TranslationSuggestionRow(
        r.get("id", UUID.class),
        targetType,
        r.get("spu_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("locale", String.class),
        r.get("suggested_name", String.class),
        r.get("suggested_description", String.class),
        r.get("origin", String.class),
        r.get("model_name", String.class),
        r.get("batch_ref", String.class),
        r.get("status", String.class),
        r.get("decided_by", UUID.class),
        instant(r.get("decided_at", OffsetDateTime.class)),
        r.get("reject_reason", String.class),
        sku ? r.get("sku_code", String.class) : r.get("spu_code", String.class),
        sku ? r.get("sku_name", String.class) : r.get("spu_name", String.class),
        r.get("version", Long.class),
        instant(r.get("created_at", OffsetDateTime.class)),
        instant(r.get("updated_at", OffsetDateTime.class)));
  }

  /** 校验并规范化主体类型。 */
  static String checkedType(String targetType) {
    String type = MediaRules.normalizeUpper(targetType);
    String problem = TranslationRules.targetTypeProblem(type);
    check(problem == null, problem);
    return type;
  }

  List<String> normalizeLocales(List<String> locales) {
    if (locales == null || locales.isEmpty()) return List.of();
    List<String> out = new ArrayList<>();
    for (String value : locales) {
      String locale = TranslationRules.normalizeLocale(value);
      if (locale.isEmpty()) continue;
      if (!out.contains(locale)) out.add(locale);
    }
    String problem = TranslationRules.coverageLocalesSizeProblem(out.size());
    check(problem == null, problem);
    for (String locale : out) {
      String p = TranslationRules.localeProblem(locale);
      check(p == null, p);
    }
    return out;
  }

  /** 租户启用的语言需求（「需要哪些语言」的默认取值）。 */
  List<String> requiredLocales() {
    return sql
        .fetch(
            "select locale from translation_locale where tenant_id=? and enabled order by locale",
            currentTenantId())
        .stream()
        .map(r -> r.get(0, String.class))
        .toList();
  }

  UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  static int clampSize(int size) {
    if (size <= 0) return 20;
    return Math.min(size, MAX_PAGE_SIZE);
  }

  static String escapeLike(String value) {
    return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  static void check(boolean ok, String message) {
    if (!ok) throw MediaRules.bad(message);
  }

  static void checkVersion(long current, long submitted) {
    check(current == submitted, "这条记录刚被别人改过（版本是 " + current + "，你提交的是 " + submitted + "），请刷新后重试");
  }

  static UUID requireUuid(String value, String field) {
    UUID id = parseUuid(value, field);
    if (id == null) throw MediaRules.bad(field + " 不能为空（主体类型是 " + field + " 时必须给对应的 id）");
    return id;
  }

  static UUID parseUuid(String value, String field) {
    String text = MediaRules.normalize(value);
    if (text.isEmpty()) return null;
    try {
      return UUID.fromString(text);
    } catch (IllegalArgumentException e) {
      throw MediaRules.bad(field + " 不是合法的 UUID：" + text);
    }
  }

  /** 当前 UTC 时刻。写 timestamptz 列时一律配 {@code ?::timestamptz}，不经过 Timestamp.valueOf。 */
  static OffsetDateTime utcNow() {
    return OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
  }

  static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }

  static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /** CSV 转义按 RFC 4180；前导 =/+/-/@ 会被 Excel 当成公式执行，加前缀单引号切断。 */
  static String csv(String value) {
    String text = value == null ? "" : value;
    if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) text = "'" + text;
    return '"' + text.replace("\"", "\"\"") + '"';
  }

  static String rootMessage(DataAccessException e) {
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
