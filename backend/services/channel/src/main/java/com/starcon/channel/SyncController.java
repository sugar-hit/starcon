package com.starcon.channel;

import static com.starcon.channel.ChannelModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.web.bind.annotation.*;

/**
 * 02.4 同步运行 与 02.5 平台回写。
 *
 * <p>端点按蓝图模块号标注，方便对照 02-channel-and-shop.md。
 */
@RestController
@RequestMapping("/channel")
public class SyncController {
  private final AccessService access;
  private final SyncService service;

  public SyncController(AccessService access, SyncService service) {
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

  // ---- 2.4.1.1 同步任务 ----

  @GetMapping("/sync-cursors")
  public List<SyncCursorView> cursors(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID connectionId) {
    return call(auth, "channel.sync.read", a -> service.cursors(connectionId));
  }

  @GetMapping("/sync-cursors/{id}")
  public SyncCursorView cursor(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "channel.sync.read", a -> service.cursor(id));
  }

  @PostMapping("/sync-cursors")
  public UUID cursorCreate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SyncCursorInput input) {
    return call(auth, "channel.sync.create", a -> service.registerCursor(a, input));
  }

  @PostMapping("/sync-cursors/{id}/pause")
  public void cursorPause(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    callVoid(auth, "channel.sync.update", a -> service.setCursorPaused(a, id, true));
  }

  @PostMapping("/sync-cursors/{id}/resume")
  public void cursorResume(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    callVoid(auth, "channel.sync.update", a -> service.setCursorPaused(a, id, false));
  }

  /** 2.4.1.3 断点续传：回退游标以重拉时间窗。重放幂等由 external_key 保证。 */
  @PostMapping("/sync-cursors/{id}/rewind")
  public void cursorRewind(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody CursorRewindInput input) {
    callVoid(auth, "channel.sync.update", a -> service.rewindCursor(a, id, input.cursorValue()));
  }

  /** 2.4.1.1 手动触发同步。运行先落库，处理提交后游标才推进。 */
  @PostMapping("/sync-cursors/{id}/trigger")
  public UUID cursorTrigger(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "channel.sync.trigger", a -> service.triggerRun(a, id, "MANUAL"));
  }

  /**
   * 2.4.1.2 结束一次运行。
   *
   * <p>只有 SUCCEEDED 才推进游标；PARTIAL/FAILED 保持原位，让下一次从同一处重来。
   */
  @PostMapping("/sync-runs/{id}/complete")
  public void runComplete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SyncRunCompletionInput input) {
    callVoid(
        auth,
        "channel.sync.complete",
        a -> service.completeRun(a, id, input.result(), input.failures()));
  }

  @GetMapping("/sync-runs")
  public List<SyncRunView> runs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID cursorId,
      @RequestParam(defaultValue = "50") int limit) {
    return call(auth, "channel.sync.read", a -> service.runs(a.tenantId(), cursorId, limit));
  }

  // ---- 2.4.2 失败队列与重放 ----

  @GetMapping("/sync-failures")
  public List<SyncFailureView> failures(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String reason,
      @RequestParam(defaultValue = "50") int limit) {
    return call(auth, "channel.sync.read", a -> service.failures(a.tenantId(), reason, limit));
  }

  /** 2.4.2.1 失败原因分布。 */
  @GetMapping("/sync-failures/summary")
  public List<SyncFailureSummary> failureSummary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.sync.read", a -> service.failureSummary(a.tenantId()));
  }

  /** 2.4.2.2 人工重放。返回新的运行 ID，重放结果通过该运行回报。 */
  @PostMapping("/sync-failures/{id}/replay")
  public UUID failureReplay(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "channel.sync.trigger", a -> service.replay(a, id));
  }

  @PostMapping("/sync-runs/{id}/resolve")
  public void runResolve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam boolean succeeded) {
    callVoid(auth, "channel.sync.complete", a -> service.resolveFailures(a, id, succeeded));
  }

  // ---- 2.5 平台回写 ----

  @GetMapping("/platform-operations")
  public List<PlatformOperationView> operations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "50") int limit) {
    return call(
        auth, "channel.operation.read", a -> service.operations(a.tenantId(), status, limit));
  }

  /** 2.5.2.1 批量回写：幂等键重复时复用既有任务，不会产生第二次平台动作。 */
  @PostMapping("/platform-operations")
  public UUID operationEnqueue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PlatformOperationInput input) {
    return call(auth, "channel.operation.create", a -> service.enqueueOperation(a, input));
  }

  @PostMapping("/platform-operations/{id}/start")
  public void operationStart(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    callVoid(auth, "channel.operation.update", a -> service.startOperation(a, id));
  }

  @PostMapping("/platform-operations/{id}/succeed")
  public void operationSucceed(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) OperationAckInput input) {
    callVoid(
        auth,
        "channel.operation.update",
        a -> service.succeedOperation(a, id, input == null ? null : input.platformRequestId()));
  }

  @PostMapping("/platform-operations/{id}/fail")
  public void operationFail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody OperationFailInput input) {
    callVoid(auth, "channel.operation.update", a -> service.failOperation(a, id, input.reason()));
  }

  /**
   * 2.5.1.2 标记为未知结果。
   *
   * <p>超时或网络中断时必须走这里而不是 fail——UNKNOWN 表示平台可能已经执行， 直接当作失败重试会导致重复发货或库存错扣。
   */
  @PostMapping("/platform-operations/{id}/unknown")
  public void operationUnknown(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody OperationFailInput input) {
    callVoid(auth, "channel.operation.update", a -> service.markUnknown(a, id, input.reason()));
  }

  /** 2.5.1.2 核验结论。STILL_UNKNOWN 会保持 UNKNOWN，不允许直接重试。 */
  @PostMapping("/platform-operations/{id}/verify")
  public void operationVerify(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody VerificationInput input) {
    callVoid(
        auth,
        "channel.operation.update",
        a -> service.verifyOperation(a, id, input.verification()));
  }
}
