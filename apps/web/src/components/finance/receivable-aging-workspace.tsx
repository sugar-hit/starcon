'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  FinanceAgingBucketDef,
  FinanceAgingBucketInput,
  FinanceReceivableAgingBucketConfig,
  FinanceReceivableAgingCapabilities,
  FinanceReceivableAgingDistribution,
  FinanceReceivableAgingOptions,
  FinanceReceivableAgingOverduePage,
} from '../../../../../packages/api-contracts/src/index';
import {
  agingBucketLabel,
  agingBucketRange,
  agingBucketSourceLabel,
  agingDimensionLabel,
  fetchReceivableAgingBuckets,
  fetchReceivableAgingCapabilities,
  fetchReceivableAgingDistribution,
  fetchReceivableAgingOptions,
  fetchReceivableAgingOverdue,
  formatAgingAmount,
  formatAgingMoney,
  formatAgingShare,
  formatOverdueDays,
  updateReceivableAgingBuckets,
} from './receivable-aging-model';

/**
 * 12.1.1.2 应收账龄。
 *
 * <p>蓝图 12-finance.md：「按账龄区间（未到期/1-30/31-60/61-90/90+）统计应收分布，支持按店铺、客户、币种维度查看，识别逾期集中风险。」输出：「账龄分布报表 + 逾期清单。」
 *
 * <p><b>页面围绕那三条硬要求组织</b>：
 *
 * <ol>
 *   <li><b>争议中金额单列</b>（跨境应收最大的风险是平台争议冻结）：分布表的每一行、
 *       每个币种合计里都有一栏「争议中」，逾期清单里争议单还带一个「争议中」标记。</li>
 *   <li><b>币种分开</b>：所有汇总按币种分组渲染，页面**不做任何业务算术** ——
 *       USD 与 EUR 加起来不是任何一个金额。</li>
 *   <li><b>识别逾期集中风险</b>：维度下钻（店铺 / 客户 / 币种）+ 服务端算好的集中度
 *       （逾期额最多的那一维占该币种逾期总额的比例）。</li>
 * </ol>
 *
 * <p><b>账龄区间是租户自己的口径</b>：页面第一块就是区间配置，名称与边界都取服务端下发的值，
 * 页面**不写死**五档 —— 租户改了档位，页面跟着变。
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有未核销应收」。
 */

/** 区间编辑器的草稿行（原始字符串，避免输入中途就被当成数字）。 */
interface BucketDraft {
  code: string;
  label: string;
  from: string;
  to: string;
}

const DIMENSIONS = ['SHOP', 'COUNTERPARTY', 'CURRENCY'] as const;

function draftOf(bucket: FinanceAgingBucketDef): BucketDraft {
  return {
    code: bucket.code,
    label: bucket.label,
    from: bucket.overdueFromDays === null ? '' : String(bucket.overdueFromDays),
    to: bucket.overdueToDays === null ? '' : String(bucket.overdueToDays),
  };
}

function toInput(draft: BucketDraft, sortOrder: number): FinanceAgingBucketInput {
  const from = draft.from.trim() === '' ? null : Number(draft.from);
  const to = draft.to.trim() === '' ? null : Number(draft.to);
  return {
    code: draft.code,
    label: draft.label.trim(),
    overdueFromDays: Number.isNaN(from as number) ? null : from,
    overdueToDays: Number.isNaN(to as number) ? null : to,
    sortOrder,
  };
}

export function ReceivableAgingWorkspace() {
  const [caps, setCaps] = useState<FinanceReceivableAgingCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceReceivableAgingOptions | null>(null);
  const [config, setConfig] = useState<FinanceReceivableAgingBucketConfig | null>(null);
  const [distribution, setDistribution] = useState<FinanceReceivableAgingDistribution | null>(null);
  const [overdue, setOverdue] = useState<FinanceReceivableAgingOverduePage | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选（分布与逾期清单共用同一套条件）
  const [currency, setCurrency] = useState('');
  const [shopId, setShopId] = useState('');
  const [counterpartyRef, setCounterpartyRef] = useState('');
  const [bucketCode, setBucketCode] = useState('');
  const [disputeOnly, setDisputeOnly] = useState(false);
  const [dimension, setDimension] = useState<(typeof DIMENSIONS)[number]>('SHOP');
  const [currentPage, setCurrentPage] = useState(0);

  // 区间配置草稿
  const [drafts, setDrafts] = useState<BucketDraft[]>([]);

  /**
   * 筛选参数。
   *
   * ⚠️ `exactOptionalPropertyTypes` 开着（AGENTS.md）：**没填的筛选项不能显式给 undefined** ——
   *   所以这里按「填了才建键」处理，而不是一律塞 undefined。
   */
  const params = useMemo(
    () => ({
      ...(currency ? { currency } : {}),
      ...(shopId ? { shopId } : {}),
      ...(counterpartyRef ? { counterpartyRef } : {}),
      ...(bucketCode ? { bucketCode } : {}),
      ...(disputeOnly ? { disputeOnly } : {}),
    }),
    [currency, shopId, counterpartyRef, bucketCode, disputeOnly],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, b, d, p] = await Promise.all([
        fetchReceivableAgingCapabilities(),
        fetchReceivableAgingOptions(),
        fetchReceivableAgingBuckets(),
        fetchReceivableAgingDistribution(dimension, params),
        fetchReceivableAgingOverdue(params, currentPage, 50),
      ]);
      setCaps(c);
      setOptions(o);
      setConfig(b);
      setDistribution(d);
      setOverdue(p);
    } catch (e) {
      setError(e instanceof Error ? e.message : '账龄报表加载失败');
    } finally {
      setLoading(false);
    }
  }, [dimension, params, currentPage]);

  useEffect(() => {
    void load();
  }, [load]);

  // 区间草稿跟着服务端下发的配置走（配置改了 → 草稿重置）。
  useEffect(() => {
    setDrafts((config?.buckets ?? []).map(draftOf));
  }, [config]);

  async function saveBuckets() {
    setActionError('');
    setNotice('');
    try {
      const saved = await updateReceivableAgingBuckets(
        drafts.map((draft, index) => toInput(draft, index)),
      );
      setConfig(saved);
      setNotice(`账龄区间已更新（当前生效：${agingBucketSourceLabel(saved.source)}）`);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '账龄区间配置失败');
    }
  }

  async function resetBuckets() {
    setActionError('');
    setNotice('');
    try {
      const saved = await updateReceivableAgingBuckets([]);
      setConfig(saved);
      setNotice('已回到蓝图给定的五档账龄区间');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '账龄区间配置失败');
    }
  }

  const hasFilters = Boolean(currency || shopId || counterpartyRef || bucketCode) || disputeOnly;
  const totalPages = overdue ? Math.max(1, Math.ceil(overdue.total / Math.max(1, overdue.size))) : 1;
  const gaps = caps?.downstreamGaps.filter((gap) => !gap.available) ?? [];

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="应收账龄"
        description="按账龄区间统计未核销应收分布，支持按店铺 / 客户 / 币种下钻，识别逾期集中风险"
        actions={
          <Button variant="secondary" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <div className="flex items-center gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm">
          <TriangleAlert className="h-4 w-4" />
          {error}
        </div>
      )}
      {actionError && (
        <Notice tone="danger" title="操作未完成">
          {actionError}
        </Notice>
      )}
      {notice && (
        <Notice tone="info" title="已完成">
          {notice}
        </Notice>
      )}

      {loading && <p className="text-muted-foreground text-sm">加载中…</p>}

      {!loading && !error && (
        <>
          {/* ---------- 下游缺口（未交付的模块说清楚归谁） ---------- */}
          {gaps.length > 0 && (
            <Notice tone="warning" title="未交付的下游">
              {gaps.map((gap) => `${gap.module} ${gap.name}（${gap.detail}）`).join('；')}
            </Notice>
          )}

          {/* ---------- 概览 ---------- */}
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard label="在册未核销应收" value={distribution?.totalCount ?? 0} unit="单" />
            <StatCard label="逾期未核销" value={overdue?.total ?? 0} unit="单" />
            <StatCard label="其中争议中" value={overdue?.disputeCount ?? 0} unit="单" />
            <StatCard
              label="账龄基准日"
              value={distribution?.asOfDate ?? '—'}
              comparisonLabel="按 UTC 日历日分桶"
            />
          </div>

          {/* ---------- 账龄区间配置 ---------- */}
          <SectionCard
            title="账龄区间配置"
            description="账龄区间是租户自己的口径 —— 档位必须连续覆盖 1 天起，留缝会让落在缝里的应收不属于任何一档。"
          >
            {!config ? (
              <p className="text-muted-foreground text-sm">没有账龄区间配置。</p>
            ) : (
              <div className="space-y-3">
                <p className="text-muted-foreground text-copy-13">
                  当前生效：<Badge tone="info">{agingBucketSourceLabel(config.source)}</Badge>
                  <span className="ml-2">{config.detail}</span>
                </p>
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">编码</th>
                      <th className="py-2">名称</th>
                      <th className="py-2">逾期天数（下界）</th>
                      <th className="py-2">逾期天数（上界）</th>
                      <th className="py-2">区间</th>
                    </tr>
                  </thead>
                  <tbody>
                    {drafts.map((draft, index) => {
                      const original = config.buckets.find((b) => b.code === draft.code);
                      const notDue = original?.overdueFromDays === null;
                      return (
                        <tr key={draft.code} className="border-border border-t">
                          <td className="py-2 font-medium">{draft.code}</td>
                          <td className="py-2">
                            <Input
                              value={draft.label}
                              aria-label={`${draft.code} 名称`}
                              onChange={(e) =>
                                setDrafts((list) =>
                                  list.map((item, i) =>
                                    i === index ? { ...item, label: e.target.value } : item,
                                  ),
                                )
                              }
                            />
                          </td>
                          <td className="py-2">
                            <Input
                              value={draft.from}
                              disabled={notDue}
                              aria-label={`${draft.code} 下界`}
                              placeholder={notDue ? '未到期档' : '1'}
                              onChange={(e) =>
                                setDrafts((list) =>
                                  list.map((item, i) =>
                                    i === index ? { ...item, from: e.target.value } : item,
                                  ),
                                )
                              }
                            />
                          </td>
                          <td className="py-2">
                            <Input
                              value={draft.to}
                              disabled={notDue}
                              aria-label={`${draft.code} 上界`}
                              placeholder={notDue ? '未到期档' : '留空 = 无上界'}
                              onChange={(e) =>
                                setDrafts((list) =>
                                  list.map((item, i) =>
                                    i === index ? { ...item, to: e.target.value } : item,
                                  ),
                                )
                              }
                            />
                          </td>
                          <td className="py-2">
                            {agingBucketRange(
                              config.buckets.find((b) => b.code === draft.code),
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
                <div className="flex flex-wrap gap-2">
                  <Button onClick={() => void saveBuckets()} disabled={loading}>
                    保存区间
                  </Button>
                  <Button variant="secondary" onClick={() => void resetBuckets()} disabled={loading}>
                    回到蓝图默认
                  </Button>
                </div>
              </div>
            )}
          </SectionCard>

          {/* ---------- 筛选 ---------- */}
          <SectionCard
            title="筛选"
            description="分布与逾期清单共用同一套条件 —— 换任何一项，两边一起变。"
          >
            <div className="grid gap-3 md:grid-cols-4">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={currency}
                onChange={(e) => {
                  setCurrency(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部币种</option>
                {(options?.currencies ?? []).map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={shopId}
                onChange={(e) => {
                  setShopId(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部店铺</option>
                {(options?.shops ?? []).map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={counterpartyRef}
                onChange={(e) => {
                  setCounterpartyRef(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部应收对象</option>
                {(options?.counterparties ?? []).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={bucketCode}
                onChange={(e) => {
                  setBucketCode(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部账龄区间</option>
                {(options?.buckets ?? []).map((b) => (
                  <option key={b.code} value={b.code}>
                    {b.label}
                  </option>
                ))}
              </select>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={disputeOnly}
                  onChange={(e) => {
                    setDisputeOnly(e.target.checked);
                    setCurrentPage(0);
                  }}
                />
                只看争议中
              </label>
              <div className="flex flex-wrap gap-2 md:col-span-3">
                {DIMENSIONS.map((d) => (
                  <Button
                    key={d}
                    size="sm"
                    variant={dimension === d ? 'default' : 'outline'}
                    onClick={() => setDimension(d)}
                  >
                    {agingDimensionLabel(d)}
                  </Button>
                ))}
              </div>
            </div>
          </SectionCard>

          {/* ---------- 账龄分布 ---------- */}
          <SectionCard
            title={`账龄分布（${agingDimensionLabel(dimension)}）`}
            description="⚠️ 不同币种不合并；争议中金额单列 —— 跨境应收最大的风险是平台争议冻结。"
          >
            {!distribution || distribution.rows.length === 0 ? (
              <EmptyState
                title={hasFilters ? '没有符合筛选条件的应收' : '还没有未核销的应收单'}
                description={
                  hasFilters
                    ? '把筛选条件放宽一点再试。'
                    : '账龄分布只统计未核销与部分核销的应收单（草稿与已作废的单不进报表）。'
                }
              />
            ) : (
              <div className="space-y-4">
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">{agingDimensionLabel(dimension)}</th>
                      <th className="py-2">币种</th>
                      <th className="py-2">未核销</th>
                      <th className="py-2">逾期未核销</th>
                      <th className="py-2">其中争议中</th>
                      <th className="py-2">争议且逾期</th>
                      <th className="py-2">逾期占比</th>
                      <th className="py-2">张数</th>
                    </tr>
                  </thead>
                  <tbody>
                    {distribution.rows.map((row) => (
                      <tr
                        key={`${row.dimensionKey}-${row.currency}`}
                        className="border-border border-t"
                      >
                        <td className="py-2 font-medium">{row.dimensionName}</td>
                        <td className="py-2">{row.currency}</td>
                        <td className="py-2">{formatAgingAmount(row.openAmount)}</td>
                        <td className="py-2">{formatAgingAmount(row.overdueAmount)}</td>
                        <td className="py-2">
                          {row.disputeAmount > 0 ? (
                            <Badge tone="warning">{formatAgingAmount(row.disputeAmount)}</Badge>
                          ) : (
                            <span className="text-muted-foreground">—</span>
                          )}
                        </td>
                        <td className="py-2">
                          {row.disputeOverdueAmount > 0
                            ? formatAgingAmount(row.disputeOverdueAmount)
                            : '—'}
                        </td>
                        <td className="py-2">{formatAgingShare(row.overdueShare)}</td>
                        <td className="py-2">{row.count}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>

                <div>
                  <p className="text-muted-foreground text-copy-13 mb-2">按币种的合计与集中度</p>
                  <table className="text-copy-13 w-full text-left">
                    <thead className="text-muted-foreground">
                      <tr>
                        <th className="py-2">币种</th>
                        <th className="py-2">未核销</th>
                        <th className="py-2">逾期未核销</th>
                        <th className="py-2">其中争议中</th>
                        <th className="py-2">逾期最集中的一维</th>
                        <th className="py-2">集中度</th>
                        <th className="py-2">张数</th>
                      </tr>
                    </thead>
                    <tbody>
                      {distribution.totals.map((total) => (
                        <tr key={total.currency} className="border-border border-t">
                          <td className="py-2 font-medium">{total.currency}</td>
                          <td className="py-2">{formatAgingAmount(total.openAmount)}</td>
                          <td className="py-2">{formatAgingAmount(total.overdueAmount)}</td>
                          <td className="py-2">{formatAgingAmount(total.disputeAmount)}</td>
                          <td className="py-2">{total.topDimensionName ?? '—'}</td>
                          <td className="py-2">{formatAgingShare(total.overdueShareTop)}</td>
                          <td className="py-2">{total.count}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                <div>
                  <p className="text-muted-foreground text-copy-13 mb-2">分档金额</p>
                  <table className="text-copy-13 w-full text-left">
                    <thead className="text-muted-foreground">
                      <tr>
                        <th className="py-2">{agingDimensionLabel(dimension)}</th>
                        <th className="py-2">币种</th>
                        <th className="py-2">账龄区间</th>
                        <th className="py-2">未核销金额</th>
                        <th className="py-2">张数</th>
                      </tr>
                    </thead>
                    <tbody>
                      {distribution.rows.flatMap((row) =>
                        row.buckets.map((bucket) => (
                          <tr
                            key={`${row.dimensionKey}-${row.currency}-${bucket.code}`}
                            className="border-border border-t"
                          >
                            <td className="py-2">{row.dimensionName}</td>
                            <td className="py-2">{row.currency}</td>
                            <td className="py-2">{bucket.label}</td>
                            <td className="py-2">{formatAgingAmount(bucket.amount)}</td>
                            <td className="py-2">{bucket.count}</td>
                          </tr>
                        )),
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </SectionCard>

          {/* ---------- 逾期清单 ---------- */}
          <SectionCard
            title="逾期清单"
            description="只列真正逾期的应收（到期日早于账龄基准日）；汇总覆盖整个筛选结果集，不是当前页。"
          >
            {!overdue || overdue.items.length === 0 ? (
              <EmptyState
                title={hasFilters ? '没有符合筛选条件的逾期应收' : '没有逾期的应收单'}
                description={
                  hasFilters
                    ? '把筛选条件放宽一点再试。'
                    : '所有未核销的应收都还在账期内。'
                }
              />
            ) : (
              <>
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">单号</th>
                      <th className="py-2">币种</th>
                      <th className="py-2">应收对象</th>
                      <th className="py-2">店铺</th>
                      <th className="py-2">到期日</th>
                      <th className="py-2">逾期</th>
                      <th className="py-2">账龄区间</th>
                      <th className="py-2">未核销</th>
                      <th className="py-2">争议</th>
                    </tr>
                  </thead>
                  <tbody>
                    {overdue.items.map((row) => (
                      <tr key={row.id} className="border-border border-t">
                        <td className="py-2 font-medium">
                          {row.receivableNo}
                          {row.disputeFlag && (
                            <Badge tone="warning" className="ml-2">
                              争议中
                            </Badge>
                          )}
                        </td>
                        <td className="py-2">{row.currency}</td>
                        <td className="py-2">{row.counterpartyName}</td>
                        <td className="py-2">{row.shopName || '—'}</td>
                        <td className="py-2">{row.dueDate}</td>
                        <td className="py-2">{formatOverdueDays(row.overdueDays)}</td>
                        <td className="py-2">{row.bucketLabel}</td>
                        <td className="py-2">{formatAgingMoney(row.openAmount, row.currency)}</td>
                        <td className="py-2">
                          {row.disputeFlag ? (
                            <span title={row.disputeNote}>{row.disputeNote || '争议中'}</span>
                          ) : (
                            <span className="text-muted-foreground">—</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>

                <div className="mt-3 flex items-center justify-between">
                  <span className="text-muted-foreground text-copy-13">
                    第 {overdue.page + 1} / {totalPages} 页 · 共 {overdue.total} 张
                  </span>
                  <div className="flex gap-2">
                    <Button
                      variant="secondary"
                      size="sm"
                      disabled={overdue.page <= 0}
                      onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
                    >
                      上一页
                    </Button>
                    <Button
                      variant="secondary"
                      size="sm"
                      disabled={overdue.page + 1 >= totalPages}
                      onClick={() => setCurrentPage((p) => p + 1)}
                    >
                      下一页
                    </Button>
                  </div>
                </div>

                {overdue.summaryByCurrency.length > 0 && (
                  <div className="mt-4">
                    <p className="text-muted-foreground text-copy-13 mb-2">
                      按币种的逾期汇总（覆盖整个筛选结果集）
                    </p>
                    <table className="text-copy-13 w-full text-left">
                      <thead className="text-muted-foreground">
                        <tr>
                          <th className="py-2">币种</th>
                          <th className="py-2">逾期未核销</th>
                          <th className="py-2">其中争议中</th>
                          <th className="py-2">争议单数</th>
                          <th className="py-2">张数</th>
                        </tr>
                      </thead>
                      <tbody>
                        {overdue.summaryByCurrency.map((s) => (
                          <tr key={s.currency} className="border-border border-t">
                            <td className="py-2 font-medium">{s.currency}</td>
                            <td className="py-2">{formatAgingAmount(s.overdueAmount)}</td>
                            <td className="py-2">
                              {s.disputeAmount > 0 ? (
                                <Badge tone="warning">{formatAgingAmount(s.disputeAmount)}</Badge>
                              ) : (
                                <span className="text-muted-foreground">—</span>
                              )}
                            </td>
                            <td className="py-2">{s.disputeCount}</td>
                            <td className="py-2">{s.count}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </>
            )}
          </SectionCard>
        </>
      )}
    </div>
  );
}
