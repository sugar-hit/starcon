package com.starcon.platform;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 18.1.3.1 的 CSV 读写。
 *
 * <p>🔴 **为什么自己写而不引依赖**：本模块只需要「带引号的逗号分隔 + 双写转义」这一种格式，
 * 而一个 CSV 库会带来它自己的方言（分隔符猜测、编码猜测、日期转换）——
 * 那些方言会让「导出的文件在 Excel 里长得不一样」这种问题很难查。
 *
 * <p>🔴 **UTF-8 BOM**：Excel 直接双击打开 UTF-8 CSV 时会按本地编码猜，中文会变成乱码。
 * 加 BOM 是本仓既有的做法（照抄 18.2.1.1 的凭证文件导出）。
 *
 * <p>⚠️ **RFC 4180 语义**：字段里含 {@code ,}、{@code "}、换行时用双引号包起来，
 * 字段里的双引号写成两个双引号。
 */
final class ImportExportCsv {

  /** UTF-8 BOM（Excel 双击直接开中文不乱码）。 */
  static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

  private ImportExportCsv() {}

  /**
   * 读一份 CSV。
   *
   * @return 每一行一个字符串数组（**不含表头判断** —— 表头由调用方按模板定义认定）
   */
  static List<String[]> read(byte[] content) {
    var text = new String(content, StandardCharsets.UTF_8);
    if (!text.isEmpty() && text.charAt(0) == '﻿') {
      text = text.substring(1);
    }
    var rows = new ArrayList<String[]>();
    var row = new ArrayList<String>();
    var field = new StringBuilder();
    var inQuotes = false;
    var sawAnyChar = false;
    for (var i = 0; i < text.length(); i++) {
      var c = text.charAt(i);
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            field.append('"');
            i++;
          } else {
            inQuotes = false;
          }
        } else {
          field.append(c);
        }
        sawAnyChar = true;
        continue;
      }
      if (c == '"') {
        inQuotes = true;
        sawAnyChar = true;
        continue;
      }
      if (c == ',') {
        row.add(field.toString());
        field.setLength(0);
        sawAnyChar = true;
        continue;
      }
      if (c == '\r') {
        continue;
      }
      if (c == '\n') {
        row.add(field.toString());
        rows.add(row.toArray(new String[0]));
        row = new ArrayList<>();
        field.setLength(0);
        sawAnyChar = false;
        continue;
      }
      field.append(c);
      sawAnyChar = true;
    }
    // 结尾没有换行时补上最后一行（但空文件不产生空行）。
    if (sawAnyChar || field.length() > 0 || !row.isEmpty()) {
      row.add(field.toString());
      rows.add(row.toArray(new String[0]));
    }
    return rows;
  }

  /**
   * 写一份 CSV（**带 BOM**）。
   *
   * @param header 表头（可为空数组 —— 那就没有表头行）
   * @param rows 数据行
   */
  static byte[] write(List<String> header, List<String[]> rows) {
    var out = new ByteArrayOutputStream();
    out.write(BOM, 0, BOM.length);
    try {
      if (header != null && !header.isEmpty()) {
        out.write(join(header).getBytes(StandardCharsets.UTF_8));
        out.write('\n');
      }
      for (var row : rows) {
        out.write(join(row).getBytes(StandardCharsets.UTF_8));
        out.write('\n');
      }
    } catch (java.io.IOException e) {
      throw new IllegalStateException("CSV 写出失败", e);
    }
    return out.toByteArray();
  }

  private static String join(List<String> cells) {
    var out = new StringBuilder();
    for (var i = 0; i < cells.size(); i++) {
      if (i > 0) out.append(',');
      out.append(escape(cells.get(i) == null ? "" : cells.get(i)));
    }
    return out.toString();
  }

  private static String join(String[] cells) {
    return join(java.util.Arrays.asList(cells));
  }

  /** RFC 4180 转义：含 {@code ,}、{@code "}、换行时用双引号包起来，里面的双引号写两个。 */
  static String escape(String value) {
    var needsQuote =
        value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
            || value.indexOf('\r') >= 0;
    if (!needsQuote) return value;
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }
}
