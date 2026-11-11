import asyncio
import ctypes
import io
import json
import os
import re
import shutil
import socket
import time
import urllib.request
import uuid
import zipfile
from email.utils import parsedate_to_datetime
from pathlib import Path
from typing import List, Optional
from xml.etree import ElementTree as ET

import edge_tts
from edge_tts.constants import TRUSTED_CLIENT_TOKEN, VOICE_HEADERS, VOICE_LIST
from edge_tts.drm import DRM
from fastapi import FastAPI, HTTPException, UploadFile, File
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

try:
    from pypdf import PdfReader
except ImportError:
    PdfReader = None

try:
    import docx
except ImportError:
    docx = None

BASE_DIR = Path(__file__).resolve().parent
STATIC_DIR = BASE_DIR / "static"
OUTPUT_DIR = BASE_DIR / "outputs"
HISTORY_FILE = OUTPUT_DIR / "history.json"

STATIC_DIR.mkdir(exist_ok=True)
OUTPUT_DIR.mkdir(exist_ok=True)

app = FastAPI(title="VozIA Studio - Texto a Voz Humanizada")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Cache for voices list
_VOICES_CACHE: Optional[List[dict]] = None
_SYNTH_JOBS: dict = {}
_CLOCK_SYNCED: bool = False


def sync_edge_clock_skew(force: bool = False) -> None:
    """
    Synchronizes edge_tts DRM clock skew against Microsoft's server Date header
    so that synthesis never fails with HTTP 403 Forbidden if the PC clock is off.
    """
    global _CLOCK_SYNCED
    if _CLOCK_SYNCED and not force:
        return
    try:
        req = urllib.request.Request(VOICE_LIST, method="HEAD", headers=VOICE_HEADERS)
        with urllib.request.urlopen(req, timeout=8) as resp:
            date_hdr = resp.headers.get("Date")
            if date_hdr:
                parsed = parsedate_to_datetime(date_hdr)
                server_ts = parsed.timestamp()
                local_ts = DRM.get_unix_timestamp()
                DRM.adj_clock_skew_seconds(server_ts - local_ts)
                _CLOCK_SYNCED = True
    except Exception:
        pass


def set_windows_keep_awake(awake: bool) -> None:
    """Prevents Windows from sleeping while a background synthesis job is active."""
    if os.name != "nt":
        return
    try:
        ES_CONTINUOUS = 0x80000000
        ES_SYSTEM_REQUIRED = 0x00000001
        flags = (ES_CONTINUOUS | ES_SYSTEM_REQUIRED) if awake else ES_CONTINUOUS
        ctypes.windll.kernel32.SetThreadExecutionState(flags)
    except Exception:
        pass

COUNTRY_NAMES = {
    "es-MX": "México 🇲🇽",
    "es-ES": "España 🇪🇸",
    "es-AR": "Argentina 🇦🇷",
    "es-CO": "Colombia 🇨🇴",
    "es-CL": "Chile 🇨🇱",
    "es-PE": "Perú 🇵🇪",
    "es-VE": "Venezuela 🇻🇪",
    "es-US": "EE.UU. (Español) 🇺🇸",
    "es-UY": "Uruguay 🇺🇾",
    "es-CR": "Costa Rica 🇨🇷",
    "es-EC": "Ecuador 🇪🇨",
    "es-GT": "Guatemala 🇬🇹",
    "es-PA": "Panamá 🇵🇦",
    "es-DO": "Rep. Dominicana 🇩🇴",
    "es-PR": "Puerto Rico 🇵🇷",
    "es-PY": "Paraguay 🇵🇾",
    "es-SV": "El Salvador 🇸🇻",
    "es-HN": "Honduras 🇭🇳",
    "es-NI": "Nicaragua 🇳🇮",
    "es-BO": "Bolivia 🇧🇴",
    "es-CU": "Cuba 🇨🇺",
    "es-GQ": "Guinea Ecuatorial 🇬🇶",
    "en-US": "EE.UU. (Inglés) 🇺🇸",
    "en-GB": "Reino Unido 🇬🇧",
    "en-AU": "Australia 🇦🇺",
    "en-CA": "Canadá 🇨🇦",
    "pt-BR": "Brasil 🇧🇷",
    "pt-PT": "Portugal 🇵🇹",
    "fr-FR": "Francia 🇫🇷",
    "fr-CA": "Canadá (Francés) 🇨🇦",
    "de-DE": "Alemania 🇩🇪",
    "it-IT": "Italia 🇮🇹",
    "ja-JP": "Japón 🇯🇵",
    "ko-KR": "Corea del Sur 🇰🇷",
    "zh-CN": "China 🇨🇳",
}

LANGUAGE_NAMES = {
    "es": "Español",
    "en": "Inglés",
    "pt": "Portugués",
    "fr": "Francés",
    "de": "Alemán",
    "it": "Italiano",
    "ja": "Japonés",
    "ko": "Coreano",
    "zh": "Chino",
}


class SynthesizeRequest(BaseModel):
    text: str
    voice: str = "es-MX-DaliaNeural"
    rate: int = 0       # -50 to +100 (%)
    pitch: int = 0      # -50 to +50 (Hz)
    volume: int = 0     # -50 to +50 (%)
    pause_mode: str = "natural"  # "none", "natural", "narrator"
    title: Optional[str] = None
    is_preview: bool = False


def load_history() -> List[dict]:
    if not HISTORY_FILE.exists():
        return []
    try:
        with open(HISTORY_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
            # Filter out entries whose MP3 no longer exists
            valid = [item for item in data if (OUTPUT_DIR / item.get("filename", "")).exists()]
            return valid
    except Exception:
        return []


def save_history(items: List[dict]) -> None:
    with open(HISTORY_FILE, "w", encoding="utf-8") as f:
        json.dump(items[:100], f, ensure_ascii=False, indent=2)


def get_local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def preprocess_text_and_chunk(text: str, pause_mode: str = "natural", max_chars: int = 1800) -> List[str]:
    """
    Cleans text, handles custom pause tags like [pausa] or [pausa 1s],
    and splits long texts/books into natural sentence/paragraph chunks
    so that synthesis never times out and retains natural prosody.
    """
    text = text.strip()
    if not text:
        return []

    # Normalize custom pause markers into natural ellipsis/paragraph breaks for Edge-TTS
    text = re.sub(r"\[pausa(?:\s+\d+(?:\.\d+)?s)?\]", " ... \n\n", text, flags=re.IGNORECASE)

    if pause_mode == "narrator":
        # Add slightly more breathing room between paragraphs
        text = re.sub(r"\n\s*\n", "\n\n...\n\n", text)

    paragraphs = [p.strip() for p in re.split(r"\n+", text) if p.strip()]
    chunks: List[str] = []
    current_chunk = ""

    for para in paragraphs:
        if len(current_chunk) + len(para) + 2 <= max_chars:
            current_chunk = f"{current_chunk}\n\n{para}".strip() if current_chunk else para
        else:
            if current_chunk:
                chunks.append(current_chunk)
                current_chunk = ""
            if len(para) <= max_chars:
                current_chunk = para
            else:
                # Split very long paragraph by sentences
                sentences = re.split(r"(?<=[.!?…])\s+", para)
                for sent in sentences:
                    if len(current_chunk) + len(sent) + 1 <= max_chars:
                        current_chunk = f"{current_chunk} {sent}".strip() if current_chunk else sent
                    else:
                        if current_chunk:
                            chunks.append(current_chunk)
                        # If a single sentence is still huge, slice by words
                        if len(sent) > max_chars:
                            words = sent.split(" ")
                            temp = ""
                            for w in words:
                                if len(temp) + len(w) + 1 <= max_chars:
                                    temp = f"{temp} {w}".strip() if temp else w
                                else:
                                    chunks.append(temp)
                                    temp = w
                            current_chunk = temp
                        else:
                            current_chunk = sent

    if current_chunk:
        chunks.append(current_chunk)

    return chunks


@app.get("/api/info")
async def get_server_info():
    ip = get_local_ip()
    return {
        "local_ip": ip,
        "port": 8000,
        "android_url": f"http://{ip}:8000",
        "apk_available": (OUTPUT_DIR / "VozIA_Studio.apk").exists(),
        "apk_url": "/api/download-apk",
    }


def extract_docx_fallback(raw_bytes: bytes) -> str:
    """Fallback DOCX text extractor using built-in zipfile + XML if python-docx is unavailable."""
    with zipfile.ZipFile(io.BytesIO(raw_bytes)) as zf:
        xml_content = zf.read("word/document.xml")
    tree = ET.fromstring(xml_content)
    ns = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
    paragraphs = []
    for p in tree.findall(".//w:p", ns):
        texts = [t.text for t in p.findall(".//w:t", ns) if t.text]
        if texts:
            paragraphs.append("".join(texts))
    return "\n\n".join(paragraphs)


@app.post("/api/extract-document")
async def extract_document(file: UploadFile = File(...)):
    filename = file.filename or "documento"
    ext = Path(filename).suffix.lower()
    raw_bytes = await file.read()

    if not raw_bytes:
        raise HTTPException(status_code=400, detail="El archivo subido está vacío.")

    extracted_text = ""
    pages_count = 1

    try:
        if ext == ".pdf":
            if PdfReader is None:
                raise HTTPException(status_code=500, detail="Librería pypdf no instalada en el servidor.")
            reader = PdfReader(io.BytesIO(raw_bytes))
            pages_count = len(reader.pages)
            page_texts = []
            for idx, page in enumerate(reader.pages):
                txt = (page.extract_text() or "").strip()
                if txt:
                    page_texts.append(txt)
            extracted_text = "\n\n".join(page_texts)

        elif ext == ".docx":
            if docx is not None:
                doc = docx.Document(io.BytesIO(raw_bytes))
                paras = [p.text.strip() for p in doc.paragraphs if p.text and p.text.strip()]
                # Also extract text from tables if present
                for table in doc.tables:
                    for row in table.rows:
                        row_txt = " | ".join(cell.text.strip() for cell in row.cells if cell.text.strip())
                        if row_txt:
                            paras.append(row_txt)
                extracted_text = "\n\n".join(paras)
            else:
                extracted_text = extract_docx_fallback(raw_bytes)

        elif ext in [".txt", ".md", ".srt", ".csv"]:
            for encoding in ("utf-8", "utf-8-sig", "latin-1", "cp1252"):
                try:
                    extracted_text = raw_bytes.decode(encoding)
                    break
                except UnicodeDecodeError:
                    continue
        else:
            raise HTTPException(
                status_code=400,
                detail=f"Formato '{ext}' no soportado. Sube un archivo PDF (.pdf), Word (.docx) o Texto (.txt, .md).",
            )
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"No se pudo leer el documento ({filename}): {str(e)}")

    # Clean up excessive whitespace while keeping paragraph structure
    extracted_text = re.sub(r"[ \t]+", " ", extracted_text)
    extracted_text = re.sub(r"\n{3,}", "\n\n", extracted_text).strip()

    if not extracted_text:
        raise HTTPException(
            status_code=400,
            detail="No se encontró texto seleccionable en el archivo (si es un PDF escaneado como imagen, requiere OCR).",
        )

    clean_title = Path(filename).stem
    return {
        "title": clean_title,
        "filename": filename,
        "extension": ext,
        "pages": pages_count,
        "char_count": len(extracted_text),
        "word_count": len(extracted_text.split()),
        "text": extracted_text,
    }


@app.get("/api/download-apk")
async def download_apk():
    apk_path = OUTPUT_DIR / "VozIA_Studio.apk"
    if not apk_path.exists():
        raise HTTPException(status_code=404, detail="El archivo APK aún no está disponible.")
    return FileResponse(
        apk_path,
        media_type="application/vnd.android.package-archive",
        filename="VozIA_Studio.apk",
        headers={"Content-Disposition": 'attachment; filename="VozIA_Studio.apk"'},
    )


@app.get("/api/voices")
async def get_voices():
    global _VOICES_CACHE
    if _VOICES_CACHE is None:
        try:
            await asyncio.to_thread(sync_edge_clock_skew)
            raw_voices = await edge_tts.list_voices()
            processed = []
            for v in raw_voices:
                locale = v.get("Locale", "")
                lang_code = locale.split("-")[0] if "-" in locale else locale
                short_name = v.get("ShortName", "")
                # Friendly display name (e.g., "Dalia" from "es-MX-DaliaNeural")
                clean_name = short_name.replace(f"{locale}-", "").replace("Neural", "")
                is_multilingual = "Multilingual" in clean_name
                if is_multilingual:
                    clean_name = clean_name.replace("Multilingual", " (Multilingüe)")

                gender_raw = v.get("Gender", "Female")
                gender_es = "Femenina" if gender_raw == "Female" else "Masculina"

                processed.append({
                    "id": short_name,
                    "name": clean_name,
                    "locale": locale,
                    "lang": lang_code,
                    "lang_label": LANGUAGE_NAMES.get(lang_code, lang_code.upper()),
                    "region_label": COUNTRY_NAMES.get(locale, locale),
                    "gender": gender_raw,
                    "gender_label": gender_es,
                    "multilingual": is_multilingual,
                    "personalities": v.get("VoiceTag", {}).get("VoicePersonalities", []),
                })

            # Sort with Spanish first (Mexico, Spain, Argentina, Colombia, US, etc.), then other languages
            priority_locales = ["es-MX", "es-ES", "es-AR", "es-CO", "es-US", "es-CL", "es-PE", "es-VE"]

            def sort_key(item):
                loc = item["locale"]
                lang = item["lang"]
                if loc in priority_locales:
                    return (0, priority_locales.index(loc), item["name"])
                if lang == "es":
                    return (1, loc, item["name"])
                if lang in LANGUAGE_NAMES:
                    return (2, LANGUAGE_NAMES[lang], loc, item["name"])
                return (3, loc, item["name"])

            processed.sort(key=sort_key)
            _VOICES_CACHE = processed
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"Error obteniendo voces: {str(e)}")

    return {"voices": _VOICES_CACHE}


async def _stream_chunk_with_retry(chunk: str, voice: str, rate_str: str, pitch_str: str, volume_str: str) -> bytes:
    await asyncio.to_thread(sync_edge_clock_skew)
    for attempt in range(2):
        buf = bytearray()
        try:
            communicate = edge_tts.Communicate(
                text=chunk,
                voice=voice,
                rate=rate_str,
                pitch=pitch_str,
                volume=volume_str,
            )
            async for message in communicate.stream():
                if message["type"] == "audio":
                    buf.extend(message["data"])
            return bytes(buf)
        except Exception as e:
            if attempt == 0 and "403" in str(e):
                await asyncio.to_thread(sync_edge_clock_skew, True)
                continue
            raise


async def _run_synthesis_job(job_id: str, req: SynthesizeRequest, chunks: List[str]):
    rate_str = f"{req.rate:+d}%"
    pitch_str = f"{req.pitch:+d}%" if False else f"{req.pitch:+d}Hz"
    volume_str = f"{req.volume:+d}%"
    text = req.text.strip()

    safe_title = re.sub(r"[^a-zA-Z0-9_\-\sáéíóúÁÉÍÓÚñÑ]", "", req.title or text[:35]).strip()
    safe_title = re.sub(r"\s+", "_", safe_title)[:40] or "audio_vozia"
    filename = f"{safe_title}_{job_id}.mp3"
    output_path = OUTPUT_DIR / filename

    start_t = time.time()
    set_windows_keep_awake(True)
    try:
        with open(output_path, "wb") as out_file:
            for idx, chunk in enumerate(chunks):
                _SYNTH_JOBS[job_id]["current_chunk"] = idx + 1
                part_bytes = await _stream_chunk_with_retry(chunk, req.voice, rate_str, pitch_str, volume_str)
                out_file.write(part_bytes)

        elapsed = round(time.time() - start_t, 2)
        file_size_kb = round(output_path.stat().st_size / 1024, 1)

        record = {
            "id": job_id,
            "title": req.title.strip() if req.title and req.title.strip() else (text[:55] + ("..." if len(text) > 55 else "")),
            "text_preview": text[:160] + ("..." if len(text) > 160 else ""),
            "full_text": text[:5000],
            "char_count": len(text),
            "word_count": len(text.split()),
            "chunks_count": len(chunks),
            "voice": req.voice,
            "rate": req.rate,
            "pitch": req.pitch,
            "volume": req.volume,
            "filename": filename,
            "url": f"/api/audio/{filename}",
            "download_url": f"/api/download/{filename}",
            "size_kb": file_size_kb,
            "elapsed_sec": elapsed,
            "created_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        }

        history = load_history()
        history.insert(0, record)
        save_history(history)

        _SYNTH_JOBS[job_id]["status"] = "done"
        _SYNTH_JOBS[job_id]["record"] = record
    except Exception as e:
        if output_path.exists():
            output_path.unlink(missing_ok=True)
        _SYNTH_JOBS[job_id]["status"] = "error"
        _SYNTH_JOBS[job_id]["error"] = f"Error al generar el audio: {str(e)}"
    finally:
        if not any(j.get("status") == "running" for j in _SYNTH_JOBS.values()):
            set_windows_keep_awake(False)


@app.post("/api/synthesize")
async def synthesize_speech(req: SynthesizeRequest):
    text = req.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="El texto no puede estar vacío.")

    chunks = preprocess_text_and_chunk(text, pause_mode=req.pause_mode)
    if not chunks:
        raise HTTPException(status_code=400, detail="No se encontró texto válido para convertir.")

    rate_str = f"{req.rate:+d}%"
    pitch_str = f"{req.pitch:+d}Hz"
    volume_str = f"{req.volume:+d}%"

    if req.is_preview:
        audio_bytes = bytearray()
        try:
            for chunk in chunks:
                part = await _stream_chunk_with_retry(chunk, req.voice, rate_str, pitch_str, volume_str)
                audio_bytes.extend(part)
            return Response(content=bytes(audio_bytes), media_type="audio/mpeg")
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"Error en prueba de voz: {str(e)}")

    job_id = uuid.uuid4().hex[:10]
    _SYNTH_JOBS[job_id] = {
        "job_id": job_id,
        "status": "running",
        "current_chunk": 0,
        "total_chunks": len(chunks),
    }
    asyncio.create_task(_run_synthesis_job(job_id, req, chunks))
    return _SYNTH_JOBS[job_id]


@app.get("/api/synthesize/status/{job_id}")
async def get_synthesis_status(job_id: str):
    job = _SYNTH_JOBS.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Tarea de conversión no encontrada.")
    return job


@app.get("/api/synthesize/active")
async def get_active_synthesis_job():
    for job in reversed(list(_SYNTH_JOBS.values())):
        if job.get("status") == "running":
            return {"active_job": job}
    return {"active_job": None}


@app.get("/api/history")
async def get_history():
    return {"history": load_history()}


@app.delete("/api/history/{item_id}")
async def delete_history_item(item_id: str):
    history = load_history()
    target = next((item for item in history if item["id"] == item_id), None)
    if not target:
        raise HTTPException(status_code=404, detail="Audio no encontrado.")

    file_path = OUTPUT_DIR / target["filename"]
    if file_path.exists():
        file_path.unlink(missing_ok=True)

    updated = [item for item in history if item["id"] != item_id]
    save_history(updated)
    return {"ok": True}


@app.get("/api/audio/{filename}")
async def stream_audio(filename: str):
    safe_name = os.path.basename(filename)
    file_path = OUTPUT_DIR / safe_name
    if not file_path.exists():
        raise HTTPException(status_code=404, detail="Archivo MP3 no encontrado.")
    return FileResponse(file_path, media_type="audio/mpeg")


@app.get("/api/download/{filename}")
async def download_audio(filename: str):
    safe_name = os.path.basename(filename)
    file_path = OUTPUT_DIR / safe_name
    if not file_path.exists():
        raise HTTPException(status_code=404, detail="Archivo MP3 no encontrado.")
    try:
        user_downloads = Path.home() / "Downloads"
        if user_downloads.exists():
            shutil.copy2(file_path, user_downloads / safe_name)
    except Exception:
        pass
    return FileResponse(
        file_path,
        media_type="audio/mpeg",
        filename=safe_name,
        headers={"Content-Disposition": f'attachment; filename="{safe_name}"'},
    )


app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")


@app.get("/manifest.json")
async def serve_manifest():
    return FileResponse(STATIC_DIR / "manifest.json", media_type="application/manifest+json")


@app.get("/sw.js")
async def serve_sw():
    return FileResponse(STATIC_DIR / "sw.js", media_type="application/javascript")


@app.get("/")
async def serve_index():
    return FileResponse(STATIC_DIR / "index.html")

