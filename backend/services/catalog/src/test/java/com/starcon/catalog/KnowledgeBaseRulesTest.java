package com.starcon.catalog;

import static com.starcon.catalog.KnowledgeBaseRules.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 11.4.2.2 客服知识库与话术的纯规则测试。
 *
 * <p>本文件逐条钉死 R1~R5（口径写在 {@link KnowledgeBaseRules} 的类注释里）， 并对与 3.4.2.2（{@code
 * DescriptionRules}）共有的纪律做<b>跨模块一致性回归断言</b>： 两条资产线各自演进，但「多语言占位符必须一致」这条不能让其中一边先破。
 */
class KnowledgeBaseRulesTest {

  // ================================================================ R1 占位符一致性

  @Test
  void 占位符完全一致时通过() {
    assertThat(
            placeholderProblem(
                "您好 {buyer_name}，订单 {order_no}", "Hi {buyer_name}, order {order_no}"))
        .isNull();
  }

  @Test
  void 译文漏占位符必须报错并说清漏了哪个() {
    String problem =
        placeholderProblem("您好 {buyer_name}，订单 {order_no}", "Hi buyer_name, order {order_no}");
    assertThat(problem).contains("不一致").contains("buyer_name").contains("漏抄");
  }

  @Test
  void 译文多占位符必须报错并说清多了哪个() {
    String problem =
        placeholderProblem("您好 {buyer_name}", "Hi {buyer_name}, tracking {tracking_no}");
    assertThat(problem).contains("不一致").contains("tracking_no").contains("永远填不上");
  }

  @Test
  void 漏与多同时出现时两个都报出来() {
    String problem = placeholderProblem("{a} {b}", "{a} {c}");
    assertThat(problem).contains("b").contains("c");
  }

  @Test
  void 占位符集合与出现次数无关() {
    // {x} 出现三次与出现一次是同一个「缺失」：同一个占位符没有取到值，报三次没有信息量。
    assertThat(placeholderProblem("请确认 {order_no}", "请确认 {order_no} {order_no} {order_no}"))
        .isNull();
  }

  @Test
  void 两边都没有占位符时通过() {
    assertThat(placeholderProblem("感谢您的耐心等待。", "Thank you for your patience.")).isNull();
  }

  @Test
  void 占位符取花括号之间的任意非空内容() {
    // 与 3.4.2.2 同一宽松口径：不限定 ASCII 标识符，带空格的中文也是占位符。
    assertThat(placeholdersOf("提示：{有空格} 与 {order_no}")).isEqualTo(Set.of("有空格", "order_no"));
    // 空花括号不是占位符。
    assertThat(placeholdersOf("{ } 与 {}")).isEmpty();
    // 没闭合的左花括号不算（后面没有配对的 }）。
    assertThat(placeholdersOf("{order_no")).isEmpty();
  }

  @Test
  void 占位符提取去重且有序() {
    assertThat(List.copyOf(placeholdersOf("{b} {a} {b}"))).containsExactly("a", "b");
  }

  // ================================================================ R2~R4 命中率

  @Test
  void 命中率按启用话术条数计算而不是引用次数() {
    // 10 条启用话术里 3 条被引用过 → 0.3，而不是「引用 800 次所以命中率很高」。
    assertThat(hitRate(10, 3)).isEqualByComparingTo("0.3000");
  }

  @Test
  void 命中率为零时是零不是null() {
    // 「有话术但一条都没被用过」是真实状态，必须是 0.0000。
    assertThat(hitRate(10, 0)).isEqualByComparingTo("0.0000");
  }

  @Test
  void 分母为零时报null而不是零() {
    // R4：「一条话术都没有」与「话术一条都没被用过」是两件不同的事，页面必须能区分。
    assertThat(hitRate(0, 0)).isNull();
    assertThat(hitRate(0, 5)).isNull();
  }

  @Test
  void 全部命中时是一() {
    assertThat(hitRate(8, 8)).isEqualByComparingTo("1.0000");
  }

  @Test
  void 命中率保留四位小数() {
    // 1/3 = 0.3333…，保留 4 位 HALF_UP。
    assertThat(hitRate(3, 1)).isEqualByComparingTo("0.3333");
    assertThat(hitRate(3, 2)).isEqualByComparingTo("0.6667");
  }

  @Test
  void 只有已解决与部分解决算命中() {
    // R3：UNRESOLVED 是「引用了但没答上」，算进分子会奖励坏话术。
    assertThat(countsAsHit("RESOLVED")).isTrue();
    assertThat(countsAsHit("PARTIAL")).isTrue();
    assertThat(countsAsHit("UNRESOLVED")).isFalse();
    assertThat(countsAsHit("ABANDONED")).isFalse();
    assertThat(countsAsHit("REJECTED")).isFalse();
    assertThat(countsAsHit(null)).isFalse();
  }

  @Test
  void 被驳回与放弃引用是两类需要盯的负信号() {
    assertThat(isProblemOutcome("REJECTED")).isTrue();
    assertThat(isProblemOutcome("ABANDONED")).isTrue();
    assertThat(isProblemOutcome("RESOLVED")).isFalse();
    assertThat(isProblemOutcome("UNRESOLVED")).isFalse();
  }

  @Test
  void 命中率的返回类型是精确十进制不是浮点() {
    assertThat(hitRate(3, 1)).isInstanceOf(BigDecimal.class);
  }

  // ================================================================ R5 触发词

  @Test
  void 触发词正常写法通过() {
    assertThat(keywordsProblem("退,退货,换货,refund")).isNull();
    assertThat(keywordsProblem(null)).isNull();
    assertThat(keywordsProblem("  ")).isNull();
  }

  @Test
  void 触发词规范化去重并统一分隔() {
    // 复制粘贴很容易把同一个词写两遍，那样列表页会显示「退, 退」并在检索词表里占两格。
    assertThat(normalizeKeywords(" 退 , 退货,退 ")).isEqualTo("退,退货");
    assertThat(normalizeKeywords("退，退")).isEqualTo("退");
  }

  @Test
  void 触发词数量超限必须报错() {
    assertThat(
            keywordsProblem(
                String.join(
                    ",", java.util.stream.IntStream.range(0, 13).mapToObj(i -> "词" + i).toList())))
        .contains("最多 12 个");
  }

  @Test
  void 触发词太长必须报错并说明为什么是短词() {
    // 41 个字符（超过 MAX_KEYWORD=40），且是「一整句话」而不是短词。
    String problem = keywordsProblem("退货".repeat(21));
    assertThat(problem).contains("40").contains("短词");
    // 正好 40 个字符是合法的（边界含等号）。
    assertThat(keywordsProblem("退".repeat(40))).isNull();
  }

  @Test
  void 触发词不能含花括号或逗号() {
    assertThat(keywordsProblem("{buyer_name}")).contains("花括号");
    // 单个词内含逗号会让「退,换」被切成两个词，切出来的那个是错的。
    assertThat(keywordsProblem("退,换货,正常,,  ")).isNull();
  }

  @Test
  void 只由逗号组成的触发词必须报错() {
    assertThat(keywordsProblem(",,,")).isNotNull();
  }

  // ================================================================ 条目校验

  @Test
  void 编码规则与错误文案() {
    assertThat(codeProblem("refund_policy")).isNull();
    assertThat(codeProblem("REFUND_POLICY")).isNull();
    assertThat(codeProblem("")).contains("不能为空");
    assertThat(codeProblem("退换货话术")).contains("大写字母");
    assertThat(codeProblem("A".repeat(33))).contains("最长 32");
  }

  @Test
  void 标题与正文长度上限() {
    assertThat(titleProblem("退货政策")).isNull();
    assertThat(titleProblem("x".repeat(121))).contains("120");
    assertThat(bodyProblem("标准答法")).isNull();
    // 正文上限 8000（V21 的详情页是 20000）：客服会话窗口里的话术是一屏，不是说明书。
    assertThat(bodyProblem("x".repeat(8001))).contains("8000").contains("一屏");
  }

  @Test
  void 话术类型只能是四种() {
    for (String type : ENTRY_TYPES) assertThat(entryTypeProblem(type)).isNull();
    assertThat(entryTypeProblem("FAQ")).isNull();
    assertThat(entryTypeProblem("faq")).isNull();
    assertThat(entryTypeProblem("TICKET")).contains("FAQ / POLICY / RETURN_EX / OTHER");
    assertThat(entryTypeProblem(null)).contains("不能为空");
  }

  @Test
  void 语言代码按BCP47校验并规范化大小写() {
    assertThat(localeProblem("zh-CN")).isNull();
    assertThat(localeProblem("en")).isNull();
    assertThat(localeProblem("zh_CN")).contains("BCP-47");
    assertThat(normalizeLocale("zh-cn")).isEqualTo("zh-CN");
    assertThat(normalizeLocale("EN-us")).isEqualTo("en-US");
    assertThat(normalizeLocale("fr")).isEqualTo("fr");
  }

  // ================================================================ 规则版本

  @Test
  void 政策编码规则() {
    assertThat(policyKeyProblem("AMAZON_RETURN_POLICY")).isNull();
    // 小写写法是**合法输入**（服务层负责转大写），不是错误 —— 校验只判字符集与长度。
    assertThat(policyKeyProblem("amazon_return_policy")).isNull();
    assertThat(policyKeyProblem("")).contains("不能为空");
    assertThat(policyKeyProblem("A".repeat(49))).contains("48");
    assertThat(policyKeyProblem("有中文")).contains("大写字母");
    // 落库形态是大写：服务层用 normalizeUpper 规范化后写入。
    assertThat(normalizeUpper("amazon_return_policy")).isEqualTo("AMAZON_RETURN_POLICY");
  }

  @Test
  void 平台只能是五个平台() {
    for (String platform : PLATFORMS) assertThat(platformProblem(platform)).isNull();
    assertThat(platformProblem("amazon")).isNull();
    assertThat(platformProblem("WALMART")).contains("AMAZON");
  }

  @Test
  void 生效区间左闭右开终点必须严格晚于起点() {
    var from = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8));
    var same = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8));
    var later = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8));
    assertThat(intervalProblem(from, null)).isNull();
    assertThat(intervalProblem(from, later)).isNull();
    assertThat(intervalProblem(from, same)).contains("终点等于起点");
    assertThat(intervalProblem(null, later)).contains("生效起点不能为空");
  }

  @Test
  void 标记被接替时变更摘要必填() {
    // 接替之后客服要靠这句话知道该复核什么；没有摘要的话待办只有「政策改版了，去看看」。
    assertThat(changeSummaryProblem("退货运费改为买家承担", true)).isNull();
    assertThat(changeSummaryProblem("", true)).contains("必须写变更摘要");
    assertThat(changeSummaryProblem("   ", true)).contains("必须写变更摘要");
    // 非接替操作（建草稿 / 改标题）不强制。
    assertThat(changeSummaryProblem("", false)).isNull();
  }

  @Test
  void 变更摘要有长度上限() {
    assertThat(changeSummaryProblem("x".repeat(2001), true)).contains("2000");
  }

  // ================================================================ 跨模块一致性回归

  @Test
  void 与3_4_2_2的正文长度上限是有意不同但都有上限() {
    // 两边都是「有上限」而不是「无上限」，但具体数值不同：
    // 详情页描述是长段落（V21 给 20000），客服话术是一屏（本模块 8000）。
    // 断言的是**各自的上限确实存在**，防止有人为了少截断一次而删掉其中一边的校验。
    assertThat(bodyProblem("x".repeat(MAX_BODY + 1))).contains(String.valueOf(MAX_BODY));
    assertThat(DescriptionRules.bodyProblem("x".repeat(20001))).isNotNull();
  }

  @Test
  void 与3_4_2_2共用同一套多语言占位符命名与判定口径() {
    // 两边都接受 {order_no} 这种下划线标识符，都不接受带空格的写法 ——
    // 资产线不同，但「什么算一个变量」的判定必须一致，否则同一句话在两条线上判定相反。
    // （本模块返回去重排序的 Set 以便直接做集合差，3.4.2.2 返回 List 以便保留出现次序；
    //  判定口径本身由下面两行断言钉住。）
    assertThat(placeholdersOf("{order_no}"))
        .isEqualTo(Set.copyOf(DescriptionRules.placeholdersOf("{order_no}")));
    assertThat(placeholdersOf("{有空格}"))
        .isEqualTo(Set.copyOf(DescriptionRules.placeholdersOf("{有空格}")));
    assertThat(DescriptionRules.placeholdersOf("{order_no}")).containsExactly("order_no");
  }

  @Test
  void 词表枚举与服务层校验口径一致() {
    // 这三条枚举同时出现在 V100 的 CHECK、/options 端点与本页的校验里；
    // 任何一边改了而另一边没改，这里会先炸。
    assertThat(ENTRY_TYPES).containsExactly("FAQ", "POLICY", "RETURN_EX", "OTHER");
    assertThat(USAGE_OUTCOMES)
        .containsExactly("RESOLVED", "PARTIAL", "UNRESOLVED", "ABANDONED", "REJECTED");
    assertThat(REVIEW_STATUSES).containsExactly("PENDING", "REVIEWING", "DONE");
    // 每个枚举值都有中文标签（页面上不显示裸枚举值）。
    for (String type : ENTRY_TYPES) assertThat(entryTypeLabel(type)).isNotEqualTo(type);
    for (String outcome : USAGE_OUTCOMES) assertThat(outcomeLabel(outcome)).isNotEqualTo(outcome);
    for (String status : REVIEW_STATUSES)
      assertThat(reviewStatusLabel(status)).isNotEqualTo(status);
  }

  @Test
  void 标签函数对未知值原样返回而不是猜一个() {
    // 库里出现了词表外的值（老数据、手改）时，页面上显示原值并能被认出来是异常，
    // 猜一个相近标签会把数据问题藏起来。
    assertThat(entryTypeLabel("NOPE")).isEqualTo("NOPE");
    assertThat(outcomeLabel("NOPE")).isEqualTo("NOPE");
    assertThat(reviewStatusLabel("NOPE")).isEqualTo("NOPE");
  }
}
