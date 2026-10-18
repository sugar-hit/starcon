package com.starcon.trade;

import static com.starcon.trade.AftersalesCaseModels.AftersalesCaseCreate;
import static com.starcon.trade.AftersalesIntakeModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 11.1.1.3 售后受理。
 *
 * <p>蓝图 11-aftersales-and-returns.md 第 86-90 行原文：
 *
 * <blockquote>
 * 职责：受理平台或内部发起的售后请求：判定是否在售后期内、是否符合条件、指定责任人与处理方案、设置回复时限。
 * 输入：售后申请（平台消息/买家消息/内部录入）；售后政策配置（期限、条件、必退情形）。
 * 输出：售后单（待处理）；政策判定结果；责任人分配。
 * 关系：上游 = 15 客户与客服、2 平台消息；下游 = 11.2/11.3。
 * <b>必退情形（平台强制、收到即退）要自动判定</b>，人工不能为了业绩拖着不退导致平台判责。
 * </blockquote>
 *
 * <p><b>写入边界（AGENTS.md「每张事实表只有一个写入所有者」）</b>：
 * {@code aftersales_case} 仍是 <b>11.1.1.1 {@link AftersalesCaseService} 的</b>写入所有者，
 * 本类<b>不</b>自己 INSERT 它，也<b>不</b> ALTER 它（V156 已把它的 UPDATE 收紧到列级白名单）。
 * 本类在受理时<b>委托</b> {@link AftersalesCaseService#create} 建单 ——
 * 11.1.1.1 那个方法的注释已写明「11.1.1.3 交付后应当接管本方法」，
 * 接管的是<b>受理判定</b>，不是把 INSERT 语句抄第二份。
 * 本类自己只写 {@code aftersales_intake_verdict}（V164，append-only 判定凭据）。
 *
 * <p><b>四项判定的真身各在哪</b>（每一项都不是硬编码常量）：
 *
 * <ol>
 *   <li><b>售后期</b>：锚点读 {@code sales_order}（V99，只读，9.1.1.1 车道的表），
 *       天数读 17.5.1.1 的 {@code AFTERSALE_RETURN_WINDOW_DAYS}（经
 *       {@link AftersalesPolicyQueryPort} 跨服务读 identity，不落影子副本）。
 *   <li><b>条件 / 必退</b>：词表读 17.5.1.1 的 {@code AFTERSALE_MUST_REFUND_REASONS}（同上）。
 *       判定优先级固定为 <b>平台声明 &gt; 原因分类映射 &gt; 不命中</b>，见 {@link #decideMustRefund}。
 *   <li><b>处理方案</b>：必退时强制为 {@link AftersalesHandlingPlan#REFUND_ONLY} 起步
 *       （金额与件数由下游 11.3 按实际结算），非必退时由受理人显式给出。
 *   <li><b>回复时限</b>：平台给的时限优先，否则用本服务的 {@code aftersales_reply_sla}
 *       （V164，租户可维护）。<b>17.5.1.1 里没有回复时限参数</b>（实测确认），
 *       所以它刻意不假装是「政策参数」，来源在响应里如实报出。
 * </ol>
 *
 * <p><b>三条硬纪律</b>：
 *
 * <ol>
 *   <li><b>必退不可在本地降级。</b>平台声明的必退（{@code PLATFORM_DECLARED}）一旦写下，
 *       重新判定时 V164 的 {@code guard_aftersales_verdict_must_refund} 触发器会拒绝把它改成非必退。
 *       加粗句那句「不能为了业绩拖着不退」是 DB 层的不变量，不是服务层的君子协定。
 *   <li><b>判定凭据 append-only。</b>重新判定 = 追加一行 + 把上一行标记 {@code superseded_at}，
 *       冻结的参数原值一起留档 —— 改了必退词表之后，三个月前那张单仍要能读回
 *       「当初按哪份词表判的必退」。
 *   <li><b>所有查询带 tenant_id。</b>RLS 在数据库层强制，但 SQL 里仍然显式带租户谓词：
 *       判定凭据里冻结着政策参数原文与必退结论，漏掉就是跨租户泄露。
 * </ol>
 */
@Service
public class AftersalesIntakeService {

  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final AftersalesCaseService cases;
  private final AftersalesPolicyQueryPort policy;

  public AftersalesIntakeService(
      DSLContext sql, AftersalesCaseService cases, AftersalesPolicyQueryPort policy) {
    this.sql = sql;
    this.cases = cases;
    this.policy = policy;
  }

  // ================================================================ 受理

  /**
   * 受理一张售后请求：判四项、建单、派责任人，落一份判定凭据。
   *
   * <p><b>全程一个事务</b>（{@code AccessService.run} 绑定）：建单、判定凭据、责任人分配
   * 要么全成要么全不成 —— 只建成单却没判定凭据，会留下一张「受理过但说不出按什么判的」的单。
   *
   * @param actorId 已认证的受理人（走已认证身份，不信任请求体里的任何用户标识）
   * @param bearer 调用方的 Authorization，透传给身份服务读业务参数（跨服务读不透传租户号）
   */
  public AftersalesIntakeResult intake(AftersalesIntakeRequest request, UUID actorId, String bearer) {
    if (request == null) {
      throw new IllegalArgumentException("受理请求不能为空");
    }
    if (request.orderId() == null) {
      throw new IllegalArgumentException("必须关联一张订单：售后期以它的下单时刻为锚点");
    }
    AftersalesIntakeSource source = parseEnum(AftersalesIntakeSource.class, request.intakeSource(), "受理来源");
    AftersalesIntakeChannel channel =
        parseEnum(
            AftersalesIntakeChannel.class,
            request.intakeChannel() == null || request.intakeChannel().isBlank()
                // 内部录入默认走内部登记；平台/买家消息不填渠道**不给默认值**，
                // 因为「一条平台消息是经哪个平台来的」问错了就是错的，猜一个会污染受理来源。
                ? (source == AftersalesIntakeSource.INTERNAL_ENTRY
                    ? "INTERNAL_NOTE"
                    : null)
                : request.intakeChannel(),
            "受理渠道");

    // ---- 政策只读一次（两条参数同源，见端口注释）----
    AftersalesPolicyQueryPort.PolicySnapshot snapshot = policy.snapshot(bearer);

    // ---- 订单锚点（V99 sales_order，同库只读）----
    Record order =
        sql.fetchOne(
            "select o.id, o.order_no, o.order_source, o.submitted_at, o.created_at"
                + " from sales_order o"
                + " where o.tenant_id = ?::uuid and o.id = ?::uuid",
            currentTenant(),
            request.orderId());
    if (order == null) {
      throw new IllegalArgumentException("关联订单不存在或不属于当前租户：" + request.orderId());
    }
    OffsetDateTime submittedAt = order.get(3, OffsetDateTime.class);
    OffsetDateTime createdAt = order.get(4, OffsetDateTime.class);
    // ⚠️ 锚点用了哪一列**如实记进判定凭据**：DRAFT 手工单没有 submitted_at，
    //   拿 created_at 当「下单时刻」会让窗口短算一截，而这件事必须让人看得见。
    boolean useSubmitted = submittedAt != null;
    AftersalesOrderAnchorKind anchorKind =
        useSubmitted ? AftersalesOrderAnchorKind.SUBMITTED_AT : AftersalesOrderAnchorKind.CREATED_AT;
    OffsetDateTime anchorAt = useSubmitted ? submittedAt : createdAt;

    // 请求到达时刻：时效计时起点。平台消息用平台受理时刻，内部补录用调用方给的时刻，
    // 都没给才退回墙上时钟（补录晚到不该多算时限，所以调用方给了就用调用方的）。
    OffsetDateTime receivedAt =
        firstNonNull(
            request.platformReceivedAt(),
            request.receivedAt(),
            OffsetDateTime.now(ZoneOffset.UTC));

    // ---- 判定一：是否在售后期内 ----
    OffsetDateTime windowDeadline = anchorAt.plusDays(snapshot.returnWindowDays());
    boolean inWindow = !receivedAt.isAfter(windowDeadline);
    // 余量小时数：**窗口内为正（还剩多久），超窗为负（超了多久）**。
    // 口径与「剩余秒数」一致 —— 一个正数永远表示「还有余量」，
    // 读的人不必先判断符号方向才知道是好消息还是坏消息。
    long marginHours = (windowDeadline.toEpochSecond() - receivedAt.toEpochSecond()) / 3600L;

    // ---- 判定二：是否符合条件（必退情形自动判定）----
    String reasonCode = reasonOf(request);
    MustRefundDecision mustRefund =
        decideMustRefund(
            reasonCode, Boolean.TRUE.equals(request.platformMustRefund()), source, snapshot);

    // ---- 判定四：回复时限（要在建单之前算出来，create() 要求至少给一个时限）----
    ReplyDue replyDue =
        resolveReplyDue(request, snapshot, order.get(2, String.class), receivedAt);

    // ---- 判定三：处理方案 ----
    AftersalesHandlingPlan plan = resolvePlan(request, mustRefund);
    // 必退单不许判成「不受理」：平台强制必退没有「不办」这个选项。
    // （DB 上还有一条 CHECK 兜底，这里先给出人话错误而不是让约束异常冒到 500。）
    if (mustRefund.mustRefund() && plan == AftersalesHandlingPlan.REJECT) {
      throw new IllegalArgumentException(
          "该申请命中必退情形（"
              + mustRefund.situation()
              + "），处理方案不能是「不受理」：平台强制、收到即退");
    }

    AftersalesEligibility eligibility =
        mustRefund.mustRefund()
            ? AftersalesEligibility.ACCEPTED_MUST_REFUND
            : inWindow
                ? AftersalesEligibility.ACCEPTED
                : (plan == AftersalesHandlingPlan.REJECT
                    ? AftersalesEligibility.REJECTED_OUT_OF_WINDOW
                    : AftersalesEligibility.OUT_OF_WINDOW_PENDING);

    // ---- 责任人分配（在建单之前定，建单时一并落到 11.1.1.1 的责任人留痕表）----
    RosterPick pick = request.ownerRef() != null && !request.ownerRef().isBlank()
        ? RosterPick.specified(request.ownerRef().trim())
        : pickFromRoster();

    // ---- 建单：委托既有写入所有者，本类不碰 aftersales_case ----
    AftersalesCaseModels.AftersalesCaseRow row =
        cases.create(
            new AftersalesCaseCreate(
                request.orderId(),
                request.caseType(),
                request.reasonCode(),
                request.reasonNote(),
                request.requestedAmount(),
                request.currency(),
                request.requestedQuantity(),
                blankToEmpty(request.intakeReference()),
                "",
                request.platformReceivedAt(),
                request.buyerRequest(),
                replyDue.replyDueAt(),
                request.resolveDueAt(),
                receivedAt,
                pick.ownerRef(),
                pick.displayName(),
                pick.roleLabel(),
                pick.ownerSource() == null ? "TENANT_MAINTAINED" : pick.ownerSource(),
                pick.sourceReference(),
                receivedAt),
            actorId);

    // ---- 判定凭据：append-only，本模块唯一的写入 ----
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    UUID verdictId = UUID.randomUUID();
    sql.execute(
        "insert into aftersales_intake_verdict(id,tenant_id,case_id,intake_source,intake_reference,"
            + " intake_channel,request_received_at,judged_at,judged_by,order_anchor_kind,order_anchor_at,"
            + " policy_return_window_days,return_window_deadline_at,in_return_window,window_margin_hours,"
            + " policy_must_refund_reasons,must_refund,must_refund_basis,must_refund_situation,eligibility,"
            + " policy_source_scope,policy_parameter_codes,policy_read_at,handling_plan,handling_plan_source,"
            + " reply_due_at,reply_due_basis,reply_sla_hours,resolve_due_at,resolve_sla_hours,"
            + " owner_assignment_mode,created_at)"
            + " values (?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?::timestamptz,?,?::timestamptz,?,?,"
            + " ?::text[],?,?,?,?,?,?::text[],?::timestamptz,?,?,?::timestamptz,?,?,?::timestamptz,?,?,now())",
        verdictId,
        currentTenant(),
        row.id(),
        source.name(),
        blankToEmpty(request.intakeReference()),
        channel.name(),
        receivedAt,
        now,
        actorId,
        anchorKind.name(),
        anchorAt,
        snapshot.returnWindowDays(),
        windowDeadline,
        inWindow,
        marginHours,
        // ⚠️ **必须带 ::text[]**：jOOQ 把 List<String> 按 varchar 数组绑，
        //   不加 cast 时 PG 报「column policy_must_refund_reasons is of type text[] but
        //   expression is of type character varying[]」。
        mustRefund.words().toArray(new String[0]),
        mustRefund.mustRefund(),
        mustRefund.basis().name(),
        mustRefund.situation(),
        eligibility.name(),
        snapshot.windowScope(),
        new String[] {
          AftersalesPolicyQueryPort.PARAM_RETURN_WINDOW_DAYS,
          AftersalesPolicyQueryPort.PARAM_MUST_REFUND_REASONS
        },
        now,
        plan.name(),
        mustRefund.mustRefund()
            ? "MUST_REFUND_FORCED"
            : "OPERATOR",
        replyDue.replyDueAt(),
        replyDue.basis().name(),
        replyDue.slaHours(),
        request.resolveDueAt(),
        null,
        pick.mode().name());

    return new AftersalesIntakeResult(
        loadVerdict(verdictId, row.caseNo()),
        row,
        new AftersalesOwnerAssignmentView(
            null,
            pick.ownerRef(),
            pick.displayName(),
            pick.roleLabel(),
            pick.ownerSource() == null ? "TENANT_MAINTAINED" : pick.ownerSource(),
            pick.mode().name(),
            pick.mode() == AftersalesOwnerAssignMode.ROSTER_ROUND_ROBIN
                ? "按名册加权最少在办单轮转派单（权重越大分到越多）"
                : pick.mode() == AftersalesOwnerAssignMode.OPERATOR_SPECIFIED
                    ? "受理人在受理请求里指定的责任人"
                    : "名册为空且受理人未指定：这张单先落在待分配池，由列表页人工认领"),
        "期限天数与必退词表读自 17.5.1.1 业务参数（identity 服务，档位 "
            + snapshot.windowScope()
            + " / "
            + snapshot.reasonsScope()
            + "），已随本次判定冻结；回复时限来源为"
            + replyDue.basis().label());
  }

  /**
   * 必退情形判定 —— 蓝图加粗句「必退情形（平台强制、收到即退）要自动判定」的落点。
   *
   * <p><b>三档优先级，顺序不可调换</b>：
   *
   * <ol>
   *   <li><b>平台声明</b>（{@code PLATFORM_DECLARED}）：请求里带
   *       {@code platformMustRefund=true} <b>且</b>受理来源是平台消息。
   *       第二半句是硬闸：受理人自己在内部单上勾「平台说必退」不构成平台声明 ——
   *       那样任何人都能给自己开一张「不许拒绝」的免死金牌。
   *   <li><b>原因分类映射</b>（{@code REASON_MAPPED}）：{@code reason_code} 映射到
   *       必退情形码，命中当前生效词表即为必退。映射表见 {@link #MUST_REFUND_SITUATION}。
   *   <li><b>不命中</b>（{@code NONE}）：走普通受理。
   * </ol>
   *
   * <p><b>命中词表为空是有意义的结论</b>：此时 {@code basis=NONE}，
   * 而 {@code MUST_REFUND_FORCED} 不会出现 —— 「没配必退情形」不能被读成「什么都可以退」。
   */
  private MustRefundDecision decideMustRefund(
      String reasonCode,
      boolean platformMustRefundFlag,
      AftersalesIntakeSource source,
      AftersalesPolicyQueryPort.PolicySnapshot snapshot) {
    List<String> words = snapshot.mustRefundReasons();
    boolean platformDeclared =
        platformMustRefundFlag && source == AftersalesIntakeSource.PLATFORM_MESSAGE;
    if (platformDeclared) {
      // 情形码记成 PLATFORM_DECLARED 本身：平台声明没有对应的原因分类码，
      // 编一个 QUALITY_DEFECT 之类的会让「为什么必退」答错。
      return new MustRefundDecision(
          true, AftersalesMustRefundBasis.PLATFORM_DECLARED, "PLATFORM_DECLARED", words);
    }
    if (platformMustRefundFlag && source != AftersalesIntakeSource.PLATFORM_MESSAGE) {
      throw new IllegalArgumentException(
          "平台强制必退只能由平台消息声明（当前受理来源是 "
              + source.label()
              + "）：受理人在内部单上勾这一项不构成平台声明，否则任何人都能给自己开免死金牌");
    }
    String situation = MUST_REFUND_SITUATION.get(reasonCode);
    if (situation != null && words.contains(situation)) {
      return new MustRefundDecision(
          true, AftersalesMustRefundBasis.REASON_MAPPED, situation, words);
    }
    return new MustRefundDecision(false, AftersalesMustRefundBasis.NONE, "", words);
  }

  /**
   * 原因分类 → 必退情形码的映射。
   *
   * <p>⚠️ 这是**七类原因分类**与**六种必退情形**之间唯一的桥，所以它显式写成一张表而不是散在 if 里：
   * 少一条映射就等于那一类原因永远拿不到必退判定，而这种遗漏在报表上看不出来。
   *
   * <p>依据：{@code AFTERSALE_MUST_REFUND_REASONS} 的六个合法取值
   * （identity V48 码表）逐个对上本服务七类原因分类（V156 / 11.1.2.1 原文）：
   *
   * <ul>
   *   <li>{@code QUALITY_DEFECT}（质量问题）→ {@code QUALITY_DEFECT}：质量问题平台强制退。
   *   <li>{@code DESCRIPTION_MISMATCH}（描述不符）→ {@code NOT_AS_DESCRIBED}：与描述不符。
   *   <li>{@code LOGISTICS_DAMAGE}（物流损坏）→ {@code DAMAGED_ON_ARRIVAL}：
   *       运输致损由卖方承担，对应「到手即损」。
   *   <li>{@code SIZE_MISMATCH} / {@code BUYER_REASON} / {@code MISTAKE_BUY} → **不映射**：
   *       尺码不符 / 买家原因 / 误购都不是平台强制必退情形，是否退由平台政策与商家裁量决定。
   *   <li>{@code FRAUD_RETURN}（欺诈退货）→ **刻意不映射**：欺诈恰恰是**不**能退的一类，
   *       把它映成必退会让系统对着一张欺诈单自动同意退款。
   * </ul>
   */
  private static final Map<String, String> MUST_REFUND_SITUATION =
      Map.of(
          "QUALITY_DEFECT", "QUALITY_DEFECT",
          "DESCRIPTION_MISMATCH", "NOT_AS_DESCRIBED",
          "LOGISTICS_DAMAGE", "DAMAGED_ON_ARRIVAL");

  /** 原因分类未出现在 {@link #MUST_REFUND_SITUATION} 里即为「无对应必退情形」：不在配置时不退。 */
  private static String reasonOf(AftersalesIntakeRequest request) {
    String raw = request.reasonCode();
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException(
          "原因分类不能为空：售后期与必退判定都要用它，蓝图 11.1.2.1 明确"
              + "「必须强制选择分类而不是留自由文本」");
    }
    String code = raw.trim().toUpperCase();
    List<String> all =
        Arrays.asList(
            "QUALITY_DEFECT",
            "DESCRIPTION_MISMATCH",
            "SIZE_MISMATCH",
            "LOGISTICS_DAMAGE",
            "BUYER_REASON",
            "MISTAKE_BUY",
            "FRAUD_RETURN");
    if (!all.contains(code)) {
      throw new IllegalArgumentException(
          "原因分类只能是 " + all + " 之一：收到 " + raw + "（七类与 11.1.2.1 原文逐字对齐）");
    }
    return code;
  }

  /**
   * 回复时限解析：平台给的 > 租户 SLA 推算的。**两档都取不到就失败**。
   *
   * <p>⚠️ 刻意<b>不</b>提供第三档兜底（哪怕是「默认 48 小时」）：
   *   回复时限是平台判责的计时基准，替租户编一个数字会让
   *   {@code capabilities.replyDeadlineNote} 变成假话 —— 界面说「按租户策略推算」，
   *   实际用的是代码里的常量，两处都会对不上。
   *   失败时的话术直接告诉运营去哪儿配。
   */
  private ReplyDue resolveReplyDue(
      AftersalesIntakeRequest request,
      AftersalesPolicyQueryPort.PolicySnapshot snapshot,
      String platformSource,
      OffsetDateTime receivedAt) {
    if (request.replyDueAt() != null) {
      return new ReplyDue(
          request.replyDueAt(), AftersalesReplyDueBasis.PLATFORM_GIVEN, (Integer) null);
    }
    Record sla =
        sql.fetchOne(
            "select s.reply_sla_hours from aftersales_reply_sla s"
                + " where s.tenant_id = ?::uuid and s.platform_scope in (?,'')"
                + " and s.effective_from <= ?::timestamptz"
                + " and (s.effective_to is null or s.effective_to > ?::timestamptz)"
                + " order by case when s.platform_scope = ? then 0 else 1 end,"
                + "          s.effective_from desc"
                + " limit 1",
            currentTenant(),
            platformSource,
            receivedAt,
            receivedAt,
            platformSource);
    if (sla == null) {
      throw new IllegalArgumentException(
          "既没有平台给的回复时限，也没有配置租户回复时限策略（aftersales_reply_sla，"
              + "受理时限是平台判责的计时基准，本服务不会替租户编一个默认小时数）："
              + "请先在受理策略里配一档 SLA，或在本次受理里带上平台给的 replyDueAt");
    }
    Integer hours = sla.get(0, Integer.class);
    return new ReplyDue(
        receivedAt.plusHours(hours), AftersalesReplyDueBasis.POLICY_SLA, hours);
  }

  /** 处理方案：必退强制给一个非「不受理」的方案，其余必须由受理人显式给出。 */
  private AftersalesHandlingPlan resolvePlan(
      AftersalesIntakeRequest request, MustRefundDecision mustRefund) {
    if (mustRefund.mustRefund()) {
      // 必退时给什么方案要看 case_type：退货退款就退货退款、换货就换货。
      // 这里只兜住「受理人没给」的情况 —— 给出的默认值与 case_type 一致，
      // 真正的处置流程是 11.2 / 11.3 的职责，本模块不越权执行。
      if (isSet(request.handlingPlan())) {
        AftersalesHandlingPlan p =
            parseEnum(AftersalesHandlingPlan.class, request.handlingPlan(), "处理方案");
        if (p == AftersalesHandlingPlan.REJECT) {
          throw new IllegalArgumentException(
              "该申请命中必退情形，处理方案不能是「不受理」：平台强制、收到即退");
        }
        return p;
      }
      return switch (request.caseType() == null ? "" : request.caseType().trim().toUpperCase()) {
        case "RETURN_REFUND" -> AftersalesHandlingPlan.RETURN_REFUND;
        case "REFUND_ONLY" -> AftersalesHandlingPlan.REFUND_ONLY;
        case "EXCHANGE" -> AftersalesHandlingPlan.EXCHANGE;
        case "REISSUE" -> AftersalesHandlingPlan.REISSUE;
        case "REPAIR" -> AftersalesHandlingPlan.REPAIR;
        default -> AftersalesHandlingPlan.REFUND_ONLY;
      };
    }
    return parseEnum(AftersalesHandlingPlan.class, request.handlingPlan(), "处理方案");
  }

  /**
   * 按责任人名册**加权最少在办单**轮转派单。
   *
   * <p>排序键是 {@code 在办单数 / 权重}：权重 100 的人分到的单是权重 1 的百倍，
   * 所以权重是真在算的（不是摆设）。同负载时按 {@code owner_ref} 字典序定胜负 ——
   * 不加这一句的话，两个人负载相同时每次派单结果会随数据库返回顺序抖动，
   * 「同一批单为什么都派给了同一个人」这种问题就没法复现。
   *
   * <p>「在办单」= 责任人流转里当前责任人是他、且售后单尚未结案（{@code resolved_at is null}）。
   */
  private RosterPick pickFromRoster() {
    Record row =
        sql.fetchOne(
            "select r.owner_ref, r.owner_display_name, r.owner_role_label, r.owner_source,"
                + " r.source_reference,"
                + " (select count(*) from aftersales_owner_assignment a"
                + "    where a.tenant_id = r.tenant_id and a.owner_ref = r.owner_ref"
                + "      and a.captured_at = (select max(a2.captured_at)"
                + "                          from aftersales_owner_assignment a2"
                + "                         where a2.tenant_id = a.tenant_id"
                + "                           and a2.case_id = a.case_id)"
                + "      and exists (select 1 from aftersales_case c"
                + "                   where c.tenant_id = a.tenant_id and c.id = a.case_id"
                + "                     and c.resolved_at is null))::numeric / r.weight as load_ratio"
                + " from aftersales_owner_roster r"
                + " where r.tenant_id = ?::uuid and r.accepting_new_cases"
                + " order by load_ratio asc, r.owner_ref asc"
                + " limit 1",
            currentTenant());
    if (row == null) {
      // 如实报出「没人可派」，不静默留空：空责任人会让这张单在列表里没人认领，
      // 而 UNASSIGNED 本身就是一种可读的状态（前端据此显示待认领）。
      return RosterPick.unassigned();
    }
    return new RosterPick(
        row.get(0, String.class),
        blankToEmpty(row.get(1, String.class)),
        blankToEmpty(row.get(2, String.class)),
        blankToEmpty(row.get(3, String.class)),
        blankToEmpty(row.get(4, String.class)),
        AftersalesOwnerAssignMode.ROSTER_ROUND_ROBIN);
  }

  // ================================================================ 重新判定

  /**
   * 重新判定一张售后单：按<b>当前</b>生效政策再判一次，旧判定留档。
   *
   * <p>这就是「改参数不改历史单据已冻结的行为」的操作面：改完参数不会自动改判，
   * 需要显式重新判定，而**原来那次判定连同它读到的参数原值永久留在表里**。
   *
   * <p>⚠️ 平台声明的必退**不能**在这里被降级：V164 的
   * {@code guard_aftersales_verdict_must_refund} 触发器会直接拒绝 ——
   * 要推翻平台强制必退只能改平台侧。
   */
  public AftersalesIntakeVerdictView rejudge(UUID caseId, UUID actorId, String bearer) {
    Record current =
        sql.fetchOne(
            "select v.*, a.case_no, a.reason_code"
                + " from aftersales_intake_verdict v"
                + " join aftersales_case a on a.tenant_id = v.tenant_id and a.id = v.case_id"
                + " where v.tenant_id = ?::uuid and v.case_id = ?::uuid and v.superseded_at is null",
            currentTenant(),
            caseId);
    if (current == null) {
      throw new IllegalArgumentException(
          "该售后单没有生效中的受理判定（或不存在、不属于当前租户）：" + caseId);
    }
    String caseNo = current.get("case_no", String.class);
    String reasonCode = current.get("reason_code", String.class);
    OffsetDateTime receivedAt = current.get("request_received_at", OffsetDateTime.class);
    OffsetDateTime anchorAt = current.get("order_anchor_at", OffsetDateTime.class);
    String priorBasis = current.get("must_refund_basis", String.class);

    AftersalesPolicyQueryPort.PolicySnapshot snapshot = policy.snapshot(bearer);
    MustRefundDecision decided =
        decideMustRefund(
            reasonCode,
            false,
            parseEnum(
                AftersalesIntakeSource.class, current.get("intake_source", String.class), "受理来源"),
            snapshot);

    // 平台声明必退是**跨判定**的事实：上一轮若判的是平台声明必退，这一轮即便按新词表没命中
    // 也仍然是必退 —— 否则「改一次词表就把平台强制的单洗成普通单」会变成绕过
    // DB 触发器的一条路（触发器只挡 INSERT 那一刻的降级，这里先在服务层对齐就不会去撞它）。
    if ("PLATFORM_DECLARED".equals(priorBasis)) {
      decided =
          new MustRefundDecision(
              true,
              AftersalesMustRefundBasis.PLATFORM_DECLARED,
              "PLATFORM_DECLARED",
              snapshot.mustRefundReasons());
    }

    // 窗口按**锚点 + 当前生效天数**重算：期限参数改了窗口跟着变，而锚点永远不变 ——
    // 这正是「改参数不改已冻结判定」的意义（旧判定里原样留着旧窗口）。
    OffsetDateTime windowDeadline = anchorAt.plusDays(snapshot.returnWindowDays());
    boolean inWindow = !receivedAt.isAfter(windowDeadline);
    long marginHours = (windowDeadline.toEpochSecond() - receivedAt.toEpochSecond()) / 3600L;
    AftersalesHandlingPlan plan =
        decided.mustRefund()
            ? parseEnum(
                AftersalesHandlingPlan.class, current.get("handling_plan", String.class), "上一轮处理方案")
            : AftersalesHandlingPlan.REJECT;
    if (decided.mustRefund() && plan == AftersalesHandlingPlan.REJECT) {
      // 上一轮是「不受理」而这一轮命中必退：给一个兜底方案而不是拒绝重新判定 ——
      // 平台强制的单本来就该有处理方案。
      plan = AftersalesHandlingPlan.REFUND_ONLY;
    }
    AftersalesEligibility eligibility =
        decided.mustRefund()
            ? AftersalesEligibility.ACCEPTED_MUST_REFUND
            : (inWindow
                ? AftersalesEligibility.ACCEPTED
                : AftersalesEligibility.REJECTED_OUT_OF_WINDOW);

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    UUID newId = UUID.randomUUID();
    UUID priorId = current.get("id", UUID.class);

    // 旧判定先收口再插新的：顺序反了会撞「一张单同一时刻只有一条生效判定」的部分唯一索引。
    sql.execute(
        "update aftersales_intake_verdict set superseded_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid and superseded_at is null",
        now,
        currentTenant(),
        priorId);

    sql.execute(
        "insert into aftersales_intake_verdict(id,tenant_id,case_id,intake_source,intake_reference,"
            + " intake_channel,request_received_at,judged_at,judged_by,order_anchor_kind,order_anchor_at,"
            + " policy_return_window_days,return_window_deadline_at,in_return_window,window_margin_hours,"
            + " policy_must_refund_reasons,must_refund,must_refund_basis,must_refund_situation,eligibility,"
            + " policy_source_scope,policy_parameter_codes,policy_read_at,handling_plan,handling_plan_source,"
            + " reply_due_at,reply_due_basis,reply_sla_hours,resolve_due_at,resolve_sla_hours,"
            + " owner_assignment_mode,supersedes_id,created_at)"
            + " values (?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?::timestamptz,?,?::timestamptz,?,?,"
            + " ?::text[],?,?,?,?,?,?::text[],?::timestamptz,?,?,?::timestamptz,?,?,?::timestamptz,?,?,?,now())",
        newId,
        currentTenant(),
        caseId,
        current.get("intake_source", String.class),
        current.get("intake_reference", String.class),
        current.get("intake_channel", String.class),
        receivedAt,
        now,
        actorId,
        current.get("order_anchor_kind", String.class),
        anchorAt,
        snapshot.returnWindowDays(),
        windowDeadline,
        inWindow,
        marginHours,
        // ⚠️ 必须带 ::text[]，理由见 intake() 里同一处注释。
        decided.words().toArray(new String[0]),
        decided.mustRefund(),
        decided.basis().name(),
        decided.situation(),
        eligibility.name(),
        snapshot.windowScope(),
        new String[] {
          AftersalesPolicyQueryPort.PARAM_RETURN_WINDOW_DAYS,
          AftersalesPolicyQueryPort.PARAM_MUST_REFUND_REASONS
        },
        now,
        plan.name(),
        decided.mustRefund() ? "MUST_REFUND_FORCED" : "OPERATOR",
        // 回复时限**沿用首判**：重新判定的是「符不符合条件」，不是重开一次计时。
        // 换一个新时限等于给一张被平台强制必退、且已超时的单续命 —— 那正是蓝图要防的事。
        current.get("reply_due_at", OffsetDateTime.class),
        current.get("reply_due_basis", String.class),
        current.get("reply_sla_hours", Integer.class),
        current.get("resolve_due_at", OffsetDateTime.class),
        current.get("resolve_sla_hours", Integer.class),
        current.get("owner_assignment_mode", String.class),
        priorId);

    return loadVerdict(newId, caseNo);
  }

  // ================================================================ 查询

  /**
   * 受理凭据列表：真分页 + 多维筛选 + <b>与分页无关</b>的汇总。
   *
   * <p>汇总里 {@code replyOverdueMustRefundCount} 是最要紧的一个数：
   * 「必退且回复已超时」—— 直接对应账号绩效与资金冻结，所以它一进页面就能看到，
   * 不必翻到最后一页去数。
   */
  public AftersalesIntakePage list(AftersalesIntakeQuery query) {
    int page = Math.max(0, query.page() == null ? 0 : query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() == null ? 50 : query.size()));
    OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
    boolean currentOnly = query.currentOnly() == null || query.currentOnly();

    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    where.add("v.tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (currentOnly) {
      where.add("v.superseded_at is null");
    }
    if (isSet(query.eligibility())) {
      where.add("v.eligibility = ?");
      binds.add(query.eligibility().trim().toUpperCase());
    }
    if (query.mustRefund() != null) {
      where.add("v.must_refund = ?");
      binds.add(query.mustRefund());
    }
    if (isSet(query.intakeSource())) {
      where.add("v.intake_source = ?");
      binds.add(query.intakeSource().trim().toUpperCase());
    }
    if (isSet(query.ownerAssignmentMode())) {
      where.add("v.owner_assignment_mode = ?");
      binds.add(query.ownerAssignmentMode().trim().toUpperCase());
    }
    if (query.caseId() != null) {
      where.add("v.case_id = ?::uuid");
      binds.add(query.caseId());
    }
    if (isSet(query.caseNo())) {
      where.add("a.case_no = ?");
      binds.add(query.caseNo().trim());
    }
    if (isSet(query.keyword())) {
      where.add("(a.case_no ilike ? or a.order_id::text = ? or v.intake_reference ilike ?)");
      String kw = "%" + query.keyword().trim() + "%";
      binds.add(kw);
      binds.add(query.keyword().trim());
      binds.add(kw);
    }
    String clause = " where " + String.join(" and ", where);
    String sort = sortClause(query.sort());

    long total =
        sql.fetchOne(
                "select count(*) from aftersales_intake_verdict v"
                    + " join aftersales_case a on a.tenant_id = v.tenant_id and a.id = v.case_id"
                    + clause,
                binds.toArray())
            .get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);
    List<Record> rows =
        sql.fetch(
            "select v.*, a.case_no from aftersales_intake_verdict v"
                + " join aftersales_case a on a.tenant_id = v.tenant_id and a.id = v.case_id"
                + clause
                + sort
                + " limit ? offset ?",
            pageBinds.toArray());

    // 汇总与分页无关：统计的是**命中筛选条件的全部行**，不是当前这一页。
    Record sum =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where v.must_refund) as must_refund,"
                + " count(*) filter (where v.must_refund and v.reply_due_at < ?::timestamptz)"
                + "   as overdue_must_refund,"
                + " count(*) filter (where v.eligibility = 'OUT_OF_WINDOW_PENDING') as out_pending,"
                + " count(*) filter (where v.owner_assignment_mode = 'UNASSIGNED') as unassigned,"
                + " count(*) filter (where v.superseded_at is not null) as superseded"
                + " from aftersales_intake_verdict v"
                + " join aftersales_case a on a.tenant_id = v.tenant_id and a.id = v.case_id"
                + clause,
            prepend(at, binds).toArray());

    List<AftersalesIntakeVerdictView> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(toVerdictView(r, at));
    }
    int totalPages = size <= 0 ? 0 : (int) Math.ceil(total / (double) size);
    return new AftersalesIntakePage(
        items,
        total,
        page,
        size,
        totalPages,
        at,
        new AftersalesIntakeSummary(
            sum.get("total", Long.class),
            sum.get("must_refund", Long.class),
            sum.get("overdue_must_refund", Long.class),
            sum.get("out_pending", Long.class),
            sum.get("unassigned", Long.class),
            sum.get("superseded", Long.class)),
        sortSemantics());
  }

  private static String sortClause(String raw) {
    if (raw == null) return " order by v.reply_due_at asc nulls last, v.judged_at desc";
    return switch (raw.trim().toUpperCase()) {
      case "REPLIED_ASC" -> " order by v.reply_due_at asc nulls last";
      case "REPLIED_DESC" -> " order by v.reply_due_at desc nulls last";
      case "JUDGED_ASC" -> " order by v.judged_at asc";
      case "MUST_REFUND_FIRST" ->
          " order by v.must_refund desc, v.reply_due_at asc nulls last";
      default -> " order by v.reply_due_at asc nulls last, v.judged_at desc";
    };
  }

  private static String sortSemantics() {
    return "默认按回复时限升序（最紧急在前，超时排在最前）；"
        + "MUST_REFUND_FIRST 先列必退单；JUDGED_ASC 按受理时刻升序。"
        + "全部排序在数据库层完成。";
  }

  private static List<Object> prepend(Object first, List<Object> rest) {
    List<Object> out = new ArrayList<>();
    out.add(first);
    out.addAll(rest);
    return out;
  }

  private AftersalesIntakeVerdictView loadVerdict(UUID verdictId, String caseNo) {
    Record r =
        sql.fetchOne(
            "select v.*, a.case_no from aftersales_intake_verdict v"
                + " join aftersales_case a on a.tenant_id = v.tenant_id and a.id = v.case_id"
                + " where v.tenant_id = ?::uuid and v.id = ?::uuid",
            currentTenant(),
            verdictId);
    if (r == null) {
      throw new IllegalStateException("受理判定刚写入却读不到：" + verdictId);
    }
    return toVerdictView(r, OffsetDateTime.now(ZoneOffset.UTC));
  }

  private AftersalesIntakeVerdictView toVerdictView(Record r, OffsetDateTime at) {
    Long margin = r.get("window_margin_hours", Long.class);
    Integer sla = r.get("reply_sla_hours", Integer.class);
    Integer resolveSla = r.get("resolve_sla_hours", Integer.class);
    return new AftersalesIntakeVerdictView(
        r.get("id", UUID.class),
        r.get("case_id", UUID.class),
        r.get("case_no", String.class),
        r.get("intake_source", String.class),
        r.get("intake_reference", String.class),
        r.get("intake_channel", String.class),
        r.get("request_received_at", OffsetDateTime.class),
        r.get("judged_at", OffsetDateTime.class),
        r.get("order_anchor_kind", String.class),
        r.get("order_anchor_at", OffsetDateTime.class),
        r.get("policy_return_window_days", Integer.class),
        r.get("return_window_deadline_at", OffsetDateTime.class),
        Boolean.TRUE.equals(r.get("in_return_window", Boolean.class)),
        margin,
        stringArray(r.get("policy_must_refund_reasons", String[].class)),
        Boolean.TRUE.equals(r.get("must_refund", Boolean.class)),
        r.get("must_refund_basis", String.class),
        r.get("must_refund_situation", String.class),
        r.get("eligibility", String.class),
        r.get("handling_plan", String.class),
        r.get("handling_plan_source", String.class),
        r.get("reply_due_at", OffsetDateTime.class),
        r.get("reply_due_basis", String.class),
        sla,
        r.get("resolve_due_at", OffsetDateTime.class),
        resolveSla,
        r.get("owner_assignment_mode", String.class),
        r.get("policy_source_scope", String.class),
        stringArray(r.get("policy_parameter_codes", String[].class)),
        r.get("policy_read_at", OffsetDateTime.class));
  }

  private static List<String> stringArray(String[] raw) {
    return raw == null ? List.of() : Arrays.asList(raw);
  }

  // ================================================================ 回复时限策略

  /** 当前生效的回复时限档（平台精确档优先于全平台兜底档）。 */
  public AftersalesReplySlaView effectiveSla(String platformSource) {
    Record r =
        sql.fetchOne(
            "select s.*, (s.effective_from <= now()"
            + "   and (s.effective_to is null or s.effective_to > now())) as effective_now"
            + " from aftersales_reply_sla s"
            + " where s.tenant_id = ?::uuid and s.platform_scope in (?,'')"
            + "   and s.effective_from <= now()"
            + "   and (s.effective_to is null or s.effective_to > now())"
            + " order by case when s.platform_scope = ? then 0 else 1 end,"
            + "          s.effective_from desc limit 1",
            currentTenant(),
            platformSource == null ? "" : platformSource,
            platformSource == null ? "" : platformSource);
    return r == null ? null : toSlaView(r);
  }

  public List<AftersalesReplySlaView> listSla() {
    return sql.fetch(
            "select s.*, (s.effective_from <= now()"
                + "   and (s.effective_to is null or s.effective_to > now())) as effective_now"
                + " from aftersales_reply_sla s where s.tenant_id = ?::uuid"
                + " order by s.platform_scope asc, s.effective_from desc",
            currentTenant())
        .map(this::toSlaView);
  }

  /** 新开一档。**不写兜底默认值**：没配置就是没配置，让受理在缺时限时报错并指向这里。 */
  public AftersalesReplySlaView createSla(AftersalesReplySlaCreate req, UUID actorId) {
    if (req.replySlaHours() <= 0) {
      throw new IllegalArgumentException("回复时限必须大于 0 小时");
    }
    if (req.resolveSlaHours() != null && req.resolveSlaHours() < req.replySlaHours()) {
      throw new IllegalArgumentException("处理时限不得早于回复时限");
    }
    if (req.effectiveFrom() == null) {
      throw new IllegalArgumentException("必须给出生效起始时刻");
    }
    String scope = req.platformScope() == null ? "" : req.platformScope().trim().toUpperCase();
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into aftersales_reply_sla(id,tenant_id,platform_scope,reply_sla_hours,"
            + " resolve_sla_hours,effective_from,created_by,created_at)"
            + " values (?,?,?,?,?,?::timestamptz,?,now())",
        id,
        currentTenant(),
        scope,
        req.replySlaHours(),
        req.resolveSlaHours(),
        req.effectiveFrom(),
        actorId);
    return loadSla(id, true);
  }

  /** 收口一档（换版或作废）。内容不可改 —— 理由见 V164 的 immutable 触发器。 */
  public AftersalesReplySlaView retireSla(UUID id, AftersalesReplySlaRetire req, UUID actorId) {
    OffsetDateTime to =
        req == null || req.effectiveTo() == null
            ? OffsetDateTime.now(ZoneOffset.UTC)
            : req.effectiveTo();
    int n =
        sql.execute(
            "update aftersales_reply_sla set effective_to = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid and effective_to is null",
            to,
            currentTenant(),
            id);
    if (n == 0) {
      throw new IllegalArgumentException(
          "该回复时限档不存在、已收口或不属于当前租户：" + id);
    }
    return loadSla(id, false);
  }

  private AftersalesReplySlaView loadSla(UUID id, boolean created) {
    Record r =
        sql.fetchOne(
            "select s.*, (s.effective_from <= now()"
                + "   and (s.effective_to is null or s.effective_to > now())) as effective_now"
                + " from aftersales_reply_sla s where s.tenant_id = ?::uuid and s.id = ?::uuid",
            currentTenant(),
            id);
    if (r == null) {
      throw new IllegalStateException("回复时限档刚写入却读不到：" + id);
    }
    AftersalesReplySlaView v = toSlaView(r);
    return new AftersalesReplySlaView(
        v.id(),
        v.platformScope(),
        v.replySlaHours(),
        v.resolveSlaHours(),
        v.effectiveFrom(),
        v.effectiveTo(),
        v.effectiveNow(),
        created);
  }

  private AftersalesReplySlaView toSlaView(Record r) {
    return new AftersalesReplySlaView(
        r.get("id", UUID.class),
        r.get("platform_scope", String.class),
        r.get("reply_sla_hours", Integer.class),
        r.get("resolve_sla_hours", Integer.class),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class),
        Boolean.TRUE.equals(r.get("effective_now", Boolean.class)),
        false);
  }

  // ================================================================ 责任人名册

  public List<AftersalesOwnerRosterView> listRoster() {
    return sql.fetch(
            "select * from aftersales_owner_roster where tenant_id = ?::uuid order by owner_ref",
            currentTenant())
        .map(this::toRosterView);
  }

  /**
   * 登记一个责任人。
   *
   * <p>⚠️ 同一 {@code ownerRef} 重复登记时**更新既有行**而不是报冲突：
   * 名册是「这个人能接单」的事实，同一个人被登记两次（比如先手工登记、
   * 后来同步器又来一遍）不该让同步任务一直失败。标识之外的列按本次请求覆盖。
   */
  public AftersalesOwnerRosterView upsertRoster(AftersalesOwnerRosterCreate req, UUID actorId) {
    if (!isSet(req.ownerRef())) {
      throw new IllegalArgumentException("责任人标识不能为空");
    }
    String ref = req.ownerRef().trim();
    String source =
        req.ownerSource() == null || req.ownerSource().isBlank()
            ? "TENANT_MAINTAINED"
            : req.ownerSource().trim().toUpperCase();
    if ("MODULE_15_SERVICE_STAFF".equals(source) && !isSet(req.ownerSourceReference())) {
      throw new IllegalArgumentException(
          "责任人来源为 MODULE_15_SERVICE_STAFF 时必须给出 sourceReference（哪份来源的哪一段）");
    }
    int weight = req.weight() == null ? 1 : req.weight();
    if (weight < 1 || weight > 100) {
      throw new IllegalArgumentException("权重必须在 1~100 之间：收到 " + req.weight());
    }
    int n =
        sql.execute(
            "update aftersales_owner_roster set owner_display_name = ?, owner_role_label = ?,"
                + " owner_source = ?, source_reference = ?, weight = ?, updated_at = now()"
                + " where tenant_id = ?::uuid and owner_ref = ?",
            blankToEmpty(req.ownerDisplayName()),
            blankToEmpty(req.ownerRoleLabel()),
            source,
            blankToEmpty(req.ownerSourceReference()),
            weight,
            currentTenant(),
            ref);
    if (n == 0) {
      sql.execute(
          "insert into aftersales_owner_roster(id,tenant_id,owner_ref,owner_display_name,"
              + " owner_role_label,owner_source,source_reference,accepting_new_cases,weight,"
              + " created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,true,?,now(),now())",
          UUID.randomUUID(),
          currentTenant(),
          ref,
          blankToEmpty(req.ownerDisplayName()),
          blankToEmpty(req.ownerRoleLabel()),
          source,
          blankToEmpty(req.ownerSourceReference()),
          weight);
    }
    return sql.fetchOne(
            "select * from aftersales_owner_roster where tenant_id = ?::uuid and owner_ref = ?",
            currentTenant(),
            ref)
        .map(this::toRosterView);
  }

  public AftersalesOwnerRosterView updateRoster(UUID id, AftersalesOwnerRosterUpdate req) {
    Record cur =
        sql.fetchOne(
            "select * from aftersales_owner_roster where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    if (cur == null) {
      throw new IllegalArgumentException("该责任人不在名册里，或不属于当前租户：" + id);
    }
    boolean accepting =
        req.acceptingNewCases() == null
            ? Boolean.TRUE.equals(cur.get("accepting_new_cases", Boolean.class))
            : req.acceptingNewCases();
    int weight = req.weight() == null ? cur.get("weight", Integer.class) : req.weight();
    if (weight < 1 || weight > 100) {
      throw new IllegalArgumentException("权重必须在 1~100 之间：收到 " + req.weight());
    }
    sql.execute(
        "update aftersales_owner_roster set accepting_new_cases = ?, weight = ?,"
            + " owner_display_name = ?, owner_role_label = ?, updated_at = now()"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        accepting,
        weight,
        req.ownerDisplayName() == null
            ? cur.get("owner_display_name", String.class)
            : req.ownerDisplayName(),
        req.ownerRoleLabel() == null
            ? cur.get("owner_role_label", String.class)
            : req.ownerRoleLabel(),
        currentTenant(),
        id);
    return sql.fetchOne(
            "select * from aftersales_owner_roster where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id)
        .map(this::toRosterView);
  }

  private AftersalesOwnerRosterView toRosterView(Record r) {
    return new AftersalesOwnerRosterView(
        r.get("id", UUID.class),
        r.get("owner_ref", String.class),
        r.get("owner_display_name", String.class),
        r.get("owner_role_label", String.class),
        r.get("owner_source", String.class),
        r.get("source_reference", String.class),
        Boolean.TRUE.equals(r.get("accepting_new_cases", Boolean.class)),
        r.get("weight", Integer.class),
        r.get("created_at", OffsetDateTime.class));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：四项判定各读什么、必退词表当前长什么样、回复时限策略配没配。
   *
   * <p>⚠️ 本方法<b>会真的去读一次身份服务</b>（判定期限与词表），所以它可能返回 503。
   *   这是刻意的：capabilities 说「必退词表是空的」而实际读不到，与「真的没配」不可区分，
   *   而两者会导致完全不同的受理决策。与 11.1.1.1 的 capabilities（纯静态）不同，
   *   这里返回的是**当前生效值**，因为运营来这个页面的问题正是「我们现在的必退情形有哪些」。
   */
  public AftersalesIntakeCapabilities capabilities(String bearer) {
    AftersalesPolicyQueryPort.PolicySnapshot snapshot = policy.snapshot(bearer);
    Long rosterSize =
        sql.fetchOne(
                "select count(*) from aftersales_owner_roster where tenant_id = ?::uuid",
                currentTenant())
            .get(0, Long.class);
    Long assignable =
        sql.fetchOne(
                "select count(*) from aftersales_owner_roster"
                    + " where tenant_id = ?::uuid and accepting_new_cases",
                currentTenant())
            .get(0, Long.class);
    boolean slaConfigured =
        sql.fetchOne(
                "select count(*) from aftersales_reply_sla"
                    + " where tenant_id = ?::uuid and effective_from <= now()"
                    + "   and (effective_to is null or effective_to > now())",
                currentTenant())
            .get(0, Long.class)
            > 0;

    return new AftersalesIntakeCapabilities(
        List.of(
            "是否在售后期内：锚点取订单下单时刻，期限天数读 17.5.1.1 "
                + AftersalesPolicyQueryPort.PARAM_RETURN_WINDOW_DAYS,
            "是否符合条件：必退词表读 17.5.1.1 "
                + AftersalesPolicyQueryPort.PARAM_MUST_REFUND_REASONS
                + "，平台声明优先于原因分类映射",
            "责任人分配：受理人指定，或按名册加权最少在办单轮转",
            "处理方案：必退单强制非「不受理」，其余由受理人显式给出",
            "回复时限：平台给的时限优先，否则按租户回复时限策略推算"),
        "17.5.1.1 业务参数（identity 服务，/identity/business-parameters/effective）",
        "政策**不在本服务落影子副本**：AGENTS.md 禁止跨服务 SQL，读的是 identity 的授权只读端点，"
            + "透传的是调用方自己的 Authorization（租户仍由身份服务按已认证成员关系解析）。"
            + "每次受理都把读到的生效值冻结进判定凭据，改参数不影响历史单据的判定留档。",
        AftersalesPolicyQueryPort.PARAM_RETURN_WINDOW_DAYS,
        AftersalesPolicyQueryPort.PARAM_MUST_REFUND_REASONS,
        snapshot.returnWindowDays(),
        snapshot.mustRefundReasons(),
        snapshot.windowScope(),
        "本服务 aftersales_reply_sla（V164，租户可维护、分档生效、内容写定不可改）",
        "⚠️ 17.5.1.1 的 8 条出厂默认值里**没有**售后回复时限参数（实测确认，AFTERSALE 类只有"
            + "期限天数与必退情形两条），所以回复时限刻意不假装是「政策参数」。"
            + "平台给了时限就用平台的；没给则按租户 SLA 推算；两档都没有时**受理直接失败**并指向这里，"
            + "绝不默默替租户编一个默认小时数 —— 那会让倒计时对着一个没人承诺过的数字跑。",
        slaConfigured,
        rosterSize == null ? 0 : rosterSize.intValue(),
        assignable == null ? 0 : assignable.intValue(),
        "责任人名册是**租户可维护的登记表**（owner_source 二选一 + 采集时刻纪律照抄 V156）："
            + "卷 15 客户与客服在集成头只交付了 15.4.1.1（在 crm 的独立库），"
            + "本服务读不到「谁是售后客服」，禁止跨服务 SQL ⇒ 由同步器按同一形状写入 MODULE_15_SERVICE_STAFF。"
            + "本模块刻意不伪造 L4 模块号：卷 15 里目前没有任何一个 L4 是「售后责任人分配」的交付方。",
        "平台声明的必退（mustRefundBasis=PLATFORM_DECLARED）写在 V164 的"
            + " guard_aftersales_verdict_must_refund 触发器里：**重新判定不许把它降级为非必退**。"
            + "要推翻只能改平台侧。原因分类映射出来的必退（REASON_MAPPED）允许随词表变化重新判定 —— "
            + "那本来就是政策改了的结果。人工无法自行把一张单标成必退："
            + "平台强制必退只接受平台消息的声明，内部单勾选会被拒。",
        "上游卷 2 平台消息在集成头**未交付**（channel 服务无平台消息表，禁止跨服务 SQL）。"
            + "受理来源与渠道照 V157 的既有纪律如实标注，平台消息同步器到位后按同一形状写入即可。",
        Arrays.stream(AftersalesIntakeSource.class.getEnumConstants())
            .map(AftersalesIntakeSource::name)
            .toList(),
        Arrays.stream(AftersalesHandlingPlan.class.getEnumConstants())
            .map(x -> new AftersalesHandlingPlanOption(x.name(), x.label()))
            .toList(),
        "本模块对 identity 只做授权只读调用（业务参数），不读对方库；"
            + "对 channel / crm 不做任何跨服务调用（卷 2 平台消息与卷 15 客服均未交付）。");
  }

  // ================================================================ 工具

  private UUID currentTenant() {
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    try {
      return Enum.valueOf(type, raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          label + "只能是 " + Arrays.toString(type.getEnumConstants()) + "：收到 " + raw);
    }
  }

  private static boolean isSet(String v) {
    return v != null && !v.isBlank();
  }

  private static String blankToEmpty(String v) {
    return v == null ? "" : v;
  }

  @SafeVarargs
  private static <T> T firstNonNull(T... values) {
    for (T v : values) {
      if (v != null) return v;
    }
    return null;
  }

  /**
   * 必退判定结论。
   *
   * @param words <b>当前生效的完整必退词表</b>（归一后冻结进凭据），**不是**只记命中的那一个 ——
   *     只存命中项就答不出「三个月前还有哪些情形算必退」，而那正是改参数后要复查的东西。
   */
  private record MustRefundDecision(
      boolean mustRefund,
      AftersalesMustRefundBasis basis,
      String situation,
      List<String> words) {}

  private record ReplyDue(
      OffsetDateTime replyDueAt, AftersalesReplyDueBasis basis, Integer slaHours) {}

  private record RosterPick(
      String ownerRef,
      String displayName,
      String roleLabel,
      String ownerSource,
      String sourceReference,
      AftersalesOwnerAssignMode mode) {

    static RosterPick specified(String ref) {
      return new RosterPick(
          ref, "", "", null, "", AftersalesOwnerAssignMode.OPERATOR_SPECIFIED);
    }

    static RosterPick unassigned() {
      return new RosterPick(null, "", "", null, "", AftersalesOwnerAssignMode.UNASSIGNED);
    }
  }
}
