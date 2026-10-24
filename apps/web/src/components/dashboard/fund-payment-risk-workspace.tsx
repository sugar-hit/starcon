'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button, EmptyState, StatCard } from '@starcon/ui';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import {
  DEFAULT_DUE_HORIZON_DAYS,
  DUE_HORIZON_OPTIONS,
  PAYABLE_URGENCY_LABELS,
  RISK_SOURCE_KEYS,
  RISK_SOURCE_SPECS,
  buildAgingCurrencies,
  buildCurrencySummaries,
  buildInTransitGoods,
  buildInTransitMoney,
  buildOverdueView,
  buildPendingSettlement,
  buildUpcomingPayables,
  describeAging,
  describeInTransitGoods,
  describeInTransitMoney,
  describeOverdue,
  describePayable,
  formatMoney,
  formatRatio,
  inTransitBasisNote,
  sourceAvailabilities,
  type Access,
  type AgingCurrency,
  type FinanceDeferredSettlementSummary,
  type FinancePayableUnpaidList,
  type FinanceReceivableAgingDistribution,
  type FinanceReceivableAgingOverduePage,
  type FinanceSettlementPage,
  type InTransitBasisNote,
  type InTransitGoods,
  type InTransitMoneyCurrency,
  type OverdueView,
  type RiskProjectionPage,
  type RiskProjectionRow,
  type RiskSourceKey,
  type SourceAvailability,
  type UpcomingPayableView,
} from './fund-payment-risk-model';

/**
 * 1.1.3.2 资金与回款风险工作区（卷 01 工作台与门户）。
 *
 * <p>蓝图输出：「账龄分布图 + 逾期清单 + 待结算金额；点击进入 12 对应收款。」
 *
 * <p><b>数据来自六处，跨服务聚合在本页完成</b>：
 *
 * <ul>
 *   <li>{@code GET finance/receivable-aging/distribution} —— 应收账龄分布（12.1.1.2）</li>
 *   <li>{@code GET finance/receivable-aging/overdue} —— 逾期清单（12.1.1.2）</li>
 *   <li>{@code GET finance/settlement?status=PENDING} + {@code ?status=PARTIAL} —— 平台待结算（12.3.1.1）</li>
 *   <li>{@code GET finance/payables/unpaid} —— 供应商应付未付清单（12.1.2.1）</li>
 *   <li>{@code GET finance/deferred-settlement/summary} —— 在途未结算资金（12.3.1.3）</li>
 *   <li>{@code GET warehouse/inventory-risk/projection} —— 在途库存数量（07 库存）</li>
 * </ul>
 *
 * <p><b>三种「读不到」必须分开</b>：<b>无权限</b>（你的角色没有这个来源的读权限，
 * 连请求都不发）、<b>读不到</b>（服务不可达 / 报错）、<b>没有数据</b>（读到了但这一档是空的）。
 * 前两种让对应的数字显示「不可判定」，第三种显示「—（该币种没有）」——
 * 三者都<b>不是 0</b>：0 会被读成「没有逾期应收」。
 *
 * <p><b>只读 + 跳转</b>：页面上<b>没有</b>确认 / 催收 / 调整 / 核销任何写动作，
 * 每个数字的出口是<b>跳到归属系统</b>（12 财务的应收账龄 / 应付单 / 结算单 / 延迟结算页）。
 *
 * <p><b>多币种绝不相加</b>：概览按币种分行，没有「总计」这一列 ——
 * USD 与 EUR 加起来不是任何一个金额。
 */
export function FundPaymentRiskWorkspace() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [ready, setReady] = useState(false);

  const [availability, setAvailability] = useState<Record<RiskSourceKey, SourceAvailability> | null>(
    null,
  );
  /** 逐来源的读不到原因（无权限也归到这里，措辞不同）。 */
  const [sourceErrors, setSourceErrors] = useState<Record<string, string>>({});

  const [aging, setAging] = useState<AgingCurrency[]>([]);
  const [agingMeta, setAgingMeta] = useState<FinanceReceivableAgingDistribution | null>(null);
  const [overdue, setOverdue] = useState<OverdueView>(buildOverdueView(null));
  const [pendingSettlement, setPendingSettlement] = useState<
    { currency: string; settlementAmount: number; receivedAmount: number; unsettledAmount: number; pendingCount: number; partialCount: number; count: number }[]
  >([]);
  const [payables, setPayables] = useState<UpcomingPayableView>(buildUpcomingPayables(null));
  const [inTransitMoney, setInTransitMoney] = useState<InTransitMoneyCurrency[]>([]);
  const [basis, setBasis] = useState<InTransitBasisNote | null>(null);
  const [inTransitGoods, setInTransitGoods] = useState<InTransitGoods>(buildInTransitGoods(null));

  const [horizonDays, setHorizonDays] = useState<number>(DEFAULT_DUE_HORIZON_DAYS);
  const [counterpartyType, setCounterpartyType] = useState('SUPPLIER');
  const [selectedOverdueKey, setSelectedOverdueKey] = useState('');
  const [selectedPayableKey, setSelectedPayableKey] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    setSourceErrors({});
    try {
      // 1. 先向身份服务确认身份与权限：租户**不**来自客户端头。
      const access = await api<Access>('auth/session');
      const avail = sourceAvailabilities(access);
      setAvailability(avail);

      const errors: Record<string, string> = {};
      const permitted = (key: RiskSourceKey) => avail[key].permitted;
      const denied = (key: RiskSourceKey) => {
        errors[key] = '无权限 · 对你不开放';
      };
      const failed = (key: RiskSourceKey, failure: unknown) => {
        errors[key] =
          failure instanceof ApiError ? failure.message : '服务暂时读不到';
      };

      // 2. 账龄分布
      if (!permitted('AGING')) {
        denied('AGING');
        setAging([]);
        setAgingMeta(null);
      } else {
        try {
          const page = await api<FinanceReceivableAgingDistribution>(
            'finance/receivable-aging/distribution',
          );
          setAgingMeta(page);
          setAging(buildAgingCurrencies(page));
        } catch (failure) {
          failed('AGING', failure);
          setAging([]);
          setAgingMeta(null);
        }
      }

      // 3. 逾期清单
      if (!permitted('OVERDUE')) {
        denied('OVERDUE');
        setOverdue(buildOverdueView(null));
      } else {
        try {
          const page = await api<FinanceReceivableAgingOverduePage>(
            'finance/receivable-aging/overdue?size=200',
          );
          setOverdue(buildOverdueView(page));
        } catch (failure) {
          failed('OVERDUE', failure);
          setOverdue(buildOverdueView(null));
        }
      }

      // 4. 平台待结算：待到账 + 部分到账两档（status 过滤一次只吃一个取值）。
      if (!permitted('SETTLEMENT')) {
        denied('SETTLEMENT');
        setPendingSettlement([]);
      } else {
        try {
          const [pending, partial] = await Promise.all([
            api<FinanceSettlementPage>('finance/settlement?status=PENDING&size=200'),
            api<FinanceSettlementPage>('finance/settlement?status=PARTIAL&size=200'),
          ]);
          setPendingSettlement(buildPendingSettlement(pending, partial));
        } catch (failure) {
          failed('SETTLEMENT', failure);
          setPendingSettlement([]);
        }
      }

      // 5. 供应商应付未付清单（服务端一次给全量，不分页）
      if (!permitted('PAYABLE')) {
        denied('PAYABLE');
        setPayables(buildUpcomingPayables(null));
      } else {
        try {
          const list = await api<FinancePayableUnpaidList>('finance/payables/unpaid');
          setPayables(buildUpcomingPayables(list, horizonDays, counterpartyType));
        } catch (failure) {
          failed('PAYABLE', failure);
          setPayables(buildUpcomingPayables(null));
        }
      }

      // 6. 在途未结算资金（钱）
      if (!permitted('DEFERRED')) {
        denied('DEFERRED');
        setInTransitMoney([]);
        setBasis(null);
      } else {
        try {
          const summary = await api<FinanceDeferredSettlementSummary>(
            'finance/deferred-settlement/summary',
          );
          setInTransitMoney(buildInTransitMoney(summary));
          setBasis(inTransitBasisNote(summary));
        } catch (failure) {
          failed('DEFERRED', failure);
          setInTransitMoney([]);
          setBasis(null);
        }
      }

      // 7. 在途库存（数量）：投影端点分页，逐页取完；取不完就如实标截断。
      if (!permitted('INVENTORY')) {
        denied('INVENTORY');
        setInTransitGoods(buildInTransitGoods(null));
      } else {
        try {
          const rows: RiskProjectionRow[] = [];
          const pageSize = 500;
          let total = 0;
          let page = 0;
          for (;;) {
            const projection = await api<RiskProjectionPage>(
              `warehouse/inventory-risk/projection?size=${pageSize}&page=${page}`,
            );
            rows.push(...(projection.rows ?? []));
            total = projection.total ?? 0;
            if (rows.length >= total || (projection.rows ?? []).length === 0) break;
            page += 1;
            if (page >= 20) break;
          }
          setInTransitGoods(
            buildInTransitGoods({
              rows,
              total,
              page: 0,
              size: pageSize,
              asOfDate: '',
              computedAt: '',
              warehouseKindOptions: [],
              warehouseOptions: [],
              truncationNote: '',
            }),
          );
        } catch (failure) {
          failed('INVENTORY', failure);
          setInTransitGoods(buildInTransitGoods(null));
        }
      }

      setSourceErrors(errors);
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : '资金与回款风险暂时读不到');
    } finally {
      setLoading(false);
      setReady(true);
    }
  }, [counterpartyType, horizonDays]);

  useEffect(() => {
    void load();
  }, [load]);

  const sourceAvailable = useMemo(() => {
    const out = {} as Record<RiskSourceKey, boolean>;
    for (const key of RISK_SOURCE_KEYS) {
      out[key] = !sourceErrors[key];
    }
    return out;
  }, [sourceErrors]);

  const summaries = useMemo(
    () =>
      buildCurrencySummaries(
        aging,
        overdue,
        pendingSettlement,
        payables,
        inTransitMoney,
        sourceAvailable,
      ),
    [aging, overdue, pendingSettlement, payables, inTransitMoney, sourceAvailable],
  );

  const selectedOverdue = useMemo(
    () => overdue.rows.find((row) => row.key === selectedOverdueKey) ?? null,
    [overdue.rows, selectedOverdueKey],
  );
  const selectedPayable = useMemo(
    () => payables.rows.find((row) => row.key === selectedPayableKey) ?? null,
    [payables.rows, selectedPayableKey],
  );

  if (!ready) {
    return <p className="text-copy-13 text-muted-foreground">正在加载资金与回款风险…</p>;
  }

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <h1 className="text-copy-16 font-medium">资金与回款风险</h1>
          <p className="text-copy-12 text-muted-foreground">
            应收账龄、平台待结算金额、逾期应收、即将到期的供应商应付、在途资金占用。
            本页只读：确认 / 催收 / 调整 / 核销都回 12 财务去做。
          </p>
          {agingMeta && (
            <p className="text-copy-12 text-muted-foreground">
              账龄基准日 {agingMeta.asOfDate} · 账龄区间来源{' '}
              {agingMeta.bucketSource === 'TENANT_CONFIG' ? '租户自定义' : '蓝图默认'} · 命中应收{' '}
              {agingMeta.totalCount} 笔
            </p>
          )}
        </div>
        <Button variant="outline" onClick={() => void load()} disabled={loading}>
          <RefreshCw className="mr-1 h-4 w-4" />
          刷新
        </Button>
      </header>

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

      {/* ---------------- 数据来源可达性 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-15 font-medium">数据来源可达性</h2>
        <ul className="space-y-1">
          {RISK_SOURCE_KEYS.map((key) => {
            const spec = RISK_SOURCE_SPECS[key];
            const reason = sourceErrors[key];
            const permitted = availability?.[key]?.permitted ?? false;
            const scope = availability?.[key]?.scopeMode;
            return (
              <li
                key={key}
                className={`text-copy-12 rounded-md border p-2 ${
                  reason ? 'border-warning/40 bg-warning/5' : 'text-muted-foreground'
                }`}
              >
                <Badge tone={reason ? 'warning' : 'info'}>
                  {reason ? (permitted ? '读不到' : '无权限') : '已接通'}
                </Badge>
                <span className="ml-2">
                  {spec.label} · {spec.owner}（{spec.ownerModule}） ·{' '}
                  <span className="font-mono">{spec.endpoint}</span>
                  {scope ? ` · 数据范围：${scope === 'ALL' ? '租户内全部' : scope === 'DEPARTMENT' ? '本部门' : '仅本人'}` : ''}
                </span>
                {reason ? (
                  <span className="ml-1">
                    —— {reason}。{spec.impact}
                  </span>
                ) : null}
              </li>
            );
          })}
        </ul>
        <p className="text-copy-12 text-muted-foreground">
          租户来自已认证的成员关系，法人 / 店铺 / 仓库的数据范围由归属服务在服务端判定；
          本页不把范围当成可信输入往下传。多币种一律分组呈现，<b>没有跨币种总额</b> ——
          汇率不可得时也不按 1:1 兜底。
        </p>
      </section>

      {/* ---------------- 概览（按币种，无总计） ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-15 font-medium">概览 · 按币种</h2>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="币种数" value={summaries.length} unit="个" />
          <StatCard label="逾期应收张数" value={overdue.total} unit="笔" />
          <StatCard
            label="已逾期供应商应付"
            value={payables.overdueCount}
            unit="笔"
          />
          <StatCard
            label="即将到期供应商应付"
            value={payables.dueSoonCount}
            unit={`笔（${horizonDays} 天窗口）`}
          />
        </div>
        {summaries.length === 0 ? (
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title="没有可汇总的资金数据"
            description="六个来源要么无权限、要么读不到、要么这个租户确实没有资金流水。"
          />
        ) : (
          <div className="bg-card overflow-x-auto rounded-lg border">
            <table className="w-full text-left text-copy-12">
              <thead className="text-muted-foreground border-b">
                <tr>
                  <th className="px-3 py-2 font-medium">币种</th>
                  <th className="px-3 py-2 text-right font-medium">应收未核销</th>
                  <th className="px-3 py-2 text-right font-medium">逾期应收</th>
                  <th className="px-3 py-2 text-right font-medium">平台待结算</th>
                  <th className="px-3 py-2 text-right font-medium">应付未付（到期+逾期）</th>
                  <th className="px-3 py-2 text-right font-medium">在途未结算资金</th>
                </tr>
              </thead>
              <tbody>
                {summaries.map((item) => (
                  <tr key={item.currency} className="border-b last:border-b-0">
                    <td className="px-3 py-2 font-mono">{item.currency}</td>
                    <td className="px-3 py-2 text-right font-mono">
                      {formatMoney(item.receivableOpenAmount)}
                    </td>
                    <td className="px-3 py-2 text-right font-mono">
                      {formatMoney(item.receivableOverdueAmount)}
                    </td>
                    <td className="px-3 py-2 text-right font-mono">
                      {formatMoney(item.pendingSettlementAmount)}
                    </td>
                    <td className="px-3 py-2 text-right font-mono">
                      {formatMoney(item.payableUnpaidAmount)}
                    </td>
                    <td className="px-3 py-2 text-right font-mono">
                      {formatMoney(item.inTransitMoneyAmount)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="text-copy-12 text-muted-foreground">
          每一格都来自它自己那份清单（账龄分布 / 逾期清单 / 待结算 / 未付清单 / 在途资金统计），
          所以概览与清单同源。显示「—」表示那个来源读不到或不可判定，**不是 0**。
        </p>
      </section>

      {/* ---------------- 账龄分布图 ---------------- */}
      <section className="space-y-3">
        <h2 className="text-copy-15 font-medium">应收账龄分布</h2>
        {aging.length === 0 ? (
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title="账龄分布读不到"
            description="来源不可达或这个租户还没有应收单 —— 去 12 财务的应收账龄页看。"
          />
        ) : (
          aging.map((currency) => {
            const max = currency.buckets.reduce((acc, bucket) => Math.max(acc, bucket.amount), 0);
            return (
              <div key={currency.currency} className="bg-card rounded-lg border p-3">
                <div className="flex flex-wrap items-baseline gap-2">
                  <span className="font-mono text-copy-13">{currency.currency}</span>
                  <span className="text-copy-12 text-muted-foreground">
                    未核销 {formatMoney(currency.openAmount)} · {currency.count} 笔 ·
                    逾期 {formatMoney(currency.overdueAmount)}
                  </span>
                  {currency.disputeAmount > 0 && (
                    <Badge tone="warning">争议中 {formatMoney(currency.disputeAmount)}</Badge>
                  )}
                </div>
                <div className="mt-2 space-y-1">
                  {currency.buckets.map((bucket) => (
                    <div key={bucket.bucketCode} className="text-copy-12">
                      <div className="flex items-baseline justify-between gap-2">
                        <span>{bucket.bucketLabel}</span>
                        <span className="font-mono">
                          {formatMoney(bucket.amount)} · {bucket.count} 笔 ·{' '}
                          {formatRatio(bucket.share)}
                        </span>
                      </div>
                      <div className="bg-muted mt-0.5 h-1.5 w-full overflow-hidden rounded">
                        {bucket.share !== null && max > 0 ? (
                          <div
                            className="bg-brand h-full"
                            style={{ width: `${(bucket.amount / max) * 100}%` }}
                          />
                        ) : null}
                      </div>
                    </div>
                  ))}
                </div>
                <details className="text-copy-12 text-muted-foreground mt-2">
                  <summary className="cursor-pointer">口径明细</summary>
                  <ul className="mt-1 space-y-1">
                    {describeAging(currency).map((line) => (
                      <li key={line}>{line}</li>
                    ))}
                  </ul>
                </details>
              </div>
            );
          })
        )}
      </section>

      {/* ---------------- 逾期清单 ---------------- */}
      <section className="space-y-2">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-copy-15 font-medium">逾期应收清单</h2>
          <Link href="/finance/aging" className="text-copy-12 text-brand underline">
            去 12 财务应收账龄处理
          </Link>
        </div>
        {overdue.rows.length === 0 ? (
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title="没有逾期应收"
            description={
              sourceErrors.OVERDUE
                ? `逾期清单读不到：${sourceErrors.OVERDUE}。`
                : '这个租户当前没有已逾期的应收单。'
            }
          />
        ) : (
          <div className="bg-card overflow-x-auto rounded-lg border">
            <table className="w-full text-left text-copy-12">
              <thead className="text-muted-foreground border-b">
                <tr>
                  <th className="px-3 py-2 font-medium">单号</th>
                  <th className="px-3 py-2 font-medium">币种</th>
                  <th className="px-3 py-2 font-medium">往来对象</th>
                  <th className="px-3 py-2 font-medium">账龄档</th>
                  <th className="px-3 py-2 text-right font-medium">逾期天数</th>
                  <th className="px-3 py-2 text-right font-medium">未核销</th>
                  <th className="px-3 py-2 font-medium">争议</th>
                </tr>
              </thead>
              <tbody>
                {overdue.rows.map((row) => (
                  <tr
                    key={row.key}
                    onClick={() =>
                      setSelectedOverdueKey(row.key === selectedOverdueKey ? '' : row.key)
                    }
                    className={`cursor-pointer border-b last:border-b-0 ${
                      row.key === selectedOverdueKey ? 'bg-muted/50' : ''
                    }`}
                  >
                    <td className="px-3 py-2 font-mono">{row.receivableNo}</td>
                    <td className="px-3 py-2 font-mono">{row.currency}</td>
                    <td className="px-3 py-2">
                      {row.counterpartyName}
                      <span className="text-muted-foreground ml-1">{row.shopName}</span>
                    </td>
                    <td className="px-3 py-2">{row.bucketLabel}</td>
                    <td className="px-3 py-2 text-right font-mono">{row.overdueDays}</td>
                    <td className="px-3 py-2 text-right font-mono">{formatMoney(row.openAmount)}</td>
                    <td className="px-3 py-2">
                      {row.disputeFlag ? <Badge tone="danger">争议中</Badge> : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {overdue.truncated && (
          <p className="text-copy-12 text-warning">
            逾期清单共 {overdue.total} 笔，本页取回 {overdue.fetchedCount} 笔 —— 已如实截断，
            下面的张数按服务端给的总数算，明细只列出取回的部分。
          </p>
        )}
        {overdue.disputeCount > 0 && (
          <p className="text-copy-12 text-muted-foreground">
            争议中 {overdue.disputeCount} 笔 —— 跨境应收最大的风险是平台争议冻结，
            争议金额单列，不混在普通逾期里。
          </p>
        )}
        {selectedOverdue && (
          <div className="bg-card rounded-lg border p-3">
            <h3 className="text-copy-13 font-medium">
              明细 · {selectedOverdue.receivableNo}
            </h3>
            <ul className="text-copy-12 mt-1 space-y-1">
              {describeOverdue(selectedOverdue).map((line) => (
                <li key={line}>{line}</li>
              ))}
            </ul>
            <Link
              href={selectedOverdue.href}
              className="text-copy-12 text-brand mt-2 inline-block underline"
            >
              去 12 财务处理这笔应收
            </Link>
          </div>
        )}
      </section>

      {/* ---------------- 平台待结算 ---------------- */}
      <section className="space-y-2">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-copy-15 font-medium">平台待结算金额</h2>
          <Link href="/finance/settlement" className="text-copy-12 text-brand underline">
            去 12 财务结算单处理
          </Link>
        </div>
        {pendingSettlement.length === 0 ? (
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title="没有待结算金额"
            description={
              sourceErrors.SETTLEMENT
                ? `结算单读不到：${sourceErrors.SETTLEMENT}。`
                : '没有待到账或部分到账的结算单。'
            }
          />
        ) : (
          <div className="bg-card overflow-x-auto rounded-lg border">
            <table className="w-full text-left text-copy-12">
              <thead className="text-muted-foreground border-b">
                <tr>
                  <th className="px-3 py-2 font-medium">币种</th>
                  <th className="px-3 py-2 text-right font-medium">结算金额</th>
                  <th className="px-3 py-2 text-right font-medium">已到账</th>
                  <th className="px-3 py-2 text-right font-medium">未到账</th>
                  <th className="px-3 py-2 text-right font-medium">待到账</th>
                  <th className="px-3 py-2 text-right font-medium">部分到账</th>
                </tr>
              </thead>
              <tbody>
                {pendingSettlement.map((item) => (
                  <tr key={item.currency} className="border-b last:border-b-0">
                    <td className="px-3 py-2 font-mono">{item.currency}</td>
                    <td className="px-3 py-2 text-right font-mono">
                      {formatMoney(item.settlementAmount)}
                    </td>
                    <td className="px-3 py-2 text-right font-mono">
                      {formatMoney(item.receivedAmount)}
                    </td>
                    <td className="px-3 py-2 text-right font-mono">
                      {formatMoney(item.unsettledAmount)}
                    </td>
                    <td className="px-3 py-2 text-right font-mono">{item.pendingCount}</td>
                    <td className="px-3 py-2 text-right font-mono">{item.partialCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="text-copy-12 text-muted-foreground">
          未到账 = 结算金额 − 已到账金额。待结算口径 = 待到账 + 部分到账两档，
          按币种合并（同一个币种内相加合法，跨币种相加不合法）。
        </p>
      </section>

      {/* ---------------- 即将到期的供应商应付 ---------------- */}
      <section className="space-y-2">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-copy-15 font-medium">即将到期的供应商应付</h2>
          <Link href="/finance/payables" className="text-copy-12 text-brand underline">
            去 12 财务应付单处理
          </Link>
        </div>
        <div className="flex flex-wrap items-end gap-3">
          <label className="flex flex-col gap-1 text-copy-12">
            <span className="text-muted-foreground">到期窗口</span>
            <select
              className="border rounded px-2 py-1 text-copy-12"
              value={horizonDays}
              onChange={(e) => setHorizonDays(Number(e.target.value))}
            >
              {DUE_HORIZON_OPTIONS.map((days) => (
                <option key={days} value={days}>
                  {days} 天内到期
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-copy-12">
            <span className="text-muted-foreground">应付对象</span>
            <select
              className="border rounded px-2 py-1 text-copy-12"
              value={counterpartyType}
              onChange={(e) => setCounterpartyType(e.target.value)}
            >
              <option value="SUPPLIER">供应商</option>
              <option value="CARRIER">承运商</option>
              <option value="PLATFORM">平台</option>
              <option value="OTHER">其他</option>
              <option value="">全部</option>
            </select>
          </label>
        </div>
        {payables.rows.length === 0 ? (
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title="没有到期或逾期的应付"
            description={
              sourceErrors.PAYABLE
                ? `未付清单读不到：${sourceErrors.PAYABLE}。`
                : `这个租户在 ${horizonDays} 天窗口内没有到期的应付单，也没有已逾期的应付单。`
            }
          />
        ) : (
          <div className="bg-card overflow-x-auto rounded-lg border">
            <table className="w-full text-left text-copy-12">
              <thead className="text-muted-foreground border-b">
                <tr>
                  <th className="px-3 py-2 font-medium">单号</th>
                  <th className="px-3 py-2 font-medium">币种</th>
                  <th className="px-3 py-2 font-medium">应付对象</th>
                  <th className="px-3 py-2 font-medium">到期日</th>
                  <th className="px-3 py-2 font-medium">状态</th>
                  <th className="px-3 py-2 text-right font-medium">未付余额</th>
                </tr>
              </thead>
              <tbody>
                {payables.rows.map((row) => (
                  <tr
                    key={row.key}
                    onClick={() =>
                      setSelectedPayableKey(row.key === selectedPayableKey ? '' : row.key)
                    }
                    className={`cursor-pointer border-b last:border-b-0 ${
                      row.key === selectedPayableKey ? 'bg-muted/50' : ''
                    }`}
                  >
                    <td className="px-3 py-2 font-mono">{row.payableNo}</td>
                    <td className="px-3 py-2 font-mono">{row.currency}</td>
                    <td className="px-3 py-2">{row.counterpartyName}</td>
                    <td className="px-3 py-2 font-mono">{row.dueDate}</td>
                    <td className="px-3 py-2">
                      <Badge tone={row.urgency === 'OVERDUE' ? 'danger' : 'warning'}>
                        {PAYABLE_URGENCY_LABELS[row.urgency]}
                      </Badge>
                    </td>
                    <td className="px-3 py-2 text-right font-mono">
                      {formatMoney(row.unpaidAmount)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="text-copy-12 text-muted-foreground">
          已逾期 {payables.overdueCount} 笔（无论窗口多长都计入），窗口内即将到期{' '}
          {payables.dueSoonCount} 笔。服务端未付清单共 {payables.totalOpenCount} 笔，
          上面的张数与未付余额由<b>本页这一份集合</b>算出。
        </p>
        {selectedPayable && (
          <div className="bg-card rounded-lg border p-3">
            <h3 className="text-copy-13 font-medium">明细 · {selectedPayable.payableNo}</h3>
            <ul className="text-copy-12 mt-1 space-y-1">
              {describePayable(selectedPayable, horizonDays).map((line) => (
                <li key={line}>{line}</li>
              ))}
            </ul>
            <Link
              href={selectedPayable.href}
              className="text-copy-12 text-brand mt-2 inline-block underline"
            >
              去 12 财务处理这笔应付
            </Link>
          </div>
        )}
      </section>

      {/* ---------------- 在途资金占用 ---------------- */}
      <section className="space-y-2">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-copy-15 font-medium">在途资金占用</h2>
          <Link href="/finance/deferred-settlement" className="text-copy-12 text-brand underline">
            去 12 财务延迟结算处理
          </Link>
        </div>
        {inTransitMoney.length === 0 ? (
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title="在途资金统计读不到"
            description={
              sourceErrors.DEFERRED
                ? `延迟结算统计读不到：${sourceErrors.DEFERRED}。`
                : '这个租户没有延迟结算台账记录。'
            }
          />
        ) : (
          <div className="bg-card overflow-x-auto rounded-lg border">
            <table className="w-full text-left text-copy-12">
              <thead className="text-muted-foreground border-b">
                <tr>
                  <th className="px-3 py-2 font-medium">币种</th>
                  <th className="px-3 py-2 text-right font-medium">在途未结算</th>
                  <th className="px-3 py-2 text-right font-medium">订单口径</th>
                  <th className="px-3 py-2 text-right font-medium">已结算口径</th>
                  <th className="px-3 py-2 text-right font-medium">预计到账已过</th>
                </tr>
              </thead>
              <tbody>
                {inTransitMoney.map((item) => (
                  <tr key={item.currency} className="border-b last:border-b-0">
                    <td className="px-3 py-2 font-mono">{item.currency}</td>
                    <td className="px-3 py-2 text-right font-mono">
                      {formatMoney(item.inTransitAmount)}
                    </td>
                    <td className="px-3 py-2 text-right font-mono">
                      {formatMoney(item.orderBasisAmount)}
                    </td>
                    <td className="px-3 py-2 text-right font-mono">
                      {formatMoney(item.settledBasisAmount)}
                    </td>
                    <td className="px-3 py-2 text-right font-mono">{item.overdueCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {basis && (
          <p className="text-copy-12 text-muted-foreground">
            口径定义：订单口径 = {basis.orderBasis}；已结算口径 = {basis.settledBasis}；
            在途未结算 = {basis.inTransitBasis}；{basis.basisDifference}。币种政策：{basis.currencyPolicy}。
          </p>
        )}
        <div className="bg-card rounded-lg border p-3">
          <h3 className="text-copy-13 font-medium">在途库存（07 库存）</h3>
          <ul className="text-copy-12 mt-1 space-y-1">
            {describeInTransitGoods(inTransitGoods).map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        </div>
      </section>
    </div>
  );
}
