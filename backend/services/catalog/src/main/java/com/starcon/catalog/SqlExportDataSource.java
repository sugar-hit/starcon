package com.starcon.catalog;

import static com.starcon.catalog.ExportDataSource.*;
import static com.starcon.catalog.ExportModels.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jooq.DSLContext;

/**
 * {@link ExportDataSource} 的 SQL 通用实现：把 count 与分批 stream 收在一处。
 *
 * <p><b>为什么这两件事必须写在同一个类里</b>：导出记录的 {@code row_count} 与文件里的
 * 实际行数是同一个数字的两种说法，两者若各拼一遍 WHERE，就可能出现「记录说 120 行、
 * 文件里 118 行」——那是一个谁也解释不清的不一致。所以这里让 count 与 stream
 * 共用同一个 {@link #conditions}，且 stream 必须带 {@link #orderBy} 才能分批。
 *
 * <p><b>租户与数据范围在这里自动生效</b>：count / stream 都只用注入的 {@link DSLContext}，
 * 不自己开事务。调用方（{@link ExportJobService}）在 {@code access.run(actor, permission)}
 * 开的租户事务里调用它们，于是 {@code app.current_tenant} 与 V3 数据范围依赖的
 * {@code app.actor / app.grants / app.department} 全部就位：
 * SELF / DEPARTMENT 范围的用户在本模块导出的行，与他在列表页看到的行**逐行相同**。
 *
 * <p><b>不自己开事务的另一半理由</b>：一次大导出会跑很久，摊着一个事务不放既长时间占着
 * 行锁，也让连接池被长期占住。分批由上层逐批开短事务，本类只负责「这一批」。
 */
abstract class SqlExportDataSource implements ExportDataSource {
  private final DSLContext sql;

  protected SqlExportDataSource(DSLContext sql) {
    this.sql = sql;
  }

  /** SELECT 列表：按 {@code columns} 的顺序给出各列的 SQL 表达式（**必须是代码常量**）。 */
  protected abstract String selectList(List<ExportColumn> columns);

  @Override
  public long count(ExportFilterInput spec) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(spec, where, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    return sql.fetchOne("select count(*)" + from() + clause, binds.toArray()).get(0, Long.class);
  }

  @Override
  public void stream(
      ExportFilterInput spec,
      ExportSort sort,
      boolean desc,
      List<ExportColumn> columns,
      long offset,
      int limit,
      Consumer<List<String[]>> sink) {
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(spec, where, binds);
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    binds.add(limit);
    binds.add(offset);
    var rows =
        sql.fetch(
            "select "
                + selectList(columns)
                + from()
                + clause
                + orderBy(sort, desc)
                + " limit ? offset ?",
            binds.toArray());
    if (rows.isEmpty()) return;
    sink.accept(rows.map(r -> row(columns, r)));
  }

  /** 把一条结果行按字段顺序取成文本数组（null 归一为空串）。 */
  private static String[] row(List<ExportColumn> columns, org.jooq.Record record) {
    var cells = new String[columns.size()];
    for (int i = 0; i < columns.size(); i++) {
      String value = columns.get(i).cell().apply(record);
      cells[i] = value == null ? "" : value;
    }
    return cells;
  }

  /** LIKE 模式转义：用户输入里的 % 与 _ 只当字面量（与 3.1.2.1 同一套转义）。 */
  protected static String escape(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  protected static void ilike(String column, String value, List<String> where, List<Object> binds) {
    where.add(column + " ilike ? escape '!'");
    binds.add("%" + escape(value.trim()) + "%");
  }

  protected static void equals(String column, String value, List<String> where, List<Object> binds) {
    where.add(column + " = ?");
    binds.add(value.trim());
  }
}