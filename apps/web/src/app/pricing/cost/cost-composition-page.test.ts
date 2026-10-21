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
 *   <li>分区表里三个模块各占一条，每条分区的**「未交付」标记与摘要口径一致** ——
 *       画一个空白框让人以为已交付，比明说「未交付」糟得多；
 *       反过来，已交付的分区摘要里也不该再残留「未交付」字样；
 *   <li>兄弟模块要加分区时**不需要重写本页骨架** —— 所以本页不得引入
 *       「整页只有一个模块」的假设。
 * </ol>
 */

const PAGE = readFileSync(fileURLToPath(new URL('./page.tsx', import.meta.url)), 'utf8');

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
    expect(COST_SECTIONS.map((s) => s.moduleId)).toEqual(['5.2.1.1', '5.2.1.2', '5.2.1.3']);
  });

  it('未交付的分区写明「未交付」，已交付的分区不写「未交付」', () => {
    // 🔴 这条断言**不钉死「哪个兄弟模块还没交付」**：
    // 5.2.1.2 到岸成本已交付，5.2.1.3 成本分摊下轮就会落地，delivered 标志会来回翻。
    // 硬编码 moduleId 的断言每翻一次就要改一次测试，而每改一次就会漏一处 ——
    // 本车道当初就是漏改了兄弟模块 5.2.1.1 留下的这条用例，把集成头的前端测试打红了。
    // 所以这里断言的是**对全体分区都成立的不变量**：
    //   已交付 ⇒ 摘要不得再写「未交付」；未交付 ⇒ 摘要必须写明「未交付」。
    for (const section of COST_SECTIONS) {
      if (section.delivered) {
        expect(section.summary, `${section.moduleId} 已交付，摘要不得再写「未交付」`).not.toContain(
          '未交付',
        );
      } else {
        expect(section.summary, `${section.moduleId} 未交付，摘要必须写明「未交付」`).toContain(
          '未交付',
        );
      }
    }
  });

  it('已交付的两个口径各自写明口径差异', () => {
    // 🔴 口径关键词是**稳定文案**：5.2.1.3 落地后仍然只有 5.2.1.1 / 5.2.1.2 两条已交付分区，
    // 这两条的口径措辞不会因为兄弟模块落地而变 —— 所以可以硬断言。
    // 但**不断言未交付分区的任何具体关键词**：未交付分区的摘要会随那个模块落地被整段换掉。
    const composition = COST_SECTIONS.find((s) => s.moduleId === '5.2.1.1');
    expect(composition, '5.2.1.1 必须在分区表里').toBeTruthy();
    expect(composition?.delivered).toBe(true);
    // 5.2.1.1 的口径 = SKU 级单位成本。现文案写作「SKU 的单位成本构成」，
    // 所以断言口径关键词本身，不锁死「SKU 的 / SKU 级」这类措辞差异。
    expect(composition?.summary).toMatch(/SKU(\s*的|\s*级)?\s*单位成本/);

    const landed = COST_SECTIONS.find((s) => s.moduleId === '5.2.1.2');
    expect(landed, '5.2.1.2 必须在分区表里').toBeTruthy();
    expect(landed?.delivered).toBe(true);
    expect(landed?.summary).toContain('单据级');
    // 到岸成本摘要必须同时点名 5.2.1.1 的口径（SKU 级）——
    // 两个分区共用 /pricing/cost 路由，不点明「单据级 vs SKU 级」，运营会把两个数当成同一个数。
    expect(landed?.summary).toContain('SKU 级');
  });

  it('分区 id 是兄弟模块后续扩展时要用的键（唯一且稳定）', () => {
    const ids = COST_SECTIONS.map((s) => s.id);
    expect(ids).toEqual(['composition', 'landed', 'allocation']);
    expect(new Set(ids).size).toBe(ids.length);
  });
});
