# The sizes a load test runs at (docs/load-test.md). `scripts/aws.sh up --loadtest` applies these
# with loadtest = true; everything not named here is as it is for customers.
#
# Two of each service, so the edge balances across stores and the store across ledgers as they would
# in any deployment worth load-testing.
#
# The database stays db.t4g.micro — two vCPUs and a gigabyte — because it is the largest an AWS
# free-plan account may create: a db.m7g.large was refused with FreeTierRestrictionError. RDS runs
# T4g instances in unlimited mode, so under sustained load it is not throttled when its CPU credits
# run out; what a run measures is two Graviton2 cores, not a credit balance.

size = {
  edge    = { cpu = 512, memory = 1024, count = 2 }
  store   = { cpu = 1024, memory = 2048, count = 2 }
  ledger  = { cpu = 1024, memory = 2048, count = 2 }
  kafka   = { cpu = 1024, memory = 4096 }
  idp     = { cpu = 512, memory = 1024 }
  loadgen = { cpu = 8192, memory = 16384 }
}

db_instance_class = "db.t4g.micro"
