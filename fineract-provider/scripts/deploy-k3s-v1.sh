#!/usr/bin/env bash
# Builds fineract-provider.jar, bakes it into the image deploy/fineract runs today
# (Dockerfile.k3s-v1 swaps only the jar), publishes it and rolls it out on the Orel k3s
# cluster, then health-checks it. See README.md next to this script, or --help.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DOCKERFILE="$SCRIPT_DIR/Dockerfile.k3s-v1"

NAMESPACE="${NAMESPACE:-fused}"
DEPLOYMENT="${DEPLOYMENT:-fineract}"
CONTAINER="${CONTAINER:-fineract}"
SSH_HOST="${SSH_HOST:-k3s1}"
NODES="${NODES:-k3s1 k3s2 k3s3}"
GHCR_REPO="${GHCR_REPO:-ghcr.io/evoke-labs/fineract-backend}"
ECR_REPO="${ECR_REPO:-269280350252.dkr.ecr.us-east-1.amazonaws.com/evoke-labs/fineract-backend}"
AWS_REGION="${AWS_REGION:-us-east-1}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-15m}"
NODE_IPS="${NODE_IPS:-172.16.183.35 172.16.50.8 172.16.44.22}"
NODE_PORT=30200
HEALTH_PATH="/fineract-provider/api/v1/evoke/health?tenantIdentifier=default"
PULL_SECRET=ghcr-pull
TAG_RE='^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$'

JAR="" REGISTRY=ghcr BASE="" TAG="" DRY_RUN=0 ROLLBACK=0 AUTO_ROLLBACK=0 ASSUME_YES=0
LOGGED_IN=""

usage() {
    cat <<EOF
Usage: $0 [options]

Build fineract-provider.jar, layer it onto the current image of deploy/$DEPLOYMENT
(namespace $NAMESPACE) with Dockerfile.k3s-v1, publish it as linux/amd64, roll it out
and health-check every node. deploy/fineract-jobs is never touched.

Options:
  --jar PATH        use this jar instead of running ./build-jar.sh (needs a Java 8 JDK)
  --registry R      ghcr (default) | ecr | import
                      ghcr    push to $GHCR_REPO, deploy by digest and add
                              imagePullSecrets $PULL_SECRET in the same patch. The gh token
                              needs write:packages:  gh auth refresh -s write:packages
                      ecr     push to $ECR_REPO, deploy by digest (aws creds)
                      import  no registry: docker save | k3s ctr images import on every node
                              in NODES, deploy by tag (the tag must exist on all nodes)
  --base IMAGE      base image (default: current image of deploy/$DEPLOYMENT)
  --tag TAG         image tag (default: v1-<git short sha>[-dirty]-<UTC YYYYMMDDHHMMSS>)
  --dry-run         read-only: preflight, resolve base/tag, print the build/push/patch
                    commands and show the patched image via kubectl patch --dry-run=server
  --rollback        kubectl rollout undo to the previous revision, wait, health check
  --auto-rollback   undo automatically when the rollout fails (default: stop and advise)
  -y, --yes         skip the confirmation prompt
  -h, --help        show this help

Environment overrides (current values):
  NAMESPACE=$NAMESPACE DEPLOYMENT=$DEPLOYMENT CONTAINER=$CONTAINER
  SSH_HOST=$SSH_HOST NODES="$NODES" AWS_REGION=$AWS_REGION ROLLOUT_TIMEOUT=$ROLLOUT_TIMEOUT
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
kctl() {
    if local_kubectl; then
        kubectl ${KUBECONTEXT:+--context "$KUBECONTEXT"} -n "$NAMESPACE" "$@"
    else
        on "$SSH_HOST" kubectl -n "$NAMESPACE" "$@"
    fi
}
dep_image() { kctl get deployment "$DEPLOYMENT" -o "jsonpath={.spec.template.spec.containers[?(@.name==\"$CONTAINER\")].image}"; }

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

preflight() {
    need ssh
    if local_kubectl; then need kubectl; fi
    if ((!ROLLBACK)); then
        need docker
        docker info >/dev/null 2>&1 || die "docker daemon not reachable; start Docker Desktop"
        docker buildx version >/dev/null 2>&1 || die "docker buildx not available"
        log "buildx builder: $(docker buildx inspect 2>/dev/null | awk '/^Name:/ && !n {n=$2} /^Driver:/ && !d {d=$2} END {print n " (driver " d ")"}')"
    fi

    ssh -n -o ConnectTimeout=15 "$SSH_HOST" true || die "cannot ssh to $SSH_HOST (needs the ~/.ssh/config entry via t651)"
    log "ssh $SSH_HOST: ok"

    local info
    info=$(kctl get deployment "$DEPLOYMENT" -o "jsonpath={.spec.replicas}|{.spec.selector.matchLabels}|{.spec.template.spec.containers[?(@.name==\"$CONTAINER\")].image}") ||
        die "cannot read deployment/$DEPLOYMENT in $NAMESPACE ($(kube_where))"
    IFS='|' read -r REPLICAS SELECTOR CURRENT_IMAGE <<<"$info"
    SELECTOR=$(printf '%s' "$SELECTOR" | tr -d '{}"' | tr : =) # {"app":"x"} -> app=x
    [[ -n $CURRENT_IMAGE ]] || die "container '$CONTAINER' not found in deployment/$DEPLOYMENT"
    log "deployment/$DEPLOYMENT ($(kube_where)): $REPLICAS replicas, selector $SELECTOR"
    if ((ROLLBACK)); then return 0; fi

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
    log "waiting for rollout of deployment/$DEPLOYMENT (timeout $ROLLOUT_TIMEOUT)"
    if kctl rollout status deployment/"$DEPLOYMENT" --timeout="$ROLLOUT_TIMEOUT"; then return 0; fi
    warn "rollout did not complete; pods and recent events:"
    kctl get pods -l "$SELECTOR" -o wide || true
    kctl get events --sort-by=.lastTimestamp | grep -F "$DEPLOYMENT" | tail -n 25 || true
    return 1
}

health_check() {
    local want=$1 bad=0 n=0 pods name ready image deleting ip code
    log "health check: $REPLICAS pods ready on $want"
    pods=$(kctl get pods -l "$SELECTOR" -o "jsonpath={range .items[*]}{.metadata.name}|{.status.containerStatuses[?(@.name==\"$CONTAINER\")].ready}|{.spec.containers[?(@.name==\"$CONTAINER\")].image}|{.metadata.deletionTimestamp}{\"\\n\"}{end}")
    while IFS='|' read -r name ready image deleting; do
        if [[ -z $name || -n $deleting ]]; then continue; fi
        n=$((n + 1))
        if [[ $ready == true && $image == "$want" ]]; then
            log "  ok   pod $name"
        else
            log "  FAIL pod $name ready=$ready image=$image"
            bad=1
        fi
    done <<<"$pods"
    if [[ $n != "$REPLICAS" ]]; then
        log "  FAIL expected $REPLICAS running pods, found $n"
        bad=1
    fi

    for ip in $NODE_IPS; do
        code=$(on "$SSH_HOST" curl -sk -o /dev/null -w '%{http_code}' --max-time 20 "https://$ip:$NODE_PORT$HEALTH_PATH") || true
        if [[ $code == 200 ]]; then
            log "  ok   https://$ip:$NODE_PORT -> 200"
        else
            log "  FAIL https://$ip:$NODE_PORT -> ${code:-no response}"
            bad=1
        fi
    done
    if ((bad)); then die "health check failed; to revert run: $0 --rollback"; fi
    log "healthy"
}

patch_json() {
    local secrets=""
    if [[ $REGISTRY == ghcr ]]; then secrets="\"imagePullSecrets\":[{\"name\":\"$PULL_SECRET\"}],"; fi
    printf '{"metadata":{"annotations":{"kubernetes.io/change-cause":"deploy-k3s-v1.sh %s (%s)"}},"spec":{"template":{"spec":{%s"containers":[{"name":"%s","image":"%s"}]}}}}' \
        "$TAG" "$REGISTRY" "$secrets" "$CONTAINER" "$1"
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

deploy() {
    local repo out ref digest patch sha dirty=""
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

    BASE=${BASE:-$CURRENT_IMAGE}
    case $BASE in "$GHCR_REPO"[@:]* | "$ECR_REPO"@*)
        warn "base $BASE was built by this script: the new jar layer stacks on the old one (works, image grows)," \
            "and a node-imported base is in no registry. Prefer --base <original ECR image>." ;;
    esac
    if [[ -z $TAG ]]; then
        sha=$(git -C "$REPO_ROOT" rev-parse --short HEAD)
        if [[ -n $(git -C "$REPO_ROOT" status --porcelain) ]]; then dirty=-dirty; fi
        TAG="v1-$sha$dirty-$(date -u +%Y%m%d%H%M%S)"
    fi
    [[ $TAG =~ $TAG_RE ]] || die "invalid image tag: $TAG"
    ref="$repo:$TAG"
    log "base: $BASE"
    log "tag:  $TAG"

    registry_login "${BASE%%/*}"
    if [[ $REGISTRY != import ]]; then registry_login "${repo%%/*}"; fi

    TMP=$(mktemp -d "${TMPDIR:-/tmp}/deploy-k3s-v1.XXXXXX")
    trap 'rm -rf "$TMP"' EXIT
    mkdir "$TMP/ctx"
    if ((!DRY_RUN)); then cp "$JAR" "$TMP/ctx/fineract-provider.jar"; fi
    if [[ $REGISTRY == import ]]; then out=--load; else out=--push; fi
    run docker buildx build --platform linux/amd64 --provenance=false \
        --build-arg "BASE_IMAGE=$BASE" -f "$DOCKERFILE" -t "$ref" "$out" "$TMP/ctx" ||
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
    fi

    patch=$(patch_json "$NEW_IMAGE")
    log "current image: $CURRENT_IMAGE"
    log "new image:     $NEW_IMAGE"
    if ((DRY_RUN)); then
        dry "kubectl -n $NAMESPACE patch deployment $DEPLOYMENT --type strategic -p '$patch'  # $(kube_where)"
        log "server-side dry-run of that patch:"
        kctl patch deployment "$DEPLOYMENT" --type strategic -p "$patch" --dry-run=server -o "jsonpath=  image:            {.spec.template.spec.containers[?(@.name==\"$CONTAINER\")].image}{\"\\n\"}  imagePullSecrets: {.spec.template.spec.imagePullSecrets[*].name}{\"\\n\"}  change-cause:     {.metadata.annotations.kubernetes\\.io/change-cause}{\"\\n\"}"
        log "dry run complete; nothing was built, pushed, imported or patched"
        return 0
    fi

    confirm "Roll out $NEW_IMAGE to $NAMESPACE/$DEPLOYMENT ($REPLICAS pods)?"
    log "+ kubectl -n $NAMESPACE patch deployment $DEPLOYMENT --type strategic -p '$patch'"
    kctl patch deployment "$DEPLOYMENT" --type strategic -p "$patch"
    if ! wait_rollout; then
        if ((AUTO_ROLLBACK)); then
            warn "--auto-rollback: kubectl rollout undo deployment/$DEPLOYMENT"
            kctl rollout undo deployment/"$DEPLOYMENT"
            kctl rollout status deployment/"$DEPLOYMENT" --timeout="$ROLLOUT_TIMEOUT" || true
            die "rollout of $NEW_IMAGE failed and was rolled back"
        fi
        die "rollout of $NEW_IMAGE failed; deployment left as is. Investigate, or revert with: $0 --rollback"
    fi
    health_check "$NEW_IMAGE"
    log "done: $NEW_IMAGE is live. To revert: $0 --rollback"
}

rollback() {
    local history prev target
    history=$(kctl rollout history deployment/"$DEPLOYMENT")
    printf '%s\n' "$history"
    prev=$(printf '%s\n' "$history" | awk '$1 ~ /^[0-9]+$/ {p = c; c = $1} END {print p}')
    [[ -n $prev ]] || die "no previous revision to roll back to"
    # history -o prints the ReplicaSet (or its pod template, depending on kubectl): match either.
    target=$(kctl rollout history deployment/"$DEPLOYMENT" --revision="$prev" -o "jsonpath={..containers[?(@.name==\"$CONTAINER\")].image}")
    log "current image:          $CURRENT_IMAGE"
    log "rollback to revision $prev: $target"
    if ((DRY_RUN)); then
        dry "kubectl -n $NAMESPACE rollout undo deployment/$DEPLOYMENT --to-revision=$prev  # $(kube_where)"
        return 0
    fi
    confirm "Roll $NAMESPACE/$DEPLOYMENT back to revision $prev?"
    kctl rollout undo deployment/"$DEPLOYMENT" --to-revision="$prev"
    wait_rollout || die "rollback did not complete (see above)"
    health_check "$(dep_image)"
}

while (($#)); do
    case $1 in
        --jar) JAR=${2:?"--jar needs a path"}; shift 2 ;;
        --registry) REGISTRY=${2:?"--registry needs ghcr|ecr|import"}; shift 2 ;;
        --base) BASE=${2:?"--base needs an image"}; shift 2 ;;
        --tag) TAG=${2:?"--tag needs a value"}; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --rollback) ROLLBACK=1; shift ;;
        --auto-rollback) AUTO_ROLLBACK=1; shift ;;
        -y | --yes) ASSUME_YES=1; shift ;;
        -h | --help) usage; exit 0 ;;
        *) die "unknown argument: $1 (see --help)" ;;
    esac
done
case $REGISTRY in ghcr | ecr | import) ;; *) die "--registry must be ghcr, ecr or import (got '$REGISTRY')" ;; esac

preflight
if ((ROLLBACK)); then rollback; else deploy; fi
