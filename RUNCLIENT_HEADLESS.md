# Running `./gradlew runClient` in headless environments

This repository's `runClient` task launches the Minecraft client and requires a GLFW-compatible display.

## Symptom
When no X11/Wayland display is available, `./gradlew runClient` fails with:

- `Failed to initialize GLFW`
- `Failed to detect any supported platform`

## Fix (headless Linux CI/container)
Install a virtual X server and run the task through `xvfb-run`:

```bash
apt-get update
apt-get install -y xvfb imagemagick
xvfb-run -a ./gradlew runClient
```

Notes:
- `imagemagick` is optional, only needed if you want to capture screenshots via `import`.
- Audio initialization may still warn (`Failed to open OpenAL device`) in containers; the game can continue rendering.

## Capturing a screenshot while running

```bash
mkdir -p artifacts
xvfb-run -a bash -lc './gradlew runClient > artifacts/runClient-xvfb.log 2>&1 & pid=$!; sleep 75; import -display "$DISPLAY" -window root artifacts/runClient.png; kill $pid; wait $pid || true'
```

The screenshot will be saved to `artifacts/runClient.png`.
