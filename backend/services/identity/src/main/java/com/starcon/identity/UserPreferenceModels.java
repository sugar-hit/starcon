package com.starcon.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 1.4.1.2 列表与表格偏好 —— identity 侧的「个人视图状态」形状。
 *
 * <p>与 {@link GlobalSearchRecentModels} 分开：最近搜索记的是「搜过什么」，本模块记的是
 * 「这个列表页上次长什么样」。两者都是纯个人偏好，但载荷形状与页面 key 的语义不同。
 *
 * <p><b>载荷为什么是 {@code Map<String, Object>} 而不是定长 record</b>：
 * 蓝图把「偏好按页面 key 存储，新增字段不影响旧偏好」写成硬约束，
 * 而兄弟模块 1.4.1.1（工作台布局）将来要复用**同一张表**、用它自己的 page_key 存自己的载荷形状
 * （卡片顺序、默认 Tab）。定长 record 只能描述本模块这一种形状，
 * 会把 1.4.1.1 逼到另建一张表 —— 那正是「一页一行 + JSONB」要避免的事。
 * 载荷的结构与体积由 {@link UserPreferencePayloadRules} 在服务端统一把关。
 */
public final class UserPreferenceModels {

  private UserPreferenceModels() {}

  /**
   * 一个页面 key 上的个人视图状态。
   *
   * @param pageKey 页面 key（稳定的页面标识，见 {@link UserPreferencePayloadRules#PAGE_KEY}）
   * @param stored 是否真的存过这一页（false = 从没保存过，界面应当直接用默认值）
   * @param preference 载荷；缺键是**正常**的（老偏好没有新字段），由前端对缺失键套默认值
   * @param createdAt 首次保存时刻；{@code stored=false} 时为 null
   * @param updatedAt 最近一次保存时刻；{@code stored=false} 时为 null
   */
  public record UserPreferenceView(
      String pageKey,
      boolean stored,
      Map<String, Object> preference,
      @Schema(nullable = true) OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime updatedAt) {}

  /** 保存偏好时的入参：只有载荷，没有 pageKey —— pageKey 在路径上。 */
  public record UserPreferenceInput(Map<String, Object> preference) {}

  /** 列出我全部页面的偏好（设置页用）。 */
  public record UserPreferenceList(List<UserPreferenceView> entries) {}
}
