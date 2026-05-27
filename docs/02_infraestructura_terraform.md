# 2. Infraestructura como Código (Terraform)

## Descripción

Toda la infraestructura de CircleGuard está definida en Terraform (>= 1.5) con el proveedor de GCP. La estructura es modular y soporta tres ambientes independientes: `dev`, `stage` y `prod`.

## Estructura

```
terraform/
├── versions.tf                        # Constraints de providers (google ~>5.0, kubernetes ~>2.25)
├── modules/
│   ├── vpc-network/                   # Red VPC + subred con rangos secundarios
│   │   ├── main.tf
│   │   ├── variables.tf
│   │   └── outputs.tf
│   ├── gke-cluster/                   # Cluster GKE (sin node pool por defecto)
│   │   ├── main.tf
│   │   ├── variables.tf
│   │   └── outputs.tf
│   └── node-pool/                     # Node pool con autoscaling
│       ├── main.tf
│       ├── variables.tf
│       └── outputs.tf
└── environments/
    ├── dev/
    │   ├── main.tf
    │   ├── variables.tf
    │   ├── terraform.tfvars
    │   └── backend.tf                 # GCS remote state: bucket/dev
    ├── stage/
    │   ├── main.tf
    │   ├── variables.tf
    │   ├── terraform.tfvars
    │   └── backend.tf
    └── prod/
        ├── main.tf
        ├── variables.tf
        ├── terraform.tfvars
        └── backend.tf
```

## Módulos

### vpc-network
Crea una red VPC con una subred regional y dos rangos secundarios (pods y services) requeridos por GKE para IP aliasing.

| Variable | Dev | Stage | Prod |
|----------|-----|-------|------|
| `subnet_cidr` | 10.0.0.0/20 | 10.1.0.0/20 | 10.2.0.0/20 |
| `pods_cidr` | 10.16.0.0/16 | 10.18.0.0/16 | 10.20.0.0/16 |
| `services_cidr` | 10.17.0.0/16 | 10.19.0.0/16 | 10.21.0.0/16 |

### gke-cluster
Cluster GKE zonal con Workload Identity habilitado. Se crea sin node pool por defecto (`remove_default_node_pool = true`) para tener control sobre los nodos.

- Kubernetes version: 1.29+
- Workload Identity: `{project}.svc.id.goog`
- IP aliasing habilitado (VPC-native)

### node-pool
Node pool con autoscaling y auto-repair/auto-upgrade habilitados.

| Parámetro | Dev | Stage | Prod |
|-----------|-----|-------|------|
| `machine_type` | e2-standard-2 | e2-standard-2 | e2-standard-4 |
| `node_count` | 2 | 2 | 3 |
| `min_nodes` | 1 | 2 | 3 |
| `max_nodes` | 3 | 5 | 8 |
| `disk_size_gb` | 50 | 50 | 100 |

## Remote State

El estado se almacena en Google Cloud Storage:

```
Bucket: circleguard-terraform-state
├── dev/    → estado del ambiente dev
├── stage/  → estado del ambiente stage
└── prod/   → estado del ambiente prod
```

Cada ambiente tiene su propio prefix, así los estados quedan completamente aislados y no hay riesgo de interferencia entre ambientes.

## Cómo Usar

### Prerequisitos
```bash
# Instalar Terraform
choco install terraform  # Windows

# Autenticarse con GCP
gcloud auth application-default login
gcloud config set project circleguard-dev
```

### Inicializar y aplicar un ambiente

```bash
cd terraform/environments/dev

terraform init
terraform plan -var-file="terraform.tfvars"
terraform apply -var-file="terraform.tfvars"

# Obtener kubeconfig
gcloud container clusters get-credentials circleguard-dev \
    --region us-central1 \
    --project circleguard-dev
```

### Destruir un ambiente (solo dev/stage)
```bash
terraform destroy -var-file="terraform.tfvars"
```

> **Importante:** Nunca ejecutar `terraform destroy` en `prod` sin aprobación del equipo.

## Costos Estimados (GCP us-central1)

| Recurso | Dev/mes | Stage/mes | Prod/mes |
|---------|---------|-----------|----------|
| GKE Cluster (management fee) | $0 (free tier 1 cluster) | $72 | $72 |
| Nodos (e2-standard-2 x2) | ~$97 | ~$97 | — |
| Nodos (e2-standard-4 x3) | — | — | ~$291 |
| Persistent Disk (50GB x2) | ~$4 | ~$4 | — |
| Persistent Disk (100GB x3) | — | — | ~$15 |
| Networking (egress estimado) | ~$5 | ~$10 | ~$20 |
| GCS (bucket de estado) | <$1 | <$1 | <$1 |
| **Total estimado** | **~$106** | **~$184** | **~$399** |

Precios aproximados. Ver la calculadora de GCP para estimaciones precisas.

## Decisiones de Diseño

| Decisión | Alternativa | Razón |
|----------|-------------|-------|
| GKE sobre GCP | EKS (AWS), AKS (Azure) | GCP ofrece GKE Autopilot gratis para 1 cluster; mejor integración con Workload Identity |
| Módulos propios | Módulos del Terraform Registry | Control total sobre la configuración; sin dependencias externas |
| Remote state en GCS | Terraform Cloud | GCS es nativo de GCP; no requiere cuenta adicional |
| Node pool separado del cluster | Node pool integrado | Permite reemplazar nodos sin recrear el cluster |
