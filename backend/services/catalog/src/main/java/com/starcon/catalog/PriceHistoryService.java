package com.starcon.catalog;

import static com.starcon.catalog.PriceHistoryModels.*;

import com.starcon.catalog.PriceHistoryModels.PriceHistoryChangeQuery;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryChangeSource;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryComparisonPage;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryComparisonQuery;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryComparisonRow;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryDirection;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryInputCapability;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryPendingSuggestion;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryChangeRow;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryCapabilities;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryReasonCategory;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryReasonInput;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryReasonRow;
import com.starcon.catalog.PriceHistoryModels.PriceHistorySalesTotal;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryTimeline;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryTimelineEntry;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.5.1.1 价格历史：SKU × 店铺的**价格变更时间轴**，以及与**利润 / 销量**的对齐视图。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：记录 SKU/listing 的价格变更历史（时间、旧价、新价、操作人/来源、原因），支持按时间轴回溯并对比同期销量利润变化。
 *
 * **输入**：价格变更事件（人工与自动规则都记）；查询条件。
 *
 * **输出**：价格时间轴 + 关联的销售/利润变化。
 *
 * **关系**：上游 = 5.4.1、5.3.2.2；下游 = 13 分析。**价格历史 + 销量 + 利润三者对齐，才能回答"降价到底有没有用"**。
 *
 * </blockquote>
 *
 * <h2>🔴 本类的七条纪律</h2>
 *
 * <ol>
 *   <li><b>🔴 观测 ≠ 变更</b>。5.5.2.1 的 {@code price_anomaly_observation} 是「某时刻看到的价」，
 *       本表的 {@code price_change_history} 是「价被改了」。<b>观测不进变更事实</b> ——
 *       把观测复制成变更，等于凭空造出一堆没发生过的调价。
 *       观测台账只被<b>只读</b>引用（当前价来源）。</li>
 *   <li><b>🔴 listing = (SKU × 店铺)</b>。本仓没有 listing 的本地投影（listing 事实属
 *       channel 的 4.1.1.1），跨服务查表 / 跨库 FK / 共享视图被 AGENTS.md 禁止 ⇒
 *       <b>不建影子表</b>，按 (SKU × 店铺) 记，与 5.4.1.2、5.5.2.1 逐字同口径。</li>
 *   <li><b>🔴 本表是唯一写入所有者</b>。上游（5.4.1.1 调价单、5.4.1.2 批量调价、5.3.2.2 价格规则）
 *       <b>不直接写表</b>，它们经 {@link PriceChangeHistoryPort} 投递 ——
 *       否则一次改价会在三张表里各留一行，「这次到底改成了多少」就无从回答。</li>
 *   <li><b>🔴 变更事实不可变</b>。价格改过一次就再改一次，不在原地修改 ——
 *       否则「什么时候改的、为什么改」全部无从追溯，而这正是这本账的全部价值。</li>
 *   <li><b>🔴 利润一律由 5.3.1.1 的引擎算</b>（{@link ProfitCalculatorService#calculate}），
 *       本模块<b>零个测算算法</b>；且沿用它的<b>决策就绪闸</b> ——
 *       成本不完整时利润四列是 <b>null</b>，不是 0（0 会被渲染成「毛利刚好 0」）。</li>
 *   <li><b>🔴 销量取不到就明说取不到</b>。销量事实属 9 订单履约 / trade 服务，本仓 catalog 里
 *       一个销量数字都没有 ⇒ 走 {@link SalesFactPort} 跨服务只读；未接线时
 *       <b>显式 unavailable</b>，绝不拿 0 冒充销量（那会让每次降价都显示成「降价后一件没卖出去」）。</li>
 *   <li><b>🔴 租户只来自已认证成员关系</b>（{@link AccessService}），不读 {@code X-Tenant-Id} 头。
 *       每条 SQL 的 where 都带显式 {@code tenant_id=} 绑定，RLS 再兜一道。</li>
 * </ol>
 */
@Service
public class PriceHistoryService implements PriceChangeHistoryPort {

  /** 租户上下文取法：RLS 已按它隔离，这里带上只是让执行计划走租户前缀的索引。 */
  private static final String TENANT = "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 台账页大小上限：与相邻模块一致，防止一次拉爆整个租户的数据。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 单条时间轴最多回溯多少格。**触顶时不静默截断** —— 响应里会写明只覆盖了最近多少格。 */
  static final int MAX_TIMELINE_ENTRIES = 500;

  /** 对比视图里最多做多少次销量查询（每次一次跨服务调用）。 */
  static final int MAX_SALES_LOOKUPS = 50;

  /** 销量对比窗口的默认天数（变更前后各取多少天）。 */
  static final int DEFAULT_SALES_WINDOW_DAYS = 14;

  /** 原因字典默认的粗分类。 */
  private static final String DEFAULT_REASON_CATEGORY = "OTHER";

  private final DSLContext sql;
  private final ProfitCalculatorService calculator;
  private final SalesFactPort salesFacts;

  public PriceHistoryService(
      DSLContext sql, ProfitCalculatorService calculator, SalesFactPort salesFacts) {
    this.sql = sql;
    this.calculator = calculator;
    this.salesFacts = salesFacts;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：价格、销量、利润三个维度的真值在哪、能不能取到、缺了会怎样。
   *
   * <p>🔴 蓝图那句「<b>价格历史 + 销量 + 利润三者对齐，才能回答"降价到底有没有用"</b>」
   * 是本模块存在的理由，而它同时也是本模块最容易被糊弄过去的地方：
   * 三者里少一个，那个问题的答案就不成立。所以本页把「谁齐了、谁没齐」摆在最上面。
   */
  public PriceHistoryCapabilities capabilities() {
    var inputs =
        List.of(
            new PriceHistoryInputCapability(
                "PRICE_CHANGE",
                "价格变更事件",
                "PRICE_CHANGE_HISTORY_LEDGER",
                true,
                "5.5.1.1 价格历史（本服务）· price_change_history（V241）",
                "价格变更是本模块自己写的事实表。上游（5.4.1.1 调价单、5.4.1.2 批量调价、"
                    + "5.3.2.2 价格规则）经写端口投递，**不直接写表**。",
                "一行 = 一个 SKU 在一个店铺上的一次价格变更（旧价 → 新价 + 谁动的 + 为什么 + 何时生效）。",
                "只追加：改价不改历史，历史留着 —— 「这个价是什么时候降下来的」才答得出来。"),
            new PriceHistoryInputCapability(
                "CURRENT_PRICE",
                "当前价",
                "PRICE_OBSERVATION_LEDGER",
                true,
                "5.5.2.1 异常告警（本服务）· price_anomaly_observation（V222）",
                "当前价取 5.5.2.1 价格观测台账里该 (SKU × 店铺) 的**最新一条**观测"
                    + "（优先 internal_price，退到 platform_price）。🔴 **只读** —— "
                    + "那张表的写入所有者是 5.5.2.1，本模块绝不写、绝不 ALTER。",
                "同服务、同一张台账。台账只追加，所以「最新一条」是一个**时间点**而不是「当前价」。",
                "🔴 **观测 ≠ 变更**：观测是「某时刻看到的价」，变更是「价被改了」。"
                    + "本模块不把观测复制成变更事实 —— 那会凭空造出一堆没发生过的调价。"),
            new PriceHistoryInputCapability(
                "GROSS_PROFIT",
                "利润变化",
                "PROFIT_CALCULATOR_ENGINE",
                true,
                "5.3.1.1 利润测算器（本服务）· ProfitCalculatorService.calculate",
                "改价前后的毛利额与毛利率<b>全部</b>由 5.3.1.1 的引擎算，本模块零算法。"
                    + "沿用它的**决策就绪闸**：成本不完整时利润四列为 null（不是 0）。",
                "同服务、同引擎、同口径（金额 4 位小数 / 费率 6 位小数 / HALF_UP）。",
                "🔴 5.2.3.1 平台费用与 5.2.3.2 物流费用**未交付** ⇒ 费率与运费是**手工假设**；"
                    + "缺了它们毛利率就测不准，时间轴上显示「未测准」而不是给一个虚高的数。"),
            new PriceHistoryInputCapability(
                "SALES",
                "销量变化",
                "CROSS_SERVICE_READ_PORT",
                false,
                "9 订单履约（trade 服务）· 13.2.1.1 销量趋势",
                "销量事实属 **trade 服务**（订单行数量），本仓 catalog 里**没有**任何销量事实。"
                    + "跨服务查表 / 跨库 FK / 共享视图被 AGENTS.md 禁止 ⇒ 走 "
                    + "SalesFactPort 跨服务只读；**未接线时显式返回 unavailable**。",
                "端口约定 GET /trade/sales-facts/sku-summary（按 SKU × 店铺 × 区间聚合）。",
                "🔴 **该端点当前未交付** ⇒ 销量维度是**未接线**状态，不是已完成。"
                    + "本模块<b>绝不</b>用 0 或空数组冒充销量 —— 那会让每次降价都显示成"
                    + "「降价后一件没卖出去」，是一个凭空造出来的结论。"));

    return new PriceHistoryCapabilities(
        inputs,
        "listing 的口径 = **(SKU × 店铺)**（sku_id + platform_code + site_code）。"
            + "本仓的 listing 事实属 channel 服务（4.1.1.1），catalog 没有 listing 的本地投影，"
            + "而 AGENTS.md 禁止跨服务直接查对方的表 ⇒ 本模块**不新建任何 listing 影子表**。",
        "变更来源（谁发起的）：人工调价（5.4.1.1 / 人工登记）、批量调价（5.4.1.2）、"
            + "价格规则自动调价（5.3.2.2 → 5.4.1.1 执行）、价目表改价（5.1，未交付）、"
            + "平台价格回流（4.3.2.2 只回流状态、不回流价格）、批量导入、其它。",
        "利润：**只**由 5.3.1.1 的引擎算，本模块零算法。改价前后的毛利额与毛利率"
            + "按变更**当时**的口径快照下来（不是用今天的成本重算历史）。"
            + "成本不完整时利润四列是 null（decisionReady=false），不是 0。",
        "销量：**当前未接线**。销量事实属 trade 服务，端口已定义但上游端点尚未交付 ⇒ "
            + "对比视图里销量三列为 null 并写明「未接线」。这不是「已完成」，是「还没做」。",
        "上游 = 5.4.1（调价单与批量调价）、5.3.2.2（价格规则）。"
            + "🔴 5.4.1.1 调价单**与本模块同批并行开发**，本仓此刻尚不存在 —— "
            + "本模块对外暴露写端口 PriceChangeHistoryPort，**没有**假装它已经接上了。"
            + "5.4.1.2 批量调价**已交付**，它执行后经同一个端口逐条投递。",
        "下游 = 13 分析（13.2.1.1 销量趋势 / 13.3 产品表现）。"
            + "13 分析会拿价格历史 + 销量 + 利润三者对齐去回答「降价到底有没有用」，"
            + "本模块提供前两者（价格 + 利润），销量那一半当前未接线。",
        "本模块只写自己那两张表（price_change_history / price_change_reason）。"
            + "🔴 **只读** 5.5.2.1 的价格观测台账、5.4.1.2 的批量调价单与明细、"
            + "5.3.2.2 的建议表 —— 那些表的写入所有者是别的模块，"
            + "在本模块落一份副本就会立刻出现两个真相。",
        "可做的动作：登记一次价格变更（人工）、查看价格时间轴（按 SKU / 店铺 / 时间范围 / "
            + "变更来源 / 原因真筛选 + 真分页 + 真排序）、查看单条变更详情、"
            + "查看「价格 + 利润 + 销量」对比视图、维护租户自己的变更原因字典。");
  }

  // ================================================================ 写端口（5.5.1.1 是唯一写入所有者）

  @Override
  public UUID record(AccessService.Access actor, String bearer, PriceChangeEvent event) {
    if (actor == null) throw ProfitCalculatorService.badRequest("缺少调用者身份");
    if (event == null) throw ProfitCalculatorService.badRequest("价格变更事件不能为空");
    if (event.skuId() == null) throw ProfitCalculatorService.badRequest("必须指定 SKU");

    String platform = upper(event.platformCode());
    String site = upper(event.siteCode());
    if (platform == null || !platform.matches("[A-Z0-9][A-Z0-9_-]{0,31}")) {
      throw ProfitCalculatorService.badRequest("平台码必须是大写字母 / 数字 / 下划线 / 连字符（1~32 位）");
    }
    if (site == null || !site.matches("[A-Z0-9][A-Z0-9_-]{0,31}")) {
      throw ProfitCalculatorService.badRequest("站点码必须是大写字母 / 数字 / 下划线 / 连字符（1~32 位）");
    }
    String currency = upper(event.currency());
    if (currency == null || !currency.matches("[A-Z]{3}")) {
      throw ProfitCalculatorService.badRequest("币种必须是三位大写字母（ISO 4217）");
    }
    if (event.newPrice() == null) throw ProfitCalculatorService.badRequest("必须给出新价");
    BigDecimal newPrice = BatchPriceAdjustmentRules.price(event.newPrice());
    if (newPrice.signum() <= 0) {
      throw ProfitCalculatorService.badRequest("新价必须大于 0（0 价不是价 —— 它会被算成「免费」并污染后面每一次毛利率）");
    }
    BigDecimal oldPrice = null;
    if (event.oldPrice() != null) {
      oldPrice = BatchPriceAdjustmentRules.price(event.oldPrice());
      if (oldPrice.signum() < 0) {
        throw ProfitCalculatorService.badRequest("旧价不能为负");
      }
    }
    PriceHistoryChangeSource source = PriceHistoryChangeSource.parse(event.changeSource());
    if (source == null) {
      throw ProfitCalculatorService.badRequest("未知的变更来源：" + event.changeSource());
    }
    if (event.sourceModule() == null || event.sourceModule().isBlank()) {
      throw ProfitCalculatorService.badRequest(
          "必须点名投递方模块（sourceModule）：来路不明的变更不许进账，否则事后无人追得出它从哪来");
    }
    if (event.eventKey() == null || event.eventKey().isBlank()) {
      throw ProfitCalculatorService.badRequest("必须给出幂等键（eventKey）：重复投递不能堆出第二条变更");
    }
    if (event.effectiveAt() == null) {
      throw ProfitCalculatorService.badRequest("必须给出变更生效时刻（effectiveAt）");
    }
    String reasonText = event.reasonText() == null ? "" : event.reasonText().trim();
    if (reasonText.isEmpty()) {
      throw ProfitCalculatorService.badRequest("必须写明变更原因（reasonText）—— 蓝图要求记「原因」");
    }
    String reasonCode = event.reasonCode() == null ? "" : event.reasonCode().trim();
    String sourceModule = event.sourceModule().trim();
    String sourceReference = event.sourceReference() == null ? "" : event.sourceReference().trim();

    // ---- 价差 / 方向 / 幅度（方向是**派生**的，不能与数字打架）----
    BigDecimal delta = null;
    BigDecimal ratio = null;
    PriceHistoryDirection direction;
    if (oldPrice == null) {
      // 🔴 首次定价**不是**「从 0 涨到新价」—— 那会让时间轴上凭空多出一跳 100% 的涨幅。
      direction = PriceHistoryDirection.INITIAL;
    } else {
      delta = BatchPriceAdjustmentRules.money(newPrice.subtract(oldPrice));
      direction =
          delta.signum() > 0
              ? PriceHistoryDirection.UP
              : delta.signum() < 0 ? PriceHistoryDirection.DOWN : PriceHistoryDirection.UNCHANGED;
      // 旧价为 0 时幅度无定义（旧价 0 不是价，而是「当时免费」）：留 null，不编一个数。
      if (oldPrice.signum() > 0) {
        ratio = delta.divide(oldPrice, 6, RoundingMode.HALF_UP);
      }
    }

    // ---- 利润快照（由 5.3.1.1 的引擎算；本模块零算法）----
    var profit = profitSnapshot(event, platform, site, currency, oldPrice, newPrice, bearer);

    var id = Ids.next();
    sql.execute(
        "insert into price_change_history(id,tenant_id,sku_id,platform_code,site_code,currency,"
            + "old_price,new_price,price_delta,change_ratio,direction,change_source,reason_code,"
            + "reason_text,operator_id,source_module,source_reference,event_key,effective_at,"
            + "recorded_at,gross_profit_before,gross_margin_before,gross_profit_after,"
            + "gross_margin_after,decision_ready,profit_note,created_at)"
            + " values(?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?::uuid,?,?,?,?::timestamptz,"
            + "now(),?,?,?,?,?,?,now())"
            + " on conflict (tenant_id,event_key) do nothing",
        id,
        actor.tenantId(),
        event.skuId(),
        platform,
        site,
        currency,
        oldPrice,
        newPrice,
        delta,
        ratio,
        direction.name(),
        source.name(),
        reasonCode,
        reasonText,
        event.operatorId(),
        sourceModule,
        sourceReference,
        event.eventKey(),
        event.effectiveAt(),
        profit.grossProfitBefore(),
        profit.grossMarginBefore(),
        profit.grossProfitAfter(),
        profit.grossMarginAfter(),
        profit.decisionReady(),
        profit.note());
    var row =
        sql.fetchOne(
            "select id from price_change_history where tenant_id=" + TENANT + " and event_key=?",
            event.eventKey());
    if (row == null) {
      throw new IllegalStateException("价格变更写入后读不回来：" + event.eventKey());
    }
    return row.get("id", UUID.class);
  }

  /** 利润快照。**测不准就不给数字**（null，不是 0）。 */
  private record ProfitSnapshot(
      BigDecimal grossProfitBefore,
      BigDecimal grossMarginBefore,
      BigDecimal grossProfitAfter,
      BigDecimal grossMarginAfter,
      boolean decisionReady,
      String note) {}

  private ProfitSnapshot profitSnapshot(
      PriceChangeEvent event,
      String platform,
      String site,
      String currency,
      BigDecimal oldPrice,
      BigDecimal newPrice,
      String bearer) {
    // ---- 上游已经用 5.3.1.1 的引擎算过了 ⇒ 原样落库，不重算 ----
    //   重算会让同一批 listing 多翻一倍 warehouse 成本，
    //   而且 warehouse 抖一下就会把**已经算准的**利润降级成「没测准」。
    var given = event.profitSnapshot();
    if (given != null) {
      // 🔴 上游说「测准了」却没给改价后的毛利 ⇒ 那不是测准，是数据不全 ⇒ 按没测准处理。
      if (!given.decisionReady() || given.grossProfitAfter() == null) {
        return new ProfitSnapshot(
            null, null, null, null, false,
            given.note() == null || given.note().isBlank()
                ? "上游那次测算没测准（决策就绪闸未通过），本次变更不挂利润数字。"
                : given.note());
      }
      return new ProfitSnapshot(
          ProfitCalculatorRules.money(given.grossProfitBefore()),
          ProfitCalculatorRules.rate(given.grossMarginBefore()),
          ProfitCalculatorRules.money(given.grossProfitAfter()),
          ProfitCalculatorRules.rate(given.grossMarginAfter()),
          true,
          given.note() == null ? "" : given.note());
    }
    var basis = event.profitBasis();
    if (basis == null) {
      return new ProfitSnapshot(
          null, null, null, null, false, "未提供利润测算口径（5.3.1.1 的输入参数），本次变更不挂利润快照。");
    }
    if (bearer == null || bearer.isBlank()) {
      return new ProfitSnapshot(
          null,
          null,
          null,
          null,
          false,
          "未提供调用凭证，无法跨服务读成本构成（头程 / 关税），本次变更不挂利润快照。");
    }
    try {
      var before =
          oldPrice == null
              ? null
              : calculate(event.skuId(), platform, site, currency, oldPrice, basis, bearer);
      var after = calculate(event.skuId(), platform, site, currency, newPrice, basis, bearer);
      boolean ready = after.decisionReady() && (before == null || before.decisionReady());
      if (!ready) {
        String reason = after.grossProfitSuppressedReason();
        return new ProfitSnapshot(
            null,
            null,
            null,
            null,
            false,
            "利润没测准（决策就绪闸未通过）："
                + (reason == null || reason.isBlank() ? "缺少成本构成或扣减项" : reason));
      }
      return new ProfitSnapshot(
          before == null ? null : ProfitCalculatorRules.money(before.grossProfit()),
          before == null ? null : ProfitCalculatorRules.rate(before.grossMarginRate()),
          ProfitCalculatorRules.money(after.grossProfit()),
          ProfitCalculatorRules.rate(after.grossMarginRate()),
          true,
          "");
    } catch (RuntimeException e) {
      // 🔴 写历史是**记账**，不是改价：利润算不出来不能把账也丢掉。
      //   降级成「未测准」并写明原因 —— 绝不拿 0 冒充毛利。
      return new ProfitSnapshot(
          null, null, null, null, false, "利润测算未完成：" + reasonOf(e));
    }
  }

  /** 一次 5.3.1.1 的测算（改价前或改价后）。 */
  private ProfitCalculatorModels.ProfitCalculation calculate(
      UUID skuId,
      String platform,
      String site,
      String currency,
      BigDecimal price,
      PriceChangeHistoryPort.ProfitBasis basis,
      String bearer) {
    return calculator.calculate(
        new ProfitCalculatorModels.CalculateRequest(
            skuId,
            site,
            platform,
            currency,
            basis.costCurrency(),
            ProfitCalculatorModels.CalcMode.PRICE,
            price,
            null,
            basis.quantity(),
            basis.fxRate(),
            basis.platformFeeRate(),
            basis.platformFeeFixed(),
            basis.logisticsCostPerUnit(),
            basis.monthlyFixedCost(),
            List.of(),
            basis.costWindowFrom(),
            basis.costWindowTo(),
            "5.5.1.1 价格历史：变更前后的利润快照"),
        bearer);
  }

  // ================================================================ 人工登记

  /** 人工登记一次价格变更（蓝图的「价格变更事件」输入入口之一）。 */
  public PriceHistoryChangeSaved register(AccessService.Access actor, String bearer, PriceHistoryChangeInput input) {
    if (input == null) throw ProfitCalculatorService.badRequest("价格变更登记不能为空");
    // 🔴 一个利润口径参数都没给 ⇒ **不跑测算**（跑一遍只会得到「没测准」，
    //    还会白跑一次跨服务的成本读取）。写明「未提供口径」比给一个假数字诚实。
    var basis =
        hasProfitBasis(input)
            ? new PriceChangeHistoryPort.ProfitBasis(
                upper(input.costCurrency()),
                input.fxRate(),
                input.quantity(),
                input.platformFeeRate(),
                input.platformFeeFixed(),
                input.logisticsCostPerUnit(),
                input.monthlyFixedCost(),
                input.costWindowFrom(),
                input.costWindowTo())
            : null;
    var event =
        new PriceChangeHistoryPort.PriceChangeEvent(
            input.skuId(),
            input.platformCode(),
            input.siteCode(),
            input.currency(),
            input.oldPrice(),
            input.newPrice(),
            input.changeSource(),
            input.reasonCode(),
            input.reasonText(),
            actor.userId(),
            MANUAL_SOURCE_MODULE,
            input.note() == null ? "" : input.note().trim(),
            "MANUAL:" + UUID.randomUUID(),
            input.effectiveAt(),
            basis,
            null);
    var id = record(actor, bearer, event);
    return new PriceHistoryChangeSaved(
        id,
        input.skuId(),
        upper(input.platformCode()),
        upper(input.siteCode()),
        false,
        "已登记。变更事实只追加：改价要改，就再登记一条。");
  }

  /** 人工登记时的「投递方模块」标记。 */
  static final String MANUAL_SOURCE_MODULE = "MANUAL_REGISTRATION";

  /** 调用方到底有没有给利润口径（一个都没给 ⇒ 不跑测算）。 */
  private static boolean hasProfitBasis(PriceHistoryChangeInput input) {
    return input.quantity() != null
        || input.costCurrency() != null
        || input.fxRate() != null
        || input.platformFeeRate() != null
        || input.platformFeeFixed() != null
        || input.logisticsCostPerUnit() != null
        || input.monthlyFixedCost() != null
        || input.costWindowFrom() != null
        || input.costWindowTo() != null;
  }

  // ================================================================ 时间轴（列表）

  /** 价格变更时间轴（真筛选 + 真分页 + 真排序）。 */
  public PriceHistoryChangeListPage listChanges(PriceHistoryChangeQuery query) {
    int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    int page = Math.max(query.page(), 0);
    var where = new StringBuilder(" where h.tenant_id = " + TENANT);
    var binds = new ArrayList<Object>();
    applyFilters(where, binds, query);

    var total =
        sql.fetchOne(
                "select count(*) from price_change_history h"
                    + " left join sku k on k.tenant_id=h.tenant_id and k.id=h.sku_id" + where,
                binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select h.id, h.sku_id, k.code as sku_code, k.name as sku_name, h.platform_code,"
                + " h.site_code, h.currency, h.old_price, h.new_price, h.price_delta, h.change_ratio,"
                + " h.direction, h.change_source, h.reason_code, h.reason_text, h.operator_id,"
                + " h.source_module, h.source_reference, h.event_key, h.effective_at, h.recorded_at,"
                + " h.gross_profit_before, h.gross_margin_before, h.gross_profit_after,"
                + " h.gross_margin_after, h.decision_ready, h.profit_note, h.created_at"
                + " from price_change_history h"
                + " left join sku k on k.tenant_id=h.tenant_id and k.id=h.sku_id"
                + where
                + orderOf(query.sort())
                + " limit ? offset ?",
            concat(binds, List.of(size, page * size)).toArray());
    var items = new ArrayList<PriceHistoryChangeRow>();
    for (var row : rows) {
      items.add(toRow(row));
    }
    return new PriceHistoryChangeListPage(items, total, page, size, "");
  }

  private void applyFilters(StringBuilder where, List<Object> binds, PriceHistoryChangeQuery query) {
    if (notBlank(query.keyword())) {
      where.append(" and (k.code ilike ? or k.name ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
    }
    if (query.skuId() != null) {
      where.append(" and h.sku_id=?::uuid");
      binds.add(query.skuId());
    }
    if (notBlank(query.platformCode())) {
      where.append(" and h.platform_code=?");
      binds.add(query.platformCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.siteCode())) {
      where.append(" and h.site_code=?");
      binds.add(query.siteCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.changeSource())) {
      PriceHistoryChangeSource parsed = PriceHistoryChangeSource.parse(query.changeSource());
      if (parsed == null) {
        throw ProfitCalculatorService.badRequest("未知的变更来源：" + query.changeSource());
      }
      where.append(" and h.change_source=?");
      binds.add(parsed.name());
    }
    if (notBlank(query.reasonCode())) {
      where.append(" and h.reason_code=?");
      binds.add(query.reasonCode().trim());
    }
    if (notBlank(query.direction())) {
      PriceHistoryDirection parsed = PriceHistoryDirection.parse(query.direction());
      if (parsed == null) {
        throw ProfitCalculatorService.badRequest("未知的方向：" + query.direction());
      }
      where.append(" and h.direction=?");
      binds.add(parsed.name());
    }
    if (query.windowFrom() != null) {
      where.append(" and h.effective_at >= ?::timestamptz");
      binds.add(query.windowFrom().atStartOfDay().atOffset(ZoneOffset.UTC));
    }
    if (query.windowTo() != null) {
      // 半开区间 [from, to+1)：to 当天整天都算在内。
      where.append(" and h.effective_at < ?::timestamptz");
      binds.add(query.windowTo().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
    }
  }

  /** 排序：白名单式，**不接受**任意 SQL 片段。 */
  private String orderOf(String sort) {
    if (sort == null || sort.isBlank()) {
      return " order by h.effective_at desc, h.id desc";
    }
    return switch (sort.trim().toUpperCase(Locale.ROOT)) {
      case "EFFECTIVE_AT_ASC" -> " order by h.effective_at asc, h.id asc";
      case "PRICE_DELTA_DESC" -> " order by h.price_delta desc nulls last, h.effective_at desc, h.id";
      case "PRICE_DELTA_ASC" -> " order by h.price_delta asc nulls last, h.effective_at desc, h.id";
      case "CREATED_AT_DESC" -> " order by h.created_at desc, h.id desc";
      case "SKU_ASC" -> " order by k.code asc nulls last, h.effective_at desc, h.id desc";
      case "EFFECTIVE_AT_DESC" -> " order by h.effective_at desc, h.id desc";
      default ->
          throw ProfitCalculatorService.badRequest(
              "未知的排序方式：" + sort + "（可用：EFFECTIVE_AT_DESC / EFFECTIVE_AT_ASC / "
                  + "PRICE_DELTA_DESC / PRICE_DELTA_ASC / CREATED_AT_DESC / SKU_ASC）");
    };
  }

  // ================================================================ 单条详情

  /** 单条变更详情：变更事实 + 前后相邻的变更（时间轴的上下文）。 */
  public PriceHistoryChangeDetail getChange(UUID id) {
    var row = fetchChange(id);
    if (row == null) throw ProfitCalculatorService.notFound("价格变更不存在");
    var change = toRow(row);
    var previous =
        fetchNeighbour(
            change.skuId(),
            change.platformCode(),
            change.siteCode(),
            change.effectiveAt(),
            id,
            false);
    var next =
        fetchNeighbour(
            change.skuId(), change.platformCode(), change.siteCode(), change.effectiveAt(), id, true);
    return new PriceHistoryChangeDetail(
        change,
        previous,
        next,
        "变更事实只追加：这条记录是「某一次改价」的**快照**，改价要改就再登记一条，"
            + "不在原地修改已经发生过的历史。利润四列由 5.3.1.1 的引擎在**变更当时**算好并快照下来。",
        "");
  }

  private Record fetchChange(UUID id) {
    return sql.fetchOne(
        "select h.id, h.sku_id, k.code as sku_code, k.name as sku_name, h.platform_code,"
            + " h.site_code, h.currency, h.old_price, h.new_price, h.price_delta, h.change_ratio,"
            + " h.direction, h.change_source, h.reason_code, h.reason_text, h.operator_id,"
            + " h.source_module, h.source_reference, h.event_key, h.effective_at, h.recorded_at,"
            + " h.gross_profit_before, h.gross_margin_before, h.gross_profit_after,"
            + " h.gross_margin_after, h.decision_ready, h.profit_note, h.created_at"
            + " from price_change_history h"
            + " left join sku k on k.tenant_id=h.tenant_id and k.id=h.sku_id"
            + " where h.tenant_id="
            + TENANT
            + " and h.id=?::uuid",
        id);
  }

  /** 时间轴上的前一条 / 后一条（同一个 listing 内）。 */
  private PriceHistoryChangeRow fetchNeighbour(
      UUID skuId,
      String platformCode,
      String siteCode,
      OffsetDateTime at,
      UUID id,
      boolean forward) {
    var sqlText =
        "select h.id, h.sku_id, k.code as sku_code, k.name as sku_name, h.platform_code,"
            + " h.site_code, h.currency, h.old_price, h.new_price, h.price_delta, h.change_ratio,"
            + " h.direction, h.change_source, h.reason_code, h.reason_text, h.operator_id,"
            + " h.source_module, h.source_reference, h.event_key, h.effective_at, h.recorded_at,"
            + " h.gross_profit_before, h.gross_margin_before, h.gross_profit_after,"
            + " h.gross_margin_after, h.decision_ready, h.profit_note, h.created_at"
            + " from price_change_history h"
            + " left join sku k on k.tenant_id=h.tenant_id and k.id=h.sku_id"
            + " where h.tenant_id="
            + TENANT
            + " and h.sku_id=?::uuid and h.platform_code=? and h.site_code=?"
            + (forward
                ? " and (h.effective_at, h.id) > (?::timestamptz, ?::uuid)"
                : " and (h.effective_at, h.id) < (?::timestamptz, ?::uuid)")
            + (forward
                ? " order by h.effective_at asc, h.id asc limit 1"
                : " order by h.effective_at desc, h.id desc limit 1");
    var row =
        sql.fetchOne(sqlText, skuId, platformCode, siteCode, at, id);
    return row == null ? null : toRow(row);
  }

  // ================================================================ 时间轴（单 listing）

  /** 一个 listing（SKU × 店铺）的价格时间轴。 */
  public PriceHistoryTimeline timeline(
      UUID skuId, String platformCode, String siteCode, LocalDate from, LocalDate to) {
    if (skuId == null) throw ProfitCalculatorService.badRequest("必须指定 SKU");
    String platform = upper(platformCode);
    String site = upper(siteCode);
    if (platform == null || site == null) {
      throw ProfitCalculatorService.badRequest("必须指定店铺（平台码 + 站点码）—— 一个 listing 就是一个 SKU 在一个店铺");
    }
    var where =
        new StringBuilder(
            " where h.tenant_id="
                + TENANT
                + " and h.sku_id=?::uuid and h.platform_code=? and h.site_code=?");
    var binds = new ArrayList<Object>();
    binds.add(skuId);
    binds.add(platform);
    binds.add(site);
    if (from != null) {
      where.append(" and h.effective_at >= ?::timestamptz");
      binds.add(from.atStartOfDay().atOffset(ZoneOffset.UTC));
    }
    if (to != null) {
      where.append(" and h.effective_at < ?::timestamptz");
      binds.add(to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
    }
    var rows =
        sql.fetch(
            "select h.id, h.sku_id, k.code as sku_code, k.name as sku_name, h.platform_code,"
                + " h.site_code, h.currency, h.old_price, h.new_price, h.price_delta, h.change_ratio,"
                + " h.direction, h.change_source, h.reason_code, h.reason_text, h.operator_id,"
                + " h.source_module, h.source_reference, h.event_key, h.effective_at, h.recorded_at,"
                + " h.gross_profit_before, h.gross_margin_before, h.gross_profit_after,"
                + " h.gross_margin_after, h.decision_ready, h.profit_note, h.created_at"
                + " from price_change_history h"
                + " left join sku k on k.tenant_id=h.tenant_id and k.id=h.sku_id"
                + where
                + " order by h.effective_at asc, h.id asc"
                + " limit ?",
            concat(binds, List.of(MAX_TIMELINE_ENTRIES + 1)).toArray());

    boolean truncated = rows.size() > MAX_TIMELINE_ENTRIES;
    var entries = new ArrayList<PriceHistoryTimelineEntry>();
    PriceHistoryChangeRow previous = null;
    BigDecimal firstPrice = null;
    String firstCurrency = null;
    for (int i = 0; i < Math.min(rows.size(), MAX_TIMELINE_ENTRIES); i++) {
      var row = toRow(rows.get(i));
      if (firstPrice == null) {
        firstPrice = row.newPrice();
        firstCurrency = row.currency();
      }
      boolean consistent = true;
      String note = "";
      if (previous != null) {
        if (previous.newPrice() == null || row.oldPrice() == null) {
          consistent = false;
          note = "本次变更没有旧价（旧价为空），与上一格接不上";
        } else if (previous.newPrice().compareTo(row.oldPrice()) != 0) {
          consistent = false;
          note =
              "链条断了：上一格的新价 "
                  + previous.newPrice().toPlainString()
                  + " 与本次的旧价 "
                  + row.oldPrice().toPlainString()
                  + " 对不上（中间有一次变更没进账？）";
        }
      }
      BigDecimal cumulative = null;
      if (firstPrice != null && row.newPrice() != null && firstCurrency != null) {
        if (firstCurrency.equals(row.currency())) {
          cumulative = BatchPriceAdjustmentRules.money(row.newPrice().subtract(firstPrice));
        } else {
          note =
              (note.isEmpty() ? "" : note + "；")
                  + "累计价差不可算：这一格与时间轴起点的币种不同（"
                  + firstCurrency
                  + " vs "
                  + row.currency()
                  + "），本模块**绝不按 1:1 折算**";
        }
      }
      entries.add(new PriceHistoryTimelineEntry(row, consistent, note, cumulative));
      previous = row;
    }

    PriceHistoryChangeRow last = entries.isEmpty() ? null : entries.get(entries.size() - 1).change();
    var skuRow =
        sql.fetchOne(
            "select code, name from sku where tenant_id=" + TENANT + " and id=?::uuid", skuId);
    var suggestions = pendingSuggestions(skuId, platform, site);
    String note =
        truncated
            ? "本时间轴只覆盖了最近 "
                + MAX_TIMELINE_ENTRIES
                + " 格（更早的变更请按时间窗口缩小范围查询）—— 本模块不会静默截断。"
            : "";
    return new PriceHistoryTimeline(
        skuId,
        skuRow == null ? null : skuRow.get("code", String.class),
        skuRow == null ? null : skuRow.get("name", String.class),
        platform,
        site,
        last == null ? null : last.currency(),
        last == null ? null : last.newPrice(),
        last == null ? null : last.effectiveAt(),
        entries.size(),
        entries,
        suggestions,
        note);
  }

  /**
   * 5.3.2.2 价格规则的**待执行建议**（只读引用）。
   *
   * <p>🔴 建议**不是生效价**（改价的写入所有权在 5.4.1.1）——
   * 所以它只作为「尚未生效的调价意向」出现，**不复制成变更事实**。
   */
  private List<PriceHistoryPendingSuggestion> pendingSuggestions(UUID skuId, String platform, String site) {
    var rows =
        sql.fetch(
            "select s.id, s.rule_id, r.name as rule_name, s.sku_id, s.platform_code, s.site_code,"
                + " s.currency, s.action_type, s.current_price, s.suggested_price, s.price_change_rate,"
                + " s.status, s.requires_confirmation, s.gross_margin_rate, s.reason, s.created_at"
                + " from pricing_rule_suggestion s"
                + " left join pricing_rule r on r.tenant_id=s.tenant_id and r.id=s.rule_id"
                + " where s.tenant_id="
                + TENANT
                + " and s.sku_id=?::uuid and s.platform_code=? and s.site_code=?"
                + " and s.status in ('READY','PENDING_CONFIRMATION')"
                + " and s.action_type in ('LOWER_PRICE','RAISE_PRICE')"
                + " order by s.created_at desc limit 50",
            skuId,
            platform,
            site);
    var out = new ArrayList<PriceHistoryPendingSuggestion>();
    for (var row : rows) {
      out.add(
          new PriceHistoryPendingSuggestion(
              row.get("id", UUID.class),
              row.get("rule_id", UUID.class),
              row.get("rule_name", String.class),
              row.get("sku_id", UUID.class),
              row.get("platform_code", String.class),
              row.get("site_code", String.class),
              row.get("currency", String.class),
              row.get("action_type", String.class),
              row.get("current_price", BigDecimal.class),
              row.get("suggested_price", BigDecimal.class),
              row.get("price_change_rate", BigDecimal.class),
              row.get("status", String.class),
              Boolean.TRUE.equals(row.get("requires_confirmation", Boolean.class)),
              row.get("gross_margin_rate", BigDecimal.class),
              row.get("reason", String.class),
              row.get("created_at", OffsetDateTime.class)));
    }
    return out;
  }

  // ================================================================ 对比视图（价格 + 利润 + 销量）

  /**
   * 「降价到底有没有用」的对比视图。
   *
   * <p>🔴 蓝图加粗句要求**三者对齐**。本视图逐条给出：价怎么变的、利润怎么变的、
   * 销量怎么变的 —— 销量那一列**当前未接线**（上游端点未交付），
   * 于是它是 <b>null</b> 并写明原因，**不是 0**。
   */
  public PriceHistoryComparisonPage comparison(PriceHistoryComparisonQuery query, String bearer) {
    int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    int page = Math.max(query.page(), 0);
    int windowDays =
        query.salesWindowDays() <= 0 ? DEFAULT_SALES_WINDOW_DAYS : Math.min(query.salesWindowDays(), 180);
    var where = new StringBuilder(" where h.tenant_id = " + TENANT);
    var binds = new ArrayList<Object>();
    applyComparisonFilters(where, binds, query);

    var total =
        sql.fetchOne(
                "select count(*) from price_change_history h"
                    + " left join sku k on k.tenant_id=h.tenant_id and k.id=h.sku_id" + where,
                binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select h.id, h.sku_id, k.code as sku_code, k.name as sku_name, h.platform_code,"
                + " h.site_code, h.currency, h.old_price, h.new_price, h.price_delta, h.change_ratio,"
                + " h.direction, h.change_source, h.reason_code, h.reason_text, h.operator_id,"
                + " h.source_module, h.source_reference, h.event_key, h.effective_at, h.recorded_at,"
                + " h.gross_profit_before, h.gross_margin_before, h.gross_profit_after,"
                + " h.gross_margin_after, h.decision_ready, h.profit_note, h.created_at"
                + " from price_change_history h"
                + " left join sku k on k.tenant_id=h.tenant_id and k.id=h.sku_id"
                + where
                + " order by h.effective_at desc, h.id desc"
                + " limit ? offset ?",
            concat(binds, List.of(size, page * size)).toArray());

    // ---- 销量：先探一次，探不通就整页标「未接线」（不做 N 次注定失败的调用）----
    boolean salesAvailable = true;
    String salesNote = "";
    var salesCache = new LinkedHashMap<UUID, PriceHistorySalesTotal[]>();
    int lookups = 0;
    for (var row : rows) {
      if (!salesAvailable) break;
      var change = toRow(row);
      if (lookups >= MAX_SALES_LOOKUPS) {
        salesAvailable = false;
        salesNote =
            "销量对比只覆盖了本次返回的前 "
                + MAX_SALES_LOOKUPS
                + " 条变更（每条要两次跨服务查询）—— 本模块不会静默截断，"
                + "请缩小时间范围再看。";
        break;
      }
      try {
        var pair = salesAround(change, windowDays, bearer);
        salesCache.put(change.id(), pair);
        lookups += 2;
      } catch (SalesFactPort.SalesUnavailableException e) {
        salesAvailable = false;
        salesNote = e.getMessage();
        break;
      }
    }

    var items = new ArrayList<PriceHistoryComparisonRow>();
    for (var row : rows) {
      var change = toRow(row);
      BigDecimal profitDelta = null;
      if (change.grossProfitBefore() != null && change.grossProfitAfter() != null) {
        profitDelta = BatchPriceAdjustmentRules.money(change.grossProfitAfter().subtract(change.grossProfitBefore()));
      }
      BigDecimal salesBefore = null;
      BigDecimal salesAfter = null;
      BigDecimal salesDelta = null;
      if (salesAvailable) {
        var pair = salesCache.get(change.id());
        if (pair != null) {
          salesBefore = pair[0] == null ? null : pair[0].quantity();
          salesAfter = pair[1] == null ? null : pair[1].quantity();
          if (salesBefore != null && salesAfter != null) {
            salesDelta = salesAfter.subtract(salesBefore);
          }
        }
      }
      items.add(
          new PriceHistoryComparisonRow(
              change.id(),
              change.skuId(),
              change.skuCode(),
              change.skuName(),
              change.platformCode(),
              change.siteCode(),
              change.currency(),
              change.effectiveAt(),
              change.oldPrice(),
              change.newPrice(),
              change.priceDelta(),
              change.changeRatio(),
              change.direction(),
              change.changeSource(),
              change.reasonCode(),
              change.reasonText(),
              change.grossProfitBefore(),
              change.grossProfitAfter(),
              profitDelta,
              change.grossMarginBefore(),
              change.grossMarginAfter(),
              change.decisionReady(),
              change.profitNote(),
              salesAvailable,
              salesAvailable ? "" : salesNote,
              salesBefore,
              salesAfter,
              salesDelta));
    }
    String note =
        salesAvailable
            ? "销量对比窗口：变更前后各 "
                + windowDays
                + " 天。销量事实来自 9 订单履约的授权只读端点。价格与利润来自本服务。"
            : salesNote;
    return new PriceHistoryComparisonPage(items, total, page, size, salesAvailable, note);
  }

  private void applyComparisonFilters(
      StringBuilder where, List<Object> binds, PriceHistoryComparisonQuery query) {
    if (notBlank(query.keyword())) {
      where.append(" and (k.code ilike ? or k.name ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
    }
    if (query.skuId() != null) {
      where.append(" and h.sku_id=?::uuid");
      binds.add(query.skuId());
    }
    if (notBlank(query.platformCode())) {
      where.append(" and h.platform_code=?");
      binds.add(query.platformCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.siteCode())) {
      where.append(" and h.site_code=?");
      binds.add(query.siteCode().trim().toUpperCase(Locale.ROOT));
    }
    if (query.windowFrom() != null) {
      where.append(" and h.effective_at >= ?::timestamptz");
      binds.add(query.windowFrom().atStartOfDay().atOffset(ZoneOffset.UTC));
    }
    if (query.windowTo() != null) {
      where.append(" and h.effective_at < ?::timestamptz");
      binds.add(query.windowTo().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
    }
  }

  /** 变更前后的销量（各一段区间）。取不到就抛，调用方据此把销量标成不可用。 */
  private PriceHistorySalesTotal[] salesAround(PriceHistoryChangeRow change, int windowDays, String bearer) {
    var at = change.effectiveAt();
    var before =
        salesFacts.sales(
            bearer,
            change.skuId(),
            change.platformCode(),
            change.siteCode(),
            at.minusDays(windowDays),
            at);
    var after =
        salesFacts.sales(
            bearer,
            change.skuId(),
            change.platformCode(),
            change.siteCode(),
            at,
            at.plusDays(windowDays));
    return new PriceHistorySalesTotal[] {before, after};
  }

  /** 直接取一段区间的销量事实（未接线时抛 {@link SalesFactPort.SalesUnavailableException}）。 */
  public PriceHistorySalesTotal sales(
      String bearer,
      UUID skuId,
      String platformCode,
      String siteCode,
      OffsetDateTime from,
      OffsetDateTime to) {
    return salesFacts.sales(bearer, skuId, platformCode, siteCode, from, to);
  }

  // ================================================================ 原因字典

  /** 变更原因字典（全部，含已停用的）。 */
  public List<PriceHistoryReasonRow> reasons() {
    var rows =
        sql.fetch(
            "select id, code, label, category, enabled, sort_order, note, updated_at"
                + " from price_change_reason where tenant_id="
                + TENANT
                + " order by sort_order, code");
    var out = new ArrayList<PriceHistoryReasonRow>();
    for (var row : rows) {
      out.add(toReason(row));
    }
    return out;
  }

  /** 保存一条变更原因（按 code 覆盖）。 */
  public PriceHistoryReasonRow saveReason(AccessService.Access actor, PriceHistoryReasonInput input) {
    if (input == null) throw ProfitCalculatorService.badRequest("变更原因不能为空");
    String code = input.code() == null ? "" : input.code().trim();
    String label = input.label() == null ? "" : input.label().trim();
    if (code.isEmpty()) throw ProfitCalculatorService.badRequest("变更原因编码不能为空");
    if (label.isEmpty()) throw ProfitCalculatorService.badRequest("变更原因名称不能为空");
    PriceHistoryReasonCategory category = PriceHistoryReasonCategory.parse(input.category());
    if (category == null) {
      throw ProfitCalculatorService.badRequest("未知的变更原因分类：" + input.category());
    }
    sql.execute(
        "insert into price_change_reason(id,tenant_id,code,label,category,enabled,sort_order,note,"
            + "created_by,created_at,updated_at)"
            + " values(?::uuid,?::uuid,?,?,?,?,?,?,?::uuid,now(),now())"
            + " on conflict (tenant_id,code) do update set"
            + " label=excluded.label, category=excluded.category, enabled=excluded.enabled,"
            + " sort_order=excluded.sort_order, note=excluded.note, updated_at=now()",
        Ids.next(),
        actor.tenantId(),
        code,
        label,
        category.name(),
        input.enabled() == null || input.enabled(),
        input.sortOrder() == null ? 0 : input.sortOrder(),
        input.note() == null ? "" : input.note().trim(),
        actor.userId());
    return reasons().stream()
        .filter(r -> r.code().equals(code))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("变更原因保存后却读不回来：" + code));
  }

  private PriceHistoryReasonRow toReason(Record row) {
    PriceHistoryReasonCategory category = PriceHistoryReasonCategory.parse(row.get("category", String.class));
    return new PriceHistoryReasonRow(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("label", String.class),
        row.get("category", String.class),
        category == null ? DEFAULT_REASON_CATEGORY : category.label(),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
        row.get("sort_order", Integer.class) == null ? 0 : row.get("sort_order", Integer.class),
        row.get("note", String.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 内部

  private PriceHistoryChangeRow toRow(Record row) {
    PriceHistoryDirection direction = PriceHistoryDirection.parse(field(row, "direction", String.class));
    PriceHistoryChangeSource source = PriceHistoryChangeSource.parse(field(row, "change_source", String.class));
    var operator = field(row, "operator_id", UUID.class);
    return new PriceHistoryChangeRow(
        field(row, "id", UUID.class),
        field(row, "sku_id", UUID.class),
        field(row, "sku_code", String.class),
        field(row, "sku_name", String.class),
        field(row, "platform_code", String.class),
        field(row, "site_code", String.class),
        field(row, "currency", String.class),
        field(row, "old_price", BigDecimal.class),
        field(row, "new_price", BigDecimal.class),
        field(row, "price_delta", BigDecimal.class),
        field(row, "change_ratio", BigDecimal.class),
        field(row, "direction", String.class),
        direction == null ? "" : direction.label(),
        field(row, "change_source", String.class),
        source == null ? "" : source.label(),
        field(row, "reason_code", String.class),
        field(row, "reason_text", String.class),
        operator == null ? null : operator.toString(),
        field(row, "source_module", String.class),
        field(row, "source_reference", String.class),
        field(row, "event_key", String.class),
        field(row, "effective_at", OffsetDateTime.class),
        field(row, "recorded_at", OffsetDateTime.class),
        field(row, "gross_profit_before", BigDecimal.class),
        field(row, "gross_margin_before", BigDecimal.class),
        field(row, "gross_profit_after", BigDecimal.class),
        field(row, "gross_margin_after", BigDecimal.class),
        Boolean.TRUE.equals(field(row, "decision_ready", Boolean.class)),
        field(row, "profit_note", String.class),
        field(row, "created_at", OffsetDateTime.class));
  }

  /**
   * 取一列，**列不存在时返回 null**（而不是抛 {@link IllegalArgumentException}）。
   *
   * <p>🔴 为什么要这么写：jOOQ 的 {@code record.get(name, type)} 在列不存在时抛
   * {@code IllegalArgumentException}，而本服务的 {@code ApiErrorHandler} 把
   * {@code IllegalArgumentException} 翻成 <b>400「字段格式不正确」</b> ——
   * 一个「SELECT 少写了一列」的实现疏漏，会以「用户请求有问题」的假象出现在界面上。
   * 这里让缺列退化成 null（那一列就是没有值），把疏漏留在代码里而不是骗到用户面前。
   */
  private static <T> T field(Record row, String name, Class<T> type) {
    for (var candidate : row.fields()) {
      if (name.equalsIgnoreCase(candidate.getName())) {
        return row.get(candidate, type);
      }
    }
    return null;
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String upper(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
  }

  private static List<Object> concat(List<Object> first, List<Object> second) {
    var out = new ArrayList<Object>(first.size() + second.size());
    out.addAll(first);
    out.addAll(second);
    return out;
  }

  /** 异常的可用人话（不泄露堆栈）。 */
  private static String reasonOf(RuntimeException e) {
    if (e instanceof ResponseStatusException status) {
      String reason = status.getReason();
      return reason == null || reason.isBlank() ? status.getStatusCode().toString() : reason;
    }
    var message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }
}
