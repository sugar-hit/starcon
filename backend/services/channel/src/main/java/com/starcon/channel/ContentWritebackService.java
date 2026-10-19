package com.starcon.channel;

import static com.starcon.channel.ContentWritebackModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 4.3.2.1 内容回写。
 *
 * <p>蓝图：「把内部已发布的商品内容变更推送到平台（改标题、改图、改描述、改 A+），支持按变更类型触发或批量触发，并记录回写前后快照。输入：内容变更事件；用户批量回写操作；目标 listing 集合。输出：回写任务（经 02 队列）+ 变更历史。关系：上游 = 3 商品主数据变更、4.2.1.2 批量编辑；下游 = 2.5.1.1。<b>内容回写不触碰价格与库存</b>（那是 05/07 的事），三条回写通道独立失败独立重试。」
 *
 * <p>🔴 本类的四个核心不变量，每一个都有集成测试逐条钉住：
 *
 * <ol>
 *   <li><b>🔴🔴 内容通道装不下价格与库存</b> —— {@link #create} 逐个点名并 422 拒绝含
 *       PRICE / STOCK / QUANTITY 的请求（<b>不静默忽略</b>：静默忽略会让用户以为价格也推上去了）；
 *       DB 的 CHECK 取值域里同样没有它们。改价 / 改库存分属 05 定价 / 07 库存两条独立通道。
 *   <li><b>三条通道独立失败独立重试</b> —— {@link #retry} 只打回**自己**的 FAILED 任务
 *       （DB 触发器 {@code guard_content_writeback_terminal} 让已成功的打不回去），
 *       它插进 02 队列的 {@code platform_operation} 用的是 {@code operation='PUBLISH'} +
 *       payload 里的 {@code contentWriteback=true}，<b>不碰</b>队列里 UPDATE_PRICE /
 *       UPDATE_STOCK 那些行 —— 那是 05 / 07 的通道，重试内容改不动它们。
 *   <li><b>改前改后两侧都有真实来源</b> —— 改前来自 V175 的平台侧当前快照
 *       （{@code before_source='PLATFORM_SNAPSHOT'}），改后来自 V177 的内部草稿
 *       （{@code after_source='INTERNAL_DRAFT'}）或调用方显式覆盖值（{@code OVERRIDE}）。
 *       内部没有该字段的待推送内容时判 {@code SKIPPED_NO_SOURCE}，<b>不编一个假值</b>。
 *   <li><b>改后与线上逐字相同 ⇒ 不派发，也不报成功</b> —— 那是 {@code SKIPPED_NO_CHANGE}：
 *       对平台的写调用是真实的外部副作用，无意义的写不该发生；而谎报「已推送」比不推送更难发现。
 * </ol>
 *
 * <p>🔴 本模块<b>不模拟平台调用</b>：派发即在途（DISPATCHED），由 4.3.2.2 / 02 平台适配器
 * 通过 {@link #report} 回报成功 / 失败。成功回执时写一条 V175 的
 * {@code listing_publish_record(action='CONTENT_WRITEBACK')} —— 那张表的 CHECK 里
 * {@code CONTENT_WRITEBACK} 这个取值就是为本模块预留的。
 *
 * <p>🔴 时间口径：本类<b>不做任何"相对 N 分钟"的判断</b>；窗口限流只用
 * {@code dispatched_at} 与 {@code now()} 的真实差值，且集成测试用固定时刻构造。
 */
@Service
public class ContentWritebackService {

  private static final String PENDING = "PENDING";
  private static final String SKIPPED_NO_CHANGE = "SKIPPED_NO_CHANGE";
  private static final String DISPATCHED = "DISPATCHED";
  private static final String SUCCEEDED = "SUCCEEDED";
  private static final String FAILED = "FAILED";
  private static final String RUNNING = "RUNNING";
  private static final String COMPLETED = "COMPLETED";
  private static final String CANCELLED = "CANCELLED";

  private final DSLContext sql;
  private final ObjectMapper json;

  public ContentWritebackService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ==================================================================
  // 小工具（照抄 PublishService 的同名写法）
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
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

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value == null ? Map.of() : value);
    } catch (Exception e) {
      return "{}";
    }
  }

  private String jsonText(Object raw) {
    if (raw == null) {
      return "{}";
    }
    String value = raw instanceof org.jooq.JSONB j ? j.data() : String.valueOf(raw);
    if (value == null || value.isBlank() || "{}".equals(value)) {
      return "{}";
    }
    try {
      return json.writeValueAsString(json.readTree(value));
    } catch (Exception e) {
      return value;
    }
  }

  /** jsonb 列取字符串（标题 / 描述是文本；要点 / 图片 / A+ 是数组，取其 JSON 原文）。 */
  private String jsonAsText(Object raw) {
    if (raw == null) {
      return "";
    }
    String value = raw instanceof org.jooq.JSONB j ? j.data() : String.valueOf(raw);
    return value == null ? "" : value;
  }

  private static <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 🔴 逐维度能力声明。
   *
   * <p>本模块在本仓能**真实交付**的是「内容通道的回写编排 + 前后快照 + 经队列派发 + 变更历史」：
   * 建回写、逐字段算改前改后、按平台限流派发、回报结果、只重试失败项、变更历史查询。
   *
   * <p>它<b>不能</b>在本批真实交付的有三段，逐条在下面声明而不是假装具备：实际调平台 API
   * （02 平台适配器 / 4.3.2.2）、上游 3 商品主数据变更的变更事件（跨服务）、以及描述与 A+
   * 的内部基线（4.2.1.1 刊登编辑器未交付）。
   */
  public ContentWritebackCapabilities capabilities() {
    return new ContentWritebackCapabilities(
        List.of(
            new ContentWritebackCapabilityDimension(
                "content_change_types",
                "🔴 五个内容字段：TITLE 标题 / BULLETS 卖点 / IMAGES 图片 / DESCRIPTION 描述 / A_PLUS A+。"
                    + "**取值域里没有 PRICE / STOCK** —— 那是 05 定价 / 07 库存两条独立通道",
                true, "ContentWritebackModels.CHANGE_TYPES + V187 的 CHECK", "",
                "含价格 / 库存的请求会被逐个点名 422 拒绝，不静默忽略 —— 静默忽略会让用户"
                    + "以为价格也推上去了，而它根本没有"),
            new ContentWritebackCapabilityDimension(
                "trigger_by_type_or_batch",
                "🔴 按变更类型触发（只推标题）/ 批量触发（一次推多条 listing 的集合）",
                true, "listing_content_writeback.change_types + listing_ids（V187）", "",
                "change_types 与 listing_ids 都是提交那一刻的口径快照，批量跑到一半改了"
                    + "筛选也不影响已入队的任务"),
            new ContentWritebackCapabilityDimension(
                "before_after_snapshot",
                "🔴 逐字段「改前 vs 改后」：改前来自平台侧当前快照（V175），改后来自内部草稿"
                    + "（V177）或显式覆盖值；两侧来源逐行记下",
                true, "listing_content_writeback_change.before_source / after_source（V187）", "",
                "「内部这次改了但线上还没变」与「平台上被人手改过」两种情况因此分得开 —— "
                    + "它们都会显示成 DIFFERENT，但含义完全不同"),
            new ContentWritebackCapabilityDimension(
                "queue_via_02",
                "🔴 回写任务经 02 队列（V2 platform_operation）派发：外键 + CHECK 双保险，"
                    + "在途与终态的任务不可能没有队列行",
                true, "listing_content_writeback_task.operation_id → platform_operation（V2）", "",
                "2.5.1.1 的回写任务列表里能按 operationId 查到本模块推的任务，"
                    + "「经队列」是可查事实而不是描述"),
            new ContentWritebackCapabilityDimension(
                "independent_channels",
                "🔴 内容 / 价格 / 库存三条通道独立失败独立重试：重试只打回本模块自己的 FAILED "
                    + "任务，且只复用 operation='PUBLISH' 的队列行，碰不到 UPDATE_PRICE / UPDATE_STOCK",
                true, "guard_content_writeback_terminal（V187）+ V2 platform_operation.operation 取值域", "",
                "已成功的回写**打不回**队列（触发器 23514）：重跑已成功的等于在平台上"
                    + "重复改一轮内容，而内容与线上已一致时连「有没有真的改」都答不上来"),
            new ContentWritebackCapabilityDimension(
                "change_history",
                "🔴 变更历史：任务维度与商品维度两种取法；历史不可就地改写（只能追加新的一次触发）",
                true, "listing_content_writeback_change + guard_content_writeback_change_append_only（V187）", "",
                "「当时推了什么、改前是什么」是事后追责与申诉的凭据，改写它比查不到更难发现"),
            new ContentWritebackCapabilityDimension(
                "rate_limit",
                "🔴 按平台（或店铺+平台）各自的 API 配额排队；缺配置的平台不派发（fail-closed）",
                true, "publish_rate_limit（V183，与 4.3.1.2 共用配置、各记各的账）", "",
                "内容回写与批量发布是两条通道，但**限流配置是同一份真实配额**，"
                    + "两张任务表的在途与窗口计数彼此独立，所以互不占额度也不会互相顶满"),
            new ContentWritebackCapabilityDimension(
                "platform_execution",
                "🔴 实际调平台 API（改标题 / 改图 / 改描述 / 改 A+ 的真实往返）",
                false, "—", "02 平台适配器（backend/services/channel/vendor/*） + 4.3.2.2",
                "本模块只负责**编排、限流、快照与记账**；派发后的任务停在 DISPATCHED（在途），"
                    + "由 4.3.2.2 / 适配器回报成功失败，不在本模块伪造平台返回"),
            new ContentWritebackCapabilityDimension(
                "upstream_change_events",
                "🔴 由上游 3 商品主数据变更的变更事件自动触发回写",
                false, "—", "03 商品主数据（catalog）",
                "AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图，本模块**读不到** catalog 的"
                    + "变更事件，因此不假装能自动感知；本批的真实入口是用户显式触发的"
                    + "「按变更类型 / 批量触发」，上游事件接入需 catalog 侧提供授权端点"),
            new ContentWritebackCapabilityDimension(
                "internal_description_baseline",
                "🔴 描述与 A+ 的**内部**待推送内容（不需要调用方每次显式给值）",
                false, "—", "4.2.1.1 刊登编辑器",
                "V177 的内容草稿只有 title / bullets / images / category_path / platform_category_id，"
                    + "**没有 description 与 a_plus**。所以这两段的改后值只能来自调用方的显式覆盖值；"
                    + "既无草稿又无覆盖值的那条任务判 SKIPPED_NO_SOURCE 并写明归属，**不编一个假值**"),
            new ContentWritebackCapabilityDimension(
                "category_writeback",
                "🔴 类目回写（category_path / platform_category_id）",
                false, "—", "4.3.1.2 批量发布（草稿转 PUBLISHED 时一并上线）",
                "职责句列的是「改标题、改图、改描述、改 A+」四件事，类目不在其中；"
                    + "而且草稿里已经带着类目，由发布流程统一上线更安全（V177 的"
                    + "guard_listing_live_content 只放开发布流程写线上）。"
                    + "因此带 CATEGORY 的请求会被点名拒绝，而不是悄悄忽略")),
        CHANGE_TYPES,
        REJECTED_CHANGE_TYPES,
        List.of(
            new ContentWritebackChannel(
                "CONTENT", "内容回写（本模块）", "4.3.2.1", true,
                "标题 / 卖点 / 图片 / 描述 / A+；本模块的 retry 只打回自己这些 FAILED 任务"),
            new ContentWritebackChannel(
                "PRICE", "价格回写", "05 定价", false,
                "改价走 UPDATE_PRICE 的队列任务；本模块的表里没有价格列，改价请求被 422 拒绝"),
            new ContentWritebackChannel(
                "STOCK", "库存回写", "07 库存", false,
                "改库存走 UPDATE_STOCK 的队列任务；本模块的表里没有库存列，改库存请求被 422 拒绝")),
        List.of(RUNNING, COMPLETED, CANCELLED),
        List.of(PENDING, SKIPPED_NO_CHANGE, DISPATCHED, SUCCEEDED, FAILED),
        "改前值来自**平台侧当前内容快照**（V175 listing_content_snapshot，同 listing + language + "
            + "is_current），改后值来自**内部待发布内容草稿**（V177 listing_content_draft）或调用方"
            + "显式给的覆盖值。两侧来源逐行记在 before_source / after_source 上："
            + "「没有平台侧快照」记 MISSING 而不是拿改后值当改前值 —— "
            + "那会让「线上是什么样」永远答不上来。改后与线上逐字相同的那条判 NO_CHANGE 且**不派发**："
            + "对平台的写调用是真实的外部副作用，无意义的写不该发生，而谎报「已推送」比不推送更难发现。",
        "上游 4.2.1.2 内容草稿（listing_content_draft，channel 同库）+ 4.1.1.2 平台侧内容快照"
            + "（listing_content_snapshot）+ 3 商品主数据变更（catalog，**跨服务，本批未接入**）；"
            + "下游 2.5.1.1 回写任务列表（按 operationId 可查到本模块经队列推的任务）。"
            + "本模块**无任何金额列**：改价归 05 定价，与 4.1.1.1 / 4.2.1.2 同一纪律。");
  }

  // ==================================================================
  // 🔴🔴 建一次回写（按变更类型 / 批量触发）
  // ==================================================================

  /**
   * 🔴🔴 建一次内容回写。
   *
   * <p>逐条不变量：
   *
   * <ol>
   *   <li><b>拒绝价格与库存</b>：请求里的变更类型逐个对照 {@link #CHANGE_TYPES}，
   *       不在其中且在 {@link #REJECTED_CHANGE_TYPES} 里的<b>逐个点名 422</b>。
   *       不在白名单也不在拒绝名单里的（比如拼错的 {@code TITEL}）同样拒 —— 猜一个最像的
   *       去执行，比报错危险得多。
   *   <li><b>目标集合冻结</b>：{@code listingIds} 或按筛选条件挑出来的集合，都写进
   *       {@code listing_ids} 快照（同时是 data_scope 能成立的前提，见 V187）。
   *   <li><b>逐字段算改前改后</b>：改前取平台侧当前快照，改后取内部草稿 / 显式覆盖值；
   *       两侧都没有 ⇒ SKIPPED_NO_SOURCE（不编值）。
   *   <li><b>整条都没有真改动 ⇒ 不建任务</b>：全部字段都是 NO_CHANGE 或 SKIPPED_NO_SOURCE 的
   *       listing 记成 {@code SKIPPED_NO_CHANGE} 任务（进批次计数、逐条可见），
   *       但<b>永不派发</b>。
   * </ol>
   */
  public ContentWritebackRow create(CreateContentWritebackRequest request, UUID actorId) {
    UUID tenant = currentTenant();

    List<String> types = normalizeTypes(request.changeTypes());
    Map<String, String> overrides = normalizeOverrides(request.overrides());

    int limit = request.limit() == null ? 50 : request.limit();
    if (limit < 1 || limit > 500) {
      throw unprocessable("数量上限必须在 1~500 之间");
    }

    // ---- 目标集合：显式给 id 就用它，否则按筛选条件挑（形状照抄 4.1.1.1）
    List<Record> targets = resolveTargets(tenant, request, limit);
    if (targets.isEmpty()) {
      throw unprocessable("目标集合为空：请检查 listing 筛选条件，或确认这些 listing 在你的数据范围内");
    }

    UUID writebackId = Ids.next();
    UUID[] listingIds = targets.stream().map(r -> r.get("id", UUID.class)).toArray(UUID[]::new);
    // 语言：请求给的就用它；不给则**逐条**取该 listing 的当前快照语言（内容按语言分别保存）
    String requestLanguage = blankToNull(request.language());
    sql.execute(
        """
        insert into listing_content_writeback
          (id,tenant_id,trigger_kind,change_types,listing_ids,overrides,language,state,total_count,
           pending_count,skipped_count,dispatched_count,succeeded_count,failed_count,
           actor_id,note,created_at,updated_at)
        values (?,?,?,?,?::uuid[],?::jsonb,'','RUNNING',0,0,0,0,0,0,?,?,now(),now())
        """,
        writebackId, tenant, targets.size() == 1 ? "SINGLE" : "BULK_EVENT",
        types.toArray(new String[0]), listingIds, writeJson(overrides), actorId,
        note(request.note()));

    // ---- 逐条建任务 + 逐字段落改前改后
    int created = 0;
    for (Record t : targets) {
      UUID listingId = t.get("id", UUID.class);
      String language = requestLanguage != null ? requestLanguage : snapshotLanguage(tenant, listingId);
      UUID snapshotId = language.isEmpty() ? null : currentSnapshotId(tenant, listingId, language);
      Record draft = currentDraft(tenant, listingId);
      UUID draftId = draft == null ? null : draft.get("id", UUID.class);

      List<PendingChange> changes = computeChanges(types, overrides, language, snapshotId, draft);

      // 全都没有真改动 ⇒ 这条永不派发，但仍留痕（批次计数里能看到"为什么少了一条"）
      boolean anyApplied = changes.stream().anyMatch(c -> "APPLIED".equals(c.outcome()));
      String state = anyApplied ? PENDING : SKIPPED_NO_CHANGE;
      String reason =
          anyApplied
              ? ""
              : changes.stream()
                  .filter(c -> "SKIPPED_NO_SOURCE".equals(c.outcome()))
                  .map(c -> c.field() + "：内部没有该字段的待推送内容")
                  .findFirst()
                  .orElse("改后内容与平台侧当前内容逐字相同，未产生真实变更");

      UUID taskId = Ids.next();
      sql.execute(
          """
          insert into listing_content_writeback_task
            (id,tenant_id,writeback_id,listing_id,platform_id,shop_id,language,draft_id,snapshot_id,
             change_types,state,attempts,operation_id,failure_reason,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,?,0,null,?,now(),now())
          """,
          taskId, tenant, writebackId, listingId, t.get("platform_id", UUID.class),
          t.get("shop_id", UUID.class), language, draftId, snapshotId,
          types.toArray(new String[0]), state, reason);

      for (PendingChange c : changes) {
        sql.execute(
            """
            insert into listing_content_writeback_change
              (id,tenant_id,task_id,listing_id,field,before_value,after_value,changed,outcome,
               before_source,after_source,reason,created_at)
            values (?,?,?,?,?,?,?,?,?,?,?,?,now())
            """,
            Ids.next(), tenant, taskId, listingId, c.field(), c.beforeValue(), c.afterValue(),
            "APPLIED".equals(c.outcome()), c.outcome(), c.beforeSource(), c.afterSource(), c.reason());
      }
      created++;
    }

    refreshProgress(tenant, writebackId);
    return loadWriteback(tenant, writebackId);
  }

  /** 变更类型归一 + 🔴 拒绝价格 / 库存 / 类目等不属于内容通道的类型。 */
  private List<String> normalizeTypes(List<String> raw) {
    if (safe(raw).isEmpty()) {
      throw unprocessable("请至少指定一个变更类型（" + String.join(" / ", CHANGE_TYPES) + "）");
    }
    List<String> normalized = new ArrayList<>();
    List<String> rejected = new ArrayList<>();
    for (String value : raw) {
      String type = text(value).toUpperCase(Locale.ROOT);
      if (CHANGE_TYPES.contains(type)) {
        if (!normalized.contains(type)) {
          normalized.add(type);
        }
      } else {
        rejected.add(type.isEmpty() ? "(空)" : type);
      }
    }
    if (!rejected.isEmpty()) {
      // 🔴 逐个点名，不静默忽略。价格 / 库存归属 05 / 07；类目由 4.3.1.2 发布流程一并上线。
      throw unprocessable(
          "内容回写通道不处理：" + String.join("、", rejected)
              + "。可回写的字段只有 " + String.join(" / ", CHANGE_TYPES)
              + "；改价归 05 定价、改库存归 07 库存（它们是三条独立通道，"
              + "独立失败独立重试）；类目由 4.3.1.2 发布流程随草稿一并上线。");
    }
    return normalized;
  }

  /** 覆盖值归一：**只接受内容通道的键**，其余逐个点名拒绝（DB 的 CHECK 也会再挡一道）。 */
  private Map<String, String> normalizeOverrides(Map<String, String> raw) {
    Map<String, String> out = new LinkedHashMap<>();
    if (raw == null) {
      return out;
    }
    List<String> rejected = new ArrayList<>();
    for (Map.Entry<String, String> e : raw.entrySet()) {
      String key = text(e.getKey()).toUpperCase(Locale.ROOT);
      if (CHANGE_TYPES.contains(key)) {
        out.put(key, e.getValue() == null ? "" : e.getValue());
      } else {
        rejected.add(key.isEmpty() ? "(空)" : key);
      }
    }
    if (!rejected.isEmpty()) {
      throw unprocessable(
          "内容回写通道不接受这些覆盖字段：" + String.join("、", rejected)
              + "。可覆盖的字段只有 " + String.join(" / ", CHANGE_TYPES)
              + "；价格归 05 定价、库存归 07 库存，不在本通道。");
    }
    return out;
  }

  /** 内部：一条待落库的字段变更。 */
  private record PendingChange(
      String field,
      String beforeValue,
      String afterValue,
      String outcome,
      String beforeSource,
      String afterSource,
      String reason) {}

  /**
   * 🔴 逐字段算「改前 vs 改后」。
   *
   * <p>改前只来自**平台侧当前快照**（{@code PLATFORM_SNAPSHOT}）；没有快照就是
   * {@code MISSING} + 空串 —— <b>绝不拿改后值当改前值</b>，那会让「线上是什么样」永远答不上来。
   * 改后优先用调用方显式覆盖值（{@code OVERRIDE}），其次内部草稿（{@code INTERNAL_DRAFT}），
   * 都没有就是 {@code SKIPPED_NO_SOURCE} + 写明归属，不编值。
   */
  private List<PendingChange> computeChanges(
      List<String> types,
      Map<String, String> overrides,
      String language,
      UUID snapshotId,
      Record draft) {
    Record snapshot =
        snapshotId == null
            ? null
            : sql.fetchOne(
                "select title,description,bullet_points,images,a_plus_modules "
                    + "from listing_content_snapshot where tenant_id=? and id=?",
                currentTenant(), snapshotId);

    List<PendingChange> out = new ArrayList<>();
    for (String type : types) {
      String before = snapshot == null ? "" : platformValue(snapshot, type);
      String beforeSource = snapshot == null ? "MISSING" : "PLATFORM_SNAPSHOT";

      String override = overrides.get(type);
      String after;
      String afterSource;
      String reason;
      if (override != null) {
        after = override;
        afterSource = "OVERRIDE";
        reason = "";
      } else {
        String drafted = draft == null ? "" : draftValue(draft, type);
        if (drafted.isEmpty()) {
          out.add(new PendingChange(type, before, "", "SKIPPED_NO_SOURCE", beforeSource,
              "MISSING",
              "内部草稿没有该字段的待推送内容，且本次未给显式覆盖值（描述与 A+ 的内部草稿"
                  + "属 4.2.1.1 刊登编辑器，尚未交付）"));
          continue;
        }
        after = drafted;
        afterSource = "INTERNAL_DRAFT";
        reason = "";
      }

      if (beforeSource.equals("MISSING")) {
        // 没有平台侧基线：仍然推（改后内容是真的），但明确记下改前不可知
        out.add(new PendingChange(type, "", after, "APPLIED", beforeSource, afterSource,
            "平台侧没有该语言的当前内容快照，改前值不可知（不会拿改后值冒充改前值）"));
      } else if (before.equals(after)) {
        out.add(new PendingChange(type, before, after, "NO_CHANGE", beforeSource, afterSource,
            "改后内容与平台侧当前内容逐字相同，未产生真实变更（不派发）"));
      } else {
        out.add(new PendingChange(type, before, after, "APPLIED", beforeSource, afterSource, reason));
      }
    }
    return out;
  }

  /** 🔴 平台侧快照里某个字段的原文（数组型字段统一规范化成紧凑 JSON，两侧比较才是同一口径）。 */
  private String platformValue(Record snapshot, String type) {
    return switch (type) {
      case "TITLE" -> normalizeJsonText(nullToEmpty(snapshot.get("title", String.class)));
      case "DESCRIPTION" -> normalizeJsonText(nullToEmpty(snapshot.get("description", String.class)));
      case "BULLETS" -> normalizeJsonText(jsonAsText(snapshot.get("bullet_points")));
      case "IMAGES" -> normalizeJsonText(jsonAsText(snapshot.get("images")));
      case "A_PLUS" -> normalizeJsonText(jsonAsText(snapshot.get("a_plus_modules")));
      default -> "";
    };
  }

  /**
   * 内部草稿里某个字段的原文。
   *
   * <p>🔴 诚实的边界：V177 的草稿<b>没有</b> description 与 a_plus 列（那是 4.2.1.1 刊登编辑器的
   * 内容域，而它尚未交付）。这里返回空串 ⇒ {@link #computeChanges} 判 {@code SKIPPED_NO_SOURCE}
   * 并写明归属，<b>不编一个假值</b>。这两段的改后值只能来自调用方的显式覆盖值。
   */
  private String draftValue(Record draft, String type) {
    return switch (type) {
      case "TITLE" -> normalizeJsonText(nullToEmpty(draft.get("title", String.class)));
      case "BULLETS" -> normalizeJsonText(arrayToText(draft.get("bullets")));
      case "IMAGES" -> normalizeJsonText(jsonAsText(draft.get("images")));
      default -> "";
    };
  }

  /**
   * 把一段文本规范化成**可逐字比较**的形态。
   *
   * <p>🔴 为什么必须规范化：改前值来自 jsonb（PostgreSQL 会带空格，如 {@code ["a", "b"]}），
   * 改后值来自 {@code text[]} / 显式覆盖值。若不统一形态，「改后与线上逐字相同」这条判定
   * 会在**内容完全一致**时误判成"有变更" —— 于是每次回写都产生一次真实但无意义的平台写调用，
   * 而运营看到的还是「已推送」。解析失败时**原样返回**：宁可判不了相同，也不要把不同判成相同。
   */
  private String normalizeJsonText(String raw) {
    if (raw == null) {
      return "";
    }
    String value = raw.trim();
    if (!value.startsWith("[") && !value.startsWith("{")) {
      return value;
    }
    try {
      return json.writeValueAsString(json.readTree(value));
    } catch (Exception e) {
      return value;
    }
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  /** text[] 列转成紧凑 JSON 数组文本（与 jsonb 侧同一形态）。 */
  private String arrayToText(Object raw) {
    if (raw == null) {
      return "";
    }
    if (raw instanceof String[] arr) {
      return writeJson(List.of(arr));
    }
    if (raw instanceof List<?> list) {
      return writeJson(list);
    }
    return String.valueOf(raw);
  }

  // ==================================================================
  // 目标集合 / 草稿 / 快照
  // ==================================================================

  private List<Record> resolveTargets(UUID tenant, CreateContentWritebackRequest request, int limit) {
    StringBuilder sqlText =
        new StringBuilder(
            """
            select l.id, l.shop_id, s.platform_id, l.external_item_id
            from listing l
            join shop s on s.tenant_id=l.tenant_id and s.id=l.shop_id
            where l.tenant_id=?
            """);
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);

    // 🔴 显式给的 id 逐个解析成 UUID 再逐个绑定（jOOQ 会把它们绑成 PG 的 uuid 类型）：
    //   拼成字符串数组再 `?::uuid[]` 既会踩 PG 的数组字面量格式，也等于把用户输入拼进 SQL。
    List<UUID> explicit = new ArrayList<>();
    for (UUID raw : safe(request.listingIds())) {
      if (raw == null) {
        continue;
      }
      try {
        explicit.add(UUID.fromString(raw.toString()));
      } catch (RuntimeException e) {
        throw unprocessable("listingIds 含格式不正确的 id：" + raw);
      }
    }
    if (!explicit.isEmpty()) {
      sqlText.append(" and l.id in (");
      for (int i = 0; i < explicit.size(); i++) {
        sqlText.append(i == 0 ? "?" : ",?");
        binds.add(explicit.get(i));
      }
      sqlText.append(')');
    }
    String shopCode = blankToNull(request.shopCode());
    if (shopCode != null) {
      sqlText.append(" and s.code=?");
      binds.add(shopCode);
    }
    String status = blankToNull(request.status());
    if (status != null) {
      sqlText.append(" and l.status=?");
      binds.add(status.toUpperCase(Locale.ROOT));
    }
    sqlText.append(" order by l.updated_at desc, l.id limit ?");
    binds.add(limit);
    return sql.fetch(sqlText.toString(), binds.toArray());
  }

  /** 当前待发布草稿（4.2.1.2 的产出）：同 listing 至多一份 DRAFT（部分唯一索引已保证）。 */
  private Record currentDraft(UUID tenant, UUID listingId) {
    return sql.fetchOne(
        "select id,title,bullets,images from listing_content_draft "
            + "where tenant_id=? and listing_id=? and state='DRAFT'",
        tenant, listingId);
  }

  /** 该 listing 的当前快照语言；没有当前快照时返回空串（**不猜一个默认语言**）。 */
  private String snapshotLanguage(UUID tenant, UUID listingId) {
    Object raw =
        sql.fetchValue(
            "select language from listing_content_snapshot "
                + "where tenant_id=? and listing_id=? and is_current order by captured_at desc limit 1",
            tenant, listingId);
    return raw == null ? "" : String.valueOf(raw);
  }

  private UUID currentSnapshotId(UUID tenant, UUID listingId, String language) {
    Object raw =
        sql.fetchValue(
            "select id from listing_content_snapshot "
                + "where tenant_id=? and listing_id=? and language=? and is_current",
            tenant, listingId, language);
    return raw == null ? null : UUID.fromString(String.valueOf(raw));
  }

  // ==================================================================
  // 视图
  // ==================================================================

  public List<ContentWritebackRow> writebacks(String state, int page, int size) {
    UUID tenant = currentTenant();
    StringBuilder sqlText = new StringBuilder("select id from listing_content_writeback where tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    String s = blankToNull(state);
    if (s != null) {
      sqlText.append(" and state=?");
      binds.add(s.toUpperCase(Locale.ROOT));
    }
    sqlText.append(" order by created_at desc, id limit ? offset ?");
    int lim = clampSize(size);
    binds.add(lim);
    binds.add(Math.max(0, page) * lim);
    return sql.fetch(sqlText.toString(), binds.toArray()).stream()
        .map(r -> loadWriteback(tenant, r.get("id", UUID.class)))
        .toList();
  }

  public ContentWritebackRow loadWritebackRow(String writebackId) {
    return loadWriteback(currentTenant(), parseId(writebackId));
  }

  private ContentWritebackRow loadWriteback(UUID tenant, UUID writebackId) {
    Record r =
        sql.fetchOne(
            "select id,trigger_kind,change_types,listing_ids,overrides,language,state,"
                + "total_count,pending_count,skipped_count,dispatched_count,succeeded_count,"
                + "failed_count,actor_id,note,created_at,updated_at,completed_at "
                + "from listing_content_writeback where tenant_id=? and id=?",
            tenant, writebackId);
    if (r == null) {
      throw notFound();
    }
    return writebackRow(r);
  }

  private ContentWritebackRow writebackRow(Record r) {
    int total = r.get("total_count", Integer.class);
    int pending = r.get("pending_count", Integer.class);
    int skipped = r.get("skipped_count", Integer.class);
    int dispatched = r.get("dispatched_count", Integer.class);
    int succeeded = r.get("succeeded_count", Integer.class);
    int failed = r.get("failed_count", Integer.class);
    // 进度按"已终态 / 总数"；SKIPPED_NO_CHANGE 也是终态（它不需要派发）
    int terminal = skipped + succeeded + failed;
    int percent = total == 0 ? 0 : (int) Math.round(100.0 * terminal / total);
    return new ContentWritebackRow(
        r.get("id", UUID.class),
        r.get("trigger_kind", String.class),
        stringArray(r.get("change_types")),
        uuidArray(r.get("listing_ids")),
        jsonText(r.get("overrides", Object.class)),
        r.get("language", String.class),
        r.get("state", String.class),
        total, pending, skipped, dispatched, succeeded, failed, percent,
        r.get("actor_id", UUID.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class));
  }

  /** 批次任务列表（失败明细用 {@code state=FAILED} 取）。 */
  public List<ContentWritebackTaskRow> tasks(String writebackId, String taskState, int page, int size) {
    UUID tenant = currentTenant();
    UUID id = parseId(writebackId);
    loadWriteback(tenant, id); // 不属于本租户 / 不存在一律 404（RLS 先把行藏起来）
    StringBuilder sqlText =
        new StringBuilder(TASK_SELECT + " where t.tenant_id=? and t.writeback_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    binds.add(id);
    String s = blankToNull(taskState);
    if (s != null) {
      sqlText.append(" and t.state=?");
      binds.add(s.toUpperCase(Locale.ROOT));
    }
    sqlText.append(" order by t.created_at, t.id limit ? offset ?");
    int lim = clampSize(size);
    binds.add(lim);
    binds.add(Math.max(0, page) * lim);
    return sql.fetch(sqlText.toString(), binds.toArray()).stream().map(this::taskRow).toList();
  }

  private static final String TASK_SELECT =
      """
      select t.id,t.writeback_id,t.listing_id,l.external_item_id,l.title,s.code as shop_code,
             p.code as platform_code,t.language,t.draft_id,t.snapshot_id,t.change_types,t.state,
             t.attempts,t.operation_id,t.failure_reason,t.dispatched_at,t.completed_at
      from listing_content_writeback_task t
      join listing l on l.tenant_id=t.tenant_id and l.id=t.listing_id
      join shop s on s.tenant_id=t.tenant_id and s.id=t.shop_id
      join platform p on p.id=t.platform_id
      """;

  private ContentWritebackTaskRow taskRow(Record r) {
    return new ContentWritebackTaskRow(
        r.get("id", UUID.class),
        r.get("writeback_id", UUID.class),
        r.get("listing_id", UUID.class),
        r.get("external_item_id", String.class),
        r.get("title", String.class),
        r.get("shop_code", String.class),
        r.get("platform_code", String.class),
        r.get("language", String.class),
        stringArray(r.get("change_types")),
        r.get("draft_id", UUID.class),
        r.get("snapshot_id", UUID.class),
        r.get("state", String.class),
        r.get("attempts", Integer.class),
        r.get("operation_id", UUID.class),
        nullToEmpty(r.get("failure_reason", String.class)),
        r.get("dispatched_at", OffsetDateTime.class),
        r.get("completed_at", OffsetDateTime.class));
  }

  private ContentWritebackTaskRow loadTaskRow(UUID tenant, UUID taskId) {
    Record r = sql.fetchOne(TASK_SELECT + " where t.tenant_id=? and t.id=?", tenant, taskId);
    if (r == null) {
      throw notFound();
    }
    return taskRow(r);
  }

  /** 一条任务的逐字段改前 / 改后（「记录回写前后快照」）。 */
  public List<ContentWritebackChangeRow> changes(String taskId) {
    UUID tenant = currentTenant();
    UUID id = parseId(taskId);
    loadTaskRow(tenant, id);
    return sql.fetch(
            CHANGE_SELECT + " where c.tenant_id=? and c.task_id=? order by c.field",
            tenant, id)
        .stream()
        .map(this::changeRow)
        .toList();
  }

  private static final String CHANGE_SELECT =
      "select c.id,c.task_id,c.listing_id,c.field,c.before_value,c.after_value,c.changed,"
          + "c.outcome,c.before_source,c.after_source,c.reason from listing_content_writeback_change c";

  private ContentWritebackChangeRow changeRow(Record r) {
    return new ContentWritebackChangeRow(
        r.get("id", UUID.class),
        r.get("task_id", UUID.class),
        r.get("listing_id", UUID.class),
        r.get("field", String.class),
        nullToEmpty(r.get("before_value", String.class)),
        nullToEmpty(r.get("after_value", String.class)),
        Boolean.TRUE.equals(r.get("changed", Boolean.class)),
        r.get("outcome", String.class),
        r.get("before_source", String.class),
        r.get("after_source", String.class),
        r.get("reason", String.class));
  }

  /**
   * 某个 listing 的内容回写变更历史（按时间倒序，一次触发带它全部的字段变更）。
   *
   * <p>与 V175 的 {@code /listings/{id}/publish-history}（发布动作留痕）互补而不重复：
   * 那边记「发布动作本身发生了什么」，这边记「这次推了哪些字段、改前是什么、改后是什么」。
   */
  public List<ListingContentHistoryEntry> listingHistory(String listingId) {
    UUID tenant = currentTenant();
    UUID id = parseId(listingId);
    if (sql.fetchOne("select 1 from listing where tenant_id=? and id=?", tenant, id) == null) {
      throw notFound();
    }
    List<Record> tasks =
        sql.fetch(
            "select id,writeback_id,state,operation_id,created_at from listing_content_writeback_task "
                + "where tenant_id=? and listing_id=? order by created_at desc, id desc",
            tenant, id);
    List<ListingContentHistoryEntry> out = new ArrayList<>();
    for (Record t : tasks) {
      UUID writebackId = t.get("writeback_id", UUID.class);
      Record w =
          sql.fetchOne("select state,created_at from listing_content_writeback where tenant_id=? and id=?",
              tenant, writebackId);
      if (w == null) {
        continue;
      }
      List<ContentWritebackChangeRow> rows =
          sql.fetch(CHANGE_SELECT + " where c.tenant_id=? and c.task_id=? order by c.field",
                  tenant, t.get("id", UUID.class))
              .stream()
              .map(this::changeRow)
              .toList();
      out.add(
          new ListingContentHistoryEntry(
              writebackId,
              w.get("state", String.class),
              w.get("created_at", OffsetDateTime.class),
              t.get("state", String.class),
              t.get("operation_id", UUID.class),
              rows));
    }
    return out;
  }

  // ==================================================================
  // 🔴 派发：按平台限流排队 + 经 02 队列
  // ==================================================================

  /**
   * 🔴🔴 一轮派发：按平台（或店铺+平台）各自的配额排队，并把每条派发出去的写成
   * **02 队列任务**（V2 {@code platform_operation}）。
   *
   * <p>算法与 4.3.1.2 同构，但两条通道各记各的账：本方法统计在途 / 窗口时只数
   * {@code listing_content_writeback_task}，所以内容回写顶满自己的额度**不会**顶掉
   * 批量发布的额度，反之亦然 —— 这就是「三条通道独立失败独立重试」在限流维度上的落点。
   *
   * <p>幂等键由服务层从「任务 id + 尝试轮次」导出，<b>不接受调用方自拟</b>（照抄 V169 第五节）：
   * 调用方自拟就能两次「不同键、同一个目标」，恰好绕开幂等。重试时**复用同一个队列任务**
   * 而不是再插一条 —— 重试是同一个业务动作，平台侧不该看到两个任务。
   */
  public ContentWritebackDispatchResponse dispatch(String writebackId) {
    UUID tenant = currentTenant();
    UUID id = parseId(writebackId);
    ContentWritebackRow writeback = loadWriteback(tenant, id);

    if (!RUNNING.equals(writeback.state())) {
      // COMPLETED / CANCELLED：一律不派发（已发出的平台写请求撤不回，在途仍可回报）
      return new ContentWritebackDispatchResponse(
          id, writeback.state(), 0, 0, List.of(), List.of(), writeback);
    }

    List<Record> pending =
        sql.fetch(
            "select id,listing_id,platform_id,shop_id,attempts,operation_id,change_types,language "
                + "from listing_content_writeback_task where tenant_id=? and writeback_id=? "
                + "and state='PENDING' order by created_at, id",
            tenant, id);

    Map<String, List<Record>> byLane = new LinkedHashMap<>();
    Map<String, RateLane> lanes = new LinkedHashMap<>();
    for (Record t : pending) {
      UUID platformId = t.get("platform_id", UUID.class);
      UUID shopId = t.get("shop_id", UUID.class);
      RateLane lane = resolveLane(tenant, platformId, shopId);
      byLane.computeIfAbsent(laneKey(platformId, shopId), k -> new ArrayList<>()).add(t);
      lanes.putIfAbsent(laneKey(platformId, shopId), lane);
    }

    List<ContentWritebackLaneReport> reports = new ArrayList<>();
    List<UUID> queued = new ArrayList<>();
    int totalDispatched = 0;
    int totalSkipped = 0;
    OffsetDateTime now = OffsetDateTime.now();

    for (Map.Entry<String, List<Record>> entry : byLane.entrySet()) {
      RateLane lane = lanes.get(entry.getKey());
      List<Record> group = entry.getValue();
      String platformCode = platformCodeOf(lane.platformId());
      String shopCode = lane.shopId() == null ? null : shopCodeOf(tenant, lane.shopId());

      if (!lane.enabled()) {
        int skipped = group.size();
        totalSkipped += skipped;
        reports.add(
            new ContentWritebackLaneReport(
                platformCode, shopCode, lane.id(), lane.maxInFlight(), 0, lane.requestsPerWindow(),
                lane.windowSeconds(), 0, 0, skipped, lane.note()));
        continue;
      }

      long inFlightBefore = inFlightCount(tenant, lane.platformId(), lane.shopId());
      long windowBefore = windowCount(tenant, lane.platformId(), lane.shopId(), lane.windowSeconds());
      // 在途余量 与 窗口余量 取更紧的
      long byInflight = lane.maxInFlight() - inFlightBefore;
      long byWindow = lane.requestsPerWindow() - windowBefore;
      long allowed = Math.max(0, Math.min(byInflight, byWindow));

      int dispatched = 0;
      for (Record t : group) {
        if (dispatched >= allowed) {
          break;
        }
        queued.add(enqueue(tenant, t, now));
        dispatched++;
      }
      int skipped = group.size() - dispatched;
      totalDispatched += dispatched;
      totalSkipped += skipped;
      reports.add(
          new ContentWritebackLaneReport(
              platformCode, shopCode, lane.id(), lane.maxInFlight(), inFlightBefore,
              lane.requestsPerWindow(), lane.windowSeconds(), windowBefore, dispatched, skipped,
              skipped > 0
                  ? (allowed <= 0
                      ? "已达该平台配额上限，本轮整组不派（" + reasonFor(lane, byInflight, byWindow) + "）"
                      : "按次序派满配额后暂停")
                  : ""));
    }

    refreshProgress(tenant, id);
    return new ContentWritebackDispatchResponse(
        id, writeback.state(), totalDispatched, totalSkipped, queued, reports,
        loadWriteback(tenant, id));
  }

  /**
   * 🔴 写 02 队列任务（V2 {@code platform_operation}）并把任务推进到在途。
   *
   * <p>{@code operation='PUBLISH'}：内容回写与刊登发布走的是同一条平台写通道（都是"把内容
   * 推给平台"），所以复用既有取值域 —— <b>不改 V2 的 CHECK</b>，那是 2.5.1.1 的对外契约。
   * 方向靠 payload 里的 {@code contentWriteback=true} 区分，与 V169 把「批量下架」映射成
   * {@code PUBLISH + payload.delist=true} 同一手法。
   *
   * <p>重试时<b>复用</b>已存在的队列任务（同一个 operation_id、幂等键不变）：
   * 重试是同一个业务动作，平台侧不该看到两个任务。
   */
  private UUID enqueue(UUID tenant, Record task, OffsetDateTime now) {
    UUID taskId = task.get("id", UUID.class);
    // 🔴 队列行的 shop_id 必须是**任务自己的店铺**，不是限流行解析出来的那个
    //   （平台级限流行解析出的 shop_id 是 NULL，而 platform_operation.shop_id NOT NULL）。
    //   写错的话整条通道在"平台级限流"这一种最常见配置下直接插不进去。
    UUID shopId = task.get("shop_id", UUID.class);
    UUID existing = task.get("operation_id", UUID.class);
    UUID operationId;
    if (existing != null) {
      operationId = existing; // 重试：复用同一个队列任务，不新开一个
    } else {
      operationId = Ids.next();
      String idempotencyKey = "CB-" + taskId;
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("contentWriteback", true);
      payload.put("writebackTaskId", taskId.toString());
      payload.put("listingId", task.get("listing_id", UUID.class).toString());
      payload.put("language", nullToEmpty(task.get("language", String.class)));
      payload.put("changeTypes", stringArray(task.get("change_types")));
      sql.execute(
          """
          insert into platform_operation
            (id,tenant_id,shop_id,marketplace_id,operation,idempotency_key,payload,status,
             platform_request_id,verification,verified_at,attempts,last_error,occurred_at,updated_at)
          values (?,?,?,null,'PUBLISH',?,?::jsonb,'PENDING','','NOT_REQUIRED',null,1,'',?,?)
          """,
          operationId, tenant, shopId, idempotencyKey, writeJson(payload),
          Timestamp.from(now.toInstant()), Timestamp.from(now.toInstant()));
    }
    // 派发：state + attempts（触发器强制恰好 +1）+ 队列行；dispatched_at 保留上次派发时刻
    sql.execute(
        """
        update listing_content_writeback_task
        set state='DISPATCHED', attempts=attempts+1, operation_id=?, dispatched_at=?,
            completed_at=null, failure_reason=null, updated_at=now()
        where tenant_id=? and id=? and state='PENDING'
        """,
        operationId, Timestamp.from(now.toInstant()), tenant, taskId);
    return operationId;
  }

  private record RateLane(
      UUID id,
      UUID platformId,
      UUID shopId,
      int maxInFlight,
      int requestsPerWindow,
      int windowSeconds,
      boolean enabled,
      String note) {}

  /** 🔴 解析生效限流行：**店铺级优先，其次平台级，都没有 = null（fail-closed）**。 */
  private RateLane resolveLane(UUID tenant, UUID platformId, UUID shopId) {
    Record row =
        sql.fetchOne(
            "select id,platform_id,shop_id,max_in_flight,requests_per_window,window_seconds,enabled "
                + "from publish_rate_limit where tenant_id=? and platform_id=? and shop_id=?",
            tenant, platformId, shopId);
    if (row == null) {
      row =
          sql.fetchOne(
              "select id,platform_id,shop_id,max_in_flight,requests_per_window,window_seconds,enabled "
                  + "from publish_rate_limit where tenant_id=? and platform_id=? and shop_id is null",
              tenant, platformId);
    }
    if (row == null) {
      return new RateLane(null, platformId, shopId, 0, 0, 0, false, "该平台未配置限流参数");
    }
    boolean enabled = Boolean.TRUE.equals(row.get("enabled", Boolean.class));
    return new RateLane(
        row.get("id", UUID.class),
        platformId,
        row.get("shop_id", UUID.class),
        row.get("max_in_flight", Integer.class),
        row.get("requests_per_window", Integer.class),
        row.get("window_seconds", Integer.class),
        enabled,
        enabled ? "" : "该限流配置已停用");
  }

  /** 在途数（**只数本模块自己的任务**：内容通道与批量发布各记各的账）。 */
  private long inFlightCount(UUID tenant, UUID platformId, UUID shopId) {
    Record r =
        sql.fetchOne(
            "select count(*) as n from listing_content_writeback_task "
                + "where tenant_id=? and platform_id=? and state='DISPATCHED' "
                + "and (case when ?::uuid is null then true else shop_id=? end)",
            tenant, platformId, shopId, shopId);
    Long n = r == null ? null : r.get("n", Long.class);
    return n == null ? 0 : n;
  }

  private long windowCount(UUID tenant, UUID platformId, UUID shopId, int windowSeconds) {
    Record r =
        sql.fetchOne(
            "select count(*) as n from listing_content_writeback_task "
                + "where tenant_id=? and platform_id=? and state<>'PENDING' "
                + "and state<>'SKIPPED_NO_CHANGE' and dispatched_at is not null "
                + "and dispatched_at > now() - make_interval(secs => ?) "
                + "and (case when ?::uuid is null then true else shop_id=? end)",
            tenant, platformId, windowSeconds, shopId, shopId);
    Long n = r == null ? null : r.get("n", Long.class);
    return n == null ? 0 : n;
  }

  private static String laneKey(UUID platformId, UUID shopId) {
    return platformId + "/" + (shopId == null ? "-" : shopId);
  }

  private static String reasonFor(RateLane lane, long byInflight, long byWindow) {
    if (byInflight <= 0) {
      return "在途已达 " + lane.maxInFlight();
    }
    if (byWindow <= 0) {
      return "窗口内已派发达 " + lane.requestsPerWindow() + "/" + lane.windowSeconds() + "s";
    }
    return "配额";
  }

  private String platformCodeOf(UUID platformId) {
    Record r = sql.fetchOne("select code from platform where id=?", platformId);
    return r == null ? null : r.get("code", String.class);
  }

  private String shopCodeOf(UUID tenant, UUID shopId) {
    Record r = sql.fetchOne("select code from shop where tenant_id=? and id=?", tenant, shopId);
    return r == null ? null : r.get("code", String.class);
  }

  // ==================================================================
  // 🔴 重试（只重试失败项）/ 中止
  // ==================================================================

  /**
   * 🔴 批量重试：**只**把 FAILED 打回 PENDING。
   *
   * <p>已成功的绝不重跑 —— DB 触发器 {@code guard_content_writeback_terminal} 会拒掉任何把
   * SUCCEEDED 改走的语句，所以这条不变量不是「服务层没写那条 SQL」，而是**写不了**。
   * 在途（DISPATCHED）也不打回：必须先被回报结果。
   *
   * <p>🔴 重新入队时<b>保留 operation_id</b>（复用同一个 02 队列任务、幂等键不变）：
   * 重试是同一个业务动作，平台侧不该看到两个任务。派发时 {@code enqueue} 因此走"复用"分支。
   */
  public ContentWritebackActionResult retry(String writebackId, RetryContentWritebackRequest request) {
    UUID tenant = currentTenant();
    UUID id = parseId(writebackId);
    ContentWritebackRow writeback = loadWriteback(tenant, id);

    List<String> rejected = new ArrayList<>();
    List<UUID> changed = new ArrayList<>();
    List<UUID> targets;

    if (request == null || safe(request.taskIds()).isEmpty()) {
      targets =
          sql.fetch(
                  "select id from listing_content_writeback_task where tenant_id=? and writeback_id=? "
                      + "and state='FAILED' order by created_at,id",
                  tenant, id)
              .stream()
              .map(r -> r.get("id", UUID.class))
              .toList();
    } else {
      targets = new ArrayList<>();
      Map<UUID, String> stateOf = new LinkedHashMap<>();
      for (Record r :
          sql.fetch(
              "select id,state from listing_content_writeback_task where tenant_id=? and writeback_id=?",
              tenant, id)) {
        stateOf.put(r.get("id", UUID.class), r.get("state", String.class));
      }
      for (UUID tid : safe(request.taskIds())) {
        String st = stateOf.get(tid);
        if (st == null) {
          rejected.add(tid + ": 不属于本次回写");
        } else if (!FAILED.equals(st)) {
          // 关键：已成功的 / 在途的 / 跳过的，明确拒绝，不重跑
          rejected.add(tid + ": 状态为 " + st + "，只有 FAILED 可重试");
        } else {
          targets.add(tid);
        }
      }
    }

    for (UUID tid : targets) {
      sql.execute(
          """
          update listing_content_writeback_task
          set state='PENDING', completed_at=null, failure_reason=null, updated_at=now()
          where tenant_id=? and id=? and state='FAILED'
          """,
          tenant, tid);
      changed.add(tid);
    }
    if (!changed.isEmpty()) {
      // 重试后回到 RUNNING（之前 COMPLETED 了就重新开跑）
      sql.execute(
          "update listing_content_writeback set state='RUNNING', completed_at=null, updated_at=now() "
              + "where tenant_id=? and id=? and state='COMPLETED'",
          tenant, id);
    }
    refreshProgress(tenant, id);
    ContentWritebackRow after = loadWriteback(tenant, id);
    return new ContentWritebackActionResult(id, after.state(), changed, rejected, after);
  }

  /** 人工中止：不再派发剩余任务；已在途的仍可回报结果。 */
  public ContentWritebackRow cancel(String writebackId, ContentWritebackStateRequest request) {
    UUID tenant = currentTenant();
    UUID id = parseId(writebackId);
    ContentWritebackRow writeback = loadWriteback(tenant, id);
    if (COMPLETED.equals(writeback.state()) || CANCELLED.equals(writeback.state())) {
      throw unprocessable("本次回写已结束（" + writeback.state() + "），无法中止");
    }
    sql.execute(
        "update listing_content_writeback set state='CANCELLED', note=?, updated_at=now() "
            + "where tenant_id=? and id=?",
        note(request == null ? null : request.note()), tenant, id);
    return loadWriteback(tenant, id);
  }

  // ==================================================================
  // 在途任务回执（4.3.2.2 / 02 平台适配器回报；本模块不伪造平台返回）
  // ==================================================================

  /**
   * 回报一条在途（DISPATCHED）回写任务的结果：成功或失败。
   *
   * <p>成功时额外写一条 V175 的 {@code listing_publish_record(action='CONTENT_WRITEBACK')} ——
   * 那张表的 CHECK 里 {@code CONTENT_WRITEBACK} 这个取值就是为本模块预留的
   * （V175 文件头：「PUBLISH = 4.3.1.1 发布；CONTENT_WRITEBACK = 4.3.2.1 内容回写」），
   * 于是「这次内容回写在平台上成没成」也有了一份发布动作留痕，与逐字段的改前改后互补。
   *
   * <p>失败必须带原因（DB 有 CHECK）。
   */
  public ContentWritebackTaskRow report(String taskId, boolean success, String failureReason) {
    UUID tenant = currentTenant();
    UUID id = parseId(taskId);
    Record t =
        sql.fetchOne(
            "select id,writeback_id,state,listing_id,snapshot_id,operation_id from "
                + "listing_content_writeback_task where tenant_id=? and id=?",
            tenant, id);
    if (t == null) {
      throw notFound();
    }
    if (!DISPATCHED.equals(t.get("state", String.class))) {
      throw unprocessable(
          "只有在途（DISPATCHED）任务可回报结果，当前是 " + t.get("state", String.class));
    }
    if (success) {
      sql.execute(
          "update listing_content_writeback_task set state='SUCCEEDED', completed_at=?, updated_at=now() "
              + "where tenant_id=? and id=?",
          Timestamp.from(OffsetDateTime.now().toInstant()), tenant, id);
      sql.execute(
          """
          insert into listing_publish_record
            (id,tenant_id,listing_id,snapshot_id,action,outcome,platform_request_id,reason,occurred_at)
          values (?,?,?,?,'CONTENT_WRITEBACK','SUCCEEDED','',?,now())
          """,
          Ids.next(), tenant, t.get("listing_id", UUID.class), t.get("snapshot_id", UUID.class),
          "内容回写成功（02 队列任务 " + t.get("operation_id", UUID.class) + "）");
    } else {
      if (blankToNull(failureReason) == null) {
        throw unprocessable("失败必须给出原因");
      }
      sql.execute(
          "update listing_content_writeback_task set state='FAILED', completed_at=?, failure_reason=?, "
              + "updated_at=now() where tenant_id=? and id=?",
          Timestamp.from(OffsetDateTime.now().toInstant()), text(failureReason), tenant, id);
    }
    refreshProgress(tenant, t.get("writeback_id", UUID.class));
    return loadTaskRow(tenant, id);
  }

  // ==================================================================
  // 进度重算
  // ==================================================================

  /**
   * 从任务状态实时重算本次回写的计数并写回；全部终态时置 COMPLETED。
   *
   * <p>🔴 用**固定的一次聚合查询**算六个计数，DB 的 CHECK 保证"各状态之和 = 总数"，
   * 所以界面上的进度不可能自相矛盾。{@code SKIPPED_NO_CHANGE} 也是终态（它不需要派发）。
   */
  private void refreshProgress(UUID tenant, UUID writebackId) {
    Record counts =
        sql.fetchOne(
            """
            select
              count(*) filter (where state='PENDING')            as pending,
              count(*) filter (where state='SKIPPED_NO_CHANGE')   as skipped,
              count(*) filter (where state='DISPATCHED')          as dispatched,
              count(*) filter (where state='SUCCEEDED')           as succeeded,
              count(*) filter (where state='FAILED')              as failed,
              count(*)                                             as total
            from listing_content_writeback_task where tenant_id=? and writeback_id=?
            """,
            tenant, writebackId);
    int pending = counts.get("pending", Integer.class);
    int skipped = counts.get("skipped", Integer.class);
    int dispatched = counts.get("dispatched", Integer.class);
    int succeeded = counts.get("succeeded", Integer.class);
    int failed = counts.get("failed", Integer.class);
    int total = counts.get("total", Integer.class);
    boolean allTerminal = total > 0 && pending == 0 && dispatched == 0;

    sql.execute(
        """
        update listing_content_writeback
        set pending_count=?, skipped_count=?, dispatched_count=?, succeeded_count=?, failed_count=?,
            total_count=?,
            state = case when ? then 'COMPLETED' else state end,
            completed_at = case when ? then now() else completed_at end,
            updated_at=now()
        where tenant_id=? and id=?
        """,
        pending, skipped, dispatched, succeeded, failed, total,
        allTerminal, allTerminal, tenant, writebackId);
  }

  // ==================================================================
  // 杂项
  // ==================================================================

  private static int clampSize(int size) {
    if (size <= 0) {
      return 50;
    }
    return Math.min(size, 200);
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(text(raw));
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 格式不正确");
    }
  }

  private static String note(String raw) {
    String v = text(raw);
    return v.length() > 500 ? v.substring(0, 500) : v;
  }

  /** text[] 列读成 List（jOOQ 对 PG 的 text[] 直接返回 String[]）。 */
  private static List<String> stringArray(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (raw instanceof String[] arr) {
      return List.of(arr);
    }
    if (raw instanceof List<?> list) {
      return list.stream().map(String::valueOf).toList();
    }
    return List.of(String.valueOf(raw));
  }

  /** uuid[] 列读成 List（前端要的是字符串形态的 id 列表，序列化成 UUID 数组由 Jackson 处理）。 */
  private static List<UUID> uuidArray(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (raw instanceof UUID[] arr) {
      return List.of(arr);
    }
    if (raw instanceof List<?> list) {
      return list.stream()
          .filter(java.util.Objects::nonNull)
          .map(v -> UUID.fromString(String.valueOf(v)))
          .toList();
    }
    return List.of();
  }
}
