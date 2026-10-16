package com.starcon.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

/**
 * 4.2.3.1 平台类目映射的 DTO。
 *
 * <p>刻意独立于 {@link CatalogModels}：那个文件已经 200KB 量级，且历史上出现过
 * 两个模块的同名 record 在 codegen 合并进 {@code catalog.ts} 时互相覆盖
 * （见 15.4.2.2 会话记录）。本模块的 record 全部带 {@code PlatformCategory} / {@code PlatformAttribute} 前缀，
 * 并单独成文件，让「这个类型属于哪个模块」一眼可见。
 *
 * <p>可空列一律标 {@code @Schema(nullable = true)}：不标的话 OpenAPI 会把它们写成必填，
 * 而它们在业务上确实可以为空（例如尚未绑定内部属性的平台必填属性）。
 */
public final class PlatformCategoryMappingModels {
  private PlatformCategoryMappingModels() {}

  // ============================================================ 映射

  /**
   * 映射台账的一行。
   *
   * <p>{@code platformCategoryPath} 是平台树上从根到该节点的名称串（如 {@code Electronics / Computers}）：
   * 平台类目只有编码时人在界面上认不出来，而刊登时真正要选的是那条路径。
   */
  public record PlatformCategoryMappingRow(
      UUID id,
      UUID internalCategoryId,
      String internalCategoryCode,
      String internalCategoryName,
      String platformCode,
      String siteCode,
      UUID platformCategoryId,
      String platformCategoryCode,
      String platformCategoryName,
      String platformCategoryPath,
      boolean isDefault,
      boolean enabled,
      /** 多对多的另一侧：这个平台类目在同平台同站点下被多少个内部分类映射到。等于 1 时它是「只服务于一个内部分类」的专属类目。 */
      long mappedInternalCount,
      /** 本条映射下启用的平台属性规则数（平台必填 / 推荐 / 可选合计）。 */
      long attributeRuleCount,
      /** 其中平台必填（REQUIRED）的条数。 */
      long requiredCount,
      /** 其中还没绑定内部属性的条数——刊登表单生成时会缺字段。 */
      long unmappedRequiredCount,
      long version,
      String createdAt,
      String updatedAt) {}

  /**
   * 新建 / 改一条映射。
   *
   * <p>{@code version} 只在更新时有意义（乐观锁），新建时传 0 即可。
   * {@code isDefault} 与 {@code enabled} 都是 boolean 且**没有三态**：
   * 「不传」与「传 false」在这个契约里是同一件事，因为映射要么存在要么不存在，
   * 不存在时整个 body 都没有意义——不存在需要清空默认的场景（映射没了就自然没了）。
   */
  public record PlatformCategoryMappingInput(
      @NotNull UUID internalCategoryId,
      @NotBlank @Size(max = 32) String platformCode,
      @NotBlank @Size(max = 32) String siteCode,
      @NotNull UUID platformCategoryId,
      boolean isDefault,
      boolean enabled,
      @PositiveOrZero long version) {}

  /** 分页台账。total 是**筛选后**的总数，与 items 同源同条件，不做假分页。 */
  public record PlatformCategoryMappingPage(
      List<PlatformCategoryMappingRow> items, long total, int page, int size) {}

  // ============================================================ 概览与候选

  /**
   * 概览：映射规模、覆盖率与缺口。
   *
   * <p>{@code mappedInternalCategoryCount} 与 {@code internalCategoryCount} 的比值就是「有多少内部分类已经能刊登」，
   * 这比「映射条数」更能反映真实进度——一个内部分类配 10 条映射不代表 10 个类目能刊登。
   */
  public record PlatformCategoryMappingOverview(
      long mappingCount,
      long enabledMappingCount,
      long defaultMappingCount,
      long internalCategoryCount,
      long mappedInternalCategoryCount,
      long platformCategoryCount,
      long mappedPlatformCategoryCount,
      long platformCount,
      long siteCount,
      long attributeRuleCount,
      long unmappedRequiredCount,
      List<PlatformSiteCoverage> coverage) {}

  /** 单个「平台 × 站点」的覆盖情况。页面右侧那组卡片就是它的一行。 */
  public record PlatformSiteCoverage(
      String platformCode,
      String siteCode,
      long mappingCount,
      long internalCategoryCount,
      long mappedInternalCategoryCount,
      long unmappedRequiredCount) {}

  /**
   * 平台与站点的候选项。
   *
   * <p>{@code platforms} 来自租户**已建的 PLATFORM 分类树**（真实数据，不是硬编码常量），
   * {@code siteCodes} 来自该平台上**已在用的**站点码。
   *
   * <p>为什么不给一张权威站点码表：平台与站点的码表在 channel 服务（全局码表），
   * AGENTS.md 禁止跨服务读取，catalog 侧再建一份就成了第二个写入所有者。
   * 这里的取舍是「候选来自真实树与真实历史数据，允许录入新站点码并做形态校验」，
   * 代价（填了 channel 里不存在的站点码）在迁移末尾的「与 channel 的边界」一节里写明了。
   */
  public record PlatformSiteOption(
      String platformCode, List<String> siteCodes, long mappingCount, long categoryCount) {}

  /** 内部属性字典候选项（供「平台的材质对应内部哪个属性」下拉）。 */
  public record InternalAttributeOption(
      UUID id, String code, String name, String dataType, boolean multiple) {}

  // ============================================================ 平台类目属性映射规则

  /**
   * 平台的某个必填/选填属性对应内部哪个属性。
   *
   * <p>{@code internalAttributeId} 可空且**允许为空**：平台要求填某个属性、而租户字典里还没有对应条目，
   * 这是一个必须被记下来的事实（见 V145 开头决策 D）。记下来才能出现在覆盖缺口统计里，
   * 也才能让 4.2.3.2 拿它当阻断项。
   */
  public record PlatformAttributeMappingRow(
      UUID id,
      UUID mappingId,
      String platformAttributeCode,
      String platformAttributeName,
      @Schema(nullable = true) UUID internalAttributeId,
      @Schema(nullable = true) String internalAttributeCode,
      @Schema(nullable = true) String internalAttributeName,
      String requirement,
      String platformDataType,
      boolean multiple,
      List<String> allowedValues,
      String transform,
      boolean enabled,
      int position,
      long version,
      String createdAt,
      String updatedAt) {}

  public record PlatformAttributeMappingInput(
      @NotBlank @Size(max = 64) String platformAttributeCode,
      @NotBlank @Size(max = 200) String platformAttributeName,
      @Schema(nullable = true) UUID internalAttributeId,
      @NotBlank String requirement,
      @NotBlank String platformDataType,
      boolean multiple,
      @Schema(nullable = true) @Size(max = 200) List<@Size(max = 100) String> allowedValues,
      String transform,
      boolean enabled,
      @PositiveOrZero int position,
      @PositiveOrZero long version) {}

  /**
   * 必填属性清单 + 绑定状态。这是蓝图点名的第二个输出「平台类目必填属性清单」，
   * 也是 4.2.1.1 表单生成直接消费的形状。
   */
  public record PlatformRequiredAttribute(
      String platformAttributeCode,
      String platformAttributeName,
      String requirement,
      String platformDataType,
      boolean multiple,
      List<String> allowedValues,
      String transform,
      @Schema(nullable = true) UUID internalAttributeId,
      @Schema(nullable = true) String internalAttributeCode,
      @Schema(nullable = true) String internalAttributeName,
      /** 是否已绑定内部属性。false 表示这条必填属性还没有内部来源，刊登表单生成时会缺字段。 */
      boolean mapped) {}

  /**
   * 「内部分类 × 平台 × 站点」解析到的刊登目标与必填清单。
   *
   * <p>4.2.1.1 只需要这一个结果就能生成表单：目标平台类目 + 每个必填字段的来源与渲染方式，
   * 不必自己再拼「映射 → 属性规则 → 内部属性」三段查询。
   */
  public record PlatformCategoryResolveResult(
      UUID mappingId,
      UUID internalCategoryId,
      String internalCategoryName,
      String platformCode,
      String siteCode,
      UUID platformCategoryId,
      String platformCategoryCode,
      String platformCategoryName,
      String platformCategoryPath,
      boolean isDefault,
      List<PlatformRequiredAttribute> requiredAttributes,
      long unmappedRequiredCount) {}

  /** 映射详情：映射行本身 + 它的属性规则列表 + 该平台类目的其他内部分类（多对多的另一侧）。 */
  public record PlatformCategoryMappingDetail(
      PlatformCategoryMappingRow mapping,
      List<PlatformAttributeMappingRow> attributes,
      List<MappedInternalCategory> alsoMappedFrom,
      PlatformMappingSummary summary) {}

  /** 同一个平台类目还被哪些内部分类映射到——多对多的另一侧，必须显式可见。 */
  public record MappedInternalCategory(UUID internalCategoryId, String code, String name, boolean enabled) {}

  public record PlatformMappingSummary(
      long attributeRuleCount, long requiredCount, long mappedRequiredCount, long unmappedRequiredCount) {}
}
