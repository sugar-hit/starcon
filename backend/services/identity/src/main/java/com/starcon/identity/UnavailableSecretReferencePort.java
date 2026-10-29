package com.starcon.identity;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * {@link SecretReferencePort} 的**当前实现**：如实答「未接通」。
 *
 * <p>与 platform 的 {@code UnavailableFinanceSoftwarePushPort}、
 * crm 的 {@code UnavailablePlatformEmailPort} **完全同构**：
 * 端口先立起来，实现逐能力答「未接通 + 缺什么 + 归谁」，<b>不返回任何假凭据</b>。
 *
 * <p>🔴 <b>本实现一次凭据都不产出</b>：解析结果永远是「解析不了」，
 * {@code secret} 永远是 null。一条假凭据会让「这个渠道的凭据已经接好了」
 * 在数据上看起来发生过，而现场一个 Webhook 都没接上。
 *
 * <p>⚠️ <b>这不代表本模块是半成品</b>：需要外部通道的只有「真的把消息送出去」这一段。
 * 与外部通道有没有接通，不影响下面几件**本系统内部能力**的真实生效：
 * 渠道登记（只落 secret manager 引用，**凭据不落库**）、偏好过滤（1.3.1.2）、
 * 候选清单（1.3.2.1 的真实命中）、投递记录（成功 / 失败 / 重试 / 告警的**真事实**）。
 */
@Service
public class UnavailableSecretReferencePort implements SecretReferencePort {

  /**
   * 未接通的**归属模块**：<b>18 系统</b>（卷级归属）。
   *
   * <p>为什么是卷级而不是某个 L4：secret manager 是**平台基础设施**
   * （K8s External Secrets / Vault，见 AGENTS.md 安全一节），
   * 它不是任何单一 L4 模块的职责 —— 蓝图里也没有单独列一格。
   * 所以这里如实写成**卷级归属**（18），与 platform 的
   * {@code UnavailableFinanceSoftwarePushPort} 写「18.2」同一套做法。
   */
  static final String OWNER_MODULE = "18 集成与自动化（卷级归属：secret manager 凭据引用解析）";

  @Override
  public boolean available() {
    return false;
  }

  @Override
  public List<Availability> availability() {
    var out = new java.util.ArrayList<Availability>();
    for (var code : ExternalPushRules.CHANNEL_CODES) {
      out.add(new Availability(code, false, reason(), OWNER_MODULE));
    }
    return out;
  }

  @Override
  public Resolution resolve(String secretRef) {
    // ⚠️ 这里**不**返回一条「已解析」了事：一条假凭据会让「这个渠道的凭据已经接好了」
    // 在数据上看起来发生过，而现场一个 Webhook 都没接上。
    return new Resolution(false, null, reason(), OWNER_MODULE);
  }

  /** 原因文案：说清「缺什么、归谁、现在怎么办」。 */
  private String reason() {
    return "secret manager **未接通**：本仓目前没有 secret manager 服务"
        + "（K8s External Secrets / Vault 均未部署），"
        + "所以登记在渠道上的 secret manager 引用**解析不出凭据**。"
        + "该能力归 **18 集成与自动化** 卷（凭据引用解析是平台基础设施，不是任何单一 L4 模块的职责）。"
        + "在此之前渠道登记本身是可用的：库里只落**引用串**，"
        + "凭据本体既不落库、也不会被伪造 —— 投递会如实失败并记下失败原因。";
  }
}
