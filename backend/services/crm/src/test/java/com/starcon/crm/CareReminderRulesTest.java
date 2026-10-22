package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;

import com.starcon.crm.CareReminderModels.CareReminderBlockCode;
import com.starcon.crm.CareReminderModels.CareReminderEventType;
import com.starcon.crm.CareReminderModels.CareReminderStatus;
import com.starcon.crm.CareReminderModels.CareReminderTransport;
import com.starcon.crm.CareReminderModels.CareReminderType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 15.5.2.1 关怀与催办的**纯逻辑**守卫（不碰数据库）。
 *
 * <p>本类钉住三组**合规常量** —— 它们是加粗句「遵守频次上限与退订要求」与
 * 「催付有合规边界」的**口径本身**，一旦被人悄悄放宽，两道闸就名存实亡：
 *
 * <ul>
 *   <li><b>催付平台白名单</b>：白名单外的平台建不出催付规则（V303 表级 CHECK 同步挡一道）。
 *       这份名单是**产品决策**，不是实现细节，所以要有一条测试钉住它。</li>
 *   <li><b>营销类触达</b>：scope='MARKETING' 的退订只排除营销类触达；
 *       发货通知 / 物流提醒 / 催付 / 售后进度通知属于服务性通知，只有 scope='ALL' 才挡。</li>
 *   <li><b>拦截码词表</b>：只有退订与频次两档 —— 「该平台不支持催付」是**规则级**判定，
 *       平台不在白名单里时那条规则根本建不出来，不存在「一条催付任务被平台挡住」。</li>
 * </ul>
 *
 * <p>⚠️ 本类**不**重复验证数据库层的行为（那些在
 * {@link CareReminderIntegrationTest} 与 {@link CareReminderTenancyIntegrationTest} 里
 * 跑真实 PostgreSQL 16）。这里只钉「口径」，避免同一个语义在两处各写一遍。
 */
class CareReminderRulesTest {

  @Test
  void 催付平台白名单里没有Amazon_白名单外的平台建不出催付规则() {
    assertThat(CareReminderService.PAYMENT_DEMAND_ALLOWED_PLATFORMS)
        .as("催付白名单是产品决策：改它必须同步改 V303 的表级 CHECK 与能力声明")
        .containsExactlyInAnyOrder("SHOPIFY", "EBAY", "TIKTOK_SHOP", "ALIBABA_1688");
    assertThat(CareReminderService.PAYMENT_DEMAND_ALLOWED_PLATFORMS)
        .as("Amazon 的买家消息由平台托管且对催付话术与频次有额外限制，不允许卖家主动催付")
        .doesNotContain("AMAZON");
    // 白名单不允许为空 —— 空名单等于「所有平台都不许催付」，那不是配置，是关掉整个功能。
    assertThat(CareReminderService.PAYMENT_DEMAND_ALLOWED_PLATFORMS).isNotEmpty();
  }

  @Test
  void 营销类触达只有关怀邮件与催评_其余是服务性通知() {
    assertThat(CareReminderService.MARKETING_TYPES)
        .as("scope=MARKETING 的退订只排除「带索取性质」的触达")
        .containsExactlyInAnyOrder(CareReminderType.CARE_MAIL, CareReminderType.REVIEW_REQUEST);
    // 服务性通知：只有 scope=ALL（不要再联系我）才挡。
    assertThat(CareReminderService.MARKETING_TYPES)
        .doesNotContain(
            CareReminderType.SHIPMENT_NOTICE,
            CareReminderType.LOGISTICS_REMINDER,
            CareReminderType.PAYMENT_DEMAND,
            CareReminderType.AFTERSALES_PROGRESS);
  }

  @Test
  void 触达类型照蓝图点名的六类归位() {
    assertThat(
            List.of(
                CareReminderType.SHIPMENT_NOTICE,
                CareReminderType.LOGISTICS_REMINDER,
                CareReminderType.PAYMENT_DEMAND,
                CareReminderType.REVIEW_REQUEST,
                CareReminderType.AFTERSALES_PROGRESS,
                CareReminderType.CARE_MAIL))
        .containsExactlyInAnyOrder(CareReminderType.values());
    // 六类都要有人话标签 —— 页面直接显示它，前端不自己编。
    for (CareReminderType type : CareReminderType.values()) {
      assertThat(type.label()).as(type + " 缺中文标签").isNotBlank();
    }
  }

  @Test
  void 拦截码只有退订与频次两档() {
    assertThat(CareReminderBlockCode.values())
        .containsExactlyInAnyOrder(
            CareReminderBlockCode.OPT_OUT, CareReminderBlockCode.FREQUENCY_LIMIT);
    for (CareReminderBlockCode code : CareReminderBlockCode.values()) {
      assertThat(code.label()).isNotBlank();
    }
  }

  @Test
  void 状态与事件词表齐全() {
    assertThat(CareReminderStatus.values())
        .containsExactlyInAnyOrder(
            CareReminderStatus.QUEUED,
            CareReminderStatus.SENT,
            CareReminderStatus.FAILED,
            CareReminderStatus.BLOCKED,
            CareReminderStatus.CANCELLED);
    assertThat(CareReminderEventType.values())
        .containsExactlyInAnyOrder(CareReminderEventType.OPEN, CareReminderEventType.CLICK);
    assertThat(CareReminderTransport.values())
        .containsExactlyInAnyOrder(
            CareReminderTransport.MANUAL_HANDOFF, CareReminderTransport.PLATFORM_API);
  }

  @Test
  void 触达通道当前只有人工交接可用() {
    // ⚠️ PLATFORM_API 这一档**服务端拒绝写**（平台侧发送接口未接入）。
    //   词表留着它，是为了让「缺了什么、归谁」成为一条可查的事实，
    //   而不是让它变成一条能被误用的路径。
    assertThat(CareReminderTransport.MANUAL_HANDOFF.name()).isEqualTo("MANUAL_HANDOFF");
    assertThat(CareReminderTransport.PLATFORM_API.label()).contains("未接入");
  }

  @Test
  void 营销类集合与白名单互不重叠到让人误解() {
    Set<CareReminderType> marketing = CareReminderService.MARKETING_TYPES;
    assertThat(marketing).contains(CareReminderType.CARE_MAIL);
    // 催付**不是**营销类触达：它是服务性通知（催的是没付钱的订单，不是求好评）。
    assertThat(marketing).doesNotContain(CareReminderType.PAYMENT_DEMAND);
  }
}
