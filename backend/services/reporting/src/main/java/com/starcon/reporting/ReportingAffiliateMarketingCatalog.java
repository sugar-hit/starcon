package com.starcon.reporting;

import java.util.List;
import java.util.Set;

/**
 * 13.3.2.2 关联营销效果的**指标口径**与**营销位类型目录**（口径唯一的「标准份」）。
 *
 * <p>蓝图原文：「分析站外/关联营销（关联视频、A+、Store 导流、社交媒体）的引流效果：
 * 曝光、点击、加购、成交归因。输入：营销位配置与曝光点击数据；订单归因数据。
 * 输出：营销位效果报表 + ROI。关系：上游 = 4 刊登（营销模块）、14 广告；下游 = 13.3.1.1。
 * <b>归因需要明确规则</b>（末次点击 vs 首次点击），否则各人看到的数不一样。」
 *
 * <p><b>🔴 本模块的第一性纪律：归因规则必须显式，且规则一变历史不能跟着变</b>
 *
 * <p>蓝图那句加粗句是硬要求。本类把它落成三件事：
 *
 * <ul>
 *   <li><b>规则快照来自上游归因记录</b>（channel 14.5.2.1 每条归因记录自带
 *       归因模型 / 分摊权重基准 / 归因窗口 / 退款口径 / 佣金计提口径）——
 *       <b>不用「当前生效规则」回填历史</b>：规则改了以后，历史那一格仍然说得清它是怎么算出来的。
 *   <li><b>报表按 (营销位, 归因规则) 分列</b> ⇒ ROI 只在<b>同一条规则内部</b>相除；
 *       两条规则的钱绝不会被先加到一起再除（那正是「各人看到的数不一样」的来源）。
 *   <li><b>口径词表要说实话</b>：上游的归因模型取值域是
 *       {@code COOPERATION_FULL}（合作全额归因）/ {@code CAMPAIGN_POOL_SPLIT}（活动池分摊），
 *       <b>不是</b>「末次点击 / 首次点击」—— 后者是<b>广告点击归因</b>（14 广告的职责）。
 *       本模块不提供点击归因，页面上必须写明这一点，<b>不许把达人归因说成末次点击归因</b>。
 * </ul>
 *
 * <p><b>🔴 两套口径绝不混算</b>：达人归因口径（{@link Caliber#INFLUENCER_ATTRIBUTION}）
 * 的「成交归因」与落地商品口径（{@link Caliber#LANDING_LISTING}）的「平台成交」是
 * <b>两回事</b>，报表里分列，各带自己的口径标签。
 *
 * <p><b>🔴 派生值只能在查询期算</b>：ROI / 点击率 / 归因订单率在口径表里<b>只写引用</b>
 * （分子分母的指标码），DB 触发器拒绝给它们落事实行 ⇒ 落库就成了第二个真相。
 */
public final class ReportingAffiliateMarketingCatalog {

  private ReportingAffiliateMarketingCatalog() {}

  /** 单位。 */
  public enum Unit {
    CURRENCY,
    COUNT,
    QUANTITY,
    PERCENT
  }

  /** 聚合方式。 */
  public enum Aggregation {
    /** 汇总型：窗口内把贡献行相加。 */
    SUM,
    /** 派生比：分子 ÷ 分母（**只在查询期算**）。 */
    DERIVED_RATIO
  }

  /**
   * 🔴 **数据口径**（页面上必须显示）。
   *
   * <p>两个口径的统计范围不同，<b>绝不相加、相除当同一个数</b>。
   */
  public enum Caliber {
    /** 达人归因口径：channel 14.5.2.1 的带货归因（**带规则快照**）。 */
    INFLUENCER_ATTRIBUTION,
    /** 落地商品口径：listing 表现（**落地页流量**，不是素材自身曝光）。 */
    LANDING_LISTING
  }

  /**
   * 🔴 **营销位类型**（蓝图点名的四类，封闭词表）。
   *
   * <p>每一类带自己的<b>数据来源</b>与<b>未交付时的归属</b>。
   */
  public enum SlotType {
    /** 关联视频：上游**没有**营销位配置与营销位级数据 ⇒ 本轮不可用。 */
    RELATED_VIDEO("关联视频"),
    /** A+：内容回写留痕证明该 listing 有 A+ 内容 ⇒ 落地商品口径。 */
    A_PLUS("A+"),
    /** Store 导流：上游**没有**营销位配置与营销位级数据 ⇒ 本轮不可用。 */
    STORE_TRAFFIC("Store 导流"),
    /** 社交媒体：达人带货归因 ⇒ 达人归因口径（**带规则快照**）。 */
    SOCIAL_MEDIA("社交媒体");

    private final String displayName;

    SlotType(String displayName) {
      this.displayName = displayName;
    }

    public String displayName() {
      return displayName;
    }

    /** 本轮这一类营销位**有没有**可用的数据口径。 */
    public boolean deliveredThisRound() {
      return this == A_PLUS || this == SOCIAL_MEDIA;
    }

    /** 这一类营销位用哪个数据口径（未交付的类型没有口径）。 */
    public Caliber caliber() {
      return switch (this) {
        case SOCIAL_MEDIA -> Caliber.INFLUENCER_ATTRIBUTION;
        case A_PLUS -> Caliber.LANDING_LISTING;
        default -> null;
      };
    }
  }

  /**
   * 一个指标的口径定义。
   *
   * @param metricCode 指标码
   * @param displayName 显示名
   * @param unit 单位
   * @param aggregation 聚合方式
   * @param numeratorMetricCode 派生型的分子指标码（SUM 型为空）
   * @param denominatorMetricCode 派生型的分母指标码（SUM 型为空）
   * @param currencyAware 是否涉币（涉币 ⇒ **绝不跨币种相加**）
   * @param attributionBound 是否依赖归因规则（true ⇒ 事实行必须带归因规则码）
   * @param available 本轮是否可用（false ⇒ 页面显示「本轮不可用 + 归谁管」）
   * @param ownerModule 上游归属模块 / 未交付段的责任模块
   * @param description 口径说明（页面上照着显示）
   */
  public record Definition(
      String metricCode,
      String displayName,
      Unit unit,
      Aggregation aggregation,
      String numeratorMetricCode,
      String denominatorMetricCode,
      boolean currencyAware,
      boolean attributionBound,
      boolean available,
      String ownerModule,
      String description) {}

  // ---------------------------------------------------------------------------
  // 指标码
  // ---------------------------------------------------------------------------

  /** 曝光。 */
  public static final String IMPRESSIONS = "AFFILIATE_IMPRESSIONS";
  /** 点击。 */
  public static final String CLICKS = "AFFILIATE_CLICKS";
  /** 加购（**本轮不可用**：上游没有平台侧加购行为数据）。 */
  public static final String ADD_TO_CART = "AFFILIATE_ADD_TO_CART";
  /** 归因成交金额。 */
  public static final String ATTRIBUTED_GMV = "AFFILIATE_ATTRIBUTED_GMV";
  /** 归因订单数。 */
  public static final String ATTRIBUTED_ORDERS = "AFFILIATE_ATTRIBUTED_ORDERS";
  /** 营销投入（佣金成本）。 */
  public static final String SPEND = "AFFILIATE_SPEND";

  /** 点击率 = 点击 ÷ 曝光（派生）。 */
  public static final String CTR = "AFFILIATE_CTR";
  /** ROI = 归因成交金额 ÷ 营销投入（派生）。 */
  public static final String ROI = "AFFILIATE_ROI";
  /** 归因客单价 = 归因成交金额 ÷ 归因订单数（派生，**同币种**）。 */
  public static final String ATTRIBUTED_AOV = "AFFILIATE_ATTRIBUTED_AOV";

  // ---------------------------------------------------------------------------
  // 标准口径清单
  // ---------------------------------------------------------------------------

  /**
   * 标准口径清单。
   *
   * <p>⚠️ 顺序即播种顺序：派生指标必须排在其依赖之后（DB 触发器会校验）。
   */
  public static final List<Definition> STANDARD =
      List.of(
          // ---- 曝光 / 点击 ----
          new Definition(
              IMPRESSIONS,
              "曝光",
              Unit.QUANTITY,
              Aggregation.SUM,
              null,
              null,
              false,
              false,
              true,
              "4.5.1.1",
              "营销位带来的**曝光量**。🔴 上游只提供**落地商品口径**的曝光（listing 表现，"
                  + "平台自然日）—— 那是**落地页收到的全部流量**（含自然、广告与其它营销位），"
                  + "**不是该营销位自身的曝光**；达人（社交媒体）站外曝光上游未接入 ⇒ "
                  + "社交媒体营销位的曝光**不可算**（不是 0）。"
                  + "⚠️ 与货币无关 ⇒ 跨币种合计（币种显示为空串 = 与货币无关，不是「币种未知」）。"),
          new Definition(
              CLICKS,
              "点击",
              Unit.QUANTITY,
              Aggregation.SUM,
              null,
              null,
              false,
              false,
              true,
              "4.5.1.1",
              "营销位带来的**点击量**（**落地商品口径**：listing 表现的平台自然日）。"
                  + "🔴 与曝光同源同范围 ⇒ 点击率 = 点击 ÷ 曝光 才是同口径的比率。"
                  + "🔴 达人（社交媒体）站外点击上游未接入 ⇒ 那一类营销位的点击**不可算**。"),
          new Definition(
              ADD_TO_CART,
              "加购",
              Unit.QUANTITY,
              Aggregation.SUM,
              null,
              null,
              false,
              false,
              false,
              "2.1.2.1",
              "🔴 **本轮不可用**：平台侧的加购行为数据上游未接入（channel 的表现 / 广告 / 归因"
                  + "三路报表都没有加购列）。CRM 15.6.2.2 只覆盖**独立站购物车**（Shopify 等），"
                  + "与亚马逊等平台的关联营销**不是同一口径**，**不拿它顶替**。"
                  + "当前替代口径：用「曝光 → 点击 → 成交归因」三段漏斗代替，页面上这一格显示"
                  + "「本轮不可用 + 归 2.1.2.1 管」，**不是 0**。"),
          // ---- 归因与投入 ----
          new Definition(
              ATTRIBUTED_GMV,
              "归因成交金额",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              true,
              true,
              true,
              "14.5.2.1",
              "按**归因规则快照**分摊到该营销位的**成交金额**（达人归因口径）。"
                  + "🔴 **绝不跨币种相加**：按币种分别报。🔴 **依赖归因规则** ⇒ 报表按"
                  + "(营销位, 归因规则) 分列，两条规则的钱不会被加到一起。"),
          new Definition(
              ATTRIBUTED_ORDERS,
              "归因订单数",
              Unit.COUNT,
              Aggregation.SUM,
              null,
              null,
              false,
              true,
              true,
              "14.5.2.1",
              "按**归因规则快照**归到该营销位的**订单数**（达人归因口径）。"
                  + "🔴 **依赖归因规则** ⇒ 报表按 (营销位, 归因规则) 分列。"
                  + "⚠️ 与货币无关 ⇒ 跨币种合计（件数相加不会把两种货币的钱加起来）。"),
          new Definition(
              SPEND,
              "营销投入（佣金成本）",
              Unit.CURRENCY,
              Aggregation.SUM,
              null,
              null,
              true,
              true,
              true,
              "14.5.2.1",
              "该营销位带来的**投入**（达人佣金成本，上游归因记录的佣金金额）。"
                  + "🔴 **绝不跨币种相加**：ROI 的分子分母**必须同币种** ⇒ 币种不同的行不能相除。"
                  + "🔴 落地商品口径（A+ 等内容营销位）**没有投入数据** ⇒ 那一类的 ROI **不可算**。"),
          // ---- 派生口径（依赖上面已登记的指标）----
          new Definition(
              CTR,
              "点击率",
              Unit.PERCENT,
              Aggregation.DERIVED_RATIO,
              CLICKS,
              IMPRESSIONS,
              false,
              false,
              true,
              "13.3.2.2",
              "点击率 = 点击 ÷ 曝光（**落地商品口径**，分子分母同源同范围）。"
                  + "⚠️ 分母为 0 时**算不出来**（不是 0%）。"),
          new Definition(
              ROI,
              "ROI",
              Unit.CURRENCY,
              Aggregation.DERIVED_RATIO,
              ATTRIBUTED_GMV,
              SPEND,
              true,
              false,
              true,
              "13.3.2.2",
              "ROI = 归因成交金额 ÷ 营销投入（**同币种**、**同一条归因规则**）。"
                  + "🔴 分子分母必须**同币种**（按币种分别算）；分母缺失或为 0 时**不可算**"
                  + "（**不是 0**）；🔴 **派生值只能在查询期算**，DB 拒绝给它落事实行。"),
          new Definition(
              ATTRIBUTED_AOV,
              "归因客单价",
              Unit.CURRENCY,
              Aggregation.DERIVED_RATIO,
              ATTRIBUTED_GMV,
              ATTRIBUTED_ORDERS,
              true,
              false,
              true,
              "13.3.2.2",
              "归因客单价 = 归因成交金额 ÷ 归因订单数（**达人归因口径**，**同币种**）。"
                  + "🔴 分子分母必须**同币种**；分母缺失或为 0 时**不可算**（**不是 0**）。"));

  // ---------------------------------------------------------------------------
  // 营销位类型 × 指标 的可算性矩阵
  // ---------------------------------------------------------------------------

  /**
   * 🔴 **某类营销位上，某个指标本轮能不能算**。
   *
   * <p>这是「口径不同」的第二道闸：达人归因口径没有曝光 / 点击，落地商品口径没有归因 / 投入。
   * 算不出来的一律**不可算**（页面显示「本轮不可用 + 归谁管」），<b>不是 0</b>。
   */
  public static boolean computable(SlotType slotType, String metricCode) {
    if (slotType == null) return false;
    return switch (slotType) {
      case SOCIAL_MEDIA ->
          // 达人归因口径：有归因成交 / 归因订单 / 投入 / ROI / 客单价；**没有**站外曝光点击与加购。
          Set.of(ATTRIBUTED_GMV, ATTRIBUTED_ORDERS, SPEND, ROI, ATTRIBUTED_AOV).contains(metricCode);
      case A_PLUS ->
          // 落地商品口径：有曝光 / 点击 / 点击率；**没有**归因与投入 ⇒ ROI 不可算。
          Set.of(IMPRESSIONS, CLICKS, CTR).contains(metricCode);
      case RELATED_VIDEO, STORE_TRAFFIC ->
          // 本轮上游没有任何数据 ⇒ 全部不可算。
          false;
    };
  }

  /** 某一类营销位「本轮不可用」的说明（页面上常驻显示）。 */
  public static String slotTypeNote(SlotType slotType) {
    return switch (slotType) {
      case SOCIAL_MEDIA ->
          "达人归因口径（channel 14.5.2.1）：成交归因 / 归因订单 / 佣金投入 / ROI 可算；"
              + "🔴 站外曝光、点击、加购上游未接入 ⇒ **不可算**（不是 0）。";
      case A_PLUS ->
          "落地商品口径（channel 4.5.1.1 listing 表现）：曝光 / 点击 / 点击率可算；"
              + "🔴 那是**落地页收到的全部流量**（含自然、广告与其它营销位），"
              + "**不是 A+ 素材自身的曝光**；🔴 成交归因与投入上游未接入 ⇒ ROI **不可算**。";
      case RELATED_VIDEO ->
          "🔴 **本轮不可用**：上游**没有**关联视频的营销位配置表，也**没有**营销位级的"
              + "曝光 / 点击 / 归因数据（channel 的内容回写只支持 TITLE / BULLETS / IMAGES / "
              + "DESCRIPTION / A_PLUS 五类变更，**没有 VIDEO**）。归 4.2.1.1 刊登编辑器管。"
              + "当前替代口径：以**同落地 listing** 的落地页曝光 / 点击作参考，"
              + "但**不计入**关联视频这一类（那会把别人的流量算到它头上）。";
      case STORE_TRAFFIC ->
          "🔴 **本轮不可用**：上游**没有**Store 导流的营销位配置，也**没有**导流位的"
              + "曝光 / 点击 / 归因数据。归 2.1.2.1 平台同步管（站外导流数据未接入）。"
              + "当前替代口径：以**同落地 listing** 的落地页曝光 / 点击作参考，"
              + "但**不计入**Store 导流这一类。";
    };
  }

  /** 某一类营销位的上游 / 归属说明。 */
  public static String slotTypeOwner(SlotType slotType) {
    return switch (slotType) {
      case SOCIAL_MEDIA -> "14.5.2.1 带货归因（channel）";
      case A_PLUS -> "4.3.2.1 内容回写 + 4.5.1.1 平台表现（channel）";
      case RELATED_VIDEO -> "4.2.1.1 刊登编辑器（关联视频营销位建模未交付）";
      case STORE_TRAFFIC -> "2.1.2.1 平台同步（站外导流数据未接入）";
    };
  }

  // ---------------------------------------------------------------------------
  // 归因规则口径（**页面上必须显式标出用的是哪条规则**）
  // ---------------------------------------------------------------------------

  /**
   * 🔴 归因规则的取值域（**逐字取自上游 channel 的封闭词表**）。
   *
   * <p>⚠️ 上游的归因模型是「合作全额归因 / 活动池分摊」，<b>不是</b>「末次点击 / 首次点击」——
   * 后者是<b>广告点击归因</b>（14 广告的职责）。本模块<b>不提供</b>点击归因，页面上写明这一点。
   */
  public static final List<String> ATTRIBUTION_MODELS = List.of("COOPERATION_FULL", "CAMPAIGN_POOL_SPLIT");

  /** 分摊权重基准取值域。 */
  public static final List<String> POOL_WEIGHT_BASES = List.of("FOLLOWER_SHARE", "COOPERATION_GMV_SHARE");

  /** 退款口径取值域。 */
  public static final List<String> REFUND_TREATMENTS = List.of("GROSS", "NET");

  /** 佣金计提口径取值域。 */
  public static final List<String> COMMISSION_BASES = List.of("COOPERATION_COMMISSION", "RATE_ON_ATTRIBUTED_GMV");

  /** 归因规则口径的总说明（页面上常驻）。 */
  public static final String ATTRIBUTION_RULE_NOTE =
      "🔴 **归因规则必须显式**：本报表里的成交归因用的是**上游归因记录自带的那份规则快照**"
          + "（归因模型 / 分摊权重基准 / 归因窗口 / 退款口径 / 佣金计提口径），"
          + "**不是**「当前生效规则」—— 规则改了以后历史那一格仍然说得清它是怎么算出来的。"
          + "报表按 **(营销位, 归因规则)** 分列，**ROI 只在同一条规则内部相除**。"
          + "⚠️ 上游的归因模型词表是「合作全额归因 / 活动池分摊」，"
          + "**不是**「末次点击 / 首次点击」—— 后者属**广告点击归因**（14 广告），本模块不提供。";
}
