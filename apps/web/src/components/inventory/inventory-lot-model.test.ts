import { describe, expect, it } from 'vitest';
import {
  expiryText,
  lotOriginLabels,
  lotSortOptions,
  lotSourceSystemLabels,
  lotStatusLabels,
  sourceText,
} from './inventory-lot-model';

/**
 * 7.3.1.1 批次管理的展示口径。
 *
 * <p>这几个函数承载的是「同一个数字怎么读」的问题，不是格式化问题：
 * 过期批次的负数天数如果显示成「剩余 -2471 天」，用户会以为系统算错了；
 * 来源只显示 id 不显示单号，售后拿着采购单来查批次就还得再开一个系统。
 */
describe('批次有效期文案', () => {
  it('无失效日期显示为非效期品而不是空白或 0 天', () => {
    expect(expiryText(null, null)).toBe('非效期品');
  });

  it('已过期批次的剩余天数按正数表述', () => {
    expect(expiryText('2020-01-01', -2471)).toBe('2020-01-01（已过期 2471 天）');
  });

  it('今天到期与还有 N 天分开表述', () => {
    expect(expiryText('2026-10-07', 0)).toBe('2026-10-07（今天到期）');
    expect(expiryText('2026-12-31', 85)).toBe('2026-12-31（还有 85 天）');
  });

  it('有失效日期但天数缺失时仍给出日期本身', () => {
    expect(expiryText('2026-12-31', null)).toBe('2026-12-31');
  });
});

describe('批次来源文案', () => {
  it('来源系统与单号都显示，未知码原样透出而不是吞掉', () => {
    expect(
      sourceText({ sourceSystem: 'PURCHASE', sourceRefCode: 'PO-2026-001', supplierName: '' }),
    ).toBe('采购单 PO-2026-001');
    expect(sourceText({ sourceSystem: 'WMS', sourceRefCode: 'X-1', supplierName: '' })).toBe(
      'WMS X-1',
    );
  });

  it('有来源系统但没填单号时不留悬空的尾缀', () => {
    expect(sourceText({ sourceSystem: 'RECEIPT', sourceRefCode: '  ', supplierName: '' })).toBe(
      '收货单',
    );
  });

  it('来源与供应商同时存在时用间隔号连起来', () => {
    expect(
      sourceText({
        sourceSystem: 'PURCHASE',
        sourceRefCode: 'PO-1',
        supplierName: '苏州供应商',
      }),
    ).toBe('采购单 PO-1 · 供应商 苏州供应商');
  });

  it('既无来源也无供应商时如实说明是手工补录', () => {
    expect(sourceText({ sourceSystem: null, sourceRefCode: '', supplierName: '  ' })).toBe(
      '手工补录（无来源单据）',
    );
  });
});

describe('批次词表', () => {
  it('状态与建立方式都覆盖后端的 CHECK 约束取值', () => {
    // 后端 inventory_lot.status 的 CHECK 只放这三个；少一个界面上就筛不出该状态
    expect(Object.keys(lotStatusLabels).sort()).toEqual(['ACTIVE', 'DEPLETED', 'EXPIRED']);
    expect(Object.keys(lotOriginLabels).sort()).toEqual(['MANUAL', 'RECEIPT']);
    expect(Object.keys(lotSourceSystemLabels).sort()).toEqual(['MANUAL', 'PURCHASE', 'RECEIPT']);
  });

  it('排序键都在后端白名单内，非法值会让接口返回 400', () => {
    expect(lotSortOptions.map((o) => o.value)).toContain('EXPIRY');
    expect(lotSortOptions.every((o) => o.value !== 'DROP_TABLE')).toBe(true);
  });
});
