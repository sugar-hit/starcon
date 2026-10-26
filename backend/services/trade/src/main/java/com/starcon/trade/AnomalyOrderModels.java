package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 1.1.1.2 异常订单队列的传输模型。
 *
 * <p>蓝图原文（{@code docs/product/capability-blueprint/01-dashboard-and-portal.md}）：
 *
 * <pre>
 * ### 1.1.1.2 异常订单队列
 * - 职责：识别"卡住了"的订单并给出处置建议。异常类型：平台超时未回传发货、地址不可达、
 *         买家留言待确认、黑名单买家、库存不足卡单、地址欺诈疑似、重复下单。
 * - 输入：order.status_history 投影、order_charge 异常标记、地址规则判定结果、
 *         黑名单匹配结果（16 合规与风控）、库存可用量投影（07 库存）。
 * - 输出：异常订单清单（订单号、店铺、异常类型、停留时长、建议动作）；批量标记忽略 /
 *         转人工 / 转售后；生成的处理工单。
 * - 关系：上游 = 09 订单、07 库存、16 合规；下游 = 09 订单处理页、11 售后（转售后）、
 *         02 渠道（平台侧查询）。共享数据：sales_order 摘要、listing 映射状态。
 * </pre>
 *
 * <h2>为什么这个模块是**只读投影**，而且**不建表**</h2>
 *
 * <p>卷 01 的边界写在蓝图第一行：「不产生新的业务事实，只聚合各系统的投影」、
 * 「<b>不做</b>：任何写业务数据的操作。工作台只读 + 跳转；一切状态变更回到归属系统完成」。
 *
 * <p>⇒ 本模块**不新建任何表**（因此**不占用 V327**），**不提供任何写端点**，
 * 全部读的是 09/11 已经在本服务里落下的事实表。
 *
 * <h2>跨服务分工：谁的数据在谁那里，聚合在哪里做</h2>
 *
 * <p>七类异常里有两类的上游不在 trade：
 *
 * <ul>
 *   <li><b>黑名单买家</b> —— 权威登记在 <b>channel</b>（16.5.1.1 {@code buyer_blacklist_entry} /
 *       {@code buyer_blacklist_order_flag}）。trade **不**跨库查 channel，
 *       而是**前端聚合层**去读 channel 的授权只读端点 {@code GET /channel/buyer-blacklist/flags}，
 *       用 {@code buyer_key}（收件人三件套的 md5，与本服务
 *       {@code md5(concat_ws(E'\x1f', recipient_name, recipient_phone, recipient_email))} 同式）连接。
 *   <li><b>库存不足卡单</b> —— 可用量在 <b>warehouse</b>（07 库存 7.1.1.3 的
 *       {@code GET /warehouse/inventory/sellable/skus}）。同样由**前端聚合层**去读，
 *       本服务**不**在 trade 里写 SQL 查 warehouse 的表（AGENTS.md 模块边界纪律）。
 * </ul>
 *
 * <p>⇒ 本模块（trade 侧）只负责**订单侧的四类信号**加上**订单摘要**；
 * 另两类由前端合并后补进同一张清单。合并之后页面上每个数字都能点进去看到明细，
 * 且明细口径与清单口径一致。
 *
 * <h2>七类异常与它们的真实上游（逐条落到真实表）</h2>
 *
 * <table>
 *   <caption>异常类型 → 事实来源</caption>
 *   <tr><th>异常类型</th><th>事实来源（真实表名）</th><th>归属</th></tr>
 *   <tr><td>平台超时未回传发货</td><td>{@code shipment_writeback_task} + {@code shipment_record}</td><td>本服务</td></tr>
 *   <tr><td>地址不可达</td><td>{@code order_address_check}（{@code delivery_status='NOT_DELIVERABLE'}）</td><td>本服务</td></tr>
 *   <tr><td>买家留言待确认</td><td>{@code platform_order}（{@code normalized_status='PENDING_CONFIRMATION'}）</td><td>本服务</td></tr>
 *   <tr><td>黑名单买家</td><td>{@code buyer_blacklist_order_flag}</td><td><b>channel</b>（前端合并）</td></tr>
 *   <tr><td>库存不足卡单</td><td>{@code stock_bucket} / 可售量投影</td><td><b>warehouse</b>（前端合并）</td></tr>
 *   <tr><td>地址欺诈疑似</td><td>{@code order_risk_assessment_component}（{@code ADDRESS_CONCENTRATION}）</td><td>本服务</td></tr>
 *   <tr><td>重复下单</td><td>{@code order_risk_assessment_component}（{@code ORDER_FREQUENCY}）</td><td>本服务</td></tr>
 * </table>
 *
 * <p>⚠️ 蓝图里写的 {@code order.status_history} 在本仓的真实表名是
 * {@code sales_order_status_history}；{@code order_charge} 真实存在，
 * 但它记的是**费用**（运费 / 折扣 / 税 / 包装 / 其他），**没有「异常标记」列** ——
 * 本模块把它当作**订单费用明细**（进详情页），而不是当异常判据，
 * 因为费用行的金额正负由 DB 的 CHECK 钉死，不存在「一条费用自己带异常标记」这回事。
 *
 * <h2>记录简单类名全局唯一</h2>
 *
 * <p>springdoc 用简单类名做 components.schemas 键，codegen 又把各服务的 schema 合并进
 * 同一个桶文件，重名会被**静默覆盖**。本模块所有 record 一律带 {@code AnomalyOrder} 前缀
 * （与 {@code AddressPaymentCheck} / {@code Risk} / {@code Intercept} 同一手法）。
 */
public final class AnomalyOrderModels {

  private AnomalyOrderModels() {}

  // ============================================================ 枚举

  /**
   * 七类异常（蓝图职责句逐字点名，顺序即蓝图里的顺序）。
   *
   * <p>刻意做成**枚举而不是字符串**：前端要按它渲染标签、按它分色、按它筛。
   * 散落的字符串常量迟早会多出第八个「顺手加的」类型，而新加的类型没有事实来源。
   */
  public enum AnomalyType {
    PLATFORM_SHIPMENT_TIMEOUT("平台超时未回传发货", "trade"),
    ADDRESS_UNDELIVERABLE("地址不可达", "trade"),
    BUYER_MESSAGE_PENDING("买家留言待确认", "trade"),
    BLACKLIST_BUYER("黑名单买家", "channel"),
    INSUFFICIENT_STOCK("库存不足卡单", "warehouse"),
    ADDRESS_FRAUD_SUSPECTED("地址欺诈疑似", "trade"),
    DUPLICATE_ORDER("重复下单", "trade");

    private final String label;
    private final String ownerService;

    AnomalyType(String label, String ownerService) {
      this.label = label;
      this.ownerService = ownerService;
    }

    public String label() {
      return label;
    }

    /** 该类异常的**事实归属服务**（{@code trade} / {@code channel} / {@code warehouse}）。 */
    public String ownerService() {
      return ownerService;
    }
  }

  /**
   * 严重度。取值域刻意收窄到四级，且**有明确的高低序**（前端按它排序与配色）。
   *
   * <p>顺序里没有「忽略」这一档：一条地址不可达的单要么值得处理，要么根本没进清单 ——
   * 「有信号但先不管」是处置状态，不是严重度。
   */
  public enum AnomalySeverity {
    LOW("低"),
    MEDIUM("中"),
    HIGH("高"),
    CRITICAL("严重");

    private final String label;

    AnomalySeverity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 建议动作（蓝图的「建议动作」与「批量标记忽略 / 转人工 / 转售后」）。
   *
   * <p>🔴 **本枚举里没有一个动作是「在工作台里改业务数据」**：每个动作都指向**归属系统**，
   * 工作台只负责把人送到那里并交接选中的订单号。这不是偷懒，是卷 01 的边界
   * （「工作台只读 + 跳转；一切状态变更回到归属系统完成」）。
   *
   * <p>{@link #MARK_IGNORED} 是唯一一个**归属系统没有对应写端点**的动作 ——
   * 它在 {@link AnomalyOrderAction#available} 上恒为 false，并在能力声明里点名缺口。
   */
  public enum AnomalyActionCode {
    OPEN_ORDER("打开订单处理页", "09 订单处理页"),
    ESCALATE_MANUAL("转人工（异常拦截处置）", "09 异常拦截"),
    TRANSFER_AFTERSALES("转售后（售后受理入口）", "11 售后"),
    QUERY_PLATFORM("平台侧查询（发货回传）", "02 渠道"),
    OPEN_BLACKLIST("查看黑名单登记", "16 合规与风控"),
    OPEN_INVENTORY("查看库存可售量", "07 库存"),
    MARK_IGNORED("标记忽略", "09 订单处理页");

    private final String label;
    private final String ownerModule;

    AnomalyActionCode(String label, String ownerModule) {
      this.label = label;
      this.ownerModule = ownerModule;
    }

    public String label() {
      return label;
    }

    public String ownerModule() {
      return ownerModule;
    }
  }

  // ============================================================ 能力声明

  /**
   * 一条能力声明：**这个异常类型接通了没有**，没接通的话**归谁**、**影响什么**。
   *
   * @param dimension 异常类型码
   * @param label 中文标签
   * @param available 本服务（或前端聚合层）**是否真的能判出这一类**
   * @param ownerModule 没接通时的归属模块（蓝图里的模块号 + 名称）
   * @param source 事实来源（真实表名或端点路径）
   * @param aggregation 聚合位置：{@code TRADE}（本服务）/ {@code FRONTEND}（前端聚合层）
   * @param impact 没接通 / 不可用时的影响面（人话，不许写「暂无」）
   */
  public record AnomalyOrderCapability(
      String dimension,
      String label,
      boolean available,
      String ownerModule,
      String source,
      String aggregation,
      String impact) {}

  /**
   * 跨服务来源清单：**哪一类异常的数据在别的服务里，前端去哪个端点读，怎么连**。
   *
   * <p>这是卷 01「前端聚合层」的**显式契约**：页面照着它去读，而不是各处硬编码。
   *
   * @param dimension 异常类型码
   * @param label 中文标签
   * @param ownerModule 归属模块（蓝图模块号 + 名称）
   * @param endpoint 前端要读的**授权只读**端点
   * @param joinKey 连接键（说明字段名与**同式**要求）
   */
  public record AnomalyOrderCrossSource(
      String dimension,
      String label,
      String ownerModule,
      String endpoint,
      String joinKey) {}

  /** 能力声明全集。 */
  public record AnomalyOrderCapabilities(
      List<AnomalyOrderCapability> dimensions,
      List<AnomalyOrderCrossSource> crossServiceSources,
      List<String> notConnected,
      List<AnomalyOrderAction> actions,
      int platformWritebackTimeoutHours,
      int buyerMessageTimeoutHours,
      String dataAsOf) {}

  /**
   * 一个建议动作的**归属与可用性**。
   *
   * @param available 归属系统**是否真的有**这个动作的入口
   * @param impact 不可用时的影响面
   */
  public record AnomalyOrderAction(
      String code,
      String label,
      String ownerModule,
      boolean available,
      String impact) {}

  // ============================================================ 选项

  /** 异常类型选项（码 → 标签），供前端下拉与筛选。 */
  public record AnomalyOrderTypeOption(String code, String label, String ownerService) {}

  /** 停留时长筛选档位（小时）。 */
  public record AnomalyOrderDwellPreset(String code, String label, int minHours) {}

  /** 筛选项全集。 */
  public record AnomalyOrderOptions(
      List<AnomalyOrderTypeOption> anomalyTypes,
      List<String> shops,
      List<String> orderSources,
      List<AnomalyOrderDwellPreset> dwellPresets,
      int defaultMinDwellHours,
      String dataAsOf) {}

  // ============================================================ 清单

  /**
   * 订单行上的 SKU 与它需要的数量（前端用它连 warehouse 的可售量）。
   *
   * @param quantity 这一行需要的数量（与 {@code sales_order_line.quantity} 同口径）
   */
  public record AnomalyOrderSkuNeed(UUID skuId, String skuCode, BigDecimal quantity) {}

  /**
   * 一条异常订单（清单行）。
   *
   * @param anomalyTypes 该单命中的异常类型码（可多个）
   * @param severity 该单的最高严重度
   * @param dwellSeconds 停留时长（秒）——从「进入当前异常状态」到现在的时长
   * @param dwellSince 停留起点的时刻
   * @param recommendedActions 建议动作码（与 {@link AnomalyActionCode} 同域）
   * @param buyerKey 买家身份键（收件人三件套的 md5），前端用它连 channel 的黑名单
   * @param skuNeeds 订单行上的 SKU 与所需数量，前端用它连 warehouse 的可售量
   */
  public record AnomalyOrderListRow(
      UUID orderId,
      String orderNo,
      String orderSource,
      String shopCode,
      String platformCode,
      String currency,
      BigDecimal grandTotalAmount,
      String status,
      OffsetDateTime submittedAt,
      long dwellSeconds,
      OffsetDateTime dwellSince,
      List<String> anomalyTypes,
      List<String> anomalyLabels,
      String severity,
      List<String> recommendedActions,
      String buyerKey,
      List<AnomalyOrderSkuNeed> skuNeeds,
      int lineCount,
      BigDecimal totalQuantity) {}

  /** 汇总计数（**不受分页与筛选影响**，翻页时它不能变）。 */
  public record AnomalyOrderSummary(
      long total,
      long platformShipmentTimeout,
      long addressUndeliverable,
      long buyerMessagePending,
      long blacklistBuyer,
      long insufficientStock,
      long addressFraudSuspected,
      long duplicateOrder,
      long oldestDwellSeconds,
      Map<String, Long> byShop) {}

  /** 清单页（真分页 + 真筛选 + 真排序 + 汇总 + 数据截至时间）。 */
  public record AnomalyOrderPage(
      List<AnomalyOrderListRow> items,
      int page,
      int size,
      long total,
      int totalPages,
      AnomalyOrderSummary summary,
      String dataAsOf) {}

  // ============================================================ 明细

  /** 订单行（快照，与下单当时一致）。 */
  public record AnomalyOrderLine(
      int lineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      String specSnapshot,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal lineAmount) {}

  /** 订单费用行（{@code order_charge}）。 */
  public record AnomalyOrderCharge(
      String chargeType,
      String label,
      BigDecimal amount,
      String chargeKind,
      String note) {}

  /** 状态事件（{@code sales_order_status_history}，append-only 留痕）。 */
  public record AnomalyOrderStatusEvent(
      String eventType,
      String fromStatus,
      String toStatus,
      String reason,
      OffsetDateTime occurredAt) {}

  /**
   * 一条异常的**凭据**：它凭什么算异常、停了多久、依据在哪。
   *
   * @param since 停留起点
   * @param dwellSeconds 从 {@code since} 到现在停了多少秒
   * @param facts 凭据明细（逐条人话，不是一串裸字段名）
   */
  public record AnomalyOrderEvidence(
      String dimension,
      String label,
      String severity,
      OffsetDateTime since,
      long dwellSeconds,
      String summary,
      List<String> facts) {}

  /** 订单明细页（清单行 + 明细 + 凭据 + 建议动作）。 */
  public record AnomalyOrderDetail(
      AnomalyOrderListRow order,
      List<AnomalyOrderLine> lines,
      List<AnomalyOrderCharge> charges,
      List<AnomalyOrderStatusEvent> statusHistory,
      List<AnomalyOrderEvidence> evidence,
      List<AnomalyOrderAction> actions,
      String buyerName,
      String buyerPhone,
      String buyerEmail,
      String shippingCountry,
      String shippingAddress,
      String dataAsOf) {}
}
