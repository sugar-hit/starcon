package com.starcon.channel;

import static com.starcon.channel.GlobalSearchModels.*;

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
 * 1.2.1.1 全局搜索框 —— channel 侧的只读检索（店铺）。
 *
 * <p><b>本类是读投影，不是写入者。</b>{@code shop} 的写入所有权属于 2.3.1.1 店铺注册；
 * 本类只读，一行都不写。
 *
 * <p><b>数据范围由 channel 既有的 RLS 强制</b>：{@code shop} 上有 {@code owner_id} /
 * {@code department_id} 两列与 {@code data_scope} 策略（{@code channel_scope(permission, owner, dept)}），
 * 所以只要用 {@code channel.shop.read} 开事务，行级可见性就在数据库层收好了 ——
 * 与 2.3.x 店铺端点<b>同一套</b>，本模块不另立一套范围算法。
 *
 * <p><b>匹配方式：前缀</b>（{@code ilike 'kw%'}）。店铺对使用者来说的标识是<b>店名</b>
 * （蓝图写的就是「店铺名」），所以编码与店名都参与前缀匹配；只匹配编码的话输入「北京」会一条都搜不到。
 *
 * <p><b>租户绝不来自请求头</b>：由 {@code AccessService} 向 identity 确认已认证成员关系后绑定
 * {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 */
@Service
public class GlobalSearchService {

  /** 蓝图写死的最小关键词长度。 */
  public static final int MIN_KEYWORD_LENGTH = 2;

  /** 匹配方式标识。 */
  public static final String MATCH_MODE = "PREFIX_OR_EXACT";

  private static final int MAX_LIMIT = 50;

  /** 店铺读权限码（channel 侧已注册：'channel.shop' 页面 + read 动作）。 */
  static final String PERM_SHOP = "channel.shop.read";

  private final DSLContext sql;
  private final AccessService access;

  public GlobalSearchService(DSLContext sql, AccessService access) {
    this.sql = sql;
    this.access = access;
  }

  // ================================================================ 检索

  /** 店铺前缀检索。 */
  public GlobalSearchResponse search(AccessService.Access actor, String rawKeyword, int limit) {
    String keyword = normalize(rawKeyword);
    int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
    String prefix = escapeLike(keyword) + "%";

    Map<String, List<GlobalSearchHit>> byDomain = new LinkedHashMap<>();
    List<String> unavailable = new ArrayList<>();

    if (permits(actor, PERM_SHOP)) {
      List<GlobalSearchHit> hits = access.run(actor, PERM_SHOP, () -> shopHits(prefix, size));
      byDomain.computeIfAbsent("SHOP", k -> new ArrayList<>()).addAll(hits);
    } else {
      unavailable.add("SHOP");
    }

    List<GlobalSearchGroup> groups = new ArrayList<>();
    addGroup(groups, "SHOP", "店铺", byDomain);

    return new GlobalSearchResponse(keyword, MATCH_MODE, MIN_KEYWORD_LENGTH, groups, unavailable);
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些域接通了、依据是哪张表哪一列、哪些域拿不到及其归属模块。 */
  public GlobalSearchCapabilities capabilities() {
    List<GlobalSearchDomain> domains =
        List.of(
            new GlobalSearchDomain(
                "SHOP",
                "店铺",
                true,
                "02 渠道与店铺（2.3.1.1 店铺注册）",
                "shop.code / shop.name（店铺编码或店名前缀），副信息带所属平台（platform.code）",
                MATCH_MODE,
                "店铺与刊登是两件事：刊登（listing）在 channel 的另一张表里，"
                    + "它的检索口径由 04 刊登与内容系统的同名端点提供，本模块不混进来。"),
            new GlobalSearchDomain(
                "LISTING",
                "平台刊登",
                false,
                "04 刊登与内容系统（4.x，channel 服务）",
                null,
                MATCH_MODE,
                "listing 的**可检索主标识**是平台侧商品 id / ASIN，"
                    + "它由 04 刊登与内容系统（listing 模块）定义，"
                    + "本模块不跨模块拼一个假的标题检索出来。"),
            new GlobalSearchDomain(
                "BUYER",
                "平台买家",
                false,
                "02.x 买家档案（channel 侧 buyer 域）",
                null,
                MATCH_MODE,
                "平台买家号（buyer id）是**平台侧**的买家标识，"
                    + "而 15 客户域的客户档案在本仓还没有交付（crm 侧只有接触记录），"
                    + "所以买家号检索由 02 的买家域端点负责，不在这里冒充「客户」。"));
    List<String> notes =
        List.of(
            "只做前缀 / 精确匹配，不做全文语义检索。",
            "关键词少于 " + MIN_KEYWORD_LENGTH + " 个字符时后端直接 400（前端也挡一道，后端是权威）。",
            "数据范围由 shop 表上的 data_scope RLS 策略强制（channel_scope），与 2.3.x 店铺端点同一套。");
    return new GlobalSearchCapabilities(domains, notes);
  }

  // ================================================================ 内部

  /** 店铺命中。主标识 = 店铺编码；副信息 = 店名 · 平台 · 状态。 */
  private List<GlobalSearchHit> shopHits(String prefix, int size) {
    List<GlobalSearchHit> hits = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select s.id, s.code, s.name, s.status, s.platform_shop_id, p.code as platform_code"
                + " from shop s"
                + " join platform p on p.id = s.platform_id"
                + " where (s.code ilike ? escape '!' or s.name ilike ? escape '!')"
                + " order by s.code"
                + " limit ?",
            prefix,
            prefix,
            size)) {
      hits.add(
          new GlobalSearchHit(
              "SHOP",
              "SHOP",
              r.get("id", UUID.class),
              r.get("code", String.class),
              join(r.get("name", String.class), r.get("platform_code", String.class))));
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

  private static String join(String first, String second) {
    String a = first == null ? "" : first.trim();
    String b = second == null ? "" : second.trim();
    if (a.isEmpty()) return b.isEmpty() ? null : b;
    if (b.isEmpty()) return a;
    return a + " · " + b;
  }
}
