package com.starcon.channel;

import static com.starcon.channel.ListingPublishJobModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.3.1.1 一键刊登。
 *
 * <p>蓝图：「单个 SKU 一次性发布到多个目标店铺/站点：自动套模板、填充内容、建立映射、上传媒体、设置价格库存、提交平台审核。输入：源 SKU；目标店铺与站点集合；模板选择；价格库存来源策略；{@code idempotency_key}。输出：N 个发布任务（每个目标一个）+ 各任务的进度与结果（草稿/审核中/成功/驳回+原因）。关系：上游 = 4.2.1.1、5 定价（取价）、7 库存（取可售量）、02 平台能力；下游 = 2.5.1.1 回写任务。<b>跨平台发布不是一个事务</b>：某个平台失败不影响其他平台，各自独立重试。」
 *
 * <p>🔴 端点清单里每一组都对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code POST /jobs} —— 「单个 SKU 一次性发布到多个目标店铺/站点」的入口。
 *       幂等：同一个 {@code idempotency_key} 重复提交返回同一条作业。
 *   <li>{@code GET /target-options} —— 「目标店铺与站点集合」的候选项（真数据，只列已启用的站点）。
 *   <li>{@code GET /content-sources} —— 「模板选择」与编辑器草稿的候选项。
 *   <li>{@code GET /jobs} / {@code GET /jobs/{uuid}} —— 作业列表与详情（含服务端算好的进度）。
 *   <li>{@code GET /jobs/{uuid}/targets} —— 「N 个发布任务（每个目标一个）」。
 *   <li>{@code GET /jobs/{uuid}/steps} —— 步骤时间线（4.3.1.3 未交付，这里只给数据）。
 *   <li>{@code POST /jobs/{uuid}/dispatch} —— 🔴 派发：逐目标独立，一个失败不影响其他。
 *   <li>{@code POST /targets/{uuid}/retry} —— 🔴 各自独立重试（只重试失败的那一个）。
 *   <li>{@code POST /targets/{uuid}/report} —— 平台回执（成功 / 驳回 / 失败 + 原因）。
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 listing 列表、
 * 4.2.1.1 刊登编辑器、4.3.1.2 批量发布同族），identity 服务零改动。读操作用 read，
 * 建作业用 create，派发 / 回执 / 重试用 update。
 */
@RestController
@RequestMapping("/channel/one-click-publish")
public class ListingPublishJobController {

  private final AccessService access;
  private final ListingPublishJobService service;

  public ListingPublishJobController(AccessService access, ListingPublishJobService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  /**
   * 需要把调用方 bearer 透传给上游端口的入口（建作业要读 03 的商品属性 / 07 的可售量）。
   *
   * <p>🔴 租户<b>不由本服务传给上游</b>：转发的是调用方自己的 bearer，
   * 上游仍按已认证成员关系解析租户并强制 RLS。
   */
  private <T> T callWithBearer(
      String bearer, String permission, BiFunction<AccessService.Access, String, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor, bearer));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：取价（5.1.1.1）、合规预检（4.2.3.2）、发布任务时间线页（4.3.1.3）、
   * 下架联动（4.1.1.3）**均 available=false** + ownerModule + 影响。
   *
   * <p>前端据此显示「取价未接入（归 5.1.1.1）」，<b>不编造一个价</b>。
   */
  @GetMapping("/capabilities")
  public ListingPublishCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 选目标 / 选内容来源 ----

  /** 可选的目标（店铺 + 已启用的站点）。 */
  @GetMapping("/target-options")
  public List<ListingPublishTargetOption> targetOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.targetOptions());
  }

  /** 内容来源候选：4.2.1.1 的入队记录 + 4.2.2.1 的可用模板。 */
  @GetMapping("/content-sources")
  public ListingPublishContentSources contentSources(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.contentSources());
  }

  // ---- 建作业 ----

  /**
   * 🔴 建一键刊登作业。
   *
   * <p>幂等：同一个 {@code idempotency_key} 重复提交返回同一条作业，不产生第二条。
   * 目标集合里每个（店铺, 站点, 语言）各建一行发布任务。
   */
  @PostMapping("/jobs")
  public ListingPublishJobRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CreateListingPublishJobRequest request) {
    return callWithBearer(
        auth,
        "channel.operation.create",
        (a, bearer) -> service.create(request, bearer, a.userId()));
  }

  // ---- 读 ----

  /** 作业列表（真分页 + 真筛选）。 */
  @GetMapping("/jobs")
  public List<ListingPublishJobRow> jobs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String state,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.jobs(state, page, size));
  }

  /** 单个作业（含服务端算好的进度与总体结论）。 */
  @GetMapping("/jobs/{jobId}")
  public ListingPublishJobRow job(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String jobId) {
    return call(auth, "channel.operation.read", a -> service.job(jobId));
  }

  /** 「N 个发布任务（每个目标一个）」。 */
  @GetMapping("/jobs/{jobId}/targets")
  public List<ListingPublishTargetRow> targets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String jobId,
      @RequestParam(required = false) String state) {
    return call(auth, "channel.operation.read", a -> service.targets(jobId, state));
  }

  /** 步骤时间线（4.3.1.3 未交付，这里只给数据，不给页面）。 */
  @GetMapping("/jobs/{jobId}/steps")
  public List<ListingPublishStepRow> steps(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String jobId) {
    return call(auth, "channel.operation.read", a -> service.steps(jobId));
  }

  // ---- 派发 / 回执 / 重试 ----

  /**
   * 🔴 派发：逐目标独立提交平台。
   *
   * <p>返回<b>逐目标</b>的结果：某个目标失败时，其余目标照旧派发成功。
   */
  @PostMapping("/jobs/{jobId}/dispatch")
  public ListingPublishDispatchResponse dispatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String jobId) {
    return call(auth, "channel.operation.update", a -> service.dispatch(jobId));
  }

  /** 单个目标。 */
  @GetMapping("/targets/{targetId}")
  public ListingPublishTargetRow target(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String targetId) {
    return call(auth, "channel.operation.read", a -> service.target(targetId));
  }

  /**
   * 平台回执：成功 / 驳回+原因 / 失败+原因。
   *
   * <p>由平台适配器或 2.5.1.2 的核验通道调用；驳回与失败**分得开** ——
   * 驳回要改内容，失败可以原样重试。
   */
  @PostMapping("/targets/{targetId}/report")
  public ListingPublishTargetRow report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String targetId,
      @Valid @RequestBody ListingPublishTargetReportRequest request) {
    return call(auth, "channel.operation.update", a -> service.report(targetId, request));
  }

  /**
   * 🔴 独立重试：只重试**这一个**失败目标，内容快照不变。
   *
   * <p>已驳回的目标不能重试（内容没改，重发只会再被驳回一次）。
   */
  @PostMapping("/targets/{targetId}/retry")
  public ListingPublishTargetRow retry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String targetId) {
    return call(auth, "channel.operation.update", a -> service.retry(targetId));
  }
}
