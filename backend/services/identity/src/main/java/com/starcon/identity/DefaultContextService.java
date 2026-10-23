package com.starcon.identity;

import static com.starcon.identity.DefaultContextModels.DefaultContextCapabilities;
import static com.starcon.identity.DefaultContextModels.DefaultContextInput;
import static com.starcon.identity.DefaultContextModels.DefaultContextOptions;
import static com.starcon.identity.DefaultContextModels.DefaultContextView;
import static com.starcon.identity.DefaultContextModels.LEGAL_ENTITY;
import static com.starcon.identity.DefaultContextModels.SHOP;
import static com.starcon.identity.DefaultContextModels.DefaultContextSourceStatus;
import static com.starcon.identity.DefaultContextModels.DefaultContextStaleSelection;
import static com.starcon.identity.DefaultContextModels.WAREHOUSE;
import static com.starcon.identity.UserPreferenceModels.UserPreferenceInput;
import static com.starcon.identity.UserPreferenceModels.UserPreferenceView;

import com.starcon.tenancy.TenantTransactions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.4.2.2 默认店铺与仓库 —— 「默认上下文」的读、写、失效与回落。
 *
 * <p><b>这是纯个人偏好数据</b>：它不是业务事实，而是「我进系统时默认落在哪个店铺 / 法人 / 仓库」。
 * 写入所有权在本服务（identity，与 1.4 个人设置同域），落在 1.4.1.2 建的那张
 * {@code user_preference} 表里，页面 key 是 {@code default-context}。
 *
 * <p><b>🔴 「默认值必须在权限范围内」这条怎么落地</b>（蓝图 1.4.2.2 的硬约束）：
 *
 * <ul>
 *   <li><b>候选集只有一个来源</b>：店铺来自 channel 的授权只读端点（那条端点背后有
 *       RESTRICTIVE {@code data_scope} 策略按负责人 / 部门 / 角色范围过滤），
 *       仓库来自 warehouse 的授权只读端点，法人来自 identity 自己的法人档案。
 *       本服务<b>不自己算一遍范围</b> —— 那样会造出第二套权限真相源。</li>
 *   <li><b>写入前逐条校验</b>：选中的 id 必须出现在候选集里，否则 400。</li>
 *   <li><b>读时复核</b>：存过的默认项不在候选集里（权限变更、店铺被转手、仓库被归档）
 *       ⇒ 判为<b>失效</b>，不再生效，并<b>回落</b>到租户自己的默认仓库
 *       （{@code is_default}）。这就是蓝图说的「权限变更后自动失效并回落」，
 *       <b>不需要同步任务</b>：下一次读就发现。</li>
 *   <li><b>读不到 ≠ 没有</b>：上游不可达时该维度报「读不到」（available=false + 原因），
 *       绝不返回空清单冒充「这个租户没有店铺」。</li>
 * </ul>
 *
 * <p><b>租户与 membership_id 都来自已认证会话</b>：两个都不是请求参数，更不是请求头。
 * 伪造 {@code X-Tenant-Id} 头改不动 {@code app.current_tenant}，也拿不到别人的 membership_id。
 */
@Service
public class DefaultContextService {

  /** 法人档案的读取权限码（17.1 法人主体，本服务持有）。 */
  static final String LEGAL_ENTITY_READ = "identity.legal_entity.read";

  /** 店铺清单的来源（channel 授权只读端点）。 */
  static final String SHOP_SOURCE = "channel GET /channel/shops（2.3.1.1 店铺列表，带数据范围闸）";

  /** 仓库清单的来源（warehouse 授权只读端点）。 */
  static final String WAREHOUSE_SOURCE =
      "warehouse GET /warehouse/facilities（8.1.1.1 仓库档案列表）";

  /** 法人清单的来源（本服务自己的法人档案表）。 */
  static final String LEGAL_ENTITY_SOURCE = "identity GET /identity/legal-entities（17.1 法人档案）";

  private final UserPreferenceService preferences;
  private final DefaultContextShopDirectoryPort shops;
  private final DefaultContextWarehouseDirectoryPort warehouses;
  private final DSLContext sql;
  private final TenantTransactions tenants;

  public DefaultContextService(
      UserPreferenceService preferences,
      DefaultContextShopDirectoryPort shops,
      DefaultContextWarehouseDirectoryPort warehouses,
      DSLContext sql,
      PlatformTransactionManager manager) {
    this.preferences = preferences;
    this.shops = shops;
    this.warehouses = warehouses;
    this.sql = sql;
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  // ------------------------------------------------------------------
  // 读
  // ------------------------------------------------------------------

  /**
   * 读当前生效的默认上下文。
   *
   * <p><b>上游 HTTP 调用刻意放在租户事务之外</b>：事务期间会占住一条数据库连接，
   * 而 channel / warehouse 的授权端点会回头找 identity 确认身份 —— 在连接池很小时
   * 这会自己把自己堵死。
   */
  public DefaultContextView read(IdentityController.Access actor, String bearer) {
    Directory<DefaultContextModels.DefaultContextShopOption> shopDirectory = shopDirectory(bearer);
    Directory<DefaultContextModels.DefaultContextWarehouseOption> warehouseDirectory =
        warehouseDirectory(bearer);
    return tenants.run(
        actor.tenantId(),
        () -> {
          Directory<DefaultContextModels.DefaultContextLegalEntityOption> legalEntityDirectory =
              legalEntityDirectory(actor);
          UserPreferenceView pref =
              preferences.read(actor.tenantId(), actor.membershipId(), DefaultContextRules.PAGE_KEY);
          return resolve(pref, shopDirectory, legalEntityDirectory, warehouseDirectory);
        });
  }

  // ------------------------------------------------------------------
  // 写
  // ------------------------------------------------------------------

  /**
   * 保存默认上下文（<b>整体覆盖</b>：请求体里给 null 的维度等于清空它）。
   *
   * <p><b>写入前必须逐条校验</b>：选中的店铺 / 法人 / 仓库必须落在候选集里。
   * 候选集读不到时**拒绝写入**（503）而不是「先存着」—— 存一个验不过的默认值，
   * 等于把「默认值必须在权限范围内」这条硬约束变成一句空话。
   */
  public DefaultContextView save(
      IdentityController.Access actor, String bearer, DefaultContextInput input) {
    var payload = DefaultContextRules.payload(input);
    if (input.shopId() != null) {
      requireVisible(SHOP, input.shopId(), shopDirectory(bearer));
    }
    if (input.warehouseId() != null) {
      requireVisible(WAREHOUSE, input.warehouseId(), warehouseDirectory(bearer));
    }
    tenants.run(
        actor.tenantId(),
        () -> {
          if (input.legalEntityId() != null) {
            requireVisible(LEGAL_ENTITY, input.legalEntityId(), legalEntityDirectory(actor));
          }
          preferences.save(
              actor.tenantId(),
              actor.membershipId(),
              DefaultContextRules.PAGE_KEY,
              new UserPreferenceInput(payload));
          return null;
        });
    // 读回生效后的样子（含失效判定与回落），保证返回值与下一次 GET 一致。
    return read(actor, bearer);
  }

  /** 重置默认上下文（删掉那一行 = 回到「什么都没设」）。幂等：本来没存过也算成功。 */
  public void reset(IdentityController.Access actor) {
    tenants.run(
        actor.tenantId(),
        () -> {
          preferences.reset(actor.tenantId(), actor.membershipId(), DefaultContextRules.PAGE_KEY);
          return null;
        });
  }

  // ------------------------------------------------------------------
  // 候选集与能力声明
  // ------------------------------------------------------------------

  /** 三个维度的候选集（各自带来源状态）。 */
  public DefaultContextOptions options(IdentityController.Access actor, String bearer) {
    Directory<DefaultContextModels.DefaultContextShopOption> shopDirectory = shopDirectory(bearer);
    Directory<DefaultContextModels.DefaultContextWarehouseOption> warehouseDirectory =
        warehouseDirectory(bearer);
    return tenants.run(
        actor.tenantId(),
        () -> {
          Directory<DefaultContextModels.DefaultContextLegalEntityOption> legalEntityDirectory =
              legalEntityDirectory(actor);
          return new DefaultContextOptions(
              shopDirectory.items(),
              legalEntityDirectory.items(),
              warehouseDirectory.items(),
              List.of(
                  status(shopDirectory, SHOP, SHOP_SOURCE),
                  status(legalEntityDirectory, LEGAL_ENTITY, LEGAL_ENTITY_SOURCE),
                  status(warehouseDirectory, WAREHOUSE, WAREHOUSE_SOURCE)));
        });
  }

  /**
   * 能力声明：哪些维度真接通、哪些没接通、缺什么、归谁。
   *
   * <p>三个维度的可用性是**真探测**出来的（真的去调上游），不是照着设计文档抄的 ——
   * 部署里没配 {@code CHANNEL_URL} 时，声明就会说「未接通」，而不是继续说「已接通」。
   */
  public DefaultContextCapabilities capabilities(IdentityController.Access actor, String bearer) {
    Directory<DefaultContextModels.DefaultContextShopOption> shopDirectory = shopDirectory(bearer);
    Directory<DefaultContextModels.DefaultContextWarehouseOption> warehouseDirectory =
        warehouseDirectory(bearer);
    Directory<DefaultContextModels.DefaultContextLegalEntityOption> legalEntityDirectory =
        tenants.run(actor.tenantId(), () -> legalEntityDirectory(actor));
    return new DefaultContextCapabilities(
        List.of(
            status(shopDirectory, SHOP, SHOP_SOURCE),
            status(legalEntityDirectory, LEGAL_ENTITY, LEGAL_ENTITY_SOURCE),
            status(warehouseDirectory, WAREHOUSE, WAREHOUSE_SOURCE)),
        List.of(
            "默认值必须落在权限范围内：写入前逐条比对上游授权只读端点返回的可见清单，"
                + "不在清单里的选择一律拒绝（400），不会先存着。",
            "权限变更后自动失效并回落：读的时候重新核对可见清单，不在清单里的默认项判为失效、"
                + "不再生效，并回落到租户自己的默认仓库（is_default）；不需要任何同步任务。",
            "读不到 ≠ 没有：上游不可达时该维度报 available=false 并写明原因与归属模块，"
                + "绝不返回空清单冒充「这个租户没有店铺 / 仓库」。",
            "租户与成员身份只来自已认证会话：X-Tenant-Id 头不参与判定，请求体里也不接受租户或成员标识。"),
        List.of(
            "本模块只写「我这一份」个人偏好（user_preference 的 default-context 一行），"
                + "不写任何业务数据；店铺 / 法人 / 仓库的事实仍归 02 / 17.1 / 08 三个服务所有。",
            "法人档案表 legal_entity 目前只有显式 tenant_id 谓词，没有行级 RLS 策略"
                + "（本模块未改该表，也未新增跨服务外键）—— 缺口归 17.1 法人档案。",
            "下游页面（09 订单履约 / 07 库存核算 / 13 利润分析）用 GET /identity/default-context "
                + "取默认上下文注入初始筛选；本模块不替它们改任何查询口径。"));
  }

  // ------------------------------------------------------------------
  // 目录
  // ------------------------------------------------------------------

  /** 一个维度的候选集读取结果。 */
  record Directory<T>(boolean available, List<T> items, String reason) {

    static <T> Directory<T> ok(List<T> items) {
      return new Directory<>(true, items, null);
    }

    static <T> Directory<T> failed(String reason) {
      return new Directory<>(false, List.of(), reason);
    }
  }

  private Directory<DefaultContextModels.DefaultContextShopOption> shopDirectory(String bearer) {
    try {
      return Directory.ok(shops.shops(bearer));
    } catch (DefaultContextShopDirectoryPort.Unavailable e) {
      return Directory.failed(e.getMessage());
    }
  }

  private Directory<DefaultContextModels.DefaultContextWarehouseOption> warehouseDirectory(String bearer) {
    try {
      return Directory.ok(warehouses.warehouses(bearer));
    } catch (DefaultContextWarehouseDirectoryPort.Unavailable e) {
      return Directory.failed(e.getMessage());
    }
  }

  /**
   * 法人候选集（本服务自己的法人档案）。
   *
   * <p><b>显式 {@code tenant_id = ?} 谓词</b>：法人档案表目前没有行级 RLS 策略，
   * 显式谓词是这一层唯一的隔离保证（见能力声明里的边界说明）。
   *
   * <p><b>权限闸</b>：读法人档案要 {@code identity.legal_entity.read}；
   * 没有这个权限码时该维度报「读不到」，而不是把法人清单摊给没权限的人。
   */
  private Directory<DefaultContextModels.DefaultContextLegalEntityOption> legalEntityDirectory(
      IdentityController.Access actor) {
    if (!permitted(actor, LEGAL_ENTITY_READ)) {
      return Directory.failed(
          "当前角色没有法人读取权限（" + LEGAL_ENTITY_READ + "）：法人清单读不到");
    }
    return Directory.ok(
        sql.fetch(
                "select e.id,e.code,e.legal_name,e.status from legal_entity e"
                    + " where e.tenant_id = ?::uuid order by e.status='ACTIVE' desc,e.code",
                actor.tenantId())
            .map(
                row ->
                    new DefaultContextModels.DefaultContextLegalEntityOption(
                        row.get("id", UUID.class),
                        row.get("code", String.class),
                        row.get("legal_name", String.class),
                        row.get("status", String.class))));
  }

  /**
   * 是否持有某个权限码。
   *
   * <p>口径与 channel / warehouse 的 {@code AccessService.require} 一致：
   * 权限码在角色的授予集合里，且范围模式是 ALL / SELF / DEPARTMENT（部门范围要求有部门）。
   */
  private static boolean permitted(IdentityController.Access actor, String permission) {
    return actor
        .grants()
        .stream()
        .anyMatch(
            grant ->
                grant.permissions().contains(permission)
                    && (grant.scopeMode().equals("ALL")
                        || grant.scopeMode().equals("SELF")
                        || (grant.scopeMode().equals("DEPARTMENT")
                            && actor.departmentId() != null)));
  }

  // ------------------------------------------------------------------
  // 解析：生效 / 失效 / 回落
  // ------------------------------------------------------------------

  private DefaultContextView resolve(
      UserPreferenceView pref,
      Directory<DefaultContextModels.DefaultContextShopOption> shopDirectory,
      Directory<DefaultContextModels.DefaultContextLegalEntityOption> legalEntityDirectory,
      Directory<DefaultContextModels.DefaultContextWarehouseOption> warehouseDirectory) {
    var payload = pref.preference();
    var stored = DefaultContextRules.stored(payload);
    List<DefaultContextStaleSelection> stale = new ArrayList<>();
    List<String> notes = new ArrayList<>();

    DefaultContextModels.DefaultContextSelection shop =
        pick(SHOP, stored.shopId(), shopDirectory.available(), shopDirectory.items(), stale);
    DefaultContextModels.DefaultContextSelection legalEntity =
        pick(
            LEGAL_ENTITY,
            stored.legalEntityId(),
            legalEntityDirectory.available(),
            legalEntityDirectory.items(),
            stale);
    DefaultContextModels.DefaultContextSelection warehouse =
        pick(
            WAREHOUSE,
            stored.warehouseId(),
            warehouseDirectory.available(),
            warehouseDirectory.items(),
            stale);

    // 回落：仓库有租户级默认仓标记（is_default）；店铺与法人上游没有同类标记，
    // 所以那两个维度回落为「未设置」—— 不替用户猜一个。
    if (warehouse == null
        && warehouseDirectory.available()
        && !clearedExplicitly(payload, DefaultContextRules.KEY_WAREHOUSE)) {
      DefaultContextModels.DefaultContextWarehouseOption fallback =
          warehouseDirectory.items().stream()
              .filter(DefaultContextModels.DefaultContextWarehouseOption::defaultWarehouse)
              .filter(option -> "ACTIVE".equals(option.status()))
              .findFirst()
              .orElse(null);
      if (fallback != null) {
        warehouse =
            new DefaultContextModels.DefaultContextSelection(
                fallback.id(), fallback.code(), fallback.name(), fallback.status());
        notes.add("已回落到租户默认仓库：" + fallback.code() + " " + fallback.name());
      }
    }

    return new DefaultContextView(
        pref.stored(),
        shop,
        legalEntity,
        warehouse,
        stale,
        List.of(
            status(shopDirectory, SHOP, SHOP_SOURCE),
            status(legalEntityDirectory, LEGAL_ENTITY, LEGAL_ENTITY_SOURCE),
            status(warehouseDirectory, WAREHOUSE, WAREHOUSE_SOURCE)),
        notes.isEmpty() ? null : String.join("；", notes),
        pref.updatedAt());
  }

  /**
   * 解析一个维度：命中且启用 ⇒ 生效；命中但停用 / 归档 ⇒ 失效；没命中 ⇒ 失效。
   *
   * @param available 该维度的来源是否读到了。读不到时**不能**判定失效 ——
   *     那会把「上游挂了」说成「你没权限」，两者含义完全不同
   */
  private <T> DefaultContextModels.DefaultContextSelection pick(
      String dimension,
      UUID id,
      boolean available,
      List<T> items,
      List<DefaultContextStaleSelection> stale) {
    if (id == null) return null;
    if (!available) {
      stale.add(new DefaultContextStaleSelection(dimension, id, null, "来源读不到，无法确认是否仍在权限范围内（已回落）"));
      return null;
    }
    for (T item : items) {
      UUID candidate = idOf(item);
      if (candidate == null || !candidate.equals(id)) continue;
      String status = statusOf(item);
      if (!"ACTIVE".equals(status)) {
        stale.add(
            new DefaultContextStaleSelection(
                dimension, id, nameOf(item), "已停用或归档（状态 " + status + "），不再作为默认值（已回落）"));
        return null;
      }
      return new DefaultContextModels.DefaultContextSelection(id, codeOf(item), nameOf(item), status);
    }
    stale.add(
        new DefaultContextStaleSelection(
            dimension,
            id,
            null,
            "已不在你的可见清单里（权限变更 / 店铺转手 / 已被删除），不再作为默认值（已回落）"));
    return null;
  }

  /** 该维度是否被用户**显式清空**过（显式清空 ≠ 从没设过，前者不该再被回落覆盖）。 */
  private static boolean clearedExplicitly(java.util.Map<String, Object> payload, String key) {
    return payload != null && payload.containsKey(key) && payload.get(key) == null;
  }

  /**
   * 校验一个选择是否落在可见清单里。
   *
   * <p><b>来源读不到时一律 503，不是 400</b>：读不到意味着「无法确认在不在权限范围内」，
   * 与「确认不在权限范围内」是两件事。前者不该被说成后者 —— 否则用户会以为自己被收走了
   * 权限，而真相只是上游挂了。
   */
  private void requireVisible(String dimension, UUID id, Directory<?> directory) {
    if (!directory.available()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          label(dimension)
              + "清单读不到，无法确认所选"
              + label(dimension)
              + "是否仍在权限范围内，拒绝保存："
              + directory.reason());
    }
    for (Object item : directory.items()) {
      UUID candidate = idOf(item);
      if (candidate != null && candidate.equals(id)) return;
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "所选"
            + label(dimension)
            + "不在你的权限范围内（可见清单里没有这一条）：默认值必须落在权限范围内，拒绝保存");
  }

  private static String label(String dimension) {
    return switch (dimension) {
      case SHOP -> "店铺";
      case LEGAL_ENTITY -> "法人";
      case WAREHOUSE -> "仓库";
      default -> dimension;
    };
  }

  private static DefaultContextSourceStatus status(
      Directory<?> directory, String dimension, String source) {
    return new DefaultContextSourceStatus(
        dimension,
        directory.available(),
        source,
        directory.reason(),
        directory.available() ? null : "17.3 权限管理 / 上游部署配置");
  }

  // 目录项的三个投影：三个维度都是 (id, code, name, status) 的同构形状，
  // 这里用 instanceof 分派，避免为三个维度各写一遍几乎一样的循环。
  private static UUID idOf(Object item) {
    if (item instanceof DefaultContextModels.DefaultContextShopOption option) return option.id();
    if (item instanceof DefaultContextModels.DefaultContextLegalEntityOption option) return option.id();
    if (item instanceof DefaultContextModels.DefaultContextWarehouseOption option) return option.id();
    return null;
  }

  private static String codeOf(Object item) {
    if (item instanceof DefaultContextModels.DefaultContextShopOption option) return option.code();
    if (item instanceof DefaultContextModels.DefaultContextLegalEntityOption option) return option.code();
    if (item instanceof DefaultContextModels.DefaultContextWarehouseOption option) return option.code();
    return null;
  }

  private static String nameOf(Object item) {
    if (item instanceof DefaultContextModels.DefaultContextShopOption option) return option.name();
    if (item instanceof DefaultContextModels.DefaultContextLegalEntityOption option) return option.name();
    if (item instanceof DefaultContextModels.DefaultContextWarehouseOption option) return option.name();
    return null;
  }

  private static String statusOf(Object item) {
    if (item instanceof DefaultContextModels.DefaultContextShopOption option) return option.status();
    if (item instanceof DefaultContextModels.DefaultContextLegalEntityOption option) return option.status();
    if (item instanceof DefaultContextModels.DefaultContextWarehouseOption option) return option.status();
    return null;
  }
}
