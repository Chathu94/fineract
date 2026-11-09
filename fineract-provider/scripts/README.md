# fineract-provider/scripts

## Deploying v1 Fineract (API + scheduler) to Orel k3s

`deploy-k3s-v1.sh` builds `fineract-provider.jar`, layers it onto the running image with
`Dockerfile.k3s-v1` (it only replaces `/app/fineract-provider.jar`, so CMD, env, the `jre/lib/ext` jars and
`logback.xml` stay as they are, plus OCI labels `org.opencontainers.image.revision` / `.created`), publishes it as
`linux/amd64` and rolls it out in namespace `fused`:

```
preflight -> build -> push -> migrate Job -> deploy/fineract (API, maxSurge 1 / maxUnavailable 0) -> health
          -> deploy/fineract-jobs (scheduler, Recreate) -> health        any failure: rollout undo + stop
```

1. **Preflight** (read-only). Stops if `deploy/fineract` or `deploy/fineract-jobs` is not fully available, or if
   any of their pods is in `CrashLoopBackOff`. It greps those pods' logs for a Flyway failure and prints the recovery
   steps below. It also refuses a dirty git tree (unless `--allow-dirty`) and a running `fineract-migrate` Job.
2. **Build and push.** The image is tagged `v1-<short sha>-<YYYYMMDD-HHMM>` (local time) and deployed by digest
   (`ghcr`, `ecr`) or by tag (`import`).
3. **Migrate.** A one-off Job, `fineract-migrate-<tag>`, is built from the live pod template of
   `deploy/fineract`. It keeps only the `fineract` container, runs the new image without probes, and has the label
   `app=fineract-migrate`, so no Service sends it traffic. Fineract runs Flyway at startup, so the migration is done
   once the pod's health URL returns 200. The Job is then deleted. If Flyway fails, the pod fails, or the 1200 s
   timeout passes, the script prints the relevant log lines and the recovery steps, keeps the Job (TTL 1 h), and
   exits **without patching anything**.
4. **API.** One strategic patch sets the image, `strategy: RollingUpdate maxSurge 1 / maxUnavailable 0`
   (permanently), `kubernetes.io/change-cause` (tag, sha, branch, user, registry) and the pod annotation
   `evoke.io/deploy-tag`. The script waits for the rollout, then requires every replica to be Ready on the new image
   and NodePort 30200 to return 200 on every node.
5. **Jobs.** Same patch, minus the strategy: it stays `Recreate`, so two schedulers never run. This step only starts
   after step 4 has passed. Its health check uses NodePort 31648.
6. **Failure.** If a rollout or health check fails, the script runs `rollout undo --to-revision <revision recorded
   before the patch>`, waits for it, and stops. If the API fails, jobs is never touched. `--no-auto-rollback`
   leaves the failed deployment as it is.

Why the gating exists: on one deploy, the API was patched with maxUnavailable 1, a healthy pod was killed, and the
new image's migration (V334) failed. Jobs was then deployed with the same image. The failed `schema_version` row
then stopped **every** image, old ones included, from starting.

Prerequisites:

- Docker Desktop with buildx (the default builder is fine), `python3`, and `git`.
- A Java 8 JDK (`/usr/libexec/java_home -v 1.8`), unless you pass `--jar`.
- `~/.ssh/config` hosts `t651` and `k3s1`..`k3s3` (root, `ProxyJump t651`). kubectl runs on `k3s1` over ssh,
  or locally with `KUBECONFIG_MODE=local` / `KUBECONTEXT=<ctx>` if you open the tunnel yourself.
- AWS credentials for ECR in us-east-1: the default base image is pulled from ECR, and `--registry ecr` pushes there.
- For `--registry ghcr` (the default): `gh` logged in with `write:packages` (`gh auth refresh -s write:packages`).
  The `ghcr-pull` secret (user Chathu94, `read:packages`) must be able to read `ghcr.io/evoke-labs/fineract-backend`.
- **Cluster headroom for one extra API pod** (requests 2 CPU / 3Gi). maxSurge 1 starts the new pod before an old
  one stops. The migration Job needs the same resources while it runs. With no room, the pod stays Pending, the
  rollout times out, and the script undoes it.

```bash
fineract-provider/scripts/deploy-k3s-v1.sh --dry-run               # read-only: plan, Job manifest, server-side dry-runs
fineract-provider/scripts/deploy-k3s-v1.sh                         # migrate, then api, then jobs
MIGRATE_DB_NAME=fused_fli fineract-provider/scripts/deploy-k3s-v1.sh   # Flyway via fused_fli@primary
fineract-provider/scripts/deploy-k3s-v1.sh --target api            # API only; jobs keeps its image
fineract-provider/scripts/deploy-k3s-v1.sh --target jobs           # put the API's current image on jobs
fineract-provider/scripts/deploy-k3s-v1.sh --rollback              # undo jobs, then api, health-check each
```

Flags (`--help` also lists the environment overrides):

| flag | meaning |
|---|---|
| `--target all\|api\|jobs` | `all` (default) = api, then jobs. `jobs` alone does not build: it deploys the image `deploy/fineract` already runs, after a health check of the API on it |
| `--force-jobs` | with `--target jobs`: build a new image and deploy it to jobs only (migrate still runs) |
| `--skip-migrate` | no migration Job: the first new pod runs Flyway itself |
| `--no-auto-rollback` | on failure, stop without `rollout undo` |
| `--ignore-unhealthy` | go ahead although preflight found a degraded or crash-looping deployment |
| `--allow-dirty` | build from uncommitted changes; the tag gets `-dirty` and a warning is logged |
| `--jar PATH` | use this jar instead of `./build-jar.sh` (or `./gradlew clean jar copyDeps` when that is missing) |
| `--registry ghcr\|ecr\|import` | push to GHCR (default) or ECR and deploy by digest, or `k3s ctr import` on every node and deploy by tag |
| `--base IMAGE` | base image (default: the current image of `deploy/fineract`) |
| `--tag TAG` | image tag (default `v1-<short sha>-<YYYYMMDD-HHMM>`) |
| `--dry-run` | read-only: preflight, planned order, build/push commands, the Job manifest (env values redacted), and `--dry-run=server` for the Job and every patch |
| `--rollback` | `rollout undo` to the previous revision of each target (all: jobs first, so the new scheduler stops first), wait and health-check each; asks for confirmation |
| `-y`, `--yes` | skip the confirmation prompt |

`DEPLOYMENT` / `NODE_PORT` are no longer accepted. Use `--target`.

### Recovering from a failed migration

Flyway 2.1 (`schema_version`, `outOfOrder=true`) refuses to start while a row with `success = 0` exists. Pods on
**any** image, the old ones too, will not start until that row is gone, so don't restart or roll back pods hoping it
clears. The script never modifies the database. **Any DB change needs Hashan's approval.**

1. On the tenant schema, via the **primary** (vtgate: ``USE `fused_fli@primary` ``):
   `SELECT installed_rank, version, script, success, installed_on FROM schema_version WHERE success = 0;`
2. Check which objects of that migration (`sql/migrations/core_db/V<version>__*.sql`) exist. MySQL DDL is not
   transactional, so it may be half applied.
3. Drop the partial objects, or finish the migration by hand.
4. `DELETE FROM schema_version WHERE success = 0 AND version = '<version>';` (Flyway 2.1 has no `repair`).
   Flyway runs that version again on the next start, so if you finished it by hand, the SQL must be idempotent
   (or agree another way of recording it).
5. Fix the migration SQL if needed, rebuild, and re-run the script.

Notes:

- `MIGRATE_DB_NAME=<keyspace>` sets `EVOKE_SQL_TENANT_DB_NAME_OVERRIDE=<keyspace>@primary` on the Job only. In
  Fineract this variable also replaces the db name of the tenant-store datasource (`DataSourceProperties`), so use
  it only where the tenant list and the tenant schema share that keyspace. Without it, Flyway uses the tenant's
  configured db name through vtgate's default routing, and the script warns about that.
- `--rollback` goes to the revision before the current one. After an auto-undo, that is the revision that
  **failed**, so check the plan it prints before you confirm.
- With `import`, the image exists only on the nodes it was imported into (`NODES`). A new node or image GC means
  `ErrImagePull`: import again, or deploy via `ghcr`.
- The default base is the API's running image. If this script built it, the new jar layer stacks on the old one
  (works, but the image grows). Pass `--base <original ECR image>` to avoid that.
- Every kubectl call is a fresh ssh through t651. `ControlMaster auto` in `~/.ssh/config` makes this faster.
