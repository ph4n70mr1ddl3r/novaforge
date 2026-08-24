{{- define "novaforge-file-service.labels" -}}
app.kubernetes.io/name: novaforge-file-service
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion }}
{{- end }}

{{- define "novaforge-file-service.selectorLabels" -}}
app.kubernetes.io/name: novaforge-file-service
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
