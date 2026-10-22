import { describe, expect, it } from 'vitest';
import {
  accrualStatusLabel,
  attributionCountText,
  attributionModelLabel,
  attributionMoney,
  attributionPercent,
  attributionRoiText,
  commissionBasisLabel,
  emptyStateText,
  poolWeightBasisLabel,
  refundTreatmentLabel,
  unavailableDimensions,
} from './influencer-attribution-model';
import type { AttributionCapabilities } from './influencer-attribution-model';

/**
 * 14.5.2.1 带货归因 前端模型单测。
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **三个 GMV 口径分列三栏**（页面层的事，模型层保证三个字段各自独立）。
 * 2. **佣金成本还没进利润表**：`profitIntegration.available` 恒为 `false`。
 * 3. **「没录」不是 0**：`null` 渲染成「未录入」，**不是 0.00**。
 * 4. **空态分两种**：「还没有归因数据」与「筛选无结果」不许混成一句话。
 */
describe('14.5.2.1 带货归因 · 前端模型', () => {
  it('「没录」显示「未录入」，不是 0.00', () => {
    expect(attributionMoney(null)).toBe('未录入');
    expect(attributionMoney(undefined)).toBe('未录入');
    expect(attributionPercent(null)).toBe('未录入');
    expect(attributionRoiText(null)).toBe('未录入');
    expect(attributionCountText(null)).toBe('未录入');
  });

  it('已录的值原样展示：0 是 0.00，不是「未录入」', () => {
    expect(attributionMoney(0)).toBe('0.00');
    expect(attributionPercent(0)).toBe('0.00%');
    expect(attributionRoiText(0)).toBe('0.00');
    expect(attributionCountText(0)).toBe('0');
  });

  it('金额可以带币种，币种是服务端给的，原样拼上', () => {
    expect(attributionMoney(1234.5, 'USD')).toBe('1234.50 USD');
  });

  it('ROI 是倍数不是百分比：10.00 就是 10.00，不变成 1000%', () => {
    expect(attributionRoiText(10)).toBe('10.00');
  });

  it('比率按百分比展示（只是展示格式）', () => {
    expect(attributionPercent(0.1234)).toBe('12.34%');
    expect(attributionPercent(1)).toBe('100.00%');
  });

  it('数量原样展示，不补零也不四舍五入成整数', () => {
    expect(attributionCountText(9)).toBe('9');
    expect(attributionCountText(9.5)).toBe('9.5');
  });

  it('归因模型 / 分摊基准 / 退款口径 / 佣金口径 / 计提状态都有中文说法', () => {
    expect(attributionModelLabel('COOPERATION_FULL')).toBe('合作全额归因');
    expect(attributionModelLabel('CAMPAIGN_POOL_SPLIT')).toBe('同活动内分摊');
    expect(poolWeightBasisLabel('FOLLOWER_SHARE')).toBe('按粉丝数占比');
    expect(poolWeightBasisLabel('COOPERATION_GMV_SHARE')).toBe('按合作 GMV 占比');
    expect(refundTreatmentLabel('GROSS')).toBe('毛额（不扣退款）');
    expect(refundTreatmentLabel('NET')).toBe('净额（扣退款）');
    expect(commissionBasisLabel('COOPERATION_COMMISSION')).toBe('用合作记录里的佣金');
    expect(commissionBasisLabel('RATE_ON_ATTRIBUTED_GMV')).toBe('按归因 GMV × 比例');
    expect(accrualStatusLabel('PENDING')).toBe('待计提');
    expect(accrualStatusLabel('ACCRUED')).toBe('已计提');
    expect(accrualStatusLabel('REVERSED')).toBe('已冲销');
  });

  it('空态分两种：「还没有归因数据」与「筛选无结果」不许混成一句话', () => {
    expect(emptyStateText(0, false)).toContain('还没有归因数据');
    expect(emptyStateText(0, true)).toContain('还没有归因数据');
    expect(emptyStateText(12, true)).toContain('没有符合当前筛选条件');
    expect(emptyStateText(12, true)).not.toContain('还没有归因数据');
  });

  it('能力声明里 available=false 的维度要被单独列出来（页面必须让人看见缺口）', () => {
    const capabilities = {
      dimensions: [
        { dimension: 'attribution_rule', description: '归因规则', available: true, source: '', ownerModule: '', impact: '' },
        { dimension: 'platform_sync', description: '平台带货数据同步', available: false, source: '', ownerModule: '适配层', impact: '只来自本地合作记录' },
        { dimension: 'profit_integration', description: '佣金进利润表', available: false, source: '', ownerModule: '13.4', impact: '没有进利润表' },
      ],
    } as unknown as AttributionCapabilities;
    const unavailable = unavailableDimensions(capabilities);
    expect(unavailable.map((d) => d.dimension)).toEqual(['platform_sync', 'profit_integration']);
    expect(unavailableDimensions(null)).toEqual([]);
  });
});
