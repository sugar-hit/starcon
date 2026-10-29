import { CustomReportSharedView } from '@/components/analytics/custom-report-shared-view';

/**
 * 13.6.1.1 自定义报表 · 分享链接视图（路由 `/analytics/custom-report/shared/{token}`）。
 *
 * <p>🔴 **分享链接不等于公开访问**：令牌绑定租户 + 绑定报表归属（个人 / 团队）。
 * 未授权访问与「报表不存在」返回**逐字相同**的 404 ⇒ 访客**无法分辨**这两种情况，
 * 报表的存在性不泄露。
 */
export default async function SharedCustomReportPage({
  params,
}: {
  params: Promise<{ token: string }>;
}) {
  const { token } = await params;
  return <CustomReportSharedView token={token} />;
}
