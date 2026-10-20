import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const PAGE = readFileSync(
  fileURLToPath(new URL('./page.tsx', import.meta.url)),
  'utf8',
);

const WORKSPACE = readFileSync(
  fileURLToPath(
    new URL('../../../components/pricing/price-anomaly-workspace.tsx', import.meta.url),
  ),
  'utf8',
);

const MODEL = readFileSync(
  fileURLToPath(new URL('../../../components/pricing/price-anomaly-model.ts', import.meta.url)),
  'utf8',
);

/**
 * 5.5.2.1 异常告警的**页面路由与口径约定**断言。
 *
 * <p>本文件把蓝图那句话钉在页面上：
 * 「**站点价差倒挂是跨境特有的风险**：德国站定价低于美国站会引发跨区窜货与合规问题。」
 *
 * <ol>
 *   <li>路由目录存在，页面渲染的是**真实工作区**而不是占位页；
 *   <li>页面写明四类异常与站点两两比对（不是全平台平均价差）；
 *   <li>页面写明平台价 / 内部价的真实来源与两个未交付的上游 ——
 *       把「靠登记进来」藏起来，界面就会让人以为那是平台权威价；
 *   <li>页面写明下游 1 工作台预警未交付。
 * </ol>
 */
describe('/pricing/anomaly-alerts 路由', () => {
  it('页面渲染的是真实工作区组件，不是占位页', () => {
    expect(PAGE).toContain('PriceAnomalyWorkspace');
    // 🔴 DomainPlaceholder 是仓库里的占位页组件 —— 异常告警绝不能是它。
    expect(PAGE).not.toContain('DomainPlaceholder');
    expect(PAGE).not.toContain('getDomainSpec');
  });

  it('页面说明了四类异常', () => {
    expect(PAGE).toContain('平台价与内部价长期不一致');
    expect(PAGE).toContain('售价低于成本线');
    expect(PAGE).toContain('平台自动降价');
    expect(PAGE).toContain('多站点价格倒挂');
  });

  it('页面说明了站点价差倒挂是跨境特有风险（蓝图加粗句）', () => {
    expect(PAGE).toContain('站点价差倒挂是跨境特有的风险');
    expect(PAGE).toContain('德国站定价低于美国站会引发跨区窜货与合规问题');
  });

  it('页面说明了平台价与内部价的真实来源与未交付的上游', () => {
    expect(PAGE).toContain('无价格列');
    expect(PAGE).toContain('4.3.2.2 平台状态回流只回流状态、不回流价格');
    expect(PAGE).toContain('5.2.1.1 成本构成');
  });

  it('页面说明了下游 1 工作台预警未交付', () => {
    expect(PAGE).toContain('1 工作台预警');
    expect(PAGE).toContain('未交付');
  });
});

describe('工作区的口径约定', () => {
  it('站点倒挂在界面上写「谁低于谁」', () => {
    // 🔴 蓝图加粗句在界面上的技术落点：方向必须一眼可见。
    expect(WORKSPACE).toContain('sitePairText');
    expect(WORKSPACE).toContain('低于');
  });

  it('页面上没有把 null 渲染成 0 的写法', () => {
    // 金额一律走 moneyText / moneyWithCurrency（null → 「未登记」）。
    expect(WORKSPACE).toContain('moneyWithCurrency');
    expect(WORKSPACE).not.toMatch(/\?\? '0\.00'/);
    expect(WORKSPACE).not.toMatch(/\|\| 0\b/);
  });

  it('页面上写明成本线来自 5.2.1.1 的成本事实，而不是拍脑袋的成本线', () => {
    expect(WORKSPACE).toContain('costLineText');
    expect(MODEL).toContain('已知部分合计');
    expect(MODEL).toContain('不参与判定');
  });

  it('页面上区分了「长期」与「发生过」', () => {
    expect(WORKSPACE).toContain('durationText');
    expect(WORKSPACE).toContain('发生过的事');
  });
});
