export interface Address {
  lat: number;
  lng: number;
  address: string;
}

export interface Customer {
  id: string;
  name: string;
  address: Address;
  paymentMethod: string;
}

const CUSTOMERS: Record<string, Customer> = {
  fb_test_alice: {
    id: "cus_alice",
    name: "Alice",
    address: { lat: 37.7936, lng: -122.3958, address: "1 Market St" },
    paymentMethod: "pm_card_visa",
  },
  fb_test_bob: {
    id: "cus_bob",
    name: "Bob",
    address: { lat: 37.7596, lng: -122.4269, address: "Dolores Park" },
    paymentMethod: "pm_card_mastercard",
  },
  fb_test_carol: {
    id: "cus_carol",
    name: "Carol",
    address: { lat: 37.7694, lng: -122.4862, address: "Golden Gate Park" },
    paymentMethod: "pm_card_amex",
  },
  fb_test_dave: {
    id: "cus_dave",
    name: "Dave",
    address: { lat: 37.7544, lng: -122.4477, address: "Twin Peaks" },
    paymentMethod: "pm_card_declined",
  },
};

export function authenticate(header: string | undefined): Customer | undefined {
  if (!header) return undefined;
  const match = /^Bearer\s+(\S+)$/i.exec(header.trim());
  if (!match) return undefined;
  return CUSTOMERS[match[1]!];
}

export function profile(customer: Customer) {
  return {
    customer_id: customer.id,
    name: customer.name,
    address: customer.address,
    payment_method: customer.paymentMethod,
  };
}
