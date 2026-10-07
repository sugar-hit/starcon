import { describe, expect, it } from 'vitest';
import {
  ACTION_LABELS,
  ITEM_CODE_PATTERN,
  ITEM_KIND_LABELS,
  ITEM_STATUS_LABELS,
  disableActions,
  groupByDictionary,
  historySummary,
  referenceSummary,
  validateItemCode,
  validateItemEdit,
  validateItemName,
  type DictItemLike,
} from './dictionary-model';

function item(over: Partial<DictItemLike> = {}): DictItemLike {
  return {
    id: 'i1',
    dictCode: 'ORDER_STATUS',
    itemCode: 'PAID',
    name: '已付款',
    status: 'ACTIVE',
    referenceCount: 0,
    referencingSystems: 0,
    referenced: false,
    codeLocked: true,
    ...over,
  };
}

describe('字典项编码校验', () => {
  it('接受大写字母开头的标识符', () => {
    for (const code of ['PAID', 'A1', 'ORDER_CANCEL_REASON', 'X_1']) {
      expect(validateItemCode(code)).toBe('');
    }
  });

  it('拒绝空编码、单字符编码与小写', () => {
    expect(validateItemCode('')).toBe('字典项编码不能为空');
    expect(validateItemCode('   ')).toBe('字典项编码不能为空');
    expect(validateItemCode('P')).not.toBe('');
    expect(validateItemCode('paid')).not.toBe('');
    expect(validateItemCode('PAID-1')).not.toBe('');
    expect(validateItemCode('1PAID')).not.toBe('');
    expect(validateItemCode('已付款')).not.toBe('');
  });

  it('拒绝超过 64 位的编码', () => {
    // 64 位是上限（与库里 varchar(64) 一致），64 位合法，65 位非法
    expect(validateItemCode('A' + 'B'.repeat(63))).toBe('');
    expect(validateItemCode('A'.repeat(65))).not.toBe('');
  });

  it('首尾空白不算合法字符的一部分之外的宽容：先 trim 再校验', () => {
    expect(validateItemCode('  PAID  ')).toBe('');
  });

  it('与后端正则同口径：单字符不合法是因为后继位要求至少一位', () => {
    // 后端 [A-Z][A-Z0-9_]{1,63} 最短两位；前端必须一模一样，否则两边会给出不同结论
    expect(ITEM_CODE_PATTERN.test('P')).toBe(false);
    expect(ITEM_CODE_PATTERN.test('AB')).toBe(true);
  });
});

describe('字典项名称校验', () => {
  it('空名称不合法：历史单据会渲染成空白标签', () => {
    expect(validateItemName('')).not.toBe('');
    expect(validateItemName('  ')).not.toBe('');
    expect(validateItemName('已付款')).toBe('');
  });
});

describe('编辑校验', () => {
  it('新建时编码必填且合法', () => {
    expect(validateItemEdit(null, '', '已付款')).toBe('字典项编码不能为空');
    expect(validateItemEdit(null, 'paid', '已付款')).not.toBe('');
    expect(validateItemEdit(null, 'PAID', '')).toBe('字典项名称不能为空');
    expect(validateItemEdit(null, 'PAID', '已付款')).toBe('');
  });

  it('编辑时留空编码表示不改编码', () => {
    const referenced = item({ referenced: true, referenceCount: 99 });
    expect(validateItemEdit(referenced, '', '已付款')).toBe('');
  });

  it('被引用的字典项改编码被拒，且理由要具体', () => {
    const referenced = item({ referenced: true, referenceCount: 99 });
    const problem = validateItemEdit(referenced, 'BUYER_PAID', '已付款');
    expect(problem).not.toBe('');
    expect(problem).toContain('已被历史数据引用');
    expect(problem).toContain('PAID');
  });

  it('未被引用的字典项可以改编码', () => {
    expect(validateItemEdit(item(), 'BUYER_PAID', '已付款')).toBe('');
  });

  it('提交与原编码相同的编码不算改编码', () => {
    const referenced = item({ referenced: true });
    expect(validateItemEdit(referenced, 'PAID', '已付款')).toBe('');
  });

  it('改编码但编码本身不合法时先报格式问题', () => {
    const problem = validateItemEdit(item({ referenced: true }), 'paid', '已付款');
    expect(problem).toContain('大写字母开头');
  });
});

describe('停用而非删除', () => {
  it('启用态可以停用，停用态可以恢复', () => {
    expect(disableActions(item({ status: 'ACTIVE' }))).toEqual({
      canDeactivate: true,
      canReactivate: false,
    });
    expect(disableActions(item({ status: 'DISABLED' }))).toEqual({
      canDeactivate: false,
      canReactivate: true,
    });
  });

  it('可用操作里没有删除', () => {
    // 返回类型里根本没有 canDelete 字段：删除在本模块不是一个可表达的动作
    expect(Object.keys(disableActions(item())).sort()).toEqual(['canDeactivate', 'canReactivate']);
  });
});

describe('引用统计摘要', () => {
  it('从未被引用与当前引用 0 条要分开说', () => {
    expect(referenceSummary(item())).toBe('尚未被任何业务系统引用');
    expect(
      referenceSummary(item({ referenced: true, referenceCount: 0, referencingSystems: 2 })),
    ).toBe('2 个来源系统曾引用过，当前引用 0 条');
  });

  it('被引用时给出条数与来源数', () => {
    expect(
      referenceSummary(item({ referenced: true, referenceCount: 135, referencingSystems: 2 })),
    ).toBe('被 2 个来源系统引用，共 135 条');
  });
});

describe('变更历史摘要', () => {
  it('新建只说新建，不提前后值', () => {
    expect(
      historySummary({ action: 'CREATED', itemCode: 'PAID', afterName: '已付款', reason: '' }),
    ).toBe('新建字典项「已付款」（PAID）');
  });

  it('改名要说清楚改了什么', () => {
    expect(
      historySummary({
        action: 'UPDATED',
        itemCode: 'PAID',
        beforeName: '已付款',
        afterName: '买家已付款',
        reason: '',
      }),
    ).toBe('字典项「PAID」改名：已付款 → 买家已付款');
  });

  it('只改说明时不谎称改名', () => {
    expect(
      historySummary({
        action: 'UPDATED',
        itemCode: 'PAID',
        beforeName: '已付款',
        afterName: '已付款',
        reason: '',
      }),
    ).toContain('说明或排序');
  });

  it('停用与恢复带上原因', () => {
    expect(
      historySummary({
        action: 'DEACTIVATED',
        itemCode: 'PAID',
        afterName: '已付款',
        reason: '促销季结束',
      }),
    ).toBe('停用字典项「已付款」（PAID）：促销季结束');
    expect(
      historySummary({
        action: 'REACTIVATED',
        itemCode: 'PAID',
        afterName: '已付款',
        reason: '误停用',
      }),
    ).toBe('恢复字典项「已付款」（PAID）：误停用');
  });
});

describe('按字典分组', () => {
  it('同一字典的字典项归到一组并保留字典名', () => {
    const groups = groupByDictionary([
      { ...item({ dictCode: 'ORDER_STATUS' }), dictName: '订单状态' },
      {
        ...item({ id: 'i2', itemCode: 'SHIPPED', dictCode: 'ORDER_STATUS' }),
        dictName: '订单状态',
      },
      { ...item({ id: 'i3', dictCode: 'UNIT_TYPE' }), dictName: '计量单位类型' },
    ]);
    expect(groups).toHaveLength(2);
    expect(groups[0]?.label).toBe('订单状态');
    expect(groups[0]?.rows).toHaveLength(2);
    expect(groups[1]?.label).toBe('计量单位类型');
  });

  it('空列表返回空分组而不是抛错', () => {
    expect(groupByDictionary([])).toEqual([]);
  });
});

describe('展示标签', () => {
  it('四类字典都有中文名，与蓝图职责点名的顺序一致', () => {
    expect(Object.keys(ITEM_KIND_LABELS)).toEqual([
      'STATUS_ENUM',
      'REASON_CODE',
      'CATEGORY_LABEL',
      'UNIT_TYPE',
    ]);
  });

  it('状态与动作标签齐备', () => {
    expect(ITEM_STATUS_LABELS.DISABLED).toBe('停用');
    expect(ACTION_LABELS.DEACTIVATED).toBe('停用');
    expect(ACTION_LABELS.REACTIVATED).toBe('恢复');
  });
});
