from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class MenuItem:
    item_id: str
    name: str
    description: str
    price_cents: int
    available: bool = True

    def to_json(self) -> dict:
        return {
            "item_id": self.item_id,
            "name": self.name,
            "description": self.description,
            "price_cents": self.price_cents,
            "available": self.available,
        }


@dataclass(frozen=True)
class Restaurant:
    id: str
    name: str
    cuisine: str
    rating: float
    open: bool
    lat: float
    lng: float
    prep_seconds: int
    delivery_fee_cents: int
    menu: tuple[MenuItem, ...]

    def location(self) -> dict:
        return {"lat": self.lat, "lng": self.lng}

    def summary(self) -> dict:
        return {
            "id": self.id,
            "name": self.name,
            "cuisine": self.cuisine,
            "rating": self.rating,
            "open": self.open,
            "location": self.location(),
            "prep_seconds": self.prep_seconds,
            "delivery_fee_cents": self.delivery_fee_cents,
        }

    def detail(self) -> dict:
        body = self.summary()
        body["menu"] = [item.to_json() for item in self.menu]
        return body

    def item(self, item_id: str) -> MenuItem | None:
        for item in self.menu:
            if item.item_id == item_id:
                return item
        return None


RESTAURANTS: tuple[Restaurant, ...] = (
    Restaurant(
        id="tacos-el-rayo",
        name="Tacos El Rayo",
        cuisine="mexican",
        rating=4.7,
        open=True,
        lat=37.7599,
        lng=-122.4148,
        prep_seconds=12,
        delivery_fee_cents=199,
        menu=(
            MenuItem("al-pastor-taco", "Al Pastor Taco", "Spit-roasted pork, pineapple, onion and cilantro on a corn tortilla", 450),
            MenuItem("carnitas-burrito", "Carnitas Burrito", "Slow-braised pork, rice, pinto beans, salsa roja and crema", 1295),
            MenuItem("veggie-quesadilla", "Veggie Quesadilla", "Oaxaca cheese, grilled peppers, mushrooms and poblano rajas", 1095),
            MenuItem("chips-guac", "Chips & Guacamole", "Fresh tortilla chips with hand-mashed guacamole", 795),
            MenuItem("horchata", "Horchata", "Cinnamon rice milk, made in house", 450),
        ),
    ),
    Restaurant(
        id="golden-wok",
        name="Golden Wok",
        cuisine="chinese",
        rating=4.4,
        open=True,
        lat=37.7941,
        lng=-122.4078,
        prep_seconds=15,
        delivery_fee_cents=249,
        menu=(
            MenuItem("orange-chicken", "Orange Chicken", "Crispy chicken tossed in a sweet orange glaze", 1395),
            MenuItem("beef-broccoli", "Beef & Broccoli", "Flank steak and broccoli in oyster sauce", 1495),
            MenuItem("kung-pao-tofu", "Kung Pao Tofu", "Wok-fried tofu, peanuts, chilies and Sichuan pepper", 1295),
            MenuItem("pork-dumplings", "Pork Dumplings", "Six pan-fried dumplings with black vinegar dip", 895),
            MenuItem("yang-chow-fried-rice", "Yang Chow Fried Rice", "Egg fried rice with char siu and shrimp", 1195),
            MenuItem("hot-sour-soup", "Hot & Sour Soup", "Tofu, bamboo shoots and wood ear mushrooms", 695),
        ),
    ),
    Restaurant(
        id="slice-house",
        name="Slice House",
        cuisine="pizza",
        rating=4.5,
        open=True,
        lat=37.7837,
        lng=-122.4090,
        prep_seconds=18,
        delivery_fee_cents=299,
        menu=(
            MenuItem("margherita-pie", "Margherita Pie", "San Marzano tomato, fresh mozzarella and basil, 14 inch", 1995),
            MenuItem("pepperoni-pie", "Pepperoni Pie", "Cup-and-char pepperoni and mozzarella, 14 inch", 2195),
            MenuItem("truffle-pie", "Truffle Pie", "Wild mushrooms, fontina and black truffle oil, 14 inch", 2495, available=False),
            MenuItem("garlic-knots", "Garlic Knots", "Six knots brushed with garlic butter and parmesan", 695),
            MenuItem("caesar-salad", "Caesar Salad", "Romaine, croutons, parmesan and anchovy dressing", 995),
        ),
    ),
    Restaurant(
        id="sakura-ramen",
        name="Sakura Ramen",
        cuisine="japanese",
        rating=4.8,
        open=True,
        lat=37.7853,
        lng=-122.4294,
        prep_seconds=14,
        delivery_fee_cents=249,
        menu=(
            MenuItem("tonkotsu-ramen", "Tonkotsu Ramen", "Rich pork bone broth, chashu, ajitama egg and scallion", 1695),
            MenuItem("spicy-miso-ramen", "Spicy Miso Ramen", "Miso broth with chili oil, ground pork and corn", 1695),
            MenuItem("shoyu-ramen", "Shoyu Ramen", "Clear soy chicken broth, chashu, menma and nori", 1595),
            MenuItem("chicken-karaage", "Chicken Karaage", "Japanese fried chicken with kewpie mayo", 995),
            MenuItem("gyoza", "Gyoza", "Five pan-fried pork and cabbage dumplings", 795),
            MenuItem("matcha-mochi", "Matcha Mochi", "Two matcha ice cream mochi", 595),
        ),
    ),
    Restaurant(
        id="green-bowl",
        name="Green Bowl",
        cuisine="salads",
        rating=4.3,
        open=True,
        lat=37.7890,
        lng=-122.4010,
        prep_seconds=8,
        delivery_fee_cents=149,
        menu=(
            MenuItem("harvest-bowl", "Harvest Bowl", "Kale, roasted sweet potato, chicken, apple and goat cheese", 1495),
            MenuItem("mediterranean-bowl", "Mediterranean Bowl", "Quinoa, falafel, cucumber, tomato and tahini", 1395),
            MenuItem("cobb-salad", "Cobb Salad", "Romaine, bacon, egg, avocado, blue cheese and chicken", 1495),
            MenuItem("tofu-poke-bowl", "Tofu Poke Bowl", "Marinated tofu, brown rice, edamame and sesame", 1295),
            MenuItem("green-juice", "Green Juice", "Cold-pressed kale, cucumber, apple and lemon", 750),
        ),
    ),
    Restaurant(
        id="burger-district",
        name="Burger District",
        cuisine="burgers",
        rating=4.6,
        open=True,
        lat=37.7694,
        lng=-122.4469,
        prep_seconds=10,
        delivery_fee_cents=199,
        menu=(
            MenuItem("district-burger", "District Burger", "Double smash patty, american cheese, pickles and house sauce", 1395),
            MenuItem("mushroom-swiss", "Mushroom Swiss Burger", "Grass-fed patty, sauteed mushrooms and swiss", 1495),
            MenuItem("impossible-burger", "Impossible Burger", "Plant-based patty, lettuce, tomato and vegan aioli", 1495),
            MenuItem("crispy-chicken-sandwich", "Crispy Chicken Sandwich", "Buttermilk fried chicken, slaw and hot honey", 1295),
            MenuItem("fries", "Fries", "Hand-cut fries with sea salt", 495),
            MenuItem("chocolate-shake", "Chocolate Shake", "Thick chocolate milkshake", 695),
        ),
    ),
    Restaurant(
        id="pho-saigon",
        name="Pho Saigon",
        cuisine="vietnamese",
        rating=4.5,
        open=False,
        lat=37.7840,
        lng=-122.4180,
        prep_seconds=11,
        delivery_fee_cents=199,
        menu=(
            MenuItem("pho-dac-biet", "Pho Dac Biet", "Combination beef noodle soup with rare steak, brisket and tendon", 1595),
            MenuItem("chicken-pho", "Chicken Pho", "Poached chicken in a ginger chicken broth", 1395),
            MenuItem("banh-mi", "Grilled Pork Banh Mi", "Lemongrass pork, pickled daikon, carrot and cilantro", 1095),
            MenuItem("spring-rolls", "Spring Rolls", "Two fresh rolls with shrimp and peanut sauce", 795),
            MenuItem("vietnamese-iced-coffee", "Vietnamese Iced Coffee", "Dark roast with sweetened condensed milk", 550),
        ),
    ),
    Restaurant(
        id="curry-leaf",
        name="Curry Leaf",
        cuisine="indian",
        rating=4.6,
        open=True,
        lat=37.7510,
        lng=-122.4200,
        prep_seconds=16,
        delivery_fee_cents=249,
        menu=(
            MenuItem("chicken-tikka-masala", "Chicken Tikka Masala", "Tandoori chicken in a creamy tomato sauce", 1695),
            MenuItem("lamb-rogan-josh", "Lamb Rogan Josh", "Kashmiri lamb curry with whole spices", 1895),
            MenuItem("palak-paneer", "Palak Paneer", "Paneer in spiced spinach puree", 1495),
            MenuItem("chana-masala", "Chana Masala", "Chickpeas in an onion tomato gravy", 1295),
            MenuItem("garlic-naan", "Garlic Naan", "Tandoor-baked flatbread with garlic and butter", 450),
            MenuItem("mango-lassi", "Mango Lassi", "Yogurt and Alphonso mango", 550),
        ),
    ),
)

BY_ID: dict[str, Restaurant] = {r.id: r for r in RESTAURANTS}


def service_fee_cents(subtotal_cents: int) -> int:
    return (subtotal_cents + 5) // 10


def tax_cents(subtotal_cents: int) -> int:
    return (subtotal_cents * 8625 + 50000) // 100000
