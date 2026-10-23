import type {
  FinanceAccountSubjectByCodeResult,
  FinanceAccountSubjectCapabilities,
  FinanceAccountSubjectDetail,
  FinanceAccountSubjectMappingInput,
  FinanceAccountSubjectMappingPage,
  FinanceAccountSubjectMappingRow,
  FinanceAccountSubjectMappingUpdateInput,
  FinanceAccountSubjectOptions,
  FinanceAccountSubjectPage,
  FinanceAccountSubjectRow,
  FinanceAccountSubjectTemplateApplyInput,
  FinanceAccountSubjectTemplateApplyResult,
  FinanceAccountSubjectTemplateCatalog,
  FinanceAccountSubjectUpdateInput,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.5.1.1 会计科目的前端模型。
 *
 * <p>蓝图 12-finance.md：「维护**法人账簿**的会计科目表：科目编码、名称、类别（资产/负债/权益/成本/损益）、
 * 余额方向、是否明细科目、关联税务属性；支持科目映射到业务事件。」输入：「科目定义；业务事件映射
 * （如发货 → 收入 + 结转成本）。」输出：「科目表 + 映射规则。」关系：「上游 = 用户会计体系；下游 =
 * 12.5.2.1 凭证、12.5.2.2 报表。**科目体系是用户自定的**，系统提供模板但必须允许完全自定义。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>科目编码是只读的</b>：编码在本租户内唯一且**一经入账不可改**（下游 12.5.2.1 凭证按编码
 *       软引用它）⇒ 页面**不提供**改编码的输入框，只显示它。要换编码只能「停用 + 另建」。</li>
 *   <li><b>类别与余额方向是两个独立字段</b>：累计折旧是资产类却记贷方余额 ⇒ 页面**不联动**它们，
 *       也不替用户猜。</li>
 *   <li><b>模板只是导入源</b>：导入后每个字段都能改 ⇒ 页面提供「从模板导入」，但导入结果**逐条报出**
 *       哪些编码被跳过（已存在），不假装全导进去了。</li>
 *   <li><b>映射规则的「借贷齐备」由服务端判</b>：页面原样显示服务端给的
 *       {@code debitLineCount / creditLineCount / balanced}，**不在前端自己判断**能不能启用。</li>
 *   <li><b>不可执行的动作把服务端给的阻断原因显示出来</b>，而不是把按钮灰掉让人猜。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有科目」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code finance/account-subjects}，与同服务的其它端点首 token 全不相同
 * （{@code receivables} / {@code settlement} / {@code transactions} / {@code bad-debt} …）。
 */

/** 后端命名空间（与 api-proxy 白名单里的首 token 一致）。 */
const base = 'finance/account-subjects';

/**
 * 筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface AccountSubjectParams {
  accountingBookId?: string | undefined;
  category?: string | undefined;
  balanceDirection?: string | undefined;
  taxTreatment?: string | undefined;
  status?: string | undefined;
  detail?: boolean | undefined;
  parentSubjectCode?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/**
 * 金额口径的可选值（与契约里的枚举一致）。
 *
 * <p>页面用这个联合类型约束下拉框：写死成 `string` 会让「后端枚举」与「页面能选的值」悄悄分叉。
 */
export type AccountAmountBasisValue =
  | 'ORDER_ITEM_AMOUNT'
  | 'ITEM_COST'
  | 'SHIPPING_AMOUNT'
  | 'TAX_AMOUNT'
  | 'PLATFORM_FEE'
  | 'REFUND_AMOUNT'
  | 'ADJUSTMENT_AMOUNT'
  | 'OTHER';

/** 映射规则的筛选条件。 */
export interface AccountSubjectMappingParams {
  accountingBookId?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

function query(params: Record<string, string | number | boolean | undefined>): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    if (typeof value === 'string' && value === '') continue;
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 端点

/** 能力声明：哪些真接通、哪些还没交付、归谁。 */
export function fetchAccountSubjectCapabilities() {
  return api<FinanceAccountSubjectCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchAccountSubjectOptions() {
  return api<FinanceAccountSubjectOptions>(`${base}/options`);
}

/** 科目列表：真分页 + 真筛选 + 分类汇总。 */
export function fetchAccountSubjects(params: AccountSubjectParams = {}) {
  return api<FinanceAccountSubjectPage>(`${base}${query({ ...params })}`);
}

/** 科目详情：科目 + 变更留痕 + 引用它的映射规则。 */
export function fetchAccountSubject(id: string) {
  return api<FinanceAccountSubjectDetail>(`${base}/${encodeURIComponent(id)}`);
}

/**
 * 按科目编码查（下游 12.5.2.1 凭证的集成点）。
 *
 * <p>⚠️ 编码在本租户内唯一 ⇒ 命中时至多一条；没命中时后端如实返回 `found=false`。
 */
export function fetchAccountSubjectByCode(subjectCode: string) {
  return api<FinanceAccountSubjectByCodeResult>(
    `${base}/by-code${query({ subjectCode })}`,
  );
}

/** 新增科目。 */
export function createAccountSubject(input: {
  accountingBookId: string;
  legalEntityId: string;
  subjectCode: string;
  subjectName: string;
  category: string;
  balanceDirection: string;
  detail?: boolean;
  parentSubjectCode?: string | null;
  taxTreatment?: string | null;
  taxRate?: number | null;
  taxNote?: string | null;
}) {
  return api<FinanceAccountSubjectRow>(`${base}`, 'POST', input);
}

/**
 * 改科目。
 *
 * <p>⚠️ **没有 subjectCode**：编码一经入账不可改（下游凭证按编码软引用它）。
 */
export function updateAccountSubject(id: string, input: FinanceAccountSubjectUpdateInput) {
  return api<FinanceAccountSubjectRow>(`${base}/${encodeURIComponent(id)}`, 'PUT', input);
}

/** 停用科目（停用不是删除：历史凭证仍要能引用到它）。 */
export function disableAccountSubject(id: string) {
  return api<FinanceAccountSubjectRow>(`${base}/${encodeURIComponent(id)}/disable`, 'POST');
}

/** 重新启用科目。 */
export function enableAccountSubject(id: string) {
  return api<FinanceAccountSubjectRow>(`${base}/${encodeURIComponent(id)}/enable`, 'POST');
}

/** 系统科目模板目录。 */
export function fetchAccountSubjectTemplates() {
  return api<FinanceAccountSubjectTemplateCatalog>(`${base}/templates`);
}

/** 从模板导入科目到指定账簿。 */
export function applyAccountSubjectTemplate(input: FinanceAccountSubjectTemplateApplyInput) {
  return api<FinanceAccountSubjectTemplateApplyResult>(`${base}/templates/apply`, 'POST', input);
}

/** 映射规则清单。 */
export function fetchAccountSubjectMappings(params: AccountSubjectMappingParams = {}) {
  return api<FinanceAccountSubjectMappingPage>(`${base}/mappings${query({ ...params })}`);
}

/** 映射规则详情（头 + 分录行）。 */
export function fetchAccountSubjectMapping(id: string) {
  return api<FinanceAccountSubjectMappingRow>(`${base}/mappings/${encodeURIComponent(id)}`);
}

/** 新建映射规则（头 + 分录行）。 */
export function createAccountSubjectMapping(input: FinanceAccountSubjectMappingInput) {
  return api<FinanceAccountSubjectMappingRow>(`${base}/mappings`, 'POST', input);
}

/** 改映射规则（分录行全量替换；启用态要先停用）。 */
export function updateAccountSubjectMapping(
  id: string,
  input: FinanceAccountSubjectMappingUpdateInput,
) {
  return api<FinanceAccountSubjectMappingRow>(`${base}/mappings/${encodeURIComponent(id)}`, 'PUT', input);
}

/** 启用映射规则（借贷齐备才让启用）。 */
export function activateAccountSubjectMapping(id: string) {
  return api<FinanceAccountSubjectMappingRow>(
    `${base}/mappings/${encodeURIComponent(id)}/activate`,
    'POST',
  );
}

/** 停用映射规则。 */
export function deactivateAccountSubjectMapping(id: string) {
  return api<FinanceAccountSubjectMappingRow>(
    `${base}/mappings/${encodeURIComponent(id)}/deactivate`,
    'POST',
  );
}

// ================================================================ 可读形态

/** 科目类别的可读名。 */
export function accountSubjectCategoryLabel(category: string): string {
  switch (category) {
    case 'ASSET':
      return '资产';
    case 'LIABILITY':
      return '负债';
    case 'EQUITY':
      return '权益';
    case 'COST':
      return '成本';
    case 'PROFIT_LOSS':
      return '损益';
    default:
      return category;
  }
}

/** 余额方向的可读名。 */
export function accountBalanceDirectionLabel(direction: string): string {
  switch (direction) {
    case 'DEBIT':
      return '借方';
    case 'CREDIT':
      return '贷方';
    default:
      return direction;
  }
}

/** 税务属性的可读名。 */
export function accountTaxTreatmentLabel(treatment: string): string {
  switch (treatment) {
    case 'TAXABLE':
      return '应税';
    case 'EXEMPT':
      return '免税';
    case 'ZERO_RATED':
      return '零税率';
    case 'NON_TAXABLE':
      return '不征税';
    case 'REVERSE_CHARGE':
      return '视同应税';
    default:
      return treatment;
  }
}

/** 科目状态的可读名。 */
export function accountSubjectStatusLabel(status: string): string {
  switch (status) {
    case 'ACTIVE':
      return '在用';
    case 'DISABLED':
      return '停用';
    default:
      return status;
  }
}

/** 科目状态的语义色（页面据此上色，不解释语义）。 */
export function accountSubjectStatusTone(status: string): 'success' | 'neutral' {
  return status === 'ACTIVE' ? 'success' : 'neutral';
}

/** 是否明细科目的可读名。 */
export function accountSubjectDetailLabel(detail: boolean): string {
  return detail ? '明细科目' : '汇总科目';
}

/**
 * 税率的可读形态。
 *
 * <p>⚠️ **只做显示格式化，不在前端做算术**：0.13 显示成 13%，
 * 但服务端给什么就是什么（页面不自己乘 100 再除）。
 */
export function formatAccountTaxRate(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '未固定税率';
  return `${(rate * 100).toFixed(2).replace(/\.?0+$/, '')}%`;
}

/**
 * 金额口径的可读名。
 *
 * <p>⚠️ 模板**不固定金额** ⇒ 这里给的是「取哪个量」，不是金额本身。
 */
export function accountAmountBasisLabel(basis: string): string {
  switch (basis) {
    case 'ORDER_ITEM_AMOUNT':
      return '商品金额';
    case 'ITEM_COST':
      return '商品成本';
    case 'SHIPPING_AMOUNT':
      return '运费';
    case 'TAX_AMOUNT':
      return '税额';
    case 'PLATFORM_FEE':
      return '平台费用';
    case 'REFUND_AMOUNT':
      return '退款金额';
    case 'ADJUSTMENT_AMOUNT':
      return '调整金额';
    case 'OTHER':
      return '其他';
    default:
      return basis;
  }
}

/**
 * 映射规则「借贷是否齐备」的可读形态。
 *
 * <p>⚠️ 判定由**服务端**做（借方行数 / 贷方行数 / balanced），页面原样显示 ——
 * 前端自己数一遍就可能与服务端的守卫规则不一致。
 */
export function accountMappingBalanceText(
  debitLineCount: number,
  creditLineCount: number,
  balanced: boolean,
): string {
  return balanced
    ? `借贷齐备（借方 ${debitLineCount} 条 / 贷方 ${creditLineCount} 条）`
    : `借贷不齐备（借方 ${debitLineCount} 条 / 贷方 ${creditLineCount} 条，启用前必须借贷齐备）`;
}

/**
 * 模板导入结果的可读形态。
 *
 * <p>⚠️ **被跳过的编码必须逐条显示** —— 「导入了 20 个科目」如果不说清哪几个没进来，
 * 现场会以为模板全导进去了。
 */
export function accountTemplateApplySummary(result: {
  templateKey: string;
  created: number;
  skipped: number;
  skippedCodes: string[];
}): string {
  const skipped = result.skippedCodes.length
    ? `，跳过 ${result.skipped} 个（编码已存在：${result.skippedCodes.join('、')}）`
    : '';
  return `已从模板 ${result.templateKey} 导入 ${result.created} 个科目${skipped}`;
}

/** 留痕的一行可读形态。 */
export function accountSubjectEventText(event: {
  eventType: string;
  fieldName: string;
  fromValue: string;
  toValue: string;
  reason: string;
}): string {
  if (!event.fieldName) return event.reason || event.eventType;
  return `${event.fieldName}：${event.fromValue || '（空）'} → ${event.toValue || '（空）'}`;
}
