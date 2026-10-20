package com.starcon.channel;

import static com.starcon.channel.ListingPublishTaskModels.*;

import java.time.Duration;import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 4.3.1.3 发布任务 —— 单次发布任务的进度视图。
 *
 * <p>蓝图：「单次发布任务的进度视图：各步骤状态（内容校验 → 平台创建 → 变体创建 → 媒体上传 → 价格库存设置 → 提交审核）、失败步骤与原因、可重试的步骤。输入：任务 ID；平台返回结果。输出：步骤时间线 + 错误明细 + 重试操作。关系：上游 = 4.3.1.1/4.3.1.2；下游 = 2.5.1.1。<b>平台提交审核是异步的</b>，任务在「审核中」结束，实际结果由 4.3.2.2 回流。」
 *
 * <p>🔴 本类里有三处**结构**上的不变量：
 *
 * <ol>
 *   <li><b>本模块是进度视图，不是另一个发布系统</b>。它读 4.3.1.1 的
 *       {@code listing_publish_job_target}（= 一个发布任务）与
 *       {@code listing_publish_job_step}（= 步骤时间线），以及 4.3.1.2 的
 *       {@code publish_task}（= 队列里的一条发布任务）。
 *       <b>它自己只写一张表</b>：{@code listing_publish_step_attempt}（重试尝试留痕）。
 *   <li><b>重试动作委托给唯一的写入所有者</b>。{@code listing_publish_job_target} 与
 *       {@code listing_publish_job_step} 的写入所有者是 4.3.1.1（它的状态机由 DB 触发器
 *       {@code guard_publish_target_state} / {@code guard_publish_step_state} 钉死）。
 *       本模块**不写**这两张表 —— 重试**委托**给 4.3.1.1 的既有重试路径执行，
 *       由它推进状态；本模块只记录「自己发起了第几次尝试、结果如何」。
 *   <li><b>「已重新提交」≠「已发布」</b>。重试尝试的 {@code outcome=SUCCEEDED} 只表示
 *       「重试动作被接受并已重新提交平台」。平台提交审核是异步的，最终结果由 4.3.2.2 回流。
 * </ol>
 *
 * <p>🔴 蓝图那句六阶段（内容校验 → 平台创建 → 变体创建 → 媒体上传 → 价格库存设置 → 提交审核）
 * 与 4.3.1.1 落的八个步骤键**不是一一对应**：4.3.1.1 没有单独记「平台创建」与「变体创建」。
 * 本模块**如实报出**这两段没有独立步骤行（见 {@link #capabilities()} 的 phases），
 * <b>不凭空造两个步骤</b> —— 造出来的步骤是假数据。
 */
@Service
public class ListingPublishTaskService {

  /** 一页最多几条任务。 */
  public static final int MAX_PAGE_SIZE = 200;

  /** 05 定价未交付 ⇒ 取价没有数据源（ownerModule）。 */
  static final String OWNER_PRICING = "5.1.1.1";

  /** 4.3.1.2 批量发布（重试归它自己的批次重试入口）。 */
  static final String OWNER_BATCH = "4.3.1.2";

  /** 4.3.1.1 一键刊登（步骤行的写入所有者）。 */
  static final String OWNER_ONECLICK = "4.3.1.1";

  /** 4.3.2.2 平台状态回流（平台结果的权威来源）。 */
  static final String OWNER_STATUS_REFLOW = "4.3.2.2";

  /** 蓝图六阶段 + 4.3.1.1 额外记的两段，按时间线顺序。 */
  static final List<String> PHASE_ORDER =
      List.of("内容校验", "建立映射", "媒体上传", "价格库存设置", "提交审核", "平台结果");

  /** 步骤键 → 蓝图阶段。 */
  static final Map<String, String> STEP_PHASE =
      Map.of(
          "TEMPLATE", "内容校验",
          "CONTENT", "内容校验",
          "MAPPING", "建立映射",
          "MEDIA", "媒体上传",
          "PRICE", "价格库存设置",
          "STOCK", "价格库存设置",
          "SUBMIT", "提交审核",
          "RESULT", "平台结果");

  /** 作业级步骤（PRICE / STOCK）—— 它们是**整个作业**做的，不是某个目标做的。 */
  static final List<String> JOB_SCOPE_STEPS = List.of("PRICE", "STOCK");

  /**
   * 🔴 可以**重试**的步骤键。
   *
   * <p>只有 MEDIA / SUBMIT / RESULT 三个：它们对应**同一次平台发布请求**，
   * 重试其中任何一个都是「把同一份冻结内容重新提交一次」。
   *
   * <p>TEMPLATE / CONTENT / MAPPING **不可重试** —— 内容快照写入后由 DB 触发器钉死不可变，
   * 重跑会改掉「当时发的是什么」；PRICE / STOCK 不可重试 —— 价格与可售量快照同样冻结在作业行上。
   */
  static final List<String> RETRIABLE_STEPS = List.of("MEDIA", "SUBMIT", "RESULT");

  private final DSLContext sql;
  private final ListingPublishJobService publishes;

  public ListingPublishTaskService(DSLContext sql, ListingPublishJobService publishes) {
    this.sql = sql;
    this.publishes = publishes;
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static boolean blank(String value) {
    return text(value).isEmpty();
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  private UUID parseId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (RuntimeException e) {
      throw notFound();
    }
  }

  /** 筛选参数里的 id —— 写错格式是**请求**的问题（422），不是「资源不存在」（404）。 */
  private UUID parseFilterId(String raw, String label) {
    try {
      return UUID.fromString(raw.trim());
    } catch (RuntimeException e) {
      throw unprocessable(label + " 不是合法的 id：" + raw);
    }
  }

  /** 取列值（列不存在时返回 null —— 两条上游的列集不一样）。 */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object raw(Record record, String name) {
    Field field = record.field(name);
    return field == null ? null : record.get((Field) field);
  }

  private static String str(Record record, String name) {
    Object value = raw(record, name);
    return value == null ? null : String.valueOf(value);
  }

  private static UUID uuid(Record record, String name) {
    Object value = raw(record, name);
    if (value == null) {
      return null;
    }
    return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
  }

  private static OffsetDateTime time(Record record, String name) {
    Object value = raw(record, name);
    return value instanceof OffsetDateTime moment ? moment : null;
  }

  private static int intOf(Record record, String name) {
    Object value = raw(record, name);
    return value instanceof Number number ? number.intValue() : 0;
  }

  /** 归一化阶段：两条上游各自的原始状态 → 同一组阶段词（原始 state 另行保留，不丢）。 */
  static String stage(String kind, String state) {
    String value = text(state);
    if ("ONECLICK".equals(kind)) {
      return switch (value) {
        case "DRAFT" -> "PENDING";
        case "SUBMITTED" -> "IN_REVIEW";
        default -> value;
      };
    }
    return switch (value) {
      case "PENDING" -> "PENDING";
      case "DISPATCHED" -> "IN_REVIEW";
      default -> value;
    };
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 逐维度能力声明。
   *
   * <p>🔴 蓝图六阶段里**「平台创建」与「变体创建」没有独立步骤行**（4.3.1.1 没有单独记这两段），
   * 这里如实报出（{@code stepKeys} 为空 + note 写明归属 4.3.1.1），不凭空造步骤。
   */
  public ListingPublishTaskCapabilities capabilities() {
    return new ListingPublishTaskCapabilities(
        List.of(
            new ListingPublishTaskCapabilityDimension(
                "publish_task_progress_view",
                "🔴 单次发布任务的进度视图（步骤时间线 + 错误明细 + 重试操作）",
                true,
                "listing_publish_job_target + listing_publish_job_step（V196，4.3.1.1 已交付，只读）"
                    + " + publish_task（V183，4.3.1.2 已交付，只读）",
                "",
                "一个页面同时看到两条上游的发布任务，按 taskKind 区分，不揉成一套状态"),
            new ListingPublishTaskCapabilityDimension(
                "step_timeline",
                "🔴 步骤时间线：按蓝图阶段序，同阶段内按 position",
                true,
                "listing_publish_job_step（V196）",
                "",
                "目标级步骤（套模板 / 填充内容 / 建立映射 / 上传媒体 / 提交审核 / 平台结果）"
                    + "与作业级步骤（价格 / 库存）都进同一条时间线，并标出各自的归属"),
            new ListingPublishTaskCapabilityDimension(
                "error_details",
                "🔴 错误明细：失败步骤与原因、任务级失败 / 驳回原因、每次重试没成的原因",
                true,
                "listing_publish_job_step.detail + listing_publish_job_target.failure_reason"
                    + " + listing_publish_step_attempt.reason（V200）",
                "",
                "三个来源分得开：任务级 / 步骤级 / 尝试级，不会把「平台驳回」和「提交失败」混成一件事"),
            new ListingPublishTaskCapabilityDimension(
                "step_retry",
                "🔴 重试操作：可重试的步骤 + 重试尝试留痕",
                true,
                "listing_publish_step_attempt（V200，本模块唯一写入的表）"
                    + " + 委托 4.3.1.1 的既有重试路径（目标级状态机的写入所有者）",
                "",
                "MEDIA / SUBMIT / RESULT 可重试（重发同一份冻结内容）；"
                    + "TEMPLATE / CONTENT / MAPPING 与 PRICE / STOCK 不可重试并写明原因；"
                    + "被平台驳回的任务也不可重试"),
            new ListingPublishTaskCapabilityDimension(
                "platform_create_and_variant_phases",
                "🔴 蓝图六阶段里的「平台创建」与「变体创建」",
                false,
                "4.3.1.1（" + OWNER_ONECLICK + "）没有为这两段单独记步骤行",
                OWNER_ONECLICK,
                "本模块**不凭空造两个步骤行**（那是假数据）；时间线上这两段没有独立条目，"
                    + "相关内容随「提交平台审核」与「平台结果」两步体现"),
            new ListingPublishTaskCapabilityDimension(
                "price_source_retry",
                "🔴 价格步骤的重试（重新取价）",
                false,
                "05 定价（" + OWNER_PRICING + " 未开发）",
                OWNER_PRICING,
                "价格快照已冻结在作业行上（写入后不可变），取价来源也尚未交付；"
                    + "价格步骤显示为被挡住并写明归属，要改价请重新发起一次发布"),
            new ListingPublishTaskCapabilityDimension(
                "batch_task_retry",
                "🔴 4.3.1.2 队列任务的重试",
                false,
                "4.3.1.2 批量发布（" + OWNER_BATCH + "）的批次重试入口",
                OWNER_BATCH,
                "批量发布是「一个批次 → 一条队列，按平台配额排队」，"
                    + "与本模块的「单次发布任务」是两套独立的状态机；"
                    + "队列任务也没有步骤行，时间线显示为不可用并写明原因"),
            new ListingPublishTaskCapabilityDimension(
                "platform_result_reflow",
                "🔴 平台结果回流（审核通过 / 驳回的实际结果）",
                true,
                "4.3.2.2 平台状态回流（" + OWNER_STATUS_REFLOW + "）是平台侧状态的权威来源；"
                    + "本模块读 4.3.1.1 的目标行与步骤行呈现当前状态",
                "",
                "🔴 平台提交审核是异步的：任务在「审核中」结束就是正常结局，"
                    + "本模块**不把「已提交」显示成「已发布」**"),
            new ListingPublishTaskCapabilityDimension(
                "publish_history",
                "平台侧的发布留痕（发的是哪份内容、平台回了什么）",
                true,
                "listing_publish_record（V175，4.1.1.2 已交付，只读）",
                "",
                "发布成功后回填的平台商品 id 能对到平台侧留痕；"
                    + "对不上时显示为空，不拿别的商品的留痕凑数")),
        List.of(
            new ListingPublishTaskPhase(
                "内容校验",
                "套用模板 / 填充内容（4.3.1.1 记的两步）",
                List.of("TEMPLATE", "CONTENT"),
                ""),
            new ListingPublishTaskPhase(
                "平台创建",
                "在平台上创建商品",
                List.of(),
                "4.3.1.1 没有为这一段单独记步骤行（它随「提交平台审核」一起发生）；"
                    + "归属 " + OWNER_ONECLICK + "，本模块不凭空造步骤"),
            new ListingPublishTaskPhase(
                "变体创建",
                "创建变体（多规格）",
                List.of(),
                "4.3.1.1 没有为这一段单独记步骤行；归属 " + OWNER_ONECLICK
                    + "，本模块不凭空造步骤"),
            new ListingPublishTaskPhase(
                "媒体上传",
                "上传图片 / 视频素材",
                List.of("MEDIA"),
                ""),
            new ListingPublishTaskPhase(
                "价格库存设置",
                "设置价格与可售量（作业级：一次刊登给所有目标用同一个价与同一份可售量）",
                List.of("PRICE", "STOCK"),
                ""),
            new ListingPublishTaskPhase(
                "提交审核",
                "提交平台审核（提交之后是**异步**的）",
                List.of("SUBMIT"),
                ""),
            new ListingPublishTaskPhase(
                "建立映射",
                "建立内部 SKU ↔ 目标店铺 / 站点的映射（4.3.1.1 额外记的一步）",
                List.of("MAPPING"),
                "蓝图六段里没有独立的一段，是 4.3.1.1 多记的一步"),
            new ListingPublishTaskPhase(
                "平台结果",
                "平台回执（审核通过 / 驳回）",
                List.of("RESULT"),
                "4.3.1.1 额外记的一步；蓝图说平台结果由 4.3.2.2 回流")),
        ListingPublishTaskModels.TASK_KINDS,
        ListingPublishTaskModels.STAGES,
        ListingPublishTaskModels.STEP_STATES,
        ListingPublishTaskModels.STEP_KEYS,
        ListingPublishTaskModels.ERROR_SCOPES,
        MAX_PAGE_SIZE,
        "平台提交审核是**异步**的：任务走到「提交审核」之后就进入「审核中」，"
            + "此时它并没有发布成功。最终结果由 4.3.2.2 平台状态回流写回。",
        "跨平台发布不是一个事务：一次一键刊登给每个目标建一行任务，某个平台失败"
            + "（提交失败 / 被驳回）只改它自己那一行，其余目标照旧；重试只重试失败的那一个。",
        "重试有两种粒度：**任务级**（整条任务重来）与**步骤级**（重试时间线上的某一步）。"
            + "MEDIA / SUBMIT / RESULT 对应同一次平台发布请求，重试其中任何一个都是"
            + "把同一份冻结内容重新提交一次。内容快照已冻结的步骤（TEMPLATE / CONTENT / MAPPING）"
            + "与价格 / 库存快照已冻结的步骤（PRICE / STOCK）**不可重试** —— 重跑会改掉"
            + "「当时发的是什么」。",
        "上游 = 4.3.1.1 一键刊登（发布任务与步骤时间线的来源）、"
            + "4.3.1.2 批量发布（队列里的发布任务）；下游 = 2.5.1.1 回写任务"
            + "（重试通过 4.3.1.1 的既有路径落到 platform_operation，operation='PUBLISH'）。");
  }

  // ==================================================================
  // 读：任务列表
  // ==================================================================

  /**
   * 发布任务列表（真分页 + 真筛选）。
   *
   * <p>🔴 两条上游聚成一张列表，但**状态不揉**：每行带 {@code taskKind} 与原始
   * {@code state}，归一化后的 {@code stage} 只用于筛选与展示。
   */
  public ListingPublishTaskPage tasks(
      String kind, String stage, String platformId, String shopId, int page, int size) {
    int safePage = Math.max(0, page);
    int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));

    String kindFilter = blank(kind) ? "" : kind.trim().toUpperCase();
    if (!kindFilter.isEmpty() && !ListingPublishTaskModels.TASK_KINDS.contains(kindFilter)) {
      throw unprocessable("未知的任务来源：" + kind);
    }
    String stageFilter = blank(stage) ? "" : stage.trim().toUpperCase();
    if (!stageFilter.isEmpty() && !ListingPublishTaskModels.STAGES.contains(stageFilter)) {
      throw unprocessable("未知的任务阶段：" + stage);
    }
    UUID platform = blank(platformId) ? null : parseFilterId(platformId, "平台 id");
    UUID shop = blank(shopId) ? null : parseFilterId(shopId, "店铺 id");

    var filter = new FilterBuilder();
    filter.add("u.task_kind = ?", kindFilter);
    filter.add("u.stage = ?", stageFilter);
    filter.add("u.platform_id = ?", platform);
    filter.add("u.shop_id = ?", shop);

    String union = taskUnionSql();
    Object totalValue =
        sql.fetchValue(
            "select count(*) from (" + union + ") u" + filter.whereClause(),
            filter.binds().toArray());
    int total = totalValue instanceof Number number ? number.intValue() : 0;
    List<Record> rows =
        sql.fetch(
            "select * from (" + union + ") u"
                + filter.whereClause()
                + " order by u.created_at desc, u.task_id limit ? offset ?",
            concat(filter.binds(), safeSize, safePage * safeSize));

    List<ListingPublishTaskRow> result = new ArrayList<>();
    for (Record row : rows) {
      result.add(toTask(row, str(row, "task_kind")));
    }
    return new ListingPublishTaskPage(result, safePage, safeSize, (int) total);
  }

  /** 两条上游的发布任务聚成一个集合（列名对齐，原始状态各自保留）。 */
  private String taskUnionSql() {
    return """
        select t.id as task_id, 'ONECLICK'::text as task_kind,
               case t.state when 'DRAFT' then 'PENDING' when 'SUBMITTED' then 'IN_REVIEW'
                            else t.state end as stage,
               t.job_id, null::uuid as batch_id, t.position,
               t.platform_id, p.code as platform_code, t.shop_id, s.code as shop_code,
               t.marketplace_id, m.code as marketplace_code, t.locale,
               t.state, t.attempts, coalesce(t.failure_reason,'') as failure_reason,
               (select count(*) from listing_publish_job_step st
                 where st.tenant_id = t.tenant_id and st.target_id = t.id) as step_count,
               (select count(*) from listing_publish_job_step st
                 where st.tenant_id = t.tenant_id and st.target_id = t.id
                   and st.state = 'FAILED') as failed_step_count,
               t.dispatched_at, t.completed_at, t.created_at, t.updated_at
        from listing_publish_job_target t
        join platform p on p.id = t.platform_id
        join shop s on s.tenant_id = t.tenant_id and s.id = t.shop_id
        join marketplace m on m.id = t.marketplace_id
        where t.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
        union all
        select k.id as task_id, 'BATCH'::text as task_kind,
               case k.state when 'PENDING' then 'PENDING' when 'DISPATCHED' then 'IN_REVIEW'
                            else k.state end as stage,
               null::uuid as job_id, k.batch_id, k.position,
               k.platform_id, p.code as platform_code, k.shop_id, s.code as shop_code,
               null::uuid as marketplace_id, null::text as marketplace_code, null::text as locale,
               k.state, k.attempts, coalesce(k.failure_reason,'') as failure_reason,
               0::bigint as step_count, 0::bigint as failed_step_count,
               k.dispatched_at, k.completed_at, k.created_at, k.updated_at
        from publish_task k
        join platform p on p.id = k.platform_id
        join shop s on s.tenant_id = k.tenant_id and s.id = k.shop_id
        where k.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
        """;
  }

  /** 动态 WHERE（只加真有的筛选 ⇒ 不会写出「= null」这种永远不成立的谓词）。 */
  private static final class FilterBuilder {
    private final List<String> clauses = new ArrayList<>();
    private final List<Object> binds = new ArrayList<>();

    void add(String clause, Object value) {
      if (value == null || (value instanceof String s && s.isEmpty())) {
        return;
      }
      clauses.add(clause);
      binds.add(value);
    }

    String whereClause() {
      return clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    }

    List<Object> binds() {
      return binds;
    }
  }

  private static Object[] concat(List<Object> head, Object... tail) {
    Object[] all = new Object[head.size() + tail.length];
    for (int i = 0; i < head.size(); i++) {
      all[i] = head.get(i);
    }
    for (int i = 0; i < tail.length; i++) {
      all[head.size() + i] = tail[i];
    }
    return all;
  }

  private ListingPublishTaskRow toTask(Record row, String fallbackKind) {
    String kind = str(row, "task_kind");
    if (blank(kind)) {
      kind = fallbackKind;
    }
    String state = str(row, "state");
    return new ListingPublishTaskRow(
        uuid(row, "task_id"),
        kind,
        str(row, "stage"),
        state,
        uuid(row, "job_id"),
        uuid(row, "batch_id"),
        uuid(row, "platform_id"),
        str(row, "platform_code"),
        uuid(row, "shop_id"),
        str(row, "shop_code"),
        uuid(row, "marketplace_id"),
        str(row, "marketplace_code"),
        str(row, "locale"),
        intOf(row, "position"),
        intOf(row, "attempts"),
        text(str(row, "failure_reason")),
        intOf(row, "step_count"),
        intOf(row, "failed_step_count"),
        "ONECLICK".equals(kind),
        time(row, "dispatched_at"),
        time(row, "completed_at"),
        time(row, "created_at"),
        time(row, "updated_at"));
  }

  // ==================================================================
  // 读：任务详情 / 步骤时间线
  // ==================================================================

  /**
   * 任务详情 = 步骤时间线 + 错误明细 + 重试尝试。
   *
   * <p>🔴 4.3.1.2 的队列任务**没有步骤行**（那条通道按平台配额排队，不落步骤），
   * 这时 {@code stepTimelineAvailable=false} 且写明原因 —— **不拿空数组冒充「已完成」**。
   */
  public ListingPublishTaskDetail task(String rawTaskId) {
    UUID taskId = parseId(rawTaskId);
    Record oneClick = oneClickTask(taskId);
    if (oneClick != null) {
      return oneClickDetail(oneClick);
    }
    Record batch = batchTask(taskId);
    if (batch != null) {
      return batchDetail(batch);
    }
    throw notFound();
  }

  /** 步骤时间线（单独一条，便于界面只刷新时间线）。 */
  public List<ListingPublishTaskStepRow> steps(String rawTaskId) {
    UUID taskId = parseId(rawTaskId);
    Record oneClick = oneClickTask(taskId);
    if (oneClick == null) {
      if (batchTask(taskId) != null) {
        return List.of();
      }
      throw notFound();
    }
    return timeline(oneClick);
  }

  private Record oneClickTask(UUID taskId) {
    return sql.fetchOne(
        """
        select t.id as task_id, 'ONECLICK'::text as task_kind,
               case t.state when 'DRAFT' then 'PENDING' when 'SUBMITTED' then 'IN_REVIEW'
                            else t.state end as stage,
               t.job_id, null::uuid as batch_id, t.position,
               t.platform_id, p.code as platform_code, t.shop_id, s.code as shop_code,
               t.marketplace_id, m.code as marketplace_code, t.locale,
               t.state, t.attempts, coalesce(t.failure_reason,'') as failure_reason,
               (select count(*) from listing_publish_job_step st
                 where st.tenant_id = t.tenant_id and st.target_id = t.id) as step_count,
               (select count(*) from listing_publish_job_step st
                 where st.tenant_id = t.tenant_id and st.target_id = t.id
                   and st.state = 'FAILED') as failed_step_count,
               t.external_item_id, t.content_snapshot->>'title' as content_snapshot_title,
               t.dispatched_at, t.completed_at, t.created_at, t.updated_at
        from listing_publish_job_target t
        join platform p on p.id = t.platform_id
        join shop s on s.tenant_id = t.tenant_id and s.id = t.shop_id
        join marketplace m on m.id = t.marketplace_id
        where t.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and t.id = ?
        """,
        taskId);
  }

  private Record batchTask(UUID taskId) {
    return sql.fetchOne(
        """
        select k.id as task_id, 'BATCH'::text as task_kind,
               case k.state when 'PENDING' then 'PENDING' when 'DISPATCHED' then 'IN_REVIEW'
                            else k.state end as stage,
               null::uuid as job_id, k.batch_id, k.position,
               k.platform_id, p.code as platform_code, k.shop_id, s.code as shop_code,
               null::uuid as marketplace_id, null::text as marketplace_code, null::text as locale,
               k.state, k.attempts, coalesce(k.failure_reason,'') as failure_reason,
               0::bigint as step_count, 0::bigint as failed_step_count,
               null::text as external_item_id, null::text as content_snapshot_title,
               k.dispatched_at, k.completed_at, k.created_at, k.updated_at
        from publish_task k
        join platform p on p.id = k.platform_id
        join shop s on s.tenant_id = k.tenant_id and s.id = k.shop_id
        where k.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and k.id = ?
        """,
        taskId);
  }

  private ListingPublishTaskDetail oneClickDetail(Record target) {
    UUID taskId = uuid(target, "task_id");
    UUID jobId = uuid(target, "job_id");
    List<ListingPublishTaskStepRow> timeline = timeline(target);
    List<ListingPublishTaskAttemptRow> attempts = attempts(taskId);
    String state = str(target, "state");

    List<ListingPublishTaskError> errors = new ArrayList<>();
    String failureReason = text(str(target, "failure_reason"));
    if (!failureReason.isEmpty()
        && ("FAILED".equals(state) || "REJECTED".equals(state))) {
      errors.add(
          new ListingPublishTaskError(
              "TASK",
              null,
              null,
              failureReason,
              time(target, "completed_at"),
              taskRetryBlockedReason(state) == null));
    }
    for (ListingPublishTaskStepRow step : timeline) {
      if ("FAILED".equals(step.state()) && !step.detail().isEmpty()) {
        errors.add(
            new ListingPublishTaskError(
                "STEP",
                step.stepKey(),
                step.phase(),
                step.detail(),
                step.finishedAt(),
                step.retryable()));
      }
    }
    for (ListingPublishTaskAttemptRow attempt : attempts) {
      if ("FAILED".equals(attempt.outcome())) {
        errors.add(
            new ListingPublishTaskError(
                "ATTEMPT",
                attempt.stepKey(),
                attempt.stepKey() == null ? null : STEP_PHASE.get(attempt.stepKey()),
                attempt.reason(),
                attempt.finishedAt(),
                false));
      }
    }

    return new ListingPublishTaskDetail(
        toTask(target, "ONECLICK"),
        timeline,
        errors,
        attempts,
        true,
        "",
        publishRecords(target),
        str(target, "content_snapshot_title"),
        str(target, "external_item_id"),
        "平台提交审核是异步的：这一步走完任务就进入「审核中」，"
            + "实际结果由 4.3.2.2 平台状态回流写回。");
  }

  private ListingPublishTaskDetail batchDetail(Record task) {
    UUID taskId = uuid(task, "task_id");
    String state = str(task, "state");
    List<ListingPublishTaskError> errors = new ArrayList<>();
    String failureReason = text(str(task, "failure_reason"));
    if (!failureReason.isEmpty() && "FAILED".equals(state)) {
      errors.add(
          new ListingPublishTaskError(
              "TASK", null, null, failureReason, time(task, "completed_at"), false));
    }
    return new ListingPublishTaskDetail(
        toTask(task, "BATCH"),
        List.of(),
        errors,
        List.of(),
        false,
        "4.3.1.2 的队列任务**不落步骤行**（那条通道按平台配额排队，没有逐步骤记录）；"
            + "它的进度与重试归 4.3.1.2 批量发布自己的批次队列。",
        List.of(),
        null,
        null,
        "批量发布是「一个批次 → 一条队列，按平台配额排队」，"
            + "与本模块的「单次发布任务」是两套独立的状态机。");
  }

  /**
   * 步骤时间线：目标级步骤 + 作业级步骤（PRICE / STOCK）。
   *
   * <p>🔴 排序按**蓝图阶段序**，同一阶段内按 position ——
   * 纯按 position 排会把作业级的 PRICE(position=0) 插到目标级的 TEMPLATE(position=0) 旁边，
   * 读起来像两步并行。阶段序同时满足「时间线按阶段推进」与「阶段内按 position」。
   */
  private List<ListingPublishTaskStepRow> timeline(Record target) {
    UUID taskId = uuid(target, "task_id");
    UUID jobId = uuid(target, "job_id");
    List<Record> rows =
        sql.fetch(
            """
            select * from listing_publish_job_step
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and ((target_id = ?) or (target_id is null and job_id = ?))
            order by position, id
            """,
            taskId,
            jobId);

    List<ListingPublishTaskAttemptRow> attempts = attempts(taskId);
    Map<String, List<ListingPublishTaskAttemptRow>> byStep = new LinkedHashMap<>();
    for (ListingPublishTaskAttemptRow attempt : attempts) {
      if (attempt.stepKey() == null) {
        continue;
      }
      byStep.computeIfAbsent(attempt.stepKey(), key -> new ArrayList<>()).add(attempt);
    }

    String targetState = str(target, "state");
    List<ListingPublishTaskStepRow> result = new ArrayList<>();
    for (Record row : rows) {
      String stepKey = str(row, "step_key");
      String stepState = str(row, "state");
      String blocked = retryBlockedReason(stepKey, stepState, targetState);
      List<ListingPublishTaskAttemptRow> stepAttempts =
          byStep.getOrDefault(stepKey, List.of());
      result.add(
          new ListingPublishTaskStepRow(
              uuid(row, "id"),
              taskId,
              jobId,
              uuid(row, "target_id"),
              stepKey,
              STEP_PHASE.getOrDefault(stepKey, stepKey),
              uuid(row, "target_id") == null ? "JOB" : "TARGET",
              intOf(row, "position"),
              stepState,
              text(str(row, "detail")),
              blocked == null,
              blocked == null ? "" : blocked,
              time(row, "started_at"),
              time(row, "finished_at"),
              durationMillis(time(row, "started_at"), time(row, "finished_at")),
              stepAttempts.size(),
              stepAttempts));
    }
    result.sort(
        Comparator.comparingInt((ListingPublishTaskStepRow row) -> phaseOrder(row.phase()))
            .thenComparing(ListingPublishTaskStepRow::scope)
            .thenComparingInt(ListingPublishTaskStepRow::position));
    return result;
  }

  /** 阶段在时间线上的次序；未知阶段排最后（不排最前，免得抢在正文前面）。 */
  private static int phaseOrder(String phase) {
    int index = PHASE_ORDER.indexOf(phase);
    return index < 0 ? PHASE_ORDER.size() : index;
  }

  private static Long durationMillis(OffsetDateTime start, OffsetDateTime end) {
    if (start == null || end == null) {
      return null;
    }
    return Duration.between(start, end).toMillis();
  }

  /** 重试尝试留痕（4.3.1.3 自己的表）。 */
  private List<ListingPublishTaskAttemptRow> attempts(UUID taskId) {
    List<Record> rows =
        sql.fetch(
            """
            select * from listing_publish_step_attempt
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and task_id = ?
            order by attempt_no
            """,
            taskId);
    List<ListingPublishTaskAttemptRow> result = new ArrayList<>();
    for (Record row : rows) {
      result.add(
          new ListingPublishTaskAttemptRow(
              uuid(row, "id"),
              intOf(row, "attempt_no"),
              str(row, "step_key"),
              str(row, "scope"),
              str(row, "outcome"),
              text(str(row, "reason")),
              uuid(row, "platform_operation_id"),
              time(row, "requested_at"),
              time(row, "finished_at")));
    }
    return result;
  }

  /** 平台侧的发布留痕（读 4.1.1.2 的 listing_publish_record，只读）。 */
  private List<ListingPublishTaskPublishRecord> publishRecords(Record target) {
    String externalItemId = text(str(target, "external_item_id"));
    if (externalItemId.isEmpty()) {
      return List.of();
    }
    List<Record> rows =
        sql.fetch(
            """
            select r.id, r.action, r.outcome, r.reason, r.platform_request_id, r.occurred_at
            from listing_publish_record r
            join listing l on l.tenant_id = r.tenant_id and l.id = r.listing_id
            where r.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and l.shop_id = ? and l.external_item_id = ?
            order by r.occurred_at desc, r.id
            """,
            uuid(target, "shop_id"),
            externalItemId);
    List<ListingPublishTaskPublishRecord> result = new ArrayList<>();
    for (Record row : rows) {
      result.add(
          new ListingPublishTaskPublishRecord(
              uuid(row, "id"),
              str(row, "action"),
              str(row, "outcome"),
              text(str(row, "reason")),
              text(str(row, "platform_request_id")),
              time(row, "occurred_at")));
    }
    return result;
  }

  // ==================================================================
  // 重试
  // ==================================================================

  /**
   * 🔴 不能重试的原因；**返回 null 表示可以重试**。
   *
   * <p>这个方法是「可重试的步骤」的唯一口径 —— 详情页上的徽标与重试按钮、
   * 重试端点的准入判断，走的是**同一段代码**，不可能出现「界面说能重试、点下去被拒」。
   */
  String retryBlockedReason(String stepKey, String stepState, String targetState) {
    String state = text(stepState);
    // 🔴 内容快照已冻结的步骤**无论当前是什么状态**都不能重跑 —— 理由写在最前面，
    //    因为它回答的是更深的问题（"能不能重跑这一步再求一遍内容"），而"已经成功"
    //    只回答了表面那层。DB 触发器 guard_publish_target_snapshot 也钉死了同一件事。
    if ("TEMPLATE".equals(stepKey) || "CONTENT".equals(stepKey) || "MAPPING".equals(stepKey)) {
      return "内容快照已冻结（写入后不可变，重跑会改掉「当时发的是什么」）；"
          + "请改内容后重新发起一次发布。";
    }
    if ("SUCCEEDED".equals(state)) {
      return "这一步已经成功，不需要重试。";
    }
    if ("RUNNING".equals(state)) {
      return "这一步还在进行中，没有可重试的动作。";
    }
    if ("PENDING".equals(state)) {
      return "这一步还没开始，没有失败可重试。";
    }
    if ("SKIPPED".equals(state)) {
      return "这一步是刻意跳过的（本次不设置库存）；要改请重新发起一次发布。";
    }
    if ("BLOCKED".equals(state)) {
      if ("PRICE".equals(stepKey)) {
        return "价格来源 05 定价（" + OWNER_PRICING + "）未交付，且价格快照已冻结在作业行上"
            + "（写入后不可变）；要改价请重新发起一次发布。";
      }
      return "这一步被前置条件挡住，重试之前要先解决挡住它的条件。";
    }
    // state == FAILED
    if (JOB_SCOPE_STEPS.contains(stepKey)) {
      return "价格 / 库存快照已冻结在作业行上（写入后不可变）；"
          + "请改来源策略后重新发起一次发布。";
    }
    if (!RETRIABLE_STEPS.contains(stepKey)) {
      return "这一步没有可重试的动作。";
    }
    if ("REJECTED".equals(targetState)) {
      return "平台已驳回：内容需要修改后重新发起，重发同一份内容没有意义。";
    }
    if (!"FAILED".equals(targetState)) {
      return "发布任务当前状态是 " + text(targetState) + "，只有失败的任务能重试。";
    }
    return null;
  }

  /** 任务级重试的准入：与 4.3.1.1 的目标状态机一致（只有 FAILED 能重试，REJECTED 不能）。 */
  String taskRetryBlockedReason(String targetState) {
    String state = text(targetState);
    if ("REJECTED".equals(state)) {
      return "平台已驳回：内容需要修改后重新发起，重发同一份内容没有意义。";
    }
    if (!"FAILED".equals(state)) {
      return "发布任务当前状态是 " + state + "，只有失败的任务能重试。";
    }
    return null;
  }

  /**
   * 🔴 步骤级重试：重试时间线上的某一步。
   *
   * <p>实际动作**委托**给 4.3.1.1 的既有重试路径（它是目标级状态机的唯一写入所有者）；
   * 本模块只记录「这是第几次尝试、成没成」。
   */
  public ListingPublishTaskRetryResult retryStep(String rawTaskId, String rawStepKey) {
    UUID taskId = parseId(rawTaskId);
    String stepKey = rawStepKey == null ? "" : rawStepKey.trim().toUpperCase();
    if (!ListingPublishTaskModels.STEP_KEYS.contains(stepKey)) {
      throw unprocessable("未知的步骤键：" + rawStepKey);
    }
    Record target = oneClickTask(taskId);
    if (target == null) {
      if (batchTask(taskId) != null) {
        throw unprocessable(
            "4.3.1.2 的队列任务没有步骤行；批量重试归 " + OWNER_BATCH + " 批量发布自己的批次重试入口。");
      }
      throw notFound();
    }
    String targetState = str(target, "state");
    Record step = stepRecord(taskId, uuid(target, "job_id"), stepKey);
    if (step == null) {
      throw notFound("该发布任务没有这一步：" + stepKey);
    }
    String blocked = retryBlockedReason(stepKey, str(step, "state"), targetState);
    if (blocked != null) {
      throw unprocessable(blocked);
    }
    return performRetry(target, stepKey, "STEP_RETRY");
  }

  /**
   * 🔴 任务级重试：整条任务重来。
   *
   * <p>派发就失败的时候，时间线上**没有任何一步是 FAILED**（步骤还停在 PENDING），
   * 步骤级重试无从谈起 —— 这时唯一有意义的就是重试整条任务。
   */
  public ListingPublishTaskRetryResult retryTask(String rawTaskId) {
    UUID taskId = parseId(rawTaskId);
    Record target = oneClickTask(taskId);
    if (target == null) {
      if (batchTask(taskId) != null) {
        throw unprocessable(
            "4.3.1.2 的队列任务的重试归 " + OWNER_BATCH + " 批量发布自己的批次重试入口。");
      }
      throw notFound();
    }
    String blocked = taskRetryBlockedReason(str(target, "state"));
    if (blocked != null) {
      throw unprocessable(blocked);
    }
    return performRetry(target, null, "TASK_RETRY");
  }

  private Record stepRecord(UUID taskId, UUID jobId, String stepKey) {
    return sql.fetchOne(
        """
        select * from listing_publish_job_step
        where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
          and ((target_id = ?) or (target_id is null and job_id = ?))
          and step_key = ?
        """,
        taskId,
        jobId,
        stepKey);
  }

  /**
   * 执行一次重试：委托 4.3.1.1 → 记一笔尝试留痕。
   *
   * <p>🔴 4.3.1.1 的重试失败时抛的是业务异常（不是 DB 错误），事务**没有**被 abort，
   * 所以这里能安全地补一条 outcome=FAILED 的留痕再返回 —— 「重试没成」本身就是要留痕的事实。
   */
  private ListingPublishTaskRetryResult performRetry(Record target, String stepKey, String triggerKind) {
    UUID tenant = currentTenant();
    UUID taskId = uuid(target, "task_id");
    UUID jobId = uuid(target, "job_id");

    String reason = "";
    UUID operationId = null;
    String outcome = "SUCCEEDED";
    try {
      var updated = publishes.retry(taskId.toString());
      operationId = updated.platformOperationId();
      reason =
          "已重新提交平台审核（下游 2.5.1.1 的回写任务 "
              + (operationId == null ? "已生成" : operationId)
              + "）；平台结果由 4.3.2.2 回流。";
    } catch (ResponseStatusException e) {
      outcome = "FAILED";
      reason = e.getReason() == null ? "重试失败" : e.getReason();
    } catch (RuntimeException e) {
      outcome = "FAILED";
      reason = e.getMessage() == null ? "重试失败" : e.getMessage();
    }

    int attemptNo = recordAttempt(tenant, taskId, jobId, stepKey, triggerKind, outcome, reason, operationId);

    Record refreshed = oneClickTask(taskId);
    return new ListingPublishTaskRetryResult(
        taskId,
        stepKey,
        attemptNo,
        outcome,
        reason,
        operationId,
        refreshed == null ? List.of() : timeline(refreshed));
  }

  /**
   * 写一条尝试留痕。
   *
   * <p>🔴 attempt_no 是**该任务上的第几次尝试**（跨步骤递增）。并发重试可能算出同一个号，
   * 唯一索引会挡下来 —— 这里用 {@code on conflict do nothing} + 重算重试
   * （不能用「捕获唯一冲突再重试」：PG 里唯一冲突会把整个事务置为 aborted，救不回来）。
   */
  private int recordAttempt(
      UUID tenant,
      UUID taskId,
      UUID jobId,
      String stepKey,
      String triggerKind,
      String outcome,
      String reason,
      UUID operationId) {
    String scope = stepKey == null ? "TASK" : (JOB_SCOPE_STEPS.contains(stepKey) ? "JOB" : "TARGET");
    for (int round = 0; round < 5; round++) {
      int attemptNo = nextAttemptNo(tenant, taskId);
      UUID attemptId = UUID.randomUUID();
      OffsetDateTime now = OffsetDateTime.now();
      int inserted =
          sql.execute(
              """
              insert into listing_publish_step_attempt
                (id,tenant_id,task_id,job_id,step_key,scope,attempt_no,trigger_kind,outcome,reason,
                 platform_operation_id,requested_at,finished_at,created_at)
              values (?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,now())
              on conflict (tenant_id, task_id, attempt_no) do nothing
              """,
              attemptId,
              tenant,
              taskId,
              jobId,
              stepKey,
              scope,
              attemptNo,
              triggerKind,
              outcome,
              reason,
              operationId,
              now,
              now);
      if (inserted > 0) {
        return attemptNo;
      }
    }
    throw new IllegalStateException("重试尝试序号连续冲突，重试已放弃");
  }

  private int nextAttemptNo(UUID tenant, UUID taskId) {
    Object value =
        sql.fetchValue(
            """
            select coalesce(max(attempt_no), 0) + 1
            from listing_publish_step_attempt
            where tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid
              and task_id = ?
            """,
            taskId);
    return value instanceof Number number ? number.intValue() : 1;
  }
}
