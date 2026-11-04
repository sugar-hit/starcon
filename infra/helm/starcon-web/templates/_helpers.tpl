{{/*
=============================================================================
starcon-web —— 公共模板 helpers
=============================================================================
*/}}

{{- define "starcon-web.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "starcon-web.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "starcon-web.fullname" -}}
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

{{- define "starcon-web.labels" -}}
helm.sh/chart: {{ include "starcon-web.chart" . }}
{{ include "starcon-web.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: starcon
{{- with .Values.labels }}
{{ toYaml . }}
{{- end }}
{{- end }}

{{- define "starcon-web.selectorLabels" -}}
app.kubernetes.io/name: {{ include "starcon-web.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "starcon-web.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "starcon-web.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "starcon-web.image" -}}
{{- $repo := .Values.image.repository | default "starcon/web" -}}
{{- $tag := default .Chart.AppVersion (.Values.image.tag | toString) -}}
{{- $registry := .Values.global.imageRegistry | default "" -}}
{{- if $registry -}}
{{- printf "%s/%s:%s" (trimSuffix "/" $registry) $repo $tag -}}
{{- else -}}
{{- printf "%s:%s" $repo $tag -}}
{{- end -}}
{{- end }}

{{/*
后端服务地址。
入参：dict "ctx" $ "env" "IDENTITY_URL"
优先级：
  1. backend.urls.<ENV> 显式覆盖
  2. 由 backend.fullname + backend.services.<ENV> + backend.ports 推导
     http://<后端 fullname>-<服务名>.<ns>.svc.cluster.local:<port>
*/}}
{{- define "starcon-web.backendURL" -}}
{{- $ctx := .ctx -}}
{{- $env := .env -}}
{{- $backend := $ctx.Values.backend | default dict -}}
{{- $urls := $backend.urls | default dict -}}
{{- $override := index $urls $env | default "" -}}
{{- if $override -}}
{{- $override -}}
{{- else -}}
{{- $services := $backend.services | default dict -}}
{{- $ports := $backend.ports | default dict -}}
{{- $target := index $services $env -}}
{{- if not $target -}}
{{- fail (printf "starcon-web: backend.services 里缺少 %s 的映射" $env) -}}
{{- end -}}
{{- $port := index $ports $target -}}
{{- if not $port -}}
{{- fail (printf "starcon-web: backend.ports 里缺少 %s（服务 %s）的端口" $target $env) -}}
{{- end -}}
{{- $ns := $backend.namespace | default $ctx.Release.Namespace -}}
{{- $svcName := printf "%s-%s" $backend.fullname $target -}}
{{- printf "http://%s.%s.svc.cluster.local:%v" $svcName $ns $port -}}
{{- end -}}
{{- end }}

{{/*
TLS Secret 名
*/}}
{{- define "starcon-web.tlsSecretName" -}}
{{- default (printf "%s-tls" (include "starcon-web.fullname" .)) .Values.ingress.tls.secretName -}}
{{- end }}
