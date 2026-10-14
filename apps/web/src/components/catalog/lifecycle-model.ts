import type {
  RuleInput,
  TransitionOption,
} from '../../../../../packages/api-contracts/src/index';

export const lifecycleStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_LISTING: '待上架',
  ON_SALE: '在售',
  SUSPENDED: '停售',
  DISCONTINUED: '停产',
};

export const lifecycleOperators: { code: string; label: string }[] = [
  { code: 'GT', label: '大于' },
  { code: 'GTE', label: '大于等于' },
  { code: 'EQ', label: '等于' },
  { code: 'NEQ', label: '不等于' },
  { code: 'LTE', label: '小于等于' },
  { code: 'LT', label: '小于' },
];

/** 规则目标状态：矩阵里没有 DRAFT（退回草稿属人工动作），终态也不能作为来源。 */
export const ruleTargets = ['PENDING_LISTING', 'ON_SALE', 'SUSPENDED', 'DISCONTINUED'];

export function statusLabel(code: string): string {
  return lifecycleStatusLabels[code] ?? code;
}

export function operatorLabel(code: string): string {
  return lifecycleOperators.find((o) => o.code === code)?.label ?? code;
}

/** 流转按钮是否可点：必须选中合法目标，且矩阵要求填原因时不能留空。 */
export function transitionAllowed(
  transitions: TransitionOption[],
  target: string,
  reason: string,
): boolean {
  if (!target) return false;
  const chosen = transitions.find((t) => t.toStatus === target);
  if (!chosen) return false;
  return chosen.reasonRequired ? reason.trim().length > 0 : true;
}

/** 规则阈值输入的本地校验：后端仍会再校验一次，这里只为避免明显错误往返。 */
export function ruleDraftValid(draft: RuleInput): string | null {
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(draft.code.trim())) {
    return '规则编码请使用英文字母、数字、下划线或短横线';
  }
  if (!draft.name.trim()) return '请填写规则名称';
  if (!draft.conditions.length) return '至少需要一个判定条件';
  for (const c of draft.conditions) {
    if (!Number.isFinite(Number(c.threshold))) return '阈值必须是数字';
    const fraction = String(c.threshold).split('.')[1];
    if (fraction !== undefined && fraction.length > 6) return '阈值最多 6 位小数';
  }
  if (draft.targetStatus === 'DRAFT') return '批量规则不能把商品退回草稿';
  return null;
}
