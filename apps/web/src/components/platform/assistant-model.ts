/**
 * 18.4.1.1「对话助手」的**口径层**（纯函数，无副作用、无 React）。
 *
 * 蓝图 18-integration-and-automation.md 18.4.1.1 原文：
 *
 * > 职责：自然语言助手：用中文提问业务问题（"上周哪个店利润最差""哪些 SKU 该补货了"），
 * > 返回答案并附数据来源链接；支持追问与上下文延续。输入：用户问题；当前上下文（租户、
 * > 数据范围、当前页面）。输出：答案 + 数据明细链接 + 建议的下一步操作。
 * > **答案必须附明细链接**——不给依据的 AI 数字不能用于决策。
 *
 * 页面上的六条纪律：
 *
 * 1. **没有明细链接的答案不许显示成答案**：后端 `answered=false` 时，页面显示
 *    「没有得出结论 + 为什么」，**不显示一个像样的答案**。
 * 2. **数值全程十进制文本**：后端给的是 `NUMERIC` 十进制字符串，前端不转 `number`
 *    （转成 number 会掉精度，而金额是判定口径）。
 * 3. **算不出来 ≠ 0**：后端给 `null` 的取值显示「—」，绝不画成 0。
 * 4. **口径与时间范围常驻显示**：答案卡片上永远带着 caliber 与窗口。
 * 5. **建议的下一步只显示后端给的**：后端没给就不显示（前端不自己编路由）。
 * 6. **追问沿用会话**：追问时带上 `sessionId`，后端沿用上一轮的意图与口径。
 */

/** 一条明细依据（可下钻的事实）。 */
export interface AssistantEvidence {
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

/** 建议的下一步操作（**只显示后端给的**，前端不自己造路由）。 */
export interface AssistantNextAction {
  label: string;
  route: string;
  moduleId: string;
}

/** 一次提问的答案。 */
export interface AssistantAnswer {
  sessionId: string;
  turnId: string;
  turnNo: number;
  question: string;
  matchMode: 'KEYWORD' | 'FOLLOW_UP' | 'FREE_TEXT';
  intentCode: string;
  intentName: string;
  answered: boolean;
  answer: string;
  caliber: string;
  periodCode: string;
  periodFrom: string;
  periodTo: string;
  scopeType: string;
  shopCode: string;
  sourceService: string;
  sourceEndpoint: string;
  evidence: AssistantEvidence[];
  nextActions: AssistantNextAction[];
  unavailableReason: string;
  ownerModule: string;
}

/** 受控意图目录里的一条。 */
export interface AssistantIntentView {
  intentCode: string;
  intentName: string;
  questionExamples: string[];
  sourceService: string;
  sourceEndpoint: string;
  sourceCaliber: string;
  detailRoute: string;
  nextActions: AssistantNextAction[];
}

/** 能力声明里的一项。 */
export interface AssistantCapability {
  moduleId: string;
  name: string;
  available: boolean;
  evidence: string;
  ownerModule: string;
  note: string;
}

/** 能力声明总览。 */
export interface AssistantCapabilities {
  capabilities: AssistantCapability[];
  gaps: Array<{
    moduleId: string;
    name: string;
    service: string;
    available: boolean;
    relation: string;
    note: string;
  }>;
}

/** 一轮问答（会话历史里的一行）。 */
export interface AssistantTurnView {
  turnId: string;
  turnNo: number;
  question: string;
  matchMode: 'KEYWORD' | 'FOLLOW_UP' | 'FREE_TEXT';
  intentCode: string;
  intentName: string;
  answered: boolean;
  answer: string;
  caliber: string;
  periodFrom: string;
  periodTo: string;
  createdAt: string;
  evidence: AssistantEvidence[];
}

/** 会话上下文 + 问答历史。 */
export interface AssistantSessionView {
  sessionId: string;
  intentCode: string;
  intentName: string;
  shopCode: string;
  scopeType: string;
  periodCode: string;
  periodFrom: string;
  periodTo: string;
  turnCount: number;
  turns: AssistantTurnView[];
}

/** 语气（页面上的 Badge tone）。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

/** 命中方式 → 中文标签。 */
export function matchModeLabel(mode: string): string {
  switch (mode) {
    case 'KEYWORD':
      return '命中受控意图';
    case 'FOLLOW_UP':
      return '追问（沿用上一轮）';
    case 'FREE_TEXT':
      return '自由问句';
    default:
      return mode || '未知';
  }
}

/** 命中方式 → 语气。 */
export function matchModeTone(mode: string): Tone {
  switch (mode) {
    case 'KEYWORD':
      return 'ok';
    case 'FOLLOW_UP':
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
      return '本月';
    case 'LAST_30_DAYS':
      return '最近 30 天';
    default:
      return code || '未指定';
  }
}

/**
 * 十进制取值 → 展示文本。
 *
 * ⚠️ **不转 number**：`metricValue` 是后端给的 `NUMERIC` 十进制字符串，
 * 转成 number 会掉精度（金额是判定口径）。
 *
 * @param value `null` = **算不出来**（显示「—」，不是 0）
 */
export function formatMetric(value: string | null | undefined, unit?: string): string {
  if (value === null || value === undefined || value === '') return '—';
  const text = value.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
  return unit ? `${text} ${unit}` : text;
}

/** 比率（0.8 → 80.00%）—— 字符串移位，**不碰浮点**。 */
export function formatRatio(value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(value.trim());
  if (!match) return value;
  const [, sign, whole, fraction = ''] = match;
  const padded = (fraction + '00').slice(0, 4);
  const scaledWhole = Number(whole) * 100 + Number(padded.slice(0, 2));
  const rest = padded.slice(2);
  return `${sign}${scaledWhole}.${rest}%`;
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

/** 答案的展示形态：把「有没有结论」与「有没有依据」分开说。 */
export interface AnswerView {
  /** 是不是一条**带依据的**答案。 */
  answered: boolean;
  /** 页面主标题。 */
  headline: string;
  /** 语气。 */
  tone: Tone;
  /** 口径与时间范围（没有就空串）。 */
  caliber: string;
  /** 明细依据（**没有依据的答案这里一定是空数组**）。 */
  evidence: AssistantEvidence[];
  /** 建议的下一步。 */
  nextActions: AssistantNextAction[];
}

/**
 * 把答案整理成展示形态。
 *
 * 🔴 **结构性保证**：`answered=true` ⇒ evidence 非空。
 * 后端没给依据却标了 answered（不该发生），这里也按「没出结论」处理 ——
 * 页面上宁可说「没有依据」，也不显示一个无从追溯的数字。
 */
export function toAnswerView(answer: AssistantAnswer | null): AnswerView {
  if (!answer) {
    return {
      answered: false,
      headline: '还没有提问',
      tone: 'muted',
      caliber: '',
      evidence: [],
      nextActions: [],
    };
  }
  const evidence = answer.evidence.filter((item) => isInternalRoute(item.detailRoute));
  if (!answer.answered || evidence.length === 0) {
    return {
      answered: false,
      headline: answer.unavailableReason || answer.answer || '这一轮没有得出结论',
      tone: 'warn',
      caliber: answer.caliber,
      evidence: [],
      nextActions: answer.nextActions.filter((action) => isInternalRoute(action.route)),
    };
  }
  return {
    answered: true,
    headline: answer.answer,
    tone: 'ok',
    caliber: answer.caliber,
    evidence,
    nextActions: answer.nextActions.filter((action) => isInternalRoute(action.route)),
  };
}

/** 事实定位的可读描述（服务 / 真表名 / 真字段名）。 */
export function factLocationLabel(evidence: AssistantEvidence): string {
  return `${evidence.factService} · ${evidence.factTable} · ${evidence.factField}`;
}

/** 明细链接的可点文案。 */
export function detailLinkLabel(evidence: AssistantEvidence): string {
  return evidence.detailLabel || '打开明细';
}

/** 事实引用（页面上一行小字）。 */
export function factRefLabel(evidence: AssistantEvidence): string {
  return evidence.factRef ? `引用 ${evidence.factRef}` : '';
}

/** 一句话概括这轮问答（会话历史里用）。 */
export function turnSummary(turn: AssistantTurnView): string {
  return turn.answered ? turn.answer : turn.answer || '没有得出结论';
}
