# OpenObserve production stack — reproducible deployment

Deploy a production-grade OpenObserve stack on a **clean Linux host**:

- **RustFS** — S3-compatible object storage (stream/Parquet data)
- **PostgreSQL 16** — metadata / file-list store
- **NATS** (JetStream) — cluster coordinator for OpenObserve cluster mode
- **OpenObserve** — cluster mode (`ZO_LOCAL_MODE=false`, role `All`)
- **demo-observability** *(optional)* — Java app that exports **logs, metrics and
  traces** to organization **`VUMSLegend`** (default stream **`default`**)

Everything is wired through a single Podman user-defined network (`o2stack`)
so containers resolve each other by name.

## What a "clean host" needs

- Rocky / Alma / RHEL / Fedora (or other dnf-based distro) with:
  - `sudo` for the current user (playbook uses `become: true`)
  - the `containers.podman` Ansible collection this project pins
- Ansible (ansible-core) installed **on the controller** (can be the same box).
- Internet access: pulls images, downloads the OTel Java agent from GitHub.

The playbook **installs Podman and starts `podman.socket` itself**, so on a
fresh machine the only manual prerequisite is Ansible + sudo.

## Layout

```
openobserve-deploy/
├── ansible.cfg
├── inventory.ini            # localhost by default; edit for a remote host
├── requirements.yml         # containers.podman collection
├── group_vars/
│   └── all.yml              # all settings + credential defaults
├── vault-example/
│   └── vault.yml            # template for real secrets (encrypt → group_vars/)
├── demo-app/
│   ├── demo-app-tasks.yml   # included when deploy_demo_app=true
│   └── demo-observability/  # the OTel Java sample app (builds a WAR)
└── stack_prod.yml           # main playbook
```

## 1. Install the collection (one time, on the controller)

```bash
cd openobserve-deploy
ansible-galaxy collection install -r requirements.yml
```

> If installing globally instead of locally, drop the `collections_path` line
> from `ansible.cfg` and run `sudo ansible-galaxy collection install
> -r requirements.yml`.

## 2. Configure

### Credentials (optional, recommended beyond a local trial)

Defaults live in `group_vars/all.yml` and are fine for a throwaway run:

| Variable | Default |
| --- | --- |
| `openobserve_root_email` | `admin@example.com` |
| `openobserve_root_password` | `Complexpass#123` |
| `rustfs_secret_key` | `ChangeThis-RustFS-Secret-2026!` |
| `postgres_password` | `ChangeThis-Postgres-Secret-2026!` |
| `openobserve_org` | `VUMSLegend` |
| `openobserve_bucket` | `openobserve` |

To keep them out of the repo, use ansible-vault:

```bash
cp vault-example/vault.yml group_vars/vault.yml
ansible-vault encrypt group_vars/vault.yml     # set a vault password
# edit group_vars/vault.yml (unencrypted) -> ansible-vault edits + re-encrypt
```

Then run every playbook with `--ask-vault-pass` (or set `ANSIBLE_VAULT_PASSWORD_FILE`).

### Target host

Default inventory deploys to `localhost`. For a **remote clean host**, edit
`inventory.ini`:

```ini
[all]
o2-host ansible_host=<IP> ansible_user=<user> ansible_become=true
```

and override `openobserve_host_from_container` if the demo app should reach a
different OpenObserve endpoint (Podman's bridge gateway `10.88.0.1` is correct
when OpenObserve runs on the same host Podman builds the demo image on).

## 3. Deploy the stack

```bash
ansible-playbook -i inventory.ini stack_prod.yml
```

To **also deploy the demo telemetry generator** into `VUMSLegend`:

```bash
ansible-playbook -i inventory.ini stack_prod.yml -e deploy_demo_app=true
```

> **Note on first paint time:** OpenObserve buffers small batches in memory and
> pushes them to S3 after the configured merge/retention thresholds (default
> ~10 minutes). New streams (esp. metrics/traces) can take several minutes to
> appear in the UI. Keep the demo app generating traffic.

## 4. Verify

Open the UI: `http://<host>:5080` — log in with
`admin@example.com` / `Complexpass#123`, then switch to org **`VUMSLegend`**.

### Ingestion endpoints (bound to `0.0.0.0`, reachable from outside the host)

| Protocol | Endpoint | Use |
| --- | --- | --- |
| HTTP (Otlp) | `http://<host>:5080/api/<org>/v1/logs` | logs |
| HTTP (Otlp) | `http://<host>:5080/api/<org>/v1/metrics` | metrics |
| HTTP (Otlp) | `http://<host>:5080/api/<org>/v1/traces` | traces |
| gRPC (Otlp) | `http://<host>:5081` | all signals (OTLP-gRPC) |
| Prometheus | `http://<host>:5080/api/<org>/prometheus` | metrics query |
| JSON ingest | `http://<host>:5080/api/<org>/default/_json` | bulk log write |

Both HTTP (5080) and gRPC (5081) are published on **`0.0.0.0`**, so any OTel
SDK / collector on the network can push directly without a sidecar. RustFS S3
(9000/9001) is also bound to `0.0.0.0`. PostgreSQL (5432) stays internal-only
by default (metadata is consumed by OpenObserve itself); publish it if you need
external tooling.

Point an OTel exporter (e.g. the OTel Java agent) at:

```
OTEL_EXPORTER_OTLP_ENDPOINT=http://<host>:5080/api/VUMSLegend
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic <base64(user:pass)>
```

Generate traffic (with the demo app):

```bash
curl "http://<host>:8080/demo-observability/load?count=300"
```

APIs (Basic auth `admin@example.com:Complexpass#123`):

```bash
# Logs
curl -u 'admin@example.com:Complexpass#123' -H 'Content-Type: application/json' \
  -X POST 'http://<host>:5080/api/VUMSLegend/_search?type=logs' \
  -d '{"query":{"sql":"select count(*) as c from \"default\"","start_time":<start_us>,"end_time":<end_us>,"size":1}}'

# Traces
curl -u 'admin@example.com:Complexpass#123' -H 'Content-Type: application/json' \
  -X POST 'http://<host>:5080/api/VUMSLegend/_search?type=traces' \
  -d '{"query":{"sql":"select * from \"default\" order by _timestamp desc limit 5","start_time":<start_us>,"end_time":<end_us>,"size":5}}'

# Metrics (Prometheus-compatible query API)
curl -u 'admin@example.com:Complexpass#123' \
  -X POST 'http://<host>:5080/api/VUMSLegend/prometheus/api/v1/query' \
  --data-urlencode 'query=demo_requests_total'
```

Data on disk:

```bash
sudo podman ps                       # rustfs, postgres, nats, openobserve
sudo podman exec postgres psql -U openobserve -d openobserve \
  -c "SELECT org,stream,records FROM public.stream_stats WHERE org='VUMSLegend'"

# Parquet / index really landed in RustFS (S3)
AWS_ACCESS_KEY_ID=openobserve AWS_SECRET_ACCESS_KEY='...' \
  aws --endpoint-url http://127.0.0.1:9000 s3 ls s3://openobserve/files/VUMSLegend/ --recursive
```

## Troubleshooting

- **Streams not showing in UI**: OpenObserve pushes small batches to S3 only
  after size/retention thresholds; wait a few minutes and keep traffic flowing.
- **`InvalidBucketName`**: bucket names in RustFS cannot contain `_`. The
  default bucket is `openobserve` (valid). Do not use underscores in
  `openobserve_bucket`.
- **NATS crash `replicas > 1 not supported in non-clustered mode`**:
  keep `ZO_NATS_REPLICAS: "1"` (single-node install).
- **Demo app cannot reach OpenObserve**: ensure `openobserve_host_from_container`
  points at the host/OpenObserve from the container's point of view
  (Podman bridge gateway `10.88.0.1` for local).
- **Missing org**: OpenObserve auto-creates an org on first ingest into it; an
  empty org may not appear in the UI. Ingest at least one record (demo app load
  or a manual JSON ingest) first.

## Reset / clean re-run

To fully wipe the local stack (containers, volumes, data dir):

```bash
sudo podman rm -f rustfs postgres nats openobserve demo-observability bucket-init 2>/dev/null
sudo podman volume rm -f rustfs-data postgres-data nats-data openobserve-local 2>/dev/null
sudo rm -rf /opt/openobserve /opt/demo-observability
sudo podman network rm o2stack 2>/dev/null
ansible-playbook -i inventory.ini stack_prod.yml -e deploy_demo_app=true
```
