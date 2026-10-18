package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.3.2.1 换货授权的请求 / 响应模型。
 *
 * <p>命名与 {@code AftersalesCaseModels}（11.1.1.1）、{@code RefundStatusModels}（11.3.1.2）同构，
 * 页面路由与权限码沿用售后族：{@code /aftersales/exchanges}、{@code trade.aftersales.read|write}。
 *
 * <p>⚠️ 汇总一律用 <b>list 桶</b>而不是 {@code Map<String, BigDecimal>}：后者经 springdoc
 * 只生成空的 {@code { }}，前端拿到 unknown 而非 number（11.3.1.2 被这条真实坑过，
 * 契约从 {@code { }} 变成 {@code Array<...>} 之后才 typecheck 通过）。本模块照抄那个决定。
 */
public final class ExchangeAuthorizationModels {

  private ExchangeAuthorizationModels() {}

  // ================================================================ 能力声明

  /**
   * 换货授权能力声明。
   *
   * <p>前端据此决定「哪些按钮可点」，而不是让用户点一个注定被拒的按钮 ——
   * 让操作员点完再收一个 409 等于告诉他系统坏了，而真相是这条路径本就不该存在。
   */
  public record ExchangeCapabilities(
      boolean caseDelivered,
      boolean reservationApiDelivered,
      boolean skuCatalogDelivered,
      boolean sellableApiDelivered,
      boolean outboundHandoffDelivered,
      String outboundHandoffOwnerModule,
      boolean oldGoodsReturnDelivered,
      String oldGoodsReturnOwnerModule,
      List<String> statuses,
      List<String> statusLabels,
      List<String> decisions,
      List<String> decisionLabels,
      List<String> allowedNextActions,
      String note,
      List<String> hardRules) {}

  // ================================================================ 查询

  /**
   * 列表查询。所有筛选项可空，空即不过滤。
   *
   * <p>id 筛选项刻意用 {@code String} 而不是 {@code UUID}：控制器把它们原样透传，
   * 由服务层在拼 SQL 时统一 {@code ?::uuid}。这样非法 id 会在绑参那一刻被数据库拒，
   * 而不是先在 Java 里 parse 抛一个与租户无关的 NumberFormatException。
   */
  public record ExchangeQuery(
      @Schema(nullable = true) String caseId,
      @Schema(nullable = true) String orderId,
      @Schema(nullable = true) String skuId,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String decision,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size) {}

  /** 换货授权单列表行。 */
  public record ExchangeRow(
      UUID id,
      String authorizationNo,
      UUID caseId,
      String caseNo,
      UUID orderId,
      String orderNo,
      String status,
      String statusLabel,
      String decision,
      String decisionLabel,
      String requestedQuantity,
      String authorizedQuantity,
      int replacementSkuCount,
      int lineCount,
      int activeReservationCount,
      int releasedReservationCount,
      int pendingOutboundDemandCount,
      int waitingReturnCount,
      String reasonNote,
      UUID authorizedBy,
      OffsetDateTime authorizedAt,
      UUID cancelledBy,
      OffsetDateTime cancelledAt,
      String cancelReason,
      int version,
      OffsetDateTime createdAt) {}

  /**
   * 分页结果。
   *
   * <p>{@code total} 是**当前筛选下的真实总数**（独立 COUNT），不是本页行数。
   */
  public record ExchangePage(List<ExchangeRow> items, long total, int page, int size, ExchangeSummary summary) {}

  /** 列表顶部汇总。数量口径与列表行同源，翻页时它不变。 */
  public record ExchangeSummary(
      long totalCount,
      long activeCount,
      long cancelledCount,
      long approvedCount,
      long partiallyApprovedCount,
      String totalRequestedQuantity,
      String totalAuthorizedQuantity,
      String totalPendingOutboundQuantity,
      String totalWaitingReturnQuantity) {}

  // ================================================================ 明细视图

  /** 逐行明细（退什么 → 换成什么 → 换几件 → 预占在哪一笔）。 */
  public record ExchangeLineView(
      UUID id,
      UUID caseLineId,
      UUID originalSkuId,
      String originalSkuCode,
      String originalSkuName,
      String originalQuantity,
      UUID replacementSkuId,
      String replacementSkuCode,
      String replacementSkuName,
      String replacementReason,
      UUID warehouseId,
      String warehouseCode,
      UUID legalEntityId,
      String requestedQuantity,
      String authorizedQuantity,
      String reservedQuantity,
      UUID reservationId,
      String reservationNo,
      String reservationStatus,
      UUID outboundDemandId,
      String outboundDemandNo,
      String outboundDemandStatus,
      UUID returnRequirementId,
      String returnRequirementStatus,
      String waitingReturnQuantity) {}

  /** 详情：头 + 各行 + 可执行动作。 */
  public record ExchangeDetail(ExchangeRow authorization, List<ExchangeLineView> lines, List<String> allowedActions) {}

  /**
   * 可授权换货售后单下拉里的一条**商品行**。
   *
   * <p>刻意把商品行随单一起下发，而不是让前端再发一次请求去查售后单详情：
   * 授权对话框要同时显示「退的是哪一行、原来是什么 SKU、退几件」，
   * 少一次往返就少一次「列表已加载但明细还在飞」的中间态。
   */
  public record ExchangeCaseLineOption(
      UUID caseLineId,
      UUID orderLineId,
      UUID skuId,
      String skuCode,
      String skuName,
      String quantity) {}

  /** 下拉项：可授权的换货售后单（11.1.1.1/11.1.1.2 已交付，本模块只读）。 */
  public record ExchangeCaseOption(
      UUID caseId,
      String caseNo,
      UUID orderId,
      String orderNo,
      String status,
      String statusLabel,
      String reasonCode,
      String requestedQuantity,
      List<ExchangeCaseLineOption> lines) {}

  /**
   * 筛选与下拉项。
   *
   * <p>{@code statusLabels} / {@code decisionLabels} 与各自的码**同序**：
   * 界面直接按下标取标签，不在前端另写一份词表 ——
   * 前端硬编码一份就等于有两个真相，后端改了一个值界面上还讲着旧话。
   */
  public record ExchangeFilterOptions(
      List<String> statuses,
      List<String> statusLabels,
      List<String> decisions,
      List<String> decisionLabels,
      List<ExchangeCaseOption> exchangeCases,
      long exchangeCaseCount) {}

  // ================================================================ 请求

  /** 一行换货申请：换哪条售后行、换成哪个 SKU、申请几件。 */
  public record ExchangeLineRequest(
      @jakarta.validation.constraints.NotNull UUID caseLineId,
      @jakarta.validation.constraints.NotNull UUID replacementSkuId,
      @Schema(nullable = true) BigDecimal requestedQuantity,
      @Schema(nullable = true)
          @jakarta.validation.constraints.Size(max = 200)
          String replacementReason) {}

  /**
   * 换货授权请求。
   *
   * @param allowPartial true = 替换货库存不足时按可售量少批（结论落 PARTIALLY_APPROVED）；
   *     false（默认）= 有一行凑不齐就整笔拒绝，绝不悄悄少发
   * @param lines 至少一行；同一替换 SKU 出现在两行会被服务层合并（数量相加）
   */
  public record ExchangeAuthorizeRequest(
      @jakarta.validation.constraints.NotNull UUID caseId,
      @jakarta.validation.constraints.NotNull UUID warehouseId,
      @jakarta.validation.constraints.NotNull UUID legalEntityId,
      @jakarta.validation.constraints.NotEmpty List<@jakarta.validation.Valid ExchangeLineRequest> lines,
      @Schema(nullable = true) Boolean allowPartial,
      @Schema(nullable = true)
          @jakarta.validation.constraints.Size(max = 1000)
          String reasonNote) {}

  /**
   * 撤销换货。
   *
   * @param expectedVersion 蓝图输入句点名的乐观锁版本。不匹配一律 409 ——
   *     两个人同时点「撤销」时后到的那个必须被拒，而不是两个人各撤一次、各释放一次预占
   */
  public record ExchangeCancelRequest(
      @jakarta.validation.constraints.NotNull Integer expectedVersion,
      @Schema(nullable = true)
          @jakarta.validation.constraints.Size(max = 1000)
          String reason) {}
}
