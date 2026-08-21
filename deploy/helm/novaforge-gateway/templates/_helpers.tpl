{{- define "novaforge-gateway.labels" -}}
app.kubernetes.io/name: novaforge-gateway
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion }}
{{- end }}

{{- define "novaforge-gateway.selectorLabels" -}}
app.kubernetes.io/name: novaforge-gateway
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
