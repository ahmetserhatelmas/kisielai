"""
Web araştırma modülü.

Akış:
1. DuckDuckGo ile arama
2. İlk N URL'in içeriğini fetch et (httpx)
3. trafilatura ile temiz metin çıkar
4. LLM'le özetle / soruya göre filtrele

Tüm internet erişimi PermissionManager.INTERNET kontrolünden geçer.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from typing import Optional

import httpx

from dilara.core.logging import logger
from dilara.core.permissions import Permission, PermissionManager
from dilara.services.llm.base import ChatMessage, LLMBackend


@dataclass
class SearchResult:
    title: str
    url: str
    snippet: str = ""
    body: str = ""


@dataclass
class ResearchReport:
    query: str
    summary: str
    sources: list[SearchResult] = field(default_factory=list)


class WebResearcher:
    def __init__(
        self,
        permissions: PermissionManager,
        llm: Optional[LLMBackend] = None,
        llm_model: str = "",
        max_results: int = 5,
        max_chars_per_page: int = 6000,
    ) -> None:
        self.permissions = permissions
        self.llm = llm
        self.llm_model = llm_model
        self.max_results = max_results
        self.max_chars_per_page = max_chars_per_page

    async def search(self, query: str, k: int = 5) -> list[SearchResult]:
        self.permissions.ensure(Permission.INTERNET)

        def _search() -> list[SearchResult]:
            try:
                from duckduckgo_search import DDGS  # type: ignore
            except Exception as e:
                logger.error(f"duckduckgo_search yüklenemedi: {e}")
                return []
            results: list[SearchResult] = []
            try:
                with DDGS() as ddgs:
                    for hit in ddgs.text(query, max_results=k, region="tr-tr"):
                        results.append(
                            SearchResult(
                                title=hit.get("title", ""),
                                url=hit.get("href", ""),
                                snippet=hit.get("body", ""),
                            )
                        )
            except Exception as e:
                logger.error(f"DDG arama hatası: {e}")
            return results

        return await asyncio.get_event_loop().run_in_executor(None, _search)

    async def fetch_page(self, url: str) -> str:
        self.permissions.ensure(Permission.INTERNET)
        try:
            async with httpx.AsyncClient(
                timeout=15.0,
                headers={"User-Agent": "Mozilla/5.0 Dilara"},
                follow_redirects=True,
            ) as client:
                resp = await client.get(url)
                if resp.status_code >= 400:
                    return ""
                html = resp.text
        except Exception as e:
            logger.warning(f"Sayfa fetch hatası ({url}): {e}")
            return ""

        try:
            import trafilatura  # type: ignore

            text = trafilatura.extract(html) or ""
        except Exception:
            from bs4 import BeautifulSoup  # type: ignore

            soup = BeautifulSoup(html, "html.parser")
            for tag in soup(["script", "style", "nav", "footer"]):
                tag.decompose()
            text = soup.get_text(separator="\n").strip()

        return text[: self.max_chars_per_page]

    async def research(self, query: str, k: Optional[int] = None) -> ResearchReport:
        """Tam pipeline: ara, oku, özetle."""
        self.permissions.ensure(Permission.INTERNET)
        k = k or self.max_results

        results = await self.search(query, k=k)
        # Paralel fetch
        bodies = await asyncio.gather(
            *(self.fetch_page(r.url) for r in results), return_exceptions=True
        )
        for r, body in zip(results, bodies):
            if isinstance(body, str):
                r.body = body

        # Özet
        summary = await self._summarize(query, results)
        return ResearchReport(query=query, summary=summary, sources=results)

    async def _summarize(self, query: str, results: list[SearchResult]) -> str:
        if not self.llm or not results:
            return "\n".join(f"- {r.title}: {r.snippet}" for r in results)

        sources_text = "\n\n".join(
            f"### Kaynak {i + 1}: {r.title}\nURL: {r.url}\n{r.body[:3000]}"
            for i, r in enumerate(results)
            if r.body or r.snippet
        )
        if not sources_text:
            return "Sonuç bulunamadı."

        prompt = (
            f"Aşağıdaki kaynaklara dayanarak şu soruyu yanıtla, "
            f"Türkçe ve kısa bir özet ver:\n\n"
            f"SORU: {query}\n\n"
            f"KAYNAKLAR:\n{sources_text}\n\n"
            f"Özet (madde madde, en fazla 6 madde):"
        )
        try:
            resp = await self.llm.chat(
                [ChatMessage(role="user", content=prompt)],
                model=self.llm_model,
                temperature=0.3,
                max_tokens=600,
            )
            return resp.text.strip()
        except Exception as e:
            logger.warning(f"Özet hatası: {e}")
            return "\n".join(f"- {r.title}: {r.snippet}" for r in results)
