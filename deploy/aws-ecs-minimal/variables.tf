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

variable "server_password" {
  type      = string
  sensitive = true
  default   = "change-me-please"
}
