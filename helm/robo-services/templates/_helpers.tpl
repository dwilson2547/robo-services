{{/*
Expand the name of the chart.
*/}}
{{- define "robo-services.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "robo-services.fullname" -}}
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
Create chart name and version as used by the chart label.
*/}}
{{- define "robo-services.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "robo-services.labels" -}}
helm.sh/chart: {{ include "robo-services.chart" . }}
{{ include "robo-services.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "robo-services.selectorLabels" -}}
app.kubernetes.io/name: {{ include "robo-services.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: {{ .Values.receiver.name }}
{{- end }}

{{- define "robo-services.namespace" -}}
{{- .Values.namespace.name -}}
{{- end }}

{{- define "robo-services.receiverName" -}}
{{- .Values.receiver.name -}}
{{- end }}

{{- define "robo-services.configMapName" -}}
{{- printf "%s-config" (include "robo-services.receiverName" .) -}}
{{- end }}

{{- define "robo-services.secretName" -}}
{{- if .Values.secret.existingSecret -}}
{{- .Values.secret.existingSecret -}}
{{- else -}}
{{- .Values.secret.name -}}
{{- end -}}
{{- end }}
