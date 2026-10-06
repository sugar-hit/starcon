import { describe, expect, it } from 'vitest';
import type { AttributeOptionOverviewRow } from '../../../../../packages/api-contracts/src/catalog';
import {
  attributeLabel,
  emptyHint,
  groupByAttribute,
  moveWithin,
  referenceText,
  retainHint,
} from './attribute-option-model';

function row(overrides: Partial<AttributeOptionOverviewRow> = {}): AttributeOptionOverviewRow {
  return {
    attributeId: 'attr-1',
    attributeCode: 'COLOR',
    attributeName: '颜色',
    value: '红色',
    position: 0,
    enabled: true,
    productValueCount: 0,
    categoryRuleCount: 0,
    inUse: false,
    deletable: true,
    createdAt: '2026-10-07T00:00:00Z',
    ...overrides,
  };
}

describe('3.2.1.2 属性选项管理 · 引用计数如实呈现', () => {
  it('productValueCount 为 0 时不说「已被 0 个商品使用」', () => {
    // 字典条目拿不到商品属性值（外键仍锁在 SPU 作用域），
    // 念成「已被 0 个商品使用」等于伪造了一次实测。
    expect(referenceText(row({ productValueCount: 0 }))).toBe('暂无引用');
  });

  it('真的取用了才报商品取用数', () => {
    expect(referenceText(row({ productValueCount: 3 }))).toBe('商品取用 3');
  });

  it('类目规则引用是真的，不因 productValueCount 为 0 而被吞掉', () => {
    expect(referenceText(row({ productValueCount: 0, categoryRuleCount: 2 }))).toBe(
      '类目规则 2 条',
    );
  });

  it('有引用时给出「只能停用」的原因，且区分引用来源', () => {
    expect(retainHint(row({ inUse: true, categoryRuleCount: 2 }))).toContain('类目规则');
    expect(retainHint(row({ inUse: true, productValueCount: 1 }))).toContain('商品属性值');
    expect(retainHint(row({ inUse: false }))).toBeNull();
  });
});

describe('3.2.1.2 属性选项管理 · 空态区分', () => {
  it('筛选无结果 ≠ 租户没有选项', () => {
    expect(emptyHint(120, true)).toContain('放宽条件');
    expect(emptyHint(0, false)).toContain('还没有任何属性选项');
  });

  it('筛选无结果时即便总数为 0 也不误导成「去登记取值」', () => {
    expect(emptyHint(0, true)).toContain('放宽条件');
  });
});

describe('3.2.1.2 属性选项管理 · 分组与批量重排', () => {
  const color = [row({ value: '红', position: 0 }), row({ value: '蓝', position: 1 })];
  const size = [
    row({ attributeId: 'attr-2', attributeCode: 'SIZE', attributeName: '尺码', value: 'S', position: 0 }),
  ];

  it('相邻同属性合并成一组，且不重排服务端顺序', () => {
    const groups = groupByAttribute([...color, ...size]);
    expect(groups).toHaveLength(2);
    expect(groups[0]?.rows.map((r) => r.value)).toEqual(['红', '蓝']);
    expect(groups[1]?.rows.map((r) => r.value)).toEqual(['S']);
  });

  it('分组标题带上编码与名称，用户在总览里认得出归属', () => {
    expect(attributeLabel(size[0]!)).toBe('SIZE · 尺码');
  });

  it('重排提交完整顺序：position 必须重新编号而不是沿用旧值', () => {
    // 只提交被拖动的那一项会让新旧 position 撞在一起，所以这里必须给出全量。
    const items = moveWithin(color, 1, 0);
    expect(items).toEqual([
      { value: '蓝', position: 0 },
      { value: '红', position: 1 },
    ]);
    expect(new Set(items?.map((i) => i.position)).size).toBe(2);
  });

  it('越界移动返回 null，不发出一个无效请求', () => {
    expect(moveWithin(color, 9, 0)).toBeNull();
  });
});