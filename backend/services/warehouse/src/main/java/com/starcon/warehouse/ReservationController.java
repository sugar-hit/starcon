package com.starcon.warehouse;

import static com.starcon.warehouse.ReservationModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 7.2.1.1 预占需求列表。
 *
 * <p>蓝图：「展示所有预占需求（来自订单履约、加工领料、其他占用），含需求来源、数量、
 * 状态（held/committed/consumed/released/expired）、剩余量、超时时长。」输出「预占需求列表 + 状态汇总」。
 *
 * <p>命名空间是 {@code /warehouse/inventory/reservations}（蓝图 07 的 L3 锚点路由 {@code /inventory/reservation}
 * 的复数形，与 7.1.x 各列表端点一致）。 刻意<strong>不</strong>与 7.2.1.2 / 7.2.1.3 共用一个扁平路径：三条车道并行时各改各的 才不会撞同一行白名单。
 *
 * <p>权限码是 {@code warehouse.inventory.read} / {@code warehouse.inventory.update}， 与 7.1.x 各库存端点共用同一组
 * —— 预占需求与库存余额属同一风险面（都是「这批货还在不在」）， 没有理由给它一套更宽的权限码。
 *
 * <p>租户**绝不**来自任何客户端头：先向 identity 确认身份与权限（{@link AccessService#require}）， 再在一个事务里绑定 RLS
 * 租户上下文执行查询。伪造的 {@code X-Tenant-Id} 头进不到这个上下文里。
 */
@RestController
@RequestMapping("/warehouse/inventory/reservations")
public class ReservationController {

  private final AccessService access;
  private final ReservationService reservations;

  public ReservationController(AccessService access, ReservationService reservations) {
    this.access = access;
    this.reservations = reservations;
  }

  /** 统一入口：身份与权限交给 identity 确认，租户上下文在事务内绑定后才执行查询。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 预占需求列表：筛选（状态 / 来源类型 / 来源系统 / 时间范围 / 超时 / 法人 / SKU） + 关键字 + 排序 + 真分页。
   *
   * <p>时间参数按 ISO-8601 带偏移量收（{@code 2026-10-13T00:00:00+08:00}）。 用
   * {@code @DateTimeFormat(ISO.DATE_TIME)} 是为了让它**必须**带偏移量 —— 裸的 {@code 2026-10-13T00:00:00} 会被
   * Spring 按 JVM 默认时区解释， 服务器换个 TZ 同一个筛选就筛出不同结果，而用户看到的是同一个时间。
   *
   * <p>响应里的 {@code summary} 是**不受分页与筛选影响**的全量状态汇总。
   */
  @GetMapping
  public ReservationPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) String sourceSystem,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime requestedFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime requestedTo,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            reservations.list(
                new ReservationQuery(
                    status,
                    sourceType,
                    sourceSystem,
                    requestedFrom,
                    requestedTo,
                    overdueOnly,
                    legalEntityId,
                    skuId,
                    keyword,
                    sort,
                    page,
                    size)));
  }

  /**
   * 筛选与录入下拉项：五个状态词表（含是否终态）、三个来源系统、三个来源类型、六个排序键。
   *
   * <p>下拉项是「填什么」，能力声明是「哪些维度有数」，两件事分开返回 —— 混在一个端点里时前端会把「已分配量恒为 0」读成「下拉里没有这一项」而不是「这项还没接入」。
   */
  @GetMapping("/options")
  public ReservationOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> reservations.options());
  }

  /**
   * 预占链路各环节的接入情况。
   *
   * <p>本模块只交付「需求列表 + 登记」。7.2.1.2 分配明细与 7.2.1.3 释放/过期显式报 {@code available=false} 并写明归哪个模块 ——
   * 前端据此禁用入口并说明去向。
   */
  @GetMapping("/capabilities")
  public List<ReservationCapability> capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> reservations.capabilities());
  }

  /**
   * 登记一条预占需求。
   *
   * <p>蓝图上游（09 订单履约、6.4.1.1 加工领料）都是**跨服务**调本服务的授权 API， 所以这里提供写入入口 —— 否则列表永远只有空表。
   *
   * <p>{@code heldAt} **建议显式传**：它是超时时长的唯一基准，不传则取服务端当前时刻。
   * 跨服务调用不传等于把「上游什么时候提的需求」这个事实丢掉，只留下「本服务什么时候收到的」。
   *
   * <p>单号由本服务发，**不采信调用方传入的单号**：采信的话两个上游各发一次 {@code RSV-000001} 就会撞唯一键，而调用方拿到的是一个指不到具体行的 409。
   * 上游的去重键是 {@code source_system + reference_id}。
   *
   * <p>只允许建成 HELD：终态只能由 7.2.1.2 / 7.2.1.3 的状态迁移产生， 「插一行 CONSUMED」在 DB 层被 {@code
   * guard_reservation_insert} 拒。
   */
  @PostMapping
  public ReservationCreated create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ReservationCreateInput input) {
    return call(auth, "inventory.update", a -> reservations.create(a.tenantId(), input));
  }
}
