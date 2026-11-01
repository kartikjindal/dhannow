#!/usr/bin/env bash
# Read-only: list which /dhannow/<env>/ parameters from the Terraform inventory are missing.
#   bash infra/scripts/ssm_check.sh staging|prod
# Needs terraform state access (for the inventory) and AWS credentials for that account
# (ssm:GetParameters). Never prints a value. Exit 1 if a required one is missing.
set -euo pipefail
env="${1:?usage: ssm_check.sh staging|prod}"
here="$(cd "$(dirname "$0")/.." && pwd)"
inventory="$(terraform -chdir="$here/terraform/envs/$env" output -json ssm_parameters)"

python3 - "$env" "$inventory" <<'PY'
import json, subprocess, sys
env, inv = sys.argv[1], json.loads(sys.argv[2])
names = sorted(inv)
missing = set()
for i in range(0, len(names), 10):
    batch = names[i:i + 10]
    out = subprocess.run(
        ["aws", "ssm", "get-parameters", "--names", *batch, "--query", "InvalidParameters", "--output", "json"],
        check=True, capture_output=True, text=True).stdout
    missing.update(json.loads(out))
bad = 0
for n in names:
    req = inv[n]["required"]
    need = req == "always" or (req == "prod" and env == "prod")
    state = "missing" if n in missing else "set"
    if n in missing and need:
        bad += 1
    if n in missing or need:
        print(f"{state:8} {req:8} {'secure' if inv[n]['secure'] else 'plain ':6} {n}  {inv[n]['note']}")
print(f"{bad} required parameter(s) missing for {env}")
sys.exit(1 if bad else 0)
PY
