# Eaglercraft 26.2 VBV Client

This repository contains the native Eaglercraft 26.2 u1 client-side ViaBackportVisuals project.

## Build pipeline (run in order)

1. **01 - Prepare Eaglercraft 26.2 u1 Source Project** — manually reconstructs and verifies a source project using the supplied authorized client JAR and hash-pinned inputs. Uploads `Eaglercraft-26.2-u1-Source-Project-<run-id>`.
2. **02 - Compile Eaglercraft 26.2 u1 (VBV + Herz)** — takes the run ID and artifact ID from step 01, applies the client mod patch, compiles the Java sources, and builds the browser WASM output.
3. **03 - Package Eaglercraft 26.2 (Single HTML)** — takes the successful run ID from step 02 and packages the output into a single HTML file.

All three workflows are manually triggered; pushes do not start builds automatically. Workflow 01 needs valid HTTPS URLs and SHA-256 hashes for the authorized 26.2 client JAR, Java 17 runtime, Vineflower, source patch bundle, u1 project skeleton, resource overlay, and external resource archive. These inputs are verified before use. A successful run is not guaranteed until the workflow is tested with valid inputs.

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
