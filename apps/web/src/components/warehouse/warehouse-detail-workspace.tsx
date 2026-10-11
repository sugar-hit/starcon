'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ArrowLeft,
  Boxes,
  Building2,
  ClipboardList,
  MapPin,
  MapPinned,
  RefreshCw,
  Users,
} from 'lucide-react';
import { Button, EmptyState, FormField, Input, Notice, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  DetailEntryPoint,
  LocationPage,
  WarehouseDetail,
} from '../../../../../packages/api-contracts/src/index';
import { useIdentity } from '../iam/identity-shell';
import {
  ALL,
  addressText,
  draftOf,
  errorText,
  fetchWarehouseDetail,
  fetchWarehouseEntrypoints,
  fetchWarehouseLocations,
  isArchived,
  kindLabel,
  laborText,
  localTimeText,
  locationFillText,
  momentText,
  pickableText,
  qualityRoleLabel,
  qty,
  splitEntrypoints,
  statusLabel,
  stockSourceText,
  updateProblem,
  updateWarehouse,
  type LocationParams,
  type UpdateWarehouseDraft,
} from './warehouse-detail-model';

const PAGE_SIZE = 20;

/**
 * 8.1.1.2 仓库详情工作区 —— 仓库的控制塔。
 *
 * <p>蓝图：「单仓库全景：基础信息与地址、库位结构、库存分布、作业统计、关联的自发货/中转/
 * FBA 备货策略、作业人员与设备。」「详情页是仓库的控制塔，作业入口与库存入口都在这里。」
 *
 * <p>界面纪律（每一条都对应一个会被误显示的坑）：
 *
 * <ul>
 *   <li>**平台仓观测值与自有账本分开显示**，不相加。控制塔上合成一个「总库存」最危险：
 *       它看起来像是账实相符的数字，而 V55 的两条触发器恰好就是为了防这个。
 *   <li>**未接入的维度显示成「未接入」并写明归属模块**，不是 0。库位树、上架任务、
 *       拣货出库、组装、备货策略、人员花名册、设备都没有数据表。
 *   <li>**入口按可用与否分组**，未接入的按钮禁用而不是跳到不存在的路由。
 *   <li>**地址缺失说缺哪几个**，不画成一行空白。
 *   <li>**归档是终态**：归档后编辑入口整个关掉，提交了再报错是更差的体验。
 *   <li>加载 / 空 / 错误三态分开渲染 —— 骨架屏与「没有数据」不能长得一样。
 * </ul>
 */
export function WarehouseDetailWorkspace({ warehouseId }: { warehouseId: string }) {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.facilities.read');
  const writable = session.permissions.includes('warehouse.facilities.update');

  const [detail, setDetail] = useState<WarehouseDetail | null>(null);
  const [entrypoints, setEntrypoints] = useState<DetailEntryPoint[]>([]);
  const [locations, setLocations] = useState<LocationPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [type, setType] = useState(ALL);
  const [status, setStatus] = useState(ALL);
  const [keyword, setKeyword] = useState('');
  const [sort, setSort] = useState('CODE');
  const [pageNo, setPageNo] = useState(0);

  const [showEdit, setShowEdit] = useState(false);
  const [draft, setDraft] = useState<UpdateWarehouseDraft | null>(null);
  const [saving, setSaving] = useState(false);
  const [draftError, setDraftError] = useState('');

  const locationQuery: LocationParams = useMemo(
    () => ({ type, status, keyword, sort, page: pageNo, size: PAGE_SIZE }),
    [type, status, keyword, sort, pageNo],
  );

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [data, points, page] = await Promise.all([
        fetchWarehouseDetail(warehouseId),
        fetchWarehouseEntrypoints(warehouseId),
        fetchWarehouseLocations(warehouseId, locationQuery),
      ]);
      setDetail(data);
      setEntrypoints(points);
      setLocations(page);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [warehouseId, locationQuery, readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const startEdit = () => {
    if (!detail) return;
    setDraft(draftOf(detail));
    setDraftError('');
    setShowEdit(true);
  };

  const submit = async () => {
    if (!draft) return;
    const problem = updateProblem(draft);
    setDraftError(problem);
    if (problem) return;
    setSaving(true);
    try {
      const updated = await updateWarehouse(warehouseId, draft);
      setNotice(`已保存基础信息与地址（${statusLabel(updated.status)}）。`);
      setShowEdit(false);
      await load();
    } catch (e) {
      setDraftError(errorText(e));
    } finally {
      setSaving(false);
    }
  };

  const address = detail ? addressText(detail) : { value: '—', missing: '' };
  const stock = detail ? stockSourceText(detail) : { source: '—', note: '' };
  const grouped = splitEntrypoints(entrypoints);
  const rows = locations?.rows ?? [];
  const totalPages = locations ? Math.max(1, Math.ceil(locations.total / locations.size)) : 1;

  return (
    <>
      <PageHeader
        title={detail ? detail.base.name : '仓库详情'}
        description="单仓库全景：基础信息与地址、库位结构、库存分布、作业统计、备货策略与作业人员。"
        actions={
          <>
            <Button variant="secondary" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="mr-2 h-4 w-4" />
              刷新
            </Button>
            {writable && detail && !isArchived(detail.base.status) && (
              <Button onClick={() => (showEdit ? setShowEdit(false) : startEdit())}>
                维护基础信息与地址
              </Button>
            )}
          </>
        }
      />

      {error && (
        <Notice tone="danger" title="仓库详情加载失败">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="success" title="操作完成">
          {notice}
        </Notice>
      )}
      {!readable && (
        <Notice tone="warning" title="没有查看权限">
          当前角色未被授予仓库档案（warehouse.facilities.read）的权限。
        </Notice>
      )}

      {loading && !detail && (
        <p className="py-8 text-center text-copy-13 text-muted-foreground">加载中…</p>
      )}

      {detail && (
        <>
          <p className="mt-2 text-copy-12 text-muted-foreground">
            <Link href="/warehouse/facilities" className="inline-flex items-center hover:underline">
              <ArrowLeft className="mr-1 h-3.5 w-3.5" />
              返回仓库列表
            </Link>
            <span className="ml-3">
              {detail.base.code} · {kindLabel(detail.base.kind)} · {detail.base.countryCode} ·{' '}
              {statusLabel(detail.base.status)}
              {detail.base.defaultWarehouse ? ' · 默认发货仓' : ''}
            </span>
          </p>

          {/* 概况 */}
          <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard label="自有账本在手" value={qty(detail.stock.onHand)} polarity="neutral" icon={<Boxes />} />
            <StatCard
              label="库位占用"
              value={locationFillText(detail.locations.totalCount, detail.locations.occupiedCount)}
              polarity="neutral"
              icon={<MapPinned />}
            />
            <StatCard
              label="作业单合计"
              value={qty(detail.work.workOrderTotal)}
              unit={`未结 ${qty(detail.work.openWorkOrderCount)}`}
              icon={<ClipboardList />}
            />
            <StatCard
              label="作业任务"
              value={qty(detail.labor.taskTotal)}
              unit={`在岗 ${qty(detail.labor.distinctOperatorCount)} 人`}
              icon={<Users />}
            />
          </div>

          {/* 基础信息与地址 */}
          <SectionCard className="mt-4" title="基础信息与地址">
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              <Info label="仓库编码" value={detail.base.code} />
              <Info label="仓库类型" value={kindLabel(detail.base.kind)} />
              <Info label="启用状态" value={statusLabel(detail.base.status)} />
              <Info label="国家地区" value={detail.base.countryCode} />
              <Info label="当地时间" value={localTimeText(detail)} />
              <Info
                label="货权法人"
                value={
                  detail.base.legalEntityCode
                    ? `${detail.base.legalEntityCode} · ${detail.base.legalEntityName ?? ''}`.trim()
                    : detail.base.legalEntityCount > 0
                      ? `未绑定法人（已持有 ${detail.base.legalEntityCount} 个法人的货）`
                      : '未绑定法人'
                }
              />
            </div>
            <div className="mt-3 border-t pt-3">
              <Info label="地址" value={address.value} />
              {address.missing && (
                <p className="mt-1 text-copy-12 text-muted-foreground">
                  <MapPin className="mr-1 inline h-3.5 w-3.5" />
                  {address.missing}
                </p>
              )}
              {isArchived(detail.base.status) && (
                <p className="mt-1 text-copy-12 text-muted-foreground">
                  归档是终态：已归档的仓库不能再修改，也不能取消归档。
                </p>
              )}
            </div>
          </SectionCard>

          {/* 库存分布 */}
          <SectionCard className="mt-4" title="库存分布">
            <Notice tone="info" title={stock.source}>
              {stock.note}
            </Notice>
            <div className="mt-3 grid gap-3 sm:grid-cols-3">
              <Info label="在手" value={qty(detail.stock.onHand)} />
              <Info label="预占" value={qty(detail.stock.reserved)} />
              <Info label="可用" value={qty(detail.stock.available)} />
            </div>

            <h3 className="mt-4 text-copy-13 font-medium">按质量状态</h3>
            <table className="mt-2 w-full text-copy-13">
              <thead className="text-left text-muted-foreground">
                <tr className="border-b">
                  <th className="py-2 pr-3">质量状态</th>
                  <th className="py-2 pr-3">角色</th>
                  <th className="py-2 pr-3">在手</th>
                  <th className="py-2 pr-3">预占</th>
                  <th className="py-2 pr-3">可用</th>
                  <th className="py-2 pr-3">SKU</th>
                </tr>
              </thead>
              <tbody>
                {detail.stock.byQuality.map((s) => (
                  <tr key={s.qualityStatusId} className="border-b last:border-0">
                    <td className="py-2 pr-3">{s.name}</td>
                    <td className="py-2 pr-3">{qualityRoleLabel(s.role)}</td>
                    <td className="py-2 pr-3">{qty(s.onHand)}</td>
                    <td className="py-2 pr-3">{qty(s.reserved)}</td>
                    <td className="py-2 pr-3">{qty(s.available)}</td>
                    <td className="py-2 pr-3">{qty(s.skuCount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {detail.stock.byLegalEntity.length > 0 && (
              <>
                <h3 className="mt-4 text-copy-13 font-medium">按货权法人</h3>
                <ul className="mt-2 list-disc pl-4 text-copy-13">
                  {detail.stock.byLegalEntity.map((e) => (
                    <li key={e.legalEntityId}>
                      {e.code} · {e.name}：{qty(e.onHand)}（{qty(e.skuCount)} 个 SKU）
                    </li>
                  ))}
                </ul>
              </>
            )}
            {detail.stock.byLocationType.length > 0 && (
              <>
                <h3 className="mt-4 text-copy-13 font-medium">按库位类型</h3>
                <ul className="mt-2 list-disc pl-4 text-copy-13">
                  {detail.stock.byLocationType.map((t) => (
                    <li key={t.type}>
                      {t.typeLabel ?? t.type}：{qty(t.locationCount)} 个库位（可拣{' '}
                      {qty(t.pickableCount)}）· 在手 {qty(t.onHand)}
                    </li>
                  ))}
                </ul>
              </>
            )}
          </SectionCard>

          {/* 作业统计 */}
          <SectionCard className="mt-4" title="作业统计">
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <Info
                label="收货单"
                value={`${qty(detail.work.receiptTotal)} 张`}
                hint={`未结 ${qty(detail.work.receiptOpenCount)} · 已关 ${qty(detail.work.receiptClosedCount)}`}
              />
              <Info
                label="累计实收"
                value={qty(detail.work.receiptReceivedQuantity)}
                hint="按收货单明细的实收量"
              />
              <Info label="盘点任务" value={qty(detail.work.stocktakeTaskCount)} />
              <Info
                label="仓间调拨"
                value={`调入 ${qty(detail.work.transferInCount)} · 调出 ${qty(detail.work.transferOutCount)}`}
              />
            </div>
            <p className="mt-3 text-copy-12 text-muted-foreground">
              库存变动分录 {qty(detail.work.inventoryMovementCount)} 笔 · 作业单合计{' '}
              {qty(detail.work.workOrderTotal)}（未结 {qty(detail.work.openWorkOrderCount)}）。本合计只含本服务
              已落库的作业单据；上架、拣货出库与组装尚无单据，见下方入口清单。
            </p>
          </SectionCard>

          {/* 备货策略 */}
          <SectionCard className="mt-4" title="备货策略">
            <ul className="space-y-3">
              {detail.strategies.map((s) => (
                <li key={s.dimension}>
                  <div className="flex items-center gap-2">
                    <span className="text-copy-13 font-medium">{s.label}</span>
                    <span className="rounded border border-border px-1.5 py-0.5 text-copy-12 text-muted-foreground">
                      未接入 · 属 {s.ownerModule}
                    </span>
                  </div>
                  <p className="text-copy-12 text-muted-foreground">{s.note}</p>
                  <p className="text-copy-12 text-muted-foreground">
                    调入 {qty(s.facts.transferInCount)} · 调出 {qty(s.facts.transferOutCount)} · 在途腿{' '}
                    {qty(s.facts.inTransitLegCount)}
                    {s.facts.platformObservedOnHand !== null
                      ? ` · 平台观测在手 ${qty(s.facts.platformObservedOnHand)}`
                      : ''}
                  </p>
                  <p className="text-copy-12 text-muted-foreground">{s.facts.note}</p>
                </li>
              ))}
            </ul>
          </SectionCard>

          {/* 作业人员与设备 */}
          <SectionCard className="mt-4" title="作业人员与设备">
            <p className="text-copy-13">
              {laborText(
                detail.labor.taskTotal,
                detail.labor.unassignedTaskCount,
                detail.labor.runningTaskCount,
                detail.labor.completedTaskCount,
                detail.labor.exceptionTaskCount,
                detail.labor.distinctOperatorCount,
              )}
            </p>
            <p className="mt-1 text-copy-12 text-muted-foreground">{detail.labor.taskNote}</p>
            {detail.labor.byWorkType.length > 0 && (
              <ul className="mt-2 list-disc pl-4 text-copy-13">
                {detail.labor.byWorkType.map((w) => (
                  <li key={w.workType}>
                    {w.label}：{qty(w.taskCount)} 个任务（已完成 {qty(w.completedCount)}）
                  </li>
                ))}
              </ul>
            )}
            <div className="mt-3 space-y-1 border-t pt-3 text-copy-12 text-muted-foreground">
              <p>人员花名册与排班：未接入（属 {detail.labor.rosterOwnerModule}）。{detail.labor.rosterNote}</p>
              <p>仓库设备：未接入（属 {detail.labor.equipmentOwnerModule}）。{detail.labor.equipmentNote}</p>
            </div>
          </SectionCard>

          {/* 子模块入口 */}
          <SectionCard className="mt-4" title="子模块入口">
            <div className="flex flex-wrap gap-2">
              {grouped.available.map((p) => (
                <EntryLink key={p.key} point={p} />
              ))}
            </div>
            <h3 className="mt-4 text-copy-13 font-medium">以下子模块尚未交付</h3>
            <ul className="mt-1 list-disc pl-4 text-copy-12 text-muted-foreground">
              {grouped.unavailable.map((p) => (
                <li key={p.key}>
                  {p.label}（属 {p.module}）：{p.note}
                </li>
              ))}
            </ul>
          </SectionCard>

          {/* 维护 */}
          {showEdit && writable && draft && (
            <SectionCard className="mt-4" title="维护基础信息与地址">
              <Notice tone="info" title="这几项在这里改不了是有原因的">
                仓库编码是标识、仓库类型决定记账口径、货权法人是财务前提 —— 改它们会让已落库数据失去指向。
                拣货策略、复核比例、工作时段与班次属仓库配置（8.1.1.3），尚未交付。
              </Notice>
              <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                <FormField id="detail-name" label="仓库名称" required>
                  <Input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
                </FormField>
                <FormField id="detail-country" label="国家地区" required>
                  <Input
                    value={draft.countryCode}
                    maxLength={2}
                    onChange={(e) =>
                      setDraft({ ...draft, countryCode: e.target.value.toUpperCase() })
                    }
                  />
                </FormField>
                <FormField id="detail-timezone" label="时区" required hint="填 IANA ID，如 Asia/Shanghai">
                  <Input
                    value={draft.timezone}
                    onChange={(e) => setDraft({ ...draft, timezone: e.target.value })}
                  />
                </FormField>
                <FormField id="detail-line1" label="街道地址">
                  <Input
                    value={draft.addressLine1}
                    onChange={(e) => setDraft({ ...draft, addressLine1: e.target.value })}
                  />
                </FormField>
                <FormField id="detail-line2" label="街道地址（第二行）">
                  <Input
                    value={draft.addressLine2}
                    onChange={(e) => setDraft({ ...draft, addressLine2: e.target.value })}
                  />
                </FormField>
                <FormField id="detail-region" label="省 / 州">
                  <Input
                    value={draft.region}
                    onChange={(e) => setDraft({ ...draft, region: e.target.value })}
                  />
                </FormField>
                <FormField id="detail-city" label="城市">
                  <Input value={draft.city} onChange={(e) => setDraft({ ...draft, city: e.target.value })} />
                </FormField>
                <FormField id="detail-postal" label="邮编">
                  <Input
                    value={draft.postalCode}
                    onChange={(e) => setDraft({ ...draft, postalCode: e.target.value })}
                  />
                </FormField>
                <FormField id="detail-status" label="启用状态" hint="归档是终态，不可回退">
                  <select
                    id="detail-status"
                    className="h-9 w-full rounded-md border border-input bg-background px-3 text-copy-13"
                    value={draft.status}
                    onChange={(e) => setDraft({ ...draft, status: e.target.value })}
                  >
                    <option value="ACTIVE">启用中</option>
                    <option value="INACTIVE">已停用</option>
                    <option value="ARCHIVED">已归档</option>
                  </select>
                </FormField>
              </div>
              {draftError && (
                <p role="alert" className="mt-2 text-copy-12 text-destructive">
                  {draftError}
                </p>
              )}
              <div className="mt-3 flex gap-2">
                <Button onClick={() => void submit()} disabled={saving}>
                  {saving ? '保存中…' : '保存'}
                </Button>
                <Button variant="secondary" onClick={() => setShowEdit(false)}>
                  取消
                </Button>
              </div>
            </SectionCard>
          )}
        </>
      )}

      {/* 库位清单 */}
      {detail && (
        <SectionCard className="mt-4" title={`库位清单（${locations?.total ?? 0}）`}>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <FormField id="detail-loc-type" label="库位类型">
              <select
                id="detail-loc-type"
                className="h-9 w-full rounded-md border border-input bg-background px-3 text-copy-13"
                value={type}
                onChange={(e) => {
                  setType(e.target.value);
                  setPageNo(0);
                }}
              >
                <option value="">全部</option>
                <option value="STORAGE">存储库位</option>
                <option value="RECEIVING">收货库位</option>
                <option value="SHIPPING">发货库位</option>
                <option value="QUARANTINE">待检库位</option>
                <option value="DEFECTIVE">残次库位</option>
                <option value="FROZEN">冻结库位</option>
              </select>
            </FormField>
            <FormField id="detail-loc-status" label="库位状态">
              <select
                id="detail-loc-status"
                className="h-9 w-full rounded-md border border-input bg-background px-3 text-copy-13"
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value);
                  setPageNo(0);
                }}
              >
                <option value="">全部</option>
                <option value="ACTIVE">启用中</option>
                <option value="INACTIVE">已停用</option>
              </select>
            </FormField>
            <FormField id="detail-loc-sort" label="排序">
              <select
                id="detail-loc-sort"
                className="h-9 w-full rounded-md border border-input bg-background px-3 text-copy-13"
                value={sort}
                onChange={(e) => setSort(e.target.value)}
              >
                <option value="CODE">按编码</option>
                <option value="NAME">按名称</option>
                <option value="TYPE">按类型</option>
                <option value="STOCK">按在手量</option>
                <option value="MOVEMENT">按最近变动</option>
              </select>
            </FormField>
            <FormField id="detail-loc-keyword" label="关键字">
              <Input
                value={keyword}
                placeholder="库位编码或名称"
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setPageNo(0);
                }}
              />
            </FormField>
          </div>

          {locations && (
            <p className="mt-3 text-copy-12 text-muted-foreground">
              合计：{qty(locations.totals.locationCount)} 个库位 · 可拣 {qty(locations.totals.pickableCount)} ·{' '}
              {locationFillText(locations.totals.locationCount, locations.totals.occupiedCount)} · 在手{' '}
              {qty(locations.totals.onHand)}
            </p>
          )}

          {loading && rows.length === 0 ? (
            <p className="py-8 text-center text-copy-13 text-muted-foreground">加载中…</p>
          ) : rows.length === 0 ? (
            <EmptyState
              icon={<MapPinned />}
              title="还没有库位"
              description="库位档案属 8.1.2.1 库位树，尚未交付；本页展示的库位来自库存维度的库位明细。"
            />
          ) : (
            <div className="mt-3 overflow-x-auto">
              <table className="w-full text-copy-13">
                <thead className="text-left text-muted-foreground">
                  <tr className="border-b">
                    <th className="py-2 pr-3">库位</th>
                    <th className="py-2 pr-3">类型</th>
                    <th className="py-2 pr-3">状态</th>
                    <th className="py-2 pr-3">可拣</th>
                    <th className="py-2 pr-3">在手</th>
                    <th className="py-2 pr-3">可用</th>
                    <th className="py-2 pr-3">SKU</th>
                    <th className="py-2 pr-3">最近变动</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id} className="border-b last:border-0">
                      <td className="py-2 pr-3">
                        <div className="font-medium">{row.code}</div>
                        <div className="text-copy-12 text-muted-foreground">{row.name}</div>
                      </td>
                      <td className="py-2 pr-3">{row.typeLabel ?? row.type}</td>
                      <td className="py-2 pr-3">{row.status === 'ACTIVE' ? '启用中' : '已停用'}</td>
                      <td className="py-2 pr-3">{pickableText(row.pickable)}</td>
                      <td className="py-2 pr-3">{qty(row.onHand)}</td>
                      <td className="py-2 pr-3">{qty(row.available)}</td>
                      <td className="py-2 pr-3">{qty(row.skuCount)}</td>
                      <td className="py-2 pr-3 text-muted-foreground">
                        {row.lastMovementAt ? momentText(row.lastMovementAt) : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {locations && locations.total > 0 && (
            <div className="mt-3 flex items-center justify-between text-copy-12 text-muted-foreground">
              <span>
                第 {locations.page + 1} / {totalPages} 页 · 每页 {locations.size} 条 · {locations.note}
              </span>
              <span className="flex gap-2">
                <Button
                  variant="secondary"
                  disabled={locations.page <= 0 || loading}
                  onClick={() => setPageNo((n) => Math.max(0, n - 1))}
                >
                  上一页
                </Button>
                <Button
                  variant="secondary"
                  disabled={locations.page + 1 >= totalPages || loading}
                  onClick={() => setPageNo((n) => n + 1)}
                >
                  下一页
                </Button>
              </span>
            </div>
          )}
        </SectionCard>
      )}
    </>
  );
}

function Info({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div>
      <div className="text-copy-12 text-muted-foreground">{label}</div>
      <div className="text-copy-13">{value}</div>
      {hint && <div className="text-copy-12 text-muted-foreground">{hint}</div>}
    </div>
  );
}

/**
 * 一个入口按钮。
 *
 * <p>{@code href} 为 null 的入口**不渲染成链接**：那类入口的 {@code available} 恒为 false，
 * 渲染一个能点的东西等于告诉用户「点进去有内容」，实际会落到不存在的路由。
 */
function EntryLink({ point }: { point: DetailEntryPoint }) {
  if (!point.href) {
    return (
      <span
        key={point.key}
        className="rounded-md border border-border px-3 py-1.5 text-copy-12 text-muted-foreground"
        title={point.note}
      >
        {point.label}（属 {point.module}，本服务未提供入口）
      </span>
    );
  }
  return (
    <Link
      key={point.key}
      href={point.href}
      className="inline-flex items-center rounded-md border border-border px-3 py-1.5 text-copy-12 hover:bg-muted"
      title={point.note}
    >
      <Building2 className="mr-1.5 h-3.5 w-3.5" />
      {point.label}
    </Link>
  );
}

