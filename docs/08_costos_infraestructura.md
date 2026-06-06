# 8. Costos de Infraestructura

Estimación mensual de la infraestructura definida en Terraform (`terraform/`). Precios de referencia de Google Cloud región `us-central1`, en USD, sujetos a cambios. El objetivo es dimensionar el gasto y sustentar las decisiones de FinOps, no dar una factura exacta.

## Supuestos de cómputo (por ambiente)

Tomados de `terraform/environments/*/main.tf` y el módulo `node-pool`:

| Ambiente | Máquina | Nodos (min–max) | Disco/nodo |
|----------|---------|-----------------|------------|
| dev | e2-standard-2 (2 vCPU / 8 GB) | 1–3 (base 2) | 50 GB |
| stage | e2-standard-2 | 2–5 (base 2) | 50 GB |
| prod | e2-standard-4 (4 vCPU / 16 GB) | 3–8 (base 3) | 50 GB |

## Precios unitarios de referencia

| Recurso | Precio aprox. | Nota |
|---------|---------------|------|
| GKE: tarifa de gestión por cluster | ~$74 / mes | $0.10/hora; el primer cluster zonal puede estar exento |
| e2-standard-2 | ~$49 / mes | on-demand, 24/7 |
| e2-standard-4 | ~$98 / mes | on-demand, 24/7 |
| Disco pd-balanced 50 GB | ~$5 / mes | por nodo |
| Network Load Balancer | ~$18 / mes | + costo por GB procesado |
| Bucket GCS (estado Terraform) | < $1 / mes | almacenamiento mínimo |

## Estimación por ambiente (carga base, sin picos)

| Ambiente | Cómputo | Discos | LB | Gestión | **Total aprox.** |
|----------|---------|--------|----|---------|------------------|
| dev | 2 × $49 = $98 | $10 | $18 | $74 | **~$200 / mes** |
| stage | 2 × $49 = $98 | $10 | $18 | $74 | **~$200 / mes** |
| prod | 3 × $98 = $294 | $15 | $18 | $74 | **~$400 / mes** |
| **Total 3 ambientes** | | | | | **~$800 / mes** (base) |

En picos de autoescalado, prod puede llegar a 8 nodos (~$784 solo cómputo), elevando el total a **~$1.300 / mes**.

## Palancas de optimización (FinOps)

1. **Scale-to-zero en dev/stage fuera de horario:** apagar el node pool de dev/stage por las noches y fines de semana reduce su cómputo hasta ~65%.
2. **Spot/Preemptible VMs en dev/stage:** descuento de ~60–80% sobre el precio on-demand para cargas no críticas.
3. **Autoescalado ya configurado** (`autoscaling` en el módulo `node-pool`): evita pagar nodos ociosos en valle de tráfico.
4. **Requests/limits ajustados** en los Deployments: mayor densidad de pods por nodo → menos nodos.
5. **Retención acotada de observabilidad:** Prometheus a 7 días e índices `circleguard-*` de Elasticsearch con ILM evitan crecimiento ilimitado de disco.

Aplicando spot + scale-to-zero en los ambientes no productivos, el gasto base de los tres ambientes baja de ~$800 a un rango de **~$450–500 / mes**.
