package com.starcon.catalog;

import static com.starcon.catalog.ExportDataSource.*;
import static com.starcon.catalog.ExportModels.*;

import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * 往来单位联系人导出数据源（上游 3.6.3.1 地址与联系人）。
 *
 * <p><b>这个数据源是「PII 授权」这条要求的落点，不是顺手多加的一个来源。</b>
 * 蓝图 3.8.2.1 写死了「含联系方式等敏感字段的导出需显式授权并留审计」，
 * 而 SPU 与 SKU 两张表里**一个联系方式字段都没有** —— 只接这两个源，
 * 那条要求就只能落成一个永远为假的布尔开关，等于没做。
 *
 * <p>{@link ExportColumn#pii()} 为 true 的三列（EMAIL / PHONE / MOBILE）就是敏感字段：
 * 勾选它们必须由服务层校验 {@code piiPurpose} 非空，并写进 {@code export_job}
 * 的 {@code includes_pii / pii_fields / pii_purpose} 三列（DB CHECK 再拒一道）。
 *
 * <p>为什么是「显式授权」而不是「有权限就能导」：联系方式的批量外流是数据合规里
 * 单独成条的风险（GDPR / 数据出境），它的处置动作（谁、为什么、导了哪些字段、
 * 多少行）必须可回溯且**可举证**。权限回答的是「这个人能不能看」，
 * 授权回答的是「这次导出可不可以发生」——两件事，不是一件事。
 */
@Component
class ContactExportDataSource extends SqlExportDataSource {
  private static final List<ExportColumn> COLUMNS =
      List.of(
          new ExportColumn("PARTY_CODE", "往来单位编码", false, r -> r.get("party_code", String.class)),
          new ExportColumn("PARTY_NAME", "往来单位名称", false, r -> r.get("party_name", String.class)),
          new ExportColumn("PARTY_TYPE", "主体类型", false, r -> r.get("party_type", String.class)),
          new ExportColumn("NAME", "联系人姓名", false, r -> r.get("name", String.class)),
          new ExportColumn("POSITION", "职务", false, r -> r.get("position", String.class)),
          // ↓↓↓ 三个敏感字段：导出必须显式授权并填写用途
          new ExportColumn("EMAIL", "邮箱", true, r -> r.get("email", String.class)),
          new ExportColumn("PHONE", "电话", true, r -> r.get("phone", String.class)),
          new ExportColumn("MOBILE", "手机", true, r -> r.get("mobile", String.class)),
          // ↑↑↑
          new ExportColumn(
              "IS_PRIMARY", "是否默认联系人", false, r -> r.get("is_primary", Boolean.class) ? "是" : "否"),
          new ExportColumn(
              "ROLE_TYPES", "已指派身份", false, r -> r.get("role_types", String.class)),
          new ExportColumn("CREATED_AT", "创建时间", false, r -> instant(r.get("created_at", OffsetDateTime.class))),
          new ExportColumn("ID", "联系人 ID", false, r -> r.get("id", String.class)));

  private static final List<ExportSort> SORTS =
      List.of(
          new ExportSort("party_code", "往来单位编码", "pp.code"),
          new ExportSort("name", "联系人姓名", "c.name"),
          new ExportSort("created_at", "创建时间", "c.created_at"),
          new ExportSort("updated_at", "更新时间", "c.updated_at"));

  private static final List<ExportFilterInfo> FILTERS =
      List.of(
          new ExportFilterInfo("q", "关键字（姓名 / 邮箱 / 电话 / 手机）", "TEXT", true, ""),
          new ExportFilterInfo("partyId", "所属往来单位", "TEXT", true, ""),
          new ExportFilterInfo(
              "partyType", "主体类型", "ENUM", true, "取值：ORGANIZATION / INDIVIDUAL"),
          new ExportFilterInfo("primary", "仅默认联系人", "BOOLEAN", true, ""),
          new ExportFilterInfo("roleType", "已指派身份", "ENUM", true, "取值：SUPPLIER / CUSTOMER / CARRIER"));

  ContactExportDataSource(DSLContext sql) {
    super(sql);
  }

  @Override
  public String key() {
    return "CONTACT";
  }

  @Override
  public String label() {
    return "往来单位联系人";
  }

  @Override
  public String upstreamModule() {
    return "3.6.3.1 地址与联系人";
  }

  @Override
  public String permission() {
    // 与 PartyContactController 同一口径：联系人挂在已有的 catalog.spu.read 上。
    // 新造一套 catalog.party.* 词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
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
    // 身份（供应商 / 客户 / 承运商）是一对多，用子查询聚成一行而不是 join 出去——
    // join 会把一个联系人复制成三行，让 row_count 与实际联系人数对不上。
    return " from party_contact c join party pp on pp.tenant_id=c.tenant_id and pp.id=c.party_id";
  }

  @Override
  protected String selectList(List<ExportColumn> columns) {
    return "c.tenant_id,c.id,c.party_id,c.name,c.position,c.email,c.phone,c.mobile,c.is_primary,"
        + "c.created_at,c.updated_at,pp.code as party_code,pp.name as party_name,"
        + "pp.party_type,(select string_agg(pr.role_type,'/' order by pr.role_type)"
        + "      from party_role pr where pr.tenant_id=c.tenant_id and pr.party_id=c.party_id"
        + "      and pr.status='ACTIVE') as role_types";
  }

  @Override
  public void conditions(ExportFilterInput spec, List<String> where, List<Object> binds) {
    if (spec == null) return;
    if (notBlank(spec.q())) {
      where.add(
          "(c.name ilike ? escape '!' or coalesce(c.email,'') ilike ? escape '!'"
              + " or coalesce(c.phone,'') ilike ? escape '!' or coalesce(c.mobile,'') ilike ? escape '!')");
      for (int i = 0; i < 4; i++) binds.add("%" + escape(spec.q().trim()) + "%");
    }
    if (spec.partyId() != null) {
      where.add("c.party_id = ?");
      binds.add(spec.partyId());
    }
    if (notBlank(spec.partyType())) {
      var value = spec.partyType().trim().toUpperCase();
      CatalogService.check(
          List.of("ORGANIZATION", "INDIVIDUAL").contains(value), "无效的主体类型：" + spec.partyType());
      equals("pp.party_type", value, where, binds);
    }
    if (spec.primary() != null) {
      where.add("c.is_primary = ?");
      binds.add(spec.primary());
    }
    if (notBlank(spec.roleType())) {
      var value = spec.roleType().trim().toUpperCase();
      CatalogService.check(
          List.of("SUPPLIER", "CUSTOMER", "CARRIER").contains(value), "无效的身份：" + spec.roleType());
      where.add("exists (select 1 from party_role pr where pr.tenant_id=c.tenant_id"
          + " and pr.party_id=c.party_id and pr.role_type=?)");
      binds.add(value);
    }
  }

  @Override
  public String orderBy(ExportSort sort, boolean desc) {
    return " order by " + sort.column() + (desc ? " desc" : " asc") + ", c.id desc";
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant().toString();
  }
}