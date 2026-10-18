package com.starcon.trade;

import static com.starcon.trade.AftersalesIntakeModels.*;

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
 * 11.1.1.3 售后受理。
 *
 * <p>命名空间是 {@code /trade/aftersales/intake}，与 11.1.1.1 的 {@code /trade/aftersales/cases}、
 * 11.1.1.2 的 {@code /trade/aftersales/cases/{id}}、11.1.2.1 的 {@code /trade/aftersales/reasons}
 * **形状互斥**（都带 {@code intake/} 这一段），所以四条白名单正则在 api-proxy 里并排也不会互相吃。
 *
 * <p><b>权限码</b>：读 {@code trade.aftersales.read}，写 {@code trade.aftersales.write}。
 * 与 11.1.1.1 / 11.1.1.2 <b>同一对</b>：受理判定、必退判定与授权同属售后受理这条业务链，
 * 为它单开一套权限码只会让「谁能改必退结论」这个问题答不上来。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置 —— 受理判定里冻结着必退结论，
 * 改不动租户就等于可以替别的租户做必退判定。
 *
 * <p>⚠️ 受理端点需要<b>调用方的 Authorization</b> 透传给身份服务读售后政策参数
 * （跨服务读不透传租户号，只透传调用方自己的 bearer，见 {@link AftersalesPolicyQueryPort}）。
 */
@RestController
@RequestMapping("/trade/aftersales/intake")
public class AftersalesIntakeController {

  private static final String READ = "aftersales.read";
  private static final String WRITE = "aftersales.write";

  private final AccessService access;
  private final AftersalesIntakeService intake;

  public AftersalesIntakeController(AccessService access, AftersalesIntakeService intake) {
    this.access = access;
    this.intake = intake;
  }

  /** 统一读入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + READ);
    return access.run(actor, "trade." + READ, () -> work.apply(actor));
  }

  /** 写入口：需要 {@code trade.aftersales.write}。 */
  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + WRITE);
    return access.run(actor, "trade." + WRITE, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 受理能力声明：四项判定各读什么、<b>当前生效</b>的期限天数与必退词表、回复时限策略配没配。
   *
   * <p>⚠️ 这个端点会真的去读一次身份服务，因此<b>可能返回 503</b>。
   * 这是刻意的：说「必退词表是空的」而实际读不到，与「真的没配」会导致完全不同的受理决策，
   * 两者不可混为一谈。
   */
  @GetMapping("/capabilities")
  public AftersalesIntakeCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> intake.capabilities(auth));
  }

  // ================================================================ 受理

  /**
   * 受理一张售后请求。
   *
   * <p>一个事务里做完：建单（委托 11.1.1.1 的写入所有者）→ 落判定凭据 → 派责任人。
   * 响应含三样 —— 判定凭据、售后单（待处理）、责任人分配，正是蓝图输出句的三项。
   *
   * <p>⚠️ 平台重投同一条申请（{@code intakeSource=PLATFORM_MESSAGE} 且带 {@code intakeReference}）
   * 会被数据库层的部分唯一索引挡下并报冲突，而不是建出第二张重复单。
   */
  @PostMapping("/cases")
  public AftersalesIntakeResult intake(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AftersalesIntakeRequest request) {
    return write(auth, actor -> intake.intake(request, actor.userId(), auth));
  }

  /** 重新判定一张售后单：按当前生效政策再判一次，旧判定留档（改参数不改历史判定的操作面）。 */
  @PostMapping("/cases/{caseId}/rejudge")
  public AftersalesIntakeVerdictView rejudge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID caseId) {
    return write(auth, actor -> intake.rejudge(caseId, actor.userId(), auth));
  }

  /** 受理凭据列表：真分页 + 多维筛选 + 与分页无关的汇总（含「必退且回复已超时」计数）。 */
  @GetMapping("/verdicts")
  public AftersalesIntakePage verdicts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String eligibility,
      @RequestParam(required = false) Boolean mustRefund,
      @RequestParam(required = false) String intakeSource,
      @RequestParam(required = false) String ownerAssignmentMode,
      @RequestParam(required = false) UUID caseId,
      @RequestParam(required = false) String caseNo,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "true") boolean currentOnly,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        actor ->
            intake.list(
                new AftersalesIntakeQuery(
                    eligibility,
                    mustRefund,
                    intakeSource,
                    ownerAssignmentMode,
                    caseId,
                    caseNo,
                    keyword,
                    currentOnly,
                    sort,
                    page,
                    size)));
  }

  // ================================================================ 回复时限策略

  /**
   * 当前生效的回复时限档（按平台档解析）。
   *
   * <p>{@code null} 表示**没配** —— 这是合法状态且前端必须能显示出来：
   * 「没配 SLA」与「配了 24 小时」是两件事，后者界面才能说「我们承诺 24 小时回」。
   */
  @GetMapping("/reply-sla/effective")
  public AftersalesReplySlaView effectiveSla(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformSource) {
    return call(auth, actor -> intake.effectiveSla(platformSource));
  }

  /** 全部回复时限档（含已收口的 —— 历史就是已收区间的那些行，与 warehouse V56 同一套约定）。 */
  @GetMapping("/reply-sla")
  public List<AftersalesReplySlaView> listSla(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> intake.listSla());
  }

  /** 新开一档回复时限。**不写兜底默认值**：没配置就是没配置。 */
  @PostMapping("/reply-sla")
  public AftersalesReplySlaView createSla(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AftersalesReplySlaCreate request) {
    return write(auth, actor -> intake.createSla(request, actor.userId()));
  }

  /** 收口一档（换版或作废）。档内容不可改 —— 想改必须收口旧档再开新档。 */
  @PostMapping("/reply-sla/{id}/retire")
  public AftersalesReplySlaView retireSla(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) AftersalesReplySlaRetire request) {
    return write(auth, actor -> intake.retireSla(id, request, actor.userId()));
  }

  // ================================================================ 责任人名册

  @GetMapping("/owner-roster")
  public List<AftersalesOwnerRosterView> listRoster(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> intake.listRoster());
  }

  /**
   * 登记 / 更新一个责任人名册行。
   *
   * <p>同一 {@code ownerRef} 重复登记会**更新**既有行而不是报冲突：
   * 名册是「这个人能接单」的事实，同一个人登记两次不该让同步任务一直失败。
   */
  @PostMapping("/owner-roster")
  public AftersalesOwnerRosterView upsertRoster(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AftersalesOwnerRosterCreate request) {
    return write(auth, actor -> intake.upsertRoster(request, actor.userId()));
  }

  /** 调整名册行（是否接新单 / 权重 / 显示名）。标识本身不可改。 */
  @PutMapping("/owner-roster/{id}")
  public AftersalesOwnerRosterView updateRoster(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody AftersalesOwnerRosterUpdate request) {
    return write(auth, actor -> intake.updateRoster(id, request));
  }
}
