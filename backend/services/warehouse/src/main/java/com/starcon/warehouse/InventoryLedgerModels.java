package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.1.2.1 库存流水台账的传输模型。
 *
 * <p>蓝图原话：「按时间顺序展示所有库存业务（入库、出库、预占、释放、调整、盘点、组装、调拨）， 每笔含来源单据、数量符号、操作人、发生时间，可下钻到分录行」与「**流水只追加不修改**」。
 * 第二句决定了本模块的形状：它是 append-only 台账的**读面**，不提供任何写端点。
 *
 * <p><b>粒度（最容易算错的一处，界面与接口必须同口径）</b>：
 *
 * <ul>
 *   <li>一行 = 一笔库存业务（{@code inventory_transaction}），它的数量列是这笔业务 <b>全部</b>带符号分录的求和 ——
 *       即"这笔业务让在手/预占净变了多少"。
 *   <li>分录（{@code inventory_entry}）不在列表里，要点开 {@link InventoryLedgerDrill} 才看得到， 并且带库位级以外的全部定位维度（SKU
 *       / 仓库 / 货权法人 / 质量状态）。
 *   <li>所以"按 SKU 筛出来的行"筛的是**这笔业务碰没碰过这个 SKU**，不是"这笔业务在这个 SKU 上的量"。 一笔跨仓调拨被按单仓筛选命中时，行上的数量仍是整笔的净变动 ——
 *       这是有意的： 行代表业务单，把它的量按筛选条件切一半会让这行不再等于任何一件真实发生过的事。 筛选维度上的量在 {@link
 *       InventoryLedgerSummary#getEntryOnHandDelta()}，两个口径的差就是被筛掉的部分。
 * </ul>
 *
 * <p><b>守恒不变量</b>：没带 SKU/仓库筛选时，行口径（每笔全量）之和与分录口径之和必然相等。 这个等式就是 7.1.2.2 账实核对的基线，所以它摆在响应里 （{@code
 * onHandConserved} / {@code reservedConserved}）而不是只写在测试里。
 */
public final class InventoryLedgerModels {

  private InventoryLedgerModels() {}

  // ------------------------------------------------------------ 业务类型

  /**
   * 库存业务类型。
   *
   * <p>与 {@code inventory_transaction.kind} 的 CHECK 约束逐值对应 —— 枚举跟着 DB 约束走， 不写死一组代码里才有而 DB 放不下的值。
   *
   * <p>蓝图把业务分为「入库、出库、预占、释放、调整、盘点、组装、调拨」，与本枚举的对应关系要讲清楚， 否则界面上会出现"有盘点单却筛不出盘点"：
   *
   * <ul>
   *   <li>入库 = RECEIPT，出库 = SHIPMENT，退货 = RETURN（退货入库仍是入库的一种业务原因）。
   *   <li>调拨 = TRANSFER_OUT + TRANSFER_IN 两条（在途口径见 V1 迁移：货权离开发货仓时记出库）。
   *   <li>预占 = RESERVE（只动 reserved_delta），释放 = RELEASE。
   *   <li>盘点与组装当前由写入方记成 ADJUSTMENT：V1 的 CHECK 只给了上面 8 个码， 盘点与组装的差异体现在同一笔调整的备注与分录构成上。要把它们拆成独立类型， 需要改
   *       V1 的 CHECK 约束，属于跨模块变更，不在本只读台账的改动范围内。
   * </ul>
   */
  public enum LedgerKind {
    RECEIPT("入库"),
    SHIPMENT("出库"),
    ADJUSTMENT("调整"),
    TRANSFER_OUT("调拨出"),
    TRANSFER_IN("调拨入"),
    RETURN("退货"),
    RESERVE("预占"),
    RELEASE("释放");

    private final String label;

    LedgerKind(String label) {
      this.label = label;
    }

    /** 中文标签。界面上用；DB 与契约里仍然是 ASCII 码值。 */
    public String label() {
      return label;
    }

    /**
     * 解析业务类型。
     *
     * <p>未知码直接 400 而不是静默忽略：筛一个不存在的类型返回空列表，用户会以为"这个期间没有这类业务"， 实际是参数打错了字。
     */
    public static LedgerKind parse(String value) {
      for (var kind : values()) {
        if (kind.name().equalsIgnoreCase(value.trim())) return kind;
      }
      throw new IllegalArgumentException("未知的库存业务类型：" + value + "，可选：" + String.join("、", names()));
    }

    public static String names() {
      return String.join("、", java.util.Arrays.stream(values()).map(Enum::name).toList());
    }
  }

  // ------------------------------------------------------------ 筛选与查询

  /**
   * 台账查询条件。
   *
   * <p>{@code actorId} 与 {@code selfOnly} **不是客户端参数**：由控制器根据已认证成员的 数据范围（SELF / DEPARTMENT /
   * ALL）推导后填入，租户与身份都不来自任何请求头 （见 {@code AccessService}）。把它们放进查询对象是为了让 SQL 与服务方法保持显式参数化， 而不是去读会话变量。
   *
   * @param from 发生时间下界（含）
   * @param to 发生时间上界（不含）：用左闭右开避免相邻区间在边界那一瞬重复计数
   * @param skuId 只看碰过该 SKU 的业务（分录级存在性过滤，见类注释的粒度说明）
   * @param warehouseId 只看碰过该仓库的业务
   * @param kinds 业务类型多选（不传 = 全部）
   * @param refTypes 来源单据类型多选（不传 = 全部）
   * @param keyword 关键字：匹配 SKU 编码/名称、仓库编码/名称、业务备注
   * @param sort 排序键（白名单）
   * @param page 页码，从 0 开始
   * @param size 每页行数，服务端有上限
   * @param actorId 数据范围为 SELF 时限定操作人
   * @param selfOnly 是否只返回本人操作过的业务（由角色的数据范围决定，非客户端可控）
   */
  public record InventoryLedgerQuery(
      @Schema(nullable = true) OffsetDateTime from,
      @Schema(nullable = true) OffsetDateTime to,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) List<String> kinds,
      @Schema(nullable = true) List<String> refTypes,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size,
      @Schema(nullable = true) UUID actorId,
      @Schema(nullable = true) Boolean selfOnly) {}

  // ------------------------------------------------------------ 响应

  /**
   * 台账页。
   *
   * @param summary 期间净变动汇总（**不受分页影响**）
   * @param rows 当前页的业务行
   * @param breakdown 按业务类型的分组统计，只包含期间真实发生过的类型
   * @param refTypes 来源单据类型下拉（租户级词表，不随时间筛选变化，避免下拉随翻页漂移）
   * @param dataScope 实际生效的数据范围：ALL / DEPARTMENT / SELF
   * @param dataScopeNote 数据范围的说明文案（界面上要讲清 SELF 到底筛了什么）
   * @param total 命中业务总笔数
   * @param page 当前页码
   * @param size 每页行数
   */
  public record InventoryLedgerPage(
      InventoryLedgerSummary summary,
      List<InventoryLedgerRow> rows,
      List<InventoryLedgerBreakdown> breakdown,
      List<InventoryRefType> refTypes,
      String dataScope,
      String dataScopeNote,
      long total,
      int page,
      int size) {}

  /**
   * 期间净变动汇总。
   *
   * <p>两个口径刻意都摆出来，因为它们回答的是两个不同的问题：
   *
   * <ul>
   *   <li>{@code transactionOnHandDelta} = Σ 每笔业务的净变动（与行上的数字同源，翻页不变）。
   *   <li>{@code entryOnHandDelta} = Σ 命中业务的**分录**中满足 SKU/仓库筛选的那部分。
   * </ul>
   *
   * <p>没带 SKU/仓库筛选时两者必然相等（这正是 7.1.2.2 要拿去和余额快照核对的数）； 带了筛选时不相等是正常的，差值就是这笔业务在其它 SKU/仓库上的部分 —— 差值有解释，
   * 比只给一个数让人猜要安全。
   *
   * @param transactionCount 命中的业务笔数
   * @param entryCount 命中的分录条数（分录口径）
   * @param emptyTransactionCount 一条分录都没有的业务笔数：写入方中途失败的痕迹，必须能看见而不是被静默漏掉
   * @param skuCount 命中分录涉及的 SKU 数
   * @param warehouseCount 命中分录涉及的仓库数
   * @param actorCount 操作人数
   * @param firstOccurredAt 期间内最早一次发生时间（期间内一笔业务都没有时为 null）
   * @param lastOccurredAt 期间内最晚一次发生时间（期间内一笔业务都没有时为 null）
   */
  public record InventoryLedgerSummary(
      long transactionCount,
      long entryCount,
      long emptyTransactionCount,
      BigDecimal transactionOnHandDelta,
      BigDecimal transactionReservedDelta,
      BigDecimal entryOnHandDelta,
      BigDecimal entryReservedDelta,
      boolean onHandConserved,
      boolean reservedConserved,
      long skuCount,
      long warehouseCount,
      long actorCount,
      @Schema(nullable = true) OffsetDateTime firstOccurredAt,
      @Schema(nullable = true) OffsetDateTime lastOccurredAt) {}

  /**
   * 一行 = 一笔库存业务。
   *
   * @param onHandDelta 这笔业务的净在手变动（**入库为正、出库为负**；预占/释放不动在手，因此为 0）
   * @param reservedDelta 这笔业务的净预占变动（预占为正、释放为负）
   * @param entryCount 该业务的分录条数：> 1 说明一笔业务动了多个 SKU 或多个仓
   * @param skuCount / warehouseCount 该业务涉及的 SKU / 仓库数：
   *     行上的数量是整笔的，界面上必须能看出"这笔横跨了几处"，否则按单仓筛选的人会以为数字被算错了
   */
  public record InventoryLedgerRow(
      UUID transactionId,
      LedgerKind kind,
      String refType,
      UUID refId,
      OffsetDateTime occurredAt,
      UUID actorId,
      String notes,
      BigDecimal onHandDelta,
      BigDecimal reservedDelta,
      long entryCount,
      long skuCount,
      long warehouseCount,
      OffsetDateTime createdAt) {}

  /** 按业务类型的分组统计（期间真实发生过的类型才有行）。 */
  public record InventoryLedgerBreakdown(
      LedgerKind kind,
      long transactionCount,
      long entryCount,
      BigDecimal onHandDelta,
      BigDecimal reservedDelta) {}

  /**
   * 分录下钻。
   *
   * @param transaction 该笔业务单（与列表行同构，同一笔事实在两个端点上是同一个对象）
   * @param entries 该业务的全部带符号分录
   */
  public record InventoryLedgerDrill(
      InventoryLedgerRow transaction, List<InventoryEntryLine> entries) {}

  /**
   * 分录行。
   *
   * <p>分录挂在 balance 行上，所以定位维度是 SKU × 仓库 × 货权法人 × 质量状态 （库位与批次属 {@code stock_bucket}，是 balance
   * 之下的另一层事实，流水按 balance 记账）。
   *
   * @param balanceOnHandNow 该 balance 行的**当前**在手（不是这笔业务发生时的值）： 流水是 append-only
   *     的，要还原"当时在手多少"得从更早的分录累加 —— 本模块给的是"现在多少"， 字段名里带 Now 就是提醒它不是历史快照
   * @param balanceAvailableNow 该 balance 行的当前可用（生成列 on_hand − reserved）
   */
  public record InventoryEntryLine(
      UUID entryId,
      UUID balanceId,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      UUID legalEntityId,
      String legalEntityCode,
      UUID qualityStatusId,
      String qualityStatusCode,
      String qualityStatusName,
      BigDecimal onHandDelta,
      BigDecimal reservedDelta,
      BigDecimal balanceOnHandNow,
      BigDecimal balanceReservedNow,
      BigDecimal balanceAvailableNow,
      OffsetDateTime createdAt) {}

  /** 来源单据类型下拉项，带笔数：让用户在下拉里就能看出哪类单据量大。 */
  public record InventoryRefType(String refType, long transactionCount) {}
}
