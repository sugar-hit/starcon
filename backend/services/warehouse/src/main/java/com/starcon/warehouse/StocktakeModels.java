package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.4.2.1 盘点单的传输模型。
 *
 * <p>蓝图原话：「发起盘点：选择范围（按仓库/类目/库位/ABC 分类）、冻结时点快照、生成盘点任务
 * （按库位或按 SKU）、录入实盘数」「输出：盘点单 + 账面/实盘/差异三列数据」，以及本模块唯一的
 * 红线「**盘点时点必须冻结账面数**，否则边盘边卖会产生虚假差异」。
 *
 * <p><b>三列的口径（整个模块只有这一套，别处再写一遍就会算错）</b>：
 *
 * <pre>
 *   账面 bookOnHand   发起盘点**那一刻**的 on_hand，已经复制成行存下（V59 触发器钉死不可改）
 *   实盘 countedQty    盘点员录进来的数；NULL = 还没盘
 *   差异 variance      = countedQty − bookOnHand，DB 生成列，不接受人工填写
 * </pre>
 *
 * <p>差异是**算出来的不是填的**：手工填差异等于允许出现一个解释不了的数，而盘点差异最终要变成
 * 调整单并追责到人 —— 那个数必须能从账面与实盘两列复现。
 *
 * <p><b>账面数为什么会"看起来不对"</b>：盘点期间仓库照常收发，所以盘点单上的账面数与今天的
 * 库存余额本来就不一样。**这不是 bug，这正是冻结的含义** —— 它要回答的是「冻结那一刻有多少」，
 * 而不是「现在有多少」。要看现在的量请去 7.1.1.2 库存明细。
 *
 * <p><b>为什么不建"累计值"表</b>：账面数落成行之后它就是唯一真相，再存一份会让人无法判断该信谁
 * （与 7.1.2.1 拒绝"累计值表"同一条理由）。
 */
public final class StocktakeModels {

  private StocktakeModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 盘点范围类型。
   *
   * <p>蓝图列了四种（仓库/类目/库位/ABC 分类），这里只给三个，理由逐条写死在枚举上：
   *
   * <ul>
   *   <li>{@link #WAREHOUSE} 按仓库、{@link #LOCATION} 按库位 —— 两者都是本服务自有数据
   *       （warehouse / warehouse_location，V1/V2），可以直接筛出真正不同的任务集合。
   *   <li>{@link #CATEGORY} 按类目 —— <b>枚举里留着，但当前显式拒绝（422）</b>。类目与品牌属
   *       03 商品主数据（catalog），{@code sku_ref} 刻意不存类目（V1 文件头），本服务的
   *       CatalogProjectionPort 尚未接入读取适配器。按 7.1.1.1 已建立的同一套约定，未接入的
   *       维度**显式拒绝**而不是返回空集 —— 后者会被读成「按这个类目盘不出任何库存」。
   *       上游接入后只换那个 Bean，本模块逻辑不动。
   * </ul>
   *
   * <p><b>ABC 分类刻意连枚举都不给</b>：全库（warehouse 与 catalog 两侧所有迁移 + db/schemas）
   * 不存在任何 ABC 分类字段，也没有能推导出它的动销率/帕累托原始列。臆造一个分位算法冒充
   * 已有数据，会让盘点员以为盘的是公司认定的 A 类货，实际是一个没人批准的猜测 —— 比明说
   * 「没有这个维度」危险得多。它改由 {@link StocktakeScopeCapability} 如实报出。
   */
  public enum StocktakeScopeType {
    WAREHOUSE("按仓库"),
    LOCATION("按库位"),
    CATEGORY("按类目");

    private final String label;

    StocktakeScopeType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 任务单元：按库位发任务还是按 SKU 发任务（蓝图原文「生成盘点任务（按库位或按 SKU）」）。 */
  public enum StocktakeTaskUnit {
    BY_LOCATION("按库位"),
    BY_SKU("按 SKU");

    private final String label;

    StocktakeTaskUnit(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 盘点单状态。
   *
   * <p>{@code COUNTED} 的含义是「全部行都录了实盘」，此时这张单移交 7.4.2.2 差异处理 ——
   * 本模块**不**替差异下结论，差异处理与调整单是下游的事。
   *
   * <p>可复盘：任一行重录或撤销录入后状态会重新计算，所以 COUNTED 不是终态。
   */
  public enum StocktakeSheetStatus {
    OPEN("待录入"),
    COUNTING("录入中"),
    COUNTED("已录完");

    private final String label;

    StocktakeSheetStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 盘点任务状态 —— <b>派生值，不是列</b>。
   *
   * <p>它恒等于该任务下各行的录入进度：0 行已录 = PENDING，部分 = COUNTING，全 = DONE。
   * 现算比存列可靠：存列就要在每次录入后同步它，漏同步一次就会出现「任务显示已完成但还有行没盘」。
   */
  public enum StocktakeTaskStatus {
    PENDING("未开始"),
    COUNTING("盘点中"),
    DONE("已完成");

    private final String label;

    StocktakeTaskStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 明细筛选：这是在盘点单里"我要看哪些行"的四个互斥口径。
   *
   * <p>{@link #PENDING} 未录入、{@link #MATCH} 账实相符、{@link #GAIN} 盘盈（实盘 > 账面）、
   * {@link #LOSS} 盘亏（实盘 < 账面）。差异的正负由 {@code variance} 决定，而 variance 是
   * 生成列 —— 筛选口径与展示口径因此永远一致，不存在"界面上算一个、库里存另一个"。
   */
  public enum StocktakeLineFilter {
    ALL("全部"),
    PENDING("未录入"),
    MATCH("账实相符"),
    GAIN("盘盈"),
    LOSS("盘亏");

    private final String label;

    StocktakeLineFilter(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 输入

  /**
   * 发起盘点的请求。
   *
   * <p>{@code scopeKey} 按 {@code scopeType} 解释：WAREHOUSE 传 warehouse.id，
   * LOCATION 传 warehouse_location.id，CATEGORY 传 catalog 的类目 id（当前会被拒绝）。
   *
   * <p>刻意**没有** {@code frozenAt} 参数：冻结时刻就是发起的那一刻，由服务端取。
   * 允许调用方指定时刻等于允许「盘三天前的账」，而账面数与三天前那一刻的库存无从对齐
   * （中间的交易还没落进这条单）—— 那等于给了一个造虚假差异的入口。
   */
  public record StocktakeRequest(
      String scopeType,
      @Schema(nullable = true) UUID scopeKey,
      String taskUnit,
      @Schema(nullable = true) String note) {}

  /**
   * 录入实盘数。
   *
   * <p>{@code countedQty} 传 null 表示**撤销录入**（把这一行退回未盘），用于录错时纠正 ——
   * 复盘是 7.4.2.2 的正式处理决定之一，前置条件是这一行还能改。
   *
   * <p>{@code barcode} 对应蓝图说的「扫码」：本模块没有扫码硬件对接协议，所以做成
   * 手工录入 + 条码字段，而不是伪造一个扫码流程 —— 假扫码只会给盘点员一个假的成功提示。
   */
  public record StocktakeCountInput(
      @Schema(nullable = true) BigDecimal countedQty,
      @Schema(nullable = true) String barcode,
      @Schema(nullable = true) String note) {}

  /** 派工。目标用户 id 由调用方给出（本服务无法校验其成员归属，见 V56 同一理由）。 */
  public record StocktakeAssignInput(@Schema(nullable = true) UUID assigneeUserId) {}

  // ------------------------------------------------------------ 盘点单

  /**
   * 一张盘点单的头部。
   *
   * @param frozenAt 冻结时刻；账面数就是这一瞬间的数（见类注释）
   * @param lineCount 冻结的明细行数（= 冻结时刻落在范围内的 bucket 行数）
   * @param countedLineCount 已录实盘的行数；进度由这三个计数派生，不另存一列进度
   * @param varianceLineCount 有差异（实盘≠账面）的行数
   * @param bookTotal 账面合计（冻结值合计）。列表页要能直接看到它，否则用户会以为
   *     账面数等于当前库存余额 —— 它不是，它只回答「冻结那一刻有多少」
   */
  public record StocktakeSheet(
      UUID id,
      String code,
      StocktakeScopeType scopeType,
      String scopeLabel,
      StocktakeTaskUnit taskUnit,
      OffsetDateTime frozenAt,
      StocktakeSheetStatus status,
      long lineCount,
      long countedLineCount,
      long varianceLineCount,
      BigDecimal bookTotal,
      String note,
      UUID createdByUserId,
      OffsetDateTime createdAt) {}

  /** 盘点单列表（真分页，total 与分页无关）。 */
  public record StocktakeSheetPage(List<StocktakeSheet> sheets, long total, int page, int size) {}

  /**
   * 三列合计。差异合计与「有差异行数」是两回事：一个 -100 + 一个 +100 的合计是 0，
   * 但盘点员要看的是「差了几行」而不是「差了多少互相抵消」。
   */
  public record StocktakeSummary(
      long lineCount,
      long countedLineCount,
      long varianceLineCount,
      BigDecimal bookTotal,
      BigDecimal countedTotal,
      BigDecimal varianceTotal) {}

  /** 盘点单详情 = 头部 + 三列合计。 */
  public record StocktakeDetail(StocktakeSheet sheet, StocktakeSummary summary) {}

  /** 发起盘点的结果：新单 + 冻结规模 + 一句说清冻结意味着什么的结论。 */
  public record StocktakeSaved(
      StocktakeSheet sheet, int taskCount, String note) {}

  // ------------------------------------------------------------ 盘点明细

  /**
   * 一行盘点明细 = 冻结那一刻的一个 bucket + 三列。
   *
   * <p>维度与可读名都是**冻结时刻的快照**：sku_ref 与 warehouse_location 都可能被改名或停用，
   * 盘点单要能回看「当时盘的那个货叫什么、在哪儿」。
   *
   * @param bookOnHand 账面数（冻结值，永不随库存变动而改写）
   * @param countedQty 实盘数；null = 未录入
   * @param variance 差异 = 实盘 − 账面；未录入时为 null
   */
  public record StocktakeLine(
      UUID id,
      UUID sheetId,
      UUID bucketId,
      UUID taskId,
      UUID legalEntityId,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID warehouseId,
      String warehouseCode,
      UUID locationId,
      String locationCode,
      @Schema(nullable = true) UUID lotId,
      String lotCode,
      UUID qualityStatusId,
      String qualityStatusCode,
      BigDecimal bookOnHand,
      @Schema(nullable = true) BigDecimal countedQty,
      @Schema(nullable = true) BigDecimal variance,
      @Schema(nullable = true) OffsetDateTime countedAt,
      @Schema(nullable = true) UUID countedByUserId,
      @Schema(nullable = true) String countedBarcode,
      @Schema(nullable = true) String countedNote) {}

  /** 明细分页（真分页，total 是该筛选下的全量行数）。 */
  public record StocktakeLinePage(List<StocktakeLine> lines, long total, int page, int size) {}

  // ------------------------------------------------------------ 盘点任务

  /**
   * 一个盘点任务（按库位或按 SKU）。
   *
   * <p>{@code status} 与两个进度计数都是**派生值**（见 {@link StocktakeTaskStatus}）：
   * 它们由该任务下的明细行算出，因此不可能与实际录入进度对不上。
   *
   * @param taskKey BY_LOCATION 时是 location_id，BY_SKU 时是 sku_id
   */
  public record StocktakeTask(
      UUID id,
      UUID sheetId,
      StocktakeTaskUnit taskUnit,
      UUID taskKey,
      String taskCode,
      String taskName,
      @Schema(nullable = true) UUID assignedToUserId,
      StocktakeTaskStatus status,
      long lineCount,
      long countedLineCount,
      BigDecimal bookTotal,
      BigDecimal countedTotal,
      BigDecimal varianceTotal) {}

  // ------------------------------------------------------------ 范围能力

  /**
   * 盘点范围维度的接入情况。
   *
   * <p>前端据此**禁用**不可用的范围选项，而不是让用户填完收一个 422；
   * {@code note} 如实说明为什么不可用 —— 尤其是 ABC 分类没有字段这件事，
   * 界面必须能对用户讲清楚，而不是让一个选项凭空消失。
   */
  public record StocktakeScopeCapability(
      String scopeType, String label, boolean available, String note) {}
}