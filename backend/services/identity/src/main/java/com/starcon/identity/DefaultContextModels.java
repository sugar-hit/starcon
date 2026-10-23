package com.starcon.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 1.4.2.2 默认店铺与仓库 —— identity 侧的「默认上下文」形状。
 *
 * <p>蓝图原文：「<b>职责</b>：设定默认进入的店铺与默认操作的法人/仓库，减少每次进入的重复选择。
 * <b>输入</b>：用户选择；用户有权限的店铺/仓库集合。
 * <b>输出</b>：默认上下文，注入后续页面初始筛选。
 * <b>关系</b>：上游 = 02 店铺、08 仓库、17 权限；下游 = 09、07、13 各页面的默认筛选。
 * <b>默认值必须在权限范围内</b>，权限变更后自动失效并回落。」
 *
 * <p><b>为什么是「三段独立维度」而不是一个笼统的 currentContext</b>：店铺（02）、
 * 法人（17.1）、仓库（08）分属三个服务、三个权限闸，任何一个读不到都不该把另外两个也拖垮。
 * 所以每个维度**各自**带一份来源状态（{@link DefaultContextSourceStatus}），读不到就只报那一个维度读不到。
 *
 * <p><b>载荷为什么是 {@code Map<String, Object>} 而不是定长 record</b>：本模块**复用**
 * 1.4.1.2 的 {@code user_preference} 表（页面 key {@code default-context}），
 * 与 1.4.1.1（工作台布局）、1.4.2.1（语言与时区）共用同一张表。
 * 定长 record 只能描述本模块这一种形状，会把兄弟模块逼到另建一张表 ——
 * 那正是「一页一行 + JSONB」要避免的事。形状由 {@link DefaultContextRules} 管，
 * 结构与体积由 1.4.1.2 的 {@link UserPreferencePayloadRules} 管。
 */
public final class DefaultContextModels {

  private DefaultContextModels() {}

  /** 维度标识：店铺 / 法人 / 仓库。 */
  public static final String SHOP = "SHOP";

  public static final String LEGAL_ENTITY = "LEGAL_ENTITY";
  public static final String WAREHOUSE = "WAREHOUSE";

  /**
   * 一个维度的来源状态。
   *
   * @param dimension 维度（SHOP / LEGAL_ENTITY / WAREHOUSE）
   * @param available 这个维度的候选集**读到了**吗。false 时 {@code items} 没有意义
   * @param source 候选集的唯一来源（授权只读端点），读不到时也写出来，便于排查
   * @param reason 读不到 / 被上游拒绝的原因（available=true 时为 null）
   * @param ownerModule 缺口的归属模块（available=true 时为 null）
   */
  public record DefaultContextSourceStatus(
      String dimension,
      boolean available,
      String source,
      @Schema(nullable = true) String reason,
      @Schema(nullable = true) String ownerModule) {}

  /** 一个可选店铺（来自 channel 的授权只读端点）。 */
  public record DefaultContextShopOption(
      UUID id, String code, String name, String platformName, String status) {}

  /** 一个可选法人（来自 identity 自己的法人档案，17.1 法人主体）。 */
  public record DefaultContextLegalEntityOption(UUID id, String code, String name, String status) {}

  /** 一个可选仓库（来自 warehouse 的授权只读端点）。 */
  public record DefaultContextWarehouseOption(
      UUID id, String code, String name, String status, boolean defaultWarehouse) {}

  /**
   * 三个维度的候选集 + 各自的来源状态。
   *
   * <p>{@code shops} / {@code legalEntities} / {@code warehouses} 各自可能为空：
   * 空 = 该维度**确实没有**候选（读到了、但是空的）；读不到的情况看 {@code sources}。
   */
  public record DefaultContextOptions(
      List<DefaultContextShopOption> shops,
      List<DefaultContextLegalEntityOption> legalEntities,
      List<DefaultContextWarehouseOption> warehouses,
      List<DefaultContextSourceStatus> sources) {}

  /**
   * 保存默认上下文时的入参。
   *
   * <p><b>PUT 是整体覆盖</b>：请求体里给 null 的维度等于清空它。
   * 「只改店铺、顺带把仓库也清了」是整体覆盖语义的自然结果，不是 bug ——
   * 界面上的表单始终提交三个维度（清空就是 null），所以不会出现意外清空。
   */
  public record DefaultContextInput(
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) UUID warehouseId) {}

  /** 一个生效中的默认选择（id + 展示用编码名）。 */
  public record DefaultContextSelection(UUID id, String code, String name, String status) {}

  /**
   * 一条**已失效**的默认选择。
   *
   * <p>「失效」有三种，理由必须写清是哪一种：
   * 来源读不到（无法确认）/ 已不在权限范围内或已被删除 / 已停用归档。
   */
  public record DefaultContextStaleSelection(String dimension, UUID id, String name, String reason) {}

  /**
   * 当前生效的默认上下文。
   *
   * @param stored 服务端**确实存过**这一行（存过但全部失效时为 true，失效项在 {@code stale} 里）
   * @param shop 生效的默认店铺（失效或没设时为 null）
   * @param legalEntity 生效的默认法人（失效或没设时为 null）
   * @param warehouse 生效的默认仓库（失效或没设时为 null）
   * @param stale 失效了的默认项（权限变更 / 停用 / 上游读不到）
   * @param sources 三个维度的来源状态
   * @param note 回落说明（回落到哪个默认值、为什么没有可回落项）
   * @param updatedAt 最近一次保存时刻；从未保存过时为 null
   */
  public record DefaultContextView(
      boolean stored,
      @Schema(nullable = true) DefaultContextSelection shop,
      @Schema(nullable = true) DefaultContextSelection legalEntity,
      @Schema(nullable = true) DefaultContextSelection warehouse,
      List<DefaultContextStaleSelection> stale,
      List<DefaultContextSourceStatus> sources,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) OffsetDateTime updatedAt) {}

  /** 能力声明：本模块哪些维度真接通、哪些没接通、缺什么、归谁。 */
  public record DefaultContextCapabilities(
      List<DefaultContextSourceStatus> sources, List<String> rules, List<String> boundaries) {}
}
