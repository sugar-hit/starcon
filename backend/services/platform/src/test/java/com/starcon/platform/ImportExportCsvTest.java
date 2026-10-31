package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 18.1.3.1 的 CSV 读写单元测试。
 *
 * <p>🔴 钉住三件最容易悄悄坏掉的事：
 *
 * <ol>
 *   <li><b>UTF-8 BOM</b>：Excel 直接双击中文不乱码；读回来时也要把 BOM 剥掉
 *       （不剥的话第一列的列头会变成「﻿订阅名称」，模板与解析就对不上了）；
 *   <li><b>RFC 4180 转义</b>：字段里含 {@code ,}、{@code "}、换行时要正确包起来，
 *       里面的双引号写成两个 —— 漏了这一步，运营填的「含逗号的名称」会把整行拆歪，
 *       后面的列全部错位，而**不会**报错；
 *   <li><b>引号内的逗号与换行不算分隔符</b>。
 * </ol>
 */
class ImportExportCsvTest {

  @Test
  void 写出的CSV带UTF8BOM() {
    var content = ImportExportCsv.write(List.of("列一", "列二"), List.<String[]>of(new String[] {"a", "b"}));
    assertThat(content).startsWith(ImportExportCsv.BOM);
    var text = new String(content, StandardCharsets.UTF_8);
    assertThat(text).contains("列一,列二");
    assertThat(text).contains("a,b");
  }

  @Test
  void 读回时剥掉BOM_否则第一列的列头对不上() {
    var content = ImportExportCsv.write(List.of("订阅名称", "所属应用"), List.of());
    var rows = ImportExportCsv.read(content);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)[0]).isEqualTo("订阅名称");
  }

  @Test
  void 字段里的逗号与引号按RFC4180转义() {
    var content =
        ImportExportCsv.write(
            List.of("名称"), List.<String[]>of(new String[] {"含逗号的,名称"}, new String[] {"含\"引号\""}));
    var text = new String(content, StandardCharsets.UTF_8);
    assertThat(text).contains("\"含逗号的,名称\"");
    assertThat(text).contains("\"含\"\"引号\"\"\"");
  }

  @Test
  void 读回时引号内的逗号与换行不算分隔符() {
    var raw = "﻿名称,说明\n\"含逗号,的名称\",\"第一行\n第二行\"\n";
    var rows = ImportExportCsv.read(raw.getBytes(StandardCharsets.UTF_8));
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsExactly("名称", "说明");
    assertThat(rows.get(1)).containsExactly("含逗号,的名称", "第一行\n第二行");
  }

  @Test
  void 空文件不产生空行_结尾没有换行也补上最后一行() {
    assertThat(ImportExportCsv.read(new byte[0])).isEmpty();
    var rows = ImportExportCsv.read("a,b".getBytes(StandardCharsets.UTF_8));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsExactly("a", "b");
  }

  @Test
  void 往返一致() {
    var original = List.<String[]>of(new String[] {"普通", "含,逗号"}, new String[] {"含\"引号\"", "换\n行"});
    var rows = ImportExportCsv.read(ImportExportCsv.write(List.of("c1", "c2"), original));
    assertThat(rows).hasSize(3);
    assertThat(rows.get(1)).containsExactly("普通", "含,逗号");
    assertThat(rows.get(2)).containsExactly("含\"引号\"", "换\n行");
  }
}
