FROM python:3.13.12-slim
WORKDIR /app
RUN pip install --no-cache-dir uv==0.10.4
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev
COPY app ./app
USER 65534:65534
EXPOSE 8000
CMD [".venv/bin/uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
