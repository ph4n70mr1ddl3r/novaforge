{{- define "novaforge-integration-service.labels" -}}
app.kubernetes.io/name: novaforge-integration-service
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion }}
{{- end }}

{{- define "novaforge-integration-service.selectorLabels" -}}
app.kubernetes.io/name: novaforge-integration-service
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
