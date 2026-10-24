import { describe, expect, it } from 'vitest';
import type {
  IntegrationCredentialVerifyResult,
  IntegrationQuotaStatusRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  maskSecret,
  quotaLabel,
  quotaTone,
  verifyConclusion,
} from './app-credential-model';

/**
 * 18.1.1.1 应用与凭证 前端模型的单测。
 *
 * 🔴 这组用例守的是三件最容易做丢的事：
 *
 * 1. **「没配过配额」不能显示成「已超限」** —— 没配 = 不限；显示成红色「已用尽」会让运营
 *    去查一个根本不存在的限制。把「不限」显示成「限 0」是误伤。
 * 2. **凭证自检的四个判定必须分开说** —— 「凭证有效但授权范围不足」和「凭证不匹配」
 *    要修的地方完全不同，合成一句话就没法用。
 * 3. **明文掩码不能把整串都露出来** —— 截图与工单里不该出现完整凭证。
 */

const quota = (over: Partial<IntegrationQuotaStatusRow>): IntegrationQuotaStatusRow => ({
  windowType: 'MINUTE',
  windowTypeLabel: '分钟',
  limitCount: 100,
  configured: true,
  enabled: true,
  usedCount: 0,
  remainingCount: 100,
  exceeded: false,
  windowStartedAt: '2026-10-25T00:00:00Z',
  windowEndsAt: '2026-10-25T00:01:00Z',
  decision: 'ALLOW',
  note: '',
  ...over,
});

const verify = (
  over: Partial<IntegrationCredentialVerifyResult>,
): IntegrationCredentialVerifyResult => ({
  credentialMatched: true,
  credentialStatus: 'ACTIVE',
  appStatus: 'ACTIVE',
  scopeGranted: true,
  scopeChecked: false,
  ipAllowed: true,
  ipChecked: false,
  quotaExceeded: false,
  quotaStatus: [],
  conclusion: '',
  note: '',
  ...over,
});

describe('18.1.1.1 应用与凭证 · 配额判定的展示口径', () => {
  it('未配置的窗口是灰的「不限」，不是红色的「已超限」', () => {
    const status = quota({ configured: false, exceeded: false, decision: 'NOT_CONFIGURED' });
    expect(quotaTone(status)).toBe('muted');
    expect(quotaLabel(status)).toBe('不限（未配置）');
    expect(quotaLabel(status)).not.toContain('超限');
  });

  it('停用的窗口也是灰的', () => {
    const status = quota({ enabled: false, decision: 'DISABLED' });
    expect(quotaTone(status)).toBe('muted');
    expect(quotaLabel(status)).toBe('已停用');
  });

  it('超限才是红色，且额度充足是绿色', () => {
    expect(quotaTone(quota({ exceeded: true, decision: 'EXCEEDED' }))).toBe('danger');
    expect(quotaLabel(quota({ exceeded: true, decision: 'EXCEEDED' }))).toBe('已超限');
    expect(quotaTone(quota({}))).toBe('ok');
    expect(quotaLabel(quota({}))).toBe('额度充足');
  });
});

describe('18.1.1.1 应用与凭证 · 凭证自检结论', () => {
  it('凭证不匹配时直接说凭证不匹配', () => {
    expect(verifyConclusion(verify({ credentialMatched: false }))).toBe('凭证不匹配或已失效');
  });

  it('凭证有效但配额超限时，结论里点名是配额', () => {
    expect(verifyConclusion(verify({ quotaExceeded: true }))).toBe('凭证有效，但配额已超限');
  });

  it('凭证有效但授权范围不足时，结论里点名是授权范围', () => {
    expect(
      verifyConclusion(verify({ scopeChecked: true, scopeGranted: false })),
    ).toBe('凭证有效，但授权范围不足');
  });

  it('凭证有效但来源 IP 不在白名单时，结论里点名是 IP', () => {
    expect(verifyConclusion(verify({ ipChecked: true, ipAllowed: false }))).toBe(
      '凭证有效，但来源 IP 不在白名单内',
    );
  });

  it('全部通过才说「凭证有效」', () => {
    expect(verifyConclusion(verify({}))).toBe('凭证有效');
  });

  it('未判定的项不会被说成「不允许」', () => {
    // scopeChecked / ipChecked 都是 false ⇒ 没判定，结论不能说成授权不足或 IP 不允许。
    const conclusion = verifyConclusion(verify({ scopeGranted: false, ipAllowed: false }));
    expect(conclusion).toBe('凭证有效');
  });
});

describe('18.1.1.1 应用与凭证 · 明文掩码', () => {
  it('短串整段打码', () => {
    expect(maskSecret('sk_1234')).toBe('••••');
  });

  it('长串只留前 6 位，其余打码', () => {
    const masked = maskSecret('sk_abcdef1234567890');
    expect(masked.startsWith('sk_abc')).toBe(true);
    expect(masked).not.toContain('1234567890');
  });

  it('掩码后不残留完整凭证', () => {
    const secret = 'sk_abcdef1234567890abcdef';
    expect(maskSecret(secret)).not.toBe(secret);
  });
});
