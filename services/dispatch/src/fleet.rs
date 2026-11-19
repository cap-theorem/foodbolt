use std::collections::HashMap;
use std::sync::Arc;

use serde::{Deserialize, Serialize};
use tokio::sync::Notify;
use tokio::task::AbortHandle;

const NAMES: [&str; 12] = [
    "Maya", "Diego", "Priya", "Sam", "Jordan", "Lena", "Kenji", "Ana", "Omar", "Zoe", "Theo", "Ines",
];
const CENTER_LAT: f64 = 37.7749;
const CENTER_LNG: f64 = -122.4194;
const KM_PER_DEG_LAT: f64 = 111.0;

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct Location {
    pub lat: f64,
    pub lng: f64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Vehicle {
    Bike,
    Scooter,
    Car,
}

impl Vehicle {
    pub fn as_str(self) -> &'static str {
        match self {
            Vehicle::Bike => "bike",
            Vehicle::Scooter => "scooter",
            Vehicle::Car => "car",
        }
    }

    pub fn km_per_second(self) -> f64 {
        match self {
            Vehicle::Bike => 0.15,
            Vehicle::Scooter => 0.2,
            Vehicle::Car => 0.25,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CourierStatus {
    Idle,
    ToPickup,
    Waiting,
    ToDropoff,
}

impl CourierStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            CourierStatus::Idle => "idle",
            CourierStatus::ToPickup => "to_pickup",
            CourierStatus::Waiting => "waiting",
            CourierStatus::ToDropoff => "to_dropoff",
        }
    }
}

#[derive(Debug, Clone)]
pub struct Courier {
    pub id: String,
    pub name: String,
    pub vehicle: Vehicle,
    pub status: CourierStatus,
    pub location: Location,
    pub delivery_id: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DeliveryStatus {
    Assigned,
    AtRestaurant,
    PickedUp,
    Delivered,
    Cancelled,
}

impl DeliveryStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            DeliveryStatus::Assigned => "assigned",
            DeliveryStatus::AtRestaurant => "at_restaurant",
            DeliveryStatus::PickedUp => "picked_up",
            DeliveryStatus::Delivered => "delivered",
            DeliveryStatus::Cancelled => "cancelled",
        }
    }
}

#[derive(Debug)]
pub struct Delivery {
    pub delivery_id: String,
    pub order_id: String,
    pub status: DeliveryStatus,
    pub courier: usize,
    pub pickup: Location,
    pub dropoff: Location,
    pub eta_seconds: u64,
    pub ready: bool,
    pub request_id: String,
    pub created_at: String,
    pub updated_at: String,
    pub ready_signal: Arc<Notify>,
    pub task: Option<AbortHandle>,
}

#[derive(Debug, Clone, Serialize)]
pub struct CourierRef {
    pub id: String,
    pub name: String,
    pub vehicle: &'static str,
}

#[derive(Debug, Serialize)]
pub struct CourierView {
    pub id: String,
    pub name: String,
    pub vehicle: &'static str,
    pub status: &'static str,
    pub location: Location,
    pub delivery_id: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct DeliveryView {
    pub delivery_id: String,
    pub order_id: String,
    pub status: &'static str,
    pub courier: CourierRef,
    pub pickup: Location,
    pub dropoff: Location,
    pub eta_seconds: u64,
    pub ready: bool,
    pub created_at: String,
    pub updated_at: String,
}

pub struct Fleet {
    pub couriers: Vec<Courier>,
    pub deliveries: HashMap<String, Delivery>,
}

impl Fleet {
    pub fn new(instance: &str, size: usize) -> Fleet {
        let vehicles = [Vehicle::Bike, Vehicle::Scooter, Vehicle::Car];
        let lng_km_per_deg = KM_PER_DEG_LAT * CENTER_LAT.to_radians().cos();
        let couriers = (0..size)
            .map(|i| {
                let name = if i < NAMES.len() {
                    NAMES[i].to_string()
                } else {
                    format!("{} {}", NAMES[i % NAMES.len()], i / NAMES.len() + 1)
                };
                let angle = (i as f64 * 137.508).to_radians();
                let radius_km = 0.5 + 2.5 * ((i as f64 * 0.618_034) % 1.0);
                Courier {
                    id: format!("{instance}.c{}", i + 1),
                    name,
                    vehicle: vehicles[i % vehicles.len()],
                    status: CourierStatus::Idle,
                    location: Location {
                        lat: round(CENTER_LAT + radius_km * angle.cos() / KM_PER_DEG_LAT, 5),
                        lng: round(CENTER_LNG + radius_km * angle.sin() / lng_km_per_deg, 5),
                    },
                    delivery_id: None,
                }
            })
            .collect();
        Fleet {
            couriers,
            deliveries: HashMap::new(),
        }
    }

    pub fn nearest_idle(&self, to: Location) -> Option<usize> {
        self.couriers
            .iter()
            .enumerate()
            .filter(|(_, c)| c.status == CourierStatus::Idle)
            .min_by(|(_, a), (_, b)| {
                distance_km(a.location, to).total_cmp(&distance_km(b.location, to))
            })
            .map(|(i, _)| i)
    }

    pub fn courier_ref(&self, index: usize) -> CourierRef {
        let c = &self.couriers[index];
        CourierRef {
            id: c.id.clone(),
            name: c.name.clone(),
            vehicle: c.vehicle.as_str(),
        }
    }

    pub fn courier_view(&self, index: usize) -> CourierView {
        let c = &self.couriers[index];
        CourierView {
            id: c.id.clone(),
            name: c.name.clone(),
            vehicle: c.vehicle.as_str(),
            status: c.status.as_str(),
            location: c.location,
            delivery_id: c.delivery_id.clone(),
        }
    }

    pub fn delivery_view(&self, d: &Delivery) -> DeliveryView {
        DeliveryView {
            delivery_id: d.delivery_id.clone(),
            order_id: d.order_id.clone(),
            status: d.status.as_str(),
            courier: self.courier_ref(d.courier),
            pickup: d.pickup,
            dropoff: d.dropoff,
            eta_seconds: d.eta_seconds,
            ready: d.ready,
            created_at: d.created_at.clone(),
            updated_at: d.updated_at.clone(),
        }
    }

    pub fn free_courier(&mut self, index: usize) {
        let c = &mut self.couriers[index];
        c.status = CourierStatus::Idle;
        c.delivery_id = None;
    }
}

pub fn distance_km(a: Location, b: Location) -> f64 {
    let r = 6371.0;
    let dlat = (b.lat - a.lat).to_radians();
    let dlng = (b.lng - a.lng).to_radians();
    let h = (dlat / 2.0).sin().powi(2)
        + a.lat.to_radians().cos() * b.lat.to_radians().cos() * (dlng / 2.0).sin().powi(2);
    2.0 * r * h.sqrt().asin()
}

pub fn travel_seconds(from: Location, to: Location, vehicle: Vehicle, time_scale: f64) -> f64 {
    (distance_km(from, to) / vehicle.km_per_second()).max(3.0) * time_scale
}

pub fn round(value: f64, places: i32) -> f64 {
    let factor = 10f64.powi(places);
    (value * factor).round() / factor
}
