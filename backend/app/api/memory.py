"""Hafıza CRUD."""

from __future__ import annotations

from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.db.session import get_db
from app.models.memory import MemoryItem
from app.models.user import User
from app.schemas.memory import MemoryItemIn, MemoryItemOut


router = APIRouter()


@router.get("/", response_model=list[MemoryItemOut])
async def list_items(
    category: Optional[str] = None,
    limit: int = Query(default=100, le=500),
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    stmt = select(MemoryItem).where(MemoryItem.user_id == user.id)
    if category:
        stmt = stmt.where(MemoryItem.category == category)
    stmt = stmt.order_by(MemoryItem.updated_at.desc()).limit(limit)
    result = await db.execute(stmt)
    items = result.scalars().all()
    return [_to_out(it) for it in items]


@router.put("/{item_id}", response_model=MemoryItemOut)
async def upsert_item(
    item_id: str,
    payload: MemoryItemIn,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    if payload.id != item_id:
        raise HTTPException(status_code=400, detail="ID uyumsuz.")
    existing = await db.get(MemoryItem, item_id)
    if existing and existing.user_id != user.id:
        raise HTTPException(status_code=403, detail="Bu kayıt başkasına ait.")
    if existing:
        existing.text_encrypted = payload.text_encrypted
        existing.category = payload.category
        existing.importance = payload.importance
        existing.metadata_json = payload.metadata
    else:
        existing = MemoryItem(
            id=item_id,
            user_id=user.id,
            text_encrypted=payload.text_encrypted,
            category=payload.category,
            importance=payload.importance,
            metadata_json=payload.metadata,
        )
        db.add(existing)
    await db.commit()
    await db.refresh(existing)
    return _to_out(existing)


@router.delete("/{item_id}")
async def delete_item(
    item_id: str,
    db: AsyncSession = Depends(get_db),
    user: User = Depends(get_current_user),
):
    obj = await db.get(MemoryItem, item_id)
    if not obj or obj.user_id != user.id:
        raise HTTPException(status_code=404, detail="Kayıt bulunamadı.")
    await db.delete(obj)
    await db.commit()
    return {"deleted": True}


def _to_out(it: MemoryItem) -> MemoryItemOut:
    return MemoryItemOut(
        id=it.id,
        text_encrypted=it.text_encrypted,
        category=it.category,
        importance=it.importance,
        metadata=it.metadata_json or {},
        created_at=it.created_at,
        updated_at=it.updated_at,
    )
