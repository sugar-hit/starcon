package com.starcon.warehouse;

import static com.starcon.warehouse.ReservationReleaseModels.*;

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
 * 7.2.1.3 释放与过期。
 *
 * <p>蓝图：「处理预占的释放（订单取消、支付超时、拆单减少）与过期回收，提供手动释放入口与
 * 批量过期清理，释放时同步恢复可用量。」
 *
 * <p><b>命名空间沿用预占链路的前缀</b>（{@code /warehouse/inventory/reservations}）而不是另起一个
 * 顶层名：释放的对象就是预占，挂在它下面才看得见「释放的是哪一笔」。与 7.2.1.1 的集合级形状、
 * 7.2.1.2 的明细级形状同前缀、不同层级，三条车道各加各的。
 *
 * <p><b>集合级与预占级的形状必须分开且都在白名单里</b>：{@code /release-candidates}、
 * {@code /expire-overdue}、{@code /expiry-runs} 是集合级，{@code /{id}/release}、
 * {@code /{id}/expiry} 是预占级。api-proxy 的正则里，字面量子路径必须排在 {@code [0-9a-f-]+}
 * 之前 —— alternation 首个命中即返回，排错会让 {@code /release} 被当成「id=release」凭空放行。
 *
 * <p>权限码是 {@code warehouse.inventory.read} / {@code warehouse.inventory.update}，
 * 与预占链路其余端点同一组 —— 释放是改库存的动作，风险面与分配一致。
 *
 * <p>租户**绝不**来自任何客户端头：先向 identity 确认身份与权限（{@link AccessService#require}），
 * 再在一个事务里绑定 RLS 租户上下文后执行。伪造的 {@code X-Tenant-Id} 头进不到这个上下文里。
 */
@RestController
@RequestMapping("/warehouse/inventory/reservations")
public class ReservationReleaseController {

  private final AccessService access;
  private final ReservationReleaseService releases;

  public ReservationReleaseController(AccessService access, ReservationReleaseService releases) {
    this.access = access;
    this.releases = releases;
  }

  /** 统一入口：身份与权限交给 identity 确认，租户上下文在事务内绑定后才执行。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 过期候选清单（批量清理的对象）。
   *
   * <p>{@code evaluatedAt} 让页面能「按某个时刻预演」；不传则用服务端当前时刻，
   * 并把判定时刻**逐字返回**给前端显示。
   */
  @GetMapping("/release-candidates")
  public ReservationReleaseCandidatePage candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size,
      @RequestParam(required = false) String evaluatedAt) {
    return call(
        auth,
        "inventory.read",
        a ->
            releases.candidates(
                a.tenantId(),
                new ReservationReleaseQuery(
                    sourceType, keyword, sort, page, size, parseInstant(evaluatedAt))));
  }

  /** 批量过期清理的运行历史。 */
  @GetMapping("/expiry-runs")
  public ReservationReleaseRunPage expiryRuns(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "inventory.read", a -> releases.runs(a.tenantId(), page, size));
  }

  /** 单次运行详情 = 运行凭据 + 它产生的释放凭据。 */
  @GetMapping("/expiry-runs/{runId}")
  public ReservationReleaseRunDetail expiryRun(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID runId) {
    return call(auth, "inventory.read", a -> releases.run(a.tenantId(), runId));
  }

  /** 释放凭据。可按预占过滤（明细页的「这笔被释放过几次」）。 */
  @GetMapping("/release-records")
  public ReservationReleaseRecordPage releaseRecords(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID reservationId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "inventory.read",
        a -> releases.records(a.tenantId(), reservationId, page, size));
  }

  /** 下拉项：释放方式、释放来源、排序键，外加过期规则说明。 */
  @GetMapping("/release-options")
  public ReservationReleaseOptions releaseOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> releases.options());
  }

  /**
   * 链路各环节的接入情况。
   *
   * <p>复用 7.2.1.1 的 {@code ReservationCapability}：同一条预占链路的能力声明只能有一种表示。
   * 「可用量恢复口径」如实报 {@code available=false} 并写明归 7.1.1.3 —— 本模块把货退出占额集合，
   * 口径由 7.1.1.3 裁决。
   */
  @GetMapping("/release-capabilities")
  public List<ReservationModels.ReservationCapability> releaseCapabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> releases.capabilities());
  }

  /**
   * 批量过期清理（显式端点，**无常驻定时器**）。
   *
   * <p>{@code dryRun=true} 只写一条运行凭据，不碰预占 / 分配 / 流水；这是给运营的「先看看会动多少」。
   * {@code idempotencyKey} 让调度器重投时回放既有凭据而不是再扫一遍。
   *
   * <p>一批一个事务：候选判定、逐条终结、流水与凭据写入、运行凭据落库全在其中。
   */
  @PostMapping("/expire-overdue")
  public ReservationReleaseRunRow expireOverdue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody(required = false) ReservationReleaseRunRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> releases.expireRun(a.tenantId(), a.userId(), request));
  }

  /**
   * 手动释放：订单取消 / 支付超时 / 拆单减少 / 人工。
   *
   * <p>三种粒度：不传参数 = 整笔释放（订单取消）；只传 {@code quantity} = FIFO 释放到该量为止
   * （拆单减少）；传 {@code allocations} = 按分配逐条调整。
   *
   * <p>幂等：带 {@code idempotencyKey} 时同键同参回放既有凭据（200 + {@code replayed=true}），
   * 同键不同参 409；不带键时预占已是 RELEASED 同样回放 —— 上游事件是至少投递一次，
   * 第二次到达必须成功而不是把货再还一次。
   */
  @PostMapping("/{reservationId}/release")
  public ReservationReleaseOutcome release(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID reservationId,
      @Valid @RequestBody(required = false) ReservationReleaseInput input) {
    return call(
        auth, "inventory.update", a -> releases.release(a.tenantId(), reservationId, a.userId(), input));
  }

  /**
   * 补写 / 调整 / 清空过期时刻。
   *
   * <p>7.2.1.1 登记预占时没传 {@code expiresAt} 的那些，就靠这个端点补上；传 {@code clear=true}
   * 表示取消过期时刻。只对 HELD 开放：已承诺的预占永不自动过期，留着过期时刻只会误导。
   */
  @PostMapping("/{reservationId}/expiry")
  public ReservationReleaseExpiryOutcome expiry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID reservationId,
      @RequestBody(required = false) ReservationReleaseExpiryInput input) {
    return call(auth, "inventory.update", a -> releases.setExpiry(a.tenantId(), reservationId, input));
  }

  /**
   * query string 里的时刻解析。
   *
   * <p>失败时给 400 并**回显原值**：时间参数写错时「Invalid DateTime」这句话对用户毫无帮助。
   */
  private static java.time.OffsetDateTime parseInstant(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return java.time.OffsetDateTime.parse(raw.trim());
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("evaluatedAt 不是合法的带时区时刻（形如 2026-10-14T10:00:00+08:00）：" + raw);
    }
  }
}