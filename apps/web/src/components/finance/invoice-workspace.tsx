'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  FinanceInvoiceCapabilities,
  FinanceInvoiceDetail,
  FinanceInvoiceEventRow,
  FinanceInvoiceLineInput,
  FinanceInvoicePage,
  FinanceInvoicePlatformPayload,
  FinanceInvoicePlatformSubmissionPage,
  FinanceInvoiceRequestPage,
  FinanceInvoiceSummary,
  FinanceInvoiceTemplatePage,
} from '../../../../../packages/api-contracts/src/index';
import {
  acknowledgeInvoicePlatformSubmission,
  batchCreateInvoices,
  countryCoverageTone,
  createInvoice,
  documentTypeLabel,
  fetchInvoiceCapabilities,
  fetchInvoiceDetail,
  fetchInvoiceEvents,
  fetchInvoiceOptions,
  fetchInvoicePlatformPayload,
  fetchInvoicePlatformSubmissions,
  fetchInvoiceRequests,
  fetchInvoiceSummary,
  fetchInvoiceTemplates,
  fetchInvoices,
  formatMoney,
  formatQuantity,
  formatTaxRate,
  invoiceNumberLabel,
  invoiceOptions,
  invoiceStatusLabel,
  issueInvoice,
  platformSyncStatusLabel,
  reverseInvoice,
  sequencePeriodLabel,
  sequenceScopeLabel,
  submitInvoiceToPlatform,
  upsertInvoiceTemplate,
  voidInvoice,
} from './invoice-model';

/**
 * 12.6.2.1 发票管理。
 *
 * <p>蓝图 12-finance.md：「管理销售发票与贷项通知单：按平台/国家模板生成、维护税码税率税额、
 * 编号与状态（草稿/开具/作废/红冲），支持批量与平台开票对接。」输入：「订单与结算数据；
 * 发票模板；编号规则；作废申请。」输出：「发票与贷项单 + 编号序列 + 状态记录。」
 * 关系：「上游 = 9 订单、12.3.1；下游 = 12.5.2.1 凭证、11.4 售后（退货开票调整）。
 * <b>各国发票要求差异大</b>，模板必须按国家配置；本系统不承诺覆盖所有国家税务规则。」
 *
 * <p><b>页面围绕七件事组织</b>：
 *
 * <ol>
 *   <li><b>发票列表</b>：真分页 + 真筛选（状态 / 单据类型 / 币种 / 关键字）。</li>
 *   <li><b>发票详情</b>：头 + 明细 + 状态记录（append-only 留痕），并排显示
 *       贷项通知单与原发票这一<b>对</b>。</li>
 *   <li><b>建单</b>：只填 SKU / 数量 / 单价 —— <b>税率与税额不在这儿填</b>：
 *       税率由服务端实时取（12.6.1.1），税额由数据库生成列算。</li>
 *   <li><b>发票模板</b>：按 国家 × 平台 × 单据类型 配置，含编号规则与生效区间。</li>
 *   <li><b>待开票需求 + 批量建单</b>：上游 9 订单的只读清单，批量生成草稿，
 *       失败的那几条**逐条说清原因**。</li>
 *   <li><b>汇总</b>：🔴 <b>按币种分组</b>，页面上**不出现**跨币种的合计。</li>
 *   <li><b>平台开票</b>：报文生成、批量递交、回执登记；递交失败也留痕。</li>
 * </ol>
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有录过发票」。
 */
export function InvoiceWorkspace() {
  // ---------------------------------------------------------------- 数据
  const [caps, setCaps] = useState<FinanceInvoiceCapabilities | null>(null);
  const [options, setOptions] = useState<ReturnType<typeof invoiceOptions> | null>(null);
  const [invoices, setInvoices] = useState<FinanceInvoicePage | null>(null);
  const [templates, setTemplates] = useState<FinanceInvoiceTemplatePage | null>(null);
  const [requests, setRequests] = useState<FinanceInvoiceRequestPage | null>(null);
  const [summary, setSummary] = useState<FinanceInvoiceSummary | null>(null);
  const [submissions, setSubmissions] = useState<FinanceInvoicePlatformSubmissionPage | null>(null);

  // 选中的一张发票（详情 + 明细 + 状态记录 + 平台报文）
  const [selectedId, setSelectedId] = useState('');
  const [detail, setDetail] = useState<FinanceInvoiceDetail | null>(null);
  const [events, setEvents] = useState<FinanceInvoiceEventRow[]>([]);
  const [payload, setPayload] = useState<FinanceInvoicePlatformPayload | null>(null);

  // ---------------------------------------------------------------- 界面态
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [statusFilter, setStatusFilter] = useState('');
  const [documentTypeFilter, setDocumentTypeFilter] = useState('');
  const [currencyFilter, setCurrencyFilter] = useState('');
  const [keyword, setKeyword] = useState('');

  // 建单表单
  const [formTemplateId, setFormTemplateId] = useState('');
  const [formIssueDate, setFormIssueDate] = useState('');
  const [formOrderNo, setFormOrderNo] = useState('');
  const [formCustomer, setFormCustomer] = useState('');
  const [formNote, setFormNote] = useState('');
  const [formLines, setFormLines] = useState<FinanceInvoiceLineInput[]>([]);

  // 模板表单
  const [tplCode, setTplCode] = useState('');
  const [tplName, setTplName] = useState('');
  const [tplCountry, setTplCountry] = useState('');
  const [tplJurisdiction, setTplJurisdiction] = useState('');
  const [tplPlatform, setTplPlatform] = useState('');
  const [tplDocumentType, setTplDocumentType] = useState('INVOICE');
  const [tplTaxType, setTplTaxType] = useState('VAT');
  const [tplTaxCode, setTplTaxCode] = useState('');
  const [tplCurrency, setTplCurrency] = useState('EUR');
  const [tplTitle, setTplTitle] = useState('');
  const [tplPrefix, setTplPrefix] = useState('');
  const [tplScope, setTplScope] = useState('TENANT');
  const [tplPeriod, setTplPeriod] = useState('NONE');
  const [tplEffectiveFrom, setTplEffectiveFrom] = useState('');
  const [tplNote, setTplNote] = useState('');

  // 作废 / 红冲
  const [voidReason, setVoidReason] = useState('');
  const [reverseReason, setReverseReason] = useState('');

  // 批量建单
  const [batchTemplateId, setBatchTemplateId] = useState('');
  const [batchSelected, setBatchSelected] = useState<string[]>([]);

  // 平台递交
  const [submitPlatformCode, setSubmitPlatformCode] = useState('');
  const [submitSelected, setSubmitSelected] = useState<string[]>([]);
  const [ackSubmissionId, setAckSubmissionId] = useState('');
  const [ackRef, setAckRef] = useState('');
  const [ackResult, setAckResult] = useState('ACKNOWLEDGED');
  const [ackMessage, setAckMessage] = useState('');

  const emptyLine = (): FinanceInvoiceLineInput => ({
    skuCode: '',
    productName: '',
    quantity: 1,
    unitPrice: 0,
    taxType: '',
    taxCode: '',
    note: '',
  });

  /** 可用于建单的生效模板（只有 ACTIVE 的模板能生成发票）。 */
  const usableTemplates = useMemo(
    () =>
      (templates?.items ?? []).filter((t) => t.status === 'ACTIVE' && t.documentType === 'INVOICE'),
    [templates],
  );

  // ---------------------------------------------------------------- 加载
  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, inv, tpl, req, sum, sub] = await Promise.all([
        fetchInvoiceCapabilities(),
        fetchInvoiceOptions(),
        fetchInvoices({
          status: statusFilter || undefined,
          documentType: documentTypeFilter || undefined,
          currency: currencyFilter || undefined,
          keyword: keyword || undefined,
        }),
        fetchInvoiceTemplates(),
        fetchInvoiceRequests(),
        fetchInvoiceSummary(),
        fetchInvoicePlatformSubmissions(),
      ]);
      setCaps(c);
      setOptions(invoiceOptions(o));
      setInvoices(inv);
      setTemplates(tpl);
      setRequests(req);
      setSummary(sum);
      setSubmissions(sub);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载发票管理数据失败');
    } finally {
      setLoading(false);
    }
  }, [statusFilter, documentTypeFilter, currencyFilter, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 载入选中发票的详情、明细、状态记录与平台报文。 */
  const loadDetail = useCallback(async (id: string) => {
    if (!id) {
      setDetail(null);
      setEvents([]);
      setPayload(null);
      return;
    }
    try {
      const d = await fetchInvoiceDetail(id);
      setDetail(d);
      setEvents(await fetchInvoiceEvents(id));
      setActionError('');
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '加载发票详情失败');
    }
  }, []);

  useEffect(() => {
    void loadDetail(selectedId);
  }, [selectedId, loadDetail]);

  const loadPayload = useCallback(async (id: string) => {
    if (!id) {
      setPayload(null);
      return;
    }
    try {
      setPayload(await fetchInvoicePlatformPayload(id));
      setActionError('');
    } catch (e) {
      setPayload(null);
      setActionError(e instanceof Error ? e.message : '生成平台开票报文失败');
    }
  }, []);

  // ---------------------------------------------------------------- 动作
  const submitTemplate = useCallback(async () => {
    if (!tplCode.trim() || !tplName.trim()) {
      setActionError('模板编码与模板名称都必须填');
      return;
    }
    if (!tplCountry.trim() || tplCountry.trim().length !== 2) {
      setActionError('目的国必须是两位 ISO 国家码（例如 DE）');
      return;
    }
    if (!tplJurisdiction.trim()) {
      setActionError('必须选辖区：辖区码不是自由文本（服务端会实时校验）');
      return;
    }
    if (!tplPrefix.trim()) {
      setActionError('必须给编号前缀');
      return;
    }
    if (!tplEffectiveFrom) {
      setActionError('必须给出生效时间：没有生效时间的模板等于没有');
      return;
    }
    try {
      await upsertInvoiceTemplate({
        templateCode: tplCode.trim(),
        templateName: tplName.trim(),
        countryCode: tplCountry.trim().toUpperCase(),
        jurisdictionCode: tplJurisdiction.trim().toUpperCase(),
        platformCode: tplPlatform.trim().toUpperCase(),
        documentType: tplDocumentType,
        taxType: tplTaxType,
        taxCode: tplTaxCode.trim(),
        currency: tplCurrency.trim().toUpperCase(),
        title: tplTitle.trim(),
        sequencePrefix: tplPrefix.trim(),
        sequenceScope: tplScope,
        sequencePeriod: tplPeriod,
        effectiveFrom: new Date(tplEffectiveFrom).toISOString(),
        note: tplNote.trim(),
      });
      setActionError('');
      setNotice(`模板「${tplCode.trim()}」已登记`);
      setTplCode('');
      setTplName('');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '登记模板失败');
    }
  }, [
    tplCode,
    tplName,
    tplCountry,
    tplJurisdiction,
    tplPlatform,
    tplDocumentType,
    tplTaxType,
    tplTaxCode,
    tplCurrency,
    tplTitle,
    tplPrefix,
    tplScope,
    tplPeriod,
    tplEffectiveFrom,
    tplNote,
    load,
  ]);

  const submitInvoice = useCallback(async () => {
    if (!formTemplateId) {
      setActionError('必须选一张发票模板（发票按平台/国家模板生成）');
      return;
    }
    if (formLines.length === 0) {
      setActionError('至少要有一行明细');
      return;
    }
    const bad = formLines.find((l) => !l.skuCode.trim() || !l.productName.trim());
    if (bad) {
      setActionError('每一行都要有 SKU 与品名');
      return;
    }
    if (formLines.some((l) => !Number.isFinite(l.quantity) || l.quantity <= 0)) {
      setActionError('每一行的数量必须为正');
      return;
    }
    if (formLines.some((l) => !Number.isFinite(l.unitPrice) || l.unitPrice < 0)) {
      setActionError('每一行的单价不能为负');
      return;
    }
    try {
      const created = await createInvoice({
        templateId: formTemplateId,
        issueDate: formIssueDate ? formIssueDate : new Date().toISOString().slice(0, 10),
        shopId: null,
        shopCode: '',
        sourceSettlementId: null,
        sourceOrderNo: formOrderNo.trim(),
        customerName: formCustomer.trim(),
        lines: formLines,
        note: formNote.trim(),
      });
      setActionError('');
      setNotice(`草稿已创建（税率由服务端实时取，税额由数据库算）`);
      setFormLines([]);
      setFormOrderNo('');
      setFormCustomer('');
      setFormNote('');
      await load();
      setSelectedId(created.id);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '创建发票草稿失败');
    }
  }, [formTemplateId, formIssueDate, formLines, formOrderNo, formCustomer, formNote, load]);

  const doIssue = useCallback(
    async (id: string) => {
      try {
        const issued = await issueInvoice(id);
        setActionError('');
        setNotice(`已开具，编号 ${issued.invoiceNumber ?? ''}`);
        await load();
        await loadDetail(id);
      } catch (e) {
        setActionError(e instanceof Error ? e.message : '开具失败');
      }
    },
    [load, loadDetail],
  );

  const doVoid = useCallback(
    async (id: string) => {
      if (!voidReason.trim()) {
        setActionError('作废必须写明原因：作废要留痕「谁、何时、为什么」');
        return;
      }
      try {
        await voidInvoice(id, voidReason.trim());
        setActionError('');
        setNotice('已作废（作废留痕已写入状态记录）');
        setVoidReason('');
        await load();
        await loadDetail(id);
      } catch (e) {
        setActionError(e instanceof Error ? e.message : '作废失败');
      }
    },
    [voidReason, load, loadDetail],
  );

  const doReverse = useCallback(
    async (id: string) => {
      if (!reverseReason.trim()) {
        setActionError('红冲必须写明原因');
        return;
      }
      try {
        const credit = await reverseInvoice(id, [], reverseReason.trim());
        setActionError('');
        setNotice(
          `已生成成对的贷项通知单（金额逐行从原发票带出，状态：${credit.statusLabel ?? credit.status}）`,
        );
        setReverseReason('');
        await load();
        await loadDetail(id);
      } catch (e) {
        setActionError(e instanceof Error ? e.message : '红冲失败');
      }
    },
    [reverseReason, load, loadDetail],
  );

  const doBatch = useCallback(async () => {
    if (!batchTemplateId) {
      setActionError('批量建单必须选一张发票模板');
      return;
    }
    if (batchSelected.length === 0) {
      setActionError('至少要选一条待开票需求');
      return;
    }
    try {
      const result = await batchCreateInvoices({
        templateId: batchTemplateId,
        requestIds: batchSelected,
        issueDate: new Date().toISOString().slice(0, 10),
        note: '从待开票需求批量生成',
      });
      setActionError('');
      setNotice(
        result.failed.length === 0
          ? `已生成 ${result.created.length} 张草稿`
          : `已生成 ${result.created.length} 张草稿；${result.failed.length} 条失败（逐条原因见下方）`,
      );
      setBatchSelected([]);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '批量建单失败');
    }
  }, [batchTemplateId, batchSelected, load]);

  const doSubmitPlatform = useCallback(
    async (explicitIds?: string[]) => {
      const ids = explicitIds && explicitIds.length > 0 ? explicitIds : submitSelected;
      if (ids.length === 0) {
        setActionError('至少要选一张已开具的发票');
        return;
      }
      try {
        const result = await submitInvoiceToPlatform({
          invoiceIds: ids,
          platformCode: submitPlatformCode.trim().toUpperCase(),
        });
        setActionError('');
        setNotice(
          result.failed.length === 0
            ? `已递交 ${result.submitted.length} 张发票`
            : `已递交 ${result.submitted.length} 张；${result.failed.length} 张失败（失败也留痕，见下方提交记录）`,
        );
        setSubmitSelected([]);
        await load();
      } catch (e) {
        setActionError(e instanceof Error ? e.message : '递交平台失败');
      }
    },
    [submitSelected, submitPlatformCode, load],
  );

  const doAcknowledge = useCallback(async () => {
    if (!ackSubmissionId) {
      setActionError('先在上面点某条提交记录的「登记回执」');
      return;
    }
    try {
      await acknowledgeInvoicePlatformSubmission(ackSubmissionId, {
        platformRef: ackRef.trim(),
        result: ackResult,
        message: ackMessage.trim(),
      });
      setActionError('');
      setNotice('平台回执已登记');
      setAckRef('');
      setAckMessage('');
      setAckSubmissionId('');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '登记平台回执失败');
    }
  }, [ackSubmissionId, ackRef, ackResult, ackMessage, load]);

  // ---------------------------------------------------------------- 渲染
  if (loading && !invoices) {
    return (
      <div className="space-y-6 p-6">
        <PageHeader title="发票管理" description="正在加载发票、模板与平台提交记录…" />
        <Notice tone="info" title="加载中">
          正在从 finance 服务读取发票数据。
        </Notice>
      </div>
    );
  }

  if (error) {
    return (
      <div className="space-y-6 p-6">
        <PageHeader
          title="发票管理"
          description="管理销售发票与贷项通知单：按平台/国家模板生成、维护税码税率税额、编号与状态。"
          actions={
            <Button onClick={() => void load()} disabled={loading}>
              <RefreshCw className="mr-2 h-4 w-4" />
              重试
            </Button>
          }
        />
        <Notice tone="danger" title="加载失败">
          <TriangleAlert className="mr-2 inline h-4 w-4" />
          {error}
        </Notice>
      </div>
    );
  }

  const selected = detail?.invoice ?? null;
  const selectedLines = detail?.lines ?? [];
  const hasFilter =
    statusFilter !== '' ||
    documentTypeFilter !== '' ||
    currencyFilter !== '' ||
    keyword.trim() !== '';

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="发票管理"
        description="管理销售发票与贷项通知单：按平台/国家模板生成、维护税码税率税额、编号与状态（草稿/开具/作废/红冲），支持批量与平台开票对接。"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {/* 边界提示：职责切分 + 三条纪律。 */}
      <Notice tone="info" title="税率实时取、凭证不写、贷项单成对">
        🔴 <strong>税率与税额不是这里填的</strong>：建单时只填 SKU / 数量 / 单价，
        税率由服务端按（发票日 × 辖区 × 税码）<strong>实时取</strong>（12.6.1.1），
        税额由数据库生成列算 —— <strong>调用方填不出一个假的税额</strong>；取不到税率一律报错，
        <strong>不按 0% 处理</strong>。
        <br />
        🔴 <strong>发票不写凭证</strong>：过账走 12.5.2.1 凭证模块自己的接口，本模块只产出发票事实。
        <br />
        🔴 <strong>红冲成对</strong>：贷项通知单永远指向一张已开具的原发票，金额
        <strong>逐行带出</strong>， 不许手填；<strong>编号只在开具时分配</strong>（草稿不占号）。
        <br />
        ⚠️ <strong>各国发票要求差异大</strong>，模板必须按国家配置；本系统
        <strong>不承诺覆盖所有国家税务规则</strong> —— 下方「各国覆盖」逐个辖区给出事实。
      </Notice>

      {actionError ? (
        <Notice tone="danger" title="操作未完成">
          {actionError}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      ) : null}

      <div className="grid gap-4 md:grid-cols-4">
        <StatCard label="发票与贷项单总数" value={invoices?.total ?? 0} />
        <StatCard label="草稿" value={invoices?.draftCount ?? 0} />
        <StatCard label="已开具" value={invoices?.issuedCount ?? 0} />
        <StatCard
          label="贷项通知单"
          value={summary?.creditNoteCount ?? 0}
          comparisonLabel="红冲成对出现"
        />
      </div>

      {/* 1. 发票列表 */}
      <SectionCard
        title="发票列表"
        description="真分页 + 真筛选。草稿还没有正式编号（编号只在开具时分配）。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              aria-label="按状态筛选"
            >
              <option value="">全部状态</option>
              {(options?.statuses ?? []).map((s) => (
                <option key={s} value={s}>
                  {invoiceStatusLabel(s)}
                </option>
              ))}
            </select>
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={documentTypeFilter}
              onChange={(e) => setDocumentTypeFilter(e.target.value)}
              aria-label="按单据类型筛选"
            >
              <option value="">全部单据类型</option>
              {(options?.documentTypes ?? []).map((s) => (
                <option key={s} value={s}>
                  {documentTypeLabel(s)}
                </option>
              ))}
            </select>
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={currencyFilter}
              onChange={(e) => setCurrencyFilter(e.target.value)}
              aria-label="按币种筛选"
            >
              <option value="">全部币种</option>
              {(options?.currencies ?? []).map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
            <Input
              className="w-48"
              placeholder="发票号 / 客户 / 订单号"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              aria-label="按关键字筛选"
            />
          </div>
        }
      >
        {invoices && invoices.items.length === 0 ? (
          <EmptyState
            title={hasFilter ? '没有符合筛选条件的发票' : '还没有录过发票'}
            description={
              hasFilter
                ? '当前筛选条件下没有匹配的行 —— 放宽条件再试，或清空关键字。'
                : '发票由模板生成：先在下方登记一张发票模板，再建第一张草稿。'
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="text-copy-13 w-full text-left">
              <thead className="text-copy-12 text-muted-foreground">
                <tr>
                  <th className="p-2">递交</th>
                  <th className="p-2">发票号</th>
                  <th className="p-2">类型 / 状态</th>
                  <th className="p-2">客户 / 订单</th>
                  <th className="p-2">辖区 / 平台</th>
                  <th className="p-2">发票日</th>
                  <th className="p-2">金额</th>
                  <th className="p-2">平台对接</th>
                  <th className="p-2">操作</th>
                </tr>
              </thead>
              <tbody>
                {(invoices?.items ?? []).map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="p-2">
                      {row.status === 'ISSUED' ? (
                        <input
                          type="checkbox"
                          aria-label={`选择 ${row.invoiceNumber ?? row.id} 递交平台`}
                          checked={submitSelected.includes(row.id)}
                          onChange={(e) =>
                            setSubmitSelected((prev) =>
                              e.target.checked
                                ? [...prev, row.id]
                                : prev.filter((x) => x !== row.id),
                            )
                          }
                        />
                      ) : (
                        <span className="text-copy-12 text-muted-foreground">—</span>
                      )}
                    </td>
                    <td className="p-2">
                      <div>{invoiceNumberLabel(row)}</div>
                      {row.originalInvoiceNumber ? (
                        <div className="text-copy-12 text-muted-foreground">
                          原发票 {row.originalInvoiceNumber}
                        </div>
                      ) : null}
                    </td>
                    <td className="p-2">
                      <div>{row.documentTypeLabel || documentTypeLabel(row.documentType)}</div>
                      <Badge tone={row.status === 'ISSUED' ? 'success' : 'neutral'}>
                        {row.statusLabel || invoiceStatusLabel(row.status)}
                      </Badge>
                    </td>
                    <td className="p-2">
                      <div>{row.customerName || '—'}</div>
                      <div className="text-copy-12 text-muted-foreground">
                        {row.sourceOrderNo || '—'}
                      </div>
                    </td>
                    <td className="p-2">
                      <div>{row.jurisdictionName || row.jurisdictionCode}</div>
                      <div className="text-copy-12 text-muted-foreground">
                        {row.countryCode}
                        {row.platformCode ? ` · ${row.platformCode}` : ''}
                      </div>
                    </td>
                    <td className="p-2">{row.issueDate}</td>
                    <td className="p-2">
                      <div>{formatMoney(row.totalAmount, row.currency)}</div>
                      <div className="text-copy-12 text-muted-foreground">
                        税 {formatMoney(row.taxAmount, row.currency)}
                      </div>
                    </td>
                    <td className="p-2">
                      <Badge
                        tone={
                          row.platformSyncStatus === 'ACKNOWLEDGED'
                            ? 'success'
                            : row.platformSyncStatus === 'FAILED'
                              ? 'danger'
                              : 'neutral'
                        }
                      >
                        {row.platformSyncStatusLabel ||
                          platformSyncStatusLabel(row.platformSyncStatus)}
                      </Badge>
                      {row.platformInvoiceRef ? (
                        <div className="text-copy-12 text-muted-foreground mt-1">
                          回执 {row.platformInvoiceRef}
                        </div>
                      ) : null}
                    </td>
                    <td className="p-2">
                      <div className="flex flex-wrap gap-1">
                        <Button variant="secondary" onClick={() => setSelectedId(row.id)}>
                          查看
                        </Button>
                        {row.status === 'DRAFT' ? (
                          <Button variant="secondary" onClick={() => void doIssue(row.id)}>
                            开具
                          </Button>
                        ) : null}
                        {row.status === 'ISSUED' && row.documentType === 'INVOICE' ? (
                          <>
                            <Button variant="secondary" onClick={() => void doReverse(row.id)}>
                              红冲
                            </Button>
                            <Button
                              variant="secondary"
                              onClick={() => void doSubmitPlatform([row.id])}
                            >
                              递交平台
                            </Button>
                          </>
                        ) : null}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-copy-12 text-muted-foreground mt-2">
              共 {invoices?.total ?? 0} 张 · 当前页 {invoices?.items.length ?? 0} 条 · 页大小{' '}
              {invoices?.size ?? 0}
            </p>
          </div>
        )}
      </SectionCard>

      {/* 2. 发票详情（头 + 明细 + 状态记录） */}
      <SectionCard
        title="发票详情"
        description="明细行的金额与税额是数据库生成列 —— 应用层算错时它们不会跟着错。贷项通知单的数量是负数（红冲方向）。"
      >
        {!selected ? (
          <EmptyState
            title="还没有选中发票"
            description="在上方列表点「查看」，这里显示发票的头、明细、状态记录与平台开票报文。"
          />
        ) : (
          <div className="space-y-4">
            <div className="grid gap-3 md:grid-cols-4">
              <div>
                <div className="text-copy-12 text-muted-foreground">发票号</div>
                <div className="text-copy-13">{invoiceNumberLabel(selected)}</div>
              </div>
              <div>
                <div className="text-copy-12 text-muted-foreground">类型 / 状态</div>
                <div className="text-copy-13">
                  {selected.documentTypeLabel || documentTypeLabel(selected.documentType)} ·{' '}
                  {selected.statusLabel || invoiceStatusLabel(selected.status)}
                </div>
              </div>
              <div>
                <div className="text-copy-12 text-muted-foreground">抬头 / 模板</div>
                <div className="text-copy-13">{selected.title || '—'}</div>
                <div className="text-copy-12 text-muted-foreground">{selected.templateCode}</div>
              </div>
              <div>
                <div className="text-copy-12 text-muted-foreground">辖区 / 币种</div>
                <div className="text-copy-13">
                  {selected.jurisdictionName || selected.jurisdictionCode} · {selected.currency}
                </div>
              </div>
              <div>
                <div className="text-copy-12 text-muted-foreground">客户</div>
                <div className="text-copy-13">{selected.customerName || '—'}</div>
              </div>
              <div>
                <div className="text-copy-12 text-muted-foreground">订单 / 结算单</div>
                <div className="text-copy-13">{selected.sourceOrderNo || '—'}</div>
              </div>
              <div>
                <div className="text-copy-12 text-muted-foreground">小计 / 税额 / 合计</div>
                <div className="text-copy-13">
                  {formatMoney(selected.subtotalAmount, selected.currency)} /{' '}
                  {formatMoney(selected.taxAmount, selected.currency)} /{' '}
                  {formatMoney(selected.totalAmount, selected.currency)}
                </div>
              </div>
              <div>
                <div className="text-copy-12 text-muted-foreground">平台对接</div>
                <div className="text-copy-13">
                  {selected.platformSyncStatusLabel ||
                    platformSyncStatusLabel(selected.platformSyncStatus)}
                  {selected.platformInvoiceRef ? ` · ${selected.platformInvoiceRef}` : ''}
                </div>
              </div>
            </div>

            {/* 留痕：谁、何时、为什么 */}
            {(selected.issuedAt || selected.voidedAt || selected.reversedAt || selected.reason) && (
              <div className="rounded-md border p-3">
                <div className="text-copy-13 font-medium">留痕（谁 · 何时 · 为什么）</div>
                <ul className="text-copy-12 text-muted-foreground mt-1 list-disc pl-5">
                  {selected.issuedAt ? (
                    <li>
                      开具：{selected.issuedAt}（操作人 {selected.issuedBy ?? '—'}）
                    </li>
                  ) : null}
                  {selected.voidedAt ? (
                    <li>
                      作废：{selected.voidedAt}（操作人 {selected.voidedBy ?? '—'}）
                    </li>
                  ) : null}
                  {selected.reversedAt ? (
                    <li>
                      红冲：{selected.reversedAt}（操作人 {selected.reversedBy ?? '—'}）
                    </li>
                  ) : null}
                  {selected.reason ? <li>原因：{selected.reason}</li> : null}
                </ul>
              </div>
            )}

            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full text-left">
                <thead className="text-copy-12 text-muted-foreground">
                  <tr>
                    <th className="p-2">行号</th>
                    <th className="p-2">SKU / 品名</th>
                    <th className="p-2">数量</th>
                    <th className="p-2">单价</th>
                    <th className="p-2">行金额</th>
                    <th className="p-2">税码 / 税率</th>
                    <th className="p-2">税额</th>
                  </tr>
                </thead>
                <tbody>
                  {selectedLines.map((line) => (
                    <tr key={line.id} className="border-t">
                      <td className="p-2">{line.lineNo}</td>
                      <td className="p-2">
                        <div>{line.productName || '—'}</div>
                        <div className="text-copy-12 text-muted-foreground">
                          {line.skuCode || '—'}
                        </div>
                      </td>
                      <td className="p-2">{formatQuantity(line.quantity)}</td>
                      <td className="p-2">{formatMoney(line.unitPrice, selected.currency)}</td>
                      <td className="p-2">{formatMoney(line.lineAmount, selected.currency)}</td>
                      <td className="p-2">
                        <div>{line.taxCode}</div>
                        <div className="text-copy-12 text-muted-foreground">
                          {line.taxType} · {formatTaxRate(line.taxRate)}
                        </div>
                      </td>
                      <td className="p-2">{formatMoney(line.taxAmount, selected.currency)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* 动作区 */}
            <div className="grid gap-3 md:grid-cols-3">
              <div className="rounded-md border p-3">
                <div className="text-copy-13 font-medium">开具</div>
                <p className="text-copy-12 text-muted-foreground mt-1">
                  开具时分配正式编号（草稿不占号）。已开具 / 已作废 / 已红冲的单据不能再开具。
                </p>
                <Button
                  className="mt-2"
                  variant="secondary"
                  disabled={selected.status !== 'DRAFT'}
                  onClick={() => void doIssue(selected.id)}
                >
                  开具这张草稿
                </Button>
              </div>
              <div className="rounded-md border p-3">
                <div className="text-copy-13 font-medium">作废</div>
                <p className="text-copy-12 text-muted-foreground mt-1">
                  🔴 原因必填：作废必须留痕「谁、何时、为什么」。
                </p>
                <Input
                  className="mt-2"
                  placeholder="作废原因（必填）"
                  value={voidReason}
                  onChange={(e) => setVoidReason(e.target.value)}
                  aria-label="作废原因"
                />
                <Button
                  className="mt-2"
                  variant="secondary"
                  disabled={selected.status !== 'DRAFT' && selected.status !== 'ISSUED'}
                  onClick={() => void doVoid(selected.id)}
                >
                  作废
                </Button>
              </div>
              <div className="rounded-md border p-3">
                <div className="text-copy-13 font-medium">红冲</div>
                <p className="text-copy-12 text-muted-foreground mt-1">
                  生成成对的贷项通知单，金额逐行从原发票带出（手填的数写不进去）。
                </p>
                <Input
                  className="mt-2"
                  placeholder="红冲原因（必填）"
                  value={reverseReason}
                  onChange={(e) => setReverseReason(e.target.value)}
                  aria-label="红冲原因"
                />
                <Button
                  className="mt-2"
                  variant="secondary"
                  disabled={selected.status !== 'ISSUED' || selected.documentType !== 'INVOICE'}
                  onClick={() => void doReverse(selected.id)}
                >
                  整张红冲
                </Button>
              </div>
            </div>

            {/* 平台开票报文 */}
            <div className="rounded-md border p-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="text-copy-13 font-medium">平台开票报文</div>
                <Button variant="secondary" onClick={() => void loadPayload(selected.id)}>
                  生成报文
                </Button>
              </div>
              {payload === null ? (
                <p className="text-copy-12 text-muted-foreground mt-1">
                  点「生成报文」查看确定性报文。🔴 金额在报文里是**字符串** —— 走一遍 JSON
                  浮点解析就可能变成 23.799999999999997。
                </p>
              ) : (
                <>
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    摘要 {payload.digest} · 合计 {payload.totals.total} {payload.currency}
                  </p>
                  <pre className="text-copy-12 bg-muted mt-2 overflow-x-auto rounded-md p-3">
                    {JSON.stringify(payload, null, 2)}
                  </pre>
                </>
              )}
            </div>

            {/* 状态记录 */}
            <div>
              <div className="text-copy-13 font-medium">状态记录（append-only 留痕）</div>
              {events.length === 0 ? (
                <p className="text-copy-12 text-muted-foreground mt-1">这张单据还没有状态记录。</p>
              ) : (
                <div className="mt-2 overflow-x-auto">
                  <table className="text-copy-13 w-full text-left">
                    <thead className="text-copy-12 text-muted-foreground">
                      <tr>
                        <th className="p-2">事件</th>
                        <th className="p-2">状态变化</th>
                        <th className="p-2">原因</th>
                        <th className="p-2">操作人</th>
                        <th className="p-2">时间</th>
                      </tr>
                    </thead>
                    <tbody>
                      {events.map((ev) => (
                        <tr key={ev.id} className="border-t">
                          <td className="p-2">{ev.eventTypeLabel || ev.eventType}</td>
                          <td className="p-2">
                            {ev.fromStatus || '—'} → {ev.toStatus || '—'}
                          </td>
                          <td className="p-2">{ev.reason || '—'}</td>
                          <td className="p-2">{ev.actorId ?? '—'}</td>
                          <td className="p-2">{ev.occurredAt}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </div>
        )}
      </SectionCard>

      {/* 3. 建单 */}
      <SectionCard
        title="建一张发票草稿"
        description="只填 SKU / 数量 / 单价。🔴 税率与税额不在这里填 —— 税率由服务端实时取（12.6.1.1），税额由数据库生成列算。"
      >
        <div className="grid gap-3 md:grid-cols-3">
          <label className="text-copy-12">
            <span className="text-muted-foreground">发票模板</span>
            <select
              className="border-control-border bg-card text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
              value={formTemplateId}
              onChange={(e) => setFormTemplateId(e.target.value)}
            >
              <option value="">请选择</option>
              {usableTemplates.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.templateCode} · {t.templateName}（{t.currency} · {t.jurisdictionCode}）
                </option>
              ))}
            </select>
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">发票日</span>
            <Input
              className="mt-1"
              type="date"
              value={formIssueDate}
              onChange={(e) => setFormIssueDate(e.target.value)}
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">客户名称</span>
            <Input
              className="mt-1"
              value={formCustomer}
              onChange={(e) => setFormCustomer(e.target.value)}
            />
          </label>
          <label className="text-copy-12">
            <span className="text-muted-foreground">来源订单号</span>
            <Input
              className="mt-1"
              value={formOrderNo}
              onChange={(e) => setFormOrderNo(e.target.value)}
            />
          </label>
          <label className="text-copy-12 md:col-span-2">
            <span className="text-muted-foreground">备注</span>
            <Input
              className="mt-1"
              value={formNote}
              onChange={(e) => setFormNote(e.target.value)}
            />
          </label>
        </div>

        <div className="mt-4">
          <div className="text-copy-13 font-medium">明细行</div>
          {formLines.length === 0 ? (
            <p className="text-copy-12 text-muted-foreground mt-1">
              还没有明细行。至少要有一行才能建单。
            </p>
          ) : (
            <div className="mt-2 overflow-x-auto">
              <table className="text-copy-13 w-full text-left">
                <thead className="text-copy-12 text-muted-foreground">
                  <tr>
                    <th className="p-2">SKU</th>
                    <th className="p-2">品名</th>
                    <th className="p-2">数量</th>
                    <th className="p-2">单价</th>
                    <th className="p-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {formLines.map((line, index) => (
                    <tr key={index} className="border-t">
                      <td className="p-2">
                        <Input
                          value={line.skuCode}
                          onChange={(e) =>
                            setFormLines((prev) =>
                              prev.map((l, i) =>
                                i === index ? { ...l, skuCode: e.target.value } : l,
                              ),
                            )
                          }
                          aria-label="SKU"
                        />
                      </td>
                      <td className="p-2">
                        <Input
                          value={line.productName}
                          onChange={(e) =>
                            setFormLines((prev) =>
                              prev.map((l, i) =>
                                i === index ? { ...l, productName: e.target.value } : l,
                              ),
                            )
                          }
                          aria-label="品名"
                        />
                      </td>
                      <td className="p-2">
                        <Input
                          type="number"
                          min="0"
                          step="0.000001"
                          value={String(line.quantity)}
                          onChange={(e) =>
                            setFormLines((prev) =>
                              prev.map((l, i) =>
                                i === index ? { ...l, quantity: Number(e.target.value) } : l,
                              ),
                            )
                          }
                          aria-label="数量"
                        />
                      </td>
                      <td className="p-2">
                        <Input
                          type="number"
                          min="0"
                          step="0.0001"
                          value={String(line.unitPrice)}
                          onChange={(e) =>
                            setFormLines((prev) =>
                              prev.map((l, i) =>
                                i === index ? { ...l, unitPrice: Number(e.target.value) } : l,
                              ),
                            )
                          }
                          aria-label="单价"
                        />
                      </td>
                      <td className="p-2">
                        <Button
                          variant="secondary"
                          onClick={() => setFormLines((prev) => prev.filter((_, i) => i !== index))}
                        >
                          删除
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <div className="mt-2 flex flex-wrap gap-2">
            <Button
              variant="secondary"
              onClick={() => setFormLines((prev) => [...prev, emptyLine()])}
            >
              加一行
            </Button>
            <Button onClick={() => void submitInvoice()}>建草稿</Button>
          </div>
        </div>
      </SectionCard>

      {/* 4. 发票模板 */}
      <SectionCard
        title="发票模板（按平台 / 国家配置）"
        description="🔴 模板零预置：系统不替用户决定任何一个国家的发票长什么样。辖区码实时校验（美国辖区归 channel 16.3.2.3，录不进来）。"
      >
        {templates && templates.items.length === 0 ? (
          <EmptyState
            title="还没有登记发票模板"
            description="发票按平台/国家模板生成 —— 先登记一张模板（抬头、税档、号段规则），再建发票。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="text-copy-13 w-full text-left">
              <thead className="text-copy-12 text-muted-foreground">
                <tr>
                  <th className="p-2">模板</th>
                  <th className="p-2">国家 / 辖区 / 平台</th>
                  <th className="p-2">类型 / 税种</th>
                  <th className="p-2">币种 / 抬头</th>
                  <th className="p-2">编号规则</th>
                  <th className="p-2">生效区间</th>
                  <th className="p-2">状态</th>
                </tr>
              </thead>
              <tbody>
                {(templates?.items ?? []).map((t) => (
                  <tr key={t.id} className="border-t">
                    <td className="p-2">
                      <div>{t.templateCode}</div>
                      <div className="text-copy-12 text-muted-foreground">{t.templateName}</div>
                    </td>
                    <td className="p-2">
                      <div>{t.jurisdictionName || t.jurisdictionCode}</div>
                      <div className="text-copy-12 text-muted-foreground">
                        {t.countryCode}
                        {t.platformCode ? ` · ${t.platformCode}` : ' · 通用模板'}
                      </div>
                    </td>
                    <td className="p-2">
                      <div>{t.documentTypeLabel || documentTypeLabel(t.documentType)}</div>
                      <div className="text-copy-12 text-muted-foreground">
                        {t.taxType}
                        {t.taxCode ? ` · ${t.taxCode}` : ''}
                      </div>
                    </td>
                    <td className="p-2">
                      <div>{t.currency}</div>
                      <div className="text-copy-12 text-muted-foreground">{t.title || '—'}</div>
                    </td>
                    <td className="p-2">
                      <div>{t.sequencePrefix}</div>
                      <div className="text-copy-12 text-muted-foreground">
                        {sequenceScopeLabel(t.sequenceScope)} ·{' '}
                        {sequencePeriodLabel(t.sequencePeriod)}
                      </div>
                    </td>
                    <td className="p-2">
                      <div>{t.effectiveFrom}</div>
                      <div className="text-copy-12 text-muted-foreground">
                        {t.effectiveTo ? `至 ${t.effectiveTo}` : '未失效'}
                      </div>
                    </td>
                    <td className="p-2">
                      <Badge tone={t.status === 'ACTIVE' ? 'success' : 'neutral'}>
                        {t.status === 'ACTIVE' ? '生效中' : '已停用'}
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="mt-4 border-t pt-4">
          <div className="text-copy-13 font-medium">登记模板</div>
          <div className="mt-2 grid gap-3 md:grid-cols-3">
            <label className="text-copy-12">
              <span className="text-muted-foreground">模板编码</span>
              <Input
                className="mt-1"
                value={tplCode}
                onChange={(e) => setTplCode(e.target.value)}
              />
            </label>
            <label className="text-copy-12">
              <span className="text-muted-foreground">模板名称</span>
              <Input
                className="mt-1"
                value={tplName}
                onChange={(e) => setTplName(e.target.value)}
              />
            </label>
            <label className="text-copy-12">
              <span className="text-muted-foreground">目的国（ISO alpha-2）</span>
              <Input
                className="mt-1"
                maxLength={2}
                placeholder="DE"
                value={tplCountry}
                onChange={(e) => setTplCountry(e.target.value)}
              />
            </label>
            <label className="text-copy-12">
              <span className="text-muted-foreground">辖区码</span>
              <Input
                className="mt-1"
                placeholder="DE"
                value={tplJurisdiction}
                onChange={(e) => setTplJurisdiction(e.target.value)}
              />
            </label>
            <label className="text-copy-12">
              <span className="text-muted-foreground">平台编码（留空 = 通用模板）</span>
              <Input
                className="mt-1"
                value={tplPlatform}
                onChange={(e) => setTplPlatform(e.target.value)}
              />
            </label>
            <label className="text-copy-12">
              <span className="text-muted-foreground">单据类型</span>
              <select
                className="border-control-border bg-card text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
                value={tplDocumentType}
                onChange={(e) => setTplDocumentType(e.target.value)}
              >
                <option value="INVOICE">销售发票</option>
                <option value="CREDIT_NOTE">贷项通知单</option>
              </select>
            </label>
            <label className="text-copy-12">
              <span className="text-muted-foreground">默认税种</span>
              <select
                className="border-control-border bg-card text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
                value={tplTaxType}
                onChange={(e) => setTplTaxType(e.target.value)}
              >
                <option value="SALES_TAX">销售税</option>
                <option value="VAT">增值税</option>
                <option value="IOSS">IOSS</option>
                <option value="WITHHOLDING">代扣代缴</option>
              </select>
            </label>
            <label className="text-copy-12">
              <span className="text-muted-foreground">默认税码</span>
              <Input
                className="mt-1"
                value={tplTaxCode}
                onChange={(e) => setTplTaxCode(e.target.value)}
              />
            </label>
            <label className="text-copy-12">
              <span className="text-muted-foreground">币种</span>
              <Input
                className="mt-1"
                maxLength={3}
                value={tplCurrency}
                onChange={(e) => setTplCurrency(e.target.value)}
              />
            </label>
            <label className="text-copy-12">
              <span className="text-muted-foreground">发票抬头</span>
              <Input
                className="mt-1"
                value={tplTitle}
                onChange={(e) => setTplTitle(e.target.value)}
              />
            </label>
            <label className="text-copy-12">
              <span className="text-muted-foreground">编号前缀</span>
              <Input
                className="mt-1"
                placeholder="INV-DE"
                value={tplPrefix}
                onChange={(e) => setTplPrefix(e.target.value)}
              />
            </label>
            <label className="text-copy-12">
              <span className="text-muted-foreground">编号范围</span>
              <select
                className="border-control-border bg-card text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
                value={tplScope}
                onChange={(e) => setTplScope(e.target.value)}
              >
                <option value="TENANT">全租户一个号段</option>
                <option value="COUNTRY">按目的国分号段</option>
                <option value="PLATFORM">按平台分号段</option>
              </select>
            </label>
            <label className="text-copy-12">
              <span className="text-muted-foreground">编号期间口径</span>
              <select
                className="border-control-border bg-card text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
                value={tplPeriod}
                onChange={(e) => setTplPeriod(e.target.value)}
              >
                <option value="NONE">不按期间</option>
                <option value="MONTHLY">按月</option>
                <option value="YEARLY">按年</option>
              </select>
            </label>
            <label className="text-copy-12">
              <span className="text-muted-foreground">生效时间</span>
              <Input
                className="mt-1"
                type="datetime-local"
                value={tplEffectiveFrom}
                onChange={(e) => setTplEffectiveFrom(e.target.value)}
              />
            </label>
            <label className="text-copy-12 md:col-span-2">
              <span className="text-muted-foreground">备注</span>
              <Input
                className="mt-1"
                value={tplNote}
                onChange={(e) => setTplNote(e.target.value)}
              />
            </label>
          </div>
          <Button className="mt-3" onClick={() => void submitTemplate()}>
            登记模板
          </Button>
        </div>
      </SectionCard>

      {/* 5. 待开票需求 + 批量建单 */}
      <SectionCard
        title="待开票需求（上游 9 订单，只读）"
        description="本模块只读订单侧的开票需求，不回写 invoice_state（那张表的写入所有权在 trade）。批量里失败的那几条逐条说清原因。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={batchTemplateId}
              onChange={(e) => setBatchTemplateId(e.target.value)}
              aria-label="批量建单用的模板"
            >
              <option value="">选模板</option>
              {usableTemplates.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.templateCode} · {t.templateName}
                </option>
              ))}
            </select>
            <Button variant="secondary" onClick={() => void doBatch()}>
              批量建单（{batchSelected.length}）
            </Button>
          </div>
        }
      >
        {requests === null ? (
          <Notice tone="warning" title="待开票需求读不到">
            <TriangleAlert className="mr-2 inline h-4 w-4" />
            上游（trade）读不到 —— 这<strong>不等于</strong>「没有待开票需求」。
          </Notice>
        ) : requests.items.length === 0 ? (
          <EmptyState
            title="上游没有待开票需求"
            description="订单侧还没有提出开票需求的应收单（b2b_receivable.invoice_state='REQUESTED'）。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="text-copy-13 w-full text-left">
              <thead className="text-copy-12 text-muted-foreground">
                <tr>
                  <th className="p-2">选择</th>
                  <th className="p-2">需求号 / 订单</th>
                  <th className="p-2">客户</th>
                  <th className="p-2">金额</th>
                  <th className="p-2">提出时间</th>
                  <th className="p-2">明细</th>
                </tr>
              </thead>
              <tbody>
                {requests.items.map((r) => (
                  <tr key={r.id} className="border-t">
                    <td className="p-2">
                      <input
                        type="checkbox"
                        aria-label={`选择 ${r.receivableNo}`}
                        checked={batchSelected.includes(r.id)}
                        onChange={(e) =>
                          setBatchSelected((prev) =>
                            e.target.checked ? [...prev, r.id] : prev.filter((x) => x !== r.id),
                          )
                        }
                      />
                    </td>
                    <td className="p-2">
                      <div>{r.receivableNo || '—'}</div>
                      <div className="text-copy-12 text-muted-foreground">{r.orderNo || '—'}</div>
                    </td>
                    <td className="p-2">{r.customerName || '—'}</td>
                    <td className="p-2">{formatMoney(r.amount, r.currency)}</td>
                    <td className="p-2">{r.invoiceRequestedAt || '—'}</td>
                    <td className="p-2">
                      <div>{r.lines?.length ?? 0} 行</div>
                      <div className="text-copy-12 text-muted-foreground">
                        {(r.lines ?? [])
                          .slice(0, 3)
                          .map((l) => l.skuCode || '—')
                          .join('、')}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-copy-12 text-muted-foreground mt-2">{requests.note}</p>
          </div>
        )}
      </SectionCard>

      {/* 6. 汇总（按币种分组） */}
      <SectionCard
        title="发票汇总（🔴 按币种分组）"
        description="本模块不做本位币折算，页面上**不出现**跨币种的合计 —— 按 1:1 相加出来的「总额」是假的。"
      >
        {summary === null || summary.byCurrency.length === 0 ? (
          <EmptyState
            title="还没有可汇总的发票"
            description="草稿不进合计（草稿不是对外凭据）。开具或红冲之后才有合计。"
          />
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full text-left">
                <thead className="text-copy-12 text-muted-foreground">
                  <tr>
                    <th className="p-2">币种</th>
                    <th className="p-2">小计</th>
                    <th className="p-2">税额</th>
                    <th className="p-2">合计</th>
                    <th className="p-2">发票 / 贷项单</th>
                    <th className="p-2">汇率口径</th>
                  </tr>
                </thead>
                <tbody>
                  {summary.byCurrency.map((c) => (
                    <tr key={c.currency} className="border-t">
                      <td className="p-2">{c.currency}</td>
                      <td className="p-2">{formatMoney(c.subtotal, c.currency)}</td>
                      <td className="p-2">{formatMoney(c.tax, c.currency)}</td>
                      <td className="p-2">{formatMoney(c.total, c.currency)}</td>
                      <td className="p-2">
                        {c.invoiceCount} / {c.creditNoteCount}
                      </td>
                      <td className="p-2">
                        {c.baseAmount === null || c.baseAmount === undefined
                          ? c.exchangeRateNote
                          : `${formatMoney(c.baseAmount, summary.baseCurrency)} · ${c.exchangeRateNote}`}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="text-copy-12 text-muted-foreground mt-2">{summary.note}</p>
          </>
        )}
      </SectionCard>

      {/* 7. 平台开票对接 */}
      <SectionCard
        title="平台开票对接"
        description="🔴 递交失败**也留痕**（写上游原话）—— 「提交失败」不留痕等于没提交过，现场会以为平台已经收到发票。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <Input
              className="w-40"
              placeholder="平台编码（留空 = 用发票自己的）"
              value={submitPlatformCode}
              onChange={(e) => setSubmitPlatformCode(e.target.value)}
              aria-label="目标平台编码"
            />
            <Button variant="secondary" onClick={() => void doSubmitPlatform()}>
              批量递交（{submitSelected.length}）
            </Button>
          </div>
        }
      >
        {submissions === null || submissions.items.length === 0 ? (
          <EmptyState
            title="还没有平台提交记录"
            description="递交平台开票后，每一次提交（成功或失败）都会在这里留痕。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="text-copy-13 w-full text-left">
              <thead className="text-copy-12 text-muted-foreground">
                <tr>
                  <th className="p-2">发票</th>
                  <th className="p-2">平台</th>
                  <th className="p-2">状态</th>
                  <th className="p-2">回执号</th>
                  <th className="p-2">失败原因</th>
                  <th className="p-2">时间</th>
                  <th className="p-2">操作</th>
                </tr>
              </thead>
              <tbody>
                {submissions.items.map((s) => (
                  <tr key={s.id} className="border-t">
                    <td className="p-2">{s.invoiceNumber || s.invoiceId}</td>
                    <td className="p-2">{s.platformCode}</td>
                    <td className="p-2">
                      <Badge
                        tone={
                          s.status === 'ACKNOWLEDGED'
                            ? 'success'
                            : s.status === 'FAILED'
                              ? 'danger'
                              : 'neutral'
                        }
                      >
                        {s.statusLabel || s.status}
                      </Badge>
                    </td>
                    <td className="p-2">{s.platformRef || '—'}</td>
                    <td className="p-2">{s.errorMessage || '—'}</td>
                    <td className="p-2">{s.submittedAt || s.createdAt}</td>
                    <td className="p-2">
                      <div className="flex flex-wrap gap-1">
                        <Button variant="secondary" onClick={() => setSelectedId(s.invoiceId)}>
                          查看发票
                        </Button>
                        <Button variant="secondary" onClick={() => setAckSubmissionId(s.id)}>
                          登记回执
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="mt-3 grid gap-3 md:grid-cols-3">
              <label className="text-copy-12">
                <span className="text-muted-foreground">回执号</span>
                <Input
                  className="mt-1"
                  value={ackRef}
                  onChange={(e) => setAckRef(e.target.value)}
                />
              </label>
              <label className="text-copy-12">
                <span className="text-muted-foreground">回执结果</span>
                <select
                  className="border-control-border bg-card text-copy-13 mt-1 h-9 w-full rounded-md border px-2"
                  value={ackResult}
                  onChange={(e) => setAckResult(e.target.value)}
                >
                  <option value="ACKNOWLEDGED">平台已确认</option>
                  <option value="FAILED">平台驳回</option>
                </select>
              </label>
              <label className="text-copy-12">
                <span className="text-muted-foreground">回执说明</span>
                <Input
                  className="mt-1"
                  value={ackMessage}
                  onChange={(e) => setAckMessage(e.target.value)}
                />
              </label>
            </div>
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <Button
                variant="secondary"
                disabled={!ackSubmissionId}
                onClick={() => void doAcknowledge()}
              >
                登记回执
              </Button>
              {ackSubmissionId ? (
                <span className="text-copy-12 text-muted-foreground">
                  目标提交记录 {ackSubmissionId}
                </span>
              ) : (
                <span className="text-copy-12 text-muted-foreground">
                  先在上面点某条提交记录的「登记回执」
                </span>
              )}
            </div>
          </div>
        )}
      </SectionCard>

      {/* 8. 各国覆盖情况 */}
      <SectionCard
        title="各国覆盖情况"
        description="🔴 蓝图原文：「各国发票要求差异大，模板必须按国家配置；本系统不承诺覆盖所有国家税务规则。」这一块不是免责声明，而是逐个辖区给出事实。"
      >
        {(caps?.countryCoverage ?? []).length === 0 ? (
          <EmptyState
            title="没有可用的辖区选项"
            description="辖区码表来自 channel 16.3.1.1 的受控码表；读不到时这里会显示「读不到」，而不是「没有国家」。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="text-copy-13 w-full text-left">
              <thead className="text-copy-12 text-muted-foreground">
                <tr>
                  <th className="p-2">国家 / 辖区</th>
                  <th className="p-2">模板已配</th>
                  <th className="p-2">税率可取</th>
                  <th className="p-2">结论</th>
                </tr>
              </thead>
              <tbody>
                {(caps?.countryCoverage ?? []).map((c) => (
                  <tr key={`${c.countryCode}-${c.jurisdictionCode}`} className="border-t">
                    <td className="p-2">
                      <div>{c.name || c.jurisdictionCode}</div>
                      <div className="text-copy-12 text-muted-foreground">
                        {c.countryCode} · {c.jurisdictionCode}
                      </div>
                    </td>
                    <td className="p-2">
                      <Badge tone={c.templateConfigured ? 'success' : 'neutral'}>
                        {c.templateConfigured ? '已配模板' : '未配模板'}
                      </Badge>
                    </td>
                    <td className="p-2">
                      <Badge tone={c.taxRateResolvable ? 'success' : 'neutral'}>
                        {c.taxRateResolvable ? '可取税率' : '无生效税率'}
                      </Badge>
                    </td>
                    <td className="p-2">
                      <Badge
                        tone={
                          countryCoverageTone({
                            templateConfigured: c.templateConfigured,
                            taxRateResolvable: c.taxRateResolvable,
                          }) === 'success'
                            ? 'success'
                            : countryCoverageTone({
                                  templateConfigured: c.templateConfigured,
                                  taxRateResolvable: c.taxRateResolvable,
                                }) === 'warning'
                              ? 'warning'
                              : 'danger'
                        }
                      >
                        {c.templateConfigured && c.taxRateResolvable
                          ? '可开票'
                          : c.templateConfigured
                            ? '缺税率'
                            : '缺模板'}
                      </Badge>
                      <p className="text-copy-12 text-muted-foreground mt-1">{c.note}</p>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* 9. 能力声明 */}
      <SectionCard title="能力声明" description="哪些上游真接通、职责怎么切、哪些还没交付、归谁。">
        <div className="space-y-2">
          {(caps?.capabilities ?? []).map((c) => (
            <div key={`${c.module}-${c.name}`} className="rounded-md border p-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-copy-13 font-medium">{c.name}</span>
                <Badge tone={c.available ? 'success' : 'neutral'}>
                  {c.module} {c.available ? '已接通' : '未接通'}
                </Badge>
                {c.ownerModule ? <Badge tone="neutral">归属 {c.ownerModule}</Badge> : null}
              </div>
              <p className="text-copy-12 text-muted-foreground mt-1">依据：{c.basis}</p>
              <p className="text-copy-12 mt-1">{c.detail}</p>
            </div>
          ))}
          {(caps?.downstreamGaps ?? []).map((g) => (
            <div key={`${g.module}-${g.name}`} className="rounded-md border border-dashed p-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-copy-13 font-medium">{g.name}</span>
                <Badge tone={g.available ? 'success' : 'warning'}>
                  {g.module} {g.available ? '已交付' : '未交付'}
                </Badge>
                <Badge tone="neutral">{g.ownerService}</Badge>
                <Badge tone="neutral">{g.group}</Badge>
              </div>
              <p className="text-copy-12 mt-1">{g.detail}</p>
            </div>
          ))}
        </div>
      </SectionCard>
    </div>
  );
}
