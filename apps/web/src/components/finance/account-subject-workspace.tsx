'use client';

import { useCallback, useEffect, useState } from 'react';
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
import type { AccountingBookView } from '../../../../../packages/api-contracts/src/index';
import type {
  FinanceAccountSubjectCapabilities,
  FinanceAccountSubjectDetail,
  FinanceAccountSubjectMappingPage,
  FinanceAccountSubjectMappingRow,
  FinanceAccountSubjectOptions,
  FinanceAccountSubjectPage,
  FinanceAccountSubjectTemplateCatalog,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';
import type { AccountAmountBasisValue } from './account-subject-model';
import {
  accountAmountBasisLabel,
  accountBalanceDirectionLabel,
  accountMappingBalanceText,
  accountSubjectCategoryLabel,
  accountSubjectDetailLabel,
  accountSubjectEventText,
  accountSubjectStatusLabel,
  accountSubjectStatusTone,
  accountTaxTreatmentLabel,
  activateAccountSubjectMapping,
  applyAccountSubjectTemplate,
  createAccountSubject,
  createAccountSubjectMapping,
  deactivateAccountSubjectMapping,
  disableAccountSubject,
  enableAccountSubject,
  fetchAccountSubject,
  fetchAccountSubjectCapabilities,
  fetchAccountSubjectMappings,
  fetchAccountSubjectOptions,
  fetchAccountSubjectTemplates,
  fetchAccountSubjects,
  formatAccountTaxRate,
  accountTemplateApplySummary,
} from './account-subject-model';

/**
 * 12.5.1.1 会计科目。
 *
 * <p>蓝图 12-finance.md：「维护**法人账簿**的会计科目表：科目编码、名称、类别（资产/负债/权益/成本/损益）、
 * 余额方向、是否明细科目、关联税务属性；支持科目映射到业务事件。」输出：「科目表 + 映射规则。」关系：
 * 「上游 = 用户会计体系；下游 = 12.5.2.1 凭证、12.5.2.2 报表。**科目体系是用户自定的**，系统提供模板
 * 但必须允许完全自定义。」
 *
 * <p><b>页面围绕三条纪律组织</b>：
 *
 * <ol>
 *   <li><b>科目编码只读</b>：编码在本租户内唯一且**一经入账不可改**（下游 12.5.2.1 凭证按编码软引用它）
 *       ⇒ 页面只显示编码，**不提供**改编码的输入框。要换编码只能「停用 + 另建」。</li>
 *   <li><b>模板只是导入源</b>：导入结果**逐条报出**哪些编码被跳过（已存在），不假装全导进去了；
 *       导入后每个字段都能改 —— 页面提供改科目入口。</li>
 *   <li><b>借贷齐备由服务端判</b>：页面原样显示服务端给的借方/贷方行数与 {@code balanced}，
 *       **不在前端自己判断**能不能启用；不可执行的动作把**服务端给的阻断原因**显示出来。</li>
 * </ol>
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有科目」。
 */
export function AccountSubjectWorkspace() {
  const [caps, setCaps] = useState<FinanceAccountSubjectCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceAccountSubjectOptions | null>(null);
  const [page, setPage] = useState<FinanceAccountSubjectPage | null>(null);
  const [templates, setTemplates] = useState<FinanceAccountSubjectTemplateCatalog | null>(null);
  const [mappings, setMappings] = useState<FinanceAccountSubjectMappingPage | null>(null);
  const [detail, setDetail] = useState<FinanceAccountSubjectDetail | null>(null);

  const [books, setBooks] = useState<AccountingBookView[]>([]);
  const [bookError, setBookError] = useState('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [category, setCategory] = useState('');
  const [balanceDirection, setBalanceDirection] = useState('');
  const [status, setStatus] = useState('');
  const [detailOnly, setDetailOnly] = useState('');
  const [keyword, setKeyword] = useState('');
  const [currentPage, setCurrentPage] = useState(0);

  // 新增科目
  const [bookId, setBookId] = useState('');
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [newCategory, setNewCategory] = useState('ASSET');
  const [newDirection, setNewDirection] = useState('DEBIT');
  const [isDetail, setIsDetail] = useState(true);
  const [parentCode, setParentCode] = useState('');
  const [taxTreatment, setTaxTreatment] = useState('TAXABLE');
  const [taxRate, setTaxRate] = useState('');
  const [taxNote, setTaxNote] = useState('');

  // 模板导入
  const [templateKey, setTemplateKey] = useState('');

  // 映射规则
  const [eventType, setEventType] = useState('');
  const [eventLabel, setEventLabel] = useState('');
  const [debitCode, setDebitCode] = useState('');
  const [debitBasis, setDebitBasis] = useState<AccountAmountBasisValue>('ITEM_COST');
  const [creditCode, setCreditCode] = useState('');
  const [creditBasis, setCreditBasis] = useState<AccountAmountBasisValue>('ORDER_ITEM_AMOUNT');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const params = {
        category: category || undefined,
        balanceDirection: balanceDirection || undefined,
        status: status || undefined,
        detail: detailOnly === '' ? undefined : detailOnly === 'true',
        keyword: keyword || undefined,
        page: currentPage,
        size: 50,
      };
      const [capsData, optionsData, pageData, templateData, mappingData] = await Promise.all([
        fetchAccountSubjectCapabilities(),
        fetchAccountSubjectOptions(),
        fetchAccountSubjects(params),
        fetchAccountSubjectTemplates(),
        fetchAccountSubjectMappings({ page: 0, size: 100 }),
      ]);
      setCaps(capsData);
      setOptions(optionsData);
      setPage(pageData);
      setTemplates(templateData);
      setMappings(mappingData);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [category, balanceDirection, status, detailOnly, keyword, currentPage]);

  useEffect(() => {
    void load();
  }, [load]);

  // 法人账簿主数据在 identity（17.1.2.2）；跨服务不直连对方库，页面走 web 代理读。
  useEffect(() => {
    let cancelled = false;
    api<AccountingBookView[]>('identity/accounting-books')
      .then((data) => {
        if (cancelled) return;
        setBooks(data);
        setBookError('');
        if (data.length > 0) setBookId((current) => current || data[0]!.id);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        // ⚠️ 读不到就**如实说读不到**，并允许手填账簿 id —— 不拿假账簿顶替。
        setBookError(e instanceof Error ? e.message : '读不到法人账簿清单');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function runCreate() {
    setActionError('');
    setNotice('');
    if (!bookId) {
      setActionError('必须先选一本法人账簿：科目挂在账簿上');
      return;
    }
    const book = books.find((b) => b.id === bookId);
    if (!code.trim()) {
      setActionError('科目编码不能为空');
      return;
    }
    if (!name.trim()) {
      setActionError('科目名称不能为空');
      return;
    }
    const rate = taxRate.trim() === '' ? null : Number(taxRate);
    if (rate !== null && (!Number.isFinite(rate) || rate < 0 || rate > 1)) {
      setActionError('税率必须在 0 到 1 之间（0.13 表示 13%），不能填百分数');
      return;
    }
    try {
      const created = await createAccountSubject({
        accountingBookId: bookId,
        legalEntityId: book?.legalEntityId ?? bookId,
        subjectCode: code.trim(),
        subjectName: name.trim(),
        category: newCategory,
        balanceDirection: newDirection,
        detail: isDetail,
        parentSubjectCode: parentCode.trim() || null,
        taxTreatment,
        taxRate: rate,
        taxNote: taxNote.trim() || null,
      });
      setNotice(`已新建科目 ${created.subjectCode} / ${created.subjectName}`);
      setCode('');
      setName('');
      setParentCode('');
      setTaxRate('');
      setTaxNote('');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '新建科目失败');
    }
  }

  async function runToggleStatus(id: string, subjectCode: string, current: string) {
    setActionError('');
    setNotice('');
    try {
      if (current === 'ACTIVE') {
        await disableAccountSubject(id);
        setNotice(`已停用科目 ${subjectCode}（历史凭证仍能引用它）`);
      } else {
        await enableAccountSubject(id);
        setNotice(`已启用科目 ${subjectCode}`);
      }
      await load();
    } catch (e) {
      // ⚠️ 服务端给的阻断原因原样显示（而不是把按钮灰掉让人猜）。
      setActionError(e instanceof Error ? e.message : '操作失败');
    }
  }

  async function runApplyTemplate() {
    setActionError('');
    setNotice('');
    if (!bookId) {
      setActionError('必须先选一本法人账簿');
      return;
    }
    if (!templateKey) {
      setActionError('请选择要导入的模板');
      return;
    }
    const book = books.find((b) => b.id === bookId);
    try {
      const result = await applyAccountSubjectTemplate({
        accountingBookId: bookId,
        legalEntityId: book?.legalEntityId ?? bookId,
        templateKey,
      });
      setNotice(accountTemplateApplySummary(result));
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '模板导入失败');
    }
  }

  async function runCreateMapping() {
    setActionError('');
    setNotice('');
    if (!bookId) {
      setActionError('必须先选一本法人账簿');
      return;
    }
    if (!eventType.trim()) {
      setActionError('业务事件编码不能为空');
      return;
    }
    if (!debitCode.trim() || !creditCode.trim()) {
      setActionError('映射规则必须给出借方科目与贷方科目');
      return;
    }
    try {
      const created = await createAccountSubjectMapping({
        accountingBookId: bookId,
        eventType: eventType.trim().toUpperCase(),
        eventLabel: eventLabel.trim() || null,
        effectiveFrom: null,
        note: null,
        lines: [
          {
            subjectCode: debitCode.trim(),
            direction: 'DEBIT',
            amountBasis: debitBasis,
            sequence: 1,
            note: '',
          },
          {
            subjectCode: creditCode.trim(),
            direction: 'CREDIT',
            amountBasis: creditBasis,
            sequence: 2,
            note: '',
          },
        ],
      });
      setNotice(`已新建业务事件 ${created.eventType} 的映射规则（草稿，借贷齐备后才能启用）`);
      setEventType('');
      setEventLabel('');
      setDebitCode('');
      setCreditCode('');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '新建映射规则失败');
    }
  }

  async function runToggleMapping(mapping: FinanceAccountSubjectMappingRow) {
    setActionError('');
    setNotice('');
    try {
      if (mapping.status === 'ACTIVE') {
        await deactivateAccountSubjectMapping(mapping.id);
        setNotice(`已停用业务事件 ${mapping.eventType} 的映射规则`);
      } else {
        await activateAccountSubjectMapping(mapping.id);
        setNotice(`已启用业务事件 ${mapping.eventType} 的映射规则`);
      }
      await load();
    } catch (e) {
      // ⚠️ 「借贷不齐备」这类阻断原因由服务端给出，原样显示。
      setActionError(e instanceof Error ? e.message : '操作失败');
    }
  }

  async function runShowDetail(id: string) {
    setActionError('');
    try {
      setDetail(await fetchAccountSubject(id));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '读取科目详情失败');
    }
  }

  const totalPages = page ? Math.max(1, Math.ceil(page.total / Math.max(1, page.size))) : 1;
  const detailCount =
    page?.summaryByCategory.reduce((sum, s) => sum + s.detailCount, 0) ?? 0;
  const groupCount = page?.summaryByCategory.reduce((sum, s) => sum + s.groupCount, 0) ?? 0;
  const mappingCount = mappings?.total ?? 0;

  return (
    <div className="space-y-6">
      <PageHeader
        title="会计科目"
        description="法人账簿的会计科目表：编码 · 名称 · 类别 · 余额方向 · 是否明细 · 税务属性，以及业务事件 → 科目的映射规则"
        actions={
          <Button variant="secondary" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <div className="flex items-center gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm">
          <TriangleAlert className="h-4 w-4" />
          {error}
        </div>
      )}
      {actionError && (
        <Notice tone="danger" title="操作未完成">
          {actionError}
        </Notice>
      )}
      {notice && (
        <Notice tone="info" title="已完成">
          {notice}
        </Notice>
      )}

      {loading && <p className="text-muted-foreground text-sm">加载中…</p>}

      {!loading && !error && (
        <>
          {/* ---------- 能力声明：没交付的事必须说在界面上 ---------- */}
          {caps && caps.capabilities.some((c) => !c.available) && (
            <SectionCard
              title="能力声明"
              description="🔴 标红的这几项**当前不可用** —— 界面不提供它们的入口，也不拿假数据顶替。"
            >
              <ul className="space-y-2 text-sm">
                {caps.capabilities
                  .filter((c) => !c.available)
                  .map((c) => (
                    <li key={`${c.module}-${c.name}`} className="flex items-start gap-2">
                      <Badge tone="danger">不可用</Badge>
                      <span>
                        <b>{c.name}</b>
                        {c.ownerModule ? `（归属模块 ${c.ownerModule}）` : ''}：{c.detail}
                      </span>
                    </li>
                  ))}
              </ul>
            </SectionCard>
          )}

          {/* ---------- 概览 ---------- */}
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard
              label="科目"
              value={page?.total ?? 0}
              unit="个"
              comparisonLabel="按当前筛选条件"
            />
            <StatCard
              label="明细科目"
              value={detailCount}
              unit="个"
              comparisonLabel="可以直接记账的科目"
            />
            <StatCard
              label="汇总科目"
              value={groupCount}
              unit="个"
              comparisonLabel="只能有下级、不能直接记账"
            />
            <StatCard
              label="业务事件映射"
              value={mappingCount}
              unit="条"
              comparisonLabel="事件 → 科目的映射规则"
            />
          </div>

          {/* ---------- 法人账簿 ---------- */}
          <SectionCard
            title="法人账簿"
            description="科目挂在**法人账簿**上（账簿主数据在 identity 17.1.2.2，跨服务不直连对方库）。"
          >
            {bookError ? (
              <Notice tone="warning" title="读不到法人账簿清单">
                {bookError} —— 可以直接在下面手填账簿 ID 继续操作，页面不会拿假账簿顶替。
              </Notice>
            ) : null}
            <div className="grid gap-3 md:grid-cols-2">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={bookId}
                onChange={(e) => setBookId(e.target.value)}
              >
                <option value="">（选择法人账簿）</option>
                {books.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.legalEntityName} / {b.name}（{b.code} · {b.bookType} · {b.baseCurrency}）
                  </option>
                ))}
              </select>
              <Input
                value={bookId}
                onChange={(e) => setBookId(e.target.value.trim())}
                placeholder="或手填账簿 ID"
              />
            </div>
          </SectionCard>

          {/* ---------- 新增科目 ---------- */}
          <SectionCard
            title="新增科目"
            description="⚠️ **科目编码一经入账不可修改**（下游 12.5.2.1 凭证按编码软引用它）—— 想换编码只能停用后另建。"
          >
            <div className="space-y-3">
              <div className="grid gap-3 md:grid-cols-4">
                <Input
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  placeholder="科目编码（如 1122.01）"
                />
                <Input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="科目名称"
                />
                <select
                  className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                  value={newCategory}
                  onChange={(e) => setNewCategory(e.target.value)}
                >
                  {['ASSET', 'LIABILITY', 'EQUITY', 'COST', 'PROFIT_LOSS'].map((c) => (
                    <option key={c} value={c}>
                      {accountSubjectCategoryLabel(c)}
                    </option>
                  ))}
                </select>
                <select
                  className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                  value={newDirection}
                  onChange={(e) => setNewDirection(e.target.value)}
                >
                  {['DEBIT', 'CREDIT'].map((d) => (
                    <option key={d} value={d}>
                      {accountBalanceDirectionLabel(d)}
                    </option>
                  ))}
                </select>
              </div>
              <div className="grid gap-3 md:grid-cols-4">
                <Input
                  value={parentCode}
                  onChange={(e) => setParentCode(e.target.value)}
                  placeholder="上级科目编码（留空 = 顶级科目）"
                />
                <select
                  className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                  value={taxTreatment}
                  onChange={(e) => setTaxTreatment(e.target.value)}
                >
                  {['TAXABLE', 'EXEMPT', 'ZERO_RATED', 'NON_TAXABLE', 'REVERSE_CHARGE'].map((t) => (
                    <option key={t} value={t}>
                      {accountTaxTreatmentLabel(t)}
                    </option>
                  ))}
                </select>
                <Input
                  value={taxRate}
                  onChange={(e) => setTaxRate(e.target.value)}
                  placeholder="税率（0.13 = 13%，留空 = 不固定）"
                />
                <Input
                  value={taxNote}
                  onChange={(e) => setTaxNote(e.target.value)}
                  placeholder="税务属性说明"
                />
              </div>
              <label className="text-copy-13 flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={isDetail}
                  onChange={(e) => setIsDetail(e.target.checked)}
                />
                明细科目（不勾选 = 汇总科目，只能有下级、不能被凭证直接记账）
              </label>
              <Button onClick={() => void runCreate()}>新增科目</Button>
            </div>
          </SectionCard>

          {/* ---------- 科目表 ---------- */}
          <SectionCard
            title="科目表"
            description="真分页 + 真筛选；分类汇总覆盖**整个筛选结果集**，不是当前页。"
          >
            <div className="space-y-4">
              <div className="grid gap-3 md:grid-cols-5">
                <select
                  className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                  value={category}
                  onChange={(e) => {
                    setCategory(e.target.value);
                    setCurrentPage(0);
                  }}
                >
                  <option value="">全部类别</option>
                  {(options?.categories ?? []).map((c) => (
                    <option key={c} value={c}>
                      {accountSubjectCategoryLabel(c)}
                    </option>
                  ))}
                </select>
                <select
                  className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                  value={balanceDirection}
                  onChange={(e) => {
                    setBalanceDirection(e.target.value);
                    setCurrentPage(0);
                  }}
                >
                  <option value="">全部余额方向</option>
                  {(options?.balanceDirections ?? []).map((d) => (
                    <option key={d} value={d}>
                      {accountBalanceDirectionLabel(d)}
                    </option>
                  ))}
                </select>
                <select
                  className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                  value={status}
                  onChange={(e) => {
                    setStatus(e.target.value);
                    setCurrentPage(0);
                  }}
                >
                  <option value="">全部状态</option>
                  {(options?.statuses ?? []).map((s) => (
                    <option key={s} value={s}>
                      {accountSubjectStatusLabel(s)}
                    </option>
                  ))}
                </select>
                <select
                  className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                  value={detailOnly}
                  onChange={(e) => {
                    setDetailOnly(e.target.value);
                    setCurrentPage(0);
                  }}
                >
                  <option value="">明细与汇总都要</option>
                  <option value="true">只看明细科目</option>
                  <option value="false">只看汇总科目</option>
                </select>
                <Input
                  value={keyword}
                  onChange={(e) => {
                    setKeyword(e.target.value);
                    setCurrentPage(0);
                  }}
                  placeholder="按编码 / 名称搜索"
                />
              </div>

              {page && page.items.length === 0 ? (
                <EmptyState
                  title="没有匹配的科目"
                  description={
                    page.total === 0
                      ? '当前还没有科目 —— 先从模板导入一套，或者直接新增。科目体系由用户自定，系统不强制标准科目。'
                      : '当前筛选条件下没有科目，换个条件试试。'
                  }
                />
              ) : (
                <div className="space-y-3">
                  <table className="text-copy-13 w-full text-left">
                    <thead className="text-muted-foreground">
                      <tr>
                        <th className="py-2">编码</th>
                        <th className="py-2">名称</th>
                        <th className="py-2">类别</th>
                        <th className="py-2">余额方向</th>
                        <th className="py-2">科目性质</th>
                        <th className="py-2">上级</th>
                        <th className="py-2">层级</th>
                        <th className="py-2">税务属性</th>
                        <th className="py-2">税率</th>
                        <th className="py-2">状态</th>
                        <th className="py-2">被引用</th>
                        <th className="py-2">操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {page?.items.map((s) => (
                        <tr key={s.id} className="border-border border-t">
                          <td className="py-2 font-medium">{s.subjectCode}</td>
                          <td className="py-2">{s.subjectName}</td>
                          <td className="py-2">{s.categoryLabel}</td>
                          <td className="py-2">{s.balanceDirectionLabel}</td>
                          <td className="py-2">{accountSubjectDetailLabel(s.detail)}</td>
                          <td className="py-2">{s.parentSubjectCode ?? '—'}</td>
                          <td className="py-2">{s.level}</td>
                          <td className="py-2">{s.taxTreatmentLabel}</td>
                          <td className="py-2">{formatAccountTaxRate(s.taxRate)}</td>
                          <td className="py-2">
                            <Badge tone={accountSubjectStatusTone(s.status)}>
                              {s.statusLabel}
                            </Badge>
                          </td>
                          <td className="py-2">{s.mappingReferenceCount}</td>
                          <td className="py-2">
                            <div className="flex flex-wrap items-center gap-2">
                              <Button
                                variant="secondary"
                                onClick={() => void runShowDetail(s.id)}
                              >
                                详情
                              </Button>
                              <Button
                                variant="secondary"
                                onClick={() =>
                                  void runToggleStatus(s.id, s.subjectCode, s.status)
                                }
                              >
                                {s.status === 'ACTIVE' ? '停用' : '启用'}
                              </Button>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  <div className="flex items-center justify-between text-sm">
                    <span className="text-muted-foreground">
                      共 {page?.total ?? 0} 个科目 · 第 {currentPage + 1} / {totalPages} 页
                    </span>
                    <div className="flex items-center gap-2">
                      <Button
                        variant="secondary"
                        disabled={currentPage === 0}
                        onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
                      >
                        上一页
                      </Button>
                      <Button
                        variant="secondary"
                        disabled={currentPage + 1 >= totalPages}
                        onClick={() => setCurrentPage((p) => p + 1)}
                      >
                        下一页
                      </Button>
                    </div>
                  </div>
                </div>
              )}

              {page && page.summaryByCategory.length > 0 && (
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">类别</th>
                      <th className="py-2">科目数</th>
                      <th className="py-2">明细科目</th>
                      <th className="py-2">汇总科目</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.summaryByCategory.map((s) => (
                      <tr key={s.category} className="border-border border-t">
                        <td className="py-2 font-medium">{s.categoryLabel}</td>
                        <td className="py-2">{s.count}</td>
                        <td className="py-2">{s.detailCount}</td>
                        <td className="py-2">{s.groupCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </SectionCard>

          {/* ---------- 系统模板 ---------- */}
          <SectionCard
            title="系统科目模板"
            description="模板**只是导入源**：导入后每个字段都能改，也能停用和另建科目 —— 系统不强制「标准科目必须存在」。"
            extra={
              <div className="flex items-center gap-2">
                <select
                  className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                  value={templateKey}
                  onChange={(e) => setTemplateKey(e.target.value)}
                >
                  <option value="">（选择模板）</option>
                  {(templates?.templates ?? []).map((t) => (
                    <option key={t.templateKey} value={t.templateKey}>
                      {t.templateName}（{t.subjectCount} 个科目）
                    </option>
                  ))}
                </select>
                <Button onClick={() => void runApplyTemplate()}>导入到当前账簿</Button>
              </div>
            }
          >
            {!templates || templates.subjects.length === 0 ? (
              <EmptyState
                title="还没有科目模板"
                description="模板由系统提供（迁移播种，运行期只读）。"
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">编码</th>
                    <th className="py-2">名称</th>
                    <th className="py-2">类别</th>
                    <th className="py-2">余额方向</th>
                    <th className="py-2">科目性质</th>
                    <th className="py-2">上级</th>
                    <th className="py-2">税务属性</th>
                    <th className="py-2">税率</th>
                  </tr>
                </thead>
                <tbody>
                  {templates.subjects.map((t) => (
                    <tr key={`${t.templateKey}-${t.subjectCode}`} className="border-border border-t">
                      <td className="py-2 font-medium">{t.subjectCode}</td>
                      <td className="py-2">{t.subjectName}</td>
                      <td className="py-2">{t.categoryLabel}</td>
                      <td className="py-2">{t.balanceDirectionLabel}</td>
                      <td className="py-2">{accountSubjectDetailLabel(t.detail)}</td>
                      <td className="py-2">{t.parentSubjectCode ?? '—'}</td>
                      <td className="py-2">{t.taxTreatmentLabel}</td>
                      <td className="py-2">{formatAccountTaxRate(t.taxRate)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          {/* ---------- 业务事件映射 ---------- */}
          <SectionCard
            title="业务事件 → 科目 的映射规则"
            description="蓝图输入：「业务事件映射（如发货 → 收入 + 结转成本）」。⚠️ 事件编码由用户自定义；模板**不固定金额**，金额相等由凭证侧（12.5.2.1）实算。"
          >
            <div className="space-y-4">
              <div className="grid gap-3 md:grid-cols-2">
                <Input
                  value={eventType}
                  onChange={(e) => setEventType(e.target.value)}
                  placeholder="业务事件编码（如 ORDER_SHIPPED）"
                />
                <Input
                  value={eventLabel}
                  onChange={(e) => setEventLabel(e.target.value)}
                  placeholder="事件名称（如：订单发货）"
                />
              </div>
              <div className="grid gap-3 md:grid-cols-4">
                <Input
                  value={debitCode}
                  onChange={(e) => setDebitCode(e.target.value)}
                  placeholder="借方科目编码（如 6401）"
                />
                <select
                  className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                  value={debitBasis}
                  onChange={(e) => setDebitBasis(e.target.value as AccountAmountBasisValue)}
                >
                  {[
                    'ITEM_COST',
                    'ORDER_ITEM_AMOUNT',
                    'SHIPPING_AMOUNT',
                    'TAX_AMOUNT',
                    'PLATFORM_FEE',
                    'REFUND_AMOUNT',
                    'ADJUSTMENT_AMOUNT',
                    'OTHER',
                  ].map((b) => (
                    <option key={b} value={b}>
                      {accountAmountBasisLabel(b)}
                    </option>
                  ))}
                </select>
                <Input
                  value={creditCode}
                  onChange={(e) => setCreditCode(e.target.value)}
                  placeholder="贷方科目编码（如 6001）"
                />
                <select
                  className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                  value={creditBasis}
                  onChange={(e) => setCreditBasis(e.target.value as AccountAmountBasisValue)}
                >
                  {[
                    'ORDER_ITEM_AMOUNT',
                    'ITEM_COST',
                    'SHIPPING_AMOUNT',
                    'TAX_AMOUNT',
                    'PLATFORM_FEE',
                    'REFUND_AMOUNT',
                    'ADJUSTMENT_AMOUNT',
                    'OTHER',
                  ].map((b) => (
                    <option key={b} value={b}>
                      {accountAmountBasisLabel(b)}
                    </option>
                  ))}
                </select>
              </div>
              <Button onClick={() => void runCreateMapping()}>新建映射规则（草稿）</Button>

              {!mappings || mappings.items.length === 0 ? (
                <EmptyState
                  title="还没有映射规则"
                  description="给业务事件配好借贷两行，规则才能启用 —— 启用后凭证侧就能按它取科目。"
                />
              ) : (
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">业务事件</th>
                      <th className="py-2">名称</th>
                      <th className="py-2">分录行</th>
                      <th className="py-2">借贷</th>
                      <th className="py-2">状态</th>
                      <th className="py-2">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {mappings.items.map((m) => (
                      <tr key={m.id} className="border-border border-t">
                        <td className="py-2 font-medium">{m.eventType}</td>
                        <td className="py-2">{m.eventLabel || '—'}</td>
                        <td className="py-2">
                          {m.lines
                            .map(
                              (l) =>
                                `${l.directionLabel} ${l.subjectCode}（${l.amountBasisLabel}）`,
                            )
                            .join('；') || '—'}
                        </td>
                        <td className="py-2">
                          {accountMappingBalanceText(
                            m.debitLineCount,
                            m.creditLineCount,
                            m.balanced,
                          )}
                        </td>
                        <td className="py-2">
                          <Badge tone={m.status === 'ACTIVE' ? 'success' : 'neutral'}>
                            {m.statusLabel}
                          </Badge>
                        </td>
                        <td className="py-2">
                          <Button
                            variant="secondary"
                            onClick={() => void runToggleMapping(m)}
                          >
                            {m.status === 'ACTIVE' ? '停用' : '启用'}
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </SectionCard>

          {/* ---------- 科目详情（变更留痕 + 被引用的映射） ---------- */}
          {detail && (
            <SectionCard
              title={`科目详情 · ${detail.subject.subjectCode} / ${detail.subject.subjectName}`}
              description="变更留痕是**追加式**的：改了什么有独立出处，事件流不能改。"
              extra={
                <Button variant="secondary" onClick={() => setDetail(null)}>
                  关闭
                </Button>
              }
            >
              <div className="space-y-3">
                <div className="text-copy-13 text-muted-foreground grid gap-1 md:grid-cols-3">
                  <span>类别：{detail.subject.categoryLabel}</span>
                  <span>余额方向：{detail.subject.balanceDirectionLabel}</span>
                  <span>科目性质：{accountSubjectDetailLabel(detail.subject.detail)}</span>
                  <span>上级：{detail.subject.parentSubjectCode ?? '—'}</span>
                  <span>层级：{detail.subject.level}</span>
                  <span>税务属性：{detail.subject.taxTreatmentLabel}</span>
                  <span>税率：{formatAccountTaxRate(detail.subject.taxRate)}</span>
                  <span>状态：{detail.subject.statusLabel}</span>
                  <span>来源：{detail.subject.source === 'TEMPLATE' ? '模板导入' : '用户自定义'}</span>
                </div>
                <h4 className="text-h-14">变更留痕</h4>
                {detail.events.length === 0 ? (
                  <EmptyState title="没有变更留痕" description="这个科目还没有被改过。" />
                ) : (
                  <ul className="space-y-1 text-sm">
                    {detail.events.map((e) => (
                      <li key={e.id} className="border-border border-t py-1">
                        {e.occurredAt} · {accountSubjectEventText(e)}
                      </li>
                    ))}
                  </ul>
                )}
                <h4 className="text-h-14">被这些映射规则引用</h4>
                {detail.mappingReferences.length === 0 ? (
                  <EmptyState title="还没有映射规则引用它" description="没有规则用到这个科目。" />
                ) : (
                  <ul className="space-y-1 text-sm">
                    {detail.mappingReferences.map((r) => (
                      <li key={`${r.mappingId}-${r.direction}-${r.amountBasis}`} className="py-1">
                        {r.eventType}（{r.eventLabel || '未命名'}）· {r.direction} ·{' '}
                        {accountAmountBasisLabel(r.amountBasis)} · {r.status}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}
