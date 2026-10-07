package com.starcon.catalog;

import static com.starcon.catalog.ExportDataSource.*;
import static com.starcon.catalog.ExportModels.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * SPU 列表导出数据源（上游 3.1.1.1 SPU 列表）。
 *
 * <p>字段与筛选条件**与 3.1.1.1 列表页同源**：导出只是把同一套条件下的另一批行取出来，
 * 不是另立一套口径。排序键同样取自列表页支持的那些列。
 */
@Component
class SpuExportDataSource extends SqlExportDataSource {
  private static final List<String> STATUSES = Set.of("DRAFT", "ACTIVE", "INACTIVE", "ARCHIVED").stream().sorted().toList();

  private static final List<ExportColumn> COLUMNS =
      List.of(
          new ExportColumn("CODE", "SPU 编码", false, r -> r.get("code", String.class)),
          new ExportColumn("NAME", "SPU 名称", false, r -> r.get("name", String.class)),
          new ExportColumn("CATEGORY", "分类", false, r -> r.get("category", String.class)),
          new ExportColumn("BRAND", "品牌", false, r -> r.get("brand", String.class)),
          new ExportColumn("STATUS", "生命周期状态", false, r -> r.get("status", String.class)),
          new ExportColumn(
              "DESCRIPTION", "描述", false, r -> blankToNull(r.get("description", String.class))),
          new ExportColumn(
              "CREATED_AT",
              "创建时间",
              false,
              r -> instant(r.get("created_at", OffsetDateTime.class))),
          new ExportColumn(
              "UPDATED_AT",
              "更新时间",
              false,
              r -> instant(r.get("updated_at", OffsetDateTime.class))),
          new ExportColumn("ID", "SPU ID", false, r -> r.get("id", String.class)));

  private static final List<ExportSort> SORTS =
      List.of(
          new ExportSort("code", "编码", "s.code"),
          new ExportSort("name", "名称", "s.name"),
          new ExportSort("status", "生命周期状态", "s.status"),
          new ExportSort("created_at", "创建时间", "s.created_at"),
          new ExportSort("updated_at", "更新时间", "s.updated_at"));

  private static final List<ExportFilterInfo> FILTERS =
      List.of(
          new ExportFilterInfo("q", "关键字（编码 / 名称）", "TEXT", true, ""),
          new ExportFilterInfo("category", "分类", "TEXT", true, ""),
          new ExportFilterInfo("brand", "品牌", "TEXT", true, ""),
          new ExportFilterInfo("status", "生命周期状态", "ENUM", true, "取值：" + String.join(" / ", STATUSES)));

  SpuExportDataSource(DSLContext sql) {
    super(sql);
  }

  @Override
  public String key() {
    return "SPU";
  }

  @Override
  public String label() {
    return "SPU 列表";
  }

  @Override
  public String upstreamModule() {
    return "3.1.1.1 SPU 列表";
  }

  @Override
  public String permission() {
    return "catalog.spu.read";
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
    return " from spu s";
  }

  @Override
  protected String selectList(List<ExportColumn> columns) {
    // 各列的 SQL 表达式与 COLUMNS 一一对应；顺序由 COLUMNS 定义，不接受客户端指定。
    return "s.tenant_id,s.id,s.code,s.name,s.category,s.brand,s.status,s.description,"
        + "s.created_at,s.updated_at";
  }

  @Override
  public void conditions(ExportFilterInput spec, List<String> where, List<Object> binds) {
    if (spec == null) return;
    if (notBlank(spec.q())) {
      where.add("(s.code ilike ? escape '!' or s.name ilike ? escape '!')");
      binds.add("%" + escape(spec.q().trim()) + "%");
      binds.add("%" + escape(spec.q().trim()) + "%");
    }
    if (notBlank(spec.category())) equals("s.category", spec.category(), where, binds);
    if (notBlank(spec.brand())) equals("s.brand", spec.brand(), where, binds);
    if (spec.status() != null && !spec.status().isEmpty()) {
      var values =
          spec.status().stream()
              .filter(v -> notBlank(v))
              .map(String::trim)
              .map(String::toUpperCase)
              .distinct()
              .toList();
      if (!values.isEmpty()) {
        for (var v : values)
          CatalogService.check(STATUSES.contains(v), "无效的生命周期状态：" + v);
        where.add("s.status in (" + String.join(",", java.util.Collections.nCopies(values.size(), "?")) + ")");
        binds.addAll(values);
      }
    }
  }

  @Override
  public String orderBy(ExportSort sort, boolean desc) {
    // 恒定追加 id 决胜列：updated_at 相同的行也不会在分批边界上错位或重复。
    return " order by " + sort.column() + (desc ? " desc" : " asc") + ", s.id desc";
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }
}