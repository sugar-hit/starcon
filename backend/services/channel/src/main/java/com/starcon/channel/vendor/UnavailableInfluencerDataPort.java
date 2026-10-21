package com.starcon.channel.vendor;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 14.5.1.1 达人库的<b>平台达人数据端口 · 当前实现</b>：对所有平台都答「未接入」。
 *
 * <p>🔴 <b>这不是 mock，也不是占位实现</b>：它是「这个能力现在不存在」这件事的**确定性表达**。
 *
 * <ul>
 *   <li>它<b>不返回任何达人</b>（{@code profiles} 恒为空）—— 硬编码达人列表是假事实，
 *       界面上会出现一个查无此人的达人库。</li>
 *   <li>它<b>不抛异常</b> —— 未接入是业务状态，不是故障；同步入口据此返回
 *       「平台达人数据适配器未交付」并<b>不写任何数据</b>。</li>
 *   <li>它<b>说清责任方与影响</b>，让界面上那行提示能指向真正的缺口。</li>
 * </ul>
 *
 * <p>某个平台接上达人适配器之后，本类被替换成真正的实现（{@link InfluencerDataPort} 就是
 * 那条接缝），<b>服务层与前端一行都不用改</b>。
 */
@Component
public class UnavailableInfluencerDataPort implements InfluencerDataPort {

  /** 未接入时的状态码。 */
  public static final String NOT_INTEGRATED = "NOT_INTEGRATED";

  /**
   * 未接入的原因（人能读，界面上原样显示）。
   *
   * <p>🔴 措辞要说清「**缺什么**」与「**会怎样**」，不能只说「暂不支持」——
   * 「暂不支持」会让人以为换个账号就能用。
   */
  static final String NOT_INTEGRATED_REASON =
      "平台达人数据适配器未交付：channel 的平台适配层目前只有 OAuth 授权适配器，"
          + "没有任何平台的达人数据接口。达人与效果数据目前只能**手动录入**；"
          + "平台适配器接上后本端点会自动放行，无需改前端。";

  @Override
  public Availability availability(String platformCode) {
    return new Availability(false, NOT_INTEGRATED, NOT_INTEGRATED_REASON);
  }

  @Override
  public FetchResult fetch(String platformCode, String shopCode, String bearer) {
    // 🔴 **不返回任何档案**：空集合会被读成「这个店铺没有达人」，
    //   而真相是「我们查不到」。available=false + reason 才是正确表达。
    return new FetchResult(false, NOT_INTEGRATED_REASON, List.of());
  }
}
