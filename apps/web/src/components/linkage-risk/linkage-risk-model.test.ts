import { describe, expect, it } from 'vitest';
import {
  actionLabel,
  alertStatusLabel,
  canActOnAlert,
  canActOnRemediation,
  clusterDimensionText,
  dimensionLabel,
  emptyState,
  factorText,
  graphSummary,
  legalEntityText,
  registeredOnlyCapabilities,
  remediationStatusLabel,
  severityLabel,
  severityTone,
  sourceLabel,
  unavailableCapabilities,
  type LinkageRiskAlert,
  type LinkageRiskCapabilities,
  type LinkageRiskGraphCluster,
  type LinkageRiskGraphLink,
  type LinkageRiskRemediation,
  type LinkageRiskShop,
} from './linkage-risk-model';

/**
 * 16.5.2.2 关联风险 前端模型的纯函数断言。
 *
 * 🔴 这个页面上最容易做丢的事，前端这一层再挡一道：
 * **「读不到」显示成「读不到」而不是「没有关联」**、**四种空态文案各不相同**、
 * **前端不重算后端的判定**、**不提供任何绕关联的入口**。
 */

function capability(overrides: Partial<LinkageRiskCapabilities['capabilities'][number]> = {}) {
  return {
    code: 'DEVICE',
    label: '设备关联',
    available: true,
    autoDiscoverable: false,
    source: '本服务自持',
    ownerModule: '',
    impact: '能判，但不自动发现',
    ...overrides,
  };
}

function caps(items: LinkageRiskCapabilities['capabilities']): LinkageRiskCapabilities {
  return {
    capabilities: items,
    shopSourceNote: '',
    dimensionNote: '',
    registerNote: '',
    graphNote: '',
    alertNote: '',
    remediationNote: '',
    officialApiNote: '',
    downstreamNote: '',
    financeNote: '',
  };
}

function shop(overrides: Partial<LinkageRiskShop> = {}): LinkageRiskShop {
  return {
    shopId: 'shop-1',
    code: 'S1',
    name: '测试店铺',
    platformCode: 'AMAZON',
    platformName: 'Amazon',
    status: 'ACTIVE',
    legalEntityId: null,
    legalEntityName: null,
    legalEntityNameAvailable: true,
    linkCount: 0,
    dimensions: [],
    ...overrides,
  };
}

describe('16.5.2.2 能力声明：读不到的维度必须能被单独挑出来', () => {
  it('available=false 的维度被单独列出（页面据此显示「读不到 + 归属模块」）', () => {
    const result = unavailableCapabilities(
      caps([
        capability({ code: 'DEVICE' }),
        capability({
          code: 'PAYMENT_METHOD',
          label: '支付方式关联',
          available: false,
          ownerModule: '12.7.1.1',
        }),
      ]),
    );
    expect(result).toHaveLength(1);
    expect(result[0]?.code).toBe('PAYMENT_METHOD');
    expect(result[0]?.ownerModule).toBe('12.7.1.1');
  });

  it('「可登记但不自动发现」的维度与「读不到」的维度不混在一起', () => {
    const result = registeredOnlyCapabilities(
      caps([
        capability({ code: 'DEVICE', autoDiscoverable: false }),
        capability({ code: 'LEGAL_ENTITY', autoDiscoverable: true }),
        capability({ code: 'PAYOUT_ACCOUNT', available: false }),
      ]),
    );
    expect(result.map((c) => c.code)).toEqual(['DEVICE']);
  });

  it('能力声明为空时两个助手都返回空数组（不崩）', () => {
    expect(unavailableCapabilities(null)).toEqual([]);
    expect(registeredOnlyCapabilities(null)).toEqual([]);
  });
});

describe('16.5.2.2 法人绑定：三种情况文案各不相同', () => {
  it('未绑定法人', () => {
    expect(legalEntityText(shop({ legalEntityId: null }))).toBe('未绑定法人');
  });

  it('法人档案读不到 —— 不是「没有法人」', () => {
    const text = legalEntityText(
      shop({ legalEntityId: 'le-1', legalEntityNameAvailable: false, legalEntityName: null }),
    );
    expect(text).toContain('读不到');
    expect(text).not.toContain('未绑定');
  });

  it('法人档案里没有这一条', () => {
    const text = legalEntityText(
      shop({ legalEntityId: 'le-1', legalEntityNameAvailable: true, legalEntityName: null }),
    );
    expect(text).toContain('没有这一条');
  });

  it('有名字就显示名字', () => {
    expect(
      legalEntityText(
        shop({ legalEntityId: 'le-1', legalEntityNameAvailable: true, legalEntityName: '测试法人' }),
      ),
    ).toBe('测试法人');
  });

  it('店铺读不到时也不编一个名字', () => {
    expect(legalEntityText(null)).toBe('读不到店铺');
  });
});

describe('16.5.2.2 四种空态文案互不相同', () => {
  it('还没登记策略 / 读不到店铺 / 还没登记事实 / 没有发现关联组 / 出错', () => {
    const titles = [
      emptyState('NO_POLICY').title,
      emptyState('NO_SHOPS').title,
      emptyState('NO_FACTS').title,
      emptyState('NO_CLUSTER').title,
      emptyState('ERROR').title,
    ];
    expect(new Set(titles).size).toBe(5);
  });

  it('「还没登记策略」必须说清这不等于「没有关联」', () => {
    expect(emptyState('NO_POLICY').description).toContain('不等于');
  });

  it('「没有发现关联组」必须说清只覆盖已登记的事实', () => {
    expect(emptyState('NO_CLUSTER').description).toContain('已登记的事实');
  });

  it('「读不到店铺」说的是读不到，不是「没有店铺」', () => {
    expect(emptyState('NO_SHOPS').title).toBe('读不到店铺');
  });
});

describe('16.5.2.2 前端不重算后端的判定', () => {
  it('严重度与风险等级原样显示后端算好的标签', () => {
    expect(severityLabel('CRITICAL')).toBe('严重');
    expect(severityTone('CRITICAL')).toBe('danger');
    expect(severityTone('LOW')).toBe('neutral');
    // 后端给了未知值也不崩，原样透出
    expect(severityLabel('WHATEVER')).toBe('WHATEVER');
  });

  it('图谱摘要原样透出后端给的三个计数', () => {
    expect(
      graphSummary({
        known: true,
        note: '',
        shopCount: 4,
        linkCount: 2,
        clusterCount: 1,
        nodes: [],
        links: [],
        clusters: [],
      }),
    ).toBe('4 家店铺 · 2 条关联边 · 1 个关联组');
    expect(
      graphSummary({
        known: false,
        note: '',
        shopCount: 0,
        linkCount: 0,
        clusterCount: 0,
        nodes: [],
        links: [],
        clusters: [],
      }),
    ).toBe('关联图谱尚未启用');
  });

  it('关联组维度标签按后端给的维度码翻译', () => {
    const cluster: LinkageRiskGraphCluster = {
      clusterKey: 'k',
      shopIds: ['a', 'b'],
      shopCount: 2,
      dimensions: ['DEVICE', 'LEGAL_ENTITY'],
      severity: 'HIGH',
      severityLabel: '高',
      riskLevel: 'HIGH',
      riskLevelLabel: '高',
      crossPlatform: false,
      summary: '',
    };
    expect(clusterDimensionText(cluster)).toBe('设备 / 法人');
  });

  it('关联边文案写清「自动发现」还是「登记事实」', () => {
    const link: LinkageRiskGraphLink = {
      dimension: 'DEVICE',
      dimensionLabel: '设备',
      factorKey: 'DEV-01',
      factorLabel: 'DEV-01',
      platformCode: 'AMAZON',
      shopCount: 2,
      shopIds: ['a', 'b'],
      autoDiscovered: false,
      sensitivity: 'HIGH',
    };
    expect(factorText(link)).toContain('登记事实');
    expect(factorText({ ...link, autoDiscovered: true })).toContain('自动发现');
  });
});

describe('16.5.2.2 状态机：终态不提供入口', () => {
  function alert(status: string): LinkageRiskAlert {
    return {
      alertId: 'a1',
      clusterId: 'c1',
      clusterKey: 'k',
      alertType: 'SHARED_FACTOR',
      severity: 'HIGH',
      severityLabel: '高',
      status,
      statusLabel: alertStatusLabel(status),
      shopCount: 2,
      dimensions: ['DEVICE'],
      title: 't',
      summary: 's',
      detectedAt: '2026-10-23T00:00:00Z',
      acknowledgedBy: null,
      acknowledgedAt: null,
      acknowledgeReason: '',
      resolvedBy: null,
      resolvedAt: null,
      resolutionNote: '',
      dismissedBy: null,
      dismissedAt: null,
      dismissReason: '',
      version: 1,
      createdAt: '2026-10-23T00:00:00Z',
      updatedAt: '2026-10-23T00:00:00Z',
      shops: [],
      actions: [],
      remediations: [],
    };
  }

  it('待处置 / 已确认可处置；已整改 / 已忽略是终态', () => {
    expect(canActOnAlert(alert('OPEN'))).toBe(true);
    expect(canActOnAlert(alert('ACKNOWLEDGED'))).toBe(true);
    expect(canActOnAlert(alert('REMEDIATED'))).toBe(false);
    expect(canActOnAlert(alert('DISMISSED'))).toBe(false);
    expect(canActOnAlert(null)).toBe(false);
  });

  it('建议的终态同样不提供入口', () => {
    const remediation = { status: 'DONE' } as LinkageRiskRemediation;
    expect(canActOnRemediation(remediation)).toBe(false);
    expect(canActOnRemediation({ status: 'PROPOSED' } as LinkageRiskRemediation)).toBe(true);
    expect(canActOnRemediation(null)).toBe(false);
  });
});

describe('16.5.2.2 标签映射', () => {
  it('维度、状态、来源、处置动作都有中文标签', () => {
    expect(dimensionLabel('LEGAL_ENTITY')).toBe('法人');
    expect(dimensionLabel('NETWORK_IP')).toBe('网络 IP');
    expect(alertStatusLabel('REMEDIATED')).toBe('已整改');
    expect(remediationStatusLabel('PROPOSED')).toBe('待处理');
    expect(sourceLabel('PLATFORM_NOTICE')).toBe('平台通知');
    expect(actionLabel('RESCAN')).toBe('重扫发现');
  });
});
