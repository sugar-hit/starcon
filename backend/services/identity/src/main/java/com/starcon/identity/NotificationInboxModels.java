package com.starcon.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 1.3.1.1 通知列表 —— identity 侧的「通知收件箱个人状态」形状。
 *
 * <p>蓝图原文（本模块要兑现的全部内容）：
 *
 * <blockquote>
 *
 * <b>职责</b>：系统级与业务级通知的统一收件箱，区分严重级别，支持已读/未读、批量处理、按类型订阅。
 *
 * <b>输入</b>：业务事件产生通知（{@code notification} 记录）；用户已读状态。
 *
 * <b>输出</b>：通知流（类型、严重度、时间、关联单据跳转链接）；未读计数。
 *
 * <b>关系</b>：上游 = 各系统事件（06 缺货、09 超时、10 异常、12 对账差异、15 站内信）；
 * 下游 = 对应处理页。<b>通知是投影，允许丢弃后重算</b>，不作为业务事实来源。
 *
 * </blockquote>
 *
 * <p><b>这里只有「个人状态」，没有「通知内容」</b>：通知内容是各域既有事件的只读投影
 * （缺货预警 / 超时明细 / 同步失败 / 对账差异 / 站内信），它们各自住在自己的库里，
 * 前端按各域的**授权只读端点**聚合成一条通知流。本模块落库的是两件东西：
 *
 * <ul>
 *   <li>{@link NotificationReadEntry} —— 哪些通知**我**读过（已读 / 未读）；</li>
 *   <li>{@link NotificationSubscriptions} —— 我订阅哪些通知类型（按类型订阅）。</li>
 * </ul>
 *
 * <p>两件都是**个人维度**的（按 {@code membership_id}，不是按租户共享一份），
 * 都不是业务事实，丢了可以从各域重新算出来通知流、只有个人状态会丢。
 */
public final class NotificationInboxModels {

  private NotificationInboxModels() {}

  /**
   * 一种通知类型（**受控词表**，不是自由文本）。
   *
   * @param code 类型码（与 V373 的 CHECK 约束同词表）
   * @param label 中文名
   * @param owner 归属系统（谁拥有那条事实表）
   * @param source 上游**只读**端点（相对服务前缀），前端按它取投影
   */
  public record NotificationType(
      String code, String label, String owner, String source) {}

  /**
   * 一条「我读过」的记录。
   *
   * @param key 通知键（{@code <类型>:<上游主键>}），前端拿它与通知流对上
   * @param type 通知类型码
   * @param ref 上游主键
   * @param readAt 读到的时刻
   */
  public record NotificationReadEntry(
      String key, String type, String ref, OffsetDateTime readAt) {}

  /**
   * 一批通知的已读状态。
   *
   * @param read 其中**已读**的那些（不在这里的就是未读）
   */
  public record NotificationReadState(List<NotificationReadEntry> read) {}

  /**
   * 批量标记入参：**显式**给出要改的键集合。
   *
   * <p>刻意不接受「全部」「本类型」「本租户」这类范围参数 —— 「批量已读」只对
   * 调用方**点名**的那几条生效，调用方（前端）负责把它限定在当前筛选出的集合内。
   */
  public record NotificationReadRequest(List<String> keys) {}

  /**
   * 批量标记结果。
   *
   * @param read 这些键现在的已读状态（标未读时只有仍处于已读的那些会被列出）
   * @param affected 真正被改动的行数
   */
  public record NotificationReadResult(List<NotificationReadEntry> read, int affected) {}

  /**
   * 我的订阅类型集合（**生效值**：没自定义过时是全部类型）。
   *
   * @param types 类型码列表
   */
  public record NotificationSubscriptions(List<String> types) {}

  /** 订阅入参：我要订阅的类型集合（整体替换，不是增量）。 */
  public record NotificationSubscriptionRequest(List<String> types) {}

  /**
   * 能力声明。
   *
   * @param types 受控词表（前端按它渲染订阅项，避免前端自己写一份词表）
   * @param maxKeysPerRequest 单次批量标记的键数上限
   * @param boundaries **未交付**的相邻模块（前端据此说明「这里没有」而不是假装有）
   */
  public record NotificationInboxCapabilities(
      List<NotificationType> types, int maxKeysPerRequest, List<String> boundaries) {}
}
