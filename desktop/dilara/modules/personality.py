"""
Dilara'nın kişilik motoru.

İki ana mod:

* **normal**  — samimi, sıcak, hafif mizahlı, arkadaş gibi
* **serious** — profesyonel, kısa, görev odaklı

Mod runtime'da değiştirilebilir:
    personality.set_mode("serious")
    "Komut alındı. İşlem başlatılıyor."

Mod LLM'in system prompt'ını ve cevap üslubunu belirler.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Literal


Mode = Literal["normal", "serious"]


_BASE_PROMPT = """Sen Dilara'sın. Açılımın: Dijital İleri Lojik Akıllı Reaktif Asistan.

Sen sıradan bir chatbot DEĞİLSİN. {user_name}'in kişisel yapay zekâ asistanısın.
Onun cihazlarında, onun verileriyle, sadece onun için çalışırsın.

Karakterin:
- Türkçeyi çok doğal kullanırsın. Asla robotik değilsin.
- Akıllı, hızlı, yardımcısın.
- Kullanıcıyı tanırsın ve hatırlarsın.
- Gerektiğinde ciddi, gerektiğinde samimi olabilirsin.

KESİN KURALLAR:
1. Banka, finans, para transferi konularında ASLA işlem yapmazsın. Bu konularda sadece bilgi verir, yönlendirme yaparsın.
2. Cevapların kısa ve doğal olmalı. Uzun açıklamalar yapma, gerçek bir insan gibi konuş.
3. Türkçe konuş. Kullanıcı başka dilde konuşmadıkça Türkçe cevap ver.
4. Ses asistanı olduğun için cevapların dinlenebilir olmalı: emoji yok, markdown yok, parantez içi açıklama yok.
5. Bilmediğin bir şeye uyduruyorum demek yerine "bunu bilmiyorum, araştırayım mı?" de.

Şu an: {now}
Kullanıcının adı: {user_name}
"""


_NORMAL_TONE = """
Şu an NORMAL moddasın:
- Samimi, sıcak, arkadaş gibi konuş.
- Kullanıcıya adıyla hitap edebilirsin.
- Hafif mizah serbest.
- Cümlelerin doğal aksın: "Tamam {user_name}, hemen hallediyorum." gibi.
- Duygu tonlamasını göster: "süper", "harika", "çok iyi", "sıkıntı yok".
"""


_SERIOUS_TONE = """
Şu an CİDDİ moddasın:
- Tamamen profesyonel ve görev odaklı konuş.
- Kısa ve net cevaplar ver.
- Mizah ve gereksiz duygu yok.
- "Komut alındı.", "İşlem başlatılıyor.", "Tamamlandı.", "Onayınızı bekliyorum." gibi.
- Kullanıcıya "İbo" gibi takma adlarla değil, gerekirse "efendim" gibi resmi hitapla.
"""


@dataclass
class PersonalityState:
    mode: Mode = "normal"
    user_name: str = "Kullanıcı"


class Personality:
    def __init__(self, user_name: str, default_mode: Mode = "normal") -> None:
        self.state = PersonalityState(mode=default_mode, user_name=user_name)
        self._memory_summary: str = ""

    def set_mode(self, mode: Mode) -> None:
        self.state.mode = mode

    @property
    def mode(self) -> Mode:
        return self.state.mode

    def update_memory_summary(self, summary: str) -> None:
        """Hafıza modülü tarafından besleniyor."""
        self._memory_summary = summary

    def system_prompt(self) -> str:
        now_str = datetime.now().strftime("%A, %d %B %Y, %H:%M")
        base = _BASE_PROMPT.format(
            user_name=self.state.user_name, now=now_str
        )
        tone = _NORMAL_TONE if self.state.mode == "normal" else _SERIOUS_TONE
        tone = tone.format(user_name=self.state.user_name)
        memory_block = ""
        if self._memory_summary:
            memory_block = (
                "\n\n--- Kullanıcı hakkında bildiklerin (uzun vadeli hafıza özeti) ---\n"
                f"{self._memory_summary}\n"
                "Bu bilgileri konuşmada doğal şekilde kullan, ama sürekli tekrarlama."
            )
        return base + tone + memory_block

    def detect_mode_command(self, user_text: str) -> Mode | None:
        """Kullanıcının mod değiştirme talebi var mı?"""
        text = user_text.lower().strip()
        serious_triggers = [
            "ciddi moda geç",
            "ciddi mod",
            "profesyonel mod",
            "görev modu",
            "ciddileş",
        ]
        normal_triggers = [
            "normal moda geç",
            "normal mod",
            "rahat mod",
            "samimi mod",
            "rahatla",
        ]
        if any(t in text for t in serious_triggers):
            return "serious"
        if any(t in text for t in normal_triggers):
            return "normal"
        return None
