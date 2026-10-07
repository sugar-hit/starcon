package com.starcon.catalog;

import static com.starcon.catalog.CatalogService.check;
import static com.starcon.catalog.ImportWizardModels.*;

import com.starcon.tenancy.Ids;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.8.1.1 的模板字典与字段映射配置（五环节里的第 1、3 步）。
 *
 * <p><b>模板为什么落库而不是现拼</b>：「下载模板」与「字段映射」共用同一份列定义。
 * 现拼的话这两处各写一份列名，改了其中一处，运营下载到的模板与预校验认的列就对不上，
 * 表现是「必填缺失」但用户明明按模板填了。落库之后它们读的是同一张
 * {@code import_template_column}，对不上在结构上就不可能发生。
 *
 * <p><b>新建模板时给默认列</b>：{@link ImportRules#defaultColumns} 里有五个对象的标准列，
 * 运营建模板时不必从零列头；但列可以自己增删改 —— 不同品类要的字段本来就不一样。
 * 只在 {@code columns} 为空时套默认列，显式传了就以传入的为准。
 *
 * <p><b>字段映射可复用</b>：不同供应商列名不同（{@code item_sku} / {@code 商家编码}），
 * 映射一次存成具名配置，之后每批文件直接套。这是蓝图把「字段映射配置」列为输入的原因。
 */
@Service
public class ImportTemplateService {
  private final DSLContext sql;

  public ImportTemplateService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 模板字典

  /** 模板列表（分页 + 可选按目标对象过滤）。 */
  public ImportTemplatePage templates(
      UUID tenantId, String targetEntity, String q, int page, int size) {
    StringBuilder where = new StringBuilder(" where 1=1");
    List<Object> args = new ArrayList<>();
    if (targetEntity != null && !targetEntity.isBlank()) {
      where.append(" and target_entity=?");
      args.add(targetEntity.trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (q != null && !q.isBlank()) {
      where.append(" and (code ilike ? or name ilike ?)");
      args.add("%" + q.trim() + "%");
      args.add("%" + q.trim() + "%");
    }
    long total =
        sql.fetchOne("select count(*) from import_template" + where, args.toArray()).get(0, Long.class);
    int limit = Math.min(Math.max(size, 1), 100);
    int offset = Math.max(page, 0) * limit;
    var rows =
        sql.fetch(
            "select t.*,(select count(*) from import_template_column c"
                + " where c.tenant_id=t.tenant_id and c.template_id=t.id) as column_count"
                + " from import_template t" + where
                + " order by t.code limit ? offset ?",
            bindings(args, limit, offset));
    return new ImportTemplatePage(rows.map(ImportTemplateService::templateRow), (int) total);
  }

  /** 模板详情（含列定义）。 */
  public ImportTemplateDetail detail(UUID tenantId, UUID id) {
    var t = sql.fetchOne("select * from import_template where id=?", id);
    if (t == null) throw notFound("模板不存在或已删除");
    return new ImportTemplateDetail(templateRow(t, columnCount(id)), columns(tenantId, id));
  }

  /** 模板列（按 ordinal 排序，ordinal 相同按 field_key 稳定排序）。 */
  public List<ImportTemplateColumn> columns(UUID tenantId, UUID templateId) {
    return sql.fetch(
            "select * from import_template_column where template_id=? order by ordinal,field_key", templateId)
        .map(ImportTemplateService::columnOf);
  }

  /**
   * 更新模板的名称、备注与启用状态。
   *
   * <p><b>不提供改列的端点</b>：模板列一旦被导入任务引用，删列会让
   * 「这批数据当初是按哪几列解析的」这个问题失去答案（V29 的触发器已钉死不可删），
   * 而改列头会让已保存的映射配置按新含义解释旧数据。所以列要改就新建一个模板。
   */
  public ImportTemplateDetail update(UUID tenantId, UUID id, ImportTemplateInput input) {
    detail(tenantId, id); // 不存在直接 404
    sql.execute(
        "update import_template set name=?,note=?,enabled=?,version=version+1,updated_at=now() where id=?",
        input.name().trim(), input.note() == null ? "" : input.note(),
        input.enabled() == null || input.enabled(), id);
    return detail(tenantId, id);
  }

  /**
   * 删除模板。
   *
   * <p>被任务引用过就删不掉（DB 的 RESTRICT 会拒），这里把 PG 的报错翻成人话：
   * 「这个模板已经导入过数据，改列头会让历史任务的解释变样，请改用停用」。
   */
  public void delete(UUID tenantId, UUID id) {
    try {
      if (sql.execute("delete from import_template where id=?", id) == 0)
        throw notFound("模板不存在或已删除");
    } catch (DataAccessException e) {
      throw conflict(
          "模板已经被导入任务引用，不能删除：任务记录里存的是这批数据按哪几列解析的，"
              + "删掉模板就无法解释这批历史数据。要停用它请把 enabled 设为关闭（历史任务照常可读）");
    }
  }

  /**
   * 新建模板。
   *
   * <p>{@code columns} 为空时套 {@link ImportRules#defaultColumns} 的标准列 ——
   * 新建 SPU 模板最常见的情形就是「先按标准列跑通，再按品类加列」。
   */
  public ImportTemplateDetail create(UUID tenantId, ImportTemplateInput input) {
    String code = input.code().trim();
    String name = input.name().trim();
    String entity = input.targetEntity().trim().toUpperCase(java.util.Locale.ROOT);
    check(ImportRules.entityOf(entity) != null, "目标对象不合法：" + input.targetEntity()
        + "（可用：SPU / SKU / ATTRIBUTE / PACKAGE / BARCODE）");
    UUID id = Ids.next();
    sql.execute(
        "insert into import_template(id,tenant_id,code,name,note,target_entity,enabled)"
            + " values(?,?,?,?,?,?,?)",
        id, tenantId, code, name, input.note() == null ? "" : input.note(), entity,
        input.enabled() == null || input.enabled());

    List<ImportTemplateColumnInput> columns =
        input.columns() == null || input.columns().isEmpty()
            ? defaultColumnInputs(entity)
            : input.columns();
    int matchKeys = 0;
    for (int i = 0; i < columns.size(); i++) {
      var c = columns.get(i);
      boolean matchKey = c.matchKey() != null && c.matchKey();
      if (matchKey) matchKeys++;
      sql.execute(
          "insert into import_template_column(tenant_id,template_id,field_key,header,data_type,"
              + "required,match_key,sample_value,help,ordinal) values(?,?,?,?,?,?,?,?,?,?)",
          tenantId, id, c.fieldKey().trim(), c.header().trim(),
          c.dataType().trim().toUpperCase(java.util.Locale.ROOT),
          c.required() != null && c.required(), matchKey,
          c.sampleValue() == null ? "" : c.sampleValue(), c.help() == null ? "" : c.help(),
          c.ordinal() == null ? i : c.ordinal());
    }
    // 匹配键至多一个：两个匹配键时「按编码匹配」不知道按哪个找。
    // 这里显式拒绝（而不是等部分唯一索引报一个看不懂的 23505）。
    check(matchKeys <= 1, "一个模板最多只能有一个匹配键（用于按编码匹配定位记录），当前有 " + matchKeys + " 个");
    return detail(tenantId, id);
  }

  /** 目标对象的标准列，转成输入形状。 */
  private static List<ImportTemplateColumnInput> defaultColumnInputs(String entity) {
    List<ImportTemplateColumnInput> out = new ArrayList<>();
    int ordinal = 0;
    for (var dc : ImportRules.defaultColumns(ImportRules.Entity.valueOf(entity)))
      out.add(new ImportTemplateColumnInput(
          dc.fieldKey(), dc.header(), dc.dataType(), dc.required(), dc.matchKey(), dc.sample(), dc.help(), ordinal++));
    return out;
  }

  /**
   * 生成可下载的模板 CSV：列头一行 + 示例行。
   *
   * <p>示例行不是装饰：运营照着示例行填，比对着列头猜格式的出错率低得多
   * （「日期」列到底要不要写时间、条码体系填 {@code EAN-13} 还是 {@code EAN_13}，
   * 这类差异在示例行里就是确定的）。
   *
   * <p>模板的列是**导入模板的列**，不是「文件里必须有的列」：
   * 运营可以只填一部分列，其余留空。所以列头全部写全，不按 required 裁剪。
   */
  public ImportTemplateFile csv(UUID tenantId, UUID id) {
    var detail = detail(tenantId, id);
    var columns = detail.columns();
    List<String> headers = new ArrayList<>();
    List<String> samples = new ArrayList<>();
    for (var c : columns) {
      headers.add(c.header());
      samples.add(c.sampleValue());
    }
    byte[] content =
        ImportCsvParser.write(headers, List.of(samples));
    String fileName = detail.template().code() + "-template.csv";
    return new ImportTemplateFile(fileName, "text/csv; charset=UTF-8", content);
  }

  // ================================================================ 字段映射配置

  /** 具名字段映射列表（可按模板过滤）。 */
  public ImportFieldMappingPage mappings(UUID tenantId, UUID templateId) {
    String where = templateId == null ? " where 1=1" : " where template_id=?";
    Object[] args = templateId == null ? new Object[0] : new Object[] {templateId};
    var rows = sql.fetch("select * from import_field_mapping" + where + " order by name", args);
    return new ImportFieldMappingPage(rows.map(ImportTemplateService::mappingRow), rows.size());
  }

  /**
   * 保存可复用的字段映射。
   *
   * <p>映射的键必须都是该模板真实存在的列：写进一个不存在的列会让它
   * 在预校验时静默不生效（看起来配好了，实际那一列没被读到）。
   * 所以这里逐键核对，认不出的键直接点名报错。
   */
  public ImportFieldMappingRow saveMapping(UUID tenantId, ImportFieldMappingInput input) {
    UUID templateId = input.templateId();
    var declared = new java.util.LinkedHashSet<String>();
    for (var c : sql.fetch(
        "select field_key from import_template_column where template_id=?", templateId))
      declared.add(c.get("field_key", String.class));
    check(!declared.isEmpty(), "模板还没有任何列定义，请先建好模板列");
    List<String> unknown = input.mapping().keySet().stream().filter(k -> !declared.contains(k)).toList();
    check(unknown.isEmpty(), "映射里有该模板不存在的字段：" + String.join("、", unknown));
    List<String> blank = input.mapping().entrySet().stream()
        .filter(e -> e.getValue() == null || e.getValue().isBlank()).map(Map.Entry::getKey).toList();
    check(blank.isEmpty(), "映射的值（上传文件的列名）不能为空：" + String.join("、", blank));

    String name = input.name().trim();
    var existing =
        sql.fetchOne("select * from import_field_mapping where name=?", name);
    UUID id;
    if (existing == null) {
      id = Ids.next();
      try {
        sql.execute(
            "insert into import_field_mapping(id,tenant_id,template_id,name,mapping,note)"
                + " values(?,?,?,?,?::jsonb,?)",
            id, tenantId, templateId, name, json(input.mapping()),
            input.note() == null ? "" : input.note());
      } catch (DataAccessException e) {
        throw conflict("映射配置名已被并发占用，请刷新后重试");
      }
    } else {
      id = existing.get("id", UUID.class);
      check(existing.get("template_id", UUID.class).equals(templateId),
          "同名映射「" + name + "」已存在且属于另一个模板，请换一个名字或先改那一条");
      sql.execute(
          "update import_field_mapping set mapping=?::jsonb,note=?,version=version+1,updated_at=now() where id=?",
          json(input.mapping()), input.note() == null ? "" : input.note(), id);
    }
    return mappingRow(sql.fetchOne("select * from import_field_mapping where id=?", id));
  }

  /** 删除映射配置。任务里存的是快照，删除不影响历史任务的解释能力。 */
  public void deleteMapping(UUID tenantId, UUID id) {
    int deleted = sql.execute("delete from import_field_mapping where id=?", id);
    if (deleted == 0) throw notFound("映射配置不存在或已删除");
  }

  // ================================================================ 行映射

  private static ImportTemplateRow templateRow(Record r) {
    return templateRow(r, r.get("column_count", Long.class).intValue());
  }

  private static ImportTemplateRow templateRow(Record r, int columnCount) {
    return new ImportTemplateRow(
        r.get("id", UUID.class), r.get("code", String.class), r.get("name", String.class),
        r.get("note", String.class), r.get("target_entity", String.class),
        r.get("enabled", Boolean.class), columnCount, r.get("version", Long.class),
        instant(r, "created_at"), instant(r, "updated_at"));
  }

  private static ImportTemplateColumn columnOf(Record r) {
    return new ImportTemplateColumn(
        r.get("field_key", String.class), r.get("header", String.class), r.get("data_type", String.class),
        r.get("required", Boolean.class), r.get("match_key", Boolean.class),
        r.get("sample_value", String.class), r.get("help", String.class), r.get("ordinal", Integer.class));
  }

  private static ImportFieldMappingRow mappingRow(Record r) {
    return new ImportFieldMappingRow(
        r.get("id", UUID.class), r.get("template_id", UUID.class), r.get("name", String.class),
        readJson(r.get("mapping")), r.get("note", String.class), r.get("version", Long.class),
        instant(r, "created_at"), instant(r, "updated_at"));
  }

  static java.time.Instant instant(Record r, String column) {
    var v = r.get(column, java.time.OffsetDateTime.class);
    return v == null ? null : v.toInstant();
  }

  /**
   * 读回 jsonb 里的映射。
   *
   * <p><b>jOOQ 在本项目里把 jsonb 取成 {@link org.jooq.JSON}（具体是 {@code JSONB}）</b>，
   * 既不是 {@code Map} 也不是 {@code String}。只处理 Map 的话读回来永远是空 map ——
   * 症状是「映射明明猜对了，预校验却说每列都没有对应」。所以三种形态都要接。
   */
  @SuppressWarnings("unchecked")
  static Map<String, String> readJson(Object raw) {
    Map<String, String> out = new LinkedHashMap<>();
    if (raw == null) return out;
    if (raw instanceof Map<?, ?> m) {
      m.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
      return out;
    }
    String text = null;
    if (raw instanceof org.jooq.JSON json) text = json.data();
    else if (raw instanceof String s) text = s;
    else if (raw instanceof java.sql.SQLXML xml) {
      try {
        text = xml.getString();
      } catch (Exception e) {
        throw new IllegalStateException("jsonb 内容无法读取", e);
      }
    } else {
      // 兜底：jOOQ 的 JSON/JSONB 的 toString() 就是 JSON 文本本身。
      String asText = String.valueOf(raw);
      if (asText.startsWith("{")) text = asText;
    }
    if (text == null || text.isBlank()) return out;
    try {
      var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
      if (node.isObject())
        node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().isNull() ? "" : e.getValue().asText()));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("jsonb 内容无法解析：" + text, e);
    }
    return out;
  }

  /** 把映射序列化成 jsonb 文本。手写转义容易漏（控制字符、Unicode），交给 Jackson。 */
  static String json(Map<String, String> mapping) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(mapping);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("字段映射无法序列化", e);
    }
  }

  private int columnCount(UUID templateId) {
    return sql.fetchOne("select count(*) from import_template_column where template_id=?", templateId)
        .get(0, Long.class).intValue();
  }

  private static Object[] bindings(List<Object> args, int limit, int offset) {
    var out = new ArrayList<Object>(args);
    out.add(limit);
    out.add(offset);
    return out.toArray();
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
