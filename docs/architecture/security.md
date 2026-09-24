# 安全架构

> Status: Living — 持续完善

## 信任域

```
[Internet] → [CDN/WAF] → [API Gateway] → [Internal K8s Network]
                                                    ↓
                                          [Apps / Workers / DB]
```

- **Public**: 只有 `apps/web` / `apps/admin` 暴露 HTTPS
- **Internal**: API / Workers / DB / Kafka 全部 K8s 内部网络，不可公网直连

## 认证

- 用户: JWT (RS256) + refresh token，token 旋转周期 7 天
- 服务间: mTLS (Istio/Linkerd service mesh) 或 service account JWT
- SaaS admin: 强制 WebAuthn + IP 白名单

## 授权

- RBAC: 角色 / 权限点 / 资源范围三元组，详见 [`docs/architecture/rbac.md`](rbac.md)（待写）
- 数据范围授权: 按 `tenant_id` + `org_unit_id` 过滤
- 平台 API 凭据: 经 secret manager 注入，永不落日志

## 密钥管理

- 生产环境所有 secret 走 **HashiCorp Vault** 或 **AWS Secrets Manager**
- K8s 用 **External Secrets Operator** 同步到 K8s Secret
- 旋转周期: API key 90 天，DB 密码 30 天
- 开发环境: `.env.example` 仅放变量名和占位

## 数据加密

- **传输**: TLS 1.3 全链路
- **存储**: DB TDE / S3 SSE-KMS
- **应用层**: PII 字段加密（手机号 / 身份证 / 银行卡），密钥 per-tenant

## 审计日志

- 所有写操作 / 权限变更 / 登录登出 落审计日志
- 不可篡改（append-only + 周期 hash chain）
- 留存期: 跨境合规要求 ≥ 5 年

## 漏洞与依赖

- **CI 强制**: CodeQL (SAST) + Trivy (image scan) + npm audit
- **依赖更新**: Renovate bot 每周自动开 PR
- **漏洞响应**: 见 [`docs/operations/runbooks/security-incident.md`](../operations/runbooks/security-incident.md)（待写）

## 跨境数据合规

- 欧盟数据 (GDPR): 数据驻留 EU 区域，详见 [`docs/compliance/gdpr.md`](../compliance/gdpr.md)（待写）
- 数据出境: 标准合同条款 (SCC) + 影响评估
- 用户删除权: 软删除 + 30 天硬删除 pipeline