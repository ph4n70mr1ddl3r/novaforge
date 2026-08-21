{{- define "novaforge-data-runtime.labels" -}}
app.kubernetes.io/name: novaforge-data-runtime
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion }}
{{- end }}

{{- define "novaforge-data-runtime.selectorLabels" -}}
app.kubernetes.io/name: novaforge-data-runtime
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
