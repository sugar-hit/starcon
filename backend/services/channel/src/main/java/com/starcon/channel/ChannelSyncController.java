package com.starcon.channel;

import static com.starcon.channel.ChannelSyncModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 02.4 同步运行的 HTTP 接口。
 *
 * <p>端点编号对照 docs/product/capability-blueprint/02-channel-and-shop.md §2.4。
 *
 * <p>权限用 {@code channel.sync.*} 而不是复用连接权限：暂停一个店铺的同步和读一次同步日志是
 * 两件事，前者会改变外部平台侧的行为，后者只是读，二者必须能被角色分开授权。
 */
@RestController
@RequestMapping("/channel/sync")
public class ChannelSyncController {
  private final AccessService access;
  private final ChannelSyncService service;

  public ChannelSyncController(AccessService access, ChannelSyncService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  private void callVoid(String bearer, String permission, java.util.function.Consumer<AccessService.Access> work) {
    var actor = access.require(bearer, permission);
    access.run(actor, permission, () -> {
      work.accept(actor);
      return null;
    });
  }

  // ---- 2.4.1 同步任务 ----

  /** 2.4.1.1 同步任务列表：按（连接, 站点, 资源, 分区）列出状态、积压与最近运行。 */
  @GetMapping("/tasks")
  public List<SyncCursorView> tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "") String resourceType) {
    return call(auth, "channel.sync.read", a -> service.cursors(resourceType));
  }

  /** 2.4.1.2 运行日志。 */
  @GetMapping("/runs")
  public List<SyncRunView> runs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID cursorId,
      @RequestParam(defaultValue = "50") int limit) {
    return call(auth, "channel.sync.read", a -> service.runs(cursorId, limit));
  }

  /** 2.4.1.1 手动触发同步。 */
  @PostMapping("/runs")
  public UUID runStart(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SyncRunInput input) {
    return call(auth, "channel.sync.run", a -> service.beginRun(a, input));
  }

  /**
   * 2.4.1.2 提交运行：运行终态与游标推进在同一事务内完成。
   *
   * <p>客户端在这里断开会整笔回滚，运行留在 RUNNING、水位不动——下次调度会因单飞约束拒绝重入，
   * 因此调用方必须重试而不是另起一次运行。
   */
  @PostMapping("/runs/{id}/commit")
  public SyncRunView runCommit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SyncRunCommit commit) {
    return call(auth, "channel.sync.run", a -> service.commitRun(a, id, commit));
  }

  /** 2.4.1.2 标记运行失败：水位不动，等下次断点续传。 */
  @PostMapping("/runs/{id}/fail")
  public void runFail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam String error) {
    callVoid(auth, "channel.sync.run", a -> service.failRun(a, id, error));
  }

  /** 2.4.1.3 游标回退：处理"漏了一小时数据"与"游标跑飞"。 */
  @PostMapping("/cursors/{id}/rewind")
  public void cursorRewind(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SyncCursorRewindInput input) {
    callVoid(auth, "channel.sync.run", a -> service.rewindCursor(a, id, input));
  }

  // ---- 2.4.2 异常处理 ----

  /** 2.4.2.1 失败队列。 */
  @GetMapping("/failures")
  public List<SyncFailureView> failures(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "") String reason,
      @RequestParam(defaultValue = "true") boolean openOnly) {
    return call(auth, "channel.sync.read", a -> service.failures(reason, openOnly));
  }

  /** 2.4.2.1 失败原因分布图。 */
  @GetMapping("/failures/clusters")
  public List<FailureClusterView> failureClusters(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.sync.read", a -> service.failureClusters());
  }

  /** 2.4.2.1 登记失败（同步处理器上报）。 */
  @PostMapping("/failures")
  public UUID failureRecord(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SyncFailureRecordInput input) {
    return call(auth, "channel.sync.run", a -> service.recordFailure(a, input));
  }

  /** 2.4.2.2 人工重放：幂等，重复重放不会产生第二张待办。 */
  @PostMapping("/failures/{id}/replay")
  public SyncFailureView failureReplay(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SyncFailureReplayInput input) {
    return call(auth, "channel.sync.replay", a -> service.replayFailure(a, id, input));
  }
}