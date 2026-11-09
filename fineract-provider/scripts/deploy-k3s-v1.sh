#!/usr/bin/env bash
# Builds fineract-provider.jar, layers it onto the running v1 image (Dockerfile.k3s-v1 swaps only the jar),
# publishes it, runs the Flyway migrations in a one-off Job and then rolls it out on the Orel k3s cluster:
# deploy/fineract (API) first, deploy/fineract-jobs (scheduler) only once the API is healthy on the new
# image. A failed rollout is undone. See README.md next to this script, or --help.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DOCKERFILE="$SCRIPT_DIR/Dockerfile.k3s-v1"

NAMESPACE="${NAMESPACE:-fused}"
CONTAINER="${CONTAINER:-fineract}"
SSH_HOST="${SSH_HOST:-k3s1}"
NODES="${NODES:-k3s1 k3s2 k3s3}"
GHCR_REPO="${GHCR_REPO:-ghcr.io/evoke-labs/fineract-backend}"
ECR_REPO="${ECR_REPO:-269280350252.dkr.ecr.us-east-1.amazonaws.com/evoke-labs/fineract-backend}"
AWS_REGION="${AWS_REGION:-us-east-1}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-15m}"
MIGRATE_TIMEOUT="${MIGRATE_TIMEOUT:-1200}"
MIGRATE_DB_NAME="${MIGRATE_DB_NAME:-}"
NODE_IPS="${NODE_IPS:-172.16.183.35 172.16.50.8 172.16.44.22}"
HEALTH_PATH="/fineract-provider/api/v1/evoke/health?tenantIdentifier=default"
PULL_SECRET=ghcr-pull
TAG_RE='^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$'
# What Fineract logs when tenant Flyway fails (flyway-core 2.1, table schema_version).
FLYWAY_RE='FlywayException|Migration of schema|failed migration'

# Per-target state lives in API_* / JOBS_* variables, read through dep/set_dep (bash 3.2 has no
# associative arrays). Names and NodePorts are fixed on purpose: no env override can retarget a deploy.
# shellcheck disable=SC2034
API_NAME=fineract API_PORT=30200 JOBS_NAME=fineract-jobs JOBS_PORT=31648

JAR="" REGISTRY=ghcr BASE="" TAG="" TARGET=all DRY_RUN=0 ROLLBACK=0 AUTO_ROLLBACK=1 ASSUME_YES=0
SKIP_MIGRATE=0 FORCE_JOBS=0 IGNORE_UNHEALTHY=0 ALLOW_DIRTY=0 RELAX_AA=0 ALLOW_UNAVAIL=0
API_MODE=surge # surge | relax | unavail1, decided in check_api_strategy
ORDER="" REUSE=0 DO_MIGRATE=0 NEW_IMAGE="" CHANGE_CAUSE="" REVISION="" MIGRATE_JOB=""
CRASHLOOP=0 FLYWAY_FAILED=0 LOGGED_IN="" TMP="" SUMMARY=""

usage() {
    cat <<EOF
Usage: $0 [options]

Build fineract-provider.jar, layer it onto the running image with Dockerfile.k3s-v1, publish it
(linux/amd64) and roll it out in namespace $NAMESPACE:

  preflight -> build -> push -> migrate Job -> deploy/$API_NAME (API, maxSurge 1 / maxUnavailable 0)
  -> health -> deploy/$JOBS_NAME (scheduler, Recreate) -> health

The scheduler is only patched once every API pod is Ready on the new image and healthy. A failed
rollout or health check is undone with rollout undo --to-revision, and the run stops.

Options:
  --target T          all (default: api, then jobs) | api | jobs
                        jobs alone deploys the image deploy/$API_NAME already runs (no build, no
                        migrate) after checking the API is healthy on it
  --jar PATH          use this jar instead of running ./build-jar.sh (needs a Java 8 JDK)
  --registry R        ghcr (default) | ecr | import
                        ghcr    push to $GHCR_REPO, deploy by digest and add
                                imagePullSecrets $PULL_SECRET in the same patch. The gh token
                                needs write:packages:  gh auth refresh -s write:packages
                        ecr     push to $ECR_REPO, deploy by digest (aws creds)
                        import  no registry: docker save | k3s ctr images import on every node
                                in NODES, deploy by tag (the tag must exist on all nodes)
  --base IMAGE        base image (default: current image of deploy/$API_NAME)
  --tag TAG           image tag (default: v1-<git short sha>-<local YYYYMMDD-HHMM>)
  --skip-migrate      no migration Job: the first new pod runs Flyway itself
  --no-auto-rollback  on a failed rollout or health check, stop without undoing it
  --force-jobs        with --target jobs: build a new image and deploy it to jobs only, without
                      proving it on the API first
  --ignore-unhealthy  go ahead although a deployment is degraded or crash-looping
  --relax-anti-affinity  deploy/fineract has a REQUIRED one-pod-per-node anti-affinity and as many
                      replicas as nodes, so a surge pod can never schedule. This flag turns it into
                      PREFERRED (weight 100) in the same patch, so maxSurge 1 / maxUnavailable 0 works
                      (zero downtime; the surge pod briefly shares a node). Permanent.
  --allow-unavailable-1  instead keep one-pod-per-node and roll with maxSurge 0 / maxUnavailable 1
                      (one API pod down at a time; the image is already proven by the migrate Job)
  --allow-dirty       build from a git tree with uncommitted changes (the tag gets -dirty)
  --dry-run           read-only: preflight, plan, build/push commands, the migration Job and every
                      patch through kubectl --dry-run=server
  --rollback          rollout undo to the previous revision of each target (all: jobs first, then
                      api), wait and health-check each
  -y, --yes           skip the confirmation prompt
  -h, --help          show this help

Environment overrides (current values):
  NAMESPACE=$NAMESPACE CONTAINER=$CONTAINER SSH_HOST=$SSH_HOST NODES="$NODES"
  AWS_REGION=$AWS_REGION ROLLOUT_TIMEOUT=$ROLLOUT_TIMEOUT MIGRATE_TIMEOUT=$MIGRATE_TIMEOUT (seconds)
  MIGRATE_DB_NAME=${MIGRATE_DB_NAME:-<unset>}: keyspace for the migration Job; it connects to
    <keyspace>@primary through vtgate (EVOKE_SQL_TENANT_DB_NAME_OVERRIDE)
  GHCR_REPO=$GHCR_REPO
  ECR_REPO=$ECR_REPO
  NODE_IPS="$NODE_IPS"
  KUBECONFIG_MODE=local or KUBECONTEXT=<ctx>: use the local kubectl (you open the tunnel)
    instead of 'ssh \$SSH_HOST kubectl'; health checks and imports still go over ssh.
EOF
}

log() { printf '[deploy-k3s-v1] %s\n' "$*" >&2; }
warn() { log "WARNING: $*"; }
die() {
    log "ERROR: $*"
    exit 1
}
dry() { log "[dry-run] $*"; }
need() { command -v "$1" >/dev/null 2>&1 || die "'$1' not found in PATH"; }
note() { SUMMARY+="  $*"$'\n'; }

cleanup() {
    if [[ -n $SUMMARY ]]; then printf '[deploy-k3s-v1] summary:\n%s' "$SUMMARY" >&2; fi
    if [[ -n $TMP ]]; then rm -rf "$TMP"; fi
}

# Print the command, then run it (or only print it with --dry-run).
run() {
    if ((DRY_RUN)); then
        dry "$(printf '%q ' "$@")"
        return 0
    fi
    log "+ $(printf '%q ' "$@")"
    "$@"
}

# Run a command on an ssh host; every argument is quoted for the remote shell.
on() {
    local host=$1
    shift
    ssh -n "$host" "$(printf '%q ' "$@")"
}

local_kubectl() { [[ ${KUBECONFIG_MODE:-} == local || -n ${KUBECONTEXT:-} ]]; }
kube_where() {
    if local_kubectl; then echo "local kubectl${KUBECONTEXT:+ --context $KUBECONTEXT}"; else echo "kubectl via ssh $SSH_HOST"; fi
}
# kubectl that keeps stdin (apply -f -).
kctl_in() {
    if local_kubectl; then
        kubectl ${KUBECONTEXT:+--context "$KUBECONTEXT"} -n "$NAMESPACE" "$@"
    else
        # shellcheck disable=SC2029 # client-side on purpose: %q quotes every argument for the remote shell
        ssh "$SSH_HOST" "$(printf '%q ' kubectl -n "$NAMESPACE" "$@")"
    fi
}
# kubectl with stdin closed (like ssh -n), safe inside 'while read' loops.
kctl() { kctl_in "$@" </dev/null; }

pfx() { case $1 in api) echo API ;; jobs) echo JOBS ;; *) die "unknown target: $1" ;; esac }
set_dep() { printf -v "$(pfx "$1")_$2" '%s' "$3"; }
dep() {
    local n
    n="$(pfx "$1")_$2"
    printf '%s' "${!n:-}"
}

# load_dep api|jobs: read the live deployment into <T>_REPLICAS READY SELECTOR IMAGE REV STRATEGY DTAG.
load_dep() {
    local name info f i=0 vals
    name=$(dep "$1" NAME)
    info=$(kctl get deployment "$name" -o "jsonpath={.spec.replicas}|{.status.readyReplicas}|{.spec.selector.matchLabels}|{.spec.template.spec.containers[?(@.name==\"$CONTAINER\")].image}|{.metadata.annotations.deployment\\.kubernetes\\.io/revision}|{.spec.strategy.type}|{.spec.template.metadata.annotations.evoke\\.io/deploy-tag}") ||
        die "cannot read deployment/$name in $NAMESPACE ($(kube_where))"
    IFS='|' read -r -a vals <<<"$info"
    for f in REPLICAS READY SELECTOR IMAGE REV STRATEGY DTAG; do
        set_dep "$1" "$f" "${vals[i]:-}"
        i=$((i + 1))
    done
    set_dep "$1" SELECTOR "$(dep "$1" SELECTOR | tr -d '{}"' | tr : =)" # {"app":"x"} -> app=x
    [[ -n $(dep "$1" IMAGE) ]] || die "container '$CONTAINER' not found in deployment/$name"
}

confirm() {
    if ((ASSUME_YES)); then return 0; fi
    [[ -t 0 ]] || die "no terminal to confirm on; re-run with --yes"
    local ans
    read -r -p "[deploy-k3s-v1] $1 [y/N] " ans
    [[ $ans == [yY] || $ans == [yY][eE][sS] ]] || die "aborted"
}

is_java8() {
    local v
    [[ -x $1/bin/javac ]] && v=$("$1/bin/javac" -version 2>&1) && [[ $v == *" 1.8."* ]]
}

resolve_java8() {
    if ! is_java8 "${JAVA_HOME:-}"; then
        JAVA_HOME=""
        if [[ -x /usr/libexec/java_home ]]; then JAVA_HOME=$(/usr/libexec/java_home -v 1.8 2>/dev/null || true); fi
        is_java8 "$JAVA_HOME" || die "no Java 8 JDK found (/usr/libexec/java_home -v 1.8); install one or export JAVA_HOME"
    fi
    export JAVA_HOME
    log "JAVA_HOME=$JAVA_HOME"
}

registry_login() {
    local host=$1 user
    case "$LOGGED_IN " in *" $host "*) return 0 ;; esac
    LOGGED_IN+=" $host"
    case $host in
        ghcr.io)
            if [[ -n ${GHCR_USE_DOCKER_LOGIN:-} ]]; then
                log "ghcr.io: GHCR_USE_DOCKER_LOGIN set, using the existing docker login"
                return 0
            fi
            need gh
            # Always log in fresh with the gh token: a stale saved ghcr.io login is the usual cause
            # of "token provided does not match expected scopes".
            if ! gh auth status -h github.com 2>&1 | grep -q "write:packages"; then
                die "gh token lacks write:packages; run: gh auth refresh -h github.com -s write:packages (or use --registry ecr)"
            fi
            if ((DRY_RUN)); then
                dry "gh auth token | docker login ghcr.io -u <gh user> --password-stdin"
                return 0
            fi
            user=$(gh api user -q .login)
            log "ghcr.io: docker login as $user with the gh token"
            gh auth token | docker login ghcr.io -u "$user" --password-stdin >/dev/null
            ;;
        *.dkr.ecr.*.amazonaws.com)
            need aws
            if ((DRY_RUN)); then
                dry "aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $host"
                return 0
            fi
            log "$host: docker login via aws ecr get-login-password"
            aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$host" >/dev/null
            ;;
        *) log "$host: no login step, using existing docker credentials" ;;
    esac
}

print_migration_help() {
    cat >&2 <<'EOF'
[deploy-k3s-v1] ---- failed Flyway migration: what to do ----
  Flyway 2.1 refuses to start while schema_version holds a row with success = 0, so pods on ANY image,
  old ones included, will not start until that row is removed. Do not restart or roll back pods hoping
  it clears. This script never modifies the database. Any DB change below needs Hashan's approval.

  1. On the tenant schema, via the PRIMARY (vtgate: USE `<keyspace>@primary`), find the failed row:
       SELECT installed_rank, version, script, success, installed_on FROM schema_version WHERE success = 0;
  2. Open that migration (sql/migrations/core_db/V<version>__*.sql) and check which of its objects
     already exist (SHOW CREATE TABLE, information_schema.COLUMNS / STATISTICS). MySQL DDL is not
     transactional, so it may have been applied partially.
  3. Drop the partially created objects, or finish the migration by hand.
  4. Remove the failed row (Flyway 2.1 has no 'repair' command):
       DELETE FROM schema_version WHERE success = 0 AND version = '<version>';
     Flyway then runs V<version> again on the next start. If you finished it by hand, that re-run fails
     unless the SQL is idempotent; in that case agree with Hashan how to record it instead.
  5. Fix the migration SQL if it was wrong, rebuild, and re-run this script.
EOF
}

# scan_pods api|jobs: report crash-looping / not-ready pods and grep their logs (previous and current
# container) for a Flyway failure. Sets CRASHLOOP=1 / FLYWAY_FAILED=1.
scan_pods() {
    local pods pod ready reason deleting logs hits
    pods=$(kctl get pods -l "$(dep "$1" SELECTOR)" -o "jsonpath={range .items[*]}{.metadata.name}|{.status.containerStatuses[?(@.name==\"$CONTAINER\")].ready}|{.status.containerStatuses[?(@.name==\"$CONTAINER\")].state.waiting.reason}|{.metadata.deletionTimestamp}{\"\\n\"}{end}") || return 0
    while IFS='|' read -r pod ready reason deleting; do
        if [[ -z $pod || -n $deleting || $ready == true ]]; then continue; fi
        if [[ $reason == CrashLoopBackOff ]]; then
            warn "pod $pod is in CrashLoopBackOff"
            CRASHLOOP=1
        else
            log "  pod $pod is not ready${reason:+ ($reason)}"
        fi
        logs=$({
            kctl logs "$pod" -c "$CONTAINER" --previous --tail=300
            kctl logs "$pod" -c "$CONTAINER" --tail=300
        } 2>/dev/null) || true
        hits=$(grep -E "$FLYWAY_RE" <<<"$logs" | tail -n 15) || true
        if [[ -n $hits ]]; then
            warn "pod $pod logs show a Flyway migration failure:"
            printf '%s\n' "$hits" >&2
            FLYWAY_FAILED=1
        fi
    done <<<"$pods"
}

# Refuse to deploy on top of a degraded deployment: that is how one failed migration took down every pod.
check_cluster() {
    local t name replicas ready bad=0
    for t in api jobs; do
        name=$(dep "$t" NAME) replicas=$(dep "$t" REPLICAS) ready=$(dep "$t" READY)
        log "deployment/$name: ${ready:-0}/${replicas:-0} ready, revision $(dep "$t" REV), $(dep "$t" IMAGE)"
        if ((${ready:-0} < ${replicas:-0})); then
            warn "deployment/$name is not fully available"
            bad=1
        fi
        scan_pods "$t"
    done
    if ((FLYWAY_FAILED)); then print_migration_help; fi
    if ((!bad && !CRASHLOOP && !FLYWAY_FAILED)); then return 0; fi
    if ((IGNORE_UNHEALTHY)); then
        warn "--ignore-unhealthy: continuing anyway"
        return 0
    fi
    if ((FLYWAY_FAILED)); then
        die "a failed Flyway migration blocks startup of every image, old ones included; fix schema_version first (above). Nothing was changed."
    fi
    if ((ROLLBACK)); then
        warn "rolling back while degraded"
        return 0
    fi
    die "deployments are not healthy (above); fix that first, or pass --ignore-unhealthy. Nothing was changed."
}

# deploy/fineract has a required one-pod-per-hostname anti-affinity; with replicas >= schedulable nodes a
# maxSurge pod can never be scheduled, so the rollout would hang and be undone. Make the operator choose.
check_api_strategy() {
    local req nodes reps
    case " $ORDER " in *" api "*) ;; *) return 0 ;; esac
    req=$(kctl get deployment "$API_NAME" -o 'jsonpath={.spec.template.spec.affinity.podAntiAffinity.requiredDuringSchedulingIgnoredDuringExecution[*].topologyKey}')
    nodes=$(kctl get nodes -o 'jsonpath={range .items[?(@.spec.unschedulable!=true)]}{.metadata.name}{"\n"}{end}' | grep -c . || true)
    reps=$(dep api REPLICAS)
    if [[ $req != *kubernetes.io/hostname* || $reps -lt $nodes ]]; then
        API_MODE=surge
        return 0
    fi
    if ((RELAX_AA && ALLOW_UNAVAIL)); then die "use only one of --relax-anti-affinity / --allow-unavailable-1"; fi
    if ((RELAX_AA)); then
        API_MODE=relax
    elif ((ALLOW_UNAVAIL)); then
        API_MODE=unavail1
    else
        log "deployment/$API_NAME: $reps replicas on $nodes schedulable nodes with a REQUIRED one-pod-per-node anti-affinity,"
        log "  so a zero-downtime surge pod (maxSurge 1 / maxUnavailable 0) can never be scheduled."
        log "  Choose one (Hashan's call):"
        log "    --relax-anti-affinity   make the anti-affinity preferred, keep zero downtime (permanent change)"
        log "    --allow-unavailable-1   keep one-pod-per-node, replace one API pod at a time (2 of $reps serving)"
        die "API rollout strategy needs a decision; nothing was changed"
    fi
}

preflight() {
    local mjobs j active failed
    need ssh
    if local_kubectl; then need kubectl; fi
    if ((!ROLLBACK && !REUSE)); then
        need git
        need docker
        docker info >/dev/null 2>&1 || die "docker daemon not reachable; start Docker Desktop"
        docker buildx version >/dev/null 2>&1 || die "docker buildx not available"
        log "buildx builder: $(docker buildx inspect 2>/dev/null | awk '/^Name:/ && !n {n=$2} /^Driver:/ && !d {d=$2} END {print n " (driver " d ")"}')"
        if ((DO_MIGRATE)); then need python3; fi
    fi

    ssh -n -o ConnectTimeout=15 "$SSH_HOST" true || die "cannot ssh to $SSH_HOST (needs the ~/.ssh/config entry via t651)"
    log "ssh $SSH_HOST: ok ($(kube_where))"

    load_dep api
    load_dep jobs
    if [[ $(dep jobs STRATEGY) != Recreate || $(dep jobs REPLICAS) -gt 1 ]]; then
        die "deployment/$JOBS_NAME must be strategy Recreate with 1 replica (is $(dep jobs STRATEGY), $(dep jobs REPLICAS)): two schedulers must never run"
    fi
    check_cluster

    mjobs=$(kctl get jobs -l app=fineract-migrate -o "jsonpath={range .items[*]}{.metadata.name}|{.status.active}|{.status.failed}{\"\\n\"}{end}")
    while IFS='|' read -r j active failed; do
        if [[ -z $j ]]; then continue; fi
        if [[ ${active:-0} != 0 ]]; then die "job/$j is still running: another deploy is migrating right now"; fi
        if [[ ${failed:-0} != 0 ]]; then warn "job/$j failed earlier: schema_version may still hold a success = 0 row"; fi
    done <<<"$mjobs"
    if ((ROLLBACK)); then return 0; fi
    check_api_strategy
    if ((REUSE)); then return 0; fi

    if [[ $REGISTRY == ghcr ]]; then
        kctl get secret "$PULL_SECRET" -o name >/dev/null || die "secret $PULL_SECRET not found in $NAMESPACE"
    fi
    if [[ -n $JAR ]]; then
        [[ -f $JAR ]] || die "jar not found: $JAR"
    else
        resolve_java8
    fi
}

wait_rollout() {
    local name
    name=$(dep "$1" NAME)
    log "waiting for rollout of deployment/$name (timeout $ROLLOUT_TIMEOUT)"
    if kctl rollout status deployment/"$name" --timeout="$ROLLOUT_TIMEOUT"; then return 0; fi
    warn "rollout of deployment/$name did not complete; pods and recent events:"
    kctl get pods -l "$(dep "$1" SELECTOR)" -o wide || true
    kctl get events --sort-by=.lastTimestamp | grep -F "$name" | tail -n 25 || true
    scan_pods "$1"
    if ((FLYWAY_FAILED)); then print_migration_help; fi
    return 1
}

# health_check api|jobs IMAGE: every replica Ready on IMAGE, and the NodePort answers 200 on every node.
health_check() {
    local t=$1 want=$2 name replicas port pods pod ready image deleting ip code bad=0 n=0
    name=$(dep "$t" NAME) replicas=$(dep "$t" REPLICAS) port=$(dep "$t" PORT)
    log "health check deployment/$name: $replicas pod(s) ready on $want, NodePort $port on every node"
    pods=$(kctl get pods -l "$(dep "$t" SELECTOR)" -o "jsonpath={range .items[*]}{.metadata.name}|{.status.containerStatuses[?(@.name==\"$CONTAINER\")].ready}|{.spec.containers[?(@.name==\"$CONTAINER\")].image}|{.metadata.deletionTimestamp}{\"\\n\"}{end}") || return 1
    while IFS='|' read -r pod ready image deleting; do
        if [[ -z $pod || -n $deleting ]]; then continue; fi
        n=$((n + 1))
        if [[ $ready == true && $image == "$want" ]]; then
            log "  ok   pod $pod"
        else
            log "  FAIL pod $pod ready=$ready image=$image"
            bad=1
        fi
    done <<<"$pods"
    if [[ $n != "$replicas" ]]; then
        log "  FAIL expected $replicas running pod(s), found $n"
        bad=1
    fi

    for ip in $NODE_IPS; do
        for _ in 1 2 3; do # kube-proxy may lag a few seconds behind pod readiness
            code=$(on "$SSH_HOST" curl -sk -o /dev/null -w '%{http_code}' --max-time 20 "https://$ip:$port$HEALTH_PATH") || true
            if [[ $code == 200 ]]; then break; fi
            sleep 5
        done
        if [[ $code == 200 ]]; then
            log "  ok   https://$ip:$port -> 200"
        else
            log "  FAIL https://$ip:$port -> ${code:-no response}"
            bad=1
        fi
    done
    if ((bad)); then
        log "health check of deployment/$name FAILED"
        return 1
    fi
    log "deployment/$name healthy"
}

# Registry digests get pulled, so ghcr images need the pull secret; node-imported tags are never pulled.
needs_pull_secret() { [[ $REGISTRY != import && $NEW_IMAGE == ghcr.io/* ]]; }

# Strategic patch: image, change-cause, deploy tag and (api only) the zero-downtime strategy, so it is
# a single rollout.
patch_json() {
    local strategy="" secrets="" cause=${CHANGE_CAUSE//[\"\\]/} # keep the JSON valid
    local affinity=""
    if [[ $1 == api ]]; then
        case $API_MODE in
            unavail1) strategy='"strategy":{"type":"RollingUpdate","rollingUpdate":{"maxSurge":0,"maxUnavailable":1}},' ;;
            *) strategy='"strategy":{"type":"RollingUpdate","rollingUpdate":{"maxSurge":1,"maxUnavailable":0}},' ;;
        esac
        if [[ $API_MODE == relax ]]; then
            affinity='"affinity":{"podAntiAffinity":{"requiredDuringSchedulingIgnoredDuringExecution":null,"preferredDuringSchedulingIgnoredDuringExecution":[{"weight":100,"podAffinityTerm":{"labelSelector":{"matchLabels":{"app":"fineract"}},"topologyKey":"kubernetes.io/hostname"}}]}},'
        fi
    fi
    if needs_pull_secret; then secrets="\"imagePullSecrets\":[{\"name\":\"$PULL_SECRET\"}],"; fi
    printf '{"metadata":{"annotations":{"kubernetes.io/change-cause":"%s"}},"spec":{%s"template":{"metadata":{"annotations":{"evoke.io/deploy-tag":"%s"}},"spec":{%s%s"containers":[{"name":"%s","image":"%s"}]}}}}' \
        "$cause" "$strategy" "$TAG" "$affinity" "$secrets" "$CONTAINER" "$NEW_IMAGE"
}

resolve_tag() {
    local sha branch dirty=""
    sha=$(git -C "$REPO_ROOT" rev-parse HEAD)
    branch=$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)
    if [[ -n $(git -C "$REPO_ROOT" status --porcelain --untracked-files=no) ]]; then
        ((ALLOW_DIRTY)) || die "git tree has uncommitted changes; commit them first (or pass --allow-dirty)"
        warn "--allow-dirty: building from uncommitted changes; the image is NOT reproducible from $sha"
        dirty=-dirty
    fi
    TAG="${TAG:-v1-$(git -C "$REPO_ROOT" rev-parse --short HEAD)-$(date +%Y%m%d-%H%M)}"
    if [[ -n $dirty && $TAG != *-dirty ]]; then TAG+=-dirty; fi
    [[ $TAG =~ $TAG_RE ]] || die "invalid image tag: $TAG"
    REVISION=$sha$dirty
    CHANGE_CAUSE="deploy-k3s-v1 $TAG sha=$sha branch=$branch by=${USER:-unknown} registry=$REGISTRY"
}

import_to_nodes() {
    local ref=$1 tarball="$TMP/image.tar.gz" node
    if ((DRY_RUN)); then
        dry "docker save $ref | gzip -1 > $tarball"
        for node in $NODES; do
            dry "ssh $node 'gunzip | k3s ctr -n k8s.io images import -' < $tarball; ssh $node k3s crictl images -q $ref"
        done
        return 0
    fi
    log "+ docker save $ref | gzip -1 > $tarball"
    docker save "$ref" | gzip -1 >"$tarball"
    for node in $NODES; do
        log "importing into $node ($(du -h "$tarball" | cut -f1))"
        ssh "$node" 'gunzip | k3s ctr -n k8s.io images import -' <"$tarball"
        [[ -n $(on "$node" k3s crictl images -q "$ref") ]] || die "$ref not visible to the CRI on $node after import"
        log "$node: $ref present"
    done
}

build_image() {
    local repo out ref digest
    if [[ $REGISTRY == ecr ]]; then repo=$ECR_REPO; else repo=$GHCR_REPO; fi

    if [[ -z $JAR ]]; then
        JAR="$REPO_ROOT/build/libs/fineract-provider.jar"
        if [[ -x $REPO_ROOT/build-jar.sh ]]; then
            run "$REPO_ROOT/build-jar.sh"
        else
            # Same as build-jar.sh / the Jenkinsfile 'Build' stage, for branches without it.
            if ((DRY_RUN)); then
                dry "(cd $REPO_ROOT/fineract-provider && ./gradlew clean jar copyDeps)"
            else
                log "+ (cd $REPO_ROOT/fineract-provider && ./gradlew clean jar copyDeps)"
                (cd "$REPO_ROOT/fineract-provider" && ./gradlew clean jar copyDeps)
            fi
        fi
    fi
    if [[ -f $JAR ]]; then
        log "jar: $JAR ($(du -h "$JAR" | cut -f1))"
    elif ((DRY_RUN)); then
        warn "jar not built yet: $JAR"
    else
        die "jar not found after build: $JAR"
    fi

    BASE=${BASE:-$(dep api IMAGE)}
    case $BASE in "$GHCR_REPO"[@:]* | "$ECR_REPO"@*)
        warn "base $BASE was built by this script: the new jar layer stacks on the old one (works, image grows)," \
            "and a node-imported base is in no registry. Prefer --base <original ECR image>." ;;
    esac
    ref="$repo:$TAG"
    log "base: $BASE"
    log "tag:  $TAG"

    registry_login "${BASE%%/*}"
    if [[ $REGISTRY != import ]]; then registry_login "${repo%%/*}"; fi

    mkdir "$TMP/ctx"
    if ((!DRY_RUN)); then cp "$JAR" "$TMP/ctx/fineract-provider.jar"; fi
    if [[ $REGISTRY == import ]]; then out=--load; else out=--push; fi
    run docker buildx build --platform linux/amd64 --provenance=false \
        --build-arg "BASE_IMAGE=$BASE" --build-arg "REVISION=$REVISION" \
        --build-arg "CREATED=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        -f "$DOCKERFILE" -t "$ref" "$out" "$TMP/ctx" ||
        die "image build/push failed (if 'denied' on ghcr: gh auth refresh -s write:packages, docker logout ghcr.io, re-run with --jar $JAR)"

    if [[ $REGISTRY == import ]]; then
        import_to_nodes "$ref"
        NEW_IMAGE=$ref
    elif ((DRY_RUN)); then
        dry "docker buildx imagetools inspect $ref  # resolve the pushed digest, deploy $repo@sha256:<digest>"
        NEW_IMAGE=$ref
    else
        digest=$(docker buildx imagetools inspect "$ref" | awk '/^Digest:/ && !d {d=$2} END {print d}')
        [[ $digest == sha256:* ]] || die "could not resolve the pushed digest of $ref"
        NEW_IMAGE="$repo@$digest"
        note "image: $NEW_IMAGE (tag $TAG)"
    fi
}

# Writes $TMP/job.json (and job-redacted.json for printing): a one-off Job built from the live pod
# template of deploy/fineract, running only the fineract container on NEW_IMAGE. Fineract runs tenant
# Flyway at startup, so "health returns 200" means the migrations are done.
build_migrate_job() {
    local secret=""
    MIGRATE_JOB="fineract-migrate-$(printf '%s' "$TAG" | tr '[:upper:]' '[:lower:]' | sed -e 's/[^a-z0-9-]/-/g' | cut -c1-46 | sed -e 's/-*$//')"
    if kctl get job "$MIGRATE_JOB" -o name >/dev/null 2>&1; then
        die "job/$MIGRATE_JOB already exists (kept from an earlier run for inspection); delete it: kubectl -n $NAMESPACE delete job $MIGRATE_JOB"
    fi
    if needs_pull_secret; then secret=$PULL_SECRET; fi
    if [[ -z $MIGRATE_DB_NAME ]]; then
        warn "MIGRATE_DB_NAME not set: Flyway uses the tenant's configured db name through vtgate's default routing (set MIGRATE_DB_NAME=<keyspace> to force <keyspace>@primary)"
    fi
    kctl get deployment "$API_NAME" -o json >"$TMP/api.json"
    JOB_NAME="$MIGRATE_JOB" JOB_IMAGE="$NEW_IMAGE" JOB_CONTAINER="$CONTAINER" JOB_TIMEOUT="$MIGRATE_TIMEOUT" \
        JOB_DB="$MIGRATE_DB_NAME" JOB_PULL_SECRET="$secret" JOB_TAG="$TAG" \
        python3 - "$TMP/api.json" "$TMP/job.json" "$TMP/job-redacted.json" <<'PY'
import json, os, sys

src, out, redacted_out = sys.argv[1:4]
e = os.environ
dep = json.load(open(src))
spec = dep["spec"]["template"]["spec"]
found = [c for c in spec["containers"] if c["name"] == e["JOB_CONTAINER"]]
if not found:
    sys.exit("container %s not found in deployment/%s" % (e["JOB_CONTAINER"], dep["metadata"]["name"]))
c = found[0]
c["image"] = e["JOB_IMAGE"]
for probe in ("livenessProbe", "readinessProbe", "startupProbe"):
    c.pop(probe, None)
if e["JOB_DB"]:
    c["env"] = [v for v in c.get("env", []) if v["name"] != "EVOKE_SQL_TENANT_DB_NAME_OVERRIDE"]
    c["env"].append({"name": "EVOKE_SQL_TENANT_DB_NAME_OVERRIDE", "value": e["JOB_DB"] + "@primary"})
spec["containers"] = [c]
spec["restartPolicy"] = "Never"
# The API pods' required one-per-node anti-affinity matches app=fineract pods on every node, so the Job
# pod could never schedule with it; the Job is a single short-lived pod, so drop scheduling constraints.
for k in ("affinity", "topologySpreadConstraints", "nodeName"):
    spec.pop(k, None)
if e["JOB_PULL_SECRET"]:
    secrets = spec.get("imagePullSecrets") or []
    if e["JOB_PULL_SECRET"] not in [s.get("name") for s in secrets]:
        secrets.append({"name": e["JOB_PULL_SECRET"]})
    spec["imagePullSecrets"] = secrets
# app=fineract-migrate: no Service selects the Job pod, so it never takes API traffic.
labels = {"app": "fineract-migrate"}
annotations = {"evoke.io/deploy-tag": e["JOB_TAG"]}
job = {
    "apiVersion": "batch/v1",
    "kind": "Job",
    "metadata": {"name": e["JOB_NAME"], "labels": labels, "annotations": annotations},
    "spec": {
        "backoffLimit": 0,
        "activeDeadlineSeconds": int(e["JOB_TIMEOUT"]),
        "ttlSecondsAfterFinished": 3600,
        "template": {"metadata": {"labels": labels, "annotations": annotations}, "spec": spec},
    },
}
with open(out, "w") as f:
    json.dump(job, f, indent=2)
for v in c.get("env", []):
    if "value" in v and v["name"] != "EVOKE_SQL_TENANT_DB_NAME_OVERRIDE":
        v["value"] = "<redacted>"
with open(redacted_out, "w") as f:
    json.dump(job, f, indent=2)
PY
}

migrate_failed() {
    local pod=$1
    log "migration FAILED: $2"
    if [[ -n $pod ]]; then
        log "last Flyway / migration / ERROR log lines of pod $pod:"
        { kctl logs "$pod" -c "$CONTAINER" 2>/dev/null || true; } | grep -E 'ERROR|[Ff]lyway|[Mm]igrat' | tail -n 80 >&2 || true
        log "events of pod $pod:"
        kctl get events --field-selector "involvedObject.name=$pod" --sort-by=.lastTimestamp 2>/dev/null | tail -n 10 >&2 || true
    fi
    log "job/$MIGRATE_JOB is kept for inspection (removed 1h after it finishes): kubectl -n $NAMESPACE logs job/$MIGRATE_JOB"
    print_migration_help
    note "migrate: FAILED ($2); job/$MIGRATE_JOB kept; no deployment was patched"
    die "migration failed; no deployment was changed"
}

# Apply the migration Job and poll every 10 s until its pod is healthy (done) or something failed.
migrate() {
    local pod phase ip waiting failed logs code status deadline
    local cannot_start='^(ImagePullBackOff|ErrImageNeverPull|InvalidImageName|CreateContainerConfigError|CreateContainerError)$'
    log "+ kubectl -n $NAMESPACE apply -f -  # job/$MIGRATE_JOB on $NEW_IMAGE"
    kctl_in apply -f - <"$TMP/job.json" >&2
    deadline=$((SECONDS + MIGRATE_TIMEOUT))
    while :; do
        sleep 10
        IFS='|' read -r pod phase ip waiting <<<"$(kctl get pods -l "job-name=$MIGRATE_JOB" -o "jsonpath={range .items[*]}{.metadata.name}|{.status.phase}|{.status.podIP}|{.status.containerStatuses[?(@.name==\"$CONTAINER\")].state.waiting.reason}{\"\\n\"}{end}" 2>/dev/null || true)"
        failed=$(kctl get job "$MIGRATE_JOB" -o 'jsonpath={.status.failed}' 2>/dev/null || true)
        logs=""
        if [[ -n $pod ]]; then logs=$(kctl logs "$pod" -c "$CONTAINER" --tail=500 2>/dev/null || true); fi

        status=""
        if grep -Eq "$FLYWAY_RE" <<<"$logs"; then
            status="Flyway error in the pod log"
        elif [[ ${failed:-0} != 0 || $phase == Failed ]]; then
            status="the Job/pod failed"
        elif [[ $waiting =~ $cannot_start ]]; then
            status="the container cannot start ($waiting)"
        fi
        if [[ -n $status ]]; then migrate_failed "$pod" "$status"; fi

        if [[ $phase == Running && -n $ip ]]; then
            code=$(on "$SSH_HOST" curl -sk -o /dev/null -w '%{http_code}' --max-time 10 "https://$ip:8443$HEALTH_PATH") || true
            if [[ $code == 200 ]]; then
                log "migration done: pod $pod is healthy"
                kctl delete job "$MIGRATE_JOB" --wait=false >&2 || warn "could not delete job/$MIGRATE_JOB (its ttl removes it within 1h)"
                note "migrate: ok (job/$MIGRATE_JOB deleted)"
                return 0
            fi
        fi
        if ((SECONDS >= deadline)); then migrate_failed "$pod" "timed out after ${MIGRATE_TIMEOUT}s"; fi
        log "  migrating: pod ${pod:-(not created yet)} ${phase:-Pending}${waiting:+ ($waiting)}, $((deadline - SECONDS))s left"
    done
}

print_plan() {
    local t n=1 db
    log "plan for namespace $NAMESPACE ($(kube_where)), in this order:"
    if ((DO_MIGRATE)); then
        if [[ -n $MIGRATE_DB_NAME ]]; then db="$MIGRATE_DB_NAME@primary"; else db="the tenant's db (vtgate default routing)"; fi
        log "  $n. migrate: job/$MIGRATE_JOB runs Flyway on the new image against $db,"
        log "       waits for its health URL to return 200 (timeout ${MIGRATE_TIMEOUT}s), then is deleted"
        n=$((n + 1))
    elif ((REUSE)); then
        log "     migrate: not needed, deployment/$API_NAME already started on this image"
    else
        log "     migrate: SKIPPED (--skip-migrate): the first new pod runs Flyway itself"
    fi
    for t in $ORDER; do
        log "  $n. $t: deployment/$(dep "$t" NAME), $(dep "$t" REPLICAS) pod(s), now at revision $(dep "$t" REV)"
        log "       current: $(dep "$t" IMAGE)"
        log "       new:     $NEW_IMAGE"
        if [[ $t == api ]]; then
            case $API_MODE in
                surge) log "       strategy -> RollingUpdate maxSurge 1 / maxUnavailable 0 (PERMANENT, same patch as the image)" ;;
                relax) log "       strategy -> RollingUpdate maxSurge 1 / maxUnavailable 0 and anti-affinity required -> preferred (PERMANENT, same patch)" ;;
                unavail1) log "       strategy -> RollingUpdate maxSurge 0 / maxUnavailable 1 (--allow-unavailable-1: one API pod down at a time)" ;;
            esac
        else
            log "       strategy Recreate (unchanged): the scheduler is down while the new pod starts"
        fi
        log "       then: all pods Ready on the new image + NodePort $(dep "$t" PORT) returns 200 on every node"
        n=$((n + 1))
    done
    if [[ $ORDER == "api jobs" ]]; then log "  jobs is only patched after api has rolled out and passed its health check"; fi
    if ((AUTO_ROLLBACK)); then
        log "  on failure: rollout undo --to-revision <revision above> for the failed deployment, then stop"
    else
        log "  on failure: stop and leave the deployment as is (--no-auto-rollback)"
    fi
}

dry_run_patches() {
    local t name patch
    for t in $ORDER; do
        name=$(dep "$t" NAME)
        patch=$(patch_json "$t")
        dry "kubectl -n $NAMESPACE patch deployment $name --type strategic -p '$patch'"
        log "server-side dry-run of that patch:"
        kctl patch deployment "$name" --type strategic -p "$patch" --dry-run=server -o "jsonpath=  image:            {.spec.template.spec.containers[?(@.name==\"$CONTAINER\")].image}{\"\\n\"}  strategy:         {.spec.strategy}{\"\\n\"}  imagePullSecrets: {.spec.template.spec.imagePullSecrets[*].name}{\"\\n\"}  change-cause:     {.metadata.annotations.kubernetes\\.io/change-cause}{\"\\n\"}  deploy-tag:       {.spec.template.metadata.annotations.evoke\\.io/deploy-tag}{\"\\n\"}"
    done
}

undo() {
    local t=$1 name rev
    name=$(dep "$t" NAME) rev=$(dep "$t" REV)
    [[ -n $rev ]] || return 1
    warn "auto-rollback: kubectl rollout undo deployment/$name --to-revision=$rev"
    kctl rollout undo deployment/"$name" --to-revision="$rev" >&2 || return 1
    wait_rollout "$t" && health_check "$t" "$(dep "$t" IMAGE)"
}

deploy_target() {
    local t=$1 name rev patch
    name=$(dep "$t" NAME) rev=$(dep "$t" REV)
    load_dep "$t"
    [[ $(dep "$t" REV) == "$rev" ]] || die "deployment/$name changed since the plan (revision $rev -> $(dep "$t" REV)); re-run"
    patch=$(patch_json "$t")
    if [[ $t == api ]]; then log "deployment/$name: API rollout mode '$API_MODE' (strategy/affinity in this patch are permanent)"; fi
    log "+ kubectl -n $NAMESPACE patch deployment $name --type strategic -p '$patch'"
    kctl patch deployment "$name" --type strategic -p "$patch" >&2 || die "patch of deployment/$name was rejected; it was not changed"
    if wait_rollout "$t" && health_check "$t" "$NEW_IMAGE"; then
        note "$t: deployment/$name revision $rev -> $(kctl get deployment "$name" -o 'jsonpath={.metadata.annotations.deployment\.kubernetes\.io/revision}'), healthy on $NEW_IMAGE"
        return 0
    fi
    note "$t: rollout of $NEW_IMAGE FAILED"
    if ((!AUTO_ROLLBACK)); then
        note "$t: left as is (--no-auto-rollback); revert with: $0 --rollback --target $t"
    elif undo "$t"; then
        note "$t: rolled back to revision $rev ($(dep "$t" IMAGE)), healthy"
    else
        note "$t: undo to revision $rev did not come back healthy; check the cluster NOW"
    fi
    if [[ $t == api && $ORDER == "api jobs" ]]; then note "jobs: not touched"; fi
    die "deploy of deployment/$name failed (summary below)"
}

deploy() {
    local t
    if ((REUSE)); then
        NEW_IMAGE=$(dep api IMAGE)
        TAG=$(dep api DTAG)
        TAG=${TAG:-unknown}
        log "--target jobs: deploying the image deployment/$API_NAME runs: $NEW_IMAGE"
        health_check api "$NEW_IMAGE" || die "deployment/$API_NAME is not healthy on its own image; refusing to deploy jobs"
        if [[ $(dep jobs IMAGE) == "$NEW_IMAGE" ]]; then
            log "deployment/$JOBS_NAME already runs $NEW_IMAGE; nothing to do"
            return 0
        fi
        CHANGE_CAUSE="deploy-k3s-v1 $TAG promoted from deployment/$API_NAME by=${USER:-unknown}"
    else
        resolve_tag
        if ((FORCE_JOBS)); then warn "--force-jobs: deploying an image to jobs that deploy/$API_NAME has not proven"; fi
        build_image
        if ((DO_MIGRATE)); then build_migrate_job; fi
    fi

    print_plan
    if ((DRY_RUN)); then
        if ((DO_MIGRATE)); then
            log "migration Job manifest (literal env values redacted):"
            cat "$TMP/job-redacted.json" >&2
            log "server-side dry-run of the migration Job:"
            kctl_in apply --dry-run=server -f - <"$TMP/job.json"
        fi
        dry_run_patches
        note "dry run: nothing was built, pushed, migrated or patched"
        return 0
    fi

    confirm "Proceed with this plan?"
    if ((DO_MIGRATE)); then migrate; fi
    for t in $ORDER; do deploy_target "$t"; done
    if [[ $ORDER == api ]]; then note "jobs: not deployed, deployment/$JOBS_NAME still runs $(dep jobs IMAGE)"; fi
    log "done: $NEW_IMAGE is live on: $ORDER. To revert: $0 --rollback --target $TARGET"
}

rollback() {
    local t name history prev
    for t in $ORDER; do
        name=$(dep "$t" NAME)
        history=$(kctl rollout history deployment/"$name")
        printf '%s\n' "$history"
        prev=$(printf '%s\n' "$history" | awk '$1 ~ /^[0-9]+$/ {p = c; c = $1} END {print p}')
        [[ -n $prev ]] || die "deployment/$name has no previous revision to roll back to"
        set_dep "$t" PREV "$prev"
        # history -o prints the ReplicaSet (or its pod template, depending on kubectl): match either.
        set_dep "$t" PREV_IMAGE "$(kctl rollout history deployment/"$name" --revision="$prev" -o "jsonpath={..containers[?(@.name==\"$CONTAINER\")].image}")"
    done
    log "rollback plan for namespace $NAMESPACE ($(kube_where)), in this order:"
    for t in $ORDER; do
        log "  $t: deployment/$(dep "$t" NAME) revision $(dep "$t" REV) $(dep "$t" IMAGE)"
        log "       -> revision $(dep "$t" PREV) $(dep "$t" PREV_IMAGE)"
    done
    log "  each one is waited for and health-checked before the next."
    log "  check the targets: after an auto-undo, the previous revision is the one that failed."
    if ((DRY_RUN)); then
        for t in $ORDER; do dry "kubectl -n $NAMESPACE rollout undo deployment/$(dep "$t" NAME) --to-revision=$(dep "$t" PREV)"; done
        return 0
    fi
    confirm "Roll back ${ORDER// / then } as listed?"
    for t in $ORDER; do
        name=$(dep "$t" NAME)
        log "+ kubectl -n $NAMESPACE rollout undo deployment/$name --to-revision=$(dep "$t" PREV)"
        kctl rollout undo deployment/"$name" --to-revision="$(dep "$t" PREV)" >&2
        wait_rollout "$t" || die "rollback of deployment/$name did not complete (above)"
        set_dep "$t" PREV_IMAGE "$(kctl get deployment "$name" -o "jsonpath={.spec.template.spec.containers[?(@.name==\"$CONTAINER\")].image}")"
        health_check "$t" "$(dep "$t" PREV_IMAGE)" || die "deployment/$name was rolled back but is not healthy"
        note "$t: deployment/$name rolled back from revision $(dep "$t" REV) to revision $(dep "$t" PREV)'s template, healthy on $(dep "$t" PREV_IMAGE)"
    done
}

if [[ -n ${DEPLOYMENT:-}${NODE_PORT:-} ]]; then
    die "DEPLOYMENT / NODE_PORT are no longer supported (they caused the API outage); use --target api|jobs|all"
fi
while (($#)); do
    case $1 in
        --jar) JAR=${2:?"--jar needs a path"}; shift 2 ;;
        --registry) REGISTRY=${2:?"--registry needs ghcr|ecr|import"}; shift 2 ;;
        --base) BASE=${2:?"--base needs an image"}; shift 2 ;;
        --tag) TAG=${2:?"--tag needs a value"}; shift 2 ;;
        --target) TARGET=${2:?"--target needs api|jobs|all"}; shift 2 ;;
        --skip-migrate) SKIP_MIGRATE=1; shift ;;
        --no-auto-rollback) AUTO_ROLLBACK=0; shift ;;
        --force-jobs) FORCE_JOBS=1; shift ;;
        --ignore-unhealthy) IGNORE_UNHEALTHY=1; shift ;;
        --relax-anti-affinity) RELAX_AA=1; shift ;;
        --allow-unavailable-1) ALLOW_UNAVAIL=1; shift ;;
        --allow-dirty) ALLOW_DIRTY=1; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        --rollback) ROLLBACK=1; shift ;;
        -y | --yes) ASSUME_YES=1; shift ;;
        -h | --help) usage; exit 0 ;;
        *) die "unknown argument: $1 (see --help)" ;;
    esac
done
case $REGISTRY in ghcr | ecr | import) ;; *) die "--registry must be ghcr, ecr or import (got '$REGISTRY')" ;; esac
case $TARGET in
    all) if ((ROLLBACK)); then ORDER="jobs api"; else ORDER="api jobs"; fi ;; # rollback stops the new scheduler first
    api | jobs) ORDER=$TARGET ;;
    *) die "--target must be api, jobs or all (got '$TARGET')" ;;
esac
[[ $MIGRATE_TIMEOUT =~ ^[0-9]+$ ]] || die "MIGRATE_TIMEOUT must be a number of seconds (got '$MIGRATE_TIMEOUT')"
[[ -z $MIGRATE_DB_NAME || $MIGRATE_DB_NAME =~ ^[A-Za-z0-9_-]+$ ]] || die "MIGRATE_DB_NAME must be a bare keyspace name, without @ (got '$MIGRATE_DB_NAME')"
if ((FORCE_JOBS)) && [[ $TARGET != jobs || $ROLLBACK == 1 ]]; then die "--force-jobs only applies to a --target jobs deploy"; fi
if ((!ROLLBACK)) && [[ $TARGET == jobs ]] && ((!FORCE_JOBS)); then
    REUSE=1
    [[ -z $JAR$BASE$TAG ]] || die "--target jobs deploys the image deploy/$API_NAME already runs; --jar/--base/--tag need --force-jobs"
fi
if ((!ROLLBACK && !REUSE)); then
    if ((SKIP_MIGRATE)); then
        warn "--skip-migrate: the first new pod runs Flyway itself; if it fails, schema_version keeps a failed row and no image can start"
    else
        DO_MIGRATE=1
    fi
fi

trap cleanup EXIT
TMP=$(mktemp -d "${TMPDIR:-/tmp}/deploy-k3s-v1.XXXXXX")
preflight
if ((ROLLBACK)); then rollback; else deploy; fi
