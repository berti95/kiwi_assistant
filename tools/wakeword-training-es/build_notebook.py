#!/usr/bin/env python3
"""Build the Spanish wake-word training Colab notebook.

The notebook itself (``train_es.ipynb``) is the artifact people open in
Colab. We generate it from this script rather than hand-writing the
JSON because the cell sources contain a lot of multi-line bash + python
that's much easier to read in Python source than embedded inside a
``"source": ["..."]`` array.

Run from this directory:
    python build_notebook.py
"""
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from textwrap import dedent

HERE = Path(__file__).parent


def _current_git_commit() -> str:
    try:
        return (
            subprocess.check_output(
                ["git", "rev-parse", "--short", "HEAD"],
                cwd=HERE,
                stderr=subprocess.DEVNULL,
            )
            .decode()
            .strip()
        )
    except Exception:
        return "unknown"


NOTEBOOK_VERSION = (
    f"{datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')} · {_current_git_commit()}"
)


def md(*lines: str) -> dict:
    return {
        "cell_type": "markdown",
        "metadata": {},
        "source": [_with_newlines(lines)],
    }


def code(*lines: str) -> dict:
    return {
        "cell_type": "code",
        "metadata": {},
        "execution_count": None,
        "outputs": [],
        "source": [_with_newlines(lines)],
    }


def _with_newlines(lines: tuple[str, ...]) -> str:
    return "\n".join(dedent(line).strip("\n") for line in lines)


CELLS: list[dict] = [
    md(
        f"""
        # Kiwi — Entrena tu propia palabra de activación en español

        Este notebook entrena un modelo de wake-word para
        [openWakeWord](https://github.com/dscripka/openWakeWord) con una
        frase **en español** (por ejemplo, *"hola kiwi"*) — algo que el
        notebook oficial no soporta porque está cableado a inglés.

        El truco: pre-generamos las muestras positivas con
        [Piper TTS](https://github.com/rhasspy/piper) usando voces
        `es_ES-*` y `es_MX-*`, y dejamos que la pipeline oficial de
        openWakeWord se encargue del resto (augmentación, negativos,
        entrenamiento, exportación a ONNX).

        **Tiempo total estimado**: 30–60 min en una sesión Colab gratis con GPU
        (Runtime → Change runtime type → T4 GPU). El paso de descarga de
        AudioSet/RIRs/features es el que más tarda.

        **Salida**: un fichero `<frase>.onnx` (~1 MB) que sustituye al actual
        `hey_jarvis.onnx` en `android/app/src/main/assets/wakeword/`.

        ---

        > 📌 **Versión del notebook**: `{NOTEBOOK_VERSION}` —
        > si Colab te carga otra cosa, no estás en la última. Recarga la
        > página (o ábrela en pestaña de incógnito).
        """
    ),
    code(
        f"""
        # Sanity check: imprime la versión del notebook al lanzarlo. Si lo que
        # ves aquí no coincide con la última versión del repo en
        # github.com/berti95/kiwi_assistant/commits/, Colab te ha cacheado una
        # versión vieja — recarga la página antes de seguir.
        print("📌 Notebook version: {NOTEBOOK_VERSION}")
        """
    ),
    md(
        """
        ## 1. Configuración (lo único que necesitas tocar)
        """
    ),
    code(
        """
        # ⬇️  Cambia esto a la frase que quieras como wake word.
        # Recomendaciones: 3–5 sílabas, vocales claras, sin sonidos parecidos a
        # palabras comunes ("hola", "café", "jarvis" en inglés son malas).
        TARGET_PHRASE = "hola kiwi"

        # Cuántas muestras generar. Más = mejor modelo, más tiempo.
        # 2000 es razonable para un primer intento.
        N_SAMPLES = 2000
        N_SAMPLES_VAL = 500

        # Pasos de entrenamiento. 10000 está bien para empezar.
        TRAINING_STEPS = 10000

        # Umbrales de "modelo aceptable". Si el entrenamiento no los alcanza
        # en TRAINING_STEPS, baja un poco. Si llega rápido, súbelos.
        TARGET_ACCURACY = 0.6
        TARGET_RECALL = 0.25

        MODEL_NAME = TARGET_PHRASE.replace(" ", "_").lower()
        print(f"→ Frase objetivo: '{TARGET_PHRASE}'")
        print(f"→ Modelo se llamará: {MODEL_NAME}.onnx")
        """
    ),
    md(
        """
        ## 2. Instalación

        Descargamos openWakeWord (su pipeline completa de entrenamiento) y
        Piper TTS (con voces españolas para generar los positivos).
        """
    ),
    code(
        """
        # openWakeWord — usamos su training pipeline tal cual.
        !git clone https://github.com/dscripka/openwakeword
        !pip install -q -e ./openwakeword
        # webrtcvad-wheels (no webrtcvad) — el paquete original requiere
        # compilar contra cabeceras de Python y a veces falla silenciosamente
        # en Colab con -q; las wheels son drop-in (mismo nombre de módulo).
        # Usamos {sys.executable} -m pip para garantizar que se instala en
        # el mismo Python que el kernel, y --force-reinstall por si Colab
        # tenía una versión rota cacheada.
        import sys
        !{sys.executable} -m pip install --quiet --force-reinstall --no-deps webrtcvad-wheels
        !pip install -q piper-phonemize
        # espeak-phonemizer — usado por generate_samples.py (fork dscripka).
        # Necesita libespeak-ng a nivel de sistema para funcionar.
        !apt-get install -y -qq espeak-ng libespeak-ng-dev > /dev/null
        !pip install -q espeak-phonemizer

        # Dependencias del training script.
        !pip install -q mutagen==1.47.0 torchinfo==1.8.0 torchmetrics==1.2.0
        !pip install -q speechbrain==0.5.14 audiomentations==0.33.0
        # torch-audiomentations 0.11.0 (la versión que pinea el notebook
        # oficial de openWakeWord) llama a torchaudio.set_audio_backend(),
        # que se eliminó en torchaudio 2.2+. La 0.11.1 es la primera
        # release que quitó esa llamada — bumpeamos a 0.11.1 para que el
        # `import torch_audiomentations` no falle dentro de openwakeword/
        # data.py.
        !pip install -q torch-audiomentations==0.11.1
        !pip install -q acoustics==0.2.6
        !pip install -q tensorflow==2.15.1 onnx_tf==1.10.0
        !pip install -q pronouncing deep_phonemizer==0.0.19

        # HuggingFace datasets pinned to <4.0 — la 4.x devuelve
        # torchcodec.decoders.AudioDecoder en vez de dict, lo que rompe el
        # bucle de descarga de RIRs (row["audio"]["path"]).
        !pip install -q "datasets<4.0"

        # Piper TTS regular (no el "sample-generator" que solo soporta inglés).
        !pip install -q piper-tts
        """
    ),
    code(
        """
        # Sanity check: verifica que las dependencias críticas están
        # importables ANTES de gastar 30 min descargando datasets. Si esta
        # celda explota, el pip install -q de la celda anterior falló
        # silenciosamente — relanza esa celda quitándole el -q para ver
        # el error real.
        import importlib
        for mod in ("webrtcvad", "espeak_phonemizer", "piper", "datasets",
                    "torch_audiomentations"):
            try:
                importlib.import_module(mod)
                print(f"✓ {mod}")
            except Exception as e:
                print(f"✗ {mod}: {type(e).__name__}: {e}")
                raise
        print("\\n✓ Todas las dependencias OK")
        """
    ),
    code(
        """
        # openWakeWord >=0.6 dejó de empaquetar los modelos .onnx (mel,
        # embedding, silero VAD) y los descarga bajo demanda. train.py los
        # carga al arrancar, así que los descargamos primero — si no, peta
        # con NO_SUCHFILE.
        import openwakeword.utils
        openwakeword.utils.download_models()
        print("\\n✓ Modelos runtime de openWakeWord descargados")
        """
    ),
    code(
        """
        import os
        import sys
        import shutil
        import urllib.request
        from pathlib import Path
        from tqdm import tqdm

        import numpy as np
        import scipy.io.wavfile
        import torch
        import yaml
        import datasets

        # Aseguramos que el cwd es el de openwakeword para el script de training.
        os.chdir("/content")
        ROOT = Path("/content").resolve()
        OUTPUT_DIR = ROOT / "models"
        POSITIVE_TRAIN = OUTPUT_DIR / MODEL_NAME / "positive_train"
        POSITIVE_TEST = OUTPUT_DIR / MODEL_NAME / "positive_test"
        for p in (POSITIVE_TRAIN, POSITIVE_TEST):
            p.mkdir(parents=True, exist_ok=True)
        print(f"Trabajando en {ROOT}")
        """
    ),
    md(
        """
        ## 3. Descargar voces Piper en español

        Cinco voces para variedad de acento, tono y género. Cada voz va a
        sintetizar la frase muchas veces variando velocidad para que el modelo
        aprenda a reconocerla en condiciones distintas.
        """
    ),
    code(
        """
        # Voces Piper — mezcla de España y México para cubrir varios acentos.
        # Las URLs son las oficiales de HuggingFace que mantiene rhasspy/piper.
        VOICES = [
            (
                "es_ES-davefx-medium",
                "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/davefx/medium",
            ),
            (
                "es_ES-mls_9972-low",
                "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/mls_9972/low",
            ),
            (
                "es_ES-mls_10246-low",
                "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/mls_10246/low",
            ),
            (
                "es_ES-sharvard-medium",
                "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/sharvard/medium",
            ),
            (
                "es_MX-claude-high",
                "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_MX/claude/high",
            ),
        ]

        VOICES_DIR = ROOT / "piper_voices"
        VOICES_DIR.mkdir(exist_ok=True)

        for name, base in VOICES:
            onnx_path = VOICES_DIR / f"{name}.onnx"
            json_path = VOICES_DIR / f"{name}.onnx.json"
            if not onnx_path.exists():
                print(f"↓ {name}.onnx")
                urllib.request.urlretrieve(f"{base}/{name}.onnx", onnx_path)
            if not json_path.exists():
                urllib.request.urlretrieve(f"{base}/{name}.onnx.json", json_path)

        print(f"\\nVoces descargadas en {VOICES_DIR}")
        !ls -la {VOICES_DIR}
        """
    ),
    md(
        """
        ## 4. Generar muestras positivas en español

        Sintetizamos la frase con cada voz, variando la velocidad
        (`length_scale`) y un poco el énfasis (`noise_w`) para tener
        variedad. Cada combinación se guarda como un .wav de 16 kHz.
        """
    ),
    code(
        """
        # Para evitar tener que invocar `piper` por CLI (que es más lento)
        # cargamos los modelos directamente con piper_tts.
        # piper-tts >=1.4 expone PiperVoice + SynthesisConfig en el package raíz.
        from piper import PiperVoice, SynthesisConfig

        # Cargar las voces en memoria.
        loaded = []
        for name, _ in VOICES:
            onnx_path = VOICES_DIR / f"{name}.onnx"
            if not onnx_path.exists():
                print(f"⚠️  Falta {name}, saltando")
                continue
            voice = PiperVoice.load(str(onnx_path), use_cuda=torch.cuda.is_available())
            loaded.append((name, voice))
        print(f"{len(loaded)} voces cargadas")
        """
    ),
    code(
        """
        # Generar N_SAMPLES + N_SAMPLES_VAL .wav files de la frase objetivo.
        # Cada llamada a synthesize_wav() varía length_scale y noise_w_scale
        # via SynthesisConfig para que cada muestra sea ligeramente distinta.
        import io
        import wave
        import random

        random.seed(42)
        rng = random.Random(42)

        TOTAL = N_SAMPLES + N_SAMPLES_VAL

        def synth_one(voice, text: str, out_path: Path) -> None:
            cfg = SynthesisConfig(
                length_scale=rng.uniform(0.85, 1.20),    # velocidad
                noise_scale=rng.uniform(0.50, 0.75),      # variabilidad de pronunciación
                noise_w_scale=rng.uniform(0.50, 0.85),    # variabilidad de cadencia
            )
            # Sintetizamos a un buffer en memoria — synthesize_wav() ya pone
            # el formato (16-bit mono al sample-rate del modelo).
            buf = io.BytesIO()
            with wave.open(buf, "wb") as wf:
                voice.synthesize_wav(text, wf, syn_config=cfg)
            buf.seek(0)
            with wave.open(buf, "rb") as wf_in:
                rate = wf_in.getframerate()
                frames = wf_in.readframes(wf_in.getnframes())
            audio = np.frombuffer(frames, dtype=np.int16)
            # Resample to 16 kHz if Piper rendered at a different rate
            # (las voces "high" salen a 22 kHz, las "medium"/"low" a 16 kHz).
            if rate != 16000:
                ratio = 16000 / rate
                new_len = int(len(audio) * ratio)
                audio = scipy.signal.resample(audio, new_len).astype(np.int16)
            scipy.io.wavfile.write(out_path, 16000, audio)

        import scipy.signal  # late import so the resample helper is available

        print(f"Generando {TOTAL} muestras de '{TARGET_PHRASE}'…")
        for i in tqdm(range(TOTAL)):
            name, voice = loaded[i % len(loaded)]
            target_dir = POSITIVE_TRAIN if i < N_SAMPLES else POSITIVE_TEST
            out_path = target_dir / f"{MODEL_NAME}_{name}_{i:05d}.wav"
            synth_one(voice, TARGET_PHRASE, out_path)

        print("\\nMuestras positivas generadas:")
        print(f"  Train: {len(list(POSITIVE_TRAIN.glob('*.wav')))}")
        print(f"  Test:  {len(list(POSITIVE_TEST.glob('*.wav')))}")
        """
    ),
    md(
        """
        ## 5. Datos de fondo (room impulse responses, ruido) y features pre-computadas

        Estos los reutilizamos del pipeline oficial de openWakeWord — no
        dependen del idioma de la frase, son ruido y silencios para hacer
        las muestras más realistas.

        ⚠️  Esto descarga **varios GB**. Es la parte más lenta del notebook.
        """
    ),
    code(
        """
        # MIT RIRs (~1 GB)
        rir_dir = ROOT / "mit_rirs"
        if not rir_dir.exists():
            rir_dir.mkdir()
            print("↓ MIT room impulse responses…")
            rir_dataset = datasets.load_dataset(
                "davidscripka/MIT_environmental_impulse_responses",
                split="train",
                streaming=True,
            )
            for row in tqdm(rir_dataset):
                name = row["audio"]["path"].split("/")[-1]
                scipy.io.wavfile.write(
                    rir_dir / name,
                    16000,
                    (row["audio"]["array"] * 32767).astype(np.int16),
                )
        print(f"RIRs: {len(list(rir_dir.glob('*.wav')))} ficheros")
        """
    ),
    code(
        """
        # AudioSet (un shard balanceado, ~3 GB)
        audioset_dir = ROOT / "audioset"
        audioset_16k = ROOT / "audioset_16k"
        if not audioset_16k.exists():
            audioset_dir.mkdir(exist_ok=True)
            audioset_16k.mkdir()
            link = "https://huggingface.co/datasets/agkphysics/AudioSet/resolve/main/data/bal_train09.tar"
            !wget -q -O {audioset_dir}/bal_train09.tar {link}
            !cd {audioset_dir} && tar -xf bal_train09.tar
            # Convertir a 16 kHz mono wav.
            !find {audioset_dir} -name '*.flac' -exec sox {{}} -r 16000 -c 1 {audioset_16k}/{{/.}}.wav \\;
            !find {audioset_dir} -name '*.wav' -exec sox {{}} -r 16000 -c 1 {audioset_16k}/{{/.}}.wav \\;
        print(f"AudioSet: {len(list(audioset_16k.glob('*.wav')))} ficheros")
        """
    ),
    code(
        """
        # Features pre-computadas y validation set (~10 GB) — los pesados.
        feature_file = ROOT / "openwakeword_features_ACAV100M_2000_hrs_16bit.npy"
        validation_file = ROOT / "validation_set_features.npy"
        if not feature_file.exists():
            !wget -q -O {feature_file} https://huggingface.co/datasets/davidscripka/openwakeword_features/resolve/main/openwakeword_features_ACAV100M_2000_hrs_16bit.npy
        if not validation_file.exists():
            !wget -q -O {validation_file} https://huggingface.co/datasets/davidscripka/openwakeword_features/resolve/main/validation_set_features.npy
        print("Features descargadas:")
        !ls -la {feature_file} {validation_file}
        """
    ),
    md(
        """
        ## 6. Configuración YAML del entrenamiento

        Apuntamos a las muestras positivas que ya hemos pre-generado en
        español. El script de entrenamiento detectará que la carpeta
        `positive_train/` ya tiene suficientes ficheros y solo generará los
        adversariales (negativos), que se generan en inglés pero sirven igual.
        """
    ),
    code(
        """
        config_path = ROOT / "openwakeword/examples/custom_model.yml"
        config = yaml.safe_load(config_path.read_text())

        config["target_phrase"] = [TARGET_PHRASE]
        config["model_name"] = MODEL_NAME
        config["n_samples"] = N_SAMPLES
        config["n_samples_val"] = N_SAMPLES_VAL
        config["steps"] = TRAINING_STEPS
        config["target_accuracy"] = TARGET_ACCURACY
        config["target_recall"] = TARGET_RECALL
        config["output_dir"] = str(OUTPUT_DIR)
        config["background_paths"] = [str(audioset_16k)]
        config["rir_paths"] = [str(rir_dir)]
        config["false_positive_validation_data_path"] = str(validation_file)
        config["feature_data_files"] = {
            "ACAV100M_sample": str(feature_file),
        }

        my_config = ROOT / "my_model.yaml"
        my_config.write_text(yaml.dump(config))
        print(my_config.read_text())
        """
    ),
    md(
        """
        ## 7. Entrenar

        Tres pasos: (a) generar negativos adversariales, (b) augmentar todas
        las muestras con ruido y RIRs, (c) entrenar el clasificador.

        El primer paso solo genera negativos en inglés porque
        piper-sample-generator no tiene voces españolas — pero esto da igual,
        el modelo aprende "esto NO es 'hola kiwi'" sea en el idioma que sea.
        """
    ),
    code(
        """
        # Necesitamos piper-sample-generator solo para los adversariales en
        # inglés (que actúan como negativos). El upstream rhasspy/ se
        # reestructuró y ya no expone `generate_samples.py` en la raíz —
        # train.py de openWakeWord sigue esperando la estructura vieja, así
        # que clonamos el fork de dscripka (el propio autor de openWakeWord)
        # que mantiene `generate_samples.py` accesible directamente.
        psg_root = ROOT / "piper-sample-generator"
        psg_marker = psg_root / "generate_samples.py"

        # Si la carpeta existe pero no tiene generate_samples.py es porque
        # un intento anterior clonó el fork incorrecto (rhasspy/, que ya no
        # tiene ese fichero en la raíz). Borra y vuelve a clonar el bueno.
        if psg_root.exists() and not psg_marker.exists():
            print("⚠️  piper-sample-generator existente sin generate_samples.py — re-clonando…")
            !rm -rf {psg_root}

        if not psg_root.exists():
            !git clone -q https://github.com/dscripka/piper-sample-generator
            # El fork de dscripka espera el modelo "en-us-libritts-high.pt"
            # del release v1.0.0 (no el "en_US-libritts_r-medium.pt" del
            # v2.0.0 que usa el upstream rhasspy reestructurado).
            !wget -q -O piper-sample-generator/models/en-us-libritts-high.pt 'https://github.com/rhasspy/piper-sample-generator/releases/download/v1.0.0/en-us-libritts-high.pt'

        PSG_DIR = str(psg_root)
        # Sanity check.
        assert psg_marker.exists(), \\
            "generate_samples.py missing — wrong piper-sample-generator fork?"
        print("piper-sample-generator at:", PSG_DIR)
        """
    ),
    code(
        """
        # 1) Generate adversarial negatives (en inglés). El script ve que
        # los positivos ya existen y los respeta.
        !PYTHONPATH={PSG_DIR} {sys.executable} openwakeword/openwakeword/train.py --training_config my_model.yaml --generate_clips
        """
    ),
    code(
        """
        # 2) Augmentar las muestras con ruido y RIRs.
        !PYTHONPATH={PSG_DIR} {sys.executable} openwakeword/openwakeword/train.py --training_config my_model.yaml --augment_clips
        """
    ),
    code(
        """
        # 3) Entrenar el modelo. Esto es lo más largo — 10 a 30 minutos
        # según GPU.
        !PYTHONPATH={PSG_DIR} {sys.executable} openwakeword/openwakeword/train.py --training_config my_model.yaml --train_model
        """
    ),
    md(
        """
        ## 8. Recoger el .onnx y descargarlo

        El script guarda el modelo en `models/<MODEL_NAME>/`. Lo copiamos a
        un sitio fácil y lo descargamos.
        """
    ),
    code(
        """
        from google.colab import files

        out = OUTPUT_DIR / MODEL_NAME / f"{MODEL_NAME}.onnx"
        if not out.exists():
            # A veces se guarda en otro nombre. Buscamos.
            candidates = list(OUTPUT_DIR.rglob("*.onnx"))
            print("Modelos ONNX encontrados:")
            for c in candidates:
                print(" ", c)
            if candidates:
                out = candidates[-1]
            else:
                raise FileNotFoundError("No se encontró ningún .onnx — revisa los logs anteriores.")

        target = ROOT / f"{MODEL_NAME}.onnx"
        shutil.copy(out, target)
        print(f"✓ Modelo final: {target}  ({target.stat().st_size / 1024:.1f} KB)")
        files.download(str(target))
        """
    ),
    md(
        """
        ## 9. Próximos pasos

        Cuando tengas el `.onnx` descargado:

        1. Pásaselo al chat de Claude (subir como adjunto o a Drive +
           pegar link).
        2. Lo coloca en `android/app/src/main/assets/wakeword/` reemplazando
           `hey_jarvis.onnx` (o con un nombre distinto y cambiando la
           referencia en `WakeWordDetector.kt:43`).
        3. Build + APK + actualiza la tablet.

        **Si el modelo se comporta mal** (no detecta o tiene falsos positivos):
        - Sube `N_SAMPLES` a 5000 y `TRAINING_STEPS` a 30000.
        - Añade más voces Piper (busca otras en
          [huggingface.co/rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices/tree/main/es)).
        - Ajusta el threshold en el cliente (`WakeWordListener.kt:DEFAULT_THRESHOLD`)
          en vez de re-entrenar — bájalo si no detecta, súbelo si dispara solo.
        """
    ),
]


def main() -> None:
    nb = {
        "cells": CELLS,
        "metadata": {
            "colab": {"provenance": []},
            "kernelspec": {"name": "python3", "display_name": "Python 3"},
            "language_info": {"name": "python"},
            "accelerator": "GPU",
        },
        "nbformat": 4,
        "nbformat_minor": 0,
    }
    out = HERE / "train_es.ipynb"
    out.write_text(json.dumps(nb, indent=1, ensure_ascii=False))
    print(f"Wrote {out}")


if __name__ == "__main__":
    main()
