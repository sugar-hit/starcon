package com.starcon.channel.carrier;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.3.2.1 承运商交运适配层（统一接口 + vendor 独立包的纪律）。
 *
 * <p>蓝图 10.3.2.1 的输出句点名「承运商回执」，而「交接给承运商」这一跳在
 * 本轮集成头里<b>没有真实对手方</b>：10.1 物流渠道全部未开发
 * （10.1.1.1 承运商列表 / 10.1.1.2 渠道配置 / 10.1.1.3 时效与限制 /
 * 10.1.2.1 运费计算器 / 10.1.2.2 渠道比价，台账确认全为「未开发」），
 * 没有承运商连接、没有渠道配置，所以本仓库<b>没有</b>任何承运商账号与网络出口。
 *
 * <p>因此这里<b>只定义边界，不放任何指向真实域名的 HTTP 客户端</b>，也
 * <b>刻意不放一个假的 vendor 实现</b>。理由不是图省事，而是一条具体的危害：
 * 一个「返回成功回执」的假适配器会让「已交接给承运商」在数据上看起来真的发生过 ——
 * 而按蓝图加粗句，**批次内包裹的丢失责任在承运商**。责任分界点一旦被伪造，
 * 丢件之后这批货在本系统里就成了「已交接、无责」，那比没有这个功能糟得多。
 *
 * <p>所以本模块的诚实做法是：批次状态机里保留 {@code DISPATCH_PENDING}
 * 这一档，如实表达「已登记交接、等待真实通道接通」；承运商回执由<b>人工登记</b>
 * 带回（有主体、有时刻、有来源的真实凭据），而不是系统对接取回；
 * {@code capabilities} 里 {@code carrier_dispatch} 与 {@code carrier_receipt}
 * 两项显式 {@code available=false} 并点名 {@code ownerModule=10.1.1.1}。
 *
 * <p>将来 10.1.1.1 交付承运商连接后，在本包下按 vendor 建独立子包
 * （{@code carrier/<vendor>/}），实现本接口即可，上层服务与数据模型不需要改动。
 */
public interface CarrierHandoverAdapter {

  /** 承运商代码，与 {@code handover_batch.carrier_code} 对应。 */
  String carrierCode();

  /** 一次交运请求：把某个批次的逐件清单交给承运商。 */
  record DispatchRequest(
      UUID batchId,
      String batchNo,
      String carrierCode,
      OffsetDateTime handoverAt,
      List<DispatchLine> lines) {}

  /** 交运请求里的一行 = 交接清单的一行（逐件，不是逐单元）。 */
  record DispatchLine(
      int parcelNo,
      String parcelRef,
      String waybillNo,
      BigDecimal grossWeightKg,
      BigDecimal lengthMm,
      BigDecimal widthMm,
      BigDecimal heightMm) {}

  /** 承运商回执。{@code receiptRef} 与 {@code receivedAt} 缺一不可 —— 没有回执号与
   *  回执时刻的回执无法用于事后追责，等于没有回执。 */
  record DispatchReceipt(
      String receiptRef,
      boolean accepted,
      String detail,
      OffsetDateTime receivedAt) {}

  /** 交运是否成功。区分「未接通」与「被拒」：前者是能力缺口，后者是业务事实。 */
  class CarrierDispatchException extends RuntimeException {
    private final boolean connectionFailure;

    public CarrierDispatchException(String message, boolean connectionFailure) {
      super(message);
      this.connectionFailure = connectionFailure;
    }

    /** 通道未接通 / 凭据失效：批次应停在 DISPATCH_PENDING，而不是记为被拒。 */
    public boolean connectionFailure() {
      return connectionFailure;
    }
  }

  /**
   * 把一个批次交给承运商。
   *
   * <p>实现必须：只负责与承运商交互，<b>不负责落库</b>（落库统一由服务层做，
   * 所以「交接清单不可改写」这条不变量在适配器层就无法被绕过 ——
   * 适配器根本拿不到写凭据表的句柄）。
   *
   * @throws CarrierDispatchException 通道未接通或承运商侧拒收
   */
  DispatchReceipt dispatch(DispatchRequest request);

  /** 拉取某批次的承运商回执。同样只取数，不落库。 */
  DispatchReceipt fetchReceipt(String batchNo, String receiptRef);
}
