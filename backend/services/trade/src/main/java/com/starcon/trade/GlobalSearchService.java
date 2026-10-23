package com.starcon.trade;

import static com.starcon.trade.GlobalSearchModels.*;

import com.starcon.tenancy.GrantPermissions;
import java.math.BigDecimal;
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
 * 1.2.1.1 全局搜索框 —— trade 侧的只读检索（销售订单 + 采购单）。
 *
 * <p><b>本类是读投影，不是写入者。</b>{@code sales_order} 的写入所有权属于 9.1.2.2（手工单）
 * 与将来的 9.1.2.1（平台单同步）；{@code purchase_order} 的写入所有权属于 6.3.x 采购单。
 * 本类只读，一行都不写。
 *
 * <p><b>数据范围沿用 trade 既有的两套口径，不另立一套</b>（照抄 9.1.1.1 与 6.3.2.1）：
 *
 * <ul>
 *   <li>范围判定：命中 {@code ALL} 授权就是全租户；否则 {@code DEPARTMENT} 范围且会话带部门
 *       就是本部门；再否则退化为 {@code SELF}（<b>往更严的一侧退化</b>）。
 *   <li>{@code purchase_order} 有 {@code owner_user_id} / {@code owner_department_id} 两列，
 *       所以 DEPARTMENT / SELF 直接落到列上。
 *   <li>{@code sales_order} <b>没有部门列</b>（V99 的订单簇里没有），所以 DEPARTMENT 退化到 SELF，
 *       SELF 落在 {@code sales_order_status_history.actor_id = 本人}（本人经手过的订单）——
 *       与 9.1.1.1 订单列表<b>逐字同构</b>。
 * </ul>
 *
 * <p><b>租户绝不来自请求头</b>：由 {@code AccessService} 向 identity 确认已认证成员关系后绑定
 * {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 *
 * <p><b>匹配方式：前缀</b>（{@code ilike 'kw%'}），不做全文语义检索 —— 蓝图写死的约束。
 */
@Service
public class GlobalSearchService {

  /** 蓝图写死的最小关键词长度。 */
  public static final int MIN_KEYWORD_LENGTH = 2;

  /** 匹配方式标识。 */
  public static final String MATCH_MODE = "PREFIX_OR_EXACT";

  private static final int MAX_LIMIT = 50;

  /** 销售订单读权限码（与 9.1.1.1 订单列表同一个）。 */
  static final String PERM_ORDERS = "trade.orders.read";

  /** 采购单读权限码（与 6.3.2.1 采购单列表同一个）。 */
  static final String PERM_PURCHASE = "trade.purchase.read";

  static final String SCOPE_ALL = "ALL";
  static final String SCOPE_DEPARTMENT = "DEPARTMENT";
  static final String SCOPE_SELF = "SELF";

  private final DSLContext sql;
  private final AccessService access;

  public GlobalSearchService(DSLContext sql, AccessService access) {
    this.sql = sql;
    this.access = access;
  }

  // ================================================================ 检索

  /** 销售订单 + 采购单的前缀检索。 */
  public GlobalSearchResponse search(AccessService.Access actor, String rawKeyword, int limit) {
    String keyword = normalize(rawKeyword);
    int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
    String prefix = escapeLike(keyword) + "%";

    Map<String, List<GlobalSearchHit>> byDomain = new LinkedHashMap<>();
    List<String> unavailable = new ArrayList<>();
    List<GlobalSearchSuppressed> suppressed = new ArrayList<>();
    // 每个业务域**实际生效**的数据范围。
    Map<String, String> scopeByGroup = new LinkedHashMap<>();

    if (permits(actor, PERM_ORDERS)) {
      String scope = orderScope(actor);
      List<GlobalSearchHit> hits =
          access.run(actor, PERM_ORDERS, () -> orderHits(prefix, size, scope, actor.userId()));
      byDomain.computeIfAbsent("ORDER", k -> new ArrayList<>()).addAll(hits);
      scopeByGroup.merge("ORDER", scope, GlobalSearchService::narrower);
    } else {
      unavailable.add("ORDER");
      suppressed.add(new GlobalSearchSuppressed("ORDER", "订单", "ORDER", "订单"));
    }

    if (permits(actor, PERM_PURCHASE)) {
      String scope = scopeMode(actor, PERM_PURCHASE);
      List<GlobalSearchHit> hits =
          access.run(
              actor,
              PERM_PURCHASE,
              () -> purchaseHits(prefix, size, scope, actor.userId(), actor.departmentId()));
      byDomain.computeIfAbsent("PURCHASE", k -> new ArrayList<>()).addAll(hits);
      scopeByGroup.merge("PURCHASE", scope, GlobalSearchService::narrower);
    } else {
      unavailable.add("PURCHASE");
      suppressed.add(new GlobalSearchSuppressed("PURCHASE", "采购单", "PURCHASE", "采购单"));
    }

    List<GlobalSearchGroup> groups = new ArrayList<>();
    addGroup(groups, "ORDER", "订单", byDomain, scopeByGroup);
    addGroup(groups, "PURCHASE", "采购单", byDomain, scopeByGroup);

    return new GlobalSearchResponse(
        keyword, MATCH_MODE, MIN_KEYWORD_LENGTH, groups, unavailable, suppressed);
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些域接通了、依据是哪张表哪一列、哪些域拿不到及其归属模块。 */
  public GlobalSearchCapabilities capabilities() {
    List<GlobalSearchDomain> domains =
        List.of(
            new GlobalSearchDomain(
                "ORDER",
                "销售订单",
                true,
                "09 订单履约（9.1.1.1 订单列表）",
                "sales_order.order_no（我方单号，前缀匹配）",
                MATCH_MODE,
                "主标识是**我方单号**；外部订单号（客户侧参考号）不在本模块的检索口径里 —— "
                    + "它按来源各自唯一，跨来源搜同一个号会返回多张不同的单，"
                    + "那种结果没法点进唯一一张单据。"),
            new GlobalSearchDomain(
                "PURCHASE",
                "采购单",
                true,
                "06 采购与供应（6.3.2.1 采购单列表）",
                "purchase_order.order_no（采购单号，前缀匹配）",
                MATCH_MODE,
                "采购单号是到货跟踪（purchase_arrival_tracking.purchase_order_no）的逻辑关联键，"
                    + "所以按它搜能直接定位到跟单那条链。"),
            new GlobalSearchDomain(
                "SHIPPING",
                "发货 / 物流单",
                false,
                "09.3 履约编排（9.3.3.1 履约请求）",
                null,
                MATCH_MODE,
                "sales_order 与出库单之间<b>尚无履约请求关联</b>，本服务没有任何列指向发货单或运单号。"
                    + "物流单号在 10 物流 / 8 仓库侧，由那两侧的同名端点提供，前端合并。"),
            new GlobalSearchDomain(
                "AFTER_SALES",
                "售后单",
                false,
                "11 售后与退货（11.x）",
                null,
                MATCH_MODE,
                "售后单据的**可检索主标识**（售后单号）不在本服务的检索口径里。"
                    + "本服务不跨域拼一个假的售后号出来 —— "
                    + "售后与退货由 11 侧的同名端点提供。"),
            new GlobalSearchDomain(
                "SUPPLIER",
                "供应商",
                false,
                "3.6.1.1 往来单位（catalog 服务）",
                null,
                MATCH_MODE,
                "供应商主数据在 catalog 的 party 里，本服务只存 supplier_ref_id 逻辑引用与"
                    + "supplier_name 快照 —— 拿快照当主数据搜，会搜到一份可能已改名的副本。"
                    + "供应商检索由 catalog 侧同名端点提供。"));
    List<String> notes =
        List.of(
            "只做前缀 / 精确匹配，不做全文语义检索。",
            "关键词少于 " + MIN_KEYWORD_LENGTH + " 个字符时后端直接 400（前端也挡一道，后端是权威）。",
            "数据范围沿用 9.1.1.1 / 6.3.2.1 的既有口径：ALL / DEPARTMENT / SELF，"
                + "sales_order 没有部门列时 DEPARTMENT 往更严的一侧退化为 SELF。");
    return new GlobalSearchCapabilities(domains, notes);
  }

  // ================================================================ 内部

  /** 销售订单命中。主标识 = 我方单号；副信息 = 客户名 · 单据状态 · 金额。 */
  private List<GlobalSearchHit> orderHits(String prefix, int size, String scope, UUID actorId) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where o.order_no ilike ? escape '!'");
    binds.add(prefix);
    if (!SCOPE_ALL.equals(scope)) {
      // 订单没有 owner / department 列（那是 9.1.2.2 的写入所有权范围，不在本服务），
      // SELF 落在「本人经手过」上：sales_order_status_history 里有本人的留痕。
      // 与 9.1.1.1 订单列表逐字同构（那边是「只要有一条 ALL 就按 ALL，否则按 SELF 收窄」）。
      // 刻意**不是**只判 SELF：DEPARTMENT 也必须收窄到本人经手过 ——
      // 部门范围在 sales_order 上没有可落地的列，放行等于把全租户订单返回给部门范围的角色。
      where.append(
          " and exists (select 1 from sales_order_status_history h"
              + " where h.tenant_id = o.tenant_id and h.order_id = o.id and h.actor_id = ?::uuid)");
      binds.add(actorId);
    }
    binds.add(size);
    List<GlobalSearchHit> hits = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select o.id, o.order_no, o.status, o.currency, o.grand_total_amount, p.customer_name"
                + " from sales_order o"
                + " left join sales_order_party_snapshot p"
                + "   on p.tenant_id = o.tenant_id and p.order_id = o.id"
                + where
                + " order by o.order_no"
                + " limit ?",
            binds.toArray())) {
      hits.add(
          new GlobalSearchHit(
              "ORDER",
              "SALES_ORDER",
              r.get("id", UUID.class),
              r.get("order_no", String.class),
              join(
                  r.get("customer_name", String.class),
                  join(r.get("status", String.class), amount(r, "currency", "grand_total_amount")))));
    }
    return hits;
  }

  /** 采购单命中。主标识 = 采购单号；副信息 = 供应商 · 单据状态 · 金额。 */
  private List<GlobalSearchHit> purchaseHits(
      String prefix, int size, String scope, UUID actorId, UUID actorDepartmentId) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where o.order_no ilike ? escape '!'");
    binds.add(prefix);
    if (SCOPE_DEPARTMENT.equals(scope) && actorDepartmentId != null) {
      where.append(" and o.owner_department_id = ?::uuid");
      binds.add(actorDepartmentId);
    } else if (SCOPE_SELF.equals(scope)) {
      where.append(" and o.owner_user_id = ?::uuid");
      binds.add(actorId);
    }
    binds.add(size);
    List<GlobalSearchHit> hits = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select o.id, o.order_no, o.status, o.supplier_name, o.currency, o.total_amount"
                + " from purchase_order o"
                + where
                + " order by o.order_no"
                + " limit ?",
            binds.toArray())) {
      hits.add(
          new GlobalSearchHit(
              "PURCHASE",
              "PURCHASE_ORDER",
              r.get("id", UUID.class),
              r.get("order_no", String.class),
              join(
                  r.get("supplier_name", String.class),
                  join(r.get("status", String.class), amount(r, "currency", "total_amount")))));
    }
    return hits;
  }

  /**
   * 范围判定：命中 {@code ALL} 授权就是全租户；否则 {@code DEPARTMENT} 范围且会话带部门就是本部门；
   * 再否则退化为 {@code SELF}。
   *
   * <p>「操作和范围必须由同一分配满足」（ADR-0005）：只看<b>含有本权限</b>的授权，
   * 别的权限的授权（哪怕是 ALL）不参与本域的范围判定。
   *
   * <p>判定本体在共享库 {@code GrantPermissions}（与各服务 RLS 的判定同语义：
   * 多条授权里只要有一条 ALL，返回的行就是全租户可见），这里只做映射。
   */
  static String scopeMode(AccessService.Access actor, String permission) {
    return GrantPermissions.scopeMode(
        grantsOf(actor), permission, actor.departmentId() != null);
  }

  /**
   * 销售订单的实际范围：{@code sales_order} 没有部门列（V99 的订单簇里没有），
   * 所以 {@code DEPARTMENT} 退化为 {@code SELF} —— 与 9.1.1.1 订单列表逐字同构
   * （那边是「只要有一条 ALL 就按 ALL，否则按 SELF 收窄到本人经手过的订单」）。
   *
   * <p>退化方向刻意选<b>更严</b>的一侧：报成 DEPARTMENT 会让界面显示「只看本部门」，
   * 实际返回的却是本人经手过的订单 —— 标注与事实不符。
   */
  static String orderScope(AccessService.Access actor) {
    String scope = scopeMode(actor, PERM_ORDERS);
    return SCOPE_DEPARTMENT.equals(scope) ? SCOPE_SELF : scope;
  }

  /** 域 → 分组。空组不返回。 */
  private static void addGroup(
      List<GlobalSearchGroup> groups,
      String domain,
      String label,
      Map<String, List<GlobalSearchHit>> byDomain,
      Map<String, String> scopeByGroup) {
    List<GlobalSearchHit> hits = byDomain.get(domain);
    if (hits == null || hits.isEmpty()) return;
    String scope = scopeByGroup.getOrDefault(domain, GrantPermissions.SCOPE_SELF);
    groups.add(new GlobalSearchGroup(domain, label, hits, scope, scopeNote(domain, scope)));
  }

  /** 同一业务域由多个子域组成时，取**更严**的一侧。 */
  private static String narrower(String left, String right) {
    return rank(left) >= rank(right) ? left : right;
  }

  private static int rank(String scope) {
    if (GrantPermissions.SCOPE_SELF.equals(scope)) return 3;
    if (GrantPermissions.SCOPE_DEPARTMENT.equals(scope)) return 2;
    if (GrantPermissions.SCOPE_ALL.equals(scope) || GrantPermissions.SCOPE_TENANT.equals(scope)) return 1;
    return 0;
  }

  /** 数据范围说明文案：界面上要讲清「这一组到底能看到多大范围」。 */
  private static String scopeNote(String domain, String scope) {
    boolean purchase = "PURCHASE".equals(domain);
    String subject = purchase ? "采购单" : "销售订单";
    if (GrantPermissions.SCOPE_ALL.equals(scope)) {
      return subject + "在你的授权范围内全租户可见。";
    }
    if (GrantPermissions.SCOPE_DEPARTMENT.equals(scope)) {
      return subject + "只返回本部门负责的单据。";
    }
    if (GrantPermissions.SCOPE_SELF.equals(scope)) {
      return purchase
          ? "采购单只返回你负责的单据。"
          : "销售订单没有部门维度，范围退化为「仅本人经手过」（sales_order_status_history.actor_id = 本人）。";
    }
    return subject + "的数据范围以你的角色配置为准。";
  }

  /** 会话是否具备该域的读权限。判定规则与各服务 {@code AccessService.require} 同构。 */
  private boolean permits(AccessService.Access actor, String permission) {
    return GrantPermissions.permits(grantsOf(actor), permission, actor.departmentId() != null);
  }

  /** 各服务 {@code AccessService.Grant} → 共享判定用的 {@code GrantPermissions.Grant}。 */
  private static List<GrantPermissions.Grant> grantsOf(AccessService.Access actor) {
    return actor.grants() == null
        ? List.of()
        : actor.grants().stream()
            .map(g -> new GrantPermissions.Grant(g.roleCode(), g.scopeMode(), g.permissions()))
            .toList();
  }

  /** 关键词归一化：去首尾空白；少于 2 个字符直接 400。 */
  static String normalize(String rawKeyword) {
    String keyword = rawKeyword == null ? "" : rawKeyword.trim();
    if (keyword.length() < MIN_KEYWORD_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "搜索关键词至少需要 " + MIN_KEYWORD_LENGTH + " 个字符");
    }
    return keyword;
  }

  /** LIKE 通配符转义（! 是转义符）。 */
  static String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  /** 金额副信息：币种 + 金额（BigDecimal，不走浮点）。没有金额时只报币种。 */
  private static String amount(Record r, String currencyColumn, String amountColumn) {
    String currency = r.get(currencyColumn, String.class);
    BigDecimal amount = r.get(amountColumn, BigDecimal.class);
    if (amount == null) return currency == null ? "" : currency;
    return currency == null ? amount.toPlainString() : currency + " " + amount.toPlainString();
  }

  private static String join(String first, String second) {
    String a = first == null ? "" : first.trim();
    String b = second == null ? "" : second.trim();
    if (a.isEmpty()) return b.isEmpty() ? null : b;
    if (b.isEmpty()) return a;
    return a + " · " + b;
  }
}
