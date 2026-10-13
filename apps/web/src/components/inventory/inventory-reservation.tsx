'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { Badge, Button, EmptyState, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  ReservationCapability,
  ReservationPage,
  StatusOption,
} from '../../../../../packages/api-contracts/src/index';
import {
  REMAINING_NOTE,
  RESERVATION_SOURCE_TYPE_OPTIONS,
  RESERVATION_SORT_OPTIONS,
  RESERVATION_STATUS_OPTIONS,
  allocatedText,
  countOf,
  fetchReservationCapabilities,
  fetchReservationOptions,
  fetchReservations,
  isTerminalStatus,
  overdueIsSevere,
  overdueText,
  reservationSourceSystemLabel,
  reservationSourceTypeLabel,
  reservationStatusLabel,
  summaryHint,
} from './inventory-reservation-model';
import { errorText, qty } from './inventory-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.2.1.1 预占需求列表工作区。
 *
 * <p>蓝图：「展示所有预占需求（来自订单履约、加工领料、其他占用），含需求来源、数量、
 * 状态（held/committed/consumed/released/expired）、剩余量、超时时长。」输出「预占需求列表
 * + 状态汇总」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>超时长 null 显示「—」，绝不显示 0</b>。null 的含义是「没超时 / 已终态 / 没有过期时间」，
 *       0 的含义是「超时 0 秒」—— 写死成 0 会让每一条正常的预占都显示「超时 0 秒」。
 *   <li><b>合计卡固定五行，0 也显示</b>。少一行的状态分布会让人以为「这个状态不存在」，
 *       而它只是当前没有。且合计不随筛选变化，旁边写明。
 *   <li><b>已分配量为 0 时标注「分配明细未交付」</b>，而不是显示一个看起来正常的 0。
 *   <li><b>没有「释放」按钮</b>。释放与过期属 7.2.1.3，未交付；
 *       放一个点下去必然 404 的按钮比不放更糟。页面按 capabilities 如实说明去向。
 * </ul>
 */
export function InventoryReservationWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');

  const [page, setPage] = useState<ReservationPage | null>(null);
  const [options, setOptions] = useState<StatusOption[]>(RESERVATION_STATUS_OPTIONS);
  const [capabilities, setCapabilities] = useState<ReservationCapability[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [statusFilter, setStatusFilter] = useState<string[]>([]);
  const [sourceType, setSourceType] = useState('');
  const [keyword, setKeyword] = useState('');
  const [overdueOnly, setOverdueOnly] = useState(false);
  const [sort, setSort] = useState('REQUESTED_AT');
  const [pageNumber, setPageNumber] = useState(0);

  const load = useCallback(async () => {
    if (!readable) return;
    setLoading(true);
    setError('');
    try {
      const [p, o, c] = await Promise.all([
        fetchReservations({
          ...(statusFilter.length ? { status: statusFilter } : {}),
          ...(sourceType ? { sourceType } : {}),
          ...(keyword.trim() ? { keyword: keyword.trim() } : {}),
          ...(overdueOnly ? { overdueOnly: true } : {}),
          sort,
          page: pageNumber,
          size: 20,
        }),
        fetchReservationOptions().catch(() => null),
        fetchReservationCapabilities().catch(() => null),
      ]);
      setPage(p);
      if (o?.statuses?.length) setOptions(o.statuses);
      setCapabilities(c ?? []);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [readable, statusFilter, sourceType, keyword, overdueOnly, sort, pageNumber]);

  useEffect(() => {
    void load();
  }, [load]);

  const toggleStatus = useCallback((code: string) => {
    setPageNumber(0);
    setStatusFilter((prev) =>
      prev.includes(code) ? prev.filter((s) => s !== code) : [...prev, code],
    );
  }, []);

  const capabilityOf = useMemo(() => {
    const map = new Map<string, ReservationCapability>();
    for (const c of capabilities) map.set(c.dimension, c);
    return map;
  }, [capabilities]);

  const allocation = capabilityOf.get('ALLOCATION_DETAIL');
  const release = capabilityOf.get('RELEASE');

  if (!readable) {
    return (
      <EmptyState
        title="没有预占需求查看权限"
        description="预占需求属库存卷，需要 warehouse.inventory.read 权限。"
      />
    );
  }

  const summary = page?.summary;
  const totalPages = page ? Math.max(1, Math.ceil(page.total / Math.max(1, page.size))) : 1;

  return (
    <div className="space-y-6">
      <PageHeader
        title="预占需求列表"
        description="所有预占需求：需求来源、数量、状态、剩余量与超时时长。"
        actions={
          <Button variant="ghost" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'animate-spin' : ''} />
            刷新
          </Button>
        }
      />

      {error ? (
        <SectionCard>
          <div className="space-y-3">
            <p className="text-sm text-red-600">{error}</p>
            <Button variant="secondary" onClick={() => void load()}>
              重试
            </Button>
          </div>
        </SectionCard>
      ) : null}

      {/* 状态汇总：固定五个状态，0 也显示；合计不随筛选与分页变化 */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="预占总条数"
          value={summary ? String(summary.totalCount) : '—'}
          unit="条"
          comparisonLabel={summaryHint()}
        />
        <StatCard
          label="待释放（已超时）"
          value={summary ? String(summary.overdueCount) : '—'}
          unit="条"
          comparisonLabel={
            summary ? `占着 ${qty(summary.overdueQuantity)} 件可用量` : '已超过过期时刻且仍在预占中'
          }
        />
        <StatCard
          label="需求量合计"
          value={summary ? qty(summary.requestedQuantity) : '—'}
          unit="件"
          comparisonLabel="业务提出来的预占总量"
        />
        <StatCard
          label="剩余量合计"
          value={summary ? qty(summary.remainingQuantity) : '—'}
          unit="件"
          comparisonLabel={REMAINING_NOTE}
        />
      </div>

      {/* 五态分布固定五行 */}
      <SectionCard title="状态分布">
        <div className="flex flex-wrap gap-2">
          {options.map((option) => {
            const count = countOf(summary, option.code);
            const active = statusFilter.includes(option.code);
            return (
              <button
                key={option.code}
                type="button"
                onClick={() => toggleStatus(option.code)}
                aria-pressed={active}
                className={`rounded-lg border px-3 py-2 text-left text-sm transition ${
                  active ? 'border-primary bg-primary/10' : 'border-border hover:border-primary/50'
                }`}
              >
                <span className="block font-medium">{option.label}</span>
                <span className="text-muted-foreground block text-xs">
                  {loading && !summary ? '—' : `${count} 条`}
                  {option.terminal ? ' · 终态' : ''}
                </span>
              </button>
            );
          })}
        </div>
      </SectionCard>

      {/* 筛选 */}
      <SectionCard title="筛选">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <label className="text-sm">
            <span className="text-muted-foreground mb-1 block">来源类型</span>
            <select
              className="border-border bg-background w-full rounded-md border px-2 py-1.5"
              value={sourceType}
              onChange={(e) => {
                setPageNumber(0);
                setSourceType(e.target.value);
              }}
            >
              <option value="">全部</option>
              {RESERVATION_SOURCE_TYPE_OPTIONS.map((o) => (
                <option key={o.code} value={o.code}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <label className="text-sm">
            <span className="text-muted-foreground mb-1 block">排序</span>
            <select
              className="border-border bg-background w-full rounded-md border px-2 py-1.5"
              value={sort}
              onChange={(e) => {
                setPageNumber(0);
                setSort(e.target.value);
              }}
            >
              {RESERVATION_SORT_OPTIONS.map((o) => (
                <option key={o.code} value={o.code}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <label className="text-sm">
            <span className="text-muted-foreground mb-1 block">关键字</span>
            <input
              className="border-border bg-background w-full rounded-md border px-2 py-1.5"
              value={keyword}
              placeholder="预占单号 / 上游单号 / SKU"
              onChange={(e) => {
                setPageNumber(0);
                setKeyword(e.target.value);
              }}
            />
          </label>
          <label className="flex items-end gap-2 text-sm">
            <input
              type="checkbox"
              checked={overdueOnly}
              onChange={(e) => {
                setPageNumber(0);
                setOverdueOnly(e.target.checked);
              }}
            />
            <span>只看已超时</span>
          </label>
        </div>
      </SectionCard>

      {/* 未交付能力如实说明，不放点下去必然 404 的按钮 */}
      {allocation && !allocation.available ? (
        <SectionCard title="分配明细尚未交付">
          <p className="text-muted-foreground text-sm">
            「已分配量」恒为 0 是因为 {allocation.ownerModule} 预占分配明细未交付（{allocation.note}
            ）。 已分配但未出库的部分不计入剩余量 —— 那部分货还在仓里占着可用量。
          </p>
        </SectionCard>
      ) : null}
      {release && !release.available ? (
        <SectionCard title="释放与过期尚未交付">
          <p className="text-muted-foreground text-sm">
            本页没有「释放」按钮：释放与过期属 {release.ownerModule}，尚未交付（{release.note}）。
            蓝图同时明确「承诺分配（committed）后不再自动 TTL
            释放」，所以「已承诺」的预占不计入超时候选。
          </p>
        </SectionCard>
      ) : null}

      {/* 列表 */}
      <SectionCard title="预占需求">
        {loading && !page ? (
          <p className="text-muted-foreground text-sm">正在加载预占需求…</p>
        ) : page && page.rows.length > 0 ? (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-border text-muted-foreground border-b text-left">
                    <th className="p-2">预占单号</th>
                    <th className="p-2">需求来源</th>
                    <th className="p-2">SKU</th>
                    <th className="p-2 text-right">需求量</th>
                    <th className="p-2 text-right">已分配</th>
                    <th className="p-2 text-right">剩余量</th>
                    <th className="p-2">状态</th>
                    <th className="p-2">超时时长</th>
                  </tr>
                </thead>
                <tbody>
                  {page.rows.map((row) => (
                    <tr key={row.id} className="border-border/50 border-b">
                      <td className="p-2">
                        <div>{row.reservationNo}</div>
                        {row.referenceCode ? (
                          <div className="text-muted-foreground text-xs">{row.referenceCode}</div>
                        ) : null}
                      </td>
                      <td className="p-2">
                        <div>
                          {reservationSourceTypeLabel(
                            row.sourceType,
                            row.sourceTypeLabel ?? undefined,
                          )}
                        </div>
                        <div className="text-muted-foreground text-xs">
                          {reservationSourceSystemLabel(
                            row.sourceSystem,
                            row.sourceSystemLabel ?? undefined,
                          )}
                        </div>
                      </td>
                      <td className="p-2">
                        <div>{row.skuCode ?? '—'}</div>
                        <div className="text-muted-foreground text-xs">{row.skuName ?? ''}</div>
                      </td>
                      <td className="p-2 text-right tabular-nums">{qty(row.requestedQuantity)}</td>
                      <td className="text-muted-foreground p-2 text-right tabular-nums">
                        {allocatedText(row.allocatedQuantity)}
                      </td>
                      <td className="p-2 text-right tabular-nums">{qty(row.remainingQuantity)}</td>
                      <td className="p-2">
                        <Badge tone={isTerminalStatus(row.status) ? 'neutral' : 'info'}>
                          {reservationStatusLabel(row.status, row.statusLabel ?? undefined)}
                        </Badge>
                      </td>
                      <td
                        className={`p-2 tabular-nums ${
                          overdueIsSevere(row.overdueSeconds) ? 'font-medium text-red-600' : ''
                        }`}
                      >
                        {overdueText(row.overdueSeconds)}
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
        ) : (
          <EmptyState
            title="暂无预占需求"
            description="没有符合当前筛选条件的预占。可清空筛选或重置关键字再试。"
          />
        )}
      </SectionCard>
    </div>
  );
}
