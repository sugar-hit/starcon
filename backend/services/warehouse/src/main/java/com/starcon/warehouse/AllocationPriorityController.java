package com.starcon.warehouse;

import static com.starcon.warehouse.AllocationPriorityModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
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
 * 7.8.1.2 优先级与保底。
 *
 * <p>蓝图：「在分摊冲突时按优先级裁决，并为高价值店铺设置保底可用量与安全垫，防止低优先级店铺长期断货影响其店铺
 * 绩效与平台考核。输入：店铺优先级；保底数量/比例；当前销售与绩效。输出：分摊结果（含被压低店铺的提示）；
 * 保底生效记录。」
 *
 * <p>命名空间 {@code /warehouse/inventory/allocation-priority}，权限码复用
 * {@code warehouse.inventory.read} / {@code warehouse.inventory.update} —— 与 7.8.1.1 同一套。
 * 刻意**不**新造权限码：保底是分摊算法的一部分，看保底与看分摊是同一份配置的事实，
 * 拆成两个权限码只会让人以为存在「能改分摊但改不了保底」这种中间状态。
 *
 * <p>刻意独立成 {@code AllocationPriorityController} 而不是并进 {@code AllocationPolicyController}：
 * 那是 7.8.1.1 那条车道的文件，各车道并行时各改各的才不会撞（同 7.4.2.2 的做法）。
 *
 * <p><b>整个路径上没有 DELETE 端点，而且这不是遗漏</b>：保底档与生效记录都是事实。
 * 改保底走「收口旧档 + 开新档」、作废走 {@code /retire}，生效记录 append-only
 * （V76 的 REVOKE DELETE 与不可变触发器会在权限层和数据库层各挡一次）。
 */
@RestController
@RequestMapping("/warehouse")
public class AllocationPriorityController {

  private final AccessService access;
  private final AllocationPriorityService floors;

  public AllocationPriorityController(AccessService access, AllocationPriorityService floors) {
    this.access = access;
    this.floors = floors;
  }

  /**
   * 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），
   * 再在一个事务里绑定 RLS 租户上下文执行查询（与 7.8.1.1 同一个 call）。
   */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 能力

  /**
   * 保底能力：两种保底口径、四种「被压低」原因，以及四条必须如实讲清楚的说明
   * （绩效依据为何不可用、可用量为何必须由调用方传入、保底池与另两个「缓冲」的区别、下游交接）。
   */
  @GetMapping("/inventory/allocation-priority/capabilities")
  public PriorityCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> floors.capabilities());
  }

  // ------------------------------------------------------------ 保底分档

  /** 保底分档列表（真分页；{@code effectiveOnly=true} 只看此刻仍在生效的档）。 */
  @GetMapping("/inventory/allocation-priority/floors")
  public AllocationFloorPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID policyId,
      @RequestParam(required = false) UUID referenceId,
      @RequestParam(required = false, defaultValue = "false") boolean effectiveOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a -> floors.page(new FloorQuery(policyId, referenceId, effectiveOnly, keyword, page, size)));
  }

  /** 保底详情。跨租户在 RLS 下查不到 → 404，不泄露存在性。 */
  @GetMapping("/inventory/allocation-priority/floors/{id}")
  public AllocationFloorRow detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "inventory.read", a -> floors.detail(id));
  }

  /**
   * 新建一档保底。
   *
   * <p>权限码是 {@code warehouse.inventory.update} 而不是 read：保底承诺一旦写进分摊，
   * 它就会真的从别的店手里拿走货，把「改这道闸门」挂在读权限上，那道闸门等于没有。
   */
  @PostMapping("/inventory/allocation-priority/floors")
  public AllocationFloorSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AllocationFloorInput input) {
    return call(auth, "inventory.update", a -> floors.save(null, input, a.userId()));
  }

  /**
   * 换版：收口当前在效的那一档，再开新档。
   *
   * <p>刻意不给「改这一档」的 PUT —— 保底内容写定后不可改（V76 触发器在 DB 层挡住），
   * 改保底只能通过收口 + 新开，历史那一档因此永远是「当时真正承诺过什么」的凭据。
   */
  @PostMapping("/inventory/allocation-priority/floors/{id}/versions")
  public AllocationFloorSaved supersede(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody AllocationFloorInput input) {
    return call(auth, "inventory.update", a -> floors.save(id, input, a.userId()));
  }

  /** 作废一档保底（收口它的生效区间）。用 POST 而不是 DELETE：作废是审计事件，档位行必须永久保留。 */
  @PostMapping("/inventory/allocation-priority/floors/{id}/retire")
  public AllocationFloorSaved retire(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String at) {
    return call(auth, "inventory.update", a -> floors.retire(id, moment(at)));
  }

  // ------------------------------------------------------------ 分摊

  /** 带保底的分摊**试算**。一行都不写。 */
  @PostMapping("/inventory/allocation-priority/calculation")
  public List<PriorityAllocationResult> calculate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PriorityAllocationRequest request) {
    return call(auth, "inventory.read", a -> floors.preview(request, a.userId()));
  }

  /**
   * 带保底的分摊并落库。
   *
   * <p>与 {@code /calculation} 刻意分成两个端点而不是靠一个 {@code persist} 布尔切换：
   * 「看一眼」和「写一笔事实」的风险等级不同，权限也不同。
   */
  @PostMapping("/inventory/allocation-priority/runs")
  public List<PriorityAllocationResult> run(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PriorityAllocationRequest request) {
    return call(auth, "inventory.update", a -> floors.runOnce(request, a.userId()));
  }

  // ------------------------------------------------------------ 保底生效记录

  /**
   * 保底生效记录列表（真分页）。
   *
   * <p>{@code shortfallOnly} 与 {@code suppressedOnly} 是**两个不同的病**，刻意分成两个参数而不是一个
   * 「异常」开关：前者是「承诺了但没兑现」（该补货/该调保底），后者是「没轮到你而货被别人拿走了」
   * （该调优先级/该调保底）。蓝图那句「『没货卖』和『没分到货』是两种问题」就落在这两个参数上。
   */
  @GetMapping("/inventory/allocation-priority/resolutions")
  public ResolutionPage resolutions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID runId,
      @RequestParam(required = false) UUID referenceId,
      @RequestParam(required = false, defaultValue = "false") boolean shortfallOnly,
      @RequestParam(required = false, defaultValue = "false") boolean suppressedOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a -> floors.resolutions(runId, referenceId, shortfallOnly, suppressedOnly, page, size));
  }

  /** 解析可选的 ISO-8601 时刻；解析不了就报 400 而不是悄悄用当前时刻（与 7.8.1.1 同一句理由）。 */
  private static OffsetDateTime moment(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(raw);
    } catch (RuntimeException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "时刻格式非法：" + raw + "（应为 ISO-8601 带偏移，例如 2026-10-07T10:00:00+08:00）。"
              + "悄悄改成当前时刻会让「这一档当时保不保得住」这个问题得到一个假的答案。");
    }
  }
}