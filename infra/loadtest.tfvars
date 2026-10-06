# The sizes a load test runs at (docs/load-test.md). `scripts/aws.sh up --loadtest` applies these
# with loadtest = true; everything not named here is as it is for customers.
#
# More than one of each service but the ledger, so the edge balances across stores as it would in
# any deployment worth load-testing. The ledger is one task with the cores two had: each task writes
# its commands' batches through one worker, and two tasks' batches meet on the same rows
# (docs/design/0016-batched-commands.md, "One writer").
#
# The database stays db.t4g.micro — two vCPUs and a gigabyte — because it is the largest an AWS
# free-plan account may create: a db.m7g.large was refused with FreeTierRestrictionError. RDS runs
# T4g instances in unlimited mode, so under sustained load it is not throttled when its CPU credits
# run out; what a run measures is two Graviton2 cores, not a credit balance.

size = {
  # A whole vCPU each: at half of one the first run's edges were at 99%, compressing every response.
  edge = { cpu = 1024, memory = 2048, count = 2 }
  # Four: at two, the second run's stores were at 93% CPU.
  store  = { cpu = 1024, memory = 2048, count = 4 }
  ledger = { cpu = 2048, memory = 4096, count = 1 }
  # Per broker — three run regardless of `count` here (runtime/services.tf), so this is 1 vCPU and
  # 4 GB times three, not once.
  kafka   = { cpu = 1024, memory = 4096 }
  idp     = { cpu = 512, memory = 1024 }
  loadgen = { cpu = 8192, memory = 16384 }
}

db_instance_class = "db.t4g.micro"

# Four stores at eight connections and the ledger at sixteen: 48, under the hundred or so a
# db.t4g.micro allows, whether the two share a server or not. With batching the ledger's commands use
# one of its sixteen at a time, and its reads, sweeper and publisher the rest.
db_pool = { store = 8, ledger = 16 }

# Every game's stock in sixteen rows, as an operator would split a game about to be busy: in the
# fourth run, with one row each, 53% of the ledger's stock updates found the row had moved. The
# contention benchmark put sixteen at a seventh of the conflicts of one (docs/design/0009-hot-sku-shards.md).
# `scripts/aws.sh up --loadtest --shards=1` measures without.
stock_shards = 16
