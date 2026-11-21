import asyncio

from httpx import ASGITransport, AsyncClient

from app.main import app


def test_agent_health_without_model_credentials():
    async def request_health():
        async with AsyncClient(transport=ASGITransport(app), base_url="http://test") as client:
            return await client.get("/health")

    response = asyncio.run(request_health())
    assert response.status_code == 200
    assert response.json() == {"service": "carecontinuo-agent", "status": "ready"}
