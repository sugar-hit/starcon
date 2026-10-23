package com.starcon.finance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 17 审批（identity 服务）的**跨服务授权端口** —— 12.2.2.1 付款单与审批的审批接入面。
 *
 * <p>蓝图 12-finance.md 写死「关系：上游 = 12.1.2.1、12.1.2.2；<b>下游 = 12.2.2.2、17 审批</b>」，
 * 加粗句「<b>付款审批是资金安全的第一道控制</b>，大额付款必须双人复核或多级审批」。
 *
 * <p>🔴 <b>本模块不造第二套审批引擎。</b>identity 侧已交付四个模块
 * （审批流定义 / 审批规则 / 待我审批 / 审批记录），本端口只做三件事：
 *
 * <ol>
 *   <li>{@link #activeFlow} —— 查「付款单」这个单据类型当前**生效**的审批流
 *       （{@code GET /identity/approval-flows/active}）。
 *       它的作用有两条：① 拿到 {@code flowId} 才能提交；
 *       ② 把该流程的**节点结构**读回来 ——
 *       同阶段 {@code PARALLEL} 节点 = <b>双人复核</b>，多阶段 {@code SERIAL} 节点 = <b>多级审批</b>。
 *       本模块**如实展示**这个结构，但**不自己判定**「这笔要不要双人复核」——
 *       那是 identity 提交时按金额阈值分支做的事。
 *   <li>{@link #submit} —— 把付款单**提交**成一条审批实例
 *       （{@code POST /identity/approval-instances}）。
 *       🔴 <b>金额阈值分支在这里真的生效</b>：identity 自己按 {@code approval_flow_amount_branch}
 *       选出起始节点，并如实报出路由依据（{@code AMOUNT_BRANCH} / {@code FLOW_ORDER}）。
 *   <li>{@link #record} —— 把审批链路**读回来**（{@code GET /identity/approval-records/{id}}），
 *       镜像进本库 {@code finance_payment_approval_record}。
 * </ol>
 *
 * <p><b>租户不由此端口传递</b>：转发的是<b>调用方自己的</b> {@code Authorization} bearer，
 * identity 侧仍按已认证成员关系解析租户并强制 RLS。租户隔离是<b>两道</b>
 * （finance 侧 RLS + identity 侧 RLS），不是「本服务替对方决定租户」。
 *
 * <p><b>需要的权限</b>：{@code identity.approval_flow.read}（查生效流程）与
 * {@code identity.approval_record.read}（读审批链路）。<b>提交本身不要求额外权限</b>
 * —— 它要求内部密钥 + 已认证会话（提交人不需要「待我审批」的读权限才能发起一张要审批的单）。
 *
 * <p>⚠️ <b>没有注册审批流 ⇒ 拒绝提交</b>（不是「默认放行」）：
 * identity 那句「未注册的变更类操作默认可直接执行」说的是**变更类操作**，
 * 而付款是<b>资金动作</b> —— 蓝图把「付款审批」写成资金安全的第一道控制。
 * 静默放行一笔没人审过的付款，正是这条加粗句要防的事。
 */
@Service
public class ApprovalPort {

  /** 17.4.1.1 审批流定义：按单据类型查当前生效流程。 */
  static final String ACTIVE_FLOW_PATH = "/identity/approval-flows/active";

  /** 17.4.2.1 提交入口：各业务系统把它要管控的单据登记成一条待审批实例。 */
  static final String SUBMIT_PATH = "/identity/approval-instances";

  /** 17.4.3 审批记录：一条实例的完整审批链路。 */
  static final String RECORD_PATH = "/identity/approval-records/";

  /**
   * 本模块在 identity 侧登记的单据类型码。
   *
   * <p>⇒ 租户要给自己的付款单配审批流，就在 17.4.1.1 里注册一个
   * {@code document_type_code = 'PAYMENT_ORDER'} 的流程并置为 ACTIVE。
   */
  public static final String DOCUMENT_TYPE_CODE = "PAYMENT_ORDER";

  /** 上游 JSON 的 mapper（关掉 FAIL_ON_UNKNOWN_PROPERTIES：上游加字段不该把本模块撑爆）。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /** 生效审批流（只声明本模块真正用到的字段）。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ActiveFlowView(
      FlowView flow, @JsonIgnoreProperties(ignoreUnknown = true) List<NodeView> nodes) {}

  /** 流程概览。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record FlowView(
      UUID id,
      String flowName,
      String status,
      @JsonIgnoreProperties(ignoreUnknown = true) String documentTypeCode) {}

  /** 审批节点：**双人复核 / 多级审批的结构就在这里**。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record NodeView(
      String nodeKey,
      String nodeName,
      int nodeOrder,
      int stageIndex,
      /** SERIAL（串行 = 多级）/ PARALLEL（并行 = 双人复核）/ CONDITIONAL（条件分支）。 */
      String approvalMode,
      @JsonIgnoreProperties(ignoreUnknown = true) String approverType,
      @JsonIgnoreProperties(ignoreUnknown = true) String approverLabel) {}

  /** 提交结果。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record SubmitResult(
      UUID id,
      String instanceNo,
      UUID flowId,
      String currentNodeKey,
      String currentNodeName,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID applicantId,
      /** 路由依据：AMOUNT_BRANCH（命中金额阈值）/ FLOW_ORDER（流程顺序默认）。 */
      String routedBy,
      @JsonIgnoreProperties(ignoreUnknown = true) Instant submittedAt) {}

  /** 审批记录详情：实例头 + 逐节点动作。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record RecordDetail(
      @JsonIgnoreProperties(ignoreUnknown = true) InstanceView instance,
      @JsonIgnoreProperties(ignoreUnknown = true) List<ActionView> timeline) {}

  /** 审批实例头。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record InstanceView(
      UUID id,
      String instanceNo,
      String status,
      @JsonIgnoreProperties(ignoreUnknown = true) String subjectTitle,
      @JsonIgnoreProperties(ignoreUnknown = true) BigDecimal amount,
      @JsonIgnoreProperties(ignoreUnknown = true) String currencyCode,
      @JsonIgnoreProperties(ignoreUnknown = true) String currentNodeKey,
      @JsonIgnoreProperties(ignoreUnknown = true) String currentNodeName,
      @JsonIgnoreProperties(ignoreUnknown = true) Instant submittedAt,
      @JsonIgnoreProperties(ignoreUnknown = true) Instant finishedAt) {}

  /** 审批链路上的一个动作。 */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ActionView(
      UUID actionId,
      /** APPROVE / REJECT / TRANSFER。 */
      String action,
      String nodeKey,
      @JsonIgnoreProperties(ignoreUnknown = true) String nodeName,
      int nodeOrder,
      @JsonIgnoreProperties(ignoreUnknown = true) UUID actorId,
      @JsonIgnoreProperties(ignoreUnknown = true) String actorName,
      @JsonIgnoreProperties(ignoreUnknown = true) String comment,
      @JsonIgnoreProperties(ignoreUnknown = true) Instant decidedAt,
      long dwellSeconds) {}

  private final RestClient client;
  private final String internalKey;

  public ApprovalPort(
      @Value("${IDENTITY_URL}") String url, @Value("${INTERNAL_KEY}") String internalKey) {
    var factory =
        new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.internalKey = internalKey;
    this.client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .messageConverters(java.util.List.of(new MappingJackson2HttpMessageConverter(JSON)))
            .build();
  }

  /**
   * 查「付款单」当前生效的审批流。
   *
   * @param bearer 调用方自己的会话
   * @throws ResponseStatusException 404 = 该单据类型**尚未注册生效审批流**
   */
  public ActiveFlowView activeFlow(String documentTypeCode, String bearer) {
    try {
      ActiveFlowView result =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path(ACTIVE_FLOW_PATH)
                          .queryParam("documentTypeCode", documentTypeCode)
                          .build())
              .header("Authorization", bearer)
              .retrieve()
              .body(ActiveFlowView.class);
      if (result == null || result.flow() == null) {
        throw unavailable("identity 服务没有返回审批流");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 404) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "单据类型「" + documentTypeCode + "」尚未注册生效审批流（17.4.1.1）",
            e);
      }
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "读取审批流需要 identity.approval_flow.read 权限（当前角色没有）",
            e);
      }
      throw unavailable("identity 服务返回 " + e.getStatusCode().value() + "：审批流读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("identity 服务不可达");
    }
  }

  /**
   * 提交一条审批实例。
   *
   * <p>⚠️ 该端点要求<b>内部密钥</b>（{@code X-Internal-Key}）+ <b>调用方自己的会话</b>：
   * 租户与申请人**都**来自已认证会话成员关系，请求体里没有 tenantId / applicantId。
   */
  public SubmitResult submit(
      UUID flowId,
      String sourceSystem,
      String sourceReferenceId,
      String subjectTitle,
      String currencyCode,
      BigDecimal amount,
      String bearer) {
    var body =
        java.util.Map.of(
            "flowId", flowId,
            "sourceSystem", sourceSystem,
            "sourceReferenceId", sourceReferenceId,
            "subjectTitle", subjectTitle,
            "currencyCode", currencyCode,
            "amount", amount);
    try {
      SubmitResult result =
          client
              .post()
              .uri(SUBMIT_PATH)
              .header("X-Internal-Key", internalKey)
              .header("Authorization", bearer)
              .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(SubmitResult.class);
      if (result == null || result.id() == null) {
        throw unavailable("identity 服务没有返回审批实例");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 409) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "该付款单已有一条在办的审批实例，请勿重复提交", e);
      }
      if (e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "提交审批被拒绝：内部密钥或会话校验未通过", e);
      }
      throw unavailable("identity 服务返回 " + e.getStatusCode().value() + "：审批提交失败");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("identity 服务不可达");
    }
  }

  /**
   * 读一条审批实例的完整链路（**任何状态**都能读 —— 审批记录要回答「三个月前那单最后怎么了」）。
   *
   * @param bearer 调用方自己的会话
   */
  public RecordDetail record(UUID instanceId, String bearer) {
    try {
      RecordDetail result =
          client
              .get()
              .uri(RECORD_PATH + instanceId)
              .header("Authorization", bearer)
              .retrieve()
              .body(RecordDetail.class);
      if (result == null || result.instance() == null) {
        throw unavailable("identity 服务没有返回审批记录");
      }
      return result;
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "读取审批记录需要 identity.approval_record.read 权限（当前角色没有）",
            e);
      }
      throw unavailable("identity 服务返回 " + e.getStatusCode().value() + "：审批记录读不到");
    } catch (org.springframework.web.client.ResourceAccessException e) {
      throw unavailable("identity 服务不可达");
    }
  }

  /** 上游不可用一律 503，理由写明「读不到」而不是「没有数据」。 */
  private ResponseStatusException unavailable(String detail) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, detail);
  }

  /** 供服务层复用的时间转换：上游给的是 {@link Instant}。 */
  static OffsetDateTime toOffset(Instant instant) {
    return instant == null ? null : instant.atOffset(java.time.ZoneOffset.UTC);
  }

  /** 供服务层复用的日期转换。 */
  static LocalDate toDate(Instant instant) {
    return instant == null ? null : instant.atZone(java.time.ZoneOffset.UTC).toLocalDate();
  }
}
