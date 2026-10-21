import { describe, expect, it } from 'vitest';
import {
  daysRemainingText,
  documentKindLabel,
  emptyState,
  expiryStateLabel,
  expiryStateTone,
  fileSizeText,
  statusLabel,
  upstreamUnavailableText,
  type CoverageReport,
  type CertificationSummary,
} from './product-certification-model';

/**
 * 16.2.2.2 认证与资质 前端纯函数单测。
 *
 * <p>🔴 这个文件钉住的是**口径**，不是渲染：
 *
 * 1. **剩余天数原样显示后端算好的值**（按数据库时钟 + floor 规则算）——
 *    前端不重算，所以这里只验「负数 = 已过期 N 天」这类展示约定。
 * 2. **到期状态的文案与色调**与后端词表一一对应，五个状态各有各的说法。
 * 3. **空态三态不同**：没有档案 / 没有提醒 / 没有缺口，说的话不一样。
 * 4. **「读不到上游」不与「没有数据」共用文案** —— 共用会把「catalog 挂了」读成「这个租户没有 SKU」。
 */
describe('16.2.2.2 认证与资质 · 展示口径', () => {
  it('剩余天数：负数是「已过期 N 天」，0 是「今天到期」，正数是「还剩 N 天」', () => {
    expect(daysRemainingText(-5)).toBe('已过期 5 天');
    expect(daysRemainingText(-1)).toBe('已过期 1 天');
    expect(daysRemainingText(0)).toBe('今天到期');
    expect(daysRemainingText(3)).toBe('还剩 3 天');
  });

  it('到期状态：五个状态各有各的文案与色调', () => {
    expect(expiryStateLabel('VALID')).toBe('有效');
    expect(expiryStateLabel('EXPIRING')).toBe('即将过期');
    expect(expiryStateLabel('EXPIRED')).toBe('已过期');
    expect(expiryStateLabel('FUTURE')).toBe('尚未生效');
    expect(expiryStateLabel('REVOKED')).toBe('已撤销');

    expect(expiryStateTone('VALID')).toBe('success');
    expect(expiryStateTone('EXPIRING')).toBe('warning');
    expect(expiryStateTone('EXPIRED')).toBe('danger');
    expect(expiryStateTone('REVOKED')).toBe('danger');
    expect(expiryStateTone('FUTURE')).toBe('neutral');
    // 未知状态不许默认成「有效」——那会把没量到的东西说成合规。
    expect(expiryStateTone('WHAT')).toBe('neutral');
  });

  it('扫描件类别与认证状态的文案', () => {
    expect(documentKindLabel('ORIGINAL')).toBe('原件');
    expect(documentKindLabel('RENEWAL')).toBe('续期件');
    expect(documentKindLabel('ATTACHMENT')).toBe('补充材料');
    expect(statusLabel('ACTIVE')).toBe('有效');
    expect(statusLabel('REVOKED')).toBe('已撤销');
  });

  it('文件大小的展示串', () => {
    expect(fileSizeText(0)).toBe('0 B');
    expect(fileSizeText(512)).toBe('512 B');
    expect(fileSizeText(2048)).toBe('2.0 KB');
    expect(fileSizeText(5 * 1024 * 1024)).toBe('5.0 MB');
  });

  it('空态三态文案各不相同', () => {
    const noCertification = emptyState('NO_CERTIFICATION');
    const noReminder = emptyState('NO_REMINDER');
    const noGap = emptyState('NO_GAP');

    expect(noCertification.title).not.toBe(noReminder.title);
    expect(noReminder.title).not.toBe(noGap.title);
    expect(noCertification.title).not.toBe(noGap.title);
    expect(noCertification.description).toContain('认证档案');
    expect(noReminder.description).toContain('窗口');
    expect(noGap.description).toContain('覆盖');
  });

  it('上游读不到的提示明确说「不是没有数据」', () => {
    const text = upstreamUnavailableText('catalog 服务不可达');
    expect(text).toContain('catalog 服务不可达');
    expect(text).toContain('不是');
    // 刻意与「没有数据」的空态文案不同
    expect(text).not.toBe(emptyState('NO_GAP').description);
  });

  it('覆盖统计的形状：缺认证的 SKU 带出缺哪些类型', () => {
    const coverage: CoverageReport = {
      requiredRequirementCount: 3,
      coveredRequirementCount: 1,
      missingRequirementCount: 2,
      requiredSkuCount: 2,
      coveredSkuCount: 0,
      missingSkuCount: 2,
      byType: [],
      byCategory: [],
      missingSkus: [
        { skuId: 'a', skuCode: 'SKU-A', skuName: '商品 A', missingTypeCodes: ['FCC'] },
      ],
    };
    expect(coverage.missingSkus[0]?.missingTypeCodes).toEqual(['FCC']);
    expect(coverage.coveredRequirementCount + coverage.missingRequirementCount).toBe(
      coverage.requiredRequirementCount,
    );
  });

  it('认证台账行带到期状态与剩余天数（前端不重算，直接用后端给的）', () => {
    const row: CertificationSummary = {
      id: '1',
      code: 'CE-1',
      certificationTypeCode: 'CE',
      certificationTypeName: 'CE 认证',
      title: 'CE 证书',
      certNo: '',
      issuer: '',
      status: 'ACTIVE',
      expiryState: 'EXPIRING',
      validFrom: '2026-01-01T00:00:00Z',
      validUntil: '2026-12-31T00:00:00Z',
      daysRemaining: 5,
      skuCount: 0,
      categoryCount: 0,
      documentCount: 0,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    };
    expect(row.expiryState).toBe('EXPIRING');
    expect(daysRemainingText(row.daysRemaining)).toBe('还剩 5 天');
  });
});
