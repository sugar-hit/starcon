'use client';

import { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, CalendarClock, RefreshCw, Save, Truck } from 'lucide-react';
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
  ExpiryAlertPage,
  ExpiryPolicyPage,
  FefoAllocationPlan,
  FefoAllocationRequest,
} from '../../../../../packages/api-contracts/src/index';
import {
  allocationComplete,
  expiryAlertSortOptions,
  expiryStatusLabels,
  expiryStatusOptions,
  expiryStatusTone,
  expiryStrategyLabels,
  expiryStrategyOptions,
  expiryText,
  fetchExpiryAlerts,
  fetchExpiryLots,
  fetchExpiryPolicies,
  fetchExpiryPolicy,
  fetchExpiryWarehouses,
  leadDaysText,
  parseQuantity,
  policyEnabledText,
  recommendAllocation,
  saveExpiryPolicy,
  shortfallText,
  type ExpiryAlertParams,
} from './inventory-expiry-model';
import { errorText, fetchSkus, qty } from './inventory-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.3.1.2 效期与先进先出工作区。
 *
 * <p>蓝图：「对有保质期的商品，按 FEFO（先到期先出）或 FIFO 分配出库批次，预警临期与过期库存」，
 * 输出「推荐出库批次；临期/过期预警清单」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li>**过期与临期分列，且阈值来源常驻显示**。临期窗口逐 SKU 可配，
 *       同样是 3 天到期，配 7 天的报临期、配 1 天的报正常 —— 不标来源用户会以为系统算错了。
 *   <li>**分配缺口必须可解释**。后端把过期 / 非可售 / 不可拣 / 无效期 / 无批次
 *       的量逐项报出，这里逐条显示，因为它们的处置动作各不相同（过期走临期品处理、
 *       待检走质检、不可拣走移库），混成一句「缺 20 件」等于没法派工。
 *   <li>**策略开关关着就明说**。`policyEnabled=false` 时出库退回按库位顺序，
 *       界面显示「未启用策略」而不是假装已经按 FEFO 排过。
 *   <li>**效期维度已保留但第一期可不开**。没配策略的 SKU 不会报错，
 *       而是显示兜底默认（30 天）并标明是默认值。
 * </ul>
 */
export function InventoryExpiryWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [alerts, setAlerts] = useState<ExpiryAlertPage | null>(null);
  const [policies, setPolicies] = useState<ExpiryPolicyPage | null>(null);
  // 本模块的仓库参照端点返回 warehouseId（不是通用的 id），两处下拉都用同一份列表。
  const [warehouses, setWarehouses] = useState<
    { warehouseId: string; code: string; name: string }[]
  >([]);
  const [skus, setSkus] = useState<{ id: string; code: string; name: string }[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [keyword, setKeyword] = useState('');
  const [warehouseId, setWarehouseId] = useState('');
  const [skuId, setSkuId] = useState('');
  const [statusFilter, setStatusFilter] = useState('EXPIRING');
  const [leadDays, setLeadDays] = useState('');
  const [includeEmpty, setIncludeEmpty] = useState(false);
  const [sort, setSort] = useState('EXPIRY');
  const [pageNumber, setPageNumber] = useState(0);

  // 分配试算：策略试算是这个页面的主用途之一，出库前先算一遍比事后补救便宜得多。
  const [allocSku, setAllocSku] = useState('');
  const [allocWarehouse, setAllocWarehouse] = useState('');
  const [allocQty, setAllocQty] = useState('');
  const [allocStrategy, setAllocStrategy] = useState('');
  const [plan, setPlan] = useState<FefoAllocationPlan | null>(null);
  const [planError, setPlanError] = useState('');
  const [planLoading, setPlanLoading] = useState(false);

  // 策略编辑
  const [editSku, setEditSku] = useState('');
  const [editOpen, setEditOpen] = useState(false);
  const [editStrategy, setEditStrategy] = useState('FEFO');
  const [editEnabled, setEditEnabled] = useState(false);
  const [editLeadDays, setEditLeadDays] = useState('30');
  const [editLotId, setEditLotId] = useState('');
  const [editLots, setEditLots] = useState<
    { lotId: string; lotCode: string; expiresOn: string | null }[]
  >([]);
  const [editCurrent, setEditCurrent] = useState<Awaited<
    ReturnType<typeof fetchExpiryPolicy>
  > | null>(null);
  const [editNotes, setEditNotes] = useState('');
  const [saveError, setSaveError] = useState('');
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    const params: ExpiryAlertParams = { sort, page: pageNumber, size: 50 };
    if (keyword.trim()) params.keyword = keyword.trim();
    if (warehouseId) params.warehouseId = warehouseId;
    if (skuId) params.skuId = skuId;
    if (statusFilter) params.expiryStatus = [statusFilter];
    if (leadDays.trim()) params.leadDays = Number(leadDays.trim());
    // 默认只看有货的批次（后端默认行为）；显式勾选才把零库存批次也列进来。
    if (includeEmpty) params.inStock = false;
    try {
      // 预警清单与策略列表互不依赖，并发拉；任一失败都如实报出来。
      const [a, p] = await Promise.all([
        fetchExpiryAlerts(params),
        fetchExpiryPolicies({ page: 0, size: 50 }),
      ]);
      setAlerts(a);
      setPolicies(p);
    } catch (e) {
      setError(errorText(e));
      setAlerts(null);
      setPolicies(null);
    } finally {
      setLoading(false);
    }
  }, [
    readable,
    keyword,
    warehouseId,
    skuId,
    statusFilter,
    leadDays,
    includeEmpty,
    sort,
    pageNumber,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!readable) return;
    // 仓库下拉走本模块自己的参照端点：它按仓库 code 排序，而 code 正是 NONE 策略下的排序基准。
    // 筛选与试算共用同一份列表，只拉一次。
    fetchExpiryWarehouses()
      .then(setWarehouses)
      .catch(() => setWarehouses([]));
    fetchSkus()
      .then(setSkus)
      .catch(() => setSkus([]));
  }, [readable]);

  async function runAllocation() {
    setPlanError('');
    const quantity = parseQuantity(allocQty);
    if (!allocSku) {
      setPlanError('请选择要出库的 SKU');
      return;
    }
    if (!allocWarehouse) {
      setPlanError('请选择出库仓库：FEFO 与 FIFO 都要在仓内排序，跨仓合并排序会排出拣不到的顺序');
      return;
    }
    if (quantity === null || quantity <= 0) {
      setPlanError('请填写大于 0 的出库数量');
      return;
    }
    // codegen 出来的请求体字段全是「必填但可为 null」，不显式给 null 过不了 strict 检查。
    const body: FefoAllocationRequest = {
      skuId: allocSku,
      warehouseId: allocWarehouse,
      quantity,
      strategy: allocStrategy || null,
      specifiedLotId: null,
    };
    setPlanLoading(true);
    try {
      setPlan(await recommendAllocation(body));
    } catch (e) {
      setPlanError(errorText(e));
      setPlan(null);
    } finally {
      setPlanLoading(false);
    }
  }

  async function openEditor(skuId: string) {
    setSaveError('');
    setEditSku(skuId);
    setEditOpen(true);
    setEditCurrent(null);
    setEditLots([]);
    try {
      const policy = await fetchExpiryPolicy(skuId);
      setEditCurrent(policy);
      setEditStrategy(policy.strategy === 'NONE' ? 'FEFO' : policy.strategy);
      setEditEnabled(policy.enabled);
      setEditLeadDays(String(policy.leadDays));
      setEditLotId(policy.specifiedLotId ?? '');
      setEditNotes('');
      if (policy.strategy === 'SPECIFIED_LOT' || policy.specifiedLotId) {
        setEditLots(await fetchExpiryLots(skuId));
      }
    } catch (e) {
      setSaveError(errorText(e));
    }
  }

  // 切到「指定批次」时才去拉批次下拉 —— 否则每次改策略都要多一次无用请求。
  useEffect(() => {
    if (!editOpen || !editSku) return;
    if (editStrategy !== 'SPECIFIED_LOT' || editLots.length > 0) return;
    fetchExpiryLots(editSku)
      .then(setEditLots)
      .catch(() => setEditLots([]));
  }, [editOpen, editSku, editStrategy, editLots.length]);

  async function submitPolicy() {
    setSaveError('');
    const days = Number(editLeadDays.trim());
    if (!Number.isFinite(days) || days < 0 || days > 3650) {
      setSaveError('预警提前天数必须在 0 ~ 3650 之间');
      return;
    }
    if (editStrategy === 'SPECIFIED_LOT' && !editLotId) {
      setSaveError('指定批次策略必须选择要从哪一批出');
      return;
    }
    setSaving(true);
    try {
      await saveExpiryPolicy({
        skuId: editSku,
        strategy: editStrategy,
        enabled: editEnabled,
        leadDays: days,
        specifiedLotId: editLotId || null,
        notes: editNotes,
      });
      setEditOpen(false);
      await load();
    } catch (e) {
      setSaveError(errorText(e));
    } finally {
      setSaving(false);
    }
  }

  if (!readable) {
    return (
      <EmptyState
        icon={<CalendarClock className="h-6 w-6" />}
        title="没有效期管理的查看权限"
        description="当前角色未被授予 warehouse.inventory.read，请联系管理员在角色中开启该页面。"
      />
    );
  }

  const summary = alerts?.summary ?? null;
  const totalPages = alerts ? Math.max(1, Math.ceil(alerts.total / alerts.size)) : 1;
  const reasonLines = plan ? shortfallText(plan.ineligible) : [];

  return (
    <div className="space-y-6">
      <PageHeader
        title="效期与先进先出"
        description="按 FEFO 先到期先出或 FIFO 先进先出分配出库批次，预警临期与过期库存。提前天数逐 SKU 可配。"
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
          <p>{error}</p>
        </div>
      )}

      {summary && summary.lotCount > 0 && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            label="已过期批次"
            value={String(summary.expiredLotCount)}
            comparisonLabel={`在手 ${qty(summary.expiredOnHand)}（不能再发，需先走临期品处理）`}
          />
          <StatCard
            label="临期批次"
            value={String(summary.expiringLotCount)}
            comparisonLabel={`在手 ${qty(summary.expiringOnHand)}`}
          />
          <StatCard
            label="正常 / 非效期"
            value={`${summary.healthyLotCount} / ${summary.noExpiryLotCount}`}
            comparisonLabel={`覆盖 ${summary.skuCount} 个 SKU · ${summary.distinctLeadDaysCount} 种阈值`}
          />
          <StatCard
            label="效期区间"
            value={summary.earliestExpiry ?? '—'}
            comparisonLabel={
              summary.latestExpiry
                ? `最晚失效 ${summary.latestExpiry}`
                : '命中范围里没有带效期的批次'
            }
          />
        </div>
      )}

      <SectionCard title="筛选">
        <div className="grid gap-3 md:grid-cols-3 lg:grid-cols-4">
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <input
              className="border-input w-full rounded-md border px-3 py-2 text-sm"
              value={keyword}
              placeholder="批号 / SKU 编码或名称"
              onChange={(e) => {
                setKeyword(e.target.value);
                setPageNumber(0);
              }}
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">仓库</span>
            <select
              className="border-input w-full rounded-md border px-3 py-2 text-sm"
              value={warehouseId}
              onChange={(e) => {
                setWarehouseId(e.target.value);
                setPageNumber(0);
              }}
            >
              <option value="">全部</option>
              {warehouses.map((w) => (
                <option key={w.warehouseId} value={w.warehouseId}>
                  {w.code} {w.name}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">SKU</span>
            <select
              className="border-input w-full rounded-md border px-3 py-2 text-sm"
              value={skuId}
              onChange={(e) => {
                setSkuId(e.target.value);
                setPageNumber(0);
              }}
            >
              <option value="">全部</option>
              {skus.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.code} {s.name}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">效期档位</span>
            <select
              className="border-input w-full rounded-md border px-3 py-2 text-sm"
              value={statusFilter}
              onChange={(e) => {
                setStatusFilter(e.target.value);
                setPageNumber(0);
              }}
            >
              <option value="">默认（已过期 + 临期）</option>
              {expiryStatusOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">临时覆盖提前天数</span>
            <input
              className="border-input w-full rounded-md border px-3 py-2 text-sm"
              value={leadDays}
              placeholder="留空则逐 SKU 用各自策略"
              onChange={(e) => {
                setLeadDays(e.target.value);
                setPageNumber(0);
              }}
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">排序</span>
            <select
              className="border-input w-full rounded-md border px-3 py-2 text-sm"
              value={sort}
              onChange={(e) => {
                setSort(e.target.value);
                setPageNumber(0);
              }}
            >
              {expiryAlertSortOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex items-center gap-2 self-end text-sm">
            <input
              type="checkbox"
              checked={includeEmpty}
              onChange={(e) => {
                setIncludeEmpty(e.target.checked);
                setPageNumber(0);
              }}
            />
            <span className="text-muted-foreground">包含零库存批次</span>
          </label>
        </div>
        {alerts?.leadDaysNote ? (
          <p className="text-muted-foreground mt-3 text-xs">阈值口径：{alerts.leadDaysNote}</p>
        ) : null}
      </SectionCard>

      <SectionCard
        title="效期预警清单"
        extra={
          alerts && alerts.total > 0 ? (
            <span className="text-muted-foreground text-sm">
              共 {alerts.total} 个批次 · 第 {alerts.page + 1} / {totalPages} 页
            </span>
          ) : null
        }
      >
        {loading && !alerts ? (
          <p className="text-muted-foreground py-8 text-center text-sm">正在加载效期预警…</p>
        ) : !alerts || alerts.rows.length === 0 ? (
          <EmptyState
            icon={<CalendarClock className="h-6 w-6" />}
            title="没有需要处理的效期批次"
            description={
              keyword.trim() || warehouseId || skuId || statusFilter || leadDays.trim()
                ? '试着放宽筛选条件或换一个档位：当前档位下没有批次，临期与过期的判定是逐 SKU 按各自阈值算的。'
                : '这个租户当前没有临期或过期的在库批次。批次由收货流程生成，带失效日期的批次会自动进入这里。'
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground border-b text-left">
                  <th className="p-2">批号 / 商品</th>
                  <th className="p-2">有效期</th>
                  <th className="p-2">档位</th>
                  <th className="p-2">阈值</th>
                  <th className="p-2 text-right">在手</th>
                  <th className="p-2 text-right">可用</th>
                  <th className="p-2 text-right">分布</th>
                  <th className="p-2"></th>
                </tr>
              </thead>
              <tbody>
                {alerts.rows.map((row) => {
                  const tone = expiryStatusTone(row.expiryStatus);
                  return (
                    <tr key={row.lotId} className="border-b last:border-0">
                      <td className="p-2">
                        <div>{row.lotCode}</div>
                        <div className="text-muted-foreground text-xs">
                          {row.skuCode} {row.skuName}
                          {row.receivedOn ? ` · 收货 ${row.receivedOn}` : ''}
                        </div>
                      </td>
                      <td className="p-2">{expiryText(row.expiresOn, row.daysToExpiry)}</td>
                      <td className="p-2">
                        <span
                          className={
                            tone === 'danger'
                              ? 'text-destructive font-medium'
                              : tone === 'warning'
                                ? 'text-warning font-medium'
                                : 'text-muted-foreground'
                          }
                        >
                          {expiryStatusLabels[row.expiryStatus] ?? row.expiryStatus}
                        </span>
                        <div className="text-muted-foreground text-xs">
                          批次状态 {row.lotStatus}
                        </div>
                      </td>
                      <td className="p-2 text-xs">
                        {leadDaysText(row.leadDaysSource, row.leadDays)}
                      </td>
                      <td className="p-2 text-right">{qty(row.onHand)}</td>
                      <td className="p-2 text-right">{qty(row.available)}</td>
                      <td className="p-2 text-right">{row.warehouseCount} 仓</td>
                      <td className="p-2 text-right">
                        {writable ? (
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => void openEditor(row.skuId)}
                          >
                            策略
                          </Button>
                        ) : null}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {alerts && alerts.total > alerts.size ? (
          <div className="mt-4 flex items-center justify-end gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={alerts.page <= 0}
              onClick={() => setPageNumber((p) => Math.max(0, p - 1))}
            >
              上一页
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={alerts.page + 1 >= totalPages}
              onClick={() => setPageNumber((p) => p + 1)}
            >
              下一页
            </Button>
          </div>
        ) : null}
      </SectionCard>

      <SectionCard title="出库分配试算">
        <p className="text-muted-foreground mb-4 text-xs">
          这里只做<b>试算</b>：它按策略从库位明细排序算出推荐取用批次，不改动任何库存 ——
          真正的扣减由 8 拣货的业务单据负责。
        </p>
        <div className="grid gap-3 md:grid-cols-4">
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">SKU</span>
            <select
              className="border-input w-full rounded-md border px-3 py-2 text-sm"
              value={allocSku}
              onChange={(e) => {
                setAllocSku(e.target.value);
                setPlan(null);
              }}
            >
              <option value="">请选择</option>
              {skus.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.code} {s.name}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">出库仓库</span>
            <select
              className="border-input w-full rounded-md border px-3 py-2 text-sm"
              value={allocWarehouse}
              onChange={(e) => {
                setAllocWarehouse(e.target.value);
                setPlan(null);
              }}
            >
              <option value="">请选择</option>
              {warehouses.map((w) => (
                <option key={w.warehouseId} value={w.warehouseId}>
                  {w.code} {w.name}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">出库数量</span>
            <input
              className="border-input w-full rounded-md border px-3 py-2 text-sm"
              value={allocQty}
              placeholder="例如 100"
              onChange={(e) => setAllocQty(e.target.value)}
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">策略</span>
            <select
              className="border-input w-full rounded-md border px-3 py-2 text-sm"
              value={allocStrategy}
              onChange={(e) => setAllocStrategy(e.target.value)}
            >
              <option value="">用该 SKU 的策略</option>
              {expiryStrategyOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        </div>
        <div className="mt-4 flex items-center gap-3">
          <Button onClick={() => void runAllocation()} disabled={planLoading}>
            <Truck className="mr-2 h-3.5 w-3.5" />
            {planLoading ? '计算中…' : '计算推荐批次'}
          </Button>
        </div>
        {planError ? (
          <div className="border-destructive/40 bg-destructive-soft text-destructive mt-4 flex items-start gap-3 rounded-md border p-3 text-sm">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <p>{planError}</p>
          </div>
        ) : null}
        {plan ? (
          <div className="mt-4 space-y-3">
            <div className="text-sm">
              <b>
                {plan.strategyLabel}（{expiryStrategyLabels[plan.strategy] ?? plan.strategyLabel}）
              </b>{' '}
              · 来源：{plan.strategySource} ·{' '}
              {plan.policyEnabled ? '策略已启用' : '策略未启用，出库退回按库位顺序'}
            </div>
            <div className="grid gap-3 sm:grid-cols-4">
              <StatCard label="需求" value={qty(plan.requestedQuantity)} />
              <StatCard label="可分配" value={qty(plan.allocatedQuantity)} />
              <StatCard label="缺口" value={qty(plan.shortfallQuantity)} />
              <StatCard
                label="该 SKU 在该仓可用"
                value={qty(plan.skuAvailableInWarehouse)}
                comparisonLabel={
                  plan.earliestExpiry ? `最早失效 ${plan.earliestExpiry}` : '无可分配批次'
                }
              />
            </div>
            {!plan.policyEnabled ? (
              <p className="text-muted-foreground text-xs">
                {policyEnabledText({ configured: plan.policyEnabled, enabled: false })}
                ：维度已保留但第一期可不开。
              </p>
            ) : null}
            {plan.lines.length === 0 ? (
              <EmptyState
                icon={<Truck className="h-6 w-6" />}
                title="没有可分配的批次"
                description="该 SKU 在所选仓没有可售可拣的可用量，缺口来源见下方说明。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="p-2">取用顺序</th>
                      <th className="p-2">批号</th>
                      <th className="p-2">有效期</th>
                      <th className="p-2">排序依据</th>
                      <th className="p-2 text-right">取用量</th>
                      <th className="p-2">库位</th>
                    </tr>
                  </thead>
                  <tbody>
                    {plan.lines.map((line, index) => (
                      <tr key={line.lotId ?? `nolot-${index}`} className="border-b last:border-0">
                        <td className="p-2">{index + 1}</td>
                        <td className="p-2">{line.lotCode ?? '（无批次）'}</td>
                        <td className="p-2">{expiryText(line.expiresOn, line.daysToExpiry)}</td>
                        <td className="p-2 text-xs">
                          {line.sortBasis}
                          {line.sortBasisValue ? ` · ${line.sortBasisValue}` : ''}
                        </td>
                        <td className="p-2 text-right">{qty(line.quantity)}</td>
                        <td className="p-2 text-xs">
                          {line.buckets
                            .map((b) => `${b.locationCode} ${qty(b.quantity)}`)
                            .join(' · ')}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {plan.shortfallQuantity > 0 && reasonLines.length > 0 ? (
              <div className="border-warning/40 bg-warning-soft rounded-md border p-3 text-sm">
                <p className="font-medium">
                  缺口 {qty(plan.shortfallQuantity)} 的来源（各处置动作不同，请分别派工）：
                </p>
                <ul className="mt-2 list-disc pl-5">
                  {reasonLines.map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                </ul>
              </div>
            ) : null}
            {plan.notes.length > 0 ? (
              <ul className="text-muted-foreground list-disc pl-5 text-xs">
                {plan.notes.map((n) => (
                  <li key={n}>{n}</li>
                ))}
              </ul>
            ) : null}
            {allocationComplete(plan) ? (
              <p className="text-muted-foreground text-xs">可全额出库。</p>
            ) : null}
          </div>
        ) : null}
      </SectionCard>

      <SectionCard
        title="出库分配策略"
        extra={
          policies && policies.total > 0 ? (
            <span className="text-muted-foreground text-sm">共 {policies.total} 条</span>
          ) : null
        }
      >
        <p className="text-muted-foreground mb-4 text-xs">
          策略主体是 <b>SKU</b> 而非批次：同一箱奶的每一批都用同一条策略。 没配过的 SKU
          走兜底默认（FEFO、提前 30 天、开关关闭），不会报错。
        </p>
        {loading && !policies ? (
          <p className="text-muted-foreground py-8 text-center text-sm">正在加载策略…</p>
        ) : !policies || policies.rows.length === 0 ? (
          <EmptyState
            icon={<CalendarClock className="h-6 w-6" />}
            title="还没有配置分配策略"
            description={`本租户有 ${policies?.summary.skuWithExpiryLotCount ?? 0} 个 SKU 带效期批次但都还没配策略。效期/FIFO 是食品化妆品类目的硬需求，可以逐个 SKU 开启。`}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground border-b text-left">
                  <th className="p-2">SKU</th>
                  <th className="p-2">策略</th>
                  <th className="p-2">开关</th>
                  <th className="p-2 text-right">提前天数</th>
                  <th className="p-2 text-right">批次 / 临期 / 过期</th>
                  <th className="p-2 text-right">在手</th>
                  <th className="p-2"></th>
                </tr>
              </thead>
              <tbody>
                {policies.rows.map((row) => (
                  <tr key={row.policyId} className="border-b last:border-0">
                    <td className="p-2">
                      <div>{row.skuCode}</div>
                      <div className="text-muted-foreground text-xs">{row.skuName}</div>
                    </td>
                    <td className="p-2">
                      {expiryStrategyLabels[row.strategy] ?? row.strategy}
                      {row.specifiedLotCode ? (
                        <div className="text-muted-foreground text-xs">
                          指定 {row.specifiedLotCode}
                        </div>
                      ) : null}
                    </td>
                    <td className="p-2">
                      {row.enabled ? '已启用' : '已停用'}
                      <div className="text-muted-foreground text-xs">
                        {row.enabled ? '按策略排序出库' : '退回按库位顺序'}
                      </div>
                    </td>
                    <td className="p-2 text-right">{row.leadDays}</td>
                    <td className="p-2 text-right">
                      {row.lotCount} / {row.expiringLotCount} / {row.expiredLotCount}
                    </td>
                    <td className="p-2 text-right">{qty(row.onHand)}</td>
                    <td className="p-2 text-right">
                      {writable ? (
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => void openEditor(row.skuId)}
                        >
                          编辑
                        </Button>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {policies ? (
          <p className="text-muted-foreground mt-3 text-xs">
            已启用 {policies.summary.enabledCount} · 停用 {policies.summary.disabledCount} · FEFO{' '}
            {policies.summary.fefoCount} · FIFO {policies.summary.fifoCount} · 指定批次{' '}
            {policies.summary.specifiedLotCount}
          </p>
        ) : null}
      </SectionCard>

      <Modal open={editOpen} onOpenChange={(open) => !open && setEditOpen(false)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>出库分配策略</ModalTitle>
            <ModalDescription>
              {editCurrent
                ? `${editCurrent.skuCode} ${editCurrent.skuName}${
                    editCurrent.configured ? '' : '（尚未配置，下面是兜底默认值）'
                  }`
                : editSku}
            </ModalDescription>
          </ModalHeader>

          {saveError ? (
            <div className="border-destructive/40 bg-destructive-soft text-destructive rounded-md border p-3 text-sm">
              {saveError}
            </div>
          ) : null}

          <div className="space-y-4">
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground">策略</span>
              <select
                className="border-input w-full rounded-md border px-3 py-2 text-sm"
                value={editStrategy}
                onChange={(e) => setEditStrategy(e.target.value)}
              >
                {expiryStrategyOptions.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
            <p className="text-muted-foreground text-xs">
              {editStrategy === 'FEFO'
                ? '按失效日期升序：先到期先出。没有失效日期的批次不参与排序（它们不是「最晚到期」，而是「不适用」）。'
                : editStrategy === 'FIFO'
                  ? '按收货日期升序：先进先出，与效期无关。已过期批次在任何策略下都不会被分配。'
                  : '永远从指定批次取，用于「这箱货要单独走」的场景。指定批次必须属于该 SKU。'}
            </p>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground">预警提前天数（0 ~ 3650）</span>
              <input
                className="border-input w-full rounded-md border px-3 py-2 text-sm"
                value={editLeadDays}
                onChange={(e) => setEditLeadDays(e.target.value)}
              />
            </label>
            {editStrategy === 'SPECIFIED_LOT' ? (
              <label className="space-y-1 text-sm">
                <span className="text-muted-foreground">指定批次</span>
                <select
                  className="border-input w-full rounded-md border px-3 py-2 text-sm"
                  value={editLotId}
                  onChange={(e) => setEditLotId(e.target.value)}
                >
                  <option value="">请选择</option>
                  {editLots.map((l) => (
                    <option key={l.lotId} value={l.lotId}>
                      {l.lotCode}
                      {l.expiresOn ? ` · ${expiryText(l.expiresOn, null)}` : ' · 非效期品'}
                    </option>
                  ))}
                </select>
              </label>
            ) : null}
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={editEnabled}
                onChange={(e) => setEditEnabled(e.target.checked)}
              />
              <span>启用该策略（关闭时出库退回按库位顺序，不做效期排序）</span>
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground">备注</span>
              <input
                className="border-input w-full rounded-md border px-3 py-2 text-sm"
                value={editNotes}
                placeholder="为什么这个 SKU 走这个策略"
                onChange={(e) => setEditNotes(e.target.value)}
              />
            </label>
            <p className="text-muted-foreground text-xs">
              策略没有删除入口：停用请关掉上面的开关。策略行是「这个 SKU 曾经按 FEFO
              出过货」的凭据。
            </p>
          </div>

          <ModalFooter>
            <Button variant="outline" onClick={() => setEditOpen(false)}>
              取消
            </Button>
            <Button onClick={() => void submitPolicy()} disabled={saving}>
              <Save className="mr-2 h-3.5 w-3.5" />
              {saving ? '保存中…' : '保存'}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
