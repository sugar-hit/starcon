import type { LedgerBookItem } from '../../../../../packages/api-contracts/src/index';

/**
 * 17.6.1.1 账簿与本位币的展示与入参口径。
 *
 * 跨境场景下同一个法人会同时有人民币业务账与美元海外账，所以这里的核心问题不是
 * "这本账叫什么"，而是三件事：**它属于哪一类账（业务账 / 法定账）**、
 * **它的本位币是继承法人还是自己定（决定汇算时用哪把尺）**、
 * **它是这个法人的默认落点吗（缺默认时后端显式 409，绝不静默挑一本）**。
 *
 * 枚举一律**从契约行类型派生**：Java record 的枚举字段在 TS 侧被内联成字面量联合、
 * 不生成具名 type alias（见 tools/codegen/catalog-contracts.py），所以这里用
 * `NonNullable<LedgerBookItem['bookCategory']>` 取回那一份。这里**手抄**一份字符串联合
 * 就是漂移源：后端加值时本地类型不变、编译照过、运行时才炸。
 */
export type BookCategory = NonNullable<LedgerBookItem['bookCategory']>;
export type BaseCurrencyMode = NonNullable<LedgerBookItem['baseCurrencyMode']>;
export type ChartOfAccountsSource = NonNullable<LedgerBookItem['chartOfAccountsSource']>;
export type BookStatus = NonNullable<LedgerBookItem['status']>;

export const BOOK_CATEGORY_LABELS: Record<BookCategory, string> = {
  BUSINESS: '业务账',
  STATUTORY: '法定账',
};

export const BASE_CURRENCY_MODE_LABELS: Record<BaseCurrencyMode, string> = {
  INHERIT: '继承法人本位币',
  OWN: '本账簿自定义',
};

export const CHART_OF_ACCOUNTS_SOURCE_LABELS: Record<ChartOfAccountsSource, string> = {
  STANDARD: '标准准则',
  LOCAL: '本地准则',
  GROUP: '集团准则',
};

export const BOOK_STATUS_LABELS: Record<BookStatus, string> = {
  ACTIVE: '启用',
  SUSPENDED: '停用',
};

export function bookCategoryLabel(value: string): string {
  return BOOK_CATEGORY_LABELS[value as BookCategory] ?? value;
}

export function baseCurrencyModeLabel(value: string): string {
  return BASE_CURRENCY_MODE_LABELS[value as BaseCurrencyMode] ?? value;
}

export function chartOfAccountsSourceLabel(value: string): string {
  return CHART_OF_ACCOUNTS_SOURCE_LABELS[value as ChartOfAccountsSource] ?? value;
}

export function bookStatusLabel(value: string): string {
  return BOOK_STATUS_LABELS[value as BookStatus] ?? value;
}

/** 一本账可用于落账（默认账簿与归属登记都要求启用状态，见 V81 触发器与控制器）。 */
export function isActive(book: { status: string }): boolean {
  return book.status === 'ACTIVE';
}

/**
 * 本位币行。
 *
 * <p>INHERIT 模式下账上的本位币必须是法人本位币的副本（17.1.2.2 的纪律），
 * 对不上就是数据漂移，必须显式示警 —— 否则 12 财务总账会拿一个错的基数起算，
 * 而且不会有任何报错。
 */
export function baseCurrencySummary(
  book: { baseCurrency: string; baseCurrencyMode: string },
  entityCurrency?: string,
): string {
  const mode = baseCurrencyModeLabel(book.baseCurrencyMode);
  if (
    book.baseCurrencyMode === 'INHERIT' &&
    entityCurrency &&
    entityCurrency !== book.baseCurrency
  ) {
    return `${book.baseCurrency}（${mode}，法人本位币为 ${entityCurrency}，需同步）`;
  }
  return `${book.baseCurrency}（${mode}）`;
}

/** 分组里的账簿：业务账 + 法定账，供搜索与筛选按扁平清单走。 */
export function allBooks(groups: {
  businessBooks: Array<LedgerBookItem>;
  statutoryBooks: Array<LedgerBookItem>;
}): LedgerBookItem[] {
  return [...groups.businessBooks, ...groups.statutoryBooks];
}

const UUID_RE = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
const DECIMAL_RE = /^\d+(\.\d+)?$/;

/**
 * 入参校验，与后端 `FactAssignmentInput` 的注解同一口径。
 *
 * <p>前端预校验只是让人在提交前就看到原因，最终判定始终在后端（见控制器里的
 * `@Pattern` 与金额非负校验）。
 */
export function factSourceError(value: string): string | null {
  const trimmed = value.trim();
  if (trimmed === '') return '来源域不能为空';
  if (trimmed.length > 64) return '来源域最长 64 个字符';
  if (!/^[A-Z0-9_]+$/.test(trimmed))
    return '来源域只允许大写字母、数字与下划线，例如 ORDER / PURCHASE_ORDER';
  return null;
}

export function factRefError(value: string): string | null {
  const trimmed = value.trim();
  if (trimmed === '') return '业务事实 ID 不能为空';
  if (!UUID_RE.test(trimmed)) return '业务事实 ID 需为 UUID 格式';
  return null;
}

/** 空值合法：留空表示按账簿本位币记账（后端会在登记时回填账簿本位币）。 */
export function currencyError(value: string): string | null {
  const trimmed = value.trim();
  if (trimmed === '') return null;
  if (!/^[A-Z]{3}$/.test(trimmed)) return '记账币种需为三位大写代码，例如 USD';
  return null;
}

/**
 * 金额：空值合法（后端 amount 可空），填了就必须是非负十进制。
 *
 * <p>这里只做**格式**判断，不做任何换算或相加 —— 金额在库里是 NUMERIC(19,6)，
 * 前端一旦拿它做浮点运算，精度就在 JavaScript 这一侧丢掉了。
 */
export function amountError(value: string): string | null {
  const trimmed = value.trim();
  if (trimmed === '') return null;
  if (!DECIMAL_RE.test(trimmed)) return '金额需为非负十进制数，例如 1280.50';
  return null;
}

/** 金额原样展示：只格式化、不做运算，坏值宁可显示占位符也不渲染成 NaN。 */
export function amountText(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const text = typeof value === 'number' ? String(value) : value.trim();
  return /^-?\d+(\.\d+)?$/.test(text) ? text : '—';
}

/** 归属行的事实标识：`ORDER/3f1b…`，来源域与事实 ID 缺一不可读。 */
export function factReferenceLabel(factSource: string, factRef: string): string {
  if (!factSource) return factRef;
  if (!factRef) return factSource;
  return `${factSource}/${factRef}`;
}

/**
 * 能否把这本账设为默认账簿，以及不能的原因。
 *
 * <p>与后端 `setDefaultBook` 同一口径：默认账簿必须是本法人的**启用**账簿。
 * 停用账簿会被 V81 的 `accounting_book_default_guard` 触发器与控制器双重挡下，
 * 所以界面先把原因说出来，而不是让人提交后吃一个 409。
 */
export function defaultBookBlockReason(
  book: { status: string; baseCurrency: string; baseCurrencyMode: string },
  entityCurrency?: string,
): string | null {
  if (!isActive(book)) return '停用账簿不能作为默认账簿，请先启用或改选其他账簿';
  if (entityCurrency && book.baseCurrencyMode === 'INHERIT' && book.baseCurrency !== entityCurrency)
    return '继承法人本位币但本位币与法人不一致，需先同步本位币';
  return null;
}
