'use client';

import { Fragment, useCallback, useEffect, useState } from 'react';
import { AlertTriangle, Boxes, Plus, RefreshCw, TriangleAlert } from 'lucide-react';
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
  LotDistribution,
  LotMovements,
} from '../../../../../packages/api-contracts/src/index';
import type { LotArchivePage } from '../../../../../packages/api-contracts/src/index';
import {
  createLot,
  expiryText,
  fetchLotArchive,
  fetchLotDistribution,
  fetchLotMovements,
  lotOriginLabels,
  lotSortOptions,
  lotStatusLabels,
  sourceText,
  type LotParams,
} from './inventory-lot-model';
import { errorText, fetchSkus, fetchWarehouses, qty } from './inventory-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.3.1.1 批次管理工作区。
 *
 * <p>蓝图：「管理批次（lot）：批号、生产日期、失效日期、来源采购单/供应商，
 * 支持按批次查询库存与流向追溯」，输出「批次档案 + 批次库存分布 + 出入库历史」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li>**过期与状态分开显示**。`expired` 是日历事实，`status` 是运营维护的判断；
 *       一个 `ACTIVE + 已过期` 的批次恰恰是要处理的待办，合并成一个字段就看不见了。
 *   <li>**数量分在手 / 预占 / 可用三列**，且合计行标明「不受分页影响」——
 *       否则翻页时合计变小会被当成丢数据。
 *   <li>**出入库历史必须显示归属口径**（`attributionNote`）。流水按余额行记账、
 *       余额行不含批次维度，不说清就会把同余额行上别的批次的量算到这一批头上。
 *   <li>**「刚建未收货」的批次要在列表里**，数量显示 0，而不是消失。
 * </ul>
 */
export function InventoryLotWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [page, setPage] = useState<LotArchivePage | null>(null);
  const [warehouses, setWarehouses] = useState<{ id: string; code: string; name: string }[]>([]);
  const [skus, setSkus] = useState<{ id: string; code: string; name: string }[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState<string | null>(null);
  const [distribution, setDistribution] = useState<LotDistribution | null>(null);
  const [movements, setMovements] = useState<LotMovements | null>(null);
  const [detailError, setDetailError] = useState('');
  const [detailLoading, setDetailLoading] = useState(false);

  const [keyword, setKeyword] = useState('');
  const [warehouseId, setWarehouseId] = useState('');
  const [skuId, setSkuId] = useState('');
  const [status, setStatus] = useState('');
  const [origin, setOrigin] = useState('');
  const [sourceSystem, setSourceSystem] = useState('');
  const [expiringWithinDays, setExpiringWithinDays] = useState('');
  const [inStock, setInStock] = useState(false);
  const [sort, setSort] = useState('LOT_CODE');
  const [pageNumber, setPageNumber] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    const params: LotParams = { sort, page: pageNumber, size: 50 };
    if (keyword.trim()) params.keyword = keyword.trim();
    if (warehouseId) params.warehouseId = warehouseId;
    if (skuId) params.skuId = skuId;
    if (status) params.status = [status];
    if (origin) params.origin = [origin];
    if (sourceSystem) params.sourceSystem = [sourceSystem];
    if (expiringWithinDays.trim()) params.expiringWithinDays = Number(expiringWithinDays.trim());
    if (inStock) params.inStock = true;
    try {
      setPage(await fetchLotArchive(params));
    } catch (e) {
      setError(errorText(e));
      setPage(null);
    } finally {
      setLoading(false);
    }
  }, [
    readable,
    keyword,
    warehouseId,
    skuId,
    status,
    origin,
    sourceSystem,
    expiringWithinDays,
    inStock,
    sort,
    pageNumber,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!readable) return;
    void (async () => {
      try {
        const [wh, sk] = await Promise.all([fetchWarehouses(), fetchSkus()]);
        setWarehouses(wh);
        setSkus(sk);
      } catch (e) {
        setError(errorText(e));
      }
    })();
  }, [readable]);

  async function toggleDetail(lotId: string) {
    setDetailError('');
    if (expanded === lotId) {
      setExpanded(null);
      setDistribution(null);
      setMovements(null);
      return;
    }
    setExpanded(lotId);
    setDistribution(null);
    setMovements(null);
    setDetailLoading(true);
    try {
      // 分布与流水互不依赖，并发拉；任一失败都如实报出来，不拿另一半的成功掩盖它。
      const [d, m] = await Promise.all([fetchLotDistribution(lotId), fetchLotMovements(lotId)]);
      setDistribution(d);
      setMovements(m);
    } catch (e) {
      setDetailError(errorText(e));
      setDistribution(null);
      setMovements(null);
      setExpanded(null);
    } finally {
      setDetailLoading(false);
    }
  }

  if (!readable) {
    return (
      <EmptyState
        icon={<Boxes className="h-6 w-6" />}
        title="没有批次管理的查看权限"
        description="当前角色未被授予 warehouse.inventory.read，请联系管理员在角色中开启该页面。"
      />
    );
  }

  const summary = page?.summary ?? null;
  const totalPages = page ? Math.max(1, Math.ceil(page.total / page.size)) : 1;

  return (
    <div className="space-y-6">
      <PageHeader
        title="批次管理"
        description="管理批次的批号、生产与失效日期、来源采购单与供应商，按批次查询库存分布与出入库流向。"
        actions={
          <div className="flex items-center gap-2">
            {writable ? (
              <Button size="sm" onClick={() => setCreateOpen(true)}>
                <Plus className="mr-2 h-3.5 w-3.5" />
                新建批次
              </Button>
            ) : null}
            <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
              <RefreshCw
                className={loading ? 'mr-2 h-3.5 w-3.5 animate-spin' : 'mr-2 h-3.5 w-3.5'}
              />
              刷新
            </Button>
          </div>
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
            label="批次数"
            value={String(summary.lotCount)}
            comparisonLabel={`${summary.skuCount} 个 SKU · ${summary.warehouseCount} 个仓库（合计不受分页影响）`}
          />
          <StatCard
            label="在手 / 可用"
            value={`${qty(summary.onHand)} / ${qty(summary.available)}`}
            comparisonLabel={`预占 ${qty(summary.reserved)}`}
          />
          <StatCard
            label="已过期 / 已用尽"
            value={`${summary.expiredLotCount} / ${summary.depletedLotCount}`}
            comparisonLabel="过期按失效日期判定，与批次状态分开统计"
          />
          <StatCard
            label="还有效期"
            value={String(summary.expiringLotCount)}
            comparisonLabel={
              summary.earliestExpiry
                ? `最早失效 ${summary.earliestExpiry}`
                : '命中批次里没有带失效日期的'
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
              placeholder="批号 / SKU / 来源单号 / 供应商"
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
                <option key={w.id} value={w.id}>
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
            <span className="text-muted-foreground">批次状态</span>
            <select
              className="border-input w-full rounded-md border px-3 py-2 text-sm"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPageNumber(0);
              }}
            >
              <option value="">全部</option>
              {(page?.statusOptions ?? []).map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}（{o.lotCount}）
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">建立方式</span>
            <select
              className="border-input w-full rounded-md border px-3 py-2 text-sm"
              value={origin}
              onChange={(e) => {
                setOrigin(e.target.value);
                setPageNumber(0);
              }}
            >
              <option value="">全部</option>
              {(page?.originOptions ?? []).map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}（{o.lotCount}）
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">来源系统</span>
            <select
              className="border-input w-full rounded-md border px-3 py-2 text-sm"
              value={sourceSystem}
              onChange={(e) => {
                setSourceSystem(e.target.value);
                setPageNumber(0);
              }}
            >
              <option value="">全部</option>
              {(page?.sourceSystemOptions ?? []).map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}（{o.lotCount}）
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">N 天内到期</span>
            <input
              className="border-input w-full rounded-md border px-3 py-2 text-sm"
              type="number"
              min={0}
              value={expiringWithinDays}
              placeholder="留空 = 不按效期筛"
              onChange={(e) => {
                setExpiringWithinDays(e.target.value);
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
              {lotSortOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex items-end gap-2 text-sm">
            <input
              type="checkbox"
              checked={inStock}
              onChange={(e) => {
                setInStock(e.target.checked);
                setPageNumber(0);
              }}
            />
            <span>只看在手大于 0 的批次</span>
          </label>
        </div>
      </SectionCard>

      <SectionCard
        title="批次档案"
        extra={
          page && page.total > 0 ? (
            <span className="text-muted-foreground text-sm">
              共 {page.total} 个批次 · 第 {page.page + 1} / {totalPages} 页
            </span>
          ) : null
        }
      >
        {loading && !page ? (
          <p className="text-muted-foreground py-8 text-center text-sm">正在加载批次档案…</p>
        ) : !page || page.rows.length === 0 ? (
          <EmptyState
            icon={<Boxes className="h-6 w-6" />}
            title="没有命中的批次"
            description={
              keyword.trim() || warehouseId || skuId || status || origin || sourceSystem
                ? '试着放宽筛选条件；刚建立但还没收货的批次也会出现在这里（数量为 0）。'
                : '这个租户还没有批次档案。批次由收货流程生成或手工建立，收货时建立的批次会带上来源采购单与供应商。'
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground border-b text-left">
                  <th className="p-2">批号</th>
                  <th className="p-2">商品</th>
                  <th className="p-2">有效期</th>
                  <th className="p-2">来源</th>
                  <th className="p-2 text-right">在手</th>
                  <th className="p-2 text-right">预占</th>
                  <th className="p-2 text-right">可用</th>
                  <th className="p-2 text-right">分布</th>
                  <th className="p-2">状态</th>
                </tr>
              </thead>
              <tbody>
                {page.rows.map((row) => (
                  <Fragment key={row.lotId}>
                    <tr className="border-b last:border-0">
                      <td className="p-2">
                        <button
                          type="button"
                          className="text-primary hover:underline"
                          onClick={() => void toggleDetail(row.lotId)}
                        >
                          {row.lotCode}
                        </button>
                        <div className="text-muted-foreground text-xs">
                          {lotOriginLabels[row.origin] ?? row.origin}
                          {row.receivedOn ? ` · 收货 ${row.receivedOn}` : ''}
                        </div>
                      </td>
                      <td className="p-2">
                        {row.skuCode}
                        <div className="text-muted-foreground text-xs">{row.skuName}</div>
                      </td>
                      <td className="p-2">
                        {expiryText(row.expiresOn, row.daysToExpiry)}
                        {row.producedOn ? (
                          <div className="text-muted-foreground text-xs">生产 {row.producedOn}</div>
                        ) : null}
                      </td>
                      <td className="p-2 text-xs">{sourceText(row)}</td>
                      <td className="p-2 text-right tabular-nums">{qty(row.onHand)}</td>
                      <td className="p-2 text-right tabular-nums">{qty(row.reserved)}</td>
                      <td className="p-2 text-right tabular-nums">{qty(row.available)}</td>
                      <td className="p-2 text-right tabular-nums">
                        {row.warehouseCount > 0 ? (
                          <>
                            {row.warehouseCount} 仓 / {row.locationCount} 库位
                            <div className="text-muted-foreground text-xs">
                              {row.bucketCount} 条货位明细
                            </div>
                          </>
                        ) : (
                          <span className="text-muted-foreground">尚无库存</span>
                        )}
                      </td>
                      <td className="p-2">
                        <span
                          className={
                            row.expired
                              ? 'text-destructive inline-flex items-center gap-1'
                              : 'text-muted-foreground'
                          }
                        >
                          {row.expired ? <TriangleAlert className="h-3 w-3" /> : null}
                          {lotStatusLabels[row.status] ?? row.status}
                        </span>
                        {row.expired ? (
                          <div className="text-muted-foreground text-xs">
                            日期已过期，状态未更新
                          </div>
                        ) : null}
                      </td>
                    </tr>
                    {expanded === row.lotId && (
                      <tr>
                        <td colSpan={9} className="bg-muted/40 p-3">
                          {detailLoading ? (
                            <p className="text-muted-foreground text-sm">正在加载批次分布与流水…</p>
                          ) : distribution && movements ? (
                            <LotDetail distribution={distribution} movements={movements} />
                          ) : (
                            <p className="text-muted-foreground text-sm">暂无数据。</p>
                          )}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {detailError && (
          <p className="text-destructive mt-3 flex items-start gap-2 text-sm">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            {detailError}
          </p>
        )}

        {page && page.total > page.size && (
          <div className="mt-4 flex items-center justify-between">
            <Button
              variant="outline"
              size="sm"
              disabled={page.page === 0}
              onClick={() => setPageNumber(Math.max(0, pageNumber - 1))}
            >
              上一页
            </Button>
            <span className="text-muted-foreground text-sm">
              第 {page.page + 1} / {totalPages} 页
            </span>
            <Button
              variant="outline"
              size="sm"
              disabled={page.page + 1 >= totalPages}
              onClick={() => setPageNumber(pageNumber + 1)}
            >
              下一页
            </Button>
          </div>
        )}
      </SectionCard>

      {!writable && (
        <p className="text-muted-foreground text-xs">
          当前角色只有查看权限（warehouse.inventory.read）。建立与修改批次需要
          warehouse.inventory.update。
        </p>
      )}

      {createOpen ? (
        <CreateLotDialog
          skus={skus}
          onClose={() => setCreateOpen(false)}
          onCreated={async (lotId) => {
            setCreateOpen(false);
            setPageNumber(0);
            await load();
            // 建完直接把新批次展开：刚建的批次数量为 0，不展开的话看着像什么都没发生
            await toggleDetail(lotId);
          }}
        />
      ) : null}
    </div>
  );
}

/**
 * 新建批次对话框。
 *
 * <p>收货生成与手工建立共用这一条：建立方式显式选，不从"填了来源单据没有"反推 ——
 * 手工补登历史批次也会填来源单据，那种情况下"它是收货生成的还是人建的"是两条不同的信息。
 *
 * <p>选了「收货生成」就必须填来源系统与来源单据 id（后端与 DB 约束都会挡），前端也提前拦一道，
 * 省得填完提交才报错。来源单据 id 是**跨服务定位键**：采购/收货不在本服务，
 * 所以这里只存 id 与单号，不联查对方表。
 */
function CreateLotDialog({
  skus,
  onClose,
  onCreated,
}: {
  skus: { id: string; code: string; name: string }[];
  onClose: () => void;
  onCreated: (lotId: string) => void | Promise<void>;
}) {
  const [skuId, setSkuId] = useState('');
  const [lotCode, setLotCode] = useState('');
  const [producedOn, setProducedOn] = useState('');
  const [expiresOn, setExpiresOn] = useState('');
  const [origin, setOrigin] = useState('MANUAL');
  const [sourceSystem, setSourceSystem] = useState('PURCHASE');
  const [sourceRefId, setSourceRefId] = useState('');
  const [sourceRefCode, setSourceRefCode] = useState('');
  const [supplierName, setSupplierName] = useState('');
  const [notes, setNotes] = useState('');
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');

  async function submit() {
    setFormError('');
    if (!skuId) return setFormError('请选择批次所属的 SKU');
    if (!lotCode.trim()) return setFormError('请填写批号');
    if (origin === 'RECEIPT' && !sourceRefId.trim())
      return setFormError('收货生成的批次必须填写来源单据 id');
    if (producedOn && expiresOn && expiresOn < producedOn)
      return setFormError('失效日期不能早于生产日期');
    setSaving(true);
    try {
      const saved = await createLot({
        skuId,
        lotCode: lotCode.trim(),
        producedOn: producedOn || null,
        expiresOn: expiresOn || null,
        receivedOn: null,
        origin,
        sourceSystem: sourceSystem || null,
        sourceRefId: sourceRefId.trim() || null,
        sourceRefCode: sourceRefCode.trim() || '',
        supplierRefId: null,
        supplierName: supplierName.trim() || '',
        notes: notes.trim() || '',
      });
      await onCreated(saved.lotId);
    } catch (e) {
      setFormError(errorText(e));
    } finally {
      setSaving(false);
    }
  }

  const field = 'border-input w-full rounded-md border px-3 py-2 text-sm';

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>新建批次</ModalTitle>
          <ModalDescription>
            建立批次档案。收货流程也可调用同一接口生成批次（建立方式选「收货生成」并带上来源单据）。
          </ModalDescription>
        </ModalHeader>

        {formError ? (
          <p className="text-destructive flex items-start gap-2 text-sm">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            {formError}
          </p>
        ) : null}

        <div className="grid gap-3">
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">所属 SKU</span>
            <select className={field} value={skuId} onChange={(e) => setSkuId(e.target.value)}>
              <option value="">请选择</option>
              {skus.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.code} {s.name}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">批号</span>
            <input
              className={field}
              value={lotCode}
              onChange={(e) => setLotCode(e.target.value)}
              placeholder="同 SKU 下唯一"
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">建立方式</span>
            <select className={field} value={origin} onChange={(e) => setOrigin(e.target.value)}>
              <option value="MANUAL">手工建立</option>
              <option value="RECEIPT">收货生成</option>
            </select>
          </label>
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground">生产日期</span>
              <input
                className={field}
                type="date"
                value={producedOn}
                onChange={(e) => setProducedOn(e.target.value)}
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground">失效日期</span>
              <input
                className={field}
                type="date"
                value={expiresOn}
                onChange={(e) => setExpiresOn(e.target.value)}
              />
            </label>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground">来源系统</span>
              <select
                className={field}
                value={sourceSystem}
                onChange={(e) => setSourceSystem(e.target.value)}
              >
                <option value="PURCHASE">采购单</option>
                <option value="RECEIPT">收货单</option>
                <option value="MANUAL">手工补录</option>
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground">来源单据 id</span>
              <input
                className={field}
                value={sourceRefId}
                onChange={(e) => setSourceRefId(e.target.value)}
                placeholder="采购/收货服务的单据 UUID"
              />
            </label>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground">来源单号</span>
              <input
                className={field}
                value={sourceRefCode}
                onChange={(e) => setSourceRefCode(e.target.value)}
                placeholder="固化展示，不必联查采购服务"
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground">供应商</span>
              <input
                className={field}
                value={supplierName}
                onChange={(e) => setSupplierName(e.target.value)}
              />
            </label>
          </div>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">备注</span>
            <textarea
              className={field}
              rows={2}
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="质检结论、退运原因等"
            />
          </label>
        </div>

        <ModalFooter>
          <Button variant="outline" onClick={onClose} disabled={saving}>
            取消
          </Button>
          <Button onClick={() => void submit()} disabled={saving}>
            {saving ? '提交中…' : '建立批次'}
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

/** 展开行：批次库存分布 + 出入库历史。 */
function LotDetail({
  distribution,
  movements,
}: {
  distribution: LotDistribution;
  movements: LotMovements;
}) {
  return (
    <div className="space-y-4">
      <div className="text-muted-foreground text-xs">
        合计在手 {qty(distribution.totalOnHand)} · 预占 {qty(distribution.totalReserved)} · 可用{' '}
        {qty(distribution.totalAvailable)}
      </div>

      <div>
        <h4 className="mb-2 text-sm font-medium">库存分布</h4>
        {distribution.warehouses.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            这个批次还没有落到任何库位 —— 已建立但尚未收货。
          </p>
        ) : (
          <>
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground border-b text-left">
                  <th className="p-1">库位</th>
                  <th className="p-1">质量状态</th>
                  <th className="p-1 text-right">在手</th>
                  <th className="p-1 text-right">预占</th>
                  <th className="p-1 text-right">可用</th>
                </tr>
              </thead>
              <tbody>
                {distribution.locations.map((slice) => (
                  <tr key={slice.bucketId} className="border-b last:border-0">
                    <td className="p-1">
                      {slice.locationCode}
                      <span className="text-muted-foreground ml-1 text-xs">
                        {slice.locationName}
                      </span>
                      {slice.locationPickable ? null : (
                        <span className="text-muted-foreground ml-1 text-xs">不可拣</span>
                      )}
                    </td>
                    <td className="p-1">
                      {slice.qualityStatusName}
                      <span className="text-muted-foreground ml-1 text-xs">
                        {slice.qualityStatusCode}
                      </span>
                    </td>
                    <td className="p-1 text-right tabular-nums">{qty(slice.onHand)}</td>
                    <td className="p-1 text-right tabular-nums">{qty(slice.reserved)}</td>
                    <td className="p-1 text-right tabular-nums">{qty(slice.available)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-muted-foreground mt-2 text-xs">
              仓库级合计：
              {distribution.warehouses
                .map((w) => `${w.warehouseCode} ${qty(w.onHand)}`)
                .join(' · ')}
            </p>
          </>
        )}
      </div>

      <div>
        <h4 className="mb-2 text-sm font-medium">出入库历史</h4>
        {movements.movements.rows.length === 0 ? (
          <p className="text-muted-foreground text-sm">这个批次所在库位上还没有发生过库存业务。</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-muted-foreground border-b text-left">
                <th className="p-1">发生时间</th>
                <th className="p-1">业务</th>
                <th className="p-1">来源单据</th>
                <th className="p-1 text-right">在手变动</th>
                <th className="p-1 text-right">预占变动</th>
              </tr>
            </thead>
            <tbody>
              {movements.movements.rows.map((row) => (
                <tr key={row.transactionId} className="border-b last:border-0">
                  <td className="p-1 tabular-nums">
                    {row.occurredAt.slice(0, 19).replace('T', ' ')}
                  </td>
                  <td className="p-1">{row.kind}</td>
                  <td className="p-1 text-xs">
                    {row.refType}
                    {row.notes ? ` · ${row.notes}` : ''}
                  </td>
                  <td
                    className={`p-1 text-right tabular-nums ${
                      row.onHandDelta > 0
                        ? 'text-emerald-600'
                        : row.onHandDelta < 0
                          ? 'text-destructive'
                          : 'text-muted-foreground'
                    }`}
                  >
                    {row.onHandDelta > 0 ? '+' : ''}
                    {qty(row.onHandDelta)}
                  </td>
                  <td className="p-1 text-right tabular-nums">
                    {row.reservedDelta > 0 ? '+' : ''}
                    {qty(row.reservedDelta)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="text-muted-foreground mt-2 text-xs">归属口径：{movements.attributionNote}</p>
      </div>
    </div>
  );
}
