'use client';

import { useCallback, useEffect, useState } from 'react';
import { Boxes, RefreshCw, Warehouse } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  PageHeader,
  SectionCard,
  StatCard,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@starcon/ui';
import type {
  ExternalStockLatest,
  ExternalStockObservation,
  WarehouseAccount,
  WarehouseConnection,
  WarehouseProvider,
  WarehouseSyncRun,
} from './overseas-warehouse-model';
import {
  connectionStatusLabel,
  fetchAccounts,
  fetchConnections,
  fetchLatestObservations,
  fetchObservations,
  fetchProviders,
  fetchSyncRuns,
  freshness,
  needsAttention,
  quantityText,
  recordInboundLine,
  setConnectionSyncPaused,
  triggerSync,
  warehouseKindLabel,
} from './overseas-warehouse-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 10.4.2.1 海外仓连接。
 *
 * <p>蓝图：「对接海外仓/第三方仓服务商：连接授权、库存同步、订单预报、收发状态回传、
 * 账单对接；管理多仓服务商下的仓账户」。L3 锚点 10.4.2「海外仓对接」。
 *
 * <p>🔴 这个界面上**刻意没有**「修改库存」「删除观测」这类入口。蓝图加粗句要求
 * 「外部仓库存是观测值不是自有账本，必须与自营库存分开存储与对账，不能覆盖」——
 * 一旦界面给出编辑框，就等于在产品层面承认观测值可改写。所以：
 *
 * <ul>
 *   <li>库存区**只读**：只有观测流水与最新观测，没有编辑控件</li>
 *   <li>能写的只有连接（暂停/恢复同步）与仓账户（登记服务商报送）</li>
 *   <li>「最新观测」标注观测时点与新鲜度：外部数陈旧而不知情比没有更危险</li>
 * </ul>
 *
 * <p>本模块服务期落点：连接、仓账户、同步记录、外部库存观测。
 * 订单预报（10.4.2.2）与账单对接不在本页——它们各有各的输入与失败语义，
 * 硬塞进这一页会让「观测」被「推送」淹没。
 */
export function OverseasWarehouseWorkspace() {
  const [providers, setProviders] = useState<WarehouseProvider[]>([]);
  const [connections, setConnections] = useState<WarehouseConnection[]>([]);
  const [accounts, setAccounts] = useState<WarehouseAccount[]>([]);
  const [runs, setRuns] = useState<WarehouseSyncRun[]>([]);
  const [selected, setSelected] = useState<string>('');
  const [observations, setObservations] = useState<ExternalStockObservation[]>([]);
  const [latest, setLatest] = useState<ExternalStockLatest[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [p, c, a, r] = await Promise.all([
        fetchProviders(),
        fetchConnections(),
        fetchAccounts(),
        fetchSyncRuns(),
      ]);
      setProviders(p);
      setConnections(c);
      setAccounts(a);
      setRuns(r);
      setSelected((prev) => (prev && a.some((x) => x.id === prev) ? prev : (a[0]?.id ?? '')));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, []);

  const loadObservations = useCallback(async (accountId: string) => {
    if (!accountId) {
      setObservations([]);
      setLatest([]);
      return;
    }
    try {
      const [o, l] = await Promise.all([
        fetchObservations(accountId),
        fetchLatestObservations(accountId),
      ]);
      setObservations(o);
      setLatest(l);
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void loadObservations(selected);
  }, [selected, loadObservations]);

  async function sync(accountId: string) {
    setBusy(true);
    setError('');
    try {
      await triggerSync(accountId);
      await load();
      await loadObservations(accountId);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function report(accountId: string) {
    // 服务商报送是适配器的真实输入：登记后即可触发一次真实同步。
    const sku = window.prompt('服务商 SKU 编码');
    if (!sku) return;
    const raw = window.prompt('报送数量');
    if (raw === null) return;
    const quantity = Number(raw);
    if (!Number.isFinite(quantity) || quantity < 0) {
      setError('数量必须是非负数');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await recordInboundLine(accountId, {
        externalSku: sku.trim(),
        quantity,
        unit: 'PCS',
        source: 'REPORTED_FEED',
        reportedAt: new Date().toISOString(),
      });
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function togglePaused(connection: WarehouseConnection) {
    setBusy(true);
    setError('');
    try {
      await setConnectionSyncPaused(connection.id, !connection.syncPaused);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const current = accounts.find((a) => a.id === selected);
  const stale = freshness(current?.latestObservedAt);
  const failing = runs.filter(needsAttention).length;

  return (
    <>
      <PageHeader
        title="海外仓连接"
        description="对接海外仓/第三方仓服务商：连接授权、仓账户、库存同步与外部库存观测。"
      />

      {error ? <p className="text-destructive text-sm">{error}</p> : null}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="服务商"
          value={String(providers.length)}
          comparisonLabel="已登记的对接服务商"
        />
        <StatCard
          label="连接"
          value={String(connections.length)}
          comparisonLabel="按租户维护的授权连接"
        />
        <StatCard
          label="仓账户"
          value={String(accounts.length)}
          comparisonLabel="多仓服务商下的物理仓"
        />
        <StatCard
          label="异常同步"
          value={String(failing)}
          comparisonLabel={failing ? '有失败或部分失败的运行' : '最近的同步都正常'}
        />
      </div>

      <Tabs defaultValue="connections" className="mt-6">
        <TabsList>
          <TabsTrigger value="connections">连接</TabsTrigger>
          <TabsTrigger value="accounts">仓账户</TabsTrigger>
          <TabsTrigger value="runs">同步记录</TabsTrigger>
          <TabsTrigger value="observations">外部库存观测</TabsTrigger>
        </TabsList>

        <TabsContent value="connections">
          <SectionCard
            title="服务商连接"
            extra={
              <Button size="sm" variant="ghost" onClick={() => void load()} disabled={busy}>
                <RefreshCw className="mr-1.5 h-4 w-4" />
                刷新
              </Button>
            }
          >
            {connections.length === 0 ? (
              <EmptyState
                icon={<Warehouse className="h-6 w-6" />}
                title="还没有服务商连接"
                description="连接由各租户在服务端维护；未连接前无法同步任何外部库存。"
              />
            ) : (
              <ul className="divide-y">
                {connections.map((c) => (
                  <li key={c.id} className="flex items-start justify-between gap-4 py-3">
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="font-medium">{c.displayName || c.authorizedAccount}</span>
                        <Badge tone="info">{c.providerName}</Badge>
                        <Badge
                          tone={c.status === 'ACTIVE' && !c.syncPaused ? 'success' : 'warning'}
                        >
                          {connectionStatusLabel(c)}
                        </Badge>
                      </div>
                      <p className="text-muted-foreground mt-1 text-sm">
                        账号 {c.authorizedAccount} · 仓账户 {c.accountCount} 个
                        {c.credential ? ` · 凭据 ${c.credential.status}` : ''}
                        {c.lastSyncAt
                          ? ` · 最近同步 ${new Date(c.lastSyncAt).toLocaleString()}`
                          : ' · 尚未同步'}
                      </p>
                      {c.lastError ? (
                        <p className="text-destructive mt-1 text-sm">{c.lastError}</p>
                      ) : null}
                    </div>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => void togglePaused(c)}
                      disabled={busy || c.status === 'REVOKED'}
                    >
                      {c.syncPaused ? '恢复同步' : '暂停同步'}
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>
        </TabsContent>

        <TabsContent value="accounts">
          <SectionCard title="仓账户">
            {accounts.length === 0 ? (
              <EmptyState
                icon={<Boxes className="h-6 w-6" />}
                title="还没有仓账户"
                description="仓账户是多仓服务商下的单个物理仓，同步与账单都以它为单位。"
              />
            ) : (
              <ul className="divide-y">
                {accounts.map((a) => {
                  const f = freshness(a.latestObservedAt);
                  return (
                    <li key={a.id} className="flex items-start justify-between gap-4 py-3">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <span className="font-medium">{a.name || a.externalAccountCode}</span>
                          <Badge tone="info">{warehouseKindLabel(a.warehouseKind)}</Badge>
                          {f.stale ? <Badge tone="neutral">{f.label}</Badge> : null}
                        </div>
                        <p className="text-muted-foreground mt-1 text-sm">
                          {a.connectionName} · {a.externalAccountCode}
                          {a.countryCode ? ` · ${a.countryCode}` : ''} · 观测 SKU{' '}
                          {a.observedSkuCount ?? 0} 个
                        </p>
                      </div>
                      <div className="flex shrink-0 gap-2">
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => void report(a.id)}
                          disabled={busy}
                        >
                          登记报送
                        </Button>
                        <Button size="sm" onClick={() => void sync(a.id)} disabled={busy}>
                          触发同步
                        </Button>
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
          </SectionCard>
        </TabsContent>

        <TabsContent value="runs">
          <SectionCard title="库存同步记录">
            {runs.length === 0 ? (
              <EmptyState
                icon={<RefreshCw className="h-6 w-6" />}
                title="还没有同步记录"
                description="在「仓账户」里触发一次同步即可生成记录。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">仓账户</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">触发</th>
                    <th className="py-2">拉取 / 观测 / 失败</th>
                    <th className="py-2">开始时间</th>
                  </tr>
                </thead>
                <tbody>
                  {runs.map((r) => (
                    <tr key={r.id} className="border-b last:border-0">
                      <td className="py-2">{r.accountName ?? '—'}</td>
                      <td className="py-2">
                        <Badge tone={needsAttention(r) ? 'danger' : 'neutral'}>{r.status}</Badge>
                      </td>
                      <td className="py-2">{r.triggerSource}</td>
                      <td className="py-2">
                        {r.fetchedCount} / {r.observedCount} / {r.failedCount}
                      </td>
                      <td className="py-2">{new Date(r.startedAt).toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>
        </TabsContent>

        <TabsContent value="observations">
          <SectionCard
            title="外部库存观测"
            description="只读。外部仓库存是观测值不是自有账本，本页不提供任何修改或删除入口。"
          >
            <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
              <span className="text-muted-foreground">仓账户</span>
              <select
                className="rounded border px-2 py-1"
                value={selected}
                onChange={(e) => setSelected(e.target.value)}
              >
                {accounts.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name || a.externalAccountCode}
                  </option>
                ))}
              </select>
              <Badge tone={stale.stale ? 'warning' : 'success'}>{stale.label}</Badge>
            </div>

            {!current ? (
              <EmptyState
                icon={<Boxes className="h-6 w-6" />}
                title="先选择一个仓账户"
                description="观测值按仓账户采集，同步后才能看到数据。"
              />
            ) : latest.length === 0 ? (
              <EmptyState
                icon={<Boxes className="h-6 w-6" />}
                title="该仓账户还没有观测值"
                description="登记一条服务商报送后触发同步，这里会出现观测快照。"
              />
            ) : (
              <>
                <h3 className="mb-2 text-sm font-medium">各 SKU 最新观测</h3>
                <table className="mb-6 w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2">SKU</th>
                      <th className="py-2">观测数量</th>
                      <th className="py-2">来源</th>
                      <th className="py-2">观测时点</th>
                    </tr>
                  </thead>
                  <tbody>
                    {latest.map((o) => (
                      <tr key={o.externalSku} className="border-b last:border-0">
                        <td className="py-2 font-mono text-xs">{o.externalSku}</td>
                        <td className="py-2">{quantityText(o.quantity, o.unit)}</td>
                        <td className="text-muted-foreground py-2">{o.source}</td>
                        <td className="py-2">{new Date(o.observedAt).toLocaleString()}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>

                <h3 className="mb-2 text-sm font-medium">观测流水</h3>
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2">SKU</th>
                      <th className="py-2">观测数量</th>
                      <th className="py-2">观测时点</th>
                    </tr>
                  </thead>
                  <tbody>
                    {observations.map((o) => (
                      <tr key={o.id} className="border-b last:border-0">
                        <td className="py-2 font-mono text-xs">{o.externalSku}</td>
                        <td className="py-2">{quantityText(o.quantity, o.unit)}</td>
                        <td className="py-2">{new Date(o.observedAt).toLocaleString()}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}
          </SectionCard>
        </TabsContent>
      </Tabs>
    </>
  );
}
