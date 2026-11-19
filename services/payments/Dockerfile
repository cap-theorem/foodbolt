FROM golang:1.27 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -o /out/payments .

FROM gcr.io/distroless/static-debian13:nonroot
COPY --from=build /out/payments /payments
EXPOSE 8083
ENTRYPOINT ["/payments"]
