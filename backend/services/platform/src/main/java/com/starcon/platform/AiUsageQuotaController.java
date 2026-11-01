package com.starcon.platform;

import static com.starcon.platform.AiUsageQuotaModels.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
 * 18.4.3.1「使用与配额」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/ai/usage}</b>：与同服务的兄弟模块（{@code /platform/apps} /
 * {@code /platform/text} / {@code /platform/call-monitor} / …）刻意不共用首段 ——
 * 前端的 api-proxy 白名单也按首 token 天然互斥。
 *
 * <p>租户<b>不</b>来自任何请求头：每个端点先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code platform.ai-usage.read / .record / .update}：
 * 「记用量」是<b>独立</b>的一个码 —— 它会推进配额计数，让一个只能看报表的人顺手记一笔
 * 用量等于让自己给自己抬额度，门槛要真的拦得住人。
 *
 * <p>🔴 <b>请求体里没有 tenantId / userId / membershipId</b>：归属一律由服务端从
 * <b>已认证会话</b>解析（Jackson 全局 fail-on-unknown-properties ⇒ 客户端自称的字段直接 400）。
 * 需要给**别的**成员配用户级配额时，主体成员走<b>路径</b>（{@code /members/{memberId}/quotas}），
 * 不是请求体。
 */
@RestController
@RequestMapping("/platform/ai/usage")
public class AiUsageQuotaController {

  private final AccessService access;
  private final AiUsageQuotaService usage;

  public AiUsageQuotaController(AccessService access, AiUsageQuotaService usage) {
    this.access = access;
    this.usage = usage;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.ai-usage." + permission);
    return access.run(actor, "platform.ai-usage." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些真接通、职责怎么切、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  @Operation(
      summary = "能力声明",
      description =
          "逐维说清「有没有、依据是什么、缺了归谁」。"
              + "「全局 AI 拦截点」如实答不可用 —— 本仓没有统一的 AI 出站网关，"
              + "敏感数据策略的**执行点**是各个 AI 能力自己在调用前查。")
  public AiUsageCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> usage.capabilities());
  }

  // ================================================================ 模型目录

  /** 模型目录列表（真筛选）。 */
  @GetMapping("/models")
  @Operation(
      summary = "模型目录",
      description =
          "租户可维护的模型清单：模型名、单位成本（**每 1 个量纲单位**的成本）、"
              + "上下文上限、是否可用。平台零预置 —— 单价是商业条款，归租户定。")
  public List<AiModelRow> models(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) String keyword) {
    return call(auth, "read", actor -> usage.models(enabled, keyword));
  }

  /** 登记 / 修改一个模型（同一 model_code 只有一行）。 */
  @PostMapping("/models")
  @Operation(
      summary = "登记 / 修改模型",
      description =
          "单价改价**不影响历史台账** —— 台账里存的是记这一行那一刻的单价快照。")
  public AiModelRow saveModel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AiModelInput input) {
    return call(auth, "update", actor -> usage.saveModel(input));
  }

  // ================================================================ 用量台账

  /** 用量台账（真分页 + 真筛选）。 */
  @GetMapping("/records")
  @Operation(
      summary = "用量台账",
      description =
          "一次 AI 调用一行（**只追加**）：主体成员、能力、模型、量纲、调用量、"
              + "成本快照、发生时刻、请求关联 id。")
  public AiUsageRecordPage records(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String capabilityCode,
      @RequestParam(required = false) UUID membershipId,
      @RequestParam(required = false) String modelCode,
      @RequestParam(required = false)
          @Parameter(description = "起始时刻（ISO-8601，含）") String from,
      @RequestParam(required = false)
          @Parameter(description = "结束时刻（ISO-8601，不含）") String to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        actor ->
            usage.records(capabilityCode, membershipId, modelCode, from, to, page, size));
  }

  /** 记一次 AI 调用用量（**配额的权威执行点**）。 */
  @PostMapping("/records")
  @Operation(
      summary = "记一次用量",
      description =
          "主体成员由服务端从已认证会话解析（请求体不接受 membershipId）。"
              + "成本 = 调用量 × **快照**下来的目录单价；"
              + "同一次调用里的配额计数由数据库**原子**递增，返回的判定即递增后的累计值。")
  public AiUsageRecordResult record(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AiUsageRecordInput input) {
    return call(auth, "record", actor -> usage.record(input, actor));
  }

  // ================================================================ 用量报表

  /** 用量报表（**可计费口径**）。 */
  @GetMapping("/summary")
  @Operation(
      summary = "用量报表",
      description =
          "粒度 = 能力 × 模型 × 币种；不同模型与不同币种**不混算**。"
              + "本模块只提供可计费口径的汇总，**不直连 finance 库、不写 finance 表** —— "
              + "计费（17.6.1）自行取数或由事件消费。")
  public AiUsageSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return call(auth, "read", actor -> usage.summary(from, to));
  }

  // ================================================================ 配额

  /** 配额列表（含当前周期的用量与是否越线）。 */
  @GetMapping("/quotas")
  @Operation(
      summary = "配额列表",
      description =
          "租户级与用户级配额，含**当前周期**的已用量、阈值与重置时刻。")
  public List<AiQuotaRow> quotas(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> usage.quotas());
  }

  /** 登记 / 修改一条**租户级**配额。 */
  @PostMapping("/quotas")
  @Operation(
      summary = "登记 / 修改租户级配额",
      description =
          "主体是**整个租户**。次数上限与成本上限至少配一个；两者都配时**任一越线即超限**。")
  public AiQuotaRow saveTenantQuota(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AiQuotaInput input) {
    return call(auth, "update", actor -> usage.saveTenantQuota(input));
  }

  /** 登记 / 修改一条**用户级**配额（主体成员走**路径**，不是请求体）。 */
  @PostMapping("/members/{memberId}/quotas")
  @Operation(
      summary = "登记 / 修改用户级配额",
      description =
          "主体是路径里指定的那一个成员。请求体里**没有** membershipId —— "
              + "客户端不能自称归属。⚠️ 本仓 identity 侧没有成员归属查询端点，"
              + "配给非本租户成员的配额会永远匹配不到用量（惰性行），不会越权。")
  public AiQuotaRow saveMemberQuota(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID memberId,
      @RequestBody AiQuotaInput input) {
    return call(auth, "update", actor -> usage.saveMemberQuota(memberId, input));
  }

  /** 配额预检（**读快照，不占位额度**）。 */
  @PostMapping("/quotas/check")
  @Operation(
      summary = "配额预检",
      description =
          "回答「按当前用量算，还放得下吗」。超限会给出：超了哪一条配额、当前用量、阈值、"
              + "周期、什么时候重置。⚠️ 这是**预检快照**，并发下的权威判定发生在"
              + "**记用量**那一次的数据库原子递增。")
  public AiQuotaEvaluation check(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AiQuotaCheckInput input) {
    return call(auth, "read", actor -> usage.check(input, actor));
  }

  // ================================================================ 敏感数据策略

  /** 敏感数据策略列表（真筛选）。 */
  @GetMapping("/policies")
  @Operation(
      summary = "敏感数据策略列表",
      description =
          "按能力 × 模型登记的敏感数据处理动作（禁传 / 脱敏 / 允许）。"
              + "🔴 **执行点是各个 AI 能力自己**（调用前查本表）—— 本仓没有全局 AI 拦截点。")
  public List<AiSensitivePolicyRow> policies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String capabilityCode,
      @RequestParam(required = false) String modelCode) {
    return call(auth, "read", actor -> usage.policies(capabilityCode, modelCode));
  }

  /** 查**生效**的那条策略（各 AI 能力在真正调用前查的就是它）。 */
  @GetMapping("/policies/effective")
  @Operation(
      summary = "查生效的敏感数据策略",
      description =
          "先找「能力 × 该模型」，找不到再退回「能力 × 全部模型」；"
              + "都没有登记 ⇒ 落到默认档「允许」，并如实说明「库里没登记」。")
  public AiSensitivePolicyView effectivePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String capabilityCode,
      @RequestParam(required = false) String modelCode) {
    return call(auth, "read", actor -> usage.effectivePolicy(capabilityCode, modelCode));
  }

  /** 登记 / 修改一条敏感数据策略。 */
  @PostMapping("/policies")
  @Operation(
      summary = "登记 / 修改敏感数据策略",
      description =
          "模型标识留空 = 该能力下**全部模型**；对个别模型加严时，"
              + "更具体的那条（能力 × 模型）生效。")
  public AiSensitivePolicyRow savePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AiSensitivePolicyInput input) {
    return call(auth, "update", actor -> usage.savePolicy(input));
  }
}
