package com.starcon.reporting;

import java.util.List;

/**
 * 13.6.2.1 的**投递传输端口**（蓝图 13.6.2.1：「定时订阅推送（邮件/企业 IM）」）。
 *
 * <p>🔴 **本接口是「真的发出去了」的唯一凭据**：
 * 投递记录只有在 {@link Outcome#sent()} 为 {@code true} 时才会被写成 DELIVERED。
 * 传输层没配置、连接失败、被对方拒收 —— 一律是 FAILED，并写明原因。
 *
 * <p>🔴 **绝不允许在没发出去的情况下把记录标成 DELIVERED**：
 * 上游 13.6.1.1 只把记录写成 PENDING（「待投递」），那是诚实的；
 * 本模块接了投递能力之后，就必须同样诚实 —— 一个显示「已投递」而用户根本没收到邮件的台账，
 * 比没有投递功能更糟（运维会以为链路是通的）。
 *
 * <p>⚠️ **可插拔**：EMAIL 与 IM 各一个实现，生产实现读环境变量里的 SMTP / Webhook 配置。
 * 换传输介质（比如换成企业内部的消息网关）时**只换实现类**，端点与表结构不动。
 */
public interface ReportingDeliveryTransport {

  /** 投递通道：EMAIL / IM（与上游订阅表的 CHECK 一致）。 */
  String channel();

  /**
   * 这个传输**配好了吗**。
   *
   * <p>🔴 没配好时 {@link #send} 必须**失败并说清缺什么**，不能「静默成功」。
   */
  boolean configured();

  /**
   * 真的把消息送出去。
   *
   * @throws Exception 传输失败（连接不上 / 被拒收 / 超时）—— 调用方据此记 FAILED
   */
  Outcome send(Message message) throws Exception;

  /** 要送出去的一条消息。 */
  record Message(
      /** 收件目标（邮箱 / IM 群 id）。 */
      String target,
      /** 主题。 */
      String subject,
      /** 正文（纯文本）。 */
      String bodyText,
      /** 附件名（CSV 报表）；空 = 不带附件。 */
      String attachmentName,
      /** 附件字节；空 = 不带附件。 */
      byte[] attachment,
      /** 表格行（每行是若干格子的文本），供 IM 通道渲染成消息卡片。 */
      List<List<String>> rows) {}

  /** 一次投递的结果。 */
  record Outcome(boolean sent, String detail) {

    /** 成功。 */
    public static Outcome ok(String detail) {
      return new Outcome(true, detail);
    }

    /** 失败（**detail 必须写清原因**）。 */
    public static Outcome failed(String detail) {
      return new Outcome(false, detail);
    }
  }
}
