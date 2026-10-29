import { describe, expect, it } from 'vitest';
import {
  attributionEmptyText,
  attributionExportUrl,
  attributionMetricHeader,
  attributionModelText,
  attributionMoney,
  attributionQuantity,
  attributionRatio,
  attributionRatioTone,
  attributionStatusText,
  attributionStatusTone,
  attributionTimestampText,
  metricDefinitionOf,
  parseImportPayload,
  toNumber,
  unavailableMetricText,
  validateAttributionRange,
  validateAttributionRule,
} from './ad-attribution-model';
import type { AdAttributionMetricDefinition } from './ad-attribution-model';

/**
 * 14.3.2.1 归因分析 前端模型测试。
 *
 * 🔴 这些断言盯的是前端最容易做丢的几件事：
 * 不可比 ≠ 0、归因状态要能一眼分开、归因模型要说人话、空态的几种「空」不能混、
 * 导入载荷解析失败要说清哪一行坏了。
 */

describe('toNumber', () => {
  it('null 保持 null（不可比），不是 0', () => {
    expect(toNumber(null)).toBeNull();
    expect(toNumber(undefined)).toBeNull();
    expect(toNumber('')).toBeNull();
  });

  it('数字串与数字都读成 number', () => {
    expect(toNumber(12.5)).toBe(12.5);
    expect(toNumber('12.5')).toBe(12.5);
  });

  it('非数字串当 null，不当 0', () => {
    expect(toNumber('abc')).toBeNull();
    expect(toNumber(Number.NaN)).toBeNull();
  });
});

describe('金额 / 数量展示', () => {
  it('金额带币种', () => {
    expect(attributionMoney(30, 'USD')).toBe('30.00 USD');
  });

  it('读不出来显示「—」，不是 0.00', () => {
    expect(attributionMoney(null, 'USD')).toBe('—');
    expect(attributionMoney(undefined)).toBe('—');
  });

  it('0 是「可比但真的是零」，显示 0.00', () => {
    expect(attributionMoney(0, 'USD')).toBe('0.00 USD');
  });

  it('数量最多两位小数', () => {
    expect(attributionQuantity(12.3456)).toBe('12.35');
    expect(attributionQuantity(null)).toBe('—');
  });
});

describe('比率展示：不可比 ≠ 0', () => {
  it('null 显示「不可比」', () => {
    expect(attributionRatio(null)).toBe('不可比');
    expect(attributionRatio(undefined)).toBe('不可比');
  });

  it('0 显示 0.00%（可比但真的是零）', () => {
    expect(attributionRatio(0)).toBe('0.00%');
  });

  it('比率按百分比展示，可切换成小数', () => {
    expect(attributionRatio(0.15)).toBe('15.00%');
    expect(attributionRatio(0.15, false)).toBe('0.1500');
  });

  it('不可比的语气是弱化色，可比的是正常色', () => {
    expect(attributionRatioTone(null)).toBe('text-muted-foreground');
    expect(attributionRatioTone(0)).toBe('text-foreground');
  });
});

describe('归因状态与模型', () => {
  it('归因成立与归因不成立在语气上分开', () => {
    expect(attributionStatusText('ATTRIBUTED')).toBe('归因成立');
    expect(attributionStatusTone('ATTRIBUTED')).toBe('text-emerald-600');
    expect(attributionStatusTone('NO_ELIGIBLE_CLICK')).toBe('text-amber-600');
    expect(attributionStatusTone('NO_PLATFORM_ATTRIBUTED_CLICK')).toBe('text-amber-600');
  });

  it('两种归因模型都说人话', () => {
    expect(attributionModelText('LAST_CLICK')).toBe('末次点击');
    expect(attributionModelText('PLATFORM_WINDOW')).toBe('平台归因窗口');
    expect(attributionModelText('')).toBe('—');
  });
});

describe('空态：几种「空」不能混', () => {
  it('没导入数据 ≠ 没跑归因 ≠ 没有生效规则', () => {
    expect(attributionEmptyText('NO_IMPORTED_DATA', '')).toContain('还没导入');
    expect(attributionEmptyText('NO_FACTS_FOR_RULE', '')).toContain('归因计算');
    expect(attributionEmptyText('NO_ACTIVE_RULE', '')).toContain('生效的归因规则');
    expect(attributionEmptyText('FILTERED_EMPTY', '')).toContain('筛选条件');
  });

  it('没有原因时返回空串', () => {
    expect(attributionEmptyText(null, '')).toBe('');
  });

  it('服务端给了说明就用服务端的', () => {
    expect(attributionEmptyText('NO_IMPORTED_DATA', '服务端的话')).toBe('服务端的话');
  });
});

describe('指标口径定义', () => {
  const definitions: AdAttributionMetricDefinition[] = [
    {
      metric: 'tacos',
      label: 'TACOS',
      available: false,
      formula: 'TACOS = Σ广告花费 / 总销售额',
      source: '—',
      ownerModule: '13.4.1.2（reporting 服务）',
      unavailableReason: '上游这次没读到利润事实。',
    },
  ];

  it('按指标键找定义', () => {
    expect(metricDefinitionOf(definitions, 'tacos')?.label).toBe('TACOS');
    expect(metricDefinitionOf(definitions, 'nope')).toBeUndefined();
  });

  it('表头带口径公式', () => {
    expect(attributionMetricHeader(definitions[0])).toBe('TACOS（TACOS = Σ广告花费 / 总销售额）');
    expect(attributionMetricHeader(undefined)).toBe('');
  });

  it('未接入的指标写明责任模块与原因，不是 0', () => {
    const text = unavailableMetricText(definitions[0]);
    expect(text).toContain('未接入');
    expect(text).toContain('13.4.1.2（reporting 服务）');
    expect(text).toContain('上游这次没读到利润事实。');
  });
});

describe('时间范围校验', () => {
  it('缺一端都不行', () => {
    expect(validateAttributionRange('', '2026-10-01').ok).toBe(false);
    expect(validateAttributionRange('2026-10-01', '').ok).toBe(false);
  });

  it('起止颠倒不行', () => {
    const result = validateAttributionRange('2026-10-02', '2026-10-01');
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('不能晚于');
  });

  it('正常区间通过', () => {
    expect(validateAttributionRange('2026-10-01', '2026-10-31').ok).toBe(true);
  });
});

describe('归因规则表单校验', () => {
  it('规则编码不能为空', () => {
    const result = validateAttributionRule({
      ruleCode: '  ',
      attributionModel: 'LAST_CLICK',
      attributionWindowDays: 30,
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('规则编码');
  });

  it('归因模型只能是两种', () => {
    const result = validateAttributionRule({
      ruleCode: 'R',
      attributionModel: 'SOMETHING',
      attributionWindowDays: 30,
    });
    expect(result.ok).toBe(false);
    expect(result.errors.join()).toContain('归因模型');
  });

  it('归因窗口必须在 0 ~ 365 天之间', () => {
    expect(
      validateAttributionRule({
        ruleCode: 'R',
        attributionModel: 'LAST_CLICK',
        attributionWindowDays: 366,
      }).ok,
    ).toBe(false);
    expect(
      validateAttributionRule({
        ruleCode: 'R',
        attributionModel: 'LAST_CLICK',
        attributionWindowDays: -1,
      }).ok,
    ).toBe(false);
    expect(
      validateAttributionRule({
        ruleCode: 'R',
        attributionModel: 'LAST_CLICK',
        attributionWindowDays: 0,
      }).ok,
    ).toBe(true);
  });
});

describe('导入载荷解析', () => {
  it('合法载荷能解析出来', () => {
    const parsed = parseImportPayload(
      JSON.stringify({
        dataAsOf: '2026-10-01T09:00:00+08:00',
        orders: [
          {
            externalOrderNo: 'ORD-1',
            orderAt: '2026-10-01T10:00:00+08:00',
            currency: 'USD',
            attributedSpend: 10,
          },
        ],
      }),
    );
    expect(parsed.ok).toBe(true);
    expect(parsed.request?.orders).toHaveLength(1);
  });

  it('非法 JSON 要说清原因，不能默默变成空数组', () => {
    const parsed = parseImportPayload('{不是 json');
    expect(parsed.ok).toBe(false);
    expect(parsed.errors.join()).toContain('不是合法 JSON');
    expect(parsed.request).toBeNull();
  });

  it('缺 orders 或 dataAsOf 都要报出来', () => {
    const noOrders = parseImportPayload(JSON.stringify({ dataAsOf: '2026-10-01T09:00:00+08:00' }));
    expect(noOrders.ok).toBe(false);
    expect(noOrders.errors.join()).toContain('orders');

    const noAsOf = parseImportPayload(JSON.stringify({ orders: [] }));
    expect(noAsOf.ok).toBe(false);
    expect(noAsOf.errors.join()).toContain('dataAsOf');
  });
});

describe('导出链接', () => {
  it('走 GET + query，筛选项原样带上', () => {
    const url = attributionExportUrl({
      from: '2026-10-01',
      to: '2026-10-31',
      sort: 'SPEND_DESC',
    });
    expect(url).toContain('/api/channel/ad-attributions/export?');
    expect(url).toContain('from=2026-10-01');
    expect(url).toContain('to=2026-10-31');
    expect(url).toContain('sort=SPEND_DESC');
  });

  it('空筛选项不进 query', () => {
    expect(attributionExportUrl()).toBe('/api/channel/ad-attributions/export');
  });
});

describe('时间戳展示', () => {
  it('空值显示「—」', () => {
    expect(attributionTimestampText(null)).toBe('—');
  });

  it('非法时间串原样返回', () => {
    expect(attributionTimestampText('不是时间')).toBe('不是时间');
  });
});
