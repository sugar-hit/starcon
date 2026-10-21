package com.starcon.trade;

import static com.starcon.trade.PurchaseFollowupModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 6.3.2.1 跟单与交期（卷 06 采购与供应 / L3 6.3.2 采购跟踪的第一格）。
 *
 * <p>蓝图原话：「记录与供应商的沟通跟进（催单、确认、异常反馈），维护承诺到货日变更历史，
 * 统计平均延迟天数。」输出「跟单时间线；交期变更历史；延迟统计。」
 *
 * <p><b>命名空间是 {@code /trade/purchase-followups}</b>：与既有 trade 前缀
 * （{@code /trade/purchase-orders} 6.3.1.1、{@code /trade/purchase-arrivals} 6.3.2.2、
 * {@code /trade/purchase-requests} 6.2.2.1、{@code /trade/purchase-plans} 6.2.1.3）首 token 全不相同。
 *
 * <p><b>租户绝不来自任何请求头</b>：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>数据范围由服务端从角色授权推导</b>（ALL / DEPARTMENT / SELF），
 * <b>不接受请求参数传入</b>。
 *
 * <p>🔴 <b>交期变更是「两个事务」</b>：变更落库（事实）与回写下游（派生动作）分开 ——
 * 回写失败（上游 warehouse 不可达、租户没配覆盖天数……）**不能**把已发生的变更一起回滚掉。
 * 每个回写目标各自一个事务，失败就在**另一个**事务里如实补一条 FAILED 留痕。
 */
@RestController
@RequestMapping("/trade/purchase-followups")
public class PurchaseFollowupController {

  private final AccessService access;
  private final PurchaseFollowupService followups;

  public PurchaseFollowupController(AccessService access, PurchaseFollowupService followups) {
    this.access = access;
    this.followups = followups;
  }

  /** 读路径：先确认身份与权限，再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, PurchaseFollowupService.READ_PERMISSION);
    return access.run(actor, PurchaseFollowupService.READ_PERMISSION, () -> work.apply(actor));
  }

  /** 写路径：写权限码要求同一个 grant 里同时有 read。 */
  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, PurchaseFollowupService.UPDATE_PERMISSION);
    return access.run(actor, PurchaseFollowupService.UPDATE_PERMISSION, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  @GetMapping("/capabilities")
  public PurchaseFollowupCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> followups.capabilities());
  }

  // ================================================================ 跟单记录

  /** 跟单记录列表：真分页 + 多维筛选。 */
  @GetMapping
  public PurchaseFollowupRecordPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String purchaseOrderNo,
      @RequestParam(required = false) UUID supplierRefId,
      @RequestParam(required = false) String followupType,
      @RequestParam(required = false) String channel,
      @RequestParam(required = false) OffsetDateTime occurredFrom,
      @RequestParam(required = false) OffsetDateTime occurredTo,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        actor ->
            followups.listFollowups(
                new PurchaseFollowupRecordQuery(
                    purchaseOrderNo,
                    supplierRefId,
                    parseType(followupType),
                    parseChannel(channel),
                    occurredFrom,
                    occurredTo,
                    keyword,
                    page,
                    size,
                    actor.userId(),
                    actor.departmentId(),
                    scopeMode(actor, PurchaseFollowupService.READ_PERMISSION))));
  }

  /** 登记一条跟单记录（沟通凭据）。 */
  @PostMapping
  public PurchaseFollowupRecordRow record(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PurchaseFollowupRecordInput input) {
    return write(
        auth,
        actor ->
            followups.recordFollowup(
                input,
                actor.userId(),
                scopeMode(actor, PurchaseFollowupService.UPDATE_PERMISSION),
                actor.departmentId()));
  }

  // ================================================================ 交期变更

  /** 交期变更历史：真分页。 */
  @GetMapping("/changes")
  public PurchaseFollowupChangePage changes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String purchaseOrderNo,
      @RequestParam(required = false) UUID supplierRefId,
      @RequestParam(required = false) LocalDate changedFrom,
      @RequestParam(required = false) LocalDate changedTo,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        actor ->
            followups.listChanges(
                new PurchaseFollowupChangeQuery(
                    purchaseOrderNo,
                    supplierRefId,
                    changedFrom,
                    changedTo,
                    keyword,
                    page,
                    size,
                    actor.userId(),
                    actor.departmentId(),
                    scopeMode(actor, PurchaseFollowupService.READ_PERMISSION))));
  }

  /**
   * 登记一次交期变更，并**回写** 6.2.1.1 / 6.2.1.2。
   *
   * <p>🔴 <b>三个阶段，三个独立事务</b>：
   *
   * <ol>
   *   <li><b>变更落库</b>（自己的事务）—— 交期变更是硬事实，先落库。</li>
   *   <li><b>回写 6.2.1.1</b>（另一个事务）—— 调用 6.2.1.1 <b>自己的</b>服务方法去触发重算，
   *       写入所有权仍归它，本模块只是<b>触发</b>。</li>
   *   <li><b>回写 6.2.1.2</b>（再一个事务）—— 同上，调用 6.2.1.2 自己的服务方法。</li>
   * </ol>
   *
   * <p>回写失败时**不回滚变更**（事实已经发生），而是在**再一个**事务里如实补一条 FAILED 留痕 ——
   * 「回写了没有」必须是可查的事实，不是一句返回值。
   */
  @PostMapping("/changes")
  public PurchaseFollowupChangeRow recordChange(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PurchaseFollowupChangeInput input) {
    AccessService.Access actor = access.require(auth, PurchaseFollowupService.UPDATE_PERMISSION);
    // ---- 阶段一：交期变更落库 ----
    PurchaseFollowupChangeRow created =
        access.run(
            actor,
            PurchaseFollowupService.UPDATE_PERMISSION,
            () ->
                followups.recordChange(
                    input,
                    actor.userId(),
                    scopeMode(actor, PurchaseFollowupService.UPDATE_PERMISSION),
                    actor.departmentId()));

    // ---- 阶段二 / 三：回写两个下游（各自独立事务，失败不回滚变更）----
    List<PurchaseFollowupWritebackRow> writebacks = new ArrayList<>();
    for (String target : List.of("6.2.1.1", "6.2.1.2")) {
      try {
        writebacks.add(
            access.run(
                actor,
                PurchaseFollowupService.UPDATE_PERMISSION,
                () -> followups.writebackTo(created.id(), target, actor.userId(), auth)));
      } catch (RuntimeException e) {
        final RuntimeException failure = e;
        writebacks.add(
            access.run(
                actor,
                PurchaseFollowupService.UPDATE_PERMISSION,
                () -> followups.recordWritebackFailure(created.id(), target, failure)));
      }
    }
    return withWriteback(created, writebacks);
  }

  // ================================================================ 时间线

  /** 跟单时间线：跟单记录 + 交期变更合并（真分页）。 */
  @GetMapping("/timeline")
  public PurchaseFollowupTimelinePage timeline(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String purchaseOrderNo,
      @RequestParam(required = false) UUID supplierRefId,
      @RequestParam(required = false) OffsetDateTime occurredFrom,
      @RequestParam(required = false) OffsetDateTime occurredTo,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        actor ->
            followups.timeline(
                new PurchaseFollowupTimelineQuery(
                    purchaseOrderNo,
                    supplierRefId,
                    occurredFrom,
                    occurredTo,
                    keyword,
                    page,
                    size,
                    actor.userId(),
                    actor.departmentId(),
                    scopeMode(actor, PurchaseFollowupService.READ_PERMISSION))));
  }

  // ================================================================ 延迟统计

  /** 延迟统计汇总（平均延迟天数 / 准时率 / 已逾期）。 */
  @GetMapping("/stats")
  public PurchaseFollowupDeliveryStats stats(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID supplierRefId,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "false") boolean onlyOverdue,
      @RequestParam(defaultValue = "false") boolean onlyDelayed) {
    return call(
        auth,
        actor -> followups.stats(supplierRefId, keyword, onlyOverdue, onlyDelayed));
  }

  /** 逐单交付实况（真分页）。 */
  @GetMapping("/delivery")
  public PurchaseFollowupDeliveryPage delivery(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID supplierRefId,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "false") boolean onlyOverdue,
      @RequestParam(defaultValue = "false") boolean onlyDelayed,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        actor -> followups.deliveryDetail(supplierRefId, keyword, onlyOverdue, onlyDelayed, page, size));
  }

  // ================================================================ 内部

  private static PurchaseFollowupChangeRow withWriteback(
      PurchaseFollowupChangeRow row, List<PurchaseFollowupWritebackRow> writebacks) {
    return new PurchaseFollowupChangeRow(
        row.id(),
        row.changeNo(),
        row.purchaseOrderNo(),
        row.purchaseOrderRefId(),
        row.supplierRefId(),
        row.supplierCode(),
        row.supplierName(),
        row.fromPromisedDate(),
        row.toPromisedDate(),
        row.reason(),
        row.changedBy(),
        row.changedAt(),
        row.followupPromiseDate(),
        row.documentPromiseDate(),
        row.promiseDiverged(),
        writebacks);
  }

  /**
   * 数据范围（ALL / DEPARTMENT / SELF）。
   *
   * <p>⚠️ 「操作和范围必须由同一分配满足」（ADR-0005）：只看<b>含有本权限</b>的授权。
   * 写操作按<b>写权限</b>判定范围，读操作按<b>读权限</b>判定范围。
   */
  static String scopeMode(AccessService.Access a, String permission) {
    List<AccessService.Grant> grants =
        a.grants() == null ? List.<AccessService.Grant>of() : a.grants();
    boolean all =
        grants.stream()
            .anyMatch(g -> "ALL".equals(g.scopeMode()) && g.permissions().contains(permission));
    if (all) {
      return "ALL";
    }
    boolean department =
        grants.stream()
            .anyMatch(
                g -> "DEPARTMENT".equals(g.scopeMode()) && g.permissions().contains(permission));
    if (department && a.departmentId() != null) {
      return "DEPARTMENT";
    }
    return "SELF";
  }

  private static PurchaseFollowupType parseType(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return PurchaseFollowupType.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "跟单类型只能是 URGING / CONFIRM / EXCEPTION / OTHER");
    }
  }

  private static PurchaseFollowupChannel parseChannel(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return PurchaseFollowupChannel.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "沟通渠道只能是 PHONE / EMAIL / IM / WECHAT / PLATFORM / OTHER");
    }
  }
}
