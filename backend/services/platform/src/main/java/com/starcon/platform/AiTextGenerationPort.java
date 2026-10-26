package com.starcon.platform;

import java.util.List;

/**
 * 18.4.2.1 的**AI 文本生成端口**：把「哪个 AI 供应商能真的翻出文案」这件事**显式化**。
 *
 * <p>蓝图 18.4.2.1 的输出句写着「翻译/生成结果 + 置信度提示 + 可人工编辑」，
 * 而**这条通道在本仓当前不存在**：backend / services / tools 下**没有任何**真实的
 * AI / LLM 供应商适配器（openai / anthropic / qwen / deepseek 之类的客户端一个都没有）。
 *
 * <p>所以本仓的处置是：<b>端口先立起来，实现如实答「未接入」</b>。
 * 供应商适配器交付之后，只要换掉实现类，本模块的生成入口立刻变成真的 ——
 * 而在此之前，服务层据此**拒绝**生成动作，<b>一次数据都不写</b>。
 *
 * <p>⚠️ 为什么**不能**在这里放一个「假 vendor」：一条返回成功结果的假通道
 * 会让「文案已经翻译好了」在数据上看起来发生过，而现场一个字都没翻译出来 ——
 * 运营会拿着一条不存在的译文去发版，而蓝图加粗句「<b>翻译结果必须人工确认后发布</b>，
 * 直接发机器翻译是品牌风险」防的正是这件事。
 *
 * <p>⚠️ 与 {@code crm} 的 {@code UnavailablePlatformMessageSendPort} <b>完全同构</b>：
 * 端口 + 逐能力如实答「未接入 + 缺什么 + 归谁」+ <b>不返回任何成功回执</b>。
 */
public interface AiTextGenerationPort {

  /**
   * 回答「AI 文本生成通道接上了没有」。
   *
   * <p>不传参 —— 当前只有一个能力维度（文本生成），传进来的平台清单会是**写死的数据源**。
   */
  List<AiAvailability> availability();

  /**
   * 生成 / 翻译一段文案。
   *
   * @param request 生成请求（源文本 / 两端语言 / 风格要求 / 必须保护的术语）
   * @param bearer 调用方的 bearer（转发给供应商适配器用；未接入的实现用不到）
   * @return 生成结论；未接入时 {@code connected=false} 且 {@code generated=false}，
   *     {@code reason} 说明缺什么、归谁
   */
  AiGenerationOutcome generate(AiGenerationRequest request, String bearer);

  /**
   * 一个 AI 能力的接入状态。
   *
   * @param ownerModule 该能力交付之后才会变成 true 的归属模块
   */
  record AiAvailability(
      String capability, boolean connected, String reason, String ownerModule) {}

  /** 一次生成的结论。 */
  record AiGenerationOutcome(
      boolean connected,
      boolean generated,
      String text,
      String model,
      String reason,
      String ownerModule) {}

  /**
   * 生成请求。字段刻意与 {@code platform_text_task} 一一对应，适配器交付时直接映射。
   *
   * @param protectedTerms 必须原样保护的术语（品牌词 / 专业术语）—— 品牌词译错是品牌风险，
   *     这一列必须真的进到供应商的提示词里，不能只是本模块自己记着。
   */
  record AiGenerationRequest(
      String contentType,
      String sourceText,
      String sourceLocale,
      String targetLocale,
      String styleRequirement,
      List<String> protectedTerms) {}
}
