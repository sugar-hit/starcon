package com.starcon.identity;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.PatternSyntaxException;
import org.jooq.DSLContext;
import org.jooq.Record;
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
 * 17.5.1.1 业务参数。
 *
 * <p>蓝图对本模块点名的两条硬要求，在这里各有明确落点：
 *
 * <ul>
 *   <li><b>参数必须有默认值与生效范围</b>：默认值在 {@code business_parameter_definition} （随代码发布的出厂码表），生效范围在 {@code
 *       business_parameter_override} 的三档 GLOBAL / LEGAL_ENTITY / SHOP。{@code
 *       /business-parameters/effective} 按 <b>店铺 &gt; 法人 &gt; 全局 &gt; 出厂默认</b> 的优先级解析出唯一生效值，
 *       并把「这个值从哪一档来的」一并返回——只给一个值不给来源，排查时无法判断它 是继承来的还是配出来的。
 *   <li><b>改参数不改历史单据已冻结的行为</b>：单据创建时调 {@code /business-parameters/freezes} 把当时的生效值整份拷进冻结快照；之后怎么改
 *       override，这张单据读到的永远是 {@code freeze_value} 里那一份。一个单据只冻结一次，重复冻结返回 409 而不是覆盖，
 *       否则「这张单据当时按什么参数建的」又会变成一个没有唯一答案的问题。
 * </ul>
 *
 * <p>店铺维度只存逻辑 ID + 写入时快照的店铺标签，不建跨库外键：{@code shop} 属于 channel 服务的库，AGENTS.md 禁止跨服务数据库访问（与 channel 侧
 * {@code shop.legal_entity_id} 存逻辑 ID 是同一套边界做法）。
 *
 * <p>校验规则（枚举取值、正则、数值范围）读自码表而不是写死在 Java：新增一类参数时， 口径跟着数据一起进版本库，代码不需要新增分支。
 *
 * <p>独立成 {@code BusinessParameterController} 而不并进 {@code AdministrationController}： 17.5.1.1 是独立 L4
 * 单元，同文件各自生长会让两侧改动在合并时互相打架。
 */
@RestController
@RequestMapping("/identity")
public class BusinessParameterController {
  private final DSLContext sql;
  private final IdentityController identity;
  private final TenantTransactions tenants;
  private final AuditLog audit;

  public BusinessParameterController(
      DSLContext sql, IdentityController identity, PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.audit = new AuditLog(sql, identity);
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  // ------------------------------------------------------------------
  // 出厂码表（参数定义）
  // ------------------------------------------------------------------

  /**
   * 参数定义 + 出厂默认值。
   *
   * <p>{@code allowedValueCodes} 是显式字段而不是 {@code Map}：多选参数（售后必退情形） 在契约里必须保持 {@code string[]} 类型，退化成
   * {@code {}} 会让前端完全无法渲染。
   */
  public record BusinessParameterDefinitionView(
      String code,
      String name,
      String category,
      String valueType,
      String defaultValue,
      @Schema(nullable = true) BigDecimal defaultValueNumeric,
      List<String> allowedValueCodes,
      String validationPattern,
      String unit,
      String description,
      String status) {}

  /**
   * 参数生效值。
   *
   * <p>{@code scopeType}/{@code sourceScope} 回答「这个值配在哪一档」， {@code definitionDefault*}
   * 是同一参数未被任何档覆盖时的出厂值，两者并列给出， 这样继承关系在接口层就是可读的，不必再发一次请求去猜。
   */
  public record BusinessParameterEffectiveView(
      String parameterCode,
      String parameterName,
      String category,
      String valueType,
      String unit,
      String value,
      @Schema(nullable = true) BigDecimal valueNumeric,
      List<String> valueCodes,
      String scopeType,
      String sourceScope,
      @Schema(nullable = true) UUID sourceScopeId,
      @Schema(nullable = true) UUID overrideId,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      boolean inherited,
      String definitionDefault,
      @Schema(nullable = true) BigDecimal definitionDefaultNumeric,
      List<String> definitionDefaultCodes) {}

  /**
   * 生效范围覆盖值。
   *
   * <p>{@code legalEntityCode}/{@code legalEntityName} 由联查带出，法人改名后列表仍可读； 店铺标签则是写入时快照（跨服务，拿不到
   * channel 的实时名）。
   */
  public record BusinessParameterOverrideView(
      UUID id,
      String parameterCode,
      String parameterName,
      String category,
      String valueType,
      String unit,
      String scopeType,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) String legalEntityCode,
      @Schema(nullable = true) String legalEntityName,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) String shopLabel,
      String value,
      @Schema(nullable = true) BigDecimal valueNumeric,
      List<String> valueCodes,
      OffsetDateTime effectiveFrom,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 变更历史一行。
   *
   * <p>{@code beforeValue}/{@code afterValue} 只记本行所属 scope 的值：变更的是店铺档就只记
   * 店铺档，不会把全局值一起带进来——混在一起就答不出"改的到底是哪一档"。
   */
  public record BusinessParameterHistoryView(
      UUID id,
      String parameterCode,
      String parameterName,
      String scopeType,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) String legalEntityCode,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) String shopLabel,
      String action,
      @Schema(nullable = true) String beforeValue,
      @Schema(nullable = true) BigDecimal beforeValueNumeric,
      List<String> beforeValueCodes,
      @Schema(nullable = true) String afterValue,
      @Schema(nullable = true) BigDecimal afterValueNumeric,
      List<String> afterValueCodes,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      String note,
      @Schema(nullable = true) String actorName,
      OffsetDateTime changedAt) {}

  /** 覆盖值输入。生效范围三档靠 {@code scopeType} 与两个 id 的搭配表达，与库里的 CHECK 同口径。 */
  public record BusinessParameterOverrideInput(
      @NotBlank String parameterCode,
      @NotNull @Pattern(regexp = "GLOBAL|LEGAL_ENTITY|SHOP") String scopeType,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) @Size(max = 150) String shopLabel,
      @NotNull String value,
      @Size(max = 300) String note) {}

  /** 冻结输入：一张单据创建时调一次，把当时解析出的生效值固化下来。 */
  public record BusinessParameterFreezeInput(
      @NotBlank
          @Pattern(
              regexp = "[A-Z][A-Z0-9_]{1,31}",
              message = "单据类型需为大写字母开头的标识符，如 ORDER / AFTERSALE")
          String documentType,
      @NotBlank @Size(max = 64) String documentNo,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) @Size(max = 150) String shopLabel,
      @Size(max = 300) String note) {}

  public record BusinessParameterFreezeValueView(
      String parameterCode,
      String parameterName,
      String value,
      @Schema(nullable = true) BigDecimal valueNumeric,
      List<String> valueCodes,
      String sourceScope,
      @Schema(nullable = true) UUID sourceScopeId) {}

  public record BusinessParameterFreezeView(
      UUID id,
      String documentType,
      String documentNo,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) String shopLabel,
      OffsetDateTime frozenAt,
      String note,
      List<BusinessParameterFreezeValueView> values) {}

  private static final String PARAMETER_READ = "identity.business_parameter.read";
  private static final String PARAMETER_CREATE = "identity.business_parameter.create";
  private static final String PARAMETER_UPDATE = "identity.business_parameter.update";
  private static final String PARAMETER_DELETE = "identity.business_parameter.delete";

  /**
   * 与 {@code AccountingBookController.call} 同口径：解析会话 → 切到该会话的租户事务 → 锁会话 → 二次校验租户未切换 → 校验权限码 →
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
          if (mutation) require(a.owner(), "业务参数变更需由当前租户所有者执行");
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

  /** 出厂参数目录（含默认值与合法取值），供配置页渲染表单用。全租户共用一份码表，不带租户上下文。 */
  @GetMapping("/business-parameters")
  public List<BusinessParameterDefinitionView> definitions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        PARAMETER_READ,
        false,
        a ->
            sql
                .fetch(
                    "select code,name,category,value_type,default_value,default_value_numeric,"
                        + " allowed_values,validation_pattern,unit,description,status"
                        + " from business_parameter_definition order by sort_order,code")
                .stream()
                .map(BusinessParameterController::toDefinition)
                .toList());
  }

  /**
   * 覆盖值列表，可按参数与范围档过滤。
   *
   * <p>不加过滤时返回本租户全部覆盖值：配置页常态就是「全局配了什么、每个法人又覆盖了什么」。
   */
  @GetMapping("/business-parameters/overrides")
  public List<BusinessParameterOverrideView> overrides(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String parameterCode,
      @RequestParam(required = false) String scopeType) {
    return call(
        auth,
        PARAMETER_READ,
        false,
        a -> {
          var condition = new Condition(" where o.tenant_id=?");
          condition.bind(a.tenantId());
          if (parameterCode != null && !parameterCode.isBlank()) {
            condition.add(" and o.parameter_code=?");
            condition.bind(parameterCode.trim());
          }
          if (scopeType != null && !scopeType.isBlank()) {
            check(
                Arrays.asList("GLOBAL", "LEGAL_ENTITY", "SHOP").contains(scopeType),
                "生效范围只能是 GLOBAL、LEGAL_ENTITY 或 SHOP");
            condition.add(" and o.scope_type=?");
            condition.bind(scopeType);
          }
          return sql
              .fetch(
                  selectOverrides()
                      + condition.sql()
                      + " order by d.sort_order,o.scope_type,e.code nulls last,o.shop_label nulls last",
                  condition.bindings())
              .stream()
              .map(BusinessParameterController::toOverride)
              .toList();
        });
  }

  /**
   * 参数生效值：在给定（法人 / 店铺）上下文下解析出的唯一值。
   *
   * <p>优先级 <b>店铺 &gt; 法人 &gt; 全局 &gt; 出厂默认</b>。法人或店铺不传时对应档自然不参与 匹配（比较 NULL 结果为
   * NULL，不进入候选集），不需要调用方先判断"要不要传"。
   *
   * <p>法定代表人存在性必须查：法人维度是 identity 自己拥有的表， 传一个本租户不存在的法人 id 会静默退回全局值，那是最难排查的一类错配。
   */
  @GetMapping("/business-parameters/effective")
  public List<BusinessParameterEffectiveView> effective(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) UUID shopId) {
    return call(
        auth,
        PARAMETER_READ,
        false,
        a -> {
          if (legalEntityId != null) legalEntity(a.tenantId(), legalEntityId);
          return sql.fetch(RESOLVE_SQL, a.tenantId(), legalEntityId, shopId).stream()
              .map(BusinessParameterController::toEffective)
              .toList();
        });
  }

  /**
   * 变更历史（只追加、不可改）。
   *
   * <p>可按参数与范围档过滤；按时间倒序，最新的变更在最前面。
   */
  @GetMapping("/business-parameters/history")
  public List<BusinessParameterHistoryView> history(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String parameterCode,
      @RequestParam(required = false) String scopeType) {
    return call(
        auth,
        PARAMETER_READ,
        false,
        a -> {
          var condition = new Condition(" where h.tenant_id=?");
          condition.bind(a.tenantId());
          if (parameterCode != null && !parameterCode.isBlank()) {
            condition.add(" and h.parameter_code=?");
            condition.bind(parameterCode.trim());
          }
          if (scopeType != null && !scopeType.isBlank()) {
            check(
                Arrays.asList("GLOBAL", "LEGAL_ENTITY", "SHOP").contains(scopeType),
                "生效范围只能是 GLOBAL、LEGAL_ENTITY 或 SHOP");
            condition.add(" and h.scope_type=?");
            condition.bind(scopeType);
          }
          return sql
              .fetch(
                  "select h.id,h.parameter_code,d.name as parameter_name,h.scope_type,"
                      + " h.legal_entity_id,e.code as legal_entity_code,h.shop_id,h.shop_label,"
                      + " h.action,h.before_value,h.before_value_numeric,h.after_value,"
                      + " h.after_value_numeric,h.effective_from,h.note,h.actor_name,h.changed_at"
                      + " from business_parameter_history h"
                      + " join business_parameter_definition d on d.code=h.parameter_code"
                      + " left join legal_entity e on e.tenant_id=h.tenant_id"
                      + " and e.id=h.legal_entity_id"
                      + condition.sql()
                      + " order by h.changed_at desc,h.id desc",
                  condition.bindings())
              .stream()
              .map(BusinessParameterController::toHistory)
              .toList();
        });
  }

  // ------------------------------------------------------------------
  // 写出口
  // ------------------------------------------------------------------

  @PostMapping("/business-parameters/overrides")
  public UUID create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BusinessParameterOverrideInput input) {
    return call(
        auth,
        PARAMETER_CREATE,
        true,
        a -> {
          var definition = definition(input.parameterCode());
          Scope scope =
              scope(
                  a.tenantId(),
                  input.scopeType(),
                  input.legalEntityId(),
                  input.shopId(),
                  input.shopLabel());
          var value = validated(definition, input.value());
          UUID id = Ids.next();
          try {
            // cast 刻意避开 values 末位：jOOQ 会把末位的 ? 按整体错位一格解析。
            sql.execute(
                "insert into business_parameter_override(id,tenant_id,parameter_code,scope_type,"
                    + " legal_entity_id,shop_id,shop_label,value_text,value_numeric,effective_from,"
                    + " note,created_by_membership_id)"
                    + " values(?,?,?,?,?,?,?,?,?,?::timestamptz,?,?)",
                id,
                a.tenantId(),
                definition.get("code", String.class),
                scope.type(),
                scope.legalEntityId(),
                scope.shopId(),
                scope.shopLabel(),
                value.text(),
                value.numeric(),
                utcNow(),
                note(input.note()),
                a.membershipId());
          } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw duplicate(definition.get("code", String.class), scope);
          }
          history(
              a,
              auth,
              definition.get("code", String.class),
              scope,
              "CREATED",
              null,
              value,
              input.note());
          audit.record(
              a,
              auth,
              "identity.business_parameter",
              id,
              definition.get("name", String.class) + " · " + scope.describe(),
              "BusinessParameterOverrideCreated",
              null,
              overrideSnapshot(id));
          return id;
        });
  }

  /**
   * 更新覆盖值。
   *
   * <p>生效范围（{@code scopeType} 与两个 id）不允许改：要改范围等于换一条记录， 否则变更历史里「从哪一档挪到哪一档」会分不清是改值还是搬家。范围不符返回 409。
   */
  @PutMapping("/business-parameters/overrides/{id}")
  public void update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody BusinessParameterOverrideInput input) {
    call(
        auth,
        PARAMETER_UPDATE,
        true,
        a -> {
          var row =
              sql.fetchOne(
                  "select * from business_parameter_override where tenant_id=? and id=?",
                  a.tenantId(),
                  id);
          // 404 而不是 400：跨租户拿到的 id 与真正不存在的 id 在这里不可区分，
          // 返回 403 会泄露"这个 id 存在"。
          if (row == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "参数覆盖不存在或不属于当前租户");
          var definition = definition(input.parameterCode());
          check(
              definition.get("code", String.class).equals(row.get("parameter_code", String.class)),
              "不能把覆盖值改成另一个参数，请删除后新建");
          Scope existing =
              new Scope(
                  row.get("scope_type", String.class),
                  row.get("legal_entity_id", UUID.class),
                  row.get("shop_id", UUID.class),
                  row.get("shop_label", String.class));
          Scope requested =
              scope(
                  a.tenantId(),
                  input.scopeType(),
                  input.legalEntityId(),
                  input.shopId(),
                  input.shopLabel());
          check(
              existing.sameTarget(requested),
              "生效范围不能在此修改（当前为 " + existing.describe() + "）；如需换档请删除后新建");
          var value = validated(definition, input.value());
          var before = rowSnapshot(row);
          // value_numeric 的 cast 不在末位；末位留给 tenant_id/id 这类裸 ?。
          sql.execute(
              "update business_parameter_override set value_text=?,value_numeric=?,"
                  + " effective_from=?::timestamptz,note=?,shop_label=?,version=version+1,"
                  + " updated_at=now() where tenant_id=? and id=?",
              value.text(),
              value.numeric(),
              utcNow(),
              note(input.note()),
              existing.shopId() == null ? null : requested.shopLabel(),
              a.tenantId(),
              id);
          history(
              a,
              auth,
              definition.get("code", String.class),
              existing,
              "UPDATED",
              snapshotOf(row),
              value,
              input.note());
          audit.record(
              a,
              auth,
              "identity.business_parameter",
              id,
              definition.get("name", String.class) + " · " + existing.describe(),
              "BusinessParameterOverrideUpdated",
              before,
              overrideSnapshot(id));
          return null;
        });
  }

  /** 删除覆盖值 = 退回继承（该档不再覆盖，解析时落到更低一档或出厂默认）。变更历史保留 DELETE 行。 */
  @DeleteMapping("/business-parameters/overrides/{id}")
  public void delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        PARAMETER_DELETE,
        true,
        a -> {
          var row =
              sql.fetchOne(
                  "select * from business_parameter_override where tenant_id=? and id=?",
                  a.tenantId(),
                  id);
          // 404 而不是 400：跨租户拿到的 id 与真正不存在的 id 在这里不可区分，
          // 返回 403 会泄露"这个 id 存在"。
          if (row == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "参数覆盖不存在或不属于当前租户");
          var definition = definition(row.get("parameter_code", String.class));
          Scope scope =
              new Scope(
                  row.get("scope_type", String.class),
                  row.get("legal_entity_id", UUID.class),
                  row.get("shop_id", UUID.class),
                  row.get("shop_label", String.class));
          int deleted =
              sql.execute(
                  "delete from business_parameter_override where tenant_id=? and id=?",
                  a.tenantId(),
                  id);
          check(deleted == 1, "参数覆盖不存在或不属于当前租户");
          history(
              a,
              auth,
              definition.get("code", String.class),
              scope,
              "DELETED",
              snapshotOf(row),
              null,
              "退回继承");
          audit.record(
              a,
              auth,
              "identity.business_parameter",
              id,
              definition.get("name", String.class) + " · " + scope.describe(),
              "BusinessParameterOverrideDeleted",
              rowSnapshot(row),
              null);
          return null;
        });
  }

  /**
   * 冻结一张单据的参数。
   *
   * <p>单据创建时调一次：按当前（法人 / 店铺）上下文把解析出的生效值整份拷进快照。 之后 9 审单、11 售后、7 库存都读这份快照，而不是实时解析 —— 这就是
   * 「改参数不改历史单据已冻结的行为」的物理保证。
   *
   * <p>重复冻结返回 409：一张单据的参数只应冻结一次；覆盖会让「当时按什么参数建的」 再次失去唯一答案。
   */
  @PostMapping("/business-parameters/freezes")
  public UUID freeze(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BusinessParameterFreezeInput input) {
    return call(
        auth,
        PARAMETER_CREATE,
        true,
        a -> {
          if (input.legalEntityId() != null) legalEntity(a.tenantId(), input.legalEntityId());
          var frozen =
              sql.fetchOne(
                  "select frozen_at from business_parameter_freeze where tenant_id=? and document_type=?"
                      + " and document_no=?",
                  a.tenantId(),
                  input.documentType().trim(),
                  input.documentNo().trim());
          if (frozen != null)
            reject(
                "单据 "
                    + input.documentType()
                    + " "
                    + input.documentNo()
                    + " 已冻结过参数（冻结于 "
                    + frozen.get("frozen_at", OffsetDateTime.class)
                    + "）；单据参数只冻结一次，如需更正请由业务单据侧作废重开");

          // 解析与 /effective 同一段 SQL 与同一套优先级，不另写一份"冻结专用"的解析逻辑。
          var resolved =
              sql.fetch(RESOLVE_SQL, a.tenantId(), input.legalEntityId(), input.shopId());
          check(!resolved.isEmpty(), "没有任何可用的参数定义，业务参数码表异常");

          UUID freezeId = Ids.next();
          sql.execute(
              "insert into business_parameter_freeze(id,tenant_id,document_type,document_no,"
                  + " legal_entity_id,shop_id,shop_label,frozen_at,frozen_by_membership_id,note)"
                  + " values(?,?,?,?,?,?,?,?::timestamptz,?,?)",
              freezeId,
              a.tenantId(),
              input.documentType().trim(),
              input.documentNo().trim(),
              input.legalEntityId(),
              input.shopId(),
              blankToNull(input.shopLabel()),
              utcNow(),
              a.membershipId(),
              note(input.note()));
          for (Record r : resolved) {
            // cast 在第 8 位（共 10 位），刻意避开末位。
            sql.execute(
                "insert into business_parameter_freeze_value(id,tenant_id,freeze_id,parameter_code,"
                    + " value_text,value_numeric,source_scope,source_scope_id)"
                    + " values(?,?,?,?,?,?,?,?)",
                Ids.next(),
                a.tenantId(),
                freezeId,
                r.get("code", String.class),
                r.get("value", String.class),
                r.get("value_numeric", BigDecimal.class),
                r.get("source_scope", String.class),
                r.get("override_id", UUID.class));
          }
          audit.record(
              a,
              auth,
              "identity.business_parameter",
              freezeId,
              input.documentType().trim() + " " + input.documentNo().trim(),
              "BusinessParameterFrozen",
              null,
              freezeSnapshot(freezeId));
          return freezeId;
        });
  }

  /**
   * 读回单据冻结的参数。
   *
   * <p>这是「历史单据不受后续改参数影响」的读出口：参数改过之后，这里返回的仍然是 单据创建时那一份。
   */
  @GetMapping("/business-parameters/freezes/{documentType}/{documentNo}")
  public BusinessParameterFreezeView frozen(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String documentType,
      @PathVariable String documentNo) {
    return call(
        auth,
        PARAMETER_READ,
        false,
        a -> {
          var head =
              sql.fetchOne(
                  "select id,document_type,document_no,legal_entity_id,shop_id,shop_label,"
                      + " frozen_at,note from business_parameter_freeze"
                      + " where tenant_id=? and document_type=? and document_no=?",
                  a.tenantId(),
                  documentType,
                  documentNo);
          // 查不到就是没冻结过；返回 404 而不是空快照，调用方不该把「没冻结」当成「参数为空」。
          if (head == null)
            throw IdentityController.error(
                HttpStatus.NOT_FOUND, "单据 " + documentType + " " + documentNo + " 没有冻结的参数快照");
          var values =
              sql
                  .fetch(
                      "select v.parameter_code,d.name as parameter_name,v.value_text,v.value_numeric,"
                          + " v.source_scope,v.source_scope_id"
                          + " from business_parameter_freeze_value v"
                          + " join business_parameter_definition d on d.code=v.parameter_code"
                          + " where v.tenant_id=? and v.freeze_id=? order by d.sort_order,v.parameter_code",
                      a.tenantId(),
                      head.get("id", UUID.class))
                  .stream()
                  .map(
                      r ->
                          new BusinessParameterFreezeValueView(
                              r.get("parameter_code", String.class),
                              r.get("parameter_name", String.class),
                              r.get("value_text", String.class),
                              r.get("value_numeric", BigDecimal.class),
                              codes(r.get("value_text", String.class)),
                              r.get("source_scope", String.class),
                              r.get("source_scope_id", UUID.class)))
                  .toList();
          return new BusinessParameterFreezeView(
              head.get("id", UUID.class),
              head.get("document_type", String.class),
              head.get("document_no", String.class),
              head.get("legal_entity_id", UUID.class),
              head.get("shop_id", UUID.class),
              head.get("shop_label", String.class),
              head.get("frozen_at", OffsetDateTime.class),
              head.get("note", String.class),
              values);
        });
  }

  // ------------------------------------------------------------------
  // 解析 SQL
  // ------------------------------------------------------------------

  /**
   * 生效值解析：唯一一个同时服务「实时生效值」与「单据冻结」的地方。
   *
   * <p>租户 / 法人 / 店铺三个上下文绑定在 CTE 里各占一次，避免同一占位符在 lateral 子查询里 重复出现导致绑定顺序与视觉顺序不一致——那种 SQL 改一行就要重数三遍绑定。
   *
   * <p>{@code left join lateral} + {@code order by … limit 1} 就是优先级本身： 店铺 &gt; 法人 &gt;
   * 全局。上下文没传的档比较结果为 NULL，自然不进候选集。
   *
   * <p>没有命中任何覆盖档时 {@code o.*} 全为 NULL，落到码表出厂默认值（{@code source_scope='DEFAULT'}）。
   */
  private static final String RESOLVE_SQL =
      "with c as (select cast(? as uuid) as tenant_id, cast(? as uuid) as legal_entity_id,"
          + " cast(? as uuid) as shop_id)"
          + " select d.code,d.name,d.category,d.value_type,d.unit,d.default_value,"
          + " d.default_value_numeric,"
          + " o.id as override_id,o.scope_type,o.legal_entity_id,o.shop_id,"
          + " case when o.id is null then d.default_value else o.value_text end as value,"
          + " case when o.id is null then d.default_value_numeric else o.value_numeric end as value_numeric,"
          + " case when o.id is null then 'DEFAULT' else o.scope_type end as source_scope,"
          + " case when o.scope_type='SHOP' then o.shop_id"
          + "      when o.scope_type='LEGAL_ENTITY' then o.legal_entity_id end as source_scope_id,"
          + " o.effective_from"
          + " from business_parameter_definition d"
          + " left join lateral ("
          + "   select ov.* from business_parameter_override ov, c"
          + "   where ov.tenant_id=c.tenant_id and ov.parameter_code=d.code"
          + "     and ( ov.scope_type='GLOBAL'"
          + "       or (ov.scope_type='LEGAL_ENTITY' and ov.legal_entity_id=c.legal_entity_id)"
          + "       or (ov.scope_type='SHOP' and ov.shop_id=c.shop_id) )"
          + "   order by case ov.scope_type when 'SHOP' then 1 when 'LEGAL_ENTITY' then 2 else 3 end"
          + "   limit 1) o on true"
          + " where d.status='ACTIVE' order by d.sort_order,d.code";

  private static String selectOverrides() {
    return "select o.id,o.parameter_code,d.name as parameter_name,d.category,d.value_type,d.unit,"
        + " o.scope_type,o.legal_entity_id,e.code as legal_entity_code,e.legal_name as legal_entity_name,"
        + " o.shop_id,o.shop_label,o.value_text,o.value_numeric,o.effective_from,o.note,o.version,"
        + " o.created_at,o.updated_at"
        + " from business_parameter_override o"
        + " join business_parameter_definition d on d.code=o.parameter_code"
        + " left join legal_entity e on e.tenant_id=o.tenant_id and e.id=o.legal_entity_id";
  }

  private static BusinessParameterDefinitionView toDefinition(Record r) {
    return new BusinessParameterDefinitionView(
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("category", String.class),
        r.get("value_type", String.class),
        r.get("default_value", String.class),
        r.get("default_value_numeric", BigDecimal.class),
        codes(r.get("allowed_values", String.class)),
        r.get("validation_pattern", String.class),
        r.get("unit", String.class),
        r.get("description", String.class),
        r.get("status", String.class));
  }

  private static BusinessParameterOverrideView toOverride(Record r) {
    var value = r.get("value_text", String.class);
    return new BusinessParameterOverrideView(
        r.get("id", UUID.class),
        r.get("parameter_code", String.class),
        r.get("parameter_name", String.class),
        r.get("category", String.class),
        r.get("value_type", String.class),
        r.get("unit", String.class),
        r.get("scope_type", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("legal_entity_code", String.class),
        r.get("legal_entity_name", String.class),
        r.get("shop_id", UUID.class),
        r.get("shop_label", String.class),
        value,
        r.get("value_numeric", BigDecimal.class),
        codes(value),
        r.get("effective_from", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static BusinessParameterEffectiveView toEffective(Record r) {
    var value = r.get("value", String.class);
    var sourceScope = r.get("source_scope", String.class);
    return new BusinessParameterEffectiveView(
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("category", String.class),
        r.get("value_type", String.class),
        r.get("unit", String.class),
        value,
        r.get("value_numeric", BigDecimal.class),
        codes(value),
        // 生效范围 = 请求的上下文，不是命中的那一档：全局查询时 scopeType 反映调用方所处的范围。
        sourceScope.equals("DEFAULT") ? "GLOBAL" : sourceScope,
        sourceScope,
        r.get("source_scope_id", UUID.class),
        r.get("override_id", UUID.class),
        r.get("effective_from", OffsetDateTime.class),
        sourceScope.equals("DEFAULT"),
        r.get("default_value", String.class),
        r.get("default_value_numeric", BigDecimal.class),
        codes(r.get("default_value", String.class)));
  }

  private static BusinessParameterHistoryView toHistory(Record r) {
    var before = r.get("before_value", String.class);
    var after = r.get("after_value", String.class);
    return new BusinessParameterHistoryView(
        r.get("id", UUID.class),
        r.get("parameter_code", String.class),
        r.get("parameter_name", String.class),
        r.get("scope_type", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("legal_entity_code", String.class),
        r.get("shop_id", UUID.class),
        r.get("shop_label", String.class),
        r.get("action", String.class),
        before,
        r.get("before_value_numeric", BigDecimal.class),
        codes(before),
        after,
        r.get("after_value_numeric", BigDecimal.class),
        codes(after),
        r.get("effective_from", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("actor_name", String.class),
        r.get("changed_at", OffsetDateTime.class));
  }

  // ------------------------------------------------------------------
  // 校验
  // ------------------------------------------------------------------

  /** 生效范围：类型与目标对象必须一一对应，与库里 CHECK 同口径，错误在入口就报清楚。 */
  private record Scope(String type, UUID legalEntityId, UUID shopId, String shopLabel) {
    boolean sameTarget(Scope other) {
      return type.equals(other.type)
          && java.util.Objects.equals(legalEntityId, other.legalEntityId)
          && java.util.Objects.equals(shopId, other.shopId);
    }

    String describe() {
      return switch (type) {
        case "GLOBAL" -> "全租户";
        case "LEGAL_ENTITY" -> "法人 " + legalEntityId;
        default -> "店铺 " + (shopLabel == null ? shopId : shopLabel);
      };
    }
  }

  /**
   * 校验并规范化取值。
   *
   * <p>规则全部读自码表：ENUM 查 allowed_values、MULTI_ENUM 逐项查且查格式、 INTEGER 必须能解析成数值且匹配
   * validation_pattern、STRING 按 validation_pattern 匹配。 新增一类参数时只改码表，不动这段代码。
   *
   * <p>规范化结果同时给出 {@code text} 与 {@code numeric}：数值型参数文本与数值必须一致， 下游按哪一侧读都能得到同一个答案（禁浮点，一律 BigDecimal
   * / NUMERIC(19,6)）。
   */
  private record Value(String text, BigDecimal numeric) {}

  private Value validated(Record definition, String raw) {
    String code = definition.get("code", String.class);
    String type = definition.get("value_type", String.class);
    var allowed = codes(definition.get("allowed_values", String.class));
    String pattern = definition.get("validation_pattern", String.class);
    String value = raw == null ? "" : raw.trim();

    switch (type) {
      case "ENUM" -> {
        check(!allowed.isEmpty(), "参数 " + code + " 未配置合法取值");
        check(
            allowed.contains(value),
            "参数 " + code + " 的取值必须是 " + String.join(" / ", allowed) + " 之一，当前为「" + value + "」");
        return new Value(value, null);
      }
      case "MULTI_ENUM" -> {
        // 允许空串：售后必退情形可以是「当前不设必退情形」，这是合法配置而不是漏填。
        if (value.isEmpty()) return new Value("", null);
        matchPattern(code, pattern, value);
        var selected = codes(value);
        for (var item : selected)
          check(
              allowed.contains(item),
              "参数 " + code + " 的取值「" + item + "」不在合法范围内：" + String.join(" / ", allowed));
        return new Value(String.join(",", selected), null);
      }
      case "INTEGER" -> {
        BigDecimal numeric;
        try {
          numeric = new BigDecimal(value);
        } catch (NumberFormatException e) {
          throw IdentityController.error(
              HttpStatus.BAD_REQUEST, "参数 " + code + " 需要数值，当前为「" + value + "」");
        }
        check(numeric.signum() >= 0, "参数 " + code + " 不允许为负数，当前为 " + value);
        BigDecimal normalized = numeric.stripTrailingZeros();
        // 去掉小数点后可能退化成科学计数法（1000 → 1E+3），那不是能写进 text 列的规范形式。
        String canonical =
            normalized.scale() <= 0
                ? normalized.toBigInteger().toString()
                : normalized.toPlainString();
        matchPattern(code, pattern, canonical);
        return new Value(canonical, new BigDecimal(canonical));
      }
      default -> {
        check(!value.isEmpty(), "参数 " + code + " 的值不能为空");
        matchPattern(code, pattern, value);
        return new Value(value, null);
      }
    }
  }

  /** 码表里的正则坏了要报成配置问题，不能让它变成一个看不懂的 PatternSyntaxException 冒到 500。 */
  private static void matchPattern(String code, String pattern, String value) {
    if (pattern.isBlank()) return;
    try {
      check(
          java.util.regex.Pattern.compile(pattern).matcher(value).matches(),
          "参数 " + code + " 的值「" + value + "」不符合格式要求（" + pattern + "）");
    } catch (PatternSyntaxException e) {
      throw new IllegalStateException("业务参数码表 " + code + " 的校验正则不合法：" + pattern, e);
    }
  }

  /** 读参数定义；不存在或已下线时给出可读原因，而不是让下游拿到一个空值。 */
  private Record definition(String code) {
    var row = sql.fetchOne("select * from business_parameter_definition where code=?", code.trim());
    if (row == null)
      throw IdentityController.error(HttpStatus.BAD_REQUEST, "业务参数 " + code + " 不存在");
    check(row.get("status", String.class).equals("ACTIVE"), "业务参数 " + code + " 已下线，不能再配置");
    return row;
  }

  private Record legalEntity(UUID tenantId, UUID id) {
    var row = sql.fetchOne("select * from legal_entity where tenant_id=? and id=?", tenantId, id);
    if (row == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "法人不存在或不属于当前租户");
    return row;
  }

  private Scope scope(
      UUID tenantId, String type, UUID legalEntityId, UUID shopId, String shopLabel) {
    return switch (type) {
      case "GLOBAL" -> {
        check(legalEntityId == null && shopId == null, "全租户档不能指定法人或店铺，请改用按法人或按店铺档");
        yield new Scope("GLOBAL", null, null, null);
      }
      case "LEGAL_ENTITY" -> {
        check(legalEntityId != null, "按法人档必须指定法人");
        check(shopId == null, "按法人档不能同时指定店铺");
        legalEntity(tenantId, legalEntityId);
        yield new Scope("LEGAL_ENTITY", legalEntityId, null, null);
      }
      case "SHOP" -> {
        check(shopId != null, "按店铺档必须指定店铺");
        // 店铺在 channel 服务的库，identity 查不到它的可读名，所以标签由调用方带入并落库快照。
        check(shopLabel != null && !shopLabel.isBlank(), "按店铺档必须提供店铺名称（shopLabel），它会作为历史可读标签一起冻结");
        if (legalEntityId != null) legalEntity(tenantId, legalEntityId);
        yield new Scope("SHOP", legalEntityId, shopId, shopLabel.trim());
      }
      default ->
          throw IdentityController.error(
              HttpStatus.BAD_REQUEST, "生效范围只能是 GLOBAL、LEGAL_ENTITY 或 SHOP");
    };
  }

  private static String note(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** 逗号分隔的取值列表；空串返回空列表而不是含一个空串的列表。 */
  private static List<String> codes(String csv) {
    if (csv == null || csv.isBlank()) return List.of();
    return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  private RuntimeException duplicate(String parameterCode, Scope scope) {
    throw IdentityController.error(
        HttpStatus.CONFLICT,
        "参数 " + parameterCode + " 在「" + scope.describe() + "」这一档已经配置过，请直接编辑现有配置");
  }

  /** 从库行取变更前值。文本与数值同时留：数值型参数两侧必须能互相还原出同一个数。 */
  private static Snapshot snapshotOf(Record r) {
    return new Snapshot(
        r.get("value_text", String.class), r.get("value_numeric", BigDecimal.class));
  }

  private String actorName(String auth) {
    var row = sql.fetchOne("select session_actor_name(?) as name", hash(auth));
    return row == null ? null : row.get("name", String.class);
  }

  // ------------------------------------------------------------------
  // 快照与留痕
  // ------------------------------------------------------------------

  /** 变更前后值。text 与 numeric 同时留：数值型参数两侧必须能互相还原出同一个数。 */
  private record Snapshot(String text, BigDecimal numeric) {}

  private void history(
      IdentityController.Access a,
      String auth,
      String parameterCode,
      Scope scope,
      String action,
      Snapshot before,
      Value after,
      String note) {
    history(
        a,
        auth,
        parameterCode,
        scope,
        action,
        before == null ? null : before.text(),
        before == null ? null : before.numeric(),
        after == null ? null : after.text(),
        after == null ? null : after.numeric(),
        note);
  }

  private void history(
      IdentityController.Access a,
      String auth,
      String parameterCode,
      Scope scope,
      String action,
      String beforeText,
      BigDecimal beforeNumeric,
      String afterText,
      BigDecimal afterNumeric,
      String note) {
    // changed_at 刻意绑在第 9 位（共 10 位），不在 values 末位：末位的 cast 会让 jOOQ 整体错位一格。
    sql.execute(
        "insert into business_parameter_history(id,tenant_id,parameter_code,scope_type,"
            + " legal_entity_id,shop_id,shop_label,action,before_value,before_value_numeric,"
            + " after_value,after_value_numeric,effective_from,note,actor_membership_id,actor_name,"
            + " changed_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?::timestamptz)",
        Ids.next(),
        a.tenantId(),
        parameterCode,
        scope.type(),
        scope.legalEntityId(),
        scope.shopId(),
        scope.shopLabel(),
        action,
        beforeText,
        beforeNumeric,
        afterText,
        afterNumeric,
        utcNow(),
        note(note),
        a.membershipId(),
        actorName(auth),
        utcNow());
    sql.execute(
        "insert into identity_outbox(id,tenant_id,resource_id,event_type) values(?,?,?,?)",
        Ids.next(),
        a.tenantId(),
        Ids.next(),
        "BusinessParameter" + action);
  }

  private Map<String, Object> overrideSnapshot(UUID id) {
    var row = sql.fetchOne("select * from business_parameter_override where id=?", id);
    if (row == null) return null;
    var snap = new LinkedHashMap<String, Object>();
    snap.put("parameterCode", row.get("parameter_code", String.class));
    snap.put("scopeType", row.get("scope_type", String.class));
    snap.put("legalEntityId", str(row.get("legal_entity_id", UUID.class)));
    snap.put("shopId", str(row.get("shop_id", UUID.class)));
    snap.put("shopLabel", row.get("shop_label", String.class));
    snap.put("value", row.get("value_text", String.class));
    snap.put("valueNumeric", row.get("value_numeric", BigDecimal.class));
    snap.put("effectiveFrom", str(row.get("effective_from", OffsetDateTime.class)));
    snap.put("note", row.get("note", String.class));
    snap.put("version", row.get("version", Long.class));
    return snap;
  }

  private Map<String, Object> rowSnapshot(Record r) {
    var snap = new LinkedHashMap<String, Object>();
    snap.put("parameterCode", r.get("parameter_code", String.class));
    snap.put("scopeType", r.get("scope_type", String.class));
    snap.put("legalEntityId", str(r.get("legal_entity_id", UUID.class)));
    snap.put("shopId", str(r.get("shop_id", UUID.class)));
    snap.put("shopLabel", r.get("shop_label", String.class));
    snap.put("value", r.get("value_text", String.class));
    snap.put("valueNumeric", r.get("value_numeric", BigDecimal.class));
    snap.put("effectiveFrom", str(r.get("effective_from", OffsetDateTime.class)));
    snap.put("note", r.get("note", String.class));
    snap.put("version", r.get("version", Long.class));
    return snap;
  }

  private Map<String, Object> freezeSnapshot(UUID freezeId) {
    var head =
        sql.fetchOne(
            "select document_type,document_no,legal_entity_id,shop_id,shop_label,frozen_at"
                + " from business_parameter_freeze where id=?",
            freezeId);
    if (head == null) return null;
    var snap = new LinkedHashMap<String, Object>();
    snap.put("documentType", head.get("document_type", String.class));
    snap.put("documentNo", head.get("document_no", String.class));
    snap.put("legalEntityId", str(head.get("legal_entity_id", UUID.class)));
    snap.put("shopId", str(head.get("shop_id", UUID.class)));
    snap.put("frozenAt", str(head.get("frozen_at", OffsetDateTime.class)));
    var values = new ArrayList<Map<String, Object>>();
    for (Record v :
        sql.fetch(
            "select parameter_code,value_text,value_numeric,source_scope from business_parameter_freeze_value"
                + " where freeze_id=? order by parameter_code",
            freezeId))
      values.add(
          Map.of(
              "parameterCode", v.get("parameter_code", String.class),
              "value", v.get("value_text", String.class),
              "sourceScope", v.get("source_scope", String.class)));
    snap.put("parameters", values);
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
