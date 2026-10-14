'use client';

import { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, BarChart3, RefreshCw, Scale, ShieldAlert, Store } from 'lucide-react';
import { Button, EmptyState, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  AllocationCapabilities,
  AllocationPolicyDetail,
  AllocationPolicyInput,
  AllocationPolicyPage,
  AllocationPolicySaved,
  AllocationResult,
  AllocationRunDetail,
  AllocationRunPage,
  SaleRow,
  SkuRef,
} from '../../../../../packages/api-contracts/src/index';
import { fetchSkus } from './inventory-model';
import {
  bufferModeLabels,
  conservationText,
  createPolicy,
  errorText,
  fetchCapabilities,
  fetchPolicies,
  fetchPolicy,
  fetchRun,
  fetchRuns,
  fetchSales,
  intervalText,
  methodLabels,
  notNull,
  operatingModeLabels,
  outcomeLabels,
  outcomeTone,
  percent,
  previewAllocation,
  qty,
  recordSales,
  retirePolicy,
  runAllocation,
  scopeTypeLabels,
  sumAllocated,
  supersedePolicy,
} from './allocation-policy-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.8.1.1 分配策略配置工作区。
 *
 * <p>蓝图：「定义同一总可用库存分配给多个店铺/平台的方式：按销量占比比例分配、按固定优先级先到先得、
 * 按店铺类型（自营/托管）优先级、留出不分配缓冲。支持 SKU 或店铺维度的策略覆盖。」输出
 * 「生效的分配策略；各店铺分摊额」，且「多平台共享库存时必须有一份统一的分摊算法」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li>**守恒式摆在结果最上面**。可用 = 缓冲 + 可分配，可分配 = Σ分摊 + 未分配。
 *       超卖在界面上表现为「几家加起来超过货」，把等式常驻显示，超卖就是**看得见**的。
 *   <li>**分到 0 件必须带原因**。触顶 / 被前面的分完 / 销量为 0 / 已停用，四种原因
 *       对应完全不同的处置动作，不能都显示成一个 0。
 *   <li>**试算与落库共用同一个渲染函数**。两个端点只有权限与是否落库不同，
 *       界面上「试算看到的」与「落库记下的」因此永远是同一套口径。
 *   <li>**没有统一算法时显示「拒绝」而不是「平均分」**。平均分让每家店都以为有货，
 *       那正是蓝图红线里的超卖。
 *   <li>**历史分档只读**。改策略只能换版（收口旧档 + 开新档），页面不给「就地改」的入口。
 * </ul>
 */
export function AllocationPolicyWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  // 分摊算法是防超卖的那道闸门：写入走 update 权限。
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [caps, setCaps] = useState<AllocationCapabilities | null>(null);
  const [policies, setPolicies] = useState<AllocationPolicyPage | null>(null);
  const [runs, setRuns] = useState<AllocationRunPage | null>(null);
  const [skus, setSkus] = useState<SkuRef[]>([]);
  const [selected, setSelected] = useState<AllocationPolicyDetail | null>(null);
  const [runDetail, setRunDetail] = useState<AllocationRunDetail | null>(null);
  const [sales, setSales] = useState<SaleRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [effectiveOnly, setEffectiveOnly] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [runPage, setRunPage] = useState(0);

  const [skuId, setSkuId] = useState('');
  const [available, setAvailable] = useState('100');
  const [preview, setPreview] = useState<AllocationResult[] | null>(null);
  const [previewing, setPreviewing] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [cap, policyPage, runList, skuList] = await Promise.all([
        fetchCapabilities(),
        fetchPolicies({ effectiveOnly, keyword, page, size: 20 }),
        fetchRuns({ page: runPage, size: 20 }),
        fetchSkus(),
      ]);
      setCaps(cap);
      setPolicies(policyPage);
      setRuns(runList);
      setSkus(skuList);
      if (skuList.length > 0 && !skuId) setSkuId(skuList[0]?.id ?? '');
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [effectiveOnly, keyword, page, runPage, skuId]);

  useEffect(() => {
    if (!readable) return;
    void load();
  }, [readable, load]);

  // 销量名册跟着选中的 SKU 与窗口走：比例分配的分母就是它，界面上必须能先看到它。
  const loadSales = useCallback(async () => {
    if (!skuId) {
      setSales([]);
      return;
    }
    const today = new Date();
    const from = new Date(today.getTime() - 30 * 86400000);
    try {
      setSales(
        await fetchSales({
          skuId,
          from: from.toISOString().slice(0, 10),
          to: today.toISOString().slice(0, 10),
        }),
      );
    } catch (e) {
      setSales([]);
      setError(errorText(e));
    }
  }, [skuId]);

  useEffect(() => {
    if (!readable) return;
    void loadSales();
  }, [readable, loadSales]);

  const openPolicy = useCallback(async (id: string) => {
    setError('');
    try {
      setSelected(await fetchPolicy(id));
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  const openRun = useCallback(async (id: string) => {
    setError('');
    try {
      setRunDetail(await fetchRun(id));
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  const doPreview = useCallback(async () => {
    if (!skuId) {
      setError('请先选一个 SKU：分摊是「这批货分给谁」，空 SKU 没有可回答的问题。');
      return;
    }
    setPreviewing(true);
    setError('');
    try {
      setPreview(
        await previewAllocation({ skuIds: [skuId], availableQuantity: Number(available) }),
      );
    } catch (e) {
      setPreview(null);
      setError(errorText(e));
    } finally {
      setPreviewing(false);
    }
  }, [skuId, available]);

  const doRun = useCallback(async () => {
    if (!skuId) {
      setError('请先选一个 SKU。');
      return;
    }
    setSaving(true);
    setError('');
    try {
      const results = await runAllocation({
        skuIds: [skuId],
        availableQuantity: Number(available),
      });
      setPreview(results);
      setNotice(
        results[0]?.run?.persisted
          ? `已落库为分摊批次 ${results[0].run?.runId}。该批次 append-only：改不了也删不掉，` +
              '下游 7.8.2.1 分摊结果查看读的就是它。'
          : '未落库：没有配置生效的分摊策略。',
      );
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  }, [skuId, available, load]);

  if (!readable) {
    return (
      <EmptyState
        icon={<ShieldAlert className="h-6 w-6" />}
        title="没有分配策略的查看权限"
        description="当前角色未被授予 warehouse.inventory.read，请联系管理员在角色中开启该页面。"
      />
    );
  }

  const policyList = policies?.policies.filter(notNull) ?? [];
  const starved = preview?.[0]?.starvedMemberCount ?? 0;

  return (
    <div className="space-y-6">
      <PageHeader
        title="分配策略配置"
        description="同一份可用库存分给多个店铺/平台的方式：按销量占比、按固定优先级、按店铺类型优先级，并留出不分配缓冲。支持 SKU 与店铺维度覆盖。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'mr-2 h-3.5 w-3.5 animate-spin' : 'mr-2 h-3.5 w-3.5'} />
            刷新
          </Button>
        }
      />

      {error && (
        <div className="border-destructive/40 bg-destructive-soft text-destructive flex items-start gap-3 rounded-md border p-4 text-sm">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <div className="space-y-2">
            <p>{error}</p>
            <Button variant="outline" size="sm" onClick={() => void load()}>
              重新加载
            </Button>
          </div>
        </div>
      )}
      {notice && (
        <div className="border-success/40 bg-success-soft text-success rounded-md border p-4 text-sm">
          {notice}
        </div>
      )}

      {caps && !caps.salesBasisAvailable && (
        <div className="border-warning/40 bg-warning-soft text-warning rounded-md border p-4 text-sm">
          <p className="font-medium">销量数据无法自动派生</p>
          <p className="mt-1">{caps.salesBasisNote}</p>
        </div>
      )}
      {caps && (
        <div className="border-control-border bg-muted/40 text-muted-foreground rounded-md border p-4 text-xs">
          <p className="text-foreground font-medium">缓冲口径说明</p>
          <p className="mt-1">{caps.bufferNote}</p>
        </div>
      )}

      <div className="grid gap-4 md:grid-cols-3">
        <StatCard
          label={effectiveOnly ? '在效策略' : '策略分档'}
          value={policies === null ? '—' : policies.total}
          polarity="neutral"
          comparisonLabel="SKU 档 > 店铺档 > 全租户兜底档"
        />
        <StatCard
          label="分摊批次"
          value={runs === null ? '—' : runs.total}
          polarity="neutral"
          comparisonLabel="append-only，超卖复盘靠它回答「当时按哪一档」"
        />
        <StatCard
          label="本次分到 0 件的店"
          value={preview === null ? '—' : starved}
          polarity={preview !== null && starved > 0 ? 'negative' : 'neutral'}
          comparisonLabel="每一行都带原因：触顶 / 被分完 / 零销量 / 已停用"
        />
      </div>

      <SectionCard
        title="分摊试算"
        description="试算一行都不写：先看结果，确认无误再落库。可用量留空则由服务按可售质量状态现算。"
        extra={
          <div className="flex flex-wrap items-end gap-2">
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">SKU</span>
              <select
                className="border-control-border bg-card rounded-md border px-2 py-1.5 text-sm"
                value={skuId}
                onChange={(e) => {
                  setSkuId(e.target.value);
                  setPreview(null);
                }}
              >
                {skus.length === 0 ? <option value="">没有可选 SKU</option> : null}
                {skus.filter(notNull).map((s) => (
                  <option key={s.id ?? ''} value={s.id ?? ''}>
                    {s.code} · {s.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">当前可用量</span>
              <input
                className="border-control-border bg-card w-28 rounded-md border px-2 py-1.5 text-sm"
                value={available}
                onChange={(e) => setAvailable(e.target.value)}
              />
            </label>
            <Button size="sm" onClick={() => void doPreview()} disabled={previewing}>
              {previewing ? '试算中…' : '试算'}
            </Button>
            {writable ? (
              <Button size="sm" variant="outline" onClick={() => void doRun()} disabled={saving}>
                分摊并落库
              </Button>
            ) : null}
          </div>
        }
      >
        {preview === null ? (
          <EmptyState
            icon={<Scale className="h-5 w-5" />}
            title={previewing ? '试算中' : '还没有试算结果'}
            description={
              previewing
                ? '正在按生效策略计算各店分摊额。'
                : '选一个 SKU 与可用量后试算。试算不写任何数据，可以随便试。'
            }
          />
        ) : (
          <AllocationResultView result={preview[0]} />
        )}
      </SectionCard>

      <SalesSection
        sales={sales}
        skuId={skuId}
        writable={writable}
        saving={saving}
        onSaved={async (message) => {
          setNotice(message);
          setSaving(false);
          await loadSales();
          void doPreview();
        }}
        onError={(message) => {
          setError(message);
          setSaving(false);
        }}
        onStartSaving={() => {
          setError('');
          setSaving(true);
        }}
      />

      <PolicyEditor
        writable={writable}
        selected={selected}
        saving={saving}
        onSaved={(saved: AllocationPolicySaved) => {
          setNotice(saved.message);
          setSaving(false);
          void load();
        }}
        onError={(message) => {
          setError(message);
          setSaving(false);
        }}
        onStartSaving={() => {
          setError('');
          setSaving(false);
        }}
        onSupersede={async (id, input) => {
          try {
            const saved = await supersedePolicy(id, input);
            setNotice(saved.message);
            setSaving(false);
            void load();
          } catch (e) {
            setError(errorText(e));
            setSaving(false);
          }
        }}
        onRetire={async (id) => {
          try {
            const saved = await retirePolicy(id);
            setNotice(saved.message);
            void load();
          } catch (e) {
            setError(errorText(e));
          }
        }}
      />

      <SectionCard
        title="策略分档"
        description="改策略 = 收口旧档 + 开新档，历史各档原样保留。已收口的档永远读得到当时那套算法。"
        extra={
          <div className="flex items-center gap-2">
            <input
              className="border-control-border bg-card rounded-md border px-3 py-2 text-sm"
              placeholder="搜索对象名 / 备注"
              value={keyword}
              onChange={(e) => {
                setKeyword(e.target.value);
                setPage(0);
              }}
            />
            <label className="text-muted-foreground flex items-center gap-1 text-xs">
              <input
                type="checkbox"
                checked={effectiveOnly}
                onChange={(e) => {
                  setEffectiveOnly(e.target.checked);
                  setPage(0);
                }}
              />
              只看在效
            </label>
          </div>
        }
      >
        {policyList.length === 0 ? (
          <EmptyState
            icon={<Store className="h-5 w-5" />}
            title={loading ? '加载中' : keyword ? '没有匹配的策略' : '还没有配置分配策略'}
            description={
              loading
                ? '正在读取策略分档。'
                : keyword
                  ? '换个关键字试试。'
                  : '没有统一分摊策略时，多平台共享库存只会被各店各自判断有没有货 —— 那正是超卖。'
            }
          />
        ) : (
          <>
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left text-xs">
                <tr>
                  <th className="py-2">范围</th>
                  <th className="py-2">对象</th>
                  <th className="py-2">方法</th>
                  <th className="py-2">缓冲</th>
                  <th className="py-2 text-right">成员</th>
                  <th className="py-2">生效区间</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {policyList.map((p) => (
                  <tr key={p.id ?? ''} className="border-control-border border-t">
                    <td className="py-2 text-xs">{scopeTypeLabels[p.scopeType] ?? p.scopeType}</td>
                    <td className="py-2">{p.referenceLabel || '（全租户兜底）'}</td>
                    <td className="py-2 text-xs">{methodLabels[p.method] ?? p.method}</td>
                    <td className="py-2 text-xs">
                      {p.bufferMode === 'RATIO'
                        ? percent(p.bufferRatio)
                        : `${qty(p.bufferQuantity)} 件`}
                    </td>
                    <td className="py-2 text-right">{selected?.policy?.id === p.id ? '—' : ''}</td>
                    <td className="text-muted-foreground py-2 text-xs">
                      {intervalText(p.effectiveFrom, p.effectiveTo)}
                    </td>
                    <td className="py-2 text-right">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => void openPolicy(p.id ?? '')}
                      >
                        查看 / 换版
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {policies && policies.total > 20 ? (
              <Pager page={page} total={policies.total} size={20} onChange={setPage} />
            ) : null}
          </>
        )}
        {selected ? <PolicyDetail detail={selected} /> : null}
      </SectionCard>

      <SectionCard
        title="分摊批次"
        description="每一次落库的分摊都是一条不可改的事实。7.8.2.1 分摊结果查看读的就是它。"
      >
        {runs === null || runs.runs.filter(notNull).length === 0 ? (
          <EmptyState
            icon={<BarChart3 className="h-5 w-5" />}
            title={loading ? '加载中' : '还没有分摊批次'}
            description={
              loading
                ? '正在读取分摊批次。'
                : '试算不落库。上面点「分摊并落库」后，这里会出现一条记录。'
            }
          />
        ) : (
          <>
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left text-xs">
                <tr>
                  <th className="py-2">SKU</th>
                  <th className="py-2">方法</th>
                  <th className="py-2 text-right">可用</th>
                  <th className="py-2 text-right">缓冲</th>
                  <th className="py-2 text-right">可分配</th>
                  <th className="py-2 text-right">Σ分摊</th>
                  <th className="py-2 text-right">未分配</th>
                  <th className="py-2 text-right">零份额店</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {runs.runs.filter(notNull).map((r) => (
                  <tr key={r.runId ?? ''} className="border-control-border border-t">
                    <td className="py-2">{r.skuCode || '（未登记编码）'}</td>
                    <td className="py-2 text-xs">{methodLabels[r.method] ?? r.method}</td>
                    <td className="py-2 text-right">{qty(r.availableQuantity)}</td>
                    <td className="py-2 text-right">{qty(r.bufferQuantity)}</td>
                    <td className="py-2 text-right">{qty(r.allocatableQuantity)}</td>
                    <td className="py-2 text-right">{qty(r.allocatedTotal)}</td>
                    <td className="py-2 text-right">{qty(r.unallocatedQuantity)}</td>
                    <td
                      className={`py-2 text-right ${(r.starvedLineCount ?? 0) > 0 ? outcomeTone('NO_SALES') : ''}`}
                    >
                      {qty(r.starvedLineCount)}
                    </td>
                    <td className="py-2 text-right">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => void openRun(r.runId ?? '')}
                      >
                        逐店明细
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {runs.total > 20 ? (
              <Pager page={runPage} total={runs.total} size={20} onChange={setRunPage} />
            ) : null}
          </>
        )}
        {runDetail ? <RunDetailView detail={runDetail} /> : null}
      </SectionCard>
    </div>
  );
}

// ------------------------------------------------------------ 结果渲染（试算与落库共用）

/**
 * 分摊结果。
 *
 * <p>试算与落库走**同一个组件**：两个端点只有权限与是否落库不同，
 * 渲染口径不同就会出现「试算看到的」与「落库记下的」两套数。
 */
function AllocationResultView({ result }: { result: AllocationResult | null | undefined }) {
  if (!result) return null;
  const lines = result.lines?.filter(notNull) ?? [];
  if (!result.effective?.policy) {
    return (
      <EmptyState
        icon={<ShieldAlert className="h-5 w-5" />}
        title="没有生效的分配策略"
        description={
          result.note ??
          '没有统一分摊算法时结果不是「平均分」而是「拒绝」—— 平均分让每家店都以为有货，那正是超卖。'
        }
      />
    );
  }
  const summed = sumAllocated(lines);
  const consistent = Math.abs(summed - Number(result.allocatedTotal ?? 0)) < 1e-6;
  return (
    <div className="space-y-3">
      <div className="border-control-border bg-muted/40 text-muted-foreground space-y-1 rounded-md border p-3 text-xs">
        <p className="text-foreground font-medium">守恒式</p>
        {conservationText(result).map((line) => (
          <p key={line}>{line}</p>
        ))}
        <p
          className={
            consistent
              ? 'text-emerald-600 dark:text-emerald-400'
              : 'text-rose-600 dark:text-rose-400'
          }
        >
          前端自算 Σ分摊 = {summed.toLocaleString('zh-CN')} 件，
          {consistent ? '与后端一致' : '与后端不一致 —— 这是必须被看见的错账'}
        </p>
      </div>

      <table className="w-full text-sm">
        <thead className="text-muted-foreground text-left text-xs">
          <tr>
            <th className="py-1 text-right">次序</th>
            <th className="py-1">店铺</th>
            <th className="py-1">类型</th>
            <th className="py-1 text-right">销量</th>
            <th className="py-1 text-right">占比</th>
            <th className="py-1 text-right">分摊额</th>
            <th className="py-1">为什么是这个数</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((l) => (
            <tr key={l.referenceId ?? ''} className="border-control-border border-t">
              <td className="text-muted-foreground py-1 text-right">{l.allocationOrder}</td>
              <td className="py-1">{l.referenceLabel || l.referenceId}</td>
              <td className="py-1 text-xs">
                {operatingModeLabels[l.shopOperatingMode] ?? l.shopOperatingMode}
              </td>
              <td className="py-1 text-right">{qty(l.salesUnits)}</td>
              <td className="py-1 text-right">
                {l.salesShare === null || l.salesShare === undefined ? '—' : percent(l.salesShare)}
              </td>
              <td className={`py-1 text-right font-medium ${outcomeTone(l.outcome)}`}>
                {qty(l.allocatedQty)}
              </td>
              <td className={`py-1 text-xs ${outcomeTone(l.outcome)}`}>
                {outcomeLabels[l.outcome] ?? l.outcome}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <p className="text-muted-foreground text-xs">{result.note}</p>
      {result.run?.persisted ? (
        <p className="text-muted-foreground text-xs">
          已落库为批次 <span className="font-mono">{result.run.runId}</span>（
          {new Date(result.run.allocatedAt).toLocaleString('zh-CN')}）。
        </p>
      ) : null}
    </div>
  );
}

function RunDetailView({ detail }: { detail: AllocationRunDetail }) {
  const r = detail.run;
  if (!r) return null;
  return (
    <div className="border-control-border mt-4 space-y-2 rounded-md border p-4">
      <p className="text-sm">
        批次 <span className="font-mono text-xs">{r.runId}</span> · {r.skuCode || '（未登记编码）'}{' '}
        · {methodLabels[r.method] ?? r.method}
      </p>
      <p className="text-muted-foreground text-xs">
        分摊时刻 {new Date(r.allocatedAt).toLocaleString('zh-CN')} · 策略「
        {r.policyLabel || '（未命名）'}」
      </p>
      <p className="text-muted-foreground text-xs">
        可用 {qty(r.availableQuantity)} = 缓冲 {qty(r.bufferQuantity)} + 可分配{' '}
        {qty(r.allocatableQuantity)}
        ；可分配 {qty(r.allocatableQuantity)} = Σ分摊 {qty(r.allocatedTotal)} + 未分配{' '}
        {qty(r.unallocatedQuantity)}
      </p>
      <table className="w-full text-sm">
        <thead className="text-muted-foreground text-left text-xs">
          <tr>
            <th className="py-1 text-right">次序</th>
            <th className="py-1">店铺</th>
            <th className="py-1 text-right">分摊额</th>
            <th className="py-1">原因</th>
          </tr>
        </thead>
        <tbody>
          {detail.lines.filter(notNull).map((l) => (
            <tr key={l.referenceId ?? ''} className="border-control-border border-t">
              <td className="text-muted-foreground py-1 text-right">{l.allocationOrder}</td>
              <td className="py-1">{l.referenceLabel || l.referenceId}</td>
              <td className={`py-1 text-right font-medium ${outcomeTone(l.outcome)}`}>
                {qty(l.allocatedQty)}
              </td>
              <td className={`py-1 text-xs ${outcomeTone(l.outcome)}`}>
                {outcomeLabels[l.outcome] ?? l.outcome}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function PolicyDetail({ detail }: { detail: AllocationPolicyDetail }) {
  const p = detail.policy;
  if (!p) return null;
  return (
    <div className="border-control-border mt-4 space-y-2 rounded-md border p-4">
      <p className="text-sm">
        {scopeTypeLabels[p.scopeType] ?? p.scopeType} · {p.referenceLabel || '（全租户兜底）'} ·{' '}
        {methodLabels[p.method] ?? p.method}
      </p>
      <p className="text-muted-foreground text-xs">
        缓冲 {p.bufferMode === 'RATIO' ? percent(p.bufferRatio) : `${qty(p.bufferQuantity)} 件`} ·
        销量回看 {p.salesWindowDays} 天 · 生效区间 {intervalText(p.effectiveFrom, p.effectiveTo)}
      </p>
      {p.method === 'SHOP_TYPE_PRIORITY' ? (
        <p className="text-muted-foreground text-xs">
          店铺类型优先级：
          {(p.shopTypeOrder ?? '')
            .split(',')
            .map((m) => operatingModeLabels[m] ?? m)
            .join(' → ')}
        </p>
      ) : null}
      <table className="w-full text-sm">
        <thead className="text-muted-foreground text-left text-xs">
          <tr>
            <th className="py-1 text-right">优先级</th>
            <th className="py-1">店铺</th>
            <th className="py-1">类型</th>
            <th className="py-1 text-right">份额上限</th>
            <th className="py-1">启用</th>
          </tr>
        </thead>
        <tbody>
          {detail.members.filter(notNull).map((m) => (
            <tr key={m.referenceId ?? ''} className="border-control-border border-t">
              <td className="py-1 text-right">{m.priority}</td>
              <td className="py-1">{m.referenceLabel || m.referenceId}</td>
              <td className="py-1 text-xs">
                {operatingModeLabels[m.shopOperatingMode] ?? m.shopOperatingMode}
              </td>
              <td className="py-1 text-right">
                {m.shareLimit === null || m.shareLimit === undefined ? (
                  <span className="text-amber-600 dark:text-amber-400">不设上限</span>
                ) : (
                  percent(m.shareLimit)
                )}
              </td>
              <td className="py-1 text-xs">{m.enabled ? '是' : '否'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ------------------------------------------------------------ 销量登记

function SalesSection({
  sales,
  skuId,
  writable,
  saving,
  onSaved,
  onError,
  onStartSaving,
}: {
  sales: SaleRow[];
  skuId: string;
  writable: boolean;
  saving: boolean;
  onSaved: (message: string) => Promise<void>;
  onError: (message: string) => void;
  onStartSaving: () => void;
}) {
  const [shopId, setShopId] = useState('');
  const [shopLabel, setShopLabel] = useState('');
  const [units, setUnits] = useState('0');
  const [days, setDays] = useState('30');

  const submit = async () => {
    if (!skuId) {
      onError('请先在上面选一个 SKU。');
      return;
    }
    if (!shopId.trim()) {
      onError('请填写店铺 ID：它是跨服务定位键，店铺在 channel 服务，本服务不建外键也不查它的表。');
      return;
    }
    const sold = Number(units);
    if (Number.isNaN(sold) || sold < 0) {
      onError('销量不能为负数。');
      return;
    }
    onStartSaving();
    try {
      const today = new Date();
      const from = new Date(today.getTime() - Number(days) * 86400000);
      const saved = await recordSales({
        sourceSystem: 'CHANNEL',
        referenceId: shopId.trim(),
        ...(shopLabel.trim() ? { referenceLabel: shopLabel.trim() } : {}),
        skuId,
        periodStart: from.toISOString().slice(0, 10),
        periodEnd: today.toISOString().slice(0, 10),
        unitsSold: sold,
      });
      await onSaved(saved.message ?? '已登记销量。');
    } catch (e) {
      onError(errorText(e));
    }
  };

  return (
    <SectionCard
      title="各店历史销量"
      description="销量是比例分配的分母。库存流水没有店铺维度、订单域尚未交付，所以销量在这里登记 —— 它是本服务唯一的销量真相来源。"
      extra={
        writable ? (
          <div className="flex flex-wrap items-end gap-2">
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">店铺 ID</span>
              <input
                className="border-control-border bg-card w-44 rounded-md border px-2 py-1.5 text-sm"
                value={shopId}
                onChange={(e) => setShopId(e.target.value)}
                placeholder="channel 的 shop id"
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">店铺名</span>
              <input
                className="border-control-border bg-card w-32 rounded-md border px-2 py-1.5 text-sm"
                value={shopLabel}
                onChange={(e) => setShopLabel(e.target.value)}
                placeholder="可读名快照"
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">销量</span>
              <input
                className="border-control-border bg-card w-24 rounded-md border px-2 py-1.5 text-sm"
                value={units}
                onChange={(e) => setUnits(e.target.value)}
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">回看天数</span>
              <input
                className="border-control-border bg-card w-24 rounded-md border px-2 py-1.5 text-sm"
                value={days}
                onChange={(e) => setDays(e.target.value)}
              />
            </label>
            <Button size="sm" onClick={() => void submit()} disabled={saving}>
              登记
            </Button>
          </div>
        ) : null
      }
    >
      {sales.length === 0 ? (
        <EmptyState
          icon={<BarChart3 className="h-5 w-5" />}
          title="窗口内还没有销量登记"
          description="比例分配在没有分母时如实返回 0，而不是均分 —— 均分会让零销量店铺凭空拿到货。"
        />
      ) : (
        <table className="w-full text-sm">
          <thead className="text-muted-foreground text-left text-xs">
            <tr>
              <th className="py-2">店铺</th>
              <th className="py-2 text-right">窗口内销量</th>
              <th className="py-2 text-right">登记条数</th>
            </tr>
          </thead>
          <tbody>
            {sales.filter(notNull).map((s) => (
              <tr key={s.referenceId ?? ''} className="border-control-border border-t">
                <td className="py-2">{s.referenceLabel || s.referenceId}</td>
                <td className="py-2 text-right">{qty(s.units)}</td>
                <td className="py-2 text-right">{s.records}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </SectionCard>
  );
}

// ------------------------------------------------------------ 策略编辑

function PolicyEditor({
  writable,
  selected,
  saving,
  onSaved,
  onError,
  onStartSaving,
  onSupersede,
  onRetire,
}: {
  writable: boolean;
  selected: AllocationPolicyDetail | null;
  saving: boolean;
  onSaved: (saved: AllocationPolicySaved) => void;
  onError: (message: string) => void;
  onStartSaving: () => void;
  onSupersede: (id: string, input: AllocationPolicyInput) => Promise<void>;
  onRetire: (id: string) => Promise<void>;
}) {
  const current = selected?.policy ?? null;
  const [scopeType, setScopeType] = useState('DEFAULT');
  const [referenceId, setReferenceId] = useState('');
  const [referenceLabel, setReferenceLabel] = useState('');
  const [method, setMethod] = useState('FIXED_PRIORITY');
  const [bufferMode, setBufferMode] = useState('RATIO');
  const [bufferRatio, setBufferRatio] = useState('10');
  const [bufferQty, setBufferQty] = useState('0');
  const [windowDays, setWindowDays] = useState('30');
  const [typeOrder, setTypeOrder] = useState('');
  const [note, setNote] = useState('');
  const [rows, setRows] = useState<MemberRow[]>([]);
  const [localError, setLocalError] = useState('');

  // 选中一档时把它的值灌进表单：换版 = 以当前档为初值改一改，而不是从空白重填。
  useEffect(() => {
    if (!current) return;
    setScopeType(current.scopeType);
    setReferenceId(current.referenceId ?? '');
    setReferenceLabel(current.referenceLabel);
    setMethod(current.method);
    setBufferMode(current.bufferMode);
    setBufferRatio(String(Number((current.bufferRatio ?? 0) * 100)));
    setBufferQty(String(current.bufferQuantity ?? 0));
    setWindowDays(String(current.salesWindowDays ?? 30));
    setTypeOrder(current.shopTypeOrder ?? '');
    setNote(current.note);
    setRows(
      (selected?.members ?? []).filter(notNull).map((m) => ({
        referenceId: m.referenceId ?? '',
        referenceLabel: m.referenceLabel,
        mode: m.shopOperatingMode,
        priority: String(m.priority ?? 100),
        shareLimit:
          m.shareLimit === null || m.shareLimit === undefined ? '' : String(Number(m.shareLimit)),
        enabled: Boolean(m.enabled),
      })),
    );
  }, [current, selected]);

  const build = (): AllocationPolicyInput | null => {
    const ratio = bufferMode === 'RATIO' ? Number(bufferRatio) / 100 : 0;
    if (bufferMode === 'RATIO' && (Number.isNaN(ratio) || ratio < 0 || ratio > 1)) {
      setLocalError(
        '缓冲比例必须在 0% 与 100% 之间。缓冲是防超卖的最后一道防线，越界等于没有防线。',
      );
      return null;
    }
    const qtyValue = bufferMode === 'QUANTITY' ? Number(bufferQty) : 0;
    if (bufferMode === 'QUANTITY' && (Number.isNaN(qtyValue) || qtyValue < 0)) {
      setLocalError('缓冲件数不能为负数。');
      return null;
    }
    if (rows.length === 0) {
      setLocalError('至少要配一个参与分摊的店铺：没有成员的分摊档解析不出「分给谁」。');
      return null;
    }
    const seen = new Set<string>();
    for (const row of rows) {
      if (!row.referenceId.trim()) {
        setLocalError('每个成员都要填店铺 ID。');
        return null;
      }
      if (seen.has(row.referenceId.trim())) {
        setLocalError(
          `店铺 ${row.referenceId} 出现了两次：同一个店出现两次会让「它一共分到几成」有两套答案。`,
        );
        return null;
      }
      seen.add(row.referenceId.trim());
    }
    setLocalError('');
    return {
      scopeType,
      sourceSystem: scopeType === 'DEFAULT' ? '' : scopeType === 'SHOP' ? 'CHANNEL' : 'CATALOG',
      referenceId: scopeType === 'DEFAULT' ? null : referenceId.trim(),
      referenceLabel,
      method,
      bufferMode,
      bufferRatio: ratio,
      bufferQuantity: qtyValue,
      shopTypeOrder: typeOrder.trim() ? typeOrder.trim() : null,
      salesWindowDays: Number(windowDays) || 30,
      effectiveFrom: null,
      note,
      members: rows.map((row) => ({
        sourceSystem: 'CHANNEL',
        referenceId: row.referenceId.trim(),
        referenceLabel: row.referenceLabel,
        shopOperatingMode: row.mode,
        priority: Number(row.priority) || 100,
        shareLimit: row.shareLimit ? Number(row.shareLimit) : null,
        enabled: row.enabled,
      })),
    } as AllocationPolicyInput;
  };

  const submit = async () => {
    const input = build();
    if (!input) return;
    onStartSaving();
    try {
      onSaved(await createPolicy(input));
    } catch (e) {
      onError(errorText(e));
    }
  };

  if (!writable) return null;

  return (
    <SectionCard
      title={current ? '换版当前策略' : '新建策略档'}
      description="策略内容写定后不可改：换版 = 收口旧档 + 开新档，历史那一档永远是「当时真正生效的那套算法」的唯一凭据。"
    >
      <div className="space-y-4">
        {(localError || null) && (
          <p className="border-destructive/40 bg-destructive-soft text-destructive rounded-md border p-3 text-sm">
            {localError}
          </p>
        )}
        <div className="grid gap-3 md:grid-cols-4">
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground block text-xs">生效范围</span>
            <select
              className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
              value={scopeType}
              onChange={(e) => setScopeType(e.target.value)}
            >
              <option value="DEFAULT">全租户兜底</option>
              <option value="SHOP">按店铺</option>
              <option value="SKU">按 SKU</option>
            </select>
          </label>
          {scopeType !== 'DEFAULT' ? (
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground block text-xs">对象 ID</span>
              <input
                className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
                value={referenceId}
                onChange={(e) => setReferenceId(e.target.value)}
              />
            </label>
          ) : null}
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground block text-xs">分摊方法</span>
            <select
              className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
              value={method}
              onChange={(e) => setMethod(e.target.value)}
            >
              <option value="PROPORTIONAL_SALES">按销量占比比例分配</option>
              <option value="FIXED_PRIORITY">按固定优先级先到先得</option>
              <option value="SHOP_TYPE_PRIORITY">按店铺类型优先级</option>
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground block text-xs">缓冲口径</span>
            <select
              className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
              value={bufferMode}
              onChange={(e) => setBufferMode(e.target.value)}
            >
              <option value="RATIO">按比例留</option>
              <option value="QUANTITY">按件数留</option>
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground block text-xs">
              {bufferMode === 'RATIO' ? '缓冲比例 %' : '缓冲件数'}
            </span>
            <input
              className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
              value={bufferMode === 'RATIO' ? bufferRatio : bufferQty}
              onChange={(e) =>
                bufferMode === 'RATIO'
                  ? setBufferRatio(e.target.value)
                  : setBufferQty(e.target.value)
              }
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground block text-xs">销量回看天数</span>
            <input
              className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
              value={windowDays}
              onChange={(e) => setWindowDays(e.target.value)}
            />
          </label>
          {method === 'SHOP_TYPE_PRIORITY' ? (
            <label className="space-y-1 text-sm md:col-span-2">
              <span className="text-muted-foreground block text-xs">
                店铺类型优先级（逗号分隔的五种经营模式，留空用默认顺序）
              </span>
              <input
                className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
                value={typeOrder}
                onChange={(e) => setTypeOrder(e.target.value)}
                placeholder="SELF_FULFILLED,SEMI_CONSIGNMENT,FULL_CONSIGNMENT,OVERSEAS_WAREHOUSE,LOCAL"
              />
            </label>
          ) : null}
        </div>

        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-sm font-medium">参与分摊的店铺</span>
            <Button
              variant="outline"
              size="sm"
              onClick={() =>
                setRows([
                  ...rows,
                  {
                    referenceId: '',
                    referenceLabel: '',
                    mode: 'SELF_FULFILLED',
                    priority: '100',
                    shareLimit: '',
                    enabled: true,
                  },
                ])
              }
            >
              增加店铺
            </Button>
          </div>
          {rows.length === 0 ? (
            <EmptyState
              icon={<Store className="h-5 w-5" />}
              title="还没有成员店铺"
              description="没有成员的分摊档解析不出「分给谁」，它会静默地给不出任何结果。"
            />
          ) : (
            <div className="space-y-2">
              {rows.map((row, index) => (
                <div
                  key={index}
                  className="border-control-border grid items-end gap-2 rounded-md border p-2 md:grid-cols-6"
                >
                  <label className="space-y-1 text-sm">
                    <span className="text-muted-foreground block text-xs">店铺 ID</span>
                    <input
                      className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
                      value={row.referenceId}
                      onChange={(e) =>
                        updateRow(rows, setRows, index, { referenceId: e.target.value })
                      }
                    />
                  </label>
                  <label className="space-y-1 text-sm">
                    <span className="text-muted-foreground block text-xs">店铺名</span>
                    <input
                      className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
                      value={row.referenceLabel}
                      onChange={(e) =>
                        updateRow(rows, setRows, index, { referenceLabel: e.target.value })
                      }
                    />
                  </label>
                  <label className="space-y-1 text-sm">
                    <span className="text-muted-foreground block text-xs">类型</span>
                    <select
                      className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
                      value={row.mode}
                      onChange={(e) => updateRow(rows, setRows, index, { mode: e.target.value })}
                    >
                      {Object.entries(operatingModeLabels).map(([code, label]) => (
                        <option key={code} value={code}>
                          {label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="space-y-1 text-sm">
                    <span className="text-muted-foreground block text-xs">优先级</span>
                    <input
                      className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
                      value={row.priority}
                      onChange={(e) =>
                        updateRow(rows, setRows, index, { priority: e.target.value })
                      }
                    />
                  </label>
                  <label className="space-y-1 text-sm">
                    <span className="text-muted-foreground block text-xs">
                      份额上限（留空=不限）
                    </span>
                    <input
                      className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
                      value={row.shareLimit}
                      onChange={(e) =>
                        updateRow(rows, setRows, index, { shareLimit: e.target.value })
                      }
                      placeholder="0.5"
                    />
                  </label>
                  <div className="flex items-center gap-3 pb-1.5">
                    <label className="text-muted-foreground flex items-center gap-1 text-xs">
                      <input
                        type="checkbox"
                        checked={row.enabled}
                        onChange={(e) =>
                          updateRow(rows, setRows, index, { enabled: e.target.checked })
                        }
                      />
                      启用
                    </label>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setRows(rows.filter((_, i) => i !== index))}
                    >
                      移除
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        <label className="space-y-1 text-sm">
          <span className="text-muted-foreground block text-xs">备注</span>
          <textarea
            className="border-control-border bg-card w-full rounded-md border px-2 py-1.5 text-sm"
            value={note}
            onChange={(e) => setNote(e.target.value)}
            rows={2}
          />
        </label>

        <div className="flex items-center gap-2">
          <Button size="sm" onClick={() => void submit()} disabled={saving}>
            新建一档
          </Button>
          {current ? (
            <Button
              size="sm"
              variant="outline"
              disabled={saving}
              onClick={() => {
                const input = build();
                if (input) void onSupersede(current.id ?? '', input);
              }}
            >
              换版当前这一档
            </Button>
          ) : null}
          {current && !current.effectiveTo ? (
            <Button
              size="sm"
              variant="outline"
              disabled={saving}
              onClick={() => void onRetire(current.id ?? '')}
            >
              作废这一档
            </Button>
          ) : null}
          <span className="text-muted-foreground text-xs">
            整个路径上没有删除入口：策略档与分摊批次都是事实，作废走「收口区间」，行永久保留。
          </span>
        </div>
      </div>
    </SectionCard>
  );
}

interface MemberRow {
  referenceId: string;
  referenceLabel: string;
  mode: string;
  priority: string;
  shareLimit: string;
  enabled: boolean;
}

function updateRow(
  rows: MemberRow[],
  setRows: (rows: MemberRow[]) => void,
  index: number,
  patch: Partial<MemberRow>,
) {
  setRows(rows.map((row, i) => (i === index ? { ...row, ...patch } : row)));
}

function Pager({
  page,
  total,
  size,
  onChange,
}: {
  page: number;
  total: number;
  size: number;
  onChange: (page: number) => void;
}) {
  const pages = Math.ceil(total / size);
  return (
    <div className="text-muted-foreground mt-3 flex items-center justify-end gap-2 text-xs">
      <span>
        第 {page + 1} / {Math.max(1, pages)} 页，共 {total} 条
      </span>
      <Button variant="outline" size="sm" disabled={page <= 0} onClick={() => onChange(page - 1)}>
        上一页
      </Button>
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

export { bufferModeLabels };
