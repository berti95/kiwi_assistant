FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

COPY requirements.txt .
RUN pip install -r requirements.txt

COPY kiwi_backend ./kiwi_backend

EXPOSE 8080

# --proxy-headers + --forwarded-allow-ips="*" hacen que uvicorn respete
# X-Forwarded-Proto / -For que mete el load balancer de Cloud Run.
# Sin esto request.base_url devuelve "http://..." (la conexión interna
# es HTTP) y cosas como construir el redirect_uri de OAuth fallan con
# redirect_uri_mismatch porque Google espera "https://...". El "*"
# en allow-ips es seguro en Cloud Run porque la única ruta de entrada
# al contenedor es a través del LB de Google.
CMD ["uvicorn", "kiwi_backend.main:app", "--host", "0.0.0.0", "--port", "8080", "--proxy-headers", "--forwarded-allow-ips", "*"]
