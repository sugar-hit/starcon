import { describe, expect, it } from 'vitest';
import {
  costCalculationBusinessTypeLabel,
  costCalculationCoverageLabel,
  costCalculationDifferenceLabel,
  costCalculationEntryStatusLabel,
  costCalculationPeriodLabel,
  costCalculationReasonLabel,
  costCalculationRunStatusLabel,
  formatCostCalculationMoney,
  formatCostCalculationQuantity,
  sortCostCalculationEntries,
} from './cost-calculation-model';

/**
 * 12.4.1.2 成本计算执行前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li><b>算不出来 ≠ 0</b>：服务端给 {@code null} 的地方前端一律显示「—」，
 *       绝不显示 0.0000（那会被读成「成本是 0」）；</li>
 *   <li><b>缺口必须看得见</b>：覆盖量与出库量并排显示，
 *       有缺口的那一行会被排到最后并带上原因；</li>
 *   <li><b>金额只原样展示</b>：前端不做任何业务算术，差额由服务端算；</li>
 *   <li>可读名覆盖**全部词表取值**，且不认识的原样返回（新增取值时页面不会显示 undefined）。</li>
 * </ol>
 */

describe('业务类型与状态的可读名', () => {
  it('蓝图点名的三类出库各有可读名', () => {
    expect(costCalculationBusinessTypeLabel('SALES_OUTBOUND')).toBe('销售出库');
    expect(costCalculationBusinessTypeLabel('SHIPMENT')).toBe('发货');
    expect(costCalculationBusinessTypeLabel('PLATFORM_TRANSFER_OUT')).toBe('平台仓转出');
    expect(costCalculationBusinessTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('批次状态各有可读名', () => {
    expect(costCalculationRunStatusLabel('PENDING')).toBe('计算中');
    expect(costCalculationRunStatusLabel('COMPLETED')).toBe('已完成');
    expect(costCalculationRunStatusLabel('FAILED')).toBe('已失败');
    expect(costCalculationRunStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('分录状态写明「有缺口」，不假装算全了', () => {
    expect(costCalculationEntryStatusLabel('CALCULATED')).toBe('已结转');
    expect(costCalculationEntryStatusLabel('INCOMPLETE')).toBe('有缺口');
    expect(costCalculationEntryStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('批次类型各有可读名', () => {
    expect(costCalculationReasonLabel('INITIAL')).toBe('首次计算');
    expect(costCalculationReasonLabel('METHOD_SWITCH')).toBe('方法切换重算');
    expect(costCalculationReasonLabel('MANUAL')).toBe('人工重算');
    expect(costCalculationReasonLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('算不出来不是 0', () => {
  it('金额为空时显示破折号，不显示 0.0000', () => {
    expect(formatCostCalculationMoney(null)).toBe('—');
    expect(formatCostCalculationMoney(undefined)).toBe('—');
    expect(formatCostCalculationMoney(null)).not.toContain('0');
  });

  it('有金额时按四位小数原样展示', () => {
    expect(formatCostCalculationMoney(1120)).toBe('1120.0000');
    expect(formatCostCalculationMoney(9.5)).toBe('9.5000');
  });

  it('数量按六位小数展示', () => {
    expect(formatCostCalculationQuantity(null)).toBe('—');
    expect(formatCostCalculationQuantity(110)).toBe('110.000000');
  });
});

describe('覆盖量与缺口', () => {
  it('覆盖量与出库量并排展示，差一眼看得见', () => {
    expect(costCalculationCoverageLabel(110, 110)).toBe('110.000000 / 110.000000');
    expect(costCalculationCoverageLabel(200, 250)).toBe('200.000000 / 250.000000');
  });

  it('覆盖量为空时显示破折号', () => {
    expect(costCalculationCoverageLabel(null, 10)).toBe('—');
  });
});

describe('与对比批次的差额', () => {
  it('正数说「更贵」，负数说「更便宜」，零说「相同」', () => {
    expect(costCalculationDifferenceLabel(90)).toBe('更贵 90.0000');
    expect(costCalculationDifferenceLabel(-175)).toBe('更便宜 175.0000');
    expect(costCalculationDifferenceLabel(0)).toBe('与对比批次相同');
  });

  it('没有差额时显示破折号', () => {
    expect(costCalculationDifferenceLabel(null)).toBe('—');
  });
});

describe('期间的展示', () => {
  it('期间是左闭右开的，措辞要说清「止不含」', () => {
    expect(costCalculationPeriodLabel('2026-01-01T00:00:00Z', '2026-02-01T00:00:00Z')).toBe(
      '2026-01-01 起 ~ 2026-02-01 前',
    );
  });

  it('缺一端时显示破折号', () => {
    expect(costCalculationPeriodLabel(null, null)).toBe('— 起 ~ — 前');
  });
});

describe('分录的排序', () => {
  it('有缺口的排在最后', () => {
    const entries = [
      { status: 'INCOMPLETE', costAmount: 500, occurredAt: '2026-01-10T00:00:00Z' },
      { status: 'CALCULATED', costAmount: 820, occurredAt: '2026-01-20T00:00:00Z' },
      { status: 'CALCULATED', costAmount: 300, occurredAt: '2026-01-10T00:00:00Z' },
    ];
    expect(sortCostCalculationEntries(entries).map((e) => e.costAmount)).toEqual([300, 820, 500]);
  });

  it('不改动原数组', () => {
    const entries = [
      { status: 'INCOMPLETE', costAmount: 500, occurredAt: '2026-01-10T00:00:00Z' },
      { status: 'CALCULATED', costAmount: 300, occurredAt: '2026-01-20T00:00:00Z' },
    ];
    const sorted = sortCostCalculationEntries(entries);
    expect(entries[0]?.status).toBe('INCOMPLETE');
    expect(sorted[0]?.status).toBe('CALCULATED');
  });
});
