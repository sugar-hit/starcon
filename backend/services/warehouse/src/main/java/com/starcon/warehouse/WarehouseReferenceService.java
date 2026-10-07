package com.starcon.warehouse;

import static com.starcon.warehouse.WarehouseModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.1.1.1 的参照数据与超储阈值。
 *
 * <p>三类东西在这里，因为它们都是"总览要筛/要判，但不属于余额本身"的租户级配置：
 * 引用登记（法人 / SKU / 仓库 / 质量状态）与阈值策略。
 *
 * <p>跨服务边界：法人与 SKU 的**主数据**分别属 identity 与 catalog，本服务只登记
 * "我这里有这批货、它算哪个法人"的引用事实（见 V1 迁移文件头）。这里**不**复制类目、
 * 品牌、店铺 —— 那三样必须走授权 API，复制成本地列会出现两份权威。
 */
@Service
public class WarehouseReferenceService {

  private final DSLContext sql;

  public WarehouseReferenceService(DSLContext sql) {
    this.sql = sql;
  }

  // ------------------------------------------------------------ 引用下拉项

  public List<WarehouseRef> warehouses() {
    return sql.fetch(
            "select id,code,name,country_code,status,is_default from warehouse"
                + " where status<>'ARCHIVED' order by is_default desc,code")
        .map(
            r ->
                new WarehouseRef(
                    r.get("id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class),
                    r.get("country_code", String.class),
                    r.get("status", String.class),
                    Boolean.TRUE.equals(r.get("is_default", Boolean.class))));
  }

  public List<LegalEntityRef> legalEntities() {
    return sql.fetch(
            "select id,code,name,status from legal_entity_ref where status='ACTIVE' order by code")
        .map(
            r ->
                new LegalEntityRef(
                    r.get("id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class),
                    r.get("status", String.class)));
  }

  public List<SkuRef> skus() {
    return sql.fetch("select id,code,name from sku_ref order by code")
        .map(r -> new SkuRef(r.get("id", UUID.class), r.get("code", String.class), r.get("name", String.class)));
  }

  public List<QualityStatusRef> qualityStatuses() {
    return sql.fetch(
            "select id,code,name,role,enabled from inventory_quality_status order by role,code")
        .map(
            r ->
                new QualityStatusRef(
                    r.get("id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class),
                    QualityRole.valueOf(r.get("role", String.class)),
                    Boolean.TRUE.equals(r.get("enabled", Boolean.class))));
  }

  // ------------------------------------------------------------ 阈值策略

  /**
   * 阈值列表。超储判定的**真实**来源，不是硬编码常量。
   *
   * <p>{@code scopeType} 过滤支持 {@code null}（全部）与四个词表值。
   */
  public List<InventoryPolicy> policies(String scopeType) {
    if (scopeType == null || scopeType.isBlank()) {
      return sql.fetch(selectPolicy() + " order by scope_type,scope_key").map(WarehouseReferenceService::policy);
    }
    String scope = scopeType.toUpperCase(Locale.ROOT);
    if (!InventoryOverviewService.POLICY_SCOPES.contains(scope)) {
      throw new IllegalArgumentException("不支持的策略范围：" + scopeType);
    }
    return sql.fetch(
            selectPolicy() + " where scope_type=? order by scope_key", scope)
        .map(WarehouseReferenceService::policy);
  }

  private static String selectPolicy() {
    return "select id,scope_type,scope_key,max_on_hand,min_on_hand,reorder_point,safety_stock,enabled,version"
        + " from inventory_policy";
  }

  /**
   * 保存阈值（新增或更新）。同一租户下 scope_type + scope_key 唯一，重复保存即更新。
   *
   * <p>7.2.2 会在同一张表上扩展补货点 / 安全库存 / 提前期，届时本方法与表结构一起演进，
   * 不需要迁移到别处 —— 这是"别把字段设计死到无法扩展"的兑现方式。
   */
  public InventoryPolicySaved savePolicy(UUID policyId, InventoryPolicyInput input) {
    String scope = input.scopeType() == null ? "" : input.scopeType().toUpperCase(Locale.ROOT).trim();
    if (!InventoryOverviewService.POLICY_SCOPES.contains(scope)) {
      throw new IllegalArgumentException("不支持的策略范围：" + input.scopeType());
    }
    String key = input.scopeKey() == null ? "" : input.scopeKey().trim();
    // DEFAULT 必须空键、其余必须带键：与迁移里的 CHECK 同一条规则，在应用层先给出
    // 可读的错误，而不是等 DB 抛一个只有码表的 23514。
    if (scope.equals("DEFAULT") != key.isEmpty()) {
      throw new IllegalArgumentException(
          scope.equals("DEFAULT") ? "默认级策略不需要 scopeKey" : scope + " 级策略必须指定 scopeKey");
    }
    if (input.maxOnHand() == null) {
      throw new IllegalArgumentException("必须设置超储上限 maxOnHand，否则该策略不会参与超储判定");
    }
    if (input.maxOnHand().compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("超储上限必须大于 0");
    }
    if (input.minOnHand() != null
        && input.minOnHand().compareTo(input.maxOnHand()) > 0) {
      throw new IllegalArgumentException("低储下限不能高于超储上限");
    }

    // upsert 走 ON CONFLICT：同一租户同范围只可能有一条策略行，避免出现两条 DEFAULT
    // 让解析顺序没有唯一答案（迁移里 UNIQUE NULLS NOT DISTINCT 已经兜住，这里是友好错误）。
    sql.execute(
        "insert into inventory_policy(id,tenant_id,scope_type,scope_key,max_on_hand,min_on_hand,version)"
            + " values(?,current_setting('app.current_tenant',true)::uuid,?,?,?,?,0)"
            + " on conflict (tenant_id,scope_type,scope_key)"
            + " do update set max_on_hand=excluded.max_on_hand,min_on_hand=excluded.min_on_hand,"
            + " version=inventory_policy.version+1,updated_at=now()",
        policyId == null ? Ids.next() : policyId,
        scope,
        key,
        input.maxOnHand(),
        input.minOnHand());

    var saved =
        sql.fetchOne(
            "select id,version from inventory_policy where scope_type=? and scope_key=?", scope, key);
    return new InventoryPolicySaved(saved.get("id", UUID.class), saved.get("version", Long.class));
  }

  /** 删除阈值。已被引用不阻止删除（阈值是策略不是事实），但行必须存在。 */
  public void deletePolicy(UUID id) {
    int deleted = sql.execute("delete from inventory_policy where id=?", id);
    if (deleted == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "阈值策略不存在");
  }

  private static InventoryPolicy policy(org.jooq.Record r) {
    return new InventoryPolicy(
        r.get("id", UUID.class),
        r.get("scope_type", String.class),
        r.get("scope_key", String.class),
        r.get("max_on_hand", BigDecimal.class),
        r.get("min_on_hand", BigDecimal.class),
        r.get("reorder_point", BigDecimal.class),
        r.get("safety_stock", BigDecimal.class),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("version", Long.class));
  }
}
