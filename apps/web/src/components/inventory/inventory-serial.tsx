'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  History,
  PackageCheck,
  RefreshCw,
  ScanLine,
  TriangleAlert,
  XCircle,
} from 'lucide-react';
import { Button, EmptyState, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  LotRef,
  LocationRef,
  SerialIssue,
  SerialMovementPage,
  SerialOperationPage,
  SerialPage,
  SerialProfile,
  SerialStats,
  SerialValidationResult,
  SerialVocabularies,
  SkuRef,
  WarehouseRef,
} from '../../../../../packages/api-contracts/src/index';
import { errorText } from './inventory-model';
import {
  allowedTargetStatuses,
  deleteSerial,
  directionLabels,
  fetchSerialMovements,
  fetchSerialOperations,
  fetchSerialStats,
  fetchSerials,
  fetchSerialVocabularies,
  groupIssues,
  importSerials,
  loadSerialLocations,
  loadSerialLookups,
  loadSerialLots,
  localQuantityGuard,
  moveSerials,
  parseScanInput,
  registerSerial,
  retryableScanText,
  serialQuery,
  statsBalance,
  statusLabels,
  statusTones,
  targetLocationRequired,
  targetStatusRequired,
  transitionText,
  type SerialDirectionCode,
  type SerialStatusCode,
  validateMove,
  type SerialParams,
} from './serial-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.3.2.1 序列号工作区。
 *
 * <p>三个页签按运营的判断顺序排：**档案**（现在有什么）→ **流转历史**（它去过哪）
 * → **出入库操作**（哪一单动了它）。第三个页签不是重复信息：它记录的是
 * 「这一单声明了几件、实际动了几个号」，而这两个数字相等是本模块的硬约束。
 *
 * <p>五条界面纪律：
 *
 * <ul>
 *   <li>**提交前就说清少扫了几台**。蓝图说「不允许大概齐」，收货现场的人是在离开货架之前
 *       才知道少扫了。后端仍然独立校验（V58 的 CHECK + 延迟约束触发器），
 *       前端这道只负责让人当场就知道。
 *   <li>**校验错误按号归因**。「出库失败」四个字对着一百个号毫无用处；
 *       能直接退回重扫的号给一个按钮，不该整单退回的号单独列出来。
 *   <li>**状态分布守恒式画出来**。五档加起来不等于总数时明确警告，
 *       因为那种情况下每个单独的数字都不可信。
 *   <li>**流转历史要显示「从哪到哪」**。只显示新状态会让人以为号一直都在库；
 *       from/to 成对出现才读得懂一次真实的挪动。
 *   <li>**在库/维修必有库位，已售/在途/报废必无**。界面上把空库位显式写成
 *       「不在任何库位」而不是留白 —— 留白会被读成「没查到」。
 * </ul>
 */
export function InventorySerialWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [tab, setTab] = useState<'ARCHIVE' | 'HISTORY' | 'OPERATIONS'>('ARCHIVE');

  const [serials, setSerials] = useState<SerialPage | null>(null);
  const [stats, setStats] = useState<SerialStats | null>(null);
  const [movements, setMovements] = useState<SerialMovementPage | null>(null);
  const [operations, setOperations] = useState<SerialOperationPage | null>(null);
  const [vocabularies, setVocabularies] = useState<SerialVocabularies | null>(null);
  const [warehouses, setWarehouses] = useState<WarehouseRef[]>([]);
  const [skus, setSkus] = useState<SkuRef[]>([]);
  const [locations, setLocations] = useState<LocationRef[]>([]);
  const [lots, setLots] = useState<LotRef[]>([]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [selected, setSelected] = useState<SerialProfile | null>(null);

  const [warehouseId, setWarehouseId] = useState('');
  const [skuId, setSkuId] = useState('');
  const [locationId, setLocationId] = useState('');
  const [lotId, setLotId] = useState('');
  const [status, setStatus] = useState<SerialStatusCode | ''>('');
  const [keyword, setKeyword] = useState('');
  const [sort, setSort] = useState('RECENT');
  const [page, setPage] = useState(0);
  const [historyPage, setHistoryPage] = useState(0);
  const [operationPage, setOperationPage] = useState(0);

  const params: SerialParams = useMemo(() => {
    const filters: SerialParams = { sort, page, size: 50 };
    // 逐个赋值而不是 `x || undefined`：本仓开了 exactOptionalPropertyTypes，
    // 显式 undefined 与「不传这个键」在类型上不是一回事。
    if (warehouseId) filters.warehouseId = warehouseId;
    if (skuId) filters.skuId = skuId;
    if (locationId) filters.locationId = locationId;
    if (lotId) filters.lotId = lotId;
    if (status) filters.status = status;
    if (keyword.trim()) filters.keyword = keyword.trim();
    return filters;
  }, [warehouseId, skuId, locationId, lotId, status, keyword, sort, page]);

  const loadArchive = useCallback(async () => {
    if (!readable) return;
    setLoading(true);
    setError('');
    try {
      const [page_, stats_] = await Promise.all([fetchSerials(params), fetchSerialStats(params)]);
      setSerials(page_);
      setStats(stats_);
    } catch (e) {
      setError(errorText(e));
      setSerials(null);
      setStats(null);
    } finally {
      setLoading(false);
    }
  }, [readable, params]);

  const loadHistory = useCallback(async () => {
    if (!readable) return;
    try {
      setMovements(await fetchSerialMovements({ page: historyPage, size: 50 }));
    } catch (e) {
      setError(errorText(e));
    }
  }, [readable, historyPage]);

  const loadOperations = useCallback(async () => {
    if (!readable) return;
    try {
      setOperations(await fetchSerialOperations({ page: operationPage, size: 50 }));
    } catch (e) {
      setError(errorText(e));
    }
  }, [readable, operationPage]);

  useEffect(() => {
    void loadArchive();
  }, [loadArchive]);

  useEffect(() => {
    if (!readable) return;
    void (async () => {
      try {
        setVocabularies(await fetchSerialVocabularies());
        const [{ warehouses: wh, skus: sk }, locs, ls] = await Promise.all([
          loadSerialLookups(),
          loadSerialLocations(warehouseId || undefined),
          loadSerialLots(skuId || undefined),
        ]);
        setWarehouses(wh);
        setSkus(sk);
        setLocations(locs);
        setLots(ls);
      } catch (e) {
        setError(errorText(e));
      }
    })();
    // 库位与批次按当前筛选取：先选仓再选库位这条下钻路径才在界面上成立
  }, [readable, warehouseId, skuId]);

  useEffect(() => {
    if (tab === 'HISTORY') void loadHistory();
  }, [tab, loadHistory]);

  useEffect(() => {
    if (tab === 'OPERATIONS') void loadOperations();
  }, [tab, loadOperations]);

  const balance = statsBalance(stats);

  if (!readable) {
    return (
      <EmptyState
        icon={<ScanLine className="h-6 w-6" />}
        title="没有序列号的查看权限"
        description="当前角色未被授予 warehouse.inventory.read，请联系管理员在角色中开启该页面。"
      />
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="序列号"
        description="逐件管理设备序列号：扫码录入、批量导入、状态流转、当前位置与批次绑定，出入库时强校验数量与序列号条数一致。"
        actions={
          <div className="flex items-center gap-2">
            <div className="border-control-border flex rounded-md border p-0.5">
              {(
                [
                  ['ARCHIVE', '序列号档案'],
                  ['HISTORY', '流转历史'],
                  ['OPERATIONS', '出入库操作'],
                ] as const
              ).map(([key, label]) => (
                <button
                  key={key}
                  type="button"
                  onClick={() => setTab(key)}
                  aria-pressed={tab === key}
                  className={`rounded px-3 py-1.5 text-sm transition-colors ${
                    tab === key ? 'bg-primary text-primary-foreground' : 'text-muted-foreground'
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>
            <Button
              variant="outline"
              size="sm"
              onClick={() => {
                void loadArchive();
                if (tab === 'HISTORY') void loadHistory();
                if (tab === 'OPERATIONS') void loadOperations();
              }}
              disabled={loading}
            >
              <RefreshCw
                className={loading ? 'mr-2 h-3.5 w-3.5 animate-spin' : 'mr-2 h-3.5 w-3.5'}
              />
              刷新
            </Button>
          </div>
        }
      />

      {error && (
        <div className="border-destructive/40 bg-destructive-soft text-destructive flex items-start gap-3 rounded-md border p-4 text-sm">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <div className="space-y-2">
            <p>{error}</p>
            <Button variant="outline" size="sm" onClick={() => void loadArchive()}>
              重新加载
            </Button>
          </div>
        </div>
      )}

      {notice && (
        <div className="border-success/40 bg-success-soft text-success flex items-start gap-3 rounded-md border p-4 text-sm">
          <PackageCheck className="mt-0.5 h-4 w-4 shrink-0" />
          <p>{notice}</p>
        </div>
      )}

      {stats && (
        <>
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
            <StatCard label="在库" value={stats.inStock} polarity="neutral" />
            <StatCard label="已售" value={stats.sold} polarity="neutral" />
            <StatCard label="在途" value={stats.inTransit} polarity="neutral" />
            <StatCard label="维修" value={stats.repair} polarity="negative" />
            <StatCard label="报废" value={stats.scrapped} polarity="negative" />
          </div>
          {/* 守恒式必须画出来：不守恒时每个单独的数字都不可信 */}
          {!balance.ok && (
            <div className="border-destructive/40 bg-destructive-soft text-destructive flex items-start gap-3 rounded-md border p-4 text-sm">
              <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
              <p>{balance.message}</p>
            </div>
          )}
          <div className="grid gap-4 sm:grid-cols-3">
            <StatCard
              label="档案总数"
              value={stats.total}
              polarity="neutral"
              comparisonLabel={`五档合计 ${balance.sum}`}
            />
            <StatCard
              label="未绑定批次"
              value={stats.unboundCount}
              polarity="neutral"
              comparisonLabel="非批次管理品按设计就该有"
            />
            <StatCard
              label="批次已失效仍挂着"
              value={stats.lotMismatchCount}
              polarity="negative"
              comparisonLabel="批次耗尽/过期，提醒改绑"
            />
          </div>
        </>
      )}

      {tab === 'ARCHIVE' && (
        <>
          <SectionCard title="筛选" description="未勾选的维度不参与过滤；翻页时合计不变。">
            <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-6">
              <SelectField
                label="仓库"
                value={warehouseId}
                onChange={(v) => {
                  setWarehouseId(v);
                  setLocationId('');
                  setPage(0);
                }}
                options={[
                  { value: '', label: '全部仓库' },
                  ...warehouses.map((w) => ({ value: w.id, label: `${w.code} ${w.name}` })),
                ]}
              />
              <SelectField
                label="SKU"
                value={skuId}
                onChange={(v) => {
                  setSkuId(v);
                  setLotId('');
                  setPage(0);
                }}
                options={[
                  { value: '', label: '全部 SKU' },
                  ...skus.map((s) => ({ value: s.id, label: `${s.code} ${s.name}` })),
                ]}
              />
              <SelectField
                label="库位"
                value={locationId}
                onChange={(v) => {
                  setLocationId(v);
                  setPage(0);
                }}
                options={[
                  { value: '', label: '全部库位' },
                  ...locations.map((l) => ({ value: l.id, label: `${l.code} ${l.name}` })),
                ]}
              />
              <SelectField
                label="批次"
                value={lotId}
                onChange={(v) => {
                  setLotId(v);
                  setPage(0);
                }}
                options={[
                  { value: '', label: '全部批次' },
                  ...lots.map((l) => ({ value: l.id, label: l.lotCode })),
                ]}
              />
              <SelectField
                label="状态"
                value={status}
                onChange={(v) => {
                  setStatus(v as SerialStatusCode | '');
                  setPage(0);
                }}
                options={[
                  { value: '', label: '全部状态' },
                  ...(vocabularies?.statuses ?? []).map((code) => ({
                    value: code,
                    label: statusLabels[code] ?? code,
                  })),
                ]}
              />
              <label className="space-y-1 text-sm">
                <span className="block font-medium">排序</span>
                <select
                  className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
                  value={sort}
                  onChange={(e) => {
                    setSort(e.target.value);
                    setPage(0);
                  }}
                >
                  <option value="RECENT">最近变动</option>
                  <option value="SERIAL_NUMBER">按序列号</option>
                  <option value="STATUS">按状态</option>
                  <option value="LOCATION">按库位</option>
                  <option value="MOVED">按最近流转</option>
                </select>
              </label>
              <label className="space-y-1 text-sm xl:col-span-2">
                <span className="block font-medium">关键字</span>
                <input
                  className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
                  value={keyword}
                  placeholder="序列号 / SKU 编码 / 批次号"
                  onChange={(e) => {
                    setKeyword(e.target.value);
                    setPage(0);
                  }}
                />
              </label>
            </div>
          </SectionCard>

          <SectionCard
            title="序列号档案"
            description={
              serials
                ? `共 ${serials.total} 个序列号，当前第 ${serials.page + 1} / ${Math.max(
                    1,
                    Math.ceil(serials.total / serials.size),
                  )} 页`
                : undefined
            }
          >
            {loading ? (
              <EmptyState
                icon={<RefreshCw className="h-5 w-5 animate-spin" />}
                title="加载中"
                description="正在按当前筛选读取序列号档案。"
              />
            ) : !serials || serials.serials.length === 0 ? (
              <EmptyState
                icon={<ScanLine className="h-5 w-5" />}
                title="没有匹配的序列号"
                description="调整筛选条件，或用下方的扫码录入 / 批量导入登记新设备。"
              />
            ) : (
              <>
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead className="text-muted-foreground text-xs uppercase">
                      <tr>
                        <th className="px-3 py-2">序列号</th>
                        <th className="px-3 py-2">SKU</th>
                        <th className="px-3 py-2">状态</th>
                        <th className="px-3 py-2">当前位置</th>
                        <th className="px-3 py-2">批次</th>
                        <th className="px-3 py-2 text-right">流转</th>
                        <th className="px-3 py-2 text-right">最近流转</th>
                      </tr>
                    </thead>
                    <tbody>
                      {serials.serials.map((row) => (
                        <tr
                          key={row.id}
                          onClick={() => setSelected(row)}
                          className="border-border hover:bg-muted/60 cursor-pointer border-t"
                        >
                          <td className="px-3 py-2 font-mono text-xs">{row.serialNumber}</td>
                          <td className="px-3 py-2">{row.skuCode}</td>
                          <td className="px-3 py-2">
                            <span
                              className={`rounded px-2 py-0.5 text-xs ${
                                statusTones[row.status] ?? 'bg-muted text-muted-foreground'
                              }`}
                            >
                              {row.statusLabel || statusLabels[row.status] || row.status}
                            </span>
                          </td>
                          {/* 空库位要显式写成「不在任何库位」：留白会被读成「没查到」 */}
                          <td className="px-3 py-2">
                            {row.currentLocationId ? (
                              `${row.locationCode} ${row.locationName}`
                            ) : (
                              <span className="text-muted-foreground">不在任何库位</span>
                            )}
                          </td>
                          <td className="px-3 py-2">
                            {row.lotId ? (
                              row.lotCode
                            ) : (
                              <span className="text-muted-foreground">无批次</span>
                            )}
                          </td>
                          <td className="px-3 py-2 text-right tabular-nums">{row.movementCount}</td>
                          <td className="text-muted-foreground px-3 py-2 text-right text-xs">
                            {formatTime(row.lastMovementAt)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <Pager
                  page={serials.page}
                  size={serials.size}
                  total={serials.total}
                  onChange={setPage}
                />
              </>
            )}
          </SectionCard>

          {selected && (
            <SerialDetail
              profile={selected}
              writable={writable}
              onClose={() => setSelected(null)}
              onChanged={() => {
                void loadArchive();
                void loadHistory();
                void loadOperations();
              }}
              onError={setError}
              onNotice={setNotice}
            />
          )}

          {writable && (
            <>
              <SerialRegisterPanel
                skus={skus}
                locations={locations}
                lots={lots}
                onDone={() => {
                  void loadArchive();
                }}
                onError={setError}
                onNotice={setNotice}
              />
              <SerialImportPanel
                skus={skus}
                locations={locations}
                lots={lots}
                onDone={() => {
                  void loadArchive();
                }}
                onError={setError}
                onNotice={setNotice}
              />
              <SerialMovePanel
                skus={skus}
                locations={locations}
                vocabularies={vocabularies}
                onDone={() => {
                  void loadArchive();
                  void loadHistory();
                  void loadOperations();
                }}
                onError={setError}
                onNotice={setNotice}
              />
            </>
          )}
        </>
      )}

      {tab === 'HISTORY' && (
        <SectionCard
          title="位置流转历史"
          description="append-only：每行都是一次真实的挪动、换态或改批次，写下后不可修改。"
        >
          {!movements || movements.movements.length === 0 ? (
            <EmptyState
              icon={<History className="h-5 w-5" />}
              title="暂无流转记录"
              description="录入或批量导入一个序列号后，这里会出现它的第一条入库记录。"
            />
          ) : (
            <>
              <MovementTable rows={movements.movements} />
              <Pager
                page={movements.page}
                size={movements.size}
                total={movements.total}
                onChange={setHistoryPage}
              />
            </>
          )}
        </SectionCard>
      )}

      {tab === 'OPERATIONS' && (
        <SectionCard
          title="出入库操作"
          description="每一次操作的「声明数量」与「实际序列号条数」并列可查 —— 两者相等是本模块的硬约束。"
        >
          {!operations || operations.operations.length === 0 ? (
            <EmptyState
              icon={<PackageCheck className="h-5 w-5" />}
              title="暂无操作记录"
              description="扫码录入或批量导入会写下第一条操作头。"
            />
          ) : (
            <>
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-muted-foreground text-xs uppercase">
                    <tr>
                      <th className="px-3 py-2">发生时间</th>
                      <th className="px-3 py-2">方向</th>
                      <th className="px-3 py-2">来源单据</th>
                      <th className="px-3 py-2 text-right">声明数量</th>
                      <th className="px-3 py-2 text-right">序列号条数</th>
                      <th className="px-3 py-2 text-right">一致</th>
                      <th className="px-3 py-2">备注</th>
                    </tr>
                  </thead>
                  <tbody>
                    {operations.operations
                      .filter((op) => op !== null)
                      .map((op) => (
                        <tr key={op.id} className="border-border border-t">
                          <td className="text-muted-foreground px-3 py-2 text-xs">
                            {formatTime(op.occurredAt)}
                          </td>
                          <td className="px-3 py-2">
                            {op.directionLabel || directionLabels[op.direction]}
                          </td>
                          <td className="px-3 py-2 text-xs">
                            {op.refType || '—'}
                            {op.refId ? ` · ${op.refId.slice(0, 8)}` : ''}
                          </td>
                          <td className="px-3 py-2 text-right tabular-nums">
                            {Number(op.declaredQuantity)}
                          </td>
                          <td className="px-3 py-2 text-right tabular-nums">{op.serialCount}</td>
                          <td className="px-3 py-2 text-right">
                            {Number(op.declaredQuantity) === op.serialCount ? (
                              <span className="text-success text-xs">一致</span>
                            ) : (
                              <span className="text-destructive text-xs">不一致</span>
                            )}
                          </td>
                          <td className="text-muted-foreground px-3 py-2 text-xs">
                            {op.note || '—'}
                          </td>
                        </tr>
                      ))}
                  </tbody>
                </table>
              </div>
              <Pager
                page={operations.page}
                size={operations.size}
                total={operations.total}
                onChange={setOperationPage}
              />
            </>
          )}
        </SectionCard>
      )}
    </div>
  );
}

// ================================================================ 详情面板

function SerialDetail({
  profile,
  writable,
  onClose,
  onChanged,
  onError,
  onNotice,
}: {
  profile: SerialProfile;
  writable: boolean;
  onClose: () => void;
  onChanged: () => void;
  onError: (message: string) => void;
  onNotice: (message: string) => void;
}) {
  const [history, setHistory] = useState<SerialMovementPage | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setHistory(await fetchSerialMovements({ serialId: profile.id, page: 0, size: 50 }));
    } catch (e) {
      onError(errorText(e));
    }
  }, [profile.id, onError]);

  useEffect(() => {
    void load();
  }, [load]);

  async function discard() {
    setBusy(true);
    try {
      await deleteSerial(profile.id);
      onNotice(`序列号 ${profile.serialNumber} 的建档已作废`);
      onChanged();
      onClose();
    } catch (e) {
      onError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <SectionCard
      title={`序列号 ${profile.serialNumber}`}
      description={`${profile.skuCode} · ${
        profile.currentLocationId
          ? `${profile.locationCode} ${profile.locationName}`
          : '不在任何库位'
      }${profile.lotId ? ` · 批次 ${profile.lotCode}` : ' · 无批次'}`}
    >
      <div className="flex flex-wrap items-center gap-2">
        {writable && (
          <Button variant="outline" size="sm" onClick={() => void discard()} disabled={busy}>
            作废建档
          </Button>
        )}
        <Button variant="outline" size="sm" onClick={onClose}>
          关闭
        </Button>
        {writable && (
          <span className="text-muted-foreground text-xs">
            已被流转引用过的序列号不能作废，只能走报废 —— 作废只针对「录错了」且没人引用过的号。
          </span>
        )}
      </div>

      <div className="mt-4">
        <h3 className="mb-2 text-sm font-medium">流转时间线</h3>
        {!history || history.movements.length === 0 ? (
          <EmptyState
            icon={<History className="h-5 w-5" />}
            title="暂无流转记录"
            description="这个序列号还没有产生过位置或状态变动。"
          />
        ) : (
          <MovementTable rows={history.movements} />
        )}
      </div>
    </SectionCard>
  );
}

function MovementTable({
  rows,
}: {
  rows: import('../../../../../packages/api-contracts/src/index').SerialMovementRow[];
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead className="text-muted-foreground text-xs uppercase">
          <tr>
            <th className="px-3 py-2">发生时间</th>
            <th className="px-3 py-2">序列号</th>
            <th className="px-3 py-2">流转</th>
            <th className="px-3 py-2">从 → 到</th>
            <th className="px-3 py-2">来源单据</th>
            <th className="px-3 py-2">备注</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id} className="border-border border-t">
              <td className="text-muted-foreground px-3 py-2 text-xs">
                {formatTime(row.occurredAt)}
              </td>
              <td className="px-3 py-2 font-mono text-xs">{row.serialNumber}</td>
              <td className="px-3 py-2">{row.movementLabel}</td>
              {/* from/to 成对出现：只显示新状态会让人以为号一直都在库 */}
              <td className="px-3 py-2 text-xs">
                {transitionText(row.fromLocationCode, row.toLocationCode, row.toStatus)}
                {row.fromLotId !== row.toLotId && (
                  <span className="text-muted-foreground ml-2">
                    批次 {row.fromLotCode || '无'} → {row.toLotCode || '无'}
                  </span>
                )}
              </td>
              <td className="px-3 py-2 text-xs">
                {row.refType || '—'}
                {row.refId ? ` · ${row.refId.slice(0, 8)}` : ''}
              </td>
              <td className="text-muted-foreground px-3 py-2 text-xs">{row.note || '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ================================================================ 扫码录入

function SerialRegisterPanel({
  skus,
  locations,
  lots,
  onDone,
  onError,
  onNotice,
}: {
  skus: SkuRef[];
  locations: LocationRef[];
  lots: LotRef[];
  onDone: () => void;
  onError: (message: string) => void;
  onNotice: (message: string) => void;
}) {
  const [serialNumber, setSerialNumber] = useState('');
  const [skuId, setSkuId] = useState('');
  const [locationId, setLocationId] = useState('');
  const [lotId, setLotId] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<SerialValidationResult | null>(null);

  async function submit() {
    setBusy(true);
    setResult(null);
    try {
      const res = await registerSerial({
        serialNumber: serialNumber.trim(),
        skuId,
        lotId: lotId || null,
        locationId,
        refType: null,
        refId: null,
        note: note || null,
        occurredAt: null,
      });
      setResult(res);
      if (res.applied) {
        onNotice(`序列号 ${serialNumber.trim()} 已入库`);
        setSerialNumber('');
        setNote('');
        onDone();
      }
    } catch (e) {
      onError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <SectionCard
      title="扫码 / 单条录入"
      description="录入即入库：号扫到哪个库位，它当下就在那个库位上，同时写下第一条流转记录。"
    >
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-5">
        <label className="space-y-1 text-sm xl:col-span-2">
          <span className="block font-medium">序列号</span>
          <input
            className="border-control-border bg-card w-full rounded-md border px-3 py-2 font-mono text-sm"
            value={serialNumber}
            placeholder="扫描或手工输入"
            onChange={(e) => setSerialNumber(e.target.value)}
          />
        </label>
        <SelectField
          label="SKU"
          value={skuId}
          onChange={(v) => {
            setSkuId(v);
            setLotId('');
          }}
          options={[
            { value: '', label: '请选择' },
            ...skus.map((s) => ({ value: s.id, label: `${s.code} ${s.name}` })),
          ]}
        />
        <SelectField
          label="落位库位"
          value={locationId}
          onChange={setLocationId}
          options={[
            { value: '', label: '请选择' },
            ...locations.map((l) => ({ value: l.id, label: `${l.code} ${l.name}` })),
          ]}
        />
        <SelectField
          label="批次（可空）"
          value={lotId}
          onChange={setLotId}
          options={[
            { value: '', label: '无批次' },
            ...lots.map((l) => ({ value: l.id, label: l.lotCode })),
          ]}
        />
        <label className="space-y-1 text-sm md:col-span-2">
          <span className="block font-medium">备注</span>
          <input
            className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
        </label>
      </div>
      <div className="mt-3 flex items-center gap-3">
        <Button
          onClick={() => void submit()}
          disabled={busy || !serialNumber.trim() || !skuId || !locationId}
        >
          {busy ? '提交中…' : '录入'}
        </Button>
        <span className="text-muted-foreground text-xs">
          单条录入固定为数量 1 ↔ 恰好 1 个序列号，蓝图那条硬约束在这里天然成立。
          {(!skuId || !locationId) && ' 录入必须选 SKU 与落位库位。'}
        </span>
      </div>
      <IssuePanel result={result} />
    </SectionCard>
  );
}

// ================================================================ 批量导入

function SerialImportPanel({
  skus,
  locations,
  lots,
  onDone,
  onError,
  onNotice,
}: {
  skus: SkuRef[];
  locations: LocationRef[];
  lots: LotRef[];
  onDone: () => void;
  onError: (message: string) => void;
  onNotice: (message: string) => void;
}) {
  const [skuId, setSkuId] = useState('');
  const [locationId, setLocationId] = useState('');
  const [lotId, setLotId] = useState('');
  const [raw, setRaw] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<SerialValidationResult | null>(null);

  const serials = useMemo(() => parseScanInput(raw), [raw]);
  // 提交前的本地预检：后端一定会再校验一遍，这道只是让人当场就知道少扫了几台
  const guard = useMemo(() => localQuantityGuard(String(serials.length), serials), [serials]);

  async function send(dryRun: boolean) {
    setBusy(true);
    setResult(null);
    try {
      const res = await importSerials({
        skuId,
        lotId: lotId || null,
        locationId,
        serials,
        refType: null,
        refId: null,
        note: note || null,
        occurredAt: null,
        dryRun,
      });
      setResult(res);
      if (!dryRun && res.applied) {
        onNotice(`已导入 ${serials.length} 个序列号`);
        setRaw('');
        onDone();
      }
    } catch (e) {
      onError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <SectionCard
      title="批量导入"
      description="整批同 SKU、同库位。扫描枪一次上传的整箱文本直接粘进来，换行 / 逗号都能认。"
    >
      <div className="grid gap-3 md:grid-cols-3">
        <SelectField
          label="SKU"
          value={skuId}
          onChange={(v) => {
            setSkuId(v);
            setLotId('');
          }}
          options={[
            { value: '', label: '请选择' },
            ...skus.map((s) => ({ value: s.id, label: `${s.code} ${s.name}` })),
          ]}
        />
        <SelectField
          label="落位库位"
          value={locationId}
          onChange={setLocationId}
          options={[
            { value: '', label: '请选择' },
            ...locations.map((l) => ({ value: l.id, label: `${l.code} ${l.name}` })),
          ]}
        />
        <SelectField
          label="批次（可空）"
          value={lotId}
          onChange={setLotId}
          options={[
            { value: '', label: '无批次' },
            ...lots.map((l) => ({ value: l.id, label: l.lotCode })),
          ]}
        />
        <label className="space-y-1 text-sm md:col-span-3">
          <span className="block font-medium">扫描结果（{serials.length} 个）</span>
          <textarea
            className="border-control-border bg-card h-32 w-full rounded-md border px-3 py-2 font-mono text-xs"
            value={raw}
            placeholder={'SN-0001\nSN-0002\nSN-0003'}
            onChange={(e) => setRaw(e.target.value)}
          />
        </label>
        <label className="space-y-1 text-sm md:col-span-3">
          <span className="block font-medium">备注</span>
          <input
            className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
        </label>
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-3">
        <Button
          variant="outline"
          onClick={() => void send(true)}
          disabled={busy || serials.length < 2 || !skuId || !locationId}
        >
          先试跑（只校验）
        </Button>
        <Button
          onClick={() => void send(false)}
          disabled={busy || guard !== null || serials.length < 2 || !skuId || !locationId}
        >
          {busy ? '导入中…' : '确认导入'}
        </Button>
        {(!skuId || !locationId) && (
          <span className="text-destructive text-xs">批量导入必须选 SKU 与落位库位。</span>
        )}
        {guard && <span className="text-destructive text-xs">{guard.message}</span>}
        {!guard && serials.length > 0 && (
          <span className="text-muted-foreground text-xs">
            已扫到 {serials.length} 个，数量与条数一致。
          </span>
        )}
      </div>
      <IssuePanel result={result} />
    </SectionCard>
  );
}

// ================================================================ 出入库操作

function SerialMovePanel({
  skus,
  locations,
  vocabularies,
  onDone,
  onError,
  onNotice,
}: {
  skus: SkuRef[];
  locations: LocationRef[];
  vocabularies: SerialVocabularies | null;
  onDone: () => void;
  onError: (message: string) => void;
  onNotice: (message: string) => void;
}) {
  const [direction, setDirection] = useState<SerialDirectionCode>('OUTBOUND');
  const [skuId, setSkuId] = useState('');
  const [quantity, setQuantity] = useState('');
  const [raw, setRaw] = useState('');
  const [toLocationId, setToLocationId] = useState('');
  const [fromLocationId, setFromLocationId] = useState('');
  const [lotId, setLotId] = useState('');
  const [toStatus, setToStatus] = useState<SerialStatusCode | ''>('');
  const [refType, setRefType] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<SerialValidationResult | null>(null);

  const serials = useMemo(() => parseScanInput(raw), [raw]);
  const guard = useMemo(() => localQuantityGuard(quantity, serials), [quantity, serials]);
  const targets = allowedTargetStatuses(direction);

  // 换方向时清掉不可能成立的旧输入：上一个方向留下的目标库位在这一档没有意义
  useEffect(() => {
    if (!targetLocationRequired(direction)) setToLocationId('');
    if (toStatus && !targets.includes(toStatus)) setToStatus('');
    if (direction === 'REBIND') setToStatus('');
  }, [direction, targets, toStatus]);

  async function send(dryRun: boolean) {
    setBusy(true);
    setResult(null);
    try {
      const payload = {
        direction,
        skuId: skuId || null,
        declaredQuantity: Number(quantity),
        serialNumbers: serials,
        toLocationId: toLocationId || null,
        fromLocationId: fromLocationId || null,
        lotId: lotId || null,
        toStatus: toStatus || null,
        refType: refType || null,
        refId: null,
        note: note || null,
        occurredAt: null,
      };
      const res = dryRun ? await validateMove(payload) : await moveSerials({ ...payload, dryRun });
      setResult(res);
      if (!dryRun && res.applied) {
        onNotice(`${directionLabels[direction] ?? direction} ${serials.length} 个序列号已提交`);
        setRaw('');
        onDone();
      }
    } catch (e) {
      onError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <SectionCard
      title="出入库 / 库位转移 / 状态流转 / 批次改绑"
      description="先试跑再提交：试跑只校验不落库，把「哪个号有什么问题」看清楚了再动。"
    >
      <div className="grid gap-3 md:grid-cols-3 xl:grid-cols-4">
        <SelectField
          label="方向"
          value={direction}
          onChange={(v) => setDirection(v as SerialDirectionCode)}
          options={(vocabularies?.directions ?? []).map((code) => ({
            value: code as SerialDirectionCode,
            label: directionLabels[code] ?? code,
          }))}
        />
        <SelectField
          label="SKU"
          value={skuId}
          onChange={setSkuId}
          options={[
            { value: '', label: '不校验（同一批同一型号）' },
            ...skus.map((s) => ({ value: s.id, label: `${s.code} ${s.name}` })),
          ]}
        />
        <label className="space-y-1 text-sm">
          <span className="block font-medium">数量</span>
          <input
            className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
            value={quantity}
            inputMode="numeric"
            onChange={(e) => setQuantity(e.target.value)}
          />
        </label>
        {targetStatusRequired(direction) ? (
          <SelectField
            label="目标状态（必填）"
            value={toStatus}
            onChange={(v) => setToStatus(v as SerialStatusCode)}
            options={[
              { value: '', label: '请选择去向' },
              ...targets.map((code) => ({ value: code, label: statusLabels[code] ?? code })),
            ]}
          />
        ) : targets.length > 0 ? (
          <SelectField
            label="目标状态（可空=保持）"
            value={toStatus}
            onChange={(v) => setToStatus(v as SerialStatusCode)}
            options={[
              { value: '', label: '保持原状态' },
              ...targets.map((code) => ({ value: code, label: statusLabels[code] ?? code })),
            ]}
          />
        ) : (
          <div className="space-y-1 text-sm">
            <span className="block font-medium">目标状态</span>
            <p className="border-control-border bg-muted text-muted-foreground rounded-md border px-3 py-2 text-xs">
              {direction === 'REBIND' ? '改批次不改状态' : '由方向决定'}
            </p>
          </div>
        )}

        {targetLocationRequired(direction) && (
          <SelectField
            label="目标库位（必填）"
            value={toLocationId}
            onChange={setToLocationId}
            options={[
              { value: '', label: '请选择' },
              ...locations.map((l) => ({ value: l.id, label: `${l.code} ${l.name}` })),
            ]}
          />
        )}
        <SelectField
          label="来源库位（可空=不校验）"
          value={fromLocationId}
          onChange={setFromLocationId}
          options={[
            { value: '', label: '不校验' },
            ...locations.map((l) => ({ value: l.id, label: `${l.code} ${l.name}` })),
          ]}
        />
        <label className="space-y-1 text-sm">
          <span className="block font-medium">
            {direction === 'REBIND' ? '改绑到批次' : '批次（可空）'}
          </span>
          <input
            className="border-control-border bg-card w-full rounded-md border px-3 py-2 font-mono text-xs"
            value={lotId}
            placeholder="批次 UUID"
            onChange={(e) => setLotId(e.target.value)}
          />
        </label>
        <label className="space-y-1 text-sm">
          <span className="block font-medium">来源单据类型</span>
          <input
            className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
            value={refType}
            placeholder="SO / PO / RETURN / ADJUST"
            onChange={(e) => setRefType(e.target.value)}
          />
        </label>
        <label className="space-y-1 text-sm md:col-span-3 xl:col-span-4">
          <span className="block font-medium">序列号（{serials.length} 个）</span>
          <textarea
            className="border-control-border bg-card h-24 w-full rounded-md border px-3 py-2 font-mono text-xs"
            value={raw}
            placeholder={'SN-0001\nSN-0002'}
            onChange={(e) => setRaw(e.target.value)}
          />
        </label>
        <label className="space-y-1 text-sm md:col-span-3 xl:col-span-4">
          <span className="block font-medium">备注</span>
          <input
            className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
        </label>
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-3">
        <Button
          variant="outline"
          onClick={() => void send(true)}
          disabled={busy || serials.length === 0 || quantity.trim() === ''}
        >
          先试跑
        </Button>
        <Button onClick={() => void send(false)} disabled={busy || guard !== null}>
          {busy ? '提交中…' : '确认提交'}
        </Button>
        {guard && <span className="text-destructive text-xs">{guard.message}</span>}
        <span className="text-muted-foreground text-xs">
          报废是终态；状态流转保持库位不变，报废请走出库方向。
        </span>
      </div>
      <IssuePanel result={result} />
    </SectionCard>
  );
}

// ================================================================ 校验错误面板

/**
 * 逐条展示校验错误，并按序列号归因。
 *
 * <p>「整单退回」与「挑出个别号单独处理」必须分开呈现：前者是操作员少扫了两台，
 * 后者是这两个号被别人锁在维修库里了。混在一张红框里，两种情况的正确处置完全不同。
 */
function IssuePanel({ result }: { result: SerialValidationResult | null }) {
  if (!result) return null;
  if (result.passed && !result.applied) {
    return (
      <p className="border-success/40 bg-success-soft text-success mt-3 rounded-md border px-3 py-2 text-sm">
        校验通过（试跑未落库）。声明数量 {Number(result.declaredQuantity)} 与序列号条数{' '}
        {result.serialCount} 完全一致。
      </p>
    );
  }
  if (result.passed && result.applied) {
    return (
      <p className="border-success/40 bg-success-soft text-success mt-3 rounded-md border px-3 py-2 text-sm">
        已提交：{result.serialCount} 个序列号，声明数量 {Number(result.declaredQuantity)}，
        两者一致。
      </p>
    );
  }
  const grouped = groupIssues(result.issues ?? []);
  const retry = retryableScanText(result);
  return (
    <div className="border-destructive/40 bg-destructive-soft text-destructive mt-3 space-y-2 rounded-md border p-3 text-sm">
      <p className="font-medium">
        校验未通过，本次操作一条数据都没有写入（声明数量 {Number(result.declaredQuantity)}，
        实际提供 {result.serialCount} 个序列号）
      </p>
      {grouped.global.map((issue) => (
        <p key={`${issue.code}-${issue.message}`}>· {issue.message}</p>
      ))}
      {grouped.bySerial.map((entry) => (
        <div key={entry.serialNumber} className="space-y-1">
          <p className="font-mono font-medium">{entry.serialNumber}</p>
          {entry.issues.map((issue: SerialIssue) => (
            <p key={issue.code} className="pl-4 text-xs">
              · [{issue.code}] {issue.message}
            </p>
          ))}
        </div>
      ))}
      {retry && (
        <p className="text-xs">
          需要处理的序列号共 {grouped.bySerial.length} 个，重新扫描后按上表逐个核对。
        </p>
      )}
    </div>
  );
}

// ================================================================ 小部件

function SelectField({
  label,
  value,
  onChange,
  options,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
}) {
  return (
    <label className="space-y-1 text-sm">
      <span className="block font-medium">{label}</span>
      <select
        className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
        value={value}
        onChange={(e) => onChange(e.target.value)}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  );
}

function Pager({
  page,
  size,
  total,
  onChange,
}: {
  page: number;
  size: number;
  total: number;
  onChange: (page: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <div className="mt-3 flex items-center gap-3 text-sm">
      <Button variant="outline" size="sm" disabled={page <= 0} onClick={() => onChange(page - 1)}>
        上一页
      </Button>
      <span className="text-muted-foreground">
        第 {page + 1} / {pages} 页
      </span>
      <Button
        variant="outline"
        size="sm"
        disabled={page + 1 >= pages}
        onClick={() => onChange(page + 1)}
      >
        下一页
      </Button>
    </div>
  );
}

function formatTime(value: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}
