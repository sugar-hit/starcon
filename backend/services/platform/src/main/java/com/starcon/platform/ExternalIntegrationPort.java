package com.starcon.platform;

import java.math.BigDecimal;

/**
 * **外部系统适配器**（1688 / 淘宝 / 海外仓服务商 / 物流商 / 聚合认证服务 / 自建 BI）的**唯一边界**。
 *
 * <p>蓝图 18.2.3.1 的职责写明「对接其他外部系统：1688/淘宝货源平台、海外仓服务商、物流商、
 * 聚合认证服务、自建 BI。统一管理连接配置与同步健康」—— 这些系统**全部**要出去跟外部系统说话，
 * 所以它们统一收敛到本端口后面：服务层只认本接口，<b>不认具体外部系统</b>。
 *
 * <p>🔴 **本接口刻意没有「默认成功」的实现**：
 * 通道侧没有凭据 / 主机不在白名单 / 适配器未交付 ⇒ 适配器**必须**返回
 * 「外部通道未接入」或「结果未知」，由服务层**如实记进同步台账**，
 * 绝不假装成功 —— 假装成功会让系统以为外部系统那边已经同步过了，而它那边根本没有。
 *
 * <p>🔴 **凭据只有引用**：{@link ExternalSyncContext#credentialRef()} 里是密钥管理器的
 * 引用 ID，<b>不是</b>明文。明文只在适配器解析出来的那一刻活在内存里，
 * 绝不入库、绝不入日志、绝不出现在请求摘要里。
 *
 * <p>🔴 <b>一个适配器只服务一个对接</b>：端口方法不带「是哪个连接」的参数 ——
 * 连接的身份由 {@link ExternalSyncContext#adapterCode()} 给出。
 * 这样「一个外部服务故障不应影响其他集成」在代码结构上也是成立的：
 * 适配器**没有**能力去碰别的连接的数据。
 */
public interface ExternalIntegrationPort {

  // ================================================================ 通道元信息

  /** 适配器代码（与 {@code platform_external_connection.adapter_code} 同名）。 */
  String adapterCode();

  /** 这个适配器服务的是哪一类外部系统。 */
  String category();

  /** 适配器中文名。 */
  String displayName();

  /**
   * 这个通道**现在**通不通。
   *
   * <p>🔴 当前实现**恒为 {@code false}** —— 本仓没有任何真实的外部系统凭据与网络通道。
   */
  boolean connected();

  /**
   * 不通的理由（写清「缺什么、归谁、现在怎么办」）。
   *
   * <p>🔴 **不返回空串**：空理由会让界面上出现一个没有解释的灰色开关，
   * 运营既不知道为什么不通，也不知道该找谁。
   */
  String unavailableReason();

  // ================================================================ 同步

  /**
   * 执行一次同步。
   *
   * <p>🔴 契约是「<b>如实返回结论</b>」，不是「必须成功」：
   * 未接入 ⇒ {@link ExternalSyncOutcome#NOT_CONNECTED}；
   * 超时 ⇒ {@link ExternalSyncOutcome#TIMEOUT}（**结果未知**，不是失败）。
   */
  ExternalSyncOutcome executeSync(ExternalSyncContext context, ExternalSyncCommand command);

  // ================================================================ 传输形状

  /**
   * 一次调用的上下文（**只含引用，不含任何凭据明文**）。
   *
   * @param bearer 调用方自己的会话（跨上游调用时只转发它，不传租户号）
   */
  record ExternalSyncContext(
      String adapterCode,
      String category,
      String baseUrl,
      String credentialRef,
      int timeoutSeconds,
      int maxAttempts,
      String bearer) {}

  /** 一次同步的指令。 */
  record ExternalSyncCommand(
      String taskType, String direction, String resourceRef, String scheduleExpression) {}

  /** 一次同步的结论。 */
  record ExternalSyncOutcome(
      String status,
      BigDecimal recordsTotal,
      BigDecimal recordsSucceeded,
      BigDecimal recordsFailed,
      String externalRef,
      String failureReason,
      String failureClass,
      boolean providerNotified,
      String providerNotice) {}
}
