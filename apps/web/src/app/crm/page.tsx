import { getDomainSpec } from '@starcon/ui';
import { DomainHubWorkspace } from '@/components/domain-hub/domain-hub-workspace';

/**
 * 15 客户与客服系统 · 域首页。
 *
 * <p>🔴 这一页此前挂的是一个「域占位页」（`DomainPlaceholder`）—— 只有一张静态功能清单，
 * 而这个域下面的 24 个模块**全部已经交付**：消息收件箱 / 消息发送 / 消息分类 / 邮件收发 /
 * 邮件规则 / 评价列表 / 评价回复 / 差评处理 / 索评规则 / 客户列表 / 客户详情 / 跨店合并 /
 * 客户标签 / 接触记录 / 工单列表 / 工单处理 / 客服知识库 / 自动回复 / 关怀与催办 /
 * 会员与等级 / 积分与权益 / 生命周期与流失预警 / 购物车与弃单挽回。
 *
 * <p>占位页上写的「站内信：待实施」「工单系统：待实施」全是假的 ——
 * 用户点开「CRM / 客服」看到的是一张计划表，而真实工作区就在同一个目录里。
 *
 * <p>现在这一页是**真实的域索引**：列出来的每一个模块都对应
 * `apps/web/src/app` 下真实存在的页面，模块号与模块名取自那些页面自己写的文档注释，
 * 分组名取自功能蓝图原文。清单由 `tools/codegen/build-domain-modules.mjs` 生成，
 * 页面本身**不产出任何业务数据** —— 业务数据在各自的模块页里。
 */
export default function CrmPage() {
  const spec = getDomainSpec('crm');
  return (
    <div className="space-y-4">
      <header className="space-y-1">
        <h1 className="text-copy-20 font-semibold">CRM / 客服</h1>
        <p className="text-muted-foreground text-copy-13">
          {spec?.purpose ?? '客户档案、工单、平台站内信、评价管理'}
        </p>
      </header>
      <DomainHubWorkspace prefixes={['/crm']} />
    </div>
  );
}
