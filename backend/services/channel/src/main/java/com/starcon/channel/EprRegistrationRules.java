package com.starcon.channel;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * 16.3.2.2 EPR/包装法的**判定规则**（纯函数，不碰数据库、不碰 Spring）。
 *
 * <p>蓝图加粗的那一句是「<b>EPR 未注册的国家应直接限制刊登</b>，这是避免下架与罚款最有效的做法」。
 * 所以「能不能刊登」这件事必须**可审阅、可复算、可用单元测试钉住**，
 * 而不是散在 SQL 里的一堆 if。
 *
 * <p>🔴 判定口径的七条不变量：
 *
 * <ol>
 *   <li><b>「不适用」与「未注册」是两件事</b>：某国根本不要求某制度 ⇒ {@code NOT_APPLICABLE}
 *       ⇒ <b>放行</b>；要求了但没注册 ⇒ {@code NOT_REGISTERED} ⇒ <b>阻断</b>。
 *       把 NOT_APPLICABLE 当成未注册，会把不该拦的全拦了。</li>
 *   <li><b>「判不了」不许默认放行</b>：注册数据读不到 / 有效期无法判定 ⇒ 第三态
 *       （{@code VALIDITY_UNKNOWN} / {@code NOT_EFFECTIVE_YET}）⇒ <b>阻断</b>，
 *       但原因与「明确未注册」分开列，界面能说清「这是判不了，不是已知违规」。</li>
 *   <li><b>过期与没注册是两回事</b>：过期登记要续期，没登记要去注册 ⇒ 两种原因分开报。</li>
 *   <li><b>停用的登记不作为覆盖依据</b>：它是「撤下」，不是「还在」。</li>
 *   <li><b>适用品类是「多对多」</b>：一条登记适用多个国家 + 多个品类；
 *       反查「这个国家这个品类有没有覆盖」是本模块的<b>输出</b>，不是内部细节。</li>
 *   <li><b>申报期是算出来的</b>：周期 + 起止月份 ⇒ 「当前是否在申报期内」+ 「距下一期还有几天」，
 *       不用「上次什么时候报的」这种不需要的事实。</li>
 *   <li><b>本模块不含财务凭证</b>：费用只是**本服务自己的费用记录**（12 财务在本仓尚未落地）。</li>
 * </ol>
 */
public final class EprRegistrationRules {

  private EprRegistrationRules() {}

  // ---------------------------------------------------------------- 制度词表

  /** 制度：德国 LUCID 包装登记。 */
  public static final String REGIME_LUCID = "LUCID";

  /** 制度：包装注册。 */
  public static final String REGIME_PACKAGING_REGISTRATION = "PACKAGING_REGISTRATION";

  /** 制度：电池法。 */
  public static final String REGIME_BATTERY = "BATTERY";

  /** 制度：电子废弃物（WEEE）。 */
  public static final String REGIME_WEEE = "WEEE";

  /** 🔴 制度值域：**四种**，钉死在 DB 的 CHECK 里。新增制度走 DB 迁移加值域，不许运行时塞字符串。 */
  public static final List<String> REGIME_TYPES =
      List.of(REGIME_LUCID, REGIME_PACKAGING_REGISTRATION, REGIME_BATTERY, REGIME_WEEE);

  // ---------------------------------------------------------------- 申报周期词表

  /** 申报周期：按月。 */
  public static final String CYCLE_MONTHLY = "MONTHLY";

  /** 申报周期：按季。 */
  public static final String CYCLE_QUARTERLY = "QUARTERLY";

  /** 申报周期：按年。 */
  public static final String CYCLE_ANNUAL = "ANNUAL";

  /** 申报周期：一次性。 */
  public static final String CYCLE_ONE_TIME = "ONE_TIME";

  public static final List<String> FILING_CYCLES =
      List.of(CYCLE_MONTHLY, CYCLE_QUARTERLY, CYCLE_ANNUAL, CYCLE_ONE_TIME);

  // ---------------------------------------------------------------- 有效期形态词表

  /** 有效期形态：有效期到某一天。 */
  public static final String VALIDITY_FIXED = "FIXED";

  /** 有效期形态：长期有效 / 未设失效日。 */
  public static final String VALIDITY_OPEN_ENDED = "OPEN_ENDED";

  /** 有效期形态：没记有效期 ⇒ **判不了** ⇒ 不许默认放行。 */
  public static final String VALIDITY_UNKNOWN = "UNKNOWN";

  public static final List<String> VALIDITY_KINDS =
      List.of(VALIDITY_FIXED, VALIDITY_OPEN_ENDED, VALIDITY_UNKNOWN);

  // ---------------------------------------------------------------- 判定原因词表

  /** 判定：覆盖且有效。 */
  public static final String REASON_OK = "OK";

  /** 判定：该国**不要求**该制度（不在参考表里）⇒ NOT_APPLICABLE ⇒ 放行。 */
  public static final String REASON_NOT_APPLICABLE = "NOT_APPLICABLE";

  /** 判定：该国要求该制度，但没有注册（也没声明不适用）。 */
  public static final String REASON_NOT_REGISTERED = "NOT_REGISTERED";

  /** 判定：租户显式声明该制度在本国不适用其业务 ⇒ NOT_APPLICABLE ⇒ 放行。 */
  public static final String REASON_EXEMPTED = "EXEMPTED";

  /** 判定：登记已过有效期。 */
  public static final String REASON_EXPIRED = "EXPIRED";

  /** 判定：登记没记有效期 ⇒ 判不了。 */
  public static final String REASON_VALIDITY_UNKNOWN = "VALIDITY_UNKNOWN";

  /** 判定：当前没有生效版本。 */
  public static final String REASON_NOT_EFFECTIVE_YET = "NOT_EFFECTIVE_YET";

  /** 判定：登记已被停用。 */
  public static final String REASON_DISABLED = "DISABLED";

  /** 判定：登记的适用品类没覆盖该制度在本国适用的品类。 */
  public static final String REASON_CATEGORY_NOT_COVERED = "CATEGORY_NOT_COVERED";

  /** 🔴 **会阻断刊登**的原因集合（其余一律放行）。 */
  public static final List<String> BLOCKING_REASONS =
      List.of(
          REASON_NOT_REGISTERED,
          REASON_EXPIRED,
          REASON_VALIDITY_UNKNOWN,
          REASON_NOT_EFFECTIVE_YET,
          REASON_DISABLED,
          REASON_CATEGORY_NOT_COVERED);

  // ---------------------------------------------------------------- 申报期状态词表

  /** 申报期状态：当前在申报期内。 */
  public static final String FILING_IN_PERIOD = "IN_PERIOD";

  /** 申报期状态：当前不在申报期内。 */
  public static final String FILING_OUT_OF_PERIOD = "OUT_OF_PERIOD";

  /** 申报期状态：一次性申报，没有周期。 */
  public static final String FILING_NOT_RECURRING = "NOT_RECURRING";

  /** 申报期状态：没记申报月份 ⇒ 判不了。 */
  public static final String FILING_UNKNOWN = "UNKNOWN";

  public static final List<String> FILING_STATUSES =
      List.of(FILING_IN_PERIOD, FILING_OUT_OF_PERIOD, FILING_NOT_RECURRING, FILING_UNKNOWN);

  // ---------------------------------------------------------------- 结论词表

  /** 闸门结论：放行。 */
  public static final String CONCLUSION_ALLOWED = "ALLOWED";

  /** 闸门结论：阻断（已知阻断项）。 */
  public static final String CONCLUSION_BLOCKED = "BLOCKED";

  /** 闸门结论：判不了（第三态）—— **仍然不放行**，但原因与「明确不合规」分开。 */
  public static final String CONCLUSION_INDETERMINATE = "INDETERMINATE";

  public static final List<String> CONCLUSIONS =
      List.of(CONCLUSION_ALLOWED, CONCLUSION_BLOCKED, CONCLUSION_INDETERMINATE);

  // ---------------------------------------------------------------- 费用词表

  /** 费用类型：注册费。 */
  public static final String FEE_REGISTRATION = "REGISTRATION_FEE";

  /** 费用类型：申报费。 */
  public static final String FEE_FILING = "FILING_FEE";

  /** 费用类型：罚款。 */
  public static final String FEE_PENALTY = "PENALTY";

  /** 费用类型：其他。 */
  public static final String FEE_OTHER = "OTHER";

  public static final List<String> FEE_TYPES =
      List.of(FEE_REGISTRATION, FEE_FILING, FEE_PENALTY, FEE_OTHER);

  /** 费用状态：已记录（还没付）。 */
  public static final String FEE_STATUS_RECORDED = "RECORDED";

  /** 费用状态：已付。 */
  public static final String FEE_STATUS_PAID = "PAID";

  /** 费用状态：已豁免。 */
  public static final String FEE_STATUS_WAIVED = "WAIVED";

  public static final List<String> FEE_STATUSES =
      List.of(FEE_STATUS_RECORDED, FEE_STATUS_PAID, FEE_STATUS_WAIVED);

  /** 承担方：卖家自己。 */
  public static final String PAID_BY_SELLER = "SELLER";

  /** 承担方：平台。 */
  public static final String PAID_BY_PLATFORM = "PLATFORM";

  /** 承担方：服务商（代注册 / 代申报的机构）。 */
  public static final String PAID_BY_SERVICE_PROVIDER = "SERVICE_PROVIDER";

  public static final List<String> PAID_BY =
      List.of(PAID_BY_SELLER, PAID_BY_PLATFORM, PAID_BY_SERVICE_PROVIDER);

  // ---------------------------------------------------------------- 人话

  /** 制度的人话。 */
  public static String regimeLabel(String regimeCode) {
    return switch (regimeCode) {
      case REGIME_LUCID -> "德国 LUCID 包装登记";
      case REGIME_PACKAGING_REGISTRATION -> "包装注册";
      case REGIME_BATTERY -> "电池法";
      case REGIME_WEEE -> "电子废弃物（WEEE）";
      default -> regimeCode;
    };
  }

  /** 申报周期的人话。 */
  public static String filingCycleLabel(String cycle) {
    return switch (cycle) {
      case CYCLE_MONTHLY -> "按月";
      case CYCLE_QUARTERLY -> "按季";
      case CYCLE_ANNUAL -> "按年";
      case CYCLE_ONE_TIME -> "一次性";
      default -> "未登记";
    };
  }

  /** 有效期形态的人话。 */
  public static String validityKindLabel(String kind) {
    return switch (kind) {
      case VALIDITY_FIXED -> "有效期到某一天";
      case VALIDITY_OPEN_ENDED -> "长期有效（未设失效日）";
      case VALIDITY_UNKNOWN -> "没记有效期（判不了）";
      default -> kind;
    };
  }

  /** 判定原因的人话。 */
  public static String reasonLabel(String reasonCode) {
    return switch (reasonCode) {
      case REASON_OK -> "已覆盖";
      case REASON_NOT_APPLICABLE -> "不适用";
      case REASON_NOT_REGISTERED -> "未注册";
      case REASON_EXEMPTED -> "不适用（已声明）";
      case REASON_EXPIRED -> "登记已过有效期";
      case REASON_VALIDITY_UNKNOWN -> "有效期判不了";
      case REASON_NOT_EFFECTIVE_YET -> "当前没有生效的登记版本";
      case REASON_DISABLED -> "登记已停用";
      case REASON_CATEGORY_NOT_COVERED -> "适用品类没覆盖";
      default -> reasonCode;
    };
  }

  /** 🔴 这个原因**会不会**阻断刊登。 */
  public static boolean blocking(String reasonCode) {
    return BLOCKING_REASONS.contains(reasonCode);
  }

  /** 申报期状态的人话。 */
  public static String filingStatusLabel(String status) {
    return switch (status) {
      case FILING_IN_PERIOD -> "当前在申报期内";
      case FILING_OUT_OF_PERIOD -> "当前不在申报期内";
      case FILING_NOT_RECURRING -> "一次性申报（无周期）";
      case FILING_UNKNOWN -> "没记申报月份（判不了）";
      default -> status;
    };
  }

  /** 覆盖状态的人话。 */
  public static String coverageStateLabel(String state) {
    return reasonLabel(state);
  }

  /** 费用类型的人话。 */
  public static String feeTypeLabel(String feeType) {
    return switch (feeType) {
      case FEE_REGISTRATION -> "注册费";
      case FEE_FILING -> "申报费";
      case FEE_PENALTY -> "罚款";
      case FEE_OTHER -> "其他";
      default -> feeType;
    };
  }

  /** 费用状态的人话。 */
  public static String feeStatusLabel(String status) {
    return switch (status) {
      case FEE_STATUS_RECORDED -> "已记录（未付）";
      case FEE_STATUS_PAID -> "已付";
      case FEE_STATUS_WAIVED -> "已豁免";
      default -> status;
    };
  }

  /** 承担方的人话。 */
  public static String paidByLabel(String paidBy) {
    return switch (paidBy) {
      case PAID_BY_SELLER -> "卖家承担";
      case PAID_BY_PLATFORM -> "平台承担";
      case PAID_BY_SERVICE_PROVIDER -> "服务商承担";
      default -> paidBy;
    };
  }

  /** 闸门结论的人话。 */
  public static String conclusionLabel(String conclusion) {
    return switch (conclusion) {
      case CONCLUSION_ALLOWED -> "放行";
      case CONCLUSION_BLOCKED -> "阻断";
      case CONCLUSION_INDETERMINATE -> "判不了（仍不放行）";
      default -> conclusion;
    };
  }

  // ---------------------------------------------------------------- 申报期计算

  /**
   * 申报期窗口的计算结果。
   *
   * @param status 申报期状态（IN_PERIOD / OUT_OF_PERIOD / NOT_RECURRING / UNKNOWN）
   * @param periodStart 本期申报期起（null = 没有周期）
   * @param periodEnd 本期申报期止（null = 没有周期）
   * @param daysUntilNext 距下一期申报期开始还有几天（null = 没有周期 / 判不了）
   */
  public record FilingWindow(
      String status, LocalDate periodStart, LocalDate periodEnd, Integer daysUntilNext) {}

  /**
   * 🔴 算出「当前是否在申报期内」。
   *
   * <p>口径：申报期由<b>周期 + 起止月份</b>决定。起止月份可以跨年（11..2）。
   *
   * <ul>
   *   <li>一次性（ONE_TIME）⇒ 没有周期（{@code NOT_RECURRING}）。</li>
   *   <li>没记起止月份 ⇒ <b>判不了</b>（{@code UNKNOWN}），**不是**「不在申报期内」。</li>
   *   <li>其余：算出本期窗口；今天落在窗口里 ⇒ {@code IN_PERIOD}。</li>
   * </ul>
   *
   * @param cycle 申报周期
   * @param startMonth 申报期起始月（1..12）
   * @param endMonth 申报期结束月（1..12）
   * @param now 当前日期（调用方给的是**同源 DB 时钟**换算出来的日期）
   */
  public static FilingWindow filingWindow(
      String cycle, Integer startMonth, Integer endMonth, LocalDate now) {
    if (CYCLE_ONE_TIME.equals(cycle)) {
      return new FilingWindow(FILING_NOT_RECURRING, null, null, null);
    }
    if (startMonth == null || endMonth == null) {
      return new FilingWindow(FILING_UNKNOWN, null, null, null);
    }
    int start = Math.min(12, Math.max(1, startMonth));
    int end = Math.min(12, Math.max(1, endMonth));
    int year = now.getYear();
    LocalDate periodStart = LocalDate.of(year, start, 1);
    int endYear = start <= end ? year : year + 1;
    LocalDate periodEnd =
        LocalDate.of(endYear, end, 1).with(TemporalAdjusters.lastDayOfMonth());

    if (now.isBefore(periodStart)) {
      return new FilingWindow(
          FILING_OUT_OF_PERIOD, periodStart, periodEnd, (int) ChronoUnit.DAYS.between(now, periodStart));
    }
    if (!now.isAfter(periodEnd)) {
      return new FilingWindow(FILING_IN_PERIOD, periodStart, periodEnd, 0);
    }
    LocalDate nextStart = LocalDate.of(year + 1, start, 1);
    return new FilingWindow(
        FILING_OUT_OF_PERIOD,
        periodStart,
        periodEnd,
        (int) ChronoUnit.DAYS.between(now, nextStart));
  }

  /** 申报期窗口的人话串（例：`2026-01-01 ~ 2026-03-31`）。 */
  public static String filingPeriodLabel(FilingWindow window) {
    if (window.periodStart() == null || window.periodEnd() == null) {
      return "";
    }
    return window.periodStart() + " ~ " + window.periodEnd();
  }

  // ---------------------------------------------------------------- 校验辅助

  /**
   * 🔴 币种校验：**必须是三个大写字母**。
   *
   * <p>不许把不同币种直接相加，所以币种必须是**受控形状**而不是自由文本。
   */
  public static boolean validCurrency(String currency) {
    if (currency == null) {
      return false;
    }
    String value = currency.trim();
    if (value.length() != 3) {
      return false;
    }
    for (int index = 0; index < 3; index++) {
      char c = value.charAt(index);
      if (c < 'A' || c > 'Z') {
        return false;
      }
    }
    return true;
  }

  /** 🔴 ISO 3166-1 alpha-2 国家码形状校验（两位大写字母）。 */
  public static boolean validCountryCode(String countryCode) {
    if (countryCode == null) {
      return false;
    }
    String value = countryCode.trim();
    if (value.length() != 2) {
      return false;
    }
    for (int index = 0; index < 2; index++) {
      char c = value.charAt(index);
      if (c < 'A' || c > 'Z') {
        return false;
      }
    }
    return true;
  }

  /** 一个国家在某制度下的适用品类（供服务层组装参考树）。 */
  public static List<String> categoriesOf(List<String> all, String filter) {
    List<String> result = new ArrayList<>();
    if (all == null) {
      return result;
    }
    for (String value : all) {
      if (filter == null || filter.isBlank() || filter.equals(value)) {
        result.add(value);
      }
    }
    return result;
  }

  /** 申报期的年月串（给界面显示「哪一年哪一期」）。 */
  public static String yearMonthText(LocalDate date) {
    return date == null ? "" : YearMonth.from(date).toString();
  }
}
