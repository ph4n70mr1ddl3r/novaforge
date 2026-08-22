{{- define "novaforge-notification-service.labels" -}}
app.kubernetes.io/name: novaforge-notification-service
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion }}
{{- end }}

{{- define "novaforge-notification-service.selectorLabels" -}}
app.kubernetes.io/name: novaforge-notification-service
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
