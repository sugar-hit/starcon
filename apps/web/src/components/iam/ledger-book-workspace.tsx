'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { Plus, RefreshCw, Search, Star, Trash2 } from 'lucide-react';
import type {
  DefaultBookView,
  FactAssignmentView,
  LedgerBookGroups,
  LedgerBookItem,
  LegalEntityView,
} from '../../../../../packages/api-contracts/src/index';
import { api, ApiError } from '@/lib/api';
import { useIdentity } from './identity-shell';
import {
  allBooks,
  amountError,
  amountText,
  baseCurrencySummary,
  bookCategoryLabel,
  bookStatusLabel,
  chartOfAccountsSourceLabel,
  currencyError,
  defaultBookBlockReason,
  factRefError,
  factReferenceLabel,
  factSourceError,
  isActive,
} from './ledger-book-model';

/**
 * 17.6.1.1 账簿与本位币（蓝图 17.6 → `/settings/books`）。
 *
 * 一个法人在跨境场景下会同时有人民币业务账与美元海外账，所以这个页面回答三个问题：
 *
 * <ol>
 *   <li>**这本账是哪一类**（业务账 / 法定账）—— 业务流水走业务账，报税走法定账；
 *   <li>**它的本位币怎么来的**（INHERIT 继承法人 / OWN 自己定）—— 决定汇算时用哪把尺；
 *   <li>**这一笔业务事实记在哪本账** —— 同一笔事实只能有一个归属账簿，跨账簿不混记。
 * </ol>
 *
 * 默认账簿缺省时后端**显式 409**，这里把它渲染成"去设默认"的引导而不是空态：
 * 单据落账的落点必须由人指定，静默挑一本会让同一笔业务在不同环境落到不同的账上。
 */

/** 归属登记的请求体：amount 用字符串原样递给后端的 BigDecimal，不在前端做浮点转换。 */
type FactAssignmentPayload = {
  factSource: string;
  factRef: string;
  amount?: string;
  currency?: string;
};

export function LedgerBookWorkspace() {
  const { session } = useIdentity();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [search, setSearch] = useState('');

  const [entities, setEntities] = useState<LegalEntityView[]>([]);
  const [entityId, setEntityId] = useState('');

  const [groups, setGroups] = useState<LedgerBookGroups | null>(null);
  const [groupsError, setGroupsError] = useState('');
  const [defaultBook, setDefaultBook] = useState<DefaultBookView | null>(null);
  // 409 是后端「该法人尚未设置默认账簿」的显式约定，不是失败：单独一个状态才能做引导。
  const [defaultMissing, setDefaultMissing] = useState(false);
  const [defaultMissingReason, setDefaultMissingReason] = useState('');

  const [bookId, setBookId] = useState('');
  const [assignments, setAssignments] = useState<FactAssignmentView[]>([]);
  const [assignmentsError, setAssignmentsError] = useState('');
  const [assignmentsReady, setAssignmentsReady] = useState(false);
  const [assigning, setAssigning] = useState(false);

  const can = useCallback(
    (permission: string) => session.permissions.includes(permission),
    [session.permissions],
  );
  const readable = can('identity.accounting_book.read');
  const mayUpdate = can('identity.accounting_book.update');
  const mayDelete = can('identity.accounting_book.delete');

  const entity = useMemo(
    () => entities.find((e) => e.id === entityId) ?? null,
    [entities, entityId],
  );

  /** 法人清单：账簿挂在法人上，没有法人就没有账簿可分。 */
  const loadEntities = useCallback(async () => {
    setError('');
    setNotice('');
    if (!readable) {
      // 没有读权限就不请求：空数组会被渲染成「还没有账簿」，那是骗人。
      setReady(true);
      return;
    }
    try {
      const list = await api<LegalEntityView[]>('identity/legal-entities');
      setEntities(list);
      setEntityId((current) => {
        if (current && list.some((e) => e.id === current)) return current;
        return list.find((e) => e.status === 'ACTIVE')?.id ?? list[0]?.id ?? '';
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : '请求失败，请重试');
    } finally {
      setReady(true);
    }
  }, [readable]);

  /**
   * 账簿分组与默认账簿一起取，但**不共用一个 catch**。
   *
   * <p>缺默认账簿时 `GET default-book` 是显式 409；用 Promise.all 会让这一个 409
   * 把已经成功的分组结果一起吞掉，页面就变成"这个法人一个账簿都没有" —— 那正是
   * 「把 409 吞成空态」。allSettled 让两件事各自结算：分组要么有数据要么有错误，
   * 默认账簿要么是那一本要么是"去设置"的引导。
   */
  const loadBooks = useCallback(
    async (id: string) => {
      setGroupsError('');
      setDefaultBook(null);
      setDefaultMissing(false);
      setDefaultMissingReason('');
      if (!id) {
        setGroups(null);
        return;
      }
      const [groupResult, defaultResult] = await Promise.allSettled([
        api<LedgerBookGroups>(`identity/legal-entities/${id}/ledger-books`),
        api<DefaultBookView>(`identity/legal-entities/${id}/default-book`),
      ]);
      if (groupResult.status === 'fulfilled') {
        setGroups(groupResult.value);
      } else {
        setGroups(null);
        setGroupsError(
          groupResult.reason instanceof Error ? groupResult.reason.message : '账簿加载失败，请重试',
        );
      }
      if (defaultResult.status === 'fulfilled') {
        setDefaultBook(defaultResult.value);
      } else {
        const reason = defaultResult.reason;
        if (reason instanceof ApiError && reason.status === 409) {
          setDefaultMissing(true);
          setDefaultMissingReason(reason.message);
        } else {
          setDefaultMissing(false);
          setGroupsError((current) =>
            current ||
            (reason instanceof Error ? reason.message : '默认账簿加载失败，请重试'),
          );
        }
      }
    },
    [],
  );

  useEffect(() => {
    void loadEntities();
  }, [loadEntities]);

  useEffect(() => {
    if (!entityId) return;
    void loadBooks(entityId);
  }, [entityId, loadBooks]);

  // 选中账簿在法人切换后可能已不存在，留着会打到一个不属于当前法人的 bookId。
  useEffect(() => {
    if (!groups) {
      setBookId('');
      return;
    }
    const books = allBooks(groups);
    if (books.length === 0) {
      setBookId('');
      return;
    }
    if (!bookId || !books.some((b) => b.id === bookId)) setBookId(books[0]!.id);
  }, [groups, bookId]);

  const loadAssignments = useCallback(async (id: string) => {
    setAssignmentsError('');
    setAssignmentsReady(false);
    if (!id) {
      setAssignments([]);
      setAssignmentsReady(true);
      return;
    }
    try {
      setAssignments(await api<FactAssignmentView[]>(`identity/accounting-books/${id}/fact-assignments`));
    } catch (e) {
      setAssignments([]);
      setAssignmentsError(e instanceof Error ? e.message : '归属清单加载失败，请重试');
    } finally {
      setAssignmentsReady(true);
    }
  }, []);

  useEffect(() => {
    void loadAssignments(bookId);
  }, [bookId, loadAssignments]);

  const books = useMemo(() => (groups ? allBooks(groups) : []), [groups]);
  const selected = useMemo(() => books.find((b) => b.id === bookId) ?? null, [books, bookId]);

  async function setDefault(book: LedgerBookItem) {
    if (!entityId) return;
    const reason = defaultBookBlockReason(book, entity?.baseCurrency);
    if (reason) {
      setError(reason);
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await api(`identity/legal-entities/${entityId}/default-book`, 'PUT', { bookId: book.id });
      setNotice(`已将「${book.name}」设为本法人的默认账簿`);
      await loadBooks(entityId);
    } catch (e) {
      setError(e instanceof Error ? e.message : '设置默认账簿失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  async function assignFact(input: FactAssignmentPayload) {
    if (!bookId) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await api(`identity/accounting-books/${bookId}/fact-assignments`, 'POST', input);
      setNotice(`已登记归属：${factReferenceLabel(input.factSource, input.factRef)}`);
      setAssigning(false);
      await loadAssignments(bookId);
    } catch (e) {
      // 跨账簿混记与停用账簿都是 409，提示语由后端写明「已归属账簿 X」，原样透出。
      setError(e instanceof Error ? e.message : '登记归属失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  async function releaseFact(assignment: FactAssignmentView) {
    if (!bookId) return;
    if (
      !window.confirm(
        `解除「${factReferenceLabel(assignment.factSource, assignment.factRef)}」在这本账的归属？解除后这笔事实可以被其它账簿登记。`,
      )
    )
      return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await api(`identity/accounting-books/${bookId}/fact-assignments/${assignment.id}`, 'DELETE');
      setNotice('归属已解除，该业务事实可被其它账簿登记');
      await loadAssignments(bookId);
    } catch (e) {
      setError(e instanceof Error ? e.message : '解除归属失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载账簿…
      </p>
    );

  if (!readable)
    return (
      <p className="rounded-lg border p-6 text-sm">
        当前角色没有会计账簿查看权限，请联系租户所有者开通。
      </p>
    );

  const term = search.trim().toLowerCase();
  const matches = (b: LedgerBookItem) =>
    `${b.code} ${b.name} ${b.baseCurrency}`.toLowerCase().includes(term);
  const column = (list: LedgerBookItem[]) => list.filter(matches);

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          一个法人可以同时用人民币业务账与美元海外账记账。账簿决定本位币、科目体系与报表口径，跨账簿不混记。
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
            value={entityId}
            onChange={(e) => setEntityId(e.target.value)}
            aria-label="按法人筛选"
            disabled={entities.length === 0}
            className="border-border bg-card text-copy-13 focus-visible:border-ring h-8 rounded-md border px-2 outline-none"
          >
            {entities.length === 0 && <option value="">没有可用法人</option>}
            {entities.map((e) => (
              <option key={e.id} value={e.id}>
                {e.code} · {e.legalName}
              </option>
            ))}
          </select>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              void loadEntities();
              if (entityId) void loadBooks(entityId);
              void loadAssignments(bookId);
            }}
          >
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
        </div>
      </div>

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          {error}
          <Button
            variant="link"
            onClick={() => {
              void loadEntities();
              if (entityId) void loadBooks(entityId);
              void loadAssignments(bookId);
            }}
          >
            重试
          </Button>
        </div>
      )}
      {notice && (
        <p role="status" className="text-success text-sm">
          {notice}
        </p>
      )}
      {entities.length === 0 && !error && (
        <div className="bg-card rounded-lg border p-6 text-sm">
          还没有法人档案。账簿挂在法人上，请先到「法人档案」建好法人再来配置账簿。
        </div>
      )}
      {groupsError && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          {groupsError}
          <Button variant="link" onClick={() => void loadBooks(entityId)}>
            重试
          </Button>
        </div>
      )}

      {entityId && !groupsError && (
        <section className="bg-card space-y-2 rounded-lg border p-4">
          <h2 className="text-copy-15 flex items-center gap-2 font-medium">
            <Star className="h-4 w-4" />
            默认账簿
          </h2>
          {defaultBook ? (
            <p className="text-copy-13">
              <span className="font-medium">{defaultBook.bookName}</span>
              <span className="text-muted-foreground">（{defaultBook.bookCode}）</span>
              <span className="text-muted-foreground">
                {' · '}
                {bookCategoryLabel(defaultBook.bookCategory)} ·{' '}
                {baseCurrencySummary(defaultBook, entity?.baseCurrency)} · 单据默认落账用这本
              </span>
            </p>
          ) : defaultMissing ? (
            <div className="space-y-2">
              <p role="status" className="text-copy-13">
                尚未设置默认账簿。后端不会静默挑一本 ——
                {defaultMissingReason ||
                  '单据落账的落点必须由人指定，否则同一笔业务在不同环境下会落到不同的账上。'}
              </p>
              <p className="text-copy-12 text-muted-foreground">
                在下方「业务账 / 法定账」里选一本启用中的账簿，点「设为默认」。
              </p>
            </div>
          ) : (
            <p className="text-copy-13 text-muted-foreground">正在确认默认账簿…</p>
          )}
        </section>
      )}

      {groups && books.length === 0 && (
        <div className="bg-card rounded-lg border p-6 text-sm">
          该法人还没有账簿。账簿挂在法人上，请在「账簿配置」里为本法人建立业务账与法定账。
        </div>
      )}

      {groups && books.length > 0 && (
        <div className="grid gap-4 lg:grid-cols-2">
          {[
            {
              title: '业务账',
              hint: '业务流水记在这里，与法定账分开',
              books: groups.businessBooks,
            },
            {
              title: '法定账',
              hint: '报税与法定报表口径记在这里',
              books: groups.statutoryBooks,
            },
          ].map((section) => (
            <section key={section.title} className="space-y-2">
              <div>
                <h2 className="text-copy-15 font-medium">
                  {section.title}
                  <span className="text-copy-12 text-muted-foreground ml-2">{section.hint}</span>
                </h2>
              </div>
              {column(section.books).length === 0 ? (
                <p className="bg-card text-copy-13 text-muted-foreground rounded-md border p-4">
                  {section.books.length === 0
                    ? `该法人还没有${section.title}`
                    : '没有匹配当前搜索的账簿'}
                </p>
              ) : (
                <ul className="space-y-2">
                  {column(section.books).map((b) => {
                    const blocked = defaultBookBlockReason(b, entity?.baseCurrency);
                    return (
                      <li
                        key={b.id}
                        className={`bg-card rounded-md border p-4 ${b.id === bookId ? 'border-brand' : ''}`}
                      >
                        <div className="flex flex-wrap items-start justify-between gap-3">
                          <div className="min-w-0">
                            <p className="text-copy-15 flex flex-wrap items-center gap-2 font-medium">
                              {b.name}
                              {b.defaultBook && <Badge tone="brand">默认账簿</Badge>}
                              {!isActive(b) && (
                                <Badge tone="warning">{bookStatusLabel(b.status)}</Badge>
                              )}
                            </p>
                            <dl className="text-copy-12 text-muted-foreground mt-2 grid gap-x-6 gap-y-1 sm:grid-cols-2">
                              <div>
                                <dt className="inline">编码：</dt>
                                <dd className="inline">{b.code}</dd>
                              </div>
                              <div>
                                <dt className="inline">本位币：</dt>
                                <dd className="inline">
                                  {baseCurrencySummary(b, entity?.baseCurrency)}
                                </dd>
                              </div>
                              <div>
                                <dt className="inline">科目体系来源：</dt>
                                <dd className="inline">{chartOfAccountsSourceLabel(b.chartOfAccountsSource)}</dd>
                              </div>
                              <div>
                                <dt className="inline">状态：</dt>
                                <dd className="inline">{bookStatusLabel(b.status)}</dd>
                              </div>
                            </dl>
                            {blocked && (
                              <p className="text-copy-12 text-warning mt-2">{blocked}</p>
                            )}
                          </div>
                          <div className="flex items-center gap-2">
                            <Button size="sm" variant="ghost" onClick={() => setBookId(b.id)}>
                              归属流水
                            </Button>
                            {mayUpdate && !b.defaultBook && (
                              <Button
                                size="sm"
                                variant="ghost"
                                disabled={busy}
                                title={blocked ?? '设为该法人的默认账簿'}
                                onClick={() => void setDefault(b)}
                              >
                                <Star className="h-4 w-4" />
                                设为默认
                              </Button>
                            )}
                          </div>
                        </div>
                      </li>
                    );
                  })}
                </ul>
              )}
            </section>
          ))}
        </div>
      )}

      {selected && (
        <FactAssignmentPanel
          book={selected}
          assignments={assignments}
          error={assignmentsError}
          ready={assignmentsReady}
          busy={busy}
          assigning={assigning}
          mayAssign={mayUpdate}
          mayRelease={mayDelete}
          onRetry={() => void loadAssignments(selected.id)}
          onOpen={() => setAssigning(true)}
          onClose={() => setAssigning(false)}
          onAssign={assignFact}
          onRelease={releaseFact}
        />
      )}
    </div>
  );
}

function FactAssignmentPanel({
  book,
  assignments,
  error,
  ready,
  busy,
  assigning,
  mayAssign,
  mayRelease,
  onRetry,
  onOpen,
  onClose,
  onAssign,
  onRelease,
}: {
  book: LedgerBookItem;
  assignments: FactAssignmentView[];
  error: string;
  ready: boolean;
  busy: boolean;
  assigning: boolean;
  mayAssign: boolean;
  mayRelease: boolean;
  onRetry: () => void;
  onOpen: () => void;
  onClose: () => void;
  onAssign: (input: FactAssignmentPayload) => Promise<void>;
  onRelease: (assignment: FactAssignmentView) => Promise<void>;
}) {
  return (
    <section className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="text-copy-15 font-medium">
            「{book.name}」已归属的业务事实
            <span className="text-copy-12 text-muted-foreground ml-2">
              同一笔业务事实在整个租户只能认领一本账，跨账簿不混记
            </span>
          </h2>
        </div>
        {mayAssign && (
          <Button size="sm" disabled={!isActive(book)} onClick={onOpen}>
            <Plus className="h-4 w-4" />
            登记归属
          </Button>
        )}
      </div>
      {!isActive(book) && (
        <p className="text-copy-12 text-warning">
          停用账簿不能接收新的业务事实归属，请先在「账簿配置」启用它。
        </p>
      )}
      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          {error}
          <Button variant="link" onClick={onRetry}>
            重试
          </Button>
        </div>
      )}
      {!ready ? (
        <p role="status" className="text-muted-foreground text-sm">
          正在加载归属清单…
        </p>
      ) : assignments.length === 0 ? (
        <p className="bg-card text-copy-13 text-muted-foreground rounded-md border p-4">
          这本账还没有登记任何业务事实。
        </p>
      ) : (
        <ul className="space-y-2">
          {assignments.map((a) => (
            <li key={a.id} className="bg-card flex flex-wrap items-start justify-between gap-3 rounded-md border p-3">
              <dl className="text-copy-12 text-muted-foreground grid gap-x-6 gap-y-1 sm:grid-cols-2 lg:grid-cols-3">
                <div>
                  <dt className="inline">业务事实：</dt>
                  <dd className="text-foreground inline break-all">
                    {factReferenceLabel(a.factSource, a.factRef)}
                  </dd>
                </div>
                <div>
                  <dt className="inline">金额：</dt>
                  <dd className="inline">
                    {amountText(a.amount)} {a.currency || '—'}
                  </dd>
                </div>
                <div>
                  <dt className="inline">本位币：</dt>
                  <dd className="inline">{a.baseCurrency}</dd>
                </div>
              </dl>
              {mayRelease && (
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={busy}
                  onClick={() => void onRelease(a)}
                >
                  <Trash2 className="h-4 w-4" />
                  解除归属
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {assigning && (
        <FactAssignmentForm
          book={book}
          busy={busy}
          onCancel={onClose}
          onSubmit={onAssign}
        />
      )}
    </section>
  );
}

function FactAssignmentForm({
  book,
  busy,
  onCancel,
  onSubmit,
}: {
  book: LedgerBookItem;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: FactAssignmentPayload) => Promise<void>;
}) {
  const [factSource, setFactSource] = useState('');
  const [factRef, setFactRef] = useState('');
  const [amount, setAmount] = useState('');
  const [currency, setCurrency] = useState('');

  const sourceError = factSourceError(factSource);
  const refError = factRefError(factRef);
  const moneyError = amountError(amount);
  const currencyProblem = currencyError(currency);
  const valid = !sourceError && !refError && !moneyError && !currencyProblem;

  return (
    <div className="bg-card space-y-4 rounded-md border p-4">
      <p className="text-copy-14 font-medium">登记业务事实的归属账簿</p>
      <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
        <Field label="来源域" hint="大写字母数字下划线，例如 ORDER / PURCHASE_ORDER">
          <input
            value={factSource}
            onChange={(e) => setFactSource(e.target.value.toUpperCase())}
            className={inputClass}
            aria-invalid={sourceError !== null}
          />
          {sourceError && <span className="text-destructive text-copy-12 mt-1 block">{sourceError}</span>}
        </Field>
        <Field label="业务事实 ID" hint="UUID，跨账簿全局唯一">
          <input
            value={factRef}
            onChange={(e) => setFactRef(e.target.value)}
            className={inputClass}
            aria-invalid={refError !== null}
          />
          {refError && <span className="text-destructive text-copy-12 mt-1 block">{refError}</span>}
        </Field>
        <Field label="金额" hint={`可留空；非负十进制，按 ${book.baseCurrency} 记`}>
          <input
            inputMode="decimal"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            className={inputClass}
            aria-invalid={moneyError !== null}
          />
          {moneyError && <span className="text-destructive text-copy-12 mt-1 block">{moneyError}</span>}
        </Field>
        <Field label="记账币种" hint={`留空则按本位币 ${book.baseCurrency} 记账`}>
          <input
            value={currency}
            onChange={(e) => setCurrency(e.target.value.toUpperCase())}
            placeholder={book.baseCurrency}
            className={inputClass}
            aria-invalid={currencyProblem !== null}
          />
          {currencyProblem && (
            <span className="text-destructive text-copy-12 mt-1 block">{currencyProblem}</span>
          )}
        </Field>
      </div>
      <p className="text-copy-12 text-muted-foreground">
        同一笔业务事实只能归属一本账。若它已归属其它账簿，后端会拒绝并告诉你是哪一本。
      </p>
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={busy || !valid}
          onClick={() =>
            void onSubmit({
              factSource: factSource.trim(),
              factRef: factRef.trim(),
              ...(amount.trim() === '' ? {} : { amount: amount.trim() }),
              ...(currency.trim() === '' ? {} : { currency: currency.trim() }),
            })
          }
        >
          登记归属
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          取消
        </Button>
      </div>
    </div>
  );
}

const inputClass =
  'border-border bg-background text-copy-13 focus-visible:border-ring h-9 w-full rounded-md border px-2 outline-none';

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
