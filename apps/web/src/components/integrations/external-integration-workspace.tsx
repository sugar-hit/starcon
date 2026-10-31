'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  Segmented,
  StatCard,
} from '@starcon/ui';
import { Boxes, CircleAlert, ClipboardList, Loader2, Plug, RefreshCw, Unplug } from 'lucide-react';
import {
  ADAPTERS,
  CATEGORIES,
  DIRECTIONS,
  FAILURE_CLASSES,
  MANUAL_RECORD_STATUSES,
  TASK_TYPES,
  adaptersForCategory,
  categoryForAdapter,
  disableConnection,
  disableSyncTask,
  directSyncAvailable,
  emptyHint,
  failureClassLabel,
  fetchAvailability,
  fetchCapabilities,
  fetchConnections,
  fetchOverview,
  fetchSyncRecords,
  fetchSyncTasks,
  fetchUpstreamPlatforms,
  fetchUpstreamShops,
  formatCount,
  formatDateTime,
  healthLabel,
  healthTone,
  providerNoticeText,
  recordExternalSync,
  refreshHealth,
  saveConnection,
  saveSyncTask,
  syncStatusLabel,
  syncStatusTone,
  taskTypesForCategory,
  unavailableHint,
  unnotified,
  validateConnection,
  validateExternalRecord,
  validateSyncTask,
  type ConnectionForm,
  type ExternalRecordForm,
  type SyncTaskForm,
  type Tone,
} from './external-integration-model';
import type {
  ExternalAdapterAvailability,
  ExternalConnectionRow,
  ExternalIntegrationCapabilities,
  ExternalIntegrationOverview,
  ExternalSyncRecordRow,
  ExternalSyncTaskRow,
  UpstreamChannelPlatformRow,
  UpstreamChannelShopRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 18.2.3.1「其他对接」工作区（卷 18 前端入口 `/integrations/connectors/external-integrations`）。
 *
 * <p>🔴 页面上的六条纪律：
 *
 * 1. **未接入就明说**。本仓没有任何真实的外部系统凭据与网络通道，六个适配器
 *    `connected=false`：页面**不提供**必然 409 的「发起同步」按钮
 *    （一个点了必然失败的按钮会让运营以为「点一下就能同步」）。
 *    与外部系统有没有直连通道，**不影响**下面这些**本系统内部能力**的真实生效：
 *    对接连接配置（密钥只存引用）、同步任务配置、每个对接**独立**的健康状态、
 *    同步记录台账、以及「外部侧已完成」的同步**人工登记**（今天就能用）。
 * 2. **「本地台账成功」≠「外部侧已登记」**。人工登记的行显示
 *    「外部侧已完成 · 人工登记」+「未与外部系统核对过」—— 两格都要在屏幕上。
 * 3. **每个对接的健康状态是独立的**。概览里是**逐档的数**
 *    （健康 / 亚健康 / 不健康 / 未接入），**没有**「整体健康」这一格 ——
 *    一个对接挂了不能显示成「全部都不健康」。
 * 4. **密钥只存引用**。配置表单里只有「密钥引用」与「末位提示」，
 *    没有任何「粘贴 API Key」的地方。
 * 5. **上游只读**。渠道平台与店铺来自 2 渠道机制（channel 服务），只读、读不到就明说。
 * 6. **加载 / 空 / 错误三态齐全**：空态区分「还没登记过」与「筛选无结果」。
 */

type Tab = 'connections' | 'tasks' | 'records' | 'upstream' | 'capabilities';

/** 语气 → Badge 的 tone。 */
const BADGE_TONE: Record<Tone, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

const EMPTY_CONNECTION: ConnectionForm = {
  category: 'SUPPLY_SOURCE',
  adapterCode: 'ALIBABA_1688',
  name: '',
  baseUrl: '',
  credentialRef: '',
  credentialHint: '',
  channelPlatformCode: '',
  channelShopId: '',
  status: 'ACTIVE',
  timeoutSeconds: '15',
  maxAttempts: '3',
  remark: '',
};

const EMPTY_TASK: SyncTaskForm = {
  connectionId: '',
  taskType: 'CATALOG_SYNC',
  direction: 'PULL',
  name: '',
  resourceRef: '',
  scheduleKind: 'MANUAL',
  scheduleExpression: '',
  intervalSeconds: '',
  enabled: true,
  remark: '',
};

const EMPTY_RECORD: ExternalRecordForm = {
  taskId: '',
  status: 'SUCCEEDED',
  recordsTotal: '0',
  recordsSucceeded: '0',
  recordsFailed: '0',
  externalRef: '',
  failureReason: '',
  failureClass: '',
  startedAt: '',
  finishedAt: '',
};

const selectClass =
  'border-border bg-background text-foreground h-9 w-full rounded-md border px-2 text-sm';

export function ExternalIntegrationWorkspace() {
  const [tab, setTab] = useState<Tab>('connections');

  const [capabilities, setCapabilities] = useState<ExternalIntegrationCapabilities | null>(null);
  const [overview, setOverview] = useState<ExternalIntegrationOverview | null>(null);
  const [availability, setAvailability] = useState<ExternalAdapterAvailability[]>([]);
  const [connections, setConnections] = useState<ExternalConnectionRow[]>([]);
  const [tasks, setTasks] = useState<ExternalSyncTaskRow[]>([]);
  const [records, setRecords] = useState<ExternalSyncRecordRow[]>([]);
  const [platforms, setPlatforms] = useState<UpstreamChannelPlatformRow[]>([]);
  const [shops, setShops] = useState<UpstreamChannelShopRow[]>([]);

  const [category, setCategory] = useState('');
  const [adapterCode, setAdapterCode] = useState('');
  const [status, setStatus] = useState('');
  const [healthStatus, setHealthStatus] = useState('');
  const [keyword, setKeyword] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [upstreamError, setUpstreamError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [connectionForm, setConnectionForm] = useState<ConnectionForm>(EMPTY_CONNECTION);
  const [taskForm, setTaskForm] = useState<SyncTaskForm>(EMPTY_TASK);
  const [recordForm, setRecordForm] = useState<ExternalRecordForm>(EMPTY_RECORD);

  const filtered = useMemo(
    () => Boolean(category || adapterCode || status || healthStatus || keyword.trim()),
    [category, adapterCode, status, healthStatus, keyword],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, ov, avail, conns, taskRows, recordRows] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(),
        fetchAvailability(),
        fetchConnections({
          category: category || undefined,
          adapterCode: adapterCode || undefined,
          status: status || undefined,
          healthStatus: healthStatus || undefined,
          keyword: keyword.trim() || undefined,
        }),
        fetchSyncTasks({ status: status || undefined, keyword: keyword.trim() || undefined }),
        fetchSyncRecords({ keyword: keyword.trim() || undefined }),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setAvailability(avail);
      setConnections(conns);
      setTasks(taskRows);
      setRecords(recordRows);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [category, adapterCode, status, healthStatus, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadUpstream = useCallback(async () => {
    setUpstreamError(null);
    try {
      const [p, s] = await Promise.all([fetchUpstreamPlatforms(), fetchUpstreamShops()]);
      setPlatforms(p);
      setShops(s);
    } catch (e) {
      setUpstreamError(e instanceof Error ? e.message : '上游 2 渠道机制暂时读不到，请稍后重试');
    }
  }, []);

  useEffect(() => {
    if (tab === 'upstream') void loadUpstream();
  }, [tab, loadUpstream]);

  // ---------------------------------------------------------------- 写入

  const submitConnection = async () => {
    const problem = validateConnection(connectionForm);
    if (problem) {
      setFormError(problem);
      return;
    }
    setBusy(true);
    setFormError(null);
    setNotice(null);
    try {
      await saveConnection({
        id: null,
        category: connectionForm.category,
        adapterCode: connectionForm.adapterCode,
        name: connectionForm.name.trim(),
        baseUrl: connectionForm.baseUrl.trim(),
        credentialRef: connectionForm.credentialRef.trim(),
        credentialHint: connectionForm.credentialHint.trim(),
        channelPlatformCode: connectionForm.channelPlatformCode.trim(),
        channelShopId: connectionForm.channelShopId.trim() || null,
        status: connectionForm.status,
        timeoutSeconds: Number(connectionForm.timeoutSeconds),
        maxAttempts: Number(connectionForm.maxAttempts),
        remark: connectionForm.remark.trim(),
      });
      setConnectionForm(EMPTY_CONNECTION);
      setNotice('对接连接配置已登记。');
      await load();
    } catch (e) {
      setFormError(e instanceof Error ? e.message : '保存失败，请检查输入后重试');
    } finally {
      setBusy(false);
    }
  };

  const submitTask = async () => {
    const problem = validateSyncTask(taskForm);
    if (problem) {
      setFormError(problem);
      return;
    }
    setBusy(true);
    setFormError(null);
    setNotice(null);
    try {
      await saveSyncTask({
        id: null,
        connectionId: taskForm.connectionId,
        taskType: taskForm.taskType,
        direction: taskForm.direction,
        name: taskForm.name.trim(),
        resourceRef: taskForm.resourceRef.trim(),
        scheduleKind: taskForm.scheduleKind,
        scheduleExpression: taskForm.scheduleExpression.trim(),
        intervalSeconds:
          taskForm.scheduleKind === 'INTERVAL' ? Number(taskForm.intervalSeconds) : null,
        enabled: taskForm.enabled,
        status: 'ACTIVE',
        remark: taskForm.remark.trim(),
      });
      setTaskForm({ ...EMPTY_TASK, connectionId: taskForm.connectionId });
      setNotice('同步任务已登记。');
      await load();
    } catch (e) {
      setFormError(e instanceof Error ? e.message : '保存失败，请检查输入后重试');
    } finally {
      setBusy(false);
    }
  };

  const submitRecord = async () => {
    const problem = validateExternalRecord(recordForm);
    if (problem) {
      setFormError(problem);
      return;
    }
    setBusy(true);
    setFormError(null);
    setNotice(null);
    try {
      const row = await recordExternalSync({
        taskId: recordForm.taskId,
        status: recordForm.status,
        recordsTotal: Number(recordForm.recordsTotal || '0'),
        recordsSucceeded: Number(recordForm.recordsSucceeded || '0'),
        recordsFailed: Number(recordForm.recordsFailed || '0'),
        externalRef: recordForm.externalRef.trim(),
        failureReason: recordForm.failureReason.trim(),
        failureClass: recordForm.failureClass.trim(),
        providerNotice: '',
        startedAt: recordForm.startedAt ? new Date(recordForm.startedAt).toISOString() : null,
        finishedAt: recordForm.finishedAt ? new Date(recordForm.finishedAt).toISOString() : null,
      });
      setRecordForm({ ...EMPTY_RECORD, taskId: recordForm.taskId });
      setNotice(
        `已登记一条「外部侧已完成」的同步记录（${row.statusLabel}）。本系统未与外部系统核对过。`,
      );
      await load();
    } catch (e) {
      setFormError(e instanceof Error ? e.message : '登记失败，请检查输入后重试');
    } finally {
      setBusy(false);
    }
  };

  const act = async (work: () => Promise<unknown>, ok: string) => {
    setBusy(true);
    setFormError(null);
    setNotice(null);
    try {
      await work();
      setNotice(ok);
      await load();
    } catch (e) {
      setFormError(e instanceof Error ? e.message : '操作失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  };

  // ---------------------------------------------------------------- 渲染

  const healthStats = overview
    ? [
        { label: '对接连接', value: overview.connections },
        { label: '启用中', value: overview.activeConnections },
        { label: '健康', value: overview.healthyConnections },
        { label: '亚健康', value: overview.degradedConnections },
        { label: '不健康', value: overview.unhealthyConnections },
        { label: '未接入', value: overview.notConnectedConnections },
        { label: '同步任务', value: overview.syncTasks },
        { label: '同步记录', value: overview.syncRecords },
      ]
    : [];

  const notConnectedHint = unavailableHint(availability);

  return (
    <div className="space-y-4">
      <PageHeader
        title="其他对接"
        description="1688 / 淘宝货源平台、海外仓服务商、物流商、聚合认证服务、自建 BI —— 统一管理连接配置与同步健康。"
        actions={
          <Button
            variant="outline"
            size="sm"
            onClick={() => void load()}
            disabled={loading || busy}
          >
            {loading ? (
              <Loader2 className="mr-1.5 size-4 animate-spin" />
            ) : (
              <RefreshCw className="mr-1.5 size-4" />
            )}
            刷新
          </Button>
        }
      />

      {notConnectedHint ? (
        <Notice tone="warning" title="外部通道未接入">
          <p>{notConnectedHint}</p>
          <p className="mt-1">
            与外部系统有没有直连通道，<strong>不影响</strong>连接配置、同步任务配置、
            每个对接独立的健康状态、同步记录台账，以及「外部侧已完成」的同步人工登记 ——
            这几项今天就能用。
          </p>
        </Notice>
      ) : null}

      {notice ? (
        <Notice tone="success" title="操作完成">
          <p>{notice}</p>
        </Notice>
      ) : null}

      {error ? (
        <Notice tone="danger" title="加载失败">
          <p>{error}</p>
        </Notice>
      ) : null}

      {formError ? (
        <Notice tone="danger" title="提交被挡下了">
          <p>{formError}</p>
        </Notice>
      ) : null}

      {overview ? (
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
          {healthStats.map((s) => (
            <StatCard key={s.label} label={s.label} value={formatCount(s.value)} />
          ))}
        </div>
      ) : null}

      <Segmented
        value={tab}
        onChange={(v) => setTab(v)}
        aria-label="其他对接视图"
        options={[
          { value: 'connections', label: '对接连接', icon: <Plug className="size-4" /> },
          { value: 'tasks', label: '同步任务', icon: <Boxes className="size-4" /> },
          { value: 'records', label: '同步记录', icon: <ClipboardList className="size-4" /> },
          { value: 'upstream', label: '上游渠道', icon: <Unplug className="size-4" /> },
          { value: 'capabilities', label: '能力声明', icon: <CircleAlert className="size-4" /> },
        ]}
      />

      {/* ============================================================ 对接连接 */}
      {tab === 'connections' ? (
        <div className="space-y-4">
          <SectionCard
            title="筛选"
            description="筛选与关键词都是真筛选（后端 SQL 过滤，不是前端过滤）。"
          >
            <div className="grid gap-3 md:grid-cols-5">
              <label className="block space-y-1">
                <span className="text-label-13 text-foreground">对接类别</span>
                <select
                  aria-label="对接类别"
                  className={selectClass}
                  value={category}
                  onChange={(e) => setCategory(e.target.value)}
                >
                  <option value="">全部</option>
                  {CATEGORIES.map((c) => (
                    <option key={c.code} value={c.code}>
                      {c.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block space-y-1">
                <span className="text-label-13 text-foreground">适配器</span>
                <select
                  aria-label="适配器"
                  className={selectClass}
                  value={adapterCode}
                  onChange={(e) => setAdapterCode(e.target.value)}
                >
                  <option value="">全部</option>
                  {ADAPTERS.map((a) => (
                    <option key={a.code} value={a.code}>
                      {a.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block space-y-1">
                <span className="text-label-13 text-foreground">状态</span>
                <select
                  aria-label="连接状态"
                  className={selectClass}
                  value={status}
                  onChange={(e) => setStatus(e.target.value)}
                >
                  <option value="">全部</option>
                  <option value="ACTIVE">启用</option>
                  <option value="DISABLED">已停用</option>
                </select>
              </label>
              <label className="block space-y-1">
                <span className="text-label-13 text-foreground">健康状态</span>
                <select
                  aria-label="健康状态"
                  className={selectClass}
                  value={healthStatus}
                  onChange={(e) => setHealthStatus(e.target.value)}
                >
                  <option value="">全部</option>
                  {['HEALTHY', 'DEGRADED', 'UNHEALTHY', 'UNKNOWN', 'NOT_CONNECTED'].map((h) => (
                    <option key={h} value={h}>
                      {healthLabel(h)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block space-y-1">
                <span className="text-label-13 text-foreground">关键词</span>
                <Input
                  aria-label="连接关键词"
                  placeholder="配置名 / 备注 / 接口地址"
                  value={keyword}
                  onChange={(e) => setKeyword(e.target.value)}
                />
              </label>
            </div>
          </SectionCard>

          <SectionCard
            title="对接连接"
            description="🔴 每一条连接有**自己**的健康状态 —— 一个对接挂了不会把别的对接带成异常。"
          >
            {loading ? (
              <div className="text-muted-foreground flex items-center gap-2">
                <Loader2 className="size-4 animate-spin" /> 正在加载…
              </div>
            ) : connections.length === 0 ? (
              <EmptyState
                icon={<Plug className="size-6" />}
                title="没有对接连接"
                description={emptyHint('connections', filtered)}
              />
            ) : (
              <ul className="space-y-3">
                {connections.map((row) => (
                  <li key={row.id} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-label-13 text-foreground font-semibold">
                        {row.name}
                      </span>
                      <Badge tone="neutral">{row.categoryLabel}</Badge>
                      <Badge tone="neutral">{row.adapterLabel}</Badge>
                      {/* 🔴 健康状态是这一行自己的，不是整体。 */}
                      <Badge tone={BADGE_TONE[healthTone(row.healthStatus)]}>
                        {row.healthStatusLabel}
                      </Badge>
                      <Badge tone={row.status === 'ACTIVE' ? 'success' : 'neutral'}>
                        {row.statusLabel}
                      </Badge>
                    </div>
                    <dl className="text-copy-12 text-muted-foreground mt-2 grid gap-1 md:grid-cols-3">
                      <div>
                        <dt className="inline">密钥：</dt>
                        <dd className="inline">
                          {row.credentialConfigured
                            ? `${row.credentialRef}（末位 ${row.credentialHint}）`
                            : '未配置（只有引用，没有密钥本体）'}
                        </dd>
                      </div>
                      <div>
                        <dt className="inline">上游店铺：</dt>
                        <dd className="inline">
                          {row.channelShopId
                            ? `${row.channelPlatformCode || '渠道'} · ${row.channelShopId}`
                            : '未绑定'}
                        </dd>
                      </div>
                      <div>
                        <dt className="inline">最近一次同步：</dt>
                        <dd className="inline">{formatDateTime(row.lastSyncAt)}</dd>
                      </div>
                    </dl>
                    <div className="mt-2 flex flex-wrap gap-2">
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={busy}
                        onClick={() =>
                          void act(
                            () => refreshHealth(row.id),
                            `已重算「${row.name}」的健康状态（只改这一条）。`,
                          )
                        }
                      >
                        重算这个对接的健康
                      </Button>
                      {row.status === 'ACTIVE' ? (
                        <Button
                          variant="ghost"
                          size="sm"
                          disabled={busy}
                          onClick={() =>
                            void act(
                              () => disableConnection(row.id),
                              `已停用「${row.name}」（不删行，历史同步记录保留）。`,
                            )
                          }
                        >
                          停用
                        </Button>
                      ) : null}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>

          <SectionCard
            title="登记对接连接"
            description="🔴 密钥只存**引用**（密钥管理器里的 ID）—— 这一页没有任何粘贴密钥的地方。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <FormField id="connection-category" label="对接类别" required>
                <select
                  id="connection-category"
                  aria-label="登记对接类别"
                  className={selectClass}
                  value={connectionForm.category}
                  onChange={(e) => {
                    const next = e.target.value;
                    const first = adaptersForCategory(next)[0];
                    setConnectionForm({
                      ...connectionForm,
                      category: next,
                      adapterCode: first ? first.code : connectionForm.adapterCode,
                    });
                  }}
                >
                  {CATEGORIES.map((c) => (
                    <option key={c.code} value={c.code}>
                      {c.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="connection-adapter" label="适配器" required>
                <select
                  id="connection-adapter"
                  aria-label="登记适配器"
                  className={selectClass}
                  value={connectionForm.adapterCode}
                  onChange={(e) =>
                    setConnectionForm({ ...connectionForm, adapterCode: e.target.value })
                  }
                >
                  {adaptersForCategory(connectionForm.category).map((a) => (
                    <option key={a.code} value={a.code}>
                      {a.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="connection-name" label="配置名" required>
                <Input
                  id="connection-name"
                  aria-label="配置名"
                  value={connectionForm.name}
                  onChange={(e) => setConnectionForm({ ...connectionForm, name: e.target.value })}
                />
              </FormField>
              <FormField id="connection-base-url" label="接口地址" hint="http:// 或 https:// 开头">
                <Input
                  id="connection-base-url"
                  aria-label="接口地址"
                  value={connectionForm.baseUrl}
                  onChange={(e) =>
                    setConnectionForm({ ...connectionForm, baseUrl: e.target.value })
                  }
                />
              </FormField>
              <FormField
                id="connection-credential-ref"
                label="密钥引用"
                hint="密钥管理器里的引用 ID —— 不是密钥本体"
              >
                <Input
                  id="connection-credential-ref"
                  aria-label="密钥引用"
                  placeholder="secret://external/1688/main"
                  value={connectionForm.credentialRef}
                  onChange={(e) =>
                    setConnectionForm({ ...connectionForm, credentialRef: e.target.value })
                  }
                />
              </FormField>
              <FormField
                id="connection-credential-hint"
                label="密钥末位提示"
                hint="与引用同时填或同时不填"
              >
                <Input
                  id="connection-credential-hint"
                  aria-label="密钥末位提示"
                  value={connectionForm.credentialHint}
                  onChange={(e) =>
                    setConnectionForm({ ...connectionForm, credentialHint: e.target.value })
                  }
                />
              </FormField>
              <FormField
                id="connection-shop"
                label="上游店铺 id"
                hint="2 渠道机制的店铺（只读引用）"
              >
                <Input
                  id="connection-shop"
                  aria-label="上游店铺 id"
                  value={connectionForm.channelShopId}
                  onChange={(e) =>
                    setConnectionForm({ ...connectionForm, channelShopId: e.target.value })
                  }
                />
              </FormField>
              <FormField id="connection-timeout" label="超时秒数" required>
                <Input
                  id="connection-timeout"
                  aria-label="超时秒数"
                  type="number"
                  value={connectionForm.timeoutSeconds}
                  onChange={(e) =>
                    setConnectionForm({ ...connectionForm, timeoutSeconds: e.target.value })
                  }
                />
              </FormField>
              <FormField id="connection-attempts" label="最大重试次数" required>
                <Input
                  id="connection-attempts"
                  aria-label="最大重试次数"
                  type="number"
                  value={connectionForm.maxAttempts}
                  onChange={(e) =>
                    setConnectionForm({ ...connectionForm, maxAttempts: e.target.value })
                  }
                />
              </FormField>
            </div>
            <div className="mt-3">
              <Button disabled={busy} onClick={() => void submitConnection()}>
                登记连接配置
              </Button>
            </div>
          </SectionCard>
        </div>
      ) : null}

      {/* ============================================================ 同步任务 */}
      {tab === 'tasks' ? (
        <div className="space-y-4">
          <SectionCard
            title="同步任务"
            description="一个连接一个任务类型 —— 两个对接的任务互不影响。"
          >
            {loading ? (
              <div className="text-muted-foreground flex items-center gap-2">
                <Loader2 className="size-4 animate-spin" /> 正在加载…
              </div>
            ) : tasks.length === 0 ? (
              <EmptyState
                icon={<Boxes className="size-6" />}
                title="没有同步任务"
                description={emptyHint('tasks', filtered)}
              />
            ) : (
              <ul className="space-y-2">
                {tasks.map((row) => (
                  <li
                    key={row.id}
                    className="border-border flex flex-wrap items-center gap-2 rounded-md border p-3"
                  >
                    <span className="text-label-13 text-foreground font-semibold">{row.name}</span>
                    <Badge tone="neutral">{row.taskTypeLabel}</Badge>
                    <Badge tone="neutral">{row.directionLabel}</Badge>
                    <Badge tone={row.status === 'ACTIVE' ? 'success' : 'neutral'}>
                      {row.statusLabel}
                    </Badge>
                    <span className="text-copy-12 text-muted-foreground">
                      对接：{row.connectionName} · 调度：{row.scheduleKind}
                    </span>
                    <span className="text-copy-12 text-muted-foreground">
                      最近一次：{row.lastRunStatusLabel}（{formatDateTime(row.lastRunAt)}）
                    </span>
                    {row.status === 'ACTIVE' ? (
                      <Button
                        variant="ghost"
                        size="sm"
                        disabled={busy}
                        onClick={() =>
                          void act(
                            () => disableSyncTask(row.id),
                            `已停用同步任务「${row.name}」（不删行，历史记录保留）。`,
                          )
                        }
                      >
                        停用
                      </Button>
                    ) : null}
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>

          <SectionCard
            title="登记同步任务"
            description="任务类型必须与对接类别相容 —— 否则配出来的是一个永远跑不通的任务。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <FormField id="task-connection" label="对接连接" required>
                <select
                  id="task-connection"
                  aria-label="对接连接"
                  className={selectClass}
                  value={taskForm.connectionId}
                  onChange={(e) => {
                    const id = e.target.value;
                    const row = connections.find((c) => c.id === id);
                    const types = row ? taskTypesForCategory(row.category) : TASK_TYPES;
                    setTaskForm({
                      ...taskForm,
                      connectionId: id,
                      taskType: types[0] ? types[0].code : taskForm.taskType,
                    });
                  }}
                >
                  <option value="">请选择</option>
                  {connections.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}（{c.categoryLabel}）
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="task-type" label="同步类型" required>
                <select
                  id="task-type"
                  aria-label="同步类型"
                  className={selectClass}
                  value={taskForm.taskType}
                  onChange={(e) => setTaskForm({ ...taskForm, taskType: e.target.value })}
                >
                  {TASK_TYPES.map((t) => (
                    <option key={t.code} value={t.code}>
                      {t.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="task-direction" label="方向" required>
                <select
                  id="task-direction"
                  aria-label="同步方向"
                  className={selectClass}
                  value={taskForm.direction}
                  onChange={(e) => setTaskForm({ ...taskForm, direction: e.target.value })}
                >
                  {DIRECTIONS.map((d) => (
                    <option key={d.code} value={d.code}>
                      {d.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="task-name" label="任务名" required>
                <Input
                  id="task-name"
                  aria-label="任务名"
                  value={taskForm.name}
                  onChange={(e) => setTaskForm({ ...taskForm, name: e.target.value })}
                />
              </FormField>
              <FormField
                id="task-resource"
                label="同步对象"
                hint="外部系统侧的标识（店铺代码 / 数据集名…）"
              >
                <Input
                  id="task-resource"
                  aria-label="同步对象"
                  value={taskForm.resourceRef}
                  onChange={(e) => setTaskForm({ ...taskForm, resourceRef: e.target.value })}
                />
              </FormField>
              <FormField id="task-schedule" label="调度方式" required>
                <select
                  id="task-schedule"
                  aria-label="调度方式"
                  className={selectClass}
                  value={taskForm.scheduleKind}
                  onChange={(e) => setTaskForm({ ...taskForm, scheduleKind: e.target.value })}
                >
                  <option value="MANUAL">只手动触发</option>
                  <option value="INTERVAL">固定间隔</option>
                  <option value="CRON">cron 表达式</option>
                </select>
              </FormField>
              {taskForm.scheduleKind === 'INTERVAL' ? (
                <FormField id="task-interval" label="间隔秒数" required>
                  <Input
                    id="task-interval"
                    aria-label="间隔秒数"
                    type="number"
                    value={taskForm.intervalSeconds}
                    onChange={(e) => setTaskForm({ ...taskForm, intervalSeconds: e.target.value })}
                  />
                </FormField>
              ) : null}
              {taskForm.scheduleKind === 'CRON' ? (
                <FormField id="task-cron" label="调度表达式" required>
                  <Input
                    id="task-cron"
                    aria-label="调度表达式"
                    value={taskForm.scheduleExpression}
                    onChange={(e) =>
                      setTaskForm({ ...taskForm, scheduleExpression: e.target.value })
                    }
                  />
                </FormField>
              ) : null}
            </div>
            <div className="mt-3">
              <Button disabled={busy} onClick={() => void submitTask()}>
                登记同步任务
              </Button>
            </div>
          </SectionCard>
        </div>
      ) : null}

      {/* ============================================================ 同步记录 */}
      {tab === 'records' ? (
        <div className="space-y-4">
          <SectionCard
            title="同步记录台账"
            description="🔴 「本地台账成功」≠「外部侧已登记」—— 每一行都标着有没有与外部系统核对过。"
          >
            {loading ? (
              <div className="text-muted-foreground flex items-center gap-2">
                <Loader2 className="size-4 animate-spin" /> 正在加载…
              </div>
            ) : records.length === 0 ? (
              <EmptyState
                icon={<ClipboardList className="size-6" />}
                title="没有同步记录"
                description={emptyHint('records', filtered)}
              />
            ) : (
              <ul className="space-y-2">
                {records.map((row) => (
                  <li key={row.id} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-label-13 text-foreground font-semibold">
                        {row.taskName}
                      </span>
                      <Badge tone={BADGE_TONE[syncStatusTone(row.status)]}>{row.statusLabel}</Badge>
                      <Badge tone="neutral">{row.sourceLabel}</Badge>
                      {/* 🔴 人工登记的行永远标「未与外部系统核对过」。 */}
                      {unnotified(row) ? <Badge tone="warning">未与外部系统核对过</Badge> : null}
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      对接：{row.connectionName} · 第 {row.runNo} 次 · 处理{' '}
                      {formatCount(row.recordsTotal)} 条（成功 {formatCount(row.recordsSucceeded)} ·
                      失败 {formatCount(row.recordsFailed)}）
                      {row.externalRef ? ` · 外部单号 ${row.externalRef}` : ''}
                    </p>
                    <p className="text-copy-12 text-muted-foreground">{providerNoticeText(row)}</p>
                    {row.failureReason ? (
                      <p className="text-copy-12 text-destructive mt-1">
                        {failureClassLabel(row.failureClass)}：{row.failureReason}
                      </p>
                    ) : null}
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>

          <SectionCard
            title="登记「外部侧已完成」的同步"
            description="外部系统那边跑完同步（运营在对方门户里点的），在这里补一条记录 —— 这个对接的健康状态就如实反映现状。"
          >
            <Notice tone="info" title="这一格记的是本系统自己的账">
              <p>
                登记进去的行标着「外部侧已完成 · 人工登记」与
                <strong>「未与外部系统核对过」</strong> —— 它不能被读成「已向外部系统登记」。
              </p>
            </Notice>
            <div className="mt-3 grid gap-3 md:grid-cols-3">
              <FormField id="record-task" label="同步任务" required>
                <select
                  id="record-task"
                  aria-label="同步任务"
                  className={selectClass}
                  value={recordForm.taskId}
                  onChange={(e) => setRecordForm({ ...recordForm, taskId: e.target.value })}
                >
                  <option value="">请选择</option>
                  {tasks.map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.name}（{t.connectionName}）
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="record-status" label="外部侧结论" required>
                <select
                  id="record-status"
                  aria-label="外部侧结论"
                  className={selectClass}
                  value={recordForm.status}
                  onChange={(e) => setRecordForm({ ...recordForm, status: e.target.value })}
                >
                  {MANUAL_RECORD_STATUSES.map((s) => (
                    <option key={s.code} value={s.code}>
                      {s.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="record-external-ref" label="外部单号">
                <Input
                  id="record-external-ref"
                  aria-label="外部单号"
                  value={recordForm.externalRef}
                  onChange={(e) => setRecordForm({ ...recordForm, externalRef: e.target.value })}
                />
              </FormField>
              <FormField id="record-total" label="处理条数">
                <Input
                  id="record-total"
                  aria-label="处理条数"
                  type="number"
                  value={recordForm.recordsTotal}
                  onChange={(e) => setRecordForm({ ...recordForm, recordsTotal: e.target.value })}
                />
              </FormField>
              <FormField id="record-succeeded" label="成功条数">
                <Input
                  id="record-succeeded"
                  aria-label="成功条数"
                  type="number"
                  value={recordForm.recordsSucceeded}
                  onChange={(e) =>
                    setRecordForm({ ...recordForm, recordsSucceeded: e.target.value })
                  }
                />
              </FormField>
              <FormField id="record-failed" label="失败条数">
                <Input
                  id="record-failed"
                  aria-label="失败条数"
                  type="number"
                  value={recordForm.recordsFailed}
                  onChange={(e) => setRecordForm({ ...recordForm, recordsFailed: e.target.value })}
                />
              </FormField>
              <FormField id="record-failure-class" label="失败分类">
                <select
                  id="record-failure-class"
                  aria-label="失败分类"
                  className={selectClass}
                  value={recordForm.failureClass}
                  onChange={(e) => setRecordForm({ ...recordForm, failureClass: e.target.value })}
                >
                  <option value="">（无）</option>
                  {FAILURE_CLASSES.map((f) => (
                    <option key={f.code} value={f.code}>
                      {f.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="record-failure-reason" label="失败原因">
                <Input
                  id="record-failure-reason"
                  aria-label="失败原因"
                  value={recordForm.failureReason}
                  onChange={(e) => setRecordForm({ ...recordForm, failureReason: e.target.value })}
                />
              </FormField>
              <FormField id="record-started" label="开始时间" hint="ISO-8601">
                <Input
                  id="record-started"
                  aria-label="开始时间"
                  placeholder="2026-10-31T00:00:00Z"
                  value={recordForm.startedAt}
                  onChange={(e) => setRecordForm({ ...recordForm, startedAt: e.target.value })}
                />
              </FormField>
              <FormField id="record-finished" label="结束时间" hint="ISO-8601">
                <Input
                  id="record-finished"
                  aria-label="结束时间"
                  placeholder="2026-10-31T00:05:00Z"
                  value={recordForm.finishedAt}
                  onChange={(e) => setRecordForm({ ...recordForm, finishedAt: e.target.value })}
                />
              </FormField>
            </div>
            <div className="mt-3">
              <Button disabled={busy} onClick={() => void submitRecord()}>
                登记一条外部侧已完成的同步
              </Button>
            </div>
          </SectionCard>
        </div>
      ) : null}

      {/* ============================================================ 上游渠道 */}
      {tab === 'upstream' ? (
        <div className="space-y-4">
          <Notice tone="info" title="上游 = 2 渠道机制（只读）">
            <p>
              下面两组是 channel 服务的授权只读端点（平台目录 / 店铺），
              本模块**不回写**它的库，也不抄店铺档案 —— 连接档案里只存只读引用。
            </p>
          </Notice>
          {upstreamError ? (
            <Notice tone="danger" title="上游读不到">
              <p>{upstreamError}</p>
            </Notice>
          ) : null}
          <SectionCard title="渠道平台目录">
            {platforms.length === 0 ? (
              <EmptyState icon={<Unplug className="size-6" />} title="没有读到平台目录" />
            ) : (
              <ul className="space-y-1">
                {platforms.map((p) => (
                  <li key={p.id} className="text-copy-13 text-foreground flex items-center gap-2">
                    <Badge tone="neutral">{p.code}</Badge>
                    <span>{p.name}</span>
                    <span className="text-muted-foreground">{p.integration}</span>
                    <Badge tone="neutral">{p.supportStatus}</Badge>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>
          <SectionCard title="渠道店铺">
            {shops.length === 0 ? (
              <EmptyState icon={<Unplug className="size-6" />} title="没有读到店铺" />
            ) : (
              <ul className="space-y-1">
                {shops.map((s) => (
                  <li key={s.id} className="text-copy-13 text-foreground flex items-center gap-2">
                    <Badge tone="neutral">{s.code}</Badge>
                    <span>{s.name}</span>
                    <span className="text-muted-foreground">{s.platformName}</span>
                    <Badge tone="neutral">{s.status}</Badge>
                    <span className="text-muted-foreground">
                      最近同步：{formatDateTime(s.lastSyncAt)}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>
        </div>
      ) : null}

      {/* ============================================================ 能力声明 */}
      {tab === 'capabilities' ? (
        <div className="space-y-4">
          <SectionCard
            title="能力声明"
            description="逐条说清「有没有、依据是什么、缺了会怎样、缺在谁身上」。"
          >
            {capabilities ? (
              <ul className="space-y-2">
                {capabilities.capabilities.map((c) => (
                  <li key={c.name} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={c.available ? 'success' : 'warning'}>
                        {c.available ? '可用' : '未接通'}
                      </Badge>
                      <span className="text-label-13 text-foreground font-semibold">{c.name}</span>
                      <span className="text-copy-12 text-muted-foreground">{c.moduleId}</span>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">{c.evidence}</p>
                    {c.note ? (
                      <p className="text-copy-12 text-muted-foreground mt-1">
                        缺了会怎样：{c.note}
                      </p>
                    ) : null}
                    {c.ownerModule ? (
                      <p className="text-copy-12 text-muted-foreground mt-1">
                        缺在哪个模块：{c.ownerModule}
                      </p>
                    ) : null}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-muted-foreground">正在加载…</p>
            )}
          </SectionCard>

          <SectionCard title="逐个外部系统的通道可用性">
            {availability.length === 0 ? (
              <p className="text-muted-foreground">正在加载…</p>
            ) : (
              <ul className="space-y-2">
                {availability.map((a) => (
                  <li key={a.adapterCode} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={a.connected ? 'success' : 'warning'}>
                        {a.connected ? '已接通' : '未接入'}
                      </Badge>
                      <span className="text-label-13 text-foreground font-semibold">
                        {a.adapterLabel}
                      </span>
                      <Badge tone="neutral">{a.categoryLabel}</Badge>
                      <span className="text-copy-12 text-muted-foreground">{a.adapterCode}</span>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">{a.reason}</p>
                    {directSyncAvailable(availability, a.adapterCode) ? (
                      <p className="text-copy-12 text-success mt-1">
                        这个通道已接通，可以发起同步。
                      </p>
                    ) : (
                      <p className="text-copy-12 text-muted-foreground mt-1">
                        页面不提供「发起同步」按钮 —— 点了必然 409，一个必然失败的按钮不如没有。
                        可以走「外部侧已完成」的<strong>人工登记</strong>这条路。
                      </p>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>

          {capabilities && capabilities.gaps.length > 0 ? (
            <SectionCard title="未交付的下游 / 兄弟能力">
              <ul className="space-y-2">
                {capabilities.gaps.map((g) => (
                  <li key={g.name} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone="warning">未交付</Badge>
                      <span className="text-label-13 text-foreground font-semibold">{g.name}</span>
                      <span className="text-copy-12 text-muted-foreground">
                        {g.moduleId} · {g.service} · {g.relation}
                      </span>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">{g.note}</p>
                  </li>
                ))}
              </ul>
            </SectionCard>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
