package com.starcon.trade;

import static com.starcon.trade.PlatformDisputeAppealModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 11.4.1.2 证据与申诉。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「组织举证材料（物流签收、沟通记录、商品照片、检测报告）
 * 打包提交申诉，记录平台回复与结果。」输入：「举证材料；申诉文本（可用模板）；提交动作。」
 * 输出：「申诉提交记录 + 平台回复 + 结果（胜诉/败诉/部分）。」
 * 加粗句：「<b>举证材料必须来自系统记录</b>（轨迹、沟通日志），临时拼凑的证据在申诉中往往不被采信。」
 *
 * <p><b>本类最重要的三件事</b>：
 *
 * <ol>
 *   <li><b>时限判定只有一个判据</b>：本类<b>不自己写</b> {@code evidence_deadline <= now()}，
 *       而是调 DB 函数 {@code platform_dispute_appeal_gate(...)} ——
 *       <b>DB 触发器守卫① 调的是同一个函数</b>（照抄 6.3.1.3 的
 *       {@code purchase_order_approval_match(...)} 做法）。
 *       ⇒ 规则改一次，库层与服务层**同时**改；两边不可能漂移。
 *       ⚠️ 该函数用 <b>DB 自己的 {@code now()}</b> 判定，不接受调用方传入的时刻 ⇒
 *       服务层与触发器永远在同一个时钟域里比较（不会跨时钟域误判超期）。</li>
 *   <li><b>举证材料只能来自系统记录</b>：挂材料时调 DB 函数
 *       {@code platform_dispute_appeal_source_snapshot(...)}（取快照与判存在是同一处）——
 *       指向一条不存在的记录，服务层 400、DB 守卫② 也拒。
 *       存储形态是<b>逻辑引用 + 快照元数据</b>（照抄 7.4.1.1 的附件做法），
 *       <b>不复制文件本体、不建跨库外键</b>。</li>
 *   <li><b>不假装能调平台</b>：channel 服务没有纠纷 / 申诉提交端点（grep 取证见 V269 迁移
 *       第一节）⇒ 本类只落 {@code platform_dispute_appeal_submission}
 *       （待提交平台事实：目标系统 + 目标键 + 载荷 + 幂等键 + 状态机 + 重试计数），
 *       平台侧经<b>授权 API</b> 回写平台回复与结果。</li>
 * </ol>
 *
 * <p><b>与上游 11.4.1.1 的边界</b>：{@code platform_dispute} 与
 * {@code platform_dispute_evidence_item} 是**上游的表**，本类**只读**；
 * 写入一律**驱动上游服务**（{@link PlatformDisputeService}）执行 ——
 * 「每张事实表只有一个写入所有者」。
 * 其中「提交申诉」那一刻**必须**驱动上游的「提交举证」：
 * 申诉提交与举证提交是同一个动作，不写 {@code evidence_submitted_at} 的话，
 * V265 守卫① 就会把**按时提交了申诉**的纠纷判负 —— 那正是要避免的假事实。
 */
@Service
public class PlatformDisputeAppealService {

  /** 列表单页上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 金额刻度：4 位（与 11.4.1.1 的 dispute_amount 同刻度）。 */
  private static final int MONEY_SCALE = 4;

  private final DSLContext sql;
  private final PlatformDisputeService disputes;
  private final ObjectMapper json;

  public PlatformDisputeAppealService(
      DSLContext sql, PlatformDisputeService disputes, ObjectMapper json) {
    this.sql = sql;
    this.disputes = disputes;
    this.json = json;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的逐块可用性、上下游逐个报 delivered=false 与归属模块号、
   * 本模块<b>刻意不做</b>的事、状态机与三条硬规则的口径。
   */
  public PlatformAppealCapabilities capabilities() {
    return new PlatformAppealCapabilities(
        List.of(
            "申诉单 platform_dispute_appeal（V269）—— 申诉文本 + 主张金额 + 提交记录 + 平台回复 + 结果",
            "证据材料 platform_dispute_appeal_material（V269）—— **逻辑引用 + 快照元数据**，"
                + "来源必须是系统里已有的事实记录（物流轨迹 / 沟通记录 / 附件 / 质检单）",
            "待提交平台事实 platform_dispute_appeal_submission（V269）—— 目标系统 + 目标键 + 载荷 + "
                + "幂等键 + 状态机（PENDING/SENT/ACKNOWLEDGED/FAILED）+ 重试计数",
            "申诉留痕 platform_dispute_appeal_event（V269，追加式，运行角色 UPDATE/DELETE 已收回）",
            "提交判据 platform_dispute_appeal_gate()（V269，**SQL 函数**）—— "
                + "**服务端与 DB 触发器共用同一个判据**"),
        List.of(
            section("evidenceMaterial", "举证材料", true, null,
                "物流签收 / 沟通记录 / 商品照片 / 检测报告四类，全部**指向系统里已有的一条事实记录**："
                    + "shipment_record_tracking_observation / aftersales_message / "
                    + "aftersales_attachment / quality_inspection_order。"
                    + "⚠️ **没有「随便上传一个文件」这一档** —— 临时拼凑的证据在申诉中往往不被采信。",
                "platform_dispute_appeal_material"),
            section("appealText", "申诉文本", true, null,
                "文本必填（≤8000 字）。**模板只记模板码、不在库里存模板正文** —— "
                    + "模板是话术资产（11.4.2.2 知识库与话术，未交付），正文改了不该改历史申诉。",
                "platform_dispute_appeal.appeal_text / template_code"),
            section("submission", "提交动作", true, null,
                "提交 = ① 判据通过（时限窗口 + 必举证项齐备 + 必举证项被材料覆盖）"
                    + "② 驱动上游 11.4.1.1 的「提交举证」"
                    + "③ 落一条**待提交平台事实**。"
                    + "⚠️ **不调平台**（channel 无申诉端点），见 platformSubmissionRule。",
                "platform_dispute_appeal_submission"),
            section("platformReply", "平台回复", true, null,
                "由平台侧 / channel 适配器经**授权 API** 回写（POST /{id}/platform-reply）："
                    + "回复正文 + 回复单号 + 结果档位（胜诉 / 败诉 / 部分）。",
                "platform_dispute_appeal.platform_reply_*"),
            section("result", "结果（胜诉/败诉/部分）", true, null,
                "申诉层有**四个终态**：RESOLVED_WON / RESOLVED_LOST / **RESOLVED_PARTIAL** / WITHDRAWN。"
                    + "⚠️ 「部分」这一档在 11.4.1.1 的结果码域里**没有对应物** ⇒ "
                    + "它只落在申诉层，金额口径走 11.4.1.1 的结算分录。",
                "platform_dispute_appeal.resolution_code / awarded_amount"),
            section("platformSubmission", "向平台提交申诉（下游 2 平台接口）", false, "2 平台接口",
                "**上游缺口**：channel 服务**没有纠纷 / 申诉提交端点**（grep 取证：全服务源码里 "
                    + "`dispute` / `appeal` 的命中只有 `ListingLifecycleService` 里的字符串常量 "
                    + "`\"block_appeal_workflow\"`，是 listing 生命周期的一个下架原因标签）。"
                    + "⇒ 本模块**不假装**能调平台，只落**待提交平台事实**；"
                    + "channel 落地申诉适配器后，经**授权 API** 接手即可衔接。",
                null),
            section("accountPerformance", "16 合规（账号绩效）", false, "16 合规",
                "**未交付** ⇒ 本模块**不产出任何绩效指标**，也不 mock 一个「申诉会影响账号绩效」的结论。"
                    + "账号绩效的影响在合规模块落地后经**授权 API / event** 衔接。",
                null)),
        List.of(
            related("MODULE_11_4_1_1", "11.4.1.1 纠纷列表", true, "11.4.1.1",
                "**已交付**（V265）。本模块的输入：纠纷单（金额 / 币种 / 举证时限）与"
                    + "举证清单（该交什么、齐不齐）。**上游的表由上游写** —— "
                    + "本模块的「提交申诉」与「记录结果」都是**驱动上游服务**执行的。",
                "/aftersales/disputes/platform-disputes"),
            related("MODULE_2_CHANNEL", "2 平台接口", false, "2 平台接口 / 2 平台同步",
                "**没有申诉提交端点** ⇒ 本模块只落待提交平台事实，不调平台。",
                null),
            related("MODULE_16_COMPLIANCE", "16 合规（账号绩效）", false, "16 合规",
                "**未交付** ⇒ 账号绩效影响无事实来源，本模块不产出。", null),
            related("MODULE_10_3_LOGISTICS", "10.3 物流轨迹（举证来源之一）", false, "10.3 物流轨迹",
                "轨迹观测事实落在 trade 自己的 shipment_record_tracking_observation（V127）"
                    + "⇒ 本模块**只存逻辑引用 + 快照**，不复制轨迹明细、不建跨库外键。", null),
            related("MODULE_15_CRM", "15 客服（沟通记录来源之一）", false, "15 客服",
                "售后沟通记录落在 trade 自己的 aftersales_message（V157）⇒ 同上，只存引用 + 快照。",
                null)),
        List.of(
            action("CREATE_APPEAL", "建申诉单", true, "POST", "/trade/platform-dispute-appeals", false,
                "指向一件已存在的纠纷；主张金额由服务端从纠纷单上读", null, null, null),
            action("UPDATE_TEXT", "改申诉文本", true, "PUT",
                "/trade/platform-dispute-appeals/{id}/text", false,
                "仅草稿态；提交给平台的那一份是快照", null, null, null),
            action("ATTACH_MATERIAL", "挂证据材料", true, "POST",
                "/trade/platform-dispute-appeals/{id}/materials", false,
                "仅草稿态；来源必须是系统里已有的事实记录", null, null, null),
            action("REMOVE_MATERIAL", "移除证据材料", true, "DELETE",
                "/trade/platform-dispute-appeals/{id}/materials/{materialId}", false,
                "仅草稿态", null, null, null),
            action("SUBMIT", "提交申诉", true, "POST",
                "/trade/platform-dispute-appeals/{id}/submit", false,
                "仅草稿态；**时限窗口开着** + 必举证项齐备 + 必举证项被材料覆盖",
                null, null, null),
            action("RETRY_SUBMISSION", "重试提交到平台", true, "POST",
                "/trade/platform-dispute-appeals/{id}/retry-submission", false,
                "有失败的提交尝试（**复用同一行**，幂等键保持稳定）", null, null, null),
            action("RECORD_SUBMISSION_ATTEMPT", "记录一次提交尝试的结果", true, "POST",
                "/trade/platform-dispute-appeals/{id}/submission-attempts", true,
                "仅已提交平台的申诉；**平台受理的判定权不在本服务**", null, null, null),
            action("PLATFORM_REPLY", "收回平台回复与结果", true, "POST",
                "/trade/platform-dispute-appeals/{id}/platform-reply", true,
                "仅已提交平台的申诉；「部分」必须给裁定金额", null, null, null),
            action("WITHDRAW", "我方撤诉", true, "POST",
                "/trade/platform-dispute-appeals/{id}/withdraw", true,
                "仅未终结的申诉；必须写理由", null, null, null),
            action("UPLOAD_ARBITRARY_FILE", "上传任意文件当举证材料", false, null, null, false, null,
                null, "系统（V269 守卫②）",
                "**刻意不提供** —— 举证材料必须来自系统记录，临时拼凑的证据在申诉中往往不被采信。"
                    + "材料只能引用系统里已有的一条事实记录。"),
            action("CALL_PLATFORM", "直接调平台提交申诉", false, null, null, false, null,
                null, "2 平台接口",
                "**刻意不提供** —— channel 服务没有申诉提交端点，"
                    + "放行一个不存在的调用只会让界面以为平台会自动收到申诉。")),
        List.of(
            boundary("举证材料只存逻辑引用 + 快照元数据，不复制文件本体、不建跨库外键", "系统（V269 守卫②）",
                "照抄 7.4.1.1 的附件做法：材料本体仍在文件服务里，"
                    + "本模块存的是「指向哪条系统记录」+「当时它长什么样」。"),
            boundary("不接受任意上传的文件当举证材料", "系统（V269 守卫②）",
                "蓝图加粗句「举证材料必须来自系统记录」。"
                    + "来源记录不存在 ⇒ 服务层 400、DB 也拒。"),
            boundary("提交申诉不调平台，只落待提交平台事实", "2 平台接口",
                "channel 没有申诉提交端点。事实先落下来，界面看得见「还欠平台一次提交」，"
                    + "而不是让界面以为平台已经收到了。"),
            boundary("不写 platform_dispute / platform_dispute_evidence_item（上游的表）", "11.4.1.1",
                "每张事实表只有一个写入所有者。本模块的写入一律**驱动上游服务**执行。"),
            boundary("「部分」这一档不代填 11.4.1.1 的结果码", "11.4.1.1",
                "11.4.1.1 的结果码域里没有「部分」；代填一个语义不符的码，"
                    + "比留一个明确的缺口更糟。金额口径在申诉的 awarded_amount 与结算分录上。"),
            boundary("终态不可逆：胜诉 / 败诉 / 部分 / 撤诉后业务要素整行冻结", "系统（V269 守卫③）",
                "申诉是要拿去跟平台对质的凭据，"
                    + "「我们当时交了什么、平台回了什么」必须只有一个答案。")),
        "DRAFT → SUBMITTED → PLATFORM_REPLIED → RESOLVED_WON（终态）/ RESOLVED_LOST（终态）"
            + " / RESOLVED_PARTIAL（终态）／ WITHDRAWN（终态）。"
            + "材料包只在 DRAFT 态可增删（提交给平台的那一份是快照）。",
        "**时限判定只有一个判据**：DB 函数 `platform_dispute_appeal_gate(tenant, dispute, appeal)`。"
            + "① 窗口状态（OPEN / EXPIRED / RELEASED / CLOSED）与**是否可提交**由它给出；"
            + "② 必举证项「没具备」的项数（11.4.1.1 口径：`required AND evidence_state <> 'PROVIDED'`）"
            + "与「没被本申诉材料覆盖」的项数也由它给出；"
            + "③ **服务端与 DB 触发器守卫① 调的是同一个函数** ⇒ 规则改一次两边同时改；"
            + "④ 判定用 **DB 自己的 now()**，不接受调用方传入的时刻 ⇒ 同一时钟域，不跨域误判。",
        "**「举证材料必须来自系统记录」落成结构而不是文案**："
            + "① 每条材料带 source_module + source_ref_id（逻辑引用），"
            + "指向 shipment_record_tracking_observation / aftersales_message / "
            + "aftersales_attachment / quality_inspection_order 四张表里**已存在**的一行；"
            + "② 取快照与判存在是**同一个 DB 函数**（`platform_dispute_appeal_source_snapshot`）—— "
            + "指向不存在的记录，服务层 400、DB 守卫② 也 RAISE；"
            + "③ **证据类型由来源记录推导**（附件按 evidence_kind 映射、消息是沟通记录、"
            + "轨迹是物流签收、质检单是履约证明），调用方**不能自由填类型**；"
            + "④ 存的是**逻辑引用 + 快照元数据**（单号 / 描述 / 定位符 / 时刻），"
            + "**不复制文件本体、不建跨库外键**。",
        "**不假装能调平台**：channel 服务**没有申诉提交端点**（grep 取证：全服务源码里 "
            + "`dispute` / `appeal` 的命中只有 `ListingLifecycleService` 里的字符串常量 "
            + "`\"block_appeal_workflow\"`，是 listing 生命周期的一个下架原因标签，与纠纷申诉无关）。"
            + "⇒ 本模块落**待提交平台事实**（目标系统 + 目标键 + 载荷 + 幂等键 + 状态机 + 重试计数），"
            + "**不调不存在的端点、不 mock 一个「已提交到 Amazon」**。"
            + "平台侧接手后经**授权 API**（POST /{id}/platform-reply）回写平台回复与结果。");
  }

  // ================================================================ 列表

  /** 申诉单列表：真分页 + 多维筛选 + 排序白名单 + 汇总（金额按币种分组）。 */
  public PlatformAppealPage listAppeals(PlatformAppealListQuery query) {
    int p = query.page() == null || query.page() < 0 ? 0 : query.page();
    int s = query.size() == null || query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    PlatformAppealSort sort = PlatformAppealSort.parse(query.sort());

    List<Object> binds = new ArrayList<>();
    List<String> where = conditions(query, binds);
    String clause = " where " + String.join(" and ", where);

    long total = sql.fetchOne("select count(*)" + from() + clause, binds.toArray()).get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<PlatformAppealRow> rows =
        sql.fetch(
                selectColumns() + from() + clause + " order by " + sort.sql() + " limit ? offset ?",
                pageBinds.toArray())
            .map(PlatformDisputeAppealService::mapRow);

    return new PlatformAppealPage(
        rows,
        p,
        s,
        total,
        summary(clause, binds),
        OffsetDateTime.now(ZoneOffset.UTC),
        "真分页（LIMIT/OFFSET + 独立 COUNT），汇总与排序不受分页影响。",
        "⚠️ **时限判定只有一个判据**：`platform_dispute_appeal_gate()`（DB 函数）。"
            + "服务端与 DB 触发器调的是同一个函数，用 DB 自己的 now() 判定 —— "
            + "所以「还剩多久能不能交」在库里、界面上、守卫里是**同一个答案**。",
        "⚠️ **举证材料只引用系统里已有的事实记录**，本模块不存文件本体、"
            + "不复制材料、也不接受任意上传的文件。",
        "排序白名单四个键（deadline_asc / created_desc / amount_desc / status_asc）；"
            + "其余取值落回 deadline_asc（最紧急的排最前），不接受任意表达式。");
  }

  /** 列表的 from 子句：申诉单 + 纠纷单 + **判据函数的横向展开**。 */
  private static String from() {
    return " from platform_dispute_appeal a"
        + " join platform_dispute d on d.tenant_id = a.tenant_id and d.id = a.dispute_id"
        + " left join lateral platform_dispute_appeal_gate(a.tenant_id, a.dispute_id, a.id) g on true";
  }

  private static String selectColumns() {
    return "select a.id, a.appeal_no, a.dispute_id, a.appeal_status, a.template_code,"
        + " a.appeal_text, a.submit_note, a.platform_reply_text, a.platform_reply_reference,"
        + " a.platform_reply_by, a.resolution_note, a.resolved_by, a.submitted_by, a.created_by,"
        + " a.claimed_currency, a.claimed_amount, a.awarded_currency, a.awarded_amount,"
        + " a.submitted_at, a.platform_reply_at, a.resolution_code, a.resolved_at,"
        + " a.created_at, a.updated_at, a.version,"
        + " d.dispute_no, d.platform_code, d.platform_dispute_no,"
        + " (select count(*) from platform_dispute_appeal_material m"
        + "   where m.tenant_id = a.tenant_id and m.appeal_id = a.id) as material_count,"
        + " (select count(*) from platform_dispute_appeal_submission s"
        + "   where s.tenant_id = a.tenant_id and s.appeal_id = a.id) as submission_count,"
        + " g.window_state, g.is_submittable, g.evidence_deadline";
  }

  /** 多维筛选（真筛选，不是前端过滤）。 */
  private List<String> conditions(PlatformAppealListQuery q, List<Object> binds) {
    List<String> where = new ArrayList<>();
    where.add("a.tenant_id = ?::uuid");
    binds.add(currentTenant());

    if (isSet(q.status())) {
      where.add("a.appeal_status = ?");
      binds.add(q.status().trim());
    }
    if (isSet(q.disputeId())) {
      where.add("a.dispute_id = ?::uuid");
      binds.add(UUID.fromString(q.disputeId().trim()));
    }
    if (isSet(q.currency())) {
      where.add("a.claimed_currency = ?");
      binds.add(q.currency().trim().toUpperCase());
    }
    if (isSet(q.resolutionCode())) {
      where.add("a.resolution_code = ?");
      binds.add(q.resolutionCode().trim());
    }
    if (isSet(q.submissionStatus())) {
      where.add(
          "exists (select 1 from platform_dispute_appeal_submission s"
              + " where s.tenant_id = a.tenant_id and s.appeal_id = a.id and s.submission_status = ?)");
      binds.add(q.submissionStatus().trim());
    }
    if (Boolean.TRUE.equals(q.submittableOnly())) {
      where.add("g.is_submittable");
    }
    if (q.from() != null) {
      where.add("a.created_at >= ?::timestamptz");
      binds.add(q.from());
    }
    if (q.to() != null) {
      where.add("a.created_at <= ?::timestamptz");
      binds.add(q.to());
    }
    if (isSet(q.keyword())) {
      where.add(
          "(a.appeal_no ilike ? or a.appeal_text ilike ? or d.platform_dispute_no ilike ?"
              + " or d.dispute_no ilike ?)");
      String like = "%" + q.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    return where;
  }

  /** 汇总：按状态分档 + **金额按币种分组**（不做固定汇率折算）。 */
  private PlatformAppealSummary summary(String clause, List<Object> binds) {
    // ⚠️ 汇总跑在**内层不聚合**的子查询上：外层一聚合，
    //    「待提交平台数」这个相关子查询引用的 a.tenant_id 就成了「未分组的列」
    //    （PG：subquery uses ungrouped column）。分两层写最省事也最清楚。
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where x.appeal_status = 'DRAFT') as draft,"
                + " count(*) filter (where x.appeal_status = 'SUBMITTED') as submitted,"
                + " count(*) filter (where x.appeal_status = 'PLATFORM_REPLIED') as replied,"
                + " count(*) filter (where x.appeal_status = 'RESOLVED_WON') as won,"
                + " count(*) filter (where x.appeal_status = 'RESOLVED_LOST') as lost,"
                + " count(*) filter (where x.appeal_status = 'RESOLVED_PARTIAL') as partial,"
                + " count(*) filter (where x.appeal_status = 'WITHDRAWN') as withdrawn,"
                + " count(*) filter (where x.is_submittable) as submittable,"
                + " count(*) filter (where x.pending_submission) as pending_submission"
                + " from (select a.appeal_status, g.is_submittable,"
                + "   exists (select 1 from platform_dispute_appeal_submission s2"
                + "     where s2.tenant_id = a.tenant_id and s2.appeal_id = a.id"
                + "       and s2.submission_status in ('PENDING','SENT')) as pending_submission"
                + from()
                + clause
                + ") x",
            binds.toArray());

    List<PlatformAppealCurrencyTotal> breakdown =
        sql.fetch(
                "select a.claimed_currency as currency, count(*) as cnt,"
                    + " coalesce(sum(a.claimed_amount), 0) as claimed,"
                    + " coalesce(sum(coalesce(a.awarded_amount, 0)), 0) as awarded"
                    + from()
                    + clause
                    + " group by a.claimed_currency order by a.claimed_currency",
                binds.toArray())
            .map(
                x ->
                    new PlatformAppealCurrencyTotal(
                        x.get("currency", String.class),
                        x.get("cnt", Long.class),
                        x.get("claimed", BigDecimal.class),
                        x.get("awarded", BigDecimal.class)));

    return new PlatformAppealSummary(
        r.get("total", Long.class),
        r.get("draft", Long.class),
        r.get("submitted", Long.class),
        r.get("replied", Long.class),
        r.get("won", Long.class),
        r.get("lost", Long.class),
        r.get("partial", Long.class),
        r.get("withdrawn", Long.class),
        r.get("submittable", Long.class),
        r.get("pending_submission", Long.class),
        breakdown);
  }

  // ================================================================ 详情

  /** 申诉单全景（头 + 材料 + 提交记录 + 留痕 + 判据 + 可执行动作）。 */
  public PlatformAppealDetail appealDetail(UUID appealId) {
    // ⚠️ 头用**带判据横向展开**的那条查询 —— 列表与详情给的是同一个口径
    //   （「能不能提交」不是详情独有的字段，列表上也要看得见）。
    Record head =
        sql.fetchOne(
            selectColumns() + from() + " where a.tenant_id = ?::uuid and a.id = ?::uuid",
            currentTenant(),
            appealId);
    if (head == null) {
      throw notFound("申诉单不存在或不属于当前租户：" + appealId);
    }
    UUID disputeId = head.get("dispute_id", UUID.class);

    List<PlatformAppealMaterialRow> materials =
        sql.fetch(
                "select * from platform_dispute_appeal_material"
                    + " where tenant_id = ?::uuid and appeal_id = ?::uuid order by material_seq asc",
                currentTenant(),
                appealId)
            .map(PlatformDisputeAppealService::mapMaterial);

    List<PlatformAppealSubmissionRow> submissions =
        sql.fetch(
                "select * from platform_dispute_appeal_submission"
                    + " where tenant_id = ?::uuid and appeal_id = ?::uuid"
                    + " order by created_at asc, id asc",
                currentTenant(),
                appealId)
            .map(PlatformDisputeAppealService::mapSubmission);

    List<PlatformAppealEventRow> events =
        sql.fetch(
                "select * from platform_dispute_appeal_event"
                    + " where tenant_id = ?::uuid and appeal_id = ?::uuid order by occurred_at asc, id asc",
                currentTenant(),
                appealId)
            .map(PlatformDisputeAppealService::mapEvent);

    PlatformAppealGateView gate = gate(disputeId, appealId);
    PlatformAppealRow row = mapRow(head);

    return new PlatformAppealDetail(
        row,
        head.get("appeal_text", String.class),
        head.get("submit_note", String.class),
        head.get("platform_reply_text", String.class),
        head.get("platform_reply_reference", String.class),
        head.get("platform_reply_by", UUID.class),
        head.get("resolution_note", String.class),
        head.get("resolved_by", UUID.class),
        head.get("submitted_by", UUID.class),
        head.get("created_by", UUID.class),
        materials,
        submissions,
        events,
        gate,
        actionsOf(row, materials, submissions, gate));
  }

  // ================================================================ 动作

  /** 建申诉单（草稿）。主张金额由服务端从纠纷单上读，不信客户端。 */
  public PlatformAppealDetail createAppeal(PlatformAppealCreate request, UUID userId) {
    if (request.disputeId() == null) {
      throw badRequest("必须指明申诉针对哪件纠纷");
    }
    String text = requireText(request.appealText());

    Record dispute = requireDisputeRow(request.disputeId());
    UUID disputeId = request.disputeId();

    // ⚠️ 同一件纠纷同时只允许**一份未终结**的申诉（DB 部分唯一索引兜底），
    //    这里先给一句人话。
    Long open =
        sql.fetchOne(
                "select count(*) from platform_dispute_appeal"
                    + " where tenant_id = ?::uuid and dispute_id = ?::uuid"
                    + " and appeal_status not in ('RESOLVED_WON','RESOLVED_LOST','RESOLVED_PARTIAL','WITHDRAWN')",
                currentTenant(),
                disputeId)
            .get(0, Long.class);
    if (open != null && open > 0) {
      throw badRequest("这件纠纷已经有一份未终结的申诉：重复申诉会让平台收到两份互相矛盾的说明");
    }

    UUID id = UUID.randomUUID();
    String appealNo = "PA-" + sql.fetchOne("select nextval('platform_dispute_appeal_no_seq')").get(0, Long.class);
    OffsetDateTime now = dbNow();

    sql.execute(
        "insert into platform_dispute_appeal(id,tenant_id,appeal_no,dispute_id,appeal_status,"
            + " appeal_text,template_code,claimed_currency,claimed_amount,created_by,created_at,"
            + " updated_at,updated_by) values(?::uuid,?::uuid,?::varchar,?::uuid,'DRAFT',?,?,?,?,?::uuid,"
            + "?::timestamptz,?::timestamptz,?::uuid)",
        id,
        currentTenant(),
        appealNo,
        disputeId,
        text,
        trimOrEmpty(request.templateCode()),
        dispute.get("dispute_currency", String.class),
        dispute.get("dispute_amount", BigDecimal.class),
        userId,
        now,
        now,
        userId);

    return appealDetail(id);
  }

  /** 改申诉文本（仅草稿态）。 */
  public PlatformAppealDetail updateText(UUID appealId, PlatformAppealTextUpdate request, UUID userId) {
    Record head = requireAppeal(appealId);
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "申诉单");
    requireStatus(head, Set.of(PlatformAppealStatus.DRAFT), "改申诉文本");
    String text = requireText(request.appealText());

    requireChanged(
        sql.execute(
            "update platform_dispute_appeal set appeal_text = ?, template_code = ?,"
                + " updated_at = ?::timestamptz, updated_by = ?::uuid, version = version + 1"
                + " where tenant_id = ?::uuid and id = ?::uuid and appeal_status = 'DRAFT'",
            text,
            trimOrEmpty(request.templateCode()),
            dbNow(),
            userId,
            currentTenant(),
            appealId),
        "改申诉文本");
    return appealDetail(appealId);
  }

  /**
   * 挂一份证据材料。
   *
   * <p>⚠️ 来源记录必须真实存在：调 DB 函数
   * {@code platform_dispute_appeal_source_snapshot(...)}（取快照与判存在是**同一处**）。
   * ⚠️ 证据类型**由来源记录推导**，调用方不能自由填。
   * ⚠️ 覆盖某个举证清单项时，**驱动上游**把该清单项标为「已具备」——
   * 上游仍是 {@code platform_dispute_evidence_item} 的唯一写入者。
   */
  public PlatformAppealDetail attachMaterial(
      UUID appealId, PlatformAppealMaterialCreate request, UUID userId) {
    Record head = requireAppeal(appealId);
    requireStatus(head, Set.of(PlatformAppealStatus.DRAFT), "挂证据材料");
    UUID disputeId = head.get("dispute_id", UUID.class);

    if (request.sourceRefId() == null) {
      throw badRequest("必须指明举证材料来自系统里的哪一条记录");
    }
    PlatformAppealSourceModule module =
        parseEnum(
            PlatformAppealSourceModule.class, request.sourceModule(), "举证材料来源系统");
    if (module == null) {
      throw badRequest("举证材料来源系统不能为空");
    }

    // 🔴 取快照与判存在是**同一个 DB 函数** ⇒ 服务层与 DB 守卫② 不会各说各话。
    Record snap =
        sql.fetchOne(
            "select * from platform_dispute_appeal_source_snapshot(?::uuid, ?::varchar, ?::uuid)",
            currentTenant(),
            module.name(),
            request.sourceRefId());
    if (snap == null || !Boolean.TRUE.equals(snap.get("exists_flag", Boolean.class))) {
      throw badRequest(
          "举证材料必须来自系统里已有的事实记录：来源 "
              + module.label()
              + " 下没有 id="
              + request.sourceRefId()
              + " 的记录（临时拼凑的证据在申诉中往往不被采信）");
    }
    String evidenceType = snap.get("suggested_evidence_type", String.class);
    if (evidenceType == null) {
      throw badRequest("无法从来源记录推导证据类型：" + module.label());
    }

    // 覆盖某个举证清单项？先确认它属于这件纠纷、且类型对得上。
    UUID evidenceItemId = request.evidenceItemId();
    boolean providedChecklistItem = false;
    if (evidenceItemId != null) {
      Record item =
          sql.fetchOne(
              "select evidence_type, dispute_id from platform_dispute_evidence_item"
                  + " where tenant_id = ?::uuid and id = ?::uuid",
              currentTenant(),
              evidenceItemId);
      if (item == null) {
        throw badRequest("举证清单项不存在或不属于当前租户：" + evidenceItemId);
      }
      if (!disputeId.equals(item.get("dispute_id", UUID.class))) {
        throw badRequest("举证清单项不属于这件纠纷");
      }
      if (!evidenceType.equals(item.get("evidence_type", String.class))) {
        throw badRequest(
            "证据类型（"
                + evidenceType
                + "）与清单项的类型（"
                + item.get("evidence_type", String.class)
                + "）对不上");
      }
    }

    sql.execute(
        "insert into platform_dispute_appeal_material(id,tenant_id,appeal_id,material_seq,"
            + " evidence_type,evidence_item_id,source_module,source_ref_id,source_ref_no,"
            + " source_label,source_locator,source_occurred_at,source_kind,material_note,"
            + " provided_checklist_item,created_by) values(?::uuid,?::uuid,?::uuid,0,?,?::uuid,?,"
            + "?::uuid,?,?,?,?::timestamptz,?,?,false,?::uuid)",
        UUID.randomUUID(),
        currentTenant(),
        appealId,
        evidenceType,
        evidenceItemId,
        module.name(),
        request.sourceRefId(),
        snap.get("source_ref_no", String.class),
        snap.get("source_label", String.class),
        snap.get("source_locator", String.class),
        snap.get("source_occurred_at", OffsetDateTime.class),
        snap.get("source_kind", String.class),
        trimOrEmpty(request.materialNote()),
        userId);

    // 驱动上游把清单项标为「已具备」——**上游仍是唯一写入者**。
    if (evidenceItemId != null) {
      String note =
          "已挂载证据材料（来源 "
              + module.label()
              + " / "
              + snap.get("source_label", String.class)
              + "）";
      disputes.markEvidence(
          disputeId,
          evidenceItemId,
          new PlatformDisputeModels.PlatformEvidenceMark("PROVIDED", note, null),
          userId);
      sql.execute(
          "update platform_dispute_appeal_material set provided_checklist_item = true"
              + " where tenant_id = ?::uuid and appeal_id = ?::uuid and evidence_item_id = ?::uuid",
          currentTenant(),
          appealId,
          evidenceItemId);
    }

    sql.execute(
        "update platform_dispute_appeal set updated_at = ?::timestamptz, updated_by = ?::uuid,"
            + " version = version + 1 where tenant_id = ?::uuid and id = ?::uuid",
        dbNow(),
        userId,
        currentTenant(),
        appealId);

    return appealDetail(appealId);
  }

  /** 移除一份证据材料（仅草稿态）。 */
  public PlatformAppealDetail removeMaterial(UUID appealId, UUID materialId, UUID userId) {
    Record head = requireAppeal(appealId);
    requireStatus(head, Set.of(PlatformAppealStatus.DRAFT), "移除证据材料");
    UUID disputeId = head.get("dispute_id", UUID.class);

    Record material =
        sql.fetchOne(
            "select * from platform_dispute_appeal_material"
                + " where tenant_id = ?::uuid and appeal_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            appealId,
            materialId);
    if (material == null) {
      throw badRequest("证据材料不存在或不属于当前租户：" + materialId);
    }

    // 材料删掉了，清单项就不该再声称「已具备」——
    // **但只在窗口还开着时才回退**：超期后纠纷已被系统判负，清单状态已无意义，
    // 而上游的 markEvidence 在终态会拒（那不是错误，是窗口关了）。
    boolean revert =
        Boolean.TRUE.equals(material.get("provided_checklist_item", Boolean.class))
            && material.get("evidence_item_id", UUID.class) != null
            && "OPEN".equals(gate(disputeId, appealId).windowState());

    sql.execute(
        "delete from platform_dispute_appeal_material"
            + " where tenant_id = ?::uuid and appeal_id = ?::uuid and id = ?::uuid",
        currentTenant(),
        appealId,
        materialId);

    if (revert) {
      disputes.markEvidence(
          disputeId,
          material.get("evidence_item_id", UUID.class),
          new PlatformDisputeModels.PlatformEvidenceMark(
              "PENDING", "证据材料已移除，等待重新准备", null),
          userId);
    }

    sql.execute(
        "update platform_dispute_appeal set updated_at = ?::timestamptz, updated_by = ?::uuid,"
            + " version = version + 1 where tenant_id = ?::uuid and id = ?::uuid",
        dbNow(),
        userId,
        currentTenant(),
        appealId);

    return appealDetail(appealId);
  }

  /**
   * 提交申诉（草稿 → 已提交平台）。
   *
   * <p>⚠️ <b>顺序是有讲究的</b>：
   *
   * <ol>
   *   <li>先用**同一个 DB 函数**判据检查（时限窗口 + 必举证项齐备 + 被材料覆盖）——
   *       把话说清楚（400），而不是让 DB 抛一个 409。</li>
   *   <li><b>先</b>把申诉单改成「已提交平台」——此时纠纷单还没动，
   *       DB 守卫① 里的判据仍然成立（窗口开着）。</li>
   *   <li>再<b>驱动上游</b> 11.4.1.1 的「提交举证」——
   *       不驱动的话，V265 守卫① 会把**按时提交了申诉**的纠纷判负。</li>
   *   <li>最后落一条**待提交平台事实**（不调平台）。</li>
   * </ol>
   *
   * <p>整个动作在**一个事务**里（{@code tenants.run}）：任何一步失败全部回滚。
   */
  public PlatformAppealDetail submitAppeal(UUID appealId, PlatformAppealSubmit request, UUID userId) {
    Record head = requireAppeal(appealId);
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "申诉单");
    requireStatus(head, Set.of(PlatformAppealStatus.DRAFT), "提交申诉");
    UUID disputeId = head.get("dispute_id", UUID.class);

    // 🔴 判据只有一份：DB 函数（与 DB 触发器守卫① 同一个）。
    PlatformAppealGateView g = gate(disputeId, appealId);
    if (!g.submittable()) {
      throw badRequest(
          "申诉提交窗口已关闭（"
              + g.windowStateLabel()
              + "，纠纷当前「"
              + g.disputeStatus()
              + "」，举证截止 "
              + g.evidenceDeadline()
              + "）：超期后不可提交申诉");
    }
    if (g.missingRequiredCount() > 0) {
      throw badRequest(
          "还有 " + g.missingRequiredCount() + " 项必举证材料没有具备，不能提交申诉（超期即判负，缺一项就是白丢）");
    }
    if (g.uncoveredRequiredCount() > 0) {
      throw badRequest(
          "还有 "
              + g.uncoveredRequiredCount()
              + " 项必举证材料没有被本次申诉的材料覆盖，不能提交申诉：清单说齐了但包里没有 = 白交");
    }

    OffsetDateTime submittedAt = dbNow();

    // ② 先把申诉单改成「已提交平台」（DB 守卫① 在这一步再钉一道同一判据）。
    requireChanged(
        sql.execute(
            "update platform_dispute_appeal set appeal_status = 'SUBMITTED',"
                + " submitted_at = ?::timestamptz, submitted_by = ?::uuid, submit_note = ?,"
                + " updated_at = ?::timestamptz, updated_by = ?::uuid, version = version + 1"
                + " where tenant_id = ?::uuid and id = ?::uuid and appeal_status = 'DRAFT'",
            submittedAt,
            userId,
            trimOrEmpty(request.submitNote()),
            submittedAt,
            userId,
            currentTenant(),
            appealId),
        "提交申诉");

    // ③ 驱动上游 11.4.1.1 的「提交举证」——**上游仍是 platform_dispute 的唯一写入者**。
    Record dispute = requireDisputeRow(disputeId);
    disputes.submitEvidence(
        disputeId,
        new PlatformDisputeModels.PlatformDisputeSubmitEvidence(
            trimOrEmpty(request.submitNote()), dispute.get("version", Long.class)),
        userId);

    // ④ 落待提交平台事实（**不调平台**：channel 没有申诉端点）。
    insertSubmission(appealId, disputeId, submittedAt, userId);

    return appealDetail(appealId);
  }

  /**
   * 收回平台回复 + 结果。
   *
   * <p>⚠️ 胜诉 / 败诉两档**驱动上游 11.4.1.1 的「记录平台裁决」**（上游仍是唯一写入者）；
   * 「部分」这一档**不代填**上游的结果码（上游的结果码域里没有「部分」，
   * 代填一个语义不符的码比留一个明确的缺口更糟）——
   * 金额口径落在申诉的 {@code awarded_amount} 与 11.4.1.1 的结算分录上。
   */
  public PlatformAppealDetail recordPlatformReply(
      UUID appealId, PlatformAppealPlatformReply request, UUID userId) {
    Record head = requireAppeal(appealId);
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "申诉单");
    requireStatus(
        head, Set.of(PlatformAppealStatus.SUBMITTED, PlatformAppealStatus.PLATFORM_REPLIED),
        "记录平台回复");
    UUID disputeId = head.get("dispute_id", UUID.class);

    String replyText = requireText(request.platformReplyText());
    PlatformAppealResult result =
        parseEnum(PlatformAppealResult.class, request.result(), "平台结果档位");
    if (result == null) {
      throw badRequest("必须给出平台结果档位（胜诉 / 败诉 / 部分支持）");
    }

    BigDecimal claimed = head.get("claimed_amount", BigDecimal.class);
    String claimedCurrency = head.get("claimed_currency", String.class);
    BigDecimal awarded = null;
    String awardedCurrency = null;
    if (result == PlatformAppealResult.PARTIAL) {
      awarded = money(request.awardedAmount(), "平台裁定金额");
      if (awarded.signum() <= 0) {
        throw badRequest("「部分支持」必须给出大于 0 的平台裁定金额");
      }
      if (awarded.compareTo(claimed) >= 0) {
        throw badRequest(
            "「部分支持」的裁定金额（" + awarded + "）必须小于主张金额（" + claimed + "）"
                + "：全支持是胜诉，全不支持是败诉");
      }
      awardedCurrency = requireCurrency(request.awardedCurrency(), claimedCurrency);
    }

    OffsetDateTime now = dbNow();
    String targetStatus =
        switch (result) {
          case WON -> "RESOLVED_WON";
          case LOST -> "RESOLVED_LOST";
          case PARTIAL -> "RESOLVED_PARTIAL";
        };
    String targetCode =
        switch (result) {
          case WON -> "PLATFORM_RULING_FOR";
          case LOST -> "PLATFORM_RULING_AGAINST";
          case PARTIAL -> "PARTIAL_RULING";
        };

    // ⚠️ **不绑裸 null**：jOOQ 绑一个没有类型的 null 会报「未设定参数值 N 的内容」。
    //    所以「只有部分支持才有裁定金额」这件事落成**条件 SQL**，
    //    而不是绑一个 null 进去（照抄本仓其它模块对可空列的处理）。
    StringBuilder update =
        new StringBuilder(
            "update platform_dispute_appeal set appeal_status = ?,"
                + " platform_reply_at = ?::timestamptz, platform_reply_by = ?::uuid,"
                + " platform_reply_text = ?, platform_reply_reference = ?,"
                + " resolution_code = ?, resolution_note = ?,");
    List<Object> binds =
        new ArrayList<>(
            List.of(
                targetStatus,
                now,
                userId,
                replyText,
                trimOrEmpty(request.platformReplyReference()),
                targetCode,
                trimOrEmpty(request.resolutionNote())));
    if (awarded != null) {
      update.append(" awarded_amount = ?::numeric, awarded_currency = ?,");
      binds.add(awarded);
      binds.add(awardedCurrency);
    }
    update.append(
        " resolved_at = ?::timestamptz, resolved_by = ?::uuid,"
            + " updated_at = ?::timestamptz, updated_by = ?::uuid, version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid"
            + " and appeal_status in ('SUBMITTED','PLATFORM_REPLIED')");
    binds.add(now);
    binds.add(userId);
    binds.add(now);
    binds.add(userId);
    binds.add(currentTenant());
    binds.add(appealId);

    requireChanged(sql.execute(update.toString(), binds.toArray()), "记录平台回复");

    // 驱动上游的「记录平台裁决」——**上游仍是 platform_dispute 的唯一写入者**。
    // ⚠️ 「部分」不驱动：11.4.1.1 的结果码域里没有这一档。
    if (result != PlatformAppealResult.PARTIAL) {
      Record dispute = requireDisputeRow(disputeId);
      disputes.resolve(
          disputeId,
          new PlatformDisputeModels.PlatformDisputeResolve(
              targetCode, trimOrEmpty(request.resolutionNote()), dispute.get("version", Long.class)),
          userId);
    }

    return appealDetail(appealId);
  }

  /** 我方撤诉（驱动上游的「撤诉」）。 */
  public PlatformAppealDetail withdrawAppeal(
      UUID appealId, PlatformAppealWithdraw request, UUID userId) {
    Record head = requireAppeal(appealId);
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "申诉单");
    requireStatus(
        head,
        Set.of(
            PlatformAppealStatus.DRAFT,
            PlatformAppealStatus.SUBMITTED,
            PlatformAppealStatus.PLATFORM_REPLIED),
        "我方撤诉");
    UUID disputeId = head.get("dispute_id", UUID.class);
    if (!isSet(request.withdrawReason())) {
      throw badRequest("撤诉必须写理由：事后要答得出「这件纠纷为什么是我们自己撤的」");
    }

    OffsetDateTime now = dbNow();
    requireChanged(
        sql.execute(
            "update platform_dispute_appeal set appeal_status = 'WITHDRAWN',"
                + " resolution_code = 'WITHDRAWN_BY_US', resolution_note = ?,"
                + " resolved_at = ?::timestamptz, resolved_by = ?::uuid,"
                + " updated_at = ?::timestamptz, updated_by = ?::uuid, version = version + 1"
                + " where tenant_id = ?::uuid and id = ?::uuid"
                + " and appeal_status in ('DRAFT','SUBMITTED','PLATFORM_REPLIED')",
            request.withdrawReason().trim(),
            now,
            userId,
            now,
            userId,
            currentTenant(),
            appealId),
        "我方撤诉");

    // 驱动上游的「撤诉」——上游仍是唯一写入者。
    Record dispute = requireDisputeRow(disputeId);
    disputes.withdraw(
        disputeId,
        new PlatformDisputeModels.PlatformDisputeWithdraw(
            request.withdrawReason().trim(), dispute.get("version", Long.class)),
        userId);

    return appealDetail(appealId);
  }

  /**
   * 记录一次提交尝试的结果（已发出 / 失败 / 平台已受理）。
   *
   * <p>⚠️ channel 侧**没有申诉适配器** ⇒ 这个端点当前由**平台侧经授权 API** 或
   * **人工**（在 Amazon 后台手工递了申诉时）调用，把「发出去了 / 失败了 / 平台收下了」
   * 如实记下来。**平台受理的判定权不在本服务** —— 这里只记录谁在什么时候说了什么。
   */
  public PlatformAppealDetail recordSubmissionAttempt(
      UUID appealId, PlatformAppealSubmissionAttempt request, UUID userId) {
    Record head = requireAppeal(appealId);
    requireStatus(
        head, Set.of(PlatformAppealStatus.SUBMITTED, PlatformAppealStatus.PLATFORM_REPLIED),
        "记录提交尝试结果");

    PlatformAppealSubmissionOutcome outcome =
        parseEnum(
            PlatformAppealSubmissionOutcome.class, request.outcome(), "提交尝试结果");
    if (outcome == null) {
      throw badRequest("必须给出提交尝试结果（已发出 / 提交失败 / 平台已受理）");
    }

    Record submission = requireLatestSubmission(appealId);
    if ("ACKNOWLEDGED".equals(submission.get("submission_status", String.class))) {
      throw badRequest("提交记录已被平台受理，是终态不可修改：submission_id="
          + submission.get("id", UUID.class));
    }

    OffsetDateTime now = dbNow();
    String error = trimOrEmpty(request.errorMessage());
    if (outcome == PlatformAppealSubmissionOutcome.FAILED && error.isEmpty()) {
      throw badRequest("提交失败必须写清失败原因：事后要答得出「为什么没递上去」");
    }

    // ⚠️ **条件 SQL，不绑裸 null**（jOOQ 绑没有类型的 null 会报「未设定参数值 N 的内容」）。
    StringBuilder update =
        new StringBuilder(
            "update platform_dispute_appeal_submission set"
                + " submission_status = ?,"
                + " last_error = ?,"
                + " last_attempt_at = ?::timestamptz,");
    List<Object> binds = new ArrayList<>(List.of(outcome.name(), error, now));
    if (request.nextRetryAt() != null) {
      update.append(" next_retry_at = ?::timestamptz,");
      binds.add(request.nextRetryAt());
    } else {
      update.append(" next_retry_at = null,");
    }
    if (outcome == PlatformAppealSubmissionOutcome.SENT) {
      update.append(" sent_at = ?::timestamptz,");
      binds.add(now);
    }
    if (outcome == PlatformAppealSubmissionOutcome.ACKNOWLEDGED) {
      update.append(" acknowledged_at = ?::timestamptz, acknowledged_by = ?::uuid,");
      binds.add(now);
      binds.add(userId);
    }
    update.append(
        " updated_at = ?::timestamptz, updated_by = ?::uuid"
            + " where tenant_id = ?::uuid and id = ?::uuid");
    binds.add(now);
    binds.add(userId);
    binds.add(currentTenant());
    binds.add(submission.get("id", UUID.class));

    requireChanged(sql.execute(update.toString(), binds.toArray()), "记录提交尝试结果");
    return appealDetail(appealId);
  }

  /**
   * 重试提交到平台：把失败的尝试**重新排队**。
   *
   * <p>⚠️ <b>重试复用同一行</b>（不另起一行）：幂等键必须稳定 ——
   * 平台侧靠它去重，同一份申诉重试十次也只该收到一次。
   * 尝试次数由 {@code attempt_count} 承担，历史由留痕承担。
   */
  public PlatformAppealDetail retrySubmission(
      UUID appealId, PlatformAppealRetrySubmission request, UUID userId) {
    Record head = requireAppeal(appealId);
    requireStatus(
        head, Set.of(PlatformAppealStatus.SUBMITTED, PlatformAppealStatus.PLATFORM_REPLIED),
        "重试提交");

    Record submission = requireLatestSubmission(appealId);
    String status = submission.get("submission_status", String.class);
    if ("ACKNOWLEDGED".equals(status)) {
      throw badRequest("平台已经受理了这次申诉，不需要重试");
    }
    if (!"FAILED".equals(status)) {
      throw badRequest(
          "只有失败的提交尝试可以重试（当前是「"
              + PlatformAppealSubmissionStatus.valueOf(status).label()
              + "」）：还没失败就先重试会把平台那边的记录搅乱");
    }

    requireChanged(
        sql.execute(
            "update platform_dispute_appeal_submission set submission_status = 'PENDING',"
                + " attempt_count = attempt_count + 1, next_retry_at = null,"
                + " last_attempt_at = ?::timestamptz, updated_at = ?::timestamptz"
                + " where tenant_id = ?::uuid and id = ?::uuid and submission_status = 'FAILED'",
            dbNow(),
            dbNow(),
            currentTenant(),
            submission.get("id", UUID.class)),
        "重试提交");
    return appealDetail(appealId);
  }

  /** 取该申诉最新的一条提交记录。 */
  private Record requireLatestSubmission(UUID appealId) {
    Record r =
        sql.fetchOne(
            "select * from platform_dispute_appeal_submission"
                + " where tenant_id = ?::uuid and appeal_id = ?::uuid"
                + " order by created_at desc, id desc limit 1",
            currentTenant(),
            appealId);
    if (r == null) {
      throw badRequest("这件申诉还没有待提交平台事实：先提交申诉");
    }
    return r;
  }

  // ================================================================ 待提交平台事实

  /** 落一条待提交平台事实（**不调平台**）。 */
  private void insertSubmission(UUID appealId, UUID disputeId, OffsetDateTime at, UUID userId) {
    Record head = requireAppeal(appealId);
    Record dispute = requireDisputeRow(disputeId);

    // 幂等键：同一份申诉的内容摘要 —— 内容没变就复用同一个键，
    // 内容变了就换新键。⇒ 平台侧不会收到两份一模一样的申诉。
    String idempotencyKey = idempotencyKey(appealId);

    List<PlatformAppealMaterialRow> materials =
        sql.fetch(
                "select * from platform_dispute_appeal_material"
                    + " where tenant_id = ?::uuid and appeal_id = ?::uuid order by material_seq asc",
                currentTenant(),
                appealId)
            .map(PlatformDisputeAppealService::mapMaterial);

    String payload =
        writePayload(
            new PlatformAppealSubmissionPayload(
                head.get("appeal_no", String.class),
                dispute.get("dispute_no", String.class),
                dispute.get("platform_code", String.class),
                dispute.get("platform_dispute_no", String.class),
                head.get("appeal_text", String.class),
                head.get("template_code", String.class),
                head.get("claimed_currency", String.class),
                head.get("claimed_amount", BigDecimal.class),
                materials.stream()
                    .map(
                        m ->
                            new PlatformAppealSubmissionMaterial(
                                m.materialSeq(),
                                m.evidenceType(),
                                m.sourceModule(),
                                m.sourceRefId().toString(),
                                m.sourceRefNo(),
                                m.sourceLabel(),
                                m.sourceLocator(),
                                m.sourceOccurredAt().toString()))
                    .toList(),
                idempotencyKey,
                at.toString()));

    sql.execute(
        "insert into platform_dispute_appeal_submission(id,tenant_id,appeal_id,target_system,"
            + " target_key,payload,idempotency_key,submission_status,attempt_count,last_attempt_at,"
            + " created_by,created_at,updated_at) values(?::uuid,?::uuid,?::uuid,'CHANNEL',?,"
            + "?::jsonb,?,'PENDING',1,?::timestamptz,?::uuid,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        appealId,
        head.get("appeal_no", String.class),
        payload,
        idempotencyKey,
        at,
        userId,
        at,
        at);
  }

  /** 幂等键：申诉单号 + 内容摘要。 */
  private String idempotencyKey(UUID appealId) {
    Record head = requireAppeal(appealId);
    String digest =
        head.get("appeal_text", String.class)
            + "|"
            + head.get("template_code", String.class)
            + "|"
            + head.get("claimed_amount", BigDecimal.class)
            + "|"
            + sql.fetchOne(
                    "select coalesce(string_agg(m.evidence_type || ':' || m.source_module || ':' ||"
                        + " m.source_ref_id::text, ',' order by m.material_seq), '')"
                        + " from platform_dispute_appeal_material m"
                        + " where m.tenant_id = ?::uuid and m.appeal_id = ?::uuid",
                    currentTenant(),
                    appealId)
                .get(0, String.class);
    return head.get("appeal_no", String.class) + "-" + shortHash(digest);
  }

  /** 短摘要（SHA-256 前 12 位十六进制）。 */
  private static String shortHash(String raw) {
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] bytes = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 6; i++) {
        sb.append(String.format("%02x", bytes[i]));
      }
      return sb.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // ================================================================ 判据

  /**
   * 调 DB 判据函数。
   *
   * <p>⚠️ <b>本模块唯一的时限 / 覆盖判据入口</b>：服务层不自己写
   * {@code evidence_deadline <= now()}，DB 触发器守卫① 也调同一个函数。
   */
  private PlatformAppealGateView gate(UUID disputeId, UUID appealId) {
    Record g =
        sql.fetchOne(
            "select * from platform_dispute_appeal_gate(?::uuid, ?::uuid, ?::uuid)",
            currentTenant(),
            disputeId,
            appealId);
    if (g == null) {
      throw badRequest("无法取得提交判据：纠纷单不存在或不属于当前租户");
    }
    String windowState = g.get("window_state", String.class);
    return new PlatformAppealGateView(
        windowState,
        PlatformAppealWindowState.valueOf(windowState).label(),
        Boolean.TRUE.equals(g.get("is_submittable", Boolean.class)),
        g.get("dispute_status", String.class),
        g.get("evidence_deadline", OffsetDateTime.class),
        g.get("evidence_submitted_at", OffsetDateTime.class),
        g.get("checklist_item_count", Integer.class) == null
            ? 0L
            : g.get("checklist_item_count", Integer.class),
        g.get("missing_required_count", Integer.class) == null
            ? 0L
            : g.get("missing_required_count", Integer.class),
        g.get("covered_item_count", Integer.class) == null
            ? 0L
            : g.get("covered_item_count", Integer.class),
        g.get("uncovered_required_count", Integer.class) == null
            ? 0L
            : g.get("uncovered_required_count", Integer.class));
  }

  // ================================================================ 动作清单

  /** 当前可执行动作（每条都带**为什么不能做**）。 */
  private List<PlatformAppealAction> actionsOf(
      PlatformAppealRow row,
      List<PlatformAppealMaterialRow> materials,
      List<PlatformAppealSubmissionRow> submissions,
      PlatformAppealGateView gate) {
    PlatformAppealStatus status = PlatformAppealStatus.valueOf(row.appealStatus());
    UUID id = row.id();
    boolean draft = status == PlatformAppealStatus.DRAFT;
    boolean failed = submissions.stream()
        .anyMatch(s -> "FAILED".equals(s.submissionStatus()));
    boolean openSubmission = submissions.stream()
        .anyMatch(s -> !"ACKNOWLEDGED".equals(s.submissionStatus()));

    List<PlatformAppealAction> actions = new ArrayList<>();
    actions.add(
        action(
            "ATTACH_MATERIAL", "挂证据材料", draft, "POST",
            "/trade/platform-dispute-appeals/" + id + "/materials", false,
            "仅草稿态；来源必须是系统里已有的事实记录",
            draft ? null : "申诉单已是「" + status.label() + "」，材料包已冻结"));
    actions.add(
        action(
            "REMOVE_MATERIAL", "移除证据材料", draft, "DELETE",
            "/trade/platform-dispute-appeals/" + id + "/materials/{materialId}", false,
            "仅草稿态",
            draft ? null : "申诉单已是「" + status.label() + "」，材料包已冻结"));
    actions.add(
        action(
            "UPDATE_TEXT", "改申诉文本", draft, "PUT",
            "/trade/platform-dispute-appeals/" + id + "/text", false,
            "仅草稿态；提交给平台的那一份是快照",
            draft ? null : "申诉单已是「" + status.label() + "」，申诉文本已冻结"));
    actions.add(
        action(
            "SUBMIT", "提交申诉",
            draft && gate.submittable() && gate.missingRequiredCount() == 0
                && gate.uncoveredRequiredCount() == 0,
            "POST", "/trade/platform-dispute-appeals/" + id + "/submit", false,
            "仅草稿态；时限窗口开着 + 必举证项齐备 + 必举证项被材料覆盖",
            submitBlocked(status, gate)));
    actions.add(
        action(
            "RETRY_SUBMISSION", "重试提交到平台", failed, "POST",
            "/trade/platform-dispute-appeals/" + id + "/retry-submission", false,
            "有失败的提交尝试",
            failed ? null : "没有失败的提交尝试"));
    actions.add(
        action(
            "RECORD_SUBMISSION_ATTEMPT", "记录一次提交尝试的结果", openSubmission, "POST",
            "/trade/platform-dispute-appeals/" + id + "/submission-attempts", true,
            "仅已提交平台的申诉",
            openSubmission ? null : "平台已受理，没有待记录的提交尝试"));
    actions.add(
        action(
            "PLATFORM_REPLY", "收回平台回复与结果",
            status == PlatformAppealStatus.SUBMITTED || status == PlatformAppealStatus.PLATFORM_REPLIED,
            "POST", "/trade/platform-dispute-appeals/" + id + "/platform-reply", true,
            "仅已提交平台的申诉",
            (status == PlatformAppealStatus.SUBMITTED || status == PlatformAppealStatus.PLATFORM_REPLIED)
                ? null
                : "只有已提交平台的申诉才有平台回复"));
    actions.add(
        action(
            "WITHDRAW", "我方撤诉",
            status == PlatformAppealStatus.DRAFT
                || status == PlatformAppealStatus.SUBMITTED
                || status == PlatformAppealStatus.PLATFORM_REPLIED,
            "POST", "/trade/platform-dispute-appeals/" + id + "/withdraw", true,
            "仅未终结的申诉；必须写理由",
            (status == PlatformAppealStatus.DRAFT
                    || status == PlatformAppealStatus.SUBMITTED
                    || status == PlatformAppealStatus.PLATFORM_REPLIED)
                ? null
                : "申诉单已是终态（" + status.label() + "），不可撤回"));
    return actions;
  }

  private static String submitBlocked(PlatformAppealStatus status, PlatformAppealGateView gate) {
    if (status != PlatformAppealStatus.DRAFT) {
      return "只有草稿状态的申诉可以提交";
    }
    if (!gate.submittable()) {
      return "提交窗口已关闭：" + gate.windowStateLabel() + "（举证截止 " + gate.evidenceDeadline() + "）";
    }
    if (gate.missingRequiredCount() > 0) {
      return "还有 " + gate.missingRequiredCount() + " 项必举证材料没有具备";
    }
    if (gate.uncoveredRequiredCount() > 0) {
      return "还有 " + gate.uncoveredRequiredCount() + " 项必举证材料没有被本次申诉的材料覆盖";
    }
    return null;
  }

  // ================================================================ 小工具

  private UUID currentTenant() {
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }

  /** **DB 自己的时钟**：判据在 DB 侧，写入也用同一个时钟域。 */
  private OffsetDateTime dbNow() {
    return sql.fetchOne("select now()").get(0, OffsetDateTime.class);
  }

  /** 取申诉单（**按 id 直达**的端点必须显式带租户谓词）。 */
  private Record requireAppeal(UUID id) {
    Record r =
        sql.fetchOne(
            "select * from platform_dispute_appeal where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    if (r == null) {
      throw notFound("申诉单不存在或不属于当前租户：" + id);
    }
    return r;
  }

  /** 取纠纷单（上游的事实，本模块只读）。 */
  private Record requireDisputeRow(UUID id) {
    Record r =
        sql.fetchOne(
            "select * from platform_dispute where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    if (r == null) {
      throw notFound("纠纷单不存在或不属于当前租户：" + id);
    }
    return r;
  }

  private static void requireVersion(Long current, Long expected, String label) {
    if (expected == null) {
      throw badRequest("必须带 expectedVersion（改状态要走乐观锁，避免两个人同时改同一件申诉）");
    }
    if (!expected.equals(current)) {
      throw badRequest(
          label + "已被别人改过（当前版本 " + current + "，你拿到的是 " + expected + "），请刷新后重试");
    }
  }

  private static void requireStatus(
      Record head, Set<PlatformAppealStatus> allowed, String action) {
    PlatformAppealStatus status =
        PlatformAppealStatus.valueOf(head.get("appeal_status", String.class));
    if (!allowed.contains(status)) {
      throw badRequest(
          "申诉单当前是「" + status.label() + "」，不能" + action + "（允许的状态："
              + allowed.stream().map(PlatformAppealStatus::label).toList() + "）");
    }
  }

  private static void requireChanged(int changed, String action) {
    if (changed == 0) {
      throw badRequest(action + "失败：状态已经变了（可能被别人抢先生效），请刷新后重试");
    }
  }

  private static String requireText(String raw) {
    if (!isSet(raw)) {
      throw badRequest("申诉文本不能为空：没有说明的申诉递上去等于没递");
    }
    String trimmed = raw.trim();
    if (trimmed.length() > 8000) {
      throw badRequest("申诉文本超长（上限 8000 字，当前 " + trimmed.length() + " 字）");
    }
    return trimmed;
  }

  private static BigDecimal money(BigDecimal value, String label) {
    if (value == null) {
      throw badRequest("必须给出" + label);
    }
    BigDecimal scaled = value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (scaled.signum() < 0) {
      throw badRequest(label + "不能为负：" + value);
    }
    return scaled;
  }

  private static String requireCurrency(String raw, String fallback) {
    if (!isSet(raw)) {
      return fallback;
    }
    String upper = raw.trim().toUpperCase();
    if (!upper.matches("^[A-Z]{3}$")) {
      throw badRequest("币种必须是三位大写字母：" + raw);
    }
    return upper;
  }

  private static <T extends Enum<T>> T parseEnum(Class<T> type, String raw, String label) {
    if (!isSet(raw)) {
      return null;
    }
    try {
      return Enum.valueOf(type, raw.trim());
    } catch (IllegalArgumentException e) {
      throw badRequest(
          label + "取值非法：" + raw + "（可选：" + java.util.Arrays.toString(type.getEnumConstants()) + "）");
    }
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  private static String trimOrEmpty(String value) {
    return isSet(value) ? value.trim() : "";
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private String writePayload(PlatformAppealSubmissionPayload payload) {
    try {
      return json.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("申诉载荷序列化失败", e);
    }
  }

  // ================================================================ 行映射

  private static PlatformAppealRow mapRow(Record r) {
    String status = r.get("appeal_status", String.class);
    String resolution = r.get("resolution_code", String.class);
    String windowState = r.get("window_state", String.class);
    Long materialCount = r.get("material_count", Long.class);
    Long submissionCount = r.get("submission_count", Long.class);
    return new PlatformAppealRow(
        r.get("id", UUID.class),
        r.get("appeal_no", String.class),
        r.get("dispute_id", UUID.class),
        r.get("dispute_no", String.class),
        r.get("platform_code", String.class),
        r.get("platform_dispute_no", String.class),
        status,
        PlatformAppealStatus.valueOf(status).label(),
        r.get("template_code", String.class),
        r.get("claimed_currency", String.class),
        r.get("claimed_amount", BigDecimal.class),
        r.get("awarded_amount", BigDecimal.class),
        r.get("awarded_currency", String.class),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("platform_reply_at", OffsetDateTime.class),
        resolution,
        resolution == null ? null : PlatformAppealResolution.valueOf(resolution).label(),
        r.get("resolved_at", OffsetDateTime.class),
        materialCount == null ? 0L : materialCount,
        submissionCount == null ? 0L : submissionCount,
        windowState,
        windowState == null ? "" : PlatformAppealWindowState.valueOf(windowState).label(),
        Boolean.TRUE.equals(r.get("is_submittable", Boolean.class)),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class),
        r.get("version", Long.class));
  }

  private static PlatformAppealMaterialRow mapMaterial(Record r) {
    String evidenceType = r.get("evidence_type", String.class);
    String sourceModule = r.get("source_module", String.class);
    return new PlatformAppealMaterialRow(
        r.get("id", UUID.class),
        r.get("material_seq", Integer.class),
        evidenceType,
        PlatformDisputeModels.PlatformEvidenceType.valueOf(evidenceType).label(),
        r.get("evidence_item_id", UUID.class),
        sourceModule,
        PlatformAppealSourceModule.valueOf(sourceModule).label(),
        r.get("source_ref_id", UUID.class),
        r.get("source_ref_no", String.class),
        r.get("source_label", String.class),
        r.get("source_locator", String.class),
        r.get("source_occurred_at", OffsetDateTime.class),
        r.get("source_kind", String.class),
        r.get("material_note", String.class),
        Boolean.TRUE.equals(r.get("provided_checklist_item", Boolean.class)),
        r.get("created_at", OffsetDateTime.class));
  }

  private static PlatformAppealSubmissionRow mapSubmission(Record r) {
    String status = r.get("submission_status", String.class);
    return new PlatformAppealSubmissionRow(
        r.get("id", UUID.class),
        r.get("target_system", String.class),
        r.get("target_key", String.class),
        r.get("idempotency_key", String.class),
        status,
        PlatformAppealSubmissionStatus.valueOf(status).label(),
        r.get("attempt_count", Integer.class),
        r.get("last_attempt_at", OffsetDateTime.class),
        r.get("next_retry_at", OffsetDateTime.class),
        r.get("last_error", String.class),
        r.get("sent_at", OffsetDateTime.class),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static PlatformAppealEventRow mapEvent(Record r) {
    String type = r.get("event_type", String.class);
    return new PlatformAppealEventRow(
        r.get("id", UUID.class),
        type,
        eventLabel(type),
        r.get("from_status", String.class),
        r.get("to_status", String.class),
        r.get("reason_note", String.class),
        r.get("occurred_by", UUID.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  private static String eventLabel(String code) {
    return switch (code) {
      case "CREATED" -> "创建申诉单";
      case "MATERIAL_ADDED" -> "加入证据材料";
      case "MATERIAL_REMOVED" -> "移除证据材料";
      case "TEXT_UPDATED" -> "修改申诉文本";
      case "SUBMITTED" -> "提交申诉到平台";
      case "SUBMISSION_CREATED" -> "登记待提交平台事实";
      case "SUBMISSION_SENT" -> "已发出申诉";
      case "SUBMISSION_FAILED" -> "提交失败";
      case "SUBMISSION_REQUEUED" -> "重新排队重试";
      case "SUBMISSION_ACKNOWLEDGED" -> "平台已受理";
      case "PLATFORM_REPLIED" -> "收到平台回复";
      case "RESOLVED_WON" -> "我方胜诉";
      case "RESOLVED_LOST" -> "我方败诉";
      case "RESOLVED_PARTIAL" -> "平台部分支持";
      case "WITHDRAWN" -> "我方撤诉";
      default -> code;
    };
  }

  private static PlatformAppealSectionCapability section(
      String section, String label, boolean available, String ownerModule, String reason,
      String landsIn) {
    return new PlatformAppealSectionCapability(section, label, available, ownerModule, reason, landsIn);
  }

  private static PlatformAppealRelatedModule related(
      String id, String label, boolean delivered, String ownerModule, String reason, String href) {
    return new PlatformAppealRelatedModule(id, label, delivered, ownerModule, reason, href);
  }

  private static PlatformAppealCapabilityAction action(
      String code, String label, boolean allowed, String method, String path, boolean required,
      String precondition, String blockingReason, String ownerModule, String unavailableReason) {
    return new PlatformAppealCapabilityAction(
        code, label, allowed, method, path, required, precondition, blockingReason, ownerModule,
        unavailableReason);
  }

  private static PlatformAppealBoundary boundary(String boundary, String ownerModule, String reason) {
    return new PlatformAppealBoundary(boundary, ownerModule, reason);
  }

  private static PlatformAppealAction action(
      String code,
      String label,
      boolean allowed,
      String method,
      String path,
      boolean required,
      String precondition,
      String blockedReason) {
    return new PlatformAppealAction(
        code, label, allowed, method, path, required, precondition, blockedReason);
  }

  // ================================================================ 载荷

  /** 待提交平台事实的载荷（**用具名结构**，不放 Map&lt;String,Object&gt;）。 */
  record PlatformAppealSubmissionPayload(
      String appealNo,
      String disputeNo,
      String platformCode,
      String platformDisputeNo,
      String appealText,
      String templateCode,
      String claimedCurrency,
      BigDecimal claimedAmount,
      List<PlatformAppealSubmissionMaterial> materials,
      String idempotencyKey,
      String submittedAt) {}

  /** 载荷里的一项材料。 */
  record PlatformAppealSubmissionMaterial(
      int seq,
      String evidenceType,
      String sourceModule,
      String sourceRefId,
      String sourceRefNo,
      String sourceLabel,
      String sourceLocator,
      String sourceOccurredAt) {}

  // ================================================================ 排序白名单

  /** 申诉单排序白名单。 */
  private enum PlatformAppealSort {
    DEADLINE_ASC("g.evidence_deadline asc nulls last, a.created_at desc, a.id desc"),
    CREATED_DESC("a.created_at desc, a.id desc"),
    AMOUNT_DESC("a.claimed_amount desc nulls last, a.created_at desc, a.id desc"),
    STATUS_ASC("a.appeal_status asc, a.created_at desc, a.id desc");

    private final String sql;

    PlatformAppealSort(String sql) {
      this.sql = sql;
    }

    String sql() {
      return sql;
    }

    static PlatformAppealSort parse(String raw) {
      if (raw == null) {
        return DEADLINE_ASC;
      }
      return switch (raw) {
        case "deadline_asc" -> DEADLINE_ASC;
        case "created_desc" -> CREATED_DESC;
        case "amount_desc" -> AMOUNT_DESC;
        case "status_asc" -> STATUS_ASC;
        default -> DEADLINE_ASC;
      };
    }
  }
}
