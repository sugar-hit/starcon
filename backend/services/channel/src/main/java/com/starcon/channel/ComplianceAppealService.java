package com.starcon.channel;

import static com.starcon.channel.ComplianceAppealModels.*;
import static com.starcon.channel.ComplianceAppealRules.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.1.2.2 申诉管理。
 *
 * <p>蓝图：「管理平台申诉：申诉类型、举证材料收集、申诉文本、提交跟踪、结果记录与申诉次数统计。
 * 输入：违规记录；申诉材料；提交动作。输出：申诉单 + 提交记录 + 结果（通过/驳回）。
 * 关系：上游 = 16.1.2.1；下游 = 平台申诉接口。<b>申诉配额有限</b>，需记录已用次数并优先申诉高影响违规。」
 *
 * <p>🔴🔴 本类里有六处<b>结构</b>上的不变量：
 *
 * <ol>
 *   <li><b>值域在 DB 层钉死</b>：申诉类型 / 申诉状态 / 举证材料类型 / 结果的值域与合法转换
 *       由迁移 V310 的 CHECK 与触发器钉死，服务层<b>先</b>按同一份规则拒（422，带人话），
 *       DB <b>再</b>兜底（23514）。</li>
 *   <li><b>申诉配额是真的有限</b>：配额落在 {@code platform_appeal_quota}，
 *       {@code used_count <= quota_limit} 由 CHECK 钉死，提交动作由触发器递增已用次数 ——
 *       服务层先拒（422，带人话），DB 再兜底。</li>
 *   <li><b>影响档位是派生的</b>：由上游违规的处罚措施集合现算（DB 函数
 *       {@code platform_appeal_impact_level}），<b>不落库</b>。</li>
 *   <li><b>举证材料 / 提交记录 / 结果记录三样都是追加式</b>：不可改写、不可删；
 *       驳回后再次申诉<b>不会</b>抹掉上一次的结果。</li>
 *   <li><b>只读上游，不写上游</b>：上游 16.1.2.1 的表与状态机是<b>它自己的</b>写权限边界，
 *       本模块只读 {@code platform_violation} / {@code platform_penalty}。</li>
 *   <li><b>下游未交付一律不编</b>：平台申诉接口在本仓<b>不存在</b> ⇒ 能力声明里
 *       {@code available=false} + {@code ownerModule} + 影响说明，
 *       <b>不造</b>指向不存在入口的放行形状。</li>
 * </ol>
 */
@Service
public class ComplianceAppealService {

  /** 一页最多多少行。 */
  private static final int MAX_PAGE_SIZE = 200;

  // ==================================================================
  // 口径说明（能力声明与各处共用同一句话）
  // ==================================================================

  private static final String TYPE_DOMAIN_NOTE =
      "🔴 申诉类型是**受控值域**，由 DB 层 CHECK 钉死（值域要可审阅，不只靠应用层 enum）。"
          + "蓝图只写「申诉类型」未列举，所以这份值域是**本模块的默认口径、不是平台事实**"
          + "（各平台的申诉分类并不通用）；要按平台细分得等平台适配器接上后按租户扩展。";

  private static final String QUOTA_NOTE =
      "🔴 **申诉配额有限**（蓝图加粗句，不是可选项）：配额落在 platform_appeal_quota"
          + "（店铺 + 周期 + 上限 + 已用次数）。"
          + "**每一次提交消耗一次配额** —— 驳回后补充材料再提一次是**第 2 次提交**、"
          + "消耗**第 2 次配额**；超配额的提交在 **DB 层**就被拒（used_count <= quota_limit 的 CHECK）。"
          + "「已用次数」只能由提交动作递增一次，不能被直接改写。"
          + "没配配额的店铺**提交会被拒**（不许把「没配」当成「无限」）。";

  private static final String PRIORITY_NOTE =
      "🔴 **优先申诉高影响违规**（蓝图加粗句）：影响档位是**派生**的 —— "
          + "由上游违规身上的处罚措施集合现算（停号 > 下架 > 限制销售·扣款 > 警告），"
          + "**不落库**（落库就会与上游的措施表漂移）。"
          + "申诉清单默认就按**影响档位**排序，另有一条「优先申诉队列」"
          + "列出**还没在办申诉、且配额还够**的违规，运营照着这个队列提申诉。";

  private static final String STATE_MACHINE_SEMANTICS =
      "🔴 申诉状态是**显式状态机**：草稿 → 已提交 → 通过 / 驳回；草稿可撤回；"
          + "**驳回可回到草稿**（补充举证材料后再次申诉）。"
          + "**非法转换在服务层（422，带人话）与 DB 层（23514，兜底）都会被拒**。"
          + "通过与撤回是**终态**。";

  private static final String APPEND_ONLY_SEMANTICS =
      "🔴 举证材料 / 提交记录 / 结果记录三样都是**追加式**（append-only）："
          + "不可改写、不可删（DB 触发器钉死）。"
          + "可逆性靠**追加新记录**冲销，不靠改历史 —— "
          + "驳回后再次申诉**不会**抹掉上一次的结果记录。";

  private static final String UPSTREAM_NOTE =
      "上游 = **16.1.2.1 违规与处罚**（**已交付**，同服务同库）。"
          + "本模块**只读** platform_violation / platform_penalty："
          + "校验申诉引用的违规真实存在、**属于同一店铺**、且**没有关闭**，"
          + "并按上游派生的影响档位排优先队列。"
          + "**本模块绝不写上游的任何一行** —— 上游的状态机与列是它自己的写权限边界。";

  private static final String DOWNSTREAM_NOTE =
      "下游 = **平台申诉接口**（**本仓不存在**：各平台的申诉提交通道尚未接入）⇒ "
          + "本模块**不造指向不存在入口的放行形状**："
          + "「提交」只落一条**提交记录**（提交时刻、次数、平台侧受理号），"
          + "**不会**真的把申诉送到平台去。"
          + "这条缺口在能力声明里是**可查的事实**（available=false + ownerModule + 影响说明），"
          + "不是文档里的一句话。";

  // ==================================================================
  // 小工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
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

  private UUID currentActor() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.actor',true),'')");
    return raw == null ? null : UUID.fromString(String.valueOf(raw));
  }

  /** 🔴 <b>同源 DB 时钟</b>：提交 / 裁定 / 撤回时刻一律从这里取。 */
  private OffsetDateTime dbNow() {
    Object raw = sql.fetchValue("select now()");
    return raw instanceof OffsetDateTime value ? value : OffsetDateTime.now(ZoneOffset.UTC);
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

  /** 🔴 非法 UUID 按「筛不出东西」处理（筛选参数是用户敲进来的），但**绝不当成没筛选**。 */
  private static final UUID NO_SUCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

  private static UUID parseFilterId(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    UUID parsed = parseUuid(value);
    return parsed == null ? NO_SUCH_ID : parsed;
  }

  private static long count(Object raw) {
    return raw == null ? 0L : ((Number) raw).longValue();
  }

  private static int intOf(Record row, String column) {
    Number value = row.get(column, Number.class);
    return value == null ? 0 : value.intValue();
  }

  private static int clampSize(int size) {
    return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
  }

  private static int clampPage(int page) {
    return Math.max(page, 0);
  }

  private static OffsetDateTime time(Record row, String column) {
    return row.get(column, OffsetDateTime.class);
  }

  private static Integer intOrNull(Record row, String column) {
    Number value = row.get(column, Number.class);
    return value == null ? null : value.intValue();
  }

  private final DSLContext sql;

  public ComplianceAppealService(DSLContext sql) {
    this.sql = sql;
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明：值域、状态机、口径、以及**哪些下游未交付**。
   *
   * <p>🔴 下游 **平台申诉接口** 当前<b>未交付</b>，如实报出 ownerModule 与影响。
   */
  public ComplianceAppealCapabilities capabilities() {
    List<ComplianceAppealCapabilityGap> gaps =
        List.of(
            new ComplianceAppealCapabilityGap(
                "16.1.2.1",
                "违规与处罚",
                "16.1.2.1 违规与处罚",
                true,
                "上游**已交付**且就在本服务（同一套库）⇒ 本模块**真的能**引用违规记录："
                    + "申诉单必须挂在一条**存在、属于同一店铺、且没有关闭**的违规上，"
                    + "并且**按上游派生的影响档位**排优先申诉队列。"
                    + "本模块**只读**上游的表，不写上游的任何一行。"),
            new ComplianceAppealCapabilityGap(
                "PLATFORM_APPEAL_API",
                "平台申诉接口",
                "各平台适配器（channel 服务 · vendor 包）",
                false,
                "平台申诉接口**本仓不存在**（Amazon / Shopify 等的申诉提交通道尚未接入）⇒ "
                    + "本模块**不造指向不存在入口的放行形状**："
                    + "「提交」只落一条**提交记录**（提交时刻、第几次提交、平台侧受理号），"
                    + "**不会**真的把申诉送到平台。"
                    + "等适配器接上后，由那一侧来消费这些提交记录。"));

    return new ComplianceAppealCapabilities(
        gaps,
        APPEAL_TYPES,
        APPEAL_TYPE_LABELS,
        STATUSES,
        STATUS_LABELS,
        STATUS_TRANSITIONS,
        EVIDENCE_TYPES,
        EVIDENCE_TYPE_LABELS,
        OUTCOMES,
        OUTCOME_LABELS,
        PlatformViolationRules.IMPACT_LEVELS,
        TYPE_DOMAIN_NOTE,
        QUOTA_NOTE,
        PRIORITY_NOTE,
        STATE_MACHINE_SEMANTICS,
        APPEND_ONLY_SEMANTICS,
        UPSTREAM_NOTE,
        DOWNSTREAM_NOTE);
  }

  // ==================================================================
  // 店铺范围
  // ==================================================================

  /** 店铺范围清单（真分页 + 真统计；数据来自本服务自己的店铺目录）。 */
  public ComplianceAppealShopPage shops(
      String platformCode, String keyword, String shopId, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = clampPage(page);
    int safeSize = clampSize(size);

    StringBuilder where = new StringBuilder(" where s.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    String platformValue = text(platformCode);
    if (!platformValue.isEmpty()) {
      where.append(" and p.code = ?");
      params.add(platformValue);
    }
    String keywordValue = text(keyword);
    if (!keywordValue.isEmpty()) {
      where.append(" and (s.code ilike '%' || ? || '%' or s.name ilike '%' || ? || '%')");
      params.add(keywordValue);
      params.add(keywordValue);
    }
    UUID filterShop = parseFilterId(shopId);
    if (filterShop != null) {
      where.append(" and s.id = ?");
      params.add(filterShop);
    }

    long total =
        count(
            sql.fetchValue(
                "select count(*) from shop s join platform p on p.id = s.platform_id" + where,
                params.toArray()));

    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<Record> rows =
        sql.fetch(
            """
            select s.id, s.code, s.name, p.code as platform_code, p.name as platform_name,
                   (select count(*) from platform_appeal a
                     where a.tenant_id = s.tenant_id and a.shop_id = s.id) as appeal_count,
                   (select count(*) from platform_appeal a
                     where a.tenant_id = s.tenant_id and a.shop_id = s.id
                       and a.status in ('DRAFT','SUBMITTED')) as open_appeal_count,
                   (select q.quota_limit from platform_appeal_quota q
                     where q.tenant_id = s.tenant_id and q.shop_id = s.id
                       and q.period_start <= now() and q.period_end >= now()
                     order by q.period_start desc limit 1) as quota_limit,
                   (select q.used_count from platform_appeal_quota q
                     where q.tenant_id = s.tenant_id and q.shop_id = s.id
                       and q.period_start <= now() and q.period_end >= now()
                     order by q.period_start desc limit 1) as quota_used
            from shop s
            join platform p on p.id = s.platform_id
            """
                + where
                + " order by s.code limit ? offset ?",
            pageParams.toArray());

    List<ComplianceAppealShop> items = new ArrayList<>();
    for (Record row : rows) {
      Integer limit = intOrNull(row, "quota_limit");
      Integer used = intOrNull(row, "quota_used");
      items.add(
          new ComplianceAppealShop(
              row.get("id", UUID.class).toString(),
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("platform_code", String.class),
              row.get("platform_name", String.class),
              intOf(row, "appeal_count"),
              intOf(row, "open_appeal_count"),
              limit == null ? 0 : limit,
              used == null ? 0 : used,
              limit == null ? 0 : Math.max(0, limit - used)));
    }
    return new ComplianceAppealShopPage(items, total, safePage, safeSize);
  }

  // ==================================================================
  // 申诉单清单
  // ==================================================================

  /**
   * 申诉单清单（**真分页 + 真筛选**）。
   *
   * @param sort priority（默认，按影响档位）/ created_at / submitted_at
   */
  public ComplianceAppealPage appeals(
      String shopId,
      String status,
      String appealType,
      String violationId,
      String impactLevel,
      String keyword,
      String sort,
      int page,
      int size) {
    UUID tenant = currentTenant();
    int safePage = clampPage(page);
    int safeSize = clampSize(size);

    StringBuilder where = new StringBuilder(" where a.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);

    UUID filterShop = parseFilterId(shopId);
    if (filterShop != null) {
      where.append(" and a.shop_id = ?");
      params.add(filterShop);
    }
    String statusValue = text(status).toUpperCase();
    if (!statusValue.isEmpty()) {
      if (!knownStatus(statusValue)) {
        throw unprocessable("未知的申诉状态：" + status);
      }
      where.append(" and a.status = ?");
      params.add(statusValue);
    }
    String typeValue = text(appealType).toUpperCase();
    if (!typeValue.isEmpty()) {
      if (!knownAppealType(typeValue)) {
        throw unprocessable("未知的申诉类型：" + appealType);
      }
      where.append(" and a.appeal_type = ?");
      params.add(typeValue);
    }
    UUID filterViolation = parseFilterId(violationId);
    if (filterViolation != null) {
      where.append(" and a.violation_id = ?");
      params.add(filterViolation);
    }
    String impactValue = text(impactLevel).toUpperCase();
    if (!impactValue.isEmpty()) {
      where.append(" and platform_appeal_impact_level(a.tenant_id, a.violation_id) = ?");
      params.add(impactValue);
    }
    String keywordValue = text(keyword);
    if (!keywordValue.isEmpty()) {
      where.append(" and (a.appeal_text ilike '%' || ? || '%' or v.title ilike '%' || ? || '%')");
      params.add(keywordValue);
      params.add(keywordValue);
    }

    long total =
        count(
            sql.fetchValue(
                "select count(*) from platform_appeal a"
                    + " join platform_violation v on v.tenant_id = a.tenant_id and v.id = a.violation_id"
                    + where,
                params.toArray()));

    Map<String, Long> statusCounts = new LinkedHashMap<>();
    for (String code : STATUSES) {
      statusCounts.put(code, 0L);
    }
    for (Record row :
        sql.fetch(
            "select a.status, count(*) as c from platform_appeal a"
                + " join platform_violation v on v.tenant_id = a.tenant_id and v.id = a.violation_id"
                + where
                + " group by a.status",
            params.toArray())) {
      statusCounts.put(row.get("status", String.class), count(row.get("c")));
    }

    long submissions =
        count(
            sql.fetchValue(
                "select count(*) from platform_appeal_submission su"
                    + " join platform_appeal a on a.tenant_id = su.tenant_id and a.id = su.appeal_id"
                    + where,
                params.toArray()));

    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<Record> rows =
        sql.fetch(
            APPEAL_SELECT
                + where
                + " order by "
                + orderBy(text(sort).toLowerCase())
                + " limit ? offset ?",
            pageParams.toArray());

    List<ComplianceAppeal> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(toAppeal(row));
    }

    return new ComplianceAppealPage(
        items,
        total,
        safePage,
        safeSize,
        statusCounts.getOrDefault("DRAFT", 0L),
        statusCounts.getOrDefault("SUBMITTED", 0L),
        statusCounts.getOrDefault("APPROVED", 0L),
        statusCounts.getOrDefault("REJECTED", 0L),
        statusCounts.getOrDefault("WITHDRAWN", 0L),
        submissions,
        "🔴 默认按**影响档位**排序（派生自上游的处罚措施集合，**不落库**）；"
            + "各状态计数与提交次数覆盖**整个筛选结果集**（不是当前页）。"
            + "提交次数 = **申诉次数**（驳回后再次提交会再计一次）。");
  }

  /** 🔴 排序表达式：priority = 影响档位优先（蓝图加粗句「优先申诉高影响违规」）。 */
  private static String orderBy(String sort) {
    return switch (sort) {
      case "created_at" -> "a.created_at desc, a.id desc";
      case "submitted_at" -> "a.submitted_at desc nulls last, a.id desc";
      default -> IMPACT_RANK + " asc, a.created_at desc, a.id desc";
    };
  }

  /** 🔴 影响档位的排序权重（与上游 16.1.2.1 的档位口径一致）。 */
  private static final String IMPACT_RANK =
      "case platform_appeal_impact_level(a.tenant_id, a.violation_id)"
          + " when 'CRITICAL' then 0 when 'HIGH' then 1"
          + " when 'MEDIUM' then 2 when 'LOW' then 3 else 4 end";

  private static final String APPEAL_SELECT =
      """
      select a.*, s.code as shop_code, s.name as shop_name, p.code as platform_code,
             v.title as violation_title, v.violation_type as violation_type,
             platform_appeal_impact_level(a.tenant_id, a.violation_id) as impact_level,
             (select count(*) from platform_appeal_evidence e
               where e.tenant_id = a.tenant_id and e.appeal_id = a.id) as evidence_count,
             (select count(*) from platform_appeal_submission su
               where su.tenant_id = a.tenant_id and su.appeal_id = a.id) as submission_count,
             (select count(*) from platform_appeal_decision d
               where d.tenant_id = a.tenant_id and d.appeal_id = a.id) as decision_count,
             (select d.outcome from platform_appeal_decision d
               where d.tenant_id = a.tenant_id and d.appeal_id = a.id
               order by d.decided_at desc, d.id desc limit 1) as last_outcome,
             (select d.reason from platform_appeal_decision d
               where d.tenant_id = a.tenant_id and d.appeal_id = a.id
               order by d.decided_at desc, d.id desc limit 1) as last_reason,
             (select d.decided_at from platform_appeal_decision d
               where d.tenant_id = a.tenant_id and d.appeal_id = a.id
               order by d.decided_at desc, d.id desc limit 1) as last_decided_at
      from platform_appeal a
      join shop s on s.tenant_id = a.tenant_id and s.id = a.shop_id
      join platform p on p.id = s.platform_id
      join platform_violation v on v.tenant_id = a.tenant_id and v.id = a.violation_id
      """;

  // ==================================================================
  // 申诉单详情
  // ==================================================================

  /** 申诉单详情。跨租户与不存在返回逐字相同的 404。 */
  public ComplianceAppealDetail appeal(String appealId) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(appealId);
    Record row = appealRow(tenant, id);
    if (row == null) {
      throw notFound();
    }
    UUID shopId = row.get("shop_id", UUID.class);
    return new ComplianceAppealDetail(
        toAppeal(row),
        evidenceOf(tenant, id),
        submissionsOf(tenant, id),
        decisionsOf(tenant, id),
        quotaOf(tenant, shopId, dbNow()),
        "🔴 举证材料 / 提交记录 / 结果记录三样都是**追加式**（不可改写、不可删）；"
            + "驳回后再次申诉**不会**抹掉上一次的结果记录。");
  }

  private Record appealRow(UUID tenant, UUID id) {
    return sql.fetchOne(APPEAL_SELECT + " where a.tenant_id = ? and a.id = ?", tenant, id);
  }

  private ComplianceAppeal toAppeal(Record row) {
    String status = row.get("status", String.class);
    String impact = row.get("impact_level", String.class);
    String outcome = row.get("last_outcome", String.class);
    String violationType = row.get("violation_type", String.class);
    return new ComplianceAppeal(
        row.get("id", UUID.class).toString(),
        row.get("shop_id", UUID.class).toString(),
        row.get("shop_code", String.class),
        row.get("shop_name", String.class),
        row.get("platform_code", String.class),
        row.get("violation_id", UUID.class).toString(),
        row.get("violation_title", String.class),
        violationType,
        PlatformViolationRules.violationTypeLabel(violationType),
        impact,
        impactLabel(impact),
        row.get("appeal_type", String.class),
        appealTypeLabel(row.get("appeal_type", String.class)),
        status,
        statusLabel(status),
        nextStatuses(status),
        row.get("appeal_text", String.class),
        time(row, "submitted_at"),
        time(row, "withdrawn_at"),
        intOf(row, "submission_count"),
        intOf(row, "evidence_count"),
        intOf(row, "decision_count"),
        outcome,
        outcome == null ? "" : outcomeLabel(outcome),
        row.get("last_reason", String.class),
        time(row, "last_decided_at"),
        time(row, "created_at"));
  }

  // ==================================================================
  // 建单
  // ==================================================================

  /**
   * 新建申诉单（**草稿态**）。
   *
   * <p>🔴 店铺**由违规派生**，不接受客户端自报 —— 跨店铺申诉会让平台一看就对不上。
   * 🔴 已关闭（CLOSED）的违规**不能再申诉**（申诉的前提是这条违规还在处理中）。
   */
  public ComplianceAppeal create(ComplianceAppealCreateInput input) {
    UUID tenant = currentTenant();
    UUID violationId = requireUuid(input == null ? null : input.violationId());
    Record violation = violationRow(tenant, violationId);
    if (violation == null) {
      throw notFound();
    }
    String violationStatus = violation.get("status", String.class);
    if ("CLOSED".equals(violationStatus)) {
      throw unprocessable("这条违规已经关闭，不能再对它申诉");
    }
    String type = text(input.appealType()).toUpperCase();
    if (!knownAppealType(type)) {
      throw unprocessable(
          "未知的申诉类型："
              + input.appealType()
              + "。可选："
              + String.join(" / ", APPEAL_TYPES));
    }
    String appealText = text(input.appealText());
    if (appealText.isEmpty()) {
      throw unprocessable("申诉文本不能为空");
    }
    UUID shopId = violation.get("shop_id", UUID.class);
    // 🔴 同一条违规**同时只能有一个在办的申诉**（草稿 / 已提交）——
    //   先在服务层查一次，给人话；DB 的部分唯一索引再兜一层。
    Record openAppealRow =
        sql.fetchOne(
            """
            select id from platform_appeal
             where tenant_id = ? and violation_id = ? and status in ('DRAFT','SUBMITTED')
            """,
            tenant,
            violationId);
    if (openAppealRow != null) {
      throw unprocessable(
          "这条违规已经有在办的申诉（"
              + openAppealRow.get(0, UUID.class)
              + "），先把那一条处理完");
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        """
        insert into platform_appeal
          (id, tenant_id, shop_id, violation_id, appeal_type, status, appeal_text, created_at, updated_at)
        values (?,?,?,?,?,'DRAFT',?,now(),now())
        """,
        id,
        tenant,
        shopId,
        violationId,
        type,
        appealText);
    Record stored = appealRow(tenant, id);
    if (stored == null) {
      throw unprocessable("申诉单写入后读不回来");
    }
    return toAppeal(stored);
  }

  /** 上游违规行（**只读**）。 */
  private Record violationRow(UUID tenant, UUID violationId) {
    return sql.fetchOne(
        """
        select v.*, s.code as shop_code, s.name as shop_name, p.code as platform_code
        from platform_violation v
        join shop s on s.tenant_id = v.tenant_id and s.id = v.shop_id
        join platform p on p.id = s.platform_id
        where v.tenant_id = ? and v.id = ?
        """,
        tenant,
        violationId);
  }

  /** 改申诉文本（**只有草稿态能改**）。 */
  public ComplianceAppeal updateText(String appealId, ComplianceAppealTextInput input) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(appealId);
    Record row = appealRow(tenant, id);
    if (row == null) {
      throw notFound();
    }
    String status = row.get("status", String.class);
    if (!editableText(status)) {
      throw unprocessable("申诉当前状态是「" + statusLabel(status) + "」，申诉文本只有在草稿态能改");
    }
    String appealText = text(input == null ? null : input.appealText());
    if (appealText.isEmpty()) {
      throw unprocessable("申诉文本不能为空");
    }
    sql.execute(
        "update platform_appeal set appeal_text = ?, updated_at = now() where tenant_id = ? and id = ?",
        appealText,
        tenant,
        id);
    return toAppeal(appealRow(tenant, id));
  }

  // ==================================================================
  // 举证材料（append-only）
  // ==================================================================

  /** 追加一条举证材料（**不可改写、不可删**；**只有草稿态能收集**）。 */
  public ComplianceAppealEvidence addEvidence(
      String appealId, ComplianceAppealEvidenceInput input) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(appealId);
    Record row = appealRow(tenant, id);
    if (row == null) {
      throw notFound();
    }
    String status = row.get("status", String.class);
    if (!"DRAFT".equals(status)) {
      throw unprocessable(
          "申诉当前状态是「" + statusLabel(status) + "」，举证材料只能在草稿态收集");
    }
    String type = text(input == null ? null : input.evidenceType()).toUpperCase();
    if (!knownEvidenceType(type)) {
      throw unprocessable(
          "未知的举证材料类型："
              + (input == null ? null : input.evidenceType())
              + "。可选："
              + String.join(" / ", EVIDENCE_TYPES));
    }
    String ref = text(input.evidenceRef());
    String summary = text(input.summary());
    if (ref.isEmpty() && summary.isEmpty()) {
      throw unprocessable("举证材料至少要给一个引用（evidenceRef）或一句说明（summary）");
    }
    UUID shopId = row.get("shop_id", UUID.class);
    UUID evidenceId = UUID.randomUUID();
    OffsetDateTime submittedAt = dbNow();
    sql.execute(
        """
        insert into platform_appeal_evidence
          (id, tenant_id, shop_id, appeal_id, evidence_type, evidence_ref, summary,
           submitted_by, submitted_at, created_at)
        values (?,?,?,?,?,?,?,?,?::timestamptz,now())
        """,
        evidenceId,
        tenant,
        shopId,
        id,
        type,
        ref,
        summary,
        currentActor(),
        submittedAt);
    return evidenceOf(tenant, id).stream()
        .filter(e -> e.evidenceId().equals(evidenceId.toString()))
        .findFirst()
        .orElseThrow(() -> unprocessable("举证材料写入后读不回来"));
  }

  private List<ComplianceAppealEvidence> evidenceOf(UUID tenant, UUID appealId) {
    List<ComplianceAppealEvidence> result = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select * from platform_appeal_evidence
             where tenant_id = ? and appeal_id = ? order by submitted_at desc, id desc
            """,
            tenant,
            appealId)) {
      result.add(
          new ComplianceAppealEvidence(
              row.get("id", UUID.class).toString(),
              row.get("appeal_id", UUID.class).toString(),
              row.get("evidence_type", String.class),
              row.get("evidence_ref", String.class),
              row.get("summary", String.class),
              time(row, "submitted_at")));
    }
    return result;
  }

  // ==================================================================
  // 提交（消耗配额）
  // ==================================================================

  /**
   * 提交申诉 —— <b>消耗一次申诉配额</b>，并写一条**追加式**的提交记录。
   *
   * <p>🔴 配额检查在服务层先做（422，带人话：还剩几次、什么时候过期），
   * DB 触发器再兜底（23514）。
   * 🔴 提交时刻取**同源 DB 时钟**，不接受客户端自报 —— 「什么时候交的」是事实。
   */
  public ComplianceAppealSubmissionResult submit(String appealId, ComplianceAppealSubmitInput input) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(appealId);
    Record row = appealRow(tenant, id);
    if (row == null) {
      throw notFound();
    }
    String status = row.get("status", String.class);
    if (!canTransition(status, "SUBMITTED")) {
      throw unprocessable(
          "申诉当前状态是「"
              + statusLabel(status)
              + "」，不能提交。合法转换："
              + (nextStatuses(status).isEmpty()
                  ? "（终态，不可再改）"
                  : nextStatuses(status).stream().map(ComplianceAppealRules::statusLabel).toList()));
    }
    UUID shopId = row.get("shop_id", UUID.class);
    OffsetDateTime now = dbNow();
    ComplianceAppealQuota quota = quotaOf(tenant, shopId, now);
    if (!quota.configured()) {
      throw unprocessable(
          "店铺「"
              + text(row.get("shop_name", String.class))
              + "」本周期还没有配置申诉配额，无法提交申诉。"
              + "请先配置申诉配额（申诉配额有限，没配不等于无限）。");
    }
    if (quota.remaining() <= 0) {
      throw unprocessable(
          "申诉配额已用尽：店铺「"
              + text(row.get("shop_name", String.class))
              + "」本周期上限 "
              + quota.quotaLimit()
              + " 次，已用 "
              + quota.usedCount()
              + " 次。");
    }

    int attemptNo =
        intOf(
            sql.fetchOne(
                """
                select count(*) as c from platform_appeal_submission
                 where tenant_id = ? and appeal_id = ?
                """,
                tenant,
                id),
            "c")
            + 1;

    UUID submissionId = UUID.randomUUID();
    sql.execute(
        """
        insert into platform_appeal_submission
          (id, tenant_id, shop_id, appeal_id, attempt_no, submitted_at, submitted_by,
           platform_reference, note, created_at)
        values (?,?,?,?,?,?::timestamptz,?,?,?,now())
        """,
        submissionId,
        tenant,
        shopId,
        id,
        attemptNo,
        now,
        currentActor(),
        text(input == null ? null : input.platformReference()),
        text(input == null ? null : input.note()));

    sql.execute(
        """
        update platform_appeal
           set status = 'SUBMITTED', submitted_at = ?::timestamptz, submitted_by = ?, updated_at = now()
         where tenant_id = ? and id = ?
        """,
        now,
        currentActor(),
        tenant,
        id);

    ComplianceAppealSubmission submission =
        submissionsOf(tenant, id).stream()
            .filter(s -> s.submissionId().equals(submissionId.toString()))
            .findFirst()
            .orElseThrow(() -> unprocessable("提交记录写入后读不回来"));
    return new ComplianceAppealSubmissionResult(
        toAppeal(appealRow(tenant, id)),
        submission,
        attemptNo,
        quotaOf(tenant, shopId, now),
        "🔴 本次提交消耗**一次申诉配额**（已用次数由 DB 触发器递增，直接改写会被拒）。"
            + "🔴 平台申诉接口**未交付** ⇒ 本次只落**提交记录**，**没有**真的送到平台。");
  }

  private List<ComplianceAppealSubmission> submissionsOf(UUID tenant, UUID appealId) {
    List<ComplianceAppealSubmission> result = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select * from platform_appeal_submission
             where tenant_id = ? and appeal_id = ? order by attempt_no desc, id desc
            """,
            tenant,
            appealId)) {
      result.add(
          new ComplianceAppealSubmission(
              row.get("id", UUID.class).toString(),
              row.get("appeal_id", UUID.class).toString(),
              intOf(row, "attempt_no"),
              time(row, "submitted_at"),
              row.get("platform_reference", String.class),
              row.get("note", String.class)));
    }
    return result;
  }

  // ==================================================================
  // 结果（通过 / 驳回）
  // ==================================================================

  /**
   * 记录平台裁定（**通过 / 驳回**），并写一条**追加式**的结果记录。
   *
   * <p>🔴 驳回后可以「回到草稿」补充材料再提一次 —— 那会写**第 2 条**结果记录，
   * 第 1 条**不会被抹掉**。
   */
  public ComplianceAppealDecisionResult decide(
      String appealId, ComplianceAppealDecisionInput input) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(appealId);
    Record row = appealRow(tenant, id);
    if (row == null) {
      throw notFound();
    }
    String status = row.get("status", String.class);
    String outcome = text(input == null ? null : input.outcome()).toUpperCase();
    if (!knownOutcome(outcome)) {
      throw unprocessable(
          "未知的申诉结果："
              + (input == null ? null : input.outcome())
              + "。可选：APPROVED（通过）/ REJECTED（驳回）");
    }
    if (!canTransition(status, outcome)) {
      throw unprocessable(
          "申诉当前状态是「"
              + statusLabel(status)
              + "」，不能裁定成「"
              + outcomeLabel(outcome)
              + "」。合法转换："
              + (nextStatuses(status).isEmpty()
                  ? "（终态，不可再改）"
                  : nextStatuses(status).stream().map(ComplianceAppealRules::statusLabel).toList()));
    }
    UUID shopId = row.get("shop_id", UUID.class);
    OffsetDateTime decidedAt =
        input == null || input.decidedAt() == null ? dbNow() : input.decidedAt();
    UUID decisionId = UUID.randomUUID();
    sql.execute(
        """
        insert into platform_appeal_decision
          (id, tenant_id, shop_id, appeal_id, outcome, reason, decided_at, decided_by,
           platform_reference, created_at)
        values (?,?,?,?,?,?,?::timestamptz,?,?,now())
        """,
        decisionId,
        tenant,
        shopId,
        id,
        outcome,
        text(input.reason()),
        decidedAt,
        currentActor(),
        text(input.platformReference()));
    sql.execute(
        "update platform_appeal set status = ?, updated_at = now() where tenant_id = ? and id = ?",
        outcome,
        tenant,
        id);
    ComplianceAppealDecision decision =
        decisionsOf(tenant, id).stream()
            .filter(d -> d.decisionId().equals(decisionId.toString()))
            .findFirst()
            .orElseThrow(() -> unprocessable("结果记录写入后读不回来"));
    return new ComplianceAppealDecisionResult(
        toAppeal(appealRow(tenant, id)),
        decision,
        "🔴 结果记录是**追加式**的：驳回后再次申诉**不会**抹掉这一条。");
  }

  private List<ComplianceAppealDecision> decisionsOf(UUID tenant, UUID appealId) {
    List<ComplianceAppealDecision> result = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select * from platform_appeal_decision
             where tenant_id = ? and appeal_id = ? order by decided_at desc, id desc
            """,
            tenant,
            appealId)) {
      String outcome = row.get("outcome", String.class);
      result.add(
          new ComplianceAppealDecision(
              row.get("id", UUID.class).toString(),
              row.get("appeal_id", UUID.class).toString(),
              outcome,
              outcomeLabel(outcome),
              row.get("reason", String.class),
              time(row, "decided_at"),
              row.get("platform_reference", String.class)));
    }
    return result;
  }

  // ==================================================================
  // 撤回 / 回到草稿
  // ==================================================================

  /** 撤回申诉（草稿态 ⇒ 已撤回，**终态**，不消耗配额）。 */
  public ComplianceAppeal withdraw(String appealId, ComplianceAppealWithdrawInput input) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(appealId);
    Record row = appealRow(tenant, id);
    if (row == null) {
      throw notFound();
    }
    String status = row.get("status", String.class);
    if (!canTransition(status, "WITHDRAWN")) {
      throw unprocessable(
          "申诉当前状态是「"
              + statusLabel(status)
              + "」，不能撤回。合法转换："
              + (nextStatuses(status).isEmpty()
                  ? "（终态，不可再改）"
                  : nextStatuses(status).stream().map(ComplianceAppealRules::statusLabel).toList()));
    }
    OffsetDateTime now = dbNow();
    sql.execute(
        """
        update platform_appeal
           set status = 'WITHDRAWN', withdrawn_at = ?::timestamptz, updated_at = now()
         where tenant_id = ? and id = ?
        """,
        now,
        tenant,
        id);
    return toAppeal(appealRow(tenant, id));
  }

  /**
   * 驳回后回到草稿（补充举证材料后再次申诉）。
   *
   * <p>🔴 回到草稿**不消耗配额**；真正再提交时才会消耗下一次配额。
   */
  public ComplianceAppeal reopen(String appealId) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(appealId);
    Record row = appealRow(tenant, id);
    if (row == null) {
      throw notFound();
    }
    String status = row.get("status", String.class);
    if (!canTransition(status, "DRAFT")) {
      throw unprocessable(
          "申诉当前状态是「"
              + statusLabel(status)
              + "」，只有「"
              + statusLabel("REJECTED")
              + "」才能回到草稿补充材料后再次申诉。");
    }
    sql.execute(
        """
        update platform_appeal
           set status = 'DRAFT', submitted_at = null, submitted_by = null, updated_at = now()
         where tenant_id = ? and id = ?
        """,
        tenant,
        id);
    return toAppeal(appealRow(tenant, id));
  }

  // ==================================================================
  // 配额
  // ==================================================================

  /**
   * 一个店铺在某个时刻的配额（**没有配就是没配**，不编一个「默认无限」）。
   *
   * @param at 判定「当前周期」的基准时刻（由调用方给，全链路同一个 DB 时钟）
   */
  public ComplianceAppealQuota quotaOf(UUID tenant, UUID shopId, OffsetDateTime at) {
    Record shop =
        sql.fetchOne(
            "select id, code, name from shop where tenant_id = ? and id = ?", tenant, shopId);
    Record quota =
        sql.fetchOne(
            """
            select id, period_start, period_end, quota_limit, used_count
              from platform_appeal_quota
             where tenant_id = ? and shop_id = ?
               and period_start <= ?::timestamptz and period_end >= ?::timestamptz
             order by period_start desc limit 1
            """,
            tenant,
            shopId,
            at,
            at);
    return quotaOf(tenant, shop, quota);
  }

  /** 把一行配额（或 null = 没配）变成传输模型。 */
  private ComplianceAppealQuota quotaOf(UUID tenant, Record shop, Record quota) {
    String shopCode = shop == null ? "" : shop.get("code", String.class);
    String shopName = shop == null ? "" : shop.get("name", String.class);
    if (quota == null) {
      return new ComplianceAppealQuota(
          shop == null ? "" : shop.get("id", UUID.class).toString(),
          shopCode,
          shopName,
          null,
          null,
          0,
          0,
          0,
          false,
          "🔴 该店铺本周期**没有配置申诉配额** ⇒ 提交会被拒（没配 ≠ 无限）。");
    }
    int limit = intOf(quota, "quota_limit");
    int used = intOf(quota, "used_count");
    return new ComplianceAppealQuota(
        shop.get("id", UUID.class).toString(),
        shopCode,
        shopName,
        time(quota, "period_start"),
        time(quota, "period_end"),
        limit,
        used,
        Math.max(0, limit - used),
        true,
        "🔴 已用次数 = **已提交次数**（只能由提交动作递增一次，不可直接改写）。");
  }

  /** 配额清单（**真分页**；**没有配额的店铺也会出现**，configured=false）。 */
  public ComplianceAppealQuotaPage quotas(String shopId, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = clampPage(page);
    int safeSize = clampSize(size);
    OffsetDateTime now = dbNow();

    StringBuilder where = new StringBuilder(" where s.tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    UUID filterShop = parseFilterId(shopId);
    if (filterShop != null) {
      where.append(" and s.id = ?");
      params.add(filterShop);
    }

    long total =
        count(
            sql.fetchValue(
                "select count(*) from shop s" + where, params.toArray()));

    List<Object> all = new ArrayList<>();
    all.add(now);
    all.add(now);
    all.addAll(params);
    all.add(safeSize);
    all.add(safePage * safeSize);
    List<Record> rows =
        sql.fetch(
            """
            select s.id as shop_id, s.code as shop_code, s.name as shop_name,
                   q.id as quota_id, q.period_start, q.period_end, q.quota_limit, q.used_count
            from shop s
            left join lateral (
              select q2.id, q2.period_start, q2.period_end, q2.quota_limit, q2.used_count
                from platform_appeal_quota q2
               where q2.tenant_id = s.tenant_id and q2.shop_id = s.id
                 and q2.period_start <= ?::timestamptz and q2.period_end >= ?::timestamptz
               order by q2.period_start desc limit 1
            ) q on true
            """
                + where
                + " order by s.code limit ? offset ?",
            all.toArray());

    List<ComplianceAppealQuota> items = new ArrayList<>();
    for (Record row : rows) {
      Integer limit = intOrNull(row, "quota_limit");
      Integer used = intOrNull(row, "used_count");
      boolean configured = limit != null;
      items.add(
          new ComplianceAppealQuota(
              row.get("shop_id", UUID.class).toString(),
              row.get("shop_code", String.class),
              row.get("shop_name", String.class),
              time(row, "period_start"),
              time(row, "period_end"),
              limit == null ? 0 : limit,
              used == null ? 0 : used,
              limit == null ? 0 : Math.max(0, limit - used),
              configured,
              configured
                  ? "🔴 已用次数 = **已提交次数**（只能由提交动作递增一次，不可直接改写）。"
                  : "🔴 该店铺本周期**没有配置申诉配额** ⇒ 提交会被拒（没配 ≠ 无限）。"));
    }
    return new ComplianceAppealQuotaPage(
        items,
        total,
        safePage,
        safeSize,
        "🔴 **没配配额的店铺也会出现在清单里**（configured=false）—— "
            + "「看不见」比「配了但用完了」更容易被忽略。");
  }

  /**
   * 配置申诉配额（新建或改上限）。
   *
   * <p>🔴 周期不填 ⇒ 默认**当前自然季度（UTC）**，并在响应里回报真实边界。
   * 🔴 已用次数**不可改写** —— 只能由提交动作递增。
   */
  public ComplianceAppealQuotaUpdate setQuota(ComplianceAppealQuotaInput input) {
    UUID tenant = currentTenant();
    UUID shopId = requireUuid(input == null ? null : input.shopId());
    Record shop =
        sql.fetchOne("select id, code, name from shop where tenant_id = ? and id = ?", tenant, shopId);
    if (shop == null) {
      throw notFound();
    }
    Integer limit = input == null ? null : input.quotaLimit();
    if (limit == null) {
      throw unprocessable("请给出申诉配额上限（quotaLimit）");
    }
    if (limit < 0) {
      throw unprocessable("申诉配额上限不能为负");
    }
    OffsetDateTime now = dbNow();
    OffsetDateTime periodStart =
        input.periodStart() == null ? quarterStart(now) : input.periodStart();
    OffsetDateTime periodEnd = input.periodEnd() == null ? quarterEnd(now) : input.periodEnd();
    if (periodEnd.isBefore(periodStart)) {
      throw unprocessable("配额周期的结束时刻不能早于开始时刻");
    }

    Record existing =
        sql.fetchOne(
            """
            select id, used_count from platform_appeal_quota
             where tenant_id = ? and shop_id = ? and period_start = ?::timestamptz
            """,
            tenant,
            shopId,
            periodStart);
    boolean created = existing == null;
    if (created) {
      sql.execute(
          """
          insert into platform_appeal_quota
            (id, tenant_id, shop_id, period_start, period_end, quota_limit, used_count, created_at, updated_at)
          values (?,?,?,?::timestamptz,?::timestamptz,?,0,now(),now())
          """,
          UUID.randomUUID(),
          tenant,
          shopId,
          periodStart,
          periodEnd,
          limit);
    } else {
      // 🔴 已用次数不可改写：只改上限（DB 触发器 guard_platform_appeal_quota_used_count 会兜底）。
      sql.execute(
          """
          update platform_appeal_quota
             set quota_limit = ?, period_end = ?::timestamptz, updated_at = now()
           where tenant_id = ? and id = ?
          """,
          limit,
          periodEnd,
          tenant,
          existing.get("id", UUID.class));
    }
    ComplianceAppealQuota quota =
        quotaOf(
            tenant,
            shop,
            sql.fetchOne(
                """
                select id, period_start, period_end, quota_limit, used_count
                  from platform_appeal_quota
                 where tenant_id = ? and shop_id = ? and period_start = ?::timestamptz
                """,
                tenant,
                shopId,
                periodStart));
    return new ComplianceAppealQuotaUpdate(
        quota,
        created,
        created
            ? "🔴 新建了本周期（"
                + periodStart
                + " ~ "
                + periodEnd
                + "）的申诉配额，上限 "
                + limit
                + " 次。"
            : "🔴 改了本周期（"
                + periodStart
                + " ~ "
                + periodEnd
                + "）的申诉配额上限为 "
                + limit
                + " 次（**已用次数保持不变**）。");
  }

  /** 当前自然季度的起点（UTC）。 */
  private static OffsetDateTime quarterStart(OffsetDateTime now) {
    int firstMonth = ((now.getMonthValue() - 1) / 3) * 3 + 1;
    return LocalDate.of(now.getYear(), firstMonth, 1)
        .atStartOfDay(ZoneOffset.UTC)
        .toOffsetDateTime();
  }

  /** 当前自然季度的终点（UTC，闭区间）。 */
  private static OffsetDateTime quarterEnd(OffsetDateTime now) {
    return quarterStart(now).plusMonths(3).minusNanos(1);
  }

  // ==================================================================
  // 优先申诉队列
  // ==================================================================

  /**
   * 优先申诉队列 —— 蓝图加粗句「优先申诉高影响违规」的落地。
   *
   * <p>🔴 队列里是**还没在办申诉**的违规，按**影响档位**从高到低排；
   * 每行都带「现在能不能提」与「为什么不能提」+ 本店铺的剩余配额。
   */
  public ComplianceAppealPriorityPage priorityQueue(
      String shopId, String appealableOnly, int page, int size) {
    UUID tenant = currentTenant();
    int safePage = clampPage(page);
    int safeSize = clampSize(size);
    OffsetDateTime now = dbNow();

    StringBuilder where =
        new StringBuilder(
            " where v.tenant_id = ? and v.status <> 'CLOSED'"
                + " and not exists (select 1 from platform_appeal a"
                + "   where a.tenant_id = v.tenant_id and a.violation_id = v.id"
                + "     and a.status in ('DRAFT','SUBMITTED'))");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    UUID filterShop = parseFilterId(shopId);
    if (filterShop != null) {
      where.append(" and v.shop_id = ?");
      params.add(filterShop);
    }
    if ("true".equalsIgnoreCase(text(appealableOnly))) {
      where.append(
          " and q.quota_limit is not null and (q.quota_limit - q.used_count) > 0");
    }

    String from =
        """
        select v.id as violation_id, v.shop_id, s.code as shop_code, s.name as shop_name,
               p.code as platform_code, v.title, v.violation_type, v.status as violation_status,
               v.occurred_at, v.deadline,
               platform_appeal_impact_level(v.tenant_id, v.id) as impact_level,
               case platform_appeal_impact_level(v.tenant_id, v.id)
                 when 'CRITICAL' then 0 when 'HIGH' then 1
                 when 'MEDIUM' then 2 when 'LOW' then 3 else 4 end as impact_rank,
               q.quota_limit, q.used_count
        from platform_violation v
        join shop s on s.tenant_id = v.tenant_id and s.id = v.shop_id
        join platform p on p.id = s.platform_id
        left join lateral (
          select q2.quota_limit, q2.used_count
            from platform_appeal_quota q2
           where q2.tenant_id = v.tenant_id and q2.shop_id = v.shop_id
             and q2.period_start <= ?::timestamptz and q2.period_end >= ?::timestamptz
           order by q2.period_start desc limit 1
        ) q on true
        """;

    List<Object> countParams = new ArrayList<>();
    countParams.add(now);
    countParams.add(now);
    countParams.addAll(params);
    long total =
        count(
            sql.fetchValue(
                "select count(*) from platform_violation v"
                    + " join shop s on s.tenant_id = v.tenant_id and s.id = v.shop_id"
                    + " left join lateral ("
                    + "   select q2.quota_limit, q2.used_count from platform_appeal_quota q2"
                    + "    where q2.tenant_id = v.tenant_id and q2.shop_id = v.shop_id"
                    + "      and q2.period_start <= ?::timestamptz and q2.period_end >= ?::timestamptz"
                    + "    order by q2.period_start desc limit 1) q on true"
                    + where,
                countParams.toArray()));

    List<Object> pageParams = new ArrayList<>(countParams);
    pageParams.add(safeSize);
    pageParams.add(safePage * safeSize);
    List<Record> rows =
        sql.fetch(
            from + where + " order by impact_rank asc, v.occurred_at desc, v.id desc limit ? offset ?",
            pageParams.toArray());

    List<ComplianceAppealPriorityCandidate> items = new ArrayList<>();
    for (Record row : rows) {
      String impact = row.get("impact_level", String.class);
      String violationType = row.get("violation_type", String.class);
      Integer limit = intOrNull(row, "quota_limit");
      Integer used = intOrNull(row, "used_count");
      boolean configured = limit != null;
      int remaining = configured ? Math.max(0, limit - used) : -1;
      boolean appealable = configured && remaining > 0;
      String reason =
          appealable
              ? "可以申诉：影响档位「"
                  + impactLabel(impact)
                  + "」，本店铺本周期还剩 "
                  + remaining
                  + " 次申诉配额。"
              : configured
                  ? "配额已用尽：本店铺本周期申诉配额还剩 0 次。"
                  : "该店铺本周期**没有配置申诉配额** ⇒ 提交会被拒（没配 ≠ 无限）。";
      items.add(
          new ComplianceAppealPriorityCandidate(
              row.get("violation_id", UUID.class).toString(),
              row.get("shop_id", UUID.class).toString(),
              row.get("shop_code", String.class),
              row.get("shop_name", String.class),
              row.get("platform_code", String.class),
              row.get("title", String.class),
              violationType,
              PlatformViolationRules.violationTypeLabel(violationType),
              impact,
              impactLabel(impact),
              time(row, "occurred_at"),
              time(row, "deadline"),
              row.get("violation_status", String.class),
              appealable,
              reason,
              null,
              configured,
              remaining));
    }
    return new ComplianceAppealPriorityPage(
        items,
        total,
        safePage,
        safeSize,
        "🔴 队列按**影响档位**从高到低排（派生自上游的处罚措施集合，**不落库**）。"
            + "队列里**不含**已有在办申诉（DRAFT / SUBMITTED）的违规，"
            + "也**不含**已关闭（CLOSED）的违规 —— 这两类提不了，列出来只会浪费运营的时间。");
  }

  // ==================================================================
  // 申诉次数统计
  // ==================================================================

  /** 申诉次数统计（**申诉次数** = 提交总次数）。 */
  public ComplianceAppealStats stats(String shopId, String from, String to) {
    UUID tenant = currentTenant();
    OffsetDateTime fromTime = parseTime(from, "起始时间");
    OffsetDateTime toTime = parseTime(to, "截止时间");

    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    UUID filterShop = parseFilterId(shopId);
    if (filterShop != null) {
      where.append(" and shop_id = ?");
      params.add(filterShop);
    }
    if (fromTime != null) {
      where.append(" and created_at >= ?::timestamptz");
      params.add(fromTime);
    }
    if (toTime != null) {
      where.append(" and created_at <= ?::timestamptz");
      params.add(toTime);
    }

    long total = count(sql.fetchValue("select count(*) from platform_appeal" + where, params.toArray()));

    Map<String, Long> byStatus = new LinkedHashMap<>();
    for (String code : STATUSES) {
      byStatus.put(code, 0L);
    }
    for (Record row :
        sql.fetch(
            "select status, count(*) as c from platform_appeal" + where + " group by status",
            params.toArray())) {
      byStatus.put(row.get("status", String.class), count(row.get("c")));
    }

    Map<String, Long> byType = new LinkedHashMap<>();
    for (String code : APPEAL_TYPES) {
      byType.put(code, 0L);
    }
    for (Record row :
        sql.fetch(
            "select appeal_type, count(*) as c from platform_appeal" + where + " group by appeal_type",
            params.toArray())) {
      byType.put(row.get("appeal_type", String.class), count(row.get("c")));
    }

    List<Object> submissionParams = new ArrayList<>();
    submissionParams.add(tenant);
    if (filterShop != null) {
      submissionParams.add(filterShop);
    }
    StringBuilder submissionWhere = new StringBuilder(" where tenant_id = ?");
    if (filterShop != null) {
      submissionWhere.append(" and shop_id = ?");
    }
    long submissions =
        count(
            sql.fetchValue(
                "select count(*) from platform_appeal_submission" + submissionWhere,
                submissionParams.toArray()));

    Map<String, Long> outcomes = new LinkedHashMap<>();
    for (String code : OUTCOMES) {
      outcomes.put(code, 0L);
    }
    for (Record row :
        sql.fetch(
            "select outcome, count(*) as c from platform_appeal_decision"
                + submissionWhere
                + " group by outcome",
            submissionParams.toArray())) {
      outcomes.put(row.get("outcome", String.class), count(row.get("c")));
    }

    ComplianceAppealQuota quota =
        filterShop == null ? null : quotaOf(tenant, filterShop, dbNow());

    return new ComplianceAppealStats(
        filterShop == null ? null : filterShop.toString(),
        total,
        byStatus,
        byType,
        submissions,
        outcomes.getOrDefault("APPROVED", 0L),
        outcomes.getOrDefault("REJECTED", 0L),
        quota,
        "🔴 **申诉次数** = 提交总次数（驳回后再次提交会再计一次、也再消耗一次配额）。"
            + "按店铺统计时配额只对**该店铺**有意义；未按店铺统计时配额一栏为空（不是一个能跨店铺相加的数）。");
  }

  // ==================================================================
  // 内部
  // ==================================================================

  private static UUID requireUuid(String raw) {
    UUID parsed = parseUuid(raw);
    if (parsed == null) {
      // 🔴 路径参数非法 ⇒ 404（跨租户与不存在逐字相同），不泄露「这个 id 格式对不对」。
      throw notFound();
    }
    return parsed;
  }

  /** 解析时间入参；空 ⇒ null，非法 ⇒ 422。 */
  private static OffsetDateTime parseTime(String raw, String field) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value);
    } catch (RuntimeException e) {
      throw unprocessable(field + " 不是合法的 ISO 时间");
    }
  }
}
