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
