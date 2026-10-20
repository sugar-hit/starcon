import { describe, expect, it } from 'vitest';

import {
  EMPTY_RATE_FILTERS,
  RATE_SECTIONS,
  dayText,
  entryModeLabel,
  entryModeTone,
  fetchStatusLabel,
  fetchStatusTone,
  inverseText,
  rateDetailText,
  rateText,
  sourceKindLabel,
  timeText,
  validateRateInput,
  validateSourceInput,
} from './exchange-rate-model';
import type { RateRow } from './exchange-rate-model';

/**
 * 5.2.2.1 汇率查询前端模型的纯函数断言。
 *
 * <p>这些函数守着蓝图加粗句「**汇率方向必须显式**」的**显示侧**落点：
 * 界面上任何一处显示汇率都要看得出方向（1 USD = X CNY，而不是裸的 X），
 * 反向汇率必须标注为推导值。
 */

/** 一条典型的汇率记录（方向显式）。 */
function row(overrides: Partial<RateRow> = {}): RateRow {
  return {
    id: 'r1',
    baseCurrency: 'USD',
    quoteCurrency: 'CNY',
    rate: '7.1234',
    effectiveDate: '2026-10-20',
    sourceCode: 'MANUAL',
    sourceName: '手工录入',
    sourceKind: 'MANUAL',
    entryMode: 'MANUAL',
    entryModeLabel: '手工录入',
    sourceRef: '',
    note: '',
    acquiredAt: '2026-10-20T02:00:00Z',
    createdAt: '2026-10-20T02:00:00Z',
    updatedAt: '2026-10-20T02:00:00Z',
    display: '1 USD = 7.1234 CNY',
    inverseRate: '0.1403824',
    inverseDisplay: '1 CNY = 0.1403824 USD（由 1 USD = 7.1234 CNY 推导）',
    inverseDerived: true,
    ...overrides,
  };
}

describe('汇率显示必须带方向', () => {
  it('台账里显示的是整句而不是裸数字', () => {
    expect(rateText(row())).toBe('1 USD = 7.1234 CNY');
  });

  it('后端没给 display 时仍然按 base/quote 拼出带方向的整句', () => {
    const withoutDisplay = row();
    delete (withoutDisplay as { display?: string }).display;
    expect(rateText(withoutDisplay)).toBe('1 USD = 7.1234 CNY');
    // 任何情况下都不返回裸数字。
    expect(rateText(withoutDisplay)).not.toBe('7.1234');
  });

  it('方向相反的两条记录显示句完全不同', () => {
    const forward = rateText(row());
    const backward = rateText(
      row({ baseCurrency: 'CNY', quoteCurrency: 'USD', rate: '0.1403824', display: '' }),
    );
    expect(forward).not.toBe(backward);
    expect(backward).toContain('CNY');
    expect(backward).toContain('USD');
  });

  it('缺字段时显示「未登记」而不是 0', () => {
    expect(rateText(null)).toBe('未登记');
    expect(rateText({})).toBe('未登记');
  });

  it('明细同时给出正向与反向（反向带推导标注）', () => {
    const text = rateDetailText(row());
    expect(text).toContain('1 USD = 7.1234 CNY');
    expect(text).toContain('推导');
    expect(inverseText(row())).toContain('1 CNY = 0.1403824 USD');
  });
});

describe('词表', () => {
  it('来源类型与录入方式都有中文名', () => {
    expect(sourceKindLabel('MANUAL')).toBe('手工录入');
    expect(sourceKindLabel('EXTERNAL')).toBe('外部拉取');
    expect(sourceKindLabel(null)).toBe('未登记');
    expect(entryModeLabel('EXTERNAL')).toBe('外部拉取');
  });

  it('色标把手工与外部拉取分开', () => {
    expect(entryModeTone('MANUAL')).toBe('info');
    expect(entryModeTone('EXTERNAL')).toBe('success');
    expect(entryModeTone(null)).toBe('neutral');
  });

  it('拉取状态有中文名与色标', () => {
    expect(fetchStatusLabel('SUCCEEDED')).toBe('成功');
    expect(fetchStatusLabel('FAILED')).toBe('失败');
    expect(fetchStatusTone('FAILED')).toBe('danger');
    expect(fetchStatusTone('SUCCEEDED')).toBe('success');
  });

  it('时间显示', () => {
    expect(dayText('2026-10-20T00:00:00Z')).toBe('2026-10-20');
    expect(dayText(null)).toBe('—');
    expect(timeText('2026-10-20T02:00:00Z')).toContain('2026-10-20 02:00');
    expect(timeText(null)).toBe('未登记');
  });
});

describe('手工录入的本地校验', () => {
  const base = {
    baseCurrency: 'USD',
    quoteCurrency: 'CNY',
    rate: '7.1234',
    effectiveDate: '2026-10-20',
    sourceCode: 'MANUAL',
    sourceRef: '',
    note: '',
  };

  it('方向的两侧都必须填', () => {
    expect(validateRateInput({ ...base, baseCurrency: '' })).toContain(
      '基准币种必填：汇率方向必须显式，不能只填一个数',
    );
    expect(validateRateInput({ ...base, quoteCurrency: '' })).toContain(
      '报价币种必填：汇率方向必须显式，不能只填一个数',
    );
  });

  it('币种码必须是三位大写字母', () => {
    expect(validateRateInput({ ...base, baseCurrency: 'US' }).join()).toContain('三位字母码');
    expect(validateRateInput({ ...base, quoteCurrency: '人民币' }).join()).toContain('三位字母码');
  });

  it('本币对本币不需要汇率', () => {
    expect(validateRateInput({ ...base, quoteCurrency: 'USD' }).join()).toContain('本币对本币');
  });

  it('汇率必须大于 0 且最多 8 位小数', () => {
    expect(validateRateInput({ ...base, rate: '0' }).join()).toContain('大于 0');
    expect(validateRateInput({ ...base, rate: '-1' }).join()).toContain('大于 0');
    expect(validateRateInput({ ...base, rate: 'abc' }).join()).toContain('不是数字');
    expect(validateRateInput({ ...base, rate: '7.123456789' }).join()).toContain('8 位小数');
  });

  it('生效日与来源必填', () => {
    expect(validateRateInput({ ...base, effectiveDate: '' }).join()).toContain('生效日期必填');
    expect(validateRateInput({ ...base, sourceCode: '' }).join()).toContain('汇率来源必填');
  });

  it('合法输入没有 problems', () => {
    expect(validateRateInput(base)).toEqual([]);
  });
});

describe('来源配置校验', () => {
  it('代码与名称必填，代码只能是字母数字下划线', () => {
    expect(validateSourceInput({ code: '', name: 'x', kind: 'MANUAL', endpoint: '' }).join()).toContain(
      '来源代码必填',
    );
    expect(
      validateSourceInput({ code: 'a b', name: 'x', kind: 'MANUAL', endpoint: '' }).join(),
    ).toContain('2~32 位');
  });

  it('手工来源配地址是死配置，要拦住', () => {
    expect(
      validateSourceInput({
        code: 'MANUAL',
        name: '手工',
        kind: 'MANUAL',
        endpoint: 'https://example.invalid',
      }).join(),
    ).toContain('不应配地址');
  });

  it('外部来源可以先不配地址', () => {
    expect(
      validateSourceInput({ code: 'PBOC', name: '央行', kind: 'EXTERNAL', endpoint: '' }),
    ).toEqual([]);
  });
});

describe('分区', () => {
  it('汇率查询与汇率快照都已交付，且各写各的职责', () => {
    const delivered = RATE_SECTIONS.filter((s) => s.delivered).map((s) => s.id);
    expect(delivered).toEqual(['5.2.2.1', '5.2.2.2']);

    const rate = RATE_SECTIONS.find((s) => s.id === '5.2.2.1');
    expect(rate?.delivered).toBe(true);
    expect(rate?.title).toBe('汇率查询');

    // 断言「该分区 delivered 为 true」+「该分区写出了自己的职责」，
    // **不**断言任何关于交付状态的字面量文案：兄弟模块一旦交付那个字面量就会消失，
    // 写死会让别的车道一交付就红。
    const snapshot = RATE_SECTIONS.find((s) => s.id === '5.2.2.2');
    expect(snapshot?.delivered).toBe(true);
    expect(snapshot?.title).toBe('汇率快照');
    expect(snapshot?.description).toContain('冻结');
  });

  it('默认筛选是空的', () => {
    expect(EMPTY_RATE_FILTERS.page).toBe(0);
    expect(EMPTY_RATE_FILTERS.baseCurrency).toBe('');
    expect(EMPTY_RATE_FILTERS.size).toBeGreaterThan(0);
  });
});
