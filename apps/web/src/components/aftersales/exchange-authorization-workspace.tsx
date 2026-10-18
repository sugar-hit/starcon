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
  ExchangeCapabilities,
  ExchangeCaseOption,
  ExchangeDetail,
  ExchangeFilterOptions,
  ExchangeLineView,
  ExchangePage,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from '../../lib/api';
import {
  authorizeExchange,
  cancelExchange,
  canCancel,
  cancelBlocking,
  decisionBadge,
  decisionTone,
  downstreamNotices,
  emptyHint,
  fetchExchangeCapabilities,
  fetchExchangeDetail,
  fetchExchangeFilters,
  fetchExchangeList,
  formatQty,
  formatTime,
  hardRules,
  reservationBadge,
  reservationTone,
  statusBadge,
  statusTone,
  unavailable,
  waitingReturnBadge,
  waitingReturnTone,
} from './exchange-authorization-model';

/**
 * 11.3.2.1 换货授权工作区。
 *
 * <p>蓝图：「<b>批准换货：确认可换数量与库存（要真实预占替换货）、生成新出库安排，
 * 保留原单追溯关系。</b>」
 * 加粗句：「<b>换货必须同时预占替换货并等待旧货回寄，否则会出现只发不收的损失。</b>」
 *
 * <p><b>本页面对那句加粗句负责的四件事</b>：
 *
 * <ol>
 *   <li><b>授权对话框里固定显示后端下发的硬规则原文</b>。运营点下「批准」之前，
 *       先看到「预占替换货 + 等旧货回寄，缺一不可」—— 不知道后果的批准不算批准。</li>
 *   <li><b>每行把预占与旧货回寄分成两列显示</b>。这两列缺任何一列，
 *       那一行就处在只发不收的危险状态；「未预占」被渲染成红色而不是空白，
 *       因为空白会被读成「还没显示」。</li>
 *   <li><b>撤销会真去仓库释放预占</b>，所以按钮旁写明这一点，并且带上 expectedVersion
 *       交给后端做乐观锁 —— 两个人同时点撤销时后到的那个会被 409 拒掉。</li>
 *   <li><b>未交付的下游如实写出归属模块</b>（新出库 9.3.3.1、旧货回寄 11.2.2.2），
 *       而不是让「没有出库单」看起来像出错。</li>
 * </ol>
 */
export function ExchangeAuthorizationWorkspace() {
  const [params, setParams] = useState({ status: '', decision: '', keyword: '', page: 0, size: 20 });
  const [page, setPage] = useState<ExchangePage | null>(null);
  const [capabilities, setCapabilities] = useState<ExchangeCapabilities | null>(null);
  const [filters, setFilters] = useState<ExchangeFilterOptions | null>(null);
  const [detail, setDetail] = useState<ExchangeDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // ---- 授权对话框 ----
  const [authorizing, setAuthorizing] = useState(false);
  const [caseId, setCaseId] = useState('');
  const [caseLineId, setCaseLineId] = useState('');
  const [warehouseId, setWarehouseId] = useState('');
  const [legalEntityId, setLegalEntityId] = useState('');
  const [replacementSkuId, setReplacementSkuId] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [allowPartial, setAllowPartial] = useState(false);
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
        fetchExchangeList(params),
        fetchExchangeCapabilities(),
        fetchExchangeFilters(),
      ]);
      setPage(list);
      setCapabilities(caps);
      setFilters(opts);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '换货授权加载失败');
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
      setDetail(await fetchExchangeDetail(id));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '换货授权详情加载失败');
    }
  }, []);

  const selectedCase: ExchangeCaseOption | undefined = useMemo(
    () => filters?.exchangeCases.find((c) => c.caseId === caseId),
    [filters, caseId],
  );

  const selectedLine = useMemo(
    () => selectedCase?.lines?.find((l) => l.caseLineId === caseLineId),
    [selectedCase, caseLineId],
  );

  const submitAuthorize = async () => {
    if (!caseId || !caseLineId || !warehouseId || !legalEntityId || !replacementSkuId) {
      setDialogError('换货授权必须给全：售后单、商品行、履约仓、法人主体、替换商品');
      return;
    }
    setSubmitting(true);
    setDialogError(null);
    try {
      const created = await authorizeExchange({
        caseId,
        warehouseId,
        legalEntityId,
        lines: [
          {
            caseLineId,
            replacementSkuId,
            requestedQuantity: Number(quantity),
            replacementReason: '买家要求换货',
          },
        ],
        allowPartial,
        reasonNote: reasonNote || null,
      });
      setAuthorizing(false);
      setNotice(`换货授权 ${created.authorization.authorizationNo} 已成立，替换货已真实预占。`);
      setDetail(created);
      await load();
    } catch (e) {
      // 后端的拒绝理由原样显示：库存不足差多少、替换品停售、重复换货，都有自己的那句话。
      setDialogError(e instanceof ApiError ? e.message : '换货授权失败');
    } finally {
      setSubmitting(false);
    }
  };

  const submitCancel = async () => {
    if (!detail) return;
    setSubmitting(true);
    try {
      const cancelled = await cancelExchange(detail.authorization.id, {
        expectedVersion: detail.authorization.version,
        reason: cancelReason || null,
      });
      setCancelling(false);
      setCancelReason('');
      setNotice('换货授权已撤销，替换货预占已释放，新发货需求已取消。');
      setDetail(cancelled);
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

  return (
    <div className="space-y-6">
      <PageHeader
        title="换货授权"
        description="批准换货：确认可换数量与库存，真实预占替换货，生成新发货需求并等旧货回寄。"
        actions={
          <>
            <Button variant="outline" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} /> 刷新
            </Button>
            <Button
              onClick={() => {
                setAuthorizing(true);
                setDialogError(null);
                const first = filters?.exchangeCases?.[0];
                if (first) {
                  setCaseId(first.caseId);
                  setCaseLineId(first.lines?.[0]?.caseLineId ?? '');
                  setQuantity(first.lines?.[0] ? String(Number(first.lines[0].quantity)) : '1');
                }
              }}
              disabled={loading || (filters?.exchangeCaseCount ?? 0) === 0}
            >
              批准换货
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
        <StatCard label="换货授权总数" value={summary?.totalCount ?? 0} comparisonLabel="当前筛选下的真实条数" />
        <StatCard label="生效中" value={summary?.activeCount ?? 0} comparisonLabel="预占仍占着货" />
        <StatCard label="已撤销" value={summary?.cancelledCount ?? 0} comparisonLabel="预占已释放" />
        <StatCard
          label="等旧货回寄"
          value={formatQty(summary?.totalWaitingReturnQuantity)}
          comparisonLabel="发了替换货但旧货还没回来的量"
        />
      </div>

      <SectionCard title="筛选">
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="ex-status" label="状态">
            <select
              id="ex-status"
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
          <FormField id="ex-decision" label="授权结论">
            <select
              id="ex-decision"
              className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm"
              value={params.decision}
              onChange={(e: ChangeEvent<HTMLSelectElement>) =>
                setParams({ ...params, decision: e.target.value, page: 0 })
              }
            >
              <option value="">全部</option>
              {(filters?.decisions ?? []).map((d, i) => (
                <option key={d} value={d}>
                  {filters?.decisionLabels?.[i] ?? d}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="ex-keyword" label="关键字" hint="授权号 / 售后号 / 原单号 / 替换商品">
            <Input
              id="ex-keyword"
              value={params.keyword}
              placeholder="例如 EX2026 / AS-0001 / SKU 编码"
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
        <EmptyState title="还没有换货授权" description={emptyHint(capabilities)} />
      )}

      {page && page.items.length > 0 && (
        <SectionCard title={`换货授权（${page.total}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground border-b text-left">
                  <th className="p-2">授权号</th>
                  <th className="p-2">售后单 / 原单</th>
                  <th className="p-2">状态</th>
                  <th className="p-2">结论</th>
                  <th className="p-2">申请 / 授权数量</th>
                  <th className="p-2">预占</th>
                  <th className="p-2">旧货回寄</th>
                  <th className="p-2">授权时间</th>
                  <th className="p-2" />
                </tr>
              </thead>
              <tbody>
                {page.items.map((r) => (
                  <tr key={r.id} className="border-b last:border-0">
                    <td className="p-2 font-mono text-xs">{r.authorizationNo}</td>
                    <td className="p-2">
                      <div>{r.caseNo}</div>
                      <div className="text-muted-foreground text-xs">{unavailable(r.orderNo)}</div>
                    </td>
                    <td className="p-2">
                      <Badge tone={statusTone(r.status)}>{statusBadge(r)}</Badge>
                    </td>
                    <td className="p-2">
                      <Badge tone={decisionTone(r.decision)}>{decisionBadge(r)}</Badge>
                    </td>
                    <td className="p-2">
                      {formatQty(r.requestedQuantity)} / {formatQty(r.authorizedQuantity)}
                    </td>
                    <td className="p-2">
                      {r.activeReservationCount} 笔占用 / {r.releasedReservationCount} 笔已释放
                    </td>
                    <td className="p-2">{r.waitingReturnCount} 行等回寄</td>
                    <td className="p-2 text-xs">{formatTime(r.authorizedAt)}</td>
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
        <SectionCard title={`换货授权详情 · ${detail.authorization.authorizationNo}`}>
          <div className="grid gap-2 text-sm md:grid-cols-4">
            <div>
              <div className="text-muted-foreground">关联售后单</div>
              <div>{detail.authorization.caseNo}</div>
            </div>
            <div>
              <div className="text-muted-foreground">原销售订单</div>
              <div>{unavailable(detail.authorization.orderNo)}</div>
            </div>
            <div>
              <div className="text-muted-foreground">申请 / 授权数量</div>
              <div>
                {formatQty(detail.authorization.requestedQuantity)} /{' '}
                {formatQty(detail.authorization.authorizedQuantity)}
              </div>
            </div>
            <div>
              <div className="text-muted-foreground">状态 / 结论</div>
              <div>
                {statusBadge(detail.authorization)} · {decisionBadge(detail.authorization)}
              </div>
            </div>
          </div>

          <div className="mt-4 overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground border-b text-left">
                  <th className="p-2">原商品（换回什么）</th>
                  <th className="p-2">替换商品（换什么）</th>
                  <th className="p-2">申请 / 授权</th>
                  <th className="p-2">替换货预占</th>
                  <th className="p-2">新发货需求</th>
                  <th className="p-2">旧货回寄</th>
                </tr>
              </thead>
              <tbody>
                {detail.lines.map((l) => (
                  <tr key={l.id} className="border-b last:border-0">
                    <td className="p-2">
                      <div className="font-mono text-xs">{unavailable(l.originalSkuCode)}</div>
                      <div className="text-muted-foreground text-xs">{unavailable(l.originalSkuName)}</div>
                    </td>
                    <td className="p-2">
                      <div className="font-mono text-xs">{unavailable(l.replacementSkuCode)}</div>
                      <div className="text-muted-foreground text-xs">{unavailable(l.replacementSkuName)}</div>
                      {l.replacementReason && (
                        <div className="text-muted-foreground text-xs">{l.replacementReason}</div>
                      )}
                    </td>
                    <td className="p-2">
                      {formatQty(l.requestedQuantity)} / {formatQty(l.authorizedQuantity)}
                    </td>
                    <td className="p-2">
                      <Badge tone={reservationTone(l.reservationStatus)}>{reservationBadge(l)}</Badge>
                    </td>
                    <td className="p-2 text-xs">
                      {l.outboundDemandStatus === 'PENDING' ? '待出库接手' : l.outboundDemandStatus || '—'}
                    </td>
                    <td className="p-2">
                      <Badge tone={waitingReturnTone(l.returnRequirementStatus)}>
                        {waitingReturnBadge(l)}
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="mt-4 flex items-center gap-3">
            <Button
              variant="outline"
              disabled={!canCancel(detail) || submitting}
              onClick={() => setCancelling(true)}
            >
              撤销换货（会释放预占）
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

      {/* ---------------- 授权对话框 ---------------- */}
      <Modal open={authorizing} onOpenChange={setAuthorizing}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>批准换货</ModalTitle>
            <ModalDescription>
              预占是**真实**的仓库写操作：拿不到预占应答，整笔授权会失败并把已登记的预占释放掉。
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
            <FormField id="ex-case" label="换货售后单" required>
              <select
                id="ex-case"
                className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm"
                value={caseId}
                onChange={(e: ChangeEvent<HTMLSelectElement>) => {
                  setCaseId(e.target.value);
                  // 换单时把商品行一并重置：上一张单的行 id 带到这一张上，
                  // 后端会以「商品行不属于这张售后单」拒掉，而那是运营自己看不懂的错。
                  setCaseLineId('');
                  const next = filters?.exchangeCases.find((c) => c.caseId === e.target.value);
                  setQuantity(next?.lines?.[0] ? String(Number(next.lines[0].quantity)) : '1');
                }}
              >
                <option value="">请选择</option>
                {(filters?.exchangeCases ?? []).map((c) => (
                  <option key={c.caseId} value={c.caseId}>
                    {c.caseNo} · {unavailable(c.orderNo)} · 申请 {formatQty(c.requestedQuantity)}
                  </option>
                ))}
              </select>
            </FormField>

            {selectedCase && (
              <FormField id="ex-case-line" label="换哪一条商品行" required>
                <select
                  id="ex-case-line"
                  className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm"
                  value={caseLineId}
                  onChange={(e: ChangeEvent<HTMLSelectElement>) => {
                    setCaseLineId(e.target.value);
                    const line = selectedCase.lines?.find((l) => l.caseLineId === e.target.value);
                    if (line) setQuantity(String(Number(line.quantity)));
                  }}
                >
                  <option value="">请选择</option>
                  {(selectedCase.lines ?? []).map((l) => (
                    <option key={l.caseLineId} value={l.caseLineId}>
                      {unavailable(l.skuCode)} · {unavailable(l.skuName)} · 涉及 {formatQty(l.quantity)}
                    </option>
                  ))}
                </select>
              </FormField>
            )}

            {selectedLine && (
              <p className="text-muted-foreground text-xs">
                要换回的是 {unavailable(selectedLine.skuCode)}（{unavailable(selectedLine.skuName)}），
                售后单上登记涉及 {formatQty(selectedLine.quantity)}。
              </p>
            )}

            <FormField id="ex-warehouse" label="履约仓 UUID" required hint="预占圈在这个仓的库存">
              <Input
                id="ex-warehouse"
                value={warehouseId}
                placeholder="00000000-0000-0000-0000-000000000000"
                onChange={(e) => setWarehouseId(e.target.value)}
              />
            </FormField>
            <FormField id="ex-legal" label="法人主体 UUID" required>
              <Input
                id="ex-legal"
                value={legalEntityId}
                placeholder="00000000-0000-0000-0000-000000000000"
                onChange={(e) => setLegalEntityId(e.target.value)}
              />
            </FormField>
            <FormField id="ex-sku" label="替换商品 SKU UUID" required hint="必须是 catalog 里的在售 SKU">
              <Input
                id="ex-sku"
                value={replacementSkuId}
                placeholder="00000000-0000-0000-0000-000000000000"
                onChange={(e) => setReplacementSkuId(e.target.value)}
              />
            </FormField>
            <FormField id="ex-qty" label="换货数量" required>
              <Input
                id="ex-qty"
                value={quantity}
                inputMode="decimal"
                onChange={(e) => setQuantity(e.target.value)}
              />
            </FormField>
            <FormField
              id="ex-partial"
              label="允许部分授权"
              hint="勾选后按可售量少批（结论记「部分批准」）；不勾选则凑不齐就整笔拒绝，绝不悄悄少发"
            >
              <Input
                id="ex-partial"
                type="checkbox"
                checked={allowPartial}
                onChange={(e) => setAllowPartial(e.target.checked)}
              />
            </FormField>
            <FormField id="ex-note" label="授权说明">
              <Input
                id="ex-note"
                value={reasonNote}
                placeholder="例如：买家确认换同款大一号"
                onChange={(e) => setReasonNote(e.target.value)}
              />
            </FormField>
          </div>

          {dialogError && <p className="text-destructive text-sm">{dialogError}</p>}

          <ModalFooter>
            <Button variant="outline" onClick={() => setAuthorizing(false)} disabled={submitting}>
              取消
            </Button>
            <Button onClick={() => void submitAuthorize()} disabled={submitting}>
              {submitting ? '正在预占替换货…' : '批准并预占替换货'}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---------------- 撤销对话框 ---------------- */}
      <Modal open={cancelling} onOpenChange={setCancelling}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>撤销换货</ModalTitle>
            <ModalDescription>
              撤销会**真去仓库释放预占**，并取消这张单的新发货需求、把旧货回寄要求置为免回。
              释放不成功则整笔撤销失败 —— 不会出现「本地说撤了、仓库还占着货」。
            </ModalDescription>
          </ModalHeader>
          <FormField id="ex-cancel-reason" label="撤销原因">
            <Input
              id="ex-cancel-reason"
              value={cancelReason}
              placeholder="例如：买家改主意，不换了"
              onChange={(e) => setCancelReason(e.target.value)}
            />
          </FormField>
          <ModalFooter>
            <Button variant="outline" onClick={() => setCancelling(false)} disabled={submitting}>
              再想想
            </Button>
            <Button variant="destructive" onClick={() => void submitCancel()} disabled={submitting}>
              {submitting ? '正在释放预占…' : '确认撤销并释放预占'}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}

export type { ExchangeLineView };
