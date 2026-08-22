{{- define "novaforge-script-engine.labels" -}}
app.kubernetes.io/name: novaforge-script-engine
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion }}
{{- end }}

{{- define "novaforge-script-engine.selectorLabels" -}}
app.kubernetes.io/name: novaforge-script-engine
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
