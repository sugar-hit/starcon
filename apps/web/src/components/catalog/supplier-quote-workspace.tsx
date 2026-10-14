'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import { Plus, RefreshCw, Save, Trash2, TriangleAlert, Trash } from 'lucide-react';
import type {
  SupplierQuote,
  SupplierQuoteOptions,
  SupplierQuoteOverview,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  PRIORITY_HINT,
  STATUS_LABELS,
  createQuote,
  deleteQuote,
  emptyQuoteForm,
  emptyQuoteListQuery,
  fetchQuoteOptions,
  fetchQuoteOverview,
  fetchQuotes,
  quoteErrorText,
  quoteFormProblem,
  selectionStateLabel,
  tierLabel,
  toQuoteForm,
  toQuoteInput,
  updateQuote,
  type QuoteForm,
  type QuoteListQuery,
} from './supplier-quote-model';

/**
 * 3.6.2.1 供应商报价工作区。
 *
 * <p>页面上最重要的一件事：**歧义必须看得见，而且不能被界面"顺手替用户选掉"**。
 *
 * <ul>
 *   <li>台账里每条报价都带选择状态徽标（默认采用 / 需人工选择 / 备选 / 不参与有效报价），
 *       状态由后端逐条算好后下发 —— 界面**不**自己挑一条当成默认。
 *   <li>顶部的「需人工选择」是治理数字：它正常应为 0。非 0 时它就是一个行动清单，
 *       点进去能筛出全部并列的报价。
 *   <li>编辑面板里，优先级那一栏带着「1 = 首选，越小越优先」的解释；
 *       歧义的处置建议是「把要用作默认的那条优先级调小一位」，而不是删掉另一条
 *       —— 两条报价都还可能有效（不同币种、不同交期），删一条等于丢信息。
 * </ul>
 *
 * <p>加载态 / 空态 / 错误态三者分开渲染：台账为空是正常状态（还没录报价），
 * 与"加载失败"不能共用一个界面 —— 后者必须让人一眼看出要重试。
 */
export function SupplierQuoteLedger({ writable }: { writable: boolean }) {
  const [overview, setOverview] = useState<SupplierQuoteOverview | null>(null);
  const [options, setOptions] = useState<SupplierQuoteOptions | null>(null);
  const [rows, setRows] = useState<SupplierQuote[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<QuoteListQuery>(emptyQuoteListQuery());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<QuoteForm | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [ov, page, opts] = await Promise.all([
        fetchQuoteOverview(),
        fetchQuotes(query),
        fetchQuoteOptions(),
      ]);
      setOverview(ov);
      setRows(page.items);
      setTotal(page.total);
      setOptions(opts);
    } catch (e) {
      setError(quoteErrorText(e));
    } finally {
      setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    void load();
  }, [load]);

  const patch = (next: Partial<QuoteListQuery>) =>
    setQuery((current) => ({ ...current, ...next, page: 0 }));

  const pages = Math.max(1, Math.ceil(total / query.size));

  const remove = async (quote: SupplierQuote) => {
    setError(null);
    setNotice(null);
    try {
      await deleteQuote(quote.id, quote.version);
      setNotice(`已删除 ${quote.supplierName} 对 ${quote.skuCode} 的报价。`);
      await load();
    } catch (e) {
      setError(quoteErrorText(e));
    }
  };

  return (
    <SectionCard
      title="供应商报价台账（3.6.2.1）"
      description="供应商对 SKU 的报价：货号、采购单位、币种、起订量、交期、有效期、优先级与阶梯价。有效报价默认取优先级最高且在有效期内的版本。"
      extra={
        <div className="flex gap-2">
          <Button size="sm" variant="ghost" disabled={loading} onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
          {writable && (
            <Button size="sm" onClick={() => setEditing(emptyQuoteForm())}>
              <Plus className="h-4 w-4" />
              新建报价
            </Button>
          )}
        </div>
      }
    >
      {error ? (
        <div className="space-y-3">
          <p className="text-destructive text-sm">加载失败：{error}</p>
          <Button size="sm" variant="ghost" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            重试
          </Button>
        </div>
      ) : (
        <div className="space-y-4">
          {overview && (
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <StatCard label="报价总数" value={overview.totalQuotes} unit="条" />
              <StatCard
                label="需人工选择"
                value={overview.ambiguousQuotes}
                unit="条"
                polarity={overview.ambiguousQuotes > 0 ? 'negative' : 'positive'}
              />
              <StatCard label="已停用" value={overview.disabledQuotes} unit="条" />
              <StatCard label="30 天内到期" value={overview.expiringSoonQuotes} unit="条" />
            </div>
          )}

          {overview && overview.ambiguousQuotes > 0 && (
            <div className="border-warning text-warning flex items-start gap-2 rounded-md border px-3 py-2 text-sm">
              <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
              <div>
                <p>
                  有 {overview.ambiguousQuotes} 条报价在同一 SKU、同一供应商下优先级并列。
                  系统<b>不会</b>替你随机挑一条 —— 请把要用作默认的那条优先级调小一位。
                </p>
                <Button
                  size="sm"
                  variant="ghost"
                  className="mt-1"
                  onClick={() => patch({ ambiguousOnly: !query.ambiguousOnly })}
                >
                  {query.ambiguousOnly ? '显示全部报价' : '只看这些需人工选择的'}
                </Button>
              </div>
            </div>
          )}

          {notice && <p className="text-success text-sm">{notice}</p>}

          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Field label="关键词" hint="货号 / 供应商 / SKU">
              <input
                className={control}
                value={query.keyword}
                placeholder="如 PN-001 或 星尘"
                onChange={(e) => patch({ keyword: e.target.value })}
              />
            </Field>
            <Field label="SKU">
              <input
                className={control}
                value={query.skuId}
                placeholder="SKU ID"
                onChange={(e) => patch({ skuId: e.target.value })}
              />
            </Field>
            <Field label="币种">
              <input
                className={control}
                value={query.currency}
                placeholder="USD"
                onChange={(e) => patch({ currency: e.target.value })}
              />
            </Field>
            <Field label="状态">
              <select
                className={control}
                value={query.status}
                onChange={(e) => patch({ status: e.target.value })}
              >
                <option value="">全部</option>
                {options?.statuses.map((option) => (
                  <option key={option.code} value={option.code}>
                    {option.label}
                  </option>
                ))}
              </select>
            </Field>
          </div>

          <div className="flex flex-wrap items-center gap-4 text-sm">
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={query.effectiveOnly}
                onChange={(e) => patch({ effectiveOnly: e.target.checked })}
              />
              只看有效报价
            </label>
            <label className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={query.ambiguousOnly}
                onChange={(e) => patch({ ambiguousOnly: e.target.checked })}
              />
              只看需人工选择
            </label>
            <Field label="按哪一天判定有效">
              <input
                type="date"
                className={control}
                value={query.at}
                onChange={(e) => patch({ at: e.target.value })}
              />
            </Field>
          </div>

          {loading ? (
            <p className="text-muted-foreground text-sm">加载中…</p>
          ) : rows.length === 0 ? (
            <EmptyState
              icon={<TriangleAlert className="h-6 w-6" />}
              title={query.keyword || query.skuId || query.ambiguousOnly ? '没有匹配的报价' : '还没有录过报价'}
              description={
                query.keyword || query.skuId || query.ambiguousOnly
                  ? '换个关键词或清掉筛选再试。'
                  : '录一条报价后，06 采购单才能按供应商与 SKU 带出价格。'
              }
            />
          ) : (
            <div className="space-y-3">
              <div className="overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <thead className="text-muted-foreground border-b text-xs">
                    <tr>
                      <th className="py-2 pr-3 font-medium">供应商 / SKU</th>
                      <th className="py-2 pr-3 font-medium">阶梯价</th>
                      <th className="py-2 pr-3 font-medium">起订 / 交期</th>
                      <th className="py-2 pr-3 font-medium">有效期</th>
                      <th className="py-2 pr-3 font-medium">优先级</th>
                      <th className="py-2 pr-3 font-medium">选择状态</th>
                      <th className="py-2 font-medium">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((quote) => (
                      <tr key={quote.id} className="border-b last:border-0">
                        <td className="py-2 pr-3">
                          <div className="font-medium">
                            {quote.supplierName} · {quote.skuCode}
                          </div>
                          <div className="text-muted-foreground text-xs">
                            货号 {quote.supplierSkuCode} · {quote.currency}
                            {quote.status === 'DISABLED' && ` · ${STATUS_LABELS.DISABLED}`}
                          </div>
                        </td>
                        <td className="py-2 pr-3">
                          <div className="space-y-0.5">
                            {quote.tiers.length === 0 ? (
                              <span className="text-destructive text-xs">未设阶梯价</span>
                            ) : (
                              quote.tiers.map((tier, index) => (
                                <div key={index} className="text-xs">
                                  {tierLabel(quote.tiers, index)}
                                </div>
                              ))
                            )}
                          </div>
                        </td>
                        <td className="py-2 pr-3 text-xs">
                          <div>{quote.moqLabel}</div>
                          <div className="text-muted-foreground">{quote.leadTimeLabel}</div>
                        </td>
                        <td className="py-2 pr-3 text-xs">{quote.validityLabel}</td>
                        <td className="py-2 pr-3 text-xs">{quote.priorityLabel}</td>
                        <td className="py-2 pr-3">
                          <Badge
                            tone={
                              quote.selectionState === 'SELECTED'
                                ? 'success'
                                : quote.selectionState === 'AMBIGUOUS'
                                  ? 'warning'
                                  : 'neutral'
                            }
                          >
                            {selectionStateLabel(quote.selectionState)}
                          </Badge>
                        </td>
                        <td className="py-2">
                          {writable && (
                            <div className="flex gap-1">
                              <Button
                                size="sm"
                                variant="ghost"
                                onClick={() => setEditing(toQuoteForm(quote))}
                              >
                                编辑
                              </Button>
                              <Button
                                size="sm"
                                variant="ghost"
                                onClick={() => void remove(quote)}
                              >
                                <Trash2 className="h-4 w-4" />
                              </Button>
                            </div>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {query.ambiguousOnly && (
                <p className="text-muted-foreground text-xs">
                  「只看需人工选择」是在装配之后按选择状态过滤的（选择状态是算出来的，不是库里的列），
                  当前页条数可能少于每页 {query.size} 条 —— 翻页时请以总数 {total} 为准。
                </p>
              )}

              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">共 {total} 条</span>
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={query.page === 0}
                    onClick={() => setQuery((c) => ({ ...c, page: c.page - 1 }))}
                  >
                    上一页
                  </Button>
                  <span className="text-muted-foreground self-center">
                    {query.page + 1} / {pages}
                  </span>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={query.page + 1 >= pages}
                    onClick={() => setQuery((c) => ({ ...c, page: c.page + 1 }))}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            </div>
          )}
        </div>
      )}

      {editing && (
        <QuoteEditor
          form={editing}
          onChange={setEditing}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null);
            await load();
          }}
        />
      )}
    </SectionCard>
  );
}

/**
 * 报价编辑面板（新建与修改共用）。
 *
 * <p>阶梯可以增删档：每一档是「起订量 + 单价」，
 * 提交前按起订量升序排（后端写入前也排一次，这里排是为了让用户看到的顺序
 * 与提交进去的顺序一致）。
 */
function QuoteEditor({
  form,
  onChange,
  onClose,
  onSaved,
}: {
  form: QuoteForm;
  onChange: (next: QuoteForm) => void;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const problem = useMemo(() => quoteFormProblem(form), [form]);
  const isNew = form.version === 0;

  const patchTier = (index: number, next: Partial<QuoteForm['tiers'][number]>) =>
    onChange({
      ...form,
      tiers: form.tiers.map((tier, i) => (i === index ? { ...tier, ...next } : tier)),
    });

  const addTier = () =>
    onChange({
      ...form,
      tiers: [
        ...form.tiers,
        {
          minQuantity: String(
            form.tiers.reduce((max, tier) => Math.max(max, Number(tier.minQuantity) || 0), 0) + 100,
          ),
          unitPrice: '',
          note: '',
        },
      ],
    });

  const removeTier = (index: number) =>
    onChange({ ...form, tiers: form.tiers.filter((_, i) => i !== index) });

  const save = async () => {
    if (problem) return;
    setSaving(true);
    setError(null);
    try {
      const input = toQuoteInput(form);
      if (isNew) await createQuote(input);
      else await updateQuote(form.id, input);
      await onSaved();
    } catch (e) {
      setError(quoteErrorText(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <SectionCard title={isNew ? '新建报价' : '编辑报价'} description="阶梯价按起订量升序；提交前会自动排序。">
      <div className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="供应商 ID" hint="往来单位（3.6.1.1）里被指派了供应商身份的主体">
            <input
              className={control}
              value={form.supplierId}
              onChange={(e) => onChange({ ...form, supplierId: e.target.value })}
            />
          </Field>
          <Field label="SKU ID" hint="这条报价针对的 SKU">
            <input
              className={control}
              value={form.skuId}
              onChange={(e) => onChange({ ...form, skuId: e.target.value })}
            />
          </Field>
          <Field label="供应商货号" hint="供应商那边的编号；06 采购下给对方时靠它对账">
            <input
              className={control}
              value={form.supplierSkuCode}
              onChange={(e) => onChange({ ...form, supplierSkuCode: e.target.value })}
            />
          </Field>
          <Field label="采购单位" hint="必须是 3.3.3 已登记的计量单位">
            <input
              className={control}
              value={form.purchaseUnitCode}
              onChange={(e) => onChange({ ...form, purchaseUnitCode: e.target.value })}
            />
          </Field>
          <Field label="币种" hint="ISO 4217 三位码，如 USD / EUR / CNY">
            <input
              className={control}
              value={form.currency}
              onChange={(e) => onChange({ ...form, currency: e.target.value })}
            />
          </Field>
          <Field label="起订量" hint="SKU 级门槛，必填且大于 0（与供应商档案的 MOQ 口径不同）">
            <input
              className={control}
              value={form.moqQuantity}
              onChange={(e) => onChange({ ...form, moqQuantity: e.target.value })}
            />
          </Field>
          <Field label="交期（天）" hint="0 = 当日发货">
            <input
              className={control}
              value={form.leadTimeDays}
              onChange={(e) => onChange({ ...form, leadTimeDays: e.target.value })}
            />
          </Field>
          <Field label="优先级" hint={PRIORITY_HINT}>
            <input
              className={control}
              value={form.priority}
              onChange={(e) => onChange({ ...form, priority: e.target.value })}
            />
          </Field>
          <Field label="生效日期" hint="必填。结束日留空 = 长期有效">
            <input
              type="date"
              className={control}
              value={form.validFrom}
              onChange={(e) => onChange({ ...form, validFrom: e.target.value })}
            />
          </Field>
          <Field label="失效日期" hint="闭区间：这一天当天仍然有效。留空 = 长期有效">
            <input
              type="date"
              className={control}
              value={form.validTo}
              onChange={(e) => onChange({ ...form, validTo: e.target.value })}
            />
          </Field>
          <Field label="状态">
            <select
              className={control}
              value={form.status}
              onChange={(e) => onChange({ ...form, status: e.target.value })}
            >
              <option value="ACTIVE">启用中</option>
              <option value="DISABLED">已停用</option>
            </select>
          </Field>
        </div>

        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-sm font-medium">阶梯价</span>
            <Button size="sm" variant="ghost" onClick={addTier}>
              <Plus className="h-4 w-4" />
              加一档
            </Button>
          </div>
          {form.tiers.map((tier, index) => (
            <div key={index} className="grid grid-cols-12 items-end gap-2">
              <div className="col-span-4">
                <Field label={`第 ${index + 1} 档起订量`}>
                  <input
                    className={control}
                    value={tier.minQuantity}
                    onChange={(e) => patchTier(index, { minQuantity: e.target.value })}
                  />
                </Field>
              </div>
              <div className="col-span-4">
                <Field label="单价">
                  <input
                    className={control}
                    value={tier.unitPrice}
                    onChange={(e) => patchTier(index, { unitPrice: e.target.value })}
                  />
                </Field>
              </div>
              <div className="col-span-3">
                <Field label="备注">
                  <input
                    className={control}
                    value={tier.note}
                    onChange={(e) => patchTier(index, { note: e.target.value })}
                  />
                </Field>
              </div>
              <div className="col-span-1 flex justify-end pb-2">
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={form.tiers.length <= 1}
                  onClick={() => removeTier(index)}
                >
                  <Trash className="h-4 w-4" />
                </Button>
              </div>
            </div>
          ))}
          <p className="text-muted-foreground text-xs">
            阶梯是<b>开口</b>的：最后一档没有上界，买超过它仍按最后一档计价。
            数量低于最低档起订量时没有适用价，系统不会替你退回高档价。
          </p>
        </div>

        <Field label="备注">
          <textarea
            className={control}
            rows={2}
            value={form.note}
            onChange={(e) => onChange({ ...form, note: e.target.value })}
          />
        </Field>

        {problem && <p className="text-destructive text-sm">{problem}</p>}
        {error && <p className="text-destructive text-sm">{error}</p>}

        <div className="flex gap-2">
          <Button disabled={!!problem || saving} onClick={() => void save()}>
            <Save className="h-4 w-4" />
            {saving ? '保存中…' : '保存'}
          </Button>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
        </div>
      </div>
    </SectionCard>
  );
}

