from __future__ import annotations

import json
import os
import re
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

WORKFLOW_ID = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")


@dataclass(frozen=True)
class WorkflowRecord:
    workflow_id: str
    name: str
    script: str
    enabled: bool
    state: dict[str, Any]
    created_at: float
    updated_at: float


class WorkflowStorage:
    def __init__(self, directory: Path) -> None:
        self.directory = Path(directory)
        self.metadata_path = self.directory / "metadata.json"
        self.directory.mkdir(parents=True, exist_ok=True)
        self._metadata = self._load_metadata()

    def _load_metadata(self) -> dict[str, Any]:
        if not self.metadata_path.exists():
            return {"version": 1, "workflows": {}}
        try:
            data = json.loads(self.metadata_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise RuntimeError(f"Could not read {self.metadata_path}: {error}") from error
        if not isinstance(data, dict) or not isinstance(data.get("workflows"), dict):
            raise TypeError(f"Invalid workflow metadata in {self.metadata_path}")
        data.setdefault("version", 1)
        return data

    def _write_metadata(self) -> None:
        self._atomic_write(
            self.metadata_path,
            json.dumps(self._metadata, indent=2, sort_keys=True) + "\n",
        )

    @staticmethod
    def _atomic_write(path: Path, content: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary_name = ""
        try:
            with tempfile.NamedTemporaryFile(
                "w",
                encoding="utf-8",
                dir=path.parent,
                prefix=f".{path.name}.",
                delete=False,
            ) as temporary:
                temporary.write(content)
                temporary.flush()
                os.fsync(temporary.fileno())
                temporary_name = temporary.name
            os.replace(temporary_name, path)
        finally:
            if temporary_name and os.path.exists(temporary_name):
                os.unlink(temporary_name)

    @staticmethod
    def _slug(name: str) -> str:
        slug = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")[:64]
        return slug or "workflow"

    def _entry(self, workflow_id: str) -> dict[str, Any]:
        if not WORKFLOW_ID.fullmatch(workflow_id):
            raise KeyError("Invalid workflow ID")
        try:
            return self._metadata["workflows"][workflow_id]
        except KeyError as error:
            raise KeyError(f"Workflow '{workflow_id}' does not exist") from error

    def _script_path(self, workflow_id: str) -> Path:
        if not WORKFLOW_ID.fullmatch(workflow_id):
            raise KeyError("Invalid workflow ID")
        return self.directory / f"{workflow_id}.lua"

    def list(self) -> list[WorkflowRecord]:
        records: list[WorkflowRecord] = []
        for workflow_id in sorted(self._metadata["workflows"]):
            try:
                records.append(self.get(workflow_id))
            except FileNotFoundError:
                continue
        return records

    def get(self, workflow_id: str) -> WorkflowRecord:
        entry = self._entry(workflow_id)
        script = self._script_path(workflow_id).read_text(encoding="utf-8")
        return WorkflowRecord(
            workflow_id=workflow_id,
            name=str(entry.get("name", workflow_id)),
            script=script,
            enabled=bool(entry.get("enabled", False)),
            state=dict(entry.get("state", {})),
            created_at=float(entry.get("created_at", 0)),
            updated_at=float(entry.get("updated_at", 0)),
        )

    def create(self, name: str, script: str, enabled: bool = False) -> WorkflowRecord:
        clean_name = name.strip()
        if not clean_name:
            raise ValueError("A workflow name is required")
        workflow_id = self._slug(clean_name)
        suffix = 2
        while workflow_id in self._metadata["workflows"]:
            ending = f"-{suffix}"
            workflow_id = self._slug(clean_name)[: 64 - len(ending)] + ending
            suffix += 1
        now = time.time()
        self._atomic_write(self._script_path(workflow_id), script)
        self._metadata["workflows"][workflow_id] = {
            "name": clean_name,
            "enabled": bool(enabled),
            "state": {},
            "created_at": now,
            "updated_at": now,
        }
        self._write_metadata()
        return self.get(workflow_id)

    def update(self, workflow_id: str, *, name: str | None = None, script: str | None = None) -> WorkflowRecord:
        entry = self._entry(workflow_id)
        if name is not None:
            clean_name = name.strip()
            if not clean_name:
                raise ValueError("A workflow name is required")
            entry["name"] = clean_name
        if script is not None:
            self._atomic_write(self._script_path(workflow_id), script)
        entry["updated_at"] = time.time()
        self._write_metadata()
        return self.get(workflow_id)

    def set_enabled(self, workflow_id: str, enabled: bool) -> WorkflowRecord:
        entry = self._entry(workflow_id)
        entry["enabled"] = bool(enabled)
        entry["updated_at"] = time.time()
        self._write_metadata()
        return self.get(workflow_id)

    def set_state(self, workflow_id: str, key: str, value: Any) -> None:
        entry = self._entry(workflow_id)
        state = entry.setdefault("state", {})
        if value is None:
            state.pop(key, None)
        else:
            state[key] = value
        self._write_metadata()

    def delete(self, workflow_id: str) -> None:
        self._entry(workflow_id)
        self._script_path(workflow_id).unlink(missing_ok=True)
        del self._metadata["workflows"][workflow_id]
        self._write_metadata()
