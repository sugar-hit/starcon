package com.starcon.platform;

import com.starcon.platform.ExternalIntegrationModels.ExternalIntegrationCategory;
import com.starcon.platform.ExternalIntegrationPort.ExternalSyncCommand;
import com.starcon.platform.ExternalIntegrationPort.ExternalSyncContext;
import com.starcon.platform.ExternalIntegrationPort.ExternalSyncOutcome;
import java.math.BigDecimal;

/**
 * {@link ExternalIntegrationPort} 的**当前实现基类**：逐个如实答「未接入」。
 *
 * <p>与 platform 的 {@link AbstractUnavailablePaymentProviderAdapter}、
 * {@link UnavailableFinanceSoftwarePushPort}、{@link UnavailableAiTextGenerationPort}、
 * crm 的 {@code UnavailablePlatformMessageSendPort} <b>完全同构</b>：端口先立起来，
 * 实现逐能力答「未接入 + 缺什么 + 归谁」，<b>不返回任何成功回执</b>。
 *
 * <p>🔴 <b>本实现一次成功记录都不写</b>：不落 {@code SUCCEEDED} 的同步记录、
 * 不落外部系统侧的单号、不落任何「外部系统那边已经同步过了」的痕迹。
 * 服务层拿到 {@code NOT_CONNECTED} 之后如实记进同步台账
 * （status = NOT_CONNECTED、provider_notified = false、写明缺什么归谁），
 * <b>不会</b>记成成功。
 *
 * <p>⚠️ <b>这不代表本模块是半成品</b>：需要外部适配器的只有「对某个外部系统的实际调用」
 * 这一段。与外部系统有没有直连通道，<b>不影响</b>下面这些**本系统内部能力**的真实生效：
 * 对接连接配置（密钥<b>引用</b>）、同步任务配置、每个对接<b>独立</b>的健康状态、
 * 同步记录台账、以及「外部侧已完成」的同步人工登记（今天就能用）。
 *
 * <p><b>子类只需给出「我是谁」与「为什么没接通」</b>；接上一个真实适配器时，
 * 覆盖对应方法即可（协议细节由适配器自己负责，本基类<b>不猜任何外部系统的接口形状</b>）。
 */
abstract class AbstractUnavailableExternalIntegrationAdapter implements ExternalIntegrationPort {

  /**
   * 未接入的**归属模块**：<b>18.2.3</b>（其他对接）。
   *
   * <p>为什么不是 18.2.3.1（就是本模块）：外部系统适配器（1688 / 淘宝 / 海外仓 / 物流商 /
   * 聚合认证 / 自建 BI 的对接）不是某一格 L4 独有的，它是 18.2 卷「外部系统对接」下
   * 「对外接外部系统通道」这件事的<b>外部通道</b>。
   * 蓝图里没有单独列一个「外部系统接入」的 L4 模块，所以这里如实写成<b>卷级归属</b>（18.2.3）。
   */
  static final String OWNER_MODULE = "18.2.3";

  @Override
  public boolean connected() {
    return false;
  }

  /**
   * 未接通的原因（**逐个外部系统**）：说清「缺什么、归谁、现在怎么办」。
   *
   * <p>子类覆盖它以给出<b>逐个外部系统</b>的文案；基类给出通用的兜底文案。
   */
  @Override
  public String unavailableReason() {
    return "「"
        + displayName()
        + "」的**直连适配器未接入**：本仓目前没有任何真实的"
        + categoryLabel()
        + "适配器"
        + "（backend / services / tools 下不存在对应的客户端），"
        + "也没有任何真实的外部系统凭据与网络通道，"
        + "所以「直接跟"
        + displayName()
        + "说话」这一段没有通道。"
        + "该能力归 **18.2.3 其他对接** 卷（外部通道是 18.2 各模块共用的外部通道）。"
        + "在此之前可以走**本地台账**这条路："
        + "对接连接配置（只存密钥**引用**）、同步任务配置、"
        + "每个对接**独立**的健康状态、同步记录台账、"
        + "以及「外部侧已完成」的同步人工登记"
        + "都是真实生效的 —— 外部系统那边跑完同步，在这里补一条记录，"
        + "这个对接的健康状态就如实反映了现状。";
  }

  /** 类别中文名（说清「这是哪一类外部系统」）。 */
  protected String categoryLabel() {
    var category = ExternalIntegrationCategory.parse(category());
    return category == null ? category() : category.label();
  }

  @Override
  public ExternalSyncOutcome executeSync(
      ExternalSyncContext context, ExternalSyncCommand command) {
    return new ExternalSyncOutcome(
        "NOT_CONNECTED",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        "",
        unavailableReason(),
        "NOT_CONFIGURED",
        false,
        "外部通道未接入，未与外部系统核对（本系统只记下了这次发起，未向外部系统登记）");
  }
}
