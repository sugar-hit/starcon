import { describe, expect, it } from 'vitest';
import {
  APPROVAL_MODE_LABELS,
  APPROVER_TYPE_LABELS,
  FLOW_STATUS_LABELS,
  type BranchDraft,
  type EscalationDraft,
  type FlowDraft,
  type NodeDraft,
  approverSummary,
  branchConflicts,
  emptyDraft,
  flowSummary,
  groupByStage,
  serialStageConflicts,
  toRequestBody,
  unregisteredTypes,
  unusableNodes,
  validateDraft,
} from './approval-flow-model';

const ROLE_A = 'role-a';
const ROLE_B = 'role-b';
const ROLES = [ROLE_A, ROLE_B];

function node(over: Partial<NodeDraft> = {}): NodeDraft {
  return {
    nodeKey: 'R1',
    nodeName: '节点',
    nodeOrder: 0,
    stageIndex: 0,
    approverType: 'ROLE',
    roleId: ROLE_A,
    positionId: null,
    membershipId: null,
    approvalMode: 'SERIAL',
    conditionExpression: null,
    description: '',
    ...over,
  };
}

function branch(over: Partial<BranchDraft> = {}): BranchDraft {
  return {
    branchName: '档位',
    currencyCode: 'USD',
    minAmount: '0',
    maxAmount: '1000',
    targetNodeKey: 'R1',
    description: '',
    ...over,
  };
}

function escalation(over: Partial<EscalationDraft> = {}): EscalationDraft {
  return {
    nodeKey: 'R1',
    timeoutAction: 'AUTO_APPROVE',
    timeoutHours: 24,
    escalateApproverType: null,
    escalateRoleId: null,
    escalatePositionId: null,
    escalateMembershipId: null,
    description: '',
    ...over,
  };
}

function draft(over: Partial<FlowDraft> = {}): FlowDraft {
  return {
    documentTypeCode: 'PURCHASE_ORDER',
    flowName: '采购审批',
    status: 'DRAFT',
    description: '',
    nodes: [node()],
    branches: [],
    escalations: [],
    ...over,
  };
}

describe('审批流枚举文案', () => {
  it('三种审批人与三种审批方式都有中文标签', () => {
    expect(Object.keys(APPROVER_TYPE_LABELS)).toEqual(['ROLE', 'POSITION', 'USER']);
    expect(Object.keys(APPROVAL_MODE_LABELS)).toEqual(['SERIAL', 'PARALLEL', 'CONDITIONAL']);
    expect(Object.keys(FLOW_STATUS_LABELS)).toEqual(['DRAFT', 'ACTIVE', 'DISABLED']);
  });
});

describe('空草稿', () => {
  it('默认给一个阶段 0 的串行角色节点，不会一上来就是零节点', () => {
    const d = emptyDraft(ROLE_A);
    expect(d.nodes).toHaveLength(1);
    expect(d.nodes[0]).toMatchObject({
      nodeKey: 'R1',
      stageIndex: 0,
      nodeOrder: 0,
      approvalMode: 'SERIAL',
      approverType: 'ROLE',
      roleId: ROLE_A,
    });
    // 单据类型与名称还得由用户补，所以这两条仍然在列；除它们之外没有别的问题。
    expect(validateDraft(d, ROLES)).toEqual(['请选择适用单据类型', '请填写流程名称']);
    expect(validateDraft({ ...d, documentTypeCode: 'PURCHASE_ORDER', flowName: '采购审批' }, ROLES)).toEqual([]);
  });

  it('没有可用角色时默认节点仍然要求补选审批人', () => {
    expect(validateDraft(emptyDraft(null), ROLES)).toContain('审批节点：请选择审批人');
  });
});

describe('草稿校验', () => {
  it('必填项缺失会逐条报出来', () => {
    const errors = validateDraft(draft({ documentTypeCode: '', flowName: '' }), ROLES);
    expect(errors).toContain('请选择适用单据类型');
    expect(errors).toContain('请填写流程名称');
  });

  it('零节点直接被拒并给出理由', () => {
    const errors = validateDraft(draft({ nodes: [] }), ROLES);
    expect(errors).toEqual(['审批流至少要有一个审批节点']);
  });

  it('节点标识与顺序不能重复', () => {
    const errors = validateDraft(
      draft({ nodes: [node(), node({ nodeOrder: 0, stageIndex: 1 })] }),
      ROLES,
    );
    expect(errors).toContain('节点标识重复：R1');
    expect(errors).toContain('节点顺序重复：0');
  });

  it('一个阶段里两个串行节点会被拒', () => {
    const errors = validateDraft(
      draft({
        nodes: [node(), node({ nodeKey: 'R2', nodeOrder: 1, stageIndex: 0 })],
      }),
      ROLES,
    );
    expect(errors.some((e) => e.includes('阶段 0 里有多个串行节点'))).toBe(true);
  });

  it('同阶段并行 + 跨阶段串行是合法配置', () => {
    expect(
      validateDraft(
        draft({
          nodes: [
            node(),
            node({ nodeKey: 'R2', nodeOrder: 1, stageIndex: 0, approvalMode: 'PARALLEL' }),
            node({ nodeKey: 'R3', nodeOrder: 2, stageIndex: 1 }),
          ],
        }),
        ROLES,
      ),
    ).toEqual([]);
  });

  it('条件分支必须带表达式，非条件分支不得带表达式', () => {
    expect(
      validateDraft(draft({ nodes: [node({ approvalMode: 'CONDITIONAL' })] }), ROLES).some((e) =>
        e.includes('条件分支必须填写条件表达式'),
      ),
    ).toBe(true);
    expect(
      validateDraft(
        draft({ nodes: [node({ conditionExpression: 'amount > 1' })] }),
        ROLES,
      ).some((e) => e.includes('只有条件分支节点能填条件表达式')),
    ).toBe(true);
    expect(
      validateDraft(
        draft({ nodes: [node({ approvalMode: 'CONDITIONAL', conditionExpression: 'amount > 1' })] }),
        ROLES,
      ),
    ).toEqual([]);
  });

  it('审批人必须是三选一，且不能引用不存在的角色', () => {
    expect(
      validateDraft(draft({ nodes: [node({ roleId: null })] }), ROLES).some((e) =>
        e.includes('请选择审批人'),
      ),
    ).toBe(true);
    expect(
      validateDraft(
        draft({ nodes: [node({ positionId: 'pos-1' })] }),
        ROLES,
      ).some((e) => e.includes('审批人只能选一个')),
    ).toBe(true);
    expect(
      validateDraft(draft({ nodes: [node({ roleId: 'role-missing' })] }), ROLES).some((e) =>
        e.includes('引用的角色不存在'),
      ),
    ).toBe(true);
  });

  it('金额分支上下限不能都空、不能倒置、币种必须是三位大写', () => {
    expect(
      validateDraft(draft({ branches: [branch({ minAmount: '', maxAmount: '' })] }), ROLES).some((e) =>
        e.includes('上下限不能都留空'),
      ),
    ).toBe(true);
    expect(
      validateDraft(draft({ branches: [branch({ minAmount: '100', maxAmount: '100' })] }), ROLES).some(
        (e) => e.includes('上限必须大于下限'),
      ),
    ).toBe(true);
    expect(
      validateDraft(draft({ branches: [branch({ currencyCode: 'usd' })] }), ROLES).some((e) =>
        e.includes('币种必须是三个大写字母'),
      ),
    ).toBe(true);
    expect(validateDraft(draft({ branches: [branch()] }), ROLES)).toEqual([]);
  });

  it('金额分支必须指向本流程内存在的节点', () => {
    expect(
      validateDraft(draft({ branches: [branch({ targetNodeKey: 'NOPE' })] }), ROLES).some((e) =>
        e.includes('不在本流程内'),
      ),
    ).toBe(true);
  });

  it('超时规则：时限必须为正、同一时限只能一条、转办必须指定接收人', () => {
    expect(
      validateDraft(draft({ escalations: [escalation({ timeoutHours: 0 })] }), ROLES).some((e) =>
        e.includes('超时时限必须大于 0'),
      ),
    ).toBe(true);
    expect(
      validateDraft(
        draft({
          escalations: [escalation({ timeoutHours: 24 }), escalation({ timeoutHours: 24 })],
        }),
        ROLES,
      ).some((e) => e.includes('同一节点的同一时限只能配一条规则')),
    ).toBe(true);
    expect(
      validateDraft(
        draft({ escalations: [escalation({ timeoutAction: 'ESCALATE' })] }),
        ROLES,
      ).some((e) => e.includes('转办必须且只能指定一个接收人')),
    ).toBe(true);
    expect(
      validateDraft(
        draft({
          escalations: [
            escalation({ timeoutAction: 'ESCALATE', escalateApproverType: 'ROLE', escalateRoleId: ROLE_B }),
          ],
        }),
        ROLES,
      ),
    ).toEqual([]);
    expect(
      validateDraft(
        draft({
          escalations: [
            escalation({
              timeoutAction: 'AUTO_APPROVE',
              escalateApproverType: 'ROLE',
              escalateRoleId: ROLE_B,
            }),
          ],
        }),
        ROLES,
      ).some((e) => e.includes('超时视同通过不需要指定接收人')),
    ).toBe(true);
  });
});

describe('金额区间交叠检测', () => {
  it('同一币种区间交叠会被报出来', () => {
    const conflicts = branchConflicts([
      branch({ branchName: '小额', minAmount: '0', maxAmount: '1000' }),
      branch({ branchName: '中额', minAmount: '500', maxAmount: '2000' }),
    ]);
    expect(conflicts).toHaveLength(1);
    expect(conflicts[0]).toContain('小额');
    expect(conflicts[0]).toContain('中额');
  });

  it('相邻接续不算交叠（上限等于下限）', () => {
    expect(
      branchConflicts([
        branch({ minAmount: '0', maxAmount: '1000' }),
        branch({ minAmount: '1000', maxAmount: '2000' }),
      ]),
    ).toEqual([]);
  });

  it('不同币种的同一区间允许并存', () => {
    expect(
      branchConflicts([
        branch({ currencyCode: 'USD' }),
        branch({ currencyCode: 'EUR' }),
      ]),
    ).toEqual([]);
  });

  it('无上界的分支与任何其他分支互斥', () => {
    const conflicts = branchConflicts([
      branch({ branchName: '通吃', minAmount: '0', maxAmount: '' }),
      branch({ branchName: '小额', minAmount: '0', maxAmount: '1000' }),
    ]);
    expect(conflicts).toHaveLength(1);
    expect(conflicts[0]).toContain('没有上限');
  });

  it('三段互相交叠时两两都被报出来', () => {
    const conflicts = branchConflicts([
      branch({ branchName: 'A', minAmount: '0', maxAmount: '100' }),
      branch({ branchName: 'B', minAmount: '50', maxAmount: '150' }),
      branch({ branchName: 'C', minAmount: '120', maxAmount: '200' }),
    ]);
    // A-B 交叠；A-C 不交叠（100 < 120）；B-C 交叠。
    expect(conflicts).toHaveLength(2);
  });
});

describe('阶段串行冲突定位', () => {
  it('只报真正冲突的阶段', () => {
    const stages = serialStageConflicts([
      node({ nodeKey: 'A', nodeOrder: 0, stageIndex: 0, approvalMode: 'SERIAL' }),
      node({ nodeKey: 'B', nodeOrder: 1, stageIndex: 0, approvalMode: 'SERIAL' }),
      node({ nodeKey: 'C', nodeOrder: 2, stageIndex: 1, approvalMode: 'SERIAL' }),
      node({ nodeKey: 'D', nodeOrder: 3, stageIndex: 1, approvalMode: 'SERIAL' }),
    ]);
    expect(stages).toEqual([0, 1]);
  });

  it('阶段内全是并行节点不算冲突', () => {
    expect(
      serialStageConflicts([
        node({ nodeKey: 'A', nodeOrder: 0, approvalMode: 'PARALLEL' }),
        node({ nodeKey: 'B', nodeOrder: 1, approvalMode: 'PARALLEL' }),
      ]),
    ).toEqual([]);
  });
});

describe('审批人摘要', () => {
  it('三种审批人各自描述成「类型 · 名称」', () => {
    const lookup = {
      roleName: (id: string | null) => (id === ROLE_A ? '采购经理' : '财务总监'),
      positionName: () => '财务主管岗',
      memberName: () => 'zhangsan',
    };
    expect(approverSummary(node(), lookup)).toBe('角色 · 采购经理');
    expect(
      approverSummary({ approverType: 'POSITION', positionId: 'p1', roleId: null, membershipId: null }, lookup),
    ).toBe('岗位 · 财务主管岗');
    expect(
      approverSummary({ approverType: 'USER', membershipId: 'm1', roleId: null, positionId: null }, lookup),
    ).toBe('指定人 · zhangsan');
  });

  it('审批人已停用会明确标出来', () => {
    expect(approverSummary({ ...node(), approverActive: false }, {})).toContain('已停用');
  });
});

describe('阶段分组与摘要', () => {
  it('按阶段分组且保持阶段内顺序', () => {
    const grouped = groupByStage([
      { stageIndex: 0, nodeOrder: 0 },
      { stageIndex: 1, nodeOrder: 2 },
      { stageIndex: 0, nodeOrder: 1 },
    ]);
    expect([...grouped.keys()]).toEqual([0, 1]);
    expect(grouped.get(0)).toHaveLength(2);
  });

  it('流程摘要在有分支与超时规则时才提它们', () => {
    expect(flowSummary({ documentTypeName: '采购订单', nodeCount: 2, branchCount: 0, escalationCount: 0 })).toBe(
      '采购订单 · 2 个审批节点',
    );
    expect(
      flowSummary({ documentTypeName: '采购订单', nodeCount: 2, branchCount: 1, escalationCount: 3 }),
    ).toBe('采购订单 · 2 个审批节点 · 1 条金额阈值 · 3 条超时规则');
  });
});

describe('未注册单据类型', () => {
  it('只列 registered=false 的行——那些就是当前没有管控的变更类操作', () => {
    const list = unregisteredTypes([
      { documentTypeCode: 'PURCHASE_ORDER', documentTypeName: '采购订单', registered: true },
      { documentTypeCode: 'ROLE_GRANT', documentTypeName: '角色授权', registered: false },
      { documentTypeCode: 'DATA_EXPORT', documentTypeName: '数据导出', registered: false },
    ]);
    expect(list.map((r) => r.documentTypeCode)).toEqual(['ROLE_GRANT', 'DATA_EXPORT']);
  });
});

describe('不可用审批人', () => {
  it('审批人不存在或已停用都会被点名', () => {
    expect(
      unusableNodes([
        { nodeName: '财务复核', approverExists: true, approverActive: false },
        { nodeName: '法务复核', approverExists: false, approverActive: null },
        { nodeName: '经理审批', approverExists: true, approverActive: true },
      ]),
    ).toEqual(['财务复核：审批人已停用，该节点无人可审', '法务复核：审批人已不存在，该节点无人可审']);
  });
});

describe('请求体转换', () => {
  it('空字符串转成 null，不把空串当金额下限发上去', () => {
    const body = toRequestBody(
      draft({
        nodes: [node({ approverType: 'USER', roleId: null, membershipId: 'm1' })],
        branches: [branch({ minAmount: '', maxAmount: '1000' })],
        escalations: [escalation({ nodeKey: 'R1', timeoutHours: 48 })],
      }),
    );
    expect(body.branches[0]?.minAmount).toBeNull();
    expect(body.branches[0]?.maxAmount).toBe('1000');
    expect(body.nodes[0]).toMatchObject({
      approverType: 'USER',
      membershipId: 'm1',
      roleId: null,
      positionId: null,
      conditionExpression: null,
    });
    expect(body.escalations[0]).toMatchObject({
      timeoutAction: 'AUTO_APPROVE',
      escalateApproverType: null,
      escalateRoleId: null,
    });
  });

  it('条件分支的表达式原样带上，非条件分支强制为 null', () => {
    const body = toRequestBody(
      draft({
        nodes: [
          node({ approvalMode: 'CONDITIONAL', conditionExpression: ' amount > 1000 ' }),
          node({ nodeKey: 'R2', nodeOrder: 1, stageIndex: 1, conditionExpression: '不该带上' }),
        ],
      }),
    );
    expect(body.nodes[0]?.conditionExpression).toBe('amount > 1000');
    expect(body.nodes[1]?.conditionExpression).toBeNull();
  });
});