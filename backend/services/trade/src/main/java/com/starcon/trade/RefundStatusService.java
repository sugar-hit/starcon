package com.starcon.trade;

import static com.starcon.trade.RefundStatusModels.*;

import com.starcon.trade.RefundStatusModels.DiscrepancyKind;
import com.starcon.trade.RefundStatusModels.DiscrepancyResolution;
import com.starcon.trade.RefundStatusModels.FailureClass;
import com.starcon.trade.RefundStatusModels.PlatformRefundMethod;
import com.starcon.trade.RefundStatusModels.PlatformSyncState;
import com.starcon.trade.RefundStatusModels.RefundAction;
import com.starcon.trade.RefundStatusModels.RefundActionSpec;
import com.starcon.trade.RefundStatusModels.RefundAttemptRow;
import com.starcon.trade.RefundStatusModels.RefundDiscrepancyCreate;
import com.starcon.trade.RefundStatusModels.RefundDiscrepancyPage;
import com.starcon.trade.RefundStatusModels.RefundDiscrepancyResolutionRequest;
import com.starcon.trade.RefundStatusModels.RefundDiscrepancyRow;
import com.starcon.trade.RefundStatusModels.RefundDispatchRequest;
import com.starcon.trade.RefundStatusModels.RefundDownstream;
import com.starcon.trade.RefundStatusModels.RefundExecutionCreate;
import com.starcon.trade.RefundStatusModels.RefundExecutionDetail;
import com.starcon.trade.RefundStatusModels.RefundExecutionPage;
import com.starcon.trade.RefundStatusModels.RefundExecutionQuery;
import com.starcon.trade.RefundStatusModels.RefundExecutionRow;
import com.starcon.trade.RefundStatusModels.RefundExecutionState;
import com.starcon.trade.RefundStatusModels.RefundFilterOptions;
import com.starcon.trade.RefundStatusModels.RefundPlatformSyncRequest;
import com.starcon.trade.RefundStatusModels.RefundReportRequest;
import com.starcon.trade.RefundStatusModels.RefundReportRow;
import com.starcon.trade.RefundStatusModels.RefundRetryAdvice;
import com.starcon.trade.RefundStatusModels.RefundStateTransitionSpec;
import com.starcon.trade.RefundStatusModels.RefundStatusCapabilities;
import com.starcon.trade.RefundStatusModels.RefundStatusEventRow;
import com.starcon.trade.RefundStatusModels.RefundUpstream;
import com.starcon.trade.RefundStatusModels.RefundVerificationRequest;
import com.starcon.trade.RefundStatusModels.RefundVerificationRow;
import com.starcon.trade.RefundStatusModels.ReportOutcome;
import com.starcon.trade.RefundStatusModels.ReportSource;
import com.starcon.trade.RefundStatusModels.VerificationVerdict;
import com.starcon.trade.RefundStatusModels.VerifySource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 11.3.1.2 退款状态。
 *
 * <p><b>本类是 refund_execution 簇（V159）的写入所有者</b>。对 {@code sales_order} 簇（V99）
 * 与 {@code aftersales_case} 簇（V156）**只读** —— 刻意不加外键、不 ALTER，跨车道 DDL 回归面 = 0。
 *
 * <p><b>四条硬纪律</b>：
 *
 * <ol>
 *   <li><b>幂等在 DB 层，不在 Java 层。</b>{@link #register} 与 {@link #dispatch} 都先查幂等键、
 *       命中就返回既有结果；<b>并且</b>依赖 V159 的 UNIQUE(tenant_id, idempotency_key) 兜底 ——
 *       并发下两个事务同时「查不到」时，第二个会被数据库拒绝，我们捕获它并回读既有行。
 *       只有 Java 的 if 判断挡不住并发，而资损防护不能靠运气。
 *   <li><b>「先查证交易再重试」由状态机推导，不由调用方自觉。</b>{@link #dispatch} 对 RETRY
 *       逐条校验：查证记录存在、属于这笔退款、结论为 CONFIRMED_NOT_SETTLED。
 *       此外 V159 的触发器 {@code guard_refund_attempt_retry} 在 DB 层再挡一次，
 *       绕过本类直接 INSERT 也照样被拒（集成测试有用例专门证明这一点）。
 *   <li><b>超时永远不自动变失败。</b>{@link #report} 里 {@code TIMEOUT} 与 {@code UNKNOWN}
 *       只能推进到 {@link RefundExecutionState#UNKNOWN}；而 UNKNOWN 的建议恒为
 *       {@link RefundAction#VERIFY}，绝不是 RETRY。
 *   <li><b>差异如实报，不自动抹平。</b>{@link #detectDiscrepancy} 只登记事实；
 *       {@link #resolveDiscrepancy} 只能由人调用。系统不会把「平台 100 / 系统 99」
 *       悄悄改成一致。
 * </ol>
 *
 * <p><b>时间绑定</b>：时间一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 * {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s（本项目所在时区）。
 *
 * <p><b>金额一律 BigDecimal + numeric(19,4)</b>，禁浮点（AGENTS.md）。差异比对用
 * {@link BigDecimal#compareTo} 而非 {@code equals} —— {@code equals} 会把
 * {@code 10.00} 与 {@code 10.0000} 判成不同，而它们在 numeric 列里是同一个数。
 */
@Service
public class RefundStatusService {

  /** 单页上限。与 AftersalesCaseService 的 MAX_PAGE_SIZE 同一档。 */
  private static final int MAX_PAGE_SIZE = 200;

  /**
   * 排序白名单。<b>刻意不接受任意表达式</b> —— 排序串会拼进 SQL。
   */
  private static final Map<String, String> SORTS =
      Map.of(
          "CREATED_DESC", "e.created_at DESC, e.id",
          "CREATED_ASC", "e.created_at ASC, e.id",
          "AMOUNT_DESC", "e.refund_amount DESC, e.id",
          "AMOUNT_ASC", "e.refund_amount ASC, e.id",
          "ATTENTION_FIRST",
              "CASE e.execution_state WHEN 'UNKNOWN' THEN 0 WHEN 'FAILED' THEN 1"
                  + " WHEN 'DISCREPANCY' THEN 2 ELSE 3 END, e.updated_at DESC, e.id");

  private final DSLContext sql;

  public RefundStatusService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：状态机真身（逐条从 {@link RefundExecutionState#allowedNext()} 推导，
   * **不是另抄一份**）+ 上游 / 下游逐条 Delivered 与归属模块。
   */
  public RefundStatusCapabilities capabilities() {
    List<RefundStateTransitionSpec> transitions = new ArrayList<>();
    for (RefundExecutionState from : RefundExecutionState.values()) {
      for (RefundExecutionState to : from.allowedNext()) {
        // 「需要查证」= 该转换的出发点是 UNKNOWN（结果未知，必须先查证交易）。
        // 这是从状态机推导出来的，不是硬编码的另一张表。
        boolean requiresVerification = from == RefundExecutionState.UNKNOWN && to != RefundExecutionState.DISCREPANCY;
        transitions.add(
            new RefundStateTransitionSpec(
                from.name(),
                from.label(),
                to.name(),
                to.label(),
                requiresVerification,
                requiresVerification
                    ? VerificationVerdict.CONFIRMED_NOT_SETTLED.name()
                    : null,
                switch (from.name() + ">" + to.name()) {
                  case "UNKNOWN>IN_FLIGHT" ->
                      "必须先查证交易且结论为 CONFIRMED_NOT_SETTLED；"
                          + "查证确认已付出时只能推进到 SUCCEEDED（重试会造成重复退款）";
                  case "UNKNOWN>FAILED" -> "必须先查证交易且结论为 CONFIRMED_NOT_SETTLED；超时不会自动变失败";
                  case "UNKNOWN>SUCCEEDED" -> "查证结论为 CONFIRMED_SETTLED 时走这一条";
                  case "FAILED>IN_FLIGHT" -> "确定性失败（钱确定没动）可直接重试，无需查证";
                  case "IN_FLIGHT>SUCCEEDED", "IN_FLIGHT>FAILED", "IN_FLIGHT>UNKNOWN" ->
                      "外部回报驱动：成功 / 明确失败 / 超时（超时刻意落到 UNKNOWN）";
                  case "DISCREPANCY>SUCCEEDED", "DISCREPANCY>FAILED" ->
                      "差异只能由人工裁定推进，系统不自动抹平";
                  default -> "首次派发，带幂等键";
                }));
      }
    }

    return new RefundStatusCapabilities(
        transitions,
        Arrays.stream(FailureClass.values()).map(Enum::name).toList(),
        Arrays.stream(VerificationVerdict.values()).map(Enum::name).toList(),
        Arrays.stream(DiscrepancyKind.values()).map(Enum::name).toList(),
        Arrays.stream(PlatformSyncState.values()).map(Enum::name).toList(),
        new RefundUpstream(
            false,
            "12 财务（12.1.1.1 ~ 12.7.2.2）",
            "backend/services 下无 finance 服务实现；12 财务在独立库，按 AGENTS.md 模块边界纪律"
                + "禁止跨服务 SQL / FK / 视图 ⇒ 本服务收不到它的回报，只能等正式接口。"
                + "当前真正能写 refund_execution_report 的是 PLATFORM_SYNC（平台侧退款查询）"
                + "与 MANUAL_OPERATOR（人工登记）；服务层**拒绝** reportSource=FINANCE_MODULE_12，"
                + "如实报不可用而不是收下一条来源不明的记录。",
            false,
            "11.3.1.1 退款申请",
            "同 L3 的退款申请模块未交付：trade 的迁移目录里没有任何退款申请单表。"
                + "本模块**自己拥有**退款执行与平台同步的状态模型，"
                + "并在登记入口显式标注这是 11.3.1.1 未交付期间的入口。",
            "12 财务交付后由它（或事件订阅）走授权 API POST /trade/refunds/{id}/reports "
                + "按同一形状回报，表结构与全部查询都不用改。"),
        new RefundDownstream(
            false,
            "15 客户与客服（15.x 工单与坐席）",
            "卷 15 的 24 个 L4 里只交付 1 个（15.4.1.1 知识库，在 backend/services/crm 的独立库 "
                + "starcon_crm），按模块边界纪律本服务永远读不到它的表 ⇒ 退款完成通知买家"
                + "这条对接当前不可用；本服务不造一张假的通知表。",
            false,
            "9 订单（订单退款状态回写）",
            "9 订单卷已有 24 个模块交付，但「订单退款状态回写」这一具体对接未交付。"
                + "本模块不写 sales_order：它是 9.1.1.1 车道的写入所有者，"
                + "反向写别人的表会越过「每张事实表只有一个写入所有者」。"
                + "9 侧交付后由它订阅本模块的状态事件更新订单退款状态。",
            false,
            "2 平台与店铺 / 卷 2 平台查询",
            "平台侧退款查询当前只能由人工登记（MANUAL_OPERATOR）代偿；"
                + "channel 服务的 vendor 适配器里没有退款查询实现。"
                + "capabilities 如实报 platformSyncDelivered=false。"),
        List.of(
            new RefundActionSpec(
                "DISPATCH",
                "发起退款",
                true,
                "UNIQUE(tenant_id, idempotency_key) + guard_refund_attempt_retry 的 attempt_no 连号校验",
                null,
                null),
            new RefundActionSpec(
                "RETRY",
                "重试退款",
                true,
                "guard_refund_attempt_retry 触发器：必须携带 verdict=CONFIRMED_NOT_SETTLED 的查证记录",
                null,
                null),
            new RefundActionSpec(
                "VERIFY",
                "查证交易",
                true,
                "强制前置动作：UNKNOWN 状态下 RETRY 与 AWAIT 都被拒绝",
                null,
                null),
            new RefundActionSpec(
                "AWAIT",
                "等待结果",
                true,
                "IN_FLIGHT 状态下任何重发都会被 guard_refund_attempt_retry 拒绝（重复支付）",
                null,
                null),
            new RefundActionSpec(
                "MANUAL",
                "人工裁定差异",
                true,
                "refund_discrepancy 的 UPDATE 做成列级白名单：只授回 resolution_state / "
                    + "resolution_note / resolved_at / resolved_by，检测事实改不动",
                null,
                null),
            new RefundActionSpec(
                "NOTIFY_BUYER",
                "通知买家",
                false,
                null,
                "下游 15 客服未交付本模块所需的对接（见 downstream.customerNotifyNote）",
                "15 客户与客服"),
            new RefundActionSpec(
                "WRITE_BACK_ORDER",
                "回写订单退款状态",
                false,
                null,
                "下游 9 订单的「订单退款状态回写」未交付；且 sales_order 是 9.1.1.1 车道的"
                    + "写入所有者，本模块不反向写它",
                "9 订单"),
            new RefundActionSpec(
                "AUTO_RETRY",
                "超时自动重试",
                false,
                null,
                "**刻意不提供**。蓝图加粗句「外部退款超时不等于失败，重复发起退款会造成实质资损」"
                    + "：超时后自动重试就是重复退款。要重试必须先有一次查证动作。",
                null)),
        List.of(
            "每次派发必须带幂等键，DB 层 UNIQUE(tenant_id, idempotency_key) 兜底",
            "重复提交同一幂等键返回同一结果（既有那笔），不会发起第二次退款",
            "退款业务键 UNIQUE(tenant_id, idempotency_key)：同一笔退款不会被登记成两条执行记录",
            "并发下的兜底：两个事务同时「查不到」时，第二个撞 DB 唯一约束，捕获后回读既有行",
            "派发流水 refund_attempt 是 append-only（REVOKE UPDATE,DELETE）："
                + "改掉一条记录等于抹掉重复派发的证据"),
        "UNKNOWN（结果未知）是独立状态，不是 FAILED 的子类：它的建议恒为「先查证交易」，"
            + "永远不会是「可重试」。把两者合并就是资损的起点。",
        "重试（refund_attempt.attempt_kind='RETRY'）在 DB 层被触发器 guard_refund_attempt_retry 挡住："
            + "处于 UNKNOWN 时必须携带一条 verdict=CONFIRMED_NOT_SETTLED 的查证记录；"
            + "verdict=CONFIRMED_SETTLED 时直接拒绝（钱已付出，再发就是重复退款）；"
            + "verdict=INCONCLUSIVE 时直接拒绝（还是不知道，不许重试）。"
            + "⇒ 「先查证交易再重试」不是调用方自觉，是绕过服务层也照样生效的规则。",
        "平台退款与系统退款不一致（金额 / 币种 / 时间 / 存在性）如实登记为差异清单，"
            + "两边原值都保留；检测是自动的，**裁定只能由人做**，系统不自动抹平。",
        "sales_order 实查没有 shop_code 列（V99），卷 2 的店铺在 channel 服务独立库、"
            + "按 AGENTS.md 禁止跨服务 SQL ⇒ 平台维度当前只到平台级 order_source。"
            + "**刻意不建恒为空的 shop 列**：空列会被读成「这家店没有退款」。");
  }

  // ================================================================ 登记（NOT_INITIATED）

  /**
   * 登记一笔退款执行。
   *
   * <p>⚠️ 这是 <b>11.3.1.1 退款申请未交付</b>期间的入口：它只做「把一笔退款登记成待发起状态」
   * 这件事，<b>不含</b>退款申请单审批、可退余额校验（那两件事依赖 11.3.1.1 与 12 财务）。
   * capabilities 的 refundRequestDelivered=false 如实报出这一点。
   *
   * <p><b>幂等</b>：同一 idempotencyKey 重复提交返回<b>同一笔</b>（含它的当前状态），
   * 而不是登记第二笔。
   */
  @Transactional
  public RefundExecutionRow register(RefundExecutionCreate request, UUID actor) {
    String key = required(request.idempotencyKey(), "幂等键");

    // ---- 幂等第一道：先查已有 ----
    var existing = findByIdempotencyKey(key);
    if (existing != null) {
      // 幂等重放：返回既有结果。刻意**不**校验其余字段是否一致 ——
      // 幂等键的语义是「这两次请求是同一次意图」，逐字段比对反而会让重放变成新的失败源。
      return existing;
    }

    // ---- 真查订单存在性（V99 只读，不加 FK ⇒ 存在性必须服务层查）----
    String orderNo =
        oneString(
            "select order_no from sales_order where tenant_id = ? and id = ?",
            currentTenant(),
            request.orderId());
    if (orderNo == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在或不属于当前租户");

    if (request.caseId() != null) {
      String found =
          oneString(
              "select case_no from aftersales_case where tenant_id = ? and id = ?",
              currentTenant(),
              request.caseId());
      if (found == null)
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "售后单不存在或不属于当前租户（11.3.1.1 未交付，本表只能引用已存在的售后单）");
    }

    BigDecimal amount = positive(request.refundAmount(), "退款金额");
    String currency = currency(request.currency());
    PlatformRefundMethod method =
        request.platformRefundMethod() == null
            ? PlatformRefundMethod.ORIGINAL
            : request.platformRefundMethod();

    UUID id = UUID.randomUUID();
    String refundNo = nextRefundNo();

    try {
      sql.execute(
          "insert into refund_execution(id,tenant_id,refund_no,order_id,case_id,idempotency_key,"
              + "refund_amount,currency,execution_state,failure_class,failure_code,failure_reason,"
              + "external_transaction_ref,platform_sync_state,platform_refund_no,platform_refund_method,"
              + "created_at,updated_at,initiated_at,last_verified_at,resolved_at)"
          + " values(?,?,?,?,?,?,?,?,'NOT_INITIATED',NULL,'','','',?,?,?,?::timestamptz,?::timestamptz,NULL,NULL,NULL)",
          id,
          currentTenant(),
          refundNo,
          request.orderId(),
          request.caseId(),
          key,
          amount,
          currency,
          // 平台同步状态由退款方式**决定**（V159 的 platform_sync_matches_method 等价式）：
          // 只有 PLATFORM 才谈得上同步。原路退回 / 退回余额一律 NOT_REQUESTED。
          method == PlatformRefundMethod.PLATFORM ? "PENDING" : "NOT_REQUESTED",
          "",
          method.name(),
          OffsetDateTime.now(),
          OffsetDateTime.now());
    } catch (org.jooq.exception.DataAccessException e) {
      // ---- 幂等第二道：并发下两个事务同时「查不到」，第二个撞 DB 唯一约束 ----
      if (isUniqueViolation(e)) {
        var again = findByIdempotencyKey(key);
        if (again != null) return again;
      }
      throw e;    }

    event(id, "REGISTERED", null, RefundExecutionState.NOT_INITIATED,
        "登记退款 " + refundNo + "（" + amount + " " + currency + "），尚未发起", actor);
    return loadRow(id);
  }

  // ================================================================ 派发 / 重试

  /**
   * 派发退款（首次或重试）。
   *
   * <p><b>本方法是资损防护的第二道</b>（第一道是 DB 唯一约束，第三道是 V159 的触发器）。
   * 它逐条实现「先查证交易再重试」，任何一条不满足都<b>明确拒绝</b>并说清原因：
   *
   * <ol>
   *   <li>状态是 IN_FLIGHT / SUCCEEDED / DISCREPANCY ⇒ 拒绝（重复支付）；
   *   <li>状态是 UNKNOWN 且 attemptKind=RETRY ⇒ <b>必须</b>带 verificationId，
   *       且该查证的结论必须是 CONFIRMED_NOT_SETTLED；
   *       CONFIRMED_SETTLED 时拒绝并指出正确动作是推进到 SUCCEEDED；
   *       INCONCLUSIVE 时拒绝并指出必须保持 UNKNOWN。
   *   <li>重试带一个属于<b>别的</b>退款的查证记录 ⇒ 拒绝。
   * </ol>
   *
   * <p>attemptKind 由服务端按状态<b>推导</b>（NOT_INITIATED ⇒ INITIAL，其余 ⇒ RETRY），
   * **不接受调用方指定** —— 否则调用方可以自称 INITIAL 来绕过查证要求。
   */
  @Transactional
  public RefundExecutionRow dispatch(
      UUID executionId, RefundDispatchRequest request, UUID actor) {

    String key = required(request.idempotencyKey(), "派发幂等键");

    // ---- 幂等第一道：同一派发键 ⇒ 返回同一笔，不发第二次 ----
    // ⚠️ fetchOne 在**无匹配行**时返回 null（不是空 Record）—— 直接 .get() 会 NPE。
    UUID existingAttempt =
        oneUuid("select execution_id from refund_attempt where tenant_id = ? and idempotency_key = ?",
            currentTenant(), key);
    if (existingAttempt != null) {
      if (!existingAttempt.equals(executionId))
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "该派发幂等键已被另一笔退款使用（id=" + existingAttempt + "）");
      return loadRow(executionId);
    }

    Record exec = loadExecution(executionId);
    RefundExecutionState state = RefundExecutionState.of(exec.get("execution_state", String.class));

    String attemptKind = state == RefundExecutionState.NOT_INITIATED ? "INITIAL" : "RETRY";

    if (attemptKind.equals("INITIAL") && request.verificationId() != null)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "首次派发不应携带查证记录：还没派发过，没有可查证的交易");

    // ---- 【闸二】先查证交易再重试（服务层这一道）----
    if (attemptKind.equals("RETRY")) {
      requireVerificationForRetry(executionId, state, request.verificationId());
    }

    OffsetDateTime dispatchedAt = request.dispatchedAt() == null ? OffsetDateTime.now() : request.dispatchedAt();
    int nextNo =
        sql.fetchOne(
                "select coalesce(max(attempt_no),0)+1 n from refund_attempt"
                    + " where tenant_id = ? and execution_id = ?",
                currentTenant(),
                executionId)
            .get("n", Integer.class);

    try {
      sql.execute(
          "insert into refund_attempt(id,tenant_id,execution_id,idempotency_key,attempt_kind,"
              + "attempt_no,verification_id,state_at_dispatch,dispatched_at,dispatch_channel,created_at)"
              + " values(?,?,?,?,?,?,?,?,?::timestamptz,?,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          executionId,
          key,
          attemptKind,
          nextNo,
          request.verificationId(),
          state.name(),
          dispatchedAt,
          channelOf(state, exec),
          OffsetDateTime.now());
    } catch (org.jooq.exception.DataAccessException e) {
      // ---- 幂等第二道：并发下撞 DB 唯一约束 ⇒ 回读既有行 ----
      if (isUniqueViolation(e)) {
        UUID again =
            oneUuid(
                "select execution_id from refund_attempt where tenant_id = ? and idempotency_key = ?",
                currentTenant(),
                key);
        if (again != null && again.equals(executionId)) return loadRow(executionId);
      }
      throw e;
    }

    // 首次派发只从 NOT_INITIATED 走；重试走 UNKNOWN|FAILED → IN_FLIGHT。
    // 状态机（allowedNext + V159 的 guard_refund_execution_transition）两侧同时把关。
    advance(executionId, state, RefundExecutionState.IN_FLIGHT, actor,
        attemptKind.equals("INITIAL") ? "首次派发（幂等键 " + key + "）"
            : "重试派发（第 " + nextNo + " 次，依据查证记录 " + request.verificationId() + "）",
        null, null, null, null);

    return loadRow(executionId);
  }

  /**
   * 【闸二】重试前的查证校验。服务层这一道给出<b>人话</b>错误，V159 的触发器给出 DB 层兜底。
   */
  private void requireVerificationForRetry(
      UUID executionId, RefundExecutionState state, UUID verificationId) {
    if (state == RefundExecutionState.IN_FLIGHT)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "退款仍在途（IN_FLIGHT），结果未到就重发会造成重复支付。依据蓝图：外部退款超时不等于失败，"
              + "重复发起退款会造成实质资损");
    if (state == RefundExecutionState.SUCCEEDED || state == RefundExecutionState.DISCREPANCY)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "退款已处于 " + state.label() + "，重试会造成重复退款（实质资损），已拒绝");

    if (verificationId == null)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "结果未知（UNKNOWN）的退款必须先查证交易才能重试。"
              + "当前建议动作是「先查证交易」，而不是直接重发 —— 超时不等于失败，"
              + "重复发起退款会造成实质资损");

    var v =
        sql.fetchOne(
                "select verdict, execution_id from refund_verification where tenant_id = ? and id = ?",
                currentTenant(),
                verificationId);
    if (v == null)
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "查证记录不存在或不属于当前租户：" + verificationId);

    UUID vExecution = v.get("execution_id", UUID.class);
    if (!vExecution.equals(executionId))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "查证记录不属于这笔退款（查证属于 " + vExecution + "）");

    String verdict = v.get("verdict", String.class);
    if (VerificationVerdict.CONFIRMED_SETTLED.name().equals(verdict))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "查证结论是 CONFIRMED_SETTLED：钱已经付出去了，重试就是对同一笔退款发起第二次支付。"
              + "正确动作是把执行推进到 SUCCEEDED（登记一条 SUCCEEDED 的外部回报）");
    if (VerificationVerdict.INCONCLUSIVE.name().equals(verdict))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "查证结论是 INCONCLUSIVE：仍然无法确认钱是否付出。执行必须保持 UNKNOWN，"
              + "直到查证出确定结论为止 —— 此时重发就是拿真实资损赌一个不确定");
  }

  // ================================================================ 查证交易

  /**
   * 登记一次查证交易。这是「先查证交易再重试」里的「查证」那一步。
   *
   * <p>查证本身<b>不</b>推进执行状态 —— 它只产生证据，推进由调用方拿证据去做
   * （登记回报 → SUCCEEDED，或重试 → IN_FLIGHT）。这样「有查证但没结论」与
   * 「已查证出结论」在数据上是两个可分辨的状态。
   */
  @Transactional
  public RefundVerificationRow verify(UUID executionId, RefundVerificationRequest request, UUID actor) {
    loadExecution(executionId);

    if (request.verifySource() == VerifySource.FINANCE_QUERY)
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "12 财务未交付（见 capabilities.financeDelivered=false），FINANCE_QUERY 当前不可用。"
              + "请改用 PLATFORM_QUERY（平台侧退款查询）或 MANUAL（人工查证）");

    String ref = required(request.transactionRef(), "被查证的交易号");
    OffsetDateTime at = request.verifiedAt() == null ? OffsetDateTime.now() : request.verifiedAt();

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into refund_verification(id,tenant_id,execution_id,verify_source,verdict,"
            + "transaction_ref,note,verified_at,verified_by,created_at)"
            + " values(?,?,?,?,?,?,?,?::timestamptz,?,?::timestamptz)",
        id,
        currentTenant(),
        executionId,
        request.verifySource().name(),
        request.verdict().name(),
        ref,
        text(request.note()),
        at,
        actor,
        OffsetDateTime.now());

    // last_verified_at 单独更新（V159 的列级白名单里有它）。
    sql.execute(
        "update refund_execution set last_verified_at = ?::timestamptz, updated_at = ?::timestamptz"
            + " where tenant_id = ? and id = ?",
        at,
        OffsetDateTime.now(),
        currentTenant(),
        executionId);

    event(executionId, "VERIFIED", null, null,
        "查证交易 " + ref + "：" + request.verdict().label()
            + (request.note() == null || request.note().isBlank() ? "" : "（" + request.note() + "）"),
        actor, "verification", id);
    return verificationRow(id);
  }

  // ================================================================ 外部执行结果回报

  /**
   * 登记一条外部执行结果回报（12 财务的执行结果 / 平台侧查询 / 人工登记）。
   *
   * <p><b>超时不等于失败</b>：{@code TIMEOUT} 与 {@code UNKNOWN} 两种结论
   * 只把执行推进到 {@link RefundExecutionState#UNKNOWN}，
   * <b>永不</b>推进到 FAILED（{@link ReportOutcome#toState()} 是唯一映射，
   * 且 {@link RefundExecutionState#UNKNOWN} 的建议恒为「先查证交易」）。
   *
   * <p>12 财务交付前，{@code reportSource=FINANCE_MODULE_12} 被<b>明确拒绝</b>
   * —— 收下一条来源写着「12 财务」而实际是人工填的记录，会让将来对接时无法审计。
   */
  @Transactional
  public RefundExecutionRow report(UUID executionId, RefundReportRequest request, UUID actor) {
    loadExecution(executionId);

    if (request.reportSource() == ReportSource.FINANCE_MODULE_12)
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "12 财务未交付（见 capabilities.financeDelivered=false），"
              + "reportSource=FINANCE_MODULE_12 当前不可写入。"
              + "请改用 PLATFORM_SYNC（平台侧退款查询）或 MANUAL_OPERATOR（人工登记）；"
              + "正式接口到位后本端点即可直接接收，无需改表结构");

    FailureClass failureClass = request.failureClass();
    // 结论与失败分类必须自洽（与 V159 的两条 CHECK 同一套语义）：
    // 超时 / 未知 ⇒ 只能是 INDETERMINATE；明确失败 ⇒ 只能是 DEFINITIVE。
    if (request.reportOutcome() == ReportOutcome.TIMEOUT
        || request.reportOutcome() == ReportOutcome.UNKNOWN) {
      requireIndeterminate(failureClass, "超时 / 结果未知");
    }
    if (request.reportOutcome() == ReportOutcome.FAILED) {
      if (failureClass != FailureClass.DEFINITIVE)
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "明确失败（FAILED）的失败分类必须是 DEFINITIVE（钱确定没有出去）。"
                + "拿不准时请报 TIMEOUT 或 UNKNOWN —— 超时不等于失败");
    }
    if (request.reportOutcome() == ReportOutcome.SUCCEEDED && failureClass != null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "成功的回报不允许带失败分类");

    if (request.reportOutcome() == ReportOutcome.SUCCEEDED
        && (request.reportedAmount() == null || request.reportedCurrency() == null))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "声称成功的回报必须带金额与币种：没有金额的成功无法与差异清单对账");

    if (request.reportOutcome() != ReportOutcome.SUCCEEDED
        && (blank(request.failureCode()) || blank(request.failureReason())))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "失败 / 超时 / 未知的回报必须给出失败码与人话原因");

    UUID reportId = UUID.randomUUID();
    sql.execute(
        "insert into refund_execution_report(id,tenant_id,execution_id,report_source,report_outcome,"
            + "failure_class,external_transaction_ref,reported_amount,reported_currency,"
            + "failure_code,failure_reason,reported_at,received_at,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz)",
        reportId,
        currentTenant(),
        executionId,
        request.reportSource().name(),
        request.reportOutcome().name(),
        failureClass == null ? null : failureClass.name(),
        text(request.externalTransactionRef()),
        request.reportedAmount(),
        request.reportedCurrency() == null ? null : currency(request.reportedCurrency()),
        text(request.failureCode()),
        text(request.failureReason()),
        request.reportedAt() == null ? OffsetDateTime.now() : request.reportedAt(),
        OffsetDateTime.now(),
        OffsetDateTime.now());

    RefundExecutionState current = RefundExecutionState.of(stateOf(executionId));
    RefundExecutionState target = request.reportOutcome().toState();

    // ⚠️ 三种结论各自**一条** UPDATE 走完（状态 + 失败字段 + 结论时刻同时落），
    //   不拆成「先写字段再改状态」—— V159 的两条 CHECK 是逐行判定的，
    //   拆开就会出现中间行直接违反（V159 文件头第一节记的同一个坑）。
    if (target == RefundExecutionState.SUCCEEDED) {
      advance(executionId, current, RefundExecutionState.SUCCEEDED, actor,
          "外部回报成功" + (blank(request.externalTransactionRef()) ? ""
              : "，交易号 " + request.externalTransactionRef()),
          null, null, null, text(request.externalTransactionRef()));
    } else if (target == RefundExecutionState.FAILED) {
      advance(executionId, current, RefundExecutionState.FAILED, actor,
          "外部明确失败：" + request.failureReason(),
          failureClass, request.failureCode(), request.failureReason(), null);
    } else {
      // 超时 / 结果未知：**只推进到 UNKNOWN，绝不进 FAILED**（蓝图加粗句的直接落点）。
      // ⚠️ UNKNOWN 不能从 NOT_INITIATED 直接进（V159 的 unknown_was_initiated CHECK：
      //   「结果未知」的前提是「已经发起过」）—— advance 会按状态机报 400，
      //   而不是静默把表改成一个自相矛盾的态。
      advance(executionId, current, RefundExecutionState.UNKNOWN, actor,
          request.reportOutcome().label() + "（结果未知，不是失败）：" + request.failureReason()
              + "。下一步必须先查证交易，确认未付出后才谈得上重试。",
          FailureClass.INDETERMINATE, request.failureCode(), request.failureReason(), null);
    }

    event(executionId, "REPORT_RECEIVED", current, null,
        "收到 " + request.reportSource().label() + " 的回报：" + request.reportOutcome().label(),
        actor, "report", reportId);

    return loadRow(executionId);
  }

  private void requireIndeterminate(FailureClass failureClass, String what) {
    if (failureClass != FailureClass.INDETERMINATE)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          what + "的失败分类必须是 INDETERMINATE（不确定）。"
              + "把不确定当成「明确失败」会让可重试的建议直接落到一笔可能已经付了钱的退款上 —— "
              + "那就是重复退款");
  }

  // ================================================================ 平台同步

  /**
   * 平台侧退款同步结果回报。**只更新平台同步这条轴**，不动执行状态 ——
   * 「平台还没同步」与「退款失败」是两件事（V156 同一纪律的另一次应用）。
   */
  @Transactional
  public RefundExecutionRow syncPlatform(UUID executionId, RefundPlatformSyncRequest request, UUID actor) {
    Record exec = loadExecution(executionId);
    String method = exec.get("platform_refund_method", String.class);
    if (!"PLATFORM".equals(method))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "本笔退款的方式是「" + method + "」，不经过平台，没有平台同步状态可更新。"
              + "只有 PLATFORM 方式的退款才走这条轴");

    PlatformSyncState target = request.platformSyncState();
    sql.execute(
        "update refund_execution set platform_sync_state = ?, platform_refund_no = ?,"
            + " updated_at = ?::timestamptz where tenant_id = ? and id = ?",
        target.name(),
        text(request.platformRefundNo()),
        OffsetDateTime.now(),
        currentTenant(),
        executionId);

    event(executionId, "PLATFORM_SYNCED", null, null,
        "平台同步状态：" + target.label() + (blank(request.platformRefundNo()) ? ""
            : "，平台退款单号 " + request.platformRefundNo())
            + (blank(request.note()) ? "" : "（" + request.note() + "）"),
        actor);
    return loadRow(executionId);
  }

  // ================================================================ 差异清单

  /**
   * 登记一条对账差异。<b>如实报，不自动抹平。</b>
   *
   * <p>按差异类型钉死该有的证据（与 V159 的 {@code refund_discrepancy_kind_has_evidence}
   * 同一套判定，服务层先给出人话错误，DB 层同时兜底）：
   * AMOUNT ⇒ 两边金额都有且不等；CURRENCY ⇒ 两边币种都有且不等；
   * TIMING ⇒ 两边时刻都有且不等；EXISTENCE ⇒ <b>恰好一边</b>有值。
   */
  @Transactional
  public RefundDiscrepancyRow detectDiscrepancy(UUID executionId, RefundDiscrepancyCreate request, UUID actor) {
    loadExecution(executionId);
    String detail = required(request.detail(), "差异说明");

    switch (request.discrepancyKind()) {
      case AMOUNT -> {
        if (request.systemAmount() == null || request.platformAmount() == null)
          throw badRequest("金额不一致必须同时登记系统金额与平台金额");
        if (request.systemAmount().compareTo(request.platformAmount()) == 0)
          throw badRequest("两边金额相等，不构成金额不一致（比较按数值，不受标度影响：10.00 与 10.0000 是同一个数）");
      }
      case CURRENCY -> {
        if (blank(request.systemCurrency()) || blank(request.platformCurrency()))
          throw badRequest("币种不一致必须同时登记系统币种与平台币种");
        if (currency(request.systemCurrency()).equals(currency(request.platformCurrency())))
          throw badRequest("两边币种相同，不构成币种不一致");
      }
      case TIMING -> {
        if (request.systemRefundedAt() == null || request.platformRefundedAt() == null)
          throw badRequest("时间不一致必须同时登记系统退款时刻与平台退款时刻");
        if (request.systemRefundedAt().isEqual(request.platformRefundedAt()))
          throw badRequest("两边退款时刻相同，不构成时间不一致");
      }
      case EXISTENCE -> {
        int present =
            (request.systemState() == null ? 0 : 1) + (request.platformState() == null ? 0 : 1);
        if (present != 1)
          throw badRequest(
              "存在性不一致要求**恰好一边**有状态（系统有平台没有，或平台有系统没有）；"
                  + "当前两边都有 " + present + " 个 —— 那是状态差异而不是存在性差异");
      }
    }

    UUID id = UUID.randomUUID();
    OffsetDateTime detectedAt =
        request.detectedAt() == null ? OffsetDateTime.now() : request.detectedAt();
    sql.execute(
        "insert into refund_discrepancy(id,tenant_id,execution_id,discrepancy_kind,"
            + "system_amount,system_currency,system_state,system_refunded_at,"
            + "platform_amount,platform_currency,platform_state,platform_refunded_at,"
            + "detail,resolution_state,resolution_note,resolved_at,resolved_by,detected_at,created_at)"
            + " values(?,?,?,?,?,?,?,?::timestamptz,?,?,?,?::timestamptz,?,'OPEN','',NULL,NULL,"
            + "?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        executionId,
        request.discrepancyKind().name(),
        request.systemAmount(),
        request.systemCurrency() == null ? null : currency(request.systemCurrency()),
        request.systemState() == null ? null : request.systemState().name(),
        request.systemRefundedAt(),
        request.platformAmount(),
        request.platformCurrency() == null ? null : currency(request.platformCurrency()),
        request.platformState() == null ? null : request.platformState().name(),
        request.platformRefundedAt(),
        detail,
        detectedAt,
        OffsetDateTime.now());

    // 检出差异后把执行推进到 DISCREPANCY（若还没在差异态）。
    RefundExecutionState current = RefundExecutionState.of(stateOf(executionId));
    if (current != RefundExecutionState.DISCREPANCY) {
      advance(executionId, current, RefundExecutionState.DISCREPANCY, actor,
          "检出对账差异：" + request.discrepancyKind().label() + "（" + detail + "）",
          null, null, null, null);
    }

    event(executionId, "DISCREPANCY_DETECTED", null, null,
        request.discrepancyKind().label() + "：" + detail, actor, "discrepancy", id);
    return discrepancyRow(id);
  }

  /**
   * 人工裁定差异。<b>只有人的动作能调用它</b> —— 系统不自动抹平。
   *
   * <p>RESOLVED 必须带裁定说明与时刻（V159 的 {@code refund_discrepancy_resolution_matches_time}）。
   */
  @Transactional
  public RefundDiscrepancyRow resolveDiscrepancy(
      UUID executionId, UUID discrepancyId, RefundDiscrepancyResolutionRequest request, UUID actor) {
    if (oneUuid(
            "select id from refund_discrepancy where tenant_id = ? and id = ? and execution_id = ?",
            currentTenant(),
            discrepancyId,
            executionId) == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "差异记录不存在或不属于当前租户 / 这笔退款");

    DiscrepancyResolution target = request.resolutionState();
    if (target == DiscrepancyResolution.RESOLVED && blank(request.resolutionNote()))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "裁定差异必须写明怎么处理的（裁定说明）—— 不写说明的裁定等于抹平");

    OffsetDateTime at = request.resolvedAt() == null ? OffsetDateTime.now() : request.resolvedAt();
    sql.execute(
        "update refund_discrepancy set resolution_state = ?, resolution_note = ?,"
            + " resolved_at = ?::timestamptz, resolved_by = ? where tenant_id = ? and id = ?",
        target.name(),
        text(request.resolutionNote()),
        target == DiscrepancyResolution.RESOLVED ? at : null,
        target == DiscrepancyResolution.RESOLVED ? actor : null,
        currentTenant(),
        discrepancyId);

    event(executionId, "DISCREPANCY_RESOLVED", null, null,
        "差异裁定：" + target.label()
            + (blank(request.resolutionNote()) ? "" : "（" + request.resolutionNote() + "）"),
        actor, "discrepancy", discrepancyId);
    return discrepancyRow(discrepancyId);
  }

  // ================================================================ 列表

  /**
   * 退款状态列表：真分页 + 多维筛选 + 与分页无关的全量汇总。
   *
   * <p><b>筛选、分页、聚合全部在数据库层带 tenant_id 与筛选条件</b>——
   * 「查全量再在 Java 里过滤」在多租户下就是跨租户泄露（A 家会看到 B 家正在退多少钱）。
   *
   * <p>金额区间是<b>闭区间</b>（{@code >= min and <= max}）：售后场景漏边界的代价是客诉。
   * 口径由 {@code amountRangeSemantics} 原样报出并被集成测试逐位断言。
   */
  public RefundExecutionPage list(RefundExecutionQuery q) {
    int page = Math.max(0, q.page() == null ? 0 : q.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, q.size() == null ? 20 : q.size()));

    String sort = SORTS.get(q.sort() == null ? "ATTENTION_FIRST" : q.sort());
    if (sort == null)
      throw badRequest("不支持的排序键：" + q.sort() + "，可用：" + String.join(" / ", SORTS.keySet()));

    List<Object> args = new ArrayList<>();
    String where = where(q, args);
    String attention = attentionFilter(q);

    // ⚠️ FROM 子句**每条查询都要带 join sales_order**：{@link #where} 里可能引用
    //   {@code s.order_no}（按订单号筛）。只给行查询加 join、汇总查询不加，
    //   会让「按订单号筛」在汇总那一半报「列不存在」—— 表现为筛选一加就 500，
    //   而不筛时一切正常。单一常量保证三处永远一致。
    String from =
        " from refund_execution e join sales_order s on s.tenant_id = e.tenant_id and s.id = e.order_id";
    // ⚠️ total **必须**带同一个 attention 过滤：否则「只看待处置」时 total 仍是全量，
    //   表现为「第一页 1 条、却说有 2 条」，翻页会拿到空页。
    long total = count("select count(*)" + from + where + attention, args);

    // 行：待处置计数与开放差异计数在 DB 层用子查询取（不取回来再数）。
    List<RefundExecutionRow> rows =
        sql.fetch(
                "select e.*, s.order_no, s.order_source,"
                    + " (select count(*) from refund_attempt a where a.tenant_id = e.tenant_id"
                    + "   and a.execution_id = e.id) as attempt_count,"
                    + " (select count(*) from refund_discrepancy d where d.tenant_id = e.tenant_id"
                    + "   and d.execution_id = e.id and d.resolution_state <> 'RESOLVED') as open_diff"
                    + from
                    + where
                    + attention
                    + " order by "
                    + sort
                    + " limit ? offset ?",
                // ⚠️ 参数顺序必须是 (size, offset)：SQL 里写的是 `limit ? offset ?`。
                //   写反成 (offset, size) 时**每一页都返回 0 行**（LIMIT 0），
                //   而 count(*) 走的是另一条 SQL 仍然正确 ——
                //   于是表现为「汇总有数、列表永远空」，非常难定位。
                concat(args, size, page * size))
            .map(this::row);

    // ---- 汇总：与分页无关（复用同一套 WHERE + 待处置过滤）----
    long needsAttention =
        count("select count(*)" + from + where + attention
            + " and e.execution_state in ('UNKNOWN','FAILED','DISCREPANCY')", args);
    long unknown = count("select count(*)" + from + where + attention + " and e.execution_state = 'UNKNOWN'", args);
    long inFlight = count("select count(*)" + from + where + attention + " and e.execution_state = 'IN_FLIGHT'", args);
    long failed = count("select count(*)" + from + where + attention + " and e.execution_state = 'FAILED'", args);
    long discrepancy = count("select count(*)" + from + where + attention + " and e.execution_state = 'DISCREPANCY'", args);

    List<RefundStateCountBucket> byState = new ArrayList<>();
    sql.fetch(
            "select e.execution_state, count(*) c" + from + where + attention
                + " group by e.execution_state order by e.execution_state",
            args.toArray())
        .forEach(r -> byState.add(new RefundStateCountBucket(
            r.get("execution_state", String.class), r.get("c", Long.class))));

    // 金额按币种分开：**跨币种相加是财务事故的标准起点**，绝不合并成一个总数。
    List<RefundAmountBucket> byCurrency = new ArrayList<>();
    sql.fetch(
            "select e.currency, sum(e.refund_amount) s" + from + where + attention
                + " group by e.currency order by e.currency",
            args.toArray())
        .forEach(r -> byCurrency.add(new RefundAmountBucket(
            r.get("currency", String.class), r.get("s", BigDecimal.class))));

    return new RefundExecutionPage(
        rows,
        page,
        size,
        total,
        needsAttention,
        unknown,
        inFlight,
        failed,
        discrepancy,
        byState,
        byCurrency,
        "闭区间：minAmount <= refund_amount <= maxAmount，两端都取",
        q.sort() == null ? "ATTENTION_FIRST" : q.sort());
  }

  private long count(String sqlText, List<Object> args) {
    return sql.fetchOne(sqlText, args.toArray()).get(0, Long.class);
  }

  /**
   * WHERE 子句。每一条都对应一个真实存在的列 / 索引。
   * 刻意<b>不</b>作为参数存在：{@code shopCode}（sales_order 没有该列，卷 2 店铺在 channel 独立库）
   * 与「平台退款金额筛选」（平台金额在差异清单里，不在主表）。
   */
  private String where(RefundExecutionQuery q, List<Object> args) {
    StringBuilder w = new StringBuilder(" where e.tenant_id = ?");
    args.add(currentTenant());

    if (notBlank(q.executionState())) {
      w.append(" and e.execution_state = ?");
      args.add(RefundExecutionState.of(q.executionState()).name());
    }
    if (notBlank(q.platformSyncState())) {
      w.append(" and e.platform_sync_state = ?");
      args.add(q.platformSyncState());
    }
    if (notBlank(q.platformRefundMethod())) {
      w.append(" and e.platform_refund_method = ?");
      args.add(q.platformRefundMethod());
    }
    if (notBlank(q.currency())) {
      w.append(" and e.currency = ?");
      args.add(currency(q.currency()));
    }
    if (q.orderId() != null) {
      w.append(" and e.order_id = ?");
      args.add(q.orderId());
    }
    if (q.caseId() != null) {
      w.append(" and e.case_id = ?");
      args.add(q.caseId());
    }
    if (notBlank(q.failureCode())) {
      w.append(" and e.failure_code = ?");
      args.add(q.failureCode().trim());
    }
    if (notBlank(q.minAmount())) {
      w.append(" and e.refund_amount >= ?");
      args.add(new BigDecimal(q.minAmount().trim()));
    }
    if (notBlank(q.maxAmount())) {
      w.append(" and e.refund_amount <= ?");
      args.add(new BigDecimal(q.maxAmount().trim()));
    }
    if (notBlank(q.refundNo())) {
      w.append(" and e.refund_no = ?");
      args.add(q.refundNo().trim());
    }
    if (notBlank(q.orderNo())) {
      // 订单号在 sales_order 上：join 已有（见 list 的 from 子句），直接用别名。
      w.append(" and s.order_no = ?");
      args.add(q.orderNo().trim());
    }
    return w.toString();
  }

  /**
   * 「只看待处置」的附加 WHERE 片段。
   *
   * <p>⚠️ 返回值**自带前导的 {@code and}**：它被直接拼在 {@link #where} 之后。
   * 早先这里返回 {@code "true"}，拼出来是 {@code where e.tenant_id = ?true} ——
   * 语法上勉强能过（{@code ?} 后紧跟标识符被解析成别名），于是查询静默返回 0 行，
   * 表现为「列表永远是空的」，而所有汇总与断言都看不出哪里错了。
   */
  private String attentionFilter(RefundExecutionQuery q) {
    return "true".equalsIgnoreCase(q.onlyNeedsAttention())
        ? " and e.execution_state in ('UNKNOWN','FAILED','DISCREPANCY')"
        : "";
  }

  // ================================================================ 差异清单分页

  /** 差异清单分页。**默认视图就是「未裁定的」** —— 蓝图输出句点名的就是待处理清单。 */
  public RefundDiscrepancyPage listDiscrepancies(
      String resolutionState, DiscrepancyKind kind, Integer page, Integer size) {
    int p = Math.max(0, page == null ? 0 : page);
    int s = Math.min(MAX_PAGE_SIZE, Math.max(1, size == null ? 20 : size));

    List<Object> args = new ArrayList<>();
    StringBuilder w = new StringBuilder(" where d.tenant_id = ?");
    args.add(currentTenant());
    if (notBlank(resolutionState)) {
      w.append(" and d.resolution_state = ?");
      args.add(resolutionState);
    } else {
      // 默认只看未裁定的 —— 运营每天要看的是「还有什么没处理」。
      w.append(" and d.resolution_state <> 'RESOLVED'");
    }
    if (kind != null) {
      w.append(" and d.discrepancy_kind = ?");
      args.add(kind.name());
    }

    long total =
        sql.fetchOne("select count(*) from refund_discrepancy d " + w, args.toArray()).get(0, Long.class);
    long open = count("select count(*) from refund_discrepancy d where d.tenant_id = ? and d.resolution_state = 'OPEN'", List.of(currentTenant()));
    long ack = count("select count(*) from refund_discrepancy d where d.tenant_id = ? and d.resolution_state = 'ACKNOWLEDGED'", List.of(currentTenant()));
    long resolved = count("select count(*) from refund_discrepancy d where d.tenant_id = ? and d.resolution_state = 'RESOLVED'", List.of(currentTenant()));

    List<RefundDiscrepancyRow> rows =
        sql.fetch(
                "select d.*, e.refund_no from refund_discrepancy d"
                    + " join refund_execution e on e.tenant_id = d.tenant_id and e.id = d.execution_id"
                    + w
                    + " order by d.detected_at desc, d.id limit ? offset ?",
                // 参数顺序 (size, offset)，与上面 list() 同一个坑（写反 ⇒ 每一页都空）。
                concat(args, s, p * s))
            .map(this::discrepancyRow);

    List<RefundKindCountBucket> byKind = new ArrayList<>();
    sql.fetch(
            "select discrepancy_kind, count(*) c from refund_discrepancy d " + w
                + " group by discrepancy_kind order by discrepancy_kind",
            args.toArray())
        .forEach(r -> byKind.add(new RefundKindCountBucket(
            r.get("discrepancy_kind", String.class), r.get("c", Long.class))));

    // 两边金额分别汇总：差异清单的价值就在「系统一共多了/少了多少、平台一共多了/少了多少」，
    // 合成一个净额会把方向抹掉（-100 与 +100 抵成 0，而真实情况是差了 200）。
    List<RefundKindAmountBucket> systemByKind = new ArrayList<>();
    List<RefundKindAmountBucket> platformByKind = new ArrayList<>();
    sql.fetch(
            "select discrepancy_kind,"
                + " sum(coalesce(system_amount,0)) sa, sum(coalesce(platform_amount,0)) pa"
                + " from refund_discrepancy d " + w + " group by discrepancy_kind order by discrepancy_kind",
            args.toArray())
        .forEach(
            r -> {
              String k = r.get("discrepancy_kind", String.class);
              systemByKind.add(new RefundKindAmountBucket(k, r.get("sa", BigDecimal.class)));
              platformByKind.add(new RefundKindAmountBucket(k, r.get("pa", BigDecimal.class)));
            });

    return new RefundDiscrepancyPage(
        rows, p, s, total, open, ack, resolved, byKind, systemByKind, platformByKind);
  }

  // ================================================================ 详情

  /** 详情：头 + 派发历史 + 查证历史 + 外部回报 + 差异 + 状态事件 + 当前建议。 */
  public RefundExecutionDetail detail(UUID id) {
    RefundExecutionRow header = loadRow(id);
    return new RefundExecutionDetail(
        header,
        sql.fetch(
                "select * from refund_attempt where tenant_id = ? and execution_id = ?"
                    + " order by attempt_no desc",
                currentTenant(),
                id)
            .map(this::attemptRow),
        sql.fetch(
                "select * from refund_verification where tenant_id = ? and execution_id = ?"
                    + " order by verified_at desc, id",
                currentTenant(),
                id)
            .map(this::verificationRow),
        sql.fetch(
                "select * from refund_execution_report where tenant_id = ? and execution_id = ?"
                    + " order by received_at desc, id",
                currentTenant(),
                id)
            .map(this::reportRow),
        sql.fetch(
                "select d.*, e.refund_no from refund_discrepancy d"
                    + " join refund_execution e on e.tenant_id = d.tenant_id and e.id = d.execution_id"
                    + " where d.tenant_id = ? and d.execution_id = ? order by d.detected_at desc, d.id",
                currentTenant(),
                id)
            .map(this::discrepancyRow),
        sql.fetch(
                "select * from refund_status_event where tenant_id = ? and execution_id = ?"
                    + " order by occurred_at, id",
                currentTenant(),
                id)
            .map(this::eventRow),
        RefundExecutionState.of(header.executionState()).allowedNext().stream()
            .map(Enum::name)
            .sorted()
            .toList(),
        RefundStatusModels.advise(RefundExecutionState.of(header.executionState())),
        "平台维度只到平台级（sales_order.order_no / order_source）：卷 2 的店铺在 channel 独立库，"
            + "按 AGENTS.md 禁止跨服务 SQL ⇒ 没有店铺维度。刻意不建恒为空的 shop 列。",
        "幂等两道：服务层先查 idempotency_key 命中即返回既有结果；DB 层 "
            + "UNIQUE(tenant_id, idempotency_key) 兜底并发。重复提交同一键**不会**发起第二次退款。");
  }

  // ================================================================ 筛选项

  /** 筛选项：**全部来自真实数据的 DISTINCT 查询**，不是硬编码常量列表。 */
  public RefundFilterOptions filterOptions() {
    List<String> states =
        sql.fetch("select distinct execution_state v from refund_execution where tenant_id = ? order by v",
                currentTenant())
            .map(r -> r.get("v", String.class));
    List<String> syncStates =
        sql.fetch("select distinct platform_sync_state v from refund_execution where tenant_id = ? order by v",
                currentTenant())
            .map(r -> r.get("v", String.class));
    List<String> methods =
        sql.fetch("select distinct platform_refund_method v from refund_execution where tenant_id = ? order by v",
                currentTenant())
            .map(r -> r.get("v", String.class));
    List<String> currencies =
        sql.fetch("select distinct currency v from refund_execution where tenant_id = ? order by v",
                currentTenant())
            .map(r -> r.get("v", String.class));
    List<String> failureCodes =
        sql.fetch(
                "select distinct failure_code v from refund_execution"
                    + " where tenant_id = ? and failure_code <> '' order by v",
                currentTenant())
            .map(r -> r.get("v", String.class));
    List<String> orderSources =
        sql.fetch(
                "select distinct s.order_source v from refund_execution e"
                    + " join sales_order s on s.tenant_id = e.tenant_id and s.id = e.order_id"
                    + " where e.tenant_id = ? order by v",
                currentTenant())
            .map(r -> r.get("v", String.class));
    long total =
        sql.fetchOne("select count(*) from refund_execution where tenant_id = ?", currentTenant())
            .get(0, Long.class);

    return new RefundFilterOptions(
        states, syncStates, methods, currencies, failureCodes, orderSources, total);
  }

  // ================================================================ 状态推进（唯一真身）

  /**
   * 推进状态。<b>唯一实现</b>：状态机（{@link RefundExecutionState#allowedNext()}）
   * 与 V159 的 {@code guard_refund_execution_transition} 触发器在此汇合 ——
   * Java 侧给出人话错误，DB 侧给出绕过应用时的兜底。
   *
   * <p>⚠️ <b>整条推进只发一条 UPDATE</b>，不拆成「先落 initiated_at / resolved_at / 失败字段，
   * 再改状态」几条。原因是 V159 的两条 CHECK 是<b>逐行</b>判定的：
   * {@code refund_execution_initiated_matches_state} 要求
   * {@code (state='NOT_INITIATED') = (initiated_at IS NULL)}，
   * 分两条语句写就会出现「initiated_at 落了、状态还没改」的中间行 —— 那一行直接违反 CHECK。
   * 同 {@code refund_execution_resolved_matches_state} 对 resolved_at 的要求。
   *
   * @param failureClass 目标状态要求的失败分类；{@code null} 表示由本方法按目标状态推导
   * @param failureCode 失败码；{@code null} 表示保持原值（仅 IN_FLIGHT 重试时用）
   * @param failureReason 失败原因人话；{@code null} 表示保持原值
   * @param externalRef 外部交易号；{@code null} 表示保持原值
   */
  private void advance(
      UUID executionId,
      RefundExecutionState from,
      RefundExecutionState to,
      UUID actor,
      String detail,
      FailureClass failureClass,
      String failureCode,
      String failureReason,
      String externalRef) {

    Set<RefundExecutionState> allowed = from.allowedNext();
    if (!allowed.contains(to)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "退款当前状态「" + from.name() + "」不能转到「" + to.name() + "」；合法目标："
              + (allowed.isEmpty() ? "无（终态）" : allowed.stream().map(Enum::name).sorted().toList()));
    }

    // 目标状态决定的失败字段取值：
    //   SUCCEEDED / DISCREPANCY → 清空（一张已成功的单上挂着「外部超时」会被读成「它其实超时过」）
    //   UNKNOWN                 → 强制 INDETERMINATE，并带上人话原因（它要解释「为什么是未知」）
    //   FAILED                  → 必须 DEFINITIVE（V159 的 CHECK 同步钉死）
    //   IN_FLIGHT               → 保持原值（重试派发不清掉上一次的超时记录）
    boolean clearFailure = to == RefundExecutionState.SUCCEEDED || to == RefundExecutionState.DISCREPANCY;
    String effectiveClass =
        clearFailure
            ? null
            : (to == RefundExecutionState.UNKNOWN
                ? FailureClass.INDETERMINATE.name()
                : (failureClass == null ? null : failureClass.name()));
    String effectiveCode = clearFailure ? "" : (failureCode == null ? null : failureCode.trim());
    String effectiveReason = clearFailure ? "" : (failureReason == null ? null : failureReason.trim());

    OffsetDateTime now = OffsetDateTime.now();
    sql.execute(
        "update refund_execution set execution_state = ?,"
            + " failure_class = ?, failure_code = coalesce(?, failure_code),"
            + " failure_reason = coalesce(?, failure_reason),"
            + " external_transaction_ref = coalesce(?, external_transaction_ref),"
            + " initiated_at = case when ? = 'NOT_INITIATED' then ?::timestamptz else initiated_at end,"
            + " resolved_at = case when ? in ('SUCCEEDED','FAILED','DISCREPANCY')"
            + "   then ?::timestamptz else null end,"
            + " updated_at = ?::timestamptz"
            + " where tenant_id = ? and id = ?",
        to.name(),
        effectiveClass,
        effectiveCode,
        effectiveReason,
        externalRef,
        from.name(),
        now,
        to.name(),
        now,
        now,
        currentTenant(),
        executionId);

    event(executionId, "DISPATCHED", from, to, detail, actor);
  }

  // ================================================================ 行映射

  private RefundExecutionRow row(Record r) {
    RefundExecutionState state = RefundExecutionState.of(r.get("execution_state", String.class));
    RefundRetryAdvice advice = RefundStatusModels.advise(state);
    return new RefundExecutionRow(
        r.get("id", UUID.class),
        r.get("refund_no", String.class),
        state.name(),
        state.label(),
        r.get("failure_class", String.class),
        r.get("failure_code", String.class),
        r.get("failure_reason", String.class),
        r.get("refund_amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("order_source", String.class),
        r.get("order_no", String.class),
        r.get("order_id", UUID.class),
        r.get("case_id", UUID.class),
        r.get("platform_refund_method", String.class),
        r.get("platform_sync_state", String.class),
        r.get("platform_refund_no", String.class),
        r.get("external_transaction_ref", String.class),
        advice.action().name(),
        advice.label(),
        advice.blocking(),
        advice.reason(),
        advice.requiredVerdict() == null ? null : advice.requiredVerdict().name(),
        state.needsAttention(),
        r.get("attempt_count", Integer.class),
        r.get("open_diff", Integer.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("initiated_at", OffsetDateTime.class),
        r.get("last_verified_at", OffsetDateTime.class),
        r.get("resolved_at", OffsetDateTime.class));
  }

  private RefundExecutionRow loadRow(UUID id) {
    Record r =
        sql.fetchOne(
                "select e.*, s.order_no, s.order_source,"
                    + " (select count(*) from refund_attempt a where a.tenant_id = e.tenant_id"
                    + "   and a.execution_id = e.id) as attempt_count,"
                    + " (select count(*) from refund_discrepancy d where d.tenant_id = e.tenant_id"
                    + "   and d.execution_id = e.id and d.resolution_state <> 'RESOLVED') as open_diff"
                    + " from refund_execution e join sales_order s"
                    + "   on s.tenant_id = e.tenant_id and s.id = e.order_id"
                    + " where e.tenant_id = ? and e.id = ?",
                currentTenant(),
                id);
    if (r == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "退款执行记录不存在或不属于当前租户");
    return row(r);
  }

  private RefundExecutionRow findByIdempotencyKey(String key) {
    Record r =
        sql.fetchOne(
                "select e.*, s.order_no, s.order_source,"
                    + " (select count(*) from refund_attempt a where a.tenant_id = e.tenant_id"
                    + "   and a.execution_id = e.id) as attempt_count,"
                    + " (select count(*) from refund_discrepancy d where d.tenant_id = e.tenant_id"
                    + "   and d.execution_id = e.id and d.resolution_state <> 'RESOLVED') as open_diff"
                    + " from refund_execution e join sales_order s"
                    + "   on s.tenant_id = e.tenant_id and s.id = e.order_id"
                    + " where e.tenant_id = ? and e.idempotency_key = ?",
                currentTenant(),
                key);
    return r == null ? null : row(r);
  }

  private Record loadExecution(UUID id) {
    Record r =
        sql.fetchOne(
            "select * from refund_execution where tenant_id = ? and id = ?", currentTenant(), id);
    if (r == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "退款执行记录不存在或不属于当前租户");
    return r;
  }

  private String stateOf(UUID id) {
    return loadExecution(id).get("execution_state", String.class);
  }

  private RefundAttemptRow attemptRow(Record r) {
    return new RefundAttemptRow(
        r.get("id", UUID.class),
        r.get("attempt_no", Integer.class).toString(),
        r.get("attempt_kind", String.class),
        r.get("idempotency_key", String.class),
        r.get("verification_id", UUID.class),
        r.get("state_at_dispatch", String.class),
        r.get("dispatch_channel", String.class),
        r.get("dispatched_at", OffsetDateTime.class));
  }

  private RefundVerificationRow verificationRow(Record r) {
    String verdict = r.get("verdict", String.class);
    return new RefundVerificationRow(
        r.get("id", UUID.class),
        r.get("verify_source", String.class),
        verdict,
        VerificationVerdict.valueOf(verdict).label(),
        r.get("transaction_ref", String.class),
        r.get("note", String.class),
        r.get("verified_at", OffsetDateTime.class));
  }

  private RefundVerificationRow verificationRow(UUID id) {
    return verificationRow(
        sql.fetchOne(
            "select * from refund_verification where tenant_id = ? and id = ?", currentTenant(), id));
  }

  private RefundReportRow reportRow(Record r) {
    return new RefundReportRow(
        r.get("id", UUID.class),
        r.get("report_source", String.class),
        r.get("report_outcome", String.class),
        r.get("failure_class", String.class),
        r.get("external_transaction_ref", String.class),
        r.get("reported_amount", BigDecimal.class),
        r.get("reported_currency", String.class),
        r.get("failure_code", String.class),
        r.get("failure_reason", String.class),
        r.get("reported_at", OffsetDateTime.class),
        r.get("received_at", OffsetDateTime.class));
  }

  private RefundDiscrepancyRow discrepancyRow(Record r) {
    String kind = r.get("discrepancy_kind", String.class);
    return new RefundDiscrepancyRow(
        r.get("id", UUID.class),
        r.get("execution_id", UUID.class),
        r.get("refund_no", String.class),
        kind,
        RefundStatusModels.DiscrepancyKind.valueOf(kind).label(),
        r.get("system_amount", BigDecimal.class),
        r.get("system_currency", String.class),
        r.get("system_state", String.class),
        r.get("system_refunded_at", OffsetDateTime.class),
        r.get("platform_amount", BigDecimal.class),
        r.get("platform_currency", String.class),
        r.get("platform_state", String.class),
        r.get("platform_refunded_at", OffsetDateTime.class),
        r.get("detail", String.class),
        r.get("resolution_state", String.class),
        r.get("resolution_note", String.class),
        r.get("resolved_at", OffsetDateTime.class),
        r.get("resolved_by", UUID.class),
        r.get("detected_at", OffsetDateTime.class));
  }

  private RefundDiscrepancyRow discrepancyRow(UUID id) {
    return discrepancyRow(
        sql.fetchOne(
            "select d.*, e.refund_no from refund_discrepancy d"
                + " join refund_execution e on e.tenant_id = d.tenant_id and e.id = d.execution_id"
                + " where d.tenant_id = ? and d.id = ?",
            currentTenant(),
            id));
  }

  private RefundStatusEventRow eventRow(Record r) {
    return new RefundStatusEventRow(
        r.get("id", UUID.class),
        r.get("event_type", String.class),
        r.get("from_state", String.class),
        r.get("to_state", String.class),
        r.get("detail", String.class),
        r.get("verification_id", UUID.class),
        r.get("report_id", UUID.class),
        r.get("discrepancy_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  // ================================================================ 事件流

  private void event(UUID executionId, String type, RefundExecutionState from,
      RefundExecutionState to, String detail, UUID actor) {
    event(executionId, type, from, to, detail, actor, null, null);
  }

  private void event(UUID executionId, String type, RefundExecutionState from,
      RefundExecutionState to, String detail, UUID actor, String linkKind, UUID linkId) {
    // ⚠️ {@code to_state} 永远是**这条事件发生后的真实状态**，
    //   而不是「猜一个看起来合理的默认值」—— 事件流的价值在于它记的是真发生过的转换。
    //   {@code to == null}（查证 / 收到报告 / 平台同步 / 差异检出这类不改变状态的事件）
    //   ⇒ 直接回读当前状态，保证 to_state 永远为真。
    String effectiveTo = to != null ? to.name() : stateOf(executionId);
    sql.execute(
        "insert into refund_status_event(id,tenant_id,execution_id,event_type,from_state,to_state,"
            + "detail,verification_id,report_id,discrepancy_id,actor_membership_id,occurred_at,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        executionId,
        type,
        from == null ? null : from.name(),
        effectiveTo,
        text(detail),
        "verification".equals(linkKind) ? linkId : null,
        "report".equals(linkKind) ? linkId : null,
        "discrepancy".equals(linkKind) ? linkId : null,
        actor,
        OffsetDateTime.now(),
        OffsetDateTime.now());
  }

  // ================================================================ 工具

  /**
   * 单列 UUID 查询。<b>无匹配行返回 null</b>。
   *
   * <p>⚠️ jOOQ 的 {@code fetchOne(sql, args)} 在没有匹配行时返回 <b>null</b>（不是空 Record），
   * 直接链式 {@code .get(0, X.class)} 会 NPE —— 而 NPE 的栈里看不出「是这行没查到」，
   * 排查成本极高。存在性校验一律走这两个 null-safe 助手。
   */
  private UUID oneUuid(String sqlText, Object... args) {
    Record r = sql.fetchOne(sqlText, args);
    return r == null ? null : r.get(0, UUID.class);
  }

  /** 同 {@link #oneUuid}，取单列字符串。 */
  private String oneString(String sqlText, Object... args) {
    Record r = sql.fetchOne(sqlText, args);
    return r == null ? null : r.get(0, String.class);
  }

  /** 单号：RF-日期-六位序列。序列全局，两个租户号段不重叠是额外好处，不是要求。 */
  private String nextRefundNo() {
    String date = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().replace("-", "");
    long n = sql.fetchOne("select nextval('refund_execution_no_seq') as n").get("n", Long.class);
    return "RF-" + date + "-" + String.format("%06d", n);
  }

  private UUID currentTenant() {
    String value =
        sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'') v")
            .get("v", String.class);
    if (value == null)
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "租户上下文缺失，拒绝读写退款数据");
    return UUID.fromString(value);
  }

  /** 派发通道由执行状态推导：结果未知 / 明确失败 ⇒ 走财务重试；其余按退款方式。 */
  private String channelOf(RefundExecutionState state, Record exec) {
    if (state == RefundExecutionState.UNKNOWN || state == RefundExecutionState.FAILED) {
      String method = exec.get("platform_refund_method", String.class);
      return switch (method) {
        case "BALANCE" -> "BALANCE";
        case "PLATFORM" -> "PLATFORM";
        default -> "FINANCE";
      };
    }
    String method = exec.get("platform_refund_method", String.class);
    return switch (method) {
      case "BALANCE" -> "BALANCE";
      case "PLATFORM" -> "PLATFORM";
      default -> "FINANCE";
    };
  }

  private static boolean isUniqueViolation(org.jooq.exception.DataAccessException e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof java.sql.SQLException sql
          && "23505".equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  private static Object[] concat(List<Object> base, Object... more) {
    List<Object> all = new ArrayList<>(base);
    all.addAll(Arrays.asList(more));
    return all.toArray();
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "不能为空");
    return value.trim();
  }

  private static BigDecimal positive(BigDecimal value, String field) {
    if (value == null || value.signum() <= 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "必须大于 0");
    return value;
  }

  private static String currency(String value) {
    String v = required(value, "币种").toUpperCase(java.util.Locale.ROOT);
    if (!v.matches("^[A-Z]{3}$"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是 ISO 4217 alpha-3 三位大写：" + value);
    return v;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
