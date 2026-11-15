#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Bind existing and late official-client attachment components to one JAR."""

import json
from pathlib import Path

from client_attachment import digest, run


def passed_probe(report, artifact_sha256):
    """A server graph or a test label alone cannot establish client lifetime."""
    static = report.get("receivedStateTicks", {})
    return (report.get("kind") == "original-client-native-graph-ticks"
            and report.get("receivedNativeState") is True
            and report.get("originalPacketCodecsConsumed", 0) > 0
            and report.get("probe_exit_code") == 0
            and report.get("inputs", {}).get("native_artifact_sha256") == artifact_sha256
            and static.get("ticks", 0) >= 1200
            and static.get("rootErrors") == 0 and static.get("removedObservations") == 0
            and static.get("maximumRelativeDrift", float("inf")) <= 1 / 16
            and report.get("rootErrors") == 0 and report.get("removedObservations") == 0
            and report.get("ticks", 0) >= 1200
            and report.get("maximumRelativeMotionError", float("inf")) <= 1 / 16
            and report.get("interactionRays", {}).get("cases", 0) >= 32
            and report.get("interactionRays", {}).get("helperHits") == 0)


def verify(minecraft, artifact, directory, protocol, observers):
    """Execute both observations sequentially, retaining compact portable proof.

    Each observer supplies its exact capture, trace and observation cutoff. An
    incomplete or failed probe remains a failure even after a successful server
    run; no metadata flag or source inspection can promote it.
    """
    expected_sha256 = digest(artifact)
    result = {"schema": 1, "kind": "official-client-attachment", "minecraft": minecraft,
              "artifact_sha256": expected_sha256, "status": "failed", "probes": {},
              "human_visual": False, "local_player_rendering": False}
    try:
        if set(observers) != {"existing", "late"}:
            raise ValueError("Both existing and late observer inputs are required")
        for name, inputs in observers.items():
            output = directory / (name + "-official-client.json")
            result["probes"][name] = run(minecraft, Path(inputs["capture"]), output,
                                        Path(inputs["trace"]), protocol, inputs["end_ns"], artifact)
            if not passed_probe(result["probes"][name], expected_sha256):
                raise ValueError(name + " observer original-client attachment checks failed")
        result["status"] = "passed"
    except Exception as error:
        result["error"] = str(error)
    (directory / "official-client-attachment.json").write_text(json.dumps(result, indent=2) + "\n")
    return result
