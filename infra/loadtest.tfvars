# The sizes a load test runs at (docs/load-test.md). `scripts/aws.sh up --loadtest` applies these
# with loadtest = true; everything not named here is as it is for customers.
#
# Two of each service, so the edge balances across stores and the store across ledgers as they would
# in any deployment worth load-testing. The database is the one thing sized up from the smallest:
# a burstable instance under sustained load measures its CPU credits, not the platform.

size = {
  edge    = { cpu = 512, memory = 1024, count = 2 }
  store   = { cpu = 1024, memory = 2048, count = 2 }
  ledger  = { cpu = 1024, memory = 2048, count = 2 }
  kafka   = { cpu = 1024, memory = 4096 }
  idp     = { cpu = 512, memory = 1024 }
  loadgen = { cpu = 8192, memory = 16384 }
}

db_instance_class = "db.m7g.large"
