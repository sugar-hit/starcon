import { describe, expect, it } from 'vitest';
import {
  actionAllowed,
  actionBlockedReason,
  discrepancyStatusTone,
  discrepancyText,
  eventText,
  fmtDate,
  fmtMoney,
  fmtQty,
  lineAmountText,
  qualityDeductionText,
  reconciliationQuery,
  statusTone,
  supplierText,
  unavailableModules,
} from './purchase-reconciliation-model';
import type {
  ReconciliationAction,
  ReconciliationBasis,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 6.5.1.1 采购对账前端模型的单测。
 *
 * <p>重点覆盖**四条容易做错的展示口径**：
 *
 * <ol>
 *   <li><b>质检扣量「未接线」不是 0</b>：扣量数量为 null 时显示「无数据」，
 *       状态显示「上游未接线（未扣量）」而不是「没有扣量」。</li>
 *   <li><b>采购侧不确认应付</b>：动作表里没有「确认应付 / 付款」可用项。</li>
 *   <li><b>差异逐条可读</b>：一行差异要能读出「差在哪个字段 / 我方多少 / 供应商多少 / 差多少」。</li>
 *   <li><b>跨币种不相加</b>：跨币种行显示原币 + 折算 + 汇率。</li>
 * </ol>
 */
describe('6.5.1.1 采购对账前端模型', () => {
  describe('查询参数', () => {
    it('空参数不产生问号', () => {
      expect(reconciliationQuery({})).toBe('');
    });

    it('筛选条件逐个透传并编码', () => {
      const q = reconciliationQuery({
        page: 2,
        size: 50,
        status: 'DISPUTED',
        supplierRefId: 'sup-1',
        periodType: 'MONTHLY',
        currency: 'USD',
        keyword: 'SUP-001',
        sort: 'created_desc',
      });
      expect(q).toContain('page=2');
      expect(q).toContain('size=50');
      expect(q).toContain('status=DISPUTED');
      expect(q).toContain('supplierRefId=sup-1');
      expect(q).toContain('periodType=MONTHLY');
      expect(q).toContain('currency=USD');
      expect(q).toContain('keyword=SUP-001');
      expect(q).toContain('sort=created_desc');
    });

    it('空白关键字不产生参数', () => {
      expect(reconciliationQuery({ keyword: '   ' })).toBe('');
    });
  });

  describe('数字与日期口径', () => {
    it('null 一律显示「无数据」，不显示 0', () => {
      expect(fmtMoney(null)).toBe('无数据');
      expect(fmtQty(null)).toBe('无数据');
      expect(fmtDate(null)).toBe('无数据');
    });

    it('金额与数量按原值显示', () => {
      expect(fmtMoney(12.5)).toContain('12.5');
      expect(fmtQty(40)).toContain('40');
    });

    it('日期只取日期部分（对账期间是业务日历口径）', () => {
      expect(fmtDate('2026-11-01T00:00:00Z')).toBe('2026-11-01');
    });
  });

  describe('质检扣量：未接线不是 0', () => {
    it('未接线时标签与配色都不同于「不适用」', () => {
      const unavailable = qualityDeductionText('UNAVAILABLE');
      const notApplicable = qualityDeductionText('NOT_APPLICABLE');
      expect(unavailable.label).toContain('未接线');
      expect(unavailable.tone).toBe('warning');
      expect(notApplicable.label).toBe('不适用');
      expect(notApplicable.tone).toBe('neutral');
    });

    it('已取到扣量与未知状态分得开', () => {
      expect(qualityDeductionText('APPLIED').tone).toBe('success');
      expect(qualityDeductionText(null).label).toBe('未知');
    });
  });

  describe('采购侧不确认应付', () => {
    const actions: ReconciliationAction[] = [
      {
        code: 'SEND',
        label: '发给供应商确认',
        allowed: true,
        method: 'POST',
        path: '/trade/purchase-reconciliations/{id}/send',
        required: false,
        precondition: '仅草稿',
        blockedReason: null,
      },
      {
        code: 'REOPEN',
        label: '重开对账',
        allowed: false,
        method: 'POST',
        path: '/trade/purchase-reconciliations/{id}/reopen',
        required: false,
        precondition: '仅未关闭',
        blockedReason: '已关闭的对账单不可重开',
      },
    ];

    it('动作表里没有「确认应付 / 付款」可用项', () => {
      expect(actionAllowed(actions, 'CONFIRM_PAYABLE')).toBe(false);
      expect(actionAllowed(actions, 'PAY')).toBe(false);
    });

    it('可用动作按后端给的 allowed 判', () => {
      expect(actionAllowed(actions, 'SEND')).toBe(true);
      expect(actionAllowed(actions, 'REOPEN')).toBe(false);
    });

    it('灰掉的动作带原因（界面要能说清为什么不能点）', () => {
      expect(actionBlockedReason(actions, 'REOPEN')).toBe('已关闭的对账单不可重开');
      expect(actionBlockedReason(actions, 'UNKNOWN')).toBe('当前不可用');
    });
  });

  describe('差异逐条可读', () => {
    const row = {
      id: 'd1',
      lineId: 'l1',
      lineNo: 2,
      discrepancyType: 'QUANTITY',
      discrepancyTypeLabel: '数量不符',
      fieldName: '数量',
      ourValue: 40,
      supplierValue: 38,
      difference: -2,
      currency: 'USD',
      status: 'OPEN',
      statusLabel: '待处理',
      resolution: '',
      resolutionNote: '',
      note: '供应商数量与我对不上',
      resolvedBy: null,
      resolvedAt: null,
      createdAt: '2026-11-20T10:00:00Z',
    };

    it('一行差异能读出字段 / 我方值 / 供应商值 / 差额', () => {
      const text = discrepancyText(row);
      expect(text).toContain('数量');
      expect(text).toContain('40');
      expect(text).toContain('38');
      expect(text).toContain('-2');
      expect(text).toContain('USD');
    });

    it('差异状态配色：待处理是危险色，挂起是警示色，已解决是成功色', () => {
      expect(discrepancyStatusTone('OPEN')).toBe('danger');
      expect(discrepancyStatusTone('HELD')).toBe('warning');
      expect(discrepancyStatusTone('RESOLVED')).toBe('success');
    });
  });

  describe('跨币种不相加', () => {
    const line = {
      id: 'l1',
      lineNo: 1,
      lineType: 'RECEIPT',
      lineTypeLabel: '已到货应收',
      sourceType: 'PURCHASE_ARRIVAL',
      sourceTypeLabel: '到货流水',
      sourceRefId: 't1',
      sourceRefCode: 'PO-1',
      sourceLineRefId: 'r1',
      sourceLineRefCode: 'GR-1',
      occurredAt: '2026-11-10T08:00:00Z',
      quantity: 40,
      unitCode: 'AS_RECORDED',
      unitConversion: 1,
      baseQuantity: 40,
      unitPrice: 2.5,
      currency: 'USD',
      fxRate: 7.25,
      fxRateSource: '财务提供的 11 月结汇汇率',
      fxRateAt: '2026-11-10T08:00:00Z',
      amount: 100,
      statementAmount: 725,
      deductionType: null,
      deductionTypeLabel: '',
      claimId: null,
      qualityDeductionStatus: 'UNAVAILABLE',
      qualityDeductionStatusLabel: '上游未接线',
      qualityDeductionQuantity: null,
      qualityDeductionNote: '',
      note: '',
    };

    it('跨币种行同时给原币与折算金额，并带汇率', () => {
      const text = lineAmountText(line);
      expect(text).toContain('100');
      expect(text).toContain('USD');
      expect(text).toContain('725');
      expect(text).toContain('7.25');
    });

    it('同币种行只给一个金额（不重复显示）', () => {
      expect(lineAmountText({ ...line, statementAmount: 100 })).not.toContain('→');
    });
  });

  describe('其它口径', () => {
    it('供应商显示编码 + 名称', () => {
      expect(supplierText({ supplierCode: 'SUP-001', supplierName: '东莞某某电子厂' })).toBe(
        'SUP-001 东莞某某电子厂',
      );
      expect(supplierText({ supplierCode: 'SUP-001', supplierName: null })).toBe('SUP-001');
      expect(supplierText({ supplierCode: '', supplierName: '' })).toBe('未命名供应商');
    });

    it('对账单状态配色与状态名对应', () => {
      expect(statusTone('DRAFT')).toBe('neutral');
      expect(statusTone('SENT')).toBe('warning');
      expect(statusTone('DISPUTED')).toBe('danger');
      expect(statusTone('CONFIRMED')).toBe('success');
      expect(statusTone('CLOSED')).toBe('primary');
      expect(statusTone(null)).toBe('neutral');
    });

    it('留痕行读得出「做了什么」', () => {
      const text = eventText({
        id: 'e1',
        eventType: 'SUPPLIER_CONFIRMATION',
        eventTypeLabel: '录入供应商回执',
        fromStatus: 'SENT',
        toStatus: 'DISPUTED',
        amount: null,
        reasonNote: '差异 2 条',
        occurredAt: '2026-11-20T10:00:00Z',
      });
      expect(text).toContain('录入供应商回执');
      expect(text).toContain('SENT');
      expect(text).toContain('DISPUTED');
      expect(text).toContain('差异 2 条');
    });

    it('未接线的事实被列出来（available=false 的才列）', () => {
      const basis = {
        sources: [],
        unavailableSources: [
          {
            key: 'QUALITY_DEDUCTION',
            label: '6.4.2.1 质检扣量',
            available: false,
            ownerModule: '6.4.2.1',
            reason: '未交付',
            impact: '尚未扣除质检不合格量',
          },
          {
            key: 'ALREADY_ON',
            label: '已接线',
            available: true,
            ownerModule: null,
            reason: '',
            impact: '',
          },
        ],
        receiptBasisNote: '',
        qualityBasisNote: '',
        financeHandoffNote: '',
        periodNote: '',
        fxNote: '',
        unitNote: '',
      } as unknown as ReconciliationBasis;
      const missing = unavailableModules(basis);
      expect(missing).toHaveLength(1);
      expect(missing[0]?.key).toBe('QUALITY_DEDUCTION');
      expect(missing[0]?.impact).toContain('尚未扣除');
    });

    it('没有基数说明时返回空数组而不是崩', () => {
      expect(unavailableModules(null)).toEqual([]);
    });
  });
});
