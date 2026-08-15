# Expo and Pact checkpoint

Requires Node 22.13 or later for the pinned Expo SDK 57.

```bash
npm ci
npm run typecheck
npm run test:contract
```

For a live Web demo:

1. Generate `VALID_MOBILE_TOKEN` with `../gradlew generateDemoTokens --quiet`.
2. Paste only that short-lived local token into `src/auth/classroomToken.ts`; never commit it.
3. Start the backend with `../gradlew bootRun`.
4. Run `EXPO_PUBLIC_BETTING_API_URL=http://localhost:8080 npm run web`.

For local provider verification, keep the backend running and run `npm run verify:provider`. The verifier creates a short-lived local training token itself; `PACT_BEARER_TOKEN` remains available when a facilitator deliberately wants to override it.
