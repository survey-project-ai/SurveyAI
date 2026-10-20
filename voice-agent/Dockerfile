FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONIOENCODING=utf-8 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

COPY requirements.txt .
RUN pip install -r requirements.txt

COPY . .

# The voice gender model (95 MB, Apache-2.0) is part of the image, so a call never waits for it.
RUN python setup_models.py

# "start" is the production mode: no file watching, and a stop (redeploy) waits for live calls to end.
CMD ["python", "agent.py", "start"]
