{{/* Flat DNS names: the eleven service charts' env wiring references these
       verbatim (values are data — they cannot template the release name). */}}
{{- define "novaforge-infra.name" -}}novaforge-infra{{- end -}}
{{- define "novaforge-infra.labels" -}}
app.kubernetes.io/name: novaforge-infra
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "novaforge-infra.selector" -}}
app.kubernetes.io/name: novaforge-infra
app.kubernetes.io/instance: {{ .root.Release.Name }}
component: {{ .component }}
{{- end -}}
{{/* The locked-down container posture every NovaForge chart carries (18th pass):
       non-root, no privilege escalation, all caps dropped, RuntimeDefault seccomp.
       uid/fsGroup are per-component — each image's own fixed user. */}}
{{- define "novaforge-infra.securityContext" -}}
runAsNonRoot: true
runAsUser: {{ .uid }}
{{- if .fsGroup }}
fsGroup: {{ .fsGroup }}
{{- end }}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: {{ .ro | default false }}
capabilities:
  drop: ["ALL"]
seccompProfile:
  type: RuntimeDefault
{{- end -}}
