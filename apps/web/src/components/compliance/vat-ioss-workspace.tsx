'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
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
import { CircleAlert, Loader2, RefreshCw, Receipt, Scale, ScrollText } from 'lucide-react';
import {
  FILING_CYCLES,
  FILING_SCOPE_FILTERS,
  FILING_STATUS_FILTERS,
  createFilingCycle,
  createFilingPeriod,
  confirmFilingPeriod,
  createRegistration,
  draftFilingPeriod,
  emptyStateKind,
  emptyStateText,
  fetchCapabilities,
  fetchFilingCycles,
  fetchFilingPeriods,
  fetchOptions,
  fetchOrders,
  fetchRegistrations,
  fetchStatistics,
  filingStatusLabel,
  formatAmount,
  groupTotalsByCurrency,
  nextFilingActions,
  reasonLabel,
  scopeDescription,
  scopeLabel,
  splitByScope,
  syncOrders,
  taxRateDisplay,
  type VatIossCapabilities,
  type VatIossFilingCycleView,
  type VatIossFilingPeriodView,
  type VatIossOptions,
  type VatIossOrderPage,
  type VatIossRegistrationView,
  type VatIossStatistics,
} from './vat-ioss-model';

/**
 * 16.3.2.1 VAT/IOSS 工作区。
 *
 * 蓝图：「管理 VAT 与 IOSS 制度：适用店铺与商品、申报周期、税率适用、IOSS 订单识别与代收代缴统计、
 * 与财务申报数据的对接。输入：IOSS 配置；订单税务数据；12.6.1.2 申报底稿。
 * 输出：IOSS 订单清单与金额统计；申报状态。关系：上游 = 12.6.1.1 税率、9 订单；下游 = 12.6.1.2。
 * **IOSS 订单与一般 VAT 订单必须区分统计**，混在一起申报是最常见的错误。」
 *
 * 🔴 页面上的七条纪律：
 *
 * 1. **分档是数据**：四档各自一栏，界面上**不出现**「IOSS + 一般 VAT 合计」这种总数 ——
 *    那个数一出现，就有人会拿它去申报。
 * 2. **「判不了」是独立一档**：单独一栏 + 单独一张卡，**不并进** IOSS 也不并进不在范围。
 * 3. **税率读不到就显示「读不到」**：绝不显示 0%（0% 看起来像免税）。
 * 4. **金额按币种分组**：表格里币种是独立列，**不跨币种合计**。
 * 5. **申报状态只能前进**：按钮按当前状态给（DRAFT 只给「出底稿」，DRAFTED 只给「确认」）。
 * 6. **没有「向税务机构提交」按钮**：本系统只保证数据完整可核。
 * 7. **读不到申报底稿就说读不到**：12.6.1.2 未交付，页面明写，**不编造**。
 */
export function VatIossWorkspace() {
  const [capabilities, setCapabilities] = useState<VatIossCapabilities | null>(null);
  const [options, setOptions] = useState<VatIossOptions | null>(null);
  const [registrations, setRegistrations] = useState<VatIossRegistrationView[]>([]);
  const [cycles, setCycles] = useState<VatIossFilingCycleView[]>([]);
  const [periods, setPeriods] = useState<VatIossFilingPeriodView[]>([]);
  const [statistics, setStatistics] = useState<VatIossStatistics | null>(null);
  const [orders, setOrders] = useState<VatIossOrderPage | null>(null);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [scopeFilter, setScopeFilter] = useState('');
  const [periodStatusFilter, setPeriodStatusFilter] = useState('');

  const [registrationNo, setRegistrationNo] = useState('');
  const [thresholdAmount, setThresholdAmount] = useState('150.000000');
  const [thresholdCurrency, setThresholdCurrency] = useState('EUR');
  const [shopScope, setShopScope] = useState('');
  const [goodsScope, setGoodsScope] = useState('');

  const [cycleJurisdiction, setCycleJurisdiction] = useState('EU-DE');
  const [cycleKind, setCycleKind] = useState('MONTHLY');
  const [cycleStartMonth, setCycleStartMonth] = useState('1');
  const [cycleDeadlineDay, setCycleDeadlineDay] = useState('20');

  const [periodJurisdiction, setPeriodJurisdiction] = useState('EU-DE');
  const [periodStart, setPeriodStart] = useState('');
  const [periodEnd, setPeriodEnd] = useState('');

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, opts, regs, cyclePage, periodPage, stats, orderPage] = await Promise.all([
        fetchCapabilities(),
        fetchOptions(),
        fetchRegistrations(0, 50),
        fetchFilingCycles({ size: 50 }),
        fetchFilingPeriods({ status: periodStatusFilter, size: 50 }),
        fetchStatistics(),
        fetchOrders({ filingScope: scopeFilter, size: 50 }),
      ]);
      setCapabilities(caps);
      setOptions(opts);
      setRegistrations(regs.items);
      setCycles(cyclePage.items);
      setPeriods(periodPage.items);
      setStatistics(stats);
      setOrders(orderPage);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [periodStatusFilter, scopeFilter]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const run = useCallback(async (work: () => Promise<string>) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      setNotice(await work());
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败');
    } finally {
      setBusy(false);
    }
  }, [refresh]);

  const scopes = useMemo(
    () => splitByScope(statistics?.rows ?? []),
    [statistics?.rows],
  );
  const currencyTotals = useMemo(
    () => groupTotalsByCurrency(statistics?.rows ?? []),
    [statistics?.rows],
  );
  const orderEmptyKind = useMemo(
    () =>
      emptyStateKind({
        everSynced: (statistics?.totalOrders ?? 0) > 0,
        filtered: Boolean(scopeFilter),
        hasScopeFilter: Boolean(scopeFilter),
      }),
    [scopeFilter, statistics?.totalOrders],
  );

  return (
    <div className="space-y-5">
      <PageHeader
        title="VAT / IOSS"
        description="IOSS 订单识别、代收代缴统计与申报状态。IOSS 订单与一般 VAT 订单分开统计。"
        actions={
          <div className="flex gap-2">
            <Button
              variant="outline"
              onClick={() => void refresh()}
              disabled={loading || busy}
            >
              {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
              刷新
            </Button>
            <Button
              onClick={() =>
                void run(async () => {
                  const result = await syncOrders();
                  return (
                    `已识别 ${result.scanned} 单：IOSS ${result.iossCount} / 一般 VAT ${result.generalVatCount}` +
                    ` / 判不了 ${result.unresolvedCount} / 不在范围 ${result.outOfScopeCount}` +
                    (result.truncated ? '（⚠️ 本次扫描没扫完，统计不是全量）' : '')
                  );
                })
              }
              disabled={loading || busy}
            >
              <Scale className="h-4 w-4" />
              识别 IOSS 订单
            </Button>
          </div>
        }
      />

      {error ? (
        <Notice tone="danger" title="操作失败">
          {error}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      ) : null}

      {capabilities ? (
        <Notice tone="info" title="能力声明">
          <div className="space-y-1">
            <p>{capabilities.classificationSemantics}</p>
            <p>{capabilities.currencySemantics}</p>
            <p>{capabilities.filingSemantics}</p>
            {capabilities.gaps
              .filter((gap) => !gap.available)
              .map((gap) => (
                <p key={gap.moduleId}>
                  🔴 {gap.moduleId} {gap.moduleName}（归 {gap.ownerModule}）：{gap.impact}
                </p>
              ))}
          </div>
        </Notice>
      ) : null}

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="IOSS 订单"
          value={scopes.IOSS.reduce((sum, row) => sum + row.orderCount, 0)}
          icon={<Scale className="h-4 w-4" />}
        />
        <StatCard
          label="一般 VAT 订单"
          value={scopes.GENERAL_VAT.reduce((sum, row) => sum + row.orderCount, 0)}
          icon={<Receipt className="h-4 w-4" />}
        />
        <StatCard
          label="判不了（不计入 IOSS 申报）"
          value={scopes.UNRESOLVED.reduce((sum, row) => sum + row.orderCount, 0)}
          icon={<CircleAlert className="h-4 w-4" />}
        />
        <StatCard
          label="不在申报范围"
          value={scopes.OUT_OF_SCOPE.reduce((sum, row) => sum + row.orderCount, 0)}
          icon={<ScrollText className="h-4 w-4" />}
        />
      </div>

      {statistics && !statistics.filingDraftAvailable ? (
        <Notice tone="warning" title="读不到申报底稿">
          {statistics.filingDraftImpact}
        </Notice>
      ) : null}

      <SectionCard
        title="金额统计（按分档 × 币种）"
        description="🔴 币种是独立的一列 —— 不同币种绝不直接相加（EUR + USD 不是钱）。"
        extra={
          <div className="flex flex-wrap gap-2">
            {currencyTotals.map((total) => (
              <Badge key={total.currency} tone="info">
                {total.currency} · {total.orderCount} 单 · 税额 {formatAmount(total.taxAmount, total.currency)}
              </Badge>
            ))}
          </div>
        }
      >
        {loading ? (
          <p className="text-muted-foreground text-copy-13">加载中…</p>
        ) : (statistics?.rows.length ?? 0) === 0 ? (
          <EmptyState
            title="还没有统计数据"
            description={emptyStateText(emptyStateKind({
              everSynced: false,
              filtered: false,
              hasScopeFilter: false,
            }))}
          />
        ) : (
          <table className="w-full text-left text-copy-13">
            <thead>
              <tr className="text-muted-foreground border-b">
                <th className="px-3 py-2">统计分档</th>
                <th className="px-3 py-2">币种</th>
                <th className="px-3 py-2">笔数</th>
                <th className="px-3 py-2">计税基数</th>
                <th className="px-3 py-2">代收代缴税额</th>
              </tr>
            </thead>
            <tbody>
              {statistics?.rows.map((row) => (
                <tr key={`${row.filingScope}-${row.currency}`} className="border-border border-b">
                  <td className="px-3 py-2">
                    <Badge
                      tone={
                        row.filingScope === 'IOSS'
                          ? 'success'
                          : row.filingScope === 'UNRESOLVED'
                            ? 'warning'
                            : 'neutral'
                      }
                    >
                      {scopeLabel(row.filingScope)}
                    </Badge>
                    <div className="text-muted-foreground mt-1 text-copy-12">
                      {scopeDescription(row.filingScope)}
                    </div>
                  </td>
                  <td className="px-3 py-2">{row.currency}</td>
                  <td className="px-3 py-2">{row.orderCount}</td>
                  <td className="px-3 py-2">{formatAmount(row.taxBase, row.currency)}</td>
                  <td className="px-3 py-2">{formatAmount(row.taxAmount, row.currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </SectionCard>

      <SectionCard
        title="IOSS 登记与适用范围"
        description="适用店铺与商品是登记的「范围」。应税消费品（酒 / 烟 / 能源）即使在覆盖范围内也不适用 IOSS。"
      >
        {registrations.length === 0 ? (
          <EmptyState title="还没有 IOSS 登记" description="没有生效的 IOSS 登记，所有订单都会判成「一般 VAT」。" />
        ) : (
          <table className="w-full text-left text-copy-13">
            <thead>
              <tr className="text-muted-foreground border-b">
                <th className="px-3 py-2">登记号</th>
                <th className="px-3 py-2">免征门槛</th>
                <th className="px-3 py-2">适用店铺</th>
                <th className="px-3 py-2">适用商品</th>
                <th className="px-3 py-2">生效窗口</th>
              </tr>
            </thead>
            <tbody>
              {registrations.map((row) => (
                <tr key={row.id} className="border-border border-b">
                  <td className="px-3 py-2">
                    {row.registrationNo}
                    {row.current ? (
                      <Badge tone="success" className="ml-2">
                        当前生效
                      </Badge>
                    ) : (
                      <Badge tone="neutral" className="ml-2">
                        已关闭
                      </Badge>
                    )}
                  </td>
                  <td className="px-3 py-2">
                    {formatAmount(row.thresholdAmount, row.thresholdCurrency)}
                  </td>
                  <td className="px-3 py-2">
                    {row.shopScopes.length === 0
                      ? '—'
                      : row.shopScopes.map((s) => `${s.platformCode}/${s.shopCode}`).join('、')}
                  </td>
                  <td className="px-3 py-2">
                    {row.goodsScopes.length === 0
                      ? '—'
                      : row.goodsScopes
                          .map((g) => `${g.skuCode}${g.exciseGoods ? '（应税消费品，不适用 IOSS）' : ''}`)
                          .join('、')}
                  </td>
                  <td className="px-3 py-2">
                    {row.effectiveFrom} → {row.effectiveTo || '至今'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <div className="mt-4 grid gap-3 md:grid-cols-6">
          <Input
            value={registrationNo}
            onChange={(e) => setRegistrationNo(e.target.value)}
            placeholder="IOSS 登记号"
            aria-label="IOSS 登记号"
          />
          <Input
            value={thresholdAmount}
            onChange={(e) => setThresholdAmount(e.target.value)}
            placeholder="免征门槛"
            aria-label="免征门槛"
          />
          <Input
            value={thresholdCurrency}
            onChange={(e) => setThresholdCurrency(e.target.value.toUpperCase())}
            placeholder="门槛币种"
            aria-label="门槛币种"
          />
          <Input
            value={shopScope}
            onChange={(e) => setShopScope(e.target.value)}
            placeholder="适用店铺 AMZ/店铺码"
            aria-label="适用店铺"
          />
          <Input
            value={goodsScope}
            onChange={(e) => setGoodsScope(e.target.value)}
            placeholder="适用商品 SKU（后缀 ! = 应税消费品）"
            aria-label="适用商品"
          />
          <Button
            onClick={() =>
              void run(async () => {
                const shopScopes = shopScope
                  .split(/[,，\s]+/)
                  .filter(Boolean)
                  .map((raw) => {
                    const [platformCode, shopCode] = raw.split('/');
                    return { platformCode: platformCode ?? '', shopCode: shopCode ?? raw };
                  });
                const goodsScopes = goodsScope
                  .split(/[,，\s]+/)
                  .filter(Boolean)
                  .map((raw) => ({
                    skuCode: raw.replace(/!$/, ''),
                    goodsName: '',
                    exciseGoods: raw.endsWith('!'),
                  }));
                await createRegistration({
                  registrationNo,
                  status: 'ACTIVE',
                  effectiveFrom: '',
                  effectiveTo: '',
                  intermediaryName: '',
                  thresholdAmount,
                  thresholdCurrency,
                  shopScopes,
                  goodsScopes,
                  note: '',
                });
                setRegistrationNo('');
                setShopScope('');
                setGoodsScope('');
                return 'IOSS 登记已保存。';
              })
            }
            disabled={busy || registrationNo.trim().length === 0}
          >
            登记 IOSS
          </Button>
        </div>
      </SectionCard>

      <SectionCard
        title="申报周期配置"
        description="辖区码来自 16.3.1.1 的辖区码表（只读）。"
      >
        {cycles.length === 0 ? (
          <EmptyState title="还没有申报周期配置" description="先登记一条申报周期，才能开申报期。" />
        ) : (
          <table className="w-full text-left text-copy-13">
            <thead>
              <tr className="text-muted-foreground border-b">
                <th className="px-3 py-2">辖区</th>
                <th className="px-3 py-2">周期</th>
                <th className="px-3 py-2">起始月</th>
                <th className="px-3 py-2">截止日</th>
                <th className="px-3 py-2">状态</th>
              </tr>
            </thead>
            <tbody>
              {cycles.map((row) => (
                <tr key={row.id} className="border-border border-b">
                  <td className="px-3 py-2">
                    {row.jurisdictionCode}
                    {row.jurisdictionName ? ` · ${row.jurisdictionName}` : ''}
                  </td>
                  <td className="px-3 py-2">{row.filingCycleLabel}</td>
                  <td className="px-3 py-2">{row.filingStartMonth ?? '—'}</td>
                  <td className="px-3 py-2">{row.deadlineDay ?? '—'}</td>
                  <td className="px-3 py-2">{row.status === 'ACTIVE' ? '启用' : '停用'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <div className="mt-4 grid gap-3 md:grid-cols-5">
          <select
            className="border-control-border bg-card text-copy-14 h-9 w-full rounded-md border px-3"
            value={cycleJurisdiction}
            onChange={(e) => setCycleJurisdiction(e.target.value)}
            aria-label="申报周期辖区"
          >
            {(options?.jurisdictions ?? []).map((j) => (
              <option key={j.code} value={j.code}>
                {j.code} · {j.name}
              </option>
            ))}
          </select>
          <select
            className="border-control-border bg-card text-copy-14 h-9 w-full rounded-md border px-3"
            value={cycleKind}
            onChange={(e) => setCycleKind(e.target.value)}
            aria-label="申报周期"
          >
            {FILING_CYCLES.filter((c) => c.value).map((c) => (
              <option key={c.value} value={c.value}>
                {c.label}
              </option>
            ))}
          </select>
          <Input
            value={cycleStartMonth}
            onChange={(e) => setCycleStartMonth(e.target.value)}
            placeholder="起始月 1..12"
            aria-label="申报期起始月"
          />
          <Input
            value={cycleDeadlineDay}
            onChange={(e) => setCycleDeadlineDay(e.target.value)}
            placeholder="截止日 1..28"
            aria-label="申报截止日"
          />
          <Button
            onClick={() =>
              void run(async () => {
                await createFilingCycle({
                  jurisdictionCode: cycleJurisdiction,
                  filingCycle: cycleKind,
                  filingStartMonth: Number(cycleStartMonth),
                  deadlineDay: Number(cycleDeadlineDay),
                  status: 'ACTIVE',
                  note: '',
                });
                return '申报周期配置已保存。';
              })
            }
            disabled={busy}
          >
            登记周期
          </Button>
        </div>
      </SectionCard>

      <SectionCard
        title="申报期与申报状态"
        description="DRAFT 待提交 → DRAFTED 已出底稿 → CONFIRMED 已确认（只能前进）。本系统不代用户向税务机构提交申报。"
        extra={
          <select
            className="border-control-border bg-card text-copy-13 h-8 rounded-md border px-2"
            value={periodStatusFilter}
            onChange={(e) => setPeriodStatusFilter(e.target.value)}
            aria-label="申报状态筛选"
          >
            {FILING_STATUS_FILTERS.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        }
      >
        {periods.length === 0 ? (
          <EmptyState title="还没有申报期" description="开一期申报，才能出这一期的底稿统计。" />
        ) : (
          <table className="w-full text-left text-copy-13">
            <thead>
              <tr className="text-muted-foreground border-b">
                <th className="px-3 py-2">辖区</th>
                <th className="px-3 py-2">区间</th>
                <th className="px-3 py-2">申报状态</th>
                <th className="px-3 py-2">操作</th>
              </tr>
            </thead>
            <tbody>
              {periods.map((row) => (
                <tr key={row.id} className="border-border border-b">
                  <td className="px-3 py-2">
                    {row.jurisdictionCode}
                    {row.jurisdictionName ? ` · ${row.jurisdictionName}` : ''}
                  </td>
                  <td className="px-3 py-2">
                    {row.periodStart} → {row.periodEnd}
                  </td>
                  <td className="px-3 py-2">
                    <Badge
                      tone={
                        row.status === 'CONFIRMED'
                          ? 'success'
                          : row.status === 'DRAFTED'
                            ? 'info'
                            : 'warning'
                      }
                    >
                      {filingStatusLabel(row.status)}
                    </Badge>
                  </td>
                  <td className="px-3 py-2">
                    <div className="flex gap-2">
                      {nextFilingActions(row.status).includes('draft') ? (
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() =>
                            void run(async () => {
                              await draftFilingPeriod(row.id);
                              return '已出底稿。';
                            })
                          }
                          disabled={busy}
                        >
                          出底稿
                        </Button>
                      ) : null}
                      {nextFilingActions(row.status).includes('confirm') ? (
                        <Button
                          size="sm"
                          onClick={() =>
                            void run(async () => {
                              await confirmFilingPeriod(row.id);
                              return '已确认本期数据完整可核。';
                            })
                          }
                          disabled={busy}
                        >
                          确认
                        </Button>
                      ) : null}
                      {nextFilingActions(row.status).length === 0 ? (
                        <span className="text-muted-foreground text-copy-12">
                          已确认 —— 本系统只保证数据完整可核，不代提交。
                        </span>
                      ) : null}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <div className="mt-4 grid gap-3 md:grid-cols-4">
          <select
            className="border-control-border bg-card text-copy-14 h-9 w-full rounded-md border px-3"
            value={periodJurisdiction}
            onChange={(e) => setPeriodJurisdiction(e.target.value)}
            aria-label="申报期辖区"
          >
            {(options?.jurisdictions ?? []).map((j) => (
              <option key={j.code} value={j.code}>
                {j.code} · {j.name}
              </option>
            ))}
          </select>
          <Input
            value={periodStart}
            onChange={(e) => setPeriodStart(e.target.value)}
            placeholder="申报期起 yyyy-MM-dd"
            aria-label="申报期起"
          />
          <Input
            value={periodEnd}
            onChange={(e) => setPeriodEnd(e.target.value)}
            placeholder="申报期止 yyyy-MM-dd"
            aria-label="申报期止"
          />
          <Button
            onClick={() =>
              void run(async () => {
                await createFilingPeriod({
                  jurisdictionCode: periodJurisdiction,
                  periodStart,
                  periodEnd,
                  note: '',
                });
                setPeriodStart('');
                setPeriodEnd('');
                return '申报期已开。';
              })
            }
            disabled={busy || !periodStart || !periodEnd}
          >
            开一期申报
          </Button>
        </div>
      </SectionCard>

      <SectionCard
        title="IOSS 订单清单"
        description="每一行都带一个分档（filingScope）—— 分档是数据，不是颜色。"
        extra={
          <select
            className="border-control-border bg-card text-copy-13 h-8 rounded-md border px-2"
            value={scopeFilter}
            onChange={(e) => setScopeFilter(e.target.value)}
            aria-label="分档筛选"
          >
            {FILING_SCOPE_FILTERS.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        }
      >
        {loading ? (
          <p className="text-muted-foreground text-copy-13">加载中…</p>
        ) : (orders?.items.length ?? 0) === 0 ? (
          <EmptyState title="没有订单" description={emptyStateText(orderEmptyKind)} />
        ) : (
          <table className="w-full text-left text-copy-13">
            <thead>
              <tr className="text-muted-foreground border-b">
                <th className="px-3 py-2">订单号</th>
                <th className="px-3 py-2">店铺</th>
                <th className="px-3 py-2">收货国</th>
                <th className="px-3 py-2">分档</th>
                <th className="px-3 py-2">判定原因</th>
                <th className="px-3 py-2">税率</th>
                <th className="px-3 py-2">代收代缴</th>
              </tr>
            </thead>
            <tbody>
              {orders?.items.map((row) => (
                <tr key={row.id} className="border-border border-b">
                  <td className="px-3 py-2">
                    {row.sourceOrderNo}
                    <div className="text-muted-foreground text-copy-12">{row.orderDate}</div>
                  </td>
                  <td className="px-3 py-2">{row.shopCode}</td>
                  <td className="px-3 py-2">{row.destinationCountry || '—'}</td>
                  <td className="px-3 py-2">
                    <Badge
                      tone={
                        row.filingScope === 'IOSS'
                          ? 'success'
                          : row.filingScope === 'UNRESOLVED'
                            ? 'warning'
                            : 'neutral'
                      }
                    >
                      {scopeLabel(row.filingScope)}
                    </Badge>
                  </td>
                  <td className="px-3 py-2">
                    {reasonLabel(row.reasonCode)}
                    <div className="text-muted-foreground mt-1 text-copy-12">{row.reasonDetail}</div>
                  </td>
                  <td className="px-3 py-2">{taxRateDisplay(row.taxRate, row.taxRateSource)}</td>
                  <td className="px-3 py-2">{formatAmount(row.taxAmount, row.currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </SectionCard>
    </div>
  );
}

export default VatIossWorkspace;
