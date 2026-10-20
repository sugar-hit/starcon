package com.starcon.channel;

import static com.starcon.channel.LogisticsExceptionModels.*;

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
 * 10.3.1.2 异常件。
 *
 * <p>端点按蓝图模块号标注，方便对照 10-logistics-and-freight.md。
 *
 * <p>🔴 命名空间与 10.3.1.1 轨迹查询<b>并列</b>（同一个 {@code /channel/logistics-tracking}
 * 前缀下的两个子空间），因为它们是同一个 L3 里的两个模块：
 *
 * <ul>
 *   <li>10.3.1.1（{@link TrackingQueryController}）＝ {@code /subjects} {@code /events}
 *       {@code /pull-tasks} {@code /capabilities} {@code /overview}；</li>
 *   <li>10.3.1.2（本类）＝ {@code /exceptions} 与 {@code /dispositions} 两个子空间。</li>
 * </ul>
 *
 * <p>⚠️ 因此本类**刻意不复用** {@code /capabilities} 与 {@code /overview} 两个路径 ——
 * 10.3.1.1 已经占用了它们，同一个 {@code @RequestMapping} 下再声明一次会让 Spring
 * 启动直接抛「ambiguous mapping」。本模块的对应端点落在
 * {@code /exceptions/capabilities} 与 {@code /exceptions/overview}。
 *
 * <p>🔴 本控制器**刻意没有「把异常件直接改好」的入口**：异常件的终态只能经
 * {@code POST /exceptions/{id}/dispositions} 追加一条处置记录（结案 / 作废）达成，
 * 而处置记录是 append-only 的 —— 事后改写处置史等于伪造「我们处理过」。
 *
 * <p>🔴 本控制器**不提供任何跨服务调用**：11 售后 / 15 客服 / 7 库存当前未交付，
 * 改址（10.2.1.3）在另一个服务里。处置结果落在本服务自己的处置记录与理赔/补发单上，
 * 需要下游接手的事以 {@code downstream_task} 显式标注「待通知」。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}
 * （与 10.1.1.1 承运商、10.3.1.1 轨迹查询、10.3.2.1 交运批次同族），
 * 不需要改动 identity 服务。
 */
@RestController
@RequestMapping("/channel/logistics-tracking")
public class LogisticsExceptionController {

  private final AccessService access;
  private final LogisticsExceptionService service;

  public LogisticsExceptionController(AccessService access, LogisticsExceptionService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明与概览 ----

  @GetMapping("/exceptions/capabilities")
  public LogisticsExceptionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 概览：<b>全量口径</b>，不受列表筛选与分页影响。 */
  @GetMapping("/exceptions/overview")
  public LogisticsExceptionOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.overview());
  }

  // ---- 异常件 ----

  /** 异常件清单：真分页 + 真筛选。 */
  @GetMapping("/exceptions")
  public ExceptionCasePage cases(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String exceptionType,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String ownerMembershipId,
      @RequestParam(required = false, defaultValue = "false") boolean overdueOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.cases(
                exceptionType,
                severity,
                status,
                carrierCode,
                keyword,
                ownerMembershipId,
                overdueOnly,
                page,
                size));
  }

  /**
   * 登记一个异常件。
   *
   * <p>🔴 请求体里<b>没有</b> source：来源是服务端派生的事实
   * （有异常节点 ⇒ 被动异常标记；有客服反馈 ⇒ 客服反馈；其余 ⇒ 人工登记）。
   */
  @PostMapping("/exceptions")
  public ExceptionCaseRow createCase(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CreateExceptionCaseRequest request) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.createCase(request, a.membershipId()));
  }

  /** 异常件详情：主行 + 处置记录 + 单据 + 下游待通知任务 + 上游轨迹上下文。 */
  @GetMapping("/exceptions/{id}")
  public ExceptionCaseDetail caseDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.caseDetail(parse(id)));
  }

  // ---- 异常识别规则 ----

  /** 规则清单：真分页 + 真筛选。 */
  @GetMapping("/exceptions/rules")
  public ExceptionRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String ruleType,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.rules(ruleType, page, size));
  }

  /** 登记一条异常识别规则（租户级）。 */
  @PostMapping("/exceptions/rules")
  public ExceptionRuleRow saveRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveExceptionRuleRequest request) {
    return call(
        auth, "channel.operation.create", a -> service.saveRule(request, a.membershipId()));
  }

  /**
   * 按规则扫描在途轨迹，把命中的票变成异常件。
   *
   * <p>🔴 幂等：同一票 + 同一类型已有未结异常件 ⇒ 跳过（DB 部分唯一索引兜底）。
   */
  @PostMapping("/exceptions/scan")
  public ExceptionScanResult scan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String ruleId) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.scan(ruleId == null ? null : parse(ruleId), a.membershipId()));
  }

  // ---- 处置记录 ----

  /** 某一件的处置记录。 */
  @GetMapping("/exceptions/{id}/dispositions")
  public ExceptionDispositionPage caseDispositions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.dispositions(null, null, parse(id).toString(), page, size));
  }

  /**
   * 追加一条处置记录。
   *
   * <p>🔴 结案 / 作废只能走这里（终态不可回退）；下游待通知任务随动作产生，
   * 状态恒为 {@code PENDING_NOTIFY}。
   */
  @PostMapping("/exceptions/{id}/dispositions")
  public ExceptionDispositionRow addDisposition(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody AddDispositionRequest request) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.addDisposition(parse(id), request, a.membershipId()));
  }

  /** 全量处置记录：真分页 + 真筛选。 */
  @GetMapping("/dispositions")
  public ExceptionDispositionPage dispositions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String actionType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String caseId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.dispositions(actionType, status, caseId, page, size));
  }

  /** 处置记录详情（含它触发的下游待通知任务）。 */
  @GetMapping("/dispositions/{id}")
  public ExceptionDispositionDetail dispositionDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.dispositionDetail(parse(id)));
  }

  // ---- 理赔 / 补发单 ----

  /** 某一件的单据。 */
  @GetMapping("/exceptions/{id}/claims")
  public ExceptionClaimPage caseClaims(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false) String claimType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.claims(parse(id), claimType, status, page, size));
  }

  /**
   * 发起一张理赔 / 补发单。
   *
   * <p>🔴 补发单会产出两条下游待通知任务（7 库存 / 11 售后），二者当前都未交付。
   */
  @PostMapping("/exceptions/{id}/claims")
  public ExceptionClaimRow createClaim(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody CreateExceptionClaimRequest request) {
    return call(
        auth,
        "channel.operation.create",
        a -> service.createClaim(parse(id), request, a.membershipId()));
  }

  /** 全量单据：真分页 + 真筛选。 */
  @GetMapping("/dispositions/claims")
  public ExceptionClaimPage claims(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String caseId,
      @RequestParam(required = false) String claimType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.claims(
                caseId == null ? null : parse(caseId), claimType, status, page, size));
  }

  /** 单据详情（含它触发的下游待通知任务）。 */
  @GetMapping("/dispositions/claims/{id}")
  public ExceptionClaimDetail claimDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.claimDetail(parse(id)));
  }

  /**
   * 路径段解析。
   *
   * <p>🔴 解析失败与「不存在」返回**逐字相同的 404** —— 跨租户的主键与根本不存在的
   * 主键不该被区分开（那等于给越权探测一个信号）。
   */
  private static UUID parse(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (RuntimeException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "异常件不存在");
    }
  }
}
