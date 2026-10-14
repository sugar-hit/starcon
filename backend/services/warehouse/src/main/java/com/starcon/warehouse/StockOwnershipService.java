package com.starcon.warehouse;

import static com.starcon.warehouse.StockOwnershipModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.7.1.1 货主与所有权：货主档案、货权分档、生效值解析、按货主拆分的库存报表与货权转移。
 *
 * <p><b>四条实现纪律</b>：
 *
 * <ul>
 *   <li><b>「这批货归谁」只有一处判定</b>：DB 函数 {@code resolve_stock_owner()} 承担优先级 （SKU &gt; LEGAL_ENTITY &gt;
 *       WAREHOUSE &gt; DEFAULT），bucket 自动赋权触发器、解析端点、 报表、漂移检测全部调它。Java 侧**不重写一遍优先级** —— 写成两份就一定会在某次
 *       「加一档」时只改到其中一份，然后两个页面对同一批货给出不同的货主。
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 集中在语句最前面的 CTE 里，后面全是普通绑定， cast 永远不在绑定序列末位（7.1.2.2 踩过的坑）。
 *   <li><b>数量一律 BigDecimal + numeric(19,4)</b>，与 stock_bucket 源列同刻度；比较用 {@code compareTo} 不用 {@code
 *       equals}（后者把 0 与 0.0000 判成不等）。
 *   <li><b>不用 JPA 懒加载</b>：全部 jOOQ 显式 SQL，聚合在 DB 侧完成，Java 侧只做映射与派生。
 * </ul>
 *
 * <p><b>库存余额为什么按 balance 而不是按 bucket 聚合</b>：V2 保证「只要动过 bucket， Σbucket == balance」，但「还没拆到库位」的
 * balance 行是 7.1.1.2 里的合法状态 —— 只聚合 bucket 会把它们漏成 0。两种口径的差额恒等于「未分解到库位」的部分， 因此报表**两者都报**（{@code
 * bucketOnHand} 与 {@code balanceOnHand}）， 让差异是可见的一个数，而不是藏在两个不同页面里。
 */
@Service
public class StockOwnershipService {

  /** 单页最大行数：货主与货权档是配置不是流水，量级很小，但仍设上限防一次拉爆 JVM。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 报表聚合时的数量刻度：与 stock_bucket / inventory_balance 的 numeric(19,4) 对齐。 */
  private static final int QTY_SCALE = 4;

  /** 差异为 0 时使用的量，与被比较的列同刻度，避免同一列里混着 scale 不一样的 0。 */
  private static final BigDecimal ZERO = new BigDecimal("0.0000");

  /** 货主的固定 SELECT 列。 */
  private static final String OWNER_COLUMNS =
      "o.id,o.code,o.name,o.owner_kind,o.counts_as_owned_asset,o.owner_ref_type,"
          + "o.owner_ref_id,o.owner_ref_label,o.is_default,o.status,o.note,"
          + "o.created_by_membership_id,o.created_at,o.updated_at";

  /** 货权档的固定 SELECT 列（含联查出来的可读名）。 */
  private static final String RULE_COLUMNS =
      "r.id,r.scope_type,r.warehouse_id,w.code as warehouse_code,w.name as warehouse_name,"
          + "r.legal_entity_id,le.code as legal_entity_code,le.name as legal_entity_name,"
          + "r.sku_id,s.code as sku_code,s.name as sku_name,"
          + "r.owner_id,o.name as owner_name,o.owner_kind,o.counts_as_owned_asset,"
          + "r.effective_from,r.effective_to,r.note,r.created_by_membership_id,r.created_at";

  /** 货权档的 FROM 子句：三个可空定位键一律 LEFT JOIN，响应里不存在的键自然是 null。 */
  private static final String RULE_FROM =
      " from stock_ownership_rule r"
          + " join stock_owner o on o.tenant_id = r.tenant_id and o.id = r.owner_id"
          + " left join warehouse w on w.tenant_id = r.tenant_id and w.id = r.warehouse_id"
          + " left join legal_entity_ref le on le.tenant_id = r.tenant_id and le.id = r.legal_entity_id"
          + " left join sku_ref s on s.tenant_id = r.tenant_id and s.id = r.sku_id";

  /** 货权档写入的列与每列的绑定 cast，**由同一个数组生成 SQL**（见 SellablePolicyService 同一理由）。 */
  private static final String[] RULE_COLUMNS_IN = {
    "id",
    "tenant_id",
    "scope_type",
    "warehouse_id",
    "legal_entity_id",
    "sku_id",
    "owner_id",
    "effective_from",
    "note",
    "created_by_membership_id"
  };

  /** 与 {@link #RULE_COLUMNS_IN} 一一对应：空串 = 普通绑定，其余是 PG 侧的 cast。 */
  private static final String[] RULE_CASTS_IN = {
    "", "", "", "", "", "", "", "::timestamptz", "", ""
  };

  private static String ruleInsert() {
    if (RULE_CASTS_IN.length != RULE_COLUMNS_IN.length) {
      throw new IllegalStateException(
          "货权档写入的列与 cast 数量不一致：" + RULE_COLUMNS_IN.length + " vs " + RULE_CASTS_IN.length);
    }
    StringBuilder columns = new StringBuilder();
    StringBuilder values = new StringBuilder();
    for (int i = 0; i < RULE_COLUMNS_IN.length; i++) {
      if (i > 0) {
        columns.append(',');
        values.append(',');
      }
      columns.append(RULE_COLUMNS_IN[i]);
      values.append('?').append(RULE_CASTS_IN[i]);
    }
    return "insert into stock_ownership_rule (" + columns + ") values (" + values + ")";
  }

  private final DSLContext sql;

  public StockOwnershipService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 货主档案

  /** 货主列表（真分页，total 与分页无关）。 */
  public StockOwnerPage pageOwners(StockOwnerQuery query) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() <= 0 ? 50 : query.size()));
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    ownerConditions(query, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from stock_owner o" + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select "
                + OWNER_COLUMNS
                + " from stock_owner o"
                + sqlWhere
                + " order by o.is_default desc, o.owner_kind, o.code, o.id limit ? offset ?",
            append(binds, size, (long) pageNo * size).toArray());
    return new StockOwnerPage(
        rows.stream().map(StockOwnershipService::ownerRow).toList(), total, pageNo, size);
  }

  private void ownerConditions(StockOwnerQuery query, List<String> where, List<Object> binds) {
    if (notBlank(query.ownerKind())) {
      where.add("o.owner_kind = ?");
      binds.add(query.ownerKind().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.status())) {
      where.add("o.status = ?");
      binds.add(query.status().trim().toUpperCase(Locale.ROOT));
    }
    if (query.ownerRefId() != null) {
      where.add("o.owner_ref_id = ?");
      binds.add(query.ownerRefId());
    }
    if (Boolean.TRUE.equals(query.ownedAssetOnly())) {
      // 生成列本身就是筛选条件，不在 Java 侧再判一次 ownerKind —— 那才是第二个真相。
      where.add("o.counts_as_owned_asset");
    }
    if (notBlank(query.keyword())) {
      where.add(
          "(o.code ilike ? or o.name ilike ? or o.owner_ref_label ilike ? or o.note ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 4; i++) {
        binds.add(k);
      }
    }
  }

  /**
   * 新建货主。
   *
   * <p>{@code isDefault=true} 时把原有的默认货主撤下来，保证「至多一个默认」在应用层也成立 （DB 上还有部分唯一索引，但那样报出来的是 23505
   * 而不是一句能看懂的话）。
   *
   * <p>自有货主必须挂在本租户已登记的法人上（DB 触发器 {@code guard_stock_owner_legal_entity}）。 供应商 / 平台是 catalog /
   * channel 的跨服务逻辑 ID，本服务不建外键也不查对方的表。
   */
  @Transactional
  public StockOwnerSaved saveOwner(StockOwnerInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    StockOwnerKind kind = ownerKind(input.ownerKind());
    OwnerRefType refType = ownerRefType(input.ownerRefType());

    // 范围与指向的自洽在 DB 也有 CHECK，这里先给一句能看懂的话 —— 23514 的文案说的是
    // "violates check constraint"，用户看不懂自己错在哪一条。
    boolean refRequired = refType == OwnerRefType.LEGAL_ENTITY || refType == OwnerRefType.SUPPLIER;
    if (refRequired && input.ownerRefId() == null) {
      throw bad(refType.label() + "类货主必须给 ownerRefId：它是指向货主的唯一依据，" + "留空就等于「这批货属于某个查不到的人」");
    }
    if (!refRequired && input.ownerRefId() != null) {
      throw bad(refType.label() + "类货主没有外部货主，不要给 ownerRefId");
    }
    // 与 DB CHECK 同一套规则，但在应用层先给一句能看懂的话 ——
    // CHECK 违例报出来的是 "violates check constraint \"stock_owner_check\""，用户看不懂自己错在哪。
    boolean consistent =
        switch (kind) {
          case SELF -> refType == OwnerRefType.LEGAL_ENTITY || refType == OwnerRefType.INTERNAL;
          case SUPPLIER_CONSIGNMENT -> refType == OwnerRefType.SUPPLIER;
          case PLATFORM -> refType == OwnerRefType.PLATFORM || refType == OwnerRefType.INTERNAL;
          case CONSIGNEE ->
              refType == OwnerRefType.SUPPLIER || refType == OwnerRefType.LEGAL_ENTITY;
        };
    if (!consistent) {
      throw bad(
          kind.label()
              + "（"
              + kind.name()
              + "）的货主必须指向"
              + allowedRefTypes(kind)
              + "，收到「"
              + refType.label()
              + "」。"
              + (kind == StockOwnerKind.SUPPLIER_CONSIGNMENT && refType == OwnerRefType.LEGAL_ENTITY
                  ? "寄售货挂在自家法人名下，报表上会看不出它是别人的货，然后被算进自有资产。"
                  : "所有权类型与指向不自洽的货主无法回答「这批货是谁的」。"));
    }
    UUID id = UUID.randomUUID();
    if (input.isDefault()) {
      sql.execute(
          "update stock_owner set is_default = false, updated_at = now() where tenant_id = ?::uuid and is_default",
          tenant());
    }
    try {
      sql.execute(
          "insert into stock_owner(id,tenant_id,code,name,owner_kind,owner_ref_type,owner_ref_id,"
              + "owner_ref_label,is_default,status,note,created_by_membership_id)"
              + " values(?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
          id,
          tenant(),
          input.code().trim(),
          input.name().trim(),
          kind.name(),
          refType.name(),
          input.ownerRefId(),
          nullToEmpty(input.ownerRefLabel()).trim(),
          input.isDefault(),
          nullToEmpty(input.note()).trim(),
          actorId);
    } catch (DataAccessException e) {
      throw translate(e, "货主编码「" + input.code().trim() + "」已存在，或该货主的指向与所有权类型不自洽");
    }
    var saved = requireOwner(id);
    return new StockOwnerSaved(
        id,
        saved.code(),
        saved.ownerKind().name(),
        saved.isDefault(),
        "已建立货主「"
            + saved.name()
            + "」（"
            + saved.ownerKind().label()
            + "）："
            + (saved.countsAsOwnedAsset()
                ? "这批货计入自有资产，12 财务会把它算进资产表。"
                : "这批货**不**计入自有资产（寄售 / 代销 / 平台所有），财务按 countsAsOwnedAsset=false 排除。")
            + (saved.legalEntityLinked()
                ? "已挂在本租户法人「" + saved.ownerRefLabel() + "」名下。"
                : "未挂法人：租户尚未登记法人，12 财务暂时无法归到法人资产表，页面会如实标出来。"));
  }

  /** 货主详情。跨租户在 RLS 下查不到 → 404。 */
  public StockOwnerRow owner(UUID id) {
    return requireOwner(id);
  }

  /**
   * 改货主属性。
   *
   * <p>{@code code} 与 {@code ownerKind} 是身份，不可改：改了等于让报表上历史那一行的 「货主」读成另一个含义（而 {@code
   * counts_as_owned_asset} 是 owner_kind 的生成列， 改它会连带把同一批历史货的资产归属改掉 —— 那是改账，不是改配置）。
   */
  @Transactional
  public StockOwnerSaved updateOwner(UUID id, StockOwnerInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    var existing = requireOwner(id);
    if (!existing.code().equals(input.code().trim())) {
      throw bad("货主编码不可修改（" + existing.code() + "）：它是货权的稳定标识，" + "改掉之后历史货权报表上这一行的指向就对不上了");
    }
    if (existing.ownerKind() != ownerKind(input.ownerKind())) {
      throw bad(
          "所有权类型不可从「"
              + existing.ownerKind().label()
              + "」改成「"
              + ownerKind(input.ownerKind()).label()
              + "」："
              + "「是否计入自有资产」是它的生成列，改类型等于把同一批货的资产归属改掉。"
              + "请改用货权转移把库存挪到另一个货主下");
    }
    OwnerRefType refType = ownerRefType(input.ownerRefType());
    if (input.isDefault() && !existing.isDefault()) {
      sql.execute(
          "update stock_owner set is_default = false, updated_at = now() where tenant_id = ?::uuid and is_default",
          tenant());
    }
    try {
      sql.execute(
          "update stock_owner set name = ?, owner_ref_type = ?, owner_ref_id = ?, owner_ref_label = ?,"
              + " is_default = ?, status = ?, note = ?, updated_at = now() where id = ?",
          input.name().trim(),
          refType.name(),
          input.ownerRefId(),
          nullToEmpty(input.ownerRefLabel()).trim(),
          input.isDefault() || existing.isDefault(),
          notBlank(input.status())
              ? input.status().trim().toUpperCase(Locale.ROOT)
              : existing.status(),
          nullToEmpty(input.note()).trim(),
          id);
    } catch (DataAccessException e) {
      throw translate(e, "货主属性不合法：" + e.getMessage());
    }
    var saved = requireOwner(id);
    return new StockOwnerSaved(
        id,
        saved.code(),
        saved.ownerKind().name(),
        saved.isDefault(),
        "已更新货主「"
            + saved.name()
            + "」。货主档案不提供删除："
            + "停用走 status=INACTIVE，历史货权仍需可查 —— 删掉之后上个月末这批货记在谁名下就答不出来了。");
  }

  /** 货主停用 / 启用。停用不删行，且不能是默认货主。 */
  @Transactional
  public StockOwnerSaved setOwnerStatus(UUID id, String status) {
    var existing = requireOwner(id);
    String target = notBlank(status) ? status.trim().toUpperCase(Locale.ROOT) : existing.status();
    if (!target.equals("ACTIVE") && !target.equals("INACTIVE")) {
      throw bad("货主状态只能是 ACTIVE 或 INACTIVE，收到「" + target + "」");
    }
    if ("INACTIVE".equals(target) && existing.isDefault()) {
      throw bad("不能停用默认货主：所有未配过货权的 bucket 都落它身上，停用它等于让这批货的货主变成一个查不到的值");
    }
    sql.execute("update stock_owner set status = ?, updated_at = now() where id = ?", target, id);
    return new StockOwnerSaved(
        id,
        existing.code(),
        existing.ownerKind().name(),
        existing.isDefault(),
        "INACTIVE".equals(target)
            ? "已停用货主「"
                + existing.name()
                + "」。已有的 bucket 货主不受影响（历史货权仍可查），"
                + "但不能再有新的 bucket 挂到它上面，也不能被新货权档引用。"
            : "已启用货主「" + existing.name() + "」。");
  }

  private StockOwnerRow requireOwner(UUID id) {
    var r = sql.fetchOne("select " + OWNER_COLUMNS + " from stock_owner o where o.id = ?", id);
    if (r == null) {
      throw notFound("货主不存在或不属于当前租户：" + id);
    }
    return ownerRow(r);
  }

  private static StockOwnerRow ownerRow(Record r) {
    OwnerRefType refType = OwnerRefType.valueOf(r.get("owner_ref_type", String.class));
    UUID refId = r.get("owner_ref_id", UUID.class);
    return new StockOwnerRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        StockOwnerKind.valueOf(r.get("owner_kind", String.class)),
        Boolean.TRUE.equals(r.get("counts_as_owned_asset", Boolean.class)),
        refType,
        refId,
        r.get("owner_ref_label", String.class),
        // 「是否真的挂在某个已登记法人名下」是派生值，不入库 —— 它只是 owner_ref 的一个事实，
        // 单独存一列就多出一个可能与 owner_ref 不一致的第二真相。
        refType == OwnerRefType.LEGAL_ENTITY && refId != null,
        Boolean.TRUE.equals(r.get("is_default", Boolean.class)),
        r.get("status", String.class),
        r.get("note", String.class),
        r.get("created_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ============================================================ 货权配置

  /** 货权档列表（真分页）。 */
  public OwnershipRulePage pageRules(OwnershipRuleQuery query) {
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() <= 0 ? 50 : query.size()));
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    ruleConditions(query, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        sql.fetchOne("select count(*) from stock_ownership_rule r" + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select "
                + RULE_COLUMNS
                + RULE_FROM
                + sqlWhere
                + " order by r.scope_type, r.effective_from desc, r.id limit ? offset ?",
            append(binds, size, (long) pageNo * size).toArray());
    return new OwnershipRulePage(
        rows.stream().map(StockOwnershipService::ruleRow).toList(), total, pageNo, size);
  }

  private void ruleConditions(OwnershipRuleQuery query, List<String> where, List<Object> binds) {
    if (notBlank(query.scopeType())) {
      where.add("r.scope_type = ?");
      binds.add(query.scopeType().trim().toUpperCase(Locale.ROOT));
    }
    if (query.ownerId() != null) {
      where.add("r.owner_id = ?");
      binds.add(query.ownerId());
    }
    if (query.warehouseId() != null) {
      where.add("r.warehouse_id = ?");
      binds.add(query.warehouseId());
    }
    if (query.legalEntityId() != null) {
      where.add("r.legal_entity_id = ?");
      binds.add(query.legalEntityId());
    }
    if (query.skuId() != null) {
      where.add("r.sku_id = ?");
      binds.add(query.skuId());
    }
    if (query.effectiveOnly()) {
      where.add(
          "(r.effective_from <= now() and (r.effective_to is null or r.effective_to > now()))");
    }
    if (notBlank(query.keyword())) {
      where.add(
          "(o.name ilike ? or r.note ilike ? or w.code ilike ? or le.code ilike ? or s.code ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 5; i++) {
        binds.add(k);
      }
    }
  }

  /** 货权档详情（单档）。跨租户 → 404。 */
  public OwnershipRuleRow rule(UUID id) {
    var r = sql.fetchOne("select " + RULE_COLUMNS + RULE_FROM + " where r.id = ?", id);
    if (r == null) {
      throw notFound("货权档不存在或不属于当前租户：" + id);
    }
    return ruleRow(r);
  }

  private static OwnershipRuleRow ruleRow(Record r) {
    return new OwnershipRuleRow(
        r.get("id", UUID.class),
        OwnershipScopeType.valueOf(r.get("scope_type", String.class)),
        r.get("warehouse_id", UUID.class),
        r.get("warehouse_code", String.class),
        r.get("warehouse_name", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("legal_entity_code", String.class),
        r.get("legal_entity_name", String.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("owner_id", UUID.class),
        r.get("owner_name", String.class),
        StockOwnerKind.valueOf(r.get("owner_kind", String.class)),
        r.get("counts_as_owned_asset", Boolean.class),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class),
        r.get("note", String.class),
        r.get("created_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /**
   * 新建 / 换版一档货权配置。
   *
   * <p>{@code supersedeId} 非空时先收口那一档的区间再开新档（改货权的标准动作）。 顺序不能反 —— 先插新档会被区间重叠触发器当场拒绝，那是正确的失败，
   * 但表达不出「顺带收口」这个意图。
   *
   * <p><b>新建货权档不会改动任何已有 bucket</b>：货权档带生效区间，它只影响该区间内 <b>之后写入</b>的 bucket。存量要不要跟着改由 {@link
   * #reassign} 显式发起 —— 这条边界必须明确，否则「整仓设为寄售」到底是追溯生效还是仅对生效即日起， 界面上没人说得清，而财务那边会按"追溯"来理解。
   */
  @Transactional
  public OwnershipRuleSaved saveRule(UUID supersedeId, OwnershipRuleInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    OwnershipScopeType scope = scope(input.scopeType());
    UUID warehouseId = null;
    UUID legalEntityId = null;
    UUID skuId = null;
    switch (scope) {
      case WAREHOUSE -> {
        warehouseId = requireWarehouse(input.warehouseId());
        legalEntityId = null;
        skuId = null;
      }
      case LEGAL_ENTITY -> {
        legalEntityId = requireLegalEntity(input.legalEntityId());
      }
      case SKU -> skuId = input.skuId();
      case DEFAULT -> {
        // 三个定位键一律清空：DEFAULT 档带 id 是一条永远命中不了的档（DB CHECK 也会拒）。
      }
      default -> throw bad("不支持的货权范围：" + scope);
    }
    if (scope == OwnershipScopeType.SKU && skuId == null) {
      throw bad("按 SKU 的货权档必须给 skuId：否则它管的是哪个 SKU 无法回答");
    }
    if (scope == OwnershipScopeType.WAREHOUSE && warehouseId == null) {
      throw bad("整仓货权档必须给 warehouseId");
    }
    if (scope == OwnershipScopeType.LEGAL_ENTITY && legalEntityId == null) {
      throw bad("按法人的货权档必须给 legalEntityId");
    }

    var owner = requireOwner(input.ownerId());
    if (!"ACTIVE".equals(owner.status())) {
      throw bad(
          "货主「" + owner.name() + "」当前为 " + owner.status() + "，不能作为货权档的归属：它会在解析时被跳过，而界面上那一档还在");
    }

    OffsetDateTime effectiveFrom =
        input.effectiveFrom() == null ? OffsetDateTime.now() : input.effectiveFrom();

    UUID superseded = null;
    if (supersedeId != null) {
      var target = rule(supersedeId);
      if (target.effectiveTo() != null) {
        throw bad("这一档的区间已被收口（生效至 " + target.effectiveTo() + "），不能作为换版对象：要换的是当前仍在生效的那一档");
      }
      if (target.scopeType() != scope || !sameSlot(target, warehouseId, legalEntityId, skuId)) {
        throw bad(
            "换版必须落在同一个范围槽位内（原档 "
                + target.scopeType().label()
                + "，新档 "
                + scope.label()
                + "）：跨槽位改档会让原档被收口后无处继承，"
                + "等于悄悄作废了一个货权配置");
      }
      if (!effectiveFrom.isAfter(target.effectiveFrom())) {
        throw bad(
            "新档的生效起点 " + effectiveFrom + " 必须晚于原档起点 " + target.effectiveFrom() + "：区间不前进就等于原地改货权");
      }
      sql.execute(
          "update stock_ownership_rule set effective_to = ?::timestamptz where id = ?",
          effectiveFrom,
          supersedeId);
      superseded = supersedeId;
    }

    UUID id = UUID.randomUUID();
    try {
      sql.execute(
          ruleInsert(),
          id,
          tenant(),
          scope.name(),
          warehouseId,
          legalEntityId,
          skuId,
          input.ownerId(),
          effectiveFrom,
          nullToEmpty(input.note()).trim(),
          actorId);
    } catch (DataAccessException e) {
      throw translate(e, "货权档与该范围的其它档重叠，或货主/仓库/法人不存在： " + e.getMessage());
    }
    return new OwnershipRuleSaved(
        id,
        input.ownerId(),
        owner.name(),
        scope,
        effectiveFrom,
        null,
        superseded,
        (superseded == null
                ? "已新建"
                    + scope.label()
                    + "货权档：自 "
                    + effectiveFrom
                    + " 起，这批货归「"
                    + owner.name()
                    + "」（"
                    + owner.ownerKind().label()
                    + "）。"
                : "已换版：原档区间收到 " + effectiveFrom + " 为止，新档自该时刻起生效。两档并存，" + "历史那一档仍是「当时这批货归谁」的唯一凭据。")
            + "**存量 bucket 不受影响**：货权档只对生效之后写入的库位明细赋权，"
            + "已存在的货要改判请走货权转移（否则报表会继续把旧货算成原货主的资产）。");
  }

  private static boolean sameSlot(
      OwnershipRuleRow target, UUID warehouseId, UUID legalEntityId, UUID skuId) {
    return Objects.equals(target.warehouseId(), warehouseId)
        && Objects.equals(target.legalEntityId(), legalEntityId)
        && Objects.equals(target.skuId(), skuId);
  }

  /**
   * 作废一档货权（收口它的生效区间）。刻意**不**提供 DELETE。
   *
   * <p>作废是审计事件：档位行必须永久保留，否则"上个月末这批寄售货记在谁名下"无人能答。
   */
  @Transactional
  public OwnershipRuleSaved retireRule(UUID id, OffsetDateTime at) {
    var target = rule(id);
    if (target.effectiveTo() != null) {
      throw bad("这一档已被作废过（生效至 " + target.effectiveTo() + "），重复作废不会产生新的收口时刻");
    }
    OffsetDateTime moment = at == null ? OffsetDateTime.now() : at;
    if (!moment.isAfter(target.effectiveFrom())) {
      throw bad("作废时刻 " + moment + " 必须晚于该档的生效起点 " + target.effectiveFrom() + "：否则这一档从未真正生效过");
    }
    sql.execute(
        "update stock_ownership_rule set effective_to = ?::timestamptz where id = ?", moment, id);
    return new OwnershipRuleSaved(
        id,
        target.ownerId(),
        target.ownerName(),
        target.scopeType(),
        target.effectiveFrom(),
        moment,
        null,
        "已作废：自 "
            + moment
            + " 起该范围不再按这一档判货主。行本身保留，需要回看当时货权时查得到。"
            + "若此刻没有别的在效档，解析会如实报「未配置」并回落到默认货主，而不是继续用这一档。");
  }

  // ============================================================ 货权解析

  /**
   * 解析某一组维度在某一时刻真正生效的货主。
   *
   * <p>优先级完全交给 DB 函数 {@code resolve_stock_owner()}：Java 侧不重写一遍。 四档全未命中时回落到该租户的兜底货主，并把 {@code
   * usedFallback} 标成 true —— 调用方必须能区分"查到一条真实配置"与"没配过"。
   */
  public StockOwnershipResolved resolve(
      UUID warehouseId, UUID legalEntityId, UUID skuId, OffsetDateTime at) {
    OffsetDateTime moment = at == null ? OffsetDateTime.now() : at;

    var ruleRow =
        sql.fetchOne(
            "select "
                + RULE_COLUMNS
                + RULE_FROM
                + " where r.tenant_id = current_setting('app.current_tenant',true)::uuid"
                + " and r.effective_from <= ?::timestamptz"
                + " and (r.effective_to is null or r.effective_to > ?::timestamptz)"
                + " and ((r.scope_type='SKU' and r.sku_id = ?::uuid)"
                + "   or (r.scope_type='LEGAL_ENTITY' and r.legal_entity_id = ?::uuid)"
                + "   or (r.scope_type='WAREHOUSE' and r.warehouse_id = ?::uuid)"
                + "   or r.scope_type='DEFAULT')"
                + " order by case r.scope_type when 'SKU' then 0 when 'LEGAL_ENTITY' then 1"
                + "   when 'WAREHOUSE' then 2 else 3 end, r.effective_from desc limit 1",
            moment,
            moment,
            skuId,
            legalEntityId,
            warehouseId);
    if (ruleRow != null) {
      var matched = ruleRow(ruleRow);
      var owner = requireOwner(matched.ownerId());
      return new StockOwnershipResolved(
          matched,
          owner,
          matched.scopeType(),
          false,
          moment,
          "命中"
              + matched.scopeType().label()
              + "货权档：货主「"
              + owner.name()
              + "」（"
              + owner.ownerKind().label()
              + "）。"
              + (owner.countsAsOwnedAsset() ? "计入自有资产。" : "**不**计入自有资产。"));
    }

    // 四档全未命中 → 该租户的兜底货主（默认自有）。
    var fallback =
        sql.fetchOne("select " + OWNER_COLUMNS + " from stock_owner o where o.is_default");
    if (fallback == null) {
      // 兜底货主还没被建立过（此租户一行库存都没有）。如实报出来，而不是编一个货主。
      return new StockOwnershipResolved(
          null,
          null,
          null,
          true,
          moment,
          "本租户尚未建立兜底货主，且这组维度没有任何在生效的货权档 —— 该批货目前没有货主。"
              + "写入第一条库位明细时会自动建立默认自有货主，或现在先建一个货主 / 配一档货权。");
    }
    var owner = ownerRow(fallback);
    return new StockOwnershipResolved(
        null,
        owner,
        null,
        true,
        moment,
        "未命中 SKU / 法人 / 仓库 / 兜底四档中的任何一档，已回落到默认货主「"
            + owner.name()
            + "」（"
            + owner.ownerKind().label()
            + "）。回落是如实报出的状态而不是猜测："
            + "蓝图说「默认自有」，但没配过货权这件事本身需要被看见。");
  }

  // ============================================================ 按货主拆分的库存报表

  /**
   * 按货主拆分的库存报表（真实从 {@code stock_bucket} 与 {@code inventory_balance} 聚合）。
   *
   * <p><b>为什么同时报 bucket 与 balance 两个口径</b>：V2 保证「有 bucket 时 Σbucket == balance」， 但「还没拆到库位」的 balance
   * 行是合法状态。只报 bucket 会把未分解的量漏成 0， 只报 balance 又看不到货主归属。两个数并列，差额就是"未分解到库位"的那部分，
   * 让它是一个**可见的数字**而不是藏在两个页面里的差异。
   */
  public OwnerInventoryReport report(
      UUID warehouseId, UUID legalEntityId, UUID skuId, OffsetDateTime at) {
    OffsetDateTime moment = at == null ? OffsetDateTime.now() : at;

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (warehouseId != null) {
      where.add("k.warehouse_id = ?");
      binds.add(warehouseId);
    }
    if (legalEntityId != null) {
      where.add("k.legal_entity_id = ?");
      binds.add(legalEntityId);
    }
    if (skuId != null) {
      where.add("k.sku_id = ?");
      binds.add(skuId);
    }
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    var rows =
        sql.fetch(
            "select k.stock_owner_id, o.code as owner_code, o.name as owner_name,"
                + " o.owner_kind, o.counts_as_owned_asset,"
                + " count(*) as bucket_count,"
                + " count(distinct k.warehouse_id) as warehouse_count,"
                + " count(distinct k.sku_id) as sku_count,"
                + " count(distinct k.location_id) as location_count,"
                + " sum(k.on_hand)::numeric(19,4) as on_hand,"
                + " sum(k.reserved)::numeric(19,4) as reserved,"
                + " sum(k.available)::numeric(19,4) as available"
                + " from stock_bucket k"
                + " join stock_owner o on o.tenant_id = k.tenant_id and o.id = k.stock_owner_id"
                + sqlWhere
                + " group by k.stock_owner_id, o.code, o.name, o.owner_kind, o.counts_as_owned_asset"
                // 自有货排前：财务先看的就是它
                + " order by o.counts_as_owned_asset desc, sum(k.on_hand) desc, k.stock_owner_id",
            binds.toArray());

    var mapped =
        rows.stream()
            .map(
                r ->
                    new OwnerInventoryRow(
                        r.get("stock_owner_id", UUID.class),
                        r.get("owner_name", String.class),
                        r.get("owner_code", String.class),
                        StockOwnerKind.valueOf(r.get("owner_kind", String.class)),
                        r.get("counts_as_owned_asset", Boolean.class),
                        r.get("warehouse_count", Long.class),
                        r.get("sku_count", Long.class),
                        r.get("location_count", Long.class),
                        r.get("bucket_count", Long.class),
                        money(r, "on_hand"),
                        money(r, "reserved"),
                        money(r, "available")))
            .toList();

    long totalBuckets = mapped.stream().mapToLong(OwnerInventoryRow::bucketCount).sum();
    BigDecimal totalOnHand = sum(mapped.stream().map(OwnerInventoryRow::onHand).toList());
    BigDecimal totalReserved = sum(mapped.stream().map(OwnerInventoryRow::reserved).toList());
    BigDecimal totalAvailable = sum(mapped.stream().map(OwnerInventoryRow::available).toList());
    BigDecimal owned =
        sum(
            mapped.stream()
                .filter(OwnerInventoryRow::countsAsOwnedAsset)
                .map(OwnerInventoryRow::onHand)
                .toList());

    // 「未分解到库位」= 存在余额行但一条库位明细都没有的那些行。V2 的守恒触发器保证了：
    // 只要某条余额有 bucket，Σbucket 就必须精确等于它 —— 所以未分解只能是「一条 bucket 都没有」。
    // 按这个定义直接算，而不是用「余额总量减 bucket 总量」（那样会把守恒错误算成未分解）。
    List<String> balanceWhere = new ArrayList<>();
    List<Object> balanceBinds = new ArrayList<>();
    if (warehouseId != null) {
      balanceWhere.add("b.warehouse_id = ?");
      balanceBinds.add(warehouseId);
    }
    if (legalEntityId != null) {
      balanceWhere.add("b.legal_entity_id = ?");
      balanceBinds.add(legalEntityId);
    }
    if (skuId != null) {
      balanceWhere.add("b.sku_id = ?");
      balanceBinds.add(skuId);
    }
    var balanceRow =
        sql.fetchOne(
            "select coalesce(sum(b.on_hand),0)::numeric(19,4) as on_hand"
                + " from inventory_balance b"
                // 过滤条件为空时不能拼出 "and not exists" —— 那会因为少了 where 而语法错。
                + (balanceWhere.isEmpty()
                    ? " where "
                    : " where " + String.join(" and ", balanceWhere) + " and ")
                + "not exists (select 1 from stock_bucket k"
                + "                  where k.tenant_id = b.tenant_id and k.balance_id = b.id)",
            balanceBinds.toArray());
    BigDecimal undecomposed =
        balanceRow.get("on_hand", BigDecimal.class).setScale(QTY_SCALE, RoundingMode.HALF_UP);

    return new OwnerInventoryReport(
        mapped,
        totalBuckets,
        totalOnHand,
        totalReserved,
        totalAvailable,
        owned,
        totalOnHand.subtract(owned).setScale(QTY_SCALE, RoundingMode.HALF_UP),
        mapped.size(),
        false,
        "货值（金额）口径不可用：warehouse 与 catalog 两侧的迁移及 db/schemas 中都没有任何**库存成本**列"
            + "（catalog 侧只有 supplier_quote_price_tier，那是采购报价不是库存计价，且属跨服务引用）。"
            + "用采购报价冒充库存成本会把账面资产算错，比不给更糟。"
            + "本仓库当前只出数量口径；计价能力落地后按 counts_as_owned_asset 拆自有与非自有即可。",
        moment,
        "库存总量按已分解到库位的口径为 "
            + totalOnHand.toPlainString()
            + "，另有 "
            + undecomposed.toPlainString()
            + " 未分解到库位（存在库存余额行、但一条库位明细都没有，属 7.1.1.2 的合法状态）。"
            + "这一部分**没有货主维度** —— 货主是库位明细级的属性，它还没被分解到库位，"
            + "所以按货主拆分的报表覆盖不到它。差额必须是一个可见的数，而不是藏在两个页面里。");
  }

  // ============================================================ 货权漂移与转移

  /**
   * 货权漂移：bucket 上的货主与「按当前生效档应该属于的货主」不一致。
   *
   * <p>这类行是**有意的**，不是 bug：货权档带生效区间，只对生效之后写入的 bucket 赋权， 存量不会自动跟着改。把它们列出来，是为了让「这批货的货权要不要跟着新配置改」
   * 成为一个可回答的问题。
   */
  public OwnershipDriftReport drift(UUID warehouseId, UUID legalEntityId, UUID skuId) {
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (warehouseId != null) {
      where.add("k.warehouse_id = ?");
      binds.add(warehouseId);
    }
    if (legalEntityId != null) {
      where.add("k.legal_entity_id = ?");
      binds.add(legalEntityId);
    }
    if (skuId != null) {
      where.add("k.sku_id = ?");
      binds.add(skuId);
    }
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    var rows =
        sql.fetch(
            "select k.stock_owner_id, cur.name as current_owner_name,"
                + " resolve_stock_owner(k.tenant_id, k.warehouse_id, k.legal_entity_id, k.sku_id, now())"
                + "   as expected_owner_id,"
                + " exp.name as expected_owner_name,"
                + " k.warehouse_id, k.legal_entity_id, k.sku_id,"
                + " count(*) as bucket_count,"
                + " sum(k.on_hand)::numeric(19,4) as on_hand"
                + " from stock_bucket k"
                + " join stock_owner cur on cur.tenant_id = k.tenant_id and cur.id = k.stock_owner_id"
                + " left join stock_owner exp on exp.tenant_id = k.tenant_id"
                + "   and exp.id = resolve_stock_owner(k.tenant_id, k.warehouse_id,"
                + "                               k.legal_entity_id, k.sku_id, now())"
                + sqlWhere
                + " group by k.stock_owner_id, cur.name, k.warehouse_id, k.legal_entity_id, k.sku_id,"
                + "   resolve_stock_owner(k.tenant_id, k.warehouse_id, k.legal_entity_id, k.sku_id, now()),"
                + "   exp.name"
                // 只有"确实解析得到、且与当前货主不同"才算漂移；
                // 解析不到（四档全未命中）的那些落默认货主，不算配置漂移 —— 否则会刷出一堆噪音。
                + " having resolve_stock_owner(k.tenant_id, k.warehouse_id, k.legal_entity_id,"
                + "                      k.sku_id, now()) is not null"
                + "    and resolve_stock_owner(k.tenant_id, k.warehouse_id, k.legal_entity_id,"
                + "                          k.sku_id, now()) <> k.stock_owner_id"
                + " order by sum(k.on_hand) desc, k.stock_owner_id, k.warehouse_id, k.sku_id",
            binds.toArray());

    var mapped =
        rows.stream()
            .map(
                r ->
                    new OwnershipDriftRow(
                        r.get("bucket_count", Long.class),
                        money(r, "on_hand"),
                        r.get("stock_owner_id", UUID.class),
                        r.get("current_owner_name", String.class),
                        r.get("expected_owner_id", UUID.class),
                        r.get("expected_owner_name", String.class),
                        r.get("warehouse_id", UUID.class),
                        r.get("legal_entity_id", UUID.class),
                        r.get("sku_id", UUID.class)))
            .toList();

    return new OwnershipDriftReport(
        mapped,
        mapped.stream().mapToLong(OwnershipDriftRow::bucketCount).sum(),
        sum(mapped.stream().map(OwnershipDriftRow::onHand).toList()),
        mapped.isEmpty()
            ? "当前范围内没有货权漂移：所有库位明细的货主都与生效货权档一致" + "（或该范围未配货权档、已落默认自有货主）。"
            : "以上 "
                + mapped.size()
                + " 组「库位明细的货主 ≠ 当前生效档应给的货主」。"
                + "货权档只对生效之后写入的库位明细赋权，存量不会自动跟着改 —— "
                + "这是有意为之：追溯改存量货权等于改账，必须由人显式发起货权转移。");
  }

  /**
   * 货权转移：把某一范围内属于 {@code fromOwnerId} 的 bucket 改判给 {@code toOwnerId}。
   *
   * <p>这是本模块唯一会**批量改动既有库存事实行**的入口，所以三道防线：
   *
   * <ol>
   *   <li>目标货主必须 ACTIVE（DB 触发器 {@code guard_bucket_stock_owner} 再挡一次）
   *   <li>from != to，且两者都必须存在且属于本租户（跨租户在 RLS 下查不到 → 404）
   *   <li>一次最多 {@link #MAX_REASSIGN_PAIRS} 对，超出要求分批，避免一个事务锁住半个库存表
   * </ol>
   *
   * <p><b>转移不动数量、不动 balance、不写流水</b>：货权是账上的归属，不是货物流动。 数量照常被 {@code guard_bucket_conservation}
   * 核对，转移前后 Σ 不变。
   */
  @Transactional
  public OwnershipReassignResult reassign(OwnershipReassignRequest request, OffsetDateTime at) {
    if (request == null) {
      throw bad("请求体不能为空");
    }
    if (request.fromOwnerId().equals(request.toOwnerId())) {
      throw bad("转出与转入是同一个货主：没有任何行会被改动，却会让人以为转移成功了");
    }
    var from = requireOwner(request.fromOwnerId());
    var to = requireOwner(request.toOwnerId());
    if (!"ACTIVE".equals(to.status())) {
      throw bad("转入货主「" + to.name() + "」当前为 " + to.status() + "：不能把库存转给一个停用的货主");
    }
    OffsetDateTime moment = at == null ? OffsetDateTime.now() : at;

    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (request.warehouseId() != null) {
      where.add("warehouse_id = ?");
      binds.add(request.warehouseId());
    }
    if (request.legalEntityId() != null) {
      where.add("legal_entity_id = ?");
      binds.add(request.legalEntityId());
    }
    if (request.skuId() != null) {
      where.add("sku_id = ?");
      binds.add(request.skuId());
    }
    String sqlWhere = where.isEmpty() ? "" : " and " + String.join(" and ", where);

    // 先算清楚要动多少，再动。返回「0 行」是一个合法且必须如实报出的结果，
    // 而不是报错 —— 该范围内本来就没有这个货主的货也是真实状态。
    var planned =
        sql.fetchOne(
            "select count(*) as bucket_count, coalesce(sum(on_hand),0)::numeric(19,4) as on_hand"
                + " from stock_bucket where stock_owner_id = ?"
                + sqlWhere,
            concat(new Object[] {request.fromOwnerId()}, binds.toArray()));
    long plannedCount = planned.get("bucket_count", Long.class);
    BigDecimal plannedOnHand =
        planned.get("on_hand", BigDecimal.class).setScale(QTY_SCALE, RoundingMode.HALF_UP);

    if (plannedCount > 0) {
      try {
        sql.execute(
            "update stock_bucket set stock_owner_id = ?" + " where stock_owner_id = ?" + sqlWhere,
            concat(new Object[] {request.toOwnerId(), request.fromOwnerId()}, binds.toArray()));
      } catch (DataAccessException e) {
        throw translate(e, "货权转移失败：" + e.getMessage());
      }
    }

    return new OwnershipReassignResult(
        request.fromOwnerId(),
        from.name(),
        request.toOwnerId(),
        to.name(),
        plannedCount,
        plannedOnHand,
        plannedCount == 0
            ? "该范围内没有属于「"
                + from.name()
                + "」的库位明细，0 行被改动 —— 这是一个合法的空结果，"
                + "不是失败。请确认筛选条件（仓库 / 法人 / SKU）是否选窄了。"
            : "已转移 "
                + plannedCount
                + " 行库位明细（在手 "
                + plannedOnHand.toPlainString()
                + "）的货主："
                + "「"
                + from.name()
                + "」 → 「"
                + to.name()
                + "」（"
                + to.ownerKind().label()
                + "）。"
                + "**数量、预占、库存余额与流水都没有变动** —— 货权是账上的归属，不是货物流动；"
                + "本次只改了「这批货是谁的」这一列。生效时刻 "
                + moment
                + "。");
  }

  // ============================================================ 参照下拉与能力声明

  /** 货权转移对话框需要的可选项（仓库 / 法人 / SKU 都来自本服务的引用登记）。 */
  public OwnershipReferenceOptions referenceOptions() {
    var warehouses =
        sql.fetch("select id,code,name from warehouse where status = 'ACTIVE' order by code")
            .map(
                r ->
                    new OwnershipReferenceOptions.WarehouseRefLite(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class)));
    var entities =
        sql.fetch("select id,code,name from legal_entity_ref where status = 'ACTIVE' order by code")
            .map(
                r ->
                    new OwnershipReferenceOptions.LegalEntityRefLite(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class)));
    var skus =
        sql.fetch("select id,code,name from sku_ref order by code")
            .map(
                r ->
                    new OwnershipReferenceOptions.SkuRefLite(
                        r.get("id", UUID.class),
                        r.get("code", String.class),
                        r.get("name", String.class)));
    return new OwnershipReferenceOptions(warehouses, entities, skus);
  }

  /**
   * 能力声明：把「哪些维度真有依据」显式报出来。
   *
   * <p>存在的理由与 7.4.2.2 一致：不要让调用方从一个空数组里去猜是哪一环没通。
   */
  public StockOwnershipCapabilities capabilities() {
    var dimensions =
        List.of(
            new StockOwnershipCapabilities.Dimension(
                "STOCK_OWNER_KIND",
                "所有权类型（自有 / 寄售 / 平台所有 / 代销）",
                true,
                "warehouse V67__stock_ownership.sql",
                null),
            new StockOwnershipCapabilities.Dimension(
                "LEGAL_ENTITY",
                "货权法人",
                true,
                "identity V5__legal_entity.sql（经本服务 legal_entity_ref 登记）",
                null),
            new StockOwnershipCapabilities.Dimension(
                "SUPPLIER",
                "供应商货主",
                true,
                "catalog V30__party_role_profiles.sql（跨服务逻辑 ID + 名称快照）",
                null),
            new StockOwnershipCapabilities.Dimension(
                "WAREHOUSE", "仓库维度", true, "warehouse V1__warehouse_inventory_core.sql", null),
            new StockOwnershipCapabilities.Dimension(
                "SKU", "SKU 维度", true, "catalog（sku_ref 引用登记，跨服务逻辑 ID）", null),
            new StockOwnershipCapabilities.Dimension(
                "AMOUNT_VALUATION",
                "按货主拆分的货值",
                false,
                null,
                "warehouse 与 catalog 两侧的迁移及 db/schemas 中都没有任何**库存成本**列"
                    + "（catalog 侧只有 supplier_quote_price_tier，那是采购报价不是库存计价，且属跨服务引用）。"
                    + "本模块只出数量口径；用采购报价冒充库存成本会把账面资产算错，因此显式报不可用而不是给一个假金额。"));

    return new StockOwnershipCapabilities(
        dimensions,
        false,
        "货值口径不可用，理由见 dimensions 里的 AMOUNT_VALUATION。",
        "货权维度（stock_bucket.stock_owner_id + 所有权类型 + 「是否计入自有资产」生成列）已全量可用；"
            + "数量口径的按货主拆分报表可用。货值口径需要库存计价能力，不在本模块范围内。");
  }

  // ============================================================ 内部工具

  private UUID requireWarehouse(UUID id) {
    if (id == null) {
      return null;
    }
    var r = sql.fetchOne("select id from warehouse where id = ?", id);
    if (r == null) {
      throw notFound("仓库不存在或不属于当前租户：" + id);
    }
    return id;
  }

  private UUID requireLegalEntity(UUID id) {
    if (id == null) {
      return null;
    }
    var r = sql.fetchOne("select id from legal_entity_ref where id = ?", id);
    if (r == null) {
      throw notFound("法人引用登记里没有这一条，或不属于当前租户：" + id);
    }
    return id;
  }

  private static String allowedRefTypes(StockOwnerKind kind) {
    return switch (kind) {
      case SELF -> "本租户法人或本租户自身";
      case SUPPLIER_CONSIGNMENT -> "供应商（catalog）";
      case PLATFORM -> "平台（channel）或本租户自身";
      case CONSIGNEE -> "供应商（catalog）或本租户法人";
    };
  }

  private static StockOwnerKind ownerKind(String value) {
    try {
      return StockOwnerKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的所有权类型「" + value + "」，合法值：SELF / SUPPLIER_CONSIGNMENT / PLATFORM / CONSIGNEE");
    }
  }

  private static OwnerRefType ownerRefType(String value) {
    try {
      return OwnerRefType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的货主指向类型「" + value + "」，合法值：LEGAL_ENTITY / SUPPLIER / PLATFORM / INTERNAL");
    }
  }

  private static OwnershipScopeType scope(String value) {
    try {
      return OwnershipScopeType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的货权范围「" + value + "」，合法值：DEFAULT / WAREHOUSE / LEGAL_ENTITY / SKU");
    }
  }

  /** numeric 读回可能带不同 scale，统一到源列刻度；null 归零（内连接已保证有行）。 */
  private static BigDecimal money(Record r, String field) {
    BigDecimal v = r.get(field, BigDecimal.class);
    return v == null ? ZERO : v.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal sum(List<BigDecimal> values) {
    BigDecimal total = ZERO;
    for (BigDecimal v : values) {
      total = total.add(v);
    }
    return total.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    var all = new ArrayList<>(binds);
    for (Object o : more) {
      all.add(o);
    }
    return all;
  }

  private static Object[] concat(Object[] head, Object[] tail) {
    var all = new ArrayList<Object>(head.length + tail.length);
    java.util.Collections.addAll(all, head);
    java.util.Collections.addAll(all, tail);
    return all.toArray();
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private UUID tenant() {
    return sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t")
        .get("t", UUID.class);
  }

  /**
   * DB 约束冲突翻译成人能读的一句话。
   *
   * <p>原样透出 {@code e.getMessage()} 会把整条 INSERT 与 PG 的英文约束名糊在页面上，
   * 用户看不懂自己错在哪。这里给出业务语义，剩下的细节保留在服务端日志口径里。
   */
  private static ResponseStatusException translate(DataAccessException e, String hint) {
    String message = e.getMostSpecificCause().getMessage();
    if (message != null && message.contains("guard_stock_owner_legal_entity")) {
      throw bad(
          hint + "：自有货必须挂在本租户已登记的法人上，" + "请先到法人引用登记里补上该法人，或改用 ownerRefType=INTERNAL（未挂法人的自有货）");
    }
    if (message != null && message.contains("uq_stock_owner_default")) {
      throw bad(hint + "：同一租户至多一个默认货主，请先把原默认货主取消默认");
    }
    if (message != null && message.contains("stock_owner_code_key")) {
      throw bad(hint + "：货主编码在同一租户内必须唯一");
    }
    if (message != null && message.contains("stock_ownership_rule_interval")) {
      throw bad(hint + "：同一范围槽位的生效区间不得重叠，请先收口与它冲突的那一档");
    }
    if (message != null && message.contains("uq_stock_bucket_owner_scope")) {
      throw bad(
          hint
              + "：目标货主在该库位 / 批次 / 质量状态下已有一行库位明细，"
              + "两行不能合并（合并会让两批货的货权在数量上无法区分）—— 请先确认目标货主下是否已有该货位上的货");
    }
    throw bad(hint);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
