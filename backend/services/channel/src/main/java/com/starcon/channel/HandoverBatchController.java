package com.starcon.channel;

import static com.starcon.channel.HandoverBatchModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/**
 * 10.3.2.1 交运批次。
 *
 * <p>端点按蓝图模块号标注，方便对照 10-logistics-and-freight.md。
 *
 * <p>权限码**复用** identity 已有的 {@code channel.operation.*}
 * （交运属于「平台/承运商操作」，与 10.4.2.1 海外仓连接用 connection/sync 不同），
 * 因此本模块不需要改动 identity 服务，跨车道回归面为 0。
 */
@RestController
@RequestMapping("/channel/handover-batches")
public class HandoverBatchController {
  private final AccessService access;
  private final HandoverBatchService service;

  public HandoverBatchController(AccessService access, HandoverBatchService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
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

  // ---- 能力声明 ----

  @GetMapping("/capabilities")
  public HandoverCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 候选池（上游 9.4.1.1 的只读投影）----

  /** 同步候选池：唯一写入口，只经上游授权 API 取数，不跨服务 SQL。 */
  @PostMapping("/candidates/sync")
  public SyncCandidatesResult sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody SyncCandidatesRequest request) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.syncCandidates(request, auth));
  }

  @GetMapping("/candidates")
  public HandoverCandidatePage candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String warehouseRef,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "HANDED_OVER_AT_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.candidates(warehouseRef, carrierCode, status, sort, page, size));
  }

  /** 组批预览：按承运商 + 交运日期档真 GROUP BY，不是拉全量在内存里筛。 */
  @GetMapping("/group-preview")
  public List<HandoverGroupBucket> groupPreview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate handoverBucket) {
    return call(auth, "channel.operation.read", a -> service.groupPreview(carrierCode, handoverBucket));
  }

  // ---- 交运批次 ----

  @GetMapping
  public HandoverBatchPage batches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate handoverBucket,
      @RequestParam(required = false, defaultValue = "HANDOVER_AT_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.batches(carrierCode, status, handoverBucket, sort, page, size));
  }

  /** 组批：承运商 + 交运时间 + 逐件交接清单。批次计数不接受调用方指定。 */
  @PostMapping
  public HandoverBatchDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CreateBatchRequest request) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.createBatch(request, a.membershipId()));
  }

  @GetMapping("/{id}")
  public HandoverBatchDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.detail(UUID.fromString(id)));
  }

  /** 交接清单：追责时直接给对方的那一份凭据（与详情同源，不是另算一套）。 */
  @GetMapping("/{id}/manifest")
  public HandoverManifestExport manifest(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.manifest(UUID.fromString(id)));
  }

  /** 登记交接（DRAFT → DISPATCH_PENDING）。承运商通道未接通时如实停在待接通状态。 */
  @PostMapping("/{id}/dispatch")
  public HandoverBatchDetail dispatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody DispatchRequest request) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.dispatch(UUID.fromString(id), request, a.membershipId()));
  }

  /** 承运商回执登记（10.1.1.1 未交付 ⇒ 人工带回执，不是系统对接取回）。 */
  @PostMapping("/{id}/receipt")
  public HandoverBatchDetail receipt(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody ReceiptRequest request) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.recordReceipt(UUID.fromString(id), request, a.membershipId()));
  }

  @PostMapping("/{id}/cancel")
  public HandoverBatchDetail cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false, defaultValue = "") String note) {
    return call(
        auth,
        "channel.operation.update",
        a -> service.cancel(UUID.fromString(id), note, a.membershipId()));
  }

  @GetMapping("/{id}/timeline")
  public List<HandoverBatchEventRow> timeline(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.events(UUID.fromString(id)));
  }

  /** 刻意**没有** DELETE 段：交接清单是凭据，交运批次不能靠物理删除收回。 */
}
