package com.starcon.catalog;

import static com.starcon.catalog.ExportDataSource.*;
import static com.starcon.catalog.ExportModels.*;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * SKU 列表导出数据源（上游 3.1.2.1 SKU 列表）。
 *
 * <p>FROM 与列集合**照抄 {@code SkuListService}**：导出的必须是列表页那批行， 两个查询一旦各写各的 JOIN，「列表看到的」与「导出的」就会在某天悄悄分叉。
 *
 * <p>筛选词表刻意把**依赖未接入上游投影**的那几个条件也列出来 （库存区间、售价区间、是否缺货、是否在售、在售平台），但标成 {@code available=false}
 * 并写明字段归属：它们在 3.1.2.1 列表页上同样被显式拒绝 （{@code SkuListService.requireProjections}）。导出这里沿用同一条纪律 ——
 * 静默忽略一个用户填了的条件，会让他以为筛过了而其实没筛。
 */
@Component
class SkuExportDataSource extends SqlExportDataSource {
  private static final List<String> STATUSES = List.of("ACTIVE", "ARCHIVED", "DRAFT", "INACTIVE");

  private static final List<ExportColumn> COLUMNS =
      List.of(
          new ExportColumn("CODE", "SKU 编码", false, r -> r.get("code", String.class)),
          new ExportColumn("NAME", "SKU 名称", false, r -> r.get("name", String.class)),
          new ExportColumn("SPU_CODE", "所属 SPU 编码", false, r -> r.get("spu_code", String.class)),
          new ExportColumn("SPU_NAME", "所属 SPU 名称", false, r -> r.get("spu_name", String.class)),
          new ExportColumn("CATEGORY", "分类", false, r -> r.get("category", String.class)),
          new ExportColumn("BARCODE", "条码", false, r -> r.get("barcode", String.class)),
          new ExportColumn("BASE_UNIT", "基础单位", false, r -> r.get("base_unit", String.class)),
          new ExportColumn("STATUS", "状态", false, r -> r.get("status", String.class)),
          new ExportColumn(
              "VARIANT_SIGNATURE", "规格签名", false, r -> r.get("variant_signature", String.class)),
          new ExportColumn(
              "CREATED_AT", "创建时间", false, r -> instant(r.get("created_at", OffsetDateTime.class))),
          new ExportColumn(
              "UPDATED_AT", "更新时间", false, r -> instant(r.get("updated_at", OffsetDateTime.class))),
          new ExportColumn("ID", "SKU ID", false, r -> r.get("id", String.class)));

  private static final List<ExportSort> SORTS =
      List.of(
          new ExportSort("code", "SKU 编码", "s.code"),
          new ExportSort("name", "SKU 名称", "s.name"),
          new ExportSort("status", "状态", "s.status"),
          new ExportSort("barcode", "条码", "s.barcode"),
          new ExportSort("created_at", "创建时间", "s.created_at"),
          new ExportSort("updated_at", "更新时间", "s.updated_at"));

  private static final List<ExportFilterInfo> FILTERS =
      List.of(
          new ExportFilterInfo("spuId", "所属 SPU", "TEXT", true, ""),
          new ExportFilterInfo("category", "分类", "TEXT", true, ""),
          new ExportFilterInfo("status", "状态", "ENUM", true, "取值：" + String.join(" / ", STATUSES)),
          new ExportFilterInfo("q", "关键字（编码 / 名称 / 条码）", "TEXT", true, ""),
          new ExportFilterInfo("barcode", "条码（精确）", "TEXT", true, ""),
          new ExportFilterInfo("spec", "规格关键字", "TEXT", true, ""),
          // 以下五个来自 3.1.2.1 列表页，但求值依赖尚未接入的上游投影。
          new ExportFilterInfo(
              "onHandMin", "在仓数量下限", "DECIMAL", false, SkuField.ON_HAND.ownerNote()),
          new ExportFilterInfo(
              "onHandMax", "在仓数量上限", "DECIMAL", false, SkuField.ON_HAND.ownerNote()),
          new ExportFilterInfo(
              "priceMin", "售价下限", "DECIMAL", false, SkuField.SALE_PRICE.ownerNote()),
          new ExportFilterInfo(
              "priceMax", "售价上限", "DECIMAL", false, SkuField.SALE_PRICE.ownerNote()),
          new ExportFilterInfo(
              "outOfStock", "是否缺货", "BOOLEAN", false, SkuField.AVAILABLE_QTY.ownerNote()),
          new ExportFilterInfo(
              "onSale", "是否在售", "BOOLEAN", false, SkuField.LISTED_PLATFORMS.ownerNote()),
          new ExportFilterInfo(
              "platform", "在售平台", "TEXT", false, SkuField.LISTED_PLATFORMS.ownerNote()));

  SkuExportDataSource(DSLContext sql) {
    super(sql);
  }

  @Override
  public String key() {
    return "SKU";
  }

  @Override
  public String label() {
    return "SKU 列表";
  }

  @Override
  public String upstreamModule() {
    return "3.1.2.1 SKU 列表";
  }

  @Override
  public String permission() {
    return "catalog.sku.read";
  }

  @Override
  public List<ExportColumn> columns() {
    return COLUMNS;
  }

  @Override
  public List<ExportSort> sorts() {
    return SORTS;
  }

  @Override
  public String defaultSortKey() {
    return "updated_at";
  }

  @Override
  public List<ExportFilterInfo> filters() {
    return FILTERS;
  }

  @Override
  public int maxRows() {
    return 50_000;
  }

  @Override
  public String from() {
    // 与 SkuListService.FROM 逐字相同。
    return " from sku s join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id";
  }

  @Override
  protected String selectList(List<ExportColumn> columns) {
    return "s.tenant_id,s.id,s.spu_id,s.code,s.name,s.barcode,s.base_unit,s.status,"
        + "s.variant_signature,s.created_at,s.updated_at,p.code as spu_code,p.name as spu_name,p.category";
  }

  @Override
  public void conditions(ExportFilterInput spec, List<String> where, List<Object> binds) {
    if (spec == null) return;
    if (spec.spuId() != null) {
      where.add("s.spu_id = ?");
      binds.add(spec.spuId());
    }
    if (notBlank(spec.category())) equals("p.category", spec.category(), where, binds);
    if (notBlank(spec.q())) {
      where.add(
          "(s.code ilike ? escape '!' or s.name ilike ? escape '!' or s.barcode ilike ? escape '!')");
      for (int i = 0; i < 3; i++) binds.add("%" + escape(spec.q().trim()) + "%");
    }
    if (notBlank(spec.barcode())) equals("s.barcode", spec.barcode(), where, binds);
    if (notBlank(spec.spec())) ilike("s.variant_signature", spec.spec(), where, binds);
    if (spec.status() != null && !spec.status().isEmpty()) {
      var values =
          spec.status().stream()
              .filter(SkuExportDataSource::notBlank)
              .map(String::trim)
              .map(String::toUpperCase)
              .distinct()
              .toList();
      if (!values.isEmpty()) {
        for (var v : values) CatalogService.check(STATUSES.contains(v), "无效的状态：" + v);
        where.add(
            "s.status in (" + String.join(",", Collections.nCopies(values.size(), "?")) + ")");
        binds.addAll(values);
      }
    }
  }

  @Override
  public String orderBy(ExportSort sort, boolean desc) {
    return " order by " + sort.column() + (desc ? " desc" : " asc") + ", s.id desc";
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }
}
