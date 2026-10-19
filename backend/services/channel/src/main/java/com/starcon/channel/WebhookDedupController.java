package com.starcon.channel;

import static com.starcon.channel.WebhookDedupModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.web.bind.annotation.*;

/**
 * 02.6.1.2 去重视图。
 *
 * <p>端点按蓝图模块号标注，方便对照 02-channel-and-shop.md。
 *
 * <p>与 {@link WebhookController} 的根本差别：这里**每个**端点都带用户会话，
 * 租户来自已认证成员关系（identity 解析），伪造 {@code X-Tenant-Id} 头对它没有任何作用。
 * 平台回调那条路（无会话、租户由入口解析）是 {@code WebhookController} 的事，不在这里。
 *
 * <p>权限码沿用 {@code channel.sync.*}：去重视图读的就是接收记录，
 * 换一个新权限码要动 identity 的权限真相源，收益不足且会撞其他车道的迁移版本号。
 */
@RestController
@RequestMapping("/channel")
public class WebhookDedupController {
  private final AccessService access;
  private final WebhookDedupService service;

  public WebhookDedupController(AccessService access, WebhookDedupService service) {
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

  /**
   * 去重视图列表：一个业务事件一行，带重推次数与首次处理结果引用。
   *
   * @param duplicatedOnly 只要被平台重推过的事件——接入排障第一个要看的形状
   */
  @GetMapping("/webhook-dedup")
  public List<WebhookDedupEventView> events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String externalEventId,
      @RequestParam(required = false) UUID connectionId,
      @RequestParam(required = false) String firstStatus,
      @RequestParam(defaultValue = "false") boolean duplicatedOnly,
      @RequestParam(defaultValue = "50") int limit) {
    return call(
        auth,
        "channel.sync.read",
        a ->
            service.events(
                a, externalEventId, connectionId, firstStatus, duplicatedOnly, limit));
  }

  /**
   * 去重总览。
   *
   * <p>重复投递总条数与发生过重推的事件数是两个量，不合成一个：
   * 「平台在死磕一条事件」和「很多事件各重推了一次」的处置完全不同。
   */
  @GetMapping("/webhook-dedup/summary")
  public WebhookDedupSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.sync.read", a -> service.summary(a));
  }

  /** 单个业务事件详情：归并行 + 去重命中记录 + 全部投递时间线。 */
  @GetMapping("/webhook-dedup/{firstReceiptId}")
  public WebhookDedupDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID firstReceiptId) {
    return call(auth, "channel.sync.read", a -> service.detail(a, firstReceiptId));
  }

  /**
   * 按业务事件去重认领。
   *
   * <p>下游业务服务消费平台事件时走这里，而不是按接收记录逐条认领：重复的投递连同它的
   * id 都拿不到，没有路径能创建出第二条业务记录。第二次认领必然 409。
   */
  @PostMapping("/webhook-dedup/{firstReceiptId}/claim")
  public WebhookDedupClaimResult claim(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID firstReceiptId,
      @Valid @RequestBody(required = false) WebhookDedupClaimInput input) {
    return call(
        auth,
        "channel.sync.update",
        a -> service.claim(a, firstReceiptId, input));
  }
}
