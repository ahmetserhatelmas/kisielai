"""Kullanıcı profili endpoint'leri."""

from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.db.session import get_db
from app.models.user import User
from app.schemas.profile import ProfileOut, ProfileUpdate


router = APIRouter()


@router.get("/me", response_model=ProfileOut)
async def me(user: User = Depends(get_current_user)):
    return ProfileOut(
        username=user.username,
        display_name=user.display_name,
        profile=user.profile or {},
        sync_enabled=user.sync_enabled,
    )


@router.patch("/me", response_model=ProfileOut)
async def update_me(
    payload: ProfileUpdate,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    if payload.display_name is not None:
        user.display_name = payload.display_name
    if payload.profile is not None:
        user.profile = payload.profile
    if payload.sync_enabled is not None:
        user.sync_enabled = payload.sync_enabled
    await db.commit()
    await db.refresh(user)
    return ProfileOut(
        username=user.username,
        display_name=user.display_name,
        profile=user.profile or {},
        sync_enabled=user.sync_enabled,
    )
