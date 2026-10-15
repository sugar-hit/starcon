package com.starcon.trade;

import static com.starcon.trade.DealerChannelModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
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
 * 9.7.1.2 经销与分销管理。
 *
 * <p><b>命名空间是 {@code /trade/dealers}。</b>刻意不是 {@code /trade/b2b-orders} 的子路径：
 * 9.7.1.1 是<b>批发订单与账期</b>（订单 → 发货 → 账期应收），本模块是
 * <b>经销商授权 → 配额考核 → 返点计提 → 窜货预警</b>。同一 L3 下的两个兄弟模块，
 * 两边的页面入口、读写动作与权限语义都不同，合并会让「哪个 tab 能改授权」与「这张单该收没收」都变得不明显。
 *
 * <p>权限码用 {@code trade.dealer.read} / {@code trade.dealer.create} / {@code trade.dealer.update}：
 * 改授权条款与确认窜货等于让财务按新的负债口径计提，不是读权限能带过去的动作。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/dealers")
public class DealerChannelController {

  private final AccessService access;
  private final DealerChannelService dealers;

  public DealerChannelController(AccessService access, DealerChannelService dealers) {
    this.access = access;
    this.dealers = dealers;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade.dealer." + permission);
    return access.run(actor, "trade.dealer." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>四条 {@code available=false} 是本端点存在的核心理由：16 合规未交付（授权的合规状态只能是
   * {@code UNVERIFIED}）、12 应付/返点未交付（计提口径未经财务确认）、5.5.2 未交付（窜货判定没有价格倒挂维度）、
   * 13 分析未交付（汇总只在本模块内出）。
   */
  @GetMapping("/capabilities")
  public DealerCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> dealers.capabilities());
  }

  // ================================================================ 上游：经销商候选（跨服务只读）

  /**
   * 建授权时的经销商候选（直接来自 3.6.1.2，不落库）。
   *
   * <p>上游不可达时返回 <b>503</b>，绝不返回空清单冒充「这个租户一个经销商都没有」。
   */
  @GetMapping("/candidates")
  public List<DealerPartyQueryPort.PartyView> candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword) {
    var actor = access.require(auth, "trade.dealer.read");
    return access.run(actor, "trade.dealer.read", () -> dealers.candidates(keyword, auth));
  }

  // ================================================================ 上游投影：经销商档案

  /** 经销商档案投影 upsert（3.6.1.2 → trade 侧只读投影，幂等 + 版本单调）。 */
  @PutMapping("/profiles")
  public DealerProfileRow upsertProfile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DealerProfileInput input) {
    return call(auth, "update", actor -> dealers.upsertProfile(input));
  }

  /** 经销商档案列表：真分页 + 关键词筛选（覆盖编码与名称）。 */
  @GetMapping("/profiles")
  public DealerProfilePage listProfiles(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> dealers.pageProfiles(keyword, page, size));
  }

  // ================================================================ 输出①：经销授权台账

  /** 建立经销授权（区域 + 产品线 + 期限 + 经销价 + 返点比例）。 */
  @PostMapping("/authorizations")
  public DealerSaved createAuthorization(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DealerAuthorizationInput input) {
    return call(auth, "create", actor -> dealers.createAuthorization(input));
  }

  /** 授权台账列表：真分页 + 状态 / 经销商 / 关键词筛选。 */
  @GetMapping("/authorizations")
  public DealerAuthorizationPage listAuthorizations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID dealerProfileId,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor -> dealers.pageAuthorizations(status, dealerProfileId, keyword, page, size));
  }

  /** 授权详情：台账头 + 配额 + 计提 + 预警 + 事件流。 */
  @GetMapping("/authorizations/{id}")
  public DealerAuthorizationDetail authorizationDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> dealers.authorizationDetail(id));
  }

  /** 授权状态流转（暂停 / 恢复 / 到期；终态 EXPIRED 不可回退）。 */
  @PutMapping("/authorizations/{id}/status")
  public DealerSaved changeAuthorizationStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DealerAuthorizationStatusInput input) {
    return call(auth, "update", actor -> dealers.changeAuthorizationStatus(id, input));
  }

  // ================================================================ 输出②：销量配额与完成率

  /** 建 / 改一份期间配额（同一授权 + 同一期间唯一）。 */
  @PostMapping("/quotas")
  public DealerSaved upsertQuota(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DealerQuotaInput input) {
    return call(auth, "update", actor -> dealers.upsertQuota(input));
  }

  /**
   * 配额完成率。
   *
   * <p>三个完成率都是 NUMERIC 除法的结果；目标为 0（不限配额）时为 {@code null}，不是 0 也不是 100%。
   */
  @GetMapping("/quota-attainment")
  public DealerQuotaAttainmentPage quotaAttainment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate periodStart,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate periodEnd,
      @RequestParam(required = false) UUID dealerProfileId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor -> dealers.pageQuotaAttainment(periodStart, periodEnd, dealerProfileId, page, size));
  }

  // ================================================================ 销售记录（配额与窜货的唯一来源）

  /**
   * 登记一条经销商销售与回款记录。
   *
   * <p>金额由服务层按授权经销价算出（入参没有 amount），窜货判定在同一个事务里完成。
   */
  @PostMapping("/sales")
  public DealerSaleRow recordSale(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DealerSaleInput input) {
    return call(auth, "create", actor -> dealers.recordSale(input));
  }

  /** 销售记录列表：真分页 + 授权 / 区域 / 期间筛选。 */
  @GetMapping("/sales")
  public DealerSalePage listSales(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID authorizationId,
      @RequestParam(required = false) String regionCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> dealers.pageSales(authorizationId, regionCode, from, to, page, size));
  }

  // ================================================================ 输出③：返点计提

  /**
   * 返点计提。
   *
   * <p>⚠️ 这是<b>本模块自己的计提口径</b>，蓝图原文写死「需与财务确认计提口径」，
   * 而 12 应付/返点未交付 —— 返回的 {@code financeConfirmed} 恒为 {@code false}。
   */
  @PostMapping("/rebates/accrue")
  public DealerRebateRow accrueRebate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DealerRebateAccrueInput input) {
    return call(auth, "create", actor -> dealers.accrueRebate(input));
  }

  /** 返点计提列表：真分页 + 经销商 / 计提状态筛选。 */
  @GetMapping("/rebates")
  public DealerRebatePage listRebates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID dealerProfileId,
      @RequestParam(required = false) String accrualState,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> dealers.pageRebates(dealerProfileId, accrualState, page, size));
  }

  // ================================================================ 输出④：窜货预警

  /** 窜货预警列表：真分页 + 状态 / 越界维度 / 经销商筛选。 */
  @GetMapping("/diversion-alerts")
  public DealerAlertPage listAlerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String alertState,
      @RequestParam(required = false) String breachKind,
      @RequestParam(required = false) UUID dealerProfileId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> dealers.pageAlerts(alertState, breachKind, dealerProfileId, page, size));
  }

  /** 处置一条窜货预警（判为误报必须写明理由）。 */
  @PutMapping("/diversion-alerts/{id}/state")
  public DealerDiversionAlertRow changeAlertState(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DealerAlertStateInput input) {
    return call(auth, "update", actor -> dealers.changeAlertState(id, input));
  }
}
