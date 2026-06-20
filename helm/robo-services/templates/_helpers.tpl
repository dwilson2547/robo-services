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
{{- define "robo-services.commonLabels" -}}
helm.sh/chart: {{ include "robo-services.chart" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Receiver labels
*/}}
{{- define "robo-services.receiverLabels" -}}
{{ include "robo-services.commonLabels" . }}
app.kubernetes.io/name: {{ include "robo-services.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: {{ .Values.receiver.name }}
{{- end }}

{{/*
Receiver selector labels
*/}}
{{- define "robo-services.receiverSelectorLabels" -}}
app.kubernetes.io/name: {{ include "robo-services.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: {{ .Values.receiver.name }}
{{- end }}

{{/*
Speed job labels
*/}}
{{- define "robo-services.speedJobLabels" -}}
{{ include "robo-services.commonLabels" . }}
app.kubernetes.io/name: {{ include "robo-services.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: {{ .Values.speedJob.name }}
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

{{- define "robo-services.speedJobName" -}}
{{- .Values.speedJob.name -}}
{{- end }}

{{- define "robo-services.speedJobConfigMapName" -}}
{{- printf "%s-config" (include "robo-services.speedJobName" .) -}}
{{- end }}

{{- define "robo-services.speedJobServiceAccountName" -}}
{{- .Values.speedJob.serviceAccount.name -}}
{{- end }}

{{- define "robo-services.speedJobRoleName" -}}
{{- printf "%s-role" (include "robo-services.speedJobName" .) -}}
{{- end }}

{{/*
Lap job labels
*/}}
{{- define "robo-services.lapJobLabels" -}}
{{ include "robo-services.commonLabels" . }}
app.kubernetes.io/name: {{ include "robo-services.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: {{ .Values.lapJob.name }}
{{- end }}

{{- define "robo-services.lapJobName" -}}
{{- .Values.lapJob.name -}}
{{- end }}

{{- define "robo-services.lapJobConfigMapName" -}}
{{- printf "%s-config" (include "robo-services.lapJobName" .) -}}
{{- end }}

{{- define "robo-services.lapJobServiceAccountName" -}}
{{- .Values.lapJob.serviceAccount.name -}}
{{- end }}

{{- define "robo-services.lapJobRoleName" -}}
{{- printf "%s-role" (include "robo-services.lapJobName" .) -}}
{{- end }}

{{/*
Track-position job labels
*/}}
{{- define "robo-services.trackPositionJobLabels" -}}
{{ include "robo-services.commonLabels" . }}
app.kubernetes.io/name: {{ include "robo-services.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: {{ .Values.trackPositionJob.name }}
{{- end }}

{{- define "robo-services.trackPositionJobName" -}}
{{- .Values.trackPositionJob.name -}}
{{- end }}

{{- define "robo-services.trackPositionJobConfigMapName" -}}
{{- printf "%s-config" (include "robo-services.trackPositionJobName" .) -}}
{{- end }}

{{- define "robo-services.trackPositionJobServiceAccountName" -}}
{{- .Values.trackPositionJob.serviceAccount.name -}}
{{- end }}

{{- define "robo-services.trackPositionJobRoleName" -}}
{{- printf "%s-role" (include "robo-services.trackPositionJobName" .) -}}
{{- end }}

{{- define "robo-services.secretName" -}}
{{- if .Values.secret.existingSecret -}}
{{- .Values.secret.existingSecret -}}
{{- else -}}
{{- .Values.secret.name -}}
{{- end -}}
{{- end }}

{{/*
Registry labels
*/}}
{{- define "robo-services.registryLabels" -}}
{{ include "robo-services.commonLabels" . }}
app.kubernetes.io/name: {{ include "robo-services.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: {{ .Values.registry.name }}
{{- end }}

{{- define "robo-services.registrySelectorLabels" -}}
app.kubernetes.io/name: {{ include "robo-services.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: {{ .Values.registry.name }}
{{- end }}
