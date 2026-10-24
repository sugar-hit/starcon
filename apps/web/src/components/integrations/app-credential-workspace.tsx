'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import {
  CircleAlert,
  KeyRound,
  Loader2,
  RefreshCw,
  ShieldCheck,
  ShieldOff,
  TriangleAlert,
} from 'lucide-react';
import {
  addIpAllowlist,
  fetchAppDetail,
  fetchApps,
  fetchAuditEvents,
  fetchCapabilities,
  fetchOptions,
  fetchOverview,
  fetchQuotaStatus,
  grantScope,
  issueCredential,
  maskSecret,
  quotaLabel,
  quotaTone,
  registerApp,
  removeIpAllowlist,
  revokeApp,
  revokeCredential,
  revokeScope,
  rotateCredential,
  setQuota,
  verifyConclusion,
  verifyCredential,
  type AppParams,
} from './app-credential-model';

/**
 * 18.1.1.1 应用与凭证 工作区（卷 18 前端入口 `/integrations`）。
 *
 * 蓝图：「管理第三方接入的应用：应用注册、API Key/Secret 生成与轮换、授权范围（可访问的资源与操作）、
 * IP 白名单、调用配额。输出：应用档案 + 凭证（**明文仅在生成时展示一次**，之后只存摘要）。
 * **对外凭证与平台凭据同等对待**：不落库明文、访问有审计、泄露可一键吊销。」
 *
 * 🔴 页面上的五条纪律：
 *
 * 1. **明文只在生成那一次出现**：弹窗里给出 API Key / Secret，并明确写「这是唯一一次」；
 *    关掉之后只剩前缀与末四位，页面**不再**提供任何「再显示一次」。
 * 2. **「没配过配额」不是「已超限」**：未配置的窗口显示成灰色「不限（未配置）」，
 *    绝不显示成红色「已用尽」。
 * 3. **凭证自检的四个判定分开显示**：凭证 / 授权范围 / IP 白名单 / 配额各说各的。
 * 4. **能力声明如实显示**：未接通的项显示成 `available=false` 并写明归谁，
 *    不显示成「暂无」。
 * 5. **加载 / 空 / 错误三态齐全**：空态区分「筛选无结果」与「还没有注册过应用」。
 */

const PAGE_SIZE = 10;

interface AppForm {
  appCode: string;
  appName: string;
  appType: string;
  description: string;
  contactName: string;
  contactEmail: string;
}

const EMPTY_APP: AppForm = {
  appCode: '',
  appName: '',
  appType: 'THIRD_PARTY',
  description: '',
  contactName: '',
  contactEmail: '',
};

export function AppCredentialWorkspace() {
  const [capabilities, setCapabilities] = useState<Awaited<
    ReturnType<typeof fetchCapabilities>
  > | null>(null);
  const [options, setOptions] = useState<Awaited<ReturnType<typeof fetchOptions>> | null>(null);
  const [overview, setOverview] = useState<Awaited<ReturnType<typeof fetchOverview>> | null>(null);
  const [page, setPage] = useState<Awaited<ReturnType<typeof fetchApps>> | null>(null);
  const [detail, setDetail] = useState<Awaited<ReturnType<typeof fetchAppDetail>> | null>(null);
  const [audit, setAudit] = useState<Awaited<ReturnType<typeof fetchAuditEvents>> | null>(null);
  const [quotaStatus, setQuotaStatus] = useState<Awaited<ReturnType<typeof fetchQuotaStatus>>>([]);

  const [keyword, setKeyword] = useState('');
  const [appType, setAppType] = useState('');
  const [status, setStatus] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [appOpen, setAppOpen] = useState(false);
  const [appForm, setAppForm] = useState<AppForm>(EMPTY_APP);

  const [issued, setIssued] = useState<Awaited<ReturnType<typeof issueCredential>> | null>(null);
  const [issuedOpen, setIssuedOpen] = useState(false);

  const [scopeOpen, setScopeOpen] = useState(false);
  const [scopeForm, setScopeForm] = useState({ resourceCode: '', actionCode: 'READ' });

  const [ipOpen, setIpOpen] = useState(false);
  const [ipForm, setIpForm] = useState({ cidr: '', note: '' });

  const [quotaOpen, setQuotaOpen] = useState(false);
  const [quotaForm, setQuotaForm] = useState({ windowType: 'MINUTE', limitCount: '', enabled: true });

  const [verifyOpen, setVerifyOpen] = useState(false);
  const [verifyForm, setVerifyForm] = useState({
    apiKey: '',
    secret: '',
    clientIp: '',
    resourceCode: '',
    actionCode: 'READ',
  });
  const [verifyResult, setVerifyResult] = useState<Awaited<
    ReturnType<typeof verifyCredential>
  > | null>(null);

  const [revokeOpen, setRevokeOpen] = useState(false);
  const [revokeReason, setRevokeReason] = useState('');

  const params = useMemo<AppParams>(
    () => ({
      keyword: keyword || undefined,
      appType: appType || undefined,
      status: status || undefined,
    }),
    [keyword, appType, status],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, opts, stats, list] = await Promise.all([
        fetchCapabilities(),
        fetchOptions(),
        fetchOverview(),
        fetchApps(pageIndex, PAGE_SIZE, params),
      ]);
      setCapabilities(caps);
      setOptions(opts);
      setOverview(stats);
      setPage(list);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [params, pageIndex]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 打开某个应用的档案（五块 + 审计 + 额度判定一起拉）。 */
  const openDetail = useCallback(async (appId: string) => {
    setBusy(true);
    setError('');
    try {
      const [info, events, status] = await Promise.all([
        fetchAppDetail(appId),
        fetchAuditEvents(appId, 0, 20),
        fetchQuotaStatus(appId),
      ]);
      setDetail(info);
      setAudit(events);
      setQuotaStatus(status);
      setVerifyResult(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载应用档案失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  }, []);

  /** 统一的写操作封装：出错就把后端的话原样显示出来。 */
  const run = useCallback(
    async (action: () => Promise<unknown>, success: string) => {
      setBusy(true);
      setError('');
      setNotice('');
      try {
        await action();
        setNotice(success);
        await load();
        if (detail) await openDetail(detail.app.id);
        return true;
      } catch (e) {
        setError(e instanceof Error ? e.message : '操作失败，请稍后重试');
        return false;
      } finally {
        setBusy(false);
      }
    },
    [detail, load, openDetail],
  );

  const onRegister = async () => {
    const ok = await run(
      () =>
        registerApp({
          appCode: appForm.appCode,
          appName: appForm.appName,
          appType: appForm.appType,
          description: appForm.description,
          contactName: appForm.contactName,
          contactEmail: appForm.contactEmail,
        }),
      '应用已注册。',
    );
    if (ok) {
      setAppOpen(false);
      setAppForm(EMPTY_APP);
    }
  };

  const onIssue = async () => {
    if (!detail) return;
    setBusy(true);
    setError('');
    try {
      const result = await issueCredential(detail.app.id);
      setIssued(result);
      setIssuedOpen(true);
      await load();
      await openDetail(detail.app.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : '签发凭证失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  };

  const onRotate = async () => {
    if (!detail) return;
    setBusy(true);
    setError('');
    try {
      const result = await rotateCredential(detail.app.id);
      setIssued(result);
      setIssuedOpen(true);
      await load();
      await openDetail(detail.app.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : '轮换凭证失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  };

  const onVerify = async () => {
    if (!detail) return;
    setBusy(true);
    setError('');
    try {
      const result = await verifyCredential(detail.app.id, {
        apiKey: verifyForm.apiKey,
        secret: verifyForm.secret,
        clientIp: verifyForm.clientIp,
        resourceCode: verifyForm.resourceCode,
        actionCode: verifyForm.actionCode,
      });
      setVerifyResult(result);
      await openDetail(detail.app.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : '自检失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  };

  const toneClasses = {
    ok: 'bg-success-soft text-success',
    warn: 'bg-warning-soft text-warning',
    danger: 'bg-destructive-soft text-destructive',
    muted: 'bg-muted text-muted-foreground',
  } as const;

  return (
    <div className="space-y-4">
      <PageHeader
        title="应用与凭证"
        description="管理第三方接入的应用：注册、API Key/Secret 生成与轮换、授权范围、IP 白名单、调用配额。明文只展示一次，泄露可一键吊销。"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
            刷新
          </Button>
        }
      />

      {error && (
        <div className="text-destructive flex items-start gap-2 rounded-md border p-3 text-sm">
          <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}
      {notice && (
        <div className="text-success flex items-start gap-2 rounded-md border p-3 text-sm">
          <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{notice}</span>
        </div>
      )}

      {overview && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="应用总数" value={overview.totalApps} icon={<KeyRound className="h-4 w-4" />} />
          <StatCard label="正常应用" value={overview.activeApps} />
          <StatCard label="生效中的凭证" value={overview.activeCredentials} />
          <StatCard label="已吊销应用" value={overview.revokedApps} />
        </div>
      )}

      <SectionCard
        title="应用列表"
        description="真分页 + 真筛选：按标识 / 名称搜索，按类型与状态过滤。"
        extra={
          <Button onClick={() => setAppOpen(true)}>
            <KeyRound className="h-4 w-4" />
            注册应用
          </Button>
        }
      >
        <div className="mb-4 flex flex-wrap gap-2">
          <Input
            className="max-w-xs"
            placeholder="搜索应用标识或名称"
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value);
              setPageIndex(0);
            }}
          />
          <select
            className="border-border bg-background h-9 rounded-md border px-2 text-sm"
            value={appType}
            onChange={(e) => {
              setAppType(e.target.value);
              setPageIndex(0);
            }}
          >
            <option value="">全部类型</option>
            {(options?.appTypes ?? []).map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
          <select
            className="border-border bg-background h-9 rounded-md border px-2 text-sm"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPageIndex(0);
            }}
          >
            <option value="">全部状态</option>
            {(options?.statuses ?? []).map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>

        {loading && !page ? (
          <div className="text-muted-foreground flex items-center gap-2 py-10 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在加载应用列表…
          </div>
        ) : page && page.items.length === 0 ? (
          <EmptyState
            icon={<KeyRound className="h-6 w-6" />}
            title={keyword || appType || status ? '没有符合条件的应用' : '还没有注册过应用'}
            description={
              keyword || appType || status
                ? '换个筛选条件再试一次。'
                : '注册一个应用，才能签发 API Key / Secret 并授权它能访问的资源。'
            }
            action={
              keyword || appType || status ? (
                <Button
                  variant="outline"
                  onClick={() => {
                    setKeyword('');
                    setAppType('');
                    setStatus('');
                    setPageIndex(0);
                  }}
                >
                  清空筛选
                </Button>
              ) : (
                <Button onClick={() => setAppOpen(true)}>注册应用</Button>
              )
            }
          />
        ) : (
          <div className="space-y-2">
            {page?.items.map((app) => (
              <button
                key={app.id}
                type="button"
                onClick={() => void openDetail(app.id)}
                className="border-border hover:bg-muted/40 flex w-full flex-wrap items-center gap-3 rounded-md border px-3 py-2 text-left"
              >
                <span className="text-foreground font-medium">{app.appName}</span>
                <span className="text-muted-foreground text-copy-12 font-mono">{app.appCode}</span>
                <Badge className={toneClasses[app.status === 'ACTIVE' ? 'ok' : 'muted']}>
                  {app.statusLabel}
                </Badge>
                <span className="text-muted-foreground text-copy-12">{app.appTypeLabel}</span>
                <span className="text-muted-foreground ml-auto text-copy-12">
                  凭证 {app.activeCredentialCount} · 授权 {app.scopeCount} · 白名单 {app.ipAllowlistCount} ·
                  配额 {app.quotaCount}
                </span>
              </button>
            ))}
            {page && page.total > PAGE_SIZE && (
              <div className="flex items-center justify-end gap-2 pt-2">
                <Button
                  variant="outline"
                  disabled={pageIndex === 0}
                  onClick={() => setPageIndex((p) => Math.max(p - 1, 0))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground text-copy-12">
                  第 {pageIndex + 1} 页 / 共 {page.total} 个应用
                </span>
                <Button
                  variant="outline"
                  disabled={(pageIndex + 1) * PAGE_SIZE >= page.total}
                  onClick={() => setPageIndex((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            )}
          </div>
        )}
      </SectionCard>

      {detail && (
        <>
          <SectionCard
            title={detail.app.appName}
            description={`${detail.app.appCode} · ${detail.app.appTypeLabel} · ${detail.app.statusLabel}`}
            extra={
              <div className="flex flex-wrap gap-2">
                <Button variant="outline" onClick={() => void onIssue()} disabled={busy}>
                  签发凭证
                </Button>
                <Button variant="outline" onClick={() => void onRotate()} disabled={busy}>
                  轮换凭证
                </Button>
                <Button variant="outline" onClick={() => setVerifyOpen(true)} disabled={busy}>
                  凭证自检
                </Button>
                <Button
                  variant="outline"
                  onClick={() => {
                    setRevokeReason('');
                    setRevokeOpen(true);
                  }}
                  disabled={busy || detail.app.status === 'REVOKED'}
                >
                  <ShieldOff className="h-4 w-4" />
                  吊销应用
                </Button>
              </div>
            }
          >
            <div className="text-muted-foreground grid gap-1 text-sm">
              <span>说明：{detail.app.description || '（未填写）'}</span>
              <span>
                联系人：{detail.app.contactName || '（未填写）'} · {detail.app.contactEmail || '（未填写）'}
              </span>
              <span>创建于 {detail.app.createdAt}</span>
              {detail.app.revokedAt && (
                <span className="text-destructive">
                  已于 {detail.app.revokedAt} 吊销：{detail.app.revokeReason || '（未填写原因）'}
                </span>
              )}
            </div>
          </SectionCard>

          <SectionCard
            title="凭证"
            description="只显示摘要前缀与末四位。明文只在生成 / 轮换那一次返回，之后永远取不回。"
            extra={
              <Button variant="outline" onClick={() => void openDetail(detail.app.id)} disabled={busy}>
                刷新
              </Button>
            }
          >
            {detail.credentials.length === 0 ? (
              <EmptyState
                icon={<KeyRound className="h-6 w-6" />}
                title="还没有签发过凭证"
                description="签发之后，对接方才能用 API Key / Secret 调用对外接口。"
                action={<Button onClick={() => void onIssue()}>签发凭证</Button>}
              />
            ) : (
              <div className="space-y-2">
                {detail.credentials.map((c) => (
                  <div
                    key={c.id}
                    className="border-border flex flex-wrap items-center gap-3 rounded-md border px-3 py-2"
                  >
                    <span className="text-foreground font-medium">第 {c.version} 版</span>
                    <Badge className={toneClasses[c.status === 'ACTIVE' ? 'ok' : 'muted']}>
                      {c.statusLabel}
                    </Badge>
                    <span className="text-muted-foreground font-mono text-copy-12">
                      {c.apiKeyPrefix}… / …{c.secretHint}
                    </span>
                    <span className="text-muted-foreground text-copy-12">签发于 {c.issuedAt}</span>
                    {c.rotatedAt && (
                      <span className="text-muted-foreground text-copy-12">
                        轮换于 {c.rotatedAt}
                      </span>
                    )}
                    {c.lastUsedAt && (
                      <span className="text-muted-foreground text-copy-12">
                        最近使用 {c.lastUsedAt}
                      </span>
                    )}
                    {c.status === 'ACTIVE' && (
                      <Button
                        variant="outline"
                        className="ml-auto"
                        disabled={busy}
                        onClick={() =>
                          void run(
                            () => revokeCredential(detail.app.id, c.id, '手动吊销'),
                            '凭证已吊销。',
                          )
                        }
                      >
                        吊销
                      </Button>
                    )}
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          <div className="grid gap-4 lg:grid-cols-2">
            <SectionCard
              title="授权范围"
              description="这个对外应用能访问哪些资源与操作。与平台内部的权限码是两套东西。"
              extra={
                <Button variant="outline" onClick={() => setScopeOpen(true)} disabled={busy}>
                  授权
                </Button>
              }
            >
              {detail.scopes.length === 0 ? (
                <EmptyState
                  icon={<ShieldCheck className="h-6 w-6" />}
                  title="没有授权任何范围"
                  description="未配置授权范围 = 不限制。一个都没授权时，对接方调任何业务接口都会被拒。"
                />
              ) : (
                <div className="space-y-2">
                  {detail.scopes.map((s) => (
                    <div
                      key={s.id}
                      className="border-border flex items-center gap-3 rounded-md border px-3 py-2"
                    >
                      <span className="text-foreground font-mono text-sm">{s.resourceCode}</span>
                      <Badge className={toneClasses[s.actionCode === 'WRITE' ? 'warn' : 'ok']}>
                        {s.actionCodeLabel}
                      </Badge>
                      <span className="text-muted-foreground text-copy-12">授权于 {s.grantedAt}</span>
                      <Button
                        variant="outline"
                        className="ml-auto"
                        disabled={busy}
                        onClick={() => void run(() => revokeScope(detail.app.id, s.id), '授权已撤销。')}
                      >
                        撤销
                      </Button>
                    </div>
                  ))}
                </div>
              )}
            </SectionCard>

            <SectionCard
              title="IP 白名单"
              description="留空 = 不限制来源；配了条目之后才按条目匹配。"
              extra={
                <Button variant="outline" onClick={() => setIpOpen(true)} disabled={busy}>
                  新增
                </Button>
              }
            >
              {detail.ipAllowlist.length === 0 ? (
                <EmptyState
                  icon={<ShieldCheck className="h-6 w-6" />}
                  title="没有配置 IP 白名单"
                  description="未配置 = 不限制来源。如果想收紧来源，在这里加 CIDR。"
                />
              ) : (
                <div className="space-y-2">
                  {detail.ipAllowlist.map((e) => (
                    <div
                      key={e.id}
                      className="border-border flex items-center gap-3 rounded-md border px-3 py-2"
                    >
                      <span className="text-foreground font-mono text-sm">{e.cidr}</span>
                      <span className="text-muted-foreground text-copy-12">{e.note}</span>
                      <Button
                        variant="outline"
                        className="ml-auto"
                        disabled={busy}
                        onClick={() =>
                          void run(() => removeIpAllowlist(detail.app.id, e.id), '白名单条目已删除。')
                        }
                      >
                        删除
                      </Button>
                    </div>
                  ))}
                </div>
              )}
            </SectionCard>
          </div>

          <SectionCard
            title="调用配额"
            description="按窗口类型设置上限。未配置的窗口 = 不限，不是限 0 次。"
            extra={
              <Button variant="outline" onClick={() => setQuotaOpen(true)} disabled={busy}>
                设置配额
              </Button>
            }
          >
            <div className="space-y-2">
              {quotaStatus.map((s) => {
                const tone = quotaTone(s);
                return (
                  <div
                    key={s.windowType}
                    className="border-border flex flex-wrap items-center gap-3 rounded-md border px-3 py-2"
                  >
                    <span className="text-foreground font-medium">{s.windowTypeLabel}</span>
                    <Badge className={toneClasses[tone]}>{quotaLabel(s)}</Badge>
                    <span className="text-muted-foreground text-copy-12">
                      {s.configured
                        ? `上限 ${s.limitCount} · 已用 ${s.usedCount} · 剩余 ${s.remainingCount ?? 0}`
                        : '未配置（不限）'}
                    </span>
                    {s.windowStartedAt && (
                      <span className="text-muted-foreground text-copy-12">
                        当前窗口 {s.windowStartedAt} 起
                      </span>
                    )}
                  </div>
                );
              })}
            </div>
          </SectionCard>

          <SectionCard title="审计留痕" description="只追加，不可改不可删。读取凭证档案这件事本身也会留痕。">
            {audit && audit.items.length > 0 ? (
              <div className="space-y-1">
                {audit.items.map((e) => (
                  <div key={e.id} className="text-copy-12 flex flex-wrap items-center gap-2">
                    <span className="text-muted-foreground font-mono">{e.createdAt}</span>
                    <Badge className={toneClasses.muted}>{e.eventTypeLabel}</Badge>
                    <span className="text-foreground">{e.detail}</span>
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState title="还没有审计记录" description="每次写动作都会在这里留下一行。" />
            )}
          </SectionCard>
        </>
      )}

      {capabilities && (
        <SectionCard title="能力声明" description="哪些真接通、哪些还没交付、归谁。">
          <div className="space-y-2">
            {capabilities.capabilities.map((c) => (
              <div key={c.name} className="flex flex-wrap items-center gap-2 text-sm">
                <Badge className={toneClasses[c.available ? 'ok' : 'muted']}>
                  {c.available ? '已交付' : '未接通'}
                </Badge>
                <span className="text-foreground font-medium">{c.name}</span>
                <span className="text-muted-foreground">{c.basis}</span>
                {!c.available && c.impact && (
                  <span className="text-warning-foreground flex items-center gap-1">
                    <TriangleAlert className="h-3.5 w-3.5" />
                    {c.impact}
                  </span>
                )}
              </div>
            ))}
            {capabilities.downstreamGaps.map((g) => (
              <div key={g.module} className="text-muted-foreground flex flex-wrap items-center gap-2 text-sm">
                <Badge className={toneClasses.muted}>{g.module}</Badge>
                <span>{g.name}</span>
                <span>{g.detail}</span>
              </div>
            ))}
          </div>
        </SectionCard>
      )}

      {/* ============================================================ 注册应用 */}
      <Modal open={appOpen} onOpenChange={setAppOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>注册应用</ModalTitle>
            <ModalDescription>
              应用标识（appCode）是对接方配置里用的稳定标识，租户内唯一，一经创建不建议改。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <Input
              placeholder="应用标识，例如 erp-gateway"
              value={appForm.appCode}
              onChange={(e) => setAppForm({ ...appForm, appCode: e.target.value })}
            />
            <Input
              placeholder="应用名称"
              value={appForm.appName}
              onChange={(e) => setAppForm({ ...appForm, appName: e.target.value })}
            />
            <select
              className="border-border bg-background h-9 w-full rounded-md border px-2 text-sm"
              value={appForm.appType}
              onChange={(e) => setAppForm({ ...appForm, appType: e.target.value })}
            >
              {(options?.appTypes ?? []).map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label} —— {o.basis}
                </option>
              ))}
            </select>
            <Input
              placeholder="说明（可空）"
              value={appForm.description}
              onChange={(e) => setAppForm({ ...appForm, description: e.target.value })}
            />
            <Input
              placeholder="联系人（可空）"
              value={appForm.contactName}
              onChange={(e) => setAppForm({ ...appForm, contactName: e.target.value })}
            />
            <Input
              placeholder="联系邮箱（可空）"
              value={appForm.contactEmail}
              onChange={(e) => setAppForm({ ...appForm, contactEmail: e.target.value })}
            />
          </div>
          <ModalFooter>
            <Button variant="outline" onClick={() => setAppOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void onRegister()} disabled={busy}>
              注册
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ============================================================ 明文只此一次 */}
      <Modal open={issuedOpen} onOpenChange={setIssuedOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>这是唯一一次显示明文</ModalTitle>
            <ModalDescription>{issued?.notice}</ModalDescription>
          </ModalHeader>
          {issued && (
            <div className="space-y-3 text-sm">
              <div>
                <div className="text-muted-foreground text-copy-12">API Key</div>
                <code className="bg-muted block break-all rounded p-2 font-mono">{issued.apiKey}</code>
              </div>
              <div>
                <div className="text-muted-foreground text-copy-12">Secret</div>
                <code className="bg-muted block break-all rounded p-2 font-mono">{issued.secret}</code>
              </div>
              <div className="text-muted-foreground text-copy-12">
                掩码预览：{maskSecret(issued.apiKey)} / {maskSecret(issued.secret)}
              </div>
            </div>
          )}
          <ModalFooter>
            <Button onClick={() => setIssuedOpen(false)}>我已保存</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ============================================================ 授权范围 */}
      <Modal open={scopeOpen} onOpenChange={setScopeOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>授权范围</ModalTitle>
            <ModalDescription>
              资源码是「对外」的业务资源（如 order / product），与平台内部的权限码不是一回事。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <Input
              placeholder="资源码，例如 order"
              list="integration-scope-resources"
              value={scopeForm.resourceCode}
              onChange={(e) => setScopeForm({ ...scopeForm, resourceCode: e.target.value })}
            />
            <datalist id="integration-scope-resources">
              {(options?.scopeResources ?? []).map((r) => (
                <option key={r} value={r} />
              ))}
            </datalist>
            <select
              className="border-border bg-background h-9 w-full rounded-md border px-2 text-sm"
              value={scopeForm.actionCode}
              onChange={(e) => setScopeForm({ ...scopeForm, actionCode: e.target.value })}
            >
              {(options?.scopeActions ?? []).map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label} —— {o.basis}
                </option>
              ))}
            </select>
          </div>
          <ModalFooter>
            <Button variant="outline" onClick={() => setScopeOpen(false)}>
              取消
            </Button>
            <Button
              disabled={busy}
              onClick={() => {
                if (!detail) return;
                void run(
                  () =>
                    grantScope(detail.app.id, {
                      resourceCode: scopeForm.resourceCode,
                      actionCode: scopeForm.actionCode,
                    }),
                  '授权已生效。',
                ).then((ok) => {
                  if (ok) setScopeOpen(false);
                });
              }}
            >
              授权
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ============================================================ IP 白名单 */}
      <Modal open={ipOpen} onOpenChange={setIpOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>新增 IP 白名单</ModalTitle>
            <ModalDescription>
              支持 IPv4 与 IPv6 的 CIDR，例如 203.0.113.0/24 或 2001:db8::/32。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <Input
              placeholder="CIDR"
              value={ipForm.cidr}
              onChange={(e) => setIpForm({ ...ipForm, cidr: e.target.value })}
            />
            <Input
              placeholder="备注（可空）"
              value={ipForm.note}
              onChange={(e) => setIpForm({ ...ipForm, note: e.target.value })}
            />
          </div>
          <ModalFooter>
            <Button variant="outline" onClick={() => setIpOpen(false)}>
              取消
            </Button>
            <Button
              disabled={busy}
              onClick={() => {
                if (!detail) return;
                void run(
                  () => addIpAllowlist(detail.app.id, { cidr: ipForm.cidr, note: ipForm.note }),
                  '白名单条目已新增。',
                ).then((ok) => {
                  if (ok) setIpOpen(false);
                });
              }}
            >
              新增
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ============================================================ 配额 */}
      <Modal open={quotaOpen} onOpenChange={setQuotaOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>设置调用配额</ModalTitle>
            <ModalDescription>
              同一窗口类型重复设置 = 覆盖，不是叠加。窗口按 UTC 切分。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <select
              className="border-border bg-background h-9 w-full rounded-md border px-2 text-sm"
              value={quotaForm.windowType}
              onChange={(e) => setQuotaForm({ ...quotaForm, windowType: e.target.value })}
            >
              {(options?.quotaWindows ?? []).map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label} —— {o.basis}
                </option>
              ))}
            </select>
            <Input
              placeholder="上限（次数）"
              inputMode="decimal"
              value={quotaForm.limitCount}
              onChange={(e) => setQuotaForm({ ...quotaForm, limitCount: e.target.value })}
            />
          </div>
          <ModalFooter>
            <Button variant="outline" onClick={() => setQuotaOpen(false)}>
              取消
            </Button>
            <Button
              disabled={busy}
              onClick={() => {
                if (!detail) return;
                void run(
                  () =>
                    setQuota(detail.app.id, {
                      windowType: quotaForm.windowType,
                      limitCount: Number(quotaForm.limitCount),
                      enabled: quotaForm.enabled,
                    }),
                  '配额已更新。',
                ).then((ok) => {
                  if (ok) setQuotaOpen(false);
                });
              }}
            >
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ============================================================ 凭证自检 */}
      <Modal open={verifyOpen} onOpenChange={setVerifyOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>凭证自检</ModalTitle>
            <ModalDescription>
              对接方来问「到底哪里不通」时，运营当场验一遍。四个判定分开给。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <Input
              placeholder="API Key"
              value={verifyForm.apiKey}
              onChange={(e) => setVerifyForm({ ...verifyForm, apiKey: e.target.value })}
            />
            <Input
              placeholder="Secret"
              value={verifyForm.secret}
              onChange={(e) => setVerifyForm({ ...verifyForm, secret: e.target.value })}
            />
            <Input
              placeholder="调用方 IP（可空）"
              value={verifyForm.clientIp}
              onChange={(e) => setVerifyForm({ ...verifyForm, clientIp: e.target.value })}
            />
            <Input
              placeholder="要访问的资源码（可空，例如 order）"
              value={verifyForm.resourceCode}
              onChange={(e) => setVerifyForm({ ...verifyForm, resourceCode: e.target.value })}
            />
            <select
              className="border-border bg-background h-9 w-full rounded-md border px-2 text-sm"
              value={verifyForm.actionCode}
              onChange={(e) => setVerifyForm({ ...verifyForm, actionCode: e.target.value })}
            >
              {(options?.scopeActions ?? []).map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>
          {verifyResult && (
            <div className="space-y-2 text-sm">
              <div className="font-medium">{verifyConclusion(verifyResult)}</div>
              <div className="text-muted-foreground">
                凭证：{verifyResult.credentialMatched ? '匹配' : `不匹配（${verifyResult.credentialStatus}）`} ·
                授权范围：{verifyResult.scopeChecked ? (verifyResult.scopeGranted ? '允许' : '不允许') : '未判定'} ·
                IP 白名单：{verifyResult.ipChecked ? (verifyResult.ipAllowed ? '允许' : '不允许') : '未判定'} ·
                配额：{verifyResult.quotaExceeded ? '已超限' : '未超限'}
              </div>
              {verifyResult.note && (
                <div className="text-muted-foreground">{verifyResult.note}</div>
              )}
            </div>
          )}
          <ModalFooter>
            <Button variant="outline" onClick={() => setVerifyOpen(false)}>
              关闭
            </Button>
            <Button onClick={() => void onVerify()} disabled={busy}>
              自检
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ============================================================ 吊销应用 */}
      <Modal open={revokeOpen} onOpenChange={setRevokeOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>吊销应用</ModalTitle>
            <ModalDescription>
              吊销是「一键」的：该应用全部凭证同时失效。应用档案与审计留痕会保留（应用不能被删除）。
            </ModalDescription>
          </ModalHeader>
          <Input
            placeholder="吊销原因（会写进审计留痕）"
            value={revokeReason}
            onChange={(e) => setRevokeReason(e.target.value)}
          />
          <ModalFooter>
            <Button variant="outline" onClick={() => setRevokeOpen(false)}>
              取消
            </Button>
            <Button
              disabled={busy}
              onClick={() => {
                if (!detail) return;
                void run(() => revokeApp(detail.app.id, revokeReason), '应用已吊销，全部凭证同时失效。').then(
                  (ok) => {
                    if (ok) setRevokeOpen(false);
                  },
                );
              }}
            >
              确认吊销
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
