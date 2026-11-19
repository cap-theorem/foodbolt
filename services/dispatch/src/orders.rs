use std::sync::Arc;
use std::time::{Duration, Instant};

use serde::Serialize;
use spectral_sdk::{Error as SpectralError, Provider};

use crate::App;
use crate::log;

const ATTEMPTS: u32 = 3;
const SERVICE: &str = "orders";

#[derive(Serialize)]
struct EventBody<'a> {
    event: &'a str,
    source: &'a str,
    detail: &'a str,
}

enum Outcome {
    Accepted,
    Stop(String),
    Retry(String),
}

pub async fn post_event(app: Arc<App>, order_id: String, request_id: String, event: &'static str, detail: String) {
    let service = SERVICE.to_string();

    for attempt in 1..=ATTEMPTS {
        let call_app = app.clone();
        let call_service = service.clone();
        let call_order = order_id.clone();
        let call_request = request_id.clone();
        let call_detail = detail.clone();
        let outcome = tokio::task::spawn_blocking(move || {
            send(&call_app, &call_service, &call_order, &call_request, event, &call_detail)
        })
            .await
            .unwrap_or_else(|err| Outcome::Retry(err.to_string()));

        match outcome {
            Outcome::Accepted => return,
            Outcome::Stop(reason) => {
                log::warn(
                    "order event rejected, not retrying",
                    &[
                        ("order_id", order_id.clone()),
                        ("event", event.to_string()),
                        ("reason", reason),
                        ("request_id", request_id.clone()),
                    ],
                );
                return;
            }
            Outcome::Retry(reason) => {
                log::warn(
                    "order event failed, retrying",
                    &[
                        ("order_id", order_id.clone()),
                        ("event", event.to_string()),
                        ("attempt", attempt.to_string()),
                        ("reason", reason),
                        ("request_id", request_id.clone()),
                    ],
                );
                if attempt < ATTEMPTS {
                    tokio::time::sleep(Duration::from_secs(1)).await;
                }
            }
        }
    }
    log::error(
        "order event dropped after retries",
        &[("order_id", order_id), ("event", event.to_string()), ("request_id", request_id)],
    );
}

fn send(app: &App, service: &str, order_id: &str, request_id: &str, event: &str, detail: &str) -> Outcome {
    let provider = match app.spectral.lookup(service) {
        Ok(provider) => provider,
        Err(SpectralError::ServiceNotFound(_)) => {
            log::warn("lookup failed", &[("service", service.to_string()), ("request_id", request_id.to_string())]);
            return Outcome::Retry(format!("{service} not found"));
        }
        Err(err) => {
            log::warn(
                "lookup failed",
                &[
                    ("service", service.to_string()),
                    ("error", err.to_string()),
                    ("request_id", request_id.to_string()),
                ],
            );
            return Outcome::Retry(format!("lookup {service}: {err}"));
        }
    };
    let instance = provider_instance(&provider);

    let path = format!("/v1/orders/{order_id}/events");
    let start = Instant::now();
    let result = app
        .http
        .post(format!("{}{path}", provider.endpoint))
        .header("x-request-id", request_id)
        .send_json(EventBody {
            event,
            source: "dispatch",
            detail,
        });
    let ms = format!("{:.1}", start.elapsed().as_secs_f64() * 1000.0);

    match result {
        Ok(resp) => {
            let status = resp.status().as_u16();
            log::info(
                "call",
                &[
                    ("service", service.to_string()),
                    ("instance", instance.clone()),
                    ("method", "POST".to_string()),
                    ("path", path),
                    ("status", status.to_string()),
                    ("ms", ms),
                    ("request_id", request_id.to_string()),
                ],
            );
            match status {
                200..=299 => Outcome::Accepted,
                404 | 409 => Outcome::Stop(format!("{service} answered {status}")),
                _ => Outcome::Retry(format!("{service} answered {status}")),
            }
        }
        Err(err) => {
            log::warn(
                "call failed",
                &[
                    ("service", service.to_string()),
                    ("instance", instance.clone()),
                    ("method", "POST".to_string()),
                    ("path", path),
                    ("ms", ms),
                    ("error", err.to_string()),
                    ("request_id", request_id.to_string()),
                ],
            );
            Outcome::Retry(err.to_string())
        }
    }
}

fn provider_instance(provider: &Provider) -> String {
    if !provider.instance_id.is_empty() {
        return provider.instance_id.clone();
    }
    let without_scheme = match provider.endpoint.find("://") {
        Some(i) => &provider.endpoint[i + 3..],
        None => provider.endpoint.as_str(),
    };
    let host = match without_scheme.find('/') {
        Some(i) => &without_scheme[..i],
        None => without_scheme,
    };
    host.to_string()
}
