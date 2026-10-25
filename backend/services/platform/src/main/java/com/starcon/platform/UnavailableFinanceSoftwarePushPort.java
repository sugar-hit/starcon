package com.starcon.platform;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * {@link FinanceSoftwarePushPort} 的**当前实现**：逐厂商如实答「未接入」。
 *
 * <p>与 platform 的 {@link UnavailableAiTextGenerationPort}、
 * crm 的 {@code UnavailablePlatformMessageSendPort} <b>完全同构</b>：
 * 端口先立起来，实现逐能力答「未接入 + 缺什么 + 归谁」，<b>不返回任何成功回执</b>。
 *
 * <p>🔴 <b>本实现一次数据都不写</b>：不落推送记录、不落回执、不落任何
 * 「凭证已经推给金蝶了」的痕迹。服务层拿到 {@code connected=false} 之后
 * 直接翻成 <b>409</b> 并结束。
 *
 * <p>⚠️ <b>这不代表本模块是半成品</b>：需要外部适配器的只有「直连推送」这一段。
 * 与外部软件有没有直连通道，不影响下面三件**本系统内部能力**的真实生效：
 * 科目映射、主数据映射、凭证文件导出（Excel / XML 真文件 + sha256 留痕）、
 * 余额核对差异（真读本地试算平衡表 × 导入的外部余额，逐科目算差异）。
 * 运营现在就能用「导出凭证文件 → 导入金蝶 → 把对方余额表导回来核对」这条完整路径 ——
 * 这正是蓝图加粗句说的「两边通过凭证文件与科目映射衔接」。
 */
@Service
public class UnavailableFinanceSoftwarePushPort implements FinanceSoftwarePushPort {

  /**
   * 未接入的**归属模块**：<b>18.2</b>（财务与资金）。
   *
   * <p>为什么不是 18.2.1.1（就是本模块）：外部财务软件适配器（金蝶 / 用友 / SAP / Odoo
   * 的对接）不是某一格 L4 独有的，它是 18.2 卷「财务与资金」下各格共用的**外部通道** ——
   * 18.2.2.1「支付与资金」与 18.2.3.1「其他对接」要接外部系统时缺的是同一类通道。
   * 蓝图里没有单独列一个「财务软件接入」的 L4 模块，所以这里如实写成**卷级归属**（18.2）。
   */
  static final String OWNER_MODULE = "18.2";

  @Override
  public List<Availability> availability() {
    var out = new ArrayList<Availability>();
    for (var vendor :
        List.of(
            FinanceSoftwareModels.FinanceSoftwareVendor.KINGDEE,
            FinanceSoftwareModels.FinanceSoftwareVendor.YONYOU,
            FinanceSoftwareModels.FinanceSoftwareVendor.SAP,
            FinanceSoftwareModels.FinanceSoftwareVendor.ODOO)) {
      out.add(new Availability(vendor.name(), false, reason(vendor.name()), OWNER_MODULE));
    }
    return out;
  }

  @Override
  public PushOutcome push(PushRequest request, String bearer) {
    // ⚠️ 这里**不**返回一条「已推送」了事：一条假的成功回执会让「凭证已经推给金蝶了」
    // 在数据上看起来发生过，而现场一个字都没推出去 —— 财务会拿着一张**不存在的**
    // 法定凭证去做账。蓝图加粗句「业财一体化的常见做法是本系统管业务账、
    // 外部软件管法定账，两边通过凭证文件与科目映射衔接」防的正是这件事。
    String vendor = request == null || request.vendor() == null ? "" : request.vendor();
    return new PushOutcome(false, false, "", reason(vendor), OWNER_MODULE);
  }

  /** 逐厂商的原因文案：说清「缺什么、归谁、现在怎么办」。 */
  private String reason(String vendorCode) {
    var label =
        FinanceSoftwareModels.FinanceSoftwareVendor.parse(vendorCode) == null
            ? (vendorCode == null || vendorCode.isBlank() ? "外部财务软件" : vendorCode)
            : FinanceSoftwareModels.FinanceSoftwareVendor.parse(vendorCode).label();
    return "「"
        + label
        + "」的**直连推送**适配器**未接入**："
        + "本仓目前没有任何真实的财务软件适配器"
        + "（backend / services / tools 下不存在 kingdee / yonyou / sap / odoo 之类的客户端），"
        + "所以「把凭证直接推给对方账套」这一段没有通道。"
        + "该能力归 **18.2 财务与资金** 卷（外部财务软件适配器是 18.2 各模块共用的外部通道）。"
        + "在此之前可以走**凭证文件**这条路："
        + "科目映射、主数据映射、凭证文件导出（CSV / Excel / XML 真文件 + sha256 留痕）、"
        + "余额核对差异（真读本地试算平衡表 × 导入的外部余额，逐科目算差异）"
        + "都是真实生效的 —— 导出的文件导入对方系统，再把对方的余额表导回来核对，"
        + "这正是蓝图说的「两边通过凭证文件与科目映射衔接」。";
  }
}
