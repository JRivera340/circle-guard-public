# Entorno AWS dev — segundo cloud (multi-cloud). Estado remoto en S3.
terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
  backend "s3" {
    bucket = "circleguard-terraform-state"
    key    = "aws-dev/terraform.tfstate"
    region = "us-east-1"
  }
}

provider "aws" {
  region = var.region
}

variable "region" {
  type    = string
  default = "us-east-1"
}

module "eks" {
  source        = "../../modules/eks-cluster"
  cluster_name  = "circleguard-aws-dev"
  region        = var.region
  azs           = ["us-east-1a", "us-east-1b"]
  instance_type = "t3.large"
  min_nodes     = 2
  max_nodes     = 5
  desired_nodes = 2
}

output "eks_cluster_name" {
  value = module.eks.cluster_name
}
output "eks_cluster_endpoint" {
  value = module.eks.cluster_endpoint
}
