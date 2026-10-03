#!/usr/bin/env python3
"""Make or apply the 26.2 Chinese catalog patch against Mojang's indexed asset."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys

UPSTREAM_SHA256 = "5389c7aa92675c87480e4cbac48f1e3590550c954bb09a379a74007c614f1094"
FINAL_SHA256 = "47d66d5b25a5ff1c40a4a6a179b44b165517af1863617ecac5cd03e751f49ff2"
FORMAT = "eagler-26.2-zh-cn-delta-v1"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load(path: Path, expected: str) -> dict[str, str]:
    raw = path.read_bytes()
    if digest(raw) != expected:
        raise ValueError(f"SHA-256 mismatch: {path}")
    value = json.loads(raw)
    if not isinstance(value, dict) or any(not isinstance(k, str) or not isinstance(v, str)
                                         for k, v in value.items()):
        raise ValueError(f"expected a string catalog: {path}")
    return value


def build_patch(upstream: dict[str, str], final: dict[str, str]) -> dict:
    if upstream.keys() - final.keys():
        raise ValueError("final catalog removed upstream keys")
    return {
        "format": FORMAT,
        "upstream_sha256": UPSTREAM_SHA256,
        "final_sha256": FINAL_SHA256,
        "order": list(final),
        "overrides": {key: value for key, value in final.items() if upstream.get(key) != value},
    }


def rebuild(upstream: dict[str, str], patch: dict) -> bytes:
    if set(patch) != {"format", "upstream_sha256", "final_sha256", "order", "overrides"}:
        raise ValueError("unexpected patch fields")
    if (patch["format"] != FORMAT or patch["upstream_sha256"] != UPSTREAM_SHA256
            or patch["final_sha256"] != FINAL_SHA256):
        raise ValueError("patch identity mismatch")
    order, overrides = patch["order"], patch["overrides"]
    if not isinstance(order, list) or not isinstance(overrides, dict):
        raise ValueError("invalid patch structure")
    if any(not isinstance(k, str) for k in order) or len(order) != len(set(order)):
        raise ValueError("invalid or duplicate patch keys")
    if any(not isinstance(k, str) or not isinstance(v, str) for k, v in overrides.items()):
        raise ValueError("invalid override value")
    if set(order) != set(upstream) | set(overrides):
        raise ValueError("patch key set differs from upstream and overrides")
    if any(k in upstream and upstream[k] == v for k, v in overrides.items()):
        raise ValueError("redundant override")
    result = {key: overrides.get(key, upstream.get(key)) for key in order}
    data = (json.dumps(result, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if digest(data) != FINAL_SHA256:
        raise ValueError("reconstructed catalog SHA-256 mismatch")
    return data


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("make-patch", "apply"))
    parser.add_argument("upstream", type=Path, help="official indexed zh_cn.json object")
    parser.add_argument("input", type=Path, help="target catalog for make-patch, patch JSON for apply")
    parser.add_argument("output", type=Path, help="new output file")
    args = parser.parse_args()
    try:
        if args.output.exists() or args.output.is_symlink():
            raise ValueError(f"output already exists: {args.output}")
        upstream = load(args.upstream, UPSTREAM_SHA256)
        if args.mode == "make-patch":
            final = load(args.input, FINAL_SHA256)
            patch = build_patch(upstream, final)
            if rebuild(upstream, patch) != args.input.read_bytes():
                raise ValueError("patch does not reproduce source bytes")
            data = (json.dumps(patch, ensure_ascii=False, separators=(",", ":")) + "\n").encode()
        else:
            patch = json.loads(args.input.read_bytes())
            data = rebuild(upstream, patch)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("xb") as handle:
            handle.write(data)
        print(f"{digest(data)}  {args.output}")
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
