{{/*
=============================================================================
starcon-backend —— 公共模板helpers
=============================================================================
*/}}

{{/*
chart 名（供 helm.sh/chart 标签用）
*/}}
{{- define "starcon-backend.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
短名
*/}}
{{- define "starcon-backend.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
全名。9 个服务的资源都以它为前缀：
  <fullname>            → 整个后端
  <fullname>-<服务名>   → 单个服务（Deployment / Service / Secret / PVC）
*/}}
{{- define "starcon-backend.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
公共标签
*/}}
{{- define "starcon-backend.labels" -}}
helm.sh/chart: {{ include "starcon-backend.chart" . }}
{{ include "starcon-backend.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: starcon
{{- with .Values.labels }}
{{ toYaml . }}
{{- end }}
{{- end }}

{{/*
选择器标签。**不要**把版本号之类的可变字段放进 selector —— 升级时会选不中旧 Pod。
*/}}
{{- define "starcon-backend.selectorLabels" -}}
app.kubernetes.io/name: {{ include "starcon-backend.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
ServiceAccount 名
*/}}
{{- define "starcon-backend.serviceAccountName" -}}
{{- if .Values.common.serviceAccount.create }}
{{- default (include "starcon-backend.fullname" .) .Values.common.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.common.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
镜像全名。
入参：dict "ctx" $ "name" "identity" "image" <image 块>
镜像仓库默认 starcon/<服务名>，与 deploy/docker/Dockerfile.backend 的
`--build-arg SERVICE=<服务名>` 构建约定对齐。
*/}}
{{- define "starcon-backend.image" -}}
{{- $ctx := .ctx -}}
{{- $name := .name -}}
{{- $img := .image | default dict -}}
{{- $repo := $img.repository | default (printf "starcon/%s" $name) -}}
{{- $tag := default $ctx.Chart.AppVersion ($img.tag | toString) -}}
{{- $registry := $ctx.Values.global.imageRegistry | default "" -}}
{{- if $registry -}}
{{- printf "%s/%s:%s" (trimSuffix "/" $registry) $repo $tag -}}
{{- else -}}
{{- printf "%s:%s" $repo $tag -}}
{{- end -}}
{{- end }}

{{/*
数据库连接串。
入参：dict "ctx" $ "svc" <服务块>
可用 services.<名>.databaseUrl 整串覆盖，否则按 postgres.urlTemplate 拼。

⚠️ postgres.host 为空时**直接报错**而不是渲染出一个 jdbc:postgresql://:5432/... 的坏串 ——
   那种串在模板期看不出来，要等到 Pod 启动、Spring 建连接池时才炸，排查成本高得多。
*/}}
{{- define "starcon-backend.databaseUrl" -}}
{{- $ctx := .ctx -}}
{{- $svc := .svc -}}
{{- if $svc.databaseUrl -}}
{{- $svc.databaseUrl -}}
{{- else -}}
{{- $pg := $ctx.Values.postgres -}}
{{- $host := $pg.host | toString -}}
{{- if not $host -}}
{{- fail "starcon-backend: postgres.host 必须填写（外部托管 PostgreSQL 的主机名），或为该服务单独设置 services.<名>.databaseUrl" -}}
{{- end -}}
{{- $url := $pg.urlTemplate -}}
{{- $url = $url | replace "{host}" $host -}}
{{- $url = $url | replace "{port}" ($pg.port | toString) -}}
{{- $url = $url | replace "{database}" ($svc.database | toString) -}}
{{- $url = $url | replace "{sslmode}" ($pg.sslMode | toString) -}}
{{- $url -}}
{{- end -}}
{{- end }}

{{/*
集群内服务地址：http://<fullname>-<目标服务>.<ns>.svc.cluster.local:<port>
用完整 FQDN 而不是短名 —— 短名依赖 resolv.conf 的 search 域，
在配置了自定义 search 域的集群里会解析到错误的地址。
入参：dict "ctx" $ "target" "identity"
*/}}
{{- define "starcon-backend.serviceURL" -}}
{{- $ctx := .ctx -}}
{{- $target := .target -}}
{{- $targetSvc := index $ctx.Values.services $target -}}
{{- $svcName := printf "%s-%s" (include "starcon-backend.fullname" $ctx) $target -}}
{{- printf "http://%s.%s.svc.cluster.local:%v" $svcName $ctx.Release.Namespace $targetSvc.port -}}
{{- end }}

{{/*
前端公开地址（web.publicUrl）。

🔴 这里**拒绝示例值**：values.yaml 里的 `http://starcon.example.com` 是占位符，
   不是可用的地址。带着它装上去，channel 的渠道 OAuth 授权完成会把浏览器送去
   一个不存在的域名 —— 而且安装过程一声不响，排查成本极高。
   与 postgres.host 一样，宁可在渲染期炸掉，也不要等到线上才发现。

入参：dict "ctx" $
*/}}
{{- define "starcon-backend.publicUrl" -}}
{{- $ctx := .ctx -}}
{{- $url := $ctx.Values.web.publicUrl | toString -}}
{{- if not $url -}}
{{- fail "starcon-backend: web.publicUrl 必须填写（前端 Ingress 的真实地址）" -}}
{{- end -}}
{{- if or (eq $url "http://starcon.example.com") (eq $url "https://starcon.example.com") -}}
{{- fail "starcon-backend: web.publicUrl 仍是示例值 starcon.example.com，必须填前端 Ingress 的真实地址（--set web.publicUrl=https://erp.你的域名）" -}}
{{- end -}}
{{- trimSuffix "/" $url -}}
{{- end }}

{{/*
密钥 Secret 名。
入参：dict "ctx" $ "name" "identity"
services.<名>.secret.existingSecret 非空时复用已有 Secret，不再创建 ExternalSecret。
*/}}
{{- define "starcon-backend.secretName" -}}
{{- $ctx := .ctx -}}
{{- $name := .name -}}
{{- $svc := index $ctx.Values.services $name -}}
{{- $default := printf "%s-%s" (include "starcon-backend.fullname" $ctx) $name -}}
{{- if and $svc.secret $svc.secret.existingSecret -}}
{{- $svc.secret.existingSecret -}}
{{- else -}}
{{- $default -}}
{{- end -}}
{{- end }}

{{/*
=============================================================================
单个服务的环境变量清单
入参：dict "ctx" $ "name" "identity" "svc" <服务块> "cfg" <合并后的公共块> "secretName" <secret 名>
=============================================================================
⚠️ 这些变量**不是**可选的装饰：
   - DB_URL / DB_USER / DB_PASSWORD —— Spring Boot 数据源，缺失则启动失败
   - DB_MIGRATION_USER / DB_MIGRATION_PASSWORD —— Flyway 迁移角色
   - INTERNAL_KEY —— **所有**服务都必需，且没有默认值（服务间授权密钥）
   - IDENTITY_URL —— 除 identity 外的所有服务必需，也没有默认值
   - CHANNEL_WEBHOOK_SIGNING_KEY —— channel 必需，没有默认值
跨服务地址一律显式覆盖：代码里的默认值是 http://127.0.0.1:80xx，
容器内那是自己，必然连错；个别默认值还指向了错误的端口号。
*/}}
{{- define "starcon-backend.serviceEnv" -}}
{{- $ctx := .ctx -}}
{{- $name := .name -}}
{{- $svc := .svc -}}
{{- $secretName := .secretName -}}
{{- $pg := $ctx.Values.postgres -}}
- name: DB_URL
  value: {{ include "starcon-backend.databaseUrl" (dict "ctx" $ctx "svc" $svc) | quote }}
- name: DB_USER
  value: {{ $svc.appUser | quote }}
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ $secretName | quote }}
      key: db-password
- name: DB_MIGRATION_USER
  value: {{ $pg.migrationUser | quote }}
- name: DB_MIGRATION_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ $secretName | quote }}
      key: db-migration-password
- name: INTERNAL_KEY
  valueFrom:
    secretKeyRef:
      name: {{ $secretName | quote }}
      key: internal-key
{{- range $envName, $target := $svc.upstreams }}
- name: {{ $envName }}
  value: {{ include "starcon-backend.serviceURL" (dict "ctx" $ctx "target" $target) | quote }}
{{- end }}
{{- range $key, $st := $svc.storage }}
{{- if $st.enabled }}
- name: {{ $st.env }}
  value: {{ $st.path | quote }}
{{- end }}
{{- end }}
{{- range $se := $svc.secretEnv }}
- name: {{ $se.name }}
  valueFrom:
    secretKeyRef:
      name: {{ $secretName | quote }}
      key: {{ $se.key }}
{{- end }}
{{- if and (eq $name "channel") $svc.oauth }}
{{- $publicBase := $svc.oauth.publicBaseUrl }}
{{- if not $publicBase }}
{{- $publicBase = include "starcon-backend.serviceURL" (dict "ctx" $ctx "target" "channel") }}
{{- end }}
- name: CHANNEL_OAUTH_PUBLIC_BASE_URL
  value: {{ $publicBase | quote }}
{{- $uiReturn := $svc.oauth.uiReturnUrl }}
{{- if not $uiReturn }}
{{- $uiReturn = printf "%s/channels" (include "starcon-backend.publicUrl" (dict "ctx" $ctx)) }}
{{- end }}
- name: CHANNEL_OAUTH_UI_RETURN_URL
  value: {{ $uiReturn | quote }}
- name: CHANNEL_OAUTH_STATE_TTL
  value: {{ $svc.oauth.stateTtl | default "PT15M" | quote }}
{{- end }}
{{- range $e := $svc.extraEnv }}
{{- if hasKey $e "value" }}
- name: {{ $e.name }}
  value: {{ $e.value | quote }}
{{- else if hasKey $e "valueFrom" }}
- name: {{ $e.name }}
  valueFrom:
{{ toYaml $e.valueFrom | indent 4 }}
{{- end }}
{{- end }}
{{- end -}}

{{/*
存储卷的卷名 / PVC 名片段。
入参：storage 的键名（如 secretStore、media）
⚠️ 必须转成小写 —— K8s 资源名要求 RFC 1123（小写字母数字与 '-'），
   而 values 里的键为了可读性写的是驼峰（secretStore）。
   这个片段同时用在 Deployment 的 volumes / volumeMounts 与 PVC 的 metadata.name 上，
   两边必须一致。
*/}}
{{- define "starcon-backend.storageKey" -}}
{{- lower (. | replace "_" "-") -}}
{{- end -}}

{{/*
存储卷挂载点清单。
入参：dict "ctx" $ "name" "catalog" "svc" <服务块>
返回形如 [{"name": "<卷名>", "mountPath": "/app/data/media"}]
*/}}
{{- define "starcon-backend.storageMounts" -}}
{{- $ctx := .ctx -}}
{{- $name := .name -}}
{{- $svc := .svc -}}
{{- range $key, $st := $svc.storage }}
{{- if $st.enabled }}
- name: {{ printf "%s-%s" $name (include "starcon-backend.storageKey" $key) | trunc 63 | trimSuffix "-" }}
  mountPath: {{ $st.path }}
{{- end }}
{{- end }}
{{- end -}}
