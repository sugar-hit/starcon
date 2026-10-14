package com.starcon.warehouse;

import static com.starcon.warehouse.ReservationReleaseModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.2.1.3 释放与过期。
 *
 * <p>蓝图：「处理预占的释放（订单取消、支付超时、拆单减少）与过期回收，提供手动释放入口与
 * 批量过期清理，释放时同步恢复可用量。」输出「预占终态记录 + 余额恢复分录」。
 *
 * <p><b>八条实现纪律</b>：
 *
 * <ol>
 *   <li><b>承诺后不自动过期</b>（蓝图红线）。批量清理的候选谓词只含 {@code status='HELD'}，
 *       且**预占名下只要还有一条 COMMITTED 分配就整笔跳过** —— 不只是跳过那条分配。
 *       只跳过分配而仍让预占进终态，会造出「预占已结束但有货还占着、且再也放不掉」的死状态：
 *       预占进终态后不能再挂新分配，而那条 COMMITTED 分配的货会一直占着可用量。
 *   <li><b>恢复量与释放量是两个数</b>。{@code restoredQuantity} 是真的还回可用池的量
 *       （各分配剩余量之和），{@code releasedDemandQuantity} 是预占口径上放弃的量。
 *       需求 12、只圈到 10 的货整笔释放时两者是 10 与 12；合并成一个数会让可用量凭空多 2，
 *       而「可用量只准有一个来源」是本卷硬约束。
 *   <li><b>不写 inventory_entry</b>，也不碰 inventory_balance / stock_bucket 的 reserved。
 *       7.2.1.2 登记分配时同样不写（那才是 reserved 变大的时刻）；本模块单方面写账本，
 *       会与余额侧对不上。可用量口径由 7.1.1.3 裁决 —— 本模块表达的是「退出占额集合 + 记凭据」。
 *   <li><b>时间一律带偏移量</b>：{@code ?::timestamptz} 绑定。用
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s。
 *   <li><b>写操作优先采信调用方给的发生时刻</b>。订单取消发生在上游，用服务端当前时间
 *       会把「什么时候取消的」换成「本服务什么时候知道的」。
 *   <li><b>更新一律带状态谓词并检查影响行数</b>。终态不可复活由 V102 / V107 的触发器兜底，
 *       但「更新 0 行却回执成功」是调用方最没法排查的一种假成功。
 *   <li><b>批量清理是一事务、全成功或全回滚</b>。半途失败留下「处理了 37 条然后失败」的中间态时，
 *       事后无法判断哪 37 条是真的终结了。
 *   <li><b>重放不是错误</b>。上游事件是至少投递一次，同键同参回放既有凭据、已进同种终态也回放，
 *       返回 {@code replayed=true}。把重放当错误会让上游的重试变成故障源。
 * </ol>
 *
 * <p>租户来自已认证成员关系（控制器传入），不接受任何客户端租户参数；RLS 在数据库层强制隔离，
 * 服务层的 {@code tenant_id} 谓词是第二道防线。
 */
@Service
public class ReservationReleaseService {

  /** 单页最大行数。与 ReservationService / ReservationAllocationService 同口径。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 批量清理单批硬上限。与 V112 的 CHECK limit_count<=1000 同口径，两处不各写一个数。 */
  static final int MAX_RUN_LIMIT = 1000;

  /** 排序键白名单：排序键来自 query string，只允许这四个。 */
  static final List<String> SORTS = List.of("EXPIRES_AT", "OVERDUE", "RESTORABLE", "RESERVATION_NO");

  private static final String TENANT = "current_setting('app.current_tenant', true)::uuid";

  private static final String RULE_NOTE =
      "过期判据 = status='HELD' AND expires_at 不为空 AND expires_at <= 判定时刻；"
          + "COMMITTED 永不自动 TTL 释放（蓝图红线），且预占名下只要还有一条 COMMITTED 分配，"
          + "整笔就跳过批量回收 —— 否则会造出「预占已终结、货还占着且再也放不掉」的死状态。"
          + "干跑（dryRun）只写一条运行凭据，不碰预占 / 分配 / 流水。";

  private static final String NOTE =
      "释放把分配退出占额集合（V107 的回写触发器随即把父预占的已分配量重算），"
          + "并写一条 append-only 分录与一条 append-only 释放凭据；"
          + "恢复量以各分配本次释放量之和为准，可用量的重算由 7.1.1.3 裁决。";

  private static final String OWNER_NOTE =
      "reservation_release_record / reservation_expiry_run 与预占链路三张表同属 warehouse"
          + "（V112 / V107 / V102 文件头第零节），不跨服务读写 trade 的表；"
          + "可用量口径属 7.1.1.3，本模块不重复计算。";

  private static final String TIMEZONE_NOTE =
      "时间一律带时区偏移：库里是 timestamptz，界面按浏览器本地时区显示，"
          + "服务端不做二次时区转换（转换两次就会把偏移量算错 8 小时）";

  private final DSLContext sql;
  private final Clock clock;

  public ReservationReleaseService(DSLContext sql) {
    this.sql = sql;
    // 「现在」用系统时钟取，与两个兄弟 service 同理：固定时钟会让测试变成只测一个假想的时刻。
    this.clock = Clock.systemDefaultZone();
  }

  // ================================================================ 过期候选

  /**
   * 过期候选列表（批量清理的对象）+ 汇总。
   *
   * <p>筛选：来源类型、关键字（预占单号 / 上游单号 / SKU 编码 / SKU 名称 / 备注）、排序、真分页。
   *
   * <p>{@code evaluatedAt} 把判定时刻**逐字返回**：页面上必须写清「这份清单按哪个时刻算的」，
   * 否则用户会把预演结果当成实时状态。
   */
  public ReservationReleaseCandidatePage candidates(UUID tenantId, ReservationReleaseQuery q) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, q.size() == null ? 20 : q.size()));
    int page = Math.max(0, q.page() == null ? 0 : q.page());
    OffsetDateTime evaluatedAt = q.evaluatedAt() == null ? now() : q.evaluatedAt().truncatedTo(ChronoUnit.MILLIS);

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    // 排序与 where 之前的 select 段绑定顺序必须一致，所以判定时刻先进 binds。
    binds.add(evaluatedAt);
    where.add("r.tenant_id = " + TENANT);
    where.add("r.status='HELD'");
    where.add("r.expires_at is not null");
    where.add("r.expires_at <= ?::timestamptz");

    if (q.sourceType() != null && !q.sourceType().isBlank()) {
      where.add("r.source_type = ?");
      binds.add(q.sourceType().toUpperCase(Locale.ROOT).trim());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      where.add("(r.reservation_no ilike ? or r.source_ref_code ilike ?"
          + " or r.note ilike ? or sk.code ilike ? or sk.name ilike ?)");
      String like = "%" + q.keyword().trim() + "%";
      for (int i = 0; i < 5; i++) binds.add(like);
    }
    String sqlWhere = " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from inventory_reservation r" + sqlWhere, binds.toArray())
            .get(0, Long.class);

    List<UUID> ids =
        sql.fetch(
                "select r.id from inventory_reservation r"
                    + " left join sku_ref sk on sk.tenant_id=r.tenant_id and sk.id=r.sku_id"
                    + sqlWhere
                    + orderBy(q.sort())
                    + " limit ? offset ?",
                bindsToArray(binds, size, page * size))
            .map(r -> r.get("id", UUID.class));

    List<ReservationReleaseCandidateRow> rows = new ArrayList<>();
    for (UUID id : ids) rows.add(candidateRow(id, evaluatedAt));

    return new ReservationReleaseCandidatePage(
        summary(evaluatedAt), rows, total, page, size, evaluatedAt, RULE_NOTE, NOTE);
  }

  /**
   * 候选行查询。
   *
   * <p>行与行之间**不做 join 展开**：可还回量用标量子查询算。一个子查询比一次
   * {@code left join + group by} 便宜得多，而且计数不会被分配行数放大 ——
   * 7.2.1.1 的状态汇总已经因为同一个原因踩过一次。
   */
  private static final String CANDIDATE_SELECT =
      """
      select r.id, r.reservation_no, r.source_system, r.source_type, r.source_ref_id,
             r.source_ref_code, r.sku_id, r.legal_entity_id,
             r.requested_quantity, r.allocated_quantity, r.consumed_quantity, r.released_quantity,
             r.status, r.held_at, r.expires_at, r.note,
             le.code as le_code, sk.code as sku_code, sk.name as sku_name,
             (select coalesce(sum(a.remaining),0) from reservation_allocation a
               where a.tenant_id=r.tenant_id and a.reservation_id=r.id
                 and a.status not in ('RELEASED','EXPIRED')) as restorable
        from inventory_reservation r
        left join legal_entity_ref le on le.tenant_id=r.tenant_id and le.id=r.legal_entity_id
        left join sku_ref sk on sk.tenant_id=r.tenant_id and sk.id=r.sku_id
       where r.tenant_id = %s and r.id = ?
      """.formatted(TENANT);

  private ReservationReleaseCandidateRow candidateRow(UUID reservationId, OffsetDateTime evaluatedAt) {
    var r =
        sql.fetchOne(
            CANDIDATE_SELECT,
            reservationId);
    if (r == null) throw notFound(reservationId);

    OffsetDateTime expiresAt = r.get("expires_at", OffsetDateTime.class);
    long overdueSeconds =
        Math.max(
            0L,
            Duration.between(expiresAt, evaluatedAt).getSeconds());

    return new ReservationReleaseCandidateRow(
        reservationId,
        r.get("reservation_no", String.class),
        r.get("source_system", String.class),
        ReservationModels.SourceSystem.valueOf(r.get("source_system", String.class)).label(),
        r.get("source_type", String.class),
        ReservationModels.SourceType.valueOf(r.get("source_type", String.class)).label(),
        r.get("source_ref_id", UUID.class),
        r.get("source_ref_code", String.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("le_code", String.class),
        r.get("requested_quantity", BigDecimal.class),
        r.get("allocated_quantity", BigDecimal.class),
        r.get("consumed_quantity", BigDecimal.class),
        r.get("released_quantity", BigDecimal.class),
        r.get("restorable", BigDecimal.class),
        r.get("status", String.class),
        ReservationModels.ReservationStatus.valueOf(r.get("status", String.class)).label(),
        r.get("held_at", OffsetDateTime.class),
        expiresAt,
        overdueSeconds,
        allocationLines(reservationId),
        r.get("note", String.class));
  }

  /** 该预占下**仍在占额**的分配明细。终态分配不出现在这里：它们早已还回可用量。 */
  private List<ReservationReleaseAllocationLine> allocationLines(UUID reservationId) {
    return sql.fetch(
            """
            select a.id, a.status, a.allocated_quantity, a.consumed_quantity,
                   a.released_quantity, a.remaining, a.allocated_at,
                   a.warehouse_id, w.code as warehouse_code,
                   a.location_id, l.code as location_code,
                   a.lot_id, lt.lot_code
              from reservation_allocation a
              join warehouse w on w.tenant_id=a.tenant_id and w.id=a.warehouse_id
              left join warehouse_location l on l.tenant_id=a.tenant_id and l.id=a.location_id
              left join inventory_lot lt on lt.tenant_id=a.tenant_id and lt.id=a.lot_id
             where a.tenant_id = %s and a.reservation_id = ?
               and a.status not in ('RELEASED','EXPIRED')
             order by a.allocated_at, a.id
            """.formatted(TENANT),
            reservationId)
        .map(
            r -> {
              var status = ReservationAllocationModels.ReservationAllocationStatus.valueOf(
                  r.get("status", String.class));
              return new ReservationReleaseAllocationLine(
                  r.get("id", UUID.class),
                  status.name(),
                  status.label(),
                  r.get("warehouse_id", UUID.class),
                  r.get("warehouse_code", String.class),
                  r.get("location_id", UUID.class),
                  r.get("location_code", String.class),
                  r.get("lot_id", UUID.class),
                  r.get("lot_code", String.class),
                  r.get("allocated_quantity", BigDecimal.class),
                  r.get("consumed_quantity", BigDecimal.class),
                  r.get("released_quantity", BigDecimal.class),
                  r.get("remaining", BigDecimal.class),
                  r.get("allocated_at", OffsetDateTime.class));
            });
  }

  /**
   * 候选汇总。**不受分页与筛选影响**。
   *
   * <p>{@code mixedCommitmentSkippedCount} 是「已超时但因为还有承诺在身而不会被自动回收」的条数。
   * 它必须被显式报出来：这些预占在列表上看起来和普通候选一模一样，不报的话运营永远发现不了
   * 「为什么这批货一直占着」。
   */
  private ReservationReleaseSummary summary(OffsetDateTime evaluatedAt) {
    var r =
        sql.fetchOne(
            """
            select count(*) filter (where r.status='HELD') as held_count,
                   count(*) filter (where r.status='HELD' and r.expires_at is not null
                                      and r.expires_at <= ?::timestamptz) as overdue_count,
                   count(*) filter (where r.status='HELD' and r.expires_at is null) as unmanaged_count,
                   count(*) filter (where r.status='COMMITTED') as committed_count,
                   count(*) filter (where r.status='RELEASED') as released_count,
                   count(*) filter (where r.status='EXPIRED')  as expired_count,
                   coalesce(sum(r.released_quantity),0) as released_total,
                   coalesce((select sum(a.remaining) from reservation_allocation a
                               join inventory_reservation r2
                                 on r2.tenant_id=a.tenant_id and r2.id=a.reservation_id
                              where a.tenant_id = %s
                                and a.status not in ('RELEASED','EXPIRED')
                                and r2.status='HELD' and r2.expires_at is not null
                                and r2.expires_at <= ?::timestamptz), 0) as overdue_restorable,
                   count(*) filter (where r.status='HELD' and r.expires_at is not null
                                      and r.expires_at <= ?::timestamptz
                                      and exists (select 1 from reservation_allocation a
                                                   where a.tenant_id = %s
                                                     and a.reservation_id=r.id
                                                     and a.status='COMMITTED')) as mixed_committed
              from inventory_reservation r
             where r.tenant_id = %s
            """.formatted(TENANT, TENANT, TENANT),
            evaluatedAt, evaluatedAt, evaluatedAt);

    Map<String, Long> modeCounts = new LinkedHashMap<>();
    modeCounts.put(ReservationReleaseMode.RELEASE.name(), 0L);
    modeCounts.put(ReservationReleaseMode.EXPIRE.name(), 0L);
    for (var one :
        sql.fetch(
                "select mode, count(*) as n from reservation_release_record where tenant_id = " + TENANT
                    + " group by mode",
                new Object[0])) {
      modeCounts.put(one.get("mode", String.class), one.get("n", Long.class));
    }

    return new ReservationReleaseSummary(
        r.get("held_count", Long.class),
        r.get("overdue_count", Long.class),
        r.get("overdue_restorable", BigDecimal.class),
        r.get("unmanaged_count", Long.class),
        r.get("committed_count", Long.class),
        r.get("mixed_committed", Long.class),
        r.get("released_count", Long.class),
        r.get("expired_count", Long.class),
        r.get("released_total", BigDecimal.class),
        modeCounts);
  }

  private String orderBy(String sort) {
    String key = sort == null || sort.isBlank() ? "EXPIRES_AT" : sort.toUpperCase(Locale.ROOT);
    if (!SORTS.contains(key)) throw new IllegalArgumentException("不支持的排序键：" + sort);
    // 一律以 r.id 收尾：排序键相同时必须有确定次序，否则同一页两次请求可能给出不同顺序。
    return switch (key) {
      // 最久没被处理的排前面：清理队列天然按「先到期先回收」。
      case "OVERDUE" -> " order by r.expires_at, r.id";
      case "RESTORABLE" -> " order by restorable desc, r.id";
      case "RESERVATION_NO" -> " order by r.reservation_no, r.id";
      default -> " order by r.expires_at, r.id";
    };
  }

  // ================================================================ 手动释放

  /**
   * 手动释放（订单取消 / 支付超时 / 拆单减少 / 人工）。
   *
   * <p>三条入口纪律：
   *
   * <ol>
   *   <li><b>终态重放</b>：预占已是 RELEASED 时直接回放既有凭据（200，{@code replayed=true}）；
   *       已是 EXPIRED / CONSUMED 时 409 —— 那是另一种结束方式，重复执行会改变历史叙述。
   *   <li><b>来源词表</b>：EXPIRY_SWEEP 只能由批量清理产生，人工入口给了它就会让
   *       「等太久」与「被取消」在统计上分不开。
   *   <li><b>不承诺释放不存在的货</b>：数量超过该分配剩余量 → 409，而不是截断。
   *       静默截断会让调用方以为整笔都放掉了。
   * </ol>
   *
   * <p><b>加锁顺序与 7.2.1.2 相反这件事必须钉住</b>：本方法先取分配锁、后取预占锁，
   * 与 {@code ReservationAllocationService} 的「先分配后预占」一致。反过来（先预占后分配）
   * 会与它构成死锁环：一条事务持分配等预占、另一条持预占等分配。PostgreSQL 能检测并中止其中一条，
   * 但对调用方来说那是「随机 500」，排查成本极高。所以这里的预占**先无锁读一遍**做快速判定，
   * 真正的行锁在分配锁之后补上。
   */
  public ReservationReleaseOutcome release(
      UUID tenantId, UUID reservationId, UUID actorId, ReservationReleaseInput input) {
    ReservationReleaseInput in =
        input == null
            ? new ReservationReleaseInput(null, null, null, null, null, null, null)
            : input;
    ReservationReleaseTriggerSource trigger = triggerSource(in.triggerSource());
    OffsetDateTime occurredAt =
        in.occurredAt() == null ? now() : in.occurredAt().truncatedTo(ChronoUnit.MILLIS);
    String fingerprint = fingerprint(in);
    String key = trimToNull(in.idempotencyKey());

    // 幂等键命中：同键同参回放既有凭据；同键不同参 409（而不是悄悄改写历史）。
    if (key != null) {
      var prior =
          sql.fetchOne(
              "select id, request_fingerprint, reservation_id from reservation_release_record"
                  + " where tenant_id = " + TENANT + " and idempotency_key = ?",
              key);
      if (prior != null) {
        if (!fingerprint.equals(prior.get("request_fingerprint", String.class))) {
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "幂等键 "
                  + key
                  + " 已被另一组参数用过（释放凭据 "
                  + prior.get("id", UUID.class)
                  + "）：同一个键只能对应一次意图，请换一个键");
        }
        return replayOutcome(prior.get("reservation_id", UUID.class), prior.get("id", UUID.class));
      }
    }

    var peek = peekReservation(reservationId);
    var status = ReservationModels.ReservationStatus.valueOf(peek.get("status", String.class));

    // 终态重放：同一结束方式的第二次调用成功返回，不同方式 409。
    if (isTerminal(status)) {
      if (status == ReservationModels.ReservationStatus.RELEASED) {
        return replayOutcome(reservationId, latestRecordId(reservationId));
      }
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "预占 "
              + peek.get("reservation_no", String.class)
              + " 已终结于 "
              + status.label()
              + "，不能再释放：迟到命令不得复活已终结的预占");
    }

    List<Plan> plans = plans(reservationId, in);
    // 拿到分配锁之后再锁预占：顺序与 7.2.1.2 一致，且此时状态可能被并发消耗改掉，必须重判一次。
    var parent = lockOpenReservation(reservationId);
    boolean finalizeRequested = in.finalizeReservation() != null && in.finalizeReservation();
    var effect =
        settle(
            tenantId,
            parent,
            plans,
            ReservationReleaseMode.RELEASE,
            trigger,
            in.reason(),
            occurredAt,
            actorId,
            null,
            finalizeRequested,
            key,
            fingerprint);

    return new ReservationReleaseOutcome(
        reservationId,
        parent.get("reservation_no", String.class),
        ReservationReleaseMode.RELEASE.name(),
        ReservationReleaseMode.RELEASE.label(),
        trigger.name(),
        trigger.label(),
        effect.terminal(),
        status.name(),
        effect.statusAfter(),
        ReservationModels.ReservationStatus.valueOf(effect.statusAfter()).label(),
        effect.restored(),
        effect.releasedDemand(),
        effect.allocationCount(),
        effect.recordId(),
        effect.entryId(),
        false,
        effect.terminal()
            ? "已释放 "
                + effect.restored()
                + "，还回可用池；预占终结于 "
                + effect.statusAfter()
            : "已释放 "
                + effect.restored()
                + "，还回可用池；预占仍在 "
                + effect.statusAfter()
                + " 继续占额");
  }

  /**
   * 把入参变成「要动哪些分配、各动多少」。
   *
   * <p>三条分支对应蓝图的三个场景：
   *
   * <ul>
   *   <li>不指定任何分配也不给数量 → 全量释放（订单取消：整笔不做了）
   *   <li>只给数量 → 按圈定时间 FIFO 释放到该量为止（拆单减少）
   *   <li>给分配清单 → 逐条按各自的量（批次级调整）
   * </ul>
   *
   * <p>锁顺序按 {@code allocated_at, id} 固定：并发两次释放锁的是同一批行、同一个次序，
   * 死锁因此不可能发生，而死锁在这里的表现是「随机 500」而不是一条可解释的报错。
   */
  private List<Plan> plans(UUID reservationId, ReservationReleaseInput in) {
    var open =
        sql.fetch(
            """
            select id, status, allocated_quantity, consumed_quantity, released_quantity, remaining,
                   allocated_at
              from reservation_allocation
             where tenant_id = %s and reservation_id = ? and status not in ('RELEASED','EXPIRED')
             order by allocated_at, id
             for update
            """.formatted(TENANT),
            reservationId);

    if (in.allocations() != null && !in.allocations().isEmpty()) {
      Map<UUID, BigDecimal> asked = new LinkedHashMap<>();
      for (var one : in.allocations()) {
        if (one == null || one.allocationId() == null) {
          throw new IllegalArgumentException("释放清单里的 allocationId 不能为空");
        }
        if (asked.containsKey(one.allocationId())) {
          throw new IllegalArgumentException("分配 " + one.allocationId() + " 在释放清单里出现两次");
        }
        asked.put(
            one.allocationId(),
            one.quantity() == null ? null : one.quantity().setScale(6, RoundingMode.HALF_UP));
      }
      var plans = new ArrayList<Plan>();
      for (var row : open) {
        UUID id = row.get("id", UUID.class);
        if (!asked.containsKey(id)) continue;
        BigDecimal remaining = row.get("remaining", BigDecimal.class);
        BigDecimal quantity = asked.get(id);
        if (quantity == null) {
          quantity = remaining;
        } else if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
          throw new IllegalArgumentException("释放数量必须为正");
        }
        if (quantity.compareTo(remaining) > 0) {
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "分配 " + id + " 只能释放 " + remaining + "，请求释放 " + quantity + "：超出即凭空造出可用量");
        }
        plans.add(new Plan(id, row.get("status", String.class), quantity, remaining));
      }
      for (UUID askedId : asked.keySet()) {
        boolean found = plans.stream().anyMatch(p -> p.allocationId().equals(askedId));
        if (!found) {
          var one = sql.fetchOne(
              "select status from reservation_allocation where tenant_id = " + TENANT
                  + " and reservation_id=? and id=?",
              reservationId, askedId);
          if (one == null) throw notFoundAllocation(askedId);
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "分配 " + askedId + " 当前是 " + ReservationAllocationModels
                      .ReservationAllocationStatus.valueOf(one.get("status", String.class)).label()
                  + "，已不在可释放之列");
        }
      }
      if (plans.isEmpty()) throw nothingToRelease();
      return plans;
    }

    BigDecimal target =
        in.quantity() == null ? null : in.quantity().setScale(6, RoundingMode.HALF_UP);
    if (target != null && target.compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("释放数量必须为正");
    }
    if (target == null) {
      // 全量：把每条还在占额的分配都放掉。
      List<Plan> all = new ArrayList<>();
      for (var row : open) {
        BigDecimal remaining = row.get("remaining", BigDecimal.class);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) continue;
        all.add(
            new Plan(
                row.get("id", UUID.class),
                row.get("status", String.class),
                remaining,
                remaining));
      }
      return all;
    }

    // FIFO 释放到目标量：不足时**全额释放并把差额报出来**，而不是静默少放。
    List<Plan> fifo = new ArrayList<>();
    BigDecimal left = target;
    for (var row : open) {
      if (left.compareTo(BigDecimal.ZERO) <= 0) break;
      BigDecimal remaining = row.get("remaining", BigDecimal.class);
      if (remaining.compareTo(BigDecimal.ZERO) <= 0) continue;
      BigDecimal take = remaining.min(left);
      fifo.add(
          new Plan(row.get("id", UUID.class), row.get("status", String.class), take, remaining));
      left = left.subtract(take);
    }
    if (left.compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal available = target.subtract(left);
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "请求释放 " + target + "，但这些分配上只剩 " + available + " 可释放："
              + "超出部分从未圈到 bucket，没有库存可还");
    }
    return fifo;
  }

  // ================================================================ 批量过期清理

  /**
   * 批量过期清理。**显式端点 + 服务层幂等，不引入常驻定时器**。
   *
   * <p>调用方是调度器（cron / 任务平台）或人工。整批一个事务：候选判定、逐条终结、
   * 流水与凭据写入、运行凭据落库全在其中。V112 的外键是 DEFERRABLE 的，所以逐条释放时
   * 可以先引用运行 id、末尾再写运行凭据。
   *
   * <p>幂等有两层：
   *
   * <ul>
   *   <li>带幂等键 → 命中既有运行凭据直接回放（连扫描都不做）
   *   <li>不带键 → 第二次跑扫不到候选（已终态），天然无副作用；会多出一条「处理 0 条」的凭据，
   *       所以推荐调度器显式传键
   * </ul>
   */
  public ReservationReleaseRunRow expireRun(
      UUID tenantId, UUID actorId, ReservationReleaseRunRequest request) {
    ReservationReleaseRunRequest req =
        request == null
            ? new ReservationReleaseRunRequest(null, null, null, null, null)
            : request;
    boolean dryRun = req.dryRun() != null && req.dryRun();
    OffsetDateTime evaluatedAt =
        req.evaluatedAt() == null ? now() : req.evaluatedAt().truncatedTo(ChronoUnit.MILLIS);
    int limit = req.limitCount() == null ? 200 : req.limitCount();
    if (limit <= 0 || limit > MAX_RUN_LIMIT) {
      throw new IllegalArgumentException("单批上限必须在 1 到 " + MAX_RUN_LIMIT + " 之间");
    }
    String key = trimToNull(req.idempotencyKey());

    if (key != null) {
      var prior =
          sql.fetchOne(
              "select id from reservation_expiry_run where tenant_id = " + TENANT
                  + " and idempotency_key = ?",
              key);
      // 命中即回放，**不比较参数**：调度器重投时最常见的形态就是「按当前时刻重算」，而
      // 重投的语义是「确认那一次做完了没有」，凭据里记录的判定时刻才是权威。
      if (prior != null) return runRow(prior.get("id", UUID.class), true);
    }

    long candidateCount =
        sql.fetchOne(
                "select count(*) from inventory_reservation"
                    + " where tenant_id = " + TENANT
                    + " and status='HELD' and expires_at is not null and expires_at <= ?::timestamptz",
                evaluatedAt)
            .get(0, Long.class);

    UUID runId = UUID.randomUUID();
    long processed = 0, skipped = 0, releases = 0;
    BigDecimal restored = BigDecimal.ZERO, releasedDemand = BigDecimal.ZERO;

    if (!dryRun) {
      var ids =
          sql.fetch(
                  "select id from inventory_reservation"
                      + " where tenant_id = " + TENANT
                      + " and status='HELD' and expires_at is not null and expires_at <= ?::timestamptz"
                      + " order by expires_at, id limit ?",
                  evaluatedAt, limit)
              .map(r -> r.get("id", UUID.class));

      for (UUID id : ids) {
        // 逐条加锁：并发两次清理时后到的一条 for update skip locked 直接跳过，
        // 于是它计入 skipped 而不是重复释放。加锁顺序是「先分配、后预占」，与 7.2.1.2 一致，
        // 不会与它的写路径构成死锁环。
        var parent =
            sql.fetchOne(
                    "select id, reservation_no, status, requested_quantity, consumed_quantity,"
                        + " allocated_quantity, released_quantity, remaining, sku_id"
                        + " from inventory_reservation where tenant_id = " + TENANT
                        + " and id = ? and status='HELD' for update skip locked",
                    id);
        if (parent == null) {
          skipped++;
          continue;
        }
        // 蓝图红线：预占名下还有 COMMITTED 分配就整笔跳过。
        // 不只是跳过那条分配 —— 预占一旦进终态就不能再挂新分配，那条 COMMITTED 分配的货
        // 会永远占着可用量且再也放不掉。
        long committed =
            sql.fetchOne(
                    "select count(*) from reservation_allocation where tenant_id = " + TENANT
                        + " and reservation_id = ? and status='COMMITTED'",
                    id)
                .get(0, Long.class);
        if (committed > 0) {
          skipped++;
          continue;
        }

        List<Plan> plans = new ArrayList<>();
        var open =
            sql.fetch(
                """
                select id, status, allocated_quantity, consumed_quantity, released_quantity,
                       remaining, allocated_at
                  from reservation_allocation
                 where tenant_id = %s and reservation_id = ? and status not in ('RELEASED','EXPIRED')
                 order by allocated_at, id
                 for update
                """.formatted(TENANT),
                id);
        for (var row : open) {
          BigDecimal remaining = row.get("remaining", BigDecimal.class);
          if (remaining.compareTo(BigDecimal.ZERO) <= 0) continue;
          plans.add(
              new Plan(
                  row.get("id", UUID.class),
                  row.get("status", String.class),
                  remaining,
                  remaining));
        }

        var effect =
            settle(
                tenantId,
                parent,
                plans,
                ReservationReleaseMode.EXPIRE,
                ReservationReleaseTriggerSource.EXPIRY_SWEEP,
                req.reason() == null ? "超时回收" : req.reason(),
                evaluatedAt,
                actorId,
                runId,
                true,
                null,
                null);
        processed++;
        releases++;
        restored = restored.add(effect.restored());
        releasedDemand = releasedDemand.add(effect.releasedDemand());
      }
    }

    sql.execute(
        "insert into reservation_expiry_run(id,tenant_id,dry_run,evaluated_at,limit_count,"
            + "candidate_count,processed_count,skipped_count,release_count,restored_quantity,"
            + "released_demand_quantity,status,actor_id,reason,idempotency_key,completed_at)"
            + " values(?,?,?,?::timestamptz,?,?,?,?,?,?::numeric,?::numeric,'SUCCEEDED',?,?,?,?::timestamptz)",
        runId,
        tenantId,
        dryRun,
        evaluatedAt,
        limit,
        candidateCount,
        processed,
        skipped,
        releases,
        restored,
        releasedDemand,
        actorId,
        req.reason(),
        key,
        now());

    return runRow(runId, false);
  }

  // ================================================================ 核心：落一次释放

  /**
   * 一次释放的落库流程（手动与批量共用）。
   *
   * <p>写入顺序固定为「改分配 → 写分配级分录 → 改预占 → 写释放凭据」：
   *
   * <ol>
   *   <li>V107 的分录守卫要求分配**此刻**的状态恰等于分录的 to_status，所以分录只能写在分配落库之后；
   *   <li>V112 的释放凭据守卫要求预占**此刻**的状态等于凭据的 status_after，所以凭据只能写在预占落库之后；
   *   <li>预占的 {@code released_quantity} 与 {@code allocated_quantity} 各有各的维护者：
   *       后者由 V107 的回写触发器按「非终态分配之和」重算，本模块**不碰**；
   *       前者归本模块，按「已释放量」累加。
   * </ol>
   *
   * @param finalizeRequested 是否终结整笔预占。仍有未终结分配时强制为 false 并在服务层已拒
   */
  private Effect settle(
      UUID tenantId,
      org.jooq.Record parent,
      List<Plan> plans,
      ReservationReleaseMode mode,
      ReservationReleaseTriggerSource trigger,
      String reason,
      OffsetDateTime occurredAt,
      UUID actorId,
      UUID runId,
      boolean finalizeRequested,
      String key,
      String fingerprint) {
    UUID reservationId = parent.get("id", UUID.class);
    String statusBefore = parent.get("status", String.class);
    String terminalTarget = mode == ReservationReleaseMode.EXPIRE ? "EXPIRED" : "RELEASED";

    BigDecimal restored = BigDecimal.ZERO.setScale(6);
    UUID lastEntryId = null;
    int touched = 0;

    for (Plan plan : plans) {
      // 分配级的终点状态：**整条放完才进终态**。部分释放后分配留在原状态继续占额 ——
      // 已还回去的那部分不该再占可用量，没还的还占着，这个事实由 remaining 表达。
      boolean fully = plan.quantity().compareTo(plan.remaining()) == 0;
      String targetStatus = fully ? terminalTarget : plan.status();
      boolean allocationTerminal = fully;

      // 部分释放不落终态：已还的那部分不该再占额，但 settled_at 只能在终态上带
      // （V107 的更新守卫会拒「非终态却有 settled_at」），所以两个分支的 SET 子句不同。
      String setClause =
          "update reservation_allocation set status=?,"
              + " released_quantity=released_quantity+?::numeric"
              + (allocationTerminal ? ", settled_at=?::timestamptz" : "")
              + " where id=? and tenant_id=? and status=?"
              + (allocationTerminal ? " and released_quantity+?::numeric >= allocated_quantity" : "");
      int updated =
          allocationTerminal
              ? sql.execute(
                  setClause,
                  targetStatus,
                  plan.quantity(),
                  occurredAt,
                  plan.allocationId(),
                  tenantId,
                  plan.status(),
                  plan.quantity())
              : sql.execute(
                  setClause,
                  targetStatus,
                  plan.quantity(),
                  plan.allocationId(),
                  tenantId,
                  plan.status());
      if (updated != 1) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "分配 " + plan.allocationId() + " 在释放过程中被其他事务改动（影响行数 " + updated
                + "）：并发释放由行锁串行化，出现这一行说明有裸 SQL 绕过了服务层");
      }

      // **只有真的进了终态才写分录**。部分释放时分配仍是 HELD —— 它没有发生状态迁移，
      // 而 V107 的分录守卫会把 HELD → HELD 判成非法迁移（这正是它该做的事：
      // 「时间线」是状态迁移的凭据，把没发生的迁移写进去，时间线就成了编的）。
      // 部分释放的恢复量记在 reservation_release_record 上：它同样是 append-only、
      // 同样带 restored_quantity 与发生时刻，量没有落空，只是不冒充状态迁移。
      if (allocationTerminal) {
        lastEntryId =
            appendEntry(
                tenantId,
                reservationId,
                plan.allocationId(),
                mode == ReservationReleaseMode.EXPIRE ? "EXPIRED" : "RELEASED",
                plan.status(),
                targetStatus,
                plan.quantity(),
                occurredAt,
                actorId,
                reason);
      }
      restored = restored.add(plan.quantity());
      touched++;
    }

    // 是否终结整笔：还有未终结分配就**不能**说这笔结束了。
    long stillOpen =
        sql.fetchOne(
                "select count(*) from reservation_allocation where tenant_id = " + TENANT
                    + " and reservation_id = ? and status not in ('RELEASED','EXPIRED')",
                reservationId)
            .get(0, Long.class);
    boolean terminal = stillOpen == 0;
    if (finalizeRequested && !terminal) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "仍有 " + stillOpen + " 条分配在占额，不能终结整笔预占："
              + "预占进终态后不能再挂新分配，剩下的货会再也放不掉");
    }

    String statusAfter = terminal ? terminalTarget : statusBefore;
    BigDecimal releasedDemand;

    if (terminal) {
      // 终结口径：预占侧放弃的是「需求量 − 已消费量」。
      // 它通常 >= restoredQuantity：没圈到 bucket 的部分同样不再需要，但它从没占过可用量。
      releasedDemand =
          parent
              .get("requested_quantity", BigDecimal.class)
              .subtract(parent.get("consumed_quantity", BigDecimal.class))
              .setScale(6, RoundingMode.HALF_UP);
      if (releasedDemand.compareTo(BigDecimal.ZERO) <= 0) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "预占 " + reservationId + " 没有剩余量可释放");
      }
      int parentUpdated =
          sql.execute(
              "update inventory_reservation set status=?, released_quantity=?::numeric,"
                  + " settled_at=?::timestamptz"
                  + " where id=? and tenant_id=? and status=?"
                  + " and status not in ('CONSUMED','RELEASED','EXPIRED')",
              statusAfter,
              releasedDemand,
              occurredAt,
              reservationId,
              tenantId,
              statusBefore);
      if (parentUpdated != 1) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "预占 " + reservationId + " 在释放过程中被其他事务改动（影响行数 " + parentUpdated
                + "）：并发释放由行锁串行化");
      }
      // 预占级分录只在**预占本身进终态**时写，理由同上（没迁移就不写分录）。
      // 它记的是**需求侧放弃的量**，分配级分录记的是**库存侧还回的量**；
      // 两者相等只在这笔需求被全额圈到 bucket 时成立，所以两个口径在模型里分开报。
      lastEntryId =
          appendEntry(
              tenantId,
              reservationId,
              null,
              mode == ReservationReleaseMode.EXPIRE ? "EXPIRED" : "RELEASED",
              statusBefore,
              statusAfter,
              releasedDemand,
              occurredAt,
              actorId,
              reason);
    } else {
      releasedDemand = restored;
      int parentUpdated =
          sql.execute(
              "update inventory_reservation set released_quantity=released_quantity+?::numeric"
                  + " where id=? and tenant_id=? and status=?",
              releasedDemand,
              reservationId,
              tenantId,
              statusBefore);
      if (parentUpdated != 1) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "预占 " + reservationId + " 在部分释放过程中被其他事务改动（影响行数 " + parentUpdated + "）");
      }
    }

    UUID recordId =
        appendRecord(
            tenantId,
            reservationId,
            mode,
            trigger,
            reason,
            terminal,
            statusBefore,
            statusAfter,
            touched,
            restored,
            releasedDemand,
            occurredAt,
            actorId,
            runId,
            key,
            fingerprint);

    return new Effect(terminal, statusAfter, restored, releasedDemand, touched, recordId, lastEntryId);
  }

  private record Plan(UUID allocationId, String status, BigDecimal quantity, BigDecimal remaining) {}

  private record Effect(
      boolean terminal,
      String statusAfter,
      BigDecimal restored,
      BigDecimal releasedDemand,
      int allocationCount,
      UUID recordId,
      UUID entryId) {}

  // ================================================================ 补写过期时刻

  /**
   * 补写 / 调整 / 清空过期时刻。
   *
   * <p>7.2.1.1 的登记入参注释逐字写着「不传则该预占不参与超时筛选（可稍后由 7.2.1.3 补）」——
   * 补的就是这个时刻。所以本端点**只对 HELD 开放**：COMMITTED 的预占留着过期时刻没有意义
   * （它永不参与自动回收），却会让人以为它还在等着被清掉。
   */
  public ReservationReleaseExpiryOutcome setExpiry(
      UUID tenantId, UUID reservationId, ReservationReleaseExpiryInput input) {
    var parent = peekReservation(reservationId);
    var status = ReservationModels.ReservationStatus.valueOf(parent.get("status", String.class));
    if (isTerminal(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "预占已终结于 " + status.label() + "，过期时刻是历史事实，不再改写");
    }
    if (status != ReservationModels.ReservationStatus.HELD) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "预占当前是 " + status.label() + "，只有预占中（HELD）的预占才有过期时刻："
              + "已承诺的预占永不自动过期，留着过期时刻只会误导");
    }
    if (input == null || (input.expiresAt() == null && !Boolean.TRUE.equals(input.clear()))) {
      throw new IllegalArgumentException("请提供新的 expiresAt，或传 clear=true 清空过期时刻");
    }

    OffsetDateTime expiresAt =
        Boolean.TRUE.equals(input.clear())
            ? null
            : input.expiresAt().truncatedTo(ChronoUnit.MILLIS);
    int updated =
        sql.execute(
            "update inventory_reservation set expires_at=?::timestamptz"
                + " where id=? and tenant_id=? and status='HELD'",
            expiresAt, reservationId, tenantId);
    if (updated != 1) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "预占 " + reservationId + " 在写入过期时刻时已被改动（影响行数 " + updated + "）");
    }

    // 读回库里的真值返回，而不是回显入参：断言「写回的值」比断言「没抛异常」强得多，
    // 而 timestamptz 的时区归一化正是最容易悄悄错 8 小时的地方。
    var back =
        sql.fetchOne(
            "select expires_at from inventory_reservation where tenant_id = " + TENANT + " and id = ?",
            reservationId);
    OffsetDateTime stored = back.get("expires_at", OffsetDateTime.class);
    Long overdueSeconds =
        stored == null || !stored.isBefore(now()) ? null : Duration.between(stored, now()).getSeconds();

    return new ReservationReleaseExpiryOutcome(
        reservationId,
        stored,
        true,
        overdueSeconds,
        stored == null
            ? "已清空过期时刻：这笔预占不再参与批量过期清理"
            : overdueSeconds == null
                ? "过期时刻已写入，尚未到期"
                : "过期时刻已写入，已超时 " + overdueSeconds + " 秒，可立即批量回收");
  }

  // ================================================================ 读：运行凭据与释放凭据

  /** 批量过期清理的运行历史。 */
  public ReservationReleaseRunPage runs(UUID tenantId, int page, int size) {
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    int offset = Math.max(0, page) * limit;
    long total =
        sql.fetchOne("select count(*) from reservation_expiry_run where tenant_id = " + TENANT)
            .get(0, Long.class);
    var rows =
        sql.fetch(
                "select id from reservation_expiry_run where tenant_id = " + TENANT
                    + " order by created_at desc, id limit ? offset ?",
                limit, offset)
            .map(r -> runRow(r.get("id", UUID.class), false));
    return new ReservationReleaseRunPage(
        rows,
        total,
        Math.max(0, page),
        limit,
        "运行凭据 append-only：一批一次，dry_run 的批次同样留痕（processed_count 恒为 0）。");
  }

  /** 单次运行详情 = 运行凭据 + 它产生的释放凭据。 */
  public ReservationReleaseRunDetail run(UUID tenantId, UUID runId) {
    var run = runRow(runId, false);
    var records =
        sql.fetch(
                "select id from reservation_release_record where tenant_id = " + TENANT
                    + " and expiry_run_id = ? order by occurred_at, id",
                runId)
            .map(r -> recordRow(r.get("id", UUID.class)));
    return new ReservationReleaseRunDetail(
        run, records, records.size(), "按发生时刻正序；释放凭据与分录都 append-only。");
  }

  /**
   * 释放凭据列表。
   *
   * <p>两个过滤条件给二选一地用：按预占查（明细页的「这个预占被释放过几次」），
   * 或按运行查（运行详情）。都不传则给租户全量。
   */
  public ReservationReleaseRecordPage records(UUID tenantId, UUID reservationId, int page, int size) {
    int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    int offset = Math.max(0, page) * limit;
    var where = new StringBuilder(" where tenant_id = " + TENANT);
    var binds = new ArrayList<Object>();
    if (reservationId != null) {
      where.append(" and reservation_id = ?");
      binds.add(reservationId);
    }
    long total =
        sql.fetchOne("select count(*) from reservation_release_record" + where, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
                "select id from reservation_release_record" + where
                    + " order by occurred_at desc, id limit ? offset ?",
                bindsToArray(binds, limit, offset))
            .map(r -> recordRow(r.get("id", UUID.class)));
    return new ReservationReleaseRecordPage(rows, total, Math.max(0, page), limit, NOTE);
  }

  private ReservationReleaseRunRow runRow(UUID runId, boolean replayed) {
    var r =
        sql.fetchOne(
            "select id, dry_run, evaluated_at, limit_count, candidate_count, processed_count,"
                + " skipped_count, release_count, restored_quantity, released_demand_quantity,"
                + " status, actor_id, reason, idempotency_key, created_at, completed_at"
                + " from reservation_expiry_run where tenant_id = " + TENANT + " and id = ?",
            runId);
    if (r == null) throw notFoundRun(runId);
    return new ReservationReleaseRunRow(
        r.get("id", UUID.class),
        r.get("dry_run", Boolean.class),
        r.get("evaluated_at", OffsetDateTime.class),
        r.get("limit_count", Integer.class),
        r.get("candidate_count", Long.class),
        r.get("processed_count", Long.class),
        r.get("skipped_count", Long.class),
        r.get("release_count", Long.class),
        r.get("restored_quantity", BigDecimal.class),
        r.get("released_demand_quantity", BigDecimal.class),
        r.get("status", String.class),
        r.get("actor_id", UUID.class),
        r.get("reason", String.class),
        r.get("idempotency_key", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class),
        replayed);
  }

  private ReservationReleaseRecordRow recordRow(UUID recordId) {
    var r =
        sql.fetchOne(
            "select rr.id, rr.reservation_id, r.reservation_no, rr.mode, rr.trigger_source,"
                + " rr.terminal, rr.status_before, rr.status_after, rr.allocation_count,"
                + " rr.restored_quantity, rr.released_demand_quantity, rr.occurred_at, rr.actor_id,"
                + " rr.reason, rr.expiry_run_id, rr.idempotency_key, rr.created_at"
                + " from reservation_release_record rr"
                + " left join inventory_reservation r"
                + " on r.tenant_id=rr.tenant_id and r.id=rr.reservation_id"
                + " where rr.tenant_id = " + TENANT + " and rr.id = ?",
            recordId);
    if (r == null) throw notFoundRecord(recordId);
    var mode = ReservationReleaseMode.valueOf(r.get("mode", String.class));
    var trigger = ReservationReleaseTriggerSource.valueOf(r.get("trigger_source", String.class));
    String after = r.get("status_after", String.class);
    return new ReservationReleaseRecordRow(
        r.get("id", UUID.class),
        r.get("reservation_id", UUID.class),
        r.get("reservation_no", String.class),
        mode.name(),
        mode.label(),
        trigger.name(),
        trigger.label(),
        r.get("terminal", Boolean.class),
        r.get("status_before", String.class),
        after,
        ReservationModels.ReservationStatus.valueOf(after).label(),
        r.get("allocation_count", Integer.class),
        r.get("restored_quantity", BigDecimal.class),
        r.get("released_demand_quantity", BigDecimal.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("actor_id", UUID.class),
        r.get("reason", String.class),
        r.get("expiry_run_id", UUID.class),
        r.get("idempotency_key", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  // ================================================================ 下拉项与能力声明

  public ReservationReleaseOptions options() {
    List<ReservationReleaseOption> modes = new ArrayList<>();
    for (ReservationReleaseMode m : ReservationReleaseMode.values()) {
      modes.add(
          new ReservationReleaseOption(
              m.name(), m.label(), false, "EXPIRE".equals(m.name()) ? "只回收仍在 HELD 的预占" : null));
    }
    List<ReservationReleaseOption> triggers = new ArrayList<>();
    for (ReservationReleaseTriggerSource t : ReservationReleaseTriggerSource.values()) {
      triggers.add(
          new ReservationReleaseOption(
              t.name(),
              t.label(),
              t.sweepOnly(),
              t.sweepOnly() ? "仅批量过期清理可用，手动入口不接受" : null));
    }
    return new ReservationReleaseOptions(
        modes,
        triggers,
        List.of(
            new ReservationReleaseOption("EXPIRES_AT", "按过期时刻", false, null),
            new ReservationReleaseOption("OVERDUE", "按超时时长", false, null),
            new ReservationReleaseOption("RESTORABLE", "按可还回量", false, null),
            new ReservationReleaseOption("RESERVATION_NO", "按预占单号", false, null)),
        RULE_NOTE,
        TIMEZONE_NOTE,
        OWNER_NOTE);
  }

  /**
   * 链路各环节的接入情况。复用 7.2.1.1 的 {@code ReservationCapability}
   * （同一条预占链路的能力声明只能有一种表示）。
   *
   * <p>「可用量重算」这一维度**如实报 {@code available=false}** 并写明归属 7.1.1.3：
   * 本模块把预占退出占额集合并记账，可用量的**口径**由 7.1.1.3 裁决。
   * 把它报成 true 会等于宣称本模块算出了可用量，而那正是本卷禁止的「两个地方各算一次」。
   */
  public List<ReservationModels.ReservationCapability> capabilities() {
    return List.of(
        new ReservationModels.ReservationCapability(
            "RELEASE_MANUAL",
            "手动释放",
            true,
            "7.2.1.3",
            "已接入：POST /warehouse/inventory/reservations/{id}/release，支持整笔 / 按量 / 按分配三种粒度；"
                + "已终结的重投按幂等回放（replayed=true）"),
        new ReservationModels.ReservationCapability(
            "EXPIRY_SWEEP",
            "批量过期清理",
            true,
            "7.2.1.3",
            "已接入：POST /warehouse/inventory/reservations/expire-overdue（显式端点 + 服务层幂等，"
                + "无常驻定时器）；一批一事务，dryRun 只写运行凭据"),
        new ReservationModels.ReservationCapability(
            "EXPIRY_SCHEDULE",
            "过期时刻补写",
            true,
            "7.2.1.3",
            "已接入：POST /warehouse/inventory/reservations/{id}/expiry，只对 HELD 开放；"
                + "补的是 7.2.1.1 登记时没传的 expires_at"),
        new ReservationModels.ReservationCapability(
            "RELEASE_AUDIT",
            "释放凭据与运行凭据",
            true,
            "7.2.1.3",
            "已接入：GET /release-records 与 /expiry-runs（+ /expiry-runs/{id} 明细），"
                + "两表 append-only，DB 层拒 UPDATE / DELETE"),
        new ReservationModels.ReservationCapability(
            "AVAILABLE_RESTORE",
            "可用量恢复口径",
            false,
            "7.1.1.3",
            "本模块只把分配退出占额集合并记恢复量（restoredQuantity）；"
                + "available = on_hand − reserved 的口径与重算由 7.1.1.3 裁决，本模块不重复实现"));
  }

  // ================================================================ 内部：写入与工具

  /** 追加一条迁移分录。append-only（V107 的守卫顺带校验 to_status 等于当前状态）。 */
  private UUID appendEntry(
      UUID tenantId,
      UUID reservationId,
      UUID allocationId,
      String transition,
      String fromStatus,
      String toStatus,
      BigDecimal quantity,
      OffsetDateTime occurredAt,
      UUID actorId,
      String reason) {
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into reservation_entry(id,tenant_id,reservation_id,allocation_id,transition,"
            + "from_status,to_status,quantity,occurred_at,actor_id,reason)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,?,?)",
        id,
        tenantId,
        reservationId,
        allocationId,
        transition,
        fromStatus,
        toStatus,
        quantity,
        occurredAt,
        actorId,
        reason);
    return id;
  }

  /** 追加一条释放凭据。append-only，守卫校验它与预占当前状态一致（V112）。 */
  private UUID appendRecord(
      UUID tenantId,
      UUID reservationId,
      ReservationReleaseMode mode,
      ReservationReleaseTriggerSource trigger,
      String reason,
      boolean terminal,
      String statusBefore,
      String statusAfter,
      int allocationCount,
      BigDecimal restored,
      BigDecimal releasedDemand,
      OffsetDateTime occurredAt,
      UUID actorId,
      UUID runId,
      String key,
      String fingerprint) {
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into reservation_release_record(id,tenant_id,reservation_id,mode,trigger_source,"
            + "reason,terminal,status_before,status_after,allocation_count,restored_quantity,"
            + "released_demand_quantity,occurred_at,actor_id,expiry_run_id,idempotency_key,"
            + "request_fingerprint)"
            + " values(?,?,?,?,?,?,?,?,?,?,?::numeric,?::numeric,?::timestamptz,?,?,?,?)",
        id,
        tenantId,
        reservationId,
        mode.name(),
        trigger.name(),
        reason,
        terminal,
        statusBefore,
        statusAfter,
        allocationCount,
        restored,
        releasedDemand,
        occurredAt,
        actorId,
        runId,
        key,
        fingerprint);
    return id;
  }

  /**
   * 无锁读预占：先判存在性与终态，快速失败路径不必拿行锁。
   *
   * <p>刻意与 {@link #lockOpenReservation} 分开：无锁读用来给出「已终结 / 可重放」这类
   * 可解释的 409 与重放，而**行锁必须在分配锁之后**拿（见 {@link #release} 的加锁顺序说明）。
   */
  private org.jooq.Record peekReservation(UUID reservationId) {
    var one =
        sql.fetchOne(
            "select id, reservation_no, status, requested_quantity, consumed_quantity"
                + " from inventory_reservation where tenant_id = " + TENANT + " and id = ?",
            reservationId);
    if (one == null) throw notFound(reservationId);
    return one;
  }

  /** 锁定预占并确认它没进终态。并发消耗可能在拿到分配锁的这段时间里把预占推到 CONSUMED。 */
  private org.jooq.Record lockOpenReservation(UUID reservationId) {
    var one =
        sql.fetchOne(
            "select id, reservation_no, status, requested_quantity, allocated_quantity,"
                + " consumed_quantity, released_quantity, remaining, sku_id"
                + " from inventory_reservation where tenant_id = " + TENANT + " and id=? for update",
            reservationId);
    if (one == null) throw notFound(reservationId);
    var status = ReservationModels.ReservationStatus.valueOf(one.get("status", String.class));
    if (isTerminal(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "预占 " + one.get("reservation_no", String.class) + " 已终结于 " + status.label()
              + "，不能再释放（它在本服务处理期间被并发出库消耗了）");
    }
    return one;
  }

  /** 重放时把既有凭据读回成同一形状的响应。 */
  private ReservationReleaseOutcome replayOutcome(UUID reservationId, UUID recordId) {
    var row = recordRow(recordId);
    return new ReservationReleaseOutcome(
        reservationId,
        row.reservationNo(),
        row.mode(),
        row.modeLabel(),
        row.triggerSource(),
        row.triggerSourceLabel(),
        row.terminal(),
        row.statusBefore(),
        row.statusAfter(),
        row.statusAfterLabel(),
        row.restoredQuantity(),
        row.releasedDemandQuantity(),
        row.allocationCount(),
        row.id(),
        null,
        true,
        "这笔预占已经释放过（凭据 " + row.id() + "）：重投不重复释放，货不会被还回两次");
  }

  private UUID latestRecordId(UUID reservationId) {
    var one =
        sql.fetchOne(
            "select id from reservation_release_record where tenant_id = " + TENANT
                + " and reservation_id = ? order by created_at desc, id desc limit 1",
            reservationId);
    if (one == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "预占已是已释放终态，但找不到对应的释放凭据：数据不自洽，请人工核查");
    }
    return one.get("id", UUID.class);
  }

  // ------------------------------------------------------------ 词表与指纹

  private static ReservationReleaseTriggerSource triggerSource(String raw) {
    if (raw == null || raw.isBlank()) return ReservationReleaseTriggerSource.MANUAL;
    ReservationReleaseTriggerSource value;
    try {
      value = ReservationReleaseTriggerSource.valueOf(raw.toUpperCase(Locale.ROOT).trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("不支持的释放来源：" + raw);
    }
    // 超时回收只能由批量清理产生：人工把它写进凭据，两个运营口径就再也分不开了。
    if (value.sweepOnly()) {
      throw new IllegalArgumentException(
          "EXPIRY_SWEEP 只能由批量过期清理产生，手动释放请用 MANUAL 或对应业务事件来源");
    }
    return value;
  }

  /**
   * 请求指纹：同一个幂等键必须对应同一组参数，否则重放与「改写历史意图」无法区分。
   *
   * <p>只取**语义参数**（释放粒度、数量、来源、是否终结），不取 occurredAt ——
   * 事件重投时上游常带同一个时刻，但也不保证；把它算进去会让一次无害的重投被误判成冲突。
   */
  private static String fingerprint(ReservationReleaseInput in) {
    StringBuilder sb = new StringBuilder();
    if (in.allocations() != null) {
      for (var one : in.allocations()) {
        sb.append(one.allocationId()).append(':').append(one.quantity()).append(';');
      }
    }
    sb.append("|q=").append(in.quantity())
        .append("|src=").append(in.triggerSource())
        .append("|fin=").append(in.finalizeReservation())
        .append("|reason=").append(in.reason());
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("计算请求指纹失败", e);
    }
  }

  private static String trimToNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static Object[] bindsToArray(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) all[i] = binds.get(i);
    for (int i = 0; i < tail.length; i++) all[binds.size() + i] = tail[i];
    return all;
  }

  private static boolean isTerminal(ReservationModels.ReservationStatus status) {
    return status == ReservationModels.ReservationStatus.CONSUMED
        || status == ReservationModels.ReservationStatus.RELEASED
        || status == ReservationModels.ReservationStatus.EXPIRED;
  }

  private static ResponseStatusException nothingToRelease() {
    return new ResponseStatusException(
        HttpStatus.CONFLICT, "这几条分配都已终结，没有可释放的量（货早就还回可用池了）");
  }

  private static ResponseStatusException notFound(UUID reservationId) {
    return new ResponseStatusException(
        HttpStatus.NOT_FOUND, "预占 " + reservationId + " 不存在或无权查看");
  }

  private static ResponseStatusException notFoundAllocation(UUID allocationId) {
    return new ResponseStatusException(
        HttpStatus.NOT_FOUND, "分配 " + allocationId + " 不存在或无权操作");
  }

  private static ResponseStatusException notFoundRun(UUID runId) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "过期运行 " + runId + " 不存在或无权查看");
  }

  private static ResponseStatusException notFoundRecord(UUID recordId) {
    return new ResponseStatusException(
        HttpStatus.NOT_FOUND, "释放凭据 " + recordId + " 不存在或无权查看");
  }

  private OffsetDateTime now() {
    // 提前 1 毫秒截断：PG 的 timestamptz 是微秒精度，Java 是纳秒，
    // 不截断的话写回读出的值与传入值不等，测试「断言写回的值」会差在末位。
    return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
  }
}