package com.starcon.catalog;

import static com.starcon.catalog.ExportDataSource.*;
import static com.starcon.catalog.ExportModels.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.8.2.1 导出的**纯校验规则**：字段选择、排序还原、筛选条件归属、PII 授权。
 *
 * <p>刻意做成不碰数据库的静态方法：这些是「请求对不对」的问题， 放一条从 HTTP 到落库的链路上测很难把它们单独测干净。这里全部是纯函数， 由 {@link
 * ExportJobService} 在开事务之前调用 —— 校验失败不该先占着一个事务。
 */
final class ExportRules {
  private ExportRules() {}

  /**
   * 解析字段清单：命中白名单、保留**数据源定义的列顺序**、去重。
   *
   * <p>保留列顺序而不是用户提交顺序：导出文件的列序要稳定可预期， 而「按你勾选的顺序」会让同一个模板两次导出得到不同列序的行，
   * 下游拿它做自动比对时会直接错位。用户能选择**选哪些**，不能选择**按什么顺序**。
   *
   * <p>返回的顺序即 {@code export_job.selected_fields} 与 CSV 表头的顺序。
   */
  static List<ExportColumn> resolveFields(ExportDataSource source, List<String> requested) {
    CatalogService.check(requested != null && !requested.isEmpty(), "请至少选择一个导出字段");
    var wanted = new LinkedHashSet<String>();
    for (var key : requested) {
      if (key == null || key.isBlank()) continue;
      wanted.add(key.trim().toUpperCase(Locale.ROOT));
    }
    CatalogService.check(!wanted.isEmpty(), "请至少选择一个导出字段");

    var byKey = new java.util.HashMap<String, ExportColumn>();
    for (var column : source.columns()) byKey.put(column.key(), column);

    var unknown = new ArrayList<String>();
    for (var key : wanted) if (!byKey.containsKey(key)) unknown.add(key);
    if (!unknown.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "以下字段不属于「"
              + source.label()
              + "」数据源："
              + String.join("、", unknown)
              + "。该数据源可导出字段："
              + source.columns().stream().map(ExportColumn::key).toList());

    return source.columns().stream().filter(c -> wanted.contains(c.key())).toList();
  }

  /** 解析排序键（排序还原）。未指定时用数据源的默认排序键，与列表页一致。 */
  static ExportSort resolveSort(ExportDataSource source, ExportSortInput input) {
    String key =
        input == null || input.key() == null || input.key().isBlank()
            ? source.defaultSortKey()
            : input.key().trim().toLowerCase(Locale.ROOT);
    for (var sort : source.sorts()) if (sort.key().equals(key)) return sort;
    throw new ResponseStatusException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "「"
            + source.label()
            + "」不支持按「"
            + key
            + "」排序。可排序字段："
            + source.sorts().stream().map(ExportSort::key).toList());
  }

  /** 排序方向：未指定时 desc（与列表页默认一致）。只认 asc / desc。 */
  static boolean resolveDesc(ExportSortInput input) {
    if (input == null || input.order() == null || input.order().isBlank()) return true;
    var order = input.order().trim().toLowerCase(Locale.ROOT);
    CatalogService.check(
        order.equals("asc") || order.equals("desc"), "排序方向只能是 asc 或 desc：" + input.order());
    return order.equals("desc");
  }

  /**
   * 校验筛选条件全部属于本数据源，且没有落在「上游投影未接入」的那几个上。
   *
   * <p>两条错误分开报：不属于本数据源（填错了地方）与属于但当前无法求值 （依赖 07 库存 / 05 定价 / 04 刊登）。后者必须点名归属服务，
   * 否则用户只会觉得「导出功能坏了」，而他填的条件其实是对的。
   */
  static void requireSupportedFilters(ExportDataSource source, ExportFilterInput spec) {
    if (spec == null) return;
    var supported = new java.util.HashMap<String, ExportFilterInfo>();
    for (var filter : source.filters()) supported.put(filter.key(), filter);

    var foreign = new ArrayList<String>();
    var unavailable = new ArrayList<String>();
    var reasons = new ArrayList<String>();

    for (var entry : presentFilters(spec).entrySet()) {
      var filter = supported.get(entry.getKey());
      if (filter == null) {
        foreign.add(labelOf(entry.getKey()));
        continue;
      }
      if (!filter.available()) {
        unavailable.add(filter.label());
        reasons.add(filter.label() + "（" + filter.note() + "）");
      }
    }

    if (!foreign.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "「"
              + source.label()
              + "」不支持以下筛选条件："
              + String.join("、", foreign)
              + "。该数据源可用筛选条件："
              + source.filters().stream()
                  .filter(ExportFilterInfo::available)
                  .map(ExportFilterInfo::label)
                  .toList());

    if (!unavailable.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "以下筛选条件依赖尚未接入的上游投影，无法求值：" + String.join("；", reasons) + "。请先接入对应服务，或改用其他条件。");
  }

  /**
   * PII 授权校验：本次导出含敏感字段时，理由必须非空。
   *
   * <p>返回本次导出涉及的**敏感字段键**（空列表 = 不含 PII）。 落库的是键（EMAIL / PHONE / MOBILE）而不是中文标签：键是稳定标识，
   * 标签随时可能改写，而审计要回答的是「导走了哪些字段」，那需要一份不随文案漂移的清单。
   *
   * <p>字段清单由**服务端按字段定义**推导，不采信客户端的「是否含 PII」—— 否则这个开关就只是个装饰：只要客户端说「不含 PII」，一次含邮箱的导出就能无声通过。
   */
  static List<String> requirePiiAuthorization(
      List<ExportColumn> columns, String purpose, String sourceLabel) {
    var piiColumns = columns.stream().filter(ExportColumn::pii).toList();
    if (piiColumns.isEmpty()) return List.of();
    var reason = purpose == null ? "" : purpose.trim();
    if (reason.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "本次导出包含敏感字段（"
              + piiColumns.stream().map(ExportColumn::label).toList()
              + "），必须填写导出用途后才能提交：用途会与操作人、时间、条件、字段清单一并留档，供合规回溯。");
    return piiColumns.stream().map(ExportColumn::key).toList();
  }

  /** 任务成功后到期的秒数（下载有效期）。 */
  static long expiresInSeconds(int ttlHours) {
    CatalogService.check(ttlHours > 0 && ttlHours <= 720, "下载有效期必须在 1~720 小时之间");
    return ttlHours * 3600L;
  }

  /** 把用户填的条件整理成「键 → 非空值」，供归属校验与审计回显共用同一份判断。 */
  static java.util.LinkedHashMap<String, Object> presentFilters(ExportFilterInput spec) {
    var out = new java.util.LinkedHashMap<String, Object>();
    if (spec == null) return out;
    put(out, "spuId", spec.spuId());
    put(out, "category", spec.category());
    put(out, "status", spec.status());
    put(out, "q", spec.q());
    put(out, "barcode", spec.barcode());
    put(out, "spec", spec.spec());
    put(out, "brand", spec.brand());
    put(out, "partyId", spec.partyId());
    put(out, "partyType", spec.partyType());
    put(out, "primary", spec.primary());
    put(out, "roleType", spec.roleType());
    put(out, "onHandMin", spec.onHandMin());
    put(out, "onHandMax", spec.onHandMax());
    put(out, "priceMin", spec.priceMin());
    put(out, "priceMax", spec.priceMax());
    put(out, "outOfStock", spec.outOfStock());
    put(out, "onSale", spec.onSale());
    put(out, "platform", spec.platform());
    return out;
  }

  private static void put(java.util.LinkedHashMap<String, Object> out, String key, Object value) {
    if (value == null) return;
    if (value instanceof String text && text.isBlank()) return;
    if (value instanceof List<?> list && list.isEmpty()) return;
    out.put(key, value);
  }

  /** 筛选键的中文名，供错误信息点名（取该键在任意数据源里的词表标签即可）。 */
  private static String labelOf(String key) {
    return switch (key) {
      case "spuId" -> "所属 SPU";
      case "category" -> "分类";
      case "status" -> "状态";
      case "q" -> "关键字";
      case "barcode" -> "条码";
      case "spec" -> "规格关键字";
      case "brand" -> "品牌";
      case "partyId" -> "所属往来单位";
      case "partyType" -> "主体类型";
      case "primary" -> "仅默认联系人";
      case "roleType" -> "已指派身份";
      case "onHandMin" -> "在仓数量下限";
      case "onHandMax" -> "在仓数量上限";
      case "priceMin" -> "售价下限";
      case "priceMax" -> "售价上限";
      case "outOfStock" -> "是否缺货";
      case "onSale" -> "是否在售";
      case "platform" -> "在售平台";
      default -> key;
    };
  }
}
