package com.starcon.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 3.6.2.1 供应商报价的纯规则：供应商货号、采购单位、币种、MOQ、交期、有效期、 优先级与阶梯价的词表、规范化与保存前置条件。
 *
 * <p>与 {@link PartyRoleProfileRules} / {@link PartyRules} 同一条纪律： <b>能在保存前判定的，就不要留到下游才炸</b>。
 * 「两档阶梯的起订量填反了」不会在保存时报错，它会在 06 采购单按 137 件取价时 取到 500 件那档的单价 —— 而低价档通常是量大价优的那档，于是采购单用**更低**的单价
 * 结算出一张本该按高量价结算的单，而库存账面看不出任何异常。
 *
 * <p>规则 R1~R7 在 {@code V32__supplier_quote.sql} 第九节有同样的文字， 服务层这里与前端 {@code supplier-quote-model.ts}
 * 是它的三份实现，改一处必须同步三处。
 *
 * <p>本类不碰数据库，可以在离线单测里穷举。
 *
 * <p>类名不叫 {@code SupplierQuote}：那是被静态导入的 DTO record （{@link CatalogModels}），两者在同一批文件里会直接撞名，编译期才炸。 与
 * {@link PartyRules} 同一个理由。
 */
public final class SupplierQuoteRules {
  private SupplierQuoteRules() {}

  // ================================================================ 长度与取值上限（与 V32 列宽逐字一致）

  /** 供应商货号上限，与 {@code supplier_sku_code varchar(64)} 一致。 */
  public static final int MAX_SUPPLIER_SKU_CODE_LENGTH = 64;

  /** 采购单位代码上限，与 {@code purchase_unit_code varchar(32)} 及 {@code unit_of_measure.code} 一致。 */
  public static final int MAX_UNIT_CODE_LENGTH = 32;

  /** 币种长度固定 3（ISO 4217），与 {@code currency varchar(3)} 一致。 */
  public static final int CURRENCY_LENGTH = 3;

  /** 报价备注上限，与 {@code note text} 的服务层约束一致。 */
  public static final int MAX_NOTE_LENGTH = 2000;

  /** 单档阶梯备注上限。 */
  public static final int MAX_TIER_NOTE_LENGTH = 500;

  /** 交期上限（天）。365 挡住误填的 99999（那不是交期，那是把月填成了天），与 V32 的 CHECK 一致。 */
  public static final int MAX_LEAD_TIME_DAYS = 365;

  /** 优先级上限，与 {@code priority BETWEEN 1 AND 99} 一致。1 = 首选。 */
  public static final int MAX_PRIORITY = 99;

  /** MOQ 与阶梯起订量的精度上限（6 位小数），与 {@code numeric(19,6)} 一致。 */
  public static final int QUANTITY_SCALE = 6;

  /** 单价精度上限（4 位小数），与 {@code unit_price numeric(19,4)} 及 V30 的 credit limit 同精度。 */
  public static final int PRICE_SCALE = 4;

  /** 单价整数位上限（19,4 里整数部分最多 15 位）。用 {@code BigDecimal} 判，不用 double。 */
  public static final int PRICE_PRECISION = 15;

  /** 单条报价允许的阶梯数上限。防手滑，不是容量约束：真实报价通常 2~5 档。 */
  public static final int MAX_TIERS = 50;

  // ================================================================ 词表

  /** 报价状态（规则 **R7**：枚举只增不改）。 */
  public static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");

  /**
   * 有效报价的选择状态（规则 **R2**）。
   *
   * <p>这三个值是本模块最要紧的对外契约：读出口**必须**给每条有效报价标一个， 而**不许**在服务端悄悄挑一条。前端据此渲染「需人工选择」。
   */
  public static final Set<String> SELECTION_STATES =
      Set.of("SELECTED", "AMBIGUOUS", "LOWER_PRIORITY");

  /** 报价挂的角色类型（规则 **R1**）。 */
  public static final String ROLE_SUPPLIER = "SUPPLIER";

  // ================================================================ 通用规范化

  /** 文本规范化：去首尾空白，空串归成「没有」。与 {@link PartyRules#normalizeText} 同一实现。 */
  public static String normalizeText(String value) {
    return value == null ? "" : value.trim();
  }

  /** 枚举规范化：大写 + 去空白。空串归 null（"没填"）。 */
  public static String normalizeEnum(String value) {
    String text = normalizeText(value);
    if (text.isEmpty()) return null;
    return text.toUpperCase(Locale.ROOT);
  }

  /**
   * 币种规范化：ISO 4217 三位大写；空串归 null。
   *
   * <p>刻意不查币种字典（规则 **R3**）：那张表是 12 财务的资产， 在这里硬编码一份就等于造出第二个写入所有权，而两份列表迟早会不一样
   * （一方加了新币种时另一方不会跟）。只做格式校验。
   */
  public static String normalizeCurrency(String value) {
    String text = normalizeText(value).toUpperCase(Locale.ROOT);
    return text.isEmpty() ? null : text;
  }

  /** 计量单位代码规范化：去空白 + 大写（{@code unit_of_measure.code} 允许中文，大写只影响拉丁字母）。 */
  public static String normalizeUnitCode(String value) {
    String text = normalizeText(value).toUpperCase(Locale.ROOT);
    return text.isEmpty() ? null : text;
  }

  /**
   * 数量规范化到 6 位小数（{@code numeric(19,6)}）。
   *
   * <p>{@link RoundingMode#HALF_UP} 而不是 {@code UNNECESSARY}： 前端把 0.0000001 显示成 0 后再回传是常见路径，用
   * UNNECESSARY 会让一次纯展示问题变成 400。 存储精度仍然由 DB 的 numeric(19,6) 兜底 —— 服务层放宽不等于列能存下更多。
   */
  public static BigDecimal normalizeQuantity(BigDecimal value) {
    return value == null ? null : value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  /** 单价规范化到 4 位小数（{@code numeric(19,4)}），理由与 {@link #normalizeQuantity} 同。 */
  public static BigDecimal normalizePrice(BigDecimal value) {
    return value == null ? null : value.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
  }

  /** 数量展示串：去掉多余的尾随 0（{@code 100.000000} → {@code 100}），保留至少一位小数。 */
  public static String quantityLabel(BigDecimal value) {
    if (value == null) return "";
    // stripTrailingZeros 会把 100.000000 变成 1E+2，必须走 toPlainString。
    return value.stripTrailingZeros().toPlainString();
  }

  // ================================================================ 供应商货号

  /**
   * 供应商货号校验。
   *
   * <p>必填：货号是这条报价在供应商那边**唯一**的锚点，06 采购单下给对方时靠它对账。 没有货号的报价在采购单上只能靠 SKU 名字指认，而同一家的两个产品重名是常态。
   *
   * <p>刻意<b>不</b>套 {@code PartyRules.code} 那种 [A-Z0-9] 词表：供应商货号里有 中文、有连字符、有斜杠（工厂图纸号 {@code
   * XG-2024/A}），套 ASCII 词表会判成非法， 而运营除了改成一个假编码没有别的出路 —— 那条编码在供应商那边根本不存在。
   */
  public static String supplierSkuCodeProblem(String value) {
    String code = normalizeText(value);
    if (code.isEmpty()) return "供应商货号必填：06 采购单下给对方时靠它对账";
    if (code.length() > MAX_SUPPLIER_SKU_CODE_LENGTH)
      return "供应商货号最多 " + MAX_SUPPLIER_SKU_CODE_LENGTH + " 个字符";
    return null;
  }

  // ================================================================ 采购单位与币种

  /** 采购单位校验。必填：报价是「按这个单位报的」，没有单位这个单价是一句没有意义的话。 */
  public static String purchaseUnitProblem(String value) {
    String unit = normalizeUnitCode(value);
    if (unit == null) return "采购单位必填：报价按单位报，没有单位的单价无法与 06 采购单对齐";
    if (unit.length() > MAX_UNIT_CODE_LENGTH) return "采购单位代码最多 " + MAX_UNIT_CODE_LENGTH + " 个字符";
    return null;
  }

  /**
   * 币种校验（ISO 4217 三位大写字母，规则 **R3**）。
   *
   * <p>只判格式不判词表：ISO 4217 有 180 来个活跃码，而常用就那几十个 —— 判"在不在词表里"会让真实存在但少见的币种（小币种国家、离岸结算用的非主权码）
   * 存不进来，而那正是跨境采购会遇到的情况。
   */
  public static String currencyProblem(String value) {
    String currency = normalizeCurrency(value);
    if (currency == null) return "币种必填";
    if (!currency.matches("[A-Z]{3}")) return "币种请用 ISO 4217 三位代码（如 USD / EUR / CNY）";
    return null;
  }

  // ================================================================ MOQ 与交期

  /**
   * MOQ 校验（规则 **R4**）。
   *
   * <p>必填且 &gt; 0：<b>没有起订门槛的报价不是一条可执行的采购承诺</b> （"随时下单多少都行"在跨境采购里不存在）。它为空时 06 建单会退化成"不校验起订量"，
   * 而那比直接报错危险 —— 采购单会带着一个对方根本不会接受的数量发出去。
   *
   * <p>与 {@code party_supplier_profile.moq_quantity} 的区别见 V32 第一节： 那边是供应商级门槛（可空），这边是 SKU 级门槛（必填）。
   */
  public static String moqProblem(BigDecimal value) {
    if (value == null) return "起订量必填：报价必须说清这个 SKU 最少下多少";
    if (value.signum() <= 0) return "起订量必须大于 0";
    if (value.precision() - value.scale() > 13) return "起订量超出可表示范围";
    if (value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP).signum() <= 0)
      return "起订量精度不足：小于 0.000001 的起订量无法存储";
    return null;
  }

  /**
   * 交期校验（天，规则 **R4**）。
   *
   * <p>0 是合法值且含义明确：当日/即时发货（现货供应商）。与 V30 的 {@code payment_terms_days=0} 同一语义约定 —— 0 表示「就是零」，不是「没填」。
   * 没填交期时落库取 0：跨境报价里绝大多数是现货，强制必填会让运营大量填 0， 而 0 正是现货的正确答案。
   */
  public static String leadTimeProblem(Integer days) {
    if (days == null) return null; // 落库取 0（NOT NULL DEFAULT 0）
    if (days < 0) return "交期不能是负数";
    if (days > MAX_LEAD_TIME_DAYS) return "交期最多 " + MAX_LEAD_TIME_DAYS + " 天，请确认没有把月填成天";
    return null;
  }

  /** 交期归一：null → 0（对应「当日发货」）。 */
  public static int normalizeLeadTime(Integer days) {
    return days == null ? 0 : days;
  }

  /** 交期展示串。 */
  public static String leadTimeLabel(int days) {
    return days <= 0 ? "当日发货" : "交期 " + days + " 天";
  }

  // ================================================================ 有效期

  /**
   * 有效期校验（规则见 V32 第五节）。
   *
   * <p>{@code validFrom} 必填：一条"从哪天开始生效都说不清"的报价无法参与 有效报价判定（{@code valid_from <= at} 那一半恒不成立或恒成立）。
   *
   * <p>{@code validTo} 可空 = 长期有效（常年协议价）。结束日<b>可以等于</b>开始日 （只有一天有效的促销价是真实存在的），不能早于 ——
   * 那是一条永不生效却看起来合法的行。
   *
   * <p>刻意<b>不拦</b>过去的开始日：补录一条上个月就开始的报价是正常操作 （供应商把纸质报价单发过来，运营照着录），而"valid_from 不能是过去"会让补录无法进行。
   */
  public static String validityProblem(LocalDate from, LocalDate to) {
    if (from == null) return "生效日期必填：报价必须说清从哪天开始有效";
    if (to != null && to.isBefore(from)) return "有效期结束日不能早于生效日期（" + from + " → " + to + "）";
    return null;
  }

  /** 有效期展示串。 */
  public static String validityLabel(LocalDate from, LocalDate to) {
    if (from == null && to == null) return "未设置有效期";
    if (to == null) return from + " 起长期有效";
    return from + " ~ " + to;
  }

  /**
   * 某一天是否落在有效区间内（闭区间）。
   *
   * <p>闭区间是 V32 第五节钉死的口径，与 {@link #validityLabel} 展示的文本一致： 界面上写着"~ 3/31"，系统在 3/31 就突然不认，两边说的不是同一件事。
   */
  public static boolean withinValidity(LocalDate at, LocalDate from, LocalDate to) {
    if (at == null || from == null) return false;
    if (at.isBefore(from)) return false;
    return to == null || !at.isAfter(to);
  }

  /**
   * 距离失效还有几天（负数表示已过期）。
   *
   * <p>给报价治理用："还有 7 天到期"是运营重新询价的<b>提醒</b>， 而"已过期 3 天"是<b>阻断</b>（06 建单取不到价）。两种状态用同一个数字表达，
   * 前端只要判符号，不用记两份语义。
   */
  public static long daysUntilExpiry(LocalDate at, LocalDate to) {
    if (at == null || to == null) return Long.MAX_VALUE;
    return ChronoUnit.DAYS.between(at, to);
  }

  // ================================================================ 优先级

  /** 优先级校验。1 = 首选，越小越优先（V32 第四节）。 */
  public static String priorityProblem(Integer priority) {
    if (priority == null) return null; // 落库取 1（DEFAULT）
    if (priority < 1) return "优先级从 1 开始（1 = 首选）";
    if (priority > MAX_PRIORITY) return "优先级最多 " + MAX_PRIORITY + "，1 表示首选";
    return null;
  }

  /** 优先级归一：null → 1（首选）。 */
  public static int normalizePriority(Integer priority) {
    return priority == null ? 1 : priority;
  }

  /** 优先级展示串：把"越小越优先"这句规则直接写进文案，省掉界面上的额外解释。 */
  public static String priorityLabel(int priority) {
    return priority <= 1 ? "首选" : "第 " + priority + " 优先";
  }

  // ================================================================ 状态

  /** 状态校验（规则 **R7**）。留空取 ACTIVE。 */
  public static String statusProblem(String value) {
    String status = normalizeEnum(value);
    if (status == null) return null; // 落库取 ACTIVE
    if (!STATUSES.contains(status))
      return "报价状态不在词表里（可用：" + String.join(" / ", sorted(STATUSES)) + "）";
    return null;
  }

  /** 状态归一：null → ACTIVE。 */
  public static String normalizeStatus(String value) {
    String status = normalizeEnum(value);
    return status == null ? "ACTIVE" : status;
  }

  /** 状态展示串。 */
  public static String statusLabel(String status) {
    if ("DISABLED".equals(status)) return "已停用";
    if ("ACTIVE".equals(status)) return "启用中";
    return status;
  }

  // ================================================================ 阶梯价（规则 R5）

  /**
   * 单档阶梯校验。
   *
   * <p>起订量与单价都必填且 &gt; 0：单价为 0 的档要么是"赠品"要么是"未填"， 而这两种在采购单上算出来的金额完全一样（都是 0），没法区分 ——
   * 所以直接判非法，不给"免费档"留位置。真的要赠品应该走 06 采购的折扣行， 不该混在供应商报价里（那里面的每一行都要参与金额结算）。
   */
  public static String tierProblem(BigDecimal minQuantity, BigDecimal unitPrice) {
    if (minQuantity == null) return "阶梯起订量必填";
    if (minQuantity.signum() <= 0) return "阶梯起订量必须大于 0";
    if (unitPrice == null) return "阶梯单价必填";
    if (unitPrice.signum() <= 0) return "阶梯单价必须大于 0（0 元档请走 06 采购的折扣行）";
    if (unitPrice.precision() - unitPrice.scale() > PRICE_PRECISION) return "阶梯单价超出可表示范围";
    if (unitPrice.setScale(PRICE_SCALE, RoundingMode.HALF_UP).signum() <= 0)
      return "阶梯单价精度不足：小于 0.0001 的单价无法存储";
    return null;
  }

  /**
   * 阶梯整体校验：至少一档、起订量严格递增无重复、档数上限（规则 **R5**）。
   *
   * <p><b>严格递增</b>而不是"排序后允许相等"：相等的主键在 DB 层已经不可能 （{@code PRIMARY KEY(tenant_id, quote_id,
   * min_quantity)}），而这里在写库<b>之前</b> 就拦下来，让运营看到"第 2 档与第 3 档起订量相同"而不是一条 constraint name。
   *
   * <p>调用方应先按 {@code minQuantity} 升序排好再传进来 —— 本方法只判"传入顺序是否递增"，不替调用方排序（V32 第三节： 档位顺序是服务层写入前确定的，不靠 DB
   * 排序保证）。
   */
  public static String tiersProblem(List<BigDecimal> minQuantities, int tierCount) {
    if (tierCount <= 0) return "阶梯价至少一档：没有阶梯的报价取不出单价，06 采购单会带出一个空单价";
    if (tierCount > MAX_TIERS) return "阶梯价最多 " + MAX_TIERS + " 档";
    for (int i = 0; i < minQuantities.size(); i++) {
      BigDecimal current = minQuantities.get(i);
      if (current == null) return "第 " + (i + 1) + " 档的起订量必填";
      if (i == 0) continue;
      BigDecimal previous = minQuantities.get(i - 1);
      if (previous != null && current.compareTo(previous) <= 0)
        return "第 "
            + (i + 1)
            + " 档起订量（"
            + quantityLabel(current)
            + "）必须大于第 "
            + i
            + " 档（"
            + quantityLabel(previous)
            + "）：档位按起订量升序排列，不允许重复";
    }
    return null;
  }

  /**
   * 按数量取适用档位（开口阶梯：取 {@code minQuantity <= quantity} 里最大的一档）。
   *
   * <p>返回 null 表示<b>没有任何适用档</b>（数量低于第一档起订量）。 刻意<b>不</b>返回"最后一档"来兜底 —— 低于起订量的那一档单价高是供应商的
   * 谈判空间，而系统替运营按高档价建单会让采购单带出一个对方没报过的价。 调用方据此把"数量低于起订量"报给用户（这与 MOQ 校验是同一件事的两面）。
   */
  public static <T> T applicableTier(
      List<BigDecimal> minQuantities, List<T> tiers, BigDecimal quantity) {
    if (minQuantities == null || tiers == null || minQuantities.size() != tiers.size()) return null;
    if (quantity == null) return null;
    T chosen = null;
    BigDecimal chosenFrom = null;
    for (int i = 0; i < minQuantities.size(); i++) {
      BigDecimal from = minQuantities.get(i);
      if (from == null || quantity.compareTo(from) < 0) continue;
      if (chosenFrom == null || from.compareTo(chosenFrom) > 0) {
        chosenFrom = from;
        chosen = tiers.get(i);
      }
    }
    return chosen;
  }

  // ================================================================ 歧义判定（规则 R2）

  /**
   * 「同一优先级有多条有效报价」的可读报错文案（规则 **R2**）。
   *
   * <p>蓝图点名的那条规则：**报错让用户选，不能随机取一条**。这条文案是"让用户选"的入口， 它必须说清<b>并列的是什么</b>（哪几条、什么优先级、什么币种），
   * 否则运营看到"存在多条有效报价"之后仍然不知道该去改哪一条。
   *
   * <p>给出的处置建议是"把要用的那条优先级调小一位"而不是"删掉另一条"： 两条报价都还可能有效（不同币种、不同交期），删掉一条等于丢了信息。
   *
   * @param priority 并列的优先级值
   * @param count 并列的条数
   * @return 无歧义（{@code count <= 1}）时返回 null
   */
  public static String ambiguityProblem(int priority, int count) {
    if (count <= 1) return null;
    String advice =
        priority > 1
            ? "请把要用作默认的那条优先级调小一位（" + priority + " → " + (priority - 1) + "）"
            : "这几条已经是最高优先级 1 了，请把不作为默认的那条调大一位（1 → 2）";
    return "该 SKU 有 "
        + count
        + " 条优先级同为 "
        + priority
        + " 的有效报价，"
        + "无法确定用哪一条（系统不会替你随机选）。"
        + advice
        + "，其余保持 "
        + priority
        + "；"
        + "或停用（DISABLED）暂时不用的那几条。";
  }

  /**
   * 给一批有效报价逐条标注选择状态（规则 **R2**）。
   *
   * <p>输出与输入<b>等长同序</b>，调用方按下标取用。三个状态：
   *
   * <ul>
   *   <li>{@code SELECTED} —— 唯一一个处于最高优先级的有效报价。
   *   <li>{@code AMBIGUOUS} —— 与另一个 SELECTED 共享最高优先级。
   *   <li>{@code LOWER_PRIORITY} —— 有效但不是最高优先级（备选，仍然是有效报价）。
   * </ul>
   *
   * <p>三条里**没有**一条表示"被禁用"或"已过期" —— 那些行根本不在有效报价集合里。 把它们混进同一个列表标注，是为了让下游不会误把停用行当有效报价带出去。
   *
   * @return 与 priorities 等长的状态列表
   */
  public static List<String> selectionStates(List<Integer> priorities) {
    List<String> states = new ArrayList<>(priorities.size());
    if (priorities.isEmpty()) return states;
    int best = Integer.MAX_VALUE;
    for (Integer p : priorities) if (p != null && p < best) best = p;
    int bestCount = 0;
    for (Integer p : priorities) if (p != null && p == best) bestCount++;
    for (Integer p : priorities) {
      if (p == null || p != best) states.add("LOWER_PRIORITY");
      else states.add(bestCount > 1 ? "AMBIGUOUS" : "SELECTED");
    }
    return states;
  }

  /** 选择状态展示串。 */
  public static String selectionStateLabel(String state) {
    if ("SELECTED".equals(state)) return "默认采用";
    if ("AMBIGUOUS".equals(state)) return "需人工选择";
    if ("LOWER_PRIORITY".equals(state)) return "备选";
    return state;
  }

  // ================================================================ 词表选项与标签

  public record Option(String code, String label) {}

  /** 报价状态选项。 */
  public static List<Option> statusOptions() {
    return List.of(
        new Option("ACTIVE", statusLabel("ACTIVE")),
        new Option("DISABLED", statusLabel("DISABLED")));
  }

  /**
   * 选择状态选项（给前端筛选"只看需人工选择的"用）。
   *
   * <p>这三个状态是<b>读出来</b>的而不是填进去的，所以它们的选项只出现在筛选器里。
   */
  public static List<Option> selectionStateOptions() {
    return List.of(
        new Option("SELECTED", selectionStateLabel("SELECTED")),
        new Option("AMBIGUOUS", selectionStateLabel("AMBIGUOUS")),
        new Option("LOWER_PRIORITY", selectionStateLabel("LOWER_PRIORITY")));
  }

  private static List<String> sorted(Set<String> values) {
    return values.stream().sorted().toList();
  }

  /** 词表去重（给服务层检查入参里有重复项用）。 */
  public static <T> List<T> dedupe(List<T> values) {
    return values == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(values));
  }

  /** 第一个非 null 的值（拼多个 {@code *Problem} 时用，避免层层 firstNonNull）。 */
  public static String firstNonNull(String... values) {
    for (String value : values) if (value != null) return value;
    return null;
  }
}
