'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlarmClock, RefreshCw, TriangleAlert } from 'lucide-react';
import { Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  AftersalesBulkResult,
  AftersalesCaseCapabilities,
  AftersalesCasePage,
  AftersalesCaseRow,
  AftersalesFilterOptions,
} from '../../../../../packages/api-contracts/src/index';
import {
  authorizationStateLabel,
  bulkAftersales,
  bulkItemText,
  bulkMaxSize,
  bulkOperations,
  bulkSummaryText,
  countdownText,
  dimensionByField,
  fetchAftersalesCapabilities,
  fetchAftersalesFilters,
  fetchAftersalesList,
  formatAmount,
  formatQuantity,
  formatTime,
  ownerText,
  platformSourceLabel,
  summaryLine,
  timelinessBadgeClass,
  timelinessTone,
  unavailableCell,
} from './aftersales-case-model';

/**
 * 11.1.1.1 售后单列表。
 *
 * <p>蓝图：「售后单主列表：售后号、关联订单、店铺、类型（退货退款/仅退款/换货/补发/维修）、金额、
 * 原因、状态、时效（平台要求回复/处理时限）、责任人，支持多维筛选与批量处理。」
 * 加粗句：「<b>平台时效倒计时必须显眼</b>：超时会直接影响账号绩效与资金冻结。」
 *
 * <p><b>本页面对那句加粗句負责的三件事</b>：
 *
 * <ol>
 *   <li><b>超时数是页面顶部的第一张卡</b>，不是表格里的某一列。
 *       运营打开这个页面先看的是「有多少张已经超时了」，不是「一共多少张」。</li>
 *   <li><b>每一行都带四档视觉的时效徽标</b>（超时 / 临期 / 正常 / 无限时 / 已处理），
 *       颜色、边框与字重都不同 —— 一屏 50 行时只有文字差异等于没有提醒。
 *       判定依据全部来自后端的 {@code timelinessState}，<b>前端不自己比时间</b>。</li>
 *   <li><b>默认排序就是「最紧急的在前」</b>，超时的沉不到第 30 页。</li>
 * </ol>
 *
 * <p><b>店铺维度诚实显示「—」</b>：{@code sales_order} 没有 shop_code 列、卷 2 店铺在
 * channel 独立库禁止跨服务读 ⇒ 本服务只有平台级来源。显示「—」并带原因，
 * 而不是编一个平台级下拉框叫「店铺」。
 *
 * <p><b>批量结果是逐条的</b>：成功后弹窗逐行列出成功 / 失败与原因，
 * 而不是只弹一句「操作成功」。
 */
export function AftersalesCaseWorkspace() {
  const [params, setParams] = useState({
    keyword: '',
    caseType: '',
    status: '',
    reasonCode: '',
    platformSource: '',
    timelinessState: '',
    currency: '',
    authorizationState: '',
    minAmount: '',
    maxAmount: '',
    ownerRef: '',
    sort: 'DUE_ASC',
    page: 0,
    size: 20,
  });
  const [page, setPage] = useState<AftersalesCasePage | null>(null);
  const [caps, setCaps] = useState<AftersalesCaseCapabilities | null>(null);
  const [options, setOptions] = useState<AftersalesFilterOptions | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 批量：勾选 + 结果回报
  const [selected, setSelected] = useState<string[]>([]);
  const [bulkOp, setBulkOp] = useState<'AUTHORIZE' | 'NOTE' | 'ESCALATE'>('NOTE');
  const [bulkForm, setBulkForm] = useState({
    note: '',
    decision: 'APPROVED',
    authorizedAmount: '',
    priority: 'NORMAL',
    reasonNote: '',
  });
  const [bulkBusy, setBulkBusy] = useState(false);
  const [bulkResult, setBulkResult] = useState<AftersalesBulkResult | null>(null);
  const [bulkError, setBulkError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setPage(await fetchAftersalesList(params));
    } catch (e) {
      setError(e instanceof Error ? e.message : '售后单列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await fetchAftersalesCapabilities());
        setOptions(await fetchAftersalesFilters());
      } catch {
        // 能力声明与词表拿不到不影响列表本身，页面仍要能看单。
      }
    })();
  }, []);

  const shopDimension = useMemo(() => dimensionByField(caps, 'platformShopCode'), [caps]);
  const ownerDimension = useMemo(() => dimensionByField(caps, 'ownerFromUpstream'), [caps]);
  const maxBatch = bulkMaxSize(caps, 100);
  const totalPages = page ? Math.max(1, Math.ceil(page.total / page.size)) : 1;

  const toggle = (id: string) =>
    setSelected((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));

  const toggleAllOnPage = () => {
    const ids = (page?.rows ?? []).map((r) => r.id);
    const allOn = ids.length > 0 && ids.every((id) => selected.includes(id));
    setSelected((prev) =>
      allOn ? prev.filter((id) => !ids.includes(id)) : [...new Set([...prev, ...ids])],
    );
  };

  const runBulk = async () => {
    if (selected.length === 0) return;
    setBulkBusy(true);
    setBulkError(null);
    setBulkResult(null);
    try {
      const result = await bulkAftersales({
        operation: bulkOp,
        caseIds: selected,
        ...(bulkOp === 'NOTE' ? { note: bulkForm.note } : {}),
        ...(bulkOp === 'AUTHORIZE'
          ? {
              decision: bulkForm.decision as 'APPROVED' | 'PARTIALLY_APPROVED' | 'REJECTED',
              authorizedAmount: bulkForm.authorizedAmount || undefined,
              reasonNote: bulkForm.reasonNote || undefined,
            }
          : {}),
        ...(bulkOp === 'ESCALATE'
          ? {
              priority: bulkForm.priority as 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT',
              reasonNote: bulkForm.reasonNote || undefined,
            }
          : {}),
      });
      setBulkResult(result);
      setSelected([]);
      // 批量改了状态 / 授权 / 工单，必须重读列表，否则界面显示的是旧事实。
      await load();
    } catch (e) {
      setBulkError(e instanceof Error ? e.message : '批量操作失败');
    } finally {
      setBulkBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="售后单列表"
        description="退货退款 / 仅退款 / 换货 / 补发 / 维修 五种类型，支持多维筛选与批量处理。平台时效倒计时直接关系账号绩效与资金冻结，超时的排在最前。"
      />

      {error && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive flex items-center gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="h-4 w-4 shrink-0" />
          <span className="text-copy-13">{error}</span>
          <Button variant="outline" size="sm" onClick={() => void load()} className="ml-auto">
            重试
          </Button>
        </div>
      )}

      {/* 超时卡放第一位：这是打开这个页面唯一要先看的数。 */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="已超时"
          value={page?.summary.overdueCount ?? 0}
          icon={<AlarmClock className="h-4 w-4" />}
        />
        <StatCard label="临期（≤ 24 小时）" value={page?.summary.dueSoonCount ?? 0} />
        <StatCard label="正常" value={page?.summary.onTrackCount ?? 0} />
        <StatCard label="无平台时限" value={page?.summary.noDeadlineCount ?? 0} />
      </div>

      {page && (
        <p className="text-muted-foreground text-copy-13">
          {summaryLine(page)}
          <span className="ml-2">
            排序：{page.timelinessThresholdNote}
          </span>
        </p>
      )}

      <SectionCard title="筛选" description={caps?.filterableNote ?? '多维筛选：类型 / 状态 / 原因 / 平台 / 时效 / 金额 / 责任人 / 关键字。'}>
        <div className="flex flex-wrap items-end gap-3">
          <Input
            className="w-56"
            placeholder="售后号 / 订单号 / 平台单号 / 买家诉求"
            value={params.keyword}
            onChange={(e) => setParams({ ...params, keyword: e.target.value, page: 0 })}
          />
          <select
            className="border-border bg-background text-copy-13 rounded-md border px-3 py-2"
            value={params.caseType}
            onChange={(e) => setParams({ ...params, caseType: e.target.value, page: 0 })}
            aria-label="类型"
          >
            <option value="">全部类型</option>
            {(options?.caseTypes ?? []).map((t) => (
              <option key={t.code} value={t.code}>
                {t.label}
              </option>
            ))}
          </select>
          <select
            className="border-border bg-background text-copy-13 rounded-md border px-3 py-2"
            value={params.status}
            onChange={(e) => setParams({ ...params, status: e.target.value, page: 0 })}
            aria-label="状态"
          >
            <option value="">全部状态</option>
            {(options?.statuses ?? []).map((s) => (
              <option key={s.code} value={s.code}>
                {s.label}
              </option>
            ))}
          </select>
          <select
            className="border-border bg-background text-copy-13 rounded-md border px-3 py-2"
            value={params.reasonCode}
            onChange={(e) => setParams({ ...params, reasonCode: e.target.value, page: 0 })}
            aria-label="原因"
          >
            <option value="">全部原因</option>
            {(options?.reasonCodes ?? []).map((r) => (
              <option key={r.code} value={r.code}>
                {r.label}
              </option>
            ))}
          </select>
          <select
            className="border-border bg-background text-copy-13 rounded-md border px-3 py-2"
            value={params.timelinessState}
            onChange={(e) => setParams({ ...params, timelinessState: e.target.value, page: 0 })}
            aria-label="时效"
          >
            <option value="">全部时效</option>
            {(options?.timelinessStates ?? []).map((t) => (
              <option key={t.code} value={t.code}>
                {t.label}
              </option>
            ))}
          </select>
          <select
            className="border-border bg-background text-copy-13 rounded-md border px-3 py-2"
            value={params.platformSource}
            onChange={(e) => setParams({ ...params, platformSource: e.target.value, page: 0 })}
            aria-label="平台"
            title="平台级来源。店铺维度不可得：sales_order 没有 shop_code 列，卷 2 店铺在 channel 独立库禁止跨服务读。"
          >
            <option value="">全部平台</option>
            {(options?.platformSources ?? []).map((p) => (
              <option key={p} value={p}>
                {platformSourceLabel(p)}
              </option>
            ))}
          </select>
          <Input
            className="w-28"
            placeholder="金额下限"
            value={params.minAmount}
            onChange={(e) => setParams({ ...params, minAmount: e.target.value, page: 0 })}
          />
          <Input
            className="w-28"
            placeholder="金额上限"
            value={params.maxAmount}
            onChange={(e) => setParams({ ...params, maxAmount: e.target.value, page: 0 })}
          />
          <select
            className="border-border bg-background text-copy-13 rounded-md border px-3 py-2"
            value={params.sort}
            onChange={(e) => setParams({ ...params, sort: e.target.value, page: 0 })}
            aria-label="排序"
          >
            {(options?.sorts ?? []).map((s) => (
              <option key={s.code} value={s.code}>
                {s.label}
              </option>
            ))}
          </select>
          <Button variant="outline" size="sm" onClick={() => setParams({ ...params, page: 0 })}>
            <RefreshCw className="mr-1.5 h-4 w-4" />
            应用
          </Button>
        </div>
        {page && (
          <p className="text-muted-foreground mt-3 text-copy-13">{page.amountRangeSemantics}</p>
        )}
      </SectionCard>

      {/* 批量：三件都可用，逐条回报 */}
      <SectionCard
        title={`批量处理（已选 ${selected.length} 张，单次上限 ${maxBatch}）`}
        description="授权 / 备注 / 转工单 三件都落本服务内的真实表。执行后**逐条**回报成功与失败 —— 「30 条批了 12 条」不是可以接受的结果。"
      >
        <div className="flex flex-wrap items-end gap-3">
          <select
            className="border-border bg-background text-copy-13 rounded-md border px-3 py-2"
            value={bulkOp}
            onChange={(e) => setBulkOp(e.target.value as 'AUTHORIZE' | 'NOTE' | 'ESCALATE')}
            aria-label="批量操作"
          >
            {bulkOperations.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>

          {bulkOp === 'NOTE' && (
            <Input
              className="w-72"
              placeholder="备注正文（必填，最多 2000 字）"
              value={bulkForm.note}
              onChange={(e) => setBulkForm({ ...bulkForm, note: e.target.value })}
            />
          )}

          {bulkOp === 'AUTHORIZE' && (
            <>
              <select
                className="border-border bg-background text-copy-13 rounded-md border px-3 py-2"
                value={bulkForm.decision}
                onChange={(e) => setBulkForm({ ...bulkForm, decision: e.target.value })}
                aria-label="授权决定"
              >
                <option value="APPROVED">批准</option>
                <option value="PARTIALLY_APPROVED">部分批准</option>
                <option value="REJECTED">拒绝</option>
              </select>
              <Input
                className="w-36"
                placeholder="授权金额（退款类必填）"
                value={bulkForm.authorizedAmount}
                onChange={(e) => setBulkForm({ ...bulkForm, authorizedAmount: e.target.value })}
              />
            </>
          )}

          {bulkOp === 'ESCALATE' && (
            <select
              className="border-border bg-background text-copy-13 rounded-md border px-3 py-2"
              value={bulkForm.priority}
              onChange={(e) => setBulkForm({ ...bulkForm, priority: e.target.value })}
              aria-label="工单优先级"
            >
              <option value="LOW">低</option>
              <option value="NORMAL">普通</option>
              <option value="HIGH">高</option>
              <option value="URGENT">紧急</option>
            </select>
          )}

          {(bulkOp === 'AUTHORIZE' || bulkOp === 'ESCALATE') && (
            <Input
              className="w-56"
              placeholder="原因备注（选填）"
              value={bulkForm.reasonNote}
              onChange={(e) => setBulkForm({ ...bulkForm, reasonNote: e.target.value })}
            />
          )}

          <Button onClick={() => void runBulk()} disabled={selected.length === 0 || bulkBusy}>
            {bulkBusy ? '执行中…' : `对选中的 ${selected.length} 张执行`}
          </Button>
          {selected.length > 0 && (
            <Button variant="ghost" size="sm" onClick={() => setSelected([])}>
              清空选择
            </Button>
          )}
          {selected.length > maxBatch && (
            <span className="text-destructive text-copy-13">
              已选 {selected.length} 张，超过单次上限 {maxBatch} 张，请分批执行。
            </span>
          )}
        </div>

        {bulkError && (
          <div className="border-destructive-soft bg-destructive-soft text-destructive mt-3 rounded-lg border px-4 py-3 text-copy-13">
            {bulkError}
          </div>
        )}

        {bulkResult && (
          <div className="mt-4 flex flex-col gap-2">
            <p className="text-copy-13 font-medium">{bulkSummaryText(bulkResult)}</p>
            <p className="text-muted-foreground text-copy-13">{bulkResult.note}</p>
            <ul className="divide-border divide-y text-copy-13 rounded-md border">
              {bulkResult.items.map((item, i) => (
                <li key={`${item.caseId ?? 'null'}-${i}`} className="flex items-start gap-3 px-3 py-2">
                  <span
                    className={
                      item.success
                        ? 'text-success shrink-0 font-medium'
                        : 'text-destructive shrink-0 font-medium'
                    }
                  >
                    {item.success ? '成功' : '失败'}
                  </span>
                  <span className="w-24 shrink-0 font-mono">{item.caseNo ?? '—'}</span>
                  <span className="text-muted-foreground">{bulkItemText(item)}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </SectionCard>

      {/* 列表 */}
      <SectionCard title="售后单" description={page?.dataScopeNote ?? ''}>
        {loading && !page ? (
          <div className="text-muted-foreground p-6 text-center text-copy-13">加载中…</div>
        ) : (page?.rows.length ?? 0) === 0 ? (
          <EmptyState
            title="没有符合条件的售后单"
            description={
              selected.length > 0
                ? '当前筛选条件下没有可批量处理的单。'
                : '放宽筛选条件，或先登记一张售后单。登记入口是 POST /trade/aftersales/cases（11.1.1.3 售后受理未交付期间的登记入口）。'
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy-13">
              <thead>
                <tr className="border-border border-b">
                  <th className="w-8 px-2 py-2">
                    <input
                      type="checkbox"
                      aria-label="全选本页"
                      checked={
                        (page?.rows.length ?? 0) > 0 &&
                        (page?.rows ?? []).every((r) => selected.includes(r.id))
                      }
                      onChange={toggleAllOnPage}
                    />
                  </th>
                  <th className="px-2 py-2">售后号</th>
                  <th className="px-2 py-2">关联订单</th>
                  <th className="px-2 py-2">平台</th>
                  <th className="px-2 py-2">店铺</th>
                  <th className="px-2 py-2">类型</th>
                  <th className="px-2 py-2">状态</th>
                  <th className="px-2 py-2">申请金额</th>
                  <th className="px-2 py-2">数量</th>
                  <th className="px-2 py-2">原因</th>
                  <th className="px-2 py-2">时效倒计时</th>
                  <th className="px-2 py-2">责任人</th>
                  <th className="px-2 py-2">授权</th>
                  <th className="px-2 py-2">工单</th>
                  <th className="px-2 py-2">受理时间</th>
                </tr>
              </thead>
              <tbody>
                {(page?.rows ?? []).map((row) => (
                  <Row
                    key={row.id}
                    row={row}
                    checked={selected.includes(row.id)}
                    onToggle={() => toggle(row.id)}
                    shopDimension={shopDimension}
                    ownerDimension={ownerDimension}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="mt-4 flex items-center justify-between">
          <span className="text-muted-foreground text-copy-13">
            共 {page?.total ?? 0} 张，第 {(page?.page ?? 0) + 1} / {totalPages} 页
          </span>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={(page?.page ?? 0) <= 0}
              onClick={() => setParams({ ...params, page: Math.max(0, params.page - 1) })}
            >
              上一页
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={(page?.page ?? 0) + 1 >= totalPages}
              onClick={() => setParams({ ...params, page: params.page + 1 })}
            >
              下一页
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* 能力与缺口：不让调用方从空白里猜哪一环没通 */}
      {caps && (
        <SectionCard
          title="能力与上下游"
          description="哪些维度真有依据、上游通没通、下游交付没交付，全部显式列出。"
        >
          <div className="flex flex-col gap-3 text-copy-13">
            <p>
              <b>时效：</b>
              {caps.timelinessNote}
            </p>
            <p>
              <b>登记入口：</b>
              {caps.intakeNote}
            </p>
            <p>
              <b>店铺粒度：</b>
              {caps.shopGranularityNote}
            </p>
            <p>
              <b>跨服务读：</b>
              {caps.crossServiceReadAvailable ? '可用' : '不可用（禁止）'} —— {caps.crossServiceNote}
            </p>
            <p>
              <b>下游：</b>
              {caps.downstream.note}
            </p>
            <ul className="divide-border divide-y rounded-md border">
              {caps.unavailableDimensions.map((d) => (
                <li key={d.field} className="px-3 py-2">
                  <b>{d.label}</b>
                  <span className="text-muted-foreground">（{d.field}）</span>
                  <div className="text-muted-foreground">{d.reason}</div>
                </li>
              ))}
            </ul>
            <ul className="divide-border divide-y rounded-md border">
              {caps.bulkOperations.map((op) => (
                <li key={op.code} className="px-3 py-2">
                  <b>{op.label}</b>
                  <span className="text-muted-foreground">
                    （{op.available ? '可用' : '未交付'}，落在 {op.landsIn}，上限 {op.maxBatchSize}）
                  </span>
                  <div className="text-muted-foreground">{op.reason}</div>
                  <div className="text-muted-foreground">前置条件：{op.precondition}</div>
                </li>
              ))}
            </ul>
          </div>
        </SectionCard>
      )}
    </div>
  );
}

function Row({
  row,
  checked,
  onToggle,
  shopDimension,
  ownerDimension,
}: {
  row: AftersalesCaseRow;
  checked: boolean;
  onToggle: () => void;
  shopDimension?: { label: string; ownerModule: string; reason: string } | undefined;
  ownerDimension?: { label: string; ownerModule: string; reason: string } | undefined;
}) {
  const tone = timelinessTone(row.timelinessState);
  const shop = unavailableCell(row.platformShopCode, shopDimension);
  return (
    <tr
      className={`border-border border-b ${tone.tone === 'overdue' ? 'bg-destructive-soft/40' : ''}`}
    >
      <td className="px-2 py-2">
        <input type="checkbox" checked={checked} onChange={onToggle} aria-label={`选择 ${row.caseNo}`} />
      </td>
      <td className="px-2 py-2 font-mono">{row.caseNo}</td>
      <td className="px-2 py-2 font-mono">{row.orderNo ?? '—'}</td>
      <td className="px-2 py-2">{platformSourceLabel(row.platformSource)}</td>
      <td className="text-muted-foreground px-2 py-2" title={shop.title}>
        {shop.text}
      </td>
      <td className="px-2 py-2">{row.caseTypeLabel}</td>
      <td className="px-2 py-2">{row.statusLabel}</td>
      <td className="px-2 py-2">{formatAmount(row.requestedAmount, row.currency)}</td>
      <td className="px-2 py-2">{formatQuantity(row.requestedQuantity)}</td>
      <td className="px-2 py-2" title={row.reasonNote ?? ''}>
        {row.reasonLabel}
      </td>
      {/* 时效列：徽标 + 倒计时文本。四档视觉，判定依据来自后端枚举。 */}
      <td className="px-2 py-2">
        <span
          className={`inline-flex items-center gap-1.5 rounded-md border px-2 py-0.5 font-medium ${toneBadge(tone.tone)}`}
        >
          {tone.label}
          <span className="font-normal">
            {countdownText(row.timelinessState, row.remainingSeconds)}
          </span>
        </span>
      </td>
      <td className="px-2 py-2" title={ownerDimension?.reason ?? ''}>
        {ownerText(row)}
      </td>
      <td className="px-2 py-2">{authorizationStateLabel(row.authorizationState)}</td>
      <td className="px-2 py-2 font-mono">{row.ticketNo ?? '—'}</td>
      <td className="px-2 py-2">{formatTime(row.receivedAt)}</td>
    </tr>
  );
}

/**
 * 四档视觉：颜色 + 边框 + 字重都不同。
 *
 * <p>只有文字变化而没有视觉差异，在一屏 50 行里等于没有提醒 ——
 * 这是蓝图「必须显眼」在 UI 层的落点，刻意不共用一个样式。
 */
function toneBadge(tone: string): string {
  switch (tone) {
    case 'overdue':
      return 'border-destructive bg-destructive-soft text-destructive font-semibold';
    case 'due-soon':
      return 'border-warning bg-warning-soft text-warning font-medium';
    case 'on-track':
      return 'border-border bg-muted text-muted-foreground';
    case 'done':
      return 'border-border bg-muted text-muted-foreground';
    default:
      return 'border-border bg-background text-muted-foreground border-dashed';
  }
}
