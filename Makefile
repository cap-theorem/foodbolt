.PHONY: up down build logs ps directory restaurants order track couriers traffic

GATEWAY ?= localhost:8080
TOKEN ?= fb_test_alice

up:
	docker compose up -d --build

down:
	docker compose down

build:
	docker compose build

logs:
	docker compose logs -f --tail=50

ps:
	docker compose ps

directory:
	@curl -s localhost:8000/debug/directory | python3 -m json.tool

restaurants:
	@curl -s $(GATEWAY)/v1/restaurants -H 'authorization: Bearer $(TOKEN)' | python3 -m json.tool

order:
	@curl -s $(GATEWAY)/v1/orders \
		-H 'authorization: Bearer $(TOKEN)' \
		-H 'content-type: application/json' \
		-d '{"restaurant_id":"tacos-el-rayo","items":[{"item_id":"al-pastor-taco","quantity":3},{"item_id":"horchata","quantity":1}],"tip_cents":300}' \
		| python3 -m json.tool

track:
	@while true; do \
		status=$$(curl -s $(GATEWAY)/v1/orders/$(ID) -H 'authorization: Bearer $(TOKEN)' | python3 -c 'import sys,json; o=json.load(sys.stdin); print(o.get("status") or o)'); \
		echo "$$(date +%T) $$status"; \
		case "$$status" in delivered|cancelled|payment_failed|*error*) break;; esac; \
		sleep 2; \
	done

couriers:
	@curl -s $(GATEWAY)/v1/couriers -H 'authorization: Bearer $(TOKEN)' | python3 -m json.tool

traffic:
	@while true; do \
		for t in fb_test_alice fb_test_bob fb_test_carol fb_test_dave; do \
			curl -s -o /dev/null -w "$$t %{http_code}\n" $(GATEWAY)/v1/orders \
				-H "authorization: Bearer $$t" -H 'content-type: application/json' \
				-d '{"restaurant_id":"golden-wok","items":[{"item_id":"orange-chicken","quantity":1}],"tip_cents":200}'; \
		done; \
		sleep 1; \
	done
