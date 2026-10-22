'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import {
  CircleHelp,
  Fingerprint,
  KeyRound,
  Loader2,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';
import {
  accessCountText,
  alertStatusLabel,
  alertStatusTone,
  alertTypeLabel,
  canActOnAlert,
  challengeOutcomeLabel,
  checkIp,
  controlledAccess,
  credentialAccessKindLabel,
  credentialOutcomeLabel,
  credentialOutcomeTone,
  emptyState,
  fetchAlerts,
  fetchCapabilities,
  fetchCredentialAccess,
  fetchCredentials,
  fetchIpAllowlist,
  fetchLoginAudit,
  fetchPolicy,
  fetchSession,
  ignoreAlert,
  ipCheckText,
  ipModeHint,
  ipModeLabel,
  loginOutcomeLabel,
  loginOutcomeTone,
  mfaFactorLabel,
  mfaStatusLabel,
  neverAccessed,
  resolveAlert,
  acknowledgeAlert,
  runScan,
  savePolicy,
  severityLabel,
  severityTone,
  unavailableCapabilities,
  type AccessSecurityAlert,
  type AccessSecurityAlertPage,
  type AccessSecurityCapabilities,
  type AccessSecurityCredentialAccessPage,
  type AccessSecurityCredentialPage,
  type AccessSecurityIpAllowlistPage,
  type AccessSecurityIpCheck,
  type AccessSecurityLoginAuditPage,
  type AccessSecurityPolicy,
  type AccessSecurityPolicyInput,
  type AccessSecuritySession,
} from './access-security-model';

/**
 * 16.5.2.1 登录与访问安全 工作区。
 *
 * 蓝图：「管理平台账号与系统的访问安全：多因子认证、登录审计、异常登录告警、IP 白名单、
 * 会话管理；平台授权凭据的访问审计。输入：认证配置；登录事件；访问审计事件。
 * 输出：安全配置 + 审计记录 + 告警。关系：上游 = 17 identity；下游 = 2 凭据引用。
 * **平台凭据是最值钱的资产**，访问必须有审计且最小权限。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **「读不到」显示成「读不到」**，不显示成「没有异常」—— 后端 `known=false` 就是没记录，
 *    页面显示成「还没回填」并把原因一起给出，绝不用空列表冒充一切正常。
 * 2. **量不到的维度显式标「量不到」+ 归属模块**（来源 IP、会话到期时刻、MFA 挑战真实下发）。
 * 3. **前端不重算后端的判定**：IP 网段判定、告警严重度、状态机结果全部原样显示后端算好的值。
 * 4. **凭据只显示元信息**：页面不显示、也不请求任何令牌值。
 */
export function AccessSecurityWorkspace() {
  const [caps, setCaps] = useState<AccessSecurityCapabilities | null>(null);
  const [policy, setPolicy] = useState<AccessSecurityPolicy | null>(null);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<AccessSecurityPolicyInput | null>(null);

  const [alerts, setAlerts] = useState<AccessSecurityAlertPage | null>(null);
  const [selectedAlert, setSelectedAlert] = useState<AccessSecurityAlert | null>(null);
  const [alertReason, setAlertReason] = useState('');

  const [audit, setAudit] = useState<AccessSecurityLoginAuditPage | null>(null);
  const [allowlist, setAllowlist] = useState<AccessSecurityIpAllowlistPage | null>(null);
  const [newCidr, setNewCidr] = useState('');
  const [newCidrLabel, setNewCidrLabel] = useState('');
  const [checkTarget, setCheckTarget] = useState('');
  const [checkResult, setCheckResult] = useState<AccessSecurityIpCheck | null>(null);

  const [credentialAccess, setCredentialAccess] =
    useState<AccessSecurityCredentialAccessPage | null>(null);
  const [credentials, setCredentials] = useState<AccessSecurityCredentialPage | null>(null);
  const [probeId, setProbeId] = useState('');
  const [probePurpose, setProbePurpose] = useState('');

  const [session, setSession] = useState<AccessSecuritySession | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const loadAlerts = useCallback(async () => {
    setAlerts(await fetchAlerts('', '', '', 0, 20));
  }, []);

  const loadAudit = useCallback(async () => {
    setAudit(await fetchLoginAudit('', '', 0, 20));
  }, []);

  const loadAllowlist = useCallback(async () => {
    setAllowlist(await fetchIpAllowlist('', 0, 20));
  }, []);

  const loadCredentials = useCallback(async () => {
    setCredentialAccess(await fetchCredentialAccess('', '', 0, 20));
    setCredentials(await fetchCredentials(0, 20));
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, policyResult] = await Promise.all([fetchCapabilities(), fetchPolicy()]);
      setCaps(capsResult);
      setPolicy(policyResult);
      await Promise.all([loadAlerts(), loadAudit(), loadAllowlist(), loadCredentials()]);
      setSession(await fetchSession());
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [loadAlerts, loadAudit, loadAllowlist, loadCredentials]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const unavailable = useMemo(() => unavailableCapabilities(caps), [caps]);

  const startEdit = useCallback(() => {
    if (!policy) return;
    setDraft({
      policyName: policy.policyName || '访问安全策略',
      mfaRequired: policy.mfaRequired,
      mfaPrimaryFactor: policy.mfaPrimaryFactor || 'TOTP',
      mfaGracePeriodMinutes: policy.mfaGracePeriodMinutes ?? 0,
      mfaEnforcementFrom: policy.mfaEnforcementFrom,
      sessionIdleTimeoutMinutes: policy.sessionIdleTimeoutMinutes || 480,
      sessionAbsoluteTimeoutMinutes: policy.sessionAbsoluteTimeoutMinutes || 1440,
      sessionMaxConcurrent: policy.sessionMaxConcurrent || 5,
      sessionRevokeOnMfaChange: policy.sessionRevokeOnMfaChange,
      sessionRevokeOnPasswordChange: policy.sessionRevokeOnPasswordChange,
      ipAllowlistMode: policy.ipAllowlistMode || 'DISABLED',
      anomalyWindowMinutes: policy.anomalyWindowMinutes || 15,
      anomalyFailedLoginThreshold: policy.anomalyFailedLoginThreshold || 5,
      anomalySuccessLoginThreshold: policy.anomalySuccessLoginThreshold || 3,
      version: policy.version,
    });
    setEditing(true);
  }, [policy]);

  const submitPolicy = useCallback(async () => {
    if (!draft) return;
    setBusy(true);
    setError(null);
    try {
      const saved = await savePolicy(draft);
      setPolicy(saved);
      setEditing(false);
      setNotice(
        '策略已保存。🔴 阈值改了**只影响之后**的扫描 —— 历史告警与审计记录原样留着，不会被追溯改写。',
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [draft]);

  const doScan = useCallback(async () => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const result = await runScan();
      if (!result.known) {
        setNotice(`🔴 ${result.note}（扫描窗口 ${result.windowStart} ~ ${result.windowEnd}）`);
      } else {
        setNotice(
          `扫描完成：读到 ${result.eventsScanned} 条登录 / 登出事件，新落 ${result.eventsRecorded} 条审计；` +
            `新出告警 ${result.alertsCreated} 条，更新 ${result.alertsUpdated} 条。`,
        );
      }
      await Promise.all([loadAlerts(), loadAudit()]);
    } catch (e) {
      setError(e instanceof Error ? e.message : '扫描失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [loadAlerts, loadAudit]);

  const actOnAlert = useCallback(
    async (action: 'acknowledge' | 'ignore' | 'resolve') => {
      if (!selectedAlert) return;
      if (!alertReason.trim()) {
        setError('处置理由必填 —— 出了事没人认账等于没有处置记录');
        return;
      }
      setBusy(true);
      setError(null);
      try {
        const updated =
          action === 'acknowledge'
            ? await acknowledgeAlert(selectedAlert.alertId, alertReason.trim())
            : action === 'ignore'
              ? await ignoreAlert(selectedAlert.alertId, alertReason.trim())
              : await resolveAlert(selectedAlert.alertId, alertReason.trim());
        setSelectedAlert(updated);
        setAlertReason('');
        await loadAlerts();
        setNotice('处置已留痕（谁 / 什么时候 / 从什么状态到什么状态 / 为什么）。');
      } catch (e) {
        setError(e instanceof Error ? e.message : '处置失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [alertReason, loadAlerts, selectedAlert],
  );

  const addCidr = useCallback(async () => {
    if (!newCidr.trim()) {
      setError('请填写网段');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const response = await fetch(`/api/channel/access-security/ip-allowlist`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          cidrRange: newCidr.trim(),
          label: newCidrLabel.trim(),
          scopeKind: 'TENANT',
          status: 'ACTIVE',
        }),
      });
      if (!response.ok) {
        const body = await response.json().catch(() => ({}) as Record<string, unknown>);
        throw new Error(String(body.message ?? '登记失败，请重试'));
      }
      setNewCidr('');
      setNewCidrLabel('');
      await loadAllowlist();
      setNotice('白名单条目已登记。');
    } catch (e) {
      setError(e instanceof Error ? e.message : '登记失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [loadAllowlist, newCidr, newCidrLabel]);

  const doCheck = useCallback(async () => {
    if (!checkTarget.trim()) {
      setError('请填写要判定的 IP');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      setCheckResult(await checkIp(checkTarget.trim()));
    } catch (e) {
      setError(e instanceof Error ? e.message : '判定失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [checkTarget]);

  const doProbe = useCallback(async () => {
    if (!probeId.trim()) {
      setError('请填写要访问的凭据 ID');
      return;
    }
    if (!probePurpose.trim()) {
      setError('请填写访问用途 —— 没有用途的访问审计等于没审计');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const result = await controlledAccess(probeId.trim(), probePurpose.trim());
      setNotice(result.message);
      await loadCredentials();
    } catch (e) {
      setError(e instanceof Error ? e.message : '受控访问失败，请重试');
      await loadCredentials();
    } finally {
      setBusy(false);
    }
  }, [loadCredentials, probeId, probePurpose]);

  if (loading) {
    return (
      <div className="text-copy-13 text-muted-foreground flex items-center gap-2">
        <Loader2 className="h-4 w-4 animate-spin" />
        正在加载登录与访问安全…
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="登录与访问安全"
        description="多因子认证、登录审计、异常登录告警、IP 白名单、会话管理，以及平台授权凭据的访问审计。"
      />

      {error && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span className="text-copy-13">{error}</span>
        </div>
      )}
      {notice && (
        <div className="border-info-soft bg-info-soft text-info flex items-start gap-2 rounded-lg border px-4 py-3">
          <CircleHelp className="mt-0.5 h-4 w-4 shrink-0" />
          <span className="text-copy-13 whitespace-pre-line">{notice}</span>
        </div>
      )}

      {/* ---------------- 概览 ---------------- */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="未关闭告警"
          value={(alerts?.newCount ?? 0) + (alerts?.acknowledgedCount ?? 0)}
          polarity="negative"
        />
        <StatCard
          label="严重 / 高危告警"
          value={(alerts?.criticalCount ?? 0) + (alerts?.highCount ?? 0)}
          polarity="negative"
        />
        <StatCard label="本地登录审计" value={audit?.total ?? 0} polarity="neutral" />
        <StatCard label="凭据访问记录" value={credentialAccess?.total ?? 0} polarity="neutral" />
      </div>

      {/* ---------------- 能力声明 ---------------- */}
      {caps && (
        <SectionCard
          title="能力声明"
          description="哪些能力现在做得到、做得到的依据是什么、做不到的归哪个模块 —— 界面不替上游猜。"
        >
          <div className="space-y-4">
            <div className="grid gap-2 md:grid-cols-2">
              {caps.capabilities.map((c) => (
                <div key={c.code} className="border-border rounded-md border px-3 py-2">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-copy-13 text-foreground font-medium">{c.label}</span>
                    <Badge tone={c.available ? 'success' : 'warning'}>
                      {c.available ? '做得到' : '做不到'}
                    </Badge>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    依据：{c.source}
                    {!c.available && c.ownerModule ? ` · 归属模块：${c.ownerModule}` : ''}
                  </p>
                  <p className="text-copy-12 text-muted-foreground mt-1">{c.impact}</p>
                </div>
              ))}
            </div>
            {unavailable.length > 0 && (
              <div className="border-warning-soft bg-warning-soft text-warning rounded-md border px-3 py-2">
                <p className="text-copy-13 font-medium">
                  🔴 本仓量不到的 {unavailable.length} 项：
                  {unavailable.map((c) => c.label).join('、')}
                </p>
                <p className="text-copy-12 mt-1">
                  这些维度**不会**被折成 0 或「正常」—— 它们在下面各分区里明确显示为「量不到」。
                </p>
              </div>
            )}
            <details className="text-copy-12 text-muted-foreground">
              <summary className="cursor-pointer">
                口径说明（MFA / 登录审计 / 告警 / IP / 会话 / 凭据 / 下游 / 边界）
              </summary>
              <div className="mt-2 space-y-2">
                <p>{caps.mfaNote}</p>
                <p>{caps.loginAuditNote}</p>
                <p>{caps.alertNote}</p>
                <p>{caps.ipAllowlistNote}</p>
                <p>{caps.sessionNote}</p>
                <p>{caps.credentialNote}</p>
                <p>{caps.downstreamNote}</p>
                <p>{caps.boundaryNote}</p>
              </div>
            </details>
          </div>
        </SectionCard>
      )}

      {/* ---------------- 安全策略 ---------------- */}
      <SectionCard
        title="安全策略"
        description="多因子认证口径、会话策略、IP 白名单执行口径与异常登录检测阈值（一租户一条，阈值必须由租户显式选）。"
        extra={
          !editing && (
            <Button size="sm" variant="outline" onClick={startEdit}>
              {policy?.configured ? '修改策略' : '登记策略'}
            </Button>
          )
        }
      >
        {!policy?.configured && !editing ? (
          <EmptyState
            icon={<ShieldCheck className="h-6 w-6" />}
            title={emptyState('NO_POLICY').title}
            description={emptyState('NO_POLICY').description}
            action={
              <Button size="sm" onClick={startEdit}>
                登记策略
              </Button>
            }
          />
        ) : editing && draft ? (
          <div className="space-y-4">
            <div className="grid gap-3 md:grid-cols-3">
              <FormField id="policy-name" label="策略名">
                <Input
                  id="policy-name"
                  value={draft.policyName}
                  onChange={(e) => setDraft({ ...draft, policyName: e.target.value })}
                />
              </FormField>
              <FormField id="mfa-factor" label="MFA 主因子">
                <select
                  id="mfa-factor"
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                  value={draft.mfaPrimaryFactor}
                  onChange={(e) => setDraft({ ...draft, mfaPrimaryFactor: e.target.value })}
                >
                  <option value="TOTP">认证器应用</option>
                  <option value="SMS">短信验证码</option>
                  <option value="EMAIL">邮件验证码</option>
                </select>
              </FormField>
              <FormField
                id="ip-mode"
                label="IP 白名单口径"
                hint={ipModeHint(draft.ipAllowlistMode)}
              >
                <select
                  id="ip-mode"
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                  value={draft.ipAllowlistMode}
                  onChange={(e) => setDraft({ ...draft, ipAllowlistMode: e.target.value })}
                >
                  <option value="DISABLED">不限制</option>
                  <option value="REPORT_ONLY">只告警不拦截</option>
                  <option value="ENFORCE">强制拒绝</option>
                </select>
              </FormField>
            </div>
            <div className="grid gap-3 md:grid-cols-3">
              <label className="text-copy-13 text-foreground space-y-1">
                <span>是否强制 MFA</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                  value={draft.mfaRequired ? '1' : '0'}
                  onChange={(e) => setDraft({ ...draft, mfaRequired: e.target.value === '1' })}
                >
                  <option value="0">不强制</option>
                  <option value="1">强制</option>
                </select>
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>MFA 宽限期（分钟）</span>
                <Input
                  type="number"
                  min={0}
                  value={draft.mfaGracePeriodMinutes}
                  onChange={(e) =>
                    setDraft({ ...draft, mfaGracePeriodMinutes: Number(e.target.value) || 0 })
                  }
                />
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>异常检测窗口（分钟）</span>
                <Input
                  type="number"
                  min={1}
                  value={draft.anomalyWindowMinutes}
                  onChange={(e) =>
                    setDraft({ ...draft, anomalyWindowMinutes: Number(e.target.value) || 1 })
                  }
                />
              </label>
            </div>
            <div className="grid gap-3 md:grid-cols-3">
              <label className="text-copy-13 text-foreground space-y-1">
                <span>失败登录阈值（次）</span>
                <Input
                  type="number"
                  min={1}
                  value={draft.anomalyFailedLoginThreshold}
                  onChange={(e) =>
                    setDraft({ ...draft, anomalyFailedLoginThreshold: Number(e.target.value) || 1 })
                  }
                />
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>成功登录阈值（次）</span>
                <Input
                  type="number"
                  min={1}
                  value={draft.anomalySuccessLoginThreshold}
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      anomalySuccessLoginThreshold: Number(e.target.value) || 1,
                    })
                  }
                />
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>会话空闲超时（分钟）</span>
                <Input
                  type="number"
                  min={1}
                  value={draft.sessionIdleTimeoutMinutes}
                  onChange={(e) =>
                    setDraft({ ...draft, sessionIdleTimeoutMinutes: Number(e.target.value) || 1 })
                  }
                />
              </label>
            </div>
            <div className="grid gap-3 md:grid-cols-3">
              <label className="text-copy-13 text-foreground space-y-1">
                <span>会话绝对超时（分钟）</span>
                <Input
                  type="number"
                  min={1}
                  value={draft.sessionAbsoluteTimeoutMinutes}
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      sessionAbsoluteTimeoutMinutes: Number(e.target.value) || 1,
                    })
                  }
                />
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>并发会话上限</span>
                <Input
                  type="number"
                  min={1}
                  value={draft.sessionMaxConcurrent}
                  onChange={(e) =>
                    setDraft({ ...draft, sessionMaxConcurrent: Number(e.target.value) || 1 })
                  }
                />
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>换绑 MFA 即撤销会话</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                  value={draft.sessionRevokeOnMfaChange ? '1' : '0'}
                  onChange={(e) =>
                    setDraft({ ...draft, sessionRevokeOnMfaChange: e.target.value === '1' })
                  }
                >
                  <option value="1">是</option>
                  <option value="0">否</option>
                </select>
              </label>
            </div>
            <div className="flex gap-2">
              <Button size="sm" onClick={submitPolicy} disabled={busy}>
                {busy ? '保存中…' : '保存策略'}
              </Button>
              <Button size="sm" variant="outline" onClick={() => setEditing(false)}>
                取消
              </Button>
            </div>
          </div>
        ) : policy ? (
          <div className="space-y-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-copy-14 text-foreground font-medium">{policy.policyName}</span>
              <Badge tone="neutral">版本 {policy.version}</Badge>
              <Badge tone={policy.mfaRequired ? 'success' : 'neutral'}>
                MFA：
                {policy.mfaRequired
                  ? `强制（${mfaFactorLabel(policy.mfaPrimaryFactor)}）`
                  : '不强制'}
              </Badge>
              <Badge tone={policy.ipAllowlistMode === 'ENFORCE' ? 'danger' : 'info'}>
                IP 口径：{ipModeLabel(policy.ipAllowlistMode)}
              </Badge>
            </div>
            <div className="text-copy-13 text-muted-foreground grid gap-1 md:grid-cols-2">
              <span>
                异常检测窗口：{policy.anomalyWindowMinutes} 分钟 · 失败阈值{' '}
                {policy.anomalyFailedLoginThreshold} 次 · 成功阈值{' '}
                {policy.anomalySuccessLoginThreshold} 次
              </span>
              <span>
                会话：空闲 {policy.sessionIdleTimeoutMinutes} 分钟 / 绝对{' '}
                {policy.sessionAbsoluteTimeoutMinutes} 分钟 / 并发 {policy.sessionMaxConcurrent}
              </span>
            </div>
          </div>
        ) : null}
      </SectionCard>

      {/* ---------------- 异常登录告警 ---------------- */}
      <SectionCard
        title="异常登录告警"
        description="严重度是受控值域；处置状态机：新警 → 已确认 / 已忽略 / 已处置，每次处置都留痕。"
        extra={
          <Button size="sm" variant="outline" onClick={() => void loadAlerts()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
        }
      >
        {alerts && alerts.items.length === 0 ? (
          <EmptyState
            icon={<ShieldCheck className="h-6 w-6" />}
            title={emptyState('NO_ALERT').title}
            description={emptyState('NO_ALERT').description}
          />
        ) : (
          <div className="space-y-3">
            <div className="space-y-2">
              {(alerts?.items ?? []).map((alert) => (
                <button
                  key={alert.alertId}
                  type="button"
                  className="border-border bg-card hover:bg-muted/40 block w-full rounded-md border px-3 py-2 text-left"
                  onClick={() => {
                    setSelectedAlert(alert);
                    setAlertReason('');
                  }}
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={severityTone(alert.severity)}>
                      {severityLabel(alert.severity)}
                    </Badge>
                    <Badge tone={alertStatusTone(alert.status)}>
                      {alertStatusLabel(alert.status)}
                    </Badge>
                    <span className="text-copy-13 text-foreground font-medium">
                      {alertTypeLabel(alert.alertType)}
                    </span>
                    <span className="text-copy-12 text-muted-foreground">
                      {alert.subjectValue} · {alert.eventCount} 次
                    </span>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">{alert.title}</p>
                </button>
              ))}
            </div>

            {selectedAlert && (
              <div className="border-border rounded-md border px-3 py-3">
                <p className="text-copy-13 text-foreground font-medium">{selectedAlert.title}</p>
                <p className="text-copy-12 text-muted-foreground mt-1">{selectedAlert.summary}</p>
                {selectedAlert.actions.length > 0 && (
                  <div className="text-copy-12 text-muted-foreground mt-2 space-y-1">
                    {selectedAlert.actions.map((action) => (
                      <div key={action.actionId}>
                        {action.action}：{action.fromStatus} → {action.toStatus} · {action.reason}
                      </div>
                    ))}
                  </div>
                )}
                {canActOnAlert(selectedAlert) ? (
                  <div className="mt-3 space-y-2">
                    <Input
                      value={alertReason}
                      placeholder="处置理由（必填）"
                      onChange={(e) => setAlertReason(e.target.value)}
                    />
                    <div className="flex flex-wrap gap-2">
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        onClick={() => void actOnAlert('acknowledge')}
                      >
                        确认
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        onClick={() => void actOnAlert('ignore')}
                      >
                        忽略
                      </Button>
                      <Button size="sm" disabled={busy} onClick={() => void actOnAlert('resolve')}>
                        处置
                      </Button>
                    </div>
                  </div>
                ) : (
                  <p className="text-copy-12 text-muted-foreground mt-2">
                    🔴 「{alertStatusLabel(selectedAlert.status)}」是终态 ——
                    要重新处置只能等下一次告警。
                  </p>
                )}
              </div>
            )}
          </div>
        )}
      </SectionCard>

      {/* ---------------- 登录审计 ---------------- */}
      <SectionCard
        title="登录审计"
        description="登录事件的真源头是身份服务的操作日志；本地留存一份是因为那边的排障日志只有 90 天留存期。"
        extra={
          <Button size="sm" disabled={busy} onClick={() => void doScan()}>
            {busy ? '扫描中…' : '跑一次扫描'}
          </Button>
        }
      >
        {audit && !audit.known ? (
          <EmptyState
            icon={<Fingerprint className="h-6 w-6" />}
            title={emptyState('NO_LOGIN_AUDIT').title}
            description={audit.note || emptyState('NO_LOGIN_AUDIT').description}
            action={
              <Button size="sm" disabled={busy} onClick={() => void doScan()}>
                跑一次扫描
              </Button>
            }
          />
        ) : (
          <div className="space-y-2">
            {(audit?.items ?? []).map((item) => (
              <div key={item.auditId} className="border-border rounded-md border px-3 py-2">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={loginOutcomeTone(item.outcome)}>
                    {loginOutcomeLabel(item.outcome)}
                  </Badge>
                  <span className="text-copy-13 text-foreground font-medium">
                    {item.actorName || '（未知账号）'}
                  </span>
                  {item.anomaly && <Badge tone="warning">异常</Badge>}
                </div>
                <p className="text-copy-12 text-muted-foreground mt-1">{item.summary}</p>
                <p className="text-copy-12 text-muted-foreground mt-1">
                  {item.occurredAt} · 来源 IP：
                  {item.sourceIpAvailable
                    ? item.sourceIp
                    : '🔴 量不到（身份服务的登录事件不带来源 IP）'}
                </p>
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---------------- IP 白名单 ---------------- */}
      <SectionCard
        title="IP 白名单"
        description="网段判定走数据库的 inet 包含运算（真的按网段判，不是字符串相等）。空白名单 ≠ 放行所有。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-4">
            <FormField id="cidr" label="网段" hint="支持 10.0.0.0/8、10.0.0.5、2001:db8::/32">
              <Input
                id="cidr"
                value={newCidr}
                placeholder="10.0.0.0/8"
                onChange={(e) => setNewCidr(e.target.value)}
              />
            </FormField>
            <FormField id="cidr-label" label="备注">
              <Input
                id="cidr-label"
                value={newCidrLabel}
                onChange={(e) => setNewCidrLabel(e.target.value)}
              />
            </FormField>
            <div className="flex items-end">
              <Button size="sm" disabled={busy} onClick={() => void addCidr()}>
                登记白名单
              </Button>
            </div>
            <FormField id="check-ip" label="判定一个 IP">
              <div className="flex gap-2">
                <Input
                  id="check-ip"
                  value={checkTarget}
                  placeholder="10.0.0.5"
                  onChange={(e) => setCheckTarget(e.target.value)}
                />
                <Button size="sm" variant="outline" disabled={busy} onClick={() => void doCheck()}>
                  判定
                </Button>
              </div>
            </FormField>
          </div>

          {checkResult && (
            <div className="border-border rounded-md border px-3 py-2">
              <div className="flex flex-wrap items-center gap-2">
                <Badge
                  tone={
                    checkResult.inAllowlist ? 'success' : checkResult.enforce ? 'danger' : 'warning'
                  }
                >
                  {checkResult.inAllowlist
                    ? '在白名单内'
                    : checkResult.enforce
                      ? '不能进'
                      : '不在白名单'}
                </Badge>
                <span className="text-copy-13 text-foreground">{ipCheckText(checkResult)}</span>
              </div>
              <p className="text-copy-12 text-muted-foreground mt-1">{checkResult.reason}</p>
            </div>
          )}

          {allowlist && allowlist.items.length === 0 ? (
            <EmptyState
              icon={<ShieldAlert className="h-6 w-6" />}
              title={emptyState('NO_ALLOWLIST').title}
              description={emptyState('NO_ALLOWLIST').description}
            />
          ) : (
            <div className="space-y-2">
              {(allowlist?.items ?? []).map((entry) => (
                <div
                  key={entry.entryId}
                  className="border-border flex flex-wrap items-center gap-2 rounded-md border px-3 py-2"
                >
                  <span className="text-copy-13 text-foreground font-mono">{entry.cidrRange}</span>
                  <span className="text-copy-13 text-muted-foreground">{entry.label}</span>
                  <Badge tone={entry.status === 'ACTIVE' ? 'success' : 'neutral'}>
                    {entry.status === 'ACTIVE' ? '生效中' : '已停用'}
                  </Badge>
                  <span className="text-copy-12 text-muted-foreground">
                    {entry.scopeKind === 'TENANT' ? '租户口径' : '连接口径'} · 生效{' '}
                    {entry.effectiveFrom}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 平台授权凭据的访问审计 ---------------- */}
      <SectionCard
        title="平台授权凭据的访问审计"
        description="平台凭据是最值钱的资产：访问必须有审计且最小权限 —— 这张表只增不改（append-only），且永远不记令牌值。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-3">
            <FormField id="probe-id" label="凭据 ID">
              <Input
                id="probe-id"
                value={probeId}
                placeholder="凭据 UUID"
                onChange={(e) => setProbeId(e.target.value)}
              />
            </FormField>
            <FormField id="probe-purpose" label="访问用途" hint="没有用途的访问审计等于没审计">
              <Input
                id="probe-purpose"
                value={probePurpose}
                placeholder="换令牌前核对授权范围"
                onChange={(e) => setProbePurpose(e.target.value)}
              />
            </FormField>
            <div className="flex items-end">
              <Button size="sm" disabled={busy} onClick={() => void doProbe()}>
                受控访问（读元信息并留痕）
              </Button>
            </div>
          </div>

          {credentials && credentials.items.length > 0 && (
            <div className="space-y-2">
              <p className="text-copy-12 text-muted-foreground">
                按凭据汇总（哪些凭据从没人碰过）：
              </p>
              {credentials.items.map((credential) => (
                <div
                  key={credential.credentialId}
                  className="border-border flex flex-wrap items-center gap-2 rounded-md border px-3 py-2"
                >
                  <KeyRound className="text-copy-12 text-muted-foreground h-4 w-4" />
                  <span className="text-copy-13 text-foreground font-mono">
                    {credential.externalRef}
                  </span>
                  <Badge tone={credential.status === 'ACTIVE' ? 'success' : 'neutral'}>
                    {credential.status}
                  </Badge>
                  <span className="text-copy-12 text-muted-foreground">
                    授权范围 {credential.grantedScopes || '（未记录）'} ·{' '}
                    {accessCountText(credential)}
                  </span>
                  {neverAccessed(credential) && <Badge tone="warning">从未被访问</Badge>}
                </div>
              ))}
            </div>
          )}

          {credentialAccess && credentialAccess.items.length === 0 ? (
            <EmptyState
              icon={<KeyRound className="h-6 w-6" />}
              title={emptyState('NO_CREDENTIAL_ACCESS').title}
              description={emptyState('NO_CREDENTIAL_ACCESS').description}
            />
          ) : (
            <div className="space-y-2">
              {(credentialAccess?.items ?? []).map((item) => (
                <div key={item.accessId} className="border-border rounded-md border px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={credentialOutcomeTone(item.outcome)}>
                      {credentialOutcomeLabel(item.outcome)}
                    </Badge>
                    <span className="text-copy-13 text-foreground">
                      {credentialAccessKindLabel(item.accessKind)}
                    </span>
                    <span className="text-copy-12 text-muted-foreground">{item.accessPurpose}</span>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    凭据 {item.credentialId} · {item.occurredAt} · 操作人{' '}
                    {item.actorId ?? '（系统）'}
                  </p>
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 会话现状 ---------------- */}
      {session && (
        <SectionCard
          title="会话现状"
          description="会话实体在身份服务；本模块管的是本服务侧的会话策略与审计，**不另起第二套会话系统**。"
        >
          <div className="space-y-2">
            {session.dimensions.map((dimension) => (
              <div
                key={dimension.code}
                className="border-border flex flex-wrap items-center gap-2 rounded-md border px-3 py-2"
              >
                <span className="text-copy-13 text-foreground font-medium">{dimension.label}</span>
                <Badge tone={dimension.available ? 'success' : 'warning'}>
                  {dimension.available ? dimension.value : '🔴 量不到'}
                </Badge>
                {!dimension.available && dimension.ownerModule ? (
                  <span className="text-copy-12 text-muted-foreground">
                    归属模块：{dimension.ownerModule}
                  </span>
                ) : null}
                <span className="text-copy-12 text-muted-foreground w-full">
                  {dimension.impact}
                </span>
              </div>
            ))}
          </div>
        </SectionCard>
      )}
    </div>
  );
}
