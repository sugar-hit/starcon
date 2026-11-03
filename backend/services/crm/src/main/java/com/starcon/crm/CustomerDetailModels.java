package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 15.3.1.2 客户详情的传输模型。
 *
 * <p>蓝图 15 卷 15.3.1.2 原话：
 *
 * <blockquote>
 * 职责：客户全档案：基本信息、跨店铺身份关系、订单历史、售后历史、消息历史、评价、标签、接触记录、风险与价值分层。
 * 输入：{@code customer_id}；聚合各域数据。
 * 输出：详情视图 + 时间线（订单/消息/售后合并时间线）。
 * 关系：上游 = 15.3.1.1；下游 = 9 订单、11 售后、15.1 消息。
 * <b>统一时间线是客服理解客户的唯一高效方式</b> —— 跨系统翻找会显著拖慢响应。
 * </blockquote>
 *
 * <p><b>本文件最重要的三件事</b>：
 *
 * <ol>
 *   <li><b>时间线是「合并」出来的</b>：订单 / 售后 / 消息 / 评价 / 接触记录五个来源
 *       落到同一张时间线上（{@link CrmCustomerDetailTimelineEntry}），
 *       排序键统一为 {@code occurred_at DESC, indexed_at DESC, id DESC} ——
 *       后两个键都是 DB 时钟写的 ⇒ 同一时钟域，可比。</li>
 *   <li><b>「跨店铺身份关系」按未合并的形态实现</b>：合并映射由 15.3.1.3 拥有，
 *       本模块**不建**合并表、**不读**合并表。本模块只给**线索**
 *       （同名 / 同联系方式指纹），<b>线索不是合并结论</b>。</li>
 *   <li><b>每一段都要说清「有没有、缺什么、归谁」</b>：{@link CrmCustomerDetailSourceState}
 *       逐段给出 {@code available} / {@code reason} / {@code ownerModule}。
 *       「读不到」与「没有数据」是**两件事**，前者必须如实报出来。</li>
 * </ol>
 *
 * <p><b>命名</b>：统一用 {@code CrmCustomerDetail*} 前缀，与既有类型零重叠。
 */
public final class CustomerDetailModels {

  private CustomerDetailModels() {}

  // ================================================================ 枚举

  /**
   * 统一时间线的来源类别。
   *
   * <p>每一条时间线条目都归到其中一类，{@code ownerModule} 指明**真正的拥有者**
   * （真相在它那边，本模块只做合并与展示）。
   */
  public enum CustomerTimelineSourceKind {
    ORDER("订单", "9 订单"),
    AFTERSALES("售后", "11 售后"),
    MESSAGE("消息", "15.1 消息"),
    REVIEW("评价", "15.2 评价"),
    CONTACT("接触记录", "15.3.2.2 接触记录");

    private final String label;
    private final String ownerModule;

    CustomerTimelineSourceKind(String label, String ownerModule) {
      this.label = label;
      this.ownerModule = ownerModule;
    }

    public String label() {
      return label;
    }

    /** 真正拥有这类事实的模块。 */
    public String ownerModule() {
      return ownerModule;
    }

    /** 按名字解析（解析不出来返回 {@code null}，不抛异常 —— 未知来源名不该让整页 500）。 */
    public static CustomerTimelineSourceKind fromName(String name) {
      if (name == null || name.isBlank()) return null;
      for (CustomerTimelineSourceKind kind : values()) {
        if (kind.name().equalsIgnoreCase(name.trim())) return kind;
      }
      return null;
    }
  }

  /**
   * 合并状态。
   *
   * <p>🔴 **当前只有 {@link #NOT_MERGED} 一个取值** —— 因为 15.3.1.3 跨店合并
   * **尚未交付**（它拥有逻辑客户与合并映射那几张表）。本模块**不假装**已合并，
   * 也不造一张假的合并表来「支撑」别的取值。
   */
  public enum CustomerMergeState {
    NOT_MERGED("未合并");

    private final String label;

    CustomerMergeState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 出参

  /**
   * 客户基本信息（来自 15.3.1.1 的客户主档，**只读**）。
   *
   * @param contactEmail 联系方式 —— 默认脱敏；显式授权且显式要求时才是明文
   * @param contactMasked 当前返回的联系方式是否脱敏
   */
  public record CrmCustomerDetailProfile(
      UUID id,
      String customerRef,
      String sourceShop,
      String customerName,
      @Schema(nullable = true) String customerPartyId,
      @Schema(nullable = true) String firstOrderAt,
      @Schema(nullable = true) String lastOrderAt,
      int orderCount,
      int refundCaseCount,
      @Schema(nullable = true) BigDecimal refundRate,
      String valueTier,
      String valueTierLabel,
      String riskFlag,
      String riskFlagLabel,
      String riskReason,
      String contactEmail,
      String contactPhone,
      boolean contactMasked,
      String contactSource,
      String contactSourceLabel,
      String optOutState,
      String optOutStateLabel,
      @Schema(nullable = true) String windowFrom,
      @Schema(nullable = true) String windowTo,
      @Schema(nullable = true) String syncedAt) {}

  /** 一个币种上的累计消费与客单价（金额**按币种分开**，不加总）。 */
  public record CrmCustomerDetailAmount(
      String currency,
      BigDecimal totalAmount,
      int orderCount,
      @Schema(nullable = true) BigDecimal avgOrderAmount) {}

  /** 客户身上的一枚标签（只读 15.3.2.1 的标签记录）。 */
  public record CrmCustomerDetailTag(
      String tagCode, String tagName, String category, String categoryLabel) {}

  /**
   * 一条**身份线索**：同租户下另一行客户主档，看起来可能是同一个人。
   *
   * <p>🔴 **线索不是合并结论**。本模块**不判定**这两个人是不是同一个人 ——
   * 那是 15.3.1.3 跨店合并的职责（它拥有合并映射与候选表）。
   *
   * @param clue 线索依据（人读）：例如「联系方式指纹一致」「买家名一致」
   * @param maskedContact 脱敏后的联系方式（**永远是脱敏形态**，即使调用方有明文权限）
   */
  public record CrmCustomerDetailIdentityCandidate(
      UUID customerId,
      String customerRef,
      String sourceShop,
      String customerName,
      String clue,
      String maskedContact,
      boolean sameShop) {}

  /**
   * 跨店铺身份关系段。
   *
   * @param mergeState 当前合并状态（只有 {@code NOT_MERGED}）
   * @param ownerModule 合并能力归谁（15.3.1.3）
   * @param note 人读的边界说明
   * @param candidates 身份线索（**候选线索**，不是合并结果）
   */
  public record CrmCustomerDetailIdentity(
      String mergeState,
      String mergeStateLabel,
      String ownerModule,
      String note,
      List<CrmCustomerDetailIdentityCandidate> candidates) {}

  /**
   * 一段数据的接入状态。
   *
   * @param available 这段数据**现在能读到**吗（false ⇒ {@code reason} 必填）
   * @param reason 没读到时的人读原因（「上游未接入」「上游读不到」「该客户没有上游标识」…）
   * @param ownerModule 这段数据的归属模块
   * @param count 已落库 / 已取到的条数
   */
  public record CrmCustomerDetailSourceState(
      String source,
      String sourceLabel,
      boolean available,
      String reason,
      String ownerModule,
      long count) {}

  /**
   * 统一时间线的一条。
   *
   * @param occurredAt 事实**发生**的时刻（上游自己的时钟）
   * @param amount 金额（没有金额的来源为 null；禁浮点）
   * @param quantity 数量（订单件数等；numeric(19,6)）
   */
  public record CrmCustomerDetailTimelineEntry(
      UUID id,
      String sourceKind,
      String sourceKindLabel,
      String sourceRef,
      String sourceModule,
      String occurredAt,
      String title,
      String summary,
      String status,
      String category,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) BigDecimal quantity,
      Map<String, Object> payload) {}

  /**
   * 一次时间线重建的运行记录。
   *
   * @param status SUCCEEDED / PARTIAL / FAILED
   * @param unavailableSources 读不到的来源 → 人读原因（**空 map 表示全部读到了**）
   * @param truncated 扫描被截断（时间线**不是全量**）
   */
  public record CrmCustomerDetailTimelineRun(
      UUID id,
      String runNo,
      String status,
      int entryCount,
      int orderCount,
      int aftersalesCount,
      int messageCount,
      int reviewCount,
      int contactCount,
      Map<String, String> unavailableSources,
      boolean truncated,
      String failureReason,
      @Schema(nullable = true) String startedAt,
      @Schema(nullable = true) String finishedAt) {}

  /**
   * 统一时间线页（真分页：LIMIT/OFFSET + 稳定全序）。
   *
   * @param needsBuild 这位客户**还没有**任何一次重建记录 ⇒ 前端该提示「需要建立时间线」
   * @param canRebuild 当前调用方是否**能**建立/重建（需要 {@code crm.customers.update}）
   */
  public record CrmCustomerDetailTimelinePage(
      List<CrmCustomerDetailTimelineEntry> entries,
      int page,
      int size,
      long total,
      int totalPages,
      String sourceKindFilter,
      boolean needsBuild,
      boolean canRebuild,
      @Schema(nullable = true) CrmCustomerDetailTimelineRun lastRun) {}

  /**
   * 客户详情视图。
   *
   * @param sources 逐段的接入状态（**读不到就如实说读不到**）
   * @param dataScope 实际生效的数据范围：ALL / SELF / DEPARTMENT
   */
  public record CrmCustomerDetailView(
      CrmCustomerDetailProfile profile,
      List<CrmCustomerDetailAmount> amounts,
      List<CrmCustomerDetailTag> tags,
      CrmCustomerDetailIdentity identity,
      List<CrmCustomerDetailSourceState> sources,
      CrmCustomerDetailTimelinePage timeline,
      String dataScope,
      String dataScopeNote,
      String note) {}
}
