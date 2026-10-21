import { describe, expect, it } from 'vitest';

import {
  EMPTY_ITEM_FILTERS,
  EMPTY_STALL_FILTERS,
  collectedAtText,
  collectionStatusText,
  collectionStatusTone,
  emptyCollectedItem,
  emptyStallForm,
  moqText,
  priceRangeText,
  priceText,
  validateCollectedItem,
  validateCollectorEndpoint,
  validateCurrency,
  validateStall,
} from './sources-model';
import type { CollectedItemInput } from './sources-model';

/**
 * 6.1.2.1 档口与货源池前端模型的**口径**断言。
 *
 * <p>这些断言盯的是蓝图加粗句「货源数据是外部快照，会变」在界面上有没有守住：
 *
 * <ul>
 *   <li>价格**永远是区间**（下限 ~ 上限），没有一处被压成一个「精确价」；
 *   <li>采集时刻**必须显示**（空值显示「从未采集」，不是空白）；
 *   <li>失败状态**必须带原因**，不显示成空列表；
 *   <li>价格区间写反 / 起订量非法 / 凭证不是引用 → 本地拦一次（后端与 DB CHECK 各再拦一道）。
 * </ul>
 */

describe('价格永远是区间', () => {
  it('区间显示成「下限 ~ 上限」，不压成精确价', () => {
    expect(priceRangeText(12.5, 18.8, 'CNY')).toBe('CNY 12.5 ~ 18.8');
  });

  it('上下限相同时显示成一个数（那确实是精确价）', () => {
    expect(priceRangeText(20, 20, 'CNY')).toBe('CNY 20');
  });

  it('缺上限时显示下限，不编一个上限出来', () => {
    expect(priceRangeText(20, null, 'CNY')).toBe('CNY 20');
  });

  it('缺下限时显示「—」，不用 0 冒充', () => {
    expect(priceRangeText(null, 20, 'CNY')).toBe('—');
  });

  it('金额保留最多 4 位小数，不补零也不丢精度', () => {
    expect(priceText(12.5)).toBe('12.5');
    expect(priceText(12)).toBe('12');
    expect(priceText(0.12345)).toBe('0.1235');
    expect(priceText(null)).toBe('—');
  });
});

describe('起订量与采集时刻', () => {
  it('起订量带「起」字，缺值显示「—」', () => {
    expect(moqText(2)).toBe('2 起');
    expect(moqText(2.5)).toBe('2.5 起');
    expect(moqText(null)).toBe('—');
  });

  it('从未采集显示「从未采集」，不是空白', () => {
    expect(collectedAtText(null)).toBe('从未采集');
    expect(collectedAtText('2026-10-21T02:30:00Z')).toBe('2026-10-21 02:30');
  });
});

describe('采集结果显示失败原因', () => {
  it('失败必须带原因', () => {
    expect(collectionStatusText('FAILED', '1688 开放平台暂时不可达')).toContain(
      '1688 开放平台暂时不可达',
    );
  });

  it('失败没有原因时也要说「未记录原因」，不能显示成空', () => {
    expect(collectionStatusText('FAILED', null)).toContain('未记录原因');
  });

  it('从未采集有专门的显示串', () => {
    expect(collectionStatusText(null, null)).toBe('从未采集');
  });

  it('状态色：成功 success / 失败 warning / 未采集 neutral', () => {
    expect(collectionStatusTone('SUCCEEDED')).toBe('success');
    expect(collectionStatusTone('FAILED')).toBe('warning');
    expect(collectionStatusTone(null)).toBe('neutral');
  });
});

describe('本地校验', () => {
  function item(overrides: Partial<CollectedItemInput> = {}): CollectedItemInput {
    return {
      ...emptyCollectedItem(),
      platformItemId: 'I-1',
      title: '女士通勤包',
      priceMin: '12.5',
      priceMax: '18.8',
      moq: '2',
      ...overrides,
    };
  }

  it('完整的一条货源没有校验问题', () => {
    expect(validateCollectedItem(item())).toEqual([]);
  });

  it('价格区间写反要被拦下（外部货源是阶梯价）', () => {
    const problems = validateCollectedItem(item({ priceMin: '18.8', priceMax: '12.5' }));
    expect(problems.join('；')).toContain('价格上限不能低于下限');
  });

  it('缺下限或缺上限都要被拦下（区间不能只存一个）', () => {
    expect(validateCollectedItem(item({ priceMin: '' })).join('；')).toContain('价格下限必填');
    expect(validateCollectedItem(item({ priceMax: '' })).join('；')).toContain('价格上限必填');
  });

  it('起订量必须大于 0', () => {
    expect(validateCollectedItem(item({ moq: '0' })).join('；')).toContain('起订量必须大于 0');
  });

  it('币种要三位大写', () => {
    expect(validateCurrency('cny')).toEqual([]);
    expect(validateCurrency('CN').join('；')).toContain('ISO 4217');
  });

  it('缺平台货源 ID 或标题要被拦下', () => {
    expect(validateCollectedItem(item({ platformItemId: '' })).join('；')).toContain(
      '平台货源 ID 必填',
    );
    expect(validateCollectedItem(item({ title: '' })).join('；')).toContain('货源标题必填');
  });
});

describe('档口与采集接入的本地校验', () => {
  it('完整的新建档口没有校验问题', () => {
    expect(
      validateStall({
        ...emptyStallForm(),
        code: 'S-001',
        platformStallId: 'SHOP-001',
        name: '义乌老王档口',
      }),
    ).toEqual([]);
  });

  it('缺编码 / 平台档口 ID / 名称要被拦下', () => {
    const problems = validateStall({ ...emptyStallForm() });
    expect(problems.join('；')).toContain('档口编码必填');
    expect(problems.join('；')).toContain('平台档口 ID 必填');
    expect(problems.join('；')).toContain('档口名称必填');
  });

  it('凭证只接受引用：粘明文要被拦下', () => {
    const problems = validateCollectorEndpoint({
      baseUrl: 'http://example.internal',
      credentialRef: 'sk-live-abcdefg',
      wired: true,
      note: '',
    });
    expect(problems.join('；')).toContain('凭证只能是引用');
  });

  it('声称已接线就必须同时给出地址与引用', () => {
    const problems = validateCollectorEndpoint({
      baseUrl: '',
      credentialRef: '',
      wired: true,
      note: '',
    });
    expect(problems.join('；')).toContain('网关地址');
    expect(problems.join('；')).toContain('凭证引用');
  });

  it('未接线时留空是允许的（那正是「未接线」的状态）', () => {
    expect(
      validateCollectorEndpoint({ baseUrl: '', credentialRef: '', wired: false, note: '' }),
    ).toEqual([]);
  });
});

describe('筛选初值', () => {
  it('空筛选不带任何条件', () => {
    expect(EMPTY_STALL_FILTERS.page).toBe(0);
    expect(EMPTY_ITEM_FILTERS.page).toBe(0);
    expect(EMPTY_ITEM_FILTERS.onSale).toBe('ALL');
  });

  it('新建档口默认用插件采集（不需要任何外部凭证）', () => {
    expect(emptyStallForm().collectMethod).toBe('PLUGIN_PUSH');
  });
});
