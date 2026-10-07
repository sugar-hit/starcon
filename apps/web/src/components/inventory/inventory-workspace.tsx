'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { AlertTriangle, Boxes, PackageSearch, RefreshCw, Trash2, TriangleAlert } from 'lucide-react';
import { Button, EmptyState, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  DimensionCapability,
  InventoryOverview,
  InventoryPolicy,
  InventoryPolicyInput,
  LegalEntityRef,
  OverviewGroup,
  OverviewRow,
  QualityStatusRef,
  WarehouseRef,
} from '../../../../../packages/api-contracts/src/catalog';
import { deletePolicy, errorText, fetchCapabilities, fetchLegalEntities, fetchOverview, fetchPolicies, fetchQualityStatuses, fetchWarehouses, groupByDimension, groupLabel, parseDetailHref, qty, roleBadge, roleLabels, rowComposition, savePolicy, scopeLabels, type BucketDrill, type OverviewParams } from './inventory-model';
import { InventoryBucketDetail } from './inventory-bucket-detail';
import { InventoryLedger } from './inventory-ledger';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.1.1.1 库存总览工作区。
 *
 * <p>分区顺序按运营的判断顺序排：先看总量（在手/可用/在途）→ 再看异常（零库存、超储、
 * 未设阈值）→ 再看分布图 → 最后才是逐行明细。
 *
 * <p>五条界面纪律：
 *
 * <ul>
 *   <li>**在手与可用分开展示，且在途单列**。可用不含在途（未到货不能发货），
 *       在手含在途（货权已归本租户）。合成一个数就看不出调拨在途占了多少。
 *   <li>**「未设阈值」与「未超储」分开**。没有阈值就没有超储这个判断，
 *       把两者都画成 0 会让人以为已经查过、没问题。
 *   <li>**合计不受分页影响**，且页面上标出它是全量合计 —— 否则翻页时数字变小会被当成丢数据。
 *   <li>**跨服务筛选（类目/品牌/店铺）禁用并说明归属**，而不是发一个必然 422 的请求。
 *   <li>**行点击切换到 7.1.1.2 明细并带上该行的全部维度**，把 `detailHref` 的路由契约接成
 *       真实下钻：点哪一行，明细就只看那一行。
 * </ul>
 */
export function InventoryWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [overview, setOverview] = useState<InventoryOverview | null>(null);
  const [warehouses, setWarehouses] = useState<WarehouseRef[]>([]);
  const [entities, setEntities] = useState<LegalEntityRef[]>([]);
  const [statuses, setStatuses] = useState<QualityStatusRef[]>([]);
  const [capabilities, setCapabilities] = useState<DimensionCapability[]>([]);
  const [policies, setPolicies] = useState<InventoryPolicy[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [expanded, setExpanded] = useState<string | null>(null);
  // 7.1.1.1 总览 / 7.1.1.2 明细 / 7.1.2.1 流水台账：同一页面内切换，行内下钻时带上维度。
  // 台账另有独立路由 /inventory/ledger（那是 7.1.1.2「查看流水」入口的契约地址），
  // 页内这个入口是同一工作区内的就地切换，少一次跳转。
  const [view, setView] = useState<'OVERVIEW' | 'BUCKET' | 'LEDGER'>('OVERVIEW');
  const [drill, setDrill] = useState<BucketDrill | null>(null);

  const [warehouseId, setWarehouseId] = useState('');
  const [legalEntityId, setLegalEntityId] = useState('');
  const [keyword, setKeyword] = useState('');
  const [zeroStock, setZeroStock] = useState(false);
  const [overstock, setOverstock] = useState(false);
  const [sort, setSort] = useState('ON_HAND');
  const [page, setPage] = useState(0);

  const [policyScope, setPolicyScope] = useState('WAREHOUSE');
  const [policyKey, setPolicyKey] = useState('');
  const [policyMax, setPolicyMax] = useState('');
  const [policyError, setPolicyError] = useState('');
  const [policyBusy, setPolicyBusy] = useState(false);

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    const params: OverviewParams = { sort, page, size: 50 };
    if (warehouseId) params.warehouseId = warehouseId;
    if (legalEntityId) params.legalEntityId = legalEntityId;
    if (keyword.trim()) params.keyword = keyword.trim();
    // 复选框只表达"只看"，不表达"不看"：后端把未传与 false 视为两种含义。
    if (zeroStock) params.zeroStock = true;
    if (overstock) params.overstock = true;
    try {
      setOverview(await fetchOverview(params));
    } catch (e) {
      setError(errorText(e));
      setOverview(null);
    } finally {
      setLoading(false);
    }
  }, [readable, warehouseId, legalEntityId, keyword, zeroStock, overstock, sort, page]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!readable) return;
    void (async () => {
      try {
        const [caps, wh, le, qs, pol] = await Promise.all([
          fetchCapabilities(),
          fetchWarehouses(),
          fetchLegalEntities(),
          fetchQualityStatuses(),
          fetchPolicies(''),
        ]);
        setCapabilities(caps);
        setWarehouses(wh);
        setEntities(le);
        setStatuses(qs);
        setPolicies(pol);
      } catch (e) {
        setError(errorText(e));
      }
    })();
  }, [readable]);

  const summary = overview?.summary ?? null;
  // 分布图数据：四个维度放在同一份响应里，前端不再二次聚合（避免与表格对不上）。
  // 数组身份必须稳定，否则每次渲染都是新引用，useMemo 形同虚设。
  const groups = useMemo(() => overview?.groups ?? [], [overview]);
  const warehouseChart = useMemo(
    () =>
      groupByDimension(groups, 'WAREHOUSE').map((g) => ({
        label: g.label,
        可售: Number(g.sellable),
        待检: Number(g.pendingInspection),
        残次: Number(g.defective),
        在途: Number(g.inTransit),
      })),
    [groups],
  );
  const roleChart = useMemo(
    () =>
      groupByDimension(groups, 'QUALITY_ROLE').map((g) => ({
        label: groupLabel(g),
        在手: Number(g.onHand),
        在途: Number(g.inTransit),
      })),
    [groups],
  );
  const roleColors = ['#6366f1', '#f59e0b', '#f43f5e', '#10b981'];

  const unavailable = capabilities.filter((c) => !c.available);

  const policyTarget = useMemo(() => {
    if (policyScope === 'DEFAULT') return '';
    if (policyScope === 'WAREHOUSE') return warehouseId;
    if (policyScope === 'SKU') return '';
    return policyKey.trim();
  }, [policyScope, warehouseId, policyKey]);

  async function submitPolicy() {
    setPolicyError('');
    setNotice('');
    if (!policyMax.trim()) {
      setPolicyError('必须填写超储上限，否则这条策略不会参与超储判定');
      return;
    }
    if (policyScope !== 'DEFAULT' && !policyTarget) {
      setPolicyError(`${scopeLabels[policyScope]}策略必须指定生效对象`);
      return;
    }
    setPolicyBusy(true);
    try {
      const input: InventoryPolicyInput = {
        scopeType: policyScope,
        scopeKey: policyTarget,
        maxOnHand: Number(policyMax),
        minOnHand: null,
      };
      await savePolicy(null, input);
      setPolicies(await fetchPolicies(''));
      setPolicyMax('');
      setNotice('超储上限已保存，总览的超储判定已按新阈值重算');
      await load();
    } catch (e) {
      setPolicyError(errorText(e));
    } finally {
      setPolicyBusy(false);
    }
  }

  async function removePolicy(id: string) {
    setPolicyError('');
    setNotice('');
    try {
      await deletePolicy(id);
      setPolicies(await fetchPolicies(''));
      setNotice('阈值已删除');
      await load();
    } catch (e) {
      setPolicyError(errorText(e));
    }
  }

  if (!readable) {
    return (
      <EmptyState
        icon={<PackageSearch className="h-6 w-6" />}
        title="没有库存总览的查看权限"
        description="当前角色未被授予 warehouse.inventory.read，请联系管理员在角色中开启该页面。"
      />
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="库存总览"
        description="按仓库 / 货权法人 / SKU 汇总在手、预占、可用、在途、待检与残次数量，并识别零库存与超储。"
        actions={
          <div className="flex items-center gap-2">
            <div className="flex rounded-md border border-control-border p-0.5">
              {(
                [
                  ['OVERVIEW', '库存总览'],
                  ['BUCKET', '库位明细'],
                  ['LEDGER', '流水台账'],
                ] as const
              ).map(([key, label]) => (
                <button
                  key={key}
                  type="button"
                  onClick={() => setView(key)}
                  aria-pressed={view === key}
                  className={`rounded px-3 py-1.5 text-sm transition-colors ${
                    view === key ? 'bg-primary text-primary-foreground' : 'text-muted-foreground'
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>
            <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'mr-2 h-3.5 w-3.5 animate-spin' : 'mr-2 h-3.5 w-3.5'} />
              刷新
            </Button>
          </div>
        }
      />

      {view === 'LEDGER' ? (
        <InventoryLedger drill={drill ? { skuId: drill.skuId, warehouseId: drill.warehouseId } : null} />
      ) : view === 'BUCKET' ? (
        <InventoryBucketDetail drill={drill} readable={readable} />
      ) : (
        <>
      {/* 跨服务筛选未接入：显式说明，而不是让用户填完收一个 422 */}
      {unavailable.length > 0 && (
        <div className="flex items-start gap-3 rounded-md border border-warning/40 bg-warning-soft p-4 text-sm">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0 text-warning" />
          <div className="space-y-1">
            <p className="font-medium">以下筛选条件暂不可用</p>
            <p className="text-muted-foreground">
              {unavailable.map((c) => `${c.label}（属 ${c.owner}）`).join('、')}
              尚未接入本服务。这些条件已被显式禁用，而不是按空结果返回 ——
              否则无法区分「查不了」与「确实没有库存」。
            </p>
          </div>
        </div>
      )}

      {error && (
        <div className="flex items-start gap-3 rounded-md border border-destructive/40 bg-destructive-soft p-4 text-sm text-destructive">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <div className="space-y-2">
            <p>{error}</p>
            <Button variant="outline" size="sm" onClick={() => void load()}>
              重新加载
            </Button>
          </div>
        </div>
      )}

      {/* 总量：先看能不能发货，再看货还在不在手上 */}
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="在手（含在途）" value={qty(summary?.onHand)} polarity="neutral" />
        <StatCard label="可用（不含在途）" value={qty(summary?.available)} polarity="neutral" />
        <StatCard label="在途" value={qty(summary?.inTransit)} polarity="neutral" />
        <StatCard label="预占" value={qty(summary?.reserved)} polarity="neutral" />
      </div>

      {/* 守恒式：把口径直接摆在页面上，而不是让人自己猜为什么两列对不上 */}
      {summary && (
        <div className="grid gap-4 sm:grid-cols-3 xl:grid-cols-6">
          <StatCard label="可售" value={qty(summary.sellable)} polarity="neutral" />
          <StatCard label="待检" value={qty(summary.pendingInspection)} polarity="neutral" />
          <StatCard label="残次" value={qty(summary.defective)} polarity="neutral" />
          <StatCard label="零库存行" value={summary.zeroStockRowCount} polarity="neutral" />
          <StatCard label="超储行" value={summary.overstockRowCount} polarity="negative" />
          <StatCard
            label="未设阈值行"
            value={summary.thresholdUnsetRowCount}
            polarity="neutral"
            comparisonLabel="未配置阈值 ≠ 未超储"
          />
        </div>
      )}

      {summary && !summary.balanceCheck.onHandMatches && (
        <div className="rounded-md border border-destructive/40 bg-destructive-soft p-4 text-sm text-destructive">
          守恒式校验未通过：在手 ≠ 可售 + 待检 + 残次 + 在途。请刷新；若持续出现，说明聚合口径已漂移。
        </div>
      )}

      <SectionCard
        title="筛选"
        description="合计与分布图始终是**全量**口径，不随分页变化。"
      >
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-6">
          <label className="space-y-1 text-sm">
            <span className="block font-medium">仓库</span>
            <select
              className="w-full rounded-md border border-control-border bg-card px-3 py-2 text-sm"
              value={warehouseId}
              onChange={(e) => {
                setWarehouseId(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部仓库</option>
              {warehouses.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.code} {w.name}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="block font-medium">货权法人</span>
            <select
              className="w-full rounded-md border border-control-border bg-card px-3 py-2 text-sm"
              value={legalEntityId}
              onChange={(e) => {
                setLegalEntityId(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部法人</option>
              {entities.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.code} {l.name}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="block font-medium">关键字</span>
            <input
              className="w-full rounded-md border border-control-border bg-card px-3 py-2 text-sm"
              value={keyword}
              placeholder="SKU / 仓库 / 法人 编码或名称"
              onChange={(e) => {
                setKeyword(e.target.value);
                setPage(0);
              }}
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="block font-medium">排序</span>
            <select
              className="w-full rounded-md border border-control-border bg-card px-3 py-2 text-sm"
              value={sort}
              onChange={(e) => {
                setSort(e.target.value);
                setPage(0);
              }}
            >
              <option value="ON_HAND">在手从高到低</option>
              <option value="AVAILABLE">可用从高到低</option>
              <option value="IN_TRANSIT">在途从高到低</option>
              <option value="SKU_CODE">按 SKU 编码</option>
            </select>
          </label>
          <label className="flex items-end gap-2 text-sm">
            <input
              type="checkbox"
              className="h-4 w-4"
              checked={zeroStock}
              onChange={(e) => {
                setZeroStock(e.target.checked);
                setPage(0);
              }}
            />
            只看零库存
          </label>
          <label className="flex items-end gap-2 text-sm">
            <input
              type="checkbox"
              className="h-4 w-4"
              checked={overstock}
              onChange={(e) => {
                setOverstock(e.target.checked);
                setPage(0);
              }}
            />
            只看超储
          </label>
        </div>
      </SectionCard>

      <div className="grid gap-4 lg:grid-cols-2">
        <SectionCard title="按仓库分布" description="可售 / 待检 / 残次 / 在途的堆叠对比。">
          {warehouseChart.length === 0 ? (
            <EmptyState
              icon={<Boxes className="h-5 w-5" />}
              title="暂无数据"
              description="当前筛选条件下没有库存余额行。"
            />
          ) : (
            <ResponsiveContainer width="100%" height={260}>
              <BarChart data={warehouseChart}>
                <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
                <XAxis dataKey="label" fontSize={12} />
                <YAxis fontSize={12} />
                <Tooltip />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Bar dataKey="可售" stackId="q" fill="#6366f1" />
                <Bar dataKey="待检" stackId="q" fill="#f59e0b" />
                <Bar dataKey="残次" stackId="q" fill="#f43f5e" />
                <Bar dataKey="在途" stackId="q" fill="#10b981" />
              </BarChart>
            </ResponsiveContainer>
          )}
        </SectionCard>

        <SectionCard title="按质量状态分布" description="在手与在途并列：在途不计入可用。">
          {roleChart.length === 0 ? (
            <EmptyState
              icon={<Boxes className="h-5 w-5" />}
              title="暂无数据"
              description="当前筛选条件下没有库存余额行。"
            />
          ) : (
            <ResponsiveContainer width="100%" height={260}>
              <BarChart data={roleChart}>
                <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
                <XAxis dataKey="label" fontSize={12} />
                <YAxis fontSize={12} />
                <Tooltip />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Bar dataKey="在手" fill="#6366f1" radius={[4, 4, 0, 0]}>
                  {roleChart.map((entry, index) => (
                    <Cell
                      key={entry.label}
                      fill={
                        entry.label === '可售'
                          ? '#6366f1'
                          : entry.label === '待检'
                            ? '#f59e0b'
                            : '#f43f5e'
                      }
                      opacity={index === 0 ? 1 : 0.9}
                    />
                  ))}
                </Bar>
                <Bar dataKey="在途" fill="#10b981" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </SectionCard>
      </div>

      <SectionCard
        title="汇总表"
        description={
          overview
            ? `共 ${overview.total} 行，当前第 ${overview.page + 1} / ${Math.max(
                1,
                Math.ceil(overview.total / overview.size),
              )} 页；合计为全量 ${summary?.rowCount ?? 0} 行的和。`
            : undefined
        }
        extra={
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={loading || page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              上一页
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={loading || !overview || (page + 1) * overview.size >= overview.total}
              onClick={() => setPage((p) => p + 1)}
            >
              下一页
            </Button>
          </div>
        }
      >
        {loading ? (
          <div className="space-y-2" aria-busy="true" aria-live="polite">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="h-9 animate-pulse rounded bg-muted" />
            ))}
            <p className="text-muted-foreground text-sm">正在汇总库存余额…</p>
          </div>
        ) : !overview || overview.rows.length === 0 ? (
          <EmptyState
            icon={<PackageSearch className="h-6 w-6" />}
            title={overview ? '没有符合条件的库存行' : '尚未加载'}
            description={
              overview
                ? '当前筛选条件下没有库存余额。可放宽筛选，或确认该 SKU 是否已入库。'
                : '请稍候，或点击刷新重新加载。'
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1080px] text-sm">
              <thead>
                <tr className="text-muted-foreground border-b text-left text-xs">
                  <th className="px-3 py-2 font-medium">SKU</th>
                  <th className="px-3 py-2 font-medium">仓库</th>
                  <th className="px-3 py-2 font-medium">货权法人</th>
                  <th className="px-3 py-2 font-medium">质量状态</th>
                  <th className="px-3 py-2 text-right font-medium">在手</th>
                  <th className="px-3 py-2 text-right font-medium">在途</th>
                  <th className="px-3 py-2 text-right font-medium">预占</th>
                  <th className="px-3 py-2 text-right font-medium">可用</th>
                  <th className="px-3 py-2 font-medium">识别</th>
                </tr>
              </thead>
              <tbody>
                {overview.rows.map((row) => (
                  <Row
                    key={row.balanceId}
                    row={row}
                    open={expanded === row.balanceId}
                    onToggle={() =>
                      setExpanded((current) =>
                        current === row.balanceId ? null : row.balanceId,
                      )
                    }
                    onDrill={() => {
                      // 下钻参数取自行上的 detailHref 契约，而不是在前端另拼一份：
                      // 参数名只存在后端一处，两边不会各说各话。
                      const parsed = parseDetailHref(row.detailHref);
                      if (!parsed) return;
                      setDrill(parsed);
                      setView('BUCKET');
                    }}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {writable && (
        <SectionCard
          title="超储阈值"
          description="超储是租户经营决策，按 SKU / 仓库 / 默认三级配置，解析时越具体越优先。"
        >
          <div className="grid gap-3 md:grid-cols-4">
            <label className="space-y-1 text-sm">
              <span className="block font-medium">范围</span>
              <select
                className="w-full rounded-md border border-control-border bg-card px-3 py-2 text-sm"
                value={policyScope}
                onChange={(e) => setPolicyScope(e.target.value)}
              >
                <option value="WAREHOUSE">仓库级</option>
                <option value="SKU">SKU 级</option>
                <option value="CATEGORY">类目级</option>
                <option value="DEFAULT">默认级</option>
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="block font-medium">
                {policyScope === 'DEFAULT' ? '生效对象' : '生效对象（当前筛选仓库）'}
              </span>
              <input
                className="w-full rounded-md border border-control-border bg-card px-3 py-2 text-sm disabled:bg-muted"
                value={policyTarget}
                disabled={policyScope === 'DEFAULT' || policyScope === 'WAREHOUSE'}
                placeholder={
                  policyScope === 'CATEGORY' ? '类目文本' : policyScope === 'SKU' ? 'SKU 编码' : '默认级无需填写'
                }
                onChange={(e) => setPolicyKey(e.target.value)}
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="block font-medium">超储上限</span>
              <input
                className="w-full rounded-md border border-control-border bg-card px-3 py-2 text-sm"
                value={policyMax}
                inputMode="decimal"
                onChange={(e) => setPolicyMax(e.target.value)}
              />
            </label>
            <div className="flex items-end">
              <Button size="sm" disabled={policyBusy} onClick={() => void submitPolicy()}>
                {policyBusy ? '保存中…' : '保存阈值'}
              </Button>
            </div>
          </div>
          {(policyError || notice) && (
            <p
              className={
                policyError
                  ? 'text-destructive mt-3 text-sm'
                  : 'text-muted-foreground mt-3 text-sm'
              }
            >
              {policyError || notice}
            </p>
          )}
          {policies.length > 0 ? (
            <table className="mt-4 w-full text-sm">
              <thead>
                <tr className="text-muted-foreground border-b text-left text-xs">
                  <th className="px-3 py-2 font-medium">范围</th>
                  <th className="px-3 py-2 font-medium">生效对象</th>
                  <th className="px-3 py-2 text-right font-medium">超储上限</th>
                  <th className="px-3 py-2 text-right font-medium">低储下限</th>
                  <th className="px-3 py-2" />
                </tr>
              </thead>
              <tbody>
                {policies.map((p) => (
                  <tr key={p.id} className="border-b last:border-0">
                    <td className="px-3 py-2">{scopeLabels[p.scopeType] ?? p.scopeType}</td>
                    <td className="px-3 py-2 text-muted-foreground">
                      {p.scopeType === 'DEFAULT' ? '全部' : p.scopeKey}
                    </td>
                    <td className="px-3 py-2 text-right">{qty(p.maxOnHand)}</td>
                    <td className="px-3 py-2 text-right">{qty(p.minOnHand)}</td>
                    <td className="px-3 py-2 text-right">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => void removePolicy(p.id)}
                        aria-label="删除阈值"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <p className="text-muted-foreground mt-4 text-sm">
              尚未配置任何阈值。此时总览会把相关行计为「未设阈值」，
              而不是「未超储」—— 两者含义不同。
            </p>
          )}
        </SectionCard>
      )}

      <p className="text-muted-foreground text-xs">
        质量状态维度共 {statuses.length} 项；总览行为法人货权 × SKU × 仓库 × 质量状态，
        点击行可展开构成，或切到「库位明细」按该行四个维度逐级下钻（法人货权 × SKU × 仓库 ×
        库位 × 批次 × 质量状态）。
      </p>
        </>
      )}
    </div>
  );
}

/** 一行汇总。点击展开该行的仓库级 / 状态级构成，数据取自同一份聚合响应。 */
function Row({
  row,
  open,
  onToggle,
  onDrill,
}: {
  row: OverviewRow;
  open: boolean;
  onToggle: () => void;
  onDrill: () => void;
}) {
  return (
    <>
      <tr className="hover:bg-muted/50 cursor-pointer border-b last:border-0" onClick={onToggle}>
        <td className="px-3 py-2">
          <div className="font-medium">{row.skuCode}</div>
          <div className="text-muted-foreground text-xs">{row.skuName}</div>
        </td>
        <td className="px-3 py-2">
          {row.warehouseCode}
          <div className="text-muted-foreground text-xs">{row.warehouseName}</div>
        </td>
        <td className="px-3 py-2">
          {row.legalEntityCode}
          <div className="text-muted-foreground text-xs">{row.legalEntityName}</div>
        </td>
        <td className="px-3 py-2">
          <span
            className={`rounded px-2 py-0.5 text-xs ${roleBadge[row.qualityRole] ?? ''}`}
          >
            {roleLabels[row.qualityRole] ?? row.qualityStatusName}
          </span>
        </td>
        <td className="px-3 py-2 text-right tabular-nums">{qty(row.onHand)}</td>
        <td className="px-3 py-2 text-right tabular-nums">{qty(row.inTransit)}</td>
        <td className="px-3 py-2 text-right tabular-nums">{qty(row.reserved)}</td>
        <td className="px-3 py-2 text-right font-medium tabular-nums">{qty(row.available)}</td>
        <td className="px-3 py-2">
          <div className="flex flex-wrap gap-1">
            {row.zeroStock && (
              <span className="rounded bg-destructive-soft px-2 py-0.5 text-xs text-destructive">
                零库存
              </span>
            )}
            {row.overstock && (
              <span className="rounded bg-warning-soft px-2 py-0.5 text-xs text-warning">
                超储
              </span>
            )}
            {row.inTransit > 0 && (
              <span className="rounded bg-muted px-2 py-0.5 text-xs">在途</span>
            )}
            {!row.thresholdConfigured && (
              <span className="text-muted-foreground rounded bg-muted px-2 py-0.5 text-xs">
                未设阈值
              </span>
            )}
          </div>
        </td>
      </tr>
      {open && (
        <tr className="border-b bg-muted/30 last:border-0">
          <td colSpan={9} className="px-3 py-3">
            <div className="grid gap-4 text-sm sm:grid-cols-2 lg:grid-cols-4">
              <Detail label="在手构成" value={rowComposition(row)} />
              <Detail label="可用" value={`${qty(row.available)}（在手 ${qty(row.onHand)} − 预占 ${qty(row.reserved)}）`} />
              <Detail
                label="在途归属"
                value={
                  row.inTransit > 0
                    ? `${qty(row.inTransit)} 正在运往 ${row.warehouseCode}，不计入可用`
                    : '无在途'
                }
              />
              <Detail
                label="超储阈值"
                value={
                  row.thresholdConfigured
                    ? `${scopeLabels[row.policyScope ?? ''] ?? row.policyScope} 上限 ${qty(row.maxOnHand)}`
                    : '未设阈值，未参与超储判定'
                }
              />
              <Detail
                label="质量状态"
                value={`${row.qualityStatusName}（${
                  row.qualityRole === 'SELLABLE' ? '可售，可被预占' : '不可预占'
                }）`}
              />
              <Detail label="版本" value={String(row.version)} />
              <Detail label="最近更新" value={new Date(row.updatedAt).toLocaleString('zh-CN')} />
              <Detail
                label="下钻到 7.1.1.2"
                value={`本行四个维度（SKU ${row.skuCode} / 仓库 ${row.warehouseCode} / 法人 ${row.legalEntityCode} / 质量 ${row.qualityStatusName}）`}
              />
            </div>
            <div className="mt-3">
              <Button variant="outline" size="sm" onClick={onDrill}>
                查看该行的库位明细
              </Button>
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-muted-foreground text-xs">{label}</div>
      <div className="mt-0.5 tabular-nums">{value}</div>
    </div>
  );
}
