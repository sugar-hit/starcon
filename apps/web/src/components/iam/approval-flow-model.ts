/**
 * 17.4.1.1 审批流定义的展示口径与前端预校验。
 *
 * <p>后端 {@code ApprovalFlowController} 是最终判定（审批人存在性、阶段串行冲突、金额区间
 * 交叠、生效流程唯一性都在服务端），这里的校验与展示同口径，目的是让用户在提交前就看到
 * 「为什么不行」，而不是提交后吃一个 400。后端仍然是权威：即使前端校验放过，后端也会拒。
 *
 * <p>三条蓝图硬要求在两侧必须同时改，否则界面上说的和后端算的不是一回事：
 *
 * <ul>
 *   <li><b>按单据类型注册</b>：{@link unregisteredTypes} 与后端 {@code approval_flow_no_registration}
 *       同一口径 —— {@code registered=false} 的单据类型当前<b>没有</b>管控，按蓝图约定
 *       「未注册的变更类操作默认可直接执行」。页面据此把这类行标成「未注册」。
 *   <li><b>阶段之间串行、阶段内并行</b>：{@link serialStageConflicts} 与后端
 *       {@code normalizeNodes} 的阶段串行唯一性同口径；一个阶段塞两个串行节点意味着
 *       「串行」和「阶段」互相矛盾，配置期就该拒。
 *   <li><b>金额阈值分支</b>：{@link branchConflicts} 与后端 {@code rejectOverlappingBranches}
 *       同一套半开区间 {@code [min,max)} 判定，上限等于下限算接续不算交叠；
 *       无上界的分支与任何其他分支都互斥（它是默认路径，不该是分支）。
 * </ul>
 *
 * <p><b>枚举一律从契约行类型反推</b>（{@code NonNullable<X>['field']}），不手抄字面量联合：
 * codegen 会把 Java 枚举内联成 {@code string}，手抄一份会随后端枚举增删静默漂移。
 */

/** 审批流状态。 */
export type FlowStatus = NonNullable<ApprovalFlowViewLike>['status'];
/** 审批人类型：角色 / 岗位 / 指定人。 */
export type ApproverType = NonNullable<ApprovalNodeViewLike>['approverType'];
/** 审批方式：串行 / 并行 / 条件分支。 */
export type ApprovalMode = NonNullable<ApprovalNodeViewLike>['approvalMode'];
/** 超时动作：转办 / 视同通过。 */
export type TimeoutAction = NonNullable<ApprovalEscalationViewLike>['timeoutAction'];

/** 契约行类型的最小结构（字段名与 {@code packages/api-contracts} 一致）。 */
type ApprovalFlowViewLike = { status: string };
type ApprovalNodeViewLike = { approverType: string; approvalMode: string };
type ApprovalEscalationViewLike = { timeoutAction: string };

export const FLOW_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  ACTIVE: '生效中',
  DISABLED: '已停用',
};

/** 徽标配色：生效中要显眼（这条流程真的在管控一个单据类型）。 */
export const FLOW_STATUS_TONES: Record<string, 'neutral' | 'primary' | 'success' | 'warning'> = {
  DRAFT: 'neutral',
  ACTIVE: 'success',
  DISABLED: 'warning',
};

export const APPROVER_TYPE_LABELS: Record<string, string> = {
  ROLE: '角色',
  POSITION: '岗位',
  USER: '指定人',
};

export const APPROVAL_MODE_LABELS: Record<string, string> = {
  SERIAL: '串行',
  PARALLEL: '并行',
  CONDITIONAL: '条件分支',
};

/** 条件分支的一句话说明：这一段是「条件分支」的语义核心，界面上要讲清楚。 */
export const APPROVAL_MODE_HINTS: Record<string, string> = {
  SERIAL: '阶段之间串行，阶段内只允许一个串行节点',
  PARALLEL: '与本阶段其他节点同时审批',
  CONDITIONAL: '仅当条件表达式命中时参与本阶段，不命中则跳过',
};

export const TIMEOUT_ACTION_LABELS: Record<string, string> = {
  ESCALATE: '转办他人',
  AUTO_APPROVE: '超时视同通过',
};

/** 岗位状态。岗位不携带权限，只回答「这一步由哪个岗位的人审」。 */
export const POSITION_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '启用',
  DISABLED: '已停用',
};

export const SEVERITY_LABELS: Record<string, string> = {
  ERROR: '阻断',
  WARNING: '提醒',
  INFO: '说明',
};

export const SEVERITY_TONES: Record<string, 'neutral' | 'primary' | 'success' | 'warning'> = {
  ERROR: 'warning',
  WARNING: 'primary',
  INFO: 'neutral',
};

/** 页面里最轻的一组枚举，供下拉直接铺开（白名单取自后端 CHECK 与 {@code @Pattern}）。 */
export const FLOW_STATUSES = Object.keys(FLOW_STATUS_LABELS);
export const APPROVER_TYPES = Object.keys(APPROVER_TYPE_LABELS);
export const APPROVAL_MODES = Object.keys(APPROVAL_MODE_LABELS);
export const TIMEOUT_ACTIONS = Object.keys(TIMEOUT_ACTION_LABELS);

/** 编辑器里的一条节点（还没落库，所以没有 id）。 */
export type NodeDraft = {
  nodeKey: string;
  nodeName: string;
  nodeOrder: number;
  stageIndex: number;
  approverType: string;
  roleId: string | null;
  positionId: string | null;
  membershipId: string | null;
  approvalMode: string;
  conditionExpression: string | null;
  description: string;
};

/** 编辑器里的一条金额阈值分支。 */
export type BranchDraft = {
  branchName: string;
  currencyCode: string;
  minAmount: string;
  maxAmount: string;
  targetNodeKey: string;
  description: string;
};

/** 编辑器里的一条超时升级规则。 */
export type EscalationDraft = {
  nodeKey: string;
  timeoutAction: string;
  timeoutHours: number;
  escalateApproverType: string | null;
  escalateRoleId: string | null;
  escalatePositionId: string | null;
  escalateMembershipId: string | null;
  description: string;
};

export type FlowDraft = {
  documentTypeCode: string;
  flowName: string;
  status: string;
  description: string;
  nodes: NodeDraft[];
  branches: BranchDraft[];
  escalations: EscalationDraft[];
};

/** 空草稿。默认给一个阶段 0 的串行角色节点，省得新流程一开始就是「0 个节点」被后端拒。 */
export function emptyDraft(roleId: string | null): FlowDraft {
  return {
    documentTypeCode: '',
    flowName: '',
    status: 'DRAFT',
    description: '',
    nodes: [
      {
        nodeKey: 'R1',
        nodeName: '审批节点',
        nodeOrder: 0,
        stageIndex: 0,
        approverType: 'ROLE',
        roleId,
        positionId: null,
        membershipId: null,
        approvalMode: 'SERIAL',
        conditionExpression: null,
        description: '',
      },
    ],
    branches: [],
    escalations: [],
  };
}

/**
 * 草稿整体校验，返回可直接渲染的错误文案列表。
 *
 * <p>只做**能在浏览器里确定判定**的检查：形状、必填、顺序、引用是否存在。
 * 「这个角色是否还启用」「金额区间是否与库里别的流程交叠」由服务端判定，前端不猜。
 */
export function validateDraft(draft: FlowDraft, knownRoles: string[]): string[] {
  const errors: string[] = [];
  if (!draft.documentTypeCode.trim()) errors.push('请选择适用单据类型');
  if (!draft.flowName.trim()) errors.push('请填写流程名称');
  if (draft.nodes.length === 0) {
    errors.push('审批流至少要有一个审批节点');
    return errors;
  }

  const keys = new Set<string>();
  const orders = new Set<number>();
  const serialStages = new Set<number>();
  for (const n of draft.nodes) {
    const key = n.nodeKey.trim();
    const label = n.nodeName.trim() || key || '未命名节点';
    if (!key) errors.push(`${label}：节点标识不能为空`);
    else if (keys.has(key)) errors.push(`节点标识重复：${key}`);
    keys.add(key);
    if (n.nodeOrder < 0 || !Number.isInteger(n.nodeOrder))
      errors.push(`${label}：节点顺序必须是不小于 0 的整数`);
    if (orders.has(n.nodeOrder)) errors.push(`节点顺序重复：${n.nodeOrder}`);
    orders.add(n.nodeOrder);
    if (n.stageIndex < 0 || !Number.isInteger(n.stageIndex))
      errors.push(`${label}：阶段序号必须是不小于 0 的整数`);
    if (n.approvalMode === 'SERIAL' && serialStages.has(n.stageIndex))
      errors.push(
        `阶段 ${n.stageIndex} 里有多个串行节点；一个阶段只能有一个串行节点，其余请用并行`,
      );
    if (n.approvalMode === 'SERIAL') serialStages.add(n.stageIndex);
    if (n.approvalMode === 'CONDITIONAL') {
      if (!n.conditionExpression?.trim()) errors.push(`${label}：条件分支必须填写条件表达式`);
    } else if (n.conditionExpression?.trim()) {
      errors.push(`${label}：只有条件分支节点能填条件表达式`);
    }
    // 三种审批人各认各的列：混填在保存时会被拒，这里提前说清。
    const chosen = [n.roleId, n.positionId, n.membershipId].filter(Boolean).length;
    if (chosen === 0) errors.push(`${label}：请选择审批人`);
    else if (chosen > 1) errors.push(`${label}：审批人只能选一个（角色 / 岗位 / 指定人 三选一）`);
    if (n.approverType === 'ROLE' && n.roleId && !knownRoles.includes(n.roleId))
      errors.push(`${label}：引用的角色不存在，请重新选择`);
  }

  for (const b of draft.branches) {
    const label = b.branchName.trim() || '未命名金额分支';
    if (!/^[A-Z]{3}$/.test(b.currencyCode.trim()))
      errors.push(`${label}：币种必须是三个大写字母，如 USD`);
    const min = b.minAmount.trim();
    const max = b.maxAmount.trim();
    if (!min && !max) errors.push(`${label}：上下限不能都留空，全金额都走这里应当是默认路径`);
    if (min && !/^\d+(\.\d+)?$/.test(min)) errors.push(`${label}：金额下限必须是非负数字`);
    if (max && !/^\d+(\.\d+)?$/.test(max)) errors.push(`${label}：金额上限必须是非负数字`);
    if (min && max && Number(max) <= Number(min)) errors.push(`${label}：上限必须大于下限`);
    if (!keys.has(b.targetNodeKey.trim()))
      errors.push(`${label}：指向的节点 ${b.targetNodeKey || '(空)'} 不在本流程内`);
  }

  const seen = new Set<string>();
  for (const e of draft.escalations) {
    const label = `节点 ${e.nodeKey.trim() || '(空)'} 的超时规则`;
    if (!keys.has(e.nodeKey.trim())) errors.push(`${label}：节点不在本流程内`);
    if (!Number.isInteger(e.timeoutHours) || e.timeoutHours <= 0)
      errors.push(`${label}：超时时限必须大于 0 小时`);
    const signature = `${e.nodeKey.trim()}#${e.timeoutHours}`;
    if (seen.has(signature)) errors.push(`${label}：同一节点的同一时限只能配一条规则`);
    seen.add(signature);
    if (e.timeoutAction === 'ESCALATE') {
      const chosen = [e.escalateRoleId, e.escalatePositionId, e.escalateMembershipId].filter(
        Boolean,
      ).length;
      if (chosen !== 1) errors.push(`${label}：转办必须且只能指定一个接收人`);
    } else if (
      e.escalateRoleId ||
      e.escalatePositionId ||
      e.escalateMembershipId ||
      e.escalateApproverType
    ) {
      errors.push(`${label}：超时视同通过不需要指定接收人，请清空`);
    }
  }

  errors.push(...branchConflicts(draft.branches));
  return errors;
}

/**
 * 同币种内金额区间交叠检测，与后端 {@code rejectOverlappingBranches} 同一套半开区间口径。
 *
 * <p>区间可以留缺口（缺口的金额走默认串行路径），但**不允许交叠**：交叠意味着某个金额
 * 落进两条分支时「该走哪个节点」没有唯一答案。上限留空表示覆盖该币种全部金额，
 * 因此与任何其他分支都互斥。
 */
export function branchConflicts(branches: BranchDraft[]): string[] {
  const conflicts: string[] = [];
  const byCurrency = new Map<string, BranchDraft[]>();
  for (const b of branches) {
    const code = b.currencyCode.trim();
    if (!code) continue;
    const group = byCurrency.get(code) ?? [];
    group.push(b);
    byCurrency.set(code, group);
  }
  for (const [code, group] of byCurrency) {
    const sorted = [...group].sort((x, y) => {
      const lx = x.minAmount.trim() === '' ? -1 : Number(x.minAmount);
      const ly = y.minAmount.trim() === '' ? -1 : Number(y.minAmount);
      return lx - ly;
    });
    for (let i = 0; i < sorted.length; i++)
      for (let j = i + 1; j < sorted.length; j++) {
        const a = sorted[i]!;
        const b = sorted[j]!;
        const nameA = a.branchName.trim() || '未命名金额分支';
        const nameB = b.branchName.trim() || '未命名金额分支';
        if (a.maxAmount.trim() === '') {
          conflicts.push(
            `金额分支「${nameA}」没有上限（覆盖 ${code} 全部金额），与「${nameB}」互斥；全金额走一条分支时应设为默认路径`,
          );
          continue;
        }
        const lower = b.minAmount.trim() === '' ? -1 : Number(b.minAmount);
        if (Number(a.maxAmount) > lower)
          conflicts.push(
            `金额分支「${nameA}」与「${nameB}」在币种 ${code} 上区间交叠，同一笔金额走哪条节点没有唯一答案`,
          );
      }
  }
  return conflicts;
}

/** 阶段串行冲突（单列出来便于界面上高亮到具体阶段）。 */
export function serialStageConflicts(nodes: NodeDraft[]): number[] {
  const seen = new Set<number>();
  const conflicted = new Set<number>();
  for (const n of nodes)
    if (n.approvalMode === 'SERIAL' && seen.has(n.stageIndex)) conflicted.add(n.stageIndex);
    else if (n.approvalMode === 'SERIAL') seen.add(n.stageIndex);
  return [...conflicted].sort((a, b) => a - b);
}

/**
 * 节点摘要：把审批人描述成一句人话。
 *
 * <p>审批人已被停用时额外标注 —— 那种节点配得上、批不了，是配置页最需要看见的一类事实。
 */
export function approverSummary(
  node: Pick<NodeDraft, 'approverType' | 'roleId' | 'positionId' | 'membershipId'> & {
    approverLabel?: string | null;
    approverActive?: boolean | null;
  },
  lookup: {
    roleName?: (id: string | null) => string;
    positionName?: (id: string | null) => string;
    memberName?: (id: string | null) => string;
  },
): string {
  let name: string;
  if (node.approverType === 'ROLE')
    name = lookup.roleName?.(node.roleId) ?? node.approverLabel ?? '未选择角色';
  else if (node.approverType === 'POSITION')
    name = lookup.positionName?.(node.positionId) ?? node.approverLabel ?? '未选择岗位';
  else name = lookup.memberName?.(node.membershipId) ?? node.approverLabel ?? '未选择成员';
  const kind = APPROVER_TYPE_LABELS[node.approverType] ?? node.approverType;
  const suffix = node.approverActive === false ? '（已停用）' : '';
  return `${kind} · ${name}${suffix}`;
}

/** 阶段摘要：把一个阶段里的节点收成一行，界面上按阶段分组显示。 */
export function groupByStage<T extends { stageIndex: number }>(nodes: T[]): Map<number, T[]> {
  const grouped = new Map<number, T[]>();
  for (const n of nodes) {
    const list = grouped.get(n.stageIndex) ?? [];
    list.push(n);
    grouped.set(n.stageIndex, list);
  }
  return grouped;
}

/** 流程摘要：列表页一行说清「管哪个单据类型、几个节点、有没有金额分支」。 */
export function flowSummary(flow: {
  documentTypeName: string;
  nodeCount: number;
  branchCount: number;
  escalationCount: number;
}): string {
  const parts = [`${flow.nodeCount} 个审批节点`];
  if (flow.branchCount > 0) parts.push(`${flow.branchCount} 条金额阈值`);
  if (flow.escalationCount > 0) parts.push(`${flow.escalationCount} 条超时规则`);
  return `${flow.documentTypeName} · ${parts.join(' · ')}`;
}

/** 未注册（因此当前无管控）的单据类型清单。 */
export function unregisteredTypes(
  registrations: { documentTypeCode: string; documentTypeName: string; registered: boolean }[],
): { documentTypeCode: string; documentTypeName: string }[] {
  return registrations
    .filter((r) => !r.registered)
    .map((r) => ({ documentTypeCode: r.documentTypeCode, documentTypeName: r.documentTypeName }));
}

/** 审批人不可用的节点（审批人不存在或已停用），是结构体检之外的前端即时提示。 */
export function unusableNodes(
  nodes: ({ approverExists: boolean; approverActive: boolean | null } & { nodeName: string })[],
): string[] {
  return nodes
    .filter((n) => !n.approverExists || n.approverActive === false)
    .map((n) => `${n.nodeName}：审批人${n.approverExists ? '已停用' : '已不存在'}，该节点无人可审`);
}

/** 草稿 → 请求体。把空字符串转成 null，省得把「」当成金额下限发上去。 */
export function toRequestBody(draft: FlowDraft) {
  const blank = (v: string) => (v.trim() === '' ? null : v.trim());
  return {
    documentTypeCode: draft.documentTypeCode.trim(),
    flowName: draft.flowName.trim(),
    status: draft.status,
    description: draft.description.trim(),
    nodes: draft.nodes.map((n) => ({
      nodeKey: n.nodeKey.trim(),
      nodeName: n.nodeName.trim(),
      nodeOrder: n.nodeOrder,
      stageIndex: n.stageIndex,
      approverType: n.approverType,
      roleId: n.approverType === 'ROLE' ? n.roleId : null,
      positionId: n.approverType === 'POSITION' ? n.positionId : null,
      membershipId: n.approverType === 'USER' ? n.membershipId : null,
      approvalMode: n.approvalMode,
      conditionExpression:
        n.approvalMode === 'CONDITIONAL' ? blank(n.conditionExpression ?? '') : null,
      description: n.description.trim(),
    })),
    branches: draft.branches.map((b) => ({
      branchName: b.branchName.trim(),
      currencyCode: b.currencyCode.trim(),
      minAmount: blank(b.minAmount),
      maxAmount: blank(b.maxAmount),
      targetNodeKey: b.targetNodeKey.trim(),
      description: b.description.trim(),
    })),
    escalations: draft.escalations.map((e) => ({
      nodeKey: e.nodeKey.trim(),
      timeoutAction: e.timeoutAction,
      timeoutHours: e.timeoutHours,
      escalateApproverType: e.timeoutAction === 'ESCALATE' ? e.escalateApproverType : null,
      escalateRoleId: e.timeoutAction === 'ESCALATE' ? e.escalateRoleId : null,
      escalatePositionId: e.timeoutAction === 'ESCALATE' ? e.escalatePositionId : null,
      escalateMembershipId: e.timeoutAction === 'ESCALATE' ? e.escalateMembershipId : null,
      description: e.description.trim(),
    })),
  };
}
