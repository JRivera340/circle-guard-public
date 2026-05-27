terraform {
  backend "gcs" {
    bucket = "circleguard-terraform-state"
    prefix = "stage"
  }
}
