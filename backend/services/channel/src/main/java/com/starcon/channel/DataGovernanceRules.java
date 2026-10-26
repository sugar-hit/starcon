package com.starcon.channel;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 16.6.2.1 数据出境与留存的判定规则（**纯函数**，不碰数据库）。
 *
 * <p>蓝图加粗句：「**财务数据有法定留存期，PII 有 GDPR 删除权，两者冲突时按财务留存优先**
 * ——实体目录 §5 已明确这条边界。」
 *
 * <p>🔴 本类把这条边界写成一组**可判定**的规则，而不是一句说明：
 *
 * <ol>
 *   <li><b>两类留存期分开</b>：{@code STANDARD}（普通留存期）与 {@code FINANCIAL}（法定财务留存期）
 *       是两条不同的策略，不是同一个期限加一个 flag。</li>
 *   <li><b>生效留存期取长</b>：{@code effective = max(标准期, 财务期)} ——
 *       法定留存期是**下限**，任何更短的普通留存期都不能把它压下去。</li>
 *   <li><b>冲突时财务留存期优先</b>：两条都在时，
 *       {@code resolution = FINANCIAL_PREVAILS}（财务期更长）或
 *       {@code STANDARD_APPLIES}（普通期更长 —— 反正更长的那个已经在保护数据）。</li>
 *   <li><b>删除被推迟而不是被拒绝</b>：法定留存期没走完 ⇒
 *       {@code deletionBlocked = true}，并给出 {@code deletionBlockedUntil}
 *       （财务期走完的那一天）。被遗忘权在这一天之前**不能真的删数据**。</li>
 *   <li><b>判不了 ≠ 默认放行</b>：一个数据域**没有任何留存策略** ⇒
 *       {@code conflictKind = NO_CONFLICT} 且 {@code effectiveRetentionDays = null}，
 *       服务层据此拒绝执行删除（422），而不是"没规矩就随便删"。</li>
 * </ol>
 *
 * <p>🔴 财务期走完的那一天怎么算：{@code 财务策略生效日 + 财务留存天数}。
 * 这是**保守**口径 —— 在这一天之前，本模块**无法证明**某条记录已过法定期限
 * （记录创建时刻在别的服务库里），所以保守地不许删。
 * 真实记录级年龄由下游数据清理任务按业务数据判定。
 */
public final class DataGovernanceRules {

  private DataGovernanceRules() {}

  // ================================================================ 受控值域

  /** 普通留存期。 */
  public static final String CLASS_STANDARD = "STANDARD";

  /** 法定（财务）留存期。 */
  public static final String CLASS_FINANCIAL = "FINANCIAL";

  /** 冲突形态：没有策略。 */
  public static final String KIND_NO_CONFLICT = "NO_CONFLICT";

  /** 冲突形态：这个数据域一条留存策略都没登记（**判不了**，不是"没冲突"）。 */
  public static final String KIND_NO_POLICY = "NO_POLICY";

  /** 冲突形态：财务期更长。 */
  public static final String KIND_FINANCIAL_LONGER = "FINANCIAL_LONGER";

  /** 冲突形态：普通期更长。 */
  public static final String KIND_STANDARD_LONGER = "STANDARD_LONGER";

  /** 冲突形态：只有普通期。 */
  public static final String KIND_STANDARD_ONLY = "STANDARD_ONLY";

  /** 冲突形态：只有财务期。 */
  public static final String KIND_FINANCIAL_ONLY = "FINANCIAL_ONLY";

  /** 裁决结果：财务留存优先。 */
  public static final String RESOLUTION_FINANCIAL_PREVAILS = "FINANCIAL_PREVAILS";

  /** 裁决结果：按普通留存期。 */
  public static final String RESOLUTION_STANDARD_APPLIES = "STANDARD_APPLIES";

  /** 裁决结果：无冲突。 */
  public static final String RESOLUTION_NO_CONFLICT = "NO_CONFLICT";

  /** 留存期类别的人话标签。 */
  public static String retentionClassLabel(String retentionClass) {
    if (CLASS_FINANCIAL.equals(retentionClass)) {
      return "法定（财务）留存期";
    }
    if (CLASS_STANDARD.equals(retentionClass)) {
      return "普通留存期";
    }
    return retentionClass == null || retentionClass.isEmpty() ? "未登记" : retentionClass;
  }

  /** 起算口径的人话标签。 */
  public static String retentionTriggerLabel(String trigger) {
    if ("CREATION".equals(trigger)) {
      return "按数据创建时间起算";
    }
    if ("CLOSURE".equals(trigger)) {
      return "按业务结束时间起算";
    }
    return trigger == null || trigger.isEmpty() ? "未登记" : trigger;
  }

  /** 冲突形态的人话标签。 */
  public static String conflictKindLabel(String kind) {
    switch (kind == null ? "" : kind) {
      case KIND_NO_POLICY:
        return "没登记留存策略（判不了）";
      case KIND_FINANCIAL_LONGER:
        return "财务（法定）留存期更长";
      case KIND_STANDARD_LONGER:
        return "普通留存期更长";
      case KIND_STANDARD_ONLY:
        return "只有普通留存期";
      case KIND_FINANCIAL_ONLY:
        return "只有财务（法定）留存期";
      case KIND_NO_CONFLICT:
        return "没有留存策略";
      default:
        return kind == null || kind.isEmpty() ? "未登记" : kind;
    }
  }

  /** 裁决结果的人话标签。 */
  public static String resolutionLabel(String resolution) {
    switch (resolution == null ? "" : resolution) {
      case RESOLUTION_FINANCIAL_PREVAILS:
        return "财务留存优先";
      case RESOLUTION_STANDARD_APPLIES:
        return "按普通留存期";
      case RESOLUTION_NO_CONFLICT:
        return "无冲突";
      default:
        return resolution == null || resolution.isEmpty() ? "未裁决" : resolution;
    }
  }

  /** 合法性基础的人话标签。 */
  public static String legalBasisLabel(String legalBasis) {
    switch (legalBasis == null ? "" : legalBasis) {
      case "CONSENT":
        return "同意（GDPR 6(1)(a)）";
      case "CONTRACT":
        return "履行合同所必需（GDPR 6(1)(b)）";
      case "LEGAL_OBLIGATION":
        return "法定义务（GDPR 6(1)(c)）";
      case "LEGITIMATE_INTEREST":
        return "正当利益（GDPR 6(1)(f)）";
      default:
        return legalBasis == null || legalBasis.isEmpty() ? "未登记" : legalBasis;
    }
  }

  /** 传输状态的人话标签。 */
  public static String transferStatusLabel(String status) {
    switch (status == null ? "" : status) {
      case "ACTIVE":
        return "进行中";
      case "SUSPENDED":
        return "已暂停";
      case "ENDED":
        return "已终止";
      default:
        return status == null || status.isEmpty() ? "未知" : status;
    }
  }

  /** 删除请求状态的人话标签。 */
  public static String deletionStatusLabel(String status) {
    switch (status == null ? "" : status) {
      case "PENDING":
        return "待审批";
      case "APPROVED":
        return "已批准（未执行）";
      case "DEFERRED":
        return "已推迟（财务留存优先）";
      case "REJECTED":
        return "已驳回";
      case "EXECUTED":
        return "已执行（已派发清理工单）";
      case "CANCELLED":
        return "已取消";
      default:
        return status == null || status.isEmpty() ? "未知" : status;
    }
  }

  /** 请求类型的人话标签。 */
  public static String requestTypeLabel(String requestType) {
    if ("FORGETTING_RIGHT".equals(requestType)) {
      return "被遗忘权（GDPR 第 17 条）";
    }
    if ("CLEANUP".equals(requestType)) {
      return "定期清理申请";
    }
    return requestType == null || requestType.isEmpty() ? "未知" : requestType;
  }

  /** 清理工单触发来源的人话标签。 */
  public static String triggerKindLabel(String triggerKind) {
    switch (triggerKind == null ? "" : triggerKind) {
      case "RETENTION_EXPIRY":
        return "留存期到期";
      case "DELETION_REQUEST":
        return "删除请求";
      case "MANUAL":
        return "人工发起";
      default:
        return triggerKind == null || triggerKind.isEmpty() ? "未知" : triggerKind;
    }
  }

  /** 清理工单状态的人话标签。 */
  public static String cleanupStatusLabel(String status) {
    switch (status == null ? "" : status) {
      case "PENDING":
        return "待处理";
      case "IN_PROGRESS":
        return "处理中";
      case "COMPLETED":
        return "已完成";
      case "PARTIAL":
        return "部分完成";
      case "FAILED":
        return "失败";
      case "CANCELLED":
        return "已取消";
      default:
        return status == null || status.isEmpty() ? "未知" : status;
    }
  }

  /** 执行结果的人话标签。 */
  public static String cleanupResultLabel(String result) {
    switch (result == null ? "" : result) {
      case "SUCCESS":
        return "全部成功";
      case "PARTIAL":
        return "部分成功";
      case "FAILED":
        return "失败";
      case "CANCELLED":
        return "已取消";
      default:
        return result == null || result.isEmpty() ? "未知" : result;
    }
  }

  /** 执行动作的人话标签。 */
  public static String cleanupActionLabel(String action) {
    switch (action == null ? "" : action) {
      case "EXECUTE":
        return "执行";
      case "RETRY":
        return "重试";
      case "CANCEL":
        return "取消";
      default:
        return action == null || action.isEmpty() ? "未知" : action;
    }
  }

  // ================================================================ 裁决

  /** 一条生效中的留存策略（本类只看得到裁决需要的这几项）。 */
  public record PolicyFact(
      UUID policyId,
      String retentionClass,
      BigDecimal retentionDays,
      String legalBasis,
      OffsetDateTime effectiveFrom) {}

  /** 裁决结果。 */
  public record Adjudication(
      PolicyFact standardPolicy,
      PolicyFact financialPolicy,
      String conflictKind,
      String resolution,
      BigDecimal effectiveRetentionDays,
      /** 法定留存期走完的那一天（没有财务策略时为 null）。 */
      OffsetDateTime financialMaturityAt,
      /** 现在能不能删（true = 被法定留存期挡住）。 */
      boolean deletionBlocked,
      String rationale) {}

  /**
   * 裁决一个数据域的留存期。
   *
   * @param policies 该数据域当前生效的策略（同一（域 × 类别）只有一条 ACTIVE，
   *     但这里仍按"取更长的一条"处理，防御性地不让两条并存时判定漂移）
   * @param asOf 判定时刻
   */
  public static Adjudication adjudicate(List<PolicyFact> policies, OffsetDateTime asOf) {
    PolicyFact standard = null;
    PolicyFact financial = null;
    if (policies != null) {
      for (PolicyFact policy : policies) {
        if (policy == null || policy.retentionDays() == null) {
          continue;
        }
        if (CLASS_FINANCIAL.equals(policy.retentionClass())) {
          if (financial == null || policy.retentionDays().compareTo(financial.retentionDays()) > 0) {
            financial = policy;
          }
        } else if (standard == null
            || policy.retentionDays().compareTo(standard.retentionDays()) > 0) {
          standard = policy;
        }
      }
    }

    if (standard == null && financial == null) {
      return new Adjudication(
          null,
          null,
          KIND_NO_POLICY,
          RESOLUTION_NO_CONFLICT,
          null,
          null,
          false,
          "这个数据域**没有登记任何留存策略** ⇒ 判不了能不能删。"
              + "删数据之前先把留存策略登记进来（判不了 ≠ 默认放行）。");
    }

    if (standard == null) {
      OffsetDateTime maturity = maturityAt(financial);
      boolean blocked = maturity != null && maturity.isAfter(asOf);
      return new Adjudication(
          null,
          financial,
          KIND_FINANCIAL_ONLY,
          RESOLUTION_FINANCIAL_PREVAILS,
          financial.retentionDays(),
          maturity,
          blocked,
          "这个数据域只有法定（财务）留存期 "
              + plain(financial.retentionDays())
              + " 天 ⇒ 按财务留存优先，生效留存期取 "
              + plain(financial.retentionDays())
              + " 天。"
              + (blocked
                  ? "法定留存期到 " + maturity.toLocalDate() + " 才走完 ⇒ 在那之前删除被**推迟**（不是被拒绝）。"
                  : "法定留存期已走完 ⇒ 不再阻挡删除。"));
    }

    if (financial == null) {
      return new Adjudication(
          standard,
          null,
          KIND_STANDARD_ONLY,
          RESOLUTION_STANDARD_APPLIES,
          standard.retentionDays(),
          null,
          false,
          "这个数据域只有普通留存期 "
              + plain(standard.retentionDays())
              + " 天 ⇒ 按普通留存期执行（没有法定留存期挡着）。");
    }

    // 两条都在 —— 这才是真正的「冲突对」。
    int compared = financial.retentionDays().compareTo(standard.retentionDays());
    boolean financialLonger = compared > 0;
    boolean equal = compared == 0;
    BigDecimal effective =
        financialLonger || equal ? financial.retentionDays() : standard.retentionDays();
    OffsetDateTime maturity = maturityAt(financial);
    boolean blocked = maturity != null && maturity.isAfter(asOf);
    String kind = equal ? KIND_NO_CONFLICT : (financialLonger ? KIND_FINANCIAL_LONGER : KIND_STANDARD_LONGER);
    String resolution =
        financialLonger || equal ? RESOLUTION_FINANCIAL_PREVAILS : RESOLUTION_STANDARD_APPLIES;

    String rationale =
        "冲突对：普通留存期 "
            + plain(standard.retentionDays())
            + " 天"
            + (standard.legalBasis() == null || standard.legalBasis().isEmpty()
                ? ""
                : "（依据：" + standard.legalBasis() + "）")
            + " vs 法定（财务）留存期 "
            + plain(financial.retentionDays())
            + " 天"
            + (financial.legalBasis() == null || financial.legalBasis().isEmpty()
                ? ""
                : "（依据：" + financial.legalBasis() + "）")
            + "。裁决："
            + resolutionLabel(resolution)
            + " ⇒ 生效留存期 "
            + plain(effective)
            + " 天（取两者更长者，法定留存期是下限）。"
            + (blocked
                ? "法定留存期到 " + maturity.toLocalDate() + " 才走完 ⇒ 在那之前删除请求被**推迟**。"
                : "法定留存期已走完 ⇒ 不再阻挡删除。");
    return new Adjudication(
        standard, financial, kind, resolution, effective, maturity, blocked, rationale);
  }

  /** 财务（法定）留存期走完的那一天 = 策略生效日 + 留存天数。 */
  public static OffsetDateTime maturityAt(PolicyFact financial) {
    if (financial == null || financial.effectiveFrom() == null || financial.retentionDays() == null) {
      return null;
    }
    return financial.effectiveFrom().plusDays(financial.retentionDays().longValueExact());
  }

  /**
   * 最早可清理日 = **裁决结果所依据的那条策略**的生效日 + 生效留存期。
   *
   * <p>🔴 两条期限一样长时取财务那条 —— 那正是「财务留存优先」的形状：
   * 同样长的期限内，法定留存期是更硬的那一条。
   */
  public static OffsetDateTime eligibleAt(Adjudication adjudication) {
    if (adjudication == null || adjudication.effectiveRetentionDays() == null) {
      return null;
    }
    PolicyFact governing = null;
    if (adjudication.financialPolicy() != null
        && adjudication.financialPolicy().retentionDays().compareTo(adjudication.effectiveRetentionDays())
            == 0) {
      governing = adjudication.financialPolicy();
    } else if (adjudication.standardPolicy() != null) {
      governing = adjudication.standardPolicy();
    } else {
      governing = adjudication.financialPolicy();
    }
    if (governing == null || governing.effectiveFrom() == null) {
      return null;
    }
    return governing.effectiveFrom().plusDays(adjudication.effectiveRetentionDays().longValueExact());
  }

  /** 天数的人话写法（去掉多余的 .0）。 */
  public static String plain(BigDecimal days) {
    if (days == null) {
      return "—";
    }
    return days.stripTrailingZeros().toPlainString();
  }
}
