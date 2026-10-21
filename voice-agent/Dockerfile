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

# The post-call check (verify_watch.py) runs next to the agent, restarted whenever it stops. The agent
# replaces the shell (exec) so it gets the stop signal of a redeploy itself; "start" is the production
# mode, which then waits for live calls to end.
CMD ["sh", "-c", "while true; do python -u verify_watch.py; sleep 5; done & exec python agent.py start"]
