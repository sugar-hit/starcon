import { describe, expect, it } from 'vitest';
import {
  adPromotionAnalysisExportCsvUrl,
  analysisCoverageText,
  analysisCoverageTone,
  analysisGrossProfitTone,
  analysisMarginAlertText,
  analysisMarginAlertTone,
  analysisMoney,
  analysisPercent,
  analysisPromotionTypeLabel,
  analysisQuantity,
  analysisRatio,
  analysisRunStatusLabel,
  analysisRunStatusTone,
  analysisSkippedEntries,
  analysisTimestampText,
  analysisVerdictLabel,
  analysisVerdictTone,
  analysisWindowText,
  emptyAdAnalysisCostInput,
  validateAdAnalysisCostInput,
  validateAdAnalysisRange,
} from './ad-promotion-analysis-model';

/**
 * 14.4.2.1 促销效果分析 前端模型。
 *
 * 🔴 这些断言盯的是**页面最容易说错的六件事**：
 *
 * 1. **不可比 ≠ 0**。null 渲染成「不可比」，0 才是「可比但真的是零」——
 *    毛利 0 与「拿不到成本」在运营决策上完全相反。
 * 2. **判定看毛利**。`incrementVerdict` 的四种取值各有各的显示名与色调，
 *    「数据不足」不能被渲染成「下滑」。
 * 3. **负毛利要显眼**。促销期毛利跌成负数是本模块最该被看见的读数。
 * 4. **覆盖度看得见**。「没有平台快照」与「有快照但销量是 0」是两件事，
 *    覆盖 0 天必须与 0 销量区分开。
 * 5. **跳过不许静默消失**。运行留痕里的 skipped 必须能被渲染成一行行说明。
 * 6. **窗口按当地自然日**。基期与促销期的起止要能一眼看出「比的是哪一段」。
 */

describe('14.4.2.1 不可比不是 0', () => {
  it('金额 null 显示「不可比」，不是 0.00', () => {
    expect(analysisMoney(null)).toBe('不可比');
    expect(analysisMoney(undefined)).toBe('不可比');
    expect(analysisMoney(null, 'USD')).toBe('不可比');
  });

  it('金额 0 显示 0.00（可比但真的是零）', () => {
    expect(analysisMoney(0)).toBe('0.00');
    expect(analysisMoney(0, 'USD')).toBe('0.00 USD');
    expect(analysisMoney(12.5, 'USD')).toBe('12.50 USD');
  });

  it('数量 null 同样是「不可比」', () => {
    expect(analysisQuantity(null)).toBe('不可比');
    expect(analysisQuantity(60)).toBe('60.00');
  });

  it('比率 null 是「不可比」，不是 0.00% / 0.0000', () => {
    expect(analysisPercent(null)).toBe('不可比');
    expect(analysisRatio(null)).toBe('不可比');
    expect(analysisPercent(0.2)).toBe('20.00%');
    expect(analysisRatio(-0.375)).toBe('-0.3750');
  });
});

describe('14.4.2.1 判定看毛利', () => {
  it('四种判定各有各的显示名', () => {
    expect(analysisVerdictLabel('INCREMENTAL')).toBe('增量（毛利增加）');
    expect(analysisVerdictLabel('FLAT')).toBe('持平（毛利不变）');
    expect(analysisVerdictLabel('DECREMENTAL')).toBe('下滑（毛利减少）');
    expect(analysisVerdictLabel('INSUFFICIENT_DATA')).toBe('数据不足（不可判定）');
  });

  it('显示名里必须写明判据是毛利', () => {
    for (const verdict of ['INCREMENTAL', 'FLAT', 'DECREMENTAL']) {
      expect(analysisVerdictLabel(verdict)).toContain('毛利');
    }
  });

  it('色调区分：下滑是 destructive，数据不足是琥珀色', () => {
    expect(analysisVerdictTone('INCREMENTAL')).toBe('text-emerald-600');
    expect(analysisVerdictTone('DECREMENTAL')).toBe('text-destructive');
    expect(analysisVerdictTone('INSUFFICIENT_DATA')).toBe('text-amber-600');
    expect(analysisVerdictTone('FLAT')).toBe('text-muted-foreground');
  });
});

describe('14.4.2.1 负毛利要显眼', () => {
  it('负毛利是 destructive，零毛利不是', () => {
    expect(analysisGrossProfitTone(-30)).toBe('text-destructive');
    expect(analysisGrossProfitTone(0)).toBe('text-foreground');
    expect(analysisGrossProfitTone(null)).toBe('text-muted-foreground');
  });
});

describe('14.4.2.1 放量不赚单独一列', () => {
  it('告警时写清「销售额涨了但毛利率跌了」', () => {
    expect(analysisMarginAlertText(true)).toContain('销售额涨了但毛利率跌了');
    expect(analysisMarginAlertText(true)).toBe('是（销售额涨了但毛利率跌了）');
    expect(analysisMarginAlertText(false)).toBe('否');
  });

  it('告警是 destructive，不是告警是弱化色', () => {
    expect(analysisMarginAlertTone(true)).toBe('text-destructive');
    expect(analysisMarginAlertTone(false)).toBe('text-muted-foreground');
  });
});

describe('14.4.2.1 覆盖度：没有数据 ≠ 销量是 0', () => {
  it('覆盖度渲染成「覆盖天数/总天数」', () => {
    expect(analysisCoverageText(3, 3)).toBe('3/3 天');
    expect(analysisCoverageText(1, 3)).toBe('1/3 天');
  });

  it('覆盖 0 天是琥珀色（不可比），满覆盖是弱化色', () => {
    expect(analysisCoverageTone(0, 3)).toBe('text-amber-600');
    expect(analysisCoverageTone(1, 3)).toBe('text-amber-600');
    expect(analysisCoverageTone(3, 3)).toBe('text-muted-foreground');
    expect(analysisCoverageTone(0, 0)).toBe('text-muted-foreground');
  });
});

describe('14.4.2.1 窗口与时间', () => {
  it('促销期窗口写明「不含」终点', () => {
    const text = analysisWindowText('2026-10-10T00:00:00Z', '2026-10-13T00:00:00Z');
    expect(text).toContain('2026-10-10');
    expect(text).toContain('2026-10-13');
    expect(text).toContain('不含');
  });

  it('窗口缺失显示「—」', () => {
    expect(analysisWindowText(null, null)).toBe('—');
    expect(analysisWindowText('2026-10-10T00:00:00Z', null)).toBe('—');
  });

  it('时间戳空值显示「—」', () => {
    expect(analysisTimestampText(null)).toBe('—');
    expect(analysisTimestampText('')).toBe('—');
    expect(analysisTimestampText('2026-10-01T09:00:00+08:00')).toContain('2026');
  });
});

describe('14.4.2.1 跳过不许静默消失', () => {
  it('运行状态各有各的显示名', () => {
    expect(analysisRunStatusLabel('SUCCESS')).toBe('全部算完');
    expect(analysisRunStatusLabel('PARTIAL')).toBe('部分跳过');
    expect(analysisRunStatusLabel('FAILED')).toBe('失败');
    expect(analysisRunStatusTone('PARTIAL')).toBe('text-amber-600');
  });

  it('跳过明细能渲染成一行行（促销编码 + 原因）', () => {
    const entries = analysisSkippedEntries(
      JSON.stringify([
        { promotionId: 'p1', promotionCode: 'P-NOWINDOW', reason: '促销未设置起止时间，无法界定促销期与基期' },
      ]),
    );
    expect(entries).toHaveLength(1);
    expect(entries[0]?.promotionCode).toBe('P-NOWINDOW');
    expect(entries[0]?.reason).toContain('起止时间');
  });

  it('跳过明细为空或坏 JSON 时渲染成空列表（不许崩页面）', () => {
    expect(analysisSkippedEntries('')).toEqual([]);
    expect(analysisSkippedEntries('not json')).toEqual([]);
    expect(analysisSkippedEntries('{}')).toEqual([]);
  });
});

describe('14.4.2.1 形状与下载链接', () => {
  it('导出 CSV 的下载链接指向导出留痕的 id', () => {
    expect(adPromotionAnalysisExportCsvUrl('abc-123')).toBe(
      '/api/channel/ad-promotion-analysis/exports/abc-123/csv',
    );
  });

  it('促销类型显示名与 14.4.1.1 同口径', () => {
    expect(analysisPromotionTypeLabel('PERCENT_OFF')).toBe('百分比折扣');
    expect(analysisPromotionTypeLabel('FIXED_AMOUNT_OFF')).toBe('固定额减免');
    expect(analysisPromotionTypeLabel('BOGO')).toBe('买 N 送 M');
  });
});

describe('14.4.2.1 表单校验', () => {
  it('单位成本必填（缺了毛利就不可比，登记这条快照没有意义）', () => {
    const input = { ...emptyAdAnalysisCostInput(), promotionId: 'p1', listingId: 'l1' };
    const result = validateAdAnalysisCostInput(input);
    expect(result.ok).toBe(false);
    expect(result.errors).toContain('单位成本必须是不小于 0 的数字');
  });

  it('基准价可以留空（留空 = 没登记 ⇒ 百分比促销的补贴不可比）', () => {
    const input = {
      ...emptyAdAnalysisCostInput(),
      promotionId: 'p1',
      listingId: 'l1',
      unitCost: '8',
    };
    const result = validateAdAnalysisCostInput(input);
    expect(result.ok).toBe(true);
    expect(result.errors).toEqual([]);
  });

  it('负成本要报错', () => {
    const input = {
      ...emptyAdAnalysisCostInput(),
      promotionId: 'p1',
      listingId: 'l1',
      unitCost: '-1',
    };
    expect(validateAdAnalysisCostInput(input).ok).toBe(false);
  });

  it('促销与商品 id 必填', () => {
    const result = validateAdAnalysisCostInput({
      ...emptyAdAnalysisCostInput(),
      unitCost: '8',
    });
    expect(result.errors).toContain('促销必填');
    expect(result.errors).toContain('商品 id 必填');
  });

  it('日期区间起止颠倒要报错', () => {
    const result = validateAdAnalysisRange('2026-10-10', '2026-10-01');
    expect(result.ok).toBe(false);
    expect(result.errors).toContain('起始日不能晚于截止日');
    expect(validateAdAnalysisRange('', '').ok).toBe(true);
  });
});
