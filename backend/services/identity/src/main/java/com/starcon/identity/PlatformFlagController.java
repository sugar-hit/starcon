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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
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
 * 17.7.2.1 平台参数与开关。
 *
 * <p>蓝图对本模块点名的硬要求是那句「<b>平台开关与租户参数（17.5.1）分层，平台开关不可被租户覆盖</b>」。
 * 它在本模块里落成<b>两层互不相同的数据</b>，而不是一列可覆盖的开关值：
 *
 * <ul>
 *   <li><b>平台全局层</b>（{@code platform_flag_definition} / {@code platform_flag_setting}）：
 *       <b>不带 tenant_id</b>、不启用租户 RLS、只 {@code GRANT SELECT} 给应用角色。
 *       全平台唯一一份：灰度放量比例、限流阈值、公告、功能开关默认值。
 *   <li><b>租户例外层</b>（{@code platform_flag_tenant_exception}）：{@code tenant_id NOT NULL} +
 *       RLS FORCE isolation。它回答的是「这个租户被平台禁用了某个模块」。
 * </ul>
 *
 * <p>「不可被覆盖」在这里是四条独立成立的事实，不是靠代码自觉：
 *
 * <ol>
 *   <li>全局层<b>没有租户副本</b> —— 库里不存在「按租户的那一份平台开关」这个对象。
 *   <li>应用角色<b>写不了</b>全局层 —— {@code platform_flag_setting} 只授予 SELECT，
 *       唯一的写入口是 {@code platform_flag_apply_setting()} 这个 SECURITY DEFINER 函数。
 *   <li>租户例外<b>只能收紧</b> —— {@code effect} 的取值域被 CHECK 钉死在 {@code 'DISABLED'}，
 *       租户例外永远不可能把平台关掉的功能打开。
 *   <li>限流阈值与公告<b>不参与</b>按租户禁用 —— 禁用一个限流阈值等于放宽限制，
 *       那是放宽不是收紧，DB 层触发器直接拒绝。
 * </ol>
 *
 * <p>裁决口径（{@code /identity/platform-flags/evaluate}）是<b>显式三层</b>的，
 * 不给一个孤零零的布尔值：平台全局值 → 租户例外 → 最终生效值，各自单列，
 * 调用方能直接看出「这个功能为什么对我不可用」。
 *
 * <p><b>灰度放量是确定性的</b>：分桶 = {@code sha256(salt|tenantId)} 前 4 字节大端无符号 mod 10000，
 * 命中条件是分桶 &lt; 放量比例 × 100（万分比，整数比较）。同一租户反复请求恒定，
 * 不会出现「开关时开时关」。口径同时落在数据库函数 {@code platform_flag_rollout_bucket} 上，
 * 集成测试会断言两者逐个租户一致。
 *
 * <p>独立成 {@code PlatformFlagController} 而不并进 {@code AdministrationController}：
 * 17.7.2.1 是独立 L4 单元，同文件各自生长会让两侧改动在合并时互相打架。
 */
@RestController
@RequestMapping("/identity")
public class PlatformFlagController {
  private final DSLContext sql;
  private final IdentityController identity;
  private final TenantTransactions tenants;
  private final AuditLog audit;

  /** 平台参数的灰度分桶基数：万分比。 */
  private static final int ROLLOUT_BUCKETS = 10_000;

  /** 出厂默认盐。与迁移里的默认值一致。 */
  static final String DEFAULT_ROLLOUT_SALT = "starcon-platform-rollout-v1";

  private static final String FLAG_READ = "identity.platform_flag.read";
  private static final String FLAG_UPDATE = "identity.platform_flag.update";
  private static final String FLAG_CREATE = "identity.platform_flag.create";
  private static final String FLAG_DELETE = "identity.platform_flag.delete";

  /** 开关码的形状：大写字母开头的标识符。与库里的 CHECK 同口径。 */
  private static final String FLAG_CODE_PATTERN = "[A-Z][A-Z0-9_]{1,63}";

  public PlatformFlagController(
      DSLContext sql, IdentityController identity, PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.audit = new AuditLog(sql, identity);
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  // ------------------------------------------------------------------
  // 视图
  // ------------------------------------------------------------------

  /** 出厂码表一行（全局，不带租户上下文）。 */
  public record PlatformFlagDefinitionView(
      UUID id,
      String code,
      String name,
      String flagKind,
      boolean defaultEnabled,
      BigDecimal defaultRolloutPercent,
      String description,
      String sortOrder,
      String status) {}

  /**
   * 平台全局层当前值。
   *
   * <p>{@code configured=false} 表示这个开关**还没有平台全局配置行** —— 限流阈值就是这种情况
   * （阈值不给默认值，必须平台显式配）。这时 {@code rateLimitPerMinute} 等字段为空，
   * 而不是替平台编一个数字出来。
   */
  public record PlatformFlagSettingView(
      UUID definitionId,
      String flagCode,
      String name,
      String flagKind,
      boolean configured,
      String status,
      boolean enabled,
      BigDecimal rolloutPercent,
      String rolloutSalt,
      @Schema(nullable = true) Integer rateLimitPerMinute,
      @Schema(nullable = true) String announcementTitle,
      @Schema(nullable = true) String announcementBody,
      @Schema(nullable = true) OffsetDateTime announcementFrom,
      @Schema(nullable = true) OffsetDateTime announcementTo,
      String note,
      long version,
      @Schema(nullable = true) OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime updatedAt) {}

  /** 按租户禁用例外一行。 */
  public record PlatformFlagExceptionView(
      UUID id,
      UUID tenantId,
      String flagCode,
      String flagName,
      String flagKind,
      String effect,
      String reason,
      OffsetDateTime createdAt) {}

  /** 平台全局层变更历史一行。 */
  public record PlatformFlagHistoryView(
      UUID id,
      String flagCode,
      String action,
      @Schema(nullable = true) Boolean beforeEnabled,
      @Schema(nullable = true) Boolean afterEnabled,
      @Schema(nullable = true) BigDecimal beforeRolloutPercent,
      @Schema(nullable = true) BigDecimal afterRolloutPercent,
      @Schema(nullable = true) Integer beforeRateLimitPerMinute,
      @Schema(nullable = true) Integer afterRateLimitPerMinute,
      @Schema(nullable = true) String beforeAnnouncementTitle,
      @Schema(nullable = true) String afterAnnouncementTitle,
      @Schema(nullable = true) OffsetDateTime beforeAnnouncementFrom,
      @Schema(nullable = true) OffsetDateTime afterAnnouncementFrom,
      @Schema(nullable = true) OffsetDateTime beforeAnnouncementTo,
      @Schema(nullable = true) OffsetDateTime afterAnnouncementTo,
      String note,
      @Schema(nullable = true) String operatorName,
      OffsetDateTime changedAt) {}

  /**
   * 第一层：平台全局值。
   *
   * <p>{@code configured=false} = 这个开关在平台全局层还没有配置行（限流阈值的常态）。
   */
  public record PlatformFlagPlatformLayer(
      boolean configured,
      String flagKind,
      boolean enabled,
      BigDecimal rolloutPercent,
      String rolloutSalt,
      @Schema(nullable = true) Integer rateLimitPerMinute,
      @Schema(nullable = true) String announcementTitle,
      @Schema(nullable = true) String announcementBody,
      @Schema(nullable = true) OffsetDateTime announcementFrom,
      @Schema(nullable = true) OffsetDateTime announcementTo) {}

  /**
   * 第二层：租户例外。
   *
   * <p>{@code applicable=false} 表示这一类开关不接受按租户禁用（限流阈值与公告）。
   * {@code effect} 只可能是 {@code DISABLED} —— 例外只能收紧。
   */
  public record PlatformFlagTenantLayer(
      boolean applicable,
      boolean present,
      @Schema(nullable = true) String effect,
      @Schema(nullable = true) String reason,
      @Schema(nullable = true) UUID exceptionId,
      @Schema(nullable = true) OffsetDateTime createdAt) {}

  /**
   * 第三层：最终生效值与裁决依据。
   *
   * <p>{@code reason} 是可读的裁决码：{@code PLATFORM_DISABLED} / {@code TENANT_DISABLED} /
   * {@code ROLLOUT_INCLUDED} / {@code ROLLOUT_EXCLUDED} / {@code NOT_CONFIGURED} /
   * {@code PLATFORM_THRESHOLD} / {@code ANNOUNCEMENT_ACTIVE} / {@code ANNOUNCEMENT_OUT_OF_WINDOW} /
   * {@code ANNOUNCEMENT_NOT_PUBLISHED}。
   */
  public record PlatformFlagEffectiveLayer(
      boolean enabled,
      String reason,
      @Schema(nullable = true) Integer rolloutBucket,
      @Schema(nullable = true) Integer rolloutBasisPoints,
      @Schema(nullable = true) Integer rateLimitPerMinute,
      @Schema(nullable = true) Boolean announcementActive,
      @Schema(nullable = true) String announcementTitle,
      @Schema(nullable = true) String announcementBody,
      @Schema(nullable = true) OffsetDateTime announcementFrom,
      @Schema(nullable = true) OffsetDateTime announcementTo) {}

  /** 三层裁决结果。 */
  public record PlatformFlagEvaluationView(
      UUID definitionId,
      String flagCode,
      String flagName,
      String flagKind,
      UUID tenantId,
      PlatformFlagPlatformLayer platformLayer,
      PlatformFlagTenantLayer tenantLayer,
      PlatformFlagEffectiveLayer effectiveLayer) {}

  /** 能力声明的一维。 */
  public record PlatformFlagCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /** 能力声明。 */
  public record PlatformFlagCapabilities(List<PlatformFlagCapabilityDimension> dimensions) {}

  /** 平台全局层写入输入。 */
  public record PlatformFlagSettingInput(
      @NotNull Boolean enabled,
      @NotNull BigDecimal rolloutPercent,
      @Schema(nullable = true) @Size(max = 64) String rolloutSalt,
      @Schema(nullable = true) Integer rateLimitPerMinute,
      @Schema(nullable = true) @Size(max = 200) String announcementTitle,
      @Schema(nullable = true) String announcementBody,
      @Schema(nullable = true) OffsetDateTime announcementFrom,
      @Schema(nullable = true) OffsetDateTime announcementTo,
      @Size(max = 300) String note) {}

  /** 按租户禁用输入。 */
  public record PlatformFlagExceptionInput(
      @NotBlank @Pattern(regexp = FLAG_CODE_PATTERN, message = "开关码需为大写字母开头的标识符") String flagCode,
      @NotNull UUID tenantId,
      @NotBlank @Size(max = 300) String reason) {}

  // ------------------------------------------------------------------
  // 会话与租户上下文
  // ------------------------------------------------------------------

  /**
   * 在<b>调用方自己的租户</b>上下文里干活。
   *
   * <p>与 {@code BusinessParameterController.call} 同口径：解析会话 → 切到该会话的租户事务 →
   * 锁会话 → 二次校验租户未切换 → 校验权限码 → 组织变更要求 OWNER。
   *
   * <p>租户来自会话成员关系，{@code X-Tenant-Id} 头在这里从头到尾没有出现过。
   */
  private <T> T call(
      String auth, String permission, boolean mutation, Function<IdentityController.Access, T> work) {
    var initial = identity.resolve(auth);
    return tenants.run(
        initial.tenantId(),
        () -> {
          sql.fetch("select lock_session(?)", hash(auth));
          sql.fetch(
              "select pg_advisory_xact_lock(hashtextextended(?,0))", initial.tenantId().toString());
          var a = identity.resolve(auth);
          require(a.tenantId().equals(initial.tenantId()), "租户已切换，请重试");
          require(permissionOf(a, permission), "当前角色没有此操作权限");
          if (mutation) require(a.owner(), "平台参数变更需由当前租户所有者执行");
          return work.apply(a);
        });
  }

  /**
   * 在<b>目标租户</b>的上下文里干活（用于「按租户禁用」这类以别的租户为对象的操作）。
   *
   * <p>可管理性判定必须在<b>调用方租户</b>的上下文里做：{@code tenant_closure} 受 RLS 约束，
   * 换到目标租户的上下文就看不见调用方的后代关系了。所以这里是两个先后独立的事务，
   * 不是嵌套 —— 嵌套会让 {@code set_config('app.current_tenant', …, true)} 覆盖掉外层的租户上下文。
   *
   * <p>目标租户 = 调用方自己，或调用方的后代租户（本仓的层级里，根租户是平台本身）。
   */
  private <T> T callFor(
      String auth,
      String permission,
      boolean mutation,
      UUID target,
      Function<IdentityController.Access, T> work) {
    var initial = identity.resolve(auth);
    boolean manageable =
        tenants.run(initial.tenantId(), () -> manageable(initial.tenantId(), target));
    if (!manageable)
      throw IdentityController.error(HttpStatus.FORBIDDEN, "目标租户不在你的管理范围内");
    return tenants.run(
        target,
        () -> {
          sql.fetch("select lock_session(?)", hash(auth));
          sql.fetch(
              "select pg_advisory_xact_lock(hashtextextended(?,0))", initial.tenantId().toString());
          var a = identity.resolve(auth);
          require(a.tenantId().equals(initial.tenantId()), "租户已切换，请重试");
          require(permissionOf(a, permission), "当前角色没有此操作权限");
          if (mutation) require(a.owner(), "平台参数变更需由当前租户所有者执行");
          return work.apply(a);
        });
  }

  /**
   * 目标租户是否在调用方的管理范围内。
   *
   * <p>{@code tenant_closure} 里含深度 0 的自指行，所以「自己或后代」一次查询就够。
   * 这一段必须在调用方租户的上下文里执行（RLS 只放行 tenant_id = 当前租户的行）。
   */
  private boolean manageable(UUID caller, UUID target) {
    return sql.fetchOne(
            "select 1 from tenant_closure where tenant_id=? and descendant_id=?", caller, target)
        != null;
  }

  private static boolean permissionOf(IdentityController.Access a, String permission) {
    return a.grants().stream()
        .anyMatch(g -> g.scopeMode().equals("ALL") && g.permissions().contains(permission));
  }

  private static void require(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.FORBIDDEN, message);
  }

  private static void check(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.BAD_REQUEST, message);
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

  /**
   * 能力声明：逐维说明哪些已交付、哪些还没有。
   *
   * <p>未交付的维度如实标 {@code available=false} 并写明归属模块 —— 界面据此隐藏入口，
   * 而不是让用户点进去撞 404。
   */
  @GetMapping("/platform-flags/capabilities")
  public PlatformFlagCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        FLAG_READ,
        false,
        a ->
            new PlatformFlagCapabilities(
                List.of(
                    new PlatformFlagCapabilityDimension(
                        "PLATFORM_CONFIG",
                        "平台级开关与参数的全局配置（出厂码表 + 平台全局当前值）",
                        true,
                        "platform_flag_definition / platform_flag_setting",
                        "",
                        ""),
                    new PlatformFlagCapabilityDimension(
                        "ROLLOUT",
                        "灰度放量：按 tenant_id 稳定哈希分桶，同一租户反复请求结果恒定",
                        true,
                        "platform_flag_setting.rollout_percent + platform_flag_rollout_bucket",
                        "",
                        ""),
                    new PlatformFlagCapabilityDimension(
                        "TENANT_DISABLE",
                        "按租户禁用某模块（租户例外层，只能收紧不能放宽）",
                        true,
                        "platform_flag_tenant_exception",
                        "",
                        ""),
                    new PlatformFlagCapabilityDimension(
                        "RATE_LIMIT",
                        "限流阈值（平台级参数，阈值不给默认值，必须平台显式配置）",
                        true,
                        "platform_flag_setting.rate_limit_per_minute",
                        "",
                        "未配置时判定返回「未配置」，不会替平台选一个数字"),
                    new PlatformFlagCapabilityDimension(
                        "ANNOUNCEMENT",
                        "平台公告（标题 / 正文 / 生效期，生效期外不展示）",
                        true,
                        "platform_flag_setting.announcement_*",
                        "",
                        ""),
                    new PlatformFlagCapabilityDimension(
                        "AVAILABILITY_EVALUATION",
                        "功能可用性判定：平台全局值 + 租户例外 → 最终生效值（三层显式给出）",
                        true,
                        "GET /identity/platform-flags/evaluate",
                        "",
                        ""),
                    new PlatformFlagCapabilityDimension(
                        "DOWNSTREAM_GATING",
                        "全系统功能门控接入（各业务服务消费判定结果）",
                        false,
                        "无（仓内没有统一的 feature-flag 客户端 / SDK）",
                        "各业务服务（后续接入）",
                        "判定端点已就绪，但各服务尚未接入；本模块不跨服务改代码，接入是后续待办"),
                    new PlatformFlagCapabilityDimension(
                        "PLAN_ENTITLEMENT_DEGRADE",
                        "套餐与权益的超限降级联动",
                        false,
                        "无（17.7.1.1 套餐与权益同批并行车道，此刻尚不存在）",
                        "17.7.1.1",
                        "超限降级策略暂无来源；本模块只出判定，不实现下游"),
                    new PlatformFlagCapabilityDimension(
                        "PLATFORM_OPS_VIEW",
                        "平台跨租户运维视图",
                        false,
                        "无",
                        "17.7.2.2",
                        "本模块只出判定，不出跨租户运维看板"))));
  }

  /** 出厂码表：平台定义了哪些开关与参数。全租户共用一份，不带租户上下文。 */
  @GetMapping("/platform-flags/definitions")
  public List<PlatformFlagDefinitionView> definitions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        FLAG_READ,
        false,
        a ->
            sql.fetch(
                    "select id,code,name,flag_kind,default_enabled,default_rollout_percent,"
                        + " description,sort_order,status from platform_flag_definition"
                        + " order by sort_order,code")
                .stream()
                .map(PlatformFlagController::toDefinition)
                .toList());
  }

  /**
   * 平台全局层当前值。
   *
   * <p>列表用码表 LEFT JOIN 当前值：<b>没有配置行的开关也要列出来</b>（{@code configured=false}），
   * 否则「限流阈值还没配」这件事在界面上就完全看不见了。
   */
  @GetMapping("/platform-flags/settings")
  public List<PlatformFlagSettingView> settings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        FLAG_READ,
        false,
        a ->
            sql.fetch(SETTINGS_SQL).stream().map(PlatformFlagController::toSetting).toList());
  }

  /** 单个开关的平台全局层当前值。 */
  @GetMapping("/platform-flags/settings/{code}")
  public PlatformFlagSettingView setting(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String code) {
    return call(
        auth,
        FLAG_READ,
        false,
        a -> {
          var row = sql.fetchOne(SETTINGS_SQL + " and d.code=?", code.trim());
          if (row == null)
            throw IdentityController.error(HttpStatus.NOT_FOUND, "平台开关 " + code + " 不存在");
          return toSetting(row);
        });
  }

  /**
   * 按租户禁用的例外列表。
   *
   * <p>不传 {@code tenantId} 时列的是<b>调用方自己租户</b>的例外；传了就列目标租户的，
   * 目标必须是自己或自己的后代租户。
   */
  @GetMapping("/platform-flags/exceptions")
  public List<PlatformFlagExceptionView> exceptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID tenantId) {
    if (tenantId == null)
      return call(
          auth,
          FLAG_READ,
          false,
          a ->
              sql.fetch(EXCEPTIONS_SQL, a.tenantId()).stream()
                  .map(PlatformFlagController::toException)
                  .toList());
    return callFor(
        auth,
        FLAG_READ,
        false,
        tenantId,
        a ->
            sql.fetch(EXCEPTIONS_SQL, tenantId).stream()
                .map(PlatformFlagController::toException)
                .toList());
  }

  /**
   * 平台全局层变更历史（只追加、不可改）。
   *
   * <p>这一张是全局的（不带 tenant_id）：平台参数的改动对所有平台运营都可见。
   */
  @GetMapping("/platform-flags/history")
  public List<PlatformFlagHistoryView> history(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String flagCode) {
    return call(
        auth,
        FLAG_READ,
        false,
        a -> {
          var condition = new Condition();
          if (flagCode != null && !flagCode.isBlank()) {
            condition.add("flag_code=?");
            condition.bind(flagCode.trim());
          }
          return sql.fetch(
                  "select id,flag_code,action,before_enabled,after_enabled,"
                      + " before_rollout_percent,after_rollout_percent,"
                      + " before_rate_limit_per_minute,after_rate_limit_per_minute,"
                      + " before_announcement_title,after_announcement_title,"
                      + " before_announcement_from,after_announcement_from,"
                      + " before_announcement_to,after_announcement_to,"
                      + " note,operator_name,changed_at from platform_flag_history"
                      + condition.sql()
                      + " order by changed_at desc,id desc",
                  condition.bindings())
              .stream()
              .map(PlatformFlagController::toHistory)
              .toList();
        });
  }

  /**
   * 功能可用性判定：平台全局值 × 租户例外 → 最终生效值。
   *
   * <p>三层<b>并列返回</b>，不给一个孤零零的布尔值：只给布尔值的话，调用方无法判断
   * 「为什么这个功能对我不可用」——是平台关了、是我被单独禁用了、还是我没进灰度分桶？
   *
   * <p>不传 {@code tenantId} 时判定的是<b>调用方自己租户</b>；传了则判定目标租户
   * （目标必须是自己或自己的后代租户）。
   */
  @GetMapping("/platform-flags/evaluate")
  public List<PlatformFlagEvaluationView> evaluate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String key,
      @RequestParam(required = false) UUID tenantId) {
    if (tenantId == null)
      return call(
          auth,
          FLAG_READ,
          false,
          a -> evaluateIn(a, a.tenantId(), List.of(key)));
    return callFor(
        auth,
        FLAG_READ,
        false,
        tenantId,
        a -> evaluateIn(a, tenantId, List.of(key)));
  }

  /**
   * 批量判定：给一组开关码，算出目标租户的最终生效值。
   *
   * <p>下游功能门控一次要问好几个开关，逐个发请求会打爆网关；这个批量口与单键判定
   * 共用同一段裁决代码（{@link #adjudicate}），不存在两处口径不一致。
   */
  @GetMapping("/platform-flags/evaluate-batch")
  public List<PlatformFlagEvaluationView> evaluateBatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String keys,
      @RequestParam(required = false) UUID tenantId) {
    var codes =
        java.util.Arrays.stream(keys.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .toList();
    check(!codes.isEmpty(), "至少要给一个开关码");
    if (tenantId == null)
      return call(auth, FLAG_READ, false, a -> evaluateIn(a, a.tenantId(), codes));
    return callFor(auth, FLAG_READ, false, tenantId, a -> evaluateIn(a, tenantId, codes));
  }

  // ------------------------------------------------------------------
  // 写出口
  // ------------------------------------------------------------------

  /**
   * 改平台全局层。
   *
   * <p>写入走数据库的 {@code platform_flag_apply_setting()}：应用角色对
   * {@code platform_flag_setting} <b>没有 DML 权限</b>，权限校验、类别形态校验与变更留痕
   * 都在那一个函数里完成，避免「应用层校验过了但库里形状不对」。
   */
  @PutMapping("/platform-flags/settings/{code}")
  public void updateSetting(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String code,
      @Valid @RequestBody PlatformFlagSettingInput input) {
    call(
        auth,
        FLAG_UPDATE,
        true,
        a -> {
          var flagCode = code.trim();
          var definition = definition(flagCode);
          // 🔴 平台全局层只允许根租户（parent_tenant_id IS NULL）写。
          //   本仓 create_child 会把 permission 表里的**全部**权限码授给子租户的 OWNER 角色，
          //   只看权限码的话，子租户的 OWNER 也能改全平台配置 —— 根租户才是平台本身。
          //   数据库函数 platform_flag_apply_setting 里还有同一道校验（那是兜底，这里是给人看的）。
          require(
              isRootTenant(a.tenantId()),
              "平台全局参数只能由平台（根租户）写入；当前租户是子租户，请联系平台运营");
          validateSettingInput(definition, input);
          var before = settingRow(flagCode);
          // 🔴 每个占位符都带显式 cast：jOOQ 把 null 绑成 unknown、把 OffsetDateTime 绑成
          //   character varying，PG 这样就**解析不出这个函数**（报 "function ... does not exist"）。
          //   timestamptz 的 cast 同时保证了按绝对时刻解释，不经过任何本地时区换算
          //   （见 AuditLog.utc 关于 −28800s 的说明）。
          sql.fetch(
              "select platform_flag_apply_setting(?::text,?::text,?::boolean,?::numeric,?::text,"
                  + "?::int,?::text,?::text,?::timestamptz,?::timestamptz,?::text)",
              hash(auth),
              flagCode,
              input.enabled(),
              input.rolloutPercent(),
              input.rolloutSalt(),
              input.rateLimitPerMinute(),
              input.announcementTitle(),
              input.announcementBody(),
              input.announcementFrom(),
              input.announcementTo(),
              input.note());
          var after = settingRow(flagCode);
          audit.record(
              a,
              auth,
              "identity.platform_flag",
              definition.get("id", UUID.class),
              definition.get("name", String.class) + " · " + flagCode,
              before == null ? "PlatformFlagCreated" : "PlatformFlagUpdated",
              before == null ? null : settingSnapshot(before),
              after == null ? null : settingSnapshot(after));
          return null;
        });
  }

  /**
   * 按租户禁用某模块。
   *
   * <p>写进<b>目标租户</b>的上下文：例外行带着目标租户的 {@code tenant_id}，
   * RLS 的 WITH CHECK 要求当前上下文就是那个租户。
   */
  @PostMapping("/platform-flags/exceptions")
  public UUID createException(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PlatformFlagExceptionInput input) {
    return callFor(
        auth,
        FLAG_CREATE,
        true,
        input.tenantId(),
        a -> {
          var definition = definition(input.flagCode());
          check(
              "MODULE".equals(definition.get("flag_kind", String.class)),
              "只有功能开关可以按租户禁用；"
                  + input.flagCode()
                  + " 是 "
                  + definition.get("flag_kind", String.class)
                  + " 类，禁用它等于放宽限制而不是收紧");
          UUID id = Ids.next();
          try {
            sql.execute(
                "insert into platform_flag_tenant_exception"
                    + "(id,tenant_id,flag_code,effect,reason,created_at,created_by_membership_id)"
                    + " values(?,?,?,'DISABLED',?,?::timestamptz,?)",
                id,
                input.tenantId(),
                input.flagCode(),
                input.reason().trim(),
                utcNow(),
                a.membershipId());
          } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw IdentityController.error(
                HttpStatus.CONFLICT, "该租户已经有 " + input.flagCode() + " 的禁用例外，请直接撤销现有例外");
          }
          sql.execute(
              "insert into platform_flag_exception_history"
                  + "(id,tenant_id,flag_code,action,reason,actor_membership_id,actor_name,changed_at)"
                  + " values(?,?,?,'CREATED',?,?,?,?::timestamptz)",
              Ids.next(),
              input.tenantId(),
              input.flagCode(),
              input.reason().trim(),
              a.membershipId(),
              actorName(auth),
              utcNow());
          return id;
        });
  }

  /**
   * 撤销按租户禁用。
   *
   * <p>例外行被删除，历史留在 {@code platform_flag_exception_history}（RELEASED 行）。
   * 需要显式给 {@code tenantId}：例外行归目标租户所有，按 id 直接删必须先落到那个租户的上下文里。
   */
  @DeleteMapping("/platform-flags/exceptions/{id}")
  public void releaseException(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam UUID tenantId) {
    callFor(
        auth,
        FLAG_DELETE,
        true,
        tenantId,
        a -> {
          var row =
              sql.fetchOne(
                  "select id,flag_code,effect,reason,created_at from platform_flag_tenant_exception"
                      + " where tenant_id=? and id=?",
                  tenantId,
                  id);
          // 404 而不是 403：跨租户拿到的 id 与真正不存在的 id 在这里不可区分。
          if (row == null)
            throw IdentityController.error(
                HttpStatus.NOT_FOUND, "禁用例外不存在或不属于目标租户");
          int deleted =
              sql.execute(
                  "delete from platform_flag_tenant_exception where tenant_id=? and id=?",
                  tenantId,
                  id);
          check(deleted == 1, "禁用例外不存在或不属于目标租户");
          sql.execute(
              "insert into platform_flag_exception_history"
                  + "(id,tenant_id,flag_code,action,reason,actor_membership_id,actor_name,changed_at)"
                  + " values(?,?,?,'RELEASED',?,?,?,?::timestamptz)",
              Ids.next(),
              tenantId,
              row.get("flag_code", String.class),
              row.get("reason", String.class),
              a.membershipId(),
              actorName(auth),
              utcNow());
          return null;
        });
  }

  // ------------------------------------------------------------------
  // 裁决
  // ------------------------------------------------------------------

  /**
   * 对一组开关码做三层裁决。
   *
   * <p>数据来源全部是真实表：平台全局层读 {@code platform_flag_setting}（没有配置行就退回
   * 码表出厂默认），租户例外层读 {@code platform_flag_tenant_exception}（受 RLS 约束，
   * 当前上下文就是目标租户）。裁决过程里没有任何写死的开关状态。
   */
  private List<PlatformFlagEvaluationView> evaluateIn(
      IdentityController.Access a, UUID target, List<String> codes) {
    var now = utcNow();
    var out = new java.util.ArrayList<PlatformFlagEvaluationView>();
    for (var raw : codes) {
      var code = raw.trim();
      var definition = sql.fetchOne(
          "select id,code,name,flag_kind,default_enabled,default_rollout_percent"
              + " from platform_flag_definition where code=?",
          code);
      if (definition == null)
        throw IdentityController.error(HttpStatus.NOT_FOUND, "平台开关 " + code + " 不存在");
      var setting = settingRow(code);
      var exception =
          sql.fetchOne(
              "select id,flag_code,effect,reason,created_at from platform_flag_tenant_exception"
                  + " where tenant_id=? and flag_code=?",
              target,
              code);
      out.add(adjudicate(definition, setting, exception, target, now));
    }
    return out;
  }

  /**
   * 三层裁决本体。
   *
   * <p>裁决顺序不可调换：
   *
   * <ol>
   *   <li>平台没开 → 直接不可用（<b>租户例外打不开它</b>）；
   *   <li>平台开了但这个租户被单独禁用 → 不可用（例外只能收紧）；
   *   <li>平台开了、租户没被禁用 → 看灰度分桶（确定性，同一租户恒定）。
   * </ol>
   *
   * <p>限流阈值与公告不走 1/2/3：它们不是「能不能用」，而是「是多少」和「现在是不是在生效期内」。
   */
  private PlatformFlagEvaluationView adjudicate(
      Record definition, Record setting, Record exception, UUID target, OffsetDateTime now) {
    String code = definition.get("code", String.class);
    String kind = definition.get("flag_kind", String.class);
    boolean configured = setting != null && setting.get("flag_code", String.class) != null;

    boolean enabled =
        configured
            ? Boolean.TRUE.equals(setting.get("enabled", Boolean.class))
            : Boolean.TRUE.equals(definition.get("default_enabled", Boolean.class));
    BigDecimal rolloutPercent =
        configured
            ? setting.get("rollout_percent", BigDecimal.class)
            : definition.get("default_rollout_percent", BigDecimal.class);
    if (rolloutPercent == null) rolloutPercent = BigDecimal.ZERO;
    String salt =
        configured ? setting.get("rollout_salt", String.class) : DEFAULT_ROLLOUT_SALT;
    if (salt == null || salt.isBlank()) salt = DEFAULT_ROLLOUT_SALT;

    var platformLayer =
        new PlatformFlagPlatformLayer(
            configured,
            kind,
            enabled,
            rolloutPercent,
            salt,
            configured ? setting.get("rate_limit_per_minute", Integer.class) : null,
            configured ? setting.get("announcement_title", String.class) : null,
            configured ? setting.get("announcement_body", String.class) : null,
            configured ? setting.get("announcement_from", OffsetDateTime.class) : null,
            configured ? setting.get("announcement_to", OffsetDateTime.class) : null);

    // 租户例外层：只有功能开关接受按租户禁用。
    boolean applicable = "MODULE".equals(kind);
    var tenantLayer =
        new PlatformFlagTenantLayer(
            applicable,
            exception != null,
            exception == null ? null : exception.get("effect", String.class),
            exception == null ? null : exception.get("reason", String.class),
            exception == null ? null : exception.get("id", UUID.class),
            exception == null ? null : exception.get("created_at", OffsetDateTime.class));

    boolean effectiveEnabled;
    String reason;
    Integer bucket = null;
    Integer basisPoints = null;
    Integer rateLimit = platformLayer.rateLimitPerMinute();
    Boolean announcementActive = null;

    switch (kind) {
      case "MODULE" -> {
        basisPoints = basisPoints(rolloutPercent);
        bucket = rolloutBucket(salt, target);
        if (!enabled) {
          effectiveEnabled = false;
          reason = "PLATFORM_DISABLED";
        } else if (exception != null) {
          effectiveEnabled = false;
          reason = "TENANT_DISABLED";
        } else if (bucket < basisPoints) {
          effectiveEnabled = true;
          reason = "ROLLOUT_INCLUDED";
        } else {
          effectiveEnabled = false;
          reason = "ROLLOUT_EXCLUDED";
        }
      }
      case "RATE_LIMIT" -> {
        // 🔴 阈值不给默认值：没配置就没有阈值，如实说「未配置」，不替平台选一个数字。
        if (!configured) {
          effectiveEnabled = false;
          reason = "NOT_CONFIGURED";
          rateLimit = null;
        } else {
          effectiveEnabled = enabled;
          reason = "PLATFORM_THRESHOLD";
        }
      }
      default -> {
        if (!configured) {
          effectiveEnabled = false;
          reason = "NOT_CONFIGURED";
        } else {
          var from = platformLayer.announcementFrom();
          var to = platformLayer.announcementTo();
          boolean inWindow =
              from != null && to != null && !now.isBefore(from) && !now.isAfter(to);
          announcementActive = inWindow;
          effectiveEnabled = enabled && inWindow;
          reason =
              !enabled
                  ? "ANNOUNCEMENT_NOT_PUBLISHED"
                  : inWindow ? "ANNOUNCEMENT_ACTIVE" : "ANNOUNCEMENT_OUT_OF_WINDOW";
        }
      }
    }

    var effectiveLayer =
        new PlatformFlagEffectiveLayer(
            effectiveEnabled,
            reason,
            bucket,
            basisPoints,
            rateLimit,
            announcementActive,
            platformLayer.announcementTitle(),
            platformLayer.announcementBody(),
            platformLayer.announcementFrom(),
            platformLayer.announcementTo());

    return new PlatformFlagEvaluationView(
        definition.get("id", UUID.class),
        code,
        definition.get("name", String.class),
        kind,
        target,
        platformLayer,
        tenantLayer,
        effectiveLayer);
  }

  // ------------------------------------------------------------------
  // 灰度分桶
  // ------------------------------------------------------------------

  /**
   * 灰度分桶：0 .. 9999。
   *
   * <p>口径 = {@code sha256(salt|tenantId)} 前 4 字节按<b>大端无符号</b>解释的 32 位整数 mod 10000。
   * 与数据库函数 {@code platform_flag_rollout_bucket} 逐位一致（集成测试会断言这一点）。
   *
   * <p><b>为什么必须用 long</b>：Java 的 {@code int} 是<b>有符号</b> 32 位，
   * {@code (b0 & 0xFF) << 24} 在首字节 ≥ 128 时会把符号位置 1，整个值变成负数，
   * 取模后落在 −9999 .. −1 —— 同一个租户有时算出正桶、有时算出负桶，
   * 而且与数据库的 {@code bigint} 口径对不上。{@code long} 装下 32 位无符号数，
   * 取模结果恒在 0 .. 9999。
   *
   * <p><b>为什么不能随机</b>：随机分桶会让同一个租户反复请求得到不同结果 ——
   * 用户会看到「这个功能刚才还有，怎么没了」。灰度必须<b>确定性</b>。
   */
  static int rolloutBucket(String salt, UUID tenantId) {
    byte[] digest;
    try {
      digest =
          MessageDigest.getInstance("SHA-256")
              .digest((salt + "|" + tenantId).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用，灰度分桶无法计算", e);
    }
    long value =
        ((long) (digest[0] & 0xFF) << 24)
            | ((long) (digest[1] & 0xFF) << 16)
            | ((long) (digest[2] & 0xFF) << 8)
            | (digest[3] & 0xFF);
    return (int) (value % ROLLOUT_BUCKETS);
  }

  /**
   * 放量比例 → 万分比。
   *
   * <p>{@code numeric(5,2)} × 100 一定是整数，所以这里不会真的截断；
   * 用 DOWN 而不是 UNNECESSARY 是为了在数据异常时也不抛异常打断判定。
   */
  static int basisPoints(BigDecimal percent) {
    return percent.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.DOWN).intValue();
  }

  // ------------------------------------------------------------------
  // 取数与映射
  // ------------------------------------------------------------------

  private static final String SETTINGS_SQL =
      "select d.id as definition_id,d.code,d.name,d.flag_kind,d.status,d.default_enabled,"
          + " d.default_rollout_percent,(s.flag_code is not null) as configured,"
          + " s.enabled,s.rollout_percent,s.rollout_salt,s.rate_limit_per_minute,"
          + " s.announcement_title,s.announcement_body,s.announcement_from,s.announcement_to,"
          + " s.note,s.version,s.created_at,s.updated_at"
          + " from platform_flag_definition d left join platform_flag_setting s on s.flag_code=d.code"
          + " where 1=1";

  private static final String EXCEPTIONS_SQL =
      "select e.id,e.tenant_id,e.flag_code,d.name as flag_name,d.flag_kind,e.effect,e.reason,e.created_at"
          + " from platform_flag_tenant_exception e join platform_flag_definition d on d.code=e.flag_code"
          + " where e.tenant_id=?";

  /**
   * 该租户是不是「平台」（根租户）。
   *
   * <p>本仓的租户层级里，子租户一律由 {@code create_child} 创建，而它会把自己创建的
   * OWNER 角色配上 {@code permission} 表里的**全部**权限码。所以「有写权限」不等于
   * 「是平台」——根租户才是平台本身（{@code parent_tenant_id IS NULL}）。
   *
   * <p>判定走数据库函数 {@code platform_flag_tenant_is_root}：应用角色对 {@code tenant} 表
   * **没有任何权限**（租户信息只能经授权函数读），应用层直接查会被数据库挡下。
   */
  private boolean isRootTenant(UUID tenantId) {
    var row = sql.fetchOne("select platform_flag_tenant_is_root(?) as is_root", tenantId);
    return row != null && Boolean.TRUE.equals(row.get("is_root", Boolean.class));
  }

  /**
   * 写入前的形态校验（与库里 CHECK、函数里的分支同口径）。
   *
   * <p>放在应用层是为了给人一句能读懂的话；数据库里那一份才是权威 —— 应用层放过，
   * 函数与 CHECK 也会挡下。
   */
  private void validateSettingInput(Record definition, PlatformFlagSettingInput input) {
    var percent = input.rolloutPercent();
    check(
        percent.compareTo(BigDecimal.ZERO) >= 0
            && percent.compareTo(new BigDecimal("100")) <= 0,
        "灰度放量比例必须在 0.00 ~ 100.00 之间，当前为 " + percent);
    var kind = definition.get("flag_kind", String.class);
    switch (kind) {
      case "RATE_LIMIT" -> {
        // 🔴 阈值不给默认值：平台必须显式给出，否则请求直接被拒（不替平台选一个数字）。
        check(
            input.rateLimitPerMinute() != null && input.rateLimitPerMinute() > 0,
            "限流阈值必须由平台显式给出（不给默认值），当前为 "
                + (input.rateLimitPerMinute() == null ? "空" : input.rateLimitPerMinute()));
      }
      case "ANNOUNCEMENT" -> {
        check(
            input.announcementTitle() != null && !input.announcementTitle().isBlank(),
            "公告必须填写标题");
        check(
            input.announcementFrom() != null && input.announcementTo() != null,
            "公告必须给出生效期（起 / 止）");
        check(
            input.announcementTo().isAfter(input.announcementFrom()),
            "公告结束时刻必须晚于开始时刻");
      }
      default -> {
        // MODULE：不带限流与公告字段。
        check(
            input.rateLimitPerMinute() == null
                && input.announcementTitle() == null
                && input.announcementFrom() == null
                && input.announcementTo() == null,
            "功能开关不接受限流阈值与公告字段");
      }
    }
  }

  private Record definition(String code) {    var row =
        sql.fetchOne(
            "select id,code,name,flag_kind,status from platform_flag_definition where code=?",
            code.trim());
    if (row == null)
      throw IdentityController.error(HttpStatus.BAD_REQUEST, "平台开关 " + code + " 不存在");
    check(
        "ACTIVE".equals(row.get("status", String.class)),
        "平台开关 " + code + " 已下线，不能再配置");
    return row;
  }

  /** 平台全局层当前值；没有配置行时返回 null（不是空对象 —— 「没配」与「配了空值」不是一回事）。 */
  private Record settingRow(String code) {
    return sql.fetchOne(
        "select flag_code,enabled,rollout_percent,rollout_salt,rate_limit_per_minute,"
            + " announcement_title,announcement_body,announcement_from,announcement_to,"
            + " note,version,created_at,updated_at from platform_flag_setting where flag_code=?",
        code.trim());
  }

  private static PlatformFlagDefinitionView toDefinition(Record r) {
    return new PlatformFlagDefinitionView(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("flag_kind", String.class),
        Boolean.TRUE.equals(r.get("default_enabled", Boolean.class)),
        r.get("default_rollout_percent", BigDecimal.class),
        r.get("description", String.class),
        String.valueOf(r.get("sort_order", Integer.class)),
        r.get("status", String.class));
  }

  private static PlatformFlagSettingView toSetting(Record r) {
    boolean configured = Boolean.TRUE.equals(r.get("configured", Boolean.class));
    return new PlatformFlagSettingView(
        r.get("definition_id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("flag_kind", String.class),
        configured,
        r.get("status", String.class),
        configured && Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("rollout_percent", BigDecimal.class),
        r.get("rollout_salt", String.class),
        r.get("rate_limit_per_minute", Integer.class),
        r.get("announcement_title", String.class),
        r.get("announcement_body", String.class),
        r.get("announcement_from", OffsetDateTime.class),
        r.get("announcement_to", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("version", Long.class) == null ? 0L : r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static PlatformFlagExceptionView toException(Record r) {
    return new PlatformFlagExceptionView(
        r.get("id", UUID.class),
        r.get("tenant_id", UUID.class),
        r.get("flag_code", String.class),
        r.get("flag_name", String.class),
        r.get("flag_kind", String.class),
        r.get("effect", String.class),
        r.get("reason", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static PlatformFlagHistoryView toHistory(Record r) {
    return new PlatformFlagHistoryView(
        r.get("id", UUID.class),
        r.get("flag_code", String.class),
        r.get("action", String.class),
        r.get("before_enabled", Boolean.class),
        r.get("after_enabled", Boolean.class),
        r.get("before_rollout_percent", BigDecimal.class),
        r.get("after_rollout_percent", BigDecimal.class),
        r.get("before_rate_limit_per_minute", Integer.class),
        r.get("after_rate_limit_per_minute", Integer.class),
        r.get("before_announcement_title", String.class),
        r.get("after_announcement_title", String.class),
        r.get("before_announcement_from", OffsetDateTime.class),
        r.get("after_announcement_from", OffsetDateTime.class),
        r.get("before_announcement_to", OffsetDateTime.class),
        r.get("after_announcement_to", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("operator_name", String.class),
        r.get("changed_at", OffsetDateTime.class));
  }

  private Map<String, Object> settingSnapshot(Record r) {
    var snap = new LinkedHashMap<String, Object>();
    snap.put("flagCode", r.get("flag_code", String.class));
    snap.put("enabled", r.get("enabled", Boolean.class));
    snap.put("rolloutPercent", r.get("rollout_percent", BigDecimal.class));
    snap.put("rolloutSalt", r.get("rollout_salt", String.class));
    snap.put("rateLimitPerMinute", r.get("rate_limit_per_minute", Integer.class));
    snap.put("announcementTitle", r.get("announcement_title", String.class));
    snap.put("announcementFrom", str(r.get("announcement_from", OffsetDateTime.class)));
    snap.put("announcementTo", str(r.get("announcement_to", OffsetDateTime.class)));
    snap.put("note", r.get("note", String.class));
    snap.put("version", r.get("version", Long.class));
    return snap;
  }

  private String actorName(String auth) {
    var row = sql.fetchOne("select session_actor_name(?) as name", hash(auth));
    return row == null ? null : row.get("name", String.class);
  }

  /** WHERE 子句与绑定值成对累积，避免条件与参数顺序错位（与 BusinessParameterController.Condition 同一做法）。 */
  private static final class Condition {
    private final List<String> clauses = new ArrayList<>();
    private final List<Object> bindings = new ArrayList<>();

    void add(String clause) {
      clauses.add(clause);
    }

    void bind(Object value) {
      bindings.add(value);
    }

    /**
     * 拼出完整 WHERE 子句；一个条件都没有时返回空串（调用方直接省略 WHERE）。
     *
     * <p><b>带前导空格</b>：调用方把本段直接拼在 {@code "... from 表名"} 后面，
     * 不带空格会拼成 {@code 表名where ...} —— 那是语法错误，不是「少了点空格」。
     */
    String sql() {
      if (clauses.isEmpty()) return "";
      return " where " + String.join(" ", clauses);
    }

    Object[] bindings() {
      return bindings.toArray();
    }
  }
}
