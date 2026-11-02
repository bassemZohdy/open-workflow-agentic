#!/usr/bin/env python3
"""Run the isolated WP-109 Kubernetes smoke test against a prebuilt experiment image."""
import argparse
import json
import re
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXPERIMENT = ROOT / "experiments" / "wp109-kubernetes"
IMAGE = "owa-wp109-kube-experiment:local"
NAMESPACE = "owa-wp109"
TENANT = "wp109-test"


def run(*args, timeout=180, stdin=None):
    return subprocess.run(args, cwd=ROOT, text=True, encoding="utf-8", errors="replace", check=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, input=stdin, timeout=timeout).stdout


def eventually(label, predicate, timeout=120):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            result = predicate()
            if result:
                return result
        except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
            last_error = error
        time.sleep(1)
    raise RuntimeError(f"timed out waiting for {label}: {last_error}")


def api(port, path, *, body=None, scope="oa.inspect"):
    headers = {"X-Tenant": TENANT, "X-Subject": "alice", "X-Scopes": scope}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    request = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=data, headers=headers)
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.load(response)


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default=IMAGE, help="prebuilt local image; must enable the experimental bridge")
    args = parser.parse_args()
    if args.image != IMAGE:
        raise ValueError(f"cluster manifest requires image {IMAGE}")
    run("docker", "image", "inspect", args.image)
    cluster = "owa-wp109-" + uuid.uuid4().hex[:10]
    process = None
    with tempfile.TemporaryDirectory(prefix="owa-wp109-") as temporary:
        kubeconfig = str(Path(temporary) / "kubeconfig")
        kubectl = ["kubectl", "--kubeconfig", kubeconfig, "-n", NAMESPACE]
        owned = False
        try:
            existing = run("kind", "get", "clusters").splitlines()
            if cluster in existing:
                raise RuntimeError("generated cluster name already exists")
            owned = True  # A failed creation can leave a partially created cluster with this unique name.
            print("Creating an isolated kind cluster", flush=True)
            run("kind", "create", "cluster", "--name", cluster, "--kubeconfig", kubeconfig,
                "--image", "kindest/node:v1.36.1", "--wait", "120s", timeout=240)
            print("Loading the application image", flush=True)
            run("kind", "load", "docker-image", args.image, "--name", cluster, timeout=240)
            print("Starting PostgreSQL and the two replicas", flush=True)
            run("kubectl", "--kubeconfig", kubeconfig, "apply", "-f", str(EXPERIMENT / "database.yaml"))
            run(*kubectl, "wait", "--for=condition=Ready", "pod/db", "--timeout=180s", timeout=200)
            run("kubectl", "--kubeconfig", kubeconfig, "apply", "-f", str(EXPERIMENT / "cluster.yaml"))
            run(*kubectl, "rollout", "status", "deployment/workflow", "--timeout=180s", timeout=200)

            def leases():
                items = json.loads(run(*kubectl, "get", "leases", "-o", "json"))["items"]
                return {item["metadata"]["name"]: item.get("spec", {}).get("holderIdentity", "") for item in items}

            def stopped(pod):
                containers = json.loads(run("docker", "exec", cluster + "-control-plane", "crictl", "ps", "-o", "json"))["containers"]
                return not any(item.get("labels", {}).get("io.kubernetes.pod.name") == pod for item in containers)

            members = eventually("two distinct member leases", lambda: (
                m if len(m := {k: v for k, v in leases().items() if k.startswith("flow-pool-member-wp109-") and v}) == 2
                and len(set(m.values())) == 2 else None))
            lease, original = sorted(members.items())[0]
            print("Two replicas acquired distinct member leases", flush=True)

            def forward(pod):
                nonlocal process
                port = free_port()
                process = subprocess.Popen([*kubectl, "port-forward", "pod/" + pod, f"{port}:8080"],
                                           cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                eventually("port forward", lambda: process.poll() is None and api(port, "/q/health/ready").get("status") == "UP")
                return port

            port = forward(original)
            instance = api(port, "/instances", scope="oa.operate", body={
                "definitionId": "agentic/lifecycle-probe", "definitionVersion": "0.1.0",
                "input": {"effectUrl": "http://127.0.0.1:8080/dev/lifecycle/effects", "mode": "ok"},
                "idempotencyKey": "wp109-" + uuid.uuid4().hex,
            })
            instance_id, engine_id = instance["id"], instance["engineInstanceId"]
            if not all(re.fullmatch(r"[0-9A-HJKMNP-TV-Z]{26}", item) for item in (instance_id, engine_id)):
                raise ValueError("unexpected workflow identifiers")
            eventually("workflow waiting", lambda: api(port, "/instances/" + instance_id)["status"] == "WAITING")

            def sql(query):
                return run(*kubectl, "exec", "db", "--", "psql", "-U", "postgres", "-d", "agentic",
                           "-At", "-c", query).strip()

            if sql(f"select applicationid from workflowinstanceentity where instanceid='{engine_id}'") != lease:
                raise RuntimeError("checkpoint is not bound to the selected member lease")
            if stopped(original):
                raise RuntimeError("could not identify the original running container")
            run(*kubectl, "delete", "pod", original, "--force", "--grace-period=0")
            process.terminate(); process.wait(timeout=10); process = None
            eventually("original container to stop", lambda: stopped(original), timeout=20)
            run(*kubectl, "rollout", "status", "deployment/workflow", "--timeout=150s", timeout=170)
            replacement = eventually("original member lease takeover", lambda: (
                holder if (holder := leases().get(lease)) and holder != original else None))
            port = forward(replacement)
            eventually("checkpoint restored after warmup", lambda:
                       sql(f"select resume_epoch from task.instance where id='{instance_id}'") == "1"
                       and api(port, "/instances/" + instance_id)["status"] == "WAITING")
            def deliver_until_complete():
                if api(port, "/instances/" + instance_id)["status"] == "COMPLETED":
                    return True
                api(port, "/dev/lifecycle/events", scope="oa.operate",
                    body={"type": "org.openworkflow.agentic.lifecycle.probe"})
                return False
            eventually("workflow completion", deliver_until_complete)
            if sql(f"select count(*) from task.instance where id='{instance_id}'") != "1":
                raise RuntimeError("unexpected workflow registry count")
            if sql("select count(*) from scheduled_tasks where task_name in "
                   "('audit-chain-verification','identity-grant-expiry')") != "2":
                raise RuntimeError("expected database-backed jobs are missing")
            print("PASS: lease takeover, restored waiting workflow, completion and retained database jobs", flush=True)

            # The proposed schema and candidate reader are deliberately confined to this disposable test cluster.
            process.terminate(); process.wait(timeout=10); process = None
            proposal = (ROOT / "modules/oa-runtime/src/main/resources/db/proposed/V81__pending_cancellation_receipt.sql")
            run(*kubectl, "exec", "-i", "db", "--", "psql", "-U", "postgres", "-d", "agentic",
                "-v", "ON_ERROR_STOP=1", stdin=proposal.read_text(encoding="utf-8"))
            run(*kubectl, "scale", "deployment/workflow", "--replicas=0")
            eventually("old replicas to exit", lambda: not json.loads(
                run(*kubectl, "get", "pods", "-l", "app=workflow", "-o", "json"))["items"])
            run(*kubectl, "set", "env", "deployment/workflow", "AGENTIC_TEST_PENDING_CANCELLATION_READER=true",
                "AGENTIC_RECONCILER_WAIT_DEADLINE=PT20S", "QUARKUS_FLOW_DURABLE_KUBE_LEASE_MEMBER_DURATION=40")
            run(*kubectl, "scale", "deployment/workflow", "--replicas=2")
            run(*kubectl, "rollout", "status", "deployment/workflow", "--timeout=180s", timeout=200)
            members = eventually("two member leases after test-reader rollout", lambda: (
                m if len(m := {k: v for k, v in leases().items() if k.startswith("flow-pool-member-wp109-") and v}) == 2
                and len(set(m.values())) == 2 else None))
            lease, original = sorted(members.items())[0]
            survivor = next(holder for holder in members.values() if holder != original)
            port = forward(original)
            expired = api(port, "/instances", scope="oa.operate", body={
                "definitionId": "agentic/lifecycle-probe", "definitionVersion": "0.1.0",
                "input": {"effectUrl": "http://127.0.0.1:8080/dev/lifecycle/effects", "mode": "ok"},
                "idempotencyKey": "deadline-" + uuid.uuid4().hex,
            })
            expired_id, expired_engine = expired["id"], expired["engineInstanceId"]
            if not all(re.fullmatch(r"[0-9A-HJKMNP-TV-Z]{26}", item) for item in (expired_id, expired_engine)):
                raise ValueError("unexpected deadline workflow identifiers")
            eventually("deadline workflow waiting", lambda: api(port, "/instances/" + expired_id)["status"] == "WAITING")
            if sql(f"select applicationid from workflowinstanceentity where instanceid='{expired_engine}'") != lease:
                raise RuntimeError("deadline checkpoint is not bound to the selected lease")
            deadline_millis = sql(f"select (extract(epoch from wait_deadline)*1000)::bigint from task.instance "
                                  f"where id='{expired_id}' and wait_deadline > clock_timestamp() + interval '3 seconds'")
            if not re.fullmatch(r"[0-9]+", deadline_millis):
                raise RuntimeError("deadline is missing or too close to force-delete the pod")
            if stopped(original):
                raise RuntimeError("could not identify the deadline owner's running container")
            # Prevent a replacement from starting before the absolute deadline while the surviving pod stays live.
            run("kubectl", "--kubeconfig", kubeconfig, "taint", "node", cluster + "-control-plane",
                "owa-wp109-delay=true:NoSchedule")
            run(*kubectl, "delete", "pod", original, "--force", "--grace-period=0")
            process.terminate(); process.wait(timeout=10); process = None
            eventually("deadline owner container to stop", lambda: stopped(original), timeout=20)
            eventually("deadline to pass", lambda: sql(f"select (clock_timestamp() >= "
                       f"to_timestamp({deadline_millis}/1000.0))::text") == "true")
            run(*kubectl, "wait", "--for=condition=Ready", "pod/" + survivor, "--timeout=10s", timeout=15)
            if sql(f"select resume_epoch from task.instance where id='{expired_id}'") != "0":
                raise RuntimeError("deadline workflow resumed while its owner was unavailable")
            run("kubectl", "--kubeconfig", kubeconfig, "taint", "node", cluster + "-control-plane",
                "owa-wp109-delay:NoSchedule-")
            run(*kubectl, "rollout", "status", "deployment/workflow", "--timeout=150s", timeout=170)
            replacement = eventually("deadline member lease takeover", lambda: (
                holder if (holder := leases().get(lease)) and holder != original else None))
            port = forward(replacement)
            def cancellation_observed():
                status = api(port, "/instances/" + expired_id)["status"]
                if status in {"FAILED", "COMPLETED"}:
                    raise RuntimeError("expired workflow reached unexpected terminal status " + status)
                return status == "CANCELLED"
            eventually("expired workflow cancellation", cancellation_observed)
            if sql(f"select resume_epoch from task.instance where id='{expired_id}'") != "0":
                raise RuntimeError("expired workflow resumed")
            if sql(f"select count(*) from task.pending_cancellation_receipt where instance_id='{expired_id}'") != "1":
                raise RuntimeError("committed pending-cancellation receipt is missing")
            replacement_log = run(*kubectl, "logs", replacement)
            if "probe-effect received instance=" + expired_engine in replacement_log:
                raise RuntimeError("expired workflow invoked its effect after takeover")
            print("PASS: deadline expired during pod loss and was cancelled without resume", flush=True)
        except Exception:
            # Preserve only this experiment's pod/lease status and bounded application logs before cleanup.
            if Path(kubeconfig).exists():
                evidence = ROOT / "target" / ("wp109-failure-" + cluster)
                evidence.mkdir(parents=True, exist_ok=True)
                for name, command in (
                    ("objects.json", [*kubectl, "get", "pods,leases", "-o", "json"]),
                    ("workflow.log", [*kubectl, "logs", "-l", "app=workflow", "--all-containers=true",
                                      "--prefix=true", "--tail=300"]),
                ):
                    try:
                        (evidence / name).write_text(run(*command, timeout=20), encoding="utf-8")
                    except (OSError, subprocess.SubprocessError):
                        pass
                print(f"Failure evidence: {evidence}", flush=True)
            raise
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait(timeout=10)
            if owned:
                print("Removing the isolated kind cluster", flush=True)
                run("kind", "delete", "cluster", "--name", cluster, "--kubeconfig", kubeconfig, timeout=180)


if __name__ == "__main__":
    main()
