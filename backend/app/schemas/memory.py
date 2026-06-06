from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field


class MemoryItemIn(BaseModel):
    id: str
    text_encrypted: str
    category: str = "fact"
    importance: float = Field(default=0.5, ge=0.0, le=1.0)
    metadata: dict = Field(default_factory=dict)


class MemoryItemOut(MemoryItemIn):
    created_at: datetime
    updated_at: datetime


class SyncBatch(BaseModel):
    items: list[MemoryItemIn]
    since: Optional[datetime] = None
