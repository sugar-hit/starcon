import { describe, expect, it } from 'vitest';
import {
  BASE,
  candidatesUnavailable,
  conclusionLabel,
  conclusionTone,
  daysToExpiryText,
  emptyState,
  expiryStateLabel,
  expiryStateTone,
  gateSummary,
  licenseeTypeLabel,
  scopeKindLabel,
  scopeSummary,
  severityLabel,
  severityTone,
  type BrandAuthorizationCandidatePage,
  type BrandAuthorizationPublishGate,
} from './brand-authorization-model';

/**
 * 16.4.2.1 品牌授权 前端模型的单测。
 *
 * 🔴 这组用例守的是三件最容易做丢的事：
 *
 * 1. **「判不了」不能显示成「在授权范围内」**，也不能显示成「不合格」——
 *    它是第三种状态，而且**不放行**。
 * 2. **三态空态文案必须各不相同**（同一个空态换个图标不算三态）。
 * 3. **候选读不到 ≠ 没有候选**：上游挂掉时界面要说「读不到」，不能显示成「暂无候选」。
 */

describe('结论口径', () => {
  it('五种结论各有各的人话', () => {
    expect(conclusionLabel('IN_SCOPE')).toBe('在授权范围内');
    expect(conclusionLabel('OUT_OF_SCOPE')).toBe('不在授权范围内');
    expect(conclusionLabel('EXPIRED')).toBe('授权已过期');
    expect(conclusionLabel('NO_AUTHORIZATION')).toBe('没有授权档案');
    expect(conclusionLabel('UNKNOWN')).toBe('判不了（上下文不全）');
  });

  it('UNKNOWN 与 OUT_OF_SCOPE 的色调必须不同', () => {
    expect(conclusionTone('IN_SCOPE')).toBe('success');
    expect(conclusionTone('OUT_OF_SCOPE')).toBe('danger');
    expect(conclusionTone('EXPIRED')).toBe('danger');
    expect(conclusionTone('NO_AUTHORIZATION')).toBe('danger');
    expect(conclusionTone('UNKNOWN')).toBe('warning');
    expect(conclusionTone('UNKNOWN')).not.toBe(conclusionTone('IN_SCOPE'));
    expect(conclusionTone('UNKNOWN')).not.toBe(conclusionTone('OUT_OF_SCOPE'));
  });

  it('分级的文案与色调', () => {
    expect(severityLabel('BLOCKING')).toBe('阻断刊登');
    expect(severityLabel('REVIEW')).toBe('需人工复核');
    expect(severityLabel('NONE')).toBe('通过');
    expect(severityTone('BLOCKING')).toBe('danger');
    expect(severityTone('REVIEW')).toBe('warning');
    expect(severityTone('NONE')).toBe('success');
  });

  it('到期状态的文案与色调', () => {
    expect(expiryStateLabel('ACTIVE')).toBe('生效中');
    expect(expiryStateLabel('EXPIRING')).toBe('即将到期');
    expect(expiryStateLabel('EXPIRED')).toBe('已过期');
    expect(expiryStateLabel('NOT_YET_EFFECTIVE')).toBe('尚未生效');
    expect(expiryStateLabel('DISABLED')).toBe('已停用');
    expect(expiryStateTone('ACTIVE')).toBe('success');
    expect(expiryStateTone('EXPIRING')).toBe('warning');
    expect(expiryStateTone('EXPIRED')).toBe('danger');
  });

  it('剩余天数：已过期 / 今天到期 / 还剩几天', () => {
    expect(daysToExpiryText(-3)).toBe('已过期 3 天');
    expect(daysToExpiryText(0)).toBe('今天到期');
    expect(daysToExpiryText(12)).toBe('还剩 12 天');
  });
});

describe('刊登闸门摘要', () => {
  const gate = (over: Partial<BrandAuthorizationPublishGate>): BrandAuthorizationPublishGate => ({
    allowed: false,
    blocking: true,
    severity: 'BLOCKING',
    conclusion: 'OUT_OF_SCOPE',
    reasons: [],
    evaluatedAt: '2026-06-01T00:00:00Z',
    ruleVersion: 1,
    checkId: 'check-1',
    brandId: 'brand-1',
    ...over,
  });

  it('在授权范围内 ⇒ 明确说允许刊登', () => {
    expect(gateSummary(gate({ allowed: true, blocking: false, severity: 'NONE', conclusion: 'IN_SCOPE' })))
      .toContain('允许刊登');
  });

  it('🔴 判不了 ⇒ 说「判不了」并说明不放行（不是「不合规」）', () => {
    const summary = gateSummary(
      gate({ allowed: false, blocking: false, severity: 'REVIEW', conclusion: 'UNKNOWN' }),
    );
    expect(summary).toContain('判不了');
    expect(summary).toContain('不放行');
    expect(summary).not.toContain('不允许刊登');
  });

  it('明确越权 ⇒ 说「不允许刊登」并写明结论', () => {
    const summary = gateSummary(gate({}));
    expect(summary).toContain('不允许刊登');
    expect(summary).toContain('不在授权范围内');
  });

  it('没有结论时是空串（不编一句）', () => {
    expect(gateSummary(null)).toBe('');
  });
});

describe('三态空态', () => {
  it('三种空态的标题与说明各不相同', () => {
    const noAuthorization = emptyState('NO_AUTHORIZATION');
    const noExpiring = emptyState('NO_EXPIRING');
    const noCheck = emptyState('NO_CHECK');
    expect(noAuthorization.title).not.toBe(noExpiring.title);
    expect(noExpiring.title).not.toBe(noCheck.title);
    expect(noAuthorization.description).not.toBe(noExpiring.description);
    expect(noExpiring.description).not.toBe(noCheck.description);
  });

  it('空态说明里要点出各自的关键事实', () => {
    expect(emptyState('NO_AUTHORIZATION').description).toContain('授权档案');
    expect(emptyState('NO_EXPIRING').description).toContain('上新');
    expect(emptyState('NO_CHECK').description).toContain('校验');
  });
});

describe('候选读不到 ≠ 没有候选', () => {
  const page = (note: string, count: number): BrandAuthorizationCandidatePage => ({
    items: count === 0 ? [] : [{ id: '1', code: 'c', name: 'n', status: '', note: '' }],
    total: count,
    note,
  });

  it('上游读不到（note 说明原因）⇒ 判为「读不到」', () => {
    expect(candidatesUnavailable(page('catalog 服务不可达', 0))).toBe(true);
  });

  it('真的没有候选（note 是正常的来源说明）⇒ 不判为「读不到」', () => {
    expect(candidatesUnavailable(page('03 品牌档案（catalog，只读）。', 0))).toBe(false);
  });

  it('有候选 ⇒ 不判为「读不到」', () => {
    expect(candidatesUnavailable(page('catalog 服务不可达', 2))).toBe(false);
  });

  it('没有候选页 ⇒ 判为「读不到」', () => {
    expect(candidatesUnavailable(null)).toBe(true);
  });
});

describe('范围与类型文案', () => {
  it('范围维度的人话', () => {
    expect(scopeKindLabel('SHOP')).toBe('店铺');
    expect(scopeKindLabel('COUNTRY')).toBe('国家');
    expect(scopeKindLabel('CATEGORY')).toBe('类目');
  });

  it('被授权主体类型的人话', () => {
    expect(licenseeTypeLabel('SUPPLIER')).toBe('供应商');
    expect(licenseeTypeLabel('DISTRIBUTOR')).toBe('经销商');
    expect(licenseeTypeLabel('AGENT')).toBe('代理商');
    expect(licenseeTypeLabel('SELF')).toBe('品牌自有主体');
  });

  it('没有登记范围 ⇒ 显示「全部（未限定范围）」而不是空', () => {
    expect(scopeSummary([])).toBe('全部（未限定范围）');
  });

  it('按维度归并', () => {
    expect(
      scopeSummary([
        { scopeKind: 'SHOP', scopeValue: 's1', scopeLabel: '德国店' },
        { scopeKind: 'COUNTRY', scopeValue: 'DE', scopeLabel: '德国' },
      ]),
    ).toBe('店铺：德国店 / 国家：德国');
  });

  it('没有中文标签时退回原值', () => {
    expect(scopeSummary([{ scopeKind: 'COUNTRY', scopeValue: 'DE', scopeLabel: '' }])).toBe(
      '国家：DE',
    );
  });
});

describe('白名单前缀', () => {
  it('🔴 用独立前缀，不与 compliance-precheck / listing-compliance-precheck 混', () => {
    expect(BASE).toBe('channel/brand-authorizations');
    expect(BASE).not.toContain('compliance-precheck');
  });
});
