# `react-native-expo`

A working Expo app with one screen: list, create and delete the example entity against the
backend's API. `mobile-react-native-expo` is extracted from it.

## What this proves, and what it does not

The mobile cells run `npm ci`, `tsc --noEmit`, `jest` and `expo export`. That is: the TypeScript
compiles, the screen renders and handles its three states, and Metro produces a Hermes bundle.

It does **not** prove the app launches on a device, that a gesture works, or that anything is laid
out correctly. Those need a device or an emulator, and the matrix has neither — §45 asks for that
boundary to be explicit rather than implied by a green badge.

## Running it

```bash
npm install
npm start        # then press i, a, or scan the QR code with Expo Go
npm test
```

The API base URL is `expo.extra.apiBaseUrl` in `app.json`, not an environment variable: a mobile
app has no environment at runtime, it is a bundle on a device. `localhost` in that field means the
simulator's own loopback, which is the machine running the simulator — a phone on the same network
needs the host's LAN address instead.

## Two pins worth knowing about

`react-test-renderer` is pinned to React's **exact** version. It reaches into React's internals,
and `@testing-library/react-native` otherwise pulls the newest one, which wants a React newer than
Expo's — npm then refuses the tree outright.

`expo-constants` is a dependency rather than something Expo brings in transitively, because
`app.json`'s `extra` is read through it.
