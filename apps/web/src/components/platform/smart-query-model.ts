/**
 * 18.4.1.2「智能查询」的**口径层**（纯函数，无副作用、无 React）。
 *
 * 蓝图 18-integration-and-automation.md 18.4.1.2 原文：
 *
 * > 职责：结构化智能查询：自然语言转查询条件（筛选、聚合、排序），生成报表草稿，
 * > 支持保存为常用查询。输入：自然语言查询意图；可用指标与维度清单。
 * > 输出：查询结果表 / 报表配置草稿。关系：上游 = 18.4.1.1；下游 = 13.6.1.1 自定义报表。
 * > **自然语言只映射到受控语义层指标，不生成任意 SQL。**
 *
 * 页面上的七条纪律：
 *
 * 1. **解析不出来就不显示结果**：后端 `resolved=false` 时，页面显示
 *    「没有得出结论 + 缺什么 + 归谁」，**不显示任何像样的表格**。
 * 2. **没有明细依据的结果不许显示成结果**：后端说有行却没给依据（不该发生），
 *    页面按「没有依据」处理 —— 页面上宁可说「没有依据」，也不显示无从追溯的数字。
 * 3. **数值全程十进制文本**：后端给的是 `NUMERIC` 十进制字符串，前端不转 `number`
 *    （转成 number 会掉精度，而金额与比率是判定口径）。
 * 4. **算不出来 ≠ 0**：后端给 `null` 的取值显示「—」，绝不画成 0。
 * 5. **比率已经是百分数**：13.1.1.1 的 `PERCENT` 口径是**百分数**（25.0000 = 25%），
 *    前端**只补 % 不乘 100** —— 乘一次就变成了 2500%。
 * 6. **口径与时间窗口常驻显示**：结果卡片上永远带着 caliber 与窗口。
 * 7. **建议的下一步只显示后端给的**：后端没给就不显示（前端不自己编路由）。
 */

/** 语气（页面上的 Badge tone）。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

// ================================================================ 传输模型

/** 受控语义层里的一个指标。 */
export interface SmartQueryMetricView {
  metricCode: string;
  displayName: string;
  unit: string;
  aggregation: string;
  currencyAware: boolean;
  strongKeywords: string[];
  weakKeywords: string[];
  sourceService: string;
  sourceEndpoint: string;
  sourceCaliber: string;
  detailRoute: string;
  ownerModule: string;
  available: boolean;
  unavailableReason: string;
}

/** 受控语义层里的一个维度。 */
export interface SmartQueryDimensionView {
  dimensionCode: string;
  displayName: string;
  valueKind: string;
  allowedOperators: string[];
  strongKeywords: string[];
  weakKeywords: string[];
  ownerModule: string;
  available: boolean;
  unavailableReason: string;
}

/** 一个「还没接通」的下游 / 兄弟能力。 */
export interface SmartQueryGap {
  moduleId: string;
  name: string;
  service: string;
  available: boolean;
  relation: string;
  note: string;
}

/** 受控语义层目录总览（**这是「能问什么」的唯一真相**）。 */
export interface SmartQuerySemanticLayer {
  metrics: SmartQueryMetricView[];
  dimensions: SmartQueryDimensionView[];
  rules: string[];
  gaps: SmartQueryGap[];
}

/** 能力声明里的一项。 */
export interface SmartQueryCapability {
  moduleId: string;
  name: string;
  available: boolean;
  evidence: string;
  ownerModule: string;
  note: string;
}

/** 能力声明总览。 */
export interface SmartQueryCapabilities {
  capabilities: SmartQueryCapability[];
  gaps: SmartQueryGap[];
}

/** 一条筛选条件（**词表里的码 + 绑定值**，不是 SQL 片段）。 */
export interface SmartQueryFilter {
  dimensionCode: string;
  operator: string;
  valueText: string;
  valueText2: string;
}

/** 排序。 */
export interface SmartQuerySort {
  by: string;
  direction: string;
}

/** 解析出来的**受控查询条件**。 */
export interface SmartQueryConditions {
  metricCode: string;
  metricName: string;
  unit: string;
  aggregation: string;
  currencyAware: boolean;
  dimensionCode: string;
  dimensionName: string;
  filters: SmartQueryFilter[];
  sort: SmartQuerySort;
  periodCode: string;
  periodFrom: string;
  periodTo: string;
  caliber: string;
}

/** 一个维度列。 */
export interface SmartQueryDimensionColumn {
  dimensionCode: string;
  label: string;
}

/** 一个度量列。 */
export interface SmartQueryMetricColumn {
  metricCode: string;
  displayName: string;
  unit: string;
  currencyAware: boolean;
  description: string;
}

/** 一个维度取值。 */
export interface SmartQueryDimensionValue {
  dimensionCode: string;
  label: string;
  value: string;
}

/** 一个币种上的取值（**十进制文本**；空串 = 与货币无关的跨币种合计）。 */
export interface SmartQueryCurrencyValue {
  currency: string;
  value: string | null;
  availability: string;
  availabilityNote: string;
}

/** 一个度量的取值。 */
export interface SmartQueryMeasureValue {
  metricCode: string;
  displayName: string;
  unit: string;
  currencyAware: boolean;
  values: SmartQueryCurrencyValue[];
  availability: string;
  availabilityNote: string;
}

/** 结果表的一行 = 一个维度组合。 */
export interface SmartQueryResultRow {
  seq: number;
  dimensions: SmartQueryDimensionValue[];
  measures: SmartQueryMeasureValue[];
}

/** 一条明细依据（**可下钻**的事实）。 */
export interface SmartQueryEvidence {
  factService: string;
  factTable: string;
  factField: string;
  factRef: string;
  factLabel: string;
  metricCode: string;
  metricValue: string | null;
  metricUnit: string;
  detailRoute: string;
  detailLabel: string;
  caliberNote: string;
  snapshotAt: string;
}

/** 建议的下一步操作（**只显示后端给的**）。 */
export interface SmartQueryNextAction {
  label: string;
  route: string;
  moduleId: string;
}

/** 一次智能查询的结果。 */
export interface SmartQueryResult {
  queryId: string;
  question: string;
  matchMode: string;
  resolved: boolean;
  conditions: SmartQueryConditions | null;
  reportId: string;
  reportCode: string;
  dimensionColumns: SmartQueryDimensionColumn[];
  metricColumns: SmartQueryMetricColumn[];
  rows: SmartQueryResultRow[];
  total: number;
  page: number;
  size: number;
  status: string;
  statusNote: string;
  dataUpdatedAt: string | null;
  dataUpdatedNote: string;
  caliberNotes: string[];
  evidence: SmartQueryEvidence[];
  nextActions: SmartQueryNextAction[];
  unavailableReason: string;
  ownerModule: string;
}

/** 查询台账 / 常用查询清单里的一行。 */
export interface SmartQueryQueryView {
  queryId: string;
  ownerUserId: string;
  question: string;
  displayName: string;
  saved: boolean;
  matchMode: string;
  resolved: boolean;
  metricCode: string;
  metricName: string;
  dimensionCode: string;
  dimensionName: string;
  periodCode: string;
  periodFrom: string;
  periodTo: string;
  reportId: string;
  reportCode: string;
  unavailableReason: string;
  ownerModule: string;
  evidenceCount: number;
  createdAt: string;
  updatedAt: string;
}

/** 查询台账 / 常用查询清单。 */
export interface SmartQueryPage {
  rows: SmartQueryQueryView[];
  total: number;
  page: number;
  size: number;
  keyword: string;
  saved: boolean | null;
}

// ================================================================ 展示口径

/** 命中方式 → 中文标签。 */
export function matchModeLabel(mode: string): string {
  switch (mode) {
    case 'KEYWORD':
      return '命中受控词表';
    case 'EXPLICIT':
      return '显式指定指标 / 维度';
    default:
      return mode || '未知';
  }
}

/** 命中方式 → 语气。 */
export function matchModeTone(mode: string): Tone {
  switch (mode) {
    case 'KEYWORD':
      return 'ok';
    case 'EXPLICIT':
      return 'warn';
    default:
      return 'muted';
  }
}

/** 时间范围代号 → 中文标签。 */
export function periodLabel(code: string): string {
  switch (code) {
    case 'LAST_7_DAYS':
      return '最近 7 天';
    case 'LAST_WEEK':
      return '上周（自然周）';
    case 'THIS_MONTH':
      return '本月（自然月至今）';
    case 'LAST_30_DAYS':
      return '最近 30 天';
    default:
      return code || '未指定';
  }
}

/** 单位码 → 中文标签（金额的单位是币种，由调用处给出）。 */
export function unitLabel(unit: string): string {
  switch (unit) {
    case 'CURRENCY':
      return '金额';
    case 'COUNT':
      return '笔数';
    case 'QUANTITY':
      return '数量';
    case 'PERCENT':
      return '百分比';
    default:
      return unit || '';
  }
}

/** 聚合方式 → 中文标签。 */
export function aggregationLabel(aggregation: string): string {
  switch (aggregation) {
    case 'SUM':
      return '求和';
    case 'LAST':
      return '取期末余额（不能累加）';
    case 'DERIVED_DIFF':
      return '相减（派生）';
    case 'DERIVED_RATIO':
      return '相除（派生）';
    default:
      return aggregation || '';
  }
}

/** 运算符 → 中文标签（13.6.1.1 的同一套白名单）。 */
export function operatorLabel(operator: string): string {
  switch (operator) {
    case 'EQ':
      return '等于';
    case 'IN':
      return '属于';
    case 'GTE':
      return '不早于';
    case 'LTE':
      return '不晚于';
    case 'BETWEEN':
      return '介于';
    default:
      return operator || '';
  }
}

/** 维度取值形态 → 中文标签。 */
export function valueKindLabel(valueKind: string): string {
  switch (valueKind) {
    case 'TEXT':
      return '文本';
    case 'UUID':
      return 'uuid（自然语言里没有 ⇒ 只能显式传）';
    case 'DATE':
      return '日期';
    default:
      return valueKind || '';
  }
}

/**
 * 十进制取值 → 展示文本。
 *
 * ⚠️ **不转 number**：后端给的是 `NUMERIC` 十进制字符串，转成 number 会掉精度
 * （金额与比率是判定口径）。
 *
 * @param value `null` = **算不出来**（显示「—」，不是 0）
 */
export function formatMetric(value: string | null | undefined, unit?: string): string {
  if (value === null || value === undefined || value === '') return '—';
  const text = value.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
  return unit ? `${text} ${unit}` : text;
}

/**
 * 比率 → 百分数文本。
 *
 * 🔴 **只补 % 不乘 100**：13.1.1.1 的 `PERCENT` 口径是**百分数**
 * （服务端算的是 `ratio × 100`，`25.0000` 就是 25%）。再乘一次就变成 2500%。
 */
export function formatPercent(value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const text = value.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
  return `${text}%`;
}

/**
 * 一个取值怎么显示（按单位口径分派）。
 *
 * @param unit 后端登记的单位（CURRENCY / COUNT / QUANTITY / PERCENT）
 */
export function formatValue(
  value: string | null | undefined,
  unit: string,
  currency?: string,
): string {
  if (value === null || value === undefined || value === '') return '—';
  if (unit === 'PERCENT') return formatPercent(value);
  if (unit === 'CURRENCY') return formatMetric(value, currency);
  return formatMetric(value);
}

/**
 * 明细链接是否是**真实存在**的站内路由。
 *
 * ⚠️ 后端给什么前端就显示什么，但**协议相对 / 外链一律不当作站内明细** ——
 * 明细链接必须能点回系统里的那一页。
 */
export function isInternalRoute(route: string): boolean {
  return typeof route === 'string' && route.startsWith('/') && !route.startsWith('//');
}

/** 一条筛选条件 → 可读文本。 */
export function filterLabel(filter: SmartQueryFilter): string {
  if (!filter.dimensionCode) return '';
  const op = operatorLabel(filter.operator);
  if (filter.operator === 'BETWEEN') {
    return `${filter.dimensionCode} ${op} ${filter.valueText} ~ ${filter.valueText2}`;
  }
  return `${filter.dimensionCode} ${op} ${filter.valueText}`;
}

/** 受控查询条件 → 一行可读摘要。 */
export function conditionsSummary(conditions: SmartQueryConditions | null): string {
  if (!conditions) return '';
  const parts = [
    `指标 ${conditions.metricName}（${conditions.metricCode}）`,
    conditions.dimensionCode
      ? `维度 ${conditions.dimensionName}（${conditions.dimensionCode}）`
      : '公司口径（不分组）',
    `${aggregationLabel(conditions.aggregation)}`,
    `窗口 ${conditions.periodFrom} ~ ${conditions.periodTo}（${periodLabel(conditions.periodCode)}）`,
    `排序 ${
      conditions.sort.by === 'DIMENSION' ? '按维度值' : '按指标值'
    }（${conditions.sort.direction === 'ASC' ? '从低到高' : '从高到低'}）`,
  ];
  return parts.join(' · ');
}

/** 事实定位的可读描述（服务 / 真表名 / 真字段名）。 */
export function factLocationLabel(evidence: SmartQueryEvidence): string {
  return `${evidence.factService} · ${evidence.factTable} · ${evidence.factField}`;
}

/** 明细链接的可点文案。 */
export function detailLinkLabel(evidence: SmartQueryEvidence): string {
  return evidence.detailLabel || '打开明细';
}

/** 事实引用（页面上一行小字）。 */
export function factRefLabel(evidence: SmartQueryEvidence): string {
  return evidence.factRef ? `引用 ${evidence.factRef}` : '';
}

/** 台账里的一行摘要。 */
export function querySummary(view: SmartQueryQueryView): string {
  if (!view.resolved) return view.unavailableReason || '没有得出结论';
  const dimension = view.dimensionCode ? view.dimensionName : '公司口径';
  return `${view.metricName} · ${dimension} · ${periodLabel(view.periodCode)}`;
}

// ================================================================ 结果视图

/**
 * 一次查询的展示形态。
 *
 * @param resolved 这一轮**出没出结论**
 * @param rows 结果表（**没有依据时一定是空数组**）
 * @param evidence 明细依据（**没有依据时一定是空数组**）
 */
export interface ResultView {
  resolved: boolean;
  headline: string;
  tone: Tone;
  conditions: SmartQueryConditions | null;
  rows: SmartQueryResultRow[];
  evidence: SmartQueryEvidence[];
  nextActions: SmartQueryNextAction[];
  unavailableReason: string;
  ownerModule: string;
}

/**
 * 把后端结果整理成展示形态。
 *
 * 🔴 **结构性保证**（三条）：
 *
 * 1. `resolved=false` ⇒ `rows` 与 `evidence` 都是空的 —— 没解析出来就没有数字；
 * 2. `resolved=true` 但**没有明细依据** ⇒ 按「没出结论」处理 ——
 *    页面上宁可说「没有依据」，也不显示一个无从追溯的数字；
 * 3. `resolved=true` 且**一行都没有** ⇒ 这是「查到了，但这个窗口没有数据」，
 *    与「没解析出来」是**两件事** —— 不能混为一谈。
 */
export function toResultView(result: SmartQueryResult | null): ResultView {
  if (!result) {
    return {
      resolved: false,
      headline: '还没有提问',
      tone: 'muted',
      conditions: null,
      rows: [],
      evidence: [],
      nextActions: [],
      unavailableReason: '',
      ownerModule: '',
    };
  }
  const evidence = result.evidence.filter((item) => isInternalRoute(item.detailRoute));
  const nextActions = result.nextActions.filter((action) => isInternalRoute(action.route));

  if (!result.resolved) {
    return {
      resolved: false,
      headline: result.unavailableReason || '这一轮没有得出结论',
      tone: 'warn',
      conditions: null,
      rows: [],
      evidence: [],
      nextActions,
      unavailableReason: result.unavailableReason,
      ownerModule: result.ownerModule,
    };
  }
  if (result.rows.length > 0 && evidence.length === 0) {
    return {
      resolved: false,
      headline: '后端说有结果，但没给明细依据 —— 这批数字不显示（没有依据的数字不能用于决策）',
      tone: 'danger',
      conditions: result.conditions,
      rows: [],
      evidence: [],
      nextActions,
      unavailableReason: '缺少明细依据',
      ownerModule: result.ownerModule,
    };
  }
  if (result.rows.length === 0) {
    return {
      resolved: true,
      headline: '查到了，但这个窗口没有数据（不是「没解析出来」）',
      tone: 'muted',
      conditions: result.conditions,
      rows: [],
      evidence: [],
      nextActions,
      unavailableReason: '',
      ownerModule: '',
    };
  }
  return {
    resolved: true,
    headline: `共 ${result.total} 行`,
    tone: 'ok',
    conditions: result.conditions,
    rows: result.rows,
    evidence,
    nextActions,
    unavailableReason: '',
    ownerModule: '',
  };
}

/** 一个度量取值在表格里的展示文本（**多币种逐个列出，不跨币种相加**）。 */
export function measureCellText(measure: SmartQueryMeasureValue): string {
  if (!measure.values.length) return '—';
  return measure.values
    .map((value) =>
      value.currency
        ? `${formatValue(value.value, measure.unit, value.currency)}`
        : formatValue(value.value, measure.unit),
    )
    .join(' / ');
}

/** 一个度量取值的可用性说明（算不出来时说明为什么）。 */
export function measureAvailabilityNote(measure: SmartQueryMeasureValue): string {
  const notes = measure.values
    .map((value) => value.availabilityNote)
    .filter((note) => note.trim() !== '');
  if (notes.length) return notes.join('；');
  if (measure.availabilityNote.trim()) return measure.availabilityNote;
  if (measure.availability && measure.availability !== 'AVAILABLE') return measure.availability;
  return '';
}
