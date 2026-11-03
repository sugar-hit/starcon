import { describe, expect, it } from 'vitest';
import {
  detailLinkLabel,
  factLocationLabel,
  factRefLabel,
  formatMetric,
  formatRatio,
  isInternalRoute,
  matchModeLabel,
  matchModeTone,
  periodLabel,
  toAnswerView,
  type AssistantAnswer,
  type AssistantEvidence,
} from './assistant-model';

/**
 * 18.4.1.1「对话助手」前端口径单元测试。
 *
 * <p>这些全是**纯函数**（不碰网络、不碰 DOM），测的是「口径对不对」——
 * 答案里的数字如果错了，运营照着它做决策就完了，所以口径必须被钉住。
 *
 * <p>🔴 测得最狠的一条是 {@link toAnswerView}：**没有明细链接的答案不许显示成答案**。
 * 后端说「没结论」或「没给依据」，页面都必须按「没结论」显示 ——
 * 一个看起来像样的答案，配一个点不动的明细链接，是最坏的一种错。
 */

function evidence(overrides: Partial<AssistantEvidence> = {}): AssistantEvidence {
  return {
    factService: 'reporting',
    factTable: 'reporting_profit_fact',
    factField: 'amount',
    factRef: 'SHOP-A',
    factLabel: '北美一店',
    metricCode: 'GROSS_PROFIT',
    metricValue: '-12.300000',
    metricUnit: 'USD',
    detailRoute: '/analytics/profit/profit-report?shopCode=SHOP-A',
    detailLabel: '打开该店的利润明细',
    caliberNote: '毛利额 = 收入 − 各成本项之和',
    snapshotAt: '2026-11-01T10:00:00Z',
    ...overrides,
  };
}

function answer(overrides: Partial<AssistantAnswer> = {}): AssistantAnswer {
  return {
    sessionId: '11111111-1111-1111-1111-111111111111',
    turnId: '22222222-2222-2222-2222-222222222222',
    turnNo: 1,
    question: '上周哪个店利润最差',
    matchMode: 'KEYWORD',
    intentCode: 'SHOP_PROFIT_RANKING',
    intentName: '店铺利润排名',
    answered: true,
    answer: 'USD 口径：第 1 名（毛利额从低到高）是 北美一店（-12.3 USD）。',
    caliber: '多模式利润报表（13.4.1.1）店铺维度。时间范围：2026-10-26 ~ 2026-11-01。',
    periodCode: 'LAST_WEEK',
    periodFrom: '2026-10-26',
    periodTo: '2026-11-01',
    scopeType: 'COMPANY',
    shopCode: '',
    sourceService: 'reporting',
    sourceEndpoint: '/reporting/profit-report/report?dimension=SHOP',
    evidence: [evidence()],
    nextActions: [
      { label: '打开利润报表', route: '/analytics/profit/profit-report', moduleId: '13.4.1.1' },
    ],
    unavailableReason: '',
    ownerModule: '',
    ...overrides,
  };
}

describe('formatMetric —— 十进制文本，不转数值', () => {
  it('算不出来显示「—」，绝不显示 0', () => {
    expect(formatMetric(null)).toBe('—');
    expect(formatMetric(undefined)).toBe('—');
    expect(formatMetric('')).toBe('—');
    expect(formatMetric(null, 'USD')).toBe('—');
  });

  it('去掉多余的尾零但不动数值本身', () => {
    expect(formatMetric('-12.300000', 'USD')).toBe('-12.3 USD');
    expect(formatMetric('12.000000', '件')).toBe('12 件');
    expect(formatMetric('0.500000')).toBe('0.5');
  });

  it('真的是 0 就显示 0（0 与「算不出来」是两件事）', () => {
    expect(formatMetric('0.000000', 'USD')).toBe('0 USD');
  });
});

describe('formatRatio —— 字符串移位，不碰浮点', () => {
  it('比率换算成百分数', () => {
    expect(formatRatio('0.8000')).toBe('80.00%');
    expect(formatRatio('1.0000')).toBe('100.00%');
    expect(formatRatio('0.0000')).toBe('0.00%');
  });

  it('算不出来显示「—」', () => {
    expect(formatRatio(null)).toBe('—');
    expect(formatRatio('')).toBe('—');
  });

  it('不是数字就原样显示（不猜）', () => {
    expect(formatRatio('not-a-number')).toBe('not-a-number');
  });
});

describe('isInternalRoute —— 明细链接必须是站内路由', () => {
  it('站内绝对路径算', () => {
    expect(isInternalRoute('/analytics/profit/profit-report')).toBe(true);
    expect(isInternalRoute('/analytics/profit/profit-report?shopCode=A')).toBe(true);
  });

  it('外链与协议相对链接不算', () => {
    expect(isInternalRoute('https://example.com/x')).toBe(false);
    expect(isInternalRoute('//example.com/x')).toBe(false);
    expect(isInternalRoute('analytics/profit')).toBe(false);
    expect(isInternalRoute('')).toBe(false);
  });
});

describe('toAnswerView —— 没有明细链接的答案不许显示成答案', () => {
  it('有结论且有依据 ⇒ 显示成答案', () => {
    const view = toAnswerView(answer());
    expect(view.answered).toBe(true);
    expect(view.evidence).toHaveLength(1);
    expect(view.tone).toBe('ok');
    expect(view.nextActions).toHaveLength(1);
  });

  it('后端说没结论 ⇒ 按没结论显示', () => {
    const view = toAnswerView(
      answer({
        answered: false,
        evidence: [],
        answer: 'AI 生成通道未接入',
        unavailableReason: 'AI 生成通道未接入',
        ownerModule: '18.4',
        matchMode: 'FREE_TEXT',
      }),
    );
    expect(view.answered).toBe(false);
    expect(view.evidence).toHaveLength(0);
    expect(view.headline).toContain('未接入');
    expect(view.tone).toBe('warn');
  });

  it('后端标了有结论但没给依据 ⇒ 仍然按没结论显示（页面比后端更严）', () => {
    const view = toAnswerView(answer({ evidence: [] }));
    expect(view.answered).toBe(false);
    expect(view.evidence).toHaveLength(0);
  });

  it('依据里的外链明细被丢掉（点不动的明细不算依据）', () => {
    const view = toAnswerView(
      answer({ evidence: [evidence({ detailRoute: 'https://example.com/x' })] }),
    );
    expect(view.answered).toBe(false);
    expect(view.evidence).toHaveLength(0);
  });

  it('还没提问时给一个空形态，不报错', () => {
    const view = toAnswerView(null);
    expect(view.answered).toBe(false);
    expect(view.evidence).toHaveLength(0);
    expect(view.headline).toBe('还没有提问');
  });

  it('建议的下一步也只保留站内路由', () => {
    const view = toAnswerView(
      answer({
        nextActions: [
          { label: '外链', route: 'https://example.com', moduleId: 'x' },
          { label: '站内', route: '/finance/fund-analysis', moduleId: '12.7.2.1' },
        ],
      }),
    );
    expect(view.nextActions).toHaveLength(1);
    expect(view.nextActions[0]?.route).toBe('/finance/fund-analysis');
  });
});

describe('命中方式与时间范围的标签', () => {
  it('命中方式有中文标签与语气', () => {
    expect(matchModeLabel('KEYWORD')).toBe('命中受控意图');
    expect(matchModeLabel('FOLLOW_UP')).toBe('追问（沿用上一轮）');
    expect(matchModeLabel('FREE_TEXT')).toBe('自由问句');
    expect(matchModeTone('KEYWORD')).toBe('ok');
    expect(matchModeTone('FOLLOW_UP')).toBe('warn');
    expect(matchModeTone('FREE_TEXT')).toBe('muted');
  });

  it('时间范围有中文标签', () => {
    expect(periodLabel('LAST_WEEK')).toContain('上周');
    expect(periodLabel('THIS_MONTH')).toBe('本月');
    expect(periodLabel('LAST_7_DAYS')).toBe('最近 7 天');
    expect(periodLabel('LAST_30_DAYS')).toBe('最近 30 天');
  });
});

describe('事实定位的可读描述', () => {
  it('给出服务 / 真表名 / 真字段名', () => {
    expect(factLocationLabel(evidence())).toBe('reporting · reporting_profit_fact · amount');
  });

  it('明细链接与引用各有各的说法', () => {
    expect(detailLinkLabel(evidence())).toBe('打开该店的利润明细');
    expect(detailLinkLabel(evidence({ detailLabel: '' }))).toBe('打开明细');
    expect(factRefLabel(evidence())).toBe('引用 SHOP-A');
    expect(factRefLabel(evidence({ factRef: '' }))).toBe('');
  });
});
