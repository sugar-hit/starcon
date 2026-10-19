import { describe, expect, it } from 'vitest';
import {
  COST_BASIS,
  costBasisLabel,
  costBasisTone,
  costTotalOf,
  defectShareReading,
  DISCOVERY_STAGE_LABELS,
  excludedCostNote,
  formatMoney,
  formatPercent,
  openBoxReading,
  photoClusterReading,
  rateTone,
  severityTone,
  supplierDisplayName,
} from './quality-analysis-model';
import type { CostCurrencyBreakdown, CostItem } from '../../../../../packages/api-contracts/src/index';

/**
 * 11.5.2.1 质量分析的纯展示派生逻辑。
 *
 * <p>这些函数承载蓝图加粗句「<b>质量成本（退货物流 + 损耗 + 差评影响）应量化</b>」
 * 在界面上的语义，所以单独覆盖：
 *
 * <ul>
 *   <li><b>未登记 ≠ 0</b>：参数没登记时报「未登记」并排除在合计外；
 *       按 0 画出来会被读成「质量没花钱」，而真相是「算不出来」——
 *       而「偏小的质量成本」正好是那条加粗句要防的方向。</li>
 *   <li><b>开箱率必须与覆盖率并排</b>：覆盖率 40% 时的开箱率
 *       只代表「已登记的那 40%」，剩下的沉默<b>不能</b>被读成「没开箱问题」。</li>
 *   <li><b>照片聚类不得读成「外观相似」</b>：口径是内容指纹完全相同。</li>
 * </ul>
 */
describe('11.5.2.1 质量成本依据等级', () => {
  it('三值依据的标签与语气各不相同，未登记是警告色', () => {
    expect(costBasisLabel('MEASURED')).toBe('实测');
    expect(costBasisLabel('ESTIMATED')).toBe('按参数估算');
    expect(costBasisLabel('NOT_REGISTERED')).toBe('参数未登记');

    // 实测是正向（这是账），估算是中性（这是算法），未登记是危险色（这是缺数据）。
    expect(costBasisTone('MEASURED')).toBe('success');
    expect(costBasisTone('ESTIMATED')).toBe('warning');
    expect(costBasisTone('NOT_REGISTERED')).toBe('danger');
    // 未知取值不猜。
    expect(costBasisTone('WHATEVER')).toBe('neutral');
    expect(costBasisTone(null)).toBe('neutral');
    expect(COST_BASIS.NOT_REGISTERED?.hint).toContain('不是 0');
  });

  const item = (over: Partial<CostItem>): CostItem => ({
    key: 'k',
    label: 'l',
    basis: 'MEASURED',
    basisLabel: '',
    amount: 0,
    basisQty: null,
    basisQtyNote: '',
    unitPrice: null,
    formula: '',
    ...over,
  });

  const breakdown = (over: Partial<CostCurrencyBreakdown>): CostCurrencyBreakdown => ({
    currency: 'USD',
    loss: item({ key: 'loss', basis: 'MEASURED', amount: 50 }),
    returnLogistics: item({ key: 'return-logistics', basis: 'ESTIMATED', amount: 9 }),
    reviewImpact: item({ key: 'review-impact', basis: 'ESTIMATED', amount: 12 }),
    total: 71,
    excludedItemCount: 0,
    ...over,
  });

  it('未登记项的金额是 null 且被排除在合计之外', () => {
    const unregistered = item({ key: 'review-impact', basis: 'NOT_REGISTERED', amount: null });
    const result = costTotalOf(breakdown({ reviewImpact: unregistered, excludedItemCount: 1 }));

    // 合计 = 50 + 9 = 59，**不含**算不出来的差评影响。
    expect(result.total).toBe(59);
    expect(result.excluded).toBe(1);
    expect(excludedCostNote(result.excluded)).toContain('下限');
    expect(excludedCostNote(0)).toContain('全部可计价');
  });

  it('三项都可计价时合计含全部且没有排除', () => {
    const result = costTotalOf(breakdown({}));
    expect(result.total).toBe(71);
    expect(result.excluded).toBe(0);
  });

  it('金额为 null 渲染成「未登记」而不是 0.00', () => {
    expect(formatMoney(null)).toBe('未登记');
    expect(formatMoney(undefined)).toBe('未登记');
    // 真的是 0 就要显示 0.00 —— 「算出来是 0」与「算不出来」是两件事。
    expect(formatMoney(0)).toBe('0.00');
    expect(formatMoney(12.3456)).toBe('12.35');
  });
});

describe('11.5.2.1 开箱率必须和覆盖率一起读', () => {
  it('覆盖率 100% 时开箱率是完整口径', () => {
    const low = openBoxReading(12.5, 100);
    expect(low.text).toContain('口径完整');
    expect(low.tone).toBe('success');

    // 口径完整但数值本身很高 → 危险色（「完整」不等于「没问题」）。
    const high = openBoxReading(31.2, 100);
    expect(high.text).toContain('口径完整');
    expect(high.tone).toBe('danger');
  });

  it('覆盖率不足时明说「只代表已登记的那一部分」', () => {
    const reading = openBoxReading(100, 40);
    expect(reading.text).toContain('覆盖率只有 40.00%');
    expect(reading.tone).toBe('warning');
  });

  it('一条观察都没登记时报「暂无口径」而不是 0%', () => {
    const reading = openBoxReading(0, 0);
    expect(reading.text).toContain('暂无口径');
    expect(reading.text).toContain('没数据');
  });
});

describe('11.5.2.1 聚类与供应商显示', () => {
  it('照片聚类的读法不得暗示图像相似度', () => {
    const repeated = photoClusterReading({ photoCount: 2, attributionCount: 2, repeated: true });
    expect(repeated).toContain('反复上传');
    expect(repeated).toContain('指纹完全相同');

    const single = photoClusterReading({ photoCount: 1, attributionCount: 1, repeated: false });
    expect(single).toContain('不等于「看起来像」');
    expect(single).not.toContain('相似度为');
  });

  it('未登记的供应商显示「未登记供应商」而不是 uuid', () => {
    expect(supplierDisplayName(null)).toBe('未登记供应商');
    expect(supplierDisplayName('   ')).toBe('未登记供应商');
    expect(supplierDisplayName('深圳华强电子')).toBe('深圳华强电子');
  });
});

describe('11.5.2.1 枚举标签与语气', () => {
  it('发现环节顺序即时间先后，且刻意没有「差评」这个取值', () => {
    expect(DISCOVERY_STAGE_LABELS.BUYER_UNBOXING).toBe('买家开箱即发现');
    expect(DISCOVERY_STAGE_LABELS.POST_USE).toBe('使用后发现');
    // 本仓全库没有商品评价表 —— 造一个 NEGATIVE_REVIEW 就是在声称我们读到过买家评价。
    expect(Object.keys(DISCOVERY_STAGE_LABELS)).not.toContain('NEGATIVE_REVIEW');
  });

  it('严重度分四档，致命与严重是危险色', () => {
    expect(severityTone('CRITICAL')).toBe('danger');
    expect(severityTone('MAJOR')).toBe('danger');
    expect(severityTone('MODERATE')).toBe('warning');
    expect(severityTone('MINOR')).toBe('neutral');
  });

  it('非可再销售率 ≥20% 危险、≥5% 警告、其余正向', () => {
    expect(rateTone(25)).toBe('danger');
    expect(rateTone(8)).toBe('warning');
    expect(rateTone(1)).toBe('success');
    expect(rateTone(null)).toBe('success');
  });

  it('占比读数在分母为 0 时说「暂无」而不是 100%', () => {
    expect(defectShareReading(0, 0)).toBe('暂无失败检项');
    expect(defectShareReading(50, 8)).toContain('占全部 8 条失败检项');
  });

  it('百分比格式化保留两位', () => {
    expect(formatPercent(66.666)).toBe('66.67%');
    expect(formatPercent(null)).toBe('—');
  });
});
