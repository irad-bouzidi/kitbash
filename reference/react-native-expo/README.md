# demo-mobile

## The stack

- **Mobile** — React Native with Expo, in `mobile/`.
<!-- kitbash:stack -->

## Start it

### The mobile app

```bash
cd mobile
npm install
npm start        # then press i or a, or scan the QR code with Expo Go
```

The API base URL is `expo.extra.apiBaseUrl` in `mobile/app.json`, not an environment
variable: a mobile app has no environment at runtime, it is a bundle on a device.
`localhost` there means the simulator's own loopback — a phone on the same network needs
the host's LAN address instead.
<!-- kitbash:start -->

## Work on it

```bash
cp .env.example .env          # then export it, or let your IDE load it
```

<!-- kitbash:work -->

## How it is laid out

<!-- kitbash:layout -->

## Rules worth knowing before editing

<!-- kitbash:rules -->
