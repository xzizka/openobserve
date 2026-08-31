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
│  Tomcat 10 + OTel agent │      /api/default/v1/{logs,        │  :5080           │
└─────────────────────────┘      metrics,traces}               └──────────────────┘
         :8080
```

- The **OpenTelemetry javaagent** (v2.31.1) is attached at JVM startup.
  It automatically captures HTTP spans, JVM metrics and application logs,
  and exports all three signals to OpenObserve over OTLP/HTTP.
- The demo servlet additionally creates an explicit child span, a custom
  counter metric (`demo.requests.total`) and emits log records that
  correlate with the enclosing trace.

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
OpenTelemetry exporter to OpenObserve on `:5080` with Basic auth.

## Verify

- App UI: `http://<host>:8080/demo-observability/`
- Generate load: `http://<host>:8080/demo-observability/load?count=50`
- OpenObserve UI (`http://<host>:5080`), then check:
  - **Traces** — filter by `demo-observability`
  - **Logs** — search `demo-observability`
  - **Metrics** / **Streams** — `demo.requests.total` and JVM metrics
