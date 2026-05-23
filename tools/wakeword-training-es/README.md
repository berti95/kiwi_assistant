# Wake-word training (español) — herramienta one-off

Notebook de Google Colab para entrenar un modelo de wake word de
[openWakeWord](https://github.com/dscripka/openWakeWord) con una frase
**en español** ("hola kiwi", "oye kiwi", lo que prefieras).

> El notebook oficial de openWakeWord está cableado a inglés
> (`piper-sample-generator` solo distribuye voces en-US). Aquí pre-generamos
> los positivos con `piper-tts` regular y voces `es_ES-*` / `es_MX-*` y
> reusamos el resto del pipeline oficial (augmentación, negativos, training,
> export ONNX).

## Abrir en Colab

[![Open in Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/berti95/kiwi_assistant/blob/claude/setup-project-config-8apSY/tools/wakeword-training-es/train_es.ipynb)

> El badge apunta a la rama actual de desarrollo. Cuando se mergee a `main`,
> reemplaza `claude/setup-project-config-8apSY` por `main` en la URL.

## Cómo se usa (resumen)

1. Click en el badge → se abre en Colab.
2. Runtime → Change runtime type → **GPU (T4)**. Sin GPU se entrena, pero
   tarda ~3× más.
3. En la primera celda de código cambia `TARGET_PHRASE` a la frase que
   quieras (3-5 sílabas, vocales claras).
4. Runtime → Run all.
5. **30-60 min** después se descarga automáticamente un `.onnx` (~1 MB).
6. Pásamelo al chat — lo meto en `android/app/src/main/assets/wakeword/`
   y empujo el cambio.

## Si modificas el notebook

`train_es.ipynb` se genera desde `build_notebook.py` para no pelearse con
JSON escapado. Tras tocar el script:

```sh
python build_notebook.py
git add train_es.ipynb build_notebook.py
git commit -m "..."
```

## Costes

Cero. Colab gratis tiene cuota de GPU diaria suficiente para una sesión de
entrenamiento de este tamaño.

## Limitaciones conocidas

- Los **negativos adversariales** se generan en inglés (con
  `piper-sample-generator` que es lo que pide el training script de
  openWakeWord). En la práctica esto no afecta a la detección — el modelo
  aprende "esto NO es 'hola kiwi'" independientemente del idioma — pero
  puede que tengas un falso positivo si alguien dice una frase inglesa
  fonéticamente parecida.
- Las muestras positivas son TTS sintéticas, no voces reales. El modelo
  generaliza mejor con voces reales pero conseguirlas a escala es más
  trabajo. Si te molesta el rate de detección con tu voz, una solución es
  re-entrenar añadiendo 20-30 grabaciones tuyas de la frase como muestras
  positivas adicionales.
