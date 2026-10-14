package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 6.4.1.2 BOM 关联的传输模型。
 *
 * <p>蓝图 06 卷 6.4.1.2 原文：
 *
 * <blockquote>
 *
 * 职责：加工单引用 BOM 版本并冻结展开结果，后续 BOM 修改不影响已下达的加工单。 输入：BOM 版本选择；冻结动作。输出：冻结的组件清单快照。 关系：上游 = 3.5.2.2 展开；下游
 * = 6.4.1.1。 <b>冻结与订单冻结组件同理</b>：加工周期长，BOM 中途改配会导致实物与系统不符。
 *
 * </blockquote>
 *
 * <p><b>建模纪律（与 6.4.1.1 的 {@code ProcessingModels} 同源，不另立一套）</b>：
 *
 * <ul>
 *   <li><b>快照不是引用</b>：{@link BomSnapshotLine} 里的每一个数都是展开那一刻的 <b>值</b>，不是指向 catalog 的指针。读快照时**不 join
 *       catalog** —— BOM 中途改配、展开重算、库存变动都不影响已冻结的清单。
 *   <li><b>不重新实现 R1–R7</b>：展开由 catalog 3.5.2.2（V26）真算，本模块原样搬运。 尤其 {@code scrapRate} 是<b>百分数</b>（V25
 *       规则 R6，5 = 5%）， {@code requiredQuantity} 是规则 R7 的 6 位小数<b>不取整</b>结果。
 *   <li><b>数量全程 BigDecimal</b>，禁浮点；数量列一律 NUMERIC(19,6) / NUMERIC(7,4)， 与来源列同精度。
 *   <li><b>被截断的快照必须显形</b>：{@code snapshotStatus = TRUNCATED} 时 {@link BomSnapshot#truncated()}
 *       为真，页面必须看见 —— 静默截断是未完成（V26 规则 E4）。
 * </ul>
 */
public final class BomBindingModels {

  private BomBindingModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 冻结快照的状态（DB 词表，一字不改）。
   *
   * <p>沿用 V26 {@code bom_explosion_run.status} 的两个值： {@code COMPLETED} = 清单完整；{@code TRUNCATED} =
   * 命中深度上限，<b>清单不完整</b>。
   */
  public enum SnapshotStatus {
    COMPLETED("清单完整"),
    TRUNCATED("已按深度上限截断，清单不完整");

    private final String label;

    SnapshotStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 输入

  /**
   * 冻结动作的输入：BOM 版本选择 + 冻结参数。
   *
   * <p>{@code outputQuantity} 与 {@code warehouseId} 是展开的输入（V26 同名字段）， {@code asOf} 是展开基准时刻（规则 R4
   * 的时刻查询，子 BOM 按这一刻取生效版本）。 三者都随快照一起冻结 —— 事后重算会得到另一个答案。
   *
   * <p>{@code maxDepth} 不传时由服务层取 catalog 3.5.2.2 的默认值 （{@code
   * BomExplosionRules.DEFAULT_MAX_DEPTH}），本模块**不另立一个上限**。
   */
  public record BomFreezeInput(
      @NotNull UUID bomVersionId,
      @NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal outputQuantity,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) String asOf,
      @Schema(nullable = true) @Min(1) @Max(25) Integer maxDepth,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  // ------------------------------------------------------------ 输出

  /**
   * 冻结快照明细的一行（展开树上的一层）。
   *
   * <p><b>每一列都是值，不是指针</b>：{@code unitQuantity} / {@code scrapRate} / {@code requiredQuantity} 与
   * catalog {@code bom_explosion_line} 逐列同义同精度； {@code sourceVersionNo} 回答「这一层的料是按哪一版算的」——
   * 这是单层领料清单（6.4.1.1）答不出、而多层冻结快照必须答得出的问题。
   *
   * @param requiredQuantity 需求量（规则 R7 逐层递推，含损耗、含逐层结转，6 位小数不取整）
   * @param available / inTransit / gap V26 规则 E2 的三列；<b>冻结它们是因为 E1 的逐层结转</b>： 深层的 required
   *     是按父行缺口递推的，不冻这三个数就答不出「这一层为什么是这个数」
   */
  public record BomSnapshotLine(
      UUID id,
      @Schema(nullable = true) UUID parentLineId,
      int depth,
      String path,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID componentId,
      UUID sourceVersionId,
      String sourceVersionNo,
      String sourceBomCode,
      @Digits(integer = 13, fraction = 6) BigDecimal unitQuantity,
      @Digits(integer = 3, fraction = 4) BigDecimal scrapRate,
      @Digits(integer = 13, fraction = 6) BigDecimal requiredQuantity,
      @Digits(integer = 13, fraction = 6) BigDecimal available,
      @Digits(integer = 13, fraction = 6) BigDecimal inTransit,
      @Digits(integer = 13, fraction = 6) BigDecimal gap,
      @Digits(integer = 13, fraction = 6) BigDecimal surplus,
      boolean isSubAssembly,
      @Schema(nullable = true) UUID subBomId,
      @Schema(nullable = true) String subBomCode,
      @Schema(nullable = true) UUID subVersionId,
      @Schema(nullable = true) String subVersionNo,
      boolean ambiguousSubBom,
      boolean expanded,
      boolean covered,
      boolean truncated,
      String role,
      String note) {}

  /**
   * 一张加工单冻结下来的那次展开结果。
   *
   * <p>{@code explosionRunId} 是来源展开运行（catalog 3.5.2.2）的 id， 只作<b>凭据</b>：本快照不依赖它可读 —— 即使 catalog
   * 侧清理了运行记录， 快照仍然完整成立（这正是快照的意义，也是它与「引用」的分界线）。
   *
   * @param snapshotStatus COMPLETED = 清单完整；TRUNCATED = <b>清单不完整</b>，页面必须显红
   * @param asOf 展开基准时刻（业务时间），与 {@code frozenAt}（系统时间）分开存
   */
  public record BomSnapshot(
      UUID id,
      UUID orderId,
      UUID explosionRunId,
      UUID bomId,
      UUID bomVersionId,
      String bomVersionNo,
      UUID outputSkuId,
      String outputSkuCode,
      @Digits(integer = 13, fraction = 6) BigDecimal outputQuantity,
      @Schema(nullable = true) UUID warehouseId,
      String asOf,
      int maxDepth,
      int reachedDepth,
      String snapshotStatus,
      boolean truncated,
      int lineCount,
      int purchaseLineCount,
      @Digits(integer = 13, fraction = 6) BigDecimal totalRequired,
      @Digits(integer = 13, fraction = 6) BigDecimal totalGap,
      UUID frozenBy,
      String frozenAt,
      String note,
      List<BomSnapshotLine> lines) {}

  /**
   * 加工单的 BOM 绑定详情：快照 + 「与当前 BOM 是否已漂移」的判定。
   *
   * <p>{@code drifted} 是本模块对蓝图那句「后续 BOM 修改不影响已下达的加工单」的 <b>正面回答</b>：它比对的是「快照里冻结的版本」与「此刻生效的版本（规则 R4）」
   * 两个 <b>id</b>，而不是去比对组件用量 —— 用量差异由 {@code lines} 原样呈现即可， 而「这张单当时按哪一版算的」才是不可变的那部分。
   *
   * @param effectiveVersionNo 此刻生效的已发布版本号（规则 R4 时刻查询）。找不到时为 null
   * @param effectiveVersionMissing 该单冻结的版本已不再是此刻唯一生效版本 —— 页面据此显式告警
   */
  public record BomBinding(
      UUID orderId,
      String orderNo,
      String orderStatus,
      /**
       * 冻结快照。**没冻结过时是 null**（加工单存在但尚未执行冻结，是正常业务状态）。
       *
       * <p>注意：Spring 对**记录组件类型的引用**不发射 {@code nullable}，所以生成的契约里 这一列是裸 {@code $ref}（与 6.4.1.1 的
       * {@code inboundRequest} 同一形状）。 前端因此按「可能为 null」处理（{@code ?? null} + 空态分支），不依赖契约的单取值。
       */
      @Schema(nullable = true) BomSnapshot snapshot,
      @Schema(nullable = true) String effectiveVersionNo,
      boolean drifted,
      boolean effectiveVersionMissing,
      List<String> notes) {}

  /**
   * 冻结结果 = 快照 + 冻结动作本身的回执。
   *
   * <p>{@code alreadyFrozen} 为真表示这张单此前已冻结过：模块**不覆盖**旧快照 （UNIQUE(tenant_id,order_id) +
   * 触发器钉死），而是把既有的那份原样返回 —— 「这张单当时按哪棵树算的」只能有一个答案。
   */
  public record BomFreezeResult(BomSnapshot snapshot, boolean alreadyFrozen) {}
}
