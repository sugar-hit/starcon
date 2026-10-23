'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { RefreshCw } from 'lucide-react';
import type {
  AnomalyOrderCapabilities,
  AnomalyOrderDetail,
  AnomalyOrderOptions,
  AnomalyOrderPage,
  AvailableQuantityPage,
  AvailableQuantityRow,
  BuyerBlacklistFlagPage,
  BuyerBlacklistOrderFlagView,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '@/lib/api';
import {
  ACTION_LABELS,
  ANOMALY_TYPE_LABELS,
  ANOMALY_TYPES,
  CROSS_SERVICE_TYPES,
  SEVERITY_LABELS,
  actionTarget,
  batchActions,
  filterMerged,
  formatAmount,
  formatDwell,
  formatQuantity,
  mergeCrossService,
  orderNumberDigest,
  summarizeMerged,
  type MergedAnomalyOrder,
} from './anomaly-order-model';

/**
 * 1.1.1.2 异常订单队列（卷 01 · L3 1.1.1 待办与待决策的第三格）。
 *
 * <p>🔴 **本页面不写任何业务数据**。卷 01 的边界写死：「工作台只读 + 跳转；
 * 一切状态变更回到归属系统完成」。所以这里的「批量标记忽略 / 转人工 / 转售后」
 * 一律表现为**跳转到归属系统的入口 + 交接选中的订单号**，
 * 而不是在这里按一个按钮就改掉订单状态 ——
 * 那会造出第二份真相（页面的处置结论与归属系统的结论对不上）。
 *
 * <p>页面分五块：
 *
 * <ul>
 *   <li><b>能力声明</b>——七类异常哪几类真接通、跨服务两类归谁、
 *       哪些动作**没有**归属入口、以及本仓**根本不存在**的那条线
 *       （平台绩效指标）。</li>
 *   <li><b>异常概览</b>——七类各自的命中数，点一下就按这一类筛清单。</li>
 *   <li><b>异常订单清单</b>——订单号 / 店铺 / 异常类型 / 停留时长 / 建议动作。</li>
 *   <li><b>批量处置</b>——勾选后给出归属系统的入口与订单号清单。</li>
 *   <li><b>单条明细</b>——订单行、费用、状态留痕与逐条异常凭据。</li>
 * </ul>
 *
 * <p>「读不到」与「没有数据」是四件不同的事：加载中、后端读不到（403/503）、
 * 出错可重试、真的没有异常订单。混成一句话，运营就会以为「平台还没配异常规则」
 * 而去找错方向。
 */

/** 清单每页行数。 */
const TRADE_PAGE_SIZE = 200;
/** 清单最多翻几页（超出部分如实报出「只取回前 N 条」）。 */
const TRADE_MAX_PAGES = 5;
/** channel 黑名单标记每页行数。 */
const CHANNEL_PAGE_SIZE = 200;
/** channel 黑名单标记最多翻几页。 */
const CHANNEL_MAX_PAGES = 5;
/** warehouse 可售量每页行数。 */
const WAREHOUSE_PAGE_SIZE = 200;
/** warehouse 可售量最多翻几页。 */
const WAREHOUSE_MAX_PAGES = 10;
/** 清单分页（前端分页：合并后的集合是完整的一份，切页不重取）。 */
const VIEW_PAGE_SIZE = 25;

interface Loaded<T> {
  data: T;
  truncated: boolean;
  total: number;
  error: string;
}

/** 一段读取的结果 + 它是否被页数上限截断 + 总条数。 */
async function loadAll<T>(
  fetchPage: (page: number) => Promise<{ items: T[]; total: number }>,
  maxPages: number,
): Promise<Loaded<T[]>> {
  const items: T[] = [];
  let total = 0;
  try {
    for (let page = 0; page < maxPages; page += 1) {
      const result = await fetchPage(page);
      total = result.total;
      items.push(...result.items);
      if (items.length >= total || result.items.length === 0) break;
    }
  } catch (e) {
    return {
      data: items,
      truncated: total > items.length,
      total,
      error: e instanceof Error ? e.message : '读取失败',
    };
  }
  return { data: items, truncated: total > items.length, total, error: '' };
}

export function AnomalyOrderWorkspace() {
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notices, setNotices] = useState<string[]>([]);

  const [capabilities, setCapabilities] = useState<AnomalyOrderCapabilities | null>(null);
  const [options, setOptions] = useState<AnomalyOrderOptions | null>(null);
  const [rows, setRows] = useState<MergedAnomalyOrder[]>([]);
  const [dataAsOf, setDataAsOf] = useState('');

  const [type, setType] = useState('');
  const [shopCode, setShopCode] = useState('');
  const [keyword, setKeyword] = useState('');
  const [dwell, setDwell] = useState('ALL');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<string[]>([]);
  const [detail, setDetail] = useState<AnomalyOrderDetail | null>(null);
  const [detailError, setDetailError] = useState('');

  /**
   * 传给后端的筛选条件。
   *
   * <p>⚠️ **异常类型不进后端查询**：跨服务两类（黑名单 / 库存）的事实不在 trade，
   * 后端按类型过滤会把它们整类漏掉。类型筛选统一在前端合并之后做，
   * 这样「按黑名单筛」与「按地址不可达筛」用的是**同一份集合**。
   */
  const serverQuery = useMemo(() => {
    const parts = new URLSearchParams();
    if (shopCode.trim()) parts.set('shopCode', shopCode.trim());
    if (keyword.trim()) parts.set('keyword', keyword.trim());
    const preset = options?.dwellPresets?.find((p) => p.code === dwell);
    if (preset && preset.minHours > 0) parts.set('minDwellHours', String(preset.minHours));
    return parts.toString();
  }, [shopCode, keyword, dwell, options]);

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    const notes: string[] = [];
    try {
      const [caps, opts, trade, flags, sellable] = await Promise.all([
        api<AnomalyOrderCapabilities>('trade/anomaly-orders/capabilities'),
        api<AnomalyOrderOptions>('trade/anomaly-orders/options'),
        loadAll<AnomalyOrderPage['items'][number]>(
          async (pageNo) => {
            const query = new URLSearchParams(serverQuery);
            query.set('page', String(pageNo));
            query.set('size', String(TRADE_PAGE_SIZE));
            const result = await api<AnomalyOrderPage>(`trade/anomaly-orders?${query.toString()}`);
            return { items: result.items, total: result.total };
          },
          TRADE_MAX_PAGES,
        ),
        loadAll<BuyerBlacklistOrderFlagView>(
          async (pageNo) => {
            const result = await api<BuyerBlacklistFlagPage>(
              `channel/buyer-blacklist/flags?page=${pageNo}&size=${CHANNEL_PAGE_SIZE}`,
            );
            return { items: result.items, total: result.total };
          },
          CHANNEL_MAX_PAGES,
        ),
        loadAll<AvailableQuantityRow>(
          async (pageNo) => {
            const result = await api<AvailableQuantityPage>(
              `warehouse/inventory/sellable/skus?page=${pageNo}&size=${WAREHOUSE_PAGE_SIZE}`,
            );
            return { items: result.rows, total: result.total };
          },
          WAREHOUSE_MAX_PAGES,
        ),
      ]);

      setCapabilities(caps);
      setOptions(opts);

      if (trade.error) throw new Error(trade.error);
      if (flags.error) {
        notes.push(
          `黑名单标记读不到（${flags.error}）—— 本次**没有**黑名单买家信号，不等于「没有黑名单买家」。`,
        );
      }
      if (sellable.error) {
        notes.push(
          `库存可售量读不到（${sellable.error}）—— 本次**判不了**库存不足，不等于「库存充足」。`,
        );
      }
      if (trade.truncated) {
        notes.push(
          `异常订单清单只取回前 ${trade.data.length} 条（区间内共 ${trade.total} 条），后面的没取回来。`,
        );
      }
      if (flags.truncated) {
        notes.push(
          `黑名单标记只取回前 ${flags.data.length} 条（区间内共 ${flags.total} 条），后面的没取回来。`,
        );
      }
      if (sellable.truncated) {
        notes.push(
          `库存可售量只取回前 ${sellable.data.length} 行（区间内共 ${sellable.total} 行），后面的没取回来。`,
        );
      }

      const sellableBySku = new Map<string, AvailableQuantityRow>();
      for (const row of sellable.data) sellableBySku.set(row.skuId, row);

      setRows(mergeCrossService(trade.data, flags.data, sellableBySku));
      setDataAsOf(caps.dataAsOf);
      setNotices(notes);
      setSelected([]);
      setPage(0);
    } catch (e) {
      setError(e instanceof Error ? e.message : '请求失败，请重试');
    } finally {
      setBusy(false);
      setReady(true);
    }
  }, [serverQuery]);

  useEffect(() => {
    void load();
  }, [load]);

  const visible = useMemo(
    () => filterMerged(rows, { type, shopCode: '', keyword: '', minDwellHours: 0 }),
    [rows, type],
  );
  const summary = useMemo(() => summarizeMerged(visible), [visible]);
  const pageCount = Math.max(1, Math.ceil(visible.length / VIEW_PAGE_SIZE));
  const current = Math.min(page, pageCount - 1);
  const pageRows = visible.slice(current * VIEW_PAGE_SIZE, current * VIEW_PAGE_SIZE + VIEW_PAGE_SIZE);
  const selectedRows = visible.filter((row) => selected.includes(row.row.orderId));
  const actions = batchActions(capabilities);

  async function openDetail(orderId: string) {
    setDetailError('');
    try {
      setDetail(await api<AnomalyOrderDetail>(`trade/anomaly-orders/${orderId}`));
    } catch (e) {
      setDetail(null);
      setDetailError(e instanceof Error ? e.message : '读取明细失败，请重试');
    }
  }

  function toggle(orderId: string) {
    setSelected((currentSelection) =>
      currentSelection.includes(orderId)
        ? currentSelection.filter((id) => id !== orderId)
        : [...currentSelection, orderId],
    );
  }

  function toggleAll() {
    const ids = pageRows.map((row) => row.row.orderId);
    const allSelected = ids.every((id) => selected.includes(id));
    setSelected((currentSelection) =>
      allSelected
        ? currentSelection.filter((id) => !ids.includes(id))
        : [...new Set([...currentSelection, ...ids])],
    );
  }

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载异常订单队列…
      </p>
    );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          异常订单队列是卷 01 的<b>只读聚合层</b>：它不产生新的业务事实，
          只把 09 订单、07 库存、16 合规三处的投影并到一屏上。
          页面上的每个数字都能点进去看到明细，
          <b>处置动作一律回到归属系统完成</b>（工作台不写业务数据）。
        </p>
        <Button variant="ghost" size="sm" onClick={() => void load()} disabled={busy}>
          <RefreshCw className="h-4 w-4" />
          刷新
        </Button>
      </div>

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          {error}
          <Button variant="link" onClick={() => void load()}>
            重试
          </Button>
        </div>
      )}
      {notices.map((notice) => (
        <p
          key={notice}
          role="status"
          className="border-warning/40 bg-warning/5 rounded-md border p-3 text-copy-12"
        >
          {notice}
        </p>
      ))}

      {/* ---------------- 能力声明 ---------------- */}
      {capabilities && (
        <section className="space-y-2">
          <h2 className="text-copy-15 font-medium">能力声明</h2>
          <ul className="space-y-1">
            {capabilities.dimensions.map((dimension) => (
              <li
                key={dimension.dimension}
                className="text-copy-12 text-muted-foreground rounded-md border p-2"
              >
                <Badge tone={dimension.aggregation === 'FRONTEND' ? 'info' : 'neutral'}>
                  {dimension.aggregation === 'FRONTEND' ? '前端聚合' : '本服务判定'}
                </Badge>
                <span className="ml-2">
                  {ANOMALY_TYPE_LABELS[dimension.dimension] ?? dimension.dimension}：
                  事实来源 {dimension.source} · 归属 {dimension.ownerModule}
                </span>
              </li>
            ))}
          </ul>
          {capabilities.crossServiceSources.length > 0 && (
            <ul className="space-y-1">
              {capabilities.crossServiceSources.map((source) => (
                <li key={source.dimension} className="text-copy-12 text-muted-foreground">
                  <Badge tone="info">跨服务</Badge>
                  <span className="ml-2">
                    {ANOMALY_TYPE_LABELS[source.dimension] ?? source.dimension} · 归属{' '}
                    {source.ownerModule} · 前端读 {source.endpoint} · 连接键 {source.joinKey}
                  </span>
                </li>
              ))}
            </ul>
          )}
          {capabilities.notConnected.map((text) => (
            <p key={text} className="border-warning/40 bg-warning/5 rounded-md border p-2 text-copy-12">
              <Badge tone="warning">未接通</Badge>
              <span className="ml-2">{text}</span>
            </p>
          ))}
          <p className="text-copy-12 text-muted-foreground">
            判定阈值：平台发货回传超过 {capabilities.platformWritebackTimeoutHours} 小时未确认、
            平台单超过 {capabilities.buyerMessageTimeoutHours} 小时未确认，才算异常。
            数据截至 {capabilities.dataAsOf}。
          </p>
        </section>
      )}

      {/* ---------------- 异常概览（点一下就按这一类筛） ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-15 font-medium">异常概览</h2>
        <div className="flex flex-wrap gap-2">
          {ANOMALY_TYPES.map((code) => (
            <button
              key={code}
              type="button"
              onClick={() => {
                setType((current) => (current === code ? '' : code));
                setPage(0);
              }}
              className={`rounded-md border px-3 py-2 text-left text-copy-12 ${
                type === code ? 'border-primary bg-primary/5' : ''
              }`}
            >
              <span className="text-muted-foreground">
                {ANOMALY_TYPE_LABELS[code]}
                {CROSS_SERVICE_TYPES.includes(code) ? '（跨服务）' : ''}
              </span>
              <span className="ml-2 font-medium">{summary.byType[code] ?? 0} 单</span>
            </button>
          ))}
        </div>
        <p className="text-copy-12 text-muted-foreground">
          区间内共 {summary.total} 单异常订单，最久的一单已经停了{' '}
          {formatDwell(summary.oldestDwellSeconds)}。
        </p>
      </section>

      {/* ---------------- 筛选 ---------------- */}
      <section className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">异常类型</span>
          <select
            className="border rounded px-2 py-1 text-copy-12"
            value={type}
            onChange={(e) => {
              setType(e.target.value);
              setPage(0);
            }}
          >
            <option value="">全部类型</option>
            {ANOMALY_TYPES.map((code) => (
              <option key={code} value={code}>
                {ANOMALY_TYPE_LABELS[code]}
                {CROSS_SERVICE_TYPES.includes(code) ? '（跨服务）' : ''}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">店铺</span>
          <select
            className="border rounded px-2 py-1 text-copy-12"
            value={shopCode}
            onChange={(e) => {
              setShopCode(e.target.value);
              setPage(0);
            }}
          >
            <option value="">全部店铺</option>
            {(options?.shops ?? []).map((shop) => (
              <option key={shop} value={shop}>
                {shop}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">停留时长</span>
          <select
            className="border rounded px-2 py-1 text-copy-12"
            value={dwell}
            onChange={(e) => {
              setDwell(e.target.value);
              setPage(0);
            }}
          >
            {(options?.dwellPresets ?? [{ code: 'ALL', label: '不限', minHours: 0 }]).map((preset) => (
              <option key={preset.code} value={preset.code}>
                {preset.label}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">订单号 / 外部单号</span>
          <input
            className="border rounded px-2 py-1 text-copy-12"
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value);
              setPage(0);
            }}
            placeholder="SO-…"
          />
        </label>
      </section>

      {/* ---------------- 异常订单清单 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-15 font-medium">异常订单清单</h2>
        {visible.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">
              当前筛选下**没有**异常订单。
              这既可能说明这批订单都正常，也可能说明相关的判定还没跑过
              （地址校验、风控评估、发货回传任务都是各模块自己的留痕，
              没有留痕就没有异常）—— 能力声明里写明了每类异常的事实来源。
            </p>
          </div>
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-copy-12">
                <thead className="text-muted-foreground text-left">
                  <tr>
                    <th className="p-2">
                      <input
                        type="checkbox"
                        aria-label="全选本页"
                        checked={
                          pageRows.length > 0 &&
                          pageRows.every((row) => selected.includes(row.row.orderId))
                        }
                        onChange={toggleAll}
                      />
                    </th>
                    <th className="p-2">订单号</th>
                    <th className="p-2">店铺</th>
                    <th className="p-2">异常类型</th>
                    <th className="p-2">停留时长</th>
                    <th className="p-2">严重度</th>
                    <th className="p-2">建议动作</th>
                    <th className="p-2">明细</th>
                  </tr>
                </thead>
                <tbody>
                  {pageRows.map((row) => (
                    <tr key={row.row.orderId} className="border-t">
                      <td className="p-2">
                        <input
                          type="checkbox"
                          aria-label={`选择 ${row.row.orderNo}`}
                          checked={selected.includes(row.row.orderId)}
                          onChange={() => toggle(row.row.orderId)}
                        />
                      </td>
                      <td className="p-2">
                        <div className="font-mono">{row.row.orderNo}</div>
                        <div className="text-muted-foreground">
                          {row.row.platformCode || row.row.orderSource} ·{' '}
                          {formatAmount(row.row.currency, row.row.grandTotalAmount)}
                        </div>
                      </td>
                      <td className="p-2">{row.row.shopCode || '—'}</td>
                      <td className="p-2">
                        <div className="flex flex-wrap gap-1">
                          {row.types.map((code) => (
                            <Badge
                              key={code}
                              tone={CROSS_SERVICE_TYPES.includes(code) ? 'info' : 'warning'}
                            >
                              {ANOMALY_TYPE_LABELS[code] ?? code}
                            </Badge>
                          ))}
                        </div>
                        {row.shortages.length > 0 && (
                          <p className="text-muted-foreground mt-1">
                            缺货：{row.shortages.map((s) => `${s.skuCode} 需 ${formatQuantity(s.need)} / 可售 ${formatQuantity(s.sellable)}`).join('；')}
                          </p>
                        )}
                        {row.unknownSkus.length > 0 && (
                          <p className="text-muted-foreground mt-1">
                            查无库存账：{row.unknownSkus.join('、')}（判不了，不等于库存充足）
                          </p>
                        )}
                        {row.blacklist && (
                          <p className="text-muted-foreground mt-1">
                            黑名单：{row.blacklist.riskLevel} · 建议{' '}
                            {row.blacklist.recommendedAction}
                          </p>
                        )}
                      </td>
                      <td className="p-2">{formatDwell(row.dwellSeconds)}</td>
                      <td className="p-2">
                        <Badge
                          tone={
                            row.severity === 'CRITICAL'
                              ? 'danger'
                              : row.severity === 'HIGH'
                                ? 'warning'
                                : 'neutral'
                          }
                        >
                          {SEVERITY_LABELS[row.severity] ?? row.severity}
                        </Badge>
                      </td>
                      <td className="p-2">
                        <div className="flex flex-wrap gap-1">
                          {row.actions.map((code) => (
                            <a
                              key={code}
                              href={actionTarget(code, row.row.orderId)}
                              className="text-brand underline-offset-4 hover:underline"
                            >
                              {ACTION_LABELS[code] ?? code}
                            </a>
                          ))}
                        </div>
                      </td>
                      <td className="p-2">
                        <Button variant="link" size="sm" onClick={() => void openDetail(row.row.orderId)}>
                          查看
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex items-center justify-between text-copy-12">
              <span className="text-muted-foreground">
                第 {current + 1} / {pageCount} 页 · 共 {visible.length} 单
              </span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={current <= 0}
                  onClick={() => setPage(current - 1)}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={current >= pageCount - 1}
                  onClick={() => setPage(current + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          </>
        )}
      </section>

      {/* ---------------- 批量处置 ---------------- */}
      {selectedRows.length > 0 && (
        <section className="bg-card space-y-2 rounded-lg border p-3">
          <h2 className="text-copy-15 font-medium">
            批量处置（已选 {selectedRows.length} 单）
          </h2>
          <p className="text-copy-12 text-muted-foreground">
            🔴 工作台**不写业务数据**：批量动作一律落到归属系统完成。
            下面每个入口都是归属系统的真实页面，页面不会替你改订单状态 ——
            选中订单号 {orderNumberDigest(selectedRows) || '（无）'} 请带过去。
          </p>
          <div className="flex flex-wrap gap-2">
            {actions.map((action) => {
              const target = actionTarget(action.code);
              if (!action.available) {
                return (
                  <span
                    key={action.code}
                    className="border-warning/40 bg-warning/5 text-copy-12 rounded-md border p-2"
                  >
                    <Badge tone="warning">缺入口</Badge>
                    <span className="ml-2">
                      {ACTION_LABELS[action.code] ?? action.label}（归属 {action.ownerModule}）：
                      {action.impact}
                    </span>
                  </span>
                );
              }
              return (
                <a
                  key={action.code}
                  href={target}
                  className="border rounded-md border-control-border px-3 py-2 text-copy-12 hover:bg-muted"
                >
                  {ACTION_LABELS[action.code] ?? action.label}
                  <span className="text-muted-foreground ml-2">→ {action.ownerModule}</span>
                </a>
              );
            })}
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              const text = orderNumberDigest(selectedRows);
              if (typeof navigator !== 'undefined' && navigator.clipboard) {
                void navigator.clipboard.writeText(text);
              }
            }}
          >
            复制选中订单号
          </Button>
        </section>
      )}

      {/* ---------------- 单条明细 ---------------- */}
      {detailError && (
        <p role="alert" className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm">
          {detailError}
        </p>
      )}
      {detail && (
        <section className="bg-card space-y-3 rounded-lg border p-4">
          <div className="flex items-center justify-between">
            <h2 className="text-copy-15 font-medium">
              明细 · {detail.order.orderNo}
            </h2>
            <Button variant="ghost" size="sm" onClick={() => setDetail(null)}>
              关闭
            </Button>
          </div>
          <p className="text-copy-12 text-muted-foreground">
            买家 {detail.buyerName || '—'} · {detail.buyerPhone || '—'} · {detail.buyerEmail || '—'} ·
            收货地 {detail.shippingCountry || '—'} {detail.shippingAddress}
          </p>

          <div>
            <h3 className="text-copy-13 font-medium">异常凭据</h3>
            {detail.evidence.length === 0 ? (
              <p className="text-copy-12 text-muted-foreground">
                这张单当前没有异常凭据（可能已被处置，或相关的判定还没跑过）。
              </p>
            ) : (
              <ul className="space-y-2">
                {detail.evidence.map((evidence) => (
                  <li key={evidence.dimension} className="rounded-md border p-2 text-copy-12">
                    <Badge tone="warning">
                      {ANOMALY_TYPE_LABELS[evidence.dimension] ?? evidence.dimension}
                    </Badge>
                    <span className="ml-2">{evidence.summary}</span>
                    <span className="text-muted-foreground ml-2">
                      已停 {formatDwell(evidence.dwellSeconds)}
                    </span>
                    <ul className="text-muted-foreground mt-1 list-disc pl-5">
                      {evidence.facts.map((fact) => (
                        <li key={fact}>{fact}</li>
                      ))}
                    </ul>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div>
            <h3 className="text-copy-13 font-medium">订单行</h3>
            <ul className="space-y-1">
              {detail.lines.map((line) => (
                <li key={line.lineNo} className="text-copy-12">
                  #{line.lineNo} {line.skuCode} × {formatQuantity(line.quantity)} · 单价{' '}
                  {formatAmount(detail.order.currency, line.unitPrice)} · 小计{' '}
                  {formatAmount(detail.order.currency, line.lineAmount)}
                </li>
              ))}
            </ul>
          </div>

          <div>
            <h3 className="text-copy-13 font-medium">订单费用</h3>
            {detail.charges.length === 0 ? (
              <p className="text-copy-12 text-muted-foreground">这张单没有费用行。</p>
            ) : (
              <ul className="space-y-1">
                {detail.charges.map((charge, index) => (
                  <li key={`${charge.chargeType}-${index}`} className="text-copy-12">
                    {charge.chargeType}
                    {charge.label ? ` · ${charge.label}` : ''} ·{' '}
                    {formatAmount(detail.order.currency, charge.amount)}
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div>
            <h3 className="text-copy-13 font-medium">状态留痕</h3>
            <ul className="space-y-1">
              {detail.statusHistory.map((event, index) => (
                <li key={`${event.occurredAt}-${index}`} className="text-copy-12">
                  {event.occurredAt} · {event.fromStatus} → {event.toStatus}
                  {event.reason ? ` · ${event.reason}` : ''}
                </li>
              ))}
            </ul>
          </div>

          <div>
            <h3 className="text-copy-13 font-medium">建议动作</h3>
            <div className="flex flex-wrap gap-2">
              {detail.actions.map((action) => (
                <span key={action.code} className="text-copy-12">
                  {action.available ? (
                    <a
                      href={actionTarget(action.code, detail.order.orderId)}
                      className="text-brand underline-offset-4 hover:underline"
                    >
                      {ACTION_LABELS[action.code] ?? action.label}
                    </a>
                  ) : (
                    <span className="text-muted-foreground">
                      {ACTION_LABELS[action.code] ?? action.label}（{action.impact}）
                    </span>
                  )}
                </span>
              ))}
            </div>
          </div>
        </section>
      )}

      {dataAsOf && (
        <p className="text-copy-12 text-muted-foreground">数据截至 {dataAsOf}</p>
      )}
    </div>
  );
}
