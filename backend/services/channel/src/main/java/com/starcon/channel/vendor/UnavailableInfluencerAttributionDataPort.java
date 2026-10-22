package com.starcon.channel.vendor;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 14.5.2.1 带货归因的<b>平台带货数据端口 · 当前实现</b>：对所有平台都答「未接入」。
 *
 * <p>🔴 <b>这不是 mock，也不是占位实现</b>：它是「这个能力现在不存在」这件事的
 * <b>确定性表达</b>。
 *
 * <ul>
 *   <li>它<b>不返回任何带货数据</b>（{@code attributions} 恒为空）—— 硬编码的归因记录
 *       是假事实，界面上会出现一笔查无此单的归因。</li>
 *   <li>它<b>不抛异常</b> —— 未接入是业务状态，不是故障；同步入口据此返回
 *       「平台带货数据适配器未交付」并<b>不写任何数据</b>。</li>
 *   <li>它<b>说清责任方与影响</b>，让界面上那行提示能指向真正的缺口。</li>
 * </ul>
 *
 * <p>某个平台接上带货适配器之后，本类被替换成真正的实现
 * （{@link InfluencerAttributionDataPort} 就是那条接缝），
 * <b>服务层与前端一行都不用改</b>。
 */
@Component
public class UnavailableInfluencerAttributionDataPort implements InfluencerAttributionDataPort {

  /** 未接入时的状态码。 */
  public static final String NOT_INTEGRATED = "NOT_INTEGRATED";

  /**
   * 未接入的原因（人能读，界面上原样显示）。
   *
   * <p>🔴 措辞要说清「<b>缺什么</b>」与「<b>会怎样</b>」，不能只说「暂不支持」——
   * 「暂不支持」会让人以为换个账号就能用。
   */
  static final String NOT_INTEGRATED_REASON =
      "平台带货数据适配器未交付：channel 的平台适配层目前只有 OAuth 授权适配器，"
          + "没有任何平台的达人带货数据接口；订单事实又在 trade 服务（跨服务不可读）。"
          + "所以归因数据目前**只来自本地已录的合作记录**（14.5.1.1 的达人合作历史）；"
          + "同步入口会明确返回「未接入」并**不写任何数据** —— "
          + "本模块**不编造一个假的平台接口，也不返回硬编码的归因列表**。"
          + "平台适配器接上后本端点会自动放行，无需改前端。";

  @Override
  public Availability availability(String platformCode) {
    return new Availability(false, NOT_INTEGRATED, NOT_INTEGRATED_REASON);
  }

  @Override
  public FetchResult fetch(
      String platformCode, String shopCode, OffsetDateTime from, OffsetDateTime to, String bearer) {
    // 🔴 **不返回任何归因数据**：空集合会被读成「这个店铺没有带货数据」，
    //   而真相是「我们查不到」。available=false + reason 才是正确表达。
    return new FetchResult(false, NOT_INTEGRATED_REASON, List.of());
  }
}
