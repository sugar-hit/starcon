'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { usePrefillKeyword } from '@/lib/global-search-handoff';
import Link from 'next/link';
import { Badge, Button } from '@starcon/ui';
import {
  Barcode,
  Boxes,
  Download,
  GitBranch,
  Package,
  RefreshCw,
  Scale,
  Search,
  Tags,
  TriangleAlert,
} from 'lucide-react';
import type {
  SkuBatchResult,
  SkuFieldInfo,
  SkuListCapabilities,
  SkuListRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  emptySkuFilters,
  fetchSkuCapabilities,
  fetchSkuPage,
  fieldNote,
  hasActiveFilters,
  isFieldAvailable,
  isProjectionRejection,
  projectionText,
  runSkuBatch,
  skuBatchActions,
  skuExportUrl,
  skuSortOptions,
  skuStatuses,
  skuStatusOptions,
  specText,
  type SkuFilters,
} from './sku-list-model';
import { control } from './fields';
import { useIdentity } from '../iam/identity-shell';

const PAGE_SIZE = 50;

/** 投影列：字段 code 固定，标题与可用性来自后端能力声明。 */
const PROJECTION_COLUMNS: { code: string; fallbackLabel: string }[] = [
  { code: 'ON_HAND', fallbackLabel: '在仓' },
  { code: 'AVAILABLE_QTY', fallbackLabel: '可售' },
  { code: 'SALE_PRICE', fallbackLabel: '售价' },
  { code: 'LISTED_PLATFORMS', fallbackLabel: '在售平台' },
];

/**
 * 3.1.2.1 SKU 列表工作区。
 *
 * <p>这是仓储、采购、财务共用的事实入口，所以页面上必须把"哪些数是真数据、哪些还没接入"
 * 当成一等信息展示：未接入的列写「未接入」，对应筛选控件禁用并说明归哪个服务。
 * 把没接入的库存画成 0，比留空危险得多——运营会以为真的断货了。
 */
export function SkuListWorkspace() {
  const { session } = useIdentity();
  const [filters, setFilters] = useState<SkuFilters>(emptySkuFilters);
  const [applied, setApplied] = useState<SkuFilters>(emptySkuFilters);
  // 1.2.1.1 全局搜索跳过来时带 ?keyword=：把它回填进关键字筛选器并立即生效。
  const [prefillKeyword] = usePrefillKeyword();
  const [rows, setRows] = useState<SkuListRow[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [unwired, setUnwired] = useState('');
  const [capabilities, setCapabilities] = useState<SkuListCapabilities | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [batchOpen, setBatchOpen] = useState(false);
  const readable = session.permissions.includes('catalog.sku.read');
  const writable = session.permissions.includes('catalog.sku.update');

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    setUnwired('');
    try {
      const result = await fetchSkuPage(applied, page, PAGE_SIZE);
      setRows(result.items);
      setTotal(result.total);
      // 筛选条件改变后旧的勾选已无意义，留着等于让人对看不见的行做批量操作。
      setSelected([]);
    } catch (e) {
      setRows([]);
      setTotal(0);
      setSelected([]);
      if (isProjectionRejection(e)) setUnwired(e instanceof Error ? e.message : '');
      else setError(e instanceof Error ? e.message : 'SKU 列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [applied, page, readable]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!readable) return;
    // 能力声明是只读元数据：取不到就把控件按"未接入"处理，不阻塞列表本身。
    fetchSkuCapabilities()
      .then(setCapabilities)
      .catch(() => setCapabilities(null));
  }, [readable]);

  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const update = (patch: Partial<SkuFilters>) =>
    setFilters((current) => ({ ...current, ...patch }));

  // 回填只做一次：用户随后自己改筛选器时不再被 URL 里的旧值覆盖回去。
  const prefilled = useRef(false);
  useEffect(() => {
    if (prefilled.current || !prefillKeyword) return;
    prefilled.current = true;
    setFilters((current) => ({ ...current, keyword: prefillKeyword }));
    setApplied((current) => ({ ...current, keyword: prefillKeyword }));
    setPage(0);
  }, [prefillKeyword]);
  const allSelected = rows.length > 0 && selected.length === rows.length;

  const toggleStatus = (value: string) => {
    update({
      statuses: filters.statuses.includes(value)
        ? filters.statuses.filter((s) => s !== value)
        : [...filters.statuses, value],
    });
  };

  const toggleRow = (id: string) =>
    setSelected((current) =>
      current.includes(id) ? current.filter((x) => x !== id) : [...current, id],
    );

  if (!readable)
    return (
      <p className="rounded-lg border p-6 text-sm">当前角色没有 SKU 查看权限，请联系租户管理员。</p>
    );

  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-xl font-semibold">SKU 列表</h2>
          <p className="text-muted-foreground mt-1 text-sm">
            全租户 SKU 明细：编码、规格、条码与状态。库存、售价、成本与在售平台由 07 / 05 / 06 / 04
            各自负责，未接入的列显示「未接入」而不是 0。
          </p>
        </div>
        <div className="flex items-center gap-2">
          {/* 3.1.2.3 编码与条码：列表页只读编码与条码，维护入口在独立工作区。 */}
          <Link
            href="/products/sku/code"
            className="border-border hover:bg-accent inline-flex h-8 items-center gap-1.5 rounded-md border px-3 text-sm"
          >
            <Barcode className="h-4 w-4" />
            编码与条码
          </Link>
          {/* 3.1.2.4 单位与换算：维护入口在独立工作区，列表页只读基础单位。 */}
          <Link
            href="/products/sku/units"
            className="border-border hover:bg-accent inline-flex h-8 items-center gap-1.5 rounded-md border px-3 text-sm"
          >
            <Scale className="h-4 w-4" />
            单位与换算
          </Link>
          {/* 3.1.3.1 规格轴定义：租户级规格轴字典与 SPU 绑定，维护入口在独立工作区。 */}
          <Link
            href="/products/sku/spec-axis"
            className="border-border hover:bg-accent inline-flex h-8 items-center gap-1.5 rounded-md border px-3 text-sm"
          >
            <GitBranch className="h-4 w-4" />
            规格轴定义
          </Link>
          {/* 3.2.1.1 属性定义：租户级属性字典与类目绑定规则，商品的属性值仍在商品档案里维护。 */}
          <Link
            href="/products/attributes"
            className="border-border hover:bg-accent inline-flex h-8 items-center gap-1.5 rounded-md border px-3 text-sm"
          >
            <Tags className="h-4 w-4" />
            属性定义
          </Link>
          {/* 3.1.3.2 组合唯一性校验：组合重复检测、矩阵预校验与待建 SKU 草稿，独立工作区。 */}
          <Link
            href="/products/sku/variant-combination"
            className="border-border hover:bg-accent inline-flex h-8 items-center gap-1.5 rounded-md border px-3 text-sm"
          >
            <Boxes className="h-4 w-4" />
            组合唯一性校验
          </Link>
          {/* 导出走普通链接下载，不走 api()：它是 CSV 而非 JSON。 */}
          <a
            href={skuExportUrl(applied)}
            download
            className="border-border hover:bg-accent inline-flex h-8 items-center gap-1.5 rounded-md border px-3 text-sm"
          >
            <Download className="h-4 w-4" />
            导出 CSV
          </a>
          <Button variant="ghost" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
        </div>
      </div>

      {capabilities && <UnwiredNotice capabilities={capabilities} />}

      <form
        className="bg-card space-y-3 rounded-lg border p-4"
        onSubmit={(event) => {
          event.preventDefault();
          setPage(0);
          setApplied(filters);
        }}
      >
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <input
              value={filters.keyword}
              onChange={(e) => update({ keyword: e.target.value })}
              placeholder="SKU 编码、名称或条码"
              className={control}
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">分类</span>
            <input
              value={filters.category}
              onChange={(e) => update({ category: e.target.value })}
              placeholder="SPU 所属分类"
              className={control}
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">条码</span>
            <input
              value={filters.barcode}
              onChange={(e) => update({ barcode: e.target.value })}
              placeholder="精确匹配"
              className={control}
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">规格</span>
            <input
              value={filters.spec}
              onChange={(e) => update({ spec: e.target.value })}
              placeholder="规格轴取值，如 黑色"
              className={control}
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">排序字段</span>
            <select
              value={filters.sort}
              onChange={(e) => update({ sort: e.target.value })}
              className={control}
            >
              {skuSortOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">排序方向</span>
            <select
              value={filters.order}
              onChange={(e) => update({ order: e.target.value })}
              className={control}
            >
              <option value="desc">降序</option>
              <option value="asc">升序</option>
            </select>
          </label>
          <ProjectedFilter
            label="在仓数量下限"
            value={filters.onHandMin}
            field="ON_HAND"
            capabilities={capabilities}
            onChange={(v) => update({ onHandMin: v })}
          />
          <ProjectedFilter
            label="售价下限"
            value={filters.priceMin}
            field="SALE_PRICE"
            capabilities={capabilities}
            onChange={(v) => update({ priceMin: v })}
          />
          <ProjectedFilter
            label="售价上限"
            value={filters.priceMax}
            field="SALE_PRICE"
            capabilities={capabilities}
            onChange={(v) => update({ priceMax: v })}
          />
          <ProjectedFilter
            label="是否缺货"
            value={filters.outOfStock}
            field="AVAILABLE_QTY"
            capabilities={capabilities}
            select
            onChange={(v) => update({ outOfStock: v })}
          />
          <ProjectedFilter
            label="是否在售"
            value={filters.onSale}
            field="LISTED_PLATFORMS"
            capabilities={capabilities}
            select
            onChange={(v) => update({ onSale: v })}
          />
        </div>

        <fieldset className="flex flex-wrap items-center gap-3">
          <legend className="text-muted-foreground text-sm">状态</legend>
          {skuStatusOptions.map((option) => (
            <label key={option.value} className="flex items-center gap-1.5 text-sm">
              <input
                type="checkbox"
                checked={filters.statuses.includes(option.value)}
                onChange={() => toggleStatus(option.value)}
              />
              {option.label}
            </label>
          ))}
        </fieldset>

        <div className="flex flex-wrap items-center gap-2">
          <Button type="submit" size="sm">
            <Search className="h-4 w-4" />
            检索
          </Button>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => {
              setFilters(emptySkuFilters);
              setApplied(emptySkuFilters);
              setPage(0);
            }}
          >
            重置
          </Button>
        </div>
      </form>

      {unwired && (
        <div
          role="alert"
          className="border-warning/30 bg-warning/5 text-warning rounded-md border p-3 text-sm"
        >
          <p className="flex items-center gap-2 font-medium">
            <TriangleAlert className="h-4 w-4" />
            这些筛选条件暂时无法使用
          </p>
          <p className="mt-1">{unwired}</p>
          <p className="mt-1">
            条件没有被忽略，而是被明确拒绝——否则你看到的会是「没筛过的全量数据」。
          </p>
        </div>
      )}

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

      {loading ? (
        <p role="status" className="text-muted-foreground text-sm">
          正在加载 SKU…
        </p>
      ) : rows.length === 0 ? (
        <div className="bg-card rounded-lg border p-8 text-center">
          <Package className="text-muted-foreground mx-auto mb-3 h-7 w-7" />
          <p className="font-medium">
            {hasActiveFilters(applied) ? '没有匹配的 SKU' : '还没有 SKU'}
          </p>
          <p className="text-muted-foreground mt-2">
            {hasActiveFilters(applied)
              ? '试试其他关键词，或清空筛选条件。'
              : '在商品工作区配置规格轴后创建 SKU，这里就会列出全租户的规格明细。'}
          </p>
        </div>
      ) : (
        <>
          {writable && (
            <div className="flex flex-wrap items-center justify-between gap-3">
              <p className="text-muted-foreground text-sm">已选择 {selected.length} 项</p>
              <div className="flex flex-wrap items-center gap-2">
                {skuBatchActions.map((action) => (
                  <Button
                    key={action.value}
                    size="sm"
                    variant="outline"
                    disabled={selected.length === 0}
                    onClick={() => setBatchOpen(true)}
                    title={
                      action.listing ? '依赖 04 刊登服务，尚未接入；提交后端会明确拒绝' : undefined
                    }
                  >
                    {action.label}
                  </Button>
                ))}
              </div>
            </div>
          )}

          <div className="bg-card overflow-x-auto rounded-lg border">
            <table className="w-full text-left text-sm">
              <thead className="bg-muted/50">
                <tr>
                  {writable && (
                    <th className="w-10 px-4 py-3">
                      <input
                        type="checkbox"
                        aria-label="全选当前页"
                        checked={allSelected}
                        onChange={() => setSelected(allSelected ? [] : rows.map((r) => r.id))}
                      />
                    </th>
                  )}
                  {[
                    'SKU / 编码',
                    '所属 SPU / 分类',
                    '规格',
                    '条码',
                    '状态',
                    ...PROJECTION_COLUMNS.map((c) => c.fallbackLabel),
                  ].map((h) => (
                    <th key={h} className="whitespace-nowrap px-4 py-3 font-medium">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} className="hover:bg-muted/30 border-t">
                    {writable && (
                      <td className="px-4 py-3">
                        <input
                          type="checkbox"
                          aria-label={`选择 ${row.code}`}
                          checked={selected.includes(row.id)}
                          onChange={() => toggleRow(row.id)}
                        />
                      </td>
                    )}
                    <td className="px-4 py-3">
                      {/* 编码点进 3.1.2.2 详情：列表是共用的事实入口，详情才是完整档案。 */}
                      <Link
                        href={`/products/sku/${row.id}`}
                        className="font-medium hover:underline"
                      >
                        {row.name}
                      </Link>
                      <p className="text-muted-foreground text-xs">{row.code}</p>
                    </td>
                    <td className="px-4 py-3">
                      <p className="break-all">{row.spuName}</p>
                      <p className="text-muted-foreground text-xs">
                        {row.spuCode} · {row.category || '未分类'}
                      </p>
                    </td>
                    <td className="px-4 py-3">{specText(row.specs, row.variantSignature)}</td>
                    <td className="px-4 py-3 tabular-nums">{row.barcode || '—'}</td>
                    <td className="whitespace-nowrap px-4 py-3">{skuStatusLabel(row.status)}</td>
                    {PROJECTION_COLUMNS.map((column) => {
                      const cell = row.projections.find((p) => p.field === column.code);
                      return (
                        <td key={column.code} className="whitespace-nowrap px-4 py-3 tabular-nums">
                          {cell ? (
                            cell.available ? (
                              projectionText(cell)
                            ) : (
                              <span className="text-muted-foreground" title={cell.note}>
                                未接入
                              </span>
                            )
                          ) : (
                            '—'
                          )}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="flex items-center justify-between gap-3 text-sm">
            <p className="text-muted-foreground">
              共 {total} 条，第 {page + 1} / {pages} 页
            </p>
            <div className="flex items-center gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={page === 0}
                onClick={() => setPage((current) => Math.max(0, current - 1))}
              >
                上一页
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={page + 1 >= pages}
                onClick={() => setPage((current) => current + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        </>
      )}

      {batchOpen && (
        <BatchDialog
          capabilities={capabilities}
          selected={selected}
          rows={rows}
          onClose={() => setBatchOpen(false)}
          onDone={() => {
            setBatchOpen(false);
            setSelected([]);
            void load();
          }}
        />
      )}
    </section>
  );
}

function skuStatusLabel(status: string) {
  // 状态与语气挂钩而不是颜色：启用=成功，停用/归档=中性，草稿=信息。
  const tone = status === 'ACTIVE' ? 'success' : status === 'DRAFT' ? 'info' : 'neutral';
  return <Badge tone={tone}>{skuStatuses[status] ?? status}</Badge>;
}

/** 能力声明里的「哪些列还没接入」摘要。放在筛选区上方，让人先知道边界再筛。 */
function UnwiredNotice({ capabilities }: { capabilities: SkuListCapabilities }) {
  const unwired = capabilities.fields.filter((f: SkuFieldInfo) => !f.available);
  if (!unwired.length) return null;
  return (
    <div className="bg-card text-muted-foreground rounded-lg border p-3 text-sm">
      <p className="font-medium">以下字段由其他系统负责，尚未接入</p>
      <ul className="mt-1 flex flex-wrap gap-x-4 gap-y-1">
        {unwired.map((f: SkuFieldInfo) => (
          <li key={f.code} title={f.note}>
            {f.label}（{f.source}）
          </li>
        ))}
      </ul>
      <p className="mt-1 text-xs">
        这些列显示「未接入」，不是 0。接入对应服务后会自动显示真实值，筛选条件也会随之可用。
      </p>
    </div>
  );
}

/**
 * 依赖未接入投影的筛选控件。
 *
 * <p>未接入时**禁用**并写清原因，而不是让用户填一个必然被后端拒绝的值。
 */
function ProjectedFilter({
  label,
  value,
  field,
  capabilities,
  select = false,
  onChange,
}: {
  label: string;
  value: string;
  field: string;
  capabilities: SkuListCapabilities | null;
  select?: boolean;
  onChange: (value: string) => void;
}) {
  const available = capabilities === null || isFieldAvailable(capabilities, field);
  const note = capabilities === null ? '' : fieldNote(capabilities, field);
  return (
    <label className="space-y-1 text-sm">
      <span className="text-muted-foreground">{label}</span>
      {select ? (
        <select
          value={value}
          disabled={!available}
          onChange={(e) => onChange(e.target.value)}
          className={control}
        >
          <option value="">全部</option>
          <option value="true">是</option>
          <option value="false">否</option>
        </select>
      ) : (
        <input
          value={value}
          disabled={!available}
          onChange={(e) => onChange(e.target.value)}
          className={control}
        />
      )}
      {!available && <span className="text-warning block text-xs">{note}</span>}
    </label>
  );
}

/**
 * 批量操作对话框：先预览影响范围，再确认执行。
 *
 * <p>分配与生成刊登按钮保留但会在提交时被后端拒绝——这里如实说明依赖 04 刊登，
 * 不做出"点了成功但什么都没发生"的假动作。
 */
function BatchDialog({
  capabilities,
  selected,
  rows,
  onClose,
  onDone,
}: {
  capabilities: SkuListCapabilities | null;
  selected: string[];
  rows: SkuListRow[];
  onClose: () => void;
  onDone: () => void;
}) {
  const [action, setAction] = useState('STOP_SELLING');
  const [targetStatus, setTargetStatus] = useState('INACTIVE');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState<SkuBatchResult | null>(null);

  const listing = skuBatchActions.find((a) => a.value === action)?.listing ?? false;
  const listingAvailable =
    capabilities === null || isFieldAvailable(capabilities, 'LISTED_PLATFORMS');

  const submit = async (dryRun: boolean) => {
    setBusy(true);
    setError('');
    try {
      setResult(
        await runSkuBatch({
          action,
          skuIds: selected,
          targetStatus: action === 'STOP_SELLING' ? 'INACTIVE' : targetStatus,
          reason,
          occurredAt: null,
          dryRun,
        }),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '批量操作失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="bg-card space-y-3 rounded-lg border p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="font-semibold">批量操作（已选 {selected.length} 项）</h3>
        <Button variant="ghost" size="sm" onClick={onClose}>
          关闭
        </Button>
      </div>

      {listing && !listingAvailable && (
        <p className="text-warning text-sm">{fieldNote(capabilities, 'LISTED_PLATFORMS')}</p>
      )}

      <div className="grid gap-3 sm:grid-cols-3">
        <label className="space-y-1 text-sm">
          <span className="text-muted-foreground">操作</span>
          <select value={action} onChange={(e) => setAction(e.target.value)} className={control}>
            {skuBatchActions.map((a) => (
              <option key={a.value} value={a.value}>
                {a.label}
              </option>
            ))}
          </select>
        </label>
        {action === 'SET_STATUS' && (
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">目标状态</span>
            <select
              value={targetStatus}
              onChange={(e) => setTargetStatus(e.target.value)}
              className={control}
            >
              {skuStatusOptions
                .filter((o) => o.value !== 'DRAFT')
                .map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
            </select>
          </label>
        )}
        <label className="space-y-1 text-sm sm:col-span-2">
          <span className="text-muted-foreground">原因（必填）</span>
          <input value={reason} onChange={(e) => setReason(e.target.value)} className={control} />
        </label>
      </div>

      {error && (
        <p role="alert" className="text-destructive text-sm">
          {error}
        </p>
      )}

      {result && (
        <div className="space-y-2 text-sm">
          <p>
            {result.dryRun ? '预览：' : '已执行：'}命中 {result.matchedCount} 项，将变更{' '}
            {result.appliedCount} 项，跳过 {result.skippedCount} 项。
          </p>
          <ul className="text-muted-foreground max-h-40 space-y-1 overflow-y-auto text-xs">
            {result.items.map((item) => (
              <li key={item.skuId}>
                {item.code || item.skuId} · {item.outcome === 'APPLIED' ? '变更' : '跳过'} ·{' '}
                {item.reason}
              </li>
            ))}
          </ul>
          {!result.dryRun && <p className="text-success">批量操作已完成，列表已刷新。</p>}
        </div>
      )}

      <div className="flex flex-wrap gap-2">
        <Button variant="outline" size="sm" disabled={busy} onClick={() => void submit(true)}>
          预览影响范围
        </Button>
        <Button
          size="sm"
          disabled={busy || reason.trim() === '' || result?.dryRun === false}
          onClick={() => void submit(false)}
        >
          确认执行
        </Button>
      </div>
    </div>
  );
}
