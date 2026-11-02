package com.starcon.platform;

import static com.starcon.platform.SmartQueryModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 18.4.1.2「智能查询」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/smart-query}</b>：与兄弟模块
 * （{@code /platform/assistant} 18.4.1.1、{@code /platform/ai/forecast} 18.4.2.2、
 * {@code /platform/ai/diagnosis} 18.4.2.3）同服务、不同前缀 ——
 * 那几个是「把一个已经确定的问题算出来」，本模块是「把一句话变成**可执行的查询条件**」。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码 {@code platform.smart_query.read / .create / .update}</b>：
 * 读（能力声明 / 语义层目录 / 台账 / 常用查询）、执行一次查询（写台账 + 明细依据 +
 * 在 13.6.1.1 建报表草稿）、改常用查询是**三个独立的码**。
 */
@RestController
@RequestMapping("/platform/smart-query")
public class SmartQueryController {

  private final AccessService access;
  private final SmartQueryService smartQuery;

  public SmartQueryController(AccessService access, SmartQueryService smartQuery) {
    this.access = access;
    this.smartQuery = smartQuery;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.smart_query." + permission);
    return access.run(actor, "platform.smart_query." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与语义层

  /**
   * 能力声明：哪些真接通、哪些还没接通、归谁。
   *
   * <p>🔴 每一格都写明**凭什么这么判**（端点 / 表 / 规则的具体名字）。
   */
  @GetMapping("/capabilities")
  public SmartQueryCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> smartQuery.capabilities());
  }

  /**
   * 受控语义层目录（**真读库** + **实时核对上游口径表**）。
   *
   * <p>这是「这个模块能查什么」的**唯一真相**：词表外的指标解析不出来，
   * 词表里但本租户没登记的指标如实标 {@code available=false}。
   */
  @GetMapping("/semantic-layer")
  public SmartQuerySemanticLayer semanticLayer(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> smartQuery.semanticLayer(auth, actor.tenantId()));
  }

  // ================================================================ 执行一次查询

  /**
   * 执行一次智能查询。
   *
   * <p>🔴 **请求体不接受 tenantId / userId / membershipId**：Jackson 的
   * {@code fail-on-unknown-properties} 会让这些字段直接 400 —— 租户来自已认证会话，
   * 不是客户端自称的。
   *
   * <p>🔴 **解析不到受控条目就不出结论**：返回 {@code resolved=false} +
   * 「缺什么 + 归谁」，{@code rows} 与 {@code evidence} 都为空 ——
   * **没有明细依据的数字不许出现**。
   */
  @PostMapping("/query")
  public SmartQueryResult query(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SmartQueryAskInput input) {
    return call(
        auth,
        "create",
        actor -> smartQuery.query(auth, actor.tenantId(), actor.userId(), input));
  }

  // ================================================================ 台账与常用查询

  /**
   * 查询台账 / 常用查询清单（**真分页 + 真计数 + 真搜索**）。
   *
   * <p>🔴 **只看得到自己的查询**：台账按 {@code owner_user_id} 过滤
   * （别人的问句不是公开信息）；租户之间由 RLS 挡。
   *
   * @param saved 只看常用查询（{@code true}）/ 只看普通台账（{@code false}）/ 不筛（不传）
   */
  @GetMapping("/queries")
  public SmartQueryPage queries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Boolean saved,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        actor -> smartQuery.list(actor.tenantId(), actor.userId(), keyword, saved, page, size));
  }

  /** 读一条查询（**RLS + 归属校验**：别人的查询读不到）。 */
  @GetMapping("/queries/{queryId}")
  public SmartQueryQueryView query(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID queryId) {
    return call(auth, "read", actor -> smartQuery.get(actor.tenantId(), actor.userId(), queryId));
  }

  /** 保存为常用查询（**只有发起人本人**能存自己的查询）。 */
  @PostMapping("/queries/{queryId}/save")
  public SmartQueryQueryView save(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID queryId,
      @Valid @RequestBody SmartQuerySaveInput input) {
    return call(
        auth,
        "update",
        actor ->
            smartQuery.saveFavorite(
                actor.tenantId(), actor.userId(), queryId, input.displayName()));
  }

  /** 取消常用查询（台账行留着 —— 它是一次真实的查询记录）。 */
  @DeleteMapping("/queries/{queryId}/save")
  public SmartQueryQueryView unsave(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID queryId) {
    return call(
        auth,
        "update",
        actor -> smartQuery.removeFavorite(actor.tenantId(), actor.userId(), queryId));
  }

  /**
   * 重跑一条查询（**沿用它当初解析出来的受控条件**，不重新猜）。
   *
   * <p>🔴 重跑**不新建**报表草稿：更新同一份草稿（13.6.1.1 的 PUT）⇒
   * 常用查询不会在自定义报表的清单里堆出一堆同名草稿。
   */
  @PostMapping("/queries/{queryId}/run")
  public SmartQueryResult rerun(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID queryId) {
    return call(
        auth,
        "create",
        actor -> smartQuery.rerun(auth, actor.tenantId(), actor.userId(), queryId));
  }

  /** 语义层目录里的指标清单（**给前端做下拉用的简表**）。 */
  @GetMapping("/metrics")
  public List<SmartQueryMetricView> metrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> smartQuery.semanticLayer(auth, actor.tenantId()).metrics());
  }

  /** 语义层目录里的维度清单（**5 个白名单维度**）。 */
  @GetMapping("/dimensions")
  public List<SmartQueryDimensionView> dimensions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth, "read", actor -> smartQuery.semanticLayer(auth, actor.tenantId()).dimensions());
  }
}
