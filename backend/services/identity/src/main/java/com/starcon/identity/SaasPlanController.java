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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Value;
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
 * 17.7.1.1 套餐与权益。
 *
 * <p>这是 starcon **自身**的 SaaS 商业化域：套餐定义与权益项、订阅状态、试用与到期处理、
 * 超限策略。蓝图对它有三条硬要求，本类逐条对应：
 *
 * <ol>
 *   <li><b>仅平台管理员可见，租户不可访问</b>：本类<b>没有</b>一个租户自助端点。
 *       放行条件是三道同时成立（{@link #requirePlatformOps}）：会话租户里存在一条平台运营
 *       角色登记、被登记的 roleId 正好是当前会话持有的角色、该角色分配带有所需权限码。
 *       少任何一条都是 403。租户角色即使自己把 {@code identity.plan.*} 权限码授给自己
 *       也进不来——{@code saas_platform_role} 的登记只发生在平台运营专用路径上。
 *   <li><b>与业务库物理隔离</b>：套餐与权益定义是平台级表（不带 {@code tenant_id}、
 *       不参与 RLS、{@code identity_app} 只有 SELECT），订阅 / 用量 / 留痕是租户级表
 *       （{@code tenant_id NOT NULL} + FORCE RLS）。表名走独立命名空间
 *       {@code saas_plan_*} / {@code saas_subscription_*}，与业务库零外键。
 *   <li><b>与 ERP 客户的销售收入完全无关</b>：本类<b>不落任何金额</b>。没有发票、应收、
 *       付款、结算、收入确认。权益上限与用量都是数量（{@code NUMERIC(19,6)}），
 *       订单量 / API 调用量 / AI 用量是<b>用量计数</b>不是金额。
 * </ol>
 *
 * <p><b>跨租户读取只有一条路径</b>：{@code saas_platform_subscription_list()} 这条
 * SECURITY DEFINER 函数，它自己再判一次闸门。ADR-0005 说「每次业务事务选定一个明确目标
 * 租户」，所以单条订阅的读写一律先由 {@code saas_platform_subscription_tenant()} 定出目标
 * 租户，再在该租户的 RLS 上下文里做——不是把 RLS 放大成「可见全部后代」。
 *
 * <p><b>上游没有的东西不假装有</b>：仓内没有 billing / payment 服务，订阅状态由平台运营
 * 在本模块内驱动（状态流转 + 追加留痕 + 审计）；仓内也没有统一的用量计量接口，所以用量靠
 * 来源系统经 {@code POST /saas-plans/usage} 登记，<b>没接通时 {@code available=false} 且
 * {@code usageValue} 为 null</b>——判定结果是 {@code UNDETERMINED}（判定不了），
 * 而不是拿 0 冒充「量过了，没超」。
 *
 * <p><b>时间字段</b>：{@code timestamptz} 一律绑 {@link OffsetDateTime} 并在 values 位置写
 * {@code ?::timestamptz}。用 {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800 秒
 * （见 {@link AuditLog#utc}）。
 */
@RestController
@RequestMapping("/identity")
public class SaasPlanController {
  private static final String PLAN_READ = "identity.plan.read";
  private static final String PLAN_CREATE = "identity.plan.create";
  private static final String PLAN_UPDATE = "identity.plan.update";
  private static final String SUB_READ = "identity.subscription.read";
  private static final String SUB_CREATE = "identity.subscription.create";
  private static final String SUB_UPDATE = "identity.subscription.update";

  private final DSLContext sql;
  private final IdentityController identity;
  private final TenantTransactions tenants;
  private final AuditLog audit;
  private final String internalKey;

  public SaasPlanController(
      DSLContext sql,
      IdentityController identity,
      @Value("${INTERNAL_KEY}") String internalKey,
      PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.internalKey = internalKey;
    this.audit = new AuditLog(sql, identity);
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  // ------------------------------------------------------------------
  // DTO
  // ------------------------------------------------------------------

  /**
   * 套餐定义 + 权益项计数。
   *
   * <p>{@code entitlementCount} 是真实聚合（{@code count(*)} over {@code saas_plan_entitlement}），
   * 不是写死的数字；{@code blockedActionCount} 只数「超限即阻断」的权益项——阻断与降级
   * 对租户的影响完全不同，看板上必须能分开。
   */
  public record PlanView(
      String code,
      String name,
      int tierLevel,
      String description,
      String status,
      int trialDays,
      int sortOrder,
      long entitlementCount,
      long blockedActionCount,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 权益项：上限是数量，不是金额。 */
  public record PlanEntitlementView(
      String planCode,
      String entitlementCode,
      String entitlementKind,
      BigDecimal limitValue,
      boolean unlimited,
      String overLimitAction,
      String period,
      String unit,
      int sortOrder) {}

  /** 套餐详情 = 套餐 + 它的权益项。 */
  public record PlanDetailView(PlanView plan, List<PlanEntitlementView> entitlements) {}

  /** 新建套餐。{@code code} 一经创建不可改：它会被订阅与用量记录引用。 */
  public record PlanInput(
      @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 100) String name,
      @Size(max = 300) String description,
      Integer tierLevel,
      Integer trialDays,
      Integer sortOrder) {}

  /** 改套餐。{@code status} 只能 ACTIVE / RETIRED——下线走退役而不是删除。 */
  public record PlanUpdateInput(
      @NotBlank @Size(max = 100) String name,
      @Size(max = 300) String description,
      @NotNull @Pattern(regexp = "ACTIVE|RETIRED") String status,
      Integer tierLevel,
      Integer trialDays,
      Integer sortOrder) {}

  /**
   * 权益项输入。
   *
   * <p>{@code limitValue} 允许为 null（不限量时留空）；{@code unlimited=true} 时上限不参与判定。
   */
  public record EntitlementInput(
      @NotBlank @Size(max = 64) String entitlementCode,
      @NotNull
          @Pattern(
              regexp = "SHOP_COUNT|SKU_COUNT|ORDER_VOLUME|USER_COUNT|API_CALL|AI_USAGE")
          String entitlementKind,
      BigDecimal limitValue,
      Boolean unlimited,
      @NotNull @Pattern(regexp = "BLOCK|DEGRADE|ALLOW") String overLimitAction,
      @NotNull @Pattern(regexp = "DAY|MONTH|YEAR|TOTAL") String period,
      @Size(max = 32) String unit,
      Integer sortOrder) {}

  /**
   * 开通订阅。
   *
   * <p>{@code tenantId} 是**目标租户**——平台运营替某个客户开通，所以必须显式给。
   * 它不接受任何请求头里的租户（{@code X-Tenant-Id} 在本模块从头到尾没有读取点）。
   */
  public record SubscriptionInput(
      @NotNull UUID tenantId,
      @NotBlank @Size(max = 64) String planCode,
      @NotNull @Pattern(regexp = "TRIAL|ACTIVE") String status,
      @Schema(nullable = true) OffsetDateTime trialStart,
      @Schema(nullable = true) OffsetDateTime trialEnd,
      @Schema(nullable = true) OffsetDateTime startedAt,
      @Schema(nullable = true) OffsetDateTime expiresAt,
      @Size(max = 500) String note) {}

  /** 改订阅：换套餐 / 改备注 / 延期（延期记 RENEWED）。 */
  public record SubscriptionUpdateInput(
      @Size(max = 64) String planCode,
      @Schema(nullable = true) OffsetDateTime expiresAt,
      @Size(max = 500) String note,
      @Size(max = 300) String reason) {}

  /**
   * 状态流转。
   *
   * <p>{@code reason} 必填：订阅为什么停、为什么取消、什么时候到期，事后都要能解释。
   * {@code startedAt} / {@code expiresAt} 在转入 ACTIVE 时必填（试用转正式或续费）。
   */
  public record SubscriptionStatusInput(
      @NotNull @Pattern(regexp = "TRIAL|ACTIVE|EXPIRED|SUSPENDED|CANCELLED") String status,
      @NotBlank @Size(max = 300) String reason,
      @Schema(nullable = true) OffsetDateTime startedAt,
      @Schema(nullable = true) OffsetDateTime expiresAt) {}

  /**
   * 登记权益用量。
   *
   * <p>{@code available=false} 表示这条维度<b>还没接通计量</b>：此时 {@code usageValue}
   * 必须为空，并由 {@code ownerModule} + {@code impact} 说清归谁、缺了会怎样。
   * {@code available=true} 表示来源系统真的量到了，{@code usageValue} 与 {@code measuredAt}
   * 必填。
   *
   * <p>{@code subscriptionId} 可空：留空时落在该租户最近一条订阅上；租户有多个订阅时
   * 必须显式给，否则用量会记到错误的套餐口径上。
   */
  public record SaasUsageInput(
      @NotNull UUID tenantId,
      @Schema(nullable = true) UUID subscriptionId,
      @NotBlank @Size(max = 64) String entitlementCode,
      @Schema(nullable = true) BigDecimal usageValue,
      @NotNull Boolean available,
      @Schema(nullable = true) @Size(max = 32) String ownerModule,
      @Schema(nullable = true) @Size(max = 300) String impact,
      @Schema(nullable = true) @Size(max = 32) String sourceSystem,
      @Schema(nullable = true) OffsetDateTime measuredAt,
      @Schema(nullable = true) OffsetDateTime periodStart,
      @Schema(nullable = true) OffsetDateTime periodEnd,
      @Schema(nullable = true) @Size(max = 300) String note) {}

  /** 订阅视图。{@code effectiveStatus} 是按当前时刻推导出来的真实状态。 */
  public record SubscriptionView(
      @Schema(nullable = true) UUID id,
      UUID tenantId,
      @Schema(nullable = true) String tenantCode,
      @Schema(nullable = true) String tenantName,
      @Schema(nullable = true) String planCode,
      @Schema(nullable = true) String planName,
      @Schema(nullable = true) String status,
      String effectiveStatus,
      @Schema(nullable = true) OffsetDateTime trialStart,
      @Schema(nullable = true) OffsetDateTime trialEnd,
      @Schema(nullable = true) OffsetDateTime startedAt,
      @Schema(nullable = true) OffsetDateTime expiresAt,
      @Schema(nullable = true) OffsetDateTime cancelledAt,
      String suspendReason,
      String note,
      long version,
      long usageAvailableCount,
      long usageMissingCount,
      long blockedCount,
      @Schema(nullable = true) OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime updatedAt) {}

  /** 权益用量视图：用量 + 上限 + 判定状态。 */
  public record UsageView(
      UUID id,
      UUID subscriptionId,
      String entitlementCode,
      @Schema(nullable = true) String entitlementKind,
      @Schema(nullable = true) BigDecimal limitValue,
      boolean unlimited,
      @Schema(nullable = true) String overLimitAction,
      @Schema(nullable = true) String period,
      @Schema(nullable = true) BigDecimal usageValue,
      boolean available,
      String ownerModule,
      String impact,
      String sourceSystem,
      @Schema(nullable = true) OffsetDateTime measuredAt,
      @Schema(nullable = true) OffsetDateTime periodStart,
      @Schema(nullable = true) OffsetDateTime periodEnd,
      String note,
      String state,
      OffsetDateTime updatedAt) {}

  /**
   * 超限判定结果——本模块<b>产出</b>给下游（17.7.2.1 平台参数与开关）消费的东西。
   *
   * <p>{@code state} 取值：
   *
   * <ul>
   *   <li>{@code WITHIN_LIMIT}：用量在上限内；
   *   <li>{@code OVER_LIMIT_BLOCKED} / {@code OVER_LIMIT_DEGRADED} / {@code OVER_LIMIT_ALLOWED}：
   *       超出上限，按套餐配置的超限策略给出结论；
   *   <li>{@code UNDETERMINED}：这条维度<b>还没接通计量</b>，判定不了——
   *       不能当成「没超限」，下游必须自己决定是放行还是拦；
   *   <li>{@code NO_SUBSCRIPTION}：该租户没有订阅，无从判定；
   *   <li>{@code UNLIMITED}：该权益不设上限。
   * </ul>
   */
  public record EntitlementCheckView(
      UUID tenantId,
      String entitlementCode,
      @Schema(nullable = true) String entitlementKind,
      @Schema(nullable = true) BigDecimal limitValue,
      boolean unlimited,
      @Schema(nullable = true) String overLimitAction,
      @Schema(nullable = true) String period,
      @Schema(nullable = true) BigDecimal usageValue,
      boolean available,
      String state,
      boolean blocked,
      String reason,
      String ownerModule,
      String impact) {}

  /** 订阅变更留痕一行。 */
  public record SubscriptionEventView(
      UUID id,
      UUID subscriptionId,
      String action,
      @Schema(nullable = true) String fromStatus,
      @Schema(nullable = true) String toStatus,
      String reason,
      @Schema(nullable = true) String actorName,
      OffsetDateTime occurredAt) {}

  /** 一个能力维度。{@code available=false} 时必须同时给出 ownerModule 与 impact。 */
  public record SaasCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /** 能力声明。 */
  public record SaasCapabilitiesView(
      List<SaasCapabilityDimension> dimensions,
      List<String> planStatuses,
      List<String> subscriptionStatuses,
      List<String> entitlementKinds,
      List<String> overLimitActions,
      List<String> periods,
      String boundaryNote,
      String meteringNote,
      String financeNote) {}

  /** 下拉选项里的一项套餐。 */
  public record SaasPlanOption(String code, String name, String status) {}

  /** 下拉选项：套餐 + 各类词表。 */
  public record SaasOptionsView(
      List<SaasPlanOption> plans,
      List<String> planStatuses,
      List<String> subscriptionStatuses,
      List<String> entitlementKinds,
      List<String> overLimitActions,
      List<String> periods) {}

  /** 订阅详情：订阅 + 权益用量 + 变更留痕。 */
  public record SubscriptionDetailView(
      SubscriptionView subscription, List<UsageView> usage, List<SubscriptionEventView> events) {}

  // ------------------------------------------------------------------
  // 闸门与工具
  // ------------------------------------------------------------------

  /**
   * 平台运营闸门：三条同时成立才放行。
   *
   * <p>① 会话租户里存在一条 {@code saas_platform_role} 登记；
   * ② 被登记的 {@code roleId} 正好是当前会话持有的某个角色，且其 {@code scope_mode='ALL'}；
   * ③ 该角色分配带有所需权限码。
   *
   * <p>为什么不能只看权限码：既有 {@code role / role_permission} 模型里，任何租户的所有者
   * 都能自建角色并把任意已登记的权限码授给自己。只看权限码的话，「租户不可访问」当场失效。
   */
  private void requirePlatformOps(IdentityController.Access a, String permission) {
    var registered =
        sql.fetch("select role_id from saas_platform_role where tenant_id=?", a.tenantId())
            .getValues("role_id", UUID.class);
    boolean allowed =
        a.grants()
            .stream()
            .anyMatch(
                g ->
                    g.scopeMode().equals("ALL")
                        && registered.contains(g.roleId())
                        && g.permissions().contains(permission));
    require(allowed, "套餐与权益是平台运营域，当前角色没有此操作权限");
  }

  /** 过闸门（读与写的权限码不同）。 */
  private IdentityController.Access authorize(String auth, String permission) {
    var a = identity.resolve(auth);
    tenants.run(
        a.tenantId(),
        () -> {
          requirePlatformOps(a, permission);
          return null;
        });
    return a;
  }

  /**
   * 目标租户必须真实存在。
   *
   * <p>⚠️ 不能直接 {@code select ... from tenant}：仓里**刻意不给 identity_app 直读
   * tenant 表的权限**（租户集合只能由已认证成员关系推导，V3 只把 tenant 暴露给
   * SECURITY DEFINER 函数）。应用侧直查会拿到 42501「没有权限」，而不是「查不到」——
   * 这个坑的表现是接口返回 403，与「平台运营域拒绝你」完全一样，极难定位。
   */
  private TenantProfile tenantProfile(String auth, UUID tenantId, String permission) {
    // ⚠️ 必须写成 `select * from func(...)`：jOOQ 解析裸函数调用 `select func(...)` 时
    // 不知道这个函数的返回列，于是把行类型当成「只有一个叫函数名的字段」，
    // 按 tenant_code 取值会抛「Field "tenant_code" is not contained in row type」。
    // `select *` 让列名来自 JDBC 元数据，才是函数的 OUT 参数名。
    var row =
        sql.fetchOne(
            "select * from saas_platform_tenant_info(?,?,?)", hash(auth), tenantId, permission);
    if (row == null) return null;
    return new TenantProfile(row.get("tenant_code", String.class), row.get("tenant_name", String.class));
  }

  /** 目标租户的编码与名称。 */
  private record TenantProfile(String code, String name) {}

  /** 目标租户必须真实存在；不存在直接 400。 */
  private void requireTenant(String auth, UUID tenantId, String permission) {
    check(tenantProfile(auth, tenantId, permission) != null, "目标租户不存在");
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

  /** timestamptz 一律绑 {@link OffsetDateTime}；见 {@link AuditLog#utc} 关于 −28800s 的说明。 */
  private static OffsetDateTime utcNow() {
    return OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
  }

  private String hash(String auth) {
    return IdentityController.hash(identity.token(auth));
  }

  private static String note(String value) {
    return value == null ? "" : value.trim();
  }

  /**
   * 套餐编码 / 权益编码的合法性：大写字母开头、字母数字下划线、2–64 位。
   *
   * <p>与 {@code DictionaryController.checkCode} 同一口径：这两个编码会进订阅、用量与报表，
   * 小写与空格会让同一件事在不同系统里出现两种写法。
   */
  private static void checkCode(String code, String label) {
    check(
        code.matches("[A-Z][A-Z0-9_]{1,63}"),
        label + "需为大写字母开头的标识符（2–64 位字母、数字或下划线），当前为「" + code + "」");
  }

  /**
   * 审计用的资源 id。
   *
   * <p>{@code identity_audit.resource_id} 是 {@code uuid NOT NULL}，而套餐的标识是字符串编码。
   * 这里用编码派生一个**稳定**的 UUID（同样的套餐永远得到同一个 id），这样按资源检索
   * 审计时仍然成立，且不需要为平台级码表硬造一张 uuid 主键表。
   */
  private static UUID planResourceId(String code) {
    return UUID.nameUUIDFromBytes(("saas_plan:" + code).getBytes(StandardCharsets.UTF_8));
  }

  // ------------------------------------------------------------------
  // 读出口
  // ------------------------------------------------------------------

  /**
   * 能力声明：把「实做了什么」与「**没做**什么」都摆在明面上。
   *
   * <p>三条 {@code available=false} 各自说清缺口归属与影响面——空列表与 0 冒充「没数据」
   * 是把缺口说成了事实。
   */
  @GetMapping("/saas-plans/capabilities")
  public SaasCapabilitiesView capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    authorize(auth, PLAN_READ);
    var dimensions = new ArrayList<SaasCapabilityDimension>();
    dimensions.add(
        new SaasCapabilityDimension(
            "套餐定义与权益项",
            "套餐（档位、试用天数）与六个维度的权益上限（店铺数 / SKU 数 / 订单量 / 用户数 / API 调用量 / AI 用量）",
            true,
            "identity 服务自身的平台级表",
            "",
            ""));
    dimensions.add(
        new SaasCapabilityDimension(
            "订阅状态与试用 / 到期处理",
            "开通、换套餐、试用转正式、暂停 / 恢复、到期、取消，全部带追加留痕与审计",
            true,
            "identity 服务自身",
            "",
            ""));
    dimensions.add(
        new SaasCapabilityDimension(
            "权益用量自动计量",
            "店铺数 / SKU 数 / 订单量 / API 调用量 / AI 用量分散在 catalog / channel / trade 等服务，仓内没有统一的用量计量接口",
            false,
            "各来源系统经 POST /identity/saas-plans/usage 登记",
            "17.7.1.2",
            "未登记的维度判定结果是 UNDETERMINED（判定不了），不是「没超限」；下游功能开关必须自己决定放行还是拦"));
    dimensions.add(
        new SaasCapabilityDimension(
            "超限降级 / 阻断的执行",
            "本模块只产出判定结果（state / blocked），由下游按结论开关功能",
            false,
            "本模块提供 GET /identity/saas-plans/entitlement-check",
            "17.7.2.1",
            "在平台参数与开关交付前，超限结论不会被自动执行；平台运营需要人工干预"));
    dimensions.add(
        new SaasCapabilityDimension(
            "计费与收入确认",
            "发票、应收、付款、结算、收入确认一律不实现",
            false,
            "实体目录 §8：SaaS 商业化独立于 ERP 客户的销售收入",
            "实体目录 §8",
            "本模块不落任何金额列；订阅只表示「买了哪个套餐、当前什么状态、用了多少权益」"));
    return new SaasCapabilitiesView(
        dimensions,
        List.of("ACTIVE", "RETIRED"),
        List.of("TRIAL", "ACTIVE", "EXPIRED", "SUSPENDED", "CANCELLED"),
        List.of("SHOP_COUNT", "SKU_COUNT", "ORDER_VOLUME", "USER_COUNT", "API_CALL", "AI_USAGE"),
        List.of("BLOCK", "DEGRADE", "ALLOW"),
        List.of("DAY", "MONTH", "YEAR", "TOTAL"),
        "套餐与权益是 starcon 自身的 SaaS 商业化域，仅平台管理员可见，租户不可访问；与租户自身的采购 / 应付业务完全无关。",
        "用量计量尚未接通：available=false 的维度 usageValue 为空，判定结果是 UNDETERMINED，不用 0 冒充「量过了」。",
        "本模块不落任何金额：没有发票、应收、付款、结算与收入确认。");
  }

  /** 下拉选项与词表。 */
  @GetMapping("/saas-plans/options")
  public SaasOptionsView options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    authorize(auth, PLAN_READ);
    var plans = new ArrayList<SaasPlanOption>();
    for (var row :
        sql.fetch("select code,name,status from saas_plan order by tier_level,sort_order,code")) {
      plans.add(
          new SaasPlanOption(
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("status", String.class)));
    }
    return new SaasOptionsView(
        plans,
        List.of("ACTIVE", "RETIRED"),
        List.of("TRIAL", "ACTIVE", "EXPIRED", "SUSPENDED", "CANCELLED"),
        List.of("SHOP_COUNT", "SKU_COUNT", "ORDER_VOLUME", "USER_COUNT", "API_CALL", "AI_USAGE"),
        List.of("BLOCK", "DEGRADE", "ALLOW"),
        List.of("DAY", "MONTH", "YEAR", "TOTAL"));
  }

  /** 套餐列表（含权益项计数与阻断策略计数）。 */
  @GetMapping("/saas-plans/plans")
  public List<PlanView> plans(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    authorize(auth, PLAN_READ);
    return sql.fetch(PLAN_SELECT + " order by p.tier_level,p.sort_order,p.code").stream()
        .map(SaasPlanController::toPlan)
        .toList();
  }

  /** 套餐详情（套餐 + 权益项）。 */
  @GetMapping("/saas-plans/plans/{code}")
  public PlanDetailView plan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String code) {
    authorize(auth, PLAN_READ);
    var row = sql.fetchOne(PLAN_SELECT + " where p.code=?", code.trim());
    if (row == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "套餐不存在");
    return new PlanDetailView(
        toPlan(row),
        sql.fetch(
                "select plan_code,entitlement_code,entitlement_kind,limit_value,unlimited,"
                    + "over_limit_action,period,unit,sort_order from saas_plan_entitlement"
                    + " where plan_code=? order by sort_order,entitlement_code",
                code.trim())
            .stream()
            .map(SaasPlanController::toEntitlement)
            .toList());
  }

  /**
   * 跨租户订阅看板：<b>所有</b>租户的订阅状态一行一条，没开通的租户也在列表里。
   *
   * <p>没有开通的租户也必须在列表里——「哪些客户还没开通套餐」正是这个看板要回答的问题；
   * 只列有订阅的租户会把它们藏起来。
   */
  @GetMapping("/saas-plans/subscriptions")
  public List<SubscriptionView> subscriptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    authorize(auth, SUB_READ);
    return sql.fetch("select * from saas_platform_subscription_list(?)", hash(auth)).stream()
        .map(SaasPlanController::toSubscriptionRow)
        .toList();
  }

  /** 订阅详情（含权益用量与变更留痕）。 */
  @GetMapping("/saas-plans/subscriptions/{id}")
  public SubscriptionDetailView subscription(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    authorize(auth, SUB_READ);
    var tenantId = targetTenant(auth, id, SUB_READ);
    return tenants.run(
        tenantId,
        () -> {
          var row = fetchSubscription(tenantId, id);
          if (row == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "订阅不存在");
          var view = toSubscription(row, tenantProfile(auth, tenantId, SUB_READ));
          return new SubscriptionDetailView(
              view, usageRows(tenantId, id, view.planCode()), events(tenantId, id));
        });
  }

  /** 订阅变更留痕（追加不可改）。 */
  @GetMapping("/saas-plans/subscriptions/{id}/events")
  public List<SubscriptionEventView> subscriptionEvents(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    authorize(auth, SUB_READ);
    var tenantId = targetTenant(auth, id, SUB_READ);
    return tenants.run(tenantId, () -> events(tenantId, id));
  }

  /**
   * 权益用量视图（按目标租户）。
   *
   * <p>目标租户是显式参数，不是请求头：平台运营替客户看用量，必须说清看的是谁。
   */
  @GetMapping("/saas-plans/usage")
  public List<UsageView> usage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID tenantId) {
    authorize(auth, SUB_READ);
    requireTenant(auth, tenantId, SUB_READ);
    return tenants.run(
        tenantId,
        () -> {
          var out = new ArrayList<UsageView>();
          for (var r :
              sql.fetch(
                  "select id,plan_code from saas_subscription where tenant_id=? order by created_at desc",
                  tenantId)) {
            out.addAll(
                usageRows(
                    tenantId, r.get("id", UUID.class), r.get("plan_code", String.class)));
          }
          return out;
        });
  }

  /**
   * 超限判定：某个租户的某个权益维度现在是什么结论。
   *
   * <p>这是本模块交给下游（17.7.2.1 平台参数与开关）的**判定结果**。没接通计量的维度返回
   * {@code UNDETERMINED}——下游不能把它当成「没超限」。
   */
  @GetMapping("/saas-plans/entitlement-check")
  public EntitlementCheckView entitlementCheck(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID tenantId,
      @RequestParam String entitlementCode) {
    authorize(auth, SUB_READ);
    requireTenant(auth, tenantId, SUB_READ);
    return tenants.run(tenantId, () -> checkEntitlement(tenantId, entitlementCode.trim()));
  }

  // ------------------------------------------------------------------
  // 写出口 · 套餐与权益（平台级）
  // ------------------------------------------------------------------

  /**
   * 新建套餐。
   *
   * <p>走平台运营专用写入路径（SECURITY DEFINER 函数）：平台级表对 {@code identity_app}
   * 只有 SELECT，应用侧没有 INSERT 权限，改套餐定义必须过平台运营闸门。
   */
  @PostMapping("/saas-plans/plans")
  public UUID createPlan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PlanInput input) {
    var a = authorize(auth, PLAN_CREATE);
    String code = input.code().trim();
    checkCode(code, "套餐编码");
    check(input.trialDays() == null || input.trialDays() >= 0, "试用天数不能为负数");
    try {
      sql.execute(
          "select saas_platform_create_plan(?,?,?,?,?,?,?)",
          hash(auth),
          code,
          input.name().trim(),
          input.tierLevel() == null ? 0 : input.tierLevel(),
          note(input.description()),
          input.trialDays() == null ? 0 : input.trialDays(),
          input.sortOrder() == null ? 0 : input.sortOrder());
    } catch (RuntimeException e) {
      throw translate(e, "套餐「" + code + "」创建失败", "套餐「" + code + "」已存在，请直接编辑现有套餐");
    }
    auditPlan(a, auth, code, "SaasPlanCreated", null, planSnapshot(code));
    return planResourceId(code);
  }

  /** 改套餐。编码不可改——它会被订阅与用量记录引用。 */
  @PutMapping("/saas-plans/plans/{code}")
  public void updatePlan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String code,
      @Valid @RequestBody PlanUpdateInput input) {
    var a = authorize(auth, PLAN_UPDATE);
    String planCode = code.trim();
    var before = planSnapshot(planCode);
    if (before == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "套餐不存在");
    try {
      sql.execute(
          "select saas_platform_update_plan(?,?,?,?,?,?,?,?)",
          hash(auth),
          planCode,
          input.name().trim(),
          input.tierLevel() == null ? 0 : input.tierLevel(),
          note(input.description()),
          input.status(),
          input.trialDays() == null ? 0 : input.trialDays(),
          input.sortOrder() == null ? 0 : input.sortOrder());
    } catch (RuntimeException e) {
      throw translate(e, "套餐「" + planCode + "」更新失败", "套餐「" + planCode + "」更新失败");
    }
    auditPlan(a, auth, planCode, "SaasPlanUpdated", before, planSnapshot(planCode));
  }

  /** 新增权益项。 */
  @PostMapping("/saas-plans/plans/{code}/entitlements")
  public void createEntitlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String code,
      @Valid @RequestBody EntitlementInput input) {
    var a = authorize(auth, PLAN_CREATE);
    String planCode = code.trim();
    String entitlementCode = input.entitlementCode().trim();
    checkCode(entitlementCode, "权益编码");
    check(input.limitValue() == null || input.limitValue().signum() >= 0, "上限不能为负数");
    try {
      sql.execute(
          "select saas_platform_create_entitlement(?,?,?,?,?,?,?,?,?,?)",
          hash(auth),
          planCode,
          entitlementCode,
          input.entitlementKind(),
          input.limitValue(),
          Boolean.TRUE.equals(input.unlimited()),
          input.overLimitAction(),
          input.period(),
          note(input.unit()),
          input.sortOrder() == null ? 0 : input.sortOrder());
    } catch (RuntimeException e) {
      throw translate(
          e,
          "权益项「" + entitlementCode + "」创建失败",
          "套餐「" + planCode + "」下已存在权益项「" + entitlementCode + "」");
    }
    auditPlan(
        a,
        auth,
        planCode,
        "SaasPlanEntitlementCreated",
        null,
        Map.of("entitlementCode", entitlementCode, "kind", input.entitlementKind()));
  }

  /** 改权益项（上限 / 超限策略 / 周期）。编码与所属套餐不可改。 */
  @PutMapping("/saas-plans/plans/{code}/entitlements/{entitlementCode}")
  public void updateEntitlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String code,
      @PathVariable String entitlementCode,
      @Valid @RequestBody EntitlementInput input) {
    var a = authorize(auth, PLAN_UPDATE);
    String planCode = code.trim();
    String entCode = entitlementCode.trim();
    var before = entitlementSnapshot(planCode, entCode);
    if (before == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "权益项不存在");
    check(input.limitValue() == null || input.limitValue().signum() >= 0, "上限不能为负数");
    try {
      sql.execute(
          "select saas_platform_update_entitlement(?,?,?,?,?,?,?,?,?,?)",
          hash(auth),
          planCode,
          entCode,
          input.entitlementKind(),
          input.limitValue(),
          Boolean.TRUE.equals(input.unlimited()),
          input.overLimitAction(),
          input.period(),
          note(input.unit()),
          input.sortOrder() == null ? 0 : input.sortOrder());
    } catch (RuntimeException e) {
      throw translate(e, "权益项「" + entCode + "」更新失败", "权益项「" + entCode + "」更新失败");
    }
    auditPlan(
        a,
        auth,
        planCode,
        "SaasPlanEntitlementUpdated",
        before,
        entitlementSnapshot(planCode, entCode));
  }

  /**
   * 删权益项。
   *
   * <p>已被用量登记引用过的不许删（数据库层同样挡着）：删掉之后历史用量记录会指向一个
   * 不存在的权益维度，报表上会出现空白行。
   */
  @DeleteMapping("/saas-plans/plans/{code}/entitlements/{entitlementCode}")
  public void deleteEntitlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String code,
      @PathVariable String entitlementCode) {
    var a = authorize(auth, PLAN_UPDATE);
    String planCode = code.trim();
    String entCode = entitlementCode.trim();
    var before = entitlementSnapshot(planCode, entCode);
    if (before == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "权益项不存在");
    try {
      sql.execute("select saas_platform_delete_entitlement(?,?,?)", hash(auth), planCode, entCode);
    } catch (RuntimeException e) {
      throw translate(e, "权益项「" + entCode + "」删除失败", "权益项「" + entCode + "」删除失败");
    }
    auditPlan(a, auth, planCode, "SaasPlanEntitlementDeleted", before, null);
  }

  // ------------------------------------------------------------------
  // 写出口 · 订阅与用量（租户级）
  // ------------------------------------------------------------------

  /**
   * 开通订阅（平台运营替某个客户开通）。
   *
   * <p>目标租户是请求体里的 {@code tenantId}，并校验它真实存在。试用必须给起止，
   * 正式订阅必须给起止与到期日——日期缺一半就是「什么时候到期说不清」。
   */
  @PostMapping("/saas-plans/subscriptions")
  public UUID createSubscription(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SubscriptionInput input) {
    var a = authorize(auth, SUB_CREATE);
    UUID tenantId = input.tenantId();
    var profile = tenantProfile(auth, tenantId, SUB_CREATE);
    check(profile != null, "目标租户不存在");
    String planCode = input.planCode().trim();
    var plan = sql.fetchOne("select code,status from saas_plan where code=?", planCode);
    if (plan == null) throw IdentityController.error(HttpStatus.BAD_REQUEST, "套餐不存在");
    if ("RETIRED".equals(plan.get("status", String.class)))
      reject("套餐「" + planCode + "」已退役，不能再开通新订阅");
    if ("TRIAL".equals(input.status())) {
      check(input.trialStart() != null && input.trialEnd() != null, "试用必须给出试用起止时间");
      check(!input.trialEnd().isBefore(input.trialStart()), "试用结束时间不能早于开始时间");
    } else {
      check(input.startedAt() != null && input.expiresAt() != null, "正式订阅必须给出起止时间");
      check(input.expiresAt().isAfter(input.startedAt()), "到期时间必须晚于开始时间");
    }
    UUID id = Ids.next();
    try {
      tenants.run(
          tenantId,
          () -> {
            sql.execute(
                "insert into saas_subscription(id,tenant_id,plan_code,status,trial_start,trial_end,"
                    + " started_at,expires_at,note,version,created_at,updated_at,created_by_membership_id)"
                    + " values(?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz,?,0,"
                    + "?::timestamptz,?::timestamptz,?)",
                id,
                tenantId,
                planCode,
                input.status(),
                input.trialStart(),
                input.trialEnd(),
                input.startedAt(),
                input.expiresAt(),
                note(input.note()),
                utcNow(),
                utcNow(),
                a.membershipId());
            appendEvent(
                tenantId, id, "CREATED", null, input.status(), "开通订阅", auth, a);
            sql.execute(
                "insert into identity_outbox(id,tenant_id,resource_id,event_type) values(?,?,?,?)",
                Ids.next(),
                tenantId,
                id,
                "SaasSubscriptionCreated");
            return null;
          });
    } catch (RuntimeException e) {
      throw translate(
          e,
          "开通订阅失败",
          "该租户在套餐「" + planCode + "」下已有订阅，请直接编辑现有订阅");
    }
    var created = new LinkedHashMap<String, Object>();
    created.put("tenantCode", profile == null ? null : profile.code());
    created.put("planCode", planCode);
    created.put("status", input.status());
    auditSubscription(a, auth, id, tenantId, "SaasSubscriptionCreated", null, created);
    return id;
  }

  /**
   * 改订阅：换套餐 / 延期 / 改备注。
   *
   * <p>延期记 {@code RENEWED}：续费是一个有据可查的事实，不是「改了一下到期日」。
   * 到期日只允许往后延——缩短订阅期不是续费。
   */
  @PutMapping("/saas-plans/subscriptions/{id}")
  public void updateSubscription(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SubscriptionUpdateInput input) {
    var a = authorize(auth, SUB_UPDATE);
    var tenantId = targetTenant(auth, id, SUB_UPDATE);
    var before = tenants.run(tenantId, () -> fetchSubscription(tenantId, id));
    if (before == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "订阅不存在");
    String currentPlan = before.get("plan_code", String.class);
    String nextPlan = note(input.planCode()).isEmpty() ? currentPlan : input.planCode().trim();
    boolean planChanged = !nextPlan.equals(currentPlan);
    if (planChanged) {
      var plan = sql.fetchOne("select code from saas_plan where code=?", nextPlan);
      if (plan == null) throw IdentityController.error(HttpStatus.BAD_REQUEST, "套餐不存在");
    }
    OffsetDateTime currentExpiry = before.get("expires_at", OffsetDateTime.class);
    OffsetDateTime nextExpiry = input.expiresAt() == null ? currentExpiry : input.expiresAt();
    if (input.expiresAt() != null)
      check(
          currentExpiry == null || input.expiresAt().isAfter(currentExpiry),
          "到期日只能往后延，不能往前挪（缩短订阅期不是续费）");
    boolean renewed = input.expiresAt() != null && !planChanged;
    try {
      tenants.run(
          tenantId,
          () -> {
            sql.execute(
                "update saas_subscription set plan_code=?,expires_at=?::timestamptz,note=?,"
                    + " version=version+1,updated_at=?::timestamptz where tenant_id=? and id=?",
                nextPlan,
                nextExpiry,
                input.note() == null ? before.get("note", String.class) : note(input.note()),
                utcNow(),
                tenantId,
                id);
            appendEvent(
                tenantId,
                id,
                renewed ? "RENEWED" : "UPDATED",
                before.get("status", String.class),
                before.get("status", String.class),
                note(input.reason()).isEmpty()
                    ? (renewed ? "续费延期" : "编辑订阅")
                    : note(input.reason()),
                auth,
                a);
            return null;
          });
    } catch (RuntimeException e) {
      throw translate(e, "改订阅失败", "该租户在套餐「" + nextPlan + "」下已有订阅，请先处理重复订阅");
    }
    var after = tenants.run(tenantId, () -> fetchSubscription(tenantId, id));
    var profile = tenantProfile(auth, tenantId, SUB_UPDATE);
    auditSubscription(
        a,
        auth,
        id,
        tenantId,
        "SaasSubscriptionUpdated",
        subscriptionSnapshot(before, profile),
        subscriptionSnapshot(after, profile));
  }

  /**
   * 订阅状态流转。
   *
   * <p>允许的流转是显式白名单（{@link #ALLOWED_TRANSITIONS}），不允许的返回 409——
   * 「已取消的订阅不能恢复」这种规则必须落在服务端，不能指望前端不点那个按钮。
   */
  @PostMapping("/saas-plans/subscriptions/{id}/status")
  public void changeSubscriptionStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SubscriptionStatusInput input) {
    var a = authorize(auth, SUB_UPDATE);
    var tenantId = targetTenant(auth, id, SUB_UPDATE);
    var before = tenants.run(tenantId, () -> fetchSubscription(tenantId, id));
    if (before == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "订阅不存在");
    String from = before.get("status", String.class);
    String to = input.status();
    if (from.equals(to)) reject("订阅已经是「" + to + "」状态");
    var allowed = ALLOWED_TRANSITIONS.get(from);
    if (allowed == null || !allowed.contains(to))
      reject("不允许的状态流转：" + from + " → " + to);
    OffsetDateTime baseStart = before.get("started_at", OffsetDateTime.class);
    OffsetDateTime baseExpiry = before.get("expires_at", OffsetDateTime.class);
    OffsetDateTime startedAt = baseStart;
    OffsetDateTime expiresAt = baseExpiry;
    if ("ACTIVE".equals(to)) {
      // 试用转正式 / 续费：必须给出正式的起止，否则「到哪天为止」说不清。
      OffsetDateTime nextStart = input.startedAt() == null ? baseStart : input.startedAt();
      OffsetDateTime nextExpiry = input.expiresAt() == null ? baseExpiry : input.expiresAt();
      check(nextStart != null && nextExpiry != null, "转入正式订阅必须给出开始时间与到期时间");
      check(nextExpiry.isAfter(nextStart), "到期时间必须晚于开始时间");
      startedAt = nextStart;
      expiresAt = nextExpiry;
    }
    final OffsetDateTime nextStartedAt = startedAt;
    final OffsetDateTime nextExpiresAt = expiresAt;
    OffsetDateTime cancelledAt = "CANCELLED".equals(to) ? utcNow() : null;
    String suspendReason = "SUSPENDED".equals(to) ? input.reason().trim() : "";
    try {
      tenants.run(
          tenantId,
          () -> {
            sql.execute(
                "update saas_subscription set status=?,started_at=?::timestamptz,expires_at=?::timestamptz,"
                    + " cancelled_at=?::timestamptz,suspend_reason=?,version=version+1,"
                    + " updated_at=?::timestamptz where tenant_id=? and id=?",
                to,
                nextStartedAt,
                nextExpiresAt,
                cancelledAt,
                suspendReason,
                utcNow(),
                tenantId,
                id);
            appendEvent(tenantId, id, eventAction(from, to), from, to, input.reason().trim(), auth, a);
            sql.execute(
                "insert into identity_outbox(id,tenant_id,resource_id,event_type) values(?,?,?,?)",
                Ids.next(),
                tenantId,
                id,
                "SaasSubscriptionStatusChanged");
            return null;
          });
    } catch (RuntimeException e) {
      throw translate(e, "状态流转失败", "状态流转与订阅当前状态冲突，请刷新后重试");
    }
    var after = tenants.run(tenantId, () -> fetchSubscription(tenantId, id));
    var profile = tenantProfile(auth, tenantId, SUB_UPDATE);
    auditSubscription(
        a,
        auth,
        id,
        tenantId,
        "SaasSubscriptionStatusChanged",
        subscriptionSnapshot(before, profile),
        subscriptionSnapshot(after, profile));
  }

  /**
   * 把到期 / 试用结束的订阅批量置为已到期。
   *
   * <p>仓内没有定时任务基础设施，所以到期处理由平台运营显式触发，而不是靠一个
   * 「假装在跑」的调度器。判定口径：试用已过试用结束时间、正式订阅已过到期时间。
   */
  @PostMapping("/saas-plans/subscriptions/expire-due")
  public Map<String, Object> expireDue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    var a = authorize(auth, SUB_UPDATE);
    var now = utcNow();
    var due = new ArrayList<UUID>();
    var reasons = new LinkedHashMap<UUID, String>();
    for (var row : sql.fetch("select * from saas_platform_subscription_list(?)", hash(auth))) {
      UUID subId = row.get("subscription_id", UUID.class);
      if (subId == null) continue;
      String status = row.get("status", String.class);
      OffsetDateTime trialEnd = row.get("trial_end", OffsetDateTime.class);
      OffsetDateTime expiresAt = row.get("expires_at", OffsetDateTime.class);
      if ("TRIAL".equals(status) && trialEnd != null && trialEnd.isBefore(now)) {
        due.add(subId);
        reasons.put(subId, "试用期已结束");
      } else if ("ACTIVE".equals(status) && expiresAt != null && expiresAt.isBefore(now)) {
        due.add(subId);
        reasons.put(subId, "订阅已到期");
      }
    }
    int expired = 0;
    for (UUID subId : due) {
      var tenantId = targetTenant(auth, subId, SUB_UPDATE);
      var before = tenants.run(tenantId, () -> fetchSubscription(tenantId, subId));
      if (before == null) continue;
      tenants.run(
          tenantId,
          () -> {
            sql.execute(
                "update saas_subscription set status='EXPIRED',version=version+1,"
                    + " updated_at=?::timestamptz where tenant_id=? and id=?",
                utcNow(),
                tenantId,
                subId);
            appendEvent(
                tenantId,
                subId,
                "EXPIRED",
                before.get("status", String.class),
                "EXPIRED",
                reasons.getOrDefault(subId, "到期处理"),
                auth,
                a);
            return null;
          });
      expired++;
    }
    return Map.of("expired", expired, "checked", due.size());
  }

  /**
   * 登记权益用量。
   *
   * <p>{@code available=false} 表示这条维度还没接通计量：{@code usageValue} 必须为空，
   * 且要带上 {@code ownerModule} + {@code impact} 说清归谁、缺了会怎样——
   * 数据库层的 CHECK 也钉住了这一点（{@code usage_value} 与 {@code available} 必须同时成立）。
   */
  @PostMapping("/saas-plans/usage")
  public UUID recordUsage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaasUsageInput input) {
    var a = authorize(auth, SUB_UPDATE);
    UUID tenantId = input.tenantId();
    requireTenant(auth, tenantId, SUB_UPDATE);
    boolean available = Boolean.TRUE.equals(input.available());
    if (available) {
      check(input.usageValue() != null, "已接通的计量必须给出用量值");
      check(input.usageValue().signum() >= 0, "用量不能为负数");
      check(input.measuredAt() != null, "已接通的计量必须给出计量时刻");
    } else {
      check(
          input.usageValue() == null,
          "尚未接通的计量不能填用量值（0 会被误读成「量过了正好是 0」）");
      check(
          note(input.ownerModule()).length() > 0 && note(input.impact()).length() > 0,
          "尚未接通的计量必须写明归属模块与影响面");
    }
    String entitlementCode = input.entitlementCode().trim();
    UUID id = Ids.next();
    tenants.run(
        tenantId,
        () -> {
          var sub = resolveSubscription(tenantId, input.subscriptionId());
          var ent =
              sql.fetchOne(
                  "select entitlement_code from saas_plan_entitlement"
                      + " where plan_code=? and entitlement_code=?",
                  sub.get("plan_code", String.class),
                  entitlementCode);
          if (ent == null)
            throw IdentityController.error(
                HttpStatus.BAD_REQUEST, "该订阅的套餐下没有这个权益维度：" + entitlementCode);
          UUID subId = sub.get("id", UUID.class);
          sql.execute(
              "insert into saas_entitlement_usage(id,tenant_id,subscription_id,entitlement_code,"
                  + " usage_value,available,owner_module,impact,source_system,measured_at,"
                  + " period_start,period_end,note,updated_at)"
                  + " values(?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz,?,"
                  + "?::timestamptz)"
                  + " on conflict (tenant_id,subscription_id,entitlement_code) do update set"
                  + " usage_value=excluded.usage_value,available=excluded.available,"
                  + " owner_module=excluded.owner_module,impact=excluded.impact,"
                  + " source_system=excluded.source_system,measured_at=excluded.measured_at,"
                  + " period_start=excluded.period_start,period_end=excluded.period_end,"
                  + " note=excluded.note,updated_at=excluded.updated_at",
              id,
              tenantId,
              subId,
              entitlementCode,
              input.usageValue(),
              available,
              note(input.ownerModule()),
              note(input.impact()),
              note(input.sourceSystem()),
              input.measuredAt(),
              input.periodStart(),
              input.periodEnd(),
              note(input.note()),
              utcNow());
          return null;
        });
    auditSubscription(
        a,
        auth,
        id,
        tenantId,
        "SaasEntitlementUsageRecorded",
        null,
        Map.of(
            "entitlementCode", entitlementCode,
            "available", available,
            "usageValue",
            input.usageValue() == null ? "null" : input.usageValue().toPlainString()));
    return id;
  }

  // ------------------------------------------------------------------
  // 平台运营角色登记（平台运营侧专用路径）
  // ------------------------------------------------------------------

  /**
   * 登记本租户的平台运营角色，并把它分配给调用者本人。
   *
   * <p>🔴 这条端点<b>刻意不放进浏览器的 api-proxy 白名单</b>：它是平台运营侧的专用路径，
   * 浏览器打不到它。它还要求内部密钥（{@code X-Internal-Key}）——与 {@code /internal/access}
   * 同一套「服务端到服务端」口径。登记的是「本会话所在租户」——函数不接收任何租户 id
   * 参数，租户一律来自已认证成员关系，不信任 {@code X-Tenant-Id} 头。
   */
  @PostMapping("/saas-platform/roles")
  public Map<String, Object> registerPlatformRole(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestHeader(value = "X-Internal-Key", defaultValue = "") String key,
      @RequestParam(required = false) String roleCode) {
    if (!MessageDigest.isEqual(
        key.getBytes(StandardCharsets.UTF_8), internalKey.getBytes(StandardCharsets.UTF_8)))
      throw IdentityController.error(HttpStatus.FORBIDDEN, "拒绝内部调用");
    var a = identity.resolve(auth);
    String code = roleCode == null || roleCode.isBlank() ? "PLATFORM_OPS" : roleCode.trim();
    try {
      sql.execute("select saas_platform_register_role(?,?)", hash(auth), code);
    } catch (RuntimeException e) {
      throw translate(e, "平台运营角色登记失败", "平台运营角色登记失败");
    }
    return Map.of(
        "tenantId", a.tenantId().toString(),
        "roleCode", code,
        "registered", true);
  }

  // ------------------------------------------------------------------
  // SQL 与映射
  // ------------------------------------------------------------------

  /** 套餐列表的唯一查询入口。计数是真实聚合，不是写死的数字。 */
  private static final String PLAN_SELECT =
      "select p.code,p.name,p.tier_level,p.description,p.status,p.trial_days,p.sort_order,"
          + " p.created_at,p.updated_at,"
          + " (select count(*) from saas_plan_entitlement e where e.plan_code=p.code) as entitlement_count,"
          + " (select count(*) from saas_plan_entitlement e where e.plan_code=p.code"
          + "  and e.over_limit_action='BLOCK') as blocked_action_count"
          + " from saas_plan p";

  /**
   * 订阅状态流转白名单。
   *
   * <p>{@code CANCELLED} 是终态：取消后不能恢复——恢复等于抹掉一次取消事实。
   * {@code EXPIRED → ACTIVE} 是续费；{@code SUSPENDED → ACTIVE} 是恢复。
   */
  private static final Map<String, List<String>> ALLOWED_TRANSITIONS =
      Map.of(
          "TRIAL", List.of("ACTIVE", "EXPIRED", "CANCELLED", "SUSPENDED"),
          "ACTIVE", List.of("EXPIRED", "CANCELLED", "SUSPENDED"),
          "SUSPENDED", List.of("ACTIVE", "EXPIRED", "CANCELLED"),
          "EXPIRED", List.of("ACTIVE", "CANCELLED"),
          "CANCELLED", List.of());

  private static String eventAction(String from, String to) {
    return switch (to) {
      case "CANCELLED" -> "CANCELLED";
      case "SUSPENDED" -> "SUSPENDED";
      case "EXPIRED" -> "EXPIRED";
      case "TRIAL" -> "TRIAL_STARTED";
      case "ACTIVE" ->
          "SUSPENDED".equals(from) ? "RESUMED" : ("EXPIRED".equals(from) ? "RENEWED" : "TRIAL_ENDED");
      default -> "STATUS_CHANGED";
    };
  }

  /** 目标租户：由订阅 id 反查，走同一条闸门（读与写用不同权限码）。 */
  private UUID targetTenant(String auth, UUID subscriptionId, String permission) {
    var row =
        sql.fetchOne(
            "select saas_platform_subscription_tenant(?,?,?)", hash(auth), subscriptionId, permission);
    // 找不到 → 404（不是 403）：403 会把「这个 id 存在但你不能看」和「没这个 id」混成一个答案。
    if (row == null || row.get(0, UUID.class) == null)
      throw IdentityController.error(HttpStatus.NOT_FOUND, "订阅不存在");
    return row.get(0, UUID.class);
  }

  /** 用量登记落在哪条订阅上：显式指定，或该租户最近一条。 */
  private Record resolveSubscription(UUID tenantId, UUID subscriptionId) {
    if (subscriptionId != null) {
      var row =
          sql.fetchOne(
              "select id,plan_code from saas_subscription where tenant_id=? and id=?",
              tenantId,
              subscriptionId);
      if (row == null)
        throw IdentityController.error(HttpStatus.BAD_REQUEST, "订阅不存在或不属于该租户");
      return row;
    }
    var row =
        sql.fetchOne(
            "select id,plan_code from saas_subscription where tenant_id=? order by created_at desc limit 1",
            tenantId);
    if (row == null)
      throw IdentityController.error(HttpStatus.BAD_REQUEST, "该租户还没有订阅，无法登记权益用量");
    return row;
  }

  private Record fetchSubscription(UUID tenantId, UUID id) {
    return sql.fetchOne(
        "select s.id,s.tenant_id,s.plan_code,s.status,s.trial_start,s.trial_end,s.started_at,"
            + " s.expires_at,s.cancelled_at,s.suspend_reason,s.note,s.version,s.created_at,s.updated_at,"
            + " p.name as plan_name,"
            + " (select count(*) from saas_entitlement_usage u where u.subscription_id=s.id and u.available)"
            + "   as usage_available_count,"
            + " (select count(*) from saas_entitlement_usage u where u.subscription_id=s.id and not u.available)"
            + "   as usage_missing_count,"
            + " (select count(*) from saas_entitlement_usage u"
            + "    join saas_plan_entitlement e on e.plan_code=s.plan_code"
            + "      and e.entitlement_code=u.entitlement_code"
            + "    where u.subscription_id=s.id and u.available and not e.unlimited"
            + "      and u.usage_value > e.limit_value and e.over_limit_action='BLOCK') as blocked_count"
            + " from saas_subscription s"
            + " join saas_plan p on p.code=s.plan_code"
            + " where s.tenant_id=? and s.id=?",
        tenantId,
        id);
  }

  /** 权益用量行 + 判定状态。 */
  private List<UsageView> usageRows(UUID tenantId, UUID subscriptionId, String planCode) {
    var rows =
        sql.fetch(
            "select u.id,u.subscription_id,u.entitlement_code,u.usage_value,u.available,u.owner_module,"
                + " u.impact,u.source_system,u.measured_at,u.period_start,u.period_end,u.note,u.updated_at,"
                + " e.entitlement_kind,e.limit_value,e.unlimited,e.over_limit_action,e.period"
                + " from saas_entitlement_usage u"
                + " left join saas_plan_entitlement e on e.plan_code=? and e.entitlement_code=u.entitlement_code"
                + " where u.tenant_id=? and u.subscription_id=? order by u.entitlement_code",
            planCode,
            tenantId,
            subscriptionId);
    var out = new ArrayList<UsageView>();
    for (var r : rows) {
      var view = toUsage(r);
      out.add(
          new UsageView(
              view.id(),
              view.subscriptionId(),
              view.entitlementCode(),
              view.entitlementKind(),
              view.limitValue(),
              view.unlimited(),
              view.overLimitAction(),
              view.period(),
              view.usageValue(),
              view.available(),
              view.ownerModule(),
              view.impact(),
              view.sourceSystem(),
              view.measuredAt(),
              view.periodStart(),
              view.periodEnd(),
              view.note(),
              stateOf(view),
              view.updatedAt()));
    }
    return out;
  }

  private List<SubscriptionEventView> events(UUID tenantId, UUID subscriptionId) {
    return sql.fetch(
            "select id,subscription_id,action,from_status,to_status,reason,actor_name,occurred_at"
                + " from saas_subscription_event where tenant_id=? and subscription_id=?"
                + " order by occurred_at desc,id desc",
            tenantId,
            subscriptionId)
        .stream()
        .map(
            r ->
                new SubscriptionEventView(
                    r.get("id", UUID.class),
                    r.get("subscription_id", UUID.class),
                    r.get("action", String.class),
                    r.get("from_status", String.class),
                    r.get("to_status", String.class),
                    r.get("reason", String.class),
                    r.get("actor_name", String.class),
                    r.get("occurred_at", OffsetDateTime.class)))
        .toList();
  }

  /** 超限判定。判定不出来就是判定不出来，不拿 0 冒充。 */
  private EntitlementCheckView checkEntitlement(UUID tenantId, String entitlementCode) {
    var sub =
        sql.fetchOne(
            "select id,plan_code,status from saas_subscription where tenant_id=? order by created_at desc limit 1",
            tenantId);
    if (sub == null)
      return new EntitlementCheckView(
          tenantId,
          entitlementCode,
          null,
          null,
          false,
          null,
          null,
          null,
          false,
          "NO_SUBSCRIPTION",
          false,
          "该租户还没有订阅，无从判定权益是否超限",
          "",
          "");
    var ent =
        sql.fetchOne(
            "select entitlement_kind,limit_value,unlimited,over_limit_action,period"
                + " from saas_plan_entitlement"
                + " where plan_code=? and entitlement_code=?",
            sub.get("plan_code", String.class),
            entitlementCode);
    if (ent == null)
      return new EntitlementCheckView(
          tenantId,
          entitlementCode,
          null,
          null,
          false,
          null,
          null,
          null,
          false,
          "UNDETERMINED",
          false,
          "该订阅的套餐下没有这个权益维度：" + entitlementCode,
          "",
          "");
    var usage =
        sql.fetchOne(
            "select usage_value,available,owner_module,impact from saas_entitlement_usage"
                + " where tenant_id=? and subscription_id=? and entitlement_code=?",
            tenantId,
            sub.get("id", UUID.class),
            entitlementCode);
    boolean unlimited = Boolean.TRUE.equals(ent.get("unlimited", Boolean.class));
    BigDecimal limit = ent.get("limit_value", BigDecimal.class);
    String action = ent.get("over_limit_action", String.class);
    String period = ent.get("period", String.class);
    String kind = ent.get("entitlement_kind", String.class);
    if (usage == null || !Boolean.TRUE.equals(usage.get("available", Boolean.class))) {
      String owner = usage == null ? "" : usage.get("owner_module", String.class);
      String impact = usage == null ? "" : usage.get("impact", String.class);
      return new EntitlementCheckView(
          tenantId,
          entitlementCode,
          kind,
          limit,
          unlimited,
          action,
          period,
          null,
          false,
          "UNDETERMINED",
          false,
          "该权益维度的用量尚未接通计量，判定不了（不等于「没超限」）",
          owner,
          impact);
    }
    BigDecimal used = usage.get("usage_value", BigDecimal.class);
    if (unlimited)
      return new EntitlementCheckView(
          tenantId,
          entitlementCode,
          kind,
          limit,
          true,
          action,
          period,
          used,
          true,
          "UNLIMITED",
          false,
          "该权益不设上限",
          "",
          "");
    boolean over = used != null && used.compareTo(limit) > 0;
    String state =
        !over
            ? "WITHIN_LIMIT"
            : ("BLOCK".equals(action)
                ? "OVER_LIMIT_BLOCKED"
                : ("DEGRADE".equals(action) ? "OVER_LIMIT_DEGRADED" : "OVER_LIMIT_ALLOWED"));
    return new EntitlementCheckView(
        tenantId,
        entitlementCode,
        kind,
        limit,
        false,
        action,
        period,
        used,
        true,
        state,
        "OVER_LIMIT_BLOCKED".equals(state),
        over ? "已超出权益上限" : "用量在权益上限内",
        "",
        "");
  }

  private static String stateOf(UsageView view) {
    if (!view.available()) return "UNDETERMINED";
    if (view.unlimited()) return "UNLIMITED";
    if (view.usageValue() == null || view.limitValue() == null) return "UNDETERMINED";
    boolean over = view.usageValue().compareTo(view.limitValue()) > 0;
    if (!over) return "WITHIN_LIMIT";
    return switch (view.overLimitAction() == null ? "BLOCK" : view.overLimitAction()) {
      case "DEGRADE" -> "OVER_LIMIT_DEGRADED";
      case "ALLOW" -> "OVER_LIMIT_ALLOWED";
      default -> "OVER_LIMIT_BLOCKED";
    };
  }

  private static PlanView toPlan(Record r) {
    return new PlanView(
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("tier_level", Integer.class),
        r.get("description", String.class),
        r.get("status", String.class),
        r.get("trial_days", Integer.class),
        r.get("sort_order", Integer.class),
        r.get("entitlement_count", Long.class),
        r.get("blocked_action_count", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static PlanEntitlementView toEntitlement(Record r) {
    return new PlanEntitlementView(
        r.get("plan_code", String.class),
        r.get("entitlement_code", String.class),
        r.get("entitlement_kind", String.class),
        r.get("limit_value", BigDecimal.class),
        Boolean.TRUE.equals(r.get("unlimited", Boolean.class)),
        r.get("over_limit_action", String.class),
        r.get("period", String.class),
        r.get("unit", String.class),
        r.get("sort_order", Integer.class));
  }

  private static SubscriptionView toSubscription(Record r, TenantProfile profile) {
    return new SubscriptionView(
        r.get("id", UUID.class),
        r.get("tenant_id", UUID.class),
        profile == null ? null : profile.code(),
        profile == null ? null : profile.name(),
        r.get("plan_code", String.class),
        r.get("plan_name", String.class),
        r.get("status", String.class),
        effectiveStatus(r),
        r.get("trial_start", OffsetDateTime.class),
        r.get("trial_end", OffsetDateTime.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("expires_at", OffsetDateTime.class),
        r.get("cancelled_at", OffsetDateTime.class),
        r.get("suspend_reason", String.class),
        r.get("note", String.class),
        r.get("version", Long.class),
        r.get("usage_available_count", Long.class),
        r.get("usage_missing_count", Long.class),
        r.get("blocked_count", Long.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /** 跨租户看板那一行（来自 {@code saas_platform_subscription_list}）。 */
  private static SubscriptionView toSubscriptionRow(Record r) {
    return new SubscriptionView(
        r.get("subscription_id", UUID.class),
        r.get("tenant_id", UUID.class),
        r.get("tenant_code", String.class),
        r.get("tenant_name", String.class),
        r.get("plan_code", String.class),
        r.get("plan_name", String.class),
        r.get("status", String.class),
        effectiveStatus(r),
        r.get("trial_start", OffsetDateTime.class),
        r.get("trial_end", OffsetDateTime.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("expires_at", OffsetDateTime.class),
        r.get("cancelled_at", OffsetDateTime.class),
        "",
        "",
        0L,
        r.get("usage_available_count", Long.class),
        r.get("usage_missing_count", Long.class),
        r.get("blocked_count", Long.class),
        null,
        null);
  }

  /**
   * 推导出来的真实状态。
   *
   * <p>库里存的 status 是「最后一次人工流转的结果」，而「现在到底还有效吗」要看时间：
   * 试用过了结束时间、正式订阅过了到期时间，就是已到期。这个判定是**算出来的**，
   * 不是存出来的第二个状态位。
   */
  private static String effectiveStatus(Record r) {
    String status = r.get("status", String.class);
    if (status == null) return "NONE";
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    if ("TRIAL".equals(status)) {
      OffsetDateTime trialEnd = r.get("trial_end", OffsetDateTime.class);
      if (trialEnd != null && trialEnd.isBefore(now)) return "EXPIRED";
    }
    if ("ACTIVE".equals(status)) {
      OffsetDateTime expiresAt = r.get("expires_at", OffsetDateTime.class);
      if (expiresAt != null && expiresAt.isBefore(now)) return "EXPIRED";
    }
    return status;
  }

  private static UsageView toUsage(Record r) {
    return new UsageView(
        r.get("id", UUID.class),
        r.get("subscription_id", UUID.class),
        r.get("entitlement_code", String.class),
        r.get("entitlement_kind", String.class),
        r.get("limit_value", BigDecimal.class),
        Boolean.TRUE.equals(r.get("unlimited", Boolean.class)),
        r.get("over_limit_action", String.class),
        r.get("period", String.class),
        r.get("usage_value", BigDecimal.class),
        Boolean.TRUE.equals(r.get("available", Boolean.class)),
        r.get("owner_module", String.class),
        r.get("impact", String.class),
        r.get("source_system", String.class),
        r.get("measured_at", OffsetDateTime.class),
        r.get("period_start", OffsetDateTime.class),
        r.get("period_end", OffsetDateTime.class),
        r.get("note", String.class),
        "",
        r.get("updated_at", OffsetDateTime.class));
  }

  // ------------------------------------------------------------------
  // 留痕
  // ------------------------------------------------------------------

  /** 追加订阅变更留痕。追加不可改由数据库触发器 + REVOKE 双重保证。 */
  private void appendEvent(
      UUID tenantId,
      UUID subscriptionId,
      String action,
      String fromStatus,
      String toStatus,
      String reason,
      String auth,
      IdentityController.Access a) {
    sql.execute(
        "insert into saas_subscription_event(id,tenant_id,subscription_id,action,from_status,"
            + " to_status,reason,actor_membership_id,actor_name,occurred_at)"
            + " values(?,?,?,?,?,?,?,?,?,?::timestamptz)",
        Ids.next(),
        tenantId,
        subscriptionId,
        action,
        fromStatus,
        toStatus,
        note(reason),
        a.membershipId(),
        actorName(auth),
        utcNow());
  }

  /**
   * 审计写入。
   *
   * <p>订阅类操作的审计落在**平台运营租户**的审计流水里（操作者是平台运营，目标租户在
   * 标签与变更详情里）。{@code identity_audit} 受 RLS 保护，所以审计必须在该租户的
   * 上下文里写——这正是这里包一层 {@code tenants.run} 的原因。
   */
  private void auditSubscription(
      IdentityController.Access a,
      String auth,
      UUID resourceId,
      UUID tenantId,
      String action,
      Map<String, Object> before,
      Map<String, Object> after) {
    tenants.run(
        a.tenantId(),
        () -> {
          audit.record(
              a,
              auth,
              "identity.saas_subscription",
              resourceId,
              "订阅 · " + subscriptionLabel(tenantId, after),
              action,
              before,
              after);
          return null;
        });
  }

  /** 审计标签：能读到变更后的订阅就带租户编码，否则退化成租户 id。 */
  private String subscriptionLabel(UUID tenantId, Map<String, Object> after) {
    if (after != null && after.get("tenantCode") != null)
      return String.valueOf(after.get("tenantCode"));
    return String.valueOf(tenantId);
  }

  private void auditPlan(
      IdentityController.Access a,
      String auth,
      String code,
      String action,
      Map<String, Object> before,
      Map<String, Object> after) {
    tenants.run(
        a.tenantId(),
        () -> {
          audit.record(
              a, auth, "identity.saas_plan", planResourceId(code), "套餐 · " + code, action, before, after);
          return null;
        });
  }

  private Map<String, Object> planSnapshot(String code) {
    var row = sql.fetchOne("select * from saas_plan where code=?", code);
    if (row == null) return null;
    var snap = new LinkedHashMap<String, Object>();
    snap.put("code", row.get("code", String.class));
    snap.put("name", row.get("name", String.class));
    snap.put("tierLevel", row.get("tier_level", Integer.class));
    snap.put("description", row.get("description", String.class));
    snap.put("status", row.get("status", String.class));
    snap.put("trialDays", row.get("trial_days", Integer.class));
    snap.put("sortOrder", row.get("sort_order", Integer.class));
    return snap;
  }

  private Map<String, Object> entitlementSnapshot(String planCode, String entitlementCode) {
    var row =
        sql.fetchOne(
            "select * from saas_plan_entitlement where plan_code=? and entitlement_code=?",
            planCode,
            entitlementCode);
    if (row == null) return null;
    var snap = new LinkedHashMap<String, Object>();
    snap.put("entitlementCode", row.get("entitlement_code", String.class));
    snap.put("entitlementKind", row.get("entitlement_kind", String.class));
    snap.put("limitValue", row.get("limit_value", BigDecimal.class));
    snap.put("unlimited", row.get("unlimited", Boolean.class));
    snap.put("overLimitAction", row.get("over_limit_action", String.class));
    snap.put("period", row.get("period", String.class));
    return snap;
  }

  private Map<String, Object> subscriptionSnapshot(Record r, TenantProfile profile) {
    var snap = new LinkedHashMap<String, Object>();
    snap.put("tenantCode", profile == null ? null : profile.code());
    snap.put("planCode", r.get("plan_code", String.class));
    snap.put("status", r.get("status", String.class));
    // 时间一律存成 ISO-8601 字符串：审计侧用的是不带 JavaTimeModule 的 ObjectMapper，
    // 直接塞 OffsetDateTime 会序列化失败（整个审计写入跟着炸）。
    snap.put("trialStart", iso(r.get("trial_start", OffsetDateTime.class)));
    snap.put("trialEnd", iso(r.get("trial_end", OffsetDateTime.class)));
    snap.put("startedAt", iso(r.get("started_at", OffsetDateTime.class)));
    snap.put("expiresAt", iso(r.get("expires_at", OffsetDateTime.class)));
    snap.put("cancelledAt", iso(r.get("cancelled_at", OffsetDateTime.class)));
    snap.put("version", r.get("version", Long.class));
    return snap;
  }

  /** ISO-8601 字符串；null 保持 null。 */
  private static String iso(OffsetDateTime value) {
    return value == null ? null : value.toString();
  }

  private String actorName(String auth) {
    var row = sql.fetchOne("select session_actor_name(?) as name", hash(auth));
    return row == null ? null : row.get("name", String.class);
  }

  /**
   * 把数据库侧的受控错误翻译成 HTTP 状态码。
   *
   * <p>按 SQLSTATE 判定而不是按错误文本：文本会变，SQLSTATE 是 PostgreSQL 的契约。
   * 23505 → 409（撞唯一约束）、23514 → 400（CHECK 不满足）、23503 → 400（外键）、
   * 42501 → 403（权限不足）、P0001 → 400（我们自己的 RAISE，按文案细分）。
   */
  private static RuntimeException translate(RuntimeException e, String fallback, String conflict) {
    String state = sqlState(e);
    String message = rootMessage(e);
    // 「不存在」优先按 404 判：V42 起仓里就用 'Xxx unavailable' 表示找不到，
    // 而 42501 同时也是「权限不足」的 SQLSTATE——先看文案再看 SQLSTATE 才不会把
    // 404 报成 403。
    if (message.contains("unavailable"))
      throw IdentityController.error(HttpStatus.NOT_FOUND, message);
    if ("23505".equals(state))
      throw IdentityController.error(HttpStatus.CONFLICT, conflict);
    if ("23514".equals(state) || "23503".equals(state))
      throw IdentityController.error(HttpStatus.BAD_REQUEST, message);
    if ("42501".equals(state))
      throw IdentityController.error(HttpStatus.FORBIDDEN, message);
    if ("P0001".equals(state)) {
      if (message.contains("Platform role required"))
        throw IdentityController.error(
            HttpStatus.FORBIDDEN, "套餐与权益是平台运营域，当前角色没有此操作权限");
      throw IdentityController.error(HttpStatus.BAD_REQUEST, message);
    }
    return new IllegalStateException(fallback + "：" + message, e);
  }

  /** 顺着 cause 链找 SQLSTATE。 */
  private static String sqlState(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
    }
    return null;
  }

  /** 根因消息。 */
  private static String rootMessage(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null) t = t.getCause();
    return t.getMessage() == null ? e.getMessage() : t.getMessage();
  }
}
