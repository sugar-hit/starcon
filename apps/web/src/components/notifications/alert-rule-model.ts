/**
 * 1.3.2.1 预警规则的前端模型（**纯函数层**）。
 *
 * <p>蓝图原文：「用户自定义阈值预警（某 SKU 库存低于 N 天、某店铺销售额环比跌超 X%、
 * 应收超期 Y 天）。系统预置跨境常用规则集，用户可改参数或关闭。……
 * <b>规则引擎本体在 18 系统</b>，此处只做配置界面。」
 *
 * <p>🔴 <b>本文件是纯函数层</b>：不碰网络、不碰 React。它做四件事：
 *
 * <ol>
 *   <li><b>阈值全程用字符串</b>：契约里阈值就是十进制文本（不是 number），
 *       前端从头到尾不碰浮点 —— {@code 0.1 + 0.2 = 0.30000000000000004} 那类误差
 *       一旦漂进告警线，阈值就偏了；</li>
 *   <li><b>构造入参</b>：把表单草稿折成保存请求体（形状与服务端契约逐字一致）；</li>
 *   <li><b>校验形状</b>：阈值文本 / 词表 / 对象范围与对象标识的自洽性
 *       （服务端再拒一次，前端先挡一道是为了让用户当场看到原因）；</li>
 *   <li><b>措辞与汇总</b>：把受控码折成人话，把清单折成页面上要显示的几个数字。</li>
 * </ol>
 *
 * <p>🔴 <b>权威词表在服务端</b>（identity 的 {@code AlertRuleCapabilities}），
 * 本文件里的码表只是「前端要渲染哪几个」的清单，页面加载时会用服务端下发的词表校对。
 */

/**
 * 相对路径而不是 `@/lib/api`：根 vitest 配置没有 `@/*` 别名（那只在 Next 构建里解析），
 * 用别名会让这个模型的单测在 vite 下加载失败。与 `notification-inbox-model` 保持同一套约定。
 */
import type {
  AlertRuleEffective,
  AlertRuleSaveRequest,
  AlertRuleVocabulary,
} from '../../../../../packages/api-contracts/src/index';

/** 对象类型 → 中文名（与服务端词表逐字一致）。 */
export const OBJECT_TYPE_LABELS: Record<string, string> = {
  SKU: 'SKU',
  SHOP: '店铺',
  LEGAL_ENTITY: '法人',
  SUPPLIER: '供应商',
};

/** 时间窗 → 中文名。 */
export const TIME_WINDOW_LABELS: Record<string, string> = {
  INSTANT: '即时',
  ROLLING_7D: '滚动 7 天',
  ROLLING_30D: '滚动 30 天',
  CALENDAR_MONTH: '自然月',
};

/** 比较方向 → 中文名。 */
export const COMPARISON_LABELS: Record<string, string> = {
  LT: '低于',
  LE: '不高于',
  GT: '高于',
  GE: '不低于',
};

/** 比较基准 → 中文名。 */
export const CHANGE_BASIS_LABELS: Record<string, string> = {
  NONE: '直接比较',
  PERIOD_OVER_PERIOD: '环比',
  YEAR_OVER_YEAR: '同比',
};

/** 对象范围 → 中文名。 */
export const OBJECT_SCOPE_LABELS: Record<string, string> = {
  ALL: '所有该类型对象',
  SPECIFIC: '具体某个对象',
};

/** 时区来源 → 中文名。 */
export const TIMEZONE_SOURCE_LABELS: Record<string, string> = {
  PRESET_DEFAULT: '预置默认',
  EXPLICIT: '显式指定',
  ACCOUNT_LOCALE: '继承你的展示时区',
};

/**
 * 阈值文本形状：十进制整数或小数，最多 13 位整数 + 6 位小数（与库里的
 * {@code NUMERIC(19,6)} 对齐）。
 *
 * <p>**不收正负号、不收科学计数法**：阈值是给人看、给人改的，
 * {@code 1E+2} 合法但没人看得懂，而且它绕过了「最多 6 位小数」的直觉。
 */
export const DECIMAL_PATTERN = /^\d{1,13}(\.\d{1,6})?$/;

/** 阈值文本是否合法（与服务端的 {@code AlertRuleRules.normalizeThreshold} 同口径）。 */
export function isValidThresholdText(raw: string): boolean {
  return DECIMAL_PATTERN.test(raw.trim());
}

/** 表单草稿（一条规则的五个定义项 + 名称 / 对象范围 / 时区）。 */
export interface AlertRuleDraft {
  name: string;
  objectType: string;
  metricCode: string;
  threshold: string;
  timeWindow: string;
  comparison: string;
  changeBasis: string;
  objectScope: string;
  objectRef: string;
  timezone: string;
}

/** 新建规则时的空白草稿。 */
export function emptyDraft(): AlertRuleDraft {
  return {
    name: '',
    objectType: '',
    metricCode: '',
    threshold: '',
    timeWindow: 'INSTANT',
    comparison: 'LT',
    changeBasis: 'NONE',
    objectScope: 'ALL',
    objectRef: '',
    timezone: '',
  };
}

/**
 * 把一条**生效规则**折成可编辑的草稿。
 *
 * <p>用于「编辑」：预置规则改的是**参数覆盖**（没改的项保持预置默认），
 * 自定义规则改的是整条规则。
 */
export function draftOf(rule: AlertRuleEffective): AlertRuleDraft {
  return {
    name: rule.displayName,
    objectType: rule.objectType,
    metricCode: rule.metricCode,
    threshold: rule.threshold,
    timeWindow: rule.timeWindow,
    comparison: rule.comparison,
    changeBasis: rule.changeBasis,
    objectScope: rule.objectScope,
    objectRef: rule.objectRef ?? '',
    timezone: rule.timezone,
  };
}

/**
 * 构造保存请求体。
 *
 * <p>🔴 **阈值原样以字符串送出**：服务端那边是 {@code BigDecimal}，
 * 库那边是 {@code NUMERIC(19,6)}；中间这一段要是经过一次 JS 浮点，
 * 「用户输入的 20.5」就可能变成别的数。字符串直达服务端，一个字符都不改。
 */
export function buildSaveInput(draft: AlertRuleDraft): AlertRuleSaveRequest {
  return {
    name: draft.name.trim() || null,
    objectType: draft.objectType || null,
    metricCode: draft.metricCode || null,
    threshold: draft.threshold.trim() || null,
    timeWindow: draft.timeWindow || null,
    comparison: draft.comparison || null,
    changeBasis: draft.changeBasis || null,
    objectScope: draft.objectScope || null,
    objectRef: draft.objectRef.trim() || null,
    timezone: draft.timezone.trim() || null,
  };
}

/**
 * 草稿的形状校验（**只管形状**，业务语义由服务端再判一次）。
 *
 * @returns 不合法时给出能直接显示的原因；合法时返回 null
 */
export function validateDraft(
  draft: AlertRuleDraft,
  vocabulary: AlertRuleVocabulary | null,
): string | null {
  if (!draft.name.trim()) return '规则名不能为空';
  if (!draft.objectType) return '请选择对象类型';
  if (!vocabulary?.objectTypes.includes(draft.objectType)) return '对象类型不在受控词表里';
  if (!draft.metricCode) return '请选择度量';
  if (!vocabulary?.metrics.some((metric) => metric.code === draft.metricCode)) {
    return '度量不在受控词表里';
  }
  if (!draft.threshold.trim()) return '阈值不能为空';
  if (!isValidThresholdText(draft.threshold)) {
    return '阈值必须是十进制文本（不带正负号、不带科学计数法，最多 13 位整数 + 6 位小数）';
  }
  if (!draft.timeWindow || !vocabulary?.timeWindows.includes(draft.timeWindow)) {
    return '时间窗不在受控词表里';
  }
  if (!draft.comparison || !vocabulary?.comparisons.includes(draft.comparison)) {
    return '比较方向不在受控词表里';
  }
  if (!draft.changeBasis || !vocabulary?.changeBases.includes(draft.changeBasis)) {
    return '比较基准不在受控词表里';
  }
  if (!draft.objectScope || !vocabulary?.objectScopes.includes(draft.objectScope)) {
    return '对象范围不在受控词表里';
  }
  if (draft.objectScope === 'SPECIFIC' && !draft.objectRef.trim()) {
    return '对象范围是「具体某个对象」时必须给出对象标识';
  }
  if (draft.objectScope === 'ALL' && draft.objectRef.trim()) {
    return '对象范围是「所有该类型对象」时不能给对象标识';
  }
  return null;
}

/** 度量名（找不到就退回码本身，不假装知道）。 */
export function metricName(
  vocabulary: AlertRuleVocabulary | null,
  metricCode: string,
): string {
  return vocabulary?.metrics.find((metric) => metric.code === metricCode)?.displayName ?? metricCode;
}

/** 度量是否已被上游接通（未接通的指标建出来的规则，求值时会判「不可判定」）。 */
export function metricAvailable(
  vocabulary: AlertRuleVocabulary | null,
  metricCode: string,
): boolean {
  return vocabulary?.metrics.find((metric) => metric.code === metricCode)?.available ?? true;
}

/** 阈值短语：「低于 14 天」。 */
export function thresholdPhrase(rule: AlertRuleEffective): string {
  const comparison = COMPARISON_LABELS[rule.comparison] ?? rule.comparison;
  return `${comparison} ${rule.threshold} ${rule.unitLabel}`;
}

/** 对象范围的短语。 */
export function scopePhrase(rule: AlertRuleEffective): string {
  if (rule.objectScope === 'SPECIFIC') {
    return `具体对象 ${rule.objectRef ?? ''}`;
  }
  return OBJECT_SCOPE_LABELS[rule.objectScope] ?? rule.objectScope;
}

/** 时间窗与比较基准的短语。 */
export function windowPhrase(rule: AlertRuleEffective): string {
  const window = TIME_WINDOW_LABELS[rule.timeWindow] ?? rule.timeWindow;
  const basis = CHANGE_BASIS_LABELS[rule.changeBasis] ?? rule.changeBasis;
  if (rule.changeBasis === 'NONE') return window;
  return `${window} · ${basis}`;
}

/** 是不是预置规则。 */
export function isPreset(rule: AlertRuleEffective): boolean {
  return rule.origin === 'PRESET';
}

/** 是不是自定义规则。 */
export function isCustom(rule: AlertRuleEffective): boolean {
  return rule.origin === 'CUSTOM';
}

/**
 * 能不能「恢复默认」。
 *
 * <p>只有**预置规则**有默认值可恢复；自定义规则没有 —— 请直接删除。
 */
export function canReset(rule: AlertRuleEffective): boolean {
  return isPreset(rule);
}

/**
 * 能不能删除。
 *
 * <p>预置规则**不允许删除**（服务端也会拒，409）：它属于平台的跨境常用规则集，
 * 删掉等于让「这条规则存在过」说不清。要让它别生效请用「关闭」。
 */
export function canDelete(rule: AlertRuleEffective): boolean {
  return isCustom(rule);
}

/** 能不能改（预置规则改的是参数覆盖，自定义规则改的是整条）。 */
export function canEdit(rule: AlertRuleEffective): boolean {
  return true;
}

/** 页面上要显示的几个数字。 */
export interface AlertRuleSummary {
  /** 生效规则总数。 */
  total: number;
  /** 预置规则条数。 */
  presetCount: number;
  /** 自定义规则条数。 */
  customCount: number;
  /** 被本租户改过的条数（含改参数与关闭）。 */
  customizedCount: number;
  /** 已关闭的条数。 */
  disabledCount: number;
}

/** 把清单折成页面上要显示的几个数字。 */
export function summarizeRules(rules: readonly AlertRuleEffective[]): AlertRuleSummary {
  let presetCount = 0;
  let customCount = 0;
  let customizedCount = 0;
  let disabledCount = 0;
  for (const rule of rules) {
    if (isPreset(rule)) presetCount += 1;
    else customCount += 1;
    if (rule.customized) customizedCount += 1;
    if (!rule.enabled) disabledCount += 1;
  }
  return {
    total: rules.length,
    presetCount,
    customCount,
    customizedCount,
    disabledCount,
  };
}

/** 把清单拆成「预置」与「自定义」两段（预置在前）。 */
export function splitByOrigin(rules: readonly AlertRuleEffective[]): {
  presets: AlertRuleEffective[];
  customs: AlertRuleEffective[];
} {
  return {
    presets: rules.filter(isPreset),
    customs: rules.filter(isCustom),
  };
}

/**
 * 命中记录的分辨率短语。
 *
 * <p>🔴 **「不可判定」与「没有」严格分开**：不可判定是一条**结论**
 * （这条规则这次没法判），不是「0 次命中」。
 */
export function resolutionLabel(resolution: string): string {
  return resolution === 'UNDECIDABLE' ? '不可判定' : '命中';
}
