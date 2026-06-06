#!/usr/bin/env bash
# Despliega CircleGuard completo en el cluster activo (dev).
# Uso: ./scripts/deploy-all.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> Microservicios (overlay dev)"
kubectl apply -k k8s/overlays/dev

echo "==> Observabilidad: Prometheus + Grafana + Alertmanager"
kubectl apply -f k8s/monitoring/namespace.yaml
kubectl apply -f k8s/monitoring/prometheus/
kubectl apply -f k8s/monitoring/grafana/
kubectl apply -f k8s/monitoring/alertmanager/

echo "==> Logging: ELK + Filebeat"
kubectl apply -f k8s/logging/namespace.yaml
kubectl apply -f k8s/logging/elasticsearch/
kubectl apply -f k8s/logging/logstash/
kubectl apply -f k8s/logging/kibana/
kubectl apply -f k8s/logging/filebeat/

echo "==> Tracing: Jaeger"
kubectl apply -f k8s/tracing/jaeger-all-in-one.yaml

echo "==> Seguridad: TLS + Ingress (requiere cert-manager + ingress-nginx)"
kubectl apply -f k8s/security/tls.yaml || echo "   (omite si faltan CRDs de cert-manager)"

echo "==> Esperando rollout de microservicios..."
for svc in identity auth form promotion gateway notification; do
  kubectl rollout status deployment/circleguard-${svc}-service -n circleguard-dev --timeout=180s || true
done

echo "==> Listo. Estado:"
kubectl get pods -n circleguard-dev
