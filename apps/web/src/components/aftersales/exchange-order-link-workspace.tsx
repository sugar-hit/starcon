'use client';

import type { ChangeEvent } from 'react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, ShieldAlert } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
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
  ExchangeOrderCapabilities,
  ExchangeOrderDetail,
  ExchangeOrderFilterOptions,
  ExchangeOrderPage,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from '../../lib/api';
import {
  authorizationBlocker,
  cancelBlocking,
  cancelExchangeOrder,
  canCancel,
  canSubmit,
  canSync,
  closureBlockers,
  closureHeadline,
  closureLabel,
  closureTone,
  createExchangeOrder,
  downstreamNotices,
  emptyHint,
  fetchExchangeOrderCapabilities,
  fetchExchangeOrderDetail,
  fetchExchangeOrderFilters,
  fetchExchangeOrderList,
  formatAmount,
  formatQty,
  formatTime,
  hardRules,
  newOrderLabel,
  newOrderTone,
  oldGoodsLabel,
  oldGoodsTone,
  shipmentBadge,
  shipmentTone,
  submitBlocking,
  submitExchangeOrder,
  syncExchangeOrder,
  syncExplainer,
  unavailable,
} from './exchange-order-link-model';

/**
 * 11.3.2.2 换货新单工作区。
 *
 * <p>蓝图：「<b>建立换货关联（原售后 ↔ 新销售单/发货单），跟踪换货闭环状态，防止重复换货。</b>」
 * 关系：「<b>换货新单不修改原出库事实，原单保持完整可追溯。</b>」
 *
 * <p><b>本页面最要紧的一件事是不替后端编状态</b>。后端已经做到「读不到真实发货记录
 * 就绝不置为已发出」，但界面若把「已建立」显示成一个说不清的灰状态，
 * 运营就会以为「系统在处理」—— 于是「8 出库根本没接手」永远没人知道。
 * 所以：
 *
 * <ol>
 *   <li><b>「已建立」显示成警告色</b>而不是中性色：它是一张要人推的单。</li>
 *   <li><b>详情固定显示后端下发的「没闭环的真实原因」</b>（closureBlockers），
 *       一条也不改写；空了就说明真的闭环了。</li>
 *   <li><b>建单对话框里写明「新单是真的销售单」</b>，让操作员知道这张单会真的进审单队列、
 *       真的会被 9 订单与 8 出库接手，不是本模块自己的一串编号。</li>
 *   <li><b>原单追溯两列（售后单 / 原销售单）不折叠</b>：
 *       蓝图那句「原单保持完整可追溯」必须在界面上看得见。</li>
 * </ol>
 */
export function ExchangeOrderLinkWorkspace() {
  const [params, setParams] = useState({ status: '', keyword: '', sort: 'attention_first', page: 0, size: 20 });
  const [page, setPage] = useState<ExchangeOrderPage | null>(null);
  const [capabilities, setCapabilities] = useState<ExchangeOrderCapabilities | null>(null);
  const [filters, setFilters] = useState<ExchangeOrderFilterOptions | null>(null);
  const [detail, setDetail] = useState<ExchangeOrderDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // ---- 建单对话框 ----
  const [creating, setCreating] = useState(false);
  const [authorizationId, setAuthorizationId] = useState('');
  const [reasonNote, setReasonNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [dialogError, setDialogError] = useState<string | null>(null);

  // ---- 撤销对话框 ----
  const [cancelling, setCancelling] = useState(false);
  const [cancelReason, setCancelReason] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [list, caps, opts] = await Promise.all([
        fetchExchangeOrderList(params),
        fetchExchangeOrderCapabilities(),
        fetchExchangeOrderFilters(),
      ]);
      setPage(list);
      setCapabilities(caps);
      setFilters(opts);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '换货新单加载失败');
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (id: string) => {
    setNotice(null);
    setError(null);
    try {
      setDetail(await fetchExchangeOrderDetail(id));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '换货新单详情加载失败');
    }
  }, []);

  const selectedAuthorization = useMemo(
    () => filters?.authorizations?.find((a) => a.authorizationId === authorizationId),
    [filters, authorizationId],
  );

  const blocker = useMemo(() => authorizationBlocker(selectedAuthorization), [selectedAuthorization]);

  const submitCreate = async () => {
    if (!authorizationId) {
      setDialogError('请先选择一张换货授权单');
      return;
    }
    setSubmitting(true);
    setDialogError(null);
    try {
      const created = await createExchangeOrder({
        authorizationId,
        reasonNote: reasonNote.trim() || null,
      });
      setCreating(false);
      setReasonNote('');
      setNotice(`换货新单 ${created.link.newOrderNo} 已建立：它是真的销售单，已进审单队列待提交。`);
      setDetail(created);
      await load();
    } catch (e) {
      setDialogError(e instanceof ApiError ? e.message : '建立换货新单失败');
    } finally {
      setSubmitting(false);
    }
  };

  const submitOrder = async () => {
    if (!detail) return;
    setSubmitting(true);
    try {
      const next = await submitExchangeOrder(detail.link.id, {
        expectedVersion: detail.link.version,
        reason: '提交进审单队列',
      });
      setNotice(`换货新单 ${next.link.newOrderNo} 已提交进审单队列，等待 9.2 审单与 8 出库接手。`);
      setDetail(next);
      await load();
    } catch (e) {
      setNotice(e instanceof ApiError ? e.message : '提交失败');
    } finally {
      setSubmitting(false);
    }
  };

  const syncOrder = async () => {
    if (!detail) return;
    setSubmitting(true);
    try {
      const next = await syncExchangeOrder(detail.link.id, {
        expectedVersion: detail.link.version,
        reason: '同步闭环状态',
      });
      setNotice(closureHeadline(next));
      setDetail(next);
      await load();
    } catch (e) {
      setNotice(e instanceof ApiError ? e.message : '同步失败');
    } finally {
      setSubmitting(false);
    }
  };

  const confirmCancel = async () => {
    if (!detail) return;
    setSubmitting(true);
    try {
      const next = await cancelExchangeOrder(detail.link.id, {
        expectedVersion: detail.link.version,
        reason: cancelReason.trim() || null,
      });
      setCancelling(false);
      setCancelReason('');
      setNotice('换货新单已撤销，新发货需求已同步取消。替换货预占仍被仓库圈着 —— 如需真正释放，请到「换货授权」撤销那张授权。');
      setDetail(next);
      await load();
    } catch (e) {
      setNotice(e instanceof ApiError ? e.message : '撤销失败');
    } finally {
      setSubmitting(false);
    }
  };

  const summary = page?.summary;
  const rules = hardRules(capabilities);
  const notices = downstreamNotices(capabilities);
  const blockers = closureBlockers(detail);

  return (
    <div className="space-y-6">
      <PageHeader
        title="换货新单"
        description="建立换货关联（原售后 ↔ 新销售单），跟踪换货闭环状态，防止重复换货。"
        actions={
          <>
            <Button variant="outline" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} /> 刷新
            </Button>
            <Button
              onClick={() => {
                setCreating(true);
                setDialogError(null);
                setAuthorizationId(filters?.authorizations?.[0]?.authorizationId ?? '');
              }}
              disabled={loading || (filters?.authorizationCount ?? 0) === 0}
            >
              建立换货新单
            </Button>
          </>
        }
      />

      {/* 硬规则：加粗那句不许只活在代码注释里。 */}
      {rules.length > 0 && (
        <SectionCard>
          <div className="flex items-start gap-2 text-sm">
            <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" />
            <ul className="space-y-1">
              {rules.map((r) => (
                <li key={r}>{r}</li>
              ))}
            </ul>
          </div>
        </SectionCard>
      )}

      {notices.map((n) => (
        <SectionCard key={n}>
          <p className="text-muted-foreground text-sm">{n}</p>
        </SectionCard>
      ))}

      <div className="grid gap-4 md:grid-cols-4">
        <StatCard label="换货新单总数" value={summary?.totalCount ?? 0} comparisonLabel="当前筛选下的真实条数" />
        <StatCard
          label="已建立待提交"
          value={summary?.createdCount ?? 0}
          comparisonLabel="新单还没进审单队列，要人推"
        />
        <StatCard
          label="替换货已发出"
          value={summary?.dispatchedCount ?? 0}
          comparisonLabel="已读到真实发货记录"
        />
        <StatCard
          label="换货已闭环"
          value={summary?.closedCount ?? 0}
          comparisonLabel="已发出且旧货已结清"
        />
      </div>

      <SectionCard title="筛选">
        <div className="grid gap-3 md:grid-cols-3">
          <FormField id="eol-status" label="闭环状态">
            <select
              id="eol-status"
              className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm"
              value={params.status}
              onChange={(e: ChangeEvent<HTMLSelectElement>) =>
                setParams({ ...params, status: e.target.value, page: 0 })
              }
            >
              <option value="">全部</option>
              {(filters?.statuses ?? []).map((s, i) => (
                <option key={s} value={s}>
                  {filters?.statusLabels?.[i] ?? s}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="eol-sort" label="排序">
            <select
              id="eol-sort"
              className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm"
              value={params.sort}
              onChange={(e: ChangeEvent<HTMLSelectElement>) =>
                setParams({ ...params, sort: e.target.value, page: 0 })
              }
            >
              {(filters?.sorts ?? []).map((s, i) => (
                <option key={s} value={s}>
                  {filters?.sortLabels?.[i] ?? s}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="eol-keyword" label="关键字" hint="换货新单号 / 新销售单号 / 售后号 / 原单号 / SKU">
            <Input
              id="eol-keyword"
              value={params.keyword}
              placeholder="例如 EXL2026 / EXO2026 / AS-0001"
              onChange={(e) => setParams({ ...params, keyword: e.target.value, page: 0 })}
            />
          </FormField>
        </div>
      </SectionCard>

      {error && (
        <SectionCard>
          <p className="text-destructive text-sm">{error}</p>
        </SectionCard>
      )}

      {loading && !page && <p className="text-muted-foreground text-sm">加载中…</p>}

      {!loading && page && page.items.length === 0 && (
        <EmptyState title="还没有换货新单" description={emptyHint(capabilities)} />
      )}

      {page && page.items.length > 0 && (
        <SectionCard title={`换货新单（${page.total}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground border-b text-left">
                  <th className="p-2">换货新单号</th>
                  <th className="p-2">售后单 / 原销售单</th>
                  <th className="p-2">新销售单</th>
                  <th className="p-2">闭环状态</th>
                  <th className="p-2">发货</th>
                  <th className="p-2">旧货</th>
                  <th className="p-2">换货数量</th>
                  <th className="p-2">建单时间</th>
                  <th className="p-2" />
                </tr>
              </thead>
              <tbody>
                {page.items.map((r) => (
                  <tr key={r.id} className="border-b last:border-0">
                    <td className="p-2 font-mono text-xs">{r.linkNo}</td>
                    <td className="p-2">
                      <div>{r.caseNo}</div>
                      <div className="text-muted-foreground text-xs">{unavailable(r.originalOrderNo)}</div>
                    </td>
                    <td className="p-2">
                      <div className="font-mono text-xs">{unavailable(r.newOrderNo)}</div>
                      <Badge tone={newOrderTone(r.newOrderStatus)}>{newOrderLabel(r)}</Badge>
                    </td>
                    <td className="p-2">
                      <Badge tone={closureTone(r.closureStatus)}>{closureLabel(r)}</Badge>
                    </td>
                    <td className="p-2">
                      <Badge tone={shipmentTone(r)}>{shipmentBadge(r)}</Badge>
                    </td>
                    <td className="p-2">
                      <Badge tone={oldGoodsTone(r.oldGoodsReturnStatus)}>{oldGoodsLabel(r)}</Badge>
                    </td>
                    <td className="p-2">{formatQty(r.totalQuantity)}</td>
                    <td className="p-2 text-xs">{formatTime(r.createdAt)}</td>
                    <td className="p-2">
                      <Button size="sm" variant="ghost" onClick={() => void openDetail(r.id)}>
                        详情
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="mt-3 flex items-center justify-between text-sm">
            <span className="text-muted-foreground">
              第 {page.page + 1} 页 · 每页 {page.size} 条 · 共 {page.total} 条
            </span>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={page.page <= 0}
                onClick={() => setParams({ ...params, page: page.page - 1 })}
              >
                上一页
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={(page.page + 1) * page.size >= page.total}
                onClick={() => setParams({ ...params, page: page.page + 1 })}
              >
                下一页
              </Button>
            </div>
          </div>
        </SectionCard>
      )}

      {/* ---------------- 详情 ---------------- */}
      {detail && (
        <SectionCard title={`换货新单详情 · ${detail.link.linkNo}`}>
          {/* 蓝图关系句：原单保持完整可追溯 —— 这两列不折叠。 */}
          <div className="grid gap-2 text-sm md:grid-cols-4">
            <div>
              <div className="text-muted-foreground">原售后单</div>
              <div>{detail.link.caseNo}</div>
              <div className="text-muted-foreground text-xs">授权 {detail.link.authorizationNo}</div>
            </div>
            <div>
              <div className="text-muted-foreground">原销售订单（只读，不被修改）</div>
              <div>{unavailable(detail.link.originalOrderNo)}</div>
            </div>
            <div>
              <div className="text-muted-foreground">新销售订单（真单）</div>
              <div className="font-mono text-xs">{unavailable(detail.link.newOrderNo)}</div>
              <Badge tone={newOrderTone(detail.link.newOrderStatus)}>
                {newOrderLabel(detail.link)}
              </Badge>
            </div>
            <div>
              <div className="text-muted-foreground">闭环状态</div>
              <Badge tone={closureTone(detail.link.closureStatus)}>{closureLabel(detail.link)}</Badge>
            </div>
          </div>

          {/* 没闭环的真实原因：后端下发什么就显示什么。 */}
          <div className="bg-muted/40 mt-3 rounded-md p-3 text-sm">
            <div className="font-medium">闭环判定</div>
            <div className="text-muted-foreground mt-1">{closureHeadline(detail)}</div>
            {blockers.length > 0 && (
              <ul className="text-muted-foreground mt-1 list-disc space-y-0.5 pl-5 text-xs">
                {blockers.map((b) => (
                  <li key={b}>{b}</li>
                ))}
              </ul>
            )}
          </div>

          <div className="grid gap-2 text-sm md:grid-cols-4">
            <div>
              <div className="text-muted-foreground">发货记录</div>
              <div>{shipmentBadge(detail.link)}</div>
            </div>
            <div>
              <div className="text-muted-foreground">首次发货时间</div>
              <div>{formatTime(detail.link.firstShippedAt)}</div>
            </div>
            <div>
              <div className="text-muted-foreground">换货数量 / 行数</div>
              <div>
                {formatQty(detail.link.totalQuantity)} · {detail.link.lineCount} 行
              </div>
            </div>
            <div>
              <div className="text-muted-foreground">新单金额（换货不二次收费）</div>
              <div>
                {formatAmount(detail.link.newOrderAmount)} {detail.link.currency}
              </div>
            </div>
          </div>

          <div className="mt-4 overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground border-b text-left">
                  <th className="p-2">原商品（替哪一件换的）</th>
                  <th className="p-2">替换商品</th>
                  <th className="p-2">数量</th>
                  <th className="p-2">新单行号</th>
                </tr>
              </thead>
              <tbody>
                {detail.lines.map((l) => (
                  <tr key={l.id} className="border-b last:border-0">
                    <td className="p-2 font-mono text-xs">{unavailable(l.originalSkuCode)}</td>
                    <td className="p-2">
                      <div className="font-mono text-xs">{unavailable(l.skuCode)}</div>
                      <div className="text-muted-foreground text-xs">{unavailable(l.skuName)}</div>
                    </td>
                    <td className="p-2">{formatQty(l.quantity)}</td>
                    <td className="p-2 text-xs">第 {l.newOrderLineNo} 行</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* 闭环事件时间线：每一次状态变化是谁做的都查得到。 */}
          <div className="mt-4">
            <div className="text-muted-foreground text-sm">闭环时间线</div>
            <ul className="mt-1 space-y-1 text-xs">
              {detail.events.map((e) => (
                <li key={e.id}>
                  <span className="text-muted-foreground">{formatTime(e.occurredAt)}</span>{' '}
                  <span className="font-medium">{e.eventLabel}</span>{' '}
                  <span className="text-muted-foreground">{e.detail}</span>
                </li>
              ))}
            </ul>
          </div>

          <div className="mt-4 flex flex-wrap items-center gap-3">
            <Button variant="outline" disabled={!canSubmit(detail) || submitting} onClick={() => void submitOrder()}>
              提交进审单队列
            </Button>
            {submitBlocking(detail) && (
              <span className="text-muted-foreground text-xs">{submitBlocking(detail)}</span>
            )}
            <Button variant="outline" disabled={!canSync(detail) || submitting} onClick={() => void syncOrder()}>
              同步闭环状态
            </Button>
            <span className="text-muted-foreground text-xs">{syncExplainer()}</span>
            <Button variant="outline" disabled={!canCancel(detail) || submitting} onClick={() => setCancelling(true)}>
              撤销换货新单
            </Button>
            {cancelBlocking(detail) && (
              <span className="text-muted-foreground text-xs">{cancelBlocking(detail)}</span>
            )}
          </div>

          <div className="mt-4">
            <Button size="sm" variant="ghost" onClick={() => setDetail(null)}>
              收起详情
            </Button>
          </div>
        </SectionCard>
      )}

      {notice && (
        <SectionCard>
          <p className="text-sm">{notice}</p>
        </SectionCard>
      )}

      {/* ---------------- 建单对话框 ---------------- */}
      <Modal open={creating} onOpenChange={setCreating}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>建立换货新单</ModalTitle>
            <ModalDescription>
              这张单会**真的**建在销售订单里（不是本模块自己的一串编号）：9 订单与 8 出库接手的就是它。
              收货地址与客户快照照抄原订单，金额恒为 0 —— 换货不二次收费。
            </ModalDescription>
          </ModalHeader>

          {rules.length > 0 && (
            <ul className="bg-muted/40 text-muted-foreground space-y-1 rounded-md p-3 text-xs">
              {rules.map((r) => (
                <li key={r}>· {r}</li>
              ))}
            </ul>
          )}

          <div className="space-y-3">
            <FormField id="eol-auth" label="换货授权单" required>
              <select
                id="eol-auth"
                className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm"
                value={authorizationId}
                onChange={(e: ChangeEvent<HTMLSelectElement>) => setAuthorizationId(e.target.value)}
              >
                <option value="">请选择</option>
                {(filters?.authorizations ?? []).map((a) => (
                  <option key={a.authorizationId} value={a.authorizationId}>
                    {a.authorizationNo} · {a.caseNo} · 换 {a.authorizedQuantity}
                  </option>
                ))}
              </select>
            </FormField>

            {blocker && <p className="text-destructive text-xs">{blocker}</p>}

            {selectedAuthorization && (
              <div className="bg-muted/40 text-muted-foreground rounded-md p-3 text-xs">
                <div>原销售订单：{unavailable(selectedAuthorization.originalOrderNo)}</div>
                <div className="mt-1">授权结论：{selectedAuthorization.decisionLabel}</div>
                <ul className="mt-1 space-y-0.5">
                  {selectedAuthorization.lines.map((l) => (
                    <li key={l.authorizationLineId}>
                      {unavailable(l.originalSkuCode)} → {unavailable(l.skuCode)} × {formatQty(l.quantity)}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            <FormField id="eol-reason" label="备注">
              <Input
                id="eol-reason"
                value={reasonNote}
                placeholder="例如：买家换大一码"
                onChange={(e) => setReasonNote(e.target.value)}
              />
            </FormField>

            {dialogError && <p className="text-destructive text-sm">{dialogError}</p>}
          </div>

          <ModalFooter>
            <Button variant="outline" onClick={() => setCreating(false)} disabled={submitting}>
              取消
            </Button>
            <Button onClick={() => void submitCreate()} disabled={submitting || Boolean(blocker)}>
              {submitting ? '建立中…' : '建立换货新单'}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------------- 撤销对话框 ---------------- */}
      <Modal open={cancelling} onOpenChange={setCancelling}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>撤销换货新单</ModalTitle>
            <ModalDescription>
              只在新单仍是草稿且从未发货时可撤。撤销会同步取消新发货需求；
              替换货预占仍被仓库圈着 —— 如需真正释放预占，请到「换货授权」撤销那张换货授权。
            </ModalDescription>
          </ModalHeader>

          <FormField id="eol-cancel-reason" label="撤销原因">
            <Input
              id="eol-cancel-reason"
              value={cancelReason}
              placeholder="例如：建错单了"
              onChange={(e) => setCancelReason(e.target.value)}
            />
          </FormField>

          <ModalFooter>
            <Button variant="outline" onClick={() => setCancelling(false)} disabled={submitting}>
              再想想
            </Button>
            <Button variant="destructive" onClick={() => void confirmCancel()} disabled={submitting}>
              {submitting ? '撤销中…' : '确认撤销'}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
