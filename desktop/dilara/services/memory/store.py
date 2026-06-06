"""
Uzun süreli hafıza (SQLite + vektör arama).

İki katmanlı:
1. SQLite — yapısal kayıtlar (rutinler, tercihler, önemli olaylar)
2. Vektör — semantik arama için embedding'ler

ChromaDB persistent client kullanır (lokal dosya), bağımlılığı az,
hızlı, encryption'lı SQLite ile uyumlu.

Hassas içerik AES ile şifrelenerek saklanır.
"""

from __future__ import annotations

import asyncio
import json
import sqlite3
import time
import uuid
from contextlib import contextmanager
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Iterable, Optional

from dilara.core.logging import logger
from dilara.core.security import Cipher


@dataclass
class MemoryRecord:
    id: str
    text: str                          # Şifresiz görünüm (saklanırken şifrelenir)
    category: str = "fact"             # fact | preference | event | routine | note
    importance: float = 0.5            # 0..1
    timestamp: float = field(default_factory=time.time)
    metadata: dict[str, Any] = field(default_factory=dict)


_SCHEMA = """
CREATE TABLE IF NOT EXISTS memories (
    id TEXT PRIMARY KEY,
    text_encrypted TEXT NOT NULL,
    category TEXT,
    importance REAL,
    timestamp REAL,
    metadata TEXT
);
CREATE INDEX IF NOT EXISTS idx_memories_category ON memories(category);
CREATE INDEX IF NOT EXISTS idx_memories_importance ON memories(importance);
"""


class MemoryStore:
    """SQLite + opsiyonel vektör arama."""

    def __init__(
        self,
        db_path: Path,
        cipher: Optional[Cipher] = None,
        enable_vector: bool = True,
    ) -> None:
        self.db_path = db_path
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.cipher = cipher or Cipher()
        self.enable_vector = enable_vector
        self._lock = asyncio.Lock()

        self._init_db()
        self._collection = None
        self._embedder = None

    # --- Kurulum ---
    def _init_db(self) -> None:
        with self._connect() as conn:
            conn.executescript(_SCHEMA)

    @contextmanager
    def _connect(self):
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def _ensure_vector(self) -> None:
        if not self.enable_vector or self._collection is not None:
            return
        try:
            import chromadb  # type: ignore

            client = chromadb.PersistentClient(
                path=str(self.db_path.parent / "chroma")
            )
            self._collection = client.get_or_create_collection(name="dilara_memory")
        except Exception as e:
            logger.warning(f"Chroma yüklenemedi, vektör arama devre dışı: {e}")
            self.enable_vector = False
            return

        try:
            from sentence_transformers import SentenceTransformer  # type: ignore

            self._embedder = SentenceTransformer(
                "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
            )
        except Exception as e:
            logger.warning(f"Sentence-transformers yüklenemedi: {e}")
            self.enable_vector = False

    # --- Yazma ---
    async def add(self, record: MemoryRecord) -> None:
        async with self._lock:
            await asyncio.get_event_loop().run_in_executor(
                None, self._sync_add, record
            )

    def _sync_add(self, record: MemoryRecord) -> None:
        encrypted = self.cipher.encrypt(record.text)
        with self._connect() as conn:
            conn.execute(
                """
                INSERT OR REPLACE INTO memories
                (id, text_encrypted, category, importance, timestamp, metadata)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    record.id,
                    encrypted,
                    record.category,
                    record.importance,
                    record.timestamp,
                    json.dumps(record.metadata, ensure_ascii=False),
                ),
            )

        if self.enable_vector:
            self._ensure_vector()
            if self._collection is not None and self._embedder is not None:
                try:
                    embedding = self._embedder.encode(record.text).tolist()
                    self._collection.upsert(
                        ids=[record.id],
                        embeddings=[embedding],
                        metadatas=[
                            {
                                "category": record.category,
                                "importance": record.importance,
                                "timestamp": record.timestamp,
                            }
                        ],
                        documents=[record.text],
                    )
                except Exception as e:
                    logger.warning(f"Vektör upsert hatası: {e}")

    async def remember(
        self,
        text: str,
        category: str = "fact",
        importance: float = 0.5,
        metadata: Optional[dict[str, Any]] = None,
    ) -> MemoryRecord:
        record = MemoryRecord(
            id=str(uuid.uuid4()),
            text=text,
            category=category,
            importance=importance,
            metadata=metadata or {},
        )
        await self.add(record)
        return record

    # --- Okuma ---
    async def search(
        self, query: str, k: int = 5, min_score: float = 0.0
    ) -> list[MemoryRecord]:
        async with self._lock:
            return await asyncio.get_event_loop().run_in_executor(
                None, self._sync_search, query, k, min_score
            )

    def _sync_search(self, query: str, k: int, min_score: float) -> list[MemoryRecord]:
        if self.enable_vector:
            self._ensure_vector()
            if self._collection is not None and self._embedder is not None:
                try:
                    qvec = self._embedder.encode(query).tolist()
                    res = self._collection.query(query_embeddings=[qvec], n_results=k)
                    ids = res.get("ids", [[]])[0]
                    if ids:
                        return self._fetch_by_ids(ids)
                except Exception as e:
                    logger.warning(f"Vektör search hatası: {e}")

        # Fallback: importance + recency
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT * FROM memories
                ORDER BY importance DESC, timestamp DESC
                LIMIT ?
                """,
                (k,),
            ).fetchall()
        return [self._row_to_record(row) for row in rows]

    def _fetch_by_ids(self, ids: list[str]) -> list[MemoryRecord]:
        if not ids:
            return []
        placeholders = ",".join("?" * len(ids))
        with self._connect() as conn:
            rows = conn.execute(
                f"SELECT * FROM memories WHERE id IN ({placeholders})",
                ids,
            ).fetchall()
        return [self._row_to_record(row) for row in rows]

    def _row_to_record(self, row: sqlite3.Row) -> MemoryRecord:
        return MemoryRecord(
            id=row["id"],
            text=self.cipher.decrypt(row["text_encrypted"]),
            category=row["category"],
            importance=row["importance"],
            timestamp=row["timestamp"],
            metadata=json.loads(row["metadata"]) if row["metadata"] else {},
        )

    async def list_by_category(self, category: str, limit: int = 50) -> list[MemoryRecord]:
        async with self._lock:
            return await asyncio.get_event_loop().run_in_executor(
                None, self._sync_list_by_category, category, limit
            )

    def _sync_list_by_category(self, category: str, limit: int) -> list[MemoryRecord]:
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT * FROM memories
                WHERE category = ?
                ORDER BY timestamp DESC
                LIMIT ?
                """,
                (category, limit),
            ).fetchall()
        return [self._row_to_record(row) for row in rows]

    async def summary(self, max_items: int = 12) -> str:
        """LLM'e besleyebileceğimiz kısa hafıza özeti."""
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT * FROM memories
                ORDER BY importance DESC, timestamp DESC
                LIMIT ?
                """,
                (max_items,),
            ).fetchall()
        if not rows:
            return ""
        lines = []
        for row in rows:
            text = self.cipher.decrypt(row["text_encrypted"])
            lines.append(f"- [{row['category']}] {text}")
        return "\n".join(lines)

    async def delete(self, record_id: str) -> None:
        async with self._lock:
            with self._connect() as conn:
                conn.execute("DELETE FROM memories WHERE id = ?", (record_id,))
            if self.enable_vector and self._collection is not None:
                try:
                    self._collection.delete(ids=[record_id])
                except Exception:
                    pass
