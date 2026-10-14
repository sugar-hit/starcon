package com.starcon.warehouse;

import static com.starcon.warehouse.WarehouseConfigModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 8.1.1.3 仓库配置。
 *
 * <p>蓝图原文：「配置仓库行为参数：是否支持批次/序列号、拣货策略（按订单/按波次/按库位）、 默认复核比例、是否允许拆零发货、工作时段与班次。」以及关系段的红线：
 * 「**配置影响作业方式**，不是纯展示项，修改需记录变更。」
 *
 * <p>路由挂在 {@code /warehouse/facilities/{id}/config} 之下，**不**新开一个顶层前缀： 配置是仓库的属性，挂在仓库档案之下与蓝图 08
 * 的目录（8.1.1 仓库档案 → 8.1.1.3 配置）一致。
 *
 * <p>权限码分两支，逐条说清理由：
 *
 * <ul>
 *   <li>读：{@code warehouse.facilities.read}。复用 8.1.1.1 的码而不是新开一个 ——
 *       本模块读的是自己刚写进去的当前态，可见范围不比「能看仓库详情」更宽。 独立授权只会制造「有 read 没有 read-of-history」这种没人想得清的组合。
 *   <li>写：{@code warehouse.facilities.config}（identity V104 新注册）。 **刻意不复用** {@code
 *       warehouse.facilities.update}（8.1.1.2）：改仓库地址 是可恢复的录入事故，改拣货策略或复核比例是**作业契约** —— 它影响的不是一条记录，
 *       而是之后所有出库作业的执行方式，且已按旧策略执行的部分不会被改回来。 仓库主管（调班次）与行政（填地址）通常也是不同岗位。
 * </ul>
 *
 * <p>端点独立成 {@code WarehouseConfigController}，不并进 {@code WarehouseFacilityController}（8.1.1.1）或
 * {@code WarehouseDetailController} （8.1.1.2）：那两条是并行车道，各改各的才不会在合并时撞同一个文件。
 *
 * <p>**没有任何 DELETE 形状**：班次的调整出口是停用（enabled=false）， 配置的出口是改值。删配置行等于让这个仓「退回没有配置」，而作业单已经在按 那套参数执行了。
 */
@RestController
@RequestMapping("/warehouse/facilities")
public class WarehouseConfigController {

  private final AccessService access;
  private final WarehouseConfigService config;

  public WarehouseConfigController(AccessService access, WarehouseConfigService config) {
    this.access = access;
    this.config = config;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头）， 再在一个事务里绑定 RLS 租户上下文执行查询（与 8.1.1.1 同一个 call）。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 配置页一次取全：仓库标识（给班次标时区）+ 当前配置 + 班次 + 变更历史 + 下游影响声明。
   *
   * <p>一次返回而不是四个端点：这是一个**编辑页**，四个请求会在数据版本不一致时 把「班次是改过的、配置是没改过的」拼在一起显示。
   *
   * <p>{@code editable=false}（归档仓）时前端整个编辑入口关掉 —— 归档是终态，让用户提交了再报错是更差的体验（与 8.1.1.2 同一纪律）。
   */
  @GetMapping("/{id}/config")
  public ConfigOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String scope,
      @RequestParam(required = false) UUID shiftId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "facilities.read",
        a -> config.overview(id, new ChangeQuery(scope, shiftId, page, size)));
  }

  /** 下拉项：拣货策略三选一（含「为什么这么选」的说明）、本仓班次、留痕范围。 */
  @GetMapping("/{id}/config/options")
  public ConfigOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "facilities.read", a -> config.options(id));
  }

  /**
   * 配置对下游作业的影响声明。
   *
   * <p>单列一个端点而不是塞进 overview：它**不依赖仓库 id**（逐条都是本服务的交付状态）， 而把它埋进页面数据里，前端就会在页面上渲染出一块「全都不可用」的提示，
   * 却看不出每一条到底差什么。
   */
  @GetMapping("/{id}/config/impacts")
  public List<ConfigImpact> impacts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "facilities.read", a -> config.impacts());
  }

  /**
   * 变更历史（真分页 + 按范围/班次筛选）。
   *
   * <p>独立成端点而不只是 overview 里的一页：历史会长，运营也常单独来查 「这个仓的复核比例上次是谁改的」，不需要顺带把配置与班次再拉一遍。
   */
  @GetMapping("/{id}/config/changes")
  public ChangePage changes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String scope,
      @RequestParam(required = false) UUID shiftId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "facilities.read",
        a -> config.changes(id, new ChangeQuery(scope, shiftId, page, size)));
  }

  /**
   * 修改仓库行为参数（整份替换）。
   *
   * <p>返回里带 {@code changeCount} 与本次实际写下的留痕条目：让界面能立刻告诉用户 「本次变更已留痕 N 条」，而不必让用户自己去翻历史页确认。
   *
   * <p>归档仓会被拒（400）：归档是终态，界面上 {@code editable=false} 时应已关掉入口， 这里再拒一次是为了不给绕过界面的调用方留口子。
   */
  @PutMapping("/{id}/config")
  public WarehouseConfigSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody WarehouseConfigInput input) {
    return call(auth, "facilities.config", a -> config.update(id, input, a.userId()));
  }

  /** 新建班次。跨夜由服务端按起止时刻算出，不接受调用方传入。 */
  @PostMapping("/{id}/config/shifts")
  public ShiftSaved createShift(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ShiftInput input) {
    return call(auth, "facilities.config", a -> config.createShift(id, input, a.userId()));
  }

  /**
   * 改班次（含启用状态 = 停用）。
   *
   * <p>**没有 DELETE 形状**：停用是排班调整的出口。删掉一个班次会让 「上周三夜班是谁上的」这个问题失去载体 —— 变更留痕会指向一个不存在的班次。
   */
  @PutMapping("/{id}/config/shifts/{shiftId}")
  public ShiftSaved updateShift(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID shiftId,
      @Valid @RequestBody ShiftUpdateInput input) {
    return call(auth, "facilities.config", a -> config.updateShift(id, shiftId, input, a.userId()));
  }
}
