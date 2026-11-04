import { getDomainSpec } from '@starcon/ui';
import { DomainHubWorkspace } from '@/components/domain-hub/domain-hub-workspace';

/**
 * 10 物流与头程系统 · 域首页。
 *
 * <p>🔴 这一页此前挂的是一个「域占位页」（`DomainPlaceholder`）—— 只有一张静态功能清单，
 * 而这个域下面的 26 个模块**全部已经交付**：承运商列表 / 渠道配置 / 时效与限制 /
 * 运费计算器 / 渠道比价 / 运单列表 / 运单申请 / 改址与取消 / 包裹详情 / 面单模板 /
 * 轨迹查询 / 异常件 / 交运批次 / 揽收与签收确认 / 头程发运单 / 在途跟踪 / 海外仓连接 /
 * 备货预报 / 报关单管理 / 申报要素 / 关税与税费 / 贸易单证 / 成本分析 / 时效分析 /
 * 退货接收与处置决策 / 退运回国 / 弃置与销毁。
 *
 * <p>占位页上写的「智能路由推荐：待实施」「物流轨迹跟踪：待实施」全是假的 ——
 * 用户点开「物流中心」看到的是一张计划表，而真实工作区就在同一个目录里。
 *
 * <p>现在这一页是**真实的域索引**：列出来的每一个模块都对应
 * `apps/web/src/app` 下真实存在的页面，模块号与模块名取自那些页面自己写的文档注释，
 * 分组名取自功能蓝图原文。清单由 `tools/codegen/build-domain-modules.mjs` 生成，
 * 页面本身**不产出任何业务数据** —— 业务数据在各自的模块页里。
 */
export default function LogisticsPage() {
  const spec = getDomainSpec('logistics');
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">物流中心</h1>
        <p className="text-muted-foreground text-copy-13">
          {spec?.purpose ?? '物流商对接、路由推荐、轨迹跟踪'}
        </p>
      </header>
      <DomainHubWorkspace prefixes={['/logistics']} />
    </div>
  );
}
