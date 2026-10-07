package com.starcon.catalog;

import static com.starcon.catalog.BrandRules.*;
import static com.starcon.catalog.CatalogModels.BrandNameInput;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 3.3.2.1 的纯规则单测：编码 / 名称 / 授权状态 / Logo / locale 的规范化与校验，
 * 凭证有效期判定，以及蓝图点名的**阻止刊登判定**四分支。
 *
 * <p>离线可穷举，所以阻止刊登的每一条路径都在这里穷举一遍——
 * 那条规则是本模块唯一会让「已经排好的货发不出去」的判定，
 * 它必须比集成测试更好读地铺开全部组合。
 */
class BrandRulesTest {

  @Test
  void codeIsNormalizedToUpperCaseAndRejectsNonAscii() {
    assertThat(normalizeCode("  brand-x ")).isEqualTo("BRAND-X");
    assertThat(codeProblem("acme")).isNull();
    assertThat(codeProblem(" ACME_01 ")).isNull();
    assertThat(codeProblem("")).contains("不能为空");
    assertThat(codeProblem("中文编码")).contains("英文字母");
    assertThat(codeProblem("-LEADING")).contains("英文字母");
    assertThat(codeProblem("A".repeat(65))).contains("最多 64");
  }

  @Test
  void nameAndLogoChecksMatchColumnWidths() {
    assertThat(normalizeName("  星尘 ")).isEqualTo("星尘");
    assertThat(nameProblem("星尘")).isNull();
    assertThat(nameProblem("   ")).contains("不能为空");
    assertThat(nameProblem("品".repeat(101))).contains("最多 100");
    assertThat(logoProblem(null)).isNull();
    assertThat(logoProblem("")).isNull();
    assertThat(logoProblem("logos/a.png")).isNull();
    assertThat(logoProblem("logos/my brand.png")).contains("不能包含空格");
    assertThat(logoProblem("x".repeat(501))).contains("最多 500");
  }

  @Test
  void authorizationStatusVocabularyIsClosed() {
    assertThat(authorizationStatusProblem("OWNED")).isNull();
    assertThat(authorizationStatusProblem("AGENCY")).isNull();
    assertThat(authorizationStatusProblem("LICENSED")).isNull();
    assertThat(authorizationStatusProblem(null)).contains("不能为空");
    assertThat(authorizationStatusProblem("BORROWED")).contains("OWNED（自有）");
    // 授权状态只 trim 不改大小写：它是大写下枚举，词表校验负责把非枚举值拒掉。
    assertThat(normalizeStatus(" LICENSED ")).isEqualTo("LICENSED");
    assertThat(authorizationStatusProblem(" licensed ")).contains("OWNED（自有）");
  }

  @Test
  void localeNamesAreNormalizedAndDeduplicated() {
    assertThat(normalizeLocale(" ZH-CN ")).isEqualTo("zh-cn");
    assertThat(localeNameProblem("zh-CN", "星尘")).isNull();
    assertThat(localeNameProblem("ja", "ほしだね")).isNull();
    assertThat(localeNameProblem("", "名字")).contains("语言标识不能为空");
    assertThat(localeNameProblem("zh_CN", "名字")).contains("BCP 47");
    assertThat(localeNameProblem("zh-CN", " ")).contains("内容不能为空");
    assertThat(namesProblem(null)).isNull();
    assertThat(namesProblem(List.of())).isNull();
    // zh-CN 与 zh-cn 规范化后是同一个语言：不允许两条各存一份。
    assertThat(namesProblem(List.of(new BrandNameInput("zh-CN", "甲"), new BrandNameInput("zh-cn", "乙"))))
        .contains("同一个语言只能有一个品牌名");
    assertThat(namesProblem(java.util.stream.IntStream.range(0, 21)
            .mapToObj(i -> new BrandNameInput("l" + i, "名字" + i))
            .toList()))
        .contains("最多配置 20 个语言名");
  }

  @Test
  void credentialValidityRequiresEnabledInsideWindow() {
    LocalDate from = LocalDate.of(2026, 1, 10);
    LocalDate to = LocalDate.of(2026, 1, 20);
    LocalDate inside = LocalDate.of(2026, 1, 15);
    assertThat(validOn(true, from, to, inside)).isTrue();
    assertThat(validOn(true, from, to, from)).isTrue();
    assertThat(validOn(true, from, to, to)).isTrue();
    assertThat(validOn(true, from, to, from.minusDays(1))).isFalse();
    assertThat(validOn(true, from, to, to.plusDays(1))).isFalse();
    // 撤销的凭证一律无效（行还在，可查「曾经有过、后来撤销」）。
    assertThat(validOn(false, from, to, inside)).isFalse();
    // validTo 为空是长期有效，不是漏填。
    assertThat(validOn(true, from, null, inside)).isTrue();
    assertThat(validOn(true, null, to, inside)).isFalse();
    assertThat(validOn(true, from, to, null)).isFalse();

    assertThat(certificateNoProblem("CERT-1")).isNull();
    assertThat(certificateNoProblem(" ")).contains("不能为空");
    assertThat(grantorProblem("品牌权利人")).isNull();
    assertThat(grantorProblem("")).contains("不能为空");
    assertThat(validityProblem(from, to)).isNull();
    assertThat(validityProblem(from, null)).isNull();
    assertThat(validityProblem(null, to)).contains("生效日期不能为空");
    assertThat(validityProblem(to, from)).contains("不能早于生效日期");
    assertThat(evidenceProblem(null)).isNull();
    assertThat(evidenceProblem("evidence/a.pdf")).isNull();
  }

  // ------------------------------------------------------------ 阻止刊登

  private static BrandRules.PublishInputs inputs(
      boolean enabled, String status, LocalDate asOf, List<BrandRules.Credential> credentials) {
    return new BrandRules.PublishInputs(enabled, status, credentials, asOf);
  }

  private static BrandRules.Credential credential(LocalDate from, LocalDate to) {
    return new BrandRules.Credential(true, from, to);
  }

  @Test
  void ownedAndAgencyPublishWithoutAnyCredential() {
    LocalDate asOf = LocalDate.of(2026, 6, 1);
    assertThat(publishBlock(inputs(true, "OWNED", asOf, List.of()))).isNull();
    assertThat(publishBlock(inputs(true, "AGENCY", asOf, List.of()))).isNull();
    // 给了反而过期的凭证也不影响：自有/代理本身已是「有授权」的状态。
    assertThat(publishBlock(inputs(true, "OWNED", asOf,
        List.of(credential(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1)))))).isNull();
  }

  @Test
  void disabledBrandIsBlockedRegardlessOfAuthorization() {
    LocalDate asOf = LocalDate.of(2026, 6, 1);
    assertThat(publishBlock(inputs(false, "OWNED", asOf, List.of())))
        .isEqualTo(BLOCK_BRAND_DISABLED);
    assertThat(publishBlock(inputs(false, "LICENSED", asOf,
        List.of(credential(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1))))))
        .isEqualTo(BLOCK_BRAND_DISABLED);
    // 停用的品牌优先于凭证判断：即使凭证有效也不许刊登。
  }

  @Test
  void licensedBrandNeedsAtLeastOneValidCredential() {
    LocalDate asOf = LocalDate.of(2026, 6, 1);
    assertThat(publishBlock(inputs(true, "LICENSED", asOf, List.of())))
        .isEqualTo(BLOCK_LICENSE_MISSING);
    assertThat(publishBlock(inputs(true, "LICENSED", asOf,
        List.of(credential(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))))).isNull();
    // 续签场景：旧凭证已过期 + 新凭证有效 → 放行（旧行留在库里供回溯）。
    assertThat(publishBlock(inputs(true, "LICENSED", asOf,
        List.of(
            credential(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1)),
            credential(LocalDate.of(2026, 3, 1), null))))).isNull();
  }

  @Test
  void licensedBrandDistinguishesNotYetEffectiveFromExpired() {
    LocalDate asOf = LocalDate.of(2026, 6, 1);
    assertThat(publishBlock(inputs(true, "LICENSED", asOf,
        List.of(credential(LocalDate.of(2026, 7, 1), LocalDate.of(2027, 1, 1))))))
        .isEqualTo(BLOCK_LICENSE_NOT_YET_EFFECTIVE);
    assertThat(publishBlock(inputs(true, "LICENSED", asOf,
        List.of(credential(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))))))
        .isEqualTo(BLOCK_LICENSE_EXPIRED);
    // 「一张已过期 + 一张还没到」按未生效处理：等一等就能刊登，续约动作要等它真的过期。
    assertThat(publishBlock(inputs(true, "LICENSED", asOf,
        List.of(
            credential(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)),
            credential(LocalDate.of(2026, 7, 1), null)))))
        .isEqualTo(BLOCK_LICENSE_NOT_YET_EFFECTIVE);
    // 被撤销的凭证不参与判定：撤销了就等于没有。
    assertThat(publishBlock(inputs(true, "LICENSED", asOf,
        List.of(new BrandRules.Credential(false, LocalDate.of(2026, 1, 1), null)))))
        .isEqualTo(BLOCK_LICENSE_EXPIRED);
    assertThat(publishBlock(inputs(true, "LICENSED", asOf, null)))
        .isEqualTo(BLOCK_LICENSE_MISSING);
  }

  @Test
  void blockMessagesAndLabelsCoverEveryCode() {
    assertThat(blockMessage(null, "星尘")).isNull();
    assertThat(blockMessage(BLOCK_BRAND_DISABLED, "星尘")).contains("已停用");
    assertThat(blockMessage(BLOCK_LICENSE_MISSING, "星尘")).contains("没有任何授权凭证");
    assertThat(blockMessage(BLOCK_LICENSE_NOT_YET_EFFECTIVE, "星尘")).contains("尚未到生效日期");
    assertThat(blockMessage(BLOCK_LICENSE_EXPIRED, "星尘")).contains("已全部过期");
    // 品牌名为空时降级成「该品牌」，不留「品牌【】」这种半截话。
    assertThat(blockMessage(BLOCK_LICENSE_MISSING, "  ")).contains("该品牌");
    assertThat(blockLabel(null)).isEqualTo("可刊登");
    assertThat(blockLabel(BLOCK_BRAND_DISABLED)).isEqualTo("品牌停用");
    assertThat(blockLabel(BLOCK_LICENSE_MISSING)).isEqualTo("缺少凭证");
    assertThat(blockLabel(BLOCK_LICENSE_NOT_YET_EFFECTIVE)).isEqualTo("凭证未生效");
    assertThat(blockLabel(BLOCK_LICENSE_EXPIRED)).isEqualTo("凭证已过期");
  }
}