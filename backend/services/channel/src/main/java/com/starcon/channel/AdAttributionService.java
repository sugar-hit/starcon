package com.starcon.channel;

import static com.starcon.channel.AdAttributionModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 14.3.2.1 归因分析。
 *
 * <p>蓝图：「广告归因：把广告花费归因到 SKU/订单（支持平台归因窗口口径），计算广告后净利、
 * TACOS、增量效果估计。输入：平台广告归因数据；订单数据；利润数据；归因规则配置（末次点击/平台归因窗口）。
 * 输出：归因明细 + 广告后利润报表。关系：上游 = 14.3.1.3、13.4.1.2；下游 = 13.4 利润、5 定价。
 * <b>归因规则必须固定并公示</b>，规则变了历史报表不可比——这是广告与财务对账最容易吵架的点。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>把广告花费归因到 SKU/订单</b> → {@link #run}：按规则（末次点击 / 平台归因窗口）
 *       在窗口内选一次点击，命中的那次点击拿到这一单的归因，结果落 {@code ad_attribution_fact}。
 *   <li><b>支持平台归因窗口口径</b> → 规则的 {@code attribution_model} + {@code attribution_window_days}。
 *   <li><b>广告后净利 / TACOS</b> → {@link #report}：广告侧来自归因事实，
 *       利润侧来自 13.4.1.2（reporting 服务，<b>只经授权只读 API</b>）。
 *   <li><b>增量效果估计</b> → {@link #report} 的 {@code incrementalSalesEstimate}
 *       （<b>推导值，不是平台原值</b>，口径原文见 {@link #INCREMENTALITY_NOTE}）。
 * </ul>
 *
 * <p>🔴 本类最容易做丢的五件事，这里都挡住了：
 *
 * <ol>
 *   <li><b>规则版本进事实行</b>。规则是租户级配置，改了会 {@code version+1}；
 *       归因结果按 <b>(rule_id, rule_version)</b> 落库 ⇒ 改规则**不会改写历史归因**，
 *       历史报表仍然能被「当时那套口径」解释。报表默认看<b>当前版本</b>，
 *       也可以按 {@code ruleVersion} 翻历史口径做对比。
 *   <li><b>归不上的单列</b>。归不到 SKU、窗口内没有合格点击、平台没标记归因点击 ——
 *       这三类金额在汇总里单列，<b>不摊给任何 SKU，也不摊成 0</b>。
 *   <li><b>「算不出来」与「等于 0」分开</b>。分母为 0 ⇒ TACOS / ACOS / 增量效果给 {@code null}
 *       （不可比），不是 0。
 *   <li><b>先汇总再相除</b>。ACOS / TACOS / 广告销售占比一律 Σ分子 / Σ分母，
 *       <b>不是</b>各行比率的平均（那是数学错误）。
 *   <li><b>增量效果是推导值</b>。它不是平台给的事实 ⇒ 必须标为估算并给出口径原文，
 *       绝不伪装成平台原值。
 * </ol>
 *
 * <p>🔴 报表查询的性能纪律（照抄 14.3.1.1）：时间范围<b>必填</b>且封顶；
 * 谓词顺序对齐上游索引；<b>真分页</b>（LIMIT/OFFSET）；<b>汇总单独一条聚合查询</b>（统计全集，不是当前页）；
 * 跨服务调用放在<b>数据库事务之外</b>（不在事务里等 HTTP）。
 */
@Service
public class AdAttributionService {

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 归因模型取值域。 */
  static final List<String> ATTRIBUTION_MODELS = List.of("LAST_CLICK", "PLATFORM_WINDOW");

  /** 归因状态取值域（**点击 / 模型口径**的结论）。 */
  static final List<String> ATTRIBUTION_STATUSES =
      List.of("ATTRIBUTED", "NO_ELIGIBLE_CLICK", "NO_PLATFORM_ATTRIBUTED_CLICK");

  /** 🔴 时间跨度的上限（天）。超过直接拒，不做「悄悄截断」。 */
  static final int MAX_DAYS = 400;

  static final String DEFAULT_SORT = "ORDER_AT_DESC";

  /** 排序方式 → ORDER BY 子句。**白名单映射**：排序串绝不拼进 SQL。 */
  private static final Map<String, String> SORTS =
      Map.ofEntries(
          Map.entry("ORDER_AT_DESC", "f.order_at desc, f.external_order_no asc"),
          Map.entry("ORDER_AT_ASC", "f.order_at asc, f.external_order_no asc"),
          Map.entry("SPEND_DESC", "f.attributed_spend desc, f.order_at desc"),
          Map.entry("SPEND_ASC", "f.attributed_spend asc, f.order_at desc"),
          Map.entry("SALES_DESC", "f.attributed_sales desc, f.order_at desc"),
          Map.entry("SHOPS_DESC", "f.shop_code asc, f.order_at desc"),
          Map.entry("CAMPAIGN_DESC", "f.campaign_id asc, f.order_at desc"),
          Map.entry("STATUS_ASC", "f.attribution_status asc, f.order_at desc"));

  // ==========================================================================
  // 🔴 口径原文：写在返回值里，不写在注释里
  // ==========================================================================

  /**
   * 归因口径原文（**随明细、报表、导出一起返回**，CSV 表头与导出留痕里也写一份）。
   *
   * <p>🔴 规则变了历史报表就不可比 —— 所以规则必须固定、必须公示。
   */
  static final String ATTRIBUTION_NOTE =
      "归因规则必须固定并公示（规则变了历史报表不可比 —— 这是广告与财务对账最容易吵架的点）。"
          + "① **规则是租户级配置，一个租户同一时刻只有一套生效口径**"
          + "（数据库 partial unique index 钉死 ⇒ 两套口径同时生效时「这单归给了谁」没有答案）。"
          + "② **规则可以改，改了 version+1**；已算出的归因结果**不重算、不改写** ——"
          + "每条归因结果自带规则快照（编码 / 版本 / 归因模型 / 归因窗口），"
          + "所以历史报表仍然能被「当时那套口径」解释，报表也能按版本翻出历史口径做对比。"
          + "③ **LAST_CLICK（末次点击）**：归因窗口内**最后一次点击**拿到这一单的归因，其余点击落选。"
          + "④ **PLATFORM_WINDOW（平台归因窗口）**：采信**平台自己标记**的那一次归因点击；"
          + "平台没标记 ⇒ 这一单归因不成立（**不替平台编一个**）。"
          + "⑤ **归因窗口**按「点击到下单的真实时间差」算（timestamptz），超窗的点击不参与；"
          + "窗口 = 0 天时只有与下单同时刻的点击才算。"
          + "⑥ **归不上的金额单列**（归不到 SKU / 窗口内没有合格点击 / 平台没标记归因点击）："
          + "**不摊给任何 SKU，也不摊成 0** —— 摊成 0 等于说「这些广告没花钱」。"
          + "⑦ 本模块的「广告花费」是**订单级归因口径**（平台逐单归因的结果），"
          + "与 14.3.1.3 的「投放关系分摊口径」（活动 → Listing → SKU 两级等分）**不是同一个数**，"
          + "同一个 SKU 的「广告后净利」在两张报表上不相等是**正常的**。";

  /** 逐指标口径的一句话说明。 */
  static final String METRIC_DEFINITION_NOTE =
      "ACOS = Σ广告花费 / Σ广告销售额；TACOS = Σ广告花费 / 总销售额"
          + "（总销售额含自然单，不是广告销售额）；"
          + "广告销售占比 = 广告销售额 / 总销售额；"
          + "广告后净利 = 总净利 − 广告花费；广告占比利润 = 广告花费 / 总净利。"
          + "全部按「先汇总再相除」算（Σ分子 / Σ分母），**不是**各行比率的平均 —— "
          + "后者是数学错误，会把两条 ACOS 100% 的 SKU 说成整体 50%。"
          + "分母为 0 一律返回空值（不可比），不是 0。"
          + "🔴 不同币种不合并（本仓没有汇率源）：每一行带自己的币种，汇总按币种拆开。";

  /**
   * 增量效果估计的口径原文 —— **它是推导值，不是平台给的事实**。
   *
   * <p>🔴 蓝图要求输出「增量效果估计」，但平台只给归因结果、**不给增量**。
   * 所以这一项必须：① 明确写出计算口径；② 标记为估算；③ 说清它**不是**因果增量。
   */
  static final String INCREMENTALITY_NOTE =
      "增量效果估计是**本模块的推导值，不是平台给的事实**（平台只给归因结果，不给增量）。"
          + "口径（反事实单件均价外推法）："
          + "① 总销售额 S_total 与总件数 Q_total 取 13.4.1.2 的利润口径（上游读不到 ⇒ 这一项为空，**不是 0**）；"
          + "② 广告销售额 S_ad 与广告件数 Q_ad 取本模块的订单级归因结果（只算归因成立并归到 SKU 的）；"
          + "③ 自然部分：S_org = S_total − S_ad，Q_org = Q_total − Q_ad；"
          + "④ **反事实广告销售额** S_cf = Q_ad × (S_org / Q_org)"
          + "（假设这些广告件数按**自然单件均价**成交）；"
          + "⑤ **增量销售额估计 = S_ad − S_cf**，增量占比估计 = 增量销售额估计 / S_total。"
          + "⚠️ **口径边界（必须一起说清）**：这个估计衡量的是"
          + "「广告单与自然单的**单件均价结构差**」，**不是因果增量**。"
          + "真正的增量需要留出组 / 地域对照实验，平台不提供 ⇒ 本模块**不假装**有因果结论。"
          + "估计值**可以为负**（广告单件均价低于自然单时），负值如实显示，不截成 0。"
          + "Q_org = 0（自然件数为 0）⇒ 算不出来 ⇒ 给空值而不是 0。";

  /** 数据截至时刻的口径说明。 */
  static final String DATA_AS_OF_NOTE =
      "「数据截至 X 时间」= 平台广告归因数据里**最大的 data_as_of**（这批数据是平台什么时候算出来的）。"
          + "导入的批次越新，水位越高 —— 水位之前的空白是「还没导入到」，不是「没有广告归因」。";

  /** 空态原因。 */
  static final List<String> EMPTY_REASONS =
      List.of(
          "NO_IMPORTED_DATA",
          "NO_FACTS_FOR_RULE",
          "FILTERED_EMPTY",
          "NO_ACTIVE_RULE",
          "NO_CAMPAIGN_BINDING");

  private final DSLContext sql;
  private final AccessService access;
  private final AdProductProfitPort profitPort;
  private final AdProductCatalogPort catalogPort;

  public AdAttributionService(
      DSLContext sql,
      AccessService access,
      AdProductProfitPort profitPort,
      AdProductCatalogPort catalogPort) {
    this.sql = sql;
    this.access = access;
    this.profitPort = profitPort;
    this.catalogPort = catalogPort;
  }

  // ==========================================================================
  // 小工具
  // ==========================================================================

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private long count(String query, Object... binds) {
    Long v = one(Long.class, query, binds);
    return v == null ? 0L : v;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static BigDecimal round(BigDecimal value, int scale) {
    return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
  }

  /** 🔴 分母为 0 ⇒ null（不可比），不是 0。 */
  private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
    if (numerator == null || denominator == null) return null;
    if (denominator.signum() == 0) return null;
    return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 绑定，不来自任何客户端头）。 */
  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  // ==========================================================================
  // 查询条件
  // ==========================================================================

  /**
   * 归因明细的查询条件。
   *
   * <p>🔴 {@code from} / {@code to} **必填**：没有时间范围的查询就是全表扫。
   */
  public record RecordQuery(
      UUID ruleId,
      Long ruleVersion,
      LocalDate from,
      LocalDate to,
      String status,
      String shopCode,
      UUID campaignId,
      UUID skuId,
      String platformCode,
      String keyword,
      String sort,
      int page,
      int size) {}

  /** 广告后利润报表的查询条件。 */
  public record ReportQuery(
      UUID ruleId,
      Long ruleVersion,
      LocalDate from,
      LocalDate to,
      String shopCode,
      UUID campaignId,
      UUID skuId,
      String currency,
      String sort,
      int page,
      int size) {}

  private void checkRange(LocalDate from, LocalDate to) {
    if (from == null || to == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "统计必须给出起始日与截止日（from 与 to 都是必填）");
    }
    if (from.isAfter(to)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计起始日不能晚于截止日");
    }
    long days = ChronoUnit.DAYS.between(from, to) + 1;
    if (days > MAX_DAYS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "时间跨度过大：一次最多统计 " + MAX_DAYS + " 天，当前 " + days + " 天");
    }
  }

  /** 归一化并校验归因明细的查询条件（非法值直接 400，不静默回落）。 */
  public RecordQuery normalizeRecordQuery(
      UUID ruleId,
      Long ruleVersion,
      LocalDate from,
      LocalDate to,
      String status,
      String shopCode,
      UUID campaignId,
      UUID skuId,
      String platformCode,
      String keyword,
      String sort,
      int page,
      int size) {
    checkRange(from, to);
    String statusKey = text(status).toUpperCase(Locale.ROOT);
    if (!statusKey.isEmpty() && !ATTRIBUTION_STATUSES.contains(statusKey)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "归因状态取值域是 " + ATTRIBUTION_STATUSES);
    }
    String sortKey = text(sort).toUpperCase(Locale.ROOT);
    if (sortKey.isEmpty()) sortKey = DEFAULT_SORT;
    if (!SORTS.containsKey(sortKey)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "排序方式不在白名单里");
    }
    return new RecordQuery(
        ruleId,
        ruleVersion,
        from,
        to,
        statusKey.isEmpty() ? null : statusKey,
        blankToNull(shopCode),
        campaignId,
        skuId,
        blankToNull(platformCode),
        blankToNull(keyword),
        sortKey,
        Math.max(page, 0),
        Math.min(Math.max(size, 1), 200));
  }

  /** 归一化并校验报表的查询条件。 */
  public ReportQuery normalizeReportQuery(
      UUID ruleId,
      Long ruleVersion,
      LocalDate from,
      LocalDate to,
      String shopCode,
      UUID campaignId,
      UUID skuId,
      String currency,
      String sort,
      int page,
      int size) {
    checkRange(from, to);
    String sortKey = text(sort).toUpperCase(Locale.ROOT);
    if (sortKey.isEmpty()) sortKey = "SPEND_DESC";
    if (!SORTS.containsKey(sortKey)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "排序方式不在白名单里");
    }
    String currencyKey = blankToNull(currency);
    if (currencyKey != null) {
      currencyKey = currencyKey.toUpperCase(Locale.ROOT);
      if (!currencyKey.matches("^[A-Z]{3}$")) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是三个大写字母");
      }
    }
    return new ReportQuery(
        ruleId,
        ruleVersion,
        from,
        to,
        blankToNull(shopCode),
        campaignId,
        skuId,
        currencyKey,
        sortKey,
        Math.max(page, 0),
        Math.min(Math.max(size, 1), 200));
  }

  /**
   * 统计区间的边界（**应用所在时区的自然日**）。
   *
   * <p>🔴 绑定 {@link OffsetDateTime} 而不是 {@code Timestamp.valueOf(LocalDateTime)} ——
   * 后者会静默偏 −28800s。
   */
  private static OffsetDateTime windowStart(LocalDate from) {
    return from.atStartOfDay().atZone(ZoneId.systemDefault()).toOffsetDateTime();
  }

  private static OffsetDateTime windowEndExclusive(LocalDate to) {
    return to.plusDays(1).atStartOfDay().atZone(ZoneId.systemDefault()).toOffsetDateTime();
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  public AdAttributionCapabilities capabilities() {
    return new AdAttributionCapabilities(
        List.of(
            new AdAttributionCapabilityDimension(
                "attribution_rule",
                "归因规则（末次点击 / 平台归因窗口；租户级唯一生效口径 + 版本）",
                true,
                "ad_attribution_rule（V404）+ ad_attribution_fact 的规则快照",
                "",
                "没有固定并公示的规则 ⇒ 同一张报表两个人算出两个数，广告与财务对账必然吵架"),
            new AdAttributionCapabilityDimension(
                "platform_attribution_data",
                "平台广告归因数据（订单级归因候选 + 点击触点）",
                true,
                "ad_attribution_order / ad_attribution_click（V404）",
                "",
                "没有平台逐单归因数据 ⇒ 归因只能靠猜，「这单是广告带来的吗」没有答案"),
            new AdAttributionCapabilityDimension(
                "last_click",
                "**末次点击**归因口径",
                true,
                "ad_attribution_click 的 click_at（V404）",
                "",
                "一次订单有多个点击时，不知道该算在哪一次上 ⇒ 归因不可复核"),
            new AdAttributionCapabilityDimension(
                "platform_window",
                "**平台归因窗口**口径",
                true,
                "ad_attribution_click.platform_attributed（V404）",
                "",
                "平台自己标的归因点击没采信 ⇒ 与平台后台对不上"),
            new AdAttributionCapabilityDimension(
                "attribution_detail",
                "归因明细（逐单：命中哪一击、归到了哪个 SKU、归因状态）",
                true,
                "ad_attribution_fact（V404）",
                "",
                "只有汇总没有明细 ⇒ 广告与财务逐单对账时没有依据"),
            new AdAttributionCapabilityDimension(
                "profit_side",
                "利润侧（总销售额 / 总件数 / 净利 / 利润口径广告费）",
                true,
                "13.4.1.2 SKU 利润（reporting 服务，授权只读 API）",
                "",
                "没有总销售额就没有 TACOS，也没有广告后净利 —— 只看广告 ROAS 答不了"
                    + "「广告是带来增量还是抢自然单」"),
            new AdAttributionCapabilityDimension(
                "incrementality",
                "**增量效果估计**（推导值，不是平台原值）",
                true,
                "本模块按反事实单件均价外推法推导（口径见 incrementalityNote）",
                "",
                "不知道广告带来的是增量还是抢自然单 ⇒ 投放预算没有依据"),
            new AdAttributionCapabilityDimension(
                "ad_spend_reconciliation",
                "活动级花费与订单级归因花费的对账缺口",
                true,
                "14.1.1.1 ad_campaign_daily_metric（V273）+ 本模块归因事实",
                "",
                "两种粒度的花费对不上时没有解释 ⇒ 财务质疑广告费时答不上来"),
            new AdAttributionCapabilityDimension(
                "causal_incrementality",
                "**因果增量**（留出组 / 地域对照实验）",
                false,
                "—",
                "平台（广告主侧）",
                "平台归因数据是**相关性**口径：平台自己标的归因点击就是它认为的归因，"
                    + "但它**不是**随机对照实验的结果。本模块给的是**明确标注口径的推导估计**，"
                    + "并把它与平台原值分开；真正的因果增量需要广告主自己跑留出组实验，"
                    + "平台不提供这个数据 ⇒ 不假装有因果结论。"),
            new AdAttributionCapabilityDimension(
                "multi_currency_unification",
                "跨币种统一口径（把不同币种换算成同一货币再汇总）",
                false,
                "—",
                "汇率源（本仓没有汇率表，也没有汇率同步器）",
                "报表按币种拆开：不同币种的花费 / 销售额不会被加成一个假总数，"
                    + "代价是跨币种的横向比较暂时做不了"),
            new AdAttributionCapabilityDimension(
                "order_count_incrementality",
                "增量**订单数**估计（广告单数 ÷ 总单数的增量口径）",
                false,
                "—",
                "13.4.1.2（利润事实只给金额与件数，没有 SKU 级总单数）",
                "本模块的增量估计走**单件均价结构差**（件数口径），"
                    + "因为利润侧给的是**件数**、不是**订单数** —— "
                    + "**没有 SKU 级总单数这个分母 ⇒ 不编一个分母出来**。"
                    + "广告侧自己的订单数（ad_attributed_orders）是可看的。")),
        METRIC_DEFINITIONS(true, ""),
        ATTRIBUTION_MODELS,
        ATTRIBUTION_STATUSES,
        EMPTY_REASONS,
        ATTRIBUTION_NOTE,
        METRIC_DEFINITION_NOTE,
        INCREMENTALITY_NOTE,
        "上游 = 14.1.1.1 广告活动（已交付，同库同服务）：ad_campaign / ad_campaign_daily_metric（V273）；"
            + "14.1.1.2 活动详情（已交付，同库同服务）：ad_campaign_group_product（V277）；"
            + "4.1.1.1 Listing 列表（已交付，同库同服务）：listing / listing_variant（V171）；"
            + "13.4.1.2 SKU 利润（已交付，reporting 服务，**只经授权只读 API**）；"
            + "03 商品主数据（catalog 服务，**只经授权只读 API**：SKU 编码 / SPU 归属）。",
        "下游 = 13.4 利润（广告后净利与广告占比利润回流）、5 定价。"
            + "🔴 本模块**不**做跨币种换算（没有汇率源）、**不**给因果增量结论（平台不提供对照实验数据）、"
            + "**不**给增量订单数估计（缺 SKU 级总单数这个分母）—— "
            + "这三件事都在能力声明里显式标 available=false，不用假数字糊过去。");
  }

  /**
   * 逐指标口径定义 —— **表头就是从这里渲染的**。
   *
   * @param profitAvailable 利润上游这次读到了没有（false ⇒ 利润侧的指标全部 available=false）
   */
  static List<AdAttributionMetricDefinition> METRIC_DEFINITIONS(
      boolean profitAvailable, String profitUnavailableReason) {
    String profitSource = "13.4.1.2 SKU 利润（reporting 服务，授权只读 API）";
    String profitReason =
        profitUnavailableReason.isBlank()
            ? "上游 13.4.1.2 这次没读到利润事实。"
            : profitUnavailableReason;
    String profitOwner = "13.4.1.2（reporting 服务）";
    return List.of(
        new AdAttributionMetricDefinition(
            "ad_spend",
            "归因广告花费",
            true,
            "归因广告花费 = Σ（归因成立且归到该 SKU 的订单行的花费）",
            "ad_attribution_fact（V404，本模块归因事实）",
            "",
            ""),
        new AdAttributionMetricDefinition(
            "ad_sales",
            "归因广告销售额",
            true,
            "归因广告销售额 = Σ（归因成立且归到该 SKU 的订单行的广告销售额）",
            "ad_attribution_fact（V404，本模块归因事实）",
            "",
            ""),
        new AdAttributionMetricDefinition(
            "ad_orders",
            "归因广告订单数",
            true,
            "归因广告订单数 = Σ（归因成立且归到该 SKU 的订单行的订单数）",
            "ad_attribution_fact（V404，本模块归因事实）",
            "",
            ""),
        new AdAttributionMetricDefinition(
            "ad_units",
            "归因广告件数",
            true,
            "归因广告件数 = Σ（归因成立且归到该 SKU 的订单行的件数）",
            "ad_attribution_fact（V404，本模块归因事实）",
            "",
            ""),
        new AdAttributionMetricDefinition(
            "unattributed_spend",
            "归不上的广告花费",
            true,
            "归不上的广告花费 = Σ（归因不成立 或 归不到 SKU 的订单行的花费）",
            "ad_attribution_fact（V404）",
            "",
            ""),
        new AdAttributionMetricDefinition(
            "total_sales",
            "总销售额",
            profitAvailable,
            "总销售额 = 13.4.1.2 的单品收入（该 SKU × 该币种，含自然单）",
            profitSource,
            profitAvailable ? "" : profitOwner,
            profitAvailable
                ? ""
                : profitReason
                    + " ⇒ 总销售额这一格取不到（**不是 0**：0 会被读成「这个 SKU 没卖出去」）。"),
        new AdAttributionMetricDefinition(
            "total_units",
            "总件数",
            profitAvailable,
            "总件数 = 13.4.1.2 单品收入项的件数",
            profitSource,
            profitAvailable ? "" : profitOwner,
            profitAvailable ? "" : profitReason + " ⇒ 总件数这一格取不到。"),
        new AdAttributionMetricDefinition(
            "acos",
            "ACOS",
            true,
            "ACOS = Σ归因广告花费 / Σ归因广告销售额",
            "V404 归因事实（本服务层现算）",
            "",
            ""),
        new AdAttributionMetricDefinition(
            "tacos",
            "TACOS",
            profitAvailable,
            "TACOS = Σ归因广告花费 / 总销售额（总销售额含自然单，不是广告销售额）",
            profitAvailable ? "V404 归因事实 + " + profitSource : "—",
            profitAvailable ? "" : profitOwner,
            profitAvailable
                ? ""
                : "TACOS 没有分母 ⇒ 不出数值。不用 0 当分母，也不用广告销售额顶替总销售额"
                    + "（那算出来的是 ACOS 不是 TACOS）。"),
        new AdAttributionMetricDefinition(
            "ad_sales_share",
            "广告销售占比",
            profitAvailable,
            "广告销售占比 = 归因广告销售额 / 总销售额（判断广告是带来增量还是抢自然单的关键）",
            profitAvailable ? "V404 归因事实 + " + profitSource : "—",
            profitAvailable ? "" : profitOwner,
            profitAvailable
                ? ""
                : "分母（总销售额）取不到 ⇒ 这一格取不到。"
                    + "只看广告 ROAS 答不了「广告是带来增量还是抢自然单」这个问题。"),
        new AdAttributionMetricDefinition(
            "net_profit",
            "总净利",
            profitAvailable,
            "总净利 = 13.4.1.2 的净利（收入 − 成本；成本项缺项时上游给 null）",
            profitSource,
            profitAvailable ? "" : profitOwner,
            profitAvailable ? "" : profitReason + " ⇒ 总净利这一格取不到。"),
        new AdAttributionMetricDefinition(
            "ad_after_net_profit",
            "广告后净利",
            profitAvailable,
            "广告后净利 = 总净利 − 归因广告花费（任一项缺失时为 null）",
            profitAvailable ? "13.4.1.2 + V404" : "—",
            profitAvailable ? "" : profitOwner,
            profitAvailable
                ? ""
                : "总净利取不到 ⇒ 广告后净利算不出来（**不是 0**：0 会被读成「广告后不赚钱」）。"),
        new AdAttributionMetricDefinition(
            "ad_profit_share",
            "广告占比利润",
            profitAvailable,
            "广告占比利润 = 归因广告花费 / 总净利（分母 ≤ 0 时不可比）",
            profitAvailable ? "13.4.1.2 + V404" : "—",
            profitAvailable ? "" : profitOwner,
            profitAvailable ? "" : profitReason + " ⇒ 广告占比利润这一格取不到。"),
        new AdAttributionMetricDefinition(
            "incremental_sales_estimate",
            "增量销售额估计",
            profitAvailable,
            "增量销售额估计 = Σ广告销售额 − Σ广告件数 × (自然单件均价)（**推导值，见 incrementalityNote**）",
            profitAvailable ? "V404 归因事实 + " + profitSource : "—",
            profitAvailable ? "" : profitOwner,
            profitAvailable
                ? ""
                : "增量估计依赖总销售额与总件数 ⇒ 上游读不到 ⇒ 这一格取不到。"
                    + "它是**推导值**，不是平台原值；口径与边界见 incrementalityNote。"),
        new AdAttributionMetricDefinition(
            "incremental_share",
            "增量占比估计",
            profitAvailable,
            "增量占比估计 = 增量销售额估计 / 总销售额",
            profitAvailable ? "V404 归因事实 + " + profitSource : "—",
            profitAvailable ? "" : profitOwner,
            profitAvailable ? "" : profitReason + " ⇒ 增量占比这一格取不到。"));
  }

  // ==========================================================================
  // 选项
  // ==========================================================================

  public AdAttributionOptions options() {
    List<AdAttributionOption> platforms =
        sql.fetch("select id, code, name from platform order by position, code")
            .stream()
            .map(r -> new AdAttributionOption(r.get("code", String.class), r.get("name", String.class)))
            .toList();

    List<AdAttributionOption> shops =
        sql.fetch("select code, name from shop where tenant_id = " + TENANT + " order by code")
            .stream()
            .map(r -> new AdAttributionOption(r.get("code", String.class), r.get("name", String.class)))
            .toList();

    List<AdAttributionOption> models =
        List.of(
            new AdAttributionOption("LAST_CLICK", "末次点击（窗口内最后一次点击拿到归因）"),
            new AdAttributionOption("PLATFORM_WINDOW", "平台归因窗口（采信平台自己标记的那次点击）"));

    List<AdAttributionOption> statuses =
        List.of(
            new AdAttributionOption("ATTRIBUTED", "归因成立（命中了一次点击）"),
            new AdAttributionOption("NO_ELIGIBLE_CLICK", "归因不成立：窗口内没有合格点击"),
            new AdAttributionOption("NO_PLATFORM_ATTRIBUTED_CLICK", "归因不成立：平台没标记归因点击"));

    List<AdAttributionOption> sorts =
        List.of(
            new AdAttributionOption("ORDER_AT_DESC", "下单时间（新到旧）"),
            new AdAttributionOption("ORDER_AT_ASC", "下单时间（旧到新）"),
            new AdAttributionOption("SPEND_DESC", "归因花费高到低"),
            new AdAttributionOption("SPEND_ASC", "归因花费低到高"),
            new AdAttributionOption("SALES_DESC", "归因销售额高到低"),
            new AdAttributionOption("SHOPS_DESC", "按店铺"),
            new AdAttributionOption("CAMPAIGN_DESC", "按活动"),
            new AdAttributionOption("STATUS_ASC", "按归因状态"));

    return new AdAttributionOptions(
        models,
        statuses,
        sorts,
        shops,
        platforms,
        METRIC_DEFINITIONS(true, ""),
        METRIC_DEFINITION_NOTE,
        ATTRIBUTION_NOTE,
        INCREMENTALITY_NOTE,
        "时间范围必填（from / to 都是必填项），一次最多统计 "
            + MAX_DAYS
            + " 天。统计区间按**应用所在时区**的自然日切分。跨度过大直接报错，不做悄悄截断。");
  }

  // ==========================================================================
  // 归因规则
  // ==========================================================================

  /** 规则列表（真分页 + 真筛选）。 */
  public AdAttributionRulePage rules(String status, String keyword, int page, int size) {
    StringBuilder where = new StringBuilder(" where tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    String statusKey = text(status).toUpperCase(Locale.ROOT);
    if (!statusKey.isEmpty()) {
      if (!"ACTIVE".equals(statusKey) && !"PAUSED".equals(statusKey)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态取值域是 ACTIVE / PAUSED");
      }
      where.append(" and status = ?");
      binds.add(statusKey);
    }
    String kw = blankToNull(keyword);
    if (kw != null) {
      where.append(" and (rule_code like ? escape '\\' or note like ? escape '\\')");
      String like = "%" + escapeLike(kw) + "%";
      binds.add(like);
      binds.add(like);
    }
    long total = count("select count(*) from ad_attribution_rule" + where, binds.toArray());
    List<AdAttributionRuleRow> items =
        sql.fetch(
                "select id, rule_code, attribution_model, attribution_window_days, status, note,"
                    + " version, created_at, updated_at from ad_attribution_rule"
                    + where
                    + " order by created_at desc, rule_code asc limit ? offset ?",
                concat(binds, Math.min(Math.max(size, 1), 200), Math.max(page, 0) * Math.min(Math.max(size, 1), 200)))
            .stream()
            .map(this::ruleRow)
            .toList();
    return new AdAttributionRulePage(items, total, Math.max(page, 0), Math.min(Math.max(size, 1), 200));
  }

  /** 当前生效的规则（没有生效规则时返回 null —— **不是**空规则）。 */
  public AdAttributionRuleRow currentRule() {
    Record r =
        sql.fetchOne(
            "select id, rule_code, attribution_model, attribution_window_days, status, note,"
                + " version, created_at, updated_at from ad_attribution_rule where tenant_id = "
                + TENANT
                + " and status = 'ACTIVE' limit 1");
    return r == null ? null : ruleRow(r);
  }

  private AdAttributionRuleRow ruleRow(Record r) {
    return new AdAttributionRuleRow(
        r.get("id", UUID.class),
        r.get("rule_code", String.class),
        r.get("attribution_model", String.class),
        r.get("attribution_window_days", Integer.class),
        r.get("status", String.class),
        r.get("version", Long.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 保存归因规则（新建或改）。
   *
   * <p>🔴 三条纪律：
   *
   * <ol>
   *   <li><b>一个租户只能有一套生效口径</b> ⇒ 启用新规则前先把旧的置为 PAUSED。
   *   <li><b>改规则 = 版本 +1</b> ⇒ 已算出的归因结果不重算、不改写（事实行里带规则快照）。
   *   <li><b>不能把唯一一套生效口径停用</b> ⇒ 那样租户就没有归因口径了，直接 409 并说清怎么办。
   * </ol>
   */
  public AdAttributionRuleRow saveRule(SaveAdAttributionRuleRequest request) {
    String ruleCode = text(request.ruleCode());
    if (ruleCode.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则编码不能为空");
    }
    String model = text(request.attributionModel()).toUpperCase(Locale.ROOT);
    if (!ATTRIBUTION_MODELS.contains(model)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "归因模型取值域是 " + ATTRIBUTION_MODELS);
    }
    if (request.attributionWindowDays() < 0 || request.attributionWindowDays() > 365) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "归因窗口必须在 0 ~ 365 天之间");
    }
    UUID tenant = currentTenant();

    UUID id = request.id();
    if (id == null) {
      id = Ids.next();
      if (request.activate()) {
        pauseOtherActiveRules(tenant, id);
      }
      sql.execute(
          "insert into ad_attribution_rule (tenant_id,id,rule_code,attribution_model,"
              + " attribution_window_days,status,note,version,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,0,now(),now())",
          tenant,
          id,
          ruleCode,
          model,
          request.attributionWindowDays(),
          request.activate() ? "ACTIVE" : "PAUSED",
          text(request.note()));
      return requireRule(id);
    }

    AdAttributionRuleRow existing = requireRule(id);
    if (!request.activate() && "ACTIVE".equals(existing.status())) {
      long others =
          count(
              "select count(*) from ad_attribution_rule where tenant_id = ?::uuid"
                  + " and status = 'ACTIVE' and id <> ?::uuid",
              tenant,
              id);
      if (others == 0) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "本租户必须保留一套生效的归因口径：停用这一套之前，请先启用另一套规则。");
      }
    }
    if (request.activate()) {
      pauseOtherActiveRules(tenant, id);
    }
    sql.execute(
        "update ad_attribution_rule set rule_code = ?, attribution_model = ?,"
            + " attribution_window_days = ?, status = ?, note = ?, version = version + 1,"
            + " updated_at = now() where tenant_id = ?::uuid and id = ?::uuid",
        ruleCode,
        model,
        request.attributionWindowDays(),
        request.activate() ? "ACTIVE" : "PAUSED",
        text(request.note()),
        tenant,
        id);
    return requireRule(id);
  }

  private void pauseOtherActiveRules(UUID tenant, UUID keepId) {
    sql.execute(
        "update ad_attribution_rule set status = 'PAUSED', updated_at = now(), version = version + 1"
            + " where tenant_id = ?::uuid and status = 'ACTIVE' and id <> ?::uuid",
        tenant,
        keepId);
  }

  private AdAttributionRuleRow requireRule(UUID id) {
    Record r =
        sql.fetchOne(
            "select id, rule_code, attribution_model, attribution_window_days, status, note,"
                + " version, created_at, updated_at from ad_attribution_rule where tenant_id = "
                + TENANT
                + " and id = ?",
            id);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "归因规则不存在");
    }
    return ruleRow(r);
  }

  // ==========================================================================
  // 平台广告归因数据导入
  // ==========================================================================

  /**
   * 导入平台广告归因数据（订单级归因候选 + 点击触点）。
   *
   * <p>🔴 幂等：同一订单（平台 + 订单号 + 活动 + SKU）重复导入是**刷新**，不是插重复行
   * （DB 层的 partial expression unique index 钉死 ⇒ 金额不会翻倍）。
   *
   * <p>🔴 被拒绝的行**逐条列明原因**，不静默丢弃 —— 「导入了 100 行、只落了 80 行」
   * 这种事必须看得见。
   */
  public AdAttributionImportResult importAttribution(AdAttributionImportRequest request) {
    if (request.orders() == null || request.orders().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "导入内容为空：至少要给一条订单归因数据");
    }
    if (request.orders().size() > 5000) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "一次最多导入 5000 条订单归因数据，当前 " + request.orders().size() + " 条");
    }
    OffsetDateTime dataAsOf =
        request.dataAsOf() == null ? OffsetDateTime.now(ZoneId.systemDefault()) : request.dataAsOf();
    UUID tenant = currentTenant();

    List<String> rejected = new ArrayList<>();
    int writtenOrders = 0;
    int writtenClicks = 0;

    for (AdAttributionOrderImport row : request.orders()) {
      String externalOrderNo = text(row.externalOrderNo());
      if (externalOrderNo.isEmpty()) {
        rejected.add("平台侧订单号不能为空（已跳过这一行）");
        continue;
      }
      if (row.orderAt() == null) {
        rejected.add("订单 " + externalOrderNo + "：下单时刻不能为空（已跳过这一行）");
        continue;
      }
      String shopCode = text(row.shopCode());
      if (shopCode.isEmpty()) {
        rejected.add("订单 " + externalOrderNo + "：店铺编码不能为空（已跳过这一行）");
        continue;
      }
      String platformCode = text(row.platformCode());
      if (platformCode.isEmpty()) {
        rejected.add("订单 " + externalOrderNo + "：平台编码不能为空（已跳过这一行）");
        continue;
      }
      UUID campaignId = resolveCampaign(row, tenant);
      if (campaignId == null) {
        rejected.add(
            "订单 "
                + externalOrderNo
                + "：在本租户找不到对应的广告活动（活动 id / 平台侧活动 id + 店铺 + 平台）"
                + "（已跳过这一行）");
        continue;
      }

      BigDecimal spend = nonNegative(row.attributedSpend(), "归因花费", externalOrderNo, rejected);
      BigDecimal sales = nonNegative(row.attributedSales(), "归因销售额", externalOrderNo, rejected);
      BigDecimal orders = nonNegative(row.attributedOrders(), "归因订单数", externalOrderNo, rejected);
      BigDecimal units = nonNegative(row.attributedUnits(), "归因件数", externalOrderNo, rejected);
      if (spend == null || sales == null || orders == null || units == null) continue;

      String currency = text(row.currency()).toUpperCase(Locale.ROOT);
      if (!currency.isEmpty() && !currency.matches("^[A-Z]{3}$")) {
        rejected.add("订单 " + externalOrderNo + "：币种必须是三个大写字母（已跳过这一行）");
        continue;
      }

      UUID orderId =
          one(
              UUID.class,
              "insert into ad_attribution_order (tenant_id,id,campaign_id,shop_code,platform_code,"
                  + " external_order_no,order_at,internal_sku_id,currency,attributed_spend,"
                  + " attributed_sales,attributed_orders,attributed_units,data_as_of,imported_at)"
                  + " values (?,?,?,?,?,?,?::timestamptz,?,?,?,?,?,?,?::timestamptz,now())"
                  + " on conflict (tenant_id,campaign_id,platform_code,external_order_no,"
                  + " coalesce(internal_sku_id,'00000000-0000-0000-0000-000000000000'::uuid))"
                  + " do update set order_at = excluded.order_at, currency = excluded.currency,"
                  + " attributed_spend = excluded.attributed_spend,"
                  + " attributed_sales = excluded.attributed_sales,"
                  + " attributed_orders = excluded.attributed_orders,"
                  + " attributed_units = excluded.attributed_units,"
                  + " data_as_of = excluded.data_as_of, imported_at = now()"
                  + " returning id",
              tenant,
              Ids.next(),
              campaignId,
              shopCode,
              platformCode,
              externalOrderNo,
              row.orderAt(),
              row.internalSkuId(),
              currency,
              spend,
              sales,
              orders,
              units,
              dataAsOf);
      if (orderId == null) {
        rejected.add("订单 " + externalOrderNo + "：写入失败（已跳过这一行）");
        continue;
      }
      writtenOrders++;

      if (row.clicks() != null) {
        for (AdAttributionClickImport click : row.clicks()) {
          String clickId = text(click.clickId());
          if (clickId.isEmpty()) {
            rejected.add("订单 " + externalOrderNo + "：平台侧点击 id 不能为空（已跳过这一次点击）");
            continue;
          }
          if (click.clickAt() == null) {
            rejected.add("订单 " + externalOrderNo + " 的点击 " + clickId + "：点击时刻不能为空（已跳过）");
            continue;
          }
          sql.execute(
              "insert into ad_attribution_click (tenant_id,id,order_id,click_id,click_at,"
                  + " platform_attributed,imported_at)"
                  + " values (?,?,?,?,?::timestamptz,?,now())"
                  + " on conflict (tenant_id,order_id,click_id) do update"
                  + " set click_at = excluded.click_at,"
                  + " platform_attributed = excluded.platform_attributed, imported_at = now()",
              tenant,
              Ids.next(),
              orderId,
              clickId,
              click.clickAt(),
              click.platformAttributed());
          writtenClicks++;
        }
      }
    }

    return new AdAttributionImportResult(
        request.orders().size(),
        writtenOrders,
        writtenClicks,
        rejected,
        dataAsOf,
        "导入的是**平台广告归因数据**（订单级归因候选 + 点击触点）。"
            + "重复导入同一订单是**刷新**，不是插重复行（幂等键：租户 + 活动 + 平台 + 订单号 + SKU）。"
            + "导入数据**不等于**已归因 —— 归因结论要跑一次归因计算（POST /run）才有。");
  }

  private BigDecimal nonNegative(
      BigDecimal value, String label, String externalOrderNo, List<String> rejected) {
    if (value == null) return BigDecimal.ZERO;
    if (value.signum() < 0) {
      rejected.add("订单 " + externalOrderNo + "：" + label + "不能为负（已跳过这一行）");
      return null;
    }
    return value;
  }

  /** 解析活动：给了内部 id 就用它；否则按（平台侧活动 id + 店铺 + 平台）在本租户里找。 */
  private UUID resolveCampaign(AdAttributionOrderImport row, UUID tenant) {
    if (row.campaignId() != null) {
      Long hit =
          count(
              "select count(*) from ad_campaign where tenant_id = ?::uuid and id = ?::uuid",
              tenant,
              row.campaignId());
      return hit == 0 ? null : row.campaignId();
    }
    String external = text(row.externalCampaignId());
    if (external.isEmpty()) return null;
    return one(
            UUID.class,
            "select id from ad_campaign where tenant_id = ?::uuid and external_campaign_id = ?"
                + " and shop_code = ? and platform_id = (select id from platform where code = ?)"
                + " limit 1",
            tenant,
            external,
            text(row.shopCode()),
            text(row.platformCode()));
  }

  // ==========================================================================
  // 归因计算
  // ==========================================================================

  /**
   * 跑一次归因计算。
   *
   * <p>🔴 三条纪律：
   *
   * <ol>
   *   <li><b>归因结果按 (规则, 规则版本, 订单) 落库</b> ⇒ 改规则（版本 +1）后重跑，
   *       产生的是**新版本的事实**，旧版本的事实原样保留 —— 历史报表不会被改写。
   *   <li><b>默认不重算</b>（{@code recompute=false}）⇒ 已归因的是事实。
   *   <li><b>跨版本不混算</b>：同一版本里一条订单只归因一次。
   * </ol>
   */
  public AdAttributionRunResult run(AdAttributionRunRequest request) {
    if (request.from() == null || request.to() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "归因计算必须给出起始日与截止日（from 与 to 都是必填）");
    }
    if (request.from().isAfter(request.to())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计起始日不能晚于截止日");
    }
    UUID tenant = currentTenant();
    AdAttributionRuleRow rule = resolveRule(request.ruleId());
    boolean recompute = Boolean.TRUE.equals(request.recompute());

    OffsetDateTime start = windowStart(request.from());
    OffsetDateTime end = windowEndExclusive(request.to());

    int candidates =
        (int)
            count(
                "select count(*) from ad_attribution_order where tenant_id = ?::uuid"
                    + " and order_at >= ?::timestamptz and order_at < ?::timestamptz",
                tenant,
                start,
                end);

    // 🔴 归因计算主体：一条 SQL 算完（窗口内的合格点击、命中的那一次点击、归因状态）。
    //   合格点击 = click_at <= order_at 且 order_at - click_at <= 归因窗口天数。
    //   LAST_CLICK      ⇒ 取窗口内最后一次点击（click_id 倒序兜底 ⇒ 结果可复现）。
    //   PLATFORM_WINDOW ⇒ 取平台自己标记的那一次点击（同样取最近的一次）。
    // 🔴 规则参数放在最前面的 params CTE 里 ⇒ 绑定顺序固定（不靠记 SQL 文本里 ? 的位置）。
    String sqlText =
        """
        with params as (
          select ?::uuid as rule_id, ?::text as rule_code, ?::bigint as rule_version,
                 ?::text as model, ?::int as window_days
        ),
        candidates as (
          select o.id, o.campaign_id, o.shop_code, o.platform_code, o.external_order_no,
                 o.internal_sku_id, o.currency, o.order_at,
                 o.attributed_spend, o.attributed_sales, o.attributed_orders, o.attributed_units
            from ad_attribution_order o
           where o.tenant_id = %s
             and o.order_at >= ?::timestamptz and o.order_at < ?::timestamptz
        ),
        eligible as (
          select c.order_id, c.click_id, c.click_at, c.platform_attributed,
                 row_number() over (partition by c.order_id
                                    order by c.click_at desc, c.click_id desc) as rn_all
            from ad_attribution_click c
            join candidates cand on cand.id = c.order_id
            cross join params
           where c.tenant_id = %s
             and c.click_at <= cand.order_at
             and c.click_at >= cand.order_at - (params.window_days * interval '1 day')
        ),
        platform_clicks as (
          select e.order_id, e.click_id, e.click_at,
                 row_number() over (partition by e.order_id
                                    order by e.click_at desc, e.click_id desc) as rn_platform
            from eligible e
           where e.platform_attributed
        ),
        agg as (
          select e.order_id,
                 count(*) as eligible_count,
                 max(case when e.rn_all = 1 then e.click_id end) as chosen_click_id,
                 max(case when e.rn_all = 1 then e.click_at end) as chosen_click_at
            from eligible e
           group by e.order_id
        ),
        agg_platform as (
          select p.order_id,
                 max(case when p.rn_platform = 1 then p.click_id end) as platform_click_id,
                 max(case when p.rn_platform = 1 then p.click_at end) as platform_click_at
            from platform_clicks p
           group by p.order_id
        ),
        computed as (
          select cand.*, coalesce(a.eligible_count, 0) eligible_count,
                 a.chosen_click_id, a.chosen_click_at,
                 ap.platform_click_id, ap.platform_click_at,
                 params.rule_id, params.rule_code, params.rule_version,
                 params.model, params.window_days
            from candidates cand
            left join agg a on a.order_id = cand.id
            left join agg_platform ap on ap.order_id = cand.id
            cross join params
        )
        insert into ad_attribution_fact (
          tenant_id, id, rule_id, rule_code, rule_version, attribution_model,
          attribution_window_days, order_id, campaign_id, shop_code, platform_code,
          external_order_no, internal_sku_id, currency, order_at,
          chosen_click_id, chosen_click_at, attribution_status,
          attributed_spend, attributed_sales, attributed_orders, attributed_units,
          eligible_click_count, attributed_at)
        select %s::uuid, gen_random_uuid(), computed.rule_id, computed.rule_code,
               computed.rule_version, computed.model, computed.window_days,
               computed.id, computed.campaign_id, computed.shop_code, computed.platform_code,
               computed.external_order_no, computed.internal_sku_id, computed.currency,
               computed.order_at,
               case when computed.model = 'LAST_CLICK' then coalesce(computed.chosen_click_id, '')
                    else coalesce(computed.platform_click_id, '') end,
               case when computed.model = 'LAST_CLICK' then computed.chosen_click_at
                    else computed.platform_click_at end,
               case when coalesce(computed.eligible_count, 0) = 0 then 'NO_ELIGIBLE_CLICK'
                    when computed.model = 'PLATFORM_WINDOW' and computed.platform_click_id is null
                      then 'NO_PLATFORM_ATTRIBUTED_CLICK'
                    else 'ATTRIBUTED' end,
               computed.attributed_spend, computed.attributed_sales, computed.attributed_orders,
               computed.attributed_units,
               coalesce(computed.eligible_count, 0), now()
          from computed
        %s
        returning id
        """
            .formatted(
                TENANT,
                TENANT,
                TENANT,
                recompute
                    ? "on conflict (tenant_id, rule_id, rule_version, order_id) do update set"
                        + " chosen_click_id = excluded.chosen_click_id,"
                        + " chosen_click_at = excluded.chosen_click_at,"
                        + " attribution_status = excluded.attribution_status,"
                        + " attributed_spend = excluded.attributed_spend,"
                        + " attributed_sales = excluded.attributed_sales,"
                        + " attributed_orders = excluded.attributed_orders,"
                        + " attributed_units = excluded.attributed_units,"
                        + " eligible_click_count = excluded.eligible_click_count,"
                        + " internal_sku_id = excluded.internal_sku_id,"
                        + " currency = excluded.currency,"
                        + " order_at = excluded.order_at,"
                        + " attributed_at = now()"
                    : "on conflict (tenant_id, rule_id, rule_version, order_id) do nothing");

    List<Record> written =
        sql.fetch(
            sqlText,
            rule.id(),
            rule.ruleCode(),
            rule.version(),
            rule.attributionModel(),
            rule.attributionWindowDays(),
            start,
            end);

    int writtenFacts = written.size();

    // 归因状态分布（按本次窗口内的全部事实算，不只是刚写的那些）。
    Map<String, Long> statusCounts = new LinkedHashMap<>();
    for (String status : ATTRIBUTION_STATUSES) statusCounts.put(status, 0L);
    for (Record r :
        sql.fetch(
            "select attribution_status, count(*) as c from ad_attribution_fact"
                + " where tenant_id = ?::uuid and rule_id = ?::uuid and rule_version = ?"
                + " and order_at >= ?::timestamptz and order_at < ?::timestamptz group by attribution_status",
            tenant,
            rule.id(),
            rule.version(),
            start,
            end)) {
      statusCounts.put(r.get("attribution_status", String.class), r.get("c", Long.class));
    }
    long attributedFacts = statusCounts.getOrDefault("ATTRIBUTED", 0L);
    long unattributedFacts = writtenFacts == 0 ? 0 : Math.max(0, writtenFacts - attributedFacts);

    List<AdAttributionStatusCount> statusList =
        ATTRIBUTION_STATUSES.stream()
            .map(s -> new AdAttributionStatusCount(s, statusLabel(s), statusCounts.getOrDefault(s, 0L)))
            .toList();

    return new AdAttributionRunResult(
        rule.id(),
        rule.ruleCode(),
        rule.version(),
        rule.attributionModel(),
        rule.attributionWindowDays(),
        candidates,
        writtenFacts,
        (int) attributedFacts,
        (int) unattributedFacts,
        statusList,
        "本次归因用的是规则「"
            + rule.ruleCode()
            + "」的第 "
            + rule.version()
            + " 版（"
            + rule.attributionModel()
            + "，归因窗口 "
            + rule.attributionWindowDays()
            + " 天）。"
            + "归因结果按 (规则, 规则版本, 订单) 落库 ⇒ **改规则不会改写历史归因**。"
            + (recompute
                ? "本次是**重算**：同一版本下已归因的行被刷新了。"
                : "本次**不重算**：已归因的行原样保留（已归因是事实，报表已经拿它跟财务对过账了）。"));
  }

  private AdAttributionRuleRow resolveRule(UUID ruleId) {
    if (ruleId != null) return requireRule(ruleId);
    AdAttributionRuleRow current = currentRule();
    if (current == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "本租户当前没有生效的归因规则：请先在归因规则里启用一套口径");
    }
    return current;
  }

  /** 归因状态的中文说明。 */
  static String statusLabel(String status) {
    if ("ATTRIBUTED".equals(status)) return "归因成立（命中了一次点击）";
    if ("NO_ELIGIBLE_CLICK".equals(status)) return "归因不成立：归因窗口内没有合格点击";
    return "归因不成立：平台归因窗口口径下平台没标记归因点击";
  }

  // ==========================================================================
  // 归因明细
  // ==========================================================================

  /** 归因明细（真分页 + 真筛选 + 真排序 + 汇总）。 */
  public AdAttributionRecordPage records(RecordQuery query) {
    RuleScope scope = resolveScope(query.ruleId(), query.ruleVersion());
    Filters filters = buildRecordFilters(query, scope);

    long total =
        count("select count(*) from ad_attribution_fact f" + filters.where(), filters.binds().toArray());

    List<Object> pageBinds = new ArrayList<>(filters.binds());
    pageBinds.add(Math.min(Math.max(query.size(), 1), 200));
    pageBinds.add(Math.max(query.page(), 0) * Math.min(Math.max(query.size(), 1), 200));
    List<AdAttributionRecordRow> items =
        sql.fetch(
                "select f.id, f.external_order_no, f.platform_code, f.shop_code, f.campaign_id,"
                    + " coalesce(c.campaign_name, '') campaign_name,"
                    + " f.internal_sku_id, f.currency, f.order_at, f.attribution_status,"
                    + " f.chosen_click_id, f.chosen_click_at, f.eligible_click_count,"
                    + " f.attributed_spend, f.attributed_sales, f.attributed_orders,"
                    + " f.attributed_units, f.rule_code, f.rule_version, f.attribution_model,"
                    + " f.attribution_window_days, f.attributed_at"
                    + " from ad_attribution_fact f"
                    + " left join ad_campaign c on c.tenant_id = f.tenant_id and c.id = f.campaign_id"
                    + filters.where()
                    + " order by "
                    + SORTS.get(query.sort())
                    + " limit ? offset ?",
                pageBinds.toArray())
            .stream()
            .map(this::recordRow)
            .toList();

    List<AdAttributionRecordSummary> summaries = recordSummaries(filters);

    String emptyReason = null;
    String emptyNote = "";
    if (total == 0) {
      long imported =
          count("select count(*) from ad_attribution_order where tenant_id = " + TENANT);
      if (scope.rule() == null) {
        emptyReason = "NO_ACTIVE_RULE";
        emptyNote = "本租户当前没有生效的归因规则 —— 先启用一套口径，再跑一次归因计算。";
      } else if (imported == 0) {
        emptyReason = "NO_IMPORTED_DATA";
        emptyNote =
            "还没有导入任何平台广告归因数据 —— 这不是「没有广告归因」，是还没导入数据。";
      } else if (factsForScope(scope) == 0) {
        emptyReason = "NO_FACTS_FOR_RULE";
        emptyNote =
            "规则「"
                + scope.rule().ruleCode()
                + "」下还没有归因结果 —— 先跑一次归因计算（POST /run）。";
      } else {
        emptyReason = "FILTERED_EMPTY";
        emptyNote = "这个时间区间里确实有归因结果，但被当前的筛选条件全部排除了。放宽条件再试。";
      }
    }

    return new AdAttributionRecordPage(
        items,
        total,
        query.page(),
        query.size(),
        scope.rule() == null ? null : scope.rule().id(),
        scope.rule() == null ? "" : scope.rule().ruleCode(),
        scope.rule() == null ? 0 : scope.rule().version(),
        scope.rule() == null ? "" : scope.rule().attributionModel(),
        scope.rule() == null ? 0 : scope.rule().attributionWindowDays(),
        summaries,
        emptyReason,
        emptyNote,
        METRIC_DEFINITION_NOTE,
        ATTRIBUTION_NOTE,
        INCREMENTALITY_NOTE);
  }

  private AdAttributionRecordRow recordRow(Record r) {
    String status = r.get("attribution_status", String.class);
    return new AdAttributionRecordRow(
        r.get("id", UUID.class),
        r.get("external_order_no", String.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("campaign_id", UUID.class),
        r.get("campaign_name", String.class),
        r.get("internal_sku_id", UUID.class),
        r.get("currency", String.class),
        r.get("order_at", OffsetDateTime.class),
        status,
        statusLabel(status),
        "ATTRIBUTED".equals(status) && r.get("internal_sku_id", UUID.class) != null,
        r.get("chosen_click_id", String.class),
        r.get("chosen_click_at", OffsetDateTime.class),
        r.get("eligible_click_count", Integer.class),
        r.get("attributed_spend", BigDecimal.class),
        r.get("attributed_sales", BigDecimal.class),
        r.get("attributed_orders", BigDecimal.class),
        r.get("attributed_units", BigDecimal.class),
        r.get("rule_code", String.class),
        r.get("rule_version", Long.class),
        r.get("attribution_model", String.class),
        r.get("attribution_window_days", Integer.class),
        r.get("attributed_at", OffsetDateTime.class));
  }

  /** 一条筛选条件（where 子句 + 绑定值）。 */
  private record Filters(String where, List<Object> binds) {}

  /** 归因明细的筛选条件。 */
  private Filters buildRecordFilters(RecordQuery query, RuleScope scope) {
    StringBuilder where =
        new StringBuilder(
            " where f.tenant_id = "
                + TENANT
                + " and f.order_at >= ?::timestamptz and f.order_at < ?::timestamptz");
    List<Object> binds = new ArrayList<>();
    binds.add(windowStart(query.from()));
    binds.add(windowEndExclusive(query.to()));
    if (scope.rule() != null) {
      where.append(" and f.rule_id = ?::uuid and f.rule_version = ?");
      binds.add(scope.rule().id());
      binds.add(scope.version());
    }
    if (query.status() != null) {
      where.append(" and f.attribution_status = ?");
      binds.add(query.status());
    }
    if (query.shopCode() != null) {
      where.append(" and f.shop_code = ?");
      binds.add(query.shopCode());
    }
    if (query.campaignId() != null) {
      where.append(" and f.campaign_id = ?::uuid");
      binds.add(query.campaignId());
    }
    if (query.skuId() != null) {
      where.append(" and f.internal_sku_id = ?::uuid");
      binds.add(query.skuId());
    }
    if (query.platformCode() != null) {
      where.append(" and f.platform_code = ?");
      binds.add(query.platformCode());
    }
    if (query.keyword() != null) {
      where.append(" and f.external_order_no like ? escape '\\'");
      binds.add("%" + escapeLike(query.keyword()) + "%");
    }
    return new Filters(where.toString(), binds);
  }

  /** 归因明细的汇总（**不受分页影响**：统计的是筛选后的全集）。 */
  private List<AdAttributionRecordSummary> recordSummaries(Filters filters) {
    Map<String, AdAttributionRecordSummary> byCurrency = new LinkedHashMap<>();
    Map<String, Map<String, Long>> statusByCurrency = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select f.currency, f.attribution_status, f.attributed,"
                + " f.attributed_spend, f.attributed_sales, f.attributed_orders, f.attributed_units"
                + " from ad_attribution_fact f"
                + filters.where(),
            filters.binds().toArray())) {
      String currency = r.get("currency", String.class) == null ? "" : r.get("currency", String.class);
      AdAttributionRecordSummary summary =
          byCurrency.computeIfAbsent(
              currency,
              c ->
                  new AdAttributionRecordSummary(
                      c,
                      0,
                      0,
                      0,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      List.of(),
                      ATTRIBUTION_NOTE));
      boolean attributed = Boolean.TRUE.equals(r.get("attributed", Boolean.class));
      BigDecimal spend = nz(r.get("attributed_spend", BigDecimal.class));
      BigDecimal sales = nz(r.get("attributed_sales", BigDecimal.class));
      BigDecimal orders = nz(r.get("attributed_orders", BigDecimal.class));
      BigDecimal units = nz(r.get("attributed_units", BigDecimal.class));
      byCurrency.put(
          currency,
          new AdAttributionRecordSummary(
              currency,
              summary.rowCount() + 1,
              summary.attributedRows() + (attributed ? 1 : 0),
              summary.unattributedRows() + (attributed ? 0 : 1),
              summary.totalSpend().add(spend),
              summary.totalSales().add(sales),
              summary.totalOrders().add(orders),
              summary.totalUnits().add(units),
              summary.attributedSpend().add(attributed ? spend : BigDecimal.ZERO),
              summary.attributedSales().add(attributed ? sales : BigDecimal.ZERO),
              summary.unattributedSpend().add(attributed ? BigDecimal.ZERO : spend),
              summary.unattributedSales().add(attributed ? BigDecimal.ZERO : sales),
              summary.unattributedOrders().add(attributed ? BigDecimal.ZERO : orders),
              summary.statusCounts(),
              ATTRIBUTION_NOTE));
      statusByCurrency
          .computeIfAbsent(currency, c -> new LinkedHashMap<>())
          .merge(r.get("attribution_status", String.class), 1L, Long::sum);
    }
    List<AdAttributionRecordSummary> out = new ArrayList<>();
    for (Map.Entry<String, AdAttributionRecordSummary> entry : byCurrency.entrySet()) {
      AdAttributionRecordSummary s = entry.getValue();
      Map<String, Long> counts = statusByCurrency.getOrDefault(entry.getKey(), Map.of());
      List<AdAttributionStatusCount> statusList =
          ATTRIBUTION_STATUSES.stream()
              .map(st -> new AdAttributionStatusCount(st, statusLabel(st), counts.getOrDefault(st, 0L)))
              .toList();
      out.add(
          new AdAttributionRecordSummary(
              s.currency(),
              s.rowCount(),
              s.attributedRows(),
              s.unattributedRows(),
              s.totalSpend(),
              s.totalSales(),
              s.totalOrders(),
              s.totalUnits(),
              s.attributedSpend(),
              s.attributedSales(),
              s.unattributedSpend(),
              s.unattributedSales(),
              s.unattributedOrders(),
              statusList,
              ATTRIBUTION_NOTE));
    }
    return out;
  }

  /** 归因明细的单条详情。 */
  public AdAttributionRecordRow record(UUID id) {
    Record r =
        sql.fetchOne(
            "select f.id, f.external_order_no, f.platform_code, f.shop_code, f.campaign_id,"
                + " coalesce(c.campaign_name, '') campaign_name,"
                + " f.internal_sku_id, f.currency, f.order_at, f.attribution_status,"
                + " f.chosen_click_id, f.chosen_click_at, f.eligible_click_count,"
                + " f.attributed_spend, f.attributed_sales, f.attributed_orders,"
                + " f.attributed_units, f.rule_code, f.rule_version, f.attribution_model,"
                + " f.attribution_window_days, f.attributed_at"
                + " from ad_attribution_fact f"
                + " left join ad_campaign c on c.tenant_id = f.tenant_id and c.id = f.campaign_id"
                + " where f.tenant_id = "
                + TENANT
                + " and f.id = ?",
            id);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "归因结果不存在");
    }
    return recordRow(r);
  }

  // ==========================================================================
  // 广告后利润报表
  // ==========================================================================

  /** 广告后利润报表（真分页 + 真筛选 + 真排序 + 汇总 + 口径与规则原文）。 */
  public AdAttributionReportPage report(ReportQuery query, AccessService.Access actor, String bearer) {
    // ---- 第一步：广告侧（数据库事务内）----
    // 🔴 规则作用域**也在事务内**解析：RLS 靠 app.current_tenant 才认得出本租户的行，
    //   在事务外查规则会查不到（404），那不是「没有规则」而是「租户上下文没绑」。
    ReportSide side =
        access.run(
            actor,
            "channel.operation.read",
            () -> {
              RuleScope scope = resolveScope(query.ruleId(), query.ruleVersion());
              return new ReportSide(scope, loadReportAdSide(query, scope));
            });
    RuleScope scope = side.scope();
    ReportAdSide adSide = side.adSide();

    // ---- 第二步：跨服务只读（**事务之外**：不在事务里等 HTTP）----
    AdProductProfitPort.ProfitSnapshot profit = profitPort.fetch(query.from(), query.to(), bearer);
    AdProductCatalogPort.CatalogListSnapshot catalog = catalogPort.listIdentities(bearer);

    List<AdAttributionReportRow> items = buildReportRows(query, scope, adSide, catalog, profit);
    List<AdAttributionReportSummary> summaries = buildReportSummaries(adSide, catalog, profit);

    OffsetDateTime dataAsOf =
        access.run(
            actor,
            "channel.operation.read",
            () ->
                one(
                    OffsetDateTime.class,
                    "select max(data_as_of) from ad_attribution_order where tenant_id = " + TENANT));

    String emptyReason = null;
    String emptyNote = "";
    if (adSide.total() == 0) {
      long imported =
          count("select count(*) from ad_attribution_order where tenant_id = " + TENANT);
      if (scope.rule() == null) {
        emptyReason = "NO_ACTIVE_RULE";
        emptyNote = "本租户当前没有生效的归因规则 —— 先启用一套口径，再跑一次归因计算。";
      } else if (imported == 0) {
        emptyReason = "NO_IMPORTED_DATA";
        emptyNote = "还没有导入任何平台广告归因数据 —— 先导入，再跑一次归因计算。";
      } else if (factsForScope(scope) == 0) {
        emptyReason = "NO_FACTS_FOR_RULE";
        emptyNote =
            "规则「"
                + scope.rule().ruleCode()
                + "」下还没有归因结果 —— 先跑一次归因计算（POST /run）。";
      } else {
        emptyReason = "FILTERED_EMPTY";
        emptyNote = "这个时间区间里确实有归因结果，但被当前的筛选条件全部排除了。放宽条件再试。";
      }
    }

    return new AdAttributionReportPage(
        items,
        adSide.total(),
        query.page(),
        query.size(),
        scope.rule() == null ? null : scope.rule().id(),
        scope.rule() == null ? "" : scope.rule().ruleCode(),
        scope.rule() == null ? 0 : scope.rule().version(),
        scope.rule() == null ? "" : scope.rule().attributionModel(),
        scope.rule() == null ? 0 : scope.rule().attributionWindowDays(),
        summaries,
        dataAsOf,
        emptyReason,
        emptyNote,
        METRIC_DEFINITIONS(profit.available(), profit.unavailableReason()),
        METRIC_DEFINITION_NOTE,
        ATTRIBUTION_NOTE,
        INCREMENTALITY_NOTE,
        profit.available(),
        profit.unavailableReason(),
        catalog.available(),
        catalog.unavailableReason());
  }

  /** 规则的作用域（规则 + 版本）。 */
  private record RuleScope(AdAttributionRuleRow rule, long version) {}

  /**
   * 解析规则作用域。
   *
   * <p>🔴 没指定规则时用**当前生效规则**；没指定版本时用该规则的**当前版本**。
   * 查历史口径 ⇒ 显式传 {@code ruleId + ruleVersion}。
   */
  private RuleScope resolveScope(UUID ruleId, Long ruleVersion) {
    AdAttributionRuleRow rule = ruleId != null ? requireRule(ruleId) : currentRule();
    if (rule == null) return new RuleScope(null, 0);
    long version = ruleVersion != null ? ruleVersion : rule.version();
    return new RuleScope(rule, version);
  }

  private long factsForScope(RuleScope scope) {
    if (scope.rule() == null) return 0;
    return count(
        "select count(*) from ad_attribution_fact where tenant_id = " + TENANT
            + " and rule_id = ?::uuid and rule_version = ?",
        scope.rule().id(),
        scope.version());
  }

  /** 规则作用域 + 广告侧（一次事务里查完）。 */
  private record ReportSide(RuleScope scope, ReportAdSide adSide) {}

  /** 报表的广告侧（一次查完：归因成立并归到 SKU 的按 SKU 汇总、归不上的按币种汇总）。 */
  private record ReportAdSide(      List<AdSkuSide> skuRows,
      Map<String, BigDecimal> unattributedSpend,
      Map<String, BigDecimal> unattributedSales,
      Map<String, BigDecimal> unattributedOrders,
      Map<String, BigDecimal> unattributedUnits,
      Map<String, BigDecimal> campaignSpend,
      long total) {}

  /** 一个 SKU × 一个币种的广告侧汇总。 */
  private record AdSkuSide(
      UUID skuId, String currency, BigDecimal spend, BigDecimal sales, BigDecimal orders, BigDecimal units) {}

  private ReportAdSide loadReportAdSide(ReportQuery query, RuleScope scope) {
    StringBuilder where =
        new StringBuilder(
            " where f.tenant_id = "
                + TENANT
                + " and f.order_at >= ?::timestamptz and f.order_at < ?::timestamptz");
    List<Object> binds = new ArrayList<>();
    binds.add(windowStart(query.from()));
    binds.add(windowEndExclusive(query.to()));
    if (scope.rule() != null) {
      where.append(" and f.rule_id = ?::uuid and f.rule_version = ?");
      binds.add(scope.rule().id());
      binds.add(scope.version());
    }
    if (query.shopCode() != null) {
      where.append(" and f.shop_code = ?");
      binds.add(query.shopCode());
    }
    if (query.campaignId() != null) {
      where.append(" and f.campaign_id = ?::uuid");
      binds.add(query.campaignId());
    }
    if (query.skuId() != null) {
      where.append(" and f.internal_sku_id = ?::uuid");
      binds.add(query.skuId());
    }
    if (query.currency() != null) {
      where.append(" and f.currency = ?");
      binds.add(query.currency());
    }

    // 🔴 归因成立并归到 SKU 的那一部分（**进报表的行**）。
    List<AdSkuSide> skuRows = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select f.internal_sku_id, f.currency,"
                + " sum(f.attributed_spend) spend, sum(f.attributed_sales) sales,"
                + " sum(f.attributed_orders) orders, sum(f.attributed_units) units"
                + " from ad_attribution_fact f"
                + where
                + " and f.attributed"
                + " group by f.internal_sku_id, f.currency"
                + " order by sum(f.attributed_spend) desc, f.internal_sku_id asc",
            binds.toArray())) {
      skuRows.add(
          new AdSkuSide(
              r.get("internal_sku_id", UUID.class),
              r.get("currency", String.class) == null ? "" : r.get("currency", String.class),
              nz(r.get("spend", BigDecimal.class)),
              nz(r.get("sales", BigDecimal.class)),
              nz(r.get("orders", BigDecimal.class)),
              nz(r.get("units", BigDecimal.class))));
    }

    // 🔴 归不上的那一部分（**单列**，不摊给任何 SKU，也不摊成 0）。
    Map<String, BigDecimal> unSpend = new LinkedHashMap<>();
    Map<String, BigDecimal> unSales = new LinkedHashMap<>();
    Map<String, BigDecimal> unOrders = new LinkedHashMap<>();
    Map<String, BigDecimal> unUnits = new LinkedHashMap<>();
    for (Record r :
        sql.fetch(
            "select f.currency, sum(f.attributed_spend) spend, sum(f.attributed_sales) sales,"
                + " sum(f.attributed_orders) orders, sum(f.attributed_units) units"
                + " from ad_attribution_fact f"
                + where
                + " and not f.attributed group by f.currency",
            binds.toArray())) {
      String currency = r.get("currency", String.class) == null ? "" : r.get("currency", String.class);
      unSpend.put(currency, nz(r.get("spend", BigDecimal.class)));
      unSales.put(currency, nz(r.get("sales", BigDecimal.class)));
      unOrders.put(currency, nz(r.get("orders", BigDecimal.class)));
      unUnits.put(currency, nz(r.get("units", BigDecimal.class)));
    }

    // 🔴 活动级总花费（14.1.1.1 的 ad_campaign_daily_metric，同库同服务）——
    //   用来回答「活动花的钱有多少落到了订单上」。它是**另一种粒度**，
    //   两者不保证逐分对账 ⇒ 差额单列为「对账缺口」，可正可负，如实显示。
    Map<String, BigDecimal> campaignSpend = new LinkedHashMap<>();
    if (scope.rule() != null) {
      // 🔴 活动集合 = 本次口径下**出现过的活动**（含归因不成立的），窗口取同一段自然日。
      String campaignWhere =
          " and dm.campaign_id in (select distinct f.campaign_id from ad_attribution_fact f"
              + " where f.tenant_id = "
              + TENANT
              + " and f.rule_id = ?::uuid and f.rule_version = ?"
              + " and f.order_at >= ?::timestamptz and f.order_at < ?::timestamptz)";
      for (Record r :
          sql.fetch(
              "select c.currency, sum(dm.spend) spend"
                  + " from ad_campaign_daily_metric dm"
                  + " join ad_campaign c on c.tenant_id = dm.tenant_id and c.id = dm.campaign_id"
                  + " where dm.tenant_id = "
                  + TENANT
                  + " and dm.metric_date >= ?::date and dm.metric_date <= ?::date"
                  + campaignWhere
                  + " group by c.currency",
              query.from(),
              query.to(),
              scope.rule().id(),
              scope.version(),
              windowStart(query.from()),
              windowEndExclusive(query.to()))) {
        String currency = r.get("currency", String.class) == null ? "" : r.get("currency", String.class);
        campaignSpend.put(currency, nz(r.get("spend", BigDecimal.class)));
      }
    }

    return new ReportAdSide(
        skuRows, unSpend, unSales, unOrders, unUnits, campaignSpend, skuRows.size());
  }

  private List<AdAttributionReportRow> buildReportRows(
      ReportQuery query,
      RuleScope scope,
      ReportAdSide adSide,
      AdProductCatalogPort.CatalogListSnapshot catalog,
      AdProductProfitPort.ProfitSnapshot profit) {
    List<AdSkuSide> all = new ArrayList<>(adSide.skuRows());
    // 排序（白名单映射；排序串绝不拼进 SQL）。
    String sortKey = query.sort();
    all.sort(
        switch (sortKey) {
          case "SPEND_ASC" -> java.util.Comparator.comparing(AdSkuSide::spend);
          case "SALES_DESC" -> java.util.Comparator.comparing(AdSkuSide::sales).reversed();
          case "ORDER_AT_DESC", "ORDER_AT_ASC" -> java.util.Comparator.comparing(AdSkuSide::skuId);
          default -> java.util.Comparator.comparing(AdSkuSide::spend).reversed();
        });

    int from = query.page() * query.size();
    int to = Math.min(from + query.size(), all.size());
    List<AdSkuSide> page = from >= all.size() ? List.of() : all.subList(from, to);

    List<AdAttributionReportRow> rows = new ArrayList<>();
    for (AdSkuSide side : page) {
      AdProductCatalogPort.SkuIdentity identity =
          catalog.bySkuId().get(side.skuId());
      String skuCode = identity == null ? "" : identity.skuCode();
      String spuCode = identity == null ? "" : identity.spuCode();

      AdProductProfitPort.SkuProfit skuProfit =
          skuCode.isEmpty()
              ? null
              : profit.bySkuAndCurrency().get(skuCode + "|" + side.currency());
      BigDecimal totalSales = skuProfit == null ? null : skuProfit.revenue();
      BigDecimal totalUnits = skuProfit == null ? null : skuProfit.revenueQuantity();
      BigDecimal netProfit = skuProfit == null ? null : skuProfit.netProfit();
      BigDecimal profitAdFee = skuProfit == null ? null : skuProfit.adFee();

      BigDecimal incremental = null;
      BigDecimal incrementalShare = null;
      String profitNote = "";
      if (skuProfit == null) {
        profitNote = profit.available()
            ? "13.4.1.2 里没有这个 SKU × 币种的利润事实（**不是利润为 0**）"
            : profit.unavailableReason();
      } else if (totalSales == null || totalUnits == null) {
        profitNote = "13.4.1.2 的这个 SKU 缺总销售额或总件数 ⇒ 增量效果估计算不出来（**不是 0**）";
      } else {
        BigDecimal incrementalValue = incrementalSales(side, totalSales, totalUnits);
        incremental = incrementalValue;
        if (incrementalValue != null && totalSales.signum() != 0) {
          incrementalShare = ratio(incrementalValue, totalSales);
        }
      }

      BigDecimal adAfterNetProfit =
          netProfit == null ? null : netProfit.subtract(side.spend());
      BigDecimal adProfitShare = ratio(side.spend(), netProfit);

      rows.add(
          new AdAttributionReportRow(
              side.skuId(),
              skuCode,
              spuCode,
              side.currency(),
              side.spend(),
              side.sales(),
              side.orders(),
              side.units(),
              totalSales,
              totalUnits,
              netProfit,
              profitAdFee,
              ratio(side.spend(), side.sales()),
              ratio(side.spend(), totalSales),
              ratio(side.sales(), totalSales),
              adAfterNetProfit,
              adProfitShare,
              incremental,
              incrementalShare,
              profitNote,
              scope.rule() == null ? "" : scope.rule().ruleCode(),
              scope.version(),
              scope.rule() == null ? "" : scope.rule().attributionModel(),
              scope.rule() == null ? 0 : scope.rule().attributionWindowDays()));
    }
    return rows;
  }

  /**
   * 增量销售额估计（反事实单件均价外推法）。
   *
   * <p>🔴 口径原文见 {@link #INCREMENTALITY_NOTE}。{@code null} = **算不出来**
   * （自然件数为 0 / 利润侧没数），**不是 0**。
   */
  private static BigDecimal incrementalSales(AdSkuSide side, BigDecimal totalSales, BigDecimal totalUnits) {
    BigDecimal organicSales = totalSales.subtract(nz(side.sales()));
    BigDecimal organicUnits = nz(totalUnits).subtract(nz(side.units()));
    if (organicUnits.signum() <= 0) return null;
    BigDecimal organicUnitPrice = organicSales.divide(organicUnits, 8, RoundingMode.HALF_UP);
    BigDecimal counterfactual = nz(side.units()).multiply(organicUnitPrice);
    return nz(side.sales()).subtract(counterfactual).setScale(4, RoundingMode.HALF_UP);
  }

  private List<AdAttributionReportSummary> buildReportSummaries(
      ReportAdSide adSide,
      AdProductCatalogPort.CatalogListSnapshot catalog,
      AdProductProfitPort.ProfitSnapshot profit) {
    Map<String, BigDecimal> spend = new LinkedHashMap<>();
    Map<String, BigDecimal> sales = new LinkedHashMap<>();
    Map<String, BigDecimal> orders = new LinkedHashMap<>();
    Map<String, BigDecimal> units = new LinkedHashMap<>();
    for (AdSkuSide side : adSide.skuRows()) {
      spend.merge(side.currency(), side.spend(), BigDecimal::add);
      sales.merge(side.currency(), side.sales(), BigDecimal::add);
      orders.merge(side.currency(), side.orders(), BigDecimal::add);
      units.merge(side.currency(), side.units(), BigDecimal::add);
    }

    // 利润侧：按币种归集（**只用「广告侧与利润侧都有数」的 SKU** 算增量估计）。
    Map<String, BigDecimal> revenue = new LinkedHashMap<>();
    Map<String, BigDecimal> totalUnits = new LinkedHashMap<>();
    Map<String, BigDecimal> netProfit = new LinkedHashMap<>();
    Map<String, BigDecimal> incSales = new LinkedHashMap<>();
    Map<String, BigDecimal> incUnits = new LinkedHashMap<>();
    Map<String, BigDecimal> incRevenue = new LinkedHashMap<>();
    Map<String, BigDecimal> incTotalUnits = new LinkedHashMap<>();
    Map<String, Long> skuCount = new LinkedHashMap<>();
    for (AdSkuSide side : adSide.skuRows()) {
      skuCount.merge(side.currency(), 1L, Long::sum);
      // 利润侧按 **SKU 编码** 索引 ⇒ 这里需要 catalog 给的 SKU 编码。
      AdProductCatalogPort.SkuIdentity identity = catalog.bySkuId().get(side.skuId());
      if (identity == null || identity.skuCode() == null || identity.skuCode().isEmpty()) continue;
      AdProductProfitPort.SkuProfit skuProfit =
          profit.bySkuAndCurrency().get(identity.skuCode() + "|" + side.currency());
      if (skuProfit == null) continue;
      BigDecimal rev = skuProfit.revenue();
      BigDecimal qty = skuProfit.revenueQuantity();
      if (rev == null) continue;
      revenue.merge(side.currency(), rev, BigDecimal::add);
      if (qty != null) totalUnits.merge(side.currency(), qty, BigDecimal::add);
      if (skuProfit.netProfit() != null) {
        netProfit.merge(side.currency(), skuProfit.netProfit(), BigDecimal::add);
      }
      if (qty != null) {
        incSales.merge(side.currency(), side.sales(), BigDecimal::add);
        incUnits.merge(side.currency(), side.units(), BigDecimal::add);
        incRevenue.merge(side.currency(), rev, BigDecimal::add);
        incTotalUnits.merge(side.currency(), qty, BigDecimal::add);
      }
    }

    Set<String> currencies = new java.util.TreeSet<>(spend.keySet());
    currencies.addAll(revenue.keySet());
    currencies.addAll(adSide.unattributedSpend().keySet());

    List<AdAttributionReportSummary> out = new ArrayList<>();
    for (String currency : currencies) {
      BigDecimal attributedSpend = spend.getOrDefault(currency, BigDecimal.ZERO);
      BigDecimal attributedSales = sales.getOrDefault(currency, BigDecimal.ZERO);
      BigDecimal attributedOrders = orders.getOrDefault(currency, BigDecimal.ZERO);
      BigDecimal attributedUnits = units.getOrDefault(currency, BigDecimal.ZERO);
      BigDecimal unattributedSpend = adSide.unattributedSpend().getOrDefault(currency, BigDecimal.ZERO);
      BigDecimal unattributedSales = adSide.unattributedSales().getOrDefault(currency, BigDecimal.ZERO);
      BigDecimal unattributedOrders = adSide.unattributedOrders().getOrDefault(currency, BigDecimal.ZERO);
      BigDecimal totalRevenue = revenue.get(currency);
      BigDecimal totalUnitsValue = totalUnits.get(currency);
      BigDecimal totalNetProfit = netProfit.get(currency);

      BigDecimal campaignTotalSpend = adSide.campaignSpend().getOrDefault(currency, BigDecimal.ZERO);
      BigDecimal orderLevelSpend = attributedSpend.add(unattributedSpend);
      BigDecimal campaignGap = campaignTotalSpend.subtract(orderLevelSpend);
      BigDecimal coverage = ratio(orderLevelSpend, campaignTotalSpend);

      BigDecimal incremental = null;
      BigDecimal incrementalShare = null;
      BigDecimal incSalesValue = incSales.get(currency);
      BigDecimal incUnitsValue = incUnits.get(currency);
      BigDecimal incRevenueValue = incRevenue.get(currency);
      BigDecimal incTotalUnitsValue = incTotalUnits.get(currency);
      if (incSalesValue != null
          && incUnitsValue != null
          && incRevenueValue != null
          && incTotalUnitsValue != null) {
        BigDecimal organicSales = incRevenueValue.subtract(incSalesValue);
        BigDecimal organicUnits = incTotalUnitsValue.subtract(incUnitsValue);
        if (organicUnits.signum() > 0) {
          BigDecimal organicUnitPrice = organicSales.divide(organicUnits, 8, RoundingMode.HALF_UP);
          incremental = incSalesValue.subtract(incUnitsValue.multiply(organicUnitPrice)).setScale(4, RoundingMode.HALF_UP);
          if (incRevenueValue.signum() != 0) {
            incrementalShare = ratio(incremental, incRevenueValue);
          }
        }
      }

      BigDecimal adAfterNetProfit =
          totalNetProfit == null ? null : totalNetProfit.subtract(attributedSpend);

      out.add(
          new AdAttributionReportSummary(
              currency,
              skuCount.getOrDefault(currency, 0L),
              attributedSpend,
              attributedSales,
              attributedOrders,
              attributedUnits,
              unattributedSpend,
              unattributedSales,
              unattributedOrders,
              campaignTotalSpend,
              campaignGap,
              coverage,
              totalRevenue,
              totalUnitsValue,
              totalNetProfit,
              ratio(attributedSpend, attributedSales),
              ratio(attributedSpend, totalRevenue),
              ratio(attributedSales, totalRevenue),
              adAfterNetProfit,
              ratio(attributedSpend, totalNetProfit),
              incremental,
              incrementalShare,
              METRIC_DEFINITION_NOTE,
              ATTRIBUTION_NOTE,
              INCREMENTALITY_NOTE));
    }
    return out;
  }

  // ==========================================================================
  // 导出
  // ==========================================================================

  /** 登记一次导出留痕（**带规则快照** ⇒ 事后能解释「当时用的是哪套口径」）。 */
  public AdAttributionExportRow recordExport(ReportQuery query, AccessService.Access actor) {
    RuleScope scope = resolveScope(query.ruleId(), query.ruleVersion());
    if (scope.rule() == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "本租户当前没有生效的归因规则：没有口径就没有可公示的报表");
    }
    UUID tenant = currentTenant();
    UUID id = Ids.next();
    sql.execute(
        "insert into ad_attribution_export (tenant_id,id,requested_by,rule_id,rule_code,rule_version,"
            + " attribution_model,attribution_window_days,shop_code,campaign_id,sku_id,currency,"
            + " date_from,date_to,row_count,data_as_of,metric_definition_note,attribution_note,"
            + " created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?::date,?::date,?,?::timestamptz,?,?,now())",
        tenant,
        id,
        actor.userId(),
        scope.rule().id(),
        scope.rule().ruleCode(),
        scope.version(),
        scope.rule().attributionModel(),
        scope.rule().attributionWindowDays(),
        query.shopCode(),
        query.campaignId(),
        query.skuId(),
        query.currency(),
        query.from(),
        query.to(),
        0,
        one(
            OffsetDateTime.class,
            "select max(data_as_of) from ad_attribution_order where tenant_id = " + TENANT),
        METRIC_DEFINITION_NOTE,
        ATTRIBUTION_NOTE);
    return exportRow(
        sql.fetchOne(
            "select id, requested_by, rule_id, rule_code, rule_version, attribution_model,"
                + " attribution_window_days, shop_code, campaign_id, sku_id, currency, date_from,"
                + " date_to, row_count, data_as_of, metric_definition_note, attribution_note, created_at"
                + " from ad_attribution_export where tenant_id = " + TENANT + " and id = ?",
            id));
  }

  private AdAttributionExportRow exportRow(Record r) {
    return new AdAttributionExportRow(
        r.get("id", UUID.class),
        r.get("requested_by", UUID.class),
        r.get("rule_id", UUID.class),
        r.get("rule_code", String.class),
        r.get("rule_version", Long.class),
        r.get("attribution_model", String.class),
        r.get("attribution_window_days", Integer.class),
        r.get("shop_code", String.class),
        r.get("campaign_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("currency", String.class),
        r.get("date_from", LocalDate.class),
        r.get("date_to", LocalDate.class),
        r.get("row_count", Integer.class),
        r.get("data_as_of", OffsetDateTime.class),
        r.get("metric_definition_note", String.class),
        r.get("attribution_note", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /** 导出留痕历史（谁在什么时候按什么条件、按哪套口径导出了多少行）。 */
  public AdAttributionExportPage exports(int page, int size) {
    int limit = Math.min(Math.max(size, 1), 200);
    long total = count("select count(*) from ad_attribution_export where tenant_id = " + TENANT);
    List<AdAttributionExportRow> items =
        sql.fetch(
                "select id, requested_by, rule_id, rule_code, rule_version, attribution_model,"
                    + " attribution_window_days, shop_code, campaign_id, sku_id, currency, date_from,"
                    + " date_to, row_count, data_as_of, metric_definition_note, attribution_note,"
                    + " created_at from ad_attribution_export where tenant_id = "
                    + TENANT
                    + " order by created_at desc, id desc limit ? offset ?",
                limit,
                Math.max(page, 0) * limit)
            .stream()
            .map(this::exportRow)
            .toList();
    return new AdAttributionExportPage(items, total, Math.max(page, 0), limit);
  }

  /** 导出 CSV（口径与规则原文写进表头）。返回**导出的行数**。 */
  public int streamCsv(
      ReportQuery query, AccessService.Access actor, Consumer<String> write, String bearer) {
    RuleScope scope = resolveScope(query.ruleId(), query.ruleVersion());
    StringBuilder head = new StringBuilder();
    head.append("# 14.3.2.1 归因分析 · 广告后利润报表\n");
    head.append("# 归因口径原文：").append(ATTRIBUTION_NOTE).append('\n');
    head.append("# 逐指标口径：").append(METRIC_DEFINITION_NOTE).append('\n');
    head.append("# 增量效果估计口径：").append(INCREMENTALITY_NOTE).append('\n');
    if (scope.rule() != null) {
      head.append("# 规则快照：")
          .append(scope.rule().ruleCode())
          .append(" 第 ")
          .append(scope.version())
          .append(" 版 / ")
          .append(scope.rule().attributionModel())
          .append(" / 归因窗口 ")
          .append(scope.rule().attributionWindowDays())
          .append(" 天\n");
    }
    head.append("# 统计区间：").append(query.from()).append(" ~ ").append(query.to()).append('\n');
    head.append("# 币种不同不合并（本仓没有汇率源）\n");
    head.append(
        "SKU编码,SPU编码,币种,归因广告花费,归因广告销售额,归因订单数,归因件数,总销售额,总件数,"
            + "ACOS(Σ花费/Σ广告销售额),TACOS(Σ花费/总销售额),广告销售占比(广告销售额/总销售额),"
            + "总净利,广告后净利(总净利-广告花费),广告占比利润(花费/总净利),"
            + "增量销售额估计(推导值),增量占比估计\n");
    write.accept(head.toString());

    AdAttributionReportPage page =
        report(query, actor, bearer);
    int rows = 0;
    for (AdAttributionReportRow row : page.items()) {
      rows++;
      write.accept(
          csv(row.skuCode())
              + ","
              + csv(row.spuCode())
              + ","
              + csv(row.currency())
              + ","
              + csvNumber(row.adSpend())
              + ","
              + csvNumber(row.adSales())
              + ","
              + csvNumber(row.adOrders())
              + ","
              + csvNumber(row.adUnits())
              + ","
              + csvNumber(row.totalSales())
              + ","
              + csvNumber(row.totalUnits())
              + ","
              + csvNumber(row.acos())
              + ","
              + csvNumber(row.tacos())
              + ","
              + csvNumber(row.adSalesShare())
              + ","
              + csvNumber(row.netProfit())
              + ","
              + csvNumber(row.adAfterNetProfit())
              + ","
              + csvNumber(row.adProfitShare())
              + ","
              + csvNumber(row.incrementalSalesEstimate())
              + ","
              + csvNumber(row.incrementalShare())
              + "\n");
    }
    return rows;
  }

  /** 导出结束后把**真实导出行数**写回留痕（登记时先落 0，导完再补）。 */
  public void finishExport(UUID exportId, int rowCount) {
    sql.execute(
        "update ad_attribution_export set row_count = ? where tenant_id = " + TENANT + " and id = ?",
        Math.max(rowCount, 0),
        exportId);
  }

  private static String csv(String value) {
    if (value == null) return "";
    String v = value.replace("\"", "\"\"");
    return "\"" + v + "\"";
  }

  private static String csvNumber(BigDecimal value) {
    return value == null ? "" : value.toPlainString();
  }

  // ==========================================================================
  // 小工具：拼接绑定值
  // ==========================================================================

  private static Object[] concat(List<Object> binds, Object... tail) {
    Object[] out = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) out[i] = binds.get(i);
    for (int i = 0; i < tail.length; i++) out[binds.size() + i] = tail[i];
    return out;
  }
}
