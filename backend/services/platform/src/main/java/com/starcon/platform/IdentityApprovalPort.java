package com.starcon.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 读取 **17.4 审批**（identity 服务）的**跨服务端口** —— 本模块与上游之间**唯一**的通道。
 *
 * <p>🔴 <b>本端口只读上游的「规则与流程定义」，并只在上游**确实有程序化通道**的地方写上游。</b>
 *
 * <p><b>上游有哪些形状（本车道亲自查证过，不是推测）</b>：
 *
 * <ul>
 *   <li>{@code POST /identity/approval-instances} —— <b>程序化建单（intake）</b>：
 *       {@code X-Internal-Key} + 已认证会话 ⇒ 建一条 PENDING 实例，按金额分层分支选起始节点。
 *       <b>这是上游唯一的程序化通道</b>，本模块用它把「必须走人工」的单据真的送进人工审批。
 *   <li>{@code POST /identity/my-approvals/{id}/decide} / {@code .../transfer} ——
 *       <b>人工动作</b>：绑定调用者自己的会话，{@code actor_membership_id} 恒为调用者，
 *       取单条件是「这条待办归我」（指派 / 节点指定人 / 节点角色）。
 *       ⇒ <b>没有</b>可供服务间调用的程序化决策通道。
 * </ul>
 *
 * <p>⇒ 所以本模块的形态是「<b>判定 + 记录 + 经授权端口转交</b>」，
 * <b>不在平台侧另起一套审批状态机</b>（那会与 17.4 形成两套真相）。
 * 自动通过 / 自动驳回**不建上游实例**（没有单子需要人审，也就没有待办 ——
 * 这与上游自己「免审 EXEMPT」的语义一致）；必须走人工的单据则**真的**经 intake 送上游。
 *
 * <p>⚠️ <b>只转发调用方自己的 bearer，不传租户号</b>：上游仍按已认证成员关系解析租户并强制 RLS。
 * 租户隔离因此是<b>两道</b>（platform 侧 RLS + identity 侧 RLS），而不是「本服务替对方决定租户」。
 *
 * <p>⚠️ <b>取不到就显式失败</b>：读不到一律 503，消息写明「读不到」而不是「没有数据」——
 * 一个空集合在界面上会长成「上游没有待升级的单」，而真相是「这一格没算出来」。
 *
 * <p>⚠️ <b>动作类调用（decide / transfer）的 4xx 是域内结论、不是故障</b>：
 * 上游对「这条待办不在你的待办里」返回 404，那是**这次升级没执行成功**的真实原因，
 * 必须如实回传并留痕；只有 5xx / 不可达才翻成 503（那种是「再试一次」的事，
 * 不能把它写成「执行失败」——那会让一次临时抖动变成永久结论）。
 *
 * <p>⚠️ <b>本端口自带 HTTP 客户端</b>，不共用 {@code PlatformUpstreamClient}：
 * 那个客户端只提供「读」与「空体 POST」两种形状，而本端口需要
 * 「带请求体 + 额外请求头（内部密钥）的 POST」与「动作类调用的 4xx 不抛异常」两种新形状。
 * 改共用工具会把别的模块的写入面卷进来（本车道不碰别的模块的文件）。
 */
@Service
public class IdentityApprovalPort {

  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private final RestClient client;
  private final String internalKey;

  public IdentityApprovalPort(
      @Value("${IDENTITY_URL:http://127.0.0.1:8081}") String url,
      @Value("${INTERNAL_KEY}") String internalKey) {
    this.internalKey = internalKey;
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  // ================================================================ 读：流程定义与规则

  /**
   * 上游审批流详情（节点 + <b>金额分支</b> + <b>超时升级规则</b>）。
   *
   * <p>🔴 **金额分层的唯一来源**：上游的 {@code approval_flow_amount_branch}。
   * 本模块**不另造**一套金额区间语义 —— 区间是 {@code [min_amount, max_amount)} 左闭右开，
   * 与上游 {@code ApprovalInboxController.startNode} 同口径。
   */
  public AutoApprovalModels.ApprovalFlowDetailView flow(String bearer, UUID flowId) {
    return get(
        uriBuilder -> uriBuilder.path("/identity/approval-flows/" + flowId),
        bearer,
        AutoApprovalModels.ApprovalFlowDetailView.class);
  }

  /** 上游审批记录详情（含<b>当前节点</b>与<b>停留秒数</b>）。 */
  public AutoApprovalModels.ApprovalRecordDetail record(String bearer, UUID instanceId) {
    return get(
        uriBuilder -> uriBuilder.path("/identity/approval-records/" + instanceId),
        bearer,
        AutoApprovalModels.ApprovalRecordDetail.class);
  }

  /** 上游成员清单（把「升级给某个角色」解析成具体成员时用）。 */
  public List<AutoApprovalModels.MemberView> members(String bearer) {
    return java.util.Arrays.stream(
            get(
                uriBuilder -> uriBuilder.path("/identity/members"),
                bearer,
                AutoApprovalModels.MemberView[].class))
        .toList();
  }

  // ================================================================ 写：唯一的程序化通道

  /**
   * **程序化建单**（上游唯一的程序化通道）：把一张「必须走人工」的单据真的送进上游人工审批。
   *
   * <p>⚠️ 带内部密钥（上游的 intake 端点要 {@code X-Internal-Key}），
   * 同时**只转发调用方自己的 bearer** —— 上游据此解析出租户与申请人。
   */
  public AutoApprovalModels.ApprovalSubmitResult submit(
      String bearer,
      UUID flowId,
      String sourceSystem,
      String sourceReferenceId,
      String subjectTitle,
      String currencyCode,
      BigDecimal amount) {
    var body =
        new java.util.LinkedHashMap<String, Object>();
    body.put("flowId", flowId);
    body.put("sourceSystem", sourceSystem);
    body.put("sourceReferenceId", sourceReferenceId);
    body.put("subjectTitle", subjectTitle);
    if (currencyCode != null) body.put("currencyCode", currencyCode);
    if (amount != null) body.put("amount", amount);
    try {
      var result =
          client
              .post()
              .uri(builder -> builder.path("/identity/approval-instances").build())
              .header("Authorization", bearer)
              .header("X-Internal-Key", internalKey)
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(AutoApprovalModels.ApprovalSubmitResult.class);
      if (result == null) throw unavailable("上游建单没有返回内容");
      return result;
    } catch (RestClientResponseException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "上游建单被拒（HTTP " + e.getStatusCode().value() + "）：这张单还没能进入人工审批",
          e);
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("上游不可达");
    }
  }

  // ================================================================ 动作：人工审批动作（以调用者本人的身份）

  /**
   * 以**调用者自己**的身份执行一次审批动作（{@code APPROVE} / {@code REJECT}）。
   *
   * <p>🔴 <b>这就是「上游没有程序化决策通道」的落点</b>：上游只认**当前审批人自己**的会话，
   * 所以这里的 {@code bearer} 必须是**当前审批人**的会话 —— 本模块**不冒充**任何人。
   *
   * <p>⚠️ 4xx 是**域内结论**（如「这条待办不在你的待办里」），如实回传，不翻成 503。
   */
  public ActionOutcome decide(String bearer, UUID instanceId, String decision, String comment) {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("decision", decision);
    body.put("comment", comment);
    return action(
        uriBuilder -> uriBuilder.path("/identity/my-approvals/" + instanceId + "/decide"),
        bearer,
        body,
        "审批");
  }

  /** 以**调用者自己**的身份转交一条待办（上游只允许**当前审批人**转交）。 */
  public ActionOutcome transfer(
      String bearer, UUID instanceId, UUID toMembershipId, String comment) {
    var body = new java.util.LinkedHashMap<String, Object>();
    if (toMembershipId != null) body.put("toMembershipId", toMembershipId);
    body.put("comment", comment);
    return action(
        uriBuilder -> uriBuilder.path("/identity/my-approvals/" + instanceId + "/transfer"),
        bearer,
        body,
        "转交");
  }

  /**
   * 一次动作的结论。
   *
   * @param ok 上游是否真的执行了这次动作
   * @param status 上游返回的 HTTP 状态（0 = 上游不可达）
   * @param message 人可读的结论（失败时写清**为什么**没执行）
   */
  public record ActionOutcome(boolean ok, int status, String message) {}

  // ================================================================ 传输形状

  private <T> T get(
      java.util.function.Function<org.springframework.web.util.UriBuilder, org.springframework.web.util.UriBuilder>
          uriBuilder,
      String bearer,
      Class<T> type) {
    try {
      T result =
          client
              .get()
              .uri(builder -> uriBuilder.apply(builder).build())
              .header("Authorization", bearer)
              .retrieve()
              .body(type);
      if (result == null) throw unavailable("上游没有返回内容");
      return result;
    } catch (RestClientResponseException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "读不到上游 identity 的审批事实（HTTP " + e.getStatusCode().value() + "）"
              + "（是「读不到」，不是「没有数据」）",
          e);
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("上游不可达");
    }
  }

  private ActionOutcome action(
      java.util.function.Function<org.springframework.web.util.UriBuilder, org.springframework.web.util.UriBuilder>
          uriBuilder,
      String bearer,
      Object body,
      String what) {
    try {
      var result =
          client
              .post()
              .uri(builder -> uriBuilder.apply(builder).build())
              .header("Authorization", bearer)
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(ActionAck.class);
      return new ActionOutcome(
          true, 200, what + "已在上游执行" + (result == null ? "" : "（上游状态 " + result.status() + "）"));
    } catch (RestClientResponseException e) {
      var status = e.getStatusCode().value();
      if (status >= 500) throw unavailable("上游返回 " + status);
      return new ActionOutcome(false, status, explain(status, what));
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("上游不可达");
    }
  }

  /** 把上游的状态码翻成**这次为什么没执行成功**的人话。 */
  private static String explain(int status, String what) {
    return switch (status) {
      case 400 -> "上游拒绝了这次" + what + "：入参不合上游的要求（驳回必须带理由等）";
      case 403 -> "上游拒绝了这次" + what + "：当前用户没有该动作的权限";
      case 404 -> "上游拒绝了这次" + what + "：这条待办不在当前用户的待办里（当前用户不是该节点的审批人）";
      case 409 -> "上游拒绝了这次" + what + "：该待办的下一个节点是条件节点，路由无法确定";
      default -> "上游返回 " + status + "，这次" + what + "没有执行";
    };
  }

  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE, "上游 identity " + detail + "（是「读不到」，不是「没有数据」）");
  }

  /** 上游动作类端点的返回体（本模块只关心它有没有执行成功与结案状态）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ActionAck(UUID id, String instanceNo, @Schema(nullable = true) String status) {}
}
