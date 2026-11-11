<p align="center">
  <img src="static/logo_round.png" alt="VozIA Heuristica Logo" width="120" />
</p>

<h1 align="center">VozIA Heuristica — Texto a Voz Neuronal Humanizada (PC + Android APK)</h1>

Aplicación de **Escritorio (Windows / PC)** y **Android (APK 100% autónoma)** para convertir textos, libros completos, documentos **PDF (`.pdf`)** y **Word (`.docx`)** a voz neuronal humanizada con descarga directa en formato **MP3**.

---

## ✨ Características Principales

- 🎙️ **+320 Voces Neuronales Humanizadas** (Español de México, España, Argentina, Colombia, Chile, Perú, EE.UU., además de Inglés, Portugués, Francés, Alemán, Italiano y más).
- 📄 **Importación de Documentos:** Sube archivos `.PDF`, `.DOCX` (Word) o `.TXT` / `.MD` y extrae todo el texto automáticamente.
- 📚 **Soporte para Libros y Textos Largos:** Divide textos extensos por párrafos de forma inteligente y los une en **un solo archivo MP3** continuo.
- 🎚️ **Control de Prosodia y Expresión:** Ajusta velocidad, tono (`Hz`), volumen, pausas (`[pausa]`) y perfiles preconfigurados (*Natural*, *Audiolibro*, *Dinámico / Reels*, *Documental*).
- 🛡️ **Conversión en Segundo Plano:** Sigue convirtiendo aunque cambies de ventana, minimices la aplicación o apagues la pantalla del celular (`ForegroundService` + `WakeLock` en Android y `SetThreadExecutionState` en Windows).
- 🔊 **Vista Previa Instantánea:** Botón **🔊 Probar** que reproduce la muestra de voz en memoria sin ensuciar el historial ni el reproductor principal.

---

## 📱 Instalación en Android (APK Autónoma)

No necesitas tener la computadora encendida. La aplicación de Android incluye su propio motor neuronal interno:

1. Descarga el archivo **[`VozIA_Studio.apk`](./VozIA_Studio.apk)** incluido en este repositorio.
2. Ábrelo en tu teléfono Android e instálalo.
3. Tus audios convertidos se descargan directamente en la carpeta **Descargas (`Downloads`)** de tu teléfono.

---

## 💻 Ejecución en Computadora (Windows)

### Opción rápida (con doble clic)
1. Asegúrate de tener instalado **Python 3.10+**.
2. Haz doble clic en **`Iniciar_VozIA.bat`**.
   - La primera vez creará automáticamente el entorno virtual `.venv` e instalará las dependencias de `requirements.txt`.
   - Se abrirá automáticamente en una ventana dedicada de escritorio.

### Opción manual por terminal
```bash
python -m venv .venv
.\.venv\Scripts\pip install -r requirements.txt
.\.venv\Scripts\python -m uvicorn app:app --host 0.0.0.0 --port 8000
```
Luego abre `http://localhost:8000` en tu navegador.

---

## 🛠️ Compilar el APK de Android desde el código fuente (Opcional)

El código fuente completo de la aplicación nativa de Android está en la carpeta `android-app/`:

```bash
cd android-app
.\gradlew.bat assembleRelease
```
El APK compilado se generará en `android-app/app/build/outputs/apk/release/app-release.apk`.

