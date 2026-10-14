package com.starcon.catalog;

import static com.starcon.catalog.ExportModels.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jooq.Record;

/**
 * 3.8.2.1 导出的**数据源注册式扩展点**。
 *
 * <p>蓝图的上游是「3.1.1.1 / 3.1.2.1 / 3.6.2 等列表页」——一个**开集**： 今天交付了 SPU、SKU 与联系人三个来源，明天 3.6.2.1
 * 报价列表并入还要再接一个。 所以「导出」在本模块里不是一张宽表加一个查询，而是：
 *
 * <ul>
 *   <li>{@link ExportDataSource}：一个数据源自带**字段白名单、排序白名单、筛选词表、 以及 count / stream 两条查询**；
 *   <li>{@link ExportDataSources}：把容器里所有实现收集成 {@code key → 数据源} 的注册表。
 * </ul>
 *
 * <p>加一个数据源 = 新增一个 {@code @Component} 实现 + 在注入的 List 里自动出现，
 * <b>不需要改迁移、不需要改服务层、不需要改前端</b>：字段选择器、排序还原、 授权校验、异步执行、有效期与审计全部由 {@link ExportJobService} 按注册表统一处理。
 *
 * <p>字段与排序都是**闭集白名单**：{@link ExportColumn#key()} 与 {@link ExportSort#key()} 会被直接拼进 SQL 的 SELECT /
 * ORDER BY，所以它们只能是代码里写死的常量字面量， 客户端传进来的字符串只用于**查表命中**，命中不了就走 422，绝不拼进 SQL。
 */
public interface ExportDataSource {
  /** 数据源键：{@code SPU} / {@code SKU} / {@code CONTACT}。同时也是迁移里 export_job.source_key 的取值。 */
  String key();

  /** 中文名，用于界面与导出记录回显。 */
  String label();

  /** 上游列表模块，如「3.1.1.1 SPU 列表」。导出结果与该列表页同源，这一点要让用户看得见。 */
  String upstreamModule();

  /** 读权限，如 {@code catalog.spu.read}。导出读的就是列表数据，用列表的权限，不新造词表。 */
  String permission();

  /**
   * 该数据源是否已接通。
   *
   * <p>为 false 时仍会被注册表收录并出现在能力声明里（附说明），但服务层拒绝执行。 「尚未交付」要变成一句可读的原因，而不是让用户点下去撞 404。
   */
  default boolean available() {
    return true;
  }

  /** available=false 时给出的原因（写明缺哪个上游模块）。 */
  default String unavailableNote() {
    return "";
  }

  /** 字段白名单。顺序即导出文件的列顺序。 */
  List<ExportColumn> columns();

  /** 排序白名单。至少要含一个默认排序项。 */
  List<ExportSort> sorts();

  /** 默认排序键：用户没带 sort 时用它（与列表页默认排序一致）。 */
  String defaultSortKey();

  /**
   * 筛选词表：key 与 {@link ExportFilterInput} 的字段名一致。
   *
   * <p>服务层据此拒绝「不属于本数据源」的筛选条件——静默忽略会让用户以为筛过了。
   */
  List<ExportFilterInfo> filters();

  /** 单次导出行数上限。超限明确报错让操作人收窄条件，而不是悄悄截断。 */
  int maxRows();

  /** FROM 子句（含别名），如 {@code " from sku s join spu p on ..."}。 */
  String from();

  /**
   * 往 {@code where} 追加条件、往 {@code binds} 追加绑定值。
   *
   * <p>所有值走绑定；标识符只从本类白名单取。实现不得自己拼用户输入。
   */
  void conditions(ExportFilterInput spec, List<String> where, List<Object> binds);

  /** 计数：与 {@link #stream} 共用 {@link #conditions}，所以两者不可能筛出不同集合。 */
  long count(ExportFilterInput spec);

  /**
   * 分批读取导出行。
   *
   * <p>{@code offset} 由调用方递增；实现只负责取这一批并交给 {@code sink}。 排序必须与 {@link #orderBy} 一致，否则批次之间会错位或重复。
   */
  void stream(
      ExportFilterInput spec,
      ExportSort sort,
      boolean desc,
      List<ExportColumn> columns,
      long offset,
      int limit,
      java.util.function.Consumer<List<String[]>> sink);

  /**
   * ORDER BY 子句（含别名与恒定决胜列，保证分批之间稳定）。
   *
   * <p>{@code desc} 是**调用方还原列表页排序**的结果（列表页默认 desc，列头点了变 asc）。 它必须一路传到这里：丢了它，「按价格升序排完序导出来是降序」就发生了，
   * 而那份文件看上去完全正常，只是顺序错了 —— 用户往往要靠这个顺序做取舍。
   */
  String orderBy(ExportSort sort, boolean desc);

  // ================================================================ 字段与排序

  /**
   * 一个可导出列。
   *
   * @param key 稳定字段键，同时是 CSV 列的语义标识与审计里的字段清单元素
   * @param label 列头文字（**导出文件里写的就是它**，且会随任务冻结进 field_headers）
   * @param pii 是否为敏感字段（联系方式等）。true 时该导出必须显式授权并写理由
   * @param cell 从结果行取这一列的文本值；返回 null 表示空单元格
   */
  record ExportColumn(String key, String label, boolean pii, Function<Record, String> cell) {
    public ExportFieldInfo info() {
      return new ExportFieldInfo(key, label, pii, pii ? "敏感字段：导出需显式授权并填写用途" : "");
    }
  }

  /**
   * 一个可排序键。
   *
   * @param key 稳定排序键（客户端只用于查表命中）
   * @param label 中文名
   * @param column 排序列的 SQL 表达式，**必须来自代码常量**，不接受任何外部拼接
   */
  record ExportSort(String key, String label, String column) {
    public ExportSortInfo info() {
      return new ExportSortInfo(key, label);
    }
  }

  /** 取一个批次里所有列的文本值，null 归一成空串（CSV 里没有 null，只有空单元格）。 */
  static List<String[]> cells(List<ExportColumn> columns, Record record) {
    var out = new ArrayList<String[]>(1);
    var row = new String[columns.size()];
    for (int i = 0; i < columns.size(); i++) {
      String value = columns.get(i).cell().apply(record);
      row[i] = value == null ? "" : value;
    }
    out.add(row);
    return out;
  }
}
