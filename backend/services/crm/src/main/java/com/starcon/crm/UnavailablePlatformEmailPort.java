package com.starcon.crm;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 15.1.2.1 邮件收发的<b>平台买家邮件数据端口 · 当前实现</b>：对所有平台都答「未接入」。
 *
 * <p>🔴 <b>这不是 mock，也不是占位实现</b>：它是「这个能力现在不存在」这件事的<b>确定性表达</b>。
 *
 * <ul>
 *   <li>它<b>不返回任何邮件</b>（{@code emails} 恒为空）—— 硬编码邮件列表是假事实，
 *       界面上会出现一封查无此人的买家来信。</li>
 *   <li>它<b>不抛异常</b> —— 未接入是业务状态，不是故障；同步入口据此返回
 *       「平台买家邮件适配器未交付」（<b>409</b>）并<b>不写任何数据</b>。</li>
 *   <li>它<b>说清责任方与影响</b>，让界面上那行提示能指向真正的缺口。</li>
 * </ul>
 *
 * <p>某个平台接上买家邮件适配器之后，本类被替换成真正的实现
 * （{@link PlatformEmailPort} 就是那条接缝），<b>服务层与前端一行都不用改</b>。
 *
 * <p>与 channel 侧 {@code UnavailableInfluencerDataPort}（14.5.1.1 达人库）**同构** ——
 * 那条接缝处理的是「达人数据」，本条处理的是「买家邮件」，形状与纪律逐字相同。
 */
@Service
public class UnavailablePlatformEmailPort implements PlatformEmailPort {

  /** 未接入时的状态码。 */
  public static final String NOT_INTEGRATED = "NOT_INTEGRATED";

  /**
   * 未接入的原因（人能读，界面上原样显示）。
   *
   * <p>🔴 措辞要说清「<b>缺什么</b>」与「<b>会怎样</b>」，不能只说「暂不支持」——
   * 「暂不支持」会让人以为换个账号就能用。
   */
  static final String NOT_INTEGRATED_REASON =
      "平台买家邮件适配器未交付：channel 的平台适配层目前只有 OAuth 授权适配器，"
          + "没有任何平台的买家邮件收发接口。买家邮件目前只能**人工登记**；"
          + "平台适配器接上后本端点会自动放行，无需改前端。";

  @Override
  public Availability availability(String platformCode) {
    return new Availability(false, NOT_INTEGRATED, NOT_INTEGRATED_REASON);
  }

  @Override
  public FetchResult fetch(String platformCode, UUID shopId, String bearer) {
    // 🔴 **不返回任何邮件**：空集合会被读成「这个店铺没有买家邮件」，
    //   而真相是「我们拉不到」。available=false + reason 才是正确表达。
    return new FetchResult(false, NOT_INTEGRATED_REASON, List.of());
  }
}
