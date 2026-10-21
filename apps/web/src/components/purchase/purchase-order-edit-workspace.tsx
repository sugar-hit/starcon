'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { FileText, Plus, RefreshCw, Save, Send, Trash2, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  PurchaseOrderEditCapabilities,
  PurchaseOrderEditForm,
  PurchaseOrderEditLineInput,
  PurchaseOrderEditOptions,
  PurchaseOrderEditOrderInput,
  PurchaseOrderEditQuotePreview,
} from '../../../../../packages/api-contracts/src/index';
import {
  NO_DATA_TEXT,
  attachmentTypeLabel,
  conversionStatusLabel,
  createPurchaseOrder,
  emptyLine,
  emptyOrder,
  estimateTotal,
  fetchPurchaseOrderEditCapabilities,
  fetchPurchaseOrderEditOptions,
  fetchPurchaseOrderForm,
  fmtAmount,
  fmtDate,
  fmtQty,
  fmtRate,
  fxStatusLabel,
  orderToInput,
  previewPurchaseOrderQuote,
  priceSourceLabel,
  quoteSelectionLabel,
  savePurchaseOrder,
  stockStatusLabel,
  submitPurchaseOrder,
  taxStatusLabel,
  unavailableCapabilities,
} from './purchase-order-edit-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 6.3.1.2 采购单创建与编辑工作区。
 *
 * <p>蓝图：「创建/编辑采购单：选择供应商、添加 SKU 行（带出报价与库存快照）、填写数量/采购单位/
 * 单价/币种、约定交期与发货地址、备注与附件。支持草稿与提交。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>「无数据」不是 0</b>：汇率 / 税额 / 库存 / 箱规取不到时显示「无数据」，
 *       并把后端写的口径原样透出 —— 0 会被读成「不用折算 / 不用交税 / 一件都没有」。</li>
 *   <li><b>编辑中的总额是「本页试算」</b>：真正入账的金额由数据库触发器算，
 *       页面必须标出这一点，不能拿试算冒充入账数。</li>
 *   <li><b>报价并列时让用户选</b>：歧义时后端不替用户挑，页面把并列的候选逐条列出来。</li>
 *   <li><b>能力边界常驻</b>：未交付的下游（6.3.1.3 审批 / 12 财务应付与税额）原样列出。</li>
 *   <li>加载 / 空 / 错误三态齐全，且三态文案各不相同。</li>
 * </ul>
 *
 * <p>同一个组件服务**新建**与**编辑**两种场景（`orderId` 为空 = 新建）。
 */
export function PurchaseOrderEditWorkspace({ orderId }: { orderId?: string }) {
  const editing = Boolean(orderId);

  const [form, setForm] = useState<PurchaseOrderEditOrderInput>(emptyOrder());
  const [server, setServer] = useState<PurchaseOrderEditForm | null>(null);
  const [caps, setCaps] = useState<PurchaseOrderEditCapabilities | null>(null);
  const [options, setOptions] = useState<PurchaseOrderEditOptions | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [preview, setPreview] = useState<Record<number, PurchaseOrderEditQuotePreview>>({});

  // ---- 载入 ----
  const load = useCallback(async () => {
    if (!orderId) {
      setForm(emptyOrder());
      setServer(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const loaded = await fetchPurchaseOrderForm(orderId);
      setServer(loaded);
      setForm(orderToInput(loaded));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [orderId]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await fetchPurchaseOrderEditCapabilities());
      } catch {
        setCaps(null);
      }
      try {
        setOptions(await fetchPurchaseOrderEditOptions());
      } catch {
        setOptions(null);
      }
    })();
  }, []);

  // ---- 编辑态 ----
  const editable = editing ? (server?.editable ?? false) : true;
  const lockedReason = server?.editableReason ?? '';

  const patch = useCallback((next: Partial<PurchaseOrderEditOrderInput>) => {
    setForm((current) => ({ ...current, ...next }));
  }, []);

  const patchLine = useCallback((index: number, next: Partial<PurchaseOrderEditLineInput>) => {
    setForm((current) => ({
      ...current,
      lines: current.lines.map((line, i) => (i === index ? { ...line, ...next } : line)),
    }));
  }, []);

  const addLine = useCallback(() => {
    setForm((current) => ({
      ...current,
      lines: [...current.lines, emptyLine(current.lines.length + 1)],
    }));
  }, []);

  const removeLine = useCallback((index: number) => {
    setForm((current) => ({
      ...current,
      lines: current.lines
        .filter((_, i) => i !== index)
        .map((line, i) => ({ ...line, lineNo: i + 1 })),
    }));
  }, []);

  // ---- 报价 / 库存预览（带出报价与库存快照）----
  const runPreview = useCallback(
    async (index: number) => {
      const line = form.lines[index];
      if (!line || !line.skuId || !form.supplierRefId) {
        setError('要先选供应商并填 SKU 才能带出报价');
        return;
      }
      setBusy(true);
      setError('');
      try {
        const result = await previewPurchaseOrderQuote({
          skuId: line.skuId,
          supplierRefId: form.supplierRefId,
          quantity: line.quantity ?? null,
          at: form.promisedArrivalDate || null,
        });
        setPreview((current) => ({ ...current, [index]: result }));
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [form.lines, form.supplierRefId, form.promisedArrivalDate],
  );

  // ---- 保存 ----
  const save = useCallback(async () => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      if (editing && server) {
        const saved = await savePurchaseOrder(server.id, server.version, form);
        setServer(saved);
        setForm(orderToInput(saved));
        setNotice('草稿已保存（金额与行数由数据库重算）');
      } else {
        const created = await createPurchaseOrder(form);
        setServer(created);
        setForm(orderToInput(created));
        setNotice(`已建单 ${created.orderNo}（草稿）`);
      }
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, [editing, server, form]);

  // ---- 提交（冻结快照）----
  const submit = useCallback(async () => {
    if (!server) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const submitted = await submitPurchaseOrder(server.id, server.version, '');
      setServer(submitted);
      setForm(orderToInput(submitted));
      setNotice('已提交：单价、汇率与单位换算的快照在这一刻冻结');
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, [server]);

  // ---- 展示口径 ----
  const estimate = useMemo(() => estimateTotal(form.lines), [form.lines]);
  const unavailable = useMemo(() => unavailableCapabilities(caps), [caps]);
  const serverTotal = server?.amounts.totalAmount ?? null;

  if (loading) {
    return (
      <div className="space-y-4">
        <PageHeader
          title={editing ? '编辑采购单' : '新建采购单'}
          description="正在读取采购单表单…"
        />
        <SectionCard>
          <p className="text-copy-13 text-muted-foreground">正在加载采购单表单与建单参照…</p>
        </SectionCard>
      </div>
    );
  }

  if (error && !server && editing) {
    return (
      <div className="space-y-4">
        <PageHeader title="编辑采购单" description="读不到这张采购单" />
        <SectionCard>
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title="这张采购单读不到"
            description={`${error}（可能是不存在、不属于当前租户，或不在你的数据范围内。）`}
          />
        </SectionCard>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <PageHeader
        title={editing ? `编辑采购单 ${server?.orderNo ?? ''}` : '新建采购单'}
        description={
          editing
            ? '只有草稿能改；提交那一刻冻结单价、汇率与单位换算的快照。'
            : '选供应商、加 SKU 行、填数量与单价、约定交期与发货地址，落成一张草稿。'
        }
        actions={
          <>
            <Button variant="outline" size="sm" onClick={() => void load()} disabled={busy}>
              <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
              重新读取
            </Button>
            <Button
              variant="secondary"
              size="sm"
              onClick={() => void save()}
              disabled={busy || !editable}
            >
              <Save className="mr-1.5 h-3.5 w-3.5" />
              保存草稿
            </Button>
            <Button
              variant="brand"
              size="sm"
              onClick={() => void submit()}
              disabled={busy || !editable || !server}
            >
              <Send className="mr-1.5 h-3.5 w-3.5" />
              提交
            </Button>
          </>
        }
      />

      {error && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive text-copy-13 rounded-md border px-4 py-3">
          {error}
        </div>
      )}
      {notice && (
        <div className="border-success-soft bg-success-soft text-success text-copy-13 rounded-md border px-4 py-3">
          {notice}
        </div>
      )}
      {!editable && (
        <div className="border-warning-soft bg-warning-soft text-warning text-copy-13 rounded-md border px-4 py-3">
          {lockedReason}
        </div>
      )}

      {/* ---- 金额卡：总额以**后端**给的为准，编辑中的那个是「本页试算」 ---- */}
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="总额（服务器口径）"
          value={fmtAmount(serverTotal)}
          unit={server?.amounts.currency ?? form.currency}
          polarity="neutral"
          comparisonLabel={server ? '行金额与头金额由数据库重算' : '保存后由数据库重算'}
        />
        <StatCard
          label="本页试算"
          value={estimate === null ? NO_DATA_TEXT : fmtAmount(estimate)}
          unit={form.currency || '未选币种'}
          polarity="neutral"
          comparisonLabel="编辑中的估算，入账以服务器口径为准"
        />
        <StatCard
          label="本位币折算"
          value={
            server?.amounts.functionalAmount
              ? fmtAmount(server.amounts.functionalAmount)
              : NO_DATA_TEXT
          }
          unit={server?.amounts.functionalCurrency ?? '未冻结'}
          polarity="neutral"
          comparisonLabel={`汇率快照：${fxStatusLabel(server?.amounts.fxStatus)}`}
        />
        <StatCard
          label="税额"
          value={NO_DATA_TEXT}
          unit={taxStatusLabel(server?.amounts.taxStatus)}
          polarity="neutral"
          comparisonLabel="税率的权威在 12 财务，本模块不算"
        />
      </div>

      {/* ---- 基本信息 ---- */}
      <SectionCard title="基本信息" description="供应商、币种、法人、目标仓与承诺到货日。">
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          <label className="space-y-1.5">
            <span className="text-copy-13 text-muted-foreground">供应商</span>
            <select
              className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
              value={form.supplierRefId}
              disabled={!editable}
              onChange={(e) => {
                const picked = options?.suppliers.find((s) => s.roleId === e.target.value);
                patch({
                  supplierRefId: e.target.value,
                  supplierCode: picked?.partyCode ?? '',
                  supplierName: picked?.partyName ?? '',
                });
              }}
            >
              <option value="">请选择供应商</option>
              {options?.suppliers.map((supplier) => (
                <option key={supplier.roleId} value={supplier.roleId}>
                  {supplier.partyName}（{supplier.partyCode}）
                </option>
              ))}
            </select>
          </label>

          <label className="space-y-1.5">
            <span className="text-copy-13 text-muted-foreground">币种</span>
            <select
              className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
              value={form.currency}
              disabled={!editable}
              onChange={(e) => patch({ currency: e.target.value })}
            >
              <option value="">请选择币种</option>
              {options?.currencies.map((currency) => (
                <option key={currency.currency} value={currency.currency}>
                  {currency.currency}（{currency.source}）
                </option>
              ))}
            </select>
          </label>

          <label className="space-y-1.5">
            <span className="text-copy-13 text-muted-foreground">法人</span>
            <select
              className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
              value={form.legalEntityId ?? ''}
              disabled={!editable}
              onChange={(e) => {
                const picked = options?.legalEntities.find(
                  (entity) => entity.id === e.target.value,
                );
                patch({
                  legalEntityId: e.target.value || null,
                  legalEntityCode: picked?.code ?? '',
                  legalEntityName: picked?.name ?? '',
                });
              }}
            >
              <option value="">未指定法人</option>
              {options?.legalEntities.map((entity) => (
                <option key={entity.id} value={entity.id}>
                  {entity.name}（{entity.code}
                  {entity.baseCurrency ? ` · 本位币 ${entity.baseCurrency}` : ''}）
                </option>
              ))}
            </select>
          </label>

          <label className="space-y-1.5">
            <span className="text-copy-13 text-muted-foreground">目标仓</span>
            <select
              className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
              value={form.targetWarehouseId}
              disabled={!editable}
              onChange={(e) => {
                const picked = options?.warehouses.find((w) => w.id === e.target.value);
                patch({
                  targetWarehouseId: e.target.value,
                  targetWarehouseCode: picked?.code ?? '',
                  targetWarehouseName: picked?.name ?? '',
                });
              }}
            >
              <option value="">请选择目标仓</option>
              {options?.warehouses.map((warehouse) => (
                <option key={warehouse.id} value={warehouse.id}>
                  {warehouse.name}（{warehouse.code}）
                </option>
              ))}
            </select>
          </label>

          <label className="space-y-1.5">
            <span className="text-copy-13 text-muted-foreground">承诺到货日</span>
            <Input
              type="date"
              value={form.promisedArrivalDate}
              disabled={!editable}
              onChange={(e) => patch({ promisedArrivalDate: e.target.value })}
            />
          </label>

          <label className="space-y-1.5">
            <span className="text-copy-13 text-muted-foreground">备注</span>
            <Input
              value={form.note}
              disabled={!editable}
              placeholder="给供应商的说明、特殊要求…"
              onChange={(e) => patch({ note: e.target.value })}
            />
          </label>
        </div>
      </SectionCard>

      {/* ---- SKU 行 ---- */}
      <SectionCard
        title="SKU 行"
        description="数量、���购单位、单价与币种；点「带出报价」会把报价、箱规与库存快照带出来。"
        extra={
          <Button variant="outline" size="sm" onClick={addLine} disabled={!editable}>
            <Plus className="mr-1.5 h-3.5 w-3.5" />
            加一行
          </Button>
        }
      >
        {form.lines.length === 0 ? (
          <EmptyState
            icon={<FileText className="h-6 w-6" />}
            title="还没有行"
            description="采购单至少要有一行 SKU 才能保存与提交。"
            action={
              <Button variant="brand" size="sm" onClick={addLine} disabled={!editable}>
                <Plus className="mr-1.5 h-3.5 w-3.5" />
                加一行
              </Button>
            }
          />
        ) : (
          <div className="space-y-3">
            {form.lines.map((line, index) => {
              const previewRow = preview[index];
              return (
                <div key={index} className="border-border rounded-md border p-3">
                  <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-6">
                    <label className="space-y-1.5">
                      <span className="text-copy-13 text-muted-foreground">SKU</span>
                      <Input
                        value={line.skuCode}
                        disabled={!editable}
                        placeholder="SKU-0001"
                        onChange={(e) => patchLine(index, { skuCode: e.target.value })}
                      />
                    </label>
                    <label className="space-y-1.5">
                      <span className="text-copy-13 text-muted-foreground">数量</span>
                      <Input
                        type="number"
                        step="0.000001"
                        value={line.quantity ?? ''}
                        disabled={!editable}
                        onChange={(e) =>
                          patchLine(index, {
                            quantity: e.target.value === '' ? null : Number(e.target.value),
                          })
                        }
                      />
                    </label>
                    <label className="space-y-1.5">
                      <span className="text-copy-13 text-muted-foreground">采购单位</span>
                      <Input
                        value={line.unitCode}
                        disabled={!editable}
                        placeholder="箱"
                        onChange={(e) => patchLine(index, { unitCode: e.target.value })}
                      />
                    </label>
                    <label className="space-y-1.5">
                      <span className="text-copy-13 text-muted-foreground">单价</span>
                      <Input
                        type="number"
                        step="0.000001"
                        value={line.unitPrice ?? ''}
                        disabled={!editable}
                        onChange={(e) =>
                          patchLine(index, {
                            unitPrice: e.target.value === '' ? null : Number(e.target.value),
                          })
                        }
                      />
                    </label>
                    <label className="space-y-1.5">
                      <span className="text-copy-13 text-muted-foreground">单价来源</span>
                      <select
                        className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                        value={line.priceSource}
                        disabled={!editable}
                        onChange={(e) =>
                          patchLine(index, {
                            priceSource: e.target.value,
                            quoteId: e.target.value === 'QUOTE' ? line.quoteId : null,
                          })
                        }
                      >
                        <option value="MANUAL">手工填写</option>
                        <option value="QUOTE">报价</option>
                      </select>
                    </label>
                    <div className="flex items-end gap-2">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => void runPreview(index)}
                        disabled={busy || !editable}
                      >
                        带出报价
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => removeLine(index)}
                        disabled={!editable || form.lines.length <= 1}
                        aria-label="删掉这一行"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </Button>
                    </div>
                  </div>

                  {previewRow && (
                    <div className="bg-muted/40 text-copy-13 mt-3 rounded-md p-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone="info">
                          {conversionStatusLabel(previewRow.conversionStatus)}
                        </Badge>
                        {previewRow.quantityPerBase !== null && (
                          <span>
                            1 {previewRow.purchaseUnitCode || '采购单位'} ={' '}
                            {fmtQty(previewRow.quantityPerBase)} {previewRow.baseUnit}
                          </span>
                        )}
                        <span>
                          库存：
                          {previewRow.stockStatus === 'CAPTURED'
                            ? fmtQty(previewRow.stockAvailableQty)
                            : NO_DATA_TEXT}
                        </span>
                      </div>
                      {previewRow.ambiguous && (
                        <div className="text-warning mt-2">
                          报价有歧义：同一优先级存在多条有效报价，请选一条或先在报价台账里定下优先级。
                        </div>
                      )}
                      <div className="mt-2 space-y-1">
                        {(previewRow.candidates ?? [])
                          .filter(
                            (candidate): candidate is NonNullable<typeof candidate> =>
                              candidate !== null,
                          )
                          .map((candidate) => (
                            <div
                              key={candidate.quoteId}
                              className="flex flex-wrap items-center gap-2"
                            >
                              <Badge
                                tone={
                                  candidate.selectionState === 'SELECTED'
                                    ? 'success'
                                    : candidate.selectionState === 'AMBIGUOUS'
                                      ? 'warning'
                                      : 'neutral'
                                }
                              >
                                {quoteSelectionLabel(candidate.selectionState)}
                              </Badge>
                              <span>
                                {candidate.supplierName} · {candidate.currency} ·{' '}
                                {fmtAmount(candidate.unitPrice)}
                              </span>
                              {candidate.selectionState !== 'SELECTED' && (
                                <Button
                                  variant="link"
                                  size="sm"
                                  onClick={() =>
                                    patchLine(index, {
                                      priceSource: 'QUOTE',
                                      quoteId: candidate.quoteId,
                                      unitPrice: candidate.unitPrice,
                                      unitCode: candidate.purchaseUnitCode,
                                    })
                                  }
                                >
                                  用这一条
                                </Button>
                              )}
                            </div>
                          ))}
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </SectionCard>

      {/* ---- 发货地址 ---- */}
      <SectionCard title="发货地址" description="地址是表单内容：草稿期可改，提交后冻结。">
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {(
            [
              ['recipientName', '收件人'],
              ['recipientPhone', '联系电话'],
              ['countryCode', '国家（两位字母）'],
              ['province', '省 / 州'],
              ['city', '城市'],
              ['district', '区'],
              ['detail', '详细地址'],
              ['postalCode', '邮编'],
            ] as const
          ).map(([key, label]) => (
            <label key={key} className="space-y-1.5">
              <span className="text-copy-13 text-muted-foreground">{label}</span>
              <Input
                value={form.shipAddress?.[key] ?? ''}
                disabled={!editable}
                onChange={(e) =>
                  patch({
                    shipAddress: {
                      recipientName: '',
                      recipientPhone: '',
                      countryCode: '',
                      province: '',
                      city: '',
                      district: '',
                      detail: '',
                      postalCode: '',
                      note: '',
                      ...(form.shipAddress ?? {}),
                      [key]: e.target.value,
                    },
                  })
                }
              />
            </label>
          ))}
        </div>
      </SectionCard>

      {/* ---- 附件 ---- */}
      <SectionCard
        title="附件"
        description="引用 catalog 媒体库的素材（逻辑引用 + 元数据快照，不建跨库外键）。"
      >
        {(server?.attachments ?? []).length === 0 ? (
          <EmptyState
            icon={<FileText className="h-6 w-6" />}
            title="没有附件"
            description="合同、报价单等可以挂在采购单上；素材本体在商品主数据服务的媒体库里。"
          />
        ) : (
          <ul className="space-y-2">
            {(server?.attachments ?? []).map((attachment) => (
              <li
                key={attachment.id}
                className="border-border flex items-center gap-3 rounded-md border px-3 py-2"
              >
                <Badge tone="info">{attachmentTypeLabel(attachment.attachmentType)}</Badge>
                <span className="text-copy-13">
                  {attachment.mediaName || attachment.originalFilename}
                </span>
                <span className="text-copy-13 text-muted-foreground">
                  {attachment.mimeType} · {fmtQty(attachment.byteSize)} B
                </span>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ---- 提交时冻结的快照 ---- */}
      {server && server.lines.some((line) => line.snapshot) && (
        <SectionCard
          title="提交时冻结的快照"
          description="单价、汇率与单位换算在提交那一刻冻结；之后改报价不影响这张已提交采购单的成本核算。"
        >
          <div className="space-y-3">
            {server.lines.map((line) =>
              line.snapshot ? (
                <div key={line.lineNo} className="border-border text-copy-13 rounded-md border p-3">
                  <div className="font-medium">
                    第 {line.lineNo} 行 · {line.skuCode} · {priceSourceLabel(line.priceSource)}
                  </div>
                  <div className="text-muted-foreground mt-1 space-y-0.5">
                    <div>
                      单位换算：{conversionStatusLabel(line.snapshot.conversionStatus)}
                      {line.snapshot.quantityPerBase !== null
                        ? `（1 ${line.snapshot.purchaseUnitCode} = ${fmtQty(line.snapshot.quantityPerBase)} ${line.snapshot.baseUnitCode}，本行 ${fmtQty(line.snapshot.baseQuantity)} ${line.snapshot.baseUnitCode}）`
                        : '（没取到箱规）'}
                    </div>
                    <div>
                      报价快照：
                      {line.snapshot.quoteUnitPrice !== null
                        ? `${fmtAmount(line.snapshot.quoteUnitPrice)} ${line.snapshot.quoteCurrency}`
                        : '无（这一行不是按报价下的）'}
                    </div>
                    <div>
                      库存快照：{stockStatusLabel(line.snapshot.stockStatus)}
                      {line.snapshot.stockAvailableQty !== null
                        ? `（可用 ${fmtQty(line.snapshot.stockAvailableQty)}）`
                        : ''}
                    </div>
                    <div>
                      税额：{NO_DATA_TEXT}（{taxStatusLabel(line.snapshot.taxStatus)}）
                    </div>
                  </div>
                </div>
              ) : null,
            )}
            <div className="text-copy-13 text-muted-foreground">
              本位币汇率：{fxStatusLabel(server.amounts.fxStatus)}
              {server.amounts.fxRateToFunctional !== null
                ? `（1 ${server.amounts.currency} = ${fmtRate(server.amounts.fxRateToFunctional)} ${server.amounts.functionalCurrency}，生效日 ${fmtDate(server.amounts.fxRateEffectiveDate)}）`
                : '（取不到汇率就没有折算，不按 1:1 凑）'}
            </div>
          </div>
        </SectionCard>
      )}

      {/* ---- 能力边界 ---- */}
      {unavailable.length > 0 && (
        <SectionCard
          title="本模块做不到的事"
          description="如实标出来，免得看起来像「这单没有这些值」。"
        >
          <ul className="space-y-2">
            {unavailable.map((capability) => (
              <li key={capability.code} className="text-copy-13">
                <Badge tone="warning" className="mr-2">
                  {capability.label}
                </Badge>
                <span className="text-muted-foreground">归属 {capability.ownerModule}：</span>
                {capability.reason}
              </li>
            ))}
          </ul>
        </SectionCard>
      )}
    </div>
  );
}
