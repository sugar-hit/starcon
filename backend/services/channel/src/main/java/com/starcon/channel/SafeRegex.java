package com.starcon.channel;

import static com.starcon.channel.ListingBulkEditModels.RegexSafetyVerdict;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 4.2.1.2 正则替换的 ReDoS 防线。
 *
 * <p>蓝图职责句里的「正则替换标题」在本仓是有真实风险的一行：批量操作会把同一个正则施加到
 * **一批** listing 的标题上，而标题最长 500 字。一个 {@code (a+)+$} 形状的正则撞上
 * {@code aaaa...a!} 形式的标题就是指数级回溯 —— 单条卡住就够把一次「全店批量改标题」变成事故。
 *
 * <p>因此这里是<b>双层</b>的：
 *
 * <ol>
 *   <li><b>静态结构分析</b>（{@link #analyze}）：逐字符扫描，识别反向引用、嵌套无界量词、
 *       相邻无界量词、括号不配对、字符类里的伪量词。这是快路径，把常见的灾难模式在纳秒级挡掉。
 *   <li><b>对抗语料实测计时</b>（{@link #timed}）：把模式真的跑一遍对输入不利的语料，
 *       超时即拒。静态分析漏掉的形状（例如 {@code (a|b+)+} 这种分支重叠）由这一层兜住。
 * </ol>
 *
 * <p>两层都不是"注释里说不许用"：第 1 层写在代码里绕不过去，第 2 层是**跑出来的时间**。
 * V177 里还有第三层 —— {@code guard_bulk_change_not_oversized} 触发器给 before/after 长度封顶，
 * 所以即使前两层都被绕过，算出来的巨型字符串也进不了库。
 *
 * <p>⚠️ 一个诚实的局限：{@link Future#cancel(boolean)} 的中断信号对 {@code java.util.regex}
 * 无效（Matcher 不检查线程中断），所以被放弃的探测线程会自己跑完。对抗语料的长度因此被刻意压到
 * 96 字符量级 —— 最坏情况的耗时有界，而不是"可能永远挂着"。
 */
final class SafeRegex {

  /** 模式长度上限：批量操作要在一批标题上跑，模式本身不该是长文本。 */
  static final int MAX_PATTERN_LENGTH = 200;

  /** 单次执行（对抗语料或真实输入）的预算。 */
  private static final long BUDGET_MS = 500;

  /**
   * 对抗语料：对不利的输入形状。
   *
   * <p>第三行是经典形态 —— {@code (a+)+$} 这类模式在纯 a 上要到很长的串才会爆，
   * 加上换行与结尾失配字符后回溯路径才被真正走满。
   */
  private static final List<String> ADVERSARIAL =
      List.of("a".repeat(64) + "!", "a".repeat(96) + "!", "a".repeat(32) + "\n".repeat(32) + "b");

  private static final ExecutorService PROBES =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "listing-bulk-regex-probe");
            thread.setDaemon(true);
            return thread;
          });

  private SafeRegex() {}

  /** 只做检查不改内容：供前端提交前预检，让用户在点确认之前就知道这个模式会被拒。 */
  static RegexSafetyVerdict check(String pattern) {
    String trimmed = pattern == null ? "" : pattern.trim();
    if (trimmed.isEmpty()) {
      return new RegexSafetyVerdict(false, "正则不能为空");
    }
    if (trimmed.length() > MAX_PATTERN_LENGTH) {
      return new RegexSafetyVerdict(false, "正则过长（" + trimmed.length() + " > " + MAX_PATTERN_LENGTH + "）");
    }
    String reason = analyze(trimmed);
    if (reason != null) {
      return new RegexSafetyVerdict(false, reason);
    }
    try {
      Pattern.compile(trimmed);
    } catch (PatternSyntaxException e) {
      return new RegexSafetyVerdict(false, "正则语法错误：" + e.getDescription());
    }
    return new RegexSafetyVerdict(true, "");
  }

  /**
   * 带 ReDoS 防护的替换。
   *
   * <p>失败一律抛 422 并把原因原样带出去 —— **绝不**静默降级成字面量替换：用户以为自己提交的是
   * 「把 A 换成 B」，静默降级会变成「把 A 当普通文本原样留着」，结果与预期相反且无人察觉。
   */
  static String replaceAll(String input, String pattern, String replacement) {
    RegexSafetyVerdict verdict = check(pattern);
    if (!verdict.safe()) {
      throw new IllegalArgumentException(verdict.reason());
    }
    Pattern compiled = Pattern.compile(pattern.trim());

    // 第 2 层：先拿对抗语料实测一次，确认这个模式在不利输入上不会爆
    timed(
        () -> {
          for (String hostile : ADVERSARIAL) {
            compiled.matcher(hostile).replaceAll(replacement);
          }
          return null;
        });

    // 真实输入同样计时：静态分析与对抗语料都没覆盖到的形状由这一层兜
    return timed(() -> compiled.matcher(input).replaceAll(replacement));
  }

  /** 在预算内跑一段计算；超时即视为危险模式并中止。 */
  private static <T> T timed(Supplier<T> work) {
    Future<T> future = PROBES.submit(work::get);
    try {
      return future.get(BUDGET_MS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new IllegalArgumentException(
          "正则存在灾难性回溯（ReDoS）风险：在 " + BUDGET_MS + "ms 内没跑完，已拒绝执行。"
              + "请改写成无嵌套量词、无反向引用的写法。");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      throw new IllegalArgumentException("正则执行失败：" + (cause == null ? e.getMessage() : cause.getMessage()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalArgumentException("正则执行被中断");
    }
  }

  // ==================================================================
  // 静态分析：逐字符扫描
  // ==================================================================

  /**
   * 结构分析。通过返回 {@code null}，不通过返回可直接展示给用户的中文原因。
   *
   * <p>逐字符扫描（而不是几条正则套正则）是因为**字符类 {@code [...]} 里的 * 与 + 不是量词**，
   * 转义后的 {@code \*} 也不是 —— 任何"用正则去判断有没有量词"的写法都会在这两种输入上误判，
   * 而误判的方向往往是"放行了一个危险模式"。
   */
  static String analyze(String pattern) {
    int length = pattern.length();
    if (length > MAX_PATTERN_LENGTH) {
      return "正则过长（" + length + " > " + MAX_PATTERN_LENGTH + "）";
    }

    // hasUnbounded[depth]：进入第 depth 层分组后，本层内是否已出现过无界量词
    boolean[] hasUnbounded = new boolean[length + 2];
    int depth = 0;
    int lastUnboundedAt = -1;

    for (int i = 0; i < length; i++) {
      char c = pattern.charAt(i);

      // 转义序列：\1 \k \d \* … 整体跳过（它后面的字符不是量词）
      if (c == '\\') {
        if (i + 1 >= length) {
          return "正则以孤立的反斜杠结尾";
        }
        char next = pattern.charAt(i + 1);
        if (next >= '1' && next <= '9') {
          return "反向引用 \\" + next + " 的匹配耗时不可预测，本模块拒绝反向引用";
        }
        if (next == 'k') {
          return "命名反向引用 \\k<…> 的匹配耗时不可预测，本模块拒绝反向引用";
        }
        i++;
        continue;
      }

      // 字符类：[…] 内部整体跳过，里面的 - 与 ] 另有规则，里面的 * + 不是量词
      if (c == '[') {
        int j = i + 1;
        if (j < length && pattern.charAt(j) == '^') {
          j++;
        }
        if (j < length && pattern.charAt(j) == ']') {
          j++;
        }
        while (j < length && pattern.charAt(j) != ']') {
          if (pattern.charAt(j) == '\\') {
            j++;
          }
          j++;
        }
        if (j >= length) {
          return "字符类 [ 没有闭合";
        }
        i = j;
        continue;
      }

      if (c == '(') {
        if (depth + 1 >= hasUnbounded.length) {
          return "分组嵌套过深";
        }
        hasUnbounded[depth] = false;
        depth++;
        lastUnboundedAt = -1;
        continue;
      }

      if (c == ')') {
        if (depth == 0) {
          return "括号不配对";
        }
        depth--;
        boolean innerUnbounded = hasUnbounded[depth];
        lastUnboundedAt = -1;
        int next = i + 1;
        if (isQuantifierAt(pattern, next)) {
          boolean unbounded = isUnbounded(pattern, next);
          if (innerUnbounded && unbounded) {
            return "嵌套无界量词（例如 (a+)+ ）会让单次匹配耗时指数爆炸，已拒绝";
          }
          if (unbounded && depth > 0) {
            hasUnbounded[depth - 1] = true;
          }
          i = quantifierEnd(pattern, next);
        }
        continue;
      }

      if (isQuantifierAt(pattern, i)) {
        boolean unbounded = isUnbounded(pattern, i);
        if (unbounded) {
          // 相邻两个无界量词（.*.* / \d*\w* / a+a+）：匹配量随输入长度急剧上升
          if (lastUnboundedAt >= 0 && i - lastUnboundedAt <= 3) {
            return "相邻的两个无界量词（例如 .*.* ）会让匹配耗时随标题长度急剧上升，已拒绝";
          }
          if (depth > 0) {
            hasUnbounded[depth - 1] = true;
          }
          lastUnboundedAt = i;
        }
        i = quantifierEnd(pattern, i);
        continue;
      }
    }

    if (depth != 0) {
      return "括号不配对";
    }
    return null;
  }

  /**
   * 这个位置是不是一个量词。
   *
   * <p>🔴 刻意<b>不</b>用「{@code quantifierEnd > i}」当判断：单字符量词 {@code *} / {@code +} / {@code ?}
   * 在没有惰性 / 占有后缀时 {@code quantifierEnd == i}，拿它当判断会把<b>出现在模式末尾的量词</b>
   * 漏掉 —— 于是 {@code (a+)+} 这个最经典的 ReDoS 模式恰好因为结尾那个 {@code +} 被放过。
   * （实测踩过：静态分析返回 null，防线形同虚设。）
   */
  private static boolean isQuantifierAt(String pattern, int i) {
    if (i >= pattern.length()) {
      return false;
    }
    char c = pattern.charAt(i);
    if (c == '*' || c == '+') {
      return true;
    }
    if (c == '?') {
      // (? 开头是分组前缀（?: (?= (?! (?<），不是量词
      return i == 0 || pattern.charAt(i - 1) != '(';
    }
    if (c == '{') {
      return quantifierEnd(pattern, i) > i;
    }
    return false;
  }

  /**
   * 量词构造的结束下标（含）。
   *
   * <p>不是量词时返回 {@code i}。{@code *?} / {@code *+} 这类惰性与占有量词一并吃掉 ——
   * 它们同样是 {@code *}，只是回溯策略不同。
   */
  private static int quantifierEnd(String pattern, int i) {
    if (i >= pattern.length()) {
      return i;
    }
    char c = pattern.charAt(i);
    if (c == '*' || c == '+') {
      if (i + 1 < pattern.length() && (pattern.charAt(i + 1) == '?' || pattern.charAt(i + 1) == '+')) {
        return i + 1;
      }
      return i;
    }
    if (c != '{') {
      return i;
    }
    int j = i + 1;
    while (j < pattern.length() && Character.isDigit(pattern.charAt(j))) {
      j++;
    }
    if (j < pattern.length() && pattern.charAt(j) == ',') {
      j++;
      while (j < pattern.length() && Character.isDigit(pattern.charAt(j))) {
        j++;
      }
    }
    if (j < pattern.length() && pattern.charAt(j) == '}') {
      return j + (j + 1 < pattern.length() && (pattern.charAt(j + 1) == '?' || pattern.charAt(j + 1) == '+') ? 1 : 0);
    }
    // 形如 {2} 的合法量词没匹配上；这可能只是普通字面量 '{'
    return i;
  }

  /** {@code *} 与 {@code {n,}} 是无界；{@code ?} 与 {@code {n}} 最多一次，有界。 */
  private static boolean isUnbounded(String pattern, int i) {
    char c = pattern.charAt(i);
    if (c == '*' || c == '+') {
      return true;
    }
    if (c == '?') {
      return false;
    }
    // {n} 有界；{n,} 与 {n,m} 无界
    int comma = pattern.indexOf(',', i);
    if (comma < 0 || comma > pattern.indexOf('}', i)) {
      return false;
    }
    return true;
  }
}
