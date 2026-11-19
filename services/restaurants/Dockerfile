FROM python:3.13-slim AS build
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
RUN python -m venv /venv
WORKDIR /src
COPY pyproject.toml ./
COPY src ./src
RUN /venv/bin/pip install --no-cache-dir .

FROM python:3.13-slim
COPY --from=build /venv /venv
ENV PATH=/venv/bin:$PATH PYTHONUNBUFFERED=1
EXPOSE 8082
CMD ["restaurants"]
