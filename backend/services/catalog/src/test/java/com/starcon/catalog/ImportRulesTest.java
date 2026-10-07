package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 3.8.1.1 预校验规则的纯单元测试。
 *
 * <p>不启数据库：规则的边界（空串算不算缺、{@code 0} 算不算填、毛重小于净重拦不拦、
 * 属性行的复合自然键）必须在集成测试之外被单独钉住 ——
 * 在集成测试里这些用例很容易被「顺手写对」从而永远测不到真正的分支。
 */
class ImportRulesTest {

  private static List<ImportRules.Column> columns(ImportRules.DefaultColumn... defs) {
    List<ImportRules.Column> out = new ArrayList<>();
    for (var d : defs) out.add(new ImportRules.Column(d.fieldKey(), d.dataType(), d.required(), d.matchKey()));
    return out;
  }

  private static Map<String, String> values(String... kv) {
    Map<String, String> out = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) out.put(kv[i], kv[i + 1]);
    return out;
  }

  private static List<String> codes(List<ImportRules.Problem> problems) {
    return problems.stream().map(ImportRules.Problem::code).toList();
  }

  private static List<ImportRules.Column> spuColumns() {
    return columns(ImportRules.defaultColumns(ImportRules.Entity.SPU).toArray(ImportRules.DefaultColumn[]::new));
  }

  // ================================================================ 默认列

  @Test
  void 每个目标对象的默认列都恰好有一个匹配键() {
    for (var entity : ImportRules.Entity.values()) {
      long matchKeys =
          ImportRules.defaultColumns(entity).stream().filter(ImportRules.DefaultColumn::matchKey).count();
      assertThat(matchKeys).as("%s 的匹配键数量", entity).isEqualTo(1);
    }
  }

  @Test
  void 属性导入的默认列带SKU编码列() {
    // 「仅 SKU」属性必须有地方填 SKU 编码，否则那一类属性根本没法通过向导导入。
    assertThat(ImportRules.defaultColumns(ImportRules.Entity.ATTRIBUTE))
        .extracting(ImportRules.DefaultColumn::fieldKey)
        .contains("sku_code");
  }

  // ================================================================ 必填与类型

  @Test
  void 必填列缺值报REQUIRED() {
    var problems =
        ImportRules.validateRow(
            ImportRules.Entity.SPU, ImportRules.Mode.CREATE, spuColumns(), values("spu_code", "S1"),
            ImportRules.Facts.clean());
    assertThat(codes(problems)).contains("REQUIRED");
    assertThat(ImportRules.blocking(problems)).isTrue();
  }

  @Test
  void 零值算填了不算缺失() {
    // 「0 件重」和「没填」是两件事：把 0 当缺失会让运营永远导不进合法的零值数据。
    var problems =
        ImportRules.validateRow(
            ImportRules.Entity.PACKAGE, ImportRules.Mode.CREATE,
            columns(ImportRules.defaultColumns(ImportRules.Entity.PACKAGE).toArray(ImportRules.DefaultColumn[]::new)),
            values("sku_code", "S1", "net_weight_g", "0"),
            ImportRules.Facts.clean());
    assertThat(codes(problems)).doesNotContain("REQUIRED");
  }

  @Test
  void 空白格按缺失处理而不是按类型错误() {
    var problems =
        ImportRules.validateRow(
            ImportRules.Entity.PACKAGE, ImportRules.Mode.CREATE,
            columns(ImportRules.defaultColumns(ImportRules.Entity.PACKAGE).toArray(ImportRules.DefaultColumn[]::new)),
            values("sku_code", "S1", "net_weight_g", "   "),
            ImportRules.Facts.clean());
    // 空值归必填/可选管，不该同时报「不是数字」——两种错误同时出现会让人以为格式坏了。
    assertThat(codes(problems)).doesNotContain("INVALID_TYPE");
  }

  @Test
  void 数字列填字母报INVALID_TYPE() {
    var problems =
        ImportRules.validateRow(
            ImportRules.Entity.PACKAGE, ImportRules.Mode.CREATE,
            columns(ImportRules.defaultColumns(ImportRules.Entity.PACKAGE).toArray(ImportRules.DefaultColumn[]::new)),
            values("sku_code", "S1", "net_weight_g", "abc"),
            ImportRules.Facts.clean());
    assertThat(codes(problems)).contains("INVALID_TYPE");
    assertThat(ImportRules.blocking(problems)).isTrue();
  }

  @Test
  void 类型检查逐个数据类型都认得() {
    var text = new ImportRules.Column("a", "TEXT", false, false);
    var dec = new ImportRules.Column("a", "DECIMAL", false, false);
    var integer = new ImportRules.Column("a", "INTEGER", false, false);
    var bool = new ImportRules.Column("a", "BOOLEAN", false, false);
    var date = new ImportRules.Column("a", "DATE", false, false);
    var ts = new ImportRules.Column("a", "TIMESTAMPTZ", false, false);

    assertThat(ImportRules.checkType(text, "任意文本")).isNull();
    assertThat(ImportRules.checkType(dec, "-12.75")).isNull();
    assertThat(ImportRules.checkType(integer, "12")).isNull();
    assertThat(ImportRules.checkType(integer, "12.5")).as("INTEGER 不接受小数").isNotNull();
    assertThat(ImportRules.checkType(bool, "是")).isNull();
    assertThat(ImportRules.checkType(bool, "true")).isNull();
    assertThat(ImportRules.checkType(bool, "也许")).isNotNull();
    assertThat(ImportRules.checkType(date, "2026-10-07")).isNull();
    assertThat(ImportRules.checkType(date, "2026/10/07")).isNotNull();
    assertThat(ImportRules.checkType(ts, "2026-10-07T10:00:00+08:00")).isNull();
    assertThat(ImportRules.checkType(ts, "2026-10-07 10:00")).as("TIMESTAMPTZ 必须带时区").isNotNull();
  }

  // ================================================================ 模式语义

  @Test
  void 新增模式遇到已存在编码只警告不阻断() {
    // 这是 WARNING 而不是 ERROR：CREATE 模式「不覆盖已存在的」是它的承诺，
    // 报成错误会让「导入 100 条、其中 3 条早就导过」整个批次失败。
    var problems =
        ImportRules.validateRow(
            ImportRules.Entity.SPU, ImportRules.Mode.CREATE, spuColumns(),
            values("spu_code", "S1", "spu_name", "商品"),
            new ImportRules.Facts("S1", true, false, null, List.of()));
    assertThat(codes(problems)).contains("EXISTS_IN_CREATE_MODE");
    assertThat(ImportRules.blocking(problems)).as("已存在不该阻断导入").isFalse();
  }

  @Test
  void 更新模式遇到不存在的记录必须阻断() {
    var problems =
        ImportRules.validateRow(
            ImportRules.Entity.SPU, ImportRules.Mode.UPDATE, spuColumns(),
            values("spu_code", "S1", "spu_name", "商品"),
            new ImportRules.Facts("S1", false, false, null, List.of()));
    assertThat(codes(problems)).contains("NOT_FOUND_IN_UPDATE_MODE");
    assertThat(ImportRules.blocking(problems)).isTrue();
  }

  @Test
  void 更新模式缺匹配键必须阻断() {
    var problems =
        ImportRules.validateRow(
            ImportRules.Entity.SPU, ImportRules.Mode.UPDATE, spuColumns(),
            values("spu_code", "  ", "spu_name", "商品"), ImportRules.Facts.clean());
    assertThat(codes(problems)).contains("MATCH_KEY_REQUIRED");
  }

  @Test
  void 按编码匹配模式两种情况都放行() {
    var columns = spuColumns();
    var existing =
        ImportRules.validateRow(
            ImportRules.Entity.SPU, ImportRules.Mode.MATCH_BY_CODE, columns,
            values("spu_code", "S1", "spu_name", "商品"),
            new ImportRules.Facts("S1", true, false, null, List.of()));
    var missing =
        ImportRules.validateRow(
            ImportRules.Entity.SPU, ImportRules.Mode.MATCH_BY_CODE, columns,
            values("spu_code", "S2", "spu_name", "商品"),
            new ImportRules.Facts("S2", false, false, null, List.of()));
    assertThat(ImportRules.blocking(existing)).isFalse();
    assertThat(ImportRules.blocking(missing)).isFalse();
  }

  // ================================================================ 自然键与重复

  @Test
  void 属性行按复合自然键判重而不是单列() {
    // 一个商品有几十个属性：同一个 spu_code 出现很多行是正常数据，不是重复。
    var map1 = values("spu_code", "S1", "attribute_code", "battery", "attribute_value", "30");
    var map2 = values("spu_code", "S1", "attribute_code", "material", "attribute_value", "ABS");
    assertThat(ImportRules.naturalKey(ImportRules.Entity.ATTRIBUTE, map1))
        .isNotEqualTo(ImportRules.naturalKey(ImportRules.Entity.ATTRIBUTE, map2));

    var map3 = values("spu_code", "S1", "attribute_code", "battery", "attribute_value", "40");
    assertThat(ImportRules.naturalKey(ImportRules.Entity.ATTRIBUTE, map1))
        .isEqualTo(ImportRules.naturalKey(ImportRules.Entity.ATTRIBUTE, map3));
  }

  @Test
  void 其他对象的自然键就是匹配键本身() {
    assertThat(ImportRules.naturalKey(ImportRules.Entity.SKU, values("sku_code", "K1"))).isEqualTo("K1");
    assertThat(ImportRules.naturalKey(ImportRules.Entity.BARCODE, values("sku_code", "K1"))).isEqualTo("K1");
  }

  @Test
  void 文件内重复行阻断() {
    var problems =
        ImportRules.validateRow(
            ImportRules.Entity.SPU, ImportRules.Mode.CREATE, spuColumns(),
            values("spu_code", "S1", "spu_name", "商品"),
            new ImportRules.Facts("S1", false, true, null, List.of()));
    assertThat(codes(problems)).contains("DUPLICATE_IN_FILE");
    assertThat(ImportRules.blocking(problems)).isTrue();
  }

  // ================================================================ 字段间约束

  @Test
  void 毛重小于净重必须阻断() {
    var problems =
        ImportRules.validateRow(
            ImportRules.Entity.PACKAGE, ImportRules.Mode.CREATE,
            columns(ImportRules.defaultColumns(ImportRules.Entity.PACKAGE).toArray(ImportRules.DefaultColumn[]::new)),
            values("sku_code", "K1", "net_weight_g", "300", "gross_weight_g", "200"),
            ImportRules.Facts.clean());
    assertThat(codes(problems)).contains("GROSS_LT_NET");
    assertThat(ImportRules.blocking(problems)).isTrue();
  }

  @Test
  void 毛重等于净重放行() {
    var problems =
        ImportRules.validateRow(
            ImportRules.Entity.PACKAGE, ImportRules.Mode.CREATE,
            columns(ImportRules.defaultColumns(ImportRules.Entity.PACKAGE).toArray(ImportRules.DefaultColumn[]::new)),
            values("sku_code", "K1", "net_weight_g", "200", "gross_weight_g", "200"),
            ImportRules.Facts.clean());
    assertThat(codes(problems)).doesNotContain("GROSS_LT_NET");
  }

  @Test
  void 负数尺寸阻断() {
    var problems =
        ImportRules.validateRow(
            ImportRules.Entity.PACKAGE, ImportRules.Mode.CREATE,
            columns(ImportRules.defaultColumns(ImportRules.Entity.PACKAGE).toArray(ImportRules.DefaultColumn[]::new)),
            values("sku_code", "K1", "length_mm", "-5"),
            ImportRules.Facts.clean());
    assertThat(codes(problems)).contains("NEGATIVE");
  }

  @Test
  void 条码体系必须是词表内取值() {
    var columns =
        columns(ImportRules.defaultColumns(ImportRules.Entity.BARCODE).toArray(ImportRules.DefaultColumn[]::new));
    assertThat(codes(ImportRules.validateRow(
            ImportRules.Entity.BARCODE, ImportRules.Mode.CREATE, columns,
            values("sku_code", "K1", "scheme", "EAN-13", "barcode_value", "6901"),
            ImportRules.Facts.clean())))
        .contains("INVALID_ENUM");
    assertThat(codes(ImportRules.validateRow(
            ImportRules.Entity.BARCODE, ImportRules.Mode.CREATE, columns,
            values("sku_code", "K1", "scheme", "EAN_13", "barcode_value", "6901"),
            ImportRules.Facts.clean())))
        .doesNotContain("INVALID_ENUM");
  }

  @Test
  void 商品状态词表按生命周期五态() {
    assertThat(codes(ImportRules.validateRow(
            ImportRules.Entity.SPU, ImportRules.Mode.CREATE, spuColumns(),
            values("spu_code", "S1", "spu_name", "商品", "status", "ACTIVE"),
            ImportRules.Facts.clean())))
        .as("V4 已把 ACTIVE 迁到 ON_SALE，旧值不该再被接受")
        .contains("INVALID_ENUM");
  }

  @Test
  void 引用不存在的问题并入该行() {
    var reference =
        ImportRules.Problem.error("spu_code", "REFERENCE_NOT_FOUND", "所属商品 S9 在本租户不存在");
    var problems =
        ImportRules.validateRow(
            ImportRules.Entity.SKU, ImportRules.Mode.CREATE,
            columns(ImportRules.defaultColumns(ImportRules.Entity.SKU).toArray(ImportRules.DefaultColumn[]::new)),
            values("sku_code", "K1", "spu_code", "S9", "sku_name", "黑"),
            new ImportRules.Facts("K1", false, false, reference, List.of()));
    assertThat(codes(problems)).contains("REFERENCE_NOT_FOUND");
    assertThat(ImportRules.blocking(problems)).isTrue();
  }

  @Test
  void 条码被别的SKU占用是阻断错误() {
    var extra =
        List.of(ImportRules.Problem.error("barcode_value", "BARCODE_TAKEN", "条码 EAN_13|6901 已经挂在 SKU K2 上"));
    var problems =
        ImportRules.validateRow(
            ImportRules.Entity.BARCODE, ImportRules.Mode.CREATE,
            columns(ImportRules.defaultColumns(ImportRules.Entity.BARCODE).toArray(ImportRules.DefaultColumn[]::new)),
            values("sku_code", "K1", "scheme", "EAN_13", "barcode_value", "6901"),
            new ImportRules.Facts("K1", false, false, null, extra));
    assertThat(codes(problems)).contains("BARCODE_TAKEN");
    assertThat(ImportRules.blocking(problems)).isTrue();
  }

  @Test
  void 编码超长必须阻断() {
    assertThat(codes(ImportRules.validateRow(
            ImportRules.Entity.SPU, ImportRules.Mode.CREATE, spuColumns(),
            values("spu_code", "S".repeat(65), "spu_name", "商品"),
            ImportRules.Facts.clean())))
        .contains("TOO_LONG");
  }

  @Test
  void 数值列填非数字只报类型错而不是抛异常() {
    // 字段间约束（负数、毛重净重）会自己 new BigDecimal；输入不是数字时必须跳过，
    // 否则一个「类型错」会变成 500 而不是一条可定位到行号的预校验错误。
    var problems =
        ImportRules.validateRow(
            ImportRules.Entity.PACKAGE, ImportRules.Mode.CREATE,
            columns(ImportRules.defaultColumns(ImportRules.Entity.PACKAGE).toArray(ImportRules.DefaultColumn[]::new)),
            values("sku_code", "K1", "net_weight_g", "abc", "gross_weight_g", "xyz"),
            ImportRules.Facts.clean());
    assertThat(codes(problems)).contains("INVALID_TYPE");
    assertThat(codes(problems)).doesNotContain("NEGATIVE", "GROSS_LT_NET");
  }

  @Test
  void 词表解析不合法时返回null而不是抛异常() {
    assertThat(ImportRules.entityOf("SPU")).isEqualTo(ImportRules.Entity.SPU);
    assertThat(ImportRules.entityOf(" spu ")).isEqualTo(ImportRules.Entity.SPU);
    assertThat(ImportRules.entityOf("QUOTE")).as("报价不在本模块词表内").isNull();
    assertThat(ImportRules.entityOf(null)).isNull();
    assertThat(ImportRules.modeOf("create")).isEqualTo(ImportRules.Mode.CREATE);
    assertThat(ImportRules.modeOf("UPSERT")).isNull();
  }
}
