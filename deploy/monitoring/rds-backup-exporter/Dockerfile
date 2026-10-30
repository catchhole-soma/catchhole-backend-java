FROM python:3.13.17-slim-bookworm@sha256:e32be0e55814a08977501ca863271d9bd02443710db8f2afaf8372e2c4508530

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir --requirement requirements.txt \
    && groupadd --gid 10001 exporter \
    && useradd --uid 10001 --gid exporter --no-create-home --shell /usr/sbin/nologin exporter
COPY exporter.py ./
USER 10001:10001
EXPOSE 9109
CMD ["python", "exporter.py"]
