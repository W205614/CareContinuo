FROM python:3.13.18-alpine3.24 AS build
WORKDIR /app
RUN pip install --no-cache-dir uv==0.13.4
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev

FROM python:3.13.18-alpine3.24
WORKDIR /app
RUN apk upgrade --no-cache && python -m pip uninstall --yes pip
COPY --from=build /app/.venv ./.venv
COPY app ./app
USER 65534:65534
EXPOSE 8000
CMD [".venv/bin/uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
