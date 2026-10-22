import { describe, expect, it } from 'vitest';
import {
  BASE,
  MEASURES,
  REMEDIATION_STATUSES,
  SOURCES,
  STATUSES,
  emptyState,
  emptyStateKind,
  impactLabel,
  impactTone,
  measureLabel,
  moneyText,
  remediationStatusLabel,
  sellableText,
  sourceLabel,
  statusLabel,
  statusTone,
  totalsText,
  violationTypeLabel,
} from './platform-violations-model';

/**
 * 16.1.2.1 违规与处罚 前端模型的单测。
 *
 * 🔴 这组用例守的是六件最容易做丢的事：
 *
 * 1. **扣款金额永远带币种**，没有金额时显示成空串而不是 0。
 * 2. **不同币种不直接相加**：合计按币种分组，**没有**跨币种的总数。
 * 3. **「尚无处罚措施」不等于「没问题」** —— 色调与文案都单独一份。
 * 4. **「读不到库存」不等于「没有库存」** —— 显示「未知」，不显示 0。
 * 5. **值域与后端一致**：处罚措施恰好五种（警告 / 下架 / 限制销售 / 扣款 / 停号）。
 * 6. **三态空态文案必须各不相同**（同一个空态换个图标不算三态）。
 */
describe('处罚措施值域（受控值域，与后端 CHECK 一致）', () => {
  it('恰好是蓝图点名的五种', () => {
    expect(MEASURES).toEqual([
      'WARNING',
      'DELIST',
      'RESTRICT_SALES',
      'DEDUCTION',
      'SUSPEND_ACCOUNT',
    ]);
    expect(measureLabel('WARNING')).toBe('警告');
    expect(measureLabel('DELIST')).toBe('下架');
    expect(measureLabel('RESTRICT_SALES')).toBe('限制销售');
    expect(measureLabel('DEDUCTION')).toBe('扣款');
    expect(measureLabel('SUSPEND_ACCOUNT')).toBe('停号');
  });

  it('处理状态与整改状态的值域与后端一致', () => {
    expect(STATUSES).toEqual(['PENDING', 'IN_PROGRESS', 'RECTIFIED', 'APPEALING', 'CLOSED']);
    expect(REMEDIATION_STATUSES).toEqual([
      'OPEN',
      'IN_PROGRESS',
      'SUBMITTED',
      'VERIFIED',
      'REJECTED',
    ]);
    expect(SOURCES).toEqual(['PLATFORM_SYNC', 'MANUAL']);
    expect(statusLabel('PENDING')).toBe('未处理');
    expect(statusLabel('IN_PROGRESS')).toBe('处理中');
    expect(statusLabel('RECTIFIED')).toBe('已整改');
    expect(statusLabel('APPEALING')).toBe('申诉中');
    expect(statusLabel('CLOSED')).toBe('已关闭');
    expect(remediationStatusLabel('SUBMITTED')).toContain('待验收');
  });

  it('违规类型的人话名', () => {
    expect(violationTypeLabel('PRODUCT_SAFETY')).toContain('产品安全');
    expect(violationTypeLabel('PROHIBITED_ITEM')).toBe('违禁品');
    expect(violationTypeLabel('NOT_A_TYPE')).toBe('NOT_A_TYPE');
  });

  it('来源的人话名区分平台同步与人工登记', () => {
    expect(sourceLabel('PLATFORM_SYNC')).toBe('平台同步');
    expect(sourceLabel('MANUAL')).toBe('人工登记');
  });
});

describe('🔴 扣款金额带币种，且不跨币种合计', () => {
  it('金额展示串带币种', () => {
    expect(moneyText(120.5, 'USD')).toBe('120.50 USD');
    expect(moneyText(0, 'EUR')).toBe('0.00 EUR');
  });

  it('没有金额时显示成空串，绝不是「0」', () => {
    expect(moneyText(null, null)).toBe('');
    expect(moneyText(undefined, undefined)).toBe('');
    expect(moneyText(null, null)).not.toBe('0');
    expect(moneyText(null, null)).not.toBe('0.00');
  });

  it('🔴 多个币种时明确写「未折算、未合计」，绝不加成一个数', () => {
    const text = totalsText([
      { currency: 'EUR', amount: 50, penaltyCount: 1 },
      { currency: 'USD', amount: 125, penaltyCount: 2 },
    ]);
    expect(text).toContain('EUR 50.00');
    expect(text).toContain('USD 125.00');
    expect(text).toContain('未折算');
    expect(text).toContain('未合计');
    // 🔴 不能出现一个「总额」把两个币种混在一起。
    expect(text).not.toContain('175');
  });

  it('只有一个币种时直接显示那个币种的合计', () => {
    expect(totalsText([{ currency: 'USD', amount: 125, penaltyCount: 2 }])).toBe('USD 125.00');
  });

  it('一条扣款都没有时显示「暂无扣款」', () => {
    expect(totalsText([])).toBe('暂无扣款');
    expect(totalsText(null)).toBe('暂无扣款');
  });
});

describe('🔴 影响档位：派生，不是存的', () => {
  it('档位的人话名', () => {
    expect(impactLabel('CRITICAL')).toContain('停号');
    expect(impactLabel('HIGH')).toContain('下架');
    expect(impactLabel('MEDIUM')).toContain('扣款');
    expect(impactLabel('LOW')).toContain('警告');
  });

  it('🔴「尚无处罚措施」有自己的档位与文案，不等于「没问题」', () => {
    expect(impactLabel('NONE')).toContain('尚无处罚措施');
    expect(impactLabel('NONE')).not.toContain('正常');
    expect(impactLabel('NONE')).not.toContain('健康');
  });

  it('🔴「尚无处罚措施」用中性色，不与「轻微」共用绿色', () => {
    expect(impactTone('NONE')).toBe('neutral');
    expect(impactTone('LOW')).toBe('success');
    expect(impactTone('CRITICAL')).toBe('danger');
    expect(impactTone('HIGH')).toBe('danger');
    expect(impactTone('MEDIUM')).toBe('warning');
  });
});

describe('🔴 处理状态色调', () => {
  it('未处理最严重、已关闭中性', () => {
    expect(statusTone('PENDING')).toBe('danger');
    expect(statusTone('IN_PROGRESS')).toBe('warning');
    expect(statusTone('RECTIFIED')).toBe('success');
    expect(statusTone('APPEALING')).toBe('warning');
    expect(statusTone('CLOSED')).toBe('neutral');
  });
});

describe('🔴 库存「读不到」不等于「没有库存」', () => {
  it('读不到 / 没有库存行显示成「未知」，绝不显示 0', () => {
    expect(
      sellableText({
        skuId: 'sku-1',
        skuCode: 'SKU-1',
        sellable: null,
        found: false,
        note: '',
      }),
    ).toBe('未知');
    expect(
      sellableText({
        skuId: 'sku-1',
        skuCode: 'SKU-1',
        sellable: 0,
        found: true,
        note: '',
      }),
    ).toBe('0.00');
  });
});

describe('🔴 三态空态各不相同', () => {
  it('没有店铺 / 还没登记过违规 / 筛选无结果 分开说', () => {
    expect(emptyStateKind(0, 0, false)).toBe('NO_SHOP');
    expect(emptyStateKind(3, 0, false)).toBe('NO_DATA');
    expect(emptyStateKind(3, 0, true)).toBe('NO_MATCH');
    expect(emptyStateKind(3, 5, false)).toBe('NO_MATCH');
  });

  it('三种空态的标题与说明都不相同', () => {
    const titles = new Set([
      emptyState('NO_SHOP').title,
      emptyState('NO_DATA').title,
      emptyState('NO_MATCH').title,
    ]);
    expect(titles.size).toBe(3);
    const descriptions = new Set([
      emptyState('NO_SHOP').description,
      emptyState('NO_DATA').description,
      emptyState('NO_MATCH').description,
    ]);
    expect(descriptions.size).toBe(3);
  });

  it('「还没登记过违规」这句话说清了从哪来', () => {
    expect(emptyState('NO_DATA').description).toContain('平台违规通知同步');
    expect(emptyState('NO_DATA').description).toContain('人工登记');
  });
});

describe('白名单前缀独立', () => {
  it('BASE 是本模块自己的独立前缀（不并进既有 channel 分支）', () => {
    expect(BASE).toBe('channel/platform-violations');
  });
});
