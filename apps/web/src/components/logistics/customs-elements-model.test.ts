import { describe, expect, it } from 'vitest';
import type {
  ElementSetCapabilities,
  ElementSetDetail,
  ElementSetPage,
  ElementSetRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  declaredNameForCompare,
  declaredValueText,
  detailToInput,
  destinationVersionLabel,
  errorText,
  intervalText,
  missingCountLabel,
  missingHeadline,
  missingTone,
  preferenceLabel,
  preferenceTone,
  quantityText,
  rowStateLabel,
  rowStateTone,
  sameAsSalesTitle,
  salesTitleForCompare,
  statusLabel,
  summaryLine,
  unavailableDimensions,
  unitPriceText,
  vocabularyLabel,
  vocabularyOptions,
  blockingLabels,
} from './customs-elements-model';

/**
 * 10.5.1.2 前端模型的纯函数断言。
 *
 * <p>重点钉三件容易在界面上做错、且做错之后**要到报关那一刻才暴露**的事：
 *
 * <ol>
 *   <li><b>申报品名与销售标题不得被读成同一个东西</b>（蓝图加粗句）。</li>
 *   <li><b>缺失项必须逐项点名并带原因</b>，不能只给一个计数。</li>
 *   <li><b>「未填」不得渲染成 0 或空</b>：申报数量 0、单价 0 与「没填」在海关侧是三件事。</li>
 * </ol>
 */

const SALES_TITLE = '高颜值超薄移动电源 10000mAh 快充';
const DECLARED = '移动电源 锂离子电池 10000mAh';

/**
 * 可空列的覆盖类型。
 *
 * <p>生成契约把 {@code quantity} / {@code unitPrice} 标成 {@code number}
 * （Java 侧是 {@code BigDecimal} 可空，但 spec 没标 nullable），**运行期真的会来 null**。
 * 模型的形参正是按 {@code number | null | undefined} 写的，所以这里显式放开这两个键，
 * 否则「未填」这一档在测试里根本构造不出来。
 */
type RowOverride = Omit<Partial<ElementSetRow>, 'quantity' | 'unitPrice'> & {
  quantity?: number | null;
  unitPrice?: number | null;
};

function row(over: RowOverride = {}): ElementSetRow {
  return {
    id: 'set-1',
    skuId: 'sku-1',
    destinationCountryCode: 'DE',
    versionNo: 1,
    declaredName: DECLARED,
    specMaterial: '铝合金+ABS外壳',
    brand: 'STARCON',
    quantity: 120,
    unitPrice: 8.5,
    currency: 'USD',
    originCountryCode: 'CN',
    originRuleCode: '',
    preferentialRateApplicable: false,
    status: 'DRAFT',
    effectiveFrom: '2026-01-01T00:00:00+08:00',
    effectiveTo: '2026-07-01T00:00:00+08:00',
    effectiveNow: false,
    missingCount: 0,
    blockingMissingCount: 0,
    createdAt: '2026-01-01T00:00:00+08:00',
    updatedAt: '2026-01-02T00:00:00+08:00',
    ...over,
  } as ElementSetRow;
}

function detail(over: Partial<ElementSetDetail> = {}): ElementSetDetail {
  return {
    row: row(),
    items: [],
    missing: [],
    complete: true,
    publishable: true,
    salesTitleSnapshot: SALES_TITLE,
    actions: ['EDIT', 'PUBLISH'],
    boundaryNote: '本模块只读 catalog，不写报关单',
    ...over,
  };
}

describe('10.5.1.2 申报品名 ≠ 销售标题', () => {
  it('销售标题一律带「不可直接作为申报品名」前缀', () => {
    expect(salesTitleForCompare(SALES_TITLE)).toContain('不可直接作为申报品名');
    expect(salesTitleForCompare(SALES_TITLE)).toContain(SALES_TITLE);
  });

  it('销售标题缺失时说清「上游未返回」，不留空', () => {
    expect(salesTitleForCompare('')).toBe('销售标题：上游未返回');
    expect(declaredNameForCompare('  ')).toContain('未填写');
  });

  it('申报品名带「清关口径」前缀，与销售标题不同框', () => {
    expect(declaredNameForCompare(DECLARED)).toContain('清关口径');
    expect(declaredNameForCompare(DECLARED)).not.toContain(SALES_TITLE);
  });

  it('逐字相同 / 只差大小写 / 只差首尾空白都算照搬', () => {
    expect(sameAsSalesTitle(SALES_TITLE, SALES_TITLE)).toBe(true);
    expect(sameAsSalesTitle(`  ${SALES_TITLE} `, SALES_TITLE)).toBe(true);
    expect(sameAsSalesTitle('高颜值超薄移动电源 10000mah 快充', SALES_TITLE)).toBe(true);
    expect(sameAsSalesTitle(DECLARED, SALES_TITLE)).toBe(false);
  });

  it('两边都空不算照搬（那是「还没填」，归缺失项管）', () => {
    expect(sameAsSalesTitle('', '')).toBe(false);
    expect(sameAsSalesTitle('', SALES_TITLE)).toBe(false);
  });
});

describe('10.5.1.2 缺失项提示', () => {
  const withMissing = detail({
    complete: false,
    publishable: false,
    actions: ['EDIT'],
    missing: [
      {
        field: 'brand_type',
        label: '品牌类型',
        severity: 'BLOCKER',
        reason: '多个法域的品牌申报要素问的是类型，只报品牌名答不出这一栏',
      },
      {
        field: 'preferential_rate_applicable',
        label: '优惠税率适用（口径提示）',
        severity: 'NOTICE',
        reason: '**主张优惠不等于会免**',
      },
    ],
  });

  it('逐项点名而不是只给计数', () => {
    expect(missingHeadline(withMissing)).toBe('品牌类型、优惠税率适用（口径提示）');
    expect(blockingLabels(withMissing)).toEqual(['品牌类型']);
  });

  it('齐备时明说齐备，不返回空串（空串会被读成「没数据」）', () => {
    expect(missingHeadline(detail())).toBe('申报要素齐备，可以发布');
  });

  it('每条都带原因，长度足够说清为什么必填', () => {
    for (const m of withMissing.missing) {
      expect(m.reason.length, `${m.label} 缺原因`).toBeGreaterThan(8);
    }
  });

  it('行级计数：齐备 / 只有口径提示 / 有阻断项，三种说法不同', () => {
    expect(missingCountLabel(row({ missingCount: 0 }))).toBe('齐备');
    expect(missingCountLabel(row({ missingCount: 1, blockingMissingCount: 0 }))).toBe('1 项口径提示');
    expect(missingCountLabel(row({ missingCount: 3, blockingMissingCount: 2 }))).toBe('缺 2 项');
    expect(missingTone(row({ missingCount: 0 }))).toBe('ok');
    expect(missingTone(row({ missingCount: 1, blockingMissingCount: 0 }))).toBe('warn');
    expect(missingTone(row({ missingCount: 3, blockingMissingCount: 2 }))).toBe('bad');
  });
});

describe('10.5.1.2 「未填」不得渲染成 0 或空', () => {
  it('数量缺失显示「未填」', () => {
    expect(quantityText(0)).toBe('0');
    expect(quantityText(null)).toBe('未填');
    expect(quantityText(undefined)).toBe('未填');
  });

  it('单价缺失显示「未填」，且币种缺失时明说币种没填', () => {
    expect(unitPriceText(8.5, 'USD')).toBe('8.5 USD');
    expect(unitPriceText(null, 'USD')).toBe('未填');
    expect(unitPriceText(8.5, '')).toContain('币种未填');
  });

  it('申报货值任一缺失时给「—」，不给 0', () => {
    expect(declaredValueText(row({ quantity: 120, unitPrice: 8.5, currency: 'USD' }))).toBe(
      '1020.0000 USD',
    );
    expect(declaredValueText(row({ quantity: null, unitPrice: 8.5 }))).toBe('—');
    expect(declaredValueText(row({ quantity: 120, unitPrice: null }))).toBe('—');
  });
});

describe('10.5.1.2 状态与生效', () => {
  it('「已发布」与「此刻生效」分开说，不合成一个标签', () => {
    expect(rowStateLabel(row({ status: 'PUBLISHED', effectiveNow: true }))).toBe('已发布 · 此刻生效');
    expect(rowStateLabel(row({ status: 'PUBLISHED', effectiveNow: false }))).toBe('已发布 · 此刻不生效');
    expect(rowStateLabel(row({ status: 'DRAFT' }))).toBe('草稿');
  });

  it('已发布但不生效用中性色，不用「一切正常」的绿', () => {
    expect(rowStateTone(row({ status: 'PUBLISHED', effectiveNow: true }))).toBe('ok');
    expect(rowStateTone(row({ status: 'PUBLISHED', effectiveNow: false }))).toBe('muted');
  });

  it('未知状态原样回显，不吞掉', () => {
    expect(statusLabel('WHATEVER')).toBe('WHATEVER');
    expect(statusLabel('')).toBe('未知状态');
  });

  it('生效区间左闭右开，to 为空说「一直有效」', () => {
    expect(intervalText('2026-01-01', '2026-07-01')).toContain('（不含终点）');
    expect(intervalText('2026-01-01', null)).toBe('2026-01-01 起一直有效');
  });

  it('目的地 + 版本号是清单与详情共用的读法', () => {
    expect(destinationVersionLabel(row({ destinationCountryCode: 'US', versionNo: 3 }))).toBe('US · v3');
  });
});

describe('10.5.1.2 原产地规则：主张 ≠ 会免', () => {
  it('不主张时明说不主张', () => {
    expect(preferenceLabel(row({ preferentialRateApplicable: false }))).toBe('不主张优惠');
    expect(preferenceTone(row({ preferentialRateApplicable: false }))).toBe('muted');
  });

  it('主张时带协定代码并声明「是否真的免税待合规确认」', () => {
    const r = row({ preferentialRateApplicable: true, originRuleCode: 'RCEP' });
    expect(preferenceLabel(r)).toContain('RCEP');
    expect(preferenceLabel(r)).toContain('待合规确认');
    expect(preferenceLabel(r)).not.toContain('已享惠');
    expect(preferenceTone(r)).toBe('warn');
  });

  it('勾了优惠却没填协定时，标签如实显示「未填协定」而不是留空', () => {
    const r = row({ preferentialRateApplicable: true, originRuleCode: '' });
    expect(preferenceLabel(r)).toContain('未填协定');
  });
});

describe('10.5.1.2 汇总与词表', () => {
  it('汇总行覆盖筛选后的全量，并带分页位置', () => {
    const page: ElementSetPage = {
      items: [row()],
      summary: {
        total: 7,
        draft: 3,
        published: 4,
        effectiveNow: 2,
        missingAny: 1,
        publishedMissing: 0,
      },
      page: 1,
      size: 20,
      totalElements: 7,
      totalPages: 1,
      sortNote: '按「最近更新」排序',
    };
    const line = summaryLine(page);
    expect(line).toContain('筛选后共 7 个版本');
    expect(line).toContain('此刻生效 2');
    expect(line).toContain('第 2 / 1 页');
  });

  it('无数据时页脚明说无数据，不显示 1 / 0 页', () => {
    const line = summaryLine({
      items: [],
      summary: { total: 0, draft: 0, published: 0, effectiveNow: 0, missingAny: 0, publishedMissing: 0 },
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
      sortNote: '',
    });
    expect(line).toContain('（无数据）');
  });

  it('词表取不到时回落到 code 本身，不返回空串', () => {
    const items = [{ code: 'USD', label: '美元', required: true }];
    expect(vocabularyLabel(items, 'USD')).toBe('美元');
    expect(vocabularyLabel(items, 'EUR')).toBe('EUR');
  });

  it('空串选项（不主张优惠）保留在选项里', () => {
    const opts = vocabularyOptions([
      { code: '', label: '不主张优惠', required: true },
      { code: 'RCEP', label: 'RCEP 协定', required: false },
    ]);
    expect(opts[0]).toEqual({ value: '', label: '不主张优惠' });
    expect(opts[1]?.label).toContain('RCEP');
  });

  it('available=false 的能力维度被单独挑出来（不属本模块的要说清）', () => {
    const caps = {
      dimensions: [
        { dimension: 'ELEMENT_SET', summary: '', available: true, source: '', ownerModule: '', note: '' },
        { dimension: 'TARIFF_RATE', summary: '', available: false, source: '', ownerModule: '16 合规', note: '主张不等于会免' },
      ],
      currencies: [],
      brandTypes: [],
      originRules: [],
      elementFields: [],
      statuses: [],
      severities: [],
      effectiveIntervalNote: '',
      declaredNameNote: '',
      catalogBoundaryNote: '',
    } as unknown as ElementSetCapabilities;
    const gaps = unavailableDimensions(caps);
    expect(gaps.map((d) => d.dimension)).toEqual(['TARIFF_RATE']);
  });
});

describe('10.5.1.2 错误与回填', () => {
  it('错误文案不吞掉后端给的后果说明', () => {
    expect(errorText(new Error('申报品名与销售标题完全相同'))).toContain('销售标题');
    expect(errorText('字符串错误')).toBe('未知错误');
  });

  it('详情回填写形状时保留补充要素项的位次与必填标记', () => {
    const input = detailToInput(
      detail({
        items: [
          { id: 'i1', ordinal: 1, elementCode: 'A1', elementName: '用途细分', elementValue: '手机', required: true },
        ],
      }),
    );
    expect(input.items).toEqual([
      { ordinal: 1, elementCode: 'A1', elementName: '用途细分', elementValue: '手机', required: true },
    ]);
    expect(input.destinationCountryCode).toBe('DE');
  });
});
