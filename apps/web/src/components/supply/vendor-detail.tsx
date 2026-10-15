'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
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
  EMPTY_TERMS_FORM,
  amountListText,
  createVendorQualification,
  creditLimitText,
  daysLateText,
  deleteVendorQualification,
  fetchVendorDetailOptions,
  fetchVendorPanorama,
  fetchVendorPurchaseHistory,
  fetchVendorQualifications,
  fetchVendorQuotes,
  invoiceTypeLabel,
  operationDisabledReason,
  qualificationTypeLabel,
  rateLabel,
  rateTone,
  saveVendorTerms,
  settlementMethodLabel,
  taxRateText,
  toTermsInput,
  updateVendorQualification,
  validateQualification,
  validateTerms,
  validityLabel,
  validityTone,
  windowText,
} from './vendor-detail-model';
import type {
  VendorDetailOptions,
  VendorPanorama,
  VendorPurchaseHistoryPage,
  VendorQualificationInput,
  VendorQualificationList,
  VendorQuotePage,
} from './vendor-detail-model';

/**
 * 下拉字段。
 *
 * <p>刻意不用 {@code FormField}：它只接受 {@code Input} 子元素（cloneElement 往输入框注入
 * id / required / aria-*），把 {@code <select>} 塞进去会把这些属性注到一个没有对应语义的元素上。
 */
function SelectField({
  id,
  label,
  value,
  options,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  options: { value: string; label: string }[];
  onChange: (value: string) => void;
}) {
  return (
    <div className="space-y-2">
      <label htmlFor={id} className="text-label-13 text-foreground block">
        {label}
      </label>
      <select
        id={id}
        className="border-input bg-background h-9 w-full rounded border px-2"
        value={value}
        onChange={(e) => onChange(e.target.value)}
      >
        {options.map((opt) => (
          <option key={opt.value} value={opt.value}>
            {opt.label}
          </option>
        ))}
      </select>
    </div>
  );
}

/**
 * 6.1.1.2 供应商详情：单供应商全景。
 *
 * <p>🔴 页面顶部那句话不是提示语，是这一页的设计前提：
 * **编码 / 名称 / 联系方式 / 合作等级 / 采购侧合作状态全部只读**，页面上没有任何编辑它们的入口 ——
 * 它们分别要往来单位档案（/partners）与供应商列表（/supply/vendors）去改。
 * 本页能写的只有两段：**账期与开票信息**（蓝图点名它是付款审批的输入）与**附件资质**。
 *
 * <p>比率与窗口两件事必须一起看：准时率 / 合格率是**窗口统计**，页面顶部常驻显示当前窗口；
 * 窗口内没有记录时显示「暂无数据」而不是 0% ——「还没交货过」与「交货全部延期」是相反的两件事。
 *
 * <p>金额一律按币种分列：仓库没有汇率表，100 USD + 90 EUR = 190 是一个看起来对、实际错、
 * 而且极难被发现的数字。
 */
export function VendorDetailWorkspace({ roleId }: { roleId: string }) {
  const [panorama, setPanorama] = useState<VendorPanorama | null>(null);
  const [history, setHistory] = useState<VendorPurchaseHistoryPage | null>(null);
  const [quotes, setQuotes] = useState<VendorQuotePage | null>(null);
  const [qualifications, setQualifications] = useState<VendorQualificationList | null>(null);
  const [options, setOptions] = useState<VendorDetailOptions | null>(null);
  const [termsForm, setTermsForm] = useState(EMPTY_TERMS_FORM);
  const [editingQualification, setEditingQualification] = useState<string | null>(null);
  const [qualForm, setQualForm] = useState<VendorQualificationInput | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [pano, hist, quotePage, quals, opts] = await Promise.all([
        fetchVendorPanorama(roleId),
        fetchVendorPurchaseHistory(roleId, { size: 20 }),
        fetchVendorQuotes(roleId, { size: 20 }),
        fetchVendorQualifications(roleId),
        fetchVendorDetailOptions(),
      ]);
      setPanorama(pano);
      setHistory(hist);
      setQuotes(quotePage);
      setQualifications(quals);
      setOptions(opts);
      // 未建档时给一份空表单；已建档时把现有值带进表单（version 一并带上，提交时做乐观锁）
      setTermsForm(
        pano.terms
          ? {
              currency: pano.terms.currency,
              paymentTermsDays: pano.terms.paymentTermsDays,
              settlementMethod: pano.terms.settlementMethod,
              invoiceType: pano.terms.invoiceType,
              invoiceTitle: pano.terms.invoiceTitle,
              taxId: pano.terms.taxId,
              taxRate: pano.terms.taxRate,
              creditLimit: pano.terms.creditLimit,
              depositRatio: pano.terms.depositRatio,
              effectiveFrom: pano.terms.effectiveFrom,
              note: pano.terms.note,
              version: pano.terms.version,
            }
          : { ...EMPTY_TERMS_FORM },
      );
      setLoaded(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : '供应商详情读不到');
    } finally {
      setBusy(false);
    }
  }, [roleId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function submitTerms() {
    if (!panorama) return;
    const input = toTermsInput(termsForm, panorama.partyName);
    const problems = validateTerms(input);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await saveVendorTerms(roleId, input);
      setNotice(
        `账期与开票信息已保存：${saved.currency} 账期 ${saved.paymentTermsDays} 天，12 财务可以据此生成付款申请。`,
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '账期与开票信息保存失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitQualification() {
    if (!qualForm) return;
    const problems = validateQualification(qualForm);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      if (editingQualification) {
        await updateVendorQualification(roleId, editingQualification, qualForm);
        setNotice('附件资质已更新。');
      } else {
        await createVendorQualification(roleId, qualForm);
        setNotice('附件资质已登记。');
      }
      setQualForm(null);
      setEditingQualification(null);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '附件资质保存失败');
    } finally {
      setBusy(false);
    }
  }

  async function removeQualification(id: string, title: string) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await deleteVendorQualification(roleId, id);
      setNotice(`已删除附件资质「${title}」。`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '附件资质删除失败');
    } finally {
      setBusy(false);
    }
  }

  if (!loaded && !error) {
    return (
      <>
        <PageHeader title="供应商详情" description="正在读取供应商全景…" />
        <p className="text-muted-foreground text-sm">加载中…</p>
      </>
    );
  }

  if (error && !panorama) {
    return (
      <>
        <PageHeader title="供应商详情" description="读不到这个供应商的全景。" />
        <Notice tone="danger" title="读不到这个供应商的全景">
          {error}
        </Notice>
        <Link className="text-sm underline" href="/supply/vendors">
          返回供应商列表
        </Link>
      </>
    );
  }

  if (!panorama) return null;

  return (
    <>
      <PageHeader
        title={panorama.partyName}
        description={`供应商全景 · ${panorama.partyCode} · 编码 / 名称 / 联系方式 / 合作等级来自往来单位档案（03），本页只读；本页可写的只有账期与开票信息、附件资质。交期与质量表现是窗口统计（${windowText(panorama.metricWindow)}）。`}
      />

      <div className="mb-4 flex flex-wrap gap-2">
        <Link className="text-sm underline" href="/supply/vendors">
          ← 供应商列表
        </Link>
        {panorama.operations.map((op) =>
          op.available && op.href ? (
            <Link key={op.key} className="text-sm underline" href={op.href}>
              {op.label}
            </Link>
          ) : (
            <Badge key={op.key} tone="neutral" title={operationDisabledReason(op)}>
              {op.label}（暂不可用）
            </Badge>
          ),
        )}
      </div>

      {error ? <p className="text-destructive text-sm">{error}</p> : null}
      {notice ? <p className="text-muted-foreground text-sm">{notice}</p> : null}

      {panorama.operations
        .filter((op) => !op.available)
        .map((op) => (
          <p key={`${op.key}-reason`} className="text-muted-foreground text-sm">
            「{op.label}」暂不可用：{operationDisabledReason(op)}
          </p>
        ))}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="准时交付率"
          value={rateLabel(panorama.delivery.onTimeRate)}
          polarity={rateTone(panorama.delivery.onTimeRate)}
          comparisonLabel={`窗口内 ${panorama.delivery.deliveryCount} 笔到货 · ${windowText(panorama.metricWindow)}`}
        />
        <StatCard
          label="质量合格率"
          value={rateLabel(panorama.quality.qualifiedRate)}
          polarity={rateTone(panorama.quality.qualifiedRate)}
          comparisonLabel={`按数量比 · 不合格 ${panorama.quality.unqualifiedQuantity}`}
        />
        <StatCard
          label="采购金额"
          value={amountListText(panorama.purchaseAmounts)}
          comparisonLabel="按币种分列，绝不相加"
        />
        <StatCard
          label="供货 SKU"
          value={String(panorama.quotedSkuCount)}
          comparisonLabel={`有效报价 ${panorama.effectiveQuoteCount} / 共 ${panorama.quoteCount} 条`}
        />
      </div>

      <Tabs defaultValue="terms" className="mt-4">
        <TabsList>
          <TabsTrigger value="terms">账期与开票</TabsTrigger>
          <TabsTrigger value="performance">交期与质量</TabsTrigger>
          <TabsTrigger value="quotes">报价（{panorama.quotedSkuCount} SKU）</TabsTrigger>
          <TabsTrigger value="history">采购历史（{history?.total ?? 0}）</TabsTrigger>
          <TabsTrigger value="contacts">联系人（{panorama.contacts.length}）</TabsTrigger>
          <TabsTrigger value="qualifications">
            附件资质（{panorama.qualificationCount}）
          </TabsTrigger>
          <TabsTrigger value="basic">基础信息</TabsTrigger>
        </TabsList>

        {/* ================================ 账期与开票（可写） */}
        <TabsContent value="terms">
          <SectionCard title="账期与开票信息">
            <p className="text-muted-foreground mb-3 text-sm">
              蓝图点名它是<b>付款审批的输入</b>：缺失时 12 财务无法自动生成付款申请
              （到期日、发票类型、税号都取不到），所以这里是一份<b>可维护的真实数据</b>，
              不是从别处只读一个字段。
            </p>
            {panorama.termsFound ? null : (
              <Notice tone="warning" title="还没建账期与开票信息">
                12 财务无法自动生成付款申请（到期日、发票类型、税号都取不到），下单前必须补齐。
              </Notice>
            )}
            <div className="grid gap-3 sm:grid-cols-2">
              <FormField id="terms-currency" label="结算币种（ISO 4217）">
                <Input
                  value={termsForm.currency}
                  onChange={(e) => setTermsForm({ ...termsForm, currency: e.target.value })}
                />
              </FormField>
              <FormField id="terms-days" label="本采购线账期（天，0 = 不给账期）">
                <Input
                  type="number"
                  value={termsForm.paymentTermsDays}
                  onChange={(e) =>
                    setTermsForm({ ...termsForm, paymentTermsDays: Number(e.target.value) })
                  }
                />
              </FormField>
              <SelectField
                id="terms-settlement-method"
                label="结算方式"
                value={termsForm.settlementMethod}
                options={options?.settlementMethods ?? []}
                onChange={(v) => setTermsForm({ ...termsForm, settlementMethod: v })}
              />
              <SelectField
                id="terms-invoice-type"
                label="开票方式"
                value={termsForm.invoiceType}
                options={options?.invoiceTypes ?? []}
                onChange={(v) => setTermsForm({ ...termsForm, invoiceType: v })}
              />
              <FormField id="terms-title" label="发票抬头（跨境里常与主体名不同）">
                <Input
                  value={termsForm.invoiceTitle}
                  onChange={(e) => setTermsForm({ ...termsForm, invoiceTitle: e.target.value })}
                />
              </FormField>
              <FormField id="terms-tax-id" label="纳税人识别号">
                <Input
                  value={termsForm.taxId}
                  onChange={(e) => setTermsForm({ ...termsForm, taxId: e.target.value })}
                />
              </FormField>
              <FormField id="terms-tax-rate" label="税率（0~1 的比例，13% 填 0.13）">
                <Input
                  value={termsForm.taxRate}
                  onChange={(e) => setTermsForm({ ...termsForm, taxRate: e.target.value })}
                />
              </FormField>
              <FormField id="terms-credit-limit" label="采购信用额度（留空 = 未设额度，不是 0）">
                <Input
                  value={termsForm.creditLimit ?? ''}
                  onChange={(e) =>
                    setTermsForm({
                      ...termsForm,
                      creditLimit: e.target.value === '' ? null : e.target.value,
                    })
                  }
                />
              </FormField>
              <FormField id="terms-deposit-ratio" label="预付比例（0~1）">
                <Input
                  value={termsForm.depositRatio}
                  onChange={(e) => setTermsForm({ ...termsForm, depositRatio: e.target.value })}
                />
              </FormField>
              <FormField id="terms-effective-from" label="约定生效日">
                <Input
                  type="date"
                  value={termsForm.effectiveFrom}
                  onChange={(e) => setTermsForm({ ...termsForm, effectiveFrom: e.target.value })}
                />
              </FormField>
            </div>
            <div className="mt-3">
              <Button onClick={submitTerms} disabled={busy}>
                {panorama.termsFound ? '保存账期与开票信息' : '建账期与开票信息'}
              </Button>
            </div>
            {panorama.terms ? (
              <p className="text-muted-foreground mt-3 text-sm">
                主体默认账期（3.6.1.2，只读）：{panorama.terms.masterPaymentTermsDays ?? '未设置'}{' '}
                天 /
                {panorama.terms.masterSettlementMethod
                  ? settlementMethodLabel(panorama.terms.masterSettlementMethod)
                  : '未设置'}
                。两者不同是正常的：一个主体可以同时有 USD 预付与 EUR 月结两条采购线，
                付款审批要的是本采购线这一份。
              </p>
            ) : null}
          </SectionCard>
        </TabsContent>

        {/* ================================ 交期与质量 */}
        <TabsContent value="performance">
          <SectionCard title={`交期与质量表现（窗口 ${windowText(panorama.metricWindow)}）`}>
            <p className="text-muted-foreground mb-3 text-sm">
              四个数全部是本窗口内的 SQL 聚合，没有一个是存下来的字段； 准时与否由「实际到货日 ≤
              承诺到货日」推出，库里不存 on_time 布尔列。
            </p>
            <dl className="grid gap-3 sm:grid-cols-2">
              <div>
                <dt className="text-muted-foreground text-sm">准时交付率</dt>
                <dd>
                  {rateLabel(panorama.delivery.onTimeRate)}
                  <span className="text-muted-foreground text-sm">
                    （{panorama.delivery.onTimeCount} / {panorama.delivery.deliveryCount} 笔）
                  </span>
                </dd>
              </div>
              <div>
                <dt className="text-muted-foreground text-sm">平均延期</dt>
                <dd>{daysLateText(panorama.delivery.averageDaysLate)}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground text-sm">最大延期 / 早到笔数</dt>
                <dd>
                  {panorama.delivery.maxDaysLate === null
                    ? '暂无数据'
                    : `${panorama.delivery.maxDaysLate} 天`}
                  <span className="text-muted-foreground text-sm">
                    （早到 {panorama.delivery.earlyCount} 笔）
                  </span>
                </dd>
              </div>
              <div>
                <dt className="text-muted-foreground text-sm">最近到货</dt>
                <dd>{panorama.delivery.lastDeliveryAt ?? '暂无数据'}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground text-sm">质量合格率（按数量比）</dt>
                <dd>
                  {rateLabel(panorama.quality.qualifiedRate)}
                  <span className="text-muted-foreground text-sm">
                    （检验 {panorama.quality.inspectedQuantity} / 不合格{' '}
                    {panorama.quality.unqualifiedQuantity}）
                  </span>
                </dd>
              </div>
              <div>
                <dt className="text-muted-foreground text-sm">最近质检</dt>
                <dd>{panorama.quality.lastInspectionAt ?? '暂无数据'}</dd>
              </div>
            </dl>
          </SectionCard>
        </TabsContent>

        {/* ================================ 报价（只读） */}
        <TabsContent value="quotes">
          <SectionCard title="供货 SKU 与报价">
            <p className="text-muted-foreground mb-3 text-sm">
              <b>只读</b>：报价的写入所有权在 3.6.2.1 报价台账（「更新报价」入口指过去）。
              在同一份数据上提供第二套编辑能力，就是蓝图点名要避免的「同一实体两处可编辑」。
              起订档单价取起订量最小的那一档，完整阶梯在报价详情里。
            </p>
            {quotes && quotes.items.length === 0 ? (
              <EmptyState
                title="还没有报价"
                description="到 3.6.2.1 报价台账登记后，这里会自动显示。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="p-2">SKU</th>
                    <th className="p-2">供应商货号</th>
                    <th className="p-2">起订量</th>
                    <th className="p-2">起订单价</th>
                    <th className="p-2">交期</th>
                    <th className="p-2">有效期</th>
                    <th className="p-2">状态</th>
                  </tr>
                </thead>
                <tbody>
                  {quotes?.items.map((q) => (
                    <tr key={q.id} className="border-b">
                      <td className="p-2">
                        {q.skuCode ?? q.skuId.slice(0, 8)}
                        <span className="text-muted-foreground block text-xs">
                          {q.skuName ?? ''}
                        </span>
                      </td>
                      <td className="p-2">{q.supplierSkuCode}</td>
                      <td className="p-2">
                        {q.moqQuantity} {q.purchaseUnitCode}
                      </td>
                      <td className="p-2">
                        {q.firstTierUnitPrice ?? '—'} {q.currency}
                        <span className="text-muted-foreground block text-xs">
                          {q.tierCount} 个阶梯
                        </span>
                      </td>
                      <td className="p-2">{q.leadTimeDays} 天</td>
                      <td className="p-2">
                        {q.validFrom} ~ {q.validTo ?? '长期'}
                      </td>
                      <td className="p-2">
                        <Badge tone={q.effective ? 'success' : 'neutral'}>
                          {q.effective ? '有效' : q.status === 'ACTIVE' ? '已过期' : '已停用'}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {quotes ? (
              <p className="text-muted-foreground mt-2 text-xs">
                有效报价覆盖 {quotes.effectiveSkuCount} 个 SKU（按 {quotes.effectiveOn} 判定）。
                {quotes.note}
              </p>
            ) : null}
          </SectionCard>
        </TabsContent>

        {/* ================================ 采购历史 */}
        <TabsContent value="history">
          <SectionCard title="采购历史与金额">
            {history && history.items.length === 0 ? (
              <EmptyState
                title="窗口内没有到货记录"
                description="到货是采购侧可举证的事实（收货单 + 质检 + 金额），由供应商列表页追加。"
              />
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="p-2">到货单号</th>
                    <th className="p-2">数量</th>
                    <th className="p-2">金额</th>
                    <th className="p-2">承诺 / 实际到货</th>
                    <th className="p-2">准时</th>
                    <th className="p-2">备注</th>
                  </tr>
                </thead>
                <tbody>
                  {history?.items.map((h) => (
                    <tr key={h.id} className="border-b">
                      <td className="p-2">
                        {h.deliveryRef}
                        <span className="text-muted-foreground block text-xs">
                          {h.skuCode ?? '整单到货'}
                        </span>
                      </td>
                      <td className="p-2">
                        {h.quantity} {h.purchaseUnitCode ?? ''}
                      </td>
                      <td className="p-2">
                        {h.amount} {h.currency}
                      </td>
                      <td className="p-2">
                        {h.dueDate} / {h.receivedDate}
                      </td>
                      <td className="p-2">
                        {h.isVoid ? (
                          <Badge tone="neutral">已作废</Badge>
                        ) : h.onTime ? (
                          <Badge>准时</Badge>
                        ) : (
                          <Badge tone="danger">晚 {h.daysLate} 天</Badge>
                        )}
                      </td>
                      <td className="p-2">{h.isVoid ? (h.voidReason ?? '') : h.note}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {history ? (
              <div className="mt-3">
                <p className="text-sm">
                  <b>按单位与币种分组的合计：</b>
                  {history.buckets.length === 0
                    ? '暂无数据'
                    : history.buckets
                        .map(
                          (b) =>
                            `${b.amount} ${b.currency}（${b.quantity} ${b.purchaseUnitCode || '未填单位'} / ${b.deliveryCount} 笔）`,
                        )
                        .join('　')}
                </p>
                {history.totalQuantity === null && history.buckets.length > 1 ? (
                  <p className="text-muted-foreground text-xs">
                    跨采购单位不给总数：把「3 箱 + 200
                    件」加成一个数在报表上看着正常、实际没有意义。
                  </p>
                ) : null}
                <p className="text-muted-foreground text-xs">{history.note}</p>
              </div>
            ) : null}
          </SectionCard>
        </TabsContent>

        {/* ================================ 联系人（只读） */}
        <TabsContent value="contacts">
          <SectionCard title="联系人">
            <p className="text-muted-foreground mb-3 text-sm">
              <b>只读</b>：联系人写在往来单位档案（3.6.x）。
              这里列出来是因为「问交期找谁」是采购每天都要用的信息， 但不在这里提供第二个编辑入口 ——
              那会让同一个联系人有两处可改。
            </p>
            {panorama.contacts.length === 0 ? (
              <EmptyState
                title="没有登记联系人"
                description="到往来单位档案（/partners）补建；没有联系人时问交期、约提货、对账都打不通。"
              />
            ) : (
              <ul className="text-sm">
                {panorama.contacts.map((c) => (
                  <li key={c.id} className="border-b py-2">
                    <b>{c.name}</b>
                    {c.primary ? <Badge className="ml-2">默认</Badge> : null}
                    <span className="text-muted-foreground"> {c.position ?? ''}</span>
                    <div className="text-muted-foreground">
                      {c.email ?? '—'} · {c.phone ?? '—'} · {c.mobile ?? '—'}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>
        </TabsContent>

        {/* ================================ 附件资质（可写） */}
        <TabsContent value="qualifications">
          <SectionCard title="附件资质">
            <p className="text-muted-foreground mb-3 text-sm">
              有效期状态是<b>按今天现算</b>的（VALID / EXPIRING_SOON / EXPIRED / UNKNOWN）， 不落库
              —— 存一列就要一个每日任务去维护它，而它与读出口算出的那天差一天时，
              「这张证到底过期没有」会答错，而这个答案直接决定能不能下单。
              文件本体不入库，只登记对象存储 key。
            </p>
            {panorama.qualificationExpiredCount > 0 ? (
              <Notice tone="danger" title="有附件资质已过期">
                {panorama.qualificationExpiredCount}{' '}
                份已过期：下单前必须换证，否则清关与平台准入会被卡。
              </Notice>
            ) : null}
            {panorama.qualificationExpiringCount > 0 ? (
              <Notice tone="warning" title="有附件资质即将到期">
                {panorama.qualificationExpiringCount} 份将在 30 天内到期：续期要 2~4
                周，现在就该催。
              </Notice>
            ) : null}
            {qualifications && qualifications.items.length === 0 ? (
              <EmptyState
                title="还没有登记附件资质"
                description="营业执照与体系认证是清关与平台准入的前置条件。"
              />
            ) : (
              <ul className="text-sm">
                {qualifications?.items.map((q) => (
                  <li key={q.id} className="flex flex-wrap items-center gap-2 border-b py-2">
                    <Badge tone={validityTone(q.validity) === 'positive' ? 'success' : 'danger'}>
                      {validityLabel(q)}
                    </Badge>
                    <b>{q.title}</b>
                    <span className="text-muted-foreground">
                      {qualificationTypeLabel(q.qualificationType)} · {q.certNo || '无编号'} ·{' '}
                      {q.issuer || '未填机构'}
                    </span>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => {
                        setEditingQualification(q.id);
                        setQualForm({
                          qualificationType: q.qualificationType,
                          title: q.title,
                          certNo: q.certNo,
                          issuer: q.issuer,
                          issuedOn: q.issuedOn,
                          expiresOn: q.expiresOn,
                          fileKey: q.fileKey,
                          fileName: q.fileName,
                          mimeType: q.mimeType,
                          fileSizeBytes: q.fileSizeBytes,
                          note: q.note,
                          version: q.version,
                        });
                      }}
                    >
                      改
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => removeQualification(q.id, q.title)}
                    >
                      删
                    </Button>
                  </li>
                ))}
              </ul>
            )}

            <div className="mt-4">
              <Button
                onClick={() => {
                  setEditingQualification(null);
                  setQualForm({
                    qualificationType: 'BUSINESS_LICENSE',
                    title: '',
                    certNo: '',
                    issuer: '',
                    issuedOn: null,
                    expiresOn: null,
                    fileKey: '',
                    fileName: '',
                    mimeType: null,
                    fileSizeBytes: null,
                    note: '',
                    version: 0,
                  });
                }}
              >
                登记一份附件资质
              </Button>
            </div>

            {qualForm ? (
              <div className="mt-3 grid gap-3 sm:grid-cols-2">
                <SelectField
                  id="qual-type"
                  label="资质类型"
                  value={qualForm.qualificationType}
                  options={options?.qualificationTypes ?? []}
                  onChange={(v) => setQualForm({ ...qualForm, qualificationType: v })}
                />
                <FormField id="qual-title" label="证件名称">
                  <Input
                    value={qualForm.title}
                    onChange={(e) => setQualForm({ ...qualForm, title: e.target.value })}
                  />
                </FormField>
                <FormField id="qual-cert-no" label="证书编号">
                  <Input
                    value={qualForm.certNo}
                    onChange={(e) => setQualForm({ ...qualForm, certNo: e.target.value })}
                  />
                </FormField>
                <FormField id="qual-issuer" label="发证机构">
                  <Input
                    value={qualForm.issuer}
                    onChange={(e) => setQualForm({ ...qualForm, issuer: e.target.value })}
                  />
                </FormField>
                <FormField id="qual-issued-on" label="签发日">
                  <Input
                    type="date"
                    value={qualForm.issuedOn ?? ''}
                    onChange={(e) =>
                      setQualForm({
                        ...qualForm,
                        issuedOn: e.target.value === '' ? null : e.target.value,
                      })
                    }
                  />
                </FormField>
                <FormField id="qual-expires-on" label="到期日（留空 = 长期有效）">
                  <Input
                    type="date"
                    value={qualForm.expiresOn ?? ''}
                    onChange={(e) =>
                      setQualForm({
                        ...qualForm,
                        expiresOn: e.target.value === '' ? null : e.target.value,
                      })
                    }
                  />
                </FormField>
                <FormField
                  id="qual-file-key"
                  label="文件存储 key（不是 URL：换存储服务商时不必改数据）"
                >
                  <Input
                    value={qualForm.fileKey}
                    onChange={(e) => setQualForm({ ...qualForm, fileKey: e.target.value })}
                  />
                </FormField>
                <FormField id="qual-file-name" label="展示文件名">
                  <Input
                    value={qualForm.fileName}
                    onChange={(e) => setQualForm({ ...qualForm, fileName: e.target.value })}
                  />
                </FormField>
                <div className="flex gap-2 sm:col-span-2">
                  <Button onClick={submitQualification} disabled={busy}>
                    {editingQualification ? '保存修改' : '登记'}
                  </Button>
                  <Button
                    variant="ghost"
                    onClick={() => {
                      setQualForm(null);
                      setEditingQualification(null);
                    }}
                  >
                    取消
                  </Button>
                </div>
              </div>
            ) : null}
          </SectionCard>
        </TabsContent>

        {/* ================================ 基础信息（只读） */}
        <TabsContent value="basic">
          <SectionCard title="基础信息（只读）">
            <dl className="grid gap-3 sm:grid-cols-2">
              <div>
                <dt className="text-muted-foreground text-sm">编码</dt>
                <dd>{panorama.partyCode}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground text-sm">名称</dt>
                <dd>{panorama.partyName}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground text-sm">主体状态</dt>
                <dd>{panorama.partyStatus}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground text-sm">合作等级（3.6.1.2 只读）</dt>
                <dd>{panorama.cooperationLevel ?? '未登记'}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground text-sm">联系方式</dt>
                <dd>
                  {panorama.email ?? '—'} · {panorama.phone ?? '—'}
                </dd>
              </div>
              <div>
                <dt className="text-muted-foreground text-sm">采购侧合作状态（6.1.1.1 只读）</dt>
                <dd>{panorama.supplyStatus ?? '未建采购侧档案'}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground text-sm">主供类目</dt>
                <dd>{panorama.categoryName ?? '未归类'}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground text-sm">MOQ</dt>
                <dd>
                  {panorama.moqQuantity ?? '—'} {panorama.moqUnitCode ?? ''}
                </dd>
              </div>
            </dl>
            <p className="text-muted-foreground mt-3 text-sm">
              这一段没有编辑入口是刻意的：编码 / 名称 / 联系方式要回{' '}
              <Link className="underline" href="/partners">
                往来单位档案
              </Link>
              改，采购侧合作状态与主供类目要回{' '}
              <Link className="underline" href="/supply/vendors">
                供应商列表
              </Link>
              改。两处各能编辑一遍的后果是「同一个供应商在两个页面上显示不一样，而没有任何东西会报错」。
            </p>
          </SectionCard>
        </TabsContent>
      </Tabs>

      {panorama.warnings.length > 0 ? (
        <SectionCard title="需要处理">
          <ul className="list-disc pl-5 text-sm">
            {panorama.warnings.map((w) => (
              <li key={w}>{w}</li>
            ))}
          </ul>
        </SectionCard>
      ) : null}

      <p className="text-muted-foreground mt-4 text-xs">{panorama.note}</p>
    </>
  );
}
