# Issue: react-leaflet peer dep conflict with React 19

**Date:** 2026-05-30  
**Service:** robo-services-registry UI  
**Severity:** Medium — blocked UI build; required React downgrade

---

## Symptom

`npm install` failed with a peer dependency conflict:

```
npm error ERESOLVE unable to resolve dependency tree
npm error peer react@"^18" from react-leaflet@4.2.1
npm error Found: react@19.1.0
```

The initial `package.json` was scaffolded with React 19. `react-leaflet` was added for the
map view on the Tracks page.

## Root Cause

`react-leaflet` 4.x declares `peerDependencies: { react: "^18" }`. It does not support
React 19. There is no React 19-compatible release of react-leaflet 4.x as of this date.
react-leaflet 5.x (which would support React 19) is in early development and not stable.

## Resolution

Downgraded to React 18.3.1 in `package.json`:
```json
"react": "^18.3.1",
"react-dom": "^18.3.1",
"@types/react": "^18.3.1",
"@types/react-dom": "^18.3.1"
```

Deleted `node_modules` and `package-lock.json`, re-ran `npm install`. Build succeeded.

## Prevention

When adding map functionality to a React project, check react-leaflet's peer dep requirements
before choosing the React version. For new services in this stack, default to React 18 until
react-leaflet publishes a React 19-compatible release.
