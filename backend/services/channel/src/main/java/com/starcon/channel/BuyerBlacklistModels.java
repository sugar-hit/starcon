package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 16.5.1.1 黑名单的传输模型。
 *
 * <p>蓝图：「维护买家黑名单：买家标识、店铺、原因、风险等级、生效范围（仅本店铺/全店铺），
 * 下单时自动标记并可拦截。输入：黑名单录入（手工/自动，来自风控信号）；解除操作。
 * 输出：黑名单记录；订单拦截标记。关系：上游 = 16.5.1.2 评分、9.2 审单；下游 = 9.2.1.3 拦截、11 售后。
 * <b>黑名单应支持申诉解除</b>，永久不解除会让误判的正常买家永久损失。」
 *
 * <p>🔴 <b>本文件最重要的一条纪律：解除、申诉、拦截标记三样都是「事实」，不是「当前值」。</b>
 *
 * <ul>
 *   <li>{@code status} 是**由最新一条状态事件派生**的当前状态 —— 但**历史一条不少**，
 *       {@code events} 里能看到每一次流转（谁、何时、为什么）。</li>
 *   <li>{@code recommendedAction} 是**建议**，不是「已经拦了」—— 真的拦不拦单是 9.2.1.3 的事。</li>
 *   <li>本模块**没有**「直接取消订单」的入参：那会越过 9.2 / 9.2.1.3 直接改订单状态。</li>
 * </ul>
 */
public final class BuyerBlacklistModels {

  private BuyerBlacklistModels() {}

  // ================================================================ 能力声明

  /** 逐项能力声明。 */
  public record BuyerBlacklistCapability(
      String capabilityCode,
      String capabilityLabel,
      boolean available,
      String ownerModule,
      String impact) {}

  /** 逐个上游读面的可用性。读不到就**显式说读不到**，不返回空集合冒充「上游一个数据都没有」。 */
  public record BuyerBlacklistSourceState(
      String sourceCode, String sourceLabel, boolean available, String note) {}

  /** 能力声明。 */
  public record BuyerBlacklistCapabilities(
      List<BuyerBlacklistCapability> capabilities,
      List<BuyerBlacklistSourceState> sources,
      String subjectKeySemantics,
      String scopeSemantics,
      String releaseSemantics,
      String appealSemantics,
      String flagSemantics,
      String downstreamNote,
      String boundaryNote) {}

  // ================================================================ 登记

  /**
   * 一条黑名单登记（列表行与详情头共用）。
   *
   * @param status 当前状态（由最新一条状态事件派生）
   * @param statusEventType 产生当前状态的那条事件类型
   * @param riskLevel **当前**风险等级（升级后与登记时的等级可能不同）
   */
  public record BuyerBlacklistEntryView(
      UUID entryId,
      String buyerKey,
      String buyerName,
      String buyerPhone,
      String buyerEmail,
      String scope,
      String scopeLabel,
      String shopCode,
      String riskLevel,
      String riskLevelLabel,
      String reason,
      String source,
      String sourceLabel,
      String sourceRef,
      String status,
      String statusLabel,
      String statusEventType,
      String statusEventLabel,
      String statusActor,
      String statusReason,
      OffsetDateTime statusAt,
      OffsetDateTime registeredAt,
      String registeredBy,
      String note,
      OffsetDateTime createdAt,
      long appealCount,
      long openAppealCount,
      long flagCount) {}

  /** 汇总计数（不受分页影响）。 */
  public record BuyerBlacklistCounts(
      long total, long active, long released, long shopOnly, long allShops, long critical, long high) {}

  /** 黑名单清单（真分页 + 真筛选 + 与分页无关的汇总）。 */
  public record BuyerBlacklistEntryPage(
      List<BuyerBlacklistEntryView> items,
      int page,
      int size,
      long total,
      BuyerBlacklistCounts counts) {}

  /**
   * 手工录入黑名单。
   *
   * @param buyerName / buyerPhone / buyerEmail 收件人三件套 —— 主体键由它算出
   * @param scope 生效范围（SHOP_ONLY / ALL_SHOPS）
   * @param shopCode 仅本店铺时必填
   * @param riskLevel 风险等级
   * @param reason 原因（必填 —— 没有理由的黑名单客服无法向买家解释）
   */
  public record BuyerBlacklistRegisterInput(
      @NotBlank @Size(max = 120) String buyerName,
      @NotBlank @Size(max = 64) String buyerPhone,
      @NotBlank @Size(max = 254) String buyerEmail,
      @NotBlank @Pattern(regexp = "SHOP_ONLY|ALL_SHOPS") String scope,
      @Schema(nullable = true) @Size(max = 64) String shopCode,
      @NotBlank @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL") String riskLevel,
      @NotBlank @Size(max = 600) String reason,
      @Schema(nullable = true) @Size(max = 600) String note) {}

  /**
   * 自动录入黑名单（来自风控信号）。
   *
   * @param buyerKey 32 位十六进制主体键（信号自带的）
   * @param signalRef 触发它的信号引用（**必填** —— 自动来源必须可追溯）
   */
  public record BuyerBlacklistAutoInput(
      @NotBlank @Pattern(regexp = "^[0-9a-f]{32}$") String buyerKey,
      @NotBlank @Pattern(regexp = "SHOP_ONLY|ALL_SHOPS") String scope,
      @Schema(nullable = true) @Size(max = 64) String shopCode,
      @NotBlank @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL") String riskLevel,
      @NotBlank @Size(max = 600) String reason,
      @NotBlank @Size(max = 160) String signalRef,
      @Schema(nullable = true) @Size(max = 600) String note) {}

  /** 解除黑名单（**必须带理由** —— 没有理由的解除等于「悄悄放过」）。 */
  public record BuyerBlacklistReleaseInput(@NotBlank @Size(max = 600) String reason) {}

  // ================================================================ 申诉

  /** 提交申诉。 */
  public record BuyerBlacklistAppealInput(@NotBlank @Size(max = 800) String appealReason) {}

  /** 申诉裁决。 */
  public record BuyerBlacklistAppealDecisionInput(
      @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
      @NotBlank @Size(max = 800) String decisionReason) {}

  /** 申诉单。 */
  public record BuyerBlacklistAppealView(
      UUID appealId,
      UUID entryId,
      String appealReason,
      String submittedBy,
      OffsetDateTime submittedAt,
      String decision,
      String decidedBy,
      OffsetDateTime decidedAt,
      String decisionReason) {}

  // ================================================================ 状态流转

  /** 一条状态流转留痕。 */
  public record BuyerBlacklistStatusEventView(
      UUID eventId,
      int eventSeq,
      String eventType,
      String eventLabel,
      String fromStatus,
      String toStatus,
      String riskLevel,
      String actor,
      String reason,
      UUID appealId,
      OffsetDateTime occurredAt) {}

  /** 登记详情：头 + 状态流转留痕 + 申诉单。 */
  public record BuyerBlacklistEntryDetail(
      BuyerBlacklistEntryView entry,
      List<BuyerBlacklistStatusEventView> events,
      List<BuyerBlacklistAppealView> appeals) {}

  // ================================================================ 订单拦截标记

  /** 一条订单拦截标记（时点事实，append-only）。 */
  public record BuyerBlacklistOrderFlagView(
      UUID flagId,
      String buyerKey,
      String buyerName,
      String shopCode,
      String orderRef,
      UUID entryId,
      String matchedScope,
      String riskLevel,
      String recommendedAction,
      String recommendedActionLabel,
      OffsetDateTime flaggedAt) {}

  /** 订单拦截标记清单（真分页 + 真筛选）。 */
  public record BuyerBlacklistFlagPage(
      List<BuyerBlacklistOrderFlagView> items,
      int page,
      int size,
      long total,
      BuyerBlacklistFlagCounts counts) {}

  /** 拦截标记汇总计数（不受分页影响）。 */
  public record BuyerBlacklistFlagCounts(long total, long block, long review, long watch) {}

  // ================================================================ 下单检查

  /**
   * 下单时检查（**下单那一刻的判定**）。
   *
   * @param buyerName / buyerPhone / buyerEmail 收件人三件套
   * @param shopCode 下单的那家店（留空 ⇒ 按「全店铺」口径判）
   * @param orderRef 外部订单引用（原样落进拦截标记）
   */
  public record BuyerBlacklistCheckInput(
      @NotBlank @Size(max = 120) String buyerName,
      @NotBlank @Size(max = 64) String buyerPhone,
      @NotBlank @Size(max = 254) String buyerEmail,
      @Schema(nullable = true) @Size(max = 64) String shopCode,
      @Schema(nullable = true) @Size(max = 160) String orderRef) {}

  /**
   * 下单检查的结果。
   *
   * @param blacklisted 是否命中生效中的黑名单
   * @param recommendedAction **建议**动作（BLOCK / REVIEW / WATCH）；本模块不真的拦单
   * @param flagId 命中时落下的订单拦截标记 id（没命中则 null）
   * @param upstreamAvailable 9.2 审单读面是否读得到（**读不到 ⇒ 整个请求 503，不会走到这里**）
   * @param buyerKnown 9.2 审单里有没有这个人的画像（**没有 ≠ 没风险**）
   * @param latestRiskScore 16.5.1.2 最近一次评分（**没评过是 null，不是 0**）
   */
  public record BuyerBlacklistCheckResult(
      boolean blacklisted,
      String buyerKey,
      String buyerName,
      String shopCode,
      UUID entryId,
      String matchedScope,
      String matchedScopeLabel,
      String matchedShopCode,
      String riskLevel,
      String riskLevelLabel,
      String reason,
      String recommendedAction,
      String recommendedActionLabel,
      OffsetDateTime matchedAt,
      UUID flagId,
      boolean flagWritten,
      boolean upstreamAvailable,
      String upstreamNote,
      boolean buyerKnown,
      UUID latestScoreId,
      @Schema(nullable = true) BigDecimal latestRiskScore,
      @Schema(nullable = true) String latestEvaluationStatus,
      @Schema(nullable = true) String latestRecommendedAction) {}
}
