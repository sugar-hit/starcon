import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { COST_SECTIONS } from '../../../components/pricing/cost-composition-model';

/**
 * 5.2.1.1 成本构成的**页面路由与分区块约定**断言。
 *
 * <p>5.2.1.2 到岸成本、5.2.1.3 成本分摊与本模块**共用 L3 路由 `/pricing/cost`**。
 * 本文件把这个约定钉死：
 *
 * <ol>
 *   <li>路由目录存在，页面渲染的是**真实工作区**而不是占位页；
 *   <li>分区表里三个模块各占一条，兄弟模块的分区**显式标为未交付** ——
 *       画一个空白框让人以为已交付，比明说「未交付」糟得多；
 *   <li>兄弟模块要加分区时**不需要重写本页骨架** —— 所以本页不得引入
 *       「整页只有一个模块」的假设。
 * </ol>
 */

const PAGE = readFileSync(
  fileURLToPath(new URL('./page.tsx', import.meta.url)),
  'utf8',
);

describe('/pricing/cost 路由', () => {
  it('页面渲染的是真实工作区组件，不是占位页', () => {
    expect(PAGE).toContain('CostCompositionWorkspace');
    // 🔴 DomainPlaceholder 是仓库里的占位页组件 —— 成本构成绝不能是它。
    expect(PAGE).not.toContain('DomainPlaceholder');
    expect(PAGE).not.toContain('getDomainSpec');
  });

  it('页面说明了成本口径必须明示（蓝图加粗句）', () => {
    expect(PAGE).toContain('成本口径必须在页面上明示用了哪种成本法');
  });

  it('页面说明了采购成本的真实来源与两个未交付的上游', () => {
    expect(PAGE).toContain('实际到货金额');
    expect(PAGE).toContain('6.3 采购单当前未交付');
    expect(PAGE).toContain('12 财务');
  });
});

describe('分区块约定', () => {
  it('三个模块各占一条分区', () => {
    expect(COST_SECTIONS.map((s) => s.moduleId)).toEqual([
      '5.2.1.1',
      '5.2.1.2',
      '5.2.1.3',
    ]);
  });

  it('未交付的兄弟分区写明「未交付」并说明口径差异', () => {
    const landed = COST_SECTIONS.find((s) => s.moduleId === '5.2.1.2');
    expect(landed?.delivered).toBe(false);
    expect(landed?.summary).toContain('未交付');
    expect(landed?.summary).toContain('单据级');

    const allocation = COST_SECTIONS.find((s) => s.moduleId === '5.2.1.3');
    expect(allocation?.delivered).toBe(false);
    expect(allocation?.summary).toContain('未交付');
    expect(allocation?.summary).toContain('规则表');
  });

  it('分区 id 是兄弟模块后续扩展时要用的键（唯一且稳定）', () => {
    const ids = COST_SECTIONS.map((s) => s.id);
    expect(ids).toEqual(['composition', 'landed', 'allocation']);
    expect(new Set(ids).size).toBe(ids.length);
  });
});
