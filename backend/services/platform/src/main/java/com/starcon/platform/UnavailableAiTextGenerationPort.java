package com.starcon.platform;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * {@link AiTextGenerationPort} 的**当前实现**：如实答「未接入」。
 *
 * <p>与 {@code crm} 的 {@code UnavailablePlatformMessageSendPort} <b>完全同构</b>：
 * 端口先立起来，实现逐能力答「未接入 + 缺什么 + 归谁」，<b>不返回任何成功回执</b>。
 *
 * <p>🔴 <b>本实现一次数据都不写</b>：它不落草稿、不落任务状态、不落任何「已翻译」的痕迹。
 * 服务层拿到 {@code connected=false} 之后直接翻成 <b>409</b> 并结束 ——
 * 蓝图加粗句「<b>翻译结果必须人工确认后发布</b>」防的正是「一条假的成功结果」。
 *
 * <p>⚠️ <b>这不代表本模块是半成品</b>：需要 AI 的只有「把源文翻成另一种语言」那一段。
 * 与 AI 无关的部分**全部真做**：术语保护、占位符完整性、违禁词命中、长度/字符集校验、
 * 置信度的可解释计算、人工编辑、人工确认与发布闸、客服回复模板的变量绑定。
 * 运营现在就能用「人工录入译文 + 确定性检查 + 人工确认发布」这条完整路径。
 */
@Service
public class UnavailableAiTextGenerationPort implements AiTextGenerationPort {

  /**
   * 未接入的**归属模块**：<b>18.4</b>（AI 与智能）。
   *
   * <p>为什么不是 18.4.2.1（就是本模块）：供应商适配器是 18.4 整个卷共用的**基础设施** ——
   * 18.4.1.1 对话助手、18.4.2.2 选品预测、18.4.2.3 异常诊断都缺同一条通道。
   * 蓝图里没有单独列一个「模型接入」的 L4 模块，所以这里如实写成**卷级归属**（18.4），
   * 而不是编一个不存在的 L4 编号。
   *
   * <p>为什么不是 18.4.3.1（使用与配额）：那一格管的是**用量与成本**，
   * 不是「能不能调通模型」—— 配额报表在模型没接上之前只能是 0 行。
   */
  static final String OWNER_MODULE = "18.4";

  @Override
  public List<AiAvailability> availability() {
    return List.of(
        new AiAvailability(
            "TEXT_TRANSLATION",
            false,
            reason(),
            OWNER_MODULE));
  }

  @Override
  public AiGenerationOutcome generate(AiGenerationRequest request, String bearer) {
    // ⚠️ 这里**不**返回一段「翻译好了」的文案：一条假的成功回执会让
    // 「文案已经翻译好了」在数据上看起来发生过，而现场一个字都没翻译出来 ——
    // 运营会拿着它去发版。蓝图加粗句「直接发机器翻译是品牌风险」防的正是这件事。
    return new AiGenerationOutcome(false, false, "", "", reason(), OWNER_MODULE);
  }

  /** 原因文案：说清「缺什么、归谁、现在怎么办」。 */
  private String reason() {
    return "AI 文本生成供应商**未接入**："
        + "本仓目前没有任何真实的 AI / LLM 供应商适配器"
        + "（backend / services / tools 下不存在 openai / anthropic / qwen / deepseek 之类的客户端），"
        + "所以「把源文自动翻成另一种语言」这一段没有通道。"
        + "该能力归 **18.4 AI 与智能** 卷（供应商适配器是 18.4 各模块共用的基础设施）。"
        + "在此之前可以走**人工录入译文**这条路："
        + "术语保护、占位符完整性、违禁词命中、长度与字符集校验、置信度提示"
        + "都是真实生效的确定性检查，录入后同样要经过**人工确认**才能发布。";
  }
}
