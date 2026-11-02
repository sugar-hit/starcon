package com.starcon.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.4.1.1「对话助手」的传输模型。
 *
 * <p>蓝图 18-integration-and-automation.md 18.4.1.1 原文：
 *
 * <blockquote>
 *
 * 职责：自然语言助手：用中文提问业务问题（"上周哪个店利润最差""哪些 SKU 该补货了"），
 * 返回答案并附数据来源链接；支持追问与上下文延续。 输入：用户问题；当前上下文（租户、数据范围、
 * 当前页面）。 输出：答案 + 数据明细链接 + 建议的下一步操作。 关系：上游 = 各域只读查询接口；
 * 下游 = 无（只读）。 <b>答案必须附明细链接</b>——不给依据的 AI 数字不能用于决策。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code Assistant*} 前缀 —— 刻意不用 {@code Ai*}（那是 18.4.2.1 翻译 /
 * 18.4.2.2 预测 / 18.4.2.3 诊断那一族），也不与 {@code TextTranslationModels} 的
 * {@code Text*} 撞名（契约被 codegen 合并进同一个扁平命名空间，前缀撞车会让读代码的人
 * 以为是一件事）。
 *
 * <p><b>🔴 本模块一个 {@code double} 都没有</b>：金额与数量一律 {@link BigDecimal}
 * （库里 {@code NUMERIC(19,6)}），比率 {@code NUMERIC(7,4)}，时间一律 {@link OffsetDateTime}。
 *
 * <p><b>🔴 「自由问句」在本仓当前拿不到答案</b>（AI 供应商未接入，见
 * {@link AiTextGenerationPort}）：{@link AssistantAnswer} 因此带
 * {@code answered=false} 与一条写明「缺什么、归谁」的 {@code unavailableReason}，
 * <b>它不是一个「助手回答过了」的结果</b> —— 一条假的成功回执会让「AI 已经查过了」
 * 在数据上看起来发生过，而现场一个字都没查出来。
 */
public final class AssistantModels {

  private AssistantModels() {}

  // ================================================================ 能力声明

  /**
   * 一项能力（或一项「还没接通」的能力）。
   *
   * @param available 该能力**现在**能不能用（不是「将来会有」）
   * @param evidence 凭什么这么判（端点 / 表 / 规则的具体名字）
   * @param ownerModule 还没接通的能力，**缺在哪个模块**
   */
  public record AssistantCapability(
      String moduleId, String name, boolean available, String evidence, String ownerModule, String note) {}

  /** 一个「还没交付」的下游 / 兄弟能力。 */
  public record AssistantGap(
      String moduleId, String name, String service, boolean available, String relation, String note) {}

  /** 能力声明总览。 */
  public record AssistantCapabilities(List<AssistantCapability> capabilities, List<AssistantGap> gaps) {}

  // ================================================================ 受控意图目录

  /**
   * 一个**建议的下一步操作**。
   *
   * @param route 前端**真实存在**的页面（相对路径）；不存在 ⇒ 这个建议就是假的
   */
  public record AssistantNextAction(String label, String route, String moduleId) {}

  /**
   * 一个受控业务意图。
   *
   * @param sourceEndpoint 事实来自哪个上游的哪个只读端点（**真端点**）
   * @param sourceCaliber 口径说明：数字怎么来的、算的是哪一段、哪些格子算不出来
   */
  public record AssistantIntentView(
      String intentCode,
      String intentName,
      List<String> questionExamples,
      String sourceService,
      String sourceEndpoint,
      String sourceCaliber,
      String detailRoute,
      List<AssistantNextAction> nextActions) {}

  // ================================================================ 提问

  /**
   * 提问输入。
   *
   * <p>🔴 **没有 tenantId / userId / membershipId**：租户与用户来自**已认证会话**
   * （{@code AccessService.require}），请求体里出现这三个字段会被
   * {@code fail-on-unknown-properties} 直接 400 —— 客户端自称的租户不是租户。
   */
  public record AssistantAskInput(
      @Schema(description = "中文问句（受控问题形态；自由问句会如实答「AI 通道未接通」）")
          String question,
      @Schema(description = "会话 id；不传则新开一个会话（追问时传上一轮的会话 id）")
          UUID sessionId,
      @Schema(description = "显式指定受控意图码（可选；不传则按关键词确定性匹配）")
          String intentCode) {}

  // ================================================================ 答案

  /**
   * 一条明细依据（**可下钻**的事实）。
   *
   * @param factTable **真表名**（下钻的第一跳）
   * @param factField **真字段名**（下钻的第二跳）
   * @param detailRoute 前端**真实存在**的页面（可点的明细链接）
   * @param metricValue {@code null} = 这个数算不出来（**不是 0**）。
   *   ⚠️ **十进制文本**（不是 JSON 数字）：金额与数量是判定口径，
   *   走 JSON number 会在浏览器里被解析成 double 而掉精度 ——
   *   「差一分钱」在报表上是查不出来的。同 18.4.3.1 的 quantity / unitCost 口径。
   */
  public record AssistantEvidence(
      String factService,
      String factTable,
      String factField,
      String factRef,
      String factLabel,
      String metricCode,
      @Schema(nullable = true) String metricValue,
      String metricUnit,
      String detailRoute,
      String detailLabel,
      String caliberNote,
      OffsetDateTime snapshotAt) {}

  /**
   * 一次提问的答案。
   *
   * @param answered 这一轮**出没出结论**。自由问句（AI 通道未接通）时为 {@code false}，
   *     {@code answer} 写明「为什么没出结论」，{@code evidence} 为空 ——
   *     **没有明细链接的答案不许出现**，所以这里宁可不给答案。
   * @param caliber 口径与时间范围（答案里必须带）
   */
  public record AssistantAnswer(
      UUID sessionId,
      UUID turnId,
      int turnNo,
      String question,
      String matchMode,
      String intentCode,
      String intentName,
      boolean answered,
      String answer,
      String caliber,
      String periodCode,
      LocalDate periodFrom,
      LocalDate periodTo,
      String scopeType,
      String shopCode,
      String sourceService,
      String sourceEndpoint,
      List<AssistantEvidence> evidence,
      List<AssistantNextAction> nextActions,
      String unavailableReason,
      String ownerModule) {}

  // ================================================================ 会话

  /** 一轮问答（会话历史里的一行）。 */
  public record AssistantTurnView(
      UUID turnId,
      int turnNo,
      String question,
      String matchMode,
      String intentCode,
      String intentName,
      boolean answered,
      String answer,
      String caliber,
      LocalDate periodFrom,
      LocalDate periodTo,
      OffsetDateTime createdAt,
      List<AssistantEvidence> evidence) {}

  /** 会话上下文 + 问答历史（**追问与上下文延续**的载体）。 */
  public record AssistantSessionView(
      UUID sessionId,
      String intentCode,
      String intentName,
      String shopCode,
      String scopeType,
      String periodCode,
      LocalDate periodFrom,
      LocalDate periodTo,
      int turnCount,
      List<AssistantTurnView> turns) {}
}
