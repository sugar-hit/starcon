import { describe, expect, it } from 'vitest';

import { RATE_SECTIONS } from './exchange-rate-model';
import {
  EMPTY_SNAPSHOT_FILTERS,
  amountText,
  dayText,
  directionLabel,
  directionTone,
  documentTypeLabel,
  replayTone,
  roundingModeLabel,
  snapshotConversionText,
  snapshotRateText,
  timeText,
  validateRoundingRuleInput,
  validateSnapshotInput,
} from './exchange-rate-snapshot-model';
import type { SnapshotInput, SnapshotRow } from './exchange-rate-snapshot-model';

/**
 * 5.2.2.2 汇率快照前端模型的纯函数断言。
 *
 * <p>这些函数守着蓝图加粗句「**汇率快照是利润可追溯的前提**：今天汇率变了不能让上个月的利润报表数字跟着变」
 * 在**显示侧**的落点：界面上任何一处显示汇率与金额都要看得见方向与换算过程，
 * 复算结论要一眼分得开。
 */

/** 一条典型的冻结快照（方向显式、换算过程完整）。 */
function row(overrides: Partial<SnapshotRow> = {}): SnapshotRow {
  return {
    id: 's1',
    documentType: 'PRICE_LIST',
    documentTypeLabel: '价目表',
    documentId: 'd1',
    documentNo: 'PL-1',
    lineKey: 'L1',
    sourceCurrency: 'USD',
    sourceAmount: 100,
    targetCurrency: 'CNY',
    targetAmount: 712.34,
    rateDisplay: '1 USD = 7.1234 CNY',
    rateId: 'r1',
    rateBaseCurrency: 'USD',
    rateQuoteCurrency: 'CNY',
    rate: 7.1234,
    conversionDirection: 'BASE_TO_QUOTE',
    conversionDirectionLabel: '基准币→报价币',
    conversionDirectionBasis: '目标币金额 = 原价 × 汇率。',
    rateEffectiveDate: '2026-10-20',
    rateSourceCode: 'MANUAL',
    roundingRuleCode: 'CNY',
    roundingMode: 'HALF_UP',
    roundingModeLabel: '四舍五入',
    roundingScale: 2,
    conversionDisplay: '100 USD × 7.1234 = 712.34 CNY（HALF_UP · 2 位小数）',
    frozenAt: '2026-10-20T02:00:00Z',
    createdAt: '2026-10-20T02:00:00Z',
    note: '',
    ...overrides,
  };
}

describe('换算过程必须显示成整句', () => {
  it('优先用后端给的整句', () => {
    expect(snapshotConversionText(row())).toContain('×');
    expect(snapshotConversionText(row())).toContain('712.34');
  });

  it('后端没给整句时仍然按字段拼出带方向的句子', () => {
    const withoutDisplay = row();
    delete (withoutDisplay as { conversionDisplay?: string }).conversionDisplay;
    const text = snapshotConversionText(withoutDisplay);
    expect(text).toContain('USD');
    expect(text).toContain('×');
    expect(text).toContain('712.34');
    expect(text).toContain('CNY');
    // 任何情况下都不出现裸的汇率数字当作整句。
    expect(text).not.toBe('712.34');
  });

  it('反向换算显示除号而不是乘号', () => {
    const inverse = row({
      conversionDirection: 'QUOTE_TO_BASE',
      sourceCurrency: 'CNY',
      targetCurrency: 'USD',
      targetAmount: 14.04,
      conversionDisplay: '',
    });
    expect(snapshotConversionText(inverse)).toContain('÷');
    expect(snapshotConversionText(inverse)).toContain('14.04');
  });

  it('空行显示破折号而不是 0', () => {
    expect(snapshotConversionText(null)).toBe('—');
    expect(snapshotConversionText({})).toBe('—');
  });
});

describe('冻结的汇率必须带方向', () => {
  it('优先用后端给的整句', () => {
    expect(snapshotRateText(row())).toBe('1 USD = 7.1234 CNY');
  });

  it('后端没给 display 时仍然按 base/quote 拼出带方向的整句', () => {
    const withoutDisplay = row();
    delete (withoutDisplay as { rateDisplay?: string }).rateDisplay;
    expect(snapshotRateText(withoutDisplay)).toBe('1 USD = 7.1234 CNY');
    expect(snapshotRateText(withoutDisplay)).not.toBe('7.1234');
  });

  it('缺字段时显示「未冻结」而不是 0', () => {
    expect(snapshotRateText(null)).toBe('未冻结');
    expect(snapshotRateText({})).toBe('未冻结');
  });
});

describe('金额显示', () => {
  it('去掉小数尾零但保留数值', () => {
    expect(amountText(712.34)).toBe('712.34');
    expect(amountText(100)).toBe('100');
    expect(amountText(15000)).toBe('15000');
  });

  it('缺值显示破折号而不是 0', () => {
    expect(amountText(null)).toBe('—');
    expect(amountText(undefined)).toBe('—');
  });
});

describe('词表与色标', () => {
  it('单据类型、方向、舍入方式都有中文名', () => {
    expect(documentTypeLabel('PRICE_LIST')).toBe('价目表');
    expect(documentTypeLabel('ORDER')).toBe('订单');
    expect(documentTypeLabel('NOT_A_DOC')).toBe('NOT_A_DOC');
    expect(directionLabel('BASE_TO_QUOTE')).toContain('乘');
    expect(directionLabel('QUOTE_TO_BASE')).toContain('除');
    expect(roundingModeLabel('HALF_UP')).toBe('四舍五入');
    expect(roundingModeLabel('FLOOR')).toBe('向下取整');
  });

  it('方向与复算结论有色标', () => {
    expect(directionTone('BASE_TO_QUOTE')).toBe('success');
    expect(directionTone('QUOTE_TO_BASE')).toBe('info');
    expect(directionTone(null)).toBe('neutral');
    expect(replayTone(true)).toBe('success');
    expect(replayTone(false)).toBe('danger');
  });

  it('时间显示', () => {
    expect(dayText('2026-10-20T00:00:00Z')).toBe('2026-10-20');
    expect(dayText(null)).toBe('—');
    expect(timeText('2026-10-20T02:00:00Z')).toContain('2026-10-20 02:00');
    expect(timeText(null)).toBe('未记录');
  });
});

describe('冻结快照的本地校验', () => {
  const base: SnapshotInput = {
    rateId: '',
    baseCurrency: 'USD',
    quoteCurrency: 'CNY',
    asOf: '2026-10-20',
    sourceCurrency: 'USD',
    sourceAmount: '100.00',
    documentType: 'PRICE_LIST',
    documentId: 'd1',
    documentNo: 'PL-1',
    lineKey: 'L1',
    roundingRuleCode: '',
    note: '',
  };

  it('必须指明要冻结的汇率', () => {
    expect(
      validateSnapshotInput({ ...base, baseCurrency: '', quoteCurrency: '' }).join(),
    ).toContain('必须指明要冻结的汇率');
    // 给了 rateId 就不报这条。
    expect(validateSnapshotInput({ ...base, rateId: 'r1' })).not.toContain(
      '必须指明要冻结的汇率',
    );
  });

  it('原价币种必须是三位字母码', () => {
    expect(validateSnapshotInput({ ...base, sourceCurrency: 'US' }).join()).toContain(
      '三位字母码',
    );
  });

  it('原价最多 6 位小数、必须是数字', () => {
    expect(validateSnapshotInput({ ...base, sourceAmount: '100.1234567' }).join()).toContain(
      '6 位小数',
    );
    expect(validateSnapshotInput({ ...base, sourceAmount: 'abc' }).join()).toContain('不是数字');
  });

  it('单据类型与单据 id 必填', () => {
    expect(validateSnapshotInput({ ...base, documentType: '' }).join()).toContain('单据类型必填');
    expect(validateSnapshotInput({ ...base, documentId: '' }).join()).toContain('单据 id 必填');
  });

  it('合法输入没有 problems', () => {
    expect(validateSnapshotInput(base)).toEqual([]);
  });
});

describe('舍入规则的本地校验', () => {
  it('代码、名称、模式与小数位都要挡', () => {
    expect(
      validateRoundingRuleInput({
        code: '',
        name: 'x',
        targetCurrency: '',
        roundingMode: 'HALF_UP',
        roundingScale: 2,
      }).join(),
    ).toContain('规则代码必填');
    expect(
      validateRoundingRuleInput({
        code: 'JPY',
        name: '',
        targetCurrency: '',
        roundingMode: 'HALF_UP',
        roundingScale: 2,
      }).join(),
    ).toContain('规则名称必填');
    expect(
      validateRoundingRuleInput({
        code: 'JPY',
        name: '日元',
        targetCurrency: '人民币',
        roundingMode: 'HALF_UP',
        roundingScale: 2,
      }).join(),
    ).toContain('三位大写币种代码');
    expect(
      validateRoundingRuleInput({
        code: 'JPY',
        name: '日元',
        targetCurrency: 'JPY',
        roundingMode: 'HALF_UP',
        roundingScale: 9,
      }).join(),
    ).toContain('0~6');
    expect(
      validateRoundingRuleInput({
        code: 'JPY',
        name: '日元',
        targetCurrency: 'JPY',
        roundingMode: 'HALF_UP',
        roundingScale: 0,
      }),
    ).toEqual([]);
  });
});

describe('分区与默认筛选', () => {
  it('5.2.2.2 汇率快照分区已交付（数据驱动断言，不写死交付状态的字面量）', () => {
    const snapshot = RATE_SECTIONS.find((s) => s.id === '5.2.2.2');
    expect(snapshot).toBeDefined();
    expect(snapshot?.delivered).toBe(true);
    expect(snapshot?.title).toBe('汇率快照');
    expect(snapshot?.description).toContain('冻结');
  });

  it('默认筛选是空的', () => {
    expect(EMPTY_SNAPSHOT_FILTERS.page).toBe(0);
    expect(EMPTY_SNAPSHOT_FILTERS.documentType).toBe('');
    expect(EMPTY_SNAPSHOT_FILTERS.size).toBeGreaterThan(0);
  });
});
