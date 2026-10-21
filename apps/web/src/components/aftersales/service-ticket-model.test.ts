import { describe, expect, it } from 'vitest';
import {
  achievementText,
  actionBlockedReason,
  CLOSE_REASON_OPTIONS,
  closeHint,
  countdownText,
  ESCALATE_CASE_TYPE_OPTIONS,
  ESCALATE_DISPUTE_TYPE_OPTIONS,
  ESCALATE_REASON_CODE_OPTIONS,
  escalateHint,
  escalateMissingFields,
  formatTime,
  priorityTone,
  remainingSeconds,
  satisfactionStars,
  SLA_STATE_OPTIONS,
  slaTone,
  statusTone,
  TICKET_PRIORITY_OPTIONS,
  TICKET_SORT_OPTIONS,
  TICKET_SOURCE_OPTIONS,
  TICKET_STATUS_OPTIONS,
  TICKET_TYPE_OPTIONS,
} from './service-ticket-model';

/**
 * 11.4.2.1 工单管理的前端单元测试。
 *
 * <p>测的是**蓝图职责句里的几个动词**在界面上是不是真的看得见：
 *
 * <ul>
 *   <li><b>倒计时由绝对截止时刻现算</b> —— 后端用真实的 timestamptz 列 + IMMUTABLE
 *       函数钉死了 SLA 判定，前端也必须有一份镜像：剩余时间只认截止时刻，
 *       不认接口给的「请求那一刻的剩余秒数」快照。</li>
 *   <li><b>「关闭」与「办完」分得开</b> —— 已解决 / 已升级转单算办完；
 *       买家未回应 / 重复工单 / 不再跟进**不算**。</li>
 *   <li><b>「没测过」与「全没达标」分得开</b> —— 分母为 0 时是「暂无口径」而不是 0%。</li>
 *   <li><b>类型推进是带方向的</b> —— 咨询只能升为投诉，投诉才能升为纠纷。</li>
 * </ul>
 */

describe('倒计时：由绝对截止时刻派生', () => {
  const due = '2026-03-01T08:00:00+08:00';
  const at = new Date(due).getTime();

  it('剩余秒数由截止时刻减去「现在」算出', () => {
    expect(remainingSeconds(due, at - 3600_000)).toBe(3600);
    expect(remainingSeconds(due, at - 86_400_000)).toBe(86400);
  });

  it('已过截止时刻 ⇒ 剩余 0（不是负数）', () => {
    expect(remainingSeconds(due, at + 1000)).toBe(0);
    expect(remainingSeconds(due, at + 86_400_000)).toBe(0);
  });

  it('缺失 / 非法时刻不炸，返回 0', () => {
    expect(remainingSeconds(null)).toBe(0);
    expect(remainingSeconds(undefined)).toBe(0);
    expect(remainingSeconds('不是时刻')).toBe(0);
  });

  it('文案按天 / 小时 / 分钟 / 秒分级', () => {
    expect(countdownText(due, at - 3 * 86_400_000)).toBe('3 天 0 小时');
    expect(countdownText(due, at - 2 * 3600_000 - 5 * 60_000)).toBe('2 小时 5 分');
    expect(countdownText(due, at - 90_000)).toBe('1 分 30 秒');
    expect(countdownText(due, at - 20_000)).toBe('20 秒');
  });

  it('已超期显示「已超期」而不是负数', () => {
    expect(countdownText(due, at + 1000)).toBe('已超期');
  });

  it('没设时限显示「未设时限」而不是 0 秒', () => {
    expect(countdownText(null)).toBe('未设时限');
    expect(countdownText(undefined)).toBe('未设时限');
  });
});

describe('SLA 色调：语义挂钩', () => {
  it('超期 = danger，计时中 = warning，达标 = success', () => {
    expect(slaTone('BREACHED')).toBe('danger');
    expect(slaTone('PENDING')).toBe('warning');
    expect(slaTone('MET')).toBe('success');
    expect(slaTone('NO_DEADLINE')).toBe('neutral');
    expect(slaTone(null)).toBe('neutral');
  });

  it('优先级色调：紧急 = danger，高 = warning', () => {
    expect(priorityTone('URGENT')).toBe('danger');
    expect(priorityTone('HIGH')).toBe('warning');
    expect(priorityTone('NORMAL')).toBe('info');
    expect(priorityTone('LOW')).toBe('neutral');
  });

  it('状态色调：已关闭 = success，待买家 = warning', () => {
    expect(statusTone('RESOLVED')).toBe('success');
    expect(statusTone('IN_PROGRESS')).toBe('info');
    expect(statusTone('WAITING_BUYER')).toBe('warning');
    expect(statusTone('OPEN')).toBe('neutral');
    expect(statusTone('CANCELLED')).toBe('neutral');
  });
});

describe('关闭口径：关闭 ≠ 办完', () => {
  it('已解决 / 已升级转单算办完', () => {
    expect(closeHint('RESOLVED')).toContain('算办完');
    expect(closeHint('RESOLVED')).not.toContain('不算');
    expect(closeHint('ESCALATED')).toContain('算办完');
  });

  it('买家未回应 / 重复工单 / 不再跟进只算「已关闭」', () => {
    expect(closeHint('NO_RESPONSE')).toContain('不算');
    expect(closeHint('DUPLICATE')).toContain('不算');
    expect(closeHint('CANCELLED')).toContain('不算');
    expect(closeHint('ABANDONED')).toContain('不算');
  });

  it('没关闭时没有关闭口径', () => {
    expect(closeHint('')).toBe('');
    expect(closeHint(null)).toBe('');
  });
});

describe('SLA 达成率：没测过 ≠ 全没达标', () => {
  it('分母为 0 ⇒ 「暂无口径」而不是 0%', () => {
    expect(achievementText(null)).toBe('暂无口径');
    expect(achievementText(undefined)).toBe('暂无口径');
  });

  it('有口径时显示百分比（两位小数）', () => {
    expect(achievementText(50)).toBe('50.00%');
    expect(achievementText(87.456)).toBe('87.46%');
    expect(achievementText(0)).toBe('0.00%');
  });
});

describe('满意度', () => {
  it('未评价时明说「未评价」', () => {
    expect(satisfactionStars(null)).toBe('未评价');
    expect(satisfactionStars(undefined)).toBe('未评价');
  });

  it('1~5 分 ⇒ 五颗星', () => {
    expect(satisfactionStars(5)).toBe('★★★★★（5 分）');
    expect(satisfactionStars(3)).toBe('★★★☆☆（3 分）');
    expect(satisfactionStars(1)).toBe('★☆☆☆☆（1 分）');
  });
});

describe('升级：类型推进是带方向的', () => {
  it('咨询只能升为投诉', () => {
    expect(escalateHint('INQUIRY')).toContain('升级为投诉');
    expect(escalateHint('INQUIRY')).not.toContain('升级为纠纷');
  });

  it('投诉只能升为纠纷', () => {
    expect(escalateHint('COMPLAINT')).toContain('升级为纠纷');
    expect(escalateHint('COMPLAINT')).not.toContain('升级为投诉');
  });

  it('催单 / 平台政策咨询不参与升级通道', () => {
    expect(escalateHint('ORDER_CHASE')).toContain('不参与');
    expect(escalateHint('POLICY_CONSULT')).toContain('不参与');
  });
});

describe('升级表单：缺什么当场说清', () => {
  const completeComplaint = {
    reasonNote: '买家不满答复',
    caseType: 'RETURN_REFUND',
    reasonCode: 'QUALITY_DEFECT',
    amount: '88.50',
    currency: 'USD',
    quantity: '1',
  };

  it('转投诉：要素齐了就没有缺项', () => {
    expect(escalateMissingFields('COMPLAINT', completeComplaint)).toBe('');
  });

  it('转投诉：理由、金额、币种、数量缺一都要点名', () => {
    const missing = escalateMissingFields('COMPLAINT', {
      ...completeComplaint,
      reasonNote: '  ',
      amount: '',
      currency: 'US',
    });
    expect(missing).toContain('升级理由');
    expect(missing).toContain('申请金额');
    expect(missing).toContain('币种');
    expect(missing).not.toContain('目标售后类型');
  });

  it('转纠纷：平台上下文缺一都要点名（含举证截止时刻）', () => {
    const missing = escalateMissingFields('DISPUTE', {
      reasonNote: '平台已立案',
      platformCode: 'AMAZON',
      platformName: 'Amazon',
      disputeNo: '',
      disputeType: 'A_TO_Z',
      disputeAmount: '120',
      disputeCurrency: 'USD',
      evidenceDeadline: '',
    });
    expect(missing).toContain('平台纠纷单号');
    expect(missing).toContain('举证截止时刻');
    expect(missing).not.toContain('平台码');
  });

  it('转纠纷：要素齐了就没有缺项', () => {
    expect(
      escalateMissingFields('DISPUTE', {
        reasonNote: '平台已立案',
        platformCode: 'AMAZON',
        platformName: 'Amazon',
        disputeNo: 'A2Z-1',
        disputeType: 'A_TO_Z',
        disputeAmount: '120',
        disputeCurrency: 'USD',
        evidenceDeadline: '2026-04-01T00:00:00Z',
      }),
    ).toBe('');
  });
});

describe('动作禁用理由由后端给，前端只转述', () => {
  const actions = [
    { code: 'CLOSE', allowed: true, blockedReason: null },
    { code: 'TRANSFER', allowed: false, blockedReason: '这张工单当前没有责任人' },
  ];

  it('可执行的动作没有禁用理由', () => {
    expect(actionBlockedReason(actions, 'CLOSE')).toBeNull();
  });

  it('不可执行的动作把后端的理由原样带出来', () => {
    expect(actionBlockedReason(actions, 'TRANSFER')).toBe('这张工单当前没有责任人');
  });

  it('动作不存在时也要有话说，而不是静默放行', () => {
    expect(actionBlockedReason(actions, 'NOT_A_REAL_ACTION')).toContain('不可用');
    expect(actionBlockedReason(undefined, 'CLOSE')).toContain('不可用');
  });
});

describe('选项集合', () => {
  it('工单状态五个且顺序 = 状态机推进顺序', () => {
    expect(TICKET_STATUS_OPTIONS.map((o) => o.code)).toEqual([
      'OPEN',
      'IN_PROGRESS',
      'WAITING_BUYER',
      'RESOLVED',
      'CANCELLED',
    ]);
  });

  it('工单类型四种：蓝图点名的四类', () => {
    expect(TICKET_TYPE_OPTIONS.map((o) => o.code)).toEqual([
      'INQUIRY',
      'COMPLAINT',
      'ORDER_CHASE',
      'POLICY_CONSULT',
    ]);
  });

  it('创建来源三种：人工 / 来自消息 / 自动触发', () => {
    expect(TICKET_SOURCE_OPTIONS.map((o) => o.code)).toEqual(['MANUAL', 'MESSAGE', 'AUTOMATIC']);
  });

  it('优先级四档', () => {
    expect(TICKET_PRIORITY_OPTIONS.map((o) => o.code)).toEqual([
      'LOW',
      'NORMAL',
      'HIGH',
      'URGENT',
    ]);
  });

  it('关闭原因六种，且「已解决」与「已升级转单」是算办完的那两个', () => {
    expect(CLOSE_REASON_OPTIONS).toHaveLength(6);
    expect(CLOSE_REASON_OPTIONS.map((o) => o.code)).toContain('RESOLVED');
    expect(CLOSE_REASON_OPTIONS.map((o) => o.code)).toContain('ESCALATED');
  });

  it('SLA 判定状态四个：计时中 / 已达标 / 已超期 / 无时限', () => {
    expect(SLA_STATE_OPTIONS.map((o) => o.code)).toEqual([
      'PENDING',
      'MET',
      'BREACHED',
      'NO_DEADLINE',
    ]);
  });

  it('排序白名单四个键', () => {
    expect(TICKET_SORT_OPTIONS.map((o) => o.code)).toEqual([
      'sla_asc',
      'created_desc',
      'priority_desc',
      'assigned_asc',
    ]);
  });

  it('升级用的词表照抄既有模块，不另立一套', () => {
    // 售后类型五种（11.1.1.1 的词表）。
    expect(ESCALATE_CASE_TYPE_OPTIONS.map((o) => o.code)).toEqual([
      'RETURN_REFUND',
      'REFUND_ONLY',
      'EXCHANGE',
      'REISSUE',
      'REPAIR',
    ]);
    // 原因分类七类（11.1.1.1 的词表）。
    expect(ESCALATE_REASON_CODE_OPTIONS).toHaveLength(7);
    expect(ESCALATE_REASON_CODE_OPTIONS.map((o) => o.code)).toContain('FRAUD_RETURN');
    // 纠纷类型四种（11.4.1.1 的词表）。
    expect(ESCALATE_DISPUTE_TYPE_OPTIONS.map((o) => o.code)).toEqual([
      'A_TO_Z',
      'EBAY_DISPUTE',
      'ALIEXPRESS_DISPUTE',
      'OTHER',
    ]);
  });
});

describe('展示格式化', () => {
  it('时刻缺失时显示破折号', () => {
    expect(formatTime(null)).toBe('—');
    expect(formatTime(undefined)).toBe('—');
  });

  it('非法时刻原样返回而不是显示 Invalid Date', () => {
    expect(formatTime('不是时刻')).toBe('不是时刻');
  });
});
