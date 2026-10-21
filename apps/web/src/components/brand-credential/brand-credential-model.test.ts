import { describe, expect, it } from 'vitest';
import {
  BASE,
  batchSummary,
  candidatesUnavailable,
  conclusionLabel,
  conclusionTone,
  credentialTypeLabel,
  daysToExpiryText,
  digestPreview,
  emptyState,
  expiryStateLabel,
  expiryStateTone,
  fileSizeText,
  reasonTone,
  severityLabel,
  severityTone,
  verificationStateLabel,
  verificationStateTone,
  verificationSummary,
  type BrandCredentialAuthorizationOptionPage,
  type BrandCredentialVerificationResult,
} from './brand-credential-model';

/**
 * 16.4.2.2 授权凭证 前端模型的单测。
 *
 * 🔴 这组用例守的是四件最容易做丢的事：
 *
 * 1. **「没核验过」不能显示成「核验不通过」**，也不能反过来 —— 两个状态必须能区分开。
 * 2. **不通过必须点名原因**；通过但有 WARN 提醒时**不能**说成不通过。
 * 3. **三态空态文案必须各不相同**（同一个空态换个图标不算三态）。
 * 4. **上游读不到 ≠ 没有候选**：上游挂掉时界面要说「读不到」，不能显示成「暂无候选」。
 */

const result = (
  over: Partial<BrandCredentialVerificationResult>,
): BrandCredentialVerificationResult => ({
  verificationId: 'v-1',
  credential: {
    credentialId: 'c-1',
    authorizationId: null,
    grantor: '甲品牌公司',
    credentialNo: 'BR-0001',
    credentialType: 'BRAND_LICENSE',
    credentialTypeLabel: '品牌授权书',
    subject: '本公司',
    brandId: null,
    brandName: '',
    validFrom: '2026-01-01T00:00:00Z',
    validUntil: '2026-12-31T00:00:00Z',
    issuedOn: null,
    status: 'ACTIVE',
    statusLabel: '有效',
    revokedAt: null,
    revokedReason: '',
    expiryState: 'ACTIVE',
    expiryStateLabel: '生效中',
    daysToExpiry: 100,
    currentVersion: 1,
    documentCount: 1,
    signatoryCount: 1,
    verificationState: 'VERIFIED',
    verificationStateLabel: '核验通过',
    lastVerifiedAt: '2026-10-21T00:00:00Z',
    lastConclusion: 'VERIFIED',
    note: '',
    version: 0,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
  },
  documentId: 'd-1',
  documentVersionNo: 1,
  evaluatedAt: '2026-10-21T00:00:00Z',
  ruleVersion: 1,
  conclusion: 'VERIFIED',
  conclusionLabel: '核验通过',
  severity: 'PASS',
  severityLabel: '通过',
  passed: true,
  reasons: [],
  snapshot: {},
  checkedBy: 'u-1',
  ...over,
});

describe('结论口径', () => {
  it('八种结论各有各的人话', () => {
    expect(conclusionLabel('VERIFIED')).toBe('核验通过');
    expect(conclusionLabel('DIGEST_MISMATCH')).toBe('文件摘要对不上');
    expect(conclusionLabel('FILE_MISSING')).toBe('文件不在受管存储');
    expect(conclusionLabel('EXPIRED')).toBe('凭证已过期');
    expect(conclusionLabel('NOT_YET_EFFECTIVE')).toBe('凭证尚未生效');
    expect(conclusionLabel('SIGNATORY_INCOMPLETE')).toBe('没有登记签署方');
    expect(conclusionLabel('NO_DOCUMENT')).toBe('凭证还没有文件');
    expect(conclusionLabel('REVOKED')).toBe('凭证已被撤销');
  });

  it('🔴 「尚未生效」是黄不是红（它不是「假的」）', () => {
    expect(conclusionTone('NOT_YET_EFFECTIVE')).toBe('warning');
    expect(conclusionTone('VERIFIED')).toBe('success');
    expect(conclusionTone('DIGEST_MISMATCH')).toBe('danger');
    expect(conclusionTone('FILE_MISSING')).toBe('danger');
    expect(conclusionTone('NOT_YET_EFFECTIVE')).not.toBe(conclusionTone('DIGEST_MISMATCH'));
  });

  it('分级的文案与色调', () => {
    expect(severityLabel('PASS')).toBe('通过');
    expect(severityLabel('FAIL')).toBe('不通过');
    expect(severityTone('PASS')).toBe('success');
    expect(severityTone('FAIL')).toBe('danger');
  });

  it('到期状态的文案与色调', () => {
    expect(expiryStateLabel('ACTIVE')).toBe('生效中');
    expect(expiryStateLabel('EXPIRING')).toBe('即将到期');
    expect(expiryStateLabel('EXPIRED')).toBe('已过期');
    expect(expiryStateLabel('NOT_YET_EFFECTIVE')).toBe('尚未生效');
    expect(expiryStateLabel('REVOKED')).toBe('已撤销');
    expect(expiryStateTone('ACTIVE')).toBe('success');
    expect(expiryStateTone('EXPIRING')).toBe('warning');
    expect(expiryStateTone('EXPIRED')).toBe('danger');
  });
});

describe('🔴「没核验过」≠「核验过但不通过」', () => {
  it('两个状态的人话与色调都不同', () => {
    expect(verificationStateLabel('UNVERIFIED')).toBe('从未核验');
    expect(verificationStateLabel('FAILED')).toBe('核验未通过');
    expect(verificationStateLabel('UNVERIFIED')).not.toBe(verificationStateLabel('FAILED'));
    expect(verificationStateTone('UNVERIFIED')).toBe('warning');
    expect(verificationStateTone('FAILED')).toBe('danger');
  });

  it('「从未核验」不能被当成「核验未通过」', () => {
    expect(verificationStateTone('UNVERIFIED')).not.toBe(verificationStateTone('FAILED'));
  });
});

describe('核验摘要', () => {
  it('通过 ⇒ 说清楚通过了什么', () => {
    expect(verificationSummary(result({ passed: true }))).toContain('核验通过');
  });

  it('🔴 通过但有 WARN 提醒 ⇒ 不说成不通过，但提醒要露出来', () => {
    const summary = verificationSummary(
      result({
        passed: true,
        reasons: [
          {
            code: 'SIGNATORY_UNVERIFIED',
            label: '签署方身份尚未核验',
            message: '已登记 1 个签署方，但还没有任何一个的身份被人工核验过。',
            severity: 'WARN',
          },
        ],
      }),
    );
    expect(summary).toContain('核验通过');
    expect(summary).toContain('1 条提醒');
    expect(summary).not.toContain('未通过');
  });

  it('🔴 不通过 ⇒ 必须点名原因（不能只说「未通过」）', () => {
    const summary = verificationSummary(
      result({
        passed: false,
        conclusion: 'DIGEST_MISMATCH',
        conclusionLabel: '文件摘要对不上',
        severity: 'FAIL',
        severityLabel: '不通过',
        reasons: [
          {
            code: 'DIGEST_MISMATCH',
            label: '文件摘要对不上',
            message: '重算摘要 abc 与登记摘要 def 不一致 —— 文件在登记之后被改过或换过内容。',
            severity: 'FAIL',
          },
        ],
      }),
    );
    expect(summary).toContain('核验未通过');
    expect(summary).toContain('文件摘要对不上');
    expect(summary).toContain('被改过');
  });

  it('没有结果时是空串（不编一句）', () => {
    expect(verificationSummary(null)).toBe('');
  });
});

describe('批量核验摘要', () => {
  it('🔴 没通过的那几份也要点名，不能只报通过数', () => {
    const passed = result({ passed: true });
    const failed = result({
      passed: false,
      conclusion: 'EXPIRED',
      conclusionLabel: '凭证已过期',
      severity: 'FAIL',
      reasons: [
        {
          code: 'EXPIRED',
          label: '凭证已过期',
          message: '凭证已过期。',
          severity: 'FAIL',
        },
      ],
    });
    failed.credential.credentialNo = 'BR-0002';
    const summary = batchSummary({ results: [passed, failed], verifiedCount: 1, failedCount: 1 });
    expect(summary).toContain('1 份核验通过');
    expect(summary).toContain('1 份未通过');
    expect(summary).toContain('BR-0002');
  });

  it('没有结果时是空串', () => {
    expect(batchSummary(null)).toBe('');
  });
});

describe('原因与文件展示', () => {
  it('FAIL 是红字，WARN 是提醒', () => {
    expect(reasonTone('FAIL')).toBe('danger');
    expect(reasonTone('WARN')).toBe('warning');
  });

  it('摘要预览：前后各 12 位，中间省略', () => {
    const digest = 'a'.repeat(64);
    const preview = digestPreview(digest);
    expect(preview).toContain('…');
    expect(preview.length).toBeLessThan(digest.length);
    expect(digestPreview('')).toBe('');
  });

  it('文件大小的人话', () => {
    expect(fileSizeText(512)).toBe('512 B');
    expect(fileSizeText(2048)).toBe('2.0 KB');
    expect(fileSizeText(5 * 1024 * 1024)).toBe('5.0 MB');
    expect(fileSizeText(-1)).toBe('—');
  });

  it('凭证类型未知时退回原值（不编一个）', () => {
    expect(credentialTypeLabel('BRAND_LICENSE')).toBe('品牌授权书');
    expect(credentialTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('剩余天数：已过期 / 今天到期 / 还剩几天', () => {
    expect(daysToExpiryText(-3)).toBe('已过期 3 天');
    expect(daysToExpiryText(0)).toBe('今天到期');
    expect(daysToExpiryText(12)).toBe('还剩 12 天');
  });
});

describe('三态空态', () => {
  it('三种空态的标题与说明各不相同', () => {
    const noCredential = emptyState('NO_CREDENTIAL');
    const noVerification = emptyState('NO_VERIFICATION');
    const noDocument = emptyState('NO_DOCUMENT');
    expect(noCredential.title).not.toBe(noVerification.title);
    expect(noVerification.title).not.toBe(noDocument.title);
    expect(noCredential.description).not.toBe(noVerification.description);
    expect(noVerification.description).not.toBe(noDocument.description);
  });

  it('空态说明里要点出各自的关键事实', () => {
    expect(emptyState('NO_CREDENTIAL').description).toContain('凭证');
    expect(emptyState('NO_VERIFICATION').description).toContain('sha256');
    expect(emptyState('NO_DOCUMENT').description).toContain('原件');
  });
});

describe('上游读不到 ≠ 没有候选', () => {
  const page = (note: string, count: number): BrandCredentialAuthorizationOptionPage => ({
    items:
      count === 0
        ? []
        : [
            {
              authorizationId: 'a-1',
              brandName: 'n',
              grantor: 'g',
              licenseeName: 'l',
              effectiveFrom: '2026-01-01T00:00:00Z',
              effectiveTo: '2026-12-31T00:00:00Z',
            },
          ],
    total: count,
    note,
  });

  it('上游读不到（note 说明原因）⇒ 判为「读不到」', () => {
    expect(candidatesUnavailable(page('上游服务不可达', 0))).toBe(true);
  });

  it('真的没有候选（note 是正常的来源说明）⇒ 不判为「读不到」', () => {
    expect(
      candidatesUnavailable(
        page('上游 16.4.2.1 品牌授权（**同库只读**）：本模块只读它的授权档案。', 0),
      ),
    ).toBe(false);
  });

  it('有候选 ⇒ 不判为「读不到」', () => {
    expect(candidatesUnavailable(page('上游服务不可达', 1))).toBe(false);
  });

  it('没有候选页 ⇒ 判为「读不到」', () => {
    expect(candidatesUnavailable(null)).toBe(true);
  });
});

describe('白名单前缀', () => {
  it('🔴 用独立前缀，不与 16.4.2.1 的 brand-authorizations 混', () => {
    expect(BASE).toBe('channel/brand-credentials');
    expect(BASE).not.toContain('brand-authorizations');
    expect(BASE).not.toContain('compliance-precheck');
  });
});
