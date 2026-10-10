import { expect, it } from 'vitest';
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

const book = (over: Record<string, unknown> = {}) => ({
  id: 'book-1',
  code: 'CNY-BIZ',
  name: '人民币业务账',
  bookCategory: 'BUSINESS',
  baseCurrency: 'CNY',
  baseCurrencyMode: 'INHERIT',
  chartOfAccountsSource: 'STANDARD',
  status: 'ACTIVE',
  defaultBook: false,
  ...over,
});

it('分类 / 本位币来源 / 科目体系来源 / 状态都有中文口径，未知取值原样透出', () => {
  expect(bookCategoryLabel('BUSINESS')).toBe('业务账');
  expect(bookCategoryLabel('STATUTORY')).toBe('法定账');
  expect(bookStatusLabel('ACTIVE')).toBe('启用');
  expect(bookStatusLabel('SUSPENDED')).toBe('停用');
  expect(chartOfAccountsSourceLabel('STANDARD')).toBe('标准准则');
  expect(chartOfAccountsSourceLabel('LOCAL')).toBe('本地准则');
  expect(chartOfAccountsSourceLabel('GROUP')).toBe('集团准则');
  // 后端加值时本地标签表缺项也不该渲染成空白
  expect(bookCategoryLabel('WHATEVER')).toBe('WHATEVER');
});

it('业务账与法定账在同一法人下并列，分组扁平化不丢也不重', () => {
  const business = book();
  const statutory = book({ id: 'book-2', code: 'CNY-TAX', name: '法定账', bookCategory: 'STATUTORY' });
  const flat = allBooks({
    businessBooks: [business],
    statutoryBooks: [statutory],
  });
  expect(flat.map((b) => b.id)).toEqual(['book-1', 'book-2']);
  expect(allBooks({ businessBooks: [], statutoryBooks: [] })).toEqual([]);
});

it('本位币来源分两档：INHERIT 对不上法人就是漂移，必须显式示警', () => {
  // 17.1.2.2 的纪律：INHERIT 时账上本位币必须等于法人本位币
  expect(baseCurrencySummary(book(), 'CNY')).toBe('CNY（继承法人本位币）');
  expect(baseCurrencySummary(book(), 'USD')).toBe(
    'CNY（继承法人本位币，法人本位币为 USD，需同步）',
  );
  // OWN 是跨境场景的正解：本位币与法人不同是**允许**的，不该示警
  expect(baseCurrencySummary(book({ baseCurrency: 'USD', baseCurrencyMode: 'OWN' }), 'CNY')).toBe(
    'USD（本账簿自定义）',
  );
});

it('默认账簿必须是本法人的启用账簿，停用的要能在提交前说出原因', () => {
  expect(defaultBookBlockReason(book(), 'CNY')).toBeNull();
  expect(defaultBookBlockReason(book({ status: 'SUSPENDED' }))).toBe(
    '停用账簿不能作为默认账簿，请先启用或改选其他账簿',
  );
  // INHERIT 且本位币与法人不一致时也不能当默认账簿：落点的币必须是可信的
  expect(defaultBookBlockReason(book(), 'USD')).toBe('继承法人本位币但本位币与法人不一致，需先同步本位币');
  // OWN 模式下本位币与法人不同是合法的，不拦
  expect(
    defaultBookBlockReason(book({ baseCurrency: 'USD', baseCurrencyMode: 'OWN' }), 'CNY'),
  ).toBeNull();
  expect(isActive(book())).toBe(true);
  expect(isActive(book({ status: 'SUSPENDED' }))).toBe(false);
});

it('归属入参校验与后端注解同口径：来源域大写、事实 ID 是 UUID、金额非负、币种三位', () => {
  expect(factSourceError('ORDER')).toBeNull();
  expect(factSourceError('  PURCHASE_ORDER  ')).toBeNull();
  expect(factSourceError('')).toBe('来源域不能为空');
  // 小写会被数据库 CHECK（btrim = upper）与 @Pattern 双重挡下
  expect(factSourceError('order')).toContain('大写');
  expect(factSourceError('ORDER-1')).toContain('大写');
  expect(factSourceError('A'.repeat(65))).toBe('来源域最长 64 个字符');

  expect(factRefError('3f1b7a52-0c4d-4a1e-9c3b-8d2e5f6a7b8c')).toBeNull();
  expect(factRefError('')).toBe('业务事实 ID 不能为空');
  expect(factRefError('12345')).toBe('业务事实 ID 需为 UUID 格式');

  // 金额可空（后端 amount 可空），填了必须是非负十进制
  expect(amountError('')).toBeNull();
  expect(amountError('1280.50')).toBeNull();
  expect(amountError('0')).toBeNull();
  expect(amountError('-1')).toBe('金额需为非负十进制数，例如 1280.50');
  expect(amountError('1,280.50')).toBe('金额需为非负十进制数，例如 1280.50');

  // 币种留空表示按账簿本位币记账，所以空值合法
  expect(currencyError('')).toBeNull();
  expect(currencyError('USD')).toBeNull();
  expect(currencyError('usd')).toBe('记账币种需为三位大写代码，例如 USD');
  expect(currencyError('US')).toBe('记账币种需为三位大写代码，例如 USD');
});

it('金额只格式化不做运算，坏值退化成占位符而不是 NaN', () => {
  expect(amountText(1280.5)).toBe('1280.5');
  expect(amountText('1280.500000')).toBe('1280.500000');
  expect(amountText(null)).toBe('—');
  expect(amountText(undefined)).toBe('—');
  expect(amountText('')).toBe('—');
  expect(amountText('not-a-number')).toBe('—');
});

it('归属行的事实标识能一眼看出是哪一笔', () => {
  expect(factReferenceLabel('ORDER', '3f1b7a52-0c4d-4a1e-9c3b-8d2e5f6a7b8c')).toBe(
    'ORDER/3f1b7a52-0c4d-4a1e-9c3b-8d2e5f6a7b8c',
  );
  // 单边缺失时不拼出一个带空洞的假标识
  expect(factReferenceLabel('', 'abc')).toBe('abc');
  expect(factReferenceLabel('ORDER', '')).toBe('ORDER');
});
