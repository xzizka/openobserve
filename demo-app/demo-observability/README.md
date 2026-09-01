# Demo Observability

A demo Java web application (running on Apache Tomcat) that demonstrates
observability telemetry — **logs, traces and metrics** — reported to
[OpenObserve](https://github.com/openobserve/openobserve) via the
[OpenTelemetry](https://opentelemetry.io/) Java agent (zero-code
instrumentation).

## Architecture

```
┌─────────────────────────┐     OTLP/HTTP (http/protobuf)     ┌──────────────────┐
│ demo-observability WAR  │ ─────────────────────────────────▶ │   OpenObserve    │
│  Tomcat 10 + OTel agent │      /api/VUMSLegend/v1/{logs,     │  :5080           │
└─────────────────────────┘      metrics,traces}               └──────────────────┘
         :8080
```

- The **OpenTelemetry javaagent** (v2.31.1) is attached at JVM startup.
  It automatically captures HTTP spans, JVM metrics and application logs,
  and exports all three signals to OpenObserve over OTLP/HTTP.
- The demo servlet additionally creates an explicit child span, a custom
  counter metric (`demo.requests.total`) and emits log records that
  correlate with the enclosing trace.
- All telemetry is exported to organization **`VUMSLegend`** (stream
  **`default`**).

## Project layout

```
.
├── pom.xml                    # Maven WAR project (Jakarta Servlet, OTel API)
├── Dockerfile                 # Multi-stage: build WAR, then Tomcat + agent
├── otel.properties            # Javaagent defaults (endpoint injected via env)
├── src/main/java/
│   └── dev/demo/observability/
│       ├── DemoServlet.java   # /demo and / : span, metric, logs per request
│       └── LoadGenServlet.java# /load : generates a batch of requests
└── ansible/
    ├── inventory.ini
    └── deploy_demo.yml        # Builds image and runs container in Podman
```

## Deploy

On the target machine (`192.168.122.151`), after copying the project sources
to `/opt/demo-observability`:

```bash
ansible-playbook -i inventory.ini deploy_demo.yml
```

The playbook builds the container image with Podman and runs it, wiring the
OpenTelemetry exporter to OpenObserve.

> **Endpoint note:** the agent inside the container cannot reach OpenObserve via
> `127.0.0.1` (that resolves to its own container). The demo runs on the same
> Podman `o2stack` network as OpenObserve, so it is pointed at the O2 **container
> name** (`openobserve:5080`). OpenObserve runs on `:5080` with Basic auth for
> org `VUMSLegend`.

## Verify

Ingested data lands in the `default` stream of the **`VUMSLegend`** organization
and is queryable via the OpenObserve API/UI:

- App UI: `http://<host>:8080/demo-observability/`
- Generate load: `http://<host>:8080/demo-observability/load?count=50`

Search examples (Basic auth `admin@example.com:Complexpass#123`):

```bash
# Logs
curl -u 'admin@example.com:Complexpass#123' -X POST \
  'http://<host>:5080/api/VUMSLegend/_search?type=logs' \
  -H 'Content-Type: application/json' \
  -d '{"query":{"sql":"select count(*) as c from \"default\"","start_time":<start_us>,"end_time":<end_us>}}'

# Traces
curl -u 'admin@example.com:Complexpass#123' -X POST \
  'http://<host>:5080/api/VUMSLegend/_search?type=traces' \
  -H 'Content-Type: application/json' \
  -d '{"query":{"sql":"select count(*) as c from \"default\"","start_time":<start_us>,"end_time":<end_us>}}'
```

In the OpenObserve UI (`http://<host>:5080`) — be sure to **switch to the
`VUMSLegend` organization**:

- **Traces** — filter by service `demo-observability`
- **Logs** — search the `default` stream
- **Metrics** / **Streams** — `demo_requests_total` and JVM metrics
