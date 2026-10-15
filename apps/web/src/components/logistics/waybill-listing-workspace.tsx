'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Filter, Printer, RefreshCw, Search, Truck, XCircle } from 'lucide-react';
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
  WaybillBulkActionResult,
  WaybillListFilterOptions,
  WaybillListPage,
  WaybillListRow,
  WaybillListingCapabilities,
} from '../../../../../packages/api-contracts/src/index';
import {
  bulkApplyWaybill,
  bulkCancelWaybill,
  bulkPrintWaybill,
  bulkSummaryLine,
  displayStatusTone,
  errorText,
  fetchListingCapabilities,
  fetchWaybillFilterOptions,
  fetchWaybillList,
  freightCell,
  issuedDisclaimer,
  printable,
  summaryLine,
  timeText,
  trackingGapWarning,
  unavailableStatuses,
  weightText,
  type WaybillTone,
} from './waybill-listing-model';

const PAGE_SIZE = 20;

const TONE_CLASS: Record<WaybillTone, string> = {
  ok: 'text-emerald-600 dark:text-emerald-400',
  warn: 'text-amber-600 dark:text-amber-400',
  bad: 'text-red-600 dark:text-red-400',
  muted: 'text-muted-foreground',
  wait: 'text-sky-600 dark:text-sky-400',
};

function SelectField(props: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string; disabled?: boolean }[];
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
        {props.options.map((option) => (
          <option key={option.value} value={option.value} disabled={option.disabled}>
            {option.label}
          </option>
        ))}
      </select>
      {props.hint ? <p className="text-muted-foreground text-xs">{props.hint}</p> : null}
    </div>
  );
}

/**
 * 10.2.1.1 运单列表工作区。
 *
 * <p>蓝图：「运单主列表：运单号、订单/包裹、承运商渠道、发货仓、状态、下单时间、重量、
 * 运费。」输入是「筛选条件（渠道、状态、时间、目的国）」，输出是「列表 + 批量操作
 * （申请运单、取消、打印）」。
 *
 * <p>四个区块按现场的判断顺序排：
 * <b>① 能力与缺口 → ② 筛选与汇总 → ③ 运单清单（含批量操作）</b>。
 *
 * <p>把能力放在最前不是凑数：蓝图那七个展示态里有<b>四个</b>（已揽收/运输中/已签收/
 * 异常）没有上游数据源，而加粗句要求「运单状态从承运商侧获取为准」会让运营理所当然地
 * 以为清单上会显示在途状态。不先说清，就会出现「已取号 = 一切正常」的误读。
 *
 * <p>路由取 {@code /logistics/shipments} —— 这正是 10.2.1.2 的页面注释里为
 * 10.2.1.1 预留的路径（「运单列表会把 /shipments 长成完整列表页」），
 * 与 {@code /logistics/waybills}（去申请）分得开。
 */
export function WaybillListingWorkspace() {
  const [capabilities, setCapabilities] = useState<WaybillListingCapabilities | null>(null);
  const [options, setOptions] = useState<WaybillListFilterOptions | null>(null);
  const [page, setPage] = useState<WaybillListPage | null>(null);

  const [carrierCode, setCarrierCode] = useState('');
  const [carrierChannel, setCarrierChannel] = useState('');
  const [status, setStatus] = useState('');
  const [destinationCountry, setDestinationCountry] = useState('');
  const [freight, setFreight] = useState('');
  const [keyword, setKeyword] = useState('');
  const [sort, setSort] = useState('CREATED_AT_DESC');
  const [pageIndex, setPageIndex] = useState(0);

  const [selected, setSelected] = useState<string[]>([]);
  const [cancelReason, setCancelReason] = useState('');
  const [printerTarget, setPrinterTarget] = useState('');
  const [actionResult, setActionResult] = useState<WaybillBulkActionResult | null>(null);
  const [actionError, setActionError] = useState('');
  const [busy, setBusy] = useState(false);

  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const [caps, opts, list] = await Promise.all([
        fetchListingCapabilities(),
        fetchWaybillFilterOptions(),
        fetchWaybillList({
          carrierCode,
          carrierChannel,
          status,
          destinationCountry,
          freight,
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
  }, [carrierCode, carrierChannel, status, destinationCountry, freight, keyword, sort, pageIndex]);

  useEffect(() => {
    void load();
  }, [load]);

  const gaps = useMemo(() => (capabilities ? capabilities.dimensions.filter((d) => !d.available) : []), [
    capabilities,
  ]);

  const missingStatuses = useMemo(
    () => (options ? unavailableStatuses(options.statuses) : []),
    [options],
  );

  const selectedRows = useMemo(
    () => (page ? page.items.filter((r) => selected.includes(r.id)) : []),
    [page, selected],
  );

  const toggle = (id: string) =>
    setSelected((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));

  async function runAction(action: 'apply' | 'cancel' | 'print') {
    if (selected.length === 0) {
      setActionError('请先勾选要操作的运单');
      return;
    }
    setBusy(true);
    setActionError('');
    try {
      const result =
        action === 'apply'
          ? await bulkApplyWaybill(selected, '运单列表批量申请')
          : action === 'cancel'
            ? await bulkCancelWaybill(selected, cancelReason)
            : await bulkPrintWaybill(selected, printerTarget, '运单列表批量打印');
      setActionResult(result);
      setSelected([]);
      await load();
    } catch (error) {
      setActionError(errorText(error));
    } finally {
      setBusy(false);
    }
  }

  const summary = page?.summary;

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="运单列表"
        description="运单号、订单/包裹、承运商渠道、发货仓、状态、下单时间、重量、运费。内部状态仅作展示，运单状态以承运商侧为准。"
      />

      {/* ① 能力与缺口 */}
      <SectionCard title="能力与缺口" description="哪些维度真能用、哪些没有上游">
        {loading && !capabilities ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : (
          <>
            <div className="flex flex-col gap-2">
              {gaps.map((d) => (
                <Notice key={d.dimension} tone="warning" title={d.description}>
                  {d.source}
                  <br />
                  归 <code>{d.ownerModule}</code>；{d.impact}
                </Notice>
              ))}
              {gaps.length === 0 && <p className="text-sm">当前没有缺口。</p>}
            </div>
            {missingStatuses.length > 0 && (
              <Notice tone="warning" title="这些状态当前没有数据源">
                以下状态<b>当前没有数据源</b>，在筛选里不可选（不是权限问题，是上游未交付）：
                {missingStatuses.map((s) => `${s.label}（${s.ownerModule}）`).join('、')}
              </Notice>
            )}
          </>
        )}
      </SectionCard>

      {/* ② 筛选与汇总 */}
      <SectionCard
        title="筛选与汇总"
        description="汇总覆盖筛选后的全量，与分页无关"
        extra={
          <Button variant="outline" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      >
        <div className="grid gap-3 md:grid-cols-3 lg:grid-cols-6">
          <SelectField
            id="carrier"
            label="承运商"
            value={carrierCode}
            onChange={(v) => {
              setCarrierCode(v);
              setPageIndex(0);
            }}
            options={[
              { value: '', label: '全部承运商' },
              ...(options?.carriers ?? []).map((c) => ({
                value: c.carrierCode,
                label: `${c.carrierCode}（${c.waybills}）`,
              })),
            ]}
          />
          <SelectField
            id="channel"
            label="承运渠道"
            value={carrierChannel}
            onChange={(v) => {
              setCarrierChannel(v);
              setPageIndex(0);
            }}
            options={[
              { value: '', label: '全部渠道' },
              ...(options?.carrierChannels ?? []).map((c) => ({ value: c, label: c })),
            ]}
          />
          <SelectField
            id="status"
            label="运单状态"
            value={status}
            onChange={(v) => {
              setStatus(v);
              setPageIndex(0);
            }}
            options={[
              { value: '', label: '全部状态' },
              ...(options?.statuses ?? []).map((s) => ({
                value: s.status,
                label: s.available ? s.label : `${s.label}（无数据源 · ${s.ownerModule}）`,
                disabled: !s.available,
              })),
            ]}
            hint="已揽收/运输中/已签收/承运商异常无上游数据源，已禁用"
          />
          <SelectField
            id="country"
            label="目的国"
            value={destinationCountry}
            onChange={(v) => {
              setDestinationCountry(v);
              setPageIndex(0);
            }}
            options={[
              { value: '', label: '全部国家' },
              ...(options?.destinationCountries ?? []).map((c) => ({ value: c, label: c })),
            ]}
          />
          <SelectField
            id="freight"
            label="运费登记"
            value={freight}
            onChange={(v) => {
              setFreight(v);
              setPageIndex(0);
            }}
            options={[
              { value: '', label: '不限' },
              { value: 'REGISTERED', label: '已登记' },
              { value: 'NOT_REGISTERED', label: '未登记' },
            ]}
          />
          <SelectField
            id="sort"
            label="排序"
            value={sort}
            onChange={(v) => {
              setSort(v);
              setPageIndex(0);
            }}
            options={(options?.sortOptions ?? []).map((s) => ({ value: s, label: s }))}
          />
        </div>

        <FormField
          id="keyword"
          label="关键字"
          hint="运单号 / 申请单号 / 销售订单号 / 出库单号 / 承运商"
        >
          <div className="flex gap-2">
            <Input
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="输入关键字后回车"
            />
            <Button
              onClick={() => {
                setPageIndex(0);
                void load();
              }}
            >
              <Search className="mr-2 h-4 w-4" />
              搜索
            </Button>
          </div>
        </FormField>

        {summary && (
          <>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <StatCard label="筛选结果" value={summary.total} unit="不受分页影响" />
              <StatCard
                label="已取号 / 无承运商轨迹"
                value={`${summary.numberIssued} / ${summary.issuedWithoutCarrierTracking}`}
                unit="已取号 ≠ 在途中"
              />
              <StatCard
                label="运费已登记 / 未登记"
                value={`${summary.freightRegistered} / ${summary.freightNotRegistered}`}
                unit="未登记 ≠ 0 运费"
              />
              <StatCard
                label="承运商 / 目的国"
                value={`${summary.distinctCarriers} / ${summary.distinctDestinationCountries}`}
                unit="来自真实数据 DISTINCT"
              />
            </div>
            <p className="text-muted-foreground text-xs">{summaryLine(summary)}</p>
            {trackingGapWarning(summary) && (
              <Notice tone="warning" title="已取号不等于在途中">
                {trackingGapWarning(summary)}
              </Notice>
            )}
            {summary.freightByCurrency.length > 0 && (
              <p className="text-muted-foreground text-xs">
                运费按币种分组（跨币种相加属 10.1.2.1 运费试算职责，未交付）：
                {summary.freightByCurrency
                  .map((t) => `${t.currency} ${t.totalAmount}（${t.waybills} 张）`)
                  .join('、')}
              </p>
            )}
          </>
        )}
        {page?.statusFilterNote && (
          <Notice tone="warning" title="该状态没有数据源">
            {page.statusFilterNote}
          </Notice>
        )}
      </SectionCard>

      {/* ③ 运单清单 + 批量操作 */}
      <SectionCard
        title="运单清单"
        description="勾选后可批量申请运单 / 取消 / 打印"
        extra={
          <div className="flex flex-wrap gap-2">
            <Button
              variant="outline"
              disabled={busy || selected.length === 0}
              onClick={() => void runAction('apply')}
            >
              <Truck className="mr-2 h-4 w-4" />
              批量申请运单
            </Button>
            <Button
              variant="outline"
              disabled={busy || selected.length === 0}
              onClick={() => void runAction('print')}
            >
              <Printer className="mr-2 h-4 w-4" />
              批量打印
            </Button>
            <Button
              variant="outline"
              disabled={busy || selected.length === 0}
              onClick={() => void runAction('cancel')}
            >
              <XCircle className="mr-2 h-4 w-4" />
              批量取消
            </Button>
          </div>
        }
      >
        {selected.length > 0 && (
          <div className="flex flex-wrap items-end gap-3">
            <span className="text-sm">已选 {selected.length} 张</span>
            <FormField id="cancel-reason" label="取消原因（批量取消必填）">
              <Input value={cancelReason} onChange={(e) => setCancelReason(e.target.value)} />
            </FormField>
            <FormField id="printer-target" label="打印机目标（批量打印）">
              <Input
                value={printerTarget}
                onChange={(e) => setPrinterTarget(e.target.value)}
                placeholder="如 ZEBRA-01"
              />
            </FormField>
          </div>
        )}

        {actionError && (
          <Notice tone="danger" title="批量操作失败">
            {actionError}
          </Notice>
        )}
        {actionResult && (
          <Notice
            tone={actionResult.failed > 0 ? 'warning' : 'success'}
            title={bulkSummaryLine(actionResult)}
          >
            {actionResult.batchNo ? ` · 打印批次 ${actionResult.batchNo}` : ''}
            <ul className="mt-1 flex flex-col gap-0.5 text-xs">
              {actionResult.entries.map((e) => (
                <li key={e.requestId} className="flex gap-2">
                  <span className={e.applied ? TONE_CLASS.ok : TONE_CLASS.warn}>
                    {e.applied ? '成功' : e.resultCode === 'NOT_APPLICABLE' ? '跳过' : '失败'}
                  </span>
                  <span className="font-mono">{e.requestNo}</span>
                  <span className="text-muted-foreground">{e.resultMessage}</span>
                </li>
              ))}
            </ul>
          </Notice>
        )}

        {loading && !page ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : loadError ? (
          <Notice tone="danger" title="运单清单加载失败">
            {loadError}
          </Notice>
        ) : page && page.items.length === 0 ? (
          <EmptyState
            title="没有符合条件的运单"
            description={
              page.statusFilterNote
                ? '该状态没有数据源，不是「今天没有单」。'
                : '调整筛选条件，或到「运单申请」页面去申请运单号。'
            }
          />
        ) : (
          page && (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left">
                    <th className="p-2">
                      <input
                        type="checkbox"
                        aria-label="全选本页"
                        checked={selected.length > 0 && selected.length === page.items.length}
                        onChange={(e) =>
                          setSelected(e.target.checked ? page.items.map((r) => r.id) : [])
                        }
                      />
                    </th>
                    <th className="p-2">运单号</th>
                    <th className="p-2">订单 / 包裹</th>
                    <th className="p-2">承运商渠道</th>
                    <th className="p-2">发货仓</th>
                    <th className="p-2">状态</th>
                    <th className="p-2">下单时间</th>
                    <th className="p-2">重量</th>
                    <th className="p-2">运费</th>
                    <th className="p-2">面单</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((row) => (
                    <Row
                      key={row.id}
                      row={row}
                      checked={selected.includes(row.id)}
                      onToggle={() => toggle(row.id)}
                    />
                  ))}
                </tbody>
              </table>
              <div className="mt-3 flex items-center gap-3">
                <Button
                  variant="outline"
                  disabled={page.page <= 0}
                  onClick={() => setPageIndex((p) => Math.max(p - 1, 0))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground text-xs">
                  第 {page.page + 1} / {Math.max(page.totalPages, 1)} 页 · 共 {page.totalElements} 张
                </span>
                <Button
                  variant="outline"
                  disabled={page.page + 1 >= page.totalPages}
                  onClick={() => setPageIndex((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          )
        )}
      </SectionCard>
    </div>
  );
}

function Row(props: { row: WaybillListRow; checked: boolean; onToggle: () => void }) {
  const { row } = props;
  const tone = displayStatusTone(row);
  const freight = freightCell(row);
  return (
    <tr className="border-b">
      <td className="p-2">
        <input
          type="checkbox"
          aria-label={`选择 ${row.requestNo}`}
          checked={props.checked}
          onChange={props.onToggle}
        />
      </td>
      <td className="p-2 font-mono">
        {row.waybillNo || <span className="text-muted-foreground">尚未取号</span>}
        <div className="text-muted-foreground text-xs font-sans">{row.requestNo}</div>
      </td>
      <td className="p-2">
        {row.salesOrderNo}
        <div className="text-muted-foreground text-xs">出库单 {row.outboundOrderNo}</div>
      </td>
      <td className="p-2">
        {row.carrierCode}
        <div className="text-muted-foreground text-xs">{row.carrierChannelLabel}</div>
      </td>
      <td className="p-2">{row.warehouseRef}</td>
      <td className="p-2">
        <span className={TONE_CLASS[tone]}>{row.displayStatusLabel}</span>
        <div className="text-muted-foreground text-xs">目的国 {row.destinationCountry}</div>
        {issuedDisclaimer(row) && (
          <div className="text-muted-foreground text-xs">{issuedDisclaimer(row)}</div>
        )}
      </td>
      <td className="p-2">{timeText(row.createdAt)}</td>
      <td className="p-2">{weightText(row)}</td>
      <td className={`p-2 ${TONE_CLASS[freight.tone]}`}>{freight.text}</td>
      <td className="p-2">
        {printable(row) ? (
          <span className={TONE_CLASS.ok}>可打印（只发凭据）</span>
        ) : (
          <span className={TONE_CLASS.warn}>无面单凭据</span>
        )}
      </td>
    </tr>
  );
}
