# 官网正式域名与上线顺序建议

## 用户意图

用户拥有www.starcon.ai，希望SEO/GEO、服务部署、逐页内容审查完成后再正式上线，询问当前是否适合直接使用正式域名。未授权本轮实际发布或修改DNS。

## 当前只读核验

首页仍有#占位入口和localhost:3000控制台链接。独立详情页缺少各自description等完整搜索元信息；尚未形成canonical、sitemap、404和环境索引策略。此前内容按授权为商业方案评审稿，含目标状态能力，正式发布前需与交付范围逐项核对。

## 建议（待用户决定）

当前完整版先进入受保护的preview.starcon.ai等预发布环境，设置访问控制并辅以noindex。正式www保留，逐页审查、关键用户路径和正式域名技术验证完成后再开放。预发布不靠robots.txt保护内容。无需等全部ERP规划实现，可先发布已审核且能兑现的范围，隐藏未建入口；SEO可在发布前准备，上线后再验证真实抓取和索引。GEO以清晰事实、可引用内容及品牌实体一致性为基础，不承诺被AI引用。

## 依据

Google AI features: https://developers.google.com/search/docs/appearance/ai-features
Google noindex: https://developers.google.com/search/docs/crawling-indexing/block-indexing
Google robots.txt: https://developers.google.com/search/docs/crawling-indexing/robots/intro

本轮无云资源、DNS、索引或公开发布变更。后续先确定首批公开页面及服务承诺，再编制上线审查清单。
