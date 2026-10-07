package com.starcon.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 3.7.2.1 物理与安全属性的请求与响应记录。
 *
 * <p><b>为什么单独成文件而不是并进 {@code CatalogModels}</b>：{@code CatalogModels} 是
 * 并行车道都会追加的公共文件，且它<b>不在</b> {@code merge-union} 驱动器的按键求并范围内
 * （驱动器只覆盖 {@code packages/api-contracts} 与台账）。本模块的记录放进公共文件会让
 * 两条车道在同一处各追加一段，行级三方合并必然冲突。模块自成一个文件则天然无交集，
 * 与 {@code CategoryAttributeRequirementForms} 的做法一致。
 *
 * <p>蓝图 3.7.2.1：
 * 「维护裸品物理参数（长宽高、净重、毛重、包装方式）与安全属性（是否带电、是否液体、
 * 是否危险品、是否磁性），决定可走哪些物流渠道与是否需要特殊申报。」
 */
public final class PhysicalSafetyModels {
  private PhysicalSafetyModels() {}

  // ============================================================ 物理档案

  /**
   * SKU 物理档案：物理参数（{@code sku_package}）+ 安全属性（{@code sku_safety_attribute}）
   * + 现算的派生值。
   *
   * <p><b>{@code safetyCollected} 是本模块最重要的一格</b>（V28 规则 B2）：
   * {@code false} 表示「还没人采集过安全属性」，与「采集了、结论是都不带」是<b>两件事</b>，
   * 后者的下一步动作是「确认无需申报」，前者的下一步动作是「去 SKU 建档补采集」。
   * 渠道判定在 {@code false} 时一律拒发（{@link PhysicalSafetyRules#verdict}），
   * 正是为了让「不填就能绕过申报」这条路走不通。
   *
   * <p>派生值（{@code volumeCm3} / {@code volumetricWeightKg} / {@code chargeableWeightKg}）
   * 全部为 {@code null} 时表示<b>算不出来</b>，不是 0 —— 规则 R1：缺一维不给体积。
   */
  public record PhysicalSafetyProfile(
      UUID skuId,
      String skuCode,
      String skuName,
      String spuCode,
      @Schema(nullable = true) BigDecimal lengthMm,
      @Schema(nullable = true) BigDecimal widthMm,
      @Schema(nullable = true) BigDecimal heightMm,
      @Schema(nullable = true) BigDecimal netWeightG,
      @Schema(nullable = true) BigDecimal grossWeightG,
      String packagingType,
      boolean safetyCollected,
      boolean containsBattery,
      boolean containsLiquid,
      boolean dangerousGoods,
      boolean isMagnetic,
      String safetyNote,
      @Schema(nullable = true) Long safetyVersion,
      @Schema(nullable = true) BigDecimal volumeCm3,
      @Schema(nullable = true) BigDecimal divisorCm3PerKg,
      @Schema(nullable = true) BigDecimal volumetricWeightKg,
      @Schema(nullable = true) BigDecimal chargeableWeightKg,
      List<String> activeFlags,
      List<String> requiredDeclarations,
      boolean hasVolume) {}

  /**
   * 保存物理与安全属性的入参。
   *
   * <p><b>一次提交同时写两张表</b>：物理参数进 {@code sku_package}、安全属性进
   * {@code sku_safety_attribute}。这是有意的 —— 蓝图的职责句把两者写在同一句里
   * （「维护裸品物理参数…与安全属性…」），运营在 SKU 建档时也是一次填完。
   * 分成两个端点只会让「填了尺寸忘了填安全属性」变成常态，而那正是规则 R2 要堵的洞。
   *
   * <p>{@code safetyVersion} 缺省 0（尚无行）；{@code allSafetyFalse} 为真时删除该行，
   * 回到「未采集」状态 —— 「确认什么都不带」和「什么都没填」在业务上不是一回事。
   */
  public record PhysicalSafetyInput(
      @Schema(nullable = true) @DecimalMin("0") BigDecimal lengthMm,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal widthMm,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal heightMm,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal netWeightG,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal grossWeightG,
      @Schema(nullable = true) String packagingType,
      boolean safetyCollected,
      boolean containsBattery,
      boolean containsLiquid,
      boolean dangerousGoods,
      boolean isMagnetic,
      @Schema(nullable = true) String safetyNote,
      @PositiveOrZero long safetyVersion,
      @PositiveOrZero long packagingVersion) {}

  /** 写入结果。回传写入后的档案，界面不必为了看结果再拉一次。 */
  public record PhysicalSafetySaved(PhysicalSafetyProfile profile, boolean packagingChanged) {}

  // ============================================================ 筛选查询

  /**
   * 按安全属性筛选 SKU（供 10 物流渠道筛选）。
   *
   * <p>三个 {@code containsXxx} 都是 {@link Boolean} 而不是 {@code boolean}：
   * {@code null} = 不筛，{@code false} = 筛「明确不带这一类」。
   * 运营真正会问「哪些货<b>不是</b>危险品」，只支持筛 {@code true} 就答不了。
   */
  public record PhysicalSafetyQuery(
      String keyword,
      @Schema(nullable = true) Boolean containsBattery,
      @Schema(nullable = true) Boolean containsLiquid,
      @Schema(nullable = true) Boolean dangerousGoods,
      @Schema(nullable = true) Boolean isMagnetic,
      @Schema(nullable = true) Boolean safetyCollected,
      @Schema(nullable = true) Boolean requiresDeclaration,
      @Schema(nullable = true) String packagingType,
      int page,
      int size) {}

  /** 筛选结果行。带派生体积与申报标记，供渠道适配页直接读。 */
  public record PhysicalSafetyRow(
      UUID skuId,
      String skuCode,
      String skuName,
      String spuCode,
      boolean safetyCollected,
      boolean containsBattery,
      boolean containsLiquid,
      boolean dangerousGoods,
      boolean isMagnetic,
      String flagsText,
      @Schema(nullable = true) BigDecimal volumeCm3,
      @Schema(nullable = true) BigDecimal grossWeightG,
      String packagingType,
      List<String> requiredDeclarations,
      String updatedAt) {}

  public record PhysicalSafetyPage(
      List<PhysicalSafetyRow> items, int page, int size, long total, int totalPages) {}

  // ============================================================ 渠道判定

  /**
   * 渠道可用性判定入参。
   *
   * <p><b>渠道限制由调用方给</b>（规则 R3）：本模块<b>不建渠道表</b>，渠道与限制是
   * 10 物流的事实。本模块提供的是「按你给的限制，这个 SKU 能不能走」。
   */
  public record ChannelVerdictInput(
      @NotNull String channelCode,
      @NotNull Boolean allowBattery,
      @NotNull Boolean allowLiquid,
      @NotNull Boolean allowDangerousGoods,
      @NotNull Boolean allowMagnetic,
      @NotNull Boolean requirePhysicalParameters,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal divisorCm3PerKg) {}

  /** 判定结果。{@code blockers} 为空即 {@code shippable = true}，不为 null。 */
  public record ChannelVerdictView(
      UUID skuId,
      String skuCode,
      String channelCode,
      boolean shippable,
      List<String> blockers,
      List<String> blockerLabels,
      List<String> declarations,
      List<String> declarationLabels,
      @Schema(nullable = true) BigDecimal volumeCm3,
      @Schema(nullable = true) BigDecimal divisorCm3PerKg,
      @Schema(nullable = true) BigDecimal volumetricWeightKg,
      @Schema(nullable = true) BigDecimal chargeableWeightKg,
      String note) {}

  // ============================================================ 装箱体积核算

  /** 装箱核算的一行入参（8 拣货）。 */
  public record CartonLineInput(@NotNull UUID skuId, @NotNull @DecimalMin("0") BigDecimal quantity) {}

  /**
   * 装箱核算入参。
   *
   * @param cartonVolumeCm3 每箱可用容积（cm³）
   * @param cartonMaxGrossWeightG 每箱限重（g）
   */
  public record CartonCalcInput(
      @NotNull List<@NotNull CartonLineInput> lines,
      @NotNull @DecimalMin("0") BigDecimal cartonVolumeCm3,
      @NotNull @DecimalMin("0") BigDecimal cartonMaxGrossWeightG) {}

  /** 装箱核算的一行结果。{@code note} 非空时说明这一行的件数是怎么来的。 */
  public record CartonLineView(
      UUID skuId,
      String skuCode,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) BigDecimal unitVolumeCm3,
      @Schema(nullable = true) BigDecimal netWeightG,
      @Schema(nullable = true) BigDecimal grossWeightG,
      int unitsPerCartonByVolume,
      int unitsPerCartonByWeight,
      int unitsPerCarton,
      boolean limitedByWeight,
      int cartonsNeeded,
      @Schema(nullable = true) BigDecimal totalVolumeCm3,
      String note) {}

  public record CartonCalcView(
      List<CartonLineView> lines,
      @Schema(nullable = true) BigDecimal cartonVolumeCm3,
      @Schema(nullable = true) BigDecimal cartonMaxGrossWeightG,
      int totalCartons,
      @Schema(nullable = true) BigDecimal totalVolumeCm3,
      @Schema(nullable = true) BigDecimal volumeUtilizationPercent,
      String note) {}
}
