package com.starcon.reporting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 13.1.2.1 目标设定的**目标指标清单**（蓝图 13 卷点名的那些「要设目标的东西」）。
 *
 * <p>蓝图原文（docs/product/capability-blueprint/13-profit-and-analytics.md:100）：
 * 「设定经营目标：按部门/人员/产品/店铺设定销售额、毛利额、毛利率、广告 ROI、库存周转等指标与周期，
 * 支持分解到月/周。」
 *
 * <p>🔴 **本类只声明「蓝图点的名字 ↔ 可引用的口径码」这一层映射，不声明任何公式**：
 *
 * <ul>
 *   <li>口径（单位 / 聚合方式 / 分子分母）**运行时唯一真相是口径表**
 *       （{@code reporting_metric_definition} / {@code reporting_store_metric_definition}）——
 *       服务层从那张表读，本类不复制一份；</li>
 *   <li>目标是「**引用**一个已登记的指标码 + 一个目标值 + 一个周期」，
 *       **不是**「实现一个指标公式」；</li>
 *   <li>没登记的指标码**在清单里就是「未登记」**，给归属模块 ——
 *       给了就是编一个出来，页面上的数字会变成「看起来有依据的假数」。</li>
 * </ul>
 *
 * <p>🔴 **销售额在店铺维度是三个并列口径**（与 13.1.1.2 车道的结论一致）：
 * {@code STORE_GMV_PLATFORM}（平台侧）/ {@code STORE_GMV_RESTORED}（还原口径）/
 * {@code STORE_CALIBER_GAP}（两者之差）。
 * **取其一改名叫「销售额」就是抹掉口径差异** ⇒ 三个都留着，让用户选口径并看见差异。
 *
 * <p>🔴 **广告 ROI 与库存周转**（蓝图点名但口径表里**没有**的）：
 *
 * <ul>
 *   <li><b>广告 ROI</b> ⇒ 店铺维度可用已登记的 {@code STORE_AD_ROAS}
 *       （广告销售额 ÷ 广告花费，就是 ROI 的另一种写法）；
 *       公司 / 部门 / 人员 / 产品维度**没有**这个码 ⇒ 报「未登记」，
 *       <b>不拿 {@code AD_SPEND} 冒充</b>（那是花费，不是 ROI）；</li>
 *   <li><b>库存周转</b> ⇒ <b>没有任何周转码</b>（只有 {@code INVENTORY_VALUE} 存量口径）。
 *       拿「库存总额」冒充「库存周转」是<strong>换一个指标冒充另一个指标</strong> ⇒ 报「未登记」，
 *       并写明归属模块（7.1.1.1 库存总览 / 13.5.1.1 库存分析）。</li>
 * </ul>
 *
 * <p>⚠️ **这不是一张「用户可选指标」的表**：它是**蓝图事实**（系统认为有哪些目标指标），
 * 与租户自己的口径表是两回事 —— 租户口径表决定「这个码怎么算」，本类决定
 * 「蓝图的这个目标名对应哪些口径码」。
 */
public final class ReportingTargetMetricCatalog {

  private ReportingTargetMetricCatalog() {}

  // ---------------------------------------------------------------------------
  // 蓝图目标指标键（稳定字符串：它会进目标记录的可读标签）
  // ---------------------------------------------------------------------------

  /** 销售额。 */
  public static final String SALES = "target.sales";
  /** 毛利额。 */
  public static final String GROSS_PROFIT = "target.gross-profit";
  /** 毛利率。 */
  public static final String GROSS_MARGIN = "target.gross-margin";
  /** 广告 ROI。 */
  public static final String AD_ROI = "target.ad-roi";
  /** 库存周转。 */
  public static final String INVENTORY_TURNOVER = "target.inventory-turnover";

  // ---------------------------------------------------------------------------
  // 归属对象类型（受控词表）
  // ---------------------------------------------------------------------------

  /** 部门（identity 的 department）。 */
  public static final String SCOPE_DEPARTMENT = "DEPARTMENT";
  /** 人员（identity 的 membership）。 */
  public static final String SCOPE_MEMBER = "MEMBER";
  /** 店铺（channel 的 shop_code）。 */
  public static final String SCOPE_SHOP = "SHOP";
  /** 产品（catalog 的 SPU）。 */
  public static final String SCOPE_PRODUCT = "PRODUCT";

  /** 归属对象类型的全集（受控词表，不许自由文本）。 */
  public static final List<String> SCOPE_TYPES =
      List.of(SCOPE_DEPARTMENT, SCOPE_MEMBER, SCOPE_SHOP, SCOPE_PRODUCT);

  /** 归属对象在**租户口径表**（{@code reporting_metric_definition}）上的维度。 */
  public static final List<String> ORG_SCOPE_TYPES =
      List.of(SCOPE_DEPARTMENT, SCOPE_MEMBER, SCOPE_PRODUCT);

  /** 归属对象在**店铺口径表**（{@code reporting_store_metric_definition}）上的维度。 */
  public static final List<String> SHOP_SCOPE_TYPES = List.of(SCOPE_SHOP);

  // ---------------------------------------------------------------------------
  // 口径引用
  // ---------------------------------------------------------------------------

  /**
   * 一个可引用的口径码。
   *
   * @param metricCode 口径码（必须在对应口径表里**已登记**）
   * @param note 引用说明（为什么是它 / 它和别的口径差在哪）
   */
  public record Caliber(String metricCode, String note) {}

  /**
   * 一个蓝图目标指标在**某个归属维度**上的可用性。
   *
   * @param scopeType 归属对象类型
   * @param available 该维度上能不能设这个目标。false ⇒ {@code calipers} 为空
   * @param calipers 该维度上**可引用的口径码**（店铺维度的「销售额」有三个并列口径）
   * @param reason 接不通的原因（{@code available == false} 时必填）
   */
  public record ScopeAvailability(
      String scopeType, boolean available, List<Caliber> calipers, String reason) {}

  /**
   * 一个蓝图目标指标。
   *
   * @param key 目标指标键（稳定字符串）
   * @param displayName 蓝图点名的显示名
   * @param unit 单位（CURRENCY / PERCENT / RATIO / COUNT）
   * @param ownerModule 口径归属模块（该指标归哪个系统哪个模块）
   * @param byScope 按归属维度的可用性
   */
  public record TargetMetric(
      String key,
      String displayName,
      String unit,
      String ownerModule,
      List<ScopeAvailability> byScope) {}

  // ---------------------------------------------------------------------------
  // 标准清单
  // ---------------------------------------------------------------------------

  /** 组织维度（部门 / 人员 / 产品）共用的口径清单 —— 它们都在租户口径表里。 */
  private static final List<Caliber> SALES_ORG =
      List.of(
          new Caliber(
              ReportingMetricCatalog.SALES_AMOUNT,
              "订单成交金额（9.1.1.1 订单列表口径）—— 组织维度的销售额只有这一个口径。"));

  /** 店铺维度的销售额：**三个并列口径**，取其一就抹掉了口径差异。 */
  private static final List<Caliber> SALES_SHOP =
      List.of(
          new Caliber(
              ReportingStoreComparisonCatalog.STORE_GMV_PLATFORM,
              "平台侧成交金额：各平台自己的 GMV 口径（亚马逊与 TikTok 定义不同），"
                  + "选它就是在用平台口径立目标。"),
          new Caliber(
              ReportingStoreComparisonCatalog.STORE_GMV_RESTORED,
              "还原口径成交金额：我方从平台事件还原出来的金额，与平台报告可能相差分毫。"),
          new Caliber(
              ReportingStoreComparisonCatalog.STORE_CALIBER_GAP,
              "口径差异额：平台侧与还原口径之差 —— 它量化了「这个平台的 GMV 口径与我们理解的差多少」。"
                  + "拿它立目标就是在追一个口径修正目标。"));

  private static final List<Caliber> GROSS_PROFIT_ORG =
      List.of(
          new Caliber(
              ReportingMetricCatalog.GROSS_PROFIT,
              "毛利额 = 销售额 − 成本（派生型，口径表里只写分子分母引用，本模块不重算）。"));

  private static final List<Caliber> GROSS_MARGIN_ORG =
      List.of(
          new Caliber(
              ReportingMetricCatalog.GROSS_MARGIN,
              "毛利率 = 毛利额 ÷ 销售额（派生型；分母为 0 时算不出，不是 0%）。"));

  private static final List<Caliber> AD_ROI_SHOP =
      List.of(
          new Caliber(
              ReportingStoreComparisonCatalog.STORE_AD_ROAS,
              "广告产出比 = 广告销售额 ÷ 广告花费 —— 这就是广告 ROI 的另一种写法，"
                  + "口径已登记，可以直接引用。"));

  /**
   * 蓝图点名的五个目标指标。
   *
   * <p>⚠️ **四个归属维度一个都不能少**（部门 / 人员 / 店铺 / 产品）：
   * 蓝图 13.1.2.1 的职责句原文是「按部门/人员/<b>产品</b>/店铺设定销售额、毛利额、毛利率、
   * 广告 ROI、库存周转等指标与周期」—— 少一个维度就是少一类目标。
   */
  public static final List<TargetMetric> STANDARD =
      List.of(
          new TargetMetric(
              SALES,
              "销售额",
              "CURRENCY",
              "9.1.1.1 订单列表（trade）",
              List.of(
                  orgAvailability(SCOPE_DEPARTMENT, SALES_ORG),
                  orgAvailability(SCOPE_MEMBER, SALES_ORG),
                  shopAvailability(SALES_SHOP),
                  orgAvailability(SCOPE_PRODUCT, SALES_ORG))),
          new TargetMetric(
              GROSS_PROFIT,
              "毛利额",
              "CURRENCY",
              "13.1.1.1 经营总览（本服务派生）",
              List.of(
                  orgAvailability(SCOPE_DEPARTMENT, GROSS_PROFIT_ORG),
                  orgAvailability(SCOPE_MEMBER, GROSS_PROFIT_ORG),
                  shopAvailability(List.of()),
                  orgAvailability(SCOPE_PRODUCT, GROSS_PROFIT_ORG))),
          new TargetMetric(
              GROSS_MARGIN,
              "毛利率",
              "PERCENT",
              "13.1.1.1 经营总览（本服务派生）",
              List.of(
                  orgAvailability(SCOPE_DEPARTMENT, GROSS_MARGIN_ORG),
                  orgAvailability(SCOPE_MEMBER, GROSS_MARGIN_ORG),
                  shopAvailability(List.of()),
                  orgAvailability(SCOPE_PRODUCT, GROSS_MARGIN_ORG))),
          new TargetMetric(
              AD_ROI,
              "广告 ROI",
              "RATIO",
              "14.3.1.1 活动报表（channel）",
              List.of(
                  orgAvailability(SCOPE_DEPARTMENT, List.of()),
                  orgAvailability(SCOPE_MEMBER, List.of()),
                  shopAvailability(AD_ROI_SHOP),
                  orgAvailability(SCOPE_PRODUCT, List.of()))),
          new TargetMetric(
              INVENTORY_TURNOVER,
              "库存周转",
              "RATIO",
              "7.1.1.1 库存总览（warehouse）/ 13.5.1.1 库存分析（reporting）",
              List.of(
                  orgAvailability(SCOPE_DEPARTMENT, List.of()),
                  orgAvailability(SCOPE_MEMBER, List.of()),
                  shopAvailability(List.of()),
                  orgAvailability(SCOPE_PRODUCT, List.of()))));

  // ---------------------------------------------------------------------------
  // 查询
  // ---------------------------------------------------------------------------

  /** 按目标指标键取定义（取不到返回 null）。 */
  public static TargetMetric standard(String key) {
    for (var metric : STANDARD) {
      if (metric.key().equals(key)) return metric;
    }
    return null;
  }

  /** 按归属维度取某个目标指标的可用性（取不到返回 null）。 */
  public static ScopeAvailability availability(String key, String scopeType) {
    var metric = standard(key);
    if (metric == null) return null;
    for (var availability : metric.byScope()) {
      if (availability.scopeType().equals(scopeType)) return availability;
    }
    return null;
  }

  /**
   * 一个归属维度上**可引用的口径码集合**（该维度上全部目标指标的口径码）。
   *
   * <p>⚠️ 这是**引用集合**，不是口径定义 —— 口径真相仍在口径表里。
   */
  public static Map<String, Caliber> calipersOf(String scopeType) {
    var out = new LinkedHashMap<String, Caliber>();
    for (var metric : STANDARD) {
      var availability = availability(metric.key(), scopeType);
      if (availability == null || !availability.available()) continue;
      for (var caliber : availability.calipers()) {
        out.putIfAbsent(caliber.metricCode(), caliber);
      }
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // 构造辅助
  // ---------------------------------------------------------------------------

  private static ScopeAvailability orgAvailability(String scopeType, List<Caliber> calipers) {
    return new ScopeAvailability(
        scopeType, !calipers.isEmpty(), calipers, calipers.isEmpty() ? ORG_UNAVAILABLE : "");
  }

  private static ScopeAvailability shopAvailability(List<Caliber> calipers) {
    return new ScopeAvailability(
        SCOPE_SHOP,
        !calipers.isEmpty(),
        calipers,
        calipers.isEmpty() ? SHOP_UNAVAILABLE : "");
  }

  /** 组织维度（部门 / 人员 / 产品）拿不到时的统一说明。 */
  private static final String ORG_UNAVAILABLE =
      "公司 / 部门 / 人员 / 产品维度**没有登记这个指标码** —— 口径唯一，没有登记就没有这个指标。"
          + "本模块**不自己造一个公式**（那会出现第二个真相），"
          + "需要它就先在 13 的口径表里登记（登记时必须写全分子分母）。";

  /** 店铺维度拿不到时的统一说明。 */
  private static final String SHOP_UNAVAILABLE =
      "店铺维度**没有登记这个指标码** —— 口径唯一，没有登记就没有这个指标。"
          + "其中店铺维度的毛利额 / 毛利率还多一层原因：上游的成本分录（12.4.1.2）与利润表"
          + "（12.5.2.2）都**没有店铺维度**，只有法人 / 公司维度 —— "
          + "把公司维度的毛利率挂到店铺上就是造假。";
}
