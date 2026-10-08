import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  amendCustomsDeclaration,
  cancelCustomsDeclaration,
  createCustomsDeclaration,
  customsDeclarationQuery,
  declarationActions,
  documentsReady,
  elementComplete,
  finalizeCustomsDocument,
  fetchCustomsCapabilities,
  fetchCustomsDeclarationDetail,
  fetchCustomsDeclarations,
  fetchCustomsDocuments,
  fetchCustomsEvents,
  fetchCustomsResults,
  generateCustomsDocument,
  money,
  rateText,
  recordCustomsResult,
  rejectCustomsReview,
  reviewCustomsDeclaration,
  submitCustomsDeclaration,
  submitCustomsForReview,
  withdrawCustomsReview,
  type CustomsDeclarationParams,
} from './customs-declaration-model';

/** 抓出 api() 收到的 URL 与方法，用来断言**真实调用点**（不是手抄一份路由表）。 */
const calls: Array<{ url: string; method: string; body: unknown }> = [];

vi.mock('../inventory/api', () => ({
  api: (url: string, method = 'GET', body?: unknown) => {
    calls.push({ url, method, body });
    return Promise.resolve(null);
  },
}));

const BASE = 'warehouse/customs-declarations';

beforeEach(() => {
  calls.length = 0;
});

describe('查询串：每个筛选条件都真的进 URL', () => {
  it('空条件不带问号', () => {
    expect(customsDeclarationQuery({})).toBe('');
  });

  it('全部筛选条件逐条落到查询串', () => {
    const q = customsDeclarationQuery({
      status: ['DRAFT', 'PENDING_REVIEW'],
      tradeMode: 'GENERAL_TRADE',
      transportMode: 'SEA',
      destinationCountryCode: 'US',
      sourceShipmentId: 'ship-1',
      pendingReviewOnly: true,
      keyword: 'COSCO-1234',
      sort: 'UPDATED_AT',
      page: 2,
      size: 20,
    } satisfies CustomsDeclarationParams);
    expect(q.startsWith('?')).toBe(true);
    expect(q).toContain('status=DRAFT');
    expect(q).toContain('status=PENDING_REVIEW');
    expect(q).toContain('tradeMode=GENERAL_TRADE');
    expect(q).toContain('transportMode=SEA');
    expect(q).toContain('destinationCountryCode=US');
    expect(q).toContain('sourceShipmentId=ship-1');
    expect(q).toContain('pendingReviewOnly=true');
    expect(q).toContain('keyword=COSCO-1234');
    expect(q).toContain('sort=UPDATED_AT');
    expect(q).toContain('page=2');
    expect(q).toContain('size=20');
  });

  it('状态可多选：每个值都单独出一个 status 参数', () => {
    const q = customsDeclarationQuery({ status: ['DRAFT', 'REJECTED', 'CUSTOMS_REJECTED'] });
    expect(q.match(/status=/g)).toHaveLength(3);
  });

  it('pendingReviewOnly 为 false 时不进查询串（不筛 ≠ 筛一个假值）', () => {
    expect(customsDeclarationQuery({ pendingReviewOnly: false })).toBe('');
  });

  it('纯空白的关键字与国别不进查询串', () => {
    expect(customsDeclarationQuery({ keyword: '   ', destinationCountryCode: '  ' })).toBe('');
  });

  it('关键字两侧空白被裁掉再发', () => {
    expect(customsDeclarationQuery({ keyword: '  COSCO-1234 ' })).toBe('?keyword=COSCO-1234');
  });

  it('关键字里的特殊字符被编码，不会拼坏查询串', () => {
    expect(customsDeclarationQuery({ keyword: 'A&B=C' })).toBe('?keyword=A%26B%3DC');
  });

  it('page 为 0 时仍然进查询串（0 是合法页码，不能被当假值丢掉）', () => {
    expect(customsDeclarationQuery({ page: 0 })).toBe('?page=0');
  });
});

describe('真实调用点：每个动作的 URL 与方法都被断言', () => {
  it('读：列表 / 能力 / 详情 / 三单据 / 结果 / 事件', async () => {
    await fetchCustomsDeclarations({ page: 0, size: 20 });
    await fetchCustomsCapabilities();
    await fetchCustomsDeclarationDetail('d1');
    await fetchCustomsDocuments('d1');
    await fetchCustomsResults('d1');
    await fetchCustomsEvents('d1');
    expect(calls.map((c) => [c.method, c.url])).toEqual([
      ['GET', `${BASE}?page=0&size=20`],
      ['GET', `${BASE}/capabilities`],
      ['GET', `${BASE}/d1`],
      ['GET', `${BASE}/d1/documents`],
      ['GET', `${BASE}/d1/results`],
      ['GET', `${BASE}/d1/events`],
    ]);
  });

  it('写：建单 / 复核链 / 提交报关行 / 结果 / 改单 / 取消', async () => {
    await createCustomsDeclaration({
      sourceShipmentId: 's1',
      legalEntityId: 'le1',
      tradeMode: 'GENERAL_TRADE',
      exporterName: '甲',
      exporterCountryCode: 'CN',
      consigneeName: '乙',
      consigneeCountryCode: 'US',
      iorName: '',
      iorCountryCode: '',
      brokerName: '报关行',
      brokerCode: '',
      brokerContact: '',
      destinationCountryCode: 'US',
      destinationCustomsDistrict: '',
      departurePort: '',
      destinationPort: '',
      transportMode: 'SEA',
      billOfLadingNo: '',
      declarationCurrency: 'USD',
      baseCurrency: 'CNY',
      exchangeRate: 7.1,
      exchangeRateScale: 6,
      exchangeRateDate: '2026-10-01',
      notes: '',
      lines: [],
    });
    await submitCustomsForReview('d1', { notes: '' });
    await reviewCustomsDeclaration('d1', { reviewNotes: '已核' });
    await rejectCustomsReview('d1', { reason: '要素不全' });
    await withdrawCustomsReview('d1', { reason: '要改' });
    await generateCustomsDocument('d1', { documentType: 'PACKING_LIST', documentNo: '' });
    await finalizeCustomsDocument('d1', 'doc1', { documentNo: '' });
    await submitCustomsDeclaration('d1', {
      submissionReference: '',
      brokerName: '报关行',
      submissionResult: 'ACCEPTED',
      resultReason: '',
    });
    await recordCustomsResult('d1', {
      resultType: 'CLEARED',
      resultReference: '',
      reason: '已放行',
      resultAt: '2026-10-08T06:00:00Z',
      sourceSystem: 'MANUAL_ENTRY',
      sourceReference: 'WEB',
    });
    await amendCustomsDeclaration('d1', { reason: '改单', resultReference: '' });
    await cancelCustomsDeclaration('d1', { reason: '作废' });

    expect(calls.map((c) => [c.method, c.url])).toEqual([
      ['POST', BASE],
      ['POST', `${BASE}/d1/submit-for-review`],
      ['POST', `${BASE}/d1/review`],
      ['POST', `${BASE}/d1/reject-review`],
      ['POST', `${BASE}/d1/withdraw-review`],
      ['POST', `${BASE}/d1/documents`],
      ['POST', `${BASE}/d1/documents/doc1/finalize`],
      ['POST', `${BASE}/d1/submit`],
      ['POST', `${BASE}/d1/results`],
      ['POST', `${BASE}/d1/amend`],
      ['POST', `${BASE}/d1/cancel`],
    ]);
  });

  it('id 被编码：路径穿越型的 id 不能拼出别的端点', async () => {
    await fetchCustomsDeclarationDetail('a/../b');
    expect(calls[0]?.url).toBe(`${BASE}/a%2F..%2Fb`);
  });
});

describe('动作入口：只给当前状态下合法的按钮', () => {
  it('提交报关行只在「已复核」出现 —— 复核闸是真闸，不许界面许诺一个必被拒的动作', () => {
    expect(declarationActions('DRAFT')).not.toContain('submit');
    expect(declarationActions('PENDING_REVIEW')).not.toContain('submit');
    expect(declarationActions('REJECTED')).not.toContain('submit');
    expect(declarationActions('REVIEWED')).toContain('submit');
    // 已提交之后不可能再「提交」
    expect(declarationActions('SUBMITTED')).not.toContain('submit');
  });

  it('登记海关结果只在已提交 / 已改单出现', () => {
    expect(declarationActions('SUBMITTED')).toContain('recordResult');
    expect(declarationActions('AMENDED')).toContain('recordResult');
    expect(declarationActions('DRAFT')).not.toContain('recordResult');
    expect(declarationActions('REVIEWED')).not.toContain('recordResult');
  });

  it('终态没有可点动作：放行后只能由海关发起改单，我方不能自己动', () => {
    expect(declarationActions('CLEARED')).toEqual([]);
    expect(declarationActions('CANCELLED')).toEqual([]);
  });

  it('已放行与已取消都不给取消按钮', () => {
    expect(declarationActions('CLEARED')).not.toContain('cancel');
    expect(declarationActions('CANCELLED')).not.toContain('cancel');
    // 草稿可以作废（最常见的路径）
    expect(declarationActions('DRAFT')).toContain('cancel');
  });

  it('未知状态不给任何动作（不猜）', () => {
    expect(declarationActions('SOMETHING_NEW')).toEqual([]);
  });

  it('草稿与复核退回都能送复核（改完必须重新走复核）', () => {
    expect(declarationActions('DRAFT')).toContain('submitForReview');
    expect(declarationActions('REJECTED')).toContain('submitForReview');
  });
});

describe('三单据齐备性：缺一份或没定稿都不许提交报关行', () => {
  const doc = (type: string, status: string, version: number) => ({
    id: `${type}-${version}`,
    documentType: type,
    documentTypeLabel: type,
    documentNo: '',
    documentVersion: version,
    currency: 'USD',
    totalAmount: 100,
    content: {},
    status,
    supersedesDocumentId: '',
    issuedAt: '',
    preparedBy: '',
    sourceSystem: 'MANUAL_ENTRY',
    sourceReference: '',
    createdAt: '',
  });

  it('三份都定稿 = 可提交', () => {
    const r = documentsReady([
      doc('CUSTOMS_DECLARATION', 'FINAL', 1),
      doc('PACKING_LIST', 'FINAL', 1),
      doc('COMMERCIAL_INVOICE', 'FINAL', 1),
    ]);
    expect(r.ready).toBe(true);
    expect(r.missing).toEqual([]);
    expect(r.drafts).toEqual([]);
  });

  it('缺一份时报出缺哪一份', () => {
    const r = documentsReady([doc('CUSTOMS_DECLARATION', 'FINAL', 1)]);
    expect(r.ready).toBe(false);
    expect(r.missing.sort()).toEqual(['COMMERCIAL_INVOICE', 'PACKING_LIST']);
  });

  it('生成了但没定稿 = 不可提交', () => {
    const r = documentsReady([
      doc('CUSTOMS_DECLARATION', 'FINAL', 1),
      doc('PACKING_LIST', 'DRAFT', 1),
      doc('COMMERCIAL_INVOICE', 'FINAL', 1),
    ]);
    expect(r.ready).toBe(false);
    expect(r.drafts).toEqual(['PACKING_LIST']);
  });

  it('被取代的历史版本不算数：只认最新一版', () => {
    const r = documentsReady([
      doc('CUSTOMS_DECLARATION', 'SUPERSEDED', 1),
      doc('CUSTOMS_DECLARATION', 'FINAL', 2),
      doc('PACKING_LIST', 'FINAL', 1),
      doc('COMMERCIAL_INVOICE', 'FINAL', 1),
    ]);
    expect(r.ready).toBe(true);
  });

  it('一张单据都没有时报全缺', () => {
    const r = documentsReady([]);
    expect(r.ready).toBe(false);
    expect(r.missing).toHaveLength(3);
  });
});

describe('申报要素齐备性：只有「已齐备」能过复核闸', () => {
  it('人工录入·已齐备 与 3.7.1.1 字典·已齐备 都算齐备', () => {
    expect(elementComplete('MANUAL_COMPLETE')).toBe(true);
    expect(elementComplete('MODULE_3_7_1_1_COMPLETE')).toBe(true);
  });

  it('两种「未齐备」都算不齐备（它们会被复核时的 DB 闸拒）', () => {
    expect(elementComplete('MANUAL_PENDING')).toBe(false);
    expect(elementComplete('MODULE_3_7_1_1_PENDING')).toBe(false);
  });

  it('未知取值按不齐备处理（宁可拦下，不放一个必被拒的单）', () => {
    expect(elementComplete('SOMETHING_NEW')).toBe(false);
  });
});

describe('展示口径：空值不显示成 0', () => {
  it('金额空值显示为破折号而不是 0', () => {
    expect(money(null)).toBe('—');
    expect(money(undefined)).toBe('—');
  });

  it('汇率快照必须连刻度与日期一起给（事后复核要回答「当时按哪个汇率报的」）', () => {
    expect(rateText(7.123456, 6, '2026-10-01')).toBe('7.123456 / 6 @ 2026-10-01');
    expect(rateText(null, 6, '2026-10-01')).toBe('—');
  });
});
