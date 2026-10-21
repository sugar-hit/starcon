package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.4.1.1 调价单的**审批跨服务端口** —— 17 组织（identity 服务）的审批规则与审批实例。
 *
 * <p>蓝图对调价单的界定：
 *
 * <blockquote>
 *
 * **职责**：发起价格变更：选择 SKU 与目标店铺、输入新旧价、说明原因、提交审批（若启用）；记录变更前后值。
 *
 * **输入**：调价请求；`expected_version`；审批要求（17 组织的审批规则）。
 *
 * **输出**：调价单（草稿/待审/已批/已执行/已驳回）；生效后触发平台回写。
 *
 * **关系**：上游 = 5.3.1.1、5.3.2.2；下游 = 5.4.2.1、17 审批。**调价是受控动作**：大额或大幅调价走审批，普通调整可配置免审。
 *
 * </blockquote>
 *
 * <h2>🔴 审批不是本模块发明的</h2>
 *
 * <p>「**提交审批（若启用）**」与「普通调整可配置免审」这两句**全部由 17 组织承担**：
 *
 * <ul>
 *   <li><b>要不要审</b>：{@code POST /identity/approval-rules/evaluate}（17.4.1.2）——
 *       单据类型固定用出厂注册的 {@code PRICE_CHANGE}（identity V83 第 304 行注册了 12 种
 *       单据类型，{@code PRICE_CHANGE} 是其中之一，语义就是「售价与成本价调整」）。
 *       结论有三种来源：{@code RULE}（命中某条规则）、{@code DEFAULT}（走规则集的 defaultAction）、
 *       {@code UNCONTROLLED}（本租户既没配生效规则集也没配生效流程 ⇒ <b>默认放行</b>）。</li>
 *   <li><b>谁来审、批不批</b>：审批人在 identity 侧决定，本模块<b>不发明审批流</b>，
 *       结论靠**拉取**回来（{@code GET /identity/approval-records/{id}}）。</li>
 * </ul>
 *
 * <p>所以本端口只有三个动作：求值、提交实例、查实例状态。**没有**「本地批准 / 本地驳回」——
 * 那会让本服务凭空造出一个 approval 流程，而 identity 那边还什么都不知道。
 *
 * <h2>🔴 租户不由此端口传递</h2>
 *
 * <p>转发的是<b>调用方自己的 {@code Authorization} bearer</b>，identity 侧仍按已认证成员关系
 * 解析租户并强制 RLS。隔离是<b>两道</b>（catalog 侧 RLS + identity 侧 RLS），
 * 而不是「本服务替对方决定租户」。{@code X-Tenant-Id} 这类头在本端口里从头到尾没有出现过。
 *
 * <p>唯一的例外是 {@link #submit}：它需要 {@code X-Internal-Key}（服务间授权），
 * 这个密钥<b>从配置注入，绝不写死在代码里</b>。
 */
public interface PriceAdjustmentApprovalPort {

  /**
   * 本模块在 identity 侧的来源系统标识。
   *
   * <p>它是 {@code approval_instance.source_system} 的一列，也是「同一来源单据只能有一条
   * 存活实例」那个幂等约束的一半（另一半是 {@code source_reference_id} = 本模块的调价单 id）。
   */
  String SOURCE_SYSTEM = "catalog";

  /** 本模块用的单据类型（identity V83 出厂注册，语义即「售价与成本价调整」）。 */
  String DOCUMENT_TYPE = "PRICE_CHANGE";

  /**
   * 求值：这次调价要不要走审批（17.4.1.2 的只读试算）。
   *
   * <p>🔴 它**不产生**审批实例 —— 只是一次判定，不建单、不出待办、不发通知。
   */
  Evaluation evaluate(EvaluationInput input, String bearer);

  /**
   * 提交审批实例（17.4.2.1 的提交入口）。
   *
   * <p>🔴 **幂等**：同一来源单据存活期间只能有一条实例，重复提交返回 <b>409</b>。
   * 本模块把 409 当成「幂等命中」处理（回读既有实例号），而不是当成错误 ——
   * 网络重试不该造出第二条待办。
   */
  Submitted submit(SubmitInput input, String bearer);

  /**
   * 查一条审批实例的当前状态（拉取式同步）。
   *
   * <p>🔴 本仓**没有**从 identity 推送到本服务的事件通道（libraries 下只有 tenancy），
   * 所以状态同步是**拉取式**的：调价单停在「待审」，由本模块显式刷新（或读详情时顺带刷新）
   * 把 identity 侧的结论取回来。
   */
  InstanceStatus status(UUID instanceId, String bearer);

  /**
   * 按来源单据找一条**在办**的审批实例（幂等命中时用）。
   *
   * <p>🔴 identity 的「同一来源单据存活期间只能有一条实例」约束报的是 409，
   * 而 409 的响应体里**没有**实例号 —— 所以要自己按 {@code sourceReferenceId} 找回来。
   * 找不到就返回 null（调用方如实说「实例号暂时读不回来」），**绝不**凭空造一个实例号。
   */
  InstanceStatus findLive(String sourceReferenceId, String bearer);

  // ================================================================ 契约形状

  /** 求值入参。 */
  record EvaluationInput(String documentTypeCode, String currencyCode, BigDecimal amount) {}

  /**
   * 求值结论（与 identity 的 {@code ApprovalEvaluationView} 逐字对应）。
   *
   * <p>🔴 {@code source=UNCONTROLLED} = 本租户没给 PRICE_CHANGE 配生效规则集/流程
   * ⇒ **默认放行**（与 identity 的语义一致，不拦死）。
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Evaluation(
      String documentTypeCode,
      String source,
      String decision,
      UUID matchedRuleId,
      String matchedRuleName,
      Integer matchedPriority,
      UUID targetFlowId,
      String targetFlowName,
      BigDecimal exemptionCeilingAmount,
      String exemptionCeilingCurrency,
      String reason) {

    /** 这次要不要送审。 */
    public boolean requiresApproval() {
      return "SUBMIT_FOR_APPROVAL".equals(decision);
    }

    /** 本租户压根没配规则/流程 ⇒ 默认放行。 */
    public boolean uncontrolled() {
      return "UNCONTROLLED".equals(source);
    }
  }

  /** 提交实例的入参（与 identity 的 {@code ApprovalSubmitInput} 逐字对应）。 */
  record SubmitInput(
      UUID flowId,
      String sourceSystem,
      String sourceReferenceId,
      String subjectTitle,
      String currencyCode,
      BigDecimal amount) {}

  /** 提交结果（与 identity 的 {@code ApprovalSubmitResult} 逐字对应）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Submitted(
      UUID id,
      String instanceNo,
      UUID flowId,
      String currentNodeKey,
      String currentNodeName,
      UUID applicantId,
      String routedBy,
      Instant submittedAt) {}

  /** 实例状态（identity 的 {@code ApprovalRecordView} 里本模块用得到的几列）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record InstanceStatus(
      UUID id,
      String instanceNo,
      String status,
      String currentNodeKey,
      String currentNodeName,
      String sourceSystem,
      String sourceReferenceId,
      Instant submittedAt,
      Instant finishedAt) {}

  /** identity 侧 {@code ApprovalRecordDetail} 的外壳（只取 instance）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record InstanceDetail(InstanceStatus instance) {}

  /** identity 侧 {@code ApprovalRecordPage} 的外壳（只取 items）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record RecordPage(List<InstanceStatus> items, long total, int page, int size) {}

  /** 审批服务不可达。 */
  class ApprovalUnavailableException extends RuntimeException {
    public ApprovalUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }

    public ApprovalUnavailableException(String message) {
      super(message);
    }
  }

  /**
   * 审批实例**已存在**（identity 回 409）。
   *
   * <p>🔴 它**不是错误**：那是「同一来源单据存活期间只能有一条实例」的幂等命中。
   * 本模块据此**回读**既有实例而不是重投一条 —— 重投会在 17 组织那边造出第二条待办。
   */
  class ApprovalConflictException extends RuntimeException {
    public ApprovalConflictException(String message) {
      super(message);
    }
  }

  // ================================================================ HTTP 实现

  /**
   * 真实的 HTTP 实现。
   *
   * <p>用法与 {@link ShipmentWritebackPort}（trade 读 channel）、{@link ChannelPlatformPort}
   * （catalog 读 channel）逐字同构：{@link RestClient} + {@code @Value} 读配置 +
   * 连接/读超时上限 + 错误如实分类。
   */
  class HttpPriceAdjustmentApprovalPort implements PriceAdjustmentApprovalPort {

    private final RestClient client;
    private final String internalKey;

    public HttpPriceAdjustmentApprovalPort(
        @Value("${IDENTITY_URL:}") String url, @Value("${INTERNAL_KEY:}") String internalKey) {
      var factory =
          new JdkClientHttpRequestFactory(
              java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
      factory.setReadTimeout(Duration.ofSeconds(8));
      this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
      this.internalKey = internalKey == null ? "" : internalKey;
    }

    @Override
    public Evaluation evaluate(EvaluationInput input, String bearer) {
      try {
        // 🔴 用 LinkedHashMap 而不是 Map.of：Map.of 不接受 null 值，而 identity 侧
        //   「amount 为 null」与「amount 为 0」是**两件事**（0 会命中免审额度区间，
        //   null 才是「这次没有金额」）。这里原样把 null 传过去，绝不拿 0 顶。
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("documentTypeCode", input.documentTypeCode());
        body.put("currencyCode", input.currencyCode());
        body.put("amount", input.amount());
        body.put("crossLegalEntity", Boolean.FALSE);
        body.put("crossDepartment", Boolean.FALSE);
        body.put("newVendor", Boolean.FALSE);
        body.put("highRiskProduct", Boolean.FALSE);
        Evaluation view =
            client
                .post()
                .uri("/identity/approval-rules/evaluate")
                .header("Authorization", bearer)
                .body(body)
                .retrieve()
                .body(Evaluation.class);
        if (view == null) {
          throw unavailable("审批规则求值没有返回结论，无法判断这次调价要不要走审批", null);
        }
        return view;
      } catch (RestClientResponseException e) {
        throw translate(e, "审批规则求值失败");
      } catch (RestClientException e) {
        throw unavailable("审批服务暂时不可达", e);
      }
    }

    @Override
    public Submitted submit(SubmitInput input, String bearer) {
      try {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("flowId", input.flowId().toString());
        body.put("sourceSystem", input.sourceSystem());
        body.put("sourceReferenceId", input.sourceReferenceId());
        body.put("subjectTitle", input.subjectTitle());
        body.put("currencyCode", input.currencyCode());
        body.put("amount", input.amount());
        Submitted submitted =
            client
                .post()
                .uri("/identity/approval-instances")
                .header("X-Internal-Key", internalKey)
                .header("Authorization", bearer)
                .body(body)
                .retrieve()
                .body(Submitted.class);
        if (submitted == null || submitted.id() == null) {
          throw unavailable("审批实例提交没有返回实例号，无法确认这张调价单挂在哪条待办上", null);
        }
        return submitted;
      } catch (RestClientResponseException e) {
        if (e.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
          // 🔴 409 = 同一来源单据已有一条在办实例 ⇒ **幂等命中**，不是错误。
          throw new ApprovalConflictException(
              "审批实例已存在（幂等命中）：" + e.getResponseBodyAsString());
        }
        throw translate(e, "提交审批实例失败");
      } catch (RestClientException e) {
        throw unavailable("审批服务暂时不可达", e);
      }
    }

    @Override
    public InstanceStatus status(UUID instanceId, String bearer) {
      try {
        InstanceDetail detail =
            client
                .get()
                .uri("/identity/approval-records/" + instanceId)
                .header("Authorization", bearer)
                .retrieve()
                .body(InstanceDetail.class);
        if (detail == null || detail.instance() == null) {
          throw unavailable("审批记录查询没有返回实例状态", null);
        }
        return detail.instance();
      } catch (RestClientResponseException e) {
        throw translate(e, "查询审批实例状态失败");
      } catch (RestClientException e) {
        throw unavailable("审批服务暂时不可达", e);
      }
    }

    /**
     * 按来源单据找一条在办实例。
     *
     * <p>🔴 identity 的 {@code GET /identity/approval-records} 支持按 {@code sourceSystem} 过滤，
     * 但**没有** {@code sourceReferenceId} 这个筛选参数 —— 所以这里取回该来源系统的在办实例，
     * 再逐条比对 {@code sourceReferenceId}。
     *
     * <p>扫描上限 200 条：超过就**如实**返回 null（调用方会标「实例号暂时读不回来」），
     * 绝不返回一条对不上的实例。
     */
    @Override
    public InstanceStatus findLive(String sourceReferenceId, String bearer) {
      try {
        var page =
            client
                .get()
                .uri(
                    "/identity/approval-records?sourceSystem="
                        + SOURCE_SYSTEM
                        + "&status=PENDING&size=200")
                .header("Authorization", bearer)
                .retrieve()
                .body(RecordPage.class);
        if (page == null || page.items() == null) return null;
        for (var row : page.items()) {
          if (row == null) continue;
          if (sourceReferenceId.equals(row.sourceReferenceId())) return row;
        }
        return null;
      } catch (RestClientResponseException e) {
        throw translate(e, "查询在办审批实例失败");
      } catch (RestClientException e) {
        throw unavailable("审批服务暂时不可达", e);
      }
    }

    /**
     * 401/403 **如实透传**（登录失效 / 权限不足就是这两件事，不能说成「审批服务挂了」），
     * 其余一律 503 并说清是「审批侧不可达」。
     */
    private RuntimeException translate(RestClientResponseException e, String whatFailed) {
      int status = e.getStatusCode().value();
      if (status == HttpStatus.UNAUTHORIZED.value()) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
      }
      if (status == HttpStatus.FORBIDDEN.value()) {
        return new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "当前身份无权在审批服务上完成「" + whatFailed + "」"
                + "（求值需要 identity.approval_rule.read，查实例状态需要 identity.approval_record.read）。"
                + "本服务把调用方的 Authorization 头原样透传过去，租户由审批服务从成员关系解析 —— "
                + "不传这个头等于丢掉租户上下文。这里明确拒绝而不是放行");
      }
      return unavailable(whatFailed + "：审批服务返回 " + status, e);
    }

    private ApprovalUnavailableException unavailable(String reason, Throwable cause) {
      return new ApprovalUnavailableException(
          reason
              + "。调价单的审批结论由 17 组织（identity 服务）裁定，"
              + "本服务**不会**在审批服务不可达时把单子当成已批 —— "
              + "那会把一张没审过的调价单直接推到执行。",
          cause);
    }
  }

  /**
   * 审批服务未配置时的兜底实现。
   *
   * <p>**显式抛错而不是静默放行**：{@code IDENTITY_URL} 没配时，审批规则一条都求不到值。
   * 静默放行等于「审批这一环被悄悄跳过了」，而蓝图那句「调价是受控动作」正是为了防这个。
   */
  class UnwiredPriceAdjustmentApprovalPort implements PriceAdjustmentApprovalPort {

    @Override
    public Evaluation evaluate(EvaluationInput input, String bearer) {
      throw new ApprovalUnavailableException(
          "审批服务地址未配置（IDENTITY_URL 为空）：审批规则求值与审批实例的唯一来源是 identity 服务的授权端点，"
              + "地址没配就一条都求不到。请配置 IDENTITY_URL 后重试 —— "
              + "本服务不会在没有审批规则的情况下默认放行调价单");
    }

    @Override
    public Submitted submit(SubmitInput input, String bearer) {
      throw new ApprovalUnavailableException(
          "审批服务地址未配置（IDENTITY_URL 为空）：无法提交审批实例，"
              + "这张调价单会停在「待审」，不会被当成已批");
    }

    @Override
    public InstanceStatus status(UUID instanceId, String bearer) {
      throw new ApprovalUnavailableException(
          "审批服务地址未配置（IDENTITY_URL 为空）：拉不到审批实例状态，"
              + "调价单会停在「待审」，不会被当成已批");
    }

    @Override
    public InstanceStatus findLive(String sourceReferenceId, String bearer) {
      throw new ApprovalUnavailableException(
          "审批服务地址未配置（IDENTITY_URL 为空）：读不回在办的审批实例，"
              + "调价单会停在「待审」，不会被当成已批");
    }
  }
}
