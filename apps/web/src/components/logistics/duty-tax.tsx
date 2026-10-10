'use client';

import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Calculator, RefreshCw, Scale, TriangleAlert } from 'lucide-react';
import {
  Button,
  EmptyState,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  DutyEstimateCreateRequest,
  DutyEstimateLineRow,
  DutyPaymentCreateRequest,
  DutyRateCreateRequest,
  DutyPaymentRow,
  DutyRateRow,
  DutyTaxCapabilities,
  DutyVarianceRow,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  actionLabel,
  createDutyEstimate,
  createDutyRate,
  dateOnly,
  dutyBearerLabels,
  estimateActions,
  estimateSortOptions,
  estimateStatusLabels,
  fetchDutyCapabilities,
  fetchDutyEstimateDetail,
  fetchDutyEstimates,
  fetchDutyRates,
  fetchEstimatableDeclarations,
  fetchVarianceAnalysis,
  fmt,
  incotermLabels,
  lockBlockers,
  lockDutyEstimate,
  moment,
  money,
  paymentKindLabels,
  rateMatchStatusLabels,
  rateScopeLabels,
  rateText,
  recalculateDutyEstimate,
  recordDutyPayment,
  taxKindLabels,
  varianceCauseLabels,
  varianceRateText,
  varianceResolutionLabels,
  voidDutyEstimate,
  type DutyEstimateParams,
} from './duty-tax-model';
import { errorText } from '../inventory/inventory-model';

type Detail = Awaited<ReturnType<typeof fetchDutyEstimateDetail>>;
type Analysis = Awaited<ReturnType<typeof fetchVarianceAnalysis>>;

/** 今天的 ISO 日期（YYYY-MM-DD）。生效日 / 汇率日默认取今天。 */
function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function field(label: string, children: ReactNode) {
  return (
    <label className="space-y-1 text-sm">
      <span className="text-muted-foreground">{label}</span>
      {children}
    </label>
  );
}

const inputClass =
  'bg-background w-full rounded-md border px-2 py-1.5 text-sm outline-none focus-visible:ring-2';

export function DutyTaxWorkspace() {
  const [params, setParams] = useState<DutyEstimateParams>({ page: 0, size: 20 });
  const [data, setData] = useState<Awaited<ReturnType<typeof fetchDutyEstimates>> | null>(null);
  const [caps, setCaps] = useState<DutyTaxCapabilities | null>(null);
  const [rates, setRates] = useState<DutyRateRow[]>([]);
  const [analysis, setAnalysis] = useState<Analysis | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const [detail, setDetail] = useState<Detail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [creating, setCreating] = useState(false);
  const [addingRate, setAddingRate] = useState(false);
  const [paying, setPaying] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [page, capabilities, rateRows, variance] = await Promise.all([
        fetchDutyEstimates(params),
        fetchDutyCapabilities(),
        fetchDutyRates({ page: 0, size: 50 }),
        fetchVarianceAnalysis({}),
      ]);
      setData(page);
      setCaps(capabilities);
      setRates(rateRows);
      setAnalysis(variance);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!openId) {
      setDetail(null);
      setDetailError(null);
      return;
    }
    setDetailLoading(true);
    setDetailError(null);
    fetchDutyEstimateDetail(openId)
      .then(setDetail)
      .catch((e: unknown) => setDetailError(errorText(e)))
      .finally(() => setDetailLoading(false));
  }, [openId]);

  const run = useCallback(
    async (work: () => Promise<{ message?: string }>, reloadDetail = true) => {
      setBusy(true);
      setError(null);
      setNotice(null);
      try {
        const saved = await work();
        setNotice(saved?.message ?? '已保存');
        await load();
        if (reloadDetail && openId) {
          setDetail(await fetchDutyEstimateDetail(openId));
        }
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load, openId],
  );

  const summary = data?.summary;
  const unavailable = useMemo(() => (caps?.dependencies ?? []).filter((d) => !d.available), [caps]);
  const blockers = detail ? lockBlockers(detail) : [];

  return (
    <div className="space-y-6">
      <PageHeader
        title="关税与税费"
        description="按 HS 编码税率与目的国税则估算进口税费，记录实际缴纳与差异，给出到岸成本构成"
        actions={
          <>
            <Button variant="ghost" onClick={() => void load()}>
              <RefreshCw className="size-4" />
              刷新
            </Button>
            <Button variant="secondary" onClick={() => setAddingRate(true)}>
              <Scale className="size-4" />
              录入税率
            </Button>
            <Button onClick={() => setCreating(true)}>
              <Calculator className="size-4" />
              新建税费估算
            </Button>
          </>
        }
      />

      {/* 口径常驻：本模块最容易读错的三件事 */}
      <SectionCard title="估算口径">
        <ul className="text-muted-foreground space-y-1 text-sm">
          <li>
            <strong className="text-foreground">估算与实缴分别留痕</strong>
            ：蓝图原文「税费估算与实际缴纳差异要记录，长期差异大说明 HS 归类或税率配置有误」。
            估算、实缴、差异是三份独立记录，差异区显示的是后端落库的快照，不是页面现算的。
          </li>
          <li>
            <strong className="text-foreground">税率按生效期检索</strong>
            ：税率表带生效/失效日期，估算明细里抄着自己当时用的税率值，
            所以税率表改了也不会改写历史估算。
          </li>
          <li>
            <strong className="text-foreground">0% 税率合法，「没匹配到税率」不合法</strong>
            ：带未匹配税率或没配增值税税率的行锁不了估算单 —— 按 0 税锁定的估算单会让报价少算税。
          </li>
          <li>
            <strong className="text-foreground">DDP/DDU 改变成本归属</strong>
            ：DDU 下关税与目的国税费由买方承担，卖方到岸成本不含它们；
            关税由谁付不影响税率是多少。
          </li>
          {caps && (
            <>
              <li>{caps.estimateVsPaymentNote}</li>
              <li>{caps.rateEffectiveNote}</li>
              <li>{caps.zeroRateNote}</li>
              <li>{caps.dutyBearerNote}</li>
              <li>{caps.landedCostNote}</li>
            </>
          )}
          {unavailable.map((d) => (
            <li key={d.value}>
              <strong className="text-foreground">{d.label}未交付</strong>：{d.note}
            </li>
          ))}
        </ul>
      </SectionCard>

      {error && (
        <div className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm text-destructive">
          {error}
        </div>
      )}
      {notice && (
        <div className="rounded-md border border-emerald-500/40 bg-emerald-500/5 p-3 text-sm">
          {notice}
        </div>
      )}

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <StatCard label="估算单" value={summary?.total ?? 0} comparisonLabel={`已锁定 ${summary?.locked ?? 0}`} />
        <StatCard label="估算税费合计" value={fmt(summary?.estimatedTaxTotal)} comparisonLabel="不受分页影响" />
        <StatCard label="实缴合计" value={fmt(summary?.paidTotal)} comparisonLabel="只取最新对账快照" />
        <StatCard
          label="估算与实缴差额"
          value={fmt(summary?.varianceTotal)}
          comparisonLabel="正数多缴、负数少缴"
        />
        <StatCard
          label="没配到税率的行"
          value={summary?.unmatchedRateLines ?? 0}
          comparisonLabel="这些行所在估算单锁不了"
        />
      </div>

      {/* 差异分析：蓝图那句「长期差异大说明 HS 归类或税率配置有误」的出口 */}
      <SectionCard title="差异分析">
        {!analysis ? (
          <EmptyState title="暂无差异数据" description="还没有录过实际缴纳，所以没有可对比的差异。" />
        ) : (
          <div className="space-y-4">
            <div className="grid gap-3 sm:grid-cols-4">
              <StatCard label="对账次数" value={analysis.estimateCount} />
              <StatCard label="未处理差异" value={analysis.openVarianceCount} />
              <StatCard label="差异合计" value={fmt(analysis.totalVariance)} />
              <StatCard
                label="绝对差异率"
                value={
                  analysis.absVarianceRate === null || analysis.absVarianceRate === undefined
                    ? '—'
                    : `${(analysis.absVarianceRate * 100).toFixed(2)}%`
                }
              />
            </div>
            {analysis.unmatchedRateByHsCode.length > 0 && (
              <div className="rounded-md border border-amber-500/40 bg-amber-500/5 p-3 text-sm">
                <p className="font-medium">领先指标：这些 HS 码没配到关税税率</p>
                <p className="text-muted-foreground mt-1">
                  它比等实缴回来更早暴露问题 —— 税率没配的行一旦被锁定，错误就已经进了报价。
                </p>
                <ul className="mt-2 space-y-1">
                  {analysis.unmatchedRateByHsCode.slice(0, 8).map((u) => (
                    <li key={u.hsCode}>
                      HS {u.hsCode}：{u.lineCount} 行 / {u.estimateCount} 张估算单
                      {u.destinationCountryCodes ? `（${u.destinationCountryCodes}）` : ''}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {analysis.byCause.length > 0 && (
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left">
                  <tr>
                    <th className="py-1">归因</th>
                    <th className="py-1">差异条数</th>
                    <th className="py-1 text-right">估算</th>
                    <th className="py-1 text-right">实缴</th>
                    <th className="py-1 text-right">差额</th>
                  </tr>
                </thead>
                <tbody>
                  {analysis.byCause.map((c) => (
                    <tr key={c.dimension} className="border-t">
                      <td className="py-1">{c.dimensionLabel}</td>
                      <td className="py-1">{c.varianceCount}</td>
                      <td className="py-1 text-right">{fmt(c.estimatedAmount)}</td>
                      <td className="py-1 text-right">{fmt(c.actualAmount)}</td>
                      <td className="py-1 text-right">{fmt(c.varianceAmount)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            <p className="text-muted-foreground text-xs">{analysis.note}</p>
          </div>
        )}
      </SectionCard>

      <SectionCard title="筛选">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {field(
            '状态',
            <select
              className={inputClass}
              value={params.status?.[0] ?? ''}
              onChange={(e) =>
                setParams((p) => ({ ...p, page: 0, status: e.target.value ? [e.target.value] : [] }))
              }
            >
              <option value="">全部</option>
              {(caps?.statusOptions ?? []).map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>,
          )}
          {field(
            '目的国',
            <input
              className={inputClass}
              value={params.destinationCountryCode ?? ''}
              onChange={(e) =>
                setParams((p) => ({ ...p, page: 0, destinationCountryCode: e.target.value }))
              }
              placeholder="US"
            />,
          )}
          {field(
            '关税承担方',
            <select
              className={inputClass}
              value={params.dutyBearer ?? ''}
              onChange={(e) => setParams((p) => ({ ...p, page: 0, dutyBearer: e.target.value }))}
            >
              <option value="">全部</option>
              {(caps?.dutyBearers ?? []).map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>,
          )}
          {field(
            '排序',
            <select
              className={inputClass}
              value={params.sort ?? 'UPDATED_AT'}
              onChange={(e) => setParams((p) => ({ ...p, page: 0, sort: e.target.value }))}
            >
              {estimateSortOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>,
          )}
          {field(
            '关键字',
            <input
              className={inputClass}
              value={params.keyword ?? ''}
              onChange={(e) => setParams((p) => ({ ...p, page: 0, keyword: e.target.value }))}
              placeholder="估算单号 / 报关单号"
            />,
          )}
          <div className="flex items-end gap-4 text-sm">
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={params.unmatchedRateOnly ?? false}
                onChange={(e) =>
                  setParams((p) => ({ ...p, page: 0, unmatchedRateOnly: e.target.checked }))
                }
              />
              只看没配到税率
            </label>
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={params.varianceOnly ?? false}
                onChange={(e) => setParams((p) => ({ ...p, page: 0, varianceOnly: e.target.checked }))}
              />
              只看有差异
            </label>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="税费估算单">
        {loading ? (
          <p className="text-muted-foreground py-6 text-center text-sm">加载中…</p>
        ) : !data || data.rows.length === 0 ? (
          <EmptyState
            title="还没有税费估算"
            description="先录一条税率，再对已报关的报关单建估算单 —— 关税要按 HS 编码税率算，没有税率只能按 0 算，而按 0 算的估算单不允许锁定。"
            action={
              <Button onClick={() => setCreating(true)}>
                <Calculator className="size-4" />
                新建税费估算
              </Button>
            }
          />
        ) : (
          <div className="space-y-3">
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left">
                  <tr>
                    <th className="py-2">估算单号</th>
                    <th className="py-2">报关单</th>
                    <th className="py-2">目的国</th>
                    <th className="py-2">条款 / 承担方</th>
                    <th className="py-2 text-right">估算税费</th>
                    <th className="py-2 text-right">到岸成本</th>
                    <th className="py-2 text-right">实缴</th>
                    <th className="py-2 text-right">差额</th>
                    <th className="py-2">状态</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((row: (typeof data.rows)[number]) => (
                    <tr key={row.id} className="border-t">
                      <td className="py-2 font-medium">{row.estimateCode}</td>
                      <td className="py-2">{row.declarationCode || '—'}</td>
                      <td className="py-2">{row.destinationCountryCode}</td>
                      <td className="py-2">
                        {incotermLabels[row.incoterm] ?? row.incoterm} /{' '}
                        {dutyBearerLabels[row.dutyBearer] ?? row.dutyBearer}
                      </td>
                      <td className="py-2 text-right">
                        {money(row.estimatedTotalTax, row.estimateCurrency)}
                      </td>
                      <td className="py-2 text-right">
                        {money(row.landedCostTotal, row.estimateCurrency)}
                      </td>
                      <td className="py-2 text-right">{fmt(row.paidTotalAmount)}</td>
                      <td className="py-2 text-right">{fmt(row.varianceTotal)}</td>
                      <td className="py-2">
                        <span>{estimateStatusLabels[row.status] ?? row.status}</span>
                        {row.unmatchedRateLines > 0 && (
                          <span className="ml-2 inline-flex items-center gap-1 text-amber-600">
                            <TriangleAlert className="size-3" />
                            {row.unmatchedRateLines} 行缺税率
                          </span>
                        )}
                      </td>
                      <td className="py-2 text-right">
                        <Button variant="ghost" onClick={() => setOpenId(row.id)}>
                          明细
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="text-muted-foreground flex items-center justify-between text-sm">
              <span>
                第 {(data.page ?? 0) + 1} / {data.totalPages} 页，共 {summary?.total ?? 0} 张
              </span>
              <div className="flex gap-2">
                <Button
                  variant="ghost"
                  disabled={(data.page ?? 0) <= 0}
                  onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 0) - 1 }))}
                >
                  上一页
                </Button>
                <Button
                  variant="ghost"
                  disabled={(data.page ?? 0) + 1 >= (data.totalPages ?? 1)}
                  onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 0) + 1 }))}
                >
                  下一页
                </Button>
              </div>
            </div>
          </div>
        )}
      </SectionCard>

      <SectionCard title="税则税率表">
        {rates.length === 0 ? (
          <EmptyState
            title="税率表还是空的"
            description="税率是本模块的输入数据（不是代码里的常量）。没有税率，估算只能按 0 算，而按 0 算的估算单不允许锁定。"
            action={
              <Button onClick={() => setAddingRate(true)}>
                <Scale className="size-4" />
                录入税率
              </Button>
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="py-2">目的国</th>
                  <th className="py-2">适用范围</th>
                  <th className="py-2">HS 前缀</th>
                  <th className="py-2">税种</th>
                  <th className="py-2 text-right">从价税率</th>
                  <th className="py-2 text-right">从量税率</th>
                  <th className="py-2">生效期</th>
                  <th className="py-2">出处</th>
                </tr>
              </thead>
              <tbody>
                {rates.map((r) => (
                  <tr key={r.id} className="border-t">
                    <td className="py-2">{r.destinationCountryCode}</td>
                    <td className="py-2">{rateScopeLabels[r.rateScope] ?? r.rateScope}</td>
                    <td className="py-2">{r.hsCodePrefix || '（国别）'}</td>
                    <td className="py-2">
                      {taxKindLabels[r.taxKind] ?? r.taxKind}
                      {r.preferential ? ` · ${r.treatyCode}` : ''}
                    </td>
                    <td className="py-2 text-right">{rateText(r.adValoremRate)}</td>
                    <td className="py-2 text-right">
                      {Number(r.specificRatePerUnit) > 0
                        ? `${fmt(r.specificRatePerUnit)} / ${r.specificUom}`
                        : '—'}
                    </td>
                    <td className="py-2">
                      {r.effectiveFrom} ~ {dateOnly(r.effectiveTo)}
                    </td>
                    <td className="py-2 text-muted-foreground">{r.sourceReference}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {openId && (
        <Modal open onOpenChange={() => setOpenId(null)}>
          <ModalContent className="max-w-4xl">
            <ModalHeader>
              <ModalTitle>税费估算明细</ModalTitle>
              <ModalDescription>{detail?.note ?? '加载中…'}</ModalDescription>
            </ModalHeader>
            <div className="max-h-[70vh] space-y-4 overflow-y-auto">
              {detailLoading ? (
                <p className="text-muted-foreground py-6 text-center text-sm">加载中…</p>
              ) : detailError ? (
                <div className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm text-destructive">
                  {detailError}
                </div>
              ) : detail ? (
                <>
                  <div className="grid gap-3 sm:grid-cols-3">
                    <StatCard
                      label="估算关税"
                      value={money(detail.estimate.estimatedDutyAmount, detail.estimate.estimateCurrency)}
                    />
                    <StatCard
                      label="估算增值税/GST"
                      value={money(detail.estimate.estimatedVatAmount, detail.estimate.estimateCurrency)}
                    />
                    <StatCard
                      label="估算税费合计"
                      value={money(detail.estimate.estimatedTotalTax, detail.estimate.estimateCurrency)}
                    />
                    <StatCard
                      label="到岸成本（含税）"
                      value={money(detail.estimate.landedCostTotal, detail.estimate.estimateCurrency)}
                    />
                    <StatCard
                      label="卖方承担部分"
                      value={money(detail.estimate.landedCostSeller, detail.estimate.estimateCurrency)}
                      comparisonLabel={
                        detail.estimate.dutyBearer === 'BUYER'
                          ? 'DDU：不含关税与税费'
                          : '含全部税费'
                      }
                    />
                    <StatCard label="汇率快照" value={String(detail.estimate.exchangeRate ?? '—')} comparisonLabel={`刻度 ${detail.estimate.exchangeRateScale ?? '—'} @ ${detail.estimate.exchangeRateDate ?? '—'}`} />
                  </div>

                  {blockers.length > 0 && (
                    <div className="rounded-md border border-amber-500/40 bg-amber-500/5 p-3 text-sm">
                      <p className="font-medium">还不能锁定</p>
                      <ul className="mt-1 list-disc pl-4">
                        {blockers.map((b) => (
                          <li key={b}>{b}</li>
                        ))}
                      </ul>
                    </div>
                  )}

                  <div className="flex flex-wrap gap-2">
                    {estimateActions(detail.estimate.status).map((action) => (
                      <Button
                        key={action}
                        variant={action === 'lock' ? 'default' : 'ghost'}
                        disabled={busy || (action === 'lock' && blockers.length > 0)}
                        title={
                          action === 'lock' && blockers.length > 0
                            ? '还有行没配到税率：按 0 税锁定的估算单会让报价少算税'
                            : undefined
                        }
                        onClick={() => {
                          if (action === 'payment') {
                            setPaying(true);
                          } else if (action === 'void') {
                            const reason = window.prompt('作废原因（必填）');
                            if (reason?.trim()) {
                              void run(() => voidDutyEstimate(openId, reason.trim()));
                            }
                          } else if (action === 'recalculate') {
                            void run(() => recalculateDutyEstimate(openId));
                          } else if (action === 'lock') {
                            void run(() => lockDutyEstimate(openId, ''));
                          }
                        }}
                      >
                        {actionLabel(action)}
                      </Button>
                    ))}
                  </div>

                  <div>
                    <h4 className="mb-2 text-sm font-medium">逐行明细（含命中的税率快照）</h4>
                    <div className="overflow-x-auto">
                      <table className="w-full text-sm">
                        <thead className="text-muted-foreground text-left">
                          <tr>
                            <th className="py-1">行</th>
                            <th className="py-1">HS</th>
                            <th className="py-1 text-right">完税价</th>
                            <th className="py-1 text-right">分摊运费</th>
                            <th className="py-1 text-right">分摊保险</th>
                            <th className="py-1">关税税率</th>
                            <th className="py-1 text-right">关税</th>
                            <th className="py-1 text-right">增值税率</th>
                            <th className="py-1 text-right">增值税</th>
                          </tr>
                        </thead>
                        <tbody>
                          {detail.lines.map((line: DutyEstimateLineRow) => (
                            <tr key={line.id} className="border-t">
                              <td className="py-1">{line.lineNo}</td>
                              <td className="py-1">{line.hsCode || '—'}</td>
                              <td className="py-1 text-right">{fmt(line.lineCustomsValue)}</td>
                              <td className="py-1 text-right">{fmt(line.allocatedFreight)}</td>
                              <td className="py-1 text-right">{fmt(line.allocatedInsurance)}</td>
                              <td className="py-1">
                                {line.rateMatchStatus === 'MATCHED' ? (
                                  <>
                                    {rateText(line.dutyAdValoremRate)}
                                    {line.dutyRatePrefix ? (
                                      <span className="text-muted-foreground">
                                        {' '}
                                        (前缀 {line.dutyRatePrefix})
                                      </span>
                                    ) : null}
                                  </>
                                ) : (
                                  <span className="text-amber-600">
                                    {rateMatchStatusLabels[line.rateMatchStatus] ?? line.rateMatchStatus}
                                  </span>
                                )}
                              </td>
                              <td className="py-1 text-right">{fmt(line.estimatedDutyAmount)}</td>
                              <td className="py-1 text-right">
                                {line.vatRateMatched ? rateText(line.vatRate) : '未配'}
                              </td>
                              <td className="py-1 text-right">{fmt(line.estimatedVatAmount)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </div>

                  <div>
                    <h4 className="mb-2 text-sm font-medium">实际缴纳（append-only）</h4>
                    {detail.payments.length === 0 ? (
                      <p className="text-muted-foreground text-sm">
                        还没有实缴记录。缴款凭证由人工回填，并以录入方式如实标注。
                      </p>
                    ) : (
                      <table className="w-full text-sm">
                        <thead className="text-muted-foreground text-left">
                          <tr>
                            <th className="py-1">期次</th>
                            <th className="py-1">税种</th>
                            <th className="py-1">性质</th>
                            <th className="py-1 text-right">金额</th>
                            <th className="py-1">付款方</th>
                            <th className="py-1">凭证号</th>
                            <th className="py-1">缴纳时间</th>
                          </tr>
                        </thead>
                        <tbody>
                          {detail.payments.map((p: DutyPaymentRow) => (
                            <tr key={p.id} className="border-t">
                              <td className="py-1">第 {p.paymentSequence} 次</td>
                              <td className="py-1">{taxKindLabels[p.taxKind] ?? p.taxKind}</td>
                              <td className="py-1">
                                {paymentKindLabels[p.paymentKind] ?? p.paymentKind}
                              </td>
                              <td className="py-1 text-right">{money(p.paidAmount, p.currency)}</td>
                              <td className="py-1">
                                {dutyBearerLabels[p.payer] ?? p.payer}
                              </td>
                              <td className="py-1">{p.paymentReference}</td>
                              <td className="py-1">{moment(p.paidAt)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                  </div>

                  <div>
                    <h4 className="mb-2 text-sm font-medium">
                      估算与实缴差异（蓝图要求必须记录）
                    </h4>
                    {detail.variances.length === 0 ? (
                      <p className="text-muted-foreground text-sm">
                        还没有差异记录 —— 录一笔实缴就会自动落一条差异快照，「先存实缴、差异以后再补」做不到。
                      </p>
                    ) : (
                      <table className="w-full text-sm">
                        <thead className="text-muted-foreground text-left">
                          <tr>
                            <th className="py-1">对账到</th>
                            <th className="py-1">税种</th>
                            <th className="py-1 text-right">估算</th>
                            <th className="py-1 text-right">实缴累计</th>
                            <th className="py-1 text-right">差额</th>
                            <th className="py-1">差异率</th>
                            <th className="py-1">归因</th>
                            <th className="py-1">处理</th>
                          </tr>
                        </thead>
                        <tbody>
                          {detail.variances.map((v: DutyVarianceRow) => (
                            <tr key={v.id} className="border-t">
                              <td className="py-1">第 {v.paymentSequence} 次缴款</td>
                              <td className="py-1">{taxKindLabels[v.taxKind] ?? v.taxKind}</td>
                              <td className="py-1 text-right">{fmt(v.estimatedAmount)}</td>
                              <td className="py-1 text-right">{fmt(v.actualAmount)}</td>
                              <td className="py-1 text-right">{fmt(v.varianceAmount)}</td>
                              <td className="py-1">{varianceRateText(v)}</td>
                              <td className="py-1">
                                {varianceCauseLabels[v.varianceCause] ?? v.varianceCause}
                              </td>
                              <td className="py-1">
                                {varianceResolutionLabels[v.resolution] ?? v.resolution}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                  </div>
                </>
              ) : null}
            </div>
            <ModalFooter>
              <Button variant="ghost" onClick={() => setOpenId(null)}>
                关闭
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      )}

      {creating && (
        <CreateEstimateModal
          onClose={() => setCreating(false)}
          onSubmit={async (input) => {
            await run(() => createDutyEstimate(input), false);
            setCreating(false);
          }}
        />
      )}

      {addingRate && (
        <AddRateModal
          onClose={() => setAddingRate(false)}
          onSubmit={async (input) => {
            await run(() => createDutyRate(input), false);
            setAddingRate(false);
          }}
        />
      )}

      {paying && detail && (
        <AddPaymentModal
          currency={detail.estimate.estimateCurrency}
          onClose={() => setPaying(false)}
          onSubmit={async (input) => {
            await run(() => recordDutyPayment(detail.estimate.id, input));
            setPaying(false);
          }}
        />
      )}
    </div>
  );
}

function CreateEstimateModal({
  onClose,
  onSubmit,
}: {
  onClose: () => void;
  onSubmit: (input: DutyEstimateCreateRequest) => Promise<void>;
}) {
  const [declarations, setDeclarations] = useState<{ id: string; declarationCode: string }[]>([]);
  const [declarationId, setDeclarationId] = useState('');
  const [incoterm, setIncoterm] = useState('DDU');
  const [dutyBearer, setDutyBearer] = useState('BUYER');
  const [country, setCountry] = useState('US');
  const [currency, setCurrency] = useState('USD');
  const [customsValue, setCustomsValue] = useState('');
  const [goodsValue, setGoodsValue] = useState('');
  const [freight, setFreight] = useState('');
  const [insurance, setInsurance] = useState('');
  const [other, setOther] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    fetchEstimatableDeclarations()
      .then((page) => {
        setDeclarations(
          page.rows.map((r) => ({ id: r.id, declarationCode: r.declarationCode })),
        );
        if (page.rows[0]) {
          setDeclarationId(page.rows[0].id);
          setCountry(page.rows[0].destinationCountryCode);
          setCurrency(page.rows[0].declarationCurrency);
          setGoodsValue(String(page.rows[0].declarationValue ?? ''));
          setCustomsValue(String(page.rows[0].declarationValue ?? ''));
        }
      })
      .catch((e: unknown) => setError(errorText(e)))
      .finally(() => setLoading(false));
  }, []);

  // DDP ⟹ 卖方承担、DDU ⟹ 买方承担：跟着条款走，避免建出一张自相矛盾的估算单。
  useEffect(() => {
    setDutyBearer(incoterm === 'DDP' ? 'SELLER' : 'BUYER');
  }, [incoterm]);

  return (
    <Modal open onOpenChange={onClose}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>新建税费估算</ModalTitle>
          <ModalDescription>
            完税价格默认取报关单的申报货值；关税会按报关单每一行的 HS 编码逐行匹配税率。
          </ModalDescription>
        </ModalHeader>
        <div className="space-y-3">
          {error && <div className="text-destructive text-sm">{error}</div>}
          {field(
            '报关单',
            <select
              className={inputClass}
              value={declarationId}
              onChange={(e) => {
                const found = declarations.find((d) => d.id === e.target.value);
                setDeclarationId(e.target.value);
                if (found) setCurrency('USD');
              }}
            >
              <option value="">请选择</option>
              {declarations.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.declarationCode || d.id}
                </option>
              ))}
            </select>,
          )}
          <div className="grid gap-3 sm:grid-cols-2">
            {field(
              '贸易条款',
              <select className={inputClass} value={incoterm} onChange={(e) => setIncoterm(e.target.value)}>
                {Object.entries(incotermLabels).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}（{value}）
                  </option>
                ))}
              </select>,
            )}
            {field(
              '关税承担方',
              <select
                className={inputClass}
                value={dutyBearer}
                onChange={(e) => setDutyBearer(e.target.value)}
              >
                {Object.entries(dutyBearerLabels).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>,
            )}
            {field('目的国', <input className={inputClass} value={country} onChange={(e) => setCountry(e.target.value)} />)}
            {field('计价币种', <input className={inputClass} value={currency} onChange={(e) => setCurrency(e.target.value)} />)}
            {field('完税价格', <input className={inputClass} value={customsValue} onChange={(e) => setCustomsValue(e.target.value)} placeholder="默认取申报货值" />)}
            {field('货值', <input className={inputClass} value={goodsValue} onChange={(e) => setGoodsValue(e.target.value)} />)}
            {field('运费', <input className={inputClass} value={freight} onChange={(e) => setFreight(e.target.value)} />)}
            {field('保险费', <input className={inputClass} value={insurance} onChange={(e) => setInsurance(e.target.value)} />)}
            {field('其他费用', <input className={inputClass} value={other} onChange={(e) => setOther(e.target.value)} />)}
          </div>
          {loading && <p className="text-muted-foreground text-sm">加载报关单…</p>}
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={busy || !declarationId}
            onClick={() => {
              setBusy(true);
              onSubmit({
                declarationId,
                incoterm,
                dutyBearer,
                // 法人 / 本位币 / 汇率不传就沿用报关单上的申报快照（服务端优先取报关单的值），
                // 所以这里显式带上「同报关单」的默认值，而不是留空。
                legalEntityId: '',
                destinationCountryCode: country.toUpperCase(),
                estimateCurrency: currency.toUpperCase(),
                baseCurrency: 'CNY',
                exchangeRate: 0,
                exchangeRateScale: 6,
                exchangeRateDate: today(),
                customsValue: Number(customsValue || 0),
                goodsValue: Number(goodsValue || 0),
                freightAmount: Number(freight || 0),
                insuranceAmount: Number(insurance || 0),
                otherChargeAmount: Number(other || 0),
                rateDate: today(),
                notes: '',
              }).finally(() => setBusy(false));
            }}
          >
            {busy ? '创建中…' : '创建并按当前税率算税'}
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

function AddRateModal({
  onClose,
  onSubmit,
}: {
  onClose: () => void;
  onSubmit: (input: DutyRateCreateRequest) => Promise<void>;
}) {
  const today = new Date().toISOString().slice(0, 10);
  const [country, setCountry] = useState('US');
  const [scope, setScope] = useState('HS');
  const [prefix, setPrefix] = useState('');
  const [taxKind, setTaxKind] = useState('DUTY');
  const [rate, setRate] = useState('');
  const [from, setFrom] = useState(today);
  const [source, setSource] = useState('');
  const [notes, setNotes] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  return (
    <Modal open onOpenChange={onClose}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>录入税率</ModalTitle>
          <ModalDescription>
            税率是本模块的输入数据，必须能追到出处（哪份税则/公告查来的）—— 没有出处的税率无法复核。
            0% 是合法税率（零税率国家/免税供货），所以这里允许填 0。
          </ModalDescription>
        </ModalHeader>
        <div className="space-y-3">
          {error && <div className="text-destructive text-sm">{error}</div>}
          <div className="grid gap-3 sm:grid-cols-2">
            {field('目的国', <input className={inputClass} value={country} onChange={(e) => setCountry(e.target.value)} />)}
            {field(
              '适用范围',
              <select className={inputClass} value={scope} onChange={(e) => setScope(e.target.value)}>
                <option value="HS">按 HS 编码（关税）</option>
                <option value="COUNTRY">国别（增值税/GST）</option>
              </select>,
            )}
            {field(
              'HS 前缀',
              <input
                className={inputClass}
                value={prefix}
                onChange={(e) => setPrefix(e.target.value)}
                disabled={scope === 'COUNTRY'}
                placeholder={scope === 'COUNTRY' ? '国别税率不填' : '如 610910 或 6109'}
              />,
            )}
            {field(
              '税种',
              <select className={inputClass} value={taxKind} onChange={(e) => setTaxKind(e.target.value)}>
                <option value="DUTY">关税</option>
                <option value="VAT">增值税</option>
                <option value="GST">商品服务税</option>
              </select>,
            )}
            {field(
              '从价税率（小数，0.0325 = 3.25%）',
              <input className={inputClass} value={rate} onChange={(e) => setRate(e.target.value)} placeholder="0.0325" />,
            )}
            {field('生效日', <input type="date" className={inputClass} value={from} onChange={(e) => setFrom(e.target.value)} />)}
          </div>
          {field(
            '税率出处',
            <input className={inputClass} value={source} onChange={(e) => setSource(e.target.value)} placeholder="如 HTS-2026-610910" />,
          )}
          {field('备注', <input className={inputClass} value={notes} onChange={(e) => setNotes(e.target.value)} />)}
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={busy || !country || !rate || !source}
            onClick={() => {
              setBusy(true);
              setError(null);
              onSubmit({
                destinationCountryCode: country.toUpperCase(),
                rateScope: scope,
                // 国别税率本来就没有 HS 前缀：服务端会拒绝「国别税率带 HS 前缀」，
                // 所以这里按适用范围决定传空串还是真前缀，而不是塞 undefined。
                hsCodePrefix: scope === 'COUNTRY' ? '' : prefix,
                taxKind,
                adValoremRate: Number(rate),
                specificRatePerUnit: 0,
                specificUom: 'PCS',
                preferential: false,
                treatyCode: '',
                effectiveFrom: from,
                effectiveTo: '',
                taxSource: 'MANUAL',
                taxRateVerified: false,
                sourceSystem: 'MANUAL_ENTRY',
                sourceReference: source,
                notes,
              })
                .catch((e: unknown) => setError(errorText(e)))
                .finally(() => setBusy(false));
            }}
          >
            {busy ? '保存中…' : '保存税率'}
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

function AddPaymentModal({
  currency,
  onClose,
  onSubmit,
}: {
  currency: string;
  onClose: () => void;
  onSubmit: (input: DutyPaymentCreateRequest) => Promise<void>;
}) {
  const [taxKind, setTaxKind] = useState('DUTY');
  const [paymentKind, setPaymentKind] = useState('PAYMENT');
  const [amount, setAmount] = useState('');
  const [payer, setPayer] = useState('BUYER');
  const [reference, setReference] = useState('');
  const [cause, setCause] = useState('');
  const [causeNote, setCauseNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  return (
    <Modal open onOpenChange={onClose}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>记录实际缴纳</ModalTitle>
          <ModalDescription>
            实缴与差异是<strong>同一次动作</strong>写下的：蓝图要求「税费估算与实际缴纳差异要记录」，
            所以录完实缴会立刻落一条差异快照，DB 层也会核对「有实缴却没有差异记录」并拒收。
            缴错了请新增退汇/调整记录，不要改旧行。
          </ModalDescription>
        </ModalHeader>
        <div className="space-y-3">
          {error && <div className="text-destructive text-sm">{error}</div>}
          <div className="grid gap-3 sm:grid-cols-2">
            {field(
              '税种',
              <select className={inputClass} value={taxKind} onChange={(e) => setTaxKind(e.target.value)}>
                <option value="DUTY">关税</option>
                <option value="VAT">增值税</option>
                <option value="GST">商品服务税</option>
              </select>,
            )}
            {field(
              '缴款性质',
              <select className={inputClass} value={paymentKind} onChange={(e) => setPaymentKind(e.target.value)}>
                <option value="PAYMENT">正常缴纳</option>
                <option value="REFUND">退汇（多缴退回）</option>
                <option value="ADJUSTMENT">海关核定后调整</option>
              </select>,
            )}
            {field(
              '金额',
              <input className={inputClass} value={amount} onChange={(e) => setAmount(e.target.value)} placeholder={currency} />,
            )}
            {field(
              '付款方',
              <select className={inputClass} value={payer} onChange={(e) => setPayer(e.target.value)}>
                {Object.entries(dutyBearerLabels).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>,
            )}
          </div>
          {field(
            '缴款凭证号（必填）',
            <input className={inputClass} value={reference} onChange={(e) => setReference(e.target.value)} placeholder="海关缴款通知书号 / 银行流水号" />,
          )}
          {field(
            '差异归因（可留空，留空则待人工确认）',
            <select className={inputClass} value={cause} onChange={(e) => setCause(e.target.value)}>
              <option value="">待归因</option>
              {Object.entries(varianceCauseLabels)
                .filter(([value]) => value !== 'PENDING')
                .map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
            </select>,
          )}
          {field('归因说明', <input className={inputClass} value={causeNote} onChange={(e) => setCauseNote(e.target.value)} />)}
        </div>
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={busy || !amount || !reference}
            onClick={() => {
              setBusy(true);
              setError(null);
              onSubmit({
                taxKind,
                paymentKind,
                paidAmount: Number(amount),
                currency,
                payer,
                paymentReference: reference,
                // 缴纳时间不传时服务端取 now()：缴款凭证是人工回填的，
                // 时间戳由服务端落比让浏览器时钟决定更可举证。
                paidAt: new Date().toISOString(),
                payerName: '',
                sourceSystem: 'MANUAL_ENTRY',
                sourceReference: `SR-${reference}`,
                // 差异快照行：归因留空就是「待人工确认」，不是「没有差异」。
                declarationLineId: '',
                varianceCause: cause || 'PENDING',
                causeNote,
                varianceResolution: 'OPEN',
                reviewNote: '',
                notes: '',
              })
                .catch((e: unknown) => setError(errorText(e)))
                .finally(() => setBusy(false));
            }}
          >
            {busy ? '记录中…' : '记录实缴并落差异快照'}
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
