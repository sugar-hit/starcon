package com.starcon.channel;

import static com.starcon.channel.ListingLifecycleStateMachine.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 4.1.1.3 七态状态机的单元测试（纯函数，不起容器）。
 *
 * <p>🔴 这个测试要钉住的是**状态机自己的纪律**，不是「代码跑起来了」：
 *
 * <ol>
 *   <li>每个动作在**每个**状态上的判定都有明确结论（要么允许，要么给出可读理由）——
 *       绝不出现「静默返回 null 但其实不允许」的情况。
 *   <li>每个动作的**合法起始状态**都能走到该动作的**目标状态**（结构表与动作表不许打架）。
 *   <li>没有状态可以迁回 DRAFT。
 *   <li>平台路径的宽松是**有边界的**：只认平台能表达的四态，且不许是自身。
 *   <li>拒绝理由**必须可读**：含当前状态与合法后继，不能是一句「非法」。
 * </ol>
 */
class ListingLifecycleStateMachineTest {

  @Test
  @DisplayName("4.1.1.3 动作的目标状态固定：调用方说了不算")
  void actionTargetsAreFixed() {
    assertThat(targetOf("DELIST")).isEqualTo("DELISTED_BY_US");
    assertThat(targetOf("REPUBLISH")).isEqualTo("ON_SALE");
    assertThat(targetOf("SUBMIT_REVIEW")).isEqualTo("UNDER_REVIEW");
    assertThat(targetOf("MARK_BLOCKED")).isEqualTo("BLOCKED");
  }

  @Test
  @DisplayName("4.1.1.3 状态机：每个动作 × 每个状态都有明确结论（合法或给出可读理由）")
  void everyActionOnEveryStateHasAVerdict() {
    for (String action : ACTION_TARGET.keySet()) {
      for (String status : ListingLifecycleModels.LIFECYCLE_STATUSES) {
        String rejection = actionRejection(action, status);
        if (ACTION_FROM.get(action).contains(status)) {
          assertThat(rejection)
              .as("动作 %s 在状态 %s 上应当被允许", action, status)
              .isNull();
        } else {
          assertThat(rejection)
              .as("动作 %s 在状态 %s 上应当被拒绝并给出理由", action, status)
              .isNotNull()
              .isNotBlank();
          // 🔴 拒绝理由必须写明当前状态 —— 运营要知道「现在是什么样」
          assertThat(rejection).contains(status);
        }
      }
    }
  }

  @Test
  @DisplayName("4.1.1.3 动作表与结构迁移表不许打架：合法起始状态都能走到目标状态")
  void actionFromSetsAgreeWithTransitionTable() {
    for (String action : ACTION_TARGET.keySet()) {
      String target = ACTION_TARGET.get(action);
      for (String from : ACTION_FROM.get(action)) {
        assertThat(allows(from, target))
            .as("动作 %s：%s → %s 必须是结构上成立的迁移", action, from, target)
            .isTrue();
      }
    }
  }

  @Test
  @DisplayName("4.1.1.3 没有状态可以迁回「草稿」—— 草稿是登记时的初始态")
  void draftIsNotReachable() {
    for (String from : ListingLifecycleModels.LIFECYCLE_STATUSES) {
      assertThat(allows(from, "DRAFT"))
          .as("%s 不该能迁回 DRAFT", from)
          .isFalse();
    }
  }

  @Test
  @DisplayName("4.1.1.3 重复下架被显式拒绝，理由可读")
  void repeatDelistIsRejectedWithReadableReason() {
    String rejection = actionRejection("DELIST", "DELISTED_BY_US");
    assertThat(rejection).isNotNull().contains("主动下架");
  }

  @Test
  @DisplayName("4.1.1.3 在售 / 审核中 不能「重新上架」：理由要指出为什么")
  void republishRejectionExplainsWhy() {
    assertThat(actionRejection("REPUBLISH", "ON_SALE"))
        .isNotNull()
        .contains("已经在售");
    assertThat(actionRejection("REPUBLISH", "UNDER_REVIEW"))
        .isNotNull()
        .contains("平台");
  }

  @Test
  @DisplayName("4.1.1.3 平台路径只认平台能表达的四态；受限类事件不改变内部状态")
  void platformTargetsAreBounded() {
    assertThat(platformTarget("APPROVED")).isEqualTo("ON_SALE");
    assertThat(platformTarget("REJECTED")).isEqualTo("REJECTED");
    assertThat(platformTarget("DELISTED")).isEqualTo("DELISTED_BY_PLATFORM");
    assertThat(platformTarget("SUPPRESSED")).isEqualTo("DELISTED_BY_PLATFORM");
    // 🔴 限流 / 类目审核 / 价格异常：商品仍在售，平台只是加了限制 ⇒ 内部状态不变
    assertThat(platformTarget("THROTTLED")).isNull();
    assertThat(platformTarget("CATEGORY_REVIEW")).isNull();
    assertThat(platformTarget("PRICE_ALERT")).isNull();
    assertThat(platformTarget("NOT_A_PLATFORM_EVENT")).isNull();
  }

  @Test
  @DisplayName("4.1.1.3 平台路径：自身状态被显式拒绝（没有变化的「同步」不成立）")
  void platformNoOpIsRejected() {
    String rejection = platformRejection("DELISTED", "DELISTED_BY_PLATFORM");
    assertThat(rejection).isNotNull().contains("DELISTED_BY_PLATFORM");
  }

  @Test
  @DisplayName("4.1.1.3 平台路径：未知事件类型被显式拒绝并列出全部取值")
  void platformUnknownEventTypeIsRejected() {
    String rejection = platformRejection("DELIST", "ON_SALE");
    assertThat(rejection).isNotNull().contains("未知的平台事件类型");
    assertThat(rejection).contains("DELISTED");
  }

  @Test
  @DisplayName("4.1.1.3 平台路径比人工路径宽松：平台能说的事实不该被状态机挡回去")
  void platformPathIsLooserThanManualPath() {
    // 平台说「已下架」，而内部还挂着「草稿」：人工路径不认这个迁移，
    // 平台路径**必须**认 —— 否则就是蓝图点名的漏单。
    assertThat(actionRejection("DELIST", "DRAFT")).isNull();
    assertThat(platformRejection("DELISTED", "DRAFT")).isNull();
    assertThat(platformTarget("DELISTED")).isEqualTo("DELISTED_BY_PLATFORM");
  }

  @Test
  @DisplayName("4.1.1.3 允许的动作清单随状态变化（页面据此渲染按钮）")
  void allowedActionsDependOnState() {
    assertThat(allowedActions("ON_SALE"))
        .contains("DELIST", "SUBMIT_REVIEW", "MARK_BLOCKED")
        .doesNotContain("REPUBLISH");
    assertThat(allowedActions("DELISTED_BY_US"))
        .contains("REPUBLISH", "SUBMIT_REVIEW", "MARK_BLOCKED")
        .doesNotContain("DELIST");
    assertThat(allowedActions("UNDER_REVIEW"))
        .contains("DELIST", "MARK_BLOCKED")
        .doesNotContain("REPUBLISH", "SUBMIT_REVIEW");
  }

  @Test
  @DisplayName("4.1.1.3 状态机规则说明要含关键约束（前端原样显示，不能只给一句话）")
  void ruleTextCarriesTheConstraints() {
    String rule = rule();
    assertThat(rule).contains("DRAFT").contains("DELISTED_BY_PLATFORM");
    assertThat(rule).contains("没有状态可以迁回");
    assertThat(rule).contains("限流");
  }
}
