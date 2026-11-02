# WP-109 Kubernetes lease experiment

This is an isolated development experiment, not a deployment recipe. It uses an
empty PostgreSQL database, disposable credentials and the `itest` development
profile. It must not be pointed at production data or a shared cluster.

At integration commit `3af8965`, a local kind v1.36.1 cluster with two replicas
and Quarkus Flow 1.2.0 reached PostgreSQL but neither application became ready.
Flow's `PoolController` invokes `Scheduler.newJob()` without selecting an
implementation. The project's db-scheduler 0.0.2 implementation throws
`UnsupportedOperationException: Programmatic job scheduling is not supported by
db-scheduler`. Lease readiness remained down and no leases were created. Thus the
experiment did not reach failover and supplies no two-replica safety evidence.

The [db-scheduler documentation](https://docs.quarkiverse.io/quarkus-db-scheduler/dev/index.html#_programmatic_scheduling)
describes a composite scheduler with an explicit `Scheduled.SIMPLE` selection for
programmatic jobs. The pinned Flow extension does not make that selection. A fix
must preserve database-backed scheduling while running lease coordination on
*each* replica, then repeat the failover and stale-owner tests. A second build
with `quarkus.scheduler.use-composite-scheduler=true` also failed: the actual
stack passed through `CompositeJobDefinition.schedule` into
`DbSchedulerSchedulerImpl.newJob`, where the same exception was raised.

An experimental CDI scheduler bridge, enabled only at build time, selects the
local scheduler for the pinned Flow lease-job names and leaves other jobs on the
existing scheduler. With that bridge and the composite scheduler enabled, two
replicas became ready and acquired distinct member leases. After force-deleting
the holder of member 00, a replacement acquired that lease, restored one waiting
workflow after warmup, and completed it after a test event. A graceful deletion
also cleared its member lease. The database still held the scheduled
`audit-chain-verification` and `identity-grant-expiry` jobs in this run. This
pod test does not prove node failure, network partitions, mixed versions,
duplicate-effect prevention or T12. `verify_failover.py` automates the same
scoped scenario against a prebuilt bridge-enabled image, using a unique cluster
and private kubeconfig and removing that cluster afterward. It also installs the
proposed cancellation schema, enables the test-only reader, stops an owner
container and prevents its replacement from scheduling until the deadline
passes while the other replica stays ready. The replacement then cancels the
workflow without a resume or post-takeover effect, and a receipt is retained.
This deadline case is a single-node kind test, not a node-loss or split-brain proof.
A separate `verify_node_failover.py` run placed the two replicas on separate kind
workers, killed the waiting workflow owner’s worker, and observed its member
lease transfer to a replacement on the surviving worker. That replacement
restored and completed the workflow after an event. This node test does not
cover a deadline, network partition, duplicate-effect prevention or T12.

The Maven profile `wp109-kube-experiment` adds the Kubernetes extension only to
an explicitly selected experimental build. Build the app with that profile and
copy the resulting `apps/app-runtime/target/quarkus-app` directory into this
folder before building the local image named `owa-wp109-kube-experiment:local`.
On a newly created kind cluster with a private kubeconfig, apply `database.yaml`,
wait for `db` to become ready, load the image into kind, and apply `cluster.yaml`.
Collect pod readiness, both pod logs and leases before cleaning up the dedicated
cluster. The image uses a cached Maven/JDK base for the local experiment.

For example, from a clean checkout on a machine with Java 21, Docker, kind and
kubectl, the following uses a dedicated cluster and kubeconfig:

```sh
mvn -B -pl apps/app-runtime -am package -Pwp109-kube-experiment -DskipTests \
  -Dquarkus.scheduler.use-composite-scheduler=true -Dagentic.kube.scheduler-bridge=true
mkdir -p target/wp109-image
cp -R apps/app-runtime/target/quarkus-app target/wp109-image/
cp experiments/wp109-kubernetes/Dockerfile target/wp109-image/
docker build -t owa-wp109-kube-experiment:local target/wp109-image
kind create cluster --name owa-wp109-failover --kubeconfig "$PWD/target/wp109-kubeconfig"
kind load docker-image owa-wp109-kube-experiment:local --name owa-wp109-failover
kubectl --kubeconfig "$PWD/target/wp109-kubeconfig" apply -f experiments/wp109-kubernetes/database.yaml
kubectl --kubeconfig "$PWD/target/wp109-kubeconfig" -n owa-wp109 wait --for=condition=Ready pod/db --timeout=180s
kubectl --kubeconfig "$PWD/target/wp109-kubeconfig" apply -f experiments/wp109-kubernetes/cluster.yaml
kubectl --kubeconfig "$PWD/target/wp109-kubeconfig" -n owa-wp109 get pods,leases
```

For the automated scenario, build and tag the bridge-enabled image as above,
then run `python experiments/wp109-kubernetes/verify_failover.py` for pod and
deadline cases or `python experiments/wp109-kubernetes/verify_node_failover.py`
for the three-node case. Each creates and cleans up a uniquely named kind cluster. For the manual setup, remove only the
dedicated experiment with `kind delete cluster --name owa-wp109-failover`. Do
not reuse its namespace or credentials in a shared environment.

The cluster resources, test profile and embedded credentials are intentionally
isolated and are not a production-ready Kubernetes configuration. The bridge is not enabled in the default build. Production rollout, compatibility
across extension versions, deadline failover and in-flight side-effect fencing
remain open.
