import { describe, expect, it } from 'vitest';
import type { PhysicalSafetyProfile } from '../../../../../packages/api-contracts/src/catalog';
import { ApiError } from './api';
import {
  decimal,
  draftFrom,
  draftToPayload,
  errorText,
  isConflict,
  isNotFound,
  safetyState,
  validateDraft,
  volumeText,
  volumetricText,
  writable,
  type SafetyDraft,
} from './physical-safety-model';

function profile(patch: Partial<PhysicalSafetyProfile> = {}): PhysicalSafetyProfile {
  return {
    skuId: 'sku-1',
    skuCode: 'SKU-1',
    skuName: '测试 SKU',
    spuCode: 'SPU-1',
    lengthMm: 200,
    widthMm: 150,
    heightMm: 100,
    netWeightG: 500,
    grossWeightG: 800,
    packagingType: '纸箱',
    safetyCollected: true,
    containsBattery: false,
    containsLiquid: false,
    dangerousGoods: false,
    isMagnetic: false,
    dangerousSubstanceClass: '',
    safetyNote: '',
    safetyVersion: 0,
    packagingVersion: 0,
    volumeCm3: 3000,
    divisorCm3PerKg: null,
    volumetricWeightKg: null,
    chargeableWeightKg: null,
    activeFlags: [],
    requiredDeclarations: [],
    hasVolume: true,
    ...patch,
  };
}

function draft(patch: Partial<SafetyDraft> = {}): SafetyDraft {
  return {
    lengthMm: '200',
    widthMm: '150',
    heightMm: '100',
    netWeightG: '500',
    grossWeightG: '800',
    packagingType: '纸箱',
    safetyCollected: true,
    containsBattery: false,
    containsLiquid: false,
    dangerousGoods: false,
    isMagnetic: false,
    dangerousSubstanceClass: '',
    safetyNote: '',
    safetyVersion: 0,
    packagingVersion: 0,
    ...patch,
  };
}

// ============================================================ 规则 R1：缺一维不给体积

describe('规则 R1 体积与体积重', () => {
  it('算得出体积时给出 cm³', () => {
    expect(volumeText(profile())).toBe('3000 cm³');
  });

  it('缺一维时明确说缺哪一维而不是给 0', () => {
    const text = volumeText(
      profile({ volumeCm3: null, hasVolume: false, heightMm: null }),
    );
    expect(text).toContain('缺');
    expect(text).toContain('高');
    expect(text).not.toContain('0 cm³');
  });

  it('三维都缺时把三样都点出来', () => {
    const text = volumeText(
      profile({ volumeCm3: null, hasVolume: false, lengthMm: null, widthMm: null, heightMm: null }),
    );
    expect(text).toContain('长');
    expect(text).toContain('宽');
    expect(text).toContain('高');
  });

  it('没选除数时不给体积重并说明除数是渠道口径', () => {
    const text = volumetricText(null, '');
    expect(text).toContain('未选除数');
    expect(text).toContain('渠道口径');
  });

  it('选了除数且算得出时给出体积重与除数', () => {
    expect(volumetricText(0.5, '6000')).toBe('0.5 kg（除数 6000）');
  });

  it('选了除数但缺长宽高时说明算不出', () => {
    expect(volumetricText(null, '6000')).toContain('缺长宽高');
  });
});

// ============================================================ 规则 R2：未采集 ≠ 都不带

describe('规则 R2 安全属性状态', () => {
  it('未采集是警示态', () => {
    const state = safetyState(profile({ safetyCollected: false }));
    expect(state.label).toBe('未采集');
    expect(state.tone).toBe('warning');
  });

  it('已采集且都不带是中性态而不是警示态', () => {
    const state = safetyState(profile());
    expect(state.label).toBe('已采集 · 无特殊属性');
    expect(state.tone).toBe('neutral');
  });

  it('未采集与都不带的文案与语气都不同', () => {
    const uncollected = safetyState(profile({ safetyCollected: false }));
    const plain = safetyState(profile());
    expect(uncollected.label).not.toBe(plain.label);
    expect(uncollected.tone).not.toBe(plain.tone);
  });

  it('有标记时逐个列出来并用危险色', () => {
    const state = safetyState(profile({ containsBattery: true, dangerousGoods: true }));
    expect(state.tone).toBe('danger');
    expect(state.label).toContain('带电');
    expect(state.label).toContain('危险品');
    expect(state.active).toEqual(['contains_battery', 'dangerous_goods']);
  });

  it('未采集时即使四个布尔都是 false 也不能显示成无特殊属性', () => {
    const state = safetyState(
      profile({
        safetyCollected: false,
        containsBattery: false,
        containsLiquid: false,
        dangerousGoods: false,
        isMagnetic: false,
      }),
    );
    expect(state.label).toBe('未采集');
  });
});

// ============================================================ 草稿与校验

describe('草稿校验', () => {
  it('正常草稿没有错误', () => {
    expect(validateDraft(draft())).toEqual([]);
  });

  it('毛重小于净重被拦下', () => {
    expect(validateDraft(draft({ netWeightG: '900', grossWeightG: '100' }))).toContain(
      '毛重不能小于净重',
    );
  });

  it('负数被拦下', () => {
    expect(validateDraft(draft({ lengthMm: '-1' }))).toContain('长不能为负数');
  });

  it('非数字被拦下', () => {
    expect(validateDraft(draft({ widthMm: 'abc' }))).toContain('宽必须是数字');
  });

  it('超过 6 位小数被拦下', () => {
    expect(validateDraft(draft({ heightMm: '1.1234567' }))).toContain('高最多 6 位小数');
  });

  it('勾了标记却没确认已采集被拦下', () => {
    const errors = validateDraft(draft({ safetyCollected: false, containsBattery: true }));
    expect(errors).toContain('勾选了安全标记就必须同时确认「已采集安全属性」');
  });

  it('没勾任何标记时不要求确认已采集', () => {
    expect(validateDraft(draft({ safetyCollected: false }))).toEqual([]);
  });

  it('包装方式超长被拦下', () => {
    expect(validateDraft(draft({ packagingType: 'x'.repeat(65) }))).toContain(
      '包装方式最多 64 个字符',
    );
  });

  it('备注超长被拦下', () => {
    expect(validateDraft(draft({ safetyNote: 'x'.repeat(1001) }))).toContain(
      '安全属性备注最多 1000 个字符',
    );
  });
});

describe('草稿与请求体互转', () => {
  it('档案转草稿时保留版本号（乐观锁凭据）', () => {
    const result = draftFrom(profile({ safetyVersion: 4 }));
    expect(result.safetyVersion).toBe(4);
  });

  it('未采集时安全属性版本记为 0', () => {
    expect(draftFrom(profile({ safetyCollected: false, safetyVersion: null })).safetyVersion).toBe(0);
  });

  it('空串转成 null 而不是 0', () => {
    const payload = draftToPayload(draft({ lengthMm: '', netWeightG: '' }));
    expect(payload.lengthMm).toBeNull();
    expect(payload.netWeightG).toBeNull();
  });

  it('数值原样带上', () => {
    const payload = draftToPayload(draft({ lengthMm: '200', grossWeightG: '800' }));
    expect(payload.lengthMm).toBe(200);
    expect(payload.grossWeightG).toBe(800);
  });

  it('包装方式去首尾空格', () => {
    expect(draftToPayload(draft({ packagingType: '  纸箱  ' })).packagingType).toBe('纸箱');
  });
});

// ============================================================ 展示与错误

describe('数字与错误处理', () => {
  it('null 显示成破折号而不是 0', () => {
    expect(decimal(null)).toBe('—');
    expect(decimal(undefined)).toBe('—');
  });

  it('去掉多余的尾随零', () => {
    expect(decimal(3000)).toBe('3000');
    expect(decimal(0.5)).toBe('0.5');
  });

  it('带单位时拼上单位', () => {
    expect(decimal(800, 'g')).toBe('800 g');
  });

  it('错误文本优先取后端给的中文', () => {
    expect(errorText(new Error('毛重不能小于净重'))).toBe('毛重不能小于净重');
    expect(errorText(null, '操作失败')).toBe('操作失败');
  });

  it('404 与 409 分得开', () => {
    // 必须是真的 ApiError：isNotFound 走 instanceof，plain object 会被判成 false。
    expect(isNotFound(new ApiError(404, '记录不存在或没有访问权限'))).toBe(true);
    expect(isNotFound(new ApiError(409, '已被他人修改'))).toBe(false);
    expect(isConflict(new ApiError(409, '已被他人修改'))).toBe(true);
    expect(isConflict(new ApiError(404, '记录不存在或没有访问权限'))).toBe(false);
    expect(isConflict(new Error('boom'))).toBe(false);
  });

  // 这两条是本轮修掉的两个真实缺陷：draftFrom 曾经把备注写成空串、packagingVersion 写成 0。
  it('草稿从档案回填备注与包装版本号而不是清空', () => {
    const draft = draftFrom(
      profile({ safetyNote: '磁性物已做衰减处理', safetyVersion: 3, packagingVersion: 7 }),
    );
    // 备注被清空 =「打开页面点一下保存」就静默丢掉已填内容。
    expect(draft.safetyNote).toBe('磁性物已做衰减处理');
    // packagingVersion 恒为 0 = 第二次保存必然 409（第一次写完 version 就变成 1）。
    expect(draft.packagingVersion).toBe(7);
    expect(draft.safetyVersion).toBe(3);
  });

  it('草稿回填危险品类别', () => {
    expect(
      draftFrom(profile({ dangerousGoods: true, dangerousSubstanceClass: 'UN1266' }))
        .dangerousSubstanceClass,
    ).toBe('UN1266');
  });

  // 规则 B3 的前端侧：与后端同一套判定，缺一个方向都会让界面提交出一个库层必然拒绝的形状。
  it('危险品必须填类别，非危险品不许填类别', () => {
    const base = draftFrom(profile({ dangerousGoods: true }));
    base.dangerousSubstanceClass = '';
    expect(validateDraft(base).some((e) => e.includes('危险品类别'))).toBe(true);

    base.dangerousSubstanceClass = 'UN1266';
    expect(validateDraft(base)).toEqual([]);

    const plain = draftFrom(profile({ dangerousGoods: false }));
    plain.dangerousSubstanceClass = 'UN1266';
    expect(validateDraft(plain).some((e) => e.includes('危险品类别'))).toBe(true);
  });

  it('危险品类别带进提交载荷', () => {
    const draft = draftFrom(profile({ dangerousGoods: true, dangerousSubstanceClass: 'UN1266' }));
    expect(draftToPayload(draft).dangerousSubstanceClass).toBe('UN1266');
  });

  it('写权限按 catalog.compliance.update 收敛', () => {
    expect(writable(['catalog.compliance.read'])).toBe(false);
    expect(writable(['catalog.compliance.read', 'catalog.compliance.update'])).toBe(true);
  });
});
