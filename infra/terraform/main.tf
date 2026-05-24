module "network" {
  source = "./modules/network"

  name_prefix = local.name_prefix
  vpc_cidr    = var.vpc_cidr
  tags        = local.common_tags
}

module "msk" {
  source = "./modules/msk"

  name_prefix       = local.name_prefix
  subnet_ids        = module.network.private_subnet_ids
  security_group_id = module.network.msk_security_group_id
  tags              = local.common_tags
}

module "ecs" {
  source = "./modules/ecs"

  name_prefix             = local.name_prefix
  app_name                = var.app_name
  vpc_id                  = module.network.vpc_id
  private_subnet_ids      = module.network.private_subnet_ids
  app_security_group_id   = module.network.app_security_group_id
  alb_security_group_id   = module.network.alb_security_group_id
  msk_cluster_arn         = module.msk.cluster_arn
  kafka_bootstrap_brokers = module.msk.bootstrap_brokers_sasl_iam
  aws_region              = var.region
  tags                    = local.common_tags
}
