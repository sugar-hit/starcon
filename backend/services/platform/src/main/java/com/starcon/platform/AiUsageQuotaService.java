package com.starcon.platform;

import static com.starcon.platform.AiUsageQuotaModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.4.3.1「使用与配额」的服务层。
 *
 * <p>蓝图：「<b>职责</b>：管理 AI 使用：按租户与用户的调用量与成本、配额限制、模型选择、
 * 敏感数据处理策略开关。<b>输入</b>：调用记录；配额配置。<b>输出</b>：用量报表 + 配额执行。
 * <b>关系</b>：上游 = 18.4 全部；下游 = 17.6.1 计费。」
 *
 * <p>数据访问一律走 <b>jOOQ 显式 SQL</b>（AGENTS.md：不用 JPA 懒加载掩盖关键扣减逻辑）。
 * 租户上下文由 {@link AccessService#run} 绑定 —— 本类<b>不</b>读任何请求头里的租户。
 *
 * <p><b>六条纪律：</b>
 *
 * <ol>
 *   <li><b>归属只来自会话</b>：{@code tenant_id} 与 {@code membership_id} 都取自
 *       {@link AccessService.Access}；请求体里<b>没有</b>这三个字段。
 *   <li><b>成本不硬编码</b>：单价来自租户可维护的模型目录，写台账时<b>快照</b>下来；
 *       目录改价不动历史。
 *   <li><b>配额计数在 DB 侧原子完成</b>：递增是一条
 *       {@code INSERT ... ON CONFLICT DO UPDATE SET used = used + EXCLUDED.used}，
 *       由数据库行锁串行化 —— <b>禁止</b>「Java 先读再写」（那种写法并发下会丢计数）。
 *   <li><b>量纲显式</b>：单位成本的分母是「1 个 unit」；以「次」为量纲的模型，调用量只能是 1。
 *   <li><b>不混算</b>：不同模型 / 不同币种的量纲与金额**不**加成一个总数 ——
 *       那个数没有业务含义。
 *   <li><b>不直连 finance</b>：只提供可计费口径的汇总；不建跨库 FK、不写对方的表。
 * </ol>
 */
@Service
public class AiUsageQuotaService {

  /** 金额与用量的标度（对应 {@code NUMERIC(19,6)}）。 */
  static final int SCALE = 6;

  /** 能力码：大写字母 / 数字 / 下划线。取值域是**开放的**（各 AI 能力自己回报）。 */
  private static final Pattern CAPABILITY = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

  /** 空 UUID 哨兵：用于把 TENANT 级配额的 NULL 主体折成一个可参与唯一约束的值。 */
  private static final String TENANT_SUBJECT = "00000000-0000-0000-0000-000000000000";

  /** 「全部能力」的通配码。 */
  static final String ANY_CAPABILITY = "*";

  private final DSLContext sql;

  public AiUsageQuotaService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 工具

  private static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** 状态冲突（409）：不是「参数不对」，是「当前状态不允许」。 */
  private static void conflict(String message) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static String required(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(!trimmed.isEmpty(), label + "不能为空");
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  private static String optional(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  /** 正十进制数（十进制文本 → BigDecimal）。禁浮点：只接受字符串形态的十进制。 */
  private static BigDecimal positiveDecimal(String raw, String label) {
    var text = raw == null ? "" : raw.trim();
    check(!text.isEmpty(), label + "不能为空");
    check(text.matches("\\d+(\\.\\d+)?"), label + "必须是不带符号的十进制数");
    var value = new BigDecimal(text);
    check(value.signum() > 0, label + "必须大于 0");
    return value;
  }

  /** 非负十进制数（可为空）。 */
  private static BigDecimal nonNegativeDecimal(String raw, String label) {
    if (raw == null || raw.isBlank()) return null;
    var text = raw.trim();
    check(text.matches("\\d+(\\.\\d+)?"), label + "必须是不带符号的十进制数");
    return new BigDecimal(text);
  }

  /** 十进制文本（对外暴露的口径：全程字符串，前端不碰浮点）。 */
  private static String money(BigDecimal value) {
    if (value == null) return "0";
    return value.setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
  }

  /** 能力码：归一化成大写并校验字面量。 */
  private static String capabilityCode(String raw) {
    var text = required(raw, "能力码", 64).toUpperCase(Locale.ROOT);
    check(CAPABILITY.matcher(text).matches(), "能力码只能是大写字母、数字与下划线（如 TEXT_TRANSLATION）");
    return text;
  }

  /** 事务局部 GUC：租户由 AccessService 绑定，这里读的是**同一个**绑定。 */
  private UUID currentTenant() {
    var raw =
        sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    check(raw != null && !raw.isBlank(), "缺少可信租户上下文");
    return UUID.fromString(raw);
  }

  /** DB 时钟：落库时间一律取它（见 V425 迁移第 2 节）。 */
  private OffsetDateTime dbNow() {
    return sql.fetchOne("select clock_timestamp() as t").get(0, OffsetDateTime.class);
  }

  /**
   * 配额周期窗口（左闭右开）。
   *
   * <p>🔴 固定按 <b>UTC 自然日 / 自然月</b>切：周期边界是跨租户对齐的口径
   * （下游 17.6.1 计费按同一口径切账期），用本地时区会让同一个配额算出不同的窗口。
   */
  private static OffsetDateTime[] window(QuotaPeriod period, OffsetDateTime now) {
    var utc = now.withOffsetSameInstant(ZoneOffset.UTC);
    OffsetDateTime start;
    if (period == QuotaPeriod.DAY) {
      start = utc.truncatedTo(ChronoUnit.DAYS);
      return new OffsetDateTime[] {start, start.plusDays(1)};
    }
    start = utc.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
    return new OffsetDateTime[] {start, start.plusMonths(1)};
  }

  // ================================================================ 能力声明

  /**
   * 18.4.3.1 的能力声明：逐维说清「有没有、依据是什么、缺了归谁」。
   *
   * <p>🔴 **敏感数据策略那一维必须写清执行点**：本仓**没有**全局 AI 拦截点，
   * 策略的执行点是**各个 AI 能力自己**（调用前查本表）。
   * 能力声明把这一格写清楚，「缺在谁身上」才是一条**可查的事实**。
   */
  public AiUsageCapabilities capabilities() {
    var capabilities =
        List.of(
            new AiUsageCapability(
                "18.4.3.1",
                "按租户与用户的调用量与成本",
                true,
                "platform_ai_usage_record 台账（tenant_id + membership_id 必填）"
                    + " + GET /platform/ai/usage/summary 报表",
                "",
                ""),
            new AiUsageCapability(
                "18.4.3.1",
                "调用量**量纲明确**（次 / Token / 千 Token / 字符）",
                true,
                "platform_ai_model.unit 定义量纲，unit_cost 的分母就是「1 个 unit」；"
                    + "台账行冗余记下当时的量纲，报表按「能力 × 模型」分组",
                "",
                ""),
            new AiUsageCapability(
                "18.4.3.1",
                "成本**不硬编码** + 单价**快照** + **不混算币种**",
                true,
                "单价落 platform_ai_model（租户可维护，平台零预置）；"
                    + "写台账时把当时的 unit_cost 快照进台账行，目录改价不动历史；"
                    + "配额的成本上限声明自己的币种，币种不符的调用**照计次数、不计成本**"
                    + "（不同币种的钱加在一起没有业务含义）",
                "",
                ""),
            new AiUsageCapability(
                "18.4.3.1",
                "租户级 / 用户级配额（次数上限 / 成本上限 × 日 / 月）",
                true,
                "platform_ai_quota 配置 + platform_ai_quota_usage 计数；"
                    + "用户级配额的主体成员来自服务端解析的会话成员或路径指定的成员，"
                    + "**请求体不接受 membershipId**",
                "",
                ""),
            new AiUsageCapability(
                "18.4.3.1",
                "配额超限告知（超了哪一条 / 当前用量 / 阈值 / 周期 / 什么时候重置）",
                true,
                "AiQuotaViolation 五要素齐备；周期边界固定 UTC 自然日 / 自然月，重置时刻是确定值",
                "",
                ""),
            new AiUsageCapability(
                "18.4.3.1",
                "配额计数的**并发正确性**",
                true,
                "递增由 DB 侧单语句 `INSERT ... ON CONFLICT (quota_id, period_start) DO UPDATE "
                    + "SET used_count = ...used_count + EXCLUDED.used_count` 完成（行锁串行化，"
                    + "返回值即递增后的累计值）；**没有**「Java 先 SELECT 再 UPDATE」的读后写",
                "",
                ""),
            new AiUsageCapability(
                "18.4.3.1",
                "模型选择（目录 + 上下文上限 + 是否可用）",
                true,
                "platform_ai_model 目录；停用的模型不能新记用量（历史台账不受影响）",
                "",
                ""),
            new AiUsageCapability(
                "18.4.3.1",
                "敏感数据处理策略（禁传 / 脱敏 / 允许）落库与查询",
                true,
                "platform_ai_sensitive_policy（能力 × 模型 × 动作）+ GET /platform/ai/usage/policies；"
                    + "🔴 **执行点是各个 AI 能力自己在调用前查这条策略** —— "
                    + "本仓没有统一的 AI 出站拦截点",
                "",
                ""));
    var gaps =
        List.of(
            new AiUsageGap(
                "18.4",
                "全局 AI 出站拦截点",
                "platform",
                false,
                "执行点",
                "本仓 backend/services 下**不存在**统一的 AI 出站网关，"
                    + "所以本模块**不是**敏感数据的全局拦截器："
                    + "策略落库 + 可查询，由**各 AI 能力在调用前**来查并自行执行"),
            new AiUsageGap(
                "17.6.1",
                "计费",
                "finance",
                false,
                "下游",
                "本模块只提供**可计费口径**的用量汇总（GET /platform/ai/usage/summary，"
                    + "粒度 = 能力 × 模型 × 币种）；**不直连 finance 库、不建跨库 FK、不写 finance 表** —— "
                    + "计费侧自行取数或由事件消费"),
            new AiUsageGap(
                "18.4",
                "对话助手 / 智能查询 / 选品预测 / 异常诊断（上游 AI 能力）",
                "platform",
                false,
                "上游",
                "这些能力尚未交付 ⇒ 台账里**还没有真实调用记录**；"
                    + "本模块的记录入口已就绪（POST /platform/ai/usage/records），"
                    + "各能力接上后自行回报（主体成员由服务端从会话解析）"),
            new AiUsageGap(
                "18.4.3.1",
                "「成员是否属于本租户」查询",
                "identity",
                false,
                "上游",
                "identity 侧只有 /internal/access，**没有**成员归属查询端点 ⇒ "
                    + "管理员代配用户级配额时无法校验成员归属；"
                    + "写进去的配额若指向非本租户成员，永远匹配不到用量（惰性行），不会越权"),
            new AiUsageGap(
                "18.4.3.1",
                "配额**预留**（调用前占位）",
                "platform",
                false,
                "执行点",
                "预检（POST /platform/ai/usage/quotas/check）是**读快照**，**不**占位额度；"
                    + "并发下的权威判定发生在**记用量**那一次的 DB 侧原子递增，"
                    + "因此「预检通过」不等于「这次一定放得下」"));
    return new AiUsageCapabilities(capabilities, gaps);
  }

  // ================================================================ 模型目录

  /** 模型目录列表（真筛选）。 */
  public List<AiModelRow> models(Boolean enabled, String keyword) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (enabled != null) {
      where.append(" and enabled = ?");
      params.add(enabled);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (model_code like ? or model_name like ?)");
      var like = "%" + keyword.trim() + "%";
      params.add(like);
      params.add(like);
    }
    return sql.fetch(
            "select id,model_code,model_name,provider,unit,unit_cost,currency,context_limit,"
                + " enabled,note,created_at,updated_at from platform_ai_model"
                + where
                + " order by model_code",
            params.toArray())
        .map(AiUsageQuotaService::toModelRow);
  }

  /** 登记 / 修改一个模型（**原子 upsert**：同一租户同一 model_code 只有一行）。 */
  public AiModelRow saveModel(AiModelInput input) {
    var modelCode = required(input.modelCode(), "模型标识", 64);
    var modelName = required(input.modelName(), "模型名称", 200);
    var provider = optional(input.provider(), "供应商", 64);
    var unit = UsageUnit.parse(input.unit());
    check(unit != null, "量纲只能是 REQUEST / TOKEN / THOUSAND_TOKEN / CHARACTER");
    var unitCost = nonNegativeDecimal(input.unitCost(), "单位成本");
    check(unitCost != null, "单位成本不能为空");
    var currency = optional(input.currency(), "币种", 3).toUpperCase(Locale.ROOT);
    check(currency.length() == 3, "币种必须是 3 位字母（如 USD）");
    var contextLimit = input.contextLimit() == null ? 0 : input.contextLimit();
    check(contextLimit >= 0, "上下文上限不能为负");
    var enabled = input.enabled() == null || input.enabled();
    var note = optional(input.note(), "备注", 500);

    var now = dbNow();
    var id =
        sql.fetchOne(
                "insert into platform_ai_model(id,tenant_id,model_code,model_name,provider,unit,"
                    + " unit_cost,currency,context_limit,enabled,note,created_at,updated_at)"
                    + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)"
                    + " on conflict (tenant_id, model_code) do update"
                    + " set model_name = excluded.model_name, provider = excluded.provider,"
                    + " unit = excluded.unit, unit_cost = excluded.unit_cost,"
                    + " currency = excluded.currency, context_limit = excluded.context_limit,"
                    + " enabled = excluded.enabled, note = excluded.note,"
                    + " updated_at = excluded.updated_at"
                    + " returning id",
                UUID.randomUUID(),
                currentTenant(),
                modelCode,
                modelName,
                provider,
                unit.name(),
                unitCost,
                currency,
                contextLimit,
                enabled,
                note,
                now,
                now)
            .get("id", UUID.class);
    return modelRow(id);
  }

  private AiModelRow modelRow(UUID id) {
    return sql.fetchOne(
            "select id,model_code,model_name,provider,unit,unit_cost,currency,context_limit,"
                + " enabled,note,created_at,updated_at from platform_ai_model where id = ?",
            id)
        .map(AiUsageQuotaService::toModelRow);
  }

  private static AiModelRow toModelRow(org.jooq.Record r) {
    var unit = UsageUnit.parse(r.get("unit", String.class));
    return new AiModelRow(
        r.get("id", UUID.class),
        r.get("model_code", String.class),
        r.get("model_name", String.class),
        r.get("provider", String.class),
        unit == null ? "" : unit.name(),
        unit == null ? "" : unit.label(),
        unit == null ? "" : unit.description(),
        money(r.get("unit_cost", BigDecimal.class)),
        r.get("currency", String.class),
        r.get("context_limit", Integer.class),
        r.get("enabled", Boolean.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 用量台账

  /** 用量台账（真分页 + 真筛选）。 */
  public AiUsageRecordPage records(
      String capability, UUID membershipId, String modelCode, String from, String to, int page, int size) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (capability != null && !capability.isBlank()) {
      where.append(" and capability_code = ?");
      params.add(capability.trim().toUpperCase(Locale.ROOT));
    }
    if (membershipId != null) {
      where.append(" and membership_id = ?");
      params.add(membershipId);
    }
    if (modelCode != null && !modelCode.isBlank()) {
      where.append(" and model_code = ?");
      params.add(modelCode.trim());
    }
    var fromAt = parseInstant(from, "起始时刻");
    var toAt = parseInstant(to, "结束时刻");
    if (fromAt != null) {
      where.append(" and occurred_at >= ?::timestamptz");
      params.add(fromAt);
    }
    if (toAt != null) {
      where.append(" and occurred_at < ?::timestamptz");
      params.add(toAt);
    }
    var clause = where.toString();

    var total =
        sql.fetchOne("select count(*) from platform_ai_usage_record" + clause, params.toArray())
            .get(0, Long.class);
    var limit = Math.min(Math.max(size, 1), 200);
    var offset = Math.max(page, 0) * limit;
    var items =
        sql.fetch(
                "select id,membership_id,capability_code,feature_code,model_code,unit,quantity,"
                    + " unit_cost,cost,currency,request_ref,occurred_at"
                    + " from platform_ai_usage_record"
                    + clause
                    + " order by occurred_at desc, id desc limit ? offset ?",
                concat(params, limit, offset))
            .map(AiUsageQuotaService::toUsageRow);
    return new AiUsageRecordPage(items, Math.max(page, 0), limit, total);
  }

  /**
   * 记一次 AI 调用用量。
   *
   * <p>🔴 **这一条路径是配额的权威执行点**：它同时完成「计量」与「配额计数」，
   * 计数由 DB 侧单语句原子递增，返回值即递增后的累计值。
   */
  public AiUsageRecordResult record(AiUsageRecordInput input, AccessService.Access actor) {
    var capability = capabilityCode(input.capabilityCode());
    var feature = optional(input.featureCode(), "子功能", 64);
    var modelCode = required(input.modelCode(), "模型标识", 64);
    var requestRef = optional(input.requestRef(), "请求关联 id", 64);
    var quantity = positiveDecimal(input.quantity(), "调用量");

    var model =
        sql.fetchOne(
            "select id,model_code,model_name,unit,unit_cost,currency,enabled"
                + " from platform_ai_model where model_code = ?",
            modelCode);
    check(model != null, "模型目录里没有这个模型，请先登记");
    if (!model.get("enabled", Boolean.class)) conflict("模型已停用，不能再记用量");
    var unit = UsageUnit.parse(model.get("unit", String.class));
    check(unit != null, "模型目录里的量纲不合法");
    var unitCost = model.get("unit_cost", BigDecimal.class);
    var currency = model.get("currency", String.class);

    // 🔴 量纲纪律：以「次」为量纲的模型，一次调用就是 1 次 —— 不允许传 0.5 次。
    check(
        unit != UsageUnit.REQUEST || quantity.compareTo(BigDecimal.ONE) == 0,
        "以「次」为量纲的模型，调用量只能是 1");

    // 🔴 成本 = 调用量 × **快照下来的**单价；写死这一行，目录改价不动历史。
    var cost = quantity.multiply(unitCost).setScale(SCALE, RoundingMode.HALF_UP);

    var now = dbNow();
    var id = UUID.randomUUID();
    sql.execute(
        "insert into platform_ai_usage_record(id,tenant_id,membership_id,capability_code,"
            + " feature_code,model_id,model_code,unit,quantity,unit_cost,cost,currency,"
            + " request_ref,occurred_at,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        actor.membershipId(),
        capability,
        feature,
        model.get("id", UUID.class),
        model.get("model_code", String.class),
        unit.name(),
        quantity,
        unitCost,
        cost,
        currency,
        requestRef,
        now,
        now);

    var evaluation =
        evaluate(capability, actor.membershipId(), 1L, cost, currency, now, true);
    return new AiUsageRecordResult(usageRow(id), evaluation);
  }

  private AiUsageRecordRow usageRow(UUID id) {
    return sql.fetchOne(
            "select id,membership_id,capability_code,feature_code,model_code,unit,quantity,"
                + " unit_cost,cost,currency,request_ref,occurred_at"
                + " from platform_ai_usage_record where id = ?",
            id)
        .map(AiUsageQuotaService::toUsageRow);
  }

  private static AiUsageRecordRow toUsageRow(org.jooq.Record r) {
    var unit = UsageUnit.parse(r.get("unit", String.class));
    return new AiUsageRecordRow(
        r.get("id", UUID.class),
        r.get("membership_id", UUID.class),
        r.get("capability_code", String.class),
        r.get("feature_code", String.class),
        r.get("model_code", String.class),
        unit == null ? "" : unit.name(),
        unit == null ? "" : unit.label(),
        money(r.get("quantity", BigDecimal.class)),
        money(r.get("unit_cost", BigDecimal.class)),
        money(r.get("cost", BigDecimal.class)),
        r.get("currency", String.class),
        r.get("request_ref", String.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  // ================================================================ 用量报表

  /**
   * 用量报表（**可计费口径**）。
   *
   * <p>🔴 **不混算**：不同模型 / 不同币种的量纲与金额**不**加成一个总数。
   * 按「能力 × 模型 × 币种」给出可计费粒度，另给成员维度与币种维度的合计。
   */
  public AiUsageSummary summary(String from, String to) {
    var fromAt = parseInstant(from, "起始时刻");
    var toAt = parseInstant(to, "结束时刻");
    var now = dbNow();
    var start = fromAt == null ? now.minusDays(30) : fromAt;
    var end = toAt == null ? now : toAt;

    var byCapability =
        sql.fetch(
                "select r.capability_code, r.model_code, m.model_name, r.unit, r.currency,"
                    + " count(*) as calls, sum(r.quantity) as quantity, sum(r.cost) as cost"
                    + " from platform_ai_usage_record r"
                    + " left join platform_ai_model m on m.tenant_id = r.tenant_id and m.id = r.model_id"
                    + " where r.occurred_at >= ?::timestamptz and r.occurred_at < ?::timestamptz"
                    + " group by r.capability_code, r.model_code, m.model_name, r.unit, r.currency"
                    + " order by r.capability_code, r.model_code",
                start,
                end)
            .map(
                r -> {
                  var unit = UsageUnit.parse(r.get("unit", String.class));
                  return new AiUsageSummaryRow(
                      r.get("capability_code", String.class),
                      r.get("model_code", String.class),
                      r.get("model_name", String.class),
                      unit == null ? "" : unit.name(),
                      unit == null ? "" : unit.label(),
                      r.get("currency", String.class),
                      r.get("calls", Long.class),
                      money(r.get("quantity", BigDecimal.class)),
                      money(r.get("cost", BigDecimal.class)));
                });

    var byMember =
        sql.fetch(
                "select membership_id, currency, count(*) as calls, sum(cost) as cost"
                    + " from platform_ai_usage_record"
                    + " where occurred_at >= ?::timestamptz and occurred_at < ?::timestamptz"
                    + " group by membership_id, currency order by calls desc",
                start,
                end)
            .map(
                r ->
                    new AiUsageMemberRow(
                        r.get("membership_id", UUID.class),
                        r.get("currency", String.class),
                        r.get("calls", Long.class),
                        money(r.get("cost", BigDecimal.class))));

    var byCurrency =
        sql.fetch(
                "select currency, count(*) as calls, sum(cost) as cost"
                    + " from platform_ai_usage_record"
                    + " where occurred_at >= ?::timestamptz and occurred_at < ?::timestamptz"
                    + " group by currency order by currency",
                start,
                end)
            .map(
                r ->
                    new AiUsageCurrencyRow(
                        r.get("currency", String.class),
                        r.get("calls", Long.class),
                        money(r.get("cost", BigDecimal.class))));

    var totalCalls =
        sql.fetchOne(
                "select count(*) from platform_ai_usage_record"
                    + " where occurred_at >= ?::timestamptz and occurred_at < ?::timestamptz",
                start,
                end)
            .get(0, Long.class);

    var basis =
        "粒度 = 能力 × 模型 × 币种；成本 = 记用量那一刻**快照**的单价 × 调用量；"
            + "不同模型与不同币种**不混算**（Token 数与字符数相加没有业务含义）；"
            + "本口径**不含**任何税费、折扣与汇率换算 —— 计费侧的账期与口径归 17.6.1";
    return new AiUsageSummary(
        byCapability,
        byMember,
        byCurrency,
        totalCalls,
        start.toInstant().toString(),
        end.toInstant().toString(),
        basis);
  }

  // ================================================================ 配额

  /** 配额列表（含当前周期的用量与是否越线）。 */
  public List<AiQuotaRow> quotas() {
    var now = dbNow();
    return sql.fetch(
            "select id,scope,membership_id,capability_code,limit_count,limit_cost,currency,"
                + " period,enabled,note,created_at,updated_at from platform_ai_quota"
                + " order by scope, capability_code, period")
        .map(
            r -> {
              var id = r.get("id", UUID.class);
              var scope = QuotaScope.parse(r.get("scope", String.class));
              var period = QuotaPeriod.parse(r.get("period", String.class));
              var limitCount = r.get("limit_count", Long.class);
              var limitCost = r.get("limit_cost", BigDecimal.class);
              var w = window(period, now);
              var usage =
                  sql.fetchOne(
                          "select coalesce(used_count,0) as used_count, coalesce(used_cost,0) as used_cost"
                              + " from platform_ai_quota_usage where quota_id = ?"
                              + " and period_start = ?::timestamptz",
                          id,
                          w[0]);
              var usedCount = usage == null ? 0L : usage.get("used_count", Long.class);
              var usedCost = usage == null ? BigDecimal.ZERO : usage.get("used_cost", BigDecimal.class);
              var exceeded =
                  (limitCount != null && usedCount > limitCount)
                      || (limitCost != null && usedCost.compareTo(limitCost) > 0);
              return new AiQuotaRow(
                  id,
                  scope == null ? "" : scope.name(),
                  scope == null ? "" : scope.label(),
                  r.get("membership_id", UUID.class),
                  r.get("capability_code", String.class),
                  limitCount != null,
                  limitCount == null ? 0L : limitCount,
                  limitCost != null,
                  money(limitCost),
                  r.get("currency", String.class),
                  period == null ? "" : period.name(),
                  period == null ? "" : period.label(),
                  r.get("enabled", Boolean.class),
                  r.get("note", String.class),
                  usedCount,
                  money(usedCost),
                  exceeded,
                  w[0].toInstant().toString(),
                  w[1].toInstant().toString(),
                  r.get("created_at", OffsetDateTime.class),
                  r.get("updated_at", OffsetDateTime.class));
            });
  }

  /** 登记 / 修改一条**租户级**配额。 */
  public AiQuotaRow saveTenantQuota(AiQuotaInput input) {
    return saveQuota(input, QuotaScope.TENANT, null);
  }

  /**
   * 登记 / 修改一条**用户级**配额。
   *
   * @param memberId 主体成员。走**路径**而不是请求体 ——
   *     请求体里不接受 membershipId（客户端不能自称归属）。
   */
  public AiQuotaRow saveMemberQuota(UUID memberId, AiQuotaInput input) {
    check(memberId != null, "成员不能为空");
    return saveQuota(input, QuotaScope.USER, memberId);
  }

  private AiQuotaRow saveQuota(AiQuotaInput input, QuotaScope scope, UUID memberId) {
    var capability = capabilityOrAny(input.capabilityCode());
    var limitCount = input.limitCount();
    var limitCost = nonNegativeDecimal(input.limitCost(), "成本上限");
    check(limitCount != null || limitCost != null, "次数上限与成本上限至少配一个");
    var currency =
        (input.currency() == null || input.currency().isBlank() ? "USD" : input.currency())
            .trim()
            .toUpperCase(Locale.ROOT);
    check(currency.length() == 3, "币种必须是 3 位字母（如 USD）");
    if (limitCount != null) check(limitCount > 0, "次数上限必须大于 0");
    if (limitCost != null) check(limitCost.signum() > 0, "成本上限必须大于 0");
    var period = QuotaPeriod.parse(input.period());
    check(period != null, "配额周期只能是 DAY / MONTH");
    var enabled = input.enabled() == null || input.enabled();
    var note = optional(input.note(), "备注", 500);

    var now = dbNow();
    var quotaId =
        sql.fetchOne(
                "insert into platform_ai_quota(id,tenant_id,scope,membership_id,capability_code,"
                    + " limit_count,limit_cost,currency,period,enabled,note,created_at,updated_at)"
                    + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)"
                    + " on conflict (tenant_id, capability_code, period,"
                    + " (coalesce(membership_id, '" + TENANT_SUBJECT + "'::uuid))) do update"
                    + " set scope = excluded.scope, membership_id = excluded.membership_id,"
                    + " limit_count = excluded.limit_count, limit_cost = excluded.limit_cost,"
                    + " currency = excluded.currency,"
                    + " enabled = excluded.enabled, note = excluded.note, updated_at = excluded.updated_at"
                    + " returning id",
                UUID.randomUUID(),
                currentTenant(),
                scope.name(),
                memberId,
                capability,
                limitCount,
                limitCost,
                currency,
                period.name(),
                enabled,
                note,
                now,
                now)
            .get("id", UUID.class);
    return quotas().stream().filter(q -> q.id().equals(quotaId)).findFirst().orElseThrow();
  }

  /** 能力码或通配码（'*' = 全部能力）。 */
  private static String capabilityOrAny(String raw) {
    var text = raw == null ? "" : raw.trim();
    if (text.isEmpty() || ANY_CAPABILITY.equals(text)) return ANY_CAPABILITY;
    return capabilityCode(text);
  }

  /**
   * 配额预检（**快照**，不占位额度）。
   *
   * <p>🔴 并发下的**权威**判定发生在记用量那一次的 DB 侧原子递增；
   * 这里的预检只回答「按当前用量算，还放得下吗」。
   */
  public AiQuotaEvaluation check(AiQuotaCheckInput input, AccessService.Access actor) {
    var capability = capabilityCode(input.capabilityCode());
    var callCount = input.callCount() == null || input.callCount() <= 0 ? 1L : input.callCount();
    var modelCode = optional(input.modelCode(), "模型标识", 64);

    BigDecimal projectedCost = BigDecimal.ZERO;
    String projectedCurrency = "";
    if (!modelCode.isEmpty()) {
      var model =
          sql.fetchOne(
              "select unit, unit_cost, currency from platform_ai_model where model_code = ?",
              modelCode);
      if (model != null && input.quantity() != null && !input.quantity().isBlank()) {
        var quantity = positiveDecimal(input.quantity(), "调用量");
        projectedCost = quantity.multiply(model.get("unit_cost", BigDecimal.class));
        projectedCurrency = model.get("currency", String.class);
      }
    }
    return evaluate(
            capability,
            actor.membershipId(),
            callCount,
            projectedCost,
            projectedCurrency,
            dbNow(),
            false);
  }

  // ================================================================ 配额判定（核心）

  /**
   * 对一次用量事件做配额判定。
   *
   * @param increment true = 记用量（**递增**计数，返回递增后的累计值）；
   *     false = 预检（只读当前计数，把将要发生的调用**投影**上去）
   */
  private AiQuotaEvaluation evaluate(
      String capability,
      UUID membership,
      long callCount,
      BigDecimal cost,
      String currency,
      OffsetDateTime now,
      boolean increment) {
    var quotas =
        sql.fetch(
            "select id,scope,membership_id,capability_code,limit_count,limit_cost,currency,period"
                + " from platform_ai_quota where enabled = true"
                + " and (scope = 'TENANT' or (scope = 'USER' and membership_id = ?))"
                + " and (capability_code = ? or capability_code = ?)",
            membership,
            capability,
            ANY_CAPABILITY);

    var violations = new ArrayList<AiQuotaViolation>();
    for (var quota : quotas) {
      var quotaId = quota.get("id", UUID.class);
      var scope = QuotaScope.parse(quota.get("scope", String.class));
      var period = QuotaPeriod.parse(quota.get("period", String.class));
      var limitCount = quota.get("limit_count", Long.class);
      var limitCost = quota.get("limit_cost", BigDecimal.class);
      var quotaCurrency = quota.get("currency", String.class);
      var w = window(period, now);

      // 🔴 **不同币种不混算**：本条配额的成本上限按它自己的币种计量，
      //    币种不符的调用**照计次数、不计成本**。
      var costToAdd =
          quotaCurrency != null && quotaCurrency.equals(currency) ? cost : BigDecimal.ZERO;

      long usedCount;
      BigDecimal usedCost;
      if (increment) {
        // 🔴 **DB 侧原子递增**：行锁把并发递增串行化，返回值就是递增**之后**的累计值。
        //    这里**没有**「Java 先 SELECT 再 UPDATE」—— 那种读后写在并发下会丢计数。
        var row =
            sql.fetchOne(
                "insert into platform_ai_quota_usage(id,tenant_id,quota_id,period_start,period_end,"
                    + " used_count,used_cost,updated_at)"
                    + " values(?,?,?,?::timestamptz,?::timestamptz,1,?,?::timestamptz)"
                    + " on conflict (quota_id, period_start) do update"
                    + " set used_count = platform_ai_quota_usage.used_count + excluded.used_count,"
                    + " used_cost = platform_ai_quota_usage.used_cost + excluded.used_cost,"
                    + " updated_at = excluded.updated_at"
                    + " returning used_count, used_cost",
                UUID.randomUUID(),
                currentTenant(),
                quotaId,
                w[0],
                w[1],
                costToAdd,
                now);
        usedCount = row.get("used_count", Long.class);
        usedCost = row.get("used_cost", BigDecimal.class);
      } else {
        var row =
            sql.fetchOne(
                "select coalesce(used_count,0) as used_count, coalesce(used_cost,0) as used_cost"
                    + " from platform_ai_quota_usage where quota_id = ?"
                    + " and period_start = ?::timestamptz",
                quotaId,
                w[0]);
        usedCount = row == null ? 0L : row.get("used_count", Long.class);
        usedCost = row == null ? BigDecimal.ZERO : row.get("used_cost", BigDecimal.class);
      }

      // 预检：把「即将发生的调用」投影上去再比阈值。
      var projectedCount = usedCount + (increment ? 0L : callCount);
      var projectedCost = usedCost.add(increment ? BigDecimal.ZERO : costToAdd);

      if (limitCount != null && projectedCount > limitCount) {
        violations.add(
            violation(
                quotaId,
                scope,
                quota.get("membership_id", UUID.class),
                quota.get("capability_code", String.class),
                "COUNT",
                Long.toString(limitCount),
                Long.toString(projectedCount),
                period,
                w,
                "次数上限",
                increment));
      }
      if (limitCost != null && projectedCost.compareTo(limitCost) > 0) {
        violations.add(
            violation(
                quotaId,
                scope,
                quota.get("membership_id", UUID.class),
                quota.get("capability_code", String.class),
                "COST",
                money(limitCost),
                money(projectedCost),
                period,
                w,
                "成本上限",
                increment));
      }
    }
    var mode = increment ? "POST_RECORD" : "PREFLIGHT";
    return new AiQuotaEvaluation(violations.isEmpty(), mode, violations, now.toInstant().toString());
  }

  /**
   * 一条越线说明。
   *
   * <p>🔴 五个要素齐备：超了哪一条（quotaId / scope / capabilityCode）、
   * 当前用量（{@code currentUsage}）、阈值（{@code limitValue}）、
   * 周期（{@code period} / {@code periodStart} / {@code periodEnd}）、
   * 什么时候重置（{@code resetAt} = periodEnd）。
   */
  private static AiQuotaViolation violation(
      UUID quotaId,
      QuotaScope scope,
      UUID membershipId,
      String capabilityCode,
      String limitKind,
      String limitValue,
      String currentUsage,
      QuotaPeriod period,
      OffsetDateTime[] w,
      String limitLabel,
      boolean afterRecord) {
    var subject = scope == QuotaScope.USER ? "用户级" : "租户级";
    var target = ANY_CAPABILITY.equals(capabilityCode) ? "全部能力" : capabilityCode;
    var message =
        "超出"
            + subject
            + "配额（"
            + target
            + "）："
            + limitLabel
            + " "
            + limitValue
            + "，"
            + (afterRecord ? "当前累计" : "预检投影后累计")
            + " "
            + currentUsage
            + "，周期 "
            + period.label()
            + "（"
            + w[0].toInstant()
            + " 起，"
            + w[1].toInstant()
            + " 重置）";
    return new AiQuotaViolation(
        quotaId,
        scope == null ? "" : scope.name(),
        scope == null ? "" : scope.label(),
        membershipId,
        capabilityCode,
        limitKind,
        limitValue,
        currentUsage,
        period == null ? "" : period.name(),
        period == null ? "" : period.label(),
        w[0].toInstant().toString(),
        w[1].toInstant().toString(),
        w[1].toInstant().toString(),
        message);
  }

  // ================================================================ 敏感数据策略

  /** 敏感数据策略列表（真筛选）。 */
  public List<AiSensitivePolicyRow> policies(String capability, String modelCode) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (capability != null && !capability.isBlank()) {
      where.append(" and capability_code = ?");
      params.add(capability.trim().toUpperCase(Locale.ROOT));
    }
    if (modelCode != null && !modelCode.isBlank()) {
      where.append(" and model_code = ?");
      params.add(modelCode.trim());
    }
    return sql.fetch(
            "select id,capability_code,model_code,action,enabled,note,created_at,updated_at"
                + " from platform_ai_sensitive_policy"
                + where
                + " order by capability_code, model_code",
            params.toArray())
        .map(AiUsageQuotaService::toPolicyRow);
  }

  /**
   * 查**生效**的那条策略（各 AI 能力在调用前查的就是它）。
   *
   * <p>🔴 **更具体的那条生效**：先找「能力 × 该模型」，找不到再退回「能力 × 全部模型」。
   * 都没有登记 ⇒ 落到默认档（允许），并如实说「库里没登记」。
   */
  public AiSensitivePolicyView effectivePolicy(String capability, String modelCode) {
    var capabilityText = capabilityCode(capability);
    var modelText = optional(modelCode, "模型标识", 64);
    var row =
        sql.fetchOne(
            "select id,capability_code,model_code,action,enabled,note"
                + " from platform_ai_sensitive_policy"
                + " where capability_code = ? and model_code in (?, '')"
                + " order by case when model_code = ? then 0 else 1 end limit 1",
            capabilityText,
            modelText,
            modelText);
    if (row == null) {
      return new AiSensitivePolicyView(
          capabilityText,
          modelText,
          SensitiveAction.ALLOW.name(),
          SensitiveAction.ALLOW.label(),
          false,
          "库里没有登记该能力 / 模型的敏感数据策略 ⇒ 落到默认档「允许」。"
              + "⚠️ 执行点不在本模块：**各 AI 能力在调用前自己查这条策略**并执行"
              + "（本仓没有统一的 AI 出站拦截点）");
    }
    var action = SensitiveAction.parse(row.get("action", String.class));
    var exact = modelText.equals(row.get("model_code", String.class));
    return new AiSensitivePolicyView(
        row.get("capability_code", String.class),
        row.get("model_code", String.class),
        action == null ? "" : action.name(),
        action == null ? "" : action.label(),
        true,
        "命中「"
            + row.get("capability_code", String.class)
            + " × "
            + (exact ? row.get("model_code", String.class) : "全部模型")
            + "」这条登记"
            + (row.get("enabled", Boolean.class) ? "" : "（该条已停用）")
            + "。⚠️ 执行点不在本模块：**各 AI 能力在调用前自己查这条策略**并执行");
  }

  /** 登记 / 修改一条敏感数据策略（原子 upsert）。 */
  public AiSensitivePolicyRow savePolicy(AiSensitivePolicyInput input) {
    var capability = capabilityCode(input.capabilityCode());
    var modelCode = optional(input.modelCode(), "模型标识", 64);
    var action = SensitiveAction.parse(input.action());
    check(action != null, "处理动作只能是 BLOCK / MASK / ALLOW");
    var enabled = input.enabled() == null || input.enabled();
    var note = optional(input.note(), "备注", 500);

    var now = dbNow();
    var id =
        sql.fetchOne(
                "insert into platform_ai_sensitive_policy(id,tenant_id,capability_code,model_code,"
                    + " action,enabled,note,created_at,updated_at)"
                    + " values(?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)"
                    + " on conflict (tenant_id, capability_code, model_code) do update"
                    + " set action = excluded.action, enabled = excluded.enabled,"
                    + " note = excluded.note, updated_at = excluded.updated_at"
                    + " returning id",
                UUID.randomUUID(),
                currentTenant(),
                capability,
                modelCode,
                action.name(),
                enabled,
                note,
                now,
                now)
            .get("id", UUID.class);
    return sql.fetchOne(
            "select id,capability_code,model_code,action,enabled,note,created_at,updated_at"
                + " from platform_ai_sensitive_policy where id = ?",
            id)
        .map(AiUsageQuotaService::toPolicyRow);
  }

  private static AiSensitivePolicyRow toPolicyRow(org.jooq.Record r) {
    var action = SensitiveAction.parse(r.get("action", String.class));
    return new AiSensitivePolicyRow(
        r.get("id", UUID.class),
        r.get("capability_code", String.class),
        r.get("model_code", String.class),
        action == null ? "" : action.name(),
        action == null ? "" : action.label(),
        r.get("enabled", Boolean.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 工具（杂项）

  /** 解析 ISO-8601 时刻；空串 / null 返回 {@code null}。 */
  private static OffsetDateTime parseInstant(String raw, String label) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return OffsetDateTime.parse(raw.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, label + "必须是 ISO-8601 时刻（如 2026-11-02T00:00:00Z）");
    }
  }

  /** 把参数列表拼成 jOOQ 的可变参数数组。 */
  private static Object[] concat(List<Object> params, Object... more) {
    var all = new ArrayList<Object>(params);
    all.addAll(List.of(more));
    return all.toArray();
  }
}
