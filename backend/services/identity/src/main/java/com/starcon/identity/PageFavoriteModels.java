package com.starcon.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 1.2.2.1 常用页面收藏 —— identity 侧的「个人收藏清单」形状。
 *
 * <p>蓝图原文（本模块要兑现的全部内容）：
 *
 * <blockquote>
 *
 * <b>职责</b>：用户把高频页面（某店铺订单、某仓库库存、某报表）收藏到工作台侧边快捷区，
 * 形成个人工作流。
 *
 * <b>输入</b>：用户收藏/取消收藏操作；页面注册表（前端路由 + 标题 + 所属系统）。
 *
 * <b>输出</b>：收藏列表（有序、支持拖拽排序）；点击直达。
 *
 * <b>关系</b>：上游 = 前端页面注册表；下游 = 各系统页面。共享数据：纯个人配置，存
 * {@code user_preference}，不含业务数据，不受租户角色权限以外的限制，
 * 但仍受页面级权限约束（无权限的收藏自动隐藏）。
 *
 * </blockquote>
 *
 * <p><b>与 {@link UserPreferenceModels} 分开</b>：1.4.1.2 记的是「列表页的列怎么排」，
 * 本模块记的是「我收藏了哪些页面」。两者共用同一张 {@code user_preference} 表、
 * 各自用<b>自己的 {@code page_key}</b>（{@code dashboard.favorites} vs 列表页各自的 key），
 * 互不覆盖。
 *
 * <p><b>与 {@link ShortcutTemplateModels} 分开</b>：1.2.2.2 记的是「岗位预置的快捷入口模板」
 * 与「个人对模板的覆盖项」，是<b>按角色/团队预置</b>的；
 * 本模块记的是<b>用户自己收藏</b>的页面。两者是两件事，不共用表、不共用端点。
 */
public final class PageFavoriteModels {

  private PageFavoriteModels() {}

  /**
   * 一条收藏。
   *
   * @param href 站内相对路径（点击直达的目标），例如 {@code /orders}
   * @param label 页面标题（来自前端页面注册表）
   * @param system 所属系统：{@code dev} / {@code ops} / {@code logistics} / {@code storage} / {@code top}
   * @param pageCode 目标页面的权限页码；空串 = 该页没有页面级读权限（因此不受权限闸约束）
   * @param order 排序位，**等于它在数组里的下标**（0 起）
   */
  public record PageFavoriteItem(
      String href,
      String label,
      String system,
      String pageCode,
      int order) {}

  /**
   * 我的收藏清单。
   *
   * @param items **可见**的收藏（已按顺序排好）—— 无页面读权限的那些被自动隐藏在这里
   * @param hiddenByPermission 因当前没有该页面的读权限而被隐藏的条数
   * @param total 存储的收藏总条数（含被隐藏的）
   */
  public record PageFavoriteSet(
      List<PageFavoriteItem> items, int hiddenByPermission, int total) {}

  /** 收藏入参：一条收藏的四个字段（{@code pageKey} 不由客户端给 —— 它固定是本模块的 key）。 */
  public record PageFavoriteInput(
      String href, String label, String system, @Schema(nullable = true) String pageCode) {}

  /**
   * 排序入参：把 {@code href} 移到**可见清单**的第 {@code toIndex} 位。
   *
   * <p>用「移动到第 N 位」而不是「整份顺序覆盖」：前者是**增量**语义，
   * 客户端与服务端并发改动时不会互相把对方的顺序整份冲掉。
   */
  public record PageFavoriteMove(String href, int toIndex) {}
}
