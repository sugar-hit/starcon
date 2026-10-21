import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  buildClaimBasisPayload,
  buildCostPayload,
  buildDisposePayload,
  buildDisposalOrderPayload,
  buildEvidencePayload,
  buildPolicyPayload,
  claimTypeLabel,
  costTypeLabel,
  emptyClaimBasisForm,
  emptyCostForm,
  emptyDisposeForm,
  emptyEvidenceForm,
  emptyOrderForm,
  emptyPolicyForm,
  errorText,
  evidenceKindLabel,
  evidenceReady,
  methodLabel,
  methodRequirements,
  missingEvidence,
  moneyText,
  quantityText,
  statusLabel,
  subjectTypeLabel,
  type ReturnDisposalEvidenceRow,
  type ReturnDisposalRow,
} from './return-disposal-model';

/**
 * 10.7.1.3 弃置与销毁的前端模型断言。
 *
 * <p>🔴 本文件钉死三件最容易在浏览器里退化成假活的事：
 *
 * <ol>
 *   <li><b>「凭证」不是备注栏</b>：一次登记<b>一类</b>凭证，每类有专属必填字段；
 *       {@link missingEvidence} 把「还缺哪几样」摊开，{@link evidenceReady} 决定
 *       「执行销毁」能不能点 —— <b>凭证不齐就不许完成</b>。</li>
 *   <li><b>「销毁方式」不是自由文本</b>：只有压碎 / 焚烧 / 专业销毁三个枚举值，
 *       而且方式<b>决定</b>基线凭证要求（专业销毁要重量、焚烧要处置证明编号）。</li>
 *   <li><b>「凭证要求」只能加严</b>：四项全关是不合法的（那等于把要求关掉了）。</li>
 * </ol>
 *
 * <p>🔴 另外钉死币种纪律：页面**不做任何汇率换算**，
 * 销毁费用与索赔金额都走销毁单那一个费用币种。
 */

const MODEL_SOURCE = readFileSync(
  fileURLToPath(new URL('./return-disposal-model.ts', import.meta.url)),
  'utf8',
);

function order(overrides: Partial<ReturnDisposalRow> = {}): ReturnDisposalRow {
  return {
    id: 'o-1',
    disposalNo: 'DSP-1',
    status: 'SCHEDULED',
    statusLabel: '已排期（凭证要求已冻结）',
    disposalMethod: 'PROFESSIONAL',
    disposalMethodLabel: '专业销毁',
    sourceReference: 'RDO-1',
    dispositionOrderId: 'd-1',
    decisionId: 'dd-1',
    receiptNo: 'RR-1',
    skuCode: 'SKU-1',
    skuName: '样机',
    batchNo: 'B-1',
    brandCode: 'BRAND-A',
    platformCode: 'AMAZON',
    disposalQty: 5,
    weightKg: 0,
    weightUnit: 'KG',
    requirePhoto: true,
    requireWeight: true,
    requireCertificate: true,
    requireExecutor: true,
    costCurrency: 'USD',
    totalCost: 0,
    evidenceCount: 0,
    plannedAt: null,
    disposedAt: null,
    executorName: '',
    createdAt: '2026-05-01T08:00:00Z',
    note: '',
    version: 1,
    ...overrides,
  };
}

function evidence(kind: string): ReturnDisposalEvidenceRow {
  return {
    id: `e-${kind}`,
    disposalOrderId: 'o-1',
    evidenceKind: kind,
    evidenceKindLabel: evidenceKindLabel(kind),
    photoUrl: '',
    weightKg: null,
    certificateNo: '',
    executorName: '',
    occurredAt: '2026-05-02T09:30:00Z',
    recordedBy: 'u-1',
    note: '',
    createdAt: '2026-05-02T09:31:00Z',
  };
}

describe('10.7.1.3 销毁方式是枚举事实', () => {
  it('建单时必须显式选方式，不预选', () => {
    const form = emptyOrderForm();
    expect(form.disposalMethod).toBe('');
    expect(buildDisposalOrderPayload(form)).toEqual({
      error: '必须先从待销毁清单里选一条处置单',
    });
    expect(buildDisposalOrderPayload({ ...form, dispositionOrderId: 'd-1' })).toEqual({
      error: '必须选择销毁方式（压碎 / 焚烧 / 专业销毁）',
    });
  });

  it('方式只接受三个枚举值', () => {
    const form = { ...emptyOrderForm(), dispositionOrderId: 'd-1', disposalQty: '5' };
    expect(buildDisposalOrderPayload({ ...form, disposalMethod: 'BURN_IN_BACKYARD' })).toEqual({
      error: '销毁方式只能是压碎 / 焚烧 / 专业销毁',
    });
    for (const method of ['SHRED', 'INCINERATE', 'PROFESSIONAL']) {
      const payload = buildDisposalOrderPayload({ ...form, disposalMethod: method });
      expect('error' in payload).toBe(false);
      expect((payload as { disposalMethod: string }).disposalMethod).toBe(method);
    }
  });

  it('数量必须大于 0，币种必须是三个大写字母', () => {
    const base = { ...emptyOrderForm(), dispositionOrderId: 'd-1', disposalMethod: 'SHRED' };
    expect(buildDisposalOrderPayload({ ...base, disposalQty: '0' })).toEqual({
      error: '销毁数量必须大于 0',
    });
    expect(buildDisposalOrderPayload({ ...base, disposalQty: 'abc' })).toEqual({
      error: '销毁数量必须大于 0',
    });
    expect(
      buildDisposalOrderPayload({ ...base, disposalQty: '5', costCurrency: 'usd' }),
    ).toEqual({ error: '费用币种必须是三个大写字母（例如 USD）' });
  });

  it('方式决定基线凭证要求：压碎不要重量、焚烧要处置证明、专业销毁要重量', () => {
    expect(methodRequirements('SHRED')).toEqual({
      photo: true,
      weight: false,
      certificate: false,
      executor: true,
    });
    expect(methodRequirements('INCINERATE')).toEqual({
      photo: true,
      weight: false,
      certificate: true,
      executor: true,
    });
    expect(methodRequirements('PROFESSIONAL')).toEqual({
      photo: true,
      weight: true,
      certificate: true,
      executor: true,
    });
  });
});

describe('10.7.1.3 凭证是硬闸', () => {
  it('缺哪几样就报哪几样', () => {
    const o = order();
    expect(missingEvidence(o, [])).toEqual(['PHOTO', 'WEIGHT', 'CERTIFICATE', 'EXECUTOR']);
    expect(missingEvidence(o, [evidence('PHOTO')])).toEqual(['WEIGHT', 'CERTIFICATE', 'EXECUTOR']);
    expect(missingEvidence(o, [evidence('PHOTO'), evidence('WEIGHT')])).toEqual([
      'CERTIFICATE',
      'EXECUTOR',
    ]);
  });

  it('齐备才允许执行销毁', () => {
    const o = order();
    const all = [
      evidence('PHOTO'),
      evidence('WEIGHT'),
      evidence('CERTIFICATE'),
      evidence('EXECUTOR'),
    ];
    expect(evidenceReady(o, all)).toBe(true);
    expect(evidenceReady(o, all.slice(0, 3))).toBe(false);
  });

  it('只要求照片与执行方时（压碎），补齐这两样就够了', () => {
    const o = order({
      disposalMethod: 'SHRED',
      requireWeight: false,
      requireCertificate: false,
    });
    expect(missingEvidence(o, [evidence('PHOTO'), evidence('EXECUTOR')])).toEqual([]);
    expect(evidenceReady(o, [evidence('PHOTO'), evidence('EXECUTOR')])).toBe(true);
  });

  it('每类凭证有专属必填字段', () => {
    const form = emptyEvidenceForm();
    expect(buildEvidencePayload(form)).toEqual({ error: '必须选择凭证种类' });
    expect(buildEvidencePayload({ ...form, evidenceKind: 'PHOTO' })).toEqual({
      error: '照片凭证必须填照片地址',
    });
    expect(buildEvidencePayload({ ...form, evidenceKind: 'WEIGHT' })).toEqual({
      error: '重量凭证必须填一个不小于 0 的重量',
    });
    expect(buildEvidencePayload({ ...form, evidenceKind: 'CERTIFICATE' })).toEqual({
      error: '处置证明凭证必须填处置证明编号',
    });
    expect(buildEvidencePayload({ ...form, evidenceKind: 'EXECUTOR' })).toEqual({
      error: '执行方签核凭证必须填执行方',
    });
  });

  it('执行销毁必须写明执行方', () => {
    expect(buildDisposePayload(emptyDisposeForm())).toEqual({ error: '必须写明执行方' });
    const payload = buildDisposePayload({ ...emptyDisposeForm(), executorName: ' 销毁厂 A ' });
    expect('error' in payload).toBe(false);
    expect((payload as { executorName: string }).executorName).toBe('销毁厂 A');
  });
});

describe('10.7.1.3 凭证要求只能加严', () => {
  it('四项全关是不合法的', () => {
    const form = {
      ...emptyPolicyForm(),
      requiredPhoto: false,
      requiredWeight: false,
      requiredCertificate: false,
      requiredExecutor: false,
    };
    expect(buildPolicyPayload(form)).toEqual({
      error: '凭证要求至少要开一项：要求只能加严，不能全关',
    });
  });

  it('品牌方 / 平台要求必须写明编码', () => {
    const form = { ...emptyPolicyForm(), subjectType: 'BRAND', subjectRef: '' };
    expect(buildPolicyPayload(form)).toEqual({
      error: '品牌方 / 平台要求必须写明对应的编码',
    });
  });

  it('租户默认的编码是保留位 ALL', () => {
    const form = { ...emptyPolicyForm(), subjectType: 'GENERAL' };
    const payload = buildPolicyPayload(form);
    expect('error' in payload).toBe(false);
    expect((payload as { subjectRef: string }).subjectRef).toBe('ALL');
  });
});

describe('10.7.1.3 费用与索赔依据', () => {
  it('费用金额不能小于 0，币种必须是三个大写字母', () => {
    const form = emptyCostForm();
    expect(buildCostPayload(form)).toEqual({
      error: '费用金额必须是一个不小于 0 的数字',
    });
    expect(buildCostPayload({ ...form, amount: '-1' })).toEqual({
      error: '费用金额必须是一个不小于 0 的数字',
    });
    expect(buildCostPayload({ ...form, amount: '12.34', currency: 'eur' })).toEqual({
      error: '费用币种必须是三个大写字母（例如 USD）',
    });
  });

  it('索赔依据必须写明对方编码', () => {
    const form = emptyClaimBasisForm();
    expect(buildClaimBasisPayload(form)).toEqual({
      error: '必须写明索赔对方（品牌方 / 供应商编码）',
    });
    const payload = buildClaimBasisPayload({ ...form, counterpartyCode: 'BRAND-A' });
    expect('error' in payload).toBe(false);
    expect((payload as { counterpartyCode: string }).counterpartyCode).toBe('BRAND-A');
  });
});

describe('10.7.1.3 展示纪律', () => {
  it('销毁方式与凭证种类有中文名', () => {
    expect(methodLabel('SHRED')).toBe('压碎');
    expect(methodLabel('INCINERATE')).toBe('焚烧');
    expect(methodLabel('PROFESSIONAL')).toBe('专业销毁');
    expect(evidenceKindLabel('PHOTO')).toBe('销毁照片');
    expect(evidenceKindLabel('WEIGHT')).toBe('重量记录');
    expect(evidenceKindLabel('CERTIFICATE')).toBe('处置证明编号');
    expect(evidenceKindLabel('EXECUTOR')).toBe('执行方签核');
    expect(costTypeLabel('DESTROY_FEE')).toBe('销毁费');
    expect(claimTypeLabel('BRAND')).toBe('品牌方索赔');
    expect(subjectTypeLabel('BRAND')).toBe('品牌方要求');
    expect(statusLabel('DISPOSED')).toBe('已销毁（完成态）');
  });

  it('金额原样展示，不做汇率换算', () => {
    expect(moneyText(12.34, 'USD')).toBe('12.34 USD');
    expect(moneyText(null)).toBe('—');
    expect(quantityText(5)).toBe('5');
    expect(quantityText(12.5)).toBe('12.500000');
  });

  it('错误态把服务端的话原样带出来', () => {
    expect(errorText(new Error('x'))).toBe('加载失败，请稍后重试');
  });
});

describe('10.7.1.3 取数路径（从模型文件反向抽取）', () => {
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
        '/policies',
        '/policies/${id}/revoke',
        '/orders',
        '/orders/${id}',
        '/orders/${id}/schedule',
        '/orders/${id}/evidence',
        '/orders/${id}/costs',
        '/orders/${id}/claim-basis',
        '/orders/${id}/dispose',
        '/orders/${id}/cancel',
        '/claim-bases/${id}/submit',
        '/claim-bases/${id}/reject',
        '/events',
      ]),
    );
  });

  it('基址是本模块独立前缀（不借用兄弟模块的 return-dispositions / return-shipbacks）', () => {
    expect(MODEL_SOURCE).toContain("const BASE = 'channel/return-disposals'");
    expect(MODEL_SOURCE).not.toContain('channel/return-dispositions');
    expect(MODEL_SOURCE).not.toContain('channel/return-shipbacks');
  });
});
