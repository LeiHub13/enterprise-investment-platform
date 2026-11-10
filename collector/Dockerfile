FROM python:3.13-slim
WORKDIR /app
COPY requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt && useradd --system --uid 10001 --create-home app
COPY . ./collector
USER app
CMD ["python", "-m", "collector.monitor", "--watch"]
