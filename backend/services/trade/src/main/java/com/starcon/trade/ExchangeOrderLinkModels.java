package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.3.2.2 换货新单的请求 / 响应模型。
 *
 * <p>命名与 {@code ExchangeAuthorizationModels}（11.3.2.1）、{@code RefundStatusModels}（11.3.1.2）
 * 同构，页面路由与权限码沿用售后族：{@code /aftersales/exchange-orders}、
 * {@code trade.aftersales.read|write}。
 *
 * <p>⚠️ 汇总一律用 <b>list 桶</b>而不是 {@code Map<String, BigDecimal>}：后者经 springdoc
 * 只生成空的 {@code { }}，前端拿到 unknown 而非 number（11.3.1.2 被这条真实坑过）。照抄那个决定。
 *
 * <p>⚠️ 数量与金额一律用 <b>String</b> 而不是 {@code BigDecimal}：与 11.3.2.1 一致，
 * Java 侧全程禁浮点，读回时逐值断言（AGENTS.md「金额与数量」）。
 */
public final class ExchangeOrderLinkModels {

  private ExchangeOrderLinkModels() {}

  // ================================================================ 能力声明

  /**
   * 换货新单能力声明。
   *
   * <p>本模块最重要的一个字段是 {@code oldGoodsReturnReceivedDelivered}：实查 V161 的
   * {@code rma_authorization} 词表里**没有** RECEIVED，所以「换货已闭环」当前只能由
   * 「已发出 + 旧货经批准免回」达成。页面必须如实告诉用户这件事，
   * 否则「已闭环」会被读成「旧货已入库质检通过」。
   */
  public record ExchangeOrderCapabilities(
      boolean exchangeAuthorizationDelivered,
      boolean realNewSalesOrder,
      boolean shipmentProjectionReadable,
      String shipmentProjectionOwnerModule,
      boolean outboundHandoffDelivered,
      String outboundHandoffOwnerModule,
      boolean oldGoodsReturnReceivedDelivered,
      String oldGoodsReturnOwnerModule,
      List<String> closureStatuses,
      List<String> closureStatusLabels,
      List<String> allowedNextActions,
      String note,
      List<String> hardRules) {}

  // ================================================================ 查询

  /**
   * 列表查询。所有筛选项可空，空即不过滤。
   *
   * <p>id 筛选项刻意用 {@code String} 而不是 {@code UUID}：控制器原样透传，
   * 由服务层在拼 SQL 时统一 {@code ?::uuid}，非法 id 在绑参那一刻被数据库拒，
   * 而不是先在 Java 里 parse 抛一个与租户无关的 NumberFormatException。
   */
  public record ExchangeOrderQuery(
      @Schema(nullable = true) String authorizationId,
      @Schema(nullable = true) String caseId,
      @Schema(nullable = true) String orderId,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size) {}

  /** 换货新单列表行。 */
  public record ExchangeOrderRow(
      UUID id,
      String linkNo,
      UUID authorizationId,
      String authorizationNo,
      UUID caseId,
      String caseNo,
      UUID originalOrderId,
      String originalOrderNo,
      UUID newOrderId,
      String newOrderNo,
      String newOrderStatus,
      String newOrderStatusLabel,
      String closureStatus,
      String closureStatusLabel,
      String currency,
      String totalQuantity,
      int replacementSkuCount,
      int lineCount,
      String newOrderAmount,
      int shipmentCount,
      OffsetDateTime firstShippedAt,
      String shipmentRecordNo,
      String lastTrackingStatus,
      String oldGoodsReturnStatus,
      String oldGoodsReturnStatusLabel,
      String reasonNote,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime submittedAt,
      OffsetDateTime dispatchedAt,
      OffsetDateTime closedAt,
      UUID cancelledBy,
      OffsetDateTime cancelledAt,
      String cancelReason,
      int version) {}

  /** 分页结果。{@code total} 是**当前筛选下的真实总数**（独立 COUNT），不是本页行数。 */
  public record ExchangeOrderPage(
      List<ExchangeOrderRow> items, long total, int page, int size, ExchangeOrderSummary summary) {}

  /** 列表顶部汇总。与列表同源筛选，翻页时它不变。 */
  public record ExchangeOrderSummary(
      long totalCount,
      long createdCount,
      long dispatchedCount,
      long closedCount,
      long cancelledCount,
      String totalQuantity,
      long pendingDispatchCount) {}

  // ================================================================ 明细视图

  /** 逐行明细：授权行 → 新单行、换几件、替的是哪一件。 */
  public record ExchangeOrderLineView(
      UUID id,
      UUID authorizationLineId,
      UUID newOrderLineId,
      int newOrderLineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID originalSkuId,
      String originalSkuCode,
      String quantity) {}

  /** 闭环事件流的一行（append-only 时间线）。 */
  public record ExchangeOrderEventView(
      UUID id,
      String eventKind,
      String eventLabel,
      String fromClosureStatus,
      String toClosureStatus,
      String detail,
      UUID actorMembershipId,
      OffsetDateTime occurredAt) {}

  /**
   * 详情：头 + 各行 + 事件时间线 + 可执行动作 + **没闭环的真实原因**。
   *
   * <p>{@code closureBlockers} 是本模块刻意做的一列：只给一个「已建立」而不说清
   * 「还差什么」，页面就只能显示一个灰状态 —— 而灰状态会被读成「系统在处理中」，
   * 于是「9.3.3.1 根本没接手」这件事永远没人知道。
   */
  public record ExchangeOrderDetail(
      ExchangeOrderRow link,
      List<ExchangeOrderLineView> lines,
      List<ExchangeOrderEventView> events,
      List<String> allowedActions,
      List<String> closureBlockers) {}

  // ================================================================ 下拉项

  /** 下拉里一条**授权行**：建新单对话框要显示「换的是哪个 SKU、几件」。 */
  public record ExchangeOrderAuthorizationLineOption(
      UUID authorizationLineId,
      UUID skuId,
      String skuCode,
      String skuName,
      String originalSkuCode,
      String quantity) {}

  /**
   * 下拉项：可建换货新单的换货授权单（已建过新单的 / 已撤销的刻意不出现在这里）。
   *
   * <p>商品行随单一起下发：建单对话框要同时显示「授权换的是什么、几件」，
   * 少一次往返就少一次「列表已加载但明细还在飞」的中间态。
   */
  public record ExchangeOrderAuthorizationOption(
      UUID authorizationId,
      String authorizationNo,
      UUID caseId,
      String caseNo,
      UUID originalOrderId,
      String originalOrderNo,
      String status,
      String statusLabel,
      String decision,
      String decisionLabel,
      String authorizedQuantity,
      int replacementSkuCount,
      boolean hasShippingSnapshot,
      List<ExchangeOrderAuthorizationLineOption> lines) {}

  /** 筛选与下拉项。标签与码**同序**，界面按下标取标签，前端不另写一份词表。 */
  public record ExchangeOrderFilterOptions(
      List<String> statuses,
      List<String> statusLabels,
      List<String> sorts,
      List<String> sortLabels,
      List<ExchangeOrderAuthorizationOption> authorizations,
      long authorizationCount) {}

  // ================================================================ 请求

  /**
   * 建换货新单。
   *
   * <p><b>为什么只有三个字段</b>：蓝图输入句是「换货授权；新单创建请求」，而新单上每一个
   * 可填项的真值都在别处 —— 换什么、几件来自换货授权行，数量与 SKU 由授权钉死；
   * 币种、收货地址、客户快照来自原销售订单（换货发给同一个买家）；
   * 金额恒为 0（换货不二次收费）。所以本请求只收「建哪一单的换货新单」与备注，
   * 其余一律从真表派生 —— 让调用方再填一遍就是让同一份事实出现第二个来源。
   */
  public record ExchangeOrderCreateRequest(
      @jakarta.validation.constraints.NotNull UUID authorizationId,
      @Schema(nullable = true)
          @jakarta.validation.constraints.Size(max = 1000)
          String reasonNote) {}

  /**
   * 提交换货新单进审单队列。
   *
   * @param expectedVersion 乐观锁版本。不匹配一律 409 —— 两个人同时点提交时，
   *     后到的那个必须被拒，而不是让一张单进两次审单队列
   */
  public record ExchangeOrderSubmitRequest(
      @jakarta.validation.constraints.NotNull Integer expectedVersion,
      @Schema(nullable = true)
          @jakarta.validation.constraints.Size(max = 1000)
          String reason) {}

  /**
   * 同步闭环状态。
   *
   * <p>本动作**不接受**任何状态值：它只去读真实发货记录与旧货回寄要求，然后据此推进。
   * 请求里写「请把状态置成已发货」这种形状在这里不存在 —— 那正是假交付的入口。
   *
   * @param expectedVersion 乐观锁版本
   */
  public record ExchangeOrderSyncRequest(
      @jakarta.validation.constraints.NotNull Integer expectedVersion,
      @Schema(nullable = true)
          @jakarta.validation.constraints.Size(max = 1000)
          String reason) {}

  /**
   * 撤销换货新单。
   *
   * <p>只允许在**新单仍是草稿且从未发货**时撤销：已提交的单要作废得走 9.2 审单的驳回，
   * 已发货的单更要按真实退货处理 —— 这两种情况在这里一律 409，不假装能撤。
   *
   * @param expectedVersion 乐观锁版本
   */
  public record ExchangeOrderCancelRequest(
      @jakarta.validation.constraints.NotNull Integer expectedVersion,
      @Schema(nullable = true)
          @jakarta.validation.constraints.Size(max = 1000)
          String reason) {}
}
