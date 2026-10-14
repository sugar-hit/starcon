package com.starcon.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 3.4.2.1 的标题生成引擎：渲染模板 + <b>显式控制重复度</b>，产出「相似但不雷同」的候选集。
 *
 * <p>蓝图原话：「批量生成要保证"相似但不雷同"，需在生成算法中显式控制重复度。」 这是本模块唯一被点名要求的算法，所以它在这里被写成**纯函数**： 输入是模板快照、SKU
 * 事实、三类词池、已有标题，输出是候选集与被拒原因。 不碰数据库、不碰时钟、不碰随机数——同样的输入永远得到同样的输出，
 * 于是「重复度受控」可以被单元测试逐条断言，而不是靠「跑起来看着挺像那么回事」。
 *
 * <h2>算法</h2>
 *
 * 每个 SKU、每个平台要产出 {@code candidateCount} 条候选。第 k 条（k 从 1 起）按固定顺序枚举组合：
 *
 * <pre>
 *   for attempt in 0 .. MAX_ATTEMPTS_PER_CANDIDATE-1:
 *       t = k + attempt
 *       rotation   = t % 变量个数            // 轴 1：变量槽位整体循环左移
 *       核心词下标 = (t + 0) % corePoolSize
 *       卖点下标   = (t + 1) % sellingPoolSize
 *       修饰词下标 = (t + 2) % modifierPoolSize   // 轴 2：词池里换词
 *       title      = 渲染(rotation, 各下标)
 *       title      = 按平台上限确定性裁剪
 *       if 渲染结果为空            -> 跳过（没有任何可绑定的词）
 *       if 与「本 SKU 已有 + 本次已生成」完全相同 -> 跳过（雷同）
 *       if maxSimilarity(title, 已有∪已生成) > 上限 -> 跳过，换下一个组合
 *       采纳
 * </pre>
 *
 * <p>三条轴互相错开（t、t+1、t+2）是为了让「换了修饰词时核心词也跟着动」， 否则三条轴同步轮转时，第 N 次尝试三个池子同时回到原位，于是每 n 轮重复一次同样的组合。
 *
 * <h2>为什么「拒绝」也是一种输出</h2>
 *
 * 找不到过闸的组合时，本引擎**不返回**一条雷同的标题，而是返回一条带原因的拒绝。 词池只有 1 个修饰词、模板只有 2 个变量这类情况下，「硬凑第 3 条」唯一能做到的方式 就是重复——那正是
 * 3.4.2.1 要消灭的东西。所以「这个 SKU 只产出了 2 条」是一个 诚实且可行动的结果（去加修饰词），而不是一个被掩盖的重复。
 */
public final class TitleGenerator {

  private TitleGenerator() {}

  // ============================================================ 入参

  /**
   * 模板快照（生成期不再读模板表）。
   *
   * <p>为什么快照：生成中途模板被人改了，这一批候选就必须仍能对着**提交时那套** 变量绑定与重复度上限解释，否则「这批标题为什么长这样」在几分钟后答不出来。
   */
  public record TemplateSpec(
      String pattern,
      List<VariableSpec> variables,
      int maxSimilarityPermille,
      int candidateCount) {}

  /** 一个变量占位：名字 + 绑定源 + 槽位序。 */
  public record VariableSpec(String name, String source, int slotNo) {}

  /**
   * SKU 事实快照：变量里绑事实字段时的取值来源。
   *
   * <p>取自 3.1.1.1/3.1.2.1 的 {@code sku} / {@code spu}（上游 = 3.1.1.1）。
   * 提交时快照而不是生成时再读：批量跑到一半商品改了名，这一批候选的口径也不该变。
   */
  public record SkuFacts(
      java.util.UUID skuId,
      String skuCode,
      String skuName,
      String spuName,
      String brand,
      String category,
      String spec) {}

  /** 一个平台的生成参数。 */
  public record PlatformSpec(String platform, int maxLength) {}

  /** 三类词池。顺序即稳定顺序（按 code 排），所以「下标」在同一次生成里是确定的。 */
  public record KeywordPools(List<String> core, List<String> selling, List<String> modifier) {}

  // ============================================================ 出参

  /** 产出的候选。字段与 {@code title_record} 一一对应（还没带 id 与批次 id）。 */
  public record Candidate(
      java.util.UUID skuId,
      String platform,
      int variantNo,
      String title,
      int charLength,
      boolean truncated,
      int rotation,
      int similarityPermille,
      String coreKeyword,
      String sellingKeyword,
      String modifierKeyword) {}

  /** 被拒的候选：带可执行的原因。 */
  public record Rejection(
      java.util.UUID skuId,
      String platform,
      int variantNo,
      String reason,
      int bestSimilarityPermille) {}

  /**
   * 一个 SKU 在一个平台上的生成结果。
   *
   * <p>「只产出 2 条而要了 3 条」在这里是 {@code candidates.size() < expected}， 服务层据此把这个 SKU 落成
   * outcome='REJECTED' 并写上原因。
   */
  public record SkuPlatformResult(
      java.util.UUID skuId,
      String platform,
      int expected,
      List<Candidate> candidates,
      List<Rejection> rejections) {
    public boolean complete() {
      return candidates.size() == expected;
    }

    public int rejectedCount() {
      return rejections.size();
    }
  }

  // ============================================================ 入口

  /**
   * 为一个 SKU 在一个平台上生成候选。
   *
   * @param template 模板快照
   * @param sku SKU 事实快照
   * @param platform 平台参数（长度上限）
   * @param pools 三类词池
   * @param existingTitles 该 SKU **已有**的标题（历史批次 + 其它平台）， 它们参与重复度比较——这正是「同一商品在多平台不同标题」的比较范围
   */
  public static SkuPlatformResult generate(
      TemplateSpec template,
      SkuFacts sku,
      PlatformSpec platform,
      KeywordPools pools,
      List<String> existingTitles) {
    List<VariableSpec> variables = sortedVariables(template.variables());
    int slotCount = variables.size();
    if (slotCount == 0)
      return rejected(
          sku.skuId(), platform.platform(), template.candidateCount(), "模板没有声明任何变量，无法渲染标题");

    // 「本 SKU 的全部已知标题」= 已有 + 本次已产出。重复度对两者的最大值取。
    List<String> known = new ArrayList<>();
    if (existingTitles != null)
      for (String title : existingTitles) if (title != null && !title.isBlank()) known.add(title);
    // 完全相同的文本在闸门里一定被判雷同（相似度 1000 > 上限 ≤999），
    // 所以这里不额外做「精确去重」判断——那只会让「为什么这条被拒」说不清。
    List<Candidate> candidates = new ArrayList<>();
    List<Rejection> rejections = new ArrayList<>();

    for (int k = 1; k <= template.candidateCount(); k++) {
      Candidate accepted = null;
      int bestSimilarity = 0;
      String bestReason = null;
      for (int attempt = 0; attempt < TitleRules.MAX_ATTEMPTS_PER_CANDIDATE; attempt++) {
        int t = k + attempt;
        int rotation = t % slotCount;
        // poolAt 对空池子返回空串而不是除零：Math.floorMod(x, 0) 会抛
        // ArithmeticException（实测修掉的真缺陷——租户只建了核心词、
        // 模板却绑了卖点变量时，整批生成直接 500 而不是给出可读原因）。
        // 空池子的表现是「该变量渲染成空」，其余变量照常工作。
        String coreKeyword = poolAt(pools.core(), t);
        String sellingKeyword = poolAt(pools.selling(), t + 1);
        String modifierKeyword = poolAt(pools.modifier(), t + 2);

        Rendered rendered =
            render(
                template.pattern(),
                variables,
                rotation,
                sku,
                coreKeyword,
                sellingKeyword,
                modifierKeyword);
        if (rendered.text().isBlank()) {
          bestReason = "组合渲染出来是空文本：这个 SKU 的品牌/分类/规格都为空，且词池没有可用词";
          continue;
        }
        String title = rendered.text();
        boolean truncated = false;
        if (TitleRules.charLength(title) > platform.maxLength()) {
          title = TitleRules.clampByCharLength(title, platform.maxLength());
          truncated = true;
        }
        int similarity = TitleRules.maxSimilarityPermille(title, known);
        if (similarity > template.maxSimilarityPermille()) {
          bestSimilarity = Math.max(bestSimilarity, similarity);
          bestReason =
              "在 "
                  + TitleRules.MAX_ATTEMPTS_PER_CANDIDATE
                  + " 次确定性尝试内都压不到 "
                  + template.maxSimilarityPermille()
                  + "‰ 之下（最优 "
                  + similarity
                  + "‰）：词池太小或变量太少，凑出更多候选必然与已有标题雷同。"
                  + "请往修饰词池里加词，或给模板多配一个变量";
          continue;
        }
        accepted =
            new Candidate(
                sku.skuId(),
                platform.platform(),
                k,
                title,
                TitleRules.charLength(title),
                truncated,
                rotation,
                similarity,
                rendered.coreKeyword(),
                rendered.sellingKeyword(),
                rendered.modifierKeyword());
        break;
      }
      if (accepted == null) {
        rejections.add(
            new Rejection(
                sku.skuId(),
                platform.platform(),
                k,
                bestReason == null ? "没有可用组合" : bestReason,
                bestSimilarity));
        continue;
      }
      candidates.add(accepted);
      known.add(accepted.title());
    }

    return new SkuPlatformResult(
        sku.skuId(), platform.platform(), template.candidateCount(), candidates, rejections);
  }

  private static SkuPlatformResult rejected(
      java.util.UUID skuId, String platform, int expected, String reason) {
    List<Rejection> rejections = new ArrayList<>();
    for (int k = 1; k <= expected; k++)
      rejections.add(new Rejection(skuId, platform, k, reason, 0));
    return new SkuPlatformResult(skuId, platform, expected, List.of(), rejections);
  }

  // ============================================================ 渲染

  /** 一次渲染的结果：文本 + 实际用到的三类词（作为溯源快照）。 */
  private record Rendered(
      String text, String coreKeyword, String sellingKeyword, String modifierKeyword) {}

  /**
   * 渲染模板。
   *
   * <p>词序轮转的实现：把 pattern 拆成「字面量段 + 占位符」交替的序列， 然后把**占位符序列整体循环左移 {@code rotation} 位**，字面量段留在原位。
   *
   * <p>为什么字面量不跟着转：字面量里含分隔符（「 - 」之类）， 连它一起转的话「{brand} - {core}」在 rotation=1 时会变成「 - {core}{brand}」，
   * 读起来是坏的、平台判定上也更怪。只转变量既保证了每个组合仍是合法句子， 又让同一个 SKU 的不同候选在词序上真正不同。
   */
  private static Rendered render(
      String pattern,
      List<VariableSpec> variables,
      int rotation,
      SkuFacts sku,
      String coreKeyword,
      String sellingKeyword,
      String modifierKeyword) {
    List<Piece> pieces = parse(pattern);
    // 按 slotNo 排序得到「逻辑顺序」，再按 rotation 循环左移。
    List<VariableSpec> rotated = new ArrayList<>(variables);
    Collections.rotate(rotated, -rotation);

    StringBuilder sb = new StringBuilder();
    int varIndex = 0;
    for (Piece piece : pieces) {
      if (piece.literal() != null) {
        sb.append(piece.literal());
        continue;
      }
      VariableSpec variable = rotated.get(varIndex % rotated.size());
      varIndex++;
      String value = bind(variable.source(), sku, coreKeyword, sellingKeyword, modifierKeyword);
      if (value != null && !value.isBlank()) {
        sb.append(value);
      }
      // 绑定为空时**不留占位符**、也不留多余空白：下一段字面量若以空格开头，
      // 最终 clamp/trim 会把双空格收掉；这里直接跳过空值即可。
    }
    return new Rendered(
        collapseSpaces(sb.toString()).trim(), coreKeyword, sellingKeyword, modifierKeyword);
  }

  /** pattern 的一段：要么是字面量，要么是一个占位符名。 */
  private record Piece(String literal, String placeholder) {}

  /** 把模板拆成交替的字面量段与占位符。 */
  private static List<Piece> parse(String pattern) {
    String value = TitleRules.normalizePattern(pattern);
    List<Piece> pieces = new ArrayList<>();
    StringBuilder literal = new StringBuilder();
    int i = 0;
    while (i < value.length()) {
      int open = value.indexOf('{', i);
      if (open < 0) {
        literal.append(value, i, value.length());
        break;
      }
      int close = value.indexOf('}', open + 1);
      if (close < 0) {
        literal.append(value, i, value.length());
        break;
      }
      literal.append(value, i, open);
      if (literal.length() > 0) {
        pieces.add(new Piece(literal.toString(), null));
        literal.setLength(0);
      }
      String name = value.substring(open + 1, close).trim();
      if (!name.isEmpty()) pieces.add(new Piece(null, name));
      i = close + 1;
    }
    if (literal.length() > 0) pieces.add(new Piece(literal.toString(), null));
    return pieces;
  }

  /**
   * 取变量的值。事实字段取自 SKU 快照；词根类型取自词池。
   *
   * <p>事实字段为空时返回空串而不是占位符：**变量缺失必须被看见**—— 返回 {@code {brand}} 会让「品牌还没填」这件事在标题里看不出来， 而 3.4.2.2
   * 已经明令禁止「静默留下占位符」，这里与它同一纪律。
   */
  private static String bind(
      String source, SkuFacts sku, String core, String selling, String modifier) {
    String value = MediaRules.normalizeUpper(source);
    switch (value) {
      case "SPU_BRAND":
        return MediaRules.normalize(sku.brand());
      case "SPU_CATEGORY":
        return MediaRules.normalize(sku.category());
      case "SPU_NAME":
        return MediaRules.normalize(sku.spuName());
      case "SKU_NAME":
        return MediaRules.normalize(sku.skuName());
      case "SKU_CODE":
        return MediaRules.normalize(sku.skuCode());
      case "SKU_SPEC":
        return MediaRules.normalize(sku.spec());
      case "KEYWORD_CORE":
        return core;
      case "KEYWORD_SELLING":
        return selling;
      case "KEYWORD_MODIFIER":
        return modifier;
      default:
        // 未知绑定源在 TitleRules.sourceProblem 里已经被拒，
        // 到这里只能是拼错 pattern（占位符名与变量声明对不上）——返回空而不是崩。
        return "";
    }
  }

  // ============================================================ 工具

  private static List<VariableSpec> sortedVariables(List<VariableSpec> variables) {
    if (variables == null || variables.isEmpty()) return List.of();
    List<VariableSpec> out = new ArrayList<>(variables);
    out.sort(java.util.Comparator.comparingInt(VariableSpec::slotNo));
    return out;
  }

  /**
   * 取词池里第 index 个词（循环）；**空池子返回空串**而不是除零。
   *
   * <p>{@code Math.floorMod(x, 0)} 会抛 {@link ArithmeticException}，而「租户还没建某一类词根」
   * 是完全正常的初始状态（刚开通标题库的租户只建了几个核心词）。那时应该表现成 「绑定到空池子的那个变量渲染成空、其余变量照常工作」，而不是整批 500。
   */
  private static String poolAt(List<String> pool, int index) {
    int size = poolSize(pool);
    if (size == 0) return "";
    return pool.get(Math.floorMod(index, size));
  }

  private static int poolSize(List<String> pool) {
    return pool == null ? 0 : pool.size();
  }

  /**
   * 折叠多余空白。
   *
   * <p>绑定为空时会让两段字面量的空格碰面（「{brand} {core}」在 brand 为空时 变成「 core」或「core 」）。折叠与 trim 让渲染结果始终是干净的句子，
   * 也让「同一个标题」在相似度比较里不会因为空格数量不同而被当成两条不同的。
   */
  private static String collapseSpaces(String value) {
    return value == null ? "" : value.replaceAll("\\s+", " ");
  }

  /** 词池里的词去重（同一类型内 DB 已经唯一，这里防的是调用方自己塞进来的重复）。 */
  public static List<String> dedupe(List<String> pool) {
    if (pool == null) return List.of();
    return new ArrayList<>(
        new LinkedHashSet<>(pool.stream().filter(s -> s != null && !s.isBlank()).toList()));
  }
}
