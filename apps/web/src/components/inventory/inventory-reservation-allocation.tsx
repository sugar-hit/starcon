'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { ArrowLeft, RefreshCw } from 'lucide-react';
import { Badge, Button, EmptyState, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  ReservationAllocationEntryRow,
  ReservationAllocationOption,
  ReservationAllocationPage,
  ReservationAllocationTarget,
  ReservationAllocationTimeline,
  ReservationCapability,
} from '../../../../../packages/api-contracts/src/index';
import {
  ALLOCATION_OWNER_NOTE,
  ALLOCATION_TIMEZONE_NOTE,
  RESERVATION_ALLOCATION_SORT_OPTIONS,
  RESERVATION_ALLOCATION_STATUS_OPTIONS,
  RESERVATION_ALLOCATION_TRANSITION_OPTIONS,
  UNALLOCATED_NOTE,
  allocationLevelLabel,
  allocationStatusLabel,
  allocationTransitionLabel,
  bucketText,
  commitReservationAllocation,
  consumeReservationAllocation,
  createReservationAllocation,
  entryFromLabel,
  fetchReservationAllocationCapabilities,
  fetchReservationAllocationOptions,
  fetchReservationAllocations,
  fetchReservationAllocationTimeline,
  isTerminalAllocation,
} from './inventory-reservation-allocation-model';
import { errorText, qty } from './inventory-model';
import { countOf } from './inventory-reservation-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.2.1.2 预占分配明细工作区。
 *
 * <p>蓝图：「展示一个预占需求实际分配到了哪些 bucket（哪个仓、哪个货位、哪个批次），以及每个分配的
 * held→终态迁移记录。」输出「分配明细行 + 状态迁移时间线」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>两张卡：分配明细行 + 迁移时间线</b>。蓝图输出的就是这两样，合成一张表会把
 *       「这条分配经历过几次迁移」挤成一列看不清的字。
 *   <li><b>未分配量与剩余量分开列</b>，两个数字答的是两个问题（见 {@link UNALLOCATED_NOTE}）。
 *   <li><b>汇总不随筛选与分页变化</b>，旁边写明 —— 否则「切了个筛选怎么总量变了」
 *       看起来像数据丢了。
 *   <li><b>没有「释放」按钮</b>。释放与过期属 7.2.1.3，未交付；按 capabilities 如实说明去向，
 *       而不是放一个点下去必然 404 的按钮。
 *   <li><b>写入动作按状态给</b>：只有 HELD 显承诺、只有 COMMITTED 显消耗。
 *       给一个在当前状态下必然被拒的按钮，不如不给。
 * </ul>
 *
 * <p>错误态分两种：整体拉取失败显示错误条并保留重试；单条写动作失败只在动作处提示，
 * 不把整页换成错误态 —— 否则一次「承诺失败」会把用户正在看的明细整个顶掉。
 */
export function InventoryReservationAllocationWorkspace({ reservationId }: { reservationId: string }) {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [page, setPage] = useState<ReservationAllocationPage | null>(null);
  const [timeline, setTimeline] = useState<ReservationAllocationTimeline | null>(null);
  const [statuses, setStatuses] = useState<ReservationAllocationOption[]>(
    RESERVATION_ALLOCATION_STATUS_OPTIONS,
  );
  const [targets, setTargets] = useState<ReservationAllocationTarget[]>([]);
  const [targetLocations, setTargetLocations] = useState<ReservationAllocationTarget[]>([]);
  const [targetLots, setTargetLots] = useState<ReservationAllocationTarget[]>([]);
  const [capabilities, setCapabilities] = useState<ReservationCapability[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [statusFilter, setStatusFilter] = useState<string[]>([]);
  const [bucketFilter, setBucketFilter] = useState('');
  const [keyword, setKeyword] = useState('');
  const [sort, setSort] = useState('ALLOCATED_AT');
  const [pageNumber, setPageNumber] = useState(0);
  const [timelineTransition, setTimelineTransition] = useState('');

  // 登记分配表单。空串表示「不指定」：虚拟仓没有货位、非批次管理品没有批次，
  // 逼用户选一个反而是在给一个不存在的必填项编造内容。
  const [newWarehouse, setNewWarehouse] = useState('');
  const [newLocation, setNewLocation] = useState('');
  const [newLot, setNewLot] = useState('');
  const [newQuantity, setNewQuantity] = useState('');

  const [actionError, setActionError] = useState('');
  const [acting, setActing] = useState('');

  const load = useCallback(async () => {
    if (!readable) return;
    setLoading(true);
    setError('');
    try {
      const [p, t, o, c] = await Promise.all([
        fetchReservationAllocations(reservationId, {
          ...(statusFilter.length ? { status: statusFilter } : {}),
          ...(keyword.trim() ? { keyword: keyword.trim() } : {}),
          ...(bucketFilter ? { warehouseId: bucketFilter } : {}),
          sort,
          page: pageNumber,
          size: 20,
        }),
        // 时间线独立失败不拖垮明细页：明细是主内容，时间线是佐证。
        fetchReservationAllocationTimeline(reservationId, {
          ...(timelineTransition ? { transition: timelineTransition } : {}),
          page: 0,
          size: 50,
        }).catch(() => null),
        fetchReservationAllocationOptions(reservationId).catch(() => null),
        fetchReservationAllocationCapabilities(reservationId).catch(() => null),
      ]);
      setPage(p);
      setTimeline(t);
      if (o?.statuses?.length) setStatuses(o.statuses);
      if (o?.targets) {
        setTargets(o.targets.warehouses ?? []);
        setTargetLocations(o.targets.locations ?? []);
        setTargetLots(o.targets.lots ?? []);
      }
      setCapabilities(c ?? []);
    } catch (e) {
      setError(errorText(e));
      setPage(null);
      setTimeline(null);
    } finally {
      setLoading(false);
    }
  }, [readable, reservationId, statusFilter, keyword, bucketFilter, sort, pageNumber, timelineTransition]);

  useEffect(() => {
    void load();
  }, [load]);

  const toggleStatus = useCallback((code: string) => {
    setPageNumber(0);
    setStatusFilter((prev) =>
      prev.includes(code) ? prev.filter((s) => s !== code) : [...prev, code],
    );
  }, []);

  /** 写动作：失败只在动作处提示，不把整页换成错误态。 */
  const run = useCallback(
    async (key: string, work: () => Promise<unknown>) => {
      setActing(key);
      setActionError('');
      try {
        await work();
        await load();
      } catch (e) {
        setActionError(errorText(e));
      } finally {
        setActing('');
      }
    },
    [load],
  );

  const capabilityOf = useMemo(() => {
    const map = new Map<string, ReservationCapability>();
    for (const c of capabilities) map.set(c.dimension, c);
    return map;
  }, [capabilities]);

  const allocateAvailable = capabilityOf.get('ALLOCATION_REGISTER')?.available ?? false;
  const commitAvailable = capabilityOf.get('ALLOCATION_COMMIT')?.available ?? false;
  const consumeAvailable = capabilityOf.get('ALLOCATION_CONSUME')?.available ?? false;
  const release = capabilityOf.get('ALLOCATION_RELEASE');

  /** 当前选中仓库名下的货位。换仓就把货位清空 —— 留着上一个仓的货位 id 会撞「货位不属于该仓」。 */
  const locationsOfSelectedWarehouse = useMemo(
    () => targetLocations.filter((l) => l.parentId === newWarehouse),
    [targetLocations, newWarehouse],
  );

  /** 登记分配：仓必填，货位与批次可不填（虚拟仓 / 非批次管理品）。 */
  const submitAllocation = useCallback(() => {
    const quantity = Number(newQuantity);
    if (!newWarehouse || !newQuantity.trim() || !Number.isFinite(quantity) || quantity <= 0) {
      setActionError('请选择仓库并填写一个大于 0 的分配数量');
      return;
    }
    void run('allocate', async () => {
      await createReservationAllocation(reservationId, {
        warehouseId: newWarehouse,
        locationId: newLocation || null,
        lotId: newLot || null,
        allocatedQuantity: quantity,
      });
      setNewLocation('');
      setNewQuantity('');
    });
  }, [run, reservationId, newWarehouse, newLocation, newLot, newQuantity]);

  if (!readable) {
    return (
      <EmptyState
        title="没有预占分配明细查看权限"
        description="预占分配明细属库存卷，需要 warehouse.inventory.read 权限。"
      />
    );
  }

  const summary = page?.summary;
  const totalPages = page ? Math.max(1, Math.ceil(page.total / Math.max(1, page.size))) : 1;
  const header = page?.reservation;

  return (
    <div className="space-y-6">
      <PageHeader
        title={header ? `分配明细 · ${header.reservationNo}` : '预占分配明细'}
        description="这笔预占实际分配到了哪些仓 / 货位 / 批次，以及每条分配的迁移时间线。"
        actions={
          <div className="flex gap-2">
            <Link href="/inventory/reservation">
              <Button variant="ghost">
                <ArrowLeft className="size-4" />
                返回预占列表
              </Button>
            </Link>
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
          </div>
        }
      />

      {error ? (
        <SectionCard title="加载失败">
          <p className="text-sm text-red-600">{error}</p>
          <Button variant="secondary" className="mt-3" onClick={() => void load()}>
            重试
          </Button>
        </SectionCard>
      ) : null}

      {/* 预占抬头：需求量 / 已分配 / 未分配 / 剩余量，两个「欠」问题分列 */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <StatCard label="需求量" value={header ? qty(header.requestedQuantity) : '—'}/>
        <StatCard
          label="已分配量"
          value={header ? qty(header.allocatedQuantity) : '—'}
          comparisonLabel={`圈到 ${summary?.bucketCount ?? 0} 个 bucket`}
        />
        <StatCard
          label="未分配量"
          value={summary ? qty(summary.unallocatedQuantity) : '—'}
          comparisonLabel="还欠多少没圈到 bucket"
        />
        <StatCard
          label="剩余量"
          value={header ? qty(header.remainingQuantity) : '—'}
          comparisonLabel="还欠多少没出库"
        />
        <StatCard
          label="迁移条数"
          value={page ? String(page.timelineCount) : '—'}
          comparisonLabel="append-only 审计凭据"
        />
      </div>

      {/* 汇总卡：固定五行 + 三个合计，0 也显示 —— 少一行会让人以为「这个状态不存在」 */}
      {summary ? (
        <SectionCard title="分配汇总">
          <p className="text-muted-foreground mb-3 text-xs">
            合计为整笔预占的全量分配，不随下方筛选与分页变化。
          </p>
          <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-5">
            <StatCard label="预占中" value={String(countOf(summary, 'HELD'))}/>
            <StatCard label="已承诺" value={String(countOf(summary, 'COMMITTED'))}/>
            <StatCard label="已消耗" value={String(countOf(summary, 'CONSUMED'))}/>
            <StatCard label="已释放" value={String(countOf(summary, 'RELEASED'))}/>
            <StatCard label="已过期" value={String(countOf(summary, 'EXPIRED'))}/>
            <StatCard label="分配行数" value={String(summary.totalCount)}/>
            <StatCard label="bucket 数" value={String(summary.bucketCount)}/>
            <StatCard label="已消耗量" value={qty(summary.consumedQuantity)}/>
            <StatCard label="已释放量" value={qty(summary.releasedQuantity)}/>
            <StatCard label="未出库量" value={qty(summary.remainingQuantity)}/>
          </div>
          <p className="text-muted-foreground mt-3 text-xs">{UNALLOCATED_NOTE}</p>
        </SectionCard>
      ) : null}

      {/* 筛选 */}
      <SectionCard title="筛选">
        <div className="flex flex-wrap items-center gap-2">
          {statuses.map((s) => (
            <button
              key={s.code}
              type="button"
              title={s.description ?? undefined}
              onClick={() => toggleStatus(s.code)}
              className={`rounded-md border px-2 py-1 text-xs ${
                statusFilter.includes(s.code)
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-border text-muted-foreground'
              }`}
            >
              {s.label}
            </button>
          ))}
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <select
            className="border-border rounded-md border px-2 py-1 text-sm"
            value={bucketFilter}
            onChange={(e) => {
              setPageNumber(0);
              setBucketFilter(e.target.value);
            }}
          >
            <option value="">全部仓库</option>
            {/* 筛选器用「全量在用仓」而不是「本预占出现过���仓」：
                按一个没圈过的仓筛，合法地得到 0 条，不该被排除在选项之外 */}
            {targets.map((w) => (
              <option key={w.id} value={w.id}>
                {w.code} · {w.name}
              </option>
            ))}
          </select>
          <input
            className="border-border rounded-md border px-2 py-1 text-sm"
            placeholder="仓 / 货位 / 批次关键字"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
          <select
            className="border-border rounded-md border px-2 py-1 text-sm"
            value={sort}
            onChange={(e) => {
              setPageNumber(0);
              setSort(e.target.value);
            }}
          >
            {RESERVATION_ALLOCATION_SORT_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
      </SectionCard>

      {/* 动作错误只在动作处提示，不把整页顶掉 */}
      {actionError ? (
        <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {actionError}
        </p>
      ) : null}

      {/* 登记分配：明细页要真能写，否则「尚未分配」是个死胡同 */}
      {writable && allocateAvailable ? (
        <SectionCard title="登记分配">
          <p className="text-muted-foreground mb-3 text-xs">
            把这笔预占圈到一个（仓 / 货位 / 批次）上。货位与批次可不填：虚拟仓没有货位档案，
            非批次管理品没有批次。分配量超过未分配量会被数据库层当场拒绝（超卖兜底）。
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <select
              className="border-border rounded-md border px-2 py-1 text-sm"
              value={newWarehouse}
              onChange={(e) => {
                setNewWarehouse(e.target.value);
                setNewLocation('');
              }}
            >
              <option value="">选择仓库…</option>
              {targets.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.code} · {w.name}
                </option>
              ))}
            </select>
            <select
              className="border-border rounded-md border px-2 py-1 text-sm"
              value={newLocation}
              disabled={!newWarehouse}
              onChange={(e) => setNewLocation(e.target.value)}
            >
              <option value="">不指定货位</option>
              {locationsOfSelectedWarehouse.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.code} · {l.name}
                </option>
              ))}
            </select>
            <select
              className="border-border rounded-md border px-2 py-1 text-sm"
              value={newLot}
              onChange={(e) => setNewLot(e.target.value)}
            >
              <option value="">不指定批次</option>
              {targetLots.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.code}
                </option>
              ))}
            </select>
            <input
              className="border-border w-32 rounded-md border px-2 py-1 text-sm"
              placeholder="分配数量"
              value={newQuantity}
              onChange={(e) => setNewQuantity(e.target.value)}
            />
            <Button variant="brand" disabled={acting === 'allocate'} onClick={submitAllocation}>
              登记分配
            </Button>
          </div>
        </SectionCard>
      ) : null}

      {/* 分配明细行 */}
      <SectionCard title="分配明细">
        {loading && !page ? (
          <p className="text-muted-foreground text-sm">正在加载分配明细…</p>
        ) : page && page.rows.length > 0 ? (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-border text-muted-foreground border-b text-left">
                    <th className="p-2">仓</th>
                    <th className="p-2">货位</th>
                    <th className="p-2">批次</th>
                    <th className="p-2 text-right">分配量</th>
                    <th className="p-2 text-right">已消耗</th>
                    <th className="p-2 text-right">未出库</th>
                    <th className="p-2">状态</th>
                    <th className="p-2 text-right">迁移</th>
                    <th className="p-2">圈定时间</th>
                    <th className="p-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {page.rows.map((row) => (
                    <tr key={row.id} className="border-border/50 border-b">
                      <td className="p-2">
                        <div>{row.warehouseCode}</div>
                        <div className="text-muted-foreground text-xs">{row.warehouseName}</div>
                      </td>
                      {/* 缺哪一维就显示「—」：虚拟仓没有货位、非批次管理品没有批次，
                          这两种都是合法状态，不是数据没加载出来 */}
                      <td className="p-2">{row.locationCode ?? '—'}</td>
                      <td className="p-2">
                        <div>{row.lotCode ?? '—'}</div>
                        {row.lotExpiresOn ? (
                          <div className="text-muted-foreground text-xs">
                            效期 {row.lotExpiresOn}
                          </div>
                        ) : null}
                      </td>
                      <td className="p-2 text-right tabular-nums">{qty(row.allocatedQuantity)}</td>
                      <td className="p-2 text-right tabular-nums">{qty(row.consumedQuantity)}</td>
                      <td className="p-2 text-right tabular-nums">{qty(row.remainingQuantity)}</td>
                      <td className="p-2">
                        <Badge tone={isTerminalAllocation(row.status) ? 'neutral' : 'info'}>
                          {allocationStatusLabel(row.status, row.statusLabel ?? undefined)}
                        </Badge>
                      </td>
                      <td className="p-2 text-right tabular-nums">{row.transitionCount}</td>
                      <td className="p-2 tabular-nums">
                        {new Date(row.allocatedAt).toLocaleString('zh-CN', { hour12: false })}
                      </td>
                      <td className="p-2">
                        <div className="flex flex-wrap gap-1">
                          {/* 只给当前状态下**真能成功**的动作：在 HELD 上显消耗，
                              那个请求必然被「只有已承诺的分配可以消耗」拒掉 */}
                          {writable && commitAvailable && row.status === 'HELD' ? (
                            <Button
                              variant="secondary"
                              disabled={acting === `commit:${row.id}`}
                              onClick={() =>
                                void run(`commit:${row.id}`, () =>
                                  commitReservationAllocation(reservationId, row.id, {}),
                                )
                              }
                            >
                              承诺
                            </Button>
                          ) : null}
                          {writable && consumeAvailable && row.status === 'COMMITTED' ? (
                            <Button
                              variant="secondary"
                              disabled={acting === `consume:${row.id}`}
                              onClick={() =>
                                void run(`consume:${row.id}`, () =>
                                  consumeReservationAllocation(reservationId, row.id, {}),
                                )
                              }
                            >
                              消耗
                            </Button>
                          ) : null}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="mt-3 flex items-center justify-between text-sm">
              <span className="text-muted-foreground">
                共 {page.total} 条 · 第 {page.page + 1} / {totalPages} 页
              </span>
              <div className="flex gap-2">
                <Button
                  variant="secondary"
                  disabled={page.page <= 0 || loading}
                  onClick={() => setPageNumber((n) => Math.max(0, n - 1))}
                >
                  上一页
                </Button>
                <Button
                  variant="secondary"
                  disabled={page.page + 1 >= totalPages || loading}
                  onClick={() => setPageNumber((n) => n + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          </>
        ) : page ? (
          <EmptyState
            title="尚未分配"
            description="这笔预占还没有圈到任何仓 / 货位 / 批次。可在上方筛选里放宽条件，或由履约侧发起分配。"
          />
        ) : null}
      </SectionCard>

      {/* 迁移时间线 */}
      <SectionCard title="状态迁移时间线">
        {timeline && timeline.entries.length > 0 ? (
          <>
            <div className="mb-3 flex items-center gap-2">
              <select
                className="border-border rounded-md border px-2 py-1 text-sm"
                value={timelineTransition}
                onChange={(e) => setTimelineTransition(e.target.value)}
              >
                <option value="">全部迁移</option>
                {RESERVATION_ALLOCATION_TRANSITION_OPTIONS.map((o) => (
                  <option key={o.code} value={o.code}>
                    {o.label}
                  </option>
                ))}
              </select>
              <span className="text-muted-foreground text-xs">共 {timeline.total} 条</span>
            </div>
            <ol className="space-y-2">
              {timeline.entries.map((entry: ReservationAllocationEntryRow) => (
                <li
                  key={entry.id}
                  className="border-border/60 flex flex-wrap items-baseline gap-2 border-b pb-2 text-sm"
                >
                  <span className="text-muted-foreground tabular-nums text-xs">
                    {new Date(entry.occurredAt).toLocaleString('zh-CN', { hour12: false })}
                  </span>
                  <Badge tone="neutral">{allocationLevelLabel(entry.level)}</Badge>
                  <span className="font-medium">
                    {allocationTransitionLabel(entry.transition, entry.transitionLabel ?? undefined)}
                  </span>
                  <span className="text-muted-foreground">
                    {entryFromLabel(entry.fromStatus)} →{' '}
                    {allocationStatusLabel(entry.toStatus, entry.toStatusLabel ?? undefined)}
                  </span>
                  <span className="tabular-nums">{qty(entry.quantity)}</span>
                  {entry.allocationId ? (
                    <span className="text-muted-foreground text-xs">
                      {bucketText(entry.warehouseCode, entry.locationCode, entry.lotCode)}
                    </span>
                  ) : null}
                  {entry.reason ? (
                    <span className="text-muted-foreground text-xs">理由：{entry.reason}</span>
                  ) : null}
                </li>
              ))}
            </ol>
            <p className="text-muted-foreground mt-3 text-xs">{timeline.note}</p>
          </>
        ) : timeline ? (
          <EmptyState
            title="暂无迁移记录"
            description="还没有发生过任何状态迁移。分配一旦登记就会留下第一条流水。"
          />
        ) : (
          <p className="text-muted-foreground text-sm">正在加载时间线…</p>
        )}
      </SectionCard>

      {/* 释放与过期如实说明，不放点下去必然 404 的按钮 */}
      {release && !release.available ? (
        <SectionCard title="释放与过期不在本页">
          <p className="text-muted-foreground text-sm">
            本页没有「释放」按钮：释放与过期属 {release.ownerModule}，尚未交付（{release.note}）。
            蓝图同时明确「承诺分配（committed）后不再自动 TTL 释放」。
          </p>
        </SectionCard>
      ) : null}

      <p className="text-muted-foreground text-xs">
        {ALLOCATION_OWNER_NOTE} {ALLOCATION_TIMEZONE_NOTE}
      </p>
    </div>
  );
}
