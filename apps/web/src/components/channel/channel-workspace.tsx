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
  Rewind,
  Search,
  Send,
  Store,
  Webhook,
} from 'lucide-react';
import type {
  ConnectionView,
  CredentialRotateInput,
  DepartmentView,
  LegalEntityView,
  MemberView,
  PlatformOperationView,
  PlatformView,
  ReauthorizationReminderView,
  ShopInput,
  ShopView,
  SyncCursorInput,
  SyncCursorView,
  SyncFailureSummary,
  SyncFailureView,
  SyncRunView,
  WebhookDedupDetail,
  WebhookDedupEventView,
  WebhookDedupHitView,
  WebhookDedupSummary,
  WebhookEndpointView,
  WebhookReceiptSummary,
  WebhookReceiptView,
} from '../../../../../packages/api-contracts/src/index';
import { api, ApiError } from '@/lib/api';
import { EnumMappingPanel } from './enum-mapping-panel';
import { OAuthWizard } from './oauth-wizard';
import { useIdentity } from '../iam/identity-shell';

type Section = 'platforms' | 'connections' | 'shops' | 'sync' | 'operations' | 'webhooks';

const sections: readonly { id: Section; label: string; permission: string; icon: typeof Globe2 }[] =
  [
    { id: 'shops', label: '店铺与站点', permission: 'channel.shop.read', icon: Store },
    { id: 'connections', label: '授权连接', permission: 'channel.connection.read', icon: Link2 },
    { id: 'sync', label: '同步运行', permission: 'channel.sync.read', icon: RefreshCw },
    { id: 'operations', label: '平台回写', permission: 'channel.operation.read', icon: Send },
    // 02.6.1.1 接收记录：与同步同属平台对接面，复用 channel.sync.* 权限码
    { id: 'webhooks', label: '平台消息', permission: 'channel.sync.read', icon: Webhook },
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
  // 02.6.1.1 平台消息：验签失败与重复推送都要一眼看见
  RECEIVED: 'success',
  DUPLICATE: 'warning',
  SIGNATURE_FAILED: 'danger',
  PROCESSING: 'warning',
  PROCESSED: 'success',
  VERIFIED: 'success',
  MISSING: 'danger',
  UNCONFIGURED: 'warning',
  DISABLED: 'neutral',
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
  // 02.6.1.1 平台消息接收
  RECEIVED: '已入库',
  DUPLICATE: '重复推送',
  SIGNATURE_FAILED: '验签失败',
  PROCESSING: '处理中',
  PROCESSED: '已处理',
  VERIFIED: '验签通过',
  MISSING: '缺少签名',
  UNCONFIGURED: '未登记密钥',
  DISABLED: '已停用',
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
  // 02.6.1.1 平台消息接入
  const [webhookEndpoints, setWebhookEndpoints] = useState<WebhookEndpointView[]>([]);
  const [webhookReceipts, setWebhookReceipts] = useState<WebhookReceiptView[]>([]);
  const [webhookSummary, setWebhookSummary] = useState<WebhookReceiptSummary[]>([]);
  const [receiptFilter, setReceiptFilter] = useState('');
  // 02.6.1.2 去重视图：一个业务事件一行，带重推次数与首次处理结果引用
  const [dedupEvents, setDedupEvents] = useState<WebhookDedupEventView[]>([]);
  const [dedupSummary, setDedupSummary] = useState<WebhookDedupSummary | null>(null);
  const [dedupOnly, setDedupOnly] = useState(false);
  const [dedupDetail, setDedupDetail] = useState<WebhookDedupDetail | null>(null);
  const [dedupError, setDedupError] = useState('');
  const [runCursorId, setRunCursorId] = useState<string | null>(null);
  // 店铺要绑连接与法人、负责人要选成员与部门：这些字典在 identity 服务，权限不足时保持空数组，
  // 对应控件会明说"没有权限"，而不是渲染一个看起来能选、其实选不了的下拉。
  const [legalEntities, setLegalEntities] = useState<LegalEntityView[]>([]);
  const [members, setMembers] = useState<MemberView[]>([]);
  const [departments, setDepartments] = useState<DepartmentView[]>([]);
  const [dictionaryError, setDictionaryError] = useState('');

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
      if (can('channel.registry.read'))
        setPlatforms(await api<PlatformView[]>('channel/platforms'));
      if (can('channel.connection.read')) {
        setConnections(await api<ConnectionView[]>('channel/connections'));
        setReminders(
          await api<ReauthorizationReminderView[]>('channel/connections/reminders?withinDays=7'),
        );
      }
      if (can('channel.shop.read')) setShops(await api<ShopView[]>('channel/shops'));
      if (can('channel.sync.read')) {
        setCursors(await api<SyncCursorView[]>('channel/sync-cursors'));
        setFailures(await api<SyncFailureView[]>('channel/sync-failures?limit=50'));
        setFailureSummary(await api<SyncFailureSummary[]>('channel/sync-failures/summary'));
        setRuns(
          await api<SyncRunView[]>(
            `channel/sync-runs?limit=30${runCursorId ? `&cursorId=${runCursorId}` : ''}`,
          ),
        );
      }
      if (can('channel.operation.read'))
        setOperations(await api<PlatformOperationView[]>('channel/platform-operations?limit=50'));
      // 02.6.1.1 平台消息接入：接收记录与状态分布
      if (can('channel.sync.read')) {
        setWebhookEndpoints(await api<WebhookEndpointView[]>('channel/webhook-endpoints'));
        setWebhookSummary(await api<WebhookReceiptSummary[]>('channel/webhook-receipts/summary'));
        setWebhookReceipts(
          await api<WebhookReceiptView[]>(
            `channel/webhook-receipts?limit=50${receiptFilter ? `&status=${receiptFilter}` : ''}`,
          ),
        );
        // 02.6.1.2 去重视图：默认按最近一次推送倒序，接入排障先看正在被重推的那些
        setDedupEvents(
          await api<WebhookDedupEventView[]>(
            `channel/webhook-dedup?limit=50${dedupOnly ? '&duplicatedOnly=true' : ''}`,
          ),
        );
        setDedupSummary(await api<WebhookDedupSummary>('channel/webhook-dedup/summary'));
      }
    } catch (e) {
      handleError(e);
    } finally {
      setReady(true);
    }
    // 字典单独兜底：identity 不可用不该让渠道数据一起消失，失败原因要显示在店铺区里
    try {
      if (can('identity.legal_entity.read'))
        setLegalEntities(await api<LegalEntityView[]>('identity/legal-entities'));
      if (can('identity.member.read')) setMembers(await api<MemberView[]>('identity/members'));
      if (can('identity.department.read'))
        setDepartments(await api<DepartmentView[]>('identity/departments'));
      setDictionaryError('');
    } catch (e) {
      setDictionaryError(e instanceof Error ? e.message : '关联数据加载失败');
    }
  }, [can, handleError, runCursorId, receiptFilter, dedupOnly]);

  useEffect(() => {
    void load();
  }, [load]);

  const marketplaceOf = useCallback(
    (platformCode: string) => platforms.find((p) => p.code === platformCode)?.marketplaces ?? [],
    [platforms],
  );

  const visibleSections = useMemo(() => sections.filter((s) => can(s.permission)), [can]);

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
              <li
                key={r.connectionId}
                className="text-copy-13 text-muted-foreground flex items-center gap-2"
              >
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
          connections={connections}
          platforms={platforms}
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
      {section === 'webhooks' && (
        <>
          <WebhookInbox
            endpoints={webhookEndpoints}
            receipts={webhookReceipts}
            summary={webhookSummary}
            connections={connections}
            statusFilter={receiptFilter}
            onFilterChange={setReceiptFilter}
            can={can}
            busy={busy}
            setBusy={setBusy}
            onDone={load}
            onError={handleError}
            onNotice={setNotice}
          />
          <WebhookDedupPanel
            events={dedupEvents}
            summary={dedupSummary}
            duplicatedOnly={dedupOnly}
            onDuplicatedOnlyChange={setDedupOnly}
            detail={dedupDetail}
            detailError={dedupError}
            setDetail={setDedupDetail}
            setDetailError={setDedupError}
            can={can}
            busy={busy}
            setBusy={setBusy}
            onDone={load}
            onError={handleError}
            onNotice={setNotice}
          />
        </>
      )}
      {section === 'platforms' && <PlatformDirectory platforms={platforms} term={term} can={can} />}
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
      {section === 'shops' && (
        <>
          {dictionaryError && (
            <p role="alert" className="text-warning text-copy-13">
              关联数据（法人、成员、部门）加载失败：{dictionaryError}
              。店铺仍可维护，只是暂时无法选择这些关联项。
            </p>
          )}
          {detail ? (
            <ShopDetail
              shop={detail}
              platforms={platforms}
              connections={connections}
              legalEntities={legalEntities}
              members={members}
              departments={departments}
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
              connections={connections}
              legalEntities={legalEntities}
              members={members}
              busy={busy}
              setBusy={setBusy}
              onOpen={setDetail}
              onDone={load}
              onError={handleError}
              onNotice={setNotice}
            />
          )}
        </>
      )}
    </div>
  );
}

/** 2.1.1.1 平台目录 / 2.1.1.2 能力矩阵 / 2.1.1.3 站点与国家参数 / 2.1.2.1 枚举映射 */
function PlatformDirectory({
  platforms,
  term,
  can,
}: {
  platforms: PlatformView[];
  term: string;
  can: (p: string) => boolean;
}) {
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
            接入方式：
            {p.integration === 'OFFICIAL_API'
              ? '官方 API'
              : p.integration === 'THIRD_PARTY_ISV'
                ? '第三方 ISV'
                : '中介服务'}
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
          {/* 映射影响订单、售后、物流的落库口径，只读角色也必须看得见；
              写操作在面板内部按权限收起。 */}
          <div className="mt-3 border-t pt-3">
            <EnumMappingPanel platform={p} can={can} />
          </div>
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
  // 2.2.1.2：授权向导取代原来"建连接 + 再手填凭据"的两步式表单。
  // 旧流程要运营自己跳平台、自己把令牌写进密钥管理器再回来登记引用，漏一步就留下永不参与
  // 同步的连接；向导把跳转、换令牌、建店铺都收在一个流程里。
  const [wizardOpen, setWizardOpen] = useState(false);
  const [rotating, setRotating] = useState<ConnectionView | null>(null);
  const [health, setHealth] = useState<{
    id: string;
    diagnosis: string;
    recommendation: string;
    canSync: boolean;
  } | null>(null);

  const filtered = connections.filter((c) =>
    `${c.displayName} ${c.authorizedAccount} ${c.platformName}`.toLowerCase().includes(term),
  );

  async function diagnose(c: ConnectionView) {
    try {
      setHealth(null);
      const h = await api<{
        connectionId: string;
        diagnosis: string;
        recommendation: string;
        canSync: boolean;
      }>(`channel/connections/${c.id}/health`);
      setHealth({
        id: h.connectionId,
        diagnosis: h.diagnosis,
        recommendation: h.recommendation,
        canSync: h.canSync,
      });
    } catch (e) {
      onError(e);
    }
  }

  async function revoke(c: ConnectionView) {
    if (
      !window.confirm(
        `断开「${c.displayName || c.authorizedAccount}」后同步将停止，历史数据仍可查看。继续？`,
      )
    )
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

  if (!can('channel.connection.create')) {
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
      {wizardOpen && (
        <OAuthWizard platforms={platforms} can={can} onDone={onDone} onError={onError} />
      )}
      {filtered.length === 0 && !wizardOpen ? (
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
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={busy}
                          onClick={() => void revoke(c)}
                        >
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
      {!wizardOpen && (
        <Button size="sm" onClick={() => setWizardOpen(true)}>
          <Plus className="h-4 w-4" />
          授权新平台账号
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
          <Badge tone={statusTone[c.status] ?? 'neutral'}>
            {statusLabel[c.status] ?? c.status}
          </Badge>
          {c.syncPaused && <Badge tone="neutral">同步已暂停</Badge>}
        </p>
        <p className="text-copy-12 text-muted-foreground mt-1 break-all">
          {c.platformName} · {c.authorizedAccount} · 店铺 {c.shopCount} 个
          {c.credential
            ? ` · 凭据 ${statusLabel[c.credential.status] ?? c.credential.status}`
            : ' · 未配置凭据'}
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

/** 2.3.1.1 店铺列表 / 2.3.2.1 经营模式 / 2.3.2.2 负责人 */
function ShopList({
  shops,
  term,
  can,
  platforms,
  connections,
  legalEntities,
  members,
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
  connections: ConnectionView[];
  legalEntities: LegalEntityView[];
  members: MemberView[];
  busy: boolean;
  setBusy: (v: boolean) => void;
  onOpen: (s: ShopView) => void;
  onDone: () => Promise<void>;
  onError: (e: unknown) => void;
  onNotice: (v: string) => void;
}) {
  const [creating, setCreating] = useState(false);
  const filtered = shops.filter((s) =>
    `${s.code} ${s.name} ${s.platformName}`.toLowerCase().includes(term),
  );
  const canReadMembers = can('identity.member.read');
  const ownerLabel = (userId: string | null) =>
    linkedName(
      userId,
      members,
      (m) => m.userId,
      (m) => m.username,
      canReadMembers,
      '未指定',
    );

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
          connections={connections}
          legalEntities={legalEntities}
          can={can}
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
                    <Badge tone={statusTone[s.status] ?? 'neutral'}>
                      {statusLabel[s.status] ?? s.status}
                    </Badge>
                    <Badge tone="neutral">{modeLabel(s.operatingMode)}</Badge>
                  </p>
                  <p className="text-copy-12 text-muted-foreground mt-1 break-all">
                    {s.platformName} · {s.code} · 启用站点 {s.marketplaceCount} 个
                    {s.connectionAccount ? ` · 连接 ${s.connectionAccount}` : ' · 未绑定连接'}
                    {` · 负责人 ${ownerLabel(s.ownerId)}`}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <Button size="sm" variant="ghost" onClick={() => onOpen(s)}>
                    详情
                  </Button>
                  {can('channel.shop.delete') && (
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={busy}
                      onClick={() => void remove(s)}
                    >
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

/** 2.3.1.2 店铺详情：启用站点、编辑基础信息 / 2.3.2.2 负责人与部门 */
function ShopDetail({
  shop,
  platforms,
  connections,
  legalEntities,
  members,
  departments,
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
  connections: ConnectionView[];
  legalEntities: LegalEntityView[];
  members: MemberView[];
  departments: DepartmentView[];
  can: (p: string) => boolean;
  busy: boolean;
  setBusy: (v: boolean) => void;
  onBack: () => void;
  onDone: () => Promise<void>;
  onError: (e: unknown) => void;
  onNotice: (v: string) => void;
}) {
  const editable = can('channel.shop.update');
  const [editing, setEditing] = useState(false);
  const [transferring, setTransferring] = useState(false);
  const available = platforms.find((p) => p.code === shop.platformCode)?.marketplaces ?? [];
  const enabled = new Set(shop.marketplaces.map((m) => m.marketplaceId));
  const canReadMembers = can('identity.member.read');
  const canReadDepartments = can('identity.department.read');
  const canReadLegalEntity = can('identity.legal_entity.read');

  async function toggleMarketplace(code: string, isOn: boolean) {
    setBusy(true);
    try {
      if (isOn)
        await api(`channel/shops/${shop.id}/marketplaces`, 'POST', {
          marketplaceCode: code,
          status: 'ACTIVE',
        });
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
        <div className="flex items-center gap-2">
          {editable && !editing && (
            <Button size="sm" variant="ghost" onClick={() => setEditing(true)}>
              编辑
            </Button>
          )}
          {editable && !transferring && (
            <Button size="sm" variant="ghost" onClick={() => setTransferring(true)}>
              转让负责人
            </Button>
          )}
          <Button size="sm" variant="ghost" onClick={onBack}>
            返回列表
          </Button>
        </div>
      </div>

      {editing && (
        <ShopEditForm
          shop={shop}
          connections={connections}
          legalEntities={legalEntities}
          can={can}
          busy={busy}
          onCancel={() => setEditing(false)}
          onSubmit={async (input) => {
            setBusy(true);
            try {
              // 版本号随行带回：服务端据此拒绝"别人已经改过"的提交
              await api(`channel/shops/${shop.id}`, 'PUT', input);
              setEditing(false);
              onNotice('店铺已更新');
              await onDone();
            } catch (e) {
              onError(e);
            } finally {
              setBusy(false);
            }
          }}
        />
      )}

      {transferring && (
        <OwnerTransferForm
          shop={shop}
          members={members}
          departments={departments}
          busy={busy}
          onCancel={() => setTransferring(false)}
          onSubmit={async (input) => {
            setBusy(true);
            try {
              await api(`channel/shops/${shop.id}/transfer`, 'POST', input);
              setTransferring(false);
              onNotice('负责人已转让');
              await onDone();
            } catch (e) {
              onError(e);
            } finally {
              setBusy(false);
            }
          }}
        />
      )}

      <dl className="bg-card grid gap-4 rounded-md border p-4 md:grid-cols-3">
        <div>
          <dt className="text-copy-12 text-muted-foreground">经营模式</dt>
          <dd className="text-copy-14 mt-1">{modeLabel(shop.operatingMode)}</dd>
          <dd className="text-copy-12 text-muted-foreground mt-1">
            {modeHint(shop.operatingMode)}
          </dd>
        </div>
        <div>
          <dt className="text-copy-12 text-muted-foreground">平台店铺 ID</dt>
          <dd className="text-copy-14 mt-1 break-all">{shop.platformShopId || '未回填'}</dd>
        </div>
        <div>
          <dt className="text-copy-12 text-muted-foreground">授权连接</dt>
          <dd className="text-copy-14 mt-1 break-all">{shop.connectionAccount ?? '未绑定'}</dd>
        </div>
        <div>
          <dt className="text-copy-12 text-muted-foreground">结算法人</dt>
          <dd className="text-copy-14 mt-1 break-all">
            {linkedName(
              shop.legalEntityId,
              legalEntities,
              (e) => e.id,
              (e) => e.legalName,
              canReadLegalEntity,
              '未关联',
            )}
          </dd>
        </div>
        <div>
          <dt className="text-copy-12 text-muted-foreground">负责人（业绩归属）</dt>
          <dd className="text-copy-14 mt-1 break-all">
            {linkedName(
              shop.ownerId,
              members,
              (m) => m.userId,
              (m) => m.username,
              canReadMembers,
              '未指定',
            )}
          </dd>
        </div>
        <div>
          <dt className="text-copy-12 text-muted-foreground">所属部门</dt>
          <dd className="text-copy-14 mt-1 break-all">
            {linkedName(
              shop.departmentId,
              departments,
              (d) => d.id,
              (d) => d.name,
              canReadDepartments,
              '未指定',
            )}
          </dd>
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

/** 店铺编辑：名称、经营模式、连接、法人。编码与平台建档后不可改。 */
function ShopEditForm({
  shop,
  connections,
  legalEntities,
  can,
  busy,
  onCancel,
  onSubmit,
}: {
  shop: ShopView;
  connections: ConnectionView[];
  legalEntities: LegalEntityView[];
  can: (p: string) => boolean;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: ShopInput) => Promise<void>;
}) {
  const [name, setName] = useState(shop.name);
  const [operatingMode, setOperatingMode] = useState(shop.operatingMode);
  const [connectionId, setConnectionId] = useState(shop.connectionId ?? '');
  const [legalEntityId, setLegalEntityId] = useState(shop.legalEntityId ?? '');
  const connectionOptions = connections.filter((c) => c.platformCode === shop.platformCode);
  const canReadLegalEntity = can('identity.legal_entity.read');

  return (
    <div className="bg-card space-y-3 rounded-md border p-4">
      <p className="text-copy-14 font-medium">编辑店铺</p>
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="店铺名称">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
          />
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
        <Field
          label="授权连接"
          hint={
            connectionOptions.length === 0
              ? '该平台还没有授权连接'
              : '换连接会立即影响同步与回写走哪条凭据'
          }
        >
          <select
            value={connectionId}
            onChange={(e) => setConnectionId(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
          >
            <option value="">不绑定</option>
            {connectionOptions.map((c) => (
              <option key={c.id} value={c.id}>
                {c.displayName || c.authorizedAccount}（{statusLabel[c.status] ?? c.status}）
              </option>
            ))}
          </select>
        </Field>
        <Field
          label="结算法人"
          hint={!canReadLegalEntity ? '当前角色没有法人档案查看权限' : '决定收入记到哪个法人主体'}
        >
          <select
            value={legalEntityId}
            disabled={!canReadLegalEntity}
            onChange={(e) => setLegalEntityId(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2 disabled:opacity-60"
          >
            <option value="">不关联</option>
            {legalEntities.map((e) => (
              <option key={e.id} value={e.id}>
                {e.legalName}（{e.code}）
              </option>
            ))}
          </select>
        </Field>
      </div>
      <p className="text-copy-12 text-muted-foreground">{modeHint(operatingMode)}</p>
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={busy || !name}
          onClick={() =>
            void onSubmit({
              code: shop.code,
              name,
              platformCode: shop.platformCode,
              operatingMode,
              connectionId: connectionId || null,
              legalEntityId: legalEntityId || null,
              version: shop.version,
            })
          }
        >
          保存
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          取消
        </Button>
      </div>
    </div>
  );
}

/** 2.3.2.2 转让负责人：库里 owner_id 由触发器锁为不可变，只能走这个显式动作。 */
function OwnerTransferForm({
  shop,
  members,
  departments,
  busy,
  onCancel,
  onSubmit,
}: {
  shop: ShopView;
  members: MemberView[];
  departments: DepartmentView[];
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: { newOwnerId: string; departmentId: string }) => Promise<void>;
}) {
  const candidates = members.filter((m) => m.status === 'ACTIVE' && m.userId !== shop.ownerId);
  const [newOwnerId, setNewOwnerId] = useState('');
  const [departmentId, setDepartmentId] = useState(shop.departmentId ?? '');

  if (candidates.length === 0)
    return (
      <div className="bg-card space-y-2 rounded-md border p-4">
        <p className="text-copy-14 font-medium">转让负责人</p>
        <p className="text-copy-13 text-muted-foreground">
          租户内没有其他启用中的成员可作为负责人，无法转让。
        </p>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          返回
        </Button>
      </div>
    );

  return (
    <div className="bg-card space-y-3 rounded-md border p-4">
      <p className="text-copy-14 font-medium">转让负责人</p>
      <p className="text-copy-12 text-muted-foreground">
        负责人用于业绩归属与绩效；数据可见范围由「角色配置」里的数据范围单独控制，两者互不替代。
      </p>
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="新负责人">
          <select
            value={newOwnerId}
            onChange={(e) => setNewOwnerId(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
          >
            <option value="">请选择</option>
            {candidates.map((m) => (
              <option key={m.id} value={m.userId}>
                {m.username}
              </option>
            ))}
          </select>
        </Field>
        <Field label="归属部门">
          <select
            value={departmentId}
            onChange={(e) => setDepartmentId(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
          >
            <option value="">未设置部门</option>
            {departments.map((d) => (
              <option key={d.id} value={d.id}>
                {d.name}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={busy || !newOwnerId}
          onClick={() => void onSubmit({ newOwnerId, departmentId })}
        >
          确认转让
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          取消
        </Button>
      </div>
    </div>
  );
}

function ShopForm({
  platforms,
  connections,
  legalEntities,
  can,
  busy,
  onCancel,
  onSubmit,
}: {
  platforms: PlatformView[];
  connections: ConnectionView[];
  legalEntities: LegalEntityView[];
  can: (p: string) => boolean;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: ShopInput) => Promise<void>;
}) {
  const available = platforms.filter((p) => p.supportStatus === 'AVAILABLE');
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [platformCode, setPlatformCode] = useState(available[0]?.code ?? '');
  const [operatingMode, setOperatingMode] = useState('SELF_FULFILLED');
  const [connectionId, setConnectionId] = useState('');
  const [legalEntityId, setLegalEntityId] = useState('');

  // 连接只在同平台内可选：换平台后原来选中的连接会变成非法组合，先清掉
  const connectionOptions = connections.filter((c) => c.platformCode === platformCode);
  useEffect(() => {
    setConnectionId('');
  }, [platformCode]);

  const canReadLegalEntity = can('identity.legal_entity.read');
  const canReadConnection = can('channel.connection.read');

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
        <Field
          label="授权连接"
          hint={
            !canReadConnection
              ? '当前角色没有连接查看权限，店铺可先不绑定连接'
              : connectionOptions.length === 0
                ? '该平台还没有授权连接，可先建档、稍后在详情页绑定'
                : '绑定后同步与回写都走这条连接'
          }
        >
          <select
            value={connectionId}
            disabled={!canReadConnection}
            onChange={(e) => setConnectionId(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2 disabled:opacity-60"
          >
            <option value="">暂不绑定</option>
            {connectionOptions.map((c) => (
              <option key={c.id} value={c.id}>
                {c.displayName || c.authorizedAccount}（{statusLabel[c.status] ?? c.status}）
              </option>
            ))}
          </select>
        </Field>
        <Field
          label="结算法人"
          hint={
            !canReadLegalEntity
              ? '当前角色没有法人档案查看权限，店铺可先不关联法人'
              : '决定这家店铺的收入记到哪个法人主体'
          }
        >
          <select
            value={legalEntityId}
            disabled={!canReadLegalEntity}
            onChange={(e) => setLegalEntityId(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2 disabled:opacity-60"
          >
            <option value="">暂不关联</option>
            {legalEntities.map((e) => (
              <option key={e.id} value={e.id}>
                {e.legalName}（{e.code}）
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
              connectionId: connectionId || null,
              legalEntityId: legalEntityId || null,
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

/**
 * 关联项的显示口径。
 *
 * <p>字典读不到时不能直接显示"未设置"——那是在骗人：库里明明有值，用户会以为没绑、反复去重建。
 * 读得到就显示名字，读不到就说"已设置但当前角色无查看权限"；确实没值才显示 empty。
 */
function linkedName<T>(
  value: string | null | undefined,
  items: T[],
  idOf: (item: T) => string,
  nameOf: (item: T) => string,
  readable: boolean,
  empty: string,
) {
  if (!value) return empty;
  const hit = items.find((item) => idOf(item) === value);
  if (hit) return nameOf(hit);
  return readable ? value : '已设置（当前角色无查看权限）';
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
  connections,
  platforms,
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
  connections: ConnectionView[];
  platforms: PlatformView[];
  busy: boolean;
  setBusy: (v: boolean) => void;
  onDone: () => Promise<void>;
  onError: (e: unknown) => void;
  onNotice: (v: string) => void;
}) {
  const [creating, setCreating] = useState(false);
  const [rewinding, setRewinding] = useState<string | null>(null);

  async function act(path: string, message: string, data?: unknown) {
    setBusy(true);
    try {
      await api(path, 'POST', data);
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
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-copy-14 font-medium">同步任务</p>
          {can('channel.sync.create') && !creating && (
            <Button size="sm" onClick={() => setCreating(true)}>
              <Plus className="h-4 w-4" />
              新建同步任务
            </Button>
          )}
        </div>
        <p className="text-copy-12 text-muted-foreground mt-1">
          按「连接 · 站点 · 资源」拆分。游标只在处理全部成功后推进，失败时停在原处等待重试。
        </p>
        {creating && (
          <SyncCursorForm
            connections={connections}
            platforms={platforms}
            busy={busy}
            onCancel={() => setCreating(false)}
            onSubmit={async (input: SyncCursorInput) => {
              setCreating(false);
              await act('channel/sync-cursors', '同步任务已创建', input);
            }}
          />
        )}
        {cursors.length === 0 ? (
          <p className="text-copy-13 text-muted-foreground mt-3">
            还没有同步任务。没有游标就没有同步入口，需要先按「连接 · 站点 · 资源」登记一个。
          </p>
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
                      {c.openFailures > 0 && <Badge tone="warning">{c.openFailures} 条失败</Badge>}
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
                        onClick={() =>
                          void act(`channel/sync-cursors/${c.id}/resume`, '同步已恢复')
                        }
                      >
                        恢复
                      </Button>
                    )}
                    {can('channel.sync.update') && (
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy}
                        onClick={() => setRewinding(rewinding === c.id ? null : c.id)}
                      >
                        <Rewind className="h-4 w-4" />
                        回退游标
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
                {rewinding === c.id && (
                  <RewindForm
                    current={c.cursorValue}
                    busy={busy}
                    onCancel={() => setRewinding(null)}
                    onSubmit={async (cursorValue) => {
                      setRewinding(null);
                      await act(
                        `channel/sync-cursors/${c.id}/rewind`,
                        '游标已回退，下次同步会从该位置重拉',
                        { cursorValue },
                      );
                    }}
                  />
                )}
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
              <li
                key={r.id}
                className="border-border text-copy-13 flex flex-wrap items-center gap-2 rounded-md border p-2"
              >
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
                        <Badge tone="neutral">
                          {statusLabel[o.verification] ?? o.verification}
                        </Badge>
                      )}
                    </p>
                    <p className="text-copy-12 text-muted-foreground mt-1 break-all">
                      {o.shopName} · {o.platformCode}
                      {o.marketplaceCode ? ` · ${o.marketplaceCode}` : ''} · 幂等键{' '}
                      {o.idempotencyKey}
                      {o.platformRequestId ? ` · 平台请求 ${o.platformRequestId}` : ''}
                    </p>
                    {o.lastError && (
                      <p className="text-copy-12 text-muted-foreground mt-1 break-all">
                        {o.lastError}
                      </p>
                    )}
                  </div>
                  {o.status === 'UNKNOWN' && can('channel.operation.update') && (
                    <div className="flex flex-wrap gap-2">
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy}
                        onClick={() =>
                          void verify(o.id, 'CONFIRMED_APPLIED', '已确认平台执行成功，不会重复发送')
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

const resourceTypes: readonly { value: string; label: string }[] = [
  { value: 'ORDER', label: '订单' },
  { value: 'LISTING', label: '商品' },
  { value: 'INVENTORY', label: '库存' },
  { value: 'SETTLEMENT', label: '结算' },
  { value: 'PRODUCT', label: '商品资料' },
];

/** 2.4.1.1 登记同步任务：没有游标就没有同步入口，所以必须能从页面建。 */
function SyncCursorForm({
  connections,
  platforms,
  busy,
  onCancel,
  onSubmit,
}: {
  connections: ConnectionView[];
  platforms: PlatformView[];
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: SyncCursorInput) => Promise<void>;
}) {
  const usable = connections.filter((c) => c.status === 'ACTIVE');
  const [connectionId, setConnectionId] = useState(usable[0]?.id ?? '');
  const [marketplaceId, setMarketplaceId] = useState('');
  const [resourceType, setResourceType] = useState('ORDER');
  const [partitionKey, setPartitionKey] = useState('');

  const connection = usable.find((c) => c.id === connectionId);
  const sites = connection
    ? (platforms.find((p) => p.code === connection.platformCode)?.marketplaces ?? [])
    : [];

  useEffect(() => {
    setMarketplaceId('');
  }, [connectionId]);

  if (usable.length === 0)
    return (
      <div className="border-border bg-muted/40 mt-3 space-y-2 rounded-md border p-3">
        <p className="text-copy-13 text-muted-foreground">
          没有启用中的授权连接，无法登记同步任务。请先到「授权连接」建一条连接并写入凭据引用。
        </p>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          返回
        </Button>
      </div>
    );

  return (
    <div className="border-border bg-muted/40 mt-3 space-y-3 rounded-md border p-3">
      <p className="text-copy-13 font-medium">新建同步任务</p>
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="授权连接" hint="同步数据从哪条凭据拉取">
          <select
            value={connectionId}
            onChange={(e) => setConnectionId(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
          >
            {usable.map((c) => (
              <option key={c.id} value={c.id}>
                {c.platformName} · {c.displayName || c.authorizedAccount}
              </option>
            ))}
          </select>
        </Field>
        <Field label="站点" hint="留空表示该连接下所有站点">
          <select
            value={marketplaceId}
            onChange={(e) => setMarketplaceId(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
          >
            <option value="">全部站点</option>
            {sites.map((m) => (
              <option key={m.id} value={m.id}>
                {m.name}（{m.code}）
              </option>
            ))}
          </select>
        </Field>
        <Field label="资源类型">
          <select
            value={resourceType}
            onChange={(e) => setResourceType(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
          >
            {resourceTypes.map((r) => (
              <option key={r.value} value={r.value}>
                {r.label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="分区键" hint="同一连接 + 站点 + 资源下唯一，用于区分多条并行任务">
          <input
            value={partitionKey}
            onChange={(e) => setPartitionKey(e.target.value)}
            className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
          />
        </Field>
      </div>
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={busy || !connectionId || !resourceType}
          onClick={() =>
            void onSubmit({
              connectionId,
              marketplaceId: marketplaceId || null,
              resourceType,
              partitionKey: partitionKey.trim(),
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

/** 2.4.1.3 断点续传：回退游标以重拉一段时间窗，重放幂等由 external_key 保证。 */
function RewindForm({
  current,
  busy,
  onCancel,
  onSubmit,
}: {
  current: string;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (cursorValue: string) => Promise<void>;
}) {
  const [value, setValue] = useState(current);
  return (
    <div className="border-border bg-muted/40 mt-3 space-y-2 rounded-md border p-3">
      <p className="text-copy-12 text-muted-foreground">
        回退后下一次同步会从这个位置重新拉取。重放按外部单号去重，不会产生第二张单据。
      </p>
      <div className="flex flex-wrap items-end gap-2">
        <div className="min-w-48 flex-1">
          <Field label="目标游标值" hint={`当前 ${current || '（初始位置）'}`}>
            <input
              value={value}
              onChange={(e) => setValue(e.target.value)}
              className="border-border bg-background text-copy-13 h-9 rounded-md border px-2"
            />
          </Field>
        </div>
        <Button
          size="sm"
          disabled={busy || !value.trim()}
          onClick={() => void onSubmit(value.trim())}
        >
          回退
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          取消
        </Button>
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

/**
 * 02.6.1.1 平台消息接入：接收记录与回调入口。
 *
 * <p>页面上刻意把三件事摆在最前面，因为它们是接入出问题时的第一现场：
 * 验签失败（有人在伪造推送）、重复推送（平台在超时重推）、待处理积压（异步消费跟不上）。
 */
function WebhookInbox({
  endpoints,
  receipts,
  summary,
  connections,
  statusFilter,
  onFilterChange,
  can,
  busy,
  setBusy,
  onDone,
  onError,
  onNotice,
}: {
  endpoints: WebhookEndpointView[];
  receipts: WebhookReceiptView[];
  summary: WebhookReceiptSummary[];
  connections: ConnectionView[];
  statusFilter: string;
  onFilterChange: (value: string) => void;
  can: (p: string) => boolean;
  busy: boolean;
  setBusy: (v: boolean) => void;
  onDone: () => Promise<void>;
  onError: (e: unknown) => void;
  onNotice: (v: string) => void;
}) {
  const [showForm, setShowForm] = useState(false);

  async function run(work: () => Promise<unknown>, message: string): Promise<void> {
    setBusy(true);
    try {
      await work();
      onNotice(message);
      await onDone();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  const countOf = (status: string) => summary.find((s) => s.status === status)?.total ?? 0;
  const failed = countOf('SIGNATURE_FAILED');
  const duplicates = countOf('DUPLICATE');
  const pending = countOf('RECEIVED') + countOf('PROCESSING');
  const badSignature = receipts.filter(
    (r) => r.signatureStatus !== 'VERIFIED' && r.status === 'SIGNATURE_FAILED',
  );

  const active = endpoints.filter((e) => e.status === 'ACTIVE');

  return (
    <div className="space-y-4">
      {(failed > 0 || duplicates > 0 || pending > 0) && (
        <div className="border-warning/40 bg-warning/5 rounded-md border p-4">
          <p className="text-copy-14 flex items-center gap-2 font-medium">
            <AlertTriangle className="text-warning h-4 w-4" />
            平台消息待处理
          </p>
          <ul className="text-copy-13 text-muted-foreground mt-2 space-y-1">
            {failed > 0 && (
              <li>
                {failed} 条验签失败——这些推送没有可信来源，不会进入业务处理。
                持续增长通常意味着有人在伪造回调，或密钥已轮换但平台侧还没更新。
              </li>
            )}
            {duplicates > 0 && (
              <li>
                {duplicates} 条重复推送——平台在重推同一条事件。
                每条事件只会被处理一次，重推说明上一次处理没能及时完成。
              </li>
            )}
            {pending > 0 && <li>{pending} 条已入库待处理或处理中。</li>}
          </ul>
        </div>
      )}

      <div className="grid gap-3 sm:grid-cols-3">
        {(
          [
            ['RECEIVED', '已入库'],
            ['PROCESSING', '处理中'],
            ['SIGNATURE_FAILED', '验签失败'],
            ['DUPLICATE', '重复推送'],
            ['PROCESSED', '已处理'],
            ['FAILED', '处理失败'],
          ] as const
        ).map(([status, label]) => (
          <button
            key={status}
            type="button"
            aria-pressed={statusFilter === status}
            onClick={() => onFilterChange(statusFilter === status ? '' : status)}
            className={
              statusFilter === status
                ? 'border-ring bg-card rounded-md border p-3 text-left'
                : 'border-border bg-card hover:bg-muted rounded-md border p-3 text-left'
            }
          >
            <p className="text-muted-foreground text-copy-12">{label}</p>
            <p className="text-copy-14 mt-1 font-medium">{countOf(status)}</p>
          </button>
        ))}
      </div>

      <div className="bg-card rounded-md border p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <p className="text-copy-14 font-medium">回调入口</p>
            <p className="text-copy-12 text-muted-foreground mt-1">
              把回调地址与签名密钥填到平台后台。密钥只存密文，写入后无法再读回——换密钥需重新登记。
            </p>
          </div>
          {can('channel.sync.create') &&
            (showForm ? (
              <Button size="sm" variant="ghost" onClick={() => setShowForm(false)}>
                取消
              </Button>
            ) : (
              <Button size="sm" variant="ghost" onClick={() => setShowForm(true)}>
                <Plus className="h-4 w-4" />
                登记入口
              </Button>
            ))}
        </div>

        {showForm && (
          <WebhookEndpointForm
            connections={connections}
            busy={busy}
            onCancel={() => setShowForm(false)}
            onSubmit={async (input) => {
              await run(
                () =>
                  api('channel/webhook-endpoints', 'POST', {
                    connectionId: input.connectionId,
                    signatureHeader: input.signatureHeader,
                    signatureEncoding: input.signatureEncoding,
                    signingSecret: input.signingSecret,
                  }),
                '回调入口已登记，密钥已加密保存',
              );
              setShowForm(false);
            }}
          />
        )}

        {endpoints.length === 0 ? (
          <p className="text-copy-13 text-muted-foreground mt-3">
            还没有登记回调入口。没有入口，平台推送不会被接收。
          </p>
        ) : (
          <ul className="mt-3 space-y-2">
            {endpoints.map((e) => (
              <li key={e.id} className="border-border rounded-md border p-3">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div className="min-w-0">
                    <p className="text-copy-14 flex flex-wrap items-center gap-2 font-medium">
                      {e.platformCode} · {e.authorizedAccount}
                      <Badge tone={statusTone[e.status] ?? 'neutral'}>
                        {statusLabel[e.status] ?? e.status}
                      </Badge>
                    </p>
                    <p className="text-copy-12 text-muted-foreground mt-1 break-all">
                      回调地址 {e.callbackUrl} · 签名头 {e.signatureHeader} · {e.signatureAlgorithm}{' '}
                      ({e.signatureEncoding})
                    </p>
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      最近接收 {formatTime(e.lastReceivedAt)}
                    </p>
                  </div>
                  {e.status === 'ACTIVE' && can('channel.sync.update') && (
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={busy}
                      onClick={() =>
                        void run(
                          () => api(`channel/webhook-endpoints/${e.id}/disable`, 'POST'),
                          '入口已停用，平台再推送也不会受理；接收记录仍可查',
                        )
                      }
                    >
                      停用
                    </Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="bg-card rounded-md border p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <p className="text-copy-14 font-medium">接收记录</p>
            <p className="text-copy-12 text-muted-foreground mt-1">
              先持久化再处理：接收成功即落库返回，业务处理异步完成，避免平台超时重推造成压力。
            </p>
          </div>
          {statusFilter && (
            <Button size="sm" variant="ghost" onClick={() => onFilterChange('')}>
              清除筛选
            </Button>
          )}
        </div>

        {receipts.length === 0 ? (
          <p className="text-copy-13 text-muted-foreground mt-3">
            {statusFilter
              ? `没有状态为「${statusLabel[statusFilter] ?? statusFilter}」的接收记录`
              : '还没有平台推送记录。平台配置好回调地址后，推送会实时出现在这里。'}
          </p>
        ) : (
          <ul className="mt-3 space-y-2">
            {receipts.map((r) => (
              <li key={r.id} className="border-border rounded-md border p-3">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div className="min-w-0">
                    <p className="text-copy-14 flex flex-wrap items-center gap-2 font-medium">
                      {r.eventType || r.topic || '未标注类型'}
                      <Badge tone={statusTone[r.status] ?? 'neutral'}>
                        {statusLabel[r.status] ?? r.status}
                      </Badge>
                      <Badge tone={statusTone[r.signatureStatus] ?? 'neutral'}>
                        {statusLabel[r.signatureStatus] ?? r.signatureStatus}
                      </Badge>
                      {r.duplicateOf && <Badge tone="warning">第 {r.deliveryAttempt} 次推送</Badge>}
                    </p>
                    <p className="text-copy-12 text-muted-foreground mt-1 break-all">
                      事件 {r.externalEventId || '（平台未带事件 ID）'} · {r.platformCode} ·{' '}
                      {r.shopName ?? '未标注店铺'} · 接收于 {formatTime(r.receivedAt)}
                    </p>
                    {r.duplicateOf && (
                      <p className="text-copy-12 text-muted-foreground mt-1 break-all">
                        首次接收 {r.duplicateOf}，本条不会再次进入业务处理
                      </p>
                    )}
                    {r.lastError && (
                      <p className="text-copy-12 text-muted-foreground mt-1 break-all">
                        {r.lastError}
                      </p>
                    )}
                  </div>
                  {r.status === 'RECEIVED' && can('channel.sync.update') && (
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={busy}
                      onClick={() =>
                        void run(
                          () => api(`channel/webhook-receipts/${r.id}/claim`, 'POST', {}),
                          '已认领，状态转为处理中',
                        )
                      }
                    >
                      认领处理
                    </Button>
                  )}
                  {r.status === 'PROCESSING' && can('channel.sync.update') && (
                    <div className="flex flex-wrap gap-2">
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy}
                        onClick={() =>
                          void run(
                            () =>
                              api(`channel/webhook-receipts/${r.id}/complete`, 'POST', {
                                status: 'PROCESSED',
                                lastError: '',
                              }),
                            '已标记处理完成',
                          )
                        }
                      >
                        处理完成
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy}
                        onClick={() =>
                          void run(
                            () =>
                              api(`channel/webhook-receipts/${r.id}/complete`, 'POST', {
                                status: 'FAILED',
                                lastError: '人工标记处理失败',
                              }),
                            '已标记处理失败，可重新认领',
                          )
                        }
                      >
                        处理失败
                      </Button>
                    </div>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}

        {badSignature.length > 0 && (
          <div className="border-destructive/30 bg-destructive/5 mt-4 rounded-md border p-3">
            <p className="text-copy-13 text-destructive font-medium">
              {badSignature.length} 条推送未通过验签
            </p>
            <p className="text-copy-12 text-muted-foreground mt-1">
              这些报文已留证但不会进入业务处理。若确认是密钥轮换导致的，先重新登记入口密钥；
              若无法解释，按安全事件处理——回调地址是公开的，只有签名能区分真假。
            </p>
          </div>
        )}
      </div>

      {active.length === 0 && endpoints.length > 0 && (
        <p className="text-copy-13 text-muted-foreground">
          所有回调入口都已停用，平台推送不会被受理。
        </p>
      )}
    </div>
  );
}

/** 02.6.1.1 登记回调入口：签名密钥明文只在这一刻存在。 */
function WebhookEndpointForm({
  connections,
  busy,
  onCancel,
  onSubmit,
}: {
  connections: ConnectionView[];
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: {
    connectionId: string;
    signatureHeader: string;
    signatureEncoding: 'HEX' | 'BASE64';
    signingSecret: string;
  }) => Promise<void>;
}) {
  const usable = connections.filter((c) => c.status === 'ACTIVE');
  const [connectionId, setConnectionId] = useState(usable[0]?.id ?? '');
  const [signatureHeader, setSignatureHeader] = useState('X-Starcon-Signature');
  const [signatureEncoding, setSignatureEncoding] = useState<'HEX' | 'BASE64'>('HEX');
  const [signingSecret, setSigningSecret] = useState('');

  if (usable.length === 0)
    return (
      <p className="text-copy-13 text-muted-foreground mt-3">
        没有可用连接。先在「授权连接」完成授权，才能为它登记回调入口。
      </p>
    );

  return (
    <div className="border-border mt-3 space-y-2 rounded-md border p-3">
      <label className="block">
        <span className="text-copy-13">连接</span>
        <select
          value={connectionId}
          onChange={(e) => setConnectionId(e.target.value)}
          className="border-border bg-card text-copy-13 mt-1 h-8 w-full rounded-md border px-2"
        >
          {usable.map((c) => (
            <option key={c.id} value={c.id}>
              {c.platformCode} · {c.authorizedAccount}
            </option>
          ))}
        </select>
      </label>
      <label className="block">
        <span className="text-copy-13">签名请求头</span>
        <input
          value={signatureHeader}
          onChange={(e) => setSignatureHeader(e.target.value)}
          className="border-border bg-card text-copy-13 mt-1 h-8 w-full rounded-md border px-2"
        />
      </label>
      <label className="block">
        <span className="text-copy-13">签名编码</span>
        <select
          value={signatureEncoding}
          onChange={(e) => setSignatureEncoding(e.target.value as 'HEX' | 'BASE64')}
          className="border-border bg-card text-copy-13 mt-1 h-8 w-full rounded-md border px-2"
        >
          <option value="HEX">HEX</option>
          <option value="BASE64">BASE64</option>
        </select>
      </label>
      <label className="block">
        <span className="text-copy-13">平台签名密钥</span>
        <input
          type="password"
          value={signingSecret}
          onChange={(e) => setSigningSecret(e.target.value)}
          placeholder="平台后台配置的 webhook 密钥"
          className="border-border bg-card text-copy-13 mt-1 h-8 w-full rounded-md border px-2"
        />
      </label>
      <p className="text-copy-12 text-muted-foreground">
        密钥加密后落库且无法再读回。丢失后只能重新登记。
      </p>
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={busy || !connectionId || !signingSecret.trim()}
          onClick={() =>
            void onSubmit({
              connectionId,
              signatureHeader: signatureHeader.trim() || 'X-Starcon-Signature',
              signatureEncoding,
              signingSecret: signingSecret.trim(),
            })
          }
        >
          登记
        </Button>
        <Button size="sm" variant="ghost" disabled={busy} onClick={onCancel}>
          取消
        </Button>
      </div>
    </div>
  );
}

/**
 * 02.6.1.2 去重视图。
 *
 * <p>这一段的目的是回答一个具体问题：**同一条平台事件被重推了几次，最后处理成什么样了。**
 * 所以每行是一个「业务事件」而不是一条「接收记录」——三次投递显示成一行、重推次数 2，
 * 旁边直接跟着首次处理的结论。只列 event_id 的表回答不了「到底处理了没有」，
 * 那样的去重视图等于没去重。
 *
 * <p>展开后给的是「去重命中记录 + 全部投递时间线」：每条命中都指回首次，
 * 并带上首次的当前处理结果。
 */
function WebhookDedupPanel({
  events,
  summary,
  duplicatedOnly,
  onDuplicatedOnlyChange,
  detail,
  detailError,
  setDetail,
  setDetailError,
  can,
  busy,
  setBusy,
  onDone,
  onError,
  onNotice,
}: {
  events: WebhookDedupEventView[];
  summary: WebhookDedupSummary | null;
  duplicatedOnly: boolean;
  onDuplicatedOnlyChange: (value: boolean) => void;
  detail: WebhookDedupDetail | null;
  detailError: string;
  setDetail: (value: WebhookDedupDetail | null) => void;
  setDetailError: (value: string) => void;
  can: (p: string) => boolean;
  busy: boolean;
  setBusy: (v: boolean) => void;
  onDone: () => Promise<void>;
  onError: (e: unknown) => void;
  onNotice: (v: string) => void;
}) {
  const [expanded, setExpanded] = useState<string | null>(null);
  // 本地筛选：事件 ID 与连接两个筛选用本地做，避免每敲一个字就打一次后端。
  // 结果集上限 50 条，本地筛不会漏掉"页面上本来就能看到的那些"，口径写在文案里。
  const [term, setTerm] = useState('');

  async function run(work: () => Promise<unknown>, message: string): Promise<void> {
    setBusy(true);
    try {
      await work();
      onNotice(message);
      await onDone();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  async function toggle(event: WebhookDedupEventView): Promise<void> {
    if (expanded === event.firstReceiptId) {
      setExpanded(null);
      setDetail(null);
      setDetailError('');
      return;
    }
    setExpanded(event.firstReceiptId);
    setDetail(null);
    setDetailError('');
    setBusy(true);
    try {
      setDetail(
        await api<WebhookDedupDetail>(`channel/webhook-dedup/${event.firstReceiptId}`),
      );
    } catch (e) {
      setDetailError(e instanceof Error ? e.message : '去重详情加载失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  const needle = term.trim().toLowerCase();
  const visible = events.filter(
    (e) =>
      !needle ||
      e.externalEventId.toLowerCase().includes(needle) ||
      e.platformCode.toLowerCase().includes(needle) ||
      e.authorizedAccount.toLowerCase().includes(needle) ||
      (e.eventType || '').toLowerCase().includes(needle),
  );

  const redelivered = summary?.duplicateDeliveries ?? 0;
  const duplicated = summary?.duplicatedEvents ?? 0;

  return (
    <div className="space-y-4">
      {redelivered > 0 && (
        <div className="border-warning/40 bg-warning/5 rounded-md border p-4">
          <p className="text-copy-14 flex items-center gap-2 font-medium">
            <RefreshCcw className="text-warning h-4 w-4" />
            平台在重推
          </p>
          <ul className="text-copy-13 text-muted-foreground mt-2 space-y-1">
            <li>
              {redelivered} 条重复投递，分布在 {duplicated} 个业务事件上。
              每个事件只会被处理一次，重推的是同一条消息，不会产生第二条业务记录。
            </li>
            {summary && summary.worstDuplicateCount > 0 && (
              <li>
                重推最多的是事件 {summary.worstExternalEventId || '（平台未带事件 ID）'}，已被重推{' '}
                {summary.worstDuplicateCount} 次。持续重推通常意味着上一次处理没能及时完成。
              </li>
            )}
            {summary && summary.pendingFirstEvents > 0 && (
              <li>{summary.pendingFirstEvents} 个事件的首次记录还在等下游认领或处理。</li>
            )}
          </ul>
        </div>
      )}

      <div className="grid gap-3 sm:grid-cols-4">
        {(
          [
            ['totalEvents', '业务事件'],
            ['duplicatedEvents', '被重推的事件'],
            ['duplicateDeliveries', '重复投递'],
            ['pendingFirstEvents', '待处理'],
          ] as const
        ).map(([key, label]) => (
          <div key={key} className="border-border bg-card rounded-md border p-3">
            <p className="text-muted-foreground text-copy-12">{label}</p>
            <p className="text-copy-14 mt-1 font-medium">{summary?.[key] ?? 0}</p>
          </div>
        ))}
      </div>

      <div className="bg-card rounded-md border p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <p className="text-copy-14 font-medium">去重视图</p>
            <p className="text-copy-12 text-muted-foreground mt-1">
              以（回调入口，外部事件 ID）为准归并。同一入口下同一条事件只处理一次，重推次数与首次处理结果都在这里。
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <label className="relative block">
              <span className="sr-only">按事件 ID 或平台筛选</span>
              <Search className="text-muted-foreground pointer-events-none absolute top-2 left-2 h-4 w-4" />
              <input
                value={term}
                onChange={(e) => setTerm(e.target.value)}
                placeholder="事件 ID / 平台 / 账号"
                className="border-border bg-card text-copy-13 h-8 w-56 rounded-md border pr-2 pl-8"
              />
            </label>
            <Button
              size="sm"
              variant="ghost"
              aria-pressed={duplicatedOnly}
              onClick={() => onDuplicatedOnlyChange(!duplicatedOnly)}
            >
              {duplicatedOnly ? '显示全部事件' : '只看重推过的'}
            </Button>
          </div>
        </div>

        {!summary ? (
          <p className="text-copy-13 text-muted-foreground mt-3">去重数据加载中…</p>
        ) : visible.length === 0 ? (
          <p className="text-copy-13 text-muted-foreground mt-3">
            {needle
              ? `没有匹配「${term.trim()}」的业务事件`
              : duplicatedOnly
                ? '没有任何事件被平台重推过——所有推送都是一次送达。'
                : '还没有业务事件。平台推送被接收后，这里会按事件归并显示。'}
          </p>
        ) : (
          <ul className="mt-3 space-y-2">
            {visible.map((e) => (
              <li key={e.firstReceiptId} className="border-border rounded-md border p-3">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <button
                    type="button"
                    onClick={() => void toggle(e)}
                    className="min-w-0 text-left"
                  >
                    <p className="text-copy-14 flex flex-wrap items-center gap-2 font-medium">
                      {e.eventType || e.topic || '未标注类型'}
                      <Badge tone={statusTone[e.firstStatus] ?? 'neutral'}>
                        {statusLabel[e.firstStatus] ?? e.firstStatus}
                      </Badge>
                      {e.duplicateCount > 0 ? (
                        <Badge tone="warning">重推 {e.duplicateCount} 次</Badge>
                      ) : (
                        <Badge tone="success">一次送达</Badge>
                      )}
                      {e.signatureStatus !== 'VERIFIED' && (
                        <Badge tone={statusTone[e.signatureStatus] ?? 'danger'}>
                          {statusLabel[e.signatureStatus] ?? e.signatureStatus}
                        </Badge>
                      )}
                    </p>
                    <p className="text-copy-12 text-muted-foreground mt-1 break-all">
                      事件 {e.externalEventId || '（平台未带事件 ID）'} · {e.platformCode} ·{' '}
                      {e.authorizedAccount} · {e.shopName ?? '未标注店铺'} · 共投递{' '}
                      {e.deliveryCount} 次
                    </p>
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      首次接收 {formatTime(e.firstReceivedAt)} · 最近一次{' '}
                      {formatTime(e.lastReceivedAt)}
                      {e.firstProcessedAt && ` · 处理完成 ${formatTime(e.firstProcessedAt)}`}
                    </p>
                    {e.firstLastError && (
                      <p className="text-copy-12 text-destructive mt-1 break-all">
                        首次处理失败：{e.firstLastError}
                      </p>
                    )}
                  </button>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={busy}
                    onClick={() => void toggle(e)}
                  >
                    {expanded === e.firstReceiptId ? '收起' : '投递明细'}
                  </Button>
                </div>

                {expanded === e.firstReceiptId && (
                  <div className="border-border bg-muted/40 mt-3 space-y-2 rounded-md border p-3">
                    {detailError && (
                      <p role="alert" className="text-destructive text-copy-13">
                        {detailError}
                      </p>
                    )}
                    {!detailError && !detail && <p className="text-copy-13">明细加载中…</p>}
                    {detail && detail.event.firstReceiptId !== e.firstReceiptId && (
                      <p className="text-copy-13 text-muted-foreground">
                        当前展开的是另一个事件，请重新点击。
                      </p>
                    )}
                    {detail && detail.event.firstReceiptId === e.firstReceiptId && (
                      <>
                        <p className="text-copy-13 font-medium">
                          去重命中 {detail.hits.length} 条 · 全部投递 {detail.deliveries.length} 条
                        </p>
                        {detail.hits.length === 0 && (
                          <p className="text-copy-13 text-muted-foreground">
                            这条事件只被投递了一次，没有重复推送。
                          </p>
                        )}
                        <ul className="space-y-1">
                          {detail.hits.map((h) => (
                            <li
                              key={h.receiptId}
                              className="text-copy-12 text-muted-foreground flex flex-wrap items-center gap-2"
                            >
                              <Badge tone="warning">第 {h.deliveryAttempt} 次</Badge>
                              <span>{formatTime(h.receivedAt)}</span>
                              <span>本次不进入业务处理</span>
                              <span>
                                首次处理：
                                <Badge tone={statusTone[h.firstStatus] ?? 'neutral'}>
                                  {statusLabel[h.firstStatus] ?? h.firstStatus}
                                </Badge>
                              </span>
                              {h.firstLastError && <span>{h.firstLastError}</span>}
                            </li>
                          ))}
                        </ul>
                        <p className="text-copy-12 text-muted-foreground">
                          首次处理结果{' '}
                          {detail.deliveries[0]
                            ? `${statusLabel[detail.deliveries[0].status] ?? detail.deliveries[0].status}，${formatTime(detail.deliveries[0].processedAt ?? detail.deliveries[0].processingStartedAt)}`
                            : '未知'}
                          。重复的这些投递不会产生第二条业务记录。
                        </p>
                      </>
                    )}
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      {can('channel.sync.update') && events.some((e) => e.processable) && (
        <div className="bg-card rounded-md border p-4">
          <p className="text-copy-14 font-medium">按事件认领</p>
          <p className="text-copy-12 text-muted-foreground mt-1">
            下游业务服务按外部事件 ID 消费时走这条路径，而不是逐条接收记录。重复的投递连同它的
            id 都拿不到，没有路径能创建出第二条业务记录；同一事件第二次认领会被拒绝。
          </p>
          <ul className="mt-2 space-y-1">
            {events
              .filter((e) => e.processable)
              .slice(0, 10)
              .map((e) => (
                <li
                  key={e.firstReceiptId}
                  className="text-copy-13 flex flex-wrap items-center justify-between gap-2"
                >
                  <span className="break-all">
                    {e.eventType || e.topic || '未标注类型'} ·{' '}
                    {e.externalEventId || '（平台未带事件 ID）'}
                  </span>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={busy}
                    onClick={() =>
                      void run(
                        () =>
                          api(`channel/webhook-dedup/${e.firstReceiptId}/claim`, 'POST', {}),
                        '已按业务事件认领，同一事件再次认领会被拒绝',
                      )
                    }
                  >
                    认领
                  </Button>
                </li>
              ))}
          </ul>
        </div>
      )}
    </div>
  );
}
