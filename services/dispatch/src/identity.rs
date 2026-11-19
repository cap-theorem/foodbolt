use std::collections::hash_map::RandomState;
use std::hash::BuildHasher;
use std::net::ToSocketAddrs;
use std::sync::atomic::{AtomicU64, Ordering};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::Serialize;
use spectral_sdk::{Client, Registration};

use crate::log;

#[derive(Debug, Clone)]
pub struct Identity {
    pub service: String,
    pub instance: String,
    pub machine: String,
    pub port: u16,
    pub endpoint: String,
    pub lease_ttl: Duration,
    pub started: Instant,
}

#[derive(Debug, Clone, Serialize)]
pub struct Hop {
    pub service: String,
    pub instance: String,
    pub machine: String,
    pub latency_ms: f64,
}

#[derive(Debug, Serialize)]
pub struct Info {
    pub service: String,
    pub instance: String,
    pub machine: String,
    pub endpoint: String,
    pub uptime_s: u64,
}

impl Identity {
    pub fn from_env(default_service: &str, instance_prefix: &str, default_port: u16) -> Identity {
        let hostname = hostname();
        let port = env("PORT").and_then(|p| p.parse().ok()).unwrap_or(default_port);
        let ttl = env("LEASE_TTL_SECONDS")
            .and_then(|v| v.parse::<f64>().ok())
            .filter(|v| *v > 0.0)
            .unwrap_or(5.0);
        let suffix = random_u64() & 0xffff;

        Identity {
            service: env("SERVICE_NAME").unwrap_or_else(|| default_service.to_string()),
            instance: env("INSTANCE_ID").unwrap_or_else(|| format!("{instance_prefix}-{suffix:04x}")),
            machine: env("MACHINE").unwrap_or_else(|| hostname.clone()),
            port,
            endpoint: env("ADVERTISE_URL")
                .unwrap_or_else(|| format!("http://{}:{port}", advertise_host(&hostname, port))),
            lease_ttl: Duration::from_secs_f64(ttl),
            started: Instant::now(),
        }
    }

    pub fn hop(&self, start: Instant) -> Hop {
        Hop {
            service: self.service.clone(),
            instance: self.instance.clone(),
            machine: self.machine.clone(),
            latency_ms: (start.elapsed().as_secs_f64() * 1_000_000.0).round() / 1000.0,
        }
    }

    pub fn info(&self) -> Info {
        Info {
            service: self.service.clone(),
            instance: self.instance.clone(),
            machine: self.machine.clone(),
            endpoint: self.endpoint.clone(),
            uptime_s: self.started.elapsed().as_secs(),
        }
    }

    pub fn new_id(&self) -> String {
        let mut n = random_u64() % 36u64.pow(6);
        let mut chars = [b'0'; 6];
        for slot in chars.iter_mut().rev() {
            let digit = (n % 36) as u8;
            *slot = if digit < 10 { b'0' + digit } else { b'a' + digit - 10 };
            n /= 36;
        }
        format!("{}.{}", self.instance, String::from_utf8_lossy(&chars))
    }
}

pub fn spawn_registration(client: Client, ident: &Identity, service: String) {
    let registration = Registration {
        service,
        instance_id: ident.instance.clone(),
        endpoint: ident.endpoint.clone(),
        ttl: ident.lease_ttl,
    };
    thread::Builder::new()
        .name(format!("spectral-registration-{}", registration.service))
        .spawn(move || stay_registered(&client, &registration))
        .expect("spawn spectral-registration thread");
}

fn stay_registered(client: &Client, registration: &Registration) {
    loop {
        let lease = match client.register(registration) {
            Ok(lease) => lease,
            Err(err) => {
                log::warn(
                    "spectral register failed, retrying",
                    &[("service", registration.service.clone()), ("error", err.to_string())],
                );
                thread::sleep(Duration::from_secs(1));
                continue;
            }
        };
        log::info(
            "registered with spectral",
            &[
                ("service", registration.service.clone()),
                ("instance", registration.instance_id.clone()),
                ("endpoint", registration.endpoint.clone()),
                ("ttl", format!("{:?}", lease.ttl)),
            ],
        );

        let keeper = match client.keep_alive(&lease) {
            Ok(keeper) => keeper,
            Err(err) => {
                log::warn(
                    "spectral keep_alive failed, re-registering",
                    &[("service", registration.service.clone()), ("error", err.to_string())],
                );
                continue;
            }
        };
        while !keeper.is_finished() {
            thread::sleep(Duration::from_millis(250));
        }
        if let Err(err) = keeper.stop() {
            log::warn(
                "spectral lease lost, re-registering",
                &[("service", registration.service.clone()), ("error", err.to_string())],
            );
        }
    }
}

pub fn random_u64() -> u64 {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let n = COUNTER.fetch_add(1, Ordering::Relaxed);
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_nanos();
    RandomState::new().hash_one((n, nanos, std::process::id()))
}

pub fn env(key: &str) -> Option<String> {
    std::env::var(key).ok().filter(|v| !v.is_empty())
}

fn hostname() -> String {
    env("HOSTNAME")
        .or_else(|| std::fs::read_to_string("/etc/hostname").ok().map(|s| s.trim().to_string()))
        .unwrap_or_else(|| "localhost".to_string())
}

fn advertise_host(hostname: &str, port: u16) -> String {
    (hostname, port)
        .to_socket_addrs()
        .ok()
        .and_then(|mut addrs| addrs.find(|a| a.is_ipv4() && !a.ip().is_loopback()))
        .map(|a| a.ip().to_string())
        .unwrap_or_else(|| "127.0.0.1".to_string())
}
