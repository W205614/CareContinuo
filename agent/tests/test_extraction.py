import asyncio

import pytest
from httpx import ASGITransport, AsyncClient

from model_fixture import app


@pytest.mark.parametrize(("marker", "status", "expected"), [
    ("", 200, "fixture"),
    ("[conflict]", 200, "fixture"),
    ("[unsupported]", 422, "MODEL_SOURCE_MISMATCH"),
    ("[timeout]", 504, "MODEL_TIMEOUT"),
])
def test_source_validation_at_the_agent_boundary(marker, status, expected):
    async def extract():
        async with AsyncClient(transport=ASGITransport(app), base_url="http://test") as client:
            return await client.post("/internal/v1/followup-drafts", json={
                "jobId": 1, "sourceVersion": 1, "fragments": [
                    {"id": 11, "versionId": 2, "pageNumber": None, "lineNumber": 1,
                     "excerpt": f"{marker} 安排 2099-12-05 门诊复诊"},
                    {"id": 22, "versionId": 3, "pageNumber": 1, "lineNumber": 2,
                     "excerpt": "安排 2099-12-06 门诊复诊"},
                ],
            })

    response = asyncio.run(extract())
    assert response.status_code == status
    data = response.json()
    assert data["model" if status == 200 else "detail"] == expected
    if marker == "[conflict]":
        assert {reference["segmentId"] for reference in data["conflicts"][0]["references"]} == {11, 22}
