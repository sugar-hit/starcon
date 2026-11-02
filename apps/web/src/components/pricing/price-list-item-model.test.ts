import { describe, expect, it } from 'vitest';

import {
  actionText,
  actionTone,
  batchModeOptions,
  batchScopeOptions,
  batchStats,
  conflictWindowText,
  effectiveText,
  effectiveTone,
  emptyBatchRequest,
  emptyPriceListItemInput,
  formatInstant,
  importStatusText,
  importStatusTone,
  intervalText,
  priceText,
  priorityText,
  quantityText,
  selectionTone,
  toBatchPayload,
  toPriceListItemInput,
  toPriceListItemPayload,
  validateBatchRequest,
  validatePriceListItem,
} from './price-list-item-model';
import type {
  PriceListItemBatchPreview,
  PriceListItemConflict,
  PriceListItemInput,
  PriceListItemView,
} from './price-list-item-model';

/**
 * 5.1.1.2 价目项前端模型的纯函数断言。
 *
 * <p>这些函数守的是蓝图那条加粗句的**技术落点**：
 * 「**同优先级多条有效价目必须报错让用户选，不随机取**」，
 * 以及紧挨着它的两条纪律：
 *
 * <ul>
 *   <li>🔴 价目项**没有币种** —— 表单里不能出现币种输入框（币种来自价目表）；
 *   <li>🔴 **阶梯 ≠ 冲突** —— 数量门槛不同的多行是阶梯价，不是歧义。
 * </ul>
 */

function item(overrides: Partial<PriceListItemView> = {}): PriceListItemView {
  return {
    id: '22222222-2222-2222-2222-222222222222',
    priceListId: '11111111-1111-1111-1111-111111111111',
    priceListCode: 'NA-RETAIL',
    currency: 'USD',
    skuId: '33333333-3333-3333-3333-333333333333',
    skuCode: 'SKU-001',
    skuName: '陶瓷杯',
    unitPrice: 19.99,
    minQuantity: 0,
    priority: 0,
    priorityLabel: '0（数字越小越优先）',
    validFrom: '2026-01-01T00:00:00Z',
    validTo: null,
    enabled: true,
    effectiveNow: true,
    note: '',
    version: 0,
    createdBy: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

describe('价目项表单', () => {
  it('表单里没有币种字段 —— 币种来自价目表', () => {
    const form = emptyPriceListItemInput();
    expect(Object.keys(form)).not.toContain('currency');
  });

  it('SKU 编码必填且形状合法', () => {
    expect(validatePriceListItem(emptyPriceListItemInput())?.field).toBe('skuCode');
    expect(
      validatePriceListItem({
        ...emptyPriceListItemInput(),
        skuCode: '-bad',
        unitPrice: 1,
      })?.field,
    ).toBe('skuCode');
    expect(
      validatePriceListItem({ ...emptyPriceListItemInput(), skuCode: 'SKU-1', unitPrice: 1 }),
    ).toBeNull();
  });

  it('单价不能是负数，数量门槛不能是负数', () => {
    const base = { ...emptyPriceListItemInput(), skuCode: 'SKU-1' };
    expect(validatePriceListItem({ ...base, unitPrice: -1 })?.field).toBe('unitPrice');
    expect(validatePriceListItem({ ...base, unitPrice: null })?.field).toBe('unitPrice');
    expect(validatePriceListItem({ ...base, unitPrice: 1, minQuantity: -1 })?.field).toBe(
      'minQuantity',
    );
  });

  it('生效区间是半开区间：终点必须晚于起点', () => {
    const base = { ...emptyPriceListItemInput(), skuCode: 'SKU-1', unitPrice: 1 };
    expect(
      validatePriceListItem({
        ...base,
        validFrom: '2026-02-01T00:00:00Z',
        validTo: '2026-01-01T00:00:00Z',
      })?.field,
    ).toBe('validTo');
    expect(
      validatePriceListItem({
        ...base,
        validFrom: '2026-01-01T00:00:00Z',
        validTo: '2026-01-01T00:00:00Z',
      })?.field,
    ).toBe('validTo');
  });

  it('提交时 SKU 编码转大写、备注去空白', () => {
    const payload = toPriceListItemPayload({
      ...emptyPriceListItemInput(),
      skuCode: '  sku-001 ',
      unitPrice: 1,
      note: '  备注 ',
    });
    expect(payload.skuCode).toBe('SKU-001');
    expect(payload.note).toBe('备注');
  });

  it('详情 → 表单会带回乐观锁版本', () => {
    const form = toPriceListItemInput(item({ version: 7, minQuantity: 100 }));
    expect(form.version).toBe(7);
    expect(form.minQuantity).toBe(100);
  });
});

describe('批量请求校验', () => {
  it('固定值必须给单价', () => {
    const request = { ...emptyBatchRequest(), mode: 'FIXED' as const, category: '家居' };
    expect(validateBatchRequest(request)?.field).toBe('unitPrice');
    expect(validateBatchRequest({ ...request, unitPrice: 9.9 })).toBeNull();
  });

  it('按百分比必须给涨跌幅，且不能小于等于 -100%', () => {
    const request = {
      ...emptyBatchRequest(),
      mode: 'PERCENTAGE' as const,
      scope: 'BRAND' as const,
      brand: 'ACME',
    };
    expect(validateBatchRequest(request)?.field).toBe('percent');
    expect(validateBatchRequest({ ...request, percent: -100 })?.field).toBe('percent');
    expect(validateBatchRequest({ ...request, percent: 10 })).toBeNull();
  });

  it('按类目统一必须给分类与价格清单', () => {
    const request = { ...emptyBatchRequest(), mode: 'CATEGORY_UNIFIED' as const };
    expect(validateBatchRequest(request)?.field).toBe('categoryPrices');
    expect(
      validateBatchRequest({
        ...request,
        categoryPrices: [{ category: '家居', unitPrice: 5 }],
      }),
    ).toBeNull();
  });

  it('按 SKU 清单必须至少给一个编码', () => {
    const request = {
      ...emptyBatchRequest(),
      mode: 'FIXED' as const,
      scope: 'SKU_SET' as const,
      unitPrice: 1,
    };
    expect(validateBatchRequest(request)?.field).toBe('skuCodes');
    expect(validateBatchRequest({ ...request, skuCodes: ['SKU-1'] })).toBeNull();
  });

  it('提交时 SKU 清单转大写并丢掉空行', () => {
    const payload = toBatchPayload({
      ...emptyBatchRequest(),
      scope: 'SKU_SET',
      skuCodes: ['  sku-1 ', '', 'SKU-2'],
    });
    expect(payload.skuCodes).toEqual(['SKU-1', 'SKU-2']);
  });
});

describe('展示口径', () => {
  it('数量门槛 0 = 基础档，不是「数量 0」', () => {
    expect(quantityText(0)).toBe('基础档');
    expect(quantityText(null)).toBe('基础档');
    expect(quantityText(100)).toBe('100');
  });

  it('优先级写明「数字越小越优先」', () => {
    expect(priorityText(1)).toContain('越小越优先');
    expect(priorityText(null)).toContain('越小越优先');
  });

  it('单价带币种显示，且 null 显示为破折号', () => {
    expect(priceText(19.99, 'USD')).toBe('19.99 USD');
    expect(priceText(20, 'USD')).toBe('20 USD');
    expect(priceText(null, 'USD')).toBe('—');
  });

  it('生效区间是半开区间：终点写「不含」', () => {
    expect(intervalText('2026-01-01T00:00:00Z', null)).toContain('长期有效');
    expect(intervalText('2026-01-01T00:00:00Z', '2026-02-01T00:00:00Z')).toContain('不含');
  });

  it('选择状态：歧义是 danger、默认采用是 success', () => {
    expect(selectionTone('AMBIGUOUS')).toBe('danger');
    expect(selectionTone('SELECTED')).toBe('success');
    expect(selectionTone('NOT_APPLICABLE')).toBe('neutral');
  });

  it('批量动作与导入状态的文案', () => {
    expect(actionText('CREATE')).toContain('新增');
    expect(actionText('UPDATE')).toContain('覆盖');
    expect(actionText('SKIP')).toBe('跳过');
    expect(actionTone('SKIP')).toBe('warning');
    expect(importStatusText('APPLIED')).toBe('已落库');
    expect(importStatusText('PREVIEW')).toContain('未落库');
    expect(importStatusTone('REJECTED')).toBe('danger');
  });

  it('模式与范围的选项是三三之数', () => {
    expect(batchModeOptions().map((o) => o.value)).toEqual([
      'FIXED',
      'PERCENTAGE',
      'CATEGORY_UNIFIED',
    ]);
    expect(batchScopeOptions().map((o) => o.value)).toEqual(['CATEGORY', 'BRAND', 'SKU_SET']);
  });

  it('生效状态与时刻格式化', () => {
    expect(effectiveText(true)).toBe('此刻生效');
    expect(effectiveText(null)).toBe('生效状态未知');
    expect(effectiveTone(false)).toBe('neutral');
    expect(formatInstant(null)).toBe('—');
    expect(formatInstant('2026-01-01T00:00:00Z')).toContain('2026-01-01');
  });
});

describe('批量预览与冲突', () => {
  const preview: PriceListItemBatchPreview = {
    mode: 'FIXED',
    modeLabel: '固定值',
    scope: 'CATEGORY',
    scopeLabel: '按分类',
    currency: 'USD',
    matchedSkus: 12,
    createdItems: 10,
    updatedItems: 2,
    skippedSkus: 1,
    conflictSkus: 3,
    lines: [],
    lineLimit: 500,
    truncated: false,
    conflicts: [],
    warnings: [],
    summary: '命中 12 个 SKU',
  };

  it('统计原样显示服务端给的数，前端不重算', () => {
    const stats = batchStats(preview);
    expect(stats.map((s) => s.value)).toEqual([12, 10, 2, 1, 3]);
    expect(batchStats(null)).toEqual([]);
  });

  it('冲突窗口写明「无法唯一解析」', () => {
    const conflict: PriceListItemConflict = {
      skuId: '33333333-3333-3333-3333-333333333333',
      skuCode: 'SKU-001',
      skuName: '陶瓷杯',
      minQuantity: 0,
      priority: 0,
      overlapFrom: '2026-01-01T00:00:00Z',
      overlapTo: '2026-02-01T00:00:00Z',
      candidates: [],
    };
    expect(conflictWindowText(conflict)).toContain('无法唯一解析');
    expect(conflictWindowText({ ...conflict, overlapTo: null })).toContain('长期有效');
  });
});
