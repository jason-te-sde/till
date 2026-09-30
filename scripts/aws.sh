#!/usr/bin/env bash
# Runs till on AWS for as long as it is needed, and takes it down again.
#
#   scripts/aws.sh bootstrap   once per account: the state bucket and the image registries
#   scripts/aws.sh up          build and push the images, start everything, then run `smoke`
#   scripts/aws.sh smoke       check that the running deployment behaves
#   scripts/aws.sh up --loadtest   start it set up for a load test instead (docs/load-test.md);
#                  --no-catalogue-cache to measure the store without its catalogue cache,
#                  --shards=N to keep each game's stock in N rows instead of the load test's 16
#   scripts/aws.sh loadtest    one load test run: the protocol's, or --shoppers= --ramp= --hold=
#   scripts/aws.sh accounts    the demonstration accounts' passwords
#   scripts/aws.sh status      what is billed by the hour and still there, and since when
#   scripts/aws.sh down        stop the hourly bill; the images, accounts, secrets and logs stay
#   scripts/aws.sh destroy     everything this created, the bootstrap included
#   scripts/aws.sh plan        what `up` would change, changing nothing
#   scripts/aws.sh terraform … Terraform in infra/, with the same credentials, for anything else
#
# bootstrap, up and down show Terraform's plan and ask before applying it; destroy asks. --yes
# does not ask.
#
# Needs the AWS CLI, Terraform, Docker, jq and python3. Credentials are whatever AWS_PROFILE names —
# till-deploy unless it says otherwise — and nothing here prints them. Running costs about $0.16 an
# hour; infra/README.md has the arithmetic.
set -euo pipefail

cd "$(dirname "$0")/.."

export AWS_PROFILE="${AWS_PROFILE:-till-deploy}"
export AWS_REGION="${AWS_REGION:-us-west-2}"
export AWS_PAGER=""

HOURLY=0.16
LOADTEST_HOURLY=1.15
yes=false
loadtest=false
catalogue_cache=true
shards=""
shoppers=""
ramp=""
hold=""

# Terraform's AWS SDK cannot use the sessions `aws login` keeps, and credentials handed to it once
# would expire partway through a fifteen-minute apply. So Terraform gets a profile of its own whose
# credentials come from the AWS CLI through credential_process, which the SDK runs again whenever the
# last ones expire. The file holds that command, never a credential, and goes when this script does.
#
# The command tries more than once. A refresh can fail for a while — the first deployment lost ten
# minutes and two resource waits to one — and a wait that fails leaves its resource tainted, to be
# destroyed and created again by the next apply.
TERRAFORM_AWS_CONFIG=$(mktemp)
trap 'rm -f "$TERRAFORM_AWS_CONFIG"' EXIT
cat > "$TERRAFORM_AWS_CONFIG" << EOF
[profile till-terraform]
region = $AWS_REGION
credential_process = for wait in 0 2 4 8 16 32; do sleep \$wait; env AWS_CONFIG_FILE="${AWS_CONFIG_FILE:-$HOME/.aws/config}" aws configure export-credentials --profile "$AWS_PROFILE" --format process && exit 0; done; exit 1
EOF
terraform() { AWS_CONFIG_FILE="$TERRAFORM_AWS_CONFIG" AWS_PROFILE=till-terraform command terraform "$@"; }

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
fail() {
  printf '\033[31m%s\033[0m\n' "$*" >&2
  exit 1
}
tf() { terraform -chdir=infra "$@"; }

confirm() {
  [[ $yes == true ]] && return 0
  local answer
  read -r -p "$1 [y/N] " answer
  [[ $answer == y || $answer == yes ]]
}

# Plans, shows the plan, asks, and applies exactly the plan it showed.
apply() {
  local dir="$1" question="$2"
  shift 2
  terraform -chdir="$dir" plan -input=false -out=.terraform/aws.tfplan "$@"
  confirm "$question" || fail "Nothing was changed."
  terraform -chdir="$dir" apply -input=false .terraform/aws.tfplan
}

init() {
  [[ -f infra/backend.hcl ]] || fail "infra/backend.hcl is missing: run scripts/aws.sh bootstrap first."
  tf init -input=false -backend-config=backend.hcl > /dev/null
}

# A Terraform output, or nothing when it is null — which the running half's are while stopped.
output() { tf output -json "$1" | jq -r '. // empty'; }

commit() { git rev-parse --short=12 HEAD; }

# The broker the compose stack runs, so that both run the same one.
kafka_version() { sed -n 's|^ *image: apache/kafka:||p' docker-compose.yml | head -1; }

elapsed() {
  local seconds=$(($(date +%s) - $1))
  printf '%dm%02ds' $((seconds / 60)) $((seconds % 60))
}

# --- images --------------------------------------------------------------------------------------

pushed() { aws ecr describe-images --repository-name "$1" --image-ids "imageTag=$2" > /dev/null 2>&1; }

push_images() {
  local tag="$1" registry kafka target
  registry=$(terraform -chdir=infra/bootstrap output -raw registry)
  kafka=$(kafka_version)

  say "Pushing the images for $tag"
  aws ecr get-login-password | docker login --username AWS --password-stdin "$registry" > /dev/null

  local targets="runtime edge"
  [[ $loadtest == true ]] && targets="runtime edge loadtest"

  # ARM64 whatever builds them: that is what the task definitions ask Fargate for.
  for target in $targets; do
    if pushed "till/$target" "$tag"; then
      echo "till/$target:$tag is already there"
    else
      docker build --quiet --platform linux/arm64 --target "$target" --tag "$registry/till/$target:$tag" . > /dev/null
      docker push --quiet "$registry/till/$target:$tag" > /dev/null
      echo "till/$target:$tag pushed"
    fi
  done

  # Rebuilt from a one-line Dockerfile rather than re-tagged, so that what is pushed is the one
  # platform Fargate runs rather than an index whose other platforms were never pulled.
  if pushed till/kafka "$kafka"; then
    echo "till/kafka:$kafka is already there"
  else
    docker build --quiet --platform linux/arm64 --tag "$registry/till/kafka:$kafka" - <<< "FROM apache/kafka:$kafka" > /dev/null
    docker push --quiet "$registry/till/kafka:$kafka" > /dev/null
    echo "till/kafka:$kafka pushed"
  fi
}

# --- commands ------------------------------------------------------------------------------------

cmd_bootstrap() {
  terraform -chdir=infra/bootstrap init -input=false > /dev/null
  apply infra/bootstrap "Create the state bucket and the registries?" -var "region=$AWS_REGION"
}

# The variables every plan and apply of the running half passes.
running_vars() {
  printf '%s\n' -var running=true -var "image_tag=$1" -var "kafka_version=$(kafka_version)"
  if [[ $loadtest == true ]]; then
    printf '%s\n' -var loadtest=true -var-file=loadtest.tfvars
  fi
  if [[ $catalogue_cache == false ]]; then
    printf '%s\n' -var catalogue_cache=false
  fi
  if [[ -n $shards ]]; then
    printf '%s\n' -var "stock_shards=$shards"
  fi
}

cmd_plan() {
  init
  local vars=()
  while read -r line; do vars+=("$line"); done < <(running_vars "$(commit)")
  tf plan -input=false "${vars[@]}"
}

cmd_up() {
  [[ -z $(git status --porcelain) ]] ||
    fail "The working tree has changes. The images are named after the commit they are built from: commit or stash first."
  local tag started hourly=$HOURLY line
  tag=$(commit)
  started=$(date +%s)
  [[ $loadtest == true ]] && hourly=$LOADTEST_HOURLY
  init
  push_images "$tag"

  local vars=()
  while read -r line; do vars+=("$line"); done < <(running_vars "$tag")
  if [[ $loadtest == true ]]; then
    say "Starting till at $tag for a load test: fifteen minutes or so, most of it the database"
  else
    say "Starting till at $tag: forty minutes or so, nearly all of it CloudFront"
  fi
  # A new deadline for the safety net with every up (infra/runtime/auto-stop.tf); the plan after the
  # apply leaves it alone, so it is not a change that never settles.
  apply infra "Start it? From here it costs about \$$hourly an hour, until scripts/aws.sh down." "${vars[@]}" \
    -replace='module.runtime[0].time_offset.auto_stop'

  # A configuration AWS does not read back the way it was written is one every apply changes
  # again, and a replacement can take running tasks with it (infra/runtime/discovery.tf). So an
  # apply is not done until a plan after it has nothing left to do.
  local converged=0
  tf plan -input=false -detailed-exitcode "${vars[@]}" > /dev/null || converged=$?

  if [[ $loadtest == true ]]; then
    say "Up in $(elapsed "$started"), set up for a load test at $(output load_balancer)"
  else
    say "Up in $(elapsed "$started"): $(output url)"
    cmd_smoke
  fi
  case $converged in
    0) ;;
    2) fail "Terraform still has changes to make after applying, which every apply would make again: scripts/aws.sh plan shows them." ;;
    *) fail "Terraform could not plan after applying." ;;
  esac
  echo
  echo "The safety net scales every service to zero at $(output auto_stop_at) unless up runs again before then."
  if [[ $loadtest == true ]]; then
    echo "About \$$hourly an hour from now on. scripts/aws.sh loadtest --shoppers=100 --ramp=20s --hold=1m to try it;"
    echo "scripts/aws.sh loadtest for the protocol's run; scripts/aws.sh down to stop."
  else
    echo "About \$$hourly an hour from now on. scripts/aws.sh accounts for the sign-in; scripts/aws.sh down to stop."
  fi
}

cmd_down() {
  init
  local started
  started=$(date +%s)
  say "Stopping: the load balancer, CloudFront, the database, the cache and the containers go"
  apply infra "Stop it?" -var running=false -var "kafka_version=$(kafka_version)"
  say "Stopped in $(elapsed "$started")"
  cmd_status
}

cmd_destroy() {
  init
  say "Destroying everything: the running half if it is up, the images, the accounts, the secrets, the logs, the state bucket"
  confirm "Destroy all of it? The demonstration accounts' passwords go with it." || fail "Nothing was changed."
  tf destroy -input=false -auto-approve -var "kafka_version=$(kafka_version)"
  terraform -chdir=infra/bootstrap destroy -input=false -auto-approve -var "region=$AWS_REGION"
}

cmd_accounts() {
  init
  local url
  url=$(output url)
  echo "Sign in at ${url:-(not running: scripts/aws.sh up)} as"
  tf output -json demo_passwords | jq -r 'to_entries[] | "  \(.key)  \(.value)"'
  echo "operator is in the admins group, and gets the operator console."
}

# Asks AWS directly rather than Terraform's state: the point is to find what is billing, including
# anything a failed apply or a lost state file left behind. "Not found" is an answer; any other
# error is not, and is said rather than taken to mean there is nothing there.
ask() {
  local answer error
  error=$(mktemp)
  if answer=$(aws "$@" 2> "$error"); then
    rm -f "$error"
    printf '%s' "$answer"
    return 0
  fi
  if grep -qE 'NotFound|not found' "$error"; then
    rm -f "$error"
    return 0
  fi
  printf '  could not ask: %s\n' "$(head -1 "$error")" >&2
  rm -f "$error"
  return 1
}

cmd_status() {
  local found=false unsure=false since
  aws sts get-caller-identity > /dev/null || fail "Cannot reach AWS as $AWS_PROFILE."

  probe() {
    local label="$1" answer
    shift
    answer=$(ask "$@") || {
      unsure=true
      return 0
    }
    [[ -z $answer || $answer == None ]] && return 0
    printf '  %-24s %s\n' "$label" "$answer"
    found=true
  }

  say "Billed by the hour, and still there"
  probe "CloudFront distribution" cloudfront list-distributions --output text \
    --query "DistributionList.Items[?Comment=='till'].[DomainName, Status]"
  probe "CloudFront VPC origin" cloudfront list-vpc-origins --output text \
    --query "VpcOriginList.Items[?Name=='till-edge'].Status"
  probe "load balancer" elbv2 describe-load-balancers --names till --output text \
    --query 'LoadBalancers[0].State.Code'
  probe "database" rds describe-db-instances --db-instance-identifier till --output text \
    --query 'DBInstances[0].[DBInstanceClass, DBInstanceStatus]'
  probe "cache" elasticache describe-replication-groups --replication-group-id till-sessions --output text \
    --query 'ReplicationGroups[0].[CacheNodeType, Status]'
  probe "service discovery" servicediscovery list-namespaces --output text \
    --query "Namespaces[?Name=='till.internal'].Name"
  probe "ECS services, running" ecs describe-services --cluster till --services kafka ledger store edge idp --output text \
    --query "services[?status=='ACTIVE'].join(':', [serviceName, to_string(runningCount)])"
  probe "safety net" scheduler get-schedule --name till-stop-store --output text \
    --query "join('', ['scales to zero at ', ScheduleExpression, ' UTC'])"
  probe "load generator tasks" ecs list-tasks --cluster till --family till-loadgen --output text \
    --query "length(taskArns) > \`0\` && to_string(length(taskArns)) || ''"

  if [[ $unsure == true ]]; then
    echo "  and some of it could not be asked about: see above"
    return 1
  fi
  if [[ $found == false ]]; then
    echo "  nothing: what is left is ECR's storage and the logs', cents a month"
    return 0
  fi
  since=$(ask rds describe-db-instances --db-instance-identifier till --output text \
    --query 'DBInstances[0].InstanceCreateTime') || true
  if [[ -n $since && $since != None ]]; then
    python3 - "$since" "$HOURLY" << 'PY'
import datetime, sys
started = datetime.datetime.fromisoformat(sys.argv[1].replace("Z", "+00:00"))
hours = (datetime.datetime.now(datetime.timezone.utc) - started).total_seconds() / 3600
print(f"  up for {hours:.1f}h: about ${hours * float(sys.argv[2]):.2f} so far")
PY
  fi
}

# --- a load test run -----------------------------------------------------------------------------

# Runs one task of a family to the end and writes what it logged to a file. Sets the globals
# `task_exit` and `task_reason`, because a subshell could not.
task_exit=""
task_reason=""
run_once() {
  local family="$1" group="$2" subnets="$3" overrides="$4" log="$5" container="$6" task status started
  task=$(aws ecs run-task --cluster till --task-definition "$family" --launch-type FARGATE \
    --network-configuration "awsvpcConfiguration={subnets=[$subnets],securityGroups=[$group],assignPublicIp=ENABLED}" \
    --overrides "$overrides" --query 'tasks[0].taskArn' --output text)
  [[ -n $task && $task != None ]] || fail "$family did not start."
  started=$(date +%s)
  while :; do
    status=$(aws ecs describe-tasks --cluster till --tasks "$task" --query 'tasks[0].lastStatus' --output text)
    [[ $status == STOPPED ]] && break
    printf '\r  %-12s %-14s %s ' "$container" "$status" "$(elapsed "$started")" >&2
    sleep 10
  done
  printf '\r%60s\r' '' >&2
  task_exit=$(aws ecs describe-tasks --cluster till --tasks "$task" --query 'tasks[0].containers[0].exitCode' --output text)
  task_reason=$(aws ecs describe-tasks --cluster till --tasks "$task" --query 'tasks[0].stoppedReason' --output text)
  # awslogs names a stream <prefix>/<container>/<task>, and the prefix is the log group's name.
  aws logs get-log-events --log-group-name "$(output loadtest_log_group)" --log-stream-name "loadtest/$container/${task##*/}" \
    --no-start-from-head --limit 1000 --query 'events[].message' --output json 2> /dev/null | jq -r '.[]' > "$log" ||
    fail "$family stopped ($task_reason, exit $task_exit) and wrote no log."
}

# One SQL statement against the database, in the load test's psql task; prints what it returned.
dbstat() {
  local loadgen="$1" sql="$2" log overrides
  log=$(mktemp)
  overrides=$(jq -nc --arg sql "$sql" '{containerOverrides: [{name: "dbstat", environment: [{name: "SQL", value: $sql}]}]}')
  run_once "$(jq -r .dbstat_task_definition <<< "$loadgen")" "$(jq -r .dbstat_security_group <<< "$loadgen")" \
    "$(jq -r '.subnets | join(",")' <<< "$loadgen")" "$overrides" "$log" dbstat
  [[ $task_exit == 0 ]] || fail "psql failed ($task_reason, exit $task_exit): $(tail -3 "$log")"
  cat "$log"
  rm -f "$log"
}

# What the database spent its time on, most first: pg_stat_statements since the reset before the run.
# jsonb rather than json, whose array comes back one element to a line.
TOP_STATEMENTS="select coalesce(jsonb_agg(t order by t.total_ms desc), '[]') from (select round(s.total_exec_time)::bigint as total_ms,
  s.calls, round(s.mean_exec_time::numeric, 2) as mean_ms, s.rows, d.datname as db,
  left(regexp_replace(s.query, '\s+', ' ', 'g'), 240) as query
  from pg_stat_statements s join pg_database d on d.oid = s.dbid order by s.total_exec_time desc limit 15) t"

# Transactions committed and rolled back in each database, since it started: the run's are the
# difference between this after it and this before it.
DATABASE_TRANSACTIONS="select coalesce(jsonb_object_agg(datname, jsonb_build_object('commits', xact_commit,
  'rollbacks', xact_rollback)), '{}') from pg_stat_database where datname in ('till', 'store')"

# The ledger's reservations by state: how many holds a run took, and how many nobody finished.
RESERVATIONS="select coalesce(jsonb_object_agg(state, n), '{}') from (select state, count(*) as n
  from till_reservation group by state) s"

# The ledger's stock rows: a SKU split sixteen ways is sixteen of them (docs/design/0009-hot-sku-shards.md),
# so this is what shows a split was in effect rather than only configured.
STOCK_ROWS="select jsonb_build_object('skus', count(distinct sku), 'rows', count(*)) from till_stock"

# One run of the load generator (docs/load-test.md). Without options it is the protocol's run; with
# them, a shorter one to try things with. Its summary is printed, and saved with what CloudWatch says
# about the same window to till-loadtest/results/, which is where a result has to be to count.
cmd_loadtest() {
  init
  local loadgen family subnets group overrides code reason log result file deployed before after cloudwatch reads cached split
  loadgen=$(tf output -json loadgen)
  [[ $loadgen != null ]] || fail "It is not set up for a load test: scripts/aws.sh up --loadtest"
  family=$(jq -r .task_definition <<< "$loadgen")
  subnets=$(jq -r '.subnets | join(",")' <<< "$loadgen")
  group=$(jq -r .security_group <<< "$loadgen")
  # The commit the running images were built from, which is not necessarily the one checked out.
  deployed=$(aws ecs describe-task-definition --task-definition "$family" \
    --query 'taskDefinition.containerDefinitions[0].image' --output text)
  deployed=${deployed##*:}
  # Only what was asked for is overridden: the task definition holds the protocol's run.
  overrides=$(jq -nc --arg shoppers "$shoppers" --arg ramp "$ramp" --arg hold "$hold" '{containerOverrides: [{
    name: "loadgen",
    environment: ([{name: "SHOPPERS", value: $shoppers}, {name: "RAMP", value: $ramp}, {name: "HOLD", value: $hold}]
      | map(select(.value != "")))}]}')

  say "Load test: ${shoppers:-8000} shoppers, ${ramp:-5m} to ramp up, ${hold:-10m} held"
  # The database's statement statistics from zero, so that what it reports afterwards is this run, and
  # the counts that cannot be reset, so that the run's share of them can be worked out.
  before=$(dbstat "$loadgen" "create extension if not exists pg_stat_statements; select pg_stat_statements_reset();
    select jsonb_build_object('transactions', ($DATABASE_TRANSACTIONS), 'reservations', ($RESERVATIONS))" | tail -1)
  jq -e . <<< "$before" > /dev/null 2>&1 || before=null

  log=$(mktemp)
  run_once "$family" "$group" "$subnets" "$overrides" "$log" loadgen
  code=$task_exit
  reason=$task_reason
  grep -E 'msg="unexpected' "$log" | sed -E 's/.*msg="//; s/" source=.*//' | sort | uniq -c | sort -rn | head -5 || true
  sed -En '/^shoppers +[0-9]+ at once/,/^targets  /p' "$log"
  result=$(grep '^RESULT ' "$log" | tail -1 | cut -c8-)
  rm -f "$log"
  [[ -n $result ]] || fail "The load generator stopped ($reason, exit $code) without a result."
  # What the store said about its catalogue reads over the same window, and whether it was caching.
  reads=$(catalogue_reads "$result") || reads=null
  jq -e . <<< "$reads" > /dev/null 2>&1 || reads=null
  cached=$(aws ecs describe-task-definition --task-definition till-store --output text \
    --query "taskDefinition.containerDefinitions[?name=='store'].environment[] | [?name=='STORE_CATALOGUE_CACHE'].value | [0]")
  split=$(aws ecs describe-task-definition --task-definition till-store --output text \
    --query "taskDefinition.containerDefinitions[?name=='store'].environment[] | [?name=='STORE_DEMO_SHARDS'].value | [0]")

  # The run happened whether or not psql can say what the database did in it.
  after=$(dbstat "$loadgen" "select jsonb_build_object('statements', ($TOP_STATEMENTS),
    'transactions', ($DATABASE_TRANSACTIONS), 'reservations', ($RESERVATIONS), 'stock', ($STOCK_ROWS))" | tail -1) || after=null
  jq -e . <<< "$after" > /dev/null 2>&1 || after=null

  # Nothing is written until everything is known, and a CloudWatch that cannot be read costs the
  # CloudWatch figures only: the run's own result is saved regardless.
  cloudwatch=$(server_side "$result") || cloudwatch=null
  jq -e . <<< "$cloudwatch" > /dev/null 2>&1 || cloudwatch=null
  file="till-loadtest/results/$(date -u +%Y%m%dT%H%M%SZ).json"
  mkdir -p till-loadtest/results
  jq -n --argjson result "$result" --argjson before "$before" --argjson after "$after" --argjson cloudwatch "$cloudwatch" \
    --argjson reads "$reads" --arg cached "$cached" --arg split "$split" --arg commit "$deployed" --arg code "$code" \
    '{commit: $commit, exit_code: ($code | tonumber? // $code), store_catalogue_cache: ($cached == "true"),
      stock_shards: ($split | tonumber? // null),
      k6: $result, catalogue_reads: $reads, cloudwatch: $cloudwatch,
      database_top_statements: ($after.statements // null),
      database_transactions: (if $before == null or $after == null then null else
        $after.transactions | with_entries(.key as $db | .value |= with_entries(.key as $count
          | .value -= ($before.transactions[$db][$count] // 0))) end),
      reservations: (if $after == null then null else {before: ($before.reservations // null), after: $after.reservations} end),
      stock_rows: ($after.stock // null)}' > "$file"
  say "Server side, over the same window"
  jq -r '(.cloudwatch // {}) | to_entries[] | "  \(.key | gsub("_"; " "))\(" " * (26 - (.key | length)))\(.value)"' "$file"
  say "The store's catalogue reads, over the same window (its cache $([[ $cached == true ]] && echo on || echo off))"
  jq -r '(.catalogue_reads // {}) | "  from the cache     \(.cache.reads // 0) reads, \(.cache.mean_ms // "-") ms each",
    "  from the database  \(.database.reads // 0) reads, \(.database.mean_ms // "-") ms each",
    "  on average         \(.mean_ms // "-") ms"' "$file"
  say "What the database spent its time on, over the whole run"
  jq -r '(.database_top_statements // [])[:8][] | "  \(.total_ms) ms  \(.calls) calls  \(.mean_ms) ms each  [\(.db)]  \(.query[:110])"' "$file"
  jq -r '(.database_transactions // {}) | to_entries[] | "  \(.key): \(.value.commits) transactions committed, \(.value.rollbacks) rolled back"' "$file"
  jq -r '.reservations // empty | "  reservations when it ended: \(.after | to_entries | map("\(.value) \(.key | ascii_downcase)") | join(", "))"' "$file"
  jq -r '.stock_rows // empty | "  stock: \(.skus) SKUs in \(.rows) rows"' "$file"
  echo
  echo "Saved to $file."
  # k6 exits 99 when a target was missed; the run still happened, and its numbers are the result.
  [[ $code == 0 ]] || fail "The run missed a target (k6 exit $code): the numbers above are its result."
}

# The store's catalogue reads over the steady window, from its own once-a-minute account of them
# (CatalogueCache): how many came from the cache and the database, and how long each took on
# average. A line covers the minute before it, so the lines counted are those from a minute into
# the window to its end.
catalogue_reads() {
  local result="$1" from to
  from=$(python3 -c 'import sys, datetime as d
print(int(d.datetime.fromisoformat(sys.argv[1].replace("Z", "+00:00")).timestamp() * 1000) + 60000)' \
    "$(jq -r .window.from <<< "$result")")
  to=$(python3 -c 'import sys, datetime as d
print(int(d.datetime.fromisoformat(sys.argv[1].replace("Z", "+00:00")).timestamp() * 1000) + 5000)' \
    "$(jq -r .window.to <<< "$result")")
  aws logs filter-log-events --log-group-name /till/store --start-time "$from" --end-time "$to" \
    --filter-pattern '"catalogue-reads"' --query 'events[].message' --output json |
    python3 -c '
import json, re, sys
totals = {"cache": [0, 0.0], "database": [0, 0.0]}
lines = 0
for message in json.load(sys.stdin):
    found = re.search(r"catalogue-reads (\{.*\})", message)
    if not found:
        continue
    lines += 1
    minute = json.loads(found.group(1))
    for source in totals:
        totals[source][0] += minute[source]["count"]
        totals[source][1] += minute[source]["total_ms"]
reads = sum(count for count, _ in totals.values())
spent = sum(ms for _, ms in totals.values())
answer = {source: {"reads": count, "mean_ms": round(ms / count, 2) if count else None}
          for source, (count, ms) in totals.items()}
answer["mean_ms"] = round(spent / reads, 2) if reads else None
answer["store_minutes"] = lines
print(json.dumps(answer))'
}

# What the load balancer, the database and the containers said about the steady window, from
# CloudWatch, as a check on the load generator's own figures.
server_side() {
  local result="$1" balancer
  balancer=$(aws elbv2 describe-load-balancers --names till --query 'LoadBalancers[0].LoadBalancerArn' --output text)
  # The last minute of the window reaches CloudWatch a minute or two after it ends.
  sleep 90
  python3 - "$result" "${balancer#*:loadbalancer/}" << 'PY' > "${TMPDIR:-/tmp}/till-metrics.json"
import datetime, json, sys
result, balancer = json.loads(sys.argv[1]), sys.argv[2]
# The steady window as the load generator measured it, whole minutes of it: CloudWatch's are minutes.
parse = lambda text: datetime.datetime.fromisoformat(text.replace("Z", "+00:00"))
start, end = parse(result["window"]["from"]), parse(result["window"]["to"])
whole_start = start.replace(second=0, microsecond=0) + datetime.timedelta(minutes=1)
whole_end = end.replace(second=0, microsecond=0)
if whole_end > whole_start:
    start, end = whole_start, whole_end
else:
    # A window shorter than two minutes has no whole minute inside it: take the minutes it touches.
    start, end = start.replace(second=0, microsecond=0), whole_end + datetime.timedelta(minutes=1)
length = int((end - start).total_seconds())
def stat(id, namespace, metric, dims, stat, period=60):
    return {"Id": id, "ReturnData": True, "MetricStat": {"Metric": {"Namespace": namespace, "MetricName": metric,
            "Dimensions": [{"Name": k, "Value": v} for k, v in dims.items()]}, "Period": period, "Stat": stat}}
alb = {"LoadBalancer": balancer}
queries = [
    stat("alb_requests", "AWS/ApplicationELB", "RequestCount", alb, "Sum"),
    stat("alb_target_p99", "AWS/ApplicationELB", "TargetResponseTime", alb, "p99", length),
    stat("alb_target_5xx", "AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", alb, "Sum"),
    stat("alb_own_5xx", "AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count", alb, "Sum"),
    stat("db_cpu_max", "AWS/RDS", "CPUUtilization", {"DBInstanceIdentifier": "till"}, "Maximum"),
] + [stat(f"{service}_cpu_max", "AWS/ECS", "CPUUtilization", {"ClusterName": "till", "ServiceName": service}, "Maximum")
     for service in ("edge", "store", "ledger", "kafka")]
print(json.dumps({"MetricDataQueries": queries, "StartTime": start.isoformat(), "EndTime": end.isoformat()}))
PY
  aws cloudwatch get-metric-data --cli-input-json "file://${TMPDIR:-/tmp}/till-metrics.json" --output json |
    jq --argjson seconds "$(jq '[.MetricDataQueries[] | select(.Id == "alb_target_p99") | .MetricStat.Period][0]' "${TMPDIR:-/tmp}/till-metrics.json")" '
      [.MetricDataResults[] | {key: .Id, value: .Values}] | from_entries
      | {
          window_seconds: $seconds,
          alb_requests_per_second: (((.alb_requests // []) | add // 0) / $seconds | . * 10 | round / 10),
          alb_target_p99_ms: (((.alb_target_p99 // [])[0] // null) | if . == null then null else . * 1000 | round end),
          alb_target_5xx: ((.alb_target_5xx // []) | add // 0),
          alb_own_5xx: ((.alb_own_5xx // []) | add // 0),
          db_cpu_max_percent: ((.db_cpu_max // []) | max // null | if . == null then null else . * 10 | round / 10 end),
          edge_cpu_max_percent: ((.edge_cpu_max // []) | max // null | if . == null then null else . * 10 | round / 10 end),
          store_cpu_max_percent: ((.store_cpu_max // []) | max // null | if . == null then null else . * 10 | round / 10 end),
          ledger_cpu_max_percent: ((.ledger_cpu_max // []) | max // null | if . == null then null else . * 10 | round / 10 end),
          kafka_cpu_max_percent: ((.kafka_cpu_max // []) | max // null | if . == null then null else . * 10 | round / 10 end)
        }'
  rm -f "${TMPDIR:-/tmp}/till-metrics.json"
}

cmd_smoke() {
  init
  local url failures=0
  url=$(output url)
  [[ -n $url ]] || fail "till is not running: scripts/aws.sh up"

  say "Checking $url"
  check() {
    if "${@:2}"; then
      printf '  \033[32mok\033[0m    %s\n' "$1"
    else
      printf '  \033[31mfail\033[0m  %s\n' "$1"
      failures=$((failures + 1))
    fi
  }

  check "plain HTTP is sent to HTTPS" redirects_to_https "$url"
  check "the storefront, with the edge's CSP and CloudFront's HSTS" serves_the_storefront "$url"
  check "the store stocked itself through the ledger, and the events reached it" is_stocked "$url"
  check "the catalogue is cached at the edge" is_cached "$url"
  check "nothing of the ledger is reachable, even with a token" hides_the_ledger "$url"
  check "signing in goes to Cognito, which accepts the callback" signs_in_at_cognito "$url"
  check "the edge sees the viewer's address, not one the viewer claims" knows_the_viewer "$url"

  ((failures == 0)) || fail "$failures of the checks failed."
}

# --- the checks ----------------------------------------------------------------------------------

redirects_to_https() {
  [[ $(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' "http://${1#https://}/") == "301 $1/" ]]
}

serves_the_storefront() {
  local headers
  headers=$(curl -s -D - -o /dev/null "$1/games/sunless-orbit")
  grep -q '^HTTP/[0-9.]* 200' <<< "$headers" &&
    grep -qi "^content-security-policy: default-src 'self'; script-src 'self'" <<< "$headers" &&
    grep -qi '^strict-transport-security: max-age=31536000' <<< "$headers"
}

# Stock is seeded through the ledger as the store starts, and availability is what the store's
# projection has read from Kafka since — so a game in stock means all of it is wired.
is_stocked() {
  local _
  for _ in $(seq 1 20); do
    curl -sf "$1/api/games?size=48" | jq -e '[.items[] | select(.available > 0)] | length > 0' > /dev/null && return 0
    sleep 3
  done
  return 1
}

is_cached() {
  local status _
  for _ in 1 2 3; do
    status=$(curl -s -D - -o /dev/null "$1/api/home" | tr -d '\r' | grep -i '^x-cache-status:' | awk '{print $2}')
  done
  [[ $status == HIT ]]
}

hides_the_ledger() {
  curl -s -H 'Authorization: Bearer not-a-token' "$1/v1/stock" | grep -q '<title>till games</title>'
}

# The redirect the store builds must name this deployment over HTTPS, and Cognito must accept it:
# an unregistered callback lands on Cognito's error page rather than its sign-in form.
signs_in_at_cognito() {
  local location callback landed
  location=$(curl -s -o /dev/null -w '%{redirect_url}' "$1/oauth2/authorization/idp")
  [[ $location == https://*.amazoncognito.com/oauth2/authorize\?* ]] || return 1
  callback=$(python3 -c 'import sys, urllib.parse as u
print(u.parse_qs(u.urlsplit(sys.argv[1]).query)["redirect_uri"][0])' "$location")
  [[ $callback == "$1/login/oauth2/code/idp" ]] || return 1
  landed=$(curl -s -L -o /dev/null -w '%{http_code} %{url_effective}' "$location")
  [[ $landed == "200 https://"*".amazoncognito.com/login?"* ]]
}

# The edge believes CloudFront-Viewer-Address from anything in the VPC, which only CloudFront can
# reach. That is safe only if CloudFront replaces one the viewer sends — AWS's documentation does not
# say it does, so this sends one and reads what the edge logged.
knows_the_viewer() {
  local marker me client _
  marker="smoke-$(date +%s)-$RANDOM"
  me=$(curl -s https://checkip.amazonaws.com)
  curl -s -o /dev/null -H 'CloudFront-Viewer-Address: 203.0.113.7:4444' "$1/api/genres?probe=$marker"
  for _ in $(seq 1 30); do
    # The edge's own lines only: anything else that mentions the marker is not JSON.
    client=$(aws logs filter-log-events --log-group-name "$(output edge_log_group)" \
      --start-time $((($(date +%s) - 300) * 1000)) --filter-pattern "\"$marker\"" \
      --query 'events[].message' --output json 2> /dev/null |
      jq -r '[.[] | fromjson? | .client // empty] | first // empty' 2> /dev/null || true)
    [[ -n $client ]] && break
    sleep 2
  done
  [[ -n $client && $client == "$me" ]] && return 0
  echo "        the edge logged the request as from ${client:-nothing, after a minute}"
  return 1
}

# --- main ----------------------------------------------------------------------------------------

for tool in aws terraform docker jq python3; do
  command -v "$tool" > /dev/null || fail "scripts/aws.sh needs $tool."
done

command="${1:-}"
[[ $# -gt 0 ]] && shift

if [[ $command == terraform ]]; then
  init
  tf "$@"
  exit
fi

for arg in "$@"; do
  case $arg in
    --yes) yes=true ;;
    --loadtest) loadtest=true ;;
    --no-catalogue-cache) catalogue_cache=false ;;
    --shards=*) shards=${arg#*=} ;;
    --shoppers=*) shoppers=${arg#*=} ;;
    --ramp=*) ramp=${arg#*=} ;;
    --hold=*) hold=${arg#*=} ;;
    *) fail "Unknown option: $arg" ;;
  esac
done

case $command in
  bootstrap | plan | up | smoke | loadtest | accounts | status | down | destroy) "cmd_$command" ;;
  *)
    awk 'NR > 2 && /^#/ { sub(/^# ?/, ""); print; next } NR > 2 { exit }' "$0"
    exit 64
    ;;
esac
