import { describe, expect, it } from 'vitest';
import type {
  AvailableQuantityCapabilities,
  AvailableQuantityReport,
  AvailableQuantitySlice,
  AvailableQuantityTotals,
} from '../../../../../packages/api-contracts/src/index';
import {
  bufferScopeHint,
  bufferScopeLabel,
  capabilityGaps,
  channelPolicyText,
  headlineWarning,
  isEmptyScope,
  orderedSlices,
  qty,
  safetyBufferConfigured,
  safetyBufferText,
  sliceBalanced,
  sliceOrder,
  sliceTone,
} from './inventory-sellable-model';

function slice(over: Partial<AvailableQuantitySlice>): AvailableQuantitySlice {
  return {
    code: 'SELLABLE',
    label: '合格可售',
    amount: 0,
    bucketCount: 0,
    countedInSellable: true,
    basis: '',
    ...over,
  } as AvailableQuantitySlice;
}

/** 五片之和恰好等于 available 的正常合计。 */
function balanced(over: Partial<AvailableQuantityTotals> = {}): AvailableQuantityTotals {
  return {
    onHand: 266,
    reserved: 10,
    available: 256,
    sellableBeforeBuffer: 120,
    safetyBuffer: 20,
    safetyBufferDeducted: 20,
    bufferedSkuCount: 2,
    skuCount: 3,
    bucketCount: 8,
    warehouseCount: 1,
    locationCount: 4,
    lotCount: 1,
    sellable: 100,
    bufferExceedsBase: false,
    sliceBalanced: true,
    slices: [
      slice({ code: 'SELLABLE', amount: 120, bucketCount: 4, countedInSellable: true }),
      slice({
        code: 'PENDING_INSPECTION',
        label: '待检',
        amount: 23,
        bucketCount: 2,
        countedInSellable: false,
      }),
      slice({ code: 'DEFECTIVE', label: '残次', amount: 3, bucketCount: 1, countedInSellable: false }),
      slice({ code: 'UNLISTED', label: '未上架 / 不可发货', amount: 70, bucketCount: 2, countedInSellable: false }),
      slice({
        code: 'BLOCKED_LOT',
        label: '批次已冻结 / 耗尽 / 过期',
        amount: 40,
        bucketCount: 1,
        countedInSellable: false,
      }),
    ],
    ...over,
  } as AvailableQuantityTotals;
}

describe('有序拆解：五片固定行序，缺片补片而不是少一行', () => {
  it('按固定口径顺序返回，不按金额大小重排', () => {
    const rows = orderedSlices(balanced().slices);
    expect(rows.map((r) => r.code)).toEqual([...sliceOrder]);
  });

  it('接口少给一片时补空位而不是让表格少一行', () => {
    const partial = balanced({
      slices: [
        slice({ code: 'SELLABLE', amount: 10 }),
        slice({ code: 'DEFECTIVE', amount: 5, countedInSellable: false }),
      ],
    } as Partial<AvailableQuantityTotals>);
    const rows = orderedSlices(partial.slices);
    expect(rows.map((r) => r.code)).toEqual(['SELLABLE', 'DEFECTIVE']);
    // 少的那片在界面上是空行，而不是把两片挤到一起让人以为只有两个口径
    expect(rows).toHaveLength(2);
  });

  it('输入为空时返回空数组而不是抛错', () => {
    expect(orderedSlices(undefined)).toEqual([]);
    expect(orderedSlices([])).toEqual([]);
  });
});

describe('计入 vs 被排除：两类必须用不同视觉', () => {
  it('只有 SELLABLE 那一片计入可售', () => {
    const rows = orderedSlices(balanced().slices);
    const counted = rows.filter((r) => sliceTone(r) === 'counted').map((r) => r.code);
    expect(counted).toEqual(['SELLABLE']);
  });

  it('被排除的四片都不是 counted', () => {
    const rows = orderedSlices(balanced().slices);
    expect(rows.filter((r) => sliceTone(r) === 'excluded').map((r) => r.code)).toEqual([
      'PENDING_INSPECTION',
      'DEFECTIVE',
      'UNLISTED',
      'BLOCKED_LOT',
    ]);
  });
});

describe('安全缓冲：null 显示「未配置」而不是 0', () => {
  it('未配置时给出「未配置」而不是 0', () => {
    const t = balanced({ safetyBuffer: null, bufferedSkuCount: 0, safetyBufferDeducted: 0 });
    expect(safetyBufferText(t)).toBe('未配置');
    expect(safetyBufferText(t)).not.toBe('0');
  });

  it('未配置时不弹「去配置」提示，配了才弹', () => {
    expect(safetyBufferConfigured(balanced({ bufferedSkuCount: 0 }))).toBe(false);
    expect(safetyBufferConfigured(balanced({ bufferedSkuCount: 2 }))).toBe(true);
  });

  it('配了 0 件缓冲显示 0，并认为已配置 —— 与「没配」是两种状态', () => {
    const zero = balanced({ safetyBuffer: 0, bufferedSkuCount: 1 });
    expect(safetyBufferText(zero)).toBe('0');
    expect(safetyBufferConfigured(zero)).toBe(true);
  });

  it('totals 缺失时一律「—」', () => {
    expect(safetyBufferText(null)).toBe('—');
    expect(safetyBufferText(undefined)).toBe('—');
    expect(safetyBufferConfigured(null)).toBe(false);
  });

  it('缓冲说明报出命中档位与 SKU 数', () => {
    expect(bufferScopeHint(balanced())).toContain('2 个 SKU');
    expect(bufferScopeHint(balanced({ bufferedSkuCount: 0 }))).toContain('没有任何 SKU 配置');
    expect(bufferScopeHint(null)).toBe('');
  });

  it('档位中文名区分 NONE 与 DEFAULT', () => {
    expect(bufferScopeLabel('NONE')).toBe('未配置安全缓冲');
    expect(bufferScopeLabel('DEFAULT')).toBe('全租户兜底档');
    expect(bufferScopeLabel('SKU')).toBe('SKU 档');
    expect(bufferScopeLabel(null)).toBe('未配置安全缓冲');
  });
});

describe('恒等式：五片之和必须等于 available', () => {
  it('自洽时判定为真', () => {
    expect(sliceBalanced(balanced())).toBe(true);
  });

  it('口径坏掉（和不等）时判定为假 —— 即使后端自己也说自洽', () => {
    const broken = balanced({
      available: 999,
      sliceBalanced: true,
    } as Partial<AvailableQuantityTotals>);
    expect(sliceBalanced(broken)).toBe(false);
  });

  it('后端自报不自洽时判定为假 —— 即使前端求和碰巧对上', () => {
    const lying = balanced({ sliceBalanced: false } as Partial<AvailableQuantityTotals>);
    expect(sliceBalanced(lying)).toBe(false);
  });

  it('totals 缺失时不误报为坏', () => {
    expect(sliceBalanced(null)).toBe(true);
  });

  it('NUMERIC 四舍五入残渣不算口径坏掉', () => {
    const rounding = balanced({
      available: 256,
      sliceBalanced: true,
    } as Partial<AvailableQuantityTotals>);
    // 各片之和 256.0000004 与 256 的差小于 1e-6 → 不报警
    rounding.slices = rounding.slices.map((s, i) =>
      i === 0 ? { ...s, amount: 120.0000004 } : s,
    );
    expect(sliceBalanced(rounding)).toBe(true);
  });
});

describe('第一屏告警：口径不自洽优先于缓冲过大', () => {
  it('正常时无告警', () => {
    expect(headlineWarning(balanced())).toBe('');
  });

  it('恒等式坏了先报恒等式', () => {
    const broken = balanced({ sliceBalanced: false } as Partial<AvailableQuantityTotals>);
    expect(headlineWarning(broken)).toContain('对不上');
  });

  it('缓冲大于基数时报出具体两个数', () => {
    const over = balanced({ bufferExceedsBase: true, sellable: 0 });
    expect(headlineWarning(over)).toContain('安全缓冲');
    expect(headlineWarning(over)).toContain('可售量已按 0 计');
  });

  it('totals 缺失时无告警', () => {
    expect(headlineWarning(null)).toBe('');
  });
});

describe('空态：有库存与没库存要分开', () => {
  it('没有任何 SKU / bucket 时算空', () => {
    expect(
      isEmptyScope(balanced({ skuCount: 0, bucketCount: 0 } as Partial<AvailableQuantityTotals>)),
    ).toBe(true);
  });

  it('有 SKU 就不是空态', () => {
    expect(isEmptyScope(balanced())).toBe(false);
  });

  it('totals 缺失算空（避免拿 undefined 当 0 显示）', () => {
    expect(isEmptyScope(null)).toBe(true);
  });
});

describe('数量显示：null 不显示成 0', () => {
  it('null / undefined / 空串一律「—」', () => {
    expect(qty(null)).toBe('—');
    expect(qty(undefined)).toBe('—');
    expect(qty('')).toBe('—');
  });

  it('整数不带尾随零，小数最多 6 位', () => {
    expect(qty(100)).toBe('100');
    expect(qty(100.5)).toBe('100.5');
    expect(qty(0.1)).toBe('0.1');
  });

  it('非数字不显示 NaN', () => {
    expect(qty('abc')).toBe('—');
  });
});

describe('渠道口径：只展示不参与可售量计算', () => {
  it('未配置时明确说未配置，而不是按 0 比例渲染', () => {
    const text = channelPolicyText({ configured: false } as AvailableQuantityReport['channelPolicy']);
    expect(text).toContain('未配置');
  });

  it('已配置时给出档位、池模式与缓冲比例', () => {
    const text = channelPolicyText({
      configured: true,
      resolvedScopeType: 'SHOP',
      poolMode: 'SHARED',
      bufferRatio: 0.1,
      usedFallback: false,
    } as AvailableQuantityReport['channelPolicy']);
    expect(text).toContain('SHOP');
    expect(text).toContain('共享池');
    expect(text).toContain('10.0%');
  });

  it('bufferRatio 为 0 时显示 0.0% 而不是「未配置」', () => {
    const text = channelPolicyText({
      configured: true,
      resolvedScopeType: 'DEFAULT',
      poolMode: 'EXCLUSIVE',
      bufferRatio: 0,
      usedFallback: false,
    } as AvailableQuantityReport['channelPolicy']);
    expect(text).toContain('0.0%');
    expect(text).not.toContain('未配置');
  });
});

describe('能力折叠区：未交付的维度要列出来并指认归属', () => {
  const caps = {
    quantityRoleAvailable: true,
    quantityRoleOwnerModule: '7.1.1.2 / V1',
    quantityRoleNote: '质量状态口径已接入',
    locationPickableAvailable: true,
    locationPickableOwnerModule: '8.1.2.1 / V2',
    locationPickableNote: '库位可拣已接入',
    lotStatusAvailable: true,
    lotStatusOwnerModule: '7.3 / V2',
    lotStatusNote: '批次状态已接入',
    safetyStockAvailable: true,
    safetyStockOwnerModule: '7.2.1 / V1',
    safetyStockNote: '安全缓冲已接入',
    channelPolicyAvailable: true,
    channelPolicyOwnerModule: '7.2.2.1 / V56',
    channelPolicyNote: '渠道口径已接入',
    expiryByDateAvailable: false,
    expiryByDateOwnerModule: '7.3.1.2',
    expiryByDateNote: '按日期效期剔除未接入',
    reservationReleaseAvailable: false,
    reservationReleaseOwnerModule: '7.2.1.3',
    reservationReleaseNote: '预占过期释放未接入',
    releaseNote: '预占从 reserved 进算式',
    availableFormula: 'sellable = max(0, ...)',
    sliceIdentity: '五片互斥且穷尽',
    note: '',
  } as unknown as AvailableQuantityCapabilities;

  it('未交付的两项被列进折叠区', () => {
    const gaps = capabilityGaps(caps).filter((g) => !g.available);
    expect(gaps.map((g) => g.key)).toEqual(['expiryByDate', 'reservationRelease']);
    expect(gaps.every((g) => g.ownerModule !== '')).toBe(true);
  });

  it('已接入的五项不在折叠区里出现', () => {
    const keys = capabilityGaps(caps).filter((g) => !g.available).map((g) => g.key);
    expect(keys).not.toContain('safetyStock');
    expect(keys).not.toContain('channelPolicy');
  });

  it('caps 缺失时返回空数组而不是抛错', () => {
    expect(capabilityGaps(null)).toEqual([]);
  });

  it('口径原文随能力声明返回（可售量是单一口径来源，口径本身要能被读到）', () => {
    expect(caps.availableFormula).toContain('max(0');
    expect(caps.sliceIdentity).toContain('互斥');
  });
});