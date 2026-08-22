{{- define "novaforge-scheduler-service.labels" -}}
app.kubernetes.io/name: novaforge-scheduler-service
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion }}
{{- end }}

{{- define "novaforge-scheduler-service.selectorLabels" -}}
app.kubernetes.io/name: novaforge-scheduler-service
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
