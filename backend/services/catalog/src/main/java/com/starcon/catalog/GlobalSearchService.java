package com.starcon.catalog;

import static com.starcon.catalog.GlobalSearchModels.*;

import com.starcon.tenancy.GrantPermissions;
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
 * 1.2.1.1 全局搜索框 —— catalog 侧的只读检索。
 *
 * <p><b>本类是读投影，不是写入者。</b>它只读 {@code sku} / {@code spu} / {@code party} / {@code party_role}，
 * 一行都不写；商品与往来单位的写入所有权仍在 3.1.x / 3.6.x 那些模块手里。
 *
 * <p><b>为什么客户与供应商落在 catalog 而不是 crm</b>：蓝图的「关系」句写的是「上游 = 各系统只读检索索引」，
 * 而本仓<b>没有买家档案</b> —— crm 的 {@code crm_customer_contact.customer_ref} 迁移注释里写死了
 * 「本仓**没有买家档案**（15.3.1.1 客户列表未交付）⇒ customer_ref 只是逻辑引用，不是主数据」。
 * 客户与供应商的**权威主数据**是 3.6.1.1 的 {@code party} + {@code party_role}（角色 CUSTOMER / SUPPLIER），
 * 它在 catalog 的库里。跨服务去 crm 查一份接触记录里的姓名快照当「客户档案」，
 * 拿到的会是一份可能过期的副本 —— 那正是本模块不能干的语义造假。
 *
 * <p><b>匹配方式：前缀（{@code ilike 'kw%'}），不做全文语义检索</b>。蓝图把这一条写死了：
 * 「只做前缀/精确匹配，不做全文语义检索；模糊搜索在各自系统列表内完成」。
 * 所以这里<b>不</b>上 trigram / 全文索引 / ES：仓库里 {@code party_code_trgm} / {@code party_name_trgm}
 * 两条 GIN 索引是 3.6.1.1 为<b>列表页</b>的模糊筛选建的，本模块不复用它的模糊语义。
 *
 * <p><b>租户与数据范围</b>：
 *
 * <ul>
 *   <li>租户<b>不</b>来自任何请求头 —— 由 {@code AccessService} 向 identity 确认已认证成员关系后绑定
 *       {@code app.current_tenant}，由 RLS 在数据库层强制隔离。伪造 {@code X-Tenant-Id} 头改不动它。
 *   <li>数据范围由 catalog 既有的 {@code catalog_scope(permission, owner_id, department_id)}
 *       在 RLS 层强制：<b>每个域用各自的权限码开一次事务</b>（{@code app.permission} 是事务级 GUC，
 *       一个事务里只能有一个值）。SKU 继承其 SPU 的归属；SPU 按自己的 owner / department；
 *       {@code party} 没有归属列（往来单位是租户级主数据），因此只按租户隔离。
 * </ul>
 *
 * <p><b>「没权限的域」与「没有数据的域」必须分得开</b>：前者进 {@code unavailableDomains}，
 * 后者就是不返回该组。把两者混为一谈，界面就会把「你没权限看客户」显示成「这个租户没有客户」。
 */
@Service
public class GlobalSearchService {

  /** 蓝图写死的最小关键词长度：少于 2 个字符不检索（前端也挡一道，后端是权威）。 */
  public static final int MIN_KEYWORD_LENGTH = 2;

  /** 匹配方式标识。与蓝图「只做前缀/精确匹配」逐字对应。 */
  public static final String MATCH_MODE = "PREFIX_OR_EXACT";

  private static final int MAX_LIMIT = 50;

  /** 商品档案（SPU）读权限码：catalog 已有的权限码，本模块**不新增**权限。 */
  static final String PERM_SPU = "catalog.spu.read";

  /** SKU 读权限码。 */
  static final String PERM_SKU = "catalog.sku.read";

  private final DSLContext sql;
  private final AccessService access;

  public GlobalSearchService(DSLContext sql, AccessService access) {
    this.sql = sql;
    this.access = access;
  }

  // ================================================================ 检索

  /**
   * 跨商品与往来单位主数据的只读检索。
   *
   * @param actor 已认证的会话（租户与授权都来自它，不来自请求参数）
   * @param rawKeyword 用户键入的关键词
   * @param limit 单域最多返回多少条
   */
  public GlobalSearchResponse search(AccessService.Access actor, String rawKeyword, int limit) {
    String keyword = normalize(rawKeyword);
    int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
    // 前缀匹配：'kw%'。刻意**不是** '%kw%' —— 蓝图写死「只做前缀/精确匹配」。
    String prefix = escapeLike(keyword) + "%";

    Map<String, List<GlobalSearchHit>> byDomain = new LinkedHashMap<>();
    List<String> unavailable = new ArrayList<>();

    // ---- SKU（03 商品主数据）----
    if (permits(actor, PERM_SKU)) {
      List<GlobalSearchHit> hits =
          access.run(actor, PERM_SKU, () -> skuHits(prefix, size));
      byDomain.computeIfAbsent("PRODUCT", k -> new ArrayList<>()).addAll(hits);
    } else {
      unavailable.add("PRODUCT_SKU");
    }

    // ---- SPU（03 商品主数据）----
    if (permits(actor, PERM_SPU)) {
      List<GlobalSearchHit> hits =
          access.run(actor, PERM_SPU, () -> spuHits(prefix, size));
      byDomain.computeIfAbsent("PRODUCT", k -> new ArrayList<>()).addAll(hits);
    } else {
      unavailable.add("PRODUCT_SPU");
    }

    // ---- 客户（3.6.1.1 往来单位 · CUSTOMER 角色）----
    // party 的读权限码沿用 PartyController 的既有用法：catalog.spu.read。
    if (permits(actor, PERM_SPU)) {
      List<GlobalSearchHit> hits =
          access.run(actor, PERM_SPU, () -> partyHits("CUSTOMER", "客户", prefix, size));
      byDomain.computeIfAbsent("CUSTOMER", k -> new ArrayList<>()).addAll(hits);
    } else {
      unavailable.add("CUSTOMER");
    }

    // ---- 供应商（3.6.1.1 往来单位 · SUPPLIER 角色）----
    if (permits(actor, PERM_SPU)) {
      List<GlobalSearchHit> hits =
          access.run(actor, PERM_SPU, () -> partyHits("SUPPLIER", "供应商", prefix, size));
      byDomain.computeIfAbsent("SUPPLIER", k -> new ArrayList<>()).addAll(hits);
    } else {
      unavailable.add("SUPPLIER");
    }

    List<GlobalSearchGroup> groups = new ArrayList<>();
    addGroup(groups, "PRODUCT", "商品", byDomain);
    addGroup(groups, "CUSTOMER", "客户", byDomain);
    addGroup(groups, "SUPPLIER", "供应商", byDomain);

    return new GlobalSearchResponse(keyword, MATCH_MODE, MIN_KEYWORD_LENGTH, groups, unavailable);
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：哪些域真的接通了、依据是哪张表哪一列、以及哪些域在本服务**拿不到**。
   *
   * <p>拿不到的域一律 {@code available=false} + ownerModule + impact，
   * <b>不建占位表、不返回空集冒充成功</b>（仓库既有约定，见 8.3.1.2 / 9.1.1.1）。
   */
  public GlobalSearchCapabilities capabilities() {
    List<GlobalSearchDomain> domains =
        List.of(
            new GlobalSearchDomain(
                "PRODUCT",
                "商品（SKU / SPU）",
                true,
                "03 商品主数据（3.1.x / 3.2.x）",
                "sku.code（SKU 编码）、spu.code（SPU 编码）；两者都是前缀匹配 ilike 'kw%'",
                MATCH_MODE,
                "SKU 与 SPU 都在 catalog 库里，各查一次后合并成一组「商品」；"
                    + "命中的 kind 字段区分 SKU 与 SPU。"),
            new GlobalSearchDomain(
                "CUSTOMER",
                "客户",
                true,
                "3.6.1.1 往来单位（party + party_role）",
                "party.code / party.name（往来单位编码或名称前缀），"
                    + "经 party_role.role_type='CUSTOMER' 且 role.status='ACTIVE' 过滤",
                MATCH_MODE,
                "客户档案的权威副本是 catalog 的 party；crm 侧只有接触记录里的姓名快照"
                    + "（crm_customer_contact.customer_ref 迁移注释写明「本仓没有买家档案」），"
                    + "拿它当客户档案会让界面显示一份可能过期的副本。"),
            new GlobalSearchDomain(
                "SUPPLIER",
                "供应商",
                true,
                "3.6.1.1 往来单位（party + party_role）",
                "party.code / party.name（往来单位编码或名称前缀），"
                    + "经 party_role.role_type='SUPPLIER' 且 role.status='ACTIVE' 过滤",
                MATCH_MODE,
                "供应商主数据同样在 catalog 的 party 里（trade 侧的采购单只存 supplier_ref_id 逻辑引用"
                    + "与 supplier_name 快照），所以供应商搜索也在本服务。"),
            new GlobalSearchDomain(
                "LISTING",
                "平台刊登",
                false,
                "04 刊登与内容系统（4.x，channel 服务）",
                null,
                MATCH_MODE,
                "平台刊登（listing）在 **channel** 服务的库里，不在 catalog。"
                    + "本服务不跨库查 —— 跨服务只能走授权 API。"
                    + "渠道侧搜索由 channel 服务的同名端点提供，前端合并。"),
            new GlobalSearchDomain(
                "BARCODE",
                "条码 / 箱唛",
                false,
                "03 商品主数据（3.2.x 条码规则）",
                null,
                MATCH_MODE,
                "sku.barcode 与 sku_identifier 存在，但**本模块不按条码检索**："
                    + "条码列没有可走前缀的索引，按它检索会对整张 sku 表做顺序扫描，"
                    + "而一次全局搜索要打五个服务。条码请到 SKU 详情页的条码框里查。"));
    List<String> notes =
        List.of(
            "只做前缀 / 精确匹配，不做全文语义检索 —— 模糊检索请到各系统自己的列表页。",
            "关键词少于 " + MIN_KEYWORD_LENGTH + " 个字符时后端直接 400（前端也挡一道，后端是权威）。",
            "每个域用各自的权限码开事务，app.permission 是事务级 GUC，一个事务里只能有一个值。");
    return new GlobalSearchCapabilities(domains, notes);
  }

  // ================================================================ 内部

  /** SKU 命中。主标识 = SKU 编码；副信息 = SKU 名称 + 所属 SPU 编码。 */
  private List<GlobalSearchHit> skuHits(String prefix, int size) {
    List<GlobalSearchHit> hits = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select s.id, s.code, s.name, s.status, p.code as spu_code"
                + " from sku s"
                + " join spu p on p.tenant_id = s.tenant_id and p.id = s.spu_id"
                + " where s.code ilike ? escape '!'"
                + " order by s.code"
                + " limit ?",
            prefix,
            size)) {
      hits.add(
          new GlobalSearchHit(
              "PRODUCT",
              "SKU",
              r.get("id", UUID.class),
              r.get("code", String.class),
              join(r.get("name", String.class), "SPU " + nullToEmpty(r.get("spu_code", String.class)))));
    }
    return hits;
  }

  /** SPU 命中。主标识 = SPU 编码；副信息 = SPU 名称 + 品牌。 */
  private List<GlobalSearchHit> spuHits(String prefix, int size) {
    List<GlobalSearchHit> hits = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select p.id, p.code, p.name, p.status, p.brand"
                + " from spu p"
                + " where p.code ilike ? escape '!'"
                + " order by p.code"
                + " limit ?",
            prefix,
            size)) {
      hits.add(
          new GlobalSearchHit(
              "PRODUCT",
              "SPU",
              r.get("id", UUID.class),
              r.get("code", String.class),
              join(r.get("name", String.class), r.get("brand", String.class))));
    }
    return hits;
  }

  /**
   * 往来单位命中（客户 / 供应商同一条查询，角色不同）。
   *
   * <p>前缀匹配的是<b>编码或名称</b>：往来单位对使用者来说的「标识」是名字
   * （蓝图对店铺写的就是「店铺名」），只匹配编码的话输入「北京」会一条都搜不到。
   */
  private List<GlobalSearchHit> partyHits(String roleType, String label, String prefix, int size) {
    List<GlobalSearchHit> hits = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select p.id, p.code, p.name, p.status, p.country_code"
                + " from party p"
                + " join party_role r on r.tenant_id = p.tenant_id and r.party_id = p.id"
                + " where r.role_type = ? and r.status = 'ACTIVE'"
                + "   and p.deleted_at is null"
                + "   and (p.code ilike ? escape '!' or p.name ilike ? escape '!')"
                + " order by p.code"
                + " limit ?",
            roleType,
            prefix,
            prefix,
            size)) {
      hits.add(
          new GlobalSearchHit(
              roleType,
              "PARTY_" + roleType,
              r.get("id", UUID.class),
              r.get("code", String.class),
              join(r.get("name", String.class), r.get("country_code", String.class))));
    }
    return hits;
  }

  /** 域 → 分组。空组不返回。 */
  private static void addGroup(
      List<GlobalSearchGroup> groups,
      String domain,
      String label,
      Map<String, List<GlobalSearchHit>> byDomain) {
    List<GlobalSearchHit> hits = byDomain.get(domain);
    if (hits == null || hits.isEmpty()) return;
    groups.add(new GlobalSearchGroup(domain, label, hits));
  }

  /** 会话是否具备该域的读权限。判定规则与各服务 {@code AccessService.require} 同构。 */
  private boolean permits(AccessService.Access actor, String permission) {
    List<GrantPermissions.Grant> grants =
        actor.grants() == null
            ? List.of()
            : actor.grants().stream()
                .map(g -> new GrantPermissions.Grant(g.roleCode(), g.scopeMode(), g.permissions()))
                .toList();
    return GrantPermissions.permits(grants, permission, actor.departmentId() != null);
  }

  /** 关键词归一化：去首尾空白；少于 2 个字符直接 400（蓝图写死的约束，后端是权威）。 */
  static String normalize(String rawKeyword) {
    String keyword = rawKeyword == null ? "" : rawKeyword.trim();
    if (keyword.length() < MIN_KEYWORD_LENGTH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "搜索关键词至少需要 " + MIN_KEYWORD_LENGTH + " 个字符");
    }
    return keyword;
  }

  /** LIKE 通配符转义（! 是转义符，与 3.1.1 列表页同一写法）。 */
  static String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private static String join(String first, String second) {
    String a = first == null ? "" : first.trim();
    String b = second == null ? "" : second.trim();
    if (a.isEmpty()) return b.isEmpty() ? null : b;
    if (b.isEmpty()) return a;
    return a + " · " + b;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
