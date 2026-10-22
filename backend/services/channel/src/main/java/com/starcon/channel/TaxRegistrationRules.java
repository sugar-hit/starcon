package com.starcon.channel;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 16.3.1.1 税号与辖区的**判定规则**（纯函数，不碰数据库、不碰 Spring）。
 *
 * <p>蓝图加粗的那一句是「<b>未注册辖区的销售额是合规炸弹</b>：欧洲没有 VAT 号还在欧盟销售，
 * 是稽查重点」。所以缺失项判定必须**可审阅、可复算、可用单元测试钉住**，
 * 而不是散在 SQL 里的一堆 if。
 *
 * <p>🔴 判定口径的六条不变量：
 *
 * <ol>
 *   <li><b>「未注册」与「缺号」是两个不同的事实</b>：没在那儿注册 ⇒
 *       {@code JURISDICTION_NOT_REGISTERED}；注册了但号没填 ⇒ {@code VAT_NUMBER_MISSING}。
 *       两者同时报会把一个缺口数成两个，运营会以为缺了两样东西。</li>
 *   <li><b>只有「已注册」的登记才谈缺号</b>：{@code is_registered=false} 时 VAT 号为空是
 *       <b>正常</b>的（没注册当然没号），报「缺 VAT 号」是把「没注册」重复报一遍。</li>
 *   <li><b>缺 EPR 号只在该辖区要求 EPR 时才报</b>（欧盟成员国与英国），
 *       别的辖区不要求就不报 —— 不拿「到处都缺」淹没真缺口。</li>
 *   <li><b>有效期没记 ≠ 有效期没到</b>：没记有效期是
 *       {@code REGISTRATION_VALIDITY_UNKNOWN}（判不了），**不是**「已过期」。</li>
 *   <li><b>归档（ARCHIVED）的登记不参与判定</b>：它是「撤下」，不是「还在」。</li>
 *   <li><b>主体自带的缺口与「要求未满足」的缺口分开</b>：法人主体行没有「应当登记哪些辖区」
 *       这个外部要求（系统推不出），只报它自己记录里的缺失项。</li>
 * </ol>
 */
public final class TaxRegistrationRules {

  private TaxRegistrationRules() {}

  // ---------------------------------------------------------------- 词表

  /** 主体类型：店铺（本服务 channel 自家库）。 */
  public static final String SUBJECT_SHOP = "SHOP";

  /** 主体类型：法人（identity 拥有的逻辑 ID）。 */
  public static final String SUBJECT_LEGAL_ENTITY = "LEGAL_ENTITY";

  public static final List<String> SUBJECT_TYPES = List.of(SUBJECT_SHOP, SUBJECT_LEGAL_ENTITY);

  /** VAT 状态：已注册。 */
  public static final String VAT_REGISTERED = "REGISTERED";

  /** VAT 状态：未注册。 */
  public static final String VAT_NOT_REGISTERED = "NOT_REGISTERED";

  /** VAT 状态：注册中。 */
  public static final String VAT_PENDING = "PENDING";

  /** VAT 状态：豁免（如 OSS / IOSS 方案，没有本地 VAT 号）。 */
  public static final String VAT_EXEMPT = "EXEMPT";

  /** VAT 状态：未知（还没录入）。 */
  public static final String VAT_UNKNOWN = "UNKNOWN";

  public static final List<String> VAT_STATUSES =
      List.of(VAT_REGISTERED, VAT_NOT_REGISTERED, VAT_PENDING, VAT_EXEMPT, VAT_UNKNOWN);

  /** 申报周期：按月。 */
  public static final String CYCLE_MONTHLY = "MONTHLY";

  /** 申报周期：按季。 */
  public static final String CYCLE_QUARTERLY = "QUARTERLY";

  /** 申报周期：按半年。 */
  public static final String CYCLE_SEMI_ANNUAL = "SEMI_ANNUAL";

  /** 申报周期：按年。 */
  public static final String CYCLE_ANNUAL = "ANNUAL";

  public static final List<String> FILING_CYCLES =
      List.of(CYCLE_MONTHLY, CYCLE_QUARTERLY, CYCLE_SEMI_ANNUAL, CYCLE_ANNUAL);

  // ---------------------------------------------------------------- 缺失项类型

  /** 缺失项：应当在该辖区登记，但没有有效登记。 */
  public static final String GAP_JURISDICTION_NOT_REGISTERED = "JURISDICTION_NOT_REGISTERED";

  /** 缺失项：已注册但登记号没填。 */
  public static final String GAP_VAT_NUMBER_MISSING = "VAT_NUMBER_MISSING";

  /** 缺失项：该辖区要求 EPR 注册号，但没填。 */
  public static final String GAP_EPR_NUMBER_MISSING = "EPR_NUMBER_MISSING";

  /** 缺失项：登记已过有效期。 */
  public static final String GAP_REGISTRATION_EXPIRED = "REGISTRATION_EXPIRED";

  /** 缺失项：登记即将到期。 */
  public static final String GAP_REGISTRATION_EXPIRING_SOON = "REGISTRATION_EXPIRING_SOON";

  /** 缺失项：登记有效期没记，判不了有没有过期。 */
  public static final String GAP_REGISTRATION_VALIDITY_UNKNOWN = "REGISTRATION_VALIDITY_UNKNOWN";

  public static final List<String> GAP_TYPES =
      List.of(
          GAP_JURISDICTION_NOT_REGISTERED,
          GAP_VAT_NUMBER_MISSING,
          GAP_EPR_NUMBER_MISSING,
          GAP_REGISTRATION_EXPIRED,
          GAP_REGISTRATION_EXPIRING_SOON,
          GAP_REGISTRATION_VALIDITY_UNKNOWN);

  /** 严重度：高（合规炸弹，先处理）。 */
  public static final String SEVERITY_HIGH = "HIGH";

  /** 严重度：中（要补，但不立刻爆）。 */
  public static final String SEVERITY_MEDIUM = "MEDIUM";

  public static final List<String> GAP_SEVERITIES = List.of(SEVERITY_HIGH, SEVERITY_MEDIUM);

  /** 距失效多少天之内算「即将到期」。 */
  public static final int EXPIRING_SOON_DAYS = 30;

  // ---------------------------------------------------------------- 人话

  /** 缺失项类型的人话。 */
  public static String gapTypeLabel(String gapType) {
    return switch (gapType) {
      case GAP_JURISDICTION_NOT_REGISTERED -> "未注册该辖区";
      case GAP_VAT_NUMBER_MISSING -> "缺 VAT 号（登记号）";
      case GAP_EPR_NUMBER_MISSING -> "缺 EPR 注册号";
      case GAP_REGISTRATION_EXPIRED -> "登记已过有效期";
      case GAP_REGISTRATION_EXPIRING_SOON -> "登记即将到期";
      case GAP_REGISTRATION_VALIDITY_UNKNOWN -> "没记有效期（判不了）";
      default -> gapType;
    };
  }

  /** VAT 状态的人话。 */
  public static String vatStatusLabel(String vatStatus) {
    return switch (vatStatus) {
      case VAT_REGISTERED -> "已注册";
      case VAT_NOT_REGISTERED -> "未注册";
      case VAT_PENDING -> "注册中";
      case VAT_EXEMPT -> "豁免（无本地 VAT 号）";
      case VAT_UNKNOWN -> "未知（还没录入）";
      default -> vatStatus;
    };
  }

  /** 申报周期的人话。 */
  public static String filingCycleLabel(String filingCycle) {
    return switch (filingCycle) {
      case CYCLE_MONTHLY -> "按月";
      case CYCLE_QUARTERLY -> "按季";
      case CYCLE_SEMI_ANNUAL -> "按半年";
      case CYCLE_ANNUAL -> "按年";
      default -> "未登记";
    };
  }

  /** 严重度的人话。 */
  public static String severityLabel(String severity) {
    return SEVERITY_HIGH.equals(severity) ? "高（合规炸弹）" : "中（要补）";
  }

  // ---------------------------------------------------------------- 输入形状

  /**
   * 一个辖区的业务事实（来自 {@code tax_registration_jurisdiction} 码表）。
   *
   * @param code 辖区编码
   * @param name 辖区名称
   * @param vatRequired 该辖区是否要求登记号
   * @param requiresEpr 该辖区是否要求 EPR 注册号
   */
  public record JurisdictionFact(String code, String name, boolean vatRequired, boolean requiresEpr) {}

  /**
   * 一条登记的事实（来自 {@code tax_registration_record}）。
   *
   * @param id 登记 ID
   * @param subjectType 主体类型
   * @param subjectId 主体 ID
   * @param jurisdictionCode 辖区编码
   * @param registered 是否注册
   * @param vatStatus VAT 状态
   * @param vatNumber VAT 号
   * @param eprRegistrationNo EPR 注册号
   * @param validFrom 有效期起
   * @param validUntil 有效期止
   * @param archived 是否已归档（归档 ⇒ 不参与判定）
   */
  public record RegistrationFact(
      String id,
      String subjectType,
      String subjectId,
      String jurisdictionCode,
      boolean registered,
      String vatStatus,
      String vatNumber,
      String eprRegistrationNo,
      OffsetDateTime validFrom,
      OffsetDateTime validUntil,
      boolean archived) {}

  /**
   * 一条「应当登记」的外部要求（由店铺启用的站点推导出来）。
   *
   * @param subjectType 主体类型（恒为 SHOP）
   * @param subjectId 店铺 ID
   * @param jurisdictionCode 辖区编码
   */
  public record RequirementFact(String subjectType, String subjectId, String jurisdictionCode) {}

  /**
   * 一条缺失项（规则层产物；展示文案由服务层补齐）。
   *
   * @param gapType 缺失项类型
   * @param severity 严重度
   * @param subjectType 主体类型
   * @param subjectId 主体 ID
   * @param jurisdictionCode 辖区编码
   * @param message 人话说明
   * @param remediation 整改建议
   */
  public record Gap(
      String gapType,
      String severity,
      String subjectType,
      String subjectId,
      String jurisdictionCode,
      String message,
      String remediation) {}

  // ---------------------------------------------------------------- 判定

  /**
   * 🔴 <b>外部要求</b>是否被满足：这家店在那个辖区**到底有没有在册**。
   *
   * <p>命中规则（按顺序）：
   *
   * <ol>
   *   <li>没有登记 ⇒ 未注册。</li>
   *   <li>登记了但 {@code is_registered=false} ⇒ 未注册（明确知道没注册）。</li>
   *   <li>登记已归档 ⇒ 未注册（撤下了就是不在册）。</li>
   *   <li>登记已过有效期 ⇒ 算「在册但已过期」：**不是**「未注册」——
   *       过期与没注册是两回事，前者要续期，后者要去注册。</li>
   * </ol>
   *
   * @param requirement 应当登记的要求
   * @param registration 命中的登记（可能为 null）
   * @param now 当前时刻（同源 DB 时钟）
   * @return 缺口列表（没有缺口时是空表）
   */
  public static List<Gap> evaluateRequirement(
      RequirementFact requirement, RegistrationFact registration, OffsetDateTime now) {
    List<Gap> gaps = new ArrayList<>();
    if (registration == null || registration.archived() || !registration.registered()) {
      gaps.add(
          new Gap(
              GAP_JURISDICTION_NOT_REGISTERED,
              SEVERITY_HIGH,
              requirement.subjectType(),
              requirement.subjectId(),
              requirement.jurisdictionCode(),
              "这家店在该辖区**没有在册的税务登记** —— 在这儿卖了货却查不到登记号，"
                  + "是稽查最直接的抓手。",
              "先在该辖区完成税务登记，拿到登记号后再回到本页录入（登记主体可以是店铺，也可以是它的法人）。"));
      return gaps;
    }
    // 过期不算「未注册」：续期能解决，不必重新申请。
    if (registration.validUntil() != null && registration.validUntil().isBefore(now)) {
      gaps.add(
          new Gap(
              GAP_REGISTRATION_EXPIRED,
              SEVERITY_HIGH,
              registration.subjectType(),
              registration.subjectId(),
              registration.jurisdictionCode(),
              "登记**已经过有效期** —— 过期登记和没有登记一样经不起稽查。",
              "尽快续期或重新申报，并把新的有效期回填到本条登记。"));
    }
    return gaps;
  }

  /**
   * 🔴 <b>登记自身</b>的缺失项：号有没有填、EPR 号有没有填、有效期判得了判不了。
   *
   * <p>这条判定对<b>店铺主体与法人主体一视同仁</b>：法人主体没有「应当登记哪些辖区」的外部要求
   * （系统推不出），但它自己登记里的缺号 / 过期照样要报。
   *
   * @param registration 登记
   * @param jurisdiction 该辖区的业务事实
   * @param now 当前时刻（同源 DB 时钟）
   * @return 缺口列表（没有缺口时是空表）
   */
  public static List<Gap> evaluateRegistration(
      RegistrationFact registration, JurisdictionFact jurisdiction, OffsetDateTime now) {
    List<Gap> gaps = new ArrayList<>();
    if (registration.archived()) {
      // 归档 ⇒ 不参与判定。撤下的登记不是「有缺项的登记」。
      return gaps;
    }
    if (!registration.registered()) {
      // 没注册 ⇒ 号为空是正常的，只报「未注册」（由外部要求那侧报）。
      return gaps;
    }
    if (jurisdiction.vatRequired()
        && isBlank(registration.vatNumber())
        && VAT_REGISTERED.equals(registration.vatStatus())) {
      gaps.add(
          new Gap(
              GAP_VAT_NUMBER_MISSING,
              SEVERITY_HIGH,
              registration.subjectType(),
              registration.subjectId(),
              registration.jurisdictionCode(),
              "登记状态是「已注册」，但**VAT 号是空的** —— 号没填就等于没登记。",
              "把该辖区的 VAT / 登记号填进本条登记。"));
    }
    if (jurisdiction.requiresEpr() && isBlank(registration.eprRegistrationNo())) {
      gaps.add(
          new Gap(
              GAP_EPR_NUMBER_MISSING,
              SEVERITY_MEDIUM,
              registration.subjectType(),
              registration.subjectId(),
              registration.jurisdictionCode(),
              "该辖区要求 **EPR 注册号**（包装法 / WEEE / 电池法），但没填。",
              "把 EPR 注册号填进本条登记；EPR 未注册的国家应限制刊登，那归 16.3.2.2 与 4 刊登。"));
    }
    if (registration.validUntil() == null) {
      gaps.add(
          new Gap(
              GAP_REGISTRATION_VALIDITY_UNKNOWN,
              SEVERITY_MEDIUM,
              registration.subjectType(),
              registration.subjectId(),
              registration.jurisdictionCode(),
              "**没记注册有效期** —— 判不了这条登记是不是已经过期。",
              "把注册有效期填上：没记有效期不等于没过期，只是判不了。"));
    } else if (registration.validUntil().isBefore(now)) {
      gaps.add(
          new Gap(
              GAP_REGISTRATION_EXPIRED,
              SEVERITY_HIGH,
              registration.subjectType(),
              registration.subjectId(),
              registration.jurisdictionCode(),
              "登记**已经过有效期** —— 过期登记和没有登记一样经不起稽查。",
              "尽快续期或重新申报，并把新的有效期回填到本条登记。"));
    } else if (registration.validUntil().isBefore(now.plusDays(EXPIRING_SOON_DAYS))) {
      long days = ChronoUnit.DAYS.between(now, registration.validUntil());
      gaps.add(
          new Gap(
              GAP_REGISTRATION_EXPIRING_SOON,
              SEVERITY_MEDIUM,
              registration.subjectType(),
              registration.subjectId(),
              registration.jurisdictionCode(),
              "登记将在 " + Math.max(days, 0) + " 天后过期。",
              "提前安排续期，别等过期了才想起来。"));
    }
    return gaps;
  }

  /**
   * 登记在某辖区上的**覆盖状态**（供覆盖视图用）。
   *
   * @return REGISTERED / EXPIRED / NOT_REGISTERED / MISSING
   */
  public static String coverageState(RegistrationFact registration, OffsetDateTime now) {
    if (registration == null || registration.archived()) {
      return "MISSING";
    }
    if (!registration.registered()) {
      return "NOT_REGISTERED";
    }
    if (registration.validUntil() != null && registration.validUntil().isBefore(now)) {
      return "EXPIRED";
    }
    return "REGISTERED";
  }

  /** 距失效还有几天（负数 = 已过期；null = 没记有效期）。 */
  public static Integer daysToExpiry(RegistrationFact registration, OffsetDateTime now) {
    if (registration == null || registration.validUntil() == null) {
      return null;
    }
    return (int) ChronoUnit.DAYS.between(now, registration.validUntil());
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
