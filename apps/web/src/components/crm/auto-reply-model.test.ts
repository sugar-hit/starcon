import { describe, expect, it } from 'vitest';
import {
  autoReplyClassificationLabel,
  autoReplyDutyModeLabel,
  autoReplyDutyScopeLabel,
  autoReplyEscalationReasonLabel,
  autoReplyKillSwitchText,
  autoReplyLadderText,
  autoReplyMomentText,
  autoReplyOutcomeLabel,
  autoReplyStatsText,
  autoReplySuppressedText,
  autoReplyTriggerTypeLabel,
} from './auto-reply-model';

/**
 * 15.5.1.1 自动回复前端模型的纯规则测试。
 *
 * <p>本文件把**页面最容易做错的几件事**钉死：
 *
 * <ol>
 *   <li><b>🔴 一键关闭的语义要在页面上讲清</b>：关闭是**即时生效**的，
 *       而且已排队待发的会被逐条撤销 —— 只写「已关闭」三个字，
 *       运营不知道正在排队的那几条还会不会发出去。</li>
 *   <li><b>🔴 被闸门挡下的原因不能被吃掉</b>：页面必须显示<b>后端给的原因原文</b> ——
 *       只写「被挡下」等于把原因藏起来了，而加粗句要的正是「明确原因」。</li>
 *   <li><b>🔴 阶梯语义要说清</b>：第几级、是不是终点（发完即转人工），
 *       页面**不自己算**「还差多久到下一级」（那是后端口径）。</li>
 *   <li><b>统计口径不重算</b>：页面原样展示服务端算好的那串说明。</li>
 *   <li><b>枚举都要有可读名</b>：页面显示的是中文，英文枚举码只应出现在接口层。</li>
 * </ol>
 */
describe('15.5.1.1 自动回复前端模型', () => {
  describe('触发条件（互斥三选一）', () => {
    it('蓝图点名的三种都有可读名', () => {
      expect(autoReplyTriggerTypeLabel('CLASSIFICATION')).toBe('收到消息分类命中');
      expect(autoReplyTriggerTypeLabel('KEYWORD')).toBe('关键词命中');
      expect(autoReplyTriggerTypeLabel('HUMAN_TIMEOUT')).toBe('无人工介入超时');
    });

    it('未知枚举码原样返回，不编一个看起来像真的名字', () => {
      expect(autoReplyTriggerTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    });
  });

  describe('记录结果', () => {
    it('「被闸门挡下」与「已撤销」是**不同**的两件事', () => {
      expect(autoReplyOutcomeLabel('SEND_REGISTERED')).toBe('已登记发送');
      expect(autoReplyOutcomeLabel('SUPPRESSED')).toBe('被闸门挡下（未发送）');
      expect(autoReplyOutcomeLabel('CANCELLED')).toBe('已撤销（不得再发）');
      expect(autoReplyOutcomeLabel('SUPPRESSED')).not.toBe(autoReplyOutcomeLabel('CANCELLED'));
    });
  });

  describe('转人工原因', () => {
    it('四个真实来源各有可读名', () => {
      expect(autoReplyEscalationReasonLabel('LADDER_EXHAUSTED')).toBe('阶梯走完');
      expect(autoReplyEscalationReasonLabel('DUTY_GATE')).toBe('值班闸挡下');
      expect(autoReplyEscalationReasonLabel('SEND_FAILED')).toBe('发送登记失败');
      expect(autoReplyEscalationReasonLabel('MANUAL_ESCALATION')).toBe('人工手动标记');
    });
  });

  describe('值班安排与范围', () => {
    it('值班安排三档、范围三档', () => {
      expect(autoReplyDutyModeLabel('UNATTENDED')).toBe('无人值守');
      expect(autoReplyDutyModeLabel('ATTENDED')).toBe('有人值班');
      expect(autoReplyDutyModeLabel('CLOSED')).toBe('休息');
      expect(autoReplyDutyScopeLabel('GLOBAL')).toBe('整租户');
      expect(autoReplyDutyScopeLabel('PLATFORM')).toBe('按平台');
      expect(autoReplyDutyScopeLabel('RULE')).toBe('按规则');
    });
  });

  describe('分类标签（沿用上游 15.1.1.3 的词表）', () => {
    it('六类业务标签 + 未分类兜底', () => {
      expect(autoReplyClassificationLabel('PRE_SALE')).toBe('售前咨询');
      expect(autoReplyClassificationLabel('LOGISTICS')).toBe('物流查询');
      expect(autoReplyClassificationLabel('RETURN_REFUND')).toBe('退货退款');
      expect(autoReplyClassificationLabel('COMPLAINT')).toBe('投诉');
      expect(autoReplyClassificationLabel('PLATFORM_COMPLIANCE')).toBe('平台合规');
      expect(autoReplyClassificationLabel('SPAM')).toBe('垃圾信息');
    });

    it('未分类必须说清「不可自动回复」', () => {
      expect(autoReplyClassificationLabel('UNCLASSIFIED')).toContain('不可自动回复');
    });
  });

  describe('🔴 一键关闭的语义', () => {
    it('开着时要说清「关掉会发生什么」', () => {
      const text = autoReplyKillSwitchText({
        autoReplyEnabled: true,
        closedReason: '',
        pendingCancelledCount: 0,
      });
      expect(text).toContain('开着');
      expect(text).toContain('撤销');
    });

    it('关着时要把关闭原因带出来', () => {
      const text = autoReplyKillSwitchText({
        autoReplyEnabled: false,
        closedReason: '平台对纯自动回复有限制，先全关',
        pendingCancelledCount: 3,
      });
      expect(text).toContain('已关闭');
      expect(text).toContain('平台对纯自动回复有限制');
    });

    it('关着但没有原因时不能装作正常 —— 那是异常，要说出来', () => {
      const text = autoReplyKillSwitchText({
        autoReplyEnabled: false,
        closedReason: '   ',
        pendingCancelledCount: 0,
      });
      expect(text).toContain('没有写关闭原因');
    });
  });

  describe('被闸门挡下的原因（加粗句「明确原因」的落点）', () => {
    it('原因原文**原样**带出来，不能只剩「被挡下」三个字', () => {
      const reason =
        '🔴 该会话已由人工接管（接管时刻 2026-10-23T10:00:00Z）：接管后不再自动回复同一会话。';
      const text = autoReplySuppressedText(reason);
      expect(text).toContain('人工接管');
      expect(text).toContain('不再自动回复');
      expect(text).not.toBe('被闸门挡下');
    });

    it('值班闸挡下时原因里要能看到是哪一条排班', () => {
      const reason =
        '值班闸挡下：值班日历判定为有人值班（全天 · 国庆，范围 整租户），不允许自动回复。';
      const text = autoReplySuppressedText(reason);
      expect(text).toContain('值班日历');
      expect(text).toContain('国庆');
      expect(text).toContain('不允许自动回复');
    });

    it('没有原因时不能显示空白 —— 那等于把原因藏起来了', () => {
      expect(autoReplySuppressedText('')).toContain('后端没给原因');
      expect(autoReplySuppressedText('   ')).toContain('后端没给原因');
    });
  });

  describe('阶梯语义', () => {
    it('第 1 级与中间级、终点要讲得清', () => {
      expect(autoReplyLadderText(1, false)).toContain('第 1 级');
      expect(autoReplyLadderText(1, false)).toContain('还会有下一级');
      expect(autoReplyLadderText(2, false)).toContain('第 2 级');
      expect(autoReplyLadderText(2, false)).toContain('中间级');
      expect(autoReplyLadderText(3, true)).toContain('终点');
      expect(autoReplyLadderText(3, true)).toContain('转人工');
    });

    it('页面不自己算「还差多久到下一级」', () => {
      const text = autoReplyLadderText(2, false);
      expect(text).not.toContain('分钟');
    });
  });

  describe('统计（口径服务端算）', () => {
    it('统计摘要**原样**展示，不重算', () => {
      const note = '统计口径由服务端算好，页面不重算任何比率。';
      expect(autoReplyStatsText(note)).toBe(note);
    });
  });

  describe('时刻', () => {
    it('时刻原样显示（去掉 T 与时区尾巴），不做时区换算', () => {
      expect(autoReplyMomentText('2026-10-22T02:00:00Z')).toBe('2026-10-22 02:00:00');
      expect(autoReplyMomentText('2026-10-22T10:00:00+08:00')).toBe('2026-10-22 10:00:00');
    });

    it('空时刻显示占位符，不显示空白', () => {
      expect(autoReplyMomentText(null)).toBe('—');
      expect(autoReplyMomentText('')).toBe('—');
    });
  });
});
