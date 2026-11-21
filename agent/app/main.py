from fastapi import FastAPI

from app.extraction import Result, generate

app = FastAPI(title="CareContinuo Agent", docs_url=None, redoc_url=None, openapi_url=None)
app.post("/internal/v1/followup-drafts", response_model=Result)(generate)


@app.get("/health")
def health() -> dict[str, str]:
    return {"service": "carecontinuo-agent", "status": "ready"}
