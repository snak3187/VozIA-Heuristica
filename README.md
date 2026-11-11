<p align="center">
  <img src="static/logo_round.png" alt="VozIA Heuristica Logo" width="120" />
</p>

<h1 align="center">VozIA Heuristica — Texto a Voz Neuronal Humanizada (PC + Android APK)</h1>

Aplicación multiplataforma para **Escritorio (Windows / PC)** y **Android (APK autónoma)** diseñada para convertir textos, libros completos, documentos **PDF (`.pdf`)** y **Word (`.docx`)** a voz neuronal humanizada con descarga directa en formato **MP3**.

---

## Características Principales

- **Catálogo de +320 Voces Neuronales Humanizadas:** Voces en Español (México, España, Argentina, Colombia, Chile, Perú, Estados Unidos, entre otros), además de Inglés, Portugués, Francés, Alemán e Italiano.
- **Importación de Documentos:** Extracción automática de texto a partir de archivos `.PDF`, `.DOCX` (Microsoft Word) y archivos de texto plano (`.TXT`, `.MD`, `.SRT`).
- **Soporte para Libros y Textos Extensos:** Segmentación jerárquica por párrafos y oraciones que preserva la entonación natural y concatena el resultado en un único archivo MP3 continuo.
- **Control de Prosodia y Expresión:** Ajuste de velocidad de lectura, frecuencia tonal (`Hz`), ganancia de volumen, inserción de pausas (`[pausa]`) y perfiles preconfigurados (*Natural*, *Audiolibro*, *Dinámico*, *Documental*).
- **Ejecución Persistente en Segundo Plano:** Continúa el proceso de conversión al cambiar de ventana, minimizar la aplicación o bloquear la pantalla del dispositivo (`ForegroundService` + `WakeLock` en Android y `SetThreadExecutionState` en Windows).
- **Previsualización en Memoria:** Función de prueba de voz instantánea que reproduce muestras directamente en memoria sin almacenar archivos residuales en el historial.

---

## Arquitectura y Especificaciones Técnicas

- **Motor de Síntesis Neuronal (TTS):**
  - Flujo de audio continuo a **24 kHz / 48 kbps CBR Mono MP3** (`audio-24khz-48kbitrate-mono-mp3`).
  - Generación dinámica de SSML para el control de parámetros acústicos y prosódicos.
  - **Sincronización criptográfica `Sec-MS-GEC` v2 (SHA-256 + CS64):** Ajuste automático de desfase horario (*Clock Skew Correction*) mediante encabezados RFC 2616 `Date` para garantizar la validez del token en dispositivos con reloj desincronizado.

- **Arquitectura Autónoma en Android (Sin Dependencia de PC):**
  - Implementación nativa en Java (`EdgeTtsEngine.java`) del protocolo WebSocket seguro (`WSS/TLS` sobre `SSLSocket` RFC 6455).
  - Microservidor HTTP embebido (`LocalVozIAServer.java`) multihilo sobre `127.0.0.1` con soporte para `HTTP 206 Partial Content` y exportación directa a `MediaStore.Downloads`.
  - Servicio en segundo plano (`VozIABackgroundService.java`) con `PowerManager.PARTIAL_WAKE_LOCK` y `WifiManager.WIFI_MODE_FULL_HIGH_PERF`.

- **Arquitectura en Escritorio (Windows / Python):**
  - Servidor asíncrono basado en **FastAPI** y **Uvicorn** con gestión de tareas concurrentes (`asyncio.Task`) y reporte de progreso por bloques.
  - Prevención de suspensión del sistema operativo mediante la API Win32 `ctypes.windll.kernel32.SetThreadExecutionState`.

---

## Instalación en Android (APK Autónoma)

La aplicación de Android integra su propio motor de síntesis y procesamiento documental, por lo que funciona de manera independiente sin requerir conexión con una computadora:

1. Descargue el archivo **[`VozIA_Studio.apk`](./VozIA_Studio.apk)** incluido en este repositorio.
2. Ejecute el instalador en su dispositivo Android.
3. Los archivos MP3 generados se almacenan directamente en la carpeta **Descargas (`Downloads`)** del dispositivo.

---

## Ejecución en Computadora (Windows)

### Inicio rápido
1. Asegúrese de contar con **Python 3.10 o superior**.
2. Ejecute **`Iniciar_VozIA.bat`**.
   - En la primera ejecución, el script creará automáticamente el entorno virtual `.venv` e instalará las dependencias definidas en `requirements.txt`.
   - La interfaz se abrirá en una ventana de aplicación dedicada.

### Ejecución manual desde terminal
```bash
python -m venv .venv
.\.venv\Scripts\pip install -r requirements.txt
.\.venv\Scripts\python -m uvicorn app:app --host 0.0.0.0 --port 8000
```
Posteriormente, acceda a `http://localhost:8000` desde su navegador.

---

## Compilación del APK desde el Código Fuente

El proyecto nativo de Android se encuentra en el directorio `android-app/`:

```bash
cd android-app
.\gradlew.bat assembleRelease
```
El paquete compilado se generará en `android-app/app/build/outputs/apk/release/app-release.apk`.
