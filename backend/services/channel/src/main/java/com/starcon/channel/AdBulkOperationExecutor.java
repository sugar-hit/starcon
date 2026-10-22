package com.starcon.channel;

import static com.starcon.channel.AdBulkOperationModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 14.1.1.3 批量操作的**异步执行器**：把「已受理」的批量调整真正跑完，逐条落结果。
 *
 * <p>🔴 <b>为什么是异步</b>：蓝图写的是「批量调整任务（异步）+ 每条执行结果」。
 * 一次跨几百条活动的调整要读几百条当前值、逐条写、逐条留审计 ——
 * 同步做会把这个请求挂到超时，而运营要的是「提交完先去做别的，结果回头查」。
 *
 * <p>🔴 <b>这是本地任务，不是平台队列</b>：平台广告 API 与回写队列**未交付**。
 * 任务跑完 = **本地已经改了**，平台侧还没改 ——
 * 每一处改动都把活动标记成 {@code pending_platform_writeback}，界面上必须显式写出来。
 *
 * <p><b>每批单独开租户事务</b>：租户上下文与数据范围都是**事务级 GUC**
 * （{@code app.current_tenant} / {@code app.actor} / {@code app.grants}），
 * 提交请求那个事务早就结束了。工作线程必须按 {@link AccessService.Access} 重开事务，
 * 而且必须是**带 Access 的重开** —— 活动与广告组带 {@code data_scope} RESTRICTIVE 策略，
 * 只绑 tenant_id 而不绑 grants 的话，范围会被逐行拒绝。
 *
 * <p><b>领取互斥由 DB 裁决</b>（{@code worker_claimed_at}）：两个执行者各写各的局部计数，
 * 后写的会把前一个的进度抹掉，任务显示「执行了 0 条」而其实有 120 条。
 */
@Service
public class AdBulkOperationExecutor {
  private static final Logger log = LoggerFactory.getLogger(AdBulkOperationExecutor.class);

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 一批（一个事务）处理多少条。太大 ⇒ 事务长、连接被占住；太小 ⇒ 往返多。 */
  static final int CHUNK_ITEMS = 50;

  private final DSLContext sql;
  private final AccessService access;
  private final ExecutorService pool;

  public AdBulkOperationExecutor(DSLContext sql, AccessService access) {
    this.sql = sql;
    this.access = access;
    var seq = new AtomicInteger();
    // 并行度 2：批量调整是写操作，两个并发足够；再高只会让同一批活动的写入互相踩。
    this.pool =
        Executors.newFixedThreadPool(
            2,
            new ThreadFactory() {
              @Override
              public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "channel-ad-bulk-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
              }
            });
  }

  /**
   * 受理一次批量调整：推进到 RUNNING 并丢进线程池。
   *
   * <p>🔴 调用方**必须**在创建任务的事务**提交之后**再调它 ——
   * 否则工作线程在另一个连接上看不到还没提交的任务行，
   * 会把「任务不存在」当成「没有要执行的东西」而静默退出。
   */
  public void accept(AccessService.Access actor, String permission, UUID taskId) {
    var claimed =
        access.run(
            actor,
            permission,
            () ->
                sql.fetchOne(
                    "update ad_campaign_bulk_task set status = 'RUNNING', worker_claimed_at = now(),"
                        + " started_at = coalesce(started_at, now())"
                        + " where tenant_id = ? and id = ? and status = 'PENDING'"
                        + " and worker_claimed_at is null returning id",
                    actor.tenantId(),
                    taskId));
    if (claimed == null) {
      // 已被另一个执行者领取，或任务本来就是 REJECTED（整批被护栏拒绝，没有东西要执行）。
      // 两种都不是错误，静默退出即可。
      log.debug("批量任务 {} 未被本执行器领取，跳过", taskId);
      return;
    }
    pool.submit(() -> run(actor, permission, taskId));
  }

  private void run(AccessService.Access actor, String permission, UUID taskId) {
    try {
      // 读任务行本身也必须在租户事务里：任务表开了 FORCE RLS，
      // 而工作线程此刻不在任何租户上下文里，裸查会取不到行（看起来像「任务不存在」）。
      var exec = access.run(actor, permission, () -> loadExecution(actor.tenantId(), taskId));
      if (exec == null) {
        log.warn("批量任务 {} 读不到（可能已被删除）", taskId);
        return;
      }

      // 🔴 先把当前值全读出来、算好计划、**再**裁决护栏 ——
      //   护栏是整批的裁决：越限 ⇒ 一条都不许执行。
      //   边读边执行的话，批到一半才发现越限，前半批已经改了。
      var planned =
          access.run(actor, permission, () -> plan(actor.tenantId(), taskId, exec));

      String rejection =
          planned.rejectionReason() != null ? planned.rejectionReason() : guardrail(planned);
      if (rejection != null) {
        access.run(
            actor,
            permission,
            () -> {
              rejectAll(actor.tenantId(), taskId, planned, rejection);
              return null;
            });
        return;
      }

      for (int start = 0; start < planned.items().size(); start += CHUNK_ITEMS) {
        int from = start;
        int to = Math.min(start + CHUNK_ITEMS, planned.items().size());
        var chunk = planned.items().subList(from, to);
        access.run(
            actor,
            permission,
            () -> {
              for (var item : chunk) {
                execute(actor, exec, item);
              }
              return null;
            });
      }

      access.run(
          actor,
          permission,
          () -> {
            finalize(actor.tenantId(), taskId);
            return null;
          });
    } catch (Exception e) {
      log.warn("批量任务 {} 执行失败：{}", taskId, e.toString());
      fail(actor, permission, taskId, e);
    }
  }

  // ==================================================================
  // 读取与计划
  // ==================================================================

  /** 一个待执行项：任务项 + 它的当前值 + 算好的计划。 */
  record PlannedItem(UUID itemId, AdBulkPlanItem plan) {}

  /** 一批计划 + 护栏裁决结果。 */
  record Planned(List<PlannedItem> items, String rejectionReason) {}

  private AdBulkTaskExecution loadExecution(UUID tenant, UUID taskId) {
    Record r =
        sql.fetchOne(
            "select id,operation,target_type,param_budget,param_delta,param_bid,param_bid_delta,"
                + " param_end_at,param_clear_end_at,reason,requested_by,"
                + " guardrail_max_campaigns,guardrail_max_groups,"
                + " guardrail_max_single_budget_delta,guardrail_max_total_budget_delta,"
                + " guardrail_max_campaign_budget,guardrail_max_bid,"
                + " guardrail_max_single_bid_delta,guardrail_max_total_bid_delta"
                + " from ad_campaign_bulk_task where tenant_id = ? and id = ?",
            tenant,
            taskId);
    if (r == null) return null;
    return new AdBulkTaskExecution(
        r.get("id", UUID.class),
        r.get("operation", String.class),
        r.get("target_type", String.class),
        r.get("param_budget", BigDecimal.class),
        r.get("param_delta", BigDecimal.class),
        r.get("param_bid", BigDecimal.class),
        r.get("param_bid_delta", BigDecimal.class),
        r.get("param_end_at", OffsetDateTime.class),
        Boolean.TRUE.equals(r.get("param_clear_end_at", Boolean.class)),
        r.get("reason", String.class),
        r.get("requested_by", UUID.class),
        new AdBulkTaskGuardrailSnapshot(
            r.get("guardrail_max_single_budget_delta", BigDecimal.class),
            r.get("guardrail_max_total_budget_delta", BigDecimal.class),
            r.get("guardrail_max_campaign_budget", BigDecimal.class),
            r.get("guardrail_max_bid", BigDecimal.class),
            r.get("guardrail_max_single_bid_delta", BigDecimal.class),
            r.get("guardrail_max_total_bid_delta", BigDecimal.class),
            r.get("guardrail_max_campaigns", Integer.class),
            r.get("guardrail_max_groups", Integer.class)));
  }

  /**
   * 读出所有待执行项的**当前值**并算好计划。
   *
   * <p>🔴 当前值是**执行时刻**的，不是预览时刻的：预览到执行之间别人可能又改了预算，
   * 拿旧值去执行就会把活动改到一个谁都没打算要的状态。
   */
  private Planned plan(UUID tenant, UUID taskId, AdBulkTaskExecution exec) {
    AdBulkValidatedRequest params =
        new AdBulkValidatedRequest(
            exec.operation(),
            exec.targetType(),
            exec.budget(),
            exec.delta(),
            exec.bid(),
            exec.bidDelta(),
            exec.endAt(),
            exec.clearEndAt(),
            exec.reason());
    OffsetDateTime now = OffsetDateTime.now();

    List<PlannedItem> items = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select i.id item_id, i.campaign_id, i.group_id, i.target_type,"
                + " c.campaign_name, c.shop_code, c.status, c.daily_budget, c.end_at,"
                + " g.group_name, g.default_bid, g.bid_currency"
                + " from ad_campaign_bulk_task_item i"
                + " left join ad_campaign c on c.tenant_id = i.tenant_id and c.id = i.campaign_id"
                + " left join ad_campaign_group g on g.tenant_id = i.tenant_id and g.id = i.group_id"
                + " where i.tenant_id = "
                + TENANT
                + " and i.task_id = ? and i.result = 'PENDING' order by i.id",
            taskId)) {

      UUID itemId = r.get("item_id", UUID.class);
      String targetType = r.get("target_type", String.class);
      UUID campaignId = r.get("campaign_id", UUID.class);
      UUID groupId = r.get("group_id", UUID.class);
      String campaignName = r.get("campaign_name", String.class);
      String shopCode = r.get("shop_code", String.class);
      String status = r.get("status", String.class);
      BigDecimal dailyBudget = r.get("daily_budget", BigDecimal.class);
      OffsetDateTime endAt = r.get("end_at", OffsetDateTime.class);
      String groupName = r.get("group_name", String.class);
      BigDecimal defaultBid = r.get("default_bid", BigDecimal.class);
      String bidCurrency = r.get("bid_currency", String.class);

      boolean campaignMissing = campaignName == null;
      boolean groupMissing = "AD_GROUP".equals(targetType) && groupName == null;

      AdBulkTarget target =
          new AdBulkTarget(
              targetType,
              campaignId,
              campaignName == null ? "" : campaignName,
              shopCode == null ? "" : shopCode,
              status == null ? "" : status,
              dailyBudget,
              endAt,
              groupId,
              groupName == null ? "" : groupName,
              defaultBid,
              bidCurrency == null ? "" : bidCurrency);

      AdBulkPlanItem plan;
      if (campaignMissing) {
        plan =
            new AdBulkPlanItem(
                targetType, campaignId, "", groupId, groupName, "", "", null, null, null,
                null, null, null, null, null,
                AdBulkOperationRules.VERDICT_REJECT,
                "活动不存在或已被删除");
      } else if (groupMissing) {
        plan =
            new AdBulkPlanItem(
                targetType, campaignId, campaignName, groupId, "", status, status, dailyBudget,
                dailyBudget, BigDecimal.ZERO, null, null, null, null, null,
                AdBulkOperationRules.VERDICT_REJECT,
                "广告组不存在或已被删除");
      } else {
        plan = AdBulkOperationRules.plan(target, params, now);
      }
      items.add(new PlannedItem(itemId, plan));
    }
    return new Planned(items, null);
  }

  /** 护栏裁决（整批）。 */
  private String guardrail(Planned planned) {
    List<AdBulkPlanItem> plans = planned.items().stream().map(PlannedItem::plan).toList();
    AdBulkOperationRules.Totals totals = AdBulkOperationRules.summarize(plans);
    boolean groupTarget =
        !plans.isEmpty() && "AD_GROUP".equals(plans.get(0).targetType());
    return AdBulkOperationRules.guardrailRejection(
        totals,
        plans.size(),
        AdBulkOperationService.MAX_CAMPAIGNS_PER_TASK,
        AdBulkOperationService.MAX_GROUPS_PER_TASK,
        groupTarget);
  }

  // ==================================================================
  // 执行
  // ==================================================================

  /** 执行一条：应用变更、写审计、落结果。 */
  private void execute(AccessService.Access actor, AdBulkTaskExecution exec, PlannedItem item) {
    AdBulkPlanItem plan = item.plan();
    String verdict = plan.verdict();

    if (AdBulkOperationRules.VERDICT_APPLY.equals(verdict)) {
      apply(actor, exec, plan);
      writeItem(item.itemId(), plan, "APPLIED", "已生效（本地），尚未回写平台");
    } else if (AdBulkOperationRules.VERDICT_SKIP.equals(verdict)) {
      writeItem(item.itemId(), plan, "SKIPPED", plan.message());
    } else {
      writeItem(item.itemId(), plan, "REJECTED", plan.message());
    }
  }

  /** 把变更真正写下去（只落本地，并标记「待回写平台」）。 */
  private void apply(AccessService.Access actor, AdBulkTaskExecution exec, AdBulkPlanItem plan) {
    OffsetDateTime now = OffsetDateTime.now();
    switch (exec.operation()) {
      case "ADJUST_BUDGET", "SET_BUDGET" -> {
        sql.execute(
            "update ad_campaign set daily_budget = ?, pending_platform_writeback = true,"
                + " local_changed_at = ?::timestamptz, version = version + 1, updated_at = now()"
                + " where tenant_id = "
                + TENANT
                + " and id = ?",
            plan.afterBudget(),
            now,
            plan.campaignId());
        logCampaignOperation(exec, plan, "APPLIED", "已生效（本地），尚未回写平台");
      }
      case "PAUSE", "ENABLE" -> {
        sql.execute(
            "update ad_campaign set status = ?, pending_platform_writeback = true,"
                + " local_changed_at = ?::timestamptz, version = version + 1, updated_at = now()"
                + " where tenant_id = "
                + TENANT
                + " and id = ?",
            plan.afterStatus(),
            now,
            plan.campaignId());
        logCampaignOperation(exec, plan, "APPLIED", "已生效（本地），尚未回写平台");
      }
      case "SET_END_AT" -> {
        sql.execute(
            "update ad_campaign set end_at = ?::timestamptz, pending_platform_writeback = true,"
                + " local_changed_at = ?::timestamptz, version = version + 1, updated_at = now()"
                + " where tenant_id = "
                + TENANT
                + " and id = ?",
            plan.afterEndAt(),
            now,
            plan.campaignId());
        // 🔴 结束时间**不写** ad_campaign_operation_log：那张表的 operation 枚举
        //   只有 PAUSE / ENABLE / SET_BUDGET / ADJUST_BUDGET，加一项要 ALTER 上游的表。
        //   宁可少写一张表，也不用「借一个不相关的 operation 值」把事实写歪。
        //   这一条的审计落在 ad_campaign_bulk_task_item 上。
      }
      case "ADJUST_BID" -> {
        sql.execute(
            "update ad_campaign_group set default_bid = ?, updated_at = now()"
                + " where tenant_id = "
                + TENANT
                + " and id = ?",
            plan.afterBid(),
            plan.groupId());
        // 🔴 广告组表没有 pending_platform_writeback 列（V277 没有加），
        //   于是「本地已改、还没回写平台」这个信号记在**活动**上 ——
        //   活动列表因此能看见「这条活动的竞价被本地改过，还没回写平台」。
        sql.execute(
            "update ad_campaign set pending_platform_writeback = true,"
                + " local_changed_at = ?::timestamptz, version = version + 1, updated_at = now()"
                + " where tenant_id = "
                + TENANT
                + " and id = ?",
            now,
            plan.campaignId());
        logBid(exec, plan, "APPLIED", "已生效（本地），尚未回写平台");
      }
      default -> throw new IllegalStateException("unreachable operation " + exec.operation());
    }
  }

  /**
   * 活动级操作审计（V273 的 {@code ad_campaign_operation_log}）。
   *
   * <p>🔴 本模块是该表的**第二个写入方**（14.1.1.1 是第一个）：改动来自批量任务
   * 不该让「谁改了这条活动的预算」这个事实消失。
   */
  private void logCampaignOperation(
      AdBulkTaskExecution exec, AdBulkPlanItem plan, String result, String message) {
    String operation = exec.operation();
    if (!"ADJUST_BUDGET".equals(operation)
        && !"SET_BUDGET".equals(operation)
        && !"PAUSE".equals(operation)
        && !"ENABLE".equals(operation)) {
      return;
    }
    sql.execute(
        "insert into ad_campaign_operation_log (tenant_id,id,campaign_id,operation,before_status,"
            + " after_status,before_budget,after_budget,requested_by,reason,result,message,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,now())",
        currentTenant(),
        Ids.next(),
        plan.campaignId(),
        operation,
        plan.beforeStatus(),
        plan.afterStatus(),
        plan.beforeBudget(),
        plan.afterBudget(),
        exec.requestedBy(),
        exec.reason(),
        result,
        message);
  }

  /**
   * 组级改竞价审计（V277 的 {@code ad_campaign_group_bid_log}）。
   *
   * <p>🔴 三种结果都留：被拒的那几条同样要能复盘「为什么这条没生效」。
   */
  private void logBid(
      AdBulkTaskExecution exec, AdBulkPlanItem plan, String result, String message) {
    sql.execute(
        "insert into ad_campaign_group_bid_log (tenant_id,id,campaign_id,group_id,before_bid,"
            + " after_bid,bid_currency,result,message,requested_by,reason,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,now())",
        currentTenant(),
        Ids.next(),
        plan.campaignId(),
        plan.groupId(),
        plan.beforeBid(),
        plan.afterBid() == null ? BigDecimal.ZERO : plan.afterBid(),
        "",
        result,
        message,
        exec.requestedBy(),
        exec.reason());
  }

  /** 写回一条的执行结果（**实际值**，不是计划值）。 */
  private void writeItem(UUID itemId, AdBulkPlanItem plan, String result, String message) {
    sql.execute(
        "update ad_campaign_bulk_task_item set before_status = ?, after_status = ?,"
            + " before_budget = ?, after_budget = ?, before_bid = ?, after_bid = ?,"
            + " before_end_at = ?::timestamptz, after_end_at = ?::timestamptz,"
            + " result = ?, message = ?, executed_at = now()"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        plan.beforeStatus(),
        plan.afterStatus(),
        plan.beforeBudget(),
        plan.afterBudget(),
        plan.beforeBid(),
        plan.afterBid(),
        plan.beforeEndAt(),
        plan.afterEndAt(),
        result,
        message,
        itemId);
  }

  /** 🔴 整批被护栏拒绝：一条都不执行，但每一条都留痕。 */
  private void rejectAll(UUID tenant, UUID taskId, Planned planned, String reason) {
    for (PlannedItem item : planned.items()) {
      writeItem(item.itemId(), item.plan(), "REJECTED", reason);
    }
    sql.execute(
        "update ad_campaign_bulk_task set status = 'REJECTED', applied_count = 0,"
            + " rejected_count = ?, skipped_count = 0, message = ?, finished_at = now()"
            + " where tenant_id = ? and id = ?",
        planned.items().size(),
        reason,
        tenant,
        taskId);
  }

  /** 收尾：按逐条结果汇总任务状态。 */
  private void finalize(UUID tenant, UUID taskId) {
    Record counts =
        sql.fetchOne(
            "select count(*) filter (where result = 'APPLIED') applied,"
                + " count(*) filter (where result = 'REJECTED') rejected,"
                + " count(*) filter (where result = 'SKIPPED') skipped"
                + " from ad_campaign_bulk_task_item where tenant_id = "
                + TENANT
                + " and task_id = ?",
            taskId);
    int applied = counts == null ? 0 : counts.get("applied", Integer.class);
    int rejected = counts == null ? 0 : counts.get("rejected", Integer.class);
    int skipped = counts == null ? 0 : counts.get("skipped", Integer.class);
    // 🔴 DONE = 没有一条被拒；PARTIAL = 有被拒的。两条都表示「跑完了」。
    String status = rejected == 0 ? "DONE" : "PARTIAL";
    sql.execute(
        "update ad_campaign_bulk_task set status = ?, applied_count = ?, rejected_count = ?,"
            + " skipped_count = ?, finished_at = now() where tenant_id = ? and id = ?",
        status,
        applied,
        rejected,
        skipped,
        tenant,
        taskId);
  }

  /** 执行失败：把原因写进任务行，让任务详情页能显示而不是永远停在「执行中」。 */
  private void fail(AccessService.Access actor, String permission, UUID taskId, Exception cause) {
    String message =
        cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    try {
      access.run(
          actor,
          permission,
          () -> {
            sql.execute(
                "update ad_campaign_bulk_task set status = 'FAILED', message = ?"
                    + " where tenant_id = ? and id = ? and status = 'RUNNING'",
                message.length() > 500 ? message.substring(0, 500) : message,
                actor.tenantId(),
                taskId);
            return null;
          });
    } catch (RuntimeException ignored) {
      // 连失败都写不进去（数据库不可用）时只能记日志：再抛会把工作线程打死。
      log.error("批量任务 {} 失败且无法回写状态", taskId, ignored);
    }
  }

  private UUID currentTenant() {
    return UUID.fromString(
        sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')").get(0, String.class));
  }

  /** 供测试复用的操作名归一（与服务层同一套）。 */
  static String normalizeOperation(String operation) {
    return operation == null ? "" : operation.trim().toUpperCase(Locale.ROOT);
  }
}
