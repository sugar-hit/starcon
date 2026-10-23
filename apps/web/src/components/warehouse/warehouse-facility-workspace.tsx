'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { usePrefillKeyword } from '@/lib/global-search-handoff';
import { Boxes, Filter, Plus, RefreshCw, Warehouse } from 'lucide-react';
import {
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  FacilityOptions,
  WarehouseCreated,
  WarehouseFacilityPage,
  WorkVolumeCapability,
} from '../../../../../packages/api-contracts/src/index';
import { useIdentity } from '../iam/identity-shell';
import {
  ALL,
  countryOptions,
  createProblem,
  createWarehouse,
  errorText,
  fetchFacilityOptions,
  fetchWarehouses,
  fetchWorkCapabilities,
  kindLabel,
  kindLabels,
  legalEntityText,
  localTimeText,
  qty,
  stockDisplay,
  statusLabel,
  statusTone,
  workText,
  type CreateWarehouseDraft,
  type FacilityParams,
  type WarehouseKind,
} from './warehouse-facility-model';

const PAGE_SIZE = 20;

const emptyDraft: CreateWarehouseDraft = {
  code: '',
  name: '',
  kind: '',
  countryCode: '',
  timezone: 'Asia/Shanghai',
  defaultLegalEntityId: '',
  status: 'ACTIVE',
  isDefault: false,
};

/**
 * 8.1.1.1 仓库列表工作区。
 *
 * <p>蓝图：「展示租户下所有仓库：类型（自营/三方/海外仓/平台仓/监管仓）、所属法人、国家地区、
 * 时区、启用状态、库存量与作业量，支持筛选与新增。」
 *
 * <p>界面纪律（每一条都对应一个会被误显示的坑）：
 *
 * <ul>
 *   <li>**平台仓与自有仓的库存量分开成两列**，不相加。FBA 是平台观测值（V55 的纪律），
 *       合成一个「总库存」会让人以为那是账实相符的数字。
 *   <li>**未接入的作业维度在能力卡里显示成「未接入」**，收货 / 拣货出库 / 组装尚未交付，
 *       显示 0 会被读成「这个仓不作业」。
 *   <li>**未绑定法人要说未绑定**，不能画成「法人数 0」——那是「这个仓没有任何货」，
 *       与「有货但没绑定货权法人」是两件完全不同的事。
 *   <li>**当地时间带 IANA ID**，不只给一个偏移量；库里时区无法识别时如实说。
 *   <li>**详情入口在 8.1.1.2 交付前禁用**（后端 `detailUnavailable`），
 *       不跳到一个不存在的路由。
 * </ul>
 */
export function WarehouseFacilityWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.facilities.read');
  const writable = session.permissions.includes('warehouse.facilities.create');

  const [options, setOptions] = useState<FacilityOptions | null>(null);
  const [capabilities, setCapabilities] = useState<WorkVolumeCapability[]>([]);
  const [page, setPage] = useState<WarehouseFacilityPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [kind, setKind] = useState(ALL);
  const [legalEntityId, setLegalEntityId] = useState(ALL);
  const [countryCode, setCountryCode] = useState(ALL);
  const [status, setStatus] = useState(ALL);
  const [keyword, setKeyword] = usePrefillKeyword();
  const [sort, setSort] = useState('CODE');
  const [pageNo, setPageNo] = useState(0);

  const [showCreate, setShowCreate] = useState(false);
  const [draft, setDraft] = useState<CreateWarehouseDraft>(emptyDraft);
  const [saving, setSaving] = useState(false);
  const [draftError, setDraftError] = useState('');

  const params: FacilityParams = useMemo(
    () => ({
      kind,
      legalEntityId,
      countryCode,
      status,
      keyword,
      sort,
      page: pageNo,
      size: PAGE_SIZE,
    }),
    [kind, legalEntityId, countryCode, status, keyword, sort, pageNo],
  );

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [data, opts, caps] = await Promise.all([
        fetchWarehouses(params),
        fetchFacilityOptions(),
        fetchWorkCapabilities(),
      ]);
      setPage(data);
      setOptions(opts);
      setCapabilities(caps);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [params, readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const resetFilters = () => {
    setKind(ALL);
    setLegalEntityId(ALL);
    setCountryCode(ALL);
    setStatus(ALL);
    setKeyword('');
    setSort('CODE');
    setPageNo(0);
  };

  const submit = async () => {
    const problem = createProblem(draft);
    setDraftError(problem);
    if (problem) return;
    setSaving(true);
    try {
      const created: WarehouseCreated = await createWarehouse(draft);
      setNotice(`已新增仓库 ${created.code}（${kindLabel(created.kind)}）。`);
      setShowCreate(false);
      setDraft(emptyDraft);
      setPageNo(0);
      await load();
    } catch (e) {
      setDraftError(errorText(e));
    } finally {
      setSaving(false);
    }
  };

  const summary = page?.summary;
  const rows = page?.rows ?? [];
  const totalPages = page ? Math.max(1, Math.ceil(page.total / page.size)) : 1;
  const unavailable = capabilities.filter((c) => !c.available);
  const countries = countryOptions(options?.countries ?? []);

  return (
    <>
      <PageHeader
        title="仓库列表"
        description="仓库类型、货权法人、国家与时区、启用状态，以及库存量与作业量。"
        actions={
          <>
            <Button variant="secondary" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="mr-2 h-4 w-4" />
              刷新
            </Button>
            {writable && (
              <Button onClick={() => setShowCreate((v) => !v)}>
                <Plus className="mr-2 h-4 w-4" />
                新增仓库
              </Button>
            )}
          </>
        }
      />

      {error && (
        <Notice tone="danger" title="仓库列表加载失败">
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

      {/* 合计：不受分页与筛选影响，切筛选条件不该让总量看起来像丢了数据。 */}
      <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="仓库总数" value={summary?.warehouseCount ?? 0} icon={<Warehouse />} />
        <StatCard
          label="启用中"
          value={summary?.activeCount ?? 0}
          unit={`/ ${summary?.warehouseCount ?? 0}`}
          icon={<Warehouse />}
        />
        <StatCard
          label="自有账本在手"
          value={qty(summary?.onHand)}
          polarity="neutral"
          icon={<Boxes />}
        />
        <StatCard
          label="作业单合计"
          value={summary?.workOrderTotal ?? 0}
          unit={`未结 ${summary?.openWorkOrderCount ?? 0}`}
          icon={<Boxes />}
        />
      </div>

      {summary && summary.platformWarehouseCount > 0 && (
        <Notice tone="info" title="平台仓观测值与自有账本分开存放">
          本租户有 {summary.platformWarehouseCount} 个平台仓（FBA），观测在手合计{' '}
          <strong>{qty(summary.platformObservedOnHand)}</strong>。这是平台报出的观测值，
          <strong>不计入</strong>上面的自有账本在手，两者不相加。
        </Notice>
      )}

      {unavailable.length > 0 && (
        <SectionCard className="mt-4">
          <div className="flex items-start gap-2">
            <Filter className="text-muted-foreground mt-0.5 h-4 w-4 shrink-0" />
            <div className="text-copy-12 text-muted-foreground">
              <p className="text-copy-13 text-foreground font-medium">以下作业维度尚未交付</p>
              <ul className="mt-1 list-disc pl-4">
                {unavailable.map((c) => (
                  <li key={c.dimension}>
                    {c.label}（属 {c.ownerModule}）：{c.note}
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </SectionCard>
      )}

      {/* 筛选 */}
      <SectionCard className="mt-4" title="筛选">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <FormField id="facility-kind" label="类型">
            <select
              id="facility-kind"
              className="border-input bg-background text-copy-13 h-9 w-full rounded-md border px-3"
              value={kind}
              onChange={(e) => {
                setKind(e.target.value);
                setPageNo(0);
              }}
            >
              <option value="">全部</option>
              {Object.entries(kindLabels).map(([code, label]) => (
                <option key={code} value={code}>
                  {label}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="facility-legal-entity" label="所属法人">
            <select
              id="facility-legal-entity"
              className="border-input bg-background text-copy-13 h-9 w-full rounded-md border px-3"
              value={legalEntityId}
              onChange={(e) => {
                setLegalEntityId(e.target.value);
                setPageNo(0);
              }}
            >
              <option value="">全部</option>
              {(options?.legalEntities ?? []).map((e2) => (
                <option key={e2.id} value={e2.id}>
                  {e2.code} · {e2.name}
                  {e2.status !== 'ACTIVE' ? '（已停用）' : ''}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="facility-country" label="国家地区">
            <select
              id="facility-country"
              className="border-input bg-background text-copy-13 h-9 w-full rounded-md border px-3"
              value={countryCode}
              onChange={(e) => {
                setCountryCode(e.target.value);
                setPageNo(0);
              }}
            >
              <option value="">全部</option>
              {countries.map((c) => (
                <option key={c.value} value={c.value}>
                  {c.label}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="facility-status" label="启用状态">
            <select
              id="facility-status"
              className="border-input bg-background text-copy-13 h-9 w-full rounded-md border px-3"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPageNo(0);
              }}
            >
              <option value="">全部</option>
              {(options?.statuses ?? []).map((s) => (
                <option key={s.code} value={s.code}>
                  {s.label}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="facility-sort" label="排序">
            <select
              id="facility-sort"
              className="border-input bg-background text-copy-13 h-9 w-full rounded-md border px-3"
              value={sort}
              onChange={(e) => setSort(e.target.value)}
            >
              <option value="CODE">按编码</option>
              <option value="NAME">按名称</option>
              <option value="KIND">按类型</option>
              <option value="STOCK">按在手量</option>
              <option value="WORK">按作业量</option>
            </select>
          </FormField>
        </div>
        <div className="mt-3 flex items-end gap-2">
          <div className="flex-1">
            <FormField id="facility-keyword" label="关键字">
              <Input
                value={keyword}
                placeholder="仓库编码或名称"
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setPageNo(0);
                }}
              />
            </FormField>
          </div>
          <Button variant="secondary" onClick={resetFilters}>
            重置
          </Button>
        </div>
      </SectionCard>

      {/* 新增 */}
      {showCreate && writable && (
        <SectionCard className="mt-4" title="新增仓库">
          <Notice tone="info" title="仓库绑定法人是财务前提">
            不同法人的库存货权与收入归属不同，所以所属法人必填。时区要填 IANA ID （如
            Asia/Shanghai），偏移量不表达夏令时规则。
          </Notice>
          <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            <FormField id="create-code" label="仓库编码" required>
              <Input
                value={draft.code}
                onChange={(e) => setDraft({ ...draft, code: e.target.value })}
                placeholder="WH-XX"
              />
            </FormField>
            <FormField id="create-name" label="仓库名称" required>
              <Input
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
              />
            </FormField>
            <FormField id="create-kind" label="仓库类型" required>
              <select
                id="create-kind"
                className="border-input bg-background text-copy-13 h-9 w-full rounded-md border px-3"
                value={draft.kind}
                onChange={(e) => setDraft({ ...draft, kind: e.target.value as WarehouseKind })}
              >
                <option value="">请选择</option>
                {Object.entries(kindLabels).map(([code, label]) => (
                  <option key={code} value={code}>
                    {label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField
              id="create-country"
              label="国家地区"
              required
              {...(options?.countryNote ? { hint: options.countryNote } : {})}
            >
              <Input
                value={draft.countryCode}
                maxLength={2}
                placeholder="CN"
                onChange={(e) => setDraft({ ...draft, countryCode: e.target.value.toUpperCase() })}
              />
            </FormField>
            <FormField
              id="create-timezone"
              label="时区"
              required
              {...(options?.timezoneNote ? { hint: options.timezoneNote } : {})}
            >
              <Input
                value={draft.timezone}
                onChange={(e) => setDraft({ ...draft, timezone: e.target.value })}
              />
            </FormField>
            <FormField id="create-legal-entity" label="所属法人" required hint="货权与收入归属">
              <select
                id="create-legal-entity"
                className="border-input bg-background text-copy-13 h-9 w-full rounded-md border px-3"
                value={draft.defaultLegalEntityId}
                onChange={(e) => setDraft({ ...draft, defaultLegalEntityId: e.target.value })}
              >
                <option value="">请选择</option>
                {(options?.legalEntities ?? [])
                  .filter((e2) => e2.status === 'ACTIVE')
                  .map((e2) => (
                    <option key={e2.id} value={e2.id}>
                      {e2.code} · {e2.name}
                    </option>
                  ))}
              </select>
            </FormField>
          </div>
          <label className="text-copy-13 mt-3 flex items-center gap-2">
            <input
              type="checkbox"
              checked={draft.isDefault}
              onChange={(e) => setDraft({ ...draft, isDefault: e.target.checked })}
            />
            设为默认发货仓（每个租户至多一个启用中的默认仓）
          </label>
          {draftError && (
            <p role="alert" className="text-copy-12 text-destructive mt-2">
              {draftError}
            </p>
          )}
          <div className="mt-3 flex gap-2">
            <Button onClick={() => void submit()} disabled={saving}>
              {saving ? '保存中…' : '保存'}
            </Button>
            <Button variant="secondary" onClick={() => setShowCreate(false)}>
              取消
            </Button>
          </div>
        </SectionCard>
      )}

      {/* 列表 */}
      <SectionCard className="mt-4" title={`仓库（${page?.total ?? 0}）`}>
        {loading ? (
          <p className="text-copy-13 text-muted-foreground py-8 text-center">加载中…</p>
        ) : rows.length === 0 ? (
          <EmptyState
            icon={<Warehouse />}
            title="没有符合条件的仓库"
            description="调整筛选条件，或新增一个仓库档案。"
            action={
              writable ? (
                <Button onClick={() => setShowCreate(true)}>
                  <Plus className="mr-2 h-4 w-4" />
                  新增仓库
                </Button>
              ) : undefined
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="text-copy-13 w-full">
              <thead className="text-muted-foreground text-left">
                <tr className="border-b">
                  <th className="py-2 pr-3">仓库</th>
                  <th className="py-2 pr-3">详情</th>
                  <th className="py-2 pr-3">类型</th>
                  <th className="py-2 pr-3">所属法人</th>
                  <th className="py-2 pr-3">国家</th>
                  <th className="py-2 pr-3">当地时间</th>
                  <th className="py-2 pr-3">状态</th>
                  <th className="py-2 pr-3">在手量</th>
                  <th className="py-2 pr-3">作业量</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => {
                  const stock = stockDisplay(row);
                  return (
                    <tr key={row.id} className="border-b last:border-0">
                      <td className="py-2 pr-3">
                        <div className="font-medium">{row.name}</div>
                        <div className="text-copy-12 text-muted-foreground">
                          {row.code}
                          {row.defaultWarehouse ? ' · 默认发货仓' : ''}
                        </div>
                      </td>
                      {/* 8.1.1.2 已交付：详情入口在交付前是禁用的，
                          现在由后端 detailUnavailable=false 放行。
                          href 指向本 worktree 里真实存在的页面路由。 */}
                      <td className="py-2 pr-3">
                        {page?.detailUnavailable === false ? (
                          <Link
                            href={`/warehouse/facilities/${row.id}`}
                            className="text-copy-12 hover:underline"
                          >
                            详情
                          </Link>
                        ) : (
                          <span className="text-copy-12 text-muted-foreground">—</span>
                        )}
                      </td>
                      <td className="py-2 pr-3">{kindLabel(row.kind)}</td>
                      <td className="py-2 pr-3">
                        <div>{legalEntityText(row)}</div>
                        {row.legalEntityCount > 1 && (
                          <div className="text-copy-12 text-muted-foreground">
                            实际持有 {row.legalEntityCount} 个法人的货
                          </div>
                        )}
                      </td>
                      <td className="py-2 pr-3">{row.countryCode}</td>
                      <td className="text-muted-foreground py-2 pr-3">{localTimeText(row)}</td>
                      <td className="py-2 pr-3">{statusLabel(row.status)}</td>
                      <td className="py-2 pr-3">
                        <div>{stock.onHand}</div>
                        <div className="text-copy-12 text-muted-foreground">{stock.note}</div>
                      </td>
                      <td className="py-2 pr-3">
                        <div>{workText(row)}</div>
                        <div className="text-copy-12 text-muted-foreground">
                          未结 {qty(row.work.openWorkOrderCount)} · 库位 {qty(row.locationCount)} ·
                          SKU {qty(row.skuCount)}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {page && page.total > 0 && (
          <div className="text-copy-12 text-muted-foreground mt-3 flex items-center justify-between">
            <span>
              第 {page.page + 1} / {totalPages} 页 · 每页 {page.size} 条 · {page.note}
            </span>
            <span className="flex gap-2">
              <Button
                variant="secondary"
                disabled={page.page <= 0 || loading}
                onClick={() => setPageNo((n) => Math.max(0, n - 1))}
              >
                上一页
              </Button>
              <Button
                variant="secondary"
                disabled={page.page + 1 >= totalPages || loading}
                onClick={() => setPageNo((n) => n + 1)}
              >
                下一页
              </Button>
            </span>
          </div>
        )}

        {page?.detailUnavailable && (
          <p className="text-copy-12 text-muted-foreground mt-3">
            仓库详情（8.1.1.2）尚未交付，进详情入口暂不可用。
          </p>
        )}
      </SectionCard>
    </>
  );
}
