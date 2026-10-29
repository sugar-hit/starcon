package com.starcon.channel;

import static com.starcon.channel.ServiceTaskStatusModels.*;
import static com.starcon.channel.ServiceTaskStatusRules.*;

import com.starcon.channel.ServiceTaskStatusRules.ServiceTaskStatusEvaluation;
import com.starcon.channel.ServiceTaskStatusRules.ServiceTaskStatusFacts;
import com.starcon.channel.ServiceTaskStatusSources.TaskSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.5.2.1 服务与任务状态 —— channel 侧<b>只读</b>投影层。
 *
 * <p>蓝图：「展示平台自身运行状态：后台任务 / 同步任务最近运行与耗时、失败任务、
 * 计划任务是否停滞，便于运维与用户自助判断"是系统问题还是数据问题"。
 * 输出：任务状态列表；停滞 / 失败任务提示。」
 *
 * <p>🔴🔴 本类里有六处<b>结构</b>上的不变量：
 *
 * <ol>
 *   <li><b>只读</b>：本类**没有任何写操作**。卷 01 的边界写死「工作台只读 + 跳转」，
 *       所以「重跑同步 / 取消任务 / 补授权」一律表现为**跳转到归属系统**。</li>
 *   <li><b>不新建事实表</b>：一切数字都从 channel 自己的事实表现场聚合
 *       （{@code sync_run} / {@code sync_failure} / {@code backfill_task} /
 *       {@code listing_migration_batch} / {@code publish_task} / {@code listing_collect_task}）。
 *       本仓的先例：1.5.1.1 主动不占用 V338、1.5.1.2 主动不占用 V375 ——
 *       落一张派生表只会比它描述的事实更早过期。</li>
 *   <li><b>不跨服务读别人的表</b>：finance / crm / warehouse / trade / platform 都有
 *       自己的任务表，但那些**不是本模块的数据**。它们要么走各自的授权只读端点，
 *       要么如实报「未接通」—— 本模块**一条跨库 SQL 都没有**。</li>
 *   <li><b>每张表按自己的形状处理</b>：状态列名、取值域、有没有「执行中」、
 *       有没有耗时列，全都不同 ⇒ 由 {@link ServiceTaskStatusSources} 逐表描述，
 *       SQL 与能力声明读同一份描述。</li>
 *   <li><b>时间基准是数据库的 {@code now()}</b>：「已执行多少分钟」由 SQL 用
 *       {@code now()} 算好（{@code elapsed_minutes}），本类不引入应用层墙钟。</li>
 *   <li><b>「系统故障」与「业务数据问题」分得开</b>：失败分类是一个**数据字段**
 *       （{@code failureClass}），不是一个说明文案。</li>
 * </ol>
 */
@Service
public class ServiceTaskStatusService {

  /** 权限码：复用 02.4 同步运行已有的 {@code channel.sync.read}，不新增权限码（identity 零改动）。 */
  static final String READ = "channel.sync.read";

  /** 任务范围的默认窗口（天）：只报「还在执行的」与「最近这么多天发生的」。 */
  public static final int DEFAULT_TASK_WINDOW_DAYS = 30;

  /** 提示清单的单类上限。 */
  private static final int MAX_ALERTS = 200;

  // ================================================================ 阈值（可配）

  private final int stallWarnMinutes;
  private final int stallCriticalMinutes;
  private final int failureWindowHours;
  private final int taskWindowDays;

  private final DSLContext sql;

  public ServiceTaskStatusService(
      DSLContext sql,
      @Value("${channel.service-task-status.stall-warn-minutes:"
          + ServiceTaskStatusRules.DEFAULT_STALL_WARN_MINUTES
          + "}")
          int stallWarnMinutes,
      @Value("${channel.service-task-status.stall-critical-minutes:"
          + ServiceTaskStatusRules.DEFAULT_STALL_CRITICAL_MINUTES
          + "}")
          int stallCriticalMinutes,
      @Value("${channel.service-task-status.failure-window-hours:"
          + ServiceTaskStatusRules.DEFAULT_FAILURE_WINDOW_HOURS
          + "}")
          int failureWindowHours,
      @Value("${channel.service-task-status.task-window-days:" + DEFAULT_TASK_WINDOW_DAYS + "}")
          int taskWindowDays) {
    this.sql = sql;
    this.stallWarnMinutes = Math.max(1, stallWarnMinutes);
    this.stallCriticalMinutes = Math.max(this.stallWarnMinutes + 1, stallCriticalMinutes);
    this.failureWindowHours = Math.max(1, failureWindowHours);
    this.taskWindowDays = Math.max(1, taskWindowDays);
  }

  // ================================================================ 口径说明

  /** 「停滞」的判定口径。 */
  private String stallSemantics() {
    return "🔴 **停滞**是一个具名判定，不是拍脑袋："
        + "① 任务处于**执行中**（各来源表自己的「执行中」取值，见 sources 清单）；"
        + "② 且**已执行时长 > 阈值**。已执行时长由 **数据库的 now()** 算出"
        + "（SQL 里的 stall_minutes），**不用应用层墙钟** —— 本仓已经两次因为墙钟与事务时钟分叉造成假红。"
        + "阈值：**预警线 "
        + stallWarnMinutes
        + " 分钟**（⇒ 黄）、**红线 "
        + stallCriticalMinutes
        + " 分钟**（⇒ 红），"
        + "可用 Spring 属性 `channel.service-task-status.stall-warn-minutes` / "
        + "`channel.service-task-status.stall-critical-minutes` 覆盖（也可用同名环境变量）。"
        + "🔴 **判不了是一种状态**：在跑但来源表没记开始时刻（backfill_task.started_at 可空）"
        + "⇒ 落 STALL_UNDETERMINED（黄），**不**折成「没卡住」。"
        + "🔴 **停滞 ≠ 失败**：停滞是「没在跑」，失败是「跑挂了」，两者在本模块是两个独立列表。";
  }

  /** 「系统故障 vs 业务数据问题」的判定口径。 */
  private String failureClassSemantics() {
    return "🔴 蓝图的本模块存在的理由是**区分「系统故障」与「业务数据问题」**，"
        + "所以这不是一句说明文案，而是端点里的**分类字段** failureClass + 各自的条数："
        + "① **BUSINESS（业务 / 配置问题）** = "
        + BUSINESS_FAILURE_REASONS
        + " —— 系统没坏，是数据 / 配置不对，要人处理；"
        + "② **SYSTEM（系统 / 外部问题）** = "
        + SYSTEM_FAILURE_REASONS
        + " —— 毛病在平台侧或网络侧，用户的数据本身没问题；"
        + "③ **MIXED** = 一次失败里两类都有；"
        + "④ **UNCLASSIFIED** = 有失败，但**来源表没有受控原因词表**"
        + "（publish_task.failure_reason 是自由文本、listing_collect_task 只有 http_status）"
        + "⇒ 本模块**不替它们编一套分类**，标成判不了；"
        + "⑤ **UNDETERMINED** = 失败队列**读不到**（数据范围受限）⇒ 判不了；"
        + "⑥ **NONE** = 没有失败。";
  }

  /** 「读不到」与「没有」的口径。 */
  private String readFailureSemantics() {
    return "🔴「读不到」与「没有」是**两件事**，本模块绝不把前者折成后者："
        + "① 上游读不到 ⇒ 整个端点返回 503，**不**回空集合冒充「一切正常」；"
        + "② 当前角色的数据范围（SELF / DEPARTMENT）看不到失败队列时，"
        + "失败分类标 UNDETERMINED（判不了），**不**写成「没有失败」；"
        + "③ 数据范围受限而看不到的**任务本身**就不在统计里 —— "
        + "那是数据范围口径，**不是**「这个租户没有任务」。";
  }

  /** 上游说明。 */
  private String upstreamNote() {
    return "上游 = 02.4 同步运行（sync_run）+ 02.4 失败队列（sync_failure）+ 02.6.2.1 增量补拉（backfill_task）"
        + "+ 04.4.2.2 批量搬迁（listing_migration_batch）+ 04.3.1.3 发布任务（publish_task）"
        + "+ 04.4.1.1 链接采集（listing_collect_task）。全部是**本服务自己库里的表**，"
        + "本模块**不跨服务读别人的表**。";
  }

  /** 下游说明。 */
  private String downstreamNote() {
    return "下游 = 1.3 通知。🔴 本模块**只读**，**不提供**「重跑同步 / 取消任务 / 重新授权」这类写端点 —— "
        + "那是归属系统的动作，提示一律是**跳转到归属系统**。";
  }

  // ================================================================ 小工具

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
  }

  /**
   * 🔴 <b>上游读不到 ⇒ 503</b>，而不是空集合。
   *
   * <p>把 DB 异常折成「没有任务 / 没有失败」是最危险的一种撒谎：页面会显示一片绿，
   * 而真相是「服务挂了，什么都没查成」。
   */
  private static ResponseStatusException unreadable(Throwable cause) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "服务与任务状态读不到：上游事实表暂时无法访问，请稍后重试"
            + "（本页不显示任何结论，以免把「读不到」误读成「一切正常」）",
        cause);
  }

  /** 🔴 同源 DB 时钟：判定时刻 / 数据截至时刻一律从这里取。 */
  private OffsetDateTime dbNow() {
    Object raw = sql.fetchValue("select now()");
    return raw instanceof OffsetDateTime value ? value : OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** 🔴 同源 DB 时钟之外，租户上下文只来自已认证成员关系（AccessService 绑定）。 */
  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  private static UUID parseUuid(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String placeholders(int count) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < count; i++) {
      builder.append(builder.length() == 0 ? "?" : ",?");
    }
    return builder.toString();
  }

  /**
   * 🔴 <b>失败队列是否可见</b>。
   *
   * <p>{@code sync_failure} 上挂着 RESTRICTIVE 的 {@code failure_scope} 策略，
   * 数据范围是 SELF / DEPARTMENT 时这张表**一行都看不到**。那时候「失败计数为 0」
   * 是**假象**（不是「没有失败」，是「没权限看到失败」），所以这里显式问一次数据库。
   */
  private boolean failureScopeVisible() {
    Object raw = sql.fetchValue("select channel_scope(channel_operation(), null, null)");
    return raw instanceof Boolean value ? value : true;
  }

  /**
   * 🔴 <b>SQL 字面量的安全引号</b>。
   *
   * <p>只放行 <b>字母 / 数字 / 下划线 / 点 / 连字符 / 汉字</b> —— 这些值全部来自本模块的
   * 代码常量（来源键、来源名、归属模块号、状态取值域、失败原因词表），**没有一个来自请求**。
   * 一旦有人把用户输入接到这条路上来，这里会直接抛异常而不是拼出可注入的 SQL。
   */
  private static String literal(String value) {
    String text = text(value);
    if (!text.matches("[A-Za-z0-9_.\\-一-鿿]+")) {
      throw new IllegalArgumentException("非法的 SQL 字面量：" + value);
    }
    return "'" + text + "'";
  }

  private static String literalList(List<String> values) {
    List<String> quoted = new ArrayList<>();
    for (String value : values) {
      quoted.add(literal(value));
    }
    return String.join(",", quoted);
  }

  // ================================================================ 统一投影 SQL

  /**
   * 🔴 <b>五个来源归一成一个投影</b>。
   *
   * <p>每个来源都产出**同一组列**，所以下面那条 union 的列名对每一支都成立。
   * 列的含义：
   *
   * <ul>
   *   <li>{@code raw_state} —— 那张表<b>自己</b>的状态值（不规范化，页面要能核对）；
   *   <li>{@code state} —— 本模块规范化后的状态；
   *   <li>{@code stall_minutes} —— <b>在执行中</b>时已执行的分钟数（数据库 now() 算出）；
   *   <li>{@code failure_business / failure_system} —— 该任务的失败里两类各几条（只有 sync_run 有词表）。</li>
   * </ul>
   *
   * <p>🔴 任务范围 = <b>还在执行的</b> ∪ <b>最近 {@code taskWindowDays} 天发生的</b>。
   * 更早的历史任务不在本页范围内（能力声明里写明了这一点）。
   */
  private String unionSql(List<Object> params, OffsetDateTime windowStart) {
    // 🔴 租户只查一次：每个来源的 select 都要用它，重复查等于把同一次判定做五遍。
    UUID tenant = currentTenant();
    StringBuilder union = new StringBuilder();
    for (TaskSource source : ServiceTaskStatusSources.ALL) {
      if (union.length() > 0) {
        union.append(" union all ");
      }
      union.append(sourceSelect(source, params, windowStart, tenant));
    }
    return union.toString();
  }

  /** 单个来源的投影 SELECT。 */
  private String sourceSelect(
      TaskSource source, List<Object> params, OffsetDateTime windowStart, UUID tenant) {
    switch (source.sourceKey()) {
      case "sync_run":
        return syncRunSelect(source, params, windowStart, tenant);
      case "backfill_task":
        return backfillTaskSelect(source, params, windowStart, tenant);
      case "listing_migration_batch":
        return listingMigrationBatchSelect(source, params, windowStart, tenant);
      case "publish_task":
        return publishTaskSelect(source, params, windowStart, tenant);
      case "listing_collect_task":
        return listingCollectTaskSelect(source, params, windowStart, tenant);
      default:
        throw new IllegalStateException("未登记的任务来源：" + source.sourceKey());
    }
  }

  /** 规范化状态的 CASE 表达式（由来源描述生成 ⇒ SQL 与文档不可能对不上）。 */
  private static String canonicalCase(TaskSource source, String alias) {
    StringBuilder builder = new StringBuilder("case");
    for (Map.Entry<String, String> entry : source.canonicalStates().entrySet()) {
      builder
          .append(" when ")
          .append(alias)
          .append(".")
          .append(source.stateColumn())
          .append(" = ")
          .append(literal(entry.getKey()))
          .append(" then ")
          .append(literal(entry.getValue()));
    }
    builder.append(" else ").append(literal(STATE_UNKNOWN)).append(" end");
    return builder.toString();
  }

  /** 「执行中」的原始取值谓词（没有则恒假）。 */
  private static String inFlightPredicate(TaskSource source, String alias) {
    if (source.inFlightValues().isEmpty()) {
      return "false";
    }
    return alias
        + "."
        + source.stateColumn()
        + " in ("
        + literalList(source.inFlightValues())
        + ")";
  }

  /** 🔴 02.4 同步运行：耗时 = duration_ms，失败分类来自 sync_failure（受控六值词表）。 */
  private String syncRunSelect(
      TaskSource source, List<Object> params, OffsetDateTime windowStart, UUID tenant) {
    params.add(tenant);
    params.add(tenant);
    params.add(windowStart);
    String sqlText =
        "select r.id as task_id, "
            + literal(source.sourceKey())
            + " as source_key, "
            + literal(source.label())
            + " as source_label, "
            + literal(source.ownerModule())
            + " as owner_module, "
            + "(coalesce(p.name,'') || ' · ' || coalesce(c.display_name, c.authorized_account,'')"
            + " || ' · ' || coalesce(cu.resource_type,'')) as title, "
            + "cu.resource_type as detail, "
            + "r.status as raw_state, "
            + canonicalCase(source, "r")
            + " as state, "
            + "r.started_at as started_at, r.finished_at as finished_at, r.duration_ms::bigint as duration_ms, "
            + "r.trigger_source as trigger_source, r.started_at as occurred_at, "
            + "case when r.status = "
            + literal("RUNNING")
            + " then floor(extract(epoch from (now() - r.started_at))/60)::bigint else null end as stall_minutes, "
            + "coalesce(fs.failure_total, 0)::bigint as failure_total, "
            + "coalesce(fs.failure_business, 0)::bigint as failure_business, "
            + "coalesce(fs.failure_system, 0)::bigint as failure_system "
            + "from sync_run r "
            + "left join sync_cursor cu on cu.tenant_id = r.tenant_id and cu.id = r.cursor_id "
            + "left join channel_connection c on c.tenant_id = cu.tenant_id and c.id = cu.connection_id "
            + "left join platform p on p.id = c.platform_id "
            + "left join (select f.run_id, count(*) as failure_total, "
            + "count(*) filter (where f.reason in ("
            + literalList(BUSINESS_FAILURE_REASONS)
            + ")) as failure_business, "
            + "count(*) filter (where f.reason in ("
            + literalList(SYSTEM_FAILURE_REASONS)
            + ")) as failure_system "
            + "from sync_failure f where f.tenant_id = ? group by f.run_id) fs on fs.run_id = r.id "
            + "where r.tenant_id = ? and (r.status = "
            + literal("RUNNING")
            + " or r.started_at >= ?::timestamptz)";
    return sqlText;
  }

  /** 🔴 02.6.2.1 增量补拉：started_at 可空 ⇒ 在跑却没记开始时刻的任务落 UNDETERMINED。 */
  private String backfillTaskSelect(
      TaskSource source, List<Object> params, OffsetDateTime windowStart, UUID tenant) {
    params.add(tenant);
    params.add(windowStart);
    return "select t.id as task_id, "
        + literal(source.sourceKey())
        + " as source_key, "
        + literal(source.label())
        + " as source_label, "
        + literal(source.ownerModule())
        + " as owner_module, "
        + "(coalesce(p.name,'') || ' · ' || coalesce(c.display_name, c.authorized_account,'')"
        + " || ' · ' || coalesce(t.resource_type,'')) as title, "
        + "(coalesce(t.resource_type,'') || ' · ' || coalesce(t.pagination_mode,'')) as detail, "
        + "t.status as raw_state, "
        + canonicalCase(source, "t")
        + " as state, "
        + "t.started_at as started_at, t.finished_at as finished_at, t.duration_ms::bigint as duration_ms, "
        + "t.trigger_source as trigger_source, coalesce(t.started_at, t.created_at) as occurred_at, "
        + "case when t.status = "
        + literal("RUNNING")
        + " and t.started_at is not null "
        + "then floor(extract(epoch from (now() - t.started_at))/60)::bigint else null end as stall_minutes, "
        + "0::bigint as failure_total, 0::bigint as failure_business, 0::bigint as failure_system "
        + "from backfill_task t "
        + "left join channel_connection c on c.tenant_id = t.tenant_id and c.id = t.connection_id "
        + "left join platform p on p.id = c.platform_id "
        + "where t.tenant_id = ? and (t.status = "
        + literal("RUNNING")
        + " or coalesce(t.started_at, t.created_at) >= ?::timestamptz)";
  }

  /**
   * 🔴 04.4.2.2 批量搬迁：<b>没有「执行中」这一档，也没有耗时列</b> ⇒ 停滞与耗时都判不了。
   *
   * <p>这里的 {@code started_at} 刻意置 null：{@code created_at} 是「批次被创建的
   * 时刻」，不是「开始执行的时刻」，拿它冒充开始时刻就是在编事实。
   */
  private String listingMigrationBatchSelect(
      TaskSource source, List<Object> params, OffsetDateTime windowStart, UUID tenant) {
    params.add(tenant);
    params.add(windowStart);
    return "select b.id as task_id, "
        + literal(source.sourceKey())
        + " as source_key, "
        + literal(source.label())
        + " as source_label, "
        + literal(source.ownerModule())
        + " as owner_module, "
        + "(coalesce(s.code, s.name, '未知源店铺') || ' → ' || coalesce(cardinality(b.target_shop_ids), 0)"
        + " || ' 个目标店铺') as title, "
        + "(b.state || ' · 候选 ' || b.candidate_count || ' · 搬迁 ' || b.migrated_count"
        + " || ' · 跳过 ' || b.skipped_count || ' · 失败 ' || b.failed_count) as detail, "
        + "b.state as raw_state, "
        + canonicalCase(source, "b")
        + " as state, "
        + "null::timestamptz as started_at, null::timestamptz as finished_at, null::bigint as duration_ms, "
        + "null::text as trigger_source, b.created_at as occurred_at, "
        + "null::bigint as stall_minutes, "
        + "0::bigint as failure_total, 0::bigint as failure_business, 0::bigint as failure_system "
        + "from listing_migration_batch b "
        + "left join shop s on s.tenant_id = b.tenant_id and s.id = b.source_shop_id "
        + "where b.tenant_id = ? and b.created_at >= ?::timestamptz";
  }

  /** 🔴 04.3.1.3 发布任务：DISPATCHED = 在途（DB 钉死了非 PENDING 必有 dispatched_at）。 */
  private String publishTaskSelect(
      TaskSource source, List<Object> params, OffsetDateTime windowStart, UUID tenant) {
    params.add(tenant);
    params.add(windowStart);
    return "select t.id as task_id, "
        + literal(source.sourceKey())
        + " as source_key, "
        + literal(source.label())
        + " as source_label, "
        + literal(source.ownerModule())
        + " as owner_module, "
        + "(coalesce(p.name,'') || ' · ' || coalesce(s.code, s.name, '')) as title, "
        + "(t.state || ' · 第 ' || t.attempts || ' 次尝试') as detail, "
        + "t.state as raw_state, "
        + canonicalCase(source, "t")
        + " as state, "
        + "t.dispatched_at as started_at, t.completed_at as finished_at, "
        + "case when t.dispatched_at is not null and t.completed_at is not null "
        + "then (extract(epoch from (t.completed_at - t.dispatched_at))*1000)::bigint else null end as duration_ms, "
        + "null::text as trigger_source, coalesce(t.dispatched_at, t.created_at) as occurred_at, "
        + "case when t.state = "
        + literal("DISPATCHED")
        + " and t.dispatched_at is not null "
        + "then floor(extract(epoch from (now() - t.dispatched_at))/60)::bigint else null end as stall_minutes, "
        + "0::bigint as failure_total, 0::bigint as failure_business, 0::bigint as failure_system "
        + "from publish_task t "
        + "left join platform p on p.id = t.platform_id "
        + "left join shop s on s.tenant_id = t.tenant_id and s.id = t.shop_id "
        + "where t.tenant_id = ? and (t.state = "
        + literal("DISPATCHED")
        + " or coalesce(t.dispatched_at, t.created_at) >= ?::timestamptz)";
  }

  /**
   * 🔴 04.4.1.1 链接采集：抓取是同步完成的（没有「执行中」这一档）⇒ 停滞不适用。
   *
   * <p>detail 里刻意<b>不放</b> {@code error_message}：那是外部页面的自由文本，
   * 可能带出不该在工作台展示的内容。{@code http_status} 是受控的，照实带给页面。
   */
  private String listingCollectTaskSelect(
      TaskSource source, List<Object> params, OffsetDateTime windowStart, UUID tenant) {
    params.add(tenant);
    params.add(windowStart);
    return "select t.id as task_id, "
        + literal(source.sourceKey())
        + " as source_key, "
        + literal(source.label())
        + " as source_label, "
        + literal(source.ownerModule())
        + " as owner_module, "
        + "(coalesce(t.host,'') || ' · ' || left(coalesce(t.normalized_url,''), 80)) as title, "
        + "('HTTP ' || t.http_status || ' · 第 ' || t.attempt_no || ' 次尝试') as detail, "
        + "t.state as raw_state, "
        + canonicalCase(source, "t")
        + " as state, "
        + "t.started_at as started_at, t.finished_at as finished_at, "
        + "(extract(epoch from (t.finished_at - t.started_at))*1000)::bigint as duration_ms, "
        + "null::text as trigger_source, t.started_at as occurred_at, "
        + "null::bigint as stall_minutes, "
        + "0::bigint as failure_total, 0::bigint as failure_business, 0::bigint as failure_system "
        + "from listing_collect_task t "
        + "where t.tenant_id = ? and t.started_at >= ?::timestamptz";
  }

  // ================================================================ 筛选

  /** 筛选条件。 */
  private record Filters(
      String sourceKey,
      String state,
      String triggerSource,
      String keyword,
      Boolean stalled,
      Boolean failed) {

    void append(StringBuilder statement, List<Object> params, int stallWarnMinutes) {
      if (!sourceKey.isEmpty()) {
        statement.append(" and t.source_key = ?");
        params.add(sourceKey);
      }
      if (!state.isEmpty()) {
        statement.append(" and t.state = ?");
        params.add(state);
      }
      if (!triggerSource.isEmpty()) {
        statement.append(" and t.trigger_source = ?");
        params.add(triggerSource);
      }
      if (Boolean.TRUE.equals(stalled)) {
        statement.append(" and t.stall_minutes is not null and t.stall_minutes > ?");
        params.add(stallWarnMinutes);
      } else if (Boolean.FALSE.equals(stalled)) {
        statement.append(" and (t.stall_minutes is null or t.stall_minutes <= ?)");
        params.add(stallWarnMinutes);
      }
      if (Boolean.TRUE.equals(failed)) {
        statement.append(" and t.state = ?");
        params.add(STATE_FAILED);
      } else if (Boolean.FALSE.equals(failed)) {
        statement.append(" and t.state <> ?");
        params.add(STATE_FAILED);
      }
      if (!keyword.isEmpty()) {
        statement.append(" and (coalesce(t.title,'') ilike ? or coalesce(t.detail,'') ilike ?)");
        String like = "%" + keyword + "%";
        params.add(like);
        params.add(like);
      }
    }
  }

  private static Filters filters(
      String sourceKey, String state, String triggerSource, String keyword, Boolean stalled, Boolean failed) {
    return new Filters(
        text(sourceKey),
        text(state).toUpperCase(),
        text(triggerSource).toUpperCase(),
        text(keyword),
        stalled,
        failed);
  }

  // ================================================================ 任务列表

  /**
   * 任务状态列表（真分页 + 真筛选）。
   *
   * @param sourceKey 来源筛选（空 = 全部）
   * @param state 规范化状态筛选（空 = 全部）
   * @param triggerSource 触发来源筛选（空 = 全部）
   * @param keyword 关键字（标题 / 明细）
   * @param stalled 只看停滞 / 只看没停滞（null = 全部）
   * @param failed 只看失败 / 只看没失败（null = 全部）
   * @param page 页号（从 0 开始）
   * @param size 每页行数
   */
  public ServiceTaskStatusTaskPage tasks(
      String sourceKey,
      String state,
      String triggerSource,
      String keyword,
      Boolean stalled,
      Boolean failed,
      int page,
      int size) {
    try {
      int safePage = Math.max(0, page);
      int safeSize = size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
      Filters filters = filters(sourceKey, state, triggerSource, keyword, stalled, failed);
      OffsetDateTime windowStart = dbNow().minusDays(taskWindowDays);

      List<Object> countParams = new ArrayList<>();
      StringBuilder countSql = new StringBuilder("select count(*) from (");
      countSql.append(unionSql(countParams, windowStart));
      countSql.append(") t where 1=1");
      filters.append(countSql, countParams, stallWarnMinutes);
      int total = count(countSql.toString(), countParams.toArray());

      List<Object> pageParams = new ArrayList<>();
      StringBuilder pageSql = new StringBuilder("select * from (");
      pageSql.append(unionSql(pageParams, windowStart));
      pageSql.append(") t where 1=1");
      filters.append(pageSql, pageParams, stallWarnMinutes);
      pageSql.append(" order by t.occurred_at desc nulls last, t.source_key, t.task_id limit ? offset ?");
      pageParams.add(safeSize);
      pageParams.add(safePage * safeSize);

      List<Record> rows = sql.fetch(pageSql.toString(), pageParams.toArray());
      boolean failuresVisible = failureScopeVisible();
      List<ServiceTaskStatusTaskCard> cards = new ArrayList<>();
      for (Record row : rows) {
        cards.add(toCard(row, failuresVisible));
      }
      return new ServiceTaskStatusTaskPage(List.copyOf(cards), total, safePage, safeSize);
    } catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException e) {
      // 🔴 Spring Boot 的 jOOQ 异常翻译器会先把 jOOQ 的异常翻成 Spring 的
      //    DataAccessException（BadSqlGrammarException 之类）—— 只 catch jOOQ 那个
      //    会**漏掉**真正的上游故障，于是「读不到」被折成空集合。
      throw unreadable(e);
    }
  }

  /** 单条任务。跨租户与不存在返回逐字相同的 404。 */
  public ServiceTaskStatusTaskCard task(String rawId) {
    UUID id = parseUuid(rawId);
    if (id == null) {
      throw notFound();
    }
    try {
      OffsetDateTime windowStart = dbNow().minusDays(taskWindowDays);
      for (TaskSource source : ServiceTaskStatusSources.ALL) {
        List<Object> params = new ArrayList<>();
        StringBuilder statement = new StringBuilder("select * from (");
        statement.append(sourceSelect(source, params, windowStart, currentTenant()));
        statement.append(") t where t.task_id = ?");
        params.add(id);
        List<Record> rows = sql.fetch(statement.toString(), params.toArray());
        if (!rows.isEmpty()) {
          return toCard(rows.get(0), failureScopeVisible());
        }
      }
      throw notFound();
    } catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException e) {
      // 🔴 Spring Boot 的 jOOQ 异常翻译器会先把 jOOQ 的异常翻成 Spring 的
      //    DataAccessException（BadSqlGrammarException 之类）—— 只 catch jOOQ 那个
      //    会**漏掉**真正的上游故障，于是「读不到」被折成空集合。
      throw unreadable(e);
    }
  }

  /** 一行投影 → 一张任务卡片。 */
  private ServiceTaskStatusTaskCard toCard(Record row, boolean failuresVisible) {
    String rawState = row.get("raw_state", String.class);
    String state = row.get("state", String.class);
    Long stallMinutes = row.get("stall_minutes", Long.class);
    boolean inFlight = STATE_RUNNING.equals(state);
    boolean failed = STATE_FAILED.equals(state);
    long failureTotal = longOf(row, "failure_total");
    long failureBusiness = longOf(row, "failure_business");
    long failureSystem = longOf(row, "failure_system");

    String failureClass = failureClass(state, failed, failureTotal, failureBusiness, failureSystem, failuresVisible);
    ServiceTaskStatusEvaluation evaluation =
        evaluate(
            new ServiceTaskStatusFacts(state, stallMinutes, inFlight, failureClass),
            stallWarnMinutes,
            stallCriticalMinutes);

    TaskSource source = ServiceTaskStatusSources.byKey(row.get("source_key", String.class));
    String sourceKey = row.get("source_key", String.class);
    String ownerModule = row.get("owner_module", String.class);
    String timingNote = source == null ? "" : source.timingNote();

    return new ServiceTaskStatusTaskCard(
        row.get("task_id", UUID.class).toString(),
        sourceKey,
        row.get("source_label", String.class),
        ownerModule,
        row.get("title", String.class),
        row.get("detail", String.class),
        rawState,
        state,
        inFlight,
        failed,
        failureClass,
        failureClassNote(failureClass, source),
        evaluation.stallDeterminable(),
        evaluation.stalled(),
        evaluation.stallSeverity(),
        stallMinutes,
        evaluation.severity(),
        evaluation.reasons(),
        row.get("occurred_at", OffsetDateTime.class),
        row.get("started_at", OffsetDateTime.class),
        row.get("finished_at", OffsetDateTime.class),
        row.get("duration_ms", Long.class),
        row.get("trigger_source", String.class),
        timingNote,
        ownerModule,
        targetRoute(ownerModule),
        targetHint(ownerModule),
        targetAvailable(ownerModule),
        targetNote(ownerModule));
  }

  private static long longOf(Record row, String column) {
    Long value = row.get(column, Long.class);
    return value == null ? 0L : value;
  }

  /**
   * 🔴 <b>失败分类</b>：系统故障 vs 业务数据问题。
   *
   * <p>只有 {@code sync_run} 能分类（它的失败落在 {@code sync_failure.reason} 这个
   * 受控六值词表上）；其余来源**没有词表** ⇒ UNCLASSIFIED（判不了，不是「系统故障」）。
   */
  private static String failureClass(
      String state,
      boolean failed,
      long failureTotal,
      long failureBusiness,
      long failureSystem,
      boolean failuresVisible) {
    if (!failed) {
      return FAILURE_CLASS_NONE;
    }
    if (failureTotal > 0) {
      if (failureBusiness > 0 && failureSystem > 0) {
        return FAILURE_CLASS_MIXED;
      }
      if (failureBusiness > 0) {
        return FAILURE_CLASS_BUSINESS;
      }
      if (failureSystem > 0) {
        return FAILURE_CLASS_SYSTEM;
      }
    }
    // 🔴 失败队列读不到 ⇒ 「有没有失败原因」这件事根本读不到 ⇒ 判不了。
    if (!failuresVisible) {
      return FAILURE_CLASS_UNDETERMINED;
    }
    return FAILURE_CLASS_UNCLASSIFIED;
  }

  /** 分类依据的人话说明。 */
  private static String failureClassNote(String failureClass, TaskSource source) {
    return switch (failureClass) {
      case FAILURE_CLASS_BUSINESS ->
          "失败原因落在受控词表里，且全部属业务 / 配置问题"
              + "（授权失效 / 映射缺失 / 字段解析失败）—— 系统没坏，是数据或配置不对。";
      case FAILURE_CLASS_SYSTEM ->
          "失败原因落在受控词表里，且全部属系统 / 外部问题"
              + "（网络错误 / 限流 / 平台侧错误）—— 毛病在平台侧或网络侧。";
      case FAILURE_CLASS_MIXED -> "这次失败里业务 / 配置问题与系统 / 外部问题**都有** —— 两件都要处理。";
      case FAILURE_CLASS_UNDETERMINED ->
          "失败队列在当前角色的数据范围下**读不到** ⇒ 这一条**判不了**"
              + "（不是「没有失败」，也不是「系统故障」）。";
      case FAILURE_CLASS_UNCLASSIFIED ->
          source == null
              ? "这张表没有受控的失败原因词表 ⇒ 这一条**判不了**。"
              : source.failureClassNote();
      default -> "这条任务没有失败记录。";
    };
  }

  // ================================================================ 跳转落点

  /** 🔴 处置落点：只给**真实存在**的路由。 */
  private static String targetRoute(String ownerModule) {
    return switch (ownerModule) {
      case "2.4.1.2" -> "/channels";
      case "2.6.2.1" -> "/channels/backfill";
      case "4.4.2.2" -> "/listings/migrations";
      case "4.3.1.3" -> "/listings/publish";
      case "4.4.1.1" -> "/listings/collect";
      default -> null;
    };
  }

  /** 落点提示。 */
  private static String targetHint(String ownerModule) {
    return switch (ownerModule) {
      case "2.4.1.2" -> "渠道与店铺 → 同步运行";
      case "2.6.2.1" -> "渠道与店铺 → 增量补拉";
      case "4.4.2.2" -> "刊登 → 批量搬迁";
      case "4.3.1.3" -> "刊登 → 发布与同步";
      case "4.4.1.1" -> "刊登 → 链接采集";
      default -> null;
    };
  }

  /** 跳转目标是否真的存在。 */
  private static boolean targetAvailable(String ownerModule) {
    return targetRoute(ownerModule) != null;
  }

  /** 跳转目标的真实状况说明。 */
  private static String targetNote(String ownerModule) {
    return switch (ownerModule) {
      case "2.4.1.2" ->
          "同步运行列表与运行日志在「渠道与店铺」页的「同步运行」Tab 里。"
              + "⚠️ 本仓的渠道工作台用的是**页内 Tab**，没有可深链的 URL，"
              + "所以跳转只能到 /channels 整页，落点写在提示里。";
      case "2.6.2.1" -> "增量补拉任务列表在「渠道与店铺 → 增量补拉」页里。";
      case "4.4.2.2" -> "批量搬迁批次在「刊登 → 批量搬迁」页里。";
      case "4.3.1.3" -> "发布任务在「刊登 → 发布与同步」页里。";
      case "4.4.1.1" -> "链接采集任务在「刊登 → 链接采集」页里。";
      default -> "这一类任务没有独立的归属页面。";
    };
  }

  // ================================================================ 总览

  /** 总览：任务状态分布、停滞与失败计数、失败分类、逐来源统计。 */
  public ServiceTaskStatusSummary summary() {
    try {
      OffsetDateTime windowStart = dbNow().minusDays(taskWindowDays);
      List<Object> params = new ArrayList<>();
      // 🔴 参数顺序 = 占位符在 SQL 文本里出现的顺序：阈值那个 `?` 在 SELECT 列表里，
      //    文本上**早于** union 子查询里的占位符 ⇒ 它的绑定必须先加。
      params.add(stallWarnMinutes);
      StringBuilder statement =
          new StringBuilder(
              "select source_key, source_label, owner_module, count(*) as total, "
                  + "count(*) filter (where state = 'RUNNING') as running, "
                  + "count(*) filter (where state = 'FAILED') as failed, "
                  + "count(*) filter (where state = 'PARTIAL') as partial, "
                  + "count(*) filter (where stall_minutes is not null and stall_minutes > ?) as stalled, "
                  + "count(*) filter (where state = 'RUNNING' and stall_minutes is null) as undeterminable "
                  + "from (");
      statement.append(unionSql(params, windowStart));
      statement.append(") t group by source_key, source_label, owner_module order by source_key");

      Map<String, ServiceTaskStatusSourceStat> stats = new LinkedHashMap<>();
      long total = 0L;
      long running = 0L;
      long failed = 0L;
      long partial = 0L;
      long stalled = 0L;
      long undeterminable = 0L;
      for (Record row : sql.fetch(statement.toString(), params.toArray())) {
        String key = row.get("source_key", String.class);
        ServiceTaskStatusSourceStat stat =
            new ServiceTaskStatusSourceStat(
                key,
                row.get("source_label", String.class),
                row.get("owner_module", String.class),
                longOf(row, "total"),
                longOf(row, "running"),
                longOf(row, "failed"),
                longOf(row, "partial"),
                longOf(row, "stalled"),
                longOf(row, "undeterminable"));
        stats.put(key, stat);
        total += stat.total();
        running += stat.running();
        failed += stat.failed();
        partial += stat.partial();
        stalled += stat.stalled();
        undeterminable += stat.stallUndeterminable();
      }
      List<ServiceTaskStatusSourceStat> sourceStats = new ArrayList<>();
      for (TaskSource source : ServiceTaskStatusSources.ALL) {
        sourceStats.add(
            stats.getOrDefault(
                source.sourceKey(),
                new ServiceTaskStatusSourceStat(
                    source.sourceKey(), source.label(), source.ownerModule(), 0L, 0L, 0L, 0L, 0L, 0L)));
      }

      Map<String, long[]> byReason = loadFailureCountsByReason();
      long recordTotal = 0L;
      long businessTotal = 0L;
      long systemTotal = 0L;
      Map<String, long[]> byClass = new LinkedHashMap<>();
      for (String reason : FAILURE_REASONS) {
        long[] slot = byReason.get(reason);
        if (slot == null) {
          continue;
        }
        long windowCount = slot[0];
        long totalCount = slot[1];
        recordTotal += totalCount;
        String failureClass = classifyReason(reason);
        if (failureClass == null) {
          continue;
        }
        long[] classSlot = byClass.computeIfAbsent(failureClass, key -> new long[2]);
        classSlot[0] += windowCount;
        classSlot[1] += totalCount;
        if (FAILURE_CLASS_BUSINESS.equals(failureClass)) {
          businessTotal += totalCount;
        } else {
          systemTotal += totalCount;
        }
      }

      List<ServiceTaskStatusFailureClassCount> failureByClass = new ArrayList<>();
      for (String failureClass :
          List.of(FAILURE_CLASS_BUSINESS, FAILURE_CLASS_SYSTEM, FAILURE_CLASS_MIXED)) {
        long[] slot = byClass.get(failureClass);
        if (slot == null) {
          continue;
        }
        List<ServiceTaskStatusFailureReasonCount> reasons = new ArrayList<>();
        for (String reason : FAILURE_REASONS) {
          long[] reasonSlot = byReason.get(reason);
          if (reasonSlot == null || (reasonSlot[0] == 0L && reasonSlot[1] == 0L)) {
            continue;
          }
          // 🔴 逐类清单里**只列属于这一类**的原因 —— 把六个值在每一类里都列一遍，
          //    等于把「分了类」又抹平了。
          if (!failureClass.equals(classifyReason(reason))) {
            continue;
          }
          reasons.add(
              new ServiceTaskStatusFailureReasonCount(
                  reason,
                  failureReasonLabel(reason),
                  classifyReason(reason),
                  reasonSlot[0],
                  reasonSlot[1]));
        }
        failureByClass.add(
            new ServiceTaskStatusFailureClassCount(
                failureClass, failureClassLabel(failureClass), slot[0], slot[1], List.copyOf(reasons)));
      }

      return new ServiceTaskStatusSummary(
          total,
          running,
          failed,
          partial,
          stalled,
          undeterminable,
          recordTotal,
          businessTotal,
          systemTotal,
          List.copyOf(failureByClass),
          List.copyOf(sourceStats),
          capabilityGaps(),
          dbNow());
    } catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException e) {
      // 🔴 Spring Boot 的 jOOQ 异常翻译器会先把 jOOQ 的异常翻成 Spring 的
      //    DataAccessException（BadSqlGrammarException 之类）—— 只 catch jOOQ 那个
      //    会**漏掉**真正的上游故障，于是「读不到」被折成空集合。
      throw unreadable(e);
    }
  }

  /** 逐失败原因的窗口 / 累计计数。 */
  private Map<String, long[]> loadFailureCountsByReason() {
    Map<String, long[]> result = new LinkedHashMap<>();
    OffsetDateTime windowStart = dbNow().minusHours(failureWindowHours);
    String statement =
        "select f.reason, count(*) filter (where f.occurred_at >= ?::timestamptz) as window_count, "
            + "count(*) as total_count from sync_failure f where f.tenant_id = ? group by f.reason";
    for (Record row : sql.fetch(statement, windowStart, currentTenant())) {
      result.put(
          row.get("reason", String.class),
          new long[] {
            row.get("window_count", Long.class) == null ? 0L : row.get("window_count", Long.class),
            row.get("total_count", Long.class) == null ? 0L : row.get("total_count", Long.class)
          });
    }
    return result;
  }

  private static String failureClassLabel(String failureClass) {
    return switch (failureClass) {
      case FAILURE_CLASS_BUSINESS -> "业务 / 配置问题";
      case FAILURE_CLASS_SYSTEM -> "系统 / 外部问题";
      case FAILURE_CLASS_MIXED -> "两类都有";
      case FAILURE_CLASS_UNCLASSIFIED -> "有失败但判不了分类";
      case FAILURE_CLASS_UNDETERMINED -> "失败队列读不到";
      default -> "没有失败";
    };
  }

  // ================================================================ 提示

  /**
   * 提示清单 —— 🔴 <b>停滞与失败是两个独立列表</b>。
   *
   * <p>「没在跑」与「跑挂了」是两件事：前者是调度没起来，后者是任务执行中挂掉。
   * 混在一起，运维就分不清该去看调度器还是去看任务日志。
   */
  public ServiceTaskStatusAlertList alerts() {
    try {
      OffsetDateTime windowStart = dbNow().minusDays(taskWindowDays);
      boolean failuresVisible = failureScopeVisible();

      List<Object> stalledParams = new ArrayList<>();
      StringBuilder stalledSql =
          new StringBuilder("select * from (");
      stalledSql.append(unionSql(stalledParams, windowStart));
      stalledSql.append(
          ") t where t.stall_minutes is not null and t.stall_minutes > ?"
              + " order by t.stall_minutes desc, t.occurred_at desc limit ?");
      stalledParams.add(stallWarnMinutes);
      stalledParams.add(MAX_ALERTS + 1);
      List<Record> stalledRows = sql.fetch(stalledSql.toString(), stalledParams.toArray());

      List<Object> failedParams = new ArrayList<>();
      StringBuilder failedSql = new StringBuilder("select * from (");
      failedSql.append(unionSql(failedParams, windowStart));
      failedSql.append(
          ") t where t.state = ? order by t.occurred_at desc nulls last, t.source_key limit ?");
      failedParams.add(STATE_FAILED);
      failedParams.add(MAX_ALERTS + 1);
      List<Record> failedRows = sql.fetch(failedSql.toString(), failedParams.toArray());

      boolean truncated = stalledRows.size() > MAX_ALERTS || failedRows.size() > MAX_ALERTS;

      List<ServiceTaskStatusAlert> stalledAlerts = new ArrayList<>();
      for (Record row : stalledRows) {
        if (stalledAlerts.size() >= MAX_ALERTS) {
          break;
        }
        ServiceTaskStatusTaskCard card = toCard(row, failuresVisible);
        long minutes = card.stallMinutes() == null ? 0L : card.stallMinutes();
        stalledAlerts.add(
            new ServiceTaskStatusAlert(
                "STALLED",
                card.stallSeverity() == null ? SEVERITY_YELLOW : card.stallSeverity(),
                "任务停滞：" + card.sourceLabel() + " · " + card.title(),
                "这条任务还在执行，但已经跑了 "
                    + minutes
                    + " 分钟"
                    + (SEVERITY_RED.equals(card.stallSeverity())
                        ? "（超过红线 " + stallCriticalMinutes + " 分钟）"
                        : "（超过预警线 " + stallWarnMinutes + " 分钟）")
                    + "。停滞 = **没在跑**，不是「跑挂了」。",
                card.sourceKey(),
                card.sourceLabel(),
                card.ownerModule(),
                card.taskId(),
                null,
                card.targetModule(),
                card.targetRoute(),
                card.targetHint(),
                card.targetAvailable(),
                card.targetNote()));
      }

      List<ServiceTaskStatusAlert> failedAlerts = new ArrayList<>();
      for (Record row : failedRows) {
        if (failedAlerts.size() >= MAX_ALERTS) {
          break;
        }
        ServiceTaskStatusTaskCard card = toCard(row, failuresVisible);
        failedAlerts.add(
            new ServiceTaskStatusAlert(
                "FAILED",
                SEVERITY_RED,
                "任务失败：" + card.sourceLabel() + " · " + card.title(),
                "这条任务的状态是 " + card.rawState() + "（跑挂了）。" + card.failureClassNote(),
                card.sourceKey(),
                card.sourceLabel(),
                card.ownerModule(),
                card.taskId(),
                card.failureClass(),
                card.targetModule(),
                card.targetRoute(),
                card.targetHint(),
                card.targetAvailable(),
                card.targetNote()));
      }

      return new ServiceTaskStatusAlertList(
          List.copyOf(stalledAlerts),
          List.copyOf(failedAlerts),
          stalledAlerts.size(),
          failedAlerts.size(),
          truncated);
    } catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException e) {
      // 🔴 Spring Boot 的 jOOQ 异常翻译器会先把 jOOQ 的异常翻成 Spring 的
      //    DataAccessException（BadSqlGrammarException 之类）—— 只 catch jOOQ 那个
      //    会**漏掉**真正的上游故障，于是「读不到」被折成空集合。
      throw unreadable(e);
    }
  }

  // ================================================================ 能力声明

  /** 能力声明：规则表、阈值、任务来源清单、失败分类、未接通 / 未开发的上游。 */
  public ServiceTaskStatusCapabilities capabilities() {
    List<ServiceTaskStatusSourceDoc> sources = new ArrayList<>();
    for (TaskSource source : ServiceTaskStatusSources.ALL) {
      List<String> canonical = new ArrayList<>();
      for (Map.Entry<String, String> entry : source.canonicalStates().entrySet()) {
        canonical.add(entry.getKey() + "→" + entry.getValue());
      }
      sources.add(
          new ServiceTaskStatusSourceDoc(
              source.sourceKey(),
              source.table(),
              source.label(),
              source.ownerModule(),
              source.ownerModuleName(),
              source.stateColumn(),
              source.startColumn(),
              source.finishColumn(),
              source.durationColumn(),
              String.join(" / ", source.stateValues()),
              String.join(" / ", canonical),
              source.inFlightValues().isEmpty() ? "（无 —— 这张表没有「执行中」这一档）" : String.join(" / ", source.inFlightValues()),
              source.stallSupported(),
              source.stallNote(),
              source.timingNote(),
              source.failureClassNote()));
    }
    return new ServiceTaskStatusCapabilities(
        ruleTable(stallWarnMinutes, stallCriticalMinutes),
        thresholds(stallWarnMinutes, stallCriticalMinutes, failureWindowHours),
        SEVERITY_LEVELS,
        List.copyOf(sources),
        capabilityGaps(),
        failureClasses(),
        stallSemantics(),
        failureClassSemantics(),
        readFailureSemantics(),
        upstreamNote(),
        downstreamNote(),
        dbNow());
  }

  /**
   * 🔴 <b>未接通 / 未开发的上游</b>。
   *
   * <p>三类，逐条列出来而不是含糊一句「其它模块没做」：
   *
   * <ol>
   *   <li><b>蓝图点名但整卷未开发</b>：18.3 规则任务、18.1.3.1 导入导出任务、
   *       18.1.3.2 数据迁移 —— 本模块**不造一张规则任务表顶替它们**。</li>
   *   <li><b>其它服务的任务表</b>：finance / crm / warehouse / trade / platform
   *       都有自己的任务表，但那些**不是本模块的数据** —— 跨服务只能走授权 API / event，
   *       本仓还没提供这些只读端点 ⇒ 如实报「未接通」，
   *       <b>绝不</b>为了「把全平台任务收在一页」去建一张跨服务聚合表。</li>
   * </ol>
   */
  private List<ServiceTaskStatusCapabilityGap> capabilityGaps() {
    return List.of(
        new ServiceTaskStatusCapabilityGap(
            "18.3",
            "规则任务（规则列表 / 执行日志）",
            false,
            "18.3.1.1 / 18.3.2.1",
            "🔴 蓝图把「18 规则任务」列为本模块的输入，但**卷 18 的规则部分整卷未开发**"
                + "（台账 18.3.1.1 / 18.3.1.2 / 18.3.2.1 / 18.3.2.2 / 18.3.3.1 全部未开发）。"
                + "本模块**不造一张规则任务表顶替它** —— 那样做出来的会是假事实。"
                + "等 18.3 交付后，它会作为**新的任务来源**接入本模块的注册表。"),
        new ServiceTaskStatusCapabilityGap(
            "18.1.3.1",
            "导入导出任务",
            false,
            "18.1.3.1",
            "统一的数据导入导出通道**未开发**（台账未开发）。"
                + "本模块的「数据迁移任务」输入因此只覆盖了 **channel 自己库里的 "
                + "listing_migration_batch**（04.4.2.2 批量搬迁）—— "
                + "那是本仓真实存在的迁移任务事实，不是 18.1.3.2 的替身。"),
        new ServiceTaskStatusCapabilityGap(
            "18.1.3.2",
            "数据迁移",
            false,
            "18.1.3.2",
            "从其它 ERP 迁移历史数据的通道**未开发**（台账未开发）。"
                + "蓝图输入里的「数据迁移任务」在 18.1.3.2 这条线上**尚不存在**。"),
        new ServiceTaskStatusCapabilityGap(
            "12.x",
            "财务服务的后台任务",
            false,
            "12.5 / 12.3 等 finance 服务自己的模块",
            "finance 服务自己有 finance_collection_task / finance_cost_calculation_run / "
                + "finance_reconciliation_task。🔴 那些是**finance 服务的库**，"
                + "本模块**不跨服务读别人的表**（AGENTS.md：禁止跨服务 SQL / FK / 共享视图）。"
                + "它们要进来，只能等 finance 提供**授权只读端点**，本模块再作为新的任务来源接入。"),
        new ServiceTaskStatusCapabilityGap(
            "15.x",
            "客服服务的后台任务",
            false,
            "15.x crm 服务自己的模块",
            "crm 服务自己有 crm_care_reminder_task / crm_customer_tag_run。"
                + "同上：不在本模块的数据范围里，要进来得走 crm 的授权只读端点。"),
        new ServiceTaskStatusCapabilityGap(
            "07.x / 08.x",
            "仓储服务的后台任务",
            false,
            "07 / 08 warehouse 服务自己的模块",
            "warehouse 服务自己有 allocation_run / inventory_reconciliation_run 等任务表。"
                + "（同名 backfill_task 在 channel 与 warehouse **各有一张**，本模块读的是 "
                + "**channel 自己那张**。）同上：跨服务只能走授权只读端点。"),
        new ServiceTaskStatusCapabilityGap(
            "09.x",
            "履约服务的后台任务",
            false,
            "09 trade 服务自己的模块",
            "trade 服务自己有 fulfillment_dropship_task / fulfillment_dropship_payable_task。"
                + "同上：不在本模块的数据范围里。"),
        new ServiceTaskStatusCapabilityGap(
            "18.4.x",
            "平台服务的后台任务",
            false,
            "18.4 platform 服务自己的模块",
            "platform 服务自己有 platform_text_task / platform_finance_reconcile_task。"
                + "同上：不在本模块的数据范围里。"));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（来源取自注册表，其余取自真实数据）。 */
  public ServiceTaskStatusOptions options() {
    try {
      List<String> sources = new ArrayList<>();
      for (TaskSource source : ServiceTaskStatusSources.ALL) {
        sources.add(source.sourceKey());
      }
      Set<String> triggerSources = new LinkedHashSet<>();
      OffsetDateTime windowStart = dbNow().minusDays(taskWindowDays);
      List<Object> params = new ArrayList<>();
      StringBuilder statement = new StringBuilder("select distinct trigger_source from (");
      statement.append(unionSql(params, windowStart));
      statement.append(") t where t.trigger_source is not null order by trigger_source");
      for (Record row : sql.fetch(statement.toString(), params.toArray())) {
        triggerSources.add(row.get("trigger_source", String.class));
      }
      return new ServiceTaskStatusOptions(
          List.copyOf(sources),
          STATE_LEVELS,
          SEVERITY_LEVELS,
          FAILURE_CLASS_LEVELS,
          List.copyOf(triggerSources));
    } catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException e) {
      // 🔴 Spring Boot 的 jOOQ 异常翻译器会先把 jOOQ 的异常翻成 Spring 的
      //    DataAccessException（BadSqlGrammarException 之类）—— 只 catch jOOQ 那个
      //    会**漏掉**真正的上游故障，于是「读不到」被折成空集合。
      throw unreadable(e);
    }
  }

  private int count(String statement, Object... params) {
    Object raw = sql.fetchValue(statement, params);
    return raw instanceof Number number ? number.intValue() : 0;
  }
}
