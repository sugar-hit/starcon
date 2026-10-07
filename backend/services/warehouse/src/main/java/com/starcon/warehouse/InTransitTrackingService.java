package com.starcon.warehouse;

import static com.starcon.warehouse.InTransitTrackingModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
 * 10.4.1.2 在途跟踪。
 *
 * <p>蓝图 10-logistics-and-freight.md 10.4.1.2 原文：
 *
 * <ul>
 *   <li>职责：跟踪头程在途：位置、预计到港、延误预警、清关状态、目的仓收货确认，节点异常主动告警。
 *   <li>输入：货代/船司/清关状态同步；人工更新。
 *   <li>输出：在途看板 + 延误预警 + 到港提醒。
 *   <li>关系：上游 = 10.4.1.1；下游 = 7.5.1.2 在途库存、6.2.1.1 补货（缺口计算）。
 * </ul>
 *
 * <p>六条实现纪律：
 *
 * <ol>
 *   <li><b>不建第二张在途账</b>：在途腿与在途事件的写入所有者是 7.5.1.2，本模块
 *       <b>只读</b> {@code inventory_transfer} / {@code in_transit_event}。
 *       四个跟踪表里<b>没有任何数量余额列</b> —— 「发运 100、到 60、还剩 40」全部
 *       由 {@link #legRows} 现算后随响应返回。
 *   <li><b>预警按剩余窗口提前分级</b>：蓝图关系段原文「头程延误是断货的主要外因，
 *       预警必须提前到预计到港前若干天」。所以分级不是「到港当天才发现晚了」，
 *       而是 {@link #evaluateLevel} 用「剩余窗口 = 生效ETA − 现在」对阈值表逐级判定。
 *   <li><b>阈值落库可配置</b>：三档提前量来自 {@code first_mile_delay_threshold}，
 *       按 (运输方式, 目的国) 逐级回落匹配。代码里没有任何 if 里的天数常量。
 *   <li><b>外部同步未接入</b>：货代/船司/清关连接器（10.4.2.1）<b>未交付</b>，
 *       因此本模块<b>没有同步任务、没有定时器</b>。外部事实经
 *       {@link #recordEvent} 入口按 {@code sourceSystem} + {@code referenceId} 承载，
 *       DB 层钉死「外部来源必须有单号」，写不出出处的外部事实一条都不许入库。
 *   <li><b>只记收货确认，不写库存</b>：{@link #confirmReceipt} 只落
 *       「目的仓确认收到了」这个事实并关掉跟踪，入库落账归 7.5.1.2 的 arrive。
 *   <li><b>阶段只进不退</b>：DB 触发器 {@code guard_first_mile_tracking_event_source}
 *       用 {@code stage_rank()} 钉死；服务层再给一次可读报错，两处同一套序号。
 * </ol>
 *
 * <p>状态机有两处真相：{@link TrackingStatus} 管跟踪记录自己的生死，
 * {@link TrackingStage} 管这批货走到哪了。两者分开才能表达
 * 「跟踪已关闭但清关仍在扣关中」这类真实组合。
 */
@Service
public class InTransitTrackingService {

  /** 单页最大行数。 */
  static final int MAX_PAGE_SIZE = 200;

  private static final List<String> SORTS =
      List.of("UPDATED_AT", "CREATED_AT", "ETA_ASC", "REMAINING_ASC", "LEVEL_DESC");

  private static final BigDecimal DAYS = BigDecimal.valueOf(24 * 60 * 60);
  private static final BigDecimal HOURS = BigDecimal.valueOf(60 * 60);
  private static final BigDecimal MAX = new BigDecimal("99999999.99");

  private final DSLContext sql;

  public InTransitTrackingService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力

  /**
   * 在途跟踪能力：可跟踪来源、<b>未交付依赖逐条声明</b>、两级状态机与四条口径说明。
   *
   * <p>本车道对集成头实查的依赖结论（不凭蓝图的「上游 =」假定）：
   * 10.4.1.1 头程发运单（迁移 V80）<b>已交付</b>，因此跟踪真的挂在真实发运单上；
   * 7.5.1.2 在途库存（迁移 V63）<b>已交付</b>，因此在途余量是 join 现算的真值，
   * 不是本模块自己存的一份。外部连接器全部未交付。
   */
  public TransitTrackCapabilities capabilities() {
    List<TrackingDimension> sources =
        List.of(
            new TrackingDimension(
                "FIRST_MILE_SHIPMENT",
                "头程发运单",
                true,
                "10.4.1.1",
                "/warehouse/first-mile-shipments",
                "已交付：10.4.1.1 头程发运单（迁移 V80）。建跟踪时会真去校验发运单存在、"
                    + "属于本租户，且状态必须是已发出 —— 没发出去的货不构成在途，"
                    + "给它建跟踪会让看板把一堆草稿单混进在途里。"),
            new TrackingDimension(
                "PURCHASE_IN_TRANSIT",
                "采购在途",
                false,
                "6.3.2.2",
                "/purchase/orders",
                "6 采购未交付：6.1–6.6 全部 36 个模块均未开工，采购单表与到货端点都不存在。"
                    + "本模块因此只跟踪 10.4.1.1 头程发运单，不接受采购来源 —— "
                    + "一张没有采购单可指的在途记录会让「这批货从哪家供应商来」答不出来。"));

    List<TrackingDimension> dependencies =
        List.of(
            new TrackingDimension(
                "FORWARDER_CONNECTOR",
                "货代状态同步连接器",
                false,
                "10.4.2.1",
                "/logistics/overseas-warehouses",
                "**外部连接器未交付**：10.4.2.1 海外仓连接与货代/船司连接器均未开工。"
                    + "本模块因此**没有常驻同步任务、也没有定时器**。货代报的位置与改期"
                    + "目前由人工代录，事件带 sourceSystem=FORWARDER 与来源单号，"
                    + "写不出出处的外部事实一条都不许入库（DB 层 CHECK 钉死）。"),
            new TrackingDimension(
                "CARRIER_CONNECTOR",
                "船司/航空公司轨迹同步",
                false,
                "10.4.2.1",
                "/logistics/overseas-warehouses",
                "**外部连接器未交付**：海运问船名航段、空运问航班与中转场，"
                    + "这些位置数据现在只能人工录入。当前位置列因此是自由文本快照，"
                    + "接上连接器后按同一形状写入即可，本表结构不用再改。"),
            new TrackingDimension(
                "CUSTOMS_CONNECTOR",
                "清关状态同步",
                false,
                "10.5.1.1",
                "/logistics/customs-declarations",
                "**外部连接器未交付**：10.5 报关与清关回执未开工，清关状态由人工代录。"
                    + "扣关(HELD)与退单(REJECTED)强制要求原因，没有原因的扣关事后"
                    + "没人知道该找谁、该补什么材料。"),
            new TrackingDimension(
                "IN_TRANSIT_STOCK",
                "在途库存余量",
                true,
                "7.5.1.2",
                "/warehouse/inventory/in-transit",
                "已交付：7.5.1.2 在途库存（迁移 V63）。在途腿与在途事件的写入所有者是它，"
                    + "本模块**只读**：在途余量、已了结、未达三个数全部 join "
                    + "inventory_transfer 现算，本模块的表里没有数量余额列。"),
            new TrackingDimension(
                "REPLENISHMENT",
                "补货缺口计算",
                false,
                "6.2.1.1",
                "/purchase/replenishment",
                "下游 6.2.1.1 补货未交付（属 6 采购，整体未开工）。"
                    + "本模块只输出「这批货还有几天到」这一维事实，"
                    + "**不代算缺口、不反向调用补货**：蓝图的依赖方向是补货来读在途，"
                    + "不是在途去驱动补货。"));

    List<TrackingTransition> statusTransitions = new ArrayList<>();
    for (var from : TrackingStatus.values()) {
      for (var to : allowedNextStatus(from)) {
        statusTransitions.add(new TrackingTransition(from.name(), to.name(), transitionLabel(from, to)));
      }
    }

    List<TrackingStageTransition> stageTransitions = new ArrayList<>();
    for (var from : TrackingStage.values()) {
      for (var to : allowedNextStage(from)) {
        stageTransitions.add(
            new TrackingStageTransition(from.name(), to.name(), stageLabel(from, to)));
      }
    }

    List<String> notes =
        List.of(
            "预警按**剩余窗口**（预计到港 − 现在）提前分级，不是到港当天才发现晚了："
                + "蓝图关系段原文「头程延误是断货的主要外因，预警必须提前到预计到港前若干天」。",
            "三档提前量（watch / warning / critical）来自阈值表 first_mile_delay_threshold，"
                + "按 (运输方式, 目的国) 逐级回落匹配，**不是代码里的常量** —— 改一行就换口径。",
            "外部同步**未接入**：本模块没有常驻同步任务也没有定时器。货代/船司/清关事实"
                + "经事件表承载，带 sourceSystem 与来源单号；写不出出处的外部事实不入库。",
            "不建第二张在途账：在途余量一律 join 7.5.1.2 的 inventory_transfer 现算。"
                + "本模块只记跟踪维度的事实（位置、清关、ETA、收货确认），这些在途表上根本没有。");

    return new TransitTrackCapabilities(
        sources,
        dependencies,
        statusTransitions,
        stageTransitions,
        options(CustomsStatus.values()),
        options(TrackingSource.values()),
        options(DelayLevel.values()),
        notes);
  }

  /**
   * 枚举 → 词表。
   *
   * <p>入参是 {@link Labelled[]}（{@code InTransitTrackingModels.Labelled}）而不是
   * {@code Function<Object,String>}：后者会把枚举擦成 Object，编译器再也发现不了
   * 「往里传了一个没有 label() 的类型」。数组协变让 {@code CustomsStatus[]} 直接可用。
   */
  private static List<TrackingOption> options(Labelled[] values) {
    var out = new ArrayList<TrackingOption>();
    for (var v : values) {
      out.add(new TrackingOption(v.name(), v.label()));
    }
    return out;
  }

  // ================================================================ 状态机（唯一真相）

  /** 跟踪生命周期合法转换。终态返回空表。 */
  static List<TrackingStatus> allowedNextStatus(TrackingStatus current) {
    return switch (current) {
      case TRACKING ->
          List.of(TrackingStatus.TRACKING, TrackingStatus.CLOSED, TrackingStatus.CANCELLED);
      case CLOSED, CANCELLED -> List.of();
    };
  }

  /**
   * 跟踪阶段合法推进。
   *
   * <p>只允许**推进**（rank 递增）或原地自环（报同一阶段的位置更新）。
   * EXCEPTION 从任何阶段都可达（出事不需要先走到某一步），
   * 而 EXCEPTION 之后仍可推进到后续阶段（问题排除后货继续走）。
   */
  static List<TrackingStage> allowedNextStage(TrackingStage current) {
    var out = new ArrayList<TrackingStage>();
    out.add(current); // 自环：重复报同一阶段的位置更新是常态
    for (var s : TrackingStage.values()) {
      if (s == TrackingStage.EXCEPTION) {
        // EXCEPTION 是例外分支而不是推进中的一站：任何阶段都能进，
        // 但它本身**不**排在正常链路里（否则在途 1 就会「顺理成章」变成异常）。
        if (current != TrackingStage.EXCEPTION) {
          out.add(TrackingStage.EXCEPTION);
        }
      } else if (s.rank() > current.rank() && current != TrackingStage.EXCEPTION) {
        // EXCEPTION 之后可以恢复到任何后续阶段（问题排除，货继续走）。
        out.add(s);
      }
    }
    return out;
  }

  private static String transitionLabel(TrackingStatus from, TrackingStatus to) {
    if (from == to) {
      return "同状态更新（改位置 / 改 ETA / 改清关）";
    }
    return switch (to) {
      case CLOSED -> "关闭跟踪：货已闭环，后续变化不再跟踪";
      case CANCELLED -> "取消跟踪：对应发运单被取消";
      default -> "状态变更";
    };
  }

  private static String stageLabel(TrackingStage from, TrackingStage to) {
    if (from == to) {
      return "同一阶段的重复上报";
    }
    if (to == TrackingStage.EXCEPTION) {
      return "标记节点异常：任何阶段都可进入，异常排除后可继续推进";
    }
    return "推进：" + from.label() + " → " + to.label();
  }

  // ================================================================ 阈值（落库可配）

  /**
   * 取当前生效阈值：按 (运输方式, 目的国) 逐级回落匹配，最后回落到 {@code (*,*)} 默认行。
   *
   * <p>**没有落到 Java 常量**：最差情况命中的是表里的默认行，
   * 它同样可被运营改。取阈值前先 {@link #ensureDefaultThreshold} 幂等补出这一行，
   * 之后每一次读都真的走表（不是第一次读之后就走缓存或常量）。
   */
  EffectiveThreshold effectiveThreshold(String transportMode, String countryCode) {
    ensureDefaultThreshold();
    var country = countryCode == null || countryCode.isBlank() ? "*" : countryCode.trim();
    var mode = transportMode == null || transportMode.isBlank() ? "*" : transportMode.trim();
    // 匹配顺序：精确两者 → 只精确运输方式 → 只精确国家 → 默认行。
    var candidates =
        List.of(
            List.of(mode, country),
            List.of(mode, "*"),
            List.of("*", country),
            List.of("*", "*"));
    for (var pair : candidates) {
      var hit =
          sql.fetchOne(
              "select * from first_mile_delay_threshold"
                  + " where tenant_id=? and transport_mode=? and country_code=?",
              currentTenant(),
              pair.get(0),
              pair.get(1));
      if (hit != null) {
        return thresholdOf(hit, false);
      }
    }
    // 走到这里只可能是 ensureDefaultThreshold 刚被并发删掉；如实报错而不是偷偷用一个天数。
    throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "本租户的延误预警阈值行已不存在：请重新建一条默认行"
            + "（transportMode 与 countryCode 用 * 表示通用），"
            + "否则无法判定剩余窗口分级。本模块刻意不用代码常量兜底。");
  }

  /**
   * 幂等补出本租户的 {@code (*,*)} 默认阈值行。
   *
   * <p>蓝图要求「阈值落库可配置」，那就**必须有一行真的在库里**才能读到口径 ——
   * 新租户没有行时如果直接用 Java 常量兜底，运营看到的就永远是代码里的天数，
   * 「改阈值」也永远无从谈起。所以第一次需要阈值时补一行默认值（7/3/0 天），
   * **之后每次读都走表**：这个默认值仍然可被运营改，不是常量。
   *
   * <p>用 {@code on conflict do nothing} 保证并发下只会有一行，且不会覆盖运营已改过的口径。
   */
  private void ensureDefaultThreshold() {
    var tenant = currentTenant();
    if (tenant == null) {
      return; // 租户没绑上时 RLS 本来就什么都看不到，不需要补行
    }
    sql.execute(
        "insert into first_mile_delay_threshold"
            + " (id,tenant_id,transport_mode,country_code,watch_lead_days,warning_lead_days,"
            + " critical_lead_days,notes)"
            + " values (?,?,'*','*',7,3,0,?)"
            + " on conflict (tenant_id,transport_mode,country_code) do nothing",
        UUID.randomUUID(),
        tenant,
        "模块默认口径：到港前 7 天进提示区、3 天进预警区、已过港未到货为严重延误。可直接改本行。");
  }

  private EffectiveThreshold thresholdOf(Record r, boolean defaultRow) {
    return new EffectiveThreshold(
        r.get("id", UUID.class),
        defaultRow,
        r.get("transport_mode", String.class),
        r.get("country_code", String.class),
        r.get("watch_lead_days", Integer.class),
        r.get("warning_lead_days", Integer.class),
        r.get("critical_lead_days", Integer.class),
        "剩余窗口 = 生效预计到港 − 现在。"
            + "剩余窗口 ≤ " + r.get("warning_lead_days", Integer.class) + " 天进 WARNING（延误预警）；"
            + "≤ " + r.get("watch_lead_days", Integer.class) + " 天进 WATCH（临近到港提示）；"
            + "已经过了预计到港仍未到货进 CRITICAL（严重延误）。"
            + "三档提前量取自本租户阈值表，不是代码常量。");
  }

  /**
   * 按剩余窗口分级 —— 蓝图的「预警必须提前到预计到港前若干天」就落在这三行。
   *
   * <p>判定顺序是**从最严重往下**：已经过了预计到港还没到货（剩余 &lt; 0）最严重，
   * 否则按 warning → watch 的提前量窗口逐级命中。三个天数全部来自
   * {@link EffectiveThreshold}（阈值表行），代码里没有第二份。
   */
  static DelayLevel evaluateLevel(BigDecimal remainingDays, EffectiveThreshold t) {
    if (remainingDays == null) {
      return DelayLevel.ON_TRACK; // 没有 ETA 无法分级，如实按「无判断」处理而不是猜一个档
    }
    if (remainingDays.signum() < 0) {
      return DelayLevel.CRITICAL;
    }
    if (remainingDays.compareTo(BigDecimal.valueOf(t.warningLeadDays())) <= 0) {
      return DelayLevel.WARNING;
    }
    if (remainingDays.compareTo(BigDecimal.valueOf(t.watchLeadDays())) <= 0) {
      return DelayLevel.WATCH;
    }
    return DelayLevel.ON_TRACK;
  }

  /** 该档位对应的阈值提前量（用于把 lead_days 落库，解释「按几天档触发」）。 */
  private static int leadDaysOf(DelayLevel level, EffectiveThreshold t) {
    return switch (level) {
      case WATCH -> t.watchLeadDays();
      case WARNING -> t.warningLeadDays();
      case CRITICAL -> t.criticalLeadDays();
      case ON_TRACK -> 0;
    };
  }

  /** 阈值清单（标出哪一行正被在途批次命中）。 */
  public List<DelayThresholdRow> thresholds() {
    // 全新租户还没有任何阈值行：不补的话运营打开阈值页看到空列表，
    // 而「空」与「没有口径」在这里是同一件事 —— 读路径又会补行，两边口径必须一致。
    ensureDefaultThreshold();
    var rows = sql.fetch("select * from first_mile_delay_threshold order by transport_mode,country_code");
    // 统计每条阈值行当前命中多少个在途跟踪，用于标 inUse。
    var usage = new HashMap<UUID, Long>();
    for (var t : sql.fetch("select transport_mode,destination_country_code from first_mile_tracking t"
        + " join first_mile_shipment s on s.tenant_id=t.tenant_id and s.id=t.shipment_id"
        + " where t.status='TRACKING'")) {
      var e =
          effectiveThreshold(
              t.get("transport_mode", String.class), t.get("destination_country_code", String.class));
      if (e.thresholdId() != null) {
        usage.merge(e.thresholdId(), 1L, Long::sum);
      }
    }
    return rows.stream()
        .map(
            r -> {
              var id = r.get("id", UUID.class);
              return new DelayThresholdRow(
                  id,
                  r.get("transport_mode", String.class),
                  r.get("country_code", String.class),
                  r.get("watch_lead_days", Integer.class),
                  r.get("warning_lead_days", Integer.class),
                  r.get("critical_lead_days", Integer.class),
                  r.get("notes", String.class),
                  r.get("version", Long.class),
                  r.get("updated_at", OffsetDateTime.class),
                  usage.getOrDefault(id, 0L) > 0);
            })
        .toList();
  }

  /** 改阈值：运营换预警口径不发版。 */
  @Transactional
  public DelayThresholdRow updateThreshold(UUID thresholdId, DelayThresholdUpdateRequest req) {
    var req0 = req == null ? new DelayThresholdUpdateRequest(null, null, null, null) : req;
    var hit =
        sql.fetchOne(
            "select * from first_mile_delay_threshold where tenant_id=? and id=? for update",
            currentTenant(),
            thresholdId);
    if (hit == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "阈值行不存在或无权修改");
    }
    var watch =
        req0.watchLeadDays() == null ? hit.get("watch_lead_days", Integer.class) : req0.watchLeadDays();
    var warning =
        req0.warningLeadDays() == null
            ? hit.get("warning_lead_days", Integer.class)
            : req0.warningLeadDays();
    var critical =
        req0.criticalLeadDays() == null
            ? hit.get("critical_lead_days", Integer.class)
            : req0.criticalLeadDays();
    // 递减性在服务层先给可读报错（DB 的 CHECK 会兜底，但错误文案指不到具体字段）。
    if (warning > watch) {
      throw new IllegalArgumentException(
          "warningLeadDays(" + warning + ") 不能大于 watchLeadDays(" + watch
              + ")：三级必须递减，否则「进观察区」反而比「进提示区」晚，分级会静默倒挂");
    }
    var notes =
        req0.notes() == null ? hit.get("notes", String.class) : req0.notes();
    sql.execute(
        "update first_mile_delay_threshold set watch_lead_days=?,warning_lead_days=?,"
            + " critical_lead_days=?,notes=?,version=version+1,updated_at=now(),"
            + " updated_by=nullif(current_setting('app.actor',true),'')::uuid"
            + " where tenant_id=? and id=?",
        watch, warning, critical, notes, currentTenant(), thresholdId);
    // 改完直接从 thresholds() 重读（它会标 inUse），不重复拼一遍映射。
    return thresholds().stream()
        .filter(t -> t.id().equals(thresholdId))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "阈值行不存在或无权修改"));
  }

  // ================================================================ 看板

  /** 在途看板：真分页 + **全量汇总不受分页影响**。 */
  public TransitTrackPage page(TrackingQuery query) {
    var q = query == null ? emptyQuery() : query;
    var where = new StringBuilder(" where 1=1 ");
    var args = new ArrayList<Object>();

    filterIn(where, args, "t.status", q.status());
    filterIn(where, args, "t.tracking_stage", q.stage());
    filterIn(where, args, "t.customs_status", q.customsStatus());
    if (q.shipmentId() != null) {
      where.append(" and t.shipment_id=?");
      args.add(q.shipmentId());
    }
    if (q.destinationWarehouseId() != null) {
      where.append(" and s.destination_warehouse_id=?");
      args.add(q.destinationWarehouseId());
    }
    if (q.destinationCountryCode() != null && !q.destinationCountryCode().isBlank()) {
      where.append(" and s.destination_country_code=?");
      args.add(q.destinationCountryCode().trim().toUpperCase());
    }
    if (q.transportMode() != null && !q.transportMode().isBlank()) {
      where.append(" and s.transport_mode=?");
      args.add(q.transportMode().trim());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      var kw = "%" + q.keyword().trim() + "%";
      where.append(
          " and (s.shipment_code ilike ? or s.carrier_name ilike ? or s.booking_no ilike ?"
              + " or t.current_location ilike ? or s.vessel_or_flight_no ilike ?)");
      args.add(kw);
      args.add(kw);
      args.add(kw);
      args.add(kw);
      args.add(kw);
    }
    // delayedOnly / delayLevel 都要先算剩余窗口，所以统一走 CTE 口径而不是再查一遍。
    String cte =
        "with base as ("
            + " select t.*, s.shipment_code, s.transport_mode, s.carrier_name, s.booking_no,"
            + "   s.vessel_or_flight_no, s.destination_warehouse_id, s.destination_country_code,"
            + "   w.name as destination_warehouse_name,"
            + "   coalesce(t.revised_eta,t.original_eta) as effective_eta,"
            + "   extract(epoch from (coalesce(t.revised_eta,t.original_eta) - now()))/86400.0"
            + "     as remaining_days_raw"
            + " from first_mile_tracking t"
            + " join first_mile_shipment s on s.tenant_id=t.tenant_id and s.id=t.shipment_id"
            + " join warehouse w on w.tenant_id=s.tenant_id and w.id=s.destination_warehouse_id"
            + where
            + ") select * from base";

    var rowsAll = sql.fetch(cte + " order by " + orderBy(q.sort()));
    var graded = new ArrayList<Object[]>();
    var stats = new long[4]; // watch / warning / critical / overdue
    var heldCount = 0L;
    Map<String, Long> alertCounts = alertCountByTracking();

    for (Record r : rowsAll) {
      var remaining = days(r.get("remaining_days_raw", BigDecimal.class));
      var t =
          effectiveThreshold(
              r.get("transport_mode", String.class), r.get("destination_country_code", String.class));
      var level = evaluateLevel(remaining, t);
      // delayedOnly 在**分级之后**过滤，保证筛选口径与看板显示的档位是同一个。
      if (q.delayLevel() != null && !q.delayLevel().isEmpty()) {
        var want = new LinkedHashSet<String>();
        for (var s : q.delayLevel()) {
          want.add(s.trim().toUpperCase());
        }
        if (!want.contains(level.name())) {
          continue;
        }
      }
      if (Boolean.TRUE.equals(q.delayedOnly())
          && !(level == DelayLevel.WARNING
              || level == DelayLevel.CRITICAL
              || (remaining != null && remaining.signum() < 0))) {
        continue;
      }
      switch (level) {
        case WATCH -> stats[0]++;
        case WARNING -> stats[1]++;
        case CRITICAL -> stats[2]++;
        default -> {}
      }
      if (remaining != null && remaining.signum() < 0 && r.get("status", String.class).equals("TRACKING")) {
        stats[3]++;
      }
      if (r.get("customs_status", String.class).equals("HELD")
          || r.get("customs_status", String.class).equals("REJECTED")) {
        heldCount++;
      }
      graded.add(new Object[] {r, remaining, level, t, alertCounts});
    }

    int size = clampSize(q.size());
    int page = Math.max(0, q.page());
    int from = Math.min(page * size, graded.size());
    int to = Math.min(from + size, graded.size());

    var legs = legQuantityByTracking();
    var pageRows = new ArrayList<TrackingRow>();
    for (int i = from; i < to; i++) {
      var item = graded.get(i);
      pageRows.add(
          rowOf((Record) item[0], (BigDecimal) item[1], (DelayLevel) item[2],
              (Map<String, Long>) item[4], legs));
    }

    var stages =
        java.util.Arrays.stream(TrackingStage.values())
            .map(s -> new TrackingStageOption(s.name(), s.label(), s.rank()))
            .toList();

    return new TransitTrackPage(
        pageRows,
        graded.size(),
        graded.stream().filter(o -> ((Record) o[0]).get("status", String.class).equals("TRACKING")).count(),
        stats[0],
        stats[1],
        stats[2],
        stats[3],
        heldCount,
        options(TrackingStatus.values()),
        stages,
        options(CustomsStatus.values()),
        options(DelayLevel.values()),
        warehouseOptions());
  }

  /** 每条跟踪的未结预警条数。 */
  private Map<String, Long> alertCountByTracking() {
    var out = new HashMap<String, Long>();
    for (var r :
        sql.fetch(
            "select tracking_id,count(*) from first_mile_delay_alert"
                + " where status in ('OPEN','ACKNOWLEDGED') group by tracking_id")) {
      out.put(r.get(0, UUID.class).toString(), r.get(1, Long.class));
    }
    return out;
  }

  /** 每条跟踪在 7.5.1.2 名下的腿数与在途/已了结数量（本模块**不持有余额**，只读）。 */
  private Map<String, BigDecimal> legQuantityByTracking() {
    var out = new HashMap<String, BigDecimal>();
    for (var r :
        sql.fetch(
            "select g.shipment_id, count(*), coalesce(sum(g.quantity),0),"
                + " coalesce(sum(t.settled_quantity),0)"
                + " from first_mile_shipment_leg_receipt g"
                + " left join inventory_transfer t on t.tenant_id=g.tenant_id and t.id=g.transit_leg_id"
                + " group by g.shipment_id")) {
      var id = r.get(0, UUID.class).toString();
      var leg = new BigDecimal(r.get(1, Long.class).toString());
      var shipped = zero(r.get(2, BigDecimal.class));
      var settled = zero(r.get(3, BigDecimal.class));
      out.put(id + "#count", leg);
      out.put(id + "#intransit", shipped.subtract(settled).max(BigDecimal.ZERO));
      out.put(id + "#settled", settled);
    }
    return out;
  }

  private TrackingRow rowOf(
      Record r,
      BigDecimal remaining,
      DelayLevel level,
      Map<String, Long> alerts,
      Map<String, BigDecimal> legs) {
    var shipmentId = r.get("shipment_id", UUID.class);
    var key = shipmentId.toString();
    var legCount =
        legs.containsKey(key + "#count")
            ? legs.get(key + "#count").longValue()
            : 0L;
    return new TrackingRow(
        r.get("id", UUID.class),
        shipmentId,
        r.get("shipment_code", String.class),
        r.get("transport_mode", String.class),
        r.get("carrier_name", String.class),
        r.get("booking_no", String.class),
        r.get("destination_warehouse_id", UUID.class),
        // 目的仓名随主查询一次 join 出来：逐行回查既慢又容易少绑租户。
        nz(r.get("destination_warehouse_name", String.class)),
        r.get("destination_country_code", String.class),
        r.get("status", String.class),
        r.get("tracking_stage", String.class),
        r.get("customs_status", String.class),
        r.get("current_location", String.class),
        r.get("location_occurred_at", OffsetDateTime.class),
        r.get("original_eta", OffsetDateTime.class),
        r.get("revised_eta", OffsetDateTime.class),
        r.get("effective_eta", OffsetDateTime.class),
        r.get("eta_revised_count", Integer.class),
        remaining,
        remaining != null && remaining.signum() < 0,
        level.name(),
        alerts.getOrDefault(r.get("id", UUID.class).toString(), 0L),
        legCount,
        legs.getOrDefault(key + "#intransit", BigDecimal.ZERO),
        legs.getOrDefault(key + "#settled", BigDecimal.ZERO),
        r.get("receipt_confirmed_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class),
        r.get("version", Long.class));
  }

  /** 目的仓下拉：只在途批次真实计数（不是全仓数量）。 */
  private List<TrackingWarehouseOption> warehouseOptions() {
    var out = new ArrayList<TrackingWarehouseOption>();
    for (var r :
        sql.fetch(
            "select s.destination_warehouse_id,w.code,w.name,count(*)"
                + " from first_mile_tracking t"
                + " join first_mile_shipment s on s.tenant_id=t.tenant_id and s.id=t.shipment_id"
                + " join warehouse w on w.tenant_id=s.tenant_id and w.id=s.destination_warehouse_id"
                + " where t.status='TRACKING' group by s.destination_warehouse_id,w.code,w.name"
                + " order by w.code")) {
      out.add(
          new TrackingWarehouseOption(
              r.get(0, UUID.class), r.get(1, String.class), r.get(2, String.class), r.get(3, Long.class)));
    }
    return out;
  }

  private static void filterIn(StringBuilder where, List<Object> args, String column, List<String> values) {
    if (values == null || values.isEmpty()) {
      return;
    }
    var cleaned = new ArrayList<String>();
    for (var v : values) {
      if (v != null && !v.isBlank()) {
        cleaned.add(v.trim().toUpperCase());
      }
    }
    if (cleaned.isEmpty()) {
      return;
    }
    where.append(" and ").append(column).append(" in (");
    where.append("?,".repeat(cleaned.size() - 1)).append("?)");
    args.addAll(cleaned);
  }

  /** 排序白名单：注入串静默回落到默认键（而不是拼进 SQL）。 */
  private static String orderBy(String sort) {
    if (sort == null) {
      return "updated_at desc, id";
    }
    var key = sort.trim().toUpperCase();
    var col =
        switch (key) {
          case "UPDATED_AT" -> "updated_at desc";
          case "CREATED_AT" -> "created_at desc";
          case "ETA_ASC" -> "effective_eta asc nulls last";
          case "REMAINING_ASC" -> "remaining_days_raw asc nulls last";
          case "LEVEL_DESC" ->
              // 按严重度排序要用同一套分级口径，不能靠 SQL 里再判一次。
              "case when remaining_days_raw<0 then 3 when remaining_days_raw<=2 then 2"
                  + " when remaining_days_raw<=7 then 1 else 0 end desc, remaining_days_raw asc";
          default -> "updated_at desc";
        };
    return col + ", id";
  }

  private static int clampSize(int size) {
    if (size <= 0) {
      return 50;
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private static TrackingQuery emptyQuery() {
    return new TrackingQuery(null, null, null, null, null, null, null, null, null, null, null, 0, 50);
  }

  /** 剩余窗口天数：保留两位、遇截断夹紧，避免 long 区间溢出成科学计数。 */
  private static BigDecimal days(BigDecimal rawSeconds) {
    if (rawSeconds == null) {
      return null;
    }
    var v = rawSeconds.divide(DAYS, 2, RoundingMode.HALF_UP);
    if (v.abs().compareTo(MAX) > 0) {
      return v.signum() < 0 ? MAX.negate() : MAX;
    }
    return v;
  }

  private static BigDecimal zero(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  // ================================================================ 详情

  /** 跟踪详情：头 + 事件流 + 在途腿（7.5.1.2 名下）+ 预警 + 合法下一步 + 当前阈值。 */
  public TransitTrackDetail detail(UUID trackingId) {
    var r =
        sql.fetchOne(
            "select t.*, s.shipment_code, s.transport_mode, s.carrier_name, s.booking_no,"
                + " s.vessel_or_flight_no, s.destination_warehouse_id, s.destination_country_code,"
                + " w.name as destination_warehouse_name,"
                + " coalesce(t.revised_eta,t.original_eta) as effective_eta,"
                + " extract(epoch from (coalesce(t.revised_eta,t.original_eta) - now()))/86400.0"
                + "   as remaining_days_raw"
                + " from first_mile_tracking t"
                + " join first_mile_shipment s on s.tenant_id=t.tenant_id and s.id=t.shipment_id"
                + " join warehouse w on w.tenant_id=s.tenant_id and w.id=s.destination_warehouse_id"
                + " where t.id=?",
            trackingId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "跟踪记录不存在或无权查看");
    }
    var remaining = days(r.get("remaining_days_raw", BigDecimal.class));
    var threshold =
        effectiveThreshold(
            r.get("transport_mode", String.class), r.get("destination_country_code", String.class));
    var level = evaluateLevel(remaining, threshold);

    var row =
        rowOf(
            r,
            remaining,
            level,
            alertCountByTracking(),
            legQuantityByTracking());
    var status = TrackingStatus.parse(r.get("status", String.class));
    var stage = TrackingStage.parse(r.get("tracking_stage", String.class));

    return new TransitTrackDetail(
        row,
        eventRows(trackingId),
        legRows(trackingId),
        alertRowsForTracking(trackingId),
        allowedNextStatus(status).stream().map(Enum::name).toList(),
        allowedNextStage(stage).stream().map(Enum::name).toList(),
        threshold);
  }

  private List<TrackingEventRow> eventRows(UUID trackingId) {
    return sql.fetch(
            "select * from first_mile_tracking_event where tracking_id=?"
                + " order by occurred_at desc, id",
            trackingId)
        .stream()
        .map(
            r ->
                new TrackingEventRow(
                    r.get("id", UUID.class),
                    r.get("kind", String.class),
                    r.get("location_text", String.class),
                    r.get("detail", String.class),
                    r.get("from_stage", String.class),
                    r.get("to_stage", String.class),
                    r.get("eta_before", OffsetDateTime.class),
                    r.get("eta_after", OffsetDateTime.class),
                    r.get("source_system", String.class),
                    r.get("reference_id", String.class),
                    r.get("occurred_at", OffsetDateTime.class),
                    r.get("actor_id", UUID.class)))
        .toList();
  }

  /**
   * 在途腿（7.5.1.2 名下）。
   *
   * <p>数量三个口径并排：发运 / 已了结 / 未达。「发运 100、到 60」不并排会被读成账丢了 40。
   */
  private List<TrackingLegRow> legRows(UUID trackingId) {
    var shipmentId =
        sql.fetchOne(
                "select shipment_id from first_mile_tracking where tenant_id=? and id=?",
                currentTenant(), trackingId)
            .get("shipment_id", UUID.class);
    return sql.fetch(
            "select g.line_id,l.line_no,sk.code as sku_code,sk.name as sku_name,"
                + " t.id as leg_id,t.status as leg_status,g.quantity as shipped_quantity,"
                + " coalesce(t.settled_quantity,0) as settled_quantity,"
                + " t.shipped_at,t.expected_arrive_at,t.received_at,"
                + " t.carrier_name,t.tracking_no"
                + " from first_mile_shipment_leg_receipt g"
                + " join first_mile_shipment_line l on l.tenant_id=g.tenant_id and l.id=g.line_id"
                + " join sku_ref sk on sk.tenant_id=l.tenant_id and sk.id=l.sku_id"
                + " left join inventory_transfer t on t.tenant_id=g.tenant_id and t.id=g.transit_leg_id"
                + " where g.shipment_id=? order by l.line_no",
            shipmentId)
        .stream()
        .map(
            r -> {
              var shipped = zero(r.get("shipped_quantity", BigDecimal.class));
              var settled = zero(r.get("settled_quantity", BigDecimal.class));
              return new TrackingLegRow(
                  r.get("leg_id", UUID.class),
                  r.get("line_id", UUID.class),
                  (int) r.get("line_no", Integer.class),
                  r.get("sku_code", String.class),
                  r.get("sku_name", String.class),
                  r.get("leg_status", String.class),
                  shipped,
                  settled,
                  shipped.subtract(settled).max(BigDecimal.ZERO),
                  r.get("shipped_at", OffsetDateTime.class),
                  r.get("expected_arrive_at", OffsetDateTime.class),
                  r.get("received_at", OffsetDateTime.class),
                  r.get("carrier_name", String.class),
                  r.get("tracking_no", String.class));
            })
        .toList();
  }

  // ================================================================ 建跟踪

  /** 建跟踪记录：一张发运单一条，发运单必须**已发出**。 */
  @Transactional
  public TrackingRow create(UUID actorId, TrackingCreateRequest req) {
    var r = req == null ? emptyCreate() : req;
    if (r.shipmentId() == null) {
      throw new IllegalArgumentException("缺少必填项：shipmentId（要跟踪的发运单）");
    }
    var exists =
        sql.fetchOne(
            "select id from first_mile_tracking where tenant_id=? and shipment_id=?",
            currentTenant(),
            r.shipmentId());
    if (exists != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该发运单已建立跟踪记录（重复建跟踪会让「这批货在哪」出现两个答案）："
              + exists.get("id", UUID.class));
    }
    // 预读发运单只是为了给出可读报错；DB 触发器 guard_first_mile_tracking_shipment 会再钉一次。
    var head =
        sql.fetchOne(
            "select status, transport_mode, destination_country_code, expected_arrive_at"
                + " from first_mile_shipment where tenant_id=? and id=?",
            currentTenant(),
            r.shipmentId());
    if (head == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "发运单不存在或无权查看");
    }
    if (!head.get("status", String.class).equals("DISPATCHED")) {
      throw new IllegalArgumentException(
          "发运单当前状态是「" + head.get("status", String.class)
              + "」，货还没发出。此时建立跟踪记录会让未发运的单混进在途看板 —— "
              + "只有已发出的发运单才谈得上「在途」。");
    }

    var customs =
        r.customsStatus() == null || r.customsStatus().isBlank()
            ? CustomsStatus.NOT_STARTED
            : CustomsStatus.parse(r.customsStatus());
    var stage =
        r.trackingStage() == null || r.trackingStage().isBlank()
            ? TrackingStage.AT_ORIGIN
            : TrackingStage.parse(r.trackingStage());
    if (r.currentLocation() != null
        && !r.currentLocation().isBlank()
        && r.locationOccurredAt() == null) {
      throw new IllegalArgumentException("报了位置就必须给出位置发生时间：currentLocation 有值时 locationOccurredAt 必填");
    }
    if ((customs == CustomsStatus.HELD || customs == CustomsStatus.REJECTED)
        && (r.customsHoldReason() == null || r.customsHoldReason().isBlank())) {
      throw new IllegalArgumentException("清关状态「" + customs.label() + "」必须给出原因：没有原因的扣关事后没人知道该找谁、该补什么材料");
    }

    var id = UUID.randomUUID();
    sql.execute(
        "insert into first_mile_tracking"
            + " (id,tenant_id,shipment_id,current_location,location_occurred_at,original_eta,"
            + " customs_status,customs_status_at,customs_hold_reason,status,tracking_stage,"
            + " notes,created_by) values (?,?,?,?,?::timestamptz,?::timestamptz,?,?::timestamptz,?,?,?,?,?)",
        id,
        currentTenant(),
        r.shipmentId(),
        nz(r.currentLocation()),
        r.locationOccurredAt() == null ? null : r.locationOccurredAt().toInstant(),
        head.get("expected_arrive_at", OffsetDateTime.class) == null
            ? null
            : head.get("expected_arrive_at", OffsetDateTime.class).toInstant(),
        customs.name(),
        customs == CustomsStatus.NOT_STARTED ? null : OffsetDateTime.now(),
        nz(r.customsHoldReason()),
        TrackingStatus.TRACKING.name(),
        stage.name(),
        nz(r.notes()),
        actorId);

    // 建立动作本身也进事件流：谁、什么时候、给哪张发运单建的跟踪必须可追。
    sql.execute(
        "insert into first_mile_tracking_event"
            + " (id,tenant_id,tracking_id,kind,to_stage,location_text,detail,source_system,"
            + " reference_id,occurred_at,actor_id) values (?,?,?,?,?,?,?,?,?,?::timestamptz,?)",
        UUID.randomUUID(),
        currentTenant(),
        id,
        TrackingEventKind.CREATED.name(),
        stage.name(),
        nz(r.currentLocation()),
        "建立跟踪记录，发运单 " + r.shipmentId(),
        TrackingSource.MANUAL.name(),
        "",
        OffsetDateTime.now(),
        actorId);

    return reload(id);
  }

  // ================================================================ 节点事件

  /**
   * 登记一个节点事件，并同步更新跟踪头。
   *
   * <p>这是「货代/船司/清关状态同步」与「人工更新」的**同一个入口**：
   * 外部连接器未交付（10.4.2.1），所以两种来源形状一样就走一条路径，
   * 用 {@code sourceSystem} 区分来源而不是造两张雷同的表。
   * 将来连接器接上后调用同一入口即可，本表结构不用再改。
   */
  @Transactional
  public TrackingRow recordEvent(UUID trackingId, UUID actorId, TrackingEventRequest req) {
    var r = req == null ? emptyEvent() : req;
    var head = lockTracking(trackingId);
    var status = TrackingStatus.parse(head.get("status", String.class));
    if (status != TrackingStatus.TRACKING) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "跟踪记录状态是「" + status.label() + "」，不再接受节点更新（货都闭环或取消了）");
    }
    var kind =
        r.kind() == null || r.kind().isBlank()
            ? throwBadKind()
            : TrackingEventKind.valueOf(r.kind().trim().toUpperCase());
    if (kind == TrackingEventKind.CREATED || kind == TrackingEventKind.RECEIPT) {
      throw new IllegalArgumentException(
          "事件类型「" + kind + "」不能经本入口登记：建跟踪走建单入口，收货确认走 /receipt 端点");
    }
    var source =
        r.sourceSystem() == null || r.sourceSystem().isBlank()
            ? TrackingSource.MANUAL
            : TrackingSource.parse(r.sourceSystem());
    if (source.external() && (r.referenceId() == null || r.referenceId().isBlank())) {
      throw new IllegalArgumentException(
          "来源「" + source.label() + "」是外部来源，必须给出该来源系统里的单号 referenceId："
              + "写不出出处的外部事实不入库（连接器未交付，当前由人工代录，来源必须留痕）");
    }
    if (source == TrackingSource.MANUAL && actorId == null) {
      throw new IllegalArgumentException("人工登记必须记录操作人");
    }

    var stage = TrackingStage.parse(head.get("tracking_stage", String.class));
    var customs = CustomsStatus.parse(head.get("customs_status", String.class));
    var currentEta =
        head.get("revised_eta", OffsetDateTime.class) != null
            ? head.get("revised_eta", OffsetDateTime.class)
            : head.get("original_eta", OffsetDateTime.class);
    var occurredAt = r.occurredAt() == null ? OffsetDateTime.now() : r.occurredAt();

    String locationText = nz(r.locationText());
    OffsetDateTime locationAt = r.locationOccurredAt();
    OffsetDateTime etaAfter = null;
    OffsetDateTime etaBefore = null;
    String fromStage = null;
    String toStage = null;
    var revisedCount = head.get("eta_revised_count", Integer.class);

    switch (kind) {
      case LOCATION -> {
        if (locationText.isBlank()) {
          throw new IllegalArgumentException("位置更新必须给出 locationText：这批货现在在哪是本模块最核心的一个数");
        }
        if (locationAt == null) {
          throw new IllegalArgumentException("位置更新必须给出 locationOccurredAt：位置是「什么时候的事」不问清楚就答不出来");
        }
      }
      case ETA_REVISED -> {
        if (r.etaAfter() == null) {
          throw new IllegalArgumentException("改期必须给出新的预计到港 etaAfter");
        }
        if (r.reason() == null || r.reason().isBlank()) {
          throw new IllegalArgumentException("改期必须给出原因：货代改期是断货预警的主来源，没有原因的改期事后无法复盘");
        }
        if (currentEta != null && r.etaAfter().isBefore(currentEta)) {
          throw new IllegalArgumentException(
              "新的预计到港 " + r.etaAfter() + " 早于当前 ETA " + currentEta
                  + "：把 ETA 往前调会让「已经晚了几天」算成负数，而这正是本模块最需要答对的数");
        }
        etaBefore = currentEta;
        etaAfter = r.etaAfter();
        revisedCount = revisedCount + 1;
      }
      case CUSTOMS -> {
        if (r.customsStatus() == null || r.customsStatus().isBlank()) {
          throw new IllegalArgumentException("清关事件必须给出新的清关状态 customsStatus");
        }
        customs = CustomsStatus.parse(r.customsStatus());
        if ((customs == CustomsStatus.HELD || customs == CustomsStatus.REJECTED)
            && (r.customsHoldReason() == null || r.customsHoldReason().isBlank())) {
          throw new IllegalArgumentException(
              "清关状态「" + customs.label() + "」必须给出原因 customsHoldReason："
                  + "没有原因的扣关事后没人知道该找谁、该补什么材料");
        }
      }
      case STAGE -> {
        if (r.toStage() == null || r.toStage().isBlank()) {
          throw new IllegalArgumentException("阶段推进必须给出 toStage");
        }
        var to = TrackingStage.parse(r.toStage());
        if (!allowedNextStage(stage).contains(to)) {
          throw new IllegalArgumentException(
              "阶段不允许从「" + stage.label() + "」到「" + to.label() + "」："
                  + "在途是推进过程，倒退会让「这批货到过哪」永远答不出来");
        }
        fromStage = stage.name();
        toStage = to.name();
        stage = to;
      }
      case NOTE, ALERT -> {
        if (r.detail() == null || r.detail().isBlank()) {
          throw new IllegalArgumentException("备注类事件必须给出 detail");
        }
      }
      default -> throw new IllegalArgumentException("不支持的事件类型：" + kind);
    }

    // 更新跟踪头（位置 / ETA / 清关 / 阶段一起落，保持头与事件流自洽）。
    var newLocation = locationText.isBlank() ? head.get("current_location", String.class) : locationText;
    var newLocationAt =
        locationText.isBlank() ? head.get("location_occurred_at", OffsetDateTime.class) : locationAt;
    var newCustomsAt = kind == TrackingEventKind.CUSTOMS ? occurredAt : head.get("customs_status_at", OffsetDateTime.class);
    var newHoldReason =
        kind == TrackingEventKind.CUSTOMS ? nz(r.customsHoldReason()) : head.get("customs_hold_reason", String.class);

    sql.execute(
        "update first_mile_tracking set"
            + " current_location=?, location_occurred_at=?::timestamptz, revised_eta=?::timestamptz,"
            + " eta_revised_count=?,"
            + " customs_status=?, customs_status_at=?::timestamptz, customs_hold_reason=?,"
            + " tracking_stage=?, notes=?, version=version+1, updated_at=now()"
            + " where tenant_id=? and id=?",
        newLocation,
        newLocationAt == null ? null : newLocationAt.toInstant(),
        etaAfter == null ? head.get("revised_eta", OffsetDateTime.class) : etaAfter.toInstant(),
        revisedCount,
        customs.name(),
        newCustomsAt == null ? null : newCustomsAt.toInstant(),
        newHoldReason,
        stage.name(),
        head.get("notes", String.class),
        currentTenant(),
        trackingId);

    sql.execute(
        "insert into first_mile_tracking_event"
            + " (id,tenant_id,tracking_id,kind,to_stage,from_stage,location_text,detail,"
            + " eta_before,eta_after,source_system,reference_id,occurred_at,actor_id)"
            + " values (?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?::timestamptz,?)",
        UUID.randomUUID(),
        currentTenant(),
        trackingId,
        kind.name(),
        toStage,
        fromStage,
        locationText,
        kind == TrackingEventKind.ETA_REVISED ? nz(r.reason()) + (r.detail() == null ? "" : " · " + r.detail()) : nz(r.detail()),
        etaBefore == null ? null : etaBefore.toInstant(),
        etaAfter == null ? null : etaAfter.toInstant(),
        source.name(),
        nz(r.referenceId()),
        occurredAt.toInstant(),
        source == TrackingSource.MANUAL ? actorId : actorId);

    // 阶段/清关/改期都会改变剩余窗口 → 立刻重算预警（幂等：同档位已有未结预警就只刷新）。
    raiseAlerts(trackingId, actorId);
    return reload(trackingId);
  }

  private static TrackingEventKind throwBadKind() {
    throw new IllegalArgumentException("缺少必填项：kind（事件类型）");
  }

  // ================================================================ 收货确认 / 关闭

  /**
   * 目的仓收货确认：**只记确认事实，不写任何库存**。
   *
   * <p>入库落账归 7.5.1.2 的 arrive。本方法把阶段推进到 RECEIVED、记确认人与回单号、
   * 写下事件行并把跟踪关掉；短少/破损等差异如实写进 notes，不自动抹平。
   */
  @Transactional
  public TrackingRow confirmReceipt(UUID trackingId, UUID actorId, TrackingReceiptRequest req) {
    var r = req == null ? emptyReceipt() : req;
    var head = lockTracking(trackingId);
    if (!TrackingStatus.parse(head.get("status", String.class)).equals(TrackingStatus.TRACKING)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "跟踪记录已闭环或已取消，不再重复确认收货");
    }
    var stage = TrackingStage.parse(head.get("tracking_stage", String.class));
    if (!allowedNextStage(stage).contains(TrackingStage.RECEIVED)) {
      throw new IllegalArgumentException(
          "当前阶段「" + stage.label() + "」不能直接进「已收货确认」："
              + "清关未放行就确认收货会让「货被扣在关口却已入库」同时成立");
    }
    var receivedAt = r.receivedAt() == null ? OffsetDateTime.now() : r.receivedAt();

    sql.execute(
        "update first_mile_tracking set"
            + " tracking_stage='RECEIVED', receipt_confirmed_at=?, receipt_confirmed_by=?,"
            + " receipt_reference=?, notes=?, status='CLOSED', closed_at=?, closed_by=?,"
            + " version=version+1, updated_at=now()"
            + " where tenant_id=? and id=?",
        receivedAt.toInstant(),
        actorId,
        nz(r.receiptReference()),
        nz(r.notes()),
        OffsetDateTime.now().toInstant(),
        actorId,
        currentTenant(),
        trackingId);

    sql.execute(
        "insert into first_mile_tracking_event"
            + " (id,tenant_id,tracking_id,kind,to_stage,from_stage,detail,source_system,"
            + " reference_id,occurred_at,actor_id)"
            + " values (?,?,?,?,?,?,?,?,?,?::timestamptz,?)",
        UUID.randomUUID(),
        currentTenant(),
        trackingId,
        TrackingEventKind.RECEIPT.name(),
        TrackingStage.RECEIVED.name(),
        stage.name(),
        "目的仓收货确认：回单 "
            + (r.receiptReference() == null || r.receiptReference().isBlank() ? "（未填）" : r.receiptReference())
            + (r.notes() == null || r.notes().isBlank() ? "" : " · " + r.notes())
            + "。入库落账归 7.5.1.2 的 arrive，本模块只记确认事实。",
        TrackingSource.MANUAL.name(),
        nz(r.receiptReference()),
        receivedAt.toInstant(),
        actorId);

    return reload(trackingId);
  }

  /** 关闭跟踪（货已闭环 / 已被下游接收跟踪）。 */
  @Transactional
  public TrackingRow close(UUID trackingId, UUID actorId, TrackingCloseRequest req) {
    var r = req == null ? emptyClose() : req;
    var head = lockTracking(trackingId);
    var status = TrackingStatus.parse(head.get("status", String.class));
    if (status != TrackingStatus.TRACKING) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "跟踪记录当前状态是「" + status.label() + "」，已闭环或已取消");
    }
    if (r.reason() == null || r.reason().isBlank()) {
      throw new IllegalArgumentException("关闭跟踪必须给出原因：没有原因的闭环事后无法解释这批货为什么停止跟踪");
    }
    sql.execute(
        "update first_mile_tracking set status='CLOSED', closed_at=?, closed_by=?,"
            + " notes=coalesce(nullif(notes,''),'')||'｜关闭：'||?, version=version+1, updated_at=now()"
            + " where tenant_id=? and id=?",
        OffsetDateTime.now().toInstant(),
        actorId,
        r.reason(),
        currentTenant(),
        trackingId);
    sql.execute(
        "insert into first_mile_tracking_event"
            + " (id,tenant_id,tracking_id,kind,detail,source_system,reference_id,occurred_at,actor_id)"
            + " values (?,?,?,?,?,?,?,?::timestamptz,?)",
        UUID.randomUUID(),
        currentTenant(),
        trackingId,
        TrackingEventKind.NOTE.name(),
        "关闭跟踪：" + r.reason(),
        TrackingSource.MANUAL.name(),
        "",
        OffsetDateTime.now().toInstant(),
        actorId);
    return reload(trackingId);
  }

  // ================================================================ 预警

  /** 预警清单 + 分档汇总。 */
  public DelayAlertPage alerts(List<String> level, List<String> status, String keyword, int page, int size) {
    var where = new StringBuilder(" where 1=1 ");
    var args = new ArrayList<Object>();
    filterIn(where, args, "a.level", level);
    filterIn(where, args, "a.status", status);
    if (keyword != null && !keyword.isBlank()) {
      var kw = "%" + keyword.trim() + "%";
      where.append(" and (s.shipment_code ilike ? or a.message ilike ?)");
      args.add(kw);
      args.add(kw);
    }
    var all =
        sql.fetch(
            "select a.*,s.shipment_code from first_mile_delay_alert a"
                + " join first_mile_tracking t on t.tenant_id=a.tenant_id and t.id=a.tracking_id"
                + " join first_mile_shipment s on s.tenant_id=t.tenant_id and s.id=t.shipment_id"
                + where
                + " order by a.raised_at desc, a.id",
            args.toArray());
    long watch = 0, warning = 0, critical = 0, open = 0, acked = 0;
    for (Record r : all) {
      switch (r.get("level", String.class)) {
        case "WATCH" -> watch++;
        case "WARNING" -> warning++;
        case "CRITICAL" -> critical++;
        default -> {}
      }
      if (r.get("status", String.class).equals("OPEN")) {
        open++;
      } else if (r.get("status", String.class).equals("ACKNOWLEDGED")) {
        acked++;
      }
    }
    int sz = clampSize(size);
    int pg = Math.max(0, page);
    int from = Math.min(pg * sz, all.size());
    int to = Math.min(from + sz, all.size());
    var rows = new ArrayList<DelayAlertRow>();
    for (int i = from; i < to; i++) {
      rows.add(alertOf(all.get(i)));
    }
    return new DelayAlertPage(
        rows,
        all.size(),
        open,
        acked,
        watch,
        warning,
        critical,
        options(DelayLevel.values()));
  }

  private DelayAlertRow alertOf(Record r) {
    return new DelayAlertRow(
        r.get("id", UUID.class),
        r.get("tracking_id", UUID.class),
        r.get("shipment_code", String.class),
        r.get("level", String.class),
        r.get("threshold_id", UUID.class),
        r.get("lead_days", Integer.class),
        r.get("remaining_days", BigDecimal.class),
        r.get("message", String.class),
        r.get("status", String.class),
        r.get("raised_at", OffsetDateTime.class),
        r.get("raised_for", OffsetDateTime.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("closed_at", OffsetDateTime.class),
        r.get("close_reason", String.class));
  }

  private List<DelayAlertRow> alertRowsForTracking(UUID trackingId) {
    return sql.fetch(
            "select a.*,'' as shipment_code from first_mile_delay_alert a"
                + " where a.tracking_id=? order by a.raised_at desc",
            trackingId)
        .stream()
        .map(this::alertOf)
        .toList();
  }

  /**
   * 评估并落库预警 —— 蓝图的「延误预警 + 到港提醒」。
   *
   * <p>幂等：同一跟踪同一档位已有未结预警时只**刷新**（remaining_days 与 message），
   * 不重复插行 —— 否则每评估一次看板上同一个延误会堆出十几条一模一样的告警。
   * 档位降级（CRITICAL → WATCH）时把已结的旧档预警关掉，
   * 保证「未结预警」永远等于「当前真实档位」。
   */
  @Transactional
  public int raiseAlerts(UUID trackingId, UUID actorId) {
    var head =
        sql.fetchOne(
            "select t.*, s.transport_mode, s.destination_country_code,"
                + " extract(epoch from (coalesce(t.revised_eta,t.original_eta) - now()))/86400.0"
                + "   as remaining_days_raw"
                + " from first_mile_tracking t"
                + " join first_mile_shipment s on s.tenant_id=t.tenant_id and s.id=t.shipment_id"
                + " where t.id=?",
            trackingId);
    if (head == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "跟踪记录不存在或无权查看");
    }
    if (!head.get("status", String.class).equals(TrackingStatus.TRACKING.name())) {
      return 0; // 已闭环/已取消不再产生预警
    }
    if (head.get("tracking_stage", String.class).equals(TrackingStage.RECEIVED.name())) {
      return 0; // 货已收到，不再报延误
    }
    var remaining = days(head.get("remaining_days_raw", BigDecimal.class));
    if (remaining == null) {
      return 0; // 没有 ETA 无法分级：不猜一个档，也不造一条空预警
    }
    var threshold =
        effectiveThreshold(
            head.get("transport_mode", String.class), head.get("destination_country_code", String.class));
    var level = evaluateLevel(remaining, threshold);
    var shipmentCode =
        sql.fetchOne(
                "select shipment_code from first_mile_shipment where tenant_id=? and id=?",
                currentTenant(),
                head.get("shipment_id", UUID.class))
            .get("shipment_code", String.class);

    if (level == DelayLevel.ON_TRACK) {
      // 回到正常：把该跟踪下所有未结预警关掉，否则「未结预警」会长期挂着已解除的延误。
      sql.execute(
          "update first_mile_delay_alert set status='CLOSED', closed_at=now(), closed_by=?,"
              + " close_reason='剩余窗口已回到正常区间，预警自动解除'"
              + " where tenant_id=? and tracking_id=? and status in ('OPEN','ACKNOWLEDGED')",
          actorId,
          currentTenant(),
          trackingId);
      return 0;
    }

    var message =
        switch (level) {
          case CRITICAL ->
              remaining.signum() < 0
                  ? "已过预计到港 " + remaining.abs().stripTrailingZeros().toPlainString()
                      + " 天仍未到货（发运单 " + shipmentCode + "）"
                  : "剩余窗口仅 " + remaining.toPlainString() + " 天，已进入严重延误区（发运单 "
                      + shipmentCode + "）";
          case WARNING ->
              "距预计到港只剩 " + remaining.toPlainString()
                  + " 天（发运单 " + shipmentCode + "），已进入延误预警区";
          default ->
              "距预计到港 " + remaining.toPlainString()
                  + " 天（发运单 " + shipmentCode + "），进入临近到港提示区";
        };

    var existing =
        sql.fetchOne(
            "select * from first_mile_delay_alert where tenant_id=? and tracking_id=? and level=?"
                + " and status in ('OPEN','ACKNOWLEDGED')",
            currentTenant(),
            trackingId,
            level.name());
    if (existing != null) {
      // 刷新而不新增：同档位未结预警已存在，只更新口径与描述。
      sql.execute(
          "update first_mile_delay_alert set remaining_days=?, message=?, threshold_id=?,"
              + " lead_days=?, raised_for=?::timestamptz"
              + " where tenant_id=? and id=?",
          remaining,
          message,
          threshold.thresholdId(),
          leadDaysOf(level, threshold),
          head.get("revised_eta", OffsetDateTime.class) != null
              ? head.get("revised_eta", OffsetDateTime.class).toInstant()
              : (head.get("original_eta", OffsetDateTime.class) == null
                  ? null
                  : head.get("original_eta", OffsetDateTime.class).toInstant()),
          currentTenant(),
          existing.get("id", UUID.class));
      closeBelow(trackingId, level, actorId);
      return 0;
    }

    sql.execute(
        "insert into first_mile_delay_alert"
            + " (id,tenant_id,tracking_id,level,threshold_id,lead_days,remaining_days,message,"
            + " status,raised_at,raised_for) values (?,?,?,?,?,?,?,?, 'OPEN', now(), ?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        trackingId,
        level.name(),
        threshold.thresholdId(),
        leadDaysOf(level, threshold),
        remaining,
        message,
        head.get("revised_eta", OffsetDateTime.class) != null
            ? head.get("revised_eta", OffsetDateTime.class).toInstant()
            : (head.get("original_eta", OffsetDateTime.class) == null
                ? null
                : head.get("original_eta", OffsetDateTime.class).toInstant()));
    closeBelow(trackingId, level, actorId);
    return 1;
  }

  /** 档位回落时把比当前档更轻的未结预警关掉（保持「未结 = 当前真实档位」）。 */
  private void closeBelow(UUID trackingId, DelayLevel level, UUID actorId) {
    var lighter =
        switch (level) {
          case CRITICAL -> List.of("WATCH", "WARNING");
          case WARNING -> List.of("WATCH");
          default -> List.<String>of();
        };
    if (lighter.isEmpty()) {
      return;
    }
    sql.execute(
        "update first_mile_delay_alert set status='CLOSED', closed_at=now(), closed_by=?,"
            + " close_reason='档位已回落，自动解除'"
            + " where tenant_id=? and tracking_id=? and level in ("
            + "?,".repeat(lighter.size() - 1)
            + "?) and status in ('OPEN','ACKNOWLEDGED')",
        args(lighter, actorId, currentTenant(), trackingId));
  }

  private Object[] args(List<String> levels, UUID actorId, UUID tenant, UUID trackingId) {
    var out = new ArrayList<Object>();
    out.add(actorId);
    out.add(tenant);
    out.add(trackingId);
    out.addAll(levels);
    return out.toArray();
  }

  /** 批量评估：把在途跟踪全部重算一遍。**没有定时器** —— 由调用方（页面刷新/运维）触发。 */
  @Transactional
  public int evaluateAll() {
    var ids =
        sql.fetch("select id from first_mile_tracking where tenant_id=? and status='TRACKING'", currentTenant())
            .stream()
            .map(r -> r.get("id", UUID.class))
            .toList();
    int raised = 0;
    for (var id : ids) {
      raised += raiseAlerts(id, null);
    }
    return raised;
  }

  /** 确认预警（有人在跟了）。 */
  @Transactional
  public DelayAlertRow acknowledge(UUID alertId, UUID actorId, DelayAlertAckRequest req) {
    var row = lockAlert(alertId);
    var status = row.get("status", String.class);
    if (!status.equals("OPEN")) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "预警当前状态是「" + status + "」，只有未处理的才能确认");
    }
    var note = req == null ? "" : nz(req.note());
    sql.execute(
        "update first_mile_delay_alert set status='ACKNOWLEDGED', acknowledged_at=now(),"
            + " acknowledged_by=?, message=message||? where tenant_id=? and id=?",
        actorId,
        note.isBlank() ? "" : "｜跟进：" + note,
        currentTenant(),
        alertId);
    return reloadAlert(alertId);
  }

  /** 关闭预警（必须给原因：货到了 / 改期了 / 查明了都算）。 */
  @Transactional
  public DelayAlertRow closeAlert(UUID alertId, UUID actorId, DelayAlertCloseRequest req) {
    var row = lockAlert(alertId);
    if (row.get("status", String.class).equals("CLOSED")) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "预警已关闭");
    }
    var r = req == null ? new DelayAlertCloseRequest(null) : req;
    if (r.reason() == null || r.reason().isBlank()) {
      throw new IllegalArgumentException("关闭预警必须给出原因：货到了 / 改期了 / 查明原因都算");
    }
    sql.execute(
        "update first_mile_delay_alert set status='CLOSED', closed_at=now(), closed_by=?,"
            + " close_reason=? where tenant_id=? and id=?",
        actorId,
        r.reason(),
        currentTenant(),
        alertId);
    return reloadAlert(alertId);
  }

  private DelayAlertRow reloadAlert(UUID alertId) {
    return alertOf(
        sql.fetchOne("select * from first_mile_delay_alert where tenant_id=? and id=?", currentTenant(), alertId));
  }

  // ================================================================ 内部助手

  private Record lockTracking(UUID trackingId) {
    var r =
        sql.fetchOne(
            "select * from first_mile_tracking where tenant_id=? and id=? for update",
            currentTenant(),
            trackingId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "跟踪记录不存在或无权查看");
    }
    return r;
  }

  private Record lockAlert(UUID alertId) {
    var r =
        sql.fetchOne(
            "select * from first_mile_delay_alert where tenant_id=? and id=? for update",
            currentTenant(),
            alertId);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预警不存在或无权查看");
    }
    return r;
  }

  private TrackingRow reload(UUID trackingId) {
    return detail(trackingId).tracking();
  }

  /**
   * 当前 RLS 租户：来自已认证成员关系，由 {@link AccessService#run} 在事务内设置。
   *
   * <p>读的是 {@code nullif(current_setting('app.current_tenant',true),'')::uuid} ——
   * 与 10.4.1.1 / 7.5.1.2 同一句：未设置时得到 {@code null} 而不是「解析空串抛异常」，
   * RLS 随后把查询结果收成空集（租户没绑上时什么都查不到，而不是查到别人的）。
   */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static String nz(String v) {
    return v == null ? "" : v;
  }

  private static TrackingCreateRequest emptyCreate() {
    return new TrackingCreateRequest(null, null, null, null, null, null, null);
  }

  private static TrackingEventRequest emptyEvent() {
    return new TrackingEventRequest(
        null, null, null, null, null, null, null, null, null, null, null, null);
  }

  private static TrackingReceiptRequest emptyReceipt() {
    return new TrackingReceiptRequest(null, null, null);
  }

  private static TrackingCloseRequest emptyClose() {
    return new TrackingCloseRequest(null);
  }
}