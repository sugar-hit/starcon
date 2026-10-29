package com.starcon.channel;

import static com.starcon.channel.ServiceTaskStatusModels.*;

import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 1.5.2.1 服务与任务状态 —— channel 侧只读投影端点。
 *
 * <p>蓝图：「展示平台自身运行状态：后台任务 / 同步任务最近运行与耗时、失败任务、
 * 计划任务是否停滞，便于运维与用户自助判断"是系统问题还是数据问题"。
 * 输出：任务状态列表；停滞 / 失败任务提示。」
 *
 * <p>🔴 端点清单：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：规则表、阈值、<b>逐张表</b>的来源清单、
 *       失败分类说明、未接通 / 未开发的上游。</li>
 *   <li>{@code GET /options} —— 筛选项（来源取自注册表，触发来源取自真实数据）。</li>
 *   <li>{@code GET /summary} —— 总览：任务状态分布、停滞与失败计数、失败分类计数、
 *       逐来源统计。</li>
 *   <li>{@code GET /tasks} —— 任务状态列表（<b>真分页 + 真筛选</b>）。</li>
 *   <li>{@code GET /tasks/{id}} —— 单条任务。</li>
 *   <li>{@code GET /alerts} —— 提示清单（<b>停滞与失败是两个独立列表</b>）。</li>
 * </ul>
 *
 * <p>🔴 <b>本控制器刻意没有任何写端点</b>：卷 01 的边界写死「工作台只读 + 跳转；
 * 一切状态变更回到归属系统完成」。「重跑同步 / 取消任务 / 重新授权」都归归属系统，
 * 在这里放行一个不存在的写入口，比 404 更糟（界面会以为功能已交付）。
 *
 * <p>权限码<b>复用</b> 02.4 同步运行已有的 {@code channel.sync.read}（读），
 * identity 服务零改动。🔴 同一个权限码也决定**数据范围**（SELF / DEPARTMENT 时
 * 部分任务表按店铺收敛），所以拿不到该权限的角色看不到那些任务 ——
 * 那是数据范围口径，**不是**「没有任务」。
 */
@RestController
@RequestMapping("/channel/service-task-status")
public class ServiceTaskStatusController {

  private final AccessService access;
  private final ServiceTaskStatusService service;

  public ServiceTaskStatusController(AccessService access, ServiceTaskStatusService service) {
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
   * 能力声明：规则表、阈值、逐张表的来源清单、失败分类说明、未接通 / 未开发的上游。
   *
   * <p>🔴 页面上的严重度**不是前端算的**：规则表在这里逐条公布，判定在服务端。
   */
  @GetMapping("/capabilities")
  public ServiceTaskStatusCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, ServiceTaskStatusService.READ, a -> service.capabilities());
  }

  // ---- 筛选项 ----

  /** 筛选项下拉（来源取自注册表，触发来源取自真实数据）。 */
  @GetMapping("/options")
  public ServiceTaskStatusOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, ServiceTaskStatusService.READ, a -> service.options());
  }

  // ---- 总览 ----

  /** 总览：任务状态分布、停滞与失败计数、失败分类计数、逐来源统计。 */
  @GetMapping("/summary")
  public ServiceTaskStatusSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, ServiceTaskStatusService.READ, a -> service.summary());
  }

  // ---- 任务状态列表 ----

  /** 任务状态列表（真分页 + 真筛选）。 */
  @GetMapping("/tasks")
  public ServiceTaskStatusTaskPage tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String sourceKey,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String triggerSource,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Boolean stalled,
      @RequestParam(required = false) Boolean failed,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        ServiceTaskStatusService.READ,
        a ->
            service.tasks(
                sourceKey, state, triggerSource, keyword, stalled, failed, page, size));
  }

  /** 单条任务。跨租户与不存在返回逐字相同的 404。 */
  @GetMapping("/tasks/{id}")
  public ServiceTaskStatusTaskCard task(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, ServiceTaskStatusService.READ, a -> service.task(id));
  }

  // ---- 提示清单 ----

  /**
   * 提示清单（<b>停滞与失败是两个独立列表</b>）。
   *
   * <p>🔴 每一条都指向**归属系统**的页面；本模块只读，**不**提供重跑 / 取消端点。
   */
  @GetMapping("/alerts")
  public ServiceTaskStatusAlertList alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, ServiceTaskStatusService.READ, a -> service.alerts());
  }
}
