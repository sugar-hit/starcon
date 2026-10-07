/**
 * 17.5.1.2 编号规则的展示口径与前端预校验。
 *
 * <p>后端 {@code NumberRuleController} 是最终判定（冲突校验、嵌套环、段形状都在服务端），
 * 这里的校验与展示同口径，目的是让用户在提交前就看到"为什么不行"，而不是提交后吃一个 400。
 * 后端仍然是权威：即使前端校验放过，后端也会拒。
 *
 * <p>两条蓝图硬要求在两侧必须同时改，否则界面上说的和后端算的不是一回事：
 *
 * <ul>
 *   <li><b>序号按实体/日期重置</b>：{@link resetKey} 与后端 {@code NumberRuleController.resetKey}
 *       同一套口径（NONE→ALL、DAILY→yyyy-MM-dd、MONTHLY→yyyy-MM、YEARLY→yyyy）。
 *       注意分桶用的是**业务日期**而不是"今天"——补录上周的单据要落上周的桶。
 *   <li><b>规则冲突要在配置时校验</b>：{@link findConflicts} 与后端 {@code overlaps} 同一套
 *       半开区间判定，配置页在保存前就能标红"这两条规则的生效区间撞了"。
 * </ul>
 */

export type SegmentType = 'PREFIX' | 'DATE' | 'SEQUENCE' | 'SUFFIX' | 'LITERAL' | 'NESTED';
export type RuleKind = 'PRIMARY' | 'COMPONENT';
export type ResetScope = 'NONE' | 'DAILY' | 'MONTHLY' | 'YEARLY';
export type SequenceScope = 'ENTITY' | 'RULE';

export const SEGMENT_TYPE_LABELS: Record<string, string> = {
  PREFIX: '前缀',
  DATE: '日期',
  SEQUENCE: '序号',
  SUFFIX: '后缀',
  LITERAL: '固定文本',
  NESTED: '嵌套子规则',
};

export const RULE_KIND_LABELS: Record<string, string> = {
  PRIMARY: '主规则',
  COMPONENT: '可复用子规则',
};

export const STATUS_LABELS: Record<string, string> = {
  ACTIVE: '启用',
  DISABLED: '停用',
};

export const RESET_SCOPE_LABELS: Record<string, string> = {
  NONE: '不重置',
  DAILY: '按日重置',
  MONTHLY: '按月重置',
  YEARLY: '按年重置',
};

export const SEQUENCE_SCOPE_LABELS: Record<string, string> = {
  ENTITY: '按实体连续',
  RULE: '按规则重头',
};

export const DATE_FORMAT_LABELS: Record<string, string> = {
  yyyyMMdd: '20261007',
  yyyyMM: '202610',
  yyyy: '2026',
  yyMMdd: '261007',
  MMdd: '1007',
  'yyyy-MM-dd': '2026-10-07',
};

/** 日期格式白名单，必须与后端 {@code validateSegment} 与库里的 CHECK 一致。 */
export const DATE_FORMATS = Object.keys(DATE_FORMAT_LABELS);

/** 规则里一个段的最小形状。 */
export type RuleSegment = {
  id?: string;
  segmentType: string;
  literalText?: string | null;
  dateFormat?: string | null;
  sequenceWidth?: number | null;
  sequenceStart?: number | null;
  sequenceReset?: string | null;
  sequenceScope?: string | null;
  childRuleId?: string | null;
  childRuleName?: string | null;
  rendered?: string | null;
};

/** 规则的最小形状。 */
export type RuleRow = {
  id: string;
  entityCode: string;
  entityName?: string | null;
  ruleName: string;
  ruleKind: string;
  status: string;
  timezone?: string;
  effectiveFrom?: string | null;
  effectiveTo?: string | null;
  sampleNumber?: string;
  segments?: RuleSegment[];
};

/**
 * 重置分桶键，与后端 {@code NumberRuleController.resetKey} 同一套口径。
 *
 * <p>用**业务日期**而不是"今天"：补录一张上周的订单时，它应当落进上周的桶接着编号，
 * 而不是插到今天的序号中间把当天的号全顶开。
 */
export function resetKey(scope: string, isoDate: string): string {
  const date = isoDate.slice(0, 10);
  const year = date.slice(0, 4);
  const month = date.slice(5, 7);
  const day = date.slice(8, 10);
  switch (scope) {
    case 'DAILY':
      return date;
    case 'MONTHLY':
      return `${year}-${month}`;
    case 'YEARLY':
      return year;
    default:
      return 'ALL';
  }
}

/**
 * 两个生效区间是否交叠（半开区间，{@code effectiveTo = null} 当作无穷远）。
 *
 * <p>与后端 {@code NumberRuleController.overlaps} 同口径：相邻（前者 to 等于后者 from）
 * **不算**交叠——前一条到点失效、后一条接上生效是正常配置。
 */
export function intervalsOverlap(
  aFrom: string | null | undefined,
  aTo: string | null | undefined,
  bFrom: string | null | undefined,
  bTo: string | null | undefined,
): boolean {
  const from = aFrom ? Date.parse(aFrom) : Number.NEGATIVE_INFINITY;
  const to = aTo ? Date.parse(aTo) : Number.POSITIVE_INFINITY;
  const otherFrom = bFrom ? Date.parse(bFrom) : Number.NEGATIVE_INFINITY;
  const otherTo = bTo ? Date.parse(bTo) : Number.POSITIVE_INFINITY;
  return from < otherTo && to > otherFrom;
}

/**
 * 找出同实体上生效区间交叠的规则对（只含主规则）。
 *
 * <p>停用的规则不参与判定：否则"停用一条再启用一条"会永远放行不了。
 * 返回第一组冲突，形如 `[ruleA, ruleB]`；没有冲突返回 null。
 */
export function findConflicts(
  rules: readonly RuleRow[],
  // 显式写成 `?: T | undefined`：`exactOptionalPropertyTypes` 下调用方可以直接传 undefined，
  // 否则「更新自己时不带 id」这种最自然的用法会被类型系统挡下。
  candidate: {
    entityCode: string;
    ruleKind: string;
    status: string;
    id?: string | undefined;
    effectiveFrom?: string | null | undefined;
    effectiveTo?: string | null | undefined;
  },
): [RuleRow, RuleRow] | null {
  if (candidate.ruleKind !== 'PRIMARY' || candidate.status === 'DISABLED') return null;
  for (const other of rules) {
    if (other.id && candidate.id && other.id === candidate.id) continue;
    if (other.ruleKind !== 'PRIMARY' || other.status === 'DISABLED') continue;
    if (other.entityCode !== candidate.entityCode) continue;
    if (
      intervalsOverlap(
        candidate.effectiveFrom ?? null,
        candidate.effectiveTo ?? null,
        other.effectiveFrom ?? null,
        other.effectiveTo ?? null,
      )
    )
      return [other, candidate as RuleRow];
  }
  return null;
}

/**
 * 段的形状校验，与后端 {@code validateSegment} 与库里的 CHECK 同口径。
 *
 * <p>每种段只认属于它自己的列：选了 DATE 却填了前缀文本，后端会拒，这里先给出可读原因。
 */
export function validateSegment(segment: RuleSegment, componentIds: readonly string[]): string {
  switch (segment.segmentType) {
    case 'PREFIX':
    case 'SUFFIX':
    case 'LITERAL':
      return segment.literalText && segment.literalText.trim()
        ? ''
        : `${SEGMENT_TYPE_LABELS[segment.segmentType]}段需要填写文本`;
    case 'DATE':
      if (!segment.dateFormat) return '日期段需要选择日期格式';
      return DATE_FORMATS.includes(segment.dateFormat)
        ? ''
        : `不支持的日期格式：${segment.dateFormat}`;
    case 'SEQUENCE': {
      const width = segment.sequenceWidth;
      if (width == null) return '序号段需要设置序号宽度';
      if (width < 1 || width > 12) return '序号宽度必须在 1 到 12 之间';
      const start = segment.sequenceStart;
      if (start == null || start < 0) return '序号段需要设置起始序号';
      if (!segment.sequenceReset) return '序号段需要选择重置口径';
      if (!segment.sequenceScope) return '序号段需要选择计数作用域';
      return '';
    }
    case 'NESTED': {
      if (!segment.childRuleId) return '嵌套段需要选择要嵌入的子规则';
      if (!componentIds.includes(segment.childRuleId))
        return '只能嵌入可复用子规则，不能嵌入主规则或已删除的规则';
      return '';
    }
    default:
      return '未知的段类型';
  }
}

/**
 * 规则的预校验。返回第一条可读原因，通过校验返回空串。
 *
 * <p>嵌套成环在这里也挡一道（{@link detectsNestingCycle}）：界面上先给出提示，
 * 但服务端仍然是最终判定（数据可以被直接改库，渲染侧另有一层深度上限兜底）。
 */
export function validateRule(
  rule: {
    entityCode: string;
    ruleName: string;
    ruleKind: string;
    timezone: string;
    effectiveFrom?: string | null;
    effectiveTo?: string | null;
    segments: RuleSegment[];
  },
  existing: readonly RuleRow[],
  componentsById: ReadonlyMap<string, RuleRow>,
): string {
  if (!rule.entityCode) return '需要选择单据实体';
  if (!rule.ruleName.trim()) return '需要填写规则名称';
  if (!rule.segments.length) return '至少需要一个组成段';
  if (
    rule.effectiveFrom &&
    rule.effectiveTo &&
    Date.parse(rule.effectiveTo) <= Date.parse(rule.effectiveFrom)
  )
    return '生效结束时间必须晚于生效开始时间';
  const componentIds = [...componentsById.keys()];
  for (const segment of rule.segments) {
    const problem = validateSegment(segment, componentIds);
    if (problem) return problem;
  }
  if (detectsNestingCycle(rule.segments, componentsById)) return '嵌套会形成环';
  const clash = findConflicts(existing, {
    entityCode: rule.entityCode,
    ruleKind: rule.ruleKind,
    status: 'ACTIVE',
    effectiveFrom: rule.effectiveFrom,
    effectiveTo: rule.effectiveTo,
  });
  if (clash) return `与已有规则「${clash[0].ruleName}」的生效区间重叠`;
  return '';
}

/**
 * 嵌套成环检测：从每个 NESTED 段出发向下走，看可达图里有没有环。
 *
 * <p>必须区分「正在访问」与「已访问完」两种状态：只用一个 visited 集合会把
 * <b>指向祖先的合法分支</b>（A→B、A→C、C→B）和真正的环 A→B→A 一起剪掉，
 * 间接成环就检测不出来了。回到 {@code visiting} 状态的节点才是环。
 *
 * <p>与后端 {@code descendsFrom} 同一套可达图思路，只是不依赖服务端往返就能先提示。
 */
export function detectsNestingCycle(
  segments: readonly RuleSegment[],
  componentsById: ReadonlyMap<string, RuleRow>,
  selfId?: string,
): boolean {
  // 直接自嵌：不必走到下面就能判定。
  if (selfId && segments.some((s) => s.segmentType === 'NESTED' && s.childRuleId === selfId))
    return true;

  const state = new Map<string, 'visiting' | 'done'>();
  const visit = (id: string): boolean => {
    const mark = state.get(id);
    if (mark === 'visiting') return true;
    if (mark === 'done') return false;
    state.set(id, 'visiting');
    const component = componentsById.get(id);
    for (const segment of component?.segments ?? []) {
      if (segment.segmentType !== 'NESTED' || !segment.childRuleId) continue;
      if (selfId && segment.childRuleId === selfId) return true;
      if (visit(segment.childRuleId)) return true;
    }
    state.set(id, 'done');
    return false;
  };

  return segments.some(
    (s) => s.segmentType === 'NESTED' && !!s.childRuleId && visit(s.childRuleId),
  );
}

/**
 * 本地拼一遍编号，用于配置时**立刻**看到效果、不必等一次往返。
 *
 * <p>这不是权威结果：序号段的占位是调用方给的"下一个值"，服务端预览用的是真实计数器。
 * 真正取号请用 {@code /numbering/rules/{id}/next}。
 */
export function composeSample(segments: readonly RuleSegment[], isoDate: string): string {
  return segments
    .map((segment) => {
      switch (segment.segmentType) {
        case 'PREFIX':
        case 'SUFFIX':
        case 'LITERAL':
          return segment.literalText ?? '';
        case 'DATE':
          return formatDate(isoDate.slice(0, 10), segment.dateFormat ?? '');
        case 'SEQUENCE': {
          const width = segment.sequenceWidth ?? 1;
          const value = String(segment.sequenceStart ?? 1);
          return value.length >= width ? value : '0'.repeat(width - value.length) + value;
        }
        default:
          return '';
      }
    })
    .join('');
}

/** 日期渲染，与后端 {@code formatDate} 同一套白名单；未知格式返回空串而不是抛错。 */
export function formatDate(date: string, format: string): string {
  const year = date.slice(0, 4);
  const month = date.slice(5, 7);
  const day = date.slice(8, 10);
  switch (format) {
    case 'yyyyMMdd':
      return `${year}${month}${day}`;
    case 'yyyyMM':
      return `${year}${month}`;
    case 'yyyy':
      return year;
    case 'yyMMdd':
      return `${year.slice(2)}${month}${day}`;
    case 'MMdd':
      return `${month}${day}`;
    case 'yyyy-MM-dd':
      return `${year}-${month}-${day}`;
    default:
      return '';
  }
}

/** 段的形状是否配全了（用来决定要不要渲染出"配置不全"的提示）。 */
export function segmentSummary(segment: RuleSegment): string {
  switch (segment.segmentType) {
    case 'PREFIX':
    case 'SUFFIX':
    case 'LITERAL':
      return segment.literalText ?? '';
    case 'DATE':
      return DATE_FORMAT_LABELS[segment.dateFormat ?? ''] ?? segment.dateFormat ?? '';
    case 'SEQUENCE':
      return `${segment.sequenceReset ?? ''} · ${segment.sequenceScope ?? ''}`;
    case 'NESTED':
      return segment.childRuleName ?? '未选择子规则';
    default:
      return '';
  }
}

/** 规则的生效区间摘要：长期有效的规则不能只显示一个开始时间。 */
export function effectiveSummary(rule: {
  effectiveFrom?: string | null;
  effectiveTo?: string | null;
}): string {
  const from = rule.effectiveFrom ? rule.effectiveFrom.slice(0, 10) : '—';
  const to = rule.effectiveTo ? rule.effectiveTo.slice(0, 10) : '长期有效';
  return `${from} ~ ${to}`;
}

/**
 * 冲突提示文案：把"撞了哪一条"讲清楚，配置页直接显示它。
 *
 * <p>返回空串表示没有冲突——调用方据此决定要不要标红，而不是拿布尔值自己拼文案。
 */
export function conflictMessage(pair: [RuleRow, RuleRow] | null): string {
  if (!pair) return '';
  return `与「${pair[0].ruleName}」的生效区间重叠（${effectiveSummary(pair[0])}）：同一实体同一时刻只能有一条主规则`;
}

/** 按实体分组，供配置页分区渲染。分组顺序按传入的实体顺序，不随返回顺序漂移。 */
export function groupByEntity<T extends { entityCode: string }>(
  rows: readonly T[],
): { entityCode: string; rows: T[] }[] {
  const seen: string[] = [];
  for (const row of rows) if (!seen.includes(row.entityCode)) seen.push(row.entityCode);
  return seen
    .map((entityCode) => ({ entityCode, rows: rows.filter((r) => r.entityCode === entityCode) }))
    .filter((group) => group.rows.length > 0);
}
