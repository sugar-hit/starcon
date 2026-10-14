package com.starcon.catalog;

import static com.starcon.catalog.CustomsDestinationVersionRules.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.*;

/**
 * 3.7.1.2 纯规则单测：目的国归一（含<b>拒绝通配</b>）、目的地税号格式、 认证码归一、生效区间判定（半开区间），以及<b>覆盖 vs 继承的合成</b>。
 *
 * <p>这些判定决定的是「这批货发到德国该报哪个税号」，代价出现在 <b>真实的清关退单、扣货与罚款</b>上（V85：扣货与罚款由卖家承担）， 而判定发生在录入那一刻 ——
 * 所以必须能不碰数据库逐条穷举。 集成测试（{@code CustomsDestinationVersionIntegrationTest}）只证明这些规则 真的被用上了，证明不了规则本身对不对。
 */
class CustomsDestinationVersionRulesTest {
  static final Instant T10 = Instant.parse("2026-03-01T10:00:00Z");
  static final Instant T08 = Instant.parse("2026-03-01T08:00:00Z");
  static final Instant T11 = Instant.parse("2026-03-01T11:00:00Z");
  static final Instant T12 = Instant.parse("2026-03-01T12:00:00Z");
  static final Instant T14 = Instant.parse("2026-03-01T14:00:00Z");

  /** 合成输入：基础侧（3.7.1.1）+ 目的地侧（3.7.1.2）。 */
  static MergeInput input(String baseHs, String baseName, String destHs, String destName) {
    return new MergeInput(baseHs, baseName, "PCE", "CN", false, "", destHs, destName, false);
  }

  // ============================================================ 规则 B5：目的国不可通配

  @Test
  void 目的国必须是两位大写字母() {
    assertThat(validDestination("DE")).isTrue();
    assertThat(validDestination("de")).isFalse();
    assertThat(validDestination("DEU")).isFalse();
    assertThat(validDestination("D")).isFalse();
    assertThat(validDestination("D1")).isFalse();
    assertThat(validDestination(null)).isFalse();
  }

  @Test
  void 目的国归一化去空格并转大写() {
    assertThat(normalizeDestination(" de ")).isEqualTo("DE");
    assertThat(normalizeDestination("us")).isEqualTo("US");
    assertThat(normalizeDestination("Gb")).isEqualTo("GB");
  }

  @Test
  void 目的国不能通配成星号() {
    // **本模块最重要的一条**：蓝图原文「不同目的地必须用不同版本，
    // 不能用一套申报资料打通全球」。接受 '*' 等于把要杜绝的做法引进模块。
    assertThatThrownBy(() -> normalizeDestination("*"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能用一套申报资料打通全球");
    // 带空格的通配同样拒：'*' 与 ' * ' 在「不限目的地」这件事上是同一个意思。
    assertThatThrownBy(() -> normalizeDestination(" * "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能用一套申报资料打通全球");
  }

  @Test
  void 目的国缺失或格式非法给出可读原因() {
    assertThatThrownBy(() -> normalizeDestination(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("请填写目的地国家");
    assertThatThrownBy(() -> normalizeDestination(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("请填写目的地国家");
    assertThatThrownBy(() -> normalizeDestination("DEU"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ISO 3166-1 alpha-2")
        .hasMessageContaining("把某个国家的申报资料报给了另一个国家");
  }

  // ============================================================ 规则 B4：目的地税号

  @Test
  void 目的地税号非空时必须是六到十位纯数字() {
    assertThat(validDestinationHsCode("851712")).isTrue();
    assertThat(validDestinationHsCode("8517123090")).isTrue();
    assertThat(validDestinationHsCode("85171")).isFalse();
    assertThat(validDestinationHsCode("85171230901")).isFalse();
    assertThat(validDestinationHsCode("8517A2")).isFalse();
    // **空串在这里合法**：它读作「沿用基础 HS 编码」，不是「没填」。
    assertThat(validDestinationHsCode("")).isTrue();
    assertThat(validDestinationHsCode(null)).isFalse();
  }

  @Test
  void 目的地税号归一化把空视为沿用基础() {
    assertThat(normalizeDestinationHsCode(null)).isEmpty();
    assertThat(normalizeDestinationHsCode("   ")).isEmpty();
    assertThat(normalizeDestinationHsCode("  851712 ")).isEqualTo("851712");
    // 不做善意修正：补零 / 换字母会把一个明显的错码改成「看起来对」的错码。
    assertThatThrownBy(() -> normalizeDestinationHsCode("8517A2"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("6–10 位纯数字")
        .hasMessageContaining("或者留空表示沿用基础 HS 编码");
    assertThatThrownBy(() -> normalizeDestinationHsCode("851"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("扣货与罚款由卖家承担");
  }

  @Test
  void 目的地申报品名空值合法因为空即沿用基础() {
    assertThat(normalizeDestinationDeclaredName(null)).isEmpty();
    assertThat(normalizeDestinationDeclaredName("  ")).isEmpty();
    assertThat(normalizeDestinationDeclaredName(" Mobiltelefon ")).isEqualTo("Mobiltelefon");
    assertThatThrownBy(() -> normalizeDestinationDeclaredName("品".repeat(501)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("最多 500 字");
  }

  @Test
  void 版本号必须从一开始() {
    assertThat(normalizeVersionNo(1)).isEqualTo(1);
    assertThatThrownBy(() -> normalizeVersionNo(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("从 1 开始");
    assertThatThrownBy(() -> normalizeVersionNo(-3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("从 1 开始");
  }

  // ============================================================ 认证码归一

  @Test
  void 认证码归一化转大写因为大小写不参与含义() {
    assertThat(normalizeRequirementCode("ce")).isEqualTo("CE");
    assertThat(normalizeRequirementCode(" RoHS ")).isEqualTo("ROHS");
    assertThat(normalizeRequirementCode("ukca")).isEqualTo("UKCA");
    // 危包证这类带版本号与分隔符的代码必须放行。
    assertThat(normalizeRequirementCode("un38.3")).isEqualTo("UN38.3");
    assertThatThrownBy(() -> normalizeRequirementCode("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("请填写认证代码");
    assertThatThrownBy(() -> normalizeRequirementCode("C".repeat(65)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("最多 64 个字符");
  }

  // ============================================================ 规则 R1：半开区间

  @Test
  void 相邻区间在前一版终点处不重叠() {
    // **本条是版本接替的标准形状**：[10:00,12:00) 与 [12:00,14:00) 在 12:00 处不重叠。
    // 用闭区间会把「刚刚好接替」误判成冲突，运营每次换版都要被迫留一小时空档。
    assertThat(overlaps(T10, T12, T12, T14)).isFalse();
    assertThat(overlaps(T12, T14, T10, T12)).isFalse();
    // 但真正交叠的一定要判为重叠。
    assertThat(overlaps(T10, T12, T11, T14)).isTrue();
    assertThat(overlaps(T11, T14, T10, T12)).isTrue();
    // 完全包含与完全相同。
    assertThat(overlaps(T10, T14, T11, T12)).isTrue();
    assertThat(overlaps(T10, T12, T10, T12)).isTrue();
    // 完全不相邻。
    assertThat(overlaps(T10, T12, T14, null)).isFalse();
  }

  @Test
  void 空终点表示正无穷所以永远与别人重叠() {
    assertThat(overlaps(T10, null, T12, T14)).isTrue();
    assertThat(overlaps(T12, T14, T10, null)).isTrue();
    // 但起点在别人终点之后就不重叠（+∞ 的那一版从 T12 才开始）。
    assertThat(overlaps(T14, null, T10, T12)).isFalse();
  }

  @Test
  void 生效区间是左闭右开() {
    assertThat(covers(T10, T12, T10)).isTrue(); // 起点闭
    assertThat(covers(T10, T12, T12)).isFalse(); // 终点开
    assertThat(covers(T10, T12, T11)).isTrue();
    // to = null 表示 +∞。
    assertThat(covers(T10, null, T10)).isTrue();
    assertThat(covers(T10, null, Instant.parse("2099-01-01T00:00:00Z"))).isTrue();
    // 起点之前不生效。
    assertThat(covers(T10, T12, T10.minusMillis(1))).isFalse();
  }

  @Test
  void 区间必须有序() {
    checkRangeOrdered(T10, null); // 一直有效合法
    checkRangeOrdered(T10, T12);
    // 相等也不行：空区间等于「从不生效」。
    assertThatThrownBy(() -> checkRangeOrdered(T12, T12))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须晚于生效起点");
    assertThatThrownBy(() -> checkRangeOrdered(T14, T12))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("左闭右开");
    assertThatThrownBy(() -> checkRangeOrdered(null, T12))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("请填写生效起点");
  }

  @Test
  void 时间戳解析收窄到毫秒否则保存后区间会变() {
    // PG 的 timestamptz 是微秒精度；不收窄就会出现「保存后区间变了」。
    assertThat(parseInstant("2026-03-01T10:00:00.123456Z", "查询时刻"))
        .isEqualTo(Instant.parse("2026-03-01T10:00:00.123Z"));
    // 带非零时区偏移的入参要换算到 UTC：18:00+08:00 就是 10:00Z。
    assertThat(parseInstant("2026-03-01T18:00:00+08:00", "查询时刻")).isEqualTo(T10);
    // 同一时刻的另一种写法必须解析成同一个 Instant（否则区间比较会漂）。
    assertThat(parseInstant("2026-03-01T08:00:00+00:00", "查询时刻")).isEqualTo(T08);
    // 空 = 调用方没传（不传 at 就是查此刻）。
    assertThat(parseInstant(null, "查询时刻")).isNull();
    assertThat(parseInstant("  ", "查询时刻")).isNull();
    assertThatThrownBy(() -> parseInstant("2026-03-01", "生效起点"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("生效起点 不是合法的带时区时间");
  }

  // ============================================================ 覆盖 vs 继承（本模块最要紧的一条）

  @Test
  void 目的地都覆盖时合成出目的地的税号与品名() {
    var merged = merge(input("851712", "移动电话机", "85171300", "Mobiltelefon"));
    assertThat(merged.hsCode()).isEqualTo("85171300");
    assertThat(merged.hsCodeSource()).isEqualTo(SOURCE_DESTINATION);
    assertThat(merged.hsCodeOverridden()).isTrue();
    assertThat(merged.declaredName()).isEqualTo("Mobiltelefon");
    assertThat(merged.declaredNameSource()).isEqualTo(SOURCE_DESTINATION);
    assertThat(merged.declaredNameOverridden()).isTrue();
  }

  @Test
  void 目的地没填就继承基础且明确标出是继承() {
    var merged = merge(input("851712", "移动电话机", "", ""));
    assertThat(merged.hsCode()).isEqualTo("851712");
    assertThat(merged.hsCodeSource()).isEqualTo(SOURCE_BASE);
    assertThat(merged.hsCodeOverridden()).isFalse();
    assertThat(merged.declaredName()).isEqualTo("移动电话机");
    assertThat(merged.declaredNameSource()).isEqualTo(SOURCE_BASE);
    assertThat(merged.declaredNameOverridden()).isFalse();
  }

  @Test
  void 税号与品名各自独立继承而不是一起() {
    // 只覆盖了税号，品名沿用基础 —— 两格独立判定，不是「覆盖了一个就两个都用目的地的」。
    var merged = merge(input("851712", "移动电话机", "85171300", ""));
    assertThat(merged.hsCode()).isEqualTo("85171300");
    assertThat(merged.hsCodeOverridden()).isTrue();
    assertThat(merged.declaredName()).isEqualTo("移动电话机");
    assertThat(merged.declaredNameOverridden()).isFalse();
  }

  @Test
  void 法定计量单位只来自基础属性目的地碰不到它() {
    // MergeInput 的目的地侧**没有**法定单位这一格：类型层面就复制不了。
    var merged = merge(input("851712", "移动电话机", "85171300", "Mobiltelefon"));
    assertThat(merged.statutoryUnitCode()).isEqualTo("PCE");
    assertThat(merged.statutoryUnitSource()).isEqualTo(SOURCE_BASE);
    // 原产地同理：它决定协定，而协定是原产地事实不是目的地事实。
    assertThat(merged.originCountryCode()).isEqualTo("CN");
  }

  @Test
  void 享惠要基础有资格且目的地承认两者都满足() {
    var both = merge(new MergeInput("851712", "手机", "PCE", "CN", true, "RCEP", "", "", true));
    assertThat(both.preferentialOriginEligible()).isTrue();
    assertThat(both.preferentialApplicable()).isTrue();
    assertThat(both.preferentialGranted()).isTrue();
    assertThat(both.preferenceAgreement()).isEqualTo("RCEP");

    // **有资格但这个目的地不认** —— 真实存在的状态，两个布尔必须能分开表达。
    var eligibleOnly =
        merge(new MergeInput("851712", "手机", "PCE", "CN", true, "RCEP", "", "", false));
    assertThat(eligibleOnly.preferentialOriginEligible()).isTrue();
    assertThat(eligibleOnly.preferentialApplicable()).isFalse();
    assertThat(eligibleOnly.preferentialGranted()).isFalse();

    // 目的地承认但基础没资格：同样不享惠。
    var applicableOnly =
        merge(new MergeInput("851712", "手机", "PCE", "CN", false, "", "", "", true));
    assertThat(applicableOnly.preferentialGranted()).isFalse();
  }

  @Test
  void 合成结果里空白覆盖值被当作沿用而不是异常() {
    // null 与空白在合成里等价于「沿用基础」：保存端已归一过，
    // 这里再挡一次是为了让裸调用 Rules 的测试与生产行为一致。
    var merged = merge(input("851712", "移动电话机", "   ", null));
    assertThat(merged.hsCodeOverridden()).isFalse();
    assertThat(merged.declaredNameOverridden()).isFalse();
    assertThat(merged.hsCodeSource()).isEqualTo(SOURCE_BASE);
  }

  // ============================================================ 展示口径

  @Test
  void 状态标签与生效标签是两件事() {
    assertThat(statusLabel(DRAFT)).isEqualTo("草稿");
    assertThat(statusLabel(PUBLISHED)).isEqualTo("已发布");
    assertThat(statusLabel(null)).isEqualTo("未维护");
    // 「已发布」不等于「此刻生效」：一个已发布版本可能早已被下一个接替。
    assertThat(effectiveLabel(false, false)).contains("草稿不可被报关引用");
    assertThat(effectiveLabel(true, true)).isEqualTo("此刻生效");
    assertThat(effectiveLabel(true, false)).contains("历史版本");
    assertThat(sourceLabel(SOURCE_DESTINATION)).isEqualTo("目的地版本覆盖");
    assertThat(sourceLabel(SOURCE_BASE)).isEqualTo("继承基础报关属性");
  }

  @Test
  void 要素齐备状态沿用基础属性的常量而不是新造一个() {
    // 合成链路里的申报要素原封不动来自 3.7.1.1，新造一个会让下游分不清是谁判的。
    assertThat(ELEMENT_STATUS_COMPLETE).isEqualTo(CustomsAttributeRules.ELEMENT_STATUS_COMPLETE);
  }
}
