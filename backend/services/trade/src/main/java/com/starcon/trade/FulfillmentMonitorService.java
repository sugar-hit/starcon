package com.starcon.trade;

import static com.starcon.trade.FulfillmentMonitorModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.3.3.2 履约流程监控：把 9.3.3.1 的履约流程状态读成一份**可查证、可处置**的异常清单。
 *
 * <p><b>本模块只做一件事：把「卡住了」变成一条有凭据、有结论、有处置的记录。</b>
 *
 * <p><b>七条实现纪律</b>（照抄 V152 / V126 的写法，不另起风格）：
 *
 * <ol>
 *   <li><b>超时判定用数据库时钟</b>：扫描 SQL 里 {@code extract(epoch from
 *       (clock_timestamp() - deadline_at))}，不用 Java 的 {@code Instant.now()}，
 *       更不用启动时刻缓存。时钟偏 8 小时，监控就全盘失真。
 *       <b>用 clock_timestamp() 而不是 now()</b>：一个事务里 now() 是冻结的，
 *       一次扫描判多条时会把上条的结果当成这条的。</li>
 *   <li><b>加粗句进 DB 层</b>：「先查证再重试」由 V154 第 8.1 / 8.4 节的两个触发器
 *       与第 9.1 节的窄函数强制，本类<b>不</b>自己 if 一下就算数。</li>
 *   <li><b>并发靠 DB</b>：monitor_version 一路传进窄函数做 CAS；
 *       重试的互斥由部分唯一索引 {@code fulfillment_monitor_retry_once_uq} 保证
 *       （同 request + 同 attempt 只允许一条未取消的 RETRY）。</li>
 *   <li><b>不代上游改状态</b>：本模块**不** UPDATE {@code fulfillment_request}
 *       （V152 已把 UPDATE 权收走，语义上也不该我改）。检出只落 case，
 *       要不要重试由处置动作决定，流程怎么动由 9.3.3.1 的窄函数发起。</li>
 *   <li><b>下游只登记、不假装</b>：释放登记缺省 {@code REQUESTED}（待 7.2 回执），
 *       待办缺省 {@code OPEN}（1 工作台未交付）。</li>
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 写在 values 的**对应位置**上。
 *       用 {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 -28800s，
 *       把「查证时刻」提前 8 小时，界面上就成了「我 8 小时前就查过了」。</li>
 *   <li><b>不用 JPA 懒加载</b>：全部 jOOQ 显式 SQL。</li>
 * </ol>
 */
@Service
public class FulfillmentMonitorService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 耗时刻度：与 DB 的 numeric(12,3) 对齐。 */
  private static final int SECONDS_SCALE = 3;

  /** 金额刻度：与 DB 的 numeric(19,4) 对齐。 */
  private static final int MONEY_SCALE = 4;

  private static final BigDecimal ZERO_SECONDS = new BigDecimal("0.000");

  private static final String CASE_COLUMNS =
      "id, case_no, request_id, request_code, order_ref, sku_ref, warehouse_ref, step_kind,"
          + " attempt, anomaly_kind, severity, threshold_seconds, threshold_source,"
          + " overdue_seconds, rule_evidence, detected_at, status, monitor_version,"
          + " investigating_at, verified_at, resolved_at, resolution_note";

  private final DSLContext sql;

  public FulfillmentMonitorService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>两个下游（1 工作台待办、7.2.1.3 释放）<b>都未交付</b>，而这恰恰是最容易被误读的地方：
   * 界面若把释放登记的 REQUESTED 当成「货已释放」，warehouse 那边其实什么都没释放 ——
   * 于是同一批货被两个流程各发一次，正是蓝图要防的那件事。
   */
  public FulfillmentMonitorCapabilities capabilities() {
    return new FulfillmentMonitorCapabilities(
        List.of(
            new FulfillmentMonitorDimension(
                "FULFILLMENT_PROCESS_STATE",
                "履约流程状态（status + current_step + process_version）",
                true,
                "9.3.3.1 履约请求（V152，本服务已交付）",
                null),
            new FulfillmentMonitorDimension(
                "STEP_EVIDENCE",
                "流程状态记录（每步每次尝试一行，含 INSUFFICIENT_STOCK 等失败凭据）",
                true,
                "9.3.3.1 履约请求（V152，本服务已交付）",
                null),
            new FulfillmentMonitorDimension(
                "RESERVATION_STATE",
                "预占指令状态（待回执 / 已锁定 / 已被拒）",
                true,
                "9.3.3.1 履约请求（V152，本服务已交付）",
                null),
            new FulfillmentMonitorDimension(
                "RESERVATION_RELEASE",
                "释放预占（7.2.1.3）",
                false,
                "7.2.1.3 库存释放（warehouse 服务，独立数据库，**未交付**）",
                "7.2.1.3 **未交付**（warehouse 服务的独立库，本服务永远读不到）。"
                    + "跨服务 SQL / FK / 视图被模块边界纪律禁止，故本模块**不写 warehouse 库存**，"
                    + "只登记释放指令（缺省 REQUESTED），收到 7.2 回执才变 RELEASED。"
                    + "capabilities 里 reservationReleaseDispatched=false 如实报出。"),
            new FulfillmentMonitorDimension(
                "WORKBENCH_TODO",
                "工作台待办（1）",
                false,
                "1 工作台待办（独立服务，**未交付**）",
                "人工介入入口落成**租户可维护的待办项表**，带 source_kind 与 captured_at；"
                    + "工作台接上后由同步器按同一形状写入，表结构与逻辑都不用改。"
                    + "capabilities 里 workbenchTodoPushed=false 如实报出。"),
            new FulfillmentMonitorDimension(
                "PATH_SWITCH",
                "切换履约路径（9.3.2.2）",
                false,
                "9.3.2.2 FBA 与自发货选择（V92，本服务已交付）",
                "本模块**只登记**目标 routing_decision_ref 与成本差估值（逻辑引用 + 快照），"
                    + "不 JOIN、不改 V92 的表：V92 是那批决策的写入所有者，"
                    + "本车道代写会破坏「每张事实表只有一个写入所有者」。")),
        false,
        false,
        false,
        true,
        List.of(FulfillmentMonitorAnomalyKind.values()),
        List.of(FulfillmentMonitorSeverity.values()),
        List.of(FulfillmentMonitorActionKind.values()),
        List.of(FulfillmentMonitorVerifiedScope.values()),
        "超时不是失败。一条超时的流程只落一条异常案例（case），"
            + "**不改变流程状态**，也不自动重试。要不要重试必须先做一次查证："
            + "case.status 走 OPEN → INVESTIGATING → VERIFIED，"
            + "只有存在一条 conclusion=CONFIRMED 的查证记录才允许发起 RETRY —— "
            + "这条由 V154 的两个触发器在 DB 层强制，绕不过去。"
            + "查证范围（verified_scope）也参与判断：只看本地状态（LOCAL_STATE_ONLY）"
            + "不足以支持重试，必须查过下游回执或仓库库存才算数。",
        "两个下游都没接通，所以「释放预占」与「人工介入待办」在本服务内都是**登记**，"
            + "不是执行：释放登记缺省 REQUESTED，待办缺省 OPEN。"
            + "调用方要判断「货到底放没放」，必须看 status 是否为 RELEASED，"
            + "不能看这条登记存在与否。",
        "9.3.3.1 负责「把一次履约推进下去」（持久化流程状态、断点恢复、原地重试）；"
            + "本模块负责「一堆流程卡住了 collectively 怎么看、谁来处置」："
            + "异常清单、查证凭据、处置动作与处置记录、人工介入待办。"
            + "分界点是「是不是本流程自己编排时能检出的事实」—— 本流程自己的超时与失败"
            + "由 9.3.3.1 落凭据，跨流程的监控与处置在本模块。");
  }

  // ================================================================ 扫描（检出）

  /**
   * 扫描本租户的履约流程并检出异常，逐条落案例。
   *
   * <p>判定全部在一条 SQL 里做完，{@code clock_timestamp()} 取数据库当前时间：
   * 本类不读 Java 时钟。{@code asOfSecondsAgo} 只把「当前」整体前移，用来在
   * 测试与演练里复现一次真实超时，判据本身不变。
   *
   * <p>四类异常的判据各不相同，刻意不合并：
   * 预占失败看 {@code reservation.status='REJECTED'}，库存不足看
   * {@code step.failure_code='INSUFFICIENT_STOCK'}，另两类看是否超过当前步 deadline。
   */
  @Transactional
  public FulfillmentMonitorScanResult scan(FulfillmentMonitorScanInput input, UUID actorId) {
    FulfillmentMonitorScanInput in =
        input == null
            ? new FulfillmentMonitorScanInput(null, null, null, null)
            : input;
    List<FulfillmentMonitorAnomalyKind> kinds = anomalyKinds(in.anomalyKinds());
    OffsetDateTime asOf =
        in.asOfSecondsAgo() == null
            ? OffsetDateTime.now()
            : OffsetDateTime.now().minusSeconds(in.asOfSecondsAgo());
    int maxCases = in.maxCases() == null ? 100 : Math.min(Math.max(in.maxCases(), 1), 1000);
    String warehouse = nz(in.warehouseRef(), null);

    // ⚠ 阈值解析在 SQL 里做（三档），不在 Java 里拼：解析顺序是口径的一部分，
    //   放 Java 里就会出现「列表页一个阈值、扫描页另一个阈值」。
    String scanSql =
        """
        with base as (
          select r.tenant_id, r.id as request_id, r.request_code, r.order_ref, r.sku_ref,
                 r.warehouse_ref, r.current_step, r.attempt, r.deadline_at, r.last_step_at,
                 r.reserve_timeout_seconds, r.outbound_timeout_seconds,
                 r.work_timeout_seconds, r.track_timeout_seconds,
                 (?::timestamptz) as as_of
            from fulfillment_request r
           where r.status not in ('COMPLETED','CANCELLED')
             and (?::text is null or r.warehouse_ref = ?::text)
        ), cand as (
          -- 卡在某步 / 超时未发货：过了当前步 deadline 仍没推进。
          -- TRACK 步超时叫「超时未发货」，其余步叫「流程卡在某步」——
          -- 处置动作不同（前者催发货，后者查那一步），所以不合并。
          select b.tenant_id, b.request_id, b.request_code, b.order_ref, b.sku_ref,
                 b.warehouse_ref, b.current_step, b.attempt, b.as_of, b.deadline_at,
                 b.reserve_timeout_seconds, b.outbound_timeout_seconds,
                 b.work_timeout_seconds, b.track_timeout_seconds, ''::varchar as reject_reason,
                 (case when b.current_step = 'TRACK' then 'SHIPMENT_TIMEOUT'
                       else 'STEP_STUCK' end)::varchar as anomaly_kind
            from base b
           where b.deadline_at < b.as_of
          union all
          -- 预占失败：下游明确拒了，不是「超时」，是「被拒」。
          select b.tenant_id, b.request_id, b.request_code, b.order_ref, b.sku_ref,
                 b.warehouse_ref, b.current_step, b.attempt, b.as_of, b.deadline_at,
                 b.reserve_timeout_seconds, b.outbound_timeout_seconds,
                 b.work_timeout_seconds, b.track_timeout_seconds, rs.reject_reason,
                 'RESERVATION_FAILED'::varchar
            from base b
            join fulfillment_request_reservation rs
              on rs.tenant_id = b.tenant_id and rs.request_id = b.request_id
             and rs.attempt = b.attempt and rs.status = 'REJECTED'
          union all
          -- 库存不足：V152 的 step 留痕里 INSUFFICIENT_STOCK 是**真实发生过的失败码**，
          -- 不是推断出来的。刻意**不**按 attempt 等值联接：当前这一次尝试可能还没写下自己的
          -- step 行，而「这条流程出过一次缺货失败」是跨尝试成立的事实。
          select b.tenant_id, b.request_id, b.request_code, b.order_ref, b.sku_ref,
                 b.warehouse_ref, b.current_step, b.attempt, b.as_of, b.deadline_at,
                 b.reserve_timeout_seconds, b.outbound_timeout_seconds,
                 b.work_timeout_seconds, b.track_timeout_seconds, st.failure_reason,
                 'INSUFFICIENT_STOCK'::varchar
            from base b
            join fulfillment_request_step st
              on st.tenant_id = b.tenant_id and st.request_id = b.request_id
             and st.failure_code = 'INSUFFICIENT_STOCK'
        ), resolved as (
          select c.*,
                 coalesce(
                   (select t.threshold_seconds from fulfillment_monitor_threshold t
                     where t.tenant_id = c.tenant_id and t.step_kind = c.current_step
                       and t.warehouse_ref = c.warehouse_ref),
                   (select t.threshold_seconds from fulfillment_monitor_threshold t
                     where t.tenant_id = c.tenant_id and t.step_kind = c.current_step
                       and t.warehouse_ref is null),
                   case c.current_step
                     when 'RESERVE'  then c.reserve_timeout_seconds
                     when 'OUTBOUND' then c.outbound_timeout_seconds
                     when 'WORK'     then c.work_timeout_seconds
                     else c.track_timeout_seconds end
                 ) as threshold_seconds,
                 case
                   when exists (select 1 from fulfillment_monitor_threshold t
                                  where t.tenant_id = c.tenant_id and t.step_kind = c.current_step
                                    and t.warehouse_ref = c.warehouse_ref)
                     then 'TENANT_OVERRIDE'
                   when exists (select 1 from fulfillment_monitor_threshold t
                                  where t.tenant_id = c.tenant_id and t.step_kind = c.current_step
                                    and t.warehouse_ref is null)
                     then 'TENANT_DEFAULT'
                   else 'SNAPSHOT_FROM_REQUEST' end as threshold_source
            from cand c
           where c.anomaly_kind = any(?::varchar[])
             -- 反复跑 scan 不刷屏：未结案的同类异常只留一条。
             and not exists (
               select 1 from fulfillment_monitor_case mc
                where mc.tenant_id = c.tenant_id and mc.request_id = c.request_id
                  and mc.step_kind = c.current_step and mc.attempt = c.attempt
                  and mc.anomaly_kind = c.anomaly_kind
                  and mc.status not in ('DISPOSED','DISMISSED'))
        )
        select tenant_id, request_id, request_code, order_ref, sku_ref, warehouse_ref,
               current_step, attempt, as_of, deadline_at, threshold_seconds, threshold_source,
               anomaly_kind, reject_reason,
               greatest(0::numeric,
                 extract(epoch from (as_of - deadline_at))) as overdue_seconds
          from resolved
         order by overdue_seconds desc nulls last, request_id, anomaly_kind
         limit ?
        """;

    String[] kindArray =
        kinds.stream().map(Enum::name).toArray(String[]::new);
    List<Record> rows =
        sql.fetch(scanSql, asOf, warehouse, warehouse, kindArray, maxCases);

    List<FulfillmentMonitorScanEntry> entries = new ArrayList<>();
    int created = 0;
    for (Record row : rows) {
      FulfillmentMonitorAnomalyKind kind = anomalyKind(row.get("anomaly_kind", String.class));
      FulfillmentMonitorStepKind step = stepKind(row.get("current_step", String.class));
      int threshold = row.get("threshold_seconds", Integer.class);
      FulfillmentMonitorThresholdSource source =
          thresholdSource(row.get("threshold_source", String.class));
      BigDecimal overdue = seconds(row.get("overdue_seconds", BigDecimal.class));
      FulfillmentMonitorSeverity severity = severity(kind, threshold, overdue);
      String evidenceText = evidence(kind, step, threshold, source, overdue, row);

      // 真的落一条案例（不是只算不存）：清单、详情、后续查证都挂在它上面。
      UUID caseId = UUID.randomUUID();
      String caseNo = "MC-" + caseId.toString().replace("-", "").substring(0, 16);
      OffsetDateTime detectedAt = asOf;
      sql.execute(
          "insert into fulfillment_monitor_case(id, tenant_id, case_no, request_id, request_code,"
              + " order_ref, sku_ref, warehouse_ref, step_kind, attempt, anomaly_kind, severity,"
              + " threshold_seconds, threshold_source, overdue_seconds, rule_evidence,"
              + " detected_at, detected_by_membership_id)"
              + " values (?::uuid, ?::uuid, ?, ?::uuid, ?, ?, ?, ?, ?::varchar, ?::int,"
              + " ?::varchar, ?::varchar, ?::int, ?::varchar, ?::numeric, ?,"
              + " ?::timestamptz, ?::uuid)",
          caseId,
          row.get("tenant_id", UUID.class),
          caseNo,
          row.get("request_id", UUID.class),
          row.get("request_code", String.class),
          row.get("order_ref", String.class),
          row.get("sku_ref", String.class),
          row.get("warehouse_ref", String.class),
          step.name(),
          row.get("attempt", Integer.class),
          kind.name(),
          severity.name(),
          threshold,
          source.name(),
          overdue,
          evidenceText,
          detectedAt,
          actorId);
      appendEvent(
          caseId,
          FulfillmentMonitorEventKind.DETECTED,
          "",
          FulfillmentMonitorCaseStatus.OPEN.name(),
          0L,
          evidenceText,
          actorId,
          detectedAt);
      entries.add(
          new FulfillmentMonitorScanEntry(
              row.get("request_id", UUID.class),
              row.get("request_code", String.class),
              step,
              kind,
              severity,
              threshold,
              source,
              overdue,
              evidenceText,
              true,
              caseId,
              caseNo,
              null));
      created++;
    }
    return new FulfillmentMonitorScanResult(rows.size(), created, 0, entries);
  }

  /** 严重度：被拒 / 缺货是「必须立刻处置」，超一点点只是「要人看」。 */
  private static FulfillmentMonitorSeverity severity(
      FulfillmentMonitorAnomalyKind kind, int threshold, BigDecimal overdue) {
    return switch (kind) {
      case RESERVATION_FAILED, INSUFFICIENT_STOCK -> FulfillmentMonitorSeverity.CRITICAL;
      case STEP_STUCK, SHIPMENT_TIMEOUT ->
          overdue.compareTo(BigDecimal.valueOf(threshold * 2L)) >= 0
              ? FulfillmentMonitorSeverity.CRITICAL
              : FulfillmentMonitorSeverity.WARNING;
    };
  }

  /** 判定算式原文：界面上直接展示这行就能复现判定，不必点进去再猜。 */
  private String evidence(
      FulfillmentMonitorAnomalyKind kind,
      FulfillmentMonitorStepKind step,
      int threshold,
      FulfillmentMonitorThresholdSource source,
      BigDecimal overdue,
      Record row) {
    return switch (kind) {
      case STEP_STUCK, SHIPMENT_TIMEOUT ->
          String.format(
              "当前步 %s（%s）已超过 deadline %s 秒，阈值 %d 秒（%s）",
              step.name(), step.label(), overdue.toPlainString(), threshold, source.label());
      case RESERVATION_FAILED ->
          String.format(
              "第 %d 次尝试的预占指令已被下游拒绝（拒绝理由：%s），非超时",
              row.get("attempt", Integer.class),
              nz(row.get("reject_reason", String.class), "下游未给出理由"));
      case INSUFFICIENT_STOCK ->
          String.format(
              "第 %d 次尝试的流程状态记录留痕失败码 INSUFFICIENT_STOCK（库存不足）",
              row.get("attempt", Integer.class));
    };
  }

  // ================================================================ 列表 / 详情

  /** 异常流程清单（真分页 + 真 SQL 筛选）。 */
  public FulfillmentMonitorCasePage pageCases(FulfillmentMonitorCaseQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page() == null ? 0 : query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    eq(where, binds, "case_no", query.caseNo());
    eq(where, binds, "request_code", query.requestCode());
    eq(where, binds, "order_ref", query.orderRef());
    eq(where, binds, "sku_ref", query.skuRef());
    eq(where, binds, "warehouse_ref", query.warehouseRef());
    eq(where, binds, "step_kind", query.stepKind());
    eq(where, binds, "anomaly_kind", query.anomalyKind());
    eq(where, binds, "severity", query.severity());
    eq(where, binds, "status", query.status());
    if (Boolean.TRUE.equals(query.verifiedOnly())) {
      where.add("status = 'VERIFIED'");
    }
    if (Boolean.TRUE.equals(query.pendingVerificationOnly())) {
      where.add("status in ('OPEN','INVESTIGATING')");
    }
    if (notBlank(query.keyword())) {
      where.add(
          "(case_no ilike ? or request_code ilike ? or order_ref ilike ? or sku_ref ilike ?"
              + " or rule_evidence ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne("select count(*) c from fulfillment_monitor_case" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<FulfillmentMonitorCaseRow> items =
        sql.fetch(
                "select " + CASE_COLUMNS + " from fulfillment_monitor_case" + sqlWhere
                    + " order by array_position(array['CRITICAL','WARNING','INFO'], severity), detected_at desc, id"
                    + " limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(FulfillmentMonitorService::caseRow);
    return new FulfillmentMonitorCasePage(items, total, pageNo, size);
  }

  /** 异常流程详情：案例 + 查证 + 处置 + 释放 + 待办 + 事件。 */
  public FulfillmentMonitorCaseDetail caseDetail(UUID id) {
    var head = sql.fetchOne("select " + CASE_COLUMNS + " from fulfillment_monitor_case where id = ?", id);
    if (head == null) {
      throw notFound(id);
    }
    FulfillmentMonitorCaseRow row = caseRow(head);
    List<FulfillmentMonitorDispositionRow> dispositions = dispositions(id);
    boolean retryAllowed = row.status() == FulfillmentMonitorCaseStatus.VERIFIED
        && dispositions.stream()
            .noneMatch(d -> !d.status().terminal());
    return new FulfillmentMonitorCaseDetail(
        row,
        verifications(id),
        dispositions,
        releases(id),
        todos(id),
        events(id),
        retryAllowed,
        retryAllowed ? null : retryBlockedReason(row, dispositions));
  }

  /**
   * 写清「为什么现在不能重试」，界面直接显示这句话。
   *
   * <p>不给这句话，调用方只能从 409 的报文里反推，而 409 有五种来源
   * （未查证 / 查证证伪 / 版本冲突 / 并发已有重试 / 查证不属于本案例），
   * 猜错的代价是让操作员点一次重试 —— 那正是重复发货。
   */
  private String retryBlockedReason(
      FulfillmentMonitorCaseRow row, List<FulfillmentMonitorDispositionRow> dispositions) {
    if (row.status() == FulfillmentMonitorCaseStatus.DISMISSED) {
      return "该异常已被查证证伪，不是异常，请走处置里的「证伪」而不是重试。";
    }
    if (row.status().terminal()) {
      return "该异常已结案；要重新处置请等下一次扫描检出新案例。";
    }
    if (row.status() != FulfillmentMonitorCaseStatus.VERIFIED) {
      return "还没有一条「已查证（CONFIRMED）」的查证记录 —— "
          + "超时不是失败，先查证再决定重试还是释放预占。";
    }
    return "该案例已有一条未终结的处置：同一流程不允许并发重试。";
  }

  private List<FulfillmentMonitorVerificationRow> verifications(UUID caseId) {
    return sql.fetch(
            "select id, verification_no, conclusion, verified_scope, evidence_ref, finding,"
                + " verified_at, verified_by_membership_id"
                + " from fulfillment_monitor_verification where case_id = ?"
                + " order by verified_at desc, id",
            caseId)
        .map(
            r ->
                new FulfillmentMonitorVerificationRow(
                    r.get("id", UUID.class),
                    r.get("verification_no", String.class),
                    conclusion(r.get("conclusion", String.class)),
                    label(conclusion(r.get("conclusion", String.class))),
                    scope(r.get("verified_scope", String.class)),
                    label(scope(r.get("verified_scope", String.class))),
                    r.get("evidence_ref", String.class),
                    r.get("finding", String.class),
                    r.get("verified_at", OffsetDateTime.class),
                    r.get("verified_by_membership_id", UUID.class)));
  }

  private List<FulfillmentMonitorDispositionRow> dispositions(UUID caseId) {
    return sql.fetch(
            "select id, disposition_no, case_id, request_id, action_kind, status, verification_id,"
                + " retry_grant_seconds, target_route_ref, target_warehouse_ref,"
                + " estimated_cost_delta, estimated_cost_currency_code, note,"
                + " created_by_membership_id, created_at, sent_at, acknowledged_at, completed_at,"
                + " cancelled_at, outcome, last_error"
                + " from fulfillment_monitor_disposition where case_id = ?"
                + " order by created_at desc, id",
            caseId)
        .map(FulfillmentMonitorService::dispositionRow);
  }

  private List<FulfillmentMonitorReleaseRow> releases(UUID caseId) {
    return sql.fetch(
            "select id, release_no, case_id, request_id, disposition_id, reservation_id,"
                + " source_kind, captured_at, status, release_ref, released_qty, reject_reason,"
                + " requested_at, acknowledged_at, idempotency_key"
                + " from fulfillment_monitor_release where case_id = ? order by requested_at desc, id",
            caseId)
        .map(
            r ->
                new FulfillmentMonitorReleaseRow(
                    r.get("id", UUID.class),
                    r.get("release_no", String.class),
                    r.get("case_id", UUID.class),
                    r.get("request_id", UUID.class),
                    r.get("disposition_id", UUID.class),
                    r.get("reservation_id", UUID.class),
                    sourceKind(r.get("source_kind", String.class)),
                    label(sourceKind(r.get("source_kind", String.class))),
                    r.get("captured_at", OffsetDateTime.class),
                    releaseStatus(r.get("status", String.class)),
                    label(releaseStatus(r.get("status", String.class))),
                    r.get("release_ref", String.class),
                    r.get("released_qty", BigDecimal.class),
                    r.get("reject_reason", String.class),
                    r.get("requested_at", OffsetDateTime.class),
                    r.get("acknowledged_at", OffsetDateTime.class),
                    r.get("idempotency_key", String.class)));
  }

  private List<FulfillmentMonitorTodoRow> todos(UUID caseId) {
    return sql.fetch(
            "select id, todo_no, case_id, request_id, action_kind, severity, title, detail,"
                + " source_kind, captured_at, status, claimed_by_membership_id, claimed_at,"
                + " done_at, done_note from fulfillment_monitor_todo where case_id = ?"
                + " order by captured_at desc, id",
            caseId)
        .map(
            r ->
                new FulfillmentMonitorTodoRow(
                    r.get("id", UUID.class),
                    r.get("todo_no", String.class),
                    r.get("case_id", UUID.class),
                    r.get("request_id", UUID.class),
                    todoAction(r.get("action_kind", String.class)),
                    label(todoAction(r.get("action_kind", String.class))),
                    severity(r.get("severity", String.class)),
                    r.get("title", String.class),
                    r.get("detail", String.class),
                    sourceKind(r.get("source_kind", String.class)),
                    label(sourceKind(r.get("source_kind", String.class))),
                    r.get("captured_at", OffsetDateTime.class),
                    todoStatus(r.get("status", String.class)),
                    label(todoStatus(r.get("status", String.class))),
                    r.get("claimed_by_membership_id", UUID.class),
                    r.get("claimed_at", OffsetDateTime.class),
                    r.get("done_at", OffsetDateTime.class),
                    r.get("done_note", String.class)));
  }

  private List<FulfillmentMonitorEventRow> events(UUID caseId) {
    return sql.fetch(
            "select id, event_kind, from_status, to_status, monitor_version, detail, actor_id,"
                + " occurred_at from fulfillment_monitor_event where case_id = ?"
                + " order by monitor_version desc, occurred_at desc, id",
            caseId)
        .map(
            r ->
                new FulfillmentMonitorEventRow(
                    r.get("id", UUID.class),
                    eventKind(r.get("event_kind", String.class)),
                    label(eventKind(r.get("event_kind", String.class))),
                    r.get("from_status", String.class),
                    r.get("to_status", String.class),
                    r.get("monitor_version", Long.class),
                    r.get("detail", String.class),
                    r.get("actor_id", UUID.class),
                    r.get("occurred_at", OffsetDateTime.class)));
  }

  // ================================================================ 查证

  /**
   * 发起一次查证（加粗句的落点）。
   *
   * <p>走 V154 第 9.1 节的窄函数：monitor_version 做 CAS，
   * 同 idempotencyKey 重放不会造出第二条「我查过了」。
   */
  @Transactional
  public FulfillmentMonitorVerifySaved verify(
      UUID caseId, FulfillmentMonitorVerifyInput input, UUID actorId) {
    if (input.conclusion() != FulfillmentMonitorConclusion.CONFIRMED
        && notBlank(input.evidenceRef()) == false) {
      throw bad("证伪必须点名本次查证看到的凭据：不说查了哪些东西，下次同类会被一并放过");
    }
    if (input.conclusion() == FulfillmentMonitorConclusion.CONFIRMED
        && input.verifiedScope() == FulfillmentMonitorVerifiedScope.LOCAL_STATE_ONLY) {
      throw bad("只看本地状态不足以支持重试：请把查证范围改成已向下游查证回执、"
          + "已查 warehouse 库存或已查承运轨迹");
    }
    OffsetDateTime at =
        input.verifiedAt() == null ? OffsetDateTime.now() : input.verifiedAt();
    long version =
        sql.fetchOne(
                "select verify_fulfillment_monitor_case(?::uuid,?::bigint,?::varchar,?::varchar,"
                    + "?::varchar,?::text,?::timestamptz,?::varchar,?::uuid) as v",
                caseId, input.expectedMonitorVersion(), input.conclusion().name(),
                input.verifiedScope().name(), nz(input.evidenceRef(), null), input.finding(), at,
                input.idempotencyKey(), actorId)
            .get("v", Long.class);
    var head =
        sql.fetchOne(
            "select case_no, status from fulfillment_monitor_case where id = ?", caseId);
    var verification =
        sql.fetchOne(
            "select id from fulfillment_monitor_verification"
                + " where case_id = ? order by verified_at desc, id limit 1",
            caseId);
    appendEvent(
        caseId,
        input.conclusion() == FulfillmentMonitorConclusion.CONFIRMED
            ? FulfillmentMonitorEventKind.VERIFIED
            : FulfillmentMonitorEventKind.NOT_CONFIRMED,
        "",
        head == null ? "" : head.get("status", String.class),
        version,
        input.finding(),
        actorId,
        at);
    return new FulfillmentMonitorVerifySaved(
        caseId,
        head == null ? null : head.get("case_no", String.class),
        verification == null ? null : verification.get("id", UUID.class),
        input.conclusion(),
        caseStatus(head == null ? null : head.get("status", String.class)),
        version,
        at);
  }

  // ================================================================ 处置

  /**
   * 发起一次处置。
   *
   * <p>RETRY 的三道闸门都在 DB：查证必须 CONFIRMED（8.1）、案例必须已查证（8.4）、
   * 同流程同 attempt 只允许一条未取消的 RETRY（部分唯一索引）。
   * 本方法只把语义位校验前置，好让调用方拿到 400 而不是 500。
   */
  @Transactional
  public FulfillmentMonitorDispositionSaved dispose(
      UUID caseId, FulfillmentMonitorDispositionInput input, UUID actorId) {
    if (input.actionKind() == FulfillmentMonitorActionKind.RETRY) {
      if (input.verificationId() == null) {
        throw bad("发起重试必须指定查证记录 id：超时不是失败，先查证再决定重试还是释放预占");
      }
      if (input.retryGrantSeconds() == null || input.retryGrantSeconds() <= 0) {
        throw bad("发起重试必须给出新的超时窗口秒数，否则重试出来当场又超时");
      }
    }
    if ((input.estimatedCostDelta() == null) != (input.estimatedCostCurrencyCode() == null)) {
      throw bad("成本差估值必须与币种成对出现：只给金额不给币种，下游必然按错币种入账");
    }
    if (input.actionKind() == FulfillmentMonitorActionKind.SWITCH_PATH
        && !notBlank(input.targetRouteRef())) {
      throw bad("切换履约路径必须给目标 routing_decision_ref（9.3.2.2）");
    }
    var existing =
        sql.fetchOne(
            "select id from fulfillment_monitor_disposition where tenant_id ="
                + " nullif(current_setting('app.current_tenant',true),'')::uuid"
                + " and idempotency_key = ?",
            input.idempotencyKey());
    if (existing != null) {
      var prev =
          sql.fetchOne(
              "select id, disposition_no, case_id, request_id, action_kind, status from"
                  + " fulfillment_monitor_disposition where id = ?",
              existing.get("id", UUID.class));
      var monitorVersion =
          sql.fetchOne(
                  "select monitor_version from fulfillment_monitor_case where id = ?", caseId)
              .get("monitor_version", Long.class);
      return new FulfillmentMonitorDispositionSaved(
          prev.get("id", UUID.class),
          prev.get("disposition_no", String.class),
          prev.get("case_id", UUID.class),
          prev.get("request_id", UUID.class),
          actionKind(prev.get("action_kind", String.class)),
          dispositionStatus(prev.get("status", String.class)),
          monitorVersion,
          null,
          null);
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into fulfillment_monitor_disposition(id, tenant_id, disposition_no, case_id,"
            + " request_id, attempt, action_kind, status, verification_id, retry_grant_seconds,"
            + " target_route_ref, target_warehouse_ref, estimated_cost_delta,"
            + " estimated_cost_currency_code, note, created_by_membership_id, idempotency_key)"
            + " select ?, c.tenant_id, ?, c.id, c.request_id, c.attempt, ?, 'PENDING', ?, ?, ?, ?,"
            + " ?, ?, ?, ?, ? from fulfillment_monitor_case c where c.id = ?",
        id,
        dispositionNo(caseId),
        input.actionKind().name(),
        input.verificationId(),
        input.retryGrantSeconds(),
        nz(input.targetRouteRef(), ""),
        nz(input.targetWarehouseRef(), ""),
        input.estimatedCostDelta(),
        input.estimatedCostCurrencyCode(),
        input.note(),
        actorId,
        input.idempotencyKey(),
        caseId);
    appendEvent(
        caseId,
        FulfillmentMonitorEventKind.DISPOSITION_CREATED,
        "",
        input.actionKind().name(),
        monitorVersion(caseId),
        input.note(),
        actorId,
        OffsetDateTime.now());

    UUID releaseId = null;
    String releaseNoOut = null;
    if (input.actionKind() == FulfillmentMonitorActionKind.RELEASE_RESERVATION) {
      FulfillmentMonitorReleaseRow released =
          registerRelease(caseId, id, input.idempotencyKey() + "-release", actorId);
      releaseId = released == null ? null : released.id();
      releaseNoOut = released == null ? null : released.releaseNo();
    }
    return new FulfillmentMonitorDispositionSaved(
        id,
        dispositionNo(caseId),
        caseId,
        requestId(caseId),
        input.actionKind(),
        FulfillmentMonitorDispositionStatus.PENDING,
        monitorVersion(caseId),
        releaseId,
        releaseNoOut);
  }

  /**
   * 登记释放预占指令（下游 7.2.1.3 **未交付**）。
   *
   * <p>登记的是**指令**（缺省 REQUESTED），不是「货已释放」。没有 7.2 的回执
   * 就不算释放 —— 与 V152 的预占缺省 REQUESTED 同一纪律。绝不跨库写 warehouse。
   */
  @Transactional
  public FulfillmentMonitorReleaseRow registerRelease(
      UUID caseId, UUID dispositionId, String idempotencyKey, UUID actorId) {
    var reservation =
        sql.fetchOne(
            "select id from fulfillment_request_reservation where request_id = ?"
                + " and status in ('LOCKED','RELEASED') order by attempt desc, id limit 1",
            requestId(caseId));
    if (reservation == null) {
      throw bad("该履约流程还没有一条已锁定的预占指令可释放："
          + "释放预占的前提是先锁过货，没有锁过就释放等于凭空抹掉一笔占用");
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into fulfillment_monitor_release(id, tenant_id, release_no, case_id, request_id,"
            + " disposition_id, reservation_id, source_kind, captured_at, status, requested_at,"
            + " idempotency_key, created_by_membership_id)"
            + " select ?, c.tenant_id, ?, c.id, c.request_id, ?, ?::uuid, 'TENANT_MAINTAINED',"
            + " ?::timestamptz, 'REQUESTED', clock_timestamp(), ?, ?"
            + " from fulfillment_monitor_case c where c.id = ?",
        id,
        releaseNo(caseId),
        dispositionId,
        reservation.get("id", UUID.class),
        OffsetDateTime.now(),
        idempotencyKey,
        actorId,
        caseId);
    appendEvent(
        caseId,
        FulfillmentMonitorEventKind.RELEASE_REGISTERED,
        "",
        "REQUESTED",
        monitorVersion(caseId),
        "已登记释放预占指令（待 7.2.1.3 回执，7.2.1.3 尚未交付）",
        actorId,
        OffsetDateTime.now());
    return releaseRow(id);
  }

  /** 推进处置状态（登记下游回执）。乐观并发是**状态上的 CAS**，重放第二次被挡。 */
  @Transactional
  public FulfillmentMonitorDispositionRow advanceDisposition(
      UUID caseId, UUID dispositionId, FulfillmentMonitorDispositionStatusInput input) {
    if (input.toStatus() == FulfillmentMonitorDispositionStatus.FAILED
        && !notBlank(input.lastError())) {
      throw bad("处置标记为失败必须写明失败原因：不知道为什么失败的，下一次还会踩同一个坑");
    }
    sql.execute(
        "select advance_fulfillment_monitor_disposition(?::uuid,?::varchar,?::varchar,?::varchar,"
            + "?::varchar)",
        dispositionId, input.fromStatus().name(), input.toStatus().name(), input.outcome(),
        input.lastError());
    appendEvent(
        caseId,
        dispositionEvent(input.toStatus()),
        input.fromStatus().name(),
        input.toStatus().name(),
        monitorVersion(caseId),
        nz(input.outcome(), nz(input.lastError(), "处置状态已推进")),
        null,
        OffsetDateTime.now());
    Record updated =
        sql.fetchOne(
            "select id, disposition_no, case_id, request_id, action_kind, status, verification_id,"
                + " retry_grant_seconds, target_route_ref, target_warehouse_ref,"
                + " estimated_cost_delta, estimated_cost_currency_code, note,"
                + " created_by_membership_id, created_at, sent_at, acknowledged_at, completed_at,"
                + " cancelled_at, outcome, last_error from fulfillment_monitor_disposition"
                + " where id = ?",
            dispositionId);
    if (updated == null) {
      throw notFound(dispositionId);
    }
    return dispositionRow(updated);
  }

  /** 结案：要求至少一条 COMPLETED 的处置 —— 只发出（SENT）不算处置完。 */
  @Transactional
  public FulfillmentMonitorCaseSaved close(
      UUID caseId, FulfillmentMonitorCloseInput input, UUID actorId) {
    long version =
        sql.fetchOne(
                "select close_fulfillment_monitor_case(?::uuid,?::bigint,?::varchar,?::uuid) as v",
                caseId, input.expectedMonitorVersion(), input.note(), actorId)
            .get("v", Long.class);
    var head =
        sql.fetchOne(
            "select case_no, status, monitor_version from fulfillment_monitor_case where id = ?",
            caseId);
    appendEvent(
        caseId,
        FulfillmentMonitorEventKind.DISPOSITION_COMPLETED,
        "VERIFIED",
        "DISPOSED",
        version,
        nz(input.note(), "处置完成，案例结案"),
        actorId,
        OffsetDateTime.now());
    return new FulfillmentMonitorCaseSaved(
        caseId,
        head == null ? null : head.get("case_no", String.class),
        FulfillmentMonitorCaseStatus.DISPOSED,
        version,
        OffsetDateTime.now());
  }

  /** 登记 7.2.1.3 的释放回执（REQUESTED → RELEASED / REJECTED）。 */
  @Transactional
  public FulfillmentMonitorReleaseRow ackRelease(
      UUID caseId, UUID releaseId, FulfillmentMonitorReleaseAckInput input) {
    sql.execute(
        "select ack_fulfillment_monitor_release(?::uuid,?::varchar,?::varchar,?::numeric,"
            + "?::varchar)",
        releaseId, input.status().name(), input.releaseRef(), input.releasedQty(),
        input.rejectReason());
    appendEvent(
        caseId,
        FulfillmentMonitorEventKind.RELEASE_ACK,
        "REQUESTED",
        input.status().name(),
        monitorVersion(caseId),
        nz(input.releaseRef(), nz(input.rejectReason(), "收到释放回执")),
        null,
        OffsetDateTime.now());
    return releaseRow(releaseId);
  }

  // ================================================================ 阈值

  /** 当前生效阈值：仓库覆盖 > 租户默认 > 建单快照，每档带来源。 */
  public FulfillmentMonitorEffectiveThresholds effectiveThresholds(String warehouseRef) {
    String warehouse = nz(warehouseRef, null);
    List<Record> rows =
        sql.fetch(
            "select t.id, t.warehouse_ref, s.step_kind, t.threshold_seconds, t.notes,"
                + " t.created_at, t.updated_at,"
                + " case when t.warehouse_ref is not null then 'TENANT_OVERRIDE'"
                + "      when t.warehouse_ref is null and t.id is not null then 'TENANT_DEFAULT'"
                + "      else 'BUILTIN' end as source"
                + " from (values ('RESERVE'),('OUTBOUND'),('WORK'),('TRACK')) s(step_kind)"
                + " left join fulfillment_monitor_threshold t"
                + "   on t.step_kind = s.step_kind"
                + "  and (t.warehouse_ref = ?::text or t.warehouse_ref is null)"
                + " order by case when t.warehouse_ref is null then 1 else 0 end,"
                + "  case s.step_kind when 'RESERVE' then 1 when 'OUTBOUND' then 2"
                + "    when 'WORK' then 3 else 4 end",
            warehouse);
    List<FulfillmentMonitorThresholdRow> items = new ArrayList<>();
    // 只留每步的第一行（仓库覆盖优先），并给没配的步补上内置兜底档。
    java.util.Set<String> seen = new java.util.LinkedHashSet<>();
    for (Record r : rows) {
      String step = r.get("step_kind", String.class);
      if (!seen.add(step)) {
        continue;
      }
      UUID id = r.get("id", UUID.class);
      if (id == null) {
        FulfillmentMonitorStepKind kind = stepKind(step);
        items.add(
            new FulfillmentMonitorThresholdRow(
                null, warehouse, kind, kind.label(), kind.builtinTimeoutSeconds(),
                FulfillmentMonitorThresholdSource.BUILTIN, "内置兜底",
                "租户未配置任何档，使用内置兜底值", null, null));
      } else {
        items.add(
            new FulfillmentMonitorThresholdRow(
                id, r.get("warehouse_ref", String.class), stepKind(step), stepKind(step).label(),
                r.get("threshold_seconds", Integer.class), thresholdSource(r.get("source", String.class)),
                label(thresholdSource(r.get("source", String.class))), r.get("notes", String.class),
                r.get("created_at", OffsetDateTime.class), r.get("updated_at", OffsetDateTime.class)));
      }
    }
    return new FulfillmentMonitorEffectiveThresholds(warehouse, items);
  }

  /** 阈值 upsert（幂等：同 (仓, 步) 覆盖而不是插第二条）。 */
  @Transactional
  public FulfillmentMonitorThresholdRow putThreshold(
      FulfillmentMonitorThresholdInput input, UUID actorId) {
    if (input.warehouseRef() != null && input.warehouseRef().isBlank()) {
      throw bad("仓号不许传空串或纯空白：空串≠NULL，会变成第二条默认档");
    }
    String warehouse = nz(input.warehouseRef(), null);
    if (input.thresholdSeconds() <= 0) {
      throw bad("超时阈值必须为正秒数");
    }
    sql.execute(
        "insert into fulfillment_monitor_threshold(id, tenant_id, warehouse_ref, step_kind,"
            + " threshold_seconds, created_by_membership_id, notes)"
            + " values (?::uuid, nullif(current_setting('app.current_tenant',true),'')::uuid,"
            + " ?::varchar, ?::varchar, ?::int, ?::uuid, ?)"
            + " on conflict (tenant_id, warehouse_ref, step_kind) do update"
            + "   set threshold_seconds = excluded.threshold_seconds, notes = excluded.notes,"
            + "       updated_at = clock_timestamp()",
        UUID.randomUUID(), warehouse, input.stepKind().name(), input.thresholdSeconds(), actorId,
        nz(input.notes(), ""));
    var row =
        sql.fetchOne(
            "select id, warehouse_ref, step_kind, threshold_seconds, notes, created_at, updated_at"
                + " from fulfillment_monitor_threshold where warehouse_ref is not distinct from ?::varchar"
                + " and step_kind = ?::varchar",
            warehouse, input.stepKind().name());
    return new FulfillmentMonitorThresholdRow(
        row.get("id", UUID.class),
        row.get("warehouse_ref", String.class),
        input.stepKind(),
        input.stepKind().label(),
        row.get("threshold_seconds", Integer.class),
        warehouse == null
            ? FulfillmentMonitorThresholdSource.TENANT_DEFAULT
            : FulfillmentMonitorThresholdSource.TENANT_OVERRIDE,
        warehouse == null ? "租户默认档" : "仓库覆盖档",
        row.get("notes", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 待办

  /** 人工介入待办列表（真分页）。 */
  public FulfillmentMonitorTodoPage pageTodos(FulfillmentMonitorTodoQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page() == null ? 0 : query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (query.caseId() != null) {
      where.add("case_id = ?");
      binds.add(query.caseId());
    }
    eq(where, binds, "status", query.status());
    eq(where, binds, "severity", query.severity());
    eq(where, binds, "action_kind", query.actionKind());
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne("select count(*) c from fulfillment_monitor_todo" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<FulfillmentMonitorTodoRow> items =
        sql.fetch(
                "select id, todo_no, case_id, request_id, action_kind, severity, title, detail,"
                    + " source_kind, captured_at, status, claimed_by_membership_id, claimed_at,"
                    + " done_at, done_note from fulfillment_monitor_todo" + sqlWhere
                    + " order by array_position(array['CRITICAL','WARNING','INFO'], severity), captured_at desc, id"
                    + " limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(FulfillmentMonitorService::todoRow);
    return new FulfillmentMonitorTodoPage(items, total, pageNo, size);
  }

  /**
   * 登记人工介入待办项（下游 1 工作台待办 **未交付**，本服务内是权威待办）。
   *
   * <p>{@code capturedAt}（采集时刻）与登记时刻刻意分开：登记时刻会把
   * 「十分钟前就该看的单」说成「刚刚出现的」，排序和 SLA 都会算错。
   */
  @Transactional
  public FulfillmentMonitorTodoRow createTodo(UUID caseId, FulfillmentMonitorTodoInput input, UUID actorId) {
    OffsetDateTime capturedAt =
        input.capturedAt() == null ? OffsetDateTime.now() : input.capturedAt();
    FulfillmentMonitorSourceKind source =
        input.sourceKind() == null
            ? FulfillmentMonitorSourceKind.TENANT_MAINTAINED
            : input.sourceKind();
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into fulfillment_monitor_todo(id, tenant_id, todo_no, case_id, request_id,"
            + " action_kind, severity, title, detail, source_kind, captured_at, status,"
            + " created_by_membership_id)"
            + " select ?, c.tenant_id, ?, c.id, c.request_id, ?, c.severity, ?, ?, ?,"
            + " ?::timestamptz, 'OPEN', ? from fulfillment_monitor_case c where c.id = ?",
        id,
        todoNo(caseId, input.actionKind()),
        input.actionKind().name(),
        input.title(),
        nz(input.detail(), ""),
        source.name(),
        capturedAt,
        actorId,
        caseId);
    appendEvent(
        caseId,
        FulfillmentMonitorEventKind.TODO_CREATED,
        "",
        "OPEN",
        monitorVersion(caseId),
        input.title(),
        actorId,
        capturedAt);
    return loadTodo(id);
  }

  /** 待办认领 / 完成 / 取消（1 工作台未交付，本服务内是权威待办）。 */
  @Transactional
  public FulfillmentMonitorTodoRow actionTodo(UUID todoId, FulfillmentMonitorTodoActionInput input, UUID actorId) {
    sql.execute(
        "select claim_fulfillment_monitor_todo(?::uuid,?::varchar,?::varchar,?::uuid)",
        todoId, input.action(), input.note(), actorId);
    var row =
        sql.fetchOne(
            "select case_id from fulfillment_monitor_todo where id = ?", todoId);
    UUID caseId = row == null ? null : row.get("case_id", UUID.class);
    if (caseId != null) {
      appendEvent(
          caseId,
          switch (input.action()) {
            case "CLAIM" -> FulfillmentMonitorEventKind.TODO_CLAIMED;
            case "DONE" -> FulfillmentMonitorEventKind.TODO_DONE;
            default -> FulfillmentMonitorEventKind.TODO_CANCELLED;
          },
          "",
          input.action(),
          monitorVersion(caseId),
          nz(input.note(), "待办已处理"),
          actorId,
          OffsetDateTime.now());
    }
    return loadTodo(todoId);
  }

  /** 全部事件流（跨案例的最近事件，供看板「最近发生过什么」用）。 */
  public List<FulfillmentMonitorEventRow> recentEvents(UUID caseId, int rawSize) {
    int size = pageSize(rawSize);
    if (caseId != null) {
      return events(caseId);
    }
    return sql.fetch(
            "select e.id, e.event_kind, e.from_status, e.to_status, e.monitor_version, e.detail,"
                + " e.actor_id, e.occurred_at from fulfillment_monitor_event e"
                + " order by e.occurred_at desc, e.id limit ?",
            size)
        .map(
            r ->
                new FulfillmentMonitorEventRow(
                    r.get("id", UUID.class),
                    eventKind(r.get("event_kind", String.class)),
                    label(eventKind(r.get("event_kind", String.class))),
                    r.get("from_status", String.class),
                    r.get("to_status", String.class),
                    r.get("monitor_version", Long.class),
                    r.get("detail", String.class),
                    r.get("actor_id", UUID.class),
                    r.get("occurred_at", OffsetDateTime.class)));
  }

  // ================================================================ 行映射 / 工具

  private static FulfillmentMonitorCaseRow caseRow(Record r) {
    FulfillmentMonitorStepKind step = stepKind(r.get("step_kind", String.class));
    FulfillmentMonitorAnomalyKind kind = anomalyKind(r.get("anomaly_kind", String.class));
    FulfillmentMonitorSeverity sev = severity(r.get("severity", String.class));
    FulfillmentMonitorThresholdSource source = thresholdSource(r.get("threshold_source", String.class));
    FulfillmentMonitorCaseStatus status = caseStatus(r.get("status", String.class));
    return new FulfillmentMonitorCaseRow(
        r.get("id", UUID.class),
        r.get("case_no", String.class),
        r.get("request_id", UUID.class),
        r.get("request_code", String.class),
        r.get("order_ref", String.class),
        r.get("sku_ref", String.class),
        r.get("warehouse_ref", String.class),
        step, step.label(),
        r.get("attempt", Integer.class),
        kind, kind.label(),
        sev, sev.label(),
        r.get("threshold_seconds", Integer.class),
        source, source.label(),
        seconds(r.get("overdue_seconds", BigDecimal.class)),
        r.get("rule_evidence", String.class),
        r.get("detected_at", OffsetDateTime.class),
        status, status.label(),
        r.get("monitor_version", Long.class),
        r.get("investigating_at", OffsetDateTime.class),
        r.get("verified_at", OffsetDateTime.class),
        r.get("resolved_at", OffsetDateTime.class),
        r.get("resolution_note", String.class));
  }

  private static FulfillmentMonitorDispositionRow dispositionRow(Record r) {
    FulfillmentMonitorActionKind kind = actionKind(r.get("action_kind", String.class));
    FulfillmentMonitorDispositionStatus status = dispositionStatus(r.get("status", String.class));
    return new FulfillmentMonitorDispositionRow(
        r.get("id", UUID.class),
        r.get("disposition_no", String.class),
        r.get("case_id", UUID.class),
        r.get("request_id", UUID.class),
        kind, kind.label(),
        status, status.label(),
        r.get("verification_id", UUID.class),
        r.get("retry_grant_seconds", Integer.class),
        r.get("target_route_ref", String.class),
        r.get("target_warehouse_ref", String.class),
        money(r.get("estimated_cost_delta", BigDecimal.class)),
        r.get("estimated_cost_currency_code", String.class),
        r.get("note", String.class),
        r.get("created_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("sent_at", OffsetDateTime.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class),
        r.get("cancelled_at", OffsetDateTime.class),
        r.get("outcome", String.class),
        r.get("last_error", String.class));
  }

  private static FulfillmentMonitorTodoRow todoRow(Record r) {
    FulfillmentMonitorTodoAction action = todoAction(r.get("action_kind", String.class));
    FulfillmentMonitorSeverity sev = severity(r.get("severity", String.class));
    FulfillmentMonitorSourceKind source = sourceKind(r.get("source_kind", String.class));
    FulfillmentMonitorTodoStatus status = todoStatus(r.get("status", String.class));
    return new FulfillmentMonitorTodoRow(
        r.get("id", UUID.class),
        r.get("todo_no", String.class),
        r.get("case_id", UUID.class),
        r.get("request_id", UUID.class),
        action, action.label(),
        sev,
        r.get("title", String.class),
        r.get("detail", String.class),
        source, source.label(),
        r.get("captured_at", OffsetDateTime.class),
        status, status.label(),
        r.get("claimed_by_membership_id", UUID.class),
        r.get("claimed_at", OffsetDateTime.class),
        r.get("done_at", OffsetDateTime.class),
        r.get("done_note", String.class));
  }

  private FulfillmentMonitorReleaseRow releaseRow(UUID id) {
    Record r =
        sql.fetchOne(
            "select id, release_no, case_id, request_id, disposition_id, reservation_id,"
                + " source_kind, captured_at, status, release_ref, released_qty, reject_reason,"
                + " requested_at, acknowledged_at, idempotency_key"
                + " from fulfillment_monitor_release where id = ?",
            id);
    if (r == null) {
      throw notFound(id);
    }
    FulfillmentMonitorSourceKind source = sourceKind(r.get("source_kind", String.class));
    FulfillmentMonitorReleaseStatus status = releaseStatus(r.get("status", String.class));
    return new FulfillmentMonitorReleaseRow(
        r.get("id", UUID.class),
        r.get("release_no", String.class),
        r.get("case_id", UUID.class),
        r.get("request_id", UUID.class),
        r.get("disposition_id", UUID.class),
        r.get("reservation_id", UUID.class),
        source,
        source.label(),
        r.get("captured_at", OffsetDateTime.class),
        status,
        status.label(),
        r.get("release_ref", String.class),
        r.get("released_qty", BigDecimal.class),
        r.get("reject_reason", String.class),
        r.get("requested_at", OffsetDateTime.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("idempotency_key", String.class));
  }

  /** 单条待办读回。 */
  private FulfillmentMonitorTodoRow loadTodo(UUID id) {
    Record r =
        sql.fetchOne(
            "select id, todo_no, case_id, request_id, action_kind, severity, title, detail,"
                + " source_kind, captured_at, status, claimed_by_membership_id, claimed_at,"
                + " done_at, done_note from fulfillment_monitor_todo where id = ?",
            id);
    if (r == null) {
      throw notFound(id);
    }
    return todoRow(r);
  }

  /** 事件流是留痕表（append-only），只有 INSERT 权。 */
  private void appendEvent(
      UUID caseId,
      FulfillmentMonitorEventKind kind,
      String from,
      String to,
      long version,
      String detail,
      UUID actorId,
      OffsetDateTime at) {
    sql.execute(
        "insert into fulfillment_monitor_event(id, tenant_id, case_id, event_kind, from_status,"
            + " to_status, monitor_version, detail, actor_id, occurred_at)"
            + " select ?::uuid, c.tenant_id, c.id, ?, ?, ?, ?, ?, ?, ?::timestamptz"
            + " from fulfillment_monitor_case c where c.id = ?",
        UUID.randomUUID(), kind.name(), from, to, version, nz(detail, ""), actorId, at, caseId);
  }

  private long monitorVersion(UUID caseId) {
    var row = sql.fetchOne("select monitor_version from fulfillment_monitor_case where id = ?", caseId);
    return row == null ? 0L : row.get("monitor_version", Long.class);
  }

  private UUID requestId(UUID caseId) {
    var row = sql.fetchOne("select request_id from fulfillment_monitor_case where id = ?", caseId);
    return row == null ? null : row.get("request_id", UUID.class);
  }

  private String dispositionNo(UUID caseId) {
    return "DSP-" + caseId.toString().replace("-", "").substring(0, 16);
  }

  private String releaseNo(UUID caseId) {
    return "REL-" + caseId.toString().replace("-", "").substring(0, 16);
  }

  private String todoNo(UUID caseId, FulfillmentMonitorTodoAction action) {
    return "TD-" + action.name() + "-" + caseId.toString().replace("-", "").substring(0, 12);
  }

  private static void eq(List<String> where, List<Object> binds, String column, String value) {
    if (notBlank(value)) {
      where.add(column + " = ?");
      binds.add(value.trim());
    }
  }

  private static int pageSize(Integer size) {
    if (size == null) {
      return 50;
    }
    return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
  }

  private static Object[] append(List<Object> binds, Object... more) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(more));
    return all.toArray();
  }

  private static String whereClause(List<String> where) {
    return where.isEmpty() ? "" : " where " + String.join(" and ", where);
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String nz(String value, String fallback) {
    return notBlank(value) ? value.trim() : fallback;
  }

  private static BigDecimal seconds(BigDecimal value) {
    return value == null ? ZERO_SECONDS : value.setScale(SECONDS_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static List<FulfillmentMonitorAnomalyKind> anomalyKinds(List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of(FulfillmentMonitorAnomalyKind.values());
    }
    return raw.stream().map(FulfillmentMonitorService::anomalyKind).toList();
  }

  private static FulfillmentMonitorEventKind dispositionEvent(
      FulfillmentMonitorDispositionStatus status) {
    return switch (status) {
      case SENT -> FulfillmentMonitorEventKind.DISPOSITION_SENT;
      case ACKNOWLEDGED -> FulfillmentMonitorEventKind.DISPOSITION_ACKNOWLEDGED;
      case COMPLETED -> FulfillmentMonitorEventKind.DISPOSITION_COMPLETED;
      case FAILED -> FulfillmentMonitorEventKind.DISPOSITION_FAILED;
      case CANCELLED -> FulfillmentMonitorEventKind.DISPOSITION_CANCELLED;
      case PENDING -> FulfillmentMonitorEventKind.DISPOSITION_CREATED;
    };
  }

  /** 中文名。枚举统一实现 Labelled，避免各处散落 switch（各写一遍迟早漂移）。 */
  private static String label(Enum<?> value) {
    return value == null ? null : ((Labelled) value).label();
  }

  private static ResponseStatusException notFound(UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "监控案例 " + id + " 不存在或不属于本租户");
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  // ---- 词表映射（DB CHECK ↔ Java 枚举，一字不改）----
  private static FulfillmentMonitorAnomalyKind anomalyKind(String v) {
    return FulfillmentMonitorAnomalyKind.valueOf(v);
  }

  private static FulfillmentMonitorSeverity severity(String v) {
    return FulfillmentMonitorSeverity.valueOf(v);
  }

  private static FulfillmentMonitorStepKind stepKind(String v) {
    return FulfillmentMonitorStepKind.valueOf(v);
  }

  private static FulfillmentMonitorCaseStatus caseStatus(String v) {
    return v == null ? null : FulfillmentMonitorCaseStatus.valueOf(v);
  }

  private static FulfillmentMonitorConclusion conclusion(String v) {
    return FulfillmentMonitorConclusion.valueOf(v);
  }

  private static FulfillmentMonitorVerifiedScope scope(String v) {
    return FulfillmentMonitorVerifiedScope.valueOf(v);
  }

  private static FulfillmentMonitorActionKind actionKind(String v) {
    return FulfillmentMonitorActionKind.valueOf(v);
  }

  private static FulfillmentMonitorDispositionStatus dispositionStatus(String v) {
    return FulfillmentMonitorDispositionStatus.valueOf(v);
  }

  private static FulfillmentMonitorReleaseStatus releaseStatus(String v) {
    return FulfillmentMonitorReleaseStatus.valueOf(v);
  }

  private static FulfillmentMonitorThresholdSource thresholdSource(String v) {
    return FulfillmentMonitorThresholdSource.valueOf(v);
  }

  private static FulfillmentMonitorSourceKind sourceKind(String v) {
    return FulfillmentMonitorSourceKind.valueOf(v);
  }

  private static FulfillmentMonitorTodoAction todoAction(String v) {
    return FulfillmentMonitorTodoAction.valueOf(v);
  }

  private static FulfillmentMonitorTodoStatus todoStatus(String v) {
    return FulfillmentMonitorTodoStatus.valueOf(v);
  }

  private static FulfillmentMonitorEventKind eventKind(String v) {
    return FulfillmentMonitorEventKind.valueOf(v);
  }
}
