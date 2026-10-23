package com.starcon.channel;

import static com.starcon.channel.SyncHealthModels.*;

import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 1.5.1.1 同步与 API 健康 —— channel 侧只读投影端点。
 *
 * <p>蓝图：「一屏回答"现在能不能正常同步"：各平台连接状态、最近同步时间、失败计数、
 * 限流命中、队列积压，按店铺/连接展示红黄绿。输出：连接健康卡片 + 需处理项跳转
 * （重授权、看失败队列、查积压）。」
 *
 * <p>🔴 端点清单：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：红黄绿规则表（枚举 → 严重度）、阈值、
 *       失败计数口径、两个「最近同步时刻」的口径、积压统计了哪几张表。</li>
 *   <li>{@code GET /options} —— 筛选项（平台码来自**真实连接**，不是写死的常量）。</li>
 *   <li>{@code GET /summary} —— 总览（红黄绿分布 + 失败 / 限流计数 + 积压 + 没有连接的平台）。</li>
 *   <li>{@code GET /connections} —— 连接健康卡片（**真分页 + 真筛选**）。</li>
 *   <li>{@code GET /connections/{id}} —— 单张卡片。</li>
 *   <li>{@code GET /pending-items} —— 需处理项（只给**本模块真实存在**的待处理项，
 *       落点是**归属系统**的页面）。</li>
 * </ul>
 *
 * <p>🔴 <b>本控制器刻意没有任何写端点</b>：卷 01 的边界写死「工作台只读 + 跳转；
 * 一切状态变更回到归属系统完成」。「重试同步 / 重新授权 / 改状态」都归 02 渠道与店铺，
 * 在这里放行一个不存在的写入口，比 404 更糟（界面会以为功能已交付）。
 *
 * <p>权限码<b>复用</b> 02.4 同步运行已有的 {@code channel.sync.read}（读），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/sync-health")
public class SyncHealthController {

  private final AccessService access;
  private final SyncHealthService service;

  public SyncHealthController(AccessService access, SyncHealthService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：红黄绿规则表、阈值、计数口径、积压来源、未接通 / 本模块未接入的下游。
   *
   * <p>🔴 页面上的红黄绿**不是前端算的**：规则表在这里逐条公布，判定在服务端。
   */
  @GetMapping("/capabilities")
  public SyncHealthCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, SyncHealthService.READ, a -> service.capabilities());
  }

  // ---- 筛选项 ----

  /** 筛选项下拉（平台码来自真实连接）。 */
  @GetMapping("/options")
  public SyncHealthOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, SyncHealthService.READ, a -> service.options());
  }

  // ---- 总览 ----

  /** 总览：红黄绿分布、失败与限流计数、队列积压、没有连接的平台。 */
  @GetMapping("/summary")
  public SyncHealthSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, SyncHealthService.READ, a -> service.summary());
  }

  // ---- 连接健康卡片 ----

  /** 连接健康卡片列表（真分页 + 真筛选）。 */
  @GetMapping("/connections")
  public SyncHealthConnectionPage connections(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        SyncHealthService.READ,
        a -> service.connections(platformCode, status, keyword, page, size));
  }

  /** 单张连接健康卡片。跨租户与不存在返回逐字相同的 404。 */
  @GetMapping("/connections/{id}")
  public SyncHealthConnectionCard connection(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, SyncHealthService.READ, a -> service.connection(id));
  }

  // ---- 需处理项 ----

  /**
   * 需处理项（只给本模块真实存在的待处理项）。
   *
   * <p>🔴 每一条都指向**归属系统**的页面；本模块只读，**不**提供重试 / 重新授权端点。
   */
  @GetMapping("/pending-items")
  public SyncHealthPendingList pendingItems(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, SyncHealthService.READ, a -> service.pendingItems());
  }
}
