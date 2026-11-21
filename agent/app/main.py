from hmac import compare_digest
from os import getenv
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException

from app.extraction import Result, generate

app = FastAPI(title="CareContinuo Agent", docs_url=None, redoc_url=None, openapi_url=None)
def validate_caller(authorization: str = Header(default="")) -> None:
    path = getenv("CARE_AGENT_KEY_FILE")
    if path is None:
        return
    key = Path(path).read_text(encoding="utf-8").strip()
    if len(key) < 32:
        raise RuntimeError("Invalid Agent service key")
    if not compare_digest(authorization.encode("utf-8"), ("Bearer " + key).encode("utf-8")):
        raise HTTPException(status_code=401, detail="INTERNAL_AUTH_REQUIRED")


app.post("/internal/v1/followup-drafts", response_model=Result, dependencies=[Depends(validate_caller)])(generate)


@app.get("/health")
def health() -> dict[str, str]:
    return {"service": "carecontinuo-agent", "status": "ready"}
