from __future__ import annotations

import logging
from datetime import datetime, timezone

log = logging.getLogger("restaurants")


class LogfmtFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        ts = datetime.fromtimestamp(record.created, timezone.utc)
        stamp = ts.strftime("%Y-%m-%dT%H:%M:%S.") + f"{ts.microsecond // 1000:03d}Z"
        level = "WARN" if record.levelname == "WARNING" else record.levelname
        parts = [stamp, level, record.getMessage()]
        fields = getattr(record, "fields", None) or {}
        for key, value in fields.items():
            if value is None:
                continue
            parts.append(f"{key}={format_value(value)}")
        line = " ".join(parts)
        if record.exc_info:
            line += "\n" + self.formatException(record.exc_info)
        return line


def format_value(value: object) -> str:
    if isinstance(value, bool):
        text = "true" if value else "false"
    elif isinstance(value, float):
        text = f"{value:.1f}"
    else:
        text = str(value)
    if text == "" or any(c in text for c in ' "='):
        escaped = text.replace("\\", "\\\\").replace('"', '\\"')
        return f'"{escaped}"'
    return text


def setup() -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(LogfmtFormatter())
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(logging.INFO)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)
    logging.getLogger("uvicorn.access").disabled = True
    logging.getLogger("uvicorn.error").setLevel(logging.WARNING)


def info(msg: str, **fields: object) -> None:
    log.info(msg, extra={"fields": fields})


def warn(msg: str, **fields: object) -> None:
    log.warning(msg, extra={"fields": fields})


def error(msg: str, **fields: object) -> None:
    log.error(msg, extra={"fields": fields})
