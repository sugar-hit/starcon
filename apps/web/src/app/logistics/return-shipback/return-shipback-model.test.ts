import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  allocationBalanced,
  allocationSum,
  buildConsignmentPayload,
  buildCostPayload,
  buildDeclarationPayload,
  buildEntryPayload,
  costTypeLabel,
  emptyConsignmentForm,
  emptyCostForm,
  emptyDeclarationForm,
  emptyEntryForm,
  emptySelectedCandidate,
  hasResidual,
  moneyText,
  previewTariffAmount,
  quantityText,
  ratioText,
  remainingEntryQty,
  residualSum,
  shipbackStatusLabel,
  transportModeLabel,
  validateConsignmentForm,
  validateCostForm,
  validateDeclarationForm,
  validateEntryForm,
  type ReturnShipbackAllocationRow,
  type ReturnShipbackEntryRow,
  type ReturnShipbackLineRow,
  type ShipbackCandidateRow,
} from './return-shipback-model';

/**
 * 10.7.1.2 退运回国的前端模型断言。
 *
 * <p>🔴 本文件钉死两件最容易在浏览器里退化成假活的事：
 *
 * <ol>
 *   <li><b>「合单 / 凑单」是真的</b>：合单表单一次提交<b>多条</b>处置单；
 *       空选被拒（空单摊不了成本也报不了关），数量超过处置量被拒。</li>
 *   <li><b>「成本分摊」是真的</b>：页面自己会做守恒自检 ——
 *       {@link allocationBalanced} 判「分摊合计 == 成本金额」，
 *       {@link hasResidual} / {@link residualSum} 把尾差显式摊开，
 *       尾差不许悄悄消失。</li>
 * </ol>
 *
 * <p>🔴 另外钉死币种纪律：页面**不做任何汇率换算**，成本 / 报关 / 残值三个币种口径
 * 永远不相加。
 */

const MODEL_SOURCE = readFileSync(
  fileURLToPath(new URL('./return-shipback-model.ts', import.meta.url)),
  'utf8',
);

function candidate(overrides: Partial<ShipbackCandidateRow> = {}): ShipbackCandidateRow {
  return {
    dispositionOrderId: 'order-1',
    dispositionNo: 'RDO-1',
    decisionId: 'decision-1',
    receiptNo: 'RR-1',
    skuCode: 'SKU-A',
    skuName: '退货样机',
    batchNo: 'B-1',
    brandCode: 'BRAND-A',
    platformCode: 'AMAZON',
    disposedQty: 10,
    salvageAmount: 5,
    salvageCurrency: 'CNY',
    dispositionMethod: 'RETURN_TO_COUNTRY',
    dispositionMethodLabel: '退运回国',
    issuedAt: '2026-04-01T00:00:00Z',
    note: '',
    ...overrides,
  };
}

function allocation(overrides: Partial<ReturnShipbackAllocationRow> = {}): ReturnShipbackAllocationRow {
  return {
    id: 'alloc-1',
    consignmentId: 'sbk-1',
    lineId: 'line-1',
    costId: 'cost-1',
    requestedBasis: 'WEIGHT',
    requestedBasisLabel: '按重量',
    appliedBasis: 'WEIGHT',
    appliedBasisLabel: '按重量',
    basisValue: 1,
    basisTotal: 3,
    allocationRatio: 0.3333,
    allocatedAmount: 33.33,
    currency: 'USD',
    residualAmount: 0,
    skuCode: 'SKU-A',
    skuName: '退货样机',
    ...overrides,
  };
}

describe('10.7.1.2 合单（凑单）是真的', () => {
  it('空选被拒：退运单至少要一行货', () => {
    const form = {
      ...emptyConsignmentForm(),
      originCountry: 'DE',
      originWarehouseRef: 'WH-DE-1',
      domesticWarehouseRef: 'WH-CN-1',
    };
    const error = validateConsignmentForm(form, []);
    expect(error).toContain('至少要选一条');
    const payload = buildConsignmentPayload(form, []);
    expect('error' in payload).toBe(true);
  });

  it('一次提交多行 ⇒ 请求体里的 lines 就是勾选的那几批货', () => {
    const form = {
      ...emptyConsignmentForm(),
      originCountry: 'DE',
      originWarehouseRef: 'WH-DE-1',
      domesticWarehouseRef: 'WH-CN-1',
      consolidationNote: '两票货合一个海运拼箱',
    };
    const selected = [
      emptySelectedCandidate(candidate({ dispositionOrderId: 'order-1', skuCode: 'SKU-A' })),
      emptySelectedCandidate(
        candidate({ dispositionOrderId: 'order-2', skuCode: 'SKU-B', disposedQty: 5 }),
      ),
    ];
    const first = selected[0];
    const second = selected[1];
    if (first) first.weightKg = '10';
    if (second) second.weightKg = '20';

    expect(validateConsignmentForm(form, selected)).toBe('');
    const payload = buildConsignmentPayload(form, selected);
    expect('error' in payload).toBe(false);
    if (!('error' in payload)) {
      expect(payload.lines).toHaveLength(2);
      expect(payload.lines.map((l) => l.dispositionOrderId)).toEqual(['order-1', 'order-2']);
      expect(payload.lines[0]?.weightKg).toBe(10);
      expect(payload.lines[1]?.qty).toBe(5);
      expect(payload.transportMode).toBe('SEA_CONSOLIDATION');
      expect(payload.consolidationNote).toBe('两票货合一个海运拼箱');
    }
  });

  it('退运数量超过处置单的处置量 ⇒ 拒', () => {
    const form = {
      ...emptyConsignmentForm(),
      originCountry: 'DE',
      originWarehouseRef: 'WH-DE-1',
      domesticWarehouseRef: 'WH-CN-1',
    };
    const selected = [emptySelectedCandidate(candidate())];
    const only = selected[0];
    if (only) only.qty = '11';
    expect(validateConsignmentForm(form, selected)).toContain('超过处置单的处置量');
  });

  it('国家码必须是两位、币种必须是三位大写字母', () => {
    const form = {
      ...emptyConsignmentForm(),
      originCountry: 'DEU',
      originWarehouseRef: 'WH-DE-1',
      domesticWarehouseRef: 'WH-CN-1',
    };
    expect(validateConsignmentForm(form, [emptySelectedCandidate(candidate())])).toContain('起运国家');

    const badCurrency = {
      ...form,
      originCountry: 'DE',
      costCurrency: 'US',
    };
    expect(validateConsignmentForm(badCurrency, [emptySelectedCandidate(candidate())])).toContain(
      '成本币种',
    );
  });

  it('重量 / 申报货值不许是负数', () => {
    const form = {
      ...emptyConsignmentForm(),
      originCountry: 'DE',
      originWarehouseRef: 'WH-DE-1',
      domesticWarehouseRef: 'WH-CN-1',
    };
    const selected = [emptySelectedCandidate(candidate())];
    const only = selected[0];
    if (only) only.weightKg = '-1';
    expect(validateConsignmentForm(form, selected)).toContain('重量');
    if (only) {
      only.weightKg = '1';
      only.declaredValue = '-5';
    }
    expect(validateConsignmentForm(form, selected)).toContain('申报货值');
  });
});

describe('10.7.1.2 成本分摊是真的算出来的', () => {
  it('分摊合计 == 成本金额 ⇒ 守恒', () => {
    const rows = [
      allocation({ allocatedAmount: 33.33 }),
      allocation({ id: 'alloc-2', allocatedAmount: 33.33 }),
      allocation({ id: 'alloc-3', allocatedAmount: 33.34, residualAmount: 0.01 }),
    ];
    expect(allocationSum(rows)).toBeCloseTo(100, 6);
    expect(allocationBalanced(rows, 100)).toBe(true);
    expect(allocationBalanced(rows, 120)).toBe(false);
  });

  it('尾差显式可见（除不尽时有一笔带尾差，合计仍等于总额）', () => {
    const rows = [
      allocation({ allocatedAmount: 33.33 }),
      allocation({ id: 'alloc-2', allocatedAmount: 33.33 }),
      allocation({ id: 'alloc-3', allocatedAmount: 33.34, residualAmount: 0.01 }),
    ];
    expect(hasResidual(rows)).toBe(true);
    expect(residualSum(rows)).toBeCloseTo(0.01, 6);
    expect(hasResidual([allocation({ allocatedAmount: 50, residualAmount: 0 })])).toBe(false);
  });

  it('成本表单：金额、币种、口径都要明确', () => {
    const form = emptyCostForm('USD');
    expect(validateCostForm(form)).toContain('成本金额');
    expect(validateCostForm({ ...form, amount: '-1' })).toContain('成本金额');
    expect(validateCostForm({ ...form, amount: '100', currency: 'US' })).toContain('币种');
    expect(validateCostForm({ ...form, amount: '100', allocationBasis: '' })).toContain('分摊口径');
    expect(validateCostForm({ ...form, amount: '100' })).toBe('');
    const payload = buildCostPayload({ ...form, amount: '100' });
    expect('error' in payload).toBe(false);
    if (!('error' in payload)) {
      expect(payload.amount).toBe(100);
      expect(payload.currency).toBe('USD');
      expect(payload.allocationBasis).toBe('WEIGHT');
    }
  });
});

describe('10.7.1.2 报关与关税', () => {
  it('税率必须是小数（0.15 = 15%），填 15 被拒', () => {
    const form = {
      ...emptyDeclarationForm(),
      hsCode: '61091000',
      declaredItemName: '退货 T 恤（申报品名）',
      declaredQty: '10',
      declaredValue: '1000.00',
      tariffRate: '15',
    };
    expect(validateDeclarationForm(form)).toContain('关税税率');
  });

  it('申报品名不能为空（申报品名不照搬销售标题，但它必须有）', () => {
    const form = {
      ...emptyDeclarationForm(),
      hsCode: '61091000',
      declaredQty: '10',
      declaredValue: '1000.00',
      tariffRate: '0.15',
    };
    expect(validateDeclarationForm(form)).toContain('申报品名');
  });

  it('关税预览与服务端算法一致：货值 × 税率，HALF_UP 到 4 位', () => {
    expect(previewTariffAmount(1000, 0.15)).toBe(150);
    expect(previewTariffAmount(33.33, 0.15)).toBe(4.9995);
    expect(previewTariffAmount(1000, 0)).toBe(0);
    expect(previewTariffAmount(Number.NaN, 0.15)).toBe(0);
  });

  it('报关单类型与申报币种都要明确', () => {
    const form = {
      ...emptyDeclarationForm(),
      hsCode: '61091000',
      declaredItemName: '申报品名',
      declaredQty: '10',
      declaredValue: '1000.00',
      tariffRate: '0.15',
    };
    expect(validateDeclarationForm({ ...form, declaredCurrency: 'CN' })).toContain('申报币种');
    expect(validateDeclarationForm({ ...form, declarationType: '' })).toContain('报关单类型');
    const payload = buildDeclarationPayload(form);
    expect('error' in payload).toBe(false);
    if (!('error' in payload)) {
      expect(payload.tariffRate).toBe(0.15);
      expect(payload.declaredCurrency).toBe('CNY');
    }
  });
});

describe('10.7.1.2 入库 / 报废分录', () => {
  it('没到货就没法登记分录（页面自己也拦一道）', () => {
    const form = emptyEntryForm('line-1');
    expect(validateEntryForm(form, 10)).toContain('数量');
    const withQty = { ...form, qty: '5' };
    expect(validateEntryForm(withQty, 10)).toBe('');
    expect(validateEntryForm({ ...withQty, qty: '11' }, 10)).toContain('超过');
  });

  it('一行尚未登记去向的数量 = 行数量 − 已登记分录', () => {
    const line = {
      id: 'line-1',
      qty: 10,
    } as unknown as ReturnShipbackLineRow;
    const entries = [
      { lineId: 'line-1', qty: 4 } as unknown as ReturnShipbackEntryRow,
    ];
    expect(remainingEntryQty(line, entries)).toBe(6);
  });
});

describe('10.7.1.2 展示纪律', () => {
  it('金额原样带币种，**不做任何汇率换算**', () => {
    expect(moneyText(1000, 'USD')).toBe('1000.00 USD');
    expect(moneyText(12.5, 'EUR')).toBe('12.50 EUR');
    expect(moneyText(null, 'USD')).toBe('—');
  });

  it('数量与比例按后端口径显示（数量 6 位、比例 4 位）', () => {
    expect(quantityText(5)).toBe('5');
    expect(quantityText(1.5)).toBe('1.500000');
    expect(ratioText(0.3333)).toBe('0.3333');
    expect(ratioText(null)).toBe('—');
  });

  it('状态与运输方式的展示名', () => {
    expect(shipbackStatusLabel('DRAFT')).toBe('草稿');
    expect(shipbackStatusLabel('DISPATCHED')).toBe('已发出');
    expect(transportModeLabel('SEA_CONSOLIDATION')).toBe('海运拼箱（LCL）');
    expect(costTypeLabel('OCEAN_FREIGHT')).toBe('海运费');
  });
});

describe('10.7.1.2 取数路径（从模型文件反向抽取）', () => {
  it('每个真实调用点都指向本模块自己的前缀', () => {
    // 只取**路径形状**：查询串不是路径的一部分（白名单按路径放行）
    const paths = [...MODEL_SOURCE.matchAll(/`\$\{BASE\}([^`]*)`/g)]
      .map((m) => m[1])
      .filter((p): p is string => typeof p === 'string')
      .map((p) => p.split('?')[0] ?? p);
    expect(paths.length, '一个调用点都没抽到 —— 断言本身失效了').toBeGreaterThan(0);
    for (const path of paths) {
      expect(path.startsWith('/')).toBe(true);
    }
    expect(paths).toEqual(
      expect.arrayContaining([
        '/capabilities',
        '/overview',
        '/candidates',
        '/consignments',
        '/consignments/${id}',
        '/consignments/${id}/lines',
        '/consignments/${id}/customs',
        '/consignments/${id}/costs',
        '/consignments/${id}/allocations',
        '/consignments/${id}/confirm',
        '/consignments/${id}/dispatch',
        '/consignments/${id}/arrival',
        '/consignments/${id}/entries',
        '/consignments/${id}/close',
        '/consignments/${id}/cancel',
        '/events',
      ]),
    );
  });

  it('基址是本模块独立前缀（不借用兄弟模块的 return-dispositions）', () => {
    expect(MODEL_SOURCE).toContain("const BASE = 'channel/return-shipbacks'");
    expect(MODEL_SOURCE).not.toContain('channel/return-dispositions');
  });
});
