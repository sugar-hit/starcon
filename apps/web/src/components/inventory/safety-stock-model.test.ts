import { describe, expect, it } from 'vitest';
import type { SafetyStockEvaluationRow } from '../../../../../packages/api-contracts/src/index';
import {
  alertStatusLabel,
  alertTypeText,
  capabilityLabel,
  coverageDaysText,
  dailyAverageText,
  daysOfCoverText,
  evaluationCoverageText,
  evaluationSummary,
  isPlatformRisk,
  platformEscalationNote,
  registeredDaysText,
  scopeTypeLabel,
  severityEscalated,
  severityLabel,
  severityRank,
  severityText,
  shortfallText,
  suggestionStatusLabel,
  suggestedText,
  targetStockText,
  thresholdText,
  unavailableCapabilities,
  unavailableNotices,
  warehouseKindLabel,
} from './safety-stock-model';

/**
 * 7.2.2.2 安全库存的展示口径。
 *
 * <p>这些用例盯的是「同一个数字怎么读」，不是格式化：
 *
 * <ul>
 *   <li>蓝图加粗的那句「安全库存要区分平台断货风险和自有仓耗尽，FBA 断货造成的损失远大于自有仓」
 *       —— 它的兑现方式是**平台侧一律提一级并排在前面**，所以界面必须显示提级后的级别、
 *       并且说清它是被提过的，而不是把配置值原样显示出来让两种风险长得一样。
 *   <li>算不出来的东西（未登记销量、销量为 0）一律显示「不可得 + 原因」，**绝不显示 0**：
 *       0 天会被读成今天断货，0 件建议会被读成不用补货。
 *   <li>三种「0 条预警」（没配阈值 / 没扫到 / 扫了没风险）必须在文案上分开。
 * </ul>
 */

describe('预警类型与风险面', () => {
  it('两种预警类型用不同的说法而不是都叫库存不足', () => {
    expect(alertTypeText('PLATFORM_STOCKOUT_RISK')).toBe('平台断货风险（FBA）');
    expect(alertTypeText('OWN_WAREHOUSE_DEPLETION')).toBe('自有仓耗尽');
    expect(alertTypeText(undefined)).toBe('未知预警类型');
  });

  it('平台断货风险可被单独识别出来', () => {
    expect(isPlatformRisk('PLATFORM_STOCKOUT_RISK')).toBe(true);
    expect(isPlatformRisk('OWN_WAREHOUSE_DEPLETION')).toBe(false);
    expect(isPlatformRisk(null)).toBe(false);
  });

  it('仓类型标注自有仓与平台仓', () => {
    expect(warehouseKindLabel('OWN')).toBe('自有仓');
    expect(warehouseKindLabel('PLATFORM_FBA')).toBe('平台仓（FBA）');
    expect(warehouseKindLabel(undefined)).toBe('未知仓类型');
  });
});

describe('严重度：FBA 断货损失更大必须落在展示上', () => {
  it('被提级时同时显示配置级别并说明提级来源', () => {
    // 配置 INFO，平台断货被提成 WARNING —— 只显示 WARNING 会让人以为自己配了 WARNING。
    expect(severityText('INFO', 'WARNING')).toBe('警告（配置为「提示」，平台断货风险自动提级）');
    expect(severityEscalated('INFO', 'WARNING')).toBe(true);
  });

  it('未被提级时只显示一个级别，不带多余的注解', () => {
    expect(severityText('WARNING', 'WARNING')).toBe('警告');
    expect(severityEscalated('WARNING', 'WARNING')).toBe(false);
    // 自有仓不会被提级：配置什么就是什么。
    expect(severityText('INFO', 'INFO')).toBe('提示');
    expect(severityEscalated('INFO', 'INFO')).toBe(false);
  });

  it('提级后排序权重严格高于同级自有仓', () => {
    // 蓝图那句「FBA 断货造成的损失远大于自有仓」的兑现点。
    expect(severityRank('CRITICAL')).toBeLessThan(severityRank('WARNING'));
    expect(severityRank('WARNING')).toBeLessThan(severityRank('INFO'));
    // 同为 INFO 时：平台侧提级后是 WARNING（权重 1），自有仓仍是 INFO（权重 2），
    // 平台侧因此排到前面 —— 这正是列表排序依赖的那个性质。
    expect(severityRank('WARNING')).toBeLessThan(severityRank('INFO'));
  });

  it('级别缺失时兜底成未知而不是空白', () => {
    expect(severityLabel(undefined)).toBe('未知级别');
    expect(severityLabel('URGENT')).toBe('未知级别');
    expect(severityRank(null)).toBe(3);
  });
});

describe('算不出来就显示不可得，绝不显示 0', () => {
  it('未登记销量时日均销量与可售天数显示原因', () => {
    expect(dailyAverageText(null)).toBe('日均销量不可得（尚未登记销量）');
    expect(daysOfCoverText(null, null)).toBe('可售天数不可得（尚未登记销量）');
  });

  it('销量登记为零时与缺数据分开表述', () => {
    // 0 天的可售天数会被读成「今天就断货」，而真实状态是「登记为零」。
    expect(dailyAverageText(0)).toBe('日均 0 件（登记为零，不是缺数据）');
    expect(daysOfCoverText(null, 0)).toBe('可售天数不可得（日均销量为 0，除不出天数）');
  });

  it('有销量时可售天数照常显示', () => {
    expect(daysOfCoverText(10, 10)).toBe('可售 10 天');
    expect(dailyAverageText(2.5)).toBe('日均 2.5 件');
  });

  it('建议量不可得时带上后端给的原因而不是显示 0 件', () => {
    // 这是本模块最贵的错误：0 会被读成「不用补货」，该补的那批就这么过去了。
    expect(suggestedText(null, '该 SKU 尚未登记销量')).toBe('建议量不可得（该 SKU 尚未登记销量）');
    expect(suggestedText(null)).toBe('建议量不可得（尚未登记销量，无法推算可售天数）');
    expect(suggestedText(0)).toBe('建议补 0 件');
    expect(suggestedText(120)).toBe('建议补 120 件');
  });

  it('目标库存不可得时不给 0', () => {
    expect(targetStockText(null)).toBe('目标库存不可得');
    expect(targetStockText(220)).toBe('目标库存 220 件');
  });
});

describe('数量与配置文案', () => {
  it('阈值 0 是合法取值照常显示而不是未配置', () => {
    expect(thresholdText(0)).toBe('0 件');
    expect(thresholdText(150)).toBe('150 件');
    expect(thresholdText(null)).toBe('阈值不可得');
  });

  it('覆盖天数与缺口量照常显示', () => {
    expect(coverageDaysText(7)).toBe('7 天');
    expect(coverageDaysText(null)).toBe('未配覆盖天数');
    expect(shortfallText(90)).toBe('缺口 90 件');
    expect(shortfallText(null)).toBe('缺口不可得');
  });

  it('登记天数同时显示登记次数', () => {
    expect(registeredDaysText({ coveredDays: 30, records: 2 })).toBe('30 天 / 2 次登记');
    expect(registeredDaysText({ coveredDays: 30, records: 1 })).toBe('30 天 / 1 次登记');
  });

  it('范围档与状态标签有中文而不是裸枚举', () => {
    expect(scopeTypeLabel('DEFAULT')).toBe('全租户兜底');
    expect(scopeTypeLabel('CATEGORY')).toBe('按分类');
    expect(scopeTypeLabel('SKU')).toBe('按 SKU');
    expect(alertStatusLabel('OPEN')).toBe('待处理');
    expect(alertStatusLabel('ACKNOWLEDGED')).toBe('已认领');
    expect(alertStatusLabel('RESOLVED')).toBe('已处置');
    expect(suggestionStatusLabel('DISMISSED')).toBe('已忽略');
    expect(alertStatusLabel(null)).toBe('未知状态');
  });
});

function evaluation(overrides: Partial<SafetyStockEvaluationRow>): SafetyStockEvaluationRow {
  return {
    id: 'e1',
    evaluatedAt: '2026-10-14T00:00:00Z',
    triggeredByMembershipId: 'm1',
    policiesEvaluated: 3,
    policiesNotScannable: 0,
    warehousesEvaluated: 2,
    skusEvaluated: 5,
    skusWithoutSales: 0,
    alertsOpened: 2,
    alertsSuppressed: 3,
    suggestionsCreated: 1,
    scopeWarehouseId: null,
    note: '',
    ...overrides,
  } as SafetyStockEvaluationRow;
}

describe('三种 0 条预警必须被分成三种说法', () => {
  it('压根没配阈值：0 条预警不代表库存健康', () => {
    const text = evaluationSummary(
      evaluation({ policiesEvaluated: 0, skusEvaluated: 0, alertsOpened: 0, alertsSuppressed: 0 }),
    );
    expect(text).toContain('没有任何仍在生效的安全库存阈值');
    expect(text).toContain('不代表库存健康');
    expect(text).not.toContain('低于阈值 0 对');
  });

  it('配置了但没扫到：未扫描的档位必须能看见', () => {
    const text = evaluationSummary(
      evaluation({
        policiesEvaluated: 3,
        policiesNotScannable: 2,
        skusEvaluated: 1,
        alertsOpened: 1,
      }),
    );
    expect(text).toContain('未能评估');
    expect(text).toContain('2 档分类档/兜底档');
  });

  it('扫了确实没风险：如实报扫描量与未触发数', () => {
    const text = evaluationSummary(evaluation({}));
    expect(text).toContain('扫描在效阈值 3 档');
    expect(text).toContain('低于阈值 2 对');
    expect(text).toContain('未触发 3 对');
    expect(text).not.toContain('未能评估');
  });

  it('缺销量的 SKU 数出现在摘要里', () => {
    const text = evaluationSummary(evaluation({ skusWithoutSales: 4 }));
    expect(text).toContain('4 个 SKU 未登记销量');
  });

  it('覆盖摘要把未扫描与缺销量单独列出来', () => {
    expect(evaluationCoverageText(evaluation({}))).toBe('阈值 3 档 · 仓 2 个 · SKU 5 个');
    const text = evaluationCoverageText(
      evaluation({ policiesNotScannable: 2, skusWithoutSales: 1 }),
    );
    expect(text).toContain('未扫描 2 档');
    expect(text).toContain('缺销量 1 个 SKU');
  });
});

describe('能力声明：四条边界必须显式占版面', () => {
  const caps = {
    salesSourceDerived: false,
    skuCategoryExpansionAvailable: false,
    replenishmentTargetDelivered: false,
    workbenchAlertFeedDelivered: false,
    salesSourceNote: '销量是登记进来的事实',
    categoryExpansionNote: '分类展开在 catalog 服务',
    replenishmentNote: '6.2.1.2 未交付',
    workbenchNote: '工作台未交付',
    platformStockoutEscalation: '平台断货风险一律按上一级严重度处理',
  } as unknown as Parameters<typeof unavailableNotices>[0];

  it('四个不可用都变成要显示的提示而不是让人从 404 里猜', () => {
    const notices = unavailableNotices(caps);
    expect(notices.map((n) => n.key)).toEqual(['sales', 'category', 'replenishment', 'workbench']);
    expect(notices.every((n) => n.detail.length > 0)).toBe(true);
  });

  it('能力声明还没加载时不渲染任何提示而不是渲染假的', () => {
    expect(unavailableNotices(null)).toEqual([]);
  });

  it('平台断货提级规则可被页面直接取用', () => {
    expect(platformEscalationNote(caps)).toContain('上一级严重度');
    expect(platformEscalationNote(null)).toBe('');
  });

  it('可选项按后端标签显示，缺标签时按 value 兜底', () => {
    const options = [
      { value: 'OWN', label: '自有仓', available: true, description: '' },
      { value: 'FBA', label: '', available: false, description: '未交付' },
    ];
    expect(capabilityLabel(options, 'OWN')).toBe('自有仓');
    expect(capabilityLabel(options, 'FBA')).toBe('FBA');
    expect(capabilityLabel(options, 'ZZZ')).toBe('ZZZ');
    expect(capabilityLabel(undefined, 'OWN')).toBe('OWN');
    expect(unavailableCapabilities(options).map((o) => o.value)).toEqual(['FBA']);
  });
});
