'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { AlertTriangle, Check, ExternalLink, Loader2, ShieldCheck, Store } from 'lucide-react';
import type {
  OAuthCompletedView,
  OAuthSessionView,
  OAuthShopCandidateView,
  OAuthStartView,
  PlatformView,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '@/lib/api';

/**
 * 2.2.1.2 授权向导：选平台 → 选账号 → 授权跳转 → 回调校验 → 确认店铺范围 → 完成。
 *
 * <p>六步全部由后端真实支撑，没有占位步骤：第 3 步跳的是后端返回的真实授权地址，
 * 第 4 步的状态来自回调校验后落库的会话，第 5 步的店铺列表来自平台账号接口的真实结果。
 *
 * <p>平台应用凭据是运行时配置（见 CHANNEL_OAUTH_*）。某个平台没配凭据时，后端会明确
 * 告诉缺哪个环境变量，这里把它原样显示——那是一条可执行的运维信息，不是"功能未上线"。
 */

type Step = 'platform' | 'account' | 'redirect' | 'verify' | 'scope' | 'done';

const steps: readonly { id: Step; label: string }[] = [
  { id: 'platform', label: '选择平台' },
  { id: 'account', label: '选择账号' },
  { id: 'redirect', label: '前往平台授权' },
  { id: 'verify', label: '回调校验' },
  { id: 'scope', label: '确认店铺范围' },
  { id: 'done', label: '完成' },
];

const stepIndex: Record<Step, number> = {
  platform: 0,
  account: 1,
  redirect: 2,
  verify: 3,
  scope: 4,
  done: 5,
};

const sessionStatusLabel: Record<string, string> = {
  PENDING: '等待你在平台完成授权',
  AUTHORIZED: '授权已通过，正在确认店铺范围',
  FAILED: '授权失败',
  COMPLETED: '已完成',
  EXPIRED: '已过期',
  CANCELLED: '已取消',
};

function newIdempotencyKey() {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `k-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export function OAuthWizard({
  platforms,
  can,
  onDone,
  onError,
}: {
  platforms: PlatformView[];
  can: (permission: string) => boolean;
  onDone: () => Promise<void>;
  onError: (e: unknown) => void;
}) {
  const [step, setStep] = useState<Step>('platform');
  const [platform, setPlatform] = useState('');
  const [loginHint, setLoginHint] = useState('');
  const [idempotencyKey, setIdempotencyKey] = useState(newIdempotencyKey);
  const [start, setStart] = useState<OAuthStartView | null>(null);
  // 从平台跳回来时手里只有 URL 上的 sessionId，没有 OAuthStartView；单独存，别硬造一个
  const [resumedSessionId, setResumedSessionId] = useState<string | null>(null);
  const [session, setSession] = useState<OAuthSessionView | null>(null);
  const [candidates, setCandidates] = useState<OAuthShopCandidateView[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [completed, setCompleted] = useState<OAuthCompletedView | null>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState('');
  const pollRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 只有真正开放接入的平台能发起授权；PLANNED 平台跳过去必然失败，不如不给选项
  const available = useMemo(
    () => platforms.filter((p) => p.supportStatus === 'AVAILABLE' || p.supportStatus === 'BETA'),
    [platforms],
  );

  useEffect(() => {
    if (!platform && available.length > 0) setPlatform(available[0]!.code);
  }, [available, platform]);

  const stopPolling = useCallback(() => {
    if (pollRef.current) clearTimeout(pollRef.current);
    pollRef.current = null;
  }, []);

  // 从平台授权页跳回来时，后端会 302 回这里并带上 oauthSession=...
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const sessionId = params.get('oauthSession');
    if (!sessionId) return;
    setResumedSessionId(sessionId);
    void resume(sessionId);
    return stopPolling;
    // 只在挂载时读一次 URL：之后的推进由向导自己的状态机负责
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stopPolling]);

  /** 当前正在走的授权会话：本地发起的用 start，跳回来的用 URL 上的那个。 */
  const sessionId = resumedSessionId ?? start?.sessionId ?? null;

  /** 第 4 步：轮询会话状态。回调还没落库时会先看到 PENDING。 */
  async function resume(sessionId: string) {
    setStep('verify');
    setBusy(true);
    try {
      const current = await api<OAuthSessionView>(`channel/oauth/sessions/${sessionId}`);
      setSession(current);
      if (current.status === 'AUTHORIZED') {
        await loadCandidates(sessionId);
        setStep('scope');
        return;
      }
      if (current.status === 'FAILED') {
        setFailure(current.failureMessage || '平台拒绝了本次授权');
        return;
      }
      if (current.status === 'COMPLETED') {
        // 已经确认过（例如用户刷新了页面），直接回到完成态
        setStep('done');
        await onDone();
        return;
      }
      if (current.status === 'CANCELLED' || current.status === 'EXPIRED') {
        setFailure(sessionStatusLabel[current.status] ?? '本次授权已结束');
        return;
      }
      // 回调还在路上：继续等，别把"还没到"显示成"失败"
      pollRef.current = setTimeout(() => void resume(sessionId), 1500);
    } catch (e) {
      setFailure(e instanceof Error ? e.message : '回调校验失败');
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  async function loadCandidates(sessionId: string) {
    const list = await api<OAuthShopCandidateView[]>(`channel/oauth/sessions/${sessionId}/shops`);
    setCandidates(list);
    setSelected(new Set(list.map((c) => c.id)));
  }

  /** 第 3 步：向后端换一个真实授权地址并跳过去。 */
  async function beginAuthorization() {
    setBusy(true);
    setFailure('');
    try {
      const view = await api<OAuthStartView>('channel/oauth/sessions', 'POST', {
        platformCode: platform,
        loginHint,
        idempotencyKey,
      });
      setStart(view);
      setStep('redirect');
      window.location.href = view.authorizeUrl;
    } catch (e) {
      setFailure(e instanceof Error ? e.message : '发起授权失败');
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  async function confirm() {
    if (!sessionId) return;
    setBusy(true);
    try {
      const result = await api<OAuthCompletedView>(
        `channel/oauth/sessions/${sessionId}/confirm`,
        'POST',
        {
          shopIds: [...selected],
          idempotencyKey: newIdempotencyKey(),
        },
      );
      setCompleted(result);
      setStep('done');
      await onDone();
    } catch (e) {
      setFailure(e instanceof Error ? e.message : '确认店铺范围失败');
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  async function cancel() {
    if (!sessionId) return;
    setBusy(true);
    try {
      await api(`channel/oauth/sessions/${sessionId}/cancel`, 'POST');
    } catch {
      // 已经不在待授权状态（例如用户已在平台点确认），取消失败不影响"回到第一步"
    } finally {
      stopPolling();
      setBusy(false);
      reset();
    }
  }

  function reset() {
    setStep('platform');
    setStart(null);
    setResumedSessionId(null);
    setSession(null);
    setCandidates([]);
    setSelected(new Set());
    setCompleted(null);
    setFailure('');
    setLoginHint('');
    setIdempotencyKey(newIdempotencyKey());
  }

  const current = stepIndex[step];

  return (
    <div className="bg-card space-y-4 rounded-md border p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-copy-14 font-medium">授权向导</p>
        <Button size="sm" variant="ghost" onClick={reset} disabled={busy}>
          重新开始
        </Button>
      </div>

      <ol className="flex flex-wrap gap-1" aria-label="授权步骤">
        {steps.map((s, i) => (
          <li
            key={s.id}
            aria-current={s.id === step ? 'step' : undefined}
            className={
              i === current
                ? 'bg-primary/10 text-primary text-copy-12 rounded-md px-2 py-1 font-medium'
                : i < current
                  ? 'text-muted-foreground text-copy-12 rounded-md px-2 py-1'
                  : 'text-muted-foreground/60 text-copy-12 rounded-md px-2 py-1'
            }
          >
            {i < current && <Check className="mr-1 inline h-3 w-3" />}
            {i + 1}. {s.label}
          </li>
        ))}
      </ol>

      {failure && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive text-copy-13 flex items-start gap-2 rounded-md border p-3"
        >
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{failure}</span>
        </div>
      )}

      {step === 'platform' && (
        <div className="space-y-3">
          {available.length === 0 ? (
            <p className="text-muted-foreground text-copy-13">当前没有已开放接入的平台。</p>
          ) : (
            <div className="grid gap-3 md:grid-cols-3">
              <label className="text-copy-13 flex flex-col gap-1">
                <span>平台</span>
                <select
                  value={platform}
                  onChange={(e) => setPlatform(e.target.value)}
                  className="border-border bg-background h-9 rounded-md border px-2"
                >
                  {available.map((p) => (
                    <option key={p.id} value={p.code}>
                      {p.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-copy-13 flex flex-col gap-1">
                <span>平台账号</span>
                <input
                  value={loginHint}
                  onChange={(e) => setLoginHint(e.target.value)}
                  placeholder="商家邮箱；Shopify 请填 my-store.myshopify.com"
                  className="border-border bg-background h-9 rounded-md border px-2"
                />
              </label>
              <div className="flex items-end">
                <Button
                  disabled={busy || !platform || !can('channel.connection.create')}
                  onClick={() => void beginAuthorization()}
                >
                  {busy ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <ExternalLink className="h-4 w-4" />
                  )}
                  去平台授权
                </Button>
              </div>
            </div>
          )}
          <p className="text-muted-foreground text-copy-12">
            部分平台（如 eBay）会在授权页让你重新选择要授权的账号；其余平台会预选上面填写的账号。
          </p>
        </div>
      )}

      {step === 'redirect' && start && (
        <div className="space-y-2">
          <p className="text-copy-13 flex items-center gap-2">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在跳转到 {start.platformName} 的授权页面…
          </p>
          <p className="text-muted-foreground text-copy-12">
            如果浏览器没有自动跳转，请
            <a className="text-primary underline" href={start.authorizeUrl}>
              点此前往 {start.platformName}
            </a>
            。授权完成后平台会把结果带回本系统，向导会自动继续。
          </p>
          <Button size="sm" variant="ghost" onClick={() => void cancel()}>
            放弃本次授权
          </Button>
        </div>
      )}

      {step === 'verify' && (
        <div className="space-y-2">
          <p className="text-copy-13 flex items-center gap-2">
            {busy ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <ShieldCheck className="h-4 w-4" />
            )}
            {session ? (sessionStatusLabel[session.status] ?? session.status) : '正在校验平台回调…'}
          </p>
          {session && session.grantedScopes && (
            <p className="text-muted-foreground text-copy-12">
              平台授予的权限范围：{session.grantedScopes}
            </p>
          )}
          <Button size="sm" variant="ghost" onClick={() => void cancel()}>
            放弃本次授权
          </Button>
        </div>
      )}

      {step === 'scope' && session && (
        <div className="space-y-3">
          <p className="text-copy-13">
            账号{' '}
            <span className="font-medium">
              {session.authorizedAccountName || session.authorizedAccount}
            </span>{' '}
            授权成功， 请确认要纳管的店铺范围：
          </p>
          {candidates.length === 0 ? (
            <p className="text-muted-foreground text-copy-13">该平台没有返回可纳管的店铺。</p>
          ) : (
            <ul className="space-y-1">
              {candidates.map((c) => (
                <li key={c.id}>
                  <label className="text-copy-13 flex items-center gap-2">
                    <input
                      type="checkbox"
                      checked={selected.has(c.id)}
                      onChange={(e) => {
                        setSelected((previous) => {
                          const next = new Set(previous);
                          if (e.target.checked) next.add(c.id);
                          else next.delete(c.id);
                          return next;
                        });
                      }}
                    />
                    <Store className="h-4 w-4" />
                    {c.shopName || c.externalShopId}
                    {c.marketplaceCode && <Badge tone="neutral">{c.marketplaceCode}</Badge>}
                    <span className="text-muted-foreground text-copy-12 break-all">
                      {c.externalShopId}
                    </span>
                  </label>
                </li>
              ))}
            </ul>
          )}
          <div className="flex gap-2">
            <Button size="sm" disabled={busy || selected.size === 0} onClick={() => void confirm()}>
              确认并完成
            </Button>
            <Button size="sm" variant="ghost" onClick={() => void cancel()}>
              放弃
            </Button>
          </div>
        </div>
      )}

      {step === 'done' && (
        <div className="space-y-2">
          <p className="text-success text-copy-13 flex items-center gap-2">
            <Check className="h-4 w-4" />
            授权完成，已创建连接与 {completed?.shopCount ?? 0} 个店铺档案。
          </p>
          {completed && (
            <p className="text-muted-foreground text-copy-12 break-all">
              连接 {completed.connectionId}；凭据引用 {completed.credentialId}
            </p>
          )}
          <p className="text-muted-foreground text-copy-12">
            下一步到「店铺与站点」补充法人、负责人与启用站点，再到平台目录核对枚举映射。
          </p>
          <Button size="sm" onClick={reset}>
            再授权一个平台账号
          </Button>
        </div>
      )}
    </div>
  );
}
