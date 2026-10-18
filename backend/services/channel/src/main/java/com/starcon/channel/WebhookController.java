package com.starcon.channel;

import static com.starcon.channel.WebhookModels.*;
import static com.starcon.channel.WebhookService.IngestResult;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 02.6.1 平台消息接入。
 *
 * <p>端点按蓝图模块号标注，方便对照 02-channel-and-shop.md。
 *
 * <p>注意 {@code POST /channel/webhooks/{endpointKey}} 是**唯一不带会话**的端点：
 * 它由平台服务器直接打进来，没有用户身份。租户不来自任何请求头，而是由回调入口
 * 解析 + HMAC 验签确定——伪造 {@code X-Tenant-Id} 对它没有任何作用。
 */
@RestController
@RequestMapping("/channel")
public class WebhookController {
  private final AccessService access;
  private final WebhookService service;

  public WebhookController(AccessService access, WebhookService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(
      String bearer, String permission, java.util.function.Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  private void callVoid(String bearer, String permission, Consumer<AccessService.Access> work) {
    var actor = access.require(bearer, permission);
    access.run(
        actor,
        permission,
        () -> {
          work.accept(actor);
          return null;
        });
  }

  // ---- 2.6.1.1 平台回调接入 ----

  /**
   * 接收一次平台回调。
   *
   * <p>**先持久化再处理**：这里只做验签与落库，立刻返回，绝不等待业务处理。
   * 平台回调超时会触发重推，同步处理等于把一次投递放大成一串重推。
   *
   * <p>返回 200 表示"已入库"（含重复与验签失败——它们同样已落库留证）。
   * 验签失败时返回 401 让平台知道签名不对，但记录已经留下。
   */
  @PostMapping("/webhooks/{endpointKey}")
  public ResponseEntity<Map<String, Object>> receive(
      @PathVariable String endpointKey,
      @RequestHeader(value = "X-Starcon-Signature", required = false) String defaultSignature,
      @RequestHeader(value = "X-Shopify-Hmac-Sha256", required = false) String shopifySignature,
      @RequestBody(required = false) byte[] body) {
    byte[] raw = body == null ? new byte[0] : body;
    String signature = defaultSignature != null ? defaultSignature : shopifySignature;
    IngestResult result = service.receive(endpointKey, signature, raw);
    HttpStatus status =
        SignatureStatus.VERIFIED.name().equals(result.signatureStatus())
            ? HttpStatus.OK
            : HttpStatus.UNAUTHORIZED;
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "receiptId", result.receiptId().toString(),
                "status", result.status(),
                "signatureStatus", result.signatureStatus(),
                "processable", result.processable()));
  }

  // ---- 2.6.1.1 回调入口登记 ----

  @GetMapping("/webhook-endpoints")
  public List<WebhookEndpointView> endpoints(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.sync.read", a -> service.endpoints(a.tenantId()));
  }

  /** 登记回调入口。签名密钥明文只在这次调用的内存里存在，落库前已加密。 */
  @PostMapping("/webhook-endpoints")
  public UUID endpointCreate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WebhookEndpointInput input) {
    return call(auth, "channel.sync.create", a -> service.registerEndpoint(a, input));
  }

  /** 停用入口：平台仍在推送时的止血动作，接收记录保留可查。 */
  @PostMapping("/webhook-endpoints/{id}/disable")
  public void endpointDisable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    callVoid(auth, "channel.sync.update", a -> service.disableEndpoint(a, id));
  }

  // ---- 2.6.1.1 接收记录 ----

  @GetMapping("/webhook-receipts")
  public List<WebhookReceiptView> receipts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID connectionId,
      @RequestParam(defaultValue = "50") int limit) {
    return call(
        auth,
        "channel.sync.read",
        a -> service.receipts(a.tenantId(), status, connectionId, limit));
  }

  /** 状态分布：重复与验签失败必须一眼可见，而不是埋在一堆记录里。 */
  @GetMapping("/webhook-receipts/summary")
  public List<WebhookReceiptSummary> summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.sync.read", a -> service.summary(a.tenantId()));
  }

  /** 认领处理：只有验签通过的首推记录能被领走。 */
  @PostMapping("/webhook-receipts/{id}/claim")
  public void receiptClaim(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) ReceiptClaimInput input) {
    callVoid(auth, "channel.sync.update", a -> service.claim(a, id, input));
  }

  /** 收尾处理：只能落到 PROCESSED 或 FAILED。 */
  @PostMapping("/webhook-receipts/{id}/complete")
  public void receiptComplete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ReceiptCompleteInput input) {
    callVoid(auth, "channel.sync.update", a -> service.complete(a, id, input));
  }
}
