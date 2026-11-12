variable "region" {
  type    = string
  default = "us-east-1"
}

variable "name" {
  type    = string
  default = "qoc-test"
}

variable "my_ip_cidr" {
  type    = string
  default = "0.0.0.0/0"
}

variable "openai_api_key" {
  type      = string
  sensitive = true
}

variable "db_jdbc_url" {
  type      = string
  sensitive = true
}

variable "db_username" {
  type      = string
  sensitive = true
}

variable "db_password" {
  type      = string
  sensitive = true
}

variable "server_password" {
  type      = string
  sensitive = true
  default   = "change-me-please"
}

variable "auth_mode" {
  type    = string
  default = "oidc"
}

variable "auth0_issuer" {
  type    = string
  default = "https://dev-1kxf4iy7txwbmdks.us.auth0.com/"
}

variable "auth0_audience" {
  type    = string
  default = "qoc-engine"
}
