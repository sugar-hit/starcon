'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, RefreshCw, Search } from 'lucide-react';
import {
  Button,
  EmptyState,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  ElementSetCapabilities,
  ElementSetDetail,
  ElementSetFilterOptions,
  ElementSetPage,
  ElementSetRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  closeElementSet,
  destinationVersionLabel,
  declaredNameForCompare,
  declaredValueText,
  errorText,
  fetchElementSet,
  fetchElementSetCapabilities,
  fetchElementSetFilterOptions,
  fetchElementSetList,
  fetchUpstreamPreview,
  intervalText,
  missingCountLabel,
  missingHeadline,
  missingTone,
  preferenceLabel,
  preferenceTone,
  publishElementSet,
  quantityText,
  rowStateLabel,
  rowStateTone,
  salesTitleForCompare,
  statusLabel,
  summaryLine,
  unavailableDimensions,
  unitPriceText,
  type ElementSetTone,
} from './customs-elements-model';
import type { ElementSetUpstreamPreview } from '../../../../../packages/api-contracts/src/index';

const PAGE_SIZE = 20;

const TONE_CLASS: Record<ElementSetTone, string> = {
  ok: 'text-emerald-600 dark:text-emerald-400',
  warn: 'text-amber-600 dark:text-amber-400',
  bad: 'text-red-600 dark:text-red-400',
  muted: 'text-muted-foreground',
  wait: 'text-sky-600 dark:text-sky-400',
};

function Badge(props: { tone: ElementSetTone; children: React.ReactNode }) {
  return (
    <span className={`text-xs font-medium ${TONE_CLASS[props.tone]}`}>{props.children}</span>
  );
}

function SelectField(props: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
  hint?: string;
}) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={props.id} className="text-sm font-medium">
        {props.label}
      </label>
      <select
        id={props.id}
        className="border-input h-9 rounded-md border bg-transparent px-2 text-sm"
        value={props.value}
        onChange={(e) => props.onChange(e.target.value)}
      >
        {props.options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
      {props.hint ? <p className="text-muted-foreground text-xs">{props.hint}</p> : null}
    </div>
  );
}

/**
 * 10.5.1.2 申报要素工作区。
 *
 * <p>蓝图：「维护申报所需的品名、规格材质、用途、品牌、数量、单价、币种、产地、
 * 原产地规则（RCEP/优惠税率适用），支持按目的地国家差异化。」输出「完整申报要素集；
 * <b>缺失项提示</b>」。
 *
 * <p>区块顺序按现场判断：**① 口径与能力边界 → ② 筛选与汇总（目的地维度在最前）
 * → ③ 清单 → ④ 选中版本的详情（申报品名 / 销售标题并排 + 缺失项逐项）**。
 *
 * <p>把「申报品名 ≠ 销售标题」做成界面上的**并排对照**而不是一句提示语：
 * 销售标题固定带「不可直接作为申报品名」前缀出现，运营看到它出现在申报栏旁边
 * 才会去改 —— 混用是常见被退单原因，而它一旦发生是在报关之后才发现的。
 */
export function CustomsElementsWorkspace() {
  const [capabilities, setCapabilities] = useState<ElementSetCapabilities | null>(null);
  const [options, setOptions] = useState<ElementSetFilterOptions | null>(null);
  const [page, setPage] = useState<ElementSetPage | null>(null);
  const [detail, setDetail] = useState<ElementSetDetail | null>(null);
  const [upstream, setUpstream] = useState<ElementSetUpstreamPreview | null>(null);

  // 目的地国家是本模块的第一筛选维度：运营的工作单位就是「发往德国的这批货齐了吗」。
  const [destination, setDestination] = useState('');
  const [status, setStatus] = useState('');
  const [missingOnly, setMissingOnly] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [sort, setSort] = useState('UPDATED_AT_DESC');
  const [pageIndex, setPageIndex] = useState(0);
  const [selectedId, setSelectedId] = useState('');

  // 上游预览（3.7.1.2，跨服务）：建集/详情时取销售标题作对照基准。
  const [previewSku, setPreviewSku] = useState('');
  const [previewDestination, setPreviewDestination] = useState('');
  const [previewBusy, setPreviewBusy] = useState(false);
  const [previewError, setPreviewError] = useState('');

  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const [caps, opts, list] = await Promise.all([
        fetchElementSetCapabilities(),
        fetchElementSetFilterOptions(),
        fetchElementSetList({
          destination,
          status,
          // exactOptionalPropertyTypes: 不能写 `missing: missingOnly || undefined` ——
          // 那个键会真的出现且值为 undefined。只在为真时才带上它。
          ...(missingOnly ? { missing: true } : {}),
          keyword,
          sort,
          page: pageIndex,
          size: PAGE_SIZE,
        }),
      ]);
      setCapabilities(caps);
      setOptions(opts);
      setPage(list);
    } catch (error) {
      setLoadError(errorText(error));
    } finally {
      setLoading(false);
    }
  }, [destination, status, missingOnly, keyword, sort, pageIndex]);

  useEffect(() => {
    void load();
  }, [load]);

  // 选中行后取详情（详情才带缺失项逐项与补充要素项）。
  useEffect(() => {
    if (!selectedId) {
      setDetail(null);
      return;
    }
    let cancelled = false;
    fetchElementSet(selectedId)
      .then((d) => {
        if (!cancelled) setDetail(d);
      })
      .catch((error: unknown) => {
        if (!cancelled) setActionError(errorText(error));
      });
    return () => {
      cancelled = true;
    };
  }, [selectedId]);

  const gaps = useMemo(
    () => (capabilities ? unavailableDimensions(capabilities) : []),
    [capabilities],
  );

  const destinationOptions = useMemo(
    () => [
      { value: '', label: '全部目的地' },
      ...(options?.destinations ?? []).map((d) => ({ value: d.code, label: `${d.code}（${d.label}）` })),
    ],
    [options],
  );

  const statusOptions = useMemo(
    () => [
      { value: '', label: '全部状态' },
      ...(options?.statuses ?? []).map((s) => ({ value: s.code, label: s.label })),
    ],
    [options],
  );

  const sortOptions = useMemo(
    () => (options?.sorts ?? [{ key: 'UPDATED_AT_DESC', label: '最近更新' }]).map((s) => ({ value: s.key, label: s.label })),
    [options],
  );

  async function loadPreview() {
    if (!previewSku.trim() || !previewDestination.trim()) {
      setPreviewError('请同时填 SKU 标识与两位国家代码');
      return;
    }
    setPreviewBusy(true);
    setPreviewError('');
    try {
      setUpstream(
        await fetchUpstreamPreview({ skuId: previewSku.trim(), destination: previewDestination.trim() }),
      );
    } catch (error) {
      setUpstream(null);
      setPreviewError(errorText(error));
    } finally {
      setPreviewBusy(false);
    }
  }

  async function runAction(kind: 'publish' | 'close') {
    if (!detail) return;
    setBusy(true);
    setActionError('');
    try {
      // 乐观锁凭据用当前详情里的值：后到的写入必须撞 409 而不是静默覆盖申报事实。
      const version = 0;
      const saved =
        kind === 'publish'
          ? await publishElementSet(detail.row.id, { version })
          : await closeElementSet(detail.row.id, {
              version,
              effectiveTo: new Date(Date.now() + 30 * 24 * 3600 * 1000).toISOString(),
            });
      setDetail(saved.detail);
      await load();
    } catch (error) {
      setActionError(errorText(error));
    } finally {
      setBusy(false);
    }
  }

  const summary = page?.summary;

  return (
    <div className="flex flex-col gap-6 p-6">
      <PageHeader
        title="申报要素"
        description="按目的地国家维护清关口径：品名、规格材质、用途、品牌、数量、单价、币种、产地、原产地规则，并逐项提示缺失。"
      />

      {/* ① 口径与边界：先说清什么不归本模块，避免把「界面上没有」读成「不需要」 */}
      <SectionCard title="口径与边界">
        {capabilities ? (
          <div className="flex flex-col gap-3">
            <Notice tone="info" title="申报品名与销售标题是两件事">
              {capabilities.declaredNameNote}
            </Notice>
            <Notice tone="info" title="生效区间">
              {capabilities.effectiveIntervalNote}
            </Notice>
            <div className="text-muted-foreground text-xs">{capabilities.catalogBoundaryNote}</div>
            {gaps.length > 0 ? (
              <ul className="flex flex-col gap-1 text-xs">
                {gaps.map((d) => (
                  <li key={d.dimension}>
                    <span className="text-muted-foreground">· {d.dimension}：</span>
                    <span className={TONE_CLASS.muted}>不属本模块（{d.ownerModule}）</span>
                    <span className="text-muted-foreground"> — {d.note}</span>
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
        ) : loading ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : (
          <p className="text-muted-foreground text-sm">能力声明暂不可用</p>
        )}
      </SectionCard>

      {/* 上游预览：销售标题是「申报品名 ≠ 销售标题」的比对基准 */}
      <SectionCard title="上游合成申报资料（3.7.1.2 目的地报关版本）">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-2">
            <div className="flex flex-col gap-1">
              <label htmlFor="preview-sku" className="text-sm font-medium">
                SKU 标识
              </label>
              <Input
                id="preview-sku"
                value={previewSku}
                onChange={(e) => setPreviewSku(e.target.value)}
                placeholder="UUID"
                className="w-72"
              />
            </div>
            <div className="flex flex-col gap-1">
              <label htmlFor="preview-destination" className="text-sm font-medium">
                目的国
              </label>
              <Input
                id="preview-destination"
                value={previewDestination}
                onChange={(e) => setPreviewDestination(e.target.value.toUpperCase())}
                placeholder="DE"
                className="w-24"
              />
            </div>
            <Button onClick={() => void loadPreview()} disabled={previewBusy}>
              {previewBusy ? '取数中…' : '查看上游资料'}
            </Button>
          </div>
          {previewError ? (
            <Notice tone="danger" title="上游取数失败">
              {previewError}
            </Notice>
          ) : null}
          {upstream ? (
            <div className="flex flex-col gap-1 text-sm">
              <div>{salesTitleForCompare(upstream.salesTitle)}</div>
              <div className="text-muted-foreground text-xs">
                上游归类口径：税号 {upstream.hsCode || '未维护'} · 产地{' '}
                {upstream.originCountryCode || '未维护'} · 法定单位 {upstream.statutoryUnitCode || '未维护'} ·
                基础申报品名 {upstream.declaredName || '未维护'}（来源 {upstream.declaredNameSource || '—'}）
              </div>
            </div>
          ) : null}
        </div>
      </SectionCard>

      {/* ② 筛选与汇总 */}
      <SectionCard title="筛选与汇总">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-3">
            <SelectField
              id="filter-destination"
              label="目的地国家"
              value={destination}
              onChange={(v) => {
                setDestination(v);
                setPageIndex(0);
              }}
              options={destinationOptions}
              hint="申报要素按国家差异化，不接受「*」通配"
            />
            <SelectField
              id="filter-status"
              label="状态"
              value={status}
              onChange={(v) => {
                setStatus(v);
                setPageIndex(0);
              }}
              options={statusOptions}
            />
            <SelectField
              id="filter-sort"
              label="排序"
              value={sort}
              onChange={setSort}
              options={sortOptions}
            />
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={missingOnly}
                onChange={(e) => {
                  setMissingOnly(e.target.checked);
                  setPageIndex(0);
                }}
              />
              只看有缺失项的
            </label>
            <div className="flex flex-col gap-1">
              <label htmlFor="filter-keyword" className="text-sm font-medium">
                关键词
              </label>
              <div className="flex gap-1">
                <Input
                  id="filter-keyword"
                  value={keyword}
                  onChange={(e) => setKeyword(e.target.value)}
                  placeholder="品名 / 材质 / 品牌 / 协定 / SKU"
                />
                <Button
                  variant="outline"
                  onClick={() => {
                    setPageIndex(0);
                    void load();
                  }}
                >
                  <Search className="h-4 w-4" />
                </Button>
              </div>
            </div>
            <Button variant="outline" onClick={() => void load()}>
              <RefreshCw className="h-4 w-4" />
              刷新
            </Button>
          </div>

          {summary ? (
            <div className="grid grid-cols-2 gap-3 md:grid-cols-3 lg:grid-cols-6">
              <StatCard label="筛选后版本" value={summary.total} />
              <StatCard label="草稿" value={summary.draft} />
              <StatCard label="已发布" value={summary.published} />
              <StatCard label="此刻生效" value={summary.effectiveNow} />
              <StatCard label="有缺失" value={summary.missingAny} />
              <StatCard label="已发布但有缺失" value={summary.publishedMissing} />
            </div>
          ) : null}
          {page ? <p className="text-muted-foreground text-xs">{summaryLine(page)}</p> : null}
        </div>
      </SectionCard>

      {/* ③ 清单 */}
      <SectionCard title="申报要素集清单">
        {loadError ? (
          <Notice tone="danger" title="清单加载失败">
              {loadError}
            </Notice>
        ) : loading ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : !page || page.items.length === 0 ? (
          <EmptyState
            title="还没有申报要素集"
            description="先在上方取一份上游合成申报资料，确认销售标题与归类口径，再按清关口径建集。"
          />
        ) : (
          <div className="flex flex-col gap-2">
            <table className="w-full text-left text-sm">
              <thead className="text-muted-foreground text-xs">
                <tr>
                  <th className="py-2">目的地 / 版本</th>
                  <th className="py-2">申报品名</th>
                  <th className="py-2">品牌</th>
                  <th className="py-2">数量</th>
                  <th className="py-2">单价</th>
                  <th className="py-2">申报货值</th>
                  <th className="py-2">优惠</th>
                  <th className="py-2">状态</th>
                  <th className="py-2">缺失项</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((row: ElementSetRow) => (
                  <tr
                    key={row.id}
                    className={`border-t cursor-pointer ${selectedId === row.id ? 'bg-muted/50' : ''}`}
                    onClick={() => setSelectedId(row.id)}
                  >
                    <td className="py-2">{destinationVersionLabel(row)}</td>
                    <td className="py-2">{row.declaredName || '未填'}</td>
                    <td className="py-2">{row.brand || '未填'}</td>
                    <td className="py-2">{quantityText(row.quantity)}</td>
                    <td className="py-2">{unitPriceText(row.unitPrice, row.currency)}</td>
                    <td className="py-2">{declaredValueText(row)}</td>
                    <td className="py-2">
                      <Badge tone={preferenceTone(row)}>{preferenceLabel(row)}</Badge>
                    </td>
                    <td className="py-2">
                      <Badge tone={rowStateTone(row)}>{rowStateLabel(row)}</Badge>
                    </td>
                    <td className="py-2">
                      <Badge tone={missingTone(row)}>{missingCountLabel(row)}</Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="flex items-center justify-between pt-2 text-xs">
              <span className="text-muted-foreground">{page.sortNote}</span>
              <span className="flex gap-1">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page.page <= 0}
                  onClick={() => setPageIndex((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page.page + 1 >= page.totalPages}
                  onClick={() => setPageIndex((p) => p + 1)}
                >
                  下一页
                </Button>
              </span>
            </div>
          </div>
        )}
      </SectionCard>

      {/* ④ 详情：申报品名 / 销售标题并排 + 缺失项逐项 */}
      {selectedId && detail ? (
        <SectionCard
          title={`详情 · ${destinationVersionLabel(detail.row)}`}
          description={intervalText(detail.row.effectiveFrom, detail.row.effectiveTo)}
        >
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-2 rounded-md border p-3">
              <div className="text-sm font-medium">
                <AlertTriangle className="mr-1 inline h-4 w-4 text-amber-500" />
                申报品名 ≠ 销售标题
              </div>
              <div className="flex flex-col gap-1 text-sm">
                <div className="text-emerald-700 dark:text-emerald-400">
                  {declaredNameForCompare(detail.row.declaredName)}
                </div>
                <div className="text-muted-foreground">
                  {salesTitleForCompare(detail.salesTitleSnapshot)}
                </div>
              </div>
            </div>

            <div className="flex flex-col gap-2">
              <div className="text-sm font-medium">
                缺失项提示 · {missingHeadline(detail)}
              </div>
              {detail.missing.length === 0 ? (
                <Notice tone="success" title="齐备">
                  九件本体与必填补充申报要素均已填写，可以发布。
                </Notice>
              ) : (
                <ul className="flex flex-col gap-2">
                  {detail.missing.map((m) => (
                    <li
                      key={`${m.field}-${m.label}`}
                      className={`rounded-md border p-2 text-sm ${
                        m.severity === 'BLOCKER' ? 'border-red-300' : 'border-amber-300'
                      }`}
                    >
                      <div className="flex items-center gap-2">
                        <Badge tone={m.severity === 'BLOCKER' ? 'bad' : 'warn'}>
                          {m.severity === 'BLOCKER' ? '阻断发布' : '口径提示'}
                        </Badge>
                        <span className="font-medium">{m.label}</span>
                      </div>
                      <p className="text-muted-foreground mt-1 text-xs">{m.reason}</p>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {detail.items.length > 0 ? (
              <div className="flex flex-col gap-1">
                <div className="text-sm font-medium">补充申报要素（有序）</div>
                <ul className="text-muted-foreground text-xs">
                  {detail.items.map((i) => (
                    <li key={i.id}>
                      {i.ordinal}. {i.elementCode} {i.elementName} = {i.elementValue || '（未填）'}
                      {i.required ? ' · 必填' : ''}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}

            {actionError ? (
              <Notice tone="danger" title="操作未完成">
                {actionError}
              </Notice>
            ) : null}

            <div className="flex flex-wrap gap-2">
              {detail.actions.includes('PUBLISH') ? (
                <Button disabled={busy} onClick={() => void runAction('publish')}>
                  发布
                </Button>
              ) : null}
              {detail.actions.includes('CLOSE') ? (
                <Button variant="outline" disabled={busy} onClick={() => void runAction('close')}>
                  向前关闭生效区间（30 天后）
                </Button>
              ) : null}
              <span className="text-muted-foreground self-center text-xs">
                当前状态 {statusLabel(detail.row.status)} · 可执行动作 {detail.actions.join(' / ') || '无'}
              </span>
            </div>

            <p className="text-muted-foreground text-xs">{detail.boundaryNote}</p>
          </div>
        </SectionCard>
      ) : null}
    </div>
  );
}
