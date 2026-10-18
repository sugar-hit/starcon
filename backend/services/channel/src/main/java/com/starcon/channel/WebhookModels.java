package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * 02.6.1 平台消息接入的线上契约。
 *
 * <p>对应 docs/product/capability-blueprint/02-channel-and-shop.md §2.6.1.1 接收记录。
 *
 * <p>刻意不提供"重置密钥后回显明文"的入参/出参组合：密钥只以密文落库，
 * 写入后无法再读回，与 2.2.1.3 同一纪律。
 */
public final class WebhookModels {
  private WebhookModels() {}

  /** 02.6.1.1 接收状态。取值与 {@code webhook_receipt.status} 的 CHECK 约束一一对应。 */
  public enum ReceiptStatus {
    /** 已入库：验签通过并已落库，等待异步处理。 */
    RECEIVED,
    /** 重复：同一外部事件 ID 的重推，指向首次接收记录，不可再处理。 */
    DUPLICATE,
    /** 验签失败：签名不匹配或缺失，永不进入业务处理。 */
    SIGNATURE_FAILED,
    /** 处理中：已被下游认领。 */
    PROCESSING,
    /** 已处理完。 */
    PROCESSED,
    /** 处理失败，可重新认领。 */
    FAILED
  }

  /** 02.6.1.1 验签结论。UNCONFIGURED 与 FAILED 分开：前者是没登记密钥，后者是有人在伪造。 */
  public enum SignatureStatus {
    VERIFIED,
    FAILED,
    MISSING,
    UNCONFIGURED
  }

  /** 02.6.1.1 回调入口。签名密钥以 AES-GCM 密文落库，出参里没有明文。 */
  public record WebhookEndpointView(
      UUID id,
      UUID connectionId,
      String authorizedAccount,
      String platformCode,
      String callbackUrl,
      String endpointKey,
      String signatureAlgorithm,
      String signatureHeader,
      String signatureEncoding,
      String status,
      @Schema(nullable = true) Instant lastReceivedAt,
      Instant createdAt) {}

  public record WebhookEndpointInput(
      @NotNull UUID connectionId,
      /** 回调路径片段，不传则生成。同一租户下必须唯一。 */
      @Size(max = 64) String endpointKey,
      @NotBlank @Size(max = 64) String signatureHeader,
      @NotNull SignatureEncoding signatureEncoding,
      /** 平台签名密钥明文。只在内存短暂存在，落库前用主密钥加密。 */
      @NotBlank @Size(max = 512) String signingSecret) {}

  public enum SignatureEncoding {
    HEX,
    BASE64
  }

  /**
   * 02.6.1.1 接收记录。
   *
   * <p>{@code receivedAt} 是本模块的核心事实字段：平台超时重推的排查完全依赖它，
   * 它必须来自库里读回的真实值，不是"生成时的时间"。
   */
  public record WebhookReceiptView(
      UUID id,
      UUID endpointId,
      UUID connectionId,
      String authorizedAccount,
      String platformCode,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) String shopName,
      String externalEventId,
      String eventType,
      String topic,
      String signatureStatus,
      String status,
      @Schema(nullable = true) UUID duplicateOf,
      int deliveryAttempt,
      String payloadDigest,
      String lastError,
      Instant receivedAt,
      @Schema(nullable = true) Instant processingStartedAt,
      @Schema(nullable = true) Instant processedAt) {}

  /** 状态分布：让"验签失败在涨"这类安全事件一眼可见，而不是埋在一堆记录里。 */
  public record WebhookReceiptSummary(
      String status, long total, @Schema(nullable = true) Instant oldest) {}

  /** 验签失败的签名头，供人工比对与排查。 */
  public record SignatureFailure(
      String expected, String presented, String reason, @Schema(nullable = true) UUID firstId) {}

  /** 02.6.1.1 认领处理：先持久化再处理的异步侧入口。 */
  public record ReceiptClaimInput(@Schema(nullable = true) UUID shopId) {}

  /** 收尾处理结果。 */
  public record ReceiptCompleteInput(
      @NotNull ReceiptStatus status, @Size(max = 500) String lastError) {}
}
