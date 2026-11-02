#!/usr/bin/env python3
"""Test a waiting workflow after loss of its kind worker node (disposable cluster only)."""
import argparse
import json
import re
import subprocess
import tempfile
import uuid
from pathlib import Path

from verify_failover import EXPERIMENT, IMAGE, NAMESPACE, ROOT, api, eventually, free_port, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--deadline", action="store_true", help="test expiry with the proposed schema and test-only reader")
    args = parser.parse_args()
    run("docker", "image", "inspect", IMAGE)
    cluster = "owa-node-" + uuid.uuid4().hex[:10]
    control, workers = cluster + "-control-plane", [cluster + "-worker", cluster + "-worker2"]
    forwarder = None
    owned = False
    with tempfile.TemporaryDirectory(prefix="owa-node-") as temp:
        kubeconfig = str(Path(temp) / "kubeconfig")
        config = Path(temp) / "kind.yaml"
        config.write_text("kind: Cluster\napiVersion: kind.x-k8s.io/v1alpha4\nnodes:\n"
                          "- role: control-plane\n- role: worker\n- role: worker\n", encoding="utf-8")
        kubectl = ["kubectl", "--kubeconfig", kubeconfig, "-n", NAMESPACE]
        try:
            if cluster in run("kind", "get", "clusters").splitlines():
                raise RuntimeError("generated cluster name already exists")
            owned = True
            print("Creating an isolated three-node kind cluster", flush=True)
            run("kind", "create", "cluster", "--name", cluster, "--kubeconfig", kubeconfig,
                "--image", "kindest/node:v1.36.1", "--config", str(config), "--wait", "180s", timeout=300)
            for node in workers:
                run("kubectl", "--kubeconfig", kubeconfig, "label", "node", node, "owa-wp109-worker=true")
            print("Loading application image onto the worker nodes", flush=True)
            run("kind", "load", "docker-image", IMAGE, "--name", cluster, "--nodes", ",".join(workers), timeout=360)
            database = (EXPERIMENT / "database.yaml").read_text(encoding="utf-8")
            before = "spec:\n  containers:"
            if database.count(before) != 1:
                raise RuntimeError("unexpected database manifest")
            database = database.replace(before, "spec:\n  nodeName: " + control + "\n  containers:")
            run("kubectl", "--kubeconfig", kubeconfig, "apply", "-f", "-", stdin=database)
            run(*kubectl, "wait", "--for=condition=Ready", "pod/db", "--timeout=180s", timeout=200)

            deployment = (EXPERIMENT / "cluster.yaml").read_text(encoding="utf-8")
            before = "      serviceAccountName: workflow\n"
            if deployment.count(before) != 1:
                raise RuntimeError("unexpected workflow manifest")
            scheduling = """      nodeSelector: { owa-wp109-worker: 'true' }
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
          - weight: 100
            podAffinityTerm:
              labelSelector: { matchLabels: { app: workflow } }
              topologyKey: kubernetes.io/hostname
      tolerations:
      - { key: node.kubernetes.io/not-ready, operator: Exists, effect: NoExecute, tolerationSeconds: 5 }
      - { key: node.kubernetes.io/unreachable, operator: Exists, effect: NoExecute, tolerationSeconds: 5 }
"""
            deployment = deployment.replace(before, before + scheduling)
            if args.deadline:
                before = "        - { name: AGENTIC_RECONCILER_INTERVAL, value: PT2S }\n"
                if deployment.count(before) != 1:
                    raise RuntimeError("unexpected reconciler configuration")
                deployment = deployment.replace(before, before +
                    "        - { name: AGENTIC_RECONCILER_WAIT_DEADLINE, value: PT20S }\n"
                    "        - { name: AGENTIC_TEST_PENDING_CANCELLATION_READER, value: 'true' }\n")
            run("kubectl", "--kubeconfig", kubeconfig, "apply", "-f", "-", stdin=deployment)
            run(*kubectl, "rollout", "status", "deployment/workflow", "--timeout=180s", timeout=200)

            def pods():
                return json.loads(run(*kubectl, "get", "pods", "-l", "app=workflow", "-o", "json"))["items"]

            initial = pods()
            if len(initial) != 2 or {p["spec"]["nodeName"] for p in initial} != set(workers):
                raise RuntimeError("the two ready replicas were not placed on separate workers")
            if args.deadline:
                proposal = ROOT / "modules/oa-runtime/src/main/resources/db/proposed/V81__pending_cancellation_receipt.sql"
                run(*kubectl, "exec", "-i", "db", "--", "psql", "-U", "postgres", "-d", "agentic",
                    "-v", "ON_ERROR_STOP=1", stdin=proposal.read_text(encoding="utf-8"))
            original = initial[0]["metadata"]["name"]
            survivor = initial[1]["metadata"]["name"]
            lost_node = initial[0]["spec"]["nodeName"]
            remaining_node = next(node for node in workers if node != lost_node)

            def leases():
                items = json.loads(run(*kubectl, "get", "leases", "-o", "json"))["items"]
                return {item["metadata"]["name"]: item.get("spec", {}).get("holderIdentity", "") for item in items}

            def lease_for(pod):
                return next((name for name, holder in leases().items()
                             if name.startswith("flow-pool-member-wp109-") and holder == pod), None)

            lease = eventually("original member lease", lambda: lease_for(original))

            def forward(pod):
                nonlocal forwarder
                port = free_port()
                forwarder = subprocess.Popen([*kubectl, "port-forward", "pod/" + pod, f"{port}:8080"],
                                            cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                eventually("ready port forward", lambda: forwarder.poll() is None and api(port, "/q/health/ready").get("status") == "UP")
                return port

            port = forward(original)
            instance = api(port, "/instances", scope="oa.operate", body={
                "definitionId": "agentic/lifecycle-probe", "definitionVersion": "0.1.0",
                "input": {"effectUrl": "http://127.0.0.1:8080/dev/lifecycle/effects", "mode": "ok"},
                "idempotencyKey": "node-" + uuid.uuid4().hex,
            })
            instance_id, engine_id = instance["id"], instance["engineInstanceId"]
            if not all(re.fullmatch(r"[0-9A-HJKMNP-TV-Z]{26}", item) for item in (instance_id, engine_id)):
                raise ValueError("unexpected workflow identifiers")
            eventually("workflow waiting", lambda: api(port, "/instances/" + instance_id)["status"] == "WAITING")

            def sql(query):
                return run(*kubectl, "exec", "db", "--", "psql", "-U", "postgres", "-d", "agentic",
                           "-At", "-c", query).strip()

            if sql(f"select applicationid from workflowinstanceentity where instanceid='{engine_id}'") != lease:
                raise RuntimeError("checkpoint is not bound to the original lease")
            deadline_millis = None
            if args.deadline:
                deadline_millis = sql(f"select (extract(epoch from wait_deadline)*1000)::bigint from task.instance "
                                      f"where id='{instance_id}' and wait_deadline > clock_timestamp() + interval '3 seconds'")
                if not re.fullmatch(r"[0-9]+", deadline_millis):
                    raise RuntimeError("deadline is missing or too close to stop the worker")
            print("Stopping the workflow owner's worker node", flush=True)
            run("docker", "kill", lost_node, timeout=30)
            forwarder.terminate(); forwarder.wait(timeout=10); forwarder = None
            if args.deadline:
                eventually("original deadline to pass", lambda: sql(f"select (clock_timestamp() >= "
                           f"to_timestamp({deadline_millis}/1000.0))::text") == "true")
                run(*kubectl, "wait", "--for=condition=Ready", "pod/" + survivor, "--timeout=10s", timeout=15)
                if sql(f"select resume_epoch from task.instance where id='{instance_id}'") != "0":
                    raise RuntimeError("expired workflow resumed before the deadline check")
            replacement = eventually("replacement on the surviving worker", lambda: next(
                (p["metadata"]["name"] for p in pods() if p["metadata"]["name"] != original
                 and p["spec"].get("nodeName") == remaining_node and lease_for(p["metadata"]["name"]) == lease), None),
                timeout=300)
            port = forward(replacement)
            if args.deadline:
                def cancelled():
                    status = api(port, "/instances/" + instance_id)["status"]
                    if status in {"FAILED", "COMPLETED"}:
                        raise RuntimeError("expired workflow reached unexpected terminal status " + status)
                    return status == "CANCELLED"
                eventually("expired workflow cancellation after node loss", cancelled, timeout=180)
                if sql(f"select resume_epoch from task.instance where id='{instance_id}'") != "0":
                    raise RuntimeError("expired workflow resumed after node loss")
                if sql(f"select count(*) from task.pending_cancellation_receipt where instance_id='{instance_id}'") != "1":
                    raise RuntimeError("committed cancellation receipt is missing")
                if "probe-effect received instance=" + engine_id in run(*kubectl, "logs", replacement):
                    raise RuntimeError("expired workflow invoked its effect after node loss")
                print("PASS: deadline expired during worker loss and was cancelled without resume", flush=True)
                return
            eventually("workflow restored on the surviving worker", lambda:
                       sql(f"select resume_epoch from task.instance where id='{instance_id}'") == "1"
                       and api(port, "/instances/" + instance_id)["status"] == "WAITING", timeout=180)

            def finish():
                if api(port, "/instances/" + instance_id)["status"] == "COMPLETED":
                    return True
                api(port, "/dev/lifecycle/events", scope="oa.operate",
                    body={"type": "org.openworkflow.agentic.lifecycle.probe"})
                return False

            eventually("workflow completion", finish)
            print("PASS: worker-node loss, member lease takeover, workflow restore and completion", flush=True)
        except Exception:
            if Path(kubeconfig).exists():
                evidence = ROOT / "target" / ("wp109-node-failure-" + cluster)
                evidence.mkdir(parents=True, exist_ok=True)
                for name, command in (
                    ("objects.json", [*kubectl, "get", "pods,leases,nodes", "-o", "json"]),
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
            if forwarder is not None and forwarder.poll() is None:
                forwarder.terminate()
                try:
                    forwarder.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    forwarder.kill(); forwarder.wait(timeout=10)
            if owned:
                print("Removing the isolated kind cluster", flush=True)
                run("kind", "delete", "cluster", "--name", cluster, "--kubeconfig", kubeconfig, timeout=180)


if __name__ == "__main__":
    main()
