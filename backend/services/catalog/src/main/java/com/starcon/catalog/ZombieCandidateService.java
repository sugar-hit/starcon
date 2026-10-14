package com.starcon.catalog;

import static com.starcon.catalog.ZombieCandidateModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.9.2.2 僵尸主数据清理：候选清单、规则配置、批量停用、停用记录与上游快照接入。
 *
 * <p>全部走 jOOQ 显式 SQL（AGENTS.md：不用 JPA 懒加载，关键 SQL 必须可审阅）。
 *
 * <p><b>判定在 SQL 侧完成，不在内存里过滤</b>：候选清单的"僵尸"是一个由四个维度与 时间阈值共同决定的**派生结论**，如果在内存里过滤再分页，就会出现「第 1 页 20 条里 3
 * 条是候选」而总数显示 50 —— 那是假分页，而且运营点第 2 页会看到上一页已经出现的行。 所以 {@link #verdictSql} 把三态判定整体下推到
 * SQL，分页与计数走同一份表达式。 逐条的"为什么"仍然由 {@link ZombieCandidateRules} 在内存里算 —— 它要产出可读原因， 而原因不参与筛选。
 */
@Service
public class ZombieCandidateService {

  private final DSLContext sql;
  private final ObjectMapper json = new ObjectMapper();

  public ZombieCandidateService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 词表

  /**
   * 词表：主体类型、引用维度、判定状态与停用目标。
   *
   * <p>维度的 {@code wired} 标注**当前仓库里是否已有该维度的服务**： 界面上未接入的维度不允许被启用（会一直判"无法判定"），运营由此知道差的是哪个上游事件，
   * 而不是以为配置没保存成功。
   */
  public ZombieOptions options() {
    return new ZombieOptions(
        ZombieEntityType.ordered().stream()
            .map(t -> new ZombieEntityTypeOption(t.name(), t.label(), t.table()))
            .toList(),
        ZombieMetric.ordered().stream()
            .map(
                m ->
                    new ZombieMetricOption(
                        m.name(), m.label(), m.owner(), m.source(), upstreamWired(m.source())))
            .toList(),
        List.of(
            new ZombieVerdictOption(
                ZombieVerdict.CANDIDATE.name(), ZombieVerdict.CANDIDATE.label(), true),
            new ZombieVerdictOption(
                ZombieVerdict.NOT_ZOMBIE.name(), ZombieVerdict.NOT_ZOMBIE.label(), false),
            new ZombieVerdictOption(
                ZombieVerdict.INCOMPLETE.name(), ZombieVerdict.INCOMPLETE.label(), false)),
        List.of("INACTIVE"));
  }

  /** 该维度的上游服务在本仓库是否存在。库存是四个维度里唯一已交付的。 */
  private static boolean upstreamWired(String source) {
    return "warehouse".equals(source);
  }

  // ================================================================ 规则配置

  /** 全部三类主体的规则。没配过的返回默认规则（**不落库**，configured=false）。 */
  public List<ZombieCleanupRuleView> rules() {
    var rows = sql.fetch("select * from master_data_cleanup_rule order by entity_type");
    var byType = new LinkedHashMap<String, org.jooq.Record>();
    for (var row : rows) byType.put(row.get("entity_type", String.class), row);
    var result = new ArrayList<ZombieCleanupRuleView>();
    for (var type : ZombieEntityType.ordered()) {
      var row = byType.get(type.name());
      result.add(row == null ? defaultRuleView(type) : ruleView(row));
    }
    return List.copyOf(result);
  }

  public ZombieCleanupRuleView rule(ZombieEntityType type) {
    var row =
        sql.fetchOne("select * from master_data_cleanup_rule where entity_type=?", type.name());
    return row == null ? defaultRuleView(type) : ruleView(row);
  }

  private ZombieCleanupRuleView defaultRuleView(ZombieEntityType type) {
    var defaults = ZombieCandidateRules.defaultConfig(type);
    return new ZombieCleanupRuleView(
        type.name(),
        type.label(),
        defaults.inactiveDays(),
        defaults.snapshotStaleDays(),
        false,
        false,
        false,
        false,
        defaults.enabled(),
        "按僵尸主数据清理规则批量停用",
        0L,
        false);
  }

  private ZombieCleanupRuleView ruleView(org.jooq.Record row) {
    return new ZombieCleanupRuleView(
        row.get("entity_type", String.class),
        ZombieEntityType.parse(row.get("entity_type", String.class)).label(),
        row.get("inactive_days", Integer.class),
        row.get("snapshot_stale_days", Integer.class),
        row.get("require_zero_order_reference", Boolean.class),
        row.get("require_zero_inventory", Boolean.class),
        row.get("require_zero_listing", Boolean.class),
        row.get("require_zero_cost", Boolean.class),
        row.get("enabled", Boolean.class),
        row.get("deactivate_note", String.class),
        row.get("version", Long.class),
        true);
  }

  /** 保存规则（首次保存走 INSERT，之后走带乐观锁的 UPDATE）。 */
  public ZombieCleanupRuleView saveRule(
      AccessService.Access actor, ZombieEntityType type, ZombieCleanupRuleInput input) {
    var note =
        input.deactivateNote() == null || input.deactivateNote().isBlank()
            ? "按僵尸主数据清理规则批量停用"
            : input.deactivateNote().trim();
    var existing =
        sql.fetchOne(
            "select version from master_data_cleanup_rule where entity_type=?", type.name());
    if (existing == null) {
      sql.execute(
          "insert into master_data_cleanup_rule(tenant_id,entity_type,inactive_days,"
              + "snapshot_stale_days,require_zero_order_reference,require_zero_inventory,"
              + "require_zero_listing,require_zero_cost,enabled,deactivate_note,version)"
              + " values(?,?,?,?,?,?,?,?,?,?,0)",
          currentTenantId(),
          type.name(),
          input.inactiveDays(),
          input.snapshotStaleDays(),
          input.requireZeroOrderReference(),
          input.requireZeroInventory(),
          input.requireZeroListing(),
          input.requireZeroCost(),
          input.enabled(),
          note);
    } else {
      var updated =
          sql.execute(
              "update master_data_cleanup_rule set inactive_days=?,snapshot_stale_days=?,"
                  + "require_zero_order_reference=?,require_zero_inventory=?,require_zero_listing=?,"
                  + "require_zero_cost=?,enabled=?,deactivate_note=?,version=version+1,"
                  + "updated_at=now() where entity_type=? and version=?",
              input.inactiveDays(),
              input.snapshotStaleDays(),
              input.requireZeroOrderReference(),
              input.requireZeroInventory(),
              input.requireZeroListing(),
              input.requireZeroCost(),
              input.enabled(),
              note,
              type.name(),
              input.version());
      if (updated == 0) {
        throw conflict("规则已被其他操作改动，请刷新后重试");
      }
    }
    audit(actor, type.name(), "zombie-rule-save");
    return rule(type);
  }

  private ZombieCandidateRules.Config configOf(ZombieEntityType type) {
    var required = new EnumMap<ZombieMetric, Boolean>(ZombieMetric.class);
    var view = rule(type);
    required.put(ZombieMetric.ORDER_LINE_COUNT, view.requireZeroOrderReference());
    required.put(ZombieMetric.INVENTORY_ON_HAND, view.requireZeroInventory());
    required.put(ZombieMetric.LISTING_COUNT, view.requireZeroListing());
    required.put(ZombieMetric.UNIT_COST, view.requireZeroCost());
    return new ZombieCandidateRules.Config(
        type,
        view.inactiveDays(),
        view.snapshotStaleDays(),
        required,
        view.enabled(),
        view.version());
  }

  // ================================================================ 候选清单

  /**
   * 候选清单（分页 + 主体类型 / 关键词 / 判定状态筛选）。
   *
   * <p>{@code keyword} 覆盖编码与名称，LIKE 通配符转义（与 PartyService 同一份实现）。
   */
  public ZombieCandidatePage list(ZombieCandidateQuery query) {
    var type =
        query.entityType() == null || query.entityType().isBlank()
            ? ZombieEntityType.SKU
            : ZombieEntityType.parse(query.entityType());
    var config = configOf(type);
    int page = query.page() == null || query.page() < 0 ? 0 : query.page();
    int size = query.size() == null || query.size() <= 0 ? 20 : Math.min(query.size(), 200);
    var verdictFilter = parseVerdict(query.verdict());

    if (!config.enabled()) {
      // 规则被关掉意味着"这个类型不参与扫描"。返回空页而不是回退到默认规则 ——
      // 回退会让运营以为规则没保存成功，而真相是它被有意关掉了。
      return new ZombieCandidatePage(List.of(), 0, page, size, type.name(), 0, 0, 0);
    }

    var now = now();
    var statement = new StringBuilder();
    var binds = new ArrayList<Object>();

    statsCte(statement, binds, type, config, now);
    scoredCte(statement, binds, type, config, now, verdictFilter, query.keyword());
    statement.append(
        "select * from scored order by last_maintained_at nulls last, id limit ? offset ?");
    binds.add(size);
    binds.add((long) page * size);

    var rows = sql.fetch(statement.toString(), binds.toArray());

    var counts = new EnumMap<ZombieVerdict, Long>(ZombieVerdict.class);
    for (var v : ZombieVerdict.values()) counts.put(v, 0L);
    var countStatement = new StringBuilder();
    var countBinds = new ArrayList<Object>();
    statsCte(countStatement, countBinds, type, config, now);
    scoredCte(countStatement, countBinds, type, config, now, null, query.keyword());
    countStatement.append("select verdict,count(*) as n from scored group by verdict");
    for (var row : sql.fetch(countStatement.toString(), countBinds.toArray())) {
      counts.put(ZombieVerdict.valueOf(row.get("verdict", String.class)), row.get("n", Long.class));
    }

    var observations =
        loadObservations(type, rows.stream().map(r -> r.get("id", UUID.class)).toList());
    var items = new ArrayList<ZombieCandidate>(rows.size());
    for (var row : rows) {
      var evaluation = evaluateInMemory(config, row, observations, now);
      var lastMaintained = row.get("last_maintained_at", OffsetDateTime.class);
      items.add(
          new ZombieCandidate(
              row.get("id", UUID.class),
              type.name(),
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("status", String.class),
              lastMaintained,
              ZombieCandidateRules.daysSince(lastMaintained, now) < 0
                  ? null
                  : ZombieCandidateRules.daysSince(lastMaintained, now),
              evaluation.verdict().name(),
              evaluation.verdict().label(),
              evaluation.reasons(),
              evaluation.dimensions(),
              config.inactiveDays(),
              config.enabled()));
    }
    long total = counts.values().stream().mapToLong(Long::longValue).sum();
    return new ZombieCandidatePage(
        List.copyOf(items),
        total,
        page,
        size,
        type.name(),
        counts.getOrDefault(ZombieVerdict.CANDIDATE, 0L),
        counts.getOrDefault(ZombieVerdict.NOT_ZOMBIE, 0L),
        counts.getOrDefault(ZombieVerdict.INCOMPLETE, 0L));
  }

  /** 概览：三种主体各自的活跃数与三态分布，以及每个规则的参数与依赖维度数。 */
  public ZombieCandidateOverview overview() {
    var rows = new ArrayList<ZombieOverviewRow>();
    int coveredEntities = 0;
    int coveredDimensions = 0;
    var unmapped = new ArrayList<String>();
    for (var type : ZombieEntityType.ordered()) {
      var config = configOf(type);
      var activeCount = scalar(activeCountSql(type), activeStatusBinds(type));
      var counts = new EnumMap<ZombieVerdict, Long>(ZombieVerdict.class);
      for (var v : ZombieVerdict.values()) counts.put(v, 0L);
      if (config.enabled()) {
        var now = now();
        var statement = new StringBuilder();
        var binds = new ArrayList<Object>();
        statsCte(statement, binds, type, config, now);
        scoredCte(statement, binds, type, config, now, null, null);
        statement.append("select verdict,count(*) as n from scored group by verdict");
        for (var row : sql.fetch(statement.toString(), binds.toArray())) {
          counts.put(
              ZombieVerdict.valueOf(row.get("verdict", String.class)), row.get("n", Long.class));
        }
      }
      rows.add(
          new ZombieOverviewRow(
              type.name(),
              type.label(),
              activeCount,
              counts.getOrDefault(ZombieVerdict.CANDIDATE, 0L),
              counts.getOrDefault(ZombieVerdict.NOT_ZOMBIE, 0L),
              counts.getOrDefault(ZombieVerdict.INCOMPLETE, 0L),
              config.inactiveDays(),
              config.snapshotStaleDays(),
              config.requiredCount(),
              config.enabled(),
              config.version()));
      // 该类型下被任何维度观测过的主体数（覆盖率体检）。一次查询，两个用途。
      long covered = scalar(coverageSql(type), currentTenantId(), type.name());
      coveredEntities += (int) covered;
      coveredDimensions += (int) covered * config.requiredCount();
    }
    for (var metric : ZombieMetric.ordered()) {
      if (!upstreamWired(metric.source())) {
        unmapped.add(metric.label() + "（归属 " + metric.owner() + "，该服务尚未接入）");
      }
    }
    return new ZombieCandidateOverview(
        List.copyOf(rows), coveredEntities, coveredDimensions, List.copyOf(unmapped));
  }

  private String activeCountSql(ZombieEntityType type) {
    return type.partyBacked()
        ? "select count(*) from party p where p.deleted_at is null and p.status=? and exists("
            + "select 1 from party_role r where r.tenant_id=p.tenant_id and r.party_id=p.id"
            + " and r.role_type=? and r.status='ACTIVE')"
        : "select count(*) from sku s where s.status=?";
  }

  private Object[] activeStatusBinds(ZombieEntityType type) {
    return type.partyBacked()
        ? new Object[] {type.activeStatus(), type.name()}
        : new Object[] {type.activeStatus()};
  }

  /** 「有多少条主数据被任何维度观测过」—— 覆盖率体检指标。 */
  private String coverageSql(ZombieEntityType type) {
    return "select count(*) from (select distinct entity_id from master_data_reference_stats"
        + " where tenant_id=? and entity_type=?) t";
  }

  // ------------------------------------------------------------ SQL 拼装

  /**
   * 引用统计聚合 CTE。
   *
   * <p>同时聚合**原始值**（{@code v_*} / {@code t_*}）与**新鲜值**（{@code f_*}）： 判定只用新鲜值（规则 Z7：过期的当
   * UNKNOWN），而原始值留给界面显示"过期信息"—— 运营需要分清「从没观测过」与「观测过但太老了」（Z7 明确要求显式返回 stale）。
   *
   * <p>只有规则**启用**的维度才产出 {@code f_*}：未启用的维度不该出现在判定里， 为它多算一列会让 CTE 宽一倍而没人用。
   */
  private void statsCte(
      StringBuilder statement,
      List<Object> binds,
      ZombieEntityType type,
      ZombieCandidateRules.Config config,
      OffsetDateTime now) {
    var staleCutoff = now.minusDays(config.snapshotStaleDays());
    statement.append("with stats as (select entity_id,");
    var pieces = new ArrayList<String>();
    for (var metric : ZombieMetric.ordered()) {
      pieces.add(
          "max(case when metric='"
              + metric.name()
              + "' then metric_value end) as v_"
              + metric.name());
      pieces.add(
          "max(case when metric='"
              + metric.name()
              + "' then observed_at end) as t_"
              + metric.name());
      if (config.requires(metric)) {
        pieces.add(
            "max(case when metric='"
                + metric.name()
                + "' and observed_at>=?::timestamptz then metric_value end) as f_"
                + metric.name());
        // 绑定顺序必须与占位符出现的顺序逐字对应：先按 ZombieMetric.ordered() 逐个追加。
        binds.add(staleCutoff);
      }
    }
    statement.append(String.join(",", pieces));
    statement.append(" from master_data_reference_stats where tenant_id=? and entity_type=?");
    binds.add(currentTenantId());
    binds.add(type.name());
    statement.append(" group by entity_id),");
  }

  /**
   * 三态判定表达式（SQL 侧）。
   *
   * <p>与 {@link ZombieCandidateRules#evaluate} 逐字同源，顺序也一样： <b>违反优先于未知</b>。已观测到"有订单"的主体一定报
   * NOT_ZOMBIE， 哪怕它的其它维度缺观测 —— 那种情况下"无法判定"是过度保守且会误导运营。
   */
  private String verdictSql(ZombieCandidateRules.Config config) {
    var violated = new StringBuilder();
    var observed = new StringBuilder();
    for (var metric : ZombieMetric.ordered()) {
      if (!config.requires(metric)) continue;
      var fresh = "st.f_" + metric.name();
      if (observed.length() > 0) observed.append(" or ");
      observed.append(fresh).append(" is not null");
      if (violated.length() > 0) violated.append(" or ");
      violated.append("(").append(fresh).append(" is not null and ").append(fresh).append(" > 0)");
    }
    if (violated.length() == 0) {
      // 四个维度全关：判定退化成"只要超出维护阈值就是候选"（第一档治理，合法且常用）。
      return "'CANDIDATE'";
    }
    return "case when "
        + violated
        + " then 'NOT_ZOMBIE' when not ("
        + observed
        + ") then 'INCOMPLETE' else 'CANDIDATE' end";
  }

  /** 候选来源 CTE：时间窗口 + 状态 + 关键词 + 判定过滤，全部下推到 SQL。 */
  private void scoredCte(
      StringBuilder statement,
      List<Object> binds,
      ZombieEntityType type,
      ZombieCandidateRules.Config config,
      OffsetDateTime now,
      ZombieVerdict verdictFilter,
      String keyword) {
    var alias = type.partyBacked() ? "p" : "s";
    statement.append("scored as (select ");
    statement
        .append(alias)
        .append(".id, ")
        .append(alias)
        .append(".code, ")
        .append(alias)
        .append(".name, ")
        .append(alias)
        .append(".status, ")
        .append(alias)
        .append(".updated_at as last_maintained_at, ");
    // 刻意**不在 SQL 里算 days_since**：那需要在投影里绑一个 `?`（`? - s.updated_at`），
    // 而 jOOQ 对投影表达式里的无名参数计数并不可靠（报"未设定参数值 N"而 N 指向 limit/offset）。
    // 「距今天数」由 ZombieCandidateRules.daysSince 从 last_maintained_at 现算 ——
    // 同一份口径、分页与逐条判定不会因为两处算法不同而对不上。
    statement.append(verdictSql(config)).append(" as verdict");
    // 刻意**不**把 st.v_* / st.t_* 一并 select 出来逐行返回：逐条的原始观测由
    // loadObservations() 一次批量取回（同一张表、同一批 id）。两处各存一份的话，
    // "判定用的数"与"展示用的数"有机会不一致 —— 而那种不一致不会报错，
    // 只会让界面显示「库存为 0」而判定用的其实是另一个值。
    statement.append(" from ");
    if (type.partyBacked()) {
      statement
          .append("party p left join stats st on st.entity_id=p.id")
          .append(" where p.deleted_at is null and p.status=?")
          .append(" and exists(select 1 from party_role r where r.tenant_id=p.tenant_id")
          .append(" and r.party_id=p.id and r.role_type=? and r.status='ACTIVE')");
      binds.add(type.activeStatus());
      binds.add(type.name());
    } else {
      statement.append("sku s left join stats st on st.entity_id=s.id").append(" where s.status=?");
      binds.add(type.activeStatus());
    }
    // 时间窗口：Z2 的「长期未维护」。绑定 OffsetDateTime 并显式 ?::timestamptz ——
    // 用 Timestamp.valueOf(LocalDateTime) 会静默按本机时区解释，在东八区偏 -28800s，
    // 症状是"刚过阈值 8 小时的 SKU 被算成没维护"。
    statement.append(" and ").append(alias).append(".updated_at <= ?::timestamptz");
    binds.add(now.minusDays(config.inactiveDays()));
    if (keyword != null && !keyword.isBlank()) {
      var pattern = "%" + escapeLike(keyword.trim()) + "%";
      statement
          .append(" and (")
          .append(alias)
          .append(".code ilike ? escape '!' or ")
          .append(alias)
          .append(".name ilike ? escape '!')");
      binds.add(pattern);
      binds.add(pattern);
    }
    if (verdictFilter != null) {
      statement.append(" and ").append(verdictSql(config)).append("=?");
      binds.add(verdictFilter.name());
    }
    // 收尾**不带逗号**：scoredCte 后面直接接 `select ... from scored`，
    // 多一个逗号会让整个 WITH 列表语法错误（而症状是 jOOQ 报"未设定参数值 N"，
    // 指向的却是占位符本身，最难查的一种）。
    statement.append(")");
  }

  private static ZombieVerdict parseVerdict(String value) {
    if (value == null || value.isBlank()) return null;
    for (var v : ZombieVerdict.values()) if (v.name().equals(value)) return v;
    throw badRequest("未知的判定状态：" + value);
  }

  /** 一次取回一批主数据的四维观测。 */
  private Map<UUID, Map<ZombieMetric, ZombieCandidateRules.Observation>> loadObservations(
      ZombieEntityType type, List<UUID> ids) {
    var result = new LinkedHashMap<UUID, Map<ZombieMetric, ZombieCandidateRules.Observation>>();
    if (ids.isEmpty()) return result;
    var statement =
        new StringBuilder(
            "select entity_id,metric,metric_value,observed_at from master_data_reference_stats"
                + " where tenant_id=? and entity_type=? and entity_id in (");
    statement.append(String.join(",", java.util.Collections.nCopies(ids.size(), "?")));
    statement.append(")");
    var binds = new ArrayList<Object>();
    binds.add(currentTenantId());
    binds.add(type.name());
    binds.addAll(ids);
    for (var row : sql.fetch(statement.toString(), binds.toArray())) {
      var metric = ZombieMetric.parse(row.get("metric", String.class));
      var observation =
          new ZombieCandidateRules.Observation(
              row.get("metric_value", BigDecimal.class),
              row.get("observed_at", OffsetDateTime.class));
      result
          .computeIfAbsent(row.get("entity_id", UUID.class), k -> new EnumMap<>(ZombieMetric.class))
          .put(metric, observation);
    }
    return result;
  }

  /**
   * 用纯规则重算一条，产出「为什么」。
   *
   * <p>观测值一律从 {@code observations} 取（候选清单、详情、停用三个出口共用同一份来源）， **不**从 SQL 行里按列名读 —— jOOQ 对 {@code
   * Record.field(String)} 的缺列行为不可靠 （可能返回非 null 的 Field，随后 {@code get} 抛 "Field X is not contained
   * in row type"）， 而那种异常只在某一类查询路径上出现，极难定位。
   *
   * <p>SQL 侧的判定表达式与本方法算的是同一件事（{@link #verdictSql}），但 SQL 那份只用来 筛选与分页，逐条明细与原因以本方法为准。
   */
  private ZombieCandidateRules.Evaluation evaluateInMemory(
      ZombieCandidateRules.Config config,
      org.jooq.Record row,
      Map<UUID, Map<ZombieMetric, ZombieCandidateRules.Observation>> observations,
      OffsetDateTime now) {
    var id = row.get("id", UUID.class);
    var raw = observations.getOrDefault(id, Map.of());
    return ZombieCandidateRules.evaluate(
        config,
        row.get("status", String.class),
        row.get("last_maintained_at", OffsetDateTime.class),
        now,
        raw);
  }

  // ================================================================ 详情与证据

  /** 单条主数据的引用证据 + 停用历史。 */
  public ZombieReferenceEvidence evidence(ZombieEntityType type, UUID entityId) {
    var config = configOf(type);
    var now = now();
    org.jooq.Record row;
    if (type.partyBacked()) {
      row =
          sql.fetchOne(
              "select id,code,name,status,updated_at as last_maintained_at from party"
                  + " where tenant_id=? and id=? and deleted_at is null",
              currentTenantId(),
              entityId);
    } else {
      row =
          sql.fetchOne(
              "select id,code,name,status,updated_at as last_maintained_at from sku"
                  + " where tenant_id=? and id=?",
              currentTenantId(),
              entityId);
    }
    if (row == null) throw notFound("主数据不存在或不属于当前租户");
    var evaluation = evaluateInMemory(config, row, loadObservations(type, List.of(entityId)), now);
    return new ZombieReferenceEvidence(
        type.name(),
        entityId,
        row.get("code", String.class),
        row.get("name", String.class),
        row.get("status", String.class),
        row.get("last_maintained_at", OffsetDateTime.class),
        ZombieCandidateRules.daysSince(row.get("last_maintained_at", OffsetDateTime.class), now) < 0
            ? null
            : ZombieCandidateRules.daysSince(
                row.get("last_maintained_at", OffsetDateTime.class), now),
        evaluation.verdict().name(),
        evaluation.dimensions(),
        recentRecords(type, entityId));
  }

  // ================================================================ 上游快照接入

  /**
   * 上游推送引用观测（授权 API）。
   *
   * <p>这个端点**现在就是真实可用的生产契约**，不是留给未来的占位：上游接入时 不需要改本模块的 schema 或规则，只需调它。
   *
   * <p>逐条 UPSERT 而非批量写：{@code master_data_reference_stats} 的主键是 (tenant_id, entity_type, entity_id,
   * metric)，重复推送覆盖旧值 —— 保留旧值只会让"最近有订单了但仍显示 0"这种最危险的错误一直存在。 逐条 execute 沿用本仓库的写法（{@code ?::jsonb} 那种
   * cast 上 batch(String) 不可靠）。
   *
   * <p>单条失败不吞掉：主体不存在的条目会触发 {@code validate_reference_stat_entity} 触发器并被拒绝，记入 rejectedReasons
   * 而不是整批静默丢弃 —— 静默丢弃会让上游 以为推送成功了，而那一维永远停留在"未知"。
   */
  public ZombieReferenceStatPushResult pushStats(
      AccessService.Access actor, ZombieEntityType type, ZombieReferenceStatPush push) {
    var now = now();
    int written = 0;
    var rejected = new ArrayList<String>();
    for (var item : push.items()) {
      var metric = parseMetric(item.metric());
      try {
        sql.execute(
            "insert into master_data_reference_stats(tenant_id,entity_type,entity_id,metric,"
                + "metric_value,source,observed_at,received_at)"
                + " values(?,?,?,?,?,?,?::timestamptz,now())"
                + " on conflict (tenant_id,entity_type,entity_id,metric) do update set"
                + " metric_value=excluded.metric_value,source=excluded.source,"
                + "observed_at=excluded.observed_at,received_at=now()",
            currentTenantId(),
            type.name(),
            item.entityId(),
            metric.name(),
            item.metricValue(),
            item.source().trim(),
            item.observedAt());
        written++;
      } catch (DataAccessException e) {
        rejected.add(item.entityId() + " / " + metric.label() + "：" + rootMessage(e));
      }
    }
    if (written > 0) {
      audit(actor, type.name(), "zombie-reference-push");
    }
    return new ZombieReferenceStatPushResult(
        push.items().size(), written, rejected.size(), List.copyOf(rejected));
  }

  private static String rootMessage(Throwable e) {
    var current = e;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    var message = current.getMessage();
    return message == null ? current.getClass().getSimpleName() : message;
  }

  private static ZombieMetric parseMetric(String value) {
    try {
      return ZombieMetric.parse(value);
    } catch (IllegalArgumentException e) {
      throw badRequest(e.getMessage());
    }
  }

  // ================================================================ 批量停用

  /**
   * 批量停用（规则 Z3/Z4/Z5/Z6）。
   *
   * <p>准入判定重算一遍而不是信任前端传来的 verdict：前端可能拿着上一份清单， 而上游在这中间推了新快照 —— 「以提交这一刻的真实观测为准」是停用这种不可逆动作 唯一安全的口径。
   *
   * <p>准入失败**整批 409** 而不是逐条跳过（规则 Z4）：跳过会让运营以为这批都处理完了。
   */
  public ZombieDeactivateResult deactivate(
      AccessService.Access actor, ZombieEntityType type, ZombieDeactivateInput input) {
    var config = configOf(type);
    var ids = input.entityIds().stream().distinct().toList();
    var now = now();
    var rows = loadEntities(type, ids);
    var found = new LinkedHashMap<UUID, org.jooq.Record>();
    for (var row : rows) found.put(row.get("id", UUID.class), row);
    var missing = new ArrayList<String>();
    for (var id : ids) if (!found.containsKey(id)) missing.add(id.toString());
    if (!missing.isEmpty()) {
      throw notFound("以下主数据不存在或不属于当前租户：" + String.join("、", missing));
    }

    var observations = loadObservations(type, ids);
    var evaluations = new LinkedHashMap<UUID, ZombieCandidateRules.Evaluation>();
    var entries = new ArrayList<ZombieCandidateRules.BatchEntry>();
    for (var id : ids) {
      var evaluation = evaluateInMemory(config, found.get(id), observations, now);
      evaluations.put(id, evaluation);
      entries.add(
          new ZombieCandidateRules.BatchEntry(
              found.get(id).get("code", String.class),
              found.get(id).get("name", String.class),
              evaluation.verdict()));
    }
    var admission = ZombieCandidateRules.admit(entries);
    if (!admission.admissible()) {
      throw new ZombieBatchRejectedException(admission.blocked());
    }

    var view = rule(type);
    var note = view.deactivateNote();
    var reason = input.reason() == null || input.reason().isBlank() ? note : input.reason().trim();
    var ruleSnapshot = ruleSnapshot(config, view);

    var entriesOut = new ArrayList<ZombieDeactivatedEntry>(ids.size());
    for (var id : ids) {
      var row = found.get(id);
      var evaluation = evaluations.get(id);
      var code = row.get("code", String.class);
      var name = row.get("name", String.class);
      var previous = row.get("status", String.class);
      var lastMaintained = row.get("last_maintained_at", OffsetDateTime.class);
      var evidence = evidenceJson(evaluation);

      int changed;
      if (type.partyBacked()) {
        changed =
            sql.execute(
                "update party set status=?,status_reason=?,status_changed_at=now(),"
                    + "version=version+1,updated_at=now() where tenant_id=? and id=? and status=?",
                type.inactiveStatus(),
                reason,
                currentTenantId(),
                id,
                previous);
      } else {
        changed =
            sql.execute(
                "update sku set status=?,version=version+1,updated_at=now()"
                    + " where tenant_id=? and id=? and status=?",
                type.inactiveStatus(),
                currentTenantId(),
                id,
                previous);
      }
      if (changed == 0) {
        // Z6 的兜底：并发下另一笔停用刚刚改掉了状态。整批 409 而不是继续写记录 ——
        // 记录会说"这次停用了它"，而实际停用的是上一笔。
        throw conflict("部分主数据状态已被并发改动（可能已被停用），请刷新清单后重试");
      }

      sql.execute(
          "insert into master_data_cleanup_record(tenant_id,id,entity_type,entity_id,entity_code,"
              + "entity_name,previous_status,new_status,decided_by,reason,last_maintained_at,"
              + "rule_snapshot,evidence,affected_count,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::jsonb,?::jsonb,?,?::timestamptz)",
          currentTenantId(),
          Ids.next(),
          type.name(),
          id,
          code,
          name,
          previous,
          type.inactiveStatus(),
          actor.userId(),
          reason,
          lastMaintained,
          json(ruleSnapshot),
          json(evidence),
          ids.size(),
          now);
      entriesOut.add(new ZombieDeactivatedEntry(code, name, previous, type.inactiveStatus()));
    }
    audit(actor, type.name(), "zombie-deactivate");
    return new ZombieDeactivateResult(type.name(), ids.size(), List.copyOf(entriesOut), note);
  }

  private List<org.jooq.Record> loadEntities(ZombieEntityType type, List<UUID> ids) {
    var statement =
        new StringBuilder(
            type.partyBacked()
                ? "select id,code,name,status,updated_at as last_maintained_at from party where tenant_id=? and deleted_at is null and id in ("
                : "select id,code,name,status,updated_at as last_maintained_at from sku where tenant_id=? and id in (");
    statement.append(String.join(",", java.util.Collections.nCopies(ids.size(), "?")));
    statement.append(")");
    var binds = new ArrayList<Object>();
    binds.add(currentTenantId());
    binds.addAll(ids);
    return sql.fetch(statement.toString(), binds.toArray());
  }

  /** 规则冻结快照（规则 Z5）：记录要回答"凭什么"，而规则会变。 */
  private ZombieRuleSnapshot ruleSnapshot(
      ZombieCandidateRules.Config config, ZombieCleanupRuleView view) {
    return new ZombieRuleSnapshot(
        config.entityType().name(),
        config.inactiveDays(),
        config.snapshotStaleDays(),
        view.deactivateNote(),
        config.version(),
        config.requires(ZombieMetric.ORDER_LINE_COUNT),
        config.requires(ZombieMetric.INVENTORY_ON_HAND),
        config.requires(ZombieMetric.LISTING_COUNT),
        config.requires(ZombieMetric.UNIT_COST));
  }

  /** 证据冻结快照：四个维度当时的取值、观测时刻与判定状态。 */
  private ZombieEvidenceSnapshot evidenceJson(ZombieCandidateRules.Evaluation evaluation) {
    var byMetric = new EnumMap<ZombieMetric, ZombieDimensionEvidence>(ZombieMetric.class);
    for (var dimension : evaluation.dimensions()) {
      if (dimension.metric() == null) continue;
      byMetric.put(
          dimension.metric(),
          new ZombieDimensionEvidence(
              dimension.state(),
              dimension.value() == null ? null : dimension.value().toPlainString(),
              dimension.observedAt() == null ? null : dimension.observedAt().toString(),
              dimension.stale()));
    }
    return new ZombieEvidenceSnapshot(
        evaluation.verdict().name(),
        byMetric.get(ZombieMetric.ORDER_LINE_COUNT),
        byMetric.get(ZombieMetric.INVENTORY_ON_HAND),
        byMetric.get(ZombieMetric.LISTING_COUNT),
        byMetric.get(ZombieMetric.UNIT_COST));
  }

  // ================================================================ 停用记录

  /** 停用台账：时间倒序 + 主体类型 / 主体 / 操作人筛选。 */
  public ZombieCleanupRecordPage records(ZombieRecordQuery query) {
    var type =
        query.entityType() == null || query.entityType().isBlank()
            ? null
            : ZombieEntityType.parse(query.entityType());
    int page = query.page() == null || query.page() < 0 ? 0 : query.page();
    int size = query.size() == null || query.size() <= 0 ? 20 : Math.min(query.size(), 200);

    var where = new StringBuilder(" where tenant_id=?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenantId());
    if (type != null) {
      where.append(" and entity_type=?");
      binds.add(type.name());
    }
    if (query.entityId() != null) {
      where.append(" and entity_id=?");
      binds.add(query.entityId());
    }
    if (query.decidedBy() != null) {
      where.append(" and decided_by=?");
      binds.add(query.decidedBy());
    }
    var total =
        sql.fetchOne("select count(*) from master_data_cleanup_record" + where, binds.toArray())
            .get(0, Long.class);
    var pageBinds = new ArrayList<Object>(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);
    var rows =
        sql.fetch(
            "select * from master_data_cleanup_record"
                + where
                + " order by created_at desc, id limit ? offset ?",
            pageBinds.toArray());
    var items = new ArrayList<ZombieCleanupRecord>(rows.size());
    for (var row : rows) {
      items.add(
          new ZombieCleanupRecord(
              row.get("id", UUID.class),
              row.get("entity_type", String.class),
              row.get("entity_id", UUID.class),
              row.get("entity_code", String.class),
              row.get("entity_name", String.class),
              row.get("previous_status", String.class),
              row.get("new_status", String.class),
              row.get("decided_by", UUID.class),
              row.get("reason", String.class),
              row.get("last_maintained_at", OffsetDateTime.class),
              row.get("affected_count", Integer.class),
              readJson(row.get("rule_snapshot"), ZombieRuleSnapshot.class),
              readJson(row.get("evidence"), ZombieEvidenceSnapshot.class),
              row.get("created_at", OffsetDateTime.class)));
    }
    return new ZombieCleanupRecordPage(List.copyOf(items), total, page, size);
  }

  private List<ZombieCleanupRecord> recentRecords(ZombieEntityType type, UUID entityId) {
    var rows =
        sql.fetch(
            "select * from master_data_cleanup_record where tenant_id=? and entity_type=?"
                + " and entity_id=? order by created_at desc, id limit 10",
            currentTenantId(),
            type.name(),
            entityId);
    var items = new ArrayList<ZombieCleanupRecord>(rows.size());
    for (var row : rows) {
      items.add(
          new ZombieCleanupRecord(
              row.get("id", UUID.class),
              row.get("entity_type", String.class),
              row.get("entity_id", UUID.class),
              row.get("entity_code", String.class),
              row.get("entity_name", String.class),
              row.get("previous_status", String.class),
              row.get("new_status", String.class),
              row.get("decided_by", UUID.class),
              row.get("reason", String.class),
              row.get("last_maintained_at", OffsetDateTime.class),
              row.get("affected_count", Integer.class),
              readJson(row.get("rule_snapshot"), ZombieRuleSnapshot.class),
              readJson(row.get("evidence"), ZombieEvidenceSnapshot.class),
              row.get("created_at", OffsetDateTime.class)));
    }
    return List.copyOf(items);
  }

  /**
   * 读回冻结快照。
   *
   * <p>解析失败返回 null 而不是把原始字符串塞回去：停用记录是审计证据， 一个解析不出来却"看起来有内容"的字段比空值更危险（运营会以为证据还在）。
   */
  private <T> T readJson(Object value, Class<T> type) {
    if (value == null) return null;
    try {
      return json.readValue(String.valueOf(value), type);
    } catch (Exception e) {
      return null;
    }
  }

  private String json(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ================================================================ 小工具

  private UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private OffsetDateTime now() {
    return sql.fetchOne("select now()").get(0, OffsetDateTime.class);
  }

  private long scalar(String statement, Object... binds) {
    return sql.fetchOne(statement, binds).get(0, Long.class);
  }

  /**
   * LIKE 模式里的通配符转义（与 PartyService / SupplierQuoteService 同一份实现）。
   *
   * <p>不转义的话运营搜 {@code 100%} 会变成「匹配所有」，而界面上看不出来 —— 那一页返回几百条看起来还挺像对的。
   */
  private static String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private void audit(AccessService.Access actor, String resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        actor.tenantId(),
        actor.userId(),
        UUID.nameUUIDFromBytes(resourceId.getBytes()),
        action);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  /** 批量准入被拒（规则 Z4）。带结构化 blocked 清单，由 {@link ApiErrorHandler} 渲染成 409。 */
  static final class ZombieBatchRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient List<ZombieCandidateRules.BlockedEntry> blocked;

    ZombieBatchRejectedException(List<ZombieCandidateRules.BlockedEntry> blocked) {
      super("以下主数据不满足停用条件，本次批量操作已整体取消（不会跳过它们继续停用其余）");
      this.blocked = List.copyOf(blocked);
    }

    List<ZombieCandidateRules.BlockedEntry> blocked() {
      return blocked;
    }
  }
}
