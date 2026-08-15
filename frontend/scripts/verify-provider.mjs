import path from 'node:path';
import crypto from 'node:crypto';
import { Verifier } from '@pact-foundation/pact';

const brokerUrl = process.env.PACT_BROKER_BASE_URL;
const providerVersion = process.env.GIT_COMMIT ?? 'local-provider';
const bearerToken = process.env.PACT_BEARER_TOKEN ?? createTrainingToken();
const source = brokerUrl
  ? {
      pactBrokerUrl: brokerUrl,
      pactBrokerToken: process.env.PACT_BROKER_TOKEN,
      consumerVersionSelectors: [{ mainBranch: true }, { deployedOrReleased: true }],
    }
  : {
      pactUrls: [path.resolve(process.cwd(), 'pacts/betting-expo-betting-api.json')],
    };

await new Verifier({
  provider: 'betting-api',
  providerBaseUrl: process.env.PROVIDER_BASE_URL ?? 'http://localhost:8080',
  providerVersion,
  providerVersionBranch: process.env.GIT_BRANCH ?? 'local',
  publishVerificationResult: Boolean(brokerUrl && process.env.PACT_BROKER_TOKEN),
  requestFilter: (request, _response, next) => {
    request.headers.authorization = `Bearer ${bearerToken}`;
    next();
  },
  stateHandlers: {
    'game G-100 is open for betting': async () => undefined,
  },
  ...source,
}).verifyProvider();

function createTrainingToken() {
  const now = Math.floor(Date.now() / 1000);
  const header = encode({ alg: 'HS256', typ: 'JWT' });
  const payload = encode({
    iss: 'hkjc-training-local',
    sub: 'pact-provider-verifier',
    iat: now,
    exp: now + 600,
    scope: 'bets:write',
  });
  const unsigned = `${header}.${payload}`;
  const signature = crypto
    .createHmac('sha256', 'hkjc-training-local-secret-key-32-bytes')
    .update(unsigned)
    .digest('base64url');
  return `${unsigned}.${signature}`;
}

function encode(value) {
  return Buffer.from(JSON.stringify(value)).toString('base64url');
}
