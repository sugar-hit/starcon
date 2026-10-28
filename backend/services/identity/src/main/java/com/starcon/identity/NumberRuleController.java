package com.starcon.identity;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 17.5.1.2 编号规则。
 *
 * <p>蓝图对本模块的三条硬要求，在这里各有明确落点：
 *
 * <ul>
 *   <li><b>编号是业务单据的可读标识，不是外键</b>：{@code document_number_issued} 里的 {@code documentType}/{@code
 *       documentNo} 只是可读标识，单据本体在各服务自己的库里， 这里不建跨库外键。编号唯一性由 {@code UNIQUE(tenant_id, entity_code,
 *       number_text)} 兜底——同一个租户同一实体的同一个编号不可能发放两次。
 *   <li><b>规则冲突要在配置时校验</b>：同一实体上两条启用的主规则（{@code rule_kind='PRIMARY'}） 生效区间不允许重叠。{@link #create} /
 *       {@link #update} 都在写入前查一次 {@link #overlaps}，重叠返回 409。可复用子规则（{@code COMPONENT}）不参与该判定—— 它只被
 *       NESTED 段引用，结构上不会与谁争同一实体的同一时刻。
 *   <li><b>序号按实体/日期重置</b>：计数器键是 {@code (tenant_id, entity_code, scope_key, reset_key)}。{@code
 *       entity_code} 永远是 <b>根规则</b>的实体（所以同一个子规则嵌到两个不同实体下各自独立计数）； {@code reset_key} 按 {@code
 *       NONE/DAILY/MONTHLY/YEARLY} 取 {@code ALL} / 业务日期 / 年月 / 年，换一天或换一月就是一个新桶。取号走 {@code insert …
 *       on conflict … returning} 的数据库原子递增，不做应用层读-改-写。
 * </ul>
 *
 * <p>预览（{@code /preview}）走同一套渲染逻辑但**不占号**，取号（{@code /next}）才真正递增 计数器并落 {@code
 * document_number_issued}。两个出口共用 {@link #render}，所以预览里看到的
 * 组成与取号时算出来的一定是同一个编号——预览与实际不一致是这类系统最难排查的故障之一。
 *
 * <p>多级嵌套：规则由有序的段拼成，{@code NESTED} 段递归展开子规则。环与深度在**配置时** 就拒（{@link #descendsFrom}），渲染时再兜一层 {@link
 * #MAX_DEPTH}。
 */
@RestController
@RequestMapping("/identity")
public class NumberRuleController {
  /** 嵌套深度上限。写入时用可达图查环已经能挡住环，这里的上限挡的是"合法但深得离谱"的 配置（比如 A 嵌 B 嵌 C… 十层），渲染是递归的，不设上限会让一次预览把栈打爆。 */
  private static final int MAX_DEPTH = 8;

  private final DSLContext sql;
  private final IdentityController identity;
  private final TenantTransactions tenants;
  private final AuditLog audit;

  public NumberRuleController(
      DSLContext sql, IdentityController identity, PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.audit = new AuditLog(sql, identity);
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  // ------------------------------------------------------------------
  // 契约类型
  // ------------------------------------------------------------------

  /** 出厂单据实体码表。全租户共用一份，不带租户上下文。 */
  public record NumberEntityView(String code, String name, String description) {}

  /**
   * 编号规则。
   *
   * <p>{@code segments} 是有序段，{@code sampleNumber} 是用「今天 + 当前计数器」渲染出来的
   * 样例——配置页靠它一眼看出规则长什么样，不必每次都手填预览参数。
   */
  public record NumberRuleView(
      UUID id,
      String entityCode,
      String entityName,
      String ruleName,
      String ruleKind,
      String status,
      String timezone,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String description,
      String sampleNumber,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      List<NumberRuleSegmentView> segments) {}

  /**
   * 规则的一个组成段。
   *
   * <p>{@code rendered} 是这一段在样例渲染里的取值（含子规则展开后的整段文本）， 让配置页能逐段看清编号是怎么拼出来的。
   */
  public record NumberRuleSegmentView(
      UUID id,
      int position,
      String segmentType,
      @Schema(nullable = true) String literalText,
      @Schema(nullable = true) String dateFormat,
      @Schema(nullable = true) Integer sequenceWidth,
      @Schema(nullable = true) BigDecimal sequenceStart,
      @Schema(nullable = true) String sequenceReset,
      @Schema(nullable = true) String sequenceScope,
      @Schema(nullable = true) UUID childRuleId,
      @Schema(nullable = true) String childRuleName,
      @Schema(nullable = true) String rendered) {}

  /** 段输入。与库里 {@code document_number_rule_segment_shape} 的 CHECK 同口径。 */
  public record NumberRuleSegmentInput(
      @NotNull @Pattern(regexp = "PREFIX|DATE|SEQUENCE|SUFFIX|LITERAL|NESTED") String segmentType,
      @Size(max = 64) String literalText,
      @Size(max = 16) String dateFormat,
      Integer sequenceWidth,
      Long sequenceStart,
      @Pattern(regexp = "NONE|DAILY|MONTHLY|YEARLY") String sequenceReset,
      @Pattern(regexp = "ENTITY|RULE") String sequenceScope,
      UUID childRuleId) {}

  /** 规则输入。{@code entityCode} / {@code ruleKind} 建后不可改：换实体等于换一条规则。 */
  public record NumberRuleInput(
      @NotBlank String entityCode,
      @NotBlank @Size(max = 100) String ruleName,
      @NotNull @Pattern(regexp = "PRIMARY|COMPONENT") String ruleKind,
      @Pattern(regexp = "ACTIVE|DISABLED") String status,
      @Size(max = 64) String timezone,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      @Size(max = 300) String description,
      @NotEmpty @Valid List<NumberRuleSegmentInput> segments) {}

  /** 预览参数：{@code documentDate} 同时驱动日期段与重置分桶，所以它决定编号长什么样。 */
  public record NumberPreviewInput(
      UUID ruleId,
      @Schema(nullable = true) String entityCode,
      OffsetDateTime documentDate,
      @Schema(nullable = true) UUID legalEntityId) {}

  /** 预览结果的一段。{@code depth} 从 0 起，NESTED 展开的子规则段 depth 更大。 */
  public record NumberPreviewPartView(
      UUID segmentId,
      String segmentType,
      UUID ruleId,
      String ruleName,
      int position,
      int depth,
      String value) {}

  /**
   * 预览结果。
   *
   * <p>{@code consumed=false} 表示这次预览**没有占用序号**：同一个参数重复预览永远得到同一个 编号，预览多少次都不该把真实计数推前。
   */
  public record NumberPreviewView(
      UUID ruleId,
      String ruleName,
      String entityCode,
      OffsetDateTime documentDate,
      String timezone,
      String number,
      boolean consumed,
      List<NumberPreviewPartView> parts,
      List<NumberSequencePlanView> sequences) {}

  /** 预览里每一处序号段的取号计划：取哪个桶、下一个值是多少、重置口径是什么。 */
  public record NumberSequencePlanView(
      UUID segmentId,
      String segmentType,
      String entityCode,
      String scopeKey,
      String resetKey,
      String resetScope,
      BigDecimal nextValue,
      String rendered) {}

  /** 取号结果：一条已发放编号。 */
  public record NumberIssuedView(
      UUID id,
      String entityCode,
      UUID ruleId,
      String ruleName,
      String number,
      BigDecimal sequenceValue,
      String resetKey,
      @Schema(nullable = true) String documentType,
      @Schema(nullable = true) String documentNo,
      @Schema(nullable = true) String actorName,
      OffsetDateTime issuedAt) {}

  /** 取号输入。单据引用是可读标识，不是外键。 */
  public record NumberIssueInput(
      OffsetDateTime documentDate,
      @Schema(nullable = true) @Size(max = 32) String documentType,
      @Schema(nullable = true) @Size(max = 64) String documentNo) {}

  /** 序号计数器一行：某个重置桶里的当前值。 */
  public record NumberSequenceView(
      String entityCode,
      String scopeKey,
      String resetKey,
      BigDecimal currentValue,
      OffsetDateTime updatedAt) {}

  /**
   * 冲突检测结果。
   *
   * <p>配置页在保存前就能看到「这两条规则的生效区间撞了」，而不是等写入时吃 409。 只列 PRIMARY：COMPONENT 不参与判定。
   */
  public record NumberRuleConflictView(
      UUID ruleId,
      String entityCode,
      String ruleName,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      boolean overlaps) {}

  private static final String RULE_READ = "identity.numbering_rule.read";
  private static final String RULE_CREATE = "identity.numbering_rule.create";
  private static final String RULE_UPDATE = "identity.numbering_rule.update";
  private static final String RULE_DELETE = "identity.numbering_rule.delete";

  /**
   * 与 {@code BusinessParameterController.call} 同口径：解析会话 → 切到该会话的租户事务 → 锁会话 → 二次校验租户未切换 → 校验权限码 →
   * 组织变更要求 OWNER。
   *
   * <p>租户来自会话成员关系，{@code X-Tenant-Id} 头在这里从头到尾没有出现过。
   */
  private <T> T call(
      String auth,
      String permission,
      boolean mutation,
      Function<IdentityController.Access, T> work) {
    var initial = identity.resolve(auth);
    return tenants.run(
        initial.tenantId(),
        () -> {
          sql.fetch("select lock_session(?)", hash(auth));
          sql.fetch(
              "select pg_advisory_xact_lock(hashtextextended(?,0))", initial.tenantId().toString());
          var a = identity.resolve(auth);
          require(a.tenantId().equals(initial.tenantId()), "租户已切换，请重试");
          require(
              a.grants().stream()
                  .anyMatch(
                      g -> g.scopeMode().equals("ALL") && g.permissions().contains(permission)),
              "当前角色没有此操作权限");
          if (mutation) require(a.owner(), "编号规则变更需由当前租户所有者执行");
          return work.apply(a);
        });
  }

  private static void require(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.FORBIDDEN, message);
  }

  private static void check(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.BAD_REQUEST, message);
  }

  private static void reject(String message) {
    throw IdentityController.error(HttpStatus.CONFLICT, message);
  }

  private String hash(String auth) {
    return IdentityController.hash(identity.token(auth));
  }

  private static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /** timestamptz 一律绑 {@link OffsetDateTime}；见 {@link AuditLog#utc} 关于 −28800s 的说明。 */
  private static OffsetDateTime utcNow() {
    return OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
  }

  // ------------------------------------------------------------------
  // 读出口
  // ------------------------------------------------------------------

  /** 出厂单据实体码表：序号重置按这里的实体分桶。 */
  @GetMapping("/numbering/entities")
  public List<NumberEntityView> entities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        RULE_READ,
        false,
        a ->
            sql
                .fetch(
                    "select code,name,description from document_entity where status='ACTIVE'"
                        + " order by sort_order,code")
                .stream()
                .map(
                    r ->
                        new NumberEntityView(
                            r.get("code", String.class),
                            r.get("name", String.class),
                            r.get("description", String.class)))
                .toList());
  }

  /**
   * 规则列表，含段与样例编号。
   *
   * <p>可按实体与种类过滤。不加过滤时返回本租户全部规则（含子规则）——配置页需要把 可复用的子规则一并列出来，否则用户根本不知道能嵌哪些进去。
   */
  @GetMapping("/numbering/rules")
  public List<NumberRuleView> rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String entityCode,
      @RequestParam(required = false) String ruleKind,
      @RequestParam(required = false) String status) {
    return call(
        auth,
        RULE_READ,
        false,
        a -> {
          var condition = new Condition(" where r.tenant_id=?");
          condition.bind(a.tenantId());
          if (entityCode != null && !entityCode.isBlank()) {
            condition.add(" and r.entity_code=?");
            condition.bind(entityCode.trim());
          }
          if (ruleKind != null && !ruleKind.isBlank()) {
            check(
                List.of("PRIMARY", "COMPONENT").contains(ruleKind), "规则种类只能是 PRIMARY 或 COMPONENT");
            condition.add(" and r.rule_kind=?");
            condition.bind(ruleKind);
          }
          if (status != null && !status.isBlank()) {
            check(List.of("ACTIVE", "DISABLED").contains(status), "状态只能是 ACTIVE 或 DISABLED");
            condition.add(" and r.status=?");
            condition.bind(status);
          }
          var head =
              sql.fetch(
                  "select r.id,r.entity_code,e.name as entity_name,r.rule_name,r.rule_kind,"
                      + " r.status,r.timezone,r.effective_from,r.effective_to,r.description,"
                      + " r.version,r.created_at,r.updated_at"
                      + " from document_number_rule r"
                      + " join document_entity e on e.code=r.entity_code"
                      + condition.sql()
                      + " order by r.entity_code,r.rule_kind,r.rule_name",
                  condition.bindings());
          return head.stream()
              .map(r -> toRule(a.tenantId(), r, segments(a.tenantId(), r.get("id", UUID.class))))
              .toList();
        });
  }

  /** 单条规则（含段）。跨租户拿到的 id 与真正不存在的 id 都返回 404。 */
  @GetMapping("/numbering/rules/{id}")
  public NumberRuleView rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        RULE_READ,
        false,
        a -> {
          var row =
              sql.fetchOne(selectRule() + " where r.tenant_id=? and r.id=?", a.tenantId(), id);
          if (row == null) notFound();
          return toRule(a.tenantId(), row, segments(a.tenantId(), id));
        });
  }

  /**
   * 冲突检测：同实体上生效区间交叠的主规则。
   *
   * <p>与写入时用的是同一段 {@link #overlaps} 判定，所以这里报「不冲突」就一定存得进去。
   */
  @GetMapping("/numbering/rules/conflicts")
  public List<NumberRuleConflictView> conflicts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String entityCode) {
    return call(
        auth,
        RULE_READ,
        false,
        a -> {
          var condition = new Condition(" where r.tenant_id=? and r.rule_kind='PRIMARY'");
          condition.bind(a.tenantId());
          if (entityCode != null && !entityCode.isBlank()) {
            condition.add(" and r.entity_code=?");
            condition.bind(entityCode.trim());
          }
          var rows =
              sql.fetch(
                  "select r.id,r.entity_code,r.rule_name,r.effective_from,r.effective_to"
                      + " from document_number_rule r"
                      + condition.sql()
                      + " order by r.entity_code,r.effective_from",
                  condition.bindings());
          var views = new ArrayList<NumberRuleConflictView>();
          for (Record row : rows) {
            var from = row.get("effective_from", OffsetDateTime.class);
            var to = row.get("effective_to", OffsetDateTime.class);
            boolean hit = false;
            for (Record other : rows) {
              if (other.get("id", UUID.class).equals(row.get("id", UUID.class))) continue;
              if (!other
                  .get("entity_code", String.class)
                  .equals(row.get("entity_code", String.class))) continue;
              if (overlaps(
                  from,
                  to,
                  other.get("effective_from", OffsetDateTime.class),
                  other.get("effective_to", OffsetDateTime.class))) {
                hit = true;
                break;
              }
            }
            views.add(
                new NumberRuleConflictView(
                    row.get("id", UUID.class),
                    row.get("entity_code", String.class),
                    row.get("rule_name", String.class),
                    from,
                    to,
                    hit));
          }
          return views;
        });
  }

  /** 序号计数器视图：按实体看每个重置桶的当前值。 */
  @GetMapping("/numbering/sequences")
  public List<NumberSequenceView> sequences(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String entityCode) {
    return call(
        auth,
        RULE_READ,
        false,
        a -> {
          var condition = new Condition(" where s.tenant_id=?");
          condition.bind(a.tenantId());
          if (entityCode != null && !entityCode.isBlank()) {
            condition.add(" and s.entity_code=?");
            condition.bind(entityCode.trim());
          }
          return sql
              .fetch(
                  "select s.entity_code,s.scope_key,s.reset_key,s.current_value,s.updated_at"
                      + " from document_number_sequence s"
                      + condition.sql()
                      + " order by s.entity_code,s.reset_key desc,s.scope_key",
                  condition.bindings())
              .stream()
              .map(
                  r ->
                      new NumberSequenceView(
                          r.get("entity_code", String.class),
                          r.get("scope_key", String.class),
                          r.get("reset_key", String.class),
                          BigDecimal.valueOf(r.get("current_value", Long.class)),
                          r.get("updated_at", OffsetDateTime.class)))
              .toList();
        });
  }

  /** 已发放编号（只读、不可改）。可按实体过滤。 */
  @GetMapping("/numbering/issued")
  public List<NumberIssuedView> issued(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String entityCode) {
    return call(
        auth,
        RULE_READ,
        false,
        a -> {
          var condition = new Condition(" where i.tenant_id=?");
          condition.bind(a.tenantId());
          if (entityCode != null && !entityCode.isBlank()) {
            condition.add(" and i.entity_code=?");
            condition.bind(entityCode.trim());
          }
          return sql
              .fetch(
                  "select i.id,i.entity_code,i.rule_id,r.rule_name,i.number_text,i.sequence_value,"
                      + " i.reset_key,i.document_type,i.document_no,i.actor_name,i.issued_at"
                      + " from document_number_issued i"
                      + " left join document_number_rule r"
                      + " on r.tenant_id=i.tenant_id and r.id=i.rule_id"
                      + condition.sql()
                      + " order by i.issued_at desc,i.id desc",
                  condition.bindings())
              .stream()
              .map(
                  r ->
                      new NumberIssuedView(
                          r.get("id", UUID.class),
                          r.get("entity_code", String.class),
                          r.get("rule_id", UUID.class),
                          r.get("rule_name", String.class),
                          r.get("number_text", String.class),
                          BigDecimal.valueOf(r.get("sequence_value", Long.class)),
                          r.get("reset_key", String.class),
                          r.get("document_type", String.class),
                          r.get("document_no", String.class),
                          r.get("actor_name", String.class),
                          r.get("issued_at", OffsetDateTime.class)))
              .toList();
        });
  }

  // ------------------------------------------------------------------
  // 写出口
  // ------------------------------------------------------------------

  /**
   * 新建规则（含段）。
   *
   * <p>写入前做三件事，与 {@code 17.5.1.2 规则冲突要在配置时校验} 一一对应： 段形状校验（与库 CHECK 同口径，报错更可读）、同实体生效区间交叠检查、
   * 嵌套环与深度检查。
   */
  @PostMapping("/numbering/rules")
  public UUID create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody NumberRuleInput input) {
    return call(
        auth,
        RULE_CREATE,
        true,
        a -> {
          var normalized = normalize(input);
          entity(input.entityCode());
          if (normalized.effectiveTo() != null)
            check(normalized.effectiveTo().isAfter(normalized.effectiveFrom()), "生效结束时间必须晚于生效开始时间");
          overlaps(
              a.tenantId(),
              input.entityCode().trim(),
              null,
              normalized.effectiveFrom(),
              normalized.effectiveTo());
          UUID id = Ids.next();
          // 生效时间刻意绑在第 8 / 9 位（共 12 位），不在 values 末位：
          // 末位的 ?::timestamptz 会让 jOOQ 整体错位一格。
          sql.execute(
              "insert into document_number_rule(id,tenant_id,entity_code,rule_name,rule_kind,status,"
                  + " timezone,effective_from,effective_to,description,created_by_membership_id)"
                  + " values(?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?)",
              id,
              a.tenantId(),
              input.entityCode().trim(),
              input.ruleName().trim(),
              input.ruleKind(),
              statusOf(input.status()),
              normalized.timezone(),
              normalized.effectiveFrom(),
              normalized.effectiveTo(),
              note(input.description()),
              a.membershipId());
          writeSegments(a.tenantId(), id, input.segments());
          audit.record(
              a,
              auth,
              "identity.numbering_rule",
              id,
              input.ruleName().trim(),
              "NumberRuleCreated",
              null,
              ruleSnapshot(a.tenantId(), id));
          return id;
        });
  }

  /**
   * 更新规则。
   *
   * <p>实体与种类不允许改：换实体等于换一条规则（子规则被嵌的位置、已发放的编号都对不上）， 换种类会让它突然参与或退出冲突判定。两者不符返回 400。
   *
   * <p>段整体替换（先删后插），而不是逐段 diff：段的 {@code position} 是唯一键， 中间插入会让两个段短暂同 position，diff 出来的中间态无法落库。
   */
  @PutMapping("/numbering/rules/{id}")
  public void update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody NumberRuleInput input) {
    call(
        auth,
        RULE_UPDATE,
        true,
        a -> {
          var row =
              sql.fetchOne(
                  "select * from document_number_rule where tenant_id=? and id=?",
                  a.tenantId(),
                  id);
          if (row == null) notFound();
          var normalized = normalize(input);
          entity(input.entityCode());
          check(
              input.entityCode().trim().equals(row.get("entity_code", String.class)),
              "不能把规则改到另一个实体，请删除后新建");
          check(
              input.ruleKind().equals(row.get("rule_kind", String.class)),
              "不能改规则种类（主规则 / 子规则），请删除后新建");
          if (normalized.effectiveTo() != null)
            check(normalized.effectiveTo().isAfter(normalized.effectiveFrom()), "生效结束时间必须晚于生效开始时间");
          var status = statusOf(input.status());
          // 停用的规则不参与冲突判定：与 {@code document_number_rule_entity} 部分索引同口径，
          // 否则「停用一条又启用的规则」会因为自己也参与判定而永远放行不了。
          if ("ACTIVE".equals(status))
            overlaps(
                a.tenantId(),
                input.entityCode().trim(),
                id,
                normalized.effectiveFrom(),
                normalized.effectiveTo());
          sql.execute(
              "update document_number_rule set rule_name=?,status=?,timezone=?,"
                  + " effective_from=?::timestamptz,effective_to=?::timestamptz,description=?,"
                  + " version=version+1,updated_at=now() where tenant_id=? and id=?",
              input.ruleName().trim(),
              status,
              normalized.timezone(),
              normalized.effectiveFrom(),
              normalized.effectiveTo(),
              note(input.description()),
              a.tenantId(),
              id);
          writeSegments(a.tenantId(), id, input.segments());
          audit.record(
              a,
              auth,
              "identity.numbering_rule",
              id,
              input.ruleName().trim(),
              "NumberRuleUpdated",
              ruleSnapshot(a.tenantId(), id),
              ruleSnapshot(a.tenantId(), id));
          return null;
        });
  }

  /**
   * 删除规则。
   *
   * <p>被别的规则当子规则引用时返回 409：删掉会让引用方的规则渲染时找不到段， 整个编号立刻算不出来。已发放过编号的规则同样拒删——编号引用的是规则， 规则没了，那些编号的来源就查不到了。
   */
  @DeleteMapping("/numbering/rules/{id}")
  public void delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        RULE_DELETE,
        true,
        a -> {
          var row =
              sql.fetchOne(
                  "select * from document_number_rule where tenant_id=? and id=?",
                  a.tenantId(),
                  id);
          if (row == null) notFound();
          var usage =
              sql.fetchOne(
                  "select r.rule_name from document_number_rule_segment s"
                      + " join document_number_rule r on r.tenant_id=s.tenant_id and r.id=s.rule_id"
                      + " where s.tenant_id=? and s.child_rule_id=? limit 1",
                  a.tenantId(),
                  id);
          var used = usage == null ? null : usage.get("rule_name", String.class);
          if (used != null) reject("这条子规则已被规则「" + used + "」嵌套引用，请先取消引用再删除");
          var issuedCount =
              sql.fetchOne(
                      "select count(*) as n from document_number_issued where tenant_id=? and rule_id=?",
                      a.tenantId(),
                      id)
                  .get("n", Long.class);
          check(issuedCount == 0, "这条规则已发放过 " + issuedCount + " 个编号，不能删除；如需停用请改状态为 DISABLED");
          sql.execute(
              "delete from document_number_rule where tenant_id=? and id=?", a.tenantId(), id);
          audit.record(
              a,
              auth,
              "identity.numbering_rule",
              id,
              row.get("rule_name", String.class),
              "NumberRuleDeleted",
              ruleSnapshot(a.tenantId(), id),
              null);
          return null;
        });
  }

  /**
   * 预览生成效果（**不占用序号**）。
   *
   * <p>与取号共用 {@link #render}，区别只在 {@code consume=false}：序号段读当前计数器 算出「下一个」但不写。重复预览同一个参数永远得到同一个编号。
   */
  @PostMapping("/numbering/preview")
  public NumberPreviewView preview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody NumberPreviewInput input) {
    return call(
        auth,
        RULE_READ,
        false,
        a -> {
          Record rule = resolveRule(a.tenantId(), input.ruleId(), input.entityCode());
          var date = input.documentDate() == null ? utcNow() : input.documentDate();
          var outcome =
              render(
                  a.tenantId(),
                  rule,
                  rule.get("entity_code", String.class),
                  zone(rule),
                  date,
                  false,
                  List.of());
          return new NumberPreviewView(
              rule.get("id", UUID.class),
              rule.get("rule_name", String.class),
              rule.get("entity_code", String.class),
              date,
              zone(rule).getId(),
              outcome.text(),
              false,
              outcome.parts(),
              outcome.plans());
        });
  }

  /**
   * 取号：真正递增计数器并落一条已发放编号。
   *
   * <p>这是下游全部单据创建（订单、采购、出库、售后…）的取号入口。递增用 {@code insert … on conflict … returning} 在数据库层完成，并发取号不会撞号。
   *
   * <p>两条规则碰巧渲染出同一个编号时，唯一约束 {@code (tenant_id, entity_code, number_text)} 会让第二次发放失败——返回 409
   * 而不是产生两个看起来一样的单据号。
   */
  @PostMapping("/numbering/rules/{id}/next")
  public NumberIssuedView next(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) NumberIssueInput input) {
    return call(
        auth,
        RULE_CREATE,
        true,
        a -> {
          var rule =
              sql.fetchOne(selectRule() + " where r.tenant_id=? and r.id=?", a.tenantId(), id);
          if (rule == null) notFound();
          check("PRIMARY".equals(rule.get("rule_kind", String.class)), "只有主规则能直接取号；子规则只作为嵌套片段参与拼装");
          check("ACTIVE".equals(rule.get("status", String.class)), "规则已停用，不能取号");
          var date =
              input == null || input.documentDate() == null ? utcNow() : input.documentDate();
          var outcome =
              render(
                  a.tenantId(),
                  rule,
                  rule.get("entity_code", String.class),
                  zone(rule),
                  date,
                  true,
                  List.of());

          UUID issuedId = Ids.next();
          // issued_at 绑在第 11 位（共 14 位），刻意避开末位。
          try {
            sql.execute(
                "insert into document_number_issued(id,tenant_id,entity_code,rule_id,number_text,"
                    + " sequence_value,scope_key,reset_key,document_type,document_no,"
                    + " issued_by_membership_id,actor_name,issued_at)"
                    + " values(?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
                issuedId,
                a.tenantId(),
                rule.get("entity_code", String.class),
                id,
                outcome.text(),
                outcome.primarySequence(),
                outcome.primaryScope(),
                outcome.primaryReset(),
                input == null ? null : blankToNull(input.documentType()),
                input == null ? null : blankToNull(input.documentNo()),
                a.membershipId(),
                actorName(auth),
                utcNow());
          } catch (DataIntegrityViolationException e) {
            reject("编号 " + outcome.text() + " 已经发放过（同一实体下编号不能重复）；请检查是否有两条规则渲染出相同编号");
          }
          audit.record(
              a,
              auth,
              "identity.numbering_rule",
              issuedId,
              outcome.text(),
              "NumberIssued",
              null,
              Map.of(
                  "entityCode", rule.get("entity_code", String.class),
                  "number", outcome.text()));
          return new NumberIssuedView(
              issuedId,
              rule.get("entity_code", String.class),
              id,
              rule.get("rule_name", String.class),
              outcome.text(),
              BigDecimal.valueOf(outcome.primarySequence()),
              outcome.primaryReset(),
              input == null ? null : blankToNull(input.documentType()),
              input == null ? null : blankToNull(input.documentNo()),
              actorName(auth),
              utcNow());
        });
  }

  // ------------------------------------------------------------------
  // 渲染：预览与取号共用的唯一实现
  // ------------------------------------------------------------------

  /** 渲染结果：拼好的编号、逐段明细、每一处序号段的取号计划。 */
  private record Outcome(
      String text,
      List<NumberPreviewPartView> parts,
      List<NumberSequencePlanView> plans,
      Map<UUID, String> bySegment,
      long primarySequence,
      String primaryScope,
      String primaryReset) {}

  /**
   * 按段拼装编号，NESTED 段递归展开子规则。
   *
   * <p>计数器永远用**根规则**的 {@code entityCode}（{@code rootEntity}）， 所以同一个子规则嵌到两个不同实体下时各自独立计数——这正是「按实体重置」。
   *
   * <p>{@code consume=false} 时序号段只读不写：读出当前值 +1 就是要发的号， 桶还不存在就从 {@code sequence_start} 起算。{@code
   * consume=true} 时走原子 upsert。
   *
   * <p>{@code path} 是当前递归栈，用于挡环。写入时已经用 {@link #descendsFrom} 查过可达图，
   * 这里再兜一层是防御：数据可以被直接改库，渲染不该因此变成死循环。
   */
  private Outcome render(
      UUID tenantId,
      Record rule,
      String rootEntity,
      ZoneId rootZone,
      OffsetDateTime documentDate,
      boolean consume,
      List<UUID> path) {
    UUID ruleId = rule.get("id", UUID.class);
    check(!path.contains(ruleId), "编号规则存在嵌套环，无法生成编号");
    check(path.size() < MAX_DEPTH, "编号规则嵌套超过 " + MAX_DEPTH + " 层，无法生成编号");

    // 时区取**根规则**的：整棵嵌套树共用一个"今天"，否则父子规则可能渲染出两个日期。
    ZoneId zone = rootZone;
    LocalDate date = documentDate.atZoneSameInstant(zone).toLocalDate();

    StringBuilder text = new StringBuilder();
    var parts = new ArrayList<NumberPreviewPartView>();
    var plans = new ArrayList<NumberSequencePlanView>();
    var bySegment = new LinkedHashMap<UUID, String>();
    var nextPath = new ArrayList<>(path);
    nextPath.add(ruleId);

    for (Record segment : segments(tenantId, ruleId)) {
      String type = segment.get("segment_type", String.class);
      String rendered;
      switch (type) {
        case "PREFIX", "SUFFIX", "LITERAL" -> rendered = segment.get("literal_text", String.class);
        case "DATE" -> rendered = formatDate(date, segment.get("date_format", String.class));
        case "SEQUENCE" -> {
          var plan = sequence(tenantId, rootEntity, segment, ruleId, date, consume);
          rendered = pad(plan.value(), segment.get("sequence_width", Integer.class));
          plans.add(
              new NumberSequencePlanView(
                  segment.get("id", UUID.class),
                  "SEQUENCE",
                  rootEntity,
                  plan.scopeKey(),
                  plan.resetKey(),
                  segment.get("sequence_reset", String.class),
                  BigDecimal.valueOf(plan.value()),
                  rendered));
        }
        case "NESTED" -> {
          var child =
              sql.fetchOne(
                  selectRule() + " where r.tenant_id=? and r.id=?",
                  tenantId,
                  segment.get("child_rule_id", UUID.class));
          // 见上：引用的子规则不存在属 404。
          // 404 而不是 400：引用的子规则被删掉了，与「法人不存在」同一口径。
          if (child == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "嵌套的子规则不存在或已被删除");
          // 关键：**根实体从外层一路带下来**。子规则自己挂的 entity_code 只是它被复用时的
          // 标签，计数器永远算在**本次取号入口那条规则**的实体上——同一个子规则嵌到订单与
          // 采购两条规则下时，两个实体各有各的计数线，互不推进对方的序号。
          var childOutcome =
              render(tenantId, child, rootEntity, rootZone, documentDate, consume, nextPath);
          rendered = childOutcome.text();
          // 子规则的段明细**摊进**父规则的明细（各自带 depth），而不是额外加一行 NESTED：
          // 明细要回答"这个编号由哪几段拼成"，多一行 NESTED 会把同一段内容数两遍。
          // NESTED 段本身在规则详情里仍以 childRuleName + 展开文本呈现。
          parts.addAll(childOutcome.parts());
          plans.addAll(childOutcome.plans());
        }
        default -> throw new IllegalStateException("未知段类型 " + type);
      }
      text.append(rendered);
      bySegment.put(segment.get("id", UUID.class), rendered);
      // NESTED 段的明细已由子规则摊进来，不再单独补一行。
      if (!"NESTED".equals(type))
        parts.add(
            new NumberPreviewPartView(
                segment.get("id", UUID.class),
                type,
                ruleId,
                rule.get("rule_name", String.class),
                segment.get("position", Integer.class),
                path.size(),
                rendered));
    }

    // 已发放记录只需要"这个编号里那个主序号段的值"：取 plans 里第一条 SEQUENCE。
    // plans 按段的实际出现顺序收集（嵌套子规则的段也在其中），
    // 记录第一条即可——完整明细在 {@code parts} 里返回。
    long primarySequence = 0;
    String primaryScope = "";
    String primaryReset = "";
    if (!plans.isEmpty()) {
      var first = plans.get(0);
      primarySequence = first.nextValue().longValueExact();
      primaryScope = first.scopeKey();
      primaryReset = first.resetKey();
    }
    return new Outcome(
        text.toString(), parts, plans, bySegment, primarySequence, primaryScope, primaryReset);
  }

  /** 一个序号段的取号结果。 */
  private record Plan(long value, String scopeKey, String resetKey) {}

  /**
   * 取一个序号段的号。
   *
   * <p>消耗式取号走 {@code insert … on conflict … do update set current_value = current_value + 1
   * returning current_value}：递增与读回在同一条语句里完成，并发请求不会拿到同一个号。 不消耗式（预览）只读当前值，桶不存在时从 {@code
   * sequence_start} 起算。
   */
  private Plan sequence(
      UUID tenantId,
      String rootEntity,
      Record segment,
      UUID ruleId,
      LocalDate date,
      boolean consume) {
    String scope =
        "RULE".equals(segment.get("sequence_scope", String.class)) ? "RULE:" + ruleId : "ENTITY";
    String reset = resetKey(segment.get("sequence_reset", String.class), date);
    long start = segment.get("sequence_start", Long.class);

    if (!consume) {
      var row =
          sql.fetchOne(
              "select current_value from document_number_sequence"
                  + " where tenant_id=? and entity_code=? and scope_key=? and reset_key=?",
              tenantId,
              rootEntity,
              scope,
              reset);
      return new Plan(row == null ? start : row.get("current_value", Long.class) + 1, scope, reset);
    }
    var row =
        sql.fetchOne(
            "insert into document_number_sequence(id,tenant_id,entity_code,scope_key,reset_key,"
                + " current_value) values(?,?,?,?,?,?)"
                + " on conflict (tenant_id,entity_code,scope_key,reset_key)"
                + " do update set current_value=document_number_sequence.current_value+1"
                + " returning current_value",
            Ids.next(),
            tenantId,
            rootEntity,
            scope,
            reset,
            start);
    return new Plan(row.get("current_value", Long.class), scope, reset);
  }

  /**
   * 重置分桶键。换一天 / 一月 / 一年就是一个新桶，序号自然重新开始。
   *
   * <p>分桶用的是**业务日期**（请求里传的 documentDate）而不是 {@code now()}： 补录一张上周的订单时，它应当落进上周的桶里接着编号，而不是插到今天的序号中间。
   */
  private static String resetKey(String scope, LocalDate date) {
    return switch (scope) {
      case "DAILY" -> date.toString();
      case "MONTHLY" -> String.format("%04d-%02d", date.getYear(), date.getMonthValue());
      case "YEARLY" -> String.format("%04d", date.getYear());
      default -> "ALL";
    };
  }

  /** 序号左补零到指定宽度。宽度不足时原样返回（截断会丢信息，宁可长一点也不丢号）。 */
  private static String pad(long value, int width) {
    var raw = Long.toString(value);
    if (raw.length() >= width) return raw;
    return "0".repeat(width - raw.length()) + raw;
  }

  /**
   * 日期格式渲染，白名单与库里的 CHECK 一致。
   *
   * <p>未知格式报成配置问题（500），不静默给一个错误日期——编号里的日期错了比编号生成失败更难查。
   */
  private static String formatDate(LocalDate date, String format) {
    var formatter =
        switch (format) {
          case "yyyyMMdd" -> DateTimeFormatter.ofPattern("yyyyMMdd");
          case "yyyyMM" -> DateTimeFormatter.ofPattern("yyyyMM");
          case "yyyy" -> DateTimeFormatter.ofPattern("yyyy");
          case "yyMMdd" -> DateTimeFormatter.ofPattern("yyMMdd");
          case "MMdd" -> DateTimeFormatter.ofPattern("MMdd");
          case "yyyy-MM-dd" -> DateTimeFormatter.ofPattern("yyyy-MM-dd");
          default -> throw new IllegalStateException("未实现的日期格式：" + format);
        };
    return date.format(formatter);
  }

  // ------------------------------------------------------------------
  // 规则冲突：同一实体 + 生效区间交叠
  // ------------------------------------------------------------------

  /**
   * 两个生效区间是否交叠。
   *
   * <p>半开区间语义：{@code effective_to = NULL} 表示长期有效，当作无穷远。 相邻（{@code a.to ==
   * b.from}）不算交叠——前一条到点失效、后一条接上生效是正常配置。
   */
  private static boolean overlaps(
      OffsetDateTime aFrom, OffsetDateTime aTo, OffsetDateTime bFrom, OffsetDateTime bTo) {
    return aFrom.isBefore(orFar(bTo)) && orFar(aTo).isAfter(bFrom);
  }

  private static OffsetDateTime orFar(OffsetDateTime value) {
    return value == null ? OffsetDateTime.of(9999, 12, 31, 0, 0, 0, 0, ZoneOffset.UTC) : value;
  }

  /**
   * 同实体生效区间交叠检查。命中即 409。
   *
   * <p>只查 {@code PRIMARY} + {@code ACTIVE}：子规则不参与判定（它不直接给实体发号）， 停用的规则也不参与（否则「停用一条又启用的规则」永远放行不了）。排除
   * {@code excludeId} 是为了更新自己时不把自己算成冲突。
   */
  private void overlaps(
      UUID tenantId, String entityCode, UUID excludeId, OffsetDateTime from, OffsetDateTime to) {
    var rows =
        sql.fetch(
            "select rule_name,effective_from,effective_to from document_number_rule"
                + " where tenant_id=? and entity_code=? and rule_kind='PRIMARY' and status='ACTIVE'"
                + (excludeId == null ? "" : " and id<>?"),
            excludeId == null
                ? new Object[] {tenantId, entityCode}
                : new Object[] {tenantId, entityCode, excludeId});
    for (Record row : rows)
      if (overlaps(
          from,
          to,
          row.get("effective_from", OffsetDateTime.class),
          row.get("effective_to", OffsetDateTime.class)))
        reject(
            "规则「"
                + row.get("rule_name", String.class)
                + "」在 "
                + row.get("effective_from", OffsetDateTime.class)
                + " ~ "
                + str(row.get("effective_to", OffsetDateTime.class))
                + " 已经覆盖了同一实体（"
                + entityCode
                + "）的这段生效区间；请调整生效时间，或先停用那条规则");
  }

  // ------------------------------------------------------------------
  // 嵌套环检测
  // ------------------------------------------------------------------

  /**
   * {@code from} 向下可达的规则里有没有 {@code target}。
   *
   * <p>用于写入时挡环：给规则挂一个子规则之前，先问"从这个子规则出发能不能绕回我自己"。 绕回来了就是环，渲染会无限递归。
   */
  private boolean descendsFrom(UUID tenantId, UUID from, UUID target) {
    return descendsFrom(tenantId, from, target, new HashSet<>(), 0);
  }

  private boolean descendsFrom(UUID tenantId, UUID from, UUID target, Set<UUID> seen, int depth) {
    if (from == null || !seen.add(from)) return false;
    if (from.equals(target)) return true;
    if (depth >= MAX_DEPTH) return false;
    for (Record row :
        sql.fetch(
            "select child_rule_id from document_number_rule_segment"
                + " where tenant_id=? and rule_id=? and child_rule_id is not null",
            tenantId,
            from)) {
      var child = row.get("child_rule_id", UUID.class);
      if (child.equals(target)) return true;
      if (descendsFrom(tenantId, child, target, seen, depth + 1)) return true;
    }
    return false;
  }

  // ------------------------------------------------------------------
  // 读写辅助
  // ------------------------------------------------------------------

  private static String selectRule() {
    return "select r.id,r.entity_code,e.name as entity_name,r.rule_name,r.rule_kind,r.status,"
        + " r.timezone,r.effective_from,r.effective_to,r.description,r.version,r.created_at,"
        + " r.updated_at,r.entity_code as root_entity_code"
        + " from document_number_rule r"
        + " join document_entity e on e.code=r.entity_code";
  }

  /** 规则的有序段，联查子规则名（改过名后配置页仍能读懂嵌的是什么）。 */
  private List<Record> segments(UUID tenantId, UUID ruleId) {
    return sql.fetch(
        "select s.id,s.position,s.segment_type,s.literal_text,s.date_format,s.sequence_width,"
            + " s.sequence_start,s.sequence_reset,s.sequence_scope,s.child_rule_id,"
            + " c.rule_name as child_rule_name"
            + " from document_number_rule_segment s"
            + " left join document_number_rule c"
            + " on c.tenant_id=s.tenant_id and c.id=s.child_rule_id"
            + " where s.tenant_id=? and s.rule_id=? order by s.position",
        tenantId,
        ruleId);
  }

  /** 规则视图。样例编号用「今天 + 当前计数器」渲染，不消耗序号。 */
  private NumberRuleView toRule(UUID tenantId, Record head, List<Record> segmentRows) {
    var rule = selectRuleRow(tenantId, head.get("id", UUID.class));
    // 样例渲染失败不该让整条规则读不出来：配置页要能看见这条规则有问题（段形状坏了、
    // 嵌套断了），而不是连规则本身都打不开。取号时 {@code render} 同样会再抛一次。
    var rendered = new LinkedHashMap<UUID, String>();
    String sample = "";
    try {
      var outcome =
          render(
              tenantId,
              rule,
              rule.get("entity_code", String.class),
              zone(rule),
              utcNow(),
              false,
              List.of());
      sample = outcome.text();
      // 用 bySegment 而不是 parts：parts 是摊平后的轨迹（子规则段已摊进来、不含 NESTED 行），
      // 规则详情要展示的是**本规则自有**每个段渲染出的文本，NESTED 段那一行也必须有值。
      rendered.putAll(outcome.bySegment());
    } catch (RuntimeException e) {
      sample = "";
    }
    return new NumberRuleView(
        head.get("id", UUID.class),
        head.get("entity_code", String.class),
        head.get("entity_name", String.class),
        head.get("rule_name", String.class),
        head.get("rule_kind", String.class),
        head.get("status", String.class),
        head.get("timezone", String.class),
        head.get("effective_from", OffsetDateTime.class),
        head.get("effective_to", OffsetDateTime.class),
        head.get("description", String.class),
        sample,
        head.get("version", Long.class),
        head.get("created_at", OffsetDateTime.class),
        head.get("updated_at", OffsetDateTime.class),
        segmentRows.stream().map(r -> toSegment(r, rendered)).toList());
  }

  private Record selectRuleRow(UUID tenantId, UUID ruleId) {
    return sql.fetchOne(selectRule() + " where r.tenant_id=? and r.id=?", tenantId, ruleId);
  }

  /**
   * 段视图。
   *
   * <p>{@code rendered} 来自同一次样例渲染的逐段明细：配置页靠它看清编号是怎么一段段拼出来的， 而不是只看到一个最终字符串。渲染失败时为
   * null（段确实存在但这次没算出来）。
   */
  private static NumberRuleSegmentView toSegment(Record r, Map<UUID, String> rendered) {
    return new NumberRuleSegmentView(
        r.get("id", UUID.class),
        r.get("position", Integer.class),
        r.get("segment_type", String.class),
        r.get("literal_text", String.class),
        r.get("date_format", String.class),
        r.get("sequence_width", Integer.class),
        // bigint → BigDecimal 不能直接 get(name, BigDecimal.class)：jOOQ 的类型转换会抛
        // IllegalArgumentException（被 ApiErrorHandler 报成 400 格式错误，与真实原因无关）。
        // 先读成 Long 再转，数值语义不变（序号是计数，不是金额）。
        r.get("sequence_start", Long.class) == null
            ? null
            : BigDecimal.valueOf(r.get("sequence_start", Long.class)),
        r.get("sequence_reset", String.class),
        r.get("sequence_scope", String.class),
        r.get("child_rule_id", UUID.class),
        r.get("child_rule_name", String.class),
        rendered.get(r.get("id", UUID.class)));
  }

  /** 预览/取号时按规则或实体定位规则：给 id 用它，给实体取该实体生效中的主规则。 */
  private Record resolveRule(UUID tenantId, UUID ruleId, String entityCode) {
    if (ruleId != null) {
      var row = sql.fetchOne(selectRule() + " where r.tenant_id=? and r.id=?", tenantId, ruleId);
      if (row == null) notFound();
      return row;
    }
    check(entityCode != null && !entityCode.isBlank(), "请指定规则 id 或实体编码");
    var row =
        sql.fetchOne(
            selectRule()
                + " where r.tenant_id=? and r.entity_code=? and r.rule_kind='PRIMARY'"
                + " and r.status='ACTIVE' and r.effective_from<=now()"
                + " and (r.effective_to is null or r.effective_to>now())"
                + " order by r.effective_from desc limit 1",
            tenantId,
            entityCode.trim());
    if (row == null)
      throw IdentityController.error(HttpStatus.NOT_FOUND, "实体 " + entityCode + " 当前没有生效中的主规则");
    return row;
  }

  /** 写段：整体替换。先删后插，{@code position} 是唯一键，diff 的中间态无法落库。 */
  private void writeSegments(UUID tenantId, UUID ruleId, List<NumberRuleSegmentInput> segments) {
    // 先在应用层校验一遍形状与嵌套，报错比库 CHECK 抛出来的可读。
    for (NumberRuleSegmentInput segment : segments) validateSegment(tenantId, ruleId, segment);
    sql.execute(
        "delete from document_number_rule_segment where tenant_id=? and rule_id=?",
        tenantId,
        ruleId);
    for (int i = 0; i < segments.size(); i++) {
      var s = segments.get(i);
      sql.execute(
          "insert into document_number_rule_segment(id,tenant_id,rule_id,position,segment_type,"
              + " literal_text,date_format,sequence_width,sequence_start,sequence_reset,"
              + " sequence_scope,child_rule_id) values(?,?,?,?,?,?,?,?,?,?,?,?)",
          Ids.next(),
          tenantId,
          ruleId,
          i,
          s.segmentType(),
          blankToNull(s.literalText()),
          blankToNull(s.dateFormat()),
          s.sequenceWidth(),
          s.sequenceStart(),
          blankToNull(s.sequenceReset()),
          blankToNull(s.sequenceScope()),
          s.childRuleId());
    }
  }

  /**
   * 段形状校验，与库里 {@code document_number_rule_segment_shape} 的 CHECK 同口径。
   *
   * <p>顺带挡两件数据库挡不住的事：嵌套环、时区非法。
   */
  private void validateSegment(UUID tenantId, UUID ruleId, NumberRuleSegmentInput s) {
    switch (s.segmentType()) {
      case "PREFIX", "SUFFIX", "LITERAL" ->
          check(
              s.literalText() != null && !s.literalText().isBlank(),
              s.segmentType() + " 段必须填写文本内容");
      case "DATE" -> {
        check(s.dateFormat() != null && !s.dateFormat().isBlank(), "DATE 段必须选择日期格式");
        check(
            List.of("yyyyMMdd", "yyyyMM", "yyyy", "yyMMdd", "MMdd", "yyyy-MM-dd")
                .contains(s.dateFormat()),
            "不支持的日期格式：" + s.dateFormat());
      }
      case "SEQUENCE" -> {
        check(s.sequenceWidth() != null, "SEQUENCE 段必须设置序号宽度");
        check(s.sequenceWidth() >= 1 && s.sequenceWidth() <= 12, "序号宽度必须在 1 到 12 之间");
        check(s.sequenceStart() != null && s.sequenceStart() >= 0, "SEQUENCE 段必须设置起始序号");
        check(s.sequenceReset() != null, "SEQUENCE 段必须选择重置口径");
        check(s.sequenceScope() != null, "SEQUENCE 段必须选择计数作用域");
      }
      case "NESTED" -> {
        check(s.childRuleId() != null, "NESTED 段必须选择要嵌套的子规则");
        check(!s.childRuleId().equals(ruleId), "规则不能嵌套自己");
        var child =
            sql.fetchOne(
                "select rule_kind from document_number_rule where tenant_id=? and id=?",
                tenantId,
                s.childRuleId());
        // 跨租户的 id 与不存在的 id 在这里不可区分，返回 404 而不是 403（不泄露"存在"）。
        if (child == null)
          throw IdentityController.error(HttpStatus.NOT_FOUND, "要嵌套的子规则不存在或不属于当前租户");
        check(
            "COMPONENT".equals(child.get("rule_kind", String.class)),
            "只能嵌套子规则（COMPONENT），不能嵌套另一条主规则");
        check(!descendsFrom(tenantId, s.childRuleId(), ruleId), "嵌套会形成环：这条子规则已经能绕回当前规则");
      }
      default -> throw new IllegalStateException("未知段类型 " + s.segmentType());
    }
  }

  /** 时区解析。非法时区报成配置问题，不静默退回 UTC。 */
  private static ZoneId zone(Record rule) {
    var id = rule.get("timezone", String.class);
    try {
      return ZoneId.of(id);
    } catch (RuntimeException e) {
      throw new IllegalStateException("编号规则时区不合法：" + id, e);
    }
  }

  /** 输入规范化：时区与生效时间的默认值在这里定，避免每个出口各写一份。 */
  private record Normalized(
      String timezone, OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo) {}

  private Normalized normalize(NumberRuleInput input) {
    var timezone =
        input.timezone() == null || input.timezone().isBlank() ? "UTC" : input.timezone().trim();
    check(zoneOf(timezone), "时区不合法：" + timezone);
    // 调用方没给生效起点时，默认值是「此刻」—— 而这个值要与 DB 侧的
    // `r.effective_from<=now()`（规则是否生效）比在同一口径上比较，
    // 所以必须取**数据库自己的时钟**，不能取应用服务器墙钟：
    // 写入侧用墙钟时，两端分属两个钟域，新建规则可能「刚建好却还没生效」
    // （见 AuditLog.dbNow 的注释）。调用方明确给了时刻的照旧，那是业务契约。
    var from = input.effectiveFrom() == null ? AuditLog.dbNow(sql) : input.effectiveFrom();
    return new Normalized(timezone, from, input.effectiveTo());
  }

  private static boolean zoneOf(String id) {
    try {
      ZoneId.of(id);
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private Record entity(String code) {
    var row = sql.fetchOne("select code from document_entity where code=?", code.trim());
    if (row == null)
      throw IdentityController.error(HttpStatus.BAD_REQUEST, "单据实体 " + code + " 不存在");
    return row;
  }

  private static String statusOf(String status) {
    return status == null || status.isBlank() ? "ACTIVE" : status;
  }

  private static String note(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static void notFound() {
    throw IdentityController.error(HttpStatus.NOT_FOUND, "编号规则不存在或不属于当前租户");
  }

  private String actorName(String auth) {
    var row = sql.fetchOne("select session_actor_name(?) as name", hash(auth));
    return row == null ? null : row.get("name", String.class);
  }

  private Map<String, Object> ruleSnapshot(UUID tenantId, UUID id) {
    var row =
        sql.fetchOne("select * from document_number_rule where tenant_id=? and id=?", tenantId, id);
    if (row == null) return null;
    var snap = new LinkedHashMap<String, Object>();
    snap.put("entityCode", row.get("entity_code", String.class));
    snap.put("ruleName", row.get("rule_name", String.class));
    snap.put("ruleKind", row.get("rule_kind", String.class));
    snap.put("status", row.get("status", String.class));
    snap.put("timezone", row.get("timezone", String.class));
    snap.put("effectiveFrom", str(row.get("effective_from", OffsetDateTime.class)));
    snap.put("effectiveTo", str(row.get("effective_to", OffsetDateTime.class)));
    snap.put("description", row.get("description", String.class));
    var segments = new ArrayList<Map<String, Object>>();
    for (Record s : segments(tenantId, id)) {
      // 刻意用 LinkedHashMap 而不是 Map.of：非文本段的 literal_text / date_format 是 NULL，
      // 而 Map.of 不接受 null 值（会直接抛 NPE），等于是"复制一条规则"就必然失败。
      var segment = new LinkedHashMap<String, Object>();
      segment.put("position", s.get("position", Integer.class));
      segment.put("segmentType", s.get("segment_type", String.class));
      segment.put("literalText", s.get("literal_text", String.class));
      segment.put("dateFormat", s.get("date_format", String.class));
      segment.put("sequenceWidth", s.get("sequence_width", Integer.class));
      segment.put("sequenceReset", s.get("sequence_reset", String.class));
      segment.put("childRuleId", str(s.get("child_rule_id", UUID.class)));
      segments.add(segment);
    }
    snap.put("segments", segments);
    return snap;
  }

  /** WHERE 子句与绑定值成对累积，避免条件与参数顺序错位（与 AuditController.Condition 同一做法）。 */
  private static final class Condition {
    private final List<String> clauses = new ArrayList<>();
    private final List<Object> bindings = new ArrayList<>();

    Condition(String first) {
      clauses.add(first);
    }

    void add(String clause) {
      clauses.add(clause);
    }

    void bind(Object value) {
      bindings.add(value);
    }

    String sql() {
      return String.join(" ", clauses);
    }

    Object[] bindings() {
      return bindings.toArray();
    }
  }
}
