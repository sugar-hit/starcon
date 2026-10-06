'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import {
  AlertTriangle,
  CheckCircle2,
  Globe2,
  Link2,
  Plus,
  RefreshCw,
  RefreshCcw,
  Search,
  Send,
  Store,
} from 'lucide-react';
import type {
  ConnectionView,
  CredentialRotateInput,
  PlatformOperationView,
  PlatformView,
  ReauthorizationReminderView,
  ShopInput,
  ShopView,
  SyncCursorView,
  SyncFailureSummary,
  SyncFailureView,
  SyncRunView,
} from '../../../../../packages/api-contracts/src/catalog';
import { api, ApiError } from '@/lib/api';
import { useIdentity } from '../iam/identity-shell';

type Section = 'platforms' | 'connections' | 'shops' | 'sync' | 'operations';

const sections: readonly { id: Section; label: string; permission: string; icon: typeof Globe2 }[] = [
  { id: 'shops', label: '店铺与站点', permission: 'channel.shop.read', icon: Store },
  { id: 'connections', label: '授权连接', permission: 'channel.connection.read', icon: Link2 },
  { id: 'sync', label: '同步运行', permission: 'channel.sync.read', icon: RefreshCw },
  { id: 'operations', label: '平台回写', permission: 'channel.operation.read', icon: Send },
  { id: 'platforms', label: '平台目录', permission: 'channel.registry.read', icon: Globe2 },
];

const operatingModes: readonly { value: string; label: string; hint: string }[] = [
  { value: 'SELF_FULFILLED', label: '自发货 FBM', hint: '自有库存发货，成本与库存全部进自己账' },
  { value: 'FULL_CONSIGNMENT', label: '全托管', hint: '平台负责仓配与售后，货权与结算口径不同' },
  { value: 'SEMI_CONSIGNMENT', label: '半托管', hint: '平台流量与结算，履约责任部分自担' },
  { value: 'OVERSEAS_WAREHOUSE', label: '海外仓', hint: '货已到海外仓，履约成本含尾程与关税' },
  { value: 'LOCAL', label: '本土店', hint: '当地发货，当日达为主' },
];

const statusTone: Record<string, 'success' | 'warning' | 'danger' | 'neutral'> = {
  ACTIVE: 'success',
  EXPIRING: 'warning',
  EXPIRED: 'danger',
  BROKEN: 'danger',
  SUCCEEDED: 'success',
  PARTIAL: 'warning',
  FAILED: 'danger',
  RUNNING: 'warning',
  UNKNOWN: 'danger',
  CONFIRMED_APPLIED: 'success',
  STILL_UNKNOWN: 'danger',
  REVOKED: 'neutral',
  SUSPENDED: 'warning',
  ARCHIVED: 'neutral',
};

const statusLabel: Record<string, string> = {
  ACTIVE: '正常',
  EXPIRING: '即将到期',
  EXPIRED: '已失效',
  // 02.4 同步运行
  PAUSED: '已暂停',
  BROKEN: '游标异常',
  RUNNING: '运行中',
  SUCCEEDED: '成功',
  PARTIAL: '部分失败',
  FAILED: '失败',
  CANCELLED: '已取消',
  OPEN: '待处理',
  REPLAYED: '已重放',
  RESOLVED: '已解决',
  IGNORED: '已忽略',
  // 02.5 平台回写
  PENDING: '待发送',
  SENDING: '发送中',
  UNKNOWN: '结果未知',
  NOT_REQUIRED: '无需核验',
  CONFIRMED_APPLIED: '已确认执行',
  CONFIRMED_NOT_APPLIED: '已确认未执行',
  STILL_UNKNOWN: '仍不确定',
  REVOKED: '已撤销',
  SUSPENDED: '已停用',
  ARCHIVED: '已归档',
  AVAILABLE: '可用',
  BETA: '灰度',
  PLANNED: '规划中',
};

export function ChannelWorkspace() {
  const { session } = useIdentity();
  const [section, setSection] = useState<Section>('shops');
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [search, setSearch] = useState('');
  const [platforms, setPlatforms] = useState<PlatformView[]>([]);
  const [connections, setConnections] = useState<ConnectionView[]>([]);
  const [reminders, setReminders] = useState<ReauthorizationReminderView[]>([]);
  const [shops, setShops] = useState<ShopView[]>([]);
  const [detail, setDetail] = useState<ShopView | null>(null);
  const [cursors, setCursors] = useState<SyncCursorView[]>([]);
  const [failures, setFailures] = useState<SyncFailureView[]>([]);
  const [failureSummary, setFailureSummary] = useState<SyncFailureSummary[]>([]);
  const [runs, setRuns] = useState<SyncRunView[]>([]);
  const [operations, setOperations] = useState<PlatformOperationView[]>([]);
  const [runCursorId, setRunCursorId] = useState<string | null>(null);

  const can = useCallback(
    (permission: string) => session.permissions.includes(permission),
    [session.permissions],
  );

  const handleError = useCallback((e: unknown) => {
    setError(e instanceof Error ? e.message : '请求失败，请重试');
  }, []);

  const load = useCallback(async () => {
    setError('');
    setNotice('');
    setDetail(null);
    try {
      if (can('channel.registry.read')) setPlatforms(await api<PlatformView[]>('channel/platforms'));
      if (can('channel.connection.read')) {
        setConnections(await api<ConnectionView[]>('channel/connections'));
        setReminders(await api<ReauthorizationReminderView[]>('channel/connections/reminders?withinDays=7'));
      }
      if (can('channel.shop.read')) setShops(await api<ShopView[]>('channel/shops'));
      if (can('channel.sync.read')) {
        setCursors(await api<SyncCursorView[]>('channel/sync-cursors'));
        setFailures(await api<SyncFailureView[]>('channel/sync-failures?limit=50'));
        setFailureSummary(
          await api<SyncFailureSummary[]>('channel/sync-failures/summary'),
        );
        setRuns(await api<SyncRunView[]>(
          `channel/sync-runs?limit=30${runCursorId ? `&cursorId=${runCursorId}` : ''}`,
        ));
      }
      if (can('channel.operation.read'))
        setOperations(await api<PlatformOperationView[]>('channel/platform-operations?limit=50'));
    } catch (e) {
      handleError(e);
    } finally {
      setReady(true);
    }
  }, [can, handleError, runCursorId]);

  useEffect(() => {
    void load();
  }, [load]);

  const marketplaceOf = useCallback(
    (platformCode: string) => platforms.find((p) => p.code === platformCode)?.marketplaces ?? [],
    [platforms],
  );

  const visibleSections = useMemo(
    () => sections.filter((s) => can(s.permission)),
    [can],
  );

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在连接渠道与店铺…
      </p>
    );

  const term = search.trim().toLowerCase();

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div role="tablist" aria-label="渠道与店铺分区" className="flex flex-wrap gap-1">
          {visibleSections.map((s) => (
            <button
              key={s.id}
              role="tab"
              type="button"
              aria-selected={section === s.id}
              onClick={() => {
                setSection(s.id);
                setDetail(null);
              }}
              className={
                section === s.id
                  ? 'bg-muted text-copy-13 flex h-8 items-center gap-2 rounded-md px-3 font-medium'
                  : 'text-muted-foreground hover:bg-muted text-copy-13 flex h-8 items-center gap-2 rounded-md px-3'
              }
            >
              <s.icon className="h-4 w-4" />
              {s.label}
            </button>
          ))}
        </div>
        <div className="flex items-center gap-2">
          <label className="relative flex items-center">
            <Search className="text-muted-foreground pointer-events-none absolute left-2.5 h-4 w-4" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="搜索"
              aria-label="搜索"
              className="border-border bg-card text-copy-13 focus-visible:border-ring h-8 w-56 rounded-md border pl-8 pr-2 outline-none"
            />
          </label>
          <Button variant="ghost" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
        </div>
      </div>

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive flex items-center gap-2 rounded-md border p-3 text-sm"
        >
          {error}
          <Button variant="link" onClick={() => void load()}>
            重试
          </Button>
        </div>
      )}
      {notice && (
        <p role="status" className="text-success text-sm">
          {notice}
        </p>
      )}

      {reminders.length > 0 && (
        <div className="border-warning/40 bg-warning/5 rounded-md border p-3">
          <p className="text-copy-14 flex items-center gap-2 font-medium">
            <AlertTriangle className="text-warning h-4 w-4" />
            {reminders.length} 个连接需要重新授权
          </p>
          <ul className="mt-2 space-y-1">
            {reminders.map((r) => (
              <li key={r.connectionId} className="text-copy-13 text-muted-foreground flex items-center gap-2">
                <Badge tone={r.severity === 'CRITICAL' ? 'danger' : 'warning'}>
                  {r.severity === 'CRITICAL' ? '紧急' : '待处理'}
                </Badge>
                {r.platformCode} · {r.authorizedAccount} · 还有 {r.daysUntilExpiry} 天到期
              </li>
            ))}
          </ul>
        </div>
      )}

      {section === 'sync' && (
        <SyncRuntime
          cursors={cursors}
          failures={failures}
          summary={failureSummary}
          runs={runs}
          runCursorId={runCursorId}
          onSelectRun={setRunCursorId}
          can={can}
          busy={busy}
          setBusy={setBusy}
          onDone={load}
          onError={handleError}
          onNotice={setNotice}
        />
      )}
      {section === 'operations' && (
        <OperationQueue
          operations={operations}
          can={can}
          busy={busy}
          setBusy={setBusy}
          onDone={load}
          onError={handleError}
          onNotice={setNotice}
        />
      )}
      {section === 'platforms' && (
        <PlatformDirectory platforms={platforms} term={term} />
      )}
      {section === 'connections' && (
        <ConnectionList
          connections={connections}
          term={term}
          can={can}
          platforms={platforms}
          busy={busy}
          setBusy={setBusy}
          onDone={load}
          onError={handleError}
          onNotice={setNotice}
        />
      )}
      {section === 'shops' &&
        (detail ? (
          <ShopDetail
            shop={detail}
            platforms={platforms}
            can={can}
            busy={busy}
            setBusy={setBusy}
            onBack={() => setDetail(null)}
            onDone={load}
            onError={handleError}
            onNotice={setNotice}
          />
        ) : (
          <ShopList
            shops={shops}
            term={term}
            can={can}
            platforms={platforms}
            busy={busy}
            setBusy={setBusy}
            onOpen={setDetail}
            onDone={load}
            onError={handleError}
            onNotice={setNotice}
          />
        ))}
    </div>
  );
}

/** 2.1.1.1 平台目录 / 2.1.1.2 能力矩阵 / 2.1.1.3 站点与国家参数 */
function PlatformDirectory({ platforms, term }: { platforms: PlatformView[]; term: string }) {
  const filtered = platforms.filter((p) => `${p.code} ${p.name}`.toLowerCase().includes(term));
  if (filtered.length === 0) return <Empty text="没有匹配的平台" />;
  return (
    <ul className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
      {filtered.map((p) => (
        <li key={p.id} className="bg-card rounded-lg border p-4">
          <div className="flex items-start justify-between gap-2">
            <div>
              <p className="text-copy-15 font-medium">{p.name}</p>
              <p className="text-copy-12 text-muted-foreground">{p.code}</p>
            </div>
            <Badge tone={statusTone[p.supportStatus] ?? 'neutral'}>
              {statusLabel[p.supportStatus] ?? p.supportStatus}
            </Badge>
          </div>
          <p className="text-copy-12 text-muted-foreground mt-2">
            接入方式：{p.integration === 'OFFICIAL_API' ? '官方 API' : p.integration === 'THIRD_PARTY_ISV' ? '第三方 ISV' : '中介服务'}
          </p>
          {p.capabilities.length > 0 && (
            <div className="mt-3 flex flex-wrap gap-1">
              {p.capabilities.map((c) => (
                <Badge key={c} tone="neutral">
                  {capabilityLabel(c)}
                </Badge>
              ))}
            </div>
          )}
          {p.marketplaces.length > 0 && (
            <p className="text-copy-12 text-muted-foreground mt-3">
              站点 {p.marketplaces.length} 个：
              {p.marketplaces
                .slice(0, 4)
                .map((m) => m.defaultCurrency)
                .filter((v, i, a) => a.indexOf(v) === i)
                .join(' / ')}
            </p>
          )}
        </li>
      ))}
    </ul>
  );
}

const capabilityLabels: Record<string, string> = {
  multi_channel_delivery: '多渠道配送',
  inventory_write_back: '库存回写',
  return_authorization: '自发退货单',
  label_print: '面单打印',
  delayed_settlement: '延迟结算',
  fba_fulfillment: 'FBA 履约',
  creator_attribution: '达人归因',
  full_consignment: '全托管',
};

function capabilityLabel(code: string) {
  return capabilityLabels[code] ?? code;
}

/** 2.2.1.1 连接列表 / 2.2.2.1 健康诊断 / 2.2.1.3 凭据轮换 */
function ConnectionList({
  connections,
  term,
  can,
  platforms,
  busy,
  setBusy,
  onDone,
  onError,
  onNotice,
}: {
  connections: ConnectionView[];
  term: string;
  can: (p: string) => boolean;
  platforms: PlatformView[];
  busy: boolean;
  setBusy: (v: boolean) => void;
  onDone: () => Promise<void>;
  onError: (e: unknown) => void;
  onNotice: (v: string) => void;
}) {
  const [creating, setCreating] = useState(false);
  const [rotating, setRotating] = useState<ConnectionView | null>(null);
  const [health, setHealth] = useState<{ id: string; diagnosis: string; recommendation: string; canSync: boolean } | null>(null);

  const filtered = connections.filter((c) =>
    `${c.displayName} ${c.authorizedAccount} ${c.platformName}`.toLowerCase().includes(term),
  );

  async function diagnose(c: ConnectionView) {
    try {
      setHealth(null);
      const h = await api<{ connectionId: string; diagnosis: string; recommendation: string; canSync: boolean }>(
        `channel/connections/${c.id}/health`,
      );
      setHealth({ id: h.connectionId, diagnosis: h.diagnosis, recommendation: h.recommendation, canSync: h.canSync });
    } catch (e) {
      onError(e);
    }
  }

  async function revoke(c: ConnectionView) {
    if (!window.confirm(`断开「${c.displayName || c.authorizedAccount}」后同步将停止，历史数据仍可查看。继续？`))
      return;
    setBusy(true);
    try {
      await api(`channel/connections/${c.id}/revoke?version=${c.version}`, 'POST');
      onNotice('连接已断开，同步已停止');
      await onDone();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  if (!can('channel.connection.create') && !creating) {
    return filtered.length === 0 ? (
      <Empty text="还没有授权连接" />
    ) : (
      <ul className="space-y-2">
        {filtered.map((c) => (
          <li key={c.id} className="bg-card rounded-md border p-3">
            <ConnectionRow c={c} onDiagnose={() => void diagnose(c)} />
          </li>
        ))}
      </ul>
    );
  }

  return (
    <div className="space-y-3">
      {creating && (
        <ConnectionForm
          platforms={platforms}
          busy={busy}
          onCancel={() => setCreating(false)}
          onSubmit={async (input) => {
            setBusy(true);
            try {
              await api('channel/connections', 'POST', input);
              setCreating(false);
              onNotice('连接已创建，接下来请完成授权并写入凭据引用');
              await onDone();
            } catch (e) {
              onError(e);
            } finally {
              setBusy(false);
            }
          }}
        />
      )}
      {filtered.length === 0 && !creating ? (
        <Empty text="还没有授权连接" />
      ) : (
        <ul className="space-y-2">
          {filtered.map((c) => (
            <li key={c.id} className="bg-card rounded-md border p-3">
              <ConnectionRow
                c={c}
                onDiagnose={() => void diagnose(c)}
                actions={
                  <>
                    {can('channel.connection.update') && (
                      <>
                        <Button size="sm" variant="ghost" onClick={() => setRotating(c)}>
                          轮换凭据
                        </Button>
                        <Button size="sm" variant="ghost" disabled={busy} onClick={() => void revoke(c)}>
                          断开
                        </Button>
                      </>
                    )}
                  </>
                }
              />
              {health?.id === c.id && (
                <div className="border-border bg-muted/40 mt-3 rounded-md border p-3">
                  <p className="text-copy-13 flex items-center gap-2">
                    {health.canSync ? (
                      <CheckCircle2 className="text-success h-4 w-4" />
                    ) : (
                      <AlertTriangle className="text-warning h-4 w-4" />
                    )}
                    {health.diagnosis}
                  </p>
                  <p className="text-copy-12 text-muted-foreground mt-1">{health.recommendation}</p>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {!creating && (
        <Button size="sm" onClick={() => setCreating(true)}>
          <Plus className="h-4 w-4" />
          新建连接
        </Button>
      )}
      {rotating && (
        <CredentialForm
          busy={busy}
          onCancel={() => setRotating(null)}
          onSubmit={async (input: CredentialRotateInput) => {
            setBusy(true);
            try {
              await api(`channel/connections/${rotating.id}/credential`, 'PUT', input);
              setRotating(null);
              onNotice('凭据引用已更新');
              await onDone();
            } catch (e) {
              onError(e);
            } finally {
              setBusy(false);
            }
          }}
        />
      )}
    </div>
  );
}

function ConnectionRow({
  c,
  onDiagnose,
  actions,
}: {
  c: ConnectionView;
  onDiagnose: () => void;
  actions?: React.ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div className="min-w-0">
        <p className="text-copy-14 flex items-center gap-2 font-medium">
          {c.displayName || c.authorizedAccount}
          <Badge tone={statusTone[c.status] ?? 'neutral'}>{statusLabel[c.status] ?? c.status}</Badge>
          {c.syncPaused && <Badge tone="neutral">同步已暂停</Badge>}
        </p>
        <p className="text-copy-12 text-muted-foreground mt-1 break-all">
          {c.platformName} · {c.authorizedAccount} · 店铺 {c.shopCount} 个
          {c.credential ? ` · 凭据 ${statusLabel[c.credential.status] ?? c.credential.status}` : ' · 未配置凭据'}
        </p>
      </div>
      <div className="flex items-center gap-2">
        <Button size="sm" variant="ghost" onClick={onDiagnose}>
          诊断
        </Button>
        {actions}
      </div>
    </div>
  );
}

function ConnectionForm({
  platforms,
  busy,
  onCancel,
  onSubmit,
}: {
  platforms: PlatformView[];
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: { platformCode: string; authorizedAccount: string; displayName: string }) => Promise<void>;
}) {
  const [platformCode, setPlatformCode] = useState(platforms[0]?.code ?? '');
  const [authorizedAccount, setAuthorizedAccount] = useState('');
  const [displayName, setDisplayName] = useState('');
  const available = platforms.filter((p) => p.supportStatus === 'AVAILABLE');
  return (
    <div className="bg-card space-y-3 rounded-md border p-4">
      <p className="text-copy-14 font-medium">新建连接</p>
      <div className="grid gap-3 md:grid-cols-3">
        <Field label="平台">
          <select
            value={platformCode}
            onChange={(e) => setPlatformCode(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
          >
            {(available.length > 0 ? available : platforms).map((p) => (
              <option key={p.id} value={p.code}>
                {p.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="平台账号" hint="邮箱或店铺账号">
          <input
            value={authorizedAccount}
            onChange={(e) => setAuthorizedAccount(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
          />
        </Field>
        <Field label="备注名称" hint="便于团队识别">
          <input
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
          />
        </Field>
      </div>
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={busy || !platformCode || !authorizedAccount}
          onClick={() => void onSubmit({ platformCode, authorizedAccount, displayName })}
        >
          创建
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          取消
        </Button>
      </div>
    </div>
  );
}

function CredentialForm({
  busy,
  onCancel,
  onSubmit,
}: {
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: CredentialRotateInput) => Promise<void>;
}) {
  const [externalRef, setExternalRef] = useState('');
  const [grantedScopes, setGrantedScopes] = useState('orders inventory listings');
  const [days, setDays] = useState('180');
  const save = () =>
    onSubmit({
      externalRef,
      grantedScopes,
      authorizedAt: new Date().toISOString(),
      expiresAt: new Date(Date.now() + Number(days || 0) * 86400000).toISOString(),
    });
  return (
    <div className="bg-card space-y-3 rounded-md border p-4">
      <p className="text-copy-14 font-medium">写入凭据引用</p>
      <p className="text-copy-12 text-muted-foreground">
        令牌本身写在密钥管理器里，这里只登记引用位置。starcon 不会保存、也不会显示明文令牌。
      </p>
      <div className="grid gap-3 md:grid-cols-3">
        <Field label="密钥管理器引用" hint="例如 vault://starcon/amazon/main">
          <input
            value={externalRef}
            onChange={(e) => setExternalRef(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
          />
        </Field>
        <Field label="授权范围" hint="空格分隔">
          <input
            value={grantedScopes}
            onChange={(e) => setGrantedScopes(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
          />
        </Field>
        <Field label="有效期（天）" hint="到期前 7 天会进入提醒">
          <input
            type="number"
            min={0}
            value={days}
            onChange={(e) => setDays(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
          />
        </Field>
      </div>
      <div className="flex gap-2">
        <Button size="sm" disabled={busy || !externalRef} onClick={() => void save()}>
          保存引用
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          取消
        </Button>
      </div>
    </div>
  );
}

/** 2.3.1.1 店铺列表 / 2.3.2.1 经营模式 */
function ShopList({
  shops,
  term,
  can,
  platforms,
  busy,
  setBusy,
  onOpen,
  onDone,
  onError,
  onNotice,
}: {
  shops: ShopView[];
  term: string;
  can: (p: string) => boolean;
  platforms: PlatformView[];
  busy: boolean;
  setBusy: (v: boolean) => void;
  onOpen: (s: ShopView) => void;
  onDone: () => Promise<void>;
  onError: (e: unknown) => void;
  onNotice: (v: string) => void;
}) {
  const [creating, setCreating] = useState(false);
  const filtered = shops.filter((s) => `${s.code} ${s.name} ${s.platformName}`.toLowerCase().includes(term));

  async function remove(s: ShopView) {
    if (!window.confirm(`删除店铺「${s.name}」？此操作不可撤销。`)) return;
    setBusy(true);
    try {
      await api(`channel/shops/${s.id}?version=${s.version}`, 'DELETE');
      onNotice('店铺已删除');
      await onDone();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-3">
      {creating && (
        <ShopForm
          platforms={platforms}
          connections={[]}
          busy={busy}
          onCancel={() => setCreating(false)}
          onSubmit={async (input: ShopInput) => {
            setBusy(true);
            try {
              await api('channel/shops', 'POST', input);
              setCreating(false);
              onNotice('店铺已创建');
              await onDone();
            } catch (e) {
              onError(e);
            } finally {
              setBusy(false);
            }
          }}
        />
      )}
      {filtered.length === 0 && !creating ? (
        <Empty text="还没有店铺" />
      ) : (
        <ul className="space-y-2">
          {filtered.map((s) => (
            <li key={s.id} className="bg-card rounded-md border p-3">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="min-w-0">
                  <p className="text-copy-14 flex items-center gap-2 font-medium">
                    {s.name}
                    <Badge tone={statusTone[s.status] ?? 'neutral'}>{statusLabel[s.status] ?? s.status}</Badge>
                    <Badge tone="neutral">{modeLabel(s.operatingMode)}</Badge>
                  </p>
                  <p className="text-copy-12 text-muted-foreground mt-1 break-all">
                    {s.platformName} · {s.code} · 启用站点 {s.marketplaceCount} 个
                    {s.connectionAccount ? ` · 连接 ${s.connectionAccount}` : ' · 未绑定连接'}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <Button size="sm" variant="ghost" onClick={() => onOpen(s)}>
                    详情
                  </Button>
                  {can('channel.shop.delete') && (
                    <Button size="sm" variant="ghost" disabled={busy} onClick={() => void remove(s)}>
                      删除
                    </Button>
                  )}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
      {can('channel.shop.create') && !creating && (
        <Button size="sm" onClick={() => setCreating(true)}>
          <Plus className="h-4 w-4" />
          新建店铺
        </Button>
      )}
    </div>
  );
}

/** 2.3.1.2 店铺详情：启用站点、编辑基础信息 */
function ShopDetail({
  shop,
  platforms,
  can,
  busy,
  setBusy,
  onBack,
  onDone,
  onError,
  onNotice,
}: {
  shop: ShopView;
  platforms: PlatformView[];
  can: (p: string) => boolean;
  busy: boolean;
  setBusy: (v: boolean) => void;
  onBack: () => void;
  onDone: () => Promise<void>;
  onError: (e: unknown) => void;
  onNotice: (v: string) => void;
}) {
  const editable = can('channel.shop.update');
  const available = platforms.find((p) => p.code === shop.platformCode)?.marketplaces ?? [];
  const enabled = new Set(shop.marketplaces.map((m) => m.marketplaceId));

  async function toggleMarketplace(code: string, isOn: boolean) {
    setBusy(true);
    try {
      if (isOn) await api(`channel/shops/${shop.id}/marketplaces`, 'POST', { marketplaceCode: code, status: 'ACTIVE' });
      else
        await api(
          `channel/shops/${shop.id}/marketplaces/${available.find((m) => m.code === code)?.id ?? ''}`,
          'DELETE',
        );
      onNotice(isOn ? `已启用站点 ${code}` : `已停用站点 ${code}`);
      await onDone();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <p className="text-copy-16 font-medium">{shop.name}</p>
          <p className="text-copy-12 text-muted-foreground mt-1">
            {shop.platformName} · {shop.code} · {modeLabel(shop.operatingMode)}
          </p>
        </div>
        <Button size="sm" variant="ghost" onClick={onBack}>
          返回列表
        </Button>
      </div>

      <dl className="bg-card grid gap-4 rounded-md border p-4 md:grid-cols-3">
        <div>
          <dt className="text-copy-12 text-muted-foreground">经营模式</dt>
          <dd className="text-copy-14 mt-1">{modeLabel(shop.operatingMode)}</dd>
          <dd className="text-copy-12 text-muted-foreground mt-1">{modeHint(shop.operatingMode)}</dd>
        </div>
        <div>
          <dt className="text-copy-12 text-muted-foreground">平台店铺 ID</dt>
          <dd className="text-copy-14 mt-1 break-all">{shop.platformShopId || '未回填'}</dd>
        </div>
        <div>
          <dt className="text-copy-12 text-muted-foreground">授权连接</dt>
          <dd className="text-copy-14 mt-1 break-all">{shop.connectionAccount ?? '未绑定'}</dd>
        </div>
      </dl>

      <div className="bg-card rounded-md border p-4">
        <p className="text-copy-14 font-medium">启用站点</p>
        <p className="text-copy-12 text-muted-foreground mt-1">
          站点决定该店铺在哪些国家上架，币种与税务口径由站点参数决定。
        </p>
        {available.length === 0 ? (
          <p className="text-copy-13 text-muted-foreground mt-3">该平台暂无站点参数</p>
        ) : (
          <ul className="mt-3 flex flex-wrap gap-2">
            {available.map((m) => {
              const on = enabled.has(m.id);
              return (
                <li key={m.id}>
                  <button
                    type="button"
                    disabled={!editable || busy}
                    onClick={() => void toggleMarketplace(m.code, !on)}
                    className={
                      on
                        ? 'border-primary/40 bg-primary/10 text-copy-13 flex h-8 items-center gap-2 rounded-md border px-3'
                        : 'border-border text-copy-13 hover:bg-muted flex h-8 items-center gap-2 rounded-md border px-3'
                    }
                  >
                    {m.name}
                    <span className="text-copy-12 text-muted-foreground">{m.defaultCurrency}</span>
                    {on && <CheckCircle2 className="text-success h-3.5 w-3.5" />}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
        {shop.marketplaces.length > 0 && (
          <ul className="text-copy-12 text-muted-foreground mt-3 space-y-1">
            {shop.marketplaces.map((m) => (
              <li key={m.marketplaceId}>
                {m.marketplaceName} · {m.status === 'ACTIVE' ? '已启用' : '已停用'}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

function ShopForm({
  platforms,
  connections,
  busy,
  onCancel,
  onSubmit,
}: {
  platforms: PlatformView[];
  connections: never[];
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: ShopInput) => Promise<void>;
}) {
  const available = platforms.filter((p) => p.supportStatus === 'AVAILABLE');
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [platformCode, setPlatformCode] = useState(available[0]?.code ?? '');
  const [operatingMode, setOperatingMode] = useState('SELF_FULFILLED');
  return (
    <div className="bg-card space-y-3 rounded-md border p-4">
      <p className="text-copy-14 font-medium">新建店铺</p>
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="店铺编码" hint="字母数字下划线中划线">
          <input
            value={code}
            onChange={(e) => setCode(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
          />
        </Field>
        <Field label="店铺名称">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
          />
        </Field>
        <Field label="平台">
          <select
            value={platformCode}
            onChange={(e) => setPlatformCode(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
          >
            {available.map((p) => (
              <option key={p.id} value={p.code}>
                {p.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="经营模式" hint="决定订单货权、成本构成与利润口径">
          <select
            value={operatingMode}
            onChange={(e) => setOperatingMode(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
          >
            {operatingModes.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <p className="text-copy-12 text-muted-foreground">{modeHint(operatingMode)}</p>
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={busy || !code || !name || !platformCode}
          onClick={() =>
            void onSubmit({
              code,
              name,
              platformCode,
              operatingMode,
              // 新建场景下连接与法人尚未选定；连接可稍后在店铺详情里绑定
              connectionId: null,
              legalEntityId: null,
              version: 0,
            })
          }
        >
          创建
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          取消
        </Button>
      </div>
    </div>
  );
}

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="text-copy-13 mb-1 block font-medium">{label}</span>
      {children}
      {hint && <span className="text-copy-12 text-muted-foreground mt-1 block">{hint}</span>}
    </label>
  );
}

function Empty({ text }: { text: string }) {
  return (
    <div className="bg-card rounded-lg border p-8 text-center">
      <p className="text-copy-13 text-muted-foreground">{text}</p>
    </div>
  );
}

function modeLabel(value: string) {
  return operatingModes.find((m) => m.value === value)?.label ?? value;
}

function modeHint(value: string) {
  return operatingModes.find((m) => m.value === value)?.hint ?? '';
}

export { ApiError };

/** 02.4 同步运行：游标 / 运行日志 / 失败队列与重放。 */
function SyncRuntime({
  cursors,
  failures,
  summary,
  runs,
  runCursorId,
  onSelectRun,
  can,
  busy,
  setBusy,
  onDone,
  onError,
  onNotice,
}: {
  cursors: SyncCursorView[];
  failures: SyncFailureView[];
  summary: SyncFailureSummary[];
  runs: SyncRunView[];
  runCursorId: string | null;
  onSelectRun: (id: string | null) => void;
  can: (p: string) => boolean;
  busy: boolean;
  setBusy: (v: boolean) => void;
  onDone: () => Promise<void>;
  onError: (e: unknown) => void;
  onNotice: (v: string) => void;
}) {
  async function act(path: string, message: string) {
    setBusy(true);
    try {
      await api(path, 'POST');
      onNotice(message);
      await onDone();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-5">
      {summary.length > 0 && (
        <div className="bg-card rounded-md border p-4">
          <p className="text-copy-14 font-medium">待处理失败</p>
          <p className="text-copy-12 text-muted-foreground mt-1">
            授权失效会导致持续漏单，优先处理；其余原因重放前先看明细。
          </p>
          <ul className="mt-3 flex flex-wrap gap-2">
            {summary.map((s) => (
              <li key={s.reason}>
                <Badge tone={s.reason === 'AUTHORIZATION_EXPIRED' ? 'danger' : 'warning'}>
                  {failureReasonLabel(s.reason)} {s.total}
                </Badge>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="bg-card rounded-md border p-4">
        <p className="text-copy-14 font-medium">同步任务</p>
        <p className="text-copy-12 text-muted-foreground mt-1">
          按「连接 · 站点 · 资源」拆分。游标只在处理全部成功后推进，失败时停在原处等待重试。
        </p>
        {cursors.length === 0 ? (
          <p className="text-copy-13 text-muted-foreground mt-3">还没有同步任务</p>
        ) : (
          <ul className="mt-3 space-y-2">
            {cursors.map((c) => (
              <li key={c.id} className="border-border rounded-md border p-3">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div className="min-w-0">
                    <p className="text-copy-14 flex flex-wrap items-center gap-2 font-medium">
                      {c.platformCode}
                      {c.marketplaceCode ? ` · ${c.marketplaceCode}` : ' · 全站点'}
                      <span className="text-muted-foreground font-normal">
                        {resourceLabel(c.resourceType)}
                      </span>
                      <Badge tone={statusTone[c.status] ?? 'neutral'}>
                        {statusLabel[c.status] ?? c.status}
                      </Badge>
                      {c.openFailures > 0 && (
                        <Badge tone="warning">{c.openFailures} 条失败</Badge>
                      )}
                    </p>
                    <p className="text-copy-12 text-muted-foreground mt-1 break-all">
                      游标 {c.cursorValue || '（初始位置）'} · 账号 {c.connectionAccount}
                      {c.consecutiveFailures > 0 ? ` · 连续失败 ${c.consecutiveFailures} 次` : ''}
                    </p>
                  </div>
                  <div className="flex flex-wrap items-center gap-2">
                    {can('channel.sync.trigger') && (
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy || c.status !== 'ACTIVE'}
                        onClick={() =>
                          void act(
                            `channel/sync-cursors/${c.id}/trigger`,
                            '已触发同步，处理完成后游标才会推进',
                          )
                        }
                      >
                        <RefreshCw className="h-4 w-4" />
                        立即同步
                      </Button>
                    )}
                    {can('channel.sync.update') && c.status === 'ACTIVE' && (
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy}
                        onClick={() => void act(`channel/sync-cursors/${c.id}/pause`, '同步已暂停')}
                      >
                        暂停
                      </Button>
                    )}
                    {can('channel.sync.update') && c.status === 'PAUSED' && (
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy}
                        onClick={() => void act(`channel/sync-cursors/${c.id}/resume`, '同步已恢复')}
                      >
                        恢复
                      </Button>
                    )}
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => onSelectRun(runCursorId === c.id ? null : c.id)}
                    >
                      {runCursorId === c.id ? '隐藏日志' : '运行日志'}
                    </Button>
                  </div>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>

      {runs.length > 0 && (
        <div className="bg-card rounded-md border p-4">
          <p className="text-copy-14 font-medium">运行日志</p>
          <ul className="mt-3 space-y-2">
            {runs.map((r) => (
              <li key={r.id} className="border-border flex flex-wrap items-center gap-2 rounded-md border p-2 text-copy-13">
                <Badge tone={statusTone[r.status] ?? 'neutral'}>
                  {statusLabel[r.status] ?? r.status}
                </Badge>
                <span>
                  {r.platformCode} · {resourceLabel(r.resourceType)}
                  {r.marketplaceCode ? ` · ${r.marketplaceCode}` : ''}
                </span>
                <span className="text-muted-foreground">
                  拉取 {r.fetchedCount} · 写入 {r.writtenCount} · 跳过 {r.skippedCount} · 失败{' '}
                  {r.failedCount}
                </span>
                <span className="text-muted-foreground">{formatTime(r.startedAt)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="bg-card rounded-md border p-4">
        <p className="text-copy-14 font-medium">失败队列</p>
        <p className="text-copy-12 text-muted-foreground mt-1">
          重放按外部单号去重，重复处理不会产生第二张订单。
        </p>
        {failures.length === 0 ? (
          <p className="text-copy-13 text-muted-foreground mt-3">没有待处理的失败记录</p>
        ) : (
          <ul className="mt-3 space-y-2">
            {failures.map((f) => (
              <li key={f.id} className="border-border rounded-md border p-3">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div className="min-w-0">
                    <p className="text-copy-14 flex flex-wrap items-center gap-2 font-medium">
                      <Badge tone={f.reason === 'AUTHORIZATION_EXPIRED' ? 'danger' : 'warning'}>
                        {failureReasonLabel(f.reason)}
                      </Badge>
                      <span className="break-all">{f.externalKey}</span>
                    </p>
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      {resourceLabel(f.resourceType)} · 已尝试 {f.attempts} 次 ·{' '}
                      {formatTime(f.occurredAt)}
                      {f.lastError ? ` · ${f.lastError}` : ''}
                    </p>
                  </div>
                  {can('channel.sync.trigger') && (
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={busy}
                      onClick={() =>
                        void act(
                          `channel/sync-failures/${f.id}/replay`,
                          '已加入重放队列，处理完成后失败记录会自动关闭',
                        )
                      }
                    >
                      <RefreshCcw className="h-4 w-4" />
                      重放
                    </Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

/** 02.5 平台回写：任务队列与未知结果核验。 */
function OperationQueue({
  operations,
  can,
  busy,
  setBusy,
  onDone,
  onError,
  onNotice,
}: {
  operations: PlatformOperationView[];
  can: (p: string) => boolean;
  busy: boolean;
  setBusy: (v: boolean) => void;
  onDone: () => Promise<void>;
  onError: (e: unknown) => void;
  onNotice: (v: string) => void;
}) {
  async function verify(id: string, conclusion: string, message: string) {
    setBusy(true);
    try {
      await api(`channel/platform-operations/${id}/verify`, 'POST', {
        verification: conclusion,
      });
      onNotice(message);
      await onDone();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  const unknown = operations.filter((o) => o.status === 'UNKNOWN');

  return (
    <div className="space-y-4">
      {unknown.length > 0 && (
        <div className="border-warning/40 bg-warning/5 rounded-md border p-4">
          <p className="text-copy-14 flex items-center gap-2 font-medium">
            <AlertTriangle className="text-warning h-4 w-4" />
            {unknown.length} 条回写结果未知，需先核验再决定是否重发
          </p>
          <p className="text-copy-12 text-muted-foreground mt-1">
            平台可能已经执行过这些操作。直接重试会造成重复发货或库存错扣。
          </p>
        </div>
      )}

      <div className="bg-card rounded-md border p-4">
        <p className="text-copy-14 font-medium">回写任务</p>
        <p className="text-copy-12 text-muted-foreground mt-1">
          每条任务带业务幂等键，重发不会造成平台侧重复动作。
        </p>
        {operations.length === 0 ? (
          <p className="text-copy-13 text-muted-foreground mt-3">没有回写记录</p>
        ) : (
          <ul className="mt-3 space-y-2">
            {operations.map((o) => (
              <li key={o.id} className="border-border rounded-md border p-3">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div className="min-w-0">
                    <p className="text-copy-14 flex flex-wrap items-center gap-2 font-medium">
                      {operationLabel(o.operation)}
                      <Badge tone={statusTone[o.status] ?? 'neutral'}>
                        {statusLabel[o.status] ?? o.status}
                      </Badge>
                      {o.status === 'UNKNOWN' && (
                        <Badge tone="neutral">{statusLabel[o.verification] ?? o.verification}</Badge>
                      )}
                    </p>
                    <p className="text-copy-12 text-muted-foreground mt-1 break-all">
                      {o.shopName} · {o.platformCode}
                      {o.marketplaceCode ? ` · ${o.marketplaceCode}` : ''} · 幂等键 {o.idempotencyKey}
                      {o.platformRequestId ? ` · 平台请求 ${o.platformRequestId}` : ''}
                    </p>
                    {o.lastError && (
                      <p className="text-copy-12 text-muted-foreground mt-1 break-all">{o.lastError}</p>
                    )}
                  </div>
                  {o.status === 'UNKNOWN' && can('channel.operation.update') && (
                    <div className="flex flex-wrap gap-2">
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy}
                        onClick={() =>
                          void verify(
                            o.id,
                            'CONFIRMED_APPLIED',
                            '已确认平台执行成功，不会重复发送',
                          )
                        }
                      >
                        已执行
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy}
                        onClick={() =>
                          void verify(o.id, 'CONFIRMED_NOT_APPLIED', '已确认未执行，可以重新发起')
                        }
                      >
                        未执行
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy}
                        onClick={() => void verify(o.id, 'STILL_UNKNOWN', '保持待核验状态')}
                      >
                        仍不确定
                      </Button>
                    </div>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

function resourceLabel(value: string) {
  return (
    {
      ORDER: '订单',
      LISTING: '商品',
      INVENTORY: '库存',
      SETTLEMENT: '结算',
      PRODUCT: '商品资料',
    }[value] ?? value
  );
}

function failureReasonLabel(value: string) {
  return (
    {
      AUTHORIZATION_EXPIRED: '授权失效',
      FIELD_PARSE_FAILED: '字段解析失败',
      MAPPING_MISSING: '映射缺失',
      RATE_LIMITED: '平台限流',
      NETWORK_ERROR: '网络异常',
      PLATFORM_ERROR: '平台错误',
    }[value] ?? value
  );
}

function operationLabel(value: string) {
  return (
    {
      PUBLISH: '上架',
      UPDATE_PRICE: '改价',
      UPDATE_STOCK: '改库存',
      SHIP_UPDATE: '回传物流',
      CANCEL_ORDER: '取消订单',
    }[value] ?? value
  );
}

function formatTime(value: string | null) {
  if (!value) return '—';
  const at = new Date(value);
  return Number.isNaN(at.getTime()) ? value : at.toLocaleString('zh-CN');
}
