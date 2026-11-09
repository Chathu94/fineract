# fineract-provider/scripts

## Deploying v1 (fineract) to Orel k3s

`deploy-k3s-v1.sh` builds `fineract-provider.jar` (via `./build-jar.sh`), layers it onto the image
`deploy/fineract` (namespace `fused`) runs today, publishes it as `linux/amd64`, rolls it out and
health-checks every node. `Dockerfile.k3s-v1` only replaces `/app/fineract-provider.jar`, so CMD,
env, the `jre/lib/ext` jars and `logback.xml` stay identical to the running image.

Prerequisites:

- Docker Desktop with buildx (the default builder is fine; images are built `--platform linux/amd64`).
- A Java 8 JDK (`/usr/libexec/java_home -v 1.8`), unless you pass `--jar`.
- `~/.ssh/config` hosts `t651` and `k3s1`..`k3s3` (root, `ProxyJump t651`). kubectl runs on `k3s1`
  over ssh, or locally with `KUBECONFIG_MODE=local` / `KUBECONTEXT=<ctx>` if you open the tunnel yourself.
- AWS credentials for ECR in us-east-1: the default base image is pulled from ECR, and `--registry ecr` pushes there.
- For `--registry ghcr` (default): `gh` logged in with `write:packages` (`gh auth refresh -s write:packages`).
  The `ghcr-pull` secret (user Chathu94, `read:packages`) must be able to read `ghcr.io/evoke-labs/fineract-backend`.

```bash
fineract-provider/scripts/deploy-k3s-v1.sh --dry-run              # read-only preview, nothing built or patched
fineract-provider/scripts/deploy-k3s-v1.sh                        # build jar, push to GHCR, roll out by digest
fineract-provider/scripts/deploy-k3s-v1.sh --jar build/libs/fineract-provider.jar --yes
fineract-provider/scripts/deploy-k3s-v1.sh --registry ecr         # push to ECR instead
fineract-provider/scripts/deploy-k3s-v1.sh --registry import      # no registry: k3s ctr import on every node
fineract-provider/scripts/deploy-k3s-v1.sh --rollback             # rollout undo + health check
```

`--help` lists every flag and environment override.

Notes:

- The Deployment's strategy is RollingUpdate maxSurge 0 / maxUnavailable 1, so 1 of the 3 pods is down
  while each new pod starts. The script does not change the strategy.
- `fineract-jobs` uses the same image and is left alone on purpose. Update it separately if it needs the new jar.
- `ghcr` and `ecr` deploy by digest. For `ghcr`, `imagePullSecrets: ghcr-pull` goes in the same patch as
  the image, so you get a single rollout.
- `import` deploys `ghcr.io/evoke-labs/fineract-backend:<tag>` by tag. That tag only exists on the nodes it
  was imported into (`NODES`), not in GHCR, so every node that can run the pod must have it. A new node or
  image GC means `ErrImagePull`: import again, or deploy via `ghcr`.
- By default the base is the running image. If that image came from this script, the new jar layer sits on
  top of the old one (works, but the image grows), and a node-imported base can't be pulled at all. Pass
  `--base <original ECR image>` (e.g. `…/evoke-labs/fineract-backend:k3s-obs-382a53d`) in those cases.
- If the rollout fails, the script prints pods and events and stops. It only undoes the rollout itself with
  `--auto-rollback`; otherwise run `--rollback`. The tag is recorded as `kubernetes.io/change-cause`, which
  shows up in `kubectl rollout history`.
- Every kubectl call is a fresh ssh through t651. `ControlMaster auto` in `~/.ssh/config` makes this faster.
