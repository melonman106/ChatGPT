# Eaglercraft 26.2 VBV Client

This repository contains the native Eaglercraft 26.2 u1 client-side ViaBackportVisuals project.

## Layout

- `VBV/` — client mod sources, patch scripts, and web-side mod helpers
- `ModsPack/` — optional resource-pack client mods imported during the VBV patch
- `U1-Patcher/` — verified Eaglercraft 26.2 u1 portable patcher kit
- `u1-1-source/` — Eaglercraft u1.1 source/tooling used for client builds
- `.github/workflows/02-compile-eagler-vbv.yml` — recovered WASM injection workflow
- `.github/workflows/03-single-html-eagler-vbv.yml` — single-HTML packaging workflow
- `.github/workflows/herz-compatibility.yml` — Herz compatibility investigation

Server-side Fabric/Paper VBV code and old experimental build/probe files have been removed from this client repository.
