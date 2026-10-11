import { beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  TradeDocumentRow,
  TradeReminderRow,
  TradeSetRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  acknowledgeTradeSet,
  cancelTradeSet,
  checkPassed,
  claimTradePreference,
  confirmTradePreference,
  createTradeCertification,
  createTradePreferenceRule,
  createTradeRule,
  createTradeSet,
  documentsReady,
  fetchTradeCapabilities,
  fetchTradeCertifications,
  fetchTradeChecks,
  fetchTradeDocuments,
  fetchTradeEvents,
  fetchTradePreferenceConfirmations,
  fetchTradePreferenceRules,
  fetchTradeReminders,
  fetchTradeRules,
  fetchTradeSetDetail,
  fetchTradeSets,
  fetchTradeSetReminders,
  fetchTradeTemplates,
  finalizeTradeDocument,
  fmt,
  generateTradeDocument,
  isOriginCertificate,
  money,
  moment,
  rejectTradeSetReview,
  resolveTradeReminder,
  reviewTradeSet,
  runTradeCheck,
  sendBackTradeSet,
  submitBlockers,
  submitTradeSet,
  submitTradeSetForReview,
  substantiveChangeBlocking,
  today,
  tradeQuery,
  tradeRuleQuery,
  tradeSetActions,
  withdrawTradeSetReview,
  type TradeSetParams,
} from './trade-document-model';

/** 抓出 api() 收到的 URL、方法与请求体，用来断言**真实调用点**，而不是手抄一份路由表。 */
const calls: Array<{ url: string; method: string; body: unknown }> = [];

vi.mock('../inventory/api', () => ({
  api: (url: string, method = 'GET', body?: unknown) => {
    calls.push({ url, method, body });
    return Promise.resolve(null);
  },
}));

const BASE = 'warehouse/trade-documents';

beforeEach(() => {
  calls.length = 0;
});

// ================================================================ 查询串

describe('查询串：每个筛选条件都真的进 URL', () => {
  it('空条件不带问号', () => {
    expect(tradeQuery({})).toBe('');
  });

  it('全部筛选条件逐条落到查询串', () => {
    const q = tradeQuery({
      status: ['DRAFT', 'UNDER_REVIEW'],
      destinationCountryCode: 'US',
      sourceDeclarationId: 'decl-1',
      latestCheckResult: 'FAIL',
      blockedOnly: true,
      keyword: 'TD-2026',
      sort: 'UPDATED_AT',
      page: 2,
      size: 20,
    } satisfies TradeSetParams);
    expect(q.startsWith('?')).toBe(true);
    expect(q).toContain('status=DRAFT');
    expect(q).toContain('status=UNDER_REVIEW');
    expect(q).toContain('destinationCountryCode=US');
    expect(q).toContain('sourceDeclarationId=decl-1');
    expect(q).toContain('latestCheckResult=FAIL');
    expect(q).toContain('blockedOnly=true');
    expect(q).toContain('keyword=TD-2026');
    expect(q).toContain('sort=UPDATED_AT');
    expect(q).toContain('page=2');
    expect(q).toContain('size=20');
  });

  it('状态可多选：每个值单独出一个 status 参数', () => {
    expect(tradeQuery({ status: ['DRAFT', 'APPROVED', 'SUBMITTED'] }).match(/status=/g)).toHaveLength(3);
  });

  it('blockedOnly=false 不进查询串（不筛 ≠ 筛一个假值）', () => {
    expect(tradeQuery({ blockedOnly: false })).toBe('');
  });

  it('纯空白的文本条件不进查询串，且两侧空白被裁掉', () => {
    expect(tradeQuery({ keyword: '   ', destinationCountryCode: '  ' })).toBe('');
    expect(tradeQuery({ keyword: '  TD-2026 ' })).toBe('?keyword=TD-2026');
  });

  it('关键字里的特殊字符被编码，不会拼坏查询串', () => {
    expect(tradeQuery({ keyword: 'A&B=C' })).toBe('?keyword=A%26B%3DC');
  });

  it('page=0 仍然进查询串（0 是合法页码，不能被当假值丢掉）', () => {
    expect(tradeQuery({ page: 0 })).toBe('?page=0');
  });

  it('规则查询串逐条落参，空条件同样不带问号', () => {
    expect(tradeRuleQuery({})).toBe('');
    const q = tradeRuleQuery({
      countryCode: 'US',
      documentKind: 'CERTIFICATE_OF_ORIGIN',
      requirementLevel: 'MANDATORY',
      status: 'ACTIVE',
      keyword: ' ',
      sort: 'UPDATED_AT',
      page: 0,
      size: 20,
    });
    expect(q).toContain('countryCode=US');
    expect(q).toContain('documentKind=CERTIFICATE_OF_ORIGIN');
    expect(q).toContain('requirementLevel=MANDATORY');
    expect(q).toContain('status=ACTIVE');
    expect(q).toContain('sort=UPDATED_AT');
    expect(q).toContain('page=0');
    expect(q).toContain('size=20');
    expect(q).not.toContain('keyword=');
  });
});

// ================================================================ 真实调用点

describe('真实调用点：每个端点的 URL 与方法都被断言', () => {
  it('读：能力 / 列表 / 详情 / 单证 / 校验 / 提醒 / 事件', async () => {
    await fetchTradeCapabilities();
    await fetchTradeSets({ page: 0, size: 20 });
    await fetchTradeSetDetail('s1');
    await fetchTradeDocuments('s1');
    await fetchTradeChecks('s1');
    await fetchTradeSetReminders('s1');
    await fetchTradeEvents('s1');
    expect(calls.map((c) => [c.method, c.url])).toEqual([
      ['GET', `${BASE}/capabilities`],
      ['GET', `${BASE}?page=0&size=20`],
      ['GET', `${BASE}/s1`],
      ['GET', `${BASE}/s1/documents`],
      ['GET', `${BASE}/s1/checks`],
      ['GET', `${BASE}/s1/reminders`],
      ['GET', `${BASE}/s1/events`],
    ]);
  });

  it('读：规则 / 模板 / 认证 / 优惠规则 / 优惠台账 / 缺失提醒工作台', async () => {
    await fetchTradeRules({ page: 0 });
    await fetchTradeTemplates({ page: 0 });
    await fetchTradeCertifications({ page: 0 });
    await fetchTradePreferenceRules({ page: 0 });
    await fetchTradePreferenceConfirmations({ page: 0 });
    await fetchTradeReminders({ page: 0 });
    expect(calls.map((c) => [c.method, c.url])).toEqual([
      ['GET', `${BASE}/rules?page=0`],
      ['GET', `${BASE}/templates?page=0`],
      ['GET', `${BASE}/certifications?page=0`],
      ['GET', `${BASE}/preference-rules?page=0`],
      ['GET', `${BASE}/preference-confirmations?page=0`],
      ['GET', `${BASE}/reminders?page=0`],
    ]);
  });

  it('写：建包 / 复核链 / 校验 / 提交下游 / 结果 / 取消', async () => {
    await createTradeSet({ sourceDeclarationId: 'd1', legalEntityId: null, notes: '' });
    await submitTradeSetForReview('s1', { reviewNotes: '' });
    await reviewTradeSet('s1', { reviewNotes: 'ok' });
    await rejectTradeSetReview('s1', { reason: '缺提单' });
    await withdrawTradeSetReview('s1', { notes: '先补件' });
    await runTradeCheck('s1');
    await submitTradeSet('s1', { submittedTo: '报关行', submissionReference: 'BK-1' });
    await acknowledgeTradeSet('s1', { acknowledgementReference: 'ACK-1' });
    await sendBackTradeSet('s1', { reason: '原产地证要素不全' });
    await cancelTradeSet('s1', { reason: '客户改港' });
    expect(calls.map((c) => [c.method, c.url])).toEqual([
      ['POST', BASE],
      ['POST', `${BASE}/s1/submit-for-review`],
      ['POST', `${BASE}/s1/review`],
      ['POST', `${BASE}/s1/reject-review`],
      ['POST', `${BASE}/s1/withdraw-review`],
      ['POST', `${BASE}/s1/check`],
      ['POST', `${BASE}/s1/submit`],
      ['POST', `${BASE}/s1/acknowledge`],
      ['POST', `${BASE}/s1/send-back`],
      ['POST', `${BASE}/s1/cancel`],
    ]);
  });

  it('写：生成单证 / 定稿 / 优惠主张 / 业务确认', async () => {
    await generateTradeDocument('s1', {
      documentKind: 'CERTIFICATE_OF_ORIGIN',
      templateId: null,
      documentNo: '',
      currency: 'USD',
      quantityTotal: null,
      packageCount: null,
      content: {},
      notes: '',
    });
    await finalizeTradeDocument('s1', 'doc1', { documentNo: 'CO-1', sourceReference: '系统生成' });
    await claimTradePreference('s1', 'doc1', {
      ftaScheme: 'RCEP',
      originCountryCode: 'CN',
      preferenceRuleId: 'r1',
      substantiveChangeNote: '',
      confirmed: null,
    });
    await confirmTradePreference('s1', 'doc1', {
      ftaScheme: 'RCEP',
      originCountryCode: 'CN',
      preferenceRuleId: 'r1',
      substantiveChangeNote: '工序未发生实质性改变，附工艺说明',
      confirmed: true,
    });
    expect(calls.map((c) => [c.method, c.url])).toEqual([
      ['POST', `${BASE}/s1/documents`],
      ['POST', `${BASE}/s1/documents/doc1/finalize`],
      ['POST', `${BASE}/s1/documents/doc1/preference-claim`],
      ['POST', `${BASE}/s1/documents/doc1/preference-confirm`],
    ]);
  });

  it('可空字段真的发 null 而不是空串（后端是 UUID/Integer，空串会反序列化炸）', async () => {
    await createTradeSet({ sourceDeclarationId: 'd1', legalEntityId: null, notes: '' });
    expect(calls[0]?.body).toEqual({
      sourceDeclarationId: 'd1',
      legalEntityId: null,
      notes: '',
    });
    await generateTradeDocument('s1', {
      documentKind: 'PACKING_LIST',
      templateId: null,
      documentNo: '',
      currency: 'USD',
      quantityTotal: null,
      packageCount: null,
      content: {},
      notes: '',
    });
    expect(calls[1]?.body).toMatchObject({ templateId: null, quantityTotal: null, packageCount: null });
  });

  it('写：维护单证要求 / 认证状态 / 优惠规则 / 处理提醒', async () => {
    await createTradeRule({
      countryCode: 'US',
      documentKind: 'COMMERCIAL_INVOICE',
      requirementLevel: 'MANDATORY',
      conditionExpression: '',
      requiresCertification: false,
      ruleSource: 'TENANT_MAINTAINED',
      sourceReference: '界面维护',
      validFrom: '2026-01-01',
      validTo: null,
      notes: '',
    });
    await createTradeCertification({
      skuId: 'sku-1',
      certificationCode: 'CE',
      certificationName: '欧盟安全认证',
      status: 'VALID',
      certificateNo: 'CE-001',
      issuingBody: null,
      issuedOn: null,
      expiresOn: '2027-01-01',
      documentRef: '',
      certificationSource: 'TENANT_MAINTAINED',
      sourceReference: '界面维护',
      notes: '',
    });
    await createTradePreferenceRule({
      scheme: 'RCEP',
      destinationCountryCode: 'JP',
      productScope: 'ALL',
      hsPrefix: '',
      preferenceCriteria: '完全获得',
      substantiveChangeCriterion: '经完全获得货物需满足实质性改变要求',
      requiresBusinessConfirmation: true,
      validFrom: '2026-01-01',
      validTo: null,
      status: 'ACTIVE',
      ruleSource: 'TENANT_MAINTAINED',
      sourceReference: '界面维护',
      notes: '',
    });
    await resolveTradeReminder('rm1', {
      status: 'RESOLVED',
      resolutionNote: '已补交提单扫描件',
    });
    expect(calls.map((c) => [c.method, c.url])).toEqual([
      ['POST', `${BASE}/rules`],
      ['POST', `${BASE}/certifications`],
      ['POST', `${BASE}/preference-rules`],
      ['POST', `${BASE}/reminders/rm1/resolve`],
    ]);
  });

  it('id 会做 URL 编码（id 来自 URL 参数，不能直接拼进路径）', async () => {
    await fetchTradeSetDetail('a/b c');
    await resolveTradeReminder('r/1', { status: 'DISMISSED', resolutionNote: '不适用' });
    expect(calls[0]?.url).toBe(`${BASE}/a%2Fb%20c`);
    expect(calls[1]?.url).toBe(`${BASE}/reminders/r%2F1/resolve`);
  });
});

// ================================================================ 口径判据

describe('「实质性改变」：系统不下结论，只判有没有留痕', () => {
  it('只有「待业务确认」是阻断态', () => {
    expect(substantiveChangeBlocking('PENDING_BUSINESS_CONFIRMATION')).toBe(true);
  });

  it('已确认 / 已撤回 / 不适用 / 没登记，都不是阻断态', () => {
    for (const status of ['CONFIRMED', 'REJECTED', 'NOT_APPLICABLE', '', null, undefined]) {
      expect(substantiveChangeBlocking(status)).toBe(false);
    }
  });

  it('CONFIRMED 也不代表「优惠已通过」：判据只看状态，不看别的字段', () => {
    expect(substantiveChangeBlocking('CONFIRMED')).toBe(false);
    expect(checkPassed('PASS')).toBe(true);
  });
});

describe('「待业务确认」不等于「校验通过」', () => {
  it('只有 PASS 算通过', () => {
    expect(checkPassed('PASS')).toBe(true);
  });

  it('待业务确认、不通过、未校验、空值都不算通过', () => {
    for (const result of ['PENDING_BUSINESS_CONFIRMATION', 'FAIL', 'NOT_CHECKED', '', null, undefined]) {
      expect(checkPassed(result)).toBe(false);
    }
  });
});

describe('提交前阻断清单：与后端提交闸同一组判据', () => {
  const base = {
    reminders: [] as TradeReminderRow[],
    set: { latestCheckResult: 'PASS' } as TradeSetRow,
  };

  it('校验通过且无阻断提醒时没有阻断项', () => {
    expect(submitBlockers(base)).toEqual([]);
  });

  it('没跑过校验也算阻断：没校验就提交等于没人确认齐不齐', () => {
    const blockers = submitBlockers({ ...base, set: { latestCheckResult: 'NOT_CHECKED' } as TradeSetRow });
    expect(blockers).toHaveLength(1);
    expect(blockers[0]).toContain('还没跑完整性校验');
  });

  it('校验 FAIL 会把真实结论写出来，不笼统说「请先完善」', () => {
    const blockers = submitBlockers({ ...base, set: { latestCheckResult: 'FAIL' } as TradeSetRow });
    expect(blockers[0]).toContain('校验不通过');
  });

  it('待业务确认的校验结论不是通过，也不能提交', () => {
    const blockers = submitBlockers({
      ...base,
      set: { latestCheckResult: 'PENDING_BUSINESS_CONFIRMATION' } as TradeSetRow,
    });
    expect(blockers).toHaveLength(1);
  });

  it('未处理的阻断级提醒逐条列出', () => {
    const blockers = submitBlockers({
      ...base,
      reminders: [
        {
          id: 'r1',
          severity: 'BLOCKER',
          status: 'OPEN',
          reminderType: 'MISSING_DOCUMENT',
          documentKind: 'BILL_OF_LADING',
          documentKindLabel: '提单/空运单',
          message: '目的国 US 要求提单/空运单，尚未出单',
        } as TradeReminderRow,
      ],
    });
    expect(blockers).toEqual(['目的国 US 要求提单/空运单，尚未出单']);
  });

  it('优惠待确认的提醒说清「系统只校验与留档」，不说优惠成立', () => {
    const blockers = submitBlockers({
      ...base,
      reminders: [
        {
          id: 'r2',
          severity: 'BLOCKER',
          status: 'OPEN',
          reminderType: 'UNCONFIRMED_SUBSTANTIVE_CHANGE',
          documentKind: 'RCEP_CERTIFICATE_OF_ORIGIN',
          documentKindLabel: 'RCEP 原产地证',
          message: '主张了 RCEP 优惠但还没有业务确认',
        } as TradeReminderRow,
      ],
    });
    expect(blockers[0]).toContain('实质性改变是否成立需要业务确认');
    expect(blockers[0]).toContain('系统只负责按规则校验与留档');
  });

  it('已处理 / 提示级提醒都不阻断', () => {
    const blockers = submitBlockers({
      ...base,
      reminders: [
        {
          id: 'r3',
          severity: 'BLOCKER',
          status: 'RESOLVED',
          reminderType: 'MISSING_DOCUMENT',
          message: '已补',
        } as TradeReminderRow,
        {
          id: 'r4',
          severity: 'WARNING',
          status: 'OPEN',
          reminderType: 'MISSING_DOCUMENT',
          message: '有条件必备，请判断是否适用',
        } as TradeReminderRow,
      ],
    });
    expect(blockers).toEqual([]);
  });
});

describe('单证包动作入口按状态编码，不许诺做不到的事', () => {
  it('提交下游只在「已复核通过」出现', () => {
    expect(tradeSetActions('DRAFT')).not.toContain('submit');
    expect(tradeSetActions('UNDER_REVIEW')).not.toContain('submit');
    expect(tradeSetActions('APPROVED')).toContain('submit');
  });

  it('已提交之后才出现「登记已接收 / 被退回」', () => {
    expect(tradeSetActions('DRAFT')).not.toContain('acknowledge');
    expect(tradeSetActions('SUBMITTED')).toEqual(expect.arrayContaining(['acknowledge', 'sendBack']));
  });

  it('终态没有任何动作', () => {
    expect(tradeSetActions('ACKNOWLEDGED')).toEqual([]);
    expect(tradeSetActions('CANCELLED')).toEqual([]);
  });

  it('未知状态不给动作，而不是给一份可能错误的清单', () => {
    expect(tradeSetActions('NOT_A_STATUS')).toEqual([]);
  });
});

describe('只有原产地证可以主张优惠', () => {
  it('RCEP/CO 原产地证算，别的单证不算', () => {
    expect(isOriginCertificate('RCEP_CERTIFICATE_OF_ORIGIN')).toBe(true);
    expect(isOriginCertificate('CERTIFICATE_OF_ORIGIN')).toBe(true);
  });

  it('发票、装箱单、提单、认证文件都不算', () => {
    for (const kind of ['COMMERCIAL_INVOICE', 'PACKING_LIST', 'BILL_OF_LADING', 'IMPORT_LICENSE']) {
      expect(isOriginCertificate(kind)).toBe(false);
    }
  });

  it('空值不算（没出单证时不能因为 null 就判定可主张）', () => {
    expect(isOriginCertificate(null)).toBe(false);
    expect(isOriginCertificate(undefined)).toBe(false);
    expect(isOriginCertificate('')).toBe(false);
  });
});

describe('单证齐备度：取代版后按最新版算', () => {
  const doc = (kind: string, version: number, status: string) =>
    ({
      documentKind: kind,
      documentVersion: version,
      status,
      id: `${kind}-${version}`,
    }) as TradeDocumentRow;

  it('都定稿了才算齐备', () => {
    const r = documentsReady([
      doc('COMMERCIAL_INVOICE', 1, 'FINAL'),
      doc('PACKING_LIST', 1, 'FINAL'),
    ]);
    expect(r.ready).toBe(true);
    expect(r.drafts).toEqual([]);
  });

  it('还有草稿就不算齐备', () => {
    const r = documentsReady([
      doc('COMMERCIAL_INVOICE', 1, 'FINAL'),
      doc('PACKING_LIST', 1, 'DRAFT'),
    ]);
    expect(r.ready).toBe(false);
    expect(r.drafts).toEqual(['PACKING_LIST']);
  });

  it('被取代的历史版本不算未定稿', () => {
    const r = documentsReady([
      doc('COMMERCIAL_INVOICE', 1, 'SUPERSEDED'),
      doc('COMMERCIAL_INVOICE', 2, 'DRAFT'),
    ]);
    expect(r.drafts).toEqual(['COMMERCIAL_INVOICE']);
  });

  it('最新版定稿就算齐备（改版取代旧版后旧版的草稿态不算）', () => {
    const r = documentsReady([
      doc('COMMERCIAL_INVOICE', 1, 'DRAFT'),
      doc('COMMERCIAL_INVOICE', 2, 'FINAL'),
    ]);
    expect(r.ready).toBe(true);
  });

  it('一张单证都没有时 missing 为空、ready 为真 —— 齐不齐由校验按目的国规则判', () => {
    expect(documentsReady([])).toEqual({ ready: true, missing: [], drafts: [] });
  });
});

describe('金额与时间的展示：0 和空值必须区分', () => {
  it('0 是真实金额，要显示出来', () => {
    expect(fmt(0)).toBe('0.00');
    expect(money(0, 'USD')).toBe('0.00 USD');
  });

  it('空值显示成破折号，不能显示成 0', () => {
    expect(fmt(null)).toBe('—');
    expect(fmt(undefined)).toBe('—');
    expect(money(null, 'USD')).toBe('—');
    expect(money(12.3, null)).toBe('12.30');
  });

  it('NaN / Infinity 也当空值处理，不显示 NaN', () => {
    expect(fmt(Number.NaN)).toBe('—');
    expect(fmt(Number.POSITIVE_INFINITY)).toBe('—');
  });

  it('数量可指定小数位', () => {
    expect(fmt(1234.5678, 6)).toBe('1,234.567800');
  });

  it('时间戳为空或解析不了都显示成破折号', () => {
    expect(moment(null)).toBe('—');
    expect(moment('')).toBe('—');
    expect(moment('not-a-date')).toBe('—');
  });

  it('today 给 YYYY-MM-DD，后端要的是 LocalDate', () => {
    expect(today()).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});