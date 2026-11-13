"""Optional fast storage for disposable SDK runs, with retained evidence."""

import os
from pathlib import Path
import shutil


class RuntimeWorkspace:
    def __init__(self, retained_root, name, distribution, extra_names=()):
        self.retained = retained_root / name
        fast = os.environ.get("TANDEMOUNT_RUN_CACHE")
        self.directory = Path(fast).expanduser().resolve() / name if fast else self.retained
        if self.directory.resolve() == self.retained.resolve():
            fast = None
            self.directory = self.retained
        self.fast = bool(fast)
        self.directory.mkdir(parents=True, exist_ok=False)
        self.sdk_sources = []
        for source in distribution.iterdir():
            if source.name not in {"libraries", "versions", *extra_names} and not source.name.endswith(".jar"):
                continue
            target = self.directory / source.name
            if self.fast:
                if source.is_dir():
                    shutil.copytree(source, target)
                else:
                    shutil.copy2(source, target)
            else:
                target.symlink_to(source, target_is_directory=source.is_dir())
            self.sdk_sources.append(source)

    def finish(self, report):
        if not self.fast:
            report["runtime_storage"] = "distribution-symlinks"
            return report
        # Only copies this workspace itself created are removed. Retained
        # references point back to the original immutable, hash-identified SDK.
        for source in self.sdk_sources:
            copied = self.directory / source.name
            if copied.is_dir() and not copied.is_symlink():
                shutil.rmtree(copied)
            else:
                copied.unlink()
            copied.symlink_to(source, target_is_directory=source.is_dir())
        self.retained.parent.mkdir(parents=True, exist_ok=True)
        if self.retained.exists():
            raise FileExistsError("Retained evidence directory already exists: " + str(self.retained))
        before, after = str(self.directory), str(self.retained)
        shutil.move(str(self.directory), str(self.retained))
        def rewrite(value):
            if isinstance(value, dict):
                return {key: rewrite(item) for key, item in value.items()}
            if isinstance(value, list):
                return [rewrite(item) for item in value]
            if isinstance(value, str) and value.startswith(before):
                return after + value[len(before):]
            return value
        report = rewrite(report)
        report["runtime_storage"] = "fast-staged-with-retained-evidence"
        return report
