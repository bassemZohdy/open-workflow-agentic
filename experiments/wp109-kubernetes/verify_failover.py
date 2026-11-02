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


def run(*args, timeout=180):
    return subprocess.run(args, cwd=ROOT, text=True, encoding="utf-8", errors="replace", check=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=timeout).stdout


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
            run(*kubectl, "delete", "pod", original, "--force", "--grace-period=0")
            process.terminate(); process.wait(timeout=10); process = None
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
