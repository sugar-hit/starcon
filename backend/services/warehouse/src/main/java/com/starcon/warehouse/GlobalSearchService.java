package com.starcon.warehouse;

import static com.starcon.warehouse.GlobalSearchModels.*;

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
 * 1.2.1.1 全局搜索框 —— warehouse 侧的只读检索（仓库 + 物流单号）。
 *
 * <p><b>本类是读投影，不是写入者。</b>{@code warehouse} 的写入所有权属于 8.1.1.1 仓库档案；
 * {@code shipment_handover} / {@code inventory_transfer} / {@code goods_receipt} 的写入所有权
 * 属于 8.3.4.2 交接、7.5.1.1 调拨、8.2.1.1 收货单。本类只读，一行都不写。
 *
 * <p><b>物流单号有三个真实出处</b>，它们不是同一张表的三个别名，而是三件不同的事：
 *
 * <ul>
 *   <li>{@code shipment_handover.master_waybill_no} —— <b>发运</b>：这一整次交接对外的运单号（8.3.4.2）；
 *   <li>{@code inventory_transfer.tracking_no} —— <b>在途</b>：这批货现在在哪（7.5.1.1 / V63 加列）；
 *   <li>{@code goods_receipt.waybill_no} —— <b>收货</b>：承运商把这批货送来的那个单号（8.2.1.1）。
 * </ul>
 *
 * 蓝图把「物流单号」写成一个域，但一个号落在哪张表决定了它是什么货 —— 所以副信息里
 * 一定带出**单据类型**，界面才不至于把「在途腿的运单号」显示成「已发货的运单号」。
 *
 * <p><b>数据范围</b>：warehouse 侧的 RLS 是<b>租户级</b>（没有 owner / department 列的表就没有域级范围），
 * 所以可见性就是「本租户的全部」。本模块<b>不</b>自己造一套范围算法。
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

  /** 仓库档案读权限码（warehouse 侧已注册：'warehouse.facilities' 页面 + read 动作）。 */
  static final String PERM_WAREHOUSE = "warehouse.facilities.read";

  private final DSLContext sql;
  private final AccessService access;

  public GlobalSearchService(DSLContext sql, AccessService access) {
    this.sql = sql;
    this.access = access;
  }

  // ================================================================ 检索

  /** 仓库 + 物流单号的前缀检索。 */
  public GlobalSearchResponse search(AccessService.Access actor, String rawKeyword, int limit) {
    String keyword = normalize(rawKeyword);
    int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
    String prefix = escapeLike(keyword) + "%";

    Map<String, List<GlobalSearchHit>> byDomain = new LinkedHashMap<>();
    List<String> unavailable = new ArrayList<>();

    if (permits(actor, PERM_WAREHOUSE)) {
      List<GlobalSearchHit> hits =
          access.run(
              actor,
              PERM_WAREHOUSE,
              () -> {
                List<GlobalSearchHit> all = new ArrayList<>();
                all.addAll(warehouseHits(prefix, size));
                all.addAll(logisticsHits(prefix, size));
                return all;
              });
      for (GlobalSearchHit hit : hits) {
        byDomain.computeIfAbsent(hit.domain(), k -> new ArrayList<>()).add(hit);
      }
    } else {
      unavailable.add("WAREHOUSE");
      unavailable.add("LOGISTICS");
    }

    List<GlobalSearchGroup> groups = new ArrayList<>();
    addGroup(groups, "WAREHOUSE", "仓库", byDomain);
    addGroup(groups, "LOGISTICS", "物流", byDomain);

    return new GlobalSearchResponse(keyword, MATCH_MODE, MIN_KEYWORD_LENGTH, groups, unavailable);
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些域接通了、依据是哪张表哪一列、哪些域拿不到及其归属模块。 */
  public GlobalSearchCapabilities capabilities() {
    List<GlobalSearchDomain> domains =
        List.of(
            new GlobalSearchDomain(
                "WAREHOUSE",
                "仓库",
                true,
                "08 仓储作业（8.1.1.1 仓库档案）",
                "warehouse.code / warehouse.name（仓库编码或名称前缀）",
                MATCH_MODE,
                "仓库是货权的物理落点；副信息带国家与状态。"
                    + "「仓库」这一组在本服务是租户级的（warehouse 表没有 owner / department 列，"
                    + "可见性按租户隔离）。"),
            new GlobalSearchDomain(
                "LOGISTICS",
                "物流单号",
                true,
                "08.3.4.2 交接 / 7.5.1.1 调拨 / 08.2.1.1 收货单",
                "shipment_handover.master_waybill_no（发运）、"
                    + "inventory_transfer.tracking_no（在途）、"
                    + "goods_receipt.waybill_no（收货），三者都是前缀匹配",
                MATCH_MODE,
                "三个运单号来自三件不同的事（发运 / 在途 / 收货），"
                    + "所以每条命中都带**单据类型**与所属单号，副信息里写明它是什么货。"));
    List<String> notes =
        List.of(
            "只做前缀 / 精确匹配，不做全文语义检索。",
            "关键词少于 " + MIN_KEYWORD_LENGTH + " 个字符时后端直接 400（前端也挡一道，后端是权威）。",
            "可见性按租户隔离（warehouse 侧 RLS 是租户级，本服务不另立域级范围算法）。");
    return new GlobalSearchCapabilities(domains, notes);
  }

  // ================================================================ 内部

  /** 仓库命中。主标识 = 仓库编码；副信息 = 仓库名 · 国家 · 状态。 */
  private List<GlobalSearchHit> warehouseHits(String prefix, int size) {
    List<GlobalSearchHit> hits = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select w.id, w.code, w.name, w.country_code, w.status"
                + " from warehouse w"
                + " where (w.code ilike ? escape '!' or w.name ilike ? escape '!')"
                + " order by w.code"
                + " limit ?",
            prefix,
            prefix,
            size)) {
      hits.add(
          new GlobalSearchHit(
              "WAREHOUSE",
              "WAREHOUSE",
              r.get("id", UUID.class),
              r.get("code", String.class),
              join(
                  r.get("name", String.class),
                  join(r.get("country_code", String.class), r.get("status", String.class)))));
    }
    return hits;
  }

  /**
   * 物流单号命中：发运 / 在途 / 收货三处合成一个域。
   *
   * <p>一条 SQL 用 {@code union all} 拼三段（每段一个来源），而不是三条查询再在 Java 里拼 ——
   * 这样「最多返回多少条」是<b>对整个域</b>生效的，不是每段各返回 N 条。
   */
  private List<GlobalSearchHit> logisticsHits(String prefix, int size) {
    List<GlobalSearchHit> hits = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select h.id, h.handover_code as doc_no, h.master_waybill_no as ref,"
                + " 'SHIPMENT_HANDOVER' as kind, h.carrier_name as carrier, h.status as doc_status"
                + " from shipment_handover h"
                + " where h.master_waybill_no ilike ? escape '!'"
                + " union all"
                + " select t.id, coalesce(nullif(t.source_reference,''), t.transit_kind) as doc_no,"
                + " t.tracking_no as ref, 'IN_TRANSIT' as kind, t.carrier_name as carrier,"
                + " t.status as doc_status"
                + " from inventory_transfer t"
                + " where t.tracking_no ilike ? escape '!'"
                + " union all"
                + " select g.id, g.receipt_code as doc_no, g.waybill_no as ref,"
                + " 'GOODS_RECEIPT' as kind, g.carrier as carrier, g.status as doc_status"
                + " from goods_receipt g"
                + " where g.waybill_no ilike ? escape '!'"
                + " order by ref"
                + " limit ?",
            prefix,
            prefix,
            prefix,
            size)) {
      hits.add(
          new GlobalSearchHit(
              "LOGISTICS",
              r.get("kind", String.class),
              r.get("id", UUID.class),
              r.get("ref", String.class),
              join(docLabel(r.get("kind", String.class)) + " " + r.get("doc_no", String.class),
                  r.get("carrier", String.class))));
    }
    return hits;
  }

  /** 单据类型的中文名。副信息里必须写明「这是什么货的运单号」。 */
  private static String docLabel(String kind) {
    if ("SHIPMENT_HANDOVER".equals(kind)) return "发运交接";
    if ("IN_TRANSIT".equals(kind)) return "在途";
    if ("GOODS_RECEIPT".equals(kind)) return "收货";
    return kind;
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
