'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { Plus, RefreshCw, Search } from 'lucide-react';
import type {
  AccountingBookView,
  LegalEntityView,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '@/lib/api';
import { useIdentity } from '../iam/identity-shell';
import {
  MONTH_LABELS,
  accountingRuleSummary,
  baseCurrencySummary,
  bookTypeLabel,
  firstPeriodAligned,
  firstPeriodStart,
  fiscalYearLabel,
  periodSettingSummary,
} from './accounting-book-model';

/**
 * 17.1.2.2 本位币与账簿。
 *
 * 账簿挂在**法人**上：先选法人，再配这本账的本位币与记账规则。本位币与法人档案里
 * 那个币种不一致时这里会显式示警——本位币归法人，租户默认币种只是展示默认，
 * 两个答案会让 12 财务总账无从起算。
 *
 * 期间边界按 UTC 日历定义，与后端一致，所以这里展示的期界在任何时区打开都相同。
 */
export function AccountingBookWorkspace() {
  const { session } = useIdentity();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [search, setSearch] = useState('');
  const [entityFilter, setEntityFilter] = useState('ALL');
  const [entities, setEntities] = useState<LegalEntityView[]>([]);
  const [books, setBooks] = useState<AccountingBookView[]>([]);
  const [editing, setEditing] = useState<AccountingBookView | 'new' | null>(null);

  const can = useCallback(
    (permission: string) => session.permissions.includes(permission),
    [session.permissions],
  );
  const readable = can('identity.accounting_book.read');

  const load = useCallback(async () => {
    setError('');
    setNotice('');
    if (!readable) {
      // 没有读权限就不请求：空数组会被渲染成「还没有账簿」，那是骗人。
      setReady(true);
      return;
    }
    try {
      // 两个列表同口径：没有法人就没有账簿，法人加载失败时不该显示一个空账簿页。
      const [entityList, bookList] = await Promise.all([
        api<LegalEntityView[]>('identity/legal-entities'),
        api<AccountingBookView[]>('identity/accounting-books'),
      ]);
      setEntities(entityList);
      setBooks(bookList);
    } catch (e) {
      setError(e instanceof Error ? e.message : '请求失败，请重试');
    } finally {
      setReady(true);
    }
  }, [readable]);

  useEffect(() => {
    void load();
  }, [load]);

  // Hook 必须排在早退之前：加载中 / 无权限时 return 之后再调 Hook，
  // 同一个组件在两次渲染间 Hook 数量会变，React 会直接抛错。
  // 总账是每个法人的记账主体，没有账簿的法人单独提示，而不是混在空列表里。
  const entitiesWithoutBook = useMemo(() => {
    const covered = new Set(books.map((b) => b.legalEntityId));
    return entities.filter((e) => e.status === 'ACTIVE' && !covered.has(e.id));
  }, [entities, books]);

  async function remove(book: AccountingBookView) {
    if (!window.confirm(`删除账簿「${book.name}」？此操作不可撤销。`)) return;
    setBusy(true);
    try {
      await api(`identity/accounting-books/${book.id}`, 'DELETE');
      setNotice('账簿已删除');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载会计账簿…
      </p>
    );

  if (!readable)
    return (
      <p className="rounded-lg border p-6 text-sm">
        当前角色没有会计账簿查看权限，请联系租户所有者开通。
      </p>
    );

  // 总账是每个法人的记账主体，没有它就没有账：单独提示，而不是混在空列表里。
  const term = search.trim().toLowerCase();
  const filtered = books.filter(
    (b) =>
      (entityFilter === 'ALL' || b.legalEntityId === entityFilter) &&
      `${b.code} ${b.name} ${b.legalEntityCode} ${b.legalEntityName} ${b.baseCurrency}`
        .toLowerCase()
        .includes(term),
  );

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          账簿决定法人按哪个币、用哪套精度与舍入记账，以及会计年度怎么切。
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <label className="relative flex items-center">
            <Search className="text-muted-foreground pointer-events-none absolute left-2.5 h-4 w-4" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="搜索账簿"
              aria-label="搜索账簿"
              className="border-border bg-card text-copy-13 focus-visible:border-ring h-8 w-56 rounded-md border pl-8 pr-2 outline-none"
            />
          </label>
          <select
            value={entityFilter}
            onChange={(e) => setEntityFilter(e.target.value)}
            aria-label="按法人筛选"
            className="border-border bg-card text-copy-13 focus-visible:border-ring h-8 rounded-md border px-2 outline-none"
          >
            <option value="ALL">全部法人</option>
            {entities.map((e) => (
              <option key={e.id} value={e.id}>
                {e.code} · {e.legalName}
              </option>
            ))}
          </select>
          <Button variant="ghost" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
          {can('identity.accounting_book.create') && (
            <Button size="sm" disabled={entities.length === 0} onClick={() => setEditing('new')}>
              <Plus className="h-4 w-4" />
              新建账簿
            </Button>
          )}
        </div>
      </div>

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          {error}
          <Button variant="link" onClick={() => void load()}>
            重试
          </Button>
        </div>
      )}
      {notice && (
        <p role="status" className="text-success text-sm">
          {notice}
        </p>
      )}
      {entities.length === 0 && (
        <div className="bg-card rounded-lg border p-6 text-sm">
          还没有法人档案。账簿挂在法人上，请先到「法人档案」建好法人再来配置账簿。
        </div>
      )}
      {entities.length > 0 && entitiesWithoutBook.length > 0 && (
        <p className="text-copy-12 text-muted-foreground">
          尚未配置账簿的法人：{entitiesWithoutBook.map((e) => e.code).join('、')}
        </p>
      )}

      {editing && (
        <AccountingBookForm
          book={editing === 'new' ? null : editing}
          entities={entities}
          busy={busy}
          onCancel={() => setEditing(null)}
          onSubmit={async (entityId, input) => {
            setBusy(true);
            try {
              if (editing === 'new') {
                await api(`identity/legal-entities/${entityId}/accounting-books`, 'POST', input);
                setNotice('账簿已创建');
              } else {
                await api(`identity/accounting-books/${editing.id}`, 'PUT', input);
                setNotice('账簿配置已更新');
              }
              setEditing(null);
              await load();
            } catch (e) {
              setError(e instanceof Error ? e.message : '保存失败，请重试');
            } finally {
              setBusy(false);
            }
          }}
        />
      )}

      {filtered.length === 0 ? (
        <div className="bg-card rounded-lg border p-8 text-center">
          <p className="text-copy-13 text-muted-foreground">
            {books.length === 0 ? '还没有会计账簿' : '没有匹配当前筛选条件的账簿'}
          </p>
        </div>
      ) : (
        <ul className="space-y-2">
          {filtered.map((b) => (
            <li key={b.id} className="bg-card rounded-md border p-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="text-copy-15 flex flex-wrap items-center gap-2 font-medium">
                    {b.name}
                    <Badge tone={b.bookType === 'BOOK' ? 'success' : 'neutral'}>
                      {bookTypeLabel(b.bookType)}
                    </Badge>
                    {b.status === 'ACTIVE' ? null : <Badge tone="warning">停用</Badge>}
                    {!b.currencyAligned && <Badge tone="warning">本位币待同步</Badge>}
                  </p>
                  <dl className="text-copy-12 text-muted-foreground mt-2 grid gap-x-6 gap-y-1 sm:grid-cols-2 lg:grid-cols-3">
                    <div>
                      <dt className="inline">所属法人：</dt>
                      <dd className="inline">
                        {b.legalEntityCode} · {b.legalEntityName}
                      </dd>
                    </div>
                    <div>
                      <dt className="inline">编码：</dt>
                      <dd className="inline">{b.code}</dd>
                    </div>
                    <div>
                      <dt className="inline">本位币：</dt>
                      <dd className={b.currencyAligned ? 'inline' : 'text-destructive inline'}>
                        {baseCurrencySummary(b)}
                      </dd>
                    </div>
                    <div>
                      <dt className="inline">记账规则：</dt>
                      <dd className="inline">{accountingRuleSummary(b)}</dd>
                    </div>
                    <div>
                      <dt className="inline">期间设置：</dt>
                      <dd className="inline">
                        {periodSettingSummary(b)} · 首期 {firstPeriodStart(b.firstPeriodStart)}
                      </dd>
                    </div>
                    <div>
                      <dt className="inline">当前会计年度：</dt>
                      <dd className="inline">
                        {fiscalYearLabel(b.currentFiscalYear, b.fiscalYearStart, b.fiscalYearEnd)}
                      </dd>
                    </div>
                  </dl>
                </div>
                <div className="flex items-center gap-2">
                  {can('identity.accounting_book.update') && (
                    <Button size="sm" variant="ghost" onClick={() => setEditing(b)}>
                      编辑
                    </Button>
                  )}
                  {can('identity.accounting_book.delete') && (
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={busy}
                      onClick={() => void remove(b)}
                    >
                      删除
                    </Button>
                  )}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

type BookInput = {
  code: string;
  name: string;
  bookType: string;
  baseCurrency: string;
  amountScale: number;
  amountRounding: string;
  quantityScale: number;
  quantityRounding: string;
  periodType: string;
  fiscalYearStartMonth: number;
  firstPeriodStart: string;
  status: string;
};

function AccountingBookForm({
  book,
  entities,
  busy,
  onCancel,
  onSubmit,
}: {
  book: AccountingBookView | null;
  entities: LegalEntityView[];
  busy: boolean;
  onCancel: () => void;
  onSubmit: (legalEntityId: string, input: BookInput) => Promise<void>;
}) {
  const [entityId, setEntityId] = useState(
    book?.legalEntityId ?? entities.find((e) => e.status === 'ACTIVE')?.id ?? '',
  );
  const [code, setCode] = useState(book?.code ?? '');
  const [name, setName] = useState(book?.name ?? '');
  const [bookType, setBookType] = useState(book?.bookType ?? 'BOOK');
  const [baseCurrency, setBaseCurrency] = useState(book?.baseCurrency ?? '');
  const [amountScale, setAmountScale] = useState(String(book?.amountScale ?? 2));
  const [amountRounding, setAmountRounding] = useState(book?.amountRounding ?? 'HALF_UP');
  const [quantityScale, setQuantityScale] = useState(String(book?.quantityScale ?? 3));
  const [quantityRounding, setQuantityRounding] = useState(book?.quantityRounding ?? 'HALF_UP');
  const [periodType, setPeriodType] = useState(book?.periodType ?? 'MONTH');
  const [startMonth, setStartMonth] = useState(String(book?.fiscalYearStartMonth ?? 1));
  const [firstPeriod, setFirstPeriod] = useState(firstPeriodStart(book?.firstPeriodStart));
  const [status, setStatus] = useState(book?.status ?? 'ACTIVE');

  // 本位币取所选法人的：填别的币会被后端以 409 挡下，这里先跟着法人走。
  const selectedEntity = entities.find((e) => e.id === entityId);
  useEffect(() => {
    if (selectedEntity && (!book || selectedEntity.id !== book.legalEntityId))
      setBaseCurrency(selectedEntity.baseCurrency);
  }, [selectedEntity, book]);

  const month = Number(startMonth);
  const aligned = firstPeriodAligned(periodType, month, `${firstPeriod}T00:00:00Z`);
  const currencyMismatch =
    !!selectedEntity && baseCurrency.trim().toUpperCase() !== selectedEntity.baseCurrency;
  const valid =
    entityId !== '' &&
    code.trim() !== '' &&
    name.trim() !== '' &&
    baseCurrency.trim().length === 3 &&
    aligned &&
    !currencyMismatch;

  return (
    <div className="bg-card space-y-4 rounded-md border p-4">
      <p className="text-copy-14 font-medium">{book ? '编辑账簿' : '新建账簿'}</p>
      <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
        <Field label="所属法人" hint="账簿挂在法人上">
          <select
            value={entityId}
            onChange={(e) => setEntityId(e.target.value)}
            disabled={!!book}
            className={inputClass}
          >
            {entities.map((e) => (
              <option key={e.id} value={e.id}>
                {e.code} · {e.legalName}
              </option>
            ))}
          </select>
        </Field>
        <Field label="账簿编码" hint="大写字母数字下划线中划线，法人内唯一">
          <input value={code} onChange={(e) => setCode(e.target.value)} className={inputClass} />
        </Field>
        <Field label="账簿名称">
          <input value={name} onChange={(e) => setName(e.target.value)} className={inputClass} />
        </Field>
        <Field label="账簿类型" hint="一个法人只能一本总账">
          <select
            value={bookType}
            onChange={(e) => setBookType(e.target.value)}
            className={inputClass}
          >
            <option value="BOOK">总账</option>
            <option value="CASH">现金账簿</option>
            <option value="BANK">银行账簿</option>
          </select>
        </Field>
        <Field label="本位币" hint="必须等于所属法人的本位币">
          <input
            value={baseCurrency}
            onChange={(e) => setBaseCurrency(e.target.value.toUpperCase())}
            className={inputClass}
          />
        </Field>
        <Field label="状态">
          <select value={status} onChange={(e) => setStatus(e.target.value)} className={inputClass}>
            <option value="ACTIVE">启用</option>
            <option value="SUSPENDED">停用</option>
          </select>
        </Field>
        <Field label="金额精度" hint="0-6 位，按本位币最小单位">
          <input
            type="number"
            min={0}
            max={6}
            value={amountScale}
            onChange={(e) => setAmountScale(e.target.value)}
            className={inputClass}
          />
        </Field>
        <Field label="金额舍入">
          <select
            value={amountRounding}
            onChange={(e) => setAmountRounding(e.target.value)}
            className={inputClass}
          >
            {Object.entries({
              HALF_UP: '四舍五入',
              HALF_EVEN: '银行家舍入',
              DOWN: '向下取整',
              UP: '向上取整',
            }).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="数量精度" hint="0-6 位，按计量单位">
          <input
            type="number"
            min={0}
            max={6}
            value={quantityScale}
            onChange={(e) => setQuantityScale(e.target.value)}
            className={inputClass}
          />
        </Field>
        <Field label="数量舍入">
          <select
            value={quantityRounding}
            onChange={(e) => setQuantityRounding(e.target.value)}
            className={inputClass}
          >
            {Object.entries({
              HALF_UP: '四舍五入',
              HALF_EVEN: '银行家舍入',
              DOWN: '向下取整',
              UP: '向上取整',
            }).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="期间粒度" hint="周度不对齐月份，起始月将不参与校验">
          <select
            value={periodType}
            onChange={(e) => setPeriodType(e.target.value)}
            className={inputClass}
          >
            <option value="MONTH">月度</option>
            <option value="QUARTER">季度</option>
            <option value="WEEK">周度</option>
          </select>
        </Field>
        <Field label="会计年度起始月">
          <select
            value={startMonth}
            onChange={(e) => setStartMonth(e.target.value)}
            disabled={periodType === 'WEEK'}
            className={inputClass}
          >
            {MONTH_LABELS.map((label, index) => (
              <option key={label} value={index + 1}>
                {label}
              </option>
            ))}
          </select>
        </Field>
        <Field
          label="首期起点"
          hint={aligned ? '按 UTC 日历日期定义期界' : '首期月份与会计年度起始月/期间粒度不对齐'}
        >
          <input
            type="date"
            value={firstPeriod === '—' ? '' : firstPeriod}
            onChange={(e) => setFirstPeriod(e.target.value)}
            className={inputClass}
          />
        </Field>
      </div>
      {currencyMismatch && (
        <p role="alert" className="text-destructive text-sm">
          本位币必须等于所属法人的本位币（{selectedEntity?.baseCurrency}）。本位币归法人，
          请先在法人档案中调整，而不是在本账上单独改。
        </p>
      )}
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={busy || !valid}
          onClick={() =>
            void onSubmit(entityId, {
              code: code.trim(),
              name: name.trim(),
              bookType,
              baseCurrency: baseCurrency.trim().toUpperCase(),
              amountScale: Number(amountScale),
              amountRounding,
              quantityScale: Number(quantityScale),
              quantityRounding,
              periodType,
              fiscalYearStartMonth: month,
              firstPeriodStart: `${firstPeriod}T00:00:00Z`,
              status,
            })
          }
        >
          保存
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          取消
        </Button>
      </div>
    </div>
  );
}

const inputClass =
  'border-border bg-background text-copy-13 focus-visible:border-ring h-9 rounded-md border px-2 outline-none';

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="text-copy-13 mb-1 block font-medium">{label}</span>
      {children}
      {hint && <span className="text-copy-12 text-muted-foreground mt-1 block">{hint}</span>}
    </label>
  );
}
