import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * 16.1.2.1 违规与处罚工作区的**源码守卫**。
 *
 * <p>这个模块的写入动作全部落在**真实的违规记录**上（`platform_violation_subject` /
 * `platform_violation_remediation` / `platform_violation_evidence` / `platform_penalty`），
 * 而后端对这些写入口的校验很松：
 *
 * <ul>
 *   <li>整改要求只校验「非空」⇒ 一句通用文案就能落库；
 *   <li>凭证只校验「引用或说明至少给一个」⇒ 一个假文件名就能落库；
 *   <li>扣款只校验「金额 + 币种」⇒ 一个写死的金额就能落库，
 *       而扣款金额是要进 12 财务的；
 *   <li>主体挂载**校验得最严**（SKU 必须给 skuId）⇒ 写死的占位主体必然 422，
 *       而前端一旦把错误吞掉，界面就「看着像挂上了」。
 * </ul>
 *
 * <p>所以这里对工作区源码做字面量断言：这几处写死的演示值**不许再出现**。
 * 光靠代码评审守不住 —— 这个仓库的纪律一直靠人盯，人盯不住的东西就写进测试。
 */

const WORKSPACE = readFileSync(
  fileURLToPath(new URL('./platform-violations-workspace.tsx', import.meta.url)),
  'utf8',
);

describe('🔴 工作区源码里不许再有写死的演示值', () => {
  it('不许写死主体引用（它必然 422，而错误还会被吞掉）', () => {
    expect(WORKSPACE).not.toContain('MANUAL-SKU');
  });

  it('不许写死整改要求文案', () => {
    expect(WORKSPACE).not.toContain('按平台通知补齐材料');
  });

  it('不许写死凭证文件名', () => {
    expect(WORKSPACE).not.toContain('evidence.pdf');
  });

  it('不许写死扣款金额', () => {
    expect(WORKSPACE).not.toMatch(/penaltyAmount:\s*\d/);
    expect(WORKSPACE).not.toMatch(/penaltyCurrency:\s*'[A-Z]{3}'/);
  });

  it('🔴 不许把写入失败吞掉（吞掉 = 界面假装成功）', () => {
    expect(WORKSPACE).not.toMatch(/\.catch\(\(\)\s*=>\s*undefined\)/);
  });
});

describe('🔴 写入前一律走模型层的入参整理', () => {
  it('四个写入动作都调用了对应的 build*', () => {
    expect(WORKSPACE).toContain('buildSubjectInput');
    expect(WORKSPACE).toContain('buildRemediationInput');
    expect(WORKSPACE).toContain('buildEvidenceInput');
    expect(WORKSPACE).toContain('buildDeductionInput');
  });

  it('整理失败时把错误显示出来，而不是静默跳过', () => {
    // 每个 build* 的失败分支都要 setError —— 界面上得看得见「没写进去」。
    const errorBranches = WORKSPACE.match(/if \(!built\.ok\) \{\s*setError\(built\.error\);/g);
    expect(errorBranches?.length).toBe(4);
  });
});
