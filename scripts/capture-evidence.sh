#!/usr/bin/env bash
# Recolecta evidencia para la presentación: estado del cluster, health de servicios,
# y abre port-forwards a los dashboards. Salida en ./evidence/.
# Uso: ./scripts/capture-evidence.sh
set -euo pipefail

OUT="evidence"
mkdir -p "$OUT"

echo "==> Inventario del cluster"
kubectl get all -A > "$OUT/cluster-all.txt"
kubectl get pods -n circleguard-dev -o wide > "$OUT/pods-dev.txt"
kubectl get networkpolicy,serviceaccount,role,rolebinding -n circleguard-dev > "$OUT/rbac-netpol.txt"
kubectl top pods -n circleguard-dev > "$OUT/top-pods.txt" 2>/dev/null || true

echo "==> Health de microservicios"
for svc in identity:8083 auth:8180 form:8086 promotion:8088 gateway:8087 notification:8082; do
  name="${svc%%:*}"; port="${svc##*:}"
  kubectl exec -n circleguard-dev deploy/circleguard-${name}-service -- \
    wget -qO- "http://localhost:${port}/actuator/health" > "$OUT/health-${name}.json" 2>/dev/null \
    && echo "   ${name}: OK" || echo "   ${name}: sin respuesta"
done

cat <<'EOF'

==> Dashboards (ejecutar en terminales aparte para capturar pantallas):
   kubectl port-forward -n monitoring svc/grafana 3000:3000     # http://localhost:3000  (admin / circleguard2025)
   kubectl port-forward -n monitoring svc/prometheus 9090:9090  # http://localhost:9090
   kubectl port-forward -n logging svc/kibana 5601:5601         # http://localhost:5601
   kubectl port-forward -n tracing svc/jaeger 16686:16686       # http://localhost:16686

Evidencia textual guardada en ./evidence/
EOF
