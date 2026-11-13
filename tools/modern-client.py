#!/usr/bin/env python3
"""Verify a real local vanilla-client login using exported native packet IDs."""

import argparse
import json
from pathlib import Path
import sys

from vanilla_protocol import VanillaClient, load_ids


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--ids", required=True, type=Path)
    parser.add_argument("--name", default="TmVanillaTest")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--timeout", type=int, default=30)
    args = parser.parse_args()
    ids = load_ids(args.ids)
    port = args.port or ids.get("port") or ids.get("server-port")
    if port is None or not 1 <= port <= 65535:
        parser.error("Provide the actual loopback server port")
    try:
        client = VanillaClient(port, args.minecraft, ids, name=args.name, timeout=args.timeout)
        try:
            result = client.verify(args.timeout)
        finally:
            client.close()
        result["result"] = "pass" if result["joined_play"] and result["connection_held"] and not result["errors"] else "fail"
    except Exception as error:
        result = {"result": "fail", "errors": [str(error)], "visual": "not-run"}
    result.update({"minecraft": args.minecraft, "host": "127.0.0.1", "port": port,
                   "kind": "vanilla-network-login", "native_id_export": str(args.ids)})
    rendered = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered)
    print(rendered, end="")
    return 0 if result["result"] == "pass" else 1


if __name__ == "__main__":
    sys.exit(main())
