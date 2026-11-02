package com.starcon.platform;

import static com.starcon.platform.AssistantModels.*;

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
import org.springframework.web.bind.annotation.RestController;

/**
 * 18.4.1.1「对话助手」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/assistant}</b>：与 18.4 的兄弟模块
 * （{@code /platform/text} 翻译、{@code /platform/ai/forecast} 预测、{@code /platform/ai/diagnosis} 诊断）
 * 同服务、不同前缀 —— 那些说的是「把文案翻过来」「预测需求」「诊断异常」，
 * 与「用中文问业务问题」不是一回事。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码 {@code platform.assistant.read / .create}</b>：读（能力声明 / 意图目录 / 会话历史）
 * 与提问（写一轮问答台账）是**两个独立的码** —— 能看别人的会话不等于能替别人提问。
 */
@RestController
@RequestMapping("/platform/assistant")
public class AssistantController {

  private final AccessService access;
  private final AssistantService assistant;

  public AssistantController(AccessService access, AssistantService assistant) {
    this.access = access;
    this.assistant = assistant;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.assistant." + permission);
    return access.run(actor, "platform.assistant." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与意图目录

  /**
   * 能力声明：哪些真接通、哪些还没交付、归谁。
   *
   * <p>🔴 **自由问句那一格是 {@code available=false}**：本仓没有任何真实 LLM 适配器，
   * 如实写「未接入 + 缺什么 + 归谁」。
   */
  @GetMapping("/capabilities")
  public AssistantCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> assistant.capabilities());
  }

  /**
   * 受控意图目录（**真读库**）：每个意图的问题形态、数据源、口径与建议的下一步。
   *
   * <p>这是「这个助手能问什么」的**唯一真相**：目录外的问法不会被直译成 SQL，
   * 而是走自由问句路径（如实答「AI 通道未接通」）。
   */
  @GetMapping("/intents")
  public List<AssistantIntentView> intents(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> assistant.intents(actor.tenantId()));
  }

  // ================================================================ 提问

  /**
   * 提一个问题。
   *
   * <p>🔴 **请求体不接受 tenantId / userId / membershipId**：Jackson 的
   * {@code fail-on-unknown-properties} 会让这些字段直接 400 —— 租户来自已认证会话，
   * 不是客户端自称的。
   *
   * <p>🔴 **答案必须带明细链接**：命中的意图走真实只读查询，答案里带
   * {@code evidence}（事实服务 / 真表名 / 真字段名 / 可点的明细路由）。
   * 没有明细链接的那一轮**不出结论**（{@code answered=false}）。
   *
   * <p>🔴 **自由问句如实答「未接通」**：本仓没有真实 AI 供应商适配器 ⇒ 返回
   * {@code answered=false} + 「缺什么 + 归谁」，**不返回任何看似成功的答案**。
   */
  @PostMapping("/ask")
  public AssistantAnswer ask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AssistantAskInput input) {
    return call(auth, "create", actor -> assistant.ask(auth, actor.tenantId(), input));
  }

  // ================================================================ 会话

  /**
   * 会话上下文 + 问答历史（含每轮的明细链接）。
   *
   * <p>🔴 **拿到别人的会话 id 也读不到**：RLS 按当前租户挡住。
   */
  @GetMapping("/sessions/{sessionId}")
  public AssistantSessionView session(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID sessionId) {
    return call(auth, "read", actor -> assistant.session(actor.tenantId(), sessionId));
  }
}
