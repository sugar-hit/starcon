package com.starcon.trade;

import static com.starcon.trade.FulfillmentRequestModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.3.3.1 履约请求：把「分仓 / 合单的结果」编排成一次可推进、可恢复、可重试的履约流程。
 *
 * <p><b>六条实现纪律</b>（照抄 9.3.2.1 / 9.3.2.2 的写法，不另起风格）：
 *
 * <ol>
 *   <li><b>状态机只有一处</b>：合法转移写在 {@link FulfillmentRequestStatus#allowedTargets()}，
 *       真正发起转移的只有 V152 第 10 节的四个 SECURITY DEFINER 窄函数 ——
 *       本类<b>不</b>直接 UPDATE 头行（DB 侧 UPDATE 权已 REVOKE 掉，物理上也做不到）。</li>
 *   <li><b>乐观并发在 DB 层</b>：{@code expectedVersion} 一路传到窄函数里比对，
 *       不在 Java 里先 if 一下就算数。</li>
 *   <li><b>断点恢复靠阶梯</b>：只有数清楚「current_step 之前的每步都有 COMPLETED 记录」
 *       才敢推进；DB 的延迟约束是这个不变量的唯一裁判。</li>
 *   <li><b>下游只登记、不假装</b>：预占 / 出库单的缺省状态是「待回执」，
 *       没有 warehouse 与 8.3.1 的回执就一步都不许往下走（V116 踩过的坑）。</li>
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 写在 values 的对应位置上。
 *       超时判定完全依赖时间正确性，用 {@code Timestamp.valueOf(LocalDateTime)}
 *       会静默偏 -28800s，把 deadline 提前 8 小时导致刚建单就超时。</li>
 *   <li><b>不用 JPA 懒加载</b>：全部 jOOQ 显式 SQL。</li>
 * </ol>
 *
 * <p><b>⭐ 同一行的时间戳只能来自同一个时钟源（V211 修复的缺陷）</b>：
 * 本模块<b>落库的每一个时间戳都取 DB 时钟</b>（{@code clock_timestamp()}），JVM 墙钟一个都不落库。
 * 旧实现里服务层用 JVM 墙钟写「请求侧」时刻（{@code requested_at} / {@code deadline_at} /
 * {@code last_step_at} / step 的 {@code started_at} / 事件的 {@code occurred_at}），
 * 而窄函数用 DB 时钟写「转移侧」时刻（{@code finished_at} / {@code acknowledged_at} /
 * {@code completed_at} / {@code blocked_at} …）—— 同一行的两个字段因此<b>分属两个时钟域</b>，
 * 表级约束 {@code CHECK (finished_at >= started_at)}、{@code CHECK (acknowledged_at >= requested_at)} 等
 * 会被两个时钟之间的几十微秒击穿，一次<b>完全合法</b>的履约步完成 / 预占回执会被 DB 随机拒掉，
 * 流程卡在 RUNNING / RESERVING。
 * 现在这一对（以及每一对）时间戳<b>都由 DB 时钟取</b>，{@code elapsed_seconds} 也由 DB 从这两个值算出来
 * ⇒ 三个字段在构造上就自洽。详见 {@code V211__trade_fulfillment_step_clock.sql}。
 */
@Service
public class FulfillmentRequestService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 数量刻度：与 DB 的 numeric(19,6) 对齐。 */
  private static final int QTY_SCALE = 6;

  /** 金额刻度：与 DB 的 numeric(19,4) 对齐。 */
  private static final int MONEY_SCALE = 4;

  /** 耗时刻度：与 DB 的 numeric(12,3) 对齐。 */
  private static final int SECONDS_SCALE = 3;

  /** 结算口径：统一 HALF_UP 到分。 */
  private static final int SETTLE_SCALE = 2;

  private static final BigDecimal ZERO_QTY = new BigDecimal("0.000000");

  /**
   * 头行列清单：列表、详情、版本回读共用同一份，避免两处写错。
   *
   * <p>⭐ 末列 {@code overdue} 是<b>算出来的</b>，而且在 SQL 里算：deadline_at 是 DB 时钟
   * 写下的时刻，「到没到点」就必须拿<b>同一个</b>时钟去比（V211）。
   */
  private static final String REQUEST_COLUMNS =
      "id, request_code, order_ref, order_line_ref, split_unit_ref, consolidation_ref,"
          + " allocation_decision_ref, routing_decision_ref, sku_ref, platform_code,"
          + " warehouse_ref, legal_entity_code, destination_country_code, destination_region_code,"
          + " requested_qty, estimated_handling_amount, currency_code, status, current_step,"
          + " process_version, attempt, requested_at, deadline_at, last_step_at, blocked_at,"
          + " blocked_reason_code, blocked_reason, completed_at, cancelled_at, cancel_reason, notes,"
          + " (deadline_at < clock_timestamp()) as overdue";

  private final DSLContext sql;
  private final Clock clock;

  /**
   * 时钟从容器里取：trade 服务里<b>已经有且只有一个</b> {@link Clock} Bean
   * （{@code ShipmentWritebackClockConfiguration} 声明的 {@code shipmentWritebackClock}），
   * 本模块复用它，<b>不</b>再声明第二个 —— 两个同类型 Bean 会让按类型注入变成歧义，
   * 整个 trade 服务会起不来。
   *
   * <p><b>本模块落库的每一个时间戳都取 DB 时钟</b>（{@code clock_timestamp()}），
   * JVM 墙钟一个都不落库（V211 修复的跨时钟域缺陷）。这个注入的时钟因此只剩两处用途：
   * <ol>
   *   <li>扫描超时这类**纯应用侧**的报告时刻（{@code scannedAt}）；</li>
   *   <li>让测试能把 JVM 时钟<b>钉到 DB 时钟之后</b>：跨时钟域的回归测试靠它
   *       确定性地制造「JVM 比 DB 快」的现场，并断言落库时刻<b>不受</b> JVM 影响 ——
   *       真实机器上 JVM 与 DB 只差几十微秒，没有这根把手就钉不住。</li>
   * </ol>
   */
  public FulfillmentRequestService(DSLContext sql, Clock clock) {
    this.sql = sql;
    this.clock = clock;
  }

  /** 当前时刻：走注入的 {@link Clock} 而不是 {@code OffsetDateTime.now()}，测试可钉死。 */
  private OffsetDateTime now() {
    return OffsetDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>三个 {@code false} 都是如实报出，且是本模块**最要紧**的三个事实：
   * 7.2 预占、8.3.1 出库单、10 物流下单分属别的服务的独立数据库，
   * 按 AGENTS.md 禁止跨库读写，本模块**只在本服务内登记下游指令**。
   *
   * <p>尤其 {@code reservesUpstreamStock=false}：9.3.2.1（V116）的证据里写着
   * 「本模块不预占 warehouse 库存，capabilities 显式 reservesUpstreamStock=false，
   * 否则下游落地时两边都以为占了同一批货」。本模块就是那个下游，所以这条纪律
   * 在这里同样成立 —— 界面不能因为看到「已锁定预占」就以为货真的锁在 warehouse 那边了。
   */
  public FulfillmentRequestCapabilities capabilities() {
    return new FulfillmentRequestCapabilities(
        List.of(
            new FulfillmentRequestDimension(
                "WAREHOUSE_ALLOCATION",
                "分仓结果（发货仓 + 分配数量）",
                true,
                "9.3.2.1 分仓规则（V116，本服务已交付）",
                null),
            new FulfillmentRequestDimension(
                "ORDER_CONSOLIDATION",
                "合单结果",
                true,
                "9.3.1.2 合单（V144，本服务已交付）",
                null),
            new FulfillmentRequestDimension(
                "ORDER_SPLIT",
                "拆单结果（多个履约单元）",
                false,
                "9.3.1.1 拆单（并行车道 V151，集成头尚未交付）",
                "拆单模块**尚未交付**，本表不存在，本模块既不建表也不读表："
                    + "只保留 split_unit_ref 逻辑引用列，不校验存在性。"
                    + "该车道落地后由它往本表补引用即可，本模块结构不需要改动。"),
            new FulfillmentRequestDimension(
                "FULFILLMENT_ROUTING",
                "履约路径（FBA / 自发货）",
                true,
                "9.3.2.2 FBA 与自发货选择（V92，本服务已交付）",
                null),
            new FulfillmentRequestDimension(
                "STOCK_RESERVATION",
                "锁定预占（7.2）",
                false,
                "7.2 库存预占（warehouse 服务，独立数据库）",
                "跨服务 SQL / FK / 视图被模块边界纪律禁止，故本模块只登记预占**指令**"
                    + "（状态 REQUESTED），收到 warehouse 回执才变 LOCKED。"
                    + "本模块**不写 warehouse 库存，也不声称货已锁住**。"),
            new FulfillmentRequestDimension(
                "OUTBOUND_ORDER",
                "出库单（8.3.1）",
                false,
                "8.3.1 出库单（warehouse 服务，独立数据库）",
                "同上游预占：只登记出库单指令与回执，不跨库建表、不 JOIN，"
                    + "也不假装已经把单派给 8.3.1。"),
            new FulfillmentRequestDimension(
                "LOGISTICS_ORDER",
                "物流下单（10）",
                false,
                "10 物流下单（独立服务）",
                "蓝图把 10 列进下游，但本模块的四步在出库单之后才轮到运单，"
                    + "运单主权在 9.4.1.1 与 8.3.4.2。本模块不自建运单表。"),
            new FulfillmentRequestDimension(
                "PROCESS_MONITORING",
                "跨请求异常监控与人工处置入口",
                false,
                "9.3.3.2 履约流程监控（未开发）",
                "本模块只做「本流程自己卡住了」的检出与原地重试；"
                    + "异常清单、人工处置、释放预占判给 9.3.3.2，本模块的 timeout 表是它的数据来源。")),
        false,
        false,
        false,
        true,
        false,
        List.of(FulfillmentRequestStatus.values()),
        List.of(FulfillmentStepKind.values()),
        List.of(FulfillmentBlockReason.values()),
        List.of(FulfillmentDetectionKind.values()),
        "流程状态持久化在 PostgreSQL 的 fulfillment_request 头行（status + current_step + "
            + "process_version 三件套），不是内存里的变量，所以进程重启后读库即可继续。"
            + "断点恢复 = 回到 current_step：DB 的延迟约束保证 current_step 之前的每一步"
            + "都有 COMPLETED 记录，所以「恢复到这一步」在数据库层面就等于「前面真的做完了」。"
            + "每一次推进 / 挂起 / 重试都必须带 expectedVersion（蓝图输入里点名的字段），"
            + "由 V152 的 SECURITY DEFINER 窄函数比对，不一致报 409。",
        "预占 / 出库单的缺省状态是 REQUESTED（待回执），不是 LOCKED / CONFIRMED。"
            + "本模块不写 warehouse 库存、不派单，只在本服务内登记带幂等键的下游指令，"
            + "等下游接上后按同一形状回填。将来对接时同一个 idempotencyKey 重放"
            + "不会让两边各锁一批货。",
        "本模块 = 编排一次履约（锁定预占 → 生成出库单 → 编排作业 → 跟踪完成）+ "
            + "断点恢复 + 超时重试。9.3.3.2 = 跨请求的异常清单、超时监控看板、人工处置入口"
            + "与释放预占。分界点是「本模块自己编排时能不能检出」："
            + "流程卡在自己的某一步 → 本模块；一堆流程超时要看、要人工处置 → 9.3.3.2。");
  }

  // ================================================================ 列表 / 详情

  /** 履约请求列表（真分页，total 与分页无关）。 */
  public FulfillmentRequestPage pageRequests(FulfillmentRequestQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page() == null ? 0 : query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.requestCode())) {
      where.add("request_code = ?");
      binds.add(query.requestCode().trim());
    }
    if (notBlank(query.orderRef())) {
      where.add("order_ref = ?");
      binds.add(query.orderRef().trim());
    }
    if (notBlank(query.warehouseRef())) {
      where.add("warehouse_ref = ?");
      binds.add(query.warehouseRef().trim());
    }
    if (notBlank(query.skuRef())) {
      where.add("sku_ref = ?");
      binds.add(query.skuRef().trim());
    }
    if (notBlank(query.platformCode())) {
      where.add("platform_code = ?");
      binds.add(query.platformCode().trim());
    }
    if (notBlank(query.splitUnitRef())) {
      where.add("split_unit_ref = ?");
      binds.add(query.splitUnitRef().trim());
    }
    if (notBlank(query.consolidationRef())) {
      where.add("consolidation_ref = ?");
      binds.add(query.consolidationRef().trim());
    }
    if (notBlank(query.allocationDecisionRef())) {
      where.add("allocation_decision_ref = ?");
      binds.add(query.allocationDecisionRef().trim());
    }
    if (notBlank(query.status())) {
      where.add("status = ?");
      binds.add(status(query.status()).name());
    }
    if (notBlank(query.currentStep())) {
      where.add("current_step = ?");
      binds.add(stepKind(query.currentStep()).name());
    }
    if (Boolean.TRUE.equals(query.blockedOnly())) {
      where.add("status = 'BLOCKED'");
    }
    // overdue 是**实时**判定（拿 DB 时钟比 deadline），不落库 ——
    // 落一个 overdue 布尔列就得每秒刷一次，而它只对「此刻」有意义。
    // ⭐ 用 clock_timestamp() 而不是 now()：now() 是**事务开始时刻**，
    //   长事务里它会停在事务开头，扫出来的「已超时」清单会过期（V152 文件头同一条纪律）。
    if (Boolean.TRUE.equals(query.overdueOnly())) {
      where.add("deadline_at < clock_timestamp() and status not in ('COMPLETED','CANCELLED')");
    }
    if (notBlank(query.keyword())) {
      where.add(
          "(request_code ilike ? or order_ref ilike ? or sku_ref ilike ? or notes ilike ?"
              + " or blocked_reason ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 5; i++) {
        binds.add(k);
      }
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne("select count(*) c from fulfillment_request" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<FulfillmentRequestRow> items =
        sql.fetch(
                "select " + REQUEST_COLUMNS + " from fulfillment_request" + sqlWhere
                    + " order by requested_at desc, id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(this::requestRow);
    return new FulfillmentRequestPage(items, total, pageNo, size);
  }

  /** 可恢复清单：全部 BLOCKED（断点恢复入口），按挂起时间倒序。 */
  public FulfillmentRequestResumablePage pageResumable(int rawPage, int rawSize) {
    int size = pageSize(rawSize);
    int pageNo = Math.max(0, rawPage);
    String where = " where status = 'BLOCKED'";
    long total =
        sql.fetchOne("select count(*) c from fulfillment_request" + where).get("c", Long.class);
    List<FulfillmentRequestRow> items =
        sql.fetch(
                "select " + REQUEST_COLUMNS + " from fulfillment_request" + where
                    + " order by blocked_at desc nulls last, id limit ? offset ?",
                size,
                (long) pageNo * size)
            .map(this::requestRow);
    return new FulfillmentRequestResumablePage(items, total, pageNo, size);
  }

  /** 履约请求详情：头 + 步骤 + 预占 + 出库单 + 作业 + 超时 + 事件（蓝图四项输出一次给全）。 */
  public FulfillmentRequestDetail request(UUID id) {
    var head =
        sql.fetchOne("select " + REQUEST_COLUMNS + " from fulfillment_request where id = ?", id);
    if (head == null) {
      throw notFound(id);
    }
    return new FulfillmentRequestDetail(
        requestRow(head),
        steps(id),
        reservations(id),
        outboundOrders(id),
        workOrders(id),
        timeouts(id),
        events(id));
  }

  // ================================================================ 建单

  /**
   * 建一张履约请求。
   *
   * <p>幂等：{@code idempotencyKey} 相同则**返回既有那张**而不是报错 ——
   * 09 卷关键约束「重复 webhook / 重复事件只产生一张单」在这里同样成立，
   * 重放应该无事发生，而不是让上游拿到一个 500 然后重试三次。
   */
  @Transactional
  public FulfillmentRequestSaved create(FulfillmentRequestInput input, UUID actor) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    BigDecimal qty = qty(input.requestedQty(), "requestedQty");
    String country =
        notBlank(input.destinationCountryCode())
            ? input.destinationCountryCode().trim().toUpperCase(Locale.ROOT)
            : "";
    if (country.length() != 2) {
      throw bad("目的国必须是两位国家码");
    }
    String sku = nz(input.skuRef());
    String warehouse = nz(input.warehouseRef());
    String entity = nz(input.legalEntityCode());
    if (sku.isBlank() || warehouse.isBlank() || entity.isBlank()) {
      throw bad("skuRef / warehouseRef / legalEntityCode 都不能为空：不知道发哪个 SKU、哪个仓、"
          + "货记在哪个法人名下，就等于让财务替你做一个错误决定");
    }
    // 至少要有一个上游引用：履约请求是「拆合单 / 分仓的结果」。
    if (nz(input.splitUnitRef()).isBlank()
        && nz(input.consolidationRef()).isBlank()
        && nz(input.allocationDecisionRef()).isBlank()
        && nz(input.routingDecisionRef()).isBlank()) {
      throw bad("splitUnitRef / consolidationRef / allocationDecisionRef / routingDecisionRef "
          + "至少要填一个：履约请求是上游拆合单与分仓的结果，四者皆空说明调用方"
          + "不知道自己为什么要发这批货");
    }
    String idempotency = notBlank(input.idempotencyKey())
        ? input.idempotencyKey().trim()
        : "auto:" + input.requestCode().trim();
    var existing =
        sql.fetchOne(
            "select id, request_code, status, current_step, process_version, attempt,"
                + " requested_at, deadline_at"
                + " from fulfillment_request where idempotency_key = ?",
            idempotency);
    if (existing != null) {
      return saved(existing, input.requestCode().trim());
    }

    BigDecimal amount = null;
    if (input.estimatedHandlingAmount() != null) {
      amount = money(input.estimatedHandlingAmount(), "estimatedHandlingAmount");
    }
    String currency =
        notBlank(input.currencyCode())
            ? input.currencyCode().trim().toUpperCase(Locale.ROOT)
            : "USD";
    int reserveTimeout = timeout(input.reserveTimeoutSeconds(), "reserveTimeoutSeconds",
        FulfillmentStepKind.RESERVE);
    int outboundTimeout = timeout(input.outboundTimeoutSeconds(), "outboundTimeoutSeconds",
        FulfillmentStepKind.OUTBOUND);
    int workTimeout = timeout(input.workTimeoutSeconds(), "workTimeoutSeconds",
        FulfillmentStepKind.WORK);
    int trackTimeout = timeout(input.trackTimeoutSeconds(), "trackTimeoutSeconds",
        FulfillmentStepKind.TRACK);

    UUID id = UUID.randomUUID();
    // ⭐ requested_at / deadline_at / last_step_at 三个时刻**由 DB 时钟取**（同一个
    //   clock_timestamp() 值 ts），不再从 JVM 绑定。
    //   为什么必须这样：requested_at 会被 DB 侧的 completed_at / cancelled_at / blocked_at
    //   与表级约束 CHECK (completed_at >= requested_at) 比较 —— 那三个是窄函数用
    //   clock_timestamp() 写的，requested_at 若来自 JVM，两者是**两个时钟域**，
    //   JVM 快几十微秒就违约，一次合法的完成/取消/挂起会被 DB 拒掉
    //   （V211 修复的缺陷，详见类注释与 V211 文件头）。
    //   deadline_at 必须是 requested_at + 阈值（现有断言就钉着这条），所以两者取**同一个** ts。
    // ⚠ ?::timestamptz 必须写在 values 的**对应位置**上，cast 不出现在绑定序列末位。
    var inserted =
        sql.fetchOne(
            "with t as (select clock_timestamp() as ts)"
                + " insert into fulfillment_request ("
                + "id, tenant_id, request_code, order_ref, order_line_ref, split_unit_ref, consolidation_ref, allocation_decision_ref, routing_decision_ref, sku_ref, platform_code, warehouse_ref, legal_entity_code, destination_country_code, destination_region_code, requested_qty, estimated_handling_amount, currency_code, status, current_step, process_version, attempt, reserve_timeout_seconds, outbound_timeout_seconds, work_timeout_seconds, track_timeout_seconds, idempotency_key, requested_at, deadline_at, last_step_at, notes, created_by_membership_id"
                + ") select "
                + "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,1,?,?,?,?,?,"
                + "ts,ts + make_interval(secs => ?::double precision),ts,?,?"
                + " from t returning requested_at, deadline_at",
            id,
            tenantId(),
            requestCode(input),
            nz(input.orderRef()),
            nz(input.orderLineRef()),
            nz(input.splitUnitRef()),
            nz(input.consolidationRef()),
            nz(input.allocationDecisionRef()),
            nz(input.routingDecisionRef()),
            sku,
            nz(input.platformCode()),
            warehouse,
            entity,
            country,
            notBlank(input.destinationRegionCode()) ? input.destinationRegionCode().trim() : "*",
            qty,
            amount,
            currency,
            FulfillmentRequestStatus.DRAFT.name(),
            FulfillmentStepKind.RESERVE.name(),
            reserveTimeout,
            outboundTimeout,
            workTimeout,
            trackTimeout,
            idempotency,
            reserveTimeout,
            nz(input.notes()),
            actor);
    // 回执里的时刻是**库里写下的**那一份，不是服务层自己算的那一份（两者可能差几十微秒）。
    OffsetDateTime requestedAt = inserted.get("requested_at", OffsetDateTime.class);
    OffsetDateTime deadlineAt = inserted.get("deadline_at", OffsetDateTime.class);
    event(id, FulfillmentEventKind.CREATED, "", FulfillmentRequestStatus.DRAFT.name(),
        "建单，履约单元从 " + nz(input.splitUnitRef(), "—") + " / "
            + nz(input.allocationDecisionRef(), "—") + " 来，发货仓 " + warehouse,
        actor, 0);
    return new FulfillmentRequestSaved(
        id,
        requestCode(input),
        FulfillmentRequestStatus.DRAFT,
        FulfillmentStepKind.RESERVE,
        0L,
        1,
        requestedAt,
        deadlineAt);
  }

  // ================================================================ 推进 / 挂起 / 重试 / 取消

  /**
   * 推进状态。
   *
   * <p>合法性有<b>三层</b>把关，缺一不可：Java 侧 {@code allowedTargets()} 给出可读的错误，
   * DB 侧第 8.1 节白名单钉住（绕过服务层直接写 SQL 也进不去），
   * 延迟约束再校验「前置步骤真的完成过」。任何一层单独存在都会被绕过。
   */
  @Transactional
  public FulfillmentRequestTransitionResult advance(
      UUID id, FulfillmentRequestStatusInput input, UUID actor) {
    if (input == null || input.expectedVersion() == null) {
      throw bad("expectedVersion 是蓝图输入里点名的字段，必须带");
    }
    var head = requireHead(id);
    long version = input.expectedVersion();
    FulfillmentRequestStatus target = input.status();
    FulfillmentStepKind step = input.currentStep();

    guardVersion(head, version);
    if (head.status().terminal()) {
      throw bad("履约请求当前状态「" + head.status().label() + "」是终态，不允许推进到「"
          + target.label() + "」：重发请新建一条履约请求");
    }
    if (target == FulfillmentRequestStatus.BLOCKED) {
      throw bad("挂起请走 block 操作：要同时落一条 timeout 凭据与挂起原因，"
          + "否则界面上会出现一个说不清为什么卡住的流程");
    }
    if (!head.status().allowedTargets().contains(target)) {
      throw bad("履约请求状态不能从「" + head.status().label() + "」直接改到「" + target.label()
          + "」：跳过步骤的流程一旦断电就永远接不回来");
    }
    if (target == FulfillmentRequestStatus.COMPLETED && step != FulfillmentStepKind.TRACK) {
      throw bad("跟踪完成必须站在阶梯顶端（currentStep=TRACK）");
    }
    // 从挂起恢复时，落到的状态必须与断点自洽：这一步没做完就不许标成完成的那一步。
    if (head.status() == FulfillmentRequestStatus.BLOCKED && target != FulfillmentRequestStatus.CANCELLED
        && step.resumeStatus() != target) {
      throw bad("从卡住恢复时应落到「" + step.resumeStatus().label() + "」，收到「" + target.label()
          + "」：currentStep=" + step.name() + " 的流程还没做完这一步");
    }
    int timeout =
        input.stepTimeoutSeconds() == null || input.stepTimeoutSeconds() <= 0
            ? head.stepTimeoutSeconds(step)
            : input.stepTimeoutSeconds();

    sql.execute(
        "select advance_fulfillment_request_status(?,?,?,?,?,?)",
        id,
        target.name(),
        step.name(),
        version,
        actor,
        timeout);
    var after = requireHead(id);
    FulfillmentEventKind kind = eventKindFor(target);
    event(id, kind, head.status().name(), target.name(),
        "推进到「" + target.label() + "」（第 " + after.attempt() + " 次尝试，版本 "
            + after.processVersion() + "）", actor, after.processVersion());
    return transition(after);
  }

  /** 挂起：超时 / 步骤失败 / 回执陈旧。必然落一条 timeout 凭据。 */
  @Transactional
  public FulfillmentRequestTransitionResult block(
      UUID id, FulfillmentRequestBlockInput input, UUID actor) {
    if (input == null || input.expectedVersion() == null) {
      throw bad("expectedVersion 必须带");
    }
    if (nz(input.reason()).isBlank()) {
      throw bad("挂起必须写明原因：超时不是失败，说清卡在哪才能决定重试还是释放预占");
    }
    var head = requireHead(id);
    guardVersion(head, input.expectedVersion());
    if (head.status().terminal()) {
      throw bad("履约请求当前状态「" + head.status().label() + "」是终态，不允许挂起");
    }
    if (head.status() == FulfillmentRequestStatus.BLOCKED) {
      throw bad("履约请求已经挂起了，要恢复请走 retry：同一个流程不能挂起两次");
    }
    if (!head.status().allowedTargets().contains(FulfillmentRequestStatus.BLOCKED)) {
      throw bad("当前状态「" + head.status().label() + "」不允许挂起");
    }
    if (input.stepKind() != head.currentStep()) {
      throw bad("挂起只能记在当前步「" + head.currentStep().label() + "」上，收到「"
          + input.stepKind().label() + "」：否则复盘会说流程卡在了它其实已经做完的那一步");
    }
    sql.execute(
        "select block_fulfillment_request(?,?,?,?,?,?,?,?)",
        id,
        input.stepKind().name(),
        input.detectionKind().name(),
        input.reasonCode().name(),
        input.reason().trim(),
        input.expectedVersion(),
        actor,
        UUID.randomUUID());
    var after = requireHead(id);
    event(id, FulfillmentEventKind.BLOCKED, head.status().name(), after.status().name(),
        "挂在「" + input.stepKind().label() + "」步：" + input.reason().trim()
            + "（" + input.detectionKind().label() + "）", actor, after.processVersion());
    return transition(after);
  }

  /**
   * 重试 / 断点恢复：从 current_step 原地重来。
   *
   * <p>「先查证再决定重试还是释放预占」的责任在调用方（人工或 9.3.3.2）；
   * 本方法只保证重试是<b>显式</b>动作、带版本校验、从断点而不是从头开始，
   * 并把理由写进 notes —— 没有理由的重试会变成重复发货。
   */
  @Transactional
  public FulfillmentRequestTransitionResult retry(
      UUID id, FulfillmentRequestRetryInput input, UUID actor) {
    if (input == null || input.expectedVersion() == null) {
      throw bad("expectedVersion 必须带");
    }
    if (nz(input.resumeReason()).isBlank()) {
      throw bad("重试必须写明为什么重试：蓝图说「超时不是失败」，没有理由的重试会变成重复发货");
    }
    var head = requireHead(id);
    guardVersion(head, input.expectedVersion());
    if (head.status() != FulfillmentRequestStatus.BLOCKED) {
      throw bad("只有挂起的履约请求才需要重试，当前状态是「" + head.status().label() + "」："
          + "没卡住就不叫重试");
    }
    FulfillmentStepKind step = head.currentStep();
    FulfillmentRequestStatus resume = step.resumeStatus();
    if (!head.status().allowedTargets().contains(resume)) {
      throw bad("当前状态不允许恢复到「" + resume.label() + "」");
    }
    sql.execute(
        "select retry_fulfillment_request(?,?,?,?)",
        id,
        input.expectedVersion(),
        actor,
        input.resumeReason().trim());
    var after = requireHead(id);
    event(id, FulfillmentEventKind.RETRY_REQUESTED, FulfillmentRequestStatus.BLOCKED.name(),
        resume.name(), "从断点「" + step.label() + "」重试（第 " + after.attempt() + " 次尝试）："
            + input.resumeReason().trim(), actor, after.processVersion());
    event(id, FulfillmentEventKind.RESUMED, FulfillmentRequestStatus.BLOCKED.name(),
        resume.name(), "流程状态已持久化恢复，重启后可从 " + step.name() + " 继续", actor,
        after.processVersion());
    return transition(after);
  }

  /** 取消（终态，必须带原因）。 */
  @Transactional
  public FulfillmentRequestTransitionResult cancel(
      UUID id, FulfillmentRequestCancelInput input, UUID actor) {
    if (input == null || input.expectedVersion() == null) {
      throw bad("expectedVersion 必须带");
    }
    if (nz(input.reason()).isBlank()) {
      throw bad("取消履约流程必须写明原因：取消等于把货停在半路，不写理由等于查不出谁停的");
    }
    var head = requireHead(id);
    guardVersion(head, input.expectedVersion());
    if (head.status().terminal()) {
      throw bad("履约请求当前状态「" + head.status().label() + "」是终态，不允许取消");
    }
    if (!head.status().allowedTargets().contains(FulfillmentRequestStatus.CANCELLED)) {
      throw bad("当前状态「" + head.status().label() + "」不允许取消");
    }
    sql.execute(
        "select cancel_fulfillment_request(?,?,?,?)",
        id,
        input.reason().trim(),
        input.expectedVersion(),
        actor);
    var after = requireHead(id);
    event(id, FulfillmentEventKind.CANCELLED, head.status().name(),
        FulfillmentRequestStatus.CANCELLED.name(), input.reason().trim(), actor,
        after.processVersion());
    return transition(after);
  }

  // ================================================================ 预占指令（7.2）

  /**
   * 发出锁定预占指令（当前步 = RESERVE）。
   *
   * <p>登记的是<b>指令</b>，状态 REQUESTED。<b>没有 warehouse 的回执就不算锁住</b> ——
   * 这是 9.3.2.1（V116）踩过并写进证据的坑。
   */
  @Transactional
  public FulfillmentRequestReservationRow requestReservation(
      UUID id, FulfillmentRequestReservationInput input, UUID actor) {
    var head = requireHead(id);
    requireStep(head, FulfillmentStepKind.RESERVE, FulfillmentRequestStatus.RESERVING);
    UUID reservationId = UUID.randomUUID();
    String key = notBlank(input == null ? null : input.idempotencyKey())
        ? input.idempotencyKey().trim()
        : "reservation:" + id + ":" + head.attempt();
    // ⭐ requested_at 由 DB 时钟取：它会被回执侧的 acknowledged_at / released_at
    //   与表级约束 CHECK (acknowledged_at >= requested_at) 比较，
    //   而那两个是窄函数用 clock_timestamp() 写的 ⇒ 必须同属一个时钟域（V211）。
    sql.execute(
        "insert into fulfillment_request_reservation (id, tenant_id, request_id, warehouse_ref,"
            + " sku_ref, requested_qty, status, idempotency_key, attempt,"
            + " requested_at, created_by_membership_id)"
            + " values (?,?,?,?,?,?,'REQUESTED',?,?,clock_timestamp(),?)",
        reservationId,
        tenantId(),
        id,
        head.warehouseRef(),
        head.skuRef(),
        head.requestedQty(),
        key,
        head.attempt(),
        actor);
    markStepRunning(id, FulfillmentStepKind.RESERVE, head.attempt(), actor);
    event(id, FulfillmentEventKind.RESERVATION_REQUESTED, head.status().name(),
        head.status().name(), "向 7.2 登记预占指令：" + head.requestedQty() + " 件，发货仓 "
            + head.warehouseRef() + "（尚未锁定，等 warehouse 回执）", actor, head.processVersion());
    return reservation(id, head.attempt());
  }

  /** 登记 7.2 的预占回执（LOCKED / REJECTED / RELEASED）。 */
  @Transactional
  public FulfillmentRequestReservationRow acknowledgeReservation(
      UUID id, FulfillmentRequestReservationAckInput input, UUID actor) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    var head = requireHead(id);
    var row =
        sql.fetchOne(
            "select id, status from fulfillment_request_reservation"
                + " where request_id = ? and attempt = ? order by created_at desc limit 1",
            id,
            head.attempt());
    if (row == null) {
      throw bad("履约请求 " + id + " 的第 " + head.attempt() + " 次尝试还没有发出预占指令");
    }
    if (input.status() == FulfillmentReservationStatus.LOCKED
        && (nz(input.reservationRef()).isBlank() || input.lockedQty() == null)) {
      throw bad("登记「已锁定预占」必须给 warehouse 的凭据与锁定数量：没回执就不许说货锁住了");
    }
    if (input.status() == FulfillmentReservationStatus.LOCKED
        && input.lockedQty().compareTo(head.requestedQty()) > 0) {
      throw bad("锁定数量 " + input.lockedQty() + " 超过需求量 " + head.requestedQty()
          + "：锁多了就是超发，正是 9.3 要解决的问题");
    }
    if (input.status() == FulfillmentReservationStatus.REJECTED
        && nz(input.rejectReason()).isBlank()) {
      throw bad("登记「预占被拒」必须写明原因：不知道为什么被拒，下游就无法补货或改路径");
    }
    if (input.status() == FulfillmentReservationStatus.RELEASED
        && nz(input.rejectReason()).isBlank()) {
      throw bad("登记「预占已释放」必须写明原因");
    }
    sql.execute(
        "select ack_fulfillment_reservation(?,?,?,?,?)",
        row.get("id", UUID.class),
        input.status().name(),
        nz(input.reservationRef()),
        input.lockedQty(),
        nz(input.rejectReason()));
    var after = requireHead(id);
    if (input.status() == FulfillmentReservationStatus.LOCKED) {
      completeStep(id, FulfillmentStepKind.RESERVE, head.attempt(), row.get("id", UUID.class),
          null, null, actor);
      event(id, FulfillmentEventKind.RESERVATION_LOCKED, head.status().name(),
          head.status().name(), "warehouse 回执：已锁定 " + input.lockedQty() + " 件（凭据 "
              + input.reservationRef() + "）", actor, after.processVersion());
    } else if (input.status() == FulfillmentReservationStatus.REJECTED) {
      event(id, FulfillmentEventKind.RESERVATION_REJECTED, head.status().name(),
          head.status().name(), "warehouse 回执：预占被拒 —— " + nz(input.rejectReason()), actor,
          after.processVersion());
    } else {
      event(id, FulfillmentEventKind.NOTE, head.status().name(), head.status().name(),
          "预占已释放：" + nz(input.rejectReason()), actor, after.processVersion());
    }
    return reservation(id, head.attempt());
  }

  // ================================================================ 出库单指令（8.3.1）

  /** 发出库单指令（当前步 = OUTBOUND）。同样只登记，不假装已经派单。 */
  @Transactional
  public FulfillmentRequestOutboundRow requestOutboundOrder(
      UUID id, FulfillmentRequestOutboundInput input, UUID actor) {
    var head = requireHead(id);
    requireStep(head, FulfillmentStepKind.OUTBOUND, FulfillmentRequestStatus.OUTBOUND_PENDING);
    UUID outboundId = UUID.randomUUID();
    String key = notBlank(input == null ? null : input.idempotencyKey())
        ? input.idempotencyKey().trim()
        : "outbound:" + id + ":" + head.attempt();
    // ⭐ requested_at 由 DB 时钟取：它会被回执侧的 acknowledged_at 与表级约束
    //   CHECK (acknowledged_at >= requested_at) 比较，而 acknowledged_at 是窄函数
    //   用 clock_timestamp() 写的 ⇒ 必须同属一个时钟域（V211）。
    sql.execute(
        "insert into fulfillment_request_outbound_order (id, tenant_id, request_id,"
            + " warehouse_ref, outbound_qty, status, package_ref, idempotency_key, attempt,"
            + " requested_at, created_by_membership_id)"
            + " values (?,?,?,?,?,'REQUESTED',?,?,?,clock_timestamp(),?)",
        outboundId,
        tenantId(),
        id,
        head.warehouseRef(),
        head.requestedQty(),
        nz(input == null ? null : input.packageRef()),
        key,
        head.attempt(),
        actor);
    markStepRunning(id, FulfillmentStepKind.OUTBOUND, head.attempt(), actor);
    event(id, FulfillmentEventKind.OUTBOUND_REQUESTED, head.status().name(), head.status().name(),
        "向 8.3.1 登记出库单指令：" + head.requestedQty() + " 件，发货仓 " + head.warehouseRef()
            + "（尚未确认，等下游回执）", actor, head.processVersion());
    return outbound(id, head.attempt());
  }

  /** 登记 8.3.1 的出库单回执（CONFIRMED / REJECTED / CANCELLED）。 */
  @Transactional
  public FulfillmentRequestOutboundRow acknowledgeOutboundOrder(
      UUID id, FulfillmentRequestOutboundAckInput input, UUID actor) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    var head = requireHead(id);
    var row =
        sql.fetchOne(
            "select id, status from fulfillment_request_outbound_order"
                + " where request_id = ? and attempt = ? order by created_at desc limit 1",
            id,
            head.attempt());
    if (row == null) {
      throw bad("履约请求 " + id + " 的第 " + head.attempt() + " 次尝试还没有发出库单指令");
    }
    if (input.status() == FulfillmentOutboundStatus.CONFIRMED
        && nz(input.outboundRef()).isBlank()) {
      throw bad("登记「出库单已确认」必须给出 8.3.1 开的单号：没有单号的「已出库」"
          + "无法交接、无法回传平台");
    }
    if (input.status() != FulfillmentOutboundStatus.CONFIRMED
        && nz(input.rejectReason()).isBlank()) {
      throw bad("出库单被拒 / 取消必须写明原因：查不出为什么没出库，下一次还会再拒一次");
    }
    sql.execute(
        "select ack_fulfillment_outbound_order(?,?,?,?,?)",
        row.get("id", UUID.class),
        input.status().name(),
        nz(input.outboundRef()),
        nz(input.packageRef()),
        nz(input.rejectReason()));
    var after = requireHead(id);
    if (input.status() == FulfillmentOutboundStatus.CONFIRMED) {
      completeStep(id, FulfillmentStepKind.OUTBOUND, head.attempt(), null, row.get("id", UUID.class),
          null, actor);
      event(id, FulfillmentEventKind.OUTBOUND_CONFIRMED, head.status().name(), head.status().name(),
          "8.3.1 回执：出库单 " + input.outboundRef() + " 已确认", actor, after.processVersion());
    } else if (input.status() == FulfillmentOutboundStatus.REJECTED) {
      event(id, FulfillmentEventKind.OUTBOUND_REJECTED, head.status().name(), head.status().name(),
          "8.3.1 回执：出库单被拒 —— " + nz(input.rejectReason()), actor, after.processVersion());
    } else {
      event(id, FulfillmentEventKind.NOTE, head.status().name(), head.status().name(),
          "出库单已取消：" + nz(input.rejectReason()), actor, after.processVersion());
    }
    return outbound(id, head.attempt());
  }

  // ================================================================ 跟踪完成（TRACK 步）

  /**
   * 登记「跟踪完成」这一步并把流程收尾。
   *
   * <p>刻意做成一个独立操作而不是「推进到 COMPLETED 时顺手记一步」：跟踪完成的
   * **凭据**和**状态**必须同一条事务里落，否则会出现「状态说完成了但没有任何完成记录」
   * 或者反过来。9.4 发货回传拿到履约请求时，看的就是这一步。
   */
  @Transactional
  public FulfillmentRequestTransitionResult trackComplete(
      UUID id, FulfillmentRequestRetryInput input, UUID actor) {
    if (input == null || input.expectedVersion() == null) {
      throw bad("expectedVersion 必须带");
    }
    var head = requireHead(id);
    guardVersion(head, input.expectedVersion());
    if (head.status() != FulfillmentRequestStatus.WORKING) {
      throw bad("只有「作业编排中」的履约请求才能跟踪完成，当前状态是「" + head.status().label() + "」");
    }
    // 至少要有一条真的干完的作业，否则「跟踪完成」跟踪的是空气
    var done =
        sql.fetchOne(
                "select count(*) as c from fulfillment_request_work_order"
                    + " where request_id = ? and status in ('DONE','SKIPPED')",
                id)
            .get("c", Long.class);
    if (done == null || done == 0L) {
      throw bad("一条编排作业都还没完成，不能跟踪完成："
          + "「派了活」不等于「干完了活」");
    }
    markStepRunning(id, FulfillmentStepKind.TRACK, head.attempt(), actor);
    completeStep(id, FulfillmentStepKind.TRACK, head.attempt(), null, null, done.intValue(), actor);
    sql.execute(
        "select advance_fulfillment_request_status(?,?,?,?,?,?)",
        id,
        FulfillmentRequestStatus.COMPLETED.name(),
        FulfillmentStepKind.TRACK.name(),
        input.expectedVersion(),
        actor,
        head.stepTimeoutSeconds(FulfillmentStepKind.TRACK));
    var after = requireHead(id);
    event(id, FulfillmentEventKind.COMPLETED, FulfillmentRequestStatus.WORKING.name(),
        FulfillmentRequestStatus.COMPLETED.name(),
        "跟踪完成：" + done + " 条作业已收尾（" + nz(input.resumeReason()) + "）", actor,
        after.processVersion());
    return transition(after);
  }

  // ================================================================ 编排作业

  /** 批量编排作业（当前步 = WORK）。同一个请求可有多条作业。 */
  @Transactional
  public List<FulfillmentRequestWorkOrderRow> assignWorkOrders(
      UUID id, FulfillmentRequestWorkOrderInput input, UUID actor) {
    var head = requireHead(id);
    requireStep(head, FulfillmentStepKind.WORK, FulfillmentRequestStatus.WORKING);
    if (input == null || input.tasks() == null || input.tasks().isEmpty()) {
      throw bad("至少要编排一条作业：说在干活却没有任何活可干");
    }
    for (FulfillmentRequestWorkTask task : input.tasks()) {
      BigDecimal qty = qty(task.qty(), "qty");
      if (qty.compareTo(head.requestedQty()) > 0) {
        throw bad("作业 " + task.workOrderCode() + " 的数量 " + qty + " 超过履约请求的需求量 "
            + head.requestedQty() + "：一次履约请求对应一个履约单元，不许超发");
      }
      String key =
          notBlank(task.idempotencyKey())
              ? task.idempotencyKey().trim()
              : "work:" + id + ":" + task.workOrderCode().trim();
      var dup =
          sql.fetchOne(
              "select id from fulfillment_request_work_order where idempotency_key = ?", key);
      if (dup != null) {
        // 重放编排不报错也不重复建单 —— 幂等键相同就是同一件事。
        continue;
      }
      sql.execute(
          "insert into fulfillment_request_work_order (id, tenant_id, request_id,"
              + " work_order_code, task_type, warehouse_ref, sku_ref, qty, status,"
              + " idempotency_key, attempt, created_by_membership_id)"
              + " values (?,?,?,?,?,?,?,?,'ASSIGNED',?,?,?)",
          UUID.randomUUID(),
          tenantId(),
          id,
          nz(task.workOrderCode()),
          task.taskType().name(),
          nz(task.warehouseRef()),
          nz(task.skuRef()),
          qty,
          key,
          head.attempt(),
          actor);
    }
    markStepRunning(id, FulfillmentStepKind.WORK, head.attempt(), actor);
    event(id, FulfillmentEventKind.WORK_ASSIGNED, head.status().name(), head.status().name(),
        "编排 " + input.tasks().size() + " 条作业", actor, head.processVersion());
    return workOrders(id);
  }

  /** 登记单条作业状态（RUNNING / DONE / FAILED / SKIPPED）。 */
  @Transactional
  public FulfillmentRequestWorkOrderRow updateWorkOrder(
      UUID id, UUID workOrderId, FulfillmentRequestWorkOrderStatusInput input, UUID actor) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    requireHead(id);
    var row = sql.fetchOne("select status from fulfillment_request_work_order where id = ?",
        workOrderId);
    if (row == null) {
      throw notFound(workOrderId);
    }
    if (FulfillmentWorkStatus.valueOf(row.get("status", String.class)).terminal()) {
      throw bad("这条作业已经是终态，不允许再改");
    }
    if (row.get("status", String.class).equals("ASSIGNED")
        && input.status() == FulfillmentWorkStatus.DONE) {
      throw bad("作业还停在「已派工」，必须先登记 RUNNING 才能置为「已完成」："
          + "少了 RUNNING 就答不出「这活什么时候开始干的」，9.5 的时效分析没有依据");
    }
    if (input.status() == FulfillmentWorkStatus.FAILED && nz(input.failureReason()).isBlank()) {
      throw bad("作业失败必须写明原因：9.3.3.2 的处置入口要靠它决定换人还是改路径");
    }
    sql.execute(
        "select complete_fulfillment_work_order(?,?,?,?)",
        workOrderId,
        input.status().name(),
        nz(input.assigneeRef()),
        nz(input.failureReason()));
    var after = requireHead(id);
    if (input.status() == FulfillmentWorkStatus.DONE) {
      // WORK 步只在**全部**作业都收尾后才算完成：第一条 DONE 就把整步标完成，
      // 剩下三条还在跑，界面上就会显示「作业编排已完成」而实际没干完。
      var open =
          sql.fetchOne(
                  "select count(*) as c from fulfillment_request_work_order"
                      + " where request_id = ? and status in ('ASSIGNED','RUNNING')",
                  id)
              .get("c", Long.class);
      if (open == 0L) {
        long done =
            sql.fetchOne(
                    "select count(*) as c from fulfillment_request_work_order"
                        + " where request_id = ? and status in ('DONE','SKIPPED')",
                    id)
                .get("c", Long.class);
        completeStep(id, FulfillmentStepKind.WORK, after.attempt(), null, null,
            (int) done, actor);
      }
      event(id, FulfillmentEventKind.WORK_DONE, after.status().name(), after.status().name(),
          "作业已完成", actor, after.processVersion());
    } else if (input.status() == FulfillmentWorkStatus.FAILED) {
      event(id, FulfillmentEventKind.WORK_FAILED, after.status().name(), after.status().name(),
          "作业失败：" + nz(input.failureReason()), actor, after.processVersion());
    } else {
      event(id, FulfillmentEventKind.NOTE, after.status().name(), after.status().name(),
          "作业进入「" + input.status().label() + "」", actor, after.processVersion());
    }
    return sql.fetchOne(
            "select id, work_order_code, task_type, warehouse_ref, sku_ref, qty, status,"
                + " assignee_ref, started_at, finished_at, failure_reason, attempt"
                + " from fulfillment_request_work_order where id = ?",
            workOrderId)
        .map(FulfillmentRequestService::workOrderRow);
  }

  // ================================================================ 超时扫描

  /**
   * 扫描已超过当前步 deadline 的流程并挂起（超时重试的事实侧）。
   *
   * <p>逐条处理且逐条报出挂起 / 跳过：一次扫描给一个「处理了 N 条」的总数，
   * 调用方无法知道哪几条被跳过了 —— 而跳过的原因（已终态 / 未到阈值 / 版本被并发改过）
   * 恰恰是排查时最需要的。
   */
  @Transactional
  public FulfillmentRequestScanResult scanTimeouts(FulfillmentRequestScanInput input, UUID actor) {
    int limit = input == null || input.limit() == null ? 100 : Math.min(Math.max(input.limit(), 1), 500);
    FulfillmentStepKind only = input == null ? null : input.stepKind();
    List<Object> binds = new ArrayList<>();
    String filter = only == null ? "" : " and current_step = ?";
    if (only != null) {
      binds.add(only.name());
    }
    var due =
        sql.fetch(
            "select id, request_code, current_step, process_version, deadline_at,"
                + " round(extract(epoch from (clock_timestamp() - deadline_at))::numeric, 3)"
                + " as overdue_seconds"
                + " from fulfillment_request"
                + " where status not in ('COMPLETED','CANCELLED','BLOCKED')"
                + " and deadline_at < clock_timestamp()" + filter
                + " order by deadline_at limit ?",
            append(binds, limit));
    List<FulfillmentRequestScanResult.FulfillmentRequestBlocked> blocked = new ArrayList<>();
    List<FulfillmentRequestScanResult.FulfillmentRequestSkipped> skipped = new ArrayList<>();
    for (Record row : due) {
      UUID id = row.get("id", UUID.class);
      FulfillmentStepKind step = FulfillmentStepKind.valueOf(row.get("current_step", String.class));
      long version = row.get("process_version", Long.class);
      FulfillmentBlockReason reason = timeoutReason(step);
      var blockInput =
          new FulfillmentRequestBlockInput(
              step, FulfillmentDetectionKind.TIMEOUT, reason,
              step.label() + " 步已超过 " + step.defaultTimeoutSeconds() + " 秒阈值未推进，"
                  + "自动挂起等待处置",
              version);
      try {
        var result = block(id, blockInput, actor);
        // ⭐ 「超了多久」也由 DB 时钟算：deadline_at 是 DB 时钟写的，
        //   拿 JVM 墙钟去减它又是跨时钟域（V211）。
        BigDecimal overdue = row.get("overdue_seconds", BigDecimal.class);
        blocked.add(
            new FulfillmentRequestScanResult.FulfillmentRequestBlocked(
                id, row.get("request_code", String.class), step, reason, overdue,
                result.processVersion()));
      } catch (ResponseStatusException e) {
        // 并发下这条可能刚被别人推进 / 重试 / 取消：如实报「跳过 + 原因」，
        // 不把并发冲突吞成一次成功的挂起。
        skipped.add(
            new FulfillmentRequestScanResult.FulfillmentRequestSkipped(
                id, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
      }
    }
    return new FulfillmentRequestScanResult(due.size(), blocked, skipped, now());
  }

  /** 超时记录列表（9.3.3.2 的数据来源）。 */
  public FulfillmentRequestTimeoutPage pageTimeouts(String stepKindName, String reasonCodeName, int rawPage,
      int rawSize) {
    int size = pageSize(rawSize);
    int pageNo = Math.max(0, rawPage);
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(stepKindName)) {
      where.add("step_kind = ?");
      binds.add(stepKind(stepKindName).name());
    }
    if (notBlank(reasonCodeName)) {
      where.add("reason_code = ?");
      binds.add(blockReason(reasonCodeName).name());
    }
    String sqlWhere = whereClause(where);
    long total =
        sql.fetchOne("select count(*) c from fulfillment_request_timeout" + sqlWhere,
            binds.toArray()).get("c", Long.class);
    var items =
        sql.fetch(
                "select t.id, t.request_id, r.request_code, t.step_kind, t.attempt, t.detection_kind,"
                    + " t.due_at, t.detected_at, t.overdue_seconds, t.threshold_seconds,"
                    + " t.reason_code, t.reason, t.blocked_at"
                    + " from fulfillment_request_timeout t"
                    + " join fulfillment_request r on r.id = t.request_id and r.tenant_id = t.tenant_id"
                    + sqlWhere + " order by t.detected_at desc limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(FulfillmentRequestService::timeoutRow);
    return new FulfillmentRequestTimeoutPage(items, total, pageNo, size);
  }

  private static FulfillmentBlockReason timeoutReason(FulfillmentStepKind step) {
    return switch (step) {
      case RESERVE -> FulfillmentBlockReason.RESERVE_TIMEOUT;
      case OUTBOUND -> FulfillmentBlockReason.OUTBOUND_TIMEOUT;
      case WORK -> FulfillmentBlockReason.WORK_TIMEOUT;
      case TRACK -> FulfillmentBlockReason.TRACK_TIMEOUT;
    };
  }

  // ================================================================ 读模型

  /** 详情头部的内部视图：比对外的 Row 多两个只用于写路径校验的字段。 */
  record Head(
      UUID id,
      String requestCode,
      FulfillmentRequestStatus status,
      FulfillmentStepKind currentStep,
      long processVersion,
      int attempt,
      String warehouseRef,
      String skuRef,
      BigDecimal requestedQty,
      int reserveTimeoutSeconds,
      int outboundTimeoutSeconds,
      int workTimeoutSeconds,
      int trackTimeoutSeconds) {

    /** 该步的超时阈值：显式参数优先，否则用头行上建单时定的租户口径。 */
    int stepTimeoutSeconds(FulfillmentStepKind step) {
      return switch (step) {
        case RESERVE -> reserveTimeoutSeconds;
        case OUTBOUND -> outboundTimeoutSeconds;
        case WORK -> workTimeoutSeconds;
        case TRACK -> trackTimeoutSeconds;
      };
    }
  }

  private Head requireHead(UUID id) {
    var row =
        sql.fetchOne(
            "select id, request_code, status, current_step, process_version, attempt,"
                + " warehouse_ref, sku_ref, requested_qty, reserve_timeout_seconds,"
                + " outbound_timeout_seconds, work_timeout_seconds, track_timeout_seconds"
                + " from fulfillment_request where id = ?",
            id);
    if (row == null) {
      throw notFound(id);
    }
    return new Head(
        row.get("id", UUID.class),
        row.get("request_code", String.class),
        FulfillmentRequestStatus.valueOf(row.get("status", String.class)),
        FulfillmentStepKind.valueOf(row.get("current_step", String.class)),
        row.get("process_version", Long.class),
        row.get("attempt", Integer.class),
        row.get("warehouse_ref", String.class),
        row.get("sku_ref", String.class),
        row.get("requested_qty", BigDecimal.class),
        row.get("reserve_timeout_seconds", Integer.class),
        row.get("outbound_timeout_seconds", Integer.class),
        row.get("work_timeout_seconds", Integer.class),
        row.get("track_timeout_seconds", Integer.class));
  }

  private void guardVersion(Head head, long expected) {
    if (expected != head.processVersion()) {
      throw conflict(
          "履约请求 " + head.requestCode() + " 版本冲突：期望 " + expected + "，当前 "
              + head.processVersion() + "。同一流程被并发推进过，请重新读取后再操作");
    }
  }

  /** 当前步必须是 {@code step}，且状态必须已推进到对应的「进行中」状态。 */
  private void requireStep(Head head, FulfillmentStepKind step, FulfillmentRequestStatus expected) {
    if (head.currentStep() != step) {
      throw bad("当前步是「" + head.currentStep().label() + "」，不是「" + step.label()
          + "」：请先把上一步推进完");
    }
    if (head.status() != expected) {
      throw bad("当前状态是「" + head.status().label() + "」，发这一步指令需要「"
          + expected.label() + "」");
    }
  }

  private List<FulfillmentRequestStepRow> steps(UUID requestId) {
    return sql.fetch(
            "select id, step_kind, attempt, status, reservation_id, outbound_order_id,"
                + " work_order_count, started_at, finished_at, elapsed_seconds, failure_code,"
                + " failure_reason, operated_by_membership_id"
                + " from fulfillment_request_step where request_id = ?"
                + " order by started_at, id",
            requestId)
        .map(FulfillmentRequestService::stepRow);
  }

  private FulfillmentRequestReservationRow reservation(UUID requestId, int attempt) {
    return sql.fetchOne(
            "select id, warehouse_ref, sku_ref, requested_qty, status, reservation_ref,"
                + " locked_qty, reject_reason, requested_at, acknowledged_at, released_at,"
                + " idempotency_key, attempt from fulfillment_request_reservation"
                + " where request_id = ? and attempt = ? order by created_at desc limit 1",
            requestId,
            attempt)
        .map(FulfillmentRequestService::reservationRow);
  }

  private List<FulfillmentRequestReservationRow> reservations(UUID requestId) {
    return sql.fetch(
            "select id, warehouse_ref, sku_ref, requested_qty, status, reservation_ref,"
                + " locked_qty, reject_reason, requested_at, acknowledged_at, released_at,"
                + " idempotency_key, attempt from fulfillment_request_reservation"
                + " where request_id = ? order by attempt, created_at",
            requestId)
        .map(FulfillmentRequestService::reservationRow);
  }

  private FulfillmentRequestOutboundRow outbound(UUID requestId, int attempt) {
    return sql.fetchOne(
            "select id, warehouse_ref, outbound_qty, outbound_ref, package_ref, status,"
                + " reject_reason, requested_at, acknowledged_at, cancelled_at, idempotency_key,"
                + " attempt from fulfillment_request_outbound_order"
                + " where request_id = ? and attempt = ? order by created_at desc limit 1",
            requestId,
            attempt)
        .map(FulfillmentRequestService::outboundRow);
  }

  private List<FulfillmentRequestOutboundRow> outboundOrders(UUID requestId) {
    return sql.fetch(
            "select id, warehouse_ref, outbound_qty, outbound_ref, package_ref, status,"
                + " reject_reason, requested_at, acknowledged_at, cancelled_at, idempotency_key,"
                + " attempt from fulfillment_request_outbound_order"
                + " where request_id = ? order by attempt, created_at",
            requestId)
        .map(FulfillmentRequestService::outboundRow);
  }

  private List<FulfillmentRequestWorkOrderRow> workOrders(UUID requestId) {
    return sql.fetch(
            "select id, work_order_code, task_type, warehouse_ref, sku_ref, qty, status,"
                + " assignee_ref, started_at, finished_at, failure_reason, attempt"
                + " from fulfillment_request_work_order where request_id = ?"
                + " order by created_at, id",
            requestId)
        .map(FulfillmentRequestService::workOrderRow);
  }

  private List<FulfillmentRequestTimeoutRow> timeouts(UUID requestId) {
    return sql.fetch(
            "select id, request_id, step_kind, attempt, detection_kind, due_at, detected_at,"
                + " overdue_seconds, threshold_seconds, reason_code, reason, blocked_at"
                + " from fulfillment_request_timeout where request_id = ?"
                + " order by detected_at desc, id",
            requestId)
        .map(FulfillmentRequestService::timeoutRow);
  }

  private List<FulfillmentRequestEventRow> events(UUID requestId) {
    return sql.fetch(
            "select id, event_kind, from_status, to_status, process_version, detail, actor_id,"
                + " occurred_at from fulfillment_request_event where request_id = ?"
                + " order by process_version, occurred_at, id",
            requestId)
        .map(FulfillmentRequestService::eventRow);
  }

  private FulfillmentRequestTransitionResult transition(Head head) {
    var row = sql.fetchOne(
        "select deadline_at, blocked_at, blocked_reason from fulfillment_request where id = ?",
        head.id());
    return new FulfillmentRequestTransitionResult(
        head.id(),
        head.status(),
        head.currentStep(),
        head.processVersion(),
        head.attempt(),
        row.get("deadline_at", OffsetDateTime.class),
        row.get("blocked_at", OffsetDateTime.class),
        row.get("blocked_reason", String.class));
  }

  // ================================================================ 写留痕

  private void event(UUID requestId, FulfillmentEventKind kind, String from, String to,
      String detail, UUID actor, long version) {
    // ⭐ occurred_at 由 DB 时钟取：事件流与流程步骤行是同一条时间线上的两笔账，
    //   混用两个时钟域会让「事件在步骤之前 / 之后」的先后关系说反（V211）。
    sql.execute(
        "insert into fulfillment_request_event (id, tenant_id, request_id, event_kind, from_status,"
            + " to_status, process_version, detail, actor_id, occurred_at)"
            + " values (?,?,?,?,?,?,?,?,?,clock_timestamp())",
        UUID.randomUUID(),
        tenantId(),
        requestId,
        kind.name(),
        nz(from),
        nz(to),
        version,
        nz(detail),
        actor);
  }

  /**
   * 本步开始执行：写一条 RUNNING 的 step 行（append-only，所以「开始」也要落一行）。
   *
   * <p>⭐ {@code started_at} 由 <b>DB 时钟</b>（{@code clock_timestamp()}）生成，<b>不</b>取 JVM 墙钟。
   * 这一行与后面 {@code finished_at} 必须落在<b>同一个时钟源</b>上，否则
   * {@code CHECK (finished_at >= started_at)} 会被两个时钟之间的几十微秒随机击穿
   * （V211 修复的缺陷，见类注释）。
   */
  private void markStepRunning(UUID requestId, FulfillmentStepKind kind, int attempt, UUID actor) {
    var exists =
        sql.fetchOne(
            "select id from fulfillment_request_step where request_id = ? and step_kind = ?"
                + " and attempt = ?",
            requestId,
            kind.name(),
            attempt);
    if (exists != null) {
      return;
    }
    sql.execute(
        "insert into fulfillment_request_step (id, tenant_id, request_id, step_kind, attempt,"
            + " status, work_order_count, started_at, operated_by_membership_id)"
            + " values (?,?,?,?,?,'RUNNING',0,clock_timestamp(),?)",
        UUID.randomUUID(),
        tenantId(),
        requestId,
        kind.name(),
        attempt,
        actor);
  }

  /**
   * 本步完成：写一条 COMPLETED 的 step 行并带上凭据。
   *
   * <p>⭐ {@code finished_at} 与 {@code elapsed_seconds} <b>都由 DB 算</b>：窄函数
   * {@code complete_fulfillment_step} 用与 {@code started_at} 同一个 DB 时钟取完成时刻，
   * 再由这两个值算出耗时 ⇒ 三个字段在构造上就自洽，不存在「JVM 算的耗时」与
   * 「DB 写的时间戳」对不上的可能（旧实现正是这么把耗时算成负数的）。
   */
  private void completeStep(UUID requestId, FulfillmentStepKind kind, int attempt,
      UUID reservationId, UUID outboundOrderId, Integer workOrderCount, UUID actor) {
    var existing =
        sql.fetchOne(
            "select id, status from fulfillment_request_step"
                + " where request_id = ? and step_kind = ? and attempt = ?",
            requestId,
            kind.name(),
            attempt);
    if (existing == null) {
      // 没有 RUNNING 行就直接落一条 COMPLETED（补登记路径）：
      // started_at / finished_at 取**同一个** clock_timestamp() 值，耗时恒为 0，与两者自洽。
      sql.execute(
          "with t as (select clock_timestamp() as ts)"
              + " insert into fulfillment_request_step (id, tenant_id, request_id, step_kind, attempt,"
              + " status, reservation_id, outbound_order_id, work_order_count, started_at,"
              + " finished_at, elapsed_seconds, operated_by_membership_id)"
              + " select ?,?,?,?,?,'COMPLETED',?,?,?,ts,ts,0.000,? from t",
          UUID.randomUUID(),
          tenantId(),
          requestId,
          kind.name(),
          attempt,
          reservationId,
          outboundOrderId,
          workOrderCount == null ? 0 : workOrderCount,
          actor);
      return;
    }
    if (!"RUNNING".equals(existing.get("status", String.class))) {
      return;
    }
    // ⚠ 走窄函数而不是直接 UPDATE：_step 表的 UPDATE 权已被 REVOKE，
    //   「同一次尝试 RUNNING → COMPLETED」是唯一允许的改写，由 DB 侧守卫终态。
    //   窄函数自己取 DB 时钟写 finished_at 并算出 elapsed_seconds —— 服务层不再传时刻进去，
    //   免得又出现「客户端算的耗时」与「库里写的时间戳」分属两个时钟域。
    sql.execute(
        "select complete_fulfillment_step(?,?,?,?)",
        existing.get("id", UUID.class),
        reservationId,
        outboundOrderId,
        workOrderCount == null ? 0 : workOrderCount);
  }

  private static FulfillmentEventKind eventKindFor(FulfillmentRequestStatus target) {
    return switch (target) {
      case COMPLETED -> FulfillmentEventKind.COMPLETED;
      case RESERVED -> FulfillmentEventKind.RESERVATION_LOCKED;
      case OUTBOUND_CONFIRMED -> FulfillmentEventKind.OUTBOUND_CONFIRMED;
      case WORKING -> FulfillmentEventKind.WORK_ASSIGNED;
      default -> FulfillmentEventKind.NOTE;
    };
  }

  private static FulfillmentRequestSaved saved(Record row, String code) {
    return new FulfillmentRequestSaved(
        row.get("id", UUID.class),
        row.get("request_code", String.class),
        FulfillmentRequestStatus.valueOf(row.get("status", String.class)),
        FulfillmentStepKind.valueOf(row.get("current_step", String.class)),
        row.get("process_version", Long.class),
        row.get("attempt", Integer.class),
        row.get("requested_at", OffsetDateTime.class),
        row.get("deadline_at", OffsetDateTime.class));
  }

  // ================================================================ 行映射

  private FulfillmentRequestRow requestRow(Record r) {
    FulfillmentRequestStatus status = FulfillmentRequestStatus.valueOf(r.get("status", String.class));
    FulfillmentStepKind step = FulfillmentStepKind.valueOf(r.get("current_step", String.class));
    String blockedCode = r.get("blocked_reason_code", String.class);
    FulfillmentBlockReason blockReason =
        blockedCode == null || blockedCode.isBlank() ? null : FulfillmentBlockReason.valueOf(blockedCode);
    var deadline = r.get("deadline_at", OffsetDateTime.class);
    // ⭐ 「到没到点」由 SQL 用 DB 时钟算（REQUEST_COLUMNS 里的 overdue 列）：
    //   deadline_at 是 DB 时钟写的，拿 JVM 墙钟去比它又是跨时钟域（V211）。
    boolean overdue = Boolean.TRUE.equals(r.get("overdue", Boolean.class)) && !status.terminal();
    BigDecimal amount = r.get("estimated_handling_amount", BigDecimal.class);
    return new FulfillmentRequestRow(
        r.get("id", UUID.class),
        r.get("request_code", String.class),
        r.get("order_ref", String.class),
        r.get("order_line_ref", String.class),
        r.get("split_unit_ref", String.class),
        r.get("consolidation_ref", String.class),
        r.get("allocation_decision_ref", String.class),
        r.get("routing_decision_ref", String.class),
        r.get("sku_ref", String.class),
        r.get("platform_code", String.class),
        r.get("warehouse_ref", String.class),
        r.get("legal_entity_code", String.class),
        r.get("destination_country_code", String.class),
        r.get("destination_region_code", String.class),
        r.get("requested_qty", BigDecimal.class),
        amount == null ? null : amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
        r.get("currency_code", String.class),
        status,
        status.label(),
        step,
        step.label(),
        r.get("process_version", Long.class),
        r.get("attempt", Integer.class),
        r.get("requested_at", OffsetDateTime.class),
        deadline,
        r.get("last_step_at", OffsetDateTime.class),
        overdue,
        r.get("blocked_at", OffsetDateTime.class),
        blockReason,
        blockReason == null ? "" : blockReason.label(),
        r.get("blocked_reason", String.class),
        r.get("completed_at", OffsetDateTime.class),
        r.get("cancelled_at", OffsetDateTime.class),
        r.get("cancel_reason", String.class),
        r.get("notes", String.class));
  }

  private static FulfillmentRequestStepRow stepRow(Record r) {
    FulfillmentStepKind kind = FulfillmentStepKind.valueOf(r.get("step_kind", String.class));
    FulfillmentStepStatus status = FulfillmentStepStatus.valueOf(r.get("status", String.class));
    BigDecimal elapsed = r.get("elapsed_seconds", BigDecimal.class);
    return new FulfillmentRequestStepRow(
        r.get("id", UUID.class),
        kind,
        kind.label(),
        r.get("attempt", Integer.class),
        status,
        status.label(),
        r.get("reservation_id", UUID.class),
        r.get("outbound_order_id", UUID.class),
        r.get("work_order_count", Integer.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("finished_at", OffsetDateTime.class),
        elapsed == null ? null : elapsed.setScale(SECONDS_SCALE, RoundingMode.HALF_UP),
        r.get("failure_code", String.class),
        r.get("failure_reason", String.class),
        r.get("operated_by_membership_id", UUID.class));
  }

  private static FulfillmentRequestReservationRow reservationRow(Record r) {
    FulfillmentReservationStatus status =
        FulfillmentReservationStatus.valueOf(r.get("status", String.class));
    BigDecimal locked = r.get("locked_qty", BigDecimal.class);
    return new FulfillmentRequestReservationRow(
        r.get("id", UUID.class),
        r.get("warehouse_ref", String.class),
        r.get("sku_ref", String.class),
        r.get("requested_qty", BigDecimal.class),
        status,
        status.label(),
        r.get("reservation_ref", String.class),
        locked == null ? null : locked.setScale(QTY_SCALE, RoundingMode.HALF_UP),
        r.get("reject_reason", String.class),
        r.get("requested_at", OffsetDateTime.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("released_at", OffsetDateTime.class),
        r.get("idempotency_key", String.class),
        r.get("attempt", Integer.class));
  }

  private static FulfillmentRequestOutboundRow outboundRow(Record r) {
    FulfillmentOutboundStatus status =
        FulfillmentOutboundStatus.valueOf(r.get("status", String.class));
    return new FulfillmentRequestOutboundRow(
        r.get("id", UUID.class),
        r.get("warehouse_ref", String.class),
        r.get("outbound_qty", BigDecimal.class),
        r.get("outbound_ref", String.class),
        r.get("package_ref", String.class),
        status,
        status.label(),
        r.get("reject_reason", String.class),
        r.get("requested_at", OffsetDateTime.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("cancelled_at", OffsetDateTime.class),
        r.get("idempotency_key", String.class),
        r.get("attempt", Integer.class));
  }

  private static FulfillmentRequestWorkOrderRow workOrderRow(Record r) {
    FulfillmentWorkStatus status = FulfillmentWorkStatus.valueOf(r.get("status", String.class));
    FulfillmentWorkTaskType type =
        FulfillmentWorkTaskType.valueOf(r.get("task_type", String.class));
    return new FulfillmentRequestWorkOrderRow(
        r.get("id", UUID.class),
        r.get("work_order_code", String.class),
        type,
        type.label(),
        r.get("warehouse_ref", String.class),
        r.get("sku_ref", String.class),
        r.get("qty", BigDecimal.class),
        status,
        status.label(),
        r.get("assignee_ref", String.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("finished_at", OffsetDateTime.class),
        r.get("failure_reason", String.class),
        r.get("attempt", Integer.class));
  }

  private static FulfillmentRequestTimeoutRow timeoutRow(Record r) {
    FulfillmentStepKind kind = FulfillmentStepKind.valueOf(r.get("step_kind", String.class));
    FulfillmentDetectionKind detection =
        FulfillmentDetectionKind.valueOf(r.get("detection_kind", String.class));
    FulfillmentBlockReason reason = FulfillmentBlockReason.valueOf(r.get("reason_code", String.class));
    BigDecimal overdue = r.get("overdue_seconds", BigDecimal.class);
    return new FulfillmentRequestTimeoutRow(
        r.get("id", UUID.class),
        r.get("request_id", UUID.class),
        kind,
        kind.label(),
        r.get("attempt", Integer.class),
        detection,
        detection.label(),
        r.get("due_at", OffsetDateTime.class),
        r.get("detected_at", OffsetDateTime.class),
        overdue == null ? null : overdue.setScale(SECONDS_SCALE, RoundingMode.HALF_UP),
        r.get("threshold_seconds", Integer.class),
        reason,
        reason.label(),
        r.get("reason", String.class),
        r.get("blocked_at", OffsetDateTime.class));
  }

  private static FulfillmentRequestEventRow eventRow(Record r) {
    FulfillmentEventKind kind = FulfillmentEventKind.valueOf(r.get("event_kind", String.class));
    return new FulfillmentRequestEventRow(
        r.get("id", UUID.class),
        kind,
        kind.label(),
        r.get("from_status", String.class),
        r.get("to_status", String.class),
        r.get("process_version", Long.class),
        r.get("detail", String.class),
        r.get("actor_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  // ================================================================ 小工具

  /** 当前事务的租户：与 RLS 用的是同一个 GUC，不从任何请求头取。 */
  private UUID tenantId() {
    return sql.fetchOne(
            "select nullif(current_setting('app.current_tenant', true),'')::uuid as tenant_id")
        .get("tenant_id", UUID.class);
  }

  private static String requestCode(FulfillmentRequestInput input) {
    if (nz(input.requestCode()).isBlank()) {
      throw bad("requestCode 不能为空");
    }
    return input.requestCode().trim();
  }

  private static BigDecimal qty(BigDecimal value, String field) {
    if (value == null) {
      throw bad(field + " 不能为空");
    }
    BigDecimal scaled = value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
    if (scaled.signum() <= 0) {
      throw bad(field + " 必须为正数");
    }
    return scaled;
  }

  private static BigDecimal money(BigDecimal value, String field) {
    BigDecimal scaled = value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (scaled.signum() < 0) {
      throw bad(field + " 不能为负");
    }
    return scaled;
  }

  private static int timeout(Integer value, String field, FulfillmentStepKind step) {
    if (value == null) {
      return step.defaultTimeoutSeconds();
    }
    if (value <= 0) {
      throw bad(field + " 必须为正秒数");
    }
    return value;
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

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static String nz(String value, String fallback) {
    return notBlank(value) ? value.trim() : fallback;
  }

  static FulfillmentRequestStatus status(String value) {
    try {
      return FulfillmentRequestStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("状态「" + value + "」不在词表内");
    }
  }

  static FulfillmentStepKind stepKind(String value) {
    try {
      return FulfillmentStepKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("流程步「" + value + "」不在词表内");
    }
  }

  static FulfillmentBlockReason blockReason(String value) {
    try {
      return FulfillmentBlockReason.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("挂起原因码「" + value + "」不在词表内");
    }
  }

  static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  static ResponseStatusException notFound(UUID id) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "履约请求 " + id + " 不存在或不属于本租户");
  }
}
