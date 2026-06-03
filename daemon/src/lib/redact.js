const SECRET_PATTERNS = [
  /\b(?:authorization|cookie|set-cookie)\s*:\s*[^\r\n]+/gi,
  /\bBearer\s+[A-Za-z0-9._~+/=-]{12,}\b/gi,
  /\b(sk|sk-proj|sess|pat|ghp|github_pat)_[A-Za-z0-9_=-]{16,}\b/g,
  /\b[A-Za-z0-9_-]{24,}\.[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\b/g,
  /\b(?:api[_-]?key|token|password|passwd|secret)\s*[:=]\s*["']?[^"',\s}]{6,}/gi,
];

const EMAIL_PATTERN = /\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi;
const WINDOWS_USER_PATH_PATTERN = /\b[A-Z]:\\Users\\[^\\\s]+(?:\\[^\r\n\t"'<>|]*)?/gi;
const POSIX_USER_PATH_PATTERN = /(^|[\s"'(])((?:\/Users|\/home)\/[^\/\s]+(?:\/[^\s"'<>]*)?)/g;

export function maskEmail(value) {
  return String(value || '').replace(EMAIL_PATTERN, (email) => {
    const [local, domain] = email.split('@');
    if (!domain) return '<email>';
    const head = local.slice(0, Math.min(2, local.length));
    return `${head}***@${domain}`;
  });
}

export function redactText(value, options = {}) {
  let out = String(value || '');
  for (const pattern of SECRET_PATTERNS) {
    out = out.replace(pattern, (match) => {
      if (/^(authorization|cookie|set-cookie)\s*:/i.test(match)) {
        return `${match.split(':')[0]}: <redacted>`;
      }
      if (/^Bearer\s+/i.test(match)) return 'Bearer <redacted>';
      const key = match.split(/[:=]/)[0];
      return match.includes('=') || match.includes(':') ? `${key}=<redacted>` : '<redacted-secret>';
    });
  }
  if (options.maskEmails) out = maskEmail(out);
  if (options.maskLocalPaths) out = maskLocalPaths(out);
  return out;
}

export function maskLocalPaths(value) {
  return String(value || '')
    .replace(WINDOWS_USER_PATH_PATTERN, '<local-path>')
    .replace(POSIX_USER_PATH_PATTERN, (_, prefix) => `${prefix}<local-path>`);
}

export function truncateText(value, max = 4000) {
  const text = String(value || '');
  if (text.length <= max) return text;
  return `${text.slice(0, max)}\n...已截断 ${text.length - max} 字符`;
}
