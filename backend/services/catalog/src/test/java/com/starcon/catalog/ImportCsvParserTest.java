package com.starcon.catalog;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 3.8.1.1 CSV 解析与生成的纯单元测试。
 *
 * <p>这些用例钉的是<b>运营真实会交上来的文件形态</b>：Excel 另存的 CSV 带 BOM、CRLF、
 * 尾随逗号、全角引号。任何一个没处理，表现都是「第一列永远映射不上」或「行号对不上」，
 * 而这种故障在演示数据上永远不会出现。
 */
class ImportCsvParserTest {

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void 剥掉UTF8BOM让第一列表头可用() {
    // 不剥 BOM 的话第一列表头会变成 "﻿spu_code"，映射第一列永远失败。
    var parsed = ImportCsvParser.parse(bytes("﻿spu_code,spu_name\r\nS1,商品\r\n"));
    assertThat(parsed.headers()).containsExactly("spu_code", "spu_name");
  }

  @Test
  void CRLF与LF与CR都算行结束() {
    var parsed = ImportCsvParser.parse(bytes("a,b\r\n1,2\n3,4\r5,6\r\n"));
    assertThat(parsed.rows()).hasSize(3);
    assertThat(parsed.rows().get(0)).containsExactly("1", "2");
    assertThat(parsed.rows().get(2)).containsExactly("5", "6");
  }

  @Test
  void 引号内的逗号与换行是字段内容() {
    var parsed = ImportCsvParser.parse(bytes("a,b\r\n\"含,逗号\",\"含\n换行\"\r\n"));
    assertThat(parsed.rows().get(0)).containsExactly("含,逗号", "含\n换行");
  }

  @Test
  void 双引号转义成字面引号() {
    var parsed = ImportCsvParser.parse(bytes("a\r\n\"他说\"\"好\"\"\"\r\n"));
    assertThat(parsed.rows().get(0)).containsExactly("他说\"好\"");
  }

  @Test
  void 尾随逗号是空列而不是被丢掉() {
    var parsed = ImportCsvParser.parse(bytes("a,b,c\r\n1,2,\r\n"));
    assertThat(parsed.rows().get(0)).containsExactly("1", "2", "");
  }

  @Test
  void 未加引号的空白被清理而加引号的空白保留() {
    var parsed = ImportCsvParser.parse(bytes("a,b\r\n  123  ,\"  456  \"\r\n"));
    assertThat(parsed.rows().get(0)).containsExactly("123", "  456  ");
  }

  @Test
  void 少列补空多列截断() {
    // 少列 -> 补空串（之后由必填校验报错）；多列 -> 截到表头宽度。
    var short1 = ImportCsvParser.parse(bytes("a,b,c\r\n1\r\n"));
    assertThat(short1.rows().get(0)).containsExactly("1", "", "");
    var long1 = ImportCsvParser.parse(bytes("a,b\r\n1,2,3\r\n"));
    assertThat(long1.rows().get(0)).containsExactly("1", "2");
  }

  @Test
  void 全空行被跳过而不是报必填缺失() {
    // Excel 导出末尾常带一个空行。让它进预校验会报一堆「必填缺失」，
    // 运营会以为模板坏了。
    var parsed = ImportCsvParser.parse(bytes("a,b\r\n1,2\r\n\r\n   ,   \r\n3,4\r\n"));
    assertThat(parsed.rows()).hasSize(2);
  }

  @Test
  void 表头空列名直接报错() {
    assertThatThrownBy(() -> ImportCsvParser.parse(bytes("a,,c\r\n1,2,3\r\n")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("第 2 列");
  }

  @Test
  void 空文件与超限文件被拒() {
    assertThatThrownBy(() -> ImportCsvParser.parse(new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ImportCsvParser.parse(bytes("a\r\n" + "1\r\n".repeat(ImportCsvParser.MAX_ROWS + 1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("上限");
  }

  @Test
  void GBK编码的CSV也能读() {
    // 国内运营从中文 Windows 的 Excel 另存出来常常是 GBK。
    var content = "商品编码,商品名称\r\nS1,蓝牙耳机\r\n".getBytes(java.nio.charset.Charset.forName("GBK"));
    var parsed = ImportCsvParser.parse(content);
    assertThat(parsed.headers()).containsExactly("商品编码", "商品名称");
    assertThat(parsed.rows().get(0)).containsExactly("S1", "蓝牙耳机");
  }

  @Test
  void 无法解码的字节被拒而不是替换成乱码() {
    // 替换字符混进商品名称之后没人看得出来，所以宁可报错。
    var bad = new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 0xA0, (byte) 0xA1};
    assertThatThrownBy(() -> ImportCsvParser.parse(bad)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 写出模板时含逗号引号换行的字段被正确转义() {
    var out = ImportCsvParser.write(
        List.of("a", "b"),
        List.of(List.of("含,逗号", "他说\"好\""), List.of("普通", "  前后空白  ")));
    var text = new String(out, StandardCharsets.UTF_8);
    assertThat(text).startsWith("﻿");
    // 回读能还原即说明转义正确。
    var parsed = ImportCsvParser.parse(out);
    assertThat(parsed.headers()).containsExactly("a", "b");
    assertThat(parsed.rows().get(0)).containsExactly("含,逗号", "他说\"好\"");
    assertThat(parsed.rows().get(1)).containsExactly("普通", "  前后空白  ");
  }

  @Test
  void 写出模板带BOM让Excel不乱码() {
    // 模板的第一用途就是给 Excel 打开；不带 BOM 的话中文 Windows 上列头全是乱码。
    var out = ImportCsvParser.write(List.of("商品编码"), List.of(List.of("S1")));
    assertThat((out[0] & 0xFF)).isEqualTo(0xEF);
  }

  @Test
  void sha256是内容的稳定指纹() {
    var a = ImportCsvParser.sha256(bytes("a,b\r\n1,2\r\n"));
    var b = ImportCsvParser.sha256(bytes("a,b\r\n1,2\r\n"));
    var c = ImportCsvParser.sha256(bytes("a,b\r\n1,3\r\n"));
    assertThat(a).isEqualTo(b).hasSize(64);
    assertThat(a).isNotEqualTo(c);
  }

  @Test
  void jsonb读回映射覆盖jOOQ的三种返回形态() {
    // jOOQ 取 jsonb 会给 org.jooq.JSONB（不是 Map、也不是 String）。
    // 这里把这个坑钉死：读不回来时症状是「映射明明对了，预校验却说每列都没对应」，
    // 而那种症状在演示数据上永远看不出来。
    assertThat(ImportTemplateService.readJson(org.jooq.JSONB.valueOf("{\"a\":\"1\"}")))
        .containsEntry("a", "1");
    assertThat(ImportTemplateService.readJson("{\"a\":\"1\"}")).containsEntry("a", "1");
    assertThat(ImportTemplateService.readJson(Map.of("a", "1"))).containsEntry("a", "1");
    assertThat(ImportTemplateService.readJson(null)).isEmpty();
  }
}
