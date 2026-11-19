import uvicorn

from restaurants import logs


def main() -> None:
    logs.setup()
    from restaurants.app import app, ident

    uvicorn.run(app, host="0.0.0.0", port=ident.port, access_log=False, log_config=None)


if __name__ == "__main__":
    main()
