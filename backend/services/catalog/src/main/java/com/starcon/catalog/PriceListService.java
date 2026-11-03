package com.starcon.catalog;

import static com.starcon.catalog.PriceListModels.*;

import com.starcon.catalog.PriceListModels.CoverageEntry;
import com.starcon.catalog.PriceListModels.CoverageOverview;
import com.starcon.catalog.PriceListModels.CoverageSummary;
import com.starcon.catalog.PriceListModels.CurrencyCoverage;
import com.starcon.catalog.PriceListModels.PriceListLegalEntity;
import com.starcon.catalog.PriceListModels.PriceListCapabilities;
import com.starcon.catalog.PriceListModels.PriceListDetail;
import com.starcon.catalog.PriceListModels.PriceListInput;
import com.starcon.catalog.PriceListModels.PriceListPage;
import com.starcon.catalog.PriceListModels.PriceListSummary;
import com.starcon.catalog.PriceListModels.ScopeDirectory;
import com.starcon.catalog.PriceListModels.ScopeInput;
import com.starcon.catalog.PriceListModels.ScopeType;
import com.starcon.catalog.PriceListModels.ScopeTypeCoverage;
import com.starcon.catalog.PriceListModels.ScopeView;
import com.starcon.catalog.PriceListModels.PriceListShop;
import com.starcon.catalog.PriceListModels.PriceListSite;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.1.1.1 价目表列表。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：管理价目表（如"北美自发货零售价"），每张表绑定一个币种、适用法人/店铺/站点、生效区间，展示表内价目项数量与最近更新时间。
 *
 * **输入**：价目表增删改；币种与适用范围选择；生效区间设置。
 *
 * **输出**：价目表记录 + 覆盖范围摘要。
 *
 * **关系**：上游 = —（价目表自身的增删改只依赖法人/店铺/站点主数据，卷 02/03 已交付）；下游 = 5.1.1.2 价目项、5.3.2.1 定价模板。**一价目一币种**是硬约束——混币种的价目表会让利润计算失效。
 *
 * </blockquote>
 *
 * <h2>🔴 本类的六条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 一价目一币种</b>：价目表头只有<b>一个</b>币种列（{@code price_list.currency}），
 *       单列 NOT NULL + 命名 CHECK {@code price_list_one_currency} ⇒ 混币种在 DB 层写不进去。
 *       本类只接受一个币种字符串（归一化成三位大写字母），
 *       <b>没有</b>「多个币种」这种入参形状。价目项（5.1.1.2）**不得**自带币种列。
 *   <li><b>🔴 生效区间是半开区间 [valid_from, valid_to)</b>：起点含、终点不含。
 *       「此刻是否生效」用 <b>DB 的 now()</b> 判定（不混用 JVM 墙钟 —— 那是跨时钟域比较）。
 *   <li><b>🔴 表内价目项数量来自计数器</b>：{@code price_list.item_count} 由 5.1.1.2 的价目项写入
 *       经触发器 {@code price_list_sync_item_count()} 维护。价目项模块未交付前它恒为 0 ——
 *       那是<b>真实的 0</b>（表里确实还没有价目项），<b>不是</b>「取不到」。
 *       本类<b>不读</b> price_list_item 表（那张表还不存在）。
 *   <li><b>🔴 适用范围必须真实存在</b>：法人来自 identity、店铺与站点来自 channel，
 *       全部走授权只读 HTTP 端点（跨服务 FK 被 AGENTS.md 禁止）。编码不在目录里 ⇒ 400；
 *       上游读不到 ⇒ 503，<b>绝不</b>用空目录冒充「没有法人 / 没有店铺」。
 *   <li><b>🔴 冲突检测不是本模块的职责</b>：蓝图把 5.1.2.1（优先级与冲突检测）写成本表的
 *       <b>下游</b>（两者互为上游会死锁）。本模块<b>不</b>实现任何优先级或冲突判定 ——
 *       本表连价目项都还没有，「同 SKU 冲突」的那另一半在 5.1.1.2 手里。
 *   <li><b>租户只来自已认证成员关系</b>（{@link AccessService}），不读 {@code X-Tenant-Id} 头。
 *       每条 SQL 的 where 都带显式 {@code tenant_id=} 绑定，RLS 再兜一道。
 * </ol>
 */
@Service
public class PriceListService {

  /** 租户上下文取法：RLS 已按它隔离，这里带上只是让执行计划走租户前缀的索引。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 台账页大小上限：与相邻模块一致。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 覆盖范围摘要一次最多汇总多少张价目表（价目表是租户级配置，不会很多）。 */
  private static final int COVERAGE_LIMIT = 1000;

  private final DSLContext sql;
  private final PriceListScopePort scope;
  private final ChannelPlatformPort platforms;
  /** 🔴 5.1.2.1 守门员（优先级与冲突检测）。见 {@link #save} 里的调用点。 */
  private final PriceListPriorityService priorityGate;

  public PriceListService(
      DSLContext sql,
      PriceListScopePort scope,
      ChannelPlatformPort platforms,
      PriceListPriorityService priorityGate) {
    this.sql = sql;
    this.scope = scope;
    this.platforms = platforms;
    this.priorityGate = priorityGate;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：三类适用范围 + 七条口径说明。
   *
   * <p>页面固定显示它 —— 蓝图那句「一价目一币种是硬约束」与「5.1.2.1 是守门员」必须写在脸上。
   */
  public PriceListCapabilities capabilities() {
    var scopeTypes = List.of(ScopeType.values());
    var notes =
        List.of(
            new CapabilityNote(
                "一价目一币种",
                "一张价目表**只有一个币种**：币种钉在表头上（price_list.currency，单列 NOT NULL），"
                    + "DB 层还有命名约束 price_list_one_currency —— 混币种（USD,CNY）写不进去。"
                    + "价目项（5.1.1.2）**不得**自带币种列，价目项的币种只能来自这张表。"
                    + "理由：混币种的价目表会让 5.3.1.1 的利润计算失效（利润必须落在一个币种里）。"),
            new CapabilityNote(
                "生效区间是半开区间",
                "[生效起点, 生效终点)：起点**含**、终点**不含**，终点为空 = 长期有效。"
                    + "两张首尾相接的价目表不会在交界那一刻同时生效 —— 这是 5.1.2.1 冲突检测能成立的前提。"
                    + "「此刻是否生效」由数据库时钟（now()）判定，页面显示的就是服务端算好的那个结论。"),
            new CapabilityNote(
                "表内价目项数量",
                "这一列来自 price_list.item_count，由 **5.1.1.2 价目项**的写入经触发器"
                    + " price_list_sync_item_count() 维护。价目项模块尚未交付时它恒为 0 —— "
                    + "那是**真实的 0**（这张表里确实还没有价目项），不是「取不到」。"),
            new CapabilityNote(
                "适用范围",
                "法人来自 17.1.2.2（identity），店铺来自 2.3.1.1、站点来自 2.1.1.1（channel），"
                    + "全部走授权只读端点读取，本服务库里**没有**它们的副本。"
                    + "编码不在目录里 ⇒ 拒绝保存；上游读不到 ⇒ 503「读不到」，"
                    + "**不会**用空目录冒充「没有法人 / 没有店铺」。"),
            new CapabilityNote(
                "适用范围为空 = 适用于全租户",
                "一张没有范围行的价目表对全租户生效 —— 这是**显式语义**，不是漏填，"
                    + "页面的覆盖摘要会写「全租户（未限定法人 / 店铺 / 站点）」。"),
            new CapabilityNote(
                "冲突检测不在本模块",
                "蓝图把 5.1.2.1（优先级与冲突检测）写成本表的**下游**（两者互为上游会形成死锁，"
                    + "双方永远排不出车道）。本模块**不**实现优先级与冲突判定："
                    + "本表连价目项都还没有，而「同 SKU 冲突」的另一半在 5.1.1.2 手里。"
                    + "5.1.2.1 交付后，保存价目表时才会真的校验冲突。"),
            new CapabilityNote(
                "本模块不建价目项表",
                "price_list_item 是 **5.1.1.2 的写入所有权**（蓝图 05 卷首：catalog 拥有 price_list 与 "
                    + "price_list_item 两张表）。本模块只落 price_list 与 price_list_scope，"
                    + "**不建** price_list_item —— 两边都建会出现同一张事实表两个写入所有者。"));
    return new PriceListCapabilities(
        scopeTypes,
        notes,
        "一张价目表只有一个币种（表头单列 + DB 命名约束 price_list_one_currency）。"
            + "价目项不得自带币种列。",
        "生效区间是半开区间 [起点, 终点)：起点含、终点不含，空终点 = 长期有效。",
        "表内价目项数量由 5.1.1.2 的价目项写入维护（触发器 price_list_sync_item_count）；"
            + "价目项未交付前该值恒为 0（真实的 0）。",
        "适用范围为空 = 适用于全租户（显式语义）。",
        "上游 = 法人（identity 17.1.2.2）+ 店铺与站点（channel 2.3.1.1 / 2.1.1.1），"
            + "走授权只读 HTTP 端点读取，catalog 库里没有它们的副本。",
        "下游 = 5.1.1.2 价目项（引用本表的稳定主键与币种）、5.3.2.1 定价模板（怎么算 vs 算完是多少）。",
        "🔴 5.1.2.1 优先级与冲突检测是本表的**下游**（守门员），不是本表的前置。"
            + "本模块不实现冲突检测。");
  }

  // ================================================================ 适用范围目录

  /**
   * 适用范围目录：法人 + 店铺 + 站点。
   *
   * <p>🔴 上游读不到 ⇒ <b>503</b>（理由写「读不到上游」），不用空列表冒充「没有法人 / 没有店铺」。
   */
  public ScopeDirectory scopeDirectory(String bearer) {
    var legalEntities = scope.legalEntities(bearer);
    var shops = scope.shops(bearer);
    var sites = platforms.sites(bearer);
    var byLegalEntity = new LinkedHashMap<UUID, PriceListLegalEntity>();
    for (var entity : legalEntities) {
      byLegalEntity.put(entity.id(), new PriceListLegalEntity(entity.id(), entity.code(), entity.name(), entity.baseCurrency(), entity.status()));
    }
    var shopOptions = new ArrayList<PriceListShop>();
    for (var shop : shops) {
      var owner = shop.legalEntityId() == null ? null : byLegalEntity.get(shop.legalEntityId());
      shopOptions.add(
          new PriceListShop(
              shop.code(),
              shop.name(),
              shop.platformCode(),
              owner == null ? null : owner.code(),
              owner == null ? null : owner.name(),
              shop.status(),
              shop.siteCodes()));
    }
    var siteOptions = new ArrayList<PriceListSite>();
    for (var site : sites) {
      siteOptions.add(
          new PriceListSite(
              site.platformCode(),
              site.platformName(),
              site.siteCode(),
              site.siteName(),
              site.countryCode(),
              site.defaultCurrency()));
    }
    return new ScopeDirectory(
        new ArrayList<>(byLegalEntity.values()),
        shopOptions,
        siteOptions,
        "法人来自身份服务（17.1.2.2），店铺与站点来自渠道服务（2.3.1.1 / 2.1.1.1）；"
            + "本服务不另建这些主数据的副本 —— 读不到时显示「读不到」，不显示「没有」。");
  }

  // ================================================================ 查询

  /** 价目表列表的筛选条件。 */
  public record PriceListQuery(
      String keyword,
      String currency,
      ScopeType scopeType,
      String scopeRef,
      Boolean enabled,
      Boolean effectiveNow,
      int page,
      int size) {}

  /** 价目表台账：真分页 + 关键词 / 币种 / 适用范围 / 状态筛选。 */
  public PriceListPage list(PriceListQuery query) {
    int limited = Math.min(Math.max(query.size() <= 0 ? 20 : query.size(), 1), MAX_PAGE_SIZE);
    int offset = Math.max(query.page(), 0) * limited;
    var where = new StringBuilder(" where l.tenant_id=" + TENANT);
    var params = new ArrayList<Object>();
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.append(" and (l.code ilike ? or l.name ilike ?)");
      var pattern = "%" + query.keyword().trim() + "%";
      params.add(pattern);
      params.add(pattern);
    }
    if (query.currency() != null && !query.currency().isBlank()) {
      where.append(" and l.currency=?");
      params.add(query.currency().trim().toUpperCase(Locale.ROOT));
    }
    if (query.scopeType() != null) {
      where.append(
          " and exists (select 1 from price_list_scope s where s.tenant_id=l.tenant_id"
              + " and s.price_list_id=l.id and s.scope_type=?)");
      params.add(query.scopeType().name());
      if (query.scopeRef() != null && !query.scopeRef().isBlank()) {
        where.append(
            " and exists (select 1 from price_list_scope s2 where s2.tenant_id=l.tenant_id"
                + " and s2.price_list_id=l.id and s2.scope_type=? and s2.scope_ref=?)");
        params.add(query.scopeType().name());
        params.add(query.scopeRef().trim());
      }
    }
    if (query.enabled() != null) {
      where.append(" and l.enabled=?");
      params.add(query.enabled());
    }
    if (query.effectiveNow() != null) {
      where.append(
          query.effectiveNow()
              ? " and l.valid_from <= now() and (l.valid_to is null or l.valid_to > now())"
              : " and (l.valid_from > now() or (l.valid_to is not null and l.valid_to <= now()))");
    }
    var rows =
        sql.fetch(
            "select l.id, l.code, l.name, l.currency, l.valid_from, l.valid_to, l.enabled,"
                + " l.item_count, l.created_by, l.updated_at,"
                + " (l.valid_from <= now() and (l.valid_to is null or l.valid_to > now())) as effective_now,"
                + " (select count(*) from price_list_scope s where s.tenant_id=l.tenant_id"
                + "   and s.price_list_id=l.id) as scope_count,"
                + " (select count(*) from price_list_scope s where s.tenant_id=l.tenant_id"
                + "   and s.price_list_id=l.id and s.scope_type='LEGAL_ENTITY') as legal_entity_count,"
                + " (select count(*) from price_list_scope s where s.tenant_id=l.tenant_id"
                + "   and s.price_list_id=l.id and s.scope_type='SHOP') as shop_count,"
                + " (select count(*) from price_list_scope s where s.tenant_id=l.tenant_id"
                + "   and s.price_list_id=l.id and s.scope_type='SITE') as site_count"
                + " from price_list l"
                + where
                + " order by l.updated_at desc, l.id"
                + " limit ? offset ?",
            concat(params, limited, offset));
    long total =
        sql.fetchOne("select count(*) from price_list l" + where, params.toArray()).get(0, Long.class);
    var items = new ArrayList<PriceListSummary>();
    for (var row : rows) {
      items.add(summaryOf(row));
    }
    return new PriceListPage(items, Math.max(query.page(), 0), limited, total);
  }

  /** 价目表详情 = 表头 + 适用范围 + 覆盖范围摘要。 */
  public PriceListDetail detail(UUID id) {
    var row = listRow(id);
    if (row == null) throw notFound("价目表不存在");
    return detailOf(row);
  }

  // ================================================================ 写入

  /**
   * 新建 / 修改价目表（修改走乐观锁）。适用范围整体替换。
   *
   * @param bearer 调用者的 Authorization 头（透传给上游，用于核对适用范围真实存在）
   */
  public PriceListDetail save(AccessService.Access actor, UUID id, PriceListInput input, String bearer) {
    var normalized = normalize(input);
    // 适用范围必须真实存在（上游目录）。只读本次用得到的那几个上游。
    var directory = verifyScopes(normalized.scopes(), bearer);
    if (id == null) {
      var duplicate =
          sql.fetchOne(
              "select id from price_list where tenant_id=" + TENANT + " and code=?",
              normalized.code());
      if (duplicate != null) throw conflict("价目表编码 " + normalized.code() + " 已存在");
      UUID newId = UUID.randomUUID();
      sql.execute(
          "insert into price_list(id,tenant_id,code,name,description,currency,valid_from,valid_to,"
              + "enabled,item_count,note,version,created_by,created_at,updated_at)"
              + " values(?::uuid,?::uuid,?,?,?,?,?::timestamptz,?::timestamptz,?,0,?,0,?::uuid,now(),now())",
          newId,
          actor.tenantId(),
          normalized.code(),
          normalized.name(),
          normalized.description(),
          normalized.currency(),
          normalized.validFrom(),
          normalized.validTo(),
          normalized.enabled(),
          normalized.note(),
          actor.userId());
      replaceScopes(newId, actor, normalized.scopes(), directory);
      // 🔴 5.1.2.1 守门员：适用范围刚落库（**还没提交**）就跑冲突检测 ——
      //    检出阻断级冲突 ⇒ 抛 409 ⇒ 整个保存事务回滚 ⇒ 什么都没存下。
      //    本租户一张优先级规则都没有时，这个调用直接返回（不读上游目录）。
      priorityGate.assertNoBlockingConflict(newId, bearer);
      return detail(newId);
    }
    var current =
        sql.fetchOne(
            "select version from price_list where tenant_id=" + TENANT + " and id=? for update",
            id);
    if (current == null) throw notFound("价目表不存在");
    long currentVersion = current.get(0, Long.class);
    if (input.version() == null || input.version() != currentVersion) {
      throw conflict(
          "这张价目表已经被别人改过了（当前版本 "
              + currentVersion
              + "，你提交的是 "
              + input.version()
              + "）。请刷新后重新提交 —— 覆盖别人的改动会让定价区间与覆盖范围对不上账。");
    }
    var duplicate =
        sql.fetchOne(
            "select id from price_list where tenant_id=" + TENANT + " and code=? and id<>?",
            normalized.code(),
            id);
    if (duplicate != null) throw conflict("价目表编码 " + normalized.code() + " 已存在");
    sql.execute(
        "update price_list set code=?, name=?, description=?, currency=?,"
            + " valid_from=?::timestamptz, valid_to=?::timestamptz, enabled=?, note=?,"
            + " version=version+1, updated_at=now()"
            + " where tenant_id="
            + TENANT
            + " and id=?",
        normalized.code(),
        normalized.name(),
        normalized.description(),
        normalized.currency(),
        normalized.validFrom(),
        normalized.validTo(),
        normalized.enabled(),
        normalized.note(),
        id);
    replaceScopes(id, actor, normalized.scopes(), directory);
    // 🔴 5.1.2.1 守门员：同上 —— 改适用范围也可能撞出不可解析的并列。
    priorityGate.assertNoBlockingConflict(id, bearer);
    return detail(id);
  }

  /**
   * 删除价目表（连同它的适用范围）。
   *
   * <p>🔴 表里还有价目项时**拒绝删除**：价目项是 5.1.1.2 的写入所有权，
   * 删了表会让那些价目项指向一张不存在的表 —— 那是一条看不见的坏数据。
   * 提示语直接给出该停用这张表。
   */
  public void delete(UUID id) {
    var current =
        sql.fetchOne(
            "select item_count from price_list where tenant_id=" + TENANT + " and id=? for update",
            id);
    if (current == null) throw notFound("价目表不存在");
    int itemCount = current.get(0, Integer.class);
    if (itemCount > 0) {
      throw conflict(
          "这张价目表里还有 "
              + itemCount
              + " 个价目项，不能删除 —— 先清掉价目项，或者停用这张表（停用不影响历史价目项）。");
    }
    sql.execute(
        "delete from price_list_scope where tenant_id=" + TENANT + " and price_list_id=?", id);
    sql.execute("delete from price_list where tenant_id=" + TENANT + " and id=?", id);
  }

  // ================================================================ 覆盖范围摘要

  /**
   * 模块级覆盖范围摘要（蓝图输出句：「价目表记录 + 覆盖范围摘要」）。
   *
   * <p>🔴 这里**不做**冲突检测（5.1.2.1 的职责）。
   */
  public CoverageOverview coverage() {
    var rows =
        sql.fetch(
            "select l.id, l.code, l.name, l.currency, l.valid_from, l.valid_to, l.enabled,"
                + " l.item_count, l.created_by, l.updated_at,"
                + " (l.valid_from <= now() and (l.valid_to is null or l.valid_to > now())) as effective_now,"
                + " (select count(*) from price_list_scope s where s.tenant_id=l.tenant_id"
                + "   and s.price_list_id=l.id) as scope_count,"
                + " (select count(*) from price_list_scope s where s.tenant_id=l.tenant_id"
                + "   and s.price_list_id=l.id and s.scope_type='LEGAL_ENTITY') as legal_entity_count,"
                + " (select count(*) from price_list_scope s where s.tenant_id=l.tenant_id"
                + "   and s.price_list_id=l.id and s.scope_type='SHOP') as shop_count,"
                + " (select count(*) from price_list_scope s where s.tenant_id=l.tenant_id"
                + "   and s.price_list_id=l.id and s.scope_type='SITE') as site_count"
                + " from price_list l where l.tenant_id="
                + TENANT
                + " order by l.updated_at desc, l.id limit ?",
            COVERAGE_LIMIT);
    var entries = new ArrayList<CoverageEntry>();
    var currencies = new LinkedHashMap<String, Integer>();
    var scopeTypes = new LinkedHashMap<ScopeType, Integer>();
    int enabled = 0, effectiveNow = 0, totalItems = 0;
    for (var row : rows) {
      var summary = summaryOf(row);
      entries.add(
          new CoverageEntry(
              summary.id(),
              summary.code(),
              summary.name(),
              summary.currency(),
              summary.enabled(),
              summary.effectiveNow(),
              summary.itemCount(),
              summary.coverage()));
      currencies.merge(summary.currency(), 1, Integer::sum);
      if (summary.enabled()) enabled++;
      if (summary.effectiveNow()) effectiveNow++;
      totalItems += summary.itemCount();
      var coverage = summary.coverage();
      if (coverage.legalEntityCount() > 0) scopeTypes.merge(ScopeType.LEGAL_ENTITY, 1, Integer::sum);
      if (coverage.shopCount() > 0) scopeTypes.merge(ScopeType.SHOP, 1, Integer::sum);
      if (coverage.siteCount() > 0) scopeTypes.merge(ScopeType.SITE, 1, Integer::sum);
    }
    var currencyRows = new ArrayList<CurrencyCoverage>();
    for (var entry : currencies.entrySet()) {
      currencyRows.add(new CurrencyCoverage(entry.getKey(), entry.getValue()));
    }
    var scopeRows = new ArrayList<ScopeTypeCoverage>();
    for (var type : ScopeType.values()) {
      scopeRows.add(new ScopeTypeCoverage(type, type.label(), scopeTypes.getOrDefault(type, 0)));
    }
    return new CoverageOverview(
        entries.size(),
        enabled,
        effectiveNow,
        totalItems,
        currencyRows,
        scopeRows,
        entries,
        "覆盖范围摘要只回答「有多少张价目表、管到哪里、是什么币种、此刻生效没有」。"
            + "🔴 **冲突检测不在这里** —— 那是 5.1.2.1 的职责（它是本表的守门员，属下游）。");
  }

  // ================================================================ 内部

  /** 价目表行（列表与详情共用）。 */
  private org.jooq.Record listRow(UUID id) {
    return sql.fetchOne(
        "select l.id, l.code, l.name, l.description, l.currency, l.valid_from, l.valid_to, l.enabled,"
            + " l.item_count, l.note, l.version, l.created_by, l.created_at, l.updated_at,"
            + " (l.valid_from <= now() and (l.valid_to is null or l.valid_to > now())) as effective_now,"
            + " (select count(*) from price_list_scope s where s.tenant_id=l.tenant_id"
            + "   and s.price_list_id=l.id) as scope_count,"
            + " (select count(*) from price_list_scope s where s.tenant_id=l.tenant_id"
            + "   and s.price_list_id=l.id and s.scope_type='LEGAL_ENTITY') as legal_entity_count,"
            + " (select count(*) from price_list_scope s where s.tenant_id=l.tenant_id"
            + "   and s.price_list_id=l.id and s.scope_type='SHOP') as shop_count,"
            + " (select count(*) from price_list_scope s where s.tenant_id=l.tenant_id"
            + "   and s.price_list_id=l.id and s.scope_type='SITE') as site_count"
            + " from price_list l where l.tenant_id="
            + TENANT
            + " and l.id=?",
        id);
  }

  /** 一行价目表 → 列表摘要（含覆盖范围摘要）。 */
  private PriceListSummary summaryOf(org.jooq.Record row) {
    var coverage =
        new CoverageSummary(
            intOf(row, "scope_count"),
            intOf(row, "legal_entity_count"),
            intOf(row, "shop_count"),
            intOf(row, "site_count"),
            intOf(row, "scope_count") == 0,
            PriceListRules.coverageText(
                intOf(row, "legal_entity_count"),
                intOf(row, "shop_count"),
                intOf(row, "site_count")));
    var createdBy = row.get("created_by", UUID.class);
    return new PriceListSummary(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        row.get("currency", String.class),
        row.get("valid_from", OffsetDateTime.class),
        row.get("valid_to", OffsetDateTime.class),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
        intOf(row, "item_count"),
        coverage,
        Boolean.TRUE.equals(row.get("effective_now", Boolean.class)),
        createdBy == null ? null : createdBy.toString(),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 价目表详情 = 表头 + 适用范围。 */
  private PriceListDetail detailOf(org.jooq.Record row) {
    UUID id = row.get("id", UUID.class);
    var scopes = new ArrayList<ScopeView>();
    for (var scope :
        sql.fetch(
            "select id, scope_type, scope_ref, scope_name, platform_code from price_list_scope"
                + " where tenant_id="
                + TENANT
                + " and price_list_id=? order by scope_type, scope_ref",
            id)) {
      var type = ScopeType.parse(scope.get("scope_type", String.class));
      scopes.add(
          new ScopeView(
              scope.get("id", UUID.class),
              type,
              type == null ? null : type.label(),
              scope.get("scope_ref", String.class),
              scope.get("scope_name", String.class),
              scope.get("platform_code", String.class)));
    }
    var summary = summaryOf(row);
    return new PriceListDetail(
        summary,
        descriptionOf(row),
        row.get("currency", String.class),
        row.get("valid_from", OffsetDateTime.class),
        row.get("valid_to", OffsetDateTime.class),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
        noteOf(row),
        versionOf(row),
        scopes,
        PriceListRules.coverageOf(scopes),
        createdAtOf(row),
        row.get("updated_at", OffsetDateTime.class));
  }

  private String descriptionOf(org.jooq.Record row) {
    var value = row.get("description", String.class);
    return value == null ? "" : value;
  }

  private String noteOf(org.jooq.Record row) {
    var value = row.get("note", String.class);
    return value == null ? "" : value;
  }

  private long versionOf(org.jooq.Record row) {
    var value = row.get("version", Long.class);
    return value == null ? 0L : value;
  }

  private OffsetDateTime createdAtOf(org.jooq.Record row) {
    return row.get("created_at", OffsetDateTime.class);
  }

  /** 整体替换适用范围（同一事务里删 + 插：要么全换要么全不换）。 */
  private void replaceScopes(
      UUID priceListId, AccessService.Access actor, List<ScopeInput> scopes, ScopeDirectory directory) {
    sql.execute(
        "delete from price_list_scope where tenant_id=" + TENANT + " and price_list_id=?",
        priceListId);
    for (var scope : scopes) {
      sql.execute(
          "insert into price_list_scope(id,tenant_id,price_list_id,scope_type,scope_ref,"
              + "scope_name,platform_code,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,?,?,?,?,now())",
          UUID.randomUUID(),
          actor.tenantId(),
          priceListId,
          scope.type().name(),
          scope.ref(),
          scopeName(scope, directory),
          scope.platformCode());
    }
  }

  /** 范围行的显示名快照：从上游目录里取真实名称。 */
  private String scopeName(ScopeInput scope, ScopeDirectory directory) {
    if (directory == null) return scope.ref();
    return switch (scope.type()) {
      case LEGAL_ENTITY -> {
        var found = directory.legalEntities().stream()
            .filter(e -> e.code().equalsIgnoreCase(scope.ref()))
            .findFirst();
        yield found.map(PriceListLegalEntity::name).orElse(scope.ref());
      }
      case SHOP -> {
        var found = directory.shops().stream()
            .filter(s -> s.code().equalsIgnoreCase(scope.ref()))
            .findFirst();
        yield found.map(PriceListShop::name).orElse(scope.ref());
      }
      case SITE -> {
        var found = directory.sites().stream()
            .filter(s -> s.siteCode().equalsIgnoreCase(scope.ref()))
            .findFirst();
        yield found.map(PriceListSite::siteName).orElse(scope.ref());
      }
    };
  }

  /**
   * 核对适用范围真实存在。
   *
   * <p>🔴 只读本次用得到的上游：法人来自 identity、店铺来自 channel、站点来自 channel。
   * 上游读不到 ⇒ 503（不是「校验通过」）；编码不在目录里 ⇒ 400。
   *
   * @return 本次读到的上游目录（供显示名快照复用；没有范围时是 null）
   */
  private ScopeDirectory verifyScopes(List<ScopeInput> scopes, String bearer) {
    if (scopes.isEmpty()) return null;
    var legalEntities = new ArrayList<PriceListLegalEntity>();
    var shops = new ArrayList<PriceListShop>();
    var sites = new ArrayList<PriceListSite>();
    boolean needLegalEntity = scopes.stream().anyMatch(s -> s.type() == ScopeType.LEGAL_ENTITY);
    boolean needShop = scopes.stream().anyMatch(s -> s.type() == ScopeType.SHOP);
    boolean needSite = scopes.stream().anyMatch(s -> s.type() == ScopeType.SITE);
    if (needLegalEntity) {
      for (var entity : scope.legalEntities(bearer)) {
        legalEntities.add(
            new PriceListLegalEntity(
                entity.id(), entity.code(), entity.name(), entity.baseCurrency(), entity.status()));
      }
    }
    if (needShop) {
      for (var shop : scope.shops(bearer)) {
        shops.add(
            new PriceListShop(
                shop.code(),
                shop.name(),
                shop.platformCode(),
                null,
                null,
                shop.status(),
                shop.siteCodes()));
      }
    }
    if (needSite) {
      for (var site : platforms.sites(bearer)) {
        sites.add(
            new PriceListSite(
                site.platformCode(),
                site.platformName(),
                site.siteCode(),
                site.siteName(),
                site.countryCode(),
                site.defaultCurrency()));
      }
    }
    var directory = new ScopeDirectory(legalEntities, shops, sites, "");
    for (var item : scopes) {
      switch (item.type()) {
        case LEGAL_ENTITY -> {
          var found =
              legalEntities.stream()
                  .filter(e -> e.code().equalsIgnoreCase(item.ref()))
                  .findFirst();
          if (found.isEmpty()) {
            throw badRequest(
                "法人 "
                    + item.ref()
                    + " 不存在（身份服务里没有这个法人编码）—— 适用范围必须指向真实存在的主体");
          }
        }
        case SHOP -> {
          var found =
              shops.stream().filter(s -> s.code().equalsIgnoreCase(item.ref())).findFirst();
          if (found.isEmpty()) {
            throw badRequest(
                "店铺 "
                    + item.ref()
                    + " 不存在（渠道服务里没有这个店铺编码）—— 适用范围必须指向真实存在的主体");
          }
        }
        case SITE -> {
          var found =
              sites.stream()
                  .filter(s -> s.siteCode().equalsIgnoreCase(item.ref()))
                  .findFirst();
          if (found.isEmpty()) {
            throw badRequest(
                "站点 "
                    + item.ref()
                    + " 不存在（渠道服务的平台目录里没有这个站点码）—— 适用范围必须指向真实存在的主体");
          }
          if (item.platformCode() != null
              && !item.platformCode().equalsIgnoreCase(found.get().platformCode())) {
            throw badRequest(
                "站点 "
                    + item.ref()
                    + " 属于平台 "
                    + found.get().platformCode()
                    + "，不是 "
                    + item.platformCode()
                    + " —— 站点与平台是渠道服务里的同一份事实，不允许对不上");
          }
        }
      }
    }
    return directory;
  }

  /** 归一化 + 校验：把请求变成内部可用的形状，不合法就 400。 */
  private PriceListInput normalize(PriceListInput input) {
    if (input == null) throw badRequest("价目表定义不能为空");
    String code;
    String currency;
    try {
      code = PriceListRules.normalizeCode(input.code());
      currency = PriceListRules.normalizeCurrency(input.currency());
    } catch (IllegalArgumentException e) {
      throw badRequest(e.getMessage());
    }
    var name = input.name() == null ? "" : input.name().trim();
    if (name.isEmpty()) throw badRequest("价目表名称必填");
    if (input.validFrom() == null) throw badRequest("生效起点必填");
    if (!PriceListRules.validInterval(input.validFrom(), input.validTo())) {
      throw badRequest(
          "生效终点必须晚于生效起点（生效区间是半开区间 [起点, 终点)，终点为空 = 长期有效）");
    }
    List<ScopeInput> scopes;
    try {
      scopes = PriceListRules.normalizeScopes(input.scopes());
    } catch (IllegalArgumentException e) {
      throw badRequest(e.getMessage());
    }
    return new PriceListInput(
        code,
        name,
        input.description() == null ? "" : input.description().trim(),
        currency,
        input.validFrom(),
        input.validTo(),
        input.enabled() == null ? Boolean.TRUE : input.enabled(),
        input.note() == null ? "" : input.note().trim(),
        scopes,
        input.version());
  }

  private static int intOf(org.jooq.Record row, String column) {
    var value = row.get(column, Integer.class);
    return value == null ? 0 : value;
  }

  /** 把参数列表与额外的定值拼成一个参数数组（jOOQ 不吃可变参数列表）。 */
  private static Object[] concat(List<Object> params, Object... extra) {
    var all = new ArrayList<Object>(params);
    for (var value : extra) {
      all.add(value);
    }
    return all.toArray();
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
