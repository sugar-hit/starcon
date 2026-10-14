package com.starcon.warehouse;

import static com.starcon.warehouse.PickWaveModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 8.3.2.2 波次与合并拣货入口。
 *
 * <p>命名空间 {@code /warehouse/pick-waves}，<b>顶层分支、刻意不挂在 {@code outbound-orders}
 * 之下</b> —— 与 8.3.2.1 的 {@code pick-tasks} 同一理由：波次是<b>跨订单的排班</b>，
 * 一个波次底下挂着多张出库单的拣货任务，它在契约上从来不属于任何一张出库单；
 * 挂成 {@code outbound-orders/{id}/waves} 那种子资源形状会让「波次」看起来像
 * 某一张单的附属品，而实际上八张单的排班聚成一波时，任取一张都代表不了这一波。
 *
 * <p>十四个形状，刻意逐个列出、不做通配：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与上下游声明
 *   <li>{@code GET /rules} 合并规则清单（含各规则当前认领数 / 在等数）
 *   <li>{@code POST /rules} 新建合并规则（承运渠道 / 库区 / 时段 / 订单数上限）
 *   <li>{@code POST /rules/{ruleId}/retire} 停用规则（必填原因）
 *   <li>{@code GET /candidates} 合并预览：按合并键分组 + 三个计数
 *   <li>{@code POST /} 建波次（按规则或手工指定成员）
 *   <li>{@code GET /} 列表（真分页 / 真筛选 / 排序白名单 / 汇总）
 *   <li>{@code GET /{waveId}} 详情（单头 + 成员位置 + 流水 + 合并报告）
 *   <li>{@code POST /{waveId}/release} 放行开波（必填负责人）
 *   <li>{@code POST /{waveId}/complete} 完成波次（全部成员工单终态才允许）
 *   <li>{@code POST /{waveId}/cancel} 取消波次（必填原因，<b>不动库存</b>）
 *   <li>{@code POST /{waveId}/tasks/{pickTaskId}/detach} 移出一张成员工单
 * </ul>
 *
 * <p>刻意<b>没有</b> PUT / DELETE：成员归属是排班事实（移出走 detach 并留流水），
 * 已完成的波次是交给复核的凭据。要更正就「移出 + 重新排波」，要放弃就取消留原因。
 *
 * <p><b>权限码复用 {@code warehouse.inventory.read} / {@code warehouse.inventory.update}</b>，
 * 不新起 {@code warehouse.picking.*}：identity 只注册了 warehouse 的两个页面资源
 * （inventory / facilities），用一个没注册的码会让连 OWNER 都 403 ——
 * 页面全绿而所有人点不动，是最难现场发现的一种配置错误。
 */
@RestController
@RequestMapping("/warehouse/pick-waves")
public class PickWaveController {

  private final PickWaveService pickWaves;
  private final AccessService access;

  public PickWaveController(PickWaveService pickWaves, AccessService access) {
    this.pickWaves = pickWaves;
    this.access = access;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：三个合并维度各自的真伪、库区怎么推导、时段按谁的时区、
   * 「合并不改订单粒度」这一条，以及库存边界（<b>本模块一动库存都不动</b>）。
   */
  @GetMapping("/capabilities")
  public PickWaveCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> pickWaves.capabilities());
  }

  /** 合并规则清单。每条带当前认领数与「在等上一波收工」的数。 */
  @GetMapping("/rules")
  public PickWaveRuleList rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId) {
    return call(auth, "inventory.read", a -> pickWaves.rules(warehouseId));
  }

  /** 新建合并规则：承运渠道 / 库区 / 时段 / 订单数上限。 */
  @PostMapping("/rules")
  public PickWaveRuleRow createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PickWaveRuleInput input) {
    return call(auth, "inventory.update", a -> pickWaves.createRule(input, a.userId()));
  }

  /** 停用合并规则。必填原因。 */
  @PostMapping("/rules/{ruleId}/retire")
  public PickWaveRuleRow retireRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody PickWaveRuleRetireInput input) {
    return call(auth, "inventory.update", a -> pickWaves.retireRule(ruleId, input, a.userId()));
  }

  /**
   * 合并预览：按合并键（承运渠道 × 库区）分组，每组带三个计数
   * —— 现在就能并 / 得等上一波收工 / 没有规则认领。
   */
  @GetMapping("/candidates")
  public PickWaveCandidatePage candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID warehouseId) {
    return call(auth, "inventory.read", a -> pickWaves.candidates(warehouseId));
  }

  /** 建波次：按规则自动挑，或手工指定成员。合并不改订单粒度。 */
  @PostMapping
  public PickWaveCreated create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PickWaveCreateInput input) {
    return call(auth, "inventory.update", a -> pickWaves.create(input, a.userId()));
  }

  /** 波次列表：真分页 / 真筛选 / 排序白名单 / 汇总。 */
  @GetMapping
  public PickWavePage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) UUID zoneId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            pickWaves.page(
                warehouseId, status, carrierCode, zoneId, keyword, sort, page, size));
  }

  /** 详情：单头 + 成员（按位置）+ 流水 + 合并报告。 */
  @GetMapping("/{waveId}")
  public PickWaveDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID waveId) {
    return call(auth, "inventory.read", a -> pickWaves.detail(waveId));
  }

  /** 放行开波：PLANNED → PICKING。必须给负责人。 */
  @PostMapping("/{waveId}/release")
  public PickWaveDetail release(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID waveId,
      @Valid @RequestBody PickWaveReleaseInput input) {
    return call(auth, "inventory.update", a -> pickWaves.release(waveId, input, a.userId()));
  }

  /** 完成波次：全部成员工单到终态才允许（DB 层还有一道守卫）。 */
  @PostMapping("/{waveId}/complete")
  public PickWaveDetail complete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID waveId,
      @RequestBody(required = false) PickWaveCompleteInput input) {
    return call(auth, "inventory.update", a -> pickWaves.complete(waveId, input, a.userId()));
  }

  /** 取消波次：必填原因。<b>不动库存</b>，成员工单放回候选池。 */
  @PostMapping("/{waveId}/cancel")
  public PickWaveDetail cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID waveId,
      @Valid @RequestBody PickWaveCancelInput input) {
    return call(auth, "inventory.update", a -> pickWaves.cancel(waveId, input, a.userId()));
  }

  /** 移出一张成员工单（只在「已排波」时允许）。位置由 V113 触发器重排为连续序号。 */
  @PostMapping("/{waveId}/tasks/{pickTaskId}/detach")
  public PickWaveDetail detach(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID waveId,
      @PathVariable UUID pickTaskId) {
    return call(auth, "inventory.update", a -> pickWaves.detach(waveId, pickTaskId, a.userId()));
  }
}