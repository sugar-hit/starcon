package com.starcon.channel;

import static com.starcon.channel.ListingBulkCollectModels.*;

import jakarta.validation.Valid;

import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.4.1.2 批量/插件采集。
 *
 * <p>蓝图：「支持按分类页、关键词搜索结果批量采集，以及浏览器插件在用户浏览时抓取当前页商品，解决大批量找货场景。输入：分类页 URL /
 * 关键词 + 页数范围；插件上报的页面数据。输出：批量采集任务 + 结果清单。关系：上游 = 4.4.1.1；下游 = 4.4.1.3。<b>抓取必须有频率限制与去重</b>
 * （同 URL 不重复抓、已存在的商品跳过），避免重复劳动与外部站点压力。」
 *
 * <p>端点按职责句逐项对应：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 逐维度能力声明（未交付段 available=false + ownerModule + impact），
 *       外加当前平台白名单与限频阈值。
 *   <li>{@code POST /} —— 🔴 发起批量采集（分类页 URL 或 关键词 + 页数范围）。**只建任务**：
 *       限频的拒绝发生在这里（明确的 429 + 可读原因），而不是让调用方在一次长请求里等半天。
 *   <li>{@code POST /{id}/run} —— 🔴 执行这个批量采集（真发 HTTP、真解析、真落库）。
 *   <li>{@code POST /plugin-reports} —— 🔴 浏览器插件上报当前页商品（同样落成采集草稿）。
 *   <li>{@code GET /} —— 批量采集任务列表（真分页 + 模式/状态/来源筛选 + 时间倒序）。
 *   <li>{@code GET /{id}} —— 单个任务详情。
 *   <li>{@code GET /{id}/results} —— 结果清单（真分页 + 状态筛选）。
 * </ul>
 *
 * <p>🔴 路径前缀是 {@code /channel/listing-bulk-collects}：与 4.4.1.1 的
 * {@code /channel/listing-collects} <b>刻意不同</b> —— 两者是不同的动作（一个 URL 的一次采集
 * vs 一批 URL 的批量采集），混在一个前缀下会让下游把两者的形状搞混。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 列表、4.4.1.1 链接采集、
 * 4.4.2.1 商品认领同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/listing-bulk-collects")
public class ListingBulkCollectController {

  private final AccessService access;
  private final ListingBulkCollectService service;

  public ListingBulkCollectController(AccessService access, ListingBulkCollectService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：未交付的维度 available=false + ownerModule + impact，外加当前平台白名单与限频阈值。
   *
   * <p>前端据此显示「只产生草稿」「图片只记 URL」「插件扩展本体不在本仓」等**未交付段明账**，
   * <b>不把未交付的步骤画成已完成</b>。
   */
  @GetMapping("/capabilities")
  public ListingBulkCollectCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 发起与执行 ----

  /**
   * 🔴 发起一次批量采集（分类页 URL 或 关键词 + 页数范围）。
   *
   * <p>超并发 / 发起太密 / 该来源刚被抓过 ⇒ <b>429</b> 并写明还要等多少秒（不是排队等死）。
   * 白名单外的平台或分类页域名 ⇒ <b>400</b> 并说明原因（一个 HTTP 请求都不发）。
   */
  @PostMapping
  public BulkCollectTaskRow start(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody StartBulkCollectRequest request) {
    return call(auth, "channel.operation.create", a -> service.start(request, a.userId()));
  }

  /**
   * 🔴 执行一次批量采集（真发 HTTP 请求、真解析、真落成采集草稿）。
   *
   * <p>被限频打断时任务停在 {@code RATE_LIMITED}，已采到的结果全部保留，**再调一次这个端点即可接着采**。
   * 已结束（SUCCEEDED / FAILED）的任务再跑 ⇒ <b>409</b>。
   */
  @PostMapping("/{id}/run")
  public BulkCollectTaskRow run(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.create", a -> service.run(id, a.userId()));
  }

  /**
   * 🔴 浏览器插件上报当前页商品。
   *
   * <p>插件报上来的数据**同样落成采集草稿**（4.4.1.1 的 {@code listing_collect_draft}），
   * 走的是同一套去重：已存在的商品跳过，不重复建草稿。
   */
  @PostMapping("/plugin-reports")
  public PluginReportResult reportPlugin(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PluginReportRequest request) {
    return call(auth, "channel.operation.create", a -> service.reportPlugin(request, a.userId()));
  }

  // ---- 读取 ----

  /** 批量采集任务列表（真分页 + 模式/状态/来源筛选 + 时间倒序）。 */
  @GetMapping
  public BulkCollectPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String mode,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String sourceCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.listTasks(mode, state, sourceCode, page, size));
  }

  /** 单个批量采集任务详情。 */
  @GetMapping("/{id}")
  public BulkCollectTaskRow detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.detail(id));
  }

  /** 结果清单（真分页 + 状态筛选）。 */
  @GetMapping("/{id}/results")
  public BulkCollectResultPage results(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false) String state,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.listResults(id, state, page, size));
  }
}
