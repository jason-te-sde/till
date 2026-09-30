#!/usr/bin/env bash
# Runs till on AWS for as long as it is needed, and takes it down again.
#
#   scripts/aws.sh bootstrap   once per account: the state bucket and the image registries
#   scripts/aws.sh up          build and push the images, start everything, then run `smoke`
#   scripts/aws.sh smoke       check that the running deployment behaves
#   scripts/aws.sh accounts    the demonstration accounts' passwords
#   scripts/aws.sh status      what is billed by the hour and still there, and since when
#   scripts/aws.sh down        stop the hourly bill; the images, accounts, secrets and logs stay
#   scripts/aws.sh destroy     everything this created, the bootstrap included
#   scripts/aws.sh plan        what `up` would change, changing nothing
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
yes=false

# Terraform's AWS SDK cannot use the sessions `aws login` keeps, and credentials handed to it once
# would expire partway through a fifteen-minute apply. So Terraform gets a profile of its own whose
# credentials come from the AWS CLI through credential_process, which the SDK runs again whenever the
# last ones expire. The file holds that command, never a credential, and goes when this script does.
TERRAFORM_AWS_CONFIG=$(mktemp)
trap 'rm -f "$TERRAFORM_AWS_CONFIG"' EXIT
cat > "$TERRAFORM_AWS_CONFIG" << EOF
[profile till-terraform]
region = $AWS_REGION
credential_process = env AWS_CONFIG_FILE="${AWS_CONFIG_FILE:-$HOME/.aws/config}" aws configure export-credentials --profile "$AWS_PROFILE" --format process
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

  # ARM64 whatever builds them: that is what the task definitions ask Fargate for.
  for target in runtime edge; do
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

cmd_plan() {
  init
  tf plan -input=false -var running=true -var "image_tag=$(commit)" -var "kafka_version=$(kafka_version)"
}

cmd_up() {
  [[ -z $(git status --porcelain) ]] ||
    fail "The working tree has changes. The images are named after the commit they are built from: commit or stash first."
  local tag started
  tag=$(commit)
  started=$(date +%s)
  init
  push_images "$tag"

  say "Starting till at $tag: fifteen minutes or so, most of it CloudFront's VPC origin and the database"
  apply infra "Start it? From here it costs about \$$HOURLY an hour, until scripts/aws.sh down." \
    -var running=true -var "image_tag=$tag" -var "kafka_version=$(kafka_version)"

  say "Up in $(elapsed "$started"): $(output url)"
  cmd_smoke
  echo
  echo "About \$$HOURLY an hour from now on. scripts/aws.sh accounts for the sign-in; scripts/aws.sh down to stop."
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
  probe "ECS services, running" ecs describe-services --cluster till --services kafka ledger store edge --output text \
    --query "services[?status=='ACTIVE'].join(':', [serviceName, to_string(runningCount)])"

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
    client=$(aws logs filter-log-events --log-group-name "$(output edge_log_group)" \
      --start-time $((($(date +%s) - 300) * 1000)) --filter-pattern "\"$marker\"" \
      --query 'events[0].message' --output text 2> /dev/null | jq -r '.client // empty' 2> /dev/null || true)
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
for arg in "$@"; do
  case $arg in
    --yes) yes=true ;;
    *) fail "Unknown option: $arg" ;;
  esac
done

case $command in
  bootstrap | plan | up | smoke | accounts | status | down | destroy) "cmd_$command" ;;
  *)
    awk 'NR > 2 && /^#/ { sub(/^# ?/, ""); print; next } NR > 2 { exit }' "$0"
    exit 64
    ;;
esac
