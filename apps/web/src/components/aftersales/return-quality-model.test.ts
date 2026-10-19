import { describe, expect, it } from 'vitest';
import {
  blockedVerdicts,
  blocksResale,
  concludeBlockedReason,
  failsMissingNote,
  initialDrafts,
  VERDICT_OPTIONS,
} from './return-quality-model';

/**
 * 11.2.3.1 判定口径的前端单元测试。
 *
 * <p>测的是**蓝图那句加粗句**在界面上是不是真的落成了两条不同的路径：
 * 「包装破损但商品完好可判可再销售，缺少配件则应判残次」。
 * 这一句话在服务端有守卫（{@code QualityVerdict.allowed} + V179 触发器），
 * 前端也必须有一份镜像 —— 否则质检员录完检项提交被拒才知道规则，
 * 录检项的整个过程都是逆行的。
 */

describe('blocksResale：该维度失败是否禁止判可再销售', () => {
  it('包装与外观**不**禁止可再销售（蓝图加粗句的左半）', () => {
    expect(blocksResale('PACKAGING')).toBe(false);
    expect(blocksResale('APPEARANCE')).toBe(false);
  });

  it('功能与配件**禁止**可再销售', () => {
    expect(blocksResale('FUNCTION')).toBe(true);
    expect(blocksResale('ACCESSORY')).toBe(true);
  });
});

describe('blockedVerdicts：失败项禁掉的结论集合', () => {
  it('只有包装 / 外观问题时什么都不禁 —— 仍可判可再销售', () => {
    expect(blockedVerdicts(['PACKAGING'])).toEqual([]);
    expect(blockedVerdicts(['APPEARANCE', 'PACKAGING'])).toEqual([]);
  });

  it('功能失效禁掉可再销售，但残次与报废仍然可选', () => {
    expect(blockedVerdicts(['FUNCTION'])).toEqual(['RESALABLE']);
  });

  it('配件缺失禁掉可再销售（残次与报废留给服务端那条更强的规则）', () => {
    expect(blockedVerdicts(['ACCESSORY'])).toEqual(['RESALABLE']);
  });

  it('空维度列表不禁任何结论', () => {
    expect(blockedVerdicts([])).toEqual([]);
    expect(blockedVerdicts([null, undefined])).toEqual([]);
  });
});

describe('结论是三值枚举，不是布尔勾选框', () => {
  it('可再销售 / 残次 / 报废三值齐全且互不相同', () => {
    expect(VERDICT_OPTIONS.map((o) => o.code)).toEqual(['RESALABLE', 'DEFECTIVE', 'SCRAPPED']);
    // 少一档就意味着「可再销售 / 不可再销售」回退成布尔量，
    // 而报废与残次的残值上限、指令类型、是否可逆全都不同。
    expect(new Set(VERDICT_OPTIONS.map((o) => o.label)).size).toBe(3);
  });
});

describe('concludeBlockedReason：判定按钮的禁用理由必填', () => {
  const base = {
    head: { inspectionStatus: 'DRAFT' },
    mandatoryMissingCount: 0,
    verdictOptions: [
      { code: 'RESALABLE', allowed: true },
      { code: 'DEFECTIVE', allowed: true },
      { code: 'SCRAPPED', allowed: true },
    ],
  };

  it('草稿且检项齐备时不禁用', () => {
    expect(concludeBlockedReason(base)).toBeNull();
  });

  it('还有强制检项没结果时禁用并说清缺几个', () => {
    const reason = concludeBlockedReason({ ...base, mandatoryMissingCount: 3 });
    expect(reason).toContain('3');
    expect(reason).toContain('强制检项');
  });

  it('已判定是终态：禁用并说明不可逆', () => {
    const reason = concludeBlockedReason({ ...base, head: { inspectionStatus: 'CONCLUDED' } });
    expect(reason).toContain('终态');
  });

  it('已撤销同样禁用', () => {
    expect(concludeBlockedReason({ ...base, head: { inspectionStatus: 'CANCELLED' } })).toContain('撤销');
  });

  it('三个结论全被禁时要说清原因，而不是让质检员猜', () => {
    const reason = concludeBlockedReason({
      ...base,
      verdictOptions: [
        { code: 'RESALABLE', allowed: false },
        { code: 'DEFECTIVE', allowed: true },
        { code: 'SCRAPPED', allowed: false },
      ],
    });
    expect(reason).toBeNull(); // 残次仍允许 → 还能判
    const allBlocked = concludeBlockedReason({
      ...base,
      verdictOptions: [
        { code: 'RESALABLE', allowed: false },
        { code: 'DEFECTIVE', allowed: false },
        { code: 'SCRAPPED', allowed: false },
      ],
    });
    expect(allBlocked).toContain('没有任何一个结论');
  });
});

describe('逐项结果草稿', () => {
  const items = [
    { id: 'i1', itemRequired: true },
    { id: 'i2', itemRequired: false },
  ];

  it('初始化按模板检项铺满，默认全部合格', () => {
    const drafts = initialDrafts(items);
    expect(drafts).toHaveLength(2);
    expect(drafts.every((d) => d.checkOutcome === 'PASS')).toBe(true);
    expect(drafts.map((d) => d.templateItemId)).toEqual(['i1', 'i2']);
  });

  it('判了不合格却没写说明的要被单独挑出来（判残次要拿去索赔）', () => {
    const drafts = initialDrafts(items);
    const first = drafts[0];
    if (!first) throw new Error('夹具为空，断言本身失效了');
    first.checkOutcome = 'FAIL';
    expect(failsMissingNote(drafts)).toHaveLength(1);
    first.checkNote = '外箱压瘪，主机无划痕';
    expect(failsMissingNote(drafts)).toHaveLength(0);
  });

  it('合格项即使没写说明也不算缺说明', () => {
    expect(failsMissingNote(initialDrafts(items))).toHaveLength(0);
  });
});
