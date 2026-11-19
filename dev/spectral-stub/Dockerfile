FROM golang:1.27 AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -o /out/spectral-stub .

FROM gcr.io/distroless/static-debian13:nonroot
COPY --from=build /out/spectral-stub /spectral-stub
EXPOSE 8000
ENTRYPOINT ["/spectral-stub"]
