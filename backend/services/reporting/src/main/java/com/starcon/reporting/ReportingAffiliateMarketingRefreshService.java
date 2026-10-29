package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.3.2.2 关联营销效果的**拉取适配器**（上游授权只读 API → 营销位台账 + 指标事实 + 归因事实）。
 *
 * <p>⚠️ <b>本类只写投影</b>，不产生任何业务事实 ——
 * 营销位配置与归因数据仍然在 channel（14.5.2.1 带货归因 / 4.3.2.1 内容回写 / 4.5.1.1 平台表现）。
 *
 * <p><b>🔴 三条纪律</b>：
 *
 * <ol>
 *   <li><b>口径唯一</b>：指标码必须已登记（本模块的口径表），DB 触发器钉死 ⇒
 *       事实不能自己发明指标码；派生指标（ROI / 点击率）**不许有事实行**。
 *   <li><b>归因用规则快照</b>：归因事实**逐字**保存上游那条归因记录自带的规则快照 ——
 *       <b>不用「当前规则」回填历史</b>；事实行按 (营销位, 指标, 日, 币种, 归因规则) 分行 ⇒
 *       <b>ROI 只在同一条规则内部相除</b>。
 *   <li><b>读不到 ≠ 没有数据</b>：上游故障一律翻成失败（{@code FAILED}）→ 整个刷新 503，
 *       <b>不用空集合冒充</b>。
 * </ol>
 *
 * <p><b>🔴 窗口是「重建」不是「叠加」</b>：刷新会先删掉该窗口内的旧行，再整段重写 ——
 * 上游修正了同一天的归因时，旧行必须**消失**而不是留着两份。
 *
 * <p><b>🔴 全部写入在同一个租户事务里</b>：先在内存里把三路上游都取完，**全都取到了**才落库
 * ⇒ 不会出现「归因写进去了、落地流量没写进去」的半张表。
 */
@Service
public class ReportingAffiliateMarketingRefreshService {

  /** 当前窗口最多多少天（含边界）。 */
  static final int MAX_WINDOW_DAYS = 92;

  private final ReportingAffiliateMarketingPort port;
  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingAffiliateMarketingRefreshService(
      ReportingAffiliateMarketingPort port, DSLContext sql, TenantTransactions tenants) {
    this.port = port;
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // 刷新
  // ==========================================================================

  /**
   * 刷新关联营销投影。
   *
   * @param from 当前窗口起始日
   * @param to 当前窗口结束日
   */
  public List<ReportingAffiliateMarketingModels.AffiliateMarketingRefreshResult> refresh(
      UUID tenantId, String bearer, LocalDate from, LocalDate to) {
    // ---- 阶段一：取上游（**一个字都还没落库**）----
    var attribution = fetchAttributions(bearer, from, to);
    var landing = fetchLandingListing(bearer, from, to);

    // 🔴 **上游读不到 ⇒ 整个刷新失败（503）**：不能返回一份「看起来成功、其实少了一半」的投影。
    var failed = new ArrayList<String>();
    for (var result : List.of(attribution.result(), landing.result())) {
      if ("FAILED".equals(result.status())) {
        failed.add(result.source() + "：" + result.note());
      }
    }
    if (!failed.isEmpty()) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "关联营销投影失败（上游读不到，不是「没有数据」）：" + String.join("；", failed));
    }

    // ---- 阶段二：一次租户事务里落库（全写或全不写）----
    var projected = tenants.run(tenantId, () -> write(tenantId, from, to, attribution, landing));
    return List.of(
        withProjected(attribution.result(), projected.attribution()),
        withProjected(landing.result(), projected.landing()));
  }

  // ==========================================================================
  // 上游一：14.5.2.1 带货归因（**达人归因口径**）
  // ==========================================================================

  /** 归因这一路的取数结果。 */
  private record AttributionFetch(
      List<Slot> slots,
      List<Fact> facts,
      List<AttributionFact> attributions,
      ReportingAffiliateMarketingModels.AffiliateMarketingRefreshResult result) {}

  /**
   * 拉归因记录 ⇒ 营销位台账 + 归因事实 + 每日指标事实。
   *
   * <p>🔴 **营销位 = (达人, 活动)**：slot_key 是 {@code 达人id:活动id}，
   * **不含时间** ⇒ 同一对达人 × 活动只有一个营销位。
   */
  private AttributionFetch fetchAttributions(String bearer, LocalDate from, LocalDate to) {
    var slots = new LinkedHashMap<String, Slot>();
    var facts = new LinkedHashMap<String, Fact>();
    var attributions = new ArrayList<AttributionFact>();
    int fetched = 0;
    int outOfWindow = 0;
    int withoutTimestamp = 0;
    int missingCurrency = 0;

    try {
      for (int page = 0; page < ReportingAffiliateMarketingPort.MAX_PAGES; page++) {
        var pageData = port.attributionRecords(page, bearer);
        if (pageData == null || pageData.items() == null || pageData.items().isEmpty()) break;
        for (var row : pageData.items()) {
          if (row == null) continue;
          fetched++;
          if (row.id() == null) continue;
          if (row.influencerId() == null) continue;

          var currency = currency(row.currency());
          var ruleCode = trim(row.ruleCode());
          var slotKey = row.influencerId() + ":" + (row.campaignId() == null ? "-" : row.campaignId());
          var slotLabel =
              (trim(row.influencerName()).isEmpty() ? row.influencerId().toString() : trim(row.influencerName()))
                  + " × "
                  + (trim(row.campaignName()).isEmpty() ? "-" : trim(row.campaignName()));
          slots.putIfAbsent(
              slotKey,
              new Slot(
                  ReportingAffiliateMarketingCatalog.SlotType.SOCIAL_MEDIA.name(),
                  slotKey,
                  slotLabel,
                  ReportingAffiliateMarketingCatalog.Caliber.INFLUENCER_ATTRIBUTION.name(),
                  trim(row.shopCode()),
                  "",
                  null,
                  row.influencerId(),
                  row.campaignId(),
                  row.cooperationId(),
                  "channel",
                  "attribution:" + row.id()));

          var statDate = row.attributedAt() == null ? null : row.attributedAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();

          // 🔴 **币种缺失 ⇒ 金额不落库**（算进某个币种是编造）；订单数与货币无关 ⇒ 照样落。
          BigDecimal attributedGmv = currency.isEmpty() ? null : nz(row.attributedGmv());
          BigDecimal commissionAmount = currency.isEmpty() ? null : nz(row.commissionAmount());
          if (currency.isEmpty()) {
            if (row.attributedGmv() != null || row.commissionAmount() != null) missingCurrency++;
          }

          // ⚠️ **只落窗口内的归因事实**（窗口是「重建」不是「叠加」）：
          //   窗口外的行不进库，刷新结果里如实报出跳过条数。
          if (statDate == null || statDate.isBefore(from) || statDate.isAfter(to)) {
            if (statDate == null) {
              withoutTimestamp++;
            } else {
              outOfWindow++;
            }
            continue;
          }

          attributions.add(
              new AttributionFact(
                  ReportingAffiliateMarketingCatalog.SlotType.SOCIAL_MEDIA.name(),
                  slotKey,
                  ruleCode,
                  row.id(),
                  row.influencerId(),
                  trim(row.influencerName()),
                  row.campaignId(),
                  trim(row.campaignName()),
                  row.cooperationId(),
                  trim(row.shopCode()),
                  currency,
                  trim(row.attributionModel()),
                  trim(row.poolWeightBasis()),
                  row.attributionWindowDays(),
                  trim(row.refundTreatment()),
                  trim(row.commissionBasis()),
                  row.commissionRate(),
                  row.attributionWeight(),
                  attributedGmv,
                  nz(row.attributedOrderCount()),
                  nz(row.effectiveOrderCount()),
                  commissionAmount,
                  row.attributedAt(),
                  statDate));

          // 归因成交金额（**涉币** ⇒ 币种缺失时这一格不落库）。
          if (attributedGmv != null) {
            accumulate(
                facts,
                ReportingAffiliateMarketingCatalog.SlotType.SOCIAL_MEDIA.name(),
                slotKey,
                ReportingAffiliateMarketingCatalog.ATTRIBUTED_GMV,
                statDate,
                currency,
                attributedGmv,
                BigDecimal.ZERO,
                ruleCode);
          }
          // 归因订单数（与货币无关 ⇒ 币种照记，查询期跨币种合计）。
          accumulate(
              facts,
              ReportingAffiliateMarketingCatalog.SlotType.SOCIAL_MEDIA.name(),
              slotKey,
              ReportingAffiliateMarketingCatalog.ATTRIBUTED_ORDERS,
              statDate,
              currency,
              BigDecimal.ZERO,
              nz(row.attributedOrderCount()),
              ruleCode);
          // 营销投入（佣金成本，**涉币**）。
          if (commissionAmount != null) {
            accumulate(
                facts,
                ReportingAffiliateMarketingCatalog.SlotType.SOCIAL_MEDIA.name(),
                slotKey,
                ReportingAffiliateMarketingCatalog.SPEND,
                statDate,
                currency,
                commissionAmount,
                BigDecimal.ZERO,
                ruleCode);
          }
        }
        if (pageData.items().size() < ReportingAffiliateMarketingPort.PAGE_SIZE) break;
      }
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return new AttributionFetch(
          List.of(),
          List.of(),
          List.of(),
          new ReportingAffiliateMarketingModels.AffiliateMarketingRefreshResult(
              "channel-attributions",
              fetched,
              0,
              "FAILED",
              e.getReason() == null ? "上游读不到" : e.getReason()));
    }

    var truncated = fetched >= ReportingAffiliateMarketingPort.PAGE_SIZE * ReportingAffiliateMarketingPort.MAX_PAGES;
    var note = new StringBuilder();
    note.append("归因记录 ").append(fetched).append(" 条（上游**没有时间范围筛选**，本模块按归因时刻切窗口）");
    if (outOfWindow > 0) note.append("，窗口外 ").append(outOfWindow).append(" 条跳过");
    if (withoutTimestamp > 0) note.append("，").append(withoutTimestamp).append(" 条**没有归因时刻**（进不了任何窗口）");
    if (missingCurrency > 0) note.append("，").append(missingCurrency).append(" 条**缺币种**（金额**不落库**）");
    if (truncated) note.append(" ⚠️ 超过单次上限，超出部分**未计入**");
    return new AttributionFetch(
        List.copyOf(slots.values()),
        List.copyOf(facts.values()),
        List.copyOf(attributions),
        new ReportingAffiliateMarketingModels.AffiliateMarketingRefreshResult(
            "channel-attributions", fetched, 0, truncated ? "TRUNCATED" : "SUCCEEDED", note.toString()));
  }

  // ==========================================================================
  // 上游二：4.3.2.1 内容回写 + 4.5.1.1 平台表现（**落地商品口径**）
  // ==========================================================================

  /** 落地商品这一路的取数结果。 */
  private record LandingFetch(
      List<Slot> slots,
      List<Fact> facts,
      ReportingAffiliateMarketingModels.AffiliateMarketingRefreshResult result) {}

  /**
   * 发现 **A+ 营销位**（有内容回写留痕为证）⇒ 落地 listing 的曝光 / 点击。
   *
   * <p>🔴 **营销位存在的证据必须是「真的写过 A+」**：channel 的内容回写留痕里
   * 有一条 {@code field = A_PLUS} 的字段变更 ⇒ 这个 listing 确实有 A+ 内容。
   * 只拿「有人发起过一次 A_PLUS 回写」当证据不行 —— 那一次回写可能被跳过。
   *
   * <p>⚠️ **N+1 有界**：内容回写历史只能逐个 listing 取 ⇒ 最多探测
   * {@link ReportingAffiliateMarketingPort#MAX_CONTENT_PROBES} 个 listing，
   * 触到上限时**如实报 truncated**（而不是悄悄少算）。
   */
  private LandingFetch fetchLandingListing(String bearer, LocalDate from, LocalDate to) {
    var candidates = new LinkedHashSet<UUID>();
    var writebackRows = 0;
    try {
      for (int page = 0; page < ReportingAffiliateMarketingPort.MAX_PAGES; page++) {
        var rows = port.contentWritebacks(page, bearer);
        if (rows == null || rows.isEmpty()) break;
        for (var row : rows) {
          if (row == null) continue;
          writebackRows++;
          if (row.changeTypes() == null || !row.changeTypes().contains("A_PLUS")) continue;
          if (row.listingIds() == null) continue;
          for (var listingId : row.listingIds()) {
            if (listingId != null) candidates.add(listingId);
          }
        }
        if (rows.size() < ReportingAffiliateMarketingPort.PAGE_SIZE) break;
      }
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return new LandingFetch(
          List.of(),
          List.of(),
          new ReportingAffiliateMarketingModels.AffiliateMarketingRefreshResult(
              "channel-listing-content-writeback",
              writebackRows,
              0,
              "FAILED",
              e.getReason() == null ? "上游读不到" : e.getReason()));
    }

    // ---- 逐个 listing 验证「真的写过 A+」----
    var confirmed = new LinkedHashSet<UUID>();
    int probed = 0;
    boolean probeTruncated = false;
    for (var candidate : candidates) {
      if (probed >= ReportingAffiliateMarketingPort.MAX_CONTENT_PROBES) {
        probeTruncated = true;
        break;
      }
      probed++;
      try {
        var history = port.listingContentHistory(candidate, bearer);
        if (history == null) continue;
        for (var entry : history) {
          if (entry == null || entry.changes() == null) continue;
          for (var change : entry.changes()) {
            if (change != null && "A_PLUS".equals(change.field())) {
              confirmed.add(candidate);
              break;
            }
          }
        }
      } catch (org.springframework.web.server.ResponseStatusException e) {
        return new LandingFetch(
            List.of(),
            List.of(),
            new ReportingAffiliateMarketingModels.AffiliateMarketingRefreshResult(
                "channel-listing-content-writeback",
                writebackRows,
                0,
                "FAILED",
                e.getReason() == null ? "上游读不到" : e.getReason()));
      }
    }

    // ---- 平台表现 ⇒ 落地 listing 的曝光 / 点击 ----
    var slots = new LinkedHashMap<String, Slot>();
    var facts = new LinkedHashMap<String, Fact>();
    int performanceRows = 0;
    int missingCurrency = 0;
    try {
      for (int page = 0; page < ReportingAffiliateMarketingPort.MAX_PAGES; page++) {
        var pageData = port.listingPerformance(from, to, page, bearer);
        if (pageData == null || pageData.items() == null || pageData.items().isEmpty()) break;
        for (var row : pageData.items()) {
          if (row == null) continue;
          performanceRows++;
          if (row.listingId() == null) continue;
          if (!confirmed.contains(row.listingId())) continue;
          var statDate = parseDate(row.statDate());
          if (statDate == null) continue;
          var currency = currency(row.currency());
          var slotKey = row.listingId().toString();
          slots.putIfAbsent(
              slotKey,
              new Slot(
                  ReportingAffiliateMarketingCatalog.SlotType.A_PLUS.name(),
                  slotKey,
                  trim(row.title()).isEmpty() ? slotKey : trim(row.title()),
                  ReportingAffiliateMarketingCatalog.Caliber.LANDING_LISTING.name(),
                  trim(row.shopCode()),
                  trim(row.platformCode()),
                  row.listingId(),
                  null,
                  null,
                  null,
                  "channel",
                  "listing:" + row.listingId()));
          if (currency.isEmpty()) missingCurrency++;
          accumulate(
              facts,
              ReportingAffiliateMarketingCatalog.SlotType.A_PLUS.name(),
              slotKey,
              ReportingAffiliateMarketingCatalog.IMPRESSIONS,
              statDate,
              currency,
              BigDecimal.ZERO,
              nz(row.impressions()),
              "");
          accumulate(
              facts,
              ReportingAffiliateMarketingCatalog.SlotType.A_PLUS.name(),
              slotKey,
              ReportingAffiliateMarketingCatalog.CLICKS,
              statDate,
              currency,
              BigDecimal.ZERO,
              nz(row.clicks()),
              "");
        }
        if (pageData.items().size() < ReportingAffiliateMarketingPort.PAGE_SIZE) break;
      }
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return new LandingFetch(
          List.of(),
          List.of(),
          new ReportingAffiliateMarketingModels.AffiliateMarketingRefreshResult(
              "channel-listing-performance",
              performanceRows,
              0,
              "FAILED",
              e.getReason() == null ? "上游读不到" : e.getReason()));
    }

    var truncated =
        performanceRows >= ReportingAffiliateMarketingPort.PAGE_SIZE * ReportingAffiliateMarketingPort.MAX_PAGES;
    var note = new StringBuilder();
    note.append("A+ 营销位 ").append(confirmed.size()).append(" 个（内容回写留痕为证，探测 ")
        .append(probed)
        .append(" 个 listing）；平台表现 ")
        .append(performanceRows)
        .append(" 行（落地商品口径，平台自然日）");
    if (missingCurrency > 0) note.append("，").append(missingCurrency).append(" 行缺币种");
    if (probeTruncated) note.append(" ⚠️ 探测上限已到，**部分候选 listing 未探测**");
    if (truncated) note.append(" ⚠️ 超过单次上限，超出部分**未计入**");
    return new LandingFetch(
        List.copyOf(slots.values()),
        List.copyOf(facts.values()),
        new ReportingAffiliateMarketingModels.AffiliateMarketingRefreshResult(
            "channel-listing-content-writeback+channel-listing-performance",
            performanceRows,
            0,
            (truncated || probeTruncated) ? "TRUNCATED" : "SUCCEEDED",
            note.toString()));
  }

  // ==========================================================================
  // 写库
  // ==========================================================================

  /** 一个营销位（写库前的内存形态）。 */
  private record Slot(
      String slotType,
      String slotKey,
      String slotLabel,
      String caliber,
      String shopCode,
      String platformCode,
      UUID listingId,
      UUID influencerId,
      UUID campaignId,
      UUID cooperationId,
      String sourceService,
      String sourceReference) {}

  /** 一条事实（写库前的内存形态）。 */
  private record Fact(
      String slotType,
      String slotKey,
      String metricCode,
      LocalDate statDate,
      String currency,
      BigDecimal value,
      BigDecimal quantity,
      String attributionRuleCode) {}

  /** 一条归因事实（写库前的内存形态）。 */
  private record AttributionFact(
      String slotType,
      String slotKey,
      String ruleCode,
      UUID attributionId,
      UUID influencerId,
      String influencerName,
      UUID campaignId,
      String campaignName,
      UUID cooperationId,
      String shopCode,
      String currency,
      String attributionModel,
      String poolWeightBasis,
      Integer attributionWindowDays,
      String refundTreatment,
      String commissionBasis,
      BigDecimal commissionRate,
      BigDecimal attributionWeight,
      BigDecimal attributedGmv,
      BigDecimal attributedOrderCount,
      BigDecimal effectiveOrderCount,
      BigDecimal commissionAmount,
      OffsetDateTime attributedAt,
      LocalDate statDate) {}

  /** 落库返回的条数。 */
  private record Projected(int attribution, int landing) {}

  /** 把一条贡献累加到当天的聚合里。 */
  private static void accumulate(
      Map<String, Fact> facts,
      String slotType,
      String slotKey,
      String metricCode,
      LocalDate statDate,
      String currency,
      BigDecimal value,
      BigDecimal quantity,
      String attributionRuleCode) {
    if (value.signum() == 0 && quantity.signum() == 0) return;
    var key =
        String.join(
            "|", slotType, slotKey, metricCode, statDate.toString(), currency, attributionRuleCode);
    var existing = facts.get(key);
    if (existing == null) {
      facts.put(
          key,
          new Fact(
              slotType, slotKey, metricCode, statDate, currency, value, quantity, attributionRuleCode));
      return;
    }
    facts.put(
        key,
        new Fact(
            existing.slotType(),
            existing.slotKey(),
            existing.metricCode(),
            existing.statDate(),
            existing.currency(),
            existing.value().add(value),
            existing.quantity().add(quantity),
            existing.attributionRuleCode()));
  }

  /**
   * 写营销位台账 + 事实 + 归因事实（**窗口重建**：先删该窗口旧行，再整段重写）。
   *
   * <p>⚠️ 口径必须先播种：事实行的指标码必须已登记（DB 触发器钉死）。
   *   没有这一句，**第一次刷新**就会在触发器上被拒（口径表是空的）⇒ 新租户完全用不了。
   */
  private Projected write(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      AttributionFetch attribution,
      LandingFetch landing) {
    seedDefinitions(tenantId);

    // 🔴 **窗口重建**：先删掉该窗口内的旧行，再整段重写。
    sql.execute(
        "delete from reporting_affiliate_marketing_attribution_fact "
            + "where tenant_id = ? and stat_date >= ? and stat_date <= ?",
        tenantId,
        from,
        to);
    sql.execute(
        "delete from reporting_affiliate_marketing_fact "
            + "where tenant_id = ? and stat_date >= ? and stat_date <= ?",
        tenantId,
        from,
        to);

    // ---- 营销位台账（**upsert**：营销位是台账，不是窗口内的量）----
    for (var slot : concat(attribution.slots(), landing.slots())) {
      var slotId =
          sql.fetch(
                  "select id from reporting_affiliate_marketing_slot "
                      + "where tenant_id = ? and slot_type = ? and slot_key = ?",
                  tenantId,
                  slot.slotType(),
                  slot.slotKey())
              .stream()
              .findFirst()
              .map(r -> r.get(0, UUID.class))
              .orElse(null);
      if (slotId == null) {
        slotId = Ids.next();
        sql.execute(
            """
            insert into reporting_affiliate_marketing_slot
              (id,tenant_id,slot_type,slot_key,slot_label,caliber,shop_code,platform_code,
               listing_id,influencer_id,campaign_id,cooperation_id,status,
               source_service,source_reference,created_at,updated_at)
            values (?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,now(),now())
            on conflict (tenant_id, slot_type, slot_key) do nothing
            """,
            slotId,
            tenantId,
            slot.slotType(),
            slot.slotKey(),
            slot.slotLabel(),
            slot.caliber(),
            slot.shopCode(),
            slot.platformCode(),
            slot.listingId(),
            slot.influencerId(),
            slot.campaignId(),
            slot.cooperationId(),
            slot.sourceService(),
            slot.sourceReference());
      } else {
        sql.execute(
            """
            update reporting_affiliate_marketing_slot
               set slot_label = ?, caliber = ?, shop_code = ?, platform_code = ?, updated_at = now()
             where tenant_id = ? and id = ?
            """,
            slot.slotLabel(),
            slot.caliber(),
            slot.shopCode(),
            slot.platformCode(),
            tenantId,
            slotId);
      }
    }

    // ---- 指标事实 ----
    int landingFacts = 0;
    for (var fact : concat(attribution.facts(), landing.facts())) {
      sql.execute(
          """
          insert into reporting_affiliate_marketing_fact
            (id,tenant_id,slot_type,slot_key,metric_code,stat_date,currency,value,quantity,
             attribution_rule_code,source_service,source_reference,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,now())
          on conflict (tenant_id, slot_type, slot_key, metric_code, stat_date, currency,
                       attribution_rule_code)
          do update set value = excluded.value, quantity = excluded.quantity, updated_at = now()
          """,
          Ids.next(),
          tenantId,
          fact.slotType(),
          fact.slotKey(),
          fact.metricCode(),
          fact.statDate(),
          fact.currency(),
          fact.value(),
          fact.quantity(),
          fact.attributionRuleCode(),
          "channel",
          "daily");
      if (ReportingAffiliateMarketingCatalog.SlotType.A_PLUS.name().equals(fact.slotType())) {
        landingFacts++;
      }
    }

    // ---- 归因事实（**带规则快照**）----
    int attributionFacts = 0;
    for (var attributionFact : attribution.attributions()) {
      sql.execute(
          """
          insert into reporting_affiliate_marketing_attribution_fact
            (id,tenant_id,slot_type,slot_key,rule_code,attribution_id,influencer_id,influencer_name,
             campaign_id,campaign_name,cooperation_id,shop_code,currency,
             attribution_model,pool_weight_basis,attribution_window_days,refund_treatment,
             commission_basis,commission_rate,attribution_weight,attributed_gmv,
             attributed_order_count,effective_order_count,commission_amount,attributed_at,stat_date,
             source_service,source_reference,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,now())
          on conflict (tenant_id, source_service, attribution_id)
          do update set slot_type = excluded.slot_type,
                        slot_key = excluded.slot_key,
                        influencer_name = excluded.influencer_name,
                        campaign_name = excluded.campaign_name,
                        rule_code = excluded.rule_code,
                        shop_code = excluded.shop_code,
                        currency = excluded.currency,
                        attribution_model = excluded.attribution_model,
                        pool_weight_basis = excluded.pool_weight_basis,
                        attribution_window_days = excluded.attribution_window_days,
                        refund_treatment = excluded.refund_treatment,
                        commission_basis = excluded.commission_basis,
                        commission_rate = excluded.commission_rate,
                        attribution_weight = excluded.attribution_weight,
                        attributed_gmv = excluded.attributed_gmv,
                        attributed_order_count = excluded.attributed_order_count,
                        effective_order_count = excluded.effective_order_count,
                        commission_amount = excluded.commission_amount,
                        attributed_at = excluded.attributed_at,
                        stat_date = excluded.stat_date,
                        source_reference = excluded.source_reference,
                        updated_at = now()
          """,
          Ids.next(),
          tenantId,
          attributionFact.slotType(),
          attributionFact.slotKey(),
          attributionFact.ruleCode(),
          attributionFact.attributionId(),
          attributionFact.influencerId(),
          attributionFact.influencerName(),
          attributionFact.campaignId(),
          attributionFact.campaignName(),
          attributionFact.cooperationId(),
          attributionFact.shopCode(),
          attributionFact.currency(),
          attributionFact.attributionModel(),
          attributionFact.poolWeightBasis(),
          attributionFact.attributionWindowDays(),
          attributionFact.refundTreatment(),
          attributionFact.commissionBasis(),
          attributionFact.commissionRate(),
          attributionFact.attributionWeight(),
          attributionFact.attributedGmv(),
          attributionFact.attributedOrderCount(),
          attributionFact.effectiveOrderCount(),
          attributionFact.commissionAmount(),
          attributionFact.attributedAt(),
          attributionFact.statDate(),
          "channel",
          "attribution:" + attributionFact.attributionId());
      attributionFacts++;
    }
    return new Projected(attributionFacts, landingFacts);
  }

  /** 两路取数结果拼起来。 */
  private static <T> List<T> concat(List<T> first, List<T> second) {
    var out = new ArrayList<T>(first.size() + second.size());
    out.addAll(first);
    out.addAll(second);
    return out;
  }

  /** 把落库条数写回刷新结果。 */
  private static ReportingAffiliateMarketingModels.AffiliateMarketingRefreshResult withProjected(
      ReportingAffiliateMarketingModels.AffiliateMarketingRefreshResult result, int projected) {
    return new ReportingAffiliateMarketingModels.AffiliateMarketingRefreshResult(
        result.source(), result.fetched(), projected, result.status(), result.note());
  }

  // ==========================================================================
  // 口径播种
  // ==========================================================================

  /**
   * 确保该租户有标准口径（**幂等**）。
   *
   * <p>⚠️ 与 13.1.1.1 / 13.2.1.1 / 13.2.2.1 / 13.3.2.1 同一纪律：口径是**租户自己**的标准口径，
   * 第一次用到这个租户时按标准份播种一份；租户改了口径，播种**不会**覆盖它。
   */
  void seedDefinitions(UUID tenantId) {
    var exists =
        sql.fetch(
            "select 1 from reporting_affiliate_marketing_metric_definition where tenant_id = ? limit 1",
            tenantId);
    if (!exists.isEmpty()) return;
    for (var definition : ReportingAffiliateMarketingCatalog.STANDARD) {
      sql.execute(
          """
          insert into reporting_affiliate_marketing_metric_definition
            (id,tenant_id,metric_code,display_name,unit,aggregation,numerator_metric_code,
             denominator_metric_code,currency_aware,attribution_bound,available,owner_module,
             description,enabled,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,?,?,true,now(),now())
          on conflict (tenant_id, metric_code) do nothing
          """,
          Ids.next(),
          tenantId,
          definition.metricCode(),
          definition.displayName(),
          definition.unit().name(),
          definition.aggregation().name(),
          definition.numeratorMetricCode(),
          definition.denominatorMetricCode(),
          definition.currencyAware(),
          definition.attributionBound(),
          definition.available(),
          definition.ownerModule(),
          definition.description());
    }
  }

  /** 供查询侧复用：把窗口长度换算成天数（校验用）。 */
  static int windowDays(LocalDate from, LocalDate to) {
    return (int) java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
  }

  // ==========================================================================
  // 小工具
  // ==========================================================================

  /** 解析平台统计日（上游给的是 {@code yyyy-MM-dd} 字符串）。 */
  static LocalDate parseDate(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return LocalDate.parse(value.trim());
    } catch (java.time.format.DateTimeParseException e) {
      return null;
    }
  }

  /** 币种：只收 3 位大写码；其它一律当「没有币种」（**不算进任何币种**）。 */
  static String currency(String value) {
    if (value == null) return "";
    var normalized = value.trim().toUpperCase();
    return normalized.matches("^[A-Z]{3}$") ? normalized : "";
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
