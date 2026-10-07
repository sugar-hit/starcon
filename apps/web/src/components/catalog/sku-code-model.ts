import type {
  SkuCodeBatchInput,
  SkuCodeBatchResult,
  SkuCodeOverview,
  SkuCodePanel,
  SkuCodeRule,
  SkuCodeRuleInput,
  SkuCodeRuleSaved,
  SkuCodeSaved,
  SkuIdentifier,
  SkuIdentifierImportInput,
  SkuIdentifierImportResult,
  SkuIdentifierRow,
  SkuIdentifierSaved,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/**
 * 3.1.2.3 编码与条码前端模型。
 *
 * <p>页面上最容易出错的一处是**把「没有」画成「是 0」**：概览上的「未编码 SKU 数」是
 * 编码治理的起点，如果把它和"编码为 0"混在一起，运营会去改本来正确的编码。
 *
 * <p>另一处是**拒绝清单必须逐行可读**：批量导入/补齐返回的每一行都带行号与原因，
 * 界面要把「哪一行、为什么」原样呈现，不能只弹一个"有 5 行失败"。
 */

// ------------------------------------------------------------ 接口

export function fetchOverview() {
  return api<SkuCodeOverview>('catalog/sku-code/overview');
}

export function fetchRules() {
  return api<SkuCodeRule[]>('catalog/sku-code/rules');
}

export function createRule(input: SkuCodeRuleInput) {
  return api<SkuCodeRuleSaved>('catalog/sku-code/rules', 'POST', input);
}

export function updateRule(id: string, input: SkuCodeRuleInput) {
  return api<SkuCodeRuleSaved>(`catalog/sku-code/rules/${id}`, 'PUT', input);
}

export function fetchPanel(skuId: string) {
  return api<SkuCodePanel>(`catalog/sku-code/skus/${skuId}`);
}

/** 手工输入内部编码。version 是乐观锁凭据，由面板原样带回。 */
export function saveCode(skuId: string, code: string, version: number) {
  return api<SkuCodeSaved>(`catalog/sku-code/skus/${skuId}/code`, 'PUT', { code, version });
}

export function addIdentifier(
  skuId: string,
  scheme: string,
  value: string,
  isPrimary: boolean,
  source: 'MANUAL' | 'SCAN',
) {
  return api<SkuIdentifierSaved>(`catalog/sku-code/skus/${skuId}/identifiers`, 'POST', {
    scheme,
    value,
    isPrimary,
    source,
    occurredAt: null,
  });
}

export function deleteIdentifier(skuId: string, identifierId: string) {
  return api<{ deleted: boolean }>(
    `catalog/sku-code/skus/${skuId}/identifiers/${identifierId}`,
    'DELETE',
  );
}

export function runBatch(input: SkuCodeBatchInput) {
  return api<SkuCodeBatchResult>('catalog/sku-code/batch', 'POST', input);
}

export function fetchBatch(id: string) {
  return api<SkuCodeBatchResult>(`catalog/sku-code/batches/${id}`);
}

export function importIdentifiers(rows: SkuIdentifierRow[], dryRun: boolean) {
  return api<SkuIdentifierImportResult>('catalog/sku-code/identifiers/import', 'POST', {
    rows,
    occurredAt: null,
    dryRun,
  });
}

// ------------------------------------------------------------ 错误语义

/** 404：记录不存在或当前角色没有访问权限——RLS 已过滤过跨租户的行，不区分两者。 */
export function isNotFound(error: unknown) {
  return error instanceof ApiError && error.status === 404;
}

/** 409：乐观锁冲突，或条码/编码唯一性冲突。界面必须说明发生了什么，不能让用户以为保存成功了。 */
export function isConflict(error: unknown) {
  return error instanceof ApiError && error.status === 409;
}

export function errorText(error: unknown, fallback = '操作失败') {
  return error instanceof Error && error.message ? error.message : fallback;
}

// ------------------------------------------------------------ 展示

export const schemeLabels: Record<string, string> = {
  EAN8: 'EAN-8',
  EAN13: 'EAN-13',
  UPCA: 'UPC-A',
  UPCE: 'UPC-E',
  GTIN14: 'GTIN-14',
  CUSTOM: '自定义',
};

export const sourceLabels: Record<string, string> = {
  MANUAL: '手工输入',
  SCAN: '扫码录入',
  IMPORT: '批量导入',
  RULE: '规则生成',
  BACKFILL: '批量补齐',
};

export const outcomeLabels: Record<string, string> = {
  APPLIED: '已生效',
  SKIPPED: '已跳过',
  REJECTED: '被拒绝',
};

export const actionLabels: Record<string, string> = {
  GENERATE: '按规则生成',
  BACKFILL: '批量补齐',
  IMPORT: '条码导入',
};

/** 条码 scheme 词表。与后端 BarcodeScheme.SCHEMES 一一对应，离线时也能校验。 */
export const schemes = ['EAN8', 'EAN13', 'UPCA', 'UPCE', 'GTIN14', 'CUSTOM'] as const;

const gtinWidth: Record<string, number> = {
  EAN8: 8,
  EAN13: 13,
  UPCA: 12,
  UPCE: 8,
  GTIN14: 14,
};

/**
 * GS1 mod-10 校验位（与后端 BarcodeScheme.expectedCheckDigit 同算法）。
 *
 * <p>前端的目的是**在扫码枪扫完的瞬间就告诉用户扫错位了**，而不是提交后等一趟往返。
 * 判错的标准是权威的（后端 + 10 物流面单），前端这里只做同口径的提前提示。
 */
export function expectedCheckDigit(dataDigits: string) {
  let sum = 0;
  for (let i = dataDigits.length - 1, weight = 3; i >= 0; i--, weight = weight === 3 ? 1 : 3) {
    sum += Number(dataDigits.charAt(i)) * weight;
  }
  return String((10 - (sum % 10)) % 10);
}

/** 前端条码校验，返回 null 表示可用。与后端 BarcodeScheme.problem 同口径。 */
export function barcodeProblem(scheme: string, value: string): string | null {
  const normalized = scheme.trim().toUpperCase();
  const trimmed = value.trim();
  if (!schemes.includes(normalized as (typeof schemes)[number]))
    return `不支持的条码 scheme：${scheme}`;
  if (trimmed === '') return '条码值不能为空';
  if (trimmed.length > 128) return `条码值有 ${trimmed.length} 位，超过 128 位上限`;
  const width = gtinWidth[normalized];
  if (width === undefined) return null;
  if (!/^\d+$/.test(trimmed)) return `${schemeLabels[normalized]} 只能由数字组成`;
  if (trimmed.length !== width)
    return `${schemeLabels[normalized]} 必须是 ${width} 位，当前 ${trimmed.length} 位`;
  if (expectedCheckDigit(trimmed.slice(0, -1)) !== trimmed.slice(-1))
    return `${schemeLabels[normalized]} 校验位不正确（末位应为 ${expectedCheckDigit(trimmed.slice(0, -1))}）`;
  return null;
}

/** 时间戳展示。后端统一给 UTC ISO 串，这里只做本地化，不改值。 */
export function timestamp(value: string | null | undefined) {
  if (!value) return '—';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN');
}

/** 主条码：同一 scheme 至多一个，界面上标出来是因为 sku.barcode 与面单打印都取它。 */
export function primaryIdentifier(identifiers: SkuIdentifier[]) {
  return identifiers.find((i) => i.isPrimary) ?? null;
}

/**
 * 批量结果的汇总句。
 *
 * <p>三个计数要一起说出来：生效 / 跳过 / 拒绝。只报"成功 N 条"会让人以为剩下的都消失了，
 * 而跳过与拒绝的原因往往完全不同（前者是范围外，后者是冲突）。
 */
export function batchSummary(result: SkuCodeBatchResult) {
  const parts = [`生效 ${result.appliedCount} 条`];
  if (result.skippedCount > 0) parts.push(`跳过 ${result.skippedCount} 条`);
  if (result.rejectedCount > 0) parts.push(`拒绝 ${result.rejectedCount} 条`);
  return parts.join(' · ');
}

export function importSummary(result: SkuIdentifierImportResult) {
  const parts = [`导入 ${result.appliedCount} 条`];
  if (result.rejectedCount > 0) parts.push(`拒绝 ${result.rejectedCount} 条`);
  return parts.join(' · ');
}

/** 规则草稿：摊平成表单可用的字符串。 */
export type RuleDraft = {
  code: string;
  name: string;
  description: string;
  scope: 'ALL' | 'SPU';
  spuId: string;
  prefix: string;
  suffix: string;
  seqWidth: string;
  startFrom: string;
  enabled: boolean;
  version: number;
};

export function emptyRuleDraft(): RuleDraft {
  return {
    code: '',
    name: '',
    description: '',
    scope: 'ALL',
    spuId: '',
    prefix: '',
    suffix: '',
    seqWidth: '6',
    startFrom: '1',
    enabled: true,
    version: 0,
  };
}

export function ruleDraft(rule: SkuCodeRule): RuleDraft {
  return {
    code: rule.code,
    name: rule.name,
    description: rule.description,
    scope: rule.scope === 'SPU' ? 'SPU' : 'ALL',
    spuId: rule.spuId ?? '',
    prefix: rule.prefix,
    suffix: rule.suffix,
    seqWidth: String(rule.seqWidth),
    startFrom: String(rule.startFrom),
    enabled: rule.enabled,
    version: rule.version,
  };
}

/** 内部编码的字符集，与后端 CatalogService.code 一致：英文字母、数字、下划线、短横线。 */
const CODE_PATTERN = /^[A-Z0-9][A-Z0-9_-]{0,63}$/;

/**
 * 规则表单校验。
 *
 * <p>展开长度（prefix + 序号宽度 + suffix ≤ 64）在这里先挡一次：等批量生成 500 个 SKU
 * 之后才发现编码越界，那时已经写进去多少无从追溯。
 */
export function validateRule(draft: RuleDraft): string[] {
  const errors: string[] = [];
  const code = draft.code.trim().toUpperCase();
  if (code === '') errors.push('规则编码不能为空');
  else if (!CODE_PATTERN.test(code)) errors.push('规则编码只能使用英文字母、数字、下划线或短横线');
  if (draft.name.trim() === '') errors.push('规则名称不能为空');
  if (draft.name.length > 100) errors.push('规则名称最多 100 个字符');
  if (draft.scope === 'SPU' && draft.spuId.trim() === '')
    errors.push('限定商品族的规则必须指定 SPU');

  const width = Number(draft.seqWidth);
  if (!/^\d+$/.test(draft.seqWidth.trim()) || width < 1 || width > 12)
    errors.push('序号宽度需在 1~12 之间');
  const startFrom = Number(draft.startFrom);
  if (!/^\d+$/.test(draft.startFrom.trim()) || startFrom < 1) errors.push('起始序号必须大于等于 1');

  for (const [label, value] of [
    ['编码前缀', draft.prefix],
    ['编码后缀', draft.suffix],
  ] as const) {
    if (value.length > 32) errors.push(`${label}最多 32 个字符`);
    if (value !== '' && !/^[A-Za-z0-9_-]+$/.test(value))
      errors.push(`${label}只能使用英文字母、数字、下划线或短横线`);
  }
  if (!errors.some((e) => e.includes('序号宽度'))) {
    const worst = draft.prefix.trim().length + width + draft.suffix.trim().length;
    if (worst > 64)
      errors.push(`编码展开后最长 ${worst} 个字符，超过 64 位上限，请缩短前后缀或减小序号宽度`);
  }
  return errors;
}

/** 草稿 → 请求体。version 原样带上：这是乐观锁凭据，不能由前端自己算。 */
export function draftToRuleInput(draft: RuleDraft): SkuCodeRuleInput {
  return {
    code: draft.code.trim().toUpperCase(),
    name: draft.name.trim(),
    description: draft.description,
    scope: draft.scope,
    spuId: draft.scope === 'SPU' && draft.spuId.trim() !== '' ? draft.spuId.trim() : null,
    prefix: draft.prefix.trim().toUpperCase(),
    suffix: draft.suffix.trim().toUpperCase(),
    seqWidth: Number(draft.seqWidth),
    startFrom: Number(draft.startFrom),
    enabled: draft.enabled,
    version: draft.version,
  };
}

/**
 * 粘贴解析：把多行文本拆成导入行。
 *
 * <p>运营手里的条码表通常来自供应商 Excel，格式是 `sku编码<TAB>scheme<TAB>value`。
 * 这里逐行解析并把无法解析的行**原样保留成 REJECTED 预览**，让用户在提交前就看到
 * 哪几行格式不对——比提交后等一趟往返再改要快得多。
 */
export function parseImportText(text: string, resolveSkuId: (code: string) => string | null) {
  const rows: SkuIdentifierRow[] = [];
  const problems: { line: number; text: string; reason: string }[] = [];
  const lines = text.split(/\r?\n/);
  lines.forEach((raw, index) => {
    const line = raw.trim();
    if (line === '' || line.startsWith('#')) return;
    const lineNo = index + 1;
    const parts = line.split(/[\t,;]+|\s{2,}/).map((p) => p.trim()).filter((p) => p !== '');
    if (parts.length < 3) {
      problems.push({ line: lineNo, text: line, reason: '需要「SKU 编码 / scheme / 条码值」三列' });
      return;
    }
    // parts 长度已判定 ≥3，但 noUncheckedIndexedAccess 下解构仍是 string | undefined，
    // 这里显式兜住而不是靠 as 断言糊过去——漏一列的代价是把空串当成条码值提交。
    const skuCode = parts[0] ?? '';
    const scheme = parts[1] ?? '';
    const value = parts[2] ?? '';
    const primary = parts[3];
    const skuId = resolveSkuId(skuCode);
    if (skuId === null) {
      problems.push({ line: lineNo, text: line, reason: `找不到 SKU 编码 ${skuCode}` });
      return;
    }
    const normalized = scheme.toUpperCase();
    const problem = barcodeProblem(normalized, value);
    if (problem !== null) {
      problems.push({ line: lineNo, text: line, reason: problem });
      return;
    }
    rows.push({ skuId, scheme: normalized, value, isPrimary: /^(1|true|是)$/i.test(primary ?? ''), occurredAt: null });
  });
  return { rows, problems };
}