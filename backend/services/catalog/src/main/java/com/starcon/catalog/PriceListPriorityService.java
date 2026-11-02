package com.starcon.catalog;

import static com.starcon.catalog.PriceListPriorityModels.*;
import static com.starcon.catalog.PriceListPriorityRules.Rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.catalog.PriceListModels.ScopeInput;
import com.starcon.catalog.PriceListPriorityModels.ConflictDimension;
import com.starcon.catalog.PriceListPriorityRules.Context;
import com.starcon.catalog.PriceListPriorityModels.ConflictDetail;
import com.starcon.catalog.PriceListPriorityModels.ConflictReport;
import com.starcon.catalog.PriceListPriorityModels.ExcludedPriceList;
import com.starcon.catalog.PriceListPriorityModels.ExclusionReason;
import com.starcon.catalog.PriceListPriorityModels.PriorityNote;
import com.starcon.catalog.PriceListPriorityModels.PriorityRuleInput;
import com.starcon.catalog.PriceListPriorityModels.PriorityRuleView;
import com.starcon.catalog.PriceListPriorityModels.PriorityRulesInput;
import com.starcon.catalog.PriceListPriorityModels.PriorityRulesSaved;
import com.starcon.catalog.PriceListPriorityModels.PriceListPriorityCapabilities;
import com.starcon.catalog.PriceListPriorityModels.Resolution;
import com.starcon.catalog.PriceListPriorityModels.ResolutionBasis;
import com.starcon.catalog.PriceListPriorityModels.ResolutionCandidate;
import com.starcon.catalog.PriceListPriorityModels.ResolvedPriceList;
import com.starcon.catalog.PriceListPriorityModels.ResolveRequest;
import com.starcon.catalog.PriceListPriorityModels.SkuCoverage;
import com.starcon.catalog.PriceListPriorityModels.TenantConflictOverview;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.1.2.1 优先级与冲突检测。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：定义多张价目表的适用优先级，保存时检测冲突（同 SKU + 同店铺 + 同站点 + 同时段生效的多个价目），冲突即拒绝并列出冲突明细。
 *
 * **输入**：适用范围配置（法人/店铺/站点）；现有生效价目集合。
 *
 * **输出**：冲突检测结果；最终的生效价目解析规则。
 *
 * **关系**：上游 = 5.1.1.1；下游 = 5.3.1.1、5.4 调价。**这是价格正确性的守门员**：允许多价目但必须能唯一解析出"这个 SKU 在这个店铺此刻该卖多少"。
 *
 * </blockquote>
 *
 * <h2>🔴 本类的七条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 优先级是租户可配置的</b>：规则存在 {@code price_list_priority} 表里，
 *       服务层不内置任何租户顺序。
 *   <li><b>🔴 冲突检测走真实 SQL</b>：区间重叠用数据库谓词判
 *       （{@code a.valid_from < coalesce(b.valid_to,'infinity')}），
 *       <b>不</b>把数据捞到内存里逐对比较。
 *   <li><b>🔴 冲突即拒绝</b>：保存时检出**阻断级**冲突 ⇒ 抛 409 并把明细挂上，
 *       事务回滚 ⇒ 什么都没保存（不是「保存后打标」）。
 *   <li><b>🔴 同优先级 ⇒ 不可解析</b>：解析时若最高优先级被多条并列，返回
 *       可解释的冲突错误，<b>绝不</b>随机取一条。
 *   <li><b>🔴 未配置优先级 = 不参与解析</b>（而不是「价格是 0」）：
 *       它仍然出现在冲突报告里并标注原因。
 *   <li><b>🔴 同 SKU 维度未覆盖</b>：价目项表 {@code price_list_item} 属 5.1.1.2，
 *       尚未交付 ⇒ 只做**价目级**检测，并在三处如实声明未覆盖与原因。
 *   <li><b>🔴 租户只来自已认证成员关系</b>（{@link AccessService}），不读
 *       {@code X-Tenant-Id} 头；每条 SQL 的 where 都带显式 {@code tenant_id=}，
 *       RLS 再兜一道。
 * </ol>
 */
@Service
public class PriceListPriorityService {

  /** 租户上下文取法：RLS 已按它隔离，这里带上只是让执行计划走租户前缀的索引。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 上下文（店铺-站点组合）上限：超过它一次算不完 ⇒ 明确报错，不静默截断。 */
  private static final int MAX_CONTEXTS = 5000;

  /** 冲突明细条数上限：超出部分不返回，但总数照实给出（truncated=true）。 */
  private static final int MAX_DETAILS = 500;

  private static final ObjectMapper JSON = new ObjectMapper();

  private final DSLContext sql;
  private final PriceListScopePort scope;
  private final ChannelPlatformPort platforms;

  public PriceListPriorityService(
      DSLContext sql, PriceListScopePort scope, ChannelPlatformPort platforms) {
    this.sql = sql;
    this.scope = scope;
    this.platforms = platforms;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：优先级语义 + 六个维度的覆盖情况 + 「同 SKU 未覆盖」的原因。
   *
   * <p>页面固定显示它 —— 蓝图那句「这是价格正确性的守门员」必须写在脸上。
   */
  public PriceListPriorityCapabilities capabilities() {
    var notes =
        List.of(
            new PriorityNote(
                "优先级是租户可配置的",
                "优先级不是代码常量，是**这个租户自己的配置**（price_list_priority 表）："
                    + "每条规则说「这张价目表在某个店铺/站点/法人下排第几」。"
                    + "各租户的销售场景不同 —— 同一套代码不能替不同租户排先后。"),
            new PriorityNote(
                "数字越小优先级越高",
                "1 是最高优先级。同一上下文里**优先级数字相同**的两张价目表 ⇒ "
                    + "不可唯一解析 ⇒ 保存被拒，并列出冲突明细（哪两张表、哪个店铺、哪个站点、区间重叠在哪）。"),
            new PriorityNote(
                "specificity：站点 > 店铺 > 法人 > 全局",
                "一张价目表可以配「全局默认优先级」，也可以在某个店铺/站点/法人下单独排位。"
                    + "越具体的规则越优先（站点 > 店铺 > 法人 > 全局）。"
                    + "同一张表里同一个上下文只能有一条规则 —— 两条同样具体的规则同时命中会让「哪条生效」变成猜谜。"),
            new PriorityNote(
                "冲突即拒绝",
                "保存优先级规则、保存价目表的适用范围时都会真的跑冲突检测；"
                    + "检出**阻断级**冲突就不保存（409 + 冲突明细清单），"
                    + "不是「保存后打标」。"),
            new PriorityNote(
                "未配置优先级 = 不参与解析",
                "没配优先级的价目表不参与解析（它没有排进这场赛跑），"
                    + "但它**仍会出现在冲突报告里**并注明原因 —— 静默吞掉它的话，"
                    + "运营会以为自己的表在生效。"),
            new PriorityNote(
                "本模块不写价目项",
                "price_list_item 是 **5.1.1.2 的写入所有权**，本模块**不建**那张表、"
                    + "**不读**那张表 ⇒ 「同 SKU」这一维度无法判定（见下面的维度说明）。"));
    return new PriceListPriorityCapabilities(
        List.of(PriorityScopeType.values()),
        notes,
        PriceListPriorityRules.dimensionCoverage(),
        "数字越小优先级越高（1 最高）；同一上下文里数字相同 ⇒ 不可唯一解析 ⇒ 拒绝保存。",
        "同优先级并列时**不可唯一解析**：返回可解释的冲突错误，绝不随机取一条。",
        "规则上下文 = 全局（默认）+ 法人 / 店铺 / 站点（与 5.1.1.1 的适用范围同一套词表）。",
        "上游 = 5.1.1.1 的价目表与适用范围（同一服务、同一库）；"
            + "店铺与站点归属关系、店铺与法人的关系来自 channel / identity 的授权只读端点，"
            + "catalog 库里没有它们的副本。",
        "下游 = 5.3.1.1 测算、5.4 调价 —— 它们按本模块解析出来的生效价目表取价。",
        PriceListPriorityRules.skuDimensionNote());
  }

  // ================================================================ 规则读写

  /** 本租户全部优先级规则（页面上的规则台账）。 */
  public List<PriorityRuleView> allRules() {
    return ruleViews(null);
  }

  /** 某张价目表的优先级规则。 */
  public List<PriorityRuleView> rules(UUID priceListId) {
    requireList(priceListId);
    return ruleViews(priceListId);
  }

  /**
   * 保存某张价目表的优先级规则（整体替换）。
   *
   * <p>🔴 保存后**立刻**跑真冲突检测（用的是**刚落库的这套**规则）：
   * 检出阻断级冲突 ⇒ 抛 409 ⇒ 整个事务回滚 ⇒ 新规则与旧规则都没留下。
   */
  public PriorityRulesSaved saveRules(
      AccessService.Access actor, UUID priceListId, PriorityRulesInput input, String bearer) {
    var list = listHeader(priceListId);
    List<PriorityRuleInput> normalized;
    try {
      normalized = PriceListPriorityRules.normalizeRules(input == null ? null : input.rules());
    } catch (IllegalArgumentException e) {
      throw badRequest(e.getMessage());
    }

    sql.execute(
        "delete from price_list_priority where tenant_id=" + TENANT + " and price_list_id=?",
        priceListId);
    for (var rule : normalized) {
      sql.execute(
          "insert into price_list_priority(id,tenant_id,price_list_id,scope_type,scope_ref,priority,"
              + "note,created_by,created_at,updated_at,version)"
              + " values(?::uuid,?::uuid,?::uuid,?,?,?,?,?::uuid,now(),now(),0)",
          UUID.randomUUID(),
          actor.tenantId(),
          priceListId,
          rule.scopeType().name(),
          rule.scopeRef(),
          rule.priority(),
          rule.note(),
          actor.userId());
    }

    var report = detect(priceListId, bearer);
    if (report.blockingCount() > 0) {
      throw new PriceListPriorityConflictException(
          report,
          "优先级未保存：检出 "
              + report.blockingCount()
              + " 条阻断级冲突（同一店铺/站点上生效区间重叠，且有效优先级相同 ⇒ 不可唯一解析）。"
              + "请按冲突明细调整优先级或适用范围后重试。");
    }
    return new PriorityRulesSaved(priceListId, list.code(), ruleViews(priceListId), report);
  }

  // ================================================================ 冲突检测

  /** 某张价目表的冲突明细（蓝图输出句：「冲突检测结果」）。 */
  public ConflictReport conflicts(UUID priceListId, String bearer) {
    var list = listHeader(priceListId);
    var report = detect(priceListId, bearer);
    return new ConflictReport(
        list.id(),
        list.code(),
        report.conflictCount(),
        report.blockingCount(),
        report.conflicts(),
        report.dimensionCoverage(),
        report.skuDimensionNote(),
        report.rankedPriceListCount(),
        report.truncated(),
        report.pairCount(),
        report.note());
  }

  /** 全租户冲突概览。 */
  public TenantConflictOverview overview(String bearer) {
    var report = detect(null, bearer);
    var listCount =
        sql.fetchOne("select count(*) from price_list where tenant_id=" + TENANT)
            .get(0, Long.class);
    return new TenantConflictOverview(
        report.rankedPriceListCount(),
        listCount.intValue(),
        report.pairCount(),
        report.blockingCount(),
        report.conflicts(),
        report.dimensionCoverage(),
        report.skuDimensionNote(),
        report.note());
  }

  /**
   * 守门员：保存价目表的**适用范围**时调用。
   *
   * <p>🔴 调用点在 5.1.1.1 的保存事务**之内**（适用范围已经写进库、还没提交），
   * 所以这里读到的就是**即将生效**的状态；检出冲突时抛 409 ⇒ 整个保存回滚。
   *
   * <p>🔴 只有**两边都排了位**的价目表才可能构成阻断级冲突：没配优先级的价目表
   * 压根不参与解析（{@link PriceListPriorityRules} 纪律三）。因此本租户一张优先级
   * 规则都没有时，本方法**直接返回**（连上游目录都不读）—— 5.1.1.1 的建表流程不受影响。
   */
  public void assertNoBlockingConflict(UUID priceListId, String bearer) {
    var rules = ruleIndex();
    if (!rules.containsKey(priceListId)) {
      // 这张表没配优先级 ⇒ 它不参与解析 ⇒ 不可能与谁并列 ⇒ 不构成阻断级冲突。
      return;
    }
    var report = detect(priceListId, bearer);
    if (report.blockingCount() > 0) {
      throw new PriceListPriorityConflictException(
          report,
          "未保存：检出 "
              + report.blockingCount()
              + " 条阻断级冲突（同一店铺/站点上生效区间重叠，且有效优先级相同 ⇒ 不可唯一解析）。"
              + "请按冲突明细调整适用范围或优先级后重试。");
    }
  }

  /**
   * 冲突检测（核心）。
   *
   * @param targetListId 只看与这张表有关的配对；null = 全租户
   */
  private ConflictReport detect(UUID targetListId, String bearer) {
    var rulesByList = ruleIndex();
    int rankedLists = rulesByList.size();

    // 🔴 本租户一张优先级规则都没有 ⇒ 没有并列可言 ⇒ 直接返回（不读上游目录）。
    if (rankedLists == 0) {
      return new ConflictReport(
          targetListId,
          null,
          0,
          0,
          List.of(),
          PriceListPriorityRules.dimensionCoverage(),
          PriceListPriorityRules.skuDimensionNote(),
          0,
          false,
          0,
          "本租户还没有配置任何优先级规则 ⇒ 所有价目表都「不参与解析」，"
              + "此时没有并列可言（不是「没有冲突」，而是「优先级还没排」）。");
    }

    var contexts = universe(bearer);
    var details = new ArrayList<ConflictDetail>();
    var pairs = new LinkedHashMap<String, Integer>();

    var rows = sql.fetch(conflictSql(targetListId), conflictParams(targetListId, contexts));

    for (var row : rows) {
      var leftId = row.get("left_id", UUID.class);
      var rightId = row.get("right_id", UUID.class);
      var shop = row.get("shop_code", String.class);
      var site = row.get("site_code", String.class);
      var legal = row.get("legal_entity_code", String.class);
      var ctx = new PriceListPriorityRules.Context(shop, site, legal);

      var leftRule = PriceListPriorityRules.effectiveRule(rulesByList.get(leftId), ctx);
      var rightRule = PriceListPriorityRules.effectiveRule(rulesByList.get(rightId), ctx);
      boolean tie = PriceListPriorityRules.tie(leftRule, rightRule);
      boolean leftEnabled = Boolean.TRUE.equals(row.get("left_enabled", Boolean.class));
      boolean rightEnabled = Boolean.TRUE.equals(row.get("right_enabled", Boolean.class));
      boolean blocking = tie && leftEnabled && rightEnabled;

      var dimensions = new ArrayList<ConflictDimension>();
      if (shop != null && !shop.isBlank()) dimensions.add(ConflictDimension.SHOP);
      if (site != null && !site.isBlank()) dimensions.add(ConflictDimension.SITE);
      if (legal != null && !legal.isBlank()) dimensions.add(ConflictDimension.LEGAL_ENTITY);
      dimensions.add(ConflictDimension.TIME);
      if (tie) dimensions.add(ConflictDimension.PRIORITY);

      pairs.merge(leftId + "|" + rightId, 1, Integer::sum);

      if (details.size() < MAX_DETAILS) {
        details.add(
            new ConflictDetail(
                dimensions,
                leftId,
                row.get("left_code", String.class),
                row.get("left_name", String.class),
                rightId,
                row.get("right_code", String.class),
                row.get("right_name", String.class),
                shop,
                site,
                legal,
                row.get("overlap_from", OffsetDateTime.class),
                row.get("overlap_to", OffsetDateTime.class),
                leftRule == null ? null : leftRule.priority(),
                rightRule == null ? null : rightRule.priority(),
                reasonOf(tie, leftRule, rightRule, leftEnabled, rightEnabled),
                blocking));
      }
    }

    boolean truncated = rows.size() > MAX_DETAILS;
    return new ConflictReport(
        targetListId,
        null,
        details.size(),
        (int) details.stream().filter(ConflictDetail::blocking).count(),
        details,
        PriceListPriorityRules.dimensionCoverage(),
        PriceListPriorityRules.skuDimensionNote(),
        rankedLists,
        truncated,
        pairs.size(),
        (truncated
                ? "冲突明细超过 "
                    + MAX_DETAILS
                    + " 条，下面只列出前 "
                    + MAX_DETAILS
                    + " 条（truncated=true，实际更多）"
                : "检出 " + pairs.size() + " 组冲突配对")
            + "（同一店铺/站点/法人上下文 + 生效区间重叠）。"
            + "🔴 阻断级 = 两边都排了位且有效优先级相同 ⇒ 不可唯一解析 ⇒ 保存被拒。");
  }

  /** 冲突原因（一句话，可解释）。 */
  private String reasonOf(
      boolean tie, Rule leftRule, Rule rightRule, boolean leftEnabled, boolean rightEnabled) {
    var scopeText = "两张价目表覆盖同一上下文";
    if (tie) {
      return scopeText
          + "且生效区间重叠，两边有效优先级相同（"
          + leftRule.priority()
          + " = "
          + rightRule.priority()
          + "）⇒ 不可唯一解析";
    }
    if (leftRule == null || rightRule == null) {
      var missing = leftRule == null ? "左" : "右";
      return scopeText
          + "且生效区间重叠，但"
          + missing
          + "侧价目表未配置优先级 ⇒ 它不参与解析（请先给它配优先级，否则它的价格不会出现在解析结果里）";
    }
    if (!leftEnabled || !rightEnabled) {
      return scopeText
          + "且生效区间重叠，但有一方已停用 ⇒ 当前不参与定价；"
          + "重新启用之前必须先把优先级排开（否则会立刻变成不可解析）";
    }
    return scopeText
        + "且生效区间重叠，但两边优先级不同（"
        + leftRule.priority()
        + " / "
        + rightRule.priority()
        + "）⇒ 可唯一解析（不阻断）";
  }

  // ================================================================ 解析

  /**
   * 解析「这个 SKU 在这个店铺此刻该卖多少」。
   *
   * <p>🔴 返回**一条**确定的生效价目 + **解析依据**（命中了哪条优先级规则）。
   * 最高优先级被多条并列 ⇒ 抛 {@link PriceListPriorityAmbiguityException}
   * （409 + 并列候选清单），**绝不**随机取一条。
   */
  public Resolution resolve(ResolveRequest request, String bearer) {
    if (request == null || request.shopCode() == null || request.shopCode().isBlank()) {
      throw badRequest("必须给出店铺编码 —— 解析的上下文是「哪个店铺 / 哪个站点 / 哪一刻」");
    }
    var shopCode = request.shopCode().trim();
    var siteCode = request.siteCode() == null ? null : request.siteCode().trim();
    if (siteCode != null && siteCode.isEmpty()) siteCode = null;

    var ctx = contextFor(shopCode, siteCode, bearer);
    // 🔴 判定时刻：给了就用给的；没给就用**数据库时钟**（与 5.1.1.1 同一口径，
    // 不混用 JVM 墙钟 —— 那是跨时钟域比较）。
    var at = request.at() == null ? dbNow() : request.at();

    var rows =
        sql.fetch(
            "select p.id, p.code, p.name, p.currency, p.valid_from, p.valid_to, p.enabled,"
                + " (p.valid_from <= ?::timestamptz and (p.valid_to is null or p.valid_to > ?::timestamptz)) as effective_at,"
                + " (exists (select 1 from price_list_scope s where s.tenant_id=p.tenant_id"
                + "   and s.price_list_id=p.id and s.scope_type='SHOP' and upper(s.scope_ref)=upper(?))"
                + "  or exists (select 1 from price_list_scope s where s.tenant_id=p.tenant_id"
                + "   and s.price_list_id=p.id and s.scope_type='SITE' and upper(s.scope_ref)=upper(?))"
                + "  or exists (select 1 from price_list_scope s where s.tenant_id=p.tenant_id"
                + "   and s.price_list_id=p.id and s.scope_type='LEGAL_ENTITY' and upper(s.scope_ref)=upper(?))"
                + "  or not exists (select 1 from price_list_scope s2 where s2.tenant_id=p.tenant_id"
                + "   and s2.price_list_id=p.id)) as covers_context"
                + " from price_list p where p.tenant_id="
                + TENANT
                + " order by p.code",
            resolveParams(at, ctx));

    var rulesByList = ruleIndex();
    var candidates = new ArrayList<ResolutionCandidate>();
    var excluded = new ArrayList<ExcludedPriceList>();

    for (var row : rows) {
      if (!Boolean.TRUE.equals(row.get("covers_context", Boolean.class))) continue;
      var id = row.get("id", UUID.class);
      if (!Boolean.TRUE.equals(row.get("enabled", Boolean.class))) {
        excluded.add(
            new ExcludedPriceList(
                id,
                row.get("code", String.class),
                row.get("name", String.class),
                ExclusionReason.DISABLED,
                ExclusionReason.DISABLED.label(),
                "这张价目表已停用 ⇒ 不参与定价。"));
        continue;
      }
      if (!Boolean.TRUE.equals(row.get("effective_at", Boolean.class))) {
        excluded.add(
            new ExcludedPriceList(
                id,
                row.get("code", String.class),
                row.get("name", String.class),
                ExclusionReason.NOT_EFFECTIVE,
                ExclusionReason.NOT_EFFECTIVE.label(),
                "生效区间 ["
                    + row.get("valid_from", OffsetDateTime.class)
                    + ", "
                    + row.get("valid_to", OffsetDateTime.class)
                    + ") 不含判定时刻 "
                    + at
                    + " ⇒ 此刻不生效。"));
        continue;
      }
      var rule = PriceListPriorityRules.effectiveRule(rulesByList.get(id), ctx);
      if (rule == null) {
        excluded.add(
            new ExcludedPriceList(
                id,
                row.get("code", String.class),
                row.get("name", String.class),
                ExclusionReason.NO_PRIORITY,
                ExclusionReason.NO_PRIORITY.label(),
                "这张价目表没有配置优先级 ⇒ 它不参与解析（配了优先级才会进这场赛跑）。"));
        continue;
      }
      candidates.add(
          new ResolutionCandidate(
              id,
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("currency", String.class),
              rule.priority(),
              rule.scopeType(),
              rule.scopeType().label(),
              rule.scopeRef(),
              row.get("valid_from", OffsetDateTime.class),
              row.get("valid_to", OffsetDateTime.class),
              false));
    }

    candidates.sort(
        Comparator.comparingInt(ResolutionCandidate::priority)
            .thenComparing(c -> c.priceListCode() == null ? "" : c.priceListCode()));
    var skuCoverage = new SkuCoverage(false, request.sku(), PriceListPriorityRules.skuDimensionNote());

    if (candidates.isEmpty()) {
      return new Resolution(
          false,
          "没有已排位、此刻生效且覆盖这个上下文的价目表 ⇒ 这一刻没有可解析的价格。"
              + excludedSummary(excluded),
          null,
          null,
          candidates,
          excluded,
          skuCoverage,
          shopCode,
          siteCode,
          ctx.legalEntityCode(),
          at.toString());
    }

    var top = candidates.get(0).priority();
    var tied = candidates.stream().filter(c -> c.priority() == top).toList();
    if (tied.size() > 1) {
      // 🔴 同优先级并列 ⇒ 不可解析。绝不随机取一条。
      throw new PriceListPriorityAmbiguityException(
          new Resolution(
              false,
              "不可解析："
                  + tied.size()
                  + " 张价目表在同一上下文里优先级相同（"
                  + top
                  + "）⇒ 无法唯一确定这一刻该用哪张表。请把它们的优先级排开。",
              null,
              null,
              candidates,
              excluded,
              skuCoverage,
              shopCode,
              siteCode,
              ctx.legalEntityCode(),
              at.toString()));
    }

    var winner = tied.get(0);
    var winnerRule = PriceListPriorityRules.effectiveRule(rulesByList.get(winner.priceListId()), ctx);
    var basis =
        new ResolutionBasis(
            winner.priority(),
            null,
            winnerRule.scopeType(),
            winnerRule.scopeType().label(),
            winnerRule.scopeRef(),
            "命中「"
                + winnerRule.scopeType().label()
                + " "
                + winnerRule.scopeRef()
                + "」的优先级规则（"
                + winnerRule.priority()
                + "）—— 它是这一上下文里优先级最高且唯一的一条 ⇒ 这一刻该用这张表。");

    var finalCandidates = new ArrayList<ResolutionCandidate>();
    for (var candidate : candidates) {
      finalCandidates.add(
          new ResolutionCandidate(
              candidate.priceListId(),
              candidate.priceListCode(),
              candidate.priceListName(),
              candidate.currency(),
              candidate.priority(),
              candidate.ruleScopeType(),
              candidate.ruleScopeLabel(),
              candidate.ruleScopeRef(),
              candidate.validFrom(),
              candidate.validTo(),
              candidate.priceListId().equals(winner.priceListId())));
    }

    return new Resolution(
        true,
        "已唯一解析：这一刻在店铺 "
            + shopCode
            + (siteCode == null ? "" : " 的站点 " + siteCode)
            + " 生效的是「"
            + winner.priceListCode()
            + "」。",
        new ResolvedPriceList(
            winner.priceListId(),
            winner.priceListCode(),
            winner.priceListName(),
            winner.currency()),
        basis,
        finalCandidates,
        excluded,
        skuCoverage,
        shopCode,
        siteCode,
        ctx.legalEntityCode(),
        at.toString());
  }

  private String excludedSummary(List<ExcludedPriceList> excluded) {
    if (excluded.isEmpty()) return "";
    var counts = new LinkedHashMap<String, Integer>();
    for (var item : excluded) {
      counts.merge(item.reasonLabel(), 1, Integer::sum);
    }
    var parts = new ArrayList<String>();
    for (var entry : counts.entrySet()) {
      parts.add(entry.getKey() + " " + entry.getValue() + " 张");
    }
    return "被排除的价目表：" + String.join(" / ", parts) + "。";
  }

  // ================================================================ 上下文（店铺 / 站点 / 法人）

  /**
   * 解析上下文：店铺 + （可选）站点 + 店铺所属法人。
   *
   * <p>🔴 店铺与站点的关系来自 channel（2.3.1.1 / 2.1.1.1）、店铺与法人的关系来自
   * identity（17.1.2.2），全部走授权只读端点 —— catalog 库里没有它们的副本
   * （跨服务 FK / 跨库 JOIN 被 AGENTS.md 禁止）。
   */
  private Context contextFor(String shopCode, String siteCode, String bearer) {
    var shop =
        scope.shops(bearer).stream()
            .filter(s -> s.code().equalsIgnoreCase(shopCode))
            .findFirst()
            .orElseThrow(
                () ->
                    badRequest(
                        "店铺 "
                            + shopCode
                            + " 不存在（渠道服务里没有这个店铺编码）—— 解析需要一个真实的店铺上下文"));
    String legalEntityCode = null;
    if (shop.legalEntityId() != null) {
      for (var entity : scope.legalEntities(bearer)) {
        if (entity.id().equals(shop.legalEntityId())) {
          legalEntityCode = entity.code();
          break;
        }
      }
    }
    return new Context(shop.code(), siteCode, legalEntityCode);
  }

  /**
   * 冲突检测的上下文全集：每个（店铺, 站点）组合一行。
   *
   * <p>来源：channel 的店铺目录（店铺 → 它名下的站点）与平台目录（没有归属店铺的站点）。
   * 有了它，「按店铺的表」与「按站点的表」撞上时才能被发现 ——
   * 只比编码相等会漏掉这一类。
   */
  private List<Context> universe(String bearer) {
    var entities = scope.legalEntities(bearer);
    var byId = new HashMap<UUID, String>();
    for (var entity : entities) {
      byId.put(entity.id(), entity.code());
    }
    var shops = scope.shops(bearer);
    var sites = platforms.sites(bearer);
    var contexts = new ArrayList<Context>();
    var covered = new java.util.HashSet<String>();
    for (var shop : shops) {
      var legalEntityCode = shop.legalEntityId() == null ? null : byId.get(shop.legalEntityId());
      var siteCodes = shop.siteCodes();
      if (siteCodes == null || siteCodes.isEmpty()) {
        contexts.add(new Context(shop.code(), null, legalEntityCode));
        continue;
      }
      for (var siteCode : siteCodes) {
        covered.add(siteCode.trim().toUpperCase(Locale.ROOT));
        contexts.add(new Context(shop.code(), siteCode.trim(), legalEntityCode));
      }
    }
    for (var site : sites) {
      if (site.siteCode() == null || site.siteCode().isBlank()) continue;
      if (covered.contains(site.siteCode().trim().toUpperCase(Locale.ROOT))) continue;
      contexts.add(new Context(null, site.siteCode().trim(), null));
    }
    if (contexts.size() > MAX_CONTEXTS) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "适用范围目录太大（"
              + contexts.size()
              + " 个店铺-站点组合，超过一次冲突检测的上限 "
              + MAX_CONTEXTS
              + "）⇒ 本次检测没有执行（不是「没有冲突」）");
    }
    return contexts;
  }

  // ================================================================ SQL 片段

  /**
   * 冲突检测 SQL。
   *
   * <p>🔴 区间重叠是**数据库谓词**：
   * {@code a.valid_from < coalesce(b.valid_to,'infinity')} 且
   * {@code b.valid_from < coalesce(a.valid_to,'infinity')} ——
   * 半开区间 [起点, 终点) 的标准重叠判据，首尾相接的两张表**不算**重叠。
   */
  private String conflictSql(UUID targetListId) {
    var pairFilter =
        targetListId == null
            ? ""
            : " and (a.price_list_id = ?::uuid or b.price_list_id = ?::uuid)";
    return "with ctx(shop_code, site_code, legal_entity_code) as ("
        + " select v.shop_code, v.site_code, v.legal_entity_code"
        + " from jsonb_to_recordset(?::jsonb) as v(shop_code text, site_code text, legal_entity_code text)"
        + "),"
        + "effective_scope(tenant_id, price_list_id, scope_type, scope_ref) as ("
        + " select tenant_id, price_list_id, scope_type, scope_ref from price_list_scope"
        + " where tenant_id="
        + TENANT
        + "),"
        + "cover as ("
        + " select p.id as price_list_id, p.code as price_list_code, p.name as price_list_name,"
        + "        p.enabled as enabled, p.valid_from as valid_from, p.valid_to as valid_to,"
        + "        c.shop_code as shop_code, c.site_code as site_code,"
        + "        c.legal_entity_code as legal_entity_code"
        + " from price_list p"
        + " join ctx c on ("
        + "   exists (select 1 from effective_scope s where s.tenant_id=p.tenant_id"
        + "     and s.price_list_id=p.id and s.scope_type='SHOP' and upper(s.scope_ref)=upper(c.shop_code))"
        + "   or exists (select 1 from effective_scope s where s.tenant_id=p.tenant_id"
        + "     and s.price_list_id=p.id and s.scope_type='SITE' and upper(s.scope_ref)=upper(c.site_code))"
        + "   or exists (select 1 from effective_scope s where s.tenant_id=p.tenant_id"
        + "     and s.price_list_id=p.id and s.scope_type='LEGAL_ENTITY'"
        + "     and upper(s.scope_ref)=upper(c.legal_entity_code))"
        + "   or not exists (select 1 from effective_scope s2 where s2.tenant_id=p.tenant_id"
        + "     and s2.price_list_id=p.id)"
        + " )"
        + " where p.tenant_id="
        + TENANT
        + ")"
        + " select a.price_list_id as left_id, b.price_list_id as right_id,"
        + "        a.price_list_code as left_code, a.price_list_name as left_name,"
        + "        b.price_list_code as right_code, b.price_list_name as right_name,"
        + "        a.enabled as left_enabled, b.enabled as right_enabled,"
        + "        a.shop_code as shop_code, a.site_code as site_code,"
        + "        a.legal_entity_code as legal_entity_code,"
        + "        greatest(a.valid_from, b.valid_from) as overlap_from,"
        + "        nullif(least(coalesce(a.valid_to,'infinity'::timestamptz),"
        + "                    coalesce(b.valid_to,'infinity'::timestamptz)),"
        + "                   'infinity'::timestamptz) as overlap_to"
        + " from cover a join cover b"
        + "   on a.price_list_id < b.price_list_id"
        + "  and a.shop_code is not distinct from b.shop_code"
        + "  and a.site_code is not distinct from b.site_code"
        + " where a.valid_from < coalesce(b.valid_to,'infinity'::timestamptz)"
        + "   and b.valid_from < coalesce(a.valid_to,'infinity'::timestamptz)"
        + pairFilter
        + " order by a.price_list_id, b.price_list_id,"
        + "          a.shop_code nulls first, a.site_code nulls first"
        + " limit ?";
  }

  /** 冲突检测的参数（顺序必须与 {@link #conflictSql} 里的占位符一致）。 */
  private Object[] conflictParams(UUID targetListId, List<Context> contexts) {
    var params = new ArrayList<Object>();
    params.add(contextsJson(contexts));
    if (targetListId != null) {
      params.add(targetListId);
      params.add(targetListId);
    }
    params.add(MAX_DETAILS + 1);
    return params.toArray();
  }

  /** 上下文全集的 JSON（{@code jsonb_to_recordset} 吃它）。 */
  private String contextsJson(List<Context> contexts) {
    var rows = new ArrayList<java.util.Map<String, Object>>();
    for (var context : contexts) {
      var row = new java.util.LinkedHashMap<String, Object>();
      row.put("shop_code", context.shopCode());
      row.put("site_code", context.siteCode());
      row.put("legal_entity_code", context.legalEntityCode());
      rows.add(row);
    }
    return writeJson(rows);
  }

  private String writeJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("无法序列化冲突检测参数", e);
    }
  }

  /** 解析查询的参数（判定时刻 + 上下文三段）。 */
  private Object[] resolveParams(OffsetDateTime at, Context ctx) {
    return new Object[] {at, at, ctx.shopCode(), ctx.siteCode(), ctx.legalEntityCode()};
  }

  /** 数据库时钟（与 5.1.1.1 的「此刻是否生效」同一口径）。 */
  private OffsetDateTime dbNow() {
    return sql.fetchOne("select now()").get(0, OffsetDateTime.class);
  }

  // ================================================================ 规则索引

  /** 某张价目表（或全部）的优先级规则 → 视图。 */
  private List<PriorityRuleView> ruleViews(UUID priceListId) {
    var where = priceListId == null ? "" : " and p.price_list_id=?";
    var params = priceListId == null ? new Object[] {} : new Object[] {priceListId};
    var rows =
        sql.fetch(
            "select p.id, p.price_list_id, p.scope_type, p.scope_ref, p.priority, p.note,"
                + " p.created_at, p.updated_at, l.code as price_list_code, l.name as price_list_name"
                + " from price_list_priority p"
                + " join price_list l on l.tenant_id=p.tenant_id and l.id=p.price_list_id"
                + " where p.tenant_id="
                + TENANT
                + where
                + " order by l.code,"
                + " case p.scope_type when 'GLOBAL' then 0 when 'LEGAL_ENTITY' then 1"
                + "      when 'SHOP' then 2 else 3 end,"
                + " p.scope_ref",
            params);
    var out = new ArrayList<PriorityRuleView>();
    for (var row : rows) {
      var type = PriorityScopeType.parse(row.get("scope_type", String.class));
      out.add(
          new PriorityRuleView(
              row.get("id", UUID.class),
              row.get("price_list_id", UUID.class),
              row.get("price_list_code", String.class),
              row.get("price_list_name", String.class),
              type,
              type == null ? null : type.label(),
              row.get("scope_ref", String.class),
              row.get("priority", Integer.class),
              row.get("note", String.class),
              row.get("created_at", OffsetDateTime.class),
              row.get("updated_at", OffsetDateTime.class)));
    }
    return out;
  }

  /**
   * 规则索引：价目表 → 它的规则。
   *
   * <p>🔴 读的是**库里现状** —— 调用点都在事务之内，所以读到的就是即将生效的那套。
   */
  private Map<UUID, List<Rule>> ruleIndex() {
    var map = new LinkedHashMap<UUID, List<Rule>>();
    var rows =
        sql.fetch(
            "select price_list_id, scope_type, scope_ref, priority from price_list_priority"
                + " where tenant_id="
                + TENANT);
    for (var row : rows) {
      var id = row.get("price_list_id", UUID.class);
      var type = PriorityScopeType.parse(row.get("scope_type", String.class));
      if (type == null) continue;
      map.computeIfAbsent(id, k -> new ArrayList<>())
          .add(new Rule(type, row.get("scope_ref", String.class), row.get("priority", Integer.class)));
    }
    return map;
  }

  // ================================================================ 价目表

  /** 价目表表头（不存在 ⇒ 404）。 */
  private ListHeader listHeader(UUID priceListId) {
    var row =
        sql.fetchOne(
            "select l.id, l.code, l.name, l.currency, l.enabled"
                + " from price_list l where l.tenant_id="
                + TENANT
                + " and l.id=?",
            priceListId);
    if (row == null) throw notFound("价目表不存在");
    return new ListHeader(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        row.get("currency", String.class),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)));
  }

  private void requireList(UUID priceListId) {
    listHeader(priceListId);
  }

  /** 价目表表头（内部形状）。 */
  private record ListHeader(UUID id, String code, String name, String currency, boolean enabled) {}

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
