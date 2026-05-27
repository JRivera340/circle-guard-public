# 2. Infraestructura como Código (Terraform)

## Descripción General

Toda la infraestructura de CircleGuard está definida como código usando **Terraform >= 1.5** con el proveedor de Google Cloud Platform (GCP). La estructura es modular y soporta tres ambientes independientes: `dev`, `stage` y `prod`.

## Estructura del Proyecto

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
    ├── dev/                           # Ambiente de desarrollo
    │   ├── main.tf
    │   ├── variables.tf
    │   ├── terraform.tfvars
    │   └── backend.tf                 # GCS remote state: bucket/dev
    ├── stage/                         # Ambiente de staging
    │   ├── main.tf
    │   ├── variables.tf
    │   ├── terraform.tfvars
    │   └── backend.tf                 # GCS remote state: bucket/stage
    └── prod/                          # Ambiente de producción
        ├── main.tf
        ├── variables.tf
        ├── terraform.tfvars
        └── backend.tf                 # GCS remote state: bucket/prod
```

## Módulos

### vpc-network
Crea una red VPC con una subred regional y dos rangos secundarios (pods y services) necesarios para GKE.

**Inputs principales:**
| Variable | Dev | Stage | Prod |
|----------|-----|-------|------|
| `subnet_cidr` | 10.0.0.0/20 | 10.1.0.0/20 | 10.2.0.0/20 |
| `pods_cidr` | 10.16.0.0/16 | 10.18.0.0/16 | 10.20.0.0/16 |
| `services_cidr` | 10.17.0.0/16 | 10.19.0.0/16 | 10.21.0.0/16 |

### gke-cluster
Cluster GKE zonal con Workload Identity habilitado. Se crea sin node pool por defecto (`remove_default_node_pool = true`) para tener control granular sobre los nodos.

**Características:**
- Kubernetes version: 1.29+
- Workload Identity: `{project}.svc.id.goog`
- IP aliasing habilitado (VPC-native)

### node-pool
Node pool con autoscaling y auto-repair/auto-upgrade habilitados.

**Configuración por ambiente:**
| Parámetro | Dev | Stage | Prod |
|-----------|-----|-------|------|
| `machine_type` | e2-standard-2 | e2-standard-2 | e2-standard-4 |
| `node_count` | 2 | 2 | 3 |
| `min_nodes` | 1 | 2 | 3 |
| `max_nodes` | 3 | 5 | 8 |
| `disk_size_gb` | 50 | 50 | 100 |

## Backend Remoto (Remote State)

El estado de Terraform se almacena en **Google Cloud Storage** para permitir trabajo colaborativo y evitar conflictos:

```
Bucket: circleguard-terraform-state
├── dev/    → estado del ambiente dev
├── stage/  → estado del ambiente stage
└── prod/   → estado del ambiente prod
```

Cada environment tiene su propio prefix, garantizando aislamiento total de estados.

## Cómo Usar

### Prerequisitos
```bash
# Instalar Terraform
brew install terraform   # macOS
choco install terraform  # Windows

# Autenticarse con GCP
gcloud auth application-default login
gcloud config set project circleguard-dev
```

### Inicializar y aplicar un ambiente

```bash
cd terraform/environments/dev

# Inicializar (descarga providers, configura backend)
terraform init

# Ver cambios planeados
terraform plan -var-file="terraform.tfvars"

# Aplicar cambios
terraform apply -var-file="terraform.tfvars"

# Obtener kubeconfig del cluster creado
gcloud container clusters get-credentials circleguard-dev \
    --region us-central1 \
    --project circleguard-dev
```

### Destruir un ambiente (solo dev/stage)
```bash
terraform destroy -var-file="terraform.tfvars"
```

> **IMPORTANTE:** Nunca ejecutar `terraform destroy` en `prod` sin aprobación explícita del equipo.

## Costos Estimados (GCP us-central1)

| Recurso | Dev/mes | Stage/mes | Prod/mes |
|---------|---------|-----------|----------|
| GKE Cluster (management fee) | $0 (free tier 1 cluster) | $72 | $72 |
| 2x e2-standard-2 nodos | ~$97 | ~$97 | — |
| 3x e2-standard-4 nodos | — | — | ~$291 |
| Persistent Disk (50GB x2) | ~$4 | ~$4 | — |
| Persistent Disk (100GB x3) | — | — | ~$15 |
| Networking (egress estimado) | ~$5 | ~$10 | ~$20 |
| GCS (state bucket) | <$1 | <$1 | <$1 |
| **Total estimado** | **~$106** | **~$184** | **~$399** |

> Precios aproximados. Usar [GCP Pricing Calculator](https://cloud.google.com/products/calculator) para estimaciones exactas según uso real.

## Decisiones de Arquitectura

| Decisión | Alternativa | Razón |
|----------|-------------|-------|
| GKE sobre GCP | EKS (AWS), AKS (Azure) | GCP ofrece GKE Autopilot gratis para 1 cluster; mejor integración con Workload Identity |
| Módulos propios | Módulos del registry de Terraform | Control total sobre la configuración; sin dependencias externas |
| Remote state en GCS | Terraform Cloud | GCS es nativo de GCP; sin cuenta adicional necesaria |
| Node pool separado del cluster | Node pool integrado | Permite reemplazar nodes sin recrear el cluster |
