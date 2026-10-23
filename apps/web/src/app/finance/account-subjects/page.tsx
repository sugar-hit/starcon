import { AccountSubjectWorkspace } from '@/components/finance/account-subject-workspace';

/**
 * 12.5.1.1 会计科目。
 *
 * <p>蓝图 12-finance.md：「维护**法人账簿**的会计科目表：科目编码、名称、类别（资产/负债/权益/成本/损益）、
 * 余额方向、是否明细科目、关联税务属性；支持科目映射到业务事件。」输出：「科目表 + 映射规则。」关系：
 * 「上游 = 用户会计体系；下游 = 12.5.2.1 凭证、12.5.2.2 报表。**科目体系是用户自定的**，
 * 系统提供模板但必须允许完全自定义。」
 *
 * <p>这是一个**真实工作区**：科目表（真分页 + 真筛选 + 分类汇总）+ 新增/停用/启用科目 +
 * 从系统模板导入（逐条报出被跳过的编码）+ 业务事件 → 科目的映射规则（借贷齐备才让启用），
 * 加载 / 空 / 错误三态齐全，**没有占位数据**。
 *
 * <p>路由取 {@code /finance/account-subjects} —— 与同服务的其它端点（{@code /finance/settlement}、
 * {@code /finance/transactions}、{@code /finance/bad-debt} …）**并列**而非嵌套，首 token 也刻意不同。
 *
 * <p>页面<b>刻意没有</b>三个入口：「生成凭证」（12.5.2.1 未交付）、「会计期间」（12.5.1.2 未交付）、
 * 「关账」（12.5.1.3 未交付）；能力声明会如实把它们报成 {@code available=false} 并写明归属模块。
 */
export default function AccountSubjectPage() {
  return <AccountSubjectWorkspace />;
}
