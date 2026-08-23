{{- define "novaforge-reporting-service.labels" -}}
app.kubernetes.io/name: novaforge-reporting-service
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion }}
{{- end }}

{{- define "novaforge-reporting-service.selectorLabels" -}}
app.kubernetes.io/name: novaforge-reporting-service
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
