package com.starcon.channel;

import static com.starcon.channel.InfluencerOutreachModels.*;
import static com.starcon.channel.InfluencerOutreachRules.*;

import com.starcon.channel.vendor.InfluencerOutreachPort;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 14.5.1.2 建联与样品。
 *
 * <p>蓝图：「达人建联流程：按规则筛选达人 → 模板化私信邀约（走官方接口）→
 * 寄样管理（样品单、物流、签收）→ 合作跟进 → 佣金设置。输入：达人筛选条件；邀约模板；
 * 样品寄送信息；佣金比例。输出：建联记录 + 样品单 + 合作状态跟踪。关系：上游 = 14.5.1.1；
 * 下游 = 8 仓库（样品出库）、10 物流（样品物流）、12 财务（佣金计提）。
 * <b>建联必须走官方接口</b>，批量私信是平台风控重点，第三方工具易导致账号风险。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>按规则筛选达人</b> → {@link #segmentCandidates} / {@link #batchCreate}：
 *       筛选条件（{@code influencer_outreach_segment}）里存条件，
 *       真去 {@code influencer} 表上筛（不是把候选硬编码在代码里）。
 *   <li><b>模板化私信邀约</b> → {@link #saveTemplate} / {@link #templatePreview} /
 *       {@link #batchCreate}：模板是配置，渲染结果**快照**在建联记录上。
 *   <li><b>寄样管理</b> → {@link #saveSampleOrder} / {@link #createShipment} /
 *       {@link #transitionShipment}：样品单 + 包裹（1 : N）+ 签收。
 *   <li><b>合作跟进</b> → {@link #createFollowup}：追加型事实。
 *   <li><b>佣金设置</b> → {@link #saveCommissionRule} / {@link #createAccrual}：
 *       规则是配置，计提是事实。
 * </ul>
 *
 * <p>🔴 本模块最容易犯的五个错，这里逐个挡住：
 *
 * <ol>
 *   <li><b>把「没录」写成 0</b>。样品成本、佣金比例、计提基数没录就是 null（不可比），
 *       0 会被读成「这个达人一件样品成本都没有」。</li>
 *   <li><b>编一个平台私信接口</b>。平台适配层只有 OAuth 适配器，**没有达人私信适配器**
 *       ⇒ 私信端口对所有平台答「未接入」，发送入口据此<b>不写任何数据</b>。</li>
 *   <li><b>实现「批量群发」</b>。蓝图的红线是「建联必须走官方接口，批量私信是平台风控重点」。
 *       本模块<b>没有批量发送端点</b>：批量只做到「按规则批量生成草稿」，
 *       每一条仍要**逐条**登记平台回执才变成 SENT；并且登记时过
 *       <b>幂等键 + 每日上限 + 最小间隔</b>三道闸。</li>
 *   <li><b>假装下游已交付</b>。8 仓库 / 10 物流 / 12 财务在基线里都没有可调用的授权 API
 *       ⇒ 出库 / 物流 / 计提**只落本地事实**，并且每个缺口都在响应里显式标
 *       {@code available=false} + {@code ownerModule}。</li>
 *   <li><b>让人手填样品单的签收</b>。签收只能来自包裹
 *       （{@link InfluencerOutreachRules#deriveSampleOrderStatus}），
 *       人手改样品单只能改 REQUESTED → OUTBOUNDED / CANCELLED。</li>
 * </ol>
 *
 * <p>🔴 数据来源纪律：本模块**只读**同库的 {@code shop} / {@code platform} /
 * {@code influencer}（14.5.1.1）/ {@code ad_campaign}（14.1.1.1，写入一律落在自己的九张表上。
 * 达人**不塞进**建联记录 —— 建联记录是**事件**（一次邀约），达人是**档案**。
 */
@Service
public class InfluencerOutreachService {

  // ==================================================================
  // 常量：取值域与口径
  // ==================================================================

  private static final String TENANT =
      "nullif(current_setting('app.current_tenant',true),'')::uuid";

  /** 建联状态封闭词表（与 V289 的 CHECK 一致）。 */
  static final List<String> OUTREACH_STATUSES = InfluencerOutreachRules.OUTREACH_STATUSES;

  /** 样品单状态封闭词表（与 V289 的 CHECK 一致）。 */
  static final List<String> SAMPLE_ORDER_STATUSES = InfluencerOutreachRules.SAMPLE_ORDER_STATUSES;

  /** 包裹状态封闭词表（与 V289 的 CHECK 一致）。 */
  static final List<String> SHIPMENT_STATUSES = InfluencerOutreachRules.SHIPMENT_STATUSES;

  /** 跟进方式封闭词表（与 V289 的 CHECK 一致）。 */
  static final List<String> FOLLOWUP_TYPES = InfluencerOutreachRules.FOLLOWUP_TYPES;

  /** 佣金形态封闭词表（与 V289 的 CHECK 一致）。 */
  static final List<String> COMMISSION_TYPES = InfluencerOutreachRules.COMMISSION_TYPES;

  /** 佣金适用范围封闭词表（与 V289 的 CHECK 一致）。 */
  static final List<String> COMMISSION_SCOPES = InfluencerOutreachRules.COMMISSION_SCOPES;

  /** 计提状态封闭词表（与 V289 的 CHECK 一致）。 */
  static final List<String> ACCRUAL_STATUSES = InfluencerOutreachRules.ACCRUAL_STATUSES;

  /** 配置类事实的通用状态封闭词表。 */
  static final List<String> CONFIG_STATUSES = InfluencerOutreachRules.CONFIG_STATUSES;

  /** 粉丝层级封闭词表（与 V288 一致）。 */
  static final List<String> FOLLOWER_TIERS = InfluencerOutreachRules.FOLLOWER_TIERS;

  /** 排序白名单：**只认这几个键**，其余一律回落到默认排序（防注入）。 */
  private static final Map<String, String> OUTREACH_SORTS =
      Map.of(
          "CREATED_DESC", "o.created_at DESC, o.id",
          "SENT_DESC", "o.sent_at DESC NULLS LAST, o.id",
          "SENT_ASC", "o.sent_at ASC NULLS LAST, o.id",
          "STATUS_ASC", "o.status, o.id",
          "INFLUENCER_ASC", "i.display_name, o.id");

  /** 样品单排序白名单。 */
  private static final Map<String, String> SAMPLE_SORTS =
      Map.of(
          "CREATED_DESC", "so.created_at DESC, so.id",
          "CREATED_ASC", "so.created_at ASC, so.id",
          "STATUS_ASC", "so.status, so.id",
          "INFLUENCER_ASC", "i.display_name, so.id");

  /** 计提排序白名单。 */
  private static final Map<String, String> ACCRUAL_SORTS =
      Map.of(
          "ACCRUED_DESC", "ca.accrued_at DESC, ca.id",
          "AMOUNT_DESC", "ca.accrued_amount DESC, ca.id",
          "STATUS_ASC", "ca.status, ca.id");

  /** 默认每日发送上限（没有配置策略时用这个）。 */
  static final int DEFAULT_DAILY_SEND_LIMIT = 20;

  /** 默认最小间隔秒数（没有配置策略时用这个）。 */
  static final int DEFAULT_MIN_INTERVAL_SECONDS = 60;

  /** 批量建联的单次上限（防止一次拖走整张达人表）。 */
  static final int MAX_BATCH_SIZE = 200;

  // ==================================================================
  // 口径说明（随响应返回，界面上的说明与跨部门沟通用同一份定义）
  // ==================================================================

  static final String OFFICIAL_API_NOTE =
      "🔴 **建联必须走官方接口**（蓝图加粗句）：批量私信是平台风控重点，第三方工具易导致"
          + "账号风险。所以本模块**没有批量群发通道、没有第三方发送端点**，"
          + "建联记录的 `contact_channel` 被数据库钉死为 `OFFICIAL_API`。"
          + "平台达人私信官方接口**未接入**（channel 的适配层只有 OAuth 授权适配器），"
          + "所以发送入口会明确返回「未接入」并**不写任何数据** —— "
          + "**不编造一个假的平台接口，也不返回硬编码的发送成功**。"
          + "已交付的那一半是「手动登记已发送 + 平台回执」：运营在平台官方后台发出私信后，"
          + "把平台消息 id / 回执 / 发送时刻登记回来，登记时过**幂等键 + 每日上限 + 最小间隔**三道闸。";

  static final String RATE_LIMIT_NOTE =
      "🔴 **频率上限是配置事实**（`influencer_outreach_policy`，按平台配）："
          + "每日发送上限 + 两条发送之间的最小间隔秒数。超限直接拒绝并说清原因 —— "
          + "「批量私信是平台风控重点」，宁可少发也不要触发风控。"
          + "批量能力只做到「按规则批量生成**草稿**」，每一条仍要**逐条**登记回执才变成 SENT。";

  static final String DOWNSTREAM_NOTE =
      "🔴 **三个下游都未交付**：8 仓库（样品出库）、10 物流（样品物流）、"
          + "12 财务（佣金计提）在当前仓库里**都没有可调用的授权 API**"
          + "（finance 服务不存在，warehouse 没有达人样品出库端点，物流没有达人样品轨迹端点）。"
          + "所以样品出库 / 物流 / 佣金计提**只在本服务内留本地事实**，"
          + "并且每个缺口都在数据里显式标出："
          + "样品的 `tracking_available` **恒为 false**（轨迹不可查，归 10 物流）、"
          + "样品的出库是 `LOCAL_PENDING` / `LOCAL_OUTBOUNDED` 的**本地**状态（归 8 仓库）、"
          + "计提的 `finance_posted` **恒为 false**（未进费用归集，归 12 财务）。"
          + "**没有跨库外键、没有跨库视图、没有跨服务 SQL。**";

  static final String UPSTREAM_NOTE =
      "上游 = 14.5.1.1 达人库（`influencer`，同库同服务，复合 FK 带 tenant_id "
          + "⇒ 跨租户挂载物理上不可能）+ 2.1.1.1 platform + 2.3.1.1 shop；"
          + "选品候选经 catalog 的授权 HTTP API 只读（`GET /catalog/skus`，逻辑引用 + 快照，零跨库 FK）。";

  static final String BOUNDARY_NOTE =
      "本模块**只做**建联与样品：筛选条件 / 邀约模板 / 建联记录 / 样品单与包裹 / 合作跟进 / 佣金规则与计提。"
          + "**不做**：达人档案与标签评级（14.5.1.1）、带货归因与效果报表（14.5.2.1）、"
          + "平台达人数据同步（14.5.1.1 的未交付端口）。"
          + "提成基数是**人工登记**的归因 GMV —— 归因口径归 14.5.2.1，未交付。";

  private final DSLContext sql;
  private final InfluencerOutreachPort platformOutreach;

  public InfluencerOutreachService(DSLContext sql, InfluencerOutreachPort platformOutreach) {
    this.sql = sql;
    this.platformOutreach = platformOutreach;
  }

  // ==================================================================
  // 小工具（照抄 InfluencerService 的同名写法）
  // ==================================================================

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private long count(String query, Object... binds) {
    Long v = one(Long.class, query, binds);
    return v == null ? 0L : v;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    String trimmed = text(value);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String escapeLike(String raw) {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static Object[] concat(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) {
      all[i] = binds.get(i);
    }
    System.arraycopy(tail, 0, all, binds.size(), tail.length);
    return all;
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 绑定，不来自任何客户端头）。 */
  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 跨租户与不存在返回**逐字相同**的 404（照抄 10.1.1.2 / 10.1.2.1 同款口径）。 */
  private ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在");
  }

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static UUID parseId(String id) {
    try {
      return UUID.fromString(id.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id 不是合法 UUID");
    }
  }

  private static int safeSize(int size) {
    return Math.min(Math.max(size, 1), 200);
  }

  private static int safePage(int page) {
    return Math.max(page, 0);
  }

  /** 校验取值在封闭词表里（不在 ⇒ 400 并说清合法取值）。 */
  private static String requireEnum(String value, List<String> allowed, String what) {
    String normalized = text(value).toUpperCase(Locale.ROOT);
    if (!allowed.contains(normalized)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, what + "不合法：" + value + "（合法取值：" + String.join(" / ", allowed) + "）");
    }
    return normalized;
  }

  /** 规范化币种（3 位字母）；空串表示「没录币种」。 */
  private static String normalizeCurrency(String currency) {
    String normalized = text(currency).toUpperCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      return "";
    }
    if (normalized.length() != 3) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是 3 位字母代码");
    }
    return normalized;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public InfluencerOutreachCapabilities capabilities() {
    return new InfluencerOutreachCapabilities(
        List.of(
            new InfluencerOutreachCapabilityDimension(
                "influencer_segment",
                "按规则筛选达人：条件 + 真筛选（候选预览 + 批量建联都走它）",
                true,
                "influencer_outreach_segment（V289）+ influencer（V288）",
                "",
                "没有筛选条件就只剩「手动一个个找达人」，建联流程的第一步就断了"),
            new InfluencerOutreachCapabilityDimension(
                "outreach_template",
                "模板化私信邀约：模板配置 + 变量闭合词表 + 渲染结果快照",
                true,
                "influencer_outreach_template（V289）",
                "",
                "模板化才能保证口径一致；渲染结果快照保证「改模板不改历史」"),
            new InfluencerOutreachCapabilityDimension(
                "official_outreach_api",
                "🔴 **平台达人私信官方接口**",
                false,
                "—",
                "平台适配层（channel/vendor：只有 OAuth 适配器，没有达人私信适配器）",
                "私信**发不出去**：发送入口明确返回「未接入」并**不写任何数据**。"
                    + "已交付的那一半是「手动登记已发送 + 平台回执」"),
            new InfluencerOutreachCapabilityDimension(
                "outreach_record",
                "建联记录 + 合作状态跟踪（状态机 + 幂等键 + 频率上限）",
                true,
                "influencer_outreach（V289）",
                "",
                "没有建联记录就答不出「这个达人邀过没有、现在谈到哪一步了」"),
            new InfluencerOutreachCapabilityDimension(
                "sample_management",
                "寄样管理：样品单 + 包裹（发货 / 物流单号 / 签收）",
                true,
                "influencer_sample_order / influencer_sample_shipment（V289）",
                "",
                "样品单与包裹分开才答得出「这单里的东西是不是都到了」"),
            new InfluencerOutreachCapabilityDimension(
                "followup",
                "合作跟进：追加型跟进记录 + 下次跟进时刻",
                true,
                "influencer_outreach_followup（V289）",
                "",
                "跟进不记就只剩一条状态，建联就成了无头的流程"),
            new InfluencerOutreachCapabilityDimension(
                "commission_setting",
                "佣金设置：规则（比例 / 固定额）+ 计提事实",
                true,
                "influencer_commission_rule / influencer_commission_accrual（V289）",
                "",
                "佣金口径不落库，每次谈都要重新算，且算不清历史"),
            new InfluencerOutreachCapabilityDimension(
                "warehouse_outbound",
                "🔴 **8 仓库（达人样品出库）**",
                false,
                "—",
                "8 仓库（基线里没有可调用的达人样品出库授权 API）",
                "样品出库**只是本地事实**（`LOCAL_PENDING` / `LOCAL_OUTBOUNDED`），"
                    + "仓库那边的真实单号**拿不到**，要等 8 仓库交付后回填"),
            new InfluencerOutreachCapabilityDimension(
                "logistics_tracking",
                "🔴 **10 物流（达人样品轨迹）**",
                false,
                "—",
                "10 物流（基线里没有可调用的达人样品轨迹授权 API）",
                "真实物流轨迹**不可查**（`tracking_available` 恒为 false），"
                    + "只留本地登记的物流单号"),
            new InfluencerOutreachCapabilityDimension(
                "finance_commission",
                "🔴 **12 财务（佣金入账 / 费用归集）**",
                false,
                "—",
                "12 财务（当前仓库**没有 finance 服务**）",
                "佣金计提**未进费用归集**（`finance_posted` 恒为 false），"
                    + "达人佣金成本会暂时不进利润表 —— 归因口径归 14.5.2.1，也未交付")),
        OUTREACH_STATUSES,
        SAMPLE_ORDER_STATUSES,
        SHIPMENT_STATUSES,
        FOLLOWUP_TYPES,
        COMMISSION_TYPES,
        templateVariableModels(),
        OFFICIAL_API_NOTE,
        RATE_LIMIT_NOTE,
        DOWNSTREAM_NOTE,
        UPSTREAM_NOTE,
        BOUNDARY_NOTE);
  }

  private static List<InfluencerOutreachTemplateVariable> templateVariableModels() {
    return TEMPLATE_VARIABLES.stream()
        .map(v -> new InfluencerOutreachTemplateVariable(v.name(), v.label(), v.required()))
        .toList();
  }

  // ==================================================================
  // 选项
  // ==================================================================

  public InfluencerOutreachOptions options() {
    List<InfluencerOutreachPlatformOption> platforms =
        sql.fetch(
                "select distinct pf.id, pf.code, pf.name from shop s"
                    + " join platform pf on pf.id = s.platform_id"
                    + " where s.tenant_id = "
                    + TENANT
                    + " order by pf.code")
            .stream()
            .map(
                r ->
                    new InfluencerOutreachPlatformOption(
                        r.get("id", UUID.class), r.get("code", String.class), r.get("name", String.class)))
            .toList();

    List<String> shopCodes =
        sql.fetch("select distinct code from shop where tenant_id = " + TENANT + " order by code")
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();

    return new InfluencerOutreachOptions(
        optionList(OUTREACH_STATUSES, "建联状态"),
        optionList(SAMPLE_ORDER_STATUSES, "样品单状态"),
        optionList(SHIPMENT_STATUSES, "包裹状态"),
        optionList(FOLLOWUP_TYPES, "跟进方式"),
        optionList(COMMISSION_TYPES, "佣金形态"),
        optionList(COMMISSION_SCOPES, "佣金适用范围"),
        optionList(ACCRUAL_STATUSES, "计提状态"),
        platforms,
        platforms.stream().map(InfluencerOutreachPlatformOption::code).toList(),
        shopCodes,
        templateVariableModels(),
        OFFICIAL_API_NOTE,
        RATE_LIMIT_NOTE,
        DOWNSTREAM_NOTE);
  }

  private static List<InfluencerOutreachOption> optionList(List<String> codes, String label) {
    return codes.stream().map(code -> new InfluencerOutreachOption(code, label + "：" + code)).toList();
  }

  // ==================================================================
  // 概览
  // ==================================================================

  public InfluencerOutreachSummary summary() {
    long total = count("select count(*) from influencer_outreach where tenant_id = " + TENANT);
    long draft =
        count(
            "select count(*) from influencer_outreach where tenant_id = "
                + TENANT
                + " and status = 'DRAFT'");
    long sent =
        count(
            "select count(*) from influencer_outreach where tenant_id = "
                + TENANT
                + " and status = 'SENT'");
    long replied =
        count(
            "select count(*) from influencer_outreach where tenant_id = "
                + TENANT
                + " and status = 'REPLIED'");
    long cooperating =
        count(
            "select count(*) from influencer_outreach where tenant_id = "
                + TENANT
                + " and status = 'COOPERATING'");
    long completed =
        count(
            "select count(*) from influencer_outreach where tenant_id = "
                + TENANT
                + " and status = 'COMPLETED'");
    long closed =
        count(
            "select count(*) from influencer_outreach where tenant_id = "
                + TENANT
                + " and status in ('REJECTED','NO_REPLY','CANCELLED')");
    long sampleCount =
        count("select count(*) from influencer_sample_order where tenant_id = " + TENANT);
    long signed =
        count(
            "select count(*) from influencer_sample_order where tenant_id = "
                + TENANT
                + " and status = 'SIGNED'");
    long pendingSign =
        count(
            "select count(*) from influencer_sample_order where tenant_id = "
                + TENANT
                + " and status in ('REQUESTED','OUTBOUNDED','DELIVERED')");
    long accrualCount =
        count("select count(*) from influencer_commission_accrual where tenant_id = " + TENANT);

    // 🔴 金额合计**只在单一币种时给**：跨币种加成一个总数是假的口径。
    List<String> currencies =
        sql.fetch(
                "select distinct accrued_currency from influencer_commission_accrual"
                    + " where tenant_id = "
                    + TENANT
                    + " and accrued_currency <> '' order by accrued_currency")
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();
    BigDecimal accruedTotal = null;
    String accruedCurrency = "";
    if (currencies.size() == 1) {
      accruedCurrency = currencies.get(0);
      accruedTotal =
          one(
              BigDecimal.class,
              "select sum(accrued_amount) from influencer_commission_accrual where tenant_id = "
                  + TENANT
                  + " and accrued_currency = ?",
              accruedCurrency);
      if (accruedTotal != null) {
        accruedTotal = accruedTotal.setScale(InfluencerOutreachRules.MONEY_SCALE, RoundingMode.HALF_UP);
      }
    }

    return new InfluencerOutreachSummary(
        total,
        draft,
        sent,
        replied,
        cooperating,
        completed,
        closed,
        sampleCount,
        signed,
        pendingSign,
        accrualCount,
        accruedTotal,
        accruedCurrency,
        DOWNSTREAM_NOTE,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  // ==================================================================
  // 筛选条件（配置）
  // ==================================================================

  public InfluencerOutreachSegmentPage segments(String status, String keyword, int page, int size) {
    StringBuilder where = new StringBuilder(" where s.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and s.status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (s.segment_code ilike ? or s.segment_name ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }
    int safeSize = safeSize(size);
    int safePage = safePage(page);
    long total = count("select count(*) from influencer_outreach_segment s" + where, binds.toArray());
    List<InfluencerOutreachSegmentRow> items =
        sql.fetch(
                segmentSelect()
                    + where
                    + " order by s.segment_code, s.id limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::segmentRow)
            .toList();
    return new InfluencerOutreachSegmentPage(items, total, safePage, safeSize);
  }

  private String segmentSelect() {
    return "select s.id, s.segment_code, s.segment_name, s.description, s.category,"
        + " s.follower_tier, s.min_follower_count, s.min_engagement_rate, s.min_gmv,"
        + " s.rating_tier_code, s.tag_codes, s.platform_id,"
        + " (select pf.code from platform pf where pf.id = s.platform_id) platform_code,"
        + " s.shop_code, s.exclude_blacklisted, s.status, s.note, s.version,"
        + " s.created_at, s.updated_at from influencer_outreach_segment s";
  }

  private InfluencerOutreachSegmentRow segmentRow(Record r) {
    return new InfluencerOutreachSegmentRow(
        r.get("id", UUID.class),
        r.get("segment_code", String.class),
        r.get("segment_name", String.class),
        r.get("description", String.class),
        r.get("category", String.class),
        r.get("follower_tier", String.class),
        r.get("min_follower_count", Long.class),
        r.get("min_engagement_rate", BigDecimal.class),
        r.get("min_gmv", BigDecimal.class),
        r.get("rating_tier_code", String.class),
        r.get("tag_codes", String.class),
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        Boolean.TRUE.equals(r.get("exclude_blacklisted", Boolean.class)),
        r.get("status", String.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  public InfluencerOutreachSegmentRow saveSegment(SaveInfluencerOutreachSegmentRequest request) {
    String code = text(request.segmentCode());
    if (code.isEmpty()) {
      throw badRequest("筛选条件编码不能为空");
    }
    String name = text(request.segmentName());
    if (name.isEmpty()) {
      throw badRequest("筛选条件名称不能为空");
    }
    String status = text(request.status()).isEmpty() ? "ACTIVE" : text(request.status()).toUpperCase(Locale.ROOT);
    if (!CONFIG_STATUSES.contains(status)) {
      throw badRequest("状态不合法：" + request.status());
    }
    String tier = text(request.followerTier()).toUpperCase(Locale.ROOT);
    if (!tier.isEmpty() && !FOLLOWER_TIERS.contains(tier)) {
      throw badRequest("粉丝层级不合法：" + request.followerTier());
    }
    if (request.minEngagementRate() != null
        && (request.minEngagementRate().signum() < 0
            || request.minEngagementRate().compareTo(BigDecimal.ONE) > 0)) {
      throw badRequest("互动率下限必须在 0 ~ 1 之间");
    }
    if (request.minGmv() != null && request.minGmv().signum() < 0) {
      throw badRequest("GMV 下限不能是负数");
    }
    if (request.minFollowerCount() != null && request.minFollowerCount() < 0) {
      throw badRequest("粉丝数下限不能是负数");
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into influencer_outreach_segment (tenant_id,id,segment_code,segment_name,"
            + " description,category,follower_tier,min_follower_count,min_engagement_rate,min_gmv,"
            + " rating_tier_code,tag_codes,platform_id,shop_code,exclude_blacklisted,status,note,"
            + " version,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,now(),now())",
        currentTenant(),
        id,
        code,
        name,
        text(request.description()),
        text(request.category()),
        tier,
        request.minFollowerCount(),
        request.minEngagementRate(),
        request.minGmv(),
        text(request.ratingTierCode()).toUpperCase(Locale.ROOT),
        text(request.tagCodes()),
        request.platformId(),
        text(request.shopCode()),
        request.excludeBlacklisted() == null || request.excludeBlacklisted(),
        status,
        text(request.note()));
    return segmentById(id);
  }

  public InfluencerOutreachSegmentRow updateSegment(
      String id, SaveInfluencerOutreachSegmentRequest request) {
    UUID segmentId = parseId(id);
    if (segmentRecord(segmentId) == null) {
      throw notFound("筛选条件");
    }
    String code = text(request.segmentCode());
    if (code.isEmpty()) {
      throw badRequest("筛选条件编码不能为空");
    }
    String name = text(request.segmentName());
    if (name.isEmpty()) {
      throw badRequest("筛选条件名称不能为空");
    }
    String status = text(request.status()).isEmpty() ? "ACTIVE" : text(request.status()).toUpperCase(Locale.ROOT);
    if (!CONFIG_STATUSES.contains(status)) {
      throw badRequest("状态不合法：" + request.status());
    }
    String tier = text(request.followerTier()).toUpperCase(Locale.ROOT);
    if (!tier.isEmpty() && !FOLLOWER_TIERS.contains(tier)) {
      throw badRequest("粉丝层级不合法：" + request.followerTier());
    }
    if (request.minEngagementRate() != null
        && (request.minEngagementRate().signum() < 0
            || request.minEngagementRate().compareTo(BigDecimal.ONE) > 0)) {
      throw badRequest("互动率下限必须在 0 ~ 1 之间");
    }
    if (request.minGmv() != null && request.minGmv().signum() < 0) {
      throw badRequest("GMV 下限不能是负数");
    }
    if (request.minFollowerCount() != null && request.minFollowerCount() < 0) {
      throw badRequest("粉丝数下限不能是负数");
    }
    sql.execute(
        "update influencer_outreach_segment set segment_code = ?, segment_name = ?,"
            + " description = ?, category = ?, follower_tier = ?, min_follower_count = ?,"
            + " min_engagement_rate = ?, min_gmv = ?, rating_tier_code = ?, tag_codes = ?,"
            + " platform_id = ?, shop_code = ?, exclude_blacklisted = ?, status = ?, note = ?,"
            + " updated_at = now(), version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        code,
        name,
        text(request.description()),
        text(request.category()),
        tier,
        request.minFollowerCount(),
        request.minEngagementRate(),
        request.minGmv(),
        text(request.ratingTierCode()).toUpperCase(Locale.ROOT),
        text(request.tagCodes()),
        request.platformId(),
        text(request.shopCode()),
        request.excludeBlacklisted() == null || request.excludeBlacklisted(),
        status,
        text(request.note()),
        currentTenant(),
        segmentId);
    return segmentById(segmentId);
  }

  /** 删一个筛选条件（被建联记录或佣金规则引用时返回 409）。 */
  public void deleteSegment(String id) {
    UUID segmentId = parseId(id);
    if (segmentRecord(segmentId) == null) {
      throw notFound("筛选条件");
    }
    long used =
        count(
            "select count(*) from influencer_outreach where tenant_id = " + TENANT + " and segment_id = ?",
            segmentId);
    if (used > 0) {
      throw conflict("该筛选条件已被建联记录引用（举证凭据），不能物理删除。请改为停用（status=PAUSED）。");
    }
    long ruleUsed =
        count(
            "select count(*) from influencer_commission_rule where tenant_id = "
                + TENANT
                + " and segment_id = ?",
            segmentId);
    if (ruleUsed > 0) {
      throw conflict("该筛选条件已被佣金规则引用，不能物理删除。请改为停用（status=PAUSED）。");
    }
    sql.execute(
        "delete from influencer_outreach_segment where tenant_id = ?::uuid and id = ?::uuid",
        currentTenant(),
        segmentId);
  }

  private Record segmentRecord(UUID segmentId) {
    return sql.fetchOne(
        "select id, segment_code, segment_name, platform_id, status from influencer_outreach_segment"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        segmentId);
  }

  private InfluencerOutreachSegmentRow segmentById(UUID segmentId) {
    Record r =
        sql.fetchOne(segmentSelect() + " where s.tenant_id = " + TENANT + " and s.id = ?", segmentId);
    if (r == null) {
      throw notFound("筛选条件");
    }
    return segmentRow(r);
  }

  // ==================================================================
  // 按规则筛选达人（候选预览）
  // ==================================================================

  /**
   * 按筛选条件预览匹配的达人（**真筛选**，不是写死的候选列表）。
   *
   * <p>🔴 这就是蓝图第一步「按规则筛选达人」：条件存在 {@code influencer_outreach_segment}，
   *   真去 {@code influencer} 表上筛。
   */
  public InfluencerOutreachCandidatePage segmentCandidates(
      String id, String keyword, int page, int size) {
    UUID segmentId = parseId(id);
    Record segment = segmentRecord(segmentId);
    if (segment == null) {
      throw notFound("筛选条件");
    }
    SegmentCriteria criteria = criteriaOf(segmentId);
    StringBuilder where = new StringBuilder(" where i.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    applyCriteria(where, binds, criteria);
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (i.display_name ilike ? or i.handle ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }
    int safeSize = safeSize(size);
    int safePage = safePage(page);
    long total = count("select count(*) from influencer i" + where, binds.toArray());
    List<InfluencerOutreachCandidate> items =
        sql.fetch(
                "select i.id, i.display_name, i.handle, i.category, i.follower_count,"
                    + " i.follower_tier, i.engagement_rate, i.gmv, i.gmv_currency,"
                    + " (select pf.code from platform pf where pf.id = i.platform_id) platform_code,"
                    + " i.shop_code,"
                    + " (select count(*) from influencer_blacklist b where b.tenant_id = i.tenant_id"
                    + "   and b.influencer_id = i.id and b.status = 'ACTIVE') blacklisted_count,"
                    + " (select count(*) from influencer_outreach o where o.tenant_id = i.tenant_id"
                    + "   and o.influencer_id = i.id"
                    + "   and o.status in ('DRAFT','SENT','REPLIED','ACCEPTED','COOPERATING')) open_outreach_count"
                    + " from influencer i"
                    + where
                    + " order by i.gmv desc nulls last, i.follower_count desc, i.id"
                    + " limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(
                r ->
                    new InfluencerOutreachCandidate(
                        r.get("id", UUID.class),
                        r.get("display_name", String.class),
                        r.get("handle", String.class),
                        r.get("category", String.class),
                        r.get("follower_count", Long.class) == null ? 0L : r.get("follower_count", Long.class),
                        r.get("follower_tier", String.class),
                        r.get("engagement_rate", BigDecimal.class),
                        r.get("gmv", BigDecimal.class),
                        r.get("gmv_currency", String.class),
                        r.get("platform_code", String.class),
                        r.get("shop_code", String.class),
                        (r.get("blacklisted_count", Integer.class) == null
                                ? 0
                                : r.get("blacklisted_count", Integer.class))
                            > 0,
                        (r.get("open_outreach_count", Integer.class) == null
                                ? 0
                                : r.get("open_outreach_count", Integer.class))
                            > 0))
            .toList();
    return new InfluencerOutreachCandidatePage(items, total, safePage, safeSize);
  }

  /** 筛选条件的内部形状（读出来好复用）。 */
  private record SegmentCriteria(
      String category,
      String followerTier,
      Long minFollowerCount,
      BigDecimal minEngagementRate,
      BigDecimal minGmv,
      String ratingTierCode,
      List<String> tagCodes,
      UUID platformId,
      String shopCode,
      boolean excludeBlacklisted) {}

  private SegmentCriteria criteriaOf(UUID segmentId) {
    Record r =
        sql.fetchOne(
            "select category, follower_tier, min_follower_count, min_engagement_rate, min_gmv,"
                + " rating_tier_code, tag_codes, platform_id, shop_code, exclude_blacklisted"
                + " from influencer_outreach_segment where tenant_id = "
                + TENANT
                + " and id = ?",
            segmentId);
    if (r == null) {
      throw notFound("筛选条件");
    }
    return new SegmentCriteria(
        r.get("category", String.class),
        r.get("follower_tier", String.class),
        r.get("min_follower_count", Long.class),
        r.get("min_engagement_rate", BigDecimal.class),
        r.get("min_gmv", BigDecimal.class),
        r.get("rating_tier_code", String.class),
        splitCodes(r.get("tag_codes", String.class)),
        r.get("platform_id", UUID.class),
        r.get("shop_code", String.class),
        !Boolean.FALSE.equals(r.get("exclude_blacklisted", Boolean.class)));
  }

  private static List<String> splitCodes(String csv) {
    List<String> out = new ArrayList<>();
    if (csv == null) {
      return out;
    }
    for (String part : csv.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out;
  }

  /**
   * 把筛选条件翻译成 SQL 条件。
   *
   * <p>🔴 条件之间是 <b>AND</b>（交集）；标签编码之间是 <b>OR</b>（池子的并集）。
   *   「还没录」的效果数据（null）**不会被下限命中** —— 互动率没录的达人
   *   不满足「互动率 ≥ 3%」，这是对的（不可比 ≠ 达标）。
   */
  private void applyCriteria(
      StringBuilder where, List<Object> binds, SegmentCriteria criteria) {
    String category = blankToNull(criteria.category());
    if (category != null) {
      where.append(" and i.category = ?");
      binds.add(category);
    }
    String tier = blankToNull(criteria.followerTier());
    if (tier != null) {
      where.append(" and i.follower_tier = ?");
      binds.add(tier);
    }
    if (criteria.minFollowerCount() != null) {
      where.append(" and i.follower_count >= ?");
      binds.add(criteria.minFollowerCount());
    }
    if (criteria.minEngagementRate() != null) {
      where.append(" and i.engagement_rate >= ?");
      binds.add(criteria.minEngagementRate());
    }
    if (criteria.minGmv() != null) {
      where.append(" and i.gmv >= ?");
      binds.add(criteria.minGmv());
    }
    String ratingTier = blankToNull(criteria.ratingTierCode());
    if (ratingTier != null) {
      where.append(
          " and exists (select 1 from influencer_rating_tier rt where rt.tenant_id = i.tenant_id"
              + " and rt.id = i.rating_tier_id and rt.tier_code = ?)");
      binds.add(ratingTier.toUpperCase(Locale.ROOT));
    }
    if (!criteria.tagCodes().isEmpty()) {
      where.append(
          " and exists (select 1 from influencer_tag_assignment a"
              + " join influencer_tag t on t.tenant_id = a.tenant_id and t.id = a.tag_id"
              + " where a.tenant_id = i.tenant_id and a.influencer_id = i.id"
              + " and t.tag_code in ("
              + String.join(",", java.util.Collections.nCopies(criteria.tagCodes().size(), "?"))
              + "))");
      binds.addAll(criteria.tagCodes());
    }
    if (criteria.platformId() != null) {
      where.append(" and i.platform_id = ?");
      binds.add(criteria.platformId());
    }
    String shop = blankToNull(criteria.shopCode());
    if (shop != null) {
      where.append(" and i.shop_code = ?");
      binds.add(shop);
    }
    if (criteria.excludeBlacklisted()) {
      where.append(
          " and not exists (select 1 from influencer_blacklist b"
              + " where b.tenant_id = i.tenant_id and b.influencer_id = i.id and b.status = 'ACTIVE')");
    }
    // 🔴 停用的达人不进建联池（停用 = 不再合作，与拉黑是两件事）。
    where.append(" and i.status = 'ACTIVE'");
  }

  // ==================================================================
  // 邀约模板（配置）
  // ==================================================================

  public InfluencerOutreachTemplatePage templates(
      String platformCode, String status, String keyword, int page, int size) {
    StringBuilder where = new StringBuilder(" where t.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    String platform = blankToNull(platformCode);
    if (platform != null) {
      where.append(" and t.platform_id = (select id from platform where code = ?)");
      binds.add(platform.toUpperCase(Locale.ROOT));
    }
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and t.status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (t.template_code ilike ? or t.template_name ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }
    int safeSize = safeSize(size);
    int safePage = safePage(page);
    long total = count("select count(*) from influencer_outreach_template t" + where, binds.toArray());
    List<InfluencerOutreachTemplateRow> items =
        sql.fetch(
                templateSelect()
                    + where
                    + " order by t.template_code, t.id limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::templateRow)
            .toList();
    return new InfluencerOutreachTemplatePage(items, total, safePage, safeSize);
  }

  private String templateSelect() {
    return "select t.id, t.template_code, t.template_name, t.platform_id,"
        + " pf.code platform_code, pf.name platform_name, t.subject, t.body, t.status, t.note,"
        + " t.version, t.created_at, t.updated_at"
        + " from influencer_outreach_template t join platform pf on pf.id = t.platform_id";
  }

  private InfluencerOutreachTemplateRow templateRow(Record r) {
    return new InfluencerOutreachTemplateRow(
        r.get("id", UUID.class),
        r.get("template_code", String.class),
        r.get("template_name", String.class),
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("platform_name", String.class),
        r.get("subject", String.class),
        r.get("body", String.class),
        r.get("status", String.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  public InfluencerOutreachTemplateRow saveTemplate(SaveInfluencerOutreachTemplateRequest request) {
    String code = text(request.templateCode());
    String name = text(request.templateName());
    if (code.isEmpty() || name.isEmpty()) {
      throw badRequest("模板编码与模板名称都不能为空");
    }
    if (text(request.body()).isEmpty()) {
      throw badRequest("模板正文不能为空");
    }
    if (!platformExists(request.platformId())) {
      throw badRequest("平台不存在");
    }
    String status =
        text(request.status()).isEmpty() ? "ACTIVE" : text(request.status()).toUpperCase(Locale.ROOT);
    if (!CONFIG_STATUSES.contains(status)) {
      throw badRequest("状态不合法：" + request.status());
    }
    // 🔴 模板里的占位符必须是闭合词表里的 —— 写错的变量名在这里就被拒，
    //   不等到发出去才发现消息里有一串 {{xxx}}。
    validatePlaceholders(text(request.subject()));
    validatePlaceholders(text(request.body()));
    UUID id = Ids.next();
    sql.execute(
        "insert into influencer_outreach_template (tenant_id,id,template_code,template_name,"
            + " platform_id,subject,body,status,note,version,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,0,now(),now())",
        currentTenant(),
        id,
        code,
        name,
        request.platformId(),
        text(request.subject()),
        text(request.body()),
        status,
        text(request.note()));
    return templateById(id);
  }

  public InfluencerOutreachTemplateRow updateTemplate(
      String id, SaveInfluencerOutreachTemplateRequest request) {
    UUID templateId = parseId(id);
    if (templateRecord(templateId) == null) {
      throw notFound("邀约模板");
    }
    String code = text(request.templateCode());
    String name = text(request.templateName());
    if (code.isEmpty() || name.isEmpty()) {
      throw badRequest("模板编码与模板名称都不能为空");
    }
    if (text(request.body()).isEmpty()) {
      throw badRequest("模板正文不能为空");
    }
    if (!platformExists(request.platformId())) {
      throw badRequest("平台不存在");
    }
    String status =
        text(request.status()).isEmpty() ? "ACTIVE" : text(request.status()).toUpperCase(Locale.ROOT);
    if (!CONFIG_STATUSES.contains(status)) {
      throw badRequest("状态不合法：" + request.status());
    }
    validatePlaceholders(text(request.subject()));
    validatePlaceholders(text(request.body()));
    sql.execute(
        "update influencer_outreach_template set template_code = ?, template_name = ?, platform_id = ?,"
            + " subject = ?, body = ?, status = ?, note = ?, updated_at = now(), version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        code,
        name,
        request.platformId(),
        text(request.subject()),
        text(request.body()),
        status,
        text(request.note()),
        currentTenant(),
        templateId);
    return templateById(templateId);
  }

  /** 删一个模板（被建联记录引用时返回 409）。 */
  public void deleteTemplate(String id) {
    UUID templateId = parseId(id);
    if (templateRecord(templateId) == null) {
      throw notFound("邀约模板");
    }
    long used =
        count(
            "select count(*) from influencer_outreach where tenant_id = " + TENANT + " and template_id = ?",
            templateId);
    if (used > 0) {
      throw conflict("该模板已被建联记录引用（举证凭据），不能物理删除。请改为停用（status=PAUSED）。");
    }
    sql.execute(
        "delete from influencer_outreach_template where tenant_id = ?::uuid and id = ?::uuid",
        currentTenant(),
        templateId);
  }

  private Record templateRecord(UUID templateId) {
    return sql.fetchOne(
        "select id, template_code, platform_id, subject, body, status"
            + " from influencer_outreach_template where tenant_id = "
            + TENANT
            + " and id = ?",
        templateId);
  }

  private InfluencerOutreachTemplateRow templateById(UUID templateId) {
    Record r =
        sql.fetchOne(templateSelect() + " where t.tenant_id = " + TENANT + " and t.id = ?", templateId);
    if (r == null) {
      throw notFound("邀约模板");
    }
    return templateRow(r);
  }

  private boolean platformExists(UUID platformId) {
    return one(UUID.class, "select id from platform where id = ?", platformId) != null;
  }

  /** 校验模板里的占位符都在闭合词表里。 */
  private static void validatePlaceholders(String text) {
    if (text == null || text.isEmpty()) {
      return;
    }
    int cursor = 0;
    while (cursor < text.length()) {
      int open = text.indexOf("{{", cursor);
      if (open < 0) {
        return;
      }
      int close = text.indexOf("}}", open + 2);
      if (close < 0) {
        throw badRequest("模板里有没写完的占位符（缺少 }}）");
      }
      String name = text.substring(open + 2, close).trim();
      if (!isKnownVariable(name)) {
        throw badRequest("模板里有未登记的占位符：" + name);
      }
      cursor = close + 2;
    }
  }

  /**
   * 模板预览（真渲染）。
   *
   * <p>🔴 预览时如果**达人的平台侧 id 没登记**，渲染仍然成功 —— 渲染只用到
   *   档案里的字段，不依赖平台接口。
   */
  public InfluencerOutreachTemplatePreview templatePreview(String id, UUID influencerId) {
    UUID templateId = parseId(id);
    Record template = templateRecord(templateId);
    if (template == null) {
      throw notFound("邀约模板");
    }
    if (influencerId == null) {
      throw badRequest("预览需要指定达人");
    }
    Record influencer = influencerRecord(influencerId);
    if (influencer == null) {
      throw notFound("达人");
    }
    Map<String, String> values = renderValues(template, influencer, null);
    String subject;
    String body;
    try {
      subject = render(text(template.get("subject", String.class)), values);
      body = render(text(template.get("body", String.class)), values);
    } catch (IllegalArgumentException e) {
      throw badRequest(e.getMessage());
    }
    return new InfluencerOutreachTemplatePreview(templateId, subject, body, values, OFFICIAL_API_NOTE);
  }

  // ==================================================================
  // 风控策略（配置）
  // ==================================================================

  public InfluencerOutreachPolicyPage policies(String platformCode, int page, int size) {
    StringBuilder where = new StringBuilder(" where p.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    String platform = blankToNull(platformCode);
    if (platform != null) {
      where.append(" and p.platform_id = (select id from platform where code = ?)");
      binds.add(platform.toUpperCase(Locale.ROOT));
    }
    int safeSize = safeSize(size);
    int safePage = safePage(page);
    long total = count("select count(*) from influencer_outreach_policy p" + where, binds.toArray());
    List<InfluencerOutreachPolicyRow> items =
        sql.fetch(
                policySelect()
                    + where
                    + " order by pf.code, p.id limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::policyRow)
            .toList();
    return new InfluencerOutreachPolicyPage(items, total, safePage, safeSize);
  }

  private String policySelect() {
    return "select p.id, p.platform_id, pf.code platform_code, pf.name platform_name,"
        + " p.daily_send_limit, p.min_interval_seconds, p.status, p.note, p.version,"
        + " p.created_at, p.updated_at"
        + " from influencer_outreach_policy p join platform pf on pf.id = p.platform_id";
  }

  private InfluencerOutreachPolicyRow policyRow(Record r) {
    return new InfluencerOutreachPolicyRow(
        r.get("id", UUID.class),
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("platform_name", String.class),
        r.get("daily_send_limit", Integer.class) == null ? 0 : r.get("daily_send_limit", Integer.class),
        r.get("min_interval_seconds", Integer.class) == null
            ? 0
            : r.get("min_interval_seconds", Integer.class),
        r.get("status", String.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 保存风控策略（**按平台 upsert**：一个平台一条）。 */
  public InfluencerOutreachPolicyRow savePolicy(SaveInfluencerOutreachPolicyRequest request) {
    if (!platformExists(request.platformId())) {
      throw badRequest("平台不存在");
    }
    int dailyLimit =
        request.dailySendLimit() == null ? DEFAULT_DAILY_SEND_LIMIT : request.dailySendLimit();
    int minInterval =
        request.minIntervalSeconds() == null
            ? DEFAULT_MIN_INTERVAL_SECONDS
            : request.minIntervalSeconds();
    if (dailyLimit <= 0) {
      throw badRequest("每日发送上限必须大于 0");
    }
    if (minInterval < 0) {
      throw badRequest("最小间隔秒数不能是负数");
    }
    String status =
        text(request.status()).isEmpty() ? "ACTIVE" : text(request.status()).toUpperCase(Locale.ROOT);
    if (!CONFIG_STATUSES.contains(status)) {
      throw badRequest("状态不合法：" + request.status());
    }
    UUID existing =
        one(
            UUID.class,
            "select id from influencer_outreach_policy where tenant_id = " + TENANT + " and platform_id = ?",
            request.platformId());
    if (existing != null) {
      sql.execute(
          "update influencer_outreach_policy set daily_send_limit = ?, min_interval_seconds = ?,"
              + " status = ?, note = ?, updated_at = now(), version = version + 1"
              + " where tenant_id = ?::uuid and id = ?::uuid",
          dailyLimit,
          minInterval,
          status,
          text(request.note()),
          currentTenant(),
          existing);
      return policyById(existing);
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into influencer_outreach_policy (tenant_id,id,platform_id,daily_send_limit,"
            + " min_interval_seconds,status,note,version,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,0,now(),now())",
        currentTenant(),
        id,
        request.platformId(),
        dailyLimit,
        minInterval,
        status,
        text(request.note()));
    return policyById(id);
  }

  private InfluencerOutreachPolicyRow policyById(UUID policyId) {
    Record r =
        sql.fetchOne(policySelect() + " where p.tenant_id = " + TENANT + " and p.id = ?", policyId);
    if (r == null) {
      throw notFound("风控策略");
    }
    return policyRow(r);
  }

  // ==================================================================
  // 建联记录
  // ==================================================================

  public InfluencerOutreachPage outreachs(
      UUID influencerId,
      UUID segmentId,
      UUID templateId,
      String platformCode,
      String shopCode,
      String status,
      String keyword,
      String sort,
      int page,
      int size) {
    StringBuilder where = new StringBuilder(" where o.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    if (influencerId != null) {
      where.append(" and o.influencer_id = ?");
      binds.add(influencerId);
    }
    if (segmentId != null) {
      where.append(" and o.segment_id = ?");
      binds.add(segmentId);
    }
    if (templateId != null) {
      where.append(" and o.template_id = ?");
      binds.add(templateId);
    }
    String platform = blankToNull(platformCode);
    if (platform != null) {
      where.append(" and o.platform_id = (select id from platform where code = ?)");
      binds.add(platform.toUpperCase(Locale.ROOT));
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and o.shop_code = ?");
      binds.add(shop);
    }
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and o.status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(
          " and (i.display_name ilike ? or o.platform_message_id ilike ? or o.idempotency_key ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    int safeSize = safeSize(size);
    int safePage = safePage(page);
    String order =
        OUTREACH_SORTS.getOrDefault(text(sort).toUpperCase(Locale.ROOT), OUTREACH_SORTS.get("CREATED_DESC"));
    long total = count(outreachCountSelect() + where, binds.toArray());
    List<InfluencerOutreachRow> items =
        sql.fetch(
                outreachSelect()
                    + where
                    + " order by "
                    + order
                    + " limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::outreachRow)
            .toList();
    return new InfluencerOutreachPage(items, total, safePage, safeSize);
  }

  private String outreachCountSelect() {
    return "select count(*) from influencer_outreach o"
        + " join influencer i on i.tenant_id = o.tenant_id and i.id = o.influencer_id";
  }

  private String outreachSelect() {
    return "select o.id, o.influencer_id, i.display_name influencer_name, i.category influencer_category,"
        + " o.segment_id, s.segment_code, s.segment_name,"
        + " o.template_id, t.template_code, t.template_name,"
        + " o.platform_id, pf.code platform_code, o.shop_code,"
        + " o.contact_channel, o.idempotency_key, o.subject, o.message_body, o.status,"
        + " o.platform_message_id, o.platform_receipt, o.sent_at, o.replied_at, o.closed_at,"
        + " (select count(*) from influencer_outreach_followup f where f.tenant_id = o.tenant_id"
        + "   and f.outreach_id = o.id) followup_count,"
        + " (select count(*) from influencer_sample_order so where so.tenant_id = o.tenant_id"
        + "   and so.outreach_id = o.id) sample_order_count,"
        + " (select count(*) from influencer_commission_accrual ca where ca.tenant_id = o.tenant_id"
        + "   and ca.outreach_id = o.id) accrual_count,"
        + " o.note, o.version, o.created_at, o.updated_at"
        + " from influencer_outreach o"
        + " join influencer i on i.tenant_id = o.tenant_id and i.id = o.influencer_id"
        + " join influencer_outreach_segment s on s.tenant_id = o.tenant_id and s.id = o.segment_id"
        + " join influencer_outreach_template t on t.tenant_id = o.tenant_id and t.id = o.template_id"
        + " join platform pf on pf.id = o.platform_id";
  }

  private InfluencerOutreachRow outreachRow(Record r) {
    return new InfluencerOutreachRow(
        r.get("id", UUID.class),
        r.get("influencer_id", UUID.class),
        r.get("influencer_name", String.class),
        r.get("influencer_category", String.class),
        r.get("segment_id", UUID.class),
        r.get("segment_code", String.class),
        r.get("segment_name", String.class),
        r.get("template_id", UUID.class),
        r.get("template_code", String.class),
        r.get("template_name", String.class),
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("contact_channel", String.class),
        r.get("idempotency_key", String.class),
        r.get("subject", String.class),
        r.get("message_body", String.class),
        r.get("status", String.class),
        r.get("platform_message_id", String.class),
        r.get("platform_receipt", String.class),
        r.get("sent_at", OffsetDateTime.class),
        r.get("replied_at", OffsetDateTime.class),
        r.get("closed_at", OffsetDateTime.class),
        r.get("followup_count", Integer.class) == null ? 0 : r.get("followup_count", Integer.class),
        r.get("sample_order_count", Integer.class) == null
            ? 0
            : r.get("sample_order_count", Integer.class),
        r.get("accrual_count", Integer.class) == null ? 0 : r.get("accrual_count", Integer.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 新建一条建联记录（草稿）。 */
  public InfluencerOutreachRow saveOutreach(SaveInfluencerOutreachRequest request) {
    String key = text(request.idempotencyKey());
    if (key.isEmpty()) {
      throw badRequest("幂等键不能为空");
    }
    UUID duplicate =
        one(
            UUID.class,
            "select id from influencer_outreach where tenant_id = " + TENANT + " and idempotency_key = ?",
            key);
    if (duplicate != null) {
      throw conflict("幂等键已存在（同一条发送不会发第二遍）：" + key);
    }
    Record influencer = influencerRecord(request.influencerId());
    if (influencer == null) {
      throw notFound("达人");
    }
    Record segment = segmentRecord(request.segmentId());
    if (segment == null) {
      throw notFound("筛选条件");
    }
    Record template = templateRecord(request.templateId());
    if (template == null) {
      throw notFound("邀约模板");
    }
    UUID influencerPlatform = influencer.get("platform_id", UUID.class);
    UUID templatePlatform = template.get("platform_id", UUID.class);
    if (!influencerPlatform.equals(templatePlatform)) {
      throw badRequest("达人的平台与模板面向的平台不一致");
    }
    long open =
        count(
            "select count(*) from influencer_outreach where tenant_id = "
                + TENANT
                + " and influencer_id = ?"
                + " and status in ('DRAFT','SENT','REPLIED','ACCEPTED','COOPERATING')",
            request.influencerId());
    if (open > 0) {
      throw conflict("该达人已有一条未走完的邀约，不能重复建联（重复建联 = 重复发私信）。");
    }
    Map<String, String> values = renderValues(template, influencer, request.segmentId());
    String subject;
    String body;
    try {
      subject = render(text(template.get("subject", String.class)), values);
      body = render(text(template.get("body", String.class)), values);
    } catch (IllegalArgumentException e) {
      throw badRequest(e.getMessage());
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into influencer_outreach (tenant_id,id,influencer_id,segment_id,template_id,"
            + " platform_id,shop_code,contact_channel,idempotency_key,subject,message_body,status,"
            + " platform_message_id,platform_receipt,sent_at,replied_at,closed_at,note,version,"
            + " created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,'OFFICIAL_API',?,?,?,'DRAFT','','',null,null,null,?,0,now(),now())",
        currentTenant(),
        id,
        request.influencerId(),
        request.segmentId(),
        request.templateId(),
        influencerPlatform,
        influencer.get("shop_code", String.class),
        key,
        subject,
        body,
        text(request.note()));
    return outreachById(id);
  }

  /** 改一条建联记录（只允许改草稿；已发出的私信**不可改口**）。 */
  public InfluencerOutreachRow updateOutreach(String id, SaveInfluencerOutreachRequest request) {
    UUID outreachId = parseId(id);
    Record existing = outreachRecord(outreachId);
    if (existing == null) {
      throw notFound("建联记录");
    }
    if (!"DRAFT".equals(existing.get("status", String.class))) {
      throw conflict("只有草稿可以改。已发出的私信**不可改口**（改口 = 篡改历史）。");
    }
    String key = text(request.idempotencyKey());
    if (key.isEmpty()) {
      throw badRequest("幂等键不能为空");
    }
    UUID duplicate =
        one(
            UUID.class,
            "select id from influencer_outreach where tenant_id = " + TENANT
                + " and idempotency_key = ? and id <> ?",
            key,
            outreachId);
    if (duplicate != null) {
      throw conflict("幂等键已存在（同一条发送不会发第二遍）：" + key);
    }
    Record influencer = influencerRecord(request.influencerId());
    if (influencer == null) {
      throw notFound("达人");
    }
    Record segment = segmentRecord(request.segmentId());
    if (segment == null) {
      throw notFound("筛选条件");
    }
    Record template = templateRecord(request.templateId());
    if (template == null) {
      throw notFound("邀约模板");
    }
    UUID influencerPlatform = influencer.get("platform_id", UUID.class);
    if (!influencerPlatform.equals(template.get("platform_id", UUID.class))) {
      throw badRequest("达人的平台与模板面向的平台不一致");
    }
    Map<String, String> values = renderValues(template, influencer, request.segmentId());
    String subject;
    String body;
    try {
      subject = render(text(template.get("subject", String.class)), values);
      body = render(text(template.get("body", String.class)), values);
    } catch (IllegalArgumentException e) {
      throw badRequest(e.getMessage());
    }
    sql.execute(
        "update influencer_outreach set influencer_id = ?, segment_id = ?, template_id = ?,"
            + " platform_id = ?, shop_code = ?, idempotency_key = ?, subject = ?, message_body = ?,"
            + " note = ?, updated_at = now(), version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        request.influencerId(),
        request.segmentId(),
        request.templateId(),
        influencerPlatform,
        influencer.get("shop_code", String.class),
        key,
        subject,
        body,
        text(request.note()),
        currentTenant(),
        outreachId);
    return outreachById(outreachId);
  }

  /** 删一条建联记录（**只有草稿可以删**；已发出的私信是举证凭据）。 */
  public void deleteOutreach(String id) {
    UUID outreachId = parseId(id);
    Record existing = outreachRecord(outreachId);
    if (existing == null) {
      throw notFound("建联记录");
    }
    if (!"DRAFT".equals(existing.get("status", String.class))) {
      throw conflict("只有草稿可以删。已发出的私信是**举证凭据**，不能物理删除（请改状态为 CANCELLED）。");
    }
    long samples =
        count(
            "select count(*) from influencer_sample_order where tenant_id = " + TENANT + " and outreach_id = ?",
            outreachId);
    if (samples > 0) {
      throw conflict("该建联记录下已有样品单，不能物理删除。");
    }
    sql.execute(
        "delete from influencer_outreach where tenant_id = ?::uuid and id = ?::uuid",
        currentTenant(),
        outreachId);
  }

  /**
   * 🔴 手动登记「邀约已发送 + 平台回执」。
   *
   * <p>这是官方私信接口未交付时**唯一的发送登记入口**。登记时过三道闸：
   *
   * <ol>
   *   <li><b>幂等键</b>：同一条不会登记两次（DB UNIQUE 兜底）。</li>
   *   <li><b>每日发送上限</b>：超了直接拒。</li>
   *   <li><b>最小间隔</b>：离上一条太近直接拒。</li>
   * </ol>
   */
  public InfluencerOutreachRow registerReceipt(
      String id, RegisterInfluencerOutreachReceiptRequest request) {
    UUID outreachId = parseId(id);
    Record existing = outreachRecord(outreachId);
    if (existing == null) {
      throw notFound("建联记录");
    }
    String status = existing.get("status", String.class);
    if (!"DRAFT".equals(status)) {
      throw conflict("只有草稿可以登记发送回执（当前状态：" + status + "）。");
    }
    String messageId = text(request.platformMessageId());
    if (messageId.isEmpty()) {
      throw badRequest("平台消息 id 不能为空");
    }
    UUID platformId = existing.get("platform_id", UUID.class);
    RateCheck check = rateCheck(platformId);
    if (!check.allowed()) {
      throw conflict(check.reason());
    }
    OffsetDateTime sentAt = request.sentAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.sentAt();
    sql.execute(
        "update influencer_outreach set status = 'SENT', platform_message_id = ?,"
            + " platform_receipt = ?, sent_at = ?::timestamptz, note = ?, updated_at = now(),"
            + " version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        messageId,
        text(request.platformReceipt()),
        sentAt,
        text(request.note()),
        currentTenant(),
        outreachId);
    return outreachById(outreachId);
  }

  /**
   * 🔴 走官方接口发送一条私信。
   *
   * <p>当前必然返回「未接入」并且**不写任何数据** —— 平台适配层没有达人私信适配器。
   * 端口接上之后，同一个端点自动放行，前端不用改。
   */
  public InfluencerOutreachSendResult sendViaPlatform(String id, String bearer) {
    UUID outreachId = parseId(id);
    Record existing = outreachRecord(outreachId);
    if (existing == null) {
      throw notFound("建联记录");
    }
    if (!"DRAFT".equals(existing.get("status", String.class))) {
      throw conflict("只有草稿可以发送（当前状态：" + existing.get("status", String.class) + "）。");
    }
    Record influencer = influencerRecord(existing.get("influencer_id", UUID.class));
    if (influencer == null) {
      throw notFound("达人");
    }
    String platformCode =
        one(
            String.class,
            "select code from platform where id = ?",
            existing.get("platform_id", UUID.class));
    var result =
        platformOutreach.send(
            new InfluencerOutreachPort.SendRequest(
                platformCode,
                existing.get("shop_code", String.class),
                influencer.get("external_influencer_id", String.class),
                existing.get("subject", String.class),
                existing.get("message_body", String.class),
                existing.get("idempotency_key", String.class),
                bearer));
    // 🔴 未接入 ⇒ **不写任何数据**（不改成 SENT、不写 platform_message_id）。
    //   编一个假的发送成功比「明确失败」糟得多。
    return new InfluencerOutreachSendResult(result.sent(), result.reason(), result.platformMessageId());
  }

  /** 逐平台说清私信接口是否已接入。 */
  public InfluencerOutreachAvailability availability() {
    List<InfluencerOutreachPlatformOption> platforms =
        sql.fetch(
                "select distinct pf.id, pf.code, pf.name from shop s"
                    + " join platform pf on pf.id = s.platform_id"
                    + " where s.tenant_id = "
                    + TENANT
                    + " order by pf.code")
            .stream()
            .map(
                r ->
                    new InfluencerOutreachPlatformOption(
                        r.get("id", UUID.class), r.get("code", String.class), r.get("name", String.class)))
            .toList();
    List<InfluencerOutreachPlatformAvailability> rows = new ArrayList<>();
    for (var platform : platforms) {
      var availability = platformOutreach.availability(platform.code());
      int limit = policyLimit(platform.id());
      rows.add(
          new InfluencerOutreachPlatformAvailability(
              platform.code(),
              platform.name(),
              availability.available(),
              availability.status(),
              availability.reason(),
              sentToday(platform.id()),
              limit));
    }
    return new InfluencerOutreachAvailability(rows, OFFICIAL_API_NOTE, RATE_LIMIT_NOTE);
  }

  /** 推进建联状态（状态机 + DB CHECK 双钉）。 */
  public InfluencerOutreachRow transitionOutreach(
      String id, TransitionInfluencerOutreachRequest request) {
    UUID outreachId = parseId(id);
    Record existing = outreachRecord(outreachId);
    if (existing == null) {
      throw notFound("建联记录");
    }
    String from = existing.get("status", String.class);
    String to = requireEnum(request.status(), OUTREACH_STATUSES, "建联状态");
    if (!canTransitionOutreach(from, to)) {
      throw conflict(
          "不允许的状态流转："
              + from
              + " → "
              + to
              + "（可流转到："
              + (nextOutreachStatuses(from).isEmpty() ? "无（终态）" : String.join(" / ", nextOutreachStatuses(from)))
              + "）");
    }
    OffsetDateTime occurredAt =
        request.occurredAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.occurredAt();
    OffsetDateTime sentAt = existing.get("sent_at", OffsetDateTime.class);
    if (sentAt == null && !"CANCELLED".equals(to)) {
      throw conflict(
          "这条邀约还没有发送过（sent_at 为空），不能推进到 "
              + to
              + "。发送只由「登记平台回执」这一个动作产生（它同时写平台消息 id / 回执 / 发送时刻）。");
    }
    OffsetDateTime repliedAt = existing.get("replied_at", OffsetDateTime.class);
    if ("REPLIED".equals(to) && repliedAt == null) {
      repliedAt = occurredAt;
    }
    OffsetDateTime closedAt = existing.get("closed_at", OffsetDateTime.class);
    if (isTerminalOutreach(to) && closedAt == null) {
      closedAt = occurredAt;
    }
    sql.execute(
        "update influencer_outreach set status = ?, replied_at = ?::timestamptz,"
            + " closed_at = ?::timestamptz, note = ?, updated_at = now(), version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        to,
        repliedAt,
        closedAt,
        text(request.note()),
        currentTenant(),
        outreachId);
    return outreachById(outreachId);
  }

  /** 建联记录详情。 */
  public InfluencerOutreachDetail outreach(String id) {
    UUID outreachId = parseId(id);
    Record r =
        sql.fetchOne(outreachSelect() + " where o.tenant_id = " + TENANT + " and o.id = ?", outreachId);
    if (r == null) {
      throw notFound("建联记录");
    }
    return new InfluencerOutreachDetail(
        outreachRow(r),
        followupsOf(outreachId),
        sampleOrdersOf(outreachId),
        accrualsOf(outreachId),
        OFFICIAL_API_NOTE,
        RATE_LIMIT_NOTE,
        DOWNSTREAM_NOTE,
        BOUNDARY_NOTE);
  }

  private InfluencerOutreachRow outreachById(UUID outreachId) {
    Record r =
        sql.fetchOne(outreachSelect() + " where o.tenant_id = " + TENANT + " and o.id = ?", outreachId);
    if (r == null) {
      throw notFound("建联记录");
    }
    return outreachRow(r);
  }

  private Record outreachRecord(UUID outreachId) {
    return sql.fetchOne(
        "select id, influencer_id, segment_id, template_id, platform_id, shop_code, status,"
            + " idempotency_key, subject, message_body, sent_at, replied_at, closed_at"
            + " from influencer_outreach where tenant_id = "
            + TENANT
            + " and id = ?",
        outreachId);
  }

  private Record influencerRecord(UUID influencerId) {
    return sql.fetchOne(
        "select id, display_name, handle, category, platform_id, shop_code, external_influencer_id"
            + " from influencer where tenant_id = "
            + TENANT
            + " and id = ?",
        influencerId);
  }

  /**
   * 渲染变量取值。
   *
   * <p>🔴 {@code commission_rate} 是**解析出来的生效佣金规则**的比例（格式化成百分比）；
   *   没有生效规则时取空串（这是可选变量，渲染成空不会失败）。
   */
  private Map<String, String> renderValues(
      Record template, Record influencer, UUID segmentId) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("display_name", text(influencer.get("display_name", String.class)));
    values.put("handle", text(influencer.get("handle", String.class)));
    values.put("category", text(influencer.get("category", String.class)));
    String shopCode = text(influencer.get("shop_code", String.class));
    values.put(
        "shop_name",
        text(one(String.class, "select name from shop where tenant_id = " + TENANT + " and code = ?", shopCode)));
    values.put(
        "platform_name",
        text(
            one(
                String.class,
                "select name from platform where id = ?",
                template.get("platform_id", UUID.class))));
    UUID effectiveSegment = segmentId == null ? null : segmentId;
    values.put(
        "segment_name",
        effectiveSegment == null
            ? ""
            : text(
                one(
                    String.class,
                    "select segment_name from influencer_outreach_segment where tenant_id = "
                        + TENANT
                        + " and id = ?",
                    effectiveSegment)));
    Record rule = resolveCommissionRule(effectiveSegment, influencer.get("id", UUID.class));
    if (rule != null && "RATE".equals(rule.get("commission_type", String.class))) {
      BigDecimal rate = rule.get("commission_rate", BigDecimal.class);
      values.put(
          "commission_rate",
          rate == null ? "" : rate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%");
    } else {
      values.put("commission_rate", "");
    }
    return values;
  }

  // ==================================================================
  // 批量建联
  // ==================================================================

  /**
   * 🔴 批量建联：按筛选条件挑达人，**批量生成草稿**。
   *
   * <p>🔴 <b>刻意不是「批量群发」</b>：这里只生成 DRAFT，每一条仍要**逐条**
   *   登记平台回执才变成 SENT，且登记时过幂等键 + 每日上限 + 最小间隔三道闸。
   *   蓝图的红线是「建联必须走官方接口，批量私信是平台风控重点」——
   *   所以本模块**没有**任何一次发 N 条的入口。
   */
  public InfluencerOutreachBatchResult batchCreate(BatchInfluencerOutreachRequest request) {
    Record segment = segmentRecord(request.segmentId());
    if (segment == null) {
      throw notFound("筛选条件");
    }
    Record template = templateRecord(request.templateId());
    if (template == null) {
      throw notFound("邀约模板");
    }
    UUID templatePlatform = template.get("platform_id", UUID.class);
    int limit = request.limit() == null ? 50 : Math.min(Math.max(request.limit(), 1), MAX_BATCH_SIZE);
    String batchKey =
        text(request.batchKey()).isEmpty()
            ? ("batch-" + OffsetDateTime.now(ZoneOffset.UTC).toInstant().toEpochMilli())
            : text(request.batchKey());

    SegmentCriteria criteria = criteriaOf(request.segmentId());
    StringBuilder where = new StringBuilder(" where i.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    applyCriteria(where, binds, criteria);
    // 🔴 模板面向的平台必须与达人一致 —— 跨平台用同一个模板是错的。
    where.append(" and i.platform_id = ?");
    binds.add(templatePlatform);

    List<Record> influencers =
        sql.fetch(
                "select i.id, i.display_name, i.platform_id, i.shop_code, i.external_influencer_id,"
                    + " i.handle, i.category"
                    + " from influencer i"
                    + where
                    + " order by i.gmv desc nulls last, i.follower_count desc, i.id limit ?",
                concat(binds, limit))
            .stream()
            .toList();

    List<InfluencerOutreachBatchItem> items = new ArrayList<>();
    int created = 0;
    for (Record influencer : influencers) {
      UUID influencerId = influencer.get("id", UUID.class);
      String key = "batch:" + batchKey + ":" + influencerId;
      UUID duplicate =
          one(
              UUID.class,
              "select id from influencer_outreach where tenant_id = " + TENANT + " and idempotency_key = ?",
              key);
      if (duplicate != null) {
        items.add(
            new InfluencerOutreachBatchItem(
                influencerId,
                influencer.get("display_name", String.class),
                duplicate,
                key,
                false,
                "SKIPPED_DUPLICATE",
                "同一批次已建过这条邀约（幂等键已存在）"));
        continue;
      }
      long open =
          count(
              "select count(*) from influencer_outreach where tenant_id = "
                  + TENANT
                  + " and influencer_id = ?"
                  + " and status in ('DRAFT','SENT','REPLIED','ACCEPTED','COOPERATING')",
              influencerId);
      if (open > 0) {
        items.add(
            new InfluencerOutreachBatchItem(
                influencerId,
                influencer.get("display_name", String.class),
                null,
                key,
                false,
                "SKIPPED_OPEN",
                "该达人已有一条未走完的邀约（重复建联 = 重复发私信）"));
        continue;
      }
      Map<String, String> values = renderValues(template, influencer, request.segmentId());
      String subject;
      String body;
      try {
        subject = render(text(template.get("subject", String.class)), values);
        body = render(text(template.get("body", String.class)), values);
      } catch (IllegalArgumentException e) {
        items.add(
            new InfluencerOutreachBatchItem(
                influencerId,
                influencer.get("display_name", String.class),
                null,
                key,
                false,
                "SKIPPED_TEMPLATE",
                "模板渲染失败：" + e.getMessage()));
        continue;
      }
      UUID id = Ids.next();
      sql.execute(
          "insert into influencer_outreach (tenant_id,id,influencer_id,segment_id,template_id,"
              + " platform_id,shop_code,contact_channel,idempotency_key,subject,message_body,status,"
              + " platform_message_id,platform_receipt,sent_at,replied_at,closed_at,note,version,"
              + " created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,'OFFICIAL_API',?,?,?,'DRAFT','','',null,null,null,?,0,now(),now())",
          currentTenant(),
          id,
          influencerId,
          request.segmentId(),
          request.templateId(),
          templatePlatform,
          influencer.get("shop_code", String.class),
          key,
          subject,
          body,
          text(request.note()));
      created++;
      items.add(
          new InfluencerOutreachBatchItem(
              influencerId,
              influencer.get("display_name", String.class),
              id,
              key,
              true,
              "CREATED",
              "已生成草稿（还需逐条登记平台回执才会真正发出）"));
    }
    int skipped = items.size() - created;
    return new InfluencerOutreachBatchResult(
        influencers.size(),
        created,
        skipped,
        items,
        "🔴 批量建联**只生成草稿**：每一条仍要逐条「登记平台回执」才会变成已发送，"
            + "登记时过幂等键 + 每日发送上限 + 最小间隔三道闸 —— "
            + "批量私信是平台风控重点，本模块不提供任何批量群发通道。");
  }

  // ==================================================================
  // 频率上限
  // ==================================================================

  private int policyLimit(UUID platformId) {
    Integer limit =
        one(
            Integer.class,
            "select daily_send_limit from influencer_outreach_policy where tenant_id = "
                + TENANT
                + " and platform_id = ?",
            platformId);
    return limit == null ? DEFAULT_DAILY_SEND_LIMIT : limit;
  }

  private int policyMinInterval(UUID platformId) {
    Integer interval =
        one(
            Integer.class,
            "select min_interval_seconds from influencer_outreach_policy where tenant_id = "
                + TENANT
                + " and platform_id = ?",
            platformId);
    return interval == null ? DEFAULT_MIN_INTERVAL_SECONDS : interval;
  }

  /** 今天（UTC 自然日）该平台已登记的发送条数。 */
  private long sentToday(UUID platformId) {
    Long value =
        one(
            Long.class,
            "select count(*) from influencer_outreach where tenant_id = "
                + TENANT
                + " and platform_id = ? and sent_at is not null"
                + " and sent_at >= date_trunc('day', now() at time zone 'UTC') at time zone 'UTC'",
            platformId);
    return value == null ? 0L : value;
  }

  /** 距上一条发送的秒数（null = 今天还没发过）。 */
  private Long secondsSinceLastSend(UUID platformId) {
    OffsetDateTime last =
        one(
            OffsetDateTime.class,
            "select max(sent_at) from influencer_outreach where tenant_id = "
                + TENANT
                + " and platform_id = ? and sent_at is not null",
            platformId);
    if (last == null) {
      return null;
    }
    long seconds = java.time.Duration.between(last, OffsetDateTime.now(ZoneOffset.UTC)).getSeconds();
    return seconds < 0 ? 0L : seconds;
  }

  /** 频率上限判定（纯计算在 {@link InfluencerOutreachRules#checkRate}）。 */
  private RateCheck rateCheck(UUID platformId) {
    return checkRate(
        sentToday(platformId),
        policyLimit(platformId),
        secondsSinceLastSend(platformId),
        policyMinInterval(platformId));
  }

  // ==================================================================
  // 合作跟进
  // ==================================================================

  public InfluencerOutreachFollowupPage followups(UUID outreachId, int page, int size) {
    StringBuilder where = new StringBuilder(" where f.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    if (outreachId != null) {
      where.append(" and f.outreach_id = ?");
      binds.add(outreachId);
    }
    int safeSize = safeSize(size);
    int safePage = safePage(page);
    long total = count("select count(*) from influencer_outreach_followup f" + where, binds.toArray());
    List<InfluencerOutreachFollowupRow> items =
        sql.fetch(
                "select f.id, f.outreach_id, f.followup_type, f.content, f.occurred_at,"
                    + " f.next_followup_at, f.owner_user_id, f.note, f.created_at, f.updated_at"
                    + " from influencer_outreach_followup f"
                    + where
                    + " order by f.occurred_at desc, f.id limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(
                r ->
                    new InfluencerOutreachFollowupRow(
                        r.get("id", UUID.class),
                        r.get("outreach_id", UUID.class),
                        r.get("followup_type", String.class),
                        r.get("content", String.class),
                        r.get("occurred_at", OffsetDateTime.class),
                        r.get("next_followup_at", OffsetDateTime.class),
                        r.get("owner_user_id", UUID.class),
                        r.get("note", String.class),
                        r.get("created_at", OffsetDateTime.class),
                        r.get("updated_at", OffsetDateTime.class)))
            .toList();
    return new InfluencerOutreachFollowupPage(items, total, safePage, safeSize);
  }

  private List<InfluencerOutreachFollowupRow> followupsOf(UUID outreachId) {
    return sql.fetch(
            "select f.id, f.outreach_id, f.followup_type, f.content, f.occurred_at,"
                + " f.next_followup_at, f.owner_user_id, f.note, f.created_at, f.updated_at"
                + " from influencer_outreach_followup f where f.tenant_id = "
                + TENANT
                + " and f.outreach_id = ? order by f.occurred_at desc, f.id",
            outreachId)
        .stream()
        .map(
            r ->
                new InfluencerOutreachFollowupRow(
                    r.get("id", UUID.class),
                    r.get("outreach_id", UUID.class),
                    r.get("followup_type", String.class),
                    r.get("content", String.class),
                    r.get("occurred_at", OffsetDateTime.class),
                    r.get("next_followup_at", OffsetDateTime.class),
                    r.get("owner_user_id", UUID.class),
                    r.get("note", String.class),
                    r.get("created_at", OffsetDateTime.class),
                    r.get("updated_at", OffsetDateTime.class)))
        .toList();
  }

  /** 登记一次合作跟进（追加型事实）。 */
  public InfluencerOutreachFollowupRow createFollowup(
      String outreachId, CreateInfluencerOutreachFollowupRequest request) {
    UUID id = parseId(outreachId);
    if (outreachRecord(id) == null) {
      throw notFound("建联记录");
    }
    String type = requireEnum(request.followupType(), FOLLOWUP_TYPES, "跟进方式");
    String content = text(request.content());
    if (content.isEmpty()) {
      throw badRequest("跟进内容不能为空");
    }
    OffsetDateTime occurredAt =
        request.occurredAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.occurredAt();
    if (request.nextFollowupAt() != null && request.nextFollowupAt().isBefore(occurredAt)) {
      throw badRequest("下次跟进时刻不能早于本次发生时刻");
    }
    UUID followupId = Ids.next();
    sql.execute(
        "insert into influencer_outreach_followup (tenant_id,id,outreach_id,followup_type,content,"
            + " occurred_at,next_followup_at,owner_user_id,note,created_at,updated_at)"
            + " values (?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,now(),now())",
        currentTenant(),
        followupId,
        id,
        type,
        content,
        occurredAt,
        request.nextFollowupAt(),
        request.ownerUserId(),
        text(request.note()));
    Record r =
        sql.fetchOne(
            "select id, outreach_id, followup_type, content, occurred_at, next_followup_at,"
                + " owner_user_id, note, created_at, updated_at"
                + " from influencer_outreach_followup where tenant_id = "
                + TENANT
                + " and id = ?",
            followupId);
    return new InfluencerOutreachFollowupRow(
        r.get("id", UUID.class),
        r.get("outreach_id", UUID.class),
        r.get("followup_type", String.class),
        r.get("content", String.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("next_followup_at", OffsetDateTime.class),
        r.get("owner_user_id", UUID.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 样品单
  // ==================================================================

  public InfluencerSampleOrderPage sampleOrders(
      UUID outreachId,
      UUID influencerId,
      String status,
      String shopCode,
      String skuCode,
      String keyword,
      String sort,
      int page,
      int size) {
    StringBuilder where = new StringBuilder(" where so.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    if (outreachId != null) {
      where.append(" and so.outreach_id = ?");
      binds.add(outreachId);
    }
    if (influencerId != null) {
      where.append(" and so.influencer_id = ?");
      binds.add(influencerId);
    }
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and so.status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }
    String shop = blankToNull(shopCode);
    if (shop != null) {
      where.append(" and so.shop_code = ?");
      binds.add(shop);
    }
    String sku = blankToNull(skuCode);
    if (sku != null) {
      where.append(" and so.sku_code ilike ?");
      binds.add("%" + escapeLike(sku) + "%");
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (i.display_name ilike ? or so.sku_name ilike ? or so.recipient_name ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    int safeSize = safeSize(size);
    int safePage = safePage(page);
    String order =
        SAMPLE_SORTS.getOrDefault(text(sort).toUpperCase(Locale.ROOT), SAMPLE_SORTS.get("CREATED_DESC"));
    long total =
        count(
            "select count(*) from influencer_sample_order so"
                + " join influencer i on i.tenant_id = so.tenant_id and i.id = so.influencer_id"
                + where,
            binds.toArray());
    List<InfluencerSampleOrderRow> items =
        sql.fetch(
                sampleOrderSelect()
                    + where
                    + " order by "
                    + order
                    + " limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::sampleOrderRow)
            .toList();
    return new InfluencerSampleOrderPage(items, total, safePage, safeSize);
  }

  private String sampleOrderSelect() {
    return "select so.id, so.outreach_id, so.influencer_id, i.display_name influencer_name,"
        + " so.shop_code, so.sku_code, so.sku_name, so.quantity, so.sample_value, so.sample_currency,"
        + " so.recipient_name, so.recipient_phone, so.recipient_address, so.status,"
        + " so.outbound_status, so.outbound_at, so.warehouse_outbound_ref, so.warehouse_owner_module,"
        + " so.tracking_available, so.tracking_owner_module, so.signed_at, so.signed_by,"
        + " (select count(*) from influencer_sample_shipment sh where sh.tenant_id = so.tenant_id"
        + "   and sh.sample_order_id = so.id) shipment_count,"
        + " so.note, so.version, so.created_at, so.updated_at"
        + " from influencer_sample_order so"
        + " join influencer i on i.tenant_id = so.tenant_id and i.id = so.influencer_id";
  }

  private InfluencerSampleOrderRow sampleOrderRow(Record r) {
    return new InfluencerSampleOrderRow(
        r.get("id", UUID.class),
        r.get("outreach_id", UUID.class),
        r.get("influencer_id", UUID.class),
        r.get("influencer_name", String.class),
        r.get("shop_code", String.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("quantity", BigDecimal.class),
        r.get("sample_value", BigDecimal.class),
        r.get("sample_currency", String.class),
        r.get("recipient_name", String.class),
        r.get("recipient_phone", String.class),
        r.get("recipient_address", String.class),
        r.get("status", String.class),
        // 🔴 DELIVERED / SIGNED **只能由包裹推导**（不是人填的），所以这两个状态
        //   一定是从包裹来的；OUTBOUNDED 既可能人手推进也可能由包裹推导，不作断言。
        "DELIVERED".equals(r.get("status", String.class))
            || "SIGNED".equals(r.get("status", String.class)),
        r.get("outbound_status", String.class),
        r.get("outbound_at", OffsetDateTime.class),
        r.get("warehouse_outbound_ref", String.class),
        r.get("warehouse_owner_module", String.class),
        Boolean.TRUE.equals(r.get("tracking_available", Boolean.class)),
        r.get("tracking_owner_module", String.class),
        r.get("signed_at", OffsetDateTime.class),
        r.get("signed_by", String.class),
        r.get("shipment_count", Integer.class) == null ? 0 : r.get("shipment_count", Integer.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private List<InfluencerSampleOrderRow> sampleOrdersOf(UUID outreachId) {
    return sql.fetch(
            sampleOrderSelect()
                + " where so.tenant_id = "
                + TENANT
                + " and so.outreach_id = ? order by so.created_at desc, so.id",
            outreachId)
        .stream()
        .map(this::sampleOrderRow)
        .toList();
  }

  /** 改一张样品单（新建走 {@link #createSampleOrder}）。 */
  public InfluencerSampleOrderRow updateSampleOrder(
      String id, SaveInfluencerSampleOrderRequest request) {
    UUID sampleId = parseId(id);
    if (sampleOrderRecord(sampleId) == null) {
      throw notFound("样品单");
    }
    String skuCode = text(request.skuCode());
    if (skuCode.isEmpty()) {
      throw badRequest("样品 SKU 编码不能为空");
    }
    if (request.sampleValue() != null && request.sampleValue().signum() < 0) {
      throw badRequest("样品成本不能是负数");
    }
    BigDecimal quantity = request.quantity() == null ? BigDecimal.ONE : request.quantity();
    if (quantity.signum() <= 0) {
      throw badRequest("样品数量必须大于 0");
    }
    sql.execute(
        "update influencer_sample_order set sku_code = ?, sku_name = ?, quantity = ?,"
            + " sample_value = ?, sample_currency = ?, recipient_name = ?, recipient_phone = ?,"
            + " recipient_address = ?, note = ?, updated_at = now(), version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        skuCode,
        text(request.skuName()),
        quantity,
        request.sampleValue(),
        normalizeCurrency(request.sampleCurrency()),
        text(request.recipientName()),
        text(request.recipientPhone()),
        text(request.recipientAddress()),
        text(request.note()),
        currentTenant(),
        sampleId);
    return sampleOrderById(sampleId);
  }

  /** 新建一张样品单。 */
  public InfluencerSampleOrderRow createSampleOrder(SaveInfluencerSampleOrderRequest request) {
    Record outreach = outreachRecord(request.outreachId());
    if (outreach == null) {
      throw notFound("建联记录");
    }
    String skuCode = text(request.skuCode());
    if (skuCode.isEmpty()) {
      throw badRequest("样品 SKU 编码不能为空");
    }
    if (request.sampleValue() != null && request.sampleValue().signum() < 0) {
      throw badRequest("样品成本不能是负数");
    }
    BigDecimal quantity = request.quantity() == null ? BigDecimal.ONE : request.quantity();
    if (quantity.signum() <= 0) {
      throw badRequest("样品数量必须大于 0");
    }
    UUID influencerId = outreach.get("influencer_id", UUID.class);
    UUID id = Ids.next();
    sql.execute(
        "insert into influencer_sample_order (tenant_id,id,outreach_id,influencer_id,shop_code,"
            + " sku_code,sku_name,quantity,sample_value,sample_currency,recipient_name,recipient_phone,"
            + " recipient_address,status,outbound_status,outbound_at,warehouse_owner_module,"
            + " tracking_available,tracking_owner_module,signed_at,signed_by,note,version,"
            + " created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,'REQUESTED','LOCAL_PENDING',null,'8 仓库',"
            + " false,'10 物流',null,'',?,0,now(),now())",
        currentTenant(),
        id,
        request.outreachId(),
        influencerId,
        outreach.get("shop_code", String.class),
        skuCode,
        text(request.skuName()),
        quantity,
        request.sampleValue(),
        normalizeCurrency(request.sampleCurrency()),
        text(request.recipientName()),
        text(request.recipientPhone()),
        text(request.recipientAddress()),
        text(request.note()));
    return sampleOrderById(id);
  }

  /**
   * 手动推进样品单状态。
   *
   * <p>🔴 <b>只允许 REQUESTED → OUTBOUNDED / CANCELLED</b>：DELIVERED 与 SIGNED
   *   **由包裹推导**（{@link InfluencerOutreachRules#deriveSampleOrderStatus}），
   *   人手填「已签收」会让「到底收到没」这个问题失去唯一答案。
   */
  public InfluencerSampleOrderRow transitionSampleOrder(
      String id, TransitionInfluencerSampleOrderRequest request) {
    UUID sampleId = parseId(id);
    Record existing = sampleOrderRecord(sampleId);
    if (existing == null) {
      throw notFound("样品单");
    }
    String from = existing.get("status", String.class);
    String to = requireEnum(request.status(), SAMPLE_ORDER_STATUSES, "样品单状态");
    if ("CANCELLED".equals(to)) {
      if (!"REQUESTED".equals(from)) {
        throw conflict("样品单已出库，不能取消（只能记成退回）。当前状态：" + from);
      }
      sql.execute(
          "update influencer_sample_order set status = 'CANCELLED', note = ?, updated_at = now(),"
              + " version = version + 1 where tenant_id = ?::uuid and id = ?::uuid",
          text(request.note()),
          currentTenant(),
          sampleId);
      return sampleOrderById(sampleId);
    }
    if (!"OUTBOUNDED".equals(to)) {
      throw conflict(
          "样品单的状态只能手动推进到 OUTBOUNDED 或 CANCELLED；"
              + "DELIVERED / SIGNED **由包裹推导**（登记包裹、推进包裹状态才会变）。");
    }
    if (!"REQUESTED".equals(from)) {
      throw conflict("样品单当前状态是 " + from + "，不能推进到 OUTBOUNDED。");
    }
    OffsetDateTime outboundAt =
        request.outboundAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.outboundAt();
    sql.execute(
        "update influencer_sample_order set status = 'OUTBOUNDED', outbound_status = 'LOCAL_OUTBOUNDED',"
            + " outbound_at = ?::timestamptz, note = ?, updated_at = now(), version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        outboundAt,
        text(request.note()),
        currentTenant(),
        sampleId);
    return sampleOrderById(sampleId);
  }

  private Record sampleOrderRecord(UUID sampleId) {
    return sql.fetchOne(
        "select id, outreach_id, influencer_id, shop_code, sku_code, status, outbound_status"
            + " from influencer_sample_order where tenant_id = "
            + TENANT
            + " and id = ?",
        sampleId);
  }

  private InfluencerSampleOrderRow sampleOrderById(UUID sampleId) {
    Record r =
        sql.fetchOne(
            sampleOrderSelect() + " where so.tenant_id = " + TENANT + " and so.id = ?", sampleId);
    if (r == null) {
      throw notFound("样品单");
    }
    return sampleOrderRow(r);
  }

  // ==================================================================
  // 样品包裹
  // ==================================================================

  public InfluencerSampleShipmentPage shipments(UUID sampleOrderId, int page, int size) {
    StringBuilder where = new StringBuilder(" where sh.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    if (sampleOrderId != null) {
      where.append(" and sh.sample_order_id = ?");
      binds.add(sampleOrderId);
    }
    int safeSize = safeSize(size);
    int safePage = safePage(page);
    long total = count("select count(*) from influencer_sample_shipment sh" + where, binds.toArray());
    List<InfluencerSampleShipmentRow> items =
        sql.fetch(
                "select sh.id, sh.sample_order_id, sh.carrier_code, sh.tracking_no, sh.package_count,"
                    + " sh.status, sh.shipped_at, sh.delivered_at, sh.signed_at, sh.signed_by,"
                    + " sh.tracking_available, sh.tracking_owner_module, sh.note, sh.created_at, sh.updated_at"
                    + " from influencer_sample_shipment sh"
                    + where
                    + " order by sh.shipped_at desc, sh.id limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(
                r ->
                    new InfluencerSampleShipmentRow(
                        r.get("id", UUID.class),
                        r.get("sample_order_id", UUID.class),
                        r.get("carrier_code", String.class),
                        r.get("tracking_no", String.class),
                        r.get("package_count", BigDecimal.class),
                        r.get("status", String.class),
                        r.get("shipped_at", OffsetDateTime.class),
                        r.get("delivered_at", OffsetDateTime.class),
                        r.get("signed_at", OffsetDateTime.class),
                        r.get("signed_by", String.class),
                        Boolean.TRUE.equals(r.get("tracking_available", Boolean.class)),
                        r.get("tracking_owner_module", String.class),
                        r.get("note", String.class),
                        r.get("created_at", OffsetDateTime.class),
                        r.get("updated_at", OffsetDateTime.class)))
            .toList();
    return new InfluencerSampleShipmentPage(items, total, safePage, safeSize);
  }

  /**
   * 登记一个包裹（发货）。
   *
   * <p>🔴 登记包裹之后，样品单的 DELIVERED / SIGNED **由包裹状态推导**。
   */
  public InfluencerSampleShipmentRow createShipment(
      String sampleOrderId, CreateInfluencerSampleShipmentRequest request) {
    UUID orderId = parseId(sampleOrderId);
    Record sampleOrder = sampleOrderRecord(orderId);
    if (sampleOrder == null) {
      throw notFound("样品单");
    }
    if ("CANCELLED".equals(sampleOrder.get("status", String.class))) {
      throw conflict("样品单已取消，不能再登记包裹。");
    }
    BigDecimal packageCount =
        request.packageCount() == null ? BigDecimal.ONE : request.packageCount();
    if (packageCount.signum() <= 0) {
      throw badRequest("件数必须大于 0");
    }
    OffsetDateTime shippedAt =
        request.shippedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.shippedAt();
    UUID id = Ids.next();
    sql.execute(
        "insert into influencer_sample_shipment (tenant_id,id,sample_order_id,carrier_code,"
            + " tracking_no,package_count,status,shipped_at,delivered_at,signed_at,signed_by,"
            + " tracking_available,tracking_owner_module,note,created_at,updated_at)"
            + " values (?,?,?,?,?,?,'SHIPPED',?::timestamptz,null,null,'',false,'10 物流',?,now(),now())",
        currentTenant(),
        id,
        orderId,
        text(request.carrierCode()),
        text(request.trackingNo()),
        packageCount,
        shippedAt,
        text(request.note()));
    syncSampleOrderStatus(orderId);
    return shipmentById(id);
  }

  /**
   * 推进包裹状态（送达 / 签收 / 丢件 / 退回）。
   *
   * <p>🔴 签收只能走这里 —— 样品单的 SIGNED 由它推导。
   */
  public InfluencerSampleShipmentRow transitionShipment(
      String id, TransitionInfluencerSampleShipmentRequest request) {
    UUID shipmentId = parseId(id);
    Record existing = shipmentRecord(shipmentId);
    if (existing == null) {
      throw notFound("包裹");
    }
    String from = existing.get("status", String.class);
    String to = requireEnum(request.status(), SHIPMENT_STATUSES, "包裹状态");
    if (!canTransitionShipment(from, to)) {
      throw conflict(
          "不允许的包裹状态流转："
              + from
              + " → "
              + to
              + "（可流转到："
              + (nextShipmentStatuses(from).isEmpty()
                  ? "无（终态）"
                  : String.join(" / ", nextShipmentStatuses(from)))
              + "）");
    }
    OffsetDateTime occurredAt =
        request.occurredAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.occurredAt();
    OffsetDateTime deliveredAt = existing.get("delivered_at", OffsetDateTime.class);
    if (("DELIVERED".equals(to) || "SIGNED".equals(to)) && deliveredAt == null) {
      deliveredAt = occurredAt;
    }
    OffsetDateTime signedAt = existing.get("signed_at", OffsetDateTime.class);
    if ("SIGNED".equals(to) && signedAt == null) {
      signedAt = occurredAt;
    }
    sql.execute(
        "update influencer_sample_shipment set status = ?, delivered_at = ?::timestamptz,"
            + " signed_at = ?::timestamptz, signed_by = ?, note = ?, updated_at = now()"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        to,
        deliveredAt,
        signedAt,
        text(request.signedBy()),
        text(request.note()),
        currentTenant(),
        shipmentId);
    syncSampleOrderStatus(existing.get("sample_order_id", UUID.class));
    return shipmentById(shipmentId);
  }

  /** 按包裹状态推导样品单状态（DELIVERED / SIGNED 只能从这里来）。 */
  private void syncSampleOrderStatus(UUID sampleOrderId) {
    List<String> statuses =
        sql.fetch(
                "select status from influencer_sample_shipment where tenant_id = "
                    + TENANT
                    + " and sample_order_id = ?",
                sampleOrderId)
            .stream()
            .map(r -> r.get(0, String.class))
            .toList();
    if (statuses.isEmpty()) {
      // 🔴 没有包裹 ⇒ 推导不出任何进展，不要动已有的状态。
      return;
    }
    String derived = deriveSampleOrderStatus(statuses, false);
    Record existing = sampleOrderRecord(sampleOrderId);
    if (existing == null) {
      return;
    }
    String current = existing.get("status", String.class);
    if (current.equals(derived)) {
      return;
    }
    if ("CANCELLED".equals(current)) {
      // 🔴 已取消的样品单不再被包裹状态「救活」—— 取消是运营的明确决定。
      return;
    }
    OffsetDateTime outboundAt =
        one(
            OffsetDateTime.class,
            "select outbound_at from influencer_sample_order where tenant_id = "
                + TENANT
                + " and id = ?",
            sampleOrderId);
    if (outboundAt == null) {
      outboundAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
    OffsetDateTime signedAt = null;
    String signedBy = "";
    if ("SIGNED".equals(derived)) {
      Record lastSigned =
          sql.fetchOne(
              "select signed_at, signed_by from influencer_sample_shipment where tenant_id = "
                  + TENANT
                  + " and sample_order_id = ? and signed_at is not null"
                  + " order by signed_at desc, id limit 1",
              sampleOrderId);
      signedAt = lastSigned == null ? null : lastSigned.get("signed_at", OffsetDateTime.class);
      if (signedAt == null) {
        signedAt = OffsetDateTime.now(ZoneOffset.UTC);
      }
      signedBy = lastSigned == null ? "" : text(lastSigned.get("signed_by", String.class));
    }
    sql.execute(
        "update influencer_sample_order set status = ?, outbound_status = 'LOCAL_OUTBOUNDED',"
            + " outbound_at = ?::timestamptz, signed_at = ?::timestamptz, signed_by = ?,"
            + " updated_at = now(), version = version + 1 where tenant_id = ?::uuid and id = ?::uuid",
        derived,
        outboundAt,
        signedAt,
        signedBy,
        currentTenant(),
        sampleOrderId);
  }

  private Record shipmentRecord(UUID shipmentId) {
    return sql.fetchOne(
        "select id, sample_order_id, status, delivered_at, signed_at from influencer_sample_shipment"
            + " where tenant_id = "
            + TENANT
            + " and id = ?",
        shipmentId);
  }

  private InfluencerSampleShipmentRow shipmentById(UUID shipmentId) {
    Record r =
        sql.fetchOne(
            "select sh.id, sh.sample_order_id, sh.carrier_code, sh.tracking_no, sh.package_count,"
                + " sh.status, sh.shipped_at, sh.delivered_at, sh.signed_at, sh.signed_by,"
                + " sh.tracking_available, sh.tracking_owner_module, sh.note, sh.created_at, sh.updated_at"
                + " from influencer_sample_shipment sh where sh.tenant_id = "
                + TENANT
                + " and sh.id = ?",
            shipmentId);
    if (r == null) {
      throw notFound("包裹");
    }
    return new InfluencerSampleShipmentRow(
        r.get("id", UUID.class),
        r.get("sample_order_id", UUID.class),
        r.get("carrier_code", String.class),
        r.get("tracking_no", String.class),
        r.get("package_count", BigDecimal.class),
        r.get("status", String.class),
        r.get("shipped_at", OffsetDateTime.class),
        r.get("delivered_at", OffsetDateTime.class),
        r.get("signed_at", OffsetDateTime.class),
        r.get("signed_by", String.class),
        Boolean.TRUE.equals(r.get("tracking_available", Boolean.class)),
        r.get("tracking_owner_module", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ==================================================================
  // 佣金规则
  // ==================================================================

  public InfluencerCommissionRulePage commissionRules(
      String scopeType, String status, String keyword, int page, int size) {
    StringBuilder where = new StringBuilder(" where r.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    String scope = blankToNull(scopeType);
    if (scope != null) {
      where.append(" and r.scope_type = ?");
      binds.add(scope.toUpperCase(Locale.ROOT));
    }
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and r.status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(" and (r.rule_code ilike ? or r.rule_name ilike ?)");
      String pattern = "%" + escapeLike(word) + "%";
      binds.add(pattern);
      binds.add(pattern);
    }
    int safeSize = safeSize(size);
    int safePage = safePage(page);
    long total = count("select count(*) from influencer_commission_rule r" + where, binds.toArray());
    List<InfluencerCommissionRuleRow> items =
        sql.fetch(
                commissionRuleSelect()
                    + where
                    + " order by r.rule_code, r.id limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::commissionRuleRow)
            .toList();
    return new InfluencerCommissionRulePage(items, total, safePage, safeSize);
  }

  private String commissionRuleSelect() {
    return "select r.id, r.rule_code, r.rule_name, r.scope_type, r.segment_id,"
        + " s.segment_name, r.influencer_id, i.display_name influencer_name,"
        + " r.commission_type, r.commission_rate, r.fixed_amount, r.currency,"
        + " r.effective_from, r.effective_to, r.status, r.note, r.version, r.created_at, r.updated_at"
        + " from influencer_commission_rule r"
        + " left join influencer_outreach_segment s on s.tenant_id = r.tenant_id and s.id = r.segment_id"
        + " left join influencer i on i.tenant_id = r.tenant_id and i.id = r.influencer_id";
  }

  private InfluencerCommissionRuleRow commissionRuleRow(Record r) {
    return new InfluencerCommissionRuleRow(
        r.get("id", UUID.class),
        r.get("rule_code", String.class),
        r.get("rule_name", String.class),
        r.get("scope_type", String.class),
        r.get("segment_id", UUID.class),
        r.get("segment_name", String.class),
        r.get("influencer_id", UUID.class),
        r.get("influencer_name", String.class),
        r.get("commission_type", String.class),
        r.get("commission_rate", BigDecimal.class),
        r.get("fixed_amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class),
        r.get("status", String.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  public InfluencerCommissionRuleRow saveCommissionRule(SaveInfluencerCommissionRuleRequest request) {
    ValidatedRule rule = validateCommissionRule(request);
    UUID id = Ids.next();
    sql.execute(
        "insert into influencer_commission_rule (tenant_id,id,rule_code,rule_name,scope_type,"
            + " segment_id,influencer_id,commission_type,commission_rate,fixed_amount,currency,"
            + " effective_from,effective_to,status,note,version,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,0,now(),now())",
        currentTenant(),
        id,
        rule.ruleCode(),
        rule.ruleName(),
        rule.scopeType(),
        rule.segmentId(),
        rule.influencerId(),
        rule.commissionType(),
        rule.commissionRate(),
        rule.fixedAmount(),
        rule.currency(),
        rule.effectiveFrom(),
        rule.effectiveTo(),
        rule.status(),
        text(request.note()));
    return commissionRuleById(id);
  }

  /** 佣金规则的校验结果（save 与 update 共用，避免两处口径漂移）。 */
  private record ValidatedRule(
      String ruleCode,
      String ruleName,
      String scopeType,
      UUID segmentId,
      UUID influencerId,
      String commissionType,
      BigDecimal commissionRate,
      BigDecimal fixedAmount,
      String currency,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      String status) {}

  private ValidatedRule validateCommissionRule(SaveInfluencerCommissionRuleRequest request) {
    String code = text(request.ruleCode());
    String name = text(request.ruleName());
    if (code.isEmpty() || name.isEmpty()) {
      throw badRequest("规则编码与规则名称都不能为空");
    }
    String scope =
        text(request.scopeType()).isEmpty()
            ? "GLOBAL"
            : text(request.scopeType()).toUpperCase(Locale.ROOT);
    if (!COMMISSION_SCOPES.contains(scope)) {
      throw badRequest("佣金适用范围不合法：" + request.scopeType());
    }
    String type = requireEnum(request.commissionType(), COMMISSION_TYPES, "佣金形态");
    BigDecimal rate = request.commissionRate();
    BigDecimal fixed = request.fixedAmount();
    if ("RATE".equals(type)) {
      if (rate == null) {
        throw badRequest("比例形态必须给佣金比例");
      }
      if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
        throw badRequest("佣金比例必须在 0 ~ 1 之间");
      }
      if (fixed != null) {
        // 🔴 混合形态（「先给 5% 再减 20 美元」）在服务层就拒 ——
        //   那种口径在计提时算不清（先乘后减还是先减后乘，差一个数量级）。
        throw badRequest("比例形态不能同时给固定金额（混合形态算不清）");
      }
    } else {
      if (fixed == null) {
        throw badRequest("固定额形态必须给固定金额");
      }
      if (fixed.signum() < 0) {
        throw badRequest("固定金额不能是负数");
      }
      if (rate != null) {
        throw badRequest("固定额形态不能同时给佣金比例（混合形态算不清）");
      }
    }
    UUID segmentId = null;
    UUID influencerId = null;
    if ("SEGMENT".equals(scope)) {
      if (request.segmentId() == null || segmentRecord(request.segmentId()) == null) {
        throw badRequest("适用范围是 SEGMENT 时必须给一个存在的筛选条件");
      }
      segmentId = request.segmentId();
    }
    if ("INFLUENCER".equals(scope)) {
      if (request.influencerId() == null || influencerRecord(request.influencerId()) == null) {
        throw badRequest("适用范围是 INFLUENCER 时必须给一个存在的达人");
      }
      influencerId = request.influencerId();
    }
    OffsetDateTime effectiveFrom =
        request.effectiveFrom() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.effectiveFrom();
    if (request.effectiveTo() != null && !request.effectiveTo().isAfter(effectiveFrom)) {
      throw badRequest("生效止必须晚于生效起");
    }
    String status =
        text(request.status()).isEmpty() ? "ACTIVE" : text(request.status()).toUpperCase(Locale.ROOT);
    if (!CONFIG_STATUSES.contains(status)) {
      throw badRequest("状态不合法：" + request.status());
    }
    return new ValidatedRule(
        code,
        name,
        scope,
        segmentId,
        influencerId,
        type,
        "RATE".equals(type) ? rate : null,
        "FIXED".equals(type) ? fixed : null,
        normalizeCurrency(request.currency()),
        effectiveFrom,
        request.effectiveTo(),
        status);
  }

  public InfluencerCommissionRuleRow updateCommissionRule(
      String id, SaveInfluencerCommissionRuleRequest request) {
    UUID ruleId = parseId(id);
    if (commissionRuleRecord(ruleId) == null) {
      throw notFound("佣金规则");
    }
    ValidatedRule rule = validateCommissionRule(request);
    sql.execute(
        "update influencer_commission_rule set rule_code = ?, rule_name = ?, scope_type = ?,"
            + " segment_id = ?, influencer_id = ?, commission_type = ?, commission_rate = ?,"
            + " fixed_amount = ?, currency = ?, effective_from = ?::timestamptz,"
            + " effective_to = ?::timestamptz, status = ?, note = ?, updated_at = now(),"
            + " version = version + 1 where tenant_id = ?::uuid and id = ?::uuid",
        rule.ruleCode(),
        rule.ruleName(),
        rule.scopeType(),
        rule.segmentId(),
        rule.influencerId(),
        rule.commissionType(),
        rule.commissionRate(),
        rule.fixedAmount(),
        rule.currency(),
        rule.effectiveFrom(),
        rule.effectiveTo(),
        rule.status(),
        text(request.note()),
        currentTenant(),
        ruleId);
    return commissionRuleById(ruleId);
  }

  /** 删一条佣金规则（已被计提引用时返回 409 —— 计提是举证凭据）。 */
  public void deleteCommissionRule(String id) {
    UUID ruleId = parseId(id);
    if (commissionRuleRecord(ruleId) == null) {
      throw notFound("佣金规则");
    }
    long used =
        count(
            "select count(*) from influencer_commission_accrual where tenant_id = "
                + TENANT
                + " and rule_id = ?",
            ruleId);
    if (used > 0) {
      throw conflict("该佣金规则已被计提引用（举证凭据），不能物理删除。请改为停用（status=PAUSED）。");
    }
    sql.execute(
        "delete from influencer_commission_rule where tenant_id = ?::uuid and id = ?::uuid",
        currentTenant(),
        ruleId);
  }

  private Record commissionRuleRecord(UUID ruleId) {
    return sql.fetchOne(
        "select id, rule_code, commission_type, commission_rate, fixed_amount, scope_type, segment_id,"
            + " influencer_id, status, effective_from, effective_to"
            + " from influencer_commission_rule where tenant_id = "
            + TENANT
            + " and id = ?",
        ruleId);
  }

  private InfluencerCommissionRuleRow commissionRuleById(UUID ruleId) {
    Record r =
        sql.fetchOne(
            commissionRuleSelect() + " where r.tenant_id = " + TENANT + " and r.id = ?", ruleId);
    if (r == null) {
      throw notFound("佣金规则");
    }
    return commissionRuleRow(r);
  }

  /**
   * 解析当前生效的佣金规则。
   *
   * <p>🔴 优先级：<b>INFLUENCER（达人） > SEGMENT（分群） > GLOBAL（全局）</b>，
   *   同级取 {@code effective_from} 最新的一条。这样「给某个达人单独提价」不需要
   *   去动全局规则。
   */
  private Record resolveCommissionRule(UUID segmentId, UUID influencerId) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where r.tenant_id = " + TENANT + " and r.status = 'ACTIVE'");
    where.append(" and r.effective_from <= now()");
    where.append(" and (r.effective_to is null or r.effective_to > now())");
    where.append(
        " and ((r.scope_type = 'INFLUENCER' and r.influencer_id = ?)"
            + "   or (r.scope_type = 'SEGMENT' and r.segment_id = ?)"
            + "   or r.scope_type = 'GLOBAL')");
    binds.add(influencerId);
    binds.add(segmentId);
    Record r =
        sql.fetchOne(
            "select r.id, r.rule_code, r.commission_type, r.commission_rate, r.fixed_amount, r.currency,"
                + " case r.scope_type when 'INFLUENCER' then 3 when 'SEGMENT' then 2 else 1 end scope_rank,"
                + " r.effective_from"
                + " from influencer_commission_rule r"
                + where
                + " order by scope_rank desc, r.effective_from desc, r.id limit 1",
            binds.toArray());
    return r;
  }

  // ==================================================================
  // 佣金计提
  // ==================================================================

  public InfluencerCommissionAccrualPage accruals(
      UUID outreachId, UUID influencerId, String status, String sort, int page, int size) {
    StringBuilder where = new StringBuilder(" where ca.tenant_id = " + TENANT);
    List<Object> binds = new ArrayList<>();
    if (outreachId != null) {
      where.append(" and ca.outreach_id = ?");
      binds.add(outreachId);
    }
    if (influencerId != null) {
      where.append(" and ca.influencer_id = ?");
      binds.add(influencerId);
    }
    String state = blankToNull(status);
    if (state != null) {
      where.append(" and ca.status = ?");
      binds.add(state.toUpperCase(Locale.ROOT));
    }
    int safeSize = safeSize(size);
    int safePage = safePage(page);
    String order =
        ACCRUAL_SORTS.getOrDefault(text(sort).toUpperCase(Locale.ROOT), ACCRUAL_SORTS.get("ACCRUED_DESC"));
    long total = count("select count(*) from influencer_commission_accrual ca" + where, binds.toArray());
    List<InfluencerCommissionAccrualRow> items =
        sql.fetch(
                accrualSelect()
                    + where
                    + " order by "
                    + order
                    + " limit ? offset ?",
                concat(binds, safeSize, safePage * safeSize))
            .stream()
            .map(this::accrualRow)
            .toList();
    return new InfluencerCommissionAccrualPage(items, total, safePage, safeSize);
  }

  private String accrualSelect() {
    return "select ca.id, ca.outreach_id, ca.influencer_id, i.display_name influencer_name,"
        + " ca.rule_id, r.rule_code, ca.commission_type, ca.commission_rate, ca.fixed_amount,"
        + " ca.base_amount, ca.base_currency, ca.accrued_amount, ca.accrued_currency, ca.status,"
        + " ca.finance_posted, ca.finance_owner_module, ca.accrued_at, ca.note, ca.version,"
        + " ca.created_at, ca.updated_at"
        + " from influencer_commission_accrual ca"
        + " join influencer i on i.tenant_id = ca.tenant_id and i.id = ca.influencer_id"
        + " left join influencer_commission_rule r on r.tenant_id = ca.tenant_id and r.id = ca.rule_id";
  }

  private InfluencerCommissionAccrualRow accrualRow(Record r) {
    return new InfluencerCommissionAccrualRow(
        r.get("id", UUID.class),
        r.get("outreach_id", UUID.class),
        r.get("influencer_id", UUID.class),
        r.get("influencer_name", String.class),
        r.get("rule_id", UUID.class),
        r.get("rule_code", String.class),
        r.get("commission_type", String.class),
        r.get("commission_rate", BigDecimal.class),
        r.get("fixed_amount", BigDecimal.class),
        r.get("base_amount", BigDecimal.class),
        r.get("base_currency", String.class),
        r.get("accrued_amount", BigDecimal.class),
        r.get("accrued_currency", String.class),
        r.get("status", String.class),
        Boolean.TRUE.equals(r.get("finance_posted", Boolean.class)),
        r.get("finance_owner_module", String.class),
        r.get("accrued_at", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 登记一笔佣金计提。
   *
   * <p>🔴 计提金额由**服务层按规则算**（RATE ⇒ 基数 × 比例；FIXED ⇒ 固定额），
   *   规则快照一起存下来（规则后来改了，已计提的这行不动）。
   *
   * <p>🔴 {@code finance_posted} **恒为 false**：12 财务未交付 ⇒ 未进费用归集。
   */
  public InfluencerCommissionAccrualRow createAccrual(
      CreateInfluencerCommissionAccrualRequest request) {
    Record outreach = outreachRecord(request.outreachId());
    if (outreach == null) {
      throw notFound("建联记录");
    }
    UUID influencerId = outreach.get("influencer_id", UUID.class);
    UUID segmentId = outreach.get("segment_id", UUID.class);
    Record rule;
    if (request.ruleId() != null) {
      rule = commissionRuleRecord(request.ruleId());
      if (rule == null) {
        throw notFound("佣金规则");
      }
    } else {
      rule = resolveCommissionRule(segmentId, influencerId);
      if (rule == null) {
        throw badRequest(
            "没有解析到生效的佣金规则（分群 / 达人 / 全局都没有生效规则）。"
                + "请先配置佣金规则，或显式指定 ruleId。");
      }
    }
    String type = rule.get("commission_type", String.class);
    BigDecimal rate = rule.get("commission_rate", BigDecimal.class);
    BigDecimal fixed = rule.get("fixed_amount", BigDecimal.class);
    BigDecimal base = request.baseAmount() == null ? BigDecimal.ZERO : request.baseAmount();
    if (base.signum() < 0) {
      throw badRequest("计提基数不能是负数");
    }
    BigDecimal accrued;
    try {
      accrued = accrue(type, rate, fixed, base);
    } catch (IllegalArgumentException e) {
      throw badRequest(e.getMessage());
    }
    String baseCurrency = normalizeCurrency(request.baseCurrency());
    String currency =
        baseCurrency.isEmpty()
            ? text(rule.get("currency", String.class)).toUpperCase(Locale.ROOT)
            : baseCurrency;
    UUID id = Ids.next();
    sql.execute(
        "insert into influencer_commission_accrual (tenant_id,id,outreach_id,influencer_id,rule_id,"
            + " commission_type,commission_rate,fixed_amount,base_amount,base_currency,"
            + " accrued_amount,accrued_currency,status,finance_posted,finance_owner_module,"
            + " accrued_at,note,version,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,'ACCRUED',false,'12 财务',now(),?,0,now(),now())",
        currentTenant(),
        id,
        request.outreachId(),
        influencerId,
        rule.get("id", UUID.class),
        type,
        rate,
        fixed,
        base,
        baseCurrency,
        accrued,
        currency,
        text(request.note()));
    return accrualById(id);
  }

  private InfluencerCommissionAccrualRow accrualById(UUID accrualId) {
    Record r =
        sql.fetchOne(accrualSelect() + " where ca.tenant_id = " + TENANT + " and ca.id = ?", accrualId);
    if (r == null) {
      throw notFound("佣金计提");
    }
    return accrualRow(r);
  }

  private List<InfluencerCommissionAccrualRow> accrualsOf(UUID outreachId) {
    return sql.fetch(
            accrualSelect()
                + " where ca.tenant_id = "
                + TENANT
                + " and ca.outreach_id = ? order by ca.accrued_at desc, ca.id",
            outreachId)
        .stream()
        .map(this::accrualRow)
        .toList();
  }
}
