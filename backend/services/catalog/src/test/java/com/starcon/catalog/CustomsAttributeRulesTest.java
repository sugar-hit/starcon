package com.starcon.catalog;

import static com.starcon.catalog.CustomsAttributeModels.*;
import static com.starcon.catalog.CustomsAttributeRules.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;

/**
 * 3.7.1.1 纯规则单测：HS 编码格式（规则 A1）、享惠双向一致（规则 A2）、
 * 申报要素有序性与齐备性（规则 A4）、可报关性判定、国家码归一。
 *
 * <p>这些判定决定的是「这个 SKU 能不能拿去报关」，代价出现在<b>真实的报关单与罚款</b>上
 * （蓝图：HS 编码错误直接导致申报失败与罚款；V85：扣货与罚款由卖家承担），
 * 而判定发生在录入那一刻 —— 所以必须能不碰数据库逐条穷举。
 * 集成测试（{@code CustomsAttributeIntegrationTest}）只证明这些规则真的被用上了，
 * 证明不了规则本身对不对。
 */
class CustomsAttributeRulesTest {
  static DeclarationElement element(int ordinal, String code, String value, boolean required) {
    return new DeclarationElement(
        UUID.randomUUID(), ordinal, code, "要素" + ordinal, value, required, false);
  }

  static CustomsProfile profile(String patchStatus, String submittedAt, List<DeclarationElement> elements) {
    boolean recorded = patchStatus != null || submittedAt != null;
    return new CustomsProfile(
        UUID.randomUUID(), "SKU-1", "测试商品", "SPU-1",
        recorded ? UUID.randomUUID() : null, "851712", "CN", "手机", null, "PCE",
        false, "", patchStatus, null, submittedAt, null, null, "", "", 0L,
        elements, false, List.of(), List.of(), null);
  }

  // ============================================================ 规则 A1：HS 编码格式

  @Test
  void hs编码必须是六到十位纯数字() {
    assertThat(validHsCode("851712")).isTrue();
    assertThat(validHsCode("8517123090")).isTrue();
    // 下界：少于 6 位一定不是有效 HS 编码。
    assertThat(validHsCode("85171")).isFalse();
    assertThat(validHsCode("851")).isFalse();
    // 上界：现行国际 HS 编码最长 10 位。
    assertThat(validHsCode("85171230901")).isFalse();
    // 带字母的是目的国本地税则号，不是这一列该装的编码。
    assertThat(validHsCode("8517A2")).isFalse();
    assertThat(validHsCode("CN8517")).isFalse();
    // 小数点与空格。
    assertThat(validHsCode("8517.12")).isFalse();
    assertThat(validHsCode("851 712")).isFalse();
    assertThat(validHsCode(null)).isFalse();
  }

  @Test
  void hs编码归一化只去空格不做善意修正() {
    assertThat(normalizeHsCode("  851712  ")).isEqualTo("851712");
    // 关键：**不自动补零、不把字母换成数字**。
    // 修正会把一个明显的错码改成「看起来对」的错码，而错码要到退单时才发现。
    assertThatThrownBy(() -> normalizeHsCode("8517A2"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("6–10 位纯数字")
        .hasMessageContaining("罚款");
    assertThatThrownBy(() -> normalizeHsCode("851")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> normalizeHsCode("  ")).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("请填写");
    assertThatThrownBy(() -> normalizeHsCode(null)).isInstanceOf(IllegalArgumentException.class);
  }

  // ============================================================ 原产地归一

  @Test
  void 原产地国家码trim并转大写() {
    assertThat(normalizeCountryCode("cn", "原产地")).isEqualTo("CN");
    assertThat(normalizeCountryCode(" CN ", "原产地")).isEqualTo("CN");
    assertThat(normalizeCountryCode("vn", "原产地")).isEqualTo("VN");
  }

  @Test
  void 原产地国家码不是两位大写字母时被拒() {
    assertThatThrownBy(() -> normalizeCountryCode("C", "原产地")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> normalizeCountryCode("CHN", "原产地")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> normalizeCountryCode("", "原产地")).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("请填写原产地");
    assertThatThrownBy(() -> normalizeCountryCode(null, "原产地")).isInstanceOf(IllegalArgumentException.class);
    // 报错文案必须点明后果：原产地错 = 税率错 = 优惠失效或被追缴。
    assertThatThrownBy(() -> normalizeCountryCode("123", "原产地"))
        .hasMessageContaining("追缴");
  }

  // ============================================================ 规则 A2：享惠双向一致

  @Test
  void 勾了可享惠必须有协定代码() {
    assertThat(normalizePreference(true, " RCEP ")).isEqualTo("RCEP");
    // 没有协定代码：海关无法适用优惠，而运营以为已经享惠。
    assertThatThrownBy(() -> normalizePreference(true, "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("协定代码");
  }

  @Test
  void 没勾可享惠就不该填协定代码() {
    assertThat(normalizePreference(false, null)).isEmpty();
    assertThat(normalizePreference(false, "")).isEmpty();
    // 反向：两个矛盾的答案会让下游无所适从。
    assertThatThrownBy(() -> normalizePreference(false, "RCEP"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("两个互相矛盾的答案");
  }

  // ============================================================ 申报要素有序性与齐备性（规则 A4）

  @Test
  void 申报要素按位次排序而不是按字母序() {
    var sorted = ordered(
        List.of(element(3, "03", "自用", true), element(1, "01", "手机", true),
            element(2, "02", "品牌类型", true)));
    // 海关按序比对，顺序是业务事实 —— 不能按字母序（01/02/03 恰好同序，要用非字典序验）。
    assertThat(sorted).extracting(DeclarationElement::ordinal).containsExactly(1, 2, 3);

    var reversed = ordered(
        List.of(element(2, "B", "x", true), element(1, "A", "y", true), element(10, "C", "z", true)));
    assertThat(reversed).extracting(DeclarationElement::ordinal).containsExactly(1, 2, 10);
    // 位次 10 必须排在 2 之后：**数值序**，不是字符串序（"10" < "2"）。
    assertThat(reversed.get(2).elementCode()).isEqualTo("C");
  }

  @Test
  void 必填且为空的申报要素被识别出来() {
    var elements =
        List.of(element(1, "01", "手机", true), element(2, "02", "  ", true), element(3, "03", "", true));
    assertThat(missingRequired(ordered(elements))).hasSize(2);
    // 非必填的空白**不算**未满足：可选要素留空是合法的。
    var optional = List.of(element(1, "01", "手机", true), element(2, "02", "", false));
    assertThat(missingRequired(ordered(optional))).isEmpty();
  }

  @Test
  void 申报要素拍成jsonb对象时键是要素码() {
    var byCode = toByCode(ordered(
        List.of(element(1, "01", "手机", true), element(2, "02", "自有品牌", true))));
    assertThat(byCode).containsEntry("01", "手机").containsEntry("02", "自有品牌");
    // 值 null 归一成空串：jsonb 里有 null 值与键缺失在 V85 的 CHECK 下是两种形状。
    var withNull = toByCode(ordered(List.of(element(1, "01", null, true))));
    assertThat(withNull).containsEntry("01", "");
  }

  // ============================================================ 可报关性（V94 第一节）

  @Test
  void 未维护报关属性判不可报关() {
    var result = CustomsAttributeRules.verdict(profile(null, null, List.of()), List.of());
    assertThat(result.ready()).isFalse();
    assertThat(result.blockers()).containsExactly(NOT_RECORDED);
    assertThat(blockerLabel(NOT_RECORDED)).contains("还没有报关属性");
  }

  @Test
  void 草稿未提交判不可报关() {
    // 「未提交」与「已提交待审」是**两个不同的阻断原因**：给运营的下一步动作不同。
    var notSubmitted = CustomsAttributeRules.verdict(profile(DRAFT, null, List.of()), List.of());
    assertThat(notSubmitted.ready()).isFalse();
    assertThat(notSubmitted.blockers()).containsExactly(NOT_APPROVED);

    var awaiting = CustomsAttributeRules.verdict(profile(DRAFT, "2026-10-08T02:00:00Z", List.of()), List.of());
    assertThat(awaiting.ready()).isFalse();
    assertThat(awaiting.blockers()).containsExactly(SUBMITTED_AWAITING_REVIEW);
  }

  @Test
  void 被驳回的判不可报关() {
    var result = CustomsAttributeRules.verdict(profile(REJECTED, "2026-10-08T02:00:00Z", List.of()), List.of());
    assertThat(result.ready()).isFalse();
    assertThat(result.blockers()).containsExactly(DECLARATION_REJECTED);
  }

  @Test
  void 只有审核通过且申报要素齐备才判可报关() {
    var complete =
        ordered(List.of(element(1, "01", "手机", true), element(2, "02", "自有品牌", true)));
    var ok = CustomsAttributeRules.verdict(profile(APPROVED, "2026-10-08T02:00:00Z", complete), complete);
    assertThat(ok.ready()).isTrue();
    assertThat(ok.blockers()).isEmpty();
  }

  @Test
  void 已审核通过但申报要素不齐仍然判不可报关() {
    // 审完之后把必填要素清空 —— 防御性判定：宁可拦住，也不要给下游一份残缺资料。
    var blank = ordered(List.of(element(1, "01", "手机", true), element(2, "02", "", true)));
    var missing = CustomsAttributeRules.verdict(profile(APPROVED, "2026-10-08T02:00:00Z", blank), blank);
    assertThat(missing.ready()).isFalse();
    assertThat(missing.blockers()).containsExactly(REQUIRED_ELEMENTS_INCOMPLETE);

    var none = List.<DeclarationElement>of();
    var empty = CustomsAttributeRules.verdict(profile(APPROVED, "2026-10-08T02:00:00Z", none), none);
    assertThat(empty.ready()).isFalse();
    assertThat(empty.blockers()).containsExactly(NO_ELEMENTS);
  }

  @Test
  void 阻断原因永远有中文标签且不为空串() {
    // 页面直接渲染 blockerLabels：空标签会让界面上出现一个没有解释的红标。
    for (String code :
        List.of(NOT_RECORDED, NOT_APPROVED, SUBMITTED_AWAITING_REVIEW, DECLARATION_REJECTED,
            NO_ELEMENTS, REQUIRED_ELEMENTS_INCOMPLETE)) {
      assertThat(blockerLabel(code)).isNotBlank();
      // 未知码原样返回而不是空串。
      assertThat(blockerLabel("SOME_FUTURE_CODE")).isEqualTo("SOME_FUTURE_CODE");
    }
  }

  // ============================================================ 状态与筛选归一

  @Test
  void 审核状态标签三档三词() {
    assertThat(statusLabel(null)).isEqualTo("未维护");
    assertThat(statusLabel(DRAFT)).isEqualTo("草稿");
    assertThat(statusLabel(APPROVED)).isEqualTo("已审核通过");
    assertThat(statusLabel(REJECTED)).isEqualTo("已驳回");
    assertThat(statusLabel("SOMETHING")).isEqualTo("SOMETHING");
  }

  @Test
  void 状态筛选归一拒绝非词表值而不是静默返回空集() {
    assertThat(normalizeStatus(null)).isNull();
    assertThat(normalizeStatus("  ")).isNull();
    assertThat(normalizeStatus("approved")).isEqualTo(APPROVED);
    assertThat(normalizeStatus(" REJECTED ")).isEqualTo(REJECTED);
    // 静默返回空集会让页面看起来像「没有结果」，而真相是「筛错了」。
    assertThatThrownBy(() -> normalizeStatus("PENDING")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 协定筛选的空串是有意义的筛选值不能被当成不筛() {
    // null = 不筛；"" = 筛「不适用任何协定」。两者必须能区分。
    assertThat(normalizeAgreementFilter(null)).isNull();
    assertThat(normalizeAgreementFilter("  ")).isEmpty();
    assertThat(normalizeAgreementFilter("rcep")).isEqualTo("RCEP");
  }

  @Test
  void 关键字归一去空格转小写且过长直接报错() {
    assertThat(normalizeKeyword(null)).isNull();
    assertThat(normalizeKeyword("   ")).isNull();
    assertThat(normalizeKeyword("  手机  ")).isEqualTo("手机");
    // 截断会搜出看不懂的结果集，所以报错而不是悄悄截。
    assertThatThrownBy(() -> normalizeKeyword("x".repeat(65))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void hs编码筛选不校验格式以便筛出脏数据() {
    // 与写入路径的差别是有意的：筛一个写坏的值应当返回空集而不是报错。
    assertThat(normalizeHsCodeFilter("  851712 ")).isEqualTo("851712");
    assertThat(normalizeHsCodeFilter("8517A2")).isEqualTo("8517A2");
    assertThat(normalizeHsCodeFilter("   ")).isNull();
    assertThat(normalizeHsCodeFilter(null)).isNull();
  }
}