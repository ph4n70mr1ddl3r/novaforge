{{- define "novaforge-metadata-service.labels" -}}
app.kubernetes.io/name: novaforge-metadata-service
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion }}
{{- end }}

{{- define "novaforge-metadata-service.selectorLabels" -}}
app.kubernetes.io/name: novaforge-metadata-service
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
