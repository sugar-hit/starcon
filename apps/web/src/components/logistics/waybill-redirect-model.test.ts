import { describe, expect, it } from 'vitest';
import {
  deadlineText,
  dispatchStateText,
  errorText,
  precheckSummaryLine,
  redirectSummaryLine,
  timeText,
  verdictSentence,
  verdictTone,
} from './waybill-redirect-model';

/**
 * 10.2.1.3 改址与取消的**展示派生**单测。
 *
 * <p>这些纯函数承担蓝图加粗句的最后一米：「超时的改址请求要明确告知能否成功，
 * 而不是让客服猜」。所以断言全部围绕**两种处境必须不同**：
 *
 * <ul>
 *   <li>「已超时限」⇒ 再也不会成功（红）</li>
 *   <li>「在时限内但渠道没接通」⇒ 接通后仍可成功（黄）</li>
 * </ul>
 *
 * <p>两者如果塌成同一句话 / 同一颜色，这一页就退化回「让客服猜」。
 */

const baseRow = {
  verdict: 'WITHIN_WINDOW',
  verdictLabel: '在改址时限内',
  canSucceed: false,
  canSucceedReason: '在改址时限内，但**渠道未接通**，现在发不出去；接通后仍可成功。',
  outcome: 'NOT_ATTEMPTED',
  carrierRef: '',
};

describe('verdictTone · 判定与结论是两个维度', () => {
  it('已超时限是红的：再也不会成功', () => {
    expect(
      verdictTone({ verdict: 'DEADLINE_PASSED', canSucceed: false, outcome: 'NOT_ATTEMPTED' }),
    ).toBe('bad');
  });

  it('渠道明确不支持同样是红的：一定不会成功', () => {
    expect(
      verdictTone({ verdict: 'CHANNEL_UNSUPPORTED', canSucceed: false, outcome: 'NOT_ATTEMPTED' }),
    ).toBe('bad');
  });

  it('⚠️ 在时限内但渠道没接通是黄的，不是红的 —— 与「已超时限」分开', () => {
    // 这是本模块最关键的一条：两者都是 canSucceed=false，但行动完全相反。
    // 只按 verdict 上色的话它们会同色，客服就会对超时的票一直重试。
    expect(verdictTone(baseRow)).toBe('warn');
  });

  it('规则未登记是黄的不是红的：系统不知道 ≠ 明确不行', () => {
    expect(
      verdictTone({ verdict: 'RULE_UNREGISTERED', canSucceed: false, outcome: 'NOT_ATTEMPTED' }),
    ).toBe('warn');
  });

  it('真实受理是绿的，且优先于 canSucceed（受理后 canSucceed 可能仍为 false）', () => {
    expect(
      verdictTone({ verdict: 'WITHIN_WINDOW', canSucceed: false, outcome: 'ACCEPTED' }),
    ).toBe('ok');
  });

  it('拒收是红的', () => {
    expect(verdictTone({ verdict: 'WITHIN_WINDOW', canSucceed: true, outcome: 'REJECTED' })).toBe(
      'bad',
    );
  });
});

describe('verdictSentence · 客服能照着念的一句准话', () => {
  it('已超时限：明确说「再也不会」，不是「暂不可用」', () => {
    const text = verdictSentence({
      ...baseRow,
      verdict: 'DEADLINE_PASSED',
      verdictLabel: '已超过渠道时限',
    });
    expect(text).toContain('再也不会改址成功');
    expect(text).toContain('取消 + 重新发货');
  });

  it('在时限内但没接通：说「接通后仍可成功」，并明确它不是超时', () => {
    const text = verdictSentence(baseRow);
    expect(text).toContain('接通后仍可成功');
    expect(text).not.toContain('再也不会');
  });

  it('规则未登记：说「不知道」，且要求先登记规则再答复买家', () => {
    const text = verdictSentence({
      ...baseRow,
      verdict: 'RULE_UNREGISTERED',
      verdictLabel: '未登记该渠道的规则',
    });
    expect(text).toContain('不知道');
    expect(text).toContain('登记规则');
  });

  it('能改时给一句短的肯定话，不堆砌原因', () => {
    const text = verdictSentence({ ...baseRow, canSucceed: true });
    expect(text).toContain('可以改');
  });
});

describe('deadlineText · 没有时限就写「未登记」，不编一个数字', () => {
  it('null 显示「未登记时限」而不是 0 或空白', () => {
    expect(deadlineText(null)).toBe('未登记时限');
    expect(deadlineText(undefined)).toBe('未登记时限');
  });

  it('负数是「已超时限」', () => {
    expect(deadlineText(-30)).toBe('已超时限');
  });

  it('分钟 / 小时 / 天三档', () => {
    expect(deadlineText(30)).toBe('剩 30 分钟');
    expect(deadlineText(600)).toBe('剩 10 小时');
    expect(deadlineText(60 * 24 * 3)).toBe('剩 3 天');
  });
});

describe('dispatchStateText · 未派发必须先于「能不能成」', () => {
  it('未派发要说清「没发出去」，不能显示成处理中', () => {
    const text = dispatchStateText(baseRow);
    expect(text).toContain('未派发');
    expect(text).toContain('不会凭空受理');
  });

  it('受理与拒收都带回执号', () => {
    expect(dispatchStateText({ ...baseRow, outcome: 'ACCEPTED', carrierRef: 'ref-1' })).toContain(
      'ref-1',
    );
    expect(
      dispatchStateText({ ...baseRow, outcome: 'REJECTED', carrierRef: 'ref-2' }),
    ).toContain('ref-2');
  });
});

describe('汇总行', () => {
  const page = {
    items: [],
    total: 7,
    withinWindow: 3,
    deadlinePassed: 2,
    channelUnsupported: 1,
    ruleUnregistered: 1,
    canSucceedCount: 1,
    acceptedCount: 1,
    rejectedCount: 0,
    notAttemptedCount: 6,
    pendingNotificationCount: 2,
    notes: [],
  } as unknown as Parameters<typeof redirectSummaryLine>[0];

  it('强调覆盖筛选后的全量，且四类判定分开计数', () => {
    const text = redirectSummaryLine(page);
    expect(text).toContain('共 7 条');
    expect(text).toContain('已超时限 2');
    expect(text).toContain('规则未登记 1');
    expect(text).toContain('未派发 6');
  });
});

describe('precheckSummaryLine', () => {
  it('分成「能改 / 不能改 / 未出号」三类，并声明不落库', () => {
    const text = precheckSummaryLine({
      selected: 5,
      issued: 4,
      notIssued: 1,
      canSucceed: 2,
      cannotSucceed: 3,
      entries: [],
      notes: [],
    } as unknown as Parameters<typeof precheckSummaryLine>[0]);
    expect(text).toContain('能改 2');
    expect(text).toContain('不能改 3');
    expect(text).toContain('不落库');
  });
});

describe('timeText', () => {
  it('空值显示「—」而不是 1970', () => {
    expect(timeText(null)).toBe('—');
    expect(timeText('')).toBe('—');
  });

  it('非法时间原样返回，不抛异常也不显示 NaN', () => {
    expect(timeText('not-a-date')).toBe('not-a-date');
  });
});

describe('errorText', () => {
  it('ApiError 的 message 优先', () => {
    expect(errorText(new Error('普通错误'))).toBe('普通错误');
    expect(errorText('字符串')).toBe('请求失败，请稍后重试');
  });
});
