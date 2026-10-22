package com.starcon.channel;

import static com.starcon.channel.IpRiskLibraryRules.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 16.4.1.1 侵权库 的<b>值域与逐行校验</b>单测（纯逻辑，无 IO）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的五件事：
 *
 * <ol>
 *   <li><b>权利类型恰为四种</b>（品牌商标 / 专利 / 版权 / 肖像权）—— 不多不少，也不留自由文本；</li>
 *   <li><b>风险等级是受控等级集</b>（高 / 中 / 低）—— <b>不是</b> 0..100 的裸数字；</li>
 *   <li><b>等级有顺序</b>（高 &gt; 中 &gt; 低）—— 排序与「取最严重的一条」用同一份权重；</li>
 *   <li><b>逐行校验给出「哪个字段 + 为什么」</b> —— 不是整份文件一个「失败」；</li>
 *   <li><b>未知平台被点名</b> —— 适用平台必须来自平台目录，不能是自由文本。</li>
 * </ol>
 */
class IpRiskLibraryRulesTest {

  @Test
  @DisplayName("16.4.1.1 权利类型恰为四种，风险等级恰为三级")
  void valueDomains() {
    assertThat(RIGHT_TYPES)
        .containsExactlyInAnyOrder("TRADEMARK", "PATENT", "COPYRIGHT", "IMAGE_RIGHT");
    assertThat(RISK_LEVELS).containsExactlyInAnyOrder("HIGH", "MEDIUM", "LOW");
    assertThat(STATUSES).containsExactlyInAnyOrder("ACTIVE", "DISABLED");
    assertThat(TERM_KINDS).containsExactlyInAnyOrder("KEYWORD", "PHRASE");
    assertThat(SOURCES).containsExactlyInAnyOrder("MANUAL", "IMPORT");

    assertThat(knownRightType("TRADEMARK")).isTrue();
    assertThat(knownRightType("IMAGE_RIGHT")).isTrue();
    assertThat(knownRightType("trademark")).isFalse();
    assertThat(knownRightType("商标")).isFalse();
    assertThat(knownRightType(null)).isFalse();

    assertThat(knownRiskLevel("HIGH")).isTrue();
    assertThat(knownRiskLevel("CRITICAL")).isFalse();
    assertThat(knownRiskLevel("72")).isFalse();
  }

  @Test
  @DisplayName("16.4.1.1 风险等级有顺序：高 &gt; 中 &gt; 低")
  void riskWeightOrdering() {
    assertThat(riskWeight("HIGH")).isGreaterThan(riskWeight("MEDIUM"));
    assertThat(riskWeight("MEDIUM")).isGreaterThan(riskWeight("LOW"));
    assertThat(riskWeight("NOPE")).isZero();
    assertThat(riskWeight(null)).isZero();
  }

  @Test
  @DisplayName("16.4.1.1 人话名齐全，未知编码回落到原值（不编造）")
  void labels() {
    assertThat(rightTypeLabel("TRADEMARK")).isEqualTo("品牌商标");
    assertThat(rightTypeLabel("IMAGE_RIGHT")).isEqualTo("肖像权");
    assertThat(riskLevelLabel("HIGH")).isEqualTo("高风险");
    assertThat(statusLabel("DISABLED")).isEqualTo("已停用");
    assertThat(termKindLabel("PHRASE")).isEqualTo("短语");
    assertThat(sourceLabel("IMPORT")).isEqualTo("批量导入");
    assertThat(rightTypeLabel("NOPE")).isEqualTo("NOPE");
  }

  @Test
  @DisplayName("16.4.1.1 逐行校验：干净的行没有问题")
  void cleanRowHasNoProblems() {
    List<IpRiskLibraryRules.RowProblem> problems =
        validateEntryFields(
            "TM-001",
            "TRADEMARK",
            "HIGH",
            "Nike 运动商标",
            List.of("AMAZON", "EBAY"),
            Set.of("AMAZON", "EBAY", "SHOPIFY"));
    assertThat(problems).isEmpty();
  }

  @Test
  @DisplayName("16.4.1.1 逐行校验：每个坏字段都被点名（字段 + 原因）")
  void problemsNameTheField() {
    List<IpRiskLibraryRules.RowProblem> problems =
        validateEntryFields(
            "",
            "商标",
            "72",
            "  ",
            List.of("NOT_A_PLATFORM"),
            Set.of("AMAZON"));
    assertThat(problems).extracting(IpRiskLibraryRules.RowProblem::field)
        .contains("entryCode", "rightType", "riskLevel", "title", "platforms");
    assertThat(problems).allSatisfy(p -> assertThat(p.message()).isNotBlank());
    // 🔴 自由文本权利类型被拒（否则「商标 / TRADEMARK / 商标类」会被当成三种权利）。
    assertThat(problems)
        .filteredOn(p -> p.field().equals("rightType"))
        .singleElement()
        .satisfies(p -> assertThat(p.message()).contains("TRADEMARK"));
    // 🔴 未知平台被点名（平台不是自由文本）。
    assertThat(problems)
        .filteredOn(p -> p.field().equals("platforms"))
        .singleElement()
        .satisfies(p -> assertThat(p.message()).contains("NOT_A_PLATFORM"));
  }

  @Test
  @DisplayName("16.4.1.1 逐行校验：平台列表里的空值被拒")
  void emptyPlatformRejected() {
    List<IpRiskLibraryRules.RowProblem> problems =
        validateEntryFields(
            "TM-002", "PATENT", "LOW", "某结构专利", List.of(" "), Set.of("AMAZON"));
    assertThat(problems).extracting(IpRiskLibraryRules.RowProblem::field).contains("platforms");
  }
}
