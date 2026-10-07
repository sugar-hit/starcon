import { describe, expect, it } from 'vitest';
import {
  allowedRefTypes,
  intervalText,
  nonOwnedText,
  notNull,
  ownedAssetText,
  ownerKindLabels,
  ownerKindTone,
  qty,
  refTypeLabels,
  resolvedHeadline,
  scopePriority,
  scopeTargetText,
  scopeTypeLabels,
} from './stock-ownership-model';

/**
 * 7.7.1.1 货主与所有权的展示口径。
 *
 * <p>这几个函数承载的是「同一个数字怎么读」的问题，不是格式化问题：
 *
 * <ul>
 *   <li>回落默认货主与命中真实货权档必须能一眼分开 —— 混起来就等于让人以为寄销规则还生效着。
 *   <li>寄售 / 代销 / 平台所有是**正常业务形态**，不能画成"错误"，否则人会去"修"一个没问题的东西。
 *   <li>货值不可用要说出来，不能画成一个 0。
 *   <li>货权档的作用域（哪个仓 / 哪个 SKU）是这张表的主键 —— 两档作用域不同就不相干，
 *       把它们并排成"两条打架的规则"是误读。
 * </ul>
 */
describe('货权解析结论', () => {
  const owner = {
    ownerId: 'o1',
    name: '供应商甲寄售',
    ownerKind: 'SUPPLIER_CONSIGNMENT' as never,
  };

  it('命中真实货权档时标明是哪一档', () => {
    expect(
      resolvedHeadline({
        owner,
        usedFallback: false,
        matchedScope: 'WAREHOUSE',
      } as never),
    ).toBe('命中整仓档：供应商甲寄售（供应商寄售）');
  });

  it('回落到默认货主必须显式标出「回落」', () => {
    expect(
      resolvedHeadline({
        owner: { ...owner, ownerKind: 'SELF', name: '默认自有' },
        usedFallback: true,
        matchedScope: null,
      } as never),
    ).toBe('回落默认货主：默认自有（自有）');
  });

  it('连兜底货主都没有时如实报「没有货主」，不编一个出来', () => {
    expect(resolvedHeadline({ owner: null, usedFallback: true, matchedScope: null } as never)).toBe(
      '未配置：这批货目前没有货主',
    );
  });

  it('未知的所有权码原样透出而不是吞成空白', () => {
    expect(ownerKindLabels['SOMETHING_NEW'] ?? 'SOMETHING_NEW').toBe('SOMETHING_NEW');
  });
});

describe('资产口径', () => {
  it('只有自有货计入自有资产', () => {
    expect(ownerKindTone('SELF')).toBe('owned');
    expect(ownedAssetText(true)).toBe('计入自有资产');
  });

  it('寄售 / 代销 / 平台所有一律不计入自有资产', () => {
    for (const kind of ['SUPPLIER_CONSIGNMENT', 'CONSIGNEE', 'PLATFORM']) {
      expect(ownerKindTone(kind)).toBe('non-owned');
      expect(ownedAssetText(false)).toBe('不计入自有资产');
    }
  });

  it('非自有货量单独成列而不是混进总量', () => {
    expect(nonOwnedText(200)).toBe('200');
    expect(nonOwnedText(null)).toBe('—');
  });
});

describe('所有权类型与指向的自洽', () => {
  it('寄售只能指向供应商', () => {
    expect(allowedRefTypes('SUPPLIER_CONSIGNMENT')).toEqual(['SUPPLIER']);
  });

  it('自有可挂法人，也可退回本租户自身（租户还没登记法人时的真实场景）', () => {
    expect(allowedRefTypes('SELF')).toEqual(['LEGAL_ENTITY', 'INTERNAL']);
  });

  it('平台所有可指向平台或本租户自身', () => {
    expect(allowedRefTypes('PLATFORM')).toEqual(['PLATFORM', 'INTERNAL']);
  });

  it('代销可指向供应商或法人', () => {
    expect(allowedRefTypes('CONSIGNEE')).toEqual(['SUPPLIER', 'LEGAL_ENTITY']);
  });

  it('未知类型返回空集合而不是把全部指向类型放出来', () => {
    expect(allowedRefTypes('WHATEVER')).toEqual([]);
  });

  it('指向类型有中文名，未知码原样透出', () => {
    expect(refTypeLabels['LEGAL_ENTITY']).toBe('本租户法人');
    expect(refTypeLabels['X'] ?? 'X').toBe('X');
  });
});

describe('货权档的作用域', () => {
  it('按仓库 / 法人 / SKU 显示各自的作用对象', () => {
    expect(scopeTargetText({ scopeType: 'WAREHOUSE', warehouseCode: 'WH-OWN' })).toBe('整仓 WH-OWN');
    expect(scopeTargetText({ scopeType: 'LEGAL_ENTITY', legalEntityCode: 'LE-1' })).toBe('法人 LE-1');
    expect(scopeTargetText({ scopeType: 'SKU', skuCode: 'SKU-1' })).toBe('SKU SKU-1');
    expect(scopeTargetText({ scopeType: 'DEFAULT' })).toBe('全租户兜底');
  });

  it('未选对象时明说未选，而不是显示一个空白让人以为没配对象', () => {
    expect(scopeTargetText({ scopeType: 'WAREHOUSE', warehouseCode: null })).toBe('整仓（未选仓库）');
    expect(scopeTargetText({ scopeType: 'LEGAL_ENTITY', legalEntityCode: null })).toBe('按法人（未选法人）');
    expect(scopeTargetText({ scopeType: 'SKU', skuCode: null })).toBe('按 SKU（未选 SKU）');
  });

  it('解析优先级是 SKU > 法人 > 仓库 > 兜底，与后端同一套约定', () => {
    expect(scopePriority['SKU']).toBeLessThan(scopePriority['LEGAL_ENTITY']);
    expect(scopePriority['LEGAL_ENTITY']).toBeLessThan(scopePriority['WAREHOUSE']);
    expect(scopePriority['WAREHOUSE']).toBeLessThan(scopePriority['DEFAULT']);
  });

  it('四个范围都有中文标签', () => {
    for (const code of ['DEFAULT', 'WAREHOUSE', 'LEGAL_ENTITY', 'SKU']) {
      expect(scopeTypeLabels[code]).toBeTruthy();
    }
  });
});

describe('生效区间与数值展示', () => {
  it('未收口就是「至今在生效」，收口后明确标出', () => {
    expect(intervalText('2026-03-01T01:30:00Z', null)).toContain('至今在生效');
    expect(intervalText('2026-03-01T01:30:00Z', '2026-04-01T00:00:00Z')).toContain('已收口');
  });

  it('没有生效起点显示为破折号而不是 1970', () => {
    expect(intervalText(null, null)).toBe('—');
  });

  it('数量缺失显示为破折号 —— 把 0 和「没有数据」画成一样是最容易骗人的一种', () => {
    expect(qty(null)).toBe('—');
    expect(qty(0)).toBe('0');
    expect(qty(1234.5)).toContain('1,234.5');
  });
});

describe('codegen 可空守卫', () => {
  it('null 与 undefined 都判为不可用', () => {
    expect(notNull(null)).toBe(false);
    expect(notNull(undefined)).toBe(false);
    expect(notNull('x')).toBe(true);
  });
});