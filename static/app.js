const state = {
  voices: [],
  filteredVoices: [],
  selectedGender: 'all',
  deferredPrompt: null,
  androidUrl: window.location.origin,
};

// DOM Elements
const textInput = document.getElementById('textInput');
const audioTitle = document.getElementById('audioTitle');
const charCount = document.getElementById('charCount');
const wordCount = document.getElementById('wordCount');
const chunkCount = document.getElementById('chunkCount');
const estDuration = document.getElementById('estDuration');

const langFilter = document.getElementById('langFilter');
const regionFilter = document.getElementById('regionFilter');
const genderTabs = document.querySelectorAll('.gender-tab');
const voiceSelect = document.getElementById('voiceSelect');
const voiceCountBadge = document.getElementById('voiceCountBadge');
const btnPreviewVoice = document.getElementById('btnPreviewVoice');

const rateSlider = document.getElementById('rateSlider');
const pitchSlider = document.getElementById('pitchSlider');
const volumeSlider = document.getElementById('volumeSlider');
const rateValue = document.getElementById('rateValue');
const pitchValue = document.getElementById('pitchValue');
const volumeValue = document.getElementById('volumeValue');
const pauseMode = document.getElementById('pauseMode');
const presetChips = document.querySelectorAll('.preset-chip');
const btnResetSliders = document.getElementById('btnResetSliders');

const btnSynthesize = document.getElementById('btnSynthesize');
const synthSpinner = document.getElementById('synthSpinner');
const statusMessage = document.getElementById('statusMessage');

const nowPlayingCard = document.getElementById('nowPlayingCard');
const playerTitle = document.getElementById('playerTitle');
const playerSubtitle = document.getElementById('playerSubtitle');
const mainAudioPlayer = document.getElementById('mainAudioPlayer');
const btnDownloadMain = document.getElementById('btnDownloadMain');
const audioVisualizer = document.getElementById('audioVisualizer');

const historyList = document.getElementById('historyList');
const btnRefreshHistory = document.getElementById('btnRefreshHistory');

const fileUpload = document.getElementById('fileUpload');
const btnInsertPause = document.getElementById('btnInsertPause');
const btnSampleText = document.getElementById('btnSampleText');
const btnClearText = document.getElementById('btnClearText');

let previewAudio = null;

// Initialize Service Worker
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {});
  });
}

// Update text statistics
function updateStats() {
  const text = textInput.value;
  const chars = text.length;
  const words = text.trim() ? text.trim().split(/\s+/).length : 0;
  const chunks = chars > 0 ? Math.ceil(chars / 1800) : 0;

  // Average speaking speed ~155 words per minute adjusted by rate
  const rateFactor = 1 + parseInt(rateSlider.value, 10) / 100;
  const totalSec = Math.max(0, Math.round((words / (155 * Math.max(0.4, rateFactor))) * 60));

  charCount.textContent = chars.toLocaleString();
  wordCount.textContent = words.toLocaleString();
  chunkCount.textContent = chunks;

  if (totalSec < 60) {
    estDuration.textContent = `${totalSec}s`;
  } else {
    const mins = Math.floor(totalSec / 60);
    const secs = totalSec % 60;
    estDuration.textContent = `${mins}m ${secs}s`;
  }
}

textInput.addEventListener('input', updateStats);

// Toolbar helpers
const lblFileUpload = document.getElementById('lblFileUpload');
const btnDownloadApkTop = document.getElementById('btnDownloadApkTop');

fileUpload.addEventListener('change', async (e) => {
  const file = e.target.files[0];
  if (!file) return;

  const ext = file.name.split('.').pop().toLowerCase();
  statusMessage.textContent = `📄 Extrayendo texto de "${file.name}"...`;
  if (lblFileUpload) {
    lblFileUpload.style.opacity = '0.6';
  }

  try {
    const formData = new FormData();
    formData.append('file', file);

    const res = await fetch('/api/extract-document', {
      method: 'POST',
      body: formData,
    });

    const data = await res.json();
    if (!res.ok) {
      throw new Error(data.detail || 'No se pudo extraer el texto del archivo.');
    }

    textInput.value = data.text;
    if (!audioTitle.value) {
      audioTitle.value = data.title || file.name.replace(/\.[^/.]+$/, '');
    }
    updateStats();
    const pageInfo = ext === 'pdf' ? ` (${data.pages} pág.)` : '';
    statusMessage.textContent = `✅ Documento "${file.name}"${pageInfo} cargado: ${data.word_count.toLocaleString()} palabras listas para convertir a MP3.`;
  } catch (err) {
    statusMessage.textContent = `❌ ${err.message}`;
  } finally {
    if (lblFileUpload) {
      lblFileUpload.style.opacity = '1';
    }
    fileUpload.value = '';
  }
});

btnInsertPause.addEventListener('click', () => {
  const start = textInput.selectionStart;
  const end = textInput.selectionEnd;
  const val = textInput.value;
  const pauseTag = ' [pausa] ';
  textInput.value = val.slice(0, start) + pauseTag + val.slice(end);
  textInput.focus();
  textInput.selectionStart = textInput.selectionEnd = start + pauseTag.length;
  updateStats();
});

btnSampleText.addEventListener('click', () => {
  audioTitle.value = 'Demo Narración Humanizada';
  textInput.value =
    '¡Hola! Bienvenido a VozIA Studio.\n\n' +
    'Esta es una prueba de voz neuronal humanizada de última generación. ' +
    'Puedes ajustar la velocidad, la entonación y las pausas para crear narraciones de libros, videos, documentales o podcasts.\n\n' +
    '[pausa]\n\n' +
    'Cuando estés listo, presiona el botón de convertir y descarga tu archivo directamente en formato MP3 tanto en tu computadora como en tu teléfono Android.';
  updateStats();
});

btnClearText.addEventListener('click', () => {
  textInput.value = '';
  audioTitle.value = '';
  updateStats();
});

// Load Voices & Filters
async function fetchVoices() {
  try {
    const res = await fetch('/api/voices');
    const data = await res.json();
    state.voices = data.voices || [];
    populateRegionFilter();
    applyVoiceFilters();
  } catch (err) {
    statusMessage.textContent = 'Error cargando catálogo de voces.';
  }
}

function populateRegionFilter() {
  const selectedLang = langFilter.value;
  const regionsMap = new Map();

  state.voices.forEach((v) => {
    if (selectedLang === 'all' || v.lang === selectedLang) {
      if (!regionsMap.has(v.locale)) {
        regionsMap.set(v.locale, v.region_label);
      }
    }
  });

  regionFilter.innerHTML = '<option value="all">Todos los países / acentos</option>';
  for (const [locale, label] of regionsMap.entries()) {
    const opt = document.createElement('option');
    opt.value = locale;
    opt.textContent = label;
    regionFilter.appendChild(opt);
  }
}

function applyVoiceFilters() {
  const lang = langFilter.value;
  const region = regionFilter.value;
  const gender = state.selectedGender;

  const previousSelection = voiceSelect.value;

  state.filteredVoices = state.voices.filter((v) => {
    if (lang !== 'all' && v.lang !== lang) return false;
    if (region !== 'all' && v.locale !== region) return false;
    if (gender !== 'all' && v.gender !== gender) return false;
    return true;
  });

  voiceCountBadge.textContent = state.filteredVoices.length;
  voiceSelect.innerHTML = '';

  state.filteredVoices.forEach((v) => {
    const opt = document.createElement('option');
    opt.value = v.id;
    const icon = v.gender === 'Female' ? '👩' : '👨';
    opt.textContent = `${icon} ${v.name} — ${v.region_label} (${v.gender_label})`;
    voiceSelect.appendChild(opt);
  });

  if (state.filteredVoices.some((v) => v.id === previousSelection)) {
    voiceSelect.value = previousSelection;
  }
}

langFilter.addEventListener('change', () => {
  populateRegionFilter();
  applyVoiceFilters();
});

regionFilter.addEventListener('change', applyVoiceFilters);

genderTabs.forEach((tab) => {
  tab.addEventListener('click', () => {
    genderTabs.forEach((t) => t.classList.remove('active'));
    tab.classList.add('active');
    state.selectedGender = tab.dataset.gender;
    applyVoiceFilters();
  });
});

// Sliders & Presets
function updateSliderLabels() {
  const r = parseInt(rateSlider.value, 10);
  const p = parseInt(pitchSlider.value, 10);
  const v = parseInt(volumeSlider.value, 10);

  rateValue.textContent = r === 0 ? 'Normal (0%)' : `${r > 0 ? '+' : ''}${r}%`;
  pitchValue.textContent = p === 0 ? 'Natural (0 Hz)' : `${p > 0 ? '+' : ''}${p} Hz`;
  volumeValue.textContent = `${100 + v}% (${v >= 0 ? '+' : ''}${v}%)`;
  updateStats();
}

rateSlider.addEventListener('input', updateSliderLabels);
pitchSlider.addEventListener('input', updateSliderLabels);
volumeSlider.addEventListener('input', updateSliderLabels);

btnResetSliders.addEventListener('click', () => {
  rateSlider.value = 0;
  pitchSlider.value = 0;
  volumeSlider.value = 0;
  pauseMode.value = 'natural';
  presetChips.forEach((c) => c.classList.remove('active'));
  presetChips[0].classList.add('active');
  updateSliderLabels();
});

presetChips.forEach((chip) => {
  chip.addEventListener('click', () => {
    presetChips.forEach((c) => c.classList.remove('active'));
    chip.classList.add('active');
    rateSlider.value = chip.dataset.rate;
    pitchSlider.value = chip.dataset.pitch;
    pauseMode.value = chip.dataset.pause;
    updateSliderLabels();
  });
});

// Quick Voice Preview (plays in memory without saving to history or active player)
btnPreviewVoice.addEventListener('click', async () => {
  const selectedVoice = voiceSelect.value;
  if (!selectedVoice) return;

  if (previewAudio) {
    previewAudio.pause();
    previewAudio = null;
  }

  const voiceObj = state.voices.find((v) => v.id === selectedVoice);
  const vName = voiceObj ? voiceObj.name : 'VozIA';

  const samplePrompts = {
    es: `Hola, soy ${vName}. Así suena mi voz neuronal humanizada para tus proyectos y audiolibros.`,
    en: `Hello, I am ${vName}. This is how my realistic neural voice sounds for your projects.`,
    pt: `Olá, eu sou ${vName}. É assim que soa a minha voz neural humanizada.`,
    fr: `Bonjour, je suis ${vName}. Voici ma voix neurale naturelle.`,
  };
  const langKey = voiceObj ? voiceObj.lang : 'es';
  const previewText = samplePrompts[langKey] || samplePrompts.es;

  btnPreviewVoice.disabled = true;
  btnPreviewVoice.textContent = '⏳...';

  try {
    const res = await fetch('/api/synthesize', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        text: previewText,
        voice: selectedVoice,
        rate: parseInt(rateSlider.value, 10),
        pitch: parseInt(pitchSlider.value, 10),
        volume: parseInt(volumeSlider.value, 10),
        pause_mode: pauseMode.value,
        is_preview: true,
      }),
    });
    if (!res.ok) throw new Error('Error en vista previa');
    const blob = await res.blob();
    const blobUrl = URL.createObjectURL(blob);
    previewAudio = new Audio(blobUrl);
    btnPreviewVoice.textContent = '🔊 Sonando...';
    previewAudio.onended = () => {
      btnPreviewVoice.textContent = '🔊 Probar';
      URL.revokeObjectURL(blobUrl);
    };
    await previewAudio.play();
  } catch (e) {
    statusMessage.textContent = 'No se pudo reproducir la muestra.';
    btnPreviewVoice.textContent = '🔊 Probar';
  } finally {
    btnPreviewVoice.disabled = false;
  }
});

// Main Synthesis Action (runs asynchronously in background so switching apps/windows never interrupts it)
let activeJobId = null;

async function pollSynthesisJob(jobId) {
  activeJobId = jobId;
  while (activeJobId === jobId) {
    try {
      const res = await fetch(`/api/synthesize/status/${jobId}`);
      if (res.ok) {
        const job = await res.json();
        if (job.status === 'running') {
          if (job.total_chunks > 1) {
            const pct = Math.min(99, Math.round((job.current_chunk / job.total_chunks) * 100));
            statusMessage.textContent = `⏳ Convirtiendo en segundo plano: bloque ${job.current_chunk} de ${job.total_chunks} (${pct}%)... Puedes cambiar de ventana sin problema.`;
          } else {
            statusMessage.textContent = '⏳ Generando voz neuronal humanizada en segundo plano...';
          }
        } else if (job.status === 'done' && job.record) {
          activeJobId = null;
          btnSynthesize.disabled = false;
          synthSpinner.classList.add('hidden');
          statusMessage.textContent = `✅ ¡Audio MP3 generado en ${job.record.elapsed_sec}s (${job.record.size_kb} KB)!`;
          loadIntoPlayer(job.record, true);
          fetchHistory();
          return;
        } else if (job.status === 'error') {
          activeJobId = null;
          btnSynthesize.disabled = false;
          synthSpinner.classList.add('hidden');
          statusMessage.textContent = `❌ ${job.error || 'Error al sintetizar'}`;
          return;
        }
      }
    } catch (e) {
      // If the app is in background or network hiccups while switching windows, keep polling quietly
    }
    await new Promise((r) => setTimeout(r, 900));
  }
}

document.addEventListener('visibilitychange', () => {
  if (!document.hidden && activeJobId) {
    fetchHistory();
  }
});

btnSynthesize.addEventListener('click', async () => {
  const text = textInput.value.trim();
  if (!text) {
    statusMessage.textContent = '⚠️ Escribe o pega algún texto primero para convertirlo a voz.';
    textInput.focus();
    return;
  }

  btnSynthesize.disabled = true;
  synthSpinner.classList.remove('hidden');
  const chunks = Math.ceil(text.length / 1800);
  statusMessage.textContent =
    chunks > 1
      ? `Iniciando conversión en segundo plano (${chunks} bloques)...`
      : 'Iniciando conversión a voz neuronal en segundo plano...';

  try {
    const res = await fetch('/api/synthesize', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        text,
        voice: voiceSelect.value,
        rate: parseInt(rateSlider.value, 10),
        pitch: parseInt(pitchSlider.value, 10),
        volume: parseInt(volumeSlider.value, 10),
        pause_mode: pauseMode.value,
        title: audioTitle.value.trim() || null,
      }),
    });

    const data = await res.json();
    if (!res.ok) {
      throw new Error(data.detail || 'Error al sintetizar');
    }

    if (data.job_id) {
      await pollSynthesisJob(data.job_id);
    } else {
      statusMessage.textContent = `✅ ¡Audio MP3 generado en ${data.elapsed_sec}s (${data.size_kb} KB)!`;
      loadIntoPlayer(data, true);
      fetchHistory();
      btnSynthesize.disabled = false;
      synthSpinner.classList.add('hidden');
    }
  } catch (err) {
    statusMessage.textContent = `❌ ${err.message}`;
    btnSynthesize.disabled = false;
    synthSpinner.classList.add('hidden');
  }
});

function loadIntoPlayer(item, autoPlay = false) {
  nowPlayingCard.classList.remove('hidden');
  playerTitle.textContent = item.title;
  playerSubtitle.textContent = `Voz: ${item.voice} • Tamaño: ${item.size_kb} KB • ${item.word_count || 0} palabras`;
  mainAudioPlayer.src = item.url;
  btnDownloadMain.href = item.download_url;
  btnDownloadMain.setAttribute('download', item.filename);

  if (autoPlay) {
    mainAudioPlayer.play().catch(() => {});
  }
}

mainAudioPlayer.addEventListener('play', () => {
  audioVisualizer.classList.add('playing');
});
mainAudioPlayer.addEventListener('pause', () => {
  audioVisualizer.classList.remove('playing');
});
mainAudioPlayer.addEventListener('ended', () => {
  audioVisualizer.classList.remove('playing');
});

// History Management
async function fetchHistory() {
  try {
    const res = await fetch('/api/history');
    const data = await res.json();
    renderHistory(data.history || []);
  } catch (e) {
    historyList.innerHTML = '<div class="empty-state">No se pudo cargar el historial.</div>';
  }
}

function renderHistory(items) {
  if (!items.length) {
    historyList.innerHTML =
      '<div class="empty-state">Aún no has generado audios. ¡Escribe un texto arriba y conviértelo en MP3!</div>';
    return;
  }

  historyList.innerHTML = '';
  items.forEach((item) => {
    const row = document.createElement('div');
    row.className = 'history-item';

    row.innerHTML = `
      <div class="history-item-left">
        <div class="history-item-title">${escapeHtml(item.title)}</div>
        <div class="history-item-preview">${escapeHtml(item.text_preview)}</div>
        <div class="history-badges">
          <span class="badge-mini">🎙️ ${escapeHtml(item.voice)}</span>
          <span class="badge-mini">📦 ${item.size_kb} KB</span>
          <span class="badge-mini">📝 ${item.char_count} caract.</span>
          <span class="badge-mini">🕒 ${item.created_at}</span>
        </div>
      </div>
      <div class="history-item-actions">
        <button type="button" class="btn-chip btn-play-item">▶️ Escuchar</button>
        <button type="button" class="btn-chip btn-reuse-item" title="Cargar texto en el editor">📝 Reutilizar</button>
        <a href="${item.download_url}" download="${item.filename}" class="btn btn-success" style="padding: 0.45rem 0.85rem; font-size: 0.8rem;">
          ⬇️ MP3
        </a>
        <button type="button" class="btn-chip btn-chip-danger btn-delete-item" title="Eliminar audio">🗑️</button>
      </div>
    `;

    row.querySelector('.btn-play-item').addEventListener('click', () => {
      loadIntoPlayer(item, true);
      nowPlayingCard.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });

    row.querySelector('.btn-reuse-item').addEventListener('click', () => {
      textInput.value = item.full_text || item.text_preview;
      audioTitle.value = item.title;
      updateStats();
      window.scrollTo({ top: 0, behavior: 'smooth' });
    });

    row.querySelector('.btn-delete-item').addEventListener('click', async () => {
      await fetch(`/api/history/${item.id}`, { method: 'DELETE' });
      fetchHistory();
    });

    historyList.appendChild(row);
  });
}

btnRefreshHistory.addEventListener('click', fetchHistory);

function escapeHtml(str) {
  if (!str) return '';
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

async function checkActiveJobOnBoot() {
  try {
    const res = await fetch('/api/synthesize/active');
    if (res.ok) {
      const data = await res.json();
      if (data.active_job && data.active_job.job_id) {
        btnSynthesize.disabled = true;
        synthSpinner.classList.remove('hidden');
        pollSynthesisJob(data.active_job.job_id);
      }
    }
  } catch (e) {}
}

// Initial boot
fetchVoices();
fetchHistory();
checkActiveJobOnBoot();
updateStats();

