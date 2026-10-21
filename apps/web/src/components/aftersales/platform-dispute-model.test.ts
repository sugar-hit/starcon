import { describe, expect, it } from 'vitest';
import {
  actionBlockedReason,
  countdownText,
  deadlineText,
  deadlineTone,
  DISPUTE_STATUS_OPTIONS,
  DISPUTE_TYPE_OPTIONS,
  EVIDENCE_STATE_OPTIONS,
  EVIDENCE_TYPE_OPTIONS,
  formatAmount,
  isSettlementPending,
  LIABILITY_STAGE_OPTIONS,
  platformTypeMismatchReason,
  PLATFORM_FOR_TYPE,
  POSITION_OPTIONS,
  remainingSeconds,
  RESOLUTION_OPTIONS,
  SETTLEMENT_STATE_OPTIONS,
  settlementEffectiveHint,
} from './platform-dispute-model';

/**
 * 11.4.1.1 平台纠纷的前端单元测试。
 *
 * <p>测的是**蓝图那句加粗句**「<b>平台纠纷有严格举证时限，超期自动判败</b>，
 * 是硬性时效要求」在界面上是不是真的看得见：
 *
 * <ul>
 *   <li><b>倒计时由绝对截止时刻现算</b> —— 服务端用真实的 timestamptz 列 + DB 守卫①
 *       钉死了「超期自动判负」，前端也必须有一份镜像：剩余时间只认截止时刻，
 *       不认接口给的「请求那一刻的剩余秒数」快照。</li>
 *   <li><b>已超期显示成「已超期」而不是一个负数</b> —— 负数在列表里会被当成怪数字划过去。</li>
 *   <li><b>「超期自动判负」没有手动入口</b> —— 前端的动作清单里也不该出现它。</li>
 * </ul>
 */

describe('倒计时：由绝对截止时刻派生', () => {
  const deadline = '2026-03-01T08:00:00+08:00';
  const at = new Date(deadline).getTime();

  it('剩余秒数由截止时刻减去「现在」算出', () => {
    expect(remainingSeconds(deadline, at - 3600_000)).toBe(3600);
    expect(remainingSeconds(deadline, at - 86_400_000)).toBe(86400);
  });

  it('已过截止时刻 ⇒ 剩余 0（不是负数）', () => {
    expect(remainingSeconds(deadline, at + 1000)).toBe(0);
    expect(remainingSeconds(deadline, at + 86_400_000)).toBe(0);
  });

  it('缺失 / 非法时刻不炸，返回 0', () => {
    expect(remainingSeconds(null)).toBe(0);
    expect(remainingSeconds(undefined)).toBe(0);
    expect(remainingSeconds('不是时刻')).toBe(0);
  });

  it('文案按天 / 小时 / 分钟 / 秒分级', () => {
    expect(countdownText(deadline, at - 3 * 86_400_000)).toBe('3 天 0 小时');
    expect(countdownText(deadline, at - 2 * 3600_000 - 5 * 60_000)).toBe('2 小时 5 分');
    expect(countdownText(deadline, at - 90_000)).toBe('1 分 30 秒');
    expect(countdownText(deadline, at - 20_000)).toBe('20 秒');
  });

  it('已超期显示「已超期」而不是负数', () => {
    expect(countdownText(deadline, at + 1000)).toBe('已超期');
  });

  it('举证已提交（时限解除）时不显示倒计时', () => {
    expect(deadlineText(deadline, 'RELEASED', at - 1000)).toBe('时限已解除');
    expect(deadlineText(deadline, 'NORMAL', at - 3600_000)).toBe('1 小时 0 分');
  });

  it('时限状态的色调：超期 = danger，即将到期 = warning', () => {
    expect(deadlineTone('PASSED')).toBe('danger');
    expect(deadlineTone('URGENT')).toBe('warning');
    expect(deadlineTone('RELEASED')).toBe('info');
    expect(deadlineTone('NORMAL')).toBe('neutral');
    expect(deadlineTone(null)).toBe('neutral');
  });
});

describe('类型与平台的对应', () => {
  it('蓝图点名的三种纠纷各自绑定一个平台', () => {
    expect(PLATFORM_FOR_TYPE.A_TO_Z).toBe('AMAZON');
    expect(PLATFORM_FOR_TYPE.EBAY_DISPUTE).toBe('EBAY');
    expect(PLATFORM_FOR_TYPE.ALIEXPRESS_DISPUTE).toBe('ALIEXPRESS');
  });

  it('类型与平台对不上时要说清楚为什么不能提交', () => {
    const reason = platformTypeMismatchReason('A_TO_Z', 'EBAY');
    expect(reason).toContain('AMAZON');
    expect(reason).toContain('EBAY');
  });

  it('对得上 / 其它类型不拦', () => {
    expect(platformTypeMismatchReason('A_TO_Z', 'AMAZON')).toBeNull();
    expect(platformTypeMismatchReason('OTHER', 'EBAY')).toBeNull();
    expect(platformTypeMismatchReason(null, 'EBAY')).toBeNull();
    expect(platformTypeMismatchReason('A_TO_Z', null)).toBeNull();
  });
});

describe('结算影响：登记不等于生效', () => {
  it('待财务确认时明说「尚未生效」，且生效金额是 0', () => {
    const hint = settlementEffectiveHint({
      requestedAmount: 40,
      effectiveAmount: 0,
      settlementStatus: 'PENDING_FINANCE_CONFIRM',
    });
    expect(hint).toContain('尚未生效');
    expect(hint).toContain('生效 0');
    expect(hint).toContain('等待财务确认');
  });

  it('已确认时显示生效金额', () => {
    const hint = settlementEffectiveHint({
      requestedAmount: 40,
      effectiveAmount: 40,
      settlementStatus: 'CONFIRMED',
    });
    expect(hint).toContain('已生效');
    expect(hint).toContain('40');
  });

  it('被财务驳回时生效金额仍是 0', () => {
    const hint = settlementEffectiveHint({
      requestedAmount: 40,
      effectiveAmount: 0,
      settlementStatus: 'REJECTED',
    });
    expect(hint).toContain('驳回');
    expect(hint).toContain('生效金额 0');
  });

  it('只有待确认状态算「待办」', () => {
    expect(isSettlementPending({ settlementStatus: 'PENDING_FINANCE_CONFIRM' })).toBe(true);
    expect(isSettlementPending({ settlementStatus: 'CONFIRMED' })).toBe(false);
    expect(isSettlementPending({ settlementStatus: 'REJECTED' })).toBe(false);
  });
});

describe('动作禁用理由由后端给，前端只转述', () => {
  const actions = [
    { code: 'SUBMIT_EVIDENCE', allowed: true, blockedReason: null },
    { code: 'REGISTER_SETTLEMENT', allowed: false, blockedReason: '只有已终结的纠纷才有结算影响' },
  ];

  it('可执行的动作没有禁用理由', () => {
    expect(actionBlockedReason(actions, 'SUBMIT_EVIDENCE')).toBeNull();
  });

  it('不可执行的动作把后端的理由原样带出来', () => {
    expect(actionBlockedReason(actions, 'REGISTER_SETTLEMENT')).toBe(
      '只有已终结的纠纷才有结算影响',
    );
  });

  it('动作不存在时也要有话说，而不是静默放行', () => {
    expect(actionBlockedReason(actions, 'NOT_A_REAL_ACTION')).toContain('不可用');
    expect(actionBlockedReason(undefined, 'SUBMIT_EVIDENCE')).toContain('不可用');
  });
});

describe('选项集合', () => {
  it('纠纷状态六个且顺序 = 状态机推进顺序', () => {
    expect(DISPUTE_STATUS_OPTIONS.map((o) => o.code)).toEqual([
      'OPEN',
      'EVIDENCE_PREPARING',
      'EVIDENCE_SUBMITTED',
      'RESOLVED_WON',
      'RESOLVED_LOST',
      'WITHDRAWN',
    ]);
  });

  it('纠纷类型四种：蓝图点名的三种 + 兜底', () => {
    expect(DISPUTE_TYPE_OPTIONS.map((o) => o.code)).toEqual([
      'A_TO_Z',
      'EBAY_DISPUTE',
      'ALIEXPRESS_DISPUTE',
      'OTHER',
    ]);
  });

  it('「超期自动判负」不在人工结果码里 —— 那是系统判的', () => {
    expect(RESOLUTION_OPTIONS.map((o) => o.code)).not.toContain('DEADLINE_EXPIRED');
    expect(RESOLUTION_OPTIONS).toHaveLength(3);
  });

  it('举证材料状态是三态：待核查 / 已具备 / 确认缺失', () => {
    expect(EVIDENCE_STATE_OPTIONS.map((o) => o.code)).toEqual(['PENDING', 'PROVIDED', 'MISSING']);
  });

  it('举证材料类型覆盖履约 / 物流 / 发票 / 实物 / 沟通', () => {
    expect(EVIDENCE_TYPE_OPTIONS.map((o) => o.code)).toContain('ORDER_FULFILLMENT_PROOF');
    expect(EVIDENCE_TYPE_OPTIONS.map((o) => o.code)).toContain('LOGISTICS_TRACKING_PROOF');
    expect(EVIDENCE_TYPE_OPTIONS.map((o) => o.code)).toContain('INVOICE');
  });

  it('我方立场五个，责任判定六个', () => {
    expect(POSITION_OPTIONS).toHaveLength(5);
    expect(LIABILITY_STAGE_OPTIONS).toHaveLength(6);
  });

  it('结算口径四个，「结算影响已生效」只有一个', () => {
    expect(SETTLEMENT_STATE_OPTIONS.map((o) => o.code)).toEqual([
      'NOT_APPLIED',
      'PENDING_FINANCE_CONFIRM',
      'APPLIED',
      'FINANCE_REJECTED',
    ]);
    expect(SETTLEMENT_STATE_OPTIONS.filter((o) => o.code === 'APPLIED')).toHaveLength(1);
  });
});

describe('展示格式化', () => {
  it('金额带币种且保留两位', () => {
    expect(formatAmount(88.5, 'USD')).toBe('88.50 USD');
    expect(formatAmount(7.1234, 'USD')).toBe('7.12 USD');
  });

  it('空金额显示破折号而不是 0', () => {
    expect(formatAmount(null)).toBe('—');
    expect(formatAmount(undefined)).toBe('—');
  });
});
