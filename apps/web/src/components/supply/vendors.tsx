'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { Factory, Plus } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@starcon/ui';

import {
  EMPTY_VENDOR_FILTERS,
  amountListText,
  cooperationLevelLabel,
  dateText,
  daysLateText,
  fetchVendorOptions,
  fetchVendorOverview,
  fetchVendorPurchaseView,
  fetchVendors,
  partyStatusLabel,
  partyStatusTone,
  rateLabel,
  rateTone,
  recordVendorDelivery,
  recordVendorInspection,
  saveVendorSupplyProfile,
  supplyStatusLabel,
  supplyStatusTone,
  toSupplyProfileForm,
  validateDelivery,
  validateInspection,
  validateSupplyProfile,
  validateVoidReason,
  voidVendorDelivery,
  voidVendorInspection,
  windowText,
  VENDOR_EXPORT_SOURCE_KEY,
} from './vendors-model';
import type {
  VendorDeliveryInput,
  VendorFilters,
  VendorListOptions,
  VendorOverview,
  VendorPage,
  VendorPurchaseView,
  VendorQualityInspectionInput,
  VendorRow,
  VendorSupplyProfileInput,
} from './vendors-model';

/**
 * 6.1.1.1 供应商列表。
 *
 * <p>🔴 页面顶部那句话不是提示语，是这一页的设计前提：**供应商主数据在 03，
 * 本页只呈现采购视角的视图与运营动作**。所以表格里的主体身份
 * （编码 / 名称 / 邮箱 / 电话 / 官网 / 主体状态）与合作等级 / 账期 / 结算方式
 * **全部只读**，页面上没有任何编辑它们的入口 —— 它们要回往来单位档案（/partners）改。
 * 本页能写的只有采购侧那几项：合作状态、主供类目、采购侧备注，
 * 以及到货 / 质检这两张只追加的事实表。
 *
 * <p>与 10.1.1.1 承运商列表是同一个形状（那边是「承运商是往来单位的一种角色」）。
 * 两处各建一份可编辑副本的后果是同一个：改名要改两处，而只有一处是主体档案的写权限。
 */
export function VendorWorkspace() {
  const [options, setOptions] = useState<VendorListOptions | null>(null);
  const [overview, setOverview] = useState<VendorOverview | null>(null);
  const [page, setPage] = useState<VendorPage | null>(null);
  const [filters, setFilters] = useState<VendorFilters>(EMPTY_VENDOR_FILTERS);
  const [editing, setEditing] = useState<VendorRow | null>(null);
  const [form, setForm] = useState<VendorSupplyProfileInput | null>(null);
  const [detail, setDetail] = useState<VendorPurchaseView | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [next, opts, ov] = await Promise.all([
        fetchVendors(filters),
        fetchVendorOptions(),
        fetchVendorOverview(),
      ]);
      setPage(next);
      setOptions(opts);
      setOverview(ov);
    } catch (e) {
      setError(e instanceof Error ? e.message : '供应商列表读不到');
    } finally {
      setBusy(false);
    }
  }, [filters]);

  useEffect(() => {
    void load();
  }, [load]);

  async function openDetail(row: VendorRow) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      setDetail(await fetchVendorPurchaseView(row.roleId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '采购视角详情读不到');
    } finally {
      setBusy(false);
    }
  }

  async function submitProfile(next: VendorSupplyProfileInput, row: VendorRow) {
    const problems = validateSupplyProfile(next);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    try {
      await saveVendorSupplyProfile(row.roleId, next);
      setEditing(null);
      setForm(null);
      setNotice(
        `${row.partyName} 的采购侧档案已保存，合作状态：${supplyStatusLabel(next.supplyStatus)}。`,
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }

  const totalPages = page ? Math.max(Math.ceil(page.total / Math.max(page.size, 1)), 1) : 1;

  return (
    <>
      <PageHeader
        title="供应商列表"
        description="供应商主数据在往来单位档案（03），本页是采购视角：只读主体身份与合作等级，维护采购侧合作状态、主供类目与到货 / 质检事实，并按统计窗口给出供货 SKU 数、采购金额累计、准时交付率、质量合格率。"
      />

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
      {notice ? <p className="text-muted-foreground text-sm">{notice}</p> : null}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="供应商"
          value={String(overview?.supplierCount ?? 0)}
          comparisonLabel="未删除主体的 SUPPLIER 身份"
        />
        <StatCard
          label="合作中"
          value={String(overview?.activeSupplyCount ?? 0)}
          polarity="positive"
          comparisonLabel={`暂停下单 ${overview?.suspendedSupplyCount ?? 0} 家`}
        />
        <StatCard
          label="待补采购侧档案"
          value={String(overview?.missingProfileCount ?? 0)}
          polarity={overview?.missingProfileCount ? 'negative' : 'neutral'}
          comparisonLabel="合作状态 / 主供类目还没填"
        />
        <StatCard
          label="准时交付率"
          value={rateLabel(overview?.onTimeRate ?? null)}
          polarity="neutral"
          comparisonLabel={`窗口 ${windowText(overview?.metricWindow)} · ${overview?.withDeliveryRecord ?? 0} 家有记录`}
        />
      </div>

      <Tabs defaultValue="vendors" className="mt-6">
        <TabsList>
          <TabsTrigger value="vendors">供应商</TabsTrigger>
          <TabsTrigger value="boundary">字段归属</TabsTrigger>
        </TabsList>

        <TabsContent value="vendors">
          <SectionCard title="筛选">
            <div className="grid gap-3 md:grid-cols-4">
              <FormField id="kw" label="关键词" hint="编码 / 名称 / 邮箱 / 电话">
                <Input
                  id="kw"
                  value={filters.keyword}
                  placeholder="全部"
                  onChange={(e) => setFilters({ ...filters, keyword: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="status" label="合作状态" hint="采购侧（本页可写）">
                <select
                  id="status"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={filters.supplyStatus}
                  onChange={(e) =>
                    setFilters({ ...filters, supplyStatus: e.target.value, page: 0 })
                  }
                >
                  <option value="">全部</option>
                  {(options?.supplyStatuses ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="level" label="合作等级" hint="03 只读，可筛不可改">
                <select
                  id="level"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={filters.cooperationLevel}
                  onChange={(e) =>
                    setFilters({ ...filters, cooperationLevel: e.target.value, page: 0 })
                  }
                >
                  <option value="">全部</option>
                  {(options?.cooperationLevels ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="category" label="主供类目">
                <select
                  id="category"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={filters.categoryId}
                  onChange={(e) => setFilters({ ...filters, categoryId: e.target.value, page: 0 })}
                >
                  <option value="">全部</option>
                  {(options?.categories ?? []).map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="partyStatus" label="主体状态" hint="03 只读">
                <select
                  id="partyStatus"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={filters.partyStatus}
                  onChange={(e) => setFilters({ ...filters, partyStatus: e.target.value, page: 0 })}
                >
                  <option value="">全部</option>
                  {(options?.partyStatuses ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="currency" label="币种" hint="金额按币种分组，不跨币种相加">
                <Input
                  id="currency"
                  value={filters.currency}
                  placeholder="如 USD"
                  onChange={(e) => setFilters({ ...filters, currency: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="metricFrom" label="指标窗口起" hint="准时率 / 合格率是窗口统计">
                <Input
                  id="metricFrom"
                  type="date"
                  value={filters.metricFrom}
                  onChange={(e) => setFilters({ ...filters, metricFrom: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="metricTo" label="指标窗口止">
                <Input
                  id="metricTo"
                  type="date"
                  value={filters.metricTo}
                  onChange={(e) => setFilters({ ...filters, metricTo: e.target.value, page: 0 })}
                />
              </FormField>
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <label className="text-muted-foreground flex items-center gap-2 text-xs">
                <input
                  type="checkbox"
                  checked={filters.missingProfile}
                  onChange={(e) =>
                    setFilters({ ...filters, missingProfile: e.target.checked, page: 0 })
                  }
                />
                只看还没建采购侧档案的
              </label>
              <Button
                size="sm"
                variant="ghost"
                onClick={() => setFilters({ ...EMPTY_VENDOR_FILTERS, sort: filters.sort })}
              >
                重置筛选
              </Button>
              <span className="text-muted-foreground text-xs">
                当前指标窗口：{windowText(page?.metricWindow)}（准时率与合格率按这一段算）
              </span>
            </div>
            {page?.note ? <p className="text-muted-foreground mt-2 text-xs">{page.note}</p> : null}
          </SectionCard>

          <SectionCard
            title={`供应商（${page?.total ?? 0}）`}
            extra={
              <Badge tone="info">
                导出数据源 {VENDOR_EXPORT_SOURCE_KEY}（走 3.8.2.1 统一导出通道）
              </Badge>
            }
            className="mt-4"
          >
            {page && page.items.length > 0 ? (
              <div className="space-y-3">
                {page.items.map((row) => (
                  <VendorCard
                    key={row.roleId}
                    row={row}
                    busy={busy}
                    onEdit={() => {
                      setEditing(row);
                      setForm(toSupplyProfileForm(row));
                    }}
                    onDetail={() => void openDetail(row)}
                  />
                ))}
                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={page.page <= 0 || busy}
                    onClick={() => setFilters({ ...filters, page: filters.page - 1 })}
                  >
                    上一页
                  </Button>
                  <span className="text-muted-foreground text-xs">
                    第 {page.page + 1} / {totalPages} 页
                  </span>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={page.page + 1 >= totalPages || busy}
                    onClick={() => setFilters({ ...filters, page: filters.page + 1 })}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            ) : busy ? null : (
              <EmptyState
                icon={<Factory className="h-6 w-6" />}
                title="没有符合条件的供应商"
                description="先在往来单位档案里给对手方挂上供应商身份，本页就会把它列出来（合作状态按「待评估」呈现）。也可以放宽筛选或重置窗口。"
              />
            )}
          </SectionCard>

          {editing && form ? (
            <SectionCard title={`登记采购侧档案 · ${editing.partyName}`} className="mt-4">
              <p className="text-muted-foreground mb-3 text-xs">
                下方只写采购侧的三项。编码 / 名称 / 联系方式在往来单位档案，合作等级 / 账期 /
                结算方式在 03 的角色档案 —— 本页**不提供**改它们的入口（同一实体只应有一处可编辑）。
              </p>
              <SupplyProfileForm
                form={form}
                options={options}
                busy={busy}
                onChange={setForm}
                onCancel={() => {
                  setEditing(null);
                  setForm(null);
                }}
                onSubmit={() => void submitProfile(form, editing)}
              />
            </SectionCard>
          ) : null}
        </TabsContent>

        <TabsContent value="boundary">
          <SectionCard title="字段归属：哪些能改，哪些只读">
            <Notice tone="info" title="模块边界">
              蓝图原文：供应商数据主数据在 03，本模块呈现采购视角的视图与运营动作，
              <b>避免同一实体两处可编辑</b>。
            </Notice>
            <div className="mt-3 space-y-2 text-sm">
              <p>
                <Badge tone="danger">只读 · 03 往来单位</Badge>{' '}
                编码、名称、主体类型、注册国、邮箱、电话、官网、主体状态、状态理由
              </p>
              <p>
                <Badge tone="danger">只读 · 03 角色档案</Badge> 合作等级、账期天数、结算方式
              </p>
              <p>
                <Badge tone="success">可写 · 本页</Badge> 合作状态、主供类目、采购侧备注
              </p>
              <p>
                <Badge tone="success">可写 · 本页（只追加）</Badge>{' '}
                到货记录、质检记录；录错了作废并留理由，不做编辑
              </p>
              <p>
                <Badge tone="neutral">派生 · 不落库</Badge> 供货 SKU
                数、采购金额累计、准时交付率、质量合格率 —— 四个指标全部是列表查询里的 SQL
                聚合，没有一个是存下来的字段
              </p>
            </div>
            {options?.note ? (
              <p className="text-muted-foreground mt-3 text-xs">{options.note}</p>
            ) : null}
            {overview?.note ? (
              <p className="text-muted-foreground mt-1 text-xs">{overview.note}</p>
            ) : null}
          </SectionCard>
        </TabsContent>
      </Tabs>

      {detail ? (
        <PurchaseViewDrawer
          view={detail}
          busy={busy}
          onClose={() => setDetail(null)}
          onReload={async () => {
            const refreshed = await fetchVendorPurchaseView(detail.summary.roleId);
            setDetail(refreshed);
            await load();
          }}
          onError={(message) => setError(message)}
          onNotice={(message) => setNotice(message)}
        />
      ) : null}
    </>
  );
}

/** 一行供应商。指标四列按蓝图职责逐个渲染，比率列的 null 交给 rateLabel 处理。 */
function VendorCard({
  row,
  busy,
  onEdit,
  onDetail,
}: {
  row: VendorRow;
  busy: boolean;
  onEdit: () => void;
  onDetail: () => void;
}) {
  return (
    <div className="border-border rounded-md border p-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-medium">{row.partyName}</span>
            <span className="text-muted-foreground text-xs">{row.partyCode}</span>
            <Badge tone={supplyStatusTone(row.supplyStatus)}>
              {supplyStatusLabel(row.supplyStatus)}
            </Badge>
            <Badge tone={partyStatusTone(row.partyStatus)}>
              {partyStatusLabel(row.partyStatus)}
            </Badge>
            <Badge tone="info">合作等级 {cooperationLevelLabel(row.cooperationLevel)}</Badge>
            {!row.supplyProfileFound ? <Badge tone="warning">未建采购侧档案</Badge> : null}
          </div>
          <p className="text-muted-foreground mt-1 text-xs">
            {row.email ?? '未登记邮箱'} · {row.phone ?? '未登记电话'} · 主供类目{' '}
            {row.categoryName ?? '未归类'}
          </p>
        </div>
        <div className="flex gap-2">
          <Button size="sm" variant="ghost" onClick={onDetail} disabled={busy}>
            采购视角详情
          </Button>
          {/* 6.1.1.2 供应商全景：与上面那个「采购视角详情」是**两个不同区块**，
              那一块回答「采购侧现在是什么状态」，这一块回答全景（账期与开票、报价、
              采购历史、交期质量、联系人、附件资质 + 操作入口）。 */}
          <Link
            className="text-sm underline"
            href={`/supply/vendors/${row.roleId}`}
            aria-label="打开供应商全景详情"
          >
            供应商详情
          </Link>
          <Button size="sm" variant="outline" onClick={onEdit} disabled={busy}>
            登记采购侧档案
          </Button>
        </div>
      </div>

      <div className="text-muted-foreground mt-3 grid gap-2 text-xs sm:grid-cols-4">
        <span>
          供货 SKU 数 <b className="text-foreground">{row.supplySkuCount}</b>
        </span>
        <span>
          采购金额累计 <b className="text-foreground">{amountListText(row.purchaseAmounts)}</b>
        </span>
        <span>
          准时交付率 <Badge tone={rateTone(row.onTimeRate)}>{rateLabel(row.onTimeRate)}</Badge>
          <span className="ml-1">
            （{row.onTimeDeliveryCount ?? 0}/{row.deliveryCount ?? 0} 次）
          </span>
        </span>
        <span>
          质量合格率 <Badge tone={rateTone(row.qualityRate)}>{rateLabel(row.qualityRate)}</Badge>
        </span>
      </div>
      <p className="text-muted-foreground mt-1 text-xs">
        最近到货 {dateText(row.lastDeliveryAt)} · 最近质检 {dateText(row.lastInspectionAt)}
      </p>
    </div>
  );
}

function SupplyProfileForm({
  form,
  options,
  busy,
  onChange,
  onCancel,
  onSubmit,
}: {
  form: VendorSupplyProfileInput;
  options: VendorListOptions | null;
  busy: boolean;
  onChange: (next: VendorSupplyProfileInput) => void;
  onCancel: () => void;
  onSubmit: () => void;
}) {
  return (
    <div className="grid gap-3 md:grid-cols-3">
      <FormField id="supplyStatus" label="合作状态" required hint="采购侧：还跟不跟他做">
        <select
          id="supplyStatus"
          className="border-input h-9 w-full rounded-md border px-2 text-sm"
          value={form.supplyStatus}
          onChange={(e) => onChange({ ...form, supplyStatus: e.target.value })}
        >
          {(options?.supplyStatuses ?? []).map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </FormField>
      <FormField id="categoryId" label="主供类目" hint="采购拆单 / 对账按类目分组">
        <select
          id="categoryId"
          className="border-input h-9 w-full rounded-md border px-2 text-sm"
          value={form.categoryId ?? ''}
          onChange={(e) => onChange({ ...form, categoryId: e.target.value || null })}
        >
          <option value="">未归类</option>
          {(options?.categories ?? []).map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
      </FormField>
      <FormField id="supplyNote" label="采购侧备注" hint="谈崩原因 / 资质到期日 / 内部对接人">
        <Input
          id="supplyNote"
          value={form.note}
          onChange={(e) => onChange({ ...form, note: e.target.value })}
        />
      </FormField>
      <div className="flex gap-2 md:col-span-3">
        <Button size="sm" onClick={onSubmit} disabled={busy}>
          保存
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel} disabled={busy}>
          取消
        </Button>
      </div>
    </div>
  );
}

/**
 * 采购视角详情：到货 / 质检两张只追加事实表的追加与作废。
 *
 * <p>刻意不叫「供应商详情」：6.1.1.2 才是那个模块（基础信息 + 账期 + 报价 + 采购历史 + 附件资质全景）。
 */
function PurchaseViewDrawer({
  view,
  busy,
  onClose,
  onReload,
  onError,
  onNotice,
}: {
  view: VendorPurchaseView;
  busy: boolean;
  onClose: () => void;
  onReload: () => Promise<void>;
  onError: (message: string) => void;
  onNotice: (message: string) => void;
}) {
  const [delivery, setDelivery] = useState<VendorDeliveryInput>(emptyDelivery());
  const [inspection, setInspection] = useState<VendorQualityInspectionInput>(emptyInspection());

  async function submitDelivery() {
    const problems = validateDelivery(delivery);
    if (problems.length > 0) {
      onError(problems.join('；'));
      return;
    }
    try {
      await recordVendorDelivery(view.summary.roleId, delivery);
      setDelivery(emptyDelivery());
      onNotice('到货记录已追加。指标按新窗口重算。');
      await onReload();
    } catch (e) {
      onError(e instanceof Error ? e.message : '追加到货记录失败');
    }
  }

  async function submitInspection() {
    const problems = validateInspection(inspection);
    if (problems.length > 0) {
      onError(problems.join('；'));
      return;
    }
    try {
      await recordVendorInspection(view.summary.roleId, inspection);
      setInspection(emptyInspection());
      onNotice('质检记录已追加。');
      await onReload();
    } catch (e) {
      onError(e instanceof Error ? e.message : '追加质检记录失败');
    }
  }

  async function doVoid(kind: 'delivery' | 'inspection', recordId: string) {
    const reason = window.prompt('作废理由（必填，没有理由的作废会让「准时率突然变好」无法解释）');
    if (reason === null) return;
    const problems = validateVoidReason(reason);
    if (problems.length > 0) {
      onError(problems.join('；'));
      return;
    }
    try {
      if (kind === 'delivery') await voidVendorDelivery(view.summary.roleId, recordId, reason);
      else await voidVendorInspection(view.summary.roleId, recordId, reason);
      onNotice('已作废。记录仍在库里可追溯，但不再计入任何指标。');
      await onReload();
    } catch (e) {
      onError(e instanceof Error ? e.message : '作废失败');
    }
  }

  return (
    <SectionCard
      title={`采购视角 · ${view.summary.partyName}`}
      extra={
        <Button size="sm" variant="ghost" onClick={onClose}>
          关闭
        </Button>
      }
      className="mt-4"
    >
      {view.warnings.length > 0 ? (
        <div className="mb-3 space-y-1">
          {view.warnings.map((w) => (
            <p key={w} className="text-warning text-xs">
              ⚠ {w}
            </p>
          ))}
        </div>
      ) : null}
      <p className="text-muted-foreground text-xs">{view.note}</p>

      <div className="mt-3">
        <h4 className="text-sm font-medium">追加到货记录</h4>
        <p className="text-muted-foreground text-xs">
          只追加：到货是发生过的事实，没有编辑入口。录错了用「作废」并留理由。
        </p>
        <div className="mt-2 grid gap-3 md:grid-cols-4">
          <FormField id="deliveryRef" label="到货单号" required>
            <Input
              id="deliveryRef"
              value={delivery.deliveryRef}
              onChange={(e) => setDelivery({ ...delivery, deliveryRef: e.target.value })}
            />
          </FormField>
          <FormField id="quantity" label="到货数量" required>
            <Input
              id="quantity"
              value={delivery.quantity}
              placeholder="100"
              onChange={(e) => setDelivery({ ...delivery, quantity: e.target.value })}
            />
          </FormField>
          <FormField id="amount" label="到货金额" required>
            <Input
              id="amount"
              value={delivery.amount}
              placeholder="1200.00"
              onChange={(e) => setDelivery({ ...delivery, amount: e.target.value })}
            />
          </FormField>
          <FormField id="currency" label="币种" required>
            <Input
              id="currency"
              value={delivery.currency}
              placeholder="USD"
              onChange={(e) => setDelivery({ ...delivery, currency: e.target.value })}
            />
          </FormField>
          <FormField id="orderedAt" label="下单时间" required>
            <Input
              id="orderedAt"
              type="datetime-local"
              value={delivery.orderedAt}
              onChange={(e) => setDelivery({ ...delivery, orderedAt: e.target.value })}
            />
          </FormField>
          <FormField id="dueDate" label="承诺到货日" required>
            <Input
              id="dueDate"
              type="date"
              value={delivery.dueDate}
              onChange={(e) => setDelivery({ ...delivery, dueDate: e.target.value })}
            />
          </FormField>
          <FormField id="receivedDate" label="实际到货日" required>
            <Input
              id="receivedDate"
              type="date"
              value={delivery.receivedDate}
              onChange={(e) => setDelivery({ ...delivery, receivedDate: e.target.value })}
            />
          </FormField>
          <div className="flex items-end">
            <Button size="sm" onClick={() => void submitDelivery()} disabled={busy}>
              <Plus className="mr-1 h-4 w-4" />
              追加到货
            </Button>
          </div>
        </div>
      </div>

      <div className="mt-5">
        <h4 className="text-sm font-medium">追加质检记录</h4>
        <div className="mt-2 grid gap-3 md:grid-cols-4">
          <FormField id="inspectionRef" label="质检单号" required>
            <Input
              id="inspectionRef"
              value={inspection.inspectionRef}
              onChange={(e) => setInspection({ ...inspection, inspectionRef: e.target.value })}
            />
          </FormField>
          <FormField id="inspectedQuantity" label="检验数量" required>
            <Input
              id="inspectedQuantity"
              value={inspection.inspectedQuantity}
              placeholder="100"
              onChange={(e) => setInspection({ ...inspection, inspectedQuantity: e.target.value })}
            />
          </FormField>
          <FormField id="qualifiedQuantity" label="合格数量" required>
            <Input
              id="qualifiedQuantity"
              value={inspection.qualifiedQuantity}
              placeholder="98"
              onChange={(e) => setInspection({ ...inspection, qualifiedQuantity: e.target.value })}
            />
          </FormField>
          <FormField id="inspectedAt" label="检验时间" required>
            <Input
              id="inspectedAt"
              type="datetime-local"
              value={inspection.inspectedAt}
              onChange={(e) => setInspection({ ...inspection, inspectedAt: e.target.value })}
            />
          </FormField>
          <div className="flex items-end">
            <Button size="sm" onClick={() => void submitInspection()} disabled={busy}>
              <Plus className="mr-1 h-4 w-4" />
              追加质检
            </Button>
          </div>
        </div>
      </div>

      <div className="mt-5">
        <h4 className="text-sm font-medium">到货记录（{view.deliveries.length}）</h4>
        <div className="mt-2 space-y-2">
          {view.deliveries.map((d) => (
            <div
              key={d.id}
              className="border-border flex flex-wrap items-center gap-2 rounded border p-2 text-xs"
            >
              <span className="font-medium">{d.deliveryRef}</span>
              <Badge tone={d.onTime ? 'success' : 'warning'}>{daysLateText(d.daysLate)}</Badge>
              {d.isVoid ? <Badge tone="danger">已作废</Badge> : null}
              <span className="text-muted-foreground">
                承诺 {d.dueDate} · 实际 {d.receivedDate} · {d.quantity} × {d.currency} {d.amount}
              </span>
              {d.isVoid ? (
                <span className="text-muted-foreground">作废理由：{d.voidReason}</span>
              ) : (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => void doVoid('delivery', d.id)}
                  disabled={busy}
                >
                  作废
                </Button>
              )}
            </div>
          ))}
          {view.deliveries.length === 0 ? (
            <p className="text-muted-foreground text-xs">还没有到货记录。</p>
          ) : null}
        </div>
      </div>

      <div className="mt-5">
        <h4 className="text-sm font-medium">质检记录（{view.inspections.length}）</h4>
        <div className="mt-2 space-y-2">
          {view.inspections.map((q) => (
            <div
              key={q.id}
              className="border-border flex flex-wrap items-center gap-2 rounded border p-2 text-xs"
            >
              <span className="font-medium">{q.inspectionRef}</span>
              <Badge tone={rateTone(q.qualifiedRate)}>{rateLabel(q.qualifiedRate)}</Badge>
              {q.isVoid ? <Badge tone="danger">已作废</Badge> : null}
              <span className="text-muted-foreground">
                检验 {q.inspectedQuantity} / 合格 {q.qualifiedQuantity} · {dateText(q.inspectedAt)}
              </span>
              {q.isVoid ? (
                <span className="text-muted-foreground">作废理由：{q.voidReason}</span>
              ) : (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => void doVoid('inspection', q.id)}
                  disabled={busy}
                >
                  作废
                </Button>
              )}
            </div>
          ))}
          {view.inspections.length === 0 ? (
            <p className="text-muted-foreground text-xs">还没有质检记录。</p>
          ) : null}
        </div>
      </div>
    </SectionCard>
  );
}

function emptyDelivery(): VendorDeliveryInput {
  return {
    deliveryRef: '',
    skuId: null,
    purchaseUnitCode: null,
    quantity: '',
    amount: '',
    currency: '',
    orderedAt: '',
    dueDate: '',
    receivedDate: '',
    note: '',
  };
}

function emptyInspection(): VendorQualityInspectionInput {
  return {
    deliveryId: null,
    inspectionRef: '',
    inspectedQuantity: '',
    qualifiedQuantity: '',
    inspectedAt: '',
    note: '',
  };
}
