provider "google" {
  project = var.project_id
  region  = var.region
}

module "vpc" {
  source        = "../../modules/vpc-network"
  project_id    = var.project_id
  region        = var.region
  network_name  = "circleguard-${var.environment}"
  subnet_cidr   = "10.1.0.0/20"
  pods_cidr     = "10.18.0.0/16"
  services_cidr = "10.19.0.0/16"
}

module "gke" {
  source       = "../../modules/gke-cluster"
  project_id   = var.project_id
  region       = var.region
  cluster_name = "circleguard-${var.environment}"
  network_id   = module.vpc.network_id
  subnet_id    = module.vpc.subnet_id
}

module "node_pool" {
  source         = "../../modules/node-pool"
  project_id     = var.project_id
  region         = var.region
  cluster_name   = module.gke.cluster_name
  node_pool_name = "default-pool"
  machine_type   = "e2-standard-2"
  node_count     = 2
  min_nodes      = 2
  max_nodes      = 5
  labels         = { environment = var.environment }
}

output "cluster_endpoint" { value = module.gke.cluster_endpoint }
output "cluster_name"     { value = module.gke.cluster_name }
