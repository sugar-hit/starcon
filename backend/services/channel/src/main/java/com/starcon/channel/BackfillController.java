package com.starcon.channel;

import static com.starcon.channel.BackfillModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.*;

/**
 * 02.6.2 增量补拉。
 *
 * <p>端点按蓝图模块号标注，方便对照 02-channel-and-shop.md。
 */
@RestController
@RequestMapping("/channel")
public class BackfillController {
  private final AccessService access;
  private final BackfillService service;

  public BackfillController(AccessService access, BackfillService service) {
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

  /** 02.6.2.1 能力声明：下游未交付段 available=false，界面据此禁用按钮。 */
  @GetMapping("/backfills/capabilities")
  public BackfillCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.sync.read", a -> service.capabilities());
  }

  /** 补拉任务集合读：按连接与状态筛选。 */
  @GetMapping("/backfills")
  public List<BackfillTaskView> tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID connectionId,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "50") int limit) {
    return call(auth, "channel.sync.read", a -> service.tasks(a, connectionId, status, limit));
  }

  /**
   * 02.6.2.1 发起补拉。
   *
   * <p>幂等键是入参契约的一部分：相同 idempotencyKey 重复发起返回既有任务 ID，
   * 不会产生第二个补拉任务。
   */
  @PostMapping("/backfills")
  public UUID create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BackfillTaskInput input) {
    return call(auth, "channel.sync.trigger", a -> service.create(a, input));
  }

  @GetMapping("/backfills/summary")
  public List<BackfillStatusSummary> summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.sync.read", a -> service.statusSummary(a));
  }

  @GetMapping("/backfills/{id}")
  public BackfillTaskView task(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "channel.sync.read", a -> service.task(a, id));
  }

  /** 逐条处理记录：补到过几次（seenCount）是排查重复投递的第一现场。 */
  @GetMapping("/backfills/{id}/records")
  public List<Map<String, Object>> records(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit) {
    return call(
        auth,
        "channel.sync.read",
        a ->
            service.records(a, id, limit).stream()
                .map(
                    r ->
                        Map.<String, Object>of(
                            "id", r.get("id", UUID.class),
                            "externalKey", r.get("external_key", String.class),
                            "resourceType", r.get("resource_type", String.class),
                            "payloadDigest", r.get("payload_digest", String.class),
                            "externalOccurredAt", r.get("external_occurred_at", java.time.Instant.class),
                            "seenCount", r.get("seen_count", Integer.class),
                            "lastSeenAt", r.get("last_seen_at", java.time.Instant.class),
                            "disposition", r.get("disposition", String.class),
                            "firstTaskIdempotencyKey",
                                r.get("idempotency_key", String.class)))
                .toList());
  }

  /** 分段轮询留痕：证明分段轮询真的发生（平台只给「最近 N 条」时）。 */
  @GetMapping("/backfills/{id}/segments")
  public List<Map<String, Object>> segments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        "channel.sync.read",
        a ->
            service.segments(a, id).stream()
                .map(
                    r ->
                        Map.<String, Object>of(
                            "segmentNo", r.get("segment_no", Integer.class),
                            "coveredFrom", r.get("covered_from", java.time.Instant.class),
                            "coveredTo", r.get("covered_to", java.time.Instant.class),
                            "reachedWindowStart",
                                r.get("reached_window_start", Boolean.class),
                            "recordCount", r.get("record_count", Integer.class),
                            "duplicateCount", r.get("duplicate_count", Integer.class),
                            "rateLimited", r.get("rate_limited", Boolean.class),
                            "polledAt", r.get("polled_at", java.time.Instant.class)))
                .toList());
  }

  /**
   * 02.6.2.1 提交一批补拉结果。
   *
   * <p>幂等判定在这里：逐条抢 backfill_record 的唯一索引，抢到=新增，抢不到=更新或去重。
   * 单批条数受任务 pageSize（平台分页上限）约束。
   */
  @PostMapping("/backfills/{id}/records")
  public BackfillSubmitResult submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody BackfillSubmitInput input) {
    return call(auth, "channel.sync.complete", a -> service.submit(a, id, input));
  }

  /**
   * 收尾一次补拉。只有 SUCCEEDED / PARTIAL 才推进关联游标 ——
   * 失败时保持原位，让下一次从同一处重来。
   */
  @PostMapping("/backfills/{id}/complete")
  public void complete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody BackfillCompleteInput input) {
    callVoid(auth, "channel.sync.complete", a -> service.complete(a, id, input));
  }

  @PostMapping("/backfills/{id}/cancel")
  public void cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    callVoid(auth, "channel.sync.trigger", a -> service.cancel(a, id));
  }

  /** 02.6.2.1 漏推检测：按 2.6.1.1 接收记录的静默区间识别缺口。 */
  @GetMapping("/backfills/gaps")
  public List<BackfillGap> gaps(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID connectionId,
      @RequestParam(defaultValue = "ORDER") String resourceType,
      @RequestParam(defaultValue = "24") int windowHours) {
    return call(
        auth, "channel.sync.read", a -> service.gaps(a, connectionId, resourceType, windowHours));
  }
}
