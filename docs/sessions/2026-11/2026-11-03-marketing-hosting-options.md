# 官网托管方式评估

结论：独立marketing是静态多页面及浏览器交互，构建后dist可托管，不依赖常驻Node或Java。无需为官网单独购买ECS/SLB。新Cloudflare项目可选择Workers Static Assets，Pages仍可用；主要面向中国大陆访问者时比较OSS+CDN并从目标网络实测。

官方依据：Cloudflare Workers Best Practices（https://developers.cloudflare.com/workers/best-practices/workers-best-practices/）、Static Assets计费（https://developers.cloudflare.com/workers/static-assets/billing-and-limitations/）、Custom Domains（https://developers.cloudflare.com/workers/configuration/routing/custom-domains/）；阿里云OSS静态托管（https://www.alibabacloud.com/help/en/oss/user-guide/hosting-static-websites）。

本地核验：dist约61MiB，实际首屏桌面视频约6MiB、移动视频约2MiB。构建会复制全部public旧媒体，上线前剔除无引用版本；进入控制台仍有localhost:3000/products，需替换正式域名。只上传marketing构建产物，私有文档不入部署包。

本轮只提供选型建议，未新增云资源、未公开部署；后续需确认域名、访问者区域与部署流程。
