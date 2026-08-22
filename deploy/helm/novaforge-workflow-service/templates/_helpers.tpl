{{- define "novaforge-workflow-service.labels" -}}
app.kubernetes.io/name: novaforge-workflow-service
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion }}
{{- end }}

{{- define "novaforge-workflow-service.selectorLabels" -}}
app.kubernetes.io/name: novaforge-workflow-service
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
