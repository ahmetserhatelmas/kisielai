"""Toplu senkronizasyon (cihaz <-> sunucu)."""

from __future__ import annotations

from datetime import datetime, timezone

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.db.session import get_db
from app.models.memory import MemoryItem
from app.models.user import User
from app.schemas.memory import MemoryItemOut, SyncBatch


router = APIRouter()


@router.post("/push", response_model=dict)
async def push(
    batch: SyncBatch,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Cihazdan sunucuya toplu yazma."""
    upserted = 0
    for payload in batch.items:
        existing = await db.get(MemoryItem, payload.id)
        if existing and existing.user_id != user.id:
            continue
        if existing:
            existing.text_encrypted = payload.text_encrypted
            existing.category = payload.category
            existing.importance = payload.importance
            existing.metadata_json = payload.metadata
        else:
            db.add(
                MemoryItem(
                    id=payload.id,
                    user_id=user.id,
                    text_encrypted=payload.text_encrypted,
                    category=payload.category,
                    importance=payload.importance,
                    metadata_json=payload.metadata,
                )
            )
        upserted += 1
    await db.commit()
    return {"upserted": upserted}


@router.get("/pull", response_model=list[MemoryItemOut])
async def pull(
    since: datetime | None = None,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    """Sunucudan cihaza çek (since'dan sonraki değişiklikler)."""
    stmt = select(MemoryItem).where(MemoryItem.user_id == user.id)
    if since:
        stmt = stmt.where(MemoryItem.updated_at > since)
    stmt = stmt.order_by(MemoryItem.updated_at.asc())
    result = await db.execute(stmt)
    items = result.scalars().all()
    return [
        MemoryItemOut(
            id=it.id,
            text_encrypted=it.text_encrypted,
            category=it.category,
            importance=it.importance,
            metadata=it.metadata_json or {},
            created_at=it.created_at,
            updated_at=it.updated_at,
        )
        for it in items
    ]
