from __future__ import annotations

from pydantic import BaseModel


class ProfileOut(BaseModel):
    username: str
    display_name: str
    profile: dict
    sync_enabled: bool


class ProfileUpdate(BaseModel):
    display_name: str | None = None
    profile: dict | None = None
    sync_enabled: bool | None = None
