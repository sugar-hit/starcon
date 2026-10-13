package com.starcon.warehouse;

import static com.starcon.warehouse.ReservationAllocationModels.*;
import static com.starcon.warehouse.ReservationModels.ReservationCapability;

import jakarta.validation.Valid;
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
 * 7.2.1.2 预占分配明细。
 *
 * <p>蓝图：「展示一个预占需求实际分配到了哪些 bucket（哪个仓、哪个货位、哪个批次），以及每个分配的
 * held→终态迁移记录。」输出「分配明细行 + 状态迁移时间线」。
 *
 * <p>命名空间挂在 {@code /warehouse/inventory/reservations/{reservationId}} 之下，
 * 与 7.2.1.1 的集合级端点（列表 / options / capabilities）**同一前缀、不同层级**：
 * 集合级三个形状是 7.2.1.1 的，明细级四个形状是本模块的，两条车道各改各的形状、
 * 只有白名单那一条正则要合。
 *
 * <p>路径里刻意用 {@code /timeline} 而不是 {@code /entries}：对外说的是「时间线」这个概念
 * （蓝图输出原话），而不是表的内部名字。表叫 reservation_entry 是存储细节。
 *
 * <p>权限码是 {@code warehouse.inventory.read} / {@code warehouse.inventory.update}，
 * 与 7.2.1.1 及 7.1.x 各库存端点共用同一组 —— 分配明细与库存余额属同一风险面。
 *
 * <p>租户**绝不**来自任何客户端头：先向 identity 确认身份与权限（{@link AccessService#require}），
 * 再在一个事务里绑定 RLS 租户上下文后执行查询。伪造的 {@code X-Tenant-Id} 头进不到这个上下文里。
 */
@RestController
@RequestMapping("/warehouse/inventory/reservations/{reservationId}")
public class ReservationAllocationController {

  private final AccessService access;
  private final ReservationAllocationService allocations;

  public ReservationAllocationController(
      AccessService access, ReservationAllocationService allocations) {
    this.access = access;
    this.allocations = allocations;
  }

  /** 统一入口：身份与权限交给 identity 确认，租户上下文在事务内绑定后才执行查询。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 分配明细行 + 预占抬头 + 分配汇总。
   *
   * <p>筛选：状态（可多选）、仓 / 货位 / 批次（精确匹配，候选值来自 {@code /allocation-options}
   * 而不是全库）、关键字（仓编码 / 仓名 / 货位编码 / 货位名 / 批次号 / 备注）、排序、真分页。
   *
   * <p>{@code summary} 是**不受分页与筛选影响**的全量分配汇总。
   */
  @GetMapping("/allocations")
  public ReservationAllocationPage allocations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID reservationId,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID locationId,
      @RequestParam(required = false) UUID lotId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            allocations.allocations(
                a.tenantId(),
                reservationId,
                new ReservationAllocationQuery(
                    status, warehouseId, locationId, lotId, keyword, sort, page, size)));
  }

  /**
   * 状态迁移时间线。
   *
   * <p>按发生时刻正序；可只看某一条分配（{@code allocationId}）或某一种迁移（{@code transition}）。
   * 默认一页 50 条 —— 时间线比明细长得多，单页 20 条会让人一直点「下一页」。
   */
  @GetMapping("/timeline")
  public ReservationAllocationTimeline timeline(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID reservationId,
      @RequestParam(required = false) UUID allocationId,
      @RequestParam(required = false) String transition,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            allocations.timeline(
                a.tenantId(),
                reservationId,
                new ReservationAllocationTimelineQuery(allocationId, transition, page, size)));
  }

  /**
   * 下拉项：五个分配状态、三个可触发迁移、本预占实际出现过的仓 / 货位 / 批次、五个排序键。
   *
   * <p>候选值**只取这条预占出现过的**，不是全库：明细页的筛选器问的是「这笔预占圈过哪些地方」。
   */
  @GetMapping("/allocation-options")
  public ReservationAllocationOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID reservationId) {
    return call(
        auth,
        "inventory.read",
        a -> allocations.options(a.tenantId(), reservationId));
  }

  /**
   * 链路各环节的接入情况。
   *
   * <p>复用 7.2.1.1 的 {@code ReservationCapability} 而不是另造一份形状相同的类型：
   * 同一条预占链路的能力声明只能有一种表示，否则前端要同时认两套结构。
   *
   * <p>释放与过期显式报 {@code available=false} 并写明归 7.2.1.3 ——
   * 前端据此不渲染释放按钮，而不是渲染一个点下去 404 的按钮。
   */
  @GetMapping("/capabilities")
  public List<ReservationCapability> capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID reservationId) {
    // 预占必须存在且可读，否则这是一条与当前租户无关的能力清单 —— 没有意义。
    return call(
        auth,
        "inventory.read",
        a -> {
          allocations.options(a.tenantId(), reservationId);
          return allocations.capabilities();
        });
  }

  /**
   * 登记分配：把这笔预占圈到一个（仓 / 货位 / 批次）上。
   *
   * <p>{@code allocatedAt} **建议显式传**：它是时间线的第一笔，也是批次追溯的时间基准。
   * 不传则取服务端当前时刻 —— 跨服务调用不传等于把「什么时候圈的」换成「本服务什么时候知道的」。
   *
   * <p>跨行守恒（所有分配之和 ≤ 需求量）由 DB 触发器兜底，Java 层的判断可以被一条裸 SQL 绕开，
   * 而超卖不可逆。仓 / 货位 / 批次的自洽（货位属于所声明的仓、批次属于预占的 SKU）
   * 在服务层先判一遍，好给出能指到具体字段的错误文案。
   */
  @PostMapping("/allocations")
  public ReservationAllocationCreated allocate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID reservationId,
      @Valid @RequestBody ReservationAllocationCreateInput input) {
    return call(
        auth,
        "inventory.update",
        a -> allocations.allocate(a.tenantId(), reservationId, input));
  }

  /**
   * 承诺分配：HELD → COMMITTED。
   *
   * <p>只在 HELD 上可承诺。承诺后**不再自动 TTL 过期**（蓝图 7.2.1.3 红线），所以本端点
   * 不提供任何「撤销承诺」入口 —— 撤销等于把货还回池子，而那正是释放模块的职责。
   *
   * <p>全部分配都承诺时，父预占同步进入 COMMITTED。不这样同步的话，预占会一直停在 HELD，
   * 超时清理会把已承诺发货的货误释放。
   */
  @PostMapping("/allocations/{allocationId}/commit")
  public ReservationAllocationTransitioned commit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID reservationId,
      @PathVariable UUID allocationId,
      @RequestBody(required = false) ReservationAllocationTransitionInput input) {
    return call(
        auth,
        "inventory.update",
        a -> allocations.commit(a.tenantId(), reservationId, allocationId, input));
  }

  /**
   * 消耗出库：COMMITTED → CONSUMED，支持部分出库。
   *
   * <p>数量不传时一次性出掉该分配的剩余量。刻意**不允许** HELD → CONSUMED：
   * 没承诺就出库等于货没圈住就走了，那正是超卖的定义。
   *
   * <p>覆盖需求量时父预占同步进入 CONSUMED，并把预占的已消费量对齐到各分配之和。
   */
  @PostMapping("/allocations/{allocationId}/consume")
  public ReservationAllocationTransitioned consume(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID reservationId,
      @PathVariable UUID allocationId,
      @RequestBody(required = false) ReservationAllocationTransitionInput input) {
    return call(
        auth,
        "inventory.update",
        a -> allocations.consume(a.tenantId(), reservationId, allocationId, input));
  }
}
