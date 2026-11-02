package com.starcon.platform;

import static com.starcon.platform.AiDiagnosisModels.*;

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
 * 18.4.2.3「异常诊断」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/ai/diagnosis}</b>：同卷的兄弟模块各有各的前缀
 * （{@code /platform/text} 18.4.2.1、{@code /platform/ai/forecast} 18.4.2.2、
 * {@code /platform/ai/usage} 18.4.3.1、{@code /platform/ai/templates} 18.5.1.1、
 * {@code /platform/automation/runs} 18.3.2.1）—— 前缀互不为前缀，谁也不会吃掉谁。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置；请求体里也**没有** tenantId 字段。
 *
 * <p><b>权限码 {@code platform.ai_diagnosis.read / .create / .update}</b>：
 * 「标记已处置」是<b>独立</b>的一个码 —— 能提交异常、能跑诊断的人，不该顺手把别人的
 * 异常标成「已处置」。
 */
@RestController
@RequestMapping("/platform/ai/diagnosis")
public class AiDiagnosisController {

  private final AccessService access;
  private final AiDiagnosisService diagnosis;

  public AiDiagnosisController(AccessService access, AiDiagnosisService diagnosis) {
    this.access = access;
    this.diagnosis = diagnosis;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.ai_diagnosis." + permission);
    return access.run(actor, "platform.ai_diagnosis." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与概览

  /**
   * 能力声明：归因规则、阈值、事实源与**边界**逐条报出。
   *
   * <p>🔴 响应里明确写着「AI 推理引擎未接通，本模块做确定性归因」以及
   * 「工作台待办表与各域异常事件表不存在」—— 页面照抄后端原文，不自己编。
   */
  @GetMapping("/capabilities")
  public AiDiagnosisCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> diagnosis.capabilities());
  }

  /** 概览：几个数。 */
  @GetMapping("/overview")
  public AiDiagnosisOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> diagnosis.overview());
  }

  // ================================================================ 异常事件

  /** 异常事件列表（真分页 + 真筛选 + 真搜索）。 */
  @GetMapping("/events")
  public AiDiagnosisEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String anomalyType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth, "read", actor -> diagnosis.events(anomalyType, status, keyword, page, size));
  }

  /**
   * 登记一个异常事件（**调用方提交**这一路来源）。
   *
   * <p>🔴 请求体里**没有** tenantId：租户从已认证成员关系解析。
   */
  @PostMapping("/events")
  public AiDiagnosisEventRow submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AiDiagnosisEventInput input) {
    return call(auth, "create", actor -> diagnosis.submit(input, actor.userId()));
  }

  /** 事件详情：事件 + 历史运行 + 最近一次运行的结论与依据。 */
  @GetMapping("/events/{id}")
  public AiDiagnosisEventDetail event(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> diagnosis.eventDetail(id));
  }

  /**
   * 对一个异常事件发起一次诊断（**确定性归因**）。
   *
   * <p>🔴 上游读不到时端口会翻成 <b>503</b>，本方法**不吞**——
   * 一个「看起来诊断成功但其实没读数据」的返回会让人以为已经查过了。
   */
  @PostMapping("/events/{id}/diagnose")
  public AiDiagnosisRunDetail diagnose(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "create", actor -> diagnosis.diagnose(id, actor.userId(), auth));
  }

  /**
   * 人工标记异常事件已处置。
   *
   * <p>🔴 **只是记下「人决定了处置」**：本模块**不执行**任何下游动作
   * （补货归 6 采购、调价归 5 定价、停广告归 14 广告）。
   */
  @PostMapping("/events/{id}/resolve")
  public AiDiagnosisEventRow resolve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) AiDiagnosisResolveInput input) {
    return call(auth, "update", actor -> diagnosis.resolve(id, input));
  }

  /**
   * 从**平台内部失败源**同步异常事件。
   *
   * <p>🔴 幂等：同一个失败源事实只登记一次（源表 + 行 id 做幂等键）。
   * <p>🔴 **只读上游**：V413 / V414 / V422 三张事实表一个字都不改。
   */
  @PostMapping("/events/sync")
  public AiDiagnosisSyncResult sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "create", actor -> diagnosis.syncInternalFailures(actor.userId()));
  }
}
