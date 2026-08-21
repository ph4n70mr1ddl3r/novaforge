{{- define "novaforge-audit-service.labels" -}}
app.kubernetes.io/name: novaforge-audit-service
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion }}
{{- end }}

{{- define "novaforge-audit-service.selectorLabels" -}}
app.kubernetes.io/name: novaforge-audit-service
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
