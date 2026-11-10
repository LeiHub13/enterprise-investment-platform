FROM python:3.13-slim
WORKDIR /srv/agent
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt && useradd --system --uid 10001 --create-home app
COPY app ./app
RUN mkdir -p data && chown app:app data
USER app
EXPOSE 8766
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8766"]
