'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Boxes, Filter, Merge, Package, RefreshCw } from 'lucide-react';
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
  CandidateSet,
  ConsolidationDetail,
  ConsolidationPage,
  ConsolidationRow,
  OrderConsolidationCapabilities,
  WritebackPage,
  WritebackTask,
} from '../../../../../packages/api-contracts/src/index';
import {
  STATUS_OPTIONS,
  WRITEBACK_STATUS_OPTIONS,
  advanceConsolidationStatus,
  blockedReason,
  canConsolidate,
  createConsolidation,
  crossShopText,
  errorText,
  excludedText,
  fetchCapabilities,
  fetchCandidates,
  fetchConsolidation,
  fetchConsolidations,
  fetchWritebackTasks,
  fetchRule,
  freightSavingText,
  isoText,
  money,
  platformText,
  recordWriteback,
  saveRule,
  sizeText,
} from './order-consolidation-model';

const PAGE_SIZE = 20;

/**
 * 9.3.1.2 合单工作区。
 *
 * <p>蓝图：「把同一买家的多笔订单合并成一个包裹发货，降低运费与包材成本；
 * 同时识别跨店铺同买家订单（需分别回传平台）。」输出是
 * 「合并履约请求 + 各原订单的关联记录」。
 *
 * <p><b>四个区块的顺序对应「先问资格，再问能不能合，最后才问省了多少」</b>：
 *
 * <ol>
 *   <li><b>能力与规则</b>：合单会真实改变包裹形态与逐单回传口径，
 *       所以「本租户配没配规则」排在最前 —— 没配时下面两块都不可写，
 *       且页面必须说清「为什么不能合」，而不是给一个禁用按钮了事。</li>
 *   <li><b>候选订单</b>：同一买家 + 同一收货地址的**真实 SQL 检索**结果。
 *       候选与**排除项**一起显示：排除项带原因码，
 *       「为什么只出来两张」不需要去猜系统是不是漏单了。</li>
 *   <li><b>合并单</b>：列表 + 详情（成员行、逐平台订单的回传任务、事件流）。
 *       跨店铺徽标放在第一眼位置 —— 它决定了后面要分别回传几张平台单。</li>
 *   <li><b>回传任务</b>：蓝图加粗句「合单后每个平台订单仍要单独回传物流号」的落点，
 *       按平台订单逐条列，**没有**「一个包裹一条」这种形状。</li>
 * </ol>
 *
 * <p>加载态 / 空态 / 错误态三态齐全：任何一块没取到都不会静默显示成 0 或空数组。
 */
export function OrderConsolidationWorkspace() {
  const [capabilities, setCapabilities] = useState<OrderConsolidationCapabilities | null>(null);
  const [rule, setRule] = useState<Awaited<ReturnType<typeof fetchRule>> | null>(null);
  const [ruleMissing, setRuleMissing] = useState(false);
  const [candidates, setCandidates] = useState<CandidateSet | null>(null);
  const [page, setPage] = useState<ConsolidationPage | null>(null);
  const [detail, setDetail] = useState<ConsolidationDetail | null>(null);
  const [writebacks, setWritebacks] = useState<WritebackPage | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // 候选检索表单：字段就是收货地址快照的字段，与后端判据一一对应。
  const [form, setForm] = useState({
    recipientName: '',
    recipientPhone: '',
    recipientEmail: '',
    addressLine1: '',
    city: '',
    countryCode: '',
  });
  const [selected, setSelected] = useState<string[]>([]);
  const [quote, setQuote] = useState('');

  const [filters, setFilters] = useState({ keyword: '', status: '', crossShopOnly: false });
  const [pageNo, setPageNo] = useState(0);
  const [wbFilters, setWbFilters] = useState({ status: '', platformOrderRef: '' });

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const caps = await fetchCapabilities();
      setCapabilities(caps);
      let r: Awaited<ReturnType<typeof fetchRule>> | null = null;
      let missing = false;
      try {
        r = await fetchRule();
      } catch (e) {
        // 「没登记过规则」是一个正常状态，不是错误：capabilities.ruleConfigured 已如实报出。
        if (e instanceof Error && /不存在|404/.test(e.message)) missing = true;
        else throw e;
      }
      setRule(r);
      setRuleMissing(missing);
      const [p, w] = await Promise.all([
        fetchConsolidations({ ...filters, page: pageNo, size: PAGE_SIZE }),
        fetchWritebackTasks({ ...wbFilters, page: 0, size: PAGE_SIZE }),
      ]);
      setPage(p);
      setWritebacks(w);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [filters, pageNo, wbFilters]);

  useEffect(() => {
    void load();
  }, [load]);

  const search = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    try {
      const set = await fetchCandidates({
        recipientName: form.recipientName,
        recipientPhone: form.recipientPhone,
        recipientEmail: form.recipientEmail,
        addressLine1: form.addressLine1,
        city: form.city,
        countryCode: form.countryCode,
      });
      setCandidates(set);
      setSelected([]);
    } catch (e) {
      setActionError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, [form]);

  const openDetail = useCallback(async (id: string) => {
    try {
      setDetail(await fetchConsolidation(id));
    } catch (e) {
      setActionError(errorText(e));
    }
  }, []);

  const consolidate = useCallback(async () => {
    setBusy(true);
    setActionError(null);
    try {
      const saved = await createConsolidation({
        orderIds: selected,
        // 合单时刻由调用方显式给出（后端不接受隐含的服务器当前时间）：
        // 这里是「此刻」这个动作本身，所以用当前时刻并显式传出。
        consolidatedAt: new Date().toISOString(),
        // ⚠️ 空报价必须传 null 而不是 0：0 的含义是「合并运费 0 元」，
        // 而空输入的含义是「租户还没报价」。两者在运费节省上一句是
        // 「省下 0 元」与「还不知道能省多少」，不能混。
        // 契约把该字段生成成非空 number（丢了可空性），故做一次显式转换。
        consolidatedShippingAmount: (quote.trim() === '' ? null : Number(quote)) as unknown as number,
        note: '',
      });
      setSelected([]);
      setCandidates(null);
      setQuote('');
      await load();
      await openDetail(saved.id);
    } catch (e) {
      setActionError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, [selected, quote, load, openDetail]);

  const advance = useCallback(
    async (id: string, status: 'CONFIRMED' | 'DISPATCHED' | 'CANCELLED') => {
      setBusy(true);
      setActionError(null);
      try {
        await advanceConsolidationStatus(id, {
          status,
          reason: status === 'CANCELLED' ? '运营作废' : '',
          occurredAt: new Date().toISOString(),
        });
        await load();
        await openDetail(id);
      } catch (e) {
        setActionError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load, openDetail],
  );

  const writeback = useCallback(
    async (consolidationId: string, memberId: string, task: WritebackTask) => {
      const tracking = window.prompt(`登记 ${task.platformCode} / ${task.platformOrderRef} 的物流号`);
      if (tracking === null) return;
      setBusy(true);
      setActionError(null);
      try {
        await recordWriteback(consolidationId, memberId, {
          status: 'CONFIRMED',
          trackingNumber: tracking.trim(),
          carrierCode: '',
          lastError: '',
          occurredAt: new Date().toISOString(),
        });
        await load();
        await openDetail(consolidationId);
      } catch (e) {
        setActionError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load, openDetail],
  );

  const blocked = useMemo(() => blockedReason(candidates), [candidates]);
  const canSubmit = canConsolidate(candidates, selected.length);

  if (loading && !capabilities) {
    return (
      <PageHeader title="订单合单" description="正在载入合单能力与合并单…" />
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="订单合单"
        description="把同一买家的多笔订单合并成一个包裹发货，降低运费与包材成本；跨店铺同买家订单识别后仍按平台分别回传物流号。"
        actions={
          <Button onClick={() => void load()} disabled={busy}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <Notice tone="danger" title="合单数据加载失败">
          {error}
          <Button className="ml-3" onClick={() => void load()}>
            重试
          </Button>
        </Notice>
      )}
      {actionError && (
        <Notice tone="danger" title="操作未完成">
          {actionError}
        </Notice>
      )}

      {/* ------------------------------------------------ 1. 能力与规则 */}
      <SectionCard title="能力与规则" description="合单会真实改变包裹形态与逐单回传口径，先看资格再动手。">
        {capabilities && (
          <div className="space-y-3">
            <div className="flex flex-wrap gap-2">
              {capabilities.dimensions.map((d) => (
                <span
                  key={d.code}
                  className={
                    d.available
                      ? 'rounded border border-emerald-500/40 bg-emerald-500/10 px-2 py-1 text-xs'
                      : 'rounded border border-amber-500/40 bg-amber-500/10 px-2 py-1 text-xs'
                  }
                >
                  {d.label}：{d.available ? '可用' : '未接通'}
                </span>
              ))}
            </div>
            {capabilities.dimensions
              .filter((d) => !d.available && d.unavailableReason)
              .map((d) => (
                <p key={d.code} className="text-xs text-muted-foreground">
                  <strong>{d.label}</strong>：{d.unavailableReason}
                </p>
              ))}
            <p className="text-xs text-muted-foreground">{capabilities.notes}</p>
            <p className="text-xs text-muted-foreground">{capabilities.downstreamGaps}</p>
          </div>
        )}

        {ruleMissing || !rule ? (
          <Notice tone="warning" title="本租户尚未登记合单规则">
            合单会真实改变包裹形态与逐单回传口径合单会真实改变包裹形态与逐单回传口径，本模块**不提供隐含默认值** ——
            先登记规则，才能落合并单。
          </Notice>
        ) : (
          <RuleEditor rule={rule} onSaved={load} />
        )}
      </SectionCard>

      {/* ------------------------------------------------ 2. 候选订单 */}
      <SectionCard
        title="候选订单"
        description="按收货地址快照检索同一买家 + 同一收货地址的可合订单；排除项带原因一并显示。"
      >
        <div className="grid gap-3 md:grid-cols-3">
          <FormField id="cand-recipient-name" label="收件人姓名">
            <Input
              id="cand-recipient-name"
              value={form.recipientName}
              onChange={(e) => setForm({ ...form, recipientName: e.target.value })}
              placeholder="收货地址快照上的姓名"
            />
          </FormField>
          <FormField id="cand-recipient-phone" label="收件人电话">
            <Input
              id="cand-recipient-phone"
              value={form.recipientPhone}
              onChange={(e) => setForm({ ...form, recipientPhone: e.target.value })}
            />
          </FormField>
          <FormField id="cand-recipient-email" label="收件人邮箱">
            <Input
              id="cand-recipient-email"
              value={form.recipientEmail}
              onChange={(e) => setForm({ ...form, recipientEmail: e.target.value })}
            />
          </FormField>
          <FormField id="cand-address-line1" label="地址第一行">
            <Input
              id="cand-address-line1"
              value={form.addressLine1}
              onChange={(e) => setForm({ ...form, addressLine1: e.target.value })}
            />
          </FormField>
          <FormField id="cand-city" label="城市">
            <Input
              id="cand-city"
              value={form.city}
              onChange={(e) => setForm({ ...form, city: e.target.value })}
            />
          </FormField>
          <FormField id="cand-country" label="国家码">
            <Input
              id="cand-country"
              value={form.countryCode}
              onChange={(e) => setForm({ ...form, countryCode: e.target.value })}
              placeholder="US"
            />
          </FormField>
        </div>
        <Button className="mt-3" onClick={() => void search()} disabled={busy}>
          <Boxes className="mr-2 h-4 w-4" />
          检索可合订单
        </Button>

        {candidates && (
          <div className="mt-4 space-y-3">
            {blocked && (
              <Notice tone="warning" title="现在还不能落合并单">
                {blocked}
              </Notice>
            )}
            <p className="text-sm text-muted-foreground">
              候选 {candidates.candidateCount} 笔 · 件数合计 {candidates.candidateQty} · 单独发运费合计{' '}
              {money(candidates.separateShippingTotal)}
            </p>

            {candidates.candidates.length === 0 ? (
              <EmptyState
                title="没有可合的订单"
                description="同一买家 + 同一收货地址下没有可合订单，或它们已被排除（见下方原因）。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-left text-xs text-muted-foreground">
                    <tr>
                      <th className="p-2">选</th>
                      <th className="p-2">订单号</th>
                      <th className="p-2">平台单号</th>
                      <th className="p-2">来源</th>
                      <th className="p-2">行/件</th>
                      <th className="p-2">单独发运费</th>
                      <th className="p-2">提交时刻</th>
                    </tr>
                  </thead>
                  <tbody>
                    {candidates.candidates.map((c) => (
                      <tr key={c.orderId} className="border-t">
                        <td className="p-2">
                          <input
                            type="checkbox"
                            checked={selected.includes(c.orderId)}
                            onChange={(e) =>
                              setSelected(
                                e.target.checked
                                  ? [...selected, c.orderId]
                                  : selected.filter((id) => id !== c.orderId),
                              )
                            }
                          />
                        </td>
                        <td className="p-2">{c.orderNo}</td>
                        <td className="p-2">{c.externalOrderNo || '—'}</td>
                        <td className="p-2">{c.orderSource}</td>
                        <td className="p-2">
                          {c.lineCount} / {c.totalQty}
                        </td>
                        <td className="p-2">{money(c.separateShippingAmount)}</td>
                        <td className="p-2">{isoText(c.submittedAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {candidates.excluded.length > 0 && (
              <div>
                <p className="text-sm font-medium">未进入候选的订单（{candidates.excluded.length}）</p>
                <ul className="mt-1 space-y-1 text-xs text-muted-foreground">
                  {candidates.excluded.map((e) => (
                    <li key={e.orderId}>
                      {e.orderNo} — {excludedText(e)}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            <div className="flex flex-wrap items-end gap-3">
              <FormField
                id="cand-quote"
                label="合并运费报价（留空 = 节省不可计算，不是 0）"
              >
                <Input
                  id="cand-quote"
                  value={quote}
                  onChange={(e) => setQuote(e.target.value)}
                  placeholder="例如 11.25"
                />
              </FormField>
              <Button onClick={() => void consolidate()} disabled={!canSubmit || busy}>
                <Package className="mr-2 h-4 w-4" />
                合并选中的 {selected.length} 笔
              </Button>
              {!canSubmit && candidates.candidateCount >= 2 && !candidates.ruleBlockedReason && (
                <p className="text-xs text-muted-foreground">至少勾选两笔订单才能合单（本模块是 N→1）。</p>
              )}
            </div>
          </div>
        )}
      </SectionCard>

      {/* ------------------------------------------------ 3. 合并单 */}
      <SectionCard title="合并单" description="一次合单 = 一个包裹；跨店铺徽标决定后面要分别回传几张平台单。">
        <div className="flex flex-wrap gap-2">
          <Input
            className="max-w-xs"
            placeholder="合单号或我方单号"
            value={filters.keyword}
            onChange={(e) => {
              setPageNo(0);
              setFilters({ ...filters, keyword: e.target.value });
            }}
          />
          <select
            className="rounded border px-2 py-1 text-sm"
            value={filters.status}
            onChange={(e) => {
              setPageNo(0);
              setFilters({ ...filters, status: e.target.value });
            }}
          >
            {STATUS_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
          <label className="flex items-center gap-1 text-sm">
            <input
              type="checkbox"
              checked={filters.crossShopOnly}
              onChange={(e) => {
                setPageNo(0);
                setFilters({ ...filters, crossShopOnly: e.target.checked });
              }}
            />
            只看跨店铺
          </label>
        </div>

        {page && page.items.length === 0 ? (
          <EmptyState title="还没有合并单" description="在上方检索候选订单并合并后，这里会出现记录。" />
        ) : (
          page && (
            <div className="mt-3 overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-left text-xs text-muted-foreground">
                  <tr>
                    <th className="p-2">合单号</th>
                    <th className="p-2">状态</th>
                    <th className="p-2">规模</th>
                    <th className="p-2">跨店铺</th>
                    <th className="p-2">收件人</th>
                    <th className="p-2">运费节省</th>
                    <th className="p-2">合并时刻</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((row: ConsolidationRow) => {
                    const cross = crossShopText(row);
                    return (
                      <tr key={row.id} className="border-t">
                        <td className="p-2">
                          <button className="underline" onClick={() => void openDetail(row.id)}>
                            {row.consolidationNo}
                          </button>
                        </td>
                        <td className="p-2">{row.statusLabel}</td>
                        <td className="p-2">{sizeText(row)}</td>
                        <td className="p-2">
                          {cross ? (
                            <span className="rounded border border-violet-500/40 bg-violet-500/10 px-2 py-0.5 text-xs">
                              {cross}
                            </span>
                          ) : (
                            '—'
                          )}
                        </td>
                        <td className="p-2">{row.buyerName || '—'}</td>
                        <td className="p-2">{freightSavingText(row)}</td>
                        <td className="p-2">{isoText(row.consolidatedAt)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )
        )}

        {page && (
          <div className="mt-3 flex flex-wrap items-center gap-3">
            <StatCard label="已下发批次" value={page.dispatchedCount} />
            <StatCard label="待回传任务" value={page.pendingWritebackCount} />
            <StatCard
              label="运费节省合计"
              value={page.freightSavingTotal === null ? '未报价' : money(page.freightSavingTotal)}
            />
            <Button
              onClick={() => setPageNo(Math.max(0, pageNo - 1))}
              disabled={pageNo === 0}
            >
              上一页
            </Button>
            <span className="text-xs text-muted-foreground">第 {pageNo + 1} 页</span>
            <Button
              onClick={() => setPageNo(pageNo + 1)}
              disabled={(pageNo + 1) * PAGE_SIZE >= page.total}
            >
              下一页
            </Button>
          </div>
        )}
      </SectionCard>

      {/* ------------------------------------------------ 详情 */}
      {detail && (
        <SectionCard
          title={`合并单详情 ${detail.consolidation.consolidationNo}`}
          description={detail.notes}
        >
          <div className="flex flex-wrap gap-2">
            {detail.consolidation.status === 'PLANNED' && (
              <Button onClick={() => void advance(detail.consolidation.id, 'CONFIRMED')} disabled={busy}>
                确认合并
              </Button>
            )}
            {detail.consolidation.status === 'CONFIRMED' && (
              <Button
                onClick={() => void advance(detail.consolidation.id, 'DISPATCHED')}
                disabled={busy}
              >
                下发打包
              </Button>
            )}
            {['PLANNED', 'CONFIRMED'].includes(detail.consolidation.status) && (
              <Button
                onClick={() => void advance(detail.consolidation.id, 'CANCELLED')}
                disabled={busy}
              >
                作废
              </Button>
            )}
          </div>

          <div className="mt-3">
            <p className="text-sm font-medium">成员订单（各原订单的关联记录）</p>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-left text-xs text-muted-foreground">
                  <tr>
                    <th className="p-2">订单号</th>
                    <th className="p-2">平台归属</th>
                    <th className="p-2">行/件</th>
                    <th className="p-2">单独发运费</th>
                    <th className="p-2">状态</th>
                    <th className="p-2">回传</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.members.map((m) => {
                    const task = detail.writebackTasks.find((t) => t.memberId === m.id);
                    const platform = platformText(m);
                    return (
                      <tr key={m.id} className="border-t">
                        <td className="p-2">{m.orderNo}</td>
                        <td className="p-2">{platform ?? '非平台单（无平台可回传）'}</td>
                        <td className="p-2">
                          {m.lineCount} / {m.totalQty}
                        </td>
                        <td className="p-2">{money(m.separateShippingAmount)}</td>
                        <td className="p-2">{m.memberStatusLabel}</td>
                        <td className="p-2">
                          {task ? (
                            <span className="flex flex-wrap items-center gap-2">
                              <span>{task.statusLabel}</span>
                              {task.trackingNumber && <code>{task.trackingNumber}</code>}
                              {task.status !== 'CONFIRMED' && (
                                <Button
                                  size="sm"
                                  onClick={() => void writeback(detail.consolidation.id, m.id, task)}
                                  disabled={busy}
                                >
                                  登记物流号
                                </Button>
                              )}
                            </span>
                          ) : (
                            <span className="text-muted-foreground">不需回传</span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>

          <div className="mt-4">
            <p className="text-sm font-medium">事件流</p>
            <ul className="mt-1 space-y-1 text-xs text-muted-foreground">
              {detail.events.map((e) => (
                <li key={e.id}>
                  {isoText(e.occurredAt)} · {e.detail || e.eventType}
                </li>
              ))}
            </ul>
          </div>
        </SectionCard>
      )}

      {/* ------------------------------------------------ 4. 回传任务 */}
      <SectionCard
        title="物流号回传任务"
        description="一个包裹里有多少个平台订单就有多少条任务；平台不认跨订单的合并。"
      >
        <div className="flex flex-wrap gap-2">
          <Input
            className="max-w-xs"
            placeholder="平台单号"
            value={wbFilters.platformOrderRef}
            onChange={(e) => setWbFilters({ ...wbFilters, platformOrderRef: e.target.value })}
          />
          <select
            className="rounded border px-2 py-1 text-sm"
            value={wbFilters.status}
            onChange={(e) => setWbFilters({ ...wbFilters, status: e.target.value })}
          >
            {WRITEBACK_STATUS_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>

        {writebacks && writebacks.items.length === 0 ? (
          <EmptyState title="没有回传任务" description="合单里的非平台单不产生回传任务。" />
        ) : (
          writebacks && (
            <div className="mt-3 overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-left text-xs text-muted-foreground">
                  <tr>
                    <th className="p-2">平台</th>
                    <th className="p-2">平台单号</th>
                    <th className="p-2">物流号</th>
                    <th className="p-2">状态</th>
                    <th className="p-2">尝试</th>
                  </tr>
                </thead>
                <tbody>
                  {writebacks.items.map((t) => (
                    <tr key={t.id} className="border-t">
                      <td className="p-2">
                        {t.platformCode}
                        {t.shopCode ? ` / ${t.shopCode}` : ''}
                      </td>
                      <td className="p-2">{t.platformOrderRef || '—'}</td>
                      <td className="p-2">{t.trackingNumber || '—'}</td>
                      <td className="p-2">{t.statusLabel}</td>
                      <td className="p-2">{t.attemptCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )
        )}

        {writebacks && (
          <div className="mt-3 flex flex-wrap gap-3">
            <StatCard label="待回传" value={writebacks.pendingCount} />
            <StatCard label="平台拒绝" value={writebacks.rejectedCount} />
            <StatCard label="结果待查证" value={writebacks.pendingVerificationCount} />
          </div>
        )}
      </SectionCard>
    </div>
  );
}

/** 合单规则编辑：五个开关 + 单批上限 + 时间窗，改完即 upsert。 */
function RuleEditor({
  rule,
  onSaved,
}: {
  rule: NonNullable<Awaited<ReturnType<typeof fetchRule>>>;
  onSaved: () => Promise<void>;
}) {
  const [form, setForm] = useState({
    maxOrdersPerConsolidation: rule.maxOrdersPerConsolidation,
    allowCrossShop: rule.allowCrossShop,
    maxWindowHours: rule.maxWindowHours === null || rule.maxWindowHours === undefined
      ? ''
      : String(rule.maxWindowHours),
    requireSameCurrency: rule.requireSameCurrency,
    requireSamePaymentMethod: rule.requireSamePaymentMethod,
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const save = async () => {
    setBusy(true);
    setErr(null);
    try {
      await saveRule({
        maxOrdersPerConsolidation: form.maxOrdersPerConsolidation,
        allowCrossShop: form.allowCrossShop,
        // ⚠️ 契约把 maxWindowHours 生成成 number（OpenAPI 映射丢掉了 Integer 的可空性），
        // 而后端明确区分「null = 不限」与「0 = 任何跨时段都不可合」。
        // 这里必须传 null，所以做一次显式转换 —— 不能为了迁就类型而传 0。
        maxWindowHours: (form.maxWindowHours.trim() === ''
          ? null
          : Number(form.maxWindowHours)) as unknown as number,
        requireSameCurrency: form.requireSameCurrency,
        requireSamePaymentMethod: form.requireSamePaymentMethod,
        requireShippingAddress: true,
      });
      await onSaved();
    } catch (e) {
      setErr(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-3">
      <div className="grid gap-3 md:grid-cols-2">
        <FormField id="rule-max-orders" label="单批最多合几笔订单（下限 2）">
          <Input
            id="rule-max-orders"
            type="number"
            min={2}
            value={form.maxOrdersPerConsolidation}
            onChange={(e) =>
              setForm({ ...form, maxOrdersPerConsolidation: Number(e.target.value) })
            }
          />
        </FormField>
        <FormField id="rule-window" label="可合单时间窗（小时；留空 = 不限，不是 0）">
          <Input
            id="rule-window"
            value={form.maxWindowHours}
            onChange={(e) => setForm({ ...form, maxWindowHours: e.target.value })}
          />
        </FormField>
      </div>
      <div className="flex flex-wrap gap-4">
        {(
          [
            ['allowCrossShop', '允许跨店铺合并'],
            ['requireSameCurrency', '币种必须一致'],
            ['requireSamePaymentMethod', '支付方式必须一致'],
          ] as const
        ).map(([key, label]) => (
          <label key={key} className="flex items-center gap-1 text-sm">
            <input
              type="checkbox"
              checked={form[key]}
              onChange={(e) => setForm({ ...form, [key]: e.target.checked })}
            />
            {label}
          </label>
        ))}
      </div>
      {err && (
        <Notice tone="danger" title="规则未保存">
          {err}
        </Notice>
      )}
      <Button onClick={() => void save()} disabled={busy}>
        保存合单规则
      </Button>
    </div>
  );
}
