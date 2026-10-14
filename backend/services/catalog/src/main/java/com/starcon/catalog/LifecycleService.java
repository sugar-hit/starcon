package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.1.1.3 SPU 生命周期：状态流转、变更历史与批量规则。
 *
 * <p>状态取值由 {@link SpuLifecycle} 的矩阵唯一决定；每次流转写 {@code spu_lifecycle_event} （业务时间 occurred_at 与系统时间
 * created_at 分列）并发出下游事实事件， 停售是否联动下架平台商品由 04 刊登决定。
 *
 * <p>批量规则的判定指标分两类：catalog 自有指标（SKU 数、已启用 SKU 数、资料齐备 SKU 数）真实求值； 库存、销量、刊登等跨服务指标经 {@link
 * LifecycleProjectionPort} 读取，未接入时显式拒绝执行， 不返回 0、不造数。
 */
@Service
public class LifecycleService {
  private final DSLContext sql;
  private final CatalogService catalog;
  private final LifecycleProjectionPort projections;

  public LifecycleService(
      DSLContext sql, CatalogService catalog, LifecycleProjectionPort projections) {
    this.sql = sql;
    this.catalog = catalog;
    this.projections = projections;
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  static ResponseStatusException missing() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在或没有访问权限");
  }

  /** 商品当前生命周期：状态标签、可选流转（含是否强制原因）、变更历史。 */
  public LifecycleView view(UUID spu) {
    var row = catalog.productRow(spu);
    var current = SpuLifecycle.parse(row.get("status", String.class));
    var options =
        current.transitionsFrom().stream()
            .map(
                t ->
                    new TransitionOption(
                        t.to().name(),
                        t.to().label(),
                        t.note(),
                        t.reasonRequired(),
                        t.requiresActiveSku()))
            .toList();
    return new LifecycleView(
        spu, row.get("code", String.class), current.name(), current.label(), options, history(spu));
  }

  public List<LifecycleEvent> history(UUID spu) {
    return sql.fetch(
            "select * from spu_lifecycle_event where spu_id=? order by occurred_at desc, id desc limit 200",
            spu)
        .map(LifecycleService::event);
  }

  private static LifecycleEvent event(org.jooq.Record r) {
    return new LifecycleEvent(
        r.get("id", UUID.class),
        r.get("spu_id", UUID.class),
        r.get("from_status", String.class),
        r.get("to_status", String.class),
        r.get("reason", String.class),
        r.get("source", String.class),
        r.get("rule_id", UUID.class),
        r.get("actor_id", UUID.class),
        instant(r, "occurred_at"),
        instant(r, "created_at"));
  }

  private static String instant(org.jooq.Record r, String column) {
    OffsetDateTime value = r.get(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant().toString();
  }

  /**
   * 单个 SPU 的状态流转。必须命中 {@link SpuLifecycle} 矩阵；标记为需要原因的流转拒绝空原因； 需要前置条件的流转用 catalog
   * 自身权威数据校验（至少一个已启用且必填齐备的 SKU）。
   */
  public LifecycleEvent transition(
      AccessService.Access access, UUID spu, TransitionInput input, UUID ruleId) {
    var to = SpuLifecycle.parse(input.toStatus());
    var row = sql.fetchOne("select * from spu where id=? for update", spu);
    if (row == null) throw missing();
    check(row.get("version", Long.class) == input.version(), "商品已被修改，请刷新后重试");
    var from = SpuLifecycle.parse(row.get("status", String.class));
    var rule = from.transitionTo(to);
    String reason = input.reason() == null ? "" : input.reason().trim();
    check(!rule.reasonRequired() || !reason.isEmpty(), "该流转必须填写变更原因");
    check(reason.length() <= 500, "变更原因最多 500 字");
    if (rule.requiresActiveSku()) check(readySkuCount(spu) > 0, "至少需要一个已启用且必填资料齐备的 SKU 才能进入该状态");
    Instant occurredAt =
        input.occurredAt() == null || input.occurredAt().isBlank()
            ? Instant.now()
            : OffsetDateTime.parse(input.occurredAt()).toInstant();
    sql.execute(
        "update spu set status=?,version=version+1,updated_at=now() where id=?", to.name(), spu);
    UUID event = Ids.next();
    sql.execute(
        "insert into spu_lifecycle_event(id,tenant_id,spu_id,from_status,to_status,reason,source,rule_id,actor_id,occurred_at)"
            + " values(?,?,?,?,?,?,?,?,?,?::timestamptz)",
        event,
        access.tenantId(),
        spu,
        from.name(),
        to.name(),
        reason,
        ruleId == null ? "MANUAL" : "RULE",
        ruleId,
        access.userId(),
        OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
    audit(access, spu, "SpuLifecycleChanged");
    // 下游联动（停售是否下架平台商品）由 04 刊登决定，catalog 只发布状态事实。
    sql.execute(
        "insert into outbox_event(id,tenant_id,aggregate_id,aggregate_version,event_type,payload)"
            + " select ?,tenant_id,id,version,'SpuLifecycleChanged',"
            + " jsonb_build_object('spuId',id,'fromStatus',?::text,'toStatus',?::text,'reason',?::text,'eventId',?::text)"
            + " from spu where id=?",
        Ids.next(),
        from.name(),
        to.name(),
        reason,
        event,
        spu);
    var saved = sql.fetchOne("select * from spu_lifecycle_event where id=?", event);
    if (saved == null) throw missing();
    return event(saved);
  }

  private void audit(AccessService.Access access, UUID resource, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        access.tenantId(),
        access.userId(),
        resource,
        action);
  }

  /** 已启用且所有必填属性齐备的 SKU 数，是"能否上架"的权威判据。 */
  int readySkuCount(UUID spu) {
    var detail = catalog.detail(spu);
    var required = detail.attributes().stream().filter(Attribute::required).toList();
    int ready = 0;
    for (var sku : detail.skus()) {
      if (!sku.status().equals("ACTIVE")) continue;
      boolean complete = true;
      for (var attribute : required) {
        var value =
            sku.values().stream()
                .filter(v -> v.attributeId().equals(attribute.id()))
                .findFirst()
                .orElse(null);
        if (value == null || value.effectiveValues().isEmpty()) complete = false;
      }
      if (complete) ready++;
    }
    return ready;
  }

  // ------------------------------------------------------------------ 批量规则

  public List<LifecycleRule> rules() {
    return sql.fetch("select * from spu_lifecycle_rule order by updated_at desc, id desc limit 200")
        .map(r -> rule(r, conditions(r.get("id", UUID.class))));
  }

  public LifecycleRule rule(UUID id) {
    var r = sql.fetchOne("select * from spu_lifecycle_rule where id=?", id);
    if (r == null) throw missing();
    return rule(r, conditions(id));
  }

  private LifecycleRule rule(org.jooq.Record r, List<RuleCondition> conditions) {
    return new LifecycleRule(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("description", String.class),
        r.get("enabled", Boolean.class),
        r.get("target_status", String.class),
        r.get("reason_template", String.class),
        r.get("version", Long.class),
        conditions);
  }

  private List<RuleCondition> conditions(UUID rule) {
    return sql.fetch(
            "select * from spu_lifecycle_rule_condition where rule_id=? order by ordinal", rule)
        .map(
            c ->
                new RuleCondition(
                    c.get("metric", String.class),
                    c.get("operator", String.class),
                    c.get("threshold", BigDecimal.class),
                    LifecycleMetric.parse(c.get("metric", String.class)).label()));
  }

  public UUID saveRule(AccessService.Access access, UUID id, RuleInput input) {
    String code = CatalogService.code(input.code());
    var target = SpuLifecycle.parse(input.targetStatus());
    check(!input.conditions().isEmpty(), "批量规则至少需要一个判定条件");
    check(input.conditions().size() <= 20, "批量规则最多 20 个判定条件");
    check(
        input.reasonTemplate() == null || input.reasonTemplate().length() <= 500, "规则原因模板最多 500 字");
    var conditions = new ArrayList<RuleCondition>();
    for (var c : input.conditions()) {
      var metric = LifecycleMetric.parse(c.metric());
      check(
          Set.of("GT", "GTE", "EQ", "NEQ", "LTE", "LT").contains(c.operator()),
          "不支持的比较运算符：" + c.operator());
      check(c.threshold() != null, "判定条件必须给出阈值");
      check(
          c.threshold().scale() <= 6 && c.threshold().precision() - c.threshold().scale() <= 13,
          "阈值最多 13 位整数和 6 位小数");
      conditions.add(new RuleCondition(metric.name(), c.operator(), c.threshold(), metric.label()));
    }
    if (id == null) {
      id = Ids.next();
      sql.execute(
          "insert into spu_lifecycle_rule(id,tenant_id,code,name,description,enabled,target_status,reason_template)"
              + " values(?,?,?,?,?,?,?,?)",
          id,
          access.tenantId(),
          code,
          input.name().trim(),
          input.description(),
          input.enabled(),
          target.name(),
          input.reasonTemplate() == null ? "" : input.reasonTemplate());
    } else {
      int n =
          sql.execute(
              "update spu_lifecycle_rule set code=?,name=?,description=?,enabled=?,target_status=?,reason_template=?,"
                  + "version=version+1,updated_at=now() where id=? and version=?",
              code,
              input.name().trim(),
              input.description(),
              input.enabled(),
              target.name(),
              input.reasonTemplate() == null ? "" : input.reasonTemplate(),
              id,
              input.version());
      if (n != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "规则已更新或不存在，请刷新后重试");
      sql.execute("delete from spu_lifecycle_rule_condition where rule_id=?", id);
    }
    for (int i = 0; i < conditions.size(); i++) {
      var c = conditions.get(i);
      sql.execute(
          "insert into spu_lifecycle_rule_condition(tenant_id,rule_id,ordinal,metric,operator,threshold)"
              + " values(?,?,?,?,?,?)",
          access.tenantId(),
          id,
          i,
          c.metric(),
          c.operator(),
          c.threshold());
    }
    audit(access, id, "LifecycleRuleSaved");
    return id;
  }

  public void deleteRule(AccessService.Access access, UUID id, long version) {
    long events =
        sql.fetchOne("select count(*) from spu_lifecycle_event where rule_id=?", id)
            .get(0, Long.class);
    check(events == 0, "该规则已产生过状态变更记录，不能删除，请改为停用");
    int n = sql.execute("delete from spu_lifecycle_rule where id=? and version=?", id, version);
    check(n == 1, "规则已更新或不存在，请刷新后重试");
    audit(access, id, "LifecycleRuleDeleted");
  }

  /** 指标元数据：catalog 自有指标标注可直接求值，跨服务指标按端口实际接入情况标注可用性。 */
  public List<MetricInfo> metrics() {
    return Arrays.stream(LifecycleMetric.values())
        .map(
            m ->
                new MetricInfo(
                    m.name(),
                    m.label(),
                    m.source().name(),
                    m.source().label(),
                    m.catalogAuthoritative() || projections.supports(m),
                    m.catalogAuthoritative() ? "由 catalog 自身数据求值" : m.ownerNote()))
        .toList();
  }

  /** 规则求值预览：真实遍历候选 SPU 并逐条判定。规则引用未接入的上游投影时显式拒绝， 不以 0 代替未知值。返回的 nextCursor 用于继续扫描，绝不静默截断。 */
  public RulePreview preview(UUID ruleId, int limit, String cursor) {
    check(limit > 0 && limit <= 200, "单次扫描条数需在 1 到 200 之间");
    var rule = rule(ruleId);
    check(rule.enabled(), "规则已停用，请先启用再执行");
    requireProjections(rule.conditions());
    var sources =
        Arrays.stream(SpuLifecycle.values())
            .filter(s -> s.canTransitionTo(SpuLifecycle.parse(rule.targetStatus())))
            .map(Enum::name)
            .toList();
    // (updated_at, id) 复合游标保证翻页稳定，每条候选 SPU 都真实求值，不抽样、不静默截断。
    String after = cursor == null ? "" : cursor.trim();
    OffsetDateTime afterAt = null;
    UUID afterId = null;
    if (!after.isEmpty()) {
      int split = after.indexOf('|');
      check(split > 0, "分页游标无效，请重新加载");
      afterAt = OffsetDateTime.parse(after.substring(0, split));
      afterId = UUID.fromString(after.substring(split + 1));
    }
    // 状态取值来自本类的矩阵常量，仍按绑定参数展开为 IN 列表。
    var binds = new ArrayList<Object>();
    var in = new StringBuilder();
    for (String source : sources) {
      in.append(in.isEmpty() ? "?" : ",?");
      binds.add(source);
    }
    // 没有任何来源状态能到达目标状态时，SQL 直接为假而不是拼出非法条件。
    if (in.isEmpty()) in.append("''");
    if (afterAt != null) {
      binds.add(afterAt);
      binds.add(afterId);
    }
    binds.add(limit + 1);
    var binder =
        sql.fetch(
            "select id,tenant_id,code,name,status,updated_at from spu where status in ("
                + in
                + ")"
                + (afterAt == null ? "" : " and (updated_at,id) > (?::timestamptz,?::uuid)")
                + " order by updated_at, id limit ?",
            binds.toArray());
    var matched = new ArrayList<RuleMatch>();
    int scanned = 0;
    for (int i = 0; i < binder.size() && i < limit; i++) {
      var r = binder.get(i);
      scanned++;
      if (matches(rule.conditions(), r))
        matched.add(
            new RuleMatch(
                r.get("id", UUID.class),
                r.get("code", String.class),
                r.get("name", String.class),
                r.get("status", String.class)));
    }
    boolean more = binder.size() > limit;
    String next = null;
    if (more) {
      var last = binder.get(limit - 1);
      next =
          last.get("updated_at", OffsetDateTime.class).toInstant()
              + "|"
              + last.get("id", UUID.class);
    }
    return new RulePreview(
        rule.id(), rule.code(), rule.targetStatus(), scanned, matched.size(), more, next, matched);
  }

  /** 执行批量流转：逐个 SPU 走同一套矩阵校验（原因、前置条件、版本）， 任何一条不合法即整体回滚——调用方已在同一租户事务内。 */
  public RuleRun run(AccessService.Access access, RuleInputScope scope) {
    var rule = rule(scope.ruleId());
    check(rule.enabled(), "规则已停用，请先启用再执行");
    requireProjections(rule.conditions());
    String reason =
        scope.reason() == null || scope.reason().isBlank()
            ? rule.reasonTemplate()
            : scope.reason().trim();
    check(!reason.isBlank(), "批量流转必须给出原因");
    int matched = 0;
    int applied = 0;
    String cursor = null;
    do {
      var page = preview(rule.id(), Math.min(200, Math.max(1, scope.limit())), cursor);
      cursor = page.nextCursor();
      for (var match : page.matches()) {
        matched++;
        long version =
            sql.fetchOne("select version from spu where id=?", match.spuId())
                .get("version", Long.class);
        transition(
            access,
            match.spuId(),
            new TransitionInput(rule.targetStatus(), reason, scope.occurredAt(), version),
            rule.id());
        applied++;
      }
    } while (cursor != null);
    sql.execute(
        "update spu_lifecycle_rule set version=version+1,updated_at=now() where id=?", rule.id());
    return new RuleRun(rule.id(), rule.code(), rule.targetStatus(), matched, applied, reason);
  }

  private void requireProjections(List<RuleCondition> conditions) {
    List<String> missing =
        conditions.stream()
            .map(c -> LifecycleMetric.parse(c.metric()))
            .filter(m -> !m.catalogAuthoritative())
            .filter(m -> !projections.supports(m))
            .map(m -> m.label() + "（" + m.ownerNote() + "）")
            .distinct()
            .toList();
    if (!missing.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "规则引用了尚未接入的上游投影，无法求值：" + String.join("；", missing) + "。请先接入对应服务的投影，或改用 catalog 自有指标。");
  }

  private boolean matches(List<RuleCondition> conditions, org.jooq.Record spu) {
    for (var c : conditions) {
      var metric = LifecycleMetric.parse(c.metric());
      BigDecimal value =
          metric.catalogAuthoritative()
              ? catalogMetric(metric, spu.get("id", UUID.class))
              : projected(metric, spu);
      if (!compare(value, c.operator(), c.threshold())) return false;
    }
    return true;
  }

  private BigDecimal projected(LifecycleMetric metric, org.jooq.Record spu) {
    var q =
        projections.quantity(spu.get("tenant_id", UUID.class), spu.get("id", UUID.class), metric);
    if (!q.available())
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, q.note());
    return q.value();
  }

  private BigDecimal catalogMetric(LifecycleMetric metric, UUID spu) {
    long count =
        switch (metric) {
          case SKU_COUNT ->
              sql.fetchOne("select count(*) from sku where spu_id=?", spu).get(0, Long.class);
          case ACTIVE_SKU_COUNT ->
              sql.fetchOne("select count(*) from sku where spu_id=? and status='ACTIVE'", spu)
                  .get(0, Long.class);
          case READY_SKU_COUNT -> readySkuCount(spu);
          default -> throw new IllegalStateException("非 catalog 自有指标必须经投影端口读取：" + metric);
        };
    return BigDecimal.valueOf(count);
  }

  static boolean compare(BigDecimal value, String operator, BigDecimal threshold) {
    int c = value.compareTo(threshold);
    return switch (operator) {
      case "GT" -> c > 0;
      case "GTE" -> c >= 0;
      case "EQ" -> c == 0;
      case "NEQ" -> c != 0;
      case "LTE" -> c <= 0;
      case "LT" -> c < 0;
      default -> throw new IllegalArgumentException("不支持的比较运算符：" + operator);
    };
  }
}
