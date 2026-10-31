import { describe, expect, it } from 'vitest';
import {
  availableCapabilities,
  modeLabel,
  modeTone,
  migratableTargets,
  outcomeLabel,
  outcomeTone,
  progressPercent,
  reasonLabel,
  reportBalanced,
  reportDownloadable,
  reportUrl,
  rollbackReportDownloadable,
  rollbackReportUrl,
  rollbackStatusLabel,
  rollbackStatusTone,
  rollbackable,
  rowCounts,
  rowRollbackLabel,
  rowRollbackTone,
  runStatusLabel,
  runStatusTone,
  unavailableCapabilities,
  unmigratableTargets,
} from './data-migration-model';
import type {
  DataMigrationCapabilities,
  DataMigrationRowPage,
  DataMigrationRunRow,
  DataMigrationTargetRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 18.1.3.2「数据迁移」前端模型的单元测试。
 *
 * 🔴 这里钉住的是**页面上最容易做丢的判断**：
 * 「已受理 ≠ 已完成」「试运行不写目标库」「没接通要显式摆出来」
 * 「核对报告的三个口径要加得上」「回滚有前提」「逐行回滚结论与任务级不是一套词」
 * 「读不到 ≠ 没有」。
 * 这些判断一旦做丢，界面会在**全绿**的情况下骗人 ——
 * 尤其是「逐行回滚结论」：把「这一行删掉了」显示成「已回滚」，
 * 会让人以为**每一行**都删干净了。
 */

function run(overrides: Partial<DataMigrationRunRow> = {}): DataMigrationRunRow {
  return {
    id: '00000000-0000-4000-8000-000000000000',
    runNo: 'MIG-00000000-00000001',
    mode: 'APPLY',
    targetModel: 'SPU',
    mappingId: 'mapping-1',
    mappingCode: 'JST_SPU_V1',
    sourceFilename: 'spu.csv',
    sourceSha256: 'a'.repeat(64),
    status: 'SUCCEEDED',
    rollbackStatus: 'NONE',
    totalRows: 4,
    processedRows: 4,
    succeededRows: 2,
    failedRows: 1,
    skippedRows: 1,
    rolledBackRows: 0,
    reportFileId: 'report-1',
    rollbackReportFileId: '',
    error: '',
    actorId: 'user-1',
    requestedAt: '2026-10-31T08:00:00Z',
    startedAt: '2026-10-31T08:00:01Z',
    finishedAt: '2026-10-31T08:00:02Z',
    rolledBackAt: '',
    createdAt: '2026-10-31T08:00:00Z',
    ...overrides,
  };
}

function target(overrides: Partial<DataMigrationTargetRow> = {}): DataMigrationTargetRow {
  return {
    code: 'SPU',
    label: '商品 SPU',
    ownerService: 'catalog',
    ownerModule: '3.1.2',
    fields: [
      { key: 'code', label: '商品编码', required: true, defaultValue: '', help: '' },
      { key: 'name', label: '商品名称', required: true, defaultValue: '', help: '' },
    ],
    limitation: '',
    ...overrides,
  };
}

function capabilities(
  overrides: Partial<DataMigrationCapabilities> = {},
): DataMigrationCapabilities {
  return {
    capabilities: [
      {
        module: '18.1.3.2',
        name: '映射配置（外部字段 → 内部模型）',
        available: true,
        basis: 'platform_migration_mapping 表存字段级映射',
        ownerModule: '',
        limitation: '',
      },
      {
        module: '18.1.3.2',
        name: '**迁移到订单 / 库存 / 凭证**',
        available: false,
        basis: '这些域的属主服务没有面向迁移的写入端点',
        ownerModule: '9.1 / 7.1 / 12.5.2.1',
        limitation: '强行迁移会绕过属主服务的校验链',
      },
    ],
    gaps: [],
    ...overrides,
  };
}

describe('模式与状态口径', () => {
  it('试运行与正式迁移各有各的说法', () => {
    expect(modeLabel('DRY_RUN')).toBe('试运行');
    expect(modeLabel('APPLY')).toBe('正式迁移');
  });

  it('试运行**不标成成功** —— 它跑完之后目标库里一个记录都没有', () => {
    expect(modeTone('DRY_RUN')).toBe('muted');
    expect(modeTone('APPLY')).toBe('warn');
  });

  it('迁移状态与回滚状态各有各的词表', () => {
    expect(runStatusLabel('PENDING')).toBe('待执行');
    expect(runStatusLabel('RUNNING')).toBe('执行中');
    expect(runStatusLabel('SUCCEEDED')).toBe('已完成');
    expect(runStatusLabel('FAILED')).toBe('已失败');
    expect(runStatusTone('FAILED')).toBe('danger');

    expect(rollbackStatusLabel('NONE')).toBe('未回滚');
    expect(rollbackStatusLabel('RUNNING')).toBe('回滚中');
    expect(rollbackStatusLabel('SUCCEEDED')).toBe('已回滚');
    expect(rollbackStatusLabel('FAILED')).toBe('回滚失败');
    expect(rollbackStatusTone('FAILED')).toBe('danger');
  });

  it('逐行回滚结论用的是**行级**词表，不是任务级', () => {
    // 🔴 「DELETED」是行级（这一行删掉了）；任务级没有这个词。
    expect(rowRollbackLabel('DELETED')).toBe('已删除');
    expect(rowRollbackLabel('FAILED')).toBe('未删除');
    expect(rowRollbackLabel('NONE')).toBe('未回滚');
    expect(rowRollbackTone('DELETED')).toBe('ok');
    expect(rowRollbackTone('FAILED')).toBe('danger');
    // 任务级的「已回滚」**不是**行级的说法 —— 两者不能混。
    expect(rowRollbackLabel('SUCCEEDED')).toBe('SUCCEEDED');
  });

  it('逐行结论的口径与语气', () => {
    expect(outcomeLabel('SUCCEEDED')).toBe('成功');
    expect(outcomeLabel('FAILED')).toBe('失败');
    expect(outcomeLabel('SKIPPED')).toBe('跳过');
    expect(outcomeTone('FAILED')).toBe('danger');
    expect(outcomeTone('SKIPPED')).toBe('warn');
  });
});

describe('原因码', () => {
  it('每个原因码都有人话，不是把原始码甩给用户', () => {
    expect(reasonLabel('MISSING_REQUIRED_FIELD')).toBe('必填字段为空');
    expect(reasonLabel('INVALID_CODE')).toBe('编码格式不合法');
    expect(reasonLabel('ALREADY_EXISTS')).toBe('目标库已有同编码');
    expect(reasonLabel('DUPLICATE_IN_FILE')).toBe('文件内编码重复');
    expect(reasonLabel('PARENT_SPU_NOT_FOUND')).toBe('所属商品在目标库不存在');
    expect(reasonLabel('UPSTREAM_REJECTED')).toBe('目标库拒绝了这行');
  });

  it('「查不清就跳过」也是一种失败原因，不是漏了', () => {
    expect(reasonLabel('TARGET_CHECK_INCONCLUSIVE')).toBe('目标库查不清（已跳过）');
  });

  it('空原因码与未知原因码如实显示', () => {
    expect(reasonLabel('')).toBe('—');
    expect(reasonLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('能力声明与目标模型', () => {
  it('没接通的格子显式摆出来，不混进空态', () => {
    const caps = capabilities();
    expect(unavailableCapabilities(caps)).toHaveLength(1);
    expect(unavailableCapabilities(caps)[0]?.ownerModule).toBe('9.1 / 7.1 / 12.5.2.1');
    expect(availableCapabilities(caps)).toHaveLength(1);
  });

  it('没接通的业务域照样出现在目录里，且**没有**字段', () => {
    const targets = [
      target(),
      target({
        code: 'ORDER',
        label: '销售订单',
        ownerService: 'trade',
        ownerModule: '9.1',
        fields: [],
        limitation: 'trade 没有面向迁移的写入端点',
      }),
    ];
    expect(migratableTargets(targets).map((t) => t.code)).toEqual(['SPU']);
    expect(unmigratableTargets(targets).map((t) => t.code)).toEqual(['ORDER']);
    // 没接通的模型**必须**写明缺什么 —— 页面要照着它摆出边界。
    expect(unmigratableTargets(targets)[0]?.limitation).not.toBe('');
  });
});

describe('进度与核对口径', () => {
  it('没有总行数时**不编一个百分比**出来', () => {
    expect(progressPercent(run({ totalRows: 0 }))).toBeNull();
    expect(progressPercent(null)).toBeNull();
  });

  it('进度按已处理行数算，跑完是 100%', () => {
    expect(progressPercent(run({ status: 'RUNNING', processedRows: 1, totalRows: 4 }))).toBe(25);
    expect(progressPercent(run())).toBe(100);
  });

  it('三个口径加起来必须等于处理过的行数 —— 对不上就要能看出来', () => {
    expect(reportBalanced(run())).toBe(true);
    expect(reportBalanced(run({ succeededRows: 3 }))).toBe(false);
    expect(reportBalanced(run({ processedRows: 0 }))).toBe(true);
    expect(reportBalanced(null)).toBe(false);
  });

  it('逐行结论的三个计数来自服务端', () => {
    const page: DataMigrationRowPage = {
      items: [],
      page: 0,
      size: 50,
      total: 4,
      succeededCount: 2,
      failedCount: 1,
      skippedCount: 1,
    };
    expect(rowCounts(page)).toEqual({ succeeded: 2, failed: 1, skipped: 1 });
    expect(rowCounts(null)).toEqual({ succeeded: 0, failed: 0, skipped: 0 });
  });
});

describe('回滚的前提', () => {
  it('正式迁移跑完才能回滚', () => {
    expect(rollbackable(run())).toBe(true);
    expect(rollbackable(run({ status: 'RUNNING' }))).toBe(false);
    expect(rollbackable(run({ status: 'PENDING' }))).toBe(false);
  });

  it('试运行**不能回滚** —— 它没写过任何东西', () => {
    expect(rollbackable(run({ mode: 'DRY_RUN' }))).toBe(false);
  });

  it('回滚过一次的不再能回滚（回滚是一次性的动作）', () => {
    expect(rollbackable(run({ rollbackStatus: 'SUCCEEDED' }))).toBe(false);
    expect(rollbackable(run({ rollbackStatus: 'RUNNING' }))).toBe(false);
  });

  it('一条都没迁成的任务没有可回滚的东西', () => {
    expect(rollbackable(run({ succeededRows: 0 }))).toBe(false);
    expect(rollbackable(null)).toBe(false);
  });
});

describe('报告下载', () => {
  it('报告文件存在才能给下载入口', () => {
    expect(reportDownloadable(run())).toBe(true);
    expect(reportDownloadable(run({ reportFileId: '' }))).toBe(false);
    expect(reportDownloadable(null)).toBe(false);
  });

  it('回滚报告是**另一份**文件，不覆盖核对报告', () => {
    expect(rollbackReportDownloadable(run())).toBe(false);
    expect(rollbackReportDownloadable(run({ rollbackReportFileId: 'rb-1' }))).toBe(true);
    expect(reportUrl('run-1')).toBe('/api/platform/data-migration/runs/run-1/report');
    expect(rollbackReportUrl('run-1')).toBe(
      '/api/platform/data-migration/runs/run-1/rollback-report',
    );
    // 两份报告的 URL 不许是同一条 —— 那会让「回滚删了什么」覆盖「迁成了什么」。
    expect(reportUrl('run-1')).not.toBe(rollbackReportUrl('run-1'));
  });
});
