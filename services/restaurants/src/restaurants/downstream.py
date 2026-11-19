from __future__ import annotations

import time
from dataclasses import dataclass

import httpx
from spectral_sdk import Client, ServiceNotFoundError, SpectralError

from restaurants import logs


class CallError(Exception):
    def __init__(self, service: str, instance: str, status: int, message: str) -> None:
        super().__init__(f"{service} ({instance or 'no instance'}): {message}")
        self.service = service
        self.instance = instance
        self.status = status
        self.message = message


@dataclass(frozen=True)
class Called:
    status: int
    body: dict
    instance: str


class Downstream:
    def __init__(self, spectral: Client, http: httpx.Client) -> None:
        self._spectral = spectral
        self._http = http

    def post(self, service: str, path: str, body: dict, request_id: str, timeout: float = 5.0) -> Called:
        try:
            provider = self._spectral.lookup(service)
        except ServiceNotFoundError as err:
            logs.warn("lookup failed", service=service, error="no instance registered", request_id=request_id)
            raise CallError(service, "", 404, "no instance registered") from err
        except SpectralError as err:
            logs.warn("lookup failed", service=service, error=str(err), request_id=request_id)
            raise CallError(service, "", 503, str(err)) from err

        start = time.perf_counter()
        try:
            resp = self._http.post(
                provider.endpoint + path,
                json=body,
                headers={"x-request-id": request_id},
                timeout=timeout,
            )
        except httpx.HTTPError as err:
            ms = (time.perf_counter() - start) * 1000
            logs.warn(
                "call failed",
                service=service,
                instance=provider.instance_id,
                method="POST",
                path=path,
                ms=ms,
                error=str(err) or type(err).__name__,
                request_id=request_id,
            )
            raise CallError(service, provider.instance_id, 503, str(err) or type(err).__name__) from err

        ms = (time.perf_counter() - start) * 1000
        logs.info(
            "call",
            service=service,
            instance=provider.instance_id,
            method="POST",
            path=path,
            status=resp.status_code,
            ms=ms,
            request_id=request_id,
        )
        try:
            data = resp.json()
        except ValueError:
            data = {"error": resp.text[:4096]}
        if not isinstance(data, dict):
            data = {"value": data}
        if resp.status_code >= 300:
            raise CallError(service, provider.instance_id, resp.status_code, str(data.get("error", resp.text[:4096])))
        return Called(status=resp.status_code, body=data, instance=provider.instance_id)
