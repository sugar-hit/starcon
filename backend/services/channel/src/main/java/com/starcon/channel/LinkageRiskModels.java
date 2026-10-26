package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 16.5.2.2 关联风险的传输模型。
 *
 * <p>蓝图：「识别账号关联风险：同一支付方式/收款账户/设备/IP/地址/法人关联的店铺，
 * 输出关联图谱与风险提示，提示避免触发平台关联处罚。输入：店铺与支付/设备/地址/法人关系数据；
 * 平台政策要求。输出：关联图谱 + 风险提示 + 整改建议。关系：上游 = 2 店铺、12.7.1 资金账户；
 * 下游 = 1 工作台预警。<b>关联封号是跨境多店经营的头号风险</b>：官方 API 授权是规避关联的关键，
 * 本系统只做官方 API，绝不提供任何模拟登录/指纹浏览器能力。」
 *
 * <p>🔴 <b>本文件最重要的三条纪律：</b>
 *
 * <ol>
 *   <li><b>「读不到」与「没有」是两件事。</b>法人名称读不到（identity 不可达）时返回
 *       {@code legalEntityNameAvailable=false} + {@code null} 名称，
 *       <b>绝不返回空字符串冒充「这个法人没有名字」</b>；支付方式 / 收款账户维度
 *       在能力声明里是 {@code available=false} + {@code ownerModule=12.7.1.1}。</li>
 *   <li><b>「没有关联」与「还没判过」是两件事。</b>没有登记策略时图谱返回
 *       {@code known=false} + 说明，<b>不是</b>一个空的「一切正常」的图。</li>
 *   <li><b>图谱是实算的，不是填出来的。</b>节点来自 channel 的真实 {@code shop} 表，
 *       法人边来自真实的 {@code shop.legal_entity_id}，其余边来自租户登记的真实观测。</li>
 * </ol>
 */
public final class LinkageRiskModels {

  private LinkageRiskModels() {}

  // ================================================================ 能力声明

  /**
   * 单个能力面的可用性。
   *
   * @param available 现在**做得到**吗（false ⇒ 该面在本仓没有事实来源）
   * @param autoDiscoverable 事实能**自动发现**吗（false ⇒ 事实靠租户登记）
   * @param source 事实来自哪里
   * @param ownerModule 缺的那部分归哪个模块
   * @param impact 做得到 / 做不到分别意味着什么
   */
  public record LinkageRiskCapability(
      String code,
      String label,
      boolean available,
      boolean autoDiscoverable,
      String source,
      String ownerModule,
      String impact) {}

  /** 能力声明：逐维度 + 八条口径说明。 */
  public record LinkageRiskCapabilities(
      List<LinkageRiskCapability> capabilities,
      String shopSourceNote,
      String dimensionNote,
      String registerNote,
      String graphNote,
      String alertNote,
      String remediationNote,
      String officialApiNote,
      String downstreamNote,
      String financeNote) {}

  // ================================================================ 策略

  /** 逐维度的政策口径。 */
  public record LinkageRiskPolicyDimensionView(
      UUID dimensionId,
      String dimension,
      String dimensionLabel,
      String sensitivity,
      int minShopCount,
      String policyBasis,
      boolean enabled) {}

  /** 关联风险策略（一租户一条）。 */
  public record LinkageRiskPolicyView(
      UUID policyId,
      String policyName,
      String status,
      int minSharedShopCount,
      String evaluationScope,
      List<LinkageRiskPolicyDimensionView> dimensions,
      long version,
      /** 后端没登记过策略时是 false（**后端不预填任何默认阈值**）。 */
      boolean configured,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 逐维度口径入参。 */
  public record LinkageRiskPolicyDimensionInput(
      @NotNull @Pattern(regexp = "LEGAL_ENTITY|DEVICE|NETWORK_IP|ADDRESS|OTHER") String dimension,
      @NotNull @Pattern(regexp = "HIGH|MEDIUM|LOW") String sensitivity,
      @NotNull @Min(2) @Max(50) Integer minShopCount,
      @Size(max = 400) String policyBasis,
      Boolean enabled) {}

  /** 策略登记 / 修改（upsert）。🔴 阈值必须由租户显式给，不给默认值。 */
  public record LinkageRiskPolicyInput(
      @NotBlank @Size(max = 120) String policyName,
      @NotNull @Pattern(regexp = "DRAFT|ACTIVE") String status,
      @NotNull @Min(2) @Max(50) Integer minSharedShopCount,
      @NotNull @Pattern(regexp = "TENANT|PLATFORM") String evaluationScope,
      @NotNull @Size(min = 1, max = 20) List<@Valid LinkageRiskPolicyDimensionInput> dimensions) {}

  // ================================================================ 店铺（节点来源）

  /**
   * 图谱节点（店铺）。
   *
   * @param legalEntityId 绑定的法人 id（来自真实的 {@code shop.legal_entity_id}）
   * @param legalEntityName 法人名称（来自 17 identity；**读不到时是 null**）
   * @param legalEntityNameAvailable 法人名称**读到了没有**（false ⇒ 显示「读不到」，
   *     **不是**「没有法人」）
   */
  public record LinkageRiskShopView(
      UUID shopId,
      String code,
      String name,
      String platformCode,
      String platformName,
      String status,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) String legalEntityName,
      boolean legalEntityNameAvailable,
      int linkCount,
      List<String> dimensions) {}

  /** 店铺列表（真分页 + 真搜索）。 */
  public record LinkageRiskShopPage(
      List<LinkageRiskShopView> items, int page, int size, long total) {}

  // ================================================================ 关联标识登记

  /** 登记在某个标识上的店铺。 */
  public record LinkageRiskIdentifierShopView(
      UUID shopId, String shopCode, String shopName, String platformCode) {}

  /** 关联标识（共享因子）。 */
  public record LinkageRiskIdentifierView(
      UUID identifierId,
      String dimension,
      String dimensionLabel,
      String identifierKey,
      String displayLabel,
      String status,
      int shopCount,
      List<LinkageRiskIdentifierShopView> shops,
      String note,
      long version,
      OffsetDateTime firstObservedAt,
      OffsetDateTime lastObservedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 关联标识列表（真分页 + 真筛选）。 */
  public record LinkageRiskIdentifierPage(
      List<LinkageRiskIdentifierView> items, int page, int size, long total) {}

  /** 关联标识登记入参。🔴 只有可登记维度（设备 / 网络 IP / 地址 / 其他）能进。 */
  public record LinkageRiskIdentifierInput(
      @NotNull @Pattern(regexp = "DEVICE|NETWORK_IP|ADDRESS|OTHER") String dimension,
      @NotBlank @Size(max = 200) String identifierKey,
      @Size(max = 200) String displayLabel,
      @Size(max = 600) String note,
      @Schema(nullable = true) OffsetDateTime firstObservedAt) {}

  /** 关联标识修改入参（标签 / 状态 / 备注）。 */
  public record LinkageRiskIdentifierUpdateInput(
      @Size(max = 200) String displayLabel,
      @NotNull @Pattern(regexp = "ACTIVE|RETIRED") String status,
      @Size(max = 600) String note,
      @Schema(nullable = true) Long version) {}

  /** 把一家店铺挂到某个标识上（登记一条关联事实）。 */
  public record LinkageRiskShopLinkInput(
      @NotNull UUID shopId,
      @NotNull @Pattern(regexp = "MANUAL|PLATFORM_NOTICE|OPS_REPORT") String source,
      @Size(max = 600) String note,
      @Schema(nullable = true) OffsetDateTime observedAt) {}

  // ================================================================ 关联图谱

  /** 图谱节点。 */
  public record LinkageRiskGraphNode(
      UUID shopId,
      String shopCode,
      String shopName,
      String platformCode,
      int linkCount,
      List<String> dimensions) {}

  /**
   * 图谱的一条边（一个共享因子）。
   *
   * @param factorKey 判定键（法人维度的是法人 id）
   * @param factorLabel 展示标签（法人维度可能是「法人名称读不到」）
   * @param autoDiscovered 这个因子是不是自动发现的（法人维度 true，其余 false）
   */
  public record LinkageRiskGraphLink(
      String dimension,
      String dimensionLabel,
      String factorKey,
      String factorLabel,
      String platformCode,
      int shopCount,
      List<UUID> shopIds,
      boolean autoDiscovered,
      String sensitivity) {}

  /** 图谱里的一个关联组（连通分量）。 */
  public record LinkageRiskGraphCluster(
      String clusterKey,
      List<UUID> shopIds,
      int shopCount,
      List<String> dimensions,
      String severity,
      String severityLabel,
      String riskLevel,
      String riskLevelLabel,
      boolean crossPlatform,
      String summary) {}

  /**
   * 关联图谱。
   *
   * @param known 图谱**算出来没有**（false ⇒ 还没登记策略，图谱尚未启用 ——
   *     **不是**「算过了，没有关联」）
   */
  public record LinkageRiskGraph(
      boolean known,
      String note,
      int shopCount,
      int linkCount,
      int clusterCount,
      List<LinkageRiskGraphNode> nodes,
      List<LinkageRiskGraphLink> links,
      List<LinkageRiskGraphCluster> clusters) {}

  // ================================================================ 风险提示

  /** 提示涉及的店铺。 */
  public record LinkageRiskAlertShopView(
      UUID shopId, String shopCode, String shopName, String platformCode) {}

  /** 提示的处置留痕。 */
  public record LinkageRiskAlertActionView(
      UUID actionId,
      String action,
      String fromStatus,
      String toStatus,
      String actorName,
      String reason,
      OffsetDateTime occurredAt) {}

  /** 整改建议。 */
  public record LinkageRiskRemediationView(
      UUID remediationId,
      String recommendationCode,
      String recommendationLabel,
      String dimension,
      String title,
      String detail,
      String priority,
      String status,
      String decisionNote,
      @Schema(nullable = true) OffsetDateTime decidedAt,
      long version) {}

  /** 一条关联风险提示。 */
  public record LinkageRiskAlertView(
      UUID alertId,
      UUID clusterId,
      String clusterKey,
      String alertType,
      String severity,
      String severityLabel,
      String status,
      String statusLabel,
      int shopCount,
      List<String> dimensions,
      String title,
      String summary,
      OffsetDateTime detectedAt,
      @Schema(nullable = true) UUID acknowledgedBy,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      String acknowledgeReason,
      @Schema(nullable = true) UUID resolvedBy,
      @Schema(nullable = true) OffsetDateTime resolvedAt,
      String resolutionNote,
      @Schema(nullable = true) UUID dismissedBy,
      @Schema(nullable = true) OffsetDateTime dismissedAt,
      String dismissReason,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      List<LinkageRiskAlertShopView> shops,
      List<LinkageRiskAlertActionView> actions,
      List<LinkageRiskRemediationView> remediations) {}

  /** 风险提示列表（真分页 + 真筛选 + 汇总）。 */
  public record LinkageRiskAlertPage(
      List<LinkageRiskAlertView> items,
      int page,
      int size,
      long total,
      long openCount,
      long acknowledgedCount,
      long remediatedCount,
      long dismissedCount,
      long highCount) {}

  /** 处置入参（🔴 处置理由必填）。 */
  public record LinkageRiskAlertActionInput(@NotBlank @Size(max = 600) String reason) {}

  /** 整改建议状态入参。 */
  public record LinkageRiskRemediationStatusInput(
      @NotNull @Pattern(regexp = "ACCEPTED|DISMISSED|DONE") String status,
      @Size(max = 600) String note) {}

  /** 整改建议列表（真分页 + 真筛选）。 */
  public record LinkageRiskRemediationPage(
      List<LinkageRiskRemediationView> items, int page, int size, long total) {}

  // ================================================================ 扫描

  /** 一次扫描的结果。 */
  public record LinkageRiskScanResult(
      boolean known,
      String note,
      int shopsScanned,
      int linksFound,
      int clustersFound,
      int clustersCreated,
      int clustersUpdated,
      int alertsCreated,
      int alertsUpdated,
      int remediationsCreated,
      List<LinkageRiskGraphCluster> clusters) {}
}
