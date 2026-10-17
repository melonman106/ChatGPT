# Eaglercraft 26.2 VBV Client

This repository contains the native Eaglercraft 26.2 u1 client-side ViaBackportVisuals project.

## Build pipeline (run in order)

1. **01 - Prepare Eaglercraft 26.2 u1 Source Project** — automatically searches the checked-out repository and `U1-Patcher/portable-kit.zip` for the patcher, Vineflower, source bundle, skeleton, overlay, and external resources. It uploads `Eaglercraft-26.2-u1-Source-Project-<run-id>`. The Minecraft 26.2 client JAR is not currently in the repository; if you add it as `minecraft-26.2-client.jar`, the workflow finds it automatically. Otherwise, provide its HTTPS URL and SHA-256 as the only optional workflow inputs.
2. **02 - Compile Eaglercraft 26.2 u1 (VBV + Herz)** — takes the run ID and artifact ID from step 01, applies the client mod patch, compiles the Java sources, and builds the browser WASM output.
3. **03 - Package Eaglercraft 26.2 (Single HTML)** — takes the successful run ID from step 02 and packages the output into a single HTML file.

All three workflows are manually triggered; pushes do not start builds automatically. Workflow 01 verifies the checked-in portable kit and pinned input archives. Only the client JAR may need an external URL/hash because that file is not stored in this repository. A successful run is not guaranteed until the workflow is tested with valid inputs.

## Layout

- `VBV/` — client mod sources, patch scripts, and web-side mod helpers
- `ModsPack/` — optional resource-pack client mods imported during the VBV patch
- `U1-Patcher/` — verified Eaglercraft 26.2 u1 portable patcher kit
- `u1-1-source/` — Eaglercraft u1.1 source/tooling used for client builds
- `.github/workflows/01-prepare-eaglercraft-source.yml` — source-project preparation
- `.github/workflows/02-compile-eagler-vbv.yml` — VBV + local Herz-compatible API source compilation
- `.github/workflows/03-single-html-eagler-vbv.yml` — single-HTML packaging
- `.github/workflows/herz-compatibility.yml` — upstream Herz compatibility investigation

Server-side Fabric/Paper VBV code and old experimental build/probe files have been removed from this client repository.
