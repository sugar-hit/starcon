import { describe, expect, it } from 'vitest';
import {
  ALERT_STATUSES,
  ALERT_TYPES,
  BASE,
  IP_MODES,
  MFA_FACTORS,
  MFA_STATUSES,
  SEVERITIES,
  accessCountText,
  alertStatusLabel,
  alertStatusTone,
  alertTypeLabel,
  canActOnAlert,
  challengeOutcomeLabel,
  credentialAccessKindLabel,
  credentialOutcomeLabel,
  credentialOutcomeTone,
  emptyState,
  ipCheckText,
  ipModeHint,
  ipModeLabel,
  isTerminalAlert,
  loginOutcomeLabel,
  loginOutcomeTone,
  mfaFactorLabel,
  mfaStatusLabel,
  neverAccessed,
  severityLabel,
  severityTone,
  unavailableCapabilities,
  type AccessSecurityAlert,
  type AccessSecurityCapabilities,
  type AccessSecurityCredential,
  type AccessSecurityIpCheck,
} from './access-security-model';

/**
 * 16.5.2.1 登录与访问安全 前端模型的展示口径。
 *
 * 🔴 这组用例守的是页面上最容易做丢的五件事：
 *
 * 1. **「读不到」与「没有」严格分开**（`known=false` / `available=false` 的文案不许说成「正常」）。
 * 2. **量不到的维度必须在清单里**（不被悄悄消失），并带归属模块。
 * 3. **严重度是受控值域**，且**低危不是绿色** —— 绿色会让人以为可以无视。
 * 4. **终态告警不可再处置**（已忽略 / 已处置是终态）。
 * 5. **空态文案各不相同**（不是同一个空态换个图标）。
 */
describe('16.5.2.1 登录与访问安全前端模型', () => {
  it('白名单前缀是独立的一条，不是并进既有 channel 分支', () => {
    expect(BASE).toBe('channel/access-security');
  });

  it('严重度恰为四级，状态机恰为四态，且与后端 DB 层 CHECK 同一份值域', () => {
    expect(SEVERITIES.map((s) => s.value)).toEqual(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']);
    expect(ALERT_STATUSES.map((s) => s.value)).toEqual([
      'NEW',
      'ACKNOWLEDGED',
      'IGNORED',
      'RESOLVED',
    ]);
    expect(ALERT_TYPES.map((t) => t.value)).toEqual([
      'FAILED_LOGIN_BURST',
      'SUCCESS_LOGIN_BURST',
      'IP_NOT_ALLOWED',
      'ALLOWLIST_EMPTY',
    ]);
    expect(IP_MODES.map((m) => m.value)).toEqual(['DISABLED', 'REPORT_ONLY', 'ENFORCE']);
    expect(MFA_FACTORS.map((f) => f.value)).toEqual(['TOTP', 'SMS', 'EMAIL']);
    expect(MFA_STATUSES.map((s) => s.value)).toEqual(['PENDING', 'ACTIVE', 'DISABLED', 'REVOKED']);
  });

  it('严重度的色调：严重红、高黄、中蓝、低中性 —— 低危不是「没事」', () => {
    expect(severityTone('CRITICAL')).toBe('danger');
    expect(severityTone('HIGH')).toBe('warning');
    expect(severityTone('MEDIUM')).toBe('info');
    expect(severityTone('LOW')).toBe('neutral');
    // 🔴 低危**不与**「没事」共用绿色。
    expect(severityTone('LOW')).not.toBe('success');
    expect(severityTone('UNKNOWN')).toBe('neutral');
  });

  it('人话名齐全，未知编码回落到原值（不编造）', () => {
    expect(severityLabel('CRITICAL')).toBe('严重');
    expect(alertStatusLabel('ACKNOWLEDGED')).toBe('已确认');
    expect(alertTypeLabel('FAILED_LOGIN_BURST')).toBe('登录失败激增');
    expect(ipModeLabel('ENFORCE')).toBe('强制拒绝');
    expect(mfaFactorLabel('TOTP')).toBe('认证器应用');
    expect(mfaStatusLabel('PENDING')).toBe('待绑定');
    expect(challengeOutcomeLabel('VERIFIED')).toBe('已验证');
    expect(credentialAccessKindLabel('READ_METADATA')).toBe('读取凭据元信息');
    expect(credentialOutcomeLabel('DENIED')).toBe('被拒');
    expect(loginOutcomeLabel('FAILURE')).toBe('失败');

    // 未知编码回落原值，不编造一个中文名
    expect(severityLabel('WHATEVER')).toBe('WHATEVER');
    expect(alertTypeLabel('WHATEVER')).toBe('WHATEVER');
    expect(mfaStatusLabel('WHATEVER')).toBe('WHATEVER');
  });

  it('告警状态色调：新警红、已确认黄、已处置绿', () => {
    expect(alertStatusTone('NEW')).toBe('danger');
    expect(alertStatusTone('ACKNOWLEDGED')).toBe('warning');
    expect(alertStatusTone('RESOLVED')).toBe('success');
    expect(alertStatusTone('WHATEVER')).toBe('neutral');
  });

  it('🔴 「读不到」与「没有」严格分开：量不到的维度留在清单里并带归属模块', () => {
    const caps: AccessSecurityCapabilities = {
      capabilities: [
        {
          code: 'LOGIN_AUDIT',
          label: '登录审计',
          available: true,
          source: 'identity 的 operation_log',
          ownerModule: '',
          impact: '能读',
        },
        {
          code: 'LOGIN_SOURCE_IP',
          label: '按来源 IP 判定异常登录',
          available: false,
          source: 'identity 的 operation_log.detail.remote',
          ownerModule: '17',
          impact: '登录事件不带来源 IP',
        },
      ],
      mfaNote: '',
      loginAuditNote: '',
      alertNote: '',
      ipAllowlistNote: '',
      sessionNote: '',
      credentialNote: '',
      downstreamNote: '',
      boundaryNote: '',
    };
    const unavailable = unavailableCapabilities(caps);
    expect(unavailable).toHaveLength(1);
    const [first] = unavailable;
    expect(first?.code).toBe('LOGIN_SOURCE_IP');
    expect(first?.ownerModule).toBe('17');
    // 🔴 量不到的**不会**被悄悄消失（清单里仍然有它）
    expect(caps.capabilities).toHaveLength(2);
  });

  it('🔴 IP 判定：「在不在白名单」与「能不能进」是两回事', () => {
    const notInAllowlist: AccessSecurityIpCheck = {
      ip: '192.168.1.1',
      valid: true,
      allowed: true,
      inAllowlist: false,
      mode: 'REPORT_ONLY',
      modeLabel: '只告警不拦截',
      matchedEntryId: null,
      matchedCidr: null,
      enforce: false,
      reason: '不在任何生效中的白名单网段内',
    };
    // 🔴 「只告警不拦截」口径下不在白名单**仍然能进**
    expect(notInAllowlist.allowed).toBe(true);
    expect(ipCheckText(notInAllowlist)).toContain('仍能进');

    const enforced: AccessSecurityIpCheck = {
      ...notInAllowlist,
      mode: 'ENFORCE',
      enforce: true,
      allowed: false,
    };
    expect(ipCheckText(enforced)).toContain('不能进');

    const inside: AccessSecurityIpCheck = {
      ...notInAllowlist,
      inAllowlist: true,
      allowed: true,
      matchedCidr: '10.0.0.0/8',
    };
    expect(ipCheckText(inside)).toContain('在白名单网段内');

    const invalid: AccessSecurityIpCheck = { ...notInAllowlist, valid: false, ip: '999.1.1.1' };
    expect(ipCheckText(invalid)).toContain('不合法');
  });

  it('🔴 终态告警不可再处置', () => {
    const base: AccessSecurityAlert = {
      alertId: 'a',
      alertType: 'FAILED_LOGIN_BURST',
      severity: 'HIGH',
      status: 'NEW',
      subjectKind: 'USERNAME',
      subjectValue: 'alice',
      title: 't',
      summary: 's',
      eventCount: 5,
      windowStart: null,
      windowEnd: null,
      detectedAt: 'now',
      acknowledgedBy: null,
      acknowledgedAt: null,
      ignoredBy: null,
      ignoredAt: null,
      ignoreReason: '',
      resolvedBy: null,
      resolvedAt: null,
      resolutionNote: '',
      version: 0,
      createdAt: 'now',
      actions: [],
    };
    expect(canActOnAlert(base)).toBe(true);
    expect(isTerminalAlert({ ...base, status: 'RESOLVED' })).toBe(true);
    expect(canActOnAlert({ ...base, status: 'RESOLVED' })).toBe(false);
    expect(isTerminalAlert({ ...base, status: 'IGNORED' })).toBe(true);
    expect(canActOnAlert({ ...base, status: 'ACKNOWLEDGED' })).toBe(true);
    expect(canActOnAlert(null)).toBe(false);
  });

  it('🔴 凭据「从未被访问」要被单独标出来', () => {
    const credential: AccessSecurityCredential = {
      credentialId: 'c',
      provider: 'file://',
      externalRef: 'file://x',
      tokenType: 'OAUTH2',
      grantedScopes: 'orders.read',
      status: 'ACTIVE',
      authorizedAt: null,
      expiresAt: null,
      accessCount: 0,
      lastAccessedAt: null,
      lastAccessedBy: null,
    };
    expect(neverAccessed(credential)).toBe(true);
    expect(accessCountText(credential)).toContain('从未被访问');
    expect(neverAccessed({ ...credential, accessCount: 3 })).toBe(false);
    expect(accessCountText({ ...credential, accessCount: 3 })).toContain('3 次');
  });

  it('空态文案各不相同（不是同一个空态换个图标）', () => {
    const titles = [
      emptyState('NO_POLICY').title,
      emptyState('NO_LOGIN_AUDIT').title,
      emptyState('NO_ALERT').title,
      emptyState('NO_ALLOWLIST').title,
      emptyState('NO_CREDENTIAL_ACCESS').title,
    ];
    expect(new Set(titles).size).toBe(titles.length);
    // 🔴 「没有记录」不等于「没有风险」这句话必须在登录审计空态里出现
    expect(emptyState('NO_LOGIN_AUDIT').description).toContain('没有记录');
    expect(emptyState('NO_LOGIN_AUDIT').description).toContain('不等于');
    // 🔴 空白名单不等于放行所有
    expect(emptyState('NO_ALLOWLIST').description).toContain('不等于放行所有');
  });

  it('IP 口径提示写清语义（不限制是租户显式选的，不是默认值）', () => {
    expect(ipModeHint('DISABLED')).toContain('显式选');
    expect(ipModeHint('REPORT_ONLY')).toContain('告警');
    expect(ipModeHint('ENFORCE')).toContain('拒绝');
  });

  it('登录成败与凭据访问结果的色调', () => {
    expect(loginOutcomeTone('SUCCESS')).toBe('success');
    expect(loginOutcomeTone('FAILURE')).toBe('danger');
    expect(credentialOutcomeTone('SUCCESS')).toBe('success');
    expect(credentialOutcomeTone('DENIED')).toBe('warning');
    expect(credentialOutcomeTone('FAILED')).toBe('danger');
  });
});
