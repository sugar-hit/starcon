package com.starcon.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 1.2.2.2 快捷入口配置 —— identity 侧的形状。
 *
 * <p>本模块有两个「面」：
 *
 * <ul>
 *   <li><b>模板面</b>（{@link ShortcutTemplate}）—— 租户级配置：按岗位/角色/团队预置一批快捷入口。
 *   <li><b>个人面</b>（{@link ShortcutOverride}）—— 当前这个人的覆盖项：对某个入口改一改或直接移除。
 * </ul>
 *
 * <p>两者合起来才是蓝图要的「最终生效的快捷入口集合」（{@link EffectiveShortcutSet}）。
 */
public final class ShortcutTemplateModels {

  private ShortcutTemplateModels() {}

  /**
   * 一条快捷入口。
   *
   * @param key 入口标识；模板内唯一，是「模板项」与「个人覆盖项」的合并单元
   * @param label 展示标题
   * @param pageCode 目标页面码；权限闸就是它（需要 {@code <pageCode>.read}）
   * @param path 站内跳转路径
   * @param icon 图标名（前端按名取图标，取不到就退化成文字）
   * @param position 同一模板内的排序位；小的在前
   */
  public record ShortcutEntry(
      String key,
      String label,
      String pageCode,
      String path,
      @Schema(nullable = true) String icon,
      @Schema(nullable = true) Integer position) {}

  /** 一张岗位/角色快捷入口模板。 */
  public record ShortcutTemplate(
      UUID id,
      String code,
      String name,
      String description,
      String scopeType,
      @Schema(nullable = true) UUID roleId,
      @Schema(nullable = true) UUID departmentId,
      String status,
      int priority,
      List<ShortcutEntry> entries,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 建/改模板的入参。 */
  public record ShortcutTemplateInput(
      String code,
      String name,
      @Schema(nullable = true) String description,
      String scopeType,
      @Schema(nullable = true) UUID roleId,
      @Schema(nullable = true) UUID departmentId,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) Integer priority,
      List<ShortcutEntry> entries) {}

  /** 模板列表（真分页）。 */
  public record ShortcutTemplateList(List<ShortcutTemplate> templates, int total, int limit, int offset) {}

  /** 一条个人覆盖项。 */
  public record ShortcutOverride(
      UUID id,
      String targetKey,
      String action,
      @Schema(nullable = true) String label,
      @Schema(nullable = true) String pageCode,
      @Schema(nullable = true) String path,
      @Schema(nullable = true) String icon,
      @Schema(nullable = true) Integer position,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}
  /** 写个人覆盖项的入参。 */
  public record ShortcutOverrideInput(
      String targetKey,
      String action,
      @Schema(nullable = true) String label,
      @Schema(nullable = true) String pageCode,
      @Schema(nullable = true) String path,
      @Schema(nullable = true) String icon,
      @Schema(nullable = true) Integer position) {}

  /**
   * 最终生效的一条快捷入口。
   *
   * @param source 入口来源；本模块只产出 TEMPLATE（个人覆盖是「改」不是「另起一条」）
   * @param sourceTemplateCode 这条入口命中的是哪张模板
   * @param overridden 是否被个人覆盖过（改过内容或被移除后又改回来）
   */
  public record EffectiveShortcut(
      String key,
      String label,
      String pageCode,
      String path,
      @Schema(nullable = true) String icon,
      int position,
      String source,
      @Schema(nullable = true) String sourceTemplateCode,
      boolean overridden) {}

  /**
   * 最终生效的快捷入口集合（= 模板 + 个人覆盖的合并结果）。
   *
   * @param templateCount 命中并参与合并的模板张数
   * @param overrideCount 当前用户的覆盖项条数
   * @param removedByOverride 被个人移除掉的入口数
   * @param hiddenByPermission 因指向无权访问的页面而被自动隐藏的入口数
   * @param mergeRules 合并规则的原文（界面上要能让人看懂为什么是这个结果）
   */
  public record EffectiveShortcutSet(
      List<EffectiveShortcut> entries,
      int templateCount,
      int overrideCount,
      int removedByOverride,
      int hiddenByPermission,
      List<String> mergeRules) {}

  /** 能力声明：本模块兑现了什么、没兑现什么、边界在哪。 */
  public record ShortcutCapabilities(
      boolean templateManagement,
      boolean personalOverride,
      boolean effectiveSet,
      boolean permissionScopedEntries,
      boolean favoritesList,
      String favoritesOwnerModule,
      List<String> mergeRules,
      List<String> boundaries) {}
}
