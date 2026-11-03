import { describe, expect, it } from 'vitest';
import type {
  ApiCallMonitorAlertRow,
  ApiCallMonitorCapabilities,
  ApiCallMonitorOptions,
} from '../../../../../packages/api-contracts/src/index';
import {
  alertStatusLabel,
  alertStatusTone,
  alertTypeLabel,
  errorCodeLabel,
  formatDuration,
  formatRate,
  formatWindow,
  outcomeLabel,
  outcomeTone,
  quotaTargetHint,
  severityTone,
  thresholdText,
  unavailableCapabilities,
} from './call-monitor-model';

/**
 * 18.1.1.3 调用监控 前端模型的单测。
 *
 * 🔴 这组用例守的是五件最容易做丢的事：
 *
 * 1. **「限流命中」与「凭证未通过」不能是同一种黄** —— 前者是流量问题，
 *    后者是对接方自己的凭证问题，混成一种黄，排查的人就得自己猜是哪一类。
 * 2. **不可用的下游必须能被筛出来** —— 卷 01 的工作台告警至今未交付，
 *    把它混进空态等于告诉运营「告警已经推给工作台了」。
 * 3. **限流告警必须指路** —— 「到 18.1.1.1 的配额设置里调」+ 端点 + 建议值，一个都不能少。
 * 4. **错误码要翻译成人话** —— 埋点里存的是机器可读的码，页面上要能直接读懂。
 * 5. **比率与耗时的格式化不能失真** —— 错误率是比例（0.05 → 5.00%），耗时跨秒要换算。
 */
describe('18.1.1.3 调用监控前端模型', () => {
  it('结论码各有各的说法，且语气分得开', () => {
    expect(outcomeLabel('SUCCESS')).toBe('成功');
    expect(outcomeLabel('AUTH_FAILED')).toBe('凭证未通过');
    expect(outcomeLabel('RATE_LIMITED')).toBe('被限流');
    expect(outcomeLabel('ERROR')).toBe('服务端错误');
    expect(outcomeLabel('')).toBe('—');

    expect(outcomeTone('SUCCESS')).toBe('ok');
    // 🔴 限流是黄（流量问题），凭证未通过是红（对接方自己的问题）—— 不能同色。
    expect(outcomeTone('RATE_LIMITED')).toBe('warn');
    expect(outcomeTone('AUTH_FAILED')).toBe('danger');
    expect(outcomeTone('FORBIDDEN')).toBe('danger');
    expect(outcomeTone('NOT_FOUND')).toBe('muted');
  });

  it('告警类型与状态的说法固定', () => {
    expect(alertTypeLabel('ERROR_RATE_HIGH')).toBe('错误率越线');
    expect(alertTypeLabel('SLOW_CALL')).toBe('慢调用越线');
    expect(alertTypeLabel('RATE_LIMIT_SPIKE')).toBe('限流命中越线');

    expect(alertStatusLabel('OPEN')).toBe('待处理');
    expect(alertStatusLabel('ACKNOWLEDGED')).toBe('已确认');
    expect(alertStatusLabel('RESOLVED')).toBe('已闭环');

    expect(alertStatusTone('OPEN')).toBe('danger');
    expect(alertStatusTone('ACKNOWLEDGED')).toBe('warn');
    expect(alertStatusTone('RESOLVED')).toBe('ok');

    expect(severityTone('CRITICAL')).toBe('danger');
    expect(severityTone('WARNING')).toBe('warn');
  });

  it('错误码翻译成人话，空串显示成破折号', () => {
    expect(errorCodeLabel('')).toBe('—');
    expect(errorCodeLabel('SECRET_MISMATCH')).toBe('Secret 不匹配');
    expect(errorCodeLabel('SCOPE_DENIED')).toBe('授权范围不足');
    expect(errorCodeLabel('IP_NOT_ALLOWED')).toBe('来源 IP 不在白名单');
    expect(errorCodeLabel('QUOTA_EXCEEDED')).toBe('超出配额');
    // 未知码原样透出，不吞掉。
    expect(errorCodeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('不可用的能力能被单独筛出来 —— 混进空态等于说谎', () => {
    const capabilities: ApiCallMonitorCapabilities = {
      capabilities: [
        {
          module: '18.1.1.3',
          name: '调用埋点（真实落库）',
          available: true,
          basis: '网关入口每次调用都落一行',
          ownerModule: '',
          impact: '',
        },
        {
          module: '18.1.1.3',
          name: '**工作台告警推送（卷 01）**',
          available: false,
          basis: '卷 01 的工作台告警服务至今未交付',
          ownerModule: '卷 01',
          impact: '告警落在本模块自己的表里',
        },
      ],
      downstreamGaps: [],
    };
    const unavailable = unavailableCapabilities(capabilities);
    expect(unavailable).toHaveLength(1);
    expect(unavailable[0]?.name).toContain('工作台告警推送');
    expect(unavailable[0]?.ownerModule).toBe('卷 01');
    expect(unavailableCapabilities(null)).toEqual([]);
  });

  it('限流告警要指路：端点 + 建议窗口 + 建议上限一个都不能少', () => {
    const alert = {
      id: 'a',
      appId: 'b',
      appCode: 'erp',
      alertType: 'RATE_LIMIT_SPIKE',
      alertTypeLabel: '限流命中越线',
      severity: 'WARNING',
      status: 'OPEN',
      endpointCode: '',
      metricValue: 12,
      thresholdValue: 10,
      windowSeconds: 900,
      windowStartedAt: '2026-10-31T00:00:00Z',
      windowEndedAt: '2026-10-31T00:15:00Z',
      message: '被配额挡下 12 次',
      suggestedAction: '去调配额',
      suggestedQuotaWindow: 'MINUTE',
      suggestedQuotaLimit: 101,
      quotaAdjustTarget: '/platform/apps/00000000-0000-4000-8000-000000000000/quotas',
      acknowledgedAt: '',
      acknowledgedBy: '',
      resolvedAt: '',
      createdAt: '2026-10-31T00:15:00Z',
    } as ApiCallMonitorAlertRow;

    const hint = quotaTargetHint(alert);
    expect(hint).toContain('18.1.1.1');
    expect(hint).toContain('/platform/apps/');
    expect(hint).toContain('/quotas');
    expect(hint).toContain('MINUTE');
    expect(hint).toContain('101');

    // 不是限流类告警就没有配额建议，不该显示空话。
    expect(quotaTargetHint({ ...alert, quotaAdjustTarget: '' })).toBe('');
  });

  it('阈值口径逐条给出来 —— 运营看到告警要知道是按什么判的', () => {
    const options: ApiCallMonitorOptions = {
      windows: [{ code: '900', label: '近 15 分钟', description: '统计窗口' }],
      thresholds: [
        {
          name: 'ERROR_RATE_WARNING',
          label: '错误率警告线',
          value: '0.05',
          unit: '比例',
          rule: '错误率 ≥ 5% 即开一条错误率告警',
        },
      ],
      endpoints: [],
    };
    const lines = thresholdText(options);
    expect(lines).toHaveLength(1);
    expect(lines[0]).toContain('错误率警告线');
    expect(lines[0]).toContain('0.05');
    expect(lines[0]).toContain('≥ 5%');
    expect(thresholdText(null)).toEqual([]);
  });

  it('比率与耗时的格式化不失真', () => {
    expect(formatRate(0.05)).toBe('5.00%');
    expect(formatRate(0)).toBe('0.00%');
    expect(formatRate(null)).toBe('—');

    expect(formatDuration(12.3)).toBe('12.3 ms');
    expect(formatDuration(1500)).toBe('1.50 s');
    expect(formatDuration(null)).toBe('—');

    expect(formatWindow(300)).toBe('近 5 分钟');
    expect(formatWindow(900)).toBe('近 15 分钟');
    expect(formatWindow(3600)).toBe('近 1 小时');
    expect(formatWindow(86400)).toBe('近 24 小时');
  });
});
