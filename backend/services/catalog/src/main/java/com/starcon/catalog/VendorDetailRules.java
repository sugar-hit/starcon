package com.starcon.catalog;

import static com.starcon.catalog.VendorListRules.normalizeEnum;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 6.1.1.2 供应商详情的闭集词表与纯函数规则。
 *
 * <p>与 {@link VendorListRules} 同一处理方式：<b>枚举只增不改这条承诺要在<b>一处</b>兑现</b>，
 * 前端下拉与后端校验各维护一份迟早会不一样。所以词表全部是 {@code public static final}，
 * 由 {@code /options} 端点原样吐给前端，服务层也用它做入参校验 —— 同一份 List 两处用。
 *
 * <p>这里的每个 {@code Problem} 方法都返回<b>面向使用者的中文句子</b>而不是字段名：
 * 校验失败的那条报文会直接出现在界面上，而「settlement_method」对运营没有任何意义。
 */
public final class VendorDetailRules {
  private VendorDetailRules() {}

  /** 到期前多少天算「即将到期」。写死 30 天：证书续期流程通常要 2~4 周，30 天是能让采购来得及催的窗口。 */
  public static final int EXPIRING_SOON_DAYS = 30;

  /** 资质类型（闭集）。OTHER 兜底，不建自由文本类型。 */
  public static final List<VendorListRules.Option> QUALIFICATION_TYPES =
      List.of(
          new VendorListRules.Option("BUSINESS_LICENSE", "营业执照", "跨境必备；缺它 06 建单与清关都会被卡"),
          new VendorListRules.Option("ISO9001", "ISO9001 体系认证", "质量管理体系，多数类目准入要求"),
          new VendorListRules.Option("ISO14001", "ISO14001 体系认证", "环境管理体系，部分平台与买家要求"),
          new VendorListRules.Option("CE", "CE 认证", "欧盟市场准入"),
          new VendorListRules.Option("FDA", "FDA 注册", "美国食品接触 / 医疗类目准入"),
          new VendorListRules.Option("COC", "产地证", "清关与原产地优惠"),
          new VendorListRules.Option("OTHER", "其它资质", "有 title 兜底；不建自由文本类型"));

  /**
   * 结算方式（闭集）。
   *
   * <p><b>逐字沿用 3.6.1.2 的 settlement_method 词表</b>（{@code PartyRoleProfileRules} 的同一份取值），
   * 刻意不另造一套：详情页上「本采购线结算方式」与「主体默认结算方式」并排显示，
   * 两套词汇会让同一个词在同一个界面上对不上号，而枚举只增不改也要求它们同源。
   * 语义写在 hint 里 —— 这四个取值对付款审批的动作完全不同。
   */
  public static final List<VendorListRules.Option> SETTLEMENT_METHODS =
      List.of(
          new VendorListRules.Option("PREPAID", "预付", "先款后货；预付比例必须大于 0"),
          new VendorListRules.Option("ON_DELIVERY", "货到付款", "到货即付，不产生账期"),
          new VendorListRules.Option("CREDIT", "月结 / 账期", "到期日 = 单据日 + 账期天数"),
          new VendorListRules.Option("LETTER_OF_CREDIT", "信用证", "走银行单据流程，不进普通付款审批"));

  /** 发票类型（闭集）。 */
  public static final List<VendorListRules.Option> INVOICE_TYPES =
      List.of(
          new VendorListRules.Option("VAT_SPECIAL", "增值税专票", "可进项抵扣；须有税号"),
          new VendorListRules.Option("VAT_NORMAL", "增值税普票", "不可抵扣；须有税号"),
          new VendorListRules.Option("PROFORMA", "形式发票", "跨境预付常用；12 财务按预付流程处理"),
          new VendorListRules.Option("EXPORT_DECLARATION", "出口报关单", "按报关单入账"),
          new VendorListRules.Option("NONE", "无票", "个体 / 小规模供应商；12 财务不能自动生成付款申请"));

  // ================================================================ 入参校验（返回 null = 合法）

  /** 结算方式是否在闭集内。 */
  public static String settlementMethodProblem(String value) {
    return inClosedSet(value, SETTLEMENT_METHODS, "结算方式");
  }

  /** 发票类型是否在闭集内。 */
  public static String invoiceTypeProblem(String value) {
    return inClosedSet(value, INVOICE_TYPES, "发票类型");
  }

  /** 资质类型是否在闭集内。 */
  public static String qualificationTypeProblem(String value) {
    return inClosedSet(value, QUALIFICATION_TYPES, "资质类型");
  }

  /** 报价状态筛选是否在闭集内（3.6.2.1 的 ACTIVE / DISABLED）。 */
  public static String quoteStatusProblem(String value) {
    String normalized = normalizeEnum(value);
    if ("ACTIVE".equals(normalized) || "DISABLED".equals(normalized)) return null;
    return "报价状态只能是 ACTIVE 或 DISABLED，收到的是：" + value;
  }

  private static String inClosedSet(String value, List<VendorListRules.Option> options, String label) {
    String normalized = normalizeEnum(value);
    if (normalized.isEmpty()) return label + "不能为空";
    for (var option : options) {
      if (option.value().equals(normalized)) return null;
    }
    return label + "不在可选范围：" + value;
  }

  // ================================================================ 纯函数

  /**
   * 附件资质的有效期状态（<b>不落库</b>，D7）。
   *
   * <p>UNKNOWN（未填到期日）与 VALID 是两件不同的事：产地证与体系证书的效力口径不一样，
   * 把 UNKNOWN 显示成「有效」会让一张已经不再被承认的证看起来合规。
   *
   * @param today 计算基准日（调用方传入，测试里可以钉死 —— 不用 {@code now()} 喂一个会被断言的分支）
   */
  public static String qualificationValidity(LocalDate expiresOn, LocalDate today) {
    if (expiresOn == null) return "UNKNOWN";
    long days = ChronoUnit.DAYS.between(today, expiresOn);
    if (days < 0) return "EXPIRED";
    if (days <= EXPIRING_SOON_DAYS) return "EXPIRING_SOON";
    return "VALID";
  }

  /** 距到期天数；UNKNOWN 时为 null。已过期时是**负数**（界面显示「已过期 N 天」）。 */
  public static Long daysToExpiry(LocalDate expiresOn, LocalDate today) {
    return expiresOn == null ? null : ChronoUnit.DAYS.between(today, expiresOn);
  }

  /** 税率显示：比例 → 百分数文本（0.13 → {@code 13%}）。不用浮点。 */
  public static String taxRatePercent(BigDecimal rate) {
    if (rate == null) return "";
    return rate.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
  }

  /**
   * 「到期提醒」的措辞：区分已过期与即将过期两种不同的动作。
   *
   * <p>合成一句话而不是让前端拼：同一个到期状态在详情页与治理视图里要说同样的话，
   * 而两处各拼一遍迟早出现「一个说快过期、一个说已过期」的差异。
   */
  public static String expiryWarning(VendorDetailModels.VendorQualificationRow row) {
    return switch (row.validity()) {
      case "EXPIRED" ->
          "「" + row.title() + "」已于 " + row.expiresOn() + " 过期（" + (-row.daysToExpiry()) + " 天前）：下单前必须换证，否则清关与平台准入会被卡。";
      case "EXPIRING_SOON" ->
          "「" + row.title() + "」将在 " + row.expiresOn() + " 到期（剩 " + row.daysToExpiry() + " 天）：续期流程通常要 2~4 周，现在就该催。";
      default -> "";
    };
  }
}
