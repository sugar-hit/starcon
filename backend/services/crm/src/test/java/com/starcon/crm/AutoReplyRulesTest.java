package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;

import com.starcon.crm.AutoReplyModels.CrmAutoReplyDutyMode;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyDutyScope;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyEscalationReason;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyOutcome;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyTriggerType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 15.5.1.1 自动回复的<b>纯逻辑</b>守卫（不碰数据库）。
 *
 * <p>本类钉住四组<b>口径词表</b> —— 它们是加粗句「自动回复必须可一键关闭并留出人工接管」
 * 与职责句「按触发条件（收到消息分类、关键词、无人工介入超时）自动发送回复」的**口径本身**，
 * 一旦被人悄悄放宽或加档，闸门就名存实亡：
 *
 * <ul>
 *   <li><b>触发条件三选一</b>：分类 / 关键词 / 无人工介入超时。
 *       互斥是<b>可复现性</b>的前提 —— 同时写两种，「命中口径」就没法复现了。</li>
 *   <li><b>记录结果只有三档</b>：已登记发送 / 被闸门挡下 / 已撤销。
 *       「转人工」是<b>另一件输出</b>（转人工标记），不是记录的结果 ——
 *       把两者混在一起，「这条到底发没发出去」就答不清了。</li>
 *   <li><b>转人工原因只有四档</b>：阶梯走完 / 值班闸挡下 / 发送登记失败 / 人工手动标记 ——
 *       正好对应四个真实来源，没有第五个「其它」。</li>
 *   <li><b>分类标签沿用上游 15.1.1.3 的词表</b>：本模块<b>不另造</b>平行分类词表；
 *       两份词表必然打架，而打架的方向往往是「自动回复按自己的分类发，
 *       而分类页显示的是另一套」。</li>
 * </ul>
 *
 * <p>⚠️ 本类**不**重复验证数据库层的行为（那些在
 * {@link AutoReplyIntegrationTest} 与 {@link AutoReplyTenancyIntegrationTest} 里
 * 跑真实 PostgreSQL 16）。这里只钉「口径」，避免同一个语义在两处各写一遍。
 */
class AutoReplyRulesTest {

  @Test
  void 触发条件恰好三选一且互斥() {
    assertThat(AutoReplyService.TRIGGER_TYPES)
        .as("触发条件是蓝图点名的三种：分类 / 关键词 / 无人工介入超时")
        .containsExactlyInAnyOrder("CLASSIFICATION", "KEYWORD", "HUMAN_TIMEOUT");
    assertThat(AutoReplyService.TRIGGER_TYPES).hasSize(3);
    assertThat(CrmAutoReplyTriggerType.values())
        .as("枚举与词表必须一致，否则契约与实现会各说一套")
        .hasSize(AutoReplyService.TRIGGER_TYPES.size());
  }

  @Test
  void 分类标签沿用上游1511的词表_不另造平行分类() {
    assertThat(AutoReplyService.CLASSIFICATION_LABELS)
        .as("标签必须与 V301 的表级 CHECK 同一份：不另造平行分类表")
        .containsExactlyInAnyOrder(
            "PRE_SALE",
            "LOGISTICS",
            "RETURN_REFUND",
            "COMPLAINT",
            "PLATFORM_COMPLIANCE",
            "SPAM");
    assertThat(AutoReplyService.CLASSIFICATION_LABELS)
        .as("不含兜底的 UNCLASSIFIED：未分类的消息一律不可自动回复")
        .doesNotContain("UNCLASSIFIED");
  }

  @Test
  void 记录结果只有三档_转人工是另一件输出() {
    assertThat(CrmAutoReplyOutcome.values())
        .as("已登记发送 / 被闸门挡下 / 已撤销 —— 「转人工」是转人工标记，不是记录结果")
        .containsExactlyInAnyOrder(
            CrmAutoReplyOutcome.SEND_REGISTERED,
            CrmAutoReplyOutcome.SUPPRESSED,
            CrmAutoReplyOutcome.CANCELLED);
    assertThat(CrmAutoReplyOutcome.values()).hasSize(3);
  }

  @Test
  void 转人工原因恰好四档_没有第五个其它() {
    assertThat(CrmAutoReplyEscalationReason.values())
        .as("阶梯走完 / 值班闸挡下 / 发送登记失败 / 人工手动标记 —— 四个真实来源")
        .containsExactlyInAnyOrder(
            CrmAutoReplyEscalationReason.LADDER_EXHAUSTED,
            CrmAutoReplyEscalationReason.DUTY_GATE,
            CrmAutoReplyEscalationReason.SEND_FAILED,
            CrmAutoReplyEscalationReason.MANUAL_ESCALATION);
    assertThat(CrmAutoReplyEscalationReason.values()).hasSize(4);
  }

  @Test
  void 值班安排与范围各三档() {
    assertThat(CrmAutoReplyDutyMode.values())
        .as("无人值守 / 有人值班 / 休息")
        .containsExactlyInAnyOrder(
            CrmAutoReplyDutyMode.UNATTENDED,
            CrmAutoReplyDutyMode.ATTENDED,
            CrmAutoReplyDutyMode.CLOSED);
    assertThat(CrmAutoReplyDutyScope.values())
        .as("整租户 / 按平台 / 按规则 —— 范围从窄到宽取一条")
        .containsExactlyInAnyOrder(
            CrmAutoReplyDutyScope.GLOBAL,
            CrmAutoReplyDutyScope.PLATFORM,
            CrmAutoReplyDutyScope.RULE);
  }

  @Test
  void 枚举标签不是空串_页面不能显示空白() {
    for (var type : CrmAutoReplyTriggerType.values()) {
      assertThat(type.label()).as(type + " 的中文名不能是空串").isNotBlank();
    }
    for (var outcome : CrmAutoReplyOutcome.values()) {
      assertThat(outcome.label()).as(outcome + " 的中文名不能是空串").isNotBlank();
    }
    for (var reason : CrmAutoReplyEscalationReason.values()) {
      assertThat(reason.label()).as(reason + " 的中文名不能是空串").isNotBlank();
    }
    for (var mode : CrmAutoReplyDutyMode.values()) {
      assertThat(mode.label()).as(mode + " 的中文名不能是空串").isNotBlank();
    }
    for (var scope : CrmAutoReplyDutyScope.values()) {
      assertThat(scope.label()).as(scope + " 的中文名不能是空串").isNotBlank();
    }
  }

  @Test
  void 触发类型的枚举名与服务层词表逐个对得上() {
    for (var type : CrmAutoReplyTriggerType.values()) {
      assertThat(AutoReplyService.TRIGGER_TYPES)
          .as("枚举名 " + type.name() + " 必须出现在服务层词表里")
          .contains(type.name());
    }
    assertThat(AutoReplyService.TRIGGER_TYPES)
        .as("词表里不该有枚举之外的取值")
        .allMatch(code -> List.of("CLASSIFICATION", "KEYWORD", "HUMAN_TIMEOUT").contains(code));
  }
}
