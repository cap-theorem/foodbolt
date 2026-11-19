mod fleet;
mod identity;
mod log;
mod orders;

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use axum::extract::{Path, Request, State};
use axum::http::{HeaderMap, StatusCode};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::{Deserialize, Serialize};
use serde_json::json;
use spectral_sdk::{Client, Config};
use tokio::sync::Notify;

use fleet::{
    CourierRef, CourierStatus, CourierView, Delivery, DeliveryStatus, DeliveryView, Fleet, Location, Vehicle,
    distance_km, round, travel_seconds,
};
use identity::{Hop, Identity, Info};

const HANDOFF_SECONDS: f64 = 5.0;

pub struct App {
    ident: Identity,
    spectral: Client,
    http: ureq::Agent,
    time_scale: f64,
    fleet: Mutex<Fleet>,
    stats: Stats,
}

#[derive(Default)]
struct Stats {
    deliveries_created: AtomicU64,
    delivered: AtomicU64,
    cancelled: AtomicU64,
    rejected_no_courier: AtomicU64,
    estimates: AtomicU64,
}

type ApiError = (StatusCode, Json<serde_json::Value>);

fn api_error(status: StatusCode, msg: impl Into<String>) -> ApiError {
    (status, Json(json!({ "error": msg.into() })))
}

fn request_id(headers: &HeaderMap) -> String {
    headers
        .get("x-request-id")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string()
}

#[derive(Deserialize)]
struct Pickup {
    id: String,
    lat: f64,
    lng: f64,
}

#[derive(Deserialize)]
struct EstimatesRequest {
    dropoff: Location,
    pickups: Vec<Pickup>,
}

#[derive(Serialize)]
struct Estimate {
    id: String,
    distance_km: f64,
    travel_seconds: f64,
}

#[derive(Serialize)]
struct EstimatesResponse {
    estimates: Vec<Estimate>,
    hops: Vec<Hop>,
}

async fn estimates(State(app): State<Arc<App>>, Json(req): Json<EstimatesRequest>) -> Json<EstimatesResponse> {
    let start = Instant::now();
    app.stats.estimates.fetch_add(1, Ordering::Relaxed);
    let estimates = req
        .pickups
        .into_iter()
        .map(|p| {
            let pickup = Location { lat: p.lat, lng: p.lng };
            let travel = travel_seconds(pickup, req.dropoff, Vehicle::Scooter, app.time_scale)
                + HANDOFF_SECONDS * app.time_scale;
            Estimate {
                id: p.id,
                distance_km: round(distance_km(pickup, req.dropoff), 2),
                travel_seconds: round(travel, 1),
            }
        })
        .collect();
    Json(EstimatesResponse {
        estimates,
        hops: vec![app.ident.hop(start)],
    })
}

#[derive(Deserialize)]
struct CreateDelivery {
    order_id: String,
    pickup: Location,
    dropoff: Location,
    #[serde(default)]
    ready_in_seconds: f64,
}

#[derive(Serialize)]
struct DeliveryCreated {
    delivery_id: String,
    order_id: String,
    status: &'static str,
    courier: CourierRef,
    eta_seconds: u64,
    hops: Vec<Hop>,
}

async fn create_delivery(
    State(app): State<Arc<App>>,
    headers: HeaderMap,
    Json(req): Json<CreateDelivery>,
) -> Result<(StatusCode, Json<DeliveryCreated>), ApiError> {
    let start = Instant::now();
    let rid = request_id(&headers);
    if req.order_id.is_empty() {
        return Err(api_error(StatusCode::BAD_REQUEST, "order_id is required"));
    }

    let delivery_id = app.ident.new_id();
    let now = log::now_rfc3339();
    let (courier, eta_seconds) = {
        let mut fleet = app.fleet.lock().unwrap();
        let Some(index) = fleet.nearest_idle(req.pickup) else {
            drop(fleet);
            app.stats.rejected_no_courier.fetch_add(1, Ordering::Relaxed);
            log::warn(
                "no courier available",
                &[("order_id", req.order_id.clone()), ("request_id", rid)],
            );
            return Err(api_error(StatusCode::SERVICE_UNAVAILABLE, "no courier available"));
        };

        let c = &fleet.couriers[index];
        let to_pickup = travel_seconds(c.location, req.pickup, c.vehicle, app.time_scale);
        let to_dropoff = travel_seconds(req.pickup, req.dropoff, c.vehicle, app.time_scale);
        let eta_seconds = (to_pickup.max(req.ready_in_seconds) + to_dropoff).ceil() as u64;

        let c = &mut fleet.couriers[index];
        c.status = CourierStatus::ToPickup;
        c.delivery_id = Some(delivery_id.clone());

        fleet.deliveries.insert(
            delivery_id.clone(),
            Delivery {
                delivery_id: delivery_id.clone(),
                order_id: req.order_id.clone(),
                status: DeliveryStatus::Assigned,
                courier: index,
                pickup: req.pickup,
                dropoff: req.dropoff,
                eta_seconds,
                ready: false,
                request_id: rid.clone(),
                created_at: now.clone(),
                updated_at: now,
                ready_signal: Arc::new(Notify::new()),
                task: None,
            },
        );
        let task = tokio::spawn(run_delivery(app.clone(), delivery_id.clone()));
        if let Some(d) = fleet.deliveries.get_mut(&delivery_id) {
            d.task = Some(task.abort_handle());
        }
        (fleet.courier_ref(index), eta_seconds)
    };

    app.stats.deliveries_created.fetch_add(1, Ordering::Relaxed);
    log::info(
        "courier assigned",
        &[
            ("delivery_id", delivery_id.clone()),
            ("order_id", req.order_id.clone()),
            ("courier", courier.name.clone()),
            ("courier_id", courier.id.clone()),
            ("vehicle", courier.vehicle.to_string()),
            ("eta_seconds", eta_seconds.to_string()),
            ("request_id", rid),
        ],
    );

    Ok((
        StatusCode::CREATED,
        Json(DeliveryCreated {
            delivery_id,
            order_id: req.order_id,
            status: DeliveryStatus::Assigned.as_str(),
            courier,
            eta_seconds,
            hops: vec![app.ident.hop(start)],
        }),
    ))
}

struct Trip {
    order_id: String,
    request_id: String,
    courier_name: String,
    vehicle: Vehicle,
    from: Location,
    pickup: Location,
    dropoff: Location,
    ready_signal: Arc<Notify>,
}

async fn run_delivery(app: Arc<App>, delivery_id: String) {
    let trip = {
        let fleet = app.fleet.lock().unwrap();
        let Some(d) = fleet.deliveries.get(&delivery_id) else {
            return;
        };
        let c = &fleet.couriers[d.courier];
        Trip {
            order_id: d.order_id.clone(),
            request_id: d.request_id.clone(),
            courier_name: c.name.clone(),
            vehicle: c.vehicle,
            from: c.location,
            pickup: d.pickup,
            dropoff: d.dropoff,
            ready_signal: d.ready_signal.clone(),
        }
    };

    let to_pickup = travel_seconds(trip.from, trip.pickup, trip.vehicle, app.time_scale);
    tokio::time::sleep(Duration::from_secs_f64(to_pickup)).await;

    let already_ready = {
        let mut fleet = app.fleet.lock().unwrap();
        let Some(d) = fleet.deliveries.get_mut(&delivery_id) else {
            return;
        };
        d.status = DeliveryStatus::AtRestaurant;
        d.updated_at = log::now_rfc3339();
        let ready = d.ready;
        let index = d.courier;
        let c = &mut fleet.couriers[index];
        c.status = CourierStatus::Waiting;
        c.location = trip.pickup;
        ready
    };
    log::info(
        "courier at restaurant",
        &[
            ("delivery_id", delivery_id.clone()),
            ("order_id", trip.order_id.clone()),
            ("courier", trip.courier_name.clone()),
            ("food_ready", already_ready.to_string()),
            ("request_id", trip.request_id.clone()),
        ],
    );
    orders::post_event(
        app.clone(),
        trip.order_id.clone(),
        trip.request_id.clone(),
        "courier_at_restaurant",
        format!("{} arrived at the restaurant", trip.courier_name),
    )
    .await;

    loop {
        let notified = trip.ready_signal.notified();
        let ready = {
            let fleet = app.fleet.lock().unwrap();
            fleet.deliveries.get(&delivery_id).is_some_and(|d| d.ready)
        };
        if ready {
            break;
        }
        notified.await;
    }

    {
        let mut fleet = app.fleet.lock().unwrap();
        let Some(d) = fleet.deliveries.get_mut(&delivery_id) else {
            return;
        };
        d.status = DeliveryStatus::PickedUp;
        d.updated_at = log::now_rfc3339();
        let index = d.courier;
        fleet.couriers[index].status = CourierStatus::ToDropoff;
    }
    log::info(
        "picked up",
        &[
            ("delivery_id", delivery_id.clone()),
            ("order_id", trip.order_id.clone()),
            ("courier", trip.courier_name.clone()),
            ("request_id", trip.request_id.clone()),
        ],
    );
    orders::post_event(
        app.clone(),
        trip.order_id.clone(),
        trip.request_id.clone(),
        "picked_up",
        format!("{} picked up the order on a {}", trip.courier_name, trip.vehicle.as_str()),
    )
    .await;

    let to_dropoff = travel_seconds(trip.pickup, trip.dropoff, trip.vehicle, app.time_scale);
    tokio::time::sleep(Duration::from_secs_f64(to_dropoff)).await;

    {
        let mut fleet = app.fleet.lock().unwrap();
        let Some(d) = fleet.deliveries.get_mut(&delivery_id) else {
            return;
        };
        d.status = DeliveryStatus::Delivered;
        d.updated_at = log::now_rfc3339();
        d.task = None;
        let index = d.courier;
        fleet.couriers[index].location = trip.dropoff;
        fleet.free_courier(index);
    }
    app.stats.delivered.fetch_add(1, Ordering::Relaxed);
    log::info(
        "delivered",
        &[
            ("delivery_id", delivery_id.clone()),
            ("order_id", trip.order_id.clone()),
            ("courier", trip.courier_name.clone()),
            ("request_id", trip.request_id.clone()),
        ],
    );
    orders::post_event(
        app.clone(),
        trip.order_id,
        trip.request_id,
        "delivered",
        format!("{} delivered the order", trip.courier_name),
    )
    .await;
}

async fn mark_ready(
    State(app): State<Arc<App>>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Result<(StatusCode, Json<serde_json::Value>), ApiError> {
    let rid = request_id(&headers);
    let (order_id, status) = {
        let mut fleet = app.fleet.lock().unwrap();
        let Some(d) = fleet.deliveries.get_mut(&id) else {
            return Err(api_error(StatusCode::NOT_FOUND, format!("unknown delivery {id}")));
        };
        if !d.ready {
            d.ready = true;
            d.updated_at = log::now_rfc3339();
            d.ready_signal.notify_one();
        }
        (d.order_id.clone(), d.status.as_str())
    };
    log::info(
        "food ready for pickup",
        &[
            ("delivery_id", id.clone()),
            ("order_id", order_id),
            ("delivery_status", status.to_string()),
            ("request_id", rid),
        ],
    );
    Ok((
        StatusCode::ACCEPTED,
        Json(json!({ "delivery_id": id, "status": status, "ready": true })),
    ))
}

async fn cancel_delivery(
    State(app): State<Arc<App>>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<DeliveryView>, ApiError> {
    let rid = request_id(&headers);
    let mut fleet = app.fleet.lock().unwrap();
    let Some(d) = fleet.deliveries.get_mut(&id) else {
        return Err(api_error(StatusCode::NOT_FOUND, format!("unknown delivery {id}")));
    };
    match d.status {
        DeliveryStatus::Delivered => {
            return Err(api_error(StatusCode::CONFLICT, "delivery already delivered"));
        }
        DeliveryStatus::Cancelled => {}
        _ => {
            if let Some(task) = d.task.take() {
                task.abort();
            }
            d.status = DeliveryStatus::Cancelled;
            d.updated_at = log::now_rfc3339();
            let index = d.courier;
            let order_id = d.order_id.clone();
            fleet.free_courier(index);
            app.stats.cancelled.fetch_add(1, Ordering::Relaxed);
            log::info(
                "delivery cancelled",
                &[
                    ("delivery_id", id.clone()),
                    ("order_id", order_id),
                    ("courier", fleet.couriers[index].name.clone()),
                    ("request_id", rid),
                ],
            );
        }
    }
    let d = &fleet.deliveries[&id];
    Ok(Json(fleet.delivery_view(d)))
}

async fn get_delivery(State(app): State<Arc<App>>, Path(id): Path<String>) -> Result<Json<DeliveryView>, ApiError> {
    let fleet = app.fleet.lock().unwrap();
    match fleet.deliveries.get(&id) {
        Some(d) => Ok(Json(fleet.delivery_view(d))),
        None => Err(api_error(StatusCode::NOT_FOUND, format!("unknown delivery {id}"))),
    }
}

#[derive(Serialize)]
struct CouriersResponse {
    couriers: Vec<CourierView>,
    hops: Vec<Hop>,
}

async fn couriers(State(app): State<Arc<App>>) -> Json<CouriersResponse> {
    let start = Instant::now();
    let fleet = app.fleet.lock().unwrap();
    let couriers = (0..fleet.couriers.len()).map(|i| fleet.courier_view(i)).collect();
    Json(CouriersResponse {
        couriers,
        hops: vec![app.ident.hop(start)],
    })
}

async fn stats(State(app): State<Arc<App>>) -> Json<serde_json::Value> {
    let fleet = app.fleet.lock().unwrap();
    let count = |s: CourierStatus| fleet.couriers.iter().filter(|c| c.status == s).count();
    let active = fleet
        .deliveries
        .values()
        .filter(|d| !matches!(d.status, DeliveryStatus::Delivered | DeliveryStatus::Cancelled))
        .count();
    Json(json!({
        "instance": app.ident.instance,
        "deliveries_created": app.stats.deliveries_created.load(Ordering::Relaxed),
        "delivered": app.stats.delivered.load(Ordering::Relaxed),
        "cancelled": app.stats.cancelled.load(Ordering::Relaxed),
        "rejected_no_courier": app.stats.rejected_no_courier.load(Ordering::Relaxed),
        "active_deliveries": active,
        "estimates": app.stats.estimates.load(Ordering::Relaxed),
        "couriers": {
            "total": fleet.couriers.len(),
            "idle": count(CourierStatus::Idle),
            "to_pickup": count(CourierStatus::ToPickup),
            "waiting": count(CourierStatus::Waiting),
            "to_dropoff": count(CourierStatus::ToDropoff),
        },
    }))
}

async fn info(State(app): State<Arc<App>>) -> Json<Info> {
    Json(app.ident.info())
}

async fn access_log(req: Request, next: Next) -> Response {
    let start = Instant::now();
    let method = req.method().to_string();
    let path = req.uri().path().to_string();
    let rid = request_id(req.headers());
    let resp = next.run(req).await;
    if path != "/healthz" {
        log::info(
            "http",
            &[
                ("method", method),
                ("path", path),
                ("status", resp.status().as_u16().to_string()),
                ("ms", format!("{:.1}", start.elapsed().as_secs_f64() * 1000.0)),
                ("request_id", rid),
            ],
        );
    }
    resp
}

async fn not_found(req: Request) -> impl IntoResponse {
    api_error(
        StatusCode::NOT_FOUND,
        format!("unknown route {} {}", req.method(), req.uri().path()),
    )
}

async fn shutdown_signal() {
    let ctrl_c = tokio::signal::ctrl_c();
    let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        .expect("install SIGTERM handler");
    tokio::select! {
        _ = ctrl_c => {},
        _ = term.recv() => {},
    }
    log::info("shutting down", &[]);
}

#[tokio::main]
async fn main() {
    let ident = Identity::from_env("dispatch", "dsp", 8084);
    let time_scale = identity::env("TIME_SCALE")
        .and_then(|v| v.parse::<f64>().ok())
        .filter(|v| *v > 0.0)
        .unwrap_or(1.0);
    let fleet_size = identity::env("COURIERS")
        .and_then(|v| v.parse::<usize>().ok())
        .unwrap_or(6);

    let spectral = match Client::new(Config::default()) {
        Ok(client) => client,
        Err(err) => {
            log::error("spectral client", &[("error", err.to_string())]);
            std::process::exit(1);
        }
    };
    let http: ureq::Agent = ureq::Agent::config_builder()
        .http_status_as_error(false)
        .timeout_global(Some(Duration::from_secs(3)))
        .build()
        .into();

    let listener = match tokio::net::TcpListener::bind(("0.0.0.0", ident.port)).await {
        Ok(listener) => listener,
        Err(err) => {
            log::error("listen", &[("port", ident.port.to_string()), ("error", err.to_string())]);
            std::process::exit(1);
        }
    };

    identity::spawn_registration(spectral.clone(), &ident, ident.service.clone());
    identity::spawn_registration(spectral.clone(), &ident, format!("{}/{}", ident.service, ident.instance));
    log::info(
        "listening",
        &[
            ("port", ident.port.to_string()),
            ("endpoint", ident.endpoint.clone()),
            ("instance", ident.instance.clone()),
            ("couriers", fleet_size.to_string()),
            ("time_scale", time_scale.to_string()),
        ],
    );

    let app = Arc::new(App {
        fleet: Mutex::new(Fleet::new(&ident.instance, fleet_size)),
        ident,
        spectral,
        http,
        time_scale,
        stats: Stats::default(),
    });

    let router = Router::new()
        .route("/v1/estimates", post(estimates))
        .route("/v1/deliveries", post(create_delivery))
        .route("/v1/deliveries/{id}", get(get_delivery))
        .route("/v1/deliveries/{id}/ready", post(mark_ready))
        .route("/v1/deliveries/{id}/cancel", post(cancel_delivery))
        .route("/v1/couriers", get(couriers))
        .route("/v1/stats", get(stats))
        .route("/info", get(info))
        .route("/healthz", get(|| async { "ok" }))
        .fallback(not_found)
        .layer(middleware::from_fn(access_log))
        .with_state(app);

    axum::serve(listener, router)
        .with_graceful_shutdown(shutdown_signal())
        .await
        .expect("serve");
}
