#!/usr/bin/env node
"use strict";

const fs = require("node:fs");
const crypto = require("node:crypto");
const zlib = require("node:zlib");

const [,, htmlPath, wasmPath, outputPath] = process.argv;
if (!htmlPath || !wasmPath || !outputPath) {
  console.error("Usage: node inject_recovered_wasm_into_html.js <base.html> <client.wasm> <output.html>");
  process.exit(2);
}

const html = fs.readFileSync(htmlPath, "utf8");
const wasm = fs.readFileSync(wasmPath);
const id = "eag-inline-wasm-br";
const marker = `id="${id}"`;
const markerIndex = html.indexOf(marker);
if (markerIndex < 0) throw new Error(`Missing ${marker}`);

const tagStart = html.lastIndexOf("<script", markerIndex);
const tagEnd = html.indexOf("</script>", markerIndex);
if (tagStart < 0 || tagEnd < 0 || tagEnd <= tagStart) {
  throw new Error(`Could not locate complete ${id} payload tag`);
}

const tag = html.slice(tagStart, tagEnd + "</script>".length);
const compressed = zlib.brotliCompressSync(wasm, {
  params: {
    [zlib.constants.BROTLI_PARAM_QUALITY]: 11,
    [zlib.constants.BROTLI_PARAM_MODE]: zlib.constants.BROTLI_MODE_GENERIC
  }
});
const encoded = compressed.toString("base64");
const lines = [];
for (let i = 0; i < encoded.length; i += 256 * 1024) lines.push(encoded.slice(i, i + 256 * 1024));
const replacement = `<script id="${id}" type="application/octet-stream" data-size="${compressed.length}">\n${lines.join("\n")}\n</script>`;

const before = html.slice(0, tagStart);
const after = html.slice(tagEnd + "</script>".length);
const out = before + replacement + after;
fs.writeFileSync(outputPath, out);

function sha256(buf) { return crypto.createHash("sha256").update(buf).digest("hex"); }
console.log(`Base HTML: ${html.length} bytes`);
console.log(`Recovered client.wasm: ${wasm.length} bytes sha256=${sha256(wasm)}`);
console.log(`New Brotli payload: ${compressed.length} bytes`);
console.log(`Output HTML: ${out.length} bytes`);
console.log(`Injected payload id: ${id}`);

const check = fs.readFileSync(outputPath, "utf8");
if (!check.includes(`id="${id}"`)) throw new Error("Injected payload is missing after write");
if (!check.includes(`data-size="${compressed.length}"`)) throw new Error("Injected payload size marker is missing");
