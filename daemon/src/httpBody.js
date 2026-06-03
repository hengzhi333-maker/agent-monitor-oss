export const DEFAULT_JSON_BODY_LIMIT_BYTES = 256 * 1024;

export async function readJsonBody(req, limitBytes = DEFAULT_JSON_BODY_LIMIT_BYTES) {
  const raw = await readLimitedBody(req, limitBytes);
  if (!raw.trim()) return {};
  try {
    return JSON.parse(raw);
  } catch {
    throw apiError('INVALID_JSON', 'Request body must be valid JSON.', 400);
  }
}

export async function readRawBody(req, limitBytes) {
  return Buffer.from(await readLimitedBody(req, limitBytes, { encoding: null }));
}

async function readLimitedBody(req, limitBytes, options = {}) {
  const limit = normalizeLimit(limitBytes);
  const declared = Number(req.headers?.['content-length'] || 0);
  if (Number.isFinite(declared) && declared > limit) {
    throw apiError('REQUEST_TOO_LARGE', 'Request body is too large.', 413);
  }

  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    total += buffer.length;
    if (total > limit) throw apiError('REQUEST_TOO_LARGE', 'Request body is too large.', 413);
    chunks.push(buffer);
  }

  const body = Buffer.concat(chunks);
  return options.encoding === null ? body : body.toString(options.encoding || 'utf8');
}

function normalizeLimit(value) {
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0) return DEFAULT_JSON_BODY_LIMIT_BYTES;
  return Math.floor(n);
}

function apiError(code, message, status = 500) {
  const error = new Error(message);
  error.code = code;
  error.status = status;
  return error;
}
