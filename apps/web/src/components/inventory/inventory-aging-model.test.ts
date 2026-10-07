import { describe, expect, it } from 'vitest';
import {
  agingBasisText,
  agingBucketLabel,
  agingDaysText,
  capitalTieText,
  dispositionLabel,
  dispositionNoteText,
  slowMovingStatusLabel,
  thresholdSourceText,
} from './inventory-aging-model';

/**
 * 7.6.1.1 库龄与呆滞的展示口径。
 *
 * <p>这几个函数承载的是「同一个数字怎么读」，不是格式化问题：
 *
 * <ul>
 *   <li>库龄读不出来时若显示 0 天，用户会以为「刚入库」，而它是「算不出来」；
 *   <li>库龄口径若是代理值（无批次的建档日），不标出来会被当成真实收货日；
 *   <li>占用资金不可用时若显示 0 元，用户会以为这些呆滞货不占资金 ——
 *       而蓝图说这恰恰是最容易被忽略的利润黑洞。
 * </ul>
 */
describe('库龄天数文案', () => {
  it('算不出库龄时显示不可得而不是 0 天', () => {
    expect(agingDaysText(null)).toBe('库龄不可得（无入库日期）');
    expect(agingDaysText(undefined)).toBe('库龄不可得（无入库日期）');
  });

  it('今天入库与已存放 N 天分开表述', () => {
    expect(agingDaysText(0)).toBe('今天入库');
    expect(agingDaysText(91)).toBe('已存放 91 天');
  });
});

describe('库龄分桶标签', () => {
  it('缺少后端标签时按档位名兜底而不是显示空白', () => {
    expect(agingBucketLabel('AGE_0_30')).toBe('0-30 天');
    expect(agingBucketLabel('AGE_31_60')).toBe('31-60 天');
    expect(agingBucketLabel('AGE_61_90')).toBe('61-90 天');
    expect(agingBucketLabel('AGE_90_PLUS')).toBe('90+ 天');
    expect(agingBucketLabel(undefined)).toBe('未知档位');
  });

  it('后端给了标签就以后端为准', () => {
    expect(agingBucketLabel('AGE_90_PLUS', '90+ 天')).toBe('90+ 天');
  });
});

describe('库龄口径标注', () => {
  it('批次收货日显示为真实入库日期', () => {
    expect(agingBasisText('LOT_RECEIVED_ON', '批次收货日', '2026-07-08')).toBe(
      '批次收货日（2026-07-08）',
    );
  });

  it('库位建档日必须标为代理值', () => {
    const text = agingBasisText('BUCKET_CREATED_AT', '库位建档日（代理）', '2026-10-01');
    expect(text).toContain('代理值');
    expect(text).toContain('2026-10-01');
  });
});

describe('占用资金口径', () => {
  it('不可用时给出原因而不是显示 0 元', () => {
    const text = capitalTieText(false, '本服务库存事实层无成本列，无法估算占用资金');
    expect(text).toContain('暂不可估算');
    expect(text).toContain('无成本列');
    expect(text).not.toBe('0');
  });

  it('连原因都没有时也不能显示成 0', () => {
    const text = capitalTieText(false, null);
    expect(text).toBe('占用资金暂不可估算（缺少成本口径）');
    expect(text).not.toContain('0 元');
  });
});

describe('档位与处置标签', () => {
  it('呆滞档位与处置动作都有中文标签', () => {
    expect(slowMovingStatusLabel('OBSOLETE')).toBe('呆滞');
    expect(slowMovingStatusLabel('SLOW_MOVING')).toBe('滞销');
    expect(slowMovingStatusLabel('NORMAL')).toBe('正常');
    expect(dispositionLabel('SCRAP')).toBe('报废');
    expect(dispositionLabel('RETURN_TO_VENDOR')).toBe('退仓');
    expect(dispositionLabel('MARKDOWN')).toBe('降价促销');
    expect(dispositionLabel('CLEARANCE')).toBe('清仓');
    expect(dispositionLabel('HOLD')).toBe('暂不处置');
  });

  it('处置说明必须讲清这是建议而不是动作', () => {
    expect(dispositionNoteText()).toContain('仅供参考');
    expect(dispositionNoteText()).toContain('不触发任何动作');
  });
});

describe('阈值来源', () => {
  it('区分本 SKU 配置与默认兜底', () => {
    expect(thresholdSourceText('POLICY')).toBe('本 SKU 配置');
    expect(thresholdSourceText('DEFAULT')).toBe('默认兜底（该 SKU 未配置）');
  });
});
