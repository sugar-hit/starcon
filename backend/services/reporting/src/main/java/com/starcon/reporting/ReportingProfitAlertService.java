package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.4.3.1 利润预警的**读模型**（蓝图「输出」句：预警清单 + 通知 + 处置入口）。
 *
 * <p>⚠️ **本模块是读模型 + 规则配置**，不是业务事实的写入方：
 * 预警是「13.4.1.1 的利润投影 + 租户自己的阈值规则」的**确定性计算**，
 * 同一批投影 + 同一批规则 ⇒ 同一份预警清单。**不重算费率、不重算分摊、不拉实时汇率**
 * （重算就会出现第二个真相 —— 上游规则一改，这里的预警就变成假的）。
 *
 * <p>⚠️ **「判不出来」与「没异常」是两件事**：成本项缺失、历史期数不足、订单毛利算不出来
 * ⇒ 这些情况一律进 {@code undecided} 清单（**带一句能直接展示的原因**），
 * **绝不写成「正常」**。把「判不了」写成「没异常」比误报更危险：误报会被处理，
 * 「没异常」会被忽略。
 *
 * <p>⚠️ **「算不出来」与「等于 0」是两件事**：占比、倍数、基线在算不出来时一律 {@code null}
 * （页面上是「—」），**不用 0 冒充**。
 *
 * <p>⚠️ **阈值不写死在代码里**：落在 {@code reporting_profit_alert_rule}，
 * 服务层只播种一份**默认**规则（可在接口上改）。阈值的**单位**由
 * (预警类型 × 阈值口径) 派生，**不落库**（落一份副本就会与口径漂移）。
 *
 * <p>⚠️ **多币种绝不相加**：每一行都带自己的币种，聚合键里始终带着币种。
 */
@Service
public class ReportingProfitAlertService {

  /** 百分数 / 百分点的标度（与既有指标口径一致）。 */
  private static final int RATE_SCALE = 4;

  /** 中间除法保留的位数（定标前先多留几位，避免除不尽时丢精度）。 */
  private static final int DIVIDE_SCALE = 8;

  /** 预警清单最多计算多少条（超出时如实报截断，不静默丢行）。 */
  static final int MAX_ALERTS = 2000;

  /** 「判不出来」清单最多返回多少条（超出时如实报截断）。 */
  static final int MAX_UNDECIDED = 200;

  /** 一次最多拉多少个基线窗口（**上限**：租户把 min_history_periods 改成天文数字时也不拖垮查询）。 */
  static final int MAX_HISTORY_WINDOWS = 12;

  /** 一次最多返回多少行（真分页的上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 费用类成本项（**费用占比**的口径：平台费 + 物流费 + 广告费 + 头程 + 其它）。 */
  private static final Set<String> FEE_COMPONENTS =
      Set.of("PLATFORM_FEE", "LOGISTICS_FEE", "AD_FEE", "HEAD_FREIGHT", "OTHER_COST");

  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingProfitAlertService(DSLContext sql, TenantTransactions tenants) {
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // 预警清单
  // ==========================================================================

  /**
   * 预警清单（**真分页 + 真计数**）。
   *
   * @param alertTypeFilter 只看某一类预警（空 ⇒ 全部）
   * @param severityFilter 只看某个严重度（空 ⇒ 全部）
   * @param scopeTypeFilter 只看某个归属维度（{@code SKU} / {@code SHOP}）
   * @param scopeKeyFilter 只看某个归属取值（SKU 编码 / 店铺编码）
   */
  public ReportingProfitAlertModels.ProfitAlertPage alerts(
      UUID tenantId,
      LocalDate from,
      LocalDate to,
      String alertTypeFilter,
      String severityFilter,
      String scopeTypeFilter,
      String scopeKeyFilter,
      int page,
      int size) {
    return tenants.run(
        tenantId,
        () -> {
          var rules = ensureRules(tenantId);
          var computed = compute(tenantId, from, to, rules);

          var filtered = new ArrayList<ReportingProfitAlertModels.ProfitAlert>();
          for (var alert : computed.alerts()) {
            if (alertTypeFilter != null
                && !alertTypeFilter.isBlank()
                && !alert.alertType().equals(alertTypeFilter)) continue;
            if (severityFilter != null
                && !severityFilter.isBlank()
                && !alert.severity().equals(severityFilter)) continue;
            if (scopeTypeFilter != null
                && !scopeTypeFilter.isBlank()
                && !alert.scopeType().equals(scopeTypeFilter)) continue;
            if (scopeKeyFilter != null
                && !scopeKeyFilter.isBlank()
                && !alert.scopeKey().equals(scopeKeyFilter)) continue;
            filtered.add(alert);
          }

          var undecided = new ArrayList<ReportingProfitAlertModels.ProfitAlertUndecided>();
          for (var item : computed.undecided()) {
            if (alertTypeFilter != null
                && !alertTypeFilter.isBlank()
                && !item.alertType().equals(alertTypeFilter)) continue;
            if (scopeTypeFilter != null
                && !scopeTypeFilter.isBlank()
                && !item.scopeType().equals(scopeTypeFilter)) continue;
            if (scopeKeyFilter != null
                && !scopeKeyFilter.isBlank()
                && !item.scopeKey().equals(scopeKeyFilter)) continue;
            undecided.add(item);
          }

          var pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
          var pageIndex = Math.max(page, 0);
          var fromIndex = Math.min(pageIndex * pageSize, filtered.size());
          var toIndex = Math.min(fromIndex + pageSize, filtered.size());
          var items = new ArrayList<>(filtered.subList(fromIndex, toIndex));

          var undecidedTruncated = undecided.size() > MAX_UNDECIDED;
          if (undecidedTruncated) {
            undecided = new ArrayList<>(undecided.subList(0, MAX_UNDECIDED));
          }

          var summary = summary(filtered, undecided.size(), computed.truncated(), undecidedTruncated);
          var warnings = warnings(computed, summary, undecidedTruncated);

          return new ReportingProfitAlertModels.ProfitAlertPage(
              from,
              to,
              items,
              undecided,
              summary,
              pageIndex,
              pageSize,
              filtered.size(),
              dataUpdatedAt(tenantId),
              "最近一次利润事实投影落库时刻（reporting_profit_fact 的 updated_at 最大值）。"
                  + "预警是**按那一刻的投影 + 当前的阈值规则**算出来的。",
              warnings,
              caliberNote());
        });
  }

  // ==========================================================================
  // 阈值规则
  // ==========================================================================

  /** 阈值规则（**先播种默认值**再读）。 */
  public List<ReportingProfitAlertModels.ProfitAlertRule> rules(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var rules = ensureRules(tenantId);
          var out = new ArrayList<ReportingProfitAlertModels.ProfitAlertRule>();
          for (var type : ReportingProfitAlertModels.AlertType.values()) {
            var rule = rules.get(type.name());
            if (rule == null) continue;
            out.add(
                new ReportingProfitAlertModels.ProfitAlertRule(
                    rule.alertType(),
                    type.label(),
                    rule.thresholdMode(),
                    rule.thresholdValue(),
                    type.thresholdUnit(rule.thresholdMode()),
                    rule.minHistoryPeriods(),
                    rule.severity(),
                    severityLabel(rule.severity()),
                    rule.enabled(),
                    type.description(),
                    ruleUpdatedAt(tenantId, type.name())));
          }
          return out;
        });
  }

  /**
   * 改某一类预警的阈值（**只改写进来的那几个字段**）。
   *
   * <p>⚠️ **本模块只改自己的规则表**，不写任何上游口径（费率规则在 catalog、
   * 归集规则与分摊结果在 finance —— 跨服务写入被 AGENTS.md 禁止）。
   */
  public ReportingProfitAlertModels.ProfitAlertRule updateRule(
      UUID tenantId, String alertType, ReportingProfitAlertModels.ProfitAlertRuleInput input) {
    var type = ReportingProfitAlertModels.AlertType.parse(alertType);
    if (type == null) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "alertType 只能是 "
              + java.util.Arrays.stream(ReportingProfitAlertModels.AlertType.values())
                  .map(Enum::name)
                  .toList()
              + "，收到："
              + alertType);
    }
    return tenants.run(
        tenantId,
        () -> {
          ensureRules(tenantId);
          var current = readRule(tenantId, type.name());
          var mode =
              input.thresholdMode() == null || input.thresholdMode().isBlank()
                  ? current.thresholdMode()
                  : resolveThresholdMode(type, input.thresholdMode());
          var value =
              input.thresholdValue() == null ? current.thresholdValue() : input.thresholdValue();
          var minHistory =
              input.minHistoryPeriods() == null ? current.minHistoryPeriods() : input.minHistoryPeriods();
          if (minHistory < 1) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "最少历史期数至少为 1");
          }
          if (minHistory > MAX_HISTORY_WINDOWS) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "最少历史期数最多 " + MAX_HISTORY_WINDOWS + " 期（再长就拉不动基线了）");
          }
          var severity =
              input.severity() == null || input.severity().isBlank()
                  ? current.severity()
                  : resolveSeverity(input.severity());
          var enabled = input.enabled() == null ? current.enabled() : input.enabled();

          // 🔴 **取值范围在服务层先挡一道**：DB 的 CHECK 是最后一道防线，
          //   但让用户拿到一句「为什么不行」比拿到一句约束名有用得多。
          validateThreshold(type, mode, value);

          sql.execute(
              """
              insert into reporting_profit_alert_rule
                (id,tenant_id,alert_type,threshold_mode,threshold_value,
                 min_history_periods,severity,enabled,updated_at)
              values (?,?,?,?,?,?,?,?,now())
              on conflict (tenant_id,alert_type)
              do update set threshold_mode = excluded.threshold_mode,
                            threshold_value = excluded.threshold_value,
                            min_history_periods = excluded.min_history_periods,
                            severity = excluded.severity,
                            enabled = excluded.enabled,
                            updated_at = now()
              """,
              Ids.next(),
              tenantId,
              type.name(),
              mode,
              value,
              minHistory,
              severity,
              enabled);
          return new ReportingProfitAlertModels.ProfitAlertRule(
              type.name(),
              type.label(),
              mode,
              value,
              type.thresholdUnit(mode),
              minHistory,
              severity,
              severityLabel(severity),
              enabled,
              type.description(),
              ruleUpdatedAt(tenantId, type.name()));
        });
  }

  // ==========================================================================
  // 通知
  // ==========================================================================

  /**
   * 把当前窗口命中的预警登记成通知投递记录（**幂等**）。
   *
   * <p>⚠️ **不硬编码任何 SMTP / webhook 地址或 token**（AGENTS.md：secret 一律不入仓库）。
   * 本模块只做**可观测的通知投递记录**（落库 + 查询端点）；真发通道将来在
   * {@code channel} 上加值，而不是改写历史记录。
   */
  public ReportingProfitAlertModels.ProfitAlertNotificationSummary registerNotifications(
      UUID tenantId, LocalDate from, LocalDate to) {
    return tenants.run(
        tenantId,
        () -> {
          var rules = ensureRules(tenantId);
          var computed = compute(tenantId, from, to, rules);
          var created = 0;
          var skipped = 0;
          for (var alert : computed.alerts()) {
            var inserted =
                sql.execute(
                    """
                    insert into reporting_profit_alert_notification
                      (id,tenant_id,alert_fingerprint,alert_type,severity,scope_type,scope_key,
                       currency,channel,status,title,detail,observed_value,observed_unit,
                       threshold_value,window_start,window_end,created_at)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())
                    on conflict (tenant_id,alert_fingerprint) do nothing
                    """,
                    Ids.next(),
                    tenantId,
                    alert.fingerprint(),
                    alert.alertType(),
                    alert.severity(),
                    alert.scopeType(),
                    alert.scopeKey(),
                    alert.currency(),
                    "CONSOLE",
                    "PENDING",
                    alert.alertTypeLabel() + " · " + alert.scopeLabel(),
                    alert.reason(),
                    alert.observedValue(),
                    alert.observedUnit(),
                    alert.thresholdValue(),
                    from,
                    to);
            if (inserted > 0) {
              created++;
            } else {
              skipped++;
            }
          }
          return new ReportingProfitAlertModels.ProfitAlertNotificationSummary(
              computed.alerts().size(),
              created,
              skipped,
              from,
              to,
              "命中 "
                  + computed.alerts().size()
                  + " 条预警：新登记 "
                  + created
                  + " 条，已登记过（同一窗口同一预警不重复通知）"
                  + skipped
                  + " 条。"
                  + "⚠️ 通知通道目前只有站内（CONSOLE）——**没有真发邮件 / webhook**："
                  + "那需要凭据，AGENTS.md 规定 secret 一律不入仓库。");
        });
  }

  /** 通知台账（**真分页 + 真计数**）。 */
  public ReportingProfitAlertModels.ProfitAlertNotificationPage notifications(
      UUID tenantId, String status, String alertType, int page, int size) {
    return tenants.run(
        tenantId,
        () -> {
          var filters = new StringBuilder(" where tenant_id = ?");
          var params = new ArrayList<Object>();
          params.add(tenantId);
          if (status != null && !status.isBlank()) {
            filters.append(" and status = ?");
            params.add(status);
          }
          if (alertType != null && !alertType.isBlank()) {
            filters.append(" and alert_type = ?");
            params.add(alertType);
          }
          var total =
              sql.fetchOne(
                  "select count(*) from reporting_profit_alert_notification" + filters,
                  params.toArray());
          // ⚠️ **待处理数与状态筛选无关**（它是「还有多少条没处理」的待办数），
          //   所以单独一条 SQL，不复用上面的 filters（那样会拼出两个 where）。
          var pending =
              sql.fetchOne(
                  "select count(*) from reporting_profit_alert_notification"
                      + " where tenant_id = ? and status = 'PENDING'",
                  tenantId);

          var pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
          var pageIndex = Math.max(page, 0);
          var rows =
              sql.fetch(
                  "select id, alert_type, severity, scope_type, scope_key, currency, channel, status,"
                      + " title, detail, observed_value, observed_unit, threshold_value,"
                      + " window_start, window_end, created_at, acknowledged_at, acknowledged_by,"
                      + " alert_fingerprint"
                      + " from reporting_profit_alert_notification"
                      + filters
                      + " order by created_at desc, id desc limit ? offset ?",
                  append(params, pageSize, pageIndex * pageSize));

          var items = new ArrayList<ReportingProfitAlertModels.ProfitAlertNotification>();
          for (var row : rows) {
            items.add(notificationOf(row));
          }
          return new ReportingProfitAlertModels.ProfitAlertNotificationPage(
              items,
              pageIndex,
              pageSize,
              total == null ? 0L : total.get(0, Long.class).longValue(),
              pending == null ? 0L : pending.get(0, Long.class).longValue(),
              "通知台账只记**投递记录**（谁在什么时候被通知过、确认过没有）。"
                  + "⚠️ 通道目前只有站内（CONSOLE）：**没有真发邮件 / webhook**。");
        });
  }

  /**
   * 改一条通知的状态（**已知悉 / 误报忽略**）。
   *
   * <p>⚠️ **终态不可复活**：已确认 / 已忽略的记录不能改回 PENDING ——
   * 否则「处理过的预警」会重新出现在待办里，那比不处理更糟。
   */
  public ReportingProfitAlertModels.ProfitAlertNotification updateNotification(
      UUID tenantId, UUID userId, UUID id, ReportingProfitAlertModels.ProfitAlertNotificationInput input) {
    var target =
        input == null || input.status() == null || input.status().isBlank()
            ? null
            : input.status().trim().toUpperCase();
    if (target == null || !List.of("ACKNOWLEDGED", "DISMISSED").contains(target)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "status 只能是 ACKNOWLEDGED（已知悉）或 DISMISSED（误报，忽略），收到："
              + (input == null ? "null" : input.status()));
    }
    return tenants.run(
        tenantId,
        () -> {
          var row =
              sql.fetchOne(
                  "select id, alert_type, severity, scope_type, scope_key, currency, channel,"
                      + " status, title, detail, observed_value, observed_unit, threshold_value,"
                      + " window_start, window_end, created_at, acknowledged_at, acknowledged_by,"
                      + " alert_fingerprint"
                      + " from reporting_profit_alert_notification where tenant_id = ? and id = ?",
                  tenantId,
                  id);
          if (row == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "通知不存在或不属于当前租户");
          }
          var current = row.get(7, String.class);
          if (!"PENDING".equals(current)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "这条通知已经是「" + statusLabel(current) + "」状态，终态不可复活。");
          }
          sql.execute(
              "update reporting_profit_alert_notification"
                  + " set status = ?, acknowledged_at = now(), acknowledged_by = ?"
                  + " where tenant_id = ? and id = ?",
              target,
              userId,
              tenantId,
              id);
          // 🔴 **写完再读一遍**：确认时刻与确认人**必须从库里读回来**，
          //   不能用「我打算写进去的值」糊弄（那是第二个真相）。
          var updated =
              sql.fetchOne(
                  "select id, alert_type, severity, scope_type, scope_key, currency, channel,"
                      + " status, title, detail, observed_value, observed_unit, threshold_value,"
                      + " window_start, window_end, created_at, acknowledged_at, acknowledged_by,"
                      + " alert_fingerprint"
                      + " from reporting_profit_alert_notification where tenant_id = ? and id = ?",
                  tenantId,
                  id);
          if (updated == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "通知不存在或不属于当前租户");
          }
          return notificationOf(updated);
        });
  }

  /**
   * 把一行通知记录组装成契约对象。
   *
   * <p>⚠️ **列序是固定的**（id, alert_type, severity, scope_type, scope_key, currency, channel,
   * status, title, detail, observed_value, observed_unit, threshold_value, window_start,
   * window_end, created_at, acknowledged_at, acknowledged_by, alert_fingerprint）——
   * 通知台账与改状态两处**必须用同一个列序**，否则两处会各读各的。
   */
  private static ReportingProfitAlertModels.ProfitAlertNotification notificationOf(
      org.jooq.Record row) {
    var type = ReportingProfitAlertModels.AlertType.parse(row.get(1, String.class));
    var severity = row.get(2, String.class);
    var status = row.get(7, String.class);
    var acknowledgedBy = row.get(17, UUID.class);
    return new ReportingProfitAlertModels.ProfitAlertNotification(
        row.get(0, UUID.class).toString(),
        row.get(1, String.class),
        type == null ? row.get(1, String.class) : type.label(),
        severity,
        severityLabel(severity),
        row.get(3, String.class),
        row.get(4, String.class),
        scopeLabel(row.get(3, String.class), row.get(4, String.class)),
        row.get(5, String.class),
        row.get(6, String.class),
        status,
        statusLabel(status),
        row.get(8, String.class),
        row.get(9, String.class),
        row.get(10, BigDecimal.class),
        row.get(11, String.class),
        row.get(12, BigDecimal.class),
        row.get(13, LocalDate.class),
        row.get(14, LocalDate.class),
        row.get(15, OffsetDateTime.class),
        row.get(16, OffsetDateTime.class),
        acknowledgedBy == null ? null : acknowledgedBy.toString(),
        row.get(18, String.class));
  }

  // ==========================================================================
  // 能力声明 / 数据更新时间
  // ==========================================================================

  /** 能力声明：哪些上游真接通、哪些确实算不出来、归谁。 */
  public ReportingProfitAlertModels.ProfitAlertCapabilities capabilities() {
    var types = new ArrayList<String>();
    for (var type : ReportingProfitAlertModels.AlertType.values()) {
      types.add(type.label() + "（" + type.description() + "）");
    }
    return new ReportingProfitAlertModels.ProfitAlertCapabilities(
        true,
        types,
        List.of(
            "13.4.1.1 多模式利润报表（reporting 本服务的 `reporting_profit_fact` 投影："
                + "收入 / 商品成本 / 平台费 / 物流费 / 广告费 / 头程 / 其它成本）",
            "12.3.1.2 结算明细（finance ⇒ 平台费 / 物流费 / 广告费，**唯一写入所有者**）",
            "12.4 成本归集 + 12.4.2.2 分摊结果（finance ⇒ 商品成本 / 头程 / 其它成本）",
            "2.3.2.1 经营模式（channel ⇒ 店铺档案，供利润报表分区）"),
        List.of(
            "**SKU 级毛利常常是窄口径的**：上游 12.4.2.2 的分摊多在**店铺 / 订单**维度，"
                + "平台费 / 物流费 / 广告费只到店铺级 ⇒ SKU 维度通常只剩「收入 − 商品成本」。"
                + "因此「SKU 毛利转负」是**按现有分摊口径转负**，不是全口径转负。",
            "**订单级成本常常算不出来**：平台费 / 物流费 / 广告费来自 12.3.1.2 结算明细，"
                + "**只到店铺级** ⇒ 订单级毛利通常只有「收入 − 商品成本」；"
                + "**算不出毛利的订单不计入亏损订单**（分母只算可比订单），"
                + "所以亏损订单占比是**下界**，页面同时给出「可比订单数 / 订单总数」。",
            "**广告归因收入不在本模块**：「广告亏损超标」用的是**店铺级广告费 ÷ 店铺毛利**的倍数，"
                + "**不拉广告归因成交**（归因口径归 14 广告）⇒ 要按 ROAS 判亏损请去广告模块。",
            "**缺成本项就是算不出来，不是 0**：沿用 13.4.1.1 的纪律 —— "
                + "该成本项在租户的该维度上存在、而这一格没有 ⇒ 那一格的毛利**算不出来**（页面上是「—」），"
                + "**不是「那一项没花钱」**。",
            "**历史不够就判不了**：基线是**前 N 期同口径窗口**的真实历史投影（不是写死的常量）；"
                + "可比期数不足 ⇒ 该条预警**判不了**，进「判不出来」清单，**不报「无异常」**。",
            "**上游的推事件**：上游尚未产出利润事实型 outbox 事件 ⇒ 事实来自 "
                + "13.4.1.1 的 POST /reporting/profit-report/refresh 拉取路径；"
                + "本模块**只读**该投影，**不另建第二张存同一笔钱的表**。"),
        "口径归属：预警是「利润投影 + 租户自己的阈值规则」的确定性计算。"
            + "**本模块不重算费率、不重算分摊、不拉实时汇率、不改写任何业务事实。**"
            + "阈值与严重度落在 `reporting_profit_alert_rule`（租户可改）；"
            + "通知落成 `reporting_profit_alert_notification` 投递记录（**不硬编码 SMTP / webhook**）。");
  }

  /** 数据更新时间（卷 13 卷首「数据延迟要显示」）。 */
  public OffsetDateTime dataUpdatedAt(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var row =
              sql.fetchOne("select max(updated_at) from reporting_profit_fact where tenant_id = ?", tenantId);
          return row == null ? null : row.get(0, OffsetDateTime.class);
        });
  }

  // ==========================================================================
  // 计算
  // ==========================================================================

  /** 一次计算的产物。 */
  private record Computed(
      List<ReportingProfitAlertModels.ProfitAlert> alerts,
      List<ReportingProfitAlertModels.ProfitAlertUndecided> undecided,
      boolean truncated,
      List<String> notes) {}

  /**
   * 算一次预警。
   *
   * <p>⚠️ **口径纪律全部在这里**：一次拉数、一次分桶、逐类型判定，
   * 任何一项算不出来就进 {@code undecided}，**绝不静默变成 0 或「无异常」**。
   */
  private Computed compute(
      UUID tenantId, LocalDate from, LocalDate to, Map<String, Rule> rules) {
    var windowLength = ChronoUnit.DAYS.between(from, to) + 1;
    var maxHistory =
        rules.values().stream().mapToInt(rule -> rule.minHistoryPeriods()).max().orElse(1);
    if (maxHistory > MAX_HISTORY_WINDOWS) maxHistory = MAX_HISTORY_WINDOWS;
    var spanFrom = from.minusDays((long) maxHistory * windowLength);
    var currentWin = maxHistory;

    // ---- 取数：店铺维度 / SKU 维度 / 订单级，三条独立口径 ----
    var shopSums = scopeSums(tenantId, spanFrom, to, spanFrom, windowLength, Scope.SHOP);
    var skuSums = scopeSums(tenantId, spanFrom, to, spanFrom, windowLength, Scope.SKU);
    var orderStats = orderStats(tenantId, spanFrom, to, spanFrom, windowLength);

    var shopApplicable = applicableComponents(shopSums);
    var skuApplicable = applicableComponents(skuSums);
    var shopFeeApplicable = intersect(shopApplicable, FEE_COMPONENTS);

    var shopMetrics = metricsOf(shopSums, shopApplicable, shopFeeApplicable);
    var skuMetrics = metricsOf(skuSums, skuApplicable, intersect(skuApplicable, FEE_COMPONENTS));

    var alerts = new ArrayList<ReportingProfitAlertModels.ProfitAlert>();
    var undecided = new ArrayList<ReportingProfitAlertModels.ProfitAlertUndecided>();
    var notes = new ArrayList<String>();

    // ---- 逐类型判定 ----
    for (var type : ReportingProfitAlertModels.AlertType.values()) {
      var rule = rules.get(type.name());
      if (rule == null) {
        undecided.add(
            new ReportingProfitAlertModels.ProfitAlertUndecided(
                type.name(),
                type.label(),
                type.scopeType(),
                "",
                "（全部）",
                "",
                "这一类预警还没有配置阈值规则，判不了。"));
        continue;
      }
      if (!rule.enabled()) continue;

      if (type == ReportingProfitAlertModels.AlertType.SKU_NEGATIVE_GROSS_PROFIT) {
        evaluateSkuNegative(
            rule, skuMetrics, skuApplicable, currentWin, from, to, alerts, undecided);
      } else if (type == ReportingProfitAlertModels.AlertType.SHOP_MARGIN_DROP) {
        evaluateShopMarginDrop(
            rule, shopMetrics, shopApplicable, currentWin, from, to, alerts, undecided);
      } else if (type == ReportingProfitAlertModels.AlertType.FEE_RATIO_ANOMALY) {
        evaluateFeeRatio(
            rule, shopMetrics, shopFeeApplicable, currentWin, from, to, alerts, undecided);
      } else if (type == ReportingProfitAlertModels.AlertType.AD_LOSS_EXCEEDED) {
        if (!shopApplicable.contains("AD_FEE")) {
          notes.add(
              "该租户在**店铺维度**上没有任何广告费事实 ⇒ 「广告亏损超标」这条预警**不适用**"
                  + "（是「压根没有广告费」，不是「广告没超标」）。");
          continue;
        }
        evaluateAdLoss(
            rule, shopMetrics, shopApplicable, currentWin, from, to, alerts, undecided);
      } else if (type == ReportingProfitAlertModels.AlertType.LOSS_ORDER_RATIO_SPIKE) {
        evaluateLossOrderRatio(
            rule, shopMetrics, orderStats, currentWin, from, to, alerts, undecided);
      }
    }

    alerts.sort(
        Comparator.comparing((ReportingProfitAlertModels.ProfitAlert a) -> severityRank(a.severity()))
            .reversed()
            .thenComparing(ReportingProfitAlertModels.ProfitAlert::alertType)
            .thenComparing(ReportingProfitAlertModels.ProfitAlert::scopeKey)
            .thenComparing(ReportingProfitAlertModels.ProfitAlert::currency));

    var truncated = alerts.size() > MAX_ALERTS;
    if (truncated) {
      alerts = new ArrayList<>(alerts.subList(0, MAX_ALERTS));
    }
    undecided.sort(
        Comparator.comparing(ReportingProfitAlertModels.ProfitAlertUndecided::alertType)
            .thenComparing(ReportingProfitAlertModels.ProfitAlertUndecided::scopeKey)
            .thenComparing(ReportingProfitAlertModels.ProfitAlertUndecided::currency));
    return new Computed(alerts, undecided, truncated, notes);
  }

  // ==========================================================================
  // 逐类型判定
  // ==========================================================================

  /** SKU 毛利转负（**严格转负**：毛利率 < 阈值，阈值默认 0**）。 */
  private void evaluateSkuNegative(
      Rule rule,
      Map<ScopeKey, Map<Integer, ScopeMetrics>> metrics,
      Set<String> applicable,
      int currentWin,
      LocalDate from,
      LocalDate to,
      List<ReportingProfitAlertModels.ProfitAlert> alerts,
      List<ReportingProfitAlertModels.ProfitAlertUndecided> undecided) {
    for (var entry : metrics.entrySet()) {
      var key = entry.getKey();
      var current = entry.getValue().get(currentWin);
      if (current == null || current.revenue() == null) continue; // 这个 SKU 在窗口里没有收入 ⇒ 不是它的战场
      if (current.margin() == null) {
        undecided.add(
            undecided(
                rule.alertType(),
                key,
                "这一格 SKU 的毛利**算不出来**（缺 "
                    + String.join("、", current.missingCosts())
                    + " 的事实）⇒ 判不了毛利转负，**不是「没转负」**。"));
        continue;
      }
      var previous = entry.getValue().get(currentWin - 1);
      var previousMargin = previous == null ? null : previous.margin();
      if (current.margin().compareTo(rule.thresholdValue()) >= 0) continue;

      var reason =
          "SKU "
              + key.scopeKey()
              + " 毛利率 "
              + current.margin().setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
              + "% 已跌破阈值 "
              + rule.thresholdValue().setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
              + "%（毛利 "
              + current.grossProfit().setScale(2, RoundingMode.HALF_UP).toPlainString()
              + " "
              + key.currency()
              + "）";
      if (previousMargin != null) {
        reason +=
            "；上一窗口毛利率 "
                + previousMargin.setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                + "%";
        if (previousMargin.signum() > 0) {
          reason += "（**由盈转亏**）";
        }
      }
      alerts.add(
          alert(
              rule,
              key,
              "GROSS_MARGIN",
              current.margin(),
              "PERCENT",
              previousMargin,
              previousMargin == null ? null : current.margin().subtract(previousMargin),
              "PREVIOUS_WINDOW",
              current.revenue(),
              current.grossProfit(),
              from,
              to,
              reason,
              "SKU 维度的收入取**订单行金额**口径、缺订单级运费 / 税 / 折扣；"
                  + "成本只含该 SKU 上**有事实**的成本项（缺项 = 算不出来，不是 0）。"));
    }
  }

  /** 店铺利润率骤降（FIXED = 跌破固定线；BASELINE = 相对基线下降的百分点**）。 */
  private void evaluateShopMarginDrop(
      Rule rule,
      Map<ScopeKey, Map<Integer, ScopeMetrics>> metrics,
      Set<String> applicable,
      int currentWin,
      LocalDate from,
      LocalDate to,
      List<ReportingProfitAlertModels.ProfitAlert> alerts,
      List<ReportingProfitAlertModels.ProfitAlertUndecided> undecided) {
    for (var entry : metrics.entrySet()) {
      var key = entry.getKey();
      var current = entry.getValue().get(currentWin);
      if (current == null || current.revenue() == null) continue;
      if (current.margin() == null) {
        undecided.add(
            undecided(
                rule.alertType(),
                key,
                "这一格店铺的毛利率**算不出来**（缺 "
                    + String.join("、", current.missingCosts())
                    + " 的事实）⇒ 判不了利润率是否骤降，**不是「没骤降」**。"));
        continue;
      }
      if ("FIXED".equals(rule.thresholdMode())) {
        var previous = entry.getValue().get(currentWin - 1);
        var previousMargin = previous == null ? null : previous.margin();
        if (current.margin().compareTo(rule.thresholdValue()) >= 0) continue;
        alerts.add(
            alert(
                rule,
                key,
                "GROSS_MARGIN",
                current.margin(),
                "PERCENT",
                previousMargin,
                previousMargin == null ? null : current.margin().subtract(previousMargin),
                "PREVIOUS_WINDOW",
                current.revenue(),
                current.grossProfit(),
                from,
                to,
                "店铺 "
                    + key.scopeKey()
                    + " 毛利率 "
                    + current.margin().setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                    + "% 跌破阈值 "
                    + rule.thresholdValue().setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                    + "%。",
                "店铺维度的收入取**订单总额**口径（含订单级运费 / 税 / 折扣）。"));
        continue;
      }

      var baseline = baselineMean(entry.getValue(), currentWin, rule.minHistoryPeriods(), Metric.MARGIN);
      if (baseline == null) {
        undecided.add(
            undecided(
                rule.alertType(),
                key,
                "历史基线**算不出来**（前 "
                    + rule.minHistoryPeriods()
                    + " 期同口径窗口里可比的不足 "
                    + rule.minHistoryPeriods()
                    + " 期）⇒ 判不了骤降，**不是「没骤降」**。"));
        continue;
      }
      var delta = baseline.subtract(current.margin());
      if (delta.compareTo(rule.thresholdValue()) < 0) continue;
      alerts.add(
          alert(
              rule,
              key,
              "GROSS_MARGIN",
              current.margin(),
              "PERCENT",
              baseline,
              delta,
              "BASELINE_MEAN",
              current.revenue(),
              current.grossProfit(),
              from,
              to,
              "店铺 "
                  + key.scopeKey()
                  + " 毛利率 "
                  + current.margin().setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                  + "%，相对历史基线 "
                  + baseline.setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                  + "% 下降 "
                  + delta.setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                  + " 个百分点，达到阈值 "
                  + rule.thresholdValue().setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                  + " 个百分点。",
              "基线 = 前 "
                  + rule.minHistoryPeriods()
                  + " 期同长度、同口径窗口的毛利率均值（**真实历史投影数据**，不是写死的常量）。"));
    }
  }

  /** 费用占比异常（FIXED = 费用率超过固定线；BASELINE = 相对基线上升的百分点**）。 */
  private void evaluateFeeRatio(
      Rule rule,
      Map<ScopeKey, Map<Integer, ScopeMetrics>> metrics,
      Set<String> feeApplicable,
      int currentWin,
      LocalDate from,
      LocalDate to,
      List<ReportingProfitAlertModels.ProfitAlert> alerts,
      List<ReportingProfitAlertModels.ProfitAlertUndecided> undecided) {
    if (feeApplicable.isEmpty()) {
      // 该租户在店铺维度上压根没有费用类成本 ⇒ 这条预警不适用（不是「费用没超标」）。
      return;
    }
    for (var entry : metrics.entrySet()) {
      var key = entry.getKey();
      var current = entry.getValue().get(currentWin);
      if (current == null || current.revenue() == null) continue;
      if (current.feeRatio() == null) {
        undecided.add(
            undecided(
                rule.alertType(),
                key,
                "这一格店铺的费用占比**算不出来**（缺 "
                    + String.join("、", current.missingFees())
                    + " 的事实，或收入为 0）⇒ 判不了费用占比是否异常，**不是「没异常」**。"));
        continue;
      }
      if ("FIXED".equals(rule.thresholdMode())) {
        var previous = entry.getValue().get(currentWin - 1);
        var previousRatio = previous == null ? null : previous.feeRatio();
        if (current.feeRatio().compareTo(rule.thresholdValue()) < 0) continue;
        alerts.add(
            alert(
                rule,
                key,
                "FEE_RATIO",
                current.feeRatio(),
                "PERCENT",
                previousRatio,
                previousRatio == null ? null : current.feeRatio().subtract(previousRatio),
                "PREVIOUS_WINDOW",
                current.revenue(),
                current.grossProfit(),
                from,
                to,
                "店铺 "
                    + key.scopeKey()
                    + " 费用（"
                    + String.join(" + ", labelsOf(feeApplicable))
                    + "）占收入 "
                    + current.feeRatio().setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                    + "%，达到阈值 "
                    + rule.thresholdValue().setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                    + "%。",
                "费用占比的分母是**该店铺窗口内的收入**（订单总额口径）；"
                    + "费用项只算该维度上**适用且有事实**的那些（缺项 = 算不出来，不是 0）。"));
        continue;
      }

      var baseline =
          baselineMean(entry.getValue(), currentWin, rule.minHistoryPeriods(), Metric.FEE_RATIO);
      if (baseline == null) {
        undecided.add(
            undecided(
                rule.alertType(),
                key,
                "历史基线**算不出来**（前 "
                    + rule.minHistoryPeriods()
                    + " 期同口径窗口里可比的不足 "
                    + rule.minHistoryPeriods()
                    + " 期）⇒ 判不了费用占比是否突增，**不是「没异常」**。"));
        continue;
      }
      var delta = current.feeRatio().subtract(baseline);
      if (delta.compareTo(rule.thresholdValue()) < 0) continue;
      alerts.add(
          alert(
              rule,
              key,
              "FEE_RATIO",
              current.feeRatio(),
              "PERCENT",
              baseline,
              delta,
              "BASELINE_MEAN",
              current.revenue(),
              current.grossProfit(),
              from,
              to,
              "店铺 "
                  + key.scopeKey()
                  + " 费用占比 "
                  + current.feeRatio().setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                  + "%，相对历史基线 "
                  + baseline.setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                  + "% 上升 "
                  + delta.setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                  + " 个百分点，达到阈值 "
                  + rule.thresholdValue().setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                  + " 个百分点。",
              "蓝图关系句：「**成本结构变化比绝对值更有信号**：广告占比突然上升往往先于利润下滑发生」—— "
                  + "所以费用占比**上升**（不只是绝对值高）就会被盯上。"));
    }
  }

  /** 广告亏损超标（广告费 ÷ 毛利 的倍数**）。 */
  private void evaluateAdLoss(
      Rule rule,
      Map<ScopeKey, Map<Integer, ScopeMetrics>> metrics,
      Set<String> applicable,
      int currentWin,
      LocalDate from,
      LocalDate to,
      List<ReportingProfitAlertModels.ProfitAlert> alerts,
      List<ReportingProfitAlertModels.ProfitAlertUndecided> undecided) {
    for (var entry : metrics.entrySet()) {
      var key = entry.getKey();
      var current = entry.getValue().get(currentWin);
      if (current == null || current.revenue() == null) continue;
      if (current.adFee() == null) {
        undecided.add(
            undecided(
                rule.alertType(),
                key,
                "这一格店铺**没有广告费事实**（是「没投广告」还是「上游还没投影」分不出来）"
                    + "⇒ 判不了广告亏损，**不是「广告没超标」**。"));
        continue;
      }
      if (current.grossProfit() == null) {
        undecided.add(
            undecided(
                rule.alertType(),
                key,
                "这一格店铺的毛利**算不出来**（缺 "
                    + String.join("、", current.missingCosts())
                    + " 的事实）⇒ 广告倍数没有分母，判不了，**不是「广告没超标」**。"));
        continue;
      }
      if (current.grossProfit().signum() <= 0) {
        undecided.add(
            undecided(
                rule.alertType(),
                key,
                "这一格店铺的毛利已经 ≤ 0（"
                    + current.grossProfit().setScale(2, RoundingMode.HALF_UP).toPlainString()
                    + " "
                    + key.currency()
                    + "）⇒ 广告倍数没有意义，判不了。"
                    + "整体亏损请去利润报表看毛利构成。"));
        continue;
      }
      var multiple = current.adFee().divide(current.grossProfit(), DIVIDE_SCALE, RoundingMode.HALF_UP);
      var previous = entry.getValue().get(currentWin - 1);
      BigDecimal previousMultiple = null;
      if (previous != null
          && previous.grossProfit() != null
          && previous.grossProfit().signum() > 0
          && previous.adFee() != null) {
        previousMultiple =
            previous.adFee().divide(previous.grossProfit(), DIVIDE_SCALE, RoundingMode.HALF_UP);
      }
      if (multiple.compareTo(rule.thresholdValue()) < 0) continue;
      alerts.add(
          alert(
              rule,
              key,
              "AD_FEE_TO_GROSS_PROFIT",
              multiple,
              "MULTIPLE",
              previousMultiple,
              previousMultiple == null ? null : multiple.subtract(previousMultiple),
              "PREVIOUS_WINDOW",
              current.revenue(),
              current.grossProfit(),
              from,
              to,
              "店铺 "
                  + key.scopeKey()
                  + " 广告费 "
                  + current.adFee().setScale(2, RoundingMode.HALF_UP).toPlainString()
                  + " "
                  + key.currency()
                  + " 是毛利 "
                  + current.grossProfit().setScale(2, RoundingMode.HALF_UP).toPlainString()
                  + " "
                  + key.currency()
                  + " 的 "
                  + multiple.setScale(2, RoundingMode.HALF_UP).toPlainString()
                  + " 倍，达到阈值 "
                  + rule.thresholdValue().setScale(2, RoundingMode.HALF_UP).toPlainString()
                  + " 倍。",
              "⚠️ **本模块不拉广告归因成交**（归因口径归 14 广告）："
                  + "这里的广告费是 12.3.1.2 结算明细里的**店铺级**广告费，"
                  + "倍数高说明「广告这一项吃掉了多少毛利」，**不等于 ROAS**。"));
    }
  }

  /** 订单级亏损订单占比突增（**分母只算可比订单**）。 */
  private void evaluateLossOrderRatio(
      Rule rule,
      Map<ScopeKey, Map<Integer, ScopeMetrics>> metrics,
      Map<OrderScopeKey, List<OrderStats>> orderStats,
      int currentWin,
      LocalDate from,
      LocalDate to,
      List<ReportingProfitAlertModels.ProfitAlert> alerts,
      List<ReportingProfitAlertModels.ProfitAlertUndecided> undecided) {
    for (var entry : metrics.entrySet()) {
      var key = entry.getKey();
      var current = entry.getValue().get(currentWin);
      if (current == null || current.revenue() == null) continue;

      var stats = orderStats.get(new OrderScopeKey(key.scopeKey(), key.currency()));
      if (stats == null || stats.isEmpty()) {
        undecided.add(
            undecided(
                rule.alertType(),
                key,
                "这一格店铺的收入事实**挂不上订单** ⇒ 订单级亏损算不出来，**不是「没有亏损订单」**。"));
        continue;
      }
      var currentStats = stats.get(currentWin);
      if (currentStats == null) {
        undecided.add(
            undecided(
                rule.alertType(),
                key,
                "这个窗口里没有订单级事实 ⇒ 判不了亏损订单占比，**不是「没有亏损订单」**。"));
        continue;
      }
      if (currentStats.comparableOrders() == 0) {
        undecided.add(
            undecided(
                rule.alertType(),
                key,
                "这个窗口里的 "
                    + currentStats.totalOrders()
                    + " 个订单**都算不出毛利**（成本项缺失）⇒ 分母为 0，判不了占比，"
                    + "**不是「没有亏损订单」**。"));
        continue;
      }
      var ratio =
          BigDecimal.valueOf(currentStats.lossOrders())
              .multiply(new BigDecimal("100"))
              .divide(new BigDecimal(currentStats.comparableOrders()), DIVIDE_SCALE, RoundingMode.HALF_UP)
              .setScale(RATE_SCALE, RoundingMode.HALF_UP);

      if ("FIXED".equals(rule.thresholdMode())) {
        var previous = stats.get(currentWin - 1);
        BigDecimal previousRatio = null;
        if (previous != null && previous.comparableOrders() > 0) {
          previousRatio =
              BigDecimal.valueOf(previous.lossOrders())
                  .multiply(new BigDecimal("100"))
                  .divide(new BigDecimal(previous.comparableOrders()), DIVIDE_SCALE, RoundingMode.HALF_UP)
                  .setScale(RATE_SCALE, RoundingMode.HALF_UP);
        }
        if (ratio.compareTo(rule.thresholdValue()) < 0) continue;
        alerts.add(
            alert(
                rule,
                key,
                "LOSS_ORDER_RATIO",
                ratio,
                "PERCENT",
                previousRatio,
                previousRatio == null ? null : ratio.subtract(previousRatio),
                "PREVIOUS_WINDOW",
                current.revenue(),
                current.grossProfit(),
                from,
                to,
                "店铺 "
                    + key.scopeKey()
                    + " 亏损订单占比 "
                    + ratio.setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                    + "%（亏损 "
                    + currentStats.lossOrders()
                    + " / 可比 "
                    + currentStats.comparableOrders()
                    + "，订单总数 "
                    + currentStats.totalOrders()
                    + "），达到阈值 "
                    + rule.thresholdValue().setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                    + "%。",
                "分母只算**毛利算得出来**的订单 ⇒ 这个占比是**下界**；"
                    + "算不出毛利的订单数一并给出（"
                    + (currentStats.totalOrders() - currentStats.comparableOrders())
                    + " 个），不假装它们盈利。"));
        continue;
      }

      var historyRatios = new ArrayList<BigDecimal>();
      for (int k = 1; k <= rule.minHistoryPeriods(); k++) {
        var window = stats.get(currentWin - k);
        if (window == null || window.comparableOrders() == 0) continue;
        historyRatios.add(
            BigDecimal.valueOf(window.lossOrders())
                .multiply(new BigDecimal("100"))
                .divide(new BigDecimal(window.comparableOrders()), DIVIDE_SCALE, RoundingMode.HALF_UP)
                .setScale(RATE_SCALE, RoundingMode.HALF_UP));
      }
      if (historyRatios.size() < rule.minHistoryPeriods()) {
        undecided.add(
            undecided(
                rule.alertType(),
                key,
                "历史基线**算不出来**（前 "
                    + rule.minHistoryPeriods()
                    + " 期同口径窗口里可比的不足 "
                    + historyRatios.size()
                    + " 期）⇒ 判不了占比是否突增，**不是「没突增」**。"));
        continue;
      }
      var baseline = mean(historyRatios);
      var delta = ratio.subtract(baseline);
      if (delta.compareTo(rule.thresholdValue()) < 0) continue;
      alerts.add(
          alert(
              rule,
              key,
              "LOSS_ORDER_RATIO",
              ratio,
              "PERCENT",
              baseline,
              delta,
              "BASELINE_MEAN",
              current.revenue(),
              current.grossProfit(),
              from,
              to,
              "店铺 "
                  + key.scopeKey()
                  + " 亏损订单占比 "
                  + ratio.setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                  + "%（亏损 "
                  + currentStats.lossOrders()
                  + " / 可比 "
                  + currentStats.comparableOrders()
                  + "，订单总数 "
                  + currentStats.totalOrders()
                  + "），相对历史基线 "
                  + baseline.setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                  + "% 上升 "
                  + delta.setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                  + " 个百分点，达到阈值 "
                  + rule.thresholdValue().setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString()
                  + " 个百分点。",
              "蓝图关系句：「**亏损订单占比是广告投放失控的早期信号，比 ROAS 下降更早出现**」—— "
                  + "这一格建议同时去看广告投放。"));
    }
  }

  // ==========================================================================
  // 指标
  // ==========================================================================

  /** 一次判定用到的指标。 */
  private enum Metric {
    MARGIN,
    FEE_RATIO,
    AD_MULTIPLE
  }

  /** 一个归属维度取值 × 币种在某个窗口里的指标。 */
  private record ScopeMetrics(
      BigDecimal revenue,
      BigDecimal grossProfit,
      BigDecimal margin,
      BigDecimal feeAmount,
      BigDecimal feeRatio,
      BigDecimal adFee,
      List<String> missingCosts,
      List<String> missingFees,
      boolean grossComparable,
      boolean feeComparable) {}

  /** 基线指标（固定口径下的「上一窗口」只作参考，不参与判定）。 */
  private static BigDecimal baselineMean(
      Map<Integer, ScopeMetrics> byWindow, int currentWin, int periods, Metric metric) {
    var values = new ArrayList<BigDecimal>();
    for (int k = 1; k <= periods; k++) {
      var window = byWindow.get(currentWin - k);
      if (window == null) return null;
      var value = valueOf(window, metric);
      if (value == null) return null;
      values.add(value);
    }
    return mean(values);
  }

  private static BigDecimal valueOf(ScopeMetrics metrics, Metric metric) {
    return switch (metric) {
      case MARGIN -> metrics.margin();
      case FEE_RATIO -> metrics.feeRatio();
      case AD_MULTIPLE ->
          metrics.adFee() != null
                  && metrics.grossProfit() != null
                  && metrics.grossProfit().signum() > 0
              ? metrics.adFee().divide(metrics.grossProfit(), DIVIDE_SCALE, RoundingMode.HALF_UP)
              : null;
    };
  }

  /** 算一个窗口里所有归属取值的指标。 */
  private Map<ScopeKey, Map<Integer, ScopeMetrics>> metricsOf(
      Map<ScopeKey, Map<Integer, Map<String, BigDecimal>>> sums,
      Set<String> applicableCosts,
      Set<String> applicableFees) {
    var out = new LinkedHashMap<ScopeKey, Map<Integer, ScopeMetrics>>();
    for (var entry : sums.entrySet()) {
      var byWindow = new LinkedHashMap<Integer, ScopeMetrics>();
      for (var window : entry.getValue().entrySet()) {
        byWindow.put(
            window.getKey(), metricsOfWindow(window.getValue(), applicableCosts, applicableFees));
      }
      out.put(entry.getKey(), byWindow);
    }
    return out;
  }

  /**
   * 一个窗口的指标。
   *
   * <p>⚠️ **缺项 = 算不出来，不是 0**：该成本项在租户的该维度上适用（别的范围有），
   * 而这一格没有 ⇒ 这一格的毛利**算不出来**。
   */
  private ScopeMetrics metricsOfWindow(
      Map<String, BigDecimal> amounts, Set<String> applicableCosts, Set<String> applicableFees) {
    var revenue = amounts.get("REVENUE");
    var missingCosts = new ArrayList<String>();
    var missingFees = new ArrayList<String>();
    var totalCost = BigDecimal.ZERO;
    var feeTotal = BigDecimal.ZERO;
    var grossComparable = revenue != null;
    var feeComparable = revenue != null;

    for (var component : applicableCosts) {
      var amount = amounts.get(component);
      if (amount == null) {
        missingCosts.add(componentLabel(component));
        grossComparable = false;
      } else {
        totalCost = totalCost.add(amount);
      }
    }
    for (var component : applicableFees) {
      var amount = amounts.get(component);
      if (amount == null) {
        missingFees.add(componentLabel(component));
        feeComparable = false;
      } else {
        feeTotal = feeTotal.add(amount);
      }
    }

    BigDecimal grossProfit = null;
    BigDecimal margin = null;
    if (grossComparable) {
      grossProfit = revenue.subtract(totalCost);
      if (revenue.signum() != 0) {
        margin =
            grossProfit
                .multiply(new BigDecimal("100"))
                .divide(revenue, DIVIDE_SCALE, RoundingMode.HALF_UP)
                .setScale(RATE_SCALE, RoundingMode.HALF_UP);
      }
    }
    BigDecimal feeRatio = null;
    if (feeComparable && revenue.signum() != 0) {
      feeRatio =
          feeTotal
              .multiply(new BigDecimal("100"))
              .divide(revenue, DIVIDE_SCALE, RoundingMode.HALF_UP)
              .setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }
    return new ScopeMetrics(
        revenue,
        grossProfit,
        margin,
        feeComparable ? feeTotal : null,
        feeRatio,
        amounts.get("AD_FEE"),
        missingCosts,
        missingFees,
        grossComparable,
        feeComparable);
  }

  // ==========================================================================
  // 取数
  // ==========================================================================

  /** 归属维度（取数的两种口径）。 */
  private enum Scope {
    SHOP("shop_code"),
    SKU("sku_code");

    private final String column;

    Scope(String column) {
      this.column = column;
    }
  }

  /** 归属取值 × 币种。 */
  private record ScopeKey(String scopeKey, String currency) {}

  /** 店铺 × 币种（订单级统计的键）。 */
  private record OrderScopeKey(String shopCode, String currency) {}

  /** 一个窗口里某店铺的订单统计。 */
  private record OrderStats(long totalOrders, long comparableOrders, long lossOrders) {}

  /**
   * 店铺 / SKU 维度的分桶汇总。
   *
   * <p>⚠️ **收入有两种口径，不混用**（与 13.4.1.1 逐字同构）：
   * 店铺维度取**订单总额**口径的收入行（没有 SKU 归属的那一批）；
   * SKU 维度取**订单行金额**口径的收入行（带 SKU 归属的那一批）。
   *
   * <p>⚠️ **按窗口分桶在数据库里做**（{@code div(period_start - 起点, 窗口长度)}），
   * 否则要把几十万行日粒度事实拉进内存再分桶。
   */
  private Map<ScopeKey, Map<Integer, Map<String, BigDecimal>>> scopeSums(
      UUID tenantId,
      LocalDate spanFrom,
      LocalDate spanTo,
      LocalDate bucketOrigin,
      long windowLength,
      Scope scope) {
    var caliber =
        scope == Scope.SHOP
            ? " and (component <> 'REVENUE' or (sku_code = '' and spu_code = '' and msku_code = ''))"
            : "";
    var rows =
        sql.fetch(
            "select div(period_start - ?::date, ?) as win, component, currency, "
                + scope.column
                + " as scope_key, sum(amount) as amount"
                + " from reporting_profit_fact"
                + " where tenant_id = ? and period_start >= ? and period_start <= ?"
                + " and "
                + scope.column
                + " <> ''"
                + caliber
                + " group by 1, component, currency, scope_key",
            bucketOrigin,
            (int) windowLength,
            tenantId,
            spanFrom,
            spanTo);

    var out = new LinkedHashMap<ScopeKey, Map<Integer, Map<String, BigDecimal>>>();
    for (var row : rows) {
      var win = row.get(0, Integer.class);
      var component = row.get(1, String.class);
      var currency = row.get(2, String.class);
      var scopeKey = row.get(3, String.class);
      var amount = row.get(4, BigDecimal.class);
      if (win == null) continue;
      var key = new ScopeKey(scopeKey, currency);
      var byWindow = out.computeIfAbsent(key, k -> new LinkedHashMap<Integer, Map<String, BigDecimal>>());
      var amounts = byWindow.computeIfAbsent(win, k -> new LinkedHashMap<String, BigDecimal>());
      amounts.merge(component, amount == null ? BigDecimal.ZERO : amount, BigDecimal::add);
    }
    return out;
  }

  /**
   * 订单级统计（**每个窗口**：订单总数 / 可比订单数 / 亏损订单数）。
   *
   * <p>⚠️ **可比订单的判定按店铺走**（该店铺在窗口跨度内出现过的成本项集合）：
   * 订单的成本项齐了才算得出毛利；算不出的订单**不计入亏损**（也算不进分母），
   * 页面同时给出「订单总数」与「可比订单数」，让这个占比的边界可见。
   */
  private Map<OrderScopeKey, List<OrderStats>> orderStats(
      UUID tenantId,
      LocalDate spanFrom,
      LocalDate spanTo,
      LocalDate bucketOrigin,
      long windowLength) {
    var rows =
        sql.fetch(
            """
            with applicable as (
              select shop_code, array_agg(distinct component) as comps
                from reporting_profit_fact
               where tenant_id = ? and order_ref <> '' and shop_code <> ''
                 and period_start >= ? and period_start <= ?
               group by shop_code
            ),
            per_order as (
              select div(period_start - ?::date, ?) as win,
                     shop_code, currency, order_ref,
                     sum(case when component = 'REVENUE' then amount else 0 end) as revenue,
                     sum(case when component <> 'REVENUE' then amount else 0 end) as cost,
                     array_agg(distinct component) as comps
                from reporting_profit_fact
               where tenant_id = ?
                 and period_start >= ? and period_start <= ?
                 and order_ref <> '' and shop_code <> ''
                 and (component <> 'REVENUE' or (sku_code = '' and spu_code = '' and msku_code = ''))
               group by 1, shop_code, currency, order_ref
            )
            select p.win, p.shop_code, p.currency,
                   count(*) as total_orders,
                   count(*) filter (where a.comps <@ p.comps) as comparable_orders,
                   count(*) filter (where a.comps <@ p.comps and p.revenue - p.cost < 0) as loss_orders
              from per_order p
              join applicable a on a.shop_code = p.shop_code
             group by 1, p.shop_code, p.currency
            """,
            tenantId,
            spanFrom,
            spanTo,
            bucketOrigin,
            (int) windowLength,
            tenantId,
            spanFrom,
            spanTo);

    var out = new LinkedHashMap<OrderScopeKey, List<OrderStats>>();
    for (var row : rows) {
      var win = row.get(0, Integer.class);
      if (win == null) continue;
      var key = new OrderScopeKey(row.get(1, String.class), row.get(2, String.class));
      var list = out.computeIfAbsent(key, k -> new ArrayList<OrderStats>());
      while (list.size() <= win) list.add(new OrderStats(0, 0, 0));
      list.set(
          win,
          new OrderStats(
              longOf(row.get(3, Long.class)),
              longOf(row.get(4, Long.class)),
              longOf(row.get(5, Long.class))));
    }
    return out;
  }

  // ==========================================================================
  // 规则
  // ==========================================================================

  /** 一条阈值规则（内部形态）。 */
  private record Rule(
      String alertType,
      String thresholdMode,
      BigDecimal thresholdValue,
      int minHistoryPeriods,
      String severity,
      boolean enabled) {}

  /** 播种默认规则（幂等：已存在的行不动）。 */
  private Map<String, Rule> ensureRules(UUID tenantId) {
    for (var type : ReportingProfitAlertModels.AlertType.values()) {
      sql.execute(
          """
          insert into reporting_profit_alert_rule
            (id,tenant_id,alert_type,threshold_mode,threshold_value,
             min_history_periods,severity,enabled,updated_at)
          values (?,?,?,?,?,?,?,?,now())
          on conflict (tenant_id,alert_type) do nothing
          """,
          Ids.next(),
          tenantId,
          type.name(),
          type.defaultMode(),
          type.defaultValue(),
          type.defaultMinHistory(),
          type.defaultSeverity(),
          true);
    }
    var out = new LinkedHashMap<String, Rule>();
    var rows =
        sql.fetch(
            """
            select alert_type, threshold_mode, threshold_value, min_history_periods, severity, enabled
              from reporting_profit_alert_rule
             where tenant_id = ?
             order by alert_type
            """,
            tenantId);
    for (var row : rows) {
      out.put(
          row.get(0, String.class),
          new Rule(
              row.get(0, String.class),
              row.get(1, String.class),
              row.get(2, BigDecimal.class),
              row.get(3, Integer.class),
              row.get(4, String.class),
              row.get(5, Boolean.class)));
    }
    return out;
  }

  /** 读一条规则（不存在 ⇒ 默认值）。 */
  private Rule readRule(UUID tenantId, String alertType) {
    var row =
        sql.fetchOne(
            """
            select threshold_mode, threshold_value, min_history_periods, severity, enabled
              from reporting_profit_alert_rule
             where tenant_id = ? and alert_type = ?
            """,
            tenantId,
            alertType);
    if (row == null) {
      var type = ReportingProfitAlertModels.AlertType.parse(alertType);
      return new Rule(
          alertType,
          type.defaultMode(),
          type.defaultValue(),
          type.defaultMinHistory(),
          type.defaultSeverity(),
          true);
    }
    return new Rule(
        alertType,
        row.get(0, String.class),
        row.get(1, BigDecimal.class),
        row.get(2, Integer.class),
        row.get(3, String.class),
        row.get(4, Boolean.class));
  }

  /** 规则的最后修改时刻（没有 ⇒ null）。 */
  private OffsetDateTime ruleUpdatedAt(UUID tenantId, String alertType) {
    var row =
        sql.fetchOne(
            "select max(updated_at) from reporting_profit_alert_rule"
                + " where tenant_id = ? and alert_type = ?",
            tenantId,
            alertType);
    return row == null ? null : row.get(0, OffsetDateTime.class);
  }

  /** 阈值口径（**只有支持的类型才允许 BASELINE**）。 */
  private static String resolveThresholdMode(ReportingProfitAlertModels.AlertType type, String raw) {
    var value = raw.trim().toUpperCase();
    if (!List.of("FIXED", "BASELINE").contains(value)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "thresholdMode 只能是 FIXED / BASELINE，收到：" + raw);
    }
    if ("BASELINE".equals(value) && !type.supportsBaseline()) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "「" + type.label() + "」不支持 BASELINE 口径（相对基线的倍数对它没有意义），只支持 FIXED。");
    }
    return value;
  }

  /** 严重度。 */
  private static String resolveSeverity(String raw) {
    var value = raw.trim().toUpperCase();
    if (!List.of("INFO", "WARNING", "CRITICAL").contains(value)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "severity 只能是 INFO / WARNING / CRITICAL，收到：" + raw);
    }
    return value;
  }

  /** 阈值取值范围（**服务层先挡一道**，DB 的 CHECK 是最后一道防线）。 */
  private static void validateThreshold(
      ReportingProfitAlertModels.AlertType type, String mode, BigDecimal value) {
    if (type == ReportingProfitAlertModels.AlertType.SKU_NEGATIVE_GROSS_PROFIT
        && value.signum() > 0) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "「SKU 毛利转负」的阈值是**毛利率上限**，必须 ≤ 0（否则那不是「转负」而是「偏低」），收到："
              + value.toPlainString());
    }
    if (type != ReportingProfitAlertModels.AlertType.SKU_NEGATIVE_GROSS_PROFIT
        && value.signum() < 0) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "「" + type.label() + "」的阈值是幅度，不能是负数，收到：" + value.toPlainString());
    }
    if ("FIXED".equals(mode)
        && type == ReportingProfitAlertModels.AlertType.AD_LOSS_EXCEEDED
        && value.signum() == 0) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "「广告亏损超标」的阈值是倍数，0 会让任何有广告费的店铺都命中（甚至毛利为负时恒命中）—— 请给一个正数。");
    }
  }

  // ==========================================================================
  // 组装
  // ==========================================================================

  /** 组装一条预警。 */
  private ReportingProfitAlertModels.ProfitAlert alert(
      Rule rule,
      ScopeKey key,
      String metricCode,
      BigDecimal observedValue,
      String observedUnit,
      BigDecimal baselineValue,
      BigDecimal delta,
      String baselineMode,
      BigDecimal revenue,
      BigDecimal grossProfit,
      LocalDate from,
      LocalDate to,
      String reason,
      String note) {
    var type = ReportingProfitAlertModels.AlertType.parse(rule.alertType());
    return new ReportingProfitAlertModels.ProfitAlert(
        rule.alertType(),
        type == null ? rule.alertType() : type.label(),
        rule.severity(),
        severityLabel(rule.severity()),
        type == null ? "" : type.scopeType(),
        key.scopeKey(),
        scopeLabel(type == null ? "" : type.scopeType(), key.scopeKey()),
        key.currency(),
        metricCode,
        observedValue,
        observedUnit,
        rule.thresholdMode(),
        rule.thresholdValue(),
        type == null ? "PERCENT" : type.thresholdUnit(rule.thresholdMode()),
        baselineValue,
        baselineMode,
        delta,
        revenue,
        grossProfit,
        fingerprint(rule.alertType(), type == null ? "" : type.scopeType(), key.scopeKey(), key.currency(), from, to),
        reason,
        note,
        actionsFor(type));
  }

  /** 组装一条「判不出来」的记录。 */
  private ReportingProfitAlertModels.ProfitAlertUndecided undecided(
      String alertType, ScopeKey key, String reason) {
    var type = ReportingProfitAlertModels.AlertType.parse(alertType);
    return new ReportingProfitAlertModels.ProfitAlertUndecided(
        alertType,
        type == null ? alertType : type.label(),
        type == null ? "" : type.scopeType(),
        key.scopeKey(),
        scopeLabel(type == null ? "" : type.scopeType(), key.scopeKey()),
        key.currency(),
        reason);
  }

  /** 预警指纹（通知幂等键）。 */
  private static String fingerprint(
      String alertType, String scopeType, String scopeKey, String currency, LocalDate from, LocalDate to) {
    return alertType + "|" + scopeType + "|" + scopeKey + "|" + currency + "|" + from + "|" + to;
  }

  /** 处置入口（**只指向已经存在的站内页面**）。 */
  private static List<ReportingProfitAlertModels.ProfitAlertAction> actionsFor(
      ReportingProfitAlertModels.AlertType type) {
    var actions = new ArrayList<ReportingProfitAlertModels.ProfitAlertAction>();
    if (type == null) return actions;
    switch (type) {
      case SKU_NEGATIVE_GROSS_PROFIT -> {
        actions.add(
            new ReportingProfitAlertModels.ProfitAlertAction(
                "去利润报表复核这个 SKU",
                "/analytics/profit/profit-report",
                "13.4.1.1 多模式利润报表：选 SKU 维度与该 SKU，看收入与各成本项的构成"
                    + "（本模块只给定位，不代替那边页面上的口径说明）。"));
        actions.add(
            new ReportingProfitAlertModels.ProfitAlertAction(
                "发起调价",
                "/pricing/adjust",
                "5.4.1.1 调价单：改价要走那边的单据与审批（5.4 审批规则），"
                    + "本模块**不直接改价**。"));
        break;
      }
      case SHOP_MARGIN_DROP -> {
        actions.add(
            new ReportingProfitAlertModels.ProfitAlertAction(
                "去利润报表复核这个店铺",
                "/analytics/profit/profit-report",
                "13.4.1.1 多模式利润报表：选店铺维度，看收入与各成本项的构成。"));
        actions.add(
            new ReportingProfitAlertModels.ProfitAlertAction(
                "看费用占比变化",
                "/analytics/profit/cost-structure",
                "13.4.2.1 成本结构：看费用占比相对历史基线的跳变（**成本结构变化比绝对值更有信号**）。"));
        break;
      }
      case FEE_RATIO_ANOMALY -> {
        actions.add(
            new ReportingProfitAlertModels.ProfitAlertAction(
                "看费用占比变化",
                "/analytics/profit/cost-structure",
                "13.4.2.1 成本结构：按同一店铺看占比结构与异常提示。"));
        actions.add(
            new ReportingProfitAlertModels.ProfitAlertAction(
                "去利润报表复核这个店铺",
                "/analytics/profit/profit-report",
                "13.4.1.1 多模式利润报表：看毛利构成，确认是哪一项费用把占比顶上去的。"));
        break;
      }
      case AD_LOSS_EXCEEDED -> {
        actions.add(
            new ReportingProfitAlertModels.ProfitAlertAction(
                "去广告活动",
                "/ads/campaigns",
                "14 广告：调整投放（**归因口径归广告模块**，本模块只算店铺级广告费 ÷ 毛利）。"));
        actions.add(
            new ReportingProfitAlertModels.ProfitAlertAction(
                "去广告预算",
                "/ads/budgets",
                "14 广告：收紧预算 —— 广告费已经吃掉全部毛利时，先控预算比事后追利润快。"));
        break;
      }
      case LOSS_ORDER_RATIO_SPIKE -> {
        actions.add(
            new ReportingProfitAlertModels.ProfitAlertAction(
                "去广告活动",
                "/ads/campaigns",
                "14 广告：亏损订单占比是**广告投放失控的早期信号**，比 ROAS 下降更早出现。"));
        actions.add(
            new ReportingProfitAlertModels.ProfitAlertAction(
                "去利润报表复核这个店铺",
                "/analytics/profit/profit-report",
                "13.4.1.1 多模式利润报表：看这个店铺的毛利构成，定位是哪一项成本把订单打成亏损。"));
        break;
      }
    }
    actions.add(
        new ReportingProfitAlertModels.ProfitAlertAction(
            "回到工作台",
            "/",
            "1 工作台：把这条预警挂进待办处理（处理动作在工作台那边做）。"));
    return actions;
  }

  // ==========================================================================
  // 统计与提示
  // ==========================================================================

  private ReportingProfitAlertModels.ProfitAlertSummary summary(
      List<ReportingProfitAlertModels.ProfitAlert> alerts,
      int undecidedCount,
      boolean truncated,
      boolean undecidedTruncated) {
    var critical = 0;
    var warning = 0;
    var info = 0;
    for (var alert : alerts) {
      switch (alert.severity()) {
        case "CRITICAL" -> critical++;
        case "WARNING" -> warning++;
        default -> info++;
      }
    }
    var byType =
        (int) alerts.stream().map(ReportingProfitAlertModels.ProfitAlert::alertType).distinct().count();
    var bySeverity =
        (int)
            alerts.stream().map(ReportingProfitAlertModels.ProfitAlert::severity).distinct().count();
    return new ReportingProfitAlertModels.ProfitAlertSummary(
        alerts.size(),
        byType,
        bySeverity,
        undecidedCount,
        critical,
        warning,
        info,
        truncated || undecidedTruncated);
  }

  private List<String> warnings(
      Computed computed, ReportingProfitAlertModels.ProfitAlertSummary summary, boolean undecidedTruncated) {
    var warnings = new ArrayList<String>();
    if (summary.total() > 0) {
      warnings.add(
          "命中 "
              + summary.total()
              + " 条预警（严重 "
              + summary.criticalCount()
              + " · 警告 "
              + summary.warningCount()
              + " · 提示 "
              + summary.infoCount()
              + "）。");
    }
    if (summary.undecidedCount() > 0) {
      warnings.add(
          "有 "
              + summary.undecidedCount()
              + " 条**判不出来**（不是「没异常」）：成本项缺失 / 历史期数不足 / 订单毛利算不出来 —— "
              + "每条都写明了原因。");
    }
    if (computed.truncated()) {
      warnings.add("预警条数超过 " + MAX_ALERTS + " 条，已截断（只统计前 " + MAX_ALERTS + " 条）。");
    }
    if (undecidedTruncated) {
      warnings.add(
          "「判不出来」的记录超过 " + MAX_UNDECIDED + " 条，页面只显示前 " + MAX_UNDECIDED + " 条"
              + "（总数见统计）。");
    }
    warnings.addAll(computed.notes());
    warnings.add(
        "阈值与严重度来自**租户自己的规则表**（可在「预警阈值」区改）；"
            + "基线是**前 N 期同口径窗口**的真实历史投影，不是写死的常量。");
    warnings.add(
        "预警是**按利润投影算出来的读模型**：报表是投影、可从事件重建，"
            + "与业务系统冲突时**以业务系统为准**。");
    return warnings;
  }

  /** 口径说明。 */
  private static String caliberNote() {
    return "收入：店铺 / 订单维度取**订单总额**口径（含订单级运费 / 税 / 折扣），"
        + "SKU 维度取**订单行金额**口径（不含订单级运费 / 税 / 折扣）—— **两者不相加**。"
        + " 成本：平台费 / 物流费 / 广告费取 12.3.1.2 结算明细（唯一写入所有者），"
        + "商品成本 / 头程 / 其它成本取 12.4 归集 + 12.4.2.2 分摊结果。"
        + " 分摊与费率都在上游，本模块**不重算**。"
        + " 期间：按事实的**期间起始日**落桶；基线窗口是**紧邻当前窗口的同长度前 N 期**。"
        + " 多币种：每一行带自己的币种，**绝不跨币种相加**。";
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 该维度上**适用**的成本项（租户在这个维度上确实有事实的那些）。 */
  private static Set<String> applicableComponents(
      Map<ScopeKey, Map<Integer, Map<String, BigDecimal>>> sums) {
    var out = new LinkedHashSet<String>();
    for (var byWindow : sums.values()) {
      for (var amounts : byWindow.values()) {
        for (var component : amounts.keySet()) {
          if (!"REVENUE".equals(component)) out.add(component);
        }
      }
    }
    return out;
  }

  private static Set<String> intersect(Set<String> left, Set<String> right) {
    var out = new LinkedHashSet<String>();
    for (var item : left) {
      if (right.contains(item)) out.add(item);
    }
    return out;
  }

  private static List<String> labelsOf(Set<String> components) {
    var out = new ArrayList<String>();
    for (var component : components) out.add(componentLabel(component));
    out.sort(String::compareTo);
    return out;
  }

  private static String componentLabel(String component) {
    var parsed = ReportingProfitReportModels.Component.parse(component);
    return parsed == null ? component : parsed.label();
  }

  private static String scopeLabel(String scopeType, String scopeKey) {
    if (scopeKey == null || scopeKey.isBlank()) return "（全部）";
    return ("SKU".equals(scopeType) ? "SKU " : "店铺 ") + scopeKey;
  }

  private static String severityLabel(String severity) {
    var parsed = ReportingProfitAlertModels.Severity.parse(severity);
    return parsed == null ? severity : parsed.label();
  }

  private static String statusLabel(String status) {
    return switch (status == null ? "" : status) {
      case "PENDING" -> "待处理";
      case "ACKNOWLEDGED" -> "已知悉";
      case "DISMISSED" -> "已忽略";
      default -> status;
    };
  }

  private static int severityRank(String severity) {
    return switch (severity == null ? "" : severity) {
      case "CRITICAL" -> 3;
      case "WARNING" -> 2;
      default -> 1;
    };
  }

  private static BigDecimal mean(List<BigDecimal> values) {
    if (values.isEmpty()) return null;
    var total = BigDecimal.ZERO;
    for (var value : values) total = total.add(value);
    return total
        .divide(new BigDecimal(values.size()), DIVIDE_SCALE, RoundingMode.HALF_UP)
        .setScale(RATE_SCALE, RoundingMode.HALF_UP);
  }

  private static long longOf(Long value) {
    return value == null ? 0L : value;
  }

  private static Object[] append(List<Object> params, Object... extra) {
    var out = new ArrayList<Object>(params);
    for (var item : extra) out.add(item);
    return out.toArray();
  }
}
