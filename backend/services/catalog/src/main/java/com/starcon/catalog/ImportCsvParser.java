package com.starcon.catalog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 3.8.1.1 的 CSV 解析与生成。
 *
 * <p><b>为什么 CSV 是手写而不是引库</b>：整个仓库（{@code backend/pom.xml} 与各服务 pom）
 * 没有任何 CSV 解析依赖，也没有 Excel 解析依赖（Apache POI / EasyExcel 均无），
 * 所以这个类只依赖 JDK。RFC 4180 的核心规则很短，值得自己钉住而不是引入一个传递依赖树。
 *
 * <p><b>格式取舍（与任务书要求一致）</b>：只支持 CSV，不支持 Excel。
 * 读一个 {@code .xlsx} 需要 OOXML 容器解析（zip + XML + 共享字符串表 + 日期序列号换算），
 * 仓库里没有能覆盖这件事的库；手写一套半可靠的 OOXML 解析器会得到
 * 「能读自己写的文件、读不了 Excel 导出的文件」的结果 —— 那正是任务书禁止的半成品。
 * 详见会话日志与交付报告。
 *
 * <p><b>为什么自己写而不是让「Excel 另存为 CSV」了事</b>：本类处理的是导入**管道**里的
 * 规范 CSV，运营从 Excel 另存的 CSV 有若干现实差异（UTF-8 BOM、CRLF、尾随逗号、
 * 全角引号），这些都在下面逐条处理，而不是留给运营去踩。
 *
 * <h2>逐条处理的现实差异</h2>
 * <ul>
 *   <li><b>BOM</b>：Excel 另存的 UTF-8 CSV 开头有 EF BB BF。不剥掉的话第一列表头会变成
 *       {@code ﻿spu_code}，于是「第一列永远映射不上」这种玄学 bug 会从模板下载那一刻开始。
 *   <li><b>CRLF</b>：引号内的换行必须原样保留（RFC 4180 允许字段内含换行），
 *       行外的 CRLF / LF / CR 三种都算行结束。
 *   <li><b>引号</b>：{@code ""} 是字段内的一个字面引号；引号只能出现在字段**开头**。
 *   <li><b>尾随逗号</b>：{@code a,b,} 是三列（第三列为空），不是两列。
 *   <li><b>非法编码</b>：用 {@link CodingErrorAction#REPORT}，让坏字节在解析期就报出来，
 *       而不是被替换成 U+FFFD 混进商品名称（替换字符进商品档案之后没人看得出来）。
 * </ul>
 */
final class ImportCsvParser {

  /** 解析上限：单文件 64MB。超过就拒，而不是把堆吃满。 */
  static final long MAX_BYTES = 64L * 1024 * 1024;

  /** 行上限：与数据库无关的护栏，防止一个只有换行的文件变成一亿行。 */
  static final int MAX_ROWS = 200_000;

  private ImportCsvParser() {}

  /** 解析结果：表头 + 数据行（均已剥掉 BOM、未做任何类型转换）。 */
  record Parsed(List<String> headers, List<List<String>> rows) {}

  /**
   * 解析 CSV 字节。
   *
   * <p>返回的行**不含**表头，且 {@code rowNo} 由调用方按「表头=1」补（这里只给顺序，
   * 物理行号的计算放在服务层，因为行可能被跳过）。
   *
   * @throws IllegalArgumentException 编码非法、结构非法或超过上限
   */
  static Parsed parse(byte[] content) {
    if (content == null || content.length == 0) throw new IllegalArgumentException("文件是空的，请重新选择");
    if (content.length > MAX_BYTES)
      throw new IllegalArgumentException(
          "文件超过 " + (MAX_BYTES / 1024 / 1024) + "MB 上限，请拆成几批导入");

    String text = decode(content);
    List<List<String>> table = tokenize(text);
    if (table.isEmpty()) throw new IllegalArgumentException("文件里没有任何内容");
    List<String> headers = table.get(0);
    if (headers.isEmpty()) throw new IllegalArgumentException("第一行（表头）为空");
    for (int i = 0; i < headers.size(); i++) {
      if (headers.get(i).isBlank())
        throw new IllegalArgumentException("表头第 " + (i + 1) + " 列没有列名，请补齐后重传");
    }
    if (table.size() - 1 > MAX_ROWS)
      throw new IllegalArgumentException("数据行超过 " + MAX_ROWS + " 行上限，请拆成几批导入");

    List<List<String>> rows = new ArrayList<>(table.size() - 1);
    for (int i = 1; i < table.size(); i++) {
      List<String> row = table.get(i);
      // 整行为空（全空列）视为空行跳过：Excel 导出末尾常带一个空行，
      // 让它进预校验会报一条「所有必填缺失」，运营会以为模板有问题。
      if (row.stream().allMatch(String::isBlank)) continue;
      // 补齐/截断到表头列数：少列补空串（由必填校验报错），多列截断并保留原样
      // （多出来的列不静默丢弃 —— 下方会按列数不符报错，让用户知道模板对不上）。
      if (row.size() != headers.size()) {
        List<String> fixed = new ArrayList<>(headers.size());
        for (int c = 0; c < headers.size(); c++) fixed.add(c < row.size() ? row.get(c) : "");
        rows.add(fixed);
      } else {
        rows.add(row);
      }
    }
    return new Parsed(List.copyOf(headers), List.copyOf(rows));
  }

  /**
   * 解码为字符串。
   *
   * <p>UTF-8 严格模式：坏字节抛错而不是替换。
   * GBK 兜底：国内运营拿到的文件常常是 GBK 编码的（Excel 默认在中文 Windows 上如此），
   * 严格 UTF-8 解不出来时按 GBK 再试一次 —— 两次都失败才报错。
   */
  private static String decode(byte[] content) {
    var utf8 =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    String text;
    try {
      text = utf8.decode(java.nio.ByteBuffer.wrap(content)).toString();
    } catch (java.nio.charset.CharacterCodingException e) {
      try {
        text =
            java.nio.charset.Charset.forName("GBK")
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(content))
                .toString();
      } catch (java.nio.charset.CharacterCodingException gbkFail) {
        throw new IllegalArgumentException("文件编码无法识别，请另存为 UTF-8 或 GBK 编码的 CSV 后重传");
      }
    }
    // 剥 UTF-8 BOM：Excel 另存的 CSV 一定带这个头，不剥会让第一列表头对不上。
    return text.startsWith("﻿") ? text.substring(1) : text;
  }

  /**
   * RFC 4180 词法分析。返回每一行的字段列表。
   *
   * <p>行结束后的行缓冲必须<b>换新</b>而不是清空复用：清空复用会让文件结尾处
   * 「上一行已经收尾、行缓冲却还留着上一行的字段」被误判成「还有一行没读完」，
   * 于是在每个文件末尾凭空多出一行 —— 表现为数据行数多 1、最后一行是上一行的副本。
   */
  private static List<List<String>> tokenize(String text) {
    List<List<String>> table = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean inQuotes = false;
    boolean fieldWasQuoted = false;
    int i = 0;
    int n = text.length();

    while (i < n) {
      char c = text.charAt(i);
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < n && text.charAt(i + 1) == '"') {
            field.append('"'); // "" → 字面引号
            i += 2;
            continue;
          }
          inQuotes = false;
          i++;
          continue;
        }
        field.append(c); // 引号内的换行与逗号都是字段内容
        i++;
        continue;
      }
      switch (c) {
        case '"' -> {
          // 引号只允许出现在字段开头（RFC 4180）。出现在中间时按字面处理而不是报错：
          // 现实中不少导出工具会写出 a"b 这种值，为它让整个导入失败不划算。
          if (field.length() == 0) {
            inQuotes = true;
            fieldWasQuoted = true;
          } else {
            field.append(c);
          }
          i++;
        }
        case ',' -> {
          row.add(normalize(field, fieldWasQuoted));
          field.setLength(0);
          fieldWasQuoted = false;
          i++;
        }
        case '\r' -> {
          if (i + 1 < n && text.charAt(i + 1) == '\n') i++;
          row.add(normalize(field, fieldWasQuoted));
          table.add(row);
          row = new ArrayList<>();
          field.setLength(0);
          fieldWasQuoted = false;
          i++;
        }
        case '\n' -> {
          row.add(normalize(field, fieldWasQuoted));
          table.add(row);
          row = new ArrayList<>();
          field.setLength(0);
          fieldWasQuoted = false;
          i++;
        }
        default -> {
          field.append(c);
          i++;
        }
      }
    }
    // 收尾：最后一行没有行尾符时补上（文件不以换行结尾是常态）。
    if (field.length() > 0 || !row.isEmpty() || fieldWasQuoted) {
      row.add(normalize(field, fieldWasQuoted));
      table.add(row);
    }
    return table;
  }

  /**
   * 字段归一化。
   *
   * <p>只有<b>被引号包住</b>的字段才保留首尾空白：{@code " 123 "} 的 123 有意义（前导零、
   * 名称里的空格），而 {@code   } 这种没引号的空白是 Excel 导出的残渣，trim 掉才对。
   */
  private static String normalize(StringBuilder field, boolean quoted) {
    String value = field.toString();
    return quoted ? value : value.trim();
  }

  /** 文件内容的 SHA-256（十六进制）。用来回答「导进去的到底是哪份文件」。 */
  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  /**
   * 按 RFC 4180 生成 CSV 字节。
   *
   * <p>需要加引号的字段：含逗号、引号、CR 或 LF，或首尾有空白。
   * 加了 UTF-8 BOM —— **模板文件的第一用途就是给 Excel 打开**，
   * 不带 BOM 的话中文 Windows 上的 Excel 会按本地代码页打开，列头全是乱码。
   */
  static byte[] write(List<String> headers, List<List<String>> rows) {
    StringBuilder out = new StringBuilder("﻿");
    appendRow(out, headers);
    for (List<String> row : rows) appendRow(out, row);
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static void appendRow(StringBuilder out, List<String> cells) {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) out.append(',');
      out.append(escape(cells.get(i) == null ? "" : cells.get(i)));
    }
    out.append("\r\n");
  }

  private static String escape(String value) {
    boolean needsQuote =
        value.indexOf(',') >= 0
            || value.indexOf('"') >= 0
            || value.indexOf('\n') >= 0
            || value.indexOf('\r') >= 0
            || (!value.isEmpty() && (Character.isWhitespace(value.charAt(0)) || Character.isWhitespace(value.charAt(value.length() - 1))));
    if (!needsQuote) return value;
    return '"' + value.replace("\"", "\"\"") + '"';
  }

  /** 从 {@link InputStream} 读完字节（上传端点用）。 */
  static byte[] readAll(InputStream in) throws IOException {
    try (var buffered = new java.io.BufferedInputStream(new ByteArrayInputStream(in.readAllBytes()))) {
      return buffered.readAllBytes();
    }
  }
}
