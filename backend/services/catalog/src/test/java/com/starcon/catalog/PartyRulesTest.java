package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.PartyRoleInput;
import static com.starcon.catalog.PartyRules.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 3.6.1.1 往来单位档案的纯规则单测。
 *
 * <p>离线可穷举，所以 {@code V27__party_profile.sql} 第六节钉死的 P1~P7 七条语义 在这里逐条钉一遍 ——
 * 它们与迁移文件的文字是同一份承诺，两处对不上就说明有一处被改松了。
 *
 * <p>重点覆盖三类容易漏的东西：
 *
 * <ul>
 *   <li><b>状态流转表的方向性</b>：自环必须被拒（否则静默 +1 版本号骗过乐观锁）；
 *   <li><b>「进出拉黑」而不是「进入拉黑」</b>：解除拉黑也要理由；
 *   <li><b>可用性判定的优先级</b>：已删除的主体不能报「没有角色」—— 那会让运营 去解指派角色，而真正要做的是恢复删除。
 * </ul>
 */
class PartyRulesTest {

  // ============================================================ P1 编码

  @Test
  void codeIsNormalizedToUpperCaseAndRejectsNonAscii() {
    assertThat(normalizeCode("  sup-acme  ")).isEqualTo("SUP-ACME");
    assertThat(codeProblem("acme")).isNull();
    assertThat(codeProblem(" SUP_01 ")).isNull();
    assertThat(codeProblem("")).contains("不能为空");
    assertThat(codeProblem(null)).contains("不能为空");
    assertThat(codeProblem("中文编码")).contains("英文字母");
    assertThat(codeProblem("-LEADING")).contains("英文字母");
    assertThat(codeProblem("A".repeat(65))).contains("最多 64");
  }

  @Test
  void codeIsImmutableButWhitespaceAndCaseDoNotCountAsChange() {
    // 规范化之后相同就不是「改编码」—— 否则前端把 " sup-acme " 传上来会被误拒。
    assertThat(codeChangeProblem("SUP-ACME", " sup-acme ")).isNull();
    assertThat(codeChangeProblem("SUP-ACME", "SUP-ACME")).isNull();
    assertThat(codeChangeProblem("SUP-ACME", "SUP-OTTO")).contains("建后不可修改");
  }

  // ============================================================ P2 名称与类型

  @Test
  void nameAndPartyTypeChecksMatchColumnWidths() {
    assertThat(normalizeName("  星尘科技 ")).isEqualTo("星尘科技");
    assertThat(nameProblem("星尘科技")).isNull();
    assertThat(nameProblem("   ")).contains("不能为空");
    // 200 列是给企业法定名称留的（Co., Ltd / GmbH 那种长后缀）。
    assertThat(nameProblem("Acme".repeat(51))).contains("最多 200");

    assertThat(partyTypeProblem("ORGANIZATION")).isNull();
    assertThat(partyTypeProblem("INDIVIDUAL")).isNull();
    assertThat(partyTypeProblem("")).contains("不能为空");
    assertThat(partyTypeProblem("COMPANY")).contains("ORGANIZATION");
    // 类型不接受大小写混写以外的变体：它是下游开票与收件逻辑的分叉点，不能自由文本。
    assertThat(partyTypeProblem("organization")).contains("ORGANIZATION");
  }

  @Test
  void countryIsOptionalButMustBeIsoAlpha2WhenPresent() {
    assertThat(countryProblem(null)).isNull();
    assertThat(countryProblem("  ")).isNull();
    assertThat(countryProblem("CN")).isNull();
    assertThat(countryProblem(" cn ")).isNull();
    assertThat(countryProblem("CHN")).contains("ISO 3166-1");
    assertThat(countryProblem("C1")).contains("ISO 3166-1");
    assertThat(normalizeCountry(" cn ")).isEqualTo("CN");
    assertThat(normalizeCountry(" ")).isNull();
  }

  // ============================================================ P3 联系方式

  @Test
  void contactRequiresAtLeastOneOfEmailOrPhone() {
    // 两者都没有 = 在 06 采购 / 11 售后 / 12 财务三处都用不了，建档就该被拒。
    assertThat(contactProblem(null, null, null)).contains("至少填写一项");
    assertThat(contactProblem("", "  ", "https://acme.example")).contains("至少填写一项");
    assertThat(contactProblem("purchasing@acme.example", null, null)).isNull();
    assertThat(contactProblem(null, "+86-138-0000-0000", null)).isNull();
    // 官网不能替代邮箱 / 电话 —— 官网是「查规格」用的，联系不上人对履约没有帮助。
    assertThat(contactProblem(null, null, "https://acme.example")).contains("至少填写一项");
  }

  @Test
  void emailRejectsTheTwoRealMistakes() {
    assertThat(emailProblem(null)).isNull();
    assertThat(emailProblem("purchasing@acme.example")).isNull();
    assertThat(emailProblem("purchasing@acme.co.uk")).isNull();
    // 漏打 @：跨境邮件里极常见，而它不会当场报错，要到 12 财务对账那天才暴露。
    assertThat(emailProblem("purchasing.acme.example")).contains("格式不正确");
    // 一次粘两个邮箱：整串都发不出去。
    assertThat(emailProblem("a@acme.com,b@acme.com")).contains("格式不正确");
    assertThat(emailProblem("purchasing @acme.com")).contains("不能包含空格");
    assertThat(emailProblem("a@b.c".repeat(90))).contains("最多 254");
  }

  @Test
  void phoneAndWebsiteRejectUnencodedSpaces() {
    assertThat(phoneProblem(null)).isNull();
    assertThat(phoneProblem("+86-138-0000-0000")).isNull();
    assertThat(phoneProblem("+86 (138) 0000-0000")).isNull();
    // 空格是<b>规范化掉</b>而不是拒绝：+86 (138) 0000-0000 是 B2B 最常见的写法之一。
    assertThat(phoneProblem("+86 138 0000 0000")).isNull();
    assertThat(normalizePhone(" +86 (138) 0000-0000 ")).isEqualTo("+86(138)0000-0000");
    assertThat(phoneProblem("1380000转123")).contains("只能包含数字");
    assertThat(phoneProblem("1".repeat(65))).contains("最多 64");

    assertThat(websiteProblem(null)).isNull();
    assertThat(websiteProblem("https://acme.example")).isNull();
    assertThat(websiteProblem("acme example com")).contains("不能包含空格");
    assertThat(websiteProblem("x".repeat(501))).contains("最多 500");
  }

  // ============================================================ P4 状态流转

  @Test
  void statusVocabularyIsClosed() {
    assertThat(statusProblem("ACTIVE")).isNull();
    assertThat(statusProblem("INACTIVE")).isNull();
    assertThat(statusProblem("BLACKLISTED")).isNull();
    assertThat(statusProblem("")).contains("不能为空");
    assertThat(statusProblem("BLOCKED")).contains("ACTIVE");
    assertThat(statusLabel("ACTIVE")).isEqualTo("可用");
    assertThat(statusLabel("INACTIVE")).isEqualTo("停用");
    assertThat(statusLabel("BLACKLISTED")).isEqualTo("拉黑");
    assertThat(statusLabel(null)).isEqualTo("未知");
  }

  @Test
  void selfTransitionIsRejectedInsteadOfSilentlyBumpingVersion() {
    // 「停用 → 停用」被拒：静默 +1 版本号会让乐观锁看起来工作正常，而实际上什么都没改。
    assertThat(transitionProblem("ACTIVE", "ACTIVE", "")).contains("状态没有变化");
    assertThat(transitionProblem("INACTIVE", "INACTIVE", "")).contains("状态没有变化");
    assertThat(transitionProblem("BLACKLISTED", "BLACKLISTED", "有理由也不算数")).contains("状态没有变化");
    assertThat(canTransition("ACTIVE", "ACTIVE")).isFalse();
  }

  @Test
  void allowedTransitionsMatchTheTable() {
    assertThat(canTransition("ACTIVE", "INACTIVE")).isTrue();
    assertThat(canTransition("ACTIVE", "BLACKLISTED")).isTrue();
    assertThat(canTransition("INACTIVE", "ACTIVE")).isTrue();
    assertThat(canTransition("INACTIVE", "BLACKLISTED")).isTrue();
    // 拉黑可以先转停用，也可以直接解除拉黑 —— 两个动作在业务上含义不同，不能合成一步。
    assertThat(canTransition("BLACKLISTED", "INACTIVE")).isTrue();
    assertThat(canTransition("BLACKLISTED", "ACTIVE")).isTrue();
    assertThat(allowedTargets("BLACKLISTED")).containsExactlyInAnyOrder("INACTIVE", "ACTIVE");
    assertThat(allowedTargets("ACTIVE")).containsExactlyInAnyOrder("INACTIVE", "BLACKLISTED");
  }

  @Test
  void enteringAndLeavingTheBlacklistBothRequireAReason() {
    // 进入拉黑没理由：三个月后没人知道该不该解开。
    assertThat(transitionProblem("ACTIVE", "BLACKLISTED", "")).contains("必须填写理由");
    assertThat(transitionProblem("INACTIVE", "BLACKLISTED", "   ")).contains("必须填写理由");
    // 「进出」而不是「进入」：制裁解除通常需要留档，而只进不出的写法会让
    // 「已从名单移除」这个事实无处可查。
    assertThat(transitionProblem("BLACKLISTED", "ACTIVE", "")).contains("必须填写理由");
    assertThat(transitionProblem("BLACKLISTED", "INACTIVE", null)).contains("必须填写理由");
    assertThat(requiresReason("ACTIVE", "BLACKLISTED")).isTrue();
    assertThat(requiresReason("BLACKLISTED", "ACTIVE")).isTrue();
    assertThat(requiresReason("ACTIVE", "INACTIVE")).isFalse();
    assertThat(transitionHint("ACTIVE", "BLACKLISTED")).contains("理由");
    assertThat(transitionHint("ACTIVE", "INACTIVE")).isNull();
  }

  @Test
  void ordinaryTransitionsNeedNoReasonButDirectionIsStillChecked() {
    assertThat(transitionProblem("ACTIVE", "INACTIVE", "")).isNull();
    assertThat(transitionProblem("INACTIVE", "ACTIVE", "")).isNull();
    assertThat(transitionProblem(null, "ACTIVE", "")).isNull();
    // 不存在的状态：报出来的是「当前状态异常」而不是 IllegalArgumentException。
    assertThat(transitionProblem("WAT", "ACTIVE", "")).contains("当前主体状态异常");
    assertThat(transitionProblem("ACTIVE", "WAT", "")).contains("状态只能是");
  }

  @Test
  void reasonLengthIsBounded() {
    assertThat(transitionProblem("ACTIVE", "BLACKLISTED", "x".repeat(501))).contains("最多 500");
  }

  // ============================================================ P5 / P6 角色指派

  @Test
  void roleVocabularyIsClosedAndUpperCased() {
    assertThat(normalizeRoleType(" supplier ")).isEqualTo("SUPPLIER");
    assertThat(roleTypeProblem("SUPPLIER")).isNull();
    assertThat(roleTypeProblem("customer")).isNull();
    assertThat(roleTypeProblem("CARRIER")).isNull();
    assertThat(roleTypeProblem("DISTRIBUTOR")).contains("SUPPLIER");
    assertThat(roleTypeProblem("")).contains("不能为空");
    assertThat(roleStatusProblem("ACTIVE")).isNull();
    assertThat(roleStatusProblem("BLACKLISTED")).contains("ACTIVE");
    assertThat(roleNoteProblem(null)).isNull();
    assertThat(roleNoteProblem("x".repeat(2001))).contains("最多 2000");
    assertThat(roleLabel("SUPPLIER")).isEqualTo("供应商");
    assertThat(roleLabel("CUSTOMER")).isEqualTo("客户");
    assertThat(roleLabel("CARRIER")).isEqualTo("承运商");
  }

  @Test
  void oneRoleTypeCanOnlyBeAssignedOncePerParty() {
    // 蓝图「同一主体可同时是供应商和客户，用角色档案区分，不用多份记录」在输入侧的口径。
    var both =
        List.of(
            new PartyRoleInput("SUPPLIER", "ACTIVE", ""),
            new PartyRoleInput("CUSTOMER", "ACTIVE", ""));
    assertThat(rolesProblem(both)).isNull();
    assertThat(
            rolesProblem(
                List.of(
                    new PartyRoleInput("SUPPLIER", "ACTIVE", ""),
                    new PartyRoleInput("supplier", "ACTIVE", ""))))
        .contains("同一个角色只能指派一次");
    assertThat(rolesProblem(null)).isNull();
    assertThat(rolesProblem(List.of())).isNull();
    assertThat(
            rolesProblem(
                java.util.stream.IntStream.range(0, 11)
                    .mapToObj(i -> new PartyRoleInput("SUPPLIER", "ACTIVE", ""))
                    .toList()))
        .contains("最多指派");
  }

  @Test
  void assignIsBlockedOnlyOnBlacklistedParties() {
    // P6 只拦**拉黑**：拉黑 = 不与这个对手方做生意，给它新增商业身份正是要拦的动作。
    assertThat(assignRoleProblem("BLACKLISTED", "SUPPLIER", false)).contains("拉黑名单");
    // 停用**允许**新增角色 —— 规则 P5 的正常流程「先建主体、挂好角色、再启用」就依赖这一点；
    // 一并拦掉的话「先建后指派」这个真实业务流就走不通了。
    assertThat(assignRoleProblem("INACTIVE", "SUPPLIER", false)).isNull();
    assertThat(assignRoleProblem("ACTIVE", "SUPPLIER", false)).isNull();
    // 重复指派要说清是重复，而不是笼统的「保存失败」。
    assertThat(assignRoleProblem("ACTIVE", "SUPPLIER", true)).contains("已经指派过");
    assertThat(assignRoleProblem("WAT", "SUPPLIER", false)).contains("状态只能是");
  }

  @Test
  void activatingRequiresAtLeastOneActiveRole() {
    // P5：被拒的应该是「让它在下游可用」，不是「建档」。
    assertThat(activateRoleRequirementProblem("ACTIVE", 1)).isNull();
    assertThat(activateRoleRequirementProblem("ACTIVE", 0)).contains("至少要指派一个角色");
    // 非 ACTIVE 目标不检查：停用 / 拉黑不需要角色。
    assertThat(activateRoleRequirementProblem("INACTIVE", 0)).isNull();
    assertThat(activateRoleRequirementProblem("BLACKLISTED", 0)).isNull();
  }

  @Test
  void deleteNamesEveryRoleThatStillHangs() {
    // P7：报错要逐个点名，否则运营不知道自己还要解几个。
    assertThat(deleteProblem(List.of())).isNull();
    assertThat(deleteProblem(null)).isNull();
    assertThat(deleteProblem(List.of("SUPPLIER"))).contains("供应商");
    assertThat(deleteProblem(List.of("SUPPLIER", "CUSTOMER", "CARRIER")))
        .contains("供应商、客户、承运商")
        .contains("请先解除");
  }

  // ============================================================ 角色展示

  @Test
  void roleSummaryUsesAFixedOrderSoTheUiDoesNotJitter() {
    // 顺序固定，避免同一组角色在不同查询下顺序不同而让前端 key 抖动。
    assertThat(roleSummary(List.of("CARRIER", "SUPPLIER"))).isEqualTo("供应商、承运商");
    assertThat(roleSummary(List.of("CUSTOMER", "SUPPLIER"))).isEqualTo("供应商、客户");
    assertThat(roleSummary(List.of())).isEqualTo("未指派角色");
    assertThat(roleSummary(null)).isEqualTo("未指派角色");
    assertThat(roleSummary(List.of("SUPPLIER", "CUSTOMER", "CARRIER"))).isEqualTo("供应商、客户、承运商");
  }

  // ============================================================ 可用性判定

  @Test
  void usableBlockFollowsThePriorityOrder() {
    // 已删除 > 拉黑 > 停用 > 没角色。顺序本身就是承诺：
    // 对一个已删除的主体报「没有角色」是误导（解指派角色并不能让它回来）。
    assertThat(usableBlock(new UsableInputs(false, "ACTIVE", 1))).isNull();
    assertThat(usableBlock(new UsableInputs(false, "ACTIVE", 0))).isEqualTo(BLOCK_PARTY_NO_ROLE);
    assertThat(usableBlock(new UsableInputs(false, "INACTIVE", 3))).isEqualTo(BLOCK_PARTY_INACTIVE);
    assertThat(usableBlock(new UsableInputs(false, "BLACKLISTED", 3)))
        .isEqualTo(BLOCK_PARTY_BLACKLISTED);
    // 拉黑优先于没角色：已删除优先于一切。
    assertThat(usableBlock(new UsableInputs(true, "ACTIVE", 0))).isEqualTo(BLOCK_PARTY_DELETED);
    assertThat(usableBlock(new UsableInputs(true, "BLACKLISTED", 0)))
        .isEqualTo(BLOCK_PARTY_DELETED);
  }

  @Test
  void everyBlockCodeHasItsOwnLabelAndAction() {
    // 四种原因对应四种处理动作，不能都写成「不可用」—— 运营看完不知道该做什么。
    var codes =
        List.of(
            BLOCK_PARTY_INACTIVE,
            BLOCK_PARTY_BLACKLISTED,
            BLOCK_PARTY_NO_ROLE,
            BLOCK_PARTY_DELETED);
    var labels = codes.stream().map(PartyRules::usableBlockLabel).toList();
    var actions = codes.stream().map(code -> usableBlockMessage(code, "Acme")).toList();
    assertThat(labels).doesNotHaveDuplicates();
    assertThat(actions).doesNotHaveDuplicates();
    assertThat(usableBlockLabel(null)).isEqualTo("可用");
    assertThat(usableBlockMessage(null, "Acme")).contains("可用于新单据");
    assertThat(usableBlockMessage("UNKNOWN_CODE", "Acme")).isNotBlank();
    // 消息里带上主体名：运营在一列消息里就能认出说的是谁。
    assertThat(usableBlockMessage(BLOCK_PARTY_NO_ROLE, "Acme")).contains("Acme");
  }
}
