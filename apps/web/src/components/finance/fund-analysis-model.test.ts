import { describe, expect, it } from 'vitest';
import {
  formatFundAmount,
  formatFundDays,
  formatFundExposureShare,
  formatFundMoney,
  formatFundShare,
  fundExposureDirectionLabel,
  fundGranularityLabel,
  fundSeverityLabel,
  fundThresholdSourceLabel,
} from './fund-analysis-model';

/**
 * 12.7.2.1 资金分析前端模型的单测。
 *
 * <p>重点守住四条**界面纪律**（它们一旦被破坏，页面就会开始骗人）：
 *
 * <ol>
 *   <li>金额**原样展示**，前端不做任何业务算术；</li>
 *   <li>「算不出」与「0」必须能分开（服务端给 null，前端显示「—」）——
 *       周转天数与占比都靠这条区分「0 天」和「算不出」；</li>
 *   <li>单位换算（比例 → 百分数）允许，算术不许：分母在服务端已经算好了；</li>
 *   <li>可读名取**服务端下发**的值（方向标签、阈值来源），页面不写死。</li>
 * </ol>
 */

describe('时间粒度的可读名', () => {
  it('三种粒度各自有可读名，且不认识的原样返回', () => {
    expect(fundGranularityLabel('DAY')).toBe('按日');
    expect(fundGranularityLabel('WEEK')).toBe('按周');
    expect(fundGranularityLabel('MONTH')).toBe('按月');
    expect(fundGranularityLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('敞口方向的可读名', () => {
  it('优先用服务端下发的 label，不认识的方向原样返回', () => {
    expect(fundExposureDirectionLabel('LONG_ASSET')).toBe('多外币资产');
    expect(fundExposureDirectionLabel('SHORT_ASSET')).toBe('多外币负债');
    expect(fundExposureDirectionLabel('FLAT')).toBe('无净敞口');
    expect(fundExposureDirectionLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(fundExposureDirectionLabel('SOMETHING_NEW', '服务端给的标签')).toBe('服务端给的标签');
  });
});

describe('预警严重度与阈值来源的可读名', () => {
  it('严重度三档各有可读名', () => {
    expect(fundSeverityLabel('CRITICAL')).toBe('严重');
    expect(fundSeverityLabel('WARN')).toBe('警告');
    expect(fundSeverityLabel('INFO')).toBe('提示');
    expect(fundSeverityLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('阈值来源区分「租户自配」与「蓝图默认」', () => {
    expect(fundThresholdSourceLabel('TENANT_CONFIG')).toBe('租户自配');
    expect(fundThresholdSourceLabel('BLUEPRINT_DEFAULT')).toBe('蓝图默认');
    expect(fundThresholdSourceLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('金额的可读形态', () => {
  it('原样透传（只做显示格式化），不做任何业务算术', () => {
    expect(formatFundAmount(1234.5)).toBe('1234.5000');
    expect(formatFundAmount(0)).toBe('0.0000');
    expect(formatFundMoney(1234.5, 'USD')).toBe('1234.5000 USD');
    expect(formatFundMoney(1234.5, null)).toBe('1234.5000');
  });

  it('「算不出」显示为破折号，不显示 0', () => {
    expect(formatFundAmount(null)).toBe('—');
    expect(formatFundAmount(undefined)).toBe('—');
    expect(formatFundMoney(null, 'USD')).toBe('—');
    expect(formatFundMoney(undefined, 'USD')).toBe('—');
  });
});

describe('占比与天数的可读形态', () => {
  it('占比只做单位换算（比例 → 百分数），不做加总', () => {
    expect(formatFundShare(0.6667)).toBe('66.67%');
    expect(formatFundShare(0)).toBe('0.00%');
    expect(formatFundExposureShare(0.651163)).toBe('65.12%');
  });

  it('「没有分母」显示为破折号，不显示 0%', () => {
    expect(formatFundShare(null)).toBe('—');
    expect(formatFundShare(undefined)).toBe('—');
    expect(formatFundExposureShare(null)).toBe('—');
  });

  it('天数由服务端算好，页面原样展示；null 显示为破折号而不是 0 天', () => {
    expect(formatFundDays(7.3333)).toBe('7.3333 天');
    expect(formatFundDays(-2.6667)).toBe('-2.6667 天');
    expect(formatFundDays(null)).toBe('—');
    expect(formatFundDays(undefined)).toBe('—');
  });
});
