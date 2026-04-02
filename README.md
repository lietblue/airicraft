# Airicraft

Airicraft is a Fabric mod that exposes an in-game agent bridge and a CLI for automating common tasks.

## Weave / OpenTelemetry Observability Setup

Airicraft now supports optional LLM-call tracing via OpenTelemetry.
The default mode is vendor-neutral OTLP; if you want W&B Weave, you only need to switch a single profile in config.

### 1) Why this setup exists

- The `planner` and `vision` outbound requests emit spans with:
  - request metadata (provider/model/endpoint)
  - response metadata (status/usage tokens)
  - sanitized request/response summaries
  - failures and error typing
- `generic` profile exports only standard OTEL fields.
- `weave` profile adds a small set of Weave-friendly attributes.

### 2) Edit config

Airicraft writes/reads:

`config/airicraft/agent.yml`

The file is based on `src/client/resources/config/airicraft/agent.yml.example`.

A practical OTLP setup is already scaffolded in that template under `observability`.

### 3) Generic OTLP (default)

Set:

```yaml
observability:
  enabled: true
  exporter: "otlp_http"
  otlpEndpoint: "http://127.0.0.1:4318/v1/traces"
  otlpHeaders: {}
  vendorProfile: "generic"
  captureInputs: false
  captureOutputs: false
  captureImages: false
```

### 4) W&B Weave profile

Use this to send the same spans to Weave with minimal changes:

```yaml
observability:
  enabled: true
  exporter: "otlp_http"
  otlpEndpoint: "https://trace.wandb.ai/otel/v1/traces"
  otlpHeaders:
    wandb-api-key: "<W&B_API_KEY>"
  resourceAttributes:
    wandb.entity: "shinohara-rin"
    wandb.project: "airicraft"
  vendorProfile: "weave"
  captureInputs: false
  captureOutputs: false
  captureImages: false
```

For your project slug `shinohara-rin/airicraft`, set:

- `wandb.entity: "shinohara-rin"`
- `wandb.project: "airicraft"`

### 5) Optional data capture controls

- `captureInputs`: include sanitized input summaries.
- `captureOutputs`: include sanitized output summaries.
- `captureImages`: allow screenshot/tool-capture spans to export image payloads for media-capable backends like Weave. Regular LLM request traces still redact image bytes.

If all are false, only non-content structural tracing metadata is sent.

### 6) Reload workflow

1. Start Minecraft or keep existing session.
2. Ensure `airicraft/config/agent.yml` has the desired `observability` block.
3. In-game mod/runtime picks up file changes when the mod is restarted.
4. Confirm traces appear in your OTLP collector/Weave dashboard.

## Notes

- If `observability.enabled` is false, no OTLP network calls are made.
- If `otlpEndpoint` is empty or unsupported, observability is disabled at runtime with a warning.
- This integration is wired for outbound planner and vision API calls only.
