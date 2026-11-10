"""Initial Agent service; workflows are introduced in a later delivery."""

from fastapi import FastAPI

app = FastAPI(title="PanYue Agent API", version="0.1.0")


@app.get("/internal/health")
def health() -> dict[str, str]:
    return {"status": "UP", "service": "panyue-agent-api"}
